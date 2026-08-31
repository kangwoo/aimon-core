package at.aimon.core.memory.deriver;

import java.util.List;
import java.util.Objects;

import at.aimon.core.memory.Observation;

/**
 * Result of a single {@link Deriver#derive(DerivationContext)} invocation.
 *
 * <p>
 * Distinguishes newly created observations from those merged/updated by the
 * reconciler so callers can emit accurate metrics. {@code llmTokensUsed} is
 * cumulative across all LLM calls made during the derivation.
 */
public final class DerivationResult {

    private final List<Observation> created;
    private final List<Observation> updated;
    private final long llmTokensUsed;

    private DerivationResult(List<Observation> created, List<Observation> updated, long llmTokensUsed) {
        this.created = List.copyOf(Objects.requireNonNull(created, "created cannot be null"));
        this.updated = List.copyOf(Objects.requireNonNull(updated, "updated cannot be null"));
        if (llmTokensUsed < 0) {
            throw new IllegalArgumentException("llmTokensUsed must be >= 0, got " + llmTokensUsed);
        }
        this.llmTokensUsed = llmTokensUsed;
    }

    public static DerivationResult of(List<Observation> created, List<Observation> updated, long llmTokensUsed) {
        return new DerivationResult(created, updated, llmTokensUsed);
    }

    public static DerivationResult empty() {
        return new DerivationResult(List.of(), List.of(), 0L);
    }

    public List<Observation> getCreated() {
        return created;
    }

    public List<Observation> getUpdated() {
        return updated;
    }

    public long getLlmTokensUsed() {
        return llmTokensUsed;
    }

    public int totalObservations() {
        return created.size() + updated.size();
    }

    @Override
    public String toString() {
        return "DerivationResult{created=" + created.size() + ", updated=" + updated.size() + ", tokens="
                + llmTokensUsed + "}";
    }
}
