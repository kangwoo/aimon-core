package at.aimon.core.shell.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellTimeoutException value tests")
class ShellTimeoutExceptionTest {

    @Test
    @DisplayName("the four-arg constructor defaults outputTruncated to false (backward compatible)")
    void fourArgConstructorDefaultsTruncatedToFalse() {
        final ShellTimeoutException ex = new ShellTimeoutException("timed out", Duration.ofSeconds(5), "out", "err");

        assertThat(ex.outputTruncated()).isFalse();
        assertThat(ex.stdout()).isEqualTo("out");
        assertThat(ex.stderr()).isEqualTo("err");
        assertThat(ex.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the five-arg constructor carries the outputTruncated flag")
    void fiveArgConstructorCarriesTruncatedFlag() {
        final ShellTimeoutException ex = new ShellTimeoutException("timed out", Duration.ofSeconds(5), "partial", "",
                true);

        assertThat(ex.outputTruncated()).isTrue();
        assertThat(ex.stdout()).isEqualTo("partial");
    }

    @Test
    @DisplayName("null stdout/stderr are normalized to empty strings")
    void nullOutputsNormalizedToEmpty() {
        final ShellTimeoutException ex = new ShellTimeoutException("timed out", Duration.ofSeconds(1), null, null,
                true);

        assertThat(ex.stdout()).isEmpty();
        assertThat(ex.stderr()).isEmpty();
    }
}
