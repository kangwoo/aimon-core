package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseReasoningItem;

import at.aimon.core.llm.ReasoningTrace;

/**
 * Tests the one place an OpenAI reasoning item is serialised or parsed.
 *
 * <p>
 * <strong>The store-and-replay assertion compares trees, not substrings.</strong> A plain {@code ObjectMapper}
 * <em>preserves</em> {@code encrypted_content} and unknown fields, so a {@code contains} assertion is green under the
 * wrong mapper; its corruption is the <em>addition</em> of {@code "valid":true} (from the model's {@code isValid()})
 * and {@code "content":null} (from an {@code Optional} accessor, absent the SDK's {@code NON_ABSENT} inclusion). Only
 * a comparison that notices extra keys can fail on it.
 */
@DisplayName("OpenAiReasoningTraces")
class OpenAiReasoningTracesTest {

    private static final String ITEM = "{\"id\":\"rs_abc\",\"summary\":[{\"text\":\"think\","
            + "\"type\":\"summary_text\"}],\"type\":\"reasoning\",\"encrypted_content\":\"gAAAAA==\","
            + "\"status\":\"completed\",\"future_field\":{\"x\":1}}";

    private static ResponseReasoningItem parse(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, ResponseReasoningItem.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode tree(String json) {
        try {
            return ObjectMappers.jsonMapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("an item survives store-and-replay byte-for-byte, INCLUDING a field this SDK does not model")
    void storeAndReplayIsLossless() {
        // future_field is the point. A newer server adds a field, this build stores the payload, and a later replay
        // must send it back unchanged -- which is only true because every SDK model carries a
        // @JsonAnySetter/@JsonAnyGetter pair and the SDK's own mapper is used.
        final ReasoningTrace trace = OpenAiReasoningTraces.toTrace(parse(ITEM), "OpenAI", "call_1");

        assertThat(tree(trace.getPayload())).isEqualTo(tree(ITEM));
        assertThat(trace.getPayload()).doesNotContain("\"valid\"").doesNotContain("\"content\"");

        final Optional<ResponseReasoningItem> replayed = OpenAiReasoningTraces.toItem(trace, "OpenAI");
        assertThat(replayed).isPresent();
        assertThat(tree(ObjectMappers.jsonMapper().valueToTree(replayed.orElseThrow()).toString()))
                .isEqualTo(tree(ITEM));
    }

    @Test
    @DisplayName("the anchor and provider name are carried on the trace, not inside the payload")
    void anchorAndProviderAreOnTheTrace() {
        final ReasoningTrace anchored = OpenAiReasoningTraces.toTrace(parse(ITEM), "OpenAI", "call_1");
        final ReasoningTrace unanchored = OpenAiReasoningTraces.toTrace(parse(ITEM), "OpenAI", null);

        assertThat(anchored.getProviderName()).isEqualTo("OpenAI");
        assertThat(anchored.getToolUseId()).contains("call_1");
        assertThat(unanchored.getToolUseId()).isEmpty();
        // Same payload either way: the anchor is core's, not the provider's.
        assertThat(anchored.getPayload()).isEqualTo(unanchored.getPayload());
    }

    @Test
    @DisplayName("a trace authored by another provider is dropped")
    void aForeignTraceIsDropped() {
        final ReasoningTrace anthropic = ReasoningTrace.builder().providerName("Anthropic")
                .payload("{\"type\":\"thinking\",\"thinking\":\"...\",\"signature\":\"abc\"}").build();

        assertThat(OpenAiReasoningTraces.toItem(anthropic, "OpenAI")).isEmpty();
        assertThat(OpenAiReasoningTraces.isOurs(anthropic, "OpenAI")).isFalse();
    }

    @Test
    @DisplayName("an unparseable payload is dropped, never thrown")
    void anUnparseablePayloadIsDropped() {
        // A transcript written by a newer build, or a corrupted row. A dropped trace costs re-derived reasoning; a
        // thrown exception costs the session.
        final ReasoningTrace broken = ReasoningTrace.builder().providerName("OpenAI").payload("{not json").build();

        assertThat(OpenAiReasoningTraces.toItem(broken, "OpenAI")).isEmpty();
        // ...and it is distinguishable from the foreign case, which wants different wording in the warning.
        assertThat(OpenAiReasoningTraces.isOurs(broken, "OpenAI")).isTrue();
    }

    @Test
    @DisplayName("an item missing id and summary still round-trips -- deserialisation bypasses checkRequired")
    void anItemMissingRequiredFieldsIsNotRebuilt() {
        // ResponseReasoningItem.builder() requires id and summary. A payload stored by one build and replayed by
        // another could be missing either, and rebuilding through the builder would throw on a shape the SERVER
        // produced. Deserialisation takes the @JsonCreator constructor instead, whose JsonFields default to missing,
        // so no required accessor ever fires. This test fails the moment someone "cleans up" toItem to use a builder.
        final String thin = "{\"type\":\"reasoning\",\"encrypted_content\":\"ZZZ\"}";
        final ReasoningTrace trace = ReasoningTrace.builder().providerName("OpenAI").payload(thin).build();

        final Optional<ResponseReasoningItem> item = OpenAiReasoningTraces.toItem(trace, "OpenAI");

        assertThat(item).isPresent();
        assertThat(tree(ObjectMappers.jsonMapper().valueToTree(item.orElseThrow()).toString())).isEqualTo(tree(thin));
    }
}
