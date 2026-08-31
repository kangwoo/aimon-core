package at.aimon.core.tracing;

/**
 * The kind of work a {@link TraceSpan} represents within an agent execution trace.
 *
 * <p>
 * A trace (one turn) is a tree of spans: a {@link #TURN} root contains {@link #ITERATION} spans, each of which contains
 * {@link #LLM} and {@link #TOOL} spans; a {@link #TOOL} that spawns a subagent contains a {@link #SUBAGENT} subtree.
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

    /** A conversation compaction operation. */
    COMPACTION,

    /** A knowledge-base / retrieval operation. */
    RETRIEVER
}
