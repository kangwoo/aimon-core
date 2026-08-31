package at.aimon.core.agent.tool.execution;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.schema.DefaultToolInputSchemaValidator;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;
import at.aimon.core.agent.tool.schema.SchemaValidationResult;
import at.aimon.core.agent.tool.schema.ToolInputSchemaValidator;
import at.aimon.core.agent.tool.schema.ViolationMessages;

/**
 * Default implementation of {@link ToolExecutor} that executes tools from a {@link ToolExecutionContext}.
 *
 * <p>
 * This class provides a simple, stateless tool executor that delegates tool execution to the {@link Tool} instance
 * provided in the {@link ToolExecutionContext}. It follows the Strategy pattern, where the actual tool implementation
 * is determined by the context passed at execution time.
 *
 * <p>
 * <b>Design Characteristics:</b>
 *
 * <ul>
 * <li><b>Stateless:</b> Holds no per-call state, making it thread-safe and reusable
 * <li><b>Delegation:</b> Delegates execution to the Tool instance in the context
 * <li><b>Simplicity:</b> Minimal logic, focused on coordinating execution flow
 * <li><b>Extensibility:</b> Can be extended to add cross-cutting concerns (logging, metrics, validation)
 * </ul>
 *
 * <p>
 * <b>Schema validation.</b> Before a tool runs, its arguments are checked against the schema the tool declares — see
 * {@link ToolInputSchemaValidator}. This is the one place where every tool on this path gets that check, including
 * tools we did not write. The reaction to a violation is chosen by a {@link SchemaValidationMode}, which defaults to
 * {@link SchemaValidationMode#WARN}: violations are logged, the tool still runs, and nothing the model sees changes.
 * See {@link #execute(ToolExecutionContext, ToolExecutionRequest)} for what each mode does.
 *
 * <p>
 * Thread-safe and can be safely shared across multiple threads.
 *
 * <p>
 * <b>Example usage:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create tools
 *     Tool bashTool = new BashTool(shell);
 *     Tool readTool = new ReadTool(fileSystem);
 *
 *     // Create executor (stateless, can be reused)
 *     ToolExecutor executor = new DefaultToolExecutor();
 *
 *     // Execute bash tool
 *     ToolExecutionContext bashContext = ToolExecutionContext.of(bashTool);
 *     ToolInput bashInput = ToolInput.of(Map.of("command", "ls -la"));
 *     ToolExecutionRequest bashRequest = ToolExecutionRequest.of("tool_123", bashInput, ToolContext.empty());
 *     ToolExecutionResult bashResult = executor.execute(bashContext, bashRequest);
 *
 *     // Execute read tool (same executor, different context)
 *     ToolExecutionContext readContext = ToolExecutionContext.of(readTool);
 *     ToolInput readInput = ToolInput.of(Map.of("file_path", "/path/to/file"));
 *     ToolExecutionRequest readRequest = ToolExecutionRequest.of("tool_456", readInput, ToolContext.empty());
 *     ToolExecutionResult readResult = executor.execute(readContext, readRequest);
 * }
 * </pre>
 *
 * <p>
 * <b>Extension Points:</b>
 *
 * <p>
 * Subclasses can override {@link #execute(ToolExecutionContext, ToolExecutionRequest)} to add:
 *
 * <ul>
 * <li>Execution logging and audit trails
 * <li>Performance metrics and monitoring
 * <li>Input validation and sanitization
 * <li>Error handling and recovery strategies
 * <li>Resource usage tracking
 * </ul>
 *
 * @see ToolExecutor
 * @see Tool
 * @see ToolExecutionContext
 * @see ToolExecutionRequest
 * @see ToolExecutionResult
 */
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolInputSchemaValidator schemaValidator;
    private final SchemaValidationMode validationMode;

    /**
     * Creates a new DefaultToolExecutor that validates input and logs violations without acting on them.
     *
     * <p>
     * Equivalent to {@code new DefaultToolExecutor(new DefaultToolInputSchemaValidator(), SchemaValidationMode.WARN)}.
     *
     * <p>
     * This executor holds no per-call state and can be safely shared across multiple threads. The same instance can be
     * used to execute different tools by passing different {@link ToolExecutionContext} instances.
     */
    public DefaultToolExecutor() {
        this(new DefaultToolInputSchemaValidator(), SchemaValidationMode.WARN);
    }

    /**
     * Creates a new DefaultToolExecutor with an explicit validator and mode.
     *
     * <p>
     * The mode is the intended way to turn validation off: {@link SchemaValidationMode#OFF} short-circuits before the
     * validator is consulted, so passing a real validator with {@code OFF} costs nothing. Supplying a no-op
     * {@link ToolInputSchemaValidator} is the alternative for an assembly that wants the decision made once, at wiring
     * time, rather than read from a mode.
     *
     * @param schemaValidator
     *            the validator to check tool input with (must not be null)
     * @param validationMode
     *            how to react to a violation (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public DefaultToolExecutor(ToolInputSchemaValidator schemaValidator, SchemaValidationMode validationMode) {
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "ToolInputSchemaValidator cannot be null");
        this.validationMode = Objects.requireNonNull(validationMode, "SchemaValidationMode cannot be null");
    }

    /**
     * Executes a tool based on the provided execution context and request.
     *
     * <p>
     * This method implements the core execution flow:
     *
     * <ol>
     * <li>Extracts the {@link Tool} instance from the {@link ToolExecutionContext}
     * <li>Retrieves the tool input parameters and tool context from the {@link ToolExecutionRequest}
     * <li>Checks the input against the tool's declared schema, unless the mode is {@link SchemaValidationMode#OFF}
     * <li>Delegates execution to the tool's execute method
     * <li>Wraps the {@link ToolResult} in a {@link ToolExecutionResult} with the request ID
     * </ol>
     *
     * <p>
     * <b>What a violation does</b>, by mode: {@code OFF} never looks; {@code WARN} logs and runs the tool anyway;
     * {@code ENFORCE} skips step 4 and returns the violations as a tool error, so the model can correct the call.
     *
     * <p>
     * The method is stateless and thread-safe, making it suitable for concurrent execution. Each invocation is
     * independent and does not affect other concurrent or subsequent executions.
     *
     * <p>
     * <b>Error Handling:</b>
     *
     * <ul>
     * <li>Throws {@link NullPointerException} for null parameters (fail-fast)
     * <li>Any exceptions from the tool's execute method propagate to the caller
     * <li>No exception wrapping or transformation is performed
     * </ul>
     *
     * @param executionContext
     *            The execution context containing the tool to execute (must not be null)
     * @param request
     *            The execution request containing tool input and tool context (must not be null)
     * @return The execution result containing the tool use ID and tool result (never null)
     * @throws NullPointerException
     *             if executionContext or request is null
     * @throws RuntimeException
     *             if the tool execution fails (propagated from the tool implementation)
     */
    @Override
    public ToolExecutionResult execute(ToolExecutionContext executionContext, ToolExecutionRequest request) {
        Objects.requireNonNull(executionContext, "ToolExecutionContext cannot be null");
        Objects.requireNonNull(request, "ToolExecutionRequest cannot be null");

        final Tool tool = executionContext.getTool();

        if (validationMode != SchemaValidationMode.OFF) {
            final String toolName = tool.getDefinition().getName();
            final SchemaValidationResult validation = schemaValidator.validate(tool.getDefinition().getInputSchema(),
                    request.getInput());
            if (!validation.isValid()) {
                final String violations = String.join("\n", validation.getViolations());
                if (validationMode == SchemaValidationMode.ENFORCE) {
                    log.warn("Rejecting call to tool '{}' — input does not match its schema:\n{}", toolName,
                            violations);
                    return ToolExecutionResult.of(request.getId(),
                            ToolResult.error("Invalid input for tool '" + toolName + "':\n" + violations));
                }
                // WARN: the violations are worded for a model that was refused, but here the tool runs anyway.
                log.warn(
                        "Input for tool '{}' does not match its schema. Executing it anyway"
                                + " (SchemaValidationMode.WARN); the '{}' in each line below does not apply:\n{}",
                        toolName, ViolationMessages.NOT_EXECUTED, violations);
            }
        }

        final ToolResult toolResult = tool.execute(request.getInput(), request.getToolContext());
        return ToolExecutionResult.of(request.getId(), toolResult);
    }

}
