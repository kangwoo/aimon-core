package at.aimon.core.agent.impl.orca;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.command.OrcaCommandProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.scheduling.ScheduledTaskManager;

/**
 * Manages the lifecycle of agent-scoped {@link OrcaAgentRuntime} instances.
 *
 * <p>
 * Provides thread-safe creation and retrieval of agent runtimes using per-id locking. The id is derived
 * deterministically from the {@link AgentBundle}'s agent (and an optional {@code discriminator}) via
 * {@link AgentRuntimeId#from(at.aimon.core.agent.Agent)} /
 * {@link AgentRuntimeId#from(at.aimon.core.agent.Agent, String) from(Agent, String)} so that the
 * <b>(agent, discriminator) pair</b> identifies an agent runtime, not the session. Locking ensures the same
 * id is never created concurrently while allowing parallel creation for different ids.
 *
 * <p>
 * Bootstrap callers (CLI: {@code AgentSetupFactory}; Web: {@code LiveSessionOpener}) invoke
 * {@link #getOrCreateRuntime(AgentBundle, VirtualFileSystem, CredentialStore)} once per agent at startup; subsequent
 * sessions read the same instance via the {@link AgentRuntimeRegistry}. Tear-down via
 * {@link #destroyRuntime(AgentRuntimeId)} happens only on application shutdown or explicit agent removal.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaAgentRuntimeManager manager = OrcaAgentRuntimeManager.builder()
 *             .agentExecutor(executor).agentRuntimeRegistry(registry).agentRuntimeFactory(factory)
 *             .toolProviders(OrcaAgentRuntimeFactory.defaultToolProviders())
 *             .commandProviders(OrcaAgentRuntimeFactory.defaultCommandProviders()).build();
 *
 *     // Single AEC per agent
 *     OrcaAgentRuntime context = manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);
 *
 *     // Or split a single agent definition by tenant / environment / user
 *     OrcaAgentRuntime acmeContext = manager.getOrCreateRuntime(bundle, "acme", fileSystem, credentialStore);
 * }
 * </pre>
 */
public final class OrcaAgentRuntimeManager {

    /** Builder를 생성한다. */
    public static Builder builder() {
        return new Builder();
    }

    private static final Logger log = LoggerFactory.getLogger(OrcaAgentRuntimeManager.class);

    private final OrcaAgentExecutor agentExecutor;
    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final OrcaAgentRuntimeFactory agentRuntimeFactory;
    private final ScheduledTaskManager scheduledTaskManager;
    private final List<AgentRuntimeHookRegistrar> hookRegistrars;
    private final List<OrcaToolProvider> toolProviders;
    private final List<OrcaCommandProvider> commandProviders;
    private final ConcurrentMap<String, Object> contextLocks = new ConcurrentHashMap<>();

    private OrcaAgentRuntimeManager(Builder builder) {
        this.agentExecutor = Objects.requireNonNull(builder.agentExecutor, "Agent executor cannot be null");
        this.agentRuntimeRegistry = Objects.requireNonNull(builder.agentRuntimeRegistry,
                "Context registry cannot be null");
        this.agentRuntimeFactory = Objects.requireNonNull(builder.agentRuntimeFactory,
                "Context factory cannot be null");
        this.scheduledTaskManager = builder.scheduledTaskManager;
        this.hookRegistrars = Collections.unmodifiableList(builder.hookRegistrars);
        this.toolProviders = Collections.unmodifiableList(builder.toolProviders);
        this.commandProviders = Collections.unmodifiableList(builder.commandProviders);
    }

    /**
     * Returns the agent-scoped runtime for {@code agentBundle}, creating and registering it on first call.
     *
     * <p>
     * The id is derived as {@code AgentRuntimeId.from(agentBundle.getAgent())} so the same agent always maps
     * to the same context across the application lifetime.
     *
     * <p>
     * Uses per-id double-checked locking to prevent duplicate creation for the same id while allowing parallel
     * creation for different ids. The {@code hookRegistrars} are applied exactly once per id — repeated invocations
     * for the same {@code (agent, discriminator)} pair return the same instance without re-registering hooks
     * (idempotent).
     *
     * @param agentBundle
     *            the agent bundle containing agent and optional registries (must not be null)
     * @param fileSystem
     *            the virtual file system for the context (must not be null)
     * @param credentialStore
     *            the credential store for reference-based credential resolution (may be null)
     * @return the existing or newly created agent runtime
     * @throws NullPointerException
     *             if {@code agentBundle} or {@code fileSystem} is null
     */
    public OrcaAgentRuntime getOrCreateRuntime(AgentBundle agentBundle, VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        Objects.requireNonNull(agentBundle, "Agent bundle cannot be null");
        Objects.requireNonNull(fileSystem, "File system cannot be null");
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.from(agentBundle.getAgent());
        return getOrCreateInternal(agentRuntimeId, agentBundle, fileSystem, credentialStore);
    }

