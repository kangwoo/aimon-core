package at.aimon.core.agent.compact;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Default {@link CompactionGuard} implementation enforcing the four-state decision rule (NONE / WARN / COMPACT / BLOCK)
 * defined in §3.2 of the design document.
 *
 * <p>
 * Behaviour highlights:
 *
 * <ul>
 * <li><b>Threshold ladder</b>: warning → auto-compact → blocking. Computed from the per-model
 * {@link ModelContextLimits} resolved via {@link ModelContextWindowRegistry}.
 * <li><b>Forced blocking compaction</b>: if the conversation crosses the blocking limit and the precondition holds,
 * compaction is attempted with {@code forced = true}, regardless of circuit breaker state. Failure produces
 * {@code BLOCK}.
 * <li><b>Circuit breaker</b>: per-{@link SessionId} consecutive failure counter. Opens after
 * {@value #DEFAULT_MAX_CONSECUTIVE_FAILURES} consecutive AUTO failures, suppressing further AUTO compactions until a
 * successful compaction (AUTO or MANUAL) resets the counter via {@link #recordExternalSuccess(SessionId)}.
 * <b>Hook-blocks and reentrancy do not count</b> as failures: they reflect intent or programmer error rather than
 * transient fault, so counting them would silently disable AUTO compaction for the session.
 * <li><b>Per-session lock</b>: a {@link ReentrantLock} per {@link SessionId} ensures only one thread evaluates the
 * decision at a time for a given session.
 * <li><b>Bounded state maps</b>: {@code sessionLocks} is LRU-bounded ({@value #DEFAULT_MAX_TRACKED_SESSIONS}) to
 * prevent unbounded growth in long-running processes that handle many short-lived sessions. The failure-count
 * state lives in a pluggable {@link CompactionFailureStore} ({@link InMemoryCompactionFailureStore} by default, which
 * breaks the circuit per process; a deployment that wants one counter shared across instances passes
 * {@link SessionRecordCompactionFailureStore} over its record store — design §8).
 * </ul>
 *
 * <p>
 * Thread-safe. Per-session locks remain in-memory and serialise compaction attempts on a single instance only;
 * multi-instance ordering is the responsibility of the supplied {@link CompactionFailureStore} and whatever durable
 * store it is built on.
 */
public class DefaultCompactionGuard implements CompactionGuard {

    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;
    public static final int DEFAULT_MAX_TRACKED_SESSIONS = 1024;

    private static final Logger log = LoggerFactory.getLogger(DefaultCompactionGuard.class);

    private final CompactionEngine compactionEngine;
    private final ModelContextWindowRegistry modelContextWindowRegistry;
    private final TokenEstimator tokenEstimator;
    private final int maxConsecutiveFailures;
    private final CompactionFailureStore failureStore;

    private final Map<SessionId, ReentrantLock> sessionLocks;

    public DefaultCompactionGuard(CompactionEngine compactionEngine,
            ModelContextWindowRegistry modelContextWindowRegistry, TokenEstimator tokenEstimator) {
        this(compactionEngine, modelContextWindowRegistry, tokenEstimator, DEFAULT_MAX_CONSECUTIVE_FAILURES,
                DEFAULT_MAX_TRACKED_SESSIONS, new InMemoryCompactionFailureStore());
    }

    public DefaultCompactionGuard(CompactionEngine compactionEngine,
            ModelContextWindowRegistry modelContextWindowRegistry, TokenEstimator tokenEstimator,
            int maxConsecutiveFailures) {
        this(compactionEngine, modelContextWindowRegistry, tokenEstimator, maxConsecutiveFailures,
                DEFAULT_MAX_TRACKED_SESSIONS, new InMemoryCompactionFailureStore());
    }

    public DefaultCompactionGuard(CompactionEngine compactionEngine,
            ModelContextWindowRegistry modelContextWindowRegistry, TokenEstimator tokenEstimator,
            int maxConsecutiveFailures, int maxTrackedSessions) {
        this(compactionEngine, modelContextWindowRegistry, tokenEstimator, maxConsecutiveFailures, maxTrackedSessions,
                new InMemoryCompactionFailureStore(maxTrackedSessions));
    }

    /**
     * Full constructor allowing the caller to supply a custom {@link CompactionFailureStore} — in a multi-instance
     * deployment, {@link SessionRecordCompactionFailureStore} over {@code SessionStore.records()}, so that every
     * instance serving the session breaks the same circuit.
     *
     * @param compactionEngine
     *            The compaction engine (must not be null)
     * @param modelContextWindowRegistry
     *            The context-window registry (must not be null)
     * @param tokenEstimator
     *            The token estimator (must not be null)
     * @param maxConsecutiveFailures
     *            Failure-count threshold that opens the circuit breaker (must be {@code >= 1})
     * @param maxTrackedSessions
     *            LRU-bound for the per-session lock map (must be {@code >= 1})
     * @param failureStore
     *            The failure-counter storage backend (must not be null)
     */
    public DefaultCompactionGuard(CompactionEngine compactionEngine,
            ModelContextWindowRegistry modelContextWindowRegistry, TokenEstimator tokenEstimator,
            int maxConsecutiveFailures, int maxTrackedSessions, CompactionFailureStore failureStore) {
        this.compactionEngine = Objects.requireNonNull(compactionEngine, "CompactionEngine cannot be null");
        this.modelContextWindowRegistry = Objects.requireNonNull(modelContextWindowRegistry,
                "ModelContextWindowRegistry cannot be null");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "TokenEstimator cannot be null");
        if (maxConsecutiveFailures < 1) {
            throw new IllegalArgumentException("maxConsecutiveFailures must be >= 1, got: " + maxConsecutiveFailures);
        }
        if (maxTrackedSessions < 1) {
            throw new IllegalArgumentException("maxTrackedSessions must be >= 1, got: " + maxTrackedSessions);
        }
        this.failureStore = Objects.requireNonNull(failureStore, "CompactionFailureStore cannot be null");
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.sessionLocks = Collections.synchronizedMap(new BoundedLruMap<>(maxTrackedSessions));
    }

    /**
     * Resets the consecutive-failure counter for the given session. Intended to be called by callers that perform
     * MANUAL compactions directly via the {@link CompactionEngine} so the circuit breaker re-opens for AUTO traffic.
     */
    @Override
    public void recordExternalSuccess(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        failureStore.reset(sessionId);
    }

    @Override
    public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment) {
        return serializedEvaluate(memory, model, hookRegistry, environment, null, false);
    }

    @Override
    public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, ExecutionId executionId) {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        return serializedEvaluate(memory, model, hookRegistry, environment, executionId, false);
    }

    @Override
    public CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment) {
        return serializedEvaluate(memory, model, hookRegistry, environment, null, true);
    }

    @Override
    public CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, ExecutionId executionId) {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        return serializedEvaluate(memory, model, hookRegistry, environment, executionId, true);
    }

    /**
     * Takes the per-buffer lock and evaluates the decision under it.
     *
     * <p>
     * The lock and the circuit breaker are keyed on {@link TranscriptBuffer#getSessionId()} rather than on
     * {@code executionId}, because that key must identify the <em>buffer</em> two threads could enter concurrently,
     * and a session-less run's buffer is labelled with its own run id anyway — so the key stays unique per run either
     * way. {@code executionId} travels separately because it answers a different question: who to name to the hooks.
     *
     * @param executionId
     *            the run identity to hand to the engine, or {@code null} when the compaction belongs to a genuine
     *            session and the buffer's session id is the honest identity
     * @param budgetForced
     *            {@code true} lowers the effective auto-compact trigger to the warning band, so a proactive budget
     *            hint compacts earlier than the model's own auto-compact threshold would
     */
    private CompactionDecision serializedEvaluate(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, ExecutionId executionId, boolean budgetForced) {
        Objects.requireNonNull(memory, "memory cannot be null");
        Objects.requireNonNull(model, "model cannot be null");
        Objects.requireNonNull(hookRegistry, "hookRegistry cannot be null");
        Objects.requireNonNull(environment, "environment cannot be null");

        final SessionId sessionId = memory.getSessionId();
        final ReentrantLock lock;
        synchronized (sessionLocks) {
            lock = sessionLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
        }
        if (!lock.tryLock()) {
            return CompactionDecision.none("concurrent compaction in progress");
        }
        try {
            return evaluate(memory, model, hookRegistry, environment, sessionId, executionId, budgetForced);
        } finally {
            lock.unlock();
        }
    }

    private CompactionDecision evaluate(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, SessionId sessionId, ExecutionId executionId, boolean budgetForced) {
        final String modelName = model.getName().orElseGet(() -> {
            log.debug("Model name absent for session {}; falling back to default ModelContextLimits", sessionId);
            return "";
        });
        final ModelContextLimits limits = modelContextWindowRegistry.resolve(modelName);
        final List<Message> messages = memory.getMessages();
        final int estimated = tokenEstimator.estimate(memory.getSystemPrompt(), messages);

        final int autoCompactThreshold = limits.getAutoCompactThreshold();
        final int warningThreshold = limits.getWarningThreshold();
        final int blockingLimit = limits.getBlockingLimit();
        // A budget-forced pass compacts as soon as the (lower) warning band is reached instead of waiting for the
        // model's auto-compact band. Normal AUTO passes keep the auto-compact band.
        final int effectiveAutoThreshold = budgetForced ? warningThreshold : autoCompactThreshold;
        final boolean preconditionMet = preconditionMet(messages);

        // 1) blocking-limit forced compaction takes priority over the circuit breaker
        if (estimated >= blockingLimit) {
            if (!preconditionMet) {
                return CompactionDecision.warn("blocking-limit reached but precondition unmet; deferring", estimated,
                        blockingLimit);
            }
            final CompactionResult result = invokeEngine(memory, model, hookRegistry, environment, sessionId,
                    executionId, true);
            if (result.isSuccess()) {
                resetFailures(sessionId);
                return CompactionDecision.compact(result, "blocking-limit forced compaction", estimated, blockingLimit);
            }
            recordFailureIfTransient(sessionId, result);
            return CompactionDecision.block("compaction failed at blocking limit: " + describeError(result), estimated,
                    blockingLimit);
        }

        // 2) circuit breaker — only blocks AUTO; MANUAL bypasses guard entirely
        if (failureCount(sessionId) >= maxConsecutiveFailures) {
            return CompactionDecision.none("circuit breaker open");
        }

        // 3) auto compact
        if (estimated >= effectiveAutoThreshold && preconditionMet) {
            final CompactionResult result = invokeEngine(memory, model, hookRegistry, environment, sessionId,
                    executionId, false);
            if (result.isSuccess()) {
                resetFailures(sessionId);
                final String reason = budgetForced
                        ? "budget-forced compaction (warning band)"
                        : "auto-compact threshold reached";
                return CompactionDecision.compact(result, reason, estimated, blockingLimit);
            }
            recordFailureIfTransient(sessionId, result);
            return CompactionDecision.compact(result, "auto-compact attempted but failed", estimated, blockingLimit);
        }

        // 4) warn band
        if (estimated >= warningThreshold) {
            return CompactionDecision.warn("warning threshold reached: estimated=" + estimated + ", warningAt="
                    + warningThreshold + ", autoCompactAt=" + autoCompactThreshold, estimated, blockingLimit);
        }

        return CompactionDecision.none();
    }

    private CompactionResult invokeEngine(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
            Environment environment, SessionId sessionId, ExecutionId executionId, boolean forced) {
        // executionId is null for a session-backed compaction; the builder treats that as "identify by session id",
        // which is what the engine did before this channel existed.
        final CompactionRequest request = CompactionRequest.builder().transcriptBuffer(memory)
                .trigger(CompactionTrigger.AUTO).model(model).hookRegistry(hookRegistry).environment(environment)
                .forced(forced).executionId(executionId).build();
        try {
            return compactionEngine.compact(request);
        } catch (RuntimeException e) {
            log.error("CompactionEngine threw unexpected exception for session {}: {}", sessionId, e.getMessage(), e);
            // Defensive: engines should never escape exceptions, but if they do, surface as failure.
            final CompactionMetadata metadata = CompactionMetadata.builder().trigger(CompactionTrigger.AUTO)
                    .startedAt(Instant.now()).completedAt(Instant.now()).build();
            return CompactionResult.failure(e, metadata);
        }
    }

    /**
     * Increments the circuit-breaker failure counter only for transient failures. Hook-blocks and reentrant calls are
     * intentional / programmer-error signals and must not silently disable AUTO compaction.
     */
    private void recordFailureIfTransient(SessionId sessionId, CompactionResult result) {
        final Exception error = result.getError().orElse(null);
        if (error instanceof CompactionBlockedByHookException) {
            log.debug("Compaction blocked by hook for session {}; not counted toward circuit breaker", sessionId);
            return;
        }
        if (error instanceof CompactionReentrancyException) {
            log.debug("Reentrant compaction for session {}; not counted toward circuit breaker", sessionId);
            return;
        }
        recordFailure(sessionId);
    }

    private static boolean preconditionMet(List<Message> messages) {
        if (messages.isEmpty()) {
            return true;
        }
        final Message last = messages.get(messages.size() - 1);
        // ASSISTANT with pending tool_use → results not yet attached.
        if (last.getRole() == Role.ASSISTANT && last.hasToolUses()) {
            return allToolUsesResolved(messages);
        }
        // USER turn — design §3.2 also requires that all in-flight tool_uses (if any) are resolved before compaction.
        // In practice they are, since user messages are appended after tool results, but we verify defensively to
        // cover mid-turn queue injection paths.
        if (last.getRole() == Role.USER) {
            return allToolUsesResolved(messages);
        }
        return true;
    }

    private static boolean allToolUsesResolved(List<Message> messages) {
        final Set<String> pending = new HashSet<>();
        for (Message message : messages) {
            for (ToolUse toolUse : message.getToolUses()) {
                pending.add(toolUse.getId());
            }
            for (ToolUseResult result : message.getToolUseResults()) {
                pending.remove(result.getToolUseId());
            }
        }
        return pending.isEmpty();
    }

    private int failureCount(SessionId sessionId) {
        return failureStore.get(sessionId);
    }

    private void recordFailure(SessionId sessionId) {
        failureStore.recordFailure(sessionId);
    }

    private void resetFailures(SessionId sessionId) {
        failureStore.reset(sessionId);
    }

    private static String describeError(CompactionResult result) {
        return result.getError().map(e -> e.getClass().getSimpleName() + ": " + e.getMessage()).orElse("unknown");
    }

    /**
     * LinkedHashMap-backed LRU map used to bound failure-count and lock state. Wrapped in
     * {@link Collections#synchronizedMap} by the guard for thread-safe access.
     */
    private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        BoundedLruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
