package at.aimon.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.tools.ExecutionEnvironmentAccess;

@DisplayName("ExecutionEnvironments.resolveOrUnavailable")
class ExecutionEnvironmentsTest {

    private final EnvironmentRequest request = EnvironmentRequest.builder()
            .agentRuntimeId(AgentRuntimeId.of("agent:test")).build();

    @Test
    @DisplayName("returns what the provider resolves")
    void returnsResolved() {
        final ExecutionEnvironment env = TestExecutionEnvironments.of(null);
        assertThat(ExecutionEnvironments.resolveOrUnavailable(r -> env, request)).isSameAs(env);
    }

    @Test
    @DisplayName("a throwing provider yields an unavailable environment carrying the cause")
    void throwingProvider() {
        final ExecutionEnvironment env = ExecutionEnvironments.resolveOrUnavailable(r -> {
            throw new IllegalStateException("boom");
        }, request);
        assertThat(env).isInstanceOf(UnavailableExecutionEnvironment.class);
        assertThat(env.descriptor().notes()).hasValueSatisfying(n -> assertThat(n).contains("boom"));
        assertThatThrownBy(() -> env.fileSystem().read("x"))
                .isInstanceOf(ExecutionEnvironmentUnavailableException.class);
    }

    @Test
    @DisplayName("a null provider yields an unavailable environment")
    void nullProvider() {
        final ExecutionEnvironment env = ExecutionEnvironments.resolveOrUnavailable(null, request);
        assertThat(env).isInstanceOf(UnavailableExecutionEnvironment.class);
        assertThat(env.descriptor().notes()).hasValueSatisfying(n -> assertThat(n).contains("no"));
    }

    @Test
    @DisplayName("a provider returning null yields an unavailable environment")
    void nullReturn() {
        assertThat(ExecutionEnvironments.resolveOrUnavailable(r -> null, request))
                .isInstanceOf(UnavailableExecutionEnvironment.class);
    }

    @Test
    @DisplayName("require on a context without an environment throws IllegalStateException")
    void requireOnEmptyContext() {
        assertThatThrownBy(() -> ExecutionEnvironmentAccess.require(ToolContext.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ExecutionEnvironmentAccess.NO_ENVIRONMENT_MESSAGE);
    }
}
