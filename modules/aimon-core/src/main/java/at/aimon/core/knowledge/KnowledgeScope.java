package at.aimon.core.knowledge;

import java.util.Objects;

/**
 * Identifies the owner of indexed knowledge for multi-tenant isolation.
 *
 * <p>
 * Each indexed document is tagged with an agent name and context ID. During search, these fields are used as mandatory
 * filters to ensure agents only see their own data.
 *
 * <p>
 * Filtering modes (controlled by {@link SearchQuery#isCrossContext()}):
 * <ul>
 * <li>Default: filters by both {@code agentName} and {@code contextId}
 * <li>Cross-context: filters by {@code agentName} only — returns data from all contexts of this agent
 * </ul>
 *
 * <pre>{@code
 * KnowledgeScope scope = new KnowledgeScope("ops-agent", "ctx-abc123");
 * store.index("/knowledge", IndexOptions.defaults(), scope);
 * store.search(query, scope);
 * }</pre>
 *
 * @see KnowledgeStore
 * @see SearchQuery#isCrossContext()
 */
public final class KnowledgeScope {

    private final String agentName;
    private final String contextId;

    /**
     * Creates a knowledge scope.
     *
     * @param agentName
     *            the agent name for document isolation (must not be null or empty)
     * @param contextId
     *            the agent runtime ID for document isolation (must not be null or empty)
     */
    public KnowledgeScope(String agentName, String contextId) {
        this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
        if (agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be empty");
        }
        this.contextId = Objects.requireNonNull(contextId, "contextId must not be null");
        if (contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be empty");
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KnowledgeScope that)) {
            return false;
        }
        return agentName.equals(that.agentName) && contextId.equals(that.contextId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentName, contextId);
    }

    @Override
    public String toString() {
        return "KnowledgeScope{agent='" + agentName + "', context='" + contextId + "'}";
    }
}
