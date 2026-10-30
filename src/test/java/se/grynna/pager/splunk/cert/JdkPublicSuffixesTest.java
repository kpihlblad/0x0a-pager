package se.grynna.pager.splunk.cert;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for the rule matching in {@link JdkPublicSuffixes}: rules are split into labels
 * (limit 0, dropping trailing empty labels) and matched against the domain's tail labels, so
 * multi-label, wildcard and exception rules must resolve against the JDK's public suffix list.
 */
class JdkPublicSuffixesTest {

    @Test
    void singleLabelIcannSuffixesAreRecognized() {
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("com")).isTrue();
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("uk")).isTrue();
    }

    @Test
    void multiLabelIcannSuffixesAreRecognized() {
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("co.uk")).isTrue();
    }

    @Test
    void namesUnderAPublicSuffixAreNotPublicSuffixesThemselves() {
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("example.com")).isFalse();
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("foo.co.uk")).isFalse();
    }

    @Test
    void privatePslEntriesAreNotIcannPublicSuffixes() {
        // github.io is a private PSL entry, not an ICANN one.
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("github.io")).isFalse();
    }

    @Test
    void wildcardRuleMakesTheCoveredNameThePublicSuffix() {
        // *.ck is an ICANN wildcard rule, so any foo.ck is its own prevailing public suffix.
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("foo.ck")).isTrue();
    }

    @Test
    void exceptionRuleExcludesTheExceptionName() {
        // !www.ck excepts www.ck from the *.ck wildcard: the prevailing public suffix for
        // z.www.ck is ck, so www.ck itself is not a public suffix.
        assertThat(JdkPublicSuffixes.isIcannPublicSuffix("www.ck")).isFalse();
    }
}
