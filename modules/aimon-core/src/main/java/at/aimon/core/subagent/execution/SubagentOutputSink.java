package at.aimon.core.subagent.execution;

/**
 * A one-way sink for a subagent's live progress output (design §5).
 *
 * <p>
 * {@link DefaultSubagentExecutor} calls {@link #append(String)} at the salient points of its ReAct loop (iteration
 * boundaries, the assistant's reasoning preamble, tool starts/results, and the terminal answer). For a background task
 * the sink is bound to a {@code TaskOutputStore} so the {@code AgentOutput} tool can tail the progress incrementally;
 * for foreground execution it is {@link #NO_OP}, preserving the previous "no events emitted" behaviour with zero cost.
 *
 * <p>
 * Implementations must be thread-safe: {@code append} may be invoked from parallel tool-result callbacks running on
 * shared worker threads.
 */
@FunctionalInterface
public interface SubagentOutputSink {

    /** A sink that discards all output. The default for foreground execution. */
    SubagentOutputSink NO_OP = text -> {
    };

    /**
     * Appends a chunk of progress text to the sink.
     *
     * @param text
     *            the text to append (implementations may ignore null/empty)
     */
    void append(String text);
}
