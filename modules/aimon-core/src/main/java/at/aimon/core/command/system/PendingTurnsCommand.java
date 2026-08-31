package at.aimon.core.command.system;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import at.aimon.core.agent.Constants;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.skill.policy.pending.PendingTurn;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

/**
 * Built-in command that lists every turn currently suspended awaiting user approval.
 *
 * <p>
 * Renders one line per pending turn so the user can pick an id for {@code /approve <id>} or {@code /deny <id>}. Output
 * includes the turn id, owning agent runtime, the session the turn belongs to, the skills awaiting approval, age
 * since suspension, and remaining TTL (or an {@code expired} marker when the timeout reaper has not yet swept the
 * entry).
 *
 * <p>
 * The session column is what tells the user how far an {@code /approve} of that turn will reach: with a session the
 * decision stops there, and {@code session=none} means it can only be recorded agent-wide. Every entry the agent loop
 * registers has one ({@link PendingTurn#getSessionId()}), so {@code none} shows up only for entries an embedder
 * registered itself — the column is rendered from the optional rather than assuming it.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * /pending           - List all suspended turns
 * </pre>
 *
 * <p>
 * Executes directly without LLM interaction via {@link DirectExecutable}.
 */
public final class PendingTurnsCommand extends SystemCommand implements DirectExecutable {

    public static final String COMMAND_NAME = "pending";

    private final PendingTurnRegistry registry;
    private final Clock clock;

    /**
     * Creates a new PendingTurnsCommand using the system UTC clock.
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @throws NullPointerException
     *             if {@code registry} is null
     */
    public PendingTurnsCommand(PendingTurnRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /**
     * Creates a new PendingTurnsCommand with an explicit clock (used by tests for deterministic age/TTL output).
     *
     * @param registry
     *            registry of suspended turns (must not be null)
     * @param clock
     *            clock used to compute age and remaining TTL (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public PendingTurnsCommand(PendingTurnRegistry registry, Clock clock) {
        super(COMMAND_NAME, "List turns suspended awaiting user approval");
        this.registry = Objects.requireNonNull(registry, "Pending turn registry cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, DirectCommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final List<PendingTurn> turns = registry.listAll();

        final StringBuilder output = new StringBuilder();
        output.append("Pending turns:").append(Constants.DOUBLE_NEWLINE);

        if (turns.isEmpty()) {
            output.append("No turns are currently suspended.");
            return CommandExecutionResult.success(output.toString());
        }

        final Instant now = clock.instant();
        for (PendingTurn turn : turns) {
            output.append(formatTurn(turn, now)).append(Constants.NEWLINE);
        }
        output.append(Constants.NEWLINE);
        output.append(String.format("Total: %d turn(s)", turns.size()));

        return CommandExecutionResult.success(output.toString());
    }

    private static String formatTurn(PendingTurn turn, Instant now) {
        final String skills = turn.getPendingSkills().stream().map(PendingSkillRequest::getSkillName)
                .collect(Collectors.joining(", "));
        final String age = formatDuration(Duration.between(turn.getCreatedAt(), now));
        final String ttl;
        if (turn.isExpired(now)) {
            ttl = "expired";
        } else {
            ttl = formatDuration(Duration.between(now, turn.getExpiresAt())) + " remaining";
        }
        final String session = turn.getSessionId().map(SessionId::value).orElse("none");
        return String.format("  %s  context=%s  session=%s  skills=[%s]  age=%s  %s", turn.getId().value(),
                turn.getAgentRuntimeId().value(), session, skills, age, ttl);
    }

    /**
     * Renders a duration as a compact human-readable string. Negative durations are clamped to zero so a tiny clock
     * skew never surfaces as a misleading negative age.
     */
    private static String formatDuration(Duration d) {
        Duration normalized = d.isNegative() ? Duration.ZERO : d;
        final long totalSeconds = normalized.getSeconds();
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        final long minutes = totalSeconds / 60;
        if (minutes < 60) {
            return minutes + "m" + (totalSeconds % 60 == 0 ? "" : (totalSeconds % 60) + "s");
        }
        final long hours = minutes / 60;
        return hours + "h" + (minutes % 60 == 0 ? "" : (minutes % 60) + "m");
    }
}
