package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TurnId Tests")
class TurnIdTest {

    @Test
    @DisplayName("Should create ID via of() factory method")
    void of_validValue() {
        TurnId id = TurnId.of("turn-123");

        assertThat(id.value()).isEqualTo("turn-123");
    }

    @Test
    @DisplayName("Should create ID via constructor")
    void constructor_validValue() {
        TurnId id = new TurnId("turn-456");

        assertThat(id.value()).isEqualTo("turn-456");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null value")
    void constructor_null_throwsNPE() {
        assertThatThrownBy(() -> new TurnId(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank value")
    void constructor_blank_throwsIAE() {
        assertThatThrownBy(() -> new TurnId("  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
        assertThatThrownBy(() -> TurnId.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should generate unique IDs")
    void generate_returnsUniqueIds() {
        // Two turns of the same session must never collide: the whole point of the id is to tell them apart.
        assertThat(IntStream.range(0, 500).mapToObj(i -> TurnId.generate().value()).collect(Collectors.toSet()))
                .hasSize(500);
    }

    @Test
    @DisplayName("Should be equal for same value")
    void equals_sameValue() {
        TurnId id1 = TurnId.of("abc");
        TurnId id2 = TurnId.of("abc");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isEqualTo(id1);
        assertThat(id1).isNotEqualTo(TurnId.of("abd"));
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo("abc");
    }

    @Test
    @DisplayName("Should have consistent hashCode")
    void hashCode_consistency() {
        assertThat(TurnId.of("test").hashCode()).isEqualTo(TurnId.of("test").hashCode());
    }

    @Test
    @DisplayName("Should survive an encode/decode round trip through its value")
    void valueRoundTripsThroughTheWire() {
        // The id crosses node boundaries as a bare string (inbox envelope, signal payload), so of(value()) has to be
        // the identity — an id that changed shape in transit could not be matched against the active turn.
        TurnId original = TurnId.generate();

        assertThat(TurnId.of(original.value())).isEqualTo(original);
        assertThat(TurnId.of(original.toString())).isEqualTo(original);
    }

    @Test
    @DisplayName("Should be usable as a map key")
    void isUsableAsMapKey() {
        Map<TurnId, String> byTurn = Map.of(TurnId.of("t1"), "first", TurnId.of("t2"), "second");

        assertThat(byTurn).containsEntry(TurnId.of("t1"), "first").containsEntry(TurnId.of("t2"), "second");
    }

    @Test
    @DisplayName("Should return value from toString")
    void toString_returnsValue() {
        assertThat(TurnId.of("my-turn").toString()).isEqualTo("my-turn");
    }
}