    /**
     * Returns the agent-scoped runtime for {@code (agentBundle, discriminator)}, creating and registering
     * it on first call.
     *
     * <p>
     * The id is derived as {@code AgentRuntimeId.from(agentBundle.getAgent(), discriminator)} so a single
     * agent definition can be split into separate contexts (per tenant / environment / user). The
     * {@code discriminator} must satisfy the same constraints as
     * {@link AgentRuntimeId#from(at.aimon.core.agent.Agent, String)} (non-blank, no {@code ':'}).
     *
     * @param agentBundle
     *            the agent bundle containing agent and optional registries (must not be null)
     * @param discriminator
     *            the discriminator suffix attached to the agent name in the id (must be non-blank and must not
     *            contain {@code ':'})
     * @param fileSystem
     *            the virtual file system for the context (must not be null)
     * @param credentialStore
     *            the credential store for reference-based credential resolution (may be null)
     * @return the existing or newly created agent runtime
     * @throws NullPointerException
     *             if {@code agentBundle} or {@code fileSystem} is null
     * @throws IllegalArgumentException
     *             if {@code discriminator} is null, blank, or contains {@code ':'}
     */
    public OrcaAgentRuntime getOrCreateRuntime(AgentBundle agentBundle, String discriminator,
            VirtualFileSystem fileSystem, CredentialStore credentialStore) {
        Objects.requireNonNull(agentBundle, "Agent bundle cannot be null");
        Objects.requireNonNull(fileSystem, "File system cannot be null");
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.from(agentBundle.getAgent(), discriminator);
        return getOrCreateInternal(agentRuntimeId, agentBundle, fileSystem, credentialStore);
    }

    private OrcaAgentRuntime getOrCreateInternal(AgentRuntimeId agentRuntimeId, AgentBundle agentBundle,
            VirtualFileSystem fileSystem, CredentialStore credentialStore) {
        OrcaAgentRuntime existing = findRuntime(agentRuntimeId);
        if (existing != null) {
            return existing;
        }

        return withRuntimeLock(agentRuntimeId, () -> {
            OrcaAgentRuntime current = findRuntime(agentRuntimeId);
            if (current != null) {
                return current;
            }

            log.debug("Creating agent runtime: {}", agentRuntimeId);

            OrcaAgentRuntime newContext = agentRuntimeFactory.create(agentRuntimeId, agentExecutor,
                    scheduledTaskManager, agentBundle, fileSystem, credentialStore, toolProviders, commandProviders);

            for (AgentRuntimeHookRegistrar registrar : hookRegistrars) {
                registrar.register(newContext.getHookRegistry());
            }

            agentRuntimeRegistry.register(newContext);

            log.debug("Agent runtime created and registered: {}", agentRuntimeId);
            return newContext;
        });
    }

