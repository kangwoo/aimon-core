package at.aimon.core.agent.compact;

/**
 * Identifies what caused a {@link CompactionEngine} invocation.
 */
public enum CompactionTrigger {
    /**
     * Compaction was triggered automatically by {@link CompactionGuard} based on token thresholds.
     */
    AUTO,

    /**
     * Compaction was triggered by an explicit user or programmatic action (e.g., {@code /compact} command, direct
     * {@link CompactionEngine#compact} call).
     */
    MANUAL
}
