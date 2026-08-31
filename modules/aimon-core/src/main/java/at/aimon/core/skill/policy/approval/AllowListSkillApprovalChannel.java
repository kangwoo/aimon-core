package at.aimon.core.skill.policy.approval;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Headless {@link SkillApprovalChannel} that answers ASK with {@link SkillInvocationDecision#ALLOW} for the skills on
 * a caller-supplied allow-list and {@link SkillInvocationDecision#DENY} for everything else.
 *
 * <p>
 * The middle setting between {@link DenyAllSkillApprovalChannel} (nothing gets through) and an interactive prompt
 * (which an unattended deployment cannot show). The operator decides once, at wiring time, which skills an
 * unsupervised agent may run; the channel then applies that answer without a human in the loop.
 *
 * <h3>Matching rule</h3>
 * Matching is <b>exact, case-sensitive equality</b> against {@code Skill#getName()}. There are deliberately no
 * globs, no prefixes, no regular expressions and no case folding:
 * <ul>
 * <li>{@code "deploy"} does not match {@code "Deploy"} or {@code "deploy-prod"}.</li>
 * <li>A literal {@code "*"} entry allows a skill actually <em>named</em> {@code *}, and nothing else.</li>
 * </ul>
 * This is a security gate whose keys are the same exact strings the two approval stores are keyed on, so a pattern
 * dialect would be one typo away from granting everything, and a mis-typed pattern fails open in a way that is
 * invisible until it matters. A list that needs wildcards is a rule the
 * {@link at.aimon.core.skill.policy.RuleBasedSkillInvocationPolicy RuleBasedSkillInvocationPolicy} should express
 * instead, where the pattern semantics are a first-class concern.
 *
 * <h3>Why it writes rather than just returning</h3>
 * {@link at.aimon.core.skill.policy.SkillPreflightScanner SkillPreflightScanner} does not re-run the policy once a
 * channel returns, so a decision that is not persisted is a decision the rest of the execution never sees. For an
 * allow-list that would be an outright bug — an allow-listed skill would still be refused at {@code SkillTool} time.
 * Every decision, allow and deny alike, is therefore written to the store the policy chain reads.
 *
 * <h3>Scope of the decisions</h3>
 * Decisions are recorded at {@link ApprovalScope#SESSION}, the narrowest scope available: they reach the session that
 * triggered them and the runs it delegates, and they disappear when that session is cleared or released.
 *
 * <h3>IMPORTANT — a session-less caller gets an agent-wide pre-approval</h3>
 * When the caller has no session at all ({@code null} {@link SessionId} — the two-arg entry point, or a host driving
 * the channel outside the main-agent turn loop) there is nowhere to put a session-scoped entry, so the decision goes
 * to the {@link AgentApprovalStore} instead. That escalation is not a preference this class is free to make
 * differently. {@link SkillApprovalChannel} requires that "an implementation MUST write a concrete ALLOW or DENY for
 * every requested skill into a place the active SkillInvocationPolicy chain reads" before returning, and its
 * {@code sessionId} parameter names the same fallback — "A channel that stores session-scoped decisions must fall
 * back to agent scope when this is null". Dropping the write instead would turn every allow-listed skill into a
 * refusal at {@code SkillTool} time, because the scanner never re-runs the policy after the channel returns.
 *
 * <p>
 * Know exactly what that costs, because for an ALLOW it is a <b>privilege widening</b>: one session-less run leaves a
 * standing pre-approval for that skill on <em>every</em> session of that {@link AgentRuntimeId}, present and future,
 * interactive ones included. It has no TTL, survives {@code /clear}, is consulted before the rules, and is removed
 * only by {@code /revoke --agent}. Nobody typed "agent-wide" — the allow-list said "this skill", and the missing
 * session decided the reach. Every such write is therefore logged at <b>WARN</b> naming the skill and the runtime;
 * treat those lines as configuration errors to fix, not noise.
 *
 * <p>
 * Two ways to keep the reach where it was meant. Pass a real {@link SessionId} — the main-agent turn loop always does
 * (its {@code TranscriptBuffer} never carries a null one), so this path is reached only by hosts that call the
 * channel themselves or use the deprecated session-less scan. Or give headless and interactive traffic separate agent
 * runtime ids via discriminators, so an escalated grant cannot reach a session a human is watching.
 *
 * <h3>Cached answers do not re-consult the list</h3>
 * Once a decision has been written, the policy chain answers from the store, so changing the wiring later does not
 * retroactively revoke it. That is not a race here — the list is injected at construction and immutable — but it
 * does mean a redeploy with a shorter list leaves earlier grants in place for sessions that are still open.
 *
 * <h3>Failure behaviour</h3>
 * As with {@link DenyAllSkillApprovalChannel}, an empty {@code pendingRequests} list is a no-op rather than an error
 * and a failing store is logged and swallowed. Both fail closed: an unwritten decision leaves the skill at ASK, and
 * {@code SkillTool} refuses anything that is not ALLOW.
 *
 * <p>
 * Immutable, thread-safe, stateless per request.
 *
 * @see DenyAllSkillApprovalChannel
 */
public final class AllowListSkillApprovalChannel implements SkillApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(AllowListSkillApprovalChannel.class);

    private final ApprovalGrantWriter grantWriter;
    private final Set<String> allowedSkills;

    /**
     * Creates a channel that allows exactly the named skills and denies every other one.
     *
     * <p>
     * {@code allowedSkills} is defensively copied, so later changes to the caller's collection have no effect. Every
     * entry is validated eagerly: a null or blank name is a configuration mistake that would otherwise stay invisible
     * (it can never match a real skill name), so it fails here, at wiring time, naming the offending position.
     *
     * <p>
     * An empty collection is legal and produces a channel that denies everything — the same behaviour as
     * {@link DenyAllSkillApprovalChannel}. It is logged as a warning at construction, because it is far more often a
     * missing configuration value than a deliberate choice.
     *
     * @param sessionApprovalStore
     *            the store for session-scoped decisions — where a decision normally lands (must not be null)
     * @param agentApprovalStore
     *            the agent-scoped store, used only when the caller has no session (must not be null)
     * @param allowedSkills
     *            the exact {@link Skill#getName()} values to allow (must not be null; entries must not be null or
     *            blank; duplicates are collapsed)
     * @throws NullPointerException
     *             if any argument, or any entry of {@code allowedSkills}, is null
     * @throws IllegalArgumentException
     *             if an entry of {@code allowedSkills} is blank
     */
    public AllowListSkillApprovalChannel(SessionApprovalStore sessionApprovalStore,
            AgentApprovalStore agentApprovalStore, Collection<String> allowedSkills) {
        this.grantWriter = new ApprovalGrantWriter(sessionApprovalStore, agentApprovalStore);
        this.allowedSkills = copyOf(allowedSkills);
        if (this.allowedSkills.isEmpty()) {
            log.warn("Allow-list approval channel constructed with an empty allow-list; every skill will be denied");
        } else {
            log.debug("Allow-list approval channel will allow {} skill(s): {}", this.allowedSkills.size(),
                    this.allowedSkills);
        }
    }

    /**
     * Returns the allowed skill names, in the order they were supplied. Unmodifiable; useful for start-up diagnostics
     * and for a configuration layer that wants to echo back what it wired.
     */
    public Set<String> getAllowedSkills() {
        return allowedSkills;
    }

    /**
     * Session-less entry point, kept because {@link SkillApprovalChannel} declares it as the abstract method.
     * Delegates with no session, which forces every decision agent-scoped: each allow-listed skill in the batch
     * becomes a standing agent-wide pre-approval, logged at WARN — see the class javadoc. Prefer
     * {@link #requestApproval(List, AgentRuntimeId, SessionId)} and pass the real session whenever there is one.
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
            grantWriter.writeAll(pendingRequests, agentRuntimeId, sessionId, this::decide);
        } catch (RuntimeException e) {
            // The contract says we must not propagate: an exception here sends the execution to the suspend path,
            // which on a headless node nobody can resolve. Unwritten skills stay at ASK and are refused by SkillTool.
            log.warn("Failed to record allow-list decisions for {} pending skill request(s): {}",
                    pendingRequests.size(), e.getMessage(), e);
        }
    }

    private ApprovalGrant decide(String skillName) {
        if (allowedSkills.contains(skillName)) {
            log.debug("Allowing skill '{}': it is on the approval channel's allow-list", skillName);
            return ApprovalGrant.allowForSession();
        }
        log.info("Denying skill '{}': it is not on the approval channel's allow-list, and no operator is available to "
                + "approve it", skillName);
        return ApprovalGrant.denyForSession();
    }

    private static Set<String> copyOf(Collection<String> allowedSkills) {
        Objects.requireNonNull(allowedSkills, "allowedSkills cannot be null");
        final Set<String> copy = new LinkedHashSet<>();
        int index = 0;
        for (String skillName : allowedSkills) {
            Objects.requireNonNull(skillName, "allowedSkills[" + index + "] cannot be null");
            if (skillName.isBlank()) {
                throw new IllegalArgumentException("allowedSkills[" + index + "] cannot be blank");
            }
            copy.add(skillName);
            index++;
        }
        return Collections.unmodifiableSet(copy);
    }
}
