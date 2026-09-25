package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionLogState;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.llm.Message;

/**
 * A session record as a distributed backend materialises it out of storage — the return value of
 * {@code SessionRecordStore.provision} and {@code load} for the Mongo, Postgres and Redis stores.
 *
 * <p>
 * It exists because the mutable {@code SessionRecord} class is unreachable from here on purpose: only
 * {@code at.aimon.core.agent.session.store} may depend on it (the sole-writer invariant, enforced by
 * {@code SessionRecordSoleWriterArchitectureTest}), and a transport that reached for it would be writing records
 * outside the one package allowed to. {@link SessionRecordView} is the type the SPI actually returns, and this is the
 * three backends' shared implementation of it — the same reason {@link StoredAgentExecutionResult} is shared rather
 * than copied three times.
 *
 * <p>
 * Immutable, and a snapshot rather than a handle: it is built from one read and never re-reads. A caller holding one
 * across a write of its own is looking at the record as it stood, which is exactly what a view is for.
 */
public final class StoredSessionRecord implements SessionRecordView {

    private final SessionId id;
    private final String systemPrompt;
    private final SessionLogState logState;
    private final String agentRef;
    private final int compactionFailureCount;
    private final SessionTotals sessionTotals;
    private final ExecutionBudget budgetOverride;

    private StoredSessionRecord(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "Session id cannot be null");
        this.systemPrompt = builder.systemPrompt;
        this.logState = builder.logState == null ? SessionLogState.empty() : builder.logState;
        this.agentRef = builder.agentRef;
        this.compactionFailureCount = Math.max(0, builder.compactionFailureCount);
        this.sessionTotals = builder.sessionTotals == null ? SessionTotals.empty() : builder.sessionTotals;
        this.budgetOverride = builder.budgetOverride;
    }

    /**
     * Starts building a record for {@code id}.
     *
     * @param id
     *            the session id (must not be null)
     * @return a new builder
     */
    public static Builder builder(SessionId id) {
        return new Builder(id);
    }

    /**
     * The record a backend reports for a session that has just been provisioned and holds nothing else yet.
     *
     * <p>
     * Every side field is at its default and the transcript is empty — which is the truth about a record established
     * by the claim path before the session's first turn, not a stand-in for a failed read.
     *
     * @param id
     *            the session id (must not be null)
     * @param agentRef
     *            the agent binding, or {@code null} when the record is provisioned unbound
     * @return the record, never null
     */
    public static StoredSessionRecord empty(SessionId id, String agentRef) {
        return builder(id).agentRef(agentRef).build();
    }

    @Override
    public SessionId getId() {
        return id;
    }

    @Override
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public List<Message> getMessages() {
        return logState.getMessages();
    }

    @Override
    public SessionLogState getLogState() {
        return logState;
    }

    @Override
    public Optional<String> getAgentRef() {
        return Optional.ofNullable(agentRef);
    }

    @Override
    public int getCompactionFailureCount() {
        return compactionFailureCount;
    }

    @Override
    public SessionTotals getSessionTotals() {
        return sessionTotals;
    }

    @Override
    public Optional<ExecutionBudget> getBudgetOverride() {
        return Optional.ofNullable(budgetOverride);
    }

    @Override
    public Optional<SessionRewindPoint> getRewindPoint() {
        return logState.getRewindPoint();
    }

    @Override
    public String toString() {
        return "StoredSessionRecord{id=" + id + ", messages=" + logState.getMessages().size() + ", agentRef=" + agentRef
                + ", compactionFailureCount=" + compactionFailureCount + '}';
    }

    /** Builder for {@link StoredSessionRecord}. */
    public static final class Builder {

        private final SessionId id;
        private String systemPrompt;
        private SessionLogState logState;
        private String agentRef;
        private int compactionFailureCount;
        private SessionTotals sessionTotals;
        private ExecutionBudget budgetOverride;

        private Builder(SessionId id) {
            this.id = Objects.requireNonNull(id, "Session id cannot be null");
        }

        /**
         * Takes the system prompt and the whole log from a decoded transcript.
         *
         * @param snapshot
         *            the decoded transcript (must not be null)
         * @return this builder
         */
        public Builder transcript(SessionSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "Snapshot cannot be null");
            this.systemPrompt = snapshot.getSystemPrompt();
            // The log is taken whole — entries, seqs, rewind point, format. Picking fields out of it here is how a
            // value added to it later would be lost at this hop without anyone noticing.
            this.logState = snapshot.getLogState();
            return this;
        }

        /**
         * @param systemPrompt
         *            the system prompt (may be null)
         * @return this builder
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * Replaces the log with the version-1 log {@code messages} migrate to — no rewind point.
         *
         * @param messages
         *            the message history (may be null, treated as empty)
         * @return this builder
         */
        public Builder messages(List<Message> messages) {
            this.logState = messages == null ? null : SessionLogState.ofMessages(messages);
            return this;
        }

        /**
         * @param agentRef
         *            the agent binding, or null when unbound
         * @return this builder
         */
        public Builder agentRef(String agentRef) {
            this.agentRef = agentRef;
            return this;
        }

        /**
         * @param compactionFailureCount
         *            the consecutive AUTO compaction failure count (negative values are clamped to 0)
         * @return this builder
         */
        public Builder compactionFailureCount(int compactionFailureCount) {
            this.compactionFailureCount = compactionFailureCount;
            return this;
        }

        /**
         * @param sessionTotals
         *            the accumulated totals (may be null, treated as {@link SessionTotals#empty()})
         * @return this builder
         */
        public Builder sessionTotals(SessionTotals sessionTotals) {
            this.sessionTotals = sessionTotals;
            return this;
        }

        /**
         * @param budgetOverride
         *            the runtime budget override, or null for none
         * @return this builder
         */
        public Builder budgetOverride(ExecutionBudget budgetOverride) {
            this.budgetOverride = budgetOverride;
            return this;
        }

        /**
         * @return the built record, never null
         */
        public StoredSessionRecord build() {
            return new StoredSessionRecord(this);
        }
    }
}
