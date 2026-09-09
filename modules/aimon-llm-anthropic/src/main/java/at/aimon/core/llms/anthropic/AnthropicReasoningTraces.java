package at.aimon.core.llms.anthropic;

import java.util.Objects;
import java.util.Optional;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import at.aimon.core.llm.ReasoningTrace;

/**
 * The only place an Anthropic thinking block is serialised or parsed.
 *
 * <p>
 * <strong>The mapper is the SDK's own {@link ObjectMappers#jsonMapper()}, and that is load-bearing rather than
 * stylistic.</strong> Every SDK model carries a {@code @JsonAnySetter}/{@code @JsonAnyGetter} pair, so the SDK mapper
 * round-trips a block losslessly <em>including a field this SDK version has never heard of</em> — which is what lets a
 * payload stored by one build be replayed by another after the server has added a field. A plain
 * {@code new ObjectMapper()} parses the same bytes and re-emits them with invented fields (an {@code isValid()}
 * accessor becomes {@code "valid":true}, an absent {@code Optional} becomes {@code null}, absent the SDK's
 * {@code NON_ABSENT} inclusion), and what leaves is a modified block. The server rejects those with
 * <em>"{@code thinking} or {@code redacted_thinking} blocks in the latest assistant message cannot be modified"</em>,
 * so writing the wrong mapper here does not degrade the feature — it breaks the turn.
 *
 * <p>
 * <strong>A stored payload is parsed into {@link ContentBlockParam}, never rebuilt through
 * {@code ThinkingBlockParam.builder()}.</strong> Two reasons. The union's deserializer dispatches on the {@code type}
 * discriminator, so one parse target covers {@code thinking} and {@code redacted_thinking} <em>and</em> survives a
 * block type Anthropic adds later, which falls through to the raw-JSON variant and is written straight back out. And
 * the {@code @JsonCreator} path defaults every {@code JsonField} to missing, so {@code checkRequired} is never
 * reached: rebuilding would re-validate a payload the <em>server itself</em> produced, turning "drop one trace" into a
 * thrown exception on a shape we have no business validating.
 */
final class AnthropicReasoningTraces {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    private AnthropicReasoningTraces() {
    }

    /**
     * Serialises a {@code thinking} block the model returned, for storage in a {@link ReasoningTrace} payload.
     *
     * @param block
     *            the thinking block (must not be null)
     * @return the payload
     * @throws IllegalStateException
     *             if the SDK cannot serialise its own model, which would be an SDK bug rather than bad data
     */
    static String payloadOf(ThinkingBlock block) {
        Objects.requireNonNull(block, "block");
        return serialise(block);
    }

    /**
     * Serialises a {@code redacted_thinking} block the model returned.
     *
     * <p>
     * A redacted block carries {@code data} where a signed one carries {@code signature} and {@code thinking}, and is
     * otherwise handled identically — same slot, same anchor, same replay rule. Filtering capture on
     * {@code isThinking()} alone is the vendor-documented way to break the multi-turn protocol, which is why this
     * method exists rather than a branch that skips the type.
     *
     * @param block
     *            the redacted thinking block (must not be null)
     * @return the payload
     */
    static String payloadOf(RedactedThinkingBlock block) {
        Objects.requireNonNull(block, "block");
        return serialise(block);
    }

    /**
     * Turns a stored trace back into the content block to replay, or drops it.
     *
     * <p>
     * Two reasons to drop, and both are reported rather than swallowed by the caller:
     *
     * <ul>
     * <li><strong>Foreign provider.</strong> A transcript outlives a client — a fallback policy can move a session
     * onto a different model mid-run, an operator can change the configured provider and resume — so an OpenAI
     * reasoning item can reach this method. Sending it to {@code /v1/messages} is a 400 at best.
     * <li><strong>Unparseable payload.</strong> A transcript written by a newer build, or a corrupted row. A dropped
     * trace costs re-derived reasoning; a thrown exception costs the session, so this never rethrows.
     * </ul>
     *
     * @param trace
     *            the stored trace (must not be null)
     * @param providerName
     *            this client's provider name (must not be null)
     * @return the block to replay, or empty when the trace is not ours or cannot be parsed
     */
    static Optional<ContentBlockParam> toBlockParam(ReasoningTrace trace, String providerName) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(providerName, "providerName");
        if (!isOurs(trace, providerName)) {
            return Optional.empty();
        }
        try {
            final JsonNode parsed = MAPPER.readTree(trace.getPayload());
            // The union's deserializer accepts anything and keeps what it does not recognise as raw JSON, which is
            // exactly the forward compatibility a future block type needs — and exactly why a corrupted payload has
            // to be rejected here instead. A content block is always a JSON object, so a payload that is not one can
            // never be a block, however well-formed the JSON is.
            if (!parsed.isObject()) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.treeToValue(parsed, ContentBlockParam.class));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a trace was authored by this provider. Used to tell "not ours" from "ours but broken", which are
     * different divergences and want different wording.
     *
     * @param trace
     *            the stored trace (must not be null)
     * @param providerName
     *            this client's provider name (must not be null)
     * @return {@code true} when the trace's provider matches
     */
    static boolean isOurs(ReasoningTrace trace, String providerName) {
        return providerName.equals(trace.getProviderName());
    }

    private static String serialise(Object block) {
        try {
            return MAPPER.writeValueAsString(block);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise an Anthropic thinking block", e);
        }
    }
}
