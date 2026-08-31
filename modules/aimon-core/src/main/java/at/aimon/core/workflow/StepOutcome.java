package at.aimon.core.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.CompletionReason;

/**
 * Transcript-free cache value for one completed {@code agent()} step (design §5.3).
 *
 * <p>
 * The {@code StepResultCache} memoizes only the step's <em>outcome</em> — the final text, any parsed structured output,
 * the token/cost totals needed to re-hydrate the run's budget counters on resume, the completion reason, and an input
 * hash for validation. It deliberately does <b>not</b> carry the conversation transcript or the inline {@code Subagent}
 * / {@code AgentTask} graph: those pull in serializers workflow must not depend on and cannot faithfully restore
 * an inline subagent from bytes (design §5.3c). On resume the run re-attaches the caller's live
 * {@code AgentTask}
 * and reconstructs an {@code AgentStepResult} whose transcript is empty — the outcome is preserved, the transcript is
 * not.
 *
 * <p>
 * Only steps that finished normally ({@link CompletionReason#COMPLETED}) are cached, so a replayed step is never a
 * failure or interruption. Immutable value object.
 */
public final class StepOutcome {

    /**
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final String text;
    private final Map<String, Object> structured;
    private final long totalTokens;
    private final long costMicros;
    private final CompletionReason completionReason;
    private final String inputHash;
    private final String structureFingerprint;

    private StepOutcome(Builder builder) {
        this.text = Objects.requireNonNull(builder.text, "text cannot be null");
        this.structured = builder.structured == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.structured));
        // Replay feeds these straight into the run's budget accumulators; a negative value (a corrupted persistent
        // cache object, or a buggy caller) would silently DECREMENT the tracked spend and defeat the ceilings, so
        // reject it here — a decode failure then degrades to a cache miss instead of a weakened budget.
        if (builder.totalTokens < 0) {
            throw new IllegalArgumentException("totalTokens must be >= 0, got: " + builder.totalTokens);
        }
        if (builder.costMicros < 0) {
            throw new IllegalArgumentException("costMicros must be >= 0, got: " + builder.costMicros);
        }
        this.totalTokens = builder.totalTokens;
        this.costMicros = builder.costMicros;
        this.completionReason = Objects.requireNonNull(builder.completionReason, "completionReason cannot be null");
        this.inputHash = Objects.requireNonNull(builder.inputHash, "inputHash cannot be null");
        this.structureFingerprint = Objects.requireNonNull(builder.structureFingerprint,
                "structureFingerprint cannot be null");
    }

    /**
     * @return the subagent's final answer text (never null)
     */
    public String text() {
        return text;
    }

    /**
     * @return the parsed, schema-validated structured output, or empty when the step produced none
     */
    public Optional<Map<String, Object>> structured() {
        return Optional.ofNullable(structured);
    }

    /**
     * @return the total tokens the step consumed (used to re-hydrate the run's token budget on a cache-hit replay)
     */
    public long totalTokens() {
        return totalTokens;
    }

    /**
     * @return the step cost in USD micros, as priced by the subagent executor's {@code CostEstimator} (used to
     *         re-hydrate the run's cost budget on a cache-hit replay; {@code 0} when no pricing is wired)
     */
    public long costMicros() {
        return costMicros;
    }

    /**
     * @return why the step stopped — always {@link CompletionReason#COMPLETED} for a cached outcome (never null)
     */
    public CompletionReason completionReason() {
        return completionReason;
    }

    /**
     * @return the hash of the step's input (goal + inline subagent definition + result schema), used on load to detect
     *         that the script changed what runs at this path and invalidate a stale entry (never null)
     */
    public String inputHash() {
        return inputHash;
    }

    /**
     * @return the structure fingerprint (design §6.5): a left-context digest chaining the preceding-sibling leaf input
     *         hashes and construct child-counts. Validated on load alongside {@link #inputHash()} so a homogeneous
     *         same-definition sibling shift cannot replay a sibling's cached outcome (never null)
     */
    public String structureFingerprint() {
        return structureFingerprint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StepOutcome that = (StepOutcome) o;
        return totalTokens == that.totalTokens && costMicros == that.costMicros && text.equals(that.text)
                && Objects.equals(structured, that.structured) && completionReason == that.completionReason
                && inputHash.equals(that.inputHash) && structureFingerprint.equals(that.structureFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, structured, totalTokens, costMicros, completionReason, inputHash,
                structureFingerprint);
    }

    @Override
    public String toString() {
        return "StepOutcome{" + "tokens=" + totalTokens + ", costMicros=" + costMicros + ", reason=" + completionReason
                + ", inputHash='" + inputHash + '\'' + ", structureFingerprint='" + structureFingerprint + '\'' + '}';
    }

    /** Builder for {@link StepOutcome}. */
    public static final class Builder {
        private String text;
        private Map<String, Object> structured;
        private long totalTokens;
        private long costMicros;
        private CompletionReason completionReason;
        private String inputHash;
        private String structureFingerprint;

        private Builder() {
        }

        /** Sets the final answer text. */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /** Sets the parsed structured output (nullable; defensively copied). */
        public Builder structured(Map<String, Object> structured) {
            this.structured = structured;
            return this;
        }

        /** Sets the total tokens consumed. */
        public Builder totalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        /** Sets the step cost in USD micros. */
        public Builder costMicros(long costMicros) {
            this.costMicros = costMicros;
            return this;
        }

        /** Sets the completion reason. */
        public Builder completionReason(CompletionReason completionReason) {
            this.completionReason = completionReason;
            return this;
        }

        /** Sets the input hash. */
        public Builder inputHash(String inputHash) {
            this.inputHash = inputHash;
            return this;
        }

        /** Sets the structure fingerprint (design §6.5). */
        public Builder structureFingerprint(String structureFingerprint) {
            this.structureFingerprint = structureFingerprint;
            return this;
        }

        /**
         * @return a new immutable {@link StepOutcome}
         */
        public StepOutcome build() {
            return new StepOutcome(this);
        }
    }
}
