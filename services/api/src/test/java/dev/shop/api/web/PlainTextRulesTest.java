package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class PlainTextRulesTest {

    private static final PlainTextValidator SINGLE_LINE = validator(false);
    private static final PlainTextValidator MULTI_LINE = validator(true);

    private static PlainTextValidator validator(boolean allowNewline) {
        PlainTextValidator validator = new PlainTextValidator();
        PlainText constraint = mock(PlainText.class);
        when(constraint.allowNewline()).thenReturn(allowNewline);
        validator.initialize(constraint);
        return validator;
    }

    @Test
    void nullAndOrdinaryTextAreValid() {
        assertThat(SINGLE_LINE.isValid(null, null)).isTrue();
        assertThat(SINGLE_LINE.isValid("Café   😀", null)).isTrue();
    }

    @Test
    void newlinesFollowTheSetting() {
        assertThat(SINGLE_LINE.isValid("a\nb", null)).isFalse();
        assertThat(MULTI_LINE.isValid("a\nb", null)).isTrue();
        assertThat(MULTI_LINE.isValid("a\tb", null)).isFalse();
        assertThat(MULTI_LINE.isValid("a\rb", null)).isFalse();
    }

    @Test
    void rejectsEveryDisallowedCategory() {
        for (String text : new String[] {"\u0007", "​", "\ud800", "", "͸"}) {
            assertThat(SINGLE_LINE.isValid("a" + text, null)).as(text).isFalse();
        }
    }

    @Test
    void sanitisingKeepsOnlyAllowedCodePoints() {
        assertThat(PlainTextRules.withoutDisallowed("a‮b\nc\ud800😀")).isEqualTo("abc😀");
    }
}
