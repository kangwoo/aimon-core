package at.aimon.core.hook.execution;

import java.time.Instant;
import java.util.Map;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
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
 * <li>Environment (working directory, platform, OS version)
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
     * Gets the runtime environment.
     *
     * @return The environment (never null)
     */
    Environment getEnvironment();

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
