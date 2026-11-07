package at.aimon.core.programmatic;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;

/**
 * The intrinsic grade of code an engine runs — what the code can do inside the engine without calling any tool,
 * before the callable set is folded in ({@code docs/design/tool/programmatic-tool-calling.md} decision 10).
 * {@code ExecuteCode}'s descriptor is {@code max(this, callable set)}.
 *
 * <p>
 * Immutable. Use {@link #builder()}.
 */
public final class ToolGrade {

    private final SideEffectLevel sideEffectLevel;
    private final DestructiveBehavior destructiveBehavior;
    private final DataReach dataReach;
    private final boolean approvalDelegated;
    private final String approvalKey;

    private ToolGrade(Builder builder) {
        this.sideEffectLevel = Objects.requireNonNull(builder.sideEffectLevel, "sideEffectLevel cannot be null");
        this.destructiveBehavior = Objects.requireNonNull(builder.destructiveBehavior,
                "destructiveBehavior cannot be null");
        this.dataReach = Objects.requireNonNull(builder.dataReach, "dataReach cannot be null");
        this.approvalDelegated = builder.approvalDelegated;
        this.approvalKey = builder.approvalKey;
    }

    /**
     * Returns a new builder. Every axis is required; {@code approvalDelegated} defaults to {@code false}.
     *
     * @return a new builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the side-effect level of the code itself.
     *
     * @return the level (never null)
     */
    public SideEffectLevel getSideEffectLevel() {
        return sideEffectLevel;
    }

    /**
     * Returns the destructiveness of the code itself.
     *
     * @return the destructiveness (never null)
     */
    public DestructiveBehavior getDestructiveBehavior() {
        return destructiveBehavior;
    }

    /**
     * Returns how far the code itself reaches.
     *
     * @return the reach (never null)
     */
    public DataReach getDataReach() {
        return dataReach;
    }

    /**
     * Returns whether the approval gate judges the nested calls instead of {@code ExecuteCode} itself.
     *
     * @return {@code true} when the gate never asks about {@code ExecuteCode}
     */
    public boolean isApprovalDelegated() {
        return approvalDelegated;
    }

    /**
     * Returns the approval key {@code ExecuteCode} is asked under, when it is another tool's ({@code Bash} for the
     * shell engine, decision 11).
     *
     * @return the key, empty for {@code ExecuteCode}'s own name
     */
    public Optional<String> getApprovalKey() {
        return Optional.ofNullable(approvalKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolGrade other)) {
            return false;
        }
        return sideEffectLevel == other.sideEffectLevel && destructiveBehavior == other.destructiveBehavior
                && dataReach == other.dataReach && approvalDelegated == other.approvalDelegated
                && Objects.equals(approvalKey, other.approvalKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sideEffectLevel, destructiveBehavior, dataReach, approvalDelegated, approvalKey);
    }

    @Override
    public String toString() {
        return "ToolGrade{" + sideEffectLevel + "/" + destructiveBehavior + "/" + dataReach + ", approvalDelegated="
                + approvalDelegated + (approvalKey == null ? "" : ", approvalKey=" + approvalKey) + '}';
    }

    /** Builder for {@link ToolGrade}. */
    public static final class Builder {

        private SideEffectLevel sideEffectLevel;
        private DestructiveBehavior destructiveBehavior;
        private DataReach dataReach;
        private boolean approvalDelegated;
        private String approvalKey;

        private Builder() {
        }

        /**
         * @param sideEffectLevel
         *            the side-effect level (must not be null)
         * @return this builder
         */
        public Builder sideEffectLevel(SideEffectLevel sideEffectLevel) {
            this.sideEffectLevel = sideEffectLevel;
            return this;
        }

        /**
         * @param destructiveBehavior
         *            the destructiveness (must not be null)
         * @return this builder
         */
        public Builder destructiveBehavior(DestructiveBehavior destructiveBehavior) {
            this.destructiveBehavior = destructiveBehavior;
            return this;
        }

        /**
         * @param dataReach
         *            the reach (must not be null)
         * @return this builder
         */
        public Builder dataReach(DataReach dataReach) {
            this.dataReach = dataReach;
            return this;
        }

        /**
         * @param approvalDelegated
         *            whether the gate judges nested calls instead
         * @return this builder
         */
        public Builder approvalDelegated(boolean approvalDelegated) {
            this.approvalDelegated = approvalDelegated;
            return this;
        }

        /**
         * @param approvalKey
         *            another tool's approval key, or {@code null} for {@code ExecuteCode}'s own name
         * @return this builder
         */
        public Builder approvalKey(String approvalKey) {
            this.approvalKey = approvalKey;
            return this;
        }

        /**
         * @return the grade
         * @throws NullPointerException
         *             if an axis is missing
         */
        public ToolGrade build() {
            return new ToolGrade(this);
        }
    }
}
