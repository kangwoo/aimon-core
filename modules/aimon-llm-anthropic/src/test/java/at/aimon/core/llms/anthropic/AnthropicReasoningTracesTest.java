package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.llm.ReasoningTrace;

/**
 * Pins the payload round trip: what leaves this client is what arrived, field for field.
 *
 * <p>
 * Every comparison here is over a parsed tree rather than a substring, because a wrong mapper's corruption is an
 * <em>addition</em> — an invented {@code "valid": true}, an {@code Optional} accessor serialised as {@code null} — and
 * only a comparison that notices extra keys can fail on it.
 */
@DisplayName("AnthropicReasoningTraces - thinking block payload round trip")
class AnthropicReasoningTracesTest {

    private static final String PROVIDER = "Anthropic";

    private static final String SIGNATURE = "EqMBCkYICxIMabc/+DEF==\nnewline\ttab \"quote\" \\backslash";

    private static final String THINKING_JSON = """
            {"signature":"EqMBCkYICxIMabc","thinking":"Let me check the second constraint first.","type":"thinking"}""";

    private static final String REDACTED_JSON = """
            {"data":"EvgBCkYIARgCIkDxyz","type":"redacted_thinking"}""";

    private static ReasoningTrace trace(String payload) {
        return ReasoningTrace.builder().providerName(PROVIDER).payload(payload).build();
    }

    @Test
    @DisplayName("a thinking block survives capture and replay with no field added or lost")
    void thinkingBlockRoundTrips() {
        final ContentBlock block = AnthropicFixtures.contentBlock(THINKING_JSON);

        final String payload = AnthropicReasoningTraces.payloadOf(block.asThinking());
        final Optional<ContentBlockParam> replayed = AnthropicReasoningTraces.toBlockParam(trace(payload), PROVIDER);

        assertThat(replayed).isPresent();
        assertThat(serialise(replayed.get())).isEqualTo(AnthropicFixtures.treeOf(THINKING_JSON));
    }

    @Test
    @DisplayName("the signature string survives byte for byte, escapes included")
    void signatureSurvivesExactly() {
        final ContentBlock block = AnthropicFixtures.contentBlock(ObjectMappers.jsonMapper().createObjectNode()
                .put("type", "thinking").put("signature", SIGNATURE).put("thinking", "reasoning text").toString());

        final String payload = AnthropicReasoningTraces.payloadOf(block.asThinking());
        final ContentBlockParam replayed = AnthropicReasoningTraces.toBlockParam(trace(payload), PROVIDER)
                .orElseThrow();

        // The invariant this client owns: the decoded signature that leaves equals the one that arrived. Whether the
        // server's verifier accepts the re-serialised document is not something a fixture can establish (design §9
        // U-1), and this assertion deliberately does not claim it.
        assertThat(serialise(replayed).get("signature").asText()).isEqualTo(SIGNATURE);
        assertThat(block.asThinking().signature()).isEqualTo(SIGNATURE);
    }

    @Test
    @DisplayName("a redacted_thinking block round trips the same way, keeping its own type")
    void redactedThinkingBlockRoundTrips() {
        final ContentBlock block = AnthropicFixtures.contentBlock(REDACTED_JSON);

        final String payload = AnthropicReasoningTraces.payloadOf(block.asRedactedThinking());
        final ContentBlockParam replayed = AnthropicReasoningTraces.toBlockParam(trace(payload), PROVIDER)
                .orElseThrow();

        assertThat(serialise(replayed)).isEqualTo(AnthropicFixtures.treeOf(REDACTED_JSON));
        assertThat(replayed.isRedactedThinking()).isTrue();
    }

    @Test
    @DisplayName("a field this SDK version does not model survives the round trip")
    void additionalPropertiesSurvive() {
        final String withUnknownField = """
                {"signature":"sig","thinking":"why","type":"thinking","future_field":{"nested":[1,2]}}""";

        final ContentBlock block = AnthropicFixtures.contentBlock(withUnknownField);
        final String payload = AnthropicReasoningTraces.payloadOf(block.asThinking());
        final ContentBlockParam replayed = AnthropicReasoningTraces.toBlockParam(trace(payload), PROVIDER)
                .orElseThrow();

        assertThat(serialise(replayed)).isEqualTo(AnthropicFixtures.treeOf(withUnknownField));
    }

    @Test
    @DisplayName("a block type this build has never heard of still replays, through the raw-JSON fallback")
    void unknownBlockTypeSurvives() {
        final String unknownType = """
                {"type":"thinking_v2","signature":"sig","payload":"opaque"}""";

        final ContentBlockParam replayed = AnthropicReasoningTraces.toBlockParam(trace(unknownType), PROVIDER)
                .orElseThrow();

        assertThat(serialise(replayed)).isEqualTo(AnthropicFixtures.treeOf(unknownType));
    }

    @Test
    @DisplayName("the SDK mapper is load-bearing: a plain ObjectMapper corrupts the same payload")
    void plainObjectMapperWouldCorruptThePayload() throws Exception {
        final ContentBlock block = AnthropicFixtures.contentBlock(THINKING_JSON);

        final JsonNode viaSdkMapper = AnthropicFixtures.treeOf(AnthropicReasoningTraces.payloadOf(block.asThinking()));
        final JsonNode viaPlainMapper = new ObjectMapper().valueToTree(block.asThinking());

        assertThat(viaSdkMapper).isEqualTo(AnthropicFixtures.treeOf(THINKING_JSON));
        // Not an assertion about Jackson: it is the reason the class holds ObjectMappers.jsonMapper(). What a plain
        // mapper writes is a *modified* block, which the provider rejects outright rather than degrades.
        assertThat(viaPlainMapper).isNotEqualTo(viaSdkMapper);
    }

    @Test
    @DisplayName("a trace authored by another provider is dropped, not sent")
    void foreignTraceIsDropped() {
        final ReasoningTrace foreign = ReasoningTrace.builder().providerName("OpenAI").payload(THINKING_JSON).build();

        assertThat(AnthropicReasoningTraces.toBlockParam(foreign, PROVIDER)).isEmpty();
        assertThat(AnthropicReasoningTraces.isOurs(foreign, PROVIDER)).isFalse();
    }

    @Test
    @DisplayName("a subclass's provider name matches its own traces and nothing else")
    void providerMatchIsExactAndNotHardCoded() {
        final ReasoningTrace subclassTrace = ReasoningTrace.builder().providerName("MyAnthropic").payload(THINKING_JSON)
                .build();

        assertThat(AnthropicReasoningTraces.toBlockParam(subclassTrace, "MyAnthropic")).isPresent();
        assertThat(AnthropicReasoningTraces.toBlockParam(subclassTrace, PROVIDER)).isEmpty();
    }

    @Test
    @DisplayName("an unparseable payload costs the trace, never the turn")
    void unparseablePayloadReturnsEmptyRatherThanThrowing() {
        assertThat(AnthropicReasoningTraces.toBlockParam(trace("{not json"), PROVIDER)).isEmpty();
        assertThat(AnthropicReasoningTraces.toBlockParam(trace("[1,2,3]"), PROVIDER)).isEmpty();
    }

    private static JsonNode serialise(ContentBlockParam param) {
        try {
            return AnthropicFixtures.treeOf(ObjectMappers.jsonMapper().writeValueAsString(param));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
