package at.aimon.session.routing;

/**
 * What a draining node is still holding, as counted by {@link DrainHandle#snapshot()}.
 *
 * <p>
 * Counts are read one after another without a common lock, so a snapshot taken while work is finishing may be
 * momentarily inconsistent (a turn already uncounted whose lease is not yet returned). {@link #isQuiescent()} is
 * {@code true} only when every blocking count reads zero.
 *
 * <p>
 * "Drain" here always means the node drain begun by {@link SessionRouter#beginDrain()}. The pass that collects and runs
 * a session's queued inbox messages is an <em>inbox pass</em>.
 */
public final class DrainSnapshot {

    private final boolean draining;
    private final int inFlightSubmits;
    private final int inFlightTurns;
    private final int heldTurnGates;
    private final int heldLeases;
    private final int owedDoorbellRelays;
    private final int queuedHandOvers;
    private final int pendingForwards;
    private final boolean forwardsRunHere;

    private DrainSnapshot(Builder builder) {
        this.draining = builder.draining;
        this.inFlightSubmits = builder.inFlightSubmits;
        this.inFlightTurns = builder.inFlightTurns;
        this.heldTurnGates = builder.heldTurnGates;
        this.heldLeases = builder.heldLeases;
        this.owedDoorbellRelays = builder.owedDoorbellRelays;
        this.queuedHandOvers = builder.queuedHandOvers;
        this.pendingForwards = builder.pendingForwards;
        this.forwardsRunHere = builder.forwardsRunHere;
    }

    /**
     * @return a builder; the router fills it, application code only reads snapshots
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return whether the node has begun draining
     */
    public boolean isDraining() {
        return draining;
    }

    /**
     * @return {@link SessionRouter#submit} calls still in progress on this node — each one may yet start a turn here,
     *         queue its input or register a forward
     */
    public int getInFlightSubmits() {
        return inFlightSubmits;
    }

    /**
     * @return turns and inbox passes counted in flight on this node (the counter {@code closeGracefully} waits on)
     */
    public int getInFlightTurns() {
        return inFlightTurns;
    }

    /**
     * @return sessions whose node-local turn gate is held — a turn, an inbox pass, a delete or a lease hand-over in
     *         progress
     */
    public int getHeldTurnGates() {
        return heldTurnGates;
    }

    /**
     * @return session leases this node still holds; each goes back when its session's running turn ends
     */
    public int getHeldLeases() {
        return heldLeases;
    }

    /**
     * @return sessions whose doorbell this node took on as holder and has not yet relayed to a peer; a relay whose
     *         publish failed stays counted here until the router's periodic retry succeeds
     */
    public int getOwedDoorbellRelays() {
        return owedDoorbellRelays;
    }

    /**
     * @return drain hand-overs (a held session's lease return and doorbell relay) dispatched to a worker thread and not
     *         finished yet
     */
    public int getQueuedHandOvers() {
        return queuedHandOvers;
    }

    /**
     * @return forwarded turns this node is waiting on for its callers; part of quiescence only when
     *         {@link #isForwardsRunHere()}
     */
    public int getPendingForwards() {
        return pendingForwards;
    }

    /**
     * Whether the forwards in {@link #getPendingForwards()} are turns this node itself still has to run — {@code true}
     * on a {@code SINGLE_NODE} router, where no peer exists and every forward is a message queued in this node's inbox.
     * In that case they block quiescence; on a {@code DISTRIBUTED} router they are callers waiting on turns other nodes
     * run, and do not.
     *
     * @return whether pending forwards count toward quiescence
     */
    public boolean isForwardsRunHere() {
        return forwardsRunHere;
    }

    /**
     * @return {@code true} when draining and no submit, turn, held gate, lease, owed relay or queued hand-over — nor,
     *         when {@link #isForwardsRunHere()}, forward — remains
     */
    public boolean isQuiescent() {
        return draining && inFlightSubmits == 0 && inFlightTurns == 0 && heldTurnGates == 0 && heldLeases == 0
                && owedDoorbellRelays == 0 && queuedHandOvers == 0 && (!forwardsRunHere || pendingForwards == 0);
    }

    @Override
    public String toString() {
        return "DrainSnapshot{draining=" + draining + ", inFlightSubmits=" + inFlightSubmits + ", inFlightTurns="
                + inFlightTurns + ", heldTurnGates=" + heldTurnGates + ", heldLeases=" + heldLeases
                + ", owedDoorbellRelays=" + owedDoorbellRelays + ", queuedHandOvers=" + queuedHandOvers
                + ", pendingForwards=" + pendingForwards + ", forwardsRunHere=" + forwardsRunHere + '}';
    }

    /** Builder for {@link DrainSnapshot}. */
    public static final class Builder {
        private boolean draining;
        private int inFlightSubmits;
        private int inFlightTurns;
        private int heldTurnGates;
        private int heldLeases;
        private int owedDoorbellRelays;
        private int queuedHandOvers;
        private int pendingForwards;
        private boolean forwardsRunHere;

        private Builder() {
        }

        /**
         * @param v
         *            whether the node has begun draining
         * @return this builder
         */
        public Builder draining(boolean v) {
            this.draining = v;
            return this;
        }

        /**
         * @param v
         *            submit calls still in progress
         * @return this builder
         */
        public Builder inFlightSubmits(int v) {
            this.inFlightSubmits = v;
            return this;
        }

        /**
         * @param v
         *            turns and inbox passes counted in flight
         * @return this builder
         */
        public Builder inFlightTurns(int v) {
            this.inFlightTurns = v;
            return this;
        }

        /**
         * @param v
         *            sessions whose turn gate is held
         * @return this builder
         */
        public Builder heldTurnGates(int v) {
            this.heldTurnGates = v;
            return this;
        }

        /**
         * @param v
         *            session leases still held
         * @return this builder
         */
        public Builder heldLeases(int v) {
            this.heldLeases = v;
            return this;
        }

        /**
         * @param v
         *            doorbell relays still owed
         * @return this builder
         */
        public Builder owedDoorbellRelays(int v) {
            this.owedDoorbellRelays = v;
            return this;
        }

        /**
         * @param v
         *            drain hand-overs dispatched and not finished
         * @return this builder
         */
        public Builder queuedHandOvers(int v) {
            this.queuedHandOvers = v;
            return this;
        }

        /**
         * @param v
         *            forwarded turns still awaited
         * @return this builder
         */
        public Builder pendingForwards(int v) {
            this.pendingForwards = v;
            return this;
        }

        /**
         * @param v
         *            whether pending forwards are turns this node runs itself (and so block quiescence)
         * @return this builder
         */
        public Builder forwardsRunHere(boolean v) {
            this.forwardsRunHere = v;
            return this;
        }

        /**
         * @return the snapshot
         */
        public DrainSnapshot build() {
            return new DrainSnapshot(this);
        }
    }
}
