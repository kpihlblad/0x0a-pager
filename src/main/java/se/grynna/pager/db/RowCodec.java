package se.grynna.pager.db;

import com.esotericsoftware.kryo.util.Pool;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compresses and encrypts one MVStore page (or one single stored value) of the
 * log store.
 *
 * <p>H2's file encryption is not used: it is a pure-Java AES implementation without
 * AES-NI intrinsics that is applied to every page read. The {@link LoadedLogLineDataType}
 * serializes a whole page with Kryo, compresses it (zlib, {@link Deflater#BEST_SPEED}) and seals
 * it with JCE {@code AES/GCM/NoPadding}, which uses the hardware intrinsics.
 *
 * <p>Format of a sealed blob:
 * <pre>
 *   nonce (12 bytes) || AES-GCM ciphertext (framed payload + 16-byte tag)
 *   framed payload   = mode (1 byte) || uncompressed length (4 bytes) || bytes
 * </pre>
 * A fresh random nonce is generated for every write. GCM also authenticates the
 * data, so a wrong key or a corrupted page fails to open instead of producing
 * garbage. The nonce is public; the key is derived by {@link CryptoUtils}.
 */
final class RowCodec {

    /** Version of the log-store payload written by this class. */
    static final int FORMAT_VERSION = 1;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = Byte.BYTES + Integer.BYTES;
    private static final int MIN_SEALED_BYTES = NONCE_BYTES + TAG_BITS / Byte.SIZE + HEADER_BYTES;

    private static final byte STORED = 0;
    private static final byte COMPRESSED = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** {@link Deflater} and {@link Inflater} are stateful, so they are pooled rather than shared. */
    private static final Pool<Deflater> DEFLATERS = new Pool<>(true, false, 16) {
        @Override
        protected Deflater create() {
            return new Deflater(Deflater.BEST_SPEED, true);
        }

        @Override
        protected void reset(Deflater deflater) {
            deflater.reset();
        }
    };

    private static final Pool<Inflater> INFLATERS = new Pool<>(true, false, 16) {
        @Override
        protected Inflater create() {
            return new Inflater(true);
        }

        @Override
        protected void reset(Inflater inflater) {
            inflater.reset();
        }
    };

    private final SecretKey key;

    RowCodec(SecretKey key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /**
     * Compresses and encrypts the first {@code length} bytes of {@code data}.
     *
     * @return the sealed blob: nonce, ciphertext and GCM tag
     */
    byte[] seal(byte[] data, int length) {
        byte[] framed = frame(data, length);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(framed);
            byte[] sealed = new byte[NONCE_BYTES + ciphertext.length];
            System.arraycopy(nonce, 0, sealed, 0, NONCE_BYTES);
            System.arraycopy(ciphertext, 0, sealed, NONCE_BYTES, ciphertext.length);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        } finally {
            Arrays.fill(framed, (byte) 0);
        }
    }

    /**
     * Decrypts and decompresses a blob written by {@link #seal}.
     *
     * @throws IllegalStateException when the key is wrong or the data is corrupt
     */
    byte[] open(byte[] sealed) {
        if (sealed.length < MIN_SEALED_BYTES) {
            throw new IllegalStateException("Log store row is truncated (" + sealed.length + " bytes)");
        }
        byte[] nonce = Arrays.copyOf(sealed, NONCE_BYTES);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] framed = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
            try {
                return unframe(framed);
            } finally {
                Arrays.fill(framed, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Log store data cannot be decrypted (wrong password or corrupt log store)", e);
        }
    }

    /** Whether {@code sealed} opens to exactly {@code expected}; used for the wrong-password check value. */
    boolean opensTo(byte[] sealed, byte[] expected) {
        try {
            return Arrays.equals(open(sealed), expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] frame(byte[] data, int length) {
        boolean compressed = false;
        byte[] compressedBytes = null;

        try {
            if (length > 0) {
                compressedBytes = deflate(data, length);
                compressed = compressedBytes.length < length;
            }

            int payloadLength = compressed ? compressedBytes.length : length;
            byte[] framed = new byte[HEADER_BYTES + payloadLength];
            framed[0] = compressed ? COMPRESSED : STORED;
            ByteBuffer.wrap(framed).putInt(1, length);
            if (compressed) {
                System.arraycopy(compressedBytes, 0, framed, HEADER_BYTES, compressedBytes.length);
            } else if (length > 0) {
                System.arraycopy(data, 0, framed, HEADER_BYTES, length);
            }
            return framed;
        } finally {
            if (compressedBytes != null) {
                Arrays.fill(compressedBytes, (byte) 0);
            }
        }
    }

    private static byte[] unframe(byte[] framed) {
        if (framed.length < HEADER_BYTES) {
            throw new IllegalStateException("Log store row has no header");
        }
        byte mode = framed[0];
        int length = ByteBuffer.wrap(framed).getInt(1);
        if (length < 0) {
            throw new IllegalStateException("Log store row has a negative length");
        }
        if (mode == STORED) {
            if (HEADER_BYTES + length > framed.length) {
                throw new IllegalStateException("Log store row is truncated");
            }
            return Arrays.copyOfRange(framed, HEADER_BYTES, HEADER_BYTES + length);
        }
        if (mode != COMPRESSED) {
            throw new IllegalStateException("Log store row has an unknown compression mode " + mode);
        }
        return inflate(framed, HEADER_BYTES, framed.length - HEADER_BYTES, length);
    }

    private static byte[] deflate(byte[] data, int length) {
        // zlib's worst case is length + length / 1000 + 12; leave some headroom.
        byte[] out = new byte[length + (length >> 3) + 64];
        Deflater deflater = DEFLATERS.obtain();
        try {
            deflater.setInput(data, 0, length);
            deflater.finish();
            int written = deflater.deflate(out);
            if (written == out.length) {
                throw new IllegalStateException("Deflater output exceeded the reserved buffer");
            }
            return Arrays.copyOf(out, written);
        } finally {
            DEFLATERS.free(deflater);
        }
    }

    private static byte[] inflate(byte[] data, int offset, int length, int uncompressedLength) {
        byte[] out = new byte[uncompressedLength];
        Inflater inflater = INFLATERS.obtain();
        try {
            inflater.setInput(data, offset, length);
            int total = 0;
            while (total < uncompressedLength) {
                int written = inflater.inflate(out, total, uncompressedLength - total);
                if (written == 0) {
                    break;
                }
                total += written;
            }
            if (total != uncompressedLength) {
                throw new IllegalStateException("Log store row is truncated after decompression");
            }
            return out;
        } catch (DataFormatException e) {
            throw new IllegalStateException("Log store row cannot be decompressed", e);
        } finally {
            INFLATERS.free(inflater);
        }
    }
}
