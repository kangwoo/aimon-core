package at.aimon.core.agent.session.inbox;

import java.util.List;
import java.util.Objects;

/**
 * What one {@code SessionInbox.collect} took out of the backend: the entries it could rebuild, and the entries it
 * could not.
 *
 * <p>
 * The second list exists because the two are not the same question. Every backend removes an entry from storage
 * before its codec runs, so an entry that fails to decode is gone either way — returning fewer messages and saying
 * nothing would make that loss silent, which is the failure this seam was opened to remove. The design is
 * <a href="../../../../../../../../../docs/design/session/inbox-collect-durability.md">
 * inbox-collect-durability.md</a>.
 *
 * <p>
 * A batch with nothing in {@link #getMessages()} and one entry in {@link #getUnreadable()} is the ordinary shape
 * rather than an edge case — one queued message that cannot be read produces exactly it — so a caller must not
 * treat an empty message list as "nothing happened".
 *
 * <p>
 * Immutable value object.
 */
public final class CollectedBatch {

    private static final CollectedBatch EMPTY = new CollectedBatch(List.of(), List.of());

    private final List<InboundMessage> messages;
    private final List<UnreadableEntry> unreadable;

    private CollectedBatch(List<InboundMessage> messages, List<UnreadableEntry> unreadable) {
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        this.unreadable = List.copyOf(Objects.requireNonNull(unreadable, "unreadable must not be null"));
    }

    /**
     * A static factory rather than the builder {@code .claude/rules/immutability-pattern.md} asks for, and the
     * reason is the one {@code SessionBackend} gives for the same choice: both fields are required, so a builder
     * would move the check from the compiler to {@code build()} and add nothing else.
     *
     * @param messages
     *            the entries that decoded, in priority-then-FIFO order (must not be null)
     * @param unreadable
     *            the entries that were removed but could not be decoded (must not be null)
     * @return the batch
     */
    public static CollectedBatch of(List<InboundMessage> messages, List<UnreadableEntry> unreadable) {
        return new CollectedBatch(messages, unreadable);
    }

    /**
     * The common answer: everything decoded.
     *
     * @param messages
     *            the entries that decoded (must not be null)
     * @return the batch, with no unreadable entries
     */
    public static CollectedBatch ofMessages(List<InboundMessage> messages) {
        return new CollectedBatch(messages, List.of());
    }

    /**
     * @return a batch with nothing in it
     */
    public static CollectedBatch empty() {
        return EMPTY;
    }

    /**
     * @return the decoded entries, in priority-then-FIFO order (never null, may be empty)
     */
    public List<InboundMessage> getMessages() {
        return messages;
    }

    /**
     * @return the entries removed from the backend that this build could not decode (never null, may be empty)
     */
    public List<UnreadableEntry> getUnreadable() {
        return unreadable;
    }

    /**
     * @return {@code true} when the call removed nothing at all — neither readable nor unreadable
     */
    public boolean isEmpty() {
        return messages.isEmpty() && unreadable.isEmpty();
    }

    @Override
    public String toString() {
        return "CollectedBatch{messages=" + messages.size() + ", unreadable=" + unreadable.size() + "}";
    }
}
