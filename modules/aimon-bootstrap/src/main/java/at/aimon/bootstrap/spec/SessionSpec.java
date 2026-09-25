package at.aimon.bootstrap.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.inbox.SessionInbox;
import at.aimon.core.agent.session.signal.SessionSignalBus;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionLeaseStore;
import at.aimon.core.agent.session.store.SessionLogSegmentStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.base.ExternallyManaged;
import at.aimon.session.routing.DeploymentMode;

/**
 * Declares how the stack routes and retires live sessions.
 *
 * <p>
 * Defaults describe a single-node deployment with an in-memory record store — the shape a test or a local
 * service wants. Handing in a {@link SessionRecordStore} is what makes sessions survive a restart; every other
 * knob here is about the node-local cache of live handles, which is transient by definition.
 *
 * <p>
 * The one setting that is <b>not</b> about caching is {@link #getDrainTimeout()}: it bounds how long shutdown
 * waits for in-flight turns before releasing session leases. It is the single largest contributor to shutdown
 * wall-clock that a caller can actually tune.
 *
 * <p>
 * <b>Distributed mode.</b> Setting {@link #getMode()} to {@link DeploymentMode#DISTRIBUTED} turns four
 * collaborators from optional into required: a {@link SessionLeaseStore}, a {@link SessionSignalBus}, a
 * {@link SessionInbox} and an {@link IdempotencyStore}. {@code SessionRouterBuilder} already refuses those four
 * when they are missing, and this spec repeats the check only so the message names the spec setter rather than a
 * builder two layers down. What it adds on top is the fifth: the record store, which the router cannot check
 * because it is always set by the time the router sees it — the stack substitutes an in-memory one when the spec
 * leaves it empty. A deployment that distributes leases, signals and inboxes while every node keeps its
 * transcripts on its own heap is strictly worse than single node: routing sends a session to a node that has no
 * idea what was said in it.
 *
 * <p>
 * All five are <b>borrowed</b>. They are marked {@link ExternallyManaged} and the stack closes none of them —
 * whoever built the driver connection underneath closes the thing on top of it. Two of the three shipped signal
 * buses own a thread (Mongo's change-stream watcher, Postgres' LISTEN dispatcher), so "nobody closes it" is not
 * an option; "the stack closes it too" is the option this rejects, because a resource with two destruction edges
 * is closed twice or, worse, closed once from the side that did not create it and still referenced by the side
 * that did.
 */
public final class SessionSpec {

    /** Default grace period for in-flight turns at shutdown. */
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final SessionRecordStore recordStore;
    private final SessionLogSegmentStore segmentStore;
    private final SessionLogFormat logWriteFormat;
    private final Duration segmentSweepInterval;
    private final Duration segmentSweepGrace;
    private final Duration drainTimeout;
    private final Duration idleTtl;
    private final Integer maxCachedSessions;
    private final DeploymentMode mode;
    private final String nodeId;
    private final SessionLeaseStore leaseStore;
    private final SessionSignalBus signalBus;
    private final SessionInbox inbox;
    private final IdempotencyStore idempotencyStore;

    private SessionSpec(Builder builder) {
        this.recordStore = builder.recordStore;
        this.segmentStore = builder.segmentStore;
        this.logWriteFormat = Objects.requireNonNullElse(builder.logWriteFormat, SessionLogFormat.V1);
        this.segmentSweepInterval = builder.segmentSweepInterval;
        this.segmentSweepGrace = builder.segmentSweepGrace;
        this.drainTimeout = Objects.requireNonNullElse(builder.drainTimeout, DEFAULT_DRAIN_TIMEOUT);
        this.idleTtl = builder.idleTtl;
        this.maxCachedSessions = builder.maxCachedSessions;
        this.mode = Objects.requireNonNullElse(builder.mode, DeploymentMode.SINGLE_NODE);
        this.nodeId = builder.nodeId;
        this.leaseStore = builder.leaseStore;
        this.signalBus = builder.signalBus;
        this.inbox = builder.inbox;
        this.idempotencyStore = builder.idempotencyStore;

        if (this.drainTimeout.isNegative()) {
            throw new IllegalArgumentException("drainTimeout must not be negative: " + this.drainTimeout);
        }
        if (this.idleTtl != null && (this.idleTtl.isNegative() || this.idleTtl.isZero())) {
            throw new IllegalArgumentException("idleTtl must be positive: " + this.idleTtl);
        }
        if (this.maxCachedSessions != null && this.maxCachedSessions < 1) {
            throw new IllegalArgumentException("maxCachedSessions must be >= 1: " + this.maxCachedSessions);
        }
        validateSegmentSweep();
        if (this.mode == DeploymentMode.DISTRIBUTED) {
            validateDistributed();
        }
    }

