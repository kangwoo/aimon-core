package at.aimon.core.skill.policy.pending;

import java.util.Objects;

/**
 * Immutable description of one skill invocation that was pending user approval at the moment a turn was suspended.
 *
 * <p>
 * Carried inside {@link PendingTurn} so the {@code /pending} UI can render meaningful labels and so resume logic can
 * correlate cached decisions back to the original LLM tool_use blocks.
 *
 * <p>
 * Field semantics:
 * <ul>
 * <li>{@code toolUseId} — the LLM-issued {@code tool_use_id} that produced this invocation. Optional because
 * suspensions originating from non-LLM paths (tests, programmatic invocation) may not have one.</li>
 * <li>{@code skillName} — fully qualified skill name (matches {@code Skill#getName()}).</li>
 * <li>{@code args} — raw args string passed to {@code SkillTool}; never null (empty string when none).</li>
 * </ul>
 *
 * <p>
 * {@link #toString()} deliberately leaks {@code argsLen} but never the raw {@code args} content; arguments often carry
 * sensitive data (paths, secrets) and PendingTurn snapshots may surface in audit logs.
 */
public final class PendingSkillRequest {

    private final String toolUseId;
    private final String skillName;
    private final String args;

    private PendingSkillRequest(Builder builder) {
        this.toolUseId = builder.toolUseId;
        this.skillName = Objects.requireNonNull(builder.skillName, "skillName cannot be null");
        this.args = builder.args == null ? "" : builder.args;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the LLM tool_use_id for this invocation, if known.
     */
    public String getToolUseId() {
        return toolUseId;
    }

    public String getSkillName() {
        return skillName;
    }

    public String getArgs() {
        return args;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PendingSkillRequest that = (PendingSkillRequest) o;
        return Objects.equals(toolUseId, that.toolUseId) && skillName.equals(that.skillName) && args.equals(that.args);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolUseId, skillName, args);
    }

    @Override
    public String toString() {
        return "PendingSkillRequest{toolUseId=" + toolUseId + ", skillName=" + skillName + ", argsLen=" + args.length()
                + '}';
    }

    /** Builder for {@link PendingSkillRequest}. */
    public static final class Builder {

        private String toolUseId;
        private String skillName;
        private String args;

        private Builder() {
        }

        public Builder toolUseId(String toolUseId) {
            this.toolUseId = toolUseId;
            return this;
        }

        public Builder skillName(String skillName) {
            this.skillName = skillName;
            return this;
        }

        public Builder args(String args) {
            this.args = args;
            return this;
        }

        public PendingSkillRequest build() {
            return new PendingSkillRequest(this);
        }
    }
}
