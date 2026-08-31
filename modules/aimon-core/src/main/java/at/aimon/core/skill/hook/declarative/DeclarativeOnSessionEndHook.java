package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionEndHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link OnSessionEndHook} (AIMON extension, SK-13) that fires a {@link ShellAction} once when a {@code LiveSession}
 * carrying this skill is closed (cleanly or abnormally).
 *
 * <p>
 * onSessionEnd hooks are non-blocking by interface contract — only {@link ShellAction} is supported here. Deny actions
 * are rejected at parse time. There is no matcher: the action runs unconditionally on every session-end event for as
 * long as the enclosing skill is active.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativeOnSessionEndHook extends AbstractDeclarativeShellHook<OnSessionEndContext>
        implements
            OnSessionEndHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "onSessionEnd";

    /**
     * Creates a new declarative onSessionEnd hook.
     *
     * @param skillName
     *            The skill name baked into the {@code AIMON_SKILL_NAME} env var (must not be null)
     * @param action
     *            The shell action to fire (must not be null)
     * @param shellExecutor
     *            The executor used to run the shell action (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DeclarativeOnSessionEndHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
        this(skillName, action, shellExecutor, DeclarativeHookOptions.none());
    }

    /**
     * Creates a hook with an explicit {@linkplain #getHookId() hook id} discriminator.
     *
     * <p>
     * Callers that register several hooks of this class — {@code hooks.json} entries and multi-entry skill
     * frontmatter — must pass a discriminator, otherwise all of them share one id and async-rewake routing /
     * reload cancellation cannot tell them apart. See {@link DeclarativeHookId}.
     *
     * @param skillName
     *            The skill name baked into the {@code AIMON_SKILL_NAME} env var (must not be null)
     * @param action
     *            The shell action to fire (must not be null)
     * @param shellExecutor
     *            The executor used to run the shell action (must not be null)
     * @param options
     *            config-derived options: hook-id discriminator and {@code asyncRewake} spec (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DeclarativeOnSessionEndHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativeOnSessionEndHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(OnSessionEndContext context, Map<String, String> env) {
        // Both keys are always exported, at most one populated — see DeclarativeOnSessionStartHook#contributeEnv.
        env.put(SkillHookEnv.AIMON_SESSION_ID, context.getSessionId().map(SessionId::value).orElse(""));
        env.put(SkillHookEnv.AIMON_EXECUTION_ID, context.getExecutionId().map(ExecutionId::value).orElse(""));
        env.put(SkillHookEnv.AIMON_AGENT_RUNTIME_ID, context.getAgentRuntimeId());
        env.put(SkillHookEnv.AIMON_SESSION_CLEAN, Boolean.toString(context.isClean()));
        env.put(SkillHookEnv.AIMON_TERMINATION_REASON, context.getTerminationReason().orElse(""));
    }
}
