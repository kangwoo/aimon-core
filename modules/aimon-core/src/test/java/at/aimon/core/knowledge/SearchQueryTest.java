package at.aimon.core.knowledge;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SearchQuery Tests")
class SearchQueryTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidation {

        @Test
        @DisplayName("Should create query with defaults")
        void testDefaults() {
            final SearchQuery query = SearchQuery.builder().queryText("test").build();

            assertThat(query.getQueryText()).isEqualTo("test");
            assertThat(query.getMaxResults()).isEqualTo(SearchQuery.DEFAULT_MAX_RESULTS);
            assertThat(query.getMinScore()).isEqualTo(SearchQuery.DEFAULT_MIN_SCORE);
            assertThat(query.getFilePatterns()).isEmpty();
            assertThat(query.getMetadata()).isEmpty();
        }

        @Test
        @DisplayName("Should create query with all fields")
        void testFullBuild() {
            final SearchQuery query = SearchQuery.builder().queryText("CrashLoopBackOff").maxResults(10).minScore(0.5)
                    .filePatterns(List.of("*.md")).build();

            assertThat(query.getMaxResults()).isEqualTo(10);
            assertThat(query.getMinScore()).isEqualTo(0.5);
            assertThat(query.getFilePatterns()).containsExactly("*.md");
        }

        @Test
        @DisplayName("Should reject null queryText")
        void testNullQueryText() {
            assertThatThrownBy(() -> SearchQuery.builder().build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject empty queryText")
        void testEmptyQueryText() {
            assertThatThrownBy(() -> SearchQuery.builder().queryText("").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject maxResults < 1")
        void testInvalidMaxResults() {
            assertThatThrownBy(() -> SearchQuery.builder().queryText("test").maxResults(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject minScore out of range")
        void testInvalidMinScore() {
            assertThatThrownBy(() -> SearchQuery.builder().queryText("test").minScore(-0.1).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SearchQuery.builder().queryText("test").minScore(1.1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("File patterns should be unmodifiable")
        void testFilePatternsUnmodifiable() {
            final SearchQuery query = SearchQuery.builder().queryText("test").filePatterns(List.of("*.md")).build();

            assertThatThrownBy(() -> query.getFilePatterns().add("*.txt"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
