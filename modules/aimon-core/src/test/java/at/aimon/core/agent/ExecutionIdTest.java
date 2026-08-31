package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

@DisplayName("ExecutionId Tests")
class ExecutionIdTest {

    @Test
    @DisplayName("Should create id via of() factory method")
    void of_validValue() {
        assertThat(ExecutionId.of("fork-123").value()).isEqualTo("fork-123");
    }

    @Test
    @DisplayName("Should create id via constructor")
    void constructor_validValue() {
        assertThat(new ExecutionId("rewake:env-456").value()).isEqualTo("rewake:env-456");
    }

    @Test
    @DisplayName("Should throw NullPointerException for null value")
    void constructor_null_throwsNPE() {
        assertThatThrownBy(() -> new ExecutionId(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for blank value")
    void constructor_blank_throwsIAE() {
        assertThatThrownBy(() -> new ExecutionId("  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be blank");
    }

    @Test
    @DisplayName("Should generate unique ids")
    void generate_returnsUniqueIds() {
        final Set<ExecutionId> ids = new HashSet<>();
        IntStream.range(0, 64).forEach(i -> ids.add(ExecutionId.generate()));

        assertThat(ids).hasSize(64);
    }

    /**
     * The prefix is a readability aid, not an identity: two runs of the same named thing must still be distinguishable,
     * which is the whole reason {@code generate(prefix)} exists rather than callers passing the name to {@code of}.
     */
    @Test
    @DisplayName("Should keep prefixed ids unique across runs of the same name")
    void generate_withPrefix_isPrefixedAndStillUnique() {
        final ExecutionId first = ExecutionId.generate("routine:nightly-report");
        final ExecutionId second = ExecutionId.generate("routine:nightly-report");

        assertThat(first.value()).startsWith("routine:nightly-report:");
        assertThat(second.value()).startsWith("routine:nightly-report:");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Should reject a null or blank prefix")
    void generate_withInvalidPrefix_throws() {
        assertThatThrownBy(() -> ExecutionId.generate(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("prefix cannot be null");
        assertThatThrownBy(() -> ExecutionId.generate(" ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix cannot be blank");
    }

    @Test
    @DisplayName("Should be equal for same value")
    void equals_sameValue() {
        assertThat(ExecutionId.of("abc")).isEqualTo(ExecutionId.of("abc")).isNotEqualTo(null).isNotEqualTo("abc");
    }

    /**
     * Pins the point of the type: an execution id and a session id that happen to spell the same string are not
     * interchangeable. Before this type existed, a fork's id <em>was</em> a {@code SessionId}, and nothing in the
     * type system objected when it was read as one.
     */
    @Test
    @DisplayName("Should never equal a SessionId carrying the same string")
    void equals_isNotInterchangeableWithSessionId() {
        final ExecutionId execution = ExecutionId.of("same-string");

        assertThat(execution).isNotEqualTo(SessionId.of("same-string"));
        assertThat((Object) SessionId.of("same-string")).isNotEqualTo(execution);
    }

    @Test
    @DisplayName("Should have consistent hashCode")
    void hashCode_consistency() {
        assertThat(ExecutionId.of("test")).hasSameHashCodeAs(ExecutionId.of("test"));
    }

    @Test
    @DisplayName("Should return value from toString")
    void toString_returnsValue() {
        assertThat(ExecutionId.of("my-run")).hasToString("my-run");
    }
}
