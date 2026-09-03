package at.aimon.core.memory;

/**
 * When conversation is fed into the {@link MemoryCapability#INGEST} tier.
 *
 * <p>
 * The two live modes differ in cost and in freshness, and neither is right for everyone: {@link #SESSION_END} is one
 * derivation per session, cheap and late; {@link #EXECUTION_END} is one per execution, more expensive and current.
 * A remote memory backend wants the second — its batching, gating and idle flush are all designed around a message
 * stream — while a backend that derives with an LLM call per batch may not want the bill.
 */
public enum MemoryIngestMode {

    /** Nothing is fed in. Memory fills only through explicit {@code Observe} calls, or another process. */
    OFF,

    /**
     * The whole transcript is fed in once, when the session closes.
     *
     * <p>
     * No delta is computed, so nothing can be sent twice — the transcript goes across exactly once. The cost is
     * latency: nothing a session learns is available to it while it is running.
     */
    SESSION_END,

    /**
     * The messages an execution added are fed in when that execution ends.
     *
     * <p>
     * Execution, not turn: the seam stands in the same place for a session's turn and for a session-less execution.
     * This mode needs a delta, and a delta needs a watermark that compaction invalidates — see
     * {@link at.aimon.core.agent.session.transcript.TranscriptBuffer#messagesSinceIngestMark()} for what happens to an
     * execution whose history was rewritten underneath it.
     */
    EXECUTION_END
}
