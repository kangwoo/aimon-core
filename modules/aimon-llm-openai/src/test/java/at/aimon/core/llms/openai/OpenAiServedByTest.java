package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ServiceTier;

class OpenAiServedByTest {

    @Test
    @DisplayName("OpenAI's own vocabulary is mapped explicitly: default -> standard, priority -> fast")
    void openAiVocabulary() {
        assertThat(OpenAiServedBy.fromWire(Optional.of("default"))).isEqualTo(ServiceTier.STANDARD);
        assertThat(OpenAiServedBy.fromWire(Optional.of("priority"))).isEqualTo(ServiceTier.FAST);
        assertThat(OpenAiServedBy.fromWire(Optional.of("flex"))).isEqualTo(ServiceTier.FLEX);
        assertThat(OpenAiServedBy.fromWire(Optional.of("batch"))).isEqualTo(ServiceTier.BATCH);
    }

    @Test
    @DisplayName("a gateway already answering in the neutral vocabulary maps to itself")
    void neutralVocabulary() {
        assertThat(OpenAiServedBy.fromWire(Optional.of("standard"))).isEqualTo(ServiceTier.STANDARD);
        assertThat(OpenAiServedBy.fromWire(Optional.of("fast"))).isEqualTo(ServiceTier.FAST);
    }

    @Test
    @DisplayName("auto is a request instruction, not an applied tier; unknown values are preserved")
    void autoAndUnknown() {
        assertThat(OpenAiServedBy.fromWire(Optional.of("auto"))).isNull();
        assertThat(OpenAiServedBy.fromWire(Optional.empty())).isNull();
        final ServiceTier scale = OpenAiServedBy.fromWire(Optional.of("scale"));
        assertThat(scale.getValue()).isEqualTo("scale");
        assertThat(scale.isNormalized()).isFalse();
    }
}
