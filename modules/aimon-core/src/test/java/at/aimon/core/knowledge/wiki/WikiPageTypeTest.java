package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiPageType")
class WikiPageTypeTest {

    @Nested
    @DisplayName("getPrefix / getToken")
    class PrefixAndToken {

        @Test
        @DisplayName("every type exposes a lowercase prefix matching its token")
        void prefixMatchesToken() {
            for (WikiPageType type : WikiPageType.values()) {
                assertThat(type.getPrefix()).isEqualTo(type.getToken());
                assertThat(type.getPrefix()).isLowerCase();
                assertThat(type.getPrefix()).doesNotContain("-");
            }
        }
    }

    @Nested
    @DisplayName("fromToken")
    class FromToken {

        @Test
        @DisplayName("known lowercase token maps to its enum value")
        void lowercaseTokenMaps() {
            assertThat(WikiPageType.fromToken("entity")).isEqualTo(WikiPageType.ENTITY);
            assertThat(WikiPageType.fromToken("concept")).isEqualTo(WikiPageType.CONCEPT);
            assertThat(WikiPageType.fromToken("synthesis")).isEqualTo(WikiPageType.SYNTHESIS);
        }

        @Test
        @DisplayName("matching is case-insensitive and trims whitespace")
        void caseInsensitiveAndTrimmed() {
            assertThat(WikiPageType.fromToken("  ENTITY  ")).isEqualTo(WikiPageType.ENTITY);
            assertThat(WikiPageType.fromToken("Comparison")).isEqualTo(WikiPageType.COMPARISON);
        }

        @Test
        @DisplayName("null, blank, or unknown token falls back to DEFAULT")
        void unknownFallsBack() {
            assertThat(WikiPageType.fromToken(null)).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromToken("")).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromToken("   ")).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromToken("nonsense")).isEqualTo(WikiPageType.DEFAULT);
        }

        @Test
        @DisplayName("DEFAULT is SUMMARY")
        void defaultIsSummary() {
            assertThat(WikiPageType.DEFAULT).isEqualTo(WikiPageType.SUMMARY);
        }
    }

    @Nested
    @DisplayName("strictParse")
    class StrictParse {

        @Test
        @DisplayName("known token returns the matching type")
        void knownToken() {
            assertThat(WikiPageType.strictParse("overview")).isEqualTo(WikiPageType.OVERVIEW);
        }

        @Test
        @DisplayName("null token throws NullPointerException")
        void nullThrows() {
            assertThatThrownBy(() -> WikiPageType.strictParse(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank token throws IllegalArgumentException")
        void blankThrows() {
            assertThatThrownBy(() -> WikiPageType.strictParse("   ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("unknown token throws IllegalArgumentException")
        void unknownThrows() {
            assertThatThrownBy(() -> WikiPageType.strictParse("bogus")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bogus");
        }
    }

    @Nested
    @DisplayName("fromFileName")
    class FromFileName {

        @Test
        @DisplayName("recognized prefixes map to their type")
        void recognizedPrefix() {
            assertThat(WikiPageType.fromFileName("summary-foo.md")).isEqualTo(WikiPageType.SUMMARY);
            assertThat(WikiPageType.fromFileName("entity-kubernetes-pod.md")).isEqualTo(WikiPageType.ENTITY);
            assertThat(WikiPageType.fromFileName("concept-eventual-consistency.md")).isEqualTo(WikiPageType.CONCEPT);
            assertThat(WikiPageType.fromFileName("comparison-a-vs-b.md")).isEqualTo(WikiPageType.COMPARISON);
            assertThat(WikiPageType.fromFileName("overview-databases.md")).isEqualTo(WikiPageType.OVERVIEW);
            assertThat(WikiPageType.fromFileName("synthesis-latency.md")).isEqualTo(WikiPageType.SYNTHESIS);
            assertThat(WikiPageType.fromFileName("answer-how-to.md")).isEqualTo(WikiPageType.ANSWER);
        }

        @Test
        @DisplayName("unprefixed or unknown file names fall back to DEFAULT")
        void unknownFallsBack() {
            assertThat(WikiPageType.fromFileName("kubernetes.md")).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromFileName("random-thing.md")).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromFileName(null)).isEqualTo(WikiPageType.DEFAULT);
            assertThat(WikiPageType.fromFileName("")).isEqualTo(WikiPageType.DEFAULT);
        }

        @Test
        @DisplayName("matching is case-insensitive")
        void caseInsensitive() {
            assertThat(WikiPageType.fromFileName("ENTITY-foo.md")).isEqualTo(WikiPageType.ENTITY);
        }
    }
}
