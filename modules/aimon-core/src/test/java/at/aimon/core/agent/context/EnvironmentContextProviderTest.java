package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;

@DisplayName("EnvironmentContextProvider Tests")
class EnvironmentContextProviderTest {

    private final EnvironmentContextProvider provider = new EnvironmentContextProvider();

    @Test
    @DisplayName("emits a SYSTEM block with the working directory, platform, and OS version")
    void emitsEnvironmentBlock() {
        Environment env = Environment.builder().workingDirectory("/work").platform("darwin").osVersion("25.5.0")
                .timeZone(ZoneId.of("UTC")).build();
        ContextAssemblyRequest request = ContextAssemblyRequest.builder().environment(env).build();

        List<ContextBlock> blocks = provider.provide(request);

        assertThat(blocks).hasSize(1);
        ContextBlock block = blocks.get(0);
        assertThat(block.getKind()).isEqualTo(ContextBlockKind.SYSTEM);
        assertThat(block.getKey()).isEqualTo(EnvironmentContextProvider.BLOCK_KEY);
        assertThat(block.getBody()).contains("Working directory: /work").contains("Platform: darwin")
                .contains("OS Version: 25.5.0");
    }

    @Test
    @DisplayName("emits nothing when no environment is bound")
    void emptyWhenNoEnvironment() {
        assertThat(provider.provide(ContextAssemblyRequest.builder().build())).isEmpty();
    }
}
