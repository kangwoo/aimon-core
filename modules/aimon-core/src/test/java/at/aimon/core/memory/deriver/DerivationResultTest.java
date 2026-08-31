package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DerivationResult")
class DerivationResultTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));

    @Test
    @DisplayName("empty() has zero observations and tokens")
    void emptyResult() {
        DerivationResult result = DerivationResult.empty();

        assertThat(result.getCreated()).isEmpty();
        assertThat(result.getUpdated()).isEmpty();
        assertThat(result.getLlmTokensUsed()).isZero();
        assertThat(result.totalObservations()).isZero();
    }

    @Test
    @DisplayName("totalObservations sums created + updated")
    void totals() {
        Observation o1 = obs("a", "alice likes tea");
        Observation o2 = obs("b", "alice dislikes coffee");

        DerivationResult result = DerivationResult.of(List.of(o1), List.of(o2), 100L);

        assertThat(result.totalObservations()).isEqualTo(2);
        assertThat(result.getLlmTokensUsed()).isEqualTo(100L);
    }

    @Test
    @DisplayName("negative llmTokensUsed is rejected")
    void rejectsNegativeTokens() {
        assertThatThrownBy(() -> DerivationResult.of(List.of(), List.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("llmTokensUsed");
    }

    private Observation obs(String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(ALICE).observer(ALICE).content(content)
                .type(ObservationType.EXPLICIT).confidence(0.8d).createdAt(Instant.now()).build();
    }
}
