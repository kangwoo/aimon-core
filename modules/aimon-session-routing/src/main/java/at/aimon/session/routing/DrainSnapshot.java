package at.aimon.session.routing;

/**
 * What a draining node is still holding, as counted by {@link DrainHandle#snapshot()}.
 *
 * <p>
 * Counts are read one after another without a common lock, so a snapshot taken while work is finishing may be
 * momentarily inconsistent (a turn already uncounted whose lease is not yet returned). {@link #isQuiescent()} is
 * {@code true} only when all four blocking counts read zero.
 */
public final class DrainSnapshot {

    private final boolean draining;
    private final int inFlightTurns;
    private final int activeSessions;
    private final int heldLeases;
    private final int pendingHandOvers;
    private final int pendingForwards;

    private DrainSnapshot(Builder builder) {
        this.draining = builder.draining;
        this.inFlightTurns = builder.inFlightTurns;
        this.activeSessions = builder.activeSessions;
        this.heldLeases = builder.heldLeases;
        this.pendingHandOvers = builder.pendingHandOvers;
        this.pendingForwards = builder.pendingForwards;
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
     * @return turns and drain passes counted in flight on this node (the counter {@code closeGracefully} waits on)
     */
    public int getInFlightTurns() {
        return inFlightTurns;
    }

    /**
     * @return sessions whose node-local turn gate is held — a turn, a drain pass, a delete or a lease hand-over in
     *         progress
     */
    public int getActiveSessions() {
        return activeSessions;
    }

    /**
     * @return session leases this node still holds; each goes back when its session's running turn ends
     */
    public int getHeldLeases() {
        return heldLeases;
    }

    /**
     * @return sessions whose doorbell this node took on as holder and has not yet relayed to a peer
     */
    public int getPendingHandOvers() {
        return pendingHandOvers;
    }

    /**
     * @return forwarded turns this node is waiting on for its callers; informational, not part of quiescence
     */
    public int getPendingForwards() {
        return pendingForwards;
    }

    /**
     * @return {@code true} when draining and no turn, gate, lease or owed hand-over remains
     */
    public boolean isQuiescent() {
        return draining && inFlightTurns == 0 && activeSessions == 0 && heldLeases == 0 && pendingHandOvers == 0;
    }

    @Override
    public String toString() {
        return "DrainSnapshot{draining=" + draining + ", inFlightTurns=" + inFlightTurns + ", activeSessions="
                + activeSessions + ", heldLeases=" + heldLeases + ", pendingHandOvers=" + pendingHandOvers
                + ", pendingForwards=" + pendingForwards + '}';
    }

    /** Builder for {@link DrainSnapshot}. */
    public static final class Builder {
        private boolean draining;
        private int inFlightTurns;
        private int activeSessions;
        private int heldLeases;
        private int pendingHandOvers;
        private int pendingForwards;

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
         *            turns and drain passes counted in flight
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
        public Builder activeSessions(int v) {
            this.activeSessions = v;
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
        public Builder pendingHandOvers(int v) {
            this.pendingHandOvers = v;
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
         * @return the snapshot
         */
        public DrainSnapshot build() {
            return new DrainSnapshot(this);
        }
    }
}
