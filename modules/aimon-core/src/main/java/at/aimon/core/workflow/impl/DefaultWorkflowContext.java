package at.aimon.core.workflow.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.StepKey;
import at.aimon.core.workflow.StepOutcome;
import at.aimon.core.workflow.StepResultCache;
import at.aimon.core.workflow.WorkflowBudget;
import at.aimon.core.workflow.WorkflowContext;
import at.aimon.core.workflow.WorkflowEventSink;
import at.aimon.core.workflow.WorktreeEnvironmentFactory;
import at.aimon.core.workflow.exception.WorkflowBudgetExceededException;
import at.aimon.core.workflow.exception.WorkflowException;

/**
 * Run-scoped {@link WorkflowContext} implementation. One instance per {@code run()}, so its mutable run-scoped
 * state (agent-count/token backstops, structural path frames — each frame carries its own per-call-stack nesting
 * level) is isolated to a single run.
 *
 * <p>
 * {@code agent()} runs a single inline subagent via the primitive
 * {@link SubagentExecutionManager#execute(SubagentExecutionEnvironment, Subagent, String)} on the shared, borrowed base
 * environment. {@code parallel()} fans thunks out to the run's {@link BoundedFanoutDispatcher}; {@code pipeline()} is
 * expressed as fan-out over per-item stage chains.
 *
 * <p>
 * <b>Resume (design §5.3).</b> Every {@code agent()} step gets a deterministic {@link StepKey} = {@code runId}
 * + owning {@code agentRuntimeId} + a <b>structural step-path</b>. The path is assigned by the level's owning thread in
 * program order: each level keeps a shared counter, {@code agent}&rarr;{@code a<n>},
 * {@code parallel}&rarr;{@code p<n>},
 * {@code pipeline}&rarr;{@code q<n>}, and a fan-out child additionally carries its list index — so the key is stable
 * across re-executions regardless of scheduling, and sibling constructs / identical-input branches never collide. When
 * a {@link StepResultCache} is configured (not {@link StepResultCache#NO_OP}), a completed step is memoized and a
 * matching cached step is replayed instead of re-executed; the run's token budget is still re-hydrated on a replay so
 * the backstops cannot silently reset. Only {@code COMPLETED} steps are cached, so a replay is never a failure.
 */
public final class DefaultWorkflowContext implements WorkflowContext {

    /** Deterministic map-key serializer so an equal schema always hashes identically (cross-JVM stable). */
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final SubagentExecutionManager manager;
    private final SubagentExecutionEnvironment baseEnv;
    private final BoundedFanoutDispatcher fanout;
    private final BoundedFanoutDispatcher sequentialFanout;
    private final WorkflowEventSink eventSink;
    private final WorkflowBudget budget;
    private final RunId runId;
    private final AgentRuntimeId agentRuntimeId;
    private final StepResultCache stepResultCache;

    /** Global LLM-concurrency ceiling shared across runs; wraps only the terminal leaf (design §6.2). */
    private final LeafConcurrencyLimiter leafSlots;

    /** Caller-injected per-branch env derivation for worktree isolation; {@code null} disables isolation (§6.3). */
    private final WorktreeEnvironmentFactory worktreeFactory;

    /** Max fan-out nesting depth; a deeper {@code parallel}/{@code pipeline} degrades to sequential (§6.2). */
    private final int maxNestingDepth;

    /** Run-scoped count of {@code agent()} invocations; the agent-count backstop. */
    private final AtomicInteger agentCount = new AtomicInteger();

    /** Run-scoped aggregate total-token spend across all {@code agent()} calls; the opt-in token backstop. */
    private final AtomicLong tokensSpent = new AtomicLong();

    /** Run-scoped aggregate USD cost spend (in micros) across all {@code agent()} calls; the opt-in cost backstop. */
    private final AtomicLong costSpent = new AtomicLong();

    /**
     * The current structural-path frame, per thread. Each path level's body runs sequentially on a single thread, so a
     * plain per-thread frame with plain-int/String counters (ordinal, running digest) is sufficient;
     * {@code parallel}/{@code pipeline} set a fresh child frame on the worker before running each thunk and restore it
     * after (a stack discipline that survives both the beyond-depth sequential degrade and true nested parallelism).
     * Each frame carries its {@code nestingLevel} so routing is per-call-stack, not a run-shared counter. This
     * ThreadLocal is a per-run instance field, so it cannot leak across runs.
     */
    private final ThreadLocal<PathFrame> frame = new ThreadLocal<>();

