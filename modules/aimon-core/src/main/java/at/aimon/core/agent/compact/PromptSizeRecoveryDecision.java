package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.Message;

/**
 * Outcome of a {@link PromptSizeRecoveryStrategy} attempt.
 *
 * <p>
 * The strategy is consulted after a provider rejects an LLM request with
 * {@link at.aimon.core.llm.exception.LlmPromptTooLongException}. Two outcomes are possible:
 *
 * <ul>
 * <li>{@link Action#RETRY} — the strategy produced a shorter message list. The caller installs the new list (typically
 * via {@link at.aimon.core.agent.session.transcript.TranscriptBuffer#replaceWith(List)}) and re-issues the LLM call
 * once.
 * <li>{@link Action#NONE} — no safe drop is possible (e.g. all remaining messages are protected). The caller propagates
 * the original exception so the ReAct loop's standard error path runs.
 * </ul>
 *
 * <p>
 * Immutable value object built via {@link #retry(List, String)} or {@link #none(String)}.
 */
public final class PromptSizeRecoveryDecision {

    /** Action the caller should take in response to a recovery attempt. */
    public enum Action {
        /** No safe recovery available; rethrow the original prompt-too-long error. */
        NONE,
        /** Install {@link #getRecoveredMessages()} on the conversation and retry the LLM call once. */
        RETRY
    }

    private final Action action;
    private final List<Message> recoveredMessages;
    private final String reason;

    private PromptSizeRecoveryDecision(Action action, List<Message> recoveredMessages, String reason) {
        this.action = Objects.requireNonNull(action, "action cannot be null");
        this.recoveredMessages = recoveredMessages;
        this.reason = Objects.requireNonNull(reason, "reason cannot be null");
    }

    /**
     * Builds a {@link Action#RETRY} decision carrying the recovered (shortened) message list.
     *
     * @param recoveredMessages
     *            the new message list to install before retrying (must not be null; defensively copied)
     * @param reason
     *            short human-readable explanation logged at WARN (must not be null)
     */
    public static PromptSizeRecoveryDecision retry(List<Message> recoveredMessages, String reason) {
        Objects.requireNonNull(recoveredMessages, "recoveredMessages cannot be null");
        for (Message m : recoveredMessages) {
            Objects.requireNonNull(m, "recoveredMessages must not contain null elements");
        }
        return new PromptSizeRecoveryDecision(Action.RETRY, List.copyOf(recoveredMessages), reason);
    }

    /** Builds a {@link Action#NONE} decision. The caller will rethrow the original exception. */
    public static PromptSizeRecoveryDecision none(String reason) {
        return new PromptSizeRecoveryDecision(Action.NONE, null, reason);
    }

    public Action getAction() {
        return action;
    }

    /**
     * The message list that should replace the conversation before retrying. Present only when
     * {@link #getAction()} is {@link Action#RETRY}.
     */
    public Optional<List<Message>> getRecoveredMessages() {
        return Optional.ofNullable(recoveredMessages);
    }

    public String getReason() {
        return reason;
    }
}
