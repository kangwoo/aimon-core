package at.aimon.core.agent.session;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.AgentExecutor;
import at.aimon.core.agent.AgentRegistry;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionRequest;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.store.SessionRecordStore;

/**
 * Factory for opening {@link LiveSession} instances.
 *
 * <p>
 * {@code LiveSessionFactory} is the SESSION-02 entry point: application code resolves an agent by name through an
 * {@link AgentRegistry}, obtains the <b>agent-scoped</b> {@link OrcaAgentRuntime} for it via a caller-supplied
 * {@link ContextBuilder} (typically delegating to {@code OrcaAgentRuntimeManager.getOrCreateRuntime(...)}),
 * and hands both to a long-lived {@link OrcaAgentExecutor}. The resulting {@link LiveSession} reuses the same
 * {@link SessionId} for every turn, preserving the transcript across submits, while the underlying
 * agent runtime is shared across every concurrent session that targets the same agent.
 *
 * <p>
 * The factory itself is stateless in terms of session identity — each call to
 * {@link #open(SessionId, String, LiveSessionOptions)} produces an independent {@link LiveSession}. The
 * collaborators ({@code AgentRegistry}, {@code ContextBuilder}, {@code OrcaAgentExecutor}) are expected to be
 * application-scoped: the factory does not close them when a session is closed, and a single executor can back many
 * concurrent sessions.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaAgentExecutor executor = executorFactory.create(llmClient, transcriptManager);
 *     // contextManager owns the agent-scoped AEC, registers it once at bootstrap, and returns the same instance for
 *     // every subsequent session against the same agent.
 *     LiveSessionFactory factory = new LiveSessionFactory(agentRegistry,
 *             agent -> contextManager.getOrCreateRuntime(bundleFor(agent), fileSystem, credentialStore), executor);
 *
 *     try (LiveSession session = factory.open(SessionId.generate(), "default", LiveSessionOptions.defaults())) {
 *         AgentExecutionResult turn1 = session.submit("Hello");
 *         AgentExecutionResult turn2 = session.submit("What did I just say?");
 *     }
 * }
 * </pre>
 *
 * <h2>Scheduling lifecycle</h2>
 *
 * <p>
 * Per {@code CLAUDE.md} "Scheduling Lifecycle", application-scoped scheduling components (e.g.,
 * {@code SchedulingEngine}) must outlive any individual session. Neither this factory nor the returned session ever
 * closes such components — all lifecycle of the {@link OrcaAgentExecutor} and its scheduling infrastructure remains the
 * responsibility of the caller.
 *
 * @see LiveSession
 * @see DefaultLiveSession
 * @see LiveSessionOptions
 */
