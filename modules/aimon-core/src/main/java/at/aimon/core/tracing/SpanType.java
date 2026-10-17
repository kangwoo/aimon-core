package at.aimon.core.tracing;

/**
 * The kind of work a {@link TraceSpan} represents within an agent execution trace.
 *
 * <p>
 * A trace (one turn) is a tree of spans: a {@link #TURN} root contains {@link #ITERATION} spans, each of which contains
 * {@link #LLM} and {@link #TOOL} spans; a {@link #TOOL} that spawns a subagent contains a {@link #SUBAGENT} subtree.
 *
 * <p>
 * Two shapes start a subagent without a model-chosen tool call. A turn that runs a slash command instead of the ReAct
 * loop opens a {@link #COMMAND} span under its {@link #TURN}, and a fork the command starts nests under it. A scheduled
 * routine run has no turn at all: it is a {@link #ROUTINE} root whose step attempts are {@link #TOOL} children.
 */
public enum SpanType {

    /** The root span of a trace — one turn ({@code LiveSession.submit()} / {@code OrcaAgentExecutor.execute()}). */
    TURN,

    /** A single ReAct loop iteration. */
    ITERATION,

    /** A single LLM call (prompt, response, tokens, latency, model). */
    LLM,

    /** A single tool invocation. */
    TOOL,

    /** A spawned subagent execution (contains its own nested iteration/llm/tool spans). */
    SUBAGENT,

    /**
     * A conversation compaction operation. Reserved: nothing emits it yet — a compaction summary call is recorded as an
     * {@link #LLM} span with a {@code feature=COMPACTION} attribute.
     */
    COMPACTION,

    /** A knowledge-base / retrieval operation. */
    RETRIEVER,

    /** A slash command a turn runs instead of the ReAct loop; a fork it starts nests under it. */
    COMMAND,

    /** The root span of one scheduled routine run (one fire); each step attempt is a {@link #TOOL} child. */
    ROUTINE
}
