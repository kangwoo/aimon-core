package at.aimon.core.skill.fork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.tools.ToolContextKeys;

class SubagentBackedSkillForkExecutorTest {

    private SubagentRegistry subagentRegistry;
    private SubagentExecutionManager subagentExecutionManager;
    private SubagentBackedSkillForkExecutor executor;

    @BeforeEach
    void setUp() {
        final LlmModel model = mock(LlmModel.class);
        subagentRegistry = mock(SubagentRegistry.class);
        final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        final HookRegistry hookRegistry = mock(HookRegistry.class);
        final Environment environment = mock(Environment.class);
        subagentExecutionManager = mock(SubagentExecutionManager.class);

        executor = new SubagentBackedSkillForkExecutor(model, subagentRegistry, toolRegistry, hookRegistry, environment,
                subagentExecutionManager);
    }

    private static Skill forkSkill(String agentName) {
        return Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("Review code")
                        .executionMode(ExecutionMode.FORK).forkAgentName(agentName).build())
                .content(SkillContent.of("body")).build();
    }

    private static ToolContext contextWithExecutionId(String id) {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeIds.testCtx(id)).build();
    }

    @Test
    void constructor_NullArguments_Throw() {
        final LlmModel model = mock(LlmModel.class);
        final SubagentRegistry reg = mock(SubagentRegistry.class);
        final ToolRegistry tools = mock(ToolRegistry.class);
        final HookRegistry hooks = mock(HookRegistry.class);
        final Environment env = mock(Environment.class);
        final SubagentExecutionManager mgr = mock(SubagentExecutionManager.class);

        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(null, reg, tools, hooks, env, mgr))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Default model");
        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(model, null, tools, hooks, env, mgr))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Subagent registry");
        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(model, reg, null, hooks, env, mgr))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Tool registry");
        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(model, reg, tools, null, env, mgr))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook registry");
        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(model, reg, tools, hooks, null, mgr))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Environment");
        assertThatThrownBy(() -> new SubagentBackedSkillForkExecutor(model, reg, tools, hooks, env, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Subagent execution manager");
    }

    @Test
    void fork_DelegatesToSubagentExecutionManagerWithRenderedGoal() {
        // Arrange — a known subagent and a successful execution result
        when(subagentRegistry.getSubagent("code-reviewer")).thenReturn(Optional.of(mock(Subagent.class)));
        SubagentExecutionResult success = SubagentExecutionResult.success("LGTM",
                SessionSnapshot.of(SessionId.generate(), "sys", List.of()),
                ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty())
                        .timestamps(Instant.now(), Instant.now()).build());
        when(subagentExecutionManager.execute(any(SubagentExecutionEnvironment.class), any(), eq("code-reviewer"),
                eq("rendered body"), any())).thenReturn(success);

        // Act
        SkillForkOutcome outcome = executor.fork(forkSkill("code-reviewer"), "rendered body",
                contextWithExecutionId("ctx-42"));

        // Assert — outcome carries the subagent's final answer; the env was built with the parent context ID
        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.getFinalAnswer()).contains("LGTM");

        ArgumentCaptor<SubagentExecutionEnvironment> envCaptor = ArgumentCaptor
                .forClass(SubagentExecutionEnvironment.class);
        verify(subagentExecutionManager).execute(envCaptor.capture(), any(), eq("code-reviewer"), eq("rendered body"),
                any());
        assertThat(envCaptor.getValue().getAgentRuntimeId()).isEqualTo(AgentRuntimeIds.testCtx("ctx-42"));
    }

    @Test
    void fork_ForwardsTheCallersConversationAsTheInvoker() {
        final SessionId caller = SessionId.generate();

        final SubagentExecutionEnvironment env = captureEnvFor(
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeIds.testCtx("ctx-42"))
                        .put(ToolContextKeys.SESSION_ID, caller).build());

        assertThat(env.getInvokingSessionId()).contains(caller);
    }

    @Test
    void fork_FromWithinAForkHandsDownTheInheritedConversation() {
        // Depth 2: a fork-mode skill invoked by a subagent. Handing down the intermediate fork's OWN id would end the
        // reach here, because nothing is ever granted under a fork's own id.
        final SessionId user = SessionId.generate();
        final SessionId intermediateFork = SessionId.generate();

        final SubagentExecutionEnvironment env = captureEnvFor(
                ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeIds.testCtx("ctx-42"))
                        .put(ToolContextKeys.SESSION_ID, intermediateFork)
                        .put(ToolContextKeys.INVOKING_SESSION_ID, user).build());

        assertThat(env.getInvokingSessionId()).contains(user);
        assertThat(env.getInvokingSessionId()).isNotEqualTo(Optional.of(intermediateFork));
    }

    @Test
    void fork_WithoutAnyConversation_LeavesTheInvokerEmpty() {
        final SubagentExecutionEnvironment env = captureEnvFor(contextWithExecutionId("ctx-42"));

        assertThat(env.getInvokingSessionId()).isEmpty();
    }

    /** Runs a successful fork against the given context and returns the environment the manager was handed. */
    private SubagentExecutionEnvironment captureEnvFor(ToolContext context) {
        when(subagentRegistry.getSubagent("code-reviewer")).thenReturn(Optional.of(mock(Subagent.class)));
        when(subagentExecutionManager.execute(any(SubagentExecutionEnvironment.class), any(), eq("code-reviewer"),
                eq("rendered body"), any()))
                        .thenReturn(SubagentExecutionResult.success("LGTM",
                                SessionSnapshot.of(SessionId.generate(), "sys", List.of()),
                                ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty())
                                        .timestamps(Instant.now(), Instant.now()).build()));

        executor.fork(forkSkill("code-reviewer"), "rendered body", context);

        final ArgumentCaptor<SubagentExecutionEnvironment> captor = ArgumentCaptor
                .forClass(SubagentExecutionEnvironment.class);
        verify(subagentExecutionManager).execute(captor.capture(), any(), eq("code-reviewer"), eq("rendered body"),
                any());
        return captor.getValue();
    }

    @Test
    void fork_PropagatesSubagentFailureMessage() {
        when(subagentRegistry.getSubagent("code-reviewer")).thenReturn(Optional.of(mock(Subagent.class)));
        SubagentExecutionResult failure = SubagentExecutionResult.failure("subagent crashed",
                SessionSnapshot.of(SessionId.generate()),
                ExecutionMetadata.builder().iterationCount(0).tokenUsage(TokenUsage.empty())
                        .timestamps(Instant.now(), Instant.now()).build());
        when(subagentExecutionManager.execute(any(), any(), any(), any(), any())).thenReturn(failure);

        SkillForkOutcome outcome = executor.fork(forkSkill("code-reviewer"), "goal", contextWithExecutionId("ctx-1"));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).contains("subagent crashed");
    }

    @Test
    void fork_UnknownSubagent_FailsFastWithoutInvokingManager() {
        when(subagentRegistry.getSubagent("missing")).thenReturn(Optional.empty());

        SkillForkOutcome outcome = executor.fork(forkSkill("missing"), "goal", contextWithExecutionId("ctx-1"));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).get().asString().contains("Skill 'review'")
                .contains("unknown subagent 'missing'");
        verify(subagentExecutionManager, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void fork_MissingAgentRuntimeId_FailsWithClearMessage() {
        when(subagentRegistry.getSubagent("code-reviewer")).thenReturn(Optional.of(mock(Subagent.class)));

        SkillForkOutcome outcome = executor.fork(forkSkill("code-reviewer"), "goal", ToolContext.empty());

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).get().asString().contains("agent runtime ID not available");
        verify(subagentExecutionManager, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void fork_ManagerThrows_WrappedAsFailure() {
        when(subagentRegistry.getSubagent("code-reviewer")).thenReturn(Optional.of(mock(Subagent.class)));
        when(subagentExecutionManager.execute(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("network down"));

        SkillForkOutcome outcome = executor.fork(forkSkill("code-reviewer"), "goal", contextWithExecutionId("ctx-1"));

        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).get().asString().contains("Fork execution failed").contains("network down");
    }
}
