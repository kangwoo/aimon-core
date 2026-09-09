package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import at.aimon.core.llm.ReasoningEffort;

@DisplayName("OpenAiReasoningEfforts - neutral to wire mapping")
class OpenAiReasoningEffortsTest {

    @ParameterizedTest
    @EnumSource(ReasoningEffort.class)
    @DisplayName("every neutral level maps to an SDK value")
    void everyLevelMaps(ReasoningEffort effort) {
        // The switch is exhaustive over the neutral enum with no default, so a new constant is a compile error rather
        // than a silent fall-through; this test says the mapping is total today.
        assertThat(OpenAiReasoningEfforts.toWire(effort)).isNotNull();
    }

    @Test
    @DisplayName("each level maps to its own wire value")
    void levelsMapOneToOne() {
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.NONE))
                .isEqualTo(com.openai.models.ReasoningEffort.NONE);
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.MINIMAL))
                .isEqualTo(com.openai.models.ReasoningEffort.MINIMAL);
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.LOW)).isEqualTo(com.openai.models.ReasoningEffort.LOW);
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.MEDIUM))
                .isEqualTo(com.openai.models.ReasoningEffort.MEDIUM);
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.HIGH))
                .isEqualTo(com.openai.models.ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("NONE is the wire value the tool-calling clamp sends")
    void noneIsTheClampValue() {
        assertThat(OpenAiReasoningEfforts.toWire(ReasoningEffort.NONE).asString()).isEqualTo("none");
    }
}
