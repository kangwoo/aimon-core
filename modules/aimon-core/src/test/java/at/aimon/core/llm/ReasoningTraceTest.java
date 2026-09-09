package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the value semantics of the reasoning slot.
 *
 * <p>
 * The {@code toString} test is not cosmetic. A reasoning payload is an opaque provider blob that lands in the
 * persisted transcript; printing it into a log line would be noise at best and a leak at worst, so the length is
 * printed instead and a test says so.
 */
@DisplayName("ReasoningTrace")
class ReasoningTraceTest {

    private static final String PAYLOAD = "{\"type\":\"reasoning\",\"encrypted_content\":\"ZZZ\"}";

    @Test
    @DisplayName("a built trace returns exactly what it was given")
    void builderRoundTrip() {
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD)
                .toolUseId("call_1").build();

        assertThat(trace.getProviderName()).isEqualTo("OpenAI");
        assertThat(trace.getPayload()).isEqualTo(PAYLOAD);
        assertThat(trace.getToolUseId()).contains("call_1");
    }

    @Test
    @DisplayName("the anchor is optional")
    void toolUseIdIsOptional() {
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD).build();

        assertThat(trace.getToolUseId()).isEmpty();
    }

    @Test
    @DisplayName("a blank or missing provider name is rejected")
    void providerNameIsRequired() {
        assertThatThrownBy(() -> ReasoningTrace.builder().payload(PAYLOAD).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReasoningTrace.builder().providerName("  ").payload(PAYLOAD).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a blank or missing payload is rejected")
    void payloadIsRequired() {
        assertThatThrownBy(() -> ReasoningTrace.builder().providerName("OpenAI").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ReasoningTrace.builder().providerName("OpenAI").payload("").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("equals and hashCode cover all three fields")
    void valueSemantics() {
        final ReasoningTrace base = ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD).toolUseId("call_1")
                .build();

        assertThat(base).isEqualTo(
                ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD).toolUseId("call_1").build());
        assertThat(base).hasSameHashCodeAs(
                ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD).toolUseId("call_1").build());

        assertThat(base).isNotEqualTo(
                ReasoningTrace.builder().providerName("Anthropic").payload(PAYLOAD).toolUseId("call_1").build());
        assertThat(base).isNotEqualTo(
                ReasoningTrace.builder().providerName("OpenAI").payload("{}").toolUseId("call_1").build());
        assertThat(base).isNotEqualTo(ReasoningTrace.builder().providerName("OpenAI").payload(PAYLOAD).build());
    }

    @Test
    @DisplayName("toString prints the payload's length, never the payload")
    void toStringDoesNotLeakThePayload() {
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI").payload("SECRET-CIPHERTEXT-BLOB")
                .build();

        assertThat(trace.toString()).doesNotContain("SECRET-CIPHERTEXT-BLOB").contains("payloadLength=22")
                .contains("OpenAI");
    }
}
