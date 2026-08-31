package at.aimon.core.skill.policy;

/**
 * Decides whether a given skill invocation may proceed (SK-11).
 *
 * <p>
 * Called by {@code SkillTool} after the registry lookup succeeds and before any side-effects (hook activation,
 * rendering, fork). Implementations must be:
 * <ul>
 * <li><strong>Cheap and idempotent</strong> — the agent loop's pre-flight scan (SK-11.4) calls this once per skill
 * invocation per LLM round; non-trivial I/O belongs in a separate cache layer.
 * <li><strong>Thread-safe</strong> — a single {@code SkillTool} instance is shared across concurrent agent contexts.
 * <li><strong>Total</strong> — must return one of the three enum values for any input; never throw. (Defensive:
 * implementations that may throw should be wrapped to map exceptions to {@link SkillInvocationDecision#DENY} with a
 * logged reason.)
 * </ul>
 *
 * <p>
 * The default wiring uses {@link AlwaysAllowSkillInvocationPolicy#INSTANCE} so existing deployments keep their current
 * behaviour. Production deployments should swap in {@link RuleBasedSkillInvocationPolicy} (or a custom impl) via the
 * {@code SkillTool} 5-arg constructor.
 */
@FunctionalInterface
public interface SkillInvocationPolicy {

    /**
     * Returns the decision for the given invocation request.
     *
     * @param request
     *            the request (never null)
     * @return the decision (never null)
     */
    SkillInvocationDecision check(SkillInvocationRequest request);
}
