package at.aimon.core.knowledge.wiki;

import java.util.Objects;

/**
 * Identifies the owner of a wiki knowledge base for multi-tenant isolation.
 *
 * <p>
 * Each wiki is scoped by an agent name, context ID, and wiki name. The agent name isolates data between agents, the
 * context ID isolates per execution context, and the wiki name allows multiple wikis within the same agent/context
 * (e.g., "research", "ops-runbook").
 *
 * <pre>{@code
 * WikiScope scope = new WikiScope("ops-agent", "ctx-abc123", "runbook-wiki");
 * wiki.ingest(scope, source, IngestOptions.defaults());
 * wiki.search(scope, query);
 * }</pre>
 *
 * @see WikiKnowledgeBase
 */
public final class WikiScope {

    /** Metadata/tag key for the agent name component of a {@link WikiScope}. */
    public static final String TAG_AGENT = "wiki.agent";

    /** Metadata/tag key for the context ID component of a {@link WikiScope}. */
    public static final String TAG_CONTEXT = "wiki.context";

    /** Metadata/tag key for the wiki name component of a {@link WikiScope}. */
    public static final String TAG_NAME = "wiki.name";

    private final String agentName;
    private final String contextId;
    private final String wikiName;

    /**
     * Creates a wiki scope.
     *
     * @param agentName
     *            the agent name for wiki isolation (must not be null or empty)
     * @param contextId
     *            the agent runtime ID for wiki isolation (must not be null or empty)
     * @param wikiName
     *            the wiki name within the agent/context (must not be null or empty)
     */
    public WikiScope(String agentName, String contextId, String wikiName) {
        this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
        if (agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be empty");
        }
        this.contextId = Objects.requireNonNull(contextId, "contextId must not be null");
        if (contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be empty");
        }
        this.wikiName = Objects.requireNonNull(wikiName, "wikiName must not be null");
        if (wikiName.isEmpty()) {
            throw new IllegalArgumentException("wikiName must not be empty");
        }
    }

    /**
     * Returns the agent name.
     *
     * @return the agent name (never null or empty)
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * Returns the agent runtime ID.
     *
     * @return the context ID (never null or empty)
     */
    public String getContextId() {
        return contextId;
    }

    /**
     * Returns the wiki name.
     *
     * @return the wiki name (never null or empty)
     */
    public String getWikiName() {
        return wikiName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WikiScope that)) {
            return false;
        }
        return agentName.equals(that.agentName) && contextId.equals(that.contextId) && wikiName.equals(that.wikiName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentName, contextId, wikiName);
    }

    @Override
    public String toString() {
        return "WikiScope{agent='" + agentName + "', context='" + contextId + "', wiki='" + wikiName + "'}";
    }
}
