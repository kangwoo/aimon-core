package at.aimon.core.workflow.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.workflow.RunHandle;
import at.aimon.core.workflow.RunId;

/**
 * Node-local registry of the live runs this node is hosting (design §5.1) — a run-shaped analog of
 * {@code RunningTaskRegistry}.
 *
 * <p>
 * Maps a {@link RunId} to its {@link RunControl} (the cancellation machinery {@code stop(runId)} trips) and its
 * {@link RunHandle} (returned to a caller that re-submits a still-live id). Entries are non-serializable and exist only
 * on the node running the run; the durable, cross-node view is the {@code RunStore}. Thread-safe.
 */
final class RunningRunRegistry {

    /**
     * A live run's node-local handles: its cancellation control, its typed result handle, and the runner's own result
     * future. The future is kept separately from the handle because {@link RunHandle} exposes only a decoupled copy —
     * settling a run (e.g. on {@code close()}) must complete the <em>original</em>, which is what fires the finalizer.
     */
    static final class Entry {
        private final RunControl control;
        private final RunHandle<?> handle;
        private final CompletableFuture<?> future;

        Entry(RunControl control, RunHandle<?> handle, CompletableFuture<?> future) {
            this.control = Objects.requireNonNull(control, "control cannot be null");
            this.handle = Objects.requireNonNull(handle, "handle cannot be null");
            this.future = Objects.requireNonNull(future, "future cannot be null");
        }

        RunControl control() {
            return control;
        }

        RunHandle<?> handle() {
            return handle;
        }

        CompletableFuture<?> future() {
            return future;
        }
    }

    private final ConcurrentMap<RunId, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Registers a live run, returning the created {@link Entry} so its finalizer can later remove <em>exactly</em> that
     * entry (value-checked). Overwrites any lingering entry for the same id — safe because removal is value-checked, so
     * a stale finalizer cannot delete the newer entry (closes the ABA race where a re-submit reusing a terminal id
     * registers while the prior run's finalizer has not yet removed its own entry).
     *
     * @param runId
     *            the run id (must not be null)
     * @param control
     *            the run's cancellation control (must not be null)
     * @param handle
     *            the run's result handle (must not be null)
     * @param future
     *            the runner's own result future for the run (must not be null)
     * @return the registered entry (pass it to {@link #remove(RunId, Entry)} on finalization)
     */
    Entry register(RunId runId, RunControl control, RunHandle<?> handle, CompletableFuture<?> future) {
        Objects.requireNonNull(runId, "runId cannot be null");
        final Entry entry = new Entry(control, handle, future);
        entries.put(runId, entry);
        return entry;
    }

    /**
     * @param runId
     *            the run id (must not be null)
     * @return the live entry, or empty if this node is not hosting that run
     */
    Optional<Entry> find(RunId runId) {
        Objects.requireNonNull(runId, "runId cannot be null");
        return Optional.ofNullable(entries.get(runId));
    }

    /**
     * Removes a run's entry on finalization, <b>only if</b> the currently-mapped entry is still {@code entry} (identity
     * value-check). A stale finalizer whose entry has already been overwritten by a re-submit thus becomes a no-op,
     * leaving the newer run's entry — and its cancellation lever — intact.
     *
     * @param runId
     *            the run id (must not be null)
     * @param entry
     *            the entry this finalizer registered (must not be null)
     */
    void remove(RunId runId, Entry entry) {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(entry, "entry cannot be null");
        entries.remove(runId, entry);
    }

    /**
     * @return a snapshot of the currently registered live entries (for the runner's close-time settlement sweep; the
     *         snapshot is not live — concurrent finalizations may remove entries after it is taken)
     */
    List<Map.Entry<RunId, Entry>> snapshot() {
        return new ArrayList<>(entries.entrySet());
    }
}
