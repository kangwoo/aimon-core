package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Strategy seam (AIMON extension, SK-13) for running a {@link ShellAction} attached to a declarative skill hook.
 *
 * <p>
 * Implementations decide whether shell-based hooks are even allowed in the host environment:
 * <ul>
 * <li>{@link NoOpShellActionExecutor} — refuses; intended for default {@code MarkdownSkillParser} wirings that have
 * not opted in to shell hooks. The parser uses {@link #isShellSupported()} to fail fast at parse time so the
 * configuration error is caught at skill-load time, not at the first hook firing.
 * <li>{@link DefaultShellActionExecutor} — runs the command via a {@link at.aimon.core.shell.VirtualShell}.
 * </ul>
 *
 * <p>
 * The contract is intentionally fail-soft: {@link #run} must <strong>never</strong> throw, since the calling hook
 * declares itself non-blocking. Any error must be logged at WARN and discarded. This keeps the trust boundary clear:
 * a misbehaving skill cannot abort the agent loop by handing the parser a broken shell command.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface ShellActionExecutor {

    /**
     * Returns whether this executor can actually run shell actions.
     *
     * <p>
     * The {@code SkillHookSetParser} consults this before accepting any frontmatter that declares a shell action; when
     * unsupported it raises a parse error rather than silently swallowing the hook.
     *
     * @return true when shell actions are supported, false when they must be rejected at parse time
     */
    boolean isShellSupported();

    /**
     * Runs the given action as a fire-and-forget side-effect.
     *
     * <p>
     * Must not throw under any circumstance — exceptions, non-zero exit codes, and timeouts are all handled internally
     * (logged at WARN) so that the hook can return {@code HookResult.success()}.
     *
     * @param action
     *            The action to execute (must not be null)
     * @param environmentOverrides
     *            Extra environment variables provided by the firing hook (never null; may be empty). Implementations
     *            merge these on top of any inherited environment.
     */
    void run(ShellAction action, Map<String, String> environmentOverrides);

    /**
     * Runs the given action with a JSON document on standard input and reports what it did.
     *
     * <p>
     * This is the entry point every declarative hook uses. It exists because two things cannot travel through
     * {@link #run(ShellAction, Map)}:
     * <ul>
     * <li>the <b>stdin payload</b>, which carries the nested tool input that will not fit in the environment block
     * (see {@code ShellHookPayload});
     * <li>the <b>outcome</b>, which lets a {@code preTool} hook honour the exit-code veto contract (see
     * {@link ShellHookOutcome#DENY_EXIT_CODE}).
     * </ul>
     *
     * <p>
     * The default implementation delegates to {@link #run(ShellAction, Map)} and returns
     * {@link ShellHookOutcome#notObserved()}, so an executor written before this method existed keeps working — it
     * simply drops the payload and can never deny. Implementations that can capture the process result should
     * override this and implement {@link #run(ShellAction, Map)} in terms of it.
     *
     * <p>
     * Like {@link #run(ShellAction, Map)}, this must <strong>never</strong> throw.
     *
     * @param action
     *            The action to execute (must not be null)
     * @param environmentOverrides
     *            Extra environment variables provided by the firing hook (never null; may be empty)
     * @param stdinPayload
     *            JSON document to feed the command on standard input, or null to leave stdin empty
     * @return what the command did (never null); {@link ShellHookOutcome#notObserved()} when this executor cannot
     *         report a status
     */
    default ShellHookOutcome run(ShellAction action, Map<String, String> environmentOverrides, String stdinPayload) {
        run(action, environmentOverrides);
        return ShellHookOutcome.notObserved();
    }
}
