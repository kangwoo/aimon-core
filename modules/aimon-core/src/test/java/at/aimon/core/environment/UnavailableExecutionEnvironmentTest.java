package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;

@DisplayName("UnavailableExecutionEnvironment")
class UnavailableExecutionEnvironmentTest {

    private final IllegalStateException cause = new IllegalStateException("sandbox down");
    private final ExecutionEnvironment env = UnavailableExecutionEnvironment.of(cause);

    @Test
    @DisplayName("every filesystem call throws with the cause")
    void fileSystemThrows() {
        assertThatThrownBy(() -> env.fileSystem().read("a.txt"))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class).hasMessageContaining("sandbox down")
                .hasCause(cause);
        assertThatThrownBy(() -> env.fileSystem().exists("a.txt"))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class);
        assertThatThrownBy(() -> env.fileSystem().getWorkingDirectory())
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class);
        assertThatThrownBy(() -> env.fileSystem().write("a.txt", "x"))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class);
    }

    @Test
    @DisplayName("every shell call throws with the cause")
    void shellThrows() {
        assertThatThrownBy(() -> env.shell().execute(() -> "echo hi"))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class).hasMessageContaining("sandbox down");
        assertThatThrownBy(() -> env.shell().getWorkingDirectory())
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class);
    }

    @Test
    @DisplayName("the descriptor does not throw and carries the cause in its notes")
    void descriptorCarriesCause() {
        final EnvironmentDescriptor descriptor = env.descriptor();
        assertThat(descriptor.notes()).hasValueSatisfying(n -> assertThat(n).contains("sandbox down"));
        assertThat(descriptor.workingDirectory()).isEmpty();
        assertThat(descriptor.platform()).isEmpty();
        assertThat(descriptor.osVersion()).isEmpty();
    }

    @Test
    @DisplayName("stage throws")
    void stageThrows() {
        final StagedResource resource = StagedResource.builder().sourceFileSystem(env.fileSystem()).sourceDir("s")
                .contentKey("k").name("n").files(List.of()).build();
        assertThatThrownBy(() -> env.stage(resource)).isInstanceOf(ExecutionEnvironmentUnavailableException.class);
    }

    @Test
    @DisplayName("a string cause works without an exception")
    void stringCause() {
        final ExecutionEnvironment unavailable = UnavailableExecutionEnvironment.of("no provider");
        assertThat(unavailable.descriptor().notes()).hasValue("execution environment unavailable: no provider");
        assertThatThrownBy(() -> unavailable.fileSystem().list("."))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class).hasMessageContaining("no provider");
    }

    @Test
    @DisplayName("proxies answer toString, equals and hashCode without throwing")
    void objectMethods() {
        assertThat(env.fileSystem().toString()).contains("sandbox down");
        assertThat(env.fileSystem()).isEqualTo(env.fileSystem());
        assertThat(env.shell().hashCode()).isEqualTo(env.shell().hashCode());
    }
}
