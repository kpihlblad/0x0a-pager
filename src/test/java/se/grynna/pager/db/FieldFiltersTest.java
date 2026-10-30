package se.grynna.pager.db;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldFiltersTest {

    /**
     * A record exactly as the version before {@code narrow}/{@code exclude} existed
     * serialized it: {@code new FieldFilters(Map.of("host","web-1","env","prod"))}.
     */
    private static final String OLD_RECORD = "rO0ABXNyAB9zZS5ncnlubmEucGFnZXIuZGIuRmllbGRGaWx0ZXJzgbadkkkFklICAAFMAAdmaWx0ZXJzdAATTGphdmEvdXRpbC9UcmVlTWFw"
            + "O3hwc3IAEWphdmEudXRpbC5UcmVlTWFwDMH2Pi0lauYDAAFMAApjb21wYXJhdG9ydAAWTGphdmEvdXRpbC9Db21wYXJhdG9yO3hwcHcEAAAAAnQAA2VudnQABHByb2R0AARob3N0dAAFd2ViLTF4";

    private static FieldFilters roundTrip(FieldFilters in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(in);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (FieldFilters) input.readObject();
        }
    }

    @Test
    void roundTripWithTerms() throws Exception {
        FieldFilters in = new FieldFilters(Map.of("host", "web-1"),
                new ScopeTerms(List.of("timeout", "user 42"), List.of("healthcheck")));
        FieldFilters out = roundTrip(in);
        assertThat(out.getFilters()).containsEntry("host", "web-1");
        assertThat(out.getNarrow()).containsExactly("timeout", "user 42");
        assertThat(out.getExclude()).containsExactly("healthcheck");
        assertThat(out.terms()).isEqualTo(new ScopeTerms(List.of("timeout", "user 42"), List.of("healthcheck")));
        assertThat(out.isEmpty()).isFalse();
    }

    @Test
    void roundTripWithoutTerms() throws Exception {
        FieldFilters out = roundTrip(new FieldFilters(Map.of("host", "web-1")));
        assertThat(out.getFilters()).containsEntry("host", "web-1");
        assertThat(out.getNarrow()).isEmpty();
        assertThat(out.getExclude()).isEmpty();
        assertThat(out.terms().isEmpty()).isTrue();
    }

    @Test
    void recordSavedBeforeTermsExistedStillReadsAndHasNoTerms() throws Exception {
        byte[] old = Base64.getDecoder().decode(OLD_RECORD);
        FieldFilters read;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(old))) {
            read = (FieldFilters) input.readObject();
        }
        assertThat(read.getFilters()).containsOnly(Map.entry("env", "prod"), Map.entry("host", "web-1"));
        assertThat(read.getNarrow()).isEmpty();
        assertThat(read.getExclude()).isEmpty();
        assertThat(read.terms()).isEqualTo(ScopeTerms.EMPTY);
        assertThat(read.isEmpty()).isFalse();
        assertThat(read.size()).isEqualTo(2);
        // And it can be re-saved with terms
        read.setTerms(new ScopeTerms(List.of("a"), List.of()));
        assertThat(roundTrip(read).getNarrow()).containsExactly("a");
    }

    @Test
    void emptyOnlyWhenNothingIsSaved() {
        assertThat(new FieldFilters().isEmpty()).isTrue();
        assertThat(new FieldFilters(Map.of(), new ScopeTerms(List.of(), List.of("x"))).isEmpty()).isFalse();
        assertThat(new FieldFilters(Map.of("a", "b"), ScopeTerms.EMPTY).isEmpty()).isFalse();
    }

    @Test
    void recordSavedBeforeMessageFieldsExistedUsesTheDefault() throws Exception {
        byte[] old = Base64.getDecoder().decode(OLD_RECORD);
        FieldFilters read;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(old))) {
            read = (FieldFilters) input.readObject();
        }
        assertThat(read.getMessageFieldsOrNull()).isNull();
        assertThat(read.isEmpty()).isFalse();
    }

    @Test
    void messageFieldsRoundTripInOrderIncludingAnEmptyList() throws Exception {
        FieldFilters in = new FieldFilters(Map.of(), ScopeTerms.EMPTY, List.of("title", "payload.text"));
        FieldFilters out = roundTrip(in);
        assertThat(out.getMessageFieldsOrNull()).containsExactly("title", "payload.text");

        FieldFilters none = roundTrip(new FieldFilters(Map.of(), ScopeTerms.EMPTY, List.of()));
        assertThat(none.getMessageFieldsOrNull()).isEmpty();
        assertThat(none.isEmpty()).isFalse();
    }

    @Test
    void nullMessageFieldsMeanTheDefaultAndAreEmptyOnlyWithNothingElse() throws Exception {
        FieldFilters record = new FieldFilters(Map.of(), ScopeTerms.EMPTY, null);
        assertThat(record.getMessageFieldsOrNull()).isNull();
        assertThat(record.isEmpty()).isTrue();
        assertThat(roundTrip(record).isEmpty()).isTrue();
    }
}
