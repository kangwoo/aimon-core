package at.aimon.core.config.hook;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.rewake.RewakeService;

/**
 * Performs transactional swap of hooks loaded from {@code hooks.json} layered config.
 *
 * <p>
 * Owned by the application bootstrap (CLI / web entry point) alongside {@link HookConfigWatcher}. The reloader is
 * wired as the watcher's {@code ReloadListener}: each debounced file event drives a {@link #reload(long, Path)} call.
 *
 * <p>
 * <b>Swap semantics.</b> Consumers of {@link HookRegistry} cache the registry instance directly (see
 * {@code OrcaAgentRuntime}, {@code TaskTool}, ...), so the registry reference itself cannot be swapped. The
 * reloader instead tracks the hook instances it has registered ("managed hooks") and on each reload:
 * <ol>
 * <li>loads the layered config and merges it,
 * <li>uses {@link HookRegistryApplier} to materialise the new hooks into a staging {@link DefaultHookRegistry},
 * <li>under a swap lock, unregisters the previously managed hooks and registers the new ones on the live registry,
 * <li>fires {@link at.aimon.core.hook.event.OnConfigReloadHook OnConfigReload} with the success/failure outcome.
 * </ol>
 *
 * <p>
 * Coverage of every {@link HookEventType} is automatic — the reloader iterates {@link HookEventType#values()} and uses
 * the typed {@link HookRegistry#register(HookEventType, ExecutionHook)} /
 * {@link HookRegistry#unregister(HookEventType, ExecutionHook)} pair, so adding a new event type requires zero changes
 * here.
 *
 * <p>
 * The swap is not strictly atomic from the perspective of concurrent hook firings — between unregister and
 * re-register a brief window exists where managed hooks are absent. This is acceptable because (a) hook firings are
 * non-transactional by design, (b) the swap is fast (CopyOnWriteArrayList ops on a few entries), and (c) hook callers
 * already iterate over a snapshot of the list at firing time.
 *
 * <p>
 * Hooks registered programmatically (e.g. by skills or by application code) are never touched by the reloader: it
 * only owns the hook instances it itself registered. This means in-process hook registrations survive reload.
 *
 * <p>
 * <b>Failure handling.</b> If {@link HookConfigLoader#load()} or the merge step throws, the reloader leaves the live
 * registry untouched and fires {@code OnConfigReload(successful=false)}. If the swap itself throws partway through
 * (e.g. a register call on a custom registry impl fails after some old hooks have already been unregistered), the
 * reloader rolls back every action it performed in reverse order, restoring the registry to its pre-reload state
 * before firing {@code OnConfigReload(successful=false)}.
 */
public final class HookRegistryReloader {

    private static final Logger log = LoggerFactory.getLogger(HookRegistryReloader.class);

    private final HookConfigLoader loader;
    private final HookConfigMerger merger;
    private final HookRegistryApplier bootstrap;
    private final HookRegistry registry;
    private final HookExecutionManager executionManager;
    private final ReloadInvoker invoker;
    private final RewakeService rewakeService;

    private final Object swapLock = new Object();
    private final List<TypeBinding<?>> bindings;

    /**
     * Creates a reloader.
     *
     * @param loader
     *            the layered-config loader (must not be null)
     * @param merger
     *            the layered-to-merged config merger (must not be null)
     * @param bootstrap
     *            the bootstrap that materialises {@link MergedHookConfig} into hook instances (must not be null)
     * @param registry
     *            the live hook registry to swap against (must not be null)
     * @param executionManager
     *            the manager used to fire {@link at.aimon.core.hook.event.OnConfigReloadHook OnConfigReload}; may be
     *            {@code null} to suppress event firing (e.g. during initial bootstrap before hooks are wired)
     * @param invoker
     *            invoker identity (type / name / environment) embedded in the {@link OnConfigReloadContext} (must not
     *            be null)
     */
    public HookRegistryReloader(HookConfigLoader loader, HookConfigMerger merger, HookRegistryApplier bootstrap,
            HookRegistry registry, HookExecutionManager executionManager, ReloadInvoker invoker) {
        this(loader, merger, bootstrap, registry, executionManager, invoker, RewakeService.NOOP);
    }

