package at.aimon.workflow.graaljs;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

import at.aimon.core.agent.interrupt.CancellationSignal;

/**
 * Application-scoped holder for the shared, thread-safe GraalJS {@link Engine} plus the daemon executors that drive
 * per-run wall-clock watchdogs.
 *
 * <p>
 * The {@code Engine} is created once and shared across runs so JIT/warm-up cost is amortized; per-run
 * {@code Context}s bind to it but each stays single-threaded (§7.2). Watchdog timing runs on a single scheduler
 * thread; the potentially-blocking {@code Context.close(true)} calls run on a separate cached pool so one stuck
 * close can never delay another run's deadline. Constructed at module bootstrap and closed only at shutdown — never
 * per run.
 */
public final class GraalJsEngineHolder implements AutoCloseable {

    private final Engine engine;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService closer;

    private GraalJsEngineHolder(Engine engine, ScheduledExecutorService scheduler, ExecutorService closer) {
        this.engine = engine;
        this.scheduler = scheduler;
        this.closer = closer;
    }

    /**
     * Creates a holder with a shared engine (interpreter-only warning suppressed — workflow scripts are I/O-bound
     * glue), a single daemon watchdog-timing thread, and a cached daemon pool for blocking context closes.
     */
    public static GraalJsEngineHolder create() {
        final Engine engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build();
        final ScheduledExecutorService scheduler = Executors
                .newSingleThreadScheduledExecutor(daemonThreadFactory("graaljs-watchdog-"));
        final ExecutorService closer = Executors.newCachedThreadPool(daemonThreadFactory("graaljs-closer-"));
        return new GraalJsEngineHolder(engine, scheduler, closer);
    }

    /** The shared engine to bind per-run contexts to. */
    public Engine engine() {
        return engine;
    }

    /** Arms a watchdog for one run's context (owner thread). */
    CancellationWatchdog watchdog(Context context, CancellationSignal signal, Duration wallClock,
            Runnable deadlineAction) {
        return CancellationWatchdog.arm(context, signal, wallClock, scheduler, closer, deadlineAction);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        closer.shutdownNow();
        engine.close();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        final AtomicLong counter = new AtomicLong();
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
