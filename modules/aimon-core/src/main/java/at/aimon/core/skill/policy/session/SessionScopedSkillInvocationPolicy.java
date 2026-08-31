package at.aimon.core.skill.policy.session;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;
import at.aimon.core.skill.policy.agent.ApprovalCachingSkillInvocationPolicy;

/**
 * Decorator policy that consults a {@link SessionApprovalStore} before falling back to a wrapped policy.
 *
 * <p>
 * Resolution order:
 * <ol>
 * <li>If the request carries a {@link SessionId} and the store has a cached decision for
 * {@code (sessionId, skill.getName())}, return it immediately.</li>
 * <li>Otherwise, if the request carries an <em>invoking</em> session id — the session whose turn spawned
 * this run — try the same lookup under it.</li>
 * <li>Otherwise, delegate to the wrapped policy.</li>
 * </ol>
 *
 * <p>
 * <b>Why the invoking session counts.</b> A subagent fork has no session of its own: it is identified by an
 * {@link at.aimon.core.agent.ExecutionId ExecutionId} and publishes no
 * {@link at.aimon.core.tools.ToolContextKeys#SESSION_ID SESSION_ID}, so step 1 has nothing to look up and the invoking
 * session id is the only {@link SessionId} a fork carries at all. Without step 2 the fork's every skill call misses
 * both stores and dies on the rule fallback, because no approval channel is reachable from a fork and none may be — a
 * fork must never prompt. So step 2 is not a widening of the reach that could be dropped to tighten it; it is the
 * whole reach a fork has, and removing it strands every forked skill call. It is also what makes "allow this skill
 * here" mean "…and in the work this session delegates", which is what a user answering the prompt takes it to mean.
 *
 * <p>
 * Step 1 stays ahead of step 2 because the two ids answer different questions — the run's own session versus the
 * session it acts for — and the most direct binding wins where both are answerable. On the paths that exist today the
 * order never has to break a tie: a run carries one of the two ids, never both.
 *
 * <p>
 * A hit is decisive in <em>both</em> directions: a fork inherits refusals as well as grants. That is deliberate and
 * fail-closed — a user who denied a skill in this session would be surprised to find it running in work the
 * session delegated.
 *
 * <p>
 * <b>Ordering: narrowest scope first.</b> This decorator wraps {@link ApprovalCachingSkillInvocationPolicy} — never the
 * other way round — producing {@code session -> agent -> rules}. A decision the user just made in this
 * session must beat a broader standing grant made earlier: were the agent-wide store consulted first, a
 * per-session "deny here" could never take effect, because a prior "always allow for this agent" would answer
 * first and the narrow entry would be dead storage. Narrow-first also matches how the user experiences the two answers
 * — the specific one is the more recent and the more deliberate.
 *
 * <p>
 * The same argument extends downward to the per-turn {@code PendingApprovalStore}, which is narrower still and
 * therefore belongs ahead of this one once a channel offers "just this once" replies.
 *
 * <p>
 * A request carrying neither id (scheduled tasks and other system-initiated runs) falls straight through to the
 * delegate, so those paths behave exactly as they did before this decorator existed.
 *
 * <p>
 * Like its agent-scoped sibling, this decorator never writes to the store — population is the approval channel's job.
 * Keeping reads and writes apart leaves the decorator side-effect free, as the {@link SkillInvocationPolicy} contract
 * requires (cheap, idempotent, thread-safe).
 *
 * <p>
 * Thread-safety follows from the wrapped store and delegate.
 *
 * @see SessionApprovalStore
 * @see ApprovalCachingSkillInvocationPolicy
 */
public final class SessionScopedSkillInvocationPolicy implements SkillInvocationPolicy {

    private final SessionApprovalStore store;
    private final SkillInvocationPolicy delegate;

    /**
     * Creates a session-scoped policy.
     *
     * @param store
     *            the approval cache to consult first (must not be null)
     * @param delegate
     *            the underlying policy invoked when the cache misses — typically
     *            {@link ApprovalCachingSkillInvocationPolicy} (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SessionScopedSkillInvocationPolicy(SessionApprovalStore store, SkillInvocationPolicy delegate) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
    }

    @Override
    public SkillInvocationDecision check(SkillInvocationRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        final String skillName = request.getSkill().getName();
        return lookup(request.getSessionId(), skillName).or(() -> lookup(request.getInvokingSessionId(), skillName))
                .orElseGet(() -> delegate.check(request));
    }

    private Optional<SkillInvocationDecision> lookup(Optional<SessionId> sessionId, String skillName) {
        return sessionId.flatMap(id -> store.get(id, skillName));
    }

    /**
     * Returns the underlying approval store. Intended for callers (e.g., approval channels) that write back user
     * decisions.
     */
    public SessionApprovalStore getStore() {
        return store;
    }

    /** Returns the wrapped policy. */
    public SkillInvocationPolicy getDelegate() {
        return delegate;
    }
}
