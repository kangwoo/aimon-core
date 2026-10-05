package at.aimon.core.skill.hook;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.Skill;
import at.aimon.core.tools.HookRegistryAccess;

/**
 * Production {@link SkillHookActivator}: makes a skill's declared hooks visible to that skill's fork and to nothing
 * else.
 *
 * <p>
 * It does not register anything. Activation builds a {@link SkillScopedHookRegistry} — the registry the invoking
 * execution dispatches against, with the skill's hooks layered on top — and hands it out through the scope for
 * {@code SkillTool} or, on the user-slash path, {@code LlmSkillExecutor} to pass to the fork. Another session of the
 * same agent, and the invoking execution itself, keep
 * dispatching against a registry that never contained the skill's hooks, so there is nothing to filter and nothing to
 * roll back. Closing the scope switches the layer off; each scope is single-use and idempotent on close.
 *
 * <p>
 * The base is the registry in the invoking call's {@link ToolContext} when there is one — that is what stacks a skill
 * invoked from inside another skill's fork on top of the outer skill's hooks — and the registry given to the
 * constructor otherwise.
 */
public final class ScopedSkillHookActivator implements SkillHookActivator {

    private final HookRegistry hookRegistry;

    /**
     * Creates a new ScopedSkillHookActivator.
     *
     * @param hookRegistry
     *            The registry to layer over when the invoking tool context carries none (must not be null)
     */
    public ScopedSkillHookActivator(HookRegistry hookRegistry) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "Hook registry cannot be null");
    }

    @Override
    public SkillHookScope activate(Skill skill, ToolContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final SkillHookSet hooks = skill.getMetadata().getHooks();
        if (hooks.isEmpty()) {
            return SkillHookScope.EMPTY;
        }
        return new LayerScope(new SkillScopedHookRegistry(HookRegistryAccess.of(context).orElse(hookRegistry),
                skill.getName(), hooks));
    }

    private static final class LayerScope implements SkillHookScope {

        private final SkillScopedHookRegistry registry;

        LayerScope(SkillScopedHookRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Optional<HookRegistry> hookRegistry() {
            return Optional.of(registry);
        }

        @Override
        public void close() {
            registry.deactivate();
        }
    }
}
