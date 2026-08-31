package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Integration test for {@link PostgresObservationStore}. Boots a Testcontainers Postgres via
 * {@link PostgresTestSupport}, applies V1__init.sql once, truncates between tests.
 */
@DisplayName("PostgresObservationStore integration")
@Tag("docker")
class PostgresObservationStoreIntegrationTest {

    private static final String WORKSPACE_ID = "ws-test";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private PostgresObservationStore store;
    private Workspace workspace;
    private PeerView subject;
    private PeerView otherSubject;
    private PeerView observer;

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestSupport.truncateAll();
        seedWorkspace(WORKSPACE_ID);
        store = new PostgresObservationStore(PostgresTestSupport.dataSource(), mapper);

        workspace = Workspace.builder().id(WORKSPACE_ID).displayName("Test Workspace").build();
        subject = PeerView.of(workspace, Principal.user("alice", "Alice"));
        otherSubject = PeerView.of(workspace, Principal.user("carol", "Carol"));
        observer = PeerView.of(workspace, Principal.user("bob", "Bob"));
    }

    @Test
    @DisplayName("save then findById preserves all fields")
    void saveAndFindByIdRoundTrip() {
        Instant created = Instant.parse("2025-01-15T10:30:00Z");
        Observation original = Observation.builder().id(ObservationId.of(workspace, "obs-1")).subject(subject)
                .observer(observer).content("Alice prefers dark roast coffee").type(ObservationType.EXPLICIT)
                .sourceMessageIds(List.of("msg-1", "msg-2")).createdAt(created).confidence(0.85d)
                .metadata(Map.of("topic", "preferences", "category", "food")).build();

        store.save(original);

        Optional<Observation> found = store.findById(original.getId());

        assertThat(found).isPresent();
        Observation o = found.get();
        assertThat(o.getId()).isEqualTo(original.getId());
        assertThat(o.getContent()).isEqualTo("Alice prefers dark roast coffee");
        assertThat(o.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(o.getSourceMessageIds()).containsExactly("msg-1", "msg-2");
        // Postgres timestamps may differ in sub-microsecond rounding; compare to millis precision.
        assertThat(o.getCreatedAt().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(created.truncatedTo(ChronoUnit.MILLIS));
        assertThat(o.getConfidence()).isEqualTo(0.85d);
        assertThat(o.getMetadata()).containsEntry("topic", "preferences").containsEntry("category", "food");
        assertThat(o.getSubject().getPrincipal().getId()).isEqualTo("alice");
        assertThat(o.getSubject().getPrincipal().getDisplayName()).isEqualTo("Alice");
        assertThat(o.getObserver().getPrincipal().getId()).isEqualTo("bob");
    }

    @Test
    @DisplayName("save inserts a UPSERT outbox row in the same transaction")
    void saveInsertsOutboxRow() throws Exception {
        Observation obs = newObservation("obs-1", subject, "content one", 0.7d);
        store.save(obs);

        List<OutboxRow> rows = readOutbox();
        assertThat(rows).hasSize(1);
        OutboxRow row = rows.get(0);
        assertThat(row.workspaceId).isEqualTo(WORKSPACE_ID);
        assertThat(row.observationLocalId).isEqualTo("obs-1");
        assertThat(row.subjectKey).isEqualTo(subject.key());
        assertThat(row.operation).isEqualTo("UPSERT");
        assertThat(row.payload).isEqualTo("content one");
    }

    @Test
    @DisplayName("findBySubject returns rows newest-first up to limit")
    void findBySubjectOrderingAndLimit() {
        Instant base = Instant.parse("2025-01-15T10:00:00Z");
        store.save(newObservationAt("obs-1", subject, "first", 0.5d, base));
        store.save(newObservationAt("obs-2", subject, "second", 0.5d, base.plusSeconds(60)));
        store.save(newObservationAt("obs-3", subject, "third", 0.5d, base.plusSeconds(120)));
        // unrelated subject should be excluded
        store.save(newObservationAt("obs-4", otherSubject, "other", 0.5d, base.plusSeconds(180)));

        List<Observation> top2 = store.findBySubject(subject, 2);

        assertThat(top2).extracting(o -> o.getId().getLocalId()).containsExactly("obs-3", "obs-2");
    }

    @Test
    @DisplayName("findBySubject rejects limit < 1")
    void findBySubjectRejectsBadLimit() {
        assertThatThrownBy(() -> store.findBySubject(subject, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("count returns live row count for subject only")
    void countFiltersBySubject() {
        store.save(newObservation("obs-1", subject, "one", 0.5d));
        store.save(newObservation("obs-2", subject, "two", 0.5d));
        store.save(newObservation("obs-3", otherSubject, "other", 0.5d));

        assertThat(store.count(subject)).isEqualTo(2L);
        assertThat(store.count(otherSubject)).isEqualTo(1L);
    }

    @Test
    @DisplayName("findByConfidenceBelow filters and orders ascending")
    void findByConfidenceBelowOrdering() {
        store.save(newObservation("obs-low", subject, "low", 0.1d));
        store.save(newObservation("obs-mid", subject, "mid", 0.4d));
        store.save(newObservation("obs-hi", subject, "hi", 0.9d));
        // exclude: equal to threshold (strict <)
        store.save(newObservation("obs-eq", subject, "equal", 0.5d));

        List<Observation> result = store.findByConfidenceBelow(subject, 0.5d, 10);

        assertThat(result).extracting(o -> o.getId().getLocalId()).containsExactly("obs-low", "obs-mid");
    }

    @Test
    @DisplayName("delete hard-deletes row and emits DELETE outbox row")
    void deleteRemovesRowAndEmitsOutbox() throws Exception {
        Observation obs = newObservation("obs-1", subject, "doomed", 0.5d);
        store.save(obs);
        assertThat(store.findById(obs.getId())).isPresent();

        store.delete(obs.getId());

        assertThat(store.findById(obs.getId())).isEmpty();

        // A row should remain hard-deleted, not just soft-deleted.
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM mem_observation WHERE workspace_id = ? AND local_id = ?")) {
            ps.setString(1, WORKSPACE_ID);
            ps.setString(2, "obs-1");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }

        List<OutboxRow> rows = readOutbox();
        assertThat(rows).extracting(r -> r.operation).containsExactly("UPSERT", "DELETE");
        OutboxRow deleteRow = rows.get(1);
        assertThat(deleteRow.observationLocalId).isEqualTo("obs-1");
        assertThat(deleteRow.subjectKey).isEqualTo(subject.key());
        assertThat(deleteRow.payload).isNull();
    }

    @Test
    @DisplayName("merge soft-deletes loser, upserts winner, emits two outbox rows")
    void mergeSoftDeletesLoser() throws Exception {
        Observation winner = newObservation("obs-winner", subject, "winner content", 0.7d);
        Observation loser = newObservation("obs-loser", subject, "loser content", 0.5d);
        store.save(winner);
        store.save(loser);

        Observation merged = Observation.builder().id(winner.getId()).subject(subject).observer(observer)
                .content("merged content").type(ObservationType.EXPLICIT).sourceMessageIds(List.of("m1", "m2"))
                .createdAt(Instant.now()).confidence(0.9d).metadata(Map.of("k", "v")).build();

        Observation returned = store.merge(winner.getId(), loser.getId(), merged);

        assertThat(returned).isEqualTo(merged);

        Optional<Observation> winnerAfter = store.findById(winner.getId());
        assertThat(winnerAfter).isPresent();
        assertThat(winnerAfter.get().getContent()).isEqualTo("merged content");
        assertThat(winnerAfter.get().getConfidence()).isEqualTo(0.9d);

        // Loser is soft-deleted: not surfaced by findById, but row exists with soft_deleted_at set.
        assertThat(store.findById(loser.getId())).isEmpty();
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT soft_deleted_at FROM mem_observation WHERE workspace_id = ? AND local_id = ?")) {
            ps.setString(1, WORKSPACE_ID);
            ps.setString(2, "obs-loser");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp("soft_deleted_at")).isNotNull();
            }
        }

        // Outbox should hold: UPSERT(winner), UPSERT(loser), DELETE(loser), UPSERT(winner-merged).
        List<OutboxRow> rows = readOutbox();
        assertThat(rows).extracting(r -> r.observationLocalId + ":" + r.operation).containsExactly("obs-winner:UPSERT",
                "obs-loser:UPSERT", "obs-loser:DELETE", "obs-winner:UPSERT");
        OutboxRow loserDelete = rows.get(2);
        assertThat(loserDelete.subjectKey).isEqualTo(subject.key());
        assertThat(loserDelete.payload).isNull();
        OutboxRow winnerMergedUpsert = rows.get(3);
        assertThat(winnerMergedUpsert.payload).isEqualTo("merged content");
    }

    @Test
    @DisplayName("merge rejects winner == loser")
    void mergeRejectsSameId() {
        ObservationId id = ObservationId.of(workspace, "x");
        Observation merged = newObservation("x", subject, "m", 0.5d);
        assertThatThrownBy(() -> store.merge(id, id, merged)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    @DisplayName("merge rejects merged.id != winner")
    void mergeRejectsMismatchedId() {
        ObservationId winner = ObservationId.of(workspace, "winner");
        ObservationId loser = ObservationId.of(workspace, "loser");
        Observation merged = newObservation("mismatch", subject, "m", 0.5d);
        assertThatThrownBy(() -> store.merge(winner, loser, merged)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must equal winner");
    }

    @Test
    @DisplayName("semanticSearch always throws UnsupportedOperationException")
    void semanticSearchUnsupported() {
        assertThatThrownBy(() -> store.semanticSearch(subject, "anything", 5))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("ObservationIndex");
    }

    @Test
    @DisplayName("findSubjects returns distinct live subjects, excludes soft-deleted, respects limit")
    void findSubjectsReturnsDistinct() {
        store.save(newObservation("obs-a1", subject, "a-1", 0.5d));
        store.save(newObservation("obs-a2", subject, "a-2", 0.5d));
        store.save(newObservation("obs-c1", otherSubject, "c-1", 0.5d));
        // soft-delete this loser via merge: subject still appears via remaining live row
        store.save(newObservation("obs-c2", otherSubject, "c-2", 0.5d));
        Observation winner = newObservation("obs-c1", otherSubject, "c-1-winner", 0.7d);
        store.merge(winner.getId(), ObservationId.of(workspace, "obs-c2"), winner);

        List<PeerView> subjects = store.findSubjects(workspace, 10);

        assertThat(subjects).extracting(p -> p.getPrincipal().getId()).containsExactlyInAnyOrder("alice", "carol");

        List<PeerView> capped = store.findSubjects(workspace, 1);
        assertThat(capped).hasSize(1);
    }

    @Test
    @DisplayName("findSubjects rejects limit < 1")
    void findSubjectsRejectsBadLimit() {
        assertThatThrownBy(() -> store.findSubjects(workspace, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private Observation newObservation(String localId, PeerView subj, String content, double confidence) {
        return newObservationAt(localId, subj, content, confidence, Instant.now());
    }

    private Observation newObservationAt(String localId, PeerView subj, String content, double confidence,
            Instant createdAt) {
        return Observation.builder().id(ObservationId.of(workspace, localId)).subject(subj).observer(observer)
                .content(content).type(ObservationType.EXPLICIT).sourceMessageIds(List.of()).createdAt(createdAt)
                .confidence(confidence).metadata(Map.of()).build();
    }

    private static void seedWorkspace(String id) throws SQLException {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_workspace (id, display_name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private static List<OutboxRow> readOutbox() throws SQLException {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c
                        .prepareStatement("SELECT workspace_id, observation_local_id, subject_key, operation, payload "
                                + "FROM mem_outbox ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            List<OutboxRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new OutboxRow(rs.getString("workspace_id"), rs.getString("observation_local_id"),
                        rs.getString("subject_key"), rs.getString("operation"), rs.getString("payload")));
            }
            return rows;
        }
    }

    private static final class OutboxRow {
        final String workspaceId;
        final String observationLocalId;
        final String subjectKey;
        final String operation;
        final String payload;

        OutboxRow(String workspaceId, String observationLocalId, String subjectKey, String operation, String payload) {
            this.workspaceId = workspaceId;
            this.observationLocalId = observationLocalId;
            this.subjectKey = subjectKey;
            this.operation = operation;
            this.payload = payload;
        }
    }
}
