package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnvironmentDescriptor — declared runtimes")
class EnvironmentDescriptorRuntimesTest {

    @Test
    @DisplayName("none by default; declared ones are sorted, compared and printed")
    void runtimes() {
        assertThat(EnvironmentDescriptor.builder().workingDirectory("/w").build().runtimes()).isEmpty();
        final EnvironmentDescriptor descriptor = EnvironmentDescriptor.builder().workingDirectory("/w")
                .runtimes(List.of("node", EnvironmentDescriptor.RUNTIME_PYTHON3)).build();
        assertThat(descriptor.runtimes()).containsExactly("node", "python3");
        assertThat(descriptor).isEqualTo(
                EnvironmentDescriptor.builder().workingDirectory("/w").runtime("python3").runtime("node").build());
        assertThat(descriptor).isNotEqualTo(EnvironmentDescriptor.builder().workingDirectory("/w").build());
        assertThat(descriptor.toString()).contains("python3");
        assertThatThrownBy(() -> EnvironmentDescriptor.builder().runtime("Python 3"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
