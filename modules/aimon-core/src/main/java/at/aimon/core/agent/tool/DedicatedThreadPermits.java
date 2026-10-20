package at.aimon.core.agent.tool;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared runtime bound behind {@link DedicatedThreadDispatcher}: a semaphore of
 * {@link DedicatedThreadConfig#getMaxThreads()} permits and a factory for the threads they admit.
 *
 * <p>
 * One instance is meant to be shared by every dispatcher an executor factory builds (the main executor and the fork
 * executor), so the bound holds across sessions and fork depths. Permits are taken without blocking
 * ({@link #tryAcquire()}): a caller that finds none runs the call itself instead of waiting, so no thread ever waits
 * for a permit while holding one, and nested fan-out cannot deadlock.
 *
 * <p>
 * Nothing here needs closing — every thread is joined inside the dispatch call that started it, and a semaphore holds
 * no resource — so this is not {@link AutoCloseable}.
 */
public final class DedicatedThreadPermits {

    private final Semaphore permits;
    private final AtomicInteger counter = new AtomicInteger();
    private final ThreadFactory threadFactory;

    private DedicatedThreadPermits(int maxThreads) {
        this.permits = new Semaphore(maxThreads);
        this.threadFactory = this::newDaemonThread;
    }

    /**
     * Creates a permit set whose threads come from {@code threadFactory} — for tests that need thread creation to
     * fail.
     *
     * @param maxThreads
     *            the number of permits
     * @param threadFactory
     *            creates (does not start) each dedicated thread (must not be null)
     */
    DedicatedThreadPermits(int maxThreads, ThreadFactory threadFactory) {
        this.permits = new Semaphore(maxThreads);
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory cannot be null");
    }

    /**
     * @param config
     *            the configuration whose {@link DedicatedThreadConfig#getMaxThreads()} sizes the semaphore (must not be
     *            null)
     * @return a new permit set
     */
    public static DedicatedThreadPermits of(DedicatedThreadConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        return new DedicatedThreadPermits(config.getMaxThreads());
    }

    /**
     * @return {@code true} if a permit was taken; never blocks
     */
    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /** Returns a permit taken by {@link #tryAcquire()}. */
    void release() {
        permits.release();
    }

    /**
     * Creates — does not start — a new thread for {@code task}; by default a daemon thread named
     * {@code tool-dedicated-<n>}. Every call returns a fresh thread; none is pooled or reused.
     *
     * @param task
     *            the body of the thread (must not be null)
     * @return the unstarted thread
     */
    Thread newThread(Runnable task) {
        Objects.requireNonNull(task, "task cannot be null");
        return threadFactory.newThread(task);
    }

    private Thread newDaemonThread(Runnable task) {
        final Thread thread = new Thread(task, "tool-dedicated-" + counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /**
     * @return the number of permits currently free; for tests and diagnostics
     */
    int availablePermits() {
        return permits.availablePermits();
    }
}
