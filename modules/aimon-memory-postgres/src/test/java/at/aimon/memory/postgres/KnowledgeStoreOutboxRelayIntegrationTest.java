package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.IndexResult;
import at.aimon.core.knowledge.IndexStatus;
import at.aimon.core.knowledge.KeywordKnowledgeStore;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SearchQuery;
import at.aimon.core.knowledge.SearchResult;
import at.aimon.core.knowledge.SimpleDocumentChunker;
import at.aimon.core.memory.index.KnowledgeStoreObservationIndex;

/**
 * End-to-end integration tests for {@link KnowledgeStoreOutboxRelay} backed by
 * the shared Postgres testcontainer.
 *
 * <p>
 * Each test seeds {@code mem_outbox} via plain JDBC — this module does not
 * depend on {@code PostgresObservationStore}, which is delivered by another
 * agent. The relay's responsibility ends at calling
 * {@link KnowledgeStore#reindex}, rebuilding the whole subject scope from the
 * currently-surviving observations on every dispatch (so a single UPSERT/DELETE
 * never wipes the subject's other observations); a recording fake captures those
 * calls and a real {@link KeywordKnowledgeStore} is used in happy-path tests to
 * prove the staging-VFS plumbing actually feeds the search index.
 */
@DisplayName("KnowledgeStoreOutboxRelay integration")
@Tag("docker")
class KnowledgeStoreOutboxRelayIntegrationTest {

