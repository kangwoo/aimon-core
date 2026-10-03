package at.aimon.core.environment.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.RuntimeBinding;

/**
 * One application-scoped provider over many local workspaces: each agent runtime gets its own
 * {@link LocalExecutionEnvironmentProvider}, chosen by {@link EnvironmentRequest#agentRuntimeId()}
 * (execution-environment design §4.3).
 *
 * <p>
 * The workspace a runtime id maps to is the caller's rule — a function from the id to the local provider for it — and
 * this class keeps what the function returns in a slot per id. A fork's request carries its parent environment, which
 * is returned as-is, exactly as the single-workspace provider does.
 *
 * <p>
 * <b>Eviction.</b> {@link #bindRuntime} counts the runtimes alive for an id; closing the last binding closes that id's
 * local provider and drops the slot, so an evicted tenant holds nothing here. Closing it does <em>not</em> stop a
 * background command the runtime left running — a {@code LocalShell} keeps nothing of a command, which is a process of
 * its own — and that is what lets this class honour {@link RuntimeBinding}'s contract by simply closing the slot. It
 * is true of a {@code LocalExecutionEnvironmentProvider} and not of providers in general, which is why the function's
 * return type is that class and not the interface.
 *
 * <p>
 * A request for an id nobody bound still resolves: the slot is created on the spot and stays until {@link #close()}.
 *
 * <p>
 * <b>Concurrency.</b> The workspace function runs <em>outside</em> the provider-wide lock: creating a directory,
 * sweeping its staging area or connecting a remote file system for one tenant must not hold up every other tenant's
 * {@link #resolve}. Concurrent first requests for the same id share one build (the function is called once); a build
 * that fails is forgotten, so the next request tries again. The lock guards only the bookkeeping (the slot map, the
 * in-flight builds and the binding counts). A workspace function that itself waits for {@link #workspace} of the
 * <em>same</em> id deadlocks, as any self-dependent initialisation would; other ids are unaffected.
 */
