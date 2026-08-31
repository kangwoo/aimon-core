package at.aimon.cli.skill;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.ApprovalGrant;
import at.aimon.core.skill.policy.approval.ApprovalScope;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * CLI implementation of {@link SkillApprovalChannel} that resolves SK-11.6 ASK decisions inline by prompting the user
 * on the active JLine {@link LineReader}.
 *
 * <p>
 * Lifecycle: the channel is constructed by {@code AgentSetupFactory} before the REPL terminal is built, then bound by
 * {@code ReplSession.start()} via {@link #bindLineReader(LineReader)} and unbound in the matching {@code finally} via
 * {@link #unbindLineReader()}. Calls into {@link #requestApproval} that arrive while no reader is bound — headless
 * tests, programmatic invocation, the SDK facade — throw {@link IllegalStateException} which the
 * {@link at.aimon.core.skill.policy.SkillPreflightScanner SkillPreflightScanner} catches and falls back to the
 * legacy SK-11.4 suspend/resume path. This keeps the channel CLI-specific without requiring callers to detect the
 * environment up front.
 *
 * <p>
 * <b>Reader sharing.</b> The bound reader is the very same JLine {@link LineReader} instance the REPL uses to read
 * the next user prompt — it is not a second reader on the same {@link Terminal} (two readers would race on raw/cooked
 * mode). Re-using the REPL's reader is safe because the scanner is invoked synchronously while the REPL thread is
 * blocked inside {@code future.join()} waiting for the in-flight turn, so no concurrent {@code readLine} can run.
 *
 * <p>
 * <b>Lifetime hand-off.</b> The bound reader is owned by {@code ReplSession} which closes the underlying terminal in a
 * try-with-resources block when the REPL exits. The worker thread that drives the scanner can only call
 * {@link #requestApproval} during an active turn, so the REPL thread is blocked on {@code future.join()} and cannot
 * have reached the close yet — the volatile reference therefore cannot point at a closed terminal during a
 * legitimate request.
 *
 * <h3>Prompt behaviour</h3>
 * <ul>
 * <li>Pending requests are de-duplicated by skill name — both stores are keyed on {@code skillName} within their scope,
 * so two ASK invocations for the same skill in one turn share a single answer.</li>
 * <li>Each unique skill is prompted with {@code "Allow skill 'X'? [y/a/N]: "}. {@code y}/{@code yes} allows it
 * <em>for the current session</em>; {@code a}/{@code always} allows it for the whole agent runtime; everything
 * else, blank input included, denies it for the current session — fail-closed by default.</li>
 * <li>The two "yes" answers differ only in reach, and the reach is the user's to choose: a plain {@code y} is never
 * silently widened to the agent, because at the moment of answering the user can see this session and not the
 * others.</li>
 * <li>Decisions are written through to the store matching their {@link ApprovalScope} immediately after each prompt, so
 * that even a mid-loop SIGINT or terminal hangup leaves earlier answers persisted.</li>
 * <li>{@link UserInterruptException} (Ctrl+C) and {@link EndOfFileException} (Ctrl+D) are interpreted as "deny the
 * remaining skills <em>in this session</em>" so the user is never trapped, and one interrupt does not harden into
 * an agent-wide block — matches the safe-default required by the {@link SkillApprovalChannel} contract.</li>
 * <li>When no {@link SessionId} is supplied (the deprecated two-arg entry point, or a caller with no session
 * such as a scheduled task) session-scoped answers have nowhere to go and are written agent-scoped instead.
 * Dropping them is not an option: the scanner does not re-run the policy after the channel returns, so an unwritten
 * answer means the user is asked the same question again on the next turn.</li>
 * </ul>
 */
public final class InteractiveSkillApprovalChannel implements SkillApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(InteractiveSkillApprovalChannel.class);

    private final SessionApprovalStore sessionApprovalStore;
    private final AgentApprovalStore approvalStore;
    private final OutputFormatter formatter;
    /**
     * Set by {@link #bindLineReader(LineReader)} on REPL start, cleared by {@link #unbindLineReader()} on REPL exit.
     * Volatile because the bind/unbind happens on the REPL thread while {@link #requestApproval} runs on the agent
     * worker thread that the {@link at.aimon.core.skill.policy.SkillPreflightScanner SkillPreflightScanner} drives.
     */
    private volatile LineReader activeLineReader;

    /**
     * Creates a channel that writes user-confirmed decisions to the store matching the scope the user chose, and
     * renders prompts via {@code formatter}. The reader must be supplied separately via
     * {@link #bindLineReader(LineReader)} once the JLine REPL has built it.
     *
     * <p>
     * Both stores are required. The channel must be able to honour either answer, and the policy chain consults both,
     * so accepting only one would silently turn every {@code y} into the other scope.
     *
     * @param sessionApprovalStore
     *            the store for session-scoped answers — where a plain {@code y} lands (must not be null)
     * @param approvalStore
     *            the agent-scoped store, for {@code a} answers (must not be null)
     * @param formatter
     *            the formatter used to render the approval banner (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public InteractiveSkillApprovalChannel(SessionApprovalStore sessionApprovalStore, AgentApprovalStore approvalStore,
            OutputFormatter formatter) {
        this.sessionApprovalStore = Objects.requireNonNull(sessionApprovalStore, "sessionApprovalStore cannot be null");
        this.approvalStore = Objects.requireNonNull(approvalStore, "approvalStore cannot be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter cannot be null");
    }

    /**
     * Binds the active JLine {@link LineReader} so subsequent {@link #requestApproval} calls can prompt the user.
     * Idempotent — a second bind simply replaces the previous reference (e.g. when a REPL restart constructs a new
     * reader). MUST be the same reader the REPL uses for its main prompt; constructing a second reader on the same
     * terminal would race on JLine's raw/cooked mode flips.
     */
    public void bindLineReader(LineReader lineReader) {
        Objects.requireNonNull(lineReader, "lineReader cannot be null");
        this.activeLineReader = lineReader;
    }

    /** Clears the bound reader. Safe to call when nothing is bound. */
    public void unbindLineReader() {
        this.activeLineReader = null;
    }

    /**
     * Legacy entry point kept because {@link SkillApprovalChannel} still declares it as the abstract method. Delegates
     * with no session, which forces every answer agent-scoped — see the class javadoc. Prefer
     * {@link #requestApproval(List, AgentRuntimeId, SessionId)}.
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
            throw new IllegalArgumentException("pendingRequests cannot be empty");
        }
        final LineReader lineReader = this.activeLineReader;
        if (lineReader == null) {
            // No interactive REPL is bound — let the scanner fall back to the SK-11.4 suspend path so the user can
            // resolve out-of-band via /approve. Throwing here is the documented escape hatch the scanner catches.
            throw new IllegalStateException("No JLine LineReader bound; falling back to suspend path");
        }

        final Set<String> uniqueSkills = collectUniqueSkillNames(pendingRequests);
        // Track skills we have already persisted in this call so the abort path only writes DENY for the unanswered
        // ones. This avoids a check-then-act race against concurrent writers (e.g., a /approve command on another
        // thread) that an "if absent then put" pattern would have on top of either store.
        final Set<String> writtenSkills = new HashSet<>();

        formatter.displayInfo("[Skill approval] " + uniqueSkills.size() + " skill(s) need approval. "
                + "y = allow in this session, a = always allow for this agent (cleared by /revoke), "
                + "anything else = deny in this session.");

        try {
            for (String skillName : uniqueSkills) {
                final String answer = lineReader.readLine("Allow skill '" + skillName + "'? [y/a/N]: ");
                record(parseGrant(answer), skillName, agentRuntimeId, sessionId);
                writtenSkills.add(skillName);
            }
        } catch (UserInterruptException | EndOfFileException e) {
            // Ctrl+C / Ctrl+D mid-prompt: deny anything we haven't written yet so the user is not trapped.
            denyUnwritten(uniqueSkills, writtenSkills, agentRuntimeId, sessionId);
            formatter.displayInfo("[Skill approval] Aborted; remaining skills denied for this session.");
        } catch (RuntimeException e) {
            // The contract says we must not propagate; defensively deny anything still pending and log.
            log.warn("Approval prompt failed; denying remaining skills: {}", e.getMessage(), e);
            denyUnwritten(uniqueSkills, writtenSkills, agentRuntimeId, sessionId);
        }
    }

    private static Set<String> collectUniqueSkillNames(List<PendingSkillRequest> pendingRequests) {
        final Set<String> names = new LinkedHashSet<>();
        for (PendingSkillRequest request : pendingRequests) {
            names.add(request.getSkillName());
        }
        return names;
    }

    private static ApprovalGrant parseGrant(String answer) {
        if (answer == null) {
            return ApprovalGrant.denyForSession();
        }
        final String trimmed = answer.trim().toLowerCase(Locale.ROOT);
        if ("y".equals(trimmed) || "yes".equals(trimmed)) {
            return ApprovalGrant.allowForSession();
        }
        if ("a".equals(trimmed) || "always".equals(trimmed)) {
            return ApprovalGrant.allowForAgent();
        }
        return ApprovalGrant.denyForSession();
    }

    /**
     * Writes one grant to the store its scope names. A session-scoped grant with no session to scope it to
     * falls back to the agent store rather than being dropped — the scanner never re-runs the policy, so a dropped
     * write re-asks the user next turn instead of failing visibly.
     */
    private void record(ApprovalGrant grant, String skillName, AgentRuntimeId agentRuntimeId, SessionId sessionId) {
        if (grant.getScope() == ApprovalScope.SESSION && sessionId != null) {
            sessionApprovalStore.put(sessionId, skillName, grant.getDecision());
            log.debug("Recorded {} for skill '{}' in session {}", grant.getDecision(), skillName, sessionId);
            return;
        }
        approvalStore.put(agentRuntimeId, skillName, grant.getDecision());
        if (grant.getScope() == ApprovalScope.SESSION) {
            log.debug("No session bound; recorded {} for skill '{}' agent-wide on {} instead", grant.getDecision(),
                    skillName, agentRuntimeId);
        } else {
            log.debug("Recorded {} for skill '{}' agent-wide on {}", grant.getDecision(), skillName, agentRuntimeId);
        }
    }

    private void denyUnwritten(Set<String> uniqueSkills, Set<String> writtenSkills, AgentRuntimeId agentRuntimeId,
            SessionId sessionId) {
        for (String skillName : uniqueSkills) {
            if (!writtenSkills.contains(skillName)) {
                record(ApprovalGrant.denyForSession(), skillName, agentRuntimeId, sessionId);
            }
        }
    }
}
