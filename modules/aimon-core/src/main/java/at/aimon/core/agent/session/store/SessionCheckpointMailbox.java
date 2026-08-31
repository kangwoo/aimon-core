package at.aimon.core.agent.session.store;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;

/**
 * Application-scoped mailbox that keeps store latency off the ReAct thread: a mutation hands the session to a
 * single writer thread and returns immediately, and that thread persists the newest state it can see.
 *
 * <p>
 * The end-of-turn save only writes when a ReAct loop completes, so a crash during a long turn drops every message
 * appended since the turn began. The mailbox closes that gap without making the loop wait for the store.
 *
 * <h2>One slot per session, latest wins</h2>
 * <p>
 * A session has at most one pending checkpoint. {@link #checkpoint} marks the session as needing a write
 * (O(1), never blocks, never touches the store); every further mutation that arrives before the writer gets there
 * collapses into that same pending checkpoint. The snapshot is taken by the writer thread at write time, not by the
 * mutating thread at {@code checkpoint} time, so the write always carries the newest state rather than the state as of
 * some earlier queue position.
 *
 * <h2>Why there are no locks, tombstones or version counters here</h2>
 * <p>
 * Every write happens on one thread, so writes for a session are totally ordered by that thread's program order
 * and each one carries the newest state at the moment it ran: <b>an older snapshot can never overwrite a newer one</b>.
 * The predecessor of this class ({@code ScheduledConversationFlusher}) needed a per-session lock, an
 * {@code unregistered} tombstone and {@code lastFlushedVersion} bookkeeping because three different threads could
 * write — its worker, a {@code flushNow} caller and a {@code close} caller. Here {@link #flush} and {@link #close}
 * hand their work to the writer thread instead of doing it themselves, which is what makes that machinery unnecessary
 * rather than merely tidier.
 *
 * <h2>Write amplification is bounded by the store, not by a timer</h2>
 * <p>
 * There is no interval and no change threshold. A session is written as often as the store can keep up with:
 * a fast store writes close to once per mutation (cheap), and a slow store coalesces the mutations that pile up
 * during the write in flight. This is deliberately different from a fixed interval, which under-protects a fast store
 * (waiting when it need not) and over-writes a slow one (writing when it cannot keep up).
 *
 * <h2>Ordering against the authoritative end-of-turn write</h2>
 * <p>
 * {@link #flush} drains the pending checkpoint before returning, so the caller's own write cannot be overtaken by a
 * late background write of older state. It does not write anything itself — it enqueues a barrier behind the pending
 * checkpoint and waits for the writer thread to reach it.
 *
 * <h2>Transaction isolation</h2>
 * <p>
 * The write callback runs on the mailbox's writer thread and does <b>not</b> inherit a thread-bound transaction
 * context from the mutating thread (Spring {@code @Transactional}, JTA, ...). Each invocation must be commit-complete
 * on return; a transaction opened around a ReAct turn does not wrap these writes.
 */
public final class SessionCheckpointMailbox implements AutoCloseable {
    /**
     * How long {@link #flush} waits for the writer thread to reach its barrier, and how long {@link #close} waits for
     * the writer thread to finish draining. Both are bounds on an already-degenerate situation (a store call that
     * never returns), not a normal-path timeout.
     */
    private static final long DRAIN_TIMEOUT_SECONDS = 5L;

    /**
     * How often a {@link #flush} caller re-checks that the writer thread is still alive while waiting for its barrier.
     * A barrier queued behind a thread that has exited is never released, so the wait has to be interruptible by that
     * fact rather than only by the timeout.
     */
    private static final long DRAIN_POLL_MILLIS = 50L;

    private static final Logger log = LoggerFactory.getLogger(SessionCheckpointMailbox.class);

    /** Sentinel that tells the writer thread to drain what is left and exit. */
    private static final Runnable POISON = () -> {
    };

