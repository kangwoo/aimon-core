package at.aimon.core.agent.tool.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.llm.ToolUseResult;

/**
 * Converts {@link ToolExecutionResult} to {@link ToolUseResult}.
 *
 * <p>
 * This converter bridges the gap between the tool execution layer and the LLM layer by transforming execution results
 * into the format expected by LLM clients.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutionResult executionResult = ToolExecutionResult.of("tool_123", ToolResult.success("Done"));
 *
 *     ToolUseResult useResult = ToolExecutionResultConverter.toToolUseResult(executionResult);
 *     // useResult.getToolUseId() == "tool_123"
 *     // useResult.getContent() == "Done"
 *     // useResult.isSuccess() == true
 * }
 * </pre>
 */
public final class ToolExecutionResultConverter {

    /**
     * Private constructor to prevent instantiation.
     */
    private ToolExecutionResultConverter() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    /**
     * Converts a {@link ToolExecutionResult} to a {@link ToolUseResult}.
     *
     * <p>
     * The conversion maps:
     * <ul>
     * <li>toolExecutionId → toolUseId</li>
     * <li>toolResult.content → content</li>
     * <li>toolResult.isError → isError</li>
     * <li>toolResult.renderPayload → renderPayload (opaque sidecar)</li>
     * </ul>
     *
     * @param executionResult
     *            The tool execution result to convert (must not be null)
     * @return A new ToolUseResult with the converted data
     * @throws NullPointerException
     *             if executionResult is null
     */
    public static ToolUseResult toToolUseResult(ToolExecutionResult executionResult) {
        Objects.requireNonNull(executionResult, "ToolExecutionResult cannot be null");

        String toolUseId = executionResult.getToolExecutionId();

        ToolUseResult result;
        if (executionResult.isError()) {
            result = ToolUseResult.error(toolUseId, executionResult.getContent());
        } else {
            result = ToolUseResult.success(toolUseId, executionResult.getContent());
        }

        Map<String, Object> renderPayload = executionResult.getRenderPayload();
        if (renderPayload != null) {
            result = result.withRenderPayload(renderPayload);
        }
        return result;
    }

    /**
     * {@link ToolExecutionResult} 목록을 {@link ToolUseResult} 목록으로 변환한다.
     *
     * @param executionResults
     *            변환할 실행 결과 목록 (null 불가)
     * @return 변환된 ToolUseResult 목록
     */
    public static List<ToolUseResult> toToolUseResults(List<ToolExecutionResult> executionResults) {
        Objects.requireNonNull(executionResults, "ToolExecutionResults list cannot be null");
        return executionResults.stream().map(ToolExecutionResultConverter::toToolUseResult).toList();
    }
}
