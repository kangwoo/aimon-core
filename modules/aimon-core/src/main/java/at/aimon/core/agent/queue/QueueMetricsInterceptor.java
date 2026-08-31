package at.aimon.core.agent.queue;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interceptor.AgentExecutionChain;
import at.aimon.core.agent.interceptor.AgentExecutionInterceptor;

/**
 * {@link AgentExecutionInterceptor} that correlates mid-turn command queue activity with the boundary of a single
 * agent execution.
 *
 * <h2>Why both a listener and an interceptor?</h2>
 *
 * <p>
 * The queue already offers {@link LoggingMessageQueueListener} — a cumulative per-event observer that is oblivious to
 * turn boundaries. That is the right hook for questions like <i>"how many messages were ever enqueued?"</i>. This
 * interceptor complements it by answering questions the listener cannot:
 * <ul>
 * <li>How deep was the queue (for this context) when the turn started? When it finished?
 * <li>How many messages were enqueued / drained <i>while the turn was running</i>?
 * <li>How long did the turn take?
 * </ul>
 * These quantities require knowledge of execution boundaries, which is exactly what
 * {@link AgentExecutionInterceptor} provides. The interceptor attaches a short-lived, context-filtered
 * {@link MessageQueueListener} for the duration of the execution, so observations are scoped to the current turn and
 * to the current {@link AgentRuntimeId} (main-agent vs. sub-agent traffic stay separate).
 *
 * <h2>Emitted output</h2>
 *
 * <p>
 * At the end of every observed execution the interceptor logs a single DEBUG line (matching the log level used by
 * {@link LoggingMessageQueueListener} so both observers can be enabled/disabled together):
 *
 * <pre>
 * queue-metrics ctx=... preDepth=N postDepth=M enqueuedInTurn=K drainedInTurn=L durationMs=T
 * </pre>
 *
 * and increments aggregate {@link LongAdder} counters exposed via the getters. Projects wiring a metrics backend
 * (Micrometer, OpenTelemetry, …) can subclass or wrap this interceptor and translate the per-turn values to their
 * registry of choice.
 *
 * <h2>Placement in the interceptor chain</h2>
 *
 * <p>
 * Register this interceptor as close to the outermost layer as possible so {@code preDepth}/{@code postDepth}
 * include work performed by other interceptors. {@link #getOrder()} returns {@code -1000} so that, in chains using
 * natural ordering, this interceptor wraps the rest by default.
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Safe for concurrent intercepts. Each invocation builds its own turn-scoped listener; aggregate counters use
 * {@link LongAdder}. Listener exceptions are isolated by the manager.
 *
 * <h2>Caveat: overlapping executions with the same agent runtime id</h2>
 *
 * <p>
 * The turn-scoped listener filters by {@link AgentRuntimeId}, not by identity of the executing turn. If
 * two executions run concurrently with the <i>same</i> agent runtime id, each listener observes every matching event,
 * and the two turns will double-count the shared traffic. In practice a single agent runtime id runs its turns
 * sequentially, so this is only a concern for exotic setups.
 *
 * @param <CTX>
 *            agent runtime type
 * @param <REQ>
 *            execution request type
 * @param <RES>
 *            execution result type
 *
 * @see LoggingMessageQueueListener
 * @see MessageQueueManager
 */
