package at.aimon.core.skill.policy;

import java.util.List;
import java.util.Objects;

import at.aimon.core.skill.policy.pending.PendingSkillRequest;

/**
 * Result of a {@link SkillPreflightScanner#scan} call (SK-11.4).
 *
 * <p>
 * Either:
 * <ul>
 * <li>{@link #proceed()} — every {@code Skill} tool_use in the LLM response either resolved to
 * {@link SkillInvocationDecision#ALLOW} or wasn't a Skill tool_use at all. The agent loop continues into normal tool
 * execution. {@link SkillInvocationDecision#DENY} decisions are NOT treated as suspend-worthy here either: they are
 * passed through unchanged and the per-tool execution path returns the policy-rejected error from {@code SkillTool}.
 * Only {@link SkillInvocationDecision#ASK} suspends.</li>
 * <li>{@link #suspend(List)} — at least one {@code Skill} tool_use needs out-of-band approval. The agent loop must
 * perform an atomic suspension (no assistant message, no tool_result committed to memory) and surface a
 * {@link at.aimon.core.agent.budget.CompletionReason#SUSPENDED} terminal result. {@link #getPendingSkills()} carries
 * the requests the approval channel will surface to the user.</li>
 * </ul>
 *
 * <p>
 * Immutable. {@link #getPendingSkills()} is empty when {@link #shouldSuspend()} is {@code false}.
 */
public final class SkillPreflightScanResult {

    private static final SkillPreflightScanResult PROCEED = new SkillPreflightScanResult(List.of());

    private final List<PendingSkillRequest> pendingSkills;

    private SkillPreflightScanResult(List<PendingSkillRequest> pendingSkills) {
        this.pendingSkills = List.copyOf(Objects.requireNonNull(pendingSkills, "pendingSkills cannot be null"));
    }

    /**
     * Returns the singleton "proceed without suspending" result.
     */
    public static SkillPreflightScanResult proceed() {
        return PROCEED;
    }

    /**
     * Returns a "suspend the turn" result carrying the requests to surface to the approval channel.
     *
     * @throws IllegalArgumentException
     *             if {@code pendingSkills} is empty (a suspend with nothing to ask about is a programming error)
     */
    public static SkillPreflightScanResult suspend(List<PendingSkillRequest> pendingSkills) {
        Objects.requireNonNull(pendingSkills, "pendingSkills cannot be null");
        if (pendingSkills.isEmpty()) {
            throw new IllegalArgumentException("suspend requires at least one pending skill");
        }
        return new SkillPreflightScanResult(pendingSkills);
    }

    public boolean shouldSuspend() {
        return !pendingSkills.isEmpty();
    }

    /**
     * Returns the pending requests when {@link #shouldSuspend()} is {@code true}; an empty immutable list otherwise.
     */
    public List<PendingSkillRequest> getPendingSkills() {
        return pendingSkills;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SkillPreflightScanResult that = (SkillPreflightScanResult) o;
        return pendingSkills.equals(that.pendingSkills);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pendingSkills);
    }

    @Override
    public String toString() {
        return shouldSuspend()
                ? "SkillPreflightScanResult{suspend=" + pendingSkills.size() + '}'
                : "SkillPreflightScanResult{proceed}";
    }
}
