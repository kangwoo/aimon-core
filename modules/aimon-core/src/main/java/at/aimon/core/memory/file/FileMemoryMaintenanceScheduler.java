/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.file;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;

/**
 * Application-scoped, Dreamer-independent maintenance for the file-backed memory
 * stores. On a fixed schedule it, per workspace:
 *
 * <ol>
 * <li>purges observations soft-deleted longer ago than the observation
 * retention window ({@link ObservationStore#purgeSoftDeletedBefore}),</li>
 * <li>drops representations older than the representation retention window
 * ({@link RepresentationStore#deleteOlderThan}),</li>
 * </ol>
 *
 * and then {@link Compactable#compact() compacts} every store so the append logs
 * shrink to live state. This guarantees retention and bounded disk usage even
 * when the Dreamer is disabled — the file backend's two production blockers
 * (unbounded log growth, retention only via the Dreamer) handled in one place.
 *
 * <p>
 * Single daemon thread; {@link #start()} / {@link #close()} manage its
 * lifecycle, and {@link #runOnce()} performs a synchronous pass (used by tests
 * or a custom scheduler).
 */
public final class FileMemoryMaintenanceScheduler implements AutoCloseable {

    /** Default audit-retention window for soft-deleted observations (design doc §5.2). */
    public static final Duration DEFAULT_OBSERVATION_RETENTION = Duration.ofDays(30);
    /** Default retention window for representation snapshots. */
    public static final Duration DEFAULT_REPRESENTATION_RETENTION = Duration.ofDays(90);
    /** Default interval between maintenance passes. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofHours(6);

    private static final Logger log = LoggerFactory.getLogger(FileMemoryMaintenanceScheduler.class);

    private final WorkspaceStore workspaceStore;
    private final ObservationStore observationStore;
    private final RepresentationStore representationStore;
    private final List<Compactable> compactables;
    private final Duration observationRetention;
    private final Duration representationRetention;
    private final Duration interval;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private ScheduledExecutorService scheduler;

    /** Creates a scheduler with the default retention/interval windows. */
    public FileMemoryMaintenanceScheduler(WorkspaceStore workspaceStore, ObservationStore observationStore,
            RepresentationStore representationStore, List<Compactable> compactables) {
        this(workspaceStore, observationStore, representationStore, compactables, DEFAULT_OBSERVATION_RETENTION,
                DEFAULT_REPRESENTATION_RETENTION, DEFAULT_INTERVAL);
    }

    /**
     * @param compactables
     *            the file stores to compact each pass (typically the workspace, observation and
     *            representation stores); must not be null
     */
    public FileMemoryMaintenanceScheduler(WorkspaceStore workspaceStore, ObservationStore observationStore,
            RepresentationStore representationStore, List<Compactable> compactables, Duration observationRetention,
            Duration representationRetention, Duration interval) {
        this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore cannot be null");
        this.observationStore = Objects.requireNonNull(observationStore, "observationStore cannot be null");
        this.representationStore = Objects.requireNonNull(representationStore, "representationStore cannot be null");
        this.compactables = List.copyOf(Objects.requireNonNull(compactables, "compactables cannot be null"));
        this.observationRetention = requirePositive(observationRetention, "observationRetention");
        this.representationRetention = requirePositive(representationRetention, "representationRetention");
        this.interval = requirePositive(interval, "interval");
    }

    /** Starts the daemon scheduler. Idempotent; the first pass runs after one interval. */
    public void start() {
        synchronized (lifecycleLock) {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-memory-maintenance");
                t.setDaemon(true);
                return t;
            });
            long ms = interval.toMillis();
            scheduler.scheduleWithFixedDelay(this::runSafely, ms, ms, TimeUnit.MILLISECONDS);
            log.info("File memory maintenance scheduled every {} (observationRetention={}, representationRetention={})",
                    interval, observationRetention, representationRetention);
        }
    }

    /**
     * Runs one maintenance pass synchronously: retention purge + representation pruning per workspace,
     * then compaction of every store. Per-step failures are logged and do not abort the pass.
     */
    public void runOnce() {
        final Instant now = Instant.now();
        final Instant observationCutoff = now.minus(observationRetention);
        final Instant representationCutoff = now.minus(representationRetention);

        List<Workspace> workspaces = workspaceStore.findAll(Principal.system());
        int purgedObservations = 0;
        for (Workspace ws : workspaces) {
            try {
                purgedObservations += observationStore.purgeSoftDeletedBefore(ws, observationCutoff);
            } catch (RuntimeException e) {
                log.warn("retention purge failed for workspace {}: {}", ws.getId(), e.getMessage());
            }
            try {
                representationStore.deleteOlderThan(ws, representationCutoff);
            } catch (RuntimeException e) {
                log.warn("representation pruning failed for workspace {}: {}", ws.getId(), e.getMessage());
            }
        }
        for (Compactable compactable : compactables) {
            try {
                compactable.compact();
            } catch (RuntimeException e) {
                log.warn("compaction failed for {}: {}", compactable.getClass().getSimpleName(), e.getMessage());
            }
        }
        log.info("file memory maintenance: workspaces={}, purgedObservations={}, compactedStores={}", workspaces.size(),
                purgedObservations, compactables.size());
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("file memory maintenance pass failed: {}", e.getMessage(), e);
        }
    }

    /** Stops the daemon scheduler. Idempotent. Does not close the stores themselves. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
            started.set(false);
        }
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name + " cannot be null");
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + d);
        }
        return d;
    }
}
