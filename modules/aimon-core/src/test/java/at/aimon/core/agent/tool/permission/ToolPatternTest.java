package at.aimon.core.agent.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolPattern Tests")
class ToolPatternTest {

    @Test
    @DisplayName("Should create wildcard pattern")
    void shouldCreateWildcardPattern() {
        ToolPattern pattern = ToolPattern.of("git add:*");

        assertThat(pattern.getPattern()).isEqualTo("git add:*");
        assertThat(pattern.isWildcard()).isTrue();
    }

    @Test
    @DisplayName("Should create exact pattern")
    void shouldCreateExactPattern() {
        ToolPattern pattern = ToolPattern.of("npm install");

        assertThat(pattern.getPattern()).isEqualTo("npm install");
        assertThat(pattern.isWildcard()).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should match prefix")
    void wildcardShouldMatchPrefix() {
        ToolPattern pattern = ToolPattern.of("git add:*");

        assertThat(pattern.matches("git add .")).isTrue();
        assertThat(pattern.matches("git add src/")).isTrue();
        assertThat(pattern.matches("git add src/file.txt")).isTrue();
    }

    @Test
    @DisplayName("Wildcard pattern should not match different commands")
    void wildcardShouldNotMatchDifferentCommands() {
        ToolPattern pattern = ToolPattern.of("git add:*");

        assertThat(pattern.matches("git commit")).isFalse();
        assertThat(pattern.matches("git status")).isFalse();
        assertThat(pattern.matches("npm install")).isFalse();
    }

    @Test
    @DisplayName("Exact pattern should match exactly")
    void exactPatternShouldMatchExactly() {
        ToolPattern pattern = ToolPattern.of("npm install");

        assertThat(pattern.matches("npm install")).isTrue();
    }

    @Test
    @DisplayName("Exact pattern should not match with additional arguments")
    void exactPatternShouldNotMatchWithArgs() {
        ToolPattern pattern = ToolPattern.of("npm install");

        assertThat(pattern.matches("npm install --save")).isFalse();
        assertThat(pattern.matches("npm install -g")).isFalse();
    }

    @Test
    @DisplayName("Should handle pattern with spaces")
    void shouldHandlePatternWithSpaces() {
        ToolPattern pattern = ToolPattern.of("git commit -m:*");

        assertThat(pattern.matches("git commit -m 'message'")).isTrue();
        assertThat(pattern.matches("git commit -m \"message\"")).isTrue();
    }

    @Test
    @DisplayName("Should reject null pattern")
    void shouldRejectNullPattern() {
        assertThatThrownBy(() -> ToolPattern.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Pattern cannot be null");
    }

    @Test
    @DisplayName("Should reject null actualInvocation in matches")
    void shouldRejectNullInvocation() {
        ToolPattern pattern = ToolPattern.of("git add:*");

        assertThatThrownBy(() -> pattern.matches(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Actual invocation cannot be null");
    }

    @Test
    @DisplayName("Should implement equals and hashCode correctly")
    void shouldImplementEqualsAndHashCode() {
        ToolPattern pattern1 = ToolPattern.of("git add:*");
        ToolPattern pattern2 = ToolPattern.of("git add:*");
        ToolPattern pattern3 = ToolPattern.of("git commit:*");

        assertThat(pattern1).isEqualTo(pattern2);
        assertThat(pattern1).hasSameHashCodeAs(pattern2);
        assertThat(pattern1).isNotEqualTo(pattern3);
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        ToolPattern pattern = ToolPattern.of("git add:*");

        assertThat(pattern.toString()).isEqualTo("git add:*");
    }

    @Test
    @DisplayName("Wildcard pattern should reject shell metacharacter semicolon")
    void wildcardShouldRejectSemicolon() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git status; rm -rf /")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject shell metacharacter double ampersand")
    void wildcardShouldRejectDoubleAmpersand() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git status && cat /etc/shadow")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject shell metacharacter pipe")
    void wildcardShouldRejectPipe() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git log | xargs rm")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject shell metacharacter backtick")
    void wildcardShouldRejectBacktick() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git status `whoami`")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject shell metacharacter dollar")
    void wildcardShouldRejectDollarSign() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git status $(cat /etc/passwd)")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject newline in command")
    void wildcardShouldRejectNewline() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git status\nrm -rf /")).isFalse();
    }

    @Test
    @DisplayName("Exact pattern should reject shell metacharacters in invocation")
    void exactPatternShouldRejectMetacharacters() {
        ToolPattern pattern = ToolPattern.of("echo hello; world");

        assertThat(pattern.matches("echo hello; world")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject output redirection")
    void wildcardShouldRejectOutputRedirection() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git log > /tmp/leak")).isFalse();
        assertThat(pattern.matches("git log >> /tmp/leak")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject input redirection")
    void wildcardShouldRejectInputRedirection() {
        ToolPattern pattern = ToolPattern.of("git:*");

        assertThat(pattern.matches("git hash-object < /etc/passwd")).isFalse();
    }

    @Test
    @DisplayName("Wildcard pattern should reject subshell execution")
    void wildcardShouldRejectSubshell() {
        ToolPattern pattern = ToolPattern.of("echo:*");

        assertThat(pattern.matches("echo $(whoami)")).isFalse();
        assertThat(pattern.matches("echo (rm -rf /)")).isFalse();
    }

    @Test
    @DisplayName("Exact pattern should reject redirection operators")
    void exactPatternShouldRejectRedirection() {
        ToolPattern pattern = ToolPattern.of("cat /etc/hosts > /tmp/out");

        assertThat(pattern.matches("cat /etc/hosts > /tmp/out")).isFalse();
    }

    @Test
    @DisplayName("Exact pattern should match safe commands")
    void exactPatternShouldMatchSafeCommands() {
        ToolPattern pattern = ToolPattern.of("npm install");

        assertThat(pattern.matches("npm install")).isTrue();
    }

    /**
     * The metacharacter refusal above is a command-only defence, and the tests for it read as if it were universal.
     * It is not: the same characters in a path are ordinary filename characters, and {@link PathPattern} — the matcher
     * a {@link PermissionSubject.Kind#PATH} subject selects — accepts them. This pins the boundary so a future reader
     * does not carry the command rule over to the other matcher.
     */
    @Test
    @DisplayName("The metacharacter refusal is command-only — paths are matched by PathPattern")
    void metacharacterRefusalIsCommandOnly() {
        assertThat(ToolPattern.of("/tmp:*").matches("/tmp/report (1).csv")).isFalse();
        assertThat(PathPattern.of("/tmp/**").matches("/tmp/report (1).csv")).isTrue();
    }
}
