package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LlmModel}'s {@code reasoningEffort} field.
 *
 * <p>
 * A sibling of {@code LlmModelTest}, which states its own focus as the per-request {@code requestTimeout}; this one
 * keeps that focus honest rather than widening it.
 */
@DisplayName("LlmModel - reasoningEffort")
class LlmModelReasoningEffortTest {

    @Test
    @DisplayName("reasoningEffort is empty by default")
    void reasoningEffortEmptyByDefault() {
        assertThat(LlmModel.builder().name("gpt-5.6-terra").build().getReasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("reasoningEffort is carried through the builder")
    void reasoningEffortCarriedThrough() {
        final LlmModel model = LlmModel.builder().name("gpt-5.6-terra").reasoningEffort(ReasoningEffort.HIGH).build();

        assertThat(model.getReasoningEffort()).contains(ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("null reasoningEffort leaves the field unset (no throw)")
    void nullReasoningEffortLeavesUnset() {
        assertThat(LlmModel.builder().reasoningEffort(null).build().getReasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("reasoningEffort participates in equals/hashCode")
    void reasoningEffortInEqualsAndHashCode() {
        final LlmModel a = LlmModel.builder().name("gpt-5").reasoningEffort(ReasoningEffort.LOW).build();
        final LlmModel b = LlmModel.builder().name("gpt-5").reasoningEffort(ReasoningEffort.LOW).build();
        final LlmModel different = LlmModel.builder().name("gpt-5").reasoningEffort(ReasoningEffort.HIGH).build();
        final LlmModel unset = LlmModel.builder().name("gpt-5").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(different);
        assertThat(a).isNotEqualTo(unset);
    }

    @Test
    @DisplayName("reasoningEffort appears in toString")
    void reasoningEffortInToString() {
        assertThat(LlmModel.builder().reasoningEffort(ReasoningEffort.MINIMAL).build().toString())
                .contains("reasoningEffort=MINIMAL");
    }
}
