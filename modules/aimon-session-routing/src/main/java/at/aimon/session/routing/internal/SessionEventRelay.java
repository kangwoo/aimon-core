package at.aimon.session.routing.internal;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantTextDelta;

/**
 * Bridges the session's {@code submitAsync(input, listener)} listener channel to (1) the local
 * {@link InProcessEventPublisher} and (2) the cross-node {@link SessionSignalBus} for
 * {@link SessionSignal.SignalKind#EVENT} broadcast (design §5.5).
 *
 * <p>
 * <b>One relay per turn.</b> The relay is constructed with the {@link TurnId} of the turn it serves and stamps it onto
 * every payload it publishes, so a remote subscriber can tell which turn a frame belongs to instead of guessing from
 * arrival order. Sharing one relay across two turns of the same session would attribute the second turn's frames
 * to the first, which is exactly what a per-turn event subscription must not see.
 *
 * <p>
 * Producer non-blocking invariant: {@link #accept(AgentExecutionEvent)} runs on the turn execution thread and must
 * never block. The local fan-out goes through {@link InProcessEventPublisher#emit} (non-blocking offer); the remote
 * fan-out is decoupled via a bounded {@link ArrayBlockingQueue} drained by a manager-owned dispatcher thread, so the
 * Redis publish latency can never stall the turn.
 *
 * <p>
 * <b>Overflow policy</b> (design §5.5: relay_remote_buffer_drop_total). The remote channel is best-effort, but not all
 * frames are equally droppable. {@link AssistantTextDelta} is high-volume and individually low-value, whereas the
 * terminal frames — {@link at.aimon.core.agent.stream.ExecutionCompleted ExecutionCompleted},
 * {@link at.aimon.core.agent.stream.ExecutionError ExecutionError},
 * {@link at.aimon.core.agent.stream.InterruptedAt InterruptedAt} and
 * {@link at.aimon.core.agent.stream.RejectedAt RejectedAt} — are what tells a remote subscriber the turn is over. A
 * subscriber that loses a terminal frame never completes, so on overflow this relay discards the oldest buffered text
 * delta first and only sacrifices a structural frame when the buffer holds nothing else. Drops are counted
 * ({@link #getDroppedEventCount()}) and reported at {@link #close()} so a gap is never silent.
 */
public final class SessionEventRelay implements Consumer<AgentExecutionEvent>, AutoCloseable {

    /** Bounded queue capacity per relay — design §5.5 recommended size. */
    public static final int RELAY_QUEUE_CAPACITY = 1024;

    private static final Logger log = LoggerFactory.getLogger(SessionEventRelay.class);

    private final SessionId sessionId;
    private final TurnId turnId;
    private final InProcessEventPublisher localPublisher;
    private final SessionSignalBus signalBus;
    private final String originNodeId;
    private final ExecutorService dispatcher;
    private final BlockingQueue<AgentExecutionEvent> remoteBuffer;
    private final AtomicLong droppedEvents = new AtomicLong();

