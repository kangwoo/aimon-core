package at.aimon.core.toolinvocation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.EnvironmentNotices;

/**
 * Conversions between {@link ToolDispatchResult} and the {@link ToolUseResult} a loop records in its transcript.
 *
 * <p>
 * The conversion to {@code ToolUseResult} is where the side channels meet the text, and it happens once, in the loop
 * that hands the result to the model: the hooks' advisory feedback is appended inside a
 * {@code <system-reminder key="hook-feedback">} block ({@link HookFeedback#toReminderBlock}) and the environment's
 * notices are prepended as {@value EnvironmentNotices#PREFIX} lines ({@link EnvironmentNotices#render}). The render
 * payload and the retryable flag carry across unchanged; both are invisible to the model.
 */
public final class ToolDispatchResults {

    /**
     * The error text a model-facing loop answers a tool call with when its context carries no
     * {@link at.aimon.core.agent.tool.ToolCallDispatcher}: the call is refused, not run around the gates. The main loop
     * and a fork's loop share it, so the two cannot drift apart.
     */
    public static final String NO_DISPATCHER_MESSAGE = "Tool call refused: no tool call dispatcher is bound to this"
            + " execution";

    private ToolDispatchResults() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Builds a dispatch result from a tool-use result and the side channels collected beside it.
     *
     * @param result
     *            the text result, its error and retryable flags and render payload (must not be null)
     * @param notices
     *            the environment's notices (must not be null)
     * @param hookFeedback
     *            the hooks' advisory feedback (must not be null)
     * @return the dispatch result (never null)
     */
    public static ToolDispatchResult of(ToolUseResult result, List<String> notices, List<String> hookFeedback) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(notices, "notices must not be null");
        Objects.requireNonNull(hookFeedback, "hookFeedback must not be null");
        return ToolDispatchResult.builder().toolUseId(result.getToolUseId()).text(result.getContent())
                .error(result.isError()).retryable(result.isRetryable()).renderPayload(result.getRenderPayload())
                .notices(notices).hookFeedback(hookFeedback).build();
    }

    /**
     * Builds an error dispatch result with no side channels.
     *
     * @param toolUseId
     *            the id of the call it answers (must not be null)
     * @param message
     *            the error text (must not be null)
     * @return the dispatch result (never null)
     */
    public static ToolDispatchResult error(String toolUseId, String message) {
        return of(ToolUseResult.error(toolUseId, message), List.of(), List.of());
    }

    /**
     * Turns a dispatch result into the tool-use result a model-facing loop records: the hook feedback appended, the
     * notices prepended.
     *
     * <p>
     * A feedback block that cannot be rendered is left out rather than failing the conversion — feedback is auxiliary
     * and must never cost the model the tool result it is waiting on.
     *
     * @param result
     *            the dispatch result (must not be null)
     * @return the tool-use result (never null)
     */
    public static ToolUseResult toToolUseResult(ToolDispatchResult result) {
        Objects.requireNonNull(result, "result must not be null");
        String content = result.getText();
        final Optional<String> block = HookFeedback.toReminderBlock(result.getHookFeedback());
        if (block.isPresent()) {
            content = content.isEmpty() ? block.get() : content + "\n\n" + block.get();
        }
        content = EnvironmentNotices.render(result.getNotices()) + content;
        ToolUseResult converted = result.isError()
                ? ToolUseResult.error(result.getToolUseId(), content)
                : ToolUseResult.success(result.getToolUseId(), content);
        if (!result.isRetryable()) {
            converted = converted.notRetryable();
        }
        final Optional<Map<String, Object>> payload = result.getRenderPayload();
        return payload.isPresent() ? converted.withRenderPayload(payload.get()) : converted;
    }
}
