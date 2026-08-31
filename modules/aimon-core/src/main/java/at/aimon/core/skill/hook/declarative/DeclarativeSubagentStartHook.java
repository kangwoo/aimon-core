package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link SubagentStartHook} (AIMON extension) that fires a {@link ShellAction} once when a subagent is dispatched via
 * the Task tool, before it starts working.
 *
 * <p>
 * subagentStart hooks are non-blocking by interface contract — only {@link ShellAction} is supported here. Deny actions
 * are rejected at parse time. There is no matcher: the action runs unconditionally on every subagent-start event for as
 * long as the enclosing skill is active.
 *
 * <p>
 * The goal and the description are model-authored and can be arbitrarily long, so both are capped at
 * {@value SkillHookEnv#MAX_ENV_VALUE_LENGTH} characters via {@link SkillHookEnv#truncateValue(String)} before they are
 * exported. The environment block has a hard OS size limit and is readable by anything that can list processes, which
 * is the same reason {@link DeclarativePostCompactHook} refuses to export the compaction summary at all. Note that the
 * stdin JSON payload is rendered from the very same map, so {@code subagent_goal} there is the truncated value too —
 * a hook that needs the full goal must obtain it some other way (the truncation marker tells it that it should).
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativeSubagentStartHook extends AbstractDeclarativeShellHook<SubagentStartContext>
        implements
            SubagentStartHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "subagentStart";

    /**
     * Creates a new declarative subagentStart hook.
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
    public DeclarativeSubagentStartHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativeSubagentStartHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativeSubagentStartHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(SubagentStartContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_SUBAGENT_NAME, context.getSubagentName());
        env.put(SkillHookEnv.AIMON_TASK_ID, context.getTaskId());
        env.put(SkillHookEnv.AIMON_SUBAGENT_GOAL, SkillHookEnv.truncateValue(context.getGoal()));
        env.put(SkillHookEnv.AIMON_SUBAGENT_DESCRIPTION, SkillHookEnv.truncateValue(context.getDescription()));
    }
}
