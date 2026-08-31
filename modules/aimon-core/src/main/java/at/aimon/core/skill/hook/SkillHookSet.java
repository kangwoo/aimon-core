package at.aimon.core.skill.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Immutable, type-safe bundle of hook instances scoped to a single skill (AIMON extension).
 *
 * <p>
 * Hooks held here are active only while the skill is executing. The {@link SkillHookActivator} registers them with a
 * {@link at.aimon.core.hook.HookRegistry} on activation and unregisters them on scope close.
 *
 * <p>
 * Only the events in {@link #supportedEvents()} can be scoped to a skill. The three that are missing &mdash;
 * {@code onSessionStart}, {@code onSessionEnd} and {@code onConfigReload} &mdash; fire on the session / application
 * lifecycle, strictly outside any skill invocation, so a per-skill registration for them could never fire. Declare
 * those in {@code hooks.json} instead.
 *
 * <p>
 * Compaction hooks ({@code preCompact} / {@code postCompact}) <em>are</em> in scope: a fork-mode skill's scope spans
 * the spawned subagent's whole lifetime, and that subagent can compact its own conversation while the skill runs.
 *
 * <p>
 * Thread-safe by virtue of immutability; defensive copies are taken in the builder.
 */
public final class SkillHookSet {

    /**
     * The events that can be scoped to a single skill invocation, in the order the activator registers them.
     *
     * <p>
     * The first four are the historical SK-13 set and are listed first so registration order is unchanged for skills
     * that only use them.
     */
    private static final List<HookEventType<?>> SUPPORTED_EVENTS = List.of(HookEventType.ON_START,
            HookEventType.PRE_TOOL, HookEventType.POST_TOOL, HookEventType.ON_STOP, HookEventType.SUBAGENT_START,
            HookEventType.SUBAGENT_STOP, HookEventType.PERMISSION_REQUEST, HookEventType.PERMISSION_DENIED,
            HookEventType.PRE_COMPACT, HookEventType.POST_COMPACT);

    /** The events {@link #toString()} always reports, even at zero, so the summary reads consistently. */
    private static final List<HookEventType<?>> ALWAYS_SUMMARISED = SUPPORTED_EVENTS.subList(0, 4);

    private static final SkillHookSet EMPTY = builder().build();

    private final Map<HookEventType<?>, List<ExecutionHook<?>>> byEvent;

    private SkillHookSet(Builder builder) {
        final Map<HookEventType<?>, List<ExecutionHook<?>>> copy = new LinkedHashMap<>();
        // Iterate the canonical order rather than the builder's insertion order so two sets built from the same hooks
        // in a different declaration order stay equal, and so the activator's registration order is stable regardless
        // of how the frontmatter happened to be written.
        for (HookEventType<?> type : SUPPORTED_EVENTS) {
            final List<ExecutionHook<?>> hooks = builder.byEvent.get(type);
            if (hooks != null && !hooks.isEmpty()) {
                copy.put(type, List.copyOf(hooks));
            }
        }
        byEvent = Map.copyOf(copy);
    }

    /**
     * Returns a singleton empty hook set.
     *
     * @return The empty hook set (never null)
     */
    public static SkillHookSet empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns every event that can be scoped to a skill, in activation order.
     *
     * @return immutable list (never null)
     */
    public static List<HookEventType<?>> supportedEvents() {
        return SUPPORTED_EVENTS;
    }

    /**
     * Returns the hooks declared for one event.
     *
     * @param type
     *            the event type (must not be null)
     * @param <H>
     *            the hook type keyed by {@code type}
     * @return immutable list, empty when the skill declared no hook for this event (never null)
     */
    public <H extends ExecutionHook<?>> List<H> get(HookEventType<H> type) {
        Objects.requireNonNull(type, "Hook event type cannot be null");
        // Safe: Builder#add is the only writer and it only accepts an H for a HookEventType<H>.
        @SuppressWarnings("unchecked")
        final List<H> hooks = (List<H>) byEvent.getOrDefault(type, List.of());
        return hooks;
    }

    public List<OnStartHook> getOnStartHooks() {
        return get(HookEventType.ON_START);
    }

    public List<PreToolHook> getPreToolHooks() {
        return get(HookEventType.PRE_TOOL);
    }

    public List<PostToolHook> getPostToolHooks() {
        return get(HookEventType.POST_TOOL);
    }

    public List<OnStopHook> getOnStopHooks() {
        return get(HookEventType.ON_STOP);
    }

    /** Returns true when no hooks of any type are registered. */
    public boolean isEmpty() {
        return byEvent.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SkillHookSet that)) {
            return false;
        }
        return Objects.equals(byEvent, that.byEvent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byEvent);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("SkillHookSet{");
        for (HookEventType<?> type : ALWAYS_SUMMARISED) {
            if (sb.charAt(sb.length() - 1) != '{') {
                sb.append(", ");
            }
            sb.append(type.name()).append('=').append(get(type).size());
        }
        // The remaining events only show up when populated, so the common case stays as short as it was before they
        // became declarable.
        for (HookEventType<?> type : SUPPORTED_EVENTS) {
            final int size = get(type).size();
            if (size > 0 && !ALWAYS_SUMMARISED.contains(type)) {
                sb.append(", ").append(type.name()).append('=').append(size);
            }
        }
        return sb.append('}').toString();
    }

    /** Builder for {@link SkillHookSet}. */
    public static final class Builder {

        private final Map<HookEventType<?>, List<ExecutionHook<?>>> byEvent = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds a hook for an arbitrary skill-scopable event.
         *
         * @param type
         *            the event type; must be one of {@link SkillHookSet#supportedEvents()} (must not be null)
         * @param hook
         *            the hook instance (must not be null)
         * @param <H>
         *            the hook type keyed by {@code type}
         * @return this builder
         * @throws IllegalArgumentException
         *             if {@code type} cannot be scoped to a skill
         */
        public <H extends ExecutionHook<?>> Builder add(HookEventType<H> type, H hook) {
            Objects.requireNonNull(type, "Hook event type cannot be null");
            Objects.requireNonNull(hook, "Hook cannot be null");
            if (!SUPPORTED_EVENTS.contains(type)) {
                throw new IllegalArgumentException("Event '" + type.name() + "' fires outside any skill invocation and"
                        + " cannot be scoped to a skill; declare it in hooks.json instead");
            }
            byEvent.computeIfAbsent(type, t -> new ArrayList<>()).add(hook);
            return this;
        }

        public Builder addOnStart(OnStartHook hook) {
            return add(HookEventType.ON_START, hook);
        }

        public Builder addPreTool(PreToolHook hook) {
            return add(HookEventType.PRE_TOOL, hook);
        }

        public Builder addPostTool(PostToolHook hook) {
            return add(HookEventType.POST_TOOL, hook);
        }

        public Builder addOnStop(OnStopHook hook) {
            return add(HookEventType.ON_STOP, hook);
        }

        public SkillHookSet build() {
            return new SkillHookSet(this);
        }
    }
}
