package at.aimon.core.skill.policy.agent;

import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;

/**
 * Caches user-granted approval decisions for skill invocations, keyed by {@link AgentRuntimeId}.
 *
 * <p>
 * When a user answers an ASK prompt, the resolved decision is persisted here so that subsequent invocations of the
 * same skill under the same {@link AgentRuntimeId} resolve without prompting again.
 * {@link ApprovalCachingSkillInvocationPolicy} consults this store before delegating to the underlying
 * {@link SkillInvocationPolicy}, so a cached entry short-circuits the rule evaluation.
 *
 * <p>
 * The key is <em>agent</em>-scoped ({@code agent:<name>[:discriminator]}), not session-scoped: an answer given in
 * one session also applies to every later session and {@code LiveSession} of the same agent, and survives
 * {@code /clear}. Store here only what the user explicitly asked to apply that widely
 * ({@code /approve --agent}, the {@code a} answer at the prompt); everything else belongs in
 * {@link at.aimon.core.skill.policy.session.SessionApprovalStore}, which the policy consults first.
 *
 * <p>
 * This type was named {@code SessionApprovalStore} until it was renamed to match the scope it actually keys on — the
 * old name claimed a lifetime an order of magnitude shorter than the real one. That name has since been <em>reused</em>
 * by a different type: today's {@link at.aimon.core.skill.policy.session.SessionApprovalStore} is keyed by
 * {@code SessionId} and really is session-scoped, so a memory of "SessionApprovalStore" from before the rename points
 * at this class, not at that one. See {@code docs/overview/glossary.md}.
 *
 * <p>
 * Only {@link SkillInvocationDecision#ALLOW} and {@link SkillInvocationDecision#DENY} may be stored;
 * {@link SkillInvocationDecision#ASK} represents an unanswered question and must not be cached.
 *
 * <p>
 * Lifecycle: entries are bound to an {@link AgentRuntimeId} and are never dropped automatically — there is no TTL,
 * and neither closing a session nor closing a {@code LiveSession} clears them, because the runtime outlives
 * both. Owners of the runtime (typically the application shell) must call {@link #invalidate(AgentRuntimeId)} when
 * they destroy it, and must offer the user a revocation path — the built-in {@code /revoke} command is wired to
 * exactly that.
 *
 * <p>
 * Implementations must be thread-safe. The default implementation is in-memory
 * ({@link InMemoryAgentApprovalStore}); deployments running multiple aimon instances should provide a shared backing
 * store.
 *
 * @see InMemoryAgentApprovalStore
 * @see ApprovalCachingSkillInvocationPolicy
 */
public interface AgentApprovalStore {

    /**
     * Returns the cached decision for the given context and skill, if any.
     *
     * @param agentRuntimeId
     *            the agent runtime (must not be null)
     * @param skillName
     *            the fully qualified skill name (must not be null)
     * @return the cached {@link SkillInvocationDecision#ALLOW} or {@link SkillInvocationDecision#DENY}, or empty if no
     *         entry is stored
     * @throws NullPointerException
     *             if any argument is null
     */
    Optional<SkillInvocationDecision> get(AgentRuntimeId agentRuntimeId, String skillName);

    /**
     * Stores a decision for the given agent runtime and skill. The entry stays visible to every session and
     * {@code LiveSession} of that runtime until {@link #invalidate(AgentRuntimeId)} removes it.
     *
     * @param agentRuntimeId
     *            the agent runtime (must not be null)
     * @param skillName
     *            the fully qualified skill name (must not be null)
     * @param decision
     *            either {@link SkillInvocationDecision#ALLOW} or {@link SkillInvocationDecision#DENY} (must not be null
     *            or {@link SkillInvocationDecision#ASK})
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if the decision is {@link SkillInvocationDecision#ASK}
     */
    void put(AgentRuntimeId agentRuntimeId, String skillName, SkillInvocationDecision decision);

    /**
     * Drops every cached entry for the given agent runtime.
     *
     * <p>
     * Call this when the runtime is destroyed, and whenever the user revokes earlier answers — the built-in
     * {@code /revoke} command is wired to it. Dropping entries never weakens security: on a miss the underlying
     * policy is consulted again, so the worst outcome is that the user is asked once more.
     *
     * @param agentRuntimeId
     *            the agent runtime (must not be null)
     * @throws NullPointerException
     *             if agentRuntimeId is null
     */
    void invalidate(AgentRuntimeId agentRuntimeId);
}
