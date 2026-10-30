package se.grynna.pager.db;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

@Slf4j
public class CryptoUtils {

    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 100000; // Industry standard iterations
    private static final int KEY_LENGTH_BITS = 256; // 256-bit key for AES-256

    /**
     * Derives a 256-bit AES key from a password using PBKDF2. The derived bytes
     * are copied into a {@link SecretKeySpec} and zeroed before returning, so the
     * key is not retained in a second, unmanaged buffer.
     *
     * @param password The user's password
     * @param salt A unique salt (host+username combination)
     * @return A 256-bit AES key for the log store
     */
    public static SecretKey deriveAesKey(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8),
                    PBKDF2_ITERATIONS, KEY_LENGTH_BITS);

            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            byte[] derivedKey = factory.generateSecret(spec).getEncoded();

            try {
                log.debug("Derived encryption key using PBKDF2 with {} iterations", PBKDF2_ITERATIONS);
                return new SecretKeySpec(derivedKey, "AES");
            } finally {
                spec.clearPassword();
                Arrays.fill(derivedKey, (byte) 0);
            }

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Failed to derive encryption key", e);
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    /**
     * Creates a secure filename line from host and username.
     * This ensures each user/host combination gets a unique database file.
     *
     * @param host The Splunk host
     * @param username The username
     * @return A secure line suitable for use as a filename
     */
    public static String createSecureFilename(String host, String username) {
        try {
            String combined = host + ":" + username;
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            String filename = "pager_" + hexString.toString() + ".db";
            log.debug("Generated secure filename for {}:{} -> {}", host, username, filename);
            return filename;

        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to create secure filename", e);
            throw new RuntimeException("Failed to create secure filename", e);
        }
    }

    /**
     * Creates a salt for key derivation from host and username.
     * This ensures the same password on different hosts/users produces different keys.
     *
     * @param host The Splunk host
     * @param username The username
     * @return A salt string for key derivation
     */
    public static String createSalt(String host, String username) {
        // Use host:username as salt - this ensures different keys for different users/hosts
        // Even if they use the same password
        return host + ":" + username;
    }
}
