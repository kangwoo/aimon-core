package at.aimon.memory.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.knowledge.IndexOptions;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeSource;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.memory.index.KnowledgeStoreObservationIndex;
import at.aimon.memory.postgres.internal.OutboxStagingFileSystem;

/**
 * Polling worker that drains {@code mem_outbox} and dispatches each claimed
 * row to a {@link KnowledgeStore} — the read side of the outbox pattern from
 * design doc §5.2.
 *
 * <p>
 * Writes happen elsewhere: {@code PostgresObservationStore.save / delete /
 * merge} insert into {@code mem_outbox} <i>inside the same JDBC transaction</i>
 * as the metadata mutation, so the outbox row is durably published only if the
 * metadata change commits. This relay then asynchronously moves those rows
 * into the embedding/vector index, decoupling the hot path from KnowledgeStore
 * latency or availability.
 *
 * <h2>At-least-once semantics</h2>
 * <p>
 * A claim is held via {@code claimed_by} / {@code claimed_until}; a successful
 * dispatch deletes the row, a failed one resets the claim and bumps
 * {@code next_attempt_at}. If a worker crashes between dispatch and delete the
 * row will be re-claimed when its claim expires and re-dispatched. The
 * {@link KnowledgeStore#reindex} contract is idempotent (clears the scope and
 * re-emits all staged documents) so duplicate dispatches converge to the same
 * end state.
 *
 * <h2>SKIP LOCKED isolation</h2>
 * <p>
 * Claims use {@code SELECT ... FOR UPDATE SKIP LOCKED} so multiple relay
 * processes scaled out on the same database never block each other and never
 * double-claim a row.
 *
 * <h2>Backoff and poison pill</h2>
 * <p>
 * Failed rows are retried with capped exponential backoff: the row's
 * {@code next_attempt_at} is set to {@code now() + min(60, 2^attempt) seconds}.
 * After {@link RelayOptions#getMaxAttempts()} consecutive failures the row is
 * marked with {@code claimed_by = 'POISON'} and {@code claimed_until} pinned
 * far into the future so it stays in the table for forensics but is excluded
 * from drain scans.
 *
 * <h2>Lifecycle</h2>
 * <p>
 * The relay is intended to be application-scoped and long-lived. {@link #start()}
 * spawns a single daemon thread that loops {@link #drainOnce()} then sleeps
 * {@link RelayOptions#getPollIntervalMillis()}. {@link #stop()} interrupts the
 * thread and joins it. Calling {@link #drainOnce()} directly from a test or
 * from a custom scheduler is also supported.
 *
 * @see RelayOptions
 * @see DrainResult
 */
public final class KnowledgeStoreOutboxRelay implements AutoCloseable {

    /**
     * Sentinel value written to {@code claimed_by} for rows that have exceeded
     * {@link RelayOptions#getMaxAttempts()} so they are visibly distinct from a
     * regular in-flight claim.
     */
    public static final String POISON_CLAIM = "POISON";

    /** Cap for the exponential backoff, in seconds. */
    private static final int MAX_BACKOFF_SECONDS = 60;

    /** Pinned claim duration for poisoned rows (~100 years, in days). */
    private static final int POISON_CLAIM_DAYS = 36500;

    private static final String SQL_CLAIM_SELECT = "SELECT id, workspace_id, observation_local_id, subject_key, "
            + "operation, payload, attempt_count FROM mem_outbox "
            + "WHERE next_attempt_at <= now() AND (claimed_until IS NULL OR claimed_until < now()) "
            + "ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED";

    private static final String SQL_DELETE_ROW = "DELETE FROM mem_outbox WHERE id = ?";

    private static final String SQL_FAIL_ROW = "UPDATE mem_outbox "
            + "SET attempt_count = attempt_count + 1, last_error = ?, "
            + "next_attempt_at = now() + make_interval(secs => ?), " + "claimed_by = NULL, claimed_until = NULL "
            + "WHERE id = ?";

    private static final String SQL_POISON_ROW = "UPDATE mem_outbox "
            + "SET attempt_count = attempt_count + 1, last_error = ?, " + "claimed_by = ?, "
            + "claimed_until = now() + make_interval(days => ?) " + "WHERE id = ?";

