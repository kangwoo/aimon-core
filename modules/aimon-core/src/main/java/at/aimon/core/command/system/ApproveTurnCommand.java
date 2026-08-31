package at.aimon.core.command.system;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.ApprovalScope;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnId;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Built-in command that approves a turn suspended awaiting user approval.
 *
 * <p>
 * Resolves the pending turn by id and, for each skill it carried at suspend time, writes
 * {@link SkillInvocationDecision#ALLOW} into an approval store. The pending entry is then removed from the registry.
 * Subsequent invocations of those skills short-circuit through the cache and resolve to {@code ALLOW} without
 * re-prompting, so resuming the turn (re-running the agent) will pass the pre-flight scan and proceed.
 *
 * <p>
 * <b>Approval is session-scoped by default.</b> It lands in the {@link SessionApprovalStore} keyed by the
 * suspended turn's own {@link SessionId}, so it reaches that session and the work it delegates, and no
 * unrelated session. This is the out-of-band twin of answering {@code y} at an inline prompt, and it carries the
 * same reach — the user approving a turn can see the session it belongs to, and cannot see the agent's other
 * sessions.
 *
 * <p>
 * <b>{@code --agent} widens it.</b> With the flag the decision goes to the {@link AgentApprovalStore} keyed by the
 * turn's {@link at.aimon.core.agent.AgentRuntimeId}, which is derived from the {@code (Agent, discriminator)} pair — so
 * it also applies to every later session and {@code LiveSession} of the same agent, and survives {@code /clear}.
 * Nothing drops it automatically; the user revokes it with {@code /revoke --agent} ({@link RevokeApprovalsCommand}),
 * or the owner of the runtime does so by destroying it. The same widening happens without the flag when the turn
 * carries no session, because there is then no narrower store to write to — a fallback kept for entries an embedder
 * registered itself, since no suspension the framework performs omits the session
 * ({@link PendingTurn#getSessionId()}). Subagent forks never appear here at all — they cannot suspend a turn for
 * approval, and instead inherit the spawning session's decision through {@code invokingSessionId}.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /approve &lt;turn-id&gt;             - Approve the turn's skills for its session
 * /approve &lt;turn-id&gt; --agent     - Approve them for the whole agent, until /revoke --agent
 * </pre>
 *
 * <p>
 * Executes directly without LLM interaction via {@link DirectExecutable}. Triggering the actual agent re-execution is
 * the responsibility of the surrounding REPL — this command updates the approval state and clears the pending entry.
 */
public final class ApproveTurnCommand extends SystemCommand implements DirectExecutable {

    public static final String COMMAND_NAME = "approve";

    private final PendingTurnRegistry registry;
    private final SessionApprovalStore sessionApprovals;
    private final AgentApprovalStore agentApprovals;

    /**
     * Creates a command that can only grant agent-wide approvals. Equivalent to
     * {@link #ApproveTurnCommand(PendingTurnRegistry, SessionApprovalStore, AgentApprovalStore)} with a null
     * session store.
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @param agentApprovals
     *            agent-scoped approval store (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public ApproveTurnCommand(PendingTurnRegistry registry, AgentApprovalStore agentApprovals) {
        this(registry, null, agentApprovals);
    }

    /**
     * Creates a new ApproveTurnCommand.
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @param sessionApprovals
     *            session-scoped approval store, the default target (may be null when SK-11 wiring is absent)
     * @param agentApprovals
     *            agent-scoped approval store, the {@code --agent} target (must not be null)
     * @throws NullPointerException
     *             if {@code registry} or {@code agentApprovals} is null
     */
    public ApproveTurnCommand(PendingTurnRegistry registry, SessionApprovalStore sessionApprovals,
            AgentApprovalStore agentApprovals) {
        super(COMMAND_NAME, "Approve a suspended turn by id (--agent to approve for the whole agent)");
        this.registry = Objects.requireNonNull(registry, "Pending turn registry cannot be null");
        this.sessionApprovals = sessionApprovals;
        this.agentApprovals = Objects.requireNonNull(agentApprovals, "Agent approval store cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final String rawArgs = request.getArguments().orElse("").trim();
        // Take the first non-flag token so users can paste a /pending output line, and so the flag may precede the id.
        final String idToken = ApprovalScopeArguments.firstOperand(rawArgs);
        if (idToken == null) {
            return CommandExecutionResult.success(
                    "Usage: /approve <turn-id> [--agent]\nUse /pending to list suspended turns and their ids.");
        }
        final ApprovalScope requestedScope = ApprovalScopeArguments.scopeOf(rawArgs);

        final PendingTurnId turnId;
        try {
            turnId = PendingTurnId.of(idToken);
        } catch (IllegalArgumentException e) {
            return CommandExecutionResult.success("Invalid turn id: '" + idToken + "'");
        }

        final Optional<PendingTurn> lookup = registry.get(turnId);
        if (lookup.isEmpty()) {
            return CommandExecutionResult.success("No pending turn found with id: " + idToken);
        }

        final PendingTurn turn = lookup.get();
        final SessionId sessionId = turn.getSessionId().orElse(null);
        final boolean sessionScoped = requestedScope == ApprovalScope.SESSION && sessionApprovals != null
                && sessionId != null;

        // Cache ALLOW for each distinct skill name carried by the turn before removing the entry, so a concurrent
        // /deny or timeout-reaper sweep cannot leave us with the entry gone but no approvals recorded.
        final Set<String> approved = new LinkedHashSet<>();
        for (PendingSkillRequest skill : turn.getPendingSkills()) {
            if (approved.add(skill.getSkillName())) {
                if (sessionScoped) {
                    sessionApprovals.put(sessionId, skill.getSkillName(), SkillInvocationDecision.ALLOW);
                } else {
                    agentApprovals.put(turn.getAgentRuntimeId(), skill.getSkillName(), SkillInvocationDecision.ALLOW);
                }
            }
        }
        registry.remove(turnId);

        final String reach = sessionScoped
                ? "in session " + sessionId.value()
                : "for agent " + turn.getAgentRuntimeId().value();
        return CommandExecutionResult.success("Approved turn " + turn.getId().value() + " (skills: ["
                + String.join(", ", approved) + "]) " + reach + ". Resume the agent to continue.");
    }
}
