package at.aimon.core.llm.capability;

import java.util.Objects;

import at.aimon.core.llm.ReasoningEffort;

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
 * <strong>Fail open.</strong> {@link #unknown()} is not "everything is allowed". It is the two-sided rule
 * <em>nothing the caller asked for is withheld, and nothing the caller did not ask for is invented</em>, which comes
 * out as a different boolean per flag: a value somebody set is sent, and a parameter nobody has ever sent is not
 * conjured up for a model nobody has described. The builder is seeded with the same values, so a partially specified
 * entry stays permissive by omission and fail-open is a property of the type rather than a rule each registry author
 * has to re-derive.
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
    private static final boolean DEFAULT_SUPPORTS_REASONING_TRACE_ROUND_TRIP = false;
    private static final ReasoningEffort DEFAULT_LOWEST_REASONING_EFFORT = ReasoningEffort.MINIMAL;

    private static final ModelCapabilities UNKNOWN = builder().build();

    private final boolean supportsSamplingParameters;
    private final boolean supportsReasoningEffort;
    private final boolean supportsToolsWithReasoning;
    private final boolean supportsReasoningTraceRoundTrip;
    private final ReasoningEffort lowestReasoningEffort;

    private ModelCapabilities(Builder builder) {
        this.supportsSamplingParameters = builder.supportsSamplingParameters;
        this.supportsReasoningEffort = builder.supportsReasoningEffort;
        this.supportsToolsWithReasoning = builder.supportsToolsWithReasoning;
        this.supportsReasoningTraceRoundTrip = builder.supportsReasoningTraceRoundTrip;
        this.lowestReasoningEffort = builder.lowestReasoningEffort;
    }

    /**
     * The capabilities of a model nothing is known about: nothing the caller asked for is withheld, and nothing the
     * caller did not ask for is invented.
     *
     * <p>
     * Each flag falls out of that one rule. Sampling parameters a caller set are sent, because withholding them from a
     * model nobody has described would be fail-<em>closed</em>. No reasoning effort is sent, because that is a
     * parameter the framework would have to invent. Tools are not treated as conflicting with reasoning, because
     * clamping without evidence is a restriction nobody asked for. The lowest reasoning rung is
     * {@link ReasoningEffort#MINIMAL}, which withholds only {@link ReasoningEffort#NONE} — the rung most likely to be
     * absent, though measurement has stopped short of calling it universally absent: every OpenAI o-series name
     * probed on 2026-09-09 rejects it with a message naming the model, and {@code gpt-5-nano} rejected it too, but
     * {@code gpt-5.6-terra} <em>accepts</em> it. So this default is a trade rather than a free choice, and it is made
     * on the asymmetry of the two mistakes: withholding a rung a model does have costs a reported omission and
     * leaves the server's own default in force, while sending a rung it does not have costs a 400 that fails the
     * turn. A model that really does start at {@code NONE} is describable — register a row with
     * {@code lowestReasoningEffort(NONE)}. Changing any of these silently changes the wire for every deployment
     * running a model no registry describes, which is why a test asserts each one individually.
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
     * built-in table describes OpenAI's <em>Chat Completions</em> endpoint, and that is now stated rather than
     * implied: {@code aimon-llm-openai} reads this flag only on that path. A model whose answer is {@code false}
     * still reasons on a tool-less call — a provider clamps to {@link at.aimon.core.llm.ReasoningEffort#NONE} only
     * when tools are actually present — and on an endpoint where tools and reasoning coexist the flag is not read at
     * all. {@code gpt-5} is exactly that case: it answers {@code false} here, and in the shipped default
     * configuration it no longer reaches this path because {@link #supportsReasoningTraceRoundTrip()} routes it to a
     * surface with no such conflict. The flag stays reachable, and stays correct, the moment that route is turned off.
     *
     * @return {@code true} when tools and reasoning may be combined
     */
    public boolean supportsToolsWithReasoning() {
        return supportsToolsWithReasoning;
    }

    /**
     * Whether the model returns reasoning traces that a client may send back on the next turn, and whether doing so is
     * what keeps the reasoning alive across a tool call.
     *
     * <p>
     * This is the provider-neutral fact, not an endpoint: OpenAI's answer to it is "use {@code /v1/responses} and
     * replay the reasoning items", Anthropic's is "send the thinking blocks back", and neither vocabulary belongs in
     * this type. A client that reads {@code true} is expected to capture
     * {@link at.aimon.core.llm.ReasoningTrace}s from the response and replay its own on the next request; a client
     * that reads {@code false} behaves exactly as it did before this flag existed.
     *
     * @return {@code true} when reasoning traces round-trip for this model
     */
    public boolean supportsReasoningTraceRoundTrip() {
        return supportsReasoningTraceRoundTrip;
    }

    /**
     * The lowest rung on this model's reasoning ladder — the least effort it will accept as a value.
     *
     * <p>
     * {@link #supportsReasoningEffort()} answers <em>whether the parameter exists</em>; this answers <em>which values
     * it takes</em>, and the two are independent facts that vendors get to disagree about per family. OpenAI's
     * {@code gpt-5.x} starts at {@code minimal} while its o-series starts at {@code low}, so one table of neutral
     * constants cannot be translated for both without knowing where each ladder starts. Only the floor is modelled
     * here: a family's ceiling turned out to differ between OpenAI's two endpoints for the same model, and naming it
     * was always beside this field's meaning.
     *
     * <p>
     * A requested effort below this rung is <strong>omitted and reported</strong>, never raised to meet it: a clamp
     * upward is a request the operator did not make, and it would arrive silently. Omission at least leaves the
     * server's own default in force, which is a state the divergence warning can describe honestly.
     *
     * <p>
     * The rungs compare by declaration order — see {@link ReasoningEffort}, whose constants ascend.
     *
     * @return the lowest acceptable effort (never null; {@link ReasoningEffort#MINIMAL} when nothing is known)
     */
    public ReasoningEffort lowestReasoningEffort() {
        return lowestReasoningEffort;
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
                && supportsToolsWithReasoning == that.supportsToolsWithReasoning
                && supportsReasoningTraceRoundTrip == that.supportsReasoningTraceRoundTrip
                && lowestReasoningEffort == that.lowestReasoningEffort;
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,
                supportsReasoningTraceRoundTrip, lowestReasoningEffort);
    }

    @Override
    public String toString() {
        return "ModelCapabilities{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + ", supportsReasoningTraceRoundTrip=" + supportsReasoningTraceRoundTrip
                + ", lowestReasoningEffort=" + lowestReasoningEffort + '}';
    }

    /**
     * Builder for {@link ModelCapabilities}.
     *
     * <p>
     * Seeded with {@link ModelCapabilities#unknown()}'s values, so an entry that specifies only the flag it cares about
     * leaves the rest fail-open rather than at {@code false}.
     */
    public static final class Builder {
        private boolean supportsSamplingParameters = DEFAULT_SUPPORTS_SAMPLING_PARAMETERS;
        private boolean supportsReasoningEffort = DEFAULT_SUPPORTS_REASONING_EFFORT;
        private boolean supportsToolsWithReasoning = DEFAULT_SUPPORTS_TOOLS_WITH_REASONING;
        private boolean supportsReasoningTraceRoundTrip = DEFAULT_SUPPORTS_REASONING_TRACE_ROUND_TRIP;
        private ReasoningEffort lowestReasoningEffort = DEFAULT_LOWEST_REASONING_EFFORT;

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
         * @param supportsReasoningTraceRoundTrip
         *            {@code true} when the model returns reasoning traces a client should replay on the next turn
         * @return This builder
         */
        public Builder supportsReasoningTraceRoundTrip(boolean supportsReasoningTraceRoundTrip) {
            this.supportsReasoningTraceRoundTrip = supportsReasoningTraceRoundTrip;
            return this;
        }

        /**
         * Sets the lowest rung on this model's reasoning ladder.
         *
         * @param lowestReasoningEffort
         *            the least effort this model accepts as a value (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if lowestReasoningEffort is null
         */
        public Builder lowestReasoningEffort(ReasoningEffort lowestReasoningEffort) {
            this.lowestReasoningEffort = Objects.requireNonNull(lowestReasoningEffort,
                    "lowestReasoningEffort cannot be null");
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