    /**
     * @param sessionId
     *            the session whose events this relay carries (must not be null)
     * @param turnId
     *            the turn this relay is dedicated to; stamped onto every {@code EVENT} payload it publishes (must not
     *            be
     *            null). One relay serves exactly one turn — reusing a relay across turns would attribute the second
     *            turn's frames to the first.
     * @param localPublisher
     *            the in-process publisher receiving the local fan-out (must not be null)
     * @param signalBus
     *            the cross-node bus receiving the remote fan-out (must not be null)
     * @param originNodeId
     *            this node's id, so receivers can tell self-originated signals apart (must not be null)
     * @param dispatcher
     *            the manager-owned executor that drains the remote buffer off the turn thread (must not be null)
     */
    public SessionEventRelay(SessionId sessionId, TurnId turnId, InProcessEventPublisher localPublisher,
            SessionSignalBus signalBus, String originNodeId, ExecutorService dispatcher) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.turnId = Objects.requireNonNull(turnId, "turnId must not be null");
        this.localPublisher = Objects.requireNonNull(localPublisher, "localPublisher must not be null");
        this.signalBus = Objects.requireNonNull(signalBus, "signalBus must not be null");
        this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.remoteBuffer = new ArrayBlockingQueue<>(RELAY_QUEUE_CAPACITY);
    }

    @Override
    public void accept(AgentExecutionEvent event) {
        if (event == null) {
            return;
        }
        try {
            localPublisher.emit(sessionId, event);
        } catch (Exception e) {
            log.warn("Local emit threw for session {}: {}", sessionId, e.toString());
        }
        if (!remoteBuffer.offer(event)) {
            handleOverflow(event);
        }
        try {
            dispatcher.execute(this::drainOnce);
        } catch (RejectedExecutionException ignored) {
            // dispatcher already shutting down — events will be drained synchronously by close()
        }
    }

    /**
     * Makes room for {@code incoming} by discarding the least valuable frame available, preferring a buffered text
     * delta over any structural frame. Never blocks; runs on the turn execution thread.
     */
    private void handleOverflow(AgentExecutionEvent incoming) {
        final boolean freedSlot = discardOldestDelta();
        if (freedSlot || !isDroppable(incoming)) {
            if (!freedSlot) {
                // The buffer holds only structural frames and so does the incoming event. Sacrifice the oldest one:
                // the newest frames matter more here because the terminal frame is always the last to arrive.
                remoteBuffer.poll();
            }
            remoteBuffer.offer(incoming);
        }
        // Otherwise the buffer is all structural frames and the incoming event is a text delta — drop the delta.
        final long dropped = droppedEvents.incrementAndGet();
        if (dropped == 1) {
            log.warn("Relay remote buffer overflow for session {} — remote event stream now has a gap", sessionId);
        } else {
            log.debug("Relay remote buffer overflow for session {} — {} events dropped so far", sessionId, dropped);
        }
    }

    /**
     * Removes the oldest buffered {@link AssistantTextDelta}, if any.
     *
     * @return {@code true} when a delta was removed and a slot is now free
     */
    private boolean discardOldestDelta() {
        for (Iterator<AgentExecutionEvent> it = remoteBuffer.iterator(); it.hasNext();) {
            if (isDroppable(it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Text deltas are the only frames safe to lose: they are high-volume, and the assistant message they build is
     * summarised again by {@code AssistantMessageReceived} at the end of the iteration. Every other frame is either
     * structural or terminal.
     */
    private static boolean isDroppable(AgentExecutionEvent event) {
        return event instanceof AssistantTextDelta;
    }

    /**
     * Returns the number of events this relay dropped because the remote buffer was full.
     *
     * @return the drop count for this turn (never negative)
     */
    public long getDroppedEventCount() {
        return droppedEvents.get();
    }

    /**
     * Returns the turn this relay is dedicated to — the id stamped onto every {@code EVENT} payload it publishes.
     *
     * @return the turn id (never null)
     */
    public TurnId getTurnId() {
        return turnId;
    }

    private void drainOnce() {
        AgentExecutionEvent next;
        while ((next = remoteBuffer.poll()) != null) {
            final Map<String, Object> payload = AgentExecutionEventPayload.toPayload(next, turnId);
            if (payload == null) {
                // Unrecognized event subtype — skip cross-node relay; local delivery already happened in accept().
                continue;
            }
            try {
                signalBus.publish(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.EVENT)
                        .originNodeId(originNodeId).payload(payload).build());
            } catch (Exception e) {
                log.warn("SignalBus EVENT publish failed for session {}: {}", sessionId, e.toString());
            }
        }
    }

    /**
     * Synchronously drain remaining events. Called by the manager when the turn ends.
     */
    @Override
    public void close() {
        drainOnce();
        final long dropped = droppedEvents.get();
        if (dropped > 0) {
            log.warn("Relay for session {} dropped {} event(s) on the remote channel this turn", sessionId, dropped);
        }
    }
}
