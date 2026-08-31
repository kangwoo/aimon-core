package at.aimon.core.agent.compact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.exception.LlmPromptTooLongException;

/**
 * Conservative {@link PromptSizeRecoveryStrategy} that drops the oldest user message that is safe to remove.
 *
 * <p>
 * Algorithm: scan the message list from the front and pick the first {@link Role#USER} message that is not (a) a
 * compaction boundary or summary marker (per {@link CompactBoundary}), and not (b) the most recent user message in the
 * conversation. Return {@link PromptSizeRecoveryDecision#retry(List, String)} with that message removed; otherwise
 * return {@link PromptSizeRecoveryDecision#none(String)}.
 *
 * <p>
 * Why only user messages? Dropping an assistant turn that contains a {@code tool_use} would orphan the matching
 * {@code tool_result} (and vice versa), and providers reject such conversations. Restricting removal to user messages
 * preserves all tool pairing invariants without inspecting tool IDs across messages.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class DefaultPromptSizeRecoveryStrategy implements PromptSizeRecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptSizeRecoveryStrategy.class);

    @Override
    public PromptSizeRecoveryDecision recover(List<Message> messages, LlmPromptTooLongException error) {
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(error, "error cannot be null");

        if (messages.isEmpty()) {
            return PromptSizeRecoveryDecision.none("conversation is empty");
        }

        final int lastUserIndex = lastIndexOfRole(messages, Role.USER);
        for (int i = 0; i < messages.size(); i++) {
            if (i == lastUserIndex) {
                continue;
            }
            final Message m = messages.get(i);
            if (m.getRole() != Role.USER) {
                continue;
            }
            if (isCompactMarker(m)) {
                continue;
            }
            final List<Message> shortened = new ArrayList<>(messages.size() - 1);
            for (int j = 0; j < messages.size(); j++) {
                if (j != i) {
                    shortened.add(messages.get(j));
                }
            }
            log.warn(
                    "Prompt-too-long recovery: dropped user message at index {} (size {}→{}, requestedTokens={}, "
                            + "modelLimitTokens={})",
                    i, messages.size(), shortened.size(), error.getRequestedTokens().orElse(null),
                    error.getModelLimitTokens().orElse(null));
            return PromptSizeRecoveryDecision.retry(shortened, "dropped oldest user message at index " + i);
        }

        log.warn("Prompt-too-long recovery: no safe message to drop (size={}); propagating exception", messages.size());
        return PromptSizeRecoveryDecision.none("no safe user message available to drop");
    }

    private static int lastIndexOfRole(List<Message> messages, Role role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == role) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isCompactMarker(Message m) {
        final String text = m.getContent();
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX)
                || text.startsWith(CompactBoundary.SUMMARY_OPEN_PREFIX);
    }
}
