package at.aimon.core.llm.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.llm.ReasoningEffort;

/**
 * One model's capabilities as a <em>configuration surface</em> states them: every flag optional, absence meaning
 * "not declared" rather than {@code false}.
 *
 * <p>
 * This is the neutral shape the two configuration surfaces — the CLI's {@code llm.modelCapabilities} yaml block and
 * the starter's {@code aimon.llm.model-capabilities} properties — are translated into before
 * {@link InMemoryModelCapabilityRegistry#withDefaultsExtendedBy(java.util.Map)} turns them into registry entries. It
 * exists so that the rules governing a declaration (what an omitted flag means, that a declaration extends the
 * built-in table rather than replacing it, that a name is registered as an exact match) have one implementation
 * rather than one per surface: two copies of those rules drift, and the drift is silent — the request succeeds with
 * different parameters on it.
 *
 * <p>
 * <strong>Why the fields are boxed.</strong> {@link ModelCapabilities} is total: every flag has a value, seeded from
 * {@link ModelCapabilities#unknown()}. That is the right shape for the descriptor a client reads and the wrong one
 * for what an operator writes, because it cannot distinguish "I said false" from "I said nothing". {@code null} here
 * is the latter, and {@link #capabilities()} resolves it by leaving that flag at its fail-open value — which is
 * exactly what {@link ModelCapabilities.Builder} already does for a setter nobody calls. So the defaulting rule is
 * not restated here; this type only records which setters to call.
 *
 * <p>
 * The model name is deliberately <em>not</em> a field. On both surfaces the name is the map key, and a thing with two
 * places to say its name has two places to disagree.
 *
 * <p>
 * The builder accepts {@code null} for every setter, which is an intentional exception to this repository's usual
 * {@code requireNonNull} habit: {@code null} is this type's vocabulary for "omitted", so refusing it would leave a
 * partial declaration with no way to express itself. What {@link Builder#build()} does refuse is a declaration that
 * omits <em>everything</em> — see there.
 *
 * <p>
 * Thread-safe and immutable.
 *
 * @see InMemoryModelCapabilityRegistry#withDefaultsExtendedBy(java.util.Map)
 */
public final class ModelCapabilityDeclaration {

    private final Boolean supportsSamplingParameters;
    private final Boolean supportsReasoningEffort;
    private final Boolean supportsToolsWithReasoning;
    private final Boolean supportsReasoningTraceRoundTrip;
    private final ReasoningEffort lowestReasoningEffort;
    private final Set<ReasoningEffort> acceptedReasoningEfforts;
    private final ThinkingDialect thinkingDialect;
    private final Boolean supportsReasoningSummary;
    private final ModelCapabilities capabilities;

    private ModelCapabilityDeclaration(Builder builder) {
        this.supportsSamplingParameters = builder.supportsSamplingParameters;
        this.supportsReasoningEffort = builder.supportsReasoningEffort;
        this.supportsToolsWithReasoning = builder.supportsToolsWithReasoning;
        this.supportsReasoningTraceRoundTrip = builder.supportsReasoningTraceRoundTrip;
        this.lowestReasoningEffort = builder.lowestReasoningEffort;
        this.thinkingDialect = builder.thinkingDialect;
        this.supportsReasoningSummary = builder.supportsReasoningSummary;
        // Resolved before the defensive copy below, and the order matters: resolve() is where an empty or
        // null-bearing ladder is refused by name, while EnumSet.copyOf would beat it to the exception with a
        // message about a collection.
        this.capabilities = resolve(builder);
        this.acceptedReasoningEfforts = builder.acceptedReasoningEfforts == null
                ? null
                : Collections.unmodifiableSet(EnumSet.copyOf(builder.acceptedReasoningEfforts));
    }

    private static ModelCapabilities resolve(Builder builder) {
        final ModelCapabilities.Builder resolved = ModelCapabilities.builder();
        if (builder.supportsSamplingParameters != null) {
            resolved.supportsSamplingParameters(builder.supportsSamplingParameters);
        }
        if (builder.supportsReasoningEffort != null) {
            resolved.supportsReasoningEffort(builder.supportsReasoningEffort);
        }
        if (builder.supportsToolsWithReasoning != null) {
            resolved.supportsToolsWithReasoning(builder.supportsToolsWithReasoning);
        }
        if (builder.supportsReasoningTraceRoundTrip != null) {
            resolved.supportsReasoningTraceRoundTrip(builder.supportsReasoningTraceRoundTrip);
        }
        // Only one of the two can be set -- build() refuses the pair -- so the order of these branches is not a
        // precedence rule, and neither is a fallback for the other.
        if (builder.lowestReasoningEffort != null) {
            resolved.lowestReasoningEffort(builder.lowestReasoningEffort);
        }
        if (builder.acceptedReasoningEfforts != null) {
            resolved.acceptedReasoningEfforts(builder.acceptedReasoningEfforts);
        }
        if (builder.thinkingDialect != null) {
            resolved.thinkingDialect(builder.thinkingDialect);
        }
        if (builder.supportsReasoningSummary != null) {
            resolved.supportsReasoningSummary(builder.supportsReasoningSummary);
        }
        return resolved.build();
    }

