package at.aimon.core.workflow;

import java.util.Objects;

import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.workflow.impl.DefaultWorkflowRunner;
import at.aimon.core.workflow.impl.InMemoryStepResultCache;

/**
 * Factory for {@link WorkflowRunner} instances.
 *
 * <p>
 * Lets consumers obtain a runner without importing {@code at.aimon.core.workflow.impl} directly (that boundary is
 * enforced by ArchUnit). Consumers build a base {@link SubagentExecutionEnvironment} — which requires agent/hook/llm
 * types they already hold at bootstrap — and hand it here together with a borrowed {@link SubagentExecutionManager}.
 */
public final class WorkflowRunners {

    private WorkflowRunners() {
    }

    /**
     * Creates a runner with default concurrency, no event sink, and the default agent-count backstop.
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @return a new runner
     */
    public static WorkflowRunner create(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv) {
        return new DefaultWorkflowRunner(manager, baseEnv);
    }

    /**
     * Creates a fully configured runner.
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @param concurrency
     *            the fan-out concurrency configuration (must not be null)
     * @param eventSink
     *            the progress sink; null is treated as {@link WorkflowEventSink#NO_OP}
     * @param budget
     *            the run-scoped agent-count/token backstops (must not be null)
     * @return a new runner
     */
    public static WorkflowRunner create(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            WorkflowConcurrencyConfig concurrency, WorkflowEventSink eventSink, WorkflowBudget budget) {
        return new DefaultWorkflowRunner(manager, baseEnv, concurrency, eventSink, budget);
    }

    /**
     * Creates a fully configured runner with a resume step cache.
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @param concurrency
     *            the fan-out concurrency configuration (must not be null)
     * @param eventSink
     *            the progress sink; null is treated as {@link WorkflowEventSink#NO_OP}
     * @param budget
     *            the run-scoped agent-count/token backstops (must not be null)
     * @param stepResultCache
     *            the resume step cache (e.g. an {@code InMemoryStepResultCache}); null is treated as
     *            {@link StepResultCache#NO_OP} (no resume)
     * @return a new runner
     */
    public static WorkflowRunner create(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            WorkflowConcurrencyConfig concurrency, WorkflowEventSink eventSink, WorkflowBudget budget,
            StepResultCache stepResultCache) {
        return new DefaultWorkflowRunner(manager, baseEnv, concurrency, eventSink, budget, stepResultCache);
    }

    /**
     * Creates a runner from a neutral {@link WorkflowRunnerOptions} bundle — the full configuration surface
     * (concurrency, event sink, budget, resume cache, run store, background pool) without importing the impl builder
     * Any option left null takes the runner's default. This is the assembly seam a CLI / web bootstrap uses
     * to enable resume and background hosting with in-memory defaults and a one-line swap to shared/persistent
     * backends.
     *
     * @param manager
     *            the borrowed subagent execution manager (must not be null)
     * @param baseEnv
     *            the borrowed base execution environment (must not be null)
     * @param options
     *            the runner options (must not be null; use {@link WorkflowRunnerOptions#defaults()} for all
     *            defaults)
     * @return a new runner
     */
    public static WorkflowRunner create(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            WorkflowRunnerOptions options) {
        Objects.requireNonNull(options, "options cannot be null");
        return DefaultWorkflowRunner.builder(manager, baseEnv).concurrency(options.concurrency())
                .eventSink(options.eventSink()).budget(options.budget()).stepResultCache(options.stepResultCache())
                .runStore(options.runStore()).backgroundConfig(options.backgroundConfig())
                .worktreeFactory(options.worktreeFactory()).build();
    }

    /**
     * @return a new bounded in-memory {@link StepResultCache}, for a consumer that wants same-node resume without
     *         importing the impl package (pass it to {@link WorkflowRunnerOptions.Builder#stepResultCache}). A
     *         scale-out deployment supplies its own shared/persistent {@code StepResultCache} instead.
     */
    public static StepResultCache inMemoryStepResultCache() {
        return new InMemoryStepResultCache();
    }
}