    private static final String WORKSPACE = "ws-1";
    private static final String SUBJECT_KEY = "ws-1:USER:alice";

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
    }

    @Test
    @DisplayName("drainOnce processes UPSERT rows and deletes them on success")
    void drainProcessesUpsertAndDeletes() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "alice likes coffee");
        long id = insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "alice likes coffee");

        RecordingKnowledgeStore store = new RecordingKnowledgeStore();
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);

        DrainResult result = relay.drainOnce();

        assertThat(result.getProcessed()).isEqualTo(1);
        assertThat(result.getFailed()).isZero();
        assertThat(result.getPoisoned()).isZero();
        assertThat(store.reindexCalls).hasSize(1);
        ReindexCall call = store.reindexCalls.get(0);
        assertThat(call.scope.getAgentName()).isEqualTo(KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME);
        assertThat(call.scope.getContextId()).isEqualTo(SUBJECT_KEY);
        assertThat(call.stagedFiles).contains("/observations/" + SUBJECT_KEY + "/obs-1.txt");
        assertThat(rowExists(id)).isFalse();
    }

    @Test
    @DisplayName("drainOnce processes DELETE rows and reindexes empty scope")
    void drainProcessesDeleteAsEmptyReindex() throws Exception {
        seedWorkspace(WORKSPACE);
        // Note: DELETE rows do not require the observation to be present.
        long id = insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "DELETE", null);

        RecordingKnowledgeStore store = new RecordingKnowledgeStore();
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);

        DrainResult result = relay.drainOnce();

        assertThat(result.getProcessed()).isEqualTo(1);
        assertThat(store.reindexCalls).hasSize(1);
        assertThat(store.reindexCalls.get(0).stagedFiles).isEmpty();
        assertThat(rowExists(id)).isFalse();
    }

    @Test
    @DisplayName("staging VFS plumbing feeds the real KnowledgeStore index")
    void realKnowledgeStoreIndexedFromStagingVfs() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "alice prefers espresso");
        insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "alice prefers espresso");

        try (KeywordKnowledgeStore store = new KeywordKnowledgeStore(new SimpleDocumentChunker())) {
            KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);
            DrainResult result = relay.drainOnce();
            assertThat(result.getProcessed()).isEqualTo(1);

            KnowledgeScope scope = new KnowledgeScope(KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, SUBJECT_KEY);
            List<SearchResult> hits = store.search(scope, SearchQuery.builder().queryText("espresso").build());
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).getDocumentPath()).contains("obs-1.txt");
        }
    }

    @Test
    @DisplayName("UPSERT for an already-deleted observation rebuilds the (now-empty) subject scope")
    void missingObservationRebuildsScope() throws Exception {
        seedWorkspace(WORKSPACE);
        long id = insertOutboxRow(WORKSPACE, "obs-gone", SUBJECT_KEY, "UPSERT", "irrelevant");

        RecordingKnowledgeStore store = new RecordingKnowledgeStore();
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);

        DrainResult result = relay.drainOnce();

        assertThat(result.getProcessed()).isEqualTo(1);
        // The scope is rebuilt from the current survivors (none, since the row never existed), so a single
        // empty-scope reindex is issued — clearing any stale doc rather than skipping silently.
        assertThat(store.reindexCalls).hasSize(1);
        assertThat(store.reindexCalls.get(0).stagedFiles).isEmpty();
        assertThat(rowExists(id)).isFalse();
    }

    @Test
    @DisplayName("dispatch of one observation's row keeps the subject's OTHER observations searchable")
    void rebuildKeepsOtherObservations() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "alice prefers espresso");
        seedObservation(WORKSPACE, "obs-2", SUBJECT_KEY, "alice dislikes decaf");
        insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "alice prefers espresso");
        insertOutboxRow(WORKSPACE, "obs-2", SUBJECT_KEY, "UPSERT", "alice dislikes decaf");

        try (KeywordKnowledgeStore store = new KeywordKnowledgeStore(new SimpleDocumentChunker())) {
            KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);
            relay.drainOnce();

            KnowledgeScope scope = new KnowledgeScope(KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, SUBJECT_KEY);
            assertThat(store.search(scope, SearchQuery.builder().queryText("espresso").build())).isNotEmpty();
            assertThat(store.search(scope, SearchQuery.builder().queryText("decaf").build())).isNotEmpty();

            // Soft-delete obs-2 and drain its DELETE row; obs-1 must remain searchable (the old single-row
            // staging wiped the whole subject scope here — this is the regression guard for that bug).
            softDeleteObservation(WORKSPACE, "obs-2");
            insertOutboxRow(WORKSPACE, "obs-2", SUBJECT_KEY, "DELETE", null);
            relay.drainOnce();

            assertThat(store.search(scope, SearchQuery.builder().queryText("espresso").build())).isNotEmpty();
            assertThat(store.search(scope, SearchQuery.builder().queryText("decaf").build())).isEmpty();
        }
    }

    @Test
    @DisplayName("SKIP LOCKED: a concurrent drain on a separate connection does not double-claim")
    void skipLockedPreventsDoubleClaim() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "content-1");
        seedObservation(WORKSPACE, "obs-2", SUBJECT_KEY, "content-2");
        insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "content-1");
        insertOutboxRow(WORKSPACE, "obs-2", SUBJECT_KEY, "UPSERT", "content-2");

        // Two relay instances on isolated datasources.
        RecordingKnowledgeStore storeA = new RecordingKnowledgeStore();
        RecordingKnowledgeStore storeB = new RecordingKnowledgeStore();

        DataSource dsA = PostgresTestSupport.isolatedDataSource(2);
        DataSource dsB = PostgresTestSupport.isolatedDataSource(2);
        try {
            CountDownLatch aClaimed = new CountDownLatch(1);
            CountDownLatch bDone = new CountDownLatch(1);
            AtomicInteger aProcessed = new AtomicInteger();
            AtomicInteger bProcessed = new AtomicInteger();

            // Slow KnowledgeStore so A holds its claim while B drains.
            RecordingKnowledgeStore slowA = new RecordingKnowledgeStore(() -> {
                aClaimed.countDown();
                try {
                    bDone.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            // Use a tighter batch size so each relay claims at most one row.
            RelayOptions tight = RelayOptions.builder().pollBatchSize(2).build();
            KnowledgeStoreOutboxRelay relayA = new KnowledgeStoreOutboxRelay(dsA, slowA,
                    KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, tight);
            KnowledgeStoreOutboxRelay relayB = new KnowledgeStoreOutboxRelay(dsB, storeB,
                    KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, tight);

            Thread tA = new Thread(() -> aProcessed.set(relayA.drainOnce().getProcessed()));
            tA.start();

            // Wait for A to actually be inside dispatch; A claims both rows in one tx,
            // then dispatches them one by one. The slow callback fires on the first
            // dispatch — at that point B can attempt its own drain.
            assertThat(aClaimed.await(5, TimeUnit.SECONDS)).isTrue();

            // While A is still dispatching, B drains. SKIP LOCKED is only enforced
            // during the SELECT FOR UPDATE inside claimBatch(); A's claim is
            // already committed (claimed_until in the future), so B must see no
            // eligible rows.
            DrainResult bResult = relayB.drainOnce();
            bProcessed.set(bResult.getProcessed());
            bDone.countDown();

            tA.join(10_000L);

            assertThat(aProcessed.get() + bProcessed.get()).isEqualTo(2);
            // B must not have processed any of the rows A had already claimed.
            assertThat(bProcessed.get()).isZero();
            assertThat(aProcessed.get()).isEqualTo(2);
            // Both stores together must have seen exactly 2 reindex calls (no double).
            assertThat(slowA.reindexCalls.size() + storeB.reindexCalls.size()).isEqualTo(2);
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) dsA).close();
            ((com.zaxxer.hikari.HikariDataSource) dsB).close();
        }
        // Sanity: the recording store from the outer scope was never used.
        assertThat(storeA.reindexCalls).isEmpty();
    }

    @Test
    @DisplayName("on dispatch failure the row stays, attempt_count increments, next_attempt_at advances")
    void failureBacksOffAndKeepsRow() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "content-1");
        long id = insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "content-1");

        ThrowingKnowledgeStore store = new ThrowingKnowledgeStore(new RuntimeException("boom"));
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store);

        Instant before = Instant.now();
        DrainResult result = relay.drainOnce();
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getProcessed()).isZero();

        OutboxRowSnapshot snap = readOutboxRow(id);
        assertThat(snap).isNotNull();
        assertThat(snap.attemptCount).isEqualTo(1);
        assertThat(snap.lastError).contains("boom");
        assertThat(snap.claimedBy).isNull();
        assertThat(snap.claimedUntil).isNull();
        assertThat(snap.nextAttemptAt).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("after maxAttempts failures the row is poisoned and excluded from drain")
    void poisonPillExcludedFromDrain() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "content-1");
        long id = insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "content-1");

        ThrowingKnowledgeStore store = new ThrowingKnowledgeStore(new RuntimeException("nope"));
        // Tight maxAttempts + reset next_attempt_at between drains so all retries
        // land in the same drainOnce window.
        RelayOptions opts = RelayOptions.builder().maxAttempts(3).build();
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store,
                KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, opts);

        // First two failures → retry buckets.
        for (int i = 0; i < 2; i++) {
            DrainResult r = relay.drainOnce();
            assertThat(r.getFailed()).isEqualTo(1);
            // Reset next_attempt_at so the next drain picks the row up immediately.
            forceImmediateRetry(id);
        }
        // Third failure → poison.
        DrainResult last = relay.drainOnce();
        assertThat(last.getPoisoned()).isEqualTo(1);
        assertThat(last.getFailed()).isZero();

        OutboxRowSnapshot snap = readOutboxRow(id);
        assertThat(snap).isNotNull();
        assertThat(snap.attemptCount).isEqualTo(3);
        assertThat(snap.claimedBy).isEqualTo(KnowledgeStoreOutboxRelay.POISON_CLAIM);
        assertThat(snap.claimedUntil).isAfter(Instant.now().plusSeconds(60L * 60L * 24L * 365L * 10L));

        // A subsequent drain must skip the poisoned row.
        DrainResult after = relay.drainOnce();
        assertThat(after.getClaimed()).isZero();
    }

    @Test
    @DisplayName("start/stop processes a row inserted while the worker thread is alive")
    void startStopDrainsAsynchronously() throws Exception {
        seedWorkspace(WORKSPACE);
        seedObservation(WORKSPACE, "obs-1", SUBJECT_KEY, "content-1");
        insertOutboxRow(WORKSPACE, "obs-1", SUBJECT_KEY, "UPSERT", "content-1");

        RecordingKnowledgeStore store = new RecordingKnowledgeStore();
        RelayOptions opts = RelayOptions.builder().pollIntervalMillis(50L).build();
        KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(PostgresTestSupport.dataSource(), store,
                KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, opts);

        relay.start();
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && store.reindexCalls.isEmpty()) {
                Thread.sleep(25L);
            }
        } finally {
            relay.stop();
        }
        assertThat(store.reindexCalls).hasSize(1);
        // Idempotent stop.
        relay.stop();
    }

    // -- helpers ---------------------------------------------------------------

    private static void seedWorkspace(String id) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_workspace (id, display_name, created_at) VALUES (?, ?, now())")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static void seedObservation(String workspace, String localId, String subjectKey, String content)
            throws Exception {
        // subjectKey format from the relay's POV is "ws:TYPE:id" but we only need
        // a row in mem_observation so the relay's existence check passes — we
        // therefore parse defensively.
        String[] parts = subjectKey.split(":", 3);
        String subjectType = parts.length > 1 ? parts[1] : "USER";
        String subjectId = parts.length > 2 ? parts[2] : "alice";
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO mem_observation (workspace_id, local_id, "
                        + "subject_principal_type, subject_principal_id, subject_principal_display_name, "
                        + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
                        + "content, obs_type, confidence, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
            ps.setString(1, workspace);
            ps.setString(2, localId);
            ps.setString(3, subjectType);
            ps.setString(4, subjectId);
            ps.setString(5, subjectId);
            ps.setString(6, "USER");
            ps.setString(7, "observer");
            ps.setString(8, "Observer");
            ps.setString(9, content);
            ps.setString(10, "EXPLICIT");
            ps.setDouble(11, 0.9d);
            ps.executeUpdate();
        }
    }

    private static void softDeleteObservation(String workspace, String localId) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE mem_observation SET soft_deleted_at = now() WHERE workspace_id = ? AND local_id = ?")) {
            ps.setString(1, workspace);
            ps.setString(2, localId);
            ps.executeUpdate();
        }
    }

    private static long insertOutboxRow(String workspace, String localId, String subjectKey, String operation,
            String payload) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_outbox (workspace_id, observation_local_id, subject_key, operation, payload) "
                                + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, workspace);
            ps.setString(2, localId);
            ps.setString(3, subjectKey);
            ps.setString(4, operation);
            ps.setString(5, payload);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean rowExists(long id) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT 1 FROM mem_outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static OutboxRowSnapshot readOutboxRow(long id) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT attempt_count, last_error, "
                        + "next_attempt_at, claimed_by, claimed_until FROM mem_outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp nextAttempt = rs.getTimestamp("next_attempt_at");
                Timestamp claimedUntil = rs.getTimestamp("claimed_until");
                return new OutboxRowSnapshot(rs.getInt("attempt_count"), rs.getString("last_error"),
                        nextAttempt == null ? null : nextAttempt.toInstant(), rs.getString("claimed_by"),
                        claimedUntil == null ? null : claimedUntil.toInstant());
            }
        }
    }

    private static void forceImmediateRetry(long id) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE mem_outbox SET next_attempt_at = now() - interval '1 second' WHERE id = " + id);
        }
    }

    // -- fakes -----------------------------------------------------------------

    private static final class ReindexCall {
        final KnowledgeScope scope;
        final List<String> stagedFiles;

        ReindexCall(KnowledgeScope scope, List<String> stagedFiles) {
            this.scope = scope;
            this.stagedFiles = stagedFiles;
        }
    }

    private static class RecordingKnowledgeStore implements KnowledgeStore {
        final List<ReindexCall> reindexCalls = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentHashMap<String, List<SearchResult>> stub = new ConcurrentHashMap<>();
        private final Runnable beforeReindex;

        RecordingKnowledgeStore() {
            this(() -> {
            });
        }

        RecordingKnowledgeStore(Runnable beforeReindex) {
            this.beforeReindex = beforeReindex;
        }

        @Override
        public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            return reindex(scope, source, options);
        }

        @Override
        public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            beforeReindex.run();
            List<String> staged = new ArrayList<>(source.getFileSystem().listRecursive(source.getDirectory()));
            reindexCalls.add(new ReindexCall(scope, staged));
            return IndexResult.builder().indexedDocumentCount(staged.size()).indexedChunkCount(staged.size())
                    .skippedDocumentCount(0).durationMs(0L).errors(List.of()).build();
        }

        @Override
        public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
            return stub.getOrDefault(scope.getContextId(), List.of());
        }

        @Override
        public IndexStatus getStatus() {
            return IndexStatus.builder().state(IndexStatus.State.READY).build();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ThrowingKnowledgeStore implements KnowledgeStore {
        private final RuntimeException error;

        ThrowingKnowledgeStore(RuntimeException error) {
            this.error = error;
        }

        @Override
        public IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            throw error;
        }

        @Override
        public IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options) {
            throw error;
        }

        @Override
        public List<SearchResult> search(KnowledgeScope scope, SearchQuery query) {
            return List.of();
        }

        @Override
        public IndexStatus getStatus() {
            return IndexStatus.builder().state(IndexStatus.State.READY).build();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class OutboxRowSnapshot {
        final int attemptCount;
        final String lastError;
        final Instant nextAttemptAt;
        final String claimedBy;
        final Instant claimedUntil;

        OutboxRowSnapshot(int attemptCount, String lastError, Instant nextAttemptAt, String claimedBy,
                Instant claimedUntil) {
            this.attemptCount = attemptCount;
            this.lastError = lastError;
            this.nextAttemptAt = nextAttemptAt;
            this.claimedBy = claimedBy;
            this.claimedUntil = claimedUntil;
        }
    }
}
