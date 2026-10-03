package at.aimon.core.hook.execution;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
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
 * <li>User locale (the time zone) — where commands run is {@link #getExecutionEnvironment()} and what that place
 * looks like is {@link #getEnvironmentDescriptor()}
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
     * Gets the user locale — the user- and application-side settings, today the time zone. It says nothing about
     * where commands run: the working directory, platform and OS version are on {@link #getEnvironmentDescriptor()},
     * and the place itself is {@link #getExecutionEnvironment()}.
     *
     * @return The user locale (never null)
     */
    UserLocale getUserLocale();

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
