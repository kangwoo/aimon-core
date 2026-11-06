package at.aimon.core.tools.code;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One call a program made, as the bridge recorded it — for {@code ExecuteCode}'s render payload and summary line
 * ({@code docs/design/tool/programmatic-tool-calling.md} §12.2). Never serialized to the model.
 *
 * <p>
 * Immutable.
 */
final class NestedToolCall {

    /** The longest input summary a record keeps. */
    static final int MAX_INPUT_SUMMARY_CHARS = 200;

    private final String toolUseId;
    private final String toolName;
    private final String inputSummary;
    private final int resultLength;
    private final boolean error;
    private final String refusal;
    private final long durationMillis;

    NestedToolCall(String toolUseId, String toolName, String inputSummary, int resultLength, boolean error,
            String refusal, long durationMillis) {
        this.toolUseId = Objects.requireNonNull(toolUseId, "toolUseId cannot be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName cannot be null");
        this.inputSummary = inputSummary == null ? "" : inputSummary;
        this.resultLength = resultLength;
        this.error = error;
        this.refusal = refusal;
        this.durationMillis = durationMillis;
    }

    String toolName() {
        return toolName;
    }

    boolean isError() {
        return error;
    }

    /** The bridge's own refusal kind ({@code "write cap"}, {@code "not callable"}, ...), or null when dispatched. */
    String refusal() {
        return refusal;
    }

    Map<String, Object> toPayload() {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolUseId", toolUseId);
        payload.put("name", toolName);
        payload.put("input", inputSummary);
        payload.put("resultLength", resultLength);
        payload.put("error", error);
        if (refusal != null) {
            payload.put("refused", refusal);
        }
        payload.put("durationMs", durationMillis);
        return payload;
    }
}
