package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.WorkflowEventSink;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;

@DisplayName("DefaultWorkflowRunner — agent/parallel/pipeline, backstop, reentry, scope safety, events")
class DefaultWorkflowRunnerTest {

    private final List<DefaultWorkflowRunner> runners = new ArrayList<>();
    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        // Fake inline execution: echo the goal so routing is observable. Never-throw contract preserved.
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> success("ans:" + invocation.getArgument(2, String.class)));
        env = env();
        sub = subagent("worker");
    }

    @AfterEach
    void tearDown() {
        runners.forEach(DefaultWorkflowRunner::close);
    }

    @Test
    @DisplayName("Parallel runs every thunk and returns results in input order")
    void parallelReturnsInInputOrder() {
        final List<AgentStepResult> results = runner(WorkflowConcurrencyConfig.enabled(4)).run(ctx -> ctx
                .parallel(List.of(() -> ctx.agent(sub, "g0"), () -> ctx.agent(sub, "g1"), () -> ctx.agent(sub, "g2"))));

        assertThat(results).extracting(AgentStepResult::text).containsExactly("ans:g0", "ans:g1", "ans:g2");
    }

    @Test
    @DisplayName("Pipeline flows each item through stage1 -> stage2, in input order, passing the original item")
    void pipelineFlowsItemsThroughStages() {
        final List<String> out = runner(WorkflowConcurrencyConfig.enabled(4)).run(ctx -> ctx.pipeline(List.of("a", "b"),
                item -> ctx.agent(sub, "s1-" + item), (step, item) -> step.text() + "|" + item));

        assertThat(out).containsExactly("ans:s1-a|a", "ans:s1-b|b");
    }

    @Test
    @DisplayName("The agent-count backstop aborts the run on a DIRECT (script-thread) over-limit call")
    void backstopAbortsOnDirectCall() {
        assertThatThrownBy(
                () -> runner(WorkflowConcurrencyConfig.enabled(4), WorkflowEventSink.NO_OP, WorkflowBudget.ofAgents(2))
                        .run(ctx -> {
                            ctx.agent(sub, "1");
                            ctx.agent(sub, "2");
                            return ctx.agent(sub, "3"); // exceeds max 2 agents
                        }))
                .isInstanceOf(WorkflowBudgetExceededException.class);
    }

    @Test
    @DisplayName("T8/B1: the backstop also aborts the run from inside parallel() — it is NOT swallowed on the fan-out "
            + "path")
    void backstopAbortsFromFanoutPath() {
        assertThatThrownBy(
                () -> runner(WorkflowConcurrencyConfig.enabled(4), WorkflowEventSink.NO_OP, WorkflowBudget.ofAgents(2))
                        .run(ctx -> {
                            final List<Supplier<AgentStepResult>> thunks = new ArrayList<>();
                            for (int i = 0; i < 5; i++) {
                                final int n = i;
                                thunks.add(() -> ctx.agent(sub, "g" + n));
                            }
                            return ctx.parallel(thunks); // 5 agents, max 2 → run-fatal must propagate out of the
                                                         // fan-out
                        }))
                .isInstanceOf(WorkflowBudgetExceededException.class);
    }

    @Test
    @DisplayName("Nested parallel runs sequentially instead of deadlocking the bounded pool")
    void nestedParallelDoesNotDeadlock() {
        final List<List<String>> out = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> runner(WorkflowConcurrencyConfig.enabled(2)).run(ctx -> {
                    final List<Supplier<List<String>>> outer = new ArrayList<>();
                    for (int o = 0; o < 3; o++) {
                        final int oo = o;
                        outer.add(() -> {
                            final List<Supplier<String>> inner = new ArrayList<>();
                            for (int i = 0; i < 3; i++) {
                                final int ii = i;
                                inner.add(() -> ctx.agent(sub, "o" + oo + "-i" + ii).text());
                            }
                            return ctx.parallel(inner);
                        });
                    }
                    return ctx.parallel(outer);
                }));

        assertThat(out).hasSize(3);
        assertThat(out).allSatisfy(inner -> assertThat(inner).hasSize(3));
        assertThat(out.stream().flatMap(List::stream).toList()).containsExactlyInAnyOrder("ans:o0-i0", "ans:o0-i1",
                "ans:o0-i2", "ans:o1-i0", "ans:o1-i1", "ans:o1-i2", "ans:o2-i0", "ans:o2-i1", "ans:o2-i2");
    }

    @Test
    @DisplayName("close() is a no-op — the runner does not close its borrowed collaborators and stays usable")
    void closeDoesNotCloseBorrowedCollaborators() {
        final DefaultWorkflowRunner runner = new DefaultWorkflowRunner(manager, env);

        final List<AgentStepResult> before = runner.run(ctx -> List.of(ctx.agent(sub, "a")));
        runner.close();
        // Still usable after close: close() released nothing the runner needs, and never touched the manager/env.
        final List<AgentStepResult> after = runner.run(ctx -> List.of(ctx.agent(sub, "b")));

        assertThat(before).extracting(AgentStepResult::text).containsExactly("ans:a");
        assertThat(after).extracting(AgentStepResult::text).containsExactly("ans:b");
    }

    @Test
    @DisplayName("A small review -> verify script runs end-to-end (parallel finders, pipeline verify)")
    void reviewThenVerifyScript() {
        final Subagent bug = subagent("bug");
        final Subagent perf = subagent("perf");
        final Subagent verifier = subagent("verifier");

        final List<String> verdicts = runner(WorkflowConcurrencyConfig.enabled(4)).run(ctx -> {
            ctx.phase("Review");
            final List<AgentStepResult> found = ctx
                    .parallel(List.of(() -> ctx.agent(bug, "find bugs"), () -> ctx.agent(perf, "find perf")));
            ctx.phase("Verify");
            return ctx.pipeline(found.stream().filter(Objects::nonNull).toList(), AgentStepResult::text,
                    (text, step) -> ctx.agent(verifier, "verify: " + text).text());
        });

        assertThat(verdicts).containsExactly("ans:verify: ans:find bugs", "ans:verify: ans:find perf");
    }

    @Test
    @DisplayName("The event sink receives phase/log/agentStarted/agentCompleted")
    void eventSinkReceivesSignals() {
        final RecordingSink sink = new RecordingSink();

        runner(WorkflowConcurrencyConfig.enabled(4), sink, WorkflowBudget.defaults()).run(ctx -> {
            ctx.phase("P1");
            ctx.log("hello");
            return ctx.parallel(List.of(() -> ctx.agent(sub, "g0"), () -> ctx.agent(sub, "g1")));
        });

        assertThat(sink.phases).containsExactly("P1");
        assertThat(sink.logs).containsExactly("hello");
        assertThat(sink.started).hasSize(2);
        assertThat(sink.completed).hasSize(2);
    }

    @Test
    @DisplayName("The aggregate token budget aborts the run once the token ceiling is reached")
    void tokenBudgetAbortsRun() {
        // Each agent spends 100 tokens; a 250-token ceiling admits the first three (spend 0/100/200 all < 250), the
        // third crosses to 300, and the fourth is refused (post-hoc: the crosser completes, the next is stopped).
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> successWithTokens("ans:" + invocation.getArgument(2, String.class), 100));
        final DefaultWorkflowRunner runner = runner(WorkflowConcurrencyConfig.disabled(),
                WorkflowEventSink.NO_OP, WorkflowBudget.of(1000, 250));

        assertThatThrownBy(() -> runner.run(ctx -> {
            for (int i = 0; i < 10; i++) {
                ctx.agent(sub, "g" + i);
            }
            return null;
        })).isInstanceOf(WorkflowBudgetExceededException.class).hasMessageContaining("token");
    }

    @Test
    @DisplayName("The aggregate USD cost budget aborts the run once the cost ceiling is reached")
    void costBudgetAbortsRun() {
        // Each agent costs $0.001 (1000 micros); a $0.0025 (2500 micros) ceiling admits the first three (spend
        // 0/1000/2000 all < 2500), the third crosses to 3000, and the fourth is refused (post-hoc, like the token
        // ceiling).
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> successWithCost("ans:" + invocation.getArgument(2, String.class), 0.001));
        final DefaultWorkflowRunner runner = runner(WorkflowConcurrencyConfig.disabled(),
                WorkflowEventSink.NO_OP, WorkflowBudget.of(1000, 0, 0.0025));

        assertThatThrownBy(() -> runner.run(ctx -> {
            for (int i = 0; i < 10; i++) {
                ctx.agent(sub, "g" + i);
            }
            return null;
        })).isInstanceOf(WorkflowBudgetExceededException.class).hasMessageContaining("cost");
    }

    @Test
    @DisplayName("A task with a result schema exposes parsed structured output; malformed output → empty")
    void structuredOutput() {
        final Map<String, Object> schema = Map.of("type", "object", "required", List.of("name", "score"), "properties",
                Map.of("name", Map.of("type", "string"), "score", Map.of("type", "integer")));
        final AgentTask task = AgentTask.builder().subagent(sub).goal("profile the user").resultSchema(schema).build();

        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenReturn(success("{\"name\": \"alice\", \"score\": 7}"));
        final AgentStepResult ok = runner(WorkflowConcurrencyConfig.disabled()).run(ctx -> ctx.agent(task));
        assertThat(ok.structured()).get()
                .satisfies(m -> assertThat(m).containsEntry("name", "alice").containsEntry("score", 7));

        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenReturn(success("sorry, I cannot"));
        final AgentStepResult bad = runner(WorkflowConcurrencyConfig.disabled()).run(ctx -> ctx.agent(task));
        assertThat(bad.structured()).isEmpty();
        assertThat(bad.text()).isEqualTo("sorry, I cannot");
    }

    @Test
    @DisplayName("rejects null constructor args, null script, and an invalid agent budget")
    void rejectsInvalidArguments() {
        assertThatNullPointerException().isThrownBy(() -> new DefaultWorkflowRunner(null, env));
        assertThatNullPointerException().isThrownBy(() -> new DefaultWorkflowRunner(manager, null));
        assertThatNullPointerException().isThrownBy(() -> new DefaultWorkflowRunner(manager, env,
                WorkflowConcurrencyConfig.defaults(), WorkflowEventSink.NO_OP, null));
        assertThatIllegalArgumentException().isThrownBy(() -> WorkflowBudget.ofAgents(0));
        assertThatNullPointerException().isThrownBy(() -> runner(WorkflowConcurrencyConfig.enabled(4)).run(null));
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private DefaultWorkflowRunner runner(WorkflowConcurrencyConfig config) {
        return runner(config, WorkflowEventSink.NO_OP, WorkflowBudget.defaults());
    }

    private DefaultWorkflowRunner runner(WorkflowConcurrencyConfig config, WorkflowEventSink sink,
            WorkflowBudget budget) {
        final DefaultWorkflowRunner runner = new DefaultWorkflowRunner(manager, env, config, sink, budget);
        runners.add(runner);
        return runner;
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }

    private static SubagentExecutionResult successWithTokens(String answer, int totalTokens) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()),
                ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.of(0, totalTokens, totalTokens))
                        .timestamps(now, now).build());
    }

    private static SubagentExecutionResult successWithCost(String answer, double costUsd) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(
                answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata.builder().iterationCount(1)
                        .tokenUsage(TokenUsage.empty()).timestamps(now, now).build(),
                CompletionReason.COMPLETED, Money.usd(costUsd));
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }

    /** Thread-safe recording sink: agent events fire on worker threads, so every collection is concurrent. */
    private static final class RecordingSink implements WorkflowEventSink {
        private final Queue<String> phases = new ConcurrentLinkedQueue<>();
        private final Queue<String> logs = new ConcurrentLinkedQueue<>();
        private final Queue<AgentTask> started = new ConcurrentLinkedQueue<>();
        private final Queue<AgentStepResult> completed = new ConcurrentLinkedQueue<>();

        @Override
        public void onPhase(String title) {
            phases.add(title);
        }

        @Override
        public void onLog(String message) {
            logs.add(message);
        }

        @Override
        public void onAgentStarted(AgentTask task) {
            started.add(task);
        }

        @Override
        public void onAgentCompleted(AgentTask task, AgentStepResult result) {
            completed.add(result);
        }
    }
}
