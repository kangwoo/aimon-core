package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionId Tests")
class SessionIdTest {

    @Test
    @DisplayName("Should create ID via of() factory method")
    void of_validValue() {
        SessionId id = SessionId.of("conv-123");

        assertThat(id.value()).isEqualTo("conv-123");
    }

    @Test
    @DisplayName("Should create ID via constructor")
    void constructor_validValue() {
        SessionId id = new SessionId("session-456");

        assertThat(id.value()).isEqualTo("session-456");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null value")
    void constructor_null_throwsNPE() {
        assertThatThrownBy(() -> new SessionId(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank value")
    void constructor_blank_throwsIAE() {
        assertThatThrownBy(() -> new SessionId("  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    @DisplayName("Should generate unique IDs")
    void generate_returnsUniqueIds() {
        SessionId id1 = SessionId.generate();
        SessionId id2 = SessionId.generate();

        assertThat(id1).isNotEqualTo(id2);
        assertThat(id1.value()).isNotEmpty();
    }

    @Test
    @DisplayName("Should be equal for same value")
    void equals_sameValue() {
        SessionId id1 = SessionId.of("abc");
        SessionId id2 = SessionId.of("abc");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(null);
        assertThat(id1).isNotEqualTo("abc");
    }

    @Test
    @DisplayName("Should have consistent hashCode")
    void hashCode_consistency() {
        SessionId id1 = SessionId.of("test");
        SessionId id2 = SessionId.of("test");

        assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
    }

    @Test
    @DisplayName("Should return value from toString")
    void toString_returnsValue() {
        SessionId id = SessionId.of("my-conv");

        assertThat(id.toString()).isEqualTo("my-conv");
    }
}
