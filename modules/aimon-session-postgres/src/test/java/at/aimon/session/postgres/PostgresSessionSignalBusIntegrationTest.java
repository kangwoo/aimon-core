package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionSignalBusException;
import at.aimon.core.agent.session.signal.SessionSignal;
import at.aimon.core.agent.session.signal.SessionSignal.SignalKind;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.session.postgres.internal.ListenDispatcher;

/**
 * Integration tests for {@link PostgresSessionSignalBus} against a real Postgres container.
 *
 * <p>
 * Two bus instances on different node ids exchange signals through the LISTEN/NOTIFY doorbell pattern; tests verify
 * cross-node delivery for each {@link SignalKind}, multi-handler fan-out, unsubscribe lifecycle, and large-payload
 * (> 8 KB) round-trips that exercise the row-table fetch path rather than the (size-limited) NOTIFY payload.
 */
@DisplayName("PostgresSessionSignalBus integration")
@Tag("docker")
class PostgresSessionSignalBusIntegrationTest {

    private HikariDataSource publishPoolA;
    private HikariDataSource fetchPoolA;
    private HikariDataSource publishPoolB;
    private HikariDataSource fetchPoolB;

    private PostgresSessionSignalBus busA;
    private PostgresSessionSignalBus busB;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        publishPoolA = PostgresTestSupport.isolatedDataSource(4);
        fetchPoolA = PostgresTestSupport.isolatedDataSource(2);
        publishPoolB = PostgresTestSupport.isolatedDataSource(4);
        fetchPoolB = PostgresTestSupport.isolatedDataSource(2);
        busA = new PostgresSessionSignalBus(publishPoolA, fetchPoolA, PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.listenConnectionProps(), "node-A");
        busB = new PostgresSessionSignalBus(publishPoolB, fetchPoolB, PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.listenConnectionProps(), "node-B");
    }

    @AfterEach
    void tearDown() {
        if (busA != null) {
            busA.close();
        }
        if (busB != null) {
            busB.close();
        }
        if (fetchPoolA != null) {
            fetchPoolA.close();
        }
        if (publishPoolA != null) {
            publishPoolA.close();
        }
        if (fetchPoolB != null) {
            fetchPoolB.close();
        }
        if (publishPoolB != null) {
            publishPoolB.close();
        }
    }

    @Test
    @DisplayName("INTERRUPT signal published from A reaches B's subscriber")
    void controlChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-1");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            // LISTEN registration completes synchronously inside subscribe(), but give the dispatcher's poll loop a
            // tiny window to enter getNotifications() before we publish — keeps timings stable on slow CI.
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A")
                    .payload(Map.of("reason", "USER_REQUEST")).build());

            final SessionSignal got = received.poll(3, TimeUnit.SECONDS);
            assertThat(got).as("subscriber should receive published signal").isNotNull();
            assertThat(got.getKind()).isEqualTo(SignalKind.INTERRUPT);
            assertThat(got.getOriginNodeId()).isEqualTo("node-A");
            assertThat(got.getPayload()).containsEntry("reason", "USER_REQUEST");
        }
    }

    @Test
    @DisplayName("EVENT signal published from A reaches B's subscriber via row-table fetch")
    void eventsChannelRoundTrip() throws Exception {
        final SessionId id = SessionId.of("c-bus-2");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(Map.of("type", "AssistantTextDelta", "delta", "hello", "chunkIndex", 0)).build());

            final SessionSignal got = received.poll(3, TimeUnit.SECONDS);
            assertThat(got).as("subscriber should receive EVENT").isNotNull();
            assertThat(got.getKind()).isEqualTo(SignalKind.EVENT);
            assertThat(got.getPayload()).containsEntry("type", "AssistantTextDelta").containsEntry("delta", "hello");
        }
    }

    @Test
    @DisplayName("publishAll delivers a full batch to another node's subscriber in list order, once, in one transaction")
    void publishAllKeepsListOrder() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch");
        final int count = PostgresSessionSignalBus.MAX_BATCH_ROWS;
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publishAll(deltas(id, count));

            assertThat(chunkIndexes(received, count)).isEqualTo(range(count));
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is published twice").isNull();
        }
        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isEqualTo(count);
        assertThat(queryLong("SELECT count(DISTINCT xmin::text) FROM conversation_signal"))
                .as("transactions the batch was inserted by").isEqualTo(1);
    }

    @Test
    @DisplayName("publishAll splits a list longer than one statement and still delivers it in list order")
    void publishAllSplitsAListLongerThanOneStatement() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-split");
        final int count = 2 * PostgresSessionSignalBus.MAX_BATCH_ROWS + 1;
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publishAll(deltas(id, count));

            assertThat(chunkIndexes(received, count)).isEqualTo(range(count));
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is published twice").isNull();
        }
        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isEqualTo(count);
        assertThat(queryLong("SELECT count(DISTINCT xmin::text) FROM conversation_signal"))
                .as("transactions the list was inserted by").isEqualTo(3);
    }

    @Test
    @DisplayName("publishAll of one signal delivers it, as publish does")
    void publishAllOfOneSignal() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-one");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publishAll(List.of(event(id, "only", "it's a \"quoted\" {body}, with \\ and a comma")));

            final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
            assertThat(got).isNotNull();
            assertThat(got.getPayload()).containsEntry("marker", "only").containsEntry("body",
                    "it's a \"quoted\" {body}, with \\ and a comma");
        }
        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isEqualTo(1);
    }

    @Test
    @DisplayName("publishAll routes a list that mixes sessions to each session's subscriber, in list order")
    void publishAllOfMixedSessions() throws Exception {
        final SessionId one = SessionId.of("c-bus-batch-mixed-1");
        final SessionId two = SessionId.of("c-bus-batch-mixed-2");
        final LinkedBlockingQueue<SessionSignal> forOne = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<SessionSignal> forTwo = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription subOne = busB.subscribe(one, forOne::offer);
                SessionSignalBus.Subscription subTwo = busB.subscribe(two, forTwo::offer)) {
            Thread.sleep(100);
            busA.publishAll(List.of(event(one, "1a", "ok"), event(two, "2a", "ok"), event(one, "1b", "ok"),
                    event(two, "2b", "ok")));

            assertThat(forOne.poll(5, TimeUnit.SECONDS).getPayload()).containsEntry("marker", "1a");
            assertThat(forOne.poll(5, TimeUnit.SECONDS).getPayload()).containsEntry("marker", "1b");
            assertThat(forTwo.poll(5, TimeUnit.SECONDS).getPayload()).containsEntry("marker", "2a");
            assertThat(forTwo.poll(5, TimeUnit.SECONDS).getPayload()).containsEntry("marker", "2b");
        }
    }

    @Test
    @DisplayName("publishAll throws straight away when no connection can be had")
    void publishAllWithoutAConnectionThrows() {
        publishPoolA.close();

        assertThatThrownBy(() -> busA.publishAll(deltas(SessionId.of("c-bus-batch-no-pool"), 3)))
                .isInstanceOf(SessionSignalBusException.class).hasMessageContaining("batch of 3");

        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isZero();
    }

    @Test
    @DisplayName("publishAll still delivers the signals around ones the server rejects, in order and once, and throws")
    void publishAllDeliversTheSignalsAroundOnesTheServerRejects() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-rejected");
        // jsonb has no representation for U+0000, so the server turns the row away (22P05) and with it the whole
        // statement — without saying which row it was.
        final String rejected = "nul\u0000";
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            final List<SessionSignal> batch = List.of(event(id, "a", "ok"), event(id, "bad-1", rejected),
                    event(id, "b", "ok"), event(id, "bad-2", rejected), event(id, "c", "ok"));

            assertThatThrownBy(() -> busA.publishAll(batch)).isInstanceOf(SessionSignalBusException.class).satisfies(
                    e -> assertThat(e.getCause().getSuppressed()).as("the second rejected signal").hasSize(1));

            final List<Object> markers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
                assertThat(got).as("signal %d that the server accepted", i).isNotNull();
                markers.add(got.getPayload().get("marker"));
            }
            assertThat(markers).containsExactly("a", "b", "c");
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is published twice").isNull();
        }
        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isEqualTo(3);
    }

    @Test
    @DisplayName("publishAll skips a signal whose payload cannot be encoded and keeps the rest in one transaction")
    void publishAllSkipsASignalThatCannotBeEncoded() throws Exception {
        final SessionId id = SessionId.of("c-bus-batch-unencodable");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            // A bean with no properties: Jackson refuses it, before anything is sent.
            final SessionSignal unencodable = SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT)
                    .originNodeId("node-A").payload(Map.of("marker", "poison", "body", new Object())).build();
            final List<SessionSignal> batch = List.of(event(id, "first", "ok"), unencodable, event(id, "last", "ok"));

            assertThatThrownBy(() -> busA.publishAll(batch)).isInstanceOf(SessionSignalBusException.class);

            final SessionSignal first = received.poll(5, TimeUnit.SECONDS);
            final SessionSignal last = received.poll(5, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            assertThat(last).as("the signal after the unencodable one").isNotNull();
            assertThat(first.getPayload()).containsEntry("marker", "first");
            assertThat(last.getPayload()).containsEntry("marker", "last");
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is published twice").isNull();
        }
        assertThat(queryLong("SELECT count(DISTINCT xmin::text) FROM conversation_signal"))
                .as("an unencodable signal does not cost the batch its single transaction").isEqualTo(1);
    }

    @Test
    @DisplayName("publishAll of an empty list is a no-op that takes no connection")
    void publishAllOfAnEmptyListIsANoOp() {
        // A closed pool refuses every checkout, so returning normally means none was asked for.
        publishPoolA.close();

        assertThatCode(() -> busA.publishAll(List.of())).doesNotThrowAnyException();

        assertThat(queryLong("SELECT count(*) FROM conversation_signal")).isZero();
    }

    @Test
    @DisplayName("a row whose transaction commits after a later id was delivered is still delivered")
    void aRowThatCommitsLateIsStillDelivered() throws Exception {
        final SessionId id = SessionId.of("c-bus-late-commit");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer);
                Connection slow = PostgresTestSupport.dataSource().getConnection()) {
            Thread.sleep(100);
            // A publisher that has taken its id and not committed yet.
            slow.setAutoCommit(false);
            try (PreparedStatement ps = slow.prepareStatement("INSERT INTO conversation_signal "
                    + "(conversation_id, kind, origin_node_id, payload) VALUES (?, 'EVENT', 'node-C', ?::jsonb)")) {
                ps.setString(1, id.value());
                ps.setString(2, "{\"marker\":\"late\"}");
                ps.executeUpdate();
            }

            // A second publisher takes the next id and commits first; node B's high-water mark moves past the first.
            busA.publish(event(id, "early", "ok"));
            final SessionSignal early = received.poll(5, TimeUnit.SECONDS);
            assertThat(early).isNotNull();
            assertThat(early.getPayload()).containsEntry("marker", "early");

            try (PreparedStatement notify = slow.prepareStatement("SELECT pg_notify(?, '0')")) {
                notify.setString(1, ListenDispatcher.CHANNEL);
                notify.execute();
            }
            slow.commit();

            final SessionSignal late = received.poll(5, TimeUnit.SECONDS);
            assertThat(late).as("the row that committed after a later id had been delivered").isNotNull();
            assertThat(late.getPayload()).containsEntry("marker", "late");
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is delivered twice").isNull();
        }
    }

    @Test
    @DisplayName("a batch that commits late is delivered whole, in order, with what its publisher sent after it")
    void aBatchThatCommitsLateIsDeliveredInOrder() throws Exception {
        final SessionId id = SessionId.of("c-bus-late-batch");
        final SessionId other = SessionId.of("c-bus-late-batch-other");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<SessionSignal> receivedOther = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer);
                SessionSignalBus.Subscription subOther = busB.subscribe(other, receivedOther::offer);
                Connection slow = PostgresTestSupport.dataSource().getConnection()) {
            Thread.sleep(100);
            slow.setAutoCommit(false);
            try (PreparedStatement ps = slow.prepareStatement(
                    "INSERT INTO conversation_signal " + "(conversation_id, kind, origin_node_id, payload) "
                            + "SELECT ?, 'EVENT', 'node-C', jsonb_build_object('chunkIndex', n) "
                            + "FROM generate_series(0, 99) AS n ORDER BY n")) {
                ps.setString(1, id.value());
                ps.executeUpdate();
            }

            // Another session's publisher overtakes the batch several times, over several fetch passes.
            for (int i = 0; i < 3; i++) {
                busA.publish(event(other, "overtaking-" + i, "ok"));
                assertThat(receivedOther.poll(5, TimeUnit.SECONDS)).isNotNull();
            }

            // The slow publisher commits, and its next publish is already committed by the time node B looks.
            slow.commit();
            final List<SessionSignal> next = new ArrayList<>();
            for (int i = 100; i < 110; i++) {
                next.add(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-C")
                        .payload(Map.of("chunkIndex", i)).build());
            }
            busA.publishAll(next);

            assertThat(chunkIndexes(received, 110)).isEqualTo(range(110));
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is delivered twice").isNull();
        }
    }

    @Test
    @DisplayName("a node that started on an empty table still recovers a row that commits late")
    void aNodeStartedOnAnEmptyTableRecoversALateRow() throws Exception {
        final SessionId id = SessionId.of("c-bus-late-fresh-node");
        // The table is empty but its sequence is not at the start: every row so far has been reaped. A node that
        // starts now seeds its mark at zero, and its first fetch steps over thousands of ids at once.
        try (Connection c = PostgresTestSupport.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("SELECT setval(pg_get_serial_sequence('conversation_signal', 'id'), 20000)");
        }
        final HikariDataSource publishPoolC = PostgresTestSupport.isolatedDataSource(2);
        final HikariDataSource fetchPoolC = PostgresTestSupport.isolatedDataSource(2);
        final PostgresSessionSignalBus busC = new PostgresSessionSignalBus(publishPoolC, fetchPoolC,
                PostgresTestSupport.jdbcUrl(), PostgresTestSupport.listenConnectionProps(), "node-fresh");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busC.subscribe(id, received::offer);
                Connection slow = PostgresTestSupport.dataSource().getConnection()) {
            Thread.sleep(100);
            slow.setAutoCommit(false);
            try (PreparedStatement ps = slow.prepareStatement("INSERT INTO conversation_signal "
                    + "(conversation_id, kind, origin_node_id, payload) VALUES (?, 'EVENT', 'node-C', ?::jsonb)")) {
                ps.setString(1, id.value());
                ps.setString(2, "{\"marker\":\"late\"}");
                ps.executeUpdate();
            }
            busA.publish(event(id, "early", "ok"));
            final SessionSignal early = received.poll(5, TimeUnit.SECONDS);
            assertThat(early).isNotNull();
            assertThat(early.getPayload()).containsEntry("marker", "early");

            try (PreparedStatement notify = slow.prepareStatement("SELECT pg_notify(?, '0')")) {
                notify.setString(1, ListenDispatcher.CHANNEL);
                notify.execute();
            }
            slow.commit();

            final SessionSignal late = received.poll(5, TimeUnit.SECONDS);
            assertThat(late).as("the row just below the first id this node ever fetched").isNotNull();
            assertThat(late.getPayload()).containsEntry("marker", "late");
        } finally {
            busC.close();
            fetchPoolC.close();
            publishPoolC.close();
        }
    }

    @Test
    @DisplayName("ids a rolled-back transaction took are stepped over without delivering anything twice")
    void aRolledBackBatchLeavesNoTrace() throws Exception {
        final SessionId id = SessionId.of("c-bus-rolled-back");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer);
                Connection aborted = PostgresTestSupport.dataSource().getConnection()) {
            Thread.sleep(100);
            aborted.setAutoCommit(false);
            try (PreparedStatement ps = aborted.prepareStatement(
                    "INSERT INTO conversation_signal " + "(conversation_id, kind, origin_node_id, payload) "
                            + "SELECT ?, 'EVENT', 'node-C', '{}'::jsonb FROM generate_series(1, 50)")) {
                ps.setString(1, id.value());
                ps.executeUpdate();
            }
            aborted.rollback();

            busA.publishAll(List.of(event(id, "a", "ok"), event(id, "b", "ok")));
            busA.publish(event(id, "c", "ok"));

            final List<Object> markers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
                assertThat(got).isNotNull();
                markers.add(got.getPayload().get("marker"));
            }
            assertThat(markers).containsExactly("a", "b", "c");
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("nothing is delivered twice").isNull();
        }
    }

    private static List<SessionSignal> deltas(SessionId id, int count) {
        final List<SessionSignal> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(Map.of("type", "AssistantTextDelta", "delta", "d" + i, "chunkIndex", i)).build());
        }
        return batch;
    }

    private static List<Object> chunkIndexes(LinkedBlockingQueue<SessionSignal> received, int count)
            throws InterruptedException {
        final List<Object> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
            assertThat(got).as("signal %d of the batch", i).isNotNull();
            chunks.add(got.getPayload().get("chunkIndex"));
        }
        return chunks;
    }

    private static List<Object> range(int count) {
        final List<Object> expected = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            expected.add(i);
        }
        return expected;
    }

    private static SessionSignal event(SessionId id, String marker, String body) {
        return SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                .payload(Map.of("type", "ToolCallCompleted", "marker", marker, "body", body)).build();
    }

    private static long queryLong(String sql) {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    @Test
    @DisplayName("self-broadcast (origin == nodeId) is dropped by default")
    void selfBroadcastDropped() throws Exception {
        final SessionId id = SessionId.of("c-bus-self");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sub = busA.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(
                    SessionSignal.builder().sessionId(id).kind(SignalKind.INTERRUPT).originNodeId("node-A").build());
            assertThat(received.poll(500, TimeUnit.MILLISECONDS))
                    .as("self-broadcast must not be delivered to A's own subscriber").isNull();
        }
    }

    @Test
    @DisplayName("after subscription close, no further messages reach the handler")
    void unsubscribeStopsDelivery() throws Exception {
        final SessionId id = SessionId.of("c-bus-3");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        final SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer);
        Thread.sleep(100);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(3, TimeUnit.SECONDS)).isNotNull();

        sub.close();
        Thread.sleep(100);
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).as("must not deliver after unsubscribe").isNull();
    }

    @Test
    @DisplayName("two handlers on the same conversation both receive the signal")
    void multipleHandlersOnSameConversation() throws Exception {
        final SessionId id = SessionId.of("c-bus-4");
        final LinkedBlockingQueue<SessionSignal> a = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<SessionSignal> b = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription sa = busB.subscribe(id, a::offer);
                SessionSignalBus.Subscription sb = busB.subscribe(id, b::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.MESSAGE_ENQUEUED).originNodeId("node-A")
                    .build());
            assertThat(a.poll(3, TimeUnit.SECONDS)).isNotNull();
            assertThat(b.poll(3, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    @DisplayName("payloads larger than 8 KB transit safely (NOTIFY carries only the row id)")
    void largePayloadFlowsThroughRowTable() throws Exception {
        final SessionId id = SessionId.of("c-bus-large");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        // Build a ~12 KB payload — well above the Postgres 8 KB NOTIFY limit, so this only works because
        // we ferry the actual payload through the conversation_signal table and use NOTIFY as a doorbell.
        final char[] big = new char[12 * 1024];
        java.util.Arrays.fill(big, 'x');
        final Map<String, Object> payload = new HashMap<>();
        payload.put("blob", new String(big));
        payload.put("type", "AssistantTextDelta");

        try (SessionSignalBus.Subscription sub = busB.subscribe(id, received::offer)) {
            Thread.sleep(100);
            busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVENT).originNodeId("node-A")
                    .payload(payload).build());

            final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
            assertThat(got).as("large-payload signal should be delivered").isNotNull();
            assertThat((String) got.getPayload().get("blob")).hasSize(12 * 1024);
            assertThat(got.getPayload()).containsEntry("type", "AssistantTextDelta");
        }
    }

    @Test
    @DisplayName("sweepOlderThan reaps rows whose created_at is past the cutoff")
    void sweepOlderThanReapsOldRows() {
        final SessionId id = SessionId.of("c-bus-sweep");
        busA.publish(SessionSignal.builder().sessionId(id).kind(SignalKind.EVICT).originNodeId("node-A").build());
        // Cutoff far in the future — everything qualifies.
        final int deleted = busA.sweepOlderThan(java.time.Instant.now().plusSeconds(60));
        assertThat(deleted).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a handler that throws an Error does not end the listen thread")
    void handlerThrowingAnErrorDoesNotEndTheListenThread() throws Exception {
        // The listen thread is the only thing that delivers signals to this node, and nothing restarts it. A handler
        // on one session throwing an Error must not silence every other session.
        final SessionId failing = SessionId.of("c-bus-error");
        final SessionId healthy = SessionId.of("c-bus-healthy");
        final LinkedBlockingQueue<SessionSignal> received = new LinkedBlockingQueue<>();
        try (SessionSignalBus.Subscription bad = busB.subscribe(failing, signal -> {
            throw new AssertionError("an Error, not an Exception, from a handler");
        }); SessionSignalBus.Subscription good = busB.subscribe(healthy, received::offer)) {
            Thread.sleep(100);
            for (SignalKind kind : new SignalKind[]{SignalKind.INTERRUPT, SignalKind.EVENT}) {
                busA.publish(SessionSignal.builder().sessionId(failing).kind(kind).originNodeId("node-A")
                        .payload(Map.of("reason", "USER_REQUEST")).build());
            }
            Thread.sleep(300);

            for (SignalKind kind : new SignalKind[]{SignalKind.INTERRUPT, SignalKind.EVENT}) {
                busA.publish(SessionSignal.builder().sessionId(healthy).kind(kind).originNodeId("node-A")
                        .payload(Map.of("reason", "USER_REQUEST")).build());
                final SessionSignal got = received.poll(5, TimeUnit.SECONDS);
                assertThat(got).as("a %s for the healthy session after the other handler threw", kind).isNotNull();
            }
        }
    }
}
