package at.aimon.memory.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.Principal;
import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Postgres-backed {@link ObservationStore} implementing the metadata side of the store/index split (design doc §5.2).
 *
 * <p>
 * Search ({@code semanticSearch}) is intentionally <em>not</em> implemented here: vector search is delegated to the
 * {@code KnowledgeStore} via an {@code ObservationIndex} so the memory layer does not build a parallel RAG stack.
 * Callers needing semantic search should compose this store with another one that owns indexing — calling
 * {@link #semanticSearch} directly throws {@link UnsupportedOperationException}.
 *
 * <p>
 * Every write to {@code mem_observation} is paired with an outbox row in {@code mem_outbox} written inside the same
 * JDBC transaction (design §5.2 outbox). A separate worker drains the outbox and pushes embeddings to the
 * {@code KnowledgeStore}, giving us at-least-once delivery without a 2PC across heterogeneous backends.
 *
 * <p>
 * {@link #merge(ObservationId, ObservationId, Observation)} performs a soft-delete on the loser
 * (sets {@code soft_deleted_at = now()}), keeping it for the 30-day audit window described in §5.2.
 * {@link #delete(ObservationId)} is a hard delete because the audit retention belongs to {@code merge()}, not to
 * arbitrary deletes.
 *
 * <p>
 * Wraps {@link SQLException} as {@link AimonException} (the project-wide pattern, see
 * {@code at.aimon.core.agent.session.exception.IdempotencyStoreException}).
 */
public final class PostgresObservationStore implements ObservationStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresObservationStore.class);

    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, String>> MAP_OF_STRING = new TypeReference<>() {
    };

    private static final String SQL_UPSERT_OBSERVATION = "INSERT INTO mem_observation (" + "workspace_id, local_id, "
            + "subject_principal_type, subject_principal_id, subject_principal_display_name, "
            + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
            + "content, obs_type, source_message_ids, confidence, metadata, created_at, soft_deleted_at"
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, NULL) "
            + "ON CONFLICT (workspace_id, local_id) DO UPDATE SET "
            + "subject_principal_type = EXCLUDED.subject_principal_type, "
            + "subject_principal_id = EXCLUDED.subject_principal_id, "
            + "subject_principal_display_name = EXCLUDED.subject_principal_display_name, "
            + "observer_principal_type = EXCLUDED.observer_principal_type, "
            + "observer_principal_id = EXCLUDED.observer_principal_id, "
            + "observer_principal_display_name = EXCLUDED.observer_principal_display_name, "
            + "content = EXCLUDED.content, " + "obs_type = EXCLUDED.obs_type, "
            + "source_message_ids = EXCLUDED.source_message_ids, " + "confidence = EXCLUDED.confidence, "
            + "metadata = EXCLUDED.metadata, " + "created_at = EXCLUDED.created_at, " + "soft_deleted_at = NULL";

    private static final String SQL_INSERT_OUTBOX = "INSERT INTO mem_outbox ("
            + "workspace_id, observation_local_id, subject_key, operation, payload) VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_COLUMNS = "obs.workspace_id, obs.local_id, "
            + "obs.subject_principal_type, obs.subject_principal_id, obs.subject_principal_display_name, "
            + "obs.observer_principal_type, obs.observer_principal_id, obs.observer_principal_display_name, "
            + "obs.content, obs.obs_type, obs.source_message_ids::text AS source_message_ids, "
            + "obs.confidence, obs.metadata::text AS metadata, obs.created_at, obs.soft_deleted_at, "
            + "ws.display_name AS workspace_display_name, ws.metadata::text AS workspace_metadata, "
            + "ws.created_at AS workspace_created_at";

    private static final String SELECT_FROM = " FROM mem_observation obs "
            + "JOIN mem_workspace ws ON ws.id = obs.workspace_id ";

    private static final String SQL_FIND_BY_ID = "SELECT " + SELECT_COLUMNS + SELECT_FROM
            + "WHERE obs.workspace_id = ? AND obs.local_id = ? AND obs.soft_deleted_at IS NULL";

    private static final String SQL_FIND_BY_SUBJECT = "SELECT " + SELECT_COLUMNS + SELECT_FROM
            + "WHERE obs.workspace_id = ? AND obs.subject_principal_type = ? AND obs.subject_principal_id = ? "
            + "AND obs.soft_deleted_at IS NULL ORDER BY obs.created_at DESC LIMIT ?";

    private static final String SQL_COUNT_BY_SUBJECT = "SELECT COUNT(*) FROM mem_observation "
            + "WHERE workspace_id = ? AND subject_principal_type = ? AND subject_principal_id = ? "
            + "AND soft_deleted_at IS NULL";

    private static final String SQL_FIND_BY_CONFIDENCE_BELOW = "SELECT " + SELECT_COLUMNS + SELECT_FROM
            + "WHERE obs.workspace_id = ? AND obs.subject_principal_type = ? AND obs.subject_principal_id = ? "
            + "AND obs.confidence < ? AND obs.soft_deleted_at IS NULL ORDER BY obs.confidence ASC LIMIT ?";

    private static final String SQL_FIND_SUBJECTS = "SELECT DISTINCT subject_principal_type, subject_principal_id, "
            + "subject_principal_display_name FROM mem_observation "
            + "WHERE workspace_id = ? AND soft_deleted_at IS NULL "
            + "ORDER BY subject_principal_type, subject_principal_id LIMIT ?";

    private static final String SQL_DELETE_OBSERVATION = "DELETE FROM mem_observation "
            + "WHERE workspace_id = ? AND local_id = ?";

    private static final String SQL_SOFT_DELETE_LOSER = "UPDATE mem_observation SET soft_deleted_at = ? "
            + "WHERE workspace_id = ? AND local_id = ?";

    private static final String SQL_PURGE_SOFT_DELETED = "DELETE FROM mem_observation "
            + "WHERE workspace_id = ? AND soft_deleted_at IS NOT NULL AND soft_deleted_at < ?";

    private static final String OPERATION_UPSERT = "UPSERT";
    private static final String OPERATION_DELETE = "DELETE";

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    /**
     * Creates a new store.
     *
     * @param dataSource
     *            JDBC pool against the Postgres schema in {@code db/postgres/V1__init.sql}; must not be null
     * @param mapper
     *            Jackson mapper used for {@code source_message_ids} and {@code metadata} jsonb columns; injected
     *            so multi-instance configs can control Jackson features (e.g., FAIL_ON_UNKNOWN_PROPERTIES)
     * @throws NullPointerException
     *             if any argument is null
     */
    public PostgresObservationStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Observation save(Observation observation) {
        Objects.requireNonNull(observation, "observation must not be null");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                writeObservation(c, observation);
                writeOutbox(c, observation.getId().getWorkspaceId(), observation.getId().getLocalId(),
                        observation.getSubject().key(), OPERATION_UPSERT, observation.getContent());
                c.commit();
                log.debug("save observation {} (workspace={})", observation.getId().getLocalId(),
                        observation.getId().getWorkspaceId());
                return observation;
            } catch (SQLException | JsonProcessingException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                restoreAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during save for " + observation.getId(), e);
        } catch (JsonProcessingException e) {
            throw new AimonException("Failed to serialize observation jsonb columns for " + observation.getId(), e);
        }
    }

    @Override
    public Optional<Observation> findById(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)) {
            ps.setString(1, id.getWorkspaceId());
            ps.setString(2, id.getLocalId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(buildObservation(rs));
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findById for " + id, e);
        }
    }

    @Override
    public List<Observation> findBySubject(PeerView subject, int limit) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_SUBJECT)) {
            ps.setString(1, subject.getWorkspace().getId());
            ps.setString(2, subject.getPrincipal().getType().name());
            ps.setString(3, subject.getPrincipal().getId());
            ps.setInt(4, limit);
            return executeSelect(ps);
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findBySubject for " + subject.key(), e);
        }
    }

    @Override
    public long count(PeerView subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_COUNT_BY_SUBJECT)) {
            ps.setString(1, subject.getWorkspace().getId());
            ps.setString(2, subject.getPrincipal().getType().name());
            ps.setString(3, subject.getPrincipal().getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0L;
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during count for " + subject.key(), e);
        }
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * <p>
     * Per design §5.2 the metadata store and the search index are split: vector search lives in the
     * {@code KnowledgeStore} via an {@code ObservationIndex}. To get semantic search, wrap this store in another
     * {@code ObservationStore} that owns indexing (the in-memory reference does this with
     * {@code InMemoryObservationIndex}).
     */
    @Override
    public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(query, "query must not be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        throw new UnsupportedOperationException("PostgresObservationStore does not implement semanticSearch directly; "
                + "wrap in another store with an ObservationIndex");
    }

    @Override
    public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_CONFIDENCE_BELOW)) {
            ps.setString(1, subject.getWorkspace().getId());
            ps.setString(2, subject.getPrincipal().getType().name());
            ps.setString(3, subject.getPrincipal().getId());
            ps.setDouble(4, threshold);
            ps.setInt(5, limit);
            return executeSelect(ps);
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findByConfidenceBelow for " + subject.key(), e);
        }
    }

    @Override
    public List<PeerView> findSubjects(Workspace workspace, int limit) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_SUBJECTS)) {
            ps.setString(1, workspace.getId());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<PeerView> out = new ArrayList<>();
                while (rs.next()) {
                    Principal p = Principal.builder().type(Principal.Type.valueOf(rs.getString(1))).id(rs.getString(2))
                            .displayName(rs.getString(3)).build();
                    out.add(PeerView.of(workspace, p));
                }
                return List.copyOf(out);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findSubjects for workspace " + workspace.getId(), e);
        }
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                final String subjectKey = loadSubjectKey(c, id);
                try (PreparedStatement ps = c.prepareStatement(SQL_DELETE_OBSERVATION)) {
                    ps.setString(1, id.getWorkspaceId());
                    ps.setString(2, id.getLocalId());
                    ps.executeUpdate();
                }
                writeOutbox(c, id.getWorkspaceId(), id.getLocalId(),
                        subjectKey != null ? subjectKey : id.getWorkspaceId() + ":UNKNOWN:" + id.getLocalId(),
                        OPERATION_DELETE, null);
                c.commit();
                log.debug("delete observation {} (workspace={})", id.getLocalId(), id.getWorkspaceId());
            } catch (SQLException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                restoreAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during delete for " + id, e);
        }
    }

    @Override
    public Observation merge(ObservationId winner, ObservationId loser, Observation merged) {
        Objects.requireNonNull(winner, "winner must not be null");
        Objects.requireNonNull(loser, "loser must not be null");
        Objects.requireNonNull(merged, "merged must not be null");
        if (winner.equals(loser)) {
            throw new IllegalArgumentException("winner and loser must differ: " + winner);
        }
        if (!merged.getId().equals(winner)) {
            throw new IllegalArgumentException(
                    "merged observation id (" + merged.getId() + ") must equal winner (" + winner + ")");
        }
        if (!winner.getWorkspaceId().equals(loser.getWorkspaceId())) {
            throw new IllegalArgumentException("winner workspace (" + winner.getWorkspaceId()
                    + ") must equal loser workspace (" + loser.getWorkspaceId() + ")");
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                final String loserSubjectKey = loadSubjectKey(c, loser);

                try (PreparedStatement ps = c.prepareStatement(SQL_SOFT_DELETE_LOSER)) {
                    ps.setTimestamp(1, Timestamp.from(Instant.now()));
                    ps.setString(2, loser.getWorkspaceId());
                    ps.setString(3, loser.getLocalId());
                    ps.executeUpdate();
                }
                writeOutbox(c, loser.getWorkspaceId(), loser.getLocalId(),
                        loserSubjectKey != null
                                ? loserSubjectKey
                                : loser.getWorkspaceId() + ":UNKNOWN:" + loser.getLocalId(),
                        OPERATION_DELETE, null);

                writeObservation(c, merged);
                writeOutbox(c, merged.getId().getWorkspaceId(), merged.getId().getLocalId(), merged.getSubject().key(),
                        OPERATION_UPSERT, merged.getContent());

                c.commit();
                log.debug("merge winner={} loser={} (workspace={})", winner.getLocalId(), loser.getLocalId(),
                        winner.getWorkspaceId());
                return merged;
            } catch (SQLException | JsonProcessingException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                restoreAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during merge winner=" + winner + " loser=" + loser, e);
        } catch (JsonProcessingException e) {
            throw new AimonException("Failed to serialize merge jsonb columns winner=" + winner + " loser=" + loser, e);
        }
    }

    @Override
    public void softDelete(ObservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                final String subjectKey = loadSubjectKey(c, id);
                if (subjectKey == null) {
                    // Row absent — nothing to retire.
                    c.commit();
                    return;
                }
                try (PreparedStatement ps = c.prepareStatement(SQL_SOFT_DELETE_LOSER)) {
                    ps.setTimestamp(1, Timestamp.from(Instant.now()));
                    ps.setString(2, id.getWorkspaceId());
                    ps.setString(3, id.getLocalId());
                    ps.executeUpdate();
                }
                // Drop the embedding from the KnowledgeStore (same outbox path as merge's loser).
                writeOutbox(c, id.getWorkspaceId(), id.getLocalId(), subjectKey, OPERATION_DELETE, null);
                c.commit();
                log.debug("soft-delete observation {} (workspace={})", id.getLocalId(), id.getWorkspaceId());
            } catch (SQLException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                restoreAutoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during softDelete for " + id, e);
        }
    }

    @Override
    public int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        // The KnowledgeStore entry was already removed when the row was soft-deleted (outbox DELETE),
        // so purging the retained metadata row needs no further outbox work.
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_PURGE_SOFT_DELETED)) {
            ps.setString(1, workspace.getId());
            ps.setTimestamp(2, Timestamp.from(cutoff));
            int purged = ps.executeUpdate();
            if (purged > 0) {
                log.info("Purged {} soft-deleted observations older than {} in workspace {}", purged, cutoff,
                        workspace.getId());
            }
            return purged;
        } catch (SQLException e) {
            throw new AimonException("Postgres error during purgeSoftDeletedBefore for workspace " + workspace.getId(),
                    e);
        }
    }

    private void writeObservation(Connection c, Observation observation) throws SQLException, JsonProcessingException {
        try (PreparedStatement ps = c.prepareStatement(SQL_UPSERT_OBSERVATION)) {
            ps.setString(1, observation.getId().getWorkspaceId());
            ps.setString(2, observation.getId().getLocalId());
            ps.setString(3, observation.getSubject().getPrincipal().getType().name());
            ps.setString(4, observation.getSubject().getPrincipal().getId());
            ps.setString(5, observation.getSubject().getPrincipal().getDisplayName());
            ps.setString(6, observation.getObserver().getPrincipal().getType().name());
            ps.setString(7, observation.getObserver().getPrincipal().getId());
            ps.setString(8, observation.getObserver().getPrincipal().getDisplayName());
            ps.setString(9, observation.getContent());
            ps.setString(10, observation.getType().name());
            ps.setString(11, mapper.writeValueAsString(observation.getSourceMessageIds()));
            ps.setDouble(12, observation.getConfidence());
            ps.setString(13, mapper.writeValueAsString(observation.getMetadata()));
            ps.setTimestamp(14, Timestamp.from(observation.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    private void writeOutbox(Connection c, String workspaceId, String localId, String subjectKey, String operation,
            String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_OUTBOX)) {
            ps.setString(1, workspaceId);
            ps.setString(2, localId);
            ps.setString(3, subjectKey);
            ps.setString(4, operation);
            if (payload == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, payload);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Reads {@code subject_principal_type / id / display_name + workspace} for the row identified by {@code id} so
     * the outbox row can carry the subject_key even after the metadata row has been hard-deleted. Returns
     * {@code null} if the row is not present (already-deleted or never-saved id).
     */
    private String loadSubjectKey(Connection c, ObservationId id) throws SQLException {
        final String sql = "SELECT workspace_id, subject_principal_type, subject_principal_id "
                + "FROM mem_observation WHERE workspace_id = ? AND local_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.getWorkspaceId());
            ps.setString(2, id.getLocalId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("workspace_id") + ":" + rs.getString("subject_principal_type") + ":"
                        + rs.getString("subject_principal_id");
            }
        }
    }

    private List<Observation> executeSelect(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            final List<Observation> out = new ArrayList<>();
            while (rs.next()) {
                out.add(buildObservation(rs));
            }
            return List.copyOf(out);
        }
    }

    private Observation buildObservation(ResultSet rs) throws SQLException {
        final String workspaceId = rs.getString("workspace_id");
        final String localId = rs.getString("local_id");

        final Map<String, String> workspaceMetadata = decodeJsonMap(rs.getString("workspace_metadata"), workspaceId,
                "workspace_metadata");
        final Workspace workspace = Workspace.builder().id(workspaceId)
                .displayName(rs.getString("workspace_display_name"))
                .createdAt(rs.getTimestamp("workspace_created_at").toInstant()).metadata(workspaceMetadata).build();

        final Principal subjectPrincipal = Principal.builder()
                .type(Principal.Type.valueOf(rs.getString("subject_principal_type")))
                .id(rs.getString("subject_principal_id")).displayName(rs.getString("subject_principal_display_name"))
                .build();
        final Principal observerPrincipal = Principal.builder()
                .type(Principal.Type.valueOf(rs.getString("observer_principal_type")))
                .id(rs.getString("observer_principal_id")).displayName(rs.getString("observer_principal_display_name"))
                .build();

        final List<String> sourceMessageIds = decodeJsonList(rs.getString("source_message_ids"), localId,
                "source_message_ids");
        final Map<String, String> metadata = decodeJsonMap(rs.getString("metadata"), localId, "metadata");

        return Observation.builder().id(ObservationId.of(workspace, localId))
                .subject(PeerView.of(workspace, subjectPrincipal)).observer(PeerView.of(workspace, observerPrincipal))
                .content(rs.getString("content")).type(ObservationType.valueOf(rs.getString("obs_type")))
                .sourceMessageIds(sourceMessageIds).createdAt(rs.getTimestamp("created_at").toInstant())
                .confidence(rs.getDouble("confidence")).metadata(metadata).build();
    }

    private List<String> decodeJsonList(String json, String localId, String column) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            final List<String> parsed = mapper.readValue(json, LIST_OF_STRING);
            return parsed != null ? parsed : List.of();
        } catch (JsonProcessingException e) {
            throw new AimonException(
                    "Failed to decode jsonb column '" + column + "' for observation " + localId + ": " + json, e);
        }
    }

    private Map<String, String> decodeJsonMap(String json, String localId, String column) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            final Map<String, String> parsed = mapper.readValue(json, MAP_OF_STRING);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            throw new AimonException(
                    "Failed to decode jsonb column '" + column + "' for observation " + localId + ": " + json, e);
        }
    }

    private static void rollbackQuietly(Connection c, Exception primary) {
        try {
            c.rollback();
        } catch (SQLException rollbackEx) {
            primary.addSuppressed(rollbackEx);
        }
    }

    /**
     * Resets the connection's auto-commit flag without letting the call mask the original exception.
     *
     * <p>
     * If a transactional operation already failed, the {@code finally} block must not raise its own
     * exception — otherwise the original {@link SQLException} would be lost. Most pooled drivers (HikariCP,
     * Commons-DBCP) reset auto-commit on connection return, but we restore it here for callers that pass in
     * a non-pooling {@link DataSource}, keeping the behaviour explicit. Failures during the restore are
     * logged at WARN since the connection will be discarded by the pool on close.
     */
    private static void restoreAutoCommitQuietly(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Failed to restore auto-commit on Postgres connection (pool will discard on close): {}",
                    e.getMessage());
        }
    }
}
