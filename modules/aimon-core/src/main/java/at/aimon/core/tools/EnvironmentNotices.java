package at.aimon.core.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.llm.ToolUseResult;

/**
 * Delivers an execution environment's {@linkplain ExecutionEnvironment#drainNotices() queued notices} to the model, on
 * the result of whichever tool call happened to come next (issue #254).
 *
 * <p>
 * Only the model-facing dispatch paths call {@link #deliver}: {@code SingleToolInvoker} after the PostTool chain, and
 * the hook-less fallback of {@code LlmSkillExecutor}. A path whose results go to a script, a routine step or a hook
 * must not — draining there would hand the notice to a reader that is not the model and lose it.
 *
 * <p>
 * The notices are rendered the way {@code Bash} renders a command's own {@code ShellCommandResult.notices()}: one
 * {@value #PREFIX}{@code <notice>} line each, at the head of the result, so the model reads both channels in one shape
 * and a long result does not bury them.
 */
public final class EnvironmentNotices {

    /** Prefix of each environment notice line placed at the head of a tool result. */
    public static final String PREFIX = "[environment] ";

    private static final Logger log = LoggerFactory.getLogger(EnvironmentNotices.class);

    private EnvironmentNotices() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Drains {@code environment} once and returns {@code result} with the notices prepended.
     *
     * <p>
     * Returns {@code result} itself — the same instance — when there is no environment, it had nothing queued, or
     * draining threw: a notice is auxiliary and must never cost the model the tool result it is waiting on. The
     * rewritten result keeps {@link ToolUseResult#isError()} and the render payload.
     *
     * @param result
     *            the result about to be handed to the model (must not be null)
     * @param environment
     *            the environment the call ran against, or {@code null} when the tool context carries none
     * @param toolName
     *            the tool's name, for the log line when draining fails, or {@code null} when unknown
     * @return the result carrying the notices, or {@code result} unchanged
     */
    public static ToolUseResult deliver(ToolUseResult result, ExecutionEnvironment environment, String toolName) {
        Objects.requireNonNull(result, "result must not be null");
        if (environment == null) {
            return result;
        }
        final List<String> notices;
        try {
            notices = environment.drainNotices();
        } catch (RuntimeException e) {
            log.warn("Draining environment notices failed for tool '{}', toolUseId={}: {}", toolName,
                    result.getToolUseId(), e.getMessage());
            return result;
        }
        final String rendered = render(notices);
        if (rendered.isEmpty()) {
            return result;
        }
        final String merged = rendered + result.getContent();
        final ToolUseResult rewritten = result.isError()
                ? ToolUseResult.error(result.getToolUseId(), merged)
                : ToolUseResult.success(result.getToolUseId(), merged);
        final Map<String, Object> renderPayload = result.getRenderPayload();
        return renderPayload == null ? rewritten : rewritten.withRenderPayload(renderPayload);
    }

    /**
     * Renders notices as {@value #PREFIX} lines, one per entry, skipping {@code null} and blank entries. Only the first
     * line of an entry is prefixed, as in {@code BashTool}: the SPI asks providers for single-line entries.
     *
     * @param notices
     *            the notices, or {@code null} for none
     * @return the rendered lines, each ending in a newline, or an empty string
     */
    static String render(List<String> notices) {
        if (notices == null || notices.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (String notice : notices) {
            if (notice != null && !notice.isBlank()) {
                sb.append(PREFIX).append(notice).append('\n');
            }
        }
        return sb.toString();
    }
}
