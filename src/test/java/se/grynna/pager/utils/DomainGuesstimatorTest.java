package se.grynna.pager.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link DomainGuesstimator#extractDomain(String)}: the hostname is split on
 * dots with limit 0, so a trailing dot (the FQDN root label) produces no extra empty label.
 */
class DomainGuesstimatorTest {

    @ParameterizedTest(name = "extractDomain(\"{0}\") = \"{1}\"")
    @CsvSource({"www.example.com, example.com", "a.b.c.d, c.d", "example.com, example.com", "localhost, localhost",
            // A trailing dot is the FQDN root label: its empty label is dropped, so the
            // fully-qualified form has the same labels as the short form.
            "www.example.com., example.com",
            // Two labels are returned verbatim, trailing dot included.
            "example.com., example.com.",
            // An empty label between separators is kept; the last two labels are joined.
            "www..example.com, example.com",})
    void extractDomainKeepsTheLastTwoLabels(String hostname, String expected) {
        assertThat(DomainGuesstimator.extractDomain(hostname)).isEqualTo(expected);
    }

    @Test
    void extractDomainRejectsNullAndEmptyHostnames() {
        assertThat(DomainGuesstimator.extractDomain(null)).isNull();
        assertThat(DomainGuesstimator.extractDomain("")).isNull();
    }
}
