package at.aimon.core.agent.tool.schema;

import java.util.Map;

import at.aimon.core.agent.tool.ToolInput;

/**
 * Checks a tool call's arguments against the tool's declared input schema, before the tool runs.
 *
 * <p>
 * The point of this SPI is that <b>a tool does not have to cooperate to be protected by it.</b> A tool declares a
 * schema for the model's benefit; until now nothing compared the call against that declaration, so every tool
 * re-implemented the same four checks by hand in {@code execute()} — or, more often, skipped some of them and let a
 * mistyped parameter surface as a confusing runtime error. Doing it once at the executor covers tools we did not write
 * too, MCP tools among them.
 *
 * <p>
 * <b>The schema is taken as the truth.</b> This layer cannot tell whether a schema matches the code that reads the
 * parameters — a schema can be wrong, and then this validator faithfully enforces the wrong thing. Keeping the two in
 * step is a different mechanism's job ({@code GenericTool} derives the schema from the parameter type, so they cannot
 * drift), and the two layers are meant to stack rather than replace one another.
 *
 * <p>
 * <b>Implementations must not throw.</b> A validator that fails on a schema it does not understand would take down
 * tool calls that work today, which is the opposite of the point. The contract for unfamiliar input is to pass it —
 * see {@link DefaultToolInputSchemaValidator} for the specific cases.
 *
 * <p>
 * Implementations must be thread-safe: one instance is shared by every tool call, including the concurrent ones.
 *
 * @see DefaultToolInputSchemaValidator
 * @see SchemaValidationResult
 */
public interface ToolInputSchemaValidator {

    /**
     * Validates a tool call's arguments against the tool's declared input schema.
     *
     * @param inputSchema
     *            the tool's JSON Schema as declared by {@code ToolDefinition#getInputSchema()} (may be null or empty,
     *            which means there is nothing to check)
     * @param input
     *            the arguments the caller supplied (must not be null)
     * @return the outcome — never null, and valid whenever the schema gives no grounds to reject
     */
    SchemaValidationResult validate(Map<String, Object> inputSchema, ToolInput input);
}
