package se.grynna.pager.db;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import se.grynna.pager.exceptions.DatabaseLockedException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.splunk.FieldSummary;
import se.grynna.pager.splunk.LoadedLogLine;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages per-user per-server persistent storage using two MVStore files:
 *
 * <ul>
 *   <li><b>configStore</b> (unencrypted, per host+user): indexes, field filters,
 *       field summaries, preferences (lastIndex, highlight patterns, etc).
 *       This data is not highly sensitive — the machine is assumed to have disk
 *       encryption and OS login protection.</li>
 *   <li><b>logStore</b> (rows compressed and encrypted with a password-derived
 *       key, per page): actual log segments. If the Splunk password changes, the
 *       old rows cannot be decrypted and the log store is cleared automatically —
 *       only cached log lines are lost, not configuration.</li>
 * </ul>
 *
 * Each host+user combination gets its own pair of files (filename derived from
 * SHA-256 of host:username), so switching between users never causes data leakage.
 */
@Slf4j
public class DatabaseManager {

    public static final String FIELDS = "fields_";
    public static final String CONFIG = "config";
    private static final int VERSION = 21;
    public static final String FIELD_FILTERS = "fieldFilters";
    public static final String INDEXES = "indexes";
    public static final String SEGMENT = "segment_";

    /** Log-store meta map: format version and the wrong-password check value. */
    static final String LOG_META = "log_meta";
    static final int LOG_STORE_FORMAT_VERSION = RowCodec.FORMAT_VERSION;
    static final byte[] LOG_STORE_CHECK = "0x0a-pager-log-store-check-v1".getBytes(StandardCharsets.UTF_8);

    /**
     * MVStore page cache of the log store in MB (H2's default is 16); an estimate
     * sized to hold a large segment. Whether H2 keeps cached pages encoded or
     * deserialized is not verified; the cache demonstrably reduces disk reads.
     */
    static final int LOG_STORE_CACHE_MB = 256;

    // Log-store file only — cleared when the key or row format changes; rows are compressed and encrypted per page
    private final MVStore logStore;

    // Unencrypted per-user config store — survives password changes
    private final MVStore configStore;

    private final MVMap.Builder<Integer, LoadedLogLine> mapConfig;

    // Indexes, field filters, field summaries, config all live in the config store
    private MVMap<String, FieldFilters> fieldFiltersMap;
    private MVMap<String, ConfigValue> configMap;
    private MVMap<String, SortedSet<String>> indexMap;

    // Cache for log segment maps (stored in the per-page encrypted logStore)
    private final ConcurrentHashMap<String, MVMap<Integer, LoadedLogLine>> segmentCache = new ConcurrentHashMap<>();

    /**
     * Creates a DatabaseManager with two separate stores per host+user:
     * - configStore: Unencrypted store for indexes, filters, field summaries, preferences
     * - logStore: Compressed and per-page encrypted store for actual log segment data
     *
     * @param host     The Splunk host
     * @param username The username
     * @param password The user's password (used for key derivation for logStore encryption)
     * @param baseDir  The base directory for database files
     * @throws StorageException if database creation fails
     */
    public DatabaseManager(String host, String username, String password, File baseDir) throws StorageException {
        this(host, username, password, baseDir, null);
    }

