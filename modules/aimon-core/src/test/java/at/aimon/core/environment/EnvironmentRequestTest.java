package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;

@DisplayName("EnvironmentRequest")
class EnvironmentRequestTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:main");

    @Test
    @DisplayName("definitionAttributes prefers the fork's definition over the agent's")
    void forkWins() {
        final EnvironmentRequest request = EnvironmentRequest.builder().agentRuntimeId(RUNTIME)
                .agent(DefaultAgent.builder().name("main").systemPrompt("p").attributes(Map.of("sandbox.slot", "main"))
                        .build())
                .fork(ForkDefinition.builder().name("builder").attributes(Map.of("sandbox.slot", "build")).build())
                .build();

        assertThat(request.definitionAttributes()).containsExactly(Map.entry("sandbox.slot", "build"));
    }

    @Test
    @DisplayName("definitionAttributes falls back to the agent's, then to none")
    void agentThenNone() {
        final EnvironmentRequest mainTurn = EnvironmentRequest.builder().agentRuntimeId(RUNTIME).agent(DefaultAgent
                .builder().name("main").systemPrompt("p").attributes(Map.of("sandbox.slot", "main")).build()).build();
        final EnvironmentRequest bare = EnvironmentRequest.builder().agentRuntimeId(RUNTIME).build();

        assertThat(mainTurn.definitionAttributes()).containsExactly(Map.entry("sandbox.slot", "main"));
        assertThat(bare.definitionAttributes()).isEmpty();
    }
}
