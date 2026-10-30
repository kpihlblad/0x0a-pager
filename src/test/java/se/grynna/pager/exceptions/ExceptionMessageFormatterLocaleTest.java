package se.grynna.pager.exceptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case folding of protocol/error tokens does not depend on the default locale.
 * Under a Turkish default locale {@code "UNAUTHORIZED".toLowerCase()} becomes
 * {@code "unauthorızed"}, which does not match the ASCII token.
 */
class ExceptionMessageFormatterLocaleTest {

    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalLocale);
    }

    @Test
    void classifiesUppercaseErrorTokenWithTurkishDefaultLocale() {
        ExceptionMessageFormatter.ExceptionInfo info = ExceptionMessageFormatter
                .classifyException(new RuntimeException("UNAUTHORIZED: session expired"));

        assertThat(info.category()).isEqualTo(ExceptionMessageFormatter.ErrorCategory.CLIENT_ERROR);
    }
}