    /**
     * Refuses a sweep that could never run: one with a non-positive interval or grace, and one over a supplied record
     * store with no segment store — the stack seals nothing then, so there would be nothing to sweep, and a setting
     * that is accepted and then ignored is the worse answer.
     */
    private void validateSegmentSweep() {
        if (segmentSweepInterval != null && (segmentSweepInterval.isNegative() || segmentSweepInterval.isZero())) {
            throw new IllegalArgumentException("segmentSweepInterval must be positive: " + segmentSweepInterval);
        }
        if (segmentSweepGrace != null && (segmentSweepGrace.isNegative() || segmentSweepGrace.isZero())) {
            throw new IllegalArgumentException("segmentSweepGrace must be positive: " + segmentSweepGrace);
        }
        if (segmentSweepGrace != null && segmentSweepInterval == null) {
            throw new IllegalArgumentException("segmentSweepGrace is set but segmentSweepInterval is not, so the sweep"
                    + " it configures never runs. Set segmentSweepInterval to enable the sweep.");
        }
        if (segmentSweepInterval != null && segmentStore == null && recordStore != null) {
            throw new IllegalArgumentException("segmentSweepInterval is set over a supplied record store with no"
                    + " segment store, so nothing is ever sealed and there is nothing to sweep. Supply a"
                    + " SessionLogSegmentStore (segmentStore) from the same backend, or drop the sweep.");
        }
    }

    /**
     * Refuses a distributed spec that would run with a node-local default somewhere in it.
     *
     * <p>
     * All of the missing pieces at once rather than the first one found: they arrive from the same decision, and
     * a deployment turning distributed mode on for the first time is usually missing several.
     */
    private void validateDistributed() {
        final List<String> missing = new ArrayList<>();
        if (nodeId == null || nodeId.isBlank()) {
            missing.add("nodeId(...) — the lease holder identity; two nodes sharing one identity each believe they"
                    + " hold the other's sessions");
        }
        if (leaseStore == null) {
            missing.add("leaseStore(...) — without it every node elects itself holder of every session");
        }
        if (signalBus == null) {
            missing.add("signalBus(...) — without it no node can wake or evict a session held elsewhere");
        }
        if (inbox == null) {
            missing.add("inbox(...) — without it a submit that loses the lease has nowhere to forward the turn");
        }
        if (idempotencyStore == null) {
            missing.add("idempotencyStore(...) — without it a retry that lands on another node runs the input twice");
        }
        if (recordStore == null) {
            missing.add("recordStore(...) — without it each node keeps transcripts on its own heap, so routing a"
                    + " session to its holder sends it to a node that has never seen the conversation");
        } else if (recordStore instanceof InMemorySessionRecordStore) {
            missing.add("recordStore(...) is an InMemorySessionRecordStore — leases, signals and inboxes would be"
                    + " distributed while the transcripts stay node-local, which is worse than single node rather"
                    + " than a step towards multi node");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("SessionSpec is in " + DeploymentMode.DISTRIBUTED
                    + " mode but is missing what makes a session reachable from a second node:\n  - "
                    + String.join("\n  - ", missing)
                    + "\nSet each one, or leave the mode at SINGLE_NODE to keep the in-memory defaults.");
        }
    }

