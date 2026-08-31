package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellCommandResult value object tests")
class ShellCommandResultTest {

    @Test
    @DisplayName("the four-arg constructor defaults outputTruncated to false (backward compatible)")
    void fourArgConstructorDefaultsTruncatedToFalse() {
        final ShellCommandResult result = new ShellCommandResult(0, "out", "err", Duration.ofMillis(1));

        assertThat(result.outputTruncated()).isFalse();
        assertThat(result.stdout()).isEqualTo("out");
        assertThat(result.stderr()).isEqualTo("err");
    }

    @Test
    @DisplayName("the five-arg constructor carries the outputTruncated flag")
    void fiveArgConstructorCarriesTruncatedFlag() {
        final ShellCommandResult result = new ShellCommandResult(0, "partial", "", Duration.ofMillis(1), true);

        assertThat(result.outputTruncated()).isTrue();
        assertThat(result.stdout()).isEqualTo("partial");
    }

    @Test
    @DisplayName("null stdout/stderr are normalized to empty strings")
    void nullOutputsNormalizedToEmpty() {
        final ShellCommandResult result = new ShellCommandResult(0, null, null, Duration.ofMillis(1), true);

        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    @DisplayName("outputTruncated participates in equals/hashCode")
    void outputTruncatedParticipatesInEqualsAndHashCode() {
        final Duration d = Duration.ofMillis(5);
        final ShellCommandResult complete = new ShellCommandResult(0, "x", "y", d, false);
        final ShellCommandResult truncated = new ShellCommandResult(0, "x", "y", d, true);
        final ShellCommandResult sameAsComplete = new ShellCommandResult(0, "x", "y", d);

        assertThat(complete).isNotEqualTo(truncated);
        assertThat(complete).isEqualTo(sameAsComplete);
        assertThat(complete).hasSameHashCodeAs(sameAsComplete);
    }

    @Test
    @DisplayName("toString exposes the outputTruncated flag")
    void toStringExposesTruncatedFlag() {
        final ShellCommandResult result = new ShellCommandResult(0, "x", "y", Duration.ofMillis(1), true);

        assertThat(result.toString()).contains("outputTruncated=true");
    }
}
