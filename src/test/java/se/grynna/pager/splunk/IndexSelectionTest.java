package se.grynna.pager.splunk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexSelectionTest {

    @Test
    void aSingleIndexKeepsItsName() {
        assertThat(IndexSelection.parse("web").key()).isEqualTo("web");
        assertThat(IndexSelection.parse("web").indexes()).containsExactly("web");
    }

    @Test
    void severalIndexesAreSortedDeDuplicatedAndTrimmed() {
        assertThat(IndexSelection.parse(" b , a,b,, a ").key()).isEqualTo("a,b");
        assertThat(IndexSelection.parse("web,app,db").key()).isEqualTo("app,db,web");
        assertThat(IndexSelection.parse("web,app,db").indexes()).containsExactly("app", "db", "web");
    }

    @Test
    void theKeyRoundTrips() {
        for (String key : List.of("", "*", "web", "a,b", "app,db,web")) {
            assertThat(IndexSelection.parse(key).key()).as(key).isEqualTo(key);
            assertThat(IndexSelection.parse(IndexSelection.parse(key).key())).isEqualTo(IndexSelection.parse(key));
        }
    }

    @Test
    void anAsteriskAnywhereSelectsAllIndexes() {
        assertThat(IndexSelection.parse("*")).isEqualTo(IndexSelection.ALL);
        assertThat(IndexSelection.parse("a,*")).isEqualTo(IndexSelection.ALL);
        assertThat(IndexSelection.parse("*,a").key()).isEqualTo("*");
        assertThat(IndexSelection.ALL.key()).isEqualTo("*");
        assertThat(IndexSelection.ALL.indexes()).isEmpty();
        assertThat(IndexSelection.ALL.isAll()).isTrue();
        assertThat(IndexSelection.ALL.isNone()).isFalse();
    }

    @Test
    void blankSelectsNoIndex() {
        assertThat(IndexSelection.parse(null)).isEqualTo(IndexSelection.NONE);
        assertThat(IndexSelection.parse("")).isEqualTo(IndexSelection.NONE);
        assertThat(IndexSelection.parse("  ")).isEqualTo(IndexSelection.NONE);
        assertThat(IndexSelection.parse(" , ,")).isEqualTo(IndexSelection.NONE);
        assertThat(IndexSelection.NONE.key()).isEmpty();
        assertThat(IndexSelection.NONE.indexes()).isEmpty();
        assertThat(IndexSelection.NONE.isNone()).isTrue();
        assertThat(IndexSelection.NONE.isAll()).isFalse();
    }

    @Test
    void ofNormalisesLikeParse() {
        assertThat(IndexSelection.of(Arrays.asList("web", " app ", "web", null, ""))).isEqualTo(
                IndexSelection.parse("app,web"));
        assertThat(IndexSelection.of(List.of("web", "*"))).isEqualTo(IndexSelection.ALL);
        assertThat(IndexSelection.of(List.of())).isEqualTo(IndexSelection.NONE);
        assertThat(IndexSelection.of(null)).isEqualTo(IndexSelection.NONE);
    }

    @Test
    void thePredicateOfEachKindOfSelection() {
        assertThat(IndexSelection.NONE.predicate()).isEmpty();
        assertThat(IndexSelection.ALL.predicate()).isEqualTo("index=*");
        assertThat(IndexSelection.parse("web").predicate()).isEqualTo("index=\"web\"");
        assertThat(IndexSelection.parse("b,a").predicate()).isEqualTo("(index=\"a\" OR index=\"b\")");
        assertThat(IndexSelection.parse("a,b,c").predicate())
                .isEqualTo("(index=\"a\" OR index=\"b\" OR index=\"c\")");
    }

    @Test
    void thePredicateEncodesTheNamesAsLiterals() {
        assertThat(IndexSelection.parse("a\"b").predicate()).isEqualTo("index=\"a\\\"b\"");
        assertThat(IndexSelection.parse("x\\").predicate()).isEqualTo("index=\"x\\\\\"");
        assertThat(IndexSelection.parse("a\"b,c").predicate()).isEqualTo("(index=\"a\\\"b\" OR index=\"c\")");
    }

    @Test
    void matchesIgnoresCaseAndTakesEveryEventWhenUnknownOrAllOrNone() {
        IndexSelection several = IndexSelection.parse("a,b");

        assertThat(several.matches("b")).isTrue();
        assertThat(several.matches("B")).isTrue();
        assertThat(several.matches("c")).isFalse();
        assertThat(several.matches(null)).as("unknown index is not judged").isTrue();
        assertThat(IndexSelection.ALL.matches("anything")).isTrue();
        assertThat(IndexSelection.NONE.matches("anything")).isTrue();
        assertThat(IndexSelection.parse("web").matches("WEB")).isTrue();
    }

    @Test
    void sameAsComparesTheNamesIgnoringCase() {
        assertThat(IndexSelection.parse("a,b").sameAs(IndexSelection.parse("B,A"))).isTrue();
        assertThat(IndexSelection.parse("WEB").sameAs(IndexSelection.parse("web"))).isTrue();
        assertThat(IndexSelection.parse("a").sameAs(IndexSelection.parse("a,b"))).isFalse();
        assertThat(IndexSelection.parse("a").sameAs(IndexSelection.ALL)).isFalse();
        assertThat(IndexSelection.NONE.sameAs(IndexSelection.parse("*"))).isFalse();
        assertThat(IndexSelection.NONE.sameAs(IndexSelection.NONE)).isTrue();
    }

    @Test
    void displayNamesTheSelection() {
        assertThat(IndexSelection.NONE.display()).isEmpty();
        assertThat(IndexSelection.ALL.display()).isEqualTo("all indexes");
        assertThat(IndexSelection.parse("web").display()).isEqualTo("web");
        assertThat(IndexSelection.parse("b,a").display()).isEqualTo("a, b");
    }

    @Test
    void fileLabelIsSafeForAFileName() {
        assertThat(IndexSelection.NONE.fileLabel()).isEmpty();
        assertThat(IndexSelection.ALL.fileLabel()).isEqualTo("all");
        assertThat(IndexSelection.parse("web").fileLabel()).isEqualTo("web");
        assertThat(IndexSelection.parse("b,a").fileLabel()).isEqualTo("a+b");
    }
}
