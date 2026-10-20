package at.aimon.core.agent.tool;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;

import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * The runner and listener guards both dispatchers in this package share, so a runner contract violation or a throwing
 * listener is isolated the same way on every path. Each method logs to the caller's own logger.
 */
final class ToolDispatchCallbacks {

    private ToolDispatchCallbacks() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Runs one call, turning a {@code null} result or an exception into an error result. Errors propagate — what to do
     * with one depends on the thread it lands on.
     */
    static ToolUseResult runSafely(Logger log, Function<ToolUse, ToolUseResult> runner, ToolUse toolUse) {
        try {
            final ToolUseResult result = runner.apply(toolUse);
            if (result != null) {
                return result;
            }
            log.error("Tool runner returned null for tool_use {} ({})", toolUse.getId(), toolUse.getName());
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: runner returned no result");
        } catch (Exception e) {
            // The executor's executeSingleTool already wraps tool failures, but a runner contract violation must not
            // escape and break the batch.
            log.error("Tool runner threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(), e.getMessage(),
                    e);
            return ToolUseResult.error(toolUse.getId(), "Tool execution error: " + e.getMessage());
        }
    }

    /** Runs one call and fires {@code onCompleted} with its result on the current thread. */
    static ToolUseResult runAndComplete(Logger log, Function<ToolUse, ToolUseResult> runner,
            BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse) {
        final ToolUseResult result = runSafely(log, runner, toolUse);
        safeOnCompleted(log, onCompleted, toolUse, result);
        return result;
    }

    static void safeOnStarted(Logger log, Consumer<ToolUse> onStarted, ToolUse toolUse) {
        if (onStarted == null) {
            return;
        }
        try {
            onStarted.accept(toolUse);
        } catch (Exception e) {
            log.warn("onStarted listener threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    e.getMessage(), e);
        }
    }

    static void safeOnCompleted(Logger log, BiConsumer<ToolUse, ToolUseResult> onCompleted, ToolUse toolUse,
            ToolUseResult result) {
        if (onCompleted == null) {
            return;
        }
        try {
            onCompleted.accept(toolUse, result);
        } catch (Exception e) {
            log.warn("onCompleted listener threw for tool_use {} ({}): {}", toolUse.getId(), toolUse.getName(),
                    e.getMessage(), e);
        }
    }
}
