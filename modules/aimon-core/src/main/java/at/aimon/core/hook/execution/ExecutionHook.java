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

    /**
     * Returns what the executor's outer timeout must mean for this hook, when the hook knows.
     *
     * <p>
     * The event's {@link HookExecutionPolicy#timeoutBehavior() policy} answers that question for every hook of the
     * chain at once, and the shipped policies answer {@link HookExecutionPolicy.TimeoutBehavior#FAIL_OPEN}:
     * availability first. That is the wrong answer for a hook that exists to veto — a guard cut off by the net said
     * nothing, and letting the operation through makes "be slow" the way to switch the guard off. Such a hook
     * declares {@link HookExecutionPolicy.TimeoutBehavior#FAIL_CLOSED} here and the executor honours it over the
     * policy ({@link HookExecutionPolicy#timeoutBehaviorFor(ExecutionHook)}).
     *
     * <p>
     * A {@code FAIL_CLOSED} declaration covers the two other ways a hook ends without a verdict as well
     * ({@link HookExecutionPolicy#failsClosedWithoutVerdict(ExecutionHook)}): the executor's pool refused to run it
     * (saturated, or shut down), and its body threw. Without that a guard closed against "slow" would stay open
     * against "never started" and "crashed". A hook that declares nothing keeps
     * {@link HookExecutionPolicy#onException(Exception)} for both, and so does one that declares {@code FAIL_OPEN}.
     *
     * <p>
     * The declaration only matters when the hook returned no result: a hook that returns in time is
     * never asked. The in-tree declarers are the declarative guard hooks ({@code hooks.json} / skill frontmatter on
     * {@code preTool}, {@code onStart}, {@code preCompact}, {@code permissionRequest}) that did not declare
     * {@code failOpen}. A hook registered in code is free to declare one as well.
     *
     * @return the behaviour this hook asks for when it ends without a verdict (an outer timeout; for
     *         {@code FAIL_CLOSED} also a rejection or a throw), or {@link Optional#empty()} to accept the event
     *         policy's
     */
    default Optional<HookExecutionPolicy.TimeoutBehavior> getTimeoutBehavior() {
        return Optional.empty();
    }

    /**
     * Returns whether the executor keeps waiting for this hook when the thread that fired it is interrupted.
     *
     * <p>
     * By default an interrupt of the firing thread cancels the hook's task and the hook is reported as BLOCKED: the
     * execution is being cancelled and nobody waits for the verdict. A hook whose work must finish once it has started
     * — a cleanup or audit command on {@code onStop}, say — returns {@code true}: the executor then leaves the task
     * alone, waits for what is left of the hook's budget, returns the hook's own result (or takes the ordinary timeout
     * path), and re-arms the thread's interrupt flag before it returns, so the caller's cancellation still sees it.
     *
     * <p>
     * This is the thread-interrupt half of "not stopped by an interrupt". The other half is the hook's own: it must
     * not tie its work to {@link HookContext#getExecutionCancellation()}. The declarative shell hooks that declared
     * {@code ignoreInterrupt} do both.
     *
     * <p>
     * The executor does not know the event, so the declaration is honoured wherever it is made. It never turns an
     * interrupt into a pass — the wait yields the hook's real verdict or a timeout — but on an event that gates
     * something ({@code preTool}, {@code onStart}, {@code preCompact}, {@code permissionRequest}) it makes the
     * interrupted execution wait for the gate, for up to the hook's whole budget. The declarative hooks never declare
     * it there; a hook registered in code should not either.
     *
     * @return true to be waited for across an interrupt of the firing thread; the default is false
     */
    default boolean ignoresInterrupt() {
        return false;
    }
}
