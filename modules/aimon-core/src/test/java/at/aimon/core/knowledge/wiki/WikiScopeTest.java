package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiScope")
class WikiScopeTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("valid arguments create instance with expected fields")
        void validConstruction() {
            WikiScope scope = new WikiScope("ops-agent", "ctx-abc123", "runbook-wiki");

            assertThat(scope.getAgentName()).isEqualTo("ops-agent");
            assertThat(scope.getContextId()).isEqualTo("ctx-abc123");
            assertThat(scope.getWikiName()).isEqualTo("runbook-wiki");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null agentName throws NullPointerException")
        void nullAgentNameThrowsNpe() {
            assertThatThrownBy(() -> new WikiScope(null, "ctx", "wiki")).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty agentName throws IllegalArgumentException")
        void emptyAgentNameThrowsIae() {
            assertThatThrownBy(() -> new WikiScope("", "ctx", "wiki")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null contextId throws NullPointerException")
        void nullContextIdThrowsNpe() {
            assertThatThrownBy(() -> new WikiScope("agent", null, "wiki")).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty contextId throws IllegalArgumentException")
        void emptyContextIdThrowsIae() {
            assertThatThrownBy(() -> new WikiScope("agent", "", "wiki")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null wikiName throws NullPointerException")
        void nullWikiNameThrowsNpe() {
            assertThatThrownBy(() -> new WikiScope("agent", "ctx", null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty wikiName throws IllegalArgumentException")
        void emptyWikiNameThrowsIae() {
            assertThatThrownBy(() -> new WikiScope("agent", "ctx", "")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("same values are equal and have same hash code")
        void sameValuesAreEqual() {
            WikiScope a = new WikiScope("agent", "ctx", "wiki");
            WikiScope b = new WikiScope("agent", "ctx", "wiki");

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("different agentName produces inequality")
        void differentAgentNameNotEqual() {
            WikiScope a = new WikiScope("agent-a", "ctx", "wiki");
            WikiScope b = new WikiScope("agent-b", "ctx", "wiki");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different contextId produces inequality")
        void differentContextIdNotEqual() {
            WikiScope a = new WikiScope("agent", "ctx-a", "wiki");
            WikiScope b = new WikiScope("agent", "ctx-b", "wiki");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("different wikiName produces inequality")
        void differentWikiNameNotEqual() {
            WikiScope a = new WikiScope("agent", "ctx", "wiki-a");
            WikiScope b = new WikiScope("agent", "ctx", "wiki-b");

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringMethod {

        @Test
        @DisplayName("contains agentName, contextId and wikiName")
        void toStringContainsFields() {
            WikiScope scope = new WikiScope("my-agent", "my-ctx", "my-wiki");

            assertThat(scope.toString()).contains("my-agent").contains("my-ctx").contains("my-wiki");
        }
    }
}
