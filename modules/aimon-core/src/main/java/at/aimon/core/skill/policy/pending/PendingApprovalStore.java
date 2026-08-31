package at.aimon.core.skill.policy.pending;

import java.util.Map;
import java.util.Optional;

import at.aimon.core.skill.policy.SkillInvocationDecision;

/**
 * Per-{@link PendingTurn} cache of user-supplied "approve once" / "deny once" decisions.
 *
 * <p>
 * Narrowest of the three approval caches. {@link at.aimon.core.skill.policy.agent.AgentApprovalStore} keeps
 * decisions for the whole agent runtime and drops them only on {@code /revoke};
 * {@link at.aimon.core.skill.policy.session.SessionApprovalStore} keeps them for one session and the
 * runs that session delegates; this one keeps them only until the suspended turn resumes (or is cancelled).
 * {@link #clear(PendingTurnId)} drops every entry for an id so a fresh suspension starts with no inherited decisions.
 *
 * <p>
 * <b>Not yet wired.</b> Nothing in production writes to or reads from this store today. The shipped approval channel
 * ({@code InteractiveSkillApprovalChannel}) asks a single {@code [y/N]} question per skill, so there is no "just this
 * once" reply for this store to hold. It exists as the storage half of that missing scope choice — a channel that
 * offers one should write "once" replies here, and the policy chain must then consult this store first, the
 * session store second, the runtime store third, and the configured rule policy last.
 *
 * <p>
 * That ordering is narrowest scope first, and it is the same rule the wired part of the chain already follows (see
 * {@code SessionScopedSkillInvocationPolicy}). An earlier revision of this javadoc prescribed the reverse; it was
 * wrong, because a broad standing grant consulted first answers before the narrow entry is ever read, which would make
 * a "just this once, no" reply unimplementable.
 *
 * <p>
 * Only {@link SkillInvocationDecision#ALLOW} and {@link SkillInvocationDecision#DENY} may be stored;
 * {@link SkillInvocationDecision#ASK} represents an unanswered question and must not be cached.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface PendingApprovalStore {

    /**
     * Records a user reply for one skill within a pending turn.
     *
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalArgumentException
     *             if {@code decision} is {@link SkillInvocationDecision#ASK}
     */
    void record(PendingTurnId turnId, String skillName, SkillInvocationDecision decision);

    /**
     * Returns the recorded decision for a (turn, skill) pair, if any.
     */
    Optional<SkillInvocationDecision> get(PendingTurnId turnId, String skillName);

    /**
     * Returns an immutable snapshot of every decision recorded for the given turn (skillName -&gt; decision). Empty map
     * if nothing has been recorded yet.
     */
    Map<String, SkillInvocationDecision> getAll(PendingTurnId turnId);

    /**
     * Drops every recorded decision for the given turn. Idempotent.
     */
    void clear(PendingTurnId turnId);
}