    /**
     * Returns the all-defaults spec: in-memory records, single node, router defaults for cache sizing.
     *
     * @return the default spec
     */
    public static SessionSpec defaults() {
        return builder().build();
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the durable session record store, when the caller supplied one.
     *
     * <p>
     * The stack shares a single instance across the transcript manager, every live session, and the router —
     * they all read and write the same records, so a second instance would split a session's message history
     * from its budget override.
     *
     * @return the store, or empty to use an in-memory one
     */
    public Optional<SessionRecordStore> getRecordStore() {
        return Optional.ofNullable(recordStore);
    }

    /**
     * Returns the store sealed ranges of session logs are moved into (session-log §5.6).
     *
     * <p>
     * When absent, the stack pairs an in-memory segment store with an in-memory record store — the two lose everything
     * together — and seals nothing when the record store was supplied, since an in-memory segment store behind a
     * durable record would turn every sealed range into a gap after a restart.
     *
     * @return the store, or empty
     */
    public Optional<SessionLogSegmentStore> getSegmentStore() {
        return Optional.ofNullable(segmentStore);
    }

    /**
     * Returns the session log format this node writes (session-log §7.3).
     *
     * <p>
     * Every build reads both formats; this decides only what is written. It is switched in two steps across a
     * cluster: deploy with {@link SessionLogFormat#V1} until every node runs a build that reads version 2, then switch
     * to {@link SessionLogFormat#V2}. A record already read as version 2 is written as version 2 either way. The
     * version-2 format is what keeps the log append-only, and the rolling context engine requires it: a runtime asking
     * for rolling on a version-1 node fails to build.
     *
     * @return the format, {@link SessionLogFormat#V1} by default
     */
    public SessionLogFormat getLogWriteFormat() {
        return logWriteFormat;
    }

    /**
     * Returns how often this node sweeps the segment store for orphans (session-log §11).
     *
     * <p>
     * Per-session garbage collection runs only when a session is saved, so the orphans of a session nobody resumes —
     * segments a rewind cut, a failed {@code /clear} delete, a sealing never saved, a deleted record whose segment
     * delete failed — stay until this sweep finds them. Off unless set. Safe on every node of a cluster at once (the
     * nodes only repeat each other's work), though one node is enough.
     *
     * @return the interval, or empty when the sweep is off
     */
    public Optional<Duration> getSegmentSweepInterval() {
        return Optional.ofNullable(segmentSweepInterval);
    }

    /**
     * Returns how old an unnamed segment must be before the sweep deletes it.
     *
     * @return the grace, or empty for {@code SessionLogSegmentSweeper.DEFAULT_GRACE} (24 hours)
     */
    public Optional<Duration> getSegmentSweepGrace() {
        return Optional.ofNullable(segmentSweepGrace);
    }

    /**
     * Returns how long shutdown waits for in-flight turns before releasing leases.
     *
     * @return the drain timeout, never null
     */
    public Duration getDrainTimeout() {
        return drainTimeout;
    }

    /**
     * Returns the idle TTL after which a cached live-session handle is evicted.
     *
     * @return the TTL, or empty to keep the router's default
     */
    public Optional<Duration> getIdleTtl() {
        return Optional.ofNullable(idleTtl);
    }

    /**
     * Returns the maximum number of live-session handles cached on this node.
     *
     * @return the cap, or empty to keep the router's default
     */
    public Optional<Integer> getMaxCachedSessions() {
        return Optional.ofNullable(maxCachedSessions);
    }

    /**
     * Returns the deployment topology of the session layer.
     *
     * @return the mode, never null
     */
    public DeploymentMode getMode() {
        return mode;
    }

    /**
     * Returns the stable identity this node claims session leases under.
     *
     * @return the node id, or empty to let the router generate a per-process one
     */
    public Optional<String> getNodeId() {
        return Optional.ofNullable(nodeId);
    }

    /**
     * Returns the holder-election backend.
     *
     * @return the lease store, or empty to use a node-local one
     */
    public Optional<SessionLeaseStore> getLeaseStore() {
        return Optional.ofNullable(leaseStore);
    }

    /**
     * Returns the cross-node signal bus.
     *
     * @return the bus, or empty to use a node-local one
     */
    public Optional<SessionSignalBus> getSignalBus() {
        return Optional.ofNullable(signalBus);
    }

    /**
     * Returns the durable inbox a submit forwards to when another node holds the session.
     *
     * @return the inbox, or empty to use a node-local one
     */
    public Optional<SessionInbox> getInbox() {
        return Optional.ofNullable(inbox);
    }

    /**
     * Returns the store that collapses retried submissions onto one execution.
     *
     * @return the store, or empty to use a node-local one
     */
    public Optional<IdempotencyStore> getIdempotencyStore() {
        return Optional.ofNullable(idempotencyStore);
    }

    /** Builder for {@link SessionSpec}. */
    public static final class Builder {

        private SessionRecordStore recordStore;
        private SessionLogSegmentStore segmentStore;
        private SessionLogFormat logWriteFormat;
        private Duration segmentSweepInterval;
        private Duration segmentSweepGrace;
        private Duration drainTimeout;
        private Duration idleTtl;
        private Integer maxCachedSessions;
        private DeploymentMode mode;
        private String nodeId;
        private SessionLeaseStore leaseStore;
        private SessionSignalBus signalBus;
        private SessionInbox inbox;
        private IdempotencyStore idempotencyStore;

        private Builder() {
        }

        /**
         * Sets the durable session record store.
         *
         * @param recordStore
         *            the store, or null for in-memory
         * @return this builder
         */
        public Builder recordStore(@ExternallyManaged SessionRecordStore recordStore) {
            this.recordStore = recordStore;
            return this;
        }

        /**
         * Sets the store sealed ranges of session logs are moved into. Supply one backed by the same database as the
         * record store — a segment is data moved out of the record and must be kept at least as safely.
         *
         * @param segmentStore
         *            the store, or null (see {@link SessionSpec#getSegmentStore()})
         * @return this builder
         */
        public Builder segmentStore(@ExternallyManaged SessionLogSegmentStore segmentStore) {
            this.segmentStore = segmentStore;
            return this;
        }

        /**
         * Sets the session log format this node writes.
         *
         * @param logWriteFormat
         *            the format, or null for {@link SessionLogFormat#V1} (see {@link SessionSpec#getLogWriteFormat()})
         * @return this builder
         */
        public Builder logWriteFormat(SessionLogFormat logWriteFormat) {
            this.logWriteFormat = logWriteFormat;
            return this;
        }

        /**
         * Turns on the store-wide orphan segment sweep and sets how often it runs.
         *
         * @param segmentSweepInterval
         *            a positive interval, or null to leave the sweep off (see
         *            {@link SessionSpec#getSegmentSweepInterval()})
         * @return this builder
         */
        public Builder segmentSweepInterval(Duration segmentSweepInterval) {
            this.segmentSweepInterval = segmentSweepInterval;
            return this;
        }

        /**
         * Sets how old an unnamed segment must be before the sweep deletes it. Needs
         * {@link #segmentSweepInterval(Duration)}.
         *
         * @param segmentSweepGrace
         *            a positive duration, or null for the sweeper's default
         * @return this builder
         */
        public Builder segmentSweepGrace(Duration segmentSweepGrace) {
            this.segmentSweepGrace = segmentSweepGrace;
            return this;
        }

        /**
         * Sets the shutdown drain timeout.
         *
         * @param drainTimeout
         *            a non-negative duration
         * @return this builder
         */
        public Builder drainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
            return this;
        }

        /**
         * Sets the idle TTL for cached live-session handles.
         *
         * @param idleTtl
         *            a positive duration
         * @return this builder
         */
        public Builder idleTtl(Duration idleTtl) {
            this.idleTtl = idleTtl;
            return this;
        }

        /**
         * Sets the live-session cache cap.
         *
         * @param maxCachedSessions
         *            a value {@code >= 1}
         * @return this builder
         */
        public Builder maxCachedSessions(Integer maxCachedSessions) {
            this.maxCachedSessions = maxCachedSessions;
            return this;
        }

        /**
         * Sets the deployment topology. {@link DeploymentMode#DISTRIBUTED} requires every collaborator below plus
         * a durable {@link #recordStore(SessionRecordStore)}; {@link DeploymentMode#SINGLE_NODE} allows the
         * node-local defaults.
         *
         * @param mode
         *            the mode, or null for single node
         * @return this builder
         */
        public Builder mode(DeploymentMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Sets the stable identity this node claims session leases under — a pod name, a host name, anything that
         * differs between nodes and survives a reconnect on the same one.
         *
         * @param nodeId
         *            the node id, or null to let the router generate one
         * @return this builder
         */
        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        /**
         * Sets the holder-election backend. Borrowed, never closed by the stack.
         *
         * @param leaseStore
         *            the lease store, or null for node-local
         * @return this builder
         */
        public Builder leaseStore(@ExternallyManaged SessionLeaseStore leaseStore) {
            this.leaseStore = leaseStore;
            return this;
        }

        /**
         * Sets the cross-node signal bus. Borrowed, never closed by the stack — two of the three shipped
         * implementations own a thread, so the bean that created it must be the bean that closes it.
         *
         * @param signalBus
         *            the bus, or null for node-local
         * @return this builder
         */
        public Builder signalBus(@ExternallyManaged SessionSignalBus signalBus) {
            this.signalBus = signalBus;
            return this;
        }

        /**
         * Sets the durable inbox a submit forwards to when another node holds the session. Borrowed, never closed
         * by the stack.
         *
         * @param inbox
         *            the inbox, or null for node-local
         * @return this builder
         */
        public Builder inbox(@ExternallyManaged SessionInbox inbox) {
            this.inbox = inbox;
            return this;
        }

        /**
         * Sets the store that collapses retried submissions onto one execution. Borrowed, never closed by the
         * stack.
         *
         * @param idempotencyStore
         *            the store, or null for node-local
         * @return this builder
         */
        public Builder idempotencyStore(@ExternallyManaged IdempotencyStore idempotencyStore) {
            this.idempotencyStore = idempotencyStore;
            return this;
        }

        /**
         * Builds the spec.
         *
         * @return the immutable spec
         */
        public SessionSpec build() {
            return new SessionSpec(this);
        }
    }
}
