package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiSearchQuery")
class WikiSearchQueryTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with only queryText uses default maxResults")
        void builderWithDefaultsApplied() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("kubernetes troubleshooting").build();

            assertThat(query.getQueryText()).isEqualTo("kubernetes troubleshooting");
            assertThat(query.getMaxResults()).isEqualTo(WikiSearchQuery.DEFAULT_MAX_RESULTS);
            assertThat(query.getTags()).isEmpty();
            assertThat(query.getPagePathPatterns()).isEmpty();
        }

        @Test
        @DisplayName("builder with all fields set stores all values")
        void builderWithAllFields() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("pod scheduling").maxResults(5)
                    .tags(List.of("kubernetes", "scheduling")).pagePathPatterns(List.of("/wiki/ops/**")).build();

            assertThat(query.getQueryText()).isEqualTo("pod scheduling");
            assertThat(query.getMaxResults()).isEqualTo(5);
            assertThat(query.getTags()).containsExactly("kubernetes", "scheduling");
            assertThat(query.getPagePathPatterns()).containsExactly("/wiki/ops/**");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null queryText throws NullPointerException")
        void nullQueryTextThrowsNpe() {
            assertThatThrownBy(() -> WikiSearchQuery.builder().queryText(null).build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty queryText throws IllegalArgumentException")
        void emptyQueryTextThrowsIae() {
            assertThatThrownBy(() -> WikiSearchQuery.builder().queryText("").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("maxResults of zero throws IllegalArgumentException")
        void zeroMaxResultsThrowsIae() {
            assertThatThrownBy(() -> WikiSearchQuery.builder().queryText("query").maxResults(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative maxResults throws IllegalArgumentException")
        void negativeMaxResultsThrowsIae() {
            assertThatThrownBy(() -> WikiSearchQuery.builder().queryText("query").maxResults(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("DEFAULT_MAX_RESULTS constant is 10")
        void defaultMaxResultsConstant() {
            assertThat(WikiSearchQuery.DEFAULT_MAX_RESULTS).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("tags list is unmodifiable")
        void tagsAreUnmodifiable() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("query").tags(new ArrayList<>(List.of("tag1")))
                    .build();

            assertThatThrownBy(() -> query.getTags().add("new-tag")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("pagePathPatterns list is unmodifiable")
        void pagePathPatternsAreUnmodifiable() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("query")
                    .pagePathPatterns(new ArrayList<>(List.of("/wiki/**"))).build();

            assertThatThrownBy(() -> query.getPagePathPatterns().add("/new/**"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Type filters")
    class TypeFilters {

        @Test
        @DisplayName("includeTypes and excludeTypes default to empty")
        void typeFiltersDefaultEmpty() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo").build();

            assertThat(query.getIncludeTypes()).isEmpty();
            assertThat(query.getExcludeTypes()).isEmpty();
        }

        @Test
        @DisplayName("matchesType returns true for every type when both filters are empty")
        void matchesEverythingWhenEmpty() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo").build();

            for (WikiPageType type : WikiPageType.values()) {
                assertThat(query.matchesType(type)).isTrue();
            }
        }

        @Test
        @DisplayName("includeTypes acts as an allowlist")
        void includeTypesAllowlist() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo")
                    .includeTypes(EnumSet.of(WikiPageType.ENTITY, WikiPageType.CONCEPT)).build();

            assertThat(query.matchesType(WikiPageType.ENTITY)).isTrue();
            assertThat(query.matchesType(WikiPageType.CONCEPT)).isTrue();
            assertThat(query.matchesType(WikiPageType.SUMMARY)).isFalse();
            assertThat(query.matchesType(WikiPageType.OVERVIEW)).isFalse();
        }

        @Test
        @DisplayName("excludeTypes acts as a denylist")
        void excludeTypesDenylist() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo")
                    .excludeTypes(EnumSet.of(WikiPageType.OVERVIEW, WikiPageType.SYNTHESIS)).build();

            assertThat(query.matchesType(WikiPageType.ENTITY)).isTrue();
            assertThat(query.matchesType(WikiPageType.SUMMARY)).isTrue();
            assertThat(query.matchesType(WikiPageType.OVERVIEW)).isFalse();
            assertThat(query.matchesType(WikiPageType.SYNTHESIS)).isFalse();
        }

        @Test
        @DisplayName("includeTypes and excludeTypes compose — both filters apply")
        void includeAndExcludeCompose() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo")
                    .includeTypes(EnumSet.of(WikiPageType.ENTITY, WikiPageType.CONCEPT, WikiPageType.OVERVIEW))
                    .excludeTypes(EnumSet.of(WikiPageType.OVERVIEW)).build();

            assertThat(query.matchesType(WikiPageType.ENTITY)).isTrue();
            assertThat(query.matchesType(WikiPageType.CONCEPT)).isTrue();
            assertThat(query.matchesType(WikiPageType.OVERVIEW)).isFalse();
            assertThat(query.matchesType(WikiPageType.SUMMARY)).isFalse();
        }

        @Test
        @DisplayName("includeTypes set is unmodifiable")
        void includeTypesUnmodifiable() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo")
                    .includeTypes(EnumSet.of(WikiPageType.ENTITY)).build();

            assertThatThrownBy(() -> query.getIncludeTypes().add(WikiPageType.CONCEPT))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("matchesType throws on null type")
        void matchesTypeNullThrows() {
            WikiSearchQuery query = WikiSearchQuery.builder().queryText("foo").build();

            assertThatThrownBy(() -> query.matchesType(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
