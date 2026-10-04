package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Strategy seam (AIMON extension, SK-13) for running a {@link ShellAction} attached to a declarative hook.
 *
 * <p>
 * An executor does not hold a shell of its own choosing on behalf of skills: <em>which</em> shell a command runs in is
 * decided when the hook fires, from the firing {@link HookContext}. The implementations differ in where they look:
 * <ul>
 * <li>{@link DefaultShellActionExecutor} — runs the command in the shell of the firing execution's
 * {@linkplain HookContext#getExecutionEnvironment() execution environment}. This is what skill-declared hooks use, so
 * a skill's hook command runs where the same skill's {@code Bash} calls run (execution-environment design §10).
 * <li>{@link HostShellActionExecutor} — runs the command in one fixed shell and ignores the context. Reserved for
 * operator-authored {@code hooks.json}, the only place that can declare events firing outside any execution.
 * <li>{@link NoOpShellActionExecutor} — refuses; intended for default {@code MarkdownSkillParser} wirings that have
 * not opted in to shell hooks. The parser uses {@link #isShellSupported()} to fail fast at parse time so the
 * configuration error is caught at skill-load time, not at the first hook firing.
 * </ul>
 *
 * <p>
 * {@link #run} must <strong>never</strong> throw. Any error must be logged at WARN and reported through the returned
 * {@link ShellHookOutcome} — the executor says what happened, the hook decides what it means. This keeps the trust
 * boundary clear: a misbehaving skill cannot abort the agent loop by handing the parser a broken shell command, and a
 * guard whose command could not run is not mistaken for one that approved.
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
     * Returns whether this executor can only run a command inside the firing context's execution environment.
     *
     * <p>
     * When true, a context without an environment means the command does not run at all — there is no host fallback.
     * Because that failure only shows at fire time, the front-ends refuse up front to bind such an executor to an
     * event that never has an environment: see {@link #canRunOn(HookEventType)}.
     *
     * @return true when the executor needs {@link HookContext#getExecutionEnvironment()} to be present
     */
    boolean requiresExecutionEnvironment();

    /**
     * Returns whether a shell action declared on the given event could ever run through this executor.
     *
     * <p>
     * The single rule both front-ends ({@code SkillHookSetParser}, {@code HookRegistryApplier}) apply before accepting
     * a shell action: an executor that needs an execution environment cannot serve an event that fires outside every
     * execution. Rejecting at declaration time is the point — the same mismatch at fire time is a hook that never runs.
     *
     * @param eventType
     *            the event the action is declared on (must not be null)
     * @return false when the action would be skipped on every firing
     */
    default boolean canRunOn(HookEventType<?> eventType) {
        return !requiresExecutionEnvironment() || eventType.firesInsideExecution();
    }

    /**
     * Runs the given action with a JSON document on standard input and reports what it did.
     *
     * <p>
     * This is the one entry point every declarative hook uses. It carries three things:
     * <ul>
     * <li>the <b>firing context</b>, from which an environment-bound executor takes the shell to run in;
     * <li>the <b>stdin payload</b>, which carries the nested tool input that will not fit in the environment block
     * (see {@code ShellHookPayload});
     * <li>the <b>outcome</b>, which lets a {@code preTool} hook honour the exit-code veto contract (see
     * {@link ShellHookOutcome#DENY_EXIT_CODE}).
     * </ul>
     *
     * <p>
     * Must not throw under any circumstance — exceptions, non-zero exit codes, timeouts and a missing or unavailable
     * execution environment are all handled internally (logged at WARN). A run that produced no exit status reports
     * {@link ShellHookOutcome#notRun(ShellHookOutcome.Unrun, String)} with its cause. An event with a decision channel
     * ({@code preTool}, {@code onStart}, {@code preCompact}, {@code permissionRequest}) reads that as a block or deny
     * unless the hook declared {@code failOpen}; every other event only logs it.
     *
     * @param action
     *            The action to execute (must not be null)
     * @param context
     *            The context of the hook firing the action (must not be null)
     * @param environmentOverrides
     *            Extra environment variables provided by the firing hook (never null; may be empty). Implementations
     *            merge these on top of any inherited environment.
     * @param stdinPayload
     *            JSON document to feed the command on standard input, or null to leave stdin empty
     * @return what the command did (never null); {@link ShellHookOutcome#notRun} when no status was produced
     */
    ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
            String stdinPayload);
}
