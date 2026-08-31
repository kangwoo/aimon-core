package at.aimon.bootstrap.runtime;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.scheduling.SchedulingEngine;

/**
 * Gives the scheduling engine a start and a stop that can each be called twice.
 *
 * <p>
 * The engine itself cannot. {@link SchedulingEngine#start()} starts a thread pool and
 * {@link SchedulingEngine#close()} shuts one down; neither guards against being called again, and once the stack
 * has two callers for each — a container lifecycle on one side and the ordered teardown on the other — being
 * called again is the normal case rather than a bug. A host that stops the context runs the lifecycle's stop and
 * then the stack's {@code close()}, and both mean the same thing.
 *
 * <p>
 * So this wrapper, and not a boolean in each caller: the state that decides whether stopping is still owed lives
 * next to the engine, so the two callers cannot disagree about it. The teardown registry holds <i>this</i> rather
 * than the engine, which is what makes "the lifecycle already stopped it" and "nobody did" the same code path.
 *
 * <h2>Why stop takes no timeout</h2>
 *
 * <p>
 * There is nothing to pass it to. {@code SchedulingEngine} exposes exactly {@code start()} and {@code close()},
 * and {@code close()} is {@code taskScheduler.shutdown(); routineExecutor.shutdown();} — each of those waits for
 * its own fixed period, and {@code TaskScheduler} has no pause or standby that would let a caller stop accepting
 * fires without also waiting for the running ones. A {@code stop(Duration)} here would take an argument it could
 * only ignore, which is worse than not offering one: a deployment would set it, see shutdown take longer, and
 * have no way to tell that the number never reached anything.
 */
public final class SchedulingLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulingLifecycle.class);

    private final SchedulingEngine engine;
    private boolean started;
    private boolean stopped;

    /**
     * Wraps an engine that has been built but not started.
     *
     * @param engine
     *            the engine to own the start/stop state of (must not be null)
     */
    public SchedulingLifecycle(SchedulingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    /**
     * Returns the wrapped engine.
     *
     * @return the engine, never null; borrowed, do not close — closing it directly is the one thing this class
     *         exists to prevent
     */
    public SchedulingEngine engine() {
        return engine;
    }

    /**
     * Starts the engine, unless it has already been started or stopped.
     *
     * <p>
     * Starting after a stop is refused rather than honoured: the engine's pools are gone by then, so a second
     * start would produce a scheduler that accepts registrations and never fires them.
     */
    public synchronized void start() {
        if (started || stopped) {
            return;
        }
        started = true;
        engine.start();
    }

    /**
     * Stops the engine if it is running, and does nothing otherwise.
     *
     * <p>
     * Also stops an engine that was never started, so a context that fails between assembly and lifecycle start
     * still releases the pools the engine built in its constructor.
     */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        engine.close();
    }

    /**
     * Returns whether {@link #start()} has run without a {@link #stop()} after it.
     *
     * @return {@code true} while the engine is running
     */
    public synchronized boolean isRunning() {
        return started && !stopped;
    }

    /**
     * Returns whether the engine has been stopped.
     *
     * <p>
     * Distinct from {@code !isRunning()}, which is also true before the first start. This one is the terminal
     * state: an engine that reads {@code true} here will never fire another schedule.
     *
     * @return {@code true} once {@link #stop()} has run
     */
    public synchronized boolean isStopped() {
        return stopped;
    }

    /** Same as {@link #stop()} — this is what the teardown registry calls. */
    @Override
    public void close() {
        final boolean wasRunning = isRunning();
        stop();
        if (!wasRunning) {
            log.debug("Scheduling engine was already stopped");
        }
    }

    @Override
    public String toString() {
        return "SchedulingLifecycle[started=" + started + ", stopped=" + stopped + "]";
    }
}
