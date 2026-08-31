package at.aimon.core.memory.deriver;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link DerivationQueueManager} runs its workers: how many, how much they may batch, and how long they wait
 * between polls.
 *
 * <p>
 * This is the tuning a queue implementation reads at construction, and nothing else. It is deliberately <em>not</em>
 * a configuration tree: it names no backend, no workspace and no peer, because the assembly that builds the queue
 * has already decided all three. The one model that describes a deployment's memory is
 * {@code at.aimon.bootstrap.spec.MemorySpec}, built from {@code aimon.memory.*} in the starter and from
 * {@code aimon.yaml}'s {@code memory} block in the CLI.
 *
 * <p>
 * It used to be a level of one — {@code MemoryProperties}, with sibling levels for the dialectic engine, the dreamer
 * and redaction. Those levels declared defaults that no assembly ever bound, which is a worse failure than having no
 * defaults at all: a reader could take {@code backend = "in-memory"} for the answer while the CLI was defaulting to
 * {@code file} and the starter was refusing to guess. Only this level was ever read, so only this level survives.
 *
 * <p>
 * Immutable. Every field is validated at construction, so a queue manager can use the values without re-checking
 * them.
 */
public final class DeriverProperties {

    private final int workerCount;
    private final int batchMaxTokens;
    private final Duration pollInterval;

    private DeriverProperties(int workerCount, int batchMaxTokens, Duration pollInterval) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1, got " + workerCount);
        }
        if (batchMaxTokens < 1) {
            throw new IllegalArgumentException("batchMaxTokens must be >= 1, got " + batchMaxTokens);
        }
        this.workerCount = workerCount;
        this.batchMaxTokens = batchMaxTokens;
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval cannot be null");
    }

    /**
     * Returns the tuning a queue gets when its assembly has no opinion.
     *
     * @return four workers, an 8000-token batch and a 500ms poll
     */
    public static DeriverProperties defaults() {
        return new DeriverProperties(4, 8000, Duration.ofMillis(500));
    }

    /**
     * Returns tuning with every value stated.
     *
     * @param workerCount
     *            how many worker threads drain the queue (must be >= 1)
     * @param batchMaxTokens
     *            the token budget one worker may put into a single derivation batch (must be >= 1)
     * @param pollInterval
     *            how long a worker waits before asking an empty queue again (must not be null)
     * @return the tuning
     * @throws IllegalArgumentException
     *             if {@code workerCount} or {@code batchMaxTokens} is below one
     */
    public static DeriverProperties of(int workerCount, int batchMaxTokens, Duration pollInterval) {
        return new DeriverProperties(workerCount, batchMaxTokens, pollInterval);
    }

    /**
     * Returns how many worker threads drain the queue.
     *
     * @return the worker count, at least one
     */
    public int getWorkerCount() {
        return workerCount;
    }

    /**
     * Returns the token budget for one derivation batch.
     *
     * @return the budget, at least one
     */
    public int getBatchMaxTokens() {
        return batchMaxTokens;
    }

    /**
     * Returns how long a worker waits before polling an empty queue again.
     *
     * @return the interval, never null
     */
    public Duration getPollInterval() {
        return pollInterval;
    }

    @Override
    public String toString() {
        return "DeriverProperties[workers=" + workerCount + ", batchMaxTokens=" + batchMaxTokens + ", pollInterval="
                + pollInterval + "]";
    }
}
