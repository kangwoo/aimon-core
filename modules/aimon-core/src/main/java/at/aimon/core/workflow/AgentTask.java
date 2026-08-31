package at.aimon.core.workflow;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.subagent.Subagent;

/**
 * Describes a single {@code agent()} call: an inline (code-defined) {@link Subagent} plus the goal to run it against.
 *
 * <p>
 * Immutable value object. A task carries only inline subagents (named registry lookup is deferred — see the design's
 * non-goals). The per-task model is expressed via the subagent's own frontmatter; the run's base environment must not
 * set a {@code modelOverride}, which would outrank it.
 */
public final class AgentTask {

    private final Subagent subagent;
    private final String goal;
    private final String label;
    private final String phase;
    private final Map<String, Object> resultSchema;
    private final boolean isolate;
    private final boolean nonCacheable;

    private AgentTask(Builder builder) {
        this.subagent = Objects.requireNonNull(builder.subagent, "subagent cannot be null");
        this.goal = Objects.requireNonNull(builder.goal, "goal cannot be null");
        this.label = builder.label;
        this.phase = builder.phase;
        this.resultSchema = builder.resultSchema != null ? Map.copyOf(builder.resultSchema) : null;
        this.isolate = builder.isolate;
        // Isolation implies non-cacheability: an isolated leaf's file writes land in a worktree subtree that the
        // transcript-free StepOutcome cannot replay, so a cache hit would silently drop them. Deriving it here makes
        // isNonCacheable() the single authoritative cache-bypass predicate every call site reads (design §6.3).
        this.nonCacheable = builder.nonCacheable || builder.isolate;
    }

    /**
     * Creates a task for the given inline subagent and goal.
     *
     * @param subagent
     *            the inline subagent to run (must not be null)
     * @param goal
     *            the goal (must not be null)
     * @return a new task
     */
    public static AgentTask of(Subagent subagent, String goal) {
        return builder().subagent(subagent).goal(goal).build();
    }

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the inline subagent to run (never null)
     */
    public Subagent getSubagent() {
        return subagent;
    }

    /**
     * @return the goal (never null)
     */
    public String getGoal() {
        return goal;
    }

    /**
     * @return the display/diagnostic label; the explicit label if set, otherwise the subagent's name (never null)
     */
    public String getLabel() {
        return label != null ? label : subagent.getName();
    }

    /**
     * @return the progress phase this task belongs to, if any
     */
    public Optional<String> getPhase() {
        return Optional.ofNullable(phase);
    }

    /**
     * @return the JSON Schema (as a nested {@code Map}) the subagent's output should conform to, if structured output
     *         is requested. When present, the runner augments the goal with a JSON-emit instruction and
     *         parses/validates
     *         the final answer into {@link AgentStepResult#structured()}.
     */
    public Optional<Map<String, Object>> getResultSchema() {
        return Optional.ofNullable(resultSchema);
    }

    /**
     * @return {@code true} if this step runs against an isolated per-branch filesystem view (worktree isolation,
     *         design §6.3). Requires a {@code WorktreeEnvironmentFactory} to be wired, else the step is run-fatal.
     *         Implies {@link #isNonCacheable()}.
     */
    public boolean isIsolate() {
        return isolate;
    }

    /**
     * @return {@code true} if this step must never be memoized or replayed from the {@code StepResultCache}. The single
     *         authoritative cache-bypass predicate (design §6.3): {@code true} when {@link #isIsolate()} is set,
     *         or
     *         when the caller explicitly marks a base-VFS-mutating step (e.g. a merge/promotion step) non-cacheable so
     *         a
     *         transcript-free replay cannot silently drop its file writes.
     */
    public boolean isNonCacheable() {
        return nonCacheable;
    }

    @Override
    public String toString() {
        return "AgentTask{label=" + getLabel() + ", goal='" + goal + "'}";
    }

    /** Builder for {@link AgentTask}. */
    public static final class Builder {
        private Subagent subagent;
        private String goal;
        private String label;
        private String phase;
        private Map<String, Object> resultSchema;
        private boolean isolate;
        private boolean nonCacheable;

        private Builder() {
        }

        /**
         * @param subagent
         *            the inline subagent to run
         * @return this builder
         */
        public Builder subagent(Subagent subagent) {
            this.subagent = subagent;
            return this;
        }

        /**
         * @param goal
         *            the goal
         * @return this builder
         */
        public Builder goal(String goal) {
            this.goal = goal;
            return this;
        }

        /**
         * @param label
         *            the display/diagnostic label (nullable; defaults to the subagent name)
         * @return this builder
         */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /**
         * @param phase
         *            the progress phase this task belongs to (nullable)
         * @return this builder
         */
        public Builder phase(String phase) {
            this.phase = phase;
            return this;
        }

        /**
         * @param resultSchema
         *            the JSON Schema (nested {@code Map}) the subagent output should conform to (nullable; when set,
         *            the runner requests and validates structured output — its values must be non-null so it can be
         *            defensively copied)
         * @return this builder
         */
        public Builder resultSchema(Map<String, Object> resultSchema) {
            this.resultSchema = resultSchema;
            return this;
        }

        /**
         * @param isolate
         *            whether the step runs against an isolated per-branch filesystem view (design §6.3). Implies
         *            {@code nonCacheable}. Requires a {@code WorktreeEnvironmentFactory}; unset factory ⇒ run-fatal.
         * @return this builder
         */
        public Builder isolate(boolean isolate) {
            this.isolate = isolate;
            return this;
        }

        /**
         * @param nonCacheable
         *            whether the step must never be memoized/replayed. Set this for any step that mutates the base VFS
         *            (e.g. a merge/promotion step) so a transcript-free replay cannot silently drop its writes (§6.3).
         *            {@code isolate(true)} sets this implicitly.
         * @return this builder
         */
        public Builder nonCacheable(boolean nonCacheable) {
            this.nonCacheable = nonCacheable;
            return this;
        }

        /**
         * @return the immutable task
         */
        public AgentTask build() {
            return new AgentTask(this);
        }
    }
}