// @formatter:off
public final class QueueMetricsInterceptor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult>
        implements AgentExecutionInterceptor<CTX, REQ, RES> {
    // @formatter:on

    private static final Logger log = LoggerFactory.getLogger(QueueMetricsInterceptor.class);

    /**
     * Interceptor order: sits outermost in a naturally-ordered chain so that pre/post depth snapshots include the
     * effects of all downstream interceptors.
     */
    private static final int ORDER = -1000;

    private final MessageQueueManager manager;

    private final LongAdder executionsObserved = new LongAdder();
    private final LongAdder enqueuedDuringExecutions = new LongAdder();
    private final LongAdder drainedDuringExecutions = new LongAdder();
    private final LongAdder totalDurationMillis = new LongAdder();

    /**
     * Creates a new interceptor that observes the given manager.
     *
     * @param manager
     *            the queue manager to observe (must not be null)
     * @throws NullPointerException
     *             if {@code manager} is null
     */
    public QueueMetricsInterceptor(MessageQueueManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager cannot be null");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public RES intercept(CTX context, REQ request, AgentExecutionChain<CTX, REQ, RES> chain) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(chain, "chain cannot be null");

        final AgentRuntimeId agentRuntimeId = context.getId();
        if (agentRuntimeId == null) {
            // The AgentRuntime contract permits a null id (some test doubles return null). In that case
            // we cannot distinguish this turn's traffic from concurrent traffic on other contexts, so we fall back
            // to counting everything observed during the turn. Warn loudly so production wiring does not rely on
            // this degraded mode silently.
            log.warn("QueueMetricsInterceptor observed a null AgentRuntimeId — "
                    + "per-context filtering disabled for this turn; counts include all queue traffic.");
        }

        final LongAdder enqueued = new LongAdder();
        final LongAdder drained = new LongAdder();
        final MessageQueueListener turnListener = event -> {
            if (!matchesContext(agentRuntimeId, event.getInput().getAgentRuntimeId())) {
                return;
            }
            switch (event.getChangeType()) {
                case ENQUEUED :
                    enqueued.increment();
                    break;
                case DRAINED :
                    drained.increment();
                    break;
                case REMOVED :
                default :
                    // REMOVED is reserved and currently not emitted by the default manager; future change types
                    // are intentionally ignored until they are wired into a concrete metric.
                    break;
            }
        };

        // Capture preDepth BEFORE attaching the listener so that a concurrent enqueue happening between the two
        // operations cannot be attributed to both preDepth and enqueuedInTurn.
        final int preDepth = depthForContext(agentRuntimeId);
        manager.addListener(turnListener);
        final long startNanos = System.nanoTime();
        try {
            return chain.proceed(context, request);
        } finally {
            final long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            final int postDepth = depthForContext(agentRuntimeId);
            manager.removeListener(turnListener);

            final long enq = enqueued.sum();
            final long drn = drained.sum();
            executionsObserved.increment();
            enqueuedDuringExecutions.add(enq);
            drainedDuringExecutions.add(drn);
            totalDurationMillis.add(durationMs);

            if (log.isDebugEnabled()) {
                log.debug(
                        "queue-metrics ctx={} preDepth={} postDepth={} enqueuedInTurn={} "
                                + "drainedInTurn={} durationMs={}",
                        agentRuntimeId, preDepth, postDepth, enq, drn, durationMs);
            }
        }
    }

    /**
     * The number of executions this interceptor has observed to completion (success or exception).
     *
     * @return the execution count (monotonic, never negative)
     */
    public long getExecutionsObserved() {
        return executionsObserved.sum();
    }

    /**
     * Total number of {@link MessageQueueListener.ChangeType#ENQUEUED} events observed <i>while</i> an execution was
     * in flight and whose {@link AgentRuntimeId} matched the executing context.
     *
     * @return the cumulative enqueue count across all observed executions
     */
    public long getEnqueuedDuringExecutions() {
        return enqueuedDuringExecutions.sum();
    }

    /**
     * Total number of {@link MessageQueueListener.ChangeType#DRAINED} events observed <i>while</i> an execution was
     * in flight and whose {@link AgentRuntimeId} matched the executing context.
     *
     * @return the cumulative drain count across all observed executions
     */
    public long getDrainedDuringExecutions() {
        return drainedDuringExecutions.sum();
    }

    /**
     * Total wall-clock time (in milliseconds) spent inside intercepted executions. Each observed execution adds
     * its own {@code durationMs}. Useful as a sanity check in tests and as an aggregate figure for backends that
     * do not otherwise measure latency.
     *
     * @return the cumulative duration across all observed executions, in milliseconds
     */
    public long getTotalDurationMillis() {
        return totalDurationMillis.sum();
    }

    private int depthForContext(AgentRuntimeId agentRuntimeId) {
        final List<QueuedInput> snapshot = manager.snapshot();
        if (agentRuntimeId == null) {
            return snapshot.size();
        }
        int count = 0;
        for (QueuedInput queued : snapshot) {
            if (agentRuntimeId.equals(queued.getAgentRuntimeId())) {
                count++;
            }
        }
        return count;
    }

    private static boolean matchesContext(AgentRuntimeId executing, AgentRuntimeId event) {
        // A null executing context-id (unlikely in production, but permitted by the AgentRuntime contract
        // for test doubles) degrades to "match everything". The interceptor warns at intercept() entry before
        // reaching this branch, so the caller has already been notified.
        return executing == null || executing.equals(event);
    }
}
