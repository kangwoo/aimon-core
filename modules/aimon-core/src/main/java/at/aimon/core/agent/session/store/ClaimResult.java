package at.aimon.core.agent.session.store;

import java.util.Objects;
import java.util.Optional;

/**
 * The three outcomes of {@link SessionStore#claim}.
 *
 * <p>
 * This type exists to close a leak in the shape it replaces. Previously a caller ran binding validation
 * ({@code BindingResolver.resolveAndValidate}) and holder election ({@code ConversationLock.tryAcquire}) as two
 * independent steps, in that order — so the agent binding was checked by a node that had not yet won the right to act
 * on
 * the answer, and two nodes could validate against the same "unbound" state before either of them wrote a binding. A
 * single call that returns one of these three answers cannot be interleaved that way.
 *
 * <p>
 * Sealed rather than an enum-plus-nullable-fields because each outcome carries different data and callers should be
 * forced to handle all three. Use pattern matching:
 *
 * <pre>{@code
 * final ClaimResult result = store.claim(id, agentRef, holderId, lease);
 * if (result instanceof ClaimResult.Acquired acquired) {
 *     runTurn(acquired.getLease(), acquired.getRecord());
 * } else if (result instanceof ClaimResult.HeldElsewhere held) {
 *     forwardToInbox(held.getHolder());
 * } else if (result instanceof ClaimResult.AgentConflict conflict) {
 *     throw new ConflictingAgentException(conflict.getBoundAgentRef(), conflict.getRequestedAgentRef());
 * }
 * }</pre>
 */
public sealed interface ClaimResult permits ClaimResult.Acquired, ClaimResult.HeldElsewhere, ClaimResult.AgentConflict {

    /**
     * The caller is now the holder, the record exists, and the agent binding agrees with the request.
     *
     * <p>
     * The record is guaranteed to exist because {@code claim} provisions it when absent, in the same call that binds
     * it. That is the point of folding provisioning into election: the remaining record side-field write
     * ({@code setTotalsAndBudgetOverride}) is a documented no-op when no record exists, so without it a value written
     * on turn one could be silently dropped.
     */
    final class Acquired implements ClaimResult {

        private final SessionLease lease;
        private final SessionRecordView record;

        public Acquired(SessionLease lease, SessionRecordView record) {
            this.lease = Objects.requireNonNull(lease, "lease must not be null");
            this.record = Objects.requireNonNull(record, "record must not be null");
        }

        public SessionLease getLease() {
            return lease;
        }

        /**
         * The record as it stood when the lease was won, read-only. Callers that need to mutate go through
         * {@link SessionStore#records()} so the write is fenced against this lease.
         */
        public SessionRecordView getRecord() {
            return record;
        }

        @Override
        public String toString() {
            return "Acquired{" + lease + '}';
        }
    }

    /**
     * Another holder owns the session. The caller must not touch the record.
     */
    final class HeldElsewhere implements ClaimResult {

        private final LeaseHolder holder;

        public HeldElsewhere(LeaseHolder holder) {
            this.holder = holder;
        }

        /**
         * Who beat us to it, when that is still observable.
         *
         * <p>
         * Empty is a real and expected outcome, not a defect: the identity is read a moment <em>after</em> the failed
         * acquire, so a holder that released in between leaves nothing to report. Returning an empty {@code Optional}
         * says that honestly instead of inventing a holder name. Callers that only need "not me" — the submit path,
         * which
         * forwards to the inbox either way — can ignore this entirely; callers that want to address a hand-off at the
         * holder must handle its absence by retrying the claim.
         */
        public Optional<LeaseHolder> getHolder() {
            return Optional.ofNullable(holder);
        }

        @Override
        public String toString() {
            return "HeldElsewhere{" + (holder == null ? "holder=unobserved" : holder) + '}';
        }
    }

    /**
     * The session is already bound to a different agent than the one requested.
     *
     * <p>
     * The lease was won and then returned before this result was produced, so a rejected claim does not pin the
     * session for the rest of the lease period.
     */
    final class AgentConflict implements ClaimResult {

        private final String boundAgentRef;
        private final String requestedAgentRef;

        public AgentConflict(String boundAgentRef, String requestedAgentRef) {
            this.boundAgentRef = Objects.requireNonNull(boundAgentRef, "boundAgentRef must not be null");
            this.requestedAgentRef = Objects.requireNonNull(requestedAgentRef, "requestedAgentRef must not be null");
        }

        public String getBoundAgentRef() {
            return boundAgentRef;
        }

        public String getRequestedAgentRef() {
            return requestedAgentRef;
        }

        @Override
        public String toString() {
            return "AgentConflict{bound=" + boundAgentRef + ", requested=" + requestedAgentRef + '}';
        }
    }
}
