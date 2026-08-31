package at.aimon.core.agent.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.exception.InvalidToolSpecException;

@DisplayName("AllowedTool Tests")
class AllowedToolTest {

    @Test
    @DisplayName("Should parse simple tools without pattern")
    void shouldParseSimpleTool() {
        AllowedTool tool = AllowedTool.parse("Read");

        assertThat(tool.getToolName()).isEqualTo("Read");
        assertThat(tool.hasPattern()).isFalse();
        assertThat(tool.getPattern()).isEmpty();
    }

    @Test
    @DisplayName("Should parse tools with wildcard pattern")
    void shouldParseToolWithWildcardPattern() {
        AllowedTool tool = AllowedTool.parse("Bash(git add:*)");

        assertThat(tool.getToolName()).isEqualTo("Bash");
        assertThat(tool.hasPattern()).isTrue();
        assertThat(tool.getPattern()).isPresent();
        assertThat(tool.getPattern().get().getPattern()).isEqualTo("git add:*");
    }

    @Test
    @DisplayName("Should parse tools with exact pattern")
    void shouldParseToolWithExactPattern() {
        AllowedTool tool = AllowedTool.parse("Bash(npm install)");

        assertThat(tool.getToolName()).isEqualTo("Bash");
        assertThat(tool.hasPattern()).isTrue();
        assertThat(tool.getPattern().get().getPattern()).isEqualTo("npm install");
    }

    @Test
    @DisplayName("Should parse tools with spaces")
    void shouldParseToolWithSpaces() {
        AllowedTool tool = AllowedTool.parse("  Read  ");

        assertThat(tool.getToolName()).isEqualTo("Read");
        assertThat(tool.hasPattern()).isFalse();
    }

    @Test
    @DisplayName("Should parse tools with pattern containing spaces")
    void shouldParseToolWithPatternSpaces() {
        AllowedTool tool = AllowedTool.parse("Bash( git add:* )");

        assertThat(tool.getToolName()).isEqualTo("Bash");
        assertThat(tool.getPattern().get().getPattern()).isEqualTo("git add:*");
    }

