package at.aimon.core.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ArtifactCollector Tests")
class ArtifactCollectorTest {

    @Test
    @DisplayName("Should return empty list when no artifacts added")
    void shouldReturnEmptyListWhenNoArtifactsAdded() {
        ArtifactCollector collector = new ArtifactCollector();

        assertThat(collector.getArtifacts()).isEmpty();
    }

    @Test
    @DisplayName("Should collect single artifact")
    void shouldCollectSingleArtifact() {
        ArtifactCollector collector = new ArtifactCollector();
        FileArtifact artifact = createArtifact("/reports/sales.csv", "sales.csv");

        collector.add(artifact);

        assertThat(collector.getArtifacts()).containsExactly(artifact);
    }

    @Test
    @DisplayName("Should collect multiple artifacts in order")
    void shouldCollectMultipleArtifactsInOrder() {
        ArtifactCollector collector = new ArtifactCollector();
        FileArtifact artifact1 = createArtifact("/reports/sales.csv", "sales.csv");
        FileArtifact artifact2 = createArtifact("/reports/summary.pdf", "summary.pdf");
        FileArtifact artifact3 = createArtifact("/exports/data.json", "data.json");

        collector.add(artifact1);
        collector.add(artifact2);
        collector.add(artifact3);

        assertThat(collector.getArtifacts()).containsExactly(artifact1, artifact2, artifact3);
    }

    @Test
    @DisplayName("Should reject null artifact")
    void shouldRejectNullArtifact() {
        ArtifactCollector collector = new ArtifactCollector();

        assertThatThrownBy(() -> collector.add(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Artifact cannot be null");
    }

    @Test
    @DisplayName("Should return unmodifiable list")
    void shouldReturnUnmodifiableList() {
        ArtifactCollector collector = new ArtifactCollector();
        collector.add(createArtifact("/test/file.txt", "file.txt"));

        List<FileArtifact> artifacts = collector.getArtifacts();

        assertThatThrownBy(() -> artifacts.add(createArtifact("/hack/evil.txt", "evil.txt")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("The typed context key keeps the wire name tools have always looked the collector up under")
    void shouldHaveCorrectContextKey() {
        // The untyped ArtifactCollector.CONTEXT_KEY constant is gone; the name it carried is not, because a
        // ToolContext written by one component and read by another still joins on this string.
        assertThat(ToolContextKeys.ARTIFACT_COLLECTOR.name()).isEqualTo("artifactCollector");
    }

    @Test
    @DisplayName("A snapshot does not grow with later additions — only a fresh read sees them")
    void shouldFreezeEachSnapshotAtTheMomentItWasTaken() {
        ArtifactCollector collector = new ArtifactCollector();
        FileArtifact artifact1 = createArtifact("/a.txt", "a.txt");
        collector.add(artifact1);

        List<FileArtifact> firstSnapshot = collector.getArtifacts();
        assertThat(firstSnapshot).hasSize(1);

        FileArtifact artifact2 = createArtifact("/b.txt", "b.txt");
        collector.add(artifact2);

        // A live view here would let an AgentExecutionResult's artifact list keep changing after the result was built.
        assertThat(firstSnapshot).containsExactly(artifact1);
        assertThat(collector.getArtifacts()).hasSize(2).containsExactly(artifact1, artifact2);
    }

    @Test
    @DisplayName("size() marks a window that sliceFrom() closes, returning only what was added in between")
    void shouldSliceTheArtifactsAddedSinceTheMark() {
        ArtifactCollector collector = new ArtifactCollector();
        FileArtifact before = createArtifact("/before.txt", "before.txt");
        collector.add(before);

        final int mark = collector.size();
        assertThat(mark).isEqualTo(1);

        FileArtifact during1 = createArtifact("/during1.txt", "during1.txt");
        FileArtifact during2 = createArtifact("/during2.txt", "during2.txt");
        collector.add(during1);
        collector.add(during2);

        assertThat(collector.sliceFrom(mark)).containsExactly(during1, during2);
        assertThat(collector.sliceFrom(0)).containsExactly(before, during1, during2);
    }

    @Test
    @DisplayName("sliceFrom() returns empty rather than throwing when nothing was added since the mark")
    void shouldReturnEmptySliceWhenNothingWasAddedSinceTheMark() {
        ArtifactCollector collector = new ArtifactCollector();
        collector.add(createArtifact("/a.txt", "a.txt"));

        // An iteration that produced no artifacts is the common case, not an error.
        assertThat(collector.sliceFrom(collector.size())).isEmpty();
        // Out of range in either direction: an artifact-bookkeeping slip must not fail the turn.
        assertThat(collector.sliceFrom(99)).isEmpty();
        assertThat(collector.sliceFrom(-1)).hasSize(1);
    }

    @Test
    @DisplayName("sliceFrom() returns an immutable list")
    void shouldReturnImmutableSlice() {
        ArtifactCollector collector = new ArtifactCollector();
        collector.add(createArtifact("/a.txt", "a.txt"));

        List<FileArtifact> slice = collector.sliceFrom(0);

        assertThatThrownBy(() -> slice.add(createArtifact("/hack/evil.txt", "evil.txt")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static FileArtifact createArtifact(String path, String fileName) {
        return FileArtifact.builder().path(path).fileName(fileName).size(1024).build();
    }
}
