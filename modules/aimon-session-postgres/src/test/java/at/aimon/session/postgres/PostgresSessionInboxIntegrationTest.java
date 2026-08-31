package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessage;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.base.Principal;

/**
 * Integration tests for {@link PostgresSessionInbox} against a real Postgres container.
 */
@DisplayName("PostgresSessionInbox integration")
@Tag("docker")
class PostgresSessionInboxIntegrationTest {

    private PostgresSessionInbox inbox;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        inbox = new PostgresSessionInbox(PostgresTestSupport.dataSource());
    }

    @Test
    @DisplayName("deliver returns the row id and round-trips the envelope through collect")
    void deliverRoundTrip() {
        final SessionId id = SessionId.of("c-inbox-1");
        final InboundMessageId returnedId = inbox.deliver(message(id, QueuedInputPriority.NEXT, "hello"));
        assertThat(Long.parseLong(returnedId.value())).as("row id is a positive bigint").isPositive();

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

    @Test
    @DisplayName("two concurrent collect calls cooperate via FOR UPDATE SKIP LOCKED — no duplicates, no losses")
    void concurrentCollectIsDisjoint() throws Exception {
        final SessionId id = SessionId.of("c-inbox-7");
        for (int i = 0; i < 20; i++) {
            inbox.deliver(message(id, QueuedInputPriority.NEXT, "msg-" + i));
        }
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final PostgresSessionInbox a = new PostgresSessionInbox(PostgresTestSupport.dataSource());
            final PostgresSessionInbox b = new PostgresSessionInbox(PostgresTestSupport.dataSource());
            final Future<List<InboundMessage>> fa = pool.submit(() -> a.collect(id, QueuedInputPriority.LATER));
            final Future<List<InboundMessage>> fb = pool.submit(() -> b.collect(id, QueuedInputPriority.LATER));
            final List<InboundMessage> ra = fa.get(5, TimeUnit.SECONDS);
            final List<InboundMessage> rb = fb.get(5, TimeUnit.SECONDS);
            assertThat(ra.size() + rb.size()).isEqualTo(20);
            assertThat(ra).noneMatch(m -> rb.stream().anyMatch(o -> o.getId().equals(m.getId())));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("collect honors a 1024-row limit (defense in depth — typical drains return single digits)")
    void drainBoundedByLimit() {
        final SessionId id = SessionId.of("c-inbox-8");
        final PostgresSessionInbox boundedInbox = new PostgresSessionInbox(PostgresTestSupport.dataSource(),
                new com.fasterxml.jackson.databind.ObjectMapper(), 5);
        for (int i = 0; i < 12; i++) {
            boundedInbox.deliver(message(id, QueuedInputPriority.NEXT, "m-" + i));
        }
        assertThat(boundedInbox.collect(id, QueuedInputPriority.LATER)).hasSize(5);
        assertThat(boundedInbox.collect(id, QueuedInputPriority.LATER)).hasSize(5);
        assertThat(boundedInbox.collect(id, QueuedInputPriority.LATER)).hasSize(2);
    }

    private static InboundMessage message(SessionId id, QueuedInputPriority priority, String text) {
        return InboundMessage.builder().sessionId(id).agentRef("agent-x").userInput(text).priority(priority)
                .initiator(Principal.user("u-1")).deliveredAt(Instant.parse("2025-01-01T00:00:00Z"))
                .metadata(Map.of("k", "v")).build();
    }
}
