package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.exception.AgentException;

/**
 * Thrown when one or more {@code PreCompactHook}s return a blocked result, instructing the
 * {@link CompactionEngine} to abort the compaction.
 *
 * <p>
 * Caller policy:
 *
 * <ul>
 * <li>For {@link CompactionTrigger#AUTO} triggers, the engine surfaces this as {@code CompactionResult.failure}; the
 * {@link CompactionGuard} treats it as a hook-block (does NOT increment the circuit-breaker failure counter, since
 * blocks express intent rather than transient failure).
 * <li>For {@link CompactionTrigger#MANUAL} triggers, the engine downgrades hook blocks to warnings (per design
 * §6) and continues, so this exception is not produced.
 * </ul>
 *
 * <p>
 * Extends {@link AgentException} so blanket catches at the agent layer treat it consistently with other framework
 * errors.
 */
public class CompactionBlockedByHookException extends AgentException {

    private static final long serialVersionUID = 1L;

    private final List<String> blockedReasons;

    public CompactionBlockedByHookException(List<String> blockedReasons) {
        super(formatMessage(blockedReasons));
        this.blockedReasons = List.copyOf(Objects.requireNonNull(blockedReasons, "blockedReasons cannot be null"));
    }

    public List<String> getBlockedReasons() {
        return blockedReasons;
    }

    private static String formatMessage(List<String> blockedReasons) {
        Objects.requireNonNull(blockedReasons, "blockedReasons cannot be null");
        return "Compaction blocked by " + blockedReasons.size() + " hook(s): " + String.join("; ", blockedReasons);
    }
}
