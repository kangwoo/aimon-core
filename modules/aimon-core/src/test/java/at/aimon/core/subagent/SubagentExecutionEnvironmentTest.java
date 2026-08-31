package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;

class SubagentExecutionEnvironmentTest {

    private static final AgentRuntimeId CONTEXT_ID = AgentRuntimeId.of("agent:test-1");
    private static final ToolRegistry TOOL_REGISTRY = new DefaultToolRegistry();
    private static final HookRegistry HOOK_REGISTRY = new DefaultHookRegistry();
    private static final Environment ENVIRONMENT = Environment.createDefault();
    private static final LlmModel DEFAULT_MODEL = LlmModel.builder().name("test-model").build();
    private static final SubagentRegistry SUBAGENT_REGISTRY = new EmptySubagentRegistry();
    private static final SessionId INVOKING_CONVERSATION = SessionId.generate();

    @Test
    void builder_allFieldsSet_returnsCorrectValues() {
        Map<String, Object> attrs = Map.of("key", "value");

        SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).executionAttributes(attrs).build();

        assertThat(env.getAgentRuntimeId()).isEqualTo(CONTEXT_ID);
        assertThat(env.getSubagentRegistry()).isEqualTo(SUBAGENT_REGISTRY);
        assertThat(env.getToolRegistry()).isEqualTo(TOOL_REGISTRY);
        assertThat(env.getHookRegistry()).isEqualTo(HOOK_REGISTRY);
        assertThat(env.getEnvironment()).isEqualTo(ENVIRONMENT);
        assertThat(env.getDefaultModel()).isEqualTo(DEFAULT_MODEL);
        assertThat(env.getExecutionAttributes()).isEqualTo(attrs);
    }

    @Test
    void toBuilder_rebuild_preservesEveryField() {
        final CancellationSignal signal = mock(CancellationSignal.class);
        final SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).modelOverride("override")
                .executionAttributes(Map.of("k", "v")).cancellationSignal(signal)
                .invokingSessionId(INVOKING_CONVERSATION).build();

        final SubagentExecutionEnvironment rebuilt = env.toBuilder().build();

        // Borrowed collaborators are shared (same references), not copied — the runner still owns nothing.
        assertThat(rebuilt.getAgentRuntimeId()).isEqualTo(CONTEXT_ID);
        assertThat(rebuilt.getSubagentRegistry()).isSameAs(SUBAGENT_REGISTRY);
        assertThat(rebuilt.getToolRegistry()).isSameAs(TOOL_REGISTRY);
        assertThat(rebuilt.getHookRegistry()).isSameAs(HOOK_REGISTRY);
        assertThat(rebuilt.getEnvironment()).isSameAs(ENVIRONMENT);
        assertThat(rebuilt.getDefaultModel()).isSameAs(DEFAULT_MODEL);
        assertThat(rebuilt.getModelOverride()).contains("override");
        assertThat(rebuilt.getExecutionAttributes()).containsEntry("k", "v");
        assertThat(rebuilt.getCancellationSignal()).isSameAs(signal);
        // A field dropped here is dropped silently: the run still works, it just quietly stops inheriting the user's
        // skill approvals. Nothing else would fail.
        assertThat(rebuilt.getInvokingSessionId()).contains(INVOKING_CONVERSATION);
    }

    @Test
    void toBuilder_overrideCancellationSignal_changesOnlyTheSignal() {
        final CancellationSignal original = mock(CancellationSignal.class);
        final CancellationSignal perRun = mock(CancellationSignal.class);
        final SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).cancellationSignal(original).build();

        final SubagentExecutionEnvironment derived = env.toBuilder().cancellationSignal(perRun).build();

        assertThat(derived.getCancellationSignal()).isSameAs(perRun);
        assertThat(env.getCancellationSignal()).isSameAs(original); // original is unchanged
        // Everything else is shared with the base env.
        assertThat(derived.getToolRegistry()).isSameAs(TOOL_REGISTRY);
        assertThat(derived.getSubagentRegistry()).isSameAs(SUBAGENT_REGISTRY);
        assertThat(derived.getAgentRuntimeId()).isEqualTo(CONTEXT_ID);
    }

    @Test
    void builder_nullRuntimeId_throwsNullPointerException() {
        assertThatThrownBy(() -> SubagentExecutionEnvironment.builder().subagentRegistry(SUBAGENT_REGISTRY)
                .toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT)
                .defaultModel(DEFAULT_MODEL).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Agent runtime ID");
    }

    @Test
    void builder_nullSubagentRegistry_throwsNullPointerException() {
        assertThatThrownBy(
                () -> SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID).toolRegistry(TOOL_REGISTRY)
                        .hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Subagent registry");
    }

    @Test
    void builder_nullToolRegistry_throwsNullPointerException() {
        assertThatThrownBy(() -> SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).hookRegistry(HOOK_REGISTRY).environment(ENVIRONMENT)
                .defaultModel(DEFAULT_MODEL).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool registry");
    }

    @Test
    void builder_nullHookRegistry_throwsNullPointerException() {
        assertThatThrownBy(() -> SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).environment(ENVIRONMENT)
                .defaultModel(DEFAULT_MODEL).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Hook registry");
    }

    @Test
    void builder_nullEnvironment_throwsNullPointerException() {
        assertThatThrownBy(() -> SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .defaultModel(DEFAULT_MODEL).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Environment");
    }

    @Test
    void builder_nullDefaultModel_throwsNullPointerException() {
        assertThatThrownBy(() -> SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Default model");
    }

    @Test
    void executionAttributes_defensiveCopy() {
        HashMap<String, Object> mutableMap = new HashMap<>();
        mutableMap.put("key", "value");

        SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).executionAttributes(mutableMap).build();

        mutableMap.put("newKey", "newValue");

        assertThat(env.getExecutionAttributes()).hasSize(1).containsEntry("key", "value");
    }

    @Test
    void executionAttributes_nullReturnsEmptyMap() {
        SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).executionAttributes(null).build();

        assertThat(env.getExecutionAttributes()).isEmpty();
    }

    @Test
    void executionAttributes_notSet_returnsEmptyMap() {
        SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).build();

        assertThat(env.getExecutionAttributes()).isEmpty();
    }

    @Test
    void toString_containsMeaningfulOutput() {
        SubagentExecutionEnvironment env = SubagentExecutionEnvironment.builder().agentRuntimeId(CONTEXT_ID)
                .subagentRegistry(SUBAGENT_REGISTRY).toolRegistry(TOOL_REGISTRY).hookRegistry(HOOK_REGISTRY)
                .environment(ENVIRONMENT).defaultModel(DEFAULT_MODEL).executionAttributes(Map.of("key", "value"))
                .build();

        String result = env.toString();
        assertThat(result).contains("SubagentExecutionEnvironment").contains("agentRuntimeId")
                .contains("executionAttributes").contains("key");
    }

    /** Minimal SubagentRegistry implementation for testing. */
    private static class EmptySubagentRegistry implements SubagentRegistry {
        @Override
        public Optional<Subagent> getSubagent(String name) {
            return Optional.empty();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return List.of();
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }
}