    /**
     * Test seam: {@code afterOpen} runs once both stores are open and their maps
     * are wired; it is null in production and may throw to simulate a post-open
     * failure (e.g. an MVStoreException on commit).
     */
    DatabaseManager(String host, String username, String password, File baseDir, Consumer<DatabaseManager> afterOpen)
            throws StorageException {
        if (host == null || username == null || password == null) {
            throw new IllegalArgumentException("Host, username, and password cannot be null");
        }
        if (baseDir == null) {
            throw new IllegalArgumentException("Base directory cannot be null");
        }

        // Unique filenames per host+user combination
        String secureFilename = CryptoUtils.createSecureFilename(host, username);
        String configFilename = secureFilename.replace(".db", "_config.db");

        File logDbFile;
        File configDbFile;
        String logDbName;
        String configDbName;
        try {
            logDbFile = new File(baseDir, secureFilename).getCanonicalFile();
            configDbFile = new File(baseDir, configFilename).getCanonicalFile();
            logDbName = logDbFile.getCanonicalPath();
            configDbName = configDbFile.getCanonicalPath();
        } catch (IOException e) {
            throw new StorageException("Failed opening database", e);
        }

        log.info("Creating database for user {}@{}", username, host);
        log.info("  Log store (encrypted rows): {} exists={}", logDbName, logDbFile.exists());
        log.info("  Config store (per-user): {} exists={}", configDbName, configDbFile.exists());

        // Derive the log-store key from the password. The config store stays plain.
        String salt = CryptoUtils.createSalt(host, username);
        SecretKey logStoreKey = CryptoUtils.deriveAesKey(password, salt);
        RowCodec rowCodec = new RowCodec(logStoreKey);

        MVStore openedConfigStore = null;
        MVStore openedLogStore = null;
        try {
            // Config store: unencrypted, holds indexes/filters/preferences — survives password changes
            openedConfigStore = openStoreWithRecovery(configDbFile, configDbName);

            try {
                // Log store: rows are compressed and encrypted per page, so the H2 file
                // itself is not encrypted. A key change is detected by the check value.
                openedLogStore = openLogStoreWithRecovery(logDbFile, logDbName, rowCodec);
            } catch (RuntimeException | IOException | StorageException e) {
                // Never leak the config store's file lock when the log store cannot be opened
                closeQuietly(openedConfigStore);
                throw e;
            }

            // The final fields are assigned before the post-open initialisation
            // because its helpers (getConfigInt, setConfigInt, ...) read them.
            configStore = openedConfigStore;
            logStore = openedLogStore;
            mapConfig = new MVMap.Builder<Integer, LoadedLogLine>()
                    .valueType(new LoadedLogLineDataType(rowCodec));

            try {
                configMap = configStore.openMap(CONFIG);
                fieldFiltersMap = configStore.openMap(FIELD_FILTERS);
                indexMap = configStore.openMap(INDEXES);

                // Version check applies to config store schema
                int dbVersion = getConfigInt("dbVersion", 0);
                if (dbVersion != VERSION) {
                    log.info("Database version mismatch (found {}, expected {}). Clearing stores.", dbVersion, VERSION);
                    clearAllConfigStoreMaps();
                    clearLogStoreMaps(logStore);
                    ensureLogStoreFormat(logStore, rowCodec);
                    // Re-open maps after clearing since removeMap invalidates references
                    configMap = configStore.openMap(CONFIG);
                    fieldFiltersMap = configStore.openMap(FIELD_FILTERS);
                    indexMap = configStore.openMap(INDEXES);
                }

                setConfigInt("dbVersion", VERSION);

                if (afterOpen != null) {
                    afterOpen.accept(this);
                }
            } catch (RuntimeException e) {
                // Any failure after the stores are open (e.g. MVStoreException on
                // commit or a full disk; the helpers below wrap their errors in
                // RuntimeException) must release both file locks, otherwise a retry in
                // this process fails with a file-locked error.
                segmentCache.clear();
                closeQuietly(openedLogStore);
                closeQuietly(openedConfigStore);
                throw e;
            }

            log.info("Successfully created databases for {}@{}", username, host);

        } catch (IOException e) {
            throw new StorageException("Failed opening database", e);
        }
    }

    /**
     * Opens the unencrypted config store with recovery on corruption.
     * Contention and access errors are propagated instead — a locked file means
     * another instance is still using it, so it must never be deleted.
     */
    private MVStore openStoreWithRecovery(File dbFile, String dbName) throws IOException, StorageException {
        try {
            return openConfigStore(dbName);
        } catch (RuntimeException e) {
            throwIfUnrecoverable(dbName, e);
            log.warn("Failed to open store {}. Deleting and recreating.", dbName, e);
            deleteFileIfExists(dbFile, dbName);
            return openConfigStore(dbName);
        }
    }

    /**
     * Opens the log store and validates its format and check value. A corruption
     * failure while opening or validating is recovered by deleting the file —
     * only cached log segments are lost. A password change is not an open failure,
     * because the file itself is not encrypted: it is detected by the encrypted check
     * value and clears only the log store. Contention and access errors are propagated;
     * such files must never be deleted.
     */
    private MVStore openLogStoreWithRecovery(File dbFile, String dbName, RowCodec codec)
            throws IOException, StorageException {
        try {
            MVStore store = openLogStore(dbName);
            try {
                ensureLogStoreFormat(store, codec);
                return store;
            } catch (RuntimeException e) {
                closeQuietly(store);
                throw e;
            }
        } catch (RuntimeException e) {
            throwIfUnrecoverable(dbName, e);
            log.warn("Log store failed to open or validate for {}. Deleting and recreating.", dbName, e);
            deleteFileIfExists(dbFile, dbName);
            MVStore store = openLogStore(dbName);
            ensureLogStoreFormat(store, codec);
            return store;
        }
    }

