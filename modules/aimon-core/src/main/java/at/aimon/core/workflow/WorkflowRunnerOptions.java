package at.aimon.core.workflow;

/**
 * Neutral, fully-optional configuration bundle for building an {@link WorkflowRunner} via
 * {@code WorkflowRunners.create(manager, baseEnv, options)} (design §5.1).
 *
 * <p>
 * This is the assembly seam that lets a consumer (CLI / web bootstrap) configure resume, background hosting, and the
 * run store <b>without importing {@code at.aimon.core.workflow.impl}</b> (the {@code DefaultWorkflowRunner}
 * builder is impl-internal and blocked by ArchUnit). Every field is optional: a {@code null} means the runner applies
 * its default (default concurrency, {@link WorkflowEventSink#NO_OP}, {@link WorkflowBudget#defaults()},
 * {@link StepResultCache#NO_OP} = no resume, an in-memory run store, and the default background pool). Per the
 * multi-instance rule, swapping in a shared {@link RunStore} or a persistent {@link StepResultCache} (obtainable via
 * {@link WorkflowRunners} factory helpers) is a one-line change here.
 *
 * <p>
 * Immutable value object; build via {@link #builder()}.
 */
public final class WorkflowRunnerOptions {

    /**
     * @return options with every field defaulted (equivalent to the two-arg
     *         {@code WorkflowRunners.create(manager, baseEnv)})
     */
    public static WorkflowRunnerOptions defaults() {
        return builder().build();
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final WorkflowConcurrencyConfig concurrency;
    private final WorkflowEventSink eventSink;
    private final WorkflowBudget budget;
    private final StepResultCache stepResultCache;
    private final RunStore runStore;
    private final WorkflowBackgroundConfig backgroundConfig;
    private final WorktreeEnvironmentFactory worktreeFactory;

    private WorkflowRunnerOptions(Builder builder) {
        this.concurrency = builder.concurrency;
        this.eventSink = builder.eventSink;
        this.budget = builder.budget;
        this.stepResultCache = builder.stepResultCache;
        this.runStore = builder.runStore;
        this.backgroundConfig = builder.backgroundConfig;
        this.worktreeFactory = builder.worktreeFactory;
    }

    /** @return the fan-out concurrency config, or null for the runner default */
    public WorkflowConcurrencyConfig concurrency() {
        return concurrency;
    }

    /** @return the progress sink, or null for {@link WorkflowEventSink#NO_OP} */
    public WorkflowEventSink eventSink() {
        return eventSink;
    }

    /** @return the run-scoped backstops, or null for {@link WorkflowBudget#defaults()} */
    public WorkflowBudget budget() {
        return budget;
    }

    /** @return the resume step cache, or null for {@link StepResultCache#NO_OP} (no resume) */
    public StepResultCache stepResultCache() {
        return stepResultCache;
    }

    /** @return the run store, or null for an in-memory default */
    public RunStore runStore() {
        return runStore;
    }

    /** @return the run-hosting pool config, or null for the default */
    public WorkflowBackgroundConfig backgroundConfig() {
        return backgroundConfig;
    }

    /** @return the worktree environment factory (design §6.3), or null when isolation is unavailable */
    public WorktreeEnvironmentFactory worktreeFactory() {
        return worktreeFactory;
    }

    /** Builder for {@link WorkflowRunnerOptions}. */
    public static final class Builder {
        private WorkflowConcurrencyConfig concurrency;
        private WorkflowEventSink eventSink;
        private WorkflowBudget budget;
        private StepResultCache stepResultCache;
        private RunStore runStore;
        private WorkflowBackgroundConfig backgroundConfig;
        private WorktreeEnvironmentFactory worktreeFactory;

        private Builder() {
        }

        /** Sets the fan-out concurrency config (null = default). */
        public Builder concurrency(WorkflowConcurrencyConfig concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        /** Sets the progress sink (null = {@link WorkflowEventSink#NO_OP}). */
        public Builder eventSink(WorkflowEventSink eventSink) {
            this.eventSink = eventSink;
            return this;
        }

        /** Sets the run-scoped backstops (null = {@link WorkflowBudget#defaults()}). */
        public Builder budget(WorkflowBudget budget) {
            this.budget = budget;
            return this;
        }

        /** Sets the resume step cache (null = {@link StepResultCache#NO_OP}). */
        public Builder stepResultCache(StepResultCache stepResultCache) {
            this.stepResultCache = stepResultCache;
            return this;
        }

        /** Sets the run store (null = in-memory default). */
        public Builder runStore(RunStore runStore) {
            this.runStore = runStore;
            return this;
        }

        /** Sets the run-hosting pool config (null = default). */
        public Builder backgroundConfig(WorkflowBackgroundConfig backgroundConfig) {
            this.backgroundConfig = backgroundConfig;
            return this;
        }

        /** Sets the worktree environment factory for isolated steps (design §6.3; null = isolation unavailable). */
        public Builder worktreeFactory(WorktreeEnvironmentFactory worktreeFactory) {
            this.worktreeFactory = worktreeFactory;
            return this;
        }

        /**
         * @return the immutable options
         */
        public WorkflowRunnerOptions build() {
            return new WorkflowRunnerOptions(this);
        }
    }
}
