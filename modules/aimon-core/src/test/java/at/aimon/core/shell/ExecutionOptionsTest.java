package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExecutionOptions Tests")
class ExecutionOptionsTest {

    @Test
    @DisplayName("Defaults should return expected values")
    void defaults_returnsExpectedValues() {
        ExecutionOptions options = ExecutionOptions.defaults();

        assertThat(options.getTimeout()).isNull();
        assertThat(options.getEnvironment()).isEmpty();
        assertThat(options.getWorkingDirectory()).isNull();
        assertThat(options.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(options.isRedirectErrorStream()).isFalse();
        assertThat(options.getUnixShell()).isNull();
    }

    @Test
    @DisplayName("Builder should set all options correctly")
    void builder_allOptions() {
        Map<String, String> env = Map.of("KEY", "VALUE");

        ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).environment(env)
                .workingDirectory("/tmp").charset(StandardCharsets.ISO_8859_1).redirectErrorStream(true)
                .unixShell("zsh").build();

        assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.getEnvironment()).containsEntry("KEY", "VALUE");
        assertThat(options.getWorkingDirectory()).isEqualTo("/tmp");
        assertThat(options.getCharset()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(options.isRedirectErrorStream()).isTrue();
        assertThat(options.getUnixShell()).isEqualTo("zsh");
    }

    @Test
    @DisplayName("Environment should be an immutable copy")
    void getEnvironment_returnsImmutableCopy() {
        ExecutionOptions options = ExecutionOptions.builder().environment(Map.of("A", "B")).build();

        assertThatThrownBy(() -> options.getEnvironment().put("C", "D"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Null environment should default to empty map")
    void nullEnvironment_defaultsToEmpty() {
        ExecutionOptions options = ExecutionOptions.builder().environment(null).build();

        assertThat(options.getEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("maxCaptureBytes should be stored and default to null")
    void maxCaptureBytes_storedAndDefaultsToNull() {
        assertThat(ExecutionOptions.defaults().getMaxCaptureBytes()).isNull();
        assertThat(ExecutionOptions.builder().maxCaptureBytes(4096).build().getMaxCaptureBytes()).isEqualTo(4096L);
        assertThat(ExecutionOptions.builder().maxCaptureBytes(0).build().getMaxCaptureBytes()).isZero();
    }

    @Test
    @DisplayName("Negative maxCaptureBytes should be rejected (fail-fast)")
    void negativeMaxCaptureBytes_rejected() {
        assertThatThrownBy(() -> ExecutionOptions.builder().maxCaptureBytes(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxCaptureBytes");
    }
}
