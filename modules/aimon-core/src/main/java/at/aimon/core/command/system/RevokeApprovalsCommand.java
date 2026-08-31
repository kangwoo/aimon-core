package at.aimon.core.command.system;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.ApprovalScope;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Built-in command that takes back skill approvals the user has granted.
 *
 * <p>
 * Approvals granted by answering an ASK prompt — whether inline through a
 * {@link at.aimon.core.skill.policy.approval.SkillApprovalChannel} or out-of-band via {@code /approve} — are cached so
 * the same skill does not ask again. This command is the user-facing way to take those answers back.
 *
 * <p>
 * <b>Two scopes, narrow by default.</b> A plain {@code y} lands in the {@link SessionApprovalStore} and reaches
 * the session that granted it plus the runs that session delegates, and no unrelated session; an
 * {@code a} (or {@code /approve --agent}) lands in the
 * {@link AgentApprovalStore} keyed by {@link AgentRuntimeId} and reaches every session of that agent, present
 * and future, with no TTL. {@code /revoke} mirrors that split:
 *
 * <ul>
 * <li>{@code /revoke} — drops this session's approvals and leaves the agent-wide ones alone.
 * <li>{@code /revoke --agent} — drops the agent-wide ones <em>and</em> this session's. The broader command is a
 * superset on purpose: "revoke everything for this agent" that silently left a narrower approval running would be the
 * surprising outcome.
 * </ul>
 *
 * <p>
 * Revocation never weakens security. On a cache miss the configured
 * {@link at.aimon.core.skill.policy.SkillInvocationPolicy} is consulted again, so the worst outcome of revoking is
 * being asked once more.
 *
 * <p>
 * <b>Scope limits.</b> Only cached approvals are cleared. A turn already suspended awaiting approval keeps its entry in
 * the {@link at.aimon.core.skill.policy.pending.PendingTurnRegistry} — use {@code /deny <turn-id>} for that — and
 * rules configured statically in the policy are untouched, since the user never granted those interactively.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /revoke            - Forget the skill approvals granted in this session
 * /revoke --agent    - Forget every skill approval granted for this agent
 * </pre>
 *
 * <p>
 * Executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class RevokeApprovalsCommand extends SystemCommand implements DirectExecutable {

    public static final String COMMAND_NAME = "revoke";

    private final SessionApprovalStore sessionApprovals;
    private final AgentApprovalStore agentApprovals;

    /**
     * Creates a command that can only revoke agent-wide approvals. Equivalent to
     * {@link #RevokeApprovalsCommand(SessionApprovalStore, AgentApprovalStore)} with a null session store:
     * a plain {@code /revoke} then falls back to the agent scope, because there is no narrower store to clear.
     *
     * @param agentApprovals
     *            the agent-scoped approval store to clear (must not be null)
     * @throws NullPointerException
     *             if {@code agentApprovals} is null
     */
    public RevokeApprovalsCommand(AgentApprovalStore agentApprovals) {
        this(null, agentApprovals);
    }

    /**
     * Creates a new RevokeApprovalsCommand.
     *
     * @param sessionApprovals
     *            the session-scoped approval store, cleared by a plain {@code /revoke} (may be null when SK-11
     *            wiring is absent)
     * @param agentApprovals
     *            the agent-scoped approval store, cleared by {@code /revoke --agent} (must not be null)
     * @throws NullPointerException
     *             if {@code agentApprovals} is null
     */
    public RevokeApprovalsCommand(SessionApprovalStore sessionApprovals, AgentApprovalStore agentApprovals) {
        super(COMMAND_NAME, "Forget the skill approvals granted in this session (--agent for agent-wide)");
        this.sessionApprovals = sessionApprovals;
        this.agentApprovals = Objects.requireNonNull(agentApprovals, "Agent approval store cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final ApprovalScope scope = ApprovalScopeArguments.scopeOf(request.getArguments().orElse(null));
        final SessionId sessionId = resolveSessionId(context);

        if (scope == ApprovalScope.SESSION && sessionApprovals != null && sessionId != null) {
            sessionApprovals.invalidate(sessionId);
            return CommandExecutionResult.success("Revoked the skill approvals granted in session " + sessionId.value()
                    + ". Approvals widened to the whole agent are untouched — use " + "/revoke --agent for those.");
        }

        final Optional<AgentRuntimeId> runtimeId = context.getToolContext().get(ToolContextKeys.AGENT_RUNTIME_ID);
        if (runtimeId.isEmpty()) {
            // Without the key there is no store partition to clear; say so rather than silently reporting success.
            return CommandExecutionResult
                    .success("Cannot revoke: no agent runtime is bound to this command execution.");
        }

        agentApprovals.invalidate(runtimeId.get());
        // An agent-wide revoke must subsume the narrow scope, otherwise a session-scoped ALLOW would survive a
        // command the user reasonably read as "forget everything".
        if (sessionApprovals != null && sessionId != null) {
            sessionApprovals.invalidate(sessionId);
        }
        return CommandExecutionResult.success("Revoked all skill approvals for " + runtimeId.get().value()
                + ". Skills that need approval will ask again.");
    }

    /**
     * Resolves the session this command runs in. Reads the
     * {@link at.aimon.core.agent.session.transcript.TranscriptBuffer}
     * rather than the tool context because the command execution path always carries the memory, while the tool context
     * only carries the keys the executor chose to forward.
     */
    private static SessionId resolveSessionId(CommandExecutionContext context) {
        if (context.getTranscriptBuffer() == null) {
            return null;
        }
        return context.getTranscriptBuffer().getSessionId();
    }
}
