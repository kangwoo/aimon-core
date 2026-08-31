package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;

/**
 * The {@link WorkflowRunners} assembly seam builds a fully-configured runner (resume cache, run store,
 * background pool, budget) from a neutral {@link WorkflowRunnerOptions} — <b>with no import of
 * {@code at.aimon.core.workflow.impl}</b>, exactly as a CLI / web bootstrap would.
 */
@DisplayName("WorkflowRunners — neutral options-based assembly")
class WorkflowRunnersTest {

    private final AtomicInteger execCount = new AtomicInteger();
    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    execCount.incrementAndGet();
                    return success("ans:" + invocation.getArgument(2, String.class));
                });
        env = env();
        sub = subagent("worker");
    }

    @Test
    @DisplayName("defaults() builds a usable foreground runner")
    void defaultsRunsForeground() {
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env, WorkflowRunnerOptions.defaults())) {
            final String out = runner.run(ctx -> ctx.agent(sub, "g").text(), RunId.from("fg"));
            assertThat(out).isEqualTo("ans:g");
        }
    }

    @Test
    @DisplayName("options with an in-memory resume cache enable replay across same-id runs (no impl import)")
    void optionsWithInMemoryCacheEnableResume() {
        final WorkflowRunnerOptions options = WorkflowRunnerOptions.builder()
                .stepResultCache(WorkflowRunners.inMemoryStepResultCache()).build();
        final RunId id = RunId.from("audit");

        try (WorkflowRunner runner = WorkflowRunners.create(manager, env, options)) {
            final List<String> first = runner
                    .run(ctx -> List.of(ctx.agent(sub, "a").text(), ctx.agent(sub, "b").text()), id);
            assertThat(first).containsExactly("ans:a", "ans:b");
            assertThat(execCount.get()).isEqualTo(2);

            final List<String> second = runner
                    .run(ctx -> List.of(ctx.agent(sub, "a").text(), ctx.agent(sub, "b").text()), id);
            assertThat(second).isEqualTo(first);
            assertThat(execCount.get()).isEqualTo(2); // both steps replayed from the injected cache
        }
    }

    @Test
    @DisplayName("options-built runner runs in the background and awaits a result")
    void optionsBuiltRunnerRunsInBackground() throws Exception {
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env,
                WorkflowRunnerOptions.builder().backgroundConfig(WorkflowBackgroundConfig.of(2)).build())) {
            final RunHandle<String> handle = runner.runInBackground(ctx -> ctx.agent(sub, "g").text(),
                    RunId.from("bg"));
            assertThat(handle.await(Duration.ofSeconds(5))).isEqualTo("ans:g");
        }
    }

    @Test
    @DisplayName("options carry a custom budget through to the runner")
    void optionsApplyCustomBudget() {
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env,
                WorkflowRunnerOptions.builder().budget(WorkflowBudget.ofAgents(1)).build())) {
            assertThatThrownBy(() -> runner.run(ctx -> {
                ctx.agent(sub, "1");
                return ctx.agent(sub, "2"); // exceeds the 1-agent ceiling
            }, RunId.from("budgeted"))).isInstanceOf(WorkflowBudgetExceededException.class);
        }
    }

    @Test
    @DisplayName("create rejects null options")
    void rejectsNullOptions() {
        assertThatThrownBy(() -> WorkflowRunners.create(manager, env, (WorkflowRunnerOptions) null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
