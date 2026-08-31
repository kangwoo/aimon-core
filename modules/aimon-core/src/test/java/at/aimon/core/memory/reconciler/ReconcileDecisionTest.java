package at.aimon.core.memory.reconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("ReconcileDecision")
class ReconcileDecisionTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView SUBJECT = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("bob", "Bob"));

    @Test
    @DisplayName("Accept singleton: same instance for every call")
    void acceptIsSingleton() {
        assertThat(ReconcileDecision.Accept.instance()).isSameAs(ReconcileDecision.Accept.instance());
    }

    @Test
    @DisplayName("Replace stores supersededId; rejects null")
    void replaceContract() {
        ObservationId id = ObservationId.of(WS, "obs-1");
        ReconcileDecision.Replace decision = new ReconcileDecision.Replace(id);

        assertThat(decision.getSupersededId()).isEqualTo(id);
        assertThat(decision).isEqualTo(new ReconcileDecision.Replace(id));
        assertThatThrownBy(() -> new ReconcileDecision.Replace(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Merge stores otherId + merged; rejects nulls")
    void mergeContract() {
        ObservationId other = ObservationId.of(WS, "obs-2");
        Observation merged = newObservation("obs-3", "merged content");

        ReconcileDecision.Merge decision = new ReconcileDecision.Merge(other, merged);

        assertThat(decision.getOtherId()).isEqualTo(other);
        assertThat(decision.getMerged()).isEqualTo(merged);
        assertThat(decision).isEqualTo(new ReconcileDecision.Merge(other, merged));
        assertThatThrownBy(() -> new ReconcileDecision.Merge(null, merged)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReconcileDecision.Merge(other, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Reject requires non-blank reason")
    void rejectContract() {
        ReconcileDecision.Reject decision = new ReconcileDecision.Reject("duplicate");
        assertThat(decision.getReason()).isEqualTo("duplicate");
        assertThat(decision).isEqualTo(new ReconcileDecision.Reject("duplicate"));

        assertThatThrownBy(() -> new ReconcileDecision.Reject(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReconcileDecision.Reject("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconcileDecision.Reject("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sealed permits exactly four variants")
    void sealedHierarchy() {
        Class<?>[] permitted = ReconcileDecision.class.getPermittedSubclasses();
        assertThat(permitted).isNotNull();
        assertThat(Arrays.stream(permitted).map(Class::getSimpleName)).containsExactlyInAnyOrder("Accept", "Replace",
                "Merge", "Reject");
    }

    @Test
    @DisplayName("instanceof pattern matching covers every variant")
    void patternMatchingCoversAllVariants() {
        ObservationId id = ObservationId.of(WS, "obs-x");
        Observation merged = newObservation("obs-y", "merged");
        List<ReconcileDecision> decisions = List.of(ReconcileDecision.Accept.instance(),
                new ReconcileDecision.Replace(id), new ReconcileDecision.Merge(id, merged),
                new ReconcileDecision.Reject("duplicate"));

        for (ReconcileDecision d : decisions) {
            String label;
            if (d instanceof ReconcileDecision.Accept) {
                label = "accept";
            } else if (d instanceof ReconcileDecision.Replace) {
                label = "replace";
            } else if (d instanceof ReconcileDecision.Merge) {
                label = "merge";
            } else if (d instanceof ReconcileDecision.Reject) {
                label = "reject";
            } else {
                throw new AssertionError("unreachable: sealed hierarchy must be exhaustive but saw " + d.getClass());
            }
            assertThat(label).isIn("accept", "replace", "merge", "reject");
        }
    }

    private static Observation newObservation(String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(SUBJECT).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .confidence(0.5d).build();
    }
}
