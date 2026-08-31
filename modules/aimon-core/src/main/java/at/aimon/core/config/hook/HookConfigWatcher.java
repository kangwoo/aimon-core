package at.aimon.core.config.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches one or more {@code hooks.json} files for modification and fires a debounced reload callback.
 *
 * <p>
 * <b>Detection model.</b> Each polling tick (default 1s) compares the current
 * {@link Files#getLastModifiedTime last-modified time} (and existence) of every watched file against the previously
 * observed snapshot. Any difference — file appeared, disappeared, or was modified — counts as an event. This polling
 * approach is portable and avoids the macOS-specific latency of {@code java.nio.file.WatchService} (which itself uses
 * polling on Mac with a much higher default period).
 *
 * <p>
 * <b>Debounce.</b> Multiple events within a short window collapse into a single reload. Each detected event reschedules
 * the reload {@code debounce} into the future (default 2s); the callback fires once {@code debounce} has elapsed
 * without further events. This absorbs editor-write bursts (e.g. {@code vim} rename-and-replace, multi-file save).
 *
 * <p>
 * <b>Re-entrancy guard.</b> A {@link AtomicLong reload counter} is provided to callers (typically used to populate
 * {@code OnConfigReloadContext.reloadCounter}). It is incremented exactly once per debounced reload invocation,
 * monotonic across the lifetime of the watcher.
 *
 * <p>
 * <b>Failure handling.</b> If the reload callback throws, the exception is caught and logged at WARN level so a
 * misbehaving callback does not poison the watcher thread. Subsequent events still trigger reloads.
 *
 * <p>
 * <b>Lifecycle.</b> Construct → {@link #start()} once → events fire reloads → {@link #close()} stops the polling
 * thread (idempotent). The watcher is safe to use as a try-with-resources or to register with a shutdown hook.
 *
 * <p>
 * <b>Thread safety.</b> All public methods are thread-safe. The reload callback is invoked from the watcher's
 * scheduled-executor thread and must itself be thread-safe with respect to the registry it mutates.
 */
public final class HookConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HookConfigWatcher.class);

    /** Default debounce window: 2 seconds (per design plan). */
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(2);

    /** Default polling interval: 1 second (per design plan, R2). */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    private final List<Path> watchedFiles;
    private final ReloadListener reloadListener;
    private final Duration debounce;
    private final Duration pollInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private final Map<Path, FileTime> lastModified = new HashMap<>();
    private final Map<Path, Boolean> lastExisted = new HashMap<>();
    private final AtomicReference<ScheduledFuture<?>> pendingReload = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> pollTask = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong reloadCounter = new AtomicLong();

    /**
     * Reload listener invoked after the debounce window expires.
     *
     * <p>
     * Receives the monotonic reload counter (1 for the first reload, 2 for the second, …) and the path that triggered
     * the most recent event. Implementations should be thread-safe and short-running; long-running reload work should
     * be performed on a separate executor to keep the watcher's polling thread responsive.
     */
    @FunctionalInterface
    public interface ReloadListener {
        /**
         * Invoked after a debounced burst of file events.
         *
         * @param reloadCounter
         *            monotonic count starting at 1
         * @param triggeringPath
         *            the most recent path that fired an event (never null)
         */
        void onReload(long reloadCounter, Path triggeringPath);
    }

    /**
     * Convenience constructor using the default debounce and polling interval and an internal single-thread scheduler.
     *
     * @param watchedFiles
     *            files to watch (must not be null or empty)
     * @param reloadListener
     *            listener fired on debounced reloads (must not be null)
     */
    public HookConfigWatcher(Collection<Path> watchedFiles, ReloadListener reloadListener) {
        this(watchedFiles, reloadListener, DEFAULT_DEBOUNCE, DEFAULT_POLL_INTERVAL, null);
    }

    /**
     * Full constructor for advanced configuration / testing.
     *
     * @param watchedFiles
     *            files to watch (must not be null or empty); paths that do not exist at construction time are still
     *            tracked — their first appearance counts as an event
     * @param reloadListener
     *            listener fired on debounced reloads (must not be null)
     * @param debounce
     *            debounce window (must not be null; must be positive)
     * @param pollInterval
     *            polling interval (must not be null; must be positive)
     * @param scheduler
     *            optional caller-provided scheduler. When {@code null}, the watcher creates and owns an internal
     *            single-thread scheduler that is shut down by {@link #close()}. When non-null, the caller retains
     *            ownership and must shut it down independently.
     */
    public HookConfigWatcher(Collection<Path> watchedFiles, ReloadListener reloadListener, Duration debounce,
            Duration pollInterval, ScheduledExecutorService scheduler) {
        Objects.requireNonNull(watchedFiles, "watchedFiles cannot be null");
        if (watchedFiles.isEmpty()) {
            throw new IllegalArgumentException("watchedFiles cannot be empty");
        }
        for (Path path : watchedFiles) {
            Objects.requireNonNull(path, "watchedFiles entries cannot be null");
        }
        Objects.requireNonNull(debounce, "debounce cannot be null");
        if (debounce.isNegative() || debounce.isZero()) {
            throw new IllegalArgumentException("debounce must be positive, got: " + debounce);
        }
        Objects.requireNonNull(pollInterval, "pollInterval cannot be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got: " + pollInterval);
        }
        this.watchedFiles = List.copyOf(watchedFiles);
        this.reloadListener = Objects.requireNonNull(reloadListener, "reloadListener cannot be null");
        this.debounce = debounce;
        this.pollInterval = pollInterval;
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "aimon-hook-config-watcher");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsScheduler = true;
        }
    }

    /**
     * Starts polling the watched files. Idempotent — subsequent calls are no-ops.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        snapshotInitial();
        final ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(this::poll, pollInterval.toMillis(),
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        pollTask.set(task);
        log.info("HookConfigWatcher started (files={}, debounce={}, pollInterval={})", watchedFiles, debounce,
                pollInterval);
    }

    /**
     * Returns the monotonic reload counter. Incremented once per fired reload.
     *
     * @return current counter (starts at 0; first reload increments to 1)
     */
    public long getReloadCounter() {
        return reloadCounter.get();
    }

    /**
     * Forces a debounced reload as if a file event had been observed. Useful for tests and manual reload commands.
     *
     * @param triggeringPath
     *            path to associate with the reload (must be one of the watched paths)
     */
    public void triggerReload(Path triggeringPath) {
        Objects.requireNonNull(triggeringPath, "triggeringPath cannot be null");
        if (!watchedFiles.contains(triggeringPath)) {
            throw new IllegalArgumentException(
                    "triggeringPath " + triggeringPath + " is not one of the watched files " + watchedFiles);
        }
        if (!running.get()) {
            return;
        }
        scheduleDebouncedReload(triggeringPath);
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        cancelTask(pollTask.getAndSet(null));
        cancelTask(pendingReload.getAndSet(null));
        if (ownsScheduler) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        log.info("HookConfigWatcher stopped (final reload counter: {})", reloadCounter.get());
    }

    private static void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    private void snapshotInitial() {
        for (Path path : watchedFiles) {
            try {
                final boolean exists = Files.exists(path);
                lastExisted.put(path, exists);
                if (exists) {
                    lastModified.put(path, Files.getLastModifiedTime(path));
                }
            } catch (IOException e) {
                log.warn("Failed to snapshot {}: {}", path, e.getMessage());
                lastExisted.put(path, false);
            }
        }
    }

    private void poll() {
        if (!running.get()) {
            return;
        }
        Path mostRecent = null;
        for (Path path : watchedFiles) {
            try {
                final boolean exists = Files.exists(path);
                final boolean wasExisting = Boolean.TRUE.equals(lastExisted.get(path));
                if (exists != wasExisting) {
                    lastExisted.put(path, exists);
                    if (exists) {
                        lastModified.put(path, Files.getLastModifiedTime(path));
                    } else {
                        lastModified.remove(path);
                    }
                    mostRecent = path;
                    continue;
                }
                if (!exists) {
                    continue;
                }
                final FileTime current = Files.getLastModifiedTime(path);
                final FileTime previous = lastModified.get(path);
                if (previous == null || !previous.equals(current)) {
                    lastModified.put(path, current);
                    mostRecent = path;
                }
            } catch (NoSuchFileException e) {
                if (Boolean.TRUE.equals(lastExisted.put(path, false))) {
                    lastModified.remove(path);
                    mostRecent = path;
                }
            } catch (IOException e) {
                log.warn("Failed to poll {}: {}", path, e.getMessage());
            }
        }
        if (mostRecent != null) {
            scheduleDebouncedReload(mostRecent);
        }
    }

    private void scheduleDebouncedReload(Path triggeringPath) {
        final ScheduledFuture<?> previous = pendingReload.getAndSet(null);
        if (previous != null) {
            previous.cancel(false);
        }
        final ScheduledFuture<?> task = scheduler.schedule(() -> fireReload(triggeringPath), debounce.toMillis(),
                TimeUnit.MILLISECONDS);
        pendingReload.set(task);
        log.debug("Reload scheduled in {} due to event on {}", debounce, triggeringPath);
    }

    private void fireReload(Path triggeringPath) {
        pendingReload.set(null);
        if (!running.get()) {
            return;
        }
        final long counter = reloadCounter.incrementAndGet();
        try {
            reloadListener.onReload(counter, triggeringPath);
        } catch (RuntimeException e) {
            log.warn("Reload listener failed (counter={}, path={}): {}", counter, triggeringPath, e.getMessage(), e);
        }
    }
}
