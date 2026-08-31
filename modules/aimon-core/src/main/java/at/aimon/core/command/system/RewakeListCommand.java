package at.aimon.core.command.system;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.Constants;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;

/**
 * Built-in command that lists pending {@link RewakeEnvelope} entries held by the configured {@link RewakeService}.
 *
 * <p>
 * Resolves async-rewake design §7.3: gives operators a way to see which hooks have outstanding deferred fires —
 * which envelope id, which originating hook, which agent context, the trigger summary, the attempt number, the time
 * the envelope was first scheduled, and the human-readable {@code reason} carried over from the spec.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /rewakes            - List pending rewake envelopes
 * </pre>
 *
 * <p>
 * This command executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class RewakeListCommand extends SystemCommand implements DirectExecutable {

    private final RewakeService rewakeService;

    /**
     * Creates a new RewakeListCommand.
     *
     * @param rewakeService
     *            the rewake service to query (must not be null)
     * @throws NullPointerException
     *             if {@code rewakeService} is null
     */
    public RewakeListCommand(RewakeService rewakeService) {
        super("rewakes", "Display pending async-rewake envelopes");
        this.rewakeService = Objects.requireNonNull(rewakeService, "Rewake service cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final List<RewakeEnvelope> pending;
        try {
            pending = Objects.requireNonNullElse(rewakeService.listPending(), List.of());
        } catch (Exception e) {
            return CommandExecutionResult
                    .success("Pending rewakes:" + Constants.DOUBLE_NEWLINE + "Failed to query: " + e.getMessage());
        }

        final StringBuilder output = new StringBuilder();
        output.append("Pending rewakes:").append(Constants.DOUBLE_NEWLINE);

        if (pending.isEmpty()) {
            output.append("No pending rewakes.");
            return CommandExecutionResult.success(output.toString());
        }

        for (RewakeEnvelope envelope : pending) {
            appendEnvelope(output, envelope);
        }
        output.append(String.format("Total: %d pending envelope(s)", pending.size()));
        return CommandExecutionResult.success(output.toString());
    }

    private static void appendEnvelope(StringBuilder output, RewakeEnvelope envelope) {
        output.append("- ").append(envelope.getEnvelopeId()).append(Constants.NEWLINE);
        output.append("    hook:    ").append(envelope.getOriginatingHookId()).append(Constants.NEWLINE);
        output.append("    context: ").append(envelope.getAgentRuntimeId().value()).append(Constants.NEWLINE);
        output.append("    trigger: ").append(describeTrigger(envelope.getTrigger())).append(Constants.NEWLINE);
        output.append("    attempt: ").append(envelope.getAttemptNumber()).append(Constants.NEWLINE);
        output.append("    since:   ").append(envelope.getFirstScheduledAt()).append(Constants.NEWLINE);
        output.append("    reason:  ").append(envelope.getReason()).append(Constants.NEWLINE);
    }

    private static String describeTrigger(RewakeTrigger trigger) {
        if (trigger instanceof RewakeTriggerDelay delay) {
            return "delay " + delay.getDelay();
        }
        if (trigger instanceof RewakeTriggerEvent event) {
            return "event " + event.getEventType() + ":" + event.getEventKey();
        }
        if (trigger instanceof RewakeTriggerCron cron) {
            return "cron '" + cron.getExpression() + "' (" + cron.getZone() + ")";
        }
        return trigger.getClass().getSimpleName();
    }
}
