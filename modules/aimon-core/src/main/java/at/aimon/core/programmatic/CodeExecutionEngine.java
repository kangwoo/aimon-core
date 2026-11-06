package at.aimon.core.programmatic;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;

/**
 * A language engine for {@code ExecuteCode} ({@code docs/design/tool/programmatic-tool-calling.md} §9).
 *
 * <p>
 * Application-scoped: built once at assembly and closed at shutdown, never per call
 * ({@code docs/overview/scope-model.md}). It holds no state that must outlive one {@code ExecuteCode} call; what it may
 * cache (a probe result per environment object) is held weakly.
 *
 * <p>
 * Implementations are thread-safe: several executions may run programs at once.
 */
public interface CodeExecutionEngine extends AutoCloseable {

    /** The language of the GraalJS engine. */
    String JAVASCRIPT = "javascript";

    /** The language of the shell engine. */
    String PYTHON = "python";

    /**
     * Returns the language this engine runs.
     *
     * @return {@link #JAVASCRIPT} or {@link #PYTHON} (never null)
     */
    String language();

    /**
     * Judges at offer time, from static facts only, whether this engine can run code for the offer — no shell command,
     * no file write (§6.2).
     *
     * @param facts
     *            the offer's static facts (must not be null)
     * @return the availability (never null)
     */
    EngineAvailability staticAvailability(OfferFacts facts);

    /**
     * Judges, on the first execution per environment object, whether this engine can run there. May run commands; the
     * engine caches the answer by the object's identity. The default answers available without looking.
     *
     * @param environment
     *            the execution's environment, or {@code null} when the execution has none
     * @return the availability (never null)
     */
    default EngineAvailability probe(ExecutionEnvironment environment) {
        return EngineAvailability.available();
    }

    /**
     * Returns the grade of code this engine runs, before the callable set is folded in (decision 10).
     *
     * @return the grade (never null)
     */
    ToolGrade intrinsicGrade();

    /**
     * Returns whether the callable set of this engine is bounded by {@code shell-engine-tool-scope}
     * ({@code docs/design/tool/programmatic-tool-calling-shell.md} §12.2, design §7.3 step 5): the engine runs code
     * where
     * a process co-resident with it can reach its channel to the host and call its tools, so the tools it opens are
     * limited by how far they reach. The default — an engine whose only way out is the bridge, in the host JVM — is
     * not bounded.
     *
     * @return {@code true} when the tool scope applies
     */
    default boolean isToolScopeBound() {
        return false;
    }

    /**
     * Runs one program. Never throws for a program's failure — a script error, a timeout, a cancellation and a crossed
     * limit are all in the outcome. Throws only when the host itself is broken.
     *
     * @param request
     *            the program and its limits (must not be null)
     * @param bridge
     *            the program's only way to call tools (must not be null)
     * @param context
     *            the {@code ExecuteCode} call's tool context (must not be null)
     * @return the outcome (never null)
     */
    CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context);

    /**
     * Releases what the engine holds (a shared language engine, its threads). Called once, at shutdown. The default
     * holds nothing.
     */
    @Override
    default void close() {
    }
}
