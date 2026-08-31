package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GeneratedPage")
class GeneratedPageTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with all fields produces expected page")
        void builderWithAllFields() {
            GeneratedPage page = GeneratedPage.builder().type(WikiPageType.ENTITY).slug("kubernetes-pod")
                    .title("Kubernetes Pod").content("---\ntype: entity\n---\n\n# Kubernetes Pod\n")
                    .tags(List.of("k8s", "containers")).derivedFrom(List.of("/raw/k8s.md"))
                    .strategy(GeneratedPage.UpdateStrategy.MERGE).build();

            assertThat(page.getType()).isEqualTo(WikiPageType.ENTITY);
            assertThat(page.getSlug()).isEqualTo("kubernetes-pod");
            assertThat(page.getTitle()).isEqualTo("Kubernetes Pod");
            assertThat(page.getContent()).contains("# Kubernetes Pod");
            assertThat(page.getTags()).containsExactly("k8s", "containers");
            assertThat(page.getDerivedFrom()).containsExactly("/raw/k8s.md");
            assertThat(page.getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.MERGE);
        }

        @Test
        @DisplayName("strategy defaults to CREATE")
        void strategyDefaultsToCreate() {
            GeneratedPage page = GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("foo").title("Foo")
                    .content("body").build();

            assertThat(page.getStrategy()).isEqualTo(GeneratedPage.UpdateStrategy.CREATE);
        }

        @Test
        @DisplayName("tags and derivedFrom default to empty")
        void listsDefaultToEmpty() {
            GeneratedPage page = GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("foo").title("Foo")
                    .content("body").build();

            assertThat(page.getTags()).isEmpty();
            assertThat(page.getDerivedFrom()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null type throws NullPointerException")
        void nullTypeThrows() {
            assertThatThrownBy(() -> GeneratedPage.builder().slug("foo").title("Foo").content("body").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null slug throws NullPointerException")
        void nullSlugThrows() {
            assertThatThrownBy(
                    () -> GeneratedPage.builder().type(WikiPageType.SUMMARY).title("Foo").content("body").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank slug throws IllegalArgumentException")
        void blankSlugThrows() {
            assertThatThrownBy(() -> GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("   ").title("Foo")
                    .content("body").build()).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("empty content throws IllegalArgumentException")
        void emptyContentThrows() {
            assertThatThrownBy(() -> GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("foo").title("Foo")
                    .content("").build()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("tags list is unmodifiable")
        void tagsAreUnmodifiable() {
            GeneratedPage page = GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("foo").title("Foo")
                    .content("body").tags(new ArrayList<>(List.of("a"))).build();

            assertThatThrownBy(() -> page.getTags().add("b")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("derivedFrom list is unmodifiable")
        void derivedFromIsUnmodifiable() {
            GeneratedPage page = GeneratedPage.builder().type(WikiPageType.SUMMARY).slug("foo").title("Foo")
                    .content("body").derivedFrom(new ArrayList<>(List.of("/raw/a.md"))).build();

            assertThatThrownBy(() -> page.getDerivedFrom().add("/raw/b.md"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
