package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link OnStartHook} (AIMON extension, SK-13) that fires a {@link ShellAction} once when the agent / subagent
 * carrying this skill begins executing.
 *
 * <p>
 * onStart owns a decision channel: {@code OrcaAgentExecutor} aborts the turn with an
 * {@code ExecutionBlockedByHookException} when the ON_START chain returns {@link HookResult#block(String)}. Only
 * {@link ShellAction} is supported here and {@code deny} actions are rejected at parse time, so a declarative gate
 * expresses its veto by exiting {@value ShellHookOutcome#DENY_EXIT_CODE} — the command's stderr becomes the abort
 * reason. Any other non-zero exit is fail-soft: a crashed start-up script must not lock the agent out of every turn.
 *
 * <p>
 * There is no matcher: the action runs unconditionally on every start event for as long as the enclosing skill is
 * active.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativeOnStartHook extends AbstractDeclarativeShellHook<OnStartContext> implements OnStartHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "onStart";

    /**
     * Creates a new declarative onStart hook.
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
    public DeclarativeOnStartHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativeOnStartHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativeOnStartHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(OnStartContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_USER_MESSAGE_LENGTH, Integer.toString(context.getUserMessage().length()));
    }

    @Override
    protected Optional<HookResult> vetoResult(String reason) {
        return Optional.of(HookResult.block(reason));
    }
}
