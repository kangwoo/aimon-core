package at.aimon.core.command.system;

import java.util.Objects;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Built-in clear command that clears the conversation history.
 *
 * <p>
 * The clear command:
 *
 * <ul>
 * <li>Deletes the current session from the repository
 * <li>Resets the session to a fresh state
 * <li>Drops the skill approvals granted in that session, when a {@link SessionApprovalStore} is wired
 * <li>Preserves system configuration and settings
 * </ul>
 *
 * <p>
 * <b>Why approvals go too.</b> "I reset the session but the skill I approved earlier still runs without asking" is
 * the surprising behaviour, not the other way round: a session-scoped answer has no meaning once the session
 * it was given in is gone. Agent-wide approvals — the ones the user explicitly widened by answering {@code a}, or
 * granted with {@code /approve --agent} — are deliberately <em>not</em> touched here; {@code /revoke --agent} is the
 * command for those.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /clear             - Clear conversation history
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}. It's a state management
 * operation with no tools restrictions.
 */
public final class ClearCommand extends SystemCommand implements DirectExecutable {

    private final SessionApprovalStore sessionApprovals;

    /**
     * Creates a ClearCommand that clears history only, leaving every approval cache untouched. Equivalent to
     * {@link #ClearCommand(SessionApprovalStore)} with {@code null}.
     */
    public ClearCommand() {
        this(null);
    }

    /**
     * Creates a new ClearCommand.
     *
     * @param sessionApprovals
     *            the session-scoped approval store to purge alongside the history (may be null when SK-11 wiring
     *            is absent, in which case only the history is cleared)
     */
    public ClearCommand(SessionApprovalStore sessionApprovals) {
        super("clear", "Clear conversation history");
        this.sessionApprovals = sessionApprovals;
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        // Get session snapshot from request
        final SessionSnapshot previousSnapshot = request.getPreviousSnapshot().orElse(null);

        if (previousSnapshot == null) {
            return CommandExecutionResult.success("No active conversation to clear.");
        }

        final int messageCount = previousSnapshot.getConversationHistory().size();

        // Clear the transcript buffer if available
        if (context.getTranscriptBuffer() != null) {
            context.getTranscriptBuffer().clear();
        }

        final boolean approvalsCleared = clearApprovals(previousSnapshot.getSessionId());

        final String message = String.format("Conversation cleared. Removed %d message(s).%s", messageCount,
                approvalsCleared ? " Skill approvals granted in this session were dropped." : "");
        return CommandExecutionResult.success(message);
    }

    /**
     * Drops the session's approval entries. Reports whether anything could be dropped so the success message only
     * claims it when a store was actually wired — an unconditional claim would tell the user their approvals are gone
     * in setups where nothing consulted a session store to begin with.
     */
    private boolean clearApprovals(SessionId sessionId) {
        if (sessionApprovals == null || sessionId == null) {
            return false;
        }
        sessionApprovals.invalidate(sessionId);
        return true;
    }
}
