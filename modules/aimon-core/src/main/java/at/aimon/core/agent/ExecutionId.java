package at.aimon.core.agent;

import java.util.Objects;
import java.util.UUID;

import at.aimon.core.agent.session.SessionId;

/**
 * Correlation id for one run that has no session &mdash; a subagent fork, a skill fork, a rewake hook replay, a
 * scheduled routine.
 *
 * <p>
 * This type exists to give those runs an honest identifier. Their common shape is that they execute agent work but
 * nobody is talking to them: there is no durable {@code SessionRecord} to restore, no lease to hold, and no user who
 * could recognise the id if it were shown to them. Minting a {@link SessionId} for such a run &mdash; which is what
 * the code did before this type existed &mdash; makes it indistinguishable from a real user session, so anything
 * reading the session id gets a value that looks authoritative and is not.
 *
 * <p>
 * The contract, in four parts:
 *
 * <ul>
 * <li><b>Node-local.</b> Meaningful only inside the process that minted it. Two nodes may hold ids that collide
 * without consequence, because nothing resolves an execution id to shared state.
 * <li><b>Not a durable identity.</b> No durable store is keyed by an execution id, so it is not a handle through which
 * a run can be restored, and a value that must outlive the process belongs on the session record instead. The id can
 * nonetheless be <em>written down</em>, and on one path it is: a subagent fork's execution id becomes the label of the
 * fork's transcript, which is serialized into the durable task snapshot and read back with {@link #of(String)} when
 * the fork resumes (design &sect;6-4). That snapshot is addressed by task id and never by the label, so what survives
 * the restart is a name for the run rather than a key to anything &mdash; which is the part that has to hold.
 * <li><b>No lease.</b> Holding one grants nothing. It is not a lock key, and taking a lease on a run identifier is
 * exactly the mistake this type prevents.
 * <li><b>Not forwarded.</b> A nested run mints its own; it does not inherit its parent's. This is the opposite of
 * {@link at.aimon.core.tools.ToolContextKeys#INVOKING_SESSION_ID}, whose whole purpose is to be passed down
 * unchanged so a fork can name the session it acts for.
 * </ul>
 *
 * <p>
 * Use it for correlation &mdash; log lines, trace attribution, partitioning per-run state so two concurrent forks
 * do not share a bucket. Do not use it to answer "which session is this?"; that question has two legitimate
 * answers, {@link at.aimon.core.tools.ToolContextKeys#SESSION_ID} for the reader's own session and
 * {@link at.aimon.core.tools.ToolContextKeys#INVOKING_SESSION_ID} for the session on whose behalf it runs, and for
 * a run identified by an execution id the honest answer to the first is "none".
 *
 * <p>
 * Immutable and safe to use as a map key.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     ExecutionId id = ExecutionId.generate();
 *     ExecutionId labelled = ExecutionId.of("rewake:" + envelopeId + ":" + attemptNumber);
 *
 *     // Validation happens at construction time
 *     ExecutionId.of(""); // throws IllegalArgumentException
 *     ExecutionId.of(null); // throws NullPointerException
 * }
 * </pre>
 *
 * @see SessionId
 * @see at.aimon.core.tools.ToolContextKeys#EXECUTION_ID
 */
public final class ExecutionId {

    private final String value;

    /**
     * Creates a new execution id with validation.
     *
     * @param value
     *            the execution identifier value (must not be null or blank)
     * @throws NullPointerException
     *             if value is null
     * @throws IllegalArgumentException
     *             if value is blank
     */
    public ExecutionId(String value) {
        Objects.requireNonNull(value, "Execution id cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Execution id cannot be blank");
        }
        this.value = value;
    }

    /**
     * Creates an execution id from a caller-supplied value.
     *
     * <p>
     * Prefer {@link #generate()} unless the run has a natural name that aids correlation &mdash; a rewake envelope id
     * paired with its attempt number, for instance. A caller-supplied value carries no uniqueness guarantee, so do not
     * use one as a partitioning key unless the source is itself unique per run: an envelope id or a scheduled task id
     * on its own names the recurring thing, not the fire.
     *
     * @param value
     *            the execution id value (must not be null or blank)
     * @return a new execution id (never null)
     */
    public static ExecutionId of(String value) {
        return new ExecutionId(value);
    }

    /**
     * Generates a random execution id.
     *
     * @return a new execution id backed by a random UUID (never null)
     */
    public static ExecutionId generate() {
        return new ExecutionId(UUID.randomUUID().toString());
    }

    /**
     * Generates a random execution id behind a human-readable prefix, as in {@code routine:nightly-report:<uuid>}.
     *
     * <p>
     * The prefix is for the human reading the log; the random tail is what makes the id unique per run. Prefer this
     * over {@link #of(String)} whenever the run has a recurring name &mdash; a scheduled task fires many times, so its
     * task id alone would collide across fires.
     *
     * @param prefix
     *            a short label naming the kind of run (must not be null or blank)
     * @return a new execution id of the form {@code <prefix>:<uuid>} (never null)
     */
    public static ExecutionId generate(String prefix) {
        Objects.requireNonNull(prefix, "Execution id prefix cannot be null");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("Execution id prefix cannot be blank");
        }
        return new ExecutionId(prefix + ":" + UUID.randomUUID());
    }

    /**
     * Returns the execution id value.
     *
     * @return the identifier value (never null or blank)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ExecutionId that = (ExecutionId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
