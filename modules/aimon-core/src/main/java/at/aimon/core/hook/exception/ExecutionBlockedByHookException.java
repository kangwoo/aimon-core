package at.aimon.core.hook.exception;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.InvokerType;

/**
 * Exception thrown when execution is blocked by a hook.
 *
 * <p>
 * This exception is thrown when a hook (OnStart, PreTool, etc.) returns a blocked result, preventing the execution from
 * continuing. The hook system allows for validation and control flow management at various points in the execution
 * lifecycle.
 *
 * <p>
 * Common scenarios that trigger this exception:
 *
 * <ul>
 * <li>OnStart hook blocks execution based on user input validation
 * <li>PreTool hook blocks a specific tool execution due to permission checks
 * <li>Custom hook logic determines the operation should not proceed
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * if (hookExecutionManager.hasBlockedResult(results)) {
 *     List<String> reasons = hookExecutionManager.collectBlockedReasons(results);
 *     throw new ExecutionBlockedByHookException(InvokerType.MAIN_AGENT, "agent-name", "OnStart", reasons);
 * }
 * }
 * </pre>
 */
public class ExecutionBlockedByHookException extends HookException {
    private static final long serialVersionUID = 1L;

    private final InvokerType invokerType;
    private final String invokerName;
    private final String hookType;
    private final List<String> blockReasons;

    /**
     * Creates a new ExecutionBlockedByHookException.
     *
     * @param invokerType
     *            The type of executor that was blocked (e.g., MAIN_AGENT, SUBAGENT)
     * @param invokerName
     *            The name of the agent or command that was blocked
     * @param hookType
     *            The type of hook that blocked execution (e.g., "OnStart", "PreTool")
     * @param blockReasons
     *            The list of reasons why execution was blocked
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ExecutionBlockedByHookException(InvokerType invokerType, String invokerName, String hookType,
            List<String> blockReasons) {
        super(buildMessage(invokerType, invokerName, hookType, blockReasons));
        this.invokerType = Objects.requireNonNull(invokerType, "Invoker type cannot be null");
        this.invokerName = Objects.requireNonNull(invokerName, "Invoker name cannot be null");
        this.hookType = Objects.requireNonNull(hookType, "Hook type cannot be null");
        this.blockReasons = List.copyOf(Objects.requireNonNull(blockReasons, "Block reasons cannot be null"));
    }

    /**
     * Creates a new ExecutionBlockedByHookException with a custom message.
     *
     * @param message
     *            The error message
     * @param invokerType
     *            The type of executor that was blocked
     * @param invokerName
     *            The name of the agent or command that was blocked
     * @param hookType
     *            The type of hook that blocked execution
     * @param blockReasons
     *            The list of reasons why execution was blocked
     * @throws NullPointerException
     *             if any parameter is null
     */
    public ExecutionBlockedByHookException(String message, InvokerType invokerType, String invokerName, String hookType,
            List<String> blockReasons) {
        super(message);
        this.invokerType = Objects.requireNonNull(invokerType, "Invoker type cannot be null");
        this.invokerName = Objects.requireNonNull(invokerName, "Invoker name cannot be null");
        this.hookType = Objects.requireNonNull(hookType, "Hook type cannot be null");
        this.blockReasons = List.copyOf(Objects.requireNonNull(blockReasons, "Block reasons cannot be null"));
    }

    /**
     * Builds the error message from the components.
     *
     * @param invokerType
     *            The invoker type
     * @param invokerName
     *            The invoker name
     * @param hookType
     *            The hook type
     * @param blockReasons
     *            The block reasons
     * @return The formatted error message
     */
    private static String buildMessage(InvokerType invokerType, String invokerName, String hookType,
            List<String> blockReasons) {
        final String reasonsText = String.join("; ", blockReasons);
        return String.format("Execution blocked by %s hook [%s/%s]: %s", hookType, invokerType, invokerName,
                reasonsText);
    }

    /**
     * Gets the type of executor that was blocked.
     *
     * @return The invoker type (never null)
     */
    public InvokerType getInvokerType() {
        return invokerType;
    }

    /**
     * Gets the name of the agent or command that was blocked.
     *
     * @return The invoker name (never null)
     */
    public String getInvokerName() {
        return invokerName;
    }

    /**
     * Gets the type of hook that blocked execution.
     *
     * @return The hook type (never null)
     */
    public String getHookType() {
        return hookType;
    }

    /**
     * Gets the list of reasons why execution was blocked.
     *
     * @return An immutable list of block reasons (never null, never empty)
     */
    public List<String> getBlockReasons() {
        return blockReasons;
    }
}
