package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("WikiPage")
class WikiPageTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder creates valid page with all fields set")
        void builderCreatesValidPage() {
            Instant now = Instant.now();
            WikiPage page = WikiPage.builder().path("/wiki/kubernetes-pods.md").title("Kubernetes Pods")
                    .content("# Kubernetes Pods\n\nA Pod is the smallest unit.")
                    .tags(List.of("kubernetes", "containers")).linkedPages(List.of("/wiki/containers.md"))
                    .metadata(Map.of("author", "bot")).sourceRef("/raw/k8s-guide.md").lastUpdatedAt(now).build();

            assertThat(page.getPath()).isEqualTo("/wiki/kubernetes-pods.md");
            assertThat(page.getTitle()).isEqualTo("Kubernetes Pods");
            assertThat(page.getContent()).isEqualTo("# Kubernetes Pods\n\nA Pod is the smallest unit.");
            assertThat(page.getTags()).containsExactly("kubernetes", "containers");
            assertThat(page.getLinkedPages()).containsExactly("/wiki/containers.md");
            assertThat(page.getMetadata()).containsEntry("author", "bot");
            assertThat(page.getSourceRef()).isEqualTo("/raw/k8s-guide.md");
            assertThat(page.getLastUpdatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null path throws NullPointerException")
        void nullPathThrowsNpe() {
            assertThatThrownBy(() -> WikiPage.builder().path(null).title("Title").content("Content").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty path throws IllegalArgumentException")
        void emptyPathThrowsIae() {
            assertThatThrownBy(() -> WikiPage.builder().path("").title("Title").content("Content").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null title throws NullPointerException")
        void nullTitleThrowsNpe() {
            assertThatThrownBy(() -> WikiPage.builder().path("/wiki/page.md").title(null).content("Content").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null content throws NullPointerException")
        void nullContentThrowsNpe() {
            assertThatThrownBy(() -> WikiPage.builder().path("/wiki/page.md").title("Title").content(null).build())
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Default values")
    class DefaultValues {

        @Test
        @DisplayName("tags default to empty list when not set")
        void tagsDefaultToEmpty() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getTags()).isEmpty();
        }

        @Test
        @DisplayName("linkedPages default to empty list when not set")
        void linkedPagesDefaultToEmpty() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getLinkedPages()).isEmpty();
        }

        @Test
        @DisplayName("metadata defaults to empty map when not set")
        void metadataDefaultsToEmpty() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getMetadata()).isEmpty();
        }

        @Test
        @DisplayName("sourceRef is null when not set")
        void sourceRefNullByDefault() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getSourceRef()).isNull();
        }

        @Test
        @DisplayName("lastUpdatedAt is null when not set")
        void lastUpdatedAtNullByDefault() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getLastUpdatedAt()).isNull();
        }

        @Test
        @DisplayName("type defaults to WikiPageType.DEFAULT (SUMMARY) when not set")
        void typeDefaultsToSummary() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getType()).isEqualTo(WikiPageType.DEFAULT);
            assertThat(page.getType()).isEqualTo(WikiPageType.SUMMARY);
        }

        @Test
        @DisplayName("derivedFrom defaults to empty list when not set")
        void derivedFromDefaultsToEmpty() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").build();

            assertThat(page.getDerivedFrom()).isEmpty();
        }

        @Test
        @DisplayName("explicit type is preserved")
        void explicitTypeIsPreserved() {
            WikiPage page = WikiPage.builder().path("/wiki/entity.md").title("Title").content("Content")
                    .type(WikiPageType.ENTITY).build();

            assertThat(page.getType()).isEqualTo(WikiPageType.ENTITY);
        }

        @Test
        @DisplayName("null type falls back to DEFAULT")
        void nullTypeFallsBackToDefault() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content").type(null)
                    .build();

            assertThat(page.getType()).isEqualTo(WikiPageType.DEFAULT);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("tags list is unmodifiable")
        void tagsAreUnmodifiable() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content")
                    .tags(new ArrayList<>(List.of("k8s"))).build();

            assertThatThrownBy(() -> page.getTags().add("new-tag")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("linkedPages list is unmodifiable")
        void linkedPagesAreUnmodifiable() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content")
                    .linkedPages(new ArrayList<>(List.of("/wiki/other.md"))).build();

            assertThatThrownBy(() -> page.getLinkedPages().add("/wiki/new.md"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("metadata map is unmodifiable")
        void metadataIsUnmodifiable() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content")
                    .metadata(new HashMap<>(Map.of("key", "value"))).build();

            assertThatThrownBy(() -> page.getMetadata().put("new", "entry"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("derivedFrom list is unmodifiable")
        void derivedFromIsUnmodifiable() {
            WikiPage page = WikiPage.builder().path("/wiki/page.md").title("Title").content("Content")
                    .derivedFrom(new ArrayList<>(List.of("/raw/a.md"))).build();

            assertThatThrownBy(() -> page.getDerivedFrom().add("/raw/b.md"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
