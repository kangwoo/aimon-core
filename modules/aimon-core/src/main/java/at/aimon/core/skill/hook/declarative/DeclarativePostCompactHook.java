package at.aimon.core.skill.hook.declarative;

import java.util.Map;

import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PostCompactHook} (AIMON extension) that fires a {@link ShellAction} once a conversation compaction has been
 * applied.
 *
 * <p>
 * postCompact hooks are non-blocking by interface contract — only {@link ShellAction} is supported here. Deny actions
 * are rejected at parse time. There is no matcher: the action runs unconditionally on every compaction for as long as
 * the enclosing skill is active.
 *
 * <p>
 * The generated summary itself is deliberately <em>not</em> exported: it can be arbitrarily long and would blow past
 * the environment block limit. Hooks that need it should read the conversation through a tool instead.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativePostCompactHook extends AbstractDeclarativeShellHook<PostCompactContext>
        implements
            PostCompactHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "postCompact";

    /**
     * Creates a new declarative postCompact hook.
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
    public DeclarativePostCompactHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativePostCompactHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativePostCompactHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(PostCompactContext context, Map<String, String> env) {
        final CompactionMetadata metadata = context.getCompactionMetadata();
        env.put(SkillHookEnv.AIMON_COMPACTION_TRIGGER, context.getTrigger().name());
        env.put(SkillHookEnv.AIMON_MESSAGES_SUMMARIZED, Integer.toString(metadata.getMessagesSummarized()));
        env.put(SkillHookEnv.AIMON_PRE_COMPACT_TOKENS, Integer.toString(metadata.getPreCompactTokenCount()));
        env.put(SkillHookEnv.AIMON_POST_COMPACT_TOKENS, Integer.toString(metadata.getPostCompactTokenCount()));
    }
}
