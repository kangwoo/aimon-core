package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PreCompactHook} (AIMON extension) that fires a {@link ShellAction} before a conversation is compacted.
 *
 * <p>
 * Unlike the other lifecycle events, preCompact has a decision channel: a command that exits with
 * {@link ShellHookOutcome#DENY_EXIT_CODE} aborts the compaction and its stderr becomes the reason. The engine honours
 * that veto for {@code AUTO} triggers only — a {@code MANUAL} compaction was explicitly requested by the user, so the
 * block is downgraded to a warning there. Read {@code AIMON_COMPACTION_TRIGGER} if the command needs to know which
 * case it is in.
 *
 * <p>
 * There is no matcher: the action runs unconditionally on every compaction attempt for as long as the enclosing skill
 * is active.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativePreCompactHook extends AbstractDeclarativeShellHook<PreCompactContext>
        implements
            PreCompactHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "preCompact";

    /**
     * Creates a new declarative preCompact hook.
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
    public DeclarativePreCompactHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativePreCompactHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativePreCompactHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(PreCompactContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_COMPACTION_TRIGGER, context.getTrigger().name());
        // Both keys are always exported, at most one populated — see DeclarativeOnSessionStartHook#contributeEnv.
        env.put(SkillHookEnv.AIMON_SESSION_ID, context.getSessionIdValue());
        env.put(SkillHookEnv.AIMON_EXECUTION_ID, context.getExecutionId().map(ExecutionId::value).orElse(""));
        env.put(SkillHookEnv.AIMON_MESSAGE_COUNT, Integer.toString(context.getMessageCount()));
        env.put(SkillHookEnv.AIMON_ESTIMATED_TOKENS, Integer.toString(context.getEstimatedTokens()));
    }

    @Override
    protected Optional<HookResult> vetoResult(String reason) {
        return Optional.of(HookResult.block(reason));
    }
}
