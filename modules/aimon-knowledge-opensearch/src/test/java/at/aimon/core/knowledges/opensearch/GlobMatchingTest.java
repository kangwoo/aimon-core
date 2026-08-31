package at.aimon.core.knowledges.opensearch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class GlobMatchingTest {

    @Test
    void shouldMatchExactFileName() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("readme.md", "readme.md")).isTrue();
    }

    @Test
    void shouldNotMatchDifferentFileName() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("readme.md", "guide.md")).isFalse();
    }

    @Test
    void shouldMatchWildcardStar() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.md", "readme.md")).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.md", "guide.md")).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.md", "readme.txt")).isFalse();
    }

    @Test
    void shouldMatchWildcardQuestion() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("file?.md", "file1.md")).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchGlob("file?.md", "fileAB.md")).isFalse();
    }

    @Test
    void shouldMatchMultipleStars() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.*", "readme.md")).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.*", "noext")).isFalse();
    }

    @Test
    void shouldMatchStarAtEnd() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("readme*", "readme.md")).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchGlob("readme*", "readme")).isTrue();
    }

    @Test
    void shouldMatchEmptyStarSegment() {
        assertThat(OpenSearchKnowledgeStore.matchGlob("*.txt", ".txt")).isTrue();
    }

    @Test
    void matchesFilePatternsWithEmptyListShouldMatchAll() {
        assertThat(OpenSearchKnowledgeStore.matchesFilePatterns("any.file", List.of())).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchesFilePatterns("any.file", null)).isTrue();
    }

    @Test
    void matchesFilePatternsWithMultiplePatterns() {
        assertThat(OpenSearchKnowledgeStore.matchesFilePatterns("readme.md", List.of("*.md", "*.txt"))).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchesFilePatterns("readme.txt", List.of("*.md", "*.txt"))).isTrue();
        assertThat(OpenSearchKnowledgeStore.matchesFilePatterns("readme.java", List.of("*.md", "*.txt"))).isFalse();
    }
}
