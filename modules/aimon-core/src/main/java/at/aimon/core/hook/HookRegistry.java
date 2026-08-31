package at.aimon.core.hook;

import java.util.List;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * Registry of {@link ExecutionHook} instances keyed by {@link HookEventType}.
 *
 * <p>
 * The interface intentionally exposes a small, type-token-driven surface — every event type is addressed through a
 * {@link HookEventType} constant rather than its own pair of {@code register*} / {@code unregister*} / {@code get*}
 * methods. Adding a new event type therefore requires zero changes to this interface or to alternative
 * implementations: a new {@link HookEventType} constant plus a registry-side binding suffices.
 *
 * <p>
 * Implementations must be thread-safe — {@link #register(HookEventType, ExecutionHook)} /
 * {@link #unregister(HookEventType, ExecutionHook)} may be called concurrently with iteration over the list returned
 * by {@link #getHooks(HookEventType)}. Iteration order matches registration order.
 *
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * HookRegistry registry = new DefaultHookRegistry();
 * registry.register(HookEventType.ON_START, loggingHook);
 * registry.register(HookEventType.PRE_TOOL, securityHook);
 *
 * for (PreToolHook hook : registry.getHooks(HookEventType.PRE_TOOL)) {
 *     // ...
 * }
 *
 * registry.unregister(HookEventType.ON_START, loggingHook);
 * registry.clearAll();
 * }
 * </pre>
 */
public interface HookRegistry {

    /**
     * Registers a hook for the given event type.
     *
     * <p>
     * Multiple hooks can be registered for the same event; they execute in registration order.
     *
     * @param <H>
     *            hook subtype keyed by {@code type}
     * @param type
     *            event type token (must not be null)
     * @param hook
     *            hook instance (must not be null)
     * @throws NullPointerException
     *             if {@code type} or {@code hook} is null
     */
    <H extends ExecutionHook<?>> void register(HookEventType<H> type, H hook);

    /**
     * Unregisters the first occurrence of {@code hook} from the list for {@code type}, comparing by instance equality.
     *
     * @param <H>
     *            hook subtype keyed by {@code type}
     * @param type
     *            event type token (must not be null)
     * @param hook
     *            hook instance to remove (must not be null)
     * @return {@code true} if the hook was found and removed, {@code false} otherwise
     * @throws NullPointerException
     *             if {@code type} or {@code hook} is null
     */
    <H extends ExecutionHook<?>> boolean unregister(HookEventType<H> type, H hook);

    /**
     * Returns an immutable snapshot of every hook registered for {@code type}, in registration order.
     *
     * @param <H>
     *            hook subtype keyed by {@code type}
     * @param type
     *            event type token (must not be null)
     * @return immutable list (never null; empty if nothing registered)
     * @throws NullPointerException
     *             if {@code type} is null
     */
    <H extends ExecutionHook<?>> List<H> getHooks(HookEventType<H> type);

    /**
     * Returns {@code true} if no hooks are registered for any event type.
     *
     * @return {@code true} if every event type has an empty list, {@code false} otherwise
     */
    boolean isEmpty();

    /**
     * Removes every registered hook from every event type.
     */
    void clearAll();
}
