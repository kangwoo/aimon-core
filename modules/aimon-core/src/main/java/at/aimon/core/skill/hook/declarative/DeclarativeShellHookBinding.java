package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Binds an AIMON event name to the {@link HookEventType} it registers under and to the constructor of the declarative
 * hook that serves it, for every event whose only supported action is a shell command.
 *
 * <p>
 * These events differ from each other in nothing a config reader cares about &mdash; same validation, same constructor
 * shape, same registration call &mdash; so they are a lookup table rather than one switch arm per event. Both
 * {@code hooks.json} ({@code HookRegistryApplier}) and SKILL.md frontmatter ({@code SkillHookSetParser}) resolve
 * through this single table so the two front-ends cannot drift out of sync as events are added.
 *
 * <p>
 * {@code preTool} and {@code postTool} are deliberately absent: they take a matcher predicate plus the HTTP / MCP
 * executors, so they do not fit the shell-only constructor shape.
 *
 * <p>
 * The type parameter exists purely to keep {@code HookRegistry#register} and {@code SkillHookSet.Builder#add}
 * type-safe: the event type and the hook the factory produces are the <em>same</em> {@code H}, so no cast is needed
 * even though the table that holds these is heterogeneous ({@code Map<String, DeclarativeShellHookBinding<?>>}).
 * Callers holding a wildcard instance recover {@code H} through capture conversion by passing it to a generic method.
 *
 * <p>
 * Immutable and thread-safe.
 *
 * @param <H>
 *            the hook type this event accepts
 */
public final class DeclarativeShellHookBinding<H extends ExecutionHook<?>> {

    private static final Map<String, DeclarativeShellHookBinding<?>> BY_EVENT = Map.ofEntries(
            entry(DeclarativeOnStartHook.EVENT_NAME, HookEventType.ON_START, DeclarativeOnStartHook::new),
            entry(DeclarativeOnStopHook.EVENT_NAME, HookEventType.ON_STOP, DeclarativeOnStopHook::new),
            entry(DeclarativeOnSessionStartHook.EVENT_NAME, HookEventType.ON_SESSION_START,
                    DeclarativeOnSessionStartHook::new),
            entry(DeclarativeOnSessionEndHook.EVENT_NAME, HookEventType.ON_SESSION_END,
                    DeclarativeOnSessionEndHook::new),
            entry(DeclarativeSubagentStartHook.EVENT_NAME, HookEventType.SUBAGENT_START,
                    DeclarativeSubagentStartHook::new),
            entry(DeclarativeSubagentStopHook.EVENT_NAME, HookEventType.SUBAGENT_STOP,
                    DeclarativeSubagentStopHook::new),
            entry(DeclarativePreCompactHook.EVENT_NAME, HookEventType.PRE_COMPACT, DeclarativePreCompactHook::new),
            entry(DeclarativePostCompactHook.EVENT_NAME, HookEventType.POST_COMPACT, DeclarativePostCompactHook::new),
            entry(DeclarativePermissionRequestHook.EVENT_NAME, HookEventType.PERMISSION_REQUEST,
                    DeclarativePermissionRequestHook::new),
            entry(DeclarativePermissionDeniedHook.EVENT_NAME, HookEventType.PERMISSION_DENIED,
                    DeclarativePermissionDeniedHook::new),
            entry(DeclarativeOnConfigReloadHook.EVENT_NAME, HookEventType.ON_CONFIG_RELOAD,
                    DeclarativeOnConfigReloadHook::new));

    private final HookEventType<H> eventType;
    private final Factory<H> factory;

    private DeclarativeShellHookBinding(HookEventType<H> eventType, Factory<H> factory) {
        this.eventType = Objects.requireNonNull(eventType, "Event type cannot be null");
        this.factory = Objects.requireNonNull(factory, "Factory cannot be null");
    }

    private static <H extends ExecutionHook<?>> Map.Entry<String, DeclarativeShellHookBinding<?>> entry(String name,
            HookEventType<H> type, Factory<H> factory) {
        return Map.entry(name, new DeclarativeShellHookBinding<>(type, factory));
    }

    /**
     * Looks up the binding for an AIMON event name.
     *
     * @param eventName
     *            the AIMON event name as it appears in {@code hooks.json} / skill frontmatter (may be null)
     * @return the binding, or empty when the event is unknown or is not shell-only ({@code preTool} / {@code postTool})
     */
    public static Optional<DeclarativeShellHookBinding<?>> forEvent(String eventName) {
        return Optional.ofNullable(BY_EVENT.get(eventName));
    }

    /**
     * Returns every shell-only event name.
     *
     * @return immutable set of event names (never null)
     */
    public static Set<String> eventNames() {
        return BY_EVENT.keySet();
    }

    /**
     * Returns the registry key this event's hooks register under.
     *
     * @return the event type (never null)
     */
    public HookEventType<H> getEventType() {
        return eventType;
    }

    /**
     * Builds a hook instance for this event.
     *
     * @param skillName
     *            skill (or pseudo-skill) name baked into {@code AIMON_SKILL_NAME} (must not be null)
     * @param action
     *            the shell action to fire (must not be null)
     * @param shellExecutor
     *            executor used to run the action (must not be null)
     * @param options
     *            hook-id discriminator and optional {@code asyncRewake} spec (must not be null)
     * @return the hook, typed to match {@link #getEventType()} (never null)
     */
    public H create(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
            DeclarativeHookOptions options) {
        return factory.create(skillName, action, shellExecutor, options);
    }

    @Override
    public String toString() {
        return "DeclarativeShellHookBinding[" + eventType.name() + ']';
    }

    /** The 4-arg constructor every shell-only declarative hook exposes. */
    @FunctionalInterface
    private interface Factory<H extends ExecutionHook<?>> {
        H create(String skillName, ShellAction action, ShellActionExecutor shellExecutor,
                DeclarativeHookOptions options);
    }
}
