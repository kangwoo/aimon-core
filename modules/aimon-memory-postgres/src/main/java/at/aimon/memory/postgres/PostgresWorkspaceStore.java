package at.aimon.memory.postgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.Principal;
import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;

/**
 * Postgres-backed {@link WorkspaceStore} per design §5.1 / §5.5.
 *
 * <p>
 * Each workspace maps to a single row in {@code mem_workspace} keyed by
 * {@code id}. The {@code metadata} map is stored in a JSONB column as a flat
 * {@code String→String} object. {@code created_at} round-trips through
 * {@link Timestamp} ↔ {@link java.time.Instant}.
 *
 * <p>
 * Cascading deletes on {@code mem_observation} and {@code mem_representation}
 * are handled by {@code ON DELETE CASCADE} in V1__init.sql, so {@link #delete}
 * is a single statement.
 *
 * <p>
 * <b>ACL note:</b> {@link #findAll(Principal)} currently performs no access
 * control filtering — that is deferred to a future stage. Today every caller
 * sees every row, matching the in-memory reference implementation. Production
 * deployments must extend this method once an ACL model is in place.
 */
public final class PostgresWorkspaceStore implements WorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresWorkspaceStore.class);

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    private static final String SQL_INSERT = "INSERT INTO mem_workspace (id, display_name, metadata, created_at) "
            + "VALUES (?, ?, ?::jsonb, ?)";

    private static final String SQL_FIND_BY_ID = "SELECT id, display_name, metadata::text, created_at "
            + "FROM mem_workspace WHERE id = ?";

    private static final String SQL_FIND_ALL = "SELECT id, display_name, metadata::text, created_at "
            + "FROM mem_workspace ORDER BY created_at, id";

    private static final String SQL_DELETE = "DELETE FROM mem_workspace WHERE id = ?";

    private static final TypeReference<Map<String, String>> METADATA_TYPE = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public PostgresWorkspaceStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        final String metadataJson = encodeMetadata(workspace.getMetadata());
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_INSERT)) {
            ps.setString(1, workspace.getId());
            ps.setString(2, workspace.getDisplayName());
            ps.setString(3, metadataJson);
            ps.setTimestamp(4, Timestamp.from(workspace.getCreatedAt()));
            ps.executeUpdate();
            log.debug("Inserted workspace id={}", workspace.getId());
            return workspace;
        } catch (SQLException e) {
            if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new IllegalStateException("Workspace already exists: " + workspace.getId(), e);
            }
            throw new AimonException("Postgres error during create for workspace " + workspace.getId(), e);
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(buildWorkspace(rs));
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findById for workspace " + id, e);
        }
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester must not be null");
        // Stage 1: no ACL — production deployments must enforce real access control here.
        final List<Workspace> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_FIND_ALL);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(buildWorkspace(rs));
            }
        } catch (SQLException e) {
            throw new AimonException("Postgres error during findAll", e);
        }
        return out;
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, workspace.getId());
            final int deleted = ps.executeUpdate();
            log.debug("Deleted workspace id={} ({} row{})", workspace.getId(), deleted, deleted == 1 ? "" : "s");
        } catch (SQLException e) {
            throw new AimonException("Postgres error during delete for workspace " + workspace.getId(), e);
        }
    }

    private Workspace buildWorkspace(ResultSet rs) throws SQLException {
        final String id = rs.getString("id");
        final String displayName = rs.getString("display_name");
        final String metadataJson = rs.getString("metadata");
        final Timestamp createdAt = rs.getTimestamp("created_at");
        return Workspace.builder().id(id).displayName(displayName).metadata(decodeMetadata(metadataJson))
                .createdAt(createdAt.toInstant()).build();
    }

    private String encodeMetadata(Map<String, String> metadata) {
        try {
            return mapper.writeValueAsString(metadata);
        } catch (IOException e) {
            throw new AimonException("Failed to encode workspace metadata", e);
        }
    }

    private Map<String, String> decodeMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            final Map<String, String> raw = mapper.readValue(json, METADATA_TYPE);
            return raw == null ? Map.of() : Map.copyOf(raw);
        } catch (IOException e) {
            throw new AimonException("Failed to decode workspace metadata", e);
        }
    }
}
