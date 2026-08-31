package at.aimon.core.workflow;

/**
 * An workflow script: the unit an {@link WorkflowRunner} executes.
 *
 * <p>
 * The author encodes all control flow (loops, conditionals, fan-out) in plain Java and calls the
 * {@link WorkflowContext} primitives ({@code agent}, {@code parallel}, {@code pipeline}, {@code phase},
 * {@code log}); the LLM only runs inside each subagent. A script is deterministic, fully type-safe, debuggable and
 * unit-testable.
 *
 * @param <T>
 *            the type the script produces
 */
@FunctionalInterface
public interface WorkflowScript<T> {

    /**
     * Runs the script against the given context.
     *
     * @param ctx
     *            the run-scoped workflow context (never null)
     * @return the script's result
     */
    T run(WorkflowContext ctx);
}
