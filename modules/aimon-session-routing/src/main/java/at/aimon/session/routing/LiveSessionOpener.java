package at.aimon.session.routing;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionFactory;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.OpenAttributes;
import at.aimon.core.agent.session.SessionId;

/**
 * Strategy used by {@link LiveSessionCache} to lazily open a {@link LiveSession}.
 *
 * <p>
 * Production wiring chooses one of two paths:
 *
 * <ul>
 * <li>{@code SessionRouterBuilder.sessionFactory(...)} — adapts
 * {@link LiveSessionFactory#open(SessionId, String, LiveSessionOptions)} into this opener; the manager extracts
 * the agent name from {@link AgentRuntimeId#agentName()} for the factory call. {@link OpenAttributes} flowing
 * through is ignored. This is the simple-case path used by aimon-cli and the example modules.
 * <li>{@code SessionRouterBuilder.sessionOpener(...)} — supplies a caller-defined opener directly so the
 * implementation can read application-level attributes (e.g., tenant id, organization unit) from the
 * {@link OpenAttributes} that the caller attached to the {@code SubmitRequest}.
 * </ul>
 *
 * <p>
 * Tests can supply a fake opener to substitute controllable {@link LiveSession} instances without standing up a real
 * {@code OrcaAgentExecutor}.
 *
 * <h2>AgentRuntime lifecycle (IMPORTANT)</h2>
 *
 * <p>
 * {@code AgentRuntime} is <strong>agent-scoped</strong> (one instance per {@code (Agent, discriminator)};
 * see {@code docs/design/agent-execution/agent-runtime-scope.md}). The {@link AgentRuntimeId}
 * threaded into
 * each {@link #open} call is derived by the manager from {@code SubmitRequest.agentRef} and an optional
 * {@code SubmitRequest.contextDiscriminator}; the opener's job is to <em>look up</em> the matching pre-registered
 * context and bind it to a fresh {@link LiveSession}, not to register one lazily. Implementations <strong>must
 * not</strong> create a fresh {@code AgentRuntime} per {@link #open} call. The application must register the
 * context once at bootstrap via {@code OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)} (optionally
 * with a discriminator) and reuse the same instance for every cache miss.
 *
 * <p>
 * The returned {@link LiveSession#close()} does not close the {@code AgentRuntime}. Closing the context is
 * the application owner's responsibility and must happen only at agent removal or application shutdown — typically
 * via {@code OrcaAgentRuntimeManager#destroyRuntime(id)}. Neither this opener nor
 * {@code SessionRouter#close()} closes those agent-scoped resources, so MCP clients leak if the owner
 * forgets.
 *
 * <h2>Canonical implementation</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     LiveSession open(SessionId convId, AgentRuntimeId ctxId, LiveSessionOptions options,
 *             OpenAttributes attrs) {
 *         AgentRuntime ctx = agentRuntimeRegistry.get(ctxId)
 *                 .orElseThrow(() -> new IllegalStateException("AEC not bootstrapped for " + ctxId));
 *         // attrs carries any additional caller-domain metadata (ops.agentId, ops.ouId, ...).
 *         return sessionFactory.open(convId, ctx, options);
 *     }
 * }
 * </pre>
 *
 * <p>
 * Each {@code (agent, discriminator)} pair must be registered once at bootstrap (via
 * {@code OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, discriminator, ...)}) before the first submit
 * that needs it; the opener does not register lazily.
 *
 * <h2>Hooks hot reload (optional)</h2>
 *
 * <p>
 * Web bootstraps that want {@code hooks.json} edits to propagate to the live registry without restarting can call
 * {@code at.aimon.core.config.hook.HookHotReloadBootstrap.builder()...start()} once at startup, alongside the
 * {@code OrcaAgentRuntimeManager.getOrCreateRuntime} call. The returned {@code Started} handle is
 * application-scoped and must be closed at shutdown so the polling thread exits cleanly. CLI uses the same helper —
 * see {@code aimon-cli/AgentSetupFactory#setupHookHotReload} for a reference call shape.
 *
 * <h2>Re-open semantics</h2>
 *
 * <p>
 * The opener is invoked on cache miss only. While a session is cached on the holder node, subsequent submits with
 * different {@link OpenAttributes} have no effect on the open session. See {@link OpenAttributes} for the full
 * contract.
 */
@FunctionalInterface
public interface LiveSessionOpener {

    /**
     * Opens a session bound to {@code sessionId} and {@code agentRuntimeId}, using {@code openAttributes} as the
     * caller-provided attribute channel.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param agentRuntimeId
     *            the agent-scoped runtime id to bind on first open (must not be null). Derived by the
     *            manager from {@code SubmitRequest.agentRef} and an optional
     *            {@code SubmitRequest.contextDiscriminator} via
     *            {@link AgentRuntimeId#fromName(String, String)}.
     * @param options
     *            session options (must not be null)
     * @param openAttributes
     *            caller-provided attributes; {@link OpenAttributes#empty()} when the caller did not attach any (must
     *            not be null)
     * @return the opened session (never null)
     */
    LiveSession open(SessionId sessionId, AgentRuntimeId agentRuntimeId, LiveSessionOptions options,
            OpenAttributes openAttributes);

    /**
     * Convenience overload that delegates to
     * {@link #open(SessionId, AgentRuntimeId, LiveSessionOptions, OpenAttributes)} with
     * {@link OpenAttributes#empty()}.
     *
     * <p>
     * Provided so simple callers (and adapters from {@link LiveSessionFactory}) can ignore the attribute channel
     * entirely.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param agentRuntimeId
     *            the agent-scoped runtime id (must not be null)
     * @param options
     *            session options (must not be null)
     * @return the opened session (never null)
     */
    default LiveSession open(SessionId sessionId, AgentRuntimeId agentRuntimeId, LiveSessionOptions options) {
        return open(sessionId, agentRuntimeId, options, OpenAttributes.empty());
    }
}