    @Test
    @DisplayName("Should reject null spec")
    void shouldRejectNullSpec() {
        assertThatThrownBy(() -> AllowedTool.parse(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool spec cannot be null");
    }

    @Test
    @DisplayName("Should reject malformed spec with missing closing parenthesis")
    void shouldRejectMissingClosingParen() {
        assertThatThrownBy(() -> AllowedTool.parse("Bash(git add:*")).isInstanceOf(InvalidToolSpecException.class)
                .hasMessageContaining("missing closing parenthesis");
    }

    @Test
    @DisplayName("Should reject malformed spec with empty pattern")
    void shouldRejectEmptyPattern() {
        assertThatThrownBy(() -> AllowedTool.parse("Bash()")).isInstanceOf(InvalidToolSpecException.class)
                .hasMessageContaining("empty pattern");
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        AllowedTool tool1 = AllowedTool.parse("Bash(git add:*)");
        AllowedTool tool2 = AllowedTool.parse("Bash(git add:*)");
        AllowedTool tool3 = AllowedTool.parse("Bash(git commit:*)");
        AllowedTool tool4 = AllowedTool.parse("Read");

        assertThat(tool1).isEqualTo(tool2);
        assertThat(tool1).hasSameHashCodeAs(tool2);
        assertThat(tool1).isNotEqualTo(tool3);
        assertThat(tool1).isNotEqualTo(tool4);
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        AllowedTool toolWithPattern = AllowedTool.parse("Bash(git add:*)");
        AllowedTool simpleTool = AllowedTool.parse("Read");

        assertThat(toolWithPattern.toString()).isEqualTo("Bash(git add:*)");
        assertThat(simpleTool.toString()).isEqualTo("Read");
    }

    @Test
    @DisplayName("Should parse multiple common tools formats")
    void shouldParseCommonFormats() {
        assertThat(AllowedTool.parse("Read").getToolName()).isEqualTo("Read");
        assertThat(AllowedTool.parse("Edit").getToolName()).isEqualTo("Edit");
        assertThat(AllowedTool.parse("Write").getToolName()).isEqualTo("Write");
        assertThat(AllowedTool.parse("Grep").getToolName()).isEqualTo("Grep");
        assertThat(AllowedTool.parse("Glob").getToolName()).isEqualTo("Glob");
        assertThat(AllowedTool.parse("Bash(git:*)").getToolName()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("Should parse a path glob and expose it as a path pattern")
    void shouldParsePathGlob() {
        AllowedTool tool = AllowedTool.parse("Read(/tmp/**)");

        assertThat(tool.getToolName()).isEqualTo("Read");
        assertThat(tool.hasPattern()).isTrue();
        assertThat(tool.getPathPattern()).isPresent();
        assertThat(tool.getPathPattern().get().matches("/tmp/deep/a.txt")).isTrue();
        assertThat(tool.getPathPattern().get().matches("/etc/passwd")).isFalse();
    }

    /**
     * A spec's kind is not knowable at parse time, so every pattern is compiled both ways. Both matchers being present
     * is not a contradiction — the subject's kind picks the one that applies, and the other is never consulted.
     */
    @Test
    @DisplayName("Should compile one pattern as both a command and a path matcher")
    void shouldCompileBothMatchers() {
        AllowedTool tool = AllowedTool.parse("Bash(git:*)");

        assertThat(tool.getPattern()).isPresent();
        assertThat(tool.getPathPattern()).isPresent();
        assertThat(tool.getPattern().get().matches("git status")).isTrue();
        assertThat(tool.getPathPattern().get().matches("/tmp/a.txt")).isFalse();
    }

    /**
     * A command pattern that is not valid glob syntax must not fail the parse — it only means no path subject can ever
     * be judged against it, and an absent matcher denies.
     */
    @Test
    @DisplayName("Should parse a command pattern that is not valid glob syntax")
    void shouldParseCommandPatternThatIsNotValidGlob() {
        AllowedTool tool = AllowedTool.parse("Bash(echo {a:*)");

        assertThat(tool.getPattern()).isPresent();
        assertThat(tool.getPathPattern()).isEmpty();
    }

    /**
     * The body runs to the <b>last</b> {@code )}, so a bracketed directory name survives. Closing on the first one
     * would truncate the pattern silently, which is worse than either accepting or rejecting the spec.
     */
    @Test
    @DisplayName("Should keep parentheses inside a pattern body")
    void shouldKeepInnerParentheses() {
        AllowedTool tool = AllowedTool.parse("Read(/tmp/report (1)/**)");

        assertThat(tool.getToolName()).isEqualTo("Read");
        assertThat(tool.getPattern().get().getPattern()).isEqualTo("/tmp/report (1)/**");
        assertThat(tool.getPathPattern().get().matches("/tmp/report (1)/a.txt")).isTrue();
    }

    @Test
    @DisplayName("Should reject trailing characters after the closing parenthesis")
    void shouldRejectTrailingCharacters() {
        assertThatThrownBy(() -> AllowedTool.parse("Read(/tmp/**) extra")).isInstanceOf(InvalidToolSpecException.class)
                .hasMessageContaining("trailing characters");
    }

    @Test
    @DisplayName("Should reject a closing parenthesis with no opening one")
    void shouldRejectUnopenedParenthesis() {
        assertThatThrownBy(() -> AllowedTool.parse("Read/tmp/**)")).isInstanceOf(InvalidToolSpecException.class)
                .hasMessageContaining("closing parenthesis without opening");
    }

    @Test
    @DisplayName("Should expose no path pattern for a patternless spec")
    void shouldExposeNoPathPatternWithoutPattern() {
        AllowedTool tool = AllowedTool.parse("Read");

        assertThat(tool.hasPattern()).isFalse();
        assertThat(tool.getPathPattern()).isEmpty();
    }
}
