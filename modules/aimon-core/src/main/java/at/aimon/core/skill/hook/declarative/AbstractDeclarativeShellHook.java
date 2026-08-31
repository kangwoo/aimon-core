package at.aimon.core.skill.hook.declarative;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeSpec;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Shared implementation for the declarative hooks whose only action is a {@link ShellAction}.
 *
 * <p>
 * Every event outside the tool-input pair ({@code onStart}, {@code onStop}, {@code onSessionStart},
 * {@code onSessionEnd}, {@code subagentStart}, {@code subagentStop}, {@code preCompact}, {@code postCompact},
 * {@code permissionRequest}, {@code permissionDenied}, {@code onConfigReload}) does exactly the same three things —
 * export the shared {@code AIMON_*} environment, run the command with a JSON payload on stdin, re-attach any
 * configured rewake spec — and differs only in the event name and the handful of event-specific variables.
 * Subclasses supply those two things, plus a {@link #vetoResult(String)} override on the three that can act on an
 * exit-2 veto ({@code onStart} and {@code preCompact} block, {@code permissionRequest} denies), and nothing else.
 *
 * <p>
 * {@code preTool} and {@code postTool} deliberately do <b>not</b> extend this: they carry a matcher predicate and can
 * fire HTTP / MCP actions, and {@code preTool} additionally acts on the command's exit code — it is the fourth
 * blocking chain, implemented separately in {@link DeclarativePreToolHook}.
 *
 * <p>
 * Immutable; thread-safe as long as the executor is.
 *
 * @param <C>
 *            the hook context type of the concrete event
 */
public abstract class AbstractDeclarativeShellHook<C extends HookContext> implements ExecutionHook<C> {

    private static final Logger log = LoggerFactory.getLogger(AbstractDeclarativeShellHook.class);

    private final String eventName;
    private final String skillName;
    private final String hookId;
    private final ShellAction action;
    private final ShellActionExecutor shellExecutor;
    private final RewakeSpec rewakeSpec;

    /**
     * Creates a shell-backed declarative hook.
     *
     * @param hookClass
     *            the concrete subclass, used to derive the {@linkplain #getHookId() hook id} (must not be null)
     * @param eventName
     *            the AIMON event name exported as {@code AIMON_HOOK_EVENT} (must not be null)
     * @param skillName
     *            the skill name exported as {@code AIMON_SKILL_NAME} (must not be null)
     * @param action
     *            the shell action to fire (must not be null)
     * @param shellExecutor
     *            the executor used to run the action (must not be null)
     * @param options
     *            config-derived options: hook-id discriminator and {@code asyncRewake} spec (must not be null). The
     *            spec is honoured for every event; the caller is responsible for only supplying one on events the
     *            rewake machinery can actually re-fire — see {@link DeclarativeHookOptions}.
     * @throws NullPointerException
     *             if any argument is null
     */
    protected AbstractDeclarativeShellHook(Class<?> hookClass, String eventName, String skillName, ShellAction action,
            ShellActionExecutor shellExecutor, DeclarativeHookOptions options) {
        this.eventName = Objects.requireNonNull(eventName, "Event name cannot be null");
        this.skillName = Objects.requireNonNull(skillName, "Skill name cannot be null");
        Objects.requireNonNull(options, "Options cannot be null");
        this.hookId = DeclarativeHookId.of(hookClass, this.skillName, options.getHookIdDiscriminator());
        this.rewakeSpec = options.getRewakeSpec().orElse(null);
        this.action = Objects.requireNonNull(action, "Action cannot be null");
        this.shellExecutor = Objects.requireNonNull(shellExecutor, "Shell executor cannot be null");
    }

    @Override
    public final String getHookId() {
        return hookId;
    }

    @Override
    public final Optional<Duration> getExecutionBudget() {
        return action.getExecutionBudget();
    }

    @Override
    public final HookResult execute(C context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final Map<String, String> env = new LinkedHashMap<>();
        env.put(SkillHookEnv.AIMON_HOOK_EVENT, eventName);
        env.put(SkillHookEnv.AIMON_SKILL_NAME, skillName);
        env.put(SkillHookEnv.AIMON_INVOKER_NAME, context.getInvokerName());
        env.put(SkillHookEnv.AIMON_INVOKER_TYPE, context.getInvokerType().name());
        contributeEnv(context, env);

        final ShellHookOutcome outcome = shellExecutor.run(action, env,
                ShellHookPayload.render(env, payloadToolInput(context).orElse(null)));

        // Re-attached on every fire: the chain is bounded by RewakeSpec#getMaxAttempts(), so this yields
        // "re-fire up to maxAttempts times" rather than an unbounded loop.
        return DeclarativeRewake.attach(interpret(outcome), rewakeSpec);
    }

    private HookResult interpret(ShellHookOutcome outcome) {
        if (!outcome.isDenied()) {
            return HookResult.success();
        }
        final Optional<HookResult> veto = vetoResult(outcome.denyReason());
        if (veto.isEmpty()) {
            log.warn(
                    "Skill '{}' {} shell hook exited {} (deny), but this event is advisory, so the veto is"
                            + " ignored: {}",
                    skillName, eventName, ShellHookOutcome.DENY_EXIT_CODE, outcome.denyReason());
            return HookResult.success();
        }
        log.info("Skill '{}' {} shell hook vetoed the operation: {}", skillName, eventName, outcome.denyReason());
        return veto.get();
    }

    /**
     * Translates an exit-code-{@value ShellHookOutcome#DENY_EXIT_CODE} veto into this event's decision result.
     *
     * <p>
     * Most lifecycle events are advisory notifications with nowhere to put a decision; for those the default applies —
     * the veto is logged at WARN and the event proceeds, which is the same fail-soft stance the executor takes for
     * a command that crashed. The three events in this hierarchy that <em>do</em> have a decision channel override
     * this: {@code onStart} returns {@link HookResult#block(String)} (the executor aborts the turn),
     * {@code preCompact} returns {@link HookResult#block(String)} (the compaction is skipped) and
     * {@code permissionRequest} returns {@link HookResult#deny(String)}. The fourth blocking chain, {@code preTool},
     * lives outside this hierarchy in {@link DeclarativePreToolHook} and applies the same exit-2 contract there.
     *
     * @param reason
     *            the command's stderr, or a generic fallback when it wrote none (never null or blank)
     * @return the result to return instead of success, or empty when this event cannot be vetoed
     */
    protected Optional<HookResult> vetoResult(String reason) {
        return Optional.empty();
    }

    /**
     * Returns the object to nest under {@code tool_input} in the stdin payload.
     *
     * <p>
     * Only the tool-scoped events ({@code permissionRequest}, {@code permissionDenied}) have one; everything else
     * leaves the field out entirely rather than emitting an empty object.
     *
     * @param context
     *            the firing context (never null)
     * @return the raw tool input, or empty when this event has none
     */
    protected Optional<Map<String, Object>> payloadToolInput(C context) {
        return Optional.empty();
    }

    /**
     * Adds the event-specific {@code AIMON_*} variables to the environment.
     *
     * <p>
     * The four variables shared by every event ({@code AIMON_HOOK_EVENT}, {@code AIMON_SKILL_NAME},
     * {@code AIMON_INVOKER_NAME}, {@code AIMON_INVOKER_TYPE}) are already present. Entries added here also become
     * fields of the JSON stdin payload, so use the {@link SkillHookEnv} constants rather than ad-hoc names.
     *
     * @param context
     *            the firing context (never null)
     * @param env
     *            the mutable environment being assembled (never null)
     */
    protected abstract void contributeEnv(C context, Map<String, String> env);
}
