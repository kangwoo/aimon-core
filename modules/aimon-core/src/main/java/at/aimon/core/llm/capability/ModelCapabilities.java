package at.aimon.core.llm.capability;

import java.util.Objects;

/**
 * What a client may <em>do</em> when it builds a request for one model.
 *
 * <p>
 * Every flag describes the request surface, not the model's personality: whether a parameter may be set at all, not
 * whether setting it is a good idea. Providers consult this before populating a parameter, because several vendors
 * reject a parameter by its <em>presence</em> rather than by its value — sending {@code "temperature": null} is as
 * fatal as sending {@code "temperature": 0.0}.
 *
 * <p>
 * <strong>Fail open.</strong> {@link #unknown()} is not "everything is allowed" — it is <em>today's behaviour</em>,
 * which is a different boolean per flag: the framework has always sent sampling parameters and has never sent a
 * reasoning effort. A model nobody has described therefore produces exactly the request it produced before this type
 * existed. The builder is seeded with the same values, so a partially specified entry stays permissive by omission and
 * fail-open is a property of the type rather than a rule each registry author has to re-derive.
 *
 * <p>
 * Thread-safe and immutable.
 *
 * @see ModelCapabilityRegistry
 */
public final class ModelCapabilities {

    // The fail-open values, as literals. The Builder seeds itself from these rather than from unknown(), because
    // unknown() is itself built from a Builder -- reading it during the builder's own initialisation would hand back
    // a half-built object. ModelCapabilitiesTest asserts the builder's defaults equal unknown(), which is what keeps
    // the two definitions in step.
    private static final boolean DEFAULT_SUPPORTS_SAMPLING_PARAMETERS = true;
    private static final boolean DEFAULT_SUPPORTS_REASONING_EFFORT = false;
    private static final boolean DEFAULT_SUPPORTS_TOOLS_WITH_REASONING = true;

    private static final ModelCapabilities UNKNOWN = builder().build();

    private final boolean supportsSamplingParameters;
    private final boolean supportsReasoningEffort;
    private final boolean supportsToolsWithReasoning;

    private ModelCapabilities(Builder builder) {
        this.supportsSamplingParameters = builder.supportsSamplingParameters;
        this.supportsReasoningEffort = builder.supportsReasoningEffort;
        this.supportsToolsWithReasoning = builder.supportsToolsWithReasoning;
    }

    /**
     * The capabilities of a model nothing is known about: exactly the request shape the framework produced before this
     * type existed.
     *
     * <p>
     * Sampling parameters are sent, no reasoning effort is sent, and tools are not treated as conflicting with
     * reasoning. Changing any of these silently changes the wire for every deployment running a model no registry
     * describes, which is why a test asserts each one individually.
     *
     * @return the fail-open descriptor (never null)
     */
    public static ModelCapabilities unknown() {
        return UNKNOWN;
    }

    /**
     * @return a new builder seeded with {@link #unknown()}'s values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the model accepts the sampling parameters — {@code temperature}, {@code top_p}, {@code presence_penalty},
     * {@code frequency_penalty}.
     *
     * <p>
     * One flag rather than four because no model in use today accepts a strict subset of them. Splitting it later is a
     * builder method plus a getter, i.e. additive.
     *
     * @return {@code true} when a client may set them; {@code false} when it must not set them <em>at all</em>, null
     *         included
     */
    public boolean supportsSamplingParameters() {
        return supportsSamplingParameters;
    }

    /**
     * Whether the model takes a reasoning-effort parameter at all.
     *
     * <p>
     * {@code false} means the parameter is not part of this model's request surface, so a configured
     * {@link at.aimon.core.llm.ReasoningEffort} is dropped rather than translated.
     *
     * @return {@code true} when a client may send a reasoning effort
     */
    public boolean supportsReasoningEffort() {
        return supportsReasoningEffort;
    }

    /**
     * Whether the model accepts tool definitions together with a non-{@code NONE} reasoning effort on the same request.
     *
     * <p>
     * This one is endpoint-flavoured: it means "on the request surface the resolving client uses". The framework's
     * built-in table describes OpenAI's Chat Completions endpoint because that is the only surface
     * {@code aimon-llm-openai} has. A model whose answer is {@code false} still reasons on a tool-less call — a
     * provider clamps to {@link at.aimon.core.llm.ReasoningEffort#NONE} only when tools are actually present.
     *
     * @return {@code true} when tools and reasoning may be combined
     */
    public boolean supportsToolsWithReasoning() {
        return supportsToolsWithReasoning;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ModelCapabilities that = (ModelCapabilities) o;
        return supportsSamplingParameters == that.supportsSamplingParameters
                && supportsReasoningEffort == that.supportsReasoningEffort
                && supportsToolsWithReasoning == that.supportsToolsWithReasoning;
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning);
    }

    @Override
    public String toString() {
        return "ModelCapabilities{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + '}';
    }

    /**
     * Builder for {@link ModelCapabilities}.
     *
     * <p>
     * Seeded with {@link ModelCapabilities#unknown()}'s values, so an entry that specifies only the flag it cares about
     * leaves the rest at today's behaviour rather than at {@code false}.
     */
    public static final class Builder {
        private boolean supportsSamplingParameters = DEFAULT_SUPPORTS_SAMPLING_PARAMETERS;
        private boolean supportsReasoningEffort = DEFAULT_SUPPORTS_REASONING_EFFORT;
        private boolean supportsToolsWithReasoning = DEFAULT_SUPPORTS_TOOLS_WITH_REASONING;

        private Builder() {
        }

        /**
         * @param supportsSamplingParameters
         *            {@code false} when the model rejects {@code temperature} / {@code top_p} / the penalties
         * @return This builder
         */
        public Builder supportsSamplingParameters(boolean supportsSamplingParameters) {
            this.supportsSamplingParameters = supportsSamplingParameters;
            return this;
        }

        /**
         * @param supportsReasoningEffort
         *            {@code true} when the model takes a reasoning-effort parameter
         * @return This builder
         */
        public Builder supportsReasoningEffort(boolean supportsReasoningEffort) {
            this.supportsReasoningEffort = supportsReasoningEffort;
            return this;
        }

        /**
         * @param supportsToolsWithReasoning
         *            {@code false} when the endpoint rejects tools together with a non-{@code NONE} effort
         * @return This builder
         */
        public Builder supportsToolsWithReasoning(boolean supportsToolsWithReasoning) {
            this.supportsToolsWithReasoning = supportsToolsWithReasoning;
            return this;
        }

        /**
         * @return A new {@link ModelCapabilities} instance
         */
        public ModelCapabilities build() {
            return new ModelCapabilities(this);
        }
    }
}
