package at.aimon.core.tools;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;

/**
 * Delivers an execution environment's {@linkplain ExecutionEnvironment#drainNotices() queued notices} to the model, on
 * the result of whichever tool call happened to come next (issue #254).
 *
 * <p>
 * The dispatch paths drain once per call ({@link #drain}): the gated pipeline ({@code SingleToolInvoker}) after the
 * PostTool chain, and the explicitly ungated dispatcher. They hand the notices on as a list beside the text, and a
 * model-facing loop renders them ({@link #render}) when it records the result, through
 * {@code ToolDispatchResults.toToolUseResult} — the one place the side channels meet the text. A path whose results go
 * to a routine step or a hook must not drain — that would hand the notice to a reader that is not the model and lose
 * it.
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
     * Drains {@code environment} once and returns what it had queued.
     *
     * <p>
     * Returns an empty list when there is no environment or draining threw: a notice is auxiliary and must never cost
     * the caller the tool result it is waiting on.
     *
     * @param environment
     *            the environment the call ran against, or {@code null} when the tool context carries none
     * @param toolName
     *            the tool's name, for the log line when draining fails, or {@code null} when unknown
     * @param toolUseId
     *            the call's id, for the same log line, or {@code null} when unknown
     * @return the notices (never null)
     */
    public static List<String> drain(ExecutionEnvironment environment, String toolName, String toolUseId) {
        if (environment == null) {
            return List.of();
        }
        try {
            final List<String> notices = environment.drainNotices();
            return notices == null ? List.of() : notices;
        } catch (RuntimeException e) {
            log.warn("Draining environment notices failed for tool '{}', toolUseId={}: {}", toolName, toolUseId,
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * Renders notices as {@value #PREFIX} lines, one per entry, skipping {@code null} and blank entries. Only the first
     * line of an entry is prefixed, as in {@code BashTool}: the SPI asks providers for single-line entries.
     *
     * @param notices
     *            the notices, or {@code null} for none
     * @return the rendered lines, each ending in a newline, or an empty string
     */
    public static String render(List<String> notices) {
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
