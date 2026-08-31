package at.aimon.core.memory.redaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import at.aimon.core.llm.Message;

/**
 * Applies a {@link RedactionPolicy} to every text fragment of a {@link Message}, regardless of role.
 *
 * <p>
 * This is the single, shared redaction gate used by every {@code DerivationQueueManager}
 * implementation so the secret/PII masking contract cannot diverge between the in-memory and
 * persistent queues. It redacts:
 *
 * <ul>
 * <li>the text of each {@code TextContentBlock} (USER / ASSISTANT, including the text portion of
 * multimodal messages — non-text blocks such as images are preserved verbatim), and</li>
 * <li>the content of each {@code ToolUseResult} carried by a {@code TOOL}-role message — the place
 * where {@code kubectl}/{@code curl}/log output (AWS keys, bearer tokens, ...) actually lands and
 * which the earlier per-manager logic skipped because such messages have empty
 * {@code getContent()}.</li>
 * </ul>
 *
 * <p>
 * Stateless and thread-safe provided the injected {@link RedactionPolicy} is (the default policies
 * are). Redaction is idempotent, so re-running the gate on already-redacted content is a no-op.
 */
public final class MessageRedactor {

    private final RedactionPolicy redactionPolicy;

    /**
     * @param redactionPolicy
     *            the policy applied to every text fragment (must not be null)
     */
    public MessageRedactor(RedactionPolicy redactionPolicy) {
        this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy cannot be null");
    }

    /**
     * Redacts every message in {@code messages}, returning a new list. Unmodified messages are
     * carried through by identity.
     */
    public List<Message> redactAll(List<Message> messages) {
        Objects.requireNonNull(messages, "messages cannot be null");
        List<Message> out = new ArrayList<>(messages.size());
        for (Message message : messages) {
            out.add(redact(message));
        }
        return out;
    }

    /**
     * Returns a redacted copy of {@code message}, or the original instance if the policy made no
     * change to any of its text fragments.
     */
    public Message redact(Message message) {
        Objects.requireNonNull(message, "message cannot be null");
        AtomicBoolean modified = new AtomicBoolean(false);
        Message redacted = message.mapText(text -> {
            if (text.isEmpty()) {
                return text;
            }
            RedactionResult result = redactionPolicy.redact(text);
            if (result.isModified()) {
                modified.set(true);
            }
            return result.getRedactedContent();
        });
        return modified.get() ? redacted : message;
    }
}
