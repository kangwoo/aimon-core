package at.aimon.core.tools.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.MemoryHit;
import at.aimon.core.memory.MemorySearchQuery;
import at.aimon.core.memory.MemorySearcher;
import at.aimon.core.memory.MemorySnapshot;
import at.aimon.core.memory.MemorySnapshotQuery;
import at.aimon.core.memory.MemorySnapshotReader;
import at.aimon.core.memory.MemorySnapshotScope;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationDraft;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationRecorder;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * What the memory tools show the model when the backend cannot supply something.
 *
 * <p>
 * Each case here is one where the pre-tier code would have shown the model something that reads as a fact and is not:
 * an empty observation list that means "this backend has no such concept" but renders as "nothing is known", and a
 * confidence the backend discarded rendered as the value the model chose. The tools say the first out loud and decline
 * to print the second.
 */
@DisplayName("Memory tools — backend capability signals")
class MemoryToolBackendSignalTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private static PeerView peer(String id) {
        return PeerView.of(WS, Principal.user(id, id));
    }

    private static ToolContext context() {
        return ToolContext.builder().put(MemoryToolContextKeys.WORKSPACE, WS)
                .put(MemoryToolContextKeys.OBSERVER, peer("agent")).put(MemoryToolContextKeys.SUBJECT, peer("alice"))
                .build();
    }

    private static Observation observation() {
        return Observation.builder().id(ObservationId.of(WS, "o-1")).subject(peer("alice")).observer(peer("agent"))
                .content("Alice prefers tea").type(ObservationType.EXPLICIT).confidence(0.83d).createdAt(T0).build();
    }

    @Nested
    @DisplayName("MemoryRecall")
    class Recall {

        private ToolResult recall(MemorySnapshot snapshot) {
            MemorySnapshotReader reader = query -> Optional.of(snapshot);
            return new MemoryRecallTool(reader).execute(ToolInput.of(Map.of()), context());
        }

        private MemorySnapshot.Builder snapshot() {
            return MemorySnapshot.builder().renderedText("Alice is a tea drinker.")
                    .resolvedScope(MemorySnapshotScope.GLOBAL).generatedAt(T0).tokenCount(12);
        }

        @Test
        @DisplayName("says so when the backend exposes no individual observations, instead of rendering silence")
        void observationsUnavailableIsStated() {
            ToolResult result = recall(snapshot().observationsAvailable(false).build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("does not expose individual observations");
            assertThat(result.getContent()).doesNotContain("Observations (");
        }

        @Test
        @DisplayName("a backend that does expose them, for a peer with none, renders neither the note nor a list")
        void genuinelyEmptyIsDifferent() {
            ToolResult result = recall(snapshot().observationsAvailable(true).build());

            assertThat(result.getContent()).doesNotContain("does not expose individual observations");
            assertThat(result.getContent()).doesNotContain("Observations (");
        }

        @Test
        @DisplayName("prints confidence when it is stored")
        void confidencePrintedWhenReal() {
            ToolResult result = recall(snapshot().observationsAvailable(true).confidenceAvailable(true)
                    .observations(List.of(observation())).build());

            assertThat(result.getContent()).contains("Observations (1):").contains("(confidence=0.83)");
        }

        @Test
        @DisplayName("omits confidence when the backend did not store it — a plausible false number is worse than"
                + " none")
        void confidenceOmittedWhenFabricated() {
            ToolResult result = recall(snapshot().observationsAvailable(true).confidenceAvailable(false)
                    .observations(List.of(observation())).build());

            assertThat(result.getContent()).contains("Observations (1):").contains("Alice prefers tea");
            assertThat(result.getContent()).doesNotContain("confidence");
        }
    }

    @Nested
    @DisplayName("MemorySearch")
    class Search {

        private ToolResult search(boolean confidenceAvailable) {
            MemorySearcher searcher = new FixedSearcher(List.of(
                    MemoryHit.builder().observation(observation()).confidenceAvailable(confidenceAvailable).build()));
            return new MemorySearchTool(searcher).execute(ToolInput.of(Map.of("query", "tea")), context());
        }

        @Test
        @DisplayName("prints confidence when it is stored")
        void confidencePrintedWhenReal() {
            assertThat(search(true).getContent()).contains("confidence=0.83");
        }

        @Test
        @DisplayName("omits confidence when the backend did not store it")
        void confidenceOmittedWhenFabricated() {
            ToolResult result = search(false);

            assertThat(result.getContent()).contains("Alice prefers tea").contains("type=EXPLICIT");
            assertThat(result.getContent()).doesNotContain("confidence");
        }

        @Test
        @DisplayName("renders hits in the order the tier returned them — that order is the ranking")
        void orderIsTheRanking() {
            Observation first = observation();
            Observation second = Observation.builder().id(ObservationId.of(WS, "o-2")).subject(peer("alice"))
                    .observer(peer("agent")).content("Alice dislikes coffee").type(ObservationType.DEDUCTIVE)
                    .confidence(0.99d).createdAt(T0).build();
            MemorySearcher searcher = new FixedSearcher(
                    List.of(MemoryHit.builder().observation(first).confidenceAvailable(true).build(),
                            MemoryHit.builder().observation(second).confidenceAvailable(true).build()));

            String rendered = new MemorySearchTool(searcher).execute(ToolInput.of(Map.of("query", "tea")), context())
                    .getContent();

            assertThat(rendered.indexOf("Alice prefers tea")).isLessThan(rendered.indexOf("Alice dislikes coffee"));
        }
    }

    @Nested
    @DisplayName("Observe")
    class Observe {

        @Test
        @DisplayName("offers the confidence parameter when the backend stores it")
        void schemaKeepsConfidence() {
            ObserveTool tool = new ObserveTool(new StubRecorder(true));

            assertThat(properties(tool)).containsKey("confidence");
            assertThat(tool.getDefinition().getDescription()).doesNotContain("does not store a confidence");
        }

        @Test
        @DisplayName("removes the confidence parameter when the backend drops it — a parameter the model cannot send"
                + " is a round trip that cannot lose anything")
        void schemaDropsConfidence() {
            ObserveTool tool = new ObserveTool(new StubRecorder(false));

            assertThat(properties(tool)).containsKeys("content", "type").doesNotContainKey("confidence");
            assertThat(tool.getDefinition().getDescription()).contains("does not store a confidence");
        }

        @Test
        @DisplayName("does not report a confidence it knows was discarded")
        void resultOmitsDiscardedConfidence() {
            ToolResult result = new ObserveTool(new StubRecorder(false))
                    .execute(ToolInput.of(Map.of("content", "Alice prefers tea")), context());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Observation recorded").doesNotContain("confidence");
        }

        @Test
        @DisplayName("reports the confidence a storing backend kept")
        void resultKeepsStoredConfidence() {
            ToolResult result = new ObserveTool(new StubRecorder(true))
                    .execute(ToolInput.of(Map.of("content", "Alice prefers tea", "confidence", 0.55d)), context());

            assertThat(result.getContent()).contains("confidence: 0.55");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> properties(ObserveTool tool) {
            return (Map<String, Object>) tool.getDefinition().getInputSchema().get("properties");
        }
    }

    /** Returns a fixed hit list and admits it cannot score. */
    private static final class FixedSearcher implements MemorySearcher {

        private final List<MemoryHit> hits;

        FixedSearcher(List<MemoryHit> hits) {
            this.hits = hits;
        }

        @Override
        public List<MemoryHit> search(MemorySearchQuery query) {
            return hits;
        }

        @Override
        public boolean ranksByScore() {
            return false;
        }

        @Override
        public boolean narrowsBySession() {
            return false;
        }
    }

    /** Records the draft verbatim, keeping or dropping the confidence as configured. */
    private static final class StubRecorder implements ObservationRecorder {

        private final boolean storesConfidence;

        StubRecorder(boolean storesConfidence) {
            this.storesConfidence = storesConfidence;
        }

        @Override
        public Observation observe(ObservationDraft draft) {
            return Observation.builder().id(ObservationId.of(WS, "o-new")).subject(draft.getSubject())
                    .observer(draft.getObserver()).content(draft.getContent()).type(draft.getType())
                    .confidence(storesConfidence ? draft.getConfidence() : draft.getType().baseConfidence())
                    .createdAt(T0).metadata(draft.getMetadata()).build();
        }

        @Override
        public boolean storesConfidence() {
            return storesConfidence;
        }
    }

    /** Kept here so the query type is exercised at least once from the tool side. */
    @Test
    @DisplayName("the recall tool asks the tier for GLOBAL by default")
    void recallDefaultsToGlobalScope() {
        MemorySnapshotQuery[] seen = new MemorySnapshotQuery[1];
        MemorySnapshotReader reader = query -> {
            seen[0] = query;
            return Optional.empty();
        };

        new MemoryRecallTool(reader).execute(ToolInput.of(Map.of()), context());

        assertThat(seen[0].getScope()).isEqualTo(MemorySnapshotScope.GLOBAL);
        assertThat(seen[0].getSubject()).isEqualTo(peer("alice"));
    }
}
