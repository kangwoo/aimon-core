package at.aimon.session.routing.internal;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Backing publisher for {@link at.aimon.session.routing.SessionRouter#events(SessionId)}.
 *
 * <p>
 * Maintains one {@link SubmissionPublisher} per {@link SessionId}, lazily created on first emit or subscribe.
 * Multi-subscriber: every {@code Flow.Subscriber} attached to a publisher receives the same event stream.
 *
 * <p>
 * Producer non-blocking invariant (design §5.5.1): {@link #emit(SessionId, AgentExecutionEvent)} must never
 * block the turn thread. We use {@code SubmissionPublisher.offer(item, 0L, NANOS, onDrop)} so demand-saturated
 * subscribers cause oldest events to be dropped rather than parking the producer.
 */
public final class InProcessEventPublisher implements EventSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);

    // @formatter:off
    private final ConcurrentMap<SessionId, SubmissionPublisher<AgentExecutionEvent>> publishers
            = new ConcurrentHashMap<>();
    // @formatter:on

    /**
     * Returns the publisher for {@code id}, creating one on first use.
     *
     * @param id
     *            the session (must not be null)
     * @return a non-null publisher
     */
    public Flow.Publisher<AgentExecutionEvent> publisherFor(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        return publishers.computeIfAbsent(id, k -> new SubmissionPublisher<>());
    }

    /**
     * Push {@code event} to subscribers of {@code id}'s publisher (lazy-creating one if needed).
     *
     * <p>
     * Slow subscribers cause the oldest queued event to be dropped — never block the producer.
     *
     * @param id
     *            the session (must not be null)
     * @param event
     *            the event (must not be null)
     */
    @Override
    public void emit(SessionId id, AgentExecutionEvent event) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(event, "event must not be null");
        final SubmissionPublisher<AgentExecutionEvent> publisher = publishers.computeIfAbsent(id,
                k -> new SubmissionPublisher<>());
        publisher.offer(event, 0L, TimeUnit.NANOSECONDS, (sub, dropped) -> {
            log.warn("Dropped event for slow subscriber on session {}", id);
            return false;
        });
    }

    /**
     * Emit {@code onComplete()} on the publisher for {@code id} and remove it from the map. Subsequent
     * {@code emit}/{@code publisherFor} for the same id transparently allocate a fresh publisher.
     *
     * <p>
     * Deliberately absent from {@link EventSink}: ending a session's stream is for the paths that end the
     * session, and a collaborator holding only the sink view must not be able to reach it. See {@code EventSink}'s
     * class comment for the bug that produced that rule.
     *
     * @param id
     *            the session (must not be null)
     */
    public void complete(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        final SubmissionPublisher<AgentExecutionEvent> publisher = publishers.remove(id);
        if (publisher != null) {
            publisher.close();
        }
    }

    @Override
    public void close() {
        publishers.values().forEach(SubmissionPublisher::close);
        publishers.clear();
    }
}
