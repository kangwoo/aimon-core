package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.inbox.InboundMessageId;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.session.testkit.AbstractSessionInboxDurabilityContractTest;

/** The shared inbox durability contract, against a real Postgres. */
@DisplayName("PostgresSessionInbox — batch durability contract")
@Tag("docker")
class PostgresSessionInboxDurabilityContractTest extends AbstractSessionInboxDurabilityContractTest {

    private static final String SQL_PLANT = "INSERT INTO conversation_inbox "
            + "(conversation_id, agent_ref, priority, payload, delivered_at) "
            + "VALUES (?, 'agent-x', ?, ?::jsonb, now()) RETURNING id";

    private static final String SQL_COUNT = "SELECT count(*) FROM conversation_inbox WHERE conversation_id = ?";

    private PostgresSessionInbox inbox;

    @BeforeEach
    void wire() {
        PostgresTestSupport.truncateAll();
        inbox = new PostgresSessionInbox(PostgresTestSupport.dataSource());
    }

    @Override
    protected SessionInbox inbox() {
        return inbox;
    }

    @Override
    protected InboundMessageId plantUnreadableEntry(SessionId id, QueuedInputPriority tier, String turnId,
            String idempotencyKey) {
        // "ROBOT" is not a Principal.Type, so decodePrincipal's valueOf throws — a plain IllegalArgumentException
        // from a value object rather than anything this codec declares.
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
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_PLANT)) {
            ps.setString(1, id.value());
            ps.setShort(2, (short) tier.ordinal());
            ps.setString(3, json.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return InboundMessageId.of(Long.toString(rs.getLong(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to plant an unreadable inbox row", e);
        }
    }

    @Override
    protected long countStored(SessionId id) {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_COUNT)) {
            ps.setString(1, id.value());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count inbox rows", e);
        }
    }
}
