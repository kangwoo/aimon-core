package at.aimon.core.skill.policy.approval;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Headless {@link SkillApprovalChannel} that answers every ASK with {@link SkillInvocationDecision#DENY}.
 *
 * <p>
 * This is the safe default for an unattended deployment — a REST service, a scheduled routine, an agent embedded in
 * an application. Nobody is watching a terminal, so the only two honest answers are "deny" and "suspend the
 * execution until somebody answers out of band". This channel gives the first: the execution keeps running, the skill
 * call comes back to the model as a policy rejection, and it can pick another route. Wire the suspend path instead
 * (no channel, plus an approval API over {@code PendingTurnRegistry}) when there really is a human who can be asked.
 *
 * <h3>Why it writes rather than just returning</h3>
 * {@link at.aimon.core.skill.policy.SkillPreflightScanner SkillPreflightScanner} does not re-run the policy once a
 * channel returns. A channel that decides but persists nothing therefore leaves the chain still answering ASK, and
 * {@code SkillTool} rejects the call at execution time with "no approval channel is available in this context" — the
 * exact broken state this class exists to remove. So every denial is written through to the store the policy chain
 * reads, as the {@link SkillApprovalChannel} contract requires.
 *
 * <h3>Scope of the denials</h3>
 * Denials are recorded at {@link ApprovalScope#SESSION}, the narrowest scope available: they reach the session that
 * triggered them and the runs it delegates, and they disappear when that session is cleared or released.
 *
 * <p>
 * When the caller has no session at all — the two-arg entry point and other system-initiated runs pass a {@code null}
 * {@link SessionId} — there is nowhere to put a session-scoped entry, so the denial is written agent-scoped instead.
 * That is the fallback the interface mandates over silently dropping the decision ("A channel that stores
 * session-scoped decisions must fall back to agent scope when this is null"), and for a channel whose answer is
 * a constant it records nothing this channel would not say again. Know what it costs on a runtime that is
 * <em>also</em> driven interactively, though: an agent-scoped DENY has no TTL, survives {@code /clear}, and is
 * consulted before the rules, so a later interactive session finds the skill already denied and is never prompted.
 * Every widened write is logged at <b>WARN</b> naming the skill and the runtime, so the reach is visible in the log
 * rather than only in this paragraph. Clear it with {@code /revoke --agent}; better, give headless and interactive
 * traffic separate agent runtime ids via discriminators.
 *
 * <h3>Failure behaviour</h3>
 * The channel never propagates an exception for a condition a caller can legitimately produce. An empty
 * {@code pendingRequests} list is a documented no-op rather than an error — the CLI channel rejects it, but on a
 * headless node anything thrown here routes the execution to the suspend path, where nobody can answer it. A store
 * that fails is logged and swallowed, which fails closed: the un-persisted skill stays at ASK and is refused when
 * {@code SkillTool} re-checks.
 *
 * <p>
 * Thread-safe and stateless per request: the single field is an immutable writer over two contractually thread-safe
 * stores.
 *
 * @see AllowListSkillApprovalChannel
 */
public final class DenyAllSkillApprovalChannel implements SkillApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(DenyAllSkillApprovalChannel.class);

    private final ApprovalGrantWriter grantWriter;

    /**
     * Creates a channel that denies every skill, writing session-scoped denials to {@code sessionApprovalStore} and
     * falling back to {@code agentApprovalStore} for callers that have no session.
     *
     * <p>
     * Both stores are required. They must be the same instances the policy chain reads, or the denials will not be
     * seen when {@code SkillTool} re-checks and the skill will be refused for the wrong reason.
     *
     * @param sessionApprovalStore
     *            the store for session-scoped denials — where a denial normally lands (must not be null)
     * @param agentApprovalStore
     *            the agent-scoped store, used only when the caller has no session (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DenyAllSkillApprovalChannel(SessionApprovalStore sessionApprovalStore,
            AgentApprovalStore agentApprovalStore) {
        this.grantWriter = new ApprovalGrantWriter(sessionApprovalStore, agentApprovalStore);
    }

    /**
     * Session-less entry point, kept because {@link SkillApprovalChannel} declares it as the abstract method.
     * Delegates with no session, which forces every denial agent-scoped and logs each one at WARN — see the class
     * javadoc. Prefer {@link #requestApproval(List, AgentRuntimeId, SessionId)} and pass the real session whenever
     * there is one.
     */
    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId) {
        requestApproval(pendingRequests, agentRuntimeId, null);
    }

    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId,
            SessionId sessionId) {
        Objects.requireNonNull(pendingRequests, "pendingRequests cannot be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId cannot be null");
        if (pendingRequests.isEmpty()) {
            log.debug("No pending skill requests to answer; nothing recorded");
            return;
        }
        try {
            grantWriter.writeAll(pendingRequests, agentRuntimeId, sessionId, this::deny);
        } catch (RuntimeException e) {
            // The contract says we must not propagate: an exception here sends the execution to the suspend path,
            // which on a headless node nobody can resolve. Unwritten skills stay at ASK and are refused by SkillTool.
            log.warn("Failed to record deny-all decisions for {} pending skill request(s): {}", pendingRequests.size(),
                    e.getMessage(), e);
        }
    }

    private ApprovalGrant deny(String skillName) {
        log.info("Denying skill '{}': this agent uses the headless deny-all approval channel, so there is no operator "
                + "who could answer an approval prompt", skillName);
        return ApprovalGrant.denyForSession();
    }
}
