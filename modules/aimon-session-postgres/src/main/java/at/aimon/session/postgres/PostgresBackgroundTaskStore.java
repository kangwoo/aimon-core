package at.aimon.session.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * Postgres-backed {@link BackgroundTaskStore}: the shared-backend metadata store that lets {@code Task.list} / status
 * queries observe background subagent tasks spawned on <em>any</em> instance (subagent design §4).
 *
 * <p>
 * Each task snapshot is one row in {@code background_task}, keyed by {@code task_id}. The owning {@link Principal} is
 * flattened into three nullable columns ({@code owner_type} / {@code owner_id} / {@code owner_display_name}); every
 * other
 * field maps to its own typed column. {@code put} is an {@code INSERT ... ON CONFLICT (task_id) DO UPDATE} upsert.
 *
 * <p>
 * <b>Terminal-guarded transitions.</b> {@link #transition(String, BackgroundTaskState)} and
 * {@link #heartbeat(String, Instant)} are single-statement {@code UPDATE ... WHERE task_id = ? AND state NOT IN
 * (terminal) RETURNING ...} — the guard is evaluated atomically by Postgres, so an unknown task, an already
 * {@link BackgroundTaskState#isTerminal() terminal} task, and a lost race all yield {@link Optional#empty()}. A
 * heartbeat can therefore never resurrect a task that completed or was stopped concurrently. When a transition targets
 * a
 * terminal state the end time is stamped from {@link Clock#instant()}.
 *
 * <p>
 * {@link #list(TaskQuery)} fetches every row, decodes each snapshot, and returns those the query matches — mirroring
 * the
 * reference stores so filter semantics stay identical across backends. All backend errors surface as
 * {@link IllegalStateException} — the JDBC {@link SQLException} never crosses the module boundary.
 */
public final class PostgresBackgroundTaskStore implements BackgroundTaskStore {

    private static final String COLUMNS = "task_id, subagent_name, description, state, start_time, end_time, "
            + "output_offset, owner_type, owner_id, owner_display_name, context_id, last_heartbeat";

    private static final String TERMINAL_GUARD = "state NOT IN ('COMPLETED', 'FAILED', 'KILLED')";

    private static final String SQL_UPSERT = "INSERT INTO background_task (" + COLUMNS + ") "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " + "ON CONFLICT (task_id) DO UPDATE SET "
            + "subagent_name = EXCLUDED.subagent_name, description = EXCLUDED.description, state = EXCLUDED.state, "
            + "start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time, "
            + "output_offset = EXCLUDED.output_offset, owner_type = EXCLUDED.owner_type, "
            + "owner_id = EXCLUDED.owner_id, owner_display_name = EXCLUDED.owner_display_name, "
            + "context_id = EXCLUDED.context_id, last_heartbeat = EXCLUDED.last_heartbeat";

    private static final String SQL_FIND = "SELECT " + COLUMNS + " FROM background_task WHERE task_id = ?";

    private static final String SQL_LIST = "SELECT " + COLUMNS + " FROM background_task";

    private static final String SQL_TRANSITION_TERMINAL = "UPDATE background_task SET state = ?, end_time = ? "
            + "WHERE task_id = ? AND " + TERMINAL_GUARD + " RETURNING " + COLUMNS;

    private static final String SQL_TRANSITION_NONTERMINAL = "UPDATE background_task SET state = ? "
            + "WHERE task_id = ? AND " + TERMINAL_GUARD + " RETURNING " + COLUMNS;

    private static final String SQL_HEARTBEAT = "UPDATE background_task SET last_heartbeat = ? "
            + "WHERE task_id = ? AND " + TERMINAL_GUARD + " RETURNING " + COLUMNS;

    private static final String SQL_REMOVE = "DELETE FROM background_task WHERE task_id = ?";

    private final DataSource dataSource;
    private final Clock clock;

    /**
     * Creates a store with the system UTC clock.
     *
     * @param dataSource
     *            the JDBC data source (must not be null; owned by the caller)
     */
    public PostgresBackgroundTaskStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /**
     * Creates a store with an explicit clock (used to stamp terminal transition end times).
     *
     * @param dataSource
     *            the JDBC data source (must not be null; owned by the caller)
     * @param clock
     *            the clock used to stamp terminal transition end times (must not be null)
     */
    public PostgresBackgroundTaskStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void put(BackgroundTask task) {
        Objects.requireNonNull(task, "task cannot be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_UPSERT)) {
            bindTask(ps, task);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during put for task " + task.getTaskId(), e);
        }
    }

    @Override
    public Optional<BackgroundTask> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND)) {
            ps.setString(1, taskId);
            return firstRow(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during find for task " + taskId, e);
        }
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        final List<BackgroundTask> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_LIST);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final BackgroundTask task = buildTask(rs);
                if (query.matches(task)) {
                    result.add(task);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during list", e);
        }
        return result;
    }

    @Override
    public Optional<BackgroundTask> transition(String taskId, BackgroundTaskState to) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(to, "target state cannot be null");
        final boolean terminal = to.isTerminal();
        final String sql = terminal ? SQL_TRANSITION_TERMINAL : SQL_TRANSITION_NONTERMINAL;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, to.name());
            if (terminal) {
                ps.setTimestamp(2, Timestamp.from(clock.instant()));
                ps.setString(3, taskId);
            } else {
                ps.setString(2, taskId);
            }
            return firstRow(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during transition for task " + taskId, e);
        }
    }

    @Override
    public Optional<BackgroundTask> heartbeat(String taskId, Instant at) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(at, "heartbeat instant cannot be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_HEARTBEAT)) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setString(2, taskId);
            return firstRow(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during heartbeat for task " + taskId, e);
        }
    }

    @Override
    public void remove(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_REMOVE)) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Postgres error during remove for task " + taskId, e);
        }
    }

    private static void bindTask(PreparedStatement ps, BackgroundTask task) throws SQLException {
        ps.setString(1, task.getTaskId());
        ps.setString(2, task.getSubagentName());
        ps.setString(3, task.getDescription());
        ps.setString(4, task.getState().name());
        ps.setTimestamp(5, Timestamp.from(task.getStartTime()));
        ps.setTimestamp(6, task.getEndTime().map(Timestamp::from).orElse(null));
        ps.setLong(7, task.getOutputOffset());
        final Optional<Principal> owner = task.getOwner();
        ps.setString(8, owner.map(p -> p.getType().name()).orElse(null));
        ps.setString(9, owner.map(Principal::getId).orElse(null));
        ps.setString(10, owner.map(Principal::getDisplayName).orElse(null));
        ps.setString(11, task.getAgentRuntimeId().map(AgentRuntimeId::value).orElse(null));
        ps.setTimestamp(12, task.getLastHeartbeat().map(Timestamp::from).orElse(null));
    }

    private Optional<BackgroundTask> firstRow(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(buildTask(rs));
        }
    }

    private BackgroundTask buildTask(ResultSet rs) throws SQLException {
        final BackgroundTask.Builder b = BackgroundTask.builder().taskId(rs.getString("task_id"))
                .subagentName(rs.getString("subagent_name")).description(rs.getString("description"))
                .state(BackgroundTaskState.valueOf(rs.getString("state")))
                .startTime(rs.getTimestamp("start_time").toInstant()).outputOffset(rs.getLong("output_offset"));
        final Timestamp endTime = rs.getTimestamp("end_time");
        if (endTime != null) {
            b.endTime(endTime.toInstant());
        }
        final String ownerType = rs.getString("owner_type");
        if (ownerType != null) {
            b.owner(Principal.builder().type(Principal.Type.valueOf(ownerType)).id(rs.getString("owner_id"))
                    .displayName(rs.getString("owner_display_name")).build());
        }
        final String contextId = rs.getString("context_id");
        if (contextId != null) {
            b.agentRuntimeId(AgentRuntimeId.of(contextId));
        }
        final Timestamp lastHeartbeat = rs.getTimestamp("last_heartbeat");
        if (lastHeartbeat != null) {
            b.lastHeartbeat(lastHeartbeat.toInstant());
        }
        return b.build();
    }
}
