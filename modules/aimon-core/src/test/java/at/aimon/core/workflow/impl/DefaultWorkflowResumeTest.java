package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepResultCache;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowConcurrencyConfig;
import at.aimon.core.workflow.WorkflowEventSink;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowScript;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;

@DisplayName("DefaultWorkflowContext — resume via structural step-path + StepResultCache")
class DefaultWorkflowResumeTest {

    private final AtomicInteger execCount = new AtomicInteger();
    private final List<WorkflowRunner> runners = new ArrayList<>();
    private SubagentExecutionManager manager;
    private SubagentExecutionEnvironment env;
    private Subagent sub;

    @BeforeEach
    void setUp() {
        manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentExecutionEnvironment.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    execCount.incrementAndGet();
                    final String goal = invocation.getArgument(2, String.class);
                    // A goal marked "bad" fails (not COMPLETED) so it is never cached.
                    return goal.contains("bad")
                            ? SubagentExecutionResult.emptyFailure("boom", Instant.now())
                            : success("ans:" + goal);
                });
        env = env();
        sub = subagent("worker");
    }

    @AfterEach
    void tearDown() {
        runners.forEach(WorkflowRunner::close);
    }

    @Test
    @DisplayName("a sequential script replays completed steps on a same-id re-run (0 re-executions)")
    void sequentialResumeReplays() {
        final WorkflowRunner runner = runnerWithCache(new InMemoryStepResultCache());
        final RunId id = RunId.from("audit");
        final WorkflowScript<List<String>> script = ctx -> List.of(ctx.agent(sub, "a").text(),
                ctx.agent(sub, "b").text());

        final List<String> first = runner.run(script, id);
        final int afterFirst = execCount.get();
        final List<String> second = runner.run(script, id);

        assertThat(first).containsExactly("ans:a", "ans:b");
        assertThat(afterFirst).isEqualTo(2);
        assertThat(second).isEqualTo(first);
        assertThat(execCount.get()).isEqualTo(2); // every step replayed from cache
    }

    @Test
    @DisplayName("sibling parallels get distinct construct ordinals (p0 vs p1) — no cross-contamination on re-run")
    void siblingParallelsDoNotCollide() {
        final WorkflowRunner runner = runnerWithCache(new InMemoryStepResultCache());
        final RunId id = RunId.from("audit");
        // Different goals across the two parallels: a construct-ordinal collision would make the 2nd parallel's keys
        // match the 1st's, and its inputHash would mismatch -> a re-execution, which execCount would reveal.
        final WorkflowScript<List<String>> script = ctx -> {
            final List<AgentStepResult> p = ctx.parallel(List.of(() -> ctx.agent(sub, "x"), () -> ctx.agent(sub, "y")));
            final List<AgentStepResult> q = ctx.parallel(List.of(() -> ctx.agent(sub, "m"), () -> ctx.agent(sub, "n")));
            final List<String> out = new ArrayList<>();
            p.forEach(r -> out.add(r.text()));
            q.forEach(r -> out.add(r.text()));
            return out;
        };

        final List<String> first = runner.run(script, id);
        final List<String> second = runner.run(script, id);

        assertThat(first).containsExactly("ans:x", "ans:y", "ans:m", "ans:n");
        assertThat(second).isEqualTo(first);
        assertThat(execCount.get()).isEqualTo(4); // all four replayed, none re-run
    }

    @Test
    @DisplayName("branches within one parallel get distinct list indices — no in-parallel key collision on re-run")
    void parallelBranchesDoNotCollide() {
        final WorkflowRunner runner = runnerWithCache(new InMemoryStepResultCache());
        final RunId id = RunId.from("audit");
        // Distinct goals per branch: a list-index collision would overwrite one slot, making a branch's inputHash
        // mismatch on re-run -> a re-execution.
        final WorkflowScript<List<String>> script = ctx -> {
            final List<AgentStepResult> r = ctx.parallel(List.of(() -> ctx.agent(sub, "x"), () -> ctx.agent(sub, "y")));
            final List<String> out = new ArrayList<>();
            r.forEach(s -> out.add(s.text()));
            return out;
        };

        final List<String> first = runner.run(script, id);
        final List<String> second = runner.run(script, id);

        assertThat(first).containsExactly("ans:x", "ans:y");
        assertThat(second).isEqualTo(first);
        assertThat(execCount.get()).isEqualTo(2); // both replayed
    }

    @Test
    @DisplayName("a failed step is not cached — it re-executes on resume while completed steps replay")
    void failedStepNotCached() {
        final WorkflowRunner runner = runnerWithCache(new InMemoryStepResultCache());
        final RunId id = RunId.from("audit");
        final WorkflowScript<String> script = ctx -> {
            ctx.agent(sub, "ok");
            return ctx.agent(sub, "bad").text();
        };

        runner.run(script, id);
        assertThat(execCount.get()).isEqualTo(2);
        runner.run(script, id);

        // "ok" replayed; "bad" was never cached -> re-executed once more.
        assertThat(execCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("input-hash validation: changing the goal at the same position invalidates the cache -> re-execute")
    void inputHashInvalidatesStaleEntry() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final WorkflowRunner runner = runnerWithCache(cache);
        final RunId id = RunId.from("audit");

        runner.run(ctx -> ctx.agent(sub, "v1").text(), id);
        assertThat(execCount.get()).isEqualTo(1);
        // Same structural position (a0), different goal -> inputHash mismatch -> miss -> re-execute.
        final String out = runner.run(ctx -> ctx.agent(sub, "v2").text(), id);

        assertThat(out).isEqualTo("ans:v2");
        assertThat(execCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("no-regression: the default NO_OP cache never replays (both runs execute)")
    void noOpDefaultNeverReplays() {
        final WorkflowRunner runner = new DefaultWorkflowRunner(manager, env); // default = NO_OP cache
        runners.add(runner);
        final RunId id = RunId.from("audit");

        runner.run(ctx -> ctx.agent(sub, "a").text(), id);
        runner.run(ctx -> ctx.agent(sub, "a").text(), id);

        assertThat(execCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("A replay re-hydrates the token budget, so the ceiling is still enforced after resume")
    void replayRehydratesTokenBudget() {
        final StepResultCache cache = new InMemoryStepResultCache();
        final RunId id = RunId.from("audit");
        final WorkflowScript<String> script = ctx -> {
            ctx.agent(sub, "a");
            return ctx.agent(sub, "b").text();
        };
        // Run 1: generous ceiling — both 10-token steps execute and are cached.
        runnerWith(cache, WorkflowBudget.of(10, 1000)).run(script, id);
        assertThat(execCount.get()).isEqualTo(2);

        // Run 2: a 10-token ceiling. Step a replays from the cache (re-hydrating its 10 tokens) without executing;
        // step b must then be refused by the token backstop — the refusal can ONLY come from re-hydrated counters,
        // because nothing was re-executed in this run.
        final WorkflowRunner strict = runnerWith(cache, WorkflowBudget.of(10, 10));
        assertThatThrownBy(() -> strict.run(script, id)).isInstanceOf(WorkflowBudgetExceededException.class);
        assertThat(execCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the shared DEFAULT_RUN_ID never touches the step cache (no cross-script replay pollution)")
    void defaultRunIdBypassesCache() {
        final WorkflowRunner runner = runnerWithCache(new InMemoryStepResultCache());

        runner.run(ctx -> ctx.agent(sub, "a").text());
        runner.run(ctx -> ctx.agent(sub, "a").text());

        assertThat(execCount.get()).isEqualTo(2); // identical no-arg runs re-execute — nothing was cached or replayed
    }

    private WorkflowRunner runnerWithCache(StepResultCache cache) {
        return runnerWith(cache, WorkflowBudget.defaults());
    }

    private WorkflowRunner runnerWith(StepResultCache cache, WorkflowBudget budget) {
        final WorkflowRunner runner = new DefaultWorkflowRunner(manager, env, WorkflowConcurrencyConfig.enabled(4),
                WorkflowEventSink.NO_OP, budget, cache);
        runners.add(runner);
        return runner;
    }

    private static Subagent subagent(String name) {
        return Subagent.builder().name(name).systemPrompt("(inline)").build();
    }

    private static SubagentExecutionResult success(String answer) {
        final Instant now = Instant.now();
        // Every step reports 10 total tokens so the budget-rehydration test can pin an exact ceiling.
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.of(5, 5, 10)).timestamps(now, now).build());
    }

    private static SubagentExecutionEnvironment env() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).build();
    }
}
