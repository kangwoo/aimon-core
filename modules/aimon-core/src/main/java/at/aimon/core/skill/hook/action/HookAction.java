package at.aimon.core.skill.hook.action;

import java.time.Duration;
import java.util.Optional;

/**
 * Marker for a declarative hook action parsed from a SKILL.md frontmatter or a Claude-Code-compatible
 * {@code hooks.json} document.
 *
 * <p>
 * Concrete implementations describe what should happen when a hook fires:
 * <ul>
 * <li>{@link DenyAction} &mdash; block tool execution with a reason. Only valid for {@code preTool} hooks.
 * <li>{@link ShellAction} &mdash; execute a shell command (any hook event). Exit code {@code 2} vetoes with stderr as
 * the reason; every other non-zero exit is logged and allowed.
 * <li>{@link HttpAction} &mdash; POST/GET/... a request to an HTTP endpoint and (optionally) map the JSON response
 * back into a {@code HookResult}.
 * <li>{@link McpToolAction} &mdash; invoke an MCP tool on a registered server and (optionally) map the call result
 * back into a {@code HookResult}.
 * </ul>
 *
 * <p>
 * Sealed so the parser and the {@code Declarative*Hook} dispatchers can exhaustively switch over the variants.
 * Instances are immutable value objects &mdash; share freely across threads.
 */
public sealed interface HookAction permits DenyAction, ShellAction, HttpAction, McpToolAction {

    /**
     * Returns the wall-clock budget this action needs to finish on its own terms.
     *
     * <p>
     * An action that performs I/O enforces its own deadline internally and turns it into a proper {@code HookResult}.
     * The hook executor's per-hook timeout is only the outer safety net, so it has to know about a declared budget that
     * exceeds it &mdash; otherwise the net fires first and a {@code timeoutMs} larger than
     * {@code HookExecutionPolicy.DEFAULT_TIMEOUT} would be silently truncated. See
     * {@code HookExecutionPolicy#timeoutFor(ExecutionHook)}.
     *
     * @return the declared budget, or {@link Optional#empty()} for an action that returns without doing any I/O
     */
    default Optional<Duration> getExecutionBudget() {
        return Optional.empty();
    }
}
