package at.aimon.core.memory.deriver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.redaction.MessageRedactor;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * In-memory {@link DerivationQueueManager} backed by a fixed worker pool and a
 * single shared ready queue.
 *
 * <p>
 * Enforces both invariants documented on {@link DerivationQueueManager}:
 * <ul>
 * <li>Redaction runs inside {@link #enqueue(DerivationTask)} via the shared
 * {@link MessageRedactor} on every text fragment of every message (content
 * blocks <em>and</em> tool-result content, all roles); the resulting
 * {@link DerivationTask} carries only post-redaction content. There is no path
 * that reaches a worker without going through this gate.</li>
 * <li>Per-work-unit serialization is implemented by an
 * {@link WorkUnitState active-flag + pending queue} held under a per-state
 * monitor. The first worker that observes an idle state claims the unit and
 * drains its pending queue inline — concurrent workers handling later tasks
 * for the same unit append to the pending queue and yield.</li>
 * </ul>
 *
 * <p>
 * This implementation is the dev/test default. Persistent backends (Postgres,
 * etc.) are added in stage 5 of the memory roadmap.
 */
public final class InMemoryDerivationQueueManager implements DerivationQueueManager {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDerivationQueueManager.class);

    private static final long DEFAULT_DRAIN_TIMEOUT_SECONDS = 30L;

    private final Deriver deriver;
    private final MessageRedactor messageRedactor;
    private final DeriverProperties properties;
    private final int tokenBudget;

    private final LinkedBlockingQueue<DerivationTask> readyQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<DerivationWorkUnit, WorkUnitState> states = new ConcurrentHashMap<>();

    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private ExecutorService executor;

    public InMemoryDerivationQueueManager(Deriver deriver, RedactionPolicy redactionPolicy,
            DeriverProperties properties) {
        this.deriver = Objects.requireNonNull(deriver, "deriver cannot be null");
        this.messageRedactor = new MessageRedactor(
                Objects.requireNonNull(redactionPolicy, "redactionPolicy cannot be null"));
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.tokenBudget = properties.getBatchMaxTokens();
    }

    @Override
    public void enqueue(DerivationTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        if (stopping.get()) {
            throw new IllegalStateException("queue manager is stopped");
        }
        DerivationTask redacted = task.withMessages(messageRedactor.redactAll(task.getMessages()));
        readyQueue.offer(redacted);
        log.info("Enqueued task: workUnit={}, queueSize={}", redacted.workUnit(), readyQueue.size());
    }

    @Override
    public synchronized void start() {
        if (started.get()) {
            return;
        }
        if (stopping.get()) {
            throw new IllegalStateException("cannot start a stopped manager");
        }
        int workerCount = properties.getWorkerCount();
        executor = Executors.newFixedThreadPool(workerCount, new WorkerThreadFactory());
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
        started.set(true);
        log.info("Started derivation queue with {} workers", workerCount);
    }

    @Override
    public synchronized void stop() {
        if (!started.get() || stopping.get()) {
            return;
        }
        stopping.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DEFAULT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Drain timeout exceeded; forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("Stopped derivation queue. completed={}, failed={}", completedTasks.get(), failedTasks.get());
    }

    @Override
    public QueueStats stats() {
        int pending = 0;
        for (WorkUnitState state : states.values()) {
            synchronized (state) {
                pending += state.pending.size();
            }
        }
        return QueueStats.of(readyQueue.size() + pending, activeWorkers.get(), completedTasks.get(), failedTasks.get());
    }

    private void workerLoop() {
        long pollMs = properties.getPollInterval().toMillis();
        while (true) {
            try {
                DerivationTask task = readyQueue.poll(pollMs, TimeUnit.MILLISECONDS);
                if (task != null) {
                    handleTask(task);
                    continue;
                }
                if (stopping.get() && readyQueue.isEmpty() && noPendingWork()) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("Unexpected worker error: {}", e.getMessage(), e);
            }
        }
    }

    private boolean noPendingWork() {
        for (WorkUnitState state : states.values()) {
            synchronized (state) {
                if (state.active || !state.pending.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void handleTask(DerivationTask task) {
        DerivationWorkUnit unit = task.workUnit();
        // Claim loop. A state that goes idle is removed from the map (see below) to keep `states`
        // from growing without bound, so a concurrent drain may remove the instance between our
        // computeIfAbsent and acquiring its monitor. Re-verify the map still points at the same
        // instance before trusting it; otherwise recompute. This preserves the per-work-unit
        // serialization invariant — a removed state is never offered to or claimed twice.
        WorkUnitState state;
        while (true) {
            state = states.computeIfAbsent(unit, k -> new WorkUnitState());
            synchronized (state) {
                if (states.get(unit) != state) {
                    continue;
                }
                if (state.active) {
                    state.pending.offer(task);
                    return;
                }
                state.active = true;
            }
            break;
        }
        DerivationTask current = task;
        try {
            while (current != null) {
                runDerive(current);
                synchronized (state) {
                    current = state.pending.poll();
                    if (current == null) {
                        state.active = false;
                        // Idle and empty: drop the entry so the map is bounded by in-flight units,
                        // not by lifetime (workspace, session, observer) cardinality. Remove only if
                        // the mapping still points at this exact instance.
                        states.remove(unit, state);
                    }
                }
            }
        } catch (Throwable t) {
            // Defensive: ensure the active flag is cleared and the entry dropped even on error.
            synchronized (state) {
                state.active = false;
                states.remove(unit, state);
            }
            throw t;
        }
    }

    private void runDerive(DerivationTask task) {
        activeWorkers.incrementAndGet();
        try {
            DerivationContext ctx = DerivationContext.builder().workspace(task.getWorkspace())
                    .sessionId(task.getSessionId()).observer(task.getObserver()).messages(task.getMessages())
                    .tokenBudget(tokenBudget).build();
            try {
                DerivationResult result = deriver.derive(ctx);
                completedTasks.incrementAndGet();
                log.info("Derivation succeeded: workUnit={}, observations={}, tokens={}", task.workUnit(),
                        result.totalObservations(), result.getLlmTokensUsed());
            } catch (RuntimeException e) {
                failedTasks.incrementAndGet();
                log.error("Derivation failed for {}: {}", task.workUnit(), e.getMessage(), e);
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    /** Per-work-unit state guarded by its own monitor. */
    private static final class WorkUnitState {
        private boolean active;
        private final Deque<DerivationTask> pending = new ArrayDeque<>();
    }

    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "derivation-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