    /**
     * @return a new builder with nothing declared
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#supportsSamplingParameters()}, and what it says
     */
    public Optional<Boolean> supportsSamplingParameters() {
        return Optional.ofNullable(supportsSamplingParameters);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#supportsReasoningEffort()}, and what it says
     */
    public Optional<Boolean> supportsReasoningEffort() {
        return Optional.ofNullable(supportsReasoningEffort);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#supportsToolsWithReasoning()}, and what it says
     */
    public Optional<Boolean> supportsToolsWithReasoning() {
        return Optional.ofNullable(supportsToolsWithReasoning);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#supportsReasoningTraceRoundTrip()}, and what it
     *         says
     */
    public Optional<Boolean> supportsReasoningTraceRoundTrip() {
        return Optional.ofNullable(supportsReasoningTraceRoundTrip);
    }

    /**
     * @return whether the declaration states this model's reasoning ladder <em>as a floor</em>, and where it starts.
     *         The sibling {@link #acceptedReasoningEfforts()} is the general form; a declaration states at most one
     *         of the two
     */
    public Optional<ReasoningEffort> lowestReasoningEffort() {
        return Optional.ofNullable(lowestReasoningEffort);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#acceptedReasoningEfforts()} in full, and what it
     *         says. Empty when the declaration used the {@link #lowestReasoningEffort()} shorthand instead, or stated
     *         no ladder at all — this getter reports what was <em>written</em>, and
     *         {@link #capabilities()} is where the two spellings meet
     */
    public Optional<Set<ReasoningEffort>> acceptedReasoningEfforts() {
        return Optional.ofNullable(acceptedReasoningEfforts);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#thinkingDialect()}, and what it says
     */
    public Optional<ThinkingDialect> thinkingDialect() {
        return Optional.ofNullable(thinkingDialect);
    }

    /**
     * @return whether the declaration states {@link ModelCapabilities#supportsReasoningSummary()}, and what it says
     */
    public Optional<Boolean> supportsReasoningSummary() {
        return Optional.ofNullable(supportsReasoningSummary);
    }

    /**
     * The descriptor this declaration stands for: the flags it states, with every flag it does not state left at
     * {@link ModelCapabilities#unknown()}'s value.
     *
     * <p>
     * Equal to calling {@link ModelCapabilities#builder()} and invoking only the setters the declaration filled in,
     * which is the point — a configuration entry that names one flag changes that one flag and nothing else.
     *
     * @return the resolved capabilities (never null)
     */
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ModelCapabilityDeclaration that = (ModelCapabilityDeclaration) o;
        return Objects.equals(supportsSamplingParameters, that.supportsSamplingParameters)
                && Objects.equals(supportsReasoningEffort, that.supportsReasoningEffort)
                && Objects.equals(supportsToolsWithReasoning, that.supportsToolsWithReasoning)
                && Objects.equals(supportsReasoningTraceRoundTrip, that.supportsReasoningTraceRoundTrip)
                && lowestReasoningEffort == that.lowestReasoningEffort
                && Objects.equals(acceptedReasoningEfforts, that.acceptedReasoningEfforts)
                && thinkingDialect == that.thinkingDialect
                && Objects.equals(supportsReasoningSummary, that.supportsReasoningSummary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,
                supportsReasoningTraceRoundTrip, lowestReasoningEffort, acceptedReasoningEfforts, thinkingDialect,
                supportsReasoningSummary);
    }

    @Override
    public String toString() {
        return "ModelCapabilityDeclaration{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + ", supportsReasoningTraceRoundTrip=" + supportsReasoningTraceRoundTrip
                + ", lowestReasoningEffort=" + lowestReasoningEffort + ", acceptedReasoningEfforts="
                + acceptedReasoningEfforts + ", thinkingDialect=" + thinkingDialect + ", supportsReasoningSummary="
                + supportsReasoningSummary + '}';
    }

    /**
     * Builder for {@link ModelCapabilityDeclaration}.
     *
     * <p>
     * Every setter accepts {@code null}, meaning "not declared". {@link #build()} refuses only the declaration that
     * says nothing at all.
     */
    public static final class Builder {
        private Boolean supportsSamplingParameters;
        private Boolean supportsReasoningEffort;
        private Boolean supportsToolsWithReasoning;
        private Boolean supportsReasoningTraceRoundTrip;
        private ReasoningEffort lowestReasoningEffort;
        private Set<ReasoningEffort> acceptedReasoningEfforts;
        private ThinkingDialect thinkingDialect;
        private Boolean supportsReasoningSummary;

        private Builder() {
        }

        /**
         * @param value
         *            {@code false} when the model rejects {@code temperature} / {@code top_p} / the penalties;
         *            {@code null} to leave the flag undeclared
         * @return This builder
         */
        public Builder supportsSamplingParameters(Boolean value) {
            this.supportsSamplingParameters = value;
            return this;
        }

        /**
         * @param value
         *            {@code true} when the model takes a reasoning-effort parameter; {@code null} to leave the flag
         *            undeclared
         * @return This builder
         */
        public Builder supportsReasoningEffort(Boolean value) {
            this.supportsReasoningEffort = value;
            return this;
        }

        /**
         * @param value
         *            {@code false} when the endpoint rejects tools together with a non-{@code NONE} effort;
         *            {@code null} to leave the flag undeclared
         * @return This builder
         */
        public Builder supportsToolsWithReasoning(Boolean value) {
            this.supportsToolsWithReasoning = value;
            return this;
        }

        /**
         * @param value
         *            {@code true} when the model returns reasoning traces a client should replay on the next turn;
         *            {@code null} to leave the flag undeclared
         * @return This builder
         */
        public Builder supportsReasoningTraceRoundTrip(Boolean value) {
            this.supportsReasoningTraceRoundTrip = value;
            return this;
        }

        /**
         * @param value
         *            the least effort this model accepts as a value, with every rung above it accepted too;
         *            {@code null} to leave the flag undeclared. Mutually exclusive with
         *            {@link #acceptedReasoningEfforts(Set)} — see {@link #build()}
         * @return This builder
         */
        public Builder lowestReasoningEffort(ReasoningEffort value) {
            this.lowestReasoningEffort = value;
            return this;
        }

        /**
         * @param value
         *            every rung this model accepts, for a ladder that cannot be written as a floor because it has a
         *            gap in it; {@code null} to leave the flag undeclared. Mutually exclusive with
         *            {@link #lowestReasoningEffort(ReasoningEffort)} — see {@link #build()}
         * @return This builder
         */
        public Builder acceptedReasoningEfforts(Set<ReasoningEffort> value) {
            this.acceptedReasoningEfforts = value;
            return this;
        }

        /**
         * @param value
         *            which shape this model's thinking-request parameter takes; {@code null} to leave the flag
         *            undeclared. {@link ThinkingDialect#UNKNOWN} is <em>not</em> the same as {@code null} here —
         *            declaring it is a way of saying "do not act on any built-in row for this name"
         * @return This builder
         */
        public Builder thinkingDialect(ThinkingDialect value) {
            this.thinkingDialect = value;
            return this;
        }

        /**
         * @param value
         *            {@code false} when the endpoint serving this model rejects a request for a reasoning summary;
         *            {@code null} to leave the flag undeclared
         * @return This builder
         */
        public Builder supportsReasoningSummary(Boolean value) {
            this.supportsReasoningSummary = value;
            return this;
        }

        /**
         * @return whether any of the eight flags has been declared
         */
        boolean declaresAnything() {
            return supportsSamplingParameters != null || supportsReasoningEffort != null
                    || supportsToolsWithReasoning != null || supportsReasoningTraceRoundTrip != null
                    || lowestReasoningEffort != null || acceptedReasoningEfforts != null || thinkingDialect != null
                    || supportsReasoningSummary != null;
        }

        /**
         * @return A new {@link ModelCapabilityDeclaration}
         * @throws IllegalArgumentException
         *             if none of the eight flags was declared — such an entry would register
         *             {@link ModelCapabilities#unknown()}, which is indistinguishable from not writing the entry at
         *             all, while the operator who wrote it believes it does something; or if both ladder keys were
         *             declared, because they describe the same fact two ways and an entry stating both leaves no
         *             answer to which one the operator meant. This whole surface exists because a silent no-op
         *             produced an HTTP 400, so it does not ship one of its own.
         */
        public ModelCapabilityDeclaration build() {
            if (!declaresAnything()) {
                throw new IllegalArgumentException("A model capability declaration must state at least one of"
                        + " supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,"
                        + " supportsReasoningTraceRoundTrip, supportsReasoningSummary, lowestReasoningEffort,"
                        + " acceptedReasoningEfforts, thinkingDialect. An entry that states none of them registers"
                        + " the same fail-open capabilities the model already had, so it would bind and do nothing;"
                        + " if you meant to set a flag, check the spelling of its keys.");
            }
            // Refused here rather than resolved by a precedence rule. A Java caller writes a sequence, where "the
            // last statement wins" is an unambiguous answer and is what ModelCapabilities.Builder does; a
            // configuration file presents both keys at once with no order at all, so any winner this type picked
            // would be one the operator could not have predicted.
            if (lowestReasoningEffort != null && acceptedReasoningEfforts != null) {
                throw new IllegalArgumentException("A model capability declaration states both lowestReasoningEffort"
                        + " and acceptedReasoningEfforts, and they describe the same fact two ways. Keep"
                        + " acceptedReasoningEfforts if this model's ladder has a gap in it (e.g."
                        + " [none, low, medium, high]); keep lowestReasoningEffort if it starts at one rung and runs"
                        + " to the top.");
            }
            return new ModelCapabilityDeclaration(this);
        }
    }
}
