package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RedactionPattern}: validates default and custom replacement templates plus argument validation.
 */
class RedactionPatternTest {

    @Test
    void defaultReplacementTemplateIsBracketedRedactedColonName() {
        final RedactionPattern p = RedactionPattern.of("OPENAI_API_KEY", Pattern.compile("sk-[A-Za-z0-9]{20,}"));

        assertThat(p.getReplacement()).isEqualTo("[REDACTED:OPENAI_API_KEY]");
        assertThat(p.getName()).isEqualTo("OPENAI_API_KEY");
    }

    @Test
    void customReplacementTemplateIsPreservedVerbatim() {
        final RedactionPattern p = RedactionPattern.of("EMAIL", Pattern.compile("\\w+@example\\.com"), "<email>");

        assertThat(p.getReplacement()).isEqualTo("<email>");
    }

    @Test
    void getPatternReturnsTheCompiledRegex() {
        final Pattern compiled = Pattern.compile("foo");
        final RedactionPattern p = RedactionPattern.of("FOO", compiled);

        assertThat(p.getPattern()).isSameAs(compiled);
    }

    @Test
    void nullNameRejected() {
        assertThatThrownBy(() -> RedactionPattern.of(null, Pattern.compile("x")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> RedactionPattern.of("   ", Pattern.compile("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPatternRejected() {
        assertThatThrownBy(() -> RedactionPattern.of("X", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullReplacementRejected() {
        assertThatThrownBy(() -> RedactionPattern.of("X", Pattern.compile("x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringIncludesNameAndPattern() {
        final RedactionPattern p = RedactionPattern.of("FOO", Pattern.compile("bar"));

        assertThat(p.toString()).contains("FOO").contains("bar");
    }
}