    private static void closeQuietly(MVStore store) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (RuntimeException closeFailure) {
            log.warn("Failed to close database store", closeFailure);
        }
    }

    /**
     * Makes sure the log store belongs to the current row format and can be read
     * with the current key. A format mismatch (an old store) or a failed check
     * value (password change) clears the log store's maps; the config store is
     * never touched.
     */
    private static void ensureLogStoreFormat(MVStore store, RowCodec codec) {
        MVMap<String, Object> meta = store.openMap(LOG_META);
        Object format = meta.get("format");
        Object check = meta.get("check");
        boolean current = Integer.valueOf(LOG_STORE_FORMAT_VERSION).equals(format)
                && check instanceof byte[] sealed
                && codec.opensTo(sealed, LOG_STORE_CHECK);
        if (current) {
            return;
        }
        if (Integer.valueOf(LOG_STORE_FORMAT_VERSION).equals(format)) {
            log.info("Log store cannot be read with the current key (password change?). Clearing cached log segments.");
        } else {
            log.info("Log store format {} is not current ({}). Clearing cached log segments.", format,
                    LOG_STORE_FORMAT_VERSION);
        }
        clearLogStoreMaps(store);
        MVMap<String, Object> freshMeta = store.openMap(LOG_META);
        freshMeta.put("format", LOG_STORE_FORMAT_VERSION);
        freshMeta.put("check", codec.seal(LOG_STORE_CHECK, LOG_STORE_CHECK.length));
        store.commit();
    }

    /**
     * Propagates open failures that must never trigger file deletion: contention
     * with another live instance and filesystem access errors.
     */
    private static void throwIfUnrecoverable(String dbName, RuntimeException e) throws StorageException {
        if (isFileLockFailure(e)) {
            throw new DatabaseLockedException(
                    "Database is locked by another 0x0A Pager instance (or a session still shutting down): " + dbName,
                    e);
        }
        if (isAccessFailure(e)) {
            throw new StorageException("Cannot access database file: " + dbName, e);
        }
    }

    /**
     * Detects MVStore file-lock failures: H2 reports them as MVStoreException with
     * error code {@link DataUtils#ERROR_FILE_LOCKED}, with OverlappingFileLockException
     * as cause when the lock is held by this same JVM.
     */
    private static boolean isFileLockFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OverlappingFileLockException) {
                return true;
            }
            if (t instanceof MVStoreException mve && mve.getErrorCode() == DataUtils.ERROR_FILE_LOCKED) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAccessFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AccessDeniedException) {
                return true;
            }
        }
        return false;
    }

    private void deleteFileIfExists(File dbFile, String dbName) throws IOException {
        if (dbFile.exists() && !dbFile.delete()) {
            throw new IOException("Failed to delete database file: " + dbName);
        }
    }

    /** The config store is compressed by H2 and holds no log rows. */
    private MVStore openConfigStore(String dbName) {
        return new MVStore.Builder().fileName(dbName).compress().open();
    }

    /**
     * The log store itself is not compressed or encrypted by H2: rows are
     * compressed and sealed per page by {@link LoadedLogLineDataType}. Only the
     * page cache matters for it.
     */
    private MVStore openLogStore(String dbName) {
        return new MVStore.Builder().fileName(dbName).cacheSize(LOG_STORE_CACHE_MB).open();
    }

    /**
     * MB of MVStore page cache on the log store. The config store keeps
     * H2's default.
     */
    int logStoreCacheSizeMB() {
        return logStore.getCacheSize();
    }

    /**
     * Clears all maps from the log store, including its format meta map. Only
     * used while opening; {@code segmentCache} is still empty then.
     */
    private static void clearLogStoreMaps(MVStore store) {
        for (String name : new ArrayList<>(store.getMapNames())) {
            store.removeMap(name);
            log.info("Removed map {} from log store", name);
        }
    }

    /**
     * Clears all maps from the config store (used during version migration).
     * After calling this the field references (configMap, fieldFiltersMap, indexMap)
     * are stale — re-open them via configStore.openMap().
     */
    private void clearAllConfigStoreMaps() {
        configStore.getMapNames().forEach(name -> {
            configStore.removeMap(name);
            log.info("Removed map {} from config store", name);
        });
    }

    public DatabaseManager(String host, String name, String pass) throws StorageException {
        this(host, name, pass, getOsDependentBaseDir());
    }

    private static File getOsDependentBaseDir() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home");
        File baseDir;

        if (osName.contains("win")) {
            // Windows: Use APPDATA environment variable or fallback to user.home\AppData\Local
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null && !appData.isEmpty()) {
                baseDir = new File(appData, "0x0a-pager");
            } else {
                baseDir = new File(userHome, "AppData\\Local\\0x0a-pager");
            }
        } else if (osName.contains("mac")) {
            // macOS: Use ~/Library/Application Support/0x0a-pager
            baseDir = new File(userHome, "Library/Application Support/0x0a-pager");
        } else {
            // Linux/Unix: Use XDG_DATA_HOME or fallback to ~/.local/share/0x0a-pager
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome != null && !xdgDataHome.isEmpty()) {
                baseDir = new File(xdgDataHome, "0x0a-pager");
            } else {
                baseDir = new File(userHome, ".local/share/0x0a-pager");
            }
        }

        // Create the directory if it doesn't exist
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                log.error("Failed to create application data directory: {}. Falling back to temp directory",
                        baseDir.getAbsolutePath());
                // Fallback to temp directory if we can't create the preferred location
                baseDir = new File(System.getProperty("java.io.tmpdir"), "0x0a-pager");
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    throw new RuntimeException("Failed to create fallback directory: " + baseDir.getAbsolutePath());
                }
            }
        }

        return baseDir;
    }

    public void clearSegmentCache() {
        // Clear segment maps from the encrypted log store
        Set<String> mapNames = logStore.getMapNames();

        for (String name : mapNames) {
            if (name.startsWith(SEGMENT)) {
                segmentCache.remove(name);
                logStore.removeMap(name);
                log.info("clearSegmentCache: removed old segment {}", name);
            }
        }
    }

    public void insertLogsBatch(String segmentId, List<LoadedLogLine> logs) throws StorageException {

        if (segmentId == null) {
            throw new StorageException("Segment is null");
        }
        if (logs == null) {
            throw new StorageException("Logs are null");
        }

        try {

            if (logs.isEmpty()) {
                log.debug("insertLogsBatch: no logs to insert into segment {}", segmentId);
                return;
            }

            var segmentCacheName = SEGMENT + segmentId;

            MVMap<Integer, LoadedLogLine> lines = segmentCache.computeIfAbsent(segmentCacheName,
                    k -> logStore.openMap(k, mapConfig));

            log.debug("insertLogsBatch: inserting {} ({}-{}) into segment {}", logs.size(), logs.getFirst().line(),
                    logs.getLast().line(), segmentCacheName);
            for (LoadedLogLine logLine : logs) {
                lines.put(logLine.line(), logLine);
            }
        } catch (Exception ex) {
            log.error("Error inserting logs into segment {}", segmentId, ex);
            throw new StorageException("Error inserting logs into segment " + segmentId, ex);
        }
    }

    public LoadedLogLine load(String segmentId, int lineNo) {

        var segmentCacheName = SEGMENT + segmentId;

        MVMap<Integer, LoadedLogLine> lines = segmentCache.computeIfAbsent(segmentCacheName, segmentMap -> {
            var t0 = System.currentTimeMillis();
            // The same MVMap.Builder as inserts: the value type is part of the map's
            // configuration and an openMap without it would use ObjectDataType.
            MVMap<Integer, LoadedLogLine> map = logStore.openMap(segmentMap, mapConfig);
            var t = System.currentTimeMillis() - t0;
            log.debug("Loading segment {} took {} ms", segmentMap, t);
            return map;
        });

        var result = lines.get(lineNo);
        log.trace("load: loaded {}", lineNo);
        if (result == null) {
            throw new IllegalStateException(
                    "Log line not found in segment " + segmentCacheName + " for lineNo " + lineNo);
        }
        return result;
    }

    /**
     * Get field filters for a specific index.
     * Returns a map of field name to filter value.
     */
    public Map<String, String> loadFieldFiltersForIndex(String index) throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return Map.of();
        }

        try {
            FieldFilters fieldFilters = fieldFiltersMap.get("index." + index);
            if (fieldFilters != null) {
                log.debug("Retrieved {} field filters for index {}", fieldFilters.size(), index);
                return new TreeMap<>(fieldFilters.getFilters());
            }
            log.debug("No field filters found for index {}", index);
            return Map.of();
        } catch (Exception e) {
            throw new StorageException("Error loading field filters for index " + index, e);
        }
    }

    /**
     * Set field filters for a specific index.
     * Persists a map of field name to filter value using object serialization.
     * The index's saved scope terms are kept; the record is removed only when no
     * field filter and no text term remain.
     */
    public synchronized void storeFieldFiltersForIndex(String index, Map<String, String> filters)
            throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return;
        }

        try {
            FieldFilters existing = fieldFiltersMap.get("index." + index);
            ScopeTerms terms = existing != null ? existing.terms() : ScopeTerms.EMPTY;
            saveScopeRecord(index, new FieldFilters(filters, terms, messageFieldsOf(existing)));
        } catch (Exception e) {
            throw new StorageException("Error storing field filters for index " + index, e);
        }
    }

    /**
     * Get the message fields of a specific index: the ordered JSON field paths whose
     * values make up an event's message. Empty when the index has none saved, which
     * means the default message fields.
     */
    public Optional<List<String>> loadMessageFieldsForIndex(String index) throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(messageFieldsOf(fieldFiltersMap.get("index." + index)));
        } catch (Exception e) {
            throw new StorageException("Error loading message fields for index " + index, e);
        }
    }

    /**
     * Set the message fields of a specific index; null restores the default message
     * fields. The index's field filters and scope terms are kept.
     */
    public synchronized void storeMessageFieldsForIndex(String index, List<String> fieldsOrNullForDefault)
            throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return;
        }

        try {
            FieldFilters existing = fieldFiltersMap.get("index." + index);
            Map<String, String> filters = existing != null ? existing.getFilters() : Map.of();
            ScopeTerms terms = existing != null ? existing.terms() : ScopeTerms.EMPTY;
            saveScopeRecord(index, new FieldFilters(filters, terms, fieldsOrNullForDefault));
        } catch (Exception e) {
            throw new StorageException("Error storing message fields for index " + index, e);
        }
    }

    private static List<String> messageFieldsOf(FieldFilters record) {
        return record != null ? record.getMessageFieldsOrNull() : null;
    }

    /**
     * Get the saved narrowing and excluded terms of a specific index. Records written
     * before terms existed read as empty.
     */
    public ScopeTerms loadScopeTermsForIndex(String index) throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return ScopeTerms.EMPTY;
        }

        try {
            FieldFilters fieldFilters = fieldFiltersMap.get("index." + index);
            return fieldFilters != null ? fieldFilters.terms() : ScopeTerms.EMPTY;
        } catch (Exception e) {
            throw new StorageException("Error loading scope terms for index " + index, e);
        }
    }

    /**
     * Set the narrowing and excluded terms of a specific index. The index's field
     * filters are kept; the record is removed only when no field filter and no text
     * term remain.
     */
    public synchronized void storeScopeTermsForIndex(String index, ScopeTerms terms) throws StorageException {
        if (index == null || index.trim().isEmpty()) {
            return;
        }

        try {
            FieldFilters existing = fieldFiltersMap.get("index." + index);
            Map<String, String> filters = existing != null ? existing.getFilters() : Map.of();
            saveScopeRecord(index, new FieldFilters(filters, terms, messageFieldsOf(existing)));
        } catch (Exception e) {
            throw new StorageException("Error storing scope terms for index " + index, e);
        }
    }

    private void saveScopeRecord(String index, FieldFilters record) {
        if (record.isEmpty()) {
            fieldFiltersMap.remove("index." + index);
            log.debug("Removed empty scope settings for index {}", index);
        } else {
            fieldFiltersMap.put("index." + index, record);
            log.debug("Stored {} field filters, {} narrow and {} exclude terms for index {}", record.size(),
                    record.getNarrow().size(), record.getExclude().size(), index);
        }
    }

    /**
     * Get the last selected index.
     */
    public String getLastIndex() {
        return getConfigValue("lastIndex", "");
    }

    /**
     * Set the last selected index.
     */
    public void setLastIndex(String lastIndex) {
        setConfigValue("lastIndex", lastIndex);
    }

    /**
     * Get configuration value.
     */
    public String getConfigValue(String key, String defaultValue) {
        try {
            ConfigValue configValue = configMap.get(key);
            return configValue != null ? configValue.getStringValue(defaultValue) : defaultValue;
        } catch (Exception e) {
            throw new RuntimeException("Error retrieving config value for key " + key, e);
        }
    }

    /**
     * Set configuration value.
     */
    public void setConfigValue(String key, String value) {
        try {
            if (value == null) {
                configMap.remove(key);
            } else {
                configMap.put(key, new ConfigValue(value));
            }
            log.debug("Set config value {} = {}", key, value);
        } catch (Exception e) {
            throw new RuntimeException("Error setting config value for key " + key, e);
        }
    }

    /**
     * Get configuration integer value.
     */
    public int getConfigInt(String key, int defaultValue) {
        try {
            ConfigValue configValue = configMap.get(key);
            return configValue != null ? configValue.getIntValue(defaultValue) : defaultValue;
        } catch (Exception e) {
            throw new RuntimeException("Error retrieving config int value for key " + key, e);
        }
    }

    /**
     * Set configuration integer value.
     */
    public void setConfigInt(String key, int value) {
        try {
            configMap.put(key, new ConfigValue(value));
            log.debug("Set config int value {} = {}", key, value);
        } catch (Exception e) {
            throw new RuntimeException("Error setting config int value for key " + key, e);
        }
    }

    // Default patterns for highlighting important fields in the filter selector
    private static final String DEFAULT_HIGHLIGHT_PATTERNS = "*_pod_name,*_container_name,*_app_name";

    /**
     * Get the field highlight patterns (comma-separated glob patterns).
     * Fields matching these patterns are visually highlighted in the filter selector.
     */
    public String getFieldHighlightPatterns() {
        return getConfigValue("fieldHighlightPatterns", DEFAULT_HIGHLIGHT_PATTERNS);
    }

    /**
     * Set the field highlight patterns (comma-separated glob patterns).
     * Example: "*_pod_name,*_container_name,service*"
     */
    public void setFieldHighlightPatterns(String patterns) {
        setConfigValue("fieldHighlightPatterns", patterns);
    }

    public void close() {
        logStore.close();
        configStore.close();
    }

    public void storeIndexes(SortedSet<String> indexes) throws StorageException {
        if (indexes == null) {
            throw new IllegalArgumentException("Indexes cannot be null");
        }
        try {
            indexMap.put("allIndexes", indexes);
            log.debug("Stored {} indexes", indexes.size());
        } catch (Exception e) {
            throw new StorageException("Error storing indexes", e);
        }
    }

    /**
     * Get all stored indexes.
     */
    public SortedSet<String> loadIndexes() {
        return indexMap.getOrDefault("allIndexes", new TreeSet<String>());
    }

    /**
     * Calculates the total size in bytes of all files in the base directory.
     * This includes the database files and any other files in the application directory.
     *
     * @return the total size in bytes of all files in the base directory
     */
    public static long calculateBaseDirTotalSize() {
        File baseDir = getOsDependentBaseDir();
        return FileUtils.sizeOfDirectory(baseDir);
    }

    /**
     * Gets a human-readable string representation of the base directory size.
     *
     * @return formatted size string (e.g., "1.5 MB", "512 KB", "2.3 GB")
     */
    public static String getBaseDirSizeFormatted() {
        long sizeInBytes = calculateBaseDirTotalSize();
        return FileUtils.byteCountToDisplaySize(sizeInBytes);
    }

    public void storeFieldSummaries(String index, SortedSet<FieldSummary> result) {

        MVMap<String, FieldSummary> fields = configStore.openMap(FIELDS + index);

        // add or update entries
        for (FieldSummary fs : result) {
            fields.put(fs.getField(), fs);
        }
    }

    public SortedSet<FieldSummary> loadFieldSummaries(String index) {
        MVMap<String, FieldSummary> fields = configStore.openMap(FIELDS + index);

        return new TreeSet<>(fields.values());
    }
}
