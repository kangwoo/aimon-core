package at.aimon.core.skill.policy.approval;

import java.util.List;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;

/**
 * Synchronous user-approval back-channel consulted by {@link at.aimon.core.skill.policy.SkillPreflightScanner} when
 * the policy returns {@link SkillInvocationDecision#ASK} for one or more skill invocations in a single LLM iteration
 * (SK-11.6).
 *
 * <p>
 * The channel exists so an interactive shell (CLI prompt, web UI, IDE plugin) can resolve ASK decisions inline rather
 * than forcing an atomic suspension and an out-of-band {@code /approve} round-trip. When a channel is wired into the
 * scanner, the suspend path becomes the fallback for headless callers (REST API, scheduled task, agent-as-service).
 *
 * <h3>Contract</h3>
 * <ul>
 * <li><b>Persistence is the implementation's responsibility.</b> Before returning, an implementation MUST write a
 * concrete {@link SkillInvocationDecision#ALLOW} or {@link SkillInvocationDecision#DENY} for every requested skill into
 * a place the active {@link at.aimon.core.skill.policy.SkillInvocationPolicy} chain reads — typically a
 * {@link AgentApprovalStore} that sits in front of the rule-based policy. The scanner does not re-read the channel's
 * decisions; it relies on the policy chain picking them up on the next check (which happens at {@code SkillTool}
 * execution time within the same turn). Note how far that write reaches: the store is keyed by
 * {@link AgentRuntimeId}, so a decision taken here is <b>agent-scoped, not session-scoped</b> — it silently
 * applies to every later session and {@code LiveSession} of the same agent until {@code /revoke} (or runtime
 * destruction) drops it. An implementation that prompts the user should say so, so the user knows what they are
 * agreeing to.</li>
 * <li><b>Never throw.</b> Implementations must catch their own infrastructure errors (I/O, user-interrupt, time-outs)
 * and write a safe-default decision — {@link SkillInvocationDecision#DENY} for the un-decided skills — rather than
 * propagating the exception. The scanner runs inside the agent loop; an uncaught exception here would bubble through
 * the executor as a hard failure and dump the in-flight turn.</li>
 * <li><b>Stay synchronous.</b> The scanner blocks on this call. Implementations that genuinely need async resolution
 * should not implement this interface; they should let the suspend/resume path run instead.</li>
 * <li><b>Granularity is per-skill.</b> Implementations may bundle the prompts ("approve all of these?") or surface them
 * individually, but the persisted decisions are per-skill — that is what the policy chain queries.</li>
 * </ul>
 *
 * <h3>Why not return decisions?</h3>
 * Returning a {@code Map<String, SkillInvocationDecision>} would tempt callers to bypass the
 * {@link AgentApprovalStore} and apply the decisions ad hoc. By forcing the channel to write through the same store
 * the policy reads, every other code path (per-call check inside {@code SkillTool}, future scans under the same agent
 * runtime) automatically observes the same answer.
 */
@FunctionalInterface
public interface SkillApprovalChannel {

    /**
     * Resolves approval for every entry in {@code pendingRequests}, persisting an
     * {@link SkillInvocationDecision#ALLOW} or {@link SkillInvocationDecision#DENY} per skill before returning.
     *
     * @param pendingRequests
     *            the skills the policy returned ASK for, in original tool_use order (never null, never empty)
     * @param agentRuntimeId
     *            the active agent runtime (never null) — the key the decisions are stored under in the
     *            {@link AgentApprovalStore}, which makes them <b>agent-scoped</b>: they also apply to later
     *            sessions of the same agent (see the persistence bullet above)
     */
    void requestApproval(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId);

    /**
     * Session-aware variant, called by {@link at.aimon.core.skill.policy.SkillPreflightScanner}.
     *
     * <p>
     * The default implementation drops {@code sessionId} and delegates to
     * {@link #requestApproval(List, AgentRuntimeId)}, so every existing channel keeps its current agent-scoped
     * behaviour without modification. Implementations that can narrow the write to the session should override
     * this method instead of the two-arg one — see
     * {@code docs/design/skill/approval-scope.md}.
     *
     * @param pendingRequests
     *            the skills the policy returned ASK for, in original tool_use order (never null, never empty)
     * @param agentRuntimeId
     *            the active agent runtime (never null)
     * @param sessionId
     *            the session the turn belongs to, or {@code null} when the caller has none (scheduled tasks and
     *            other system-initiated runs). A channel that stores session-scoped decisions must fall back to
     *            agent scope when this is null, rather than silently dropping the user's answer.
     *            <p>
     *            Subagent forks never reach this method at all: no channel is reachable from a fork, and none may be —
     *            the user is not looking at it. A fork's skill call is answered by the decision the user gave in the
     *            session that spawned it, carried as {@code invokingSessionId}
     */
    default void requestApproval(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId,
            SessionId sessionId) {
        requestApproval(pendingRequests, agentRuntimeId);
    }
}
