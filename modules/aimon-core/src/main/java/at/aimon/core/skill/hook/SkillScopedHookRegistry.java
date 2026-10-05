package at.aimon.core.skill.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.ExecutionHook;

/**
 * A {@link HookRegistry} view: a base registry with one skill's hooks layered on top.
 *
 * <p>
 * This is how a skill's hooks reach the skill's fork without reaching anyone else. Hook dispatch reads the registry
 * the firing execution carries, so an execution that was handed this view sees the skill's hooks and one that was not
 * cannot — the scope is who holds the object, not an id compared at fire time. The runtime's shared registry is never
 * written to.
 *
 * <p>
 * <b>Reads</b> return the base's hooks followed by the skill's, the order registration used to give. The base is read
 * on every call, so hooks registered or reloaded there afterwards show through. <b>Writes</b> go to the base: code in
 * the fork that registers a hook means what it always meant, the runtime's registry.
 *
 * <p>
 * <b>Deactivation.</b> {@link #deactivate()} switches the skill layer off for good. An execution that still holds the
 * view after the skill returned — a background subagent the fork started — then sees the base alone.
 *
 * <p>
 * <b>The skill's directory travels with the layer.</b> The view also holds the {@link StagedResource} the skill's
 * directory is staged from, so a hook of the layer can be given {@code AIMON_SKILL_DIR} when it fires
 * ({@link #stagedResourceOf}). It is deliberately the resource and not a staged path: the hook fires in whichever
 * execution holds the view &mdash; the fork, or a subagent the fork started, possibly in another environment &mdash;
 * and only that execution's environment can say where its copy is.
 *
 * <p>
 * Thread-safe: the layer is immutable and the switch is atomic.
 */
public final class SkillScopedHookRegistry implements HookRegistry {

    private final HookRegistry base;
    private final String skillName;
    private final SkillHookSet layer;
    private final StagedResource stagedResource; // null for a skill assembled by hand: nothing to stage
    private final AtomicBoolean active = new AtomicBoolean(true);

    /**
     * Creates an active view.
     *
     * @param base
     *            the registry to layer over (must not be null); may itself be a view, for a skill invoked from inside
     *            another skill's fork
     * @param skillName
     *            the skill the layer belongs to (must not be null)
     * @param layer
     *            the skill's hooks (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillScopedHookRegistry(HookRegistry base, String skillName, SkillHookSet layer) {
        this(base, skillName, layer, null);
    }

    /**
     * Creates an active view that also carries the skill's staging resource.
     *
     * @param base
     *            the registry to layer over (must not be null); may itself be a view
     * @param skillName
     *            the skill the layer belongs to (must not be null)
     * @param layer
     *            the skill's hooks (must not be null)
     * @param stagedResource
     *            what the skill's directory is staged from, or null when the skill has none (a hand-built skill); its
     *            hooks then get no {@code AIMON_SKILL_DIR}
     * @throws NullPointerException
     *             if base, skillName or layer is null
     */
    public SkillScopedHookRegistry(HookRegistry base, String skillName, SkillHookSet layer,
            StagedResource stagedResource) {
        this.base = Objects.requireNonNull(base, "Base registry cannot be null");
        this.skillName = Objects.requireNonNull(skillName, "Skill name cannot be null");
        this.layer = Objects.requireNonNull(layer, "Layer cannot be null");
        this.stagedResource = stagedResource;
    }

    /**
     * Returns the staging resource of the skill that declared the given hook, looking through the given registry and
     * the views beneath it.
     *
     * <p>
     * The hook is matched by identity against each layer, innermost view first. That is what keeps the answer honest:
     * a hook from {@code hooks.json} is in no layer and gets nothing, even when its name collides with a skill's, and
     * a hook is never handed another skill's directory. A layer that has been {@linkplain #deactivate() deactivated}
     * still answers &mdash; a hook already running when its skill returned is the same hook of the same skill.
     *
     * <p>
     * Like {@code HookRegistryAccess}, this does not look through a registry that wraps or decorates a view.
     *
     * @param registry
     *            the registry the firing execution dispatches against (must not be null)
     * @param hook
     *            the hook that is firing (must not be null)
     * @return the declaring skill's resource; empty when no layer holds the hook or the skill has no resource
     * @throws NullPointerException
     *             if either argument is null
     */
    public static Optional<StagedResource> stagedResourceOf(HookRegistry registry, ExecutionHook<?> hook) {
        Objects.requireNonNull(registry, "Registry cannot be null");
        Objects.requireNonNull(hook, "Hook cannot be null");
        HookRegistry current = registry;
        while (current instanceof SkillScopedHookRegistry view) {
            if (view.layer.contains(hook)) {
                return Optional.ofNullable(view.stagedResource);
            }
            current = view.base;
        }
        return Optional.empty();
    }

    @Override
    public <H extends ExecutionHook<?>> void register(HookEventType<H> type, H hook) {
        base.register(type, hook);
    }

    @Override
    public <H extends ExecutionHook<?>> boolean unregister(HookEventType<H> type, H hook) {
        return base.unregister(type, hook);
    }

    @Override
    public <H extends ExecutionHook<?>> List<H> getHooks(HookEventType<H> type) {
        final List<H> baseHooks = base.getHooks(type);
        if (!active.get()) {
            return baseHooks;
        }
        final List<H> skillHooks = layer.get(type);
        if (skillHooks.isEmpty()) {
            return baseHooks;
        }
        final List<H> merged = new ArrayList<>(baseHooks.size() + skillHooks.size());
        merged.addAll(baseHooks);
        merged.addAll(skillHooks);
        return List.copyOf(merged);
    }

    @Override
    public boolean isEmpty() {
        return base.isEmpty() && (!active.get() || layer.isEmpty());
    }

    @Override
    public void clearAll() {
        base.clearAll();
    }

    /** Switches the skill layer off. Idempotent; the view keeps answering with the base alone. */
    public void deactivate() {
        active.set(false);
    }

    /**
     * @return true until {@link #deactivate()} is called
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Returns the skills whose layers are active in this view and the views beneath it, outermost first.
     *
     * @return the skill names (never null; empty when every layer has been deactivated)
     */
    public List<String> activeSkills() {
        return collect(false);
    }

    /**
     * Returns the skills, in this view and the views beneath it, whose active layer declares a hook on an
     * {@linkplain SkillHookSet#guardEvents() event that can veto}. An execution that cannot carry this view — one that
     * dispatches against the runtime's registry instead — would run without those guards.
     *
     * @return the skill names, outermost first (never null; empty when no active layer guards anything)
     */
    public List<String> activeGuardSkills() {
        return collect(true);
    }

    private List<String> collect(boolean guardsOnly) {
        final List<String> names = new ArrayList<>();
        if (base instanceof SkillScopedHookRegistry outer) {
            names.addAll(outer.collect(guardsOnly));
        }
        if (active.get() && (!guardsOnly || layer.hasGuards())) {
            names.add(skillName);
        }
        return List.copyOf(names);
    }

    @Override
    public String toString() {
        return "SkillScopedHookRegistry{skill=" + skillName + ", active=" + active.get() + ", base=" + base + '}';
    }
}
