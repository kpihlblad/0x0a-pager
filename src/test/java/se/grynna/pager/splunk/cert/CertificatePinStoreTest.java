package se.grynna.pager.splunk.cert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertificatePinStoreTest {

    private Preferences node;
    private CertificatePinStore store;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("se/grynna/pager/tests/" + UUID.randomUUID());
        store = new CertificatePinStore(node);
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void storedPinIsFound() {
        assertThat(store.findPin("localhost", 8089)).isEmpty();

        store.storePin("localhost", 8089, "AA:BB");

        assertThat(store.findPin("localhost", 8089)).contains("AA:BB");
    }

    @Test
    void pinsAreScopedPerEndpoint() {
        store.storePin("a", 1, "X");
        store.storePin("b", 1, "Y");
        store.storePin("a", 2, "Z");

        assertThat(store.findPin("a", 1)).contains("X");
        assertThat(store.findPin("b", 1)).contains("Y");
        assertThat(store.findPin("a", 2)).contains("Z");
    }

    @Test
    void pinCanBeReplacedAndRemoved() {
        store.storePin("localhost", 8089, "X");
        store.storePin("localhost", 8089, "Y");
        assertThat(store.findPin("localhost", 8089)).contains("Y");

        store.removePin("localhost", 8089);

        assertThat(store.findPin("localhost", 8089)).isEmpty();
    }

    @Test
    void longHostnamePinCanBeStoredFoundAndRemoved() throws Exception {
        // 58 host characters at port 8089 already exceeds Preferences.MAX_KEY_LENGTH
        // when the key is the raw host. A valid name past that must still round-trip.
        String host = "a".repeat(50) + ".example.com";
        assertThat(host).hasSizeGreaterThanOrEqualTo(58);

        store.storePin(host, 8089, "AA:BB");

        assertThat(store.findPin(host, 8089)).contains("AA:BB");
        assertThat(node.keys()).isNotEmpty().allSatisfy(
                key -> assertThat(key).hasSizeLessThanOrEqualTo(Preferences.MAX_KEY_LENGTH).doesNotContain(host));

        store.removePin(host, 8089);

        assertThat(store.findPin(host, 8089)).isEmpty();
    }

    @Test
    void normalizedEndpointsShareAPin() {
        store.storePin("LocalHost", 8089, "AA");

        assertThat(store.findPin("localhost", 8089)).contains("AA");
        assertThat(store.findPin(" localhost. ", 8089)).contains("AA");

        store.storePin("[::1]", 8089, "BB");

        assertThat(store.findPin("::1", 8089)).contains("BB");
        assertThat(store.findPin("0:0:0:0:0:0:0:1", 8089)).contains("BB");
        assertThat(store.findPin("localhost", 8089)).contains("AA");
    }

    @Test
    void blankHostIsRejected() {
        assertThatThrownBy(() -> store.storePin("  ", 8089, "AA")).isInstanceOf(IllegalArgumentException.class);
    }
}
