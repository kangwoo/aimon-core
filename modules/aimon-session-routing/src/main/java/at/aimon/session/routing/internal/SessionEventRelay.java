package at.aimon.session.routing.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
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
 * fan-out is decoupled via a bounded {@link ArrayBlockingQueue} drained by the manager-owned dispatcher, so the bus's
 * publish latency can never stall the turn.
 *
 * <p>
 * <b>The bus is not assumed to be fast.</b> A publish may be a pub/sub write or it may be a document insert, and a
 * model emits deltas far faster than a database acknowledges inserts. Two things follow:
 *
 * <ul>
 * <li><b>The drain is batched.</b> Whatever has accumulated — up to {@link #MAX_BATCH} frames — goes out in one
 * {@link SessionSignalBus#publishAll}, so a backlog costs round trips in proportion to its size divided by the batch,
 * not to its size.</li>
 * <li><b>One batch per dispatcher task, one task at a time.</b> A relay never has two drains in flight, so batches
 * cannot leave out of order and a relay never parks a second dispatcher thread behind its first. After each batch it
 * goes to the back of the dispatcher's queue, so relays with a backlog take turns instead of one holding a thread
 * for as long as its model keeps streaming.</li>
 * <li><b>{@link #close()} is bounded.</b> It runs on the turn thread, between the end of the execution and the
 * announcement of its result, so what it waits for is what the caller waits for. It gives the remote channel
 * {@code closeDrainTimeout} to catch up and then stops waiting: the deltas still buffered are abandoned, and the
 * structural frames are left for the dispatcher to publish. The turn's result is never held behind the best-effort
 * rail.</li>
 * <li><b>A turn's frames never land inside the next turn's.</b> A relay that was left with frames at its deadline is
 * handed to the next turn's relay through {@link #after}, and the successor publishes nothing until the
 * predecessor has nothing left. Receivers read {@code EVENT} per session, not per turn, and one that saw turn N's
 * terminal frame in the middle of turn N+1 would end the wrong stream.</li>
 * </ul>
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
 * ({@link #getDroppedEventCount()}) and reported at {@link #close()} so a gap is never silent. The deltas
 * {@code close()} abandons at its deadline follow the same ranking — structural frames are the ones kept — and are
 * counted the same way.
 */
public final class SessionEventRelay implements Consumer<AgentExecutionEvent>, AutoCloseable {

    /** Bounded queue capacity per relay — design §5.5 recommended size. */
    public static final int RELAY_QUEUE_CAPACITY = 1024;

    /**
     * Most frames one {@link SessionSignalBus#publishAll} carries. Large enough that a full buffer is four round trips,
     * small enough that one batch is not a multi-megabyte write.
     */
    public static final int MAX_BATCH = 256;

    /** How long {@link #close()} waits for the remote channel by default. */
    public static final Duration DEFAULT_CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(2);

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
    private final Duration closeDrainTimeout;
    private final BlockingQueue<AgentExecutionEvent> remoteBuffer;
    private final AtomicLong droppedEvents = new AtomicLong();

    /**
     * Work-in-progress count: non-zero while a drain task is queued or running (or held back behind a predecessor),
     * incremented by everything that wants the buffer drained. Only the transition from zero queues a task, which is
     * what keeps a relay to one drain at a time.
     */
    private final AtomicInteger wip = new AtomicInteger();

    /**
     * Held while a batch is polled and published. Uncontended in normal running — {@link #wip} already admits one
     * drain at a time — and there for the one case it does not cover: {@link #close()} draining on the calling thread
     * after the dispatcher has shut down, while a task it interrupted may still be publishing.
     */
    private final ReentrantLock publishLock = new ReentrantLock();

    /** Completed once this relay is closed and has nothing left to publish. */
    private final CompletableFuture<Void> quiescent = new CompletableFuture<>();

    private volatile boolean closed;

    /** Set once the dispatcher has refused a task: from then on only {@link #close()} can drain. */
    private volatile boolean dispatcherGone;

    /**
     * A relay with no predecessor and {@link #DEFAULT_CLOSE_DRAIN_TIMEOUT}.
     *
     * @param sessionId
     *            the session whose events this relay carries (must not be null)
     * @param turnId
     *            the turn this relay is dedicated to (must not be null)
     * @param localPublisher
     *            the in-process publisher receiving the local fan-out (must not be null)
     * @param signalBus
     *            the cross-node bus receiving the remote fan-out (must not be null)
     * @param originNodeId
     *            this node's id (must not be null)
     * @param dispatcher
     *            the manager-owned executor that drains the remote buffer off the turn thread (must not be null)
     */
    public SessionEventRelay(SessionId sessionId, TurnId turnId, InProcessEventPublisher localPublisher,
            SessionSignalBus signalBus, String originNodeId, ExecutorService dispatcher) {
        this(sessionId, turnId, localPublisher, signalBus, originNodeId, dispatcher, DEFAULT_CLOSE_DRAIN_TIMEOUT);
    }

    /**
     * @param sessionId
     *            the session whose events this relay carries (must not be null)
     * @param turnId
     *            the turn this relay is dedicated to; stamped onto every {@code EVENT} payload it publishes (must not
     *            be null). One relay serves exactly one turn — reusing a relay across turns would attribute the second
     *            turn's frames to the first.
     * @param localPublisher
     *            the in-process publisher receiving the local fan-out (must not be null)
     * @param signalBus
     *            the cross-node bus receiving the remote fan-out (must not be null)
     * @param originNodeId
     *            this node's id, so receivers can tell self-originated signals apart (must not be null)
     * @param dispatcher
     *            the manager-owned executor that drains the remote buffer off the turn thread (must not be null)
     * @param closeDrainTimeout
     *            how long {@link #close()} waits for the remote channel to catch up before it abandons the buffered
     *            deltas (must not be null or negative; zero means do not wait)
     */
    public SessionEventRelay(SessionId sessionId, TurnId turnId, InProcessEventPublisher localPublisher,
            SessionSignalBus signalBus, String originNodeId, ExecutorService dispatcher, Duration closeDrainTimeout) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.turnId = Objects.requireNonNull(turnId, "turnId must not be null");
        this.localPublisher = Objects.requireNonNull(localPublisher, "localPublisher must not be null");
        this.signalBus = Objects.requireNonNull(signalBus, "signalBus must not be null");
        this.originNodeId = Objects.requireNonNull(originNodeId, "originNodeId must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.closeDrainTimeout = Objects.requireNonNull(closeDrainTimeout, "closeDrainTimeout must not be null");
        if (closeDrainTimeout.isNegative()) {
            throw new IllegalArgumentException("closeDrainTimeout must not be negative: " + closeDrainTimeout);
        }
        this.remoteBuffer = new ArrayBlockingQueue<>(RELAY_QUEUE_CAPACITY);
    }

    /**
     * Makes this relay publish nothing until {@code predecessor} — the relay of the same session's previous turn —
     * has nothing left to publish, so the two turns' frames cannot interleave. Must be called before the first
     * {@link #accept}.
     *
     * @param predecessor
     *            the previous turn's relay, or {@code null} for none
     * @return this relay
     * @throws IllegalStateException
     *             if this relay has already been given a frame
     */
    public SessionEventRelay after(SessionEventRelay predecessor) {
        if (predecessor == null || predecessor.quiescent.isDone()) {
            return this;
        }
        // Held as if a drain were already in flight: frames accumulate, nothing is queued, and the predecessor's last
        // batch is what queues this relay's first.
        if (!wip.compareAndSet(0, 1)) {
            throw new IllegalStateException("after() must be called before the relay is given its first frame");
        }
        predecessor.quiescent.thenRun(this::queueDrain);
        return this;
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
        if (wip.getAndIncrement() == 0) {
            queueDrain();
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
     * Returns the number of events this relay kept off the remote channel: those dropped because the remote buffer was
     * full, and the deltas {@link #close()} abandoned at its deadline.
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

    /**
     * Runs {@code action} once this relay is closed and has published everything it is going to — at once if that is
     * already so. The action runs on whichever thread published the last batch and must not block.
     *
     * @param action
     *            what to run (must not be null)
     */
    public void whenQuiescent(Runnable action) {
        quiescent.thenRun(Objects.requireNonNull(action, "action must not be null"));
    }

    /**
     * Queues one {@link #drainStep} on the dispatcher. Called only by whoever holds the drain — the caller that took
     * {@link #wip} from zero, the step that is handing over to the next one, or the predecessor releasing this relay.
     *
     * @return {@code false} when the dispatcher refused the task
     */
    private boolean queueDrain() {
        try {
            dispatcher.execute(this::drainStep);
            return true;
        } catch (RejectedExecutionException e) {
            // Shutting down. The frames stay buffered for close(), which drains on its own thread — and if close()
            // has already been and gone, this is the last chance they get.
            dispatcherGone = true;
            if (closed) {
                drainOnThisThread();
            }
            return false;
        }
    }

    /**
     * Publishes one batch and then either goes to the back of the dispatcher's queue or lets go of the drain. One
     * batch and not the whole buffer: on a bus slower than the model the buffer is never empty, and a loop here would
     * keep this thread from every other relay for as long as the turn streams.
     */
    private void drainStep() {
        final int seen = wip.get();
        publishBatch();
        if (!remoteBuffer.isEmpty() || wip.addAndGet(-seen) != 0) {
            // More buffered, or something asked for a drain while this one ran. wip stays non-zero, so nobody else
            // queues a task in between.
            queueDrain();
            return;
        }
        if (closed) {
            quiescent.complete(null);
        }
        // Not closed, or closed a moment after that read: close() then finds wip at zero and queues the step that
        // completes it.
    }

    private void publishBatch() {
        publishLock.lock();
        try {
            final List<SessionSignal> batch = new ArrayList<>();
            AgentExecutionEvent next;
            while (batch.size() < MAX_BATCH && (next = remoteBuffer.poll()) != null) {
                final Map<String, Object> payload = AgentExecutionEventPayload.toPayload(next, turnId);
                if (payload == null) {
                    // Unrecognized event subtype — skip cross-node relay; local delivery already happened in accept().
                    continue;
                }
                batch.add(SessionSignal.builder().sessionId(sessionId).kind(SessionSignal.SignalKind.EVENT)
                        .originNodeId(originNodeId).payload(payload).build());
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                signalBus.publishAll(batch);
            } catch (Exception e) {
                // The bus attempts every signal before it reports, so this is the signals it could not publish and
                // not necessarily the batch.
                log.warn("SignalBus EVENT publish failed for session {} in a batch of {}: {}", sessionId, batch.size(),
                        e.toString());
            }
        } finally {
            publishLock.unlock();
        }
    }

    /** The drain of last resort, for when there is no dispatcher to hand it to. */
    private void drainOnThisThread() {
        while (!remoteBuffer.isEmpty()) {
            publishBatch();
        }
        quiescent.complete(null);
    }

    /**
     * Gives the remote channel {@code closeDrainTimeout} to catch up, then returns whether or not it has. Called by the
     * manager when the turn ends, on the turn thread, before the turn's result is announced.
     *
     * <p>
     * The publishing stays on the dispatcher, which is what makes the bound real: a publish that is slow, or never
     * returns, is not on this thread. When the wait runs out the buffered deltas are abandoned and counted, and the
     * structural frames stay buffered for the dispatcher — so a remote subscriber still learns the turn ended,
     * possibly after the {@code TURN_RESULT} that this method was in the way of. That holds while the dispatcher
     * runs; frames left behind when the router shuts down are lost with it.
     *
     * <p>
     * Once the dispatcher has shut down there is no other thread to publish on, and the drain runs here, unbounded, as
     * it always has.
     */
    @Override
    public void close() {
        closed = true;
        final boolean handedOff = !dispatcherGone && (wip.getAndIncrement() != 0 || queueDrain());
        if (handedOff) {
            awaitQuiescence();
        } else {
            drainOnThisThread();
        }
        final long dropped = droppedEvents.get();
        if (dropped > 0) {
            log.warn("Relay for session {} dropped {} event(s) on the remote channel this turn", sessionId, dropped);
        }
    }

    private void awaitQuiescence() {
        String gaveUpBecause;
        try {
            quiescent.get(closeDrainTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return;
        } catch (TimeoutException e) {
            gaveUpBecause = "it had not caught up after " + closeDrainTimeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            gaveUpBecause = "the turn thread was interrupted";
        } catch (ExecutionException e) {
            // quiescent is only ever completed normally
            return;
        }
        int abandoned = 0;
        for (Iterator<AgentExecutionEvent> it = remoteBuffer.iterator(); it.hasNext();) {
            if (isDroppable(it.next())) {
                it.remove();
                abandoned++;
            }
        }
        droppedEvents.addAndGet(abandoned);
        log.warn(
                "Relay for session {} stopped waiting for the remote channel because {} — abandoned {} buffered"
                        + " delta(s); the structural frames still buffered ({}) are left to the dispatcher",
                sessionId, gaveUpBecause, abandoned, remoteBuffer.size());
    }
}
