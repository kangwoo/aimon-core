package at.aimon.core.skill.policy.session;

import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;

/**
 * Caches user-granted approval decisions for skill invocations, keyed by {@link SessionId}.
 *
 * <p>
 * This is the narrow counterpart to {@link AgentApprovalStore}, which keys on {@code AgentRuntimeId} and therefore
 * reaches every session of that agent. An answer stored here applies to the session the user was in when
 * they gave it: a second session with the same agent asks again.
 *
 * <p>
 * "The session" includes the work that session delegates. A subagent fork mints its own
 * {@link SessionId} and is never keyed here, but it carries the spawning session's id as
 * {@code invokingSessionId} and {@link SessionScopedSkillInvocationPolicy} looks the entry up under that —
 * which is what a user answering "allow this skill here" takes their answer to mean. The reach is therefore a
 * session and its descendants, not a single id.
 *
 * <p>
 * Prefer this store for anything a user answers in the flow of a session. Agent-wide storage is correct only when
 * the user explicitly asked for it ("always allow for this agent"), because they cannot see the other sessions
 * their answer would reach.
 *
 * <p>
 * Only {@link SkillInvocationDecision#ALLOW} and {@link SkillInvocationDecision#DENY} may be stored;
 * {@link SkillInvocationDecision#ASK} represents an unanswered question and must not be cached.
 *
 * <p>
 * Lifecycle: entries belong to a {@link SessionId} and have no TTL. Owners must call
 * {@link #invalidate(SessionId)} when a session is cleared, released or deleted. Dropping entries never
 * weakens security — on a miss the underlying {@link SkillInvocationPolicy} is consulted again, so the worst outcome
 * is that the user is asked once more.
 *
 * <p>
 * IMPORTANT: the scope named here is the scope of the <em>entries</em>, not of the store instance. A single instance
 * typically serves many sessions, and its own lifetime is decided by the wiring that constructs it — see
 * {@code docs/overview/scope-model.md} §5.3, which makes the same point about {@code InMemoryTodoRepository}.
 *
 * <p>
 * Implementations must be thread-safe. The default implementation is in-memory
 * ({@link InMemorySessionApprovalStore}) and therefore node-local; deployments running multiple aimon instances
 * should provide a shared backing store, or accept that a session which moves nodes re-prompts.
 *
 * @see InMemorySessionApprovalStore
 * @see AgentApprovalStore
 */
public interface SessionApprovalStore {

    /**
     * Returns the cached decision for the given session and skill, if any.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param skillName
     *            the fully qualified skill name (must not be null)
     * @return the cached {@link SkillInvocationDecision#ALLOW} or {@link SkillInvocationDecision#DENY}, or empty if no
     *         entry is stored
     * @throws NullPointerException
     *             if any argument is null
     */
    Optional<SkillInvocationDecision> get(SessionId sessionId, String skillName);

    /**
     * Stores a decision for the given session and skill. The entry is invisible to unrelated sessions,
     * including other sessions of the same agent — but it is visible to runs this session spawns, which
     * carry its id as {@code invokingSessionId}.
     *
     * @param sessionId
     *            the session (must not be null)
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
    void put(SessionId sessionId, String skillName, SkillInvocationDecision decision);

    /**
     * Drops every cached entry for the given session.
     *
     * <p>
     * Call this when the session is cleared ({@code /clear}), released or deleted. Unlike the agent-scoped store,
     * this is expected to run on ordinary session lifecycle events rather than only on explicit revocation.
     *
     * @param sessionId
     *            the session (must not be null)
     * @throws NullPointerException
     *             if sessionId is null
     */
    void invalidate(SessionId sessionId);
}