public final class LiveSessionFactory {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionFactory.class);

    /**
     * Strategy interface that returns the <b>agent-scoped</b>
     * {@link at.aimon.core.agent.impl.orca.OrcaAgentRuntime} for a resolved {@link Agent}.
     *
     * <p>
     * Callers typically implement this as a lambda that delegates to
     * {@code OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, fs, store)} (or the discriminator overload)
     * so the same context instance is returned for every session targeting the same agent. Implementations must be
     * idempotent and must not allocate a fresh context per call — context-scoped resources (MCP connections, hook
     * registrations) are deliberately shared across sessions.
     */
    @FunctionalInterface
    public interface ContextBuilder {

        /**
         * Returns the agent-scoped {@link OrcaAgentRuntime} for {@code agent}.
         *
         * @param agent
         *            the resolved agent (never null)
         * @return the agent-scoped runtime (never null)
         */
        OrcaAgentRuntime build(Agent agent);
    }

    private final AgentRegistry agentRegistry;
    private final ContextBuilder contextBuilder;
    // @formatter:off
    private final AgentExecutor<
            OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor;
    // @formatter:on
    private final SessionRecordStore sessionRecords;

    /**
     * Creates a new factory whose sessions run without durable session state.
     *
     * <p>
     * Equivalent to
     * {@link #LiveSessionFactory(AgentRegistry, ContextBuilder, AgentExecutor, SessionRecordStore)} with a
     * {@code null} repository: opened sessions keep their {@code sessionTotals} and runtime budget override purely
     * in-memory.
     *
     * @param agentRegistry
     *            the agent registry used to resolve the {@code agentRef} passed to
     *            {@link #open(SessionId, String, LiveSessionOptions)} (must not be null)
     * @param contextBuilder
     *            the strategy that builds (or returns the already-cached) <b>agent-scoped</b>
     *            {@link OrcaAgentRuntime} for each opened session (must not be null). Implementations must be
     *            idempotent and must <i>not</i> allocate a fresh context per session — the same instance is shared
     *            across all sessions targeting the same agent.
     * @param executor
     *            the application-scoped agent executor used to run every turn (must not be null). The factory does
     *            <b>not</b> take ownership — its lifecycle is the caller's responsibility.
     * @throws NullPointerException
     *             if any argument is null
     */
    public LiveSessionFactory(AgentRegistry agentRegistry, ContextBuilder contextBuilder,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor) {
        this(agentRegistry, contextBuilder, executor, null);
    }

    /**
     * Creates a new factory whose opened sessions hydrate their durable state from — and write it back to — the
     * session records held by the supplied {@link SessionRecordStore}.
     *
     * @param agentRegistry
     *            the agent registry used to resolve the {@code agentRef} passed to
     *            {@link #open(SessionId, String, LiveSessionOptions)} (must not be null)
     * @param contextBuilder
     *            the strategy that builds (or returns the already-cached) <b>agent-scoped</b>
     *            {@link OrcaAgentRuntime} for each opened session (must not be null). Implementations must be
     *            idempotent and must <i>not</i> allocate a fresh context per session — the same instance is shared
     *            across all sessions targeting the same agent.
     * @param executor
     *            the application-scoped agent executor used to run every turn (must not be null). The factory does
     *            <b>not</b> take ownership — its lifecycle is the caller's responsibility.
     * @param sessionRecords
     *            the store holding the durable session records, injected into every opened session, or
     *            {@code null} to run without durable state. The factory does <b>not</b> take ownership.
     * @throws NullPointerException
     *             if any argument other than {@code sessionRecords} is null
     */
    public LiveSessionFactory(AgentRegistry agentRegistry, ContextBuilder contextBuilder,
            AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor,
            SessionRecordStore sessionRecords) {
        this.agentRegistry = Objects.requireNonNull(agentRegistry, "agentRegistry must not be null");
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.sessionRecords = sessionRecords;
    }

    /**
     * Opens a new {@link LiveSession} bound to {@code sessionId} and the agent named {@code agentRef}.
     *
     * <p>
     * The factory resolves {@code agentRef} through the injected {@link AgentRegistry}, obtains the
     * <b>agent-scoped</b> {@link OrcaAgentRuntime} via {@link ContextBuilder#build(Agent)} (typically a
     * lookup against {@code OrcaAgentRuntimeManager}, returning a shared instance), and wraps them in a
     * {@link DefaultLiveSession}. The session does not own the context — context teardown is the bootstrap's
     * responsibility.
     *
     * @param sessionId
     *            the session id the handle is bound to (must not be null)
     * @param agentRef
     *            the agent name as registered in the {@link AgentRegistry} (must not be null)
     * @param options
     *            the session options; use {@link LiveSessionOptions#defaults()} for the defaults (must not be null)
     * @return a new {@link LiveSession} (never null)
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if no agent is registered under {@code agentRef}
     */
    public LiveSession open(SessionId sessionId, String agentRef, LiveSessionOptions options) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(agentRef, "agentRef must not be null");
        Objects.requireNonNull(options, "options must not be null");

        final Agent agent = agentRegistry.findByName(agentRef)
                .orElseThrow(() -> new IllegalArgumentException("No agent registered under name: " + agentRef));

        final OrcaAgentRuntime context = Objects.requireNonNull(contextBuilder.build(agent),
                "contextBuilder returned null context");

        log.debug("Opening LiveSession(sessionId={}, agent={}, sourceAgentId={})", sessionId, agent.getName(),
                options.getSourceAgentId().orElse("<unset>"));
        return new DefaultLiveSession(sessionId, context, executor, options, null, null, sessionRecords);
    }
}
