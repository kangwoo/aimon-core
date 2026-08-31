package at.aimon.core.skill.policy.agent;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;

/**
 * Decorator policy that consults a {@link AgentApprovalStore} before falling back to a wrapped policy.
 *
 * <p>
 * Resolution order:
 * <ol>
 * <li>If the request carries an {@link AgentRuntimeId} and the store has a cached decision for
 * {@code (agentRuntimeId, skill.getName())}, return it immediately.</li>
 * <li>Otherwise, delegate to the wrapped policy.</li>
 * </ol>
 *
 * <p>
 * This decorator never writes to the store — population is the responsibility of the approval channel (typically
 * SK-11.5/SK-11.6 wires the user's "always allow for this agent" reply into {@link AgentApprovalStore#put}). Keeping
 * the read/write paths separate lets the cache survive interleaved policy evaluations and keeps the decorator
 * side-effect free, satisfying the {@link SkillInvocationPolicy} contract (cheap, idempotent, thread-safe).
 *
 * <p>
 * This is the agent-wide half of the chain; the session-scoped
 * {@link at.aimon.core.skill.policy.session.SessionScopedSkillInvocationPolicy} wraps it so the narrower
 * answer is consulted first.
 *
 * <p>
 * Thread-safety follows from the wrapped store and delegate.
 */
public final class ApprovalCachingSkillInvocationPolicy implements SkillInvocationPolicy {

    private final AgentApprovalStore store;
    private final SkillInvocationPolicy delegate;

    /**
     * Creates a policy that short-circuits on agent-wide approvals.
     *
     * @param store
     *            the approval cache to consult first (must not be null)
     * @param delegate
     *            the underlying policy invoked when the cache misses (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public ApprovalCachingSkillInvocationPolicy(AgentApprovalStore store, SkillInvocationPolicy delegate) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public SkillInvocationDecision check(SkillInvocationRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        final Optional<AgentRuntimeId> agentRuntimeId = request.getAgentRuntimeId();
        if (agentRuntimeId.isPresent()) {
            final Optional<SkillInvocationDecision> cached = store.get(agentRuntimeId.get(),
                    request.getSkill().getName());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return delegate.check(request);
    }

    /**
     * Returns the underlying approval store. Intended for callers (e.g., approval channels) that need to write back
     * user decisions.
     */
    public AgentApprovalStore getStore() {
        return store;
    }

    /**
     * Returns the wrapped policy.
     */
    public SkillInvocationPolicy getDelegate() {
        return delegate;
    }
}