    /**
     * Looks up an existing agent runtime by its ID.
     *
     * @param agentRuntimeId
     *            the agent runtime ID
     * @return the agent runtime, or empty if not found
     * @throws NullPointerException
     *             if agentRuntimeId is null
     */
    public Optional<OrcaAgentRuntime> getRuntime(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "Agent runtime ID cannot be null");
        return Optional.ofNullable(findRuntime(agentRuntimeId));
    }

    /**
     * Destroys an agent runtime by unregistering and closing it.
     *
     * <p>
     * Because contexts are <b>agent-scoped</b> and shared across every session against the same agent, this
     * method must be called only on <b>application shutdown</b> or on an <b>explicit agent removal</b> (e.g.,
     * hot-reload that drops an agent definition). Per-session / per-session teardown must <i>not</i> call
     * destroyRuntime — sessions never own the context.
     *
     * <p>
     * If no context exists for the given ID, this method does nothing.
     *
     * <p>
     * This is safe to run concurrently with
     * {@link #getOrCreateRuntime(AgentBundle, VirtualFileSystem, CredentialStore) getOrCreateRuntime} for the same
     * id — a creator arriving during the destroy waits for it and then creates a fresh runtime. What it is
     * <i>not</i> safe against is unchanged: the caller must know that no live session is still using the runtime,
     * because this closes it out from under whoever holds it.
     *
     * @param agentRuntimeId
     *            the agent runtime ID to destroy
     * @throws NullPointerException
     *             if agentRuntimeId is null
     */
    public void destroyRuntime(AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(agentRuntimeId, "Agent runtime ID cannot be null");

        inRuntimeLock(agentRuntimeId, () -> {
            OrcaAgentRuntime context = findRuntime(agentRuntimeId);
            if (context != null) {
                agentRuntimeRegistry.unregister(agentRuntimeId);
                try {
                    context.close();
                } catch (Exception e) {
                    log.warn("Failed to close agent runtime {}: {}", agentRuntimeId, e.getMessage(), e);
                }
                log.debug("Agent runtime destroyed: {}", agentRuntimeId);
            }
            // Must stay the LAST statement under the monitor: retiring the entry hands the id back to whoever
            // arrives next, so anything done after this point would run outside the exclusion it just gave up.
            contextLocks.remove(agentRuntimeId.value());
        });
    }

    /**
     * Runs {@code action} under the monitor for {@code agentRuntimeId}, holding a monitor that the map still
     * hands out for that id.
     *
     * <p>
     * The re-check is the whole point. {@link #destroyRuntime(AgentRuntimeId)} retires the monitor while holding
     * it, and it has to — this map is keyed by runtime id, so on the tenant axis destroy is the only place it ever
     * shrinks. But that means a thread can acquire a monitor and wake up holding one the map has since replaced,
     * and two threads on two different monitors are not mutually excluded at all. Note that this is not a
     * nanosecond-wide window: a creator does not race the destroyer, it <b>waits behind it</b> for however long
     * {@code close()} takes (MCP shutdown, seconds), so arrivals in that window are the expected case rather than
     * the unlucky one.
     *
     * <p>
     * Validating the monitor after acquiring it — and retrying with the current one when it has gone stale — is
     * what makes both properties hold at once: the map keeps shrinking, and exclusion survives the shrink. A
     * single manager-wide lock would also be correct, but it would serialise every tenant's cold start behind
     * every other's, which is exactly what per-id locking exists to avoid.
     *
     * @param <T>
     *            the action's result type
     * @param agentRuntimeId
     *            the id whose monitor guards the action
     * @param action
     *            the action to run under the monitor
     * @return whatever {@code action} returns
     */
    private <T> T withRuntimeLock(AgentRuntimeId agentRuntimeId, Supplier<T> action) {
        final String key = agentRuntimeId.value();
        while (true) {
            final Object lock = contextLocks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                if (contextLocks.get(key) != lock) {
                    // Retired while we waited — the monitor we hold no longer guards this id. Take the new one.
                    continue;
                }
                return action.get();
            }
        }
    }

    private void inRuntimeLock(AgentRuntimeId agentRuntimeId, Runnable action) {
        withRuntimeLock(agentRuntimeId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Returns how many per-id monitors are currently retained — visible for testing.
     *
     * <p>
     * This map is keyed by runtime id, so on the tenant axis it grows with the tenant count and
     * {@link #destroyRuntime(AgentRuntimeId)} is the only place it ever shrinks. The count is exposed so that
     * property can be asserted directly: an implementation that stopped retiring monitors would still pass every
     * behavioural test while leaking one entry per destroyed runtime.
     *
     * @return the number of ids with a retained monitor
     */
    int trackedLockCount() {
        return contextLocks.size();
    }

    private OrcaAgentRuntime findRuntime(AgentRuntimeId agentRuntimeId) {
        return agentRuntimeRegistry.getAs(agentRuntimeId, OrcaAgentRuntime.class).orElse(null);
    }

    public static class Builder {

        private OrcaAgentExecutor agentExecutor;
        private AgentRuntimeRegistry agentRuntimeRegistry;
        private OrcaAgentRuntimeFactory agentRuntimeFactory;
        private ScheduledTaskManager scheduledTaskManager;
        private List<AgentRuntimeHookRegistrar> hookRegistrars;
        private List<OrcaToolProvider> toolProviders;
        private List<OrcaCommandProvider> commandProviders;

        /** OrcaAgentExecutor를 설정한다. */
        public Builder agentExecutor(OrcaAgentExecutor agentExecutor) {
            this.agentExecutor = agentExecutor;
            return this;
        }

        /** AgentRuntimeRegistry를 설정한다. */
        public Builder agentRuntimeRegistry(AgentRuntimeRegistry agentRuntimeRegistry) {
            this.agentRuntimeRegistry = agentRuntimeRegistry;
            return this;
        }

        /** OrcaAgentRuntimeFactory를 설정한다. */
        public Builder agentRuntimeFactory(OrcaAgentRuntimeFactory agentRuntimeFactory) {
            this.agentRuntimeFactory = agentRuntimeFactory;
            return this;
        }

        /** ScheduledTaskManager를 설정한다 (nullable). */
        public Builder scheduledTaskManager(ScheduledTaskManager scheduledTaskManager) {
            this.scheduledTaskManager = scheduledTaskManager;
            return this;
        }

        /** AgentRuntimeHookRegistrar 목록을 설정한다. */
        public Builder hookRegistrars(List<AgentRuntimeHookRegistrar> hookRegistrars) {
            this.hookRegistrars = hookRegistrars;
            return this;
        }

        /** OrcaToolProvider 목록을 설정한다. */
        public Builder toolProviders(List<OrcaToolProvider> toolProviders) {
            this.toolProviders = toolProviders;
            return this;
        }

        /** OrcaCommandProvider 목록을 설정한다. */
        public Builder commandProviders(List<OrcaCommandProvider> commandProviders) {
            this.commandProviders = commandProviders;
            return this;
        }

        /** OrcaAgentRuntimeManager를 생성한다. */
        public OrcaAgentRuntimeManager build() {
            if (agentRuntimeRegistry == null) {
                agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
            }
            if (agentRuntimeFactory == null) {
                agentRuntimeFactory = new OrcaAgentRuntimeFactory();
            }
            if (hookRegistrars == null) {
                hookRegistrars = List.of();
            }
            if (toolProviders == null) {
                toolProviders = OrcaAgentRuntimeFactory.defaultToolProviders();
            }
            if (commandProviders == null) {
                commandProviders = OrcaAgentRuntimeFactory.defaultCommandProviders();
            }
            return new OrcaAgentRuntimeManager(this);
        }
    }
}
