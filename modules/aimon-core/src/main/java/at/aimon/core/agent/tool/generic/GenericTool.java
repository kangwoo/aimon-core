package at.aimon.core.agent.tool.generic;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.llm.DynamicToolDefinitionProvider;
import at.aimon.core.llm.ToolDefinition;

/**
 * A tool whose parameters are declared once, as a record, and read back as that record.
 *
 * <p>
 * {@link AbstractTool} leaves a tool author to write the schema and the parameter extraction separately, and nothing
 * checks that the two agree. They routinely do not: a parameter is declared and never read, or read under a name the
 * schema never advertised, or declared required and then quietly defaulted. Binding the input to a record makes the
 * declaration single — the schema is derived from it and the values are bound into it — so the two cannot disagree.
 *
 * <p>
 * <b>Opt-in, not a replacement.</b> {@code AbstractTool} stays, and a tool with two string parameters has nothing to
 * gain here. The cost is one extra type and reflection at construction; the return grows with the parameter count and
 * with how many of them are optional. The rough line is in the tool development guide.
 *
 * <h2>What the base class takes over</h2>
 *
 * <ul>
 * <li><b>The schema</b> — derived from the record by {@link ToolSchemaGenerator}, including
 * {@code additionalProperties: false}
 * <li><b>Parameter reading</b> — {@link ToolInputBinder} reports <em>every</em> mismatch at once, in the same words
 * the executor's schema gate uses
 * <li><b>The no-throw contract</b> — {@link #execute} is {@code final} and catches everything, so a subclass cannot
 * break the rule that a tool returns a {@code ToolResult} rather than throwing
 * </ul>
 *
 * <h2>Errors a tool means to report</h2>
 *
 * <p>
 * Two paths, and the difference matters to the model. A {@link ToolExecutionException} is a failure the tool
 * anticipated — an unparseable regex, an interrupted search — and its message reaches the model verbatim, because the
 * tool wrote it for the model. Anything else is a bug, is logged with its stack trace, and reaches the model prefixed
 * as unexpected. Do not throw {@code ToolExecutionException} for a parameter problem the record could have expressed;
 * declare it in the record instead and let binding say it.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {@code
 * public class EchoTool extends GenericTool<EchoInput, String> {
 *
 *     public EchoTool() {
 *         super("Echo", "Repeats a message back", EchoInput.class);
 *     }
 *
 *     &#64;Override
 *     protected String doExecute(EchoInput input, ToolContext context) {
 *         return input.message().repeat(input.times() == null ? 1 : input.times());
 *     }
 *
 *     &#64;Override
 *     protected ToolResult render(String output) {
 *         return ToolResult.success(output);
 *     }
 * }
 * }
 * </pre>
 *
 * @param <I>
 *            the record the tool's parameters bind to
 * @param <O>
 *            what {@link #doExecute} produces, which {@link #render} turns into a {@code ToolResult}
 * @see ToolParam
 * @see ToolSchemaGenerator
 * @see ToolInputBinder
 */
public abstract class GenericTool<I, O> extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenericTool.class);

    private final ToolInputBinder<I> binder;

    /**
     * Creates a tool with a fixed description and the default category.
     *
     * @param name
     *            the tool name the model calls (must not be null)
     * @param description
     *            what the tool does, written for the model (must not be null)
     * @param inputType
     *            the record holding the tool's parameters (must not be null)
     */
    protected GenericTool(String name, String description, Class<I> inputType) {
        this(name, description, ToolDefinition.DEFAULT_CATEGORY, inputType);
    }

    /**
     * Creates a tool with a fixed description.
     *
     * @param name
     *            the tool name the model calls (must not be null)
     * @param description
     *            what the tool does, written for the model (must not be null)
     * @param category
     *            the grouping shown in tool listings — see {@code ToolCategories} (must not be null)
     * @param inputType
     *            the record holding the tool's parameters (must not be null)
     */
    protected GenericTool(String name, String description, String category, Class<I> inputType) {
        super(name, description, category, schemaOf(inputType));
        this.binder = ToolInputBinder.forType(inputType);
    }

    /**
     * Creates a tool whose description is recomputed on each read, for a description that depends on runtime state.
     *
     * <p>
     * Only the description is dynamic. The schema comes from the record and is derived once, here — a tool whose
     * <em>parameters</em> vary at runtime cannot be expressed as a record and belongs on {@link AbstractTool}.
     *
     * @param name
     *            the tool name the model calls (must not be null)
     * @param category
     *            the grouping shown in tool listings (must not be null)
     * @param descriptionSupplier
     *            supplies the description on each read (must not be null)
     * @param inputType
     *            the record holding the tool's parameters (must not be null)
     */
    protected GenericTool(String name, String category, Supplier<String> descriptionSupplier, Class<I> inputType) {
        super(new DynamicToolDefinitionProvider(name, category, descriptionSupplier, schemaOf(inputType)));
        this.binder = ToolInputBinder.forType(inputType);
    }

    private static Map<String, Object> schemaOf(Class<?> inputType) {
        Objects.requireNonNull(inputType, "Input type cannot be null");
        return ToolSchemaGenerator.generate(inputType);
    }

    /**
     * Binds the call's parameters, runs the tool, and renders the outcome.
     *
     * <p>
     * {@code final} on purpose. The rule that a tool never throws is the one every caller in the framework relies on,
     * and leaving it to each subclass to remember is how it gets broken. Subclasses fill in {@link #doExecute} and
     * {@link #render}; the guarantee lives here.
     *
     * @param input
     *            the call's parameters (must not be null)
     * @param context
     *            the runtime context (must not be null)
     * @return the tool's result, or an error result — never an exception
     */
    @Override
    public final ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final String name = getDefinition().getName();
        try {
            // Inside the try, not before it. Binding reports a bad parameter as a violation rather than by throwing,
            // but it runs reflection over a type it did not write, and the no-throw guarantee this method advertises
            // has to cover that too — otherwise the one caller-visible promise of the class holds everywhere except
            // in its own first statement.
            final BindResult<I> bound = binder.bind(input);
            if (!bound.isBound()) {
                log.warn("Rejected call to tool '{}': {}", name, bound.getViolations());
                return ToolResult
                        .error("Invalid input for tool '" + name + "':\n" + String.join("\n", bound.getViolations()));
            }

            final ToolResult result = render(doExecute(bound.getValue(), context));
            if (result == null) {
                log.error("Tool '{}' rendered a null result", name);
                return ToolResult.error("Unexpected error: tool '" + name + "' produced no result");
            }
            return result;
        } catch (ToolExecutionException e) {
            log.warn("Tool '{}' failed: {}", name, e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error in tool '{}': {}", name, e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Runs the tool against its bound parameters.
     *
     * <p>
     * Unlike {@code execute}, this <em>may</em> throw — that is the point of it being separate. Throw
     * {@link ToolExecutionException} for a failure the model should read in your own words; anything else is treated
     * as a bug and reported as unexpected.
     *
     * @param input
     *            the bound parameters (never null)
     * @param context
     *            the runtime context (never null)
     * @return whatever {@link #render} needs to build the result
     * @throws Exception
     *             if the tool fails
     */
    protected abstract O doExecute(I input, ToolContext context) throws Exception;

    /**
     * Turns what the tool produced into the result the model reads.
     *
     * <p>
     * Kept apart from {@link #doExecute} so that what the tool <em>did</em> and how it is <em>worded</em> can be read,
     * changed, and tested separately. For many tools this is one line.
     *
     * @param output
     *            what {@link #doExecute} returned
     * @return the result (must not be null)
     */
    protected abstract ToolResult render(O output);
}
