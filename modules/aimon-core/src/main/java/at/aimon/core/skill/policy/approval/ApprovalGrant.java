package at.aimon.core.skill.policy.approval;

import java.util.Objects;

import at.aimon.core.skill.policy.SkillInvocationDecision;

/**
 * A user's answer to an approval prompt: what they decided, and how far the decision reaches.
 *
 * <p>
 * {@link SkillInvocationDecision} carries only the first half. Widening that enum with scope variants was rejected — it
 * is read by the policy chain, by every store and by persisted state, so new constants would have to be understood
 * everywhere a decision is compared. Pairing it with an {@link ApprovalScope} in a separate value keeps the scope where
 * it is actually used: between the approval channel and the store it writes to.
 *
 * <p>
 * Immutable. Constructed through the static factories rather than a builder — both fields are required and only three
 * combinations are meaningful, so a builder could only add half-built states. This follows
 * {@link at.aimon.core.skill.policy.SkillPreflightScanResult SkillPreflightScanResult}, which makes the same trade in
 * the same layer.
 *
 * @see ApprovalScope
 */
public final class ApprovalGrant {

    private final SkillInvocationDecision decision;
    private final ApprovalScope scope;

    private ApprovalGrant(SkillInvocationDecision decision, ApprovalScope scope) {
        this.decision = Objects.requireNonNull(decision, "decision cannot be null");
        this.scope = Objects.requireNonNull(scope, "scope cannot be null");
        if (decision == SkillInvocationDecision.ASK) {
            throw new IllegalArgumentException("ASK is an unanswered question, not a grant");
        }
    }

    /**
     * Creates a grant with an explicit decision and scope.
     *
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if the decision is {@link SkillInvocationDecision#ASK}
     */
    public static ApprovalGrant of(SkillInvocationDecision decision, ApprovalScope scope) {
        return new ApprovalGrant(decision, scope);
    }

    /** "Yes, in this session." The answer a plain {@code y} should produce. */
    public static ApprovalGrant allowForSession() {
        return new ApprovalGrant(SkillInvocationDecision.ALLOW, ApprovalScope.SESSION);
    }

    /** "Yes, always, for this agent." Only for a reply that explicitly asked for the broader reach. */
    public static ApprovalGrant allowForAgent() {
        return new ApprovalGrant(SkillInvocationDecision.ALLOW, ApprovalScope.AGENT);
    }

    /**
     * "No, in this session." The fail-closed answer, and deliberately not agent-wide: a refusal here — or a Ctrl+C
     * that is read as one — should not harden into a standing block the user has no obvious way to notice.
     */
    public static ApprovalGrant denyForSession() {
        return new ApprovalGrant(SkillInvocationDecision.DENY, ApprovalScope.SESSION);
    }

    public SkillInvocationDecision getDecision() {
        return decision;
    }

    public ApprovalScope getScope() {
        return scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApprovalGrant other)) {
            return false;
        }
        return decision == other.decision && scope == other.scope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(decision, scope);
    }

    @Override
    public String toString() {
        return "ApprovalGrant{decision=" + decision + ", scope=" + scope + '}';
    }
}
