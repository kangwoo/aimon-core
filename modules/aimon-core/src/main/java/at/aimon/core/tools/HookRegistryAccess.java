package at.aimon.core.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;

/**
 * Static accessors for the {@link HookRegistry} an execution dispatches its hook events against
 * ({@link ToolContextKeys#HOOK_REGISTRY}).
 *
 * <p>
 * Code that spawns a fork — {@code Task}, {@code Workflow}, a forked skill, a custom tool — must hand the fork the
 * registry returned by {@link #of(ToolContext)} when there is one, and fall back to a registry of its own only when
 * there is not. Inside a skill's fork that registry carries the skill's hooks; a spawner that passes the runtime's
 * registry instead starts a fork the skill's guards do not cover.
 */
public final class HookRegistryAccess {

    private HookRegistryAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the registry the calling execution dispatches against.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the registry, or {@link Optional#empty()} when the context was assembled without one
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static Optional<HookRegistry> of(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.HOOK_REGISTRY);
    }

    /**
     * Returns the skills whose guard hooks are active for the calling execution — skills it is running inside the
     * fork of, that declared a hook on an event that can veto in the fork ({@code preTool}, {@code preCompact},
     * {@code permissionRequest}; not {@code onStart}, which a fork reads as advisory &mdash; EE-70).
     *
     * <p>
     * This is the question to ask before starting work that cannot carry the caller's registry, such as a run on the
     * agent-scoped background workflow runner: if the answer is not empty, that work would run without guards that
     * are still in force, and should be refused.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the skill names, outermost first (never null; empty when no skill guard is active)
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static List<String> activeSkillGuards(ToolContext context) {
        return view(context).map(SkillScopedHookRegistry::activeGuardSkills).orElse(List.of());
    }

    /**
     * Returns whether any skill guard hook is active for the calling execution.
     *
     * @param context
     *            the tool context (must not be null)
     * @return true when {@link #activeSkillGuards(ToolContext)} is not empty
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static boolean hasActiveSkillGuards(ToolContext context) {
        return !activeSkillGuards(context).isEmpty();
    }

    /**
     * Returns the skills whose hooks of any kind are active for the calling execution.
     *
     * @param context
     *            the tool context (must not be null)
     * @return the skill names, outermost first (never null; empty when no skill hook is active)
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static List<String> activeSkillHooks(ToolContext context) {
        return view(context).map(SkillScopedHookRegistry::activeSkills).orElse(List.of());
    }

    /**
     * The error a tool answers with when it refuses to start a background workflow run because skill guards are
     * active. One wording for every tool that refuses.
     *
     * @param skills
     *            the skills holding the guards, from {@link #activeSkillGuards(ToolContext)} (must not be empty)
     * @return the message for the model (never null)
     */
    public static String backgroundRefusal(List<String> skills) {
        Objects.requireNonNull(skills, "skills must not be null");
        return "Background mode is not available here: skill '" + String.join("', '", skills) + "' has guard hooks"
                + " active and a background workflow run would not be covered by them. Run the workflow in foreground"
                + " mode.";
    }

    /**
     * The error {@code ScheduleTask} answers with when it refuses to schedule a routine because skill guards are
     * active. A routine fires later on the runtime's registry, outside the skill's fork, so none of the skill's hooks
     * would cover it &mdash; the same reason a background workflow run is refused.
     *
     * @param skills
     *            the skills holding the guards, from {@link #activeSkillGuards(ToolContext)} (must not be empty)
     * @return the message for the model (never null)
     */
    public static String scheduleRefusal(List<String> skills) {
        Objects.requireNonNull(skills, "skills must not be null");
        return "Scheduling is not available here: skill '" + String.join("', '", skills) + "' has guard hooks"
                + " active and a scheduled routine would not be covered by them. Schedule the task outside the skill.";
    }

    /**
     * Returns a copy of the context that carries the given registry and is otherwise unchanged.
     *
     * <p>
     * The key is write-once, so the registry cannot be replaced in place; this assembles a new context instead, and
     * is the only place that does. It is for the code that starts a fork with a different registry than its own —
     * {@code SkillTool} handing a forked skill the view with the skill's hooks.
     *
     * @param context
     *            the context to copy (must not be null)
     * @param registry
     *            the registry the copy carries (must not be null)
     * @return the copy (never null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public static ToolContext withHookRegistry(ToolContext context, HookRegistry registry) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        final ToolContext.Builder builder = ToolContext.builder();
        for (Map.Entry<String, Object> entry : context.getContext().entrySet()) {
            if (!ToolContextKeys.HOOK_REGISTRY.name().equals(entry.getKey())) {
                builder.put(entry.getKey(), entry.getValue());
            }
        }
        return builder.put(ToolContextKeys.HOOK_REGISTRY, registry).build();
    }

    /**
     * Finds the skill view the execution dispatches against. The context's registry must <em>be</em> the
     * {@link SkillScopedHookRegistry}: a registry that wraps or decorates the view is not looked through, so a custom
     * spawn site that publishes such a decorator makes every guard question here answer "none" &mdash; and disables
     * the background refusals (Workflow, WorkflowJs, ScheduleTask) that rely on it.
     */
    private static Optional<SkillScopedHookRegistry> view(ToolContext context) {
        return of(context).filter(SkillScopedHookRegistry.class::isInstance).map(SkillScopedHookRegistry.class::cast);
    }
}
