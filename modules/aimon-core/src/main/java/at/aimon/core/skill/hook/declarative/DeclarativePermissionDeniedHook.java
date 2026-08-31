package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.hook.event.PermissionDeniedContext;
import at.aimon.core.hook.event.PermissionDeniedHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link PermissionDeniedHook} (AIMON extension) that fires a {@link ShellAction} after a tool call has been rejected
 * by the permission layer.
 *
 * <p>
 * permissionDenied hooks are non-blocking by interface contract — the decision has already been made, so this event
 * exists for audit trails and alerting. Only {@link ShellAction} is supported here; deny actions are rejected at parse
 * time.
 *
 * <p>
 * There is no matcher — the action fires for every rejection. Scripts branch on {@code AIMON_TOOL_NAME},
 * {@code AIMON_DENY_REASON}, or the {@code tool_input} object in the stdin payload.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 */
public final class DeclarativePermissionDeniedHook extends AbstractDeclarativeShellHook<PermissionDeniedContext>
        implements
            PermissionDeniedHook {

    /** AIMON event name, as it appears in {@code hooks.json} and skill frontmatter. */
    public static final String EVENT_NAME = "permissionDenied";

    /**
     * Creates a new declarative permissionDenied hook.
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
    public DeclarativePermissionDeniedHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor) {
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
    public DeclarativePermissionDeniedHook(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        super(DeclarativePermissionDeniedHook.class, EVENT_NAME, skillName, action, shellExecutor, options);
    }

    @Override
    protected void contributeEnv(PermissionDeniedContext context, Map<String, String> env) {
        env.put(SkillHookEnv.AIMON_TOOL_NAME, context.getToolName());
        env.put(SkillHookEnv.AIMON_DENY_REASON, context.getDenyReason());
        final Optional<Principal> principal = context.getPrincipal();
        env.put(SkillHookEnv.AIMON_PRINCIPAL_ID, principal.map(Principal::getId).orElse(""));
        env.put(SkillHookEnv.AIMON_PRINCIPAL_TYPE, principal.map(p -> p.getType().name()).orElse(""));
    }

    @Override
    protected Optional<Map<String, Object>> payloadToolInput(PermissionDeniedContext context) {
        return Optional.of(context.getToolInput().toMap());
    }
}
