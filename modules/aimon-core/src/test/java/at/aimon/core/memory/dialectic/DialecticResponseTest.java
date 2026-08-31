package at.aimon.core.memory.dialectic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DialecticResponse")
class DialecticResponseTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));

    @Test
    @DisplayName("text() factory builds an empty-context response")
    void textFactory() {
        DialecticResponse r = DialecticResponse.text("hello");

        assertThat(r.getAnswer()).isEqualTo("hello");
        assertThat(r.getObservationsConsidered()).isEmpty();
        assertThat(r.getTokenUsage()).isEqualTo(TokenUsage.empty());
    }

    @Test
    @DisplayName("observations list is defensively copied")
    void observationsCopied() {
        Observation obs = Observation.builder().id(ObservationId.of(WS, "x")).subject(ALICE).observer(ALICE)
                .content("Alice prefers tea").type(ObservationType.EXPLICIT).confidence(0.9d).build();

        DialecticResponse r = DialecticResponse.builder().answer("tea").observationsConsidered(List.of(obs))
                .tokenUsage(TokenUsage.empty()).build();

        assertThat(r.getObservationsConsidered()).containsExactly(obs);
        assertThatThrownBy(() -> r.getObservationsConsidered().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null answer is rejected")
    void rejectsNullAnswer() {
        assertThatThrownBy(() -> DialecticResponse.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("answer");
    }
}
