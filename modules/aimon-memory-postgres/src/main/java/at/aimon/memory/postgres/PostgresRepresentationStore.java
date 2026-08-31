package at.aimon.memory.postgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.memory.postgres.internal.RepresentationRowCodec;

/**
 * Postgres-backed {@link RepresentationStore} per design §5.3 / §5.5.
 *
 * <p>
 * Representations are append-only. {@link #save} always inserts a new row in
 * {@code mem_representation}; the typed columns ({@code subject_*},
 * {@code observer_*}, {@code session_id}, {@code generated_at}) drive the two
 * indexes that satisfy {@link #findLatestGlobal} and {@link #findLatestLocal},
 * while a self-contained JSONB snapshot (encoded by
 * {@link RepresentationRowCodec}) lives in {@code payload_blob} and is the
 * single source of truth for hydration. Storing the full snapshot in JSONB
 * preserves immutability across later observation deletions: a representation
 * captures "what we knew at time T", and that capture must not be silently
 * mutated when the underlying observations are later GC'd.
 *
 * <p>
 * The {@code observation_local_ids} JSONB column is written for cross-table
 * audits / housekeeping queries (e.g. "which representations referenced this
 * observation?") but is not required for read-side hydration.
 */
public final class PostgresRepresentationStore implements RepresentationStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresRepresentationStore.class);

    private static final String SQL_INSERT = "INSERT INTO mem_representation ("
            + "workspace_id, subject_principal_type, subject_principal_id, subject_principal_display_name, "
            + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
            + "session_id, summary, token_count, observation_local_ids, payload_blob, generated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)";

    private static final String SQL_FIND_LATEST_GLOBAL = "SELECT payload_blob::text FROM mem_representation "
            + "WHERE workspace_id = ? AND subject_principal_type = ? AND subject_principal_id = ? "
            + "AND observer_principal_id IS NULL " + "ORDER BY generated_at DESC LIMIT 1";

    private static final String SQL_FIND_LATEST_LOCAL_NULL_SESSION = "SELECT payload_blob::text "
            + "FROM mem_representation WHERE workspace_id = ? "
            + "AND subject_principal_type = ? AND subject_principal_id = ? "
            + "AND observer_principal_type = ? AND observer_principal_id = ? AND session_id IS NULL "
            + "ORDER BY generated_at DESC LIMIT 1";

    private static final String SQL_FIND_LATEST_LOCAL_WITH_SESSION = "SELECT payload_blob::text "
            + "FROM mem_representation WHERE workspace_id = ? "
            + "AND subject_principal_type = ? AND subject_principal_id = ? "
            + "AND observer_principal_type = ? AND observer_principal_id = ? AND session_id = ? "
            + "ORDER BY generated_at DESC LIMIT 1";

    private static final String SQL_DELETE_OLDER_THAN = "DELETE FROM mem_representation "
            + "WHERE workspace_id = ? AND generated_at < ?";

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final RepresentationRowCodec codec;

    public PostgresRepresentationStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.codec = new RepresentationRowCodec(this.mapper);
    }

    @Override
    public Representation save(Representation representation) {
        Objects.requireNonNull(representation, "representation must not be null");

        final PeerView subject = representation.getSubject();
        final String workspaceId = subject.getWorkspace().getId();
        final String subjectType = subject.getPrincipal().getType().name();
        final String subjectId = subject.getPrincipal().getId();
        final String subjectDisplayName = subject.getPrincipal().getDisplayName();

        final PeerView observer = representation.getObserver().orElse(null);
        final String observerType = observer == null ? null : observer.getPrincipal().getType().name();
        final String observerId = observer == null ? null : observer.getPrincipal().getId();
        final String observerDisplayName = observer == null ? null : observer.getPrincipal().getDisplayName();

        final String sessionId = representation.getSessionId().orElse(null);
        final String observationIdsJson = encodeObservationIds(representation.getObservations());
        final String payloadJson = codec.encode(representation);

        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_INSERT)) {
            ps.setString(1, workspaceId);
            ps.setString(2, subjectType);
            ps.setString(3, subjectId);
            ps.setString(4, subjectDisplayName);
            setNullableString(ps, 5, observerType);
            setNullableString(ps, 6, observerId);
            setNullableString(ps, 7, observerDisplayName);
            setNullableString(ps, 8, sessionId);
            ps.setString(9, representation.getSummary());
            ps.setInt(10, representation.getTokenCount());
            ps.setString(11, observationIdsJson);
            ps.setString(12, payloadJson);
            ps.setTimestamp(13, Timestamp.from(representation.getGeneratedAt()));
            ps.executeUpdate();
            log.debug("Inserted representation for workspace={} subject={} scope={}", workspaceId, subject.key(),
                    observer == null ? "global" : "local");
            return representation;
        } catch (SQLException e) {
            throw new AimonException("Postgres error during save for workspace " + workspaceId, e);
        }
    }

    @Override
    public Optional<Representation> findLatestGlobal(PeerView subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_FIND_LATEST_GLOBAL)) {
            ps.setString(1, subject.getWorkspace().getId());
            ps.setString(2, subject.getPrincipal().getType().name());
            ps.setString(3, subject.getPrincipal().getId());
            return loadOne(ps);
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findLatestGlobal for subject " + subject.key(), e);
        }
    }

    @Override
    public Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        if (!subject.getWorkspace().equals(observer.getWorkspace())) {
            throw new IllegalArgumentException("subject and observer must belong to the same workspace");
        }
        final String sql = sessionId == null ? SQL_FIND_LATEST_LOCAL_NULL_SESSION : SQL_FIND_LATEST_LOCAL_WITH_SESSION;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subject.getWorkspace().getId());
            ps.setString(2, subject.getPrincipal().getType().name());
            ps.setString(3, subject.getPrincipal().getId());
            ps.setString(4, observer.getPrincipal().getType().name());
            ps.setString(5, observer.getPrincipal().getId());
            if (sessionId != null) {
                ps.setString(6, sessionId);
            }
            return loadOne(ps);
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findLatestLocal for subject " + subject.key(), e);
        }
    }

    @Override
    public void deleteOlderThan(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_DELETE_OLDER_THAN)) {
            ps.setString(1, workspace.getId());
            ps.setTimestamp(2, Timestamp.from(cutoff));
            final int deleted = ps.executeUpdate();
            log.debug("Deleted {} representation row(s) for workspace={} olderThan={}", deleted, workspace.getId(),
                    cutoff);
        } catch (SQLException e) {
            throw new AimonException("Postgres error during deleteOlderThan for workspace " + workspace.getId(), e);
        }
    }

    private Optional<Representation> loadOne(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            final String payload = rs.getString(1);
            return Optional.of(codec.decode(payload));
        }
    }

    private String encodeObservationIds(List<Observation> observations) {
        try {
            final List<String> ids = observations.stream().map(o -> o.getId().getLocalId()).toList();
            return mapper.writeValueAsString(ids);
        } catch (IOException e) {
            throw new AimonException("Failed to encode observation_local_ids", e);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
