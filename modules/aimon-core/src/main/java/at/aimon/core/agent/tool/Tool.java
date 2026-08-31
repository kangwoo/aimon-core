package at.aimon.core.agent.tool;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.llm.ToolDefinition;

/**
 * Unified interface for tools that can be used by the LLM (Large Language Model).
 *
 * <p>
 * This interface represents a unified approach to tool design, combining both the tool's definition (schema) and its
 * execution logic into a single cohesive unit. This design ensures that the tool's schema and implementation stay
 * synchronized, making tool management simpler and less error-prone.
 *
 * <h2>Core Responsibilities</h2>
 *
 * <p>
 * Each tool implementation must provide:
 *
 * <ul>
 * <li>A {@link ToolDefinition} that describes the tool's name, description, and input schema for the LLM
 * <li>An execution method that processes tool invocation requests with the provided parameters
 * </ul>
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * This design follows the Single Responsibility Principle - each tool class is responsible for both its definition and
 * execution, preventing synchronization issues between schema and implementation. This ensures that when you modify a
 * tool's behavior, you update both the schema and the implementation in the same place.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Thread-safety is implementation-specific. Stateless implementations are strongly recommended to avoid concurrency
 * issues when tools are used by multiple agents or requests simultaneously.
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create a tool instance
 *     Tool bashTool = new BashTool(shell);
 *
 *     // Get definition for LLM registration
 *     ToolDefinition definition = bashTool.getDefinition();
 *
 *     // Execute the tool when LLM requests it
 *     ToolInput input = ToolInput.of(Map.of("command", "ls -la"));
 *     ToolContext context = ToolContext.builder().workingDirectory("/home/user").build();
 *     ToolResult result = bashTool.execute(input, context);
 *
 *     // Check the result
 *     if (result.isSuccess()) {
 *         String output = result.getOutput();
 *         System.out.println("Tool output: " + output);
 *     } else {
 *         String error = result.getError();
 *         System.err.println("Tool error: " + error);
 *     }
 * }
 * </pre>
 *
 * @see ToolDefinition
 * @see ToolResult
 * @see ToolContext
 */
public interface Tool {
    /**
     * Returns the tool definition that describes this tool to the LLM.
     *
     * <p>
     * The tool definition includes essential metadata that the LLM needs to understand and use this tool effectively:
     *
     * <ul>
     * <li><b>Name:</b> A unique identifier for the tool
     * <li><b>Description:</b> Explains what the tool does and when to use it
     * <li><b>Input Schema:</b> JSON schema defining the expected input parameters
     * </ul>
     *
     * <p>
     * The returned definition should be consistent across calls and should match the actual behavior of the
     * {@link #execute(ToolInput, ToolContext)} method. Changes to the tool's behavior should be reflected in both the
     * definition and the execution logic.
     *
     * <p>
     * This method is typically called during tool registration with the LLM provider.
     *
     * @return the tool definition describing this tool's interface (never null)
     */
    ToolDefinition getDefinition();

    /**
     * Executes the tool with the given input parameters and context.
     *
     * <p>
     * This method is invoked when the LLM decides to use this tool to accomplish a task. The input parameters are
     * provided through a {@link ToolInput} object that offers type-safe access to the parameters defined in the tool's
     * schema.
     *
     * <p>
     * Implementation guidelines:
     *
     * <ol>
     * <li><b>Parameter Extraction:</b> Use {@link ToolInput}'s type-safe methods to extract parameters
     * <li><b>Validation:</b> Parameter validation is handled by ToolInput, but additional business logic validation may
     * be needed
     * <li><b>Execution:</b> Perform the tool's main operation
     * <li><b>Result Creation:</b> Return a {@link ToolResult} indicating success or failure
     * </ol>
     *
     * <h3>Error Handling</h3>
     *
     * <p>
     * This method should <b>never throw exceptions</b>. All errors, including validation failures, runtime errors, and
     * unexpected exceptions, should be captured and returned as error {@link ToolResult}s. This ensures the LLM
     * receives clear feedback about what went wrong and can decide how to proceed.
     *
     * <p>
     * Example error handling:
     *
     * <pre>
     * {@code
     * public ToolResult execute(ToolInput input, ToolContext context) {
     *     try {
     *         // Extract required parameters (throws IllegalArgumentException if missing)
     *         String command = input.getRequiredString("command");
     *
     *         // Extract optional parameters with defaults
     *         int timeout = input.getInteger("timeout", 2000);
     *
     *         // Execute the tool logic
     *         String output = runCommand(command, timeout);
     *         return ToolResult.success(output);
     *     } catch (IllegalArgumentException e) {
     *         return ToolResult.error("Invalid parameter: " + e.getMessage());
     *     } catch (Exception e) {
     *         return ToolResult.error("Execution failed: " + e.getMessage());
     *     }
     * }
     * }
     * </pre>
     *
     * <h3>Context Usage</h3>
     *
     * <p>
     * The {@link ToolContext} provides runtime information that may be useful during execution:
     *
     * <ul>
     * <li>Working directory for file operations
     * <li>User or session identifiers
     * <li>Configuration settings
     * <li>Shared state between tool invocations
     * </ul>
     *
     * @param input
     *            the tool input parameters (must not be null). Use type-safe methods to extract parameter values
     * @param context
     *            the execution context providing runtime information (must not be null)
     * @return the execution result containing either success output or error information (never null)
     * @throws NullPointerException
     *             if input or context is null
     */
    ToolResult execute(ToolInput input, ToolContext context);

