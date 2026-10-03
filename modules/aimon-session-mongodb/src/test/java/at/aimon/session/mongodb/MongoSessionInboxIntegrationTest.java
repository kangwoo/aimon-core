package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.CollectedBatch;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * Integration tests for {@link MongoSessionInbox} against a real MongoDB replica-set container.
 */
@DisplayName("MongoSessionInbox integration")
@Tag("docker")
class MongoSessionInboxIntegrationTest {

    private MongoSessionInbox inbox;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        inbox = new MongoSessionInbox(MongoTestSupport.sharedDatabase(), DocumentKeys.COLL_INBOX);
    }

    @Test
    @DisplayName("deliver returns the document _id and round-trips the envelope through collect")
    void deliverRoundTrip() {
        final SessionId id = SessionId.of("c-inbox-1");
        final InboundMessageId returnedId = inbox.deliver(message(id, QueuedInputPriority.NEXT, "hello"));
        assertThat(returnedId.value()).hasSize(24).as("ObjectId hex string");

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER).getMessages();
        assertThat(collected).hasSize(1);
        final InboundMessage got = collected.get(0);
        assertThat(got.getId()).hasValue(returnedId);
        assertThat(got.getUserInput().asText()).isEqualTo("hello");
        assertThat(got.getAgentRef()).isEqualTo("agent-x");
        assertThat(got.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(got.getInitiator().getId()).isEqualTo("u-1");
        assertThat(got.getMetadata()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("collect surfaces messages priority-then-FIFO across tiers")
    void priorityOrdering() throws InterruptedException {
        final SessionId id = SessionId.of("c-inbox-2");
        // Insert in mixed order; tiers should still come out NOW → NEXT → LATER, FIFO within a tier.
        // Sleep 1ms between inserts so the deliveredAt millisecond ordering is deterministic — Mongo's Date precision
        // is only milliseconds so back-to-back inserts can collide.
        inbox.deliver(message(id, QueuedInputPriority.LATER, "later-1"));
        Thread.sleep(2);
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-1"));
        Thread.sleep(2);
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-1"));
        Thread.sleep(2);
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-2"));
        Thread.sleep(2);
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-2"));

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER).getMessages();
        assertThat(collected).extracting(m -> m.getUserInput().asText()).containsExactly("now-1", "now-2", "next-1",
                "next-2", "later-1");
    }

    @Test
    @DisplayName("collect with maxPriority NOW skips lower tiers and leaves them in the inbox")
    void collectFiltersByMaxPriority() {
        final SessionId id = SessionId.of("c-inbox-3");
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-1"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-1"));
        inbox.deliver(message(id, QueuedInputPriority.LATER, "later-1"));

        final List<InboundMessage> nowOnly = inbox.collect(id, QueuedInputPriority.NOW).getMessages();
        assertThat(nowOnly).extracting(m -> m.getUserInput().asText()).containsExactly("now-1");

        final List<InboundMessage> rest = inbox.collect(id, QueuedInputPriority.LATER).getMessages();
        assertThat(rest).extracting(m -> m.getUserInput().asText()).containsExactly("next-1", "later-1");
    }

    @Test
    @DisplayName("collect atomically removes returned entries — second collect on the same tier sees none")
    void collectRemovesEntries() {
        final SessionId id = SessionId.of("c-inbox-4");
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "x"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "y"));

        assertThat(inbox.collect(id, QueuedInputPriority.LATER).getMessages()).hasSize(2);
        assertThat(inbox.collect(id, QueuedInputPriority.LATER).getMessages()).isEmpty();
        assertThat(inbox.isEmpty(id)).isTrue();
    }

    @Test
    @DisplayName("isEmpty reflects across all priority tiers")
    void isEmptyAcrossTiers() {
        final SessionId id = SessionId.of("c-inbox-5");
        assertThat(inbox.isEmpty(id)).isTrue();

        inbox.deliver(message(id, QueuedInputPriority.LATER, "only-later"));
        assertThat(inbox.isEmpty(id)).isFalse();
    }

    @Test
    @DisplayName("purge clears every tier for the conversation")
    void purgeClearsAllTiers() {
        final SessionId id = SessionId.of("c-inbox-6");
        inbox.deliver(message(id, QueuedInputPriority.NOW, "n"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "x"));
        inbox.deliver(message(id, QueuedInputPriority.LATER, "l"));

        inbox.purge(id);
        assertThat(inbox.isEmpty(id)).isTrue();
        assertThat(inbox.collect(id, QueuedInputPriority.LATER).getMessages()).isEmpty();
    }

    @Test
    @DisplayName("an envelope with no metadata and default SubmitOptions round-trips exactly")
    void emptyMetadataAndDefaultOptionsRoundTrip() {
        // The shape a forwarding router delivers: it never sets metadata, so the codec writes an empty subdocument,
        // which a pipeline $set on MongoDB 6 rejects unless the payload is wrapped in $literal.
        final SessionId id = SessionId.of("c-inbox-literal-1");
        final InboundMessage sent = InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput("hello")
                .priority(QueuedInputPriority.NEXT).initiator(Principal.user("u-1"))
                .deliveredAt(Instant.parse("2025-01-01T00:00:00Z")).build();
        final InboundMessageId returnedId = inbox.deliver(sent);

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER).getMessages();
        assertThat(collected).hasSize(1);
        final InboundMessage got = collected.get(0);
        assertThat(got.getId()).hasValue(returnedId);
        assertThat(got.getSessionId()).isEqualTo(id);
        assertThat(got.getAgentRef()).isEqualTo("agent-x");
        assertThat(got.getUserInput().asText()).isEqualTo("hello");
        assertThat(got.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(got.getInitiator()).isEqualTo(Principal.user("u-1"));
        assertThat(got.getDeliveredAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(got.getMetadata()).isEmpty();
        assertThat(got.getSubmitOptions()).isEqualTo(SubmitOptions.empty());
        assertThat(got.getTurnId()).isEmpty();
        assertThat(got.getIdempotencyKey()).isEmpty();
        assertThat(got.getContextDiscriminator()).isEmpty();
    }

    @Test
    @DisplayName("a nested empty map inside execution attributes is stored as data")
    void nestedEmptyExecutionAttributeRoundTrips() {
        final SessionId id = SessionId.of("c-inbox-literal-2");
        final SubmitOptions options = SubmitOptions.builder()
                .executionAttributes(Map.of("empty", Map.of(), "nested", Map.of("inner", Map.of()))).build();
        inbox.deliver(InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput("hello")
                .priority(QueuedInputPriority.NEXT).initiator(Principal.user("u-1"))
                .deliveredAt(Instant.parse("2025-01-01T00:00:00Z")).submitOptions(options).build());

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER).getMessages();
        assertThat(collected).hasSize(1);
        assertThat(collected.get(0).getSubmitOptions().getExecutionAttributes())
                .isEqualTo(Map.of("empty", Map.of(), "nested", Map.of("inner", Map.of())));
    }

    @Test
    @DisplayName("strings beginning with $ are stored as data, not read as field paths or variables")
    void dollarPrefixedStringsRoundTrip() {
        // Without $literal, "$priority" resolves to the document's own priority field and "$$NOW" to the server
        // clock; the stored entry then fails to decode and its turn is dropped after findOneAndDelete removed it.
        final SessionId id = SessionId.of("c-inbox-literal-3");
        final SubmitOptions options = SubmitOptions.builder().executionAttribute("path", "$HOME")
                .systemPromptVariables(Map.of("var", "$$NOW")).build();
        inbox.deliver(InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput("$priority")
                .priority(QueuedInputPriority.NEXT).initiator(Principal.user("u-1"))
                .deliveredAt(Instant.parse("2025-01-01T00:00:00Z")).metadata(Map.of("k", "$conversationId"))
                .idempotencyKey("$idem").submitOptions(options).build());

        final CollectedBatch batch = inbox.collect(id, QueuedInputPriority.LATER);
        assertThat(batch.getUnreadable()).isEmpty();
        assertThat(batch.getMessages()).hasSize(1);
        final InboundMessage got = batch.getMessages().get(0);
        assertThat(got.getUserInput().asText()).isEqualTo("$priority");
        assertThat(got.getMetadata()).containsExactlyEntriesOf(Map.of("k", "$conversationId"));
        assertThat(got.getIdempotencyKey()).hasValue("$idem");
        assertThat(got.getSubmitOptions().getExecutionAttributes()).containsExactlyEntriesOf(Map.of("path", "$HOME"));
        assertThat(got.getSubmitOptions().getSystemPromptVariables()).containsExactlyEntriesOf(Map.of("var", "$$NOW"));
    }

    private static InboundMessage message(SessionId id, QueuedInputPriority priority, String text) {
        return InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput(text).priority(priority)
                .initiator(Principal.user("u-1")).deliveredAt(Instant.parse("2025-01-01T00:00:00Z"))
                .metadata(Map.of("k", "v")).build();
    }
}
