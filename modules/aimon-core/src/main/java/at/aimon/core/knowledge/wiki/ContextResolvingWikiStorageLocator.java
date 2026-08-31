package at.aimon.core.knowledge.wiki;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Generic {@link WikiStorageLocator} that resolves the {@link VirtualFileSystem} for each scope through a caller-
 * supplied lookup function keyed by {@link AgentRuntimeId}.
 *
 * <p>
 * This class is the standard adapter for the common pattern "the wiki's filesystem is owned by the agent execution
 * context, look it up by the scope's agentRuntimeId". It knows nothing about which agent runtime owns the context —
 * that is
 * the resolver's job. Typical resolvers extract the VFS from a registry of agent runtimes:
 *
 * <pre>{@code
 * // Generic — works with any agent context implementation that exposes a VFS:
 * Function<AgentRuntimeId, Optional<VirtualFileSystem>> resolver =
 *         id -> registry.getAs(id, MyAgentRuntime.class).map(MyAgentRuntime::getFileSystem);
 *
 * WikiStorageLocator locator = ContextResolvingWikiStorageLocator.defaultLayout(resolver, "/wiki");
 * DefaultWikiKnowledgeBase wiki = new DefaultWikiKnowledgeBase(locator, pageGenerator);
 * }</pre>
 *
 * <p>
 * Two layouts are provided:
 * <ul>
 * <li>{@link #defaultLayout(Function, String) defaultLayout} — {@code {root}/{agent}/{ctx}/{wiki}}
 * <li>{@link #contextScoped(Function, String) contextScoped} — {@code {root}/{ctx}/{wiki}}, dropping {@code agentName}
 * for callers that already partition storage by agent (e.g. via a per-agent VFS root)
 * </ul>
 *
 * <p>
 * <b>Resolution semantics</b>: this locator does <i>not</i> create agent runtimes. The context for the scope's
 * {@code agentRuntimeId} must already be available to the resolver before any wiki operation runs on that scope —
 * otherwise {@link #fileSystemFor(WikiScope)} throws {@link IllegalStateException}. This is intentional: a wiki write
 * needs the same VFS the agent is currently using, and creating that VFS requires inputs (agent bundle, credentials,
 * etc.) the locator does not own. The resolver is consulted lazily on every call (no caching), so context
 * re-creation between wiki operations is honored automatically.
 *
 * <p>
 * <b>Resolver contract</b>: implementations should signal "no VFS available for this agentRuntimeId" by returning
 * {@link Optional#empty()}, not by throwing. Exceptions thrown directly by the resolver propagate as-is and bypass
 * the {@code IllegalStateException} message — and because {@link DefaultWikiKnowledgeBase} only treats
 * {@code IllegalStateException} as a hard lifecycle error (other {@code RuntimeException}s are caught by the
 * soft-fail outer try/catch), a thrown resolver may be logged-and-swallowed instead of surfaced to the caller. Stick
 * to {@code Optional.empty()} for the not-found case.
 *
 * <p>
 * <b>Method coupling contract</b>: callers (notably {@link DefaultWikiKnowledgeBase}) must treat
 * {@link #directoryFor(WikiScope)} and {@link #fileSystemFor(WikiScope)} as a pair — both refer to the same physical
 * storage location for the same scope. {@code directoryFor} is a pure function of {@code wikiRoot} and the scope and
 * does <i>not</i> consult the resolver, so it will succeed even when no context is registered. Do not use
 * {@code directoryFor} in isolation as a side-channel for "where would this scope live"; pair it with
 * {@code fileSystemFor} so that {@link IllegalStateException} surfaces if the context is missing.
 *
 * <p>
 * Thread safety: this class is immutable; instances are safe for concurrent calls as long as the supplied resolver is
 * thread-safe (registry-backed resolvers typically are).
 *
 * @see WikiStorageLocator
 * @see DefaultWikiKnowledgeBase
 */
public final class ContextResolvingWikiStorageLocator implements WikiStorageLocator {

    private final Function<AgentRuntimeId, Optional<VirtualFileSystem>> contextResolver;
    private final String wikiRoot;
    private final boolean includeAgentInPath;

    private ContextResolvingWikiStorageLocator(Function<AgentRuntimeId, Optional<VirtualFileSystem>> contextResolver,
            String wikiRoot, boolean includeAgentInPath) {
        this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver must not be null");
        Objects.requireNonNull(wikiRoot, "wikiRoot must not be null");
        if (wikiRoot.isEmpty()) {
            throw new IllegalArgumentException("wikiRoot must not be empty");
        }
        this.wikiRoot = wikiRoot.endsWith("/") ? wikiRoot.substring(0, wikiRoot.length() - 1) : wikiRoot;
        this.includeAgentInPath = includeAgentInPath;
    }

    /**
     * Default layout {@code {root}/{agentName}/{agentRuntimeId}/{wikiName}}.
     *
     * @param contextResolver
     *            function returning the {@link VirtualFileSystem} for a given agent runtime id, or empty if no
     *            such context is registered (must not be null)
     * @param wikiRoot
     *            the wiki root directory (must not be null or empty)
     * @return a new locator
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if wikiRoot is empty
     */
    public static ContextResolvingWikiStorageLocator defaultLayout(
            Function<AgentRuntimeId, Optional<VirtualFileSystem>> contextResolver, String wikiRoot) {
        return new ContextResolvingWikiStorageLocator(contextResolver, wikiRoot, true);
    }

    /**
     * Compact layout {@code {root}/{agentRuntimeId}/{wikiName}} (drops {@code agentName}).
     *
     * @param contextResolver
     *            function returning the {@link VirtualFileSystem} for a given agent runtime id, or empty if no
     *            such context is registered (must not be null)
     * @param wikiRoot
     *            the wiki root directory (must not be null or empty)
     * @return a new locator
     * @throws NullPointerException
     *             if any parameter is null
     * @throws IllegalArgumentException
     *             if wikiRoot is empty
     */
    public static ContextResolvingWikiStorageLocator contextScoped(
            Function<AgentRuntimeId, Optional<VirtualFileSystem>> contextResolver, String wikiRoot) {
        return new ContextResolvingWikiStorageLocator(contextResolver, wikiRoot, false);
    }

    @Override
    public VirtualFileSystem fileSystemFor(WikiScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        final AgentRuntimeId agentRuntimeId = AgentRuntimeId.of(scope.getContextId());
        return contextResolver.apply(agentRuntimeId)
                .orElseThrow(() -> new IllegalStateException(
                        "No VirtualFileSystem could be resolved for agentRuntimeId=" + scope.getContextId() + " (scope="
                                + scope + "). The agent runtime must exist before wiki operations on this scope."));
    }

    @Override
    public String directoryFor(WikiScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        if (includeAgentInPath) {
            return wikiRoot + "/" + scope.getAgentName() + "/" + scope.getContextId() + "/" + scope.getWikiName();
        }
        return wikiRoot + "/" + scope.getContextId() + "/" + scope.getWikiName();
    }
}
