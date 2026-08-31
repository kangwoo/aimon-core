package at.aimon.core.subagent.task.codec;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.task.TaskResult;

/**
 * Default {@link TaskResultCodec} that maps a result to a versioned, flat JSON document by hand.
 *
 * <p>
 * Every field is written explicitly, as in {@link JsonSessionSnapshotCodec}, so nothing depends on reflection over the
 * core types and the document stays readable to a human debugging a stalled task. The top-level object carries a format
 * {@code version} so a future schema change is detected rather than silently mis-parsed.
 *
 * <p>
 * <b>Forward tolerance.</b> Unknown top-level fields are ignored on decode. An unknown
 * {@link CompletionReason} name is <em>not</em> fatal either — unlike the snapshot codec, where an unknown message role
 * makes a transcript unreconstructable, the reason here is metadata sitting next to the payload that actually matters.
 * Dropping a whole answer because a newer node named its stop reason something this one has not heard of would be the
 * worse failure, so an unreadable reason decodes as {@link CompletionReason#COMPLETED} for a success and
 * {@link CompletionReason#ERROR} for a failure — the coarse fact the {@code success} flag already carries.
 *
 * <p>
 * Stateless and thread-safe: the shared {@link ObjectMapper} is used only for tree building and text I/O.
 */
public final class JsonTaskResultCodec implements TaskResultCodec {

    /** Current serialization format version. */
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIELD_VERSION = "version";
    private static final String FIELD_SUCCESS = "success";
    private static final String FIELD_FINAL_ANSWER = "finalAnswer";
    private static final String FIELD_ERROR_MESSAGE = "errorMessage";
    private static final String FIELD_COMPLETION_REASON = "completionReason";
    private static final String FIELD_ITERATION_COUNT = "iterationCount";
    private static final String FIELD_DURATION_MILLIS = "durationMillis";
    private static final String FIELD_TOTAL_TOKENS = "totalTokens";
    private static final String FIELD_SUMMARY_TRUNCATED = "summaryTruncated";

    @Override
    public String encode(TaskResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        try {
            final ObjectNode root = MAPPER.createObjectNode();
            root.put(FIELD_VERSION, FORMAT_VERSION);
            root.put(FIELD_SUCCESS, result.isSuccess());
            result.getFinalAnswer().ifPresent(value -> root.put(FIELD_FINAL_ANSWER, value));
            result.getErrorMessage().ifPresent(value -> root.put(FIELD_ERROR_MESSAGE, value));
            root.put(FIELD_COMPLETION_REASON, result.getCompletionReason().name());
            root.put(FIELD_ITERATION_COUNT, result.getIterationCount());
            root.put(FIELD_DURATION_MILLIS, result.getDurationMillis());
            root.put(FIELD_TOTAL_TOKENS, result.getTotalTokens());
            root.put(FIELD_SUMMARY_TRUNCATED, result.isSummaryTruncated());
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new TaskResultCodecException("Failed to encode task result: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskResult decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded cannot be null");
        try {
            final JsonNode root = MAPPER.readTree(encoded);
            if (root == null || !root.isObject()) {
                throw new TaskResultCodecException("Encoded task result is not a JSON object");
            }
            final int version = root.path(FIELD_VERSION).asInt(-1);
            if (version != FORMAT_VERSION) {
                throw new TaskResultCodecException(
                        "Unsupported task result format version: " + version + " (expected " + FORMAT_VERSION + ")");
            }
            final boolean success = root.path(FIELD_SUCCESS).asBoolean(false);
            return TaskResult.builder().success(success).finalAnswer(optionalText(root, FIELD_FINAL_ANSWER))
                    .errorMessage(optionalText(root, FIELD_ERROR_MESSAGE))
                    .completionReason(decodeCompletionReason(root, success))
                    .iterationCount(root.path(FIELD_ITERATION_COUNT).asInt(0))
                    .durationMillis(root.path(FIELD_DURATION_MILLIS).asLong(0L))
                    .totalTokens(root.path(FIELD_TOTAL_TOKENS).asInt(0))
                    .summaryTruncated(root.path(FIELD_SUMMARY_TRUNCATED).asBoolean(false)).build();
        } catch (TaskResultCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskResultCodecException("Failed to decode task result: " + e.getMessage(), e);
        }
    }

    private static String optionalText(JsonNode root, String field) {
        final JsonNode value = root.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static CompletionReason decodeCompletionReason(JsonNode root, boolean success) {
        final String name = optionalText(root, FIELD_COMPLETION_REASON);
        if (name != null) {
            try {
                return CompletionReason.valueOf(name);
            } catch (IllegalArgumentException e) {
                // Fall through to the coarse reason the success flag already implies — see the type javadoc.
            }
        }
        return success ? CompletionReason.COMPLETED : CompletionReason.ERROR;
    }
}
