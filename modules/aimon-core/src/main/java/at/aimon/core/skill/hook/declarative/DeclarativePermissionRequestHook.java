package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PermissionRequestHook} (AIMON extension) that fires a {@link ShellAction} when the dispatcher asks whether a
 * caller may use a tool.
 *
 * <p>
 * This event has a decision channel: a command that exits with {@link ShellHookOutcome#DENY_EXIT_CODE} rejects the
 * call and its stderr becomes the reason surfaced to the model (and to any registered {@code permissionDenied} chain).
 * Any other exit code allows the call — a crashed or misconfigured authorization script must not silently lock the
 * agent out of every tool.
 *
 * <p>
 * The interactive {@code ASK} decision is not reachable from a shell action: prompting is a host-application concern
 * and a hook subprocess has no channel to drive it. Use a programmatic {@link PermissionRequestHook} for that.
 *
 * <p>
 * There is no matcher — the action fires for every permission check. Scripts branch on {@code AIMON_TOOL_NAME} or on
 * the {@code tool_input} object in the stdin payload.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativePermissionRequestHook extends AbstractDeclarativeShellHook<PermissionRequestContext>
        implements
            PermissionRequestHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "permissionRequest";

    /**
     * Creates a new declarative permissionRequest hook.
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
    public DeclarativePermissionRequestHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativePermissionRequestHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativePermissionRequestHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(PermissionRequestContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_TOOL_NAME, context.getToolName());
        final Optional<Principal> principal = context.getPrincipal();
        env.put(SkillHookEnv.AIMON_PRINCIPAL_ID, principal.map(Principal::getId).orElse(""));
        env.put(SkillHookEnv.AIMON_PRINCIPAL_TYPE, principal.map(p -> p.getType().name()).orElse(""));
    }

    @Override
    protected Optional<Map<String, Object>> payloadToolInput(PermissionRequestContext context) {
        return Optional.of(context.getToolInput().toMap());
    }

    @Override
    protected Optional<HookResult> vetoResult(String reason) {
        return Optional.of(HookResult.deny(reason));
    }
}
