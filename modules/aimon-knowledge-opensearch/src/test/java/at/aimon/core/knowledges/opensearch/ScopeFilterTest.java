package at.aimon.core.knowledges.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.Query;

class ScopeFilterTest {

    @Test
    void shouldCreateWithValidValues() {
        final ScopeFilter filter = new ScopeFilter("ops-agent", "ctx-123");
        assertThat(filter.getAgentName()).isEqualTo("ops-agent");
        assertThat(filter.getContextId()).isEqualTo("ctx-123");
    }

    @Test
    void shouldRejectNullAgentName() {
        assertThatThrownBy(() -> new ScopeFilter(null, "ctx-123")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyAgentName() {
        assertThatThrownBy(() -> new ScopeFilter("", "ctx-123")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentName");
    }

    @Test
    void shouldRejectNullContextId() {
        assertThatThrownBy(() -> new ScopeFilter("ops-agent", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectEmptyContextId() {
        assertThatThrownBy(() -> new ScopeFilter("ops-agent", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextId");
    }

    @Test
    void toFilterQueriesShouldReturnBothFiltersWhenNotCrossContext() {
        final ScopeFilter filter = new ScopeFilter("ops-agent", "ctx-123");
        final List<Query> queries = filter.toFilterQueries(false);

        assertThat(queries).hasSize(2);
    }

    @Test
    void toFilterQueriesTargetsTheFrozenContextIdFieldName() {
        // The read half of the freeze. toFilterQueriesShouldReturnBothFiltersWhenNotCrossContext above only counts the
        // filters — it never looks at which field they target, so it survives any rename. The name matters more here
        // than anywhere else in the codebase: documents indexed before a rename keep "context_id" and simply stop
        // matching a filter on the new name. Nothing throws; scoped search just returns an empty result set, which
        // reads as "no relevant knowledge" rather than as a bug.
        final ScopeFilter filter = new ScopeFilter("ops-agent", "ctx-123");

        final List<Query> queries = filter.toFilterQueries(false);

        assertThat(queries).anySatisfy(query -> {
            assertThat(query.isTerm()).isTrue();
            assertThat(query.term().field()).isEqualTo("context_id");
            assertThat(query.term().value().stringValue()).isEqualTo("ctx-123");
        });
    }

    @Test
    void toFilterQueriesShouldReturnOnlyAgentNameWhenCrossContext() {
        final ScopeFilter filter = new ScopeFilter("ops-agent", "ctx-123");
        final List<Query> queries = filter.toFilterQueries(true);

        assertThat(queries).hasSize(1);
    }

    @Test
    void toStringShouldContainBothFields() {
        final ScopeFilter filter = new ScopeFilter("ops-agent", "ctx-123");
        assertThat(filter.toString()).contains("ops-agent");
        assertThat(filter.toString()).contains("ctx-123");
    }
}
