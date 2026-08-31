package at.aimon.core.agent.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference {@link MessageQueueListener} that logs every queue change and keeps per-{@link ChangeType} counters.
 *
 * <p>
 * This listener is the default observability hook for the mid-turn command queue described in
 * {@code docs/features/agent-execution/command-queue-guide.md}. It is intentionally kept small and allocation-light so
 * it can be enabled
 * unconditionally — one log line per event at DEBUG (or INFO for the first few events, see {@link #onEvent(Event)}) and
 * a pair of {@link LongAdder} counters updated under the hood.
 *
 * <p>
 * Projects that need richer metrics (Micrometer, OpenTelemetry, …) should use this class as a template: implement
 * {@link MessageQueueListener} directly and register it via
 * {@link MessageQueueManager#addListener(MessageQueueListener)}. Queue events do not happen at {@code execute()}
 * boundaries, so they are not reachable from {@link at.aimon.core.agent.interceptor.AgentExecutionInterceptor} — the
 * listener extension point is the correct hook.
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * Safe for concurrent invocation from any producer/consumer thread. Counters are backed by {@link LongAdder}; the log
 * statement itself is thread-safe via SLF4J. Listener exceptions are isolated by the manager, but this implementation
 * never throws.
 */
public final class LoggingMessageQueueListener implements MessageQueueListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageQueueListener.class);

    private final Map<ChangeType, LongAdder> counters;

    /**
     * Creates a new listener with zeroed counters for every {@link ChangeType}.
     */
    public LoggingMessageQueueListener() {
        final Map<ChangeType, LongAdder> map = new EnumMap<>(ChangeType.class);
        for (ChangeType type : ChangeType.values()) {
            map.put(type, new LongAdder());
        }
        this.counters = map;
    }

    @Override
    public void onEvent(Event event) {
        Objects.requireNonNull(event, "event cannot be null");
        final ChangeType changeType = event.getChangeType();
        counters.get(changeType).increment();

        if (!log.isDebugEnabled()) {
            return;
        }
        final QueuedInput input = event.getInput();
        final Duration age = Duration.between(input.getEnqueuedAt(), Instant.now());
        log.debug("queue-event change={} uuid={} priority={} ctx={} ageMs={} source={}", changeType, input.getUuid(),
                input.getPriority(), input.getAgentRuntimeId(), age.toMillis(),
                input.getSourceAgentId().orElse("repl"));
    }

    /**
     * Returns the number of events observed for the given change type since this listener was created.
     *
     * @param changeType
     *            the change type to query (must not be null)
     * @return the current count (monotonic, never negative)
     * @throws NullPointerException
     *             if {@code changeType} is null
     */
    public long getCount(ChangeType changeType) {
        Objects.requireNonNull(changeType, "changeType cannot be null");
        return counters.get(changeType).sum();
    }

    /**
     * Convenience shortcut for {@code getCount(ChangeType.ENQUEUED)}.
     *
     * @return the enqueue count
     */
    public long getEnqueuedCount() {
        return getCount(ChangeType.ENQUEUED);
    }

    /**
     * Convenience shortcut for {@code getCount(ChangeType.DRAINED)}.
     *
     * @return the drain count
     */
    public long getDrainedCount() {
        return getCount(ChangeType.DRAINED);
    }
}
