package at.aimon.core.agent.context;

import java.util.Optional;

import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Decides what an LLM call is sent: the one place that shrinks the view of a transcript.
 *
 * <p>
 * Every loop that sends messages to an LLM asks the engine for the view right before the call instead of reading the
 * transcript buffer, and the three ways a view gets shrunk all live here &mdash; the compaction gate
 * ({@link #prepare}), prompt-too-long recovery ({@link #recover}) and {@code /compact} ({@link #compactNow}). With
 * several parties free to rewrite the transcript, none of them could guarantee a consistent view; with one, the engine
 * can. Design: {@code docs/design/agent-execution/context-engine.md}.
 *
 * <p>
 * An engine is agent-scoped, like the runtime it is wired into, and so must be thread-safe: concurrent sessions of
 * one agent call it concurrently. Implementations are expected to serialize work on the same transcript themselves.
 *
 * @see DefaultContextEngine
 */
public interface ContextEngine {

    /**
     * Called at the iteration boundary, right before the LLM call. Shrinks the view if needed (compaction) and returns
     * the view to send.
     *
     * @param request
     *            the call's inputs (must not be null)
     * @return the decision carrying the view to send (never null)
     * @throws NullPointerException
     *             if {@code request} is null
     */
    ContextDecision prepare(ContextRequest request);

    /**
     * Called after the provider rejected the view with prompt-too-long. Returns a smaller view to retry with, or empty
     * when nothing can be dropped, in which case the caller rethrows {@code error}.
     *
     * @param request
     *            the inputs of the rejected call (must not be null)
     * @param error
     *            the provider's rejection (must not be null)
     * @return a smaller view, or empty
     * @throws NullPointerException
     *             if any argument is null
     */
    Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error);

    /**
     * {@code /compact}: shrinks the view now, skipping the threshold decision.
     *
     * <p>
     * An engine that cannot compact returns a failure result rather than throwing.
     *
     * @param request
     *            the inputs (must not be null)
     * @param instructions
     *            caller-supplied advisory guidance for the summary, or {@code null} for none
     * @return the compaction outcome (never null, unless a delegated
     *         {@link at.aimon.core.agent.compact.CompactionEngine}
     *         itself returned null)
     * @throws NullPointerException
     *             if {@code request} is null
     */
    CompactionResult compactNow(ContextRequest request, String instructions);

    /**
     * An engine that never shrinks the view: every call sends the transcript as it is. Equivalent to wiring
     * {@link at.aimon.core.agent.compact.NoOpCompactionGuard} with no recovery strategy.
     *
     * @return the shared stateless instance (never null)
     */
    static ContextEngine passthrough() {
        return PassthroughContextEngine.INSTANCE;
    }
}
