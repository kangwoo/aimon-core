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
     *
     * <p>
     * IMPORTANT: <b>this mode is an obligation on the caller, not a seam the stack installs.</b> Session close is not
     * something the executor sees, so the assembly wires nothing for it: whoever assembles the stack must feed the
     * transcript itself when a session ends, from a reference it kept — the CLI's shutdown-phase runnable enqueues on
     * the derivation queue it built. Choosing this mode and wiring no such path is a memory that never fills, and in
     * fixed-peer mode it reports every capability present while doing so — no assembly can detect the missing half.
     * (A per-caller assembly raises an INGEST degradation anyway, but for its own reason: it has no observer to
     * attribute a conversation to, whatever the mode.) {@link #EXECUTION_END} is the mode where the stack feeds.
     *
     * <p>
     * A caller taking this on inherits the redaction gate with it. The assembly wraps the backend and does not
     * publish the wrapper, so a self-driven feed reaches whatever the caller kept — for the CLI that is a queue which
     * redacts inside {@code enqueue}, and for anyone holding the raw backend it is nothing at all.
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
