package at.aimon.core.llm.capability;

import java.util.Objects;
import java.util.Optional;

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
    private final ModelCapabilities capabilities;

    private ModelCapabilityDeclaration(Builder builder) {
        this.supportsSamplingParameters = builder.supportsSamplingParameters;
        this.supportsReasoningEffort = builder.supportsReasoningEffort;
        this.supportsToolsWithReasoning = builder.supportsToolsWithReasoning;
        this.supportsReasoningTraceRoundTrip = builder.supportsReasoningTraceRoundTrip;
        this.lowestReasoningEffort = builder.lowestReasoningEffort;
        this.capabilities = resolve(builder);
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
        if (builder.lowestReasoningEffort != null) {
            resolved.lowestReasoningEffort(builder.lowestReasoningEffort);
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
     * @return whether the declaration states {@link ModelCapabilities#lowestReasoningEffort()}, and what it says
     */
    public Optional<ReasoningEffort> lowestReasoningEffort() {
        return Optional.ofNullable(lowestReasoningEffort);
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
                && lowestReasoningEffort == that.lowestReasoningEffort;
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,
                supportsReasoningTraceRoundTrip, lowestReasoningEffort);
    }

    @Override
    public String toString() {
        return "ModelCapabilityDeclaration{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + ", supportsReasoningTraceRoundTrip=" + supportsReasoningTraceRoundTrip
                + ", lowestReasoningEffort=" + lowestReasoningEffort + '}';
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
         *            the least effort this model accepts as a value; {@code null} to leave the flag undeclared
         * @return This builder
         */
        public Builder lowestReasoningEffort(ReasoningEffort value) {
            this.lowestReasoningEffort = value;
            return this;
        }

        /**
         * @return whether any of the five flags has been declared
         */
        boolean declaresAnything() {
            return supportsSamplingParameters != null || supportsReasoningEffort != null
                    || supportsToolsWithReasoning != null || supportsReasoningTraceRoundTrip != null
                    || lowestReasoningEffort != null;
        }

        /**
         * @return A new {@link ModelCapabilityDeclaration}
         * @throws IllegalArgumentException
         *             if none of the five flags was declared. Such an entry would register
         *             {@link ModelCapabilities#unknown()}, which is indistinguishable from not writing the entry at
         *             all — while the operator who wrote it believes it does something. This whole surface exists
         *             because a silent no-op produced an HTTP 400, so it does not ship one of its own.
         */
        public ModelCapabilityDeclaration build() {
            if (!declaresAnything()) {
                throw new IllegalArgumentException("A model capability declaration must state at least one of"
                        + " supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,"
                        + " supportsReasoningTraceRoundTrip, lowestReasoningEffort. An entry that states none of them"
                        + " registers the same fail-open capabilities the model already had, so it would bind and do"
                        + " nothing; if you meant to set a flag, check the spelling of its keys.");
            }
            return new ModelCapabilityDeclaration(this);
        }
    }
}
