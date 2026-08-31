package at.aimon.session.routing.internal;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Narrow write-side view of an event stream for collaborators that only push frames — they neither subscribe nor end
 * the stream.
 *
 * <p>
 * Exists primarily so {@link HolderLossSweeper} can depend on the abstraction (DIP) and be unit-tested with a stub
 * sink instead of a full publisher. The single method is also what lets a sink be composed at the injection point: the
 * manager hands the sweeper a fan-out that delivers locally <em>and</em> relays on the {@code EVENT} rail, so the
 * sweeper still makes one call and stays ignorant of the rail.
 *
 * <p>
 * <b>No completion.</b> This interface used to mirror {@link InProcessEventPublisher#complete(SessionId)} as well,
 * and the sweeper called it — which ended a session's stream over a single dead turn while a successor node was
 * quite possibly running the next one. Stage 3b removed the call; the method is gone from the abstraction so the
 * abstraction cannot express it. {@code complete} remains on the publisher for the paths that genuinely end a
 * session ({@code releaseSession}, {@code deleteSession}, an {@code EVICT} signal), which hold the
 * concrete type.
 */
@FunctionalInterface
interface EventSink {

    /**
     * Push {@code event} to subscribers of {@code id}'s stream. Implementations must not block the caller.
     *
     * @param id
     *            the session (must not be null)
     * @param event
     *            the event (must not be null)
     */
    void emit(SessionId id, AgentExecutionEvent event);
}