    /**
     * Returns the {@link InterruptBehavior} this tool declares for cooperative interruption.
     *
     * <p>
     * The default is {@link InterruptBehavior#NON_INTERRUPTIBLE}, which matches the existing assumption that tools run
     * to completion once invoked. Tools that can observe a cancellation signal, tolerate {@code Thread.interrupt()}, or
     * register an external kill callback should override this method to declare the stronger behavior.
     *
     * <p>
     * The executor reads this declaration before invoking the tool so it can pick the correct propagation strategy:
     *
     * <ul>
     * <li>{@link InterruptBehavior#NON_INTERRUPTIBLE} — no propagation; the tool runs to completion and interrupts are
     * deferred to the next iteration boundary.
     * <li>{@link InterruptBehavior#COOPERATIVE} — the tool polls the
     * {@link at.aimon.core.agent.interrupt.CancellationSignal}
     * exposed through {@link ToolContext} and returns early when cancelled.
     * <li>{@link InterruptBehavior#THREAD_INTERRUPT} — the executor may call {@code Thread.interrupt()} on the tool's
     * thread when the signal trips.
     * <li>{@link InterruptBehavior#EXTERNALLY_TERMINATED} — the tool registers a terminator callback via the
     * {@link at.aimon.core.agent.interrupt.TerminatorRegistrar} exposed through {@link ToolContext} so the coordinator
     * can invoke it on trip.
     * </ul>
     *
     * @return the tool's interrupt behaviour declaration (never null)
     */
    default InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.NON_INTERRUPTIBLE;
    }

    /**
     * Returns the {@link ConcurrencyBehavior} this tool declares for batch-parallel execution.
     *
     * <p>
     * The default is {@link ConcurrencyBehavior#SEQUENTIAL}, which preserves the existing run-to-completion-in-order
     * behaviour: tools are never parallelised unless they explicitly opt in. Tools that are free of observable side
     * effects (or idempotent) and touch shared mutable state only through thread-safe means should override this to
     * declare {@link ConcurrencyBehavior#CONCURRENT_SAFE}.
     *
     * <p>
     * The {@code ParallelToolDispatcher} reads this declaration (together with {@link #getInterruptBehavior()}) before
     * deciding whether the tools in a single LLM-returned batch may run concurrently. A batch is parallelised only when
     * <em>every</em> tool in it is {@code CONCURRENT_SAFE}; a single {@code SEQUENTIAL} tool forces the whole batch to
     * run sequentially.
     *
     * @return the tool's concurrency behavior (never null)
     */
    default ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.SEQUENTIAL;
    }

    /**
     * Returns the {@link SideEffectLevel} this tool declares for what it does to the world.
     *
     * <p>
     * The default is {@link SideEffectLevel#MUTATING}, the conservative end: a tool that has never been audited is
     * assumed to change state. Existing tools therefore need no change, and remain available under the default
     * (unrestricted) ceiling.
     *
     * <p>
     * This is a different axis from {@link #getConcurrencyBehavior()} and neither can be derived from the other — a
     * read-only tool may still be {@link ConcurrencyBehavior#SEQUENTIAL} (contention on a rate-limited endpoint), and
     * an
     * idempotent <em>mutating</em> tool may be {@link ConcurrencyBehavior#CONCURRENT_SAFE}. Declare both independently.
     *
     * <p>
     * Two components read this declaration. {@code OrcaAgentExecutor} withholds the definitions of tools that exceed
     * its configured ceiling, so the LLM never sees them; {@code DefaultToolExecutionManager} refuses to execute them,
     * so the restriction holds even for a tool name the model produced from memory. Over-declaring defeats both.
     *
     * @return the tool's side-effect declaration (never null)
     */
    default SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.MUTATING;
    }

    /**
     * Convenience predicate for {@code getSideEffectLevel() == SideEffectLevel.READ_ONLY}.
     *
     * <p>
     * Derived rather than separately declared, so it cannot drift from {@link #getSideEffectLevel()}. Override that
     * method, never this one.
     *
     * @return true if this tool declares {@link SideEffectLevel#READ_ONLY}
     */
    default boolean isReadOnly() {
        return getSideEffectLevel() == SideEffectLevel.READ_ONLY;
    }

    /**
     * Returns the {@link DestructiveBehavior} this tool declares for what its writes do to state that already exists.
     *
     * <p>
     * The default is {@link DestructiveBehavior#DESTRUCTIVE}, the conservative end, matching both the default of
     * {@link #getSideEffectLevel()} and MCP's {@code destructiveHint}: a tool nobody has audited is assumed to be able
     * to destroy something. Declare {@link DestructiveBehavior#NON_DESTRUCTIVE} only when the tool's writes are
     * additive — it creates or appends and never overwrites, removes, or invalidates.
     *
     * <p>
     * This is <em>not</em> a finer grade of {@link #getSideEffectLevel()}; a destructive tool is not "more mutating"
     * than an additive one, it is differently so, which is why the two are separate declarations and only the first is
     * ordered. Consumers read this one <b>only when the level is {@link SideEffectLevel#MUTATING}</b>, so declaring
     * both {@code READ_ONLY} and {@code DESTRUCTIVE} is meaningless rather than contradictory — the second declaration
     * is never reached.
     *
     * <p>
     * {@code SideEffectApprovalGate} is the reader today: a tool declaring {@code DESTRUCTIVE} is put to the user
     * regardless of the gate's exemption threshold. The ceilings enforced by {@code OrcaAgentExecutor} and
     * {@code DefaultToolExecutionManager} do not consult this axis.
     *
     * @return the tool's destructiveness declaration (never null)
     */
    default DestructiveBehavior getDestructiveBehavior() {
        return DestructiveBehavior.DESTRUCTIVE;
    }
}
