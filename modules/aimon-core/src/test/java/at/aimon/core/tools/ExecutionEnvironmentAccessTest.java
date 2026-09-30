package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.TestExecutionEnvironments;

@DisplayName("ExecutionEnvironmentAccess")
class ExecutionEnvironmentAccessTest {

    @Test
    @DisplayName("require returns the published environment")
    void requirePresent() {
        final ExecutionEnvironment env = TestExecutionEnvironments.of(null);
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, env).build();
        assertThat(ExecutionEnvironmentAccess.require(context)).isSameAs(env);
        assertThat(ExecutionEnvironmentAccess.of(context)).containsSame(env);
    }

    @Test
    @DisplayName("require throws IllegalStateException without an environment; of is empty")
    void requireAbsent() {
        assertThatThrownBy(() -> ExecutionEnvironmentAccess.require(ToolContext.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ExecutionEnvironmentAccess.NO_ENVIRONMENT_MESSAGE);
        assertThat(ExecutionEnvironmentAccess.of(ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("providerOf returns the published provider, or empty")
    void providerOf() {
        final ExecutionEnvironmentProvider provider = request -> TestExecutionEnvironments.of(null);
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT_PROVIDER, provider)
                .build();
        assertThat(ExecutionEnvironmentAccess.providerOf(context)).containsSame(provider);
        assertThat(ExecutionEnvironmentAccess.providerOf(ToolContext.empty())).isEmpty();
    }

    @Test
    @DisplayName("null context is rejected")
    void nullContext() {
        assertThatThrownBy(() -> ExecutionEnvironmentAccess.require(null)).isInstanceOf(NullPointerException.class);
    }
}