    /**
     * Creates a reloader with explicit rewake-cancellation wiring.
     *
     * <p>
     * When a hook drops out of the merged config, every pending {@code RewakeEnvelope} originating from that hook is
     * cancelled before the swap commits — otherwise a deferred fire would arrive at a hook that no longer exists.
     *
     * @param loader
     *            the layered-config loader (must not be null)
     * @param merger
     *            the layered-to-merged config merger (must not be null)
     * @param bootstrap
     *            the bootstrap that materialises {@link MergedHookConfig} into hook instances (must not be null)
     * @param registry
     *            the live hook registry to swap against (must not be null)
     * @param executionManager
     *            the manager used to fire {@link at.aimon.core.hook.event.OnConfigReloadHook OnConfigReload}; may be
     *            {@code null} to suppress event firing
     * @param invoker
     *            invoker identity (type / name / environment) embedded in the {@link OnConfigReloadContext} (must not
     *            be null)
     * @param rewakeService
     *            the application-scoped rewake service used to cancel envelopes for removed hooks; pass
     *            {@link RewakeService#NOOP} when async rewake is disabled (must not be null)
     */
    public HookRegistryReloader(HookConfigLoader loader, HookConfigMerger merger, HookRegistryApplier bootstrap,
            HookRegistry registry, HookExecutionManager executionManager, ReloadInvoker invoker,
            RewakeService rewakeService) {
        this.loader = Objects.requireNonNull(loader, "loader cannot be null");
        this.merger = Objects.requireNonNull(merger, "merger cannot be null");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.executionManager = executionManager;
        this.invoker = Objects.requireNonNull(invoker, "invoker cannot be null");
        this.rewakeService = Objects.requireNonNull(rewakeService, "rewakeService cannot be null");
        this.bindings = createBindings();
    }