public final class PerRuntimeLocalEnvironmentProvider implements ExecutionEnvironmentProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PerRuntimeLocalEnvironmentProvider.class);

    private final Function<AgentRuntimeId, LocalExecutionEnvironmentProvider> workspaces;
    private final Object lock = new Object();
    private final Map<AgentRuntimeId, Slot> slots = new HashMap<>();
    private final Map<AgentRuntimeId, CompletableFuture<Void>> building = new HashMap<>();
    private boolean closed;

    /**
     * @param workspaces
     *            builds the local provider for a runtime id; called once per slot, and its result is owned (closed)
     *            by this provider (must not be null; must not return null)
     */
    public PerRuntimeLocalEnvironmentProvider(Function<AgentRuntimeId, LocalExecutionEnvironmentProvider> workspaces) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
    }

    @Override
    public ExecutionEnvironment resolve(EnvironmentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.parent().isPresent()) {
            return request.parent().get();
        }
        return workspace(request.agentRuntimeId()).resolve(request);
    }

    @Override
    public RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        final Slot slot = slot(agentRuntimeId, true);
        final AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                release(agentRuntimeId, slot);
            }
        };
    }

    /**
     * Returns the local provider of a runtime id's workspace — what an assembly reads the runtime's workspace
     * filesystem from. Creates the slot if there is none yet.
     *
     * @param agentRuntimeId
     *            the runtime id (must not be null)
     * @return the local provider for that id
     * @throws IllegalStateException
     *             if this provider is closed
     */
    public LocalExecutionEnvironmentProvider workspace(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        return slot(agentRuntimeId, false).provider;
    }

    /**
     * Returns the id's slot, building it first if there is none, and counts a binding on it when {@code bind}. The
     * workspace function runs with no lock held; the slot map and the binding count change only under the lock, so a
     * slot is never handed out after its last binding closed it.
     */
    private Slot slot(AgentRuntimeId agentRuntimeId, boolean bind) {
        while (true) {
            final CompletableFuture<Void> inFlight;
            final boolean builder;
            synchronized (lock) {
                checkOpen(agentRuntimeId);
                final Slot slot = slots.get(agentRuntimeId);
                if (slot != null) {
                    if (bind) {
                        slot.bindings++;
                    }
                    return slot;
                }
                final CompletableFuture<Void> existing = building.get(agentRuntimeId);
                builder = existing == null;
                inFlight = builder ? new CompletableFuture<>() : existing;
                if (builder) {
                    building.put(agentRuntimeId, inFlight);
                }
            }
            if (builder) {
                build(agentRuntimeId, bind, inFlight);
            } else {
                awaitBuild(agentRuntimeId, inFlight);
            }
            // Look again under the lock: the slot may already be gone (its last binding closed, or the provider
            // closed) between the build finishing and this thread getting here. The loop then builds a fresh one or
            // reports the provider closed.
        }
    }

    private void build(AgentRuntimeId agentRuntimeId, boolean bind, CompletableFuture<Void> inFlight) {
        final LocalExecutionEnvironmentProvider provider;
        try {
            provider = Objects.requireNonNull(workspaces.apply(agentRuntimeId),
                    () -> "The workspace function returned null for " + agentRuntimeId);
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                building.remove(agentRuntimeId, inFlight);
            }
            inFlight.completeExceptionally(e);
            throw e;
        }
        final boolean closedMeanwhile;
        synchronized (lock) {
            building.remove(agentRuntimeId, inFlight);
            closedMeanwhile = closed;
            if (!closedMeanwhile) {
                slots.put(agentRuntimeId, new Slot(provider));
            }
        }
        if (closedMeanwhile) {
            // close() ran while the function was building: nothing else will ever close what it built.
            provider.close();
            final IllegalStateException e = closedException(agentRuntimeId);
            inFlight.completeExceptionally(e);
            throw e;
        }
        if (!bind) {
            log.debug(
                    "Created the workspace of {} with no runtime bound to it; it stays until the provider closes"
                            + " (a request that arrived after the runtime was evicted re-creates it the same way)",
                    agentRuntimeId);
        }
        inFlight.complete(null);
    }

    private static void awaitBuild(AgentRuntimeId agentRuntimeId, CompletableFuture<Void> inFlight) {
        try {
            inFlight.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the workspace of " + agentRuntimeId + " was built", e);
        } catch (ExecutionException e) {
            // The building thread already forgot the failed build; the next request tries again.
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Building the workspace of " + agentRuntimeId + " failed", cause);
        }
    }

    private void checkOpen(AgentRuntimeId agentRuntimeId) {
        if (closed) {
            throw closedException(agentRuntimeId);
        }
    }

    private static IllegalStateException closedException(AgentRuntimeId agentRuntimeId) {
        return new IllegalStateException(
                "The execution environment provider is closed; no workspace for " + agentRuntimeId);
    }

    private void release(AgentRuntimeId agentRuntimeId, Slot slot) {
        synchronized (lock) {
            slot.bindings--;
            // The identity check matters after close(): the slot is no longer in the map and is already closed.
            if (slot.bindings > 0 || slots.get(agentRuntimeId) != slot) {
                return;
            }
            slots.remove(agentRuntimeId);
        }
        log.debug("Closing the workspace of {}: its last runtime is gone", agentRuntimeId);
        slot.provider.close();
    }

    /** Closes every workspace. Bindings closed afterwards do nothing. */
    @Override
    public void close() {
        final List<Slot> doomed;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            doomed = new ArrayList<>(slots.values());
            slots.clear();
        }
        doomed.forEach(slot -> slot.provider.close());
    }

    /** One runtime id's workspace and how many live runtimes are bound to it. */
    private static final class Slot {
        private final LocalExecutionEnvironmentProvider provider;
        private int bindings;

        Slot(LocalExecutionEnvironmentProvider provider) {
            this.provider = provider;
        }
    }
}
