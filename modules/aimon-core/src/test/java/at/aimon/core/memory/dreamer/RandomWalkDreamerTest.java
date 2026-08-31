package at.aimon.core.memory.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@ExtendWith(MockitoExtension.class)
@DisplayName("RandomWalkDreamer")
class RandomWalkDreamerTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView SUBJECT = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("bob", "Bob"));

    @Mock
    private ObservationStore observationStore;

    @Mock
    private LlmClient llmClient;

    private SurprisalScorer surprisalScorer;
    private Map<ObservationId, Double> stubScores;

    @BeforeEach
    void setUp() {
        stubScores = new HashMap<>();
        surprisalScorer = (obs, neighbors) -> stubScores.getOrDefault(obs.getId(), 1.0d);
    }

    @Test
    @DisplayName("less than 2 observations → empty plan, no LLM call")
    void notEnoughObservations() {
        when(observationStore.findBySubject(SUBJECT, 8))
                .thenReturn(List.of(observation("obs-1", "alpha", 0.8d)));

        ConsolidationPlan plan = newDreamer().plan(WS, SUBJECT);

        assertThat(plan.isEmpty()).isTrue();
        verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("all observations highly novel → empty plan")
    void allNovel() {
        Observation a = observation("obs-1", "alpha", 0.8d);
        Observation b = observation("obs-2", "beta", 0.7d);
        when(observationStore.findBySubject(SUBJECT, 8)).thenReturn(List.of(a, b));
        when(observationStore.semanticSearch(SUBJECT, "alpha", 4)).thenReturn(List.of(a, b));
        when(observationStore.semanticSearch(SUBJECT, "beta", 4)).thenReturn(List.of(b, a));
        // Both surprisals above threshold → no consolidation.
        stubScores.put(a.getId(), 0.9d);
        stubScores.put(b.getId(), 0.9d);

        ConsolidationPlan plan = newDreamer().plan(WS, SUBJECT);

        assertThat(plan.isEmpty()).isTrue();
        verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("redundant pair → cluster planned with higher-confidence winner")
    void redundantPairClustered() {
        Observation a = observation("obs-1", "alpha-low", 0.5d);
        Observation b = observation("obs-2", "alpha-high", 0.9d);
        when(observationStore.findBySubject(SUBJECT, 8)).thenReturn(List.of(a, b));
        when(observationStore.semanticSearch(SUBJECT, "alpha-low", 4)).thenReturn(List.of(b));
        stubScores.put(b.getId(), 0.05d); // very low surprisal vs seed a

        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"content\":\"alice likes alpha\",\"confidence\":0.95}"));

        ConsolidationPlan plan = newDreamer().plan(WS, SUBJECT);

        assertThat(plan.getClusters()).hasSize(1);
        ObservationCluster cluster = plan.getClusters().get(0);
        // b has higher confidence → winner.
        assertThat(cluster.getWinner().getId()).isEqualTo(b.getId());
        assertThat(cluster.getLosers()).extracting(Observation::getId).containsExactly(a.getId());
        assertThat(cluster.getMerged().getContent()).isEqualTo("alice likes alpha");
        assertThat(cluster.getMerged().getConfidence()).isEqualTo(0.95d);
        // LLM called exactly once.
        verify(llmClient, times(1)).sendMessage(anyString(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("apply() issues one merge() against the primary loser and soft-deletes the rest")
    void applyIssuesMergeCalls() {
        Observation winner = observation("obs-1", "winner", 0.9d);
        Observation loserA = observation("obs-2", "losera", 0.5d);
        Observation loserB = observation("obs-3", "loserb", 0.5d);
        Observation merged = Observation.builder().id(winner.getId()).subject(winner.getSubject())
                .observer(winner.getObserver()).content("merged").type(ObservationType.EXPLICIT)
                .createdAt(Instant.now()).confidence(0.95d).build();
        ObservationCluster cluster = ObservationCluster.builder().winner(winner).losers(List.of(loserA, loserB))
                .merged(merged).build();
        ConsolidationPlan plan = ConsolidationPlan.builder().workspace(WS).subject(SUBJECT).clusters(List.of(cluster))
                .build();

        ConsolidationResult result = newDreamer().apply(plan);

        // Primary loser absorbs the merged content via merge() (audit-retained soft-delete).
        verify(observationStore).merge(eq(winner.getId()), eq(loserA.getId()), eq(merged));
        // Remaining losers are soft-deleted (not hard delete()d) so they keep the 30-day audit window.
        verify(observationStore, never()).merge(eq(winner.getId()), eq(loserB.getId()), any());
        verify(observationStore).softDelete(eq(loserB.getId()));
        verify(observationStore, never()).delete(any());
        // Both losers were retired and the cluster applied.
        assertThat(result.getObservationsRemoved()).isEqualTo(2);
        assertThat(result.getClustersApplied()).isEqualTo(1);
        assertThat(result.getFailures()).isZero();
    }

    @Test
    @DisplayName("empty plan → apply() is a no-op")
    void emptyPlanNoOp() {
        newDreamer().apply(ConsolidationPlan.empty(WS, SUBJECT));
        verify(observationStore, never()).merge(any(), any(), any());
    }

    @Test
    @DisplayName("LLM returns malformed JSON → cluster dropped")
    void malformedLlmResponseDropsCluster() {
        Observation a = observation("obs-1", "alpha-low", 0.5d);
        Observation b = observation("obs-2", "alpha-high", 0.9d);
        when(observationStore.findBySubject(SUBJECT, 8)).thenReturn(List.of(a, b));
        when(observationStore.semanticSearch(SUBJECT, "alpha-low", 4)).thenReturn(List.of(b));
        when(observationStore.semanticSearch(SUBJECT, "alpha-high", 4)).thenReturn(List.of(a));
        stubScores.put(b.getId(), 0.05d);
        stubScores.put(a.getId(), 0.05d);

        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("not-json"));

        ConsolidationPlan plan = newDreamer().plan(WS, SUBJECT);

        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("constructor rejects out-of-range surprisal threshold")
    void constructorValidatesThreshold() {
        assertThatThrowingConstructorRejects(
                () -> new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "model", 1.5d, 8, 4));
        assertThatThrowingConstructorRejects(
                () -> new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "model", -0.1d, 8, 4));
        assertThatThrowingConstructorRejects(
                () -> new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "model", 0.2d, 0, 4));
        assertThatThrowingConstructorRejects(
                () -> new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "model", 0.2d, 8, 0));
        assertThatThrowingConstructorRejects(
                () -> new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "  ", 0.2d, 8, 4));
    }

    private RandomWalkDreamer newDreamer() {
        return new RandomWalkDreamer(observationStore, surprisalScorer, llmClient, "test-model", 0.2d, 8, 4);
    }

    private static Observation observation(String localId, String content, double confidence) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(SUBJECT).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .confidence(confidence).build();
    }

    private static void assertThatThrowingConstructorRejects(Runnable r) {
        try {
            r.run();
            org.junit.jupiter.api.Assertions.fail("expected constructor to reject input");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