    /**
     * Performs the initial config load and registers the resulting hooks. Does <b>not</b> fire
     * {@code OnConfigReload} — that event is reserved for post-startup reloads. Should be called once at application
     * startup before any agent runs.
     *
     * @return {@code true} when the initial load succeeded; {@code false} when the load or apply step threw (in which
     *         case the registry is left untouched).
     */
    public boolean bootstrap() {
        synchronized (swapLock) {
            try {
                final MergedHookConfig merged = merger.merge(loader.load());
                applyToManagedLocked(merged);
                log.info("Initial hooks.json bootstrap applied: {}", describeManagedCounts());
                return true;
            } catch (RuntimeException e) {
                log.warn("Initial hooks.json bootstrap failed: {}", e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * Reloads the layered config and atomically swaps the managed hooks on the live registry. Fires
     * {@code OnConfigReload} with {@code successful=true} on success, or with {@code successful=false} and a non-empty
     * {@code failureReason} on parse / merge failure.
     *
     * @param reloadCounter
     *            monotonic reload counter from the watcher (must be &gt;= 0)
     * @param triggeringPath
     *            the path that fired the reload (may be {@code null}; only used for the {@code configSource} field)
     * @return {@code true} when the swap succeeded and the new config is live; {@code false} when the reload failed
     *         and the prior config remains in effect.
     */
    public boolean reload(long reloadCounter, Path triggeringPath) {
        if (reloadCounter < 0) {
            throw new IllegalArgumentException("reloadCounter must be >= 0, got: " + reloadCounter);
        }
        final String configSource = triggeringPath == null ? "" : triggeringPath.toString();

        synchronized (swapLock) {
            final MergedHookConfig merged;
            try {
                merged = merger.merge(loader.load());
            } catch (RuntimeException e) {
                log.warn("Hook config reload (counter={}) failed during load/merge: {}", reloadCounter, e.getMessage(),
                        e);
                fireOnConfigReload(reloadCounter, configSource, false, "load/merge failed: " + e.getMessage());
                return false;
            }
            try {
                applyToManagedLocked(merged);
            } catch (RuntimeException e) {
                log.error("Hook config reload (counter={}) failed during swap: {}", reloadCounter, e.getMessage(), e);
                fireOnConfigReload(reloadCounter, configSource, false, "swap failed: " + e.getMessage());
                return false;
            }
            log.info("Hook config reload (counter={}) applied from {}: {}", reloadCounter, configSource,
                    describeManagedCounts());
            fireOnConfigReload(reloadCounter, configSource, true, "");
            return true;
        }
    }

    /**
     * Returns the current count of managed hooks (sum across every supported event type).
     *
     * @return total managed hook count
     */
    public int getManagedHookCount() {
        int total = 0;
        for (TypeBinding<?> b : bindings) {
            total += b.current.size();
        }
        return total;
    }

    private String describeManagedCounts() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            final TypeBinding<?> b = bindings.get(i);
            sb.append(b.type.name()).append('=').append(b.current.size());
        }
        return sb.toString();
    }

    private void applyToManagedLocked(MergedHookConfig merged) {
        // Step 1 — build new hooks in a staging registry. If this throws, the live registry has not been touched
        // and there is nothing to roll back.
        final DefaultHookRegistry staging = new DefaultHookRegistry();
        bootstrap.apply(merged, staging);

        for (TypeBinding<?> b : bindings) {
            b.prepareNext(staging);
        }

        // Compute the set of hook ids that are dropping out of the merged config — these are the rewake-cancel
        // candidates. Computed before the swap so the live state used here is the same state
        // that the cancel-after-commit step will refer to.
        final Set<String> removedHookIds = collectRemovedHookIds();

        // Step 2 — apply to live registry. Each binding tracks how far through its unregister / register loop it got
        // so a mid-swap throw can be rolled back to the pre-reload state.
        try {
            for (TypeBinding<?> b : bindings) {
                b.applyUnregister(registry);
            }
            for (TypeBinding<?> b : bindings) {
                b.applyRegister(registry);
            }
        } catch (RuntimeException primary) {
            log.warn("Hook swap failed mid-application; rolling back: {}", primary.getMessage());
            // Undo any new registrations performed so far (per-binding LIFO across all types).
            for (int i = bindings.size() - 1; i >= 0; i--) {
                bindings.get(i).rollbackRegisters(registry);
            }
            // Re-register previously-unregistered old hooks in their original order.
            for (TypeBinding<?> b : bindings) {
                b.rollbackUnregisters(registry);
            }
            throw primary;
        }

        for (TypeBinding<?> b : bindings) {
            b.commit();
        }

        // Step 3 — cancel pending rewake envelopes that originated from hooks just removed. Done post-commit so a
        // mid-swap rollback (above) does not over-cancel envelopes for hooks that ended up being restored.
        cancelRewakesForRemovedHooks(removedHookIds);
    }

    private Set<String> collectRemovedHookIds() {
        final Set<String> nextIds = new HashSet<>();
        for (TypeBinding<?> b : bindings) {
            b.collectNextHookIds(nextIds);
        }
        final Set<String> removed = new LinkedHashSet<>();
        for (TypeBinding<?> b : bindings) {
            b.collectRemovedHookIds(nextIds, removed);
        }
        return removed;
    }

    private void cancelRewakesForRemovedHooks(Set<String> removedHookIds) {
        if (removedHookIds.isEmpty()) {
            return;
        }
        for (String hookId : removedHookIds) {
            try {
                final int cancelled = rewakeService.cancelByOriginatingHookId(hookId);
                if (cancelled > 0) {
                    log.info("Cancelled {} pending rewake envelope(s) for removed hook id={}", cancelled, hookId);
                }
            } catch (RuntimeException e) {
                // Cancellation is best-effort — a failure here must not abort the reload, which has already
                // committed the registry swap. Log and continue.
                log.warn("Failed to cancel rewake envelopes for removed hook id={}: {}", hookId, e.getMessage(), e);
            }
        }
    }

    private static void safeRollback(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException rollbackEx) {
            log.error("Rollback action failed: {}", rollbackEx.getMessage(), rollbackEx);
        }
    }

    private void fireOnConfigReload(long counter, String configSource, boolean successful, String failureReason) {
        if (executionManager == null) {
            return;
        }
        try {
            final OnConfigReloadContext.Builder b = OnConfigReloadContext.builder().invokerType(invoker.getType())
                    .invokerName(invoker.getName()).hookRegistry(registry).environment(invoker.getEnvironment())
                    .reloadCounter(counter).successful(successful);
            if (!configSource.isEmpty()) {
                b.configSource(configSource);
            }
            if (!successful && failureReason != null) {
                b.failureReason(failureReason);
            }
            executionManager.executeOnConfigReload(b.build());
        } catch (RuntimeException e) {
            log.warn("OnConfigReload hook firing failed (counter={}, successful={}): {}", counter, successful,
                    e.getMessage(), e);
        }
    }

    private static List<TypeBinding<?>> createBindings() {
        final List<TypeBinding<?>> out = new ArrayList<>();
        for (HookEventType<?> type : HookEventType.values()) {
            out.add(TypeBinding.of(type));
        }
        return List.copyOf(out);
    }

    /** Visible for testing. */
    static int bindingCountForTest() {
        return createBindings().size();
    }

    // CHECKSTYLE.OFF: VisibilityModifier — fields are intentionally package-private for the static initialiser.
    /**
     * Per-event-type adapter tracking the staging-registry contents and the mutable state needed to track partial
     * application during swap.
     *
     * <p>
     * Each binding records — under the {@link HookRegistryReloader#swapLock} — how far through its unregister /
     * register loops it has progressed so {@link HookRegistryReloader#applyToManagedLocked} can roll back partial
     * application on mid-swap failure.
     */
    private static final class TypeBinding<H extends ExecutionHook<?>> {
        private final HookEventType<H> type;
        private volatile List<H> current = List.of();
        private List<H> next = List.of();
        private int unregisteredCount;
        private int registeredCount;

        private TypeBinding(HookEventType<H> type) {
            this.type = type;
        }

        static <H extends ExecutionHook<?>> TypeBinding<H> of(HookEventType<H> type) {
            return new TypeBinding<>(type);
        }

        void prepareNext(DefaultHookRegistry staging) {
            this.next = List.copyOf(staging.getHooks(type));
            this.unregisteredCount = 0;
            this.registeredCount = 0;
        }

        void applyUnregister(HookRegistry liveRegistry) {
            for (; unregisteredCount < current.size(); unregisteredCount++) {
                liveRegistry.unregister(type, current.get(unregisteredCount));
            }
        }

        void applyRegister(HookRegistry liveRegistry) {
            for (; registeredCount < next.size(); registeredCount++) {
                liveRegistry.register(type, next.get(registeredCount));
            }
        }

        void rollbackRegisters(HookRegistry liveRegistry) {
            for (int i = registeredCount - 1; i >= 0; i--) {
                final H h = next.get(i);
                safeRollback(() -> liveRegistry.unregister(type, h));
            }
        }

        void rollbackUnregisters(HookRegistry liveRegistry) {
            for (int i = 0; i < unregisteredCount; i++) {
                final H h = current.get(i);
                safeRollback(() -> liveRegistry.register(type, h));
            }
        }

        void commit() {
            current = next;
            next = List.of();
        }

        void collectNextHookIds(Set<String> out) {
            for (H h : next) {
                out.add(h.getHookId());
            }
        }

        void collectRemovedHookIds(Set<String> nextIds, Set<String> out) {
            for (H h : current) {
                final String id = h.getHookId();
                if (!nextIds.contains(id)) {
                    out.add(id);
                }
            }
        }
    }
    // CHECKSTYLE.ON: VisibilityModifier
}
