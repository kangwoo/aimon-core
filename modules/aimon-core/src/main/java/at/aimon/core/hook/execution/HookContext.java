package at.aimon.core.hook.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookRegistry;

/**
 * Base interface for all hook contexts.
 *
 * <p>
 * Provides common information available to all hooks:
 *
 * <ul>
 * <li>Executor type (MAIN_AGENT or SUBAGENT)
 * <li>Executor name
 * <li>Where commands run ({@link #getExecutionEnvironment()}) and what that place looks like
 * ({@link #getEnvironmentDescriptor()})
 * <li>Timestamp of the event
 * </ul>
 *
 * <p>
 * All hook contexts are immutable value objects.
 */
public interface HookContext {
    /**
     * Gets the executor type.
     *
     * @return The executor type (MAIN_AGENT or SUBAGENT, never null)
     */
    InvokerType getInvokerType();

    /**
     * Gets the executor name.
     *
     * @return The executor name (never null)
     */
    String getInvokerName();

    /**
     * Gets the hook registry.
     *
     * @return The hook registry (never null)
     */
    HookRegistry getHookRegistry();

    /**
     * The execution environment the firing execution runs in — the same instance its tools reach through
     * {@code ToolContext} (execution-environment design §10). A hook that runs a command or touches a file on the
     * execution's behalf goes through this rather than the JVM host.
     *
     * <p>
     * Events that fire outside any execution ({@code onSessionStart}, {@code onSessionEnd}, {@code onConfigReload})
     * have no environment, and empty is the correct answer for them — see
     * {@link at.aimon.core.hook.HookEventType#firesInsideExecution()}. An event that fires inside an execution can
     * still arrive empty (a rewake replay, a hand-built context, a firing site that was never handed one), so callers
     * must handle both.
     *
     * <p>
     * An <em>unavailable</em> environment is carried as-is, not replaced by empty: its {@code descriptor()} still
     * answers, and its {@code shell()} / {@code fileSystem()} throw on use. A hook must not fall back to the host when
     * it meets one.
     *
     * @return the environment, or empty for an event with no execution environment in reach
     */
    default Optional<ExecutionEnvironment> getExecutionEnvironment() {
        return Optional.empty();
    }

    /**
     * Describes where the execution's commands run — its execution environment's descriptor, not the JVM host
     * (execution-environment design §10). A hook that reasons about platform or paths reads this rather than
     * assuming the host.
     *
     * <p>
     * Derived from {@link #getExecutionEnvironment()}; a context carries the environment only, so the two can never
     * disagree.
     *
     * @return the descriptor, or empty for an event with no execution environment in reach
     */
    default Optional<EnvironmentDescriptor> getEnvironmentDescriptor() {
        return getExecutionEnvironment().map(ExecutionEnvironment::descriptor);
    }

    /**
     * The cancellation signal of the execution this event fires in — the one its tools read through
     * {@code InterruptAccess.signalOf(ToolContext)}. It is an <em>execution</em> concept: a turn, a fork and a
     * scheduled routine each have one, and a hook that starts something long-running on the execution's behalf (a
     * shell command, say) ties it to this signal so an interrupt stops it instead of waiting out its timeout.
     *
     * <p>
     * Which answer an event gives depends on what the hook is being asked:
     * <ul>
     * <li><b>A gate</b> — {@code onStart}, {@code preCompact}, {@code preTool}, {@code permissionRequest} — is asked
     * before something proceeds, and carries the signal always. A command fired after the interrupt is not started at
     * all, one that is running is stopped, and the guard blocks.
     * <li><b>A report</b> — {@code onStop}, {@code postCompact}, {@code subagentStart}, {@code subagentStop},
     * {@code postTool}, {@code permissionDenied} — records what already happened, and carries the signal only while
     * it has not tripped. A command running when the interrupt arrives is stopped, but one that starts afterwards
     * runs unbound, so an audit or cleanup hook on a cancelled execution always starts. The rule lives in the
     * context's getter, so the answer can turn from present to empty over the life of one context: read it when the
     * work starts, not when the context is built. A signal that trips between the read and the registration cancels
     * that one command at once.
     * </ul>
     * {@code subagentStart} and {@code subagentStop} fire in the spawning execution but carry the signal that governs
     * the <em>fork</em> — the spawner's for a foreground fork, the task's own for a background one.
     *
     * <p>
     * Empty is a correct answer and callers must handle it. It means no signal there can trip:
     * <ul>
     * <li>events that fire outside any execution ({@code onSessionStart}, {@code onSessionEnd},
     * {@code onConfigReload});
     * <li>a slash-command turn once its {@code onStart} has run — its {@code onStop}, and the {@code preCompact} /
     * {@code postCompact} / {@code onStop} of a manual {@code /compact} — because a command is not interruptible;
     * <li>a {@code preCompact} chain rebuilt by a rewake replay, outside the execution that fired it;
     * <li>a custom {@code ContextEngine} or {@code CompactionEngine} that does not forward the signal on the requests
     * it builds.
     * </ul>
     *
     * @return the execution's cancellation signal, or empty when none is in reach
     */
    default Optional<CancellationSignal> getExecutionCancellation() {
        return Optional.empty();
    }

    /**
     * Gets the timestamp when this event occurred.
     *
     * @return The timestamp (never null)
     */
    Instant getTimestamp();

    /**
     * Gets the execution attributes passed by the caller at agent execution request time.
     *
     * <p>
     * <b>Note:</b> The returned map is an unmodifiable shallow copy created via {@code Map.copyOf()}. Map values should
     * be effectively immutable types (e.g., {@code String}, {@code Integer}).
     *
     * @return The execution attributes (never null, may be empty)
     */
    Map<String, Object> getExecutionAttributes();
}
