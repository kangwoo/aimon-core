package at.aimon.core.agent.stream;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurnId;

/**
 * Signals that the agent loop has suspended the current turn awaiting out-of-band approval for one or more
 * {@code Skill} tool_uses (SK-11.4).
 *
 * <p>
 * <b>Use when:</b> the pre-flight scan ({@link at.aimon.core.skill.policy.SkillPreflightScanner}) returned
 * {@link at.aimon.core.skill.policy.SkillPreflightScanResult#shouldSuspend()}. The agent loop performs an "atomic
 * suspension" — neither the assistant message nor any tool_result for the in-flight LLM iteration is committed to
 * {@code TranscriptBuffer} — and the executor returns a result with
 * {@link at.aimon.core.agent.budget.CompletionReason#SUSPENDED}. This event lets the approval channel (REPL prompt,
 * web UI, etc.) discover the new pending turn and surface it to the user.
 *
 * <p>
 * Extra fields:
 * <ul>
 * <li>{@link #getPendingTurnId()} — opaque id the user references in {@code /approve} / {@code /deny}.</li>
 * <li>{@link #getPendingSkills()} — defensively-copied immutable list of pending requests for display.</li>
 * </ul>
 *
 * <p>
 * Immutable value object.
 */
public final class SkillTurnSuspendedEvent extends AgentExecutionEvent {

    private final PendingTurnId pendingTurnId;
    private final List<PendingSkillRequest> pendingSkills;

    private SkillTurnSuspendedEvent(Builder builder) {
        super(Objects.requireNonNull(builder.timestamp, "Timestamp cannot be null"),
                Objects.requireNonNull(builder.agentRuntimeId, "AgentRuntimeId cannot be null"), builder.iteration);
        this.pendingTurnId = Objects.requireNonNull(builder.pendingTurnId, "pendingTurnId cannot be null");
        Objects.requireNonNull(builder.pendingSkills, "pendingSkills cannot be null");
        if (builder.pendingSkills.isEmpty()) {
            throw new IllegalArgumentException("pendingSkills cannot be empty");
        }
        this.pendingSkills = List.copyOf(builder.pendingSkills);
    }

    public static Builder builder() {
        return new Builder();
    }

    public PendingTurnId getPendingTurnId() {
        return pendingTurnId;
    }

    public List<PendingSkillRequest> getPendingSkills() {
        return pendingSkills;
    }

    @Override
    protected String eventName() {
        return "SkillTurnSuspendedEvent";
    }

    @Override
    protected String detailString() {
        return "pendingTurnId=" + pendingTurnId + ", pendingSkills=" + pendingSkills.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SkillTurnSuspendedEvent that = (SkillTurnSuspendedEvent) o;
        return getIteration() == that.getIteration() && getTimestamp().equals(that.getTimestamp())
                && getAgentRuntimeId().equals(that.getAgentRuntimeId()) && pendingTurnId.equals(that.pendingTurnId)
                && pendingSkills.equals(that.pendingSkills);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTimestamp(), getAgentRuntimeId(), getIteration(), pendingTurnId, pendingSkills);
    }

    /** Builder for {@link SkillTurnSuspendedEvent}. */
    public static final class Builder {
        private Instant timestamp;
        private AgentRuntimeId agentRuntimeId;
        private int iteration;
        private PendingTurnId pendingTurnId;
        private List<PendingSkillRequest> pendingSkills;

        private Builder() {
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder agentRuntimeId(AgentRuntimeId agentRuntimeId) {
            this.agentRuntimeId = agentRuntimeId;
            return this;
        }

        public Builder iteration(int iteration) {
            this.iteration = iteration;
            return this;
        }

        public Builder pendingTurnId(PendingTurnId pendingTurnId) {
            this.pendingTurnId = pendingTurnId;
            return this;
        }

        public Builder pendingSkills(List<PendingSkillRequest> pendingSkills) {
            this.pendingSkills = pendingSkills;
            return this;
        }

        public SkillTurnSuspendedEvent build() {
            return new SkillTurnSuspendedEvent(this);
        }
    }
}
