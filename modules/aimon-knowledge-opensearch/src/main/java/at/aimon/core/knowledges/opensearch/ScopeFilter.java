package at.aimon.core.knowledges.opensearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermQuery;

/**
 * Immutable scope filter for multi-tenant document isolation within a shared OpenSearch index.
 *
 * <p>
 * Each document is tagged with {@code agent_name} and {@code context_id} at indexing time. During search, these fields
 * are used as mandatory term filters to ensure agents only see their own data.
 *
 * <p>
 * Scope filtering modes:
 * <ul>
 * <li>Default: filters by both {@code agent_name} and {@code context_id}
 * <li>{@code crossContext=true}: filters by {@code agent_name} only — returns data from all contexts of this agent
 * </ul>
 *
 * @see OpenSearchKnowledgeStore
 */
public final class ScopeFilter {

    private final String agentName;
    private final String contextId;

    /**
     * Creates a scope filter.
     *
     * @param agentName
     *            the agent name for document isolation (must not be null or empty)
     * @param contextId
     *            the agent runtime ID for document isolation (must not be null or empty)
     */
    public ScopeFilter(String agentName, String contextId) {
        this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
        if (agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be empty");
        }
        this.contextId = Objects.requireNonNull(contextId, "contextId must not be null");
        if (contextId.isEmpty()) {
            throw new IllegalArgumentException("contextId must not be empty");
        }
    }

    public String getAgentName() {
        return agentName;
    }

    public String getContextId() {
        return contextId;
    }

    /**
     * Builds OpenSearch term filter queries for the given cross-context mode.
     *
     * @param crossContext
     *            if true, only filters by agent_name (ignores context_id)
     * @return list of term filter queries to apply; never null or empty
     */
    List<Query> toFilterQueries(boolean crossContext) {
        final List<Query> filters = new ArrayList<>(2);

        filters.add(new Query.Builder().term(new TermQuery.Builder().field(OpenSearchDocumentMapper.FIELD_AGENT_NAME)
                .value(v -> v.stringValue(agentName)).build()).build());

        if (!crossContext) {
            filters.add(
                    new Query.Builder().term(new TermQuery.Builder().field(OpenSearchDocumentMapper.FIELD_CONTEXT_ID)
                            .value(v -> v.stringValue(contextId)).build()).build());
        }

        return filters;
    }

    @Override
    public String toString() {
        return "ScopeFilter{agent='" + agentName + "', context='" + contextId + "'}";
    }
}
