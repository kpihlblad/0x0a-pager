package se.grynna.pager.splunk.cert;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import se.grynna.pager.splunk.cert.ServerCertificate.Classification;

import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

class ServerCertificateTest {

    @Test
    void selfSignedLeafIsClassifiedSelfSigned() {
        List<X509Certificate> certs = TestCertificates.certs("self-signed-localhost-a.pem");

        ServerCertificate certificate = ServerCertificate.of("localhost", 8089, certs);

        assertThat(certificate.classification()).isEqualTo(Classification.SELF_SIGNED);
        assertThat(certificate.chain()).hasSize(1);
        assertThat(certificate.isValidNow()).isTrue();
    }

    @Test
    void certificateIssuedByUnknownCaIsClassifiedUnknownCa() {
        ServerCertificate certificate = ServerCertificate.of("splunk.example.com", 8089,
                TestCertificates.certs("unknown-ca-chain.pem"));

        assertThat(certificate.classification()).isEqualTo(Classification.UNKNOWN_CA);
        assertThat(certificate.chain()).hasSize(2);
    }

    @Test
    void loneLeafFromUnknownCaIsClassifiedUnknownCa() {
        ServerCertificate certificate = ServerCertificate.of("splunk.example.com", 8089,
                TestCertificates.certs("intercepted-leaf.pem"));

        assertThat(certificate.classification()).isEqualTo(Classification.UNKNOWN_CA);
    }

    @Test
    void sha256FingerprintIsFormattedAndStable() {
        ServerCertificate certificate = ServerCertificate.of("localhost", 8089,
                TestCertificates.certs("self-signed-localhost-a.pem"));

        String fingerprint = certificate.sha256Fingerprint();

        assertThat(fingerprint).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
        // Computing it again must yield the same value
        assertThat(certificate.sha256Fingerprint()).isEqualTo(fingerprint);
        assertThat(certificate.sha256SpkiFingerprint()).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")
                .isNotEqualTo(fingerprint);
    }

    @Test
    void differentCertificatesHaveDifferentFingerprints() {
        String a = ServerCertificate.of("localhost", 8089, TestCertificates.certs("self-signed-localhost-a.pem"))
                .sha256Fingerprint();
        String b = ServerCertificate.of("localhost", 8089, TestCertificates.certs("self-signed-localhost-b.pem"))
                .sha256Fingerprint();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void coversHostDetectsMatchingAndMismatchingHosts() {
        List<X509Certificate> certs = TestCertificates.certs("self-signed-localhost-a.pem");

        assertThat(ServerCertificate.of("localhost", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("127.0.0.1", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("::1", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("0:0:0:0:0:0:0:1", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("evil.example.com", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void wildcardMatchesASingleLeftmostLabelOnly() {
        List<X509Certificate> certs = TestCertificates.certs("wildcard-example.pem");

        assertThat(ServerCertificate.of("www.example.com", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("WWW.Example.COM", 8089, certs).coversHost()).isTrue();
        // *.example.com must not cover a second label, even though that name is the CN.
        assertThat(ServerCertificate.of("a.b.example.com", 8089, certs).coversHost()).isFalse();
        assertThat(ServerCertificate.of("example.com", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void wildcardOverAnIcannPublicSuffixDoesNotCoverHost() {
        List<X509Certificate> certs = TestCertificates.certs("wildcard-com.pem");

        assertThat(ServerCertificate.of("foo.com", 8089, certs).coversHost()).isFalse();
        assertThat(ServerCertificate.of("bar.foo.com", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void publicSuffixRestrictionUsesJdkIcannRules() {
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("com")).isTrue();
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("co.uk")).isTrue();
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("example.com")).isFalse();
        // The JDK checker applies this restriction to ICANN suffixes, not private PSL entries.
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("github.io")).isFalse();
    }

    @Test
    void wildcardOutsideTheLeftmostLabelDoesNotMatch() {
        List<X509Certificate> certs = TestCertificates.certs("wildcard-not-leftmost.pem");

        assertThat(ServerCertificate.of("www.api.example.com", 8089, certs).coversHost()).isFalse();
        assertThat(ServerCertificate.of("www.example.com", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void dnsSanTakesPrecedenceOverAConflictingCn() {
        List<X509Certificate> certs = TestCertificates.certs("san-overrides-cn.pem");

        assertThat(ServerCertificate.of("other.example.com", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("trusted.example.com", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void cnIsUsedOnlyWhenTheCertificateHasNoDnsSan() {
        List<X509Certificate> certs = TestCertificates.certs("cn-only.pem");

        assertThat(ServerCertificate.of("cn-only.example.com", 8089, certs).coversHost()).isTrue();
        assertThat(ServerCertificate.of("other.example.com", 8089, certs).coversHost()).isFalse();
        assertThat(ServerCertificate.of("127.0.0.1", 8089, certs).coversHost()).isFalse();
    }

    @Test
    void dnsAliasDoesNotCoverTheHostAndNamesAreNotResolved() {
        List<X509Certificate> ipOnly = TestCertificates.certs("ip-only-loopback.pem");
        List<X509Certificate> localhost = TestCertificates.certs("self-signed-localhost-a.pem");

        try (MockedStatic<InetAddress> names = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
            // IP SAN matches the address itself, including equivalent textual forms.
            assertThat(ServerCertificate.of("127.0.0.1", 8089, ipOnly).coversHost()).isTrue();
            assertThat(ServerCertificate.of("::1", 8089, localhost).coversHost()).isTrue();
            // localhost resolves to 127.0.0.1, but a certificate that only names the
            // address must not be reported as covering the DNS name.
            assertThat(ServerCertificate.of("localhost", 8089, ipOnly).coversHost()).isFalse();
            // No DNS SAN, so the CN is still an identity. An IP SAN must not suppress it.
            assertThat(ServerCertificate.of("unrelated.example.com", 8089, ipOnly).coversHost()).isTrue();
            // A DNS SAN for localhost must not spill over to a different name.
            assertThat(ServerCertificate.of("ip6-loopback", 8089, localhost).coversHost()).isFalse();

            names.verify(() -> InetAddress.getByName(anyString()), never());
            names.verify(() -> InetAddress.getAllByName(anyString()), never());
        }
    }

    @Test
    void subjectAlternativeNamesAreExposed() {
        ServerCertificate certificate = ServerCertificate.of("localhost", 8089,
                TestCertificates.certs("self-signed-localhost-a.pem"));

        assertThat(certificate.subjectAlternativeNames()).contains("localhost", "127.0.0.1");
    }
}
