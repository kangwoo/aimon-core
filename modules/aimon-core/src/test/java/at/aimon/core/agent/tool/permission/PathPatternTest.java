package at.aimon.core.agent.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.exception.InvalidToolSpecException;

@DisplayName("PathPattern Tests")
class PathPatternTest {

    @Test
    @DisplayName("Should match anything under a directory for a double star")
    void shouldMatchRecursivelyForDoubleStar() {
        PathPattern pattern = PathPattern.of("/tmp/**");

        assertThat(pattern.matches("/tmp/a.txt")).isTrue();
        assertThat(pattern.matches("/tmp/deep/nested/a.txt")).isTrue();
        assertThat(pattern.matches("/etc/passwd")).isFalse();
        assertThat(pattern.matches("/tmpfile")).isFalse();
    }

    @Test
    @DisplayName("Should not cross a separator for a single star")
    void shouldNotCrossSeparatorForSingleStar() {
        PathPattern pattern = PathPattern.of("/tmp/*.log");

        assertThat(pattern.matches("/tmp/app.log")).isTrue();
        assertThat(pattern.matches("/tmp/sub/app.log")).isFalse();
        assertThat(pattern.matches("/tmp/app.txt")).isFalse();
    }

    @Test
    @DisplayName("Should match a literal pattern exactly")
    void shouldMatchLiteralExactly() {
        PathPattern pattern = PathPattern.of("/etc/passwd");

        assertThat(pattern.matches("/etc/passwd")).isTrue();
        assertThat(pattern.matches("/etc/passwd.bak")).isFalse();
        assertThat(pattern.matches("/etc")).isFalse();
    }

    /**
     * The whole reason this class exists next to {@link ToolPattern}: a path never reaches a shell, so the
     * metacharacter refusal that protects a command would only put ordinary filenames out of reach here.
     */
    @Test
    @DisplayName("Should not refuse shell metacharacters")
    void shouldNotRefuseShellMetacharacters() {
        PathPattern pattern = PathPattern.of("/tmp/**");

        assertThat(pattern.matches("/tmp/report (1).csv")).isTrue();
        assertThat(pattern.matches("/tmp/a&b.txt")).isTrue();
        assertThat(pattern.matches("/tmp/cost$.txt")).isTrue();
        assertThat(pattern.matches("/tmp/a;b.txt")).isTrue();
    }

    @Test
    @DisplayName("Should match a literal parenthesis in the pattern itself")
    void shouldMatchParenthesisInPattern() {
        PathPattern pattern = PathPattern.of("/tmp/report (1)/**");

        assertThat(pattern.matches("/tmp/report (1)/a.txt")).isTrue();
        assertThat(pattern.matches("/tmp/report (2)/a.txt")).isFalse();
    }

    /**
     * Matching is lexical, so a caller that hands over an unnormalized path gets no help — which is why
     * {@link PermissionSubject.Kind#PATH} makes normalization the producer's job.
     */
    @Test
    @DisplayName("Should not normalize the candidate itself")
    void shouldNotNormalizeCandidate() {
        PathPattern pattern = PathPattern.of("/tmp/**");

        assertThat(pattern.matches("/tmp/../etc/passwd")).isTrue();
        assertThat(pattern.matches("/etc/passwd")).isFalse();
    }

    @Test
    @DisplayName("Should return empty rather than throw for a malformed glob")
    void shouldReturnEmptyForMalformedGlob() {
        assertThat(PathPattern.tryCompile("/tmp/{a")).isEmpty();
        assertThat(PathPattern.tryCompile("/tmp/[a")).isEmpty();
    }

    /**
     * A command pattern is speculatively compiled as a glob by {@link AllowedTool}, so most of them do compile — they
     * simply never match a path. Only the ones that are not valid glob syntax come back empty.
     */
    @Test
    @DisplayName("Should compile a command pattern that happens to be valid glob syntax")
    void shouldCompileCommandPatternWithValidGlobSyntax() {
        assertThat(PathPattern.tryCompile("git:*")).isPresent();
        assertThat(PathPattern.of("git:*").matches("/tmp/a.txt")).isFalse();
    }

    @Test
    @DisplayName("Should throw for a malformed glob when compiled strictly")
    void shouldThrowForMalformedGlobWhenStrict() {
        assertThatThrownBy(() -> PathPattern.of("/tmp/{a")).isInstanceOf(InvalidToolSpecException.class)
                .hasMessageContaining("/tmp/{a");
    }

    @Test
    @DisplayName("Should not match a path this platform cannot represent")
    void shouldNotMatchUnrepresentablePath() {
        // An embedded NUL is not a path on any supported platform — a non-match, not an exception
        assertThat(PathPattern.of("/tmp/**").matches("/tmp/a\0b")).isFalse();
    }

    @Test
    @DisplayName("Should expose the original pattern and compare by it")
    void shouldExposePatternAndCompareByIt() {
        assertThat(PathPattern.of("/tmp/**").getPattern()).isEqualTo("/tmp/**");
        assertThat(PathPattern.of("/tmp/**")).isEqualTo(PathPattern.of("/tmp/**"))
                .hasSameHashCodeAs(PathPattern.of("/tmp/**"));
        assertThat(PathPattern.of("/tmp/**")).isNotEqualTo(PathPattern.of("/var/**"));
        assertThat(PathPattern.of("/tmp/**")).hasToString("/tmp/**");
    }
}
