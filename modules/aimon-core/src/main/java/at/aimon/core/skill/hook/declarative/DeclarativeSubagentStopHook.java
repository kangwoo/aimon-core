package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link SubagentStopHook} (AIMON extension, SK-13) that fires a {@link ShellAction} once when a subagent dispatched
 * via the Task tool finishes (success or failure).
 *
 * <p>
 * subagentStop hooks are non-blocking by interface contract — only {@link ShellAction} is supported here. Deny actions
 * are rejected at parse time. There is no matcher: the action runs unconditionally on every subagent-stop event for as
 * long as the enclosing skill is active.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativeSubagentStopHook extends AbstractDeclarativeShellHook<SubagentStopContext>
        implements
            SubagentStopHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "subagentStop";

    /**
     * Creates a new declarative subagentStop hook.
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
    public DeclarativeSubagentStopHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativeSubagentStopHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativeSubagentStopHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(SubagentStopContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_SUBAGENT_NAME, context.getSubagentName());
        env.put(SkillHookEnv.AIMON_TASK_ID, context.getTaskId());
        env.put(SkillHookEnv.AIMON_SUCCESS, Boolean.toString(context.isSuccess()));
    }
}
