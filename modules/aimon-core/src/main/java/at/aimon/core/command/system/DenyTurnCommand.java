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
 * Built-in command that denies a turn suspended awaiting user approval.
 *
 * <p>
 * Removes the matching {@link PendingTurn} from the registry and records {@link SkillInvocationDecision#DENY} for the
 * skills it carried. Because the SK-11 atomic-suspension flow committed nothing to memory at suspend time, denial
 * requires no further cleanup — the agent loop has already returned with {@code SUSPENDED}, and the pending entry was
 * the only outstanding state. The user can then send a new prompt to redirect the agent.
 *
 * <p>
 * <b>Denial is session-scoped by default</b>, matching {@link ApproveTurnCommand} and matching what answering
 * {@code N} at an inline prompt does. A session-scoped denial also reaches the work that session delegates —
 * a user who refused a skill here would be surprised to find a subagent running it on their behalf. {@code --agent}
 * widens it to the whole agent runtime, and the same widening happens without the flag when the turn carries no
 * session, because there is then no narrower store to write to. Either way {@code /revoke} takes the decision
 * back.
 *
 * <p>
 * An earlier revision of this command deliberately cached <em>nothing</em>, on the grounds that re-issuing the prompt
 * should re-ask. That made the same answer mean two different things depending on whether a terminal happened to be
 * bound: {@code N} at an inline prompt blocked the skill for the session, while {@code /deny} on the identical
 * request did not. The scope split removed the reason for the asymmetry — a cached denial no longer outlives the
 * session that issued it.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /deny &lt;turn-id&gt;             - Drop the suspended turn and deny its skills for this session
 * /deny &lt;turn-id&gt; --agent     - Deny them for the whole agent, until /revoke --agent
 * </pre>
 *
 * <p>
 * Executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class DenyTurnCommand extends SystemCommand implements DirectExecutable {

    public static final String COMMAND_NAME = "deny";

    private final PendingTurnRegistry registry;
    private final SessionApprovalStore sessionApprovals;
    private final AgentApprovalStore agentApprovals;

    /**
     * Creates a command that drops the pending turn without caching the denial. Equivalent to
     * {@link #DenyTurnCommand(PendingTurnRegistry, SessionApprovalStore, AgentApprovalStore)} with both stores
     * null: with nowhere to record the decision, re-issuing the same prompt re-triggers the policy ASK.
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @throws NullPointerException
     *             if {@code registry} is null
     */
    public DenyTurnCommand(PendingTurnRegistry registry) {
        this(registry, null, null);
    }

    /**
     * Creates a new DenyTurnCommand.
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @param sessionApprovals
     *            session-scoped approval store, the default target (may be null)
     * @param agentApprovals
     *            agent-scoped approval store, the {@code --agent} target (may be null)
     * @throws NullPointerException
     *             if {@code registry} is null
     */
    public DenyTurnCommand(PendingTurnRegistry registry, SessionApprovalStore sessionApprovals,
            AgentApprovalStore agentApprovals) {
        super(COMMAND_NAME, "Deny a suspended turn by id (--agent to deny for the whole agent)");
        this.registry = Objects.requireNonNull(registry, "Pending turn registry cannot be null");
        this.sessionApprovals = sessionApprovals;
        this.agentApprovals = agentApprovals;
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final String rawArgs = request.getArguments().orElse("").trim();
        // Take the first non-flag token so users can paste a /pending output line, and so the flag may precede the id.
        final String idToken = ApprovalScopeArguments.firstOperand(rawArgs);
        if (idToken == null) {
            return CommandExecutionResult
                    .success("Usage: /deny <turn-id> [--agent]\nUse /pending to list suspended turns and their ids.");
        }
        final ApprovalScope requestedScope = ApprovalScopeArguments.scopeOf(rawArgs);

        final PendingTurnId turnId;
        try {
            turnId = PendingTurnId.of(idToken);
        } catch (IllegalArgumentException e) {
            return CommandExecutionResult.success("Invalid turn id: '" + idToken + "'");
        }

        final Optional<PendingTurn> removed = registry.remove(turnId);
        if (removed.isEmpty()) {
            return CommandExecutionResult.success("No pending turn found with id: " + idToken);
        }

        final PendingTurn turn = removed.get();
        final Set<String> denied = recordDenials(turn, requestedScope);
        return CommandExecutionResult.success("Denied turn " + turn.getId().value() + " (skills: ["
                + String.join(", ", denied) + "]). Send a new prompt to redirect the agent.");
    }

    /**
     * Writes DENY for each distinct skill the turn carried and returns those names in encounter order. Falls back to
     * the agent store when the requested scope has no store behind it, and records nothing at all when neither store
     * is wired — the turn is dropped either way, so a missing store degrades to the old single-shot behaviour rather
     * than failing the command.
     */
    private Set<String> recordDenials(PendingTurn turn, ApprovalScope requestedScope) {
        final SessionId sessionId = turn.getSessionId().orElse(null);
        final boolean sessionScoped = requestedScope == ApprovalScope.SESSION && sessionApprovals != null
                && sessionId != null;

        final Set<String> denied = new LinkedHashSet<>();
        for (PendingSkillRequest skill : turn.getPendingSkills()) {
            if (!denied.add(skill.getSkillName())) {
                continue;
            }
            if (sessionScoped) {
                sessionApprovals.put(sessionId, skill.getSkillName(), SkillInvocationDecision.DENY);
            } else if (agentApprovals != null) {
                agentApprovals.put(turn.getAgentRuntimeId(), skill.getSkillName(), SkillInvocationDecision.DENY);
            }
        }
        return denied;
    }
}
