package at.aimon.core.hook;

import java.util.List;
import java.util.Objects;

import at.aimon.core.hook.event.OnConfigReloadHook;
import at.aimon.core.hook.event.OnSessionEndHook;
import at.aimon.core.hook.event.OnSessionStartHook;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PermissionDeniedHook;
import at.aimon.core.hook.event.PermissionRequestHook;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Typed token identifying a hook event type.
 *
 * <p>
 * Each constant carries the wire name used in {@code hooks.json} configuration and the {@link Class} of the matching
 * hook interface. The class token lets {@link HookRegistry#getHooks(HookEventType)} return a properly-typed
 * {@code List<H>} without unchecked casts on the caller side.
 *
 * <p>
 * The set of constants is closed at compile time — adding a new event type means adding a constant here and a binding
 * in the registry / config-loader. {@link #values()} returns the canonical, registration-ordered list and is the
 * single source of truth for callers that want to enumerate every event type (registry initialisation, hot-reload
 * applier, ArchUnit coverage tests, etc.).
 *
 * <p>
 * Immutable; safe to share.
 *
 * @param <H>
 *            the {@link ExecutionHook} subtype keyed by this event
 */
public final class HookEventType<H extends ExecutionHook<?>> {

    /** Pre-tool admission event. */
    public static final HookEventType<PreToolHook> PRE_TOOL = new HookEventType<>("preTool", PreToolHook.class);

    /** Post-tool observation event. */
    public static final HookEventType<PostToolHook> POST_TOOL = new HookEventType<>("postTool", PostToolHook.class);

    /** Agent-start lifecycle event. */
    public static final HookEventType<OnStartHook> ON_START = new HookEventType<>("onStart", OnStartHook.class);

    /** Agent-stop lifecycle event. */
    public static final HookEventType<OnStopHook> ON_STOP = new HookEventType<>("onStop", OnStopHook.class);

    /** Pre-compaction event (may block AUTO compaction). */
    public static final HookEventType<PreCompactHook> PRE_COMPACT = new HookEventType<>("preCompact",
            PreCompactHook.class);

    /** Post-compaction event (non-blocking). */
    public static final HookEventType<PostCompactHook> POST_COMPACT = new HookEventType<>("postCompact",
            PostCompactHook.class);

    /** Permission-request event. */
    public static final HookEventType<PermissionRequestHook> PERMISSION_REQUEST = new HookEventType<>(
            "permissionRequest", PermissionRequestHook.class);

    /** Permission-denied audit event. */
    public static final HookEventType<PermissionDeniedHook> PERMISSION_DENIED = new HookEventType<>("permissionDenied",
            PermissionDeniedHook.class);

    /** Subagent-start lifecycle event. */
    public static final HookEventType<SubagentStartHook> SUBAGENT_START = new HookEventType<>("subagentStart",
            SubagentStartHook.class);

    /** Subagent-stop lifecycle event. */
    public static final HookEventType<SubagentStopHook> SUBAGENT_STOP = new HookEventType<>("subagentStop",
            SubagentStopHook.class);

    /** Session-start lifecycle event. */
    public static final HookEventType<OnSessionStartHook> ON_SESSION_START = new HookEventType<>("onSessionStart",
            OnSessionStartHook.class);

    /** Session-end lifecycle event. */
    public static final HookEventType<OnSessionEndHook> ON_SESSION_END = new HookEventType<>("onSessionEnd",
            OnSessionEndHook.class);

    /** Config-reload event (fires after a hooks.json reload completes). */
    public static final HookEventType<OnConfigReloadHook> ON_CONFIG_RELOAD = new HookEventType<>("onConfigReload",
            OnConfigReloadHook.class);

    private static final List<HookEventType<?>> ALL = List.of(PRE_TOOL, POST_TOOL, ON_START, ON_STOP, PRE_COMPACT,
            POST_COMPACT, PERMISSION_REQUEST, PERMISSION_DENIED, SUBAGENT_START, SUBAGENT_STOP, ON_SESSION_START,
            ON_SESSION_END, ON_CONFIG_RELOAD);

    private final String name;
    private final Class<H> hookClass;

    private HookEventType(String name, Class<H> hookClass) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.hookClass = Objects.requireNonNull(hookClass, "hookClass cannot be null");
    }

    /**
     * Returns the wire name used in {@code hooks.json} (e.g. {@code preTool}, {@code onSessionStart}).
     *
     * @return event name (never null)
     */
    public String name() {
        return name;
    }

    /**
     * Returns the hook interface keyed by this event.
     *
     * @return hook class (never null)
     */
    public Class<H> hookClass() {
        return hookClass;
    }

    /**
     * Returns the canonical list of every event type, in registration order.
     *
     * @return immutable list (never null)
     */
    public static List<HookEventType<?>> values() {
        return ALL;
    }

    @Override
    public String toString() {
        return "HookEventType[" + name + ']';
    }
}