    DefaultWorkflowContext(SubagentExecutionManager manager, SubagentExecutionEnvironment baseEnv,
            BoundedFanoutDispatcher fanout, WorkflowEventSink eventSink, WorkflowBudget budget, ResumeBinding resume,
            ContextExecutionOptions execution) {
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");
        this.baseEnv = Objects.requireNonNull(baseEnv, "baseEnv cannot be null");
        this.fanout = Objects.requireNonNull(fanout, "fanout cannot be null");
        this.eventSink = eventSink != null ? eventSink : WorkflowEventSink.NO_OP;
        this.budget = Objects.requireNonNull(budget, "budget cannot be null");
        Objects.requireNonNull(resume, "resume cannot be null");
        this.runId = resume.runId();
        this.agentRuntimeId = resume.agentRuntimeId();
        this.stepResultCache = resume.cache();
        Objects.requireNonNull(execution, "execution cannot be null");
        this.leafSlots = Objects.requireNonNull(execution.leafSlots(), "leafSlots cannot be null");
        this.worktreeFactory = execution.worktreeFactory(); // nullable — null means isolation unavailable (C30)
        if (execution.maxNestingDepth() < 1) {
            throw new IllegalArgumentException("maxNestingDepth must be >= 1, got: " + execution.maxNestingDepth());
        }
        this.maxNestingDepth = execution.maxNestingDepth();
        // A never-pooled dispatcher for a beyond-depth nested fan-out: reuses the same input-order + isolation
        // semantics without ever touching the shared pool.
        this.sequentialFanout = BoundedFanoutDispatcher.sequential();
    }

    @Override
    public AgentStepResult agent(AgentTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        checkBackstops();
        // Consume this construct's ordinal at the current level so sibling constructs stay distinct across re-runs.
        final PathFrame f = currentFrame();
        final String segment = f.next("a");
        final String path = f.childPath(segment);
        // Resolve the (possibly worktree-scoped) env FIRST, independent of cache state — so isolation works even under
        // NO_OP (the dominant default). Only fingerprint/load/save is resume-gated (design §6.3).
        final SubagentExecutionEnvironment env = resolveEnv(task, path);
        if (stepResultCache == StepResultCache.NO_OP) {
            return executeStep(task, env); // fast path skips only cache/fingerprint work, not env resolution
        }
        // Cache-active: always fold this leaf's left-context so sibling fingerprints stay correct, even when the step
        // itself is non-cacheable (§6.5).
        final String inputHash = inputHash(task);
        final String fp = f.foldLeaf(segment, inputHash);
        if (task.isNonCacheable()) {
            // Bypass BOTH load and save: a transcript-free replay cannot re-materialize this step's file writes.
            return executeStep(task, env);
        }
        final StepKey key = StepKey.of(runId, agentRuntimeId, path);
        final Optional<StepOutcome> cached = stepResultCache.load(key);
        if (cached.isPresent() && cached.get().inputHash().equals(inputHash)
                && cached.get().structureFingerprint().equals(fp)) {
            // Cache hit: position + input + structure all match. Replay the memoized outcome, re-hydrating the budget.
            return replay(task, cached.get());
        }
        final AgentStepResult result = executeStep(task, env);
        if (result.isComplete()) {
            // COMPLETED-only: never cache a failure/interruption, so a resume re-runs it.
            stepResultCache.save(key, toOutcome(result, inputHash, fp));
        }
        return result;
    }

    /**
     * Resolves the environment a step runs against. Non-isolated steps use the borrowed base env; an isolated step
     * derives a per-branch env via the injected {@link WorktreeEnvironmentFactory}, or fails run-fatal if none is wired
     * Independent of cache state so isolation holds under {@code NO_OP}.
     */
    private SubagentExecutionEnvironment resolveEnv(AgentTask task, String path) {
        if (!task.isIsolate()) {
            return baseEnv;
        }
        if (worktreeFactory == null) {
            throw new WorkflowException("agent task requested isolation (isolate=true) but no "
                    + "WorktreeEnvironmentFactory is configured — refusing to run unscoped (C30)");
        }
        return worktreeFactory.derive(baseEnv, sanitizeBranchKey(path));
    }

