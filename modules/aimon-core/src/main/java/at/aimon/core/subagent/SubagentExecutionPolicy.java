package at.aimon.core.subagent;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.budget.ExecutionBudget;

/**
 * Trusted limits and context settings for one subagent run, supplied by code — never by the model, never by a
 * subagent definition file.
 *
 * <p>
 * A policy reaches a fork one way: the spawning code puts it on the
 * {@link SubagentLaunchContext.Builder#executionPolicy(SubagentExecutionPolicy) launch context}. {@code TaskTool} does
 * so from the {@link SubagentExecutionPolicyResolver} it was configured with, keyed by the subagent name the model
 * chose; the model can pick which subagent runs, but nothing in the {@code Task} input reaches the policy. The manager
 * hands the same instance to the executor on the request ({@code SubagentExecutionRequest#getExecutionPolicy()}),
 * together with the deadline it anchored at launch.
 *
 * <p>
 * <b>Each limit is optional and independent.</b> Leaving one unset means that dimension is unbounded <em>by this
 * policy</em>; the subagent's own {@code max-iterations} still applies, and whichever of the two is lower stops the
 * loop first. The limits are separate axes on purpose: {@link #getContextTargetTokens()} bounds how big one call's
 * input should be allowed to grow, {@link #getMaxTokens()} how many tokens the whole run may spend.
 *
 * <ul>
 * <li>{@link #getMaxIterations()} / {@link #getMaxTokens()} become the fork's {@link ExecutionBudget}
 * ({@link #toBudget()}), checked before every iteration. Tokens are what the provider reported, so they are known only
 * after a call returns: one call may overshoot the limit, and the next LLM call or tool batch is then not started.
 * <li>{@link #getMaxToolCalls()} counts every call that is actually run, one per call in a parallel batch. A batch
 * that would exceed it runs only as many calls as are left; the rest are answered with an error result and not run,
 * and the fork stops with {@code TOOL_CALL_BUDGET_EXCEEDED} before its next LLM call.
 * <li>{@link #getMaxWallClock()} becomes a deadline measured from <b>launch</b> — a background fork's wait for a
 * worker counts — on a monotonic clock. When it passes, the fork's cancellation is tripped, which aborts an in-flight
 * LLM call and fires the terminators of a running tool, and the fork ends {@code WALL_CLOCK_EXCEEDED}. A foreground
 * fork additionally runs on a thread of its own and is waited for at most {@link #getExitGracePeriod()} past the
 * deadline; one that has not exited by then is reported with its exit unconfirmed rather than waited for forever.
 * <li>{@link #getCompactionTokenThreshold()} and {@link #getContextTargetTokens()} are <b>hints</b>: crossing either
 * makes the next context request {@code budgetForced}, which asks the context engine to compact at its lower warning
 * band. The engine still decides; a hint never compacts a view the engine finds small enough, and never stops the
 * fork.
 * <li>{@link #getCompactionInstructions()} are handed to the summary of every AUTO compaction of the fork, the way
 * {@code /compact <instructions>} hands them to a manual one.
 * <li>{@link #isInheritContextEngine()} runs the fork under its spawner's context engine instead of the executor's
 * own (which, as built by {@code OrcaAgentExecutorFactory}, never compacts). The engine is shared; what it keeps per
 * conversation — its lock and its failure count — is keyed on the fork's own transcript, so forks stay apart.
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class SubagentExecutionPolicy {

    /** How long a foreground fork is waited for past its deadline when the policy does not say. */
    public static final Duration DEFAULT_EXIT_GRACE_PERIOD = Duration.ofSeconds(5);

    /**
     * @param role
     *            the name the policy is known by, for logs and results (must not be blank)
     * @return a new builder
     */
    public static Builder builder(String role) {
        return new Builder(role);
    }

    private final String role;
    private final Integer maxIterations;
    private final Integer maxTokens;
    private final Integer maxToolCalls;
    private final Duration maxWallClock;
    private final Integer compactionTokenThreshold;
    private final Integer contextTargetTokens;
    private final String compactionInstructions;
    private final boolean inheritContextEngine;
    private final Duration exitGracePeriod;

    private SubagentExecutionPolicy(Builder builder) {
        this.role = builder.role;
        this.maxIterations = builder.maxIterations;
        this.maxTokens = builder.maxTokens;
        this.maxToolCalls = builder.maxToolCalls;
        this.maxWallClock = builder.maxWallClock;
        this.compactionTokenThreshold = builder.compactionTokenThreshold;
        this.contextTargetTokens = builder.contextTargetTokens;
        this.compactionInstructions = builder.compactionInstructions;
        this.inheritContextEngine = builder.inheritContextEngine;
        this.exitGracePeriod = builder.exitGracePeriod;
    }

    /** @return the name the policy is known by */
    public String getRole() {
        return role;
    }

    /** @return the iteration cap, if any */
    public Optional<Integer> getMaxIterations() {
        return Optional.ofNullable(maxIterations);
    }

    /** @return the cumulative token cap (prompt + completion, as the provider reports it), if any */
    public Optional<Integer> getMaxTokens() {
        return Optional.ofNullable(maxTokens);
    }

    /** @return the cap on tool calls actually run, if any */
    public Optional<Integer> getMaxToolCalls() {
        return Optional.ofNullable(maxToolCalls);
    }

    /** @return how long after launch the fork must be over by, if bounded */
    public Optional<Duration> getMaxWallClock() {
        return Optional.ofNullable(maxWallClock);
    }

    /** @return the cumulative-token threshold past which compaction is hinted, if any */
    public Optional<Integer> getCompactionTokenThreshold() {
        return Optional.ofNullable(compactionTokenThreshold);
    }

    /** @return the input size of one call past which compaction is hinted, if any */
    public Optional<Integer> getContextTargetTokens() {
        return Optional.ofNullable(contextTargetTokens);
    }

    /** @return the instructions every AUTO compaction summary of the fork is given, if any */
    public Optional<String> getCompactionInstructions() {
        return Optional.ofNullable(compactionInstructions);
    }

    /** @return whether the fork runs under its spawner's context engine */
    public boolean isInheritContextEngine() {
        return inheritContextEngine;
    }

    /**
     * @return how long a foreground fork is waited for past its deadline or its cancellation before it is reported
     *         unexited
     */
    public Duration getExitGracePeriod() {
        return exitGracePeriod;
    }

    /**
     * @return the capabilities this policy uses — what an executor must enforce for the policy to mean what it says
     */
    public Set<SubagentPolicyCapability> getCapabilities() {
        final EnumSet<SubagentPolicyCapability> used = EnumSet.noneOf(SubagentPolicyCapability.class);
        if (maxIterations != null) {
            used.add(SubagentPolicyCapability.MAX_ITERATIONS);
        }
        if (maxTokens != null) {
            used.add(SubagentPolicyCapability.MAX_TOKENS);
        }
        if (maxToolCalls != null) {
            used.add(SubagentPolicyCapability.MAX_TOOL_CALLS);
        }
        if (maxWallClock != null) {
            used.add(SubagentPolicyCapability.MAX_WALL_CLOCK);
        }
        if (compactionTokenThreshold != null) {
            used.add(SubagentPolicyCapability.COMPACTION_TOKEN_THRESHOLD);
        }
        if (contextTargetTokens != null) {
            used.add(SubagentPolicyCapability.CONTEXT_TARGET_TOKENS);
        }
        if (compactionInstructions != null) {
            used.add(SubagentPolicyCapability.COMPACTION_INSTRUCTIONS);
        }
        if (inheritContextEngine) {
            used.add(SubagentPolicyCapability.INHERIT_CONTEXT_ENGINE);
        }
        return Collections.unmodifiableSet(used);
    }

    /**
     * Returns what of {@link #getCapabilities()} is missing from {@code supported}.
     *
     * @param supported
     *            the capabilities an executor enforces (must not be null)
     * @return the unsupported ones, empty when the policy can be enforced as written (never null)
     */
    public Set<SubagentPolicyCapability> unsupportedBy(Set<SubagentPolicyCapability> supported) {
        Objects.requireNonNull(supported, "supported cannot be null");
        final EnumSet<SubagentPolicyCapability> missing = EnumSet.noneOf(SubagentPolicyCapability.class);
        missing.addAll(getCapabilities());
        missing.removeAll(supported);
        return Collections.unmodifiableSet(missing);
    }

    /**
     * The policy's iteration and token axes as an {@link ExecutionBudget}: what the fork's {@code BudgetTracker} is
     * built from. The wall clock is not in it — the deadline is anchored at launch, not when the tracker is built —
     * and neither is the tool-call cap, which a budget has no axis for.
     *
     * @return the budget (unlimited when the policy sets neither axis nor the compaction threshold)
     */
    public ExecutionBudget toBudget() {
        final ExecutionBudget.Builder budget = ExecutionBudget.builder();
        if (maxIterations != null) {
            budget.maxIterations(maxIterations);
        }
        if (maxTokens != null) {
            budget.maxTokens(maxTokens);
        }
        if (compactionTokenThreshold != null) {
            budget.compactionTokenThreshold(compactionTokenThreshold);
        }
        return budget.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubagentExecutionPolicy that = (SubagentExecutionPolicy) o;
        return inheritContextEngine == that.inheritContextEngine && role.equals(that.role)
                && Objects.equals(maxIterations, that.maxIterations) && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(maxToolCalls, that.maxToolCalls) && Objects.equals(maxWallClock, that.maxWallClock)
                && Objects.equals(compactionTokenThreshold, that.compactionTokenThreshold)
                && Objects.equals(contextTargetTokens, that.contextTargetTokens)
                && Objects.equals(compactionInstructions, that.compactionInstructions)
                && exitGracePeriod.equals(that.exitGracePeriod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, maxIterations, maxTokens, maxToolCalls, maxWallClock, compactionTokenThreshold,
                contextTargetTokens, compactionInstructions, inheritContextEngine, exitGracePeriod);
    }

    @Override
    public String toString() {
        return "SubagentExecutionPolicy{role='" + role + "', maxIterations=" + maxIterations + ", maxTokens="
                + maxTokens + ", maxToolCalls=" + maxToolCalls + ", maxWallClock=" + maxWallClock
                + ", compactionTokenThreshold=" + compactionTokenThreshold + ", contextTargetTokens="
                + contextTargetTokens + ", compactionInstructions=" + (compactionInstructions != null)
                + ", inheritContextEngine=" + inheritContextEngine + ", exitGracePeriod=" + exitGracePeriod + '}';
    }

    /**
     * Builder for {@link SubagentExecutionPolicy}. Every limit is optional; an unset one is not bounded by the policy.
     */
    public static final class Builder {
        private final String role;
        private Integer maxIterations;
        private Integer maxTokens;
        private Integer maxToolCalls;
        private Duration maxWallClock;
        private Integer compactionTokenThreshold;
        private Integer contextTargetTokens;
        private String compactionInstructions;
        private boolean inheritContextEngine;
        private Duration exitGracePeriod = DEFAULT_EXIT_GRACE_PERIOD;

        private Builder(String role) {
            Objects.requireNonNull(role, "role cannot be null");
            if (role.isBlank()) {
                throw new IllegalArgumentException("role must not be blank");
            }
            this.role = role;
        }

        /**
         * @param maxIterations
         *            the iteration cap (must be {@code >= 1})
         * @return this builder
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = positive("maxIterations", maxIterations);
            return this;
        }

        /**
         * @param maxTokens
         *            the cumulative token cap (must be {@code >= 1})
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = positive("maxTokens", maxTokens);
            return this;
        }

        /**
         * @param maxToolCalls
         *            the cap on tool calls run (must be {@code >= 1})
         * @return this builder
         */
        public Builder maxToolCalls(int maxToolCalls) {
            this.maxToolCalls = positive("maxToolCalls", maxToolCalls);
            return this;
        }

        /**
         * @param maxWallClock
         *            how long after launch the fork must be over by (must be positive)
         * @return this builder
         */
        public Builder maxWallClock(Duration maxWallClock) {
            this.maxWallClock = positive("maxWallClock", maxWallClock);
            return this;
        }

        /**
         * @param compactionTokenThreshold
         *            the cumulative-token compaction hint (must be {@code >= 1}, and below {@link #maxTokens(int)} when
         *            both are set — checked in {@link #build()})
         * @return this builder
         */
        public Builder compactionTokenThreshold(int compactionTokenThreshold) {
            this.compactionTokenThreshold = positive("compactionTokenThreshold", compactionTokenThreshold);
            return this;
        }

        /**
         * @param contextTargetTokens
         *            the per-call input size past which compaction is hinted (must be {@code >= 1})
         * @return this builder
         */
        public Builder contextTargetTokens(int contextTargetTokens) {
            this.contextTargetTokens = positive("contextTargetTokens", contextTargetTokens);
            return this;
        }

        /**
         * @param compactionInstructions
         *            what every AUTO compaction summary of the fork is told to keep (must not be blank)
         * @return this builder
         */
        public Builder compactionInstructions(String compactionInstructions) {
            Objects.requireNonNull(compactionInstructions, "compactionInstructions cannot be null");
            if (compactionInstructions.isBlank()) {
                throw new IllegalArgumentException("compactionInstructions must not be blank");
            }
            this.compactionInstructions = compactionInstructions;
            return this;
        }

        /**
         * @param inheritContextEngine
         *            whether the fork runs under its spawner's context engine
         * @return this builder
         */
        public Builder inheritContextEngine(boolean inheritContextEngine) {
            this.inheritContextEngine = inheritContextEngine;
            return this;
        }

        /**
         * @param exitGracePeriod
         *            how long a foreground fork is waited for past its deadline or cancellation (must not be negative;
         *            defaults to {@link #DEFAULT_EXIT_GRACE_PERIOD})
         * @return this builder
         */
        public Builder exitGracePeriod(Duration exitGracePeriod) {
            Objects.requireNonNull(exitGracePeriod, "exitGracePeriod cannot be null");
            if (exitGracePeriod.isNegative()) {
                throw new IllegalArgumentException("exitGracePeriod must not be negative, got: " + exitGracePeriod);
            }
            this.exitGracePeriod = exitGracePeriod;
            return this;
        }

        /**
         * @return the policy
         * @throws IllegalArgumentException
         *             if the compaction threshold is not below the token cap — the cap would stop the fork first and
         *             the hint could never be acted on
         */
        public SubagentExecutionPolicy build() {
            if (compactionTokenThreshold != null && maxTokens != null && compactionTokenThreshold >= maxTokens) {
                throw new IllegalArgumentException("compactionTokenThreshold (" + compactionTokenThreshold
                        + ") must be below maxTokens (" + maxTokens + ")");
            }
            return new SubagentExecutionPolicy(this);
        }

        private static int positive(String name, int value) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be >= 1, got: " + value);
            }
            return value;
        }

        private static Duration positive(String name, Duration value) {
            Objects.requireNonNull(value, name + " cannot be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive, got: " + value);
            }
            return value;
        }
    }
}
