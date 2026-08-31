package at.aimon.core.agent.session.transcript;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionRecordStore;

/**
 * Manages the transcript lifecycle and its persistence.
 *
 * <p>
 * The TranscriptManager is responsible for rehydrating a session's transcript, saving it, and managing its
 * persistence. It provides a clean abstraction over the record store, allowing different storage backends to be used
 * without changing client code.
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Single Responsibility:</b> Only responsible for transcript lifecycle management
 * <li><b>Dependency Inversion:</b> Depends on TranscriptBuffer and SessionId abstractions
 * <li><b>Open/Closed Principle:</b> Open for extension (new storage backends) but closed for modification
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Initialize and Append User Message</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     TranscriptManager manager = new DefaultTranscriptManager(repository);
 *     SessionId id = SessionId.generate();
 *
 *     TranscriptBuffer memory = manager.initialize(id, "You are a helpful assistant.");
 *     memory.addUserMessage("Hello!");
 * }
 * </pre>
 *
 * <h3>Save the transcript</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Save with potential exceptions
 *     manager.save(memory);
 *
 *     // Or save silently (errors logged but not thrown)
 *     manager.saveSilently(memory);
 * }
 * </pre>
 *
 * @see TranscriptBuffer
 * @see SessionId
 * @see SessionRecordStore
 */
public interface TranscriptManager {
    /**
     * Initializes the transcript without appending any user message.
     *
     * <p>
     * Loads the transcript recorded for {@code sessionId} if one exists, or creates a fresh one. The supplied
     * {@code systemPrompt} is applied to the returned buffer. The returned buffer's message list is identical to the
     * stored history (or empty for a new session); callers are responsible for appending any synthetic or
     * user messages afterwards via {@link TranscriptBuffer#addUserMessage(String)} or
     * {@link TranscriptBuffer#addMessage(at.aimon.core.llm.Message)}.
     *
     * @param sessionId
     *            The session id (must not be null)
     * @param systemPrompt
     *            The system prompt that defines agent behavior (can be null)
     * @return A transcript buffer with the correct system prompt and any previously stored messages (never null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    TranscriptBuffer initialize(SessionId sessionId, String systemPrompt);

    /**
     * Saves the transcript buffer to the repository.
     *
     * <p>
     * This method persists the transcript. Any errors during save are propagated to the caller, allowing them
     * to handle persistence failures appropriately.
     *
     * @param memory
     *            The transcript buffer to save (must not be null)
     * @throws NullPointerException
     *             if memory is null
     * @throws RuntimeException
     *             if the save operation fails
     */
    void save(TranscriptBuffer memory);

    /**
     * Saves the transcript buffer to the repository, suppressing exceptions, and signals end-of-turn.
     *
     * <p>
     * This method persists the transcript but does not throw exceptions on failure. Errors are logged but not
     * propagated to the caller. Use this method in {@code finally} blocks at the end of a ReAct loop turn — after this
     * call returns, the implementation may stop any background flushing it was performing for this memory.
     *
     * @param memory
     *            The transcript buffer to save (must not be null)
     * @throws NullPointerException
     *             if memory is null
     */
    void saveSilently(TranscriptBuffer memory);
}