    /** Turns a structural step-path into a single filesystem-safe branch subtree name (deterministic). */
    private static String sanitizeBranchKey(String path) {
        return path.replaceAll("[^A-Za-z0-9]", "_");
    }

    private void checkBackstops() {
        final int n = agentCount.incrementAndGet();
        if (n > budget.getMaxAgents()) {
            // Run-fatal: a runaway script, not a recoverable per-task failure. Checked BEFORE manager.execute so the
            // over-limit call spends no tokens. Thrown even from a worker thread; BoundedFanoutDispatcher re-throws
            // WorkflowException out of the fan-out so the run actually aborts (design §4.3).
            throw new WorkflowBudgetExceededException(
                    "agent-count backstop exceeded for this run (max " + budget.getMaxAgents() + ")");
        }
        // Opt-in aggregate token ceiling (post-hoc): once the run's total token spend reaches the ceiling, refuse the
        // next agent(). The crosser already completed and was counted; under concurrent fan-out up to the fan-out width
        // may cross before this is observed, but the accumulator is exact so the next agent() past the ceiling always
        // aborts (design §4.3). A cache-hit replay re-hydrates tokensSpent below, so it is enforced identically.
        if (budget.hasTokenLimit() && tokensSpent.get() >= budget.getMaxTokens()) {
            throw new WorkflowBudgetExceededException("token backstop exceeded for this run (max "
                    + budget.getMaxTokens() + ", spent " + tokensSpent.get() + ")");
        }
        // Opt-in aggregate USD cost ceiling (post-hoc, same semantics as the token ceiling): refuse the next agent()
        // once the run's accumulated cost reaches the ceiling. The crosser already completed and was counted.
        if (budget.hasCostLimit() && costSpent.get() >= budget.getMaxCostMicros()) {
            throw new WorkflowBudgetExceededException("cost backstop exceeded for this run (max micros "
                    + budget.getMaxCostMicros() + ", spent " + costSpent.get() + ")");
        }
    }

    private AgentStepResult executeStep(AgentTask task, SubagentExecutionEnvironment env) {
        // Structured output (prompt-and-parse): when a result schema is set, augment the goal with a JSON-emit
        // instruction and, after a successful run, parse+validate the final answer into AgentStepResult.structured().
        final Map<String, Object> schema = task.getResultSchema().orElse(null);
        final String goal = schema == null
                ? task.getGoal()
                : StructuredOutputSupport.augmentGoal(task.getGoal(), schema);
        eventSink.onAgentStarted(task);
        // Wrap ONLY the terminal leaf in the global leaf permit: the permit gates the LLM call, never a
        // fan-out join or the dispatcher thunk. Accounting stays outside the permit scope.
        final SubagentExecutionResult raw = leafSlots.around(env.getCancellationSignal(),
                () -> manager.execute(env, task.getSubagent(), goal));
        tokensSpent.addAndGet(raw.getMetadata().getTokenUsage().getTotalTokens());
        costSpent.addAndGet(toMicros(raw.getCost()));
        final Map<String, Object> structured = schema != null && raw.isSuccess()
                ? StructuredOutputSupport.parse(raw.getFinalAnswer(), schema).orElse(null)
                : null;
        final AgentStepResult result = AgentStepResult.of(task, raw, structured);
        eventSink.onAgentCompleted(task, result);
        return result;
    }

    private AgentStepResult replay(AgentTask task, StepOutcome cached) {
        // Re-hydrate the run's token + cost budgets so a skipped step still counts against the backstops.
        tokensSpent.addAndGet(cached.totalTokens());
        costSpent.addAndGet(cached.costMicros());
        eventSink.onAgentStarted(task);
        // Reconstruct a result carrying the caller's live task + the cached outcome, with an EMPTY transcript
        // (metadata is zeroed — consumers read text/structured; the token total is re-hydrated above). Design §5.3c.
        final SubagentExecutionResult raw = SubagentExecutionResult.emptySuccess(cached.text(), Instant.now());
        final AgentStepResult result = AgentStepResult.of(task, raw, cached.structured().orElse(null));
        eventSink.onAgentCompleted(task, result);
        return result;
    }

