package at.aimon.core.llms.openai;

import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseReasoningItem;

import at.aimon.core.llm.ReasoningTrace;

/**
 * The only place an OpenAI reasoning item is serialised or parsed.
 *
 * <p>
 * <strong>The mapper is the SDK's own {@link ObjectMappers#jsonMapper()}, and that is load-bearing rather than
 * stylistic.</strong> Every SDK model carries a {@code @JsonAnySetter}/{@code @JsonAnyGetter} pair, so the SDK mapper
 * round-trips a reasoning item losslessly <em>including a field this SDK version has never heard of</em> — which is
 * what lets a payload stored by one build be replayed by another after the server has added a field. A plain
 * {@code new ObjectMapper()} parses the same bytes and re-emits them with two invented fields ({@code "valid":true}
 * from {@code isValid()}, {@code "content":null} from the {@code Optional} accessor, absent the SDK's
 * {@code NON_ABSENT} inclusion), and sending that back is a corrupted item. {@link OpenAIMessageConverter} holds a
 * plain {@code ObjectMapper}, so writing the wrong one here is the obvious mistake.
 *
 * <p>
 * <strong>A stored payload is never rebuilt through {@link ResponseReasoningItem#builder()}.</strong> That builder
 * requires {@code id} and {@code summary}; the {@code @JsonCreator} constructor does not, because its
 * {@code JsonField}s default to missing. Deserialising therefore bypasses {@code checkRequired} entirely, and the item
 * goes straight back out re-serialised without a single required accessor being called. Rebuilding it would introduce
 * validation failures on payloads the <em>server itself</em> produced — turning "drop one trace" into a thrown
 * exception on a shape we have no business validating.
 */
final class OpenAiReasoningTraces {

    private static final JsonMapper MAPPER = ObjectMappers.jsonMapper();

    private OpenAiReasoningTraces() {
    }

    /**
     * Turns a reasoning item the model returned into a storable trace.
     *
     * @param item
     *            the reasoning item (must not be null)
     * @param providerName
     *            this client's provider name (must not be null)
     * @param toolUseId
     *            the {@code call_id} of the tool call this item immediately precedes, or {@code null} when it precedes
     *            the assistant's text / the end of the turn
     * @return the trace
     * @throws IllegalStateException
     *             if the SDK cannot serialise its own model, which would be an SDK bug rather than bad data
     */
    static ReasoningTrace toTrace(ResponseReasoningItem item, String providerName, String toolUseId) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(providerName, "providerName");
        try {
            return ReasoningTrace.builder().providerName(providerName).payload(MAPPER.writeValueAsString(item))
                    .toolUseId(toolUseId).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise an OpenAI reasoning item", e);
        }
    }

    /**
     * Turns a stored trace back into the reasoning item to replay, or drops it.
     *
     * <p>
     * Two reasons to drop, and both are reported rather than swallowed by the caller:
     *
     * <ul>
     * <li><strong>Foreign provider.</strong> A transcript outlives a client — a fallback policy can move a session
     * onto a different model mid-run, an operator can change the configured provider and resume — so an Anthropic
     * thinking block can reach this method. Sending it to {@code /v1/responses} is a 400 at best.
     * <li><strong>Unparseable payload.</strong> A transcript written by a newer build, or a corrupted row. A dropped
     * trace costs re-derived reasoning; a thrown exception costs the session, so this never rethrows.
     * </ul>
     *
     * @param trace
     *            the stored trace (must not be null)
     * @param providerName
     *            this client's provider name (must not be null)
     * @return the item to replay, or empty when the trace is not ours or cannot be parsed
     */
    static Optional<ResponseReasoningItem> toItem(ReasoningTrace trace, String providerName) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(providerName, "providerName");
        if (!providerName.equals(trace.getProviderName())) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(trace.getPayload(), ResponseReasoningItem.class));
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
}
