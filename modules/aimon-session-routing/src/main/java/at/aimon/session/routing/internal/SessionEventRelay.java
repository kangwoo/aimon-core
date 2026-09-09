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
import at.aimon.core.agent.stream.AssistantReasoningDelta;
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
 * frames are equally droppable, and there are <b>three</b> ranks rather than two:
 *
 * <ol>
 * <li>{@link AssistantReasoningDelta} — sacrificed first. Higher-volume still than answer text, and nothing
 * downstream needs it at all: no transcript, no summary, no result field.</li>
 * <li>{@link AssistantTextDelta} — high-volume and individually low-value, but it builds the assistant message, which
 * {@link at.aimon.core.agent.stream.AssistantMessageReceived AssistantMessageReceived} summarises again at the end of
 * the iteration.</li>
 * <li>Everything else — structural. The terminal frames
 * ({@link at.aimon.core.agent.stream.ExecutionCompleted ExecutionCompleted},
 * {@link at.aimon.core.agent.stream.ExecutionError ExecutionError},
 * {@link at.aimon.core.agent.stream.InterruptedAt InterruptedAt},
 * {@link at.aimon.core.agent.stream.RejectedAt RejectedAt}) are what tells a remote subscriber the turn is over, and a
 * subscriber that loses one never completes.</li>
 * </ol>
 *
 * <p>
 * On overflow the relay evicts the oldest buffered frame that ranks no higher than the incoming one and is not
 * structural, scanning by rank ascending — so under pressure thinking is sacrificed before answer text, and an
 * incoming reasoning delta is itself dropped rather than displacing buffered text. Only when nothing droppable is
 * buffered <em>and</em> the incoming frame is structural is a structural frame sacrificed. Drops are counted
 * ({@link #getDroppedEventCount()}) and reported at {@link #close()} so a gap is never silent.
 */
public final class SessionEventRelay implements Consumer<AgentExecutionEvent>, AutoCloseable {

    /** Bounded queue capacity per relay — design §5.5 recommended size. */
    public static final int RELAY_QUEUE_CAPACITY = 1024;

    /** Sacrificed first: nothing downstream needs a reasoning delta once it has been rendered. */
    private static final int RANK_REASONING = 0;

    /** Sacrificed after reasoning: a text delta builds the assistant message. */
    private static final int RANK_TEXT = 1;

    /** Never sacrificed while anything droppable is buffered: this is how a subscriber learns the turn ended. */
    private static final int RANK_STRUCTURAL = 2;

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
     * Makes room for {@code incoming} by discarding the least valuable frame available: the oldest buffered frame
     * whose rank is no higher than the incoming frame's and below {@link #RANK_STRUCTURAL}. Never blocks; runs on the
     * turn execution thread.
     *
     * <p>
     * The "no higher than the incoming frame's" half is what makes the ranks an ordering rather than a wider boolean.
     * Without it an incoming reasoning delta would evict buffered answer text, which is the opposite of the intent —
     * under pressure the choice is between dropping thinking and dropping the answer, and it is not close.
     */
    private void handleOverflow(AgentExecutionEvent incoming) {
        final boolean freedSlot = discardOldestDroppable(evictionRank(incoming));
        if (freedSlot || !isDroppable(incoming)) {
            if (!freedSlot) {
                // The buffer holds only structural frames and so does the incoming event. Sacrifice the oldest one:
                // the newest frames matter more here because the terminal frame is always the last to arrive.
                remoteBuffer.poll();
            }
            remoteBuffer.offer(incoming);
        }
        // Otherwise nothing at or below the incoming frame's rank is buffered and the incoming frame is itself
        // droppable — drop it.
        final long dropped = droppedEvents.incrementAndGet();
        if (dropped == 1) {
            log.warn("Relay remote buffer overflow for session {} — remote event stream now has a gap", sessionId);
        } else {
            log.debug("Relay remote buffer overflow for session {} — {} events dropped so far", sessionId, dropped);
        }
    }

    /**
     * Removes the oldest buffered frame that is droppable and ranks no higher than {@code incomingRank}, scanning the
     * ranks in ascending order so a reasoning delta is always sacrificed before a text delta.
     *
     * <p>
     * Two passes rather than one because the buffer is in arrival order, not rank order: a single pass would remove
     * whichever droppable frame is oldest, which for a mixed buffer is the wrong one about half the time.
     *
     * @param incomingRank
     *            the rank of the frame asking for room; nothing above it is sacrificed for it
     * @return {@code true} when a frame was removed and a slot is now free
     */
    private boolean discardOldestDroppable(int incomingRank) {
        for (int rank = RANK_REASONING; rank <= Math.min(incomingRank, RANK_STRUCTURAL - 1); rank++) {
            for (Iterator<AgentExecutionEvent> it = remoteBuffer.iterator(); it.hasNext();) {
                if (evictionRank(it.next()) == rank) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a frame may be sacrificed while a structural one is buffered. Kept as a name of its own because it is
     * the vocabulary the design and the drop-policy documentation use; the ordering among droppable frames is
     * {@link #evictionRank}'s.
     */
    private static boolean isDroppable(AgentExecutionEvent event) {
        return evictionRank(event) < RANK_STRUCTURAL;
    }

    /**
     * What a frame is worth when the buffer is full — lower is sacrificed first. See the class javadoc for the three
     * ranks and why reasoning ranks below answer text.
     */
    private static int evictionRank(AgentExecutionEvent event) {
        if (event instanceof AssistantReasoningDelta) {
            return RANK_REASONING;
        }
        if (event instanceof AssistantTextDelta) {
            return RANK_TEXT;
        }
        return RANK_STRUCTURAL;
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