    private static StepOutcome toOutcome(AgentStepResult result, String inputHash, String structureFingerprint) {
        return StepOutcome.builder().text(result.text()).structured(result.structured().orElse(null))
                .totalTokens(result.raw().getMetadata().getTokenUsage().getTotalTokens())
                .costMicros(toMicros(result.raw().getCost())).completionReason(result.completionReason())
                .inputHash(inputHash).structureFingerprint(structureFingerprint).build();
    }

    /** Converts a {@link Money} USD amount to whole micros (USD &times; 10^6), truncating fractional micros. */
    private static long toMicros(Money cost) {
        return cost.getAmount().movePointRight(6).longValue();
    }

    @Override
    public <R> List<R> parallel(List<Supplier<R>> thunks) {
        Objects.requireNonNull(thunks, "thunks cannot be null");
        return fanout("p", thunks);
    }

    @Override
    public <I, A, R> List<R> pipeline(List<I> items, Function<I, A> stage1, BiFunction<A, I, R> stage2) {
        Objects.requireNonNull(items, "items cannot be null");
        Objects.requireNonNull(stage1, "stage1 cannot be null");
        Objects.requireNonNull(stage2, "stage2 cannot be null");
        // Each item's whole stage chain becomes one thunk fanned out: items run concurrently, stages within an item run
        // sequentially, and there is no barrier between stages across items (wall-clock = slowest single-item chain
        // when top-level and pool-capacity permits).
        final List<Supplier<R>> chains = new ArrayList<>(items.size());
        for (final I item : items) {
            chains.add(() -> stage2.apply(stage1.apply(item), item));
        }
        return fanout("q", chains);
    }

    /**
     * Shared fan-out core for {@code parallel}/{@code pipeline}. Consumes this level's construct ordinal
     * ({@code kind + <n>}), then runs each thunk under a fresh child path frame {@code <constructPath>/<listIndex>} so
     * every leaf {@code agent()} keys deterministically by position.
     */
    private <R> List<R> fanout(String kind, List<Supplier<R>> thunks) {
        final PathFrame parent = currentFrame();
        final String constructSegment = parent.next(kind);
        final String constructPath = parent.childPath(constructSegment);
        final int level = parent.nestingLevel() + 1;
        // Fold the construct (kind ordinal + child count) into the parent digest so a fan-out length change diverges
        // all
        // children (§6.5). Only maintained on the cache-active path; the child seed forks from the post-fold digest.
        final boolean cacheActive = stepResultCache != StepResultCache.NO_OP;
        final String base = cacheActive ? parent.foldConstruct(constructSegment, thunks.size()) : "";
        final List<Supplier<R>> wrapped = new ArrayList<>(thunks.size());
        for (int i = 0; i < thunks.size(); i++) {
            final Supplier<R> original = thunks.get(i);
            final String childPrefix = constructPath + "/" + i;
            final String childSeed = cacheActive ? sha256Hex(base + '|' + i) : "";
            wrapped.add(() -> runUnderFrame(childPrefix, level, childSeed, original));
        }
        // Route by the per-call-stack nesting level (§6.2): a level within maxNestingDepth uses the shared cached
        // pool (true nested parallelism); a deeper level degrades to the never-pooled sequential dispatcher. Both keep
        // input-order reassembly and failure isolation, and both propagate a run-fatal WorkflowException. The
        // run's cancellation signal is threaded so a nested join is interruptible.
        final BoundedFanoutDispatcher dispatcher = level <= maxNestingDepth ? fanout : sequentialFanout;
        return dispatcher.dispatch(wrapped, Supplier::get, (thunk, error) -> null, baseEnv.getCancellationSignal());
    }

