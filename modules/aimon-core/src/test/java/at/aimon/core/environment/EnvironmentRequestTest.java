package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
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

    @Test
    @DisplayName("requestAttributes is empty by default and when set to null")
    void requestAttributesDefaultEmpty() {
        assertThat(EnvironmentRequest.builder().agentRuntimeId(RUNTIME).build().requestAttributes()).isEmpty();
        assertThat(EnvironmentRequest.builder().agentRuntimeId(RUNTIME).requestAttributes(null).build()
                .requestAttributes()).isEmpty();
    }

    @Test
    @DisplayName("requestAttributes is a defensive, unmodifiable copy and does not touch definitionAttributes")
    void requestAttributesCopied() {
        final Map<String, String> source = new HashMap<>(Map.of("sandbox.workspace", "ws-7"));
        final EnvironmentRequest request = EnvironmentRequest.builder().agentRuntimeId(RUNTIME).agent(DefaultAgent
                .builder().name("main").systemPrompt("p").attributes(Map.of("sandbox.slot", "main")).build())
                .requestAttributes(source).build();
        source.put("sandbox.other", "x");

        assertThat(request.requestAttributes()).containsExactly(Map.entry("sandbox.workspace", "ws-7"));
        assertThat(request.definitionAttributes()).containsExactly(Map.entry("sandbox.slot", "main"));
        assertThatThrownBy(() -> request.requestAttributes().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("toString names the request attribute keys and never their values")
    void toStringHidesValues() {
        final EnvironmentRequest request = EnvironmentRequest.builder().agentRuntimeId(RUNTIME)
                .requestAttributes(Map.of("sandbox.workspace", "tenant-secret", "mode", "strict")).build();

        assertThat(request.toString()).contains("requestAttributes=[mode, sandbox.workspace]")
                .doesNotContain("tenant-secret").doesNotContain("strict");
        assertThat(EnvironmentRequest.builder().agentRuntimeId(RUNTIME).build().toString())
                .doesNotContain("requestAttributes");
    }
}
