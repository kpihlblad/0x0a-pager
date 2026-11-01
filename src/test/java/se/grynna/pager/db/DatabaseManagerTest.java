package se.grynna.pager.db;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.grynna.pager.exceptions.DatabaseLockedException;
import se.grynna.pager.exceptions.StorageException;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;
import se.grynna.pager.testutils.Fake;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.grynna.pager.splunk.Level.INFO;
import static se.grynna.pager.splunk.Level.WARNING;
import static se.grynna.pager.splunk.LoadedLogLine.MsgType.RAW;
import static se.grynna.pager.testutils.SegmentTestUtil.createTestSegment;

class DatabaseManagerTest {
    public static final int TSTSIZE = 1000;
    private DatabaseManager dbManager;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException, StorageException {
        // Create a proper temporary directory
        tempDir = Files.createTempDirectory("testDB");

        // Add shutdown hook to ensure cleanup even if test fails
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (dbManager != null) {
                    dbManager.close();
                }
                deleteDirectoryRecursively(tempDir);
            } catch (Exception e) {
                System.err.println("Error during shutdown cleanup: " + e.getMessage());
            }
        }));

        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (dbManager != null) {
            dbManager.close();
        }

        // Clean up the temporary directory and all its contents
        deleteDirectoryRecursively(tempDir);
    }

    private void deleteDirectoryRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                try (Stream<Path> paths = Files.walk(path)) {
                    paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to delete temp directory: " + e.getMessage());
        }
    }

    @Test
    public void insertLogsBatch_insertsAllLogsSuccessfully() throws StorageException {

        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);

        // generate 1000 log lines with random data
        ArrayList<LoadedLogLine> logs = new ArrayList<>(TSTSIZE);
        for (int i = 0; i < TSTSIZE; i++) {
            logs.add(new LoadedLogLine(RAW, "segment1", i - 500, "cb1", "bkt1", Instant.now(), Fake.fakeText(10, 100),
                    Instant.now(), Instant.now(), Fake.fakeText(1000, 100_000), false, INFO));
        }

        dbManager.insertLogsBatch(segment.getId(), logs);

        // Verify that logs are inserted correctly
        for (int i = 0; i < logs.size(); i++) {
            System.out.println(i);
            LoadedLogLine log = dbManager.load(segment.getId(), logs.get(i).line());
            assertThat(log.getSegmentId()).isEqualTo(logs.get(i).getSegmentId());
            assertThat(log.line()).isEqualTo(logs.get(i).line());
            assertThat(log.getCb()).isEqualTo(logs.get(i).getCb());
            assertThat(log.getTime()).isEqualTo(logs.get(i).getTime());
            assertThat(log.getMsgTime()).isEqualTo(logs.get(i).getMsgTime());
            assertThat(log.getMessage()).isEqualTo(logs.get(i).getMessage());
            assertThat(log.getRaw()).isEqualTo(logs.get(i).getRaw());
        }
    }

    @Test
    public void insertLogsBatch_keepsActivityOfEachRow() throws StorageException {
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        var activity = new Activity("node-1", "pod-0", "worker-3", null, null);
        var log = new LoadedLogLine(RAW, segment.getId(), 1, "cb1", "bk1", Instant.now(), "raw", Instant.now(),
                Instant.now(), "message", false, INFO);
        log.setActivity(activity);
        dbManager.insertLogsBatch(segment.getId(), List.of(log));

        assertThat(dbManager.load(segment.getId(), 1).getActivity()).isEqualTo(activity);
    }

    @Test
    public void insertLogsBatch_roundTripsEveryStoredField() throws StorageException {
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        Instant time = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);
        var line = new LoadedLogLine(MsgType.JSON, segment.getId(), 7, "cb-7", "bkt-7", time,
                "{\"message\":\"hello\",\"level\":\"WARNING\"}", time.minusSeconds(1), time.plusNanos(42_001),
                "hello", true, WARNING);
        line.setEvent(new TreeMap<>(Map.of("_raw", "{\"message\":\"hello\"}", "app", "cas")));
        line.setActivity(new Activity("node-1", "pod-0", "worker-3", "trace-9", "idx:src", "pod-0"));

        dbManager.insertLogsBatch(segment.getId(), List.of(line));
        dbManager.close();
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        LoadedLogLine loaded = dbManager.load(segment.getId(), 7);

        assertThat(loaded.getSegmentId()).isEqualTo(segment.getId());
        assertThat(loaded.line()).isEqualTo(7);
        assertThat(loaded.getCb()).isEqualTo("cb-7");
        assertThat(loaded.getBkt()).isEqualTo("bkt-7");
        assertThat(loaded.getSplunkTime()).isEqualTo(time);
        assertThat(loaded.getIndextime()).isEqualTo(time.minusSeconds(1));
        assertThat(loaded.getDisplayTime()).isEqualTo(time.plusNanos(42_001));
        assertThat(loaded.getMessage()).isEqualTo("hello");
        assertThat(loaded.isContinuation()).isTrue();
        assertThat(loaded.getRaw()).isEqualTo("{\"message\":\"hello\",\"level\":\"WARNING\"}");
        assertThat(loaded.getMsgType()).isEqualTo(MsgType.JSON);
        assertThat(loaded.getLevel()).isEqualTo(WARNING);
        assertThat(loaded.getEventMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of("_raw", "{\"message\":\"hello\"}", "app", "cas"));
        assertThat(loaded.getActivity())
                .isEqualTo(new Activity("node-1", "pod-0", "worker-3", "trace-9", "idx:src", "pod-0"));
    }

    @Test
    public void insertLogsBatch_roundTripsNullFields() throws StorageException {
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        Instant time = Instant.now();
        var line = new LoadedLogLine(RAW, segment.getId(), 3, null, null, time, null, null, null, null, false, INFO);

        dbManager.insertLogsBatch(segment.getId(), List.of(line));
        dbManager.close();
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        LoadedLogLine loaded = dbManager.load(segment.getId(), 3);

        assertThat(loaded.line()).isEqualTo(3);
        assertThat(loaded.getCb()).isNull();
        assertThat(loaded.getBkt()).isNull();
        assertThat(loaded.getSplunkTime()).isEqualTo(time);
        assertThat(loaded.getIndextime()).isNull();
        assertThat(loaded.getDisplayTime()).isNull();
        assertThat(loaded.getMessage()).isNull();
        assertThat(loaded.getRaw()).isNull();
        assertThat(loaded.getEventMap()).isNull();
        assertThat(loaded.getActivity()).isNull();
        assertThat(loaded.getLevel()).isEqualTo(INFO);
    }

    @Test
    public void logStoreFileContainsNoPlainTextRowData() throws Exception {
        String marker = "PLAINTEXT-ROW-MARKER-" + UUID.randomUUID();
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        var line = new LoadedLogLine(RAW, segment.getId(), 1, "cb1", "bk1", Instant.now(), "raw " + marker,
                Instant.now(), Instant.now(), "message " + marker, false, INFO);
        line.setEvent(new TreeMap<>(Map.of("field", marker)));
        dbManager.insertLogsBatch(segment.getId(), List.of(line));
        dbManager.close();

        String logDbName = new File(tempDir.toFile(), CryptoUtils.createSecureFilename("test", "test"))
                .getCanonicalPath();
        byte[] file = Files.readAllBytes(Path.of(logDbName));
        String asIso88591 = new String(file, StandardCharsets.ISO_8859_1);
        String asUtf8 = new String(file, StandardCharsets.UTF_8);

        assertThat(asIso88591).doesNotContain(marker);
        assertThat(asUtf8).doesNotContain(marker);
    }

    @Test
    public void oldLogStoreFormat_isClearedButConfigSurvives() throws Exception {
        dbManager.storeIndexes(new TreeSet<>(Set.of("idx_keep")));
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        var line = new LoadedLogLine(RAW, segment.getId(), 1, "cb1", "bk1", Instant.now(), "raw", Instant.now(),
                Instant.now(), "old-format-message", false, INFO);
        dbManager.insertLogsBatch(segment.getId(), List.of(line));
        dbManager.close();

        // Simulate a store written before the Kryo/AES-GCM format: no log_meta map
        // and a Java-serialized row under the default ObjectDataType.
        String logDbName = new File(tempDir.toFile(), CryptoUtils.createSecureFilename("test", "test"))
                .getCanonicalPath();
        try (MVStore oldStore = new MVStore.Builder().fileName(logDbName).open()) {
            for (String name : new ArrayList<>(oldStore.getMapNames())) {
                oldStore.removeMap(name);
            }
            oldStore.<Integer, LoadedLogLine>openMap(DatabaseManager.SEGMENT + segment.getId()).put(1, line);
            oldStore.commit();
        }

        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());

        assertThat(dbManager.loadIndexes()).containsExactly("idx_keep");
        assertThrows(IllegalStateException.class, () -> dbManager.load(segment.getId(), 1));
    }

    @Test
    public void insertLogsBatch_insertsEmptyLogsArray() throws StorageException {
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        dbManager.insertLogsBatch(segment.getId(), List.of());
    }

    @Test
    public void insertLogsBatch_throwsStorageExceptionForNullSegment() {
        var logs = List.of(new LoadedLogLine(RAW, "segment1", -1, "cb1", "bk1", Instant.now(), "raw1", Instant.now(),
                Instant.now(), "log1", false, INFO));

        assertThrows(StorageException.class, () -> dbManager.insertLogsBatch(null, logs));
    }

    @Test
    public void insertLogsBatch_throwsStorageExceptionForNullLogs() {
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        assertThrows(StorageException.class, () -> dbManager.insertLogsBatch(segment.getId(), null));
    }

    // --- Multi-user isolation tests ---

    @Test
    public void differentUsers_haveIsolatedIndexes() throws StorageException {
        // User A stores indexes
        dbManager.storeIndexes(new TreeSet<>(Set.of("index_a1", "index_a2")));
        dbManager.setLastIndex("index_a1");
        dbManager.close();

        // User B on the same host should see empty data
        var dbManagerB = new DatabaseManager("test", "userB", "passB", tempDir.toFile());
        assertThat(dbManagerB.loadIndexes()).isEmpty();
        assertThat(dbManagerB.getLastIndex()).isEmpty();

        // User B stores different indexes
        dbManagerB.storeIndexes(new TreeSet<>(Set.of("index_b1")));
        dbManagerB.setLastIndex("index_b1");
        dbManagerB.close();

        // Re-open user A — should still see A's data
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadIndexes()).containsExactly("index_a1", "index_a2");
        assertThat(dbManager.getLastIndex()).isEqualTo("index_a1");
    }

    @Test
    public void differentUsers_haveIsolatedFieldFilters() throws StorageException {
        // User A stores filters
        dbManager.storeFieldFiltersForIndex("myindex", Map.of("host", List.of("server1")));
        dbManager.close();

        // User B should see no filters
        var dbManagerB = new DatabaseManager("test", "userB", "passB", tempDir.toFile());
        assertThat(dbManagerB.loadFieldFiltersForIndex("myindex")).isEmpty();
        dbManagerB.close();

        // User A's filters still there
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadFieldFiltersForIndex("myindex")).containsEntry("host", List.of("server1"));
    }

    @Test
    public void scopeTerms_roundTripAndSurviveReopen() throws StorageException {
        assertThat(dbManager.loadScopeTermsForIndex("web")).isEqualTo(ScopeTerms.EMPTY);

        dbManager.storeScopeTermsForIndex("web", new ScopeTerms(List.of("timeout"), List.of("healthcheck")));
        dbManager.close();

        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadScopeTermsForIndex("web"))
                .isEqualTo(new ScopeTerms(List.of("timeout"), List.of("healthcheck")));
        assertThat(dbManager.loadScopeTermsForIndex("other")).isEqualTo(ScopeTerms.EMPTY);
    }

    @Test
    public void storingFieldFiltersKeepsTermsAndStoringTermsKeepsFieldFilters() throws StorageException {
        dbManager.storeScopeTermsForIndex("web", new ScopeTerms(List.of("timeout"), List.of()));
        dbManager.storeFieldFiltersForIndex("web", Map.of("host", List.of("a1", "a2")));
        assertThat(dbManager.loadScopeTermsForIndex("web").narrow()).containsExactly("timeout");
        assertThat(dbManager.loadFieldFiltersForIndex("web")).containsEntry("host", List.of("a1", "a2"));

        dbManager.storeScopeTermsForIndex("web", new ScopeTerms(List.of(), List.of("noise")));
        assertThat(dbManager.loadFieldFiltersForIndex("web")).containsEntry("host", List.of("a1", "a2"));
        assertThat(dbManager.loadScopeTermsForIndex("web"))
                .isEqualTo(new ScopeTerms(List.of(), List.of("noise")));

        // Clearing the filters keeps the terms; clearing both removes the record
        dbManager.storeFieldFiltersForIndex("web", Map.of());
        assertThat(dbManager.loadFieldFiltersForIndex("web")).isEmpty();
        assertThat(dbManager.loadScopeTermsForIndex("web").exclude()).containsExactly("noise");
        dbManager.storeScopeTermsForIndex("web", ScopeTerms.EMPTY);
        assertThat(dbManager.loadScopeTermsForIndex("web")).isEqualTo(ScopeTerms.EMPTY);
        assertThat(dbManager.loadFieldFiltersForIndex("web")).isEmpty();
    }

    @Test
    public void messageFieldsDefaultWhenNothingIsSaved() throws StorageException {
        assertThat(dbManager.loadMessageFieldsForIndex("web")).isEmpty();
        assertThat(dbManager.loadMessageFieldsForIndex(null)).isEmpty();
    }

    @Test
    public void messageFieldsRoundTripAndEmptyListIsSavedAsNoFields() throws StorageException {
        dbManager.storeMessageFieldsForIndex("web", List.of("title", "payload.text"));
        assertThat(dbManager.loadMessageFieldsForIndex("web")).contains(List.of("title", "payload.text"));
        assertThat(dbManager.loadMessageFieldsForIndex("other")).isEmpty();

        dbManager.storeMessageFieldsForIndex("web", List.of());
        assertThat(dbManager.loadMessageFieldsForIndex("web")).contains(List.of());

        dbManager.storeMessageFieldsForIndex("web", null);
        assertThat(dbManager.loadMessageFieldsForIndex("web")).isEmpty();
    }

    @Test
    public void storingMessageFieldsKeepsFiltersAndTermsAndTheOthersKeepMessageFields() throws StorageException {
        dbManager.storeFieldFiltersForIndex("web", Map.of("host", List.of("a1")));
        dbManager.storeScopeTermsForIndex("web", new ScopeTerms(List.of("timeout"), List.of("noise")));
        dbManager.storeMessageFieldsForIndex("web", List.of("title"));

        assertThat(dbManager.loadFieldFiltersForIndex("web")).containsEntry("host", List.of("a1"));
        assertThat(dbManager.loadScopeTermsForIndex("web"))
                .isEqualTo(new ScopeTerms(List.of("timeout"), List.of("noise")));
        assertThat(dbManager.loadMessageFieldsForIndex("web")).contains(List.of("title"));

        dbManager.storeFieldFiltersForIndex("web", Map.of("host", List.of("a2")));
        dbManager.storeScopeTermsForIndex("web", ScopeTerms.EMPTY);
        assertThat(dbManager.loadMessageFieldsForIndex("web")).contains(List.of("title"));
    }

    @Test
    public void scopeTermsAreIsolatedPerUser() throws StorageException {
        dbManager.storeScopeTermsForIndex("web", new ScopeTerms(List.of("secret"), List.of()));
        dbManager.close();

        var dbManagerB = new DatabaseManager("test", "userB", "passB", tempDir.toFile());
        assertThat(dbManagerB.loadScopeTermsForIndex("web")).isEqualTo(ScopeTerms.EMPTY);
        dbManagerB.close();
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
    }

    @Test
    public void passwordChange_preservesConfigAndIndexes() throws StorageException {
        // Store config data with original password
        dbManager.storeIndexes(new TreeSet<>(Set.of("prod", "staging")));
        dbManager.setLastIndex("prod");
        dbManager.storeFieldFiltersForIndex("prod", Map.of("env", List.of("production")));
        dbManager.close();

        // Re-open with a changed password — config should survive
        dbManager = new DatabaseManager("test", "test", "newpassword", tempDir.toFile());
        assertThat(dbManager.loadIndexes()).containsExactly("prod", "staging");
        assertThat(dbManager.getLastIndex()).isEqualTo("prod");
        assertThat(dbManager.loadFieldFiltersForIndex("prod")).containsEntry("env", List.of("production"));
    }

    @Test
    public void passwordChange_logSegmentsAreLost() throws StorageException {
        // Insert log segments with original password
        var segment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        var log = new LoadedLogLine(RAW, segment.getId(), 1, "cb1", "bk1", Instant.now(), "raw", Instant.now(),
                Instant.now(), "message", false, INFO);
        dbManager.insertLogsBatch(segment.getId(), List.of(log));

        // Verify it's there
        assertThat(dbManager.load(segment.getId(), 1).getMessage()).isEqualTo("message");
        dbManager.close();

        // Re-open with changed password — the old rows cannot be decrypted and are cleared
        dbManager = new DatabaseManager("test", "test", "newpassword", tempDir.toFile());
        // Log segments gone — they were sealed with the old key
        assertThrows(Exception.class, () -> dbManager.load(segment.getId(), 1));
    }

    // --- The retained key is the one the replacement store uses ---

    @Test
    public void passwordChange_replacementStoreIsEncryptedWithNewKey() throws Exception {
        // Original password: write a log segment, then close
        var oldSegment = createTestSegment(Instant.now().minusSeconds(60), Instant.now(), 0, 0);
        dbManager.insertLogsBatch(oldSegment.getId(), List.of(new LoadedLogLine(RAW, oldSegment.getId(), 1, "cb1",
                "bk1", Instant.now(), "old", Instant.now(), Instant.now(), "old-message", false, INFO)));
        dbManager.close();

        // Reopen with a changed password: the unreadable old rows are cleared. The
        // replacement must be encrypted with the *new* derived key.
        dbManager = new DatabaseManager("test", "test", "newpassword", tempDir.toFile());
        var newSegment = createTestSegment(Instant.now(), Instant.now().plusSeconds(60), 0, 0);
        dbManager.insertLogsBatch(newSegment.getId(), List.of(new LoadedLogLine(RAW, newSegment.getId(), 1, "cb1",
                "bk1", Instant.now(), "new", Instant.now(), Instant.now(), "replacement-message", false, INFO)));
        assertThat(dbManager.load(newSegment.getId(), 1).getMessage()).isEqualTo("replacement-message");
        dbManager.close();

        // The replacement store's check value opens with the new key, and not with
        // the old password's key or a zero-filled key.
        String logDbName = new File(tempDir.toFile(), CryptoUtils.createSecureFilename("test", "test"))
                .getCanonicalPath();
        SecretKey newKey = CryptoUtils.deriveAesKey("newpassword", CryptoUtils.createSalt("test", "test"));
        SecretKey oldKey = CryptoUtils.deriveAesKey("test", CryptoUtils.createSalt("test", "test"));
        SecretKey zeroKey = new SecretKeySpec(new byte[32], "AES");
        try (MVStore store = new MVStore.Builder().fileName(logDbName).open()) {
            Object check = store.<String, Object>openMap(DatabaseManager.LOG_META).get("check");
            assertThat(check).isInstanceOf(byte[].class);
            byte[] sealed = (byte[]) check;
            assertThat(new RowCodec(newKey).opensTo(sealed, DatabaseManager.LOG_STORE_CHECK)).isTrue();
            assertThat(new RowCodec(oldKey).opensTo(sealed, DatabaseManager.LOG_STORE_CHECK)).isFalse();
            assertThat(new RowCodec(zeroKey).opensTo(sealed, DatabaseManager.LOG_STORE_CHECK)).isFalse();
        }

        // Reopening with the correct new password must still find the replacement
        dbManager = new DatabaseManager("test", "test", "newpassword", tempDir.toFile());
        assertThat(dbManager.load(newSegment.getId(), 1).getMessage()).isEqualTo("replacement-message");
    }

    // --- A locked database must never be deleted ---

    @Test
    public void concurrentOpen_sameUser_failsSafelyAndPreservesData() throws StorageException {
        // Instance A owns the stores
        dbManager.storeIndexes(new TreeSet<>(Set.of("idx1")));
        dbManager.setLastIndex("idx1");
        dbManager.storeFieldFiltersForIndex("idx1", Map.of("host", List.of("h1")));

        int filesBefore = dbFileCount();

        // A second instance for the same host/user must fail — and must NOT delete anything
        assertThrows(DatabaseLockedException.class,
                () -> new DatabaseManager("test", "test", "test", tempDir.toFile()));

        // Files untouched by the failed open
        assertThat(dbFileCount()).isEqualTo(filesBefore);

        // Original instance still works
        dbManager.setConfigValue("stillAlive", "yes");
        dbManager.close();

        // After closing, everything written before the failed open must be intact
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadIndexes()).containsExactly("idx1");
        assertThat(dbManager.getLastIndex()).isEqualTo("idx1");
        assertThat(dbManager.loadFieldFiltersForIndex("idx1")).containsEntry("host", List.of("h1"));
        assertThat(dbManager.getConfigValue("stillAlive", "")).isEqualTo("yes");
    }

    @Test
    public void lockedLogStore_failsSafelyAndPreservesData() throws Exception {
        dbManager.storeIndexes(new TreeSet<>(Set.of("idx9")));
        dbManager.close();

        // Hold a lock on the log store only; the config store stays free. The H2
        // file itself is not encrypted (rows are sealed inside the value type).
        String logDbName = new File(tempDir.toFile(), CryptoUtils.createSecureFilename("test", "test"))
                .getCanonicalPath();
        MVStore lockHolder = new MVStore.Builder().fileName(logDbName).open();
        try {
            // Config store opens fine, but the locked log store must abort the open
            // without deleting anything and without leaking the config store lock
            assertThrows(DatabaseLockedException.class,
                    () -> new DatabaseManager("test", "test", "test", tempDir.toFile()));
        } finally {
            lockHolder.close();
        }

        // No leaked config-store lock and nothing deleted: a fresh open works, data intact
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadIndexes()).containsExactly("idx9");
    }

    // --- Post-open failure cleanup: the constructor must not leak a file lock ---

    @Test
    public void postOpenFailure_releasesBothStoreLocks() throws StorageException {
        dbManager.close();

        // Both stores open fine; the constructor then fails (e.g. MVStoreException on
        // commit or a full disk). Both stores must be closed on the way out.
        assertThrows(IllegalStateException.class, () -> new DatabaseManager("test", "test", "test", tempDir.toFile(),
                manager -> {
                    throw new IllegalStateException("injected post-open failure");
                }));

        // No leaked lock on either file: an immediate retry on the same
        // directory/credentials succeeds (it would be DatabaseLockedException if the
        // config store or the log store still held its lock).
        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        dbManager.setConfigValue("afterRetry", "yes");
        assertThat(dbManager.getConfigValue("afterRetry", "")).isEqualTo("yes");
    }

    private int dbFileCount() {
        File[] files = tempDir.toFile().listFiles((dir, name) -> name.startsWith("pager_") && name.endsWith(".db"));
        return files == null ? 0 : files.length;
    }

    @Test
    public void logStoreHasExplicitPageCache() throws StorageException {
        assertThat(dbManager.logStoreCacheSizeMB()).isEqualTo(DatabaseManager.LOG_STORE_CACHE_MB);
        assertThat(DatabaseManager.LOG_STORE_CACHE_MB).isGreaterThan(16); // H2 default
    }

    @Test
    public void closeAndReopen_sameUser_dataPersists() throws StorageException {
        dbManager.storeIndexes(new TreeSet<>(Set.of("idx1", "idx2", "idx3")));
        dbManager.setLastIndex("idx2");
        dbManager.setFieldHighlightPatterns("*_pod_name,*_service");
        dbManager.storeFieldFiltersForIndex("idx1", Map.of("host", List.of("h1"), "source", List.of("s1")));
        dbManager.close();

        dbManager = new DatabaseManager("test", "test", "test", tempDir.toFile());
        assertThat(dbManager.loadIndexes()).containsExactly("idx1", "idx2", "idx3");
        assertThat(dbManager.getLastIndex()).isEqualTo("idx2");
        assertThat(dbManager.getFieldHighlightPatterns()).isEqualTo("*_pod_name,*_service");
        assertThat(dbManager.loadFieldFiltersForIndex("idx1")).hasSize(2);
    }
}
