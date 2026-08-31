package at.aimon.core.agent.compact;

/**
 * Performs the L3 full compaction: collapse the entire conversation history into a single LLM-generated summary that
 * replaces the previous messages.
 *
 * <p>
 * Implementations are expected to:
 *
 * <ul>
 * <li>Invoke {@code PreCompactHook}s and honor block decisions per {@link CompactionTrigger} policy (AUTO blocks abort,
 * MANUAL blocks are downgraded to warnings).
 * <li>Strip non-text content blocks (images/documents) before sending to the summary model.
 * <li>Call the {@link at.aimon.core.llm.LlmClient} with no tools and metadata
 * {@code feature = LlmCallMetadata.Feature.COMPACTION}.
 * <li>Atomically replace the {@link at.aimon.core.agent.session.transcript.TranscriptBuffer} content with the summary
 * message bracketed by UUID-suffixed boundary markers.
 * <li>Invoke {@code PostCompactHook}s with the post-compaction memory and metadata.
 * </ul>
 *
 * <p>
 * Implementations must be thread-safe; concurrent compactions of the same session are coordinated by the caller
 * (typically {@link CompactionGuard}).
 */
public interface CompactionEngine {

    /**
     * Executes a single compaction cycle.
     *
     * @param request
     *            the compaction parameters (must not be null)
     * @return the result, including success/failure status, the generated summary text on success, and metadata in
     *         either case
     * @throws NullPointerException
     *             if {@code request} is null
     */
    CompactionResult compact(CompactionRequest request);
}
