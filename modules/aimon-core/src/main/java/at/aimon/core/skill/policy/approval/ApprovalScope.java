package at.aimon.core.skill.policy.approval;

/**
 * How far a user's answer to an approval prompt reaches.
 *
 * <p>
 * This is the scope of the <em>answer</em>, not of any component: it selects the approval store the channel writes to,
 * and therefore which later invocations the answer will silently pre-answer.
 *
 * @see ApprovalGrant
 */
public enum ApprovalScope {

    /**
     * The answer applies to the session it was given in, and to the runs that session delegates — subagent
     * forks, skill forks, foreground workflows. An unrelated session with the same agent asks again.
     *
     * <p>
     * Delegated work is inside the reach because a fork cannot prompt and the user was told "this skill, here"; work
     * this session ordered is still "here". A fork has no {@code SessionId} of its own — it is not a session's turn —
     * so it finds the answer through the spawning session's id, which it carries as {@code invokingSessionId}. A fork
     * that forks again passes on the user's session id rather than the intermediate fork's, so the reach stays the
     * span the user could see when they answered.
     *
     * <p>
     * The right default for anything answered in the flow of a session — it is the only scope whose reach the user
     * can actually see at the moment they answer.
     */
    SESSION,

    /**
     * The answer applies to every session of the agent, present and future, until {@code /revoke} clears it.
     *
     * <p>
     * Correct only when the user explicitly asked for it. They cannot see the other sessions their answer will
     * reach, so this must never be inferred from an ordinary "yes".
     */
    AGENT
}
