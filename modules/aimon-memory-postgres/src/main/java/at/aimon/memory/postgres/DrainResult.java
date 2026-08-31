package at.aimon.memory.postgres;

/**
 * Outcome of one {@link KnowledgeStoreOutboxRelay#drainOnce()} pass.
 *
 * <p>
 * Counts the rows that were claimed in the batch and how each one resolved:
 * <ul>
 * <li>{@code processed} — successfully dispatched and removed from the outbox.
 * <li>{@code failed} — dispatch raised but the row is still retryable (attempt
 * count incremented, {@code next_attempt_at} pushed out).
 * <li>{@code poisoned} — dispatch failed for the {@code maxAttempts}-th time;
 * the row stays in the table with {@code claimed_by='POISON'} and is excluded
 * from future drains.
 * </ul>
 */
public final class DrainResult {

    private final int processed;
    private final int failed;
    private final int poisoned;

    public DrainResult(int processed, int failed, int poisoned) {
        if (processed < 0 || failed < 0 || poisoned < 0) {
            throw new IllegalArgumentException(
                    "DrainResult counts must be >= 0; got " + processed + "/" + failed + "/" + poisoned);
        }
        this.processed = processed;
        this.failed = failed;
        this.poisoned = poisoned;
    }

    public int getProcessed() {
        return processed;
    }

    public int getFailed() {
        return failed;
    }

    public int getPoisoned() {
        return poisoned;
    }

    /** Total rows claimed in the batch (sum of all three buckets). */
    public int getClaimed() {
        return processed + failed + poisoned;
    }

    @Override
    public String toString() {
        return "DrainResult{processed=" + processed + ", failed=" + failed + ", poisoned=" + poisoned + "}";
    }
}
