package at.aimon.core.agent.session.inbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;

/**
 * Single-process {@link SessionInbox} backed by per-session lock-protected lists.
 *
 * <p>
 * Implementation goal is correctness over throughput: each {@code deliver}/{@code collect} acquires the
 * session's monitor so priority-then-FIFO ordering and atomic batch removal are trivially preserved. Sequence
 * numbers issued from a single {@link AtomicLong} provide stable FIFO ordering across deliveries.
 */
public final class InMemorySessionInbox implements SessionInbox {

    private final ConcurrentMap<SessionId, List<Stored>> inboxes = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public InboundMessageId deliver(InboundMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        final InboundMessageId issuedId = InboundMessageId.of(UUID.randomUUID().toString());
        final InboundMessage stamped = rebuildWithId(message, issuedId);
        final long seq = sequence.incrementAndGet();
        final List<Stored> bucket = inboxes.computeIfAbsent(stamped.getSessionId(), k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.add(new Stored(seq, stamped));
        }
        return issuedId;
    }

    @Override
    public List<InboundMessage> collect(SessionId id, QueuedInputPriority maxPriority) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(maxPriority, "maxPriority must not be null");
        final List<Stored> bucket = inboxes.get(id);
        if (bucket == null) {
            return List.of();
        }
        final List<InboundMessage> collected = new ArrayList<>();
        synchronized (bucket) {
            final List<Stored> kept = new ArrayList<>();
            final List<Stored> taken = new ArrayList<>();
            for (Stored s : bucket) {
                if (s.message.getPriority().ordinal() <= maxPriority.ordinal()) {
                    taken.add(s);
                } else {
                    kept.add(s);
                }
            }
            taken.sort(Comparator.<Stored, Integer>comparing(s -> s.message.getPriority().ordinal())
                    .thenComparingLong(s -> s.sequence));
            for (Stored s : taken) {
                collected.add(s.message);
            }
            bucket.clear();
            bucket.addAll(kept);
        }
        return collected;
    }

    @Override
    public boolean isEmpty(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        final List<Stored> bucket = inboxes.get(id);
        if (bucket == null) {
            return true;
        }
        synchronized (bucket) {
            return bucket.isEmpty();
        }
    }

    @Override
    public void purge(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");
        inboxes.remove(id);
    }

    private static InboundMessage rebuildWithId(InboundMessage src, InboundMessageId id) {
        InboundMessage.Builder b = InboundMessage.builder().id(id).sessionId(src.getSessionId())
                .agentRef(src.getAgentRef()).userInput(src.getUserInput()).priority(src.getPriority())
                .initiator(src.getInitiator()).deliveredAt(src.getDeliveredAt()).metadata(src.getMetadata())
                .submitOptions(src.getSubmitOptions());
        src.getIdempotencyKey().ifPresent(b::idempotencyKey);
        src.getTurnId().ifPresent(b::turnId);
        src.getContextDiscriminator().ifPresent(b::contextDiscriminator);
        return b.build();
    }

    private static final class Stored {
        final long sequence;
        final InboundMessage message;

        Stored(long sequence, InboundMessage message) {
            this.sequence = sequence;
            this.message = message;
        }
    }
}
