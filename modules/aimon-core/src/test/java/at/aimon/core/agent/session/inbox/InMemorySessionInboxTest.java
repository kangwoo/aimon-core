package at.aimon.core.agent.session.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.base.Principal;

/**
 * Field-preservation contract for the in-process inbox.
 *
 * <p>
 * {@code deliver} does not store the caller's message — it rebuilds it around the id the inbox issues, copying field by
 * field. That hand-written copy is the one place in the inbox family where adding a field to {@link InboundMessage} and
 * forgetting the inbox compiles cleanly and silently drops the value on delivery. The remote codecs at least fail their
 * own round-trip tests; this one has to be pinned here.
 */
@DisplayName("InMemorySessionInbox preserves every delivered field")
class InMemorySessionInboxTest {

    private static final SessionId CONV = SessionId.of("c-inbox");

    private final InMemorySessionInbox inbox = new InMemorySessionInbox();

    @Test
    @DisplayName("a delivered message keeps its turn id through the id rebuild")
    void turnIdSurvivesDelivery() {
        inbox.deliver(baseMessage().turnId(TurnId.of("turn-42")).build());

        final List<InboundMessage> collected = inbox.collect(CONV, QueuedInputPriority.NEXT);

        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getTurnId()).contains(TurnId.of("turn-42"));
        // The issued id is the inbox's own; the turn id is the submitter's and must not be overwritten by it.
        assertThat(collected.get(0).getId()).isPresent();
    }

    @Test
    @DisplayName("a message delivered without a turn id stays without one")
    void absentTurnIdStaysAbsent() {
        inbox.deliver(baseMessage().build());

        assertThat(inbox.collect(CONV, QueuedInputPriority.NEXT).get(0).getTurnId()).isEmpty();
    }

    @Test
    @DisplayName("the rest of the envelope survives the rebuild too")
    void remainingFieldsSurviveDelivery() {
        final SubmitOptions options = SubmitOptions.builder().userContextInjection(true).build();
        inbox.deliver(baseMessage().turnId(TurnId.of("turn-7")).idempotencyKey("idem-1").submitOptions(options)
                .contextDiscriminator("tenant-a").metadata(Map.of("origin", "node-b")).build());

        final InboundMessage collected = inbox.collect(CONV, QueuedInputPriority.NEXT).get(0);

        assertThat(collected.getUserInput()).isEqualTo("hello");
        assertThat(collected.getAgentRef()).isEqualTo("agent-x");
        assertThat(collected.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(collected.getInitiator().getId()).isEqualTo("u-1");
        assertThat(collected.getDeliveredAt()).isEqualTo(Instant.parse("2026-04-27T10:00:00Z"));
        assertThat(collected.getIdempotencyKey()).contains("idem-1");
        assertThat(collected.getSubmitOptions()).isEqualTo(options);
        assertThat(collected.getMetadata()).containsEntry("origin", "node-b");
        assertThat(collected.getTurnId()).contains(TurnId.of("turn-7"));
        assertThat(collected.getContextDiscriminator()).contains("tenant-a");
    }

    @Test
    @DisplayName("a message delivered without a discriminator stays without one")
    void absentContextDiscriminatorStaysAbsent() {
        // Dropping a present discriminator opens the wrong runtime; inventing an absent one opens no runtime at all.
        inbox.deliver(baseMessage().build());

        assertThat(inbox.collect(CONV, QueuedInputPriority.NEXT).get(0).getContextDiscriminator()).isEmpty();
    }

    private InboundMessage.Builder baseMessage() {
        return InboundMessage.builder().sessionId(CONV).agentRef("agent-x").userInput("hello")
                .priority(QueuedInputPriority.NEXT)
                .initiator(Principal.builder().type(Principal.Type.USER).id("u-1").displayName("alice").build())
                .deliveredAt(Instant.parse("2026-04-27T10:00:00Z"));
    }
}
