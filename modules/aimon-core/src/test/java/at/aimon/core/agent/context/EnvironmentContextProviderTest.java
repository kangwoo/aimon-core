package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;

@DisplayName("EnvironmentContextProvider Tests")
class EnvironmentContextProviderTest {

    private final EnvironmentContextProvider provider = new EnvironmentContextProvider();

    @Test
    @DisplayName("emits a SYSTEM block with the working directory, platform, and OS version of the execution's environment")
    void emitsEnvironmentBlock() {
        EnvironmentDescriptor descriptor = EnvironmentDescriptor.builder().workingDirectory("/work").platform("darwin")
                .osVersion("25.5.0").build();
        ContextAssemblyRequest request = ContextAssemblyRequest.builder()
                .executionEnvironment(TestExecutionEnvironments.withDescriptor(descriptor)).build();

        List<ContextBlock> blocks = provider.provide(request);

        assertThat(blocks).hasSize(1);
        ContextBlock block = blocks.get(0);
        assertThat(block.getKind()).isEqualTo(ContextBlockKind.SYSTEM);
        assertThat(block.getKey()).isEqualTo(EnvironmentContextProvider.BLOCK_KEY);
        assertThat(block.getBody()).isEqualTo("Here is useful information about the environment you are running in:\n\n"
                + "**Environment:**\n```\nWorking directory: /work\nPlatform: darwin\nOS Version: 25.5.0\n```");
    }

    @Test
    @DisplayName("describes where the commands run, not the JVM host: a Linux descriptor renders Linux on any host")
    void rendersTheDescriptorNotTheHost() {
        EnvironmentDescriptor descriptor = EnvironmentDescriptor.builder().workingDirectory("/workspace")
                .platform("linux").osVersion("6.8").notes("isolated sandbox").build();
        ContextAssemblyRequest request = ContextAssemblyRequest.builder()
                .executionEnvironment(TestExecutionEnvironments.withDescriptor(descriptor)).build();

        assertThat(provider.provide(request).get(0).getBody()).contains("Platform: linux").contains("OS Version: 6.8")
                .contains("Notes: isolated sandbox");
    }

    @Test
    @DisplayName("an unavailable environment renders its cause instead of host values")
    void rendersTheUnavailableNote() {
        ContextAssemblyRequest request = ContextAssemblyRequest.builder()
                .executionEnvironment(UnavailableExecutionEnvironment.of("sandbox down")).build();

        assertThat(provider.provide(request).get(0).getBody())
                .contains("Notes: execution environment unavailable: sandbox down").doesNotContain("Platform:")
                .doesNotContain("Working directory:");
    }

    @Test
    @DisplayName("emits nothing when no environment is bound")
    void emptyWhenNoEnvironment() {
        assertThat(provider.provide(ContextAssemblyRequest.builder().build())).isEmpty();
    }
}
