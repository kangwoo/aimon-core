package at.aimon.session.redis;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.session.testkit.AbstractSessionInboxDurabilityContractTest;
import io.lettuce.core.api.StatefulRedisConnection;

/** The shared inbox durability contract, against a real Redis. */
@DisplayName("RedisSessionInbox — batch durability contract")
@Tag("docker")
class RedisSessionInboxDurabilityContractTest extends AbstractSessionInboxDurabilityContractTest {

    private StatefulRedisConnection<String, String> connection;
    private RedisSessionInbox inbox;

    @BeforeEach
    void wire() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        inbox = new RedisSessionInbox(connection);
    }

    @AfterEach
    void close() {
        if (connection != null) {
            connection.close();
        }
    }

    @Override
    protected SessionInbox inbox() {
        return inbox;
    }

    @Override
    protected InboundMessageId plantUnreadableEntry(SessionId id, QueuedInputPriority tier, String turnId,
            String idempotencyKey) {
        // "ROBOT" is not a Principal.Type, so decodePrincipal's valueOf throws — a plain IllegalArgumentException
        // from a value object rather than anything this codec declares, which is the shape §1 of the design measured.
        final StringBuilder json = new StringBuilder();
        json.append("{\"conversationId\":\"").append(id.value()).append("\",");
        json.append("\"agentRef\":\"agent-x\",\"userInput\":\"unreadable\",");
        json.append("\"priority\":\"").append(tier.name()).append("\",");
        if (turnId != null) {
            json.append("\"turnId\":\"").append(turnId).append("\",");
        }
        if (idempotencyKey != null) {
            json.append("\"idempotencyKey\":\"").append(idempotencyKey).append("\",");
        }
        json.append("\"initiator\":{\"type\":\"ROBOT\",\"id\":\"u-9\",\"displayName\":\"bad\"},");
        json.append("\"deliveredAt\":\"2026-04-27T10:00:00Z\",\"metadata\":{}}");
        final String entryId = connection.sync().xadd(streamKey(id, tier), Map.of("p", json.toString()));
        return InboundMessageId.of(entryId);
    }

    @Override
    protected long countStored(SessionId id) {
        long total = 0L;
        for (QueuedInputPriority tier : QueuedInputPriority.values()) {
            total += connection.sync().xlen(streamKey(id, tier));
        }
        return total;
    }

    private static String streamKey(SessionId id, QueuedInputPriority tier) {
        return RedisSessionInbox.DEFAULT_KEY_PREFIX + ":" + id.value() + ":" + tier.name();
    }
}
