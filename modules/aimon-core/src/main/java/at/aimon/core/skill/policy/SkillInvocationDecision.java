package at.aimon.core.skill.policy;

/**
 * Outcome of a {@link SkillInvocationPolicy} evaluation.
 *
 * <p>
 * Three values:
 * <ul>
 * <li>{@link #ALLOW} — the skill may be invoked immediately.
 * <li>{@link #DENY} — the skill must not be invoked. The {@code SkillTool} returns an error result without touching the
 * registry/renderer/fork executor.
 * <li>{@link #ASK} — the policy defers to a human operator. SK-11.4 wires this to the agent loop's pre-flight scan and
 * suspend mechanism. Until then (SK-11.1), the {@code SkillTool} treats {@code ASK} as {@code DENY} with a distinct
 * error message so callers can see why their invocation was rejected.
 * </ul>
 *
 * <p>
 * Decisions are immutable values; the policy itself owns any caching of repeated evaluations.
 */
public enum SkillInvocationDecision {
    ALLOW, DENY, ASK
}
