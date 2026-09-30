package at.aimon.core.agent.session.transcript;

/**
 * Where a session log entry came from: the conversation itself, or the runtime.
 *
 * <p>
 * The distinction is kept on the log entry rather than on the {@link at.aimon.core.llm.Message}. A message has no
 * metadata slot, and adding one would reach every {@code LlmClient} conversion and every codec. Telling the two apart
 * by text instead does not work either: a mid-turn user message is wrapped in a {@code <system-reminder>} block just
 * as the runtime's own injections are, and it is conversation.
 *
 * <p>
 * Consumers that read the log as <em>what was said</em> — memory ingest, history recall — skip {@link #SYNTHETIC}
 * entries. The LLM view does not: a synthetic entry is sent to the model like any other message.
 *
 * <p>
 * An entry migrated from a version-1 record is always {@link #CONVERSATION}: that format kept no origin, so there is
 * nothing to tell them apart by.
 */
public enum LogOrigin {

    /**
     * Part of the conversation — the user's input, an assistant response, a tool result, a user message injected
     * mid-turn from the queue.
     */
    CONVERSATION,

    /**
     * Put there by the runtime — the user-context block, an assembled {@code <system-reminder>}, advisory hook
     * feedback, a command's response, a file or skill list re-attached after compaction.
     */
    SYNTHETIC
}
