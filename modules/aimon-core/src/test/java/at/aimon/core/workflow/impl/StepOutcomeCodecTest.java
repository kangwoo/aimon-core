package at.aimon.core.workflow.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.workflow.StepOutcome;

@DisplayName("StepOutcomeCodec — versioned JSON round-trip")
class StepOutcomeCodecTest {

    private final StepOutcomeCodec codec = new StepOutcomeCodec();

    @Test
    @DisplayName("encode then decode reproduces the outcome (with structured output)")
    void roundTripWithStructured() {
        final StepOutcome original = StepOutcome.builder().text("the answer").structured(Map.of("score", 5, "ok", true))
                .totalTokens(1234).costMicros(567).completionReason(CompletionReason.COMPLETED).inputHash("h1")
                .structureFingerprint("fp1").build();

        final StepOutcome decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("round-trips an outcome with no structured output and zero token/cost")
    void roundTripMinimal() {
        final StepOutcome original = StepOutcome.builder().text("plain").completionReason(CompletionReason.COMPLETED)
                .inputHash("h2").structureFingerprint("fp2").build();

        final StepOutcome decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.structured()).isEmpty();
    }

    @Test
    @DisplayName("preserves a JSON null value inside structured output")
    void roundTripStructuredNullValue() {
        final Map<String, Object> structured = new HashMap<>();
        structured.put("maybe", null);
        final StepOutcome original = StepOutcome.builder().text("t").structured(structured)
                .completionReason(CompletionReason.COMPLETED).inputHash("h3").structureFingerprint("fp3").build();

        final StepOutcome decoded = codec.decode(codec.encode(original));

        assertThat(decoded.structured()).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("maybe", null);
    }

    @Test
    @DisplayName("decode rejects malformed JSON, wrong version, and missing required fields")
    void decodeRejectsInvalid() {
        assertThatThrownBy(() -> codec.decode("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("[1,2,3]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec
                .decode("{\"fv\":999,\"text\":\"x\",\"completionReason\":\"COMPLETED\"," + "\"inputHash\":\"h\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"fv\":2,\"text\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"fv\":2,\"text\":\"x\",\"completionReason\":\"NOPE\","
                + "\"inputHash\":\"h\",\"structureFingerprint\":\"fp\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode rejects a v2 object missing the structureFingerprint (Phase 4 required field)")
    void decodeRejectsMissingFingerprint() {
        assertThatThrownBy(
                () -> codec.decode("{\"fv\":2,\"text\":\"x\",\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode rejects a legacy v1 object → cache miss (fail-safe)")
    void decodeRejectsLegacyV1() {
        assertThatThrownBy(
                () -> codec.decode("{\"fv\":1,\"text\":\"x\",\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decode rejects tampered negative accounting values (upstream treats it as a cache miss)")
    void decodeRejectsNegativeAccounting() {
        assertThatThrownBy(() -> codec.decode("{\"fv\":2,\"text\":\"x\",\"totalTokens\":-5,"
                + "\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\",\"structureFingerprint\":\"fp\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"fv\":2,\"text\":\"x\",\"costMicros\":-1,"
                + "\"completionReason\":\"COMPLETED\",\"inputHash\":\"h\",\"structureFingerprint\":\"fp\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