    private static final String SQL_LOAD_SURVIVORS = "SELECT local_id, content FROM mem_observation "
            + "WHERE workspace_id = ? AND subject_principal_type = ? AND subject_principal_id = ? "
            + "AND soft_deleted_at IS NULL";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStoreOutboxRelay.class);

    private final DataSource dataSource;
    private final KnowledgeStore knowledgeStore;
    private final String agentName;
    private final RelayOptions options;
    private final IndexOptions indexOptions = IndexOptions.defaults();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private Thread workerThread;

    /**
     * Convenience constructor — uses
     * {@link KnowledgeStoreObservationIndex#DEFAULT_AGENT_NAME} as the agent
     * name segment of the {@link KnowledgeScope} and {@link RelayOptions#defaults()}.
     *
     * @param dataSource
     *            datasource pointing at the {@code mem_outbox}-bearing schema
     *            (must not be null)
     * @param knowledgeStore
     *            destination knowledge store (must not be null)
     */
    public KnowledgeStoreOutboxRelay(DataSource dataSource, KnowledgeStore knowledgeStore) {
        this(dataSource, knowledgeStore, KnowledgeStoreObservationIndex.DEFAULT_AGENT_NAME, RelayOptions.defaults());
    }

    /**
     * Full constructor.
     *
     * @param dataSource
     *            datasource pointing at the {@code mem_outbox}-bearing schema
     *            (must not be null)
     * @param knowledgeStore
     *            destination knowledge store (must not be null)
     * @param agentName
     *            value used as {@link KnowledgeScope#getAgentName()} (must not
     *            be null or blank)
     * @param options
     *            relay options (must not be null)
     */
    public KnowledgeStoreOutboxRelay(DataSource dataSource, KnowledgeStore knowledgeStore, String agentName,
            RelayOptions options) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore must not be null");
        this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
        if (agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    /**
     * Spawns the daemon poller. Idempotent: a second call while running is a
     * no-op.
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(false, true)) {
                log.debug("Relay already running, ignoring start()");
                return;
            }
            Thread t = new Thread(this::loop, "knowledge-store-outbox-relay-" + options.getNodeId());
            t.setDaemon(true);
            workerThread = t;
            t.start();
            log.info("Outbox relay started: {}", options);
        }
    }

    /**
     * Signals the daemon to stop and joins it (best-effort, with a short
     * timeout so callers don't hang on a stuck dispatch). Idempotent.
     */
    public void stop() {
        Thread t;
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(true, false)) {
                return;
            }
            t = workerThread;
            workerThread = null;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Outbox relay stopped");
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Runs a single drain pass: claims a batch, dispatches each row, then
     * settles them (success → delete, failure → backoff or poison).
     *
     * @return summary counts for the batch
     */
    public DrainResult drainOnce() {
        List<ClaimedRow> claimed = claimBatch();
        if (claimed.isEmpty()) {
            return new DrainResult(0, 0, 0);
        }
        int processed = 0;
        int failed = 0;
        int poisoned = 0;
        for (ClaimedRow row : claimed) {
            DispatchOutcome outcome = settle(row);
            switch (outcome) {
                case SUCCESS :
                    processed++;
                    break;
                case RETRY :
                    failed++;
                    break;
                case POISON :
                    poisoned++;
                    break;
                default :
                    throw new IllegalStateException("unknown outcome: " + outcome);
            }
        }
        return new DrainResult(processed, failed, poisoned);
    }

    private List<ClaimedRow> claimBatch() {
        List<ClaimedRow> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(SQL_CLAIM_SELECT)) {
                ps.setInt(1, options.getPollBatchSize());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ClaimedRow(rs.getLong("id"), rs.getString("workspace_id"),
                                rs.getString("observation_local_id"), rs.getString("subject_key"),
                                rs.getString("operation"), rs.getString("payload"), rs.getInt("attempt_count")));
                    }
                }
            }
            if (rows.isEmpty()) {
                c.commit();
                return List.of();
            }
            // Stamp the claim while still inside the locking transaction so a crashing
            // worker cannot release locks without persisting the claim.
            try (PreparedStatement upd = c.prepareStatement("UPDATE mem_outbox "
                    + "SET claimed_by = ?, claimed_until = now() + make_interval(secs => ?) " + "WHERE id = ?")) {
                for (ClaimedRow r : rows) {
                    upd.setString(1, options.getNodeId());
                    upd.setInt(2, options.getClaimDurationSeconds());
                    upd.setLong(3, r.id);
                    upd.addBatch();
                }
                upd.executeBatch();
            }
            c.commit();
            return rows;
        } catch (SQLException e) {
            log.error("Failed to claim outbox batch: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private DispatchOutcome settle(ClaimedRow row) {
        try {
            dispatch(row);
        } catch (Exception e) {
            return recordFailure(row, e);
        }
        return recordSuccess(row);
    }

    private DispatchOutcome recordSuccess(ClaimedRow row) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE_ROW)) {
            ps.setLong(1, row.id);
            ps.executeUpdate();
            log.debug("Outbox row dispatched + deleted: id={} op={} subject={}", row.id, row.operation, row.subjectKey);
            return DispatchOutcome.SUCCESS;
        } catch (SQLException e) {
            log.error("Failed to delete outbox row {} after successful dispatch: {}", row.id, e.getMessage(), e);
            // The row is still claimed; on claim expiry it will be re-dispatched.
            // KnowledgeStore.reindex is idempotent so this converges.
            return DispatchOutcome.RETRY;
        }
    }

    private DispatchOutcome recordFailure(ClaimedRow row, Exception cause) {
        int newAttempt = row.attemptCount + 1;
        String error = truncateError(cause);
        if (newAttempt >= options.getMaxAttempts()) {
            log.error("Outbox row {} poisoned after {} attempts: {}", row.id, newAttempt, error, cause);
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_POISON_ROW)) {
                ps.setString(1, error);
                ps.setString(2, POISON_CLAIM);
                ps.setInt(3, POISON_CLAIM_DAYS);
                ps.setLong(4, row.id);
                ps.executeUpdate();
                return DispatchOutcome.POISON;
            } catch (SQLException e) {
                log.error("Failed to poison outbox row {}: {}", row.id, e.getMessage(), e);
                return DispatchOutcome.RETRY;
            }
        }
        long backoff = computeBackoffSeconds(newAttempt);
        log.warn("Outbox row {} dispatch failed (attempt {} of {}): {} — retrying in {}s", row.id, newAttempt,
                options.getMaxAttempts(), error, backoff);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FAIL_ROW)) {
            ps.setString(1, error);
            ps.setLong(2, backoff);
            ps.setLong(3, row.id);
            ps.executeUpdate();
            return DispatchOutcome.RETRY;
        } catch (SQLException e) {
            log.error("Failed to record failure for outbox row {}: {}", row.id, e.getMessage(), e);
            return DispatchOutcome.RETRY;
        }
    }

    private void dispatch(ClaimedRow row) {
        if (!"UPSERT".equals(row.operation) && !"DELETE".equals(row.operation)) {
            throw new IllegalStateException("Unknown outbox operation: " + row.operation);
        }
        // reindex() clears the whole (agentName, subjectKey) scope and re-emits ONLY what is staged.
        // Staging just this row's payload would therefore drop every OTHER observation for the subject
        // (and a DELETE row, staging nothing, would wipe the subject entirely). Instead rebuild the
        // scope from the current set of surviving (non-soft-deleted) observations for the subject — the
        // changed row is naturally included (UPSERT) or excluded (DELETE/soft-delete). This mirrors the
        // in-process KnowledgeStoreObservationIndex and converges to the authoritative DB state.
        List<SurvivingObservation> survivors = loadSurvivors(row);
        KnowledgeScope scope = new KnowledgeScope(agentName, row.subjectKey);
        OutboxStagingFileSystem vfs = new OutboxStagingFileSystem();
        String stagingDir = "/observations/" + row.subjectKey;
        try {
            for (SurvivingObservation s : survivors) {
                vfs.put(stagingDir + "/" + s.localId + ".txt", s.content == null ? "" : s.content);
            }
            // An empty survivor set leaves the staging dir empty, so reindex correctly clears the scope
            // (the subject's last observation was removed).
            knowledgeStore.reindex(scope, new KnowledgeSource(vfs, stagingDir), indexOptions);
        } finally {
            vfs.close();
        }
    }

    private List<SurvivingObservation> loadSurvivors(ClaimedRow row) {
        String[] subject = splitSubjectKey(row.workspaceId, row.subjectKey);
        if (subject == null) {
            // Cannot rebuild the scope safely — fail the dispatch (retry/poison) rather than wipe it.
            throw new IllegalStateException(
                    "Cannot parse subject_key '" + row.subjectKey + "' for workspace '" + row.workspaceId + "'");
        }
        List<SurvivingObservation> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_LOAD_SURVIVORS)) {
            ps.setString(1, row.workspaceId);
            ps.setString(2, subject[0]);
            ps.setString(3, subject[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SurvivingObservation(rs.getString("local_id"), rs.getString("content")));
                }
            }
            return out;
        } catch (SQLException e) {
            // Propagate so settle() retries instead of wiping the scope on a transient DB error.
            throw new at.aimon.core.base.exception.AimonException(
                    "Failed to load surviving observations for subject " + row.subjectKey, e);
        }
    }

    /**
     * Splits a {@code subject_key} of the form {@code <workspaceId>:<principalType>:<principalId>} into
     * its {@code [principalType, principalId]} parts. The {@code workspaceId} prefix is stripped exactly
     * (it may itself contain colons) and the type is taken up to the first remaining colon (enum names
     * have none), leaving the rest — which may contain colons — as the principal id. Returns
     * {@code null} if the key does not match the expected shape.
     */
    private static String[] splitSubjectKey(String workspaceId, String subjectKey) {
        if (subjectKey == null) {
            return null;
        }
        String prefix = workspaceId + ":";
        if (!subjectKey.startsWith(prefix)) {
            return null;
        }
        String remainder = subjectKey.substring(prefix.length());
        int colon = remainder.indexOf(':');
        if (colon <= 0 || colon == remainder.length() - 1) {
            return null;
        }
        return new String[]{remainder.substring(0, colon), remainder.substring(colon + 1)};
    }

    private void loop() {
        log.debug("Outbox relay loop started on thread {}", Thread.currentThread().getName());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                DrainResult result = drainOnce();
                if (result.getClaimed() > 0) {
                    log.debug("Drain result: {}", result);
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error in outbox relay loop: {}", e.getMessage(), e);
            }
            try {
                Thread.sleep(options.getPollIntervalMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("Outbox relay loop exiting");
    }

    private static long computeBackoffSeconds(int attempt) {
        // 2^attempt grows quickly; cap at MAX_BACKOFF_SECONDS to avoid hour-long
        // gaps that would mask actual failures from operators.
        if (attempt <= 0) {
            return 1L;
        }
        if (attempt >= 31) {
            return MAX_BACKOFF_SECONDS;
        }
        long pow = 1L << attempt;
        return Math.min((long) MAX_BACKOFF_SECONDS, pow);
    }

    private static String truncateError(Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
        if (msg.length() > 4000) {
            return msg.substring(0, 4000);
        }
        return msg;
    }

    /** Per-row dispatch outcome. */
    private enum DispatchOutcome {
        SUCCESS, RETRY, POISON
    }

    /** A surviving (non-soft-deleted) observation's id + content, used to rebuild a subject scope. */
    private static final class SurvivingObservation {
        final String localId;
        final String content;

        SurvivingObservation(String localId, String content) {
            this.localId = localId;
            this.content = content;
        }
    }

    /** Snapshot of a single claimed outbox row. Immutable, package-private. */
    private static final class ClaimedRow {
        final long id;
        final String workspaceId;
        final String observationLocalId;
        final String subjectKey;
        final String operation;
        final String payload;
        final int attemptCount;

        ClaimedRow(long id, String workspaceId, String observationLocalId, String subjectKey, String operation,
                String payload, int attemptCount) {
            this.id = id;
            this.workspaceId = workspaceId;
            this.observationLocalId = observationLocalId;
            this.subjectKey = subjectKey;
            this.operation = operation;
            this.payload = payload;
            this.attemptCount = attemptCount;
        }
    }
}
