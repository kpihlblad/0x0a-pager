package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FieldTermTest {

    @Test
    void encodeAndDecodeRoundTrip() {
        FieldTerm term = FieldTerm.of("level", "DEBUG").orElseThrow();

        assertThat(term.encode()).startsWith(String.valueOf(FieldTerm.MARKER)).doesNotContain("level=");
        assertThat(FieldTerm.decode(term.encode())).contains(term);
        assertThat(FieldTerm.isFieldTerm(term.encode())).isTrue();
    }

    @Test
    void aValueWithQuotesAndBackslashesRoundTrips() {
        FieldTerm term = FieldTerm.of("msg", "say \"hi\" C:\\temp\\").orElseThrow();

        assertThat(FieldTerm.decode(term.encode())).contains(term);
    }

    @Test
    void aTextTermThatLooksLikeAFieldTermStaysText() {
        assertThat(FieldTerm.decode("a=\"b\"")).isEmpty();
        assertThat(FieldTerm.isFieldTerm("a=\"b\"")).isFalse();
        assertThat(FieldTerm.display("a=\"b\"")).isEqualTo("\"a=\"b\"\"");
    }

    @Test
    void notEveryMarkedStringIsAFieldTerm() {
        char m = FieldTerm.MARKER;
        assertThat(FieldTerm.decode(null)).isEmpty();
        assertThat(FieldTerm.decode("")).isEmpty();
        assertThat(FieldTerm.decode(m + "level")).as("no second marker").isEmpty();
        assertThat(FieldTerm.decode(m + "" + m + "DEBUG")).as("blank field").isEmpty();
        assertThat(FieldTerm.decode(m + " level" + m + "DEBUG")).as("field with whitespace").isEmpty();
        assertThat(FieldTerm.decode("x" + m + "level" + m + "DEBUG")).as("marker not first").isEmpty();
        assertThat(FieldTerm.isFieldTerm("timeout")).isFalse();
    }

    @Test
    void ofRejectsInvalidFieldsAndValues() {
        assertThat(FieldTerm.of(null, "x")).isEmpty();
        assertThat(FieldTerm.of("", "x")).isEmpty();
        assertThat(FieldTerm.of(" ", "x")).isEmpty();
        assertThat(FieldTerm.of("a b", "x")).isEmpty();
        assertThat(FieldTerm.of("a=b", "x")).isEmpty();
        assertThat(FieldTerm.of("a\"b", "x")).isEmpty();
        assertThat(FieldTerm.of("a" + FieldTerm.MARKER, "x")).isEmpty();
        assertThat(FieldTerm.of("level", null)).isEmpty();
        assertThat(FieldTerm.of("level", "x" + FieldTerm.MARKER)).isEmpty();
        assertThat(FieldTerm.of("level", "")).as("an empty value is a valid term").isPresent();
    }

    @Test
    void splRendersTheEscapedPredicate() {
        assertThat(FieldTerm.of("level", "DEBUG").orElseThrow().spl()).isEqualTo("level=\"DEBUG\"");
        assertThat(FieldTerm.of("msg", "say \"hi\"\\x").orElseThrow().spl())
                .isEqualTo("msg=\"say \\\"hi\\\"\\\\x\"");
    }

    @Test
    void displayShowsFieldTermsAsFieldEqualsAndTextTermsQuoted() {
        assertThat(FieldTerm.display(FieldTerm.of("level", "DEBUG").orElseThrow().encode()))
                .isEqualTo("level=\"DEBUG\"");
        assertThat(FieldTerm.display("timeout")).isEqualTo("\"timeout\"");
        assertThat(FieldTerm.display("timeout")).doesNotContain(String.valueOf(FieldTerm.MARKER));
    }
}
