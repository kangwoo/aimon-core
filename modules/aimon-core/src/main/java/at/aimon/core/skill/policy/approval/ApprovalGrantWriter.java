package at.aimon.core.skill.policy.approval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Shared write path for the headless {@link SkillApprovalChannel} implementations in this package.
 *
 * <p>
 * Package-private on purpose: this is not an extension point, it is the one place the two static channels agree on
 * how a decision reaches the store the policy chain reads. It de-duplicates the pending requests by skill name (both
 * stores are keyed on the name within their scope, so two ASK invocations of the same skill in one iteration share a
 * single answer) and resolves {@link ApprovalScope} to a concrete store.
 *
 * <p>
 * <b>The session-less escalation.</b> A session-scoped grant ({@link ApprovalScope#SESSION}) with no
 * {@link SessionId} to scope it to is written to the {@link AgentApprovalStore} instead. That is not a choice this
 * class is free to make differently:
 * {@link SkillApprovalChannel} states that "an implementation MUST write a concrete ALLOW or DENY for every requested
 * skill into a place the active SkillInvocationPolicy chain reads" before returning, and its {@code sessionId}
 * parameter names the same fallback — "A channel that stores session-scoped decisions must fall back to agent scope
 * when this is null". Skipping the write would leave the skill at ASK, which {@code SkillTool} refuses; for a denial
 * that is the same outcome, but for an allow-list grant it would silently turn a configured ALLOW into a refusal.
 *
 * <p>
 * The escalation is nevertheless a widening — the entry reaches every session of that {@link AgentRuntimeId}, has no
 * TTL, survives {@code /clear} and is removed only by {@code /revoke --agent} — and nobody asked for that reach, so
 * every such write is logged at <b>WARN</b> naming the skill, the decision and the runtime. The ordinary
 * session-scoped path stays at DEBUG.
 *
 * <p>
 * A failed write is logged and swallowed per skill rather than aborting the batch. That fails closed: an unwritten
 * decision leaves the policy chain answering ASK, and {@code SkillTool} rejects anything that is not ALLOW when it
 * re-checks at execution time. Isolating the failure to one skill keeps a single broken write from silently denying
 * the rest of the batch.
 *
 * <p>
 * Immutable and thread-safe; both stores are contractually thread-safe.
 */
final class ApprovalGrantWriter {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGrantWriter.class);

    private final SessionApprovalStore sessionApprovalStore;
    private final AgentApprovalStore agentApprovalStore;

    ApprovalGrantWriter(SessionApprovalStore sessionApprovalStore, AgentApprovalStore agentApprovalStore) {
        this.sessionApprovalStore = Objects.requireNonNull(sessionApprovalStore, "sessionApprovalStore cannot be null");
        this.agentApprovalStore = Objects.requireNonNull(agentApprovalStore, "agentApprovalStore cannot be null");
    }

    /**
     * Asks {@code decider} for a grant per unique skill name in {@code pendingRequests} and persists each one.
     *
     * @param decider
     *            maps a skill name to the grant this channel wants recorded for it; called once per unique name, in
     *            the order the names first appear
     */
    void writeAll(List<PendingSkillRequest> pendingRequests, AgentRuntimeId agentRuntimeId, SessionId sessionId,
            Function<String, ApprovalGrant> decider) {
        for (String skillName : uniqueSkillNames(pendingRequests)) {
            try {
                write(decider.apply(skillName), skillName, agentRuntimeId, sessionId);
            } catch (RuntimeException e) {
                log.warn("Could not persist the approval decision for skill '{}'; it stays unanswered and will be "
                        + "refused at execution time: {}", skillName, e.getMessage(), e);
            }
        }
    }

    /**
     * Writes one grant to the store its scope names, escalating a session-scoped grant to the agent store when there
     * is no session to scope it to. The escalation is mandated (see the class javadoc) but reaches further than the
     * grant asked for, so it is logged at WARN rather than DEBUG.
     */
    private void write(ApprovalGrant grant, String skillName, AgentRuntimeId agentRuntimeId, SessionId sessionId) {
        if (grant.getScope() == ApprovalScope.SESSION && sessionId != null) {
            sessionApprovalStore.put(sessionId, skillName, grant.getDecision());
            log.debug("Recorded {} for skill '{}' in session {}", grant.getDecision(), skillName, sessionId);
            return;
        }
        agentApprovalStore.put(agentRuntimeId, skillName, grant.getDecision());
        if (grant.getScope() == ApprovalScope.SESSION) {
            // Loud on purpose: the caller asked for session reach and is getting agent reach because it had no
            // session. An ALLOW widened this way is a standing pre-approval no operator typed.
            log.warn("No session bound; widening {} for skill '{}' from session scope to agent scope on {}. "
                    + "The entry now applies to every session of that agent, has no TTL, survives /clear and is "
                    + "removed only by '/revoke --agent'.", grant.getDecision(), skillName, agentRuntimeId);
        } else {
            log.debug("Recorded {} for skill '{}' agent-wide on {}", grant.getDecision(), skillName, agentRuntimeId);
        }
    }

    private static Set<String> uniqueSkillNames(List<PendingSkillRequest> pendingRequests) {
        final Set<String> names = new LinkedHashSet<>();
        for (PendingSkillRequest request : pendingRequests) {
            names.add(request.getSkillName());
        }
        return names;
    }
}
