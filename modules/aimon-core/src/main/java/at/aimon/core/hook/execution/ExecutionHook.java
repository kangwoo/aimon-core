package at.aimon.core.hook.execution;

import java.time.Duration;
import java.util.Optional;

/**
 * Base interface for all agent execution hooks.
 *
 * <p>
 * Hooks provide lifecycle callbacks during agent execution, enabling observability, logging, metrics collection, and
 * external integrations.
 *
 * <p>
 * Hook implementations should be fast and non-blocking. Long-running operations should be delegated to background
 * threads.
 *
 * <p>
 * Thread-safety is implementation-specific. Stateless implementations are recommended.
 *
 * <p>
 * Example implementation:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class LoggingHook implements OnStartHook {
 *         private static final Logger logger = LoggerFactory.getLogger(LoggingHook.class);
 *
 *         &#64;Override
 *         public HookResult execute(OnStartContext context) {
 *             logger.info("Agent '{}' started with message: {}", context.getExecutorName(), context.getUserMessage());
 *             return HookResult.success();
 *         }
 *     }
 * }
 * </pre>
 *
 * @param <C>
 *            The context type for this hook
 */
@FunctionalInterface
public interface ExecutionHook<C extends HookContext> {
    /**
     * Executes the hook with the given context.
     *
     * <p>
     * Implementations should not throw exceptions. Any errors should be logged internally and not propagate to the
     * caller. If an exception is thrown, the hook executor will catch it and log it, treating the hook as successful to
     * prevent breaking agent execution.
     *
     * @param context
     *            The hook context (never null)
     * @return A result indicating success or failure with optional feedback (never null)
     */
    HookResult execute(C context);

    /**
     * Returns a stable identifier for this hook. The id is used by the async-rewake machinery to route a fired
     * envelope back to the originating hook (see {@code RewakeEnvelope.getOriginatingHookId()}) and by config
     * hot-reload to invalidate pending fires when the hook is removed.
     *
     * <p>
     * The default implementation returns {@code getClass().getName()}, which is stable across the JVM lifetime and
     * sufficient for class-keyed dispatch. Implementations that register multiple instances of the same class — or
     * that need configuration-aware identity — should override this with a unique value (e.g. a hook key from the
     * config file).
     *
     * @return stable hook identifier (never null or blank)
     */
    default String getHookId() {
        return getClass().getName();
    }

    /**
     * Returns the wall-clock budget this hook needs, when it knows one.
     *
     * <p>
     * The executor enforces {@link HookExecutionPolicy#timeout()} on every hook. A hook that owns a longer deadline of
     * its own — a declarative hook whose {@code timeoutMs} exceeds the policy default, say — would otherwise be cut
     * short by that outer net before its own deadline ever fires, losing the graceful outcome it was configured to
     * produce. Declaring the budget here lets {@link HookExecutionPolicy#timeoutFor(ExecutionHook)} widen the net for
     * this hook alone.
     *
     * <p>
     * The value is a floor, not an override: a budget <i>shorter</i> than the policy timeout changes nothing, since the
     * hook already returns before the net would fire.
     *
     * @return the declared budget (positive), or {@link Optional#empty()} to accept the policy timeout as-is
     */
    default Optional<Duration> getExecutionBudget() {
        return Optional.empty();
    }
}
