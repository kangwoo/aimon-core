package at.aimon.core.agent.compact;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;

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
 *
 * <p>
 * <b>Two entries.</b> {@link #compact(CompactionRequest)} summarizes <em>and</em> rewrites the transcript buffer.
 * {@link #summarize(SummaryRequest)} does only the first half: it returns the summary and leaves every transcript
 * alone, for a caller that records the summary somewhere else than the messages it replaces. An engine advertises the
 * second entry through {@link #supportsSummarize()}, so a caller can tell whether it is there without calling it.
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
     * @deprecated Rewrites the transcript in place, which a version-2 (append-only) log does not allow. A context
     *             engine calls {@link #summarize(SummaryRequest)}, records the summary in the view state and reports
     *             it through {@link #summaryInstalled}. This entry remains for the version-1 write mode
     *             (context-engine §8.2).
     */
    @Deprecated
    CompactionResult compact(CompactionRequest request);

    /**
     * Whether {@link #summarize(SummaryRequest)} is implemented. {@code false} by default, so an engine written before
     * the method existed keeps compiling and is recognisably unable to summarize.
     *
     * @return {@code true} if {@link #summarize(SummaryRequest)} can be called
     */
    default boolean supportsSummarize() {
        return false;
    }

    /**
     * Produces a summary of {@link SummaryRequest#getMessages()} without touching any transcript.
     *
     * <p>
     * Same contract as {@link #compact(CompactionRequest)} for everything up to the summary: PreCompact hooks run and
     * AUTO blocks abort, non-text blocks are stripped, the summary LLM call carries no tools and feature
     * {@code COMPACTION}, reentrant calls fail. What it does not do is the rest: no transcript is rewritten and no
     * PostCompact hook fires, because both need the state after the summary was installed, which only the caller
     * produces. {@link CompactionMetadata#getPostCompactTokenCount()} is therefore {@code 0}.
     *
     * @param request
     *            the summary parameters (must not be null)
     * @return the result, carrying the summary text on success
     * @throws UnsupportedOperationException
     *             if {@link #supportsSummarize()} is {@code false}
     * @throws NullPointerException
     *             if {@code request} is null
     */
    default CompactionResult summarize(SummaryRequest request) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support summarize(); check supportsSummarize() first");
    }

    /**
     * Tells the engine that a summary {@link #summarize(SummaryRequest)} produced has been installed, so it can do the
     * half {@code summarize} leaves out: fire the {@code PostCompactHook}s, now that the post-compaction state exists.
     *
     * <p>
     * A context engine that keeps the log append-only records the summary in the view state rather than in the
     * transcript, and calls this afterwards. The hooks receive {@code transcriptBuffer} — what a restore hook appends
     * there lands after the summarized range and so is part of the next view — and {@code installed}'s metadata,
     * whose post-compaction size is the caller's to fill in. Hook failures are the engine's to swallow; this must not
     * throw for them.
     *
     * <p>
     * The default does nothing: an engine written before this method existed has no hooks of its own to fire.
     *
     * @param request
     *            the request the summary was produced for (must not be null)
     * @param installed
     *            the successful result, with its metadata completed by the caller (must not be null)
     * @param transcriptBuffer
     *            the buffer the summary was installed in (must not be null)
     */
    default void summaryInstalled(SummaryRequest request, CompactionResult installed,
            TranscriptBuffer transcriptBuffer) {
        // no-op by default
    }
}
