package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.hook.event.OnConfigReloadContext;
import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link OnConfigReloadHook} (AIMON extension) that fires a {@link ShellAction} whenever a watched configuration
 * source is reloaded — whether the new configuration was applied or rolled back.
 *
 * <p>
 * onConfigReload hooks are non-blocking by interface contract — only {@link ShellAction} is supported here. Deny
 * actions are rejected at parse time. There is no matcher: the action runs unconditionally on every reload event for
 * as long as the enclosing skill is active.
 *
 * <p>
 * Read {@code AIMON_SUCCESS} before reacting: the hook also fires for a <em>failed</em> reload, in which case
 * {@code AIMON_FAILURE_REASON} explains why the watcher rolled back.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativeOnConfigReloadHook extends AbstractDeclarativeShellHook<OnConfigReloadContext>
        implements
            OnConfigReloadHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "onConfigReload";

    /**
     * Creates a new declarative onConfigReload hook.
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
    public DeclarativeOnConfigReloadHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativeOnConfigReloadHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativeOnConfigReloadHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(OnConfigReloadContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_RELOAD_COUNTER, Long.toString(context.getReloadCounter()));
        env.put(SkillHookEnv.AIMON_CONFIG_SOURCE, context.getConfigSource());
        env.put(SkillHookEnv.AIMON_SUCCESS, Boolean.toString(context.isSuccessful()));
        env.put(SkillHookEnv.AIMON_FAILURE_REASON, context.getFailureReason());
    }
}
