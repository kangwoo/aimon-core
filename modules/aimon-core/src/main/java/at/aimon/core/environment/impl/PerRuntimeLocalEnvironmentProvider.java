package at.aimon.core.environment.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 */
public final class PerRuntimeLocalEnvironmentProvider implements ExecutionEnvironmentProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PerRuntimeLocalEnvironmentProvider.class);

    private final Function<AgentRuntimeId, LocalExecutionEnvironmentProvider> workspaces;
    private final Object lock = new Object();
    private final Map<AgentRuntimeId, Slot> slots = new HashMap<>();
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
        final Slot slot;
        synchronized (lock) {
            slot = slotLocked(agentRuntimeId);
            slot.bindings++;
        }
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
        synchronized (lock) {
            return slotLocked(agentRuntimeId).provider;
        }
    }

    private Slot slotLocked(AgentRuntimeId agentRuntimeId) {
        if (closed) {
            throw new IllegalStateException(
                    "The execution environment provider is closed; no workspace for " + agentRuntimeId);
        }
        Slot slot = slots.get(agentRuntimeId);
        if (slot == null) {
            slot = new Slot(Objects.requireNonNull(workspaces.apply(agentRuntimeId),
                    () -> "The workspace function returned null for " + agentRuntimeId));
            slots.put(agentRuntimeId, slot);
        }
        return slot;
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
