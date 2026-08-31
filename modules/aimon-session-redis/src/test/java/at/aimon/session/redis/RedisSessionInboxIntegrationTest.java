package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Integration tests for {@link RedisSessionInbox} against a real Redis container.
 */
@DisplayName("RedisSessionInbox integration")
@Tag("docker")
class RedisSessionInboxIntegrationTest {

    private StatefulRedisConnection<String, String> connection;
    private RedisSessionInbox inbox;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        inbox = new RedisSessionInbox(connection);
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("deliver returns the Redis Stream entry id and round-trips the envelope through collect")
    void deliverRoundTrip() {
        final SessionId id = SessionId.of("c-inbox-1");
        final InboundMessageId returnedId = inbox.deliver(message(id, QueuedInputPriority.NEXT, "hello"));
        assertThat(returnedId.value()).contains("-").as("Stream entry id should be in Redis 'ms-seq' format");

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER);
        assertThat(collected).hasSize(1);
        final InboundMessage got = collected.get(0);
        assertThat(got.getId()).hasValue(returnedId);
        assertThat(got.getUserInput()).isEqualTo("hello");
        assertThat(got.getAgentRef()).isEqualTo("agent-x");
        assertThat(got.getPriority()).isEqualTo(QueuedInputPriority.NEXT);
        assertThat(got.getInitiator().getId()).isEqualTo("u-1");
        assertThat(got.getMetadata()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("collect surfaces messages priority-then-FIFO across tiers")
    void priorityOrdering() {
        final SessionId id = SessionId.of("c-inbox-2");
        // Insert in mixed order; tiers should still come out NOW → NEXT → LATER, FIFO within a tier.
        inbox.deliver(message(id, QueuedInputPriority.LATER, "later-1"));
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-1"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-1"));
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-2"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-2"));

        final List<InboundMessage> collected = inbox.collect(id, QueuedInputPriority.LATER);
        assertThat(collected).extracting(InboundMessage::getUserInput).containsExactly("now-1", "now-2", "next-1",
                "next-2", "later-1");
    }

    @Test
    @DisplayName("collect with maxPriority NOW skips lower tiers and leaves them in the inbox")
    void collectFiltersByMaxPriority() {
        final SessionId id = SessionId.of("c-inbox-3");
        inbox.deliver(message(id, QueuedInputPriority.NOW, "now-1"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "next-1"));
        inbox.deliver(message(id, QueuedInputPriority.LATER, "later-1"));

        final List<InboundMessage> nowOnly = inbox.collect(id, QueuedInputPriority.NOW);
        assertThat(nowOnly).extracting(InboundMessage::getUserInput).containsExactly("now-1");

        final List<InboundMessage> rest = inbox.collect(id, QueuedInputPriority.LATER);
        assertThat(rest).extracting(InboundMessage::getUserInput).containsExactly("next-1", "later-1");
    }

    @Test
    @DisplayName("collect atomically removes returned entries — second collect on the same tier sees none")
    void collectRemovesEntries() {
        final SessionId id = SessionId.of("c-inbox-4");
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "x"));
        inbox.deliver(message(id, QueuedInputPriority.NEXT, "y"));

        assertThat(inbox.collect(id, QueuedInputPriority.LATER)).hasSize(2);
        assertThat(inbox.collect(id, QueuedInputPriority.LATER)).isEmpty();
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
        assertThat(inbox.collect(id, QueuedInputPriority.LATER)).isEmpty();
    }

    private static InboundMessage message(SessionId id, QueuedInputPriority priority, String text) {
        return InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput(text).priority(priority)
                .initiator(Principal.user("u-1")).deliveredAt(Instant.parse("2025-01-01T00:00:00Z"))
                .metadata(Map.of("k", "v")).build();
    }
}