    private final ConcurrentMap<SessionId, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * The session whose write is inside the store callback right now, or null between writes. Assigned only by the
     * writer thread, so no compare-and-set is needed; {@code volatile} because {@link #close} reads it from the
     * closing thread while the writer is parked in that callback.
     */
    private volatile SessionId inFlightWrite;

    /** Null when checkpointing is disabled — the only two fields that differ between the two modes. */
    private final BlockingQueue<Runnable> queue;
    private final Thread writer;

    private SessionCheckpointMailbox(boolean enabled) {
        if (!enabled) {
            this.queue = null;
            this.writer = null;
            return;
        }
        this.queue = new LinkedBlockingQueue<>();
        this.writer = new Thread(this::runWriter, "aimon-session-checkpoint");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Creates a mailbox with a single daemon writer thread. This is the wiring that gives long turns mid-turn
     * durability.
     *
     * @return a started mailbox (never null)
     */
    public static SessionCheckpointMailbox background() {
        return new SessionCheckpointMailbox(true);
    }

    /**
     * Creates a mailbox that drops every checkpoint and owns no thread. Sessions are then persisted only by the
     * end-of-turn save — the behaviour of a manager constructed without a mailbox, and what tests want when they are
     * not exercising checkpointing.
     *
     * @return a mailbox that never writes (never null)
     */
    public static SessionCheckpointMailbox disabled() {
        return new SessionCheckpointMailbox(false);
    }

    /**
     * Records that {@code memory} has state worth persisting. Returns immediately; the write happens later on the
     * writer thread.
     *
     * <p>
     * Safe to call from a {@link TranscriptBuffer.DirtyListener}, i.e. while holding the memory's monitor: this
     * method never blocks on a lock the writer thread can hold.
     *
     * <p>
     * Calling {@code checkpoint} for an id whose previous checkpoint was raised against a <em>different</em>
     * {@link TranscriptBuffer} instance supersedes that one — a memory reloaded for a new turn does not inherit the
     * previous memory's pending write. After {@link #close}, this is a silent no-op.
     *
     * @param memory
     *            the memory to persist (must not be null)
     * @param write
     *            the callback the writer thread invokes with a fresh snapshot; it must not throw, and a throw — an
     *            {@code Exception} or an {@code Error} — is logged rather than allowed to kill the writer thread
     *            (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public void checkpoint(TranscriptBuffer memory, Consumer<SessionSnapshot> write) {
        Objects.requireNonNull(memory, "memory cannot be null");
        Objects.requireNonNull(write, "write cannot be null");
        if (queue == null || closed.get()) {
            return;
        }
        // Bind the slot to the memory instance: a slot left behind by a previous turn's memory must not be the thing
        // this mutation enqueues, or the writer would persist the older object's state.
        final Slot slot = slots.compute(memory.getSessionId(),
                (id, current) -> current != null && current.memory == memory ? current : new Slot(memory, write));
        if (slot.queued.compareAndSet(false, true)) {
            queue.offer(() -> writeSlot(slot));
        }
    }

    /**
     * Drains the pending checkpoint for {@code sessionId}, if any, and returns once it has been written.
     *
     * <p>
     * Call this before an authoritative write of the same session (end of turn, or a mutation made outside a
     * turn): once it returns, no background write of older state is still in flight, so the authoritative write cannot
     * be overtaken. A checkpoint raised <em>after</em> this call is not covered — that would be a mutation concurrent
     * with the authoritative write, which is a concurrent-turn bug of its own.
     *
     * <p>
     * No-op when the session has no pending checkpoint or the mailbox is disabled. If the writer thread does not
     * reach the barrier within {@value #DRAIN_TIMEOUT_SECONDS} seconds (a store call that never returns), this logs and
     * gives up rather than blocking the ReAct thread forever. If the caller is interrupted while waiting, the interrupt
     * flag is restored and the method returns.
     *
     * <p>
     * After {@link #close} this returns immediately instead of waiting: no live writer will ever reach a barrier
     * offered then, so the wait could only stall the caller for the full timeout without buying it any ordering.
     *
     * @param sessionId
     *            the session to drain (must not be null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    public void flush(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        if (queue == null) {
            return;
        }
        if (closed.get()) {
            // Nothing will ever reach a barrier offered now: the writer thread has either finished draining or been
            // abandoned inside a store call that never returned, and no new checkpoint can be raised either. Waiting
            // out the timeout would stall the caller's authoritative write for DRAIN_TIMEOUT_SECONDS and tell it
            // nothing — and that write cannot be overtaken by older state anyway, because a checkpoint an abandoned
            // writer eventually completes snapshots the same memory at write time, not at checkpoint time.
            log.debug("Checkpoint drain for {} skipped: the mailbox is closed", sessionId.value());
            return;
        }
        final Slot slot = slots.get(sessionId);
        if (slot == null) {
            return;
        }
        final CountDownLatch barrier = new CountDownLatch(1);
        queue.offer(barrier::countDown);
        try {
            if (!awaitBarrier(barrier)) {
                if (writer.isAlive()) {
                    log.warn("Checkpoint drain for {} did not complete within {}s", sessionId.value(),
                            DRAIN_TIMEOUT_SECONDS);
                } else {
                    // close() raced this call and won: the writer exited before taking our barrier, so nothing of this
                    // session is in flight either. Not a warning — the caller's write is still the last one.
                    log.debug("Checkpoint drain for {} skipped: the writer thread has stopped", sessionId.value());
                }
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // Drop the slot so an idle session stops holding its memory. A checkpoint that arrives after the barrier
        // installs a fresh slot; one that is already queued against this slot becomes a no-op, which is what the
        // authoritative write about to happen makes correct.
        slots.remove(sessionId, slot);
    }

    /**
     * Stops accepting checkpoints, lets the writer thread persist what is already pending, and releases the thread.
     * Idempotent.
     *
     * <p>
     * If the drain does not finish within {@value #DRAIN_TIMEOUT_SECONDS} seconds the writer is abandoned rather than
     * interrupted, and the checkpoints it never reached are <b>kept</b> — {@link #pendingCheckpointSessionIds} names
     * them, together with the session whose write the abandoned thread is parked inside. Discarding them here would
     * silently drop exactly the writes this timeout branch exists to bound.
     */
    @Override
    public void close() {
        if (queue == null || !closed.compareAndSet(false, true)) {
            return;
        }
        queue.offer(POISON);
        try {
            writer.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (writer.isAlive()) {
            // Leave the slots alone. They are the checkpoints the abandoned drain never reached, and clearing them
            // would turn each one into a silent no-op — the data loss this branch is supposed to bound, not cause.
            // The writer is a daemon stuck in a store call: if that call returns it still drains its queue against
            // these slots, and if it never returns the JVM takes the thread down anyway. Naming the sessions here
            // (rather than emptying the map) is what makes the loss diagnosable afterwards — including the session
            // whose write the thread is parked inside, which no longer counts as queued but is the one write we know
            // did not complete.
            final Set<SessionId> pending = pendingCheckpointSessionIds();
            log.warn("Checkpoint writer did not finish draining within {}s; abandoning it with {} unwritten"
                    + " checkpoint(s): {}", DRAIN_TIMEOUT_SECONDS, pending.size(), pending);
        }
    }

    /**
     * The sessions whose latest state is not known to be persisted: the one whose write is in flight, if any, followed
     * by those still queued behind it.
     *
     * <p>
     * The in-flight session is listed first and is the reason this is not simply the queued set. {@link #writeSlot}
     * clears the queued mark <em>before</em> it calls the store, so a session being written is marked as neither
     * queued nor durable — and that is exactly the state a writer abandoned inside a store call is frozen in. Counting
     * only the queued ones would omit the one session the stuck writer is stuck on, i.e. the likeliest loss, from the
     * list of losses.
     *
     * <p>
     * Empty whenever the writer is caught up, so during normal operation a reader sees at most whatever is passing
     * through at that instant. It is meaningfully non-empty after {@link #close} when that drain timed out and the
     * writer was abandoned: read then, it tells a caller which sessions may have lost their most recent mid-turn state.
     *
     * @return an immutable snapshot of the pending session ids (never null)
     */
    public Set<SessionId> pendingCheckpointSessionIds() {
        final Set<SessionId> pending = new LinkedHashSet<>();
        final SessionId inFlight = inFlightWrite;
        if (inFlight != null) {
            pending.add(inFlight);
        }
        slots.forEach((sessionId, slot) -> {
            if (slot.queued.get()) {
                // A set, so a session that re-queued during its own write is named once, and keeps the leading
                // position its in-flight write earned it.
                pending.add(sessionId);
            }
        });
        return Collections.unmodifiableSet(pending);
    }

    /**
     * Waits for the writer thread to reach {@code barrier}, giving up as soon as that thread is gone. A barrier queued
     * behind a writer that has exited is never released, so a plain timed await would burn the whole timeout for a
     * result already known.
     */
    private boolean awaitBarrier(CountDownLatch barrier) throws InterruptedException {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(DRAIN_TIMEOUT_SECONDS);
        while (!barrier.await(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (!writer.isAlive()) {
                // Nobody else can count the barrier down now, so its count is a final answer: the writer may have
                // released it on its way out (close() drains what is queued before exiting).
                return barrier.getCount() == 0L;
            }
            if (System.nanoTime() - deadlineNanos >= 0L) {
                return false;
            }
        }
        return true;
    }

    private void runWriter() {
        try {
            while (true) {
                final Runnable task = queue.take();
                if (task == POISON) {
                    break;
                }
                run(task);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Drain on the writer thread rather than the closing thread, so the single-writer ordering holds to the end and
        // any flush() barrier still queued is released instead of timing out.
        Runnable remaining;
        while ((remaining = queue.poll()) != null) {
            if (remaining != POISON) {
                run(remaining);
            }
        }
    }

    private void run(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            // A throw here would kill the writer thread and silently end checkpointing for every session.
            log.warn("Checkpoint task failed: {}", e.getMessage());
        } catch (Throwable t) {
            // Same reason one level up, which is why the flusher this class replaced also caught Throwable: an Error
            // out of the store (NoClassDefFoundError from a driver, an assertion in a test double, OOM while
            // snapshotting) must not take the thread down either. A dead writer would leave every later flush()
            // barrier unlifted, so each subsequent save would pay the full drain timeout, forever. The slot needs no
            // repair — writeSlot clears 'queued' before it calls the store, so the next mutation re-queues normally.
            log.error("Checkpoint task threw an error; the writer thread continues", t);
        }
    }

    private void writeSlot(Slot slot) {
        final SessionId sessionId = slot.memory.getSessionId();
        if (slots.get(sessionId) != slot) {
            // Superseded by a newer memory for the same id, or dropped by flush().
            return;
        }
        // Clear before snapshotting: a mutation that lands during the write re-queues, at the cost of one redundant
        // write. Clearing after would lose it.
        slot.queued.set(false);
        final SessionSnapshot snapshot = slot.memory.toSnapshot();
        // Publish the session for the length of the store call. Between clearing 'queued' above and the callback
        // returning, this is the only record that the session has unpersisted state — and that window is precisely
        // where an abandoned writer is stuck, so close() has to be able to name it.
        inFlightWrite = sessionId;
        try {
            slot.write.accept(snapshot);
        } catch (Exception e) {
            log.warn("Checkpoint write failed for {}: {}", sessionId.value(), e.getMessage());
        } finally {
            // finally, not after the catch: run() also swallows Errors, and one thrown past this point would otherwise
            // leave the session named as in flight for the rest of the writer's life.
            inFlightWrite = null;
        }
        if (log.isDebugEnabled()) {
            log.debug("Checkpointed session {}", sessionId.value());
        }
    }

    private static final class Slot {
        final TranscriptBuffer memory;
        final Consumer<SessionSnapshot> write;
        final AtomicBoolean queued = new AtomicBoolean(false);

        Slot(TranscriptBuffer memory, Consumer<SessionSnapshot> write) {
            this.memory = memory;
            this.write = write;
        }
    }
}
