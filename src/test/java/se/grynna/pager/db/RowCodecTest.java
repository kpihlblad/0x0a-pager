package se.grynna.pager.db;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowCodecTest {

    private static final SecretKey KEY = CryptoUtils.deriveAesKey("password", "host:user");
    private static final SecretKey OTHER_KEY = CryptoUtils.deriveAesKey("other-password", "host:user");
    private static final RowCodec CODEC = new RowCodec(KEY);

    @Test
    void roundTripsCompressibleData() {
        byte[] data = "{\"message\":\"the same line repeated\"}".repeat(200).getBytes(StandardCharsets.UTF_8);

        byte[] sealed = CODEC.seal(data, data.length);

        assertThat(sealed.length).isLessThan(data.length);
        assertThat(CODEC.open(sealed)).isEqualTo(data);
    }

    @Test
    void roundTripsIncompressibleData() {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);

        byte[] sealed = CODEC.seal(data, data.length);

        assertThat(CODEC.open(sealed)).isEqualTo(data);
    }

    @Test
    void roundTripsEmptyData() {
        byte[] sealed = CODEC.seal(new byte[0], 0);

        assertThat(CODEC.open(sealed)).isEmpty();
    }

    @Test
    void usesAFreshNonceForEveryWrite() {
        byte[] data = "same input".getBytes(StandardCharsets.UTF_8);

        byte[] first = CODEC.seal(data, data.length);
        byte[] second = CODEC.seal(data, data.length);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void anotherKeyCannotOpenTheData() {
        byte[] data = "secret log line".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = CODEC.seal(data, data.length);

        assertThat(new RowCodec(OTHER_KEY).opensTo(sealed, data)).isFalse();
        assertThatThrownBy(() -> new RowCodec(OTHER_KEY).open(sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedDataCannotBeOpened() {
        byte[] data = "secret log line".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = CODEC.seal(data, data.length);
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> CODEC.open(sealed)).isInstanceOf(IllegalStateException.class);
    }
}