    /**
     * Runs {@code body} under a fresh child path frame rooted at {@code prefix} with the given nesting {@code level}
     * and
     * fingerprint {@code seed}, restoring the previous frame after (a stack discipline that survives both the
     * sequential
     * degrade and true nested parallelism).
     */
    private <R> R runUnderFrame(String prefix, int level, String seed, Supplier<R> body) {
        final PathFrame previous = frame.get();
        frame.set(new PathFrame(prefix, level, seed));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                frame.remove();
            } else {
                frame.set(previous);
            }
        }
    }

    private PathFrame currentFrame() {
        PathFrame f = frame.get();
        if (f == null) {
            f = new PathFrame("", 0, "");
            frame.set(f);
        }
        return f;
    }

    /**
     * Removes the calling thread's root path frame. Called by the runner after the script body finishes so a pooled
     * run-hosting (or caller) thread does not retain a completed run's final frame in its thread-local map.
     */
    void clearRootFrame() {
        frame.remove();
    }

    /**
     * Validation hash of a step's input (design §5.3b): the goal, the inline subagent's definition, the result
     * schema, and the {@code isolate}/{@code nonCacheable} flags. On load a mismatch means the script changed what
     * runs at this structural position, so a stale cache entry is treated as a miss. The flags are folded in because
     * they are part of the step's definition — flipping {@code isolate} re-scopes where the step's file writes land,
     * so both the step's own validation and (via {@code foldLeaf}) downstream siblings' left-context fingerprints
     * diverge on a flip. For cacheable steps both flags are false, and flagged steps bypass load/save anyway, so the
     * fold only ever matters through the structure guard (§6.5).
     * The subagent's {@code hashCode()} is a value hash of its definition (name, system prompt,
     * metadata, tool restrictions), stable within a process — and cross-JVM stable to the extent it bottoms out in
     * String/int/collection hashes.
     */
    private static String inputHash(AgentTask task) {
        final Subagent sa = task.getSubagent();
        final StringBuilder sb = new StringBuilder(256);
        sb.append(task.getGoal()).append(' ').append(sa.getName()).append(' ').append(sa.getMaxIterations()).append(' ')
                .append(sa.hashCode()).append(' ').append(sa.getAllowedTools()).append(' ').append(task.isIsolate())
                .append(' ').append(task.isNonCacheable()).append(' ');
        try {
            sb.append(HASH_MAPPER.writeValueAsString(task.getResultSchema().orElse(Map.of())));
        } catch (JsonProcessingException e) {
            sb.append("schema-serialization-error");
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public void phase(String title) {
        eventSink.onPhase(title);
    }

    @Override
    public void log(String message) {
        eventSink.onLog(message);
    }

    /**
     * A single structural-path level. {@code prefix} is the path up to this level ({@code ""} at root, else e.g.
     * {@code "p1/0"}); {@code ordinal} is the program-order counter the owning thread advances once per child
     * construct;
     * {@code nestingLevel} is the per-call-stack fan-out depth used for routing (§6.2); {@code runningDigest} is the
     * left-context structure fingerprint chain (§6.5), seeded from the parent. Not thread-safe by design: a level's
     * body
     * runs on exactly one thread.
     */
    private static final class PathFrame {
        private final String prefix;
        private final int nestingLevel;
        private String runningDigest;
        private int ordinal;

        PathFrame(String prefix, int nestingLevel, String seed) {
            this.prefix = prefix;
            this.nestingLevel = nestingLevel;
            this.runningDigest = seed;
        }

        int nestingLevel() {
            return nestingLevel;
        }

        /** Returns the next segment ({@code kind + ordinal}) and advances the level's counter. */
        String next(String kind) {
            return kind + ordinal++;
        }

        /** Joins {@code segment} onto this level's prefix. */
        String childPath(String segment) {
            return prefix.isEmpty() ? segment : prefix + "/" + segment;
        }

        /** Chains a leaf's segment + input hash into the running left-context digest; returns the post-fold value. */
        String foldLeaf(String segment, String inputHash) {
            runningDigest = sha256Hex(runningDigest + '|' + segment + '|' + inputHash);
            return runningDigest;
        }

        /** Chains a fan-out construct's segment + child count into the running digest; returns the post-fold value. */
        String foldConstruct(String segment, int childCount) {
            runningDigest = sha256Hex(runningDigest + '|' + segment + '|' + childCount);
            return runningDigest;
        }
    }
}
