package at.aimon.core.memory.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultDreamerEngine")
class DefaultDreamerEngineTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob", "Bob"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.system());

    @Mock
    private ObservationStore observationStore;

    @Mock
    private ConsolidationStrategy strategy;

    private DefaultDreamerEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultDreamerEngine(observationStore, strategy, 8);
    }

    @Test
    @DisplayName("no subjects → empty summary, strategy never called")
    void noSubjects() {
        when(observationStore.findSubjects(WS, 8)).thenReturn(List.of());

        DreamerCycleSummary summary = engine.consolidate(WS);

        assertThat(summary.getSubjectsWalked()).isZero();
        assertThat(summary.getSubjectsWithPlan()).isZero();
        assertThat(summary.getClustersConsolidated()).isZero();
        assertThat(summary.getObservationsMerged()).isZero();
        assertThat(summary.getErrors()).isZero();
        verify(strategy, never()).plan(any(), any());
        verify(strategy, never()).apply(any());
    }

    @Test
    @DisplayName("empty plans → apply skipped per subject")
    void emptyPlansSkipApply() {
        when(observationStore.findSubjects(WS, 8)).thenReturn(List.of(ALICE, BOB));
        when(strategy.plan(WS, ALICE)).thenReturn(ConsolidationPlan.empty(WS, ALICE));
        when(strategy.plan(WS, BOB)).thenReturn(ConsolidationPlan.empty(WS, BOB));

        DreamerCycleSummary summary = engine.consolidate(WS);

        assertThat(summary.getSubjectsWalked()).isEqualTo(2);
        assertThat(summary.getSubjectsWithPlan()).isZero();
        verify(strategy, never()).apply(any());
    }

    @Test
    @DisplayName("non-empty plan → applied + counts aggregated")
    void appliesNonEmptyPlans() {
        ConsolidationPlan alicePlan = planWithCluster(ALICE, "obs-a-winner", "obs-a-loser1", "obs-a-loser2");
        ConsolidationPlan bobPlan = planWithCluster(BOB, "obs-b-winner", "obs-b-loser1");
        when(observationStore.findSubjects(WS, 8)).thenReturn(List.of(ALICE, BOB));
        when(strategy.plan(WS, ALICE)).thenReturn(alicePlan);
        when(strategy.plan(WS, BOB)).thenReturn(bobPlan);
        // observationsMerged is the ACTUAL count reported by apply(), not the planned total.
        when(strategy.apply(eq(alicePlan))).thenReturn(new ConsolidationResult(2, 1, 0));
        when(strategy.apply(eq(bobPlan))).thenReturn(new ConsolidationResult(1, 1, 0));

        DreamerCycleSummary summary = engine.consolidate(WS);

        assertThat(summary.getSubjectsWalked()).isEqualTo(2);
        assertThat(summary.getSubjectsWithPlan()).isEqualTo(2);
        assertThat(summary.getClustersConsolidated()).isEqualTo(2);
        assertThat(summary.getObservationsMerged()).isEqualTo(3);
        assertThat(summary.getErrors()).isZero();
        verify(strategy, times(1)).apply(eq(alicePlan));
        verify(strategy, times(1)).apply(eq(bobPlan));
    }

    @Test
    @DisplayName("plan() throws → counted as error, cycle continues")
    void planFailureIsIsolated() {
        ConsolidationPlan bobPlan = planWithCluster(BOB, "obs-b-winner", "obs-b-loser");
        when(observationStore.findSubjects(WS, 8)).thenReturn(List.of(ALICE, BOB));
        when(strategy.plan(WS, ALICE)).thenThrow(new RuntimeException("LLM unavailable"));
        when(strategy.plan(WS, BOB)).thenReturn(bobPlan);
        when(strategy.apply(eq(bobPlan))).thenReturn(new ConsolidationResult(1, 1, 0));

        DreamerCycleSummary summary = engine.consolidate(WS);

        assertThat(summary.getSubjectsWalked()).isEqualTo(2);
        assertThat(summary.getSubjectsWithPlan()).isEqualTo(1);
        assertThat(summary.getClustersConsolidated()).isEqualTo(1);
        assertThat(summary.getObservationsMerged()).isEqualTo(1);
        assertThat(summary.getErrors()).isEqualTo(1);
        verify(strategy, atMost(1)).apply(any());
        verify(strategy, times(1)).apply(eq(bobPlan));
    }

    @Test
    @DisplayName("apply() throws → counted as error, next subject still runs")
    void applyFailureIsIsolated() {
        ConsolidationPlan alicePlan = planWithCluster(ALICE, "obs-a-winner", "obs-a-loser");
        ConsolidationPlan bobPlan = planWithCluster(BOB, "obs-b-winner", "obs-b-loser");
        when(observationStore.findSubjects(WS, 8)).thenReturn(List.of(ALICE, BOB));
        when(strategy.plan(WS, ALICE)).thenReturn(alicePlan);
        when(strategy.plan(WS, BOB)).thenReturn(bobPlan);
        org.mockito.Mockito.doThrow(new RuntimeException("merge collision")).when(strategy).apply(eq(alicePlan));
        when(strategy.apply(eq(bobPlan))).thenReturn(new ConsolidationResult(1, 1, 0));

        DreamerCycleSummary summary = engine.consolidate(WS);

        assertThat(summary.getErrors()).isEqualTo(1);
        verify(strategy, times(1)).apply(eq(bobPlan));
    }

    @Test
    @DisplayName("findSubjects called with configured cap")
    void findSubjectsRespectsCap() {
        DefaultDreamerEngine smallEngine = new DefaultDreamerEngine(observationStore, strategy, 3);
        when(observationStore.findSubjects(WS, 3)).thenReturn(List.of());

        smallEngine.consolidate(WS);

        verify(observationStore).findSubjects(WS, 3);
    }

    @Test
    @DisplayName("constructor rejects null deps and bad cap")
    void constructorValidates() {
        assertThatThrownBy(() -> new DefaultDreamerEngine(null, strategy)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultDreamerEngine(observationStore, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultDreamerEngine(observationStore, strategy, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ConsolidationPlan planWithCluster(PeerView subject, String winnerId, String... loserIds) {
        Observation winner = obs(subject, winnerId, "winner");
        java.util.List<Observation> losers = new java.util.ArrayList<>();
        for (String lid : loserIds) {
            losers.add(obs(subject, lid, "loser-" + lid));
        }
        Observation merged = Observation.builder().id(winner.getId()).subject(subject).observer(OBSERVER)
                .content("merged-" + winnerId).type(ObservationType.EXPLICIT).createdAt(Instant.now()).confidence(0.9d)
                .build();
        ObservationCluster cluster = ObservationCluster.builder().winner(winner).losers(List.copyOf(losers))
                .merged(merged).build();
        return ConsolidationPlan.builder().workspace(WS).subject(subject).clusters(List.of(cluster)).build();
    }

    private static Observation obs(PeerView subject, String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(subject).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .confidence(0.8d).build();
    }
}
