package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;

/**
 * Covers the inline single-subagent execution primitive
 * {@link SubagentExecutionManager#execute(SubagentExecutionEnvironment, Subagent, String)}: running a code-defined
 * {@link Subagent} once without registering it in the environment's {@link SubagentRegistry}. This is the foundation
 * the
 * workflow layer's {@code agent()} call builds on.
 */
@DisplayName("DefaultSubagentExecutionManager — inline subagent execution")
class DefaultSubagentExecutionManagerInlineTest {

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("runs an inline subagent that is NOT in the environment's registry, via the ReAct executor")
    void runsInlineSubagentWithoutRegistryRegistration() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("inline-answer"));
        final Subagent inline = Subagent.builder().name("poet").systemPrompt("(inline)").build();

        // The environment's registry is empty — the inline subagent is never registered.
        final SubagentExecutionResult result = newManager().execute(env(new InMemorySubagentRegistry()), inline,
                "write a haiku");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("inline-answer");
        verify(reactExecutor).execute(any(), any());
    }

    @Test
    @DisplayName("forwards the inline subagent and goal into the executor context/request")
    void forwardsInlineSubagentAndGoal() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("ok"));
        final Subagent inline = Subagent.builder().name("analyst").systemPrompt("(inline)").build();

        newManager().execute(env(new InMemorySubagentRegistry()), inline, "summarize the logs");

        final ArgumentCaptor<SubagentExecutionContext> ctxCaptor = ArgumentCaptor
                .forClass(SubagentExecutionContext.class);
        final ArgumentCaptor<SubagentExecutionRequest> reqCaptor = ArgumentCaptor
                .forClass(SubagentExecutionRequest.class);
        verify(reactExecutor).execute(ctxCaptor.capture(), reqCaptor.capture());

        assertThat(ctxCaptor.getValue().getSubagent()).isSameAs(inline);
        assertThat(reqCaptor.getValue().getGoal()).isEqualTo("summarize the logs");
        // A non-blank task id is generated internally so hook/attribution tracking has an identity.
        assertThat(reqCaptor.getValue().getTaskId()).isNotBlank();
    }

    @Test
    @DisplayName("never throws for an execution failure: a thrown executor error becomes an unsuccessful result")
    void wrapsExecutorExceptionAsFailure() {
        when(reactExecutor.execute(any(), any())).thenThrow(new RuntimeException("boom"));
        final Subagent inline = Subagent.builder().name("flaky").systemPrompt("(inline)").build();

        final SubagentExecutionResult result = newManager().execute(env(new InMemorySubagentRegistry()), inline, "go");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("boom");
    }

    @Test
    @DisplayName("a behavior registered under the inline subagent's name replaces the ReAct loop (dispatch is "
            + "name-consistent)")
    void registeredBehaviorUnderInlineNameReplacesReActLoop() {
        final InMemorySubagentBehaviorRegistry behaviorRegistry = new InMemorySubagentBehaviorRegistry();
        behaviorRegistry.register("poet", (ctx, req, support) -> support.success("verse"));
        final Subagent inline = Subagent.builder().name("poet").systemPrompt("(inline)").build();

        final SubagentExecutionResult result = newManager(behaviorRegistry).execute(env(new InMemorySubagentRegistry()),
                inline, "write a haiku");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("verse");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("rejects null env / subagent / goal")
    void rejectsNullArguments() {
        final DefaultSubagentExecutionManager manager = newManager();
        final SubagentExecutionEnvironment env = env(new InMemorySubagentRegistry());
        final Subagent inline = Subagent.builder().name("poet").systemPrompt("(inline)").build();

        assertThatNullPointerException().isThrownBy(() -> manager.execute(null, inline, "go"));
        assertThatNullPointerException().isThrownBy(() -> manager.execute(env, (Subagent) null, "go"));
        assertThatNullPointerException().isThrownBy(() -> manager.execute(env, inline, null));
    }

    private DefaultSubagentExecutionManager newManager() {
        return newManager(new InMemorySubagentBehaviorRegistry());
    }

    private DefaultSubagentExecutionManager newManager(InMemorySubagentBehaviorRegistry behaviorRegistry) {
        return new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, behaviorRegistry);
    }

    private static SubagentExecutionEnvironment env(SubagentRegistry subagentRegistry) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagentRegistry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }

    private static SubagentExecutionResult reactResult(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
