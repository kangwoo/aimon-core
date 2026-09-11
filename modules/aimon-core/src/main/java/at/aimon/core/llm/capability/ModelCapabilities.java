package at.aimon.core.llm.capability;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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
    // The same fact the removed DEFAULT_LOWEST_REASONING_EFFORT = MINIMAL stated, written as the set it always
    // meant: everything from MINIMAL up. Only NONE is withheld from a model nobody has described.
    private static final Set<ReasoningEffort> DEFAULT_ACCEPTED_REASONING_EFFORTS = unmodifiableLadder(
            EnumSet.range(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH));
    private static final ThinkingDialect DEFAULT_THINKING_DIALECT = ThinkingDialect.UNKNOWN;
    // True, unlike its nearest sibling DEFAULT_SUPPORTS_REASONING_EFFORT, and for the same rule rather than despite
    // it: a reasoning summary is only ever on a request because somebody asked for one, so withholding it would be
    // fail-CLOSED. See supportsReasoningSummary() and unknown()'s javadoc for why that stays safe.
    private static final boolean DEFAULT_SUPPORTS_REASONING_SUMMARY = true;

    private static final ModelCapabilities UNKNOWN = builder().build();

    private final boolean supportsSamplingParameters;
    private final boolean supportsReasoningEffort;
    private final boolean supportsToolsWithReasoning;
    private final boolean supportsReasoningTraceRoundTrip;
    private final Set<ReasoningEffort> acceptedReasoningEfforts;
    private final ThinkingDialect thinkingDialect;
    private final boolean supportsReasoningSummary;

    private ModelCapabilities(Builder builder) {
        this.supportsSamplingParameters = builder.supportsSamplingParameters;
        this.supportsReasoningEffort = builder.supportsReasoningEffort;
        this.supportsToolsWithReasoning = builder.supportsToolsWithReasoning;
        this.supportsReasoningTraceRoundTrip = builder.supportsReasoningTraceRoundTrip;
        this.acceptedReasoningEfforts = builder.acceptedReasoningEfforts;
        this.thinkingDialect = builder.thinkingDialect;
        this.supportsReasoningSummary = builder.supportsReasoningSummary;
    }

    /**
     * Copies a caller's rungs into an unmodifiable {@link EnumSet} view.
     *
     * <p>
     * The copy is what keeps the descriptor immutable when a caller mutates the set it passed in, and the
     * {@code EnumSet} is what makes iteration follow {@link ReasoningEffort}'s declaration order — which is the
     * ladder's order, and is what lets a warning that prints the set read as a ladder rather than as a bag.
     */
    private static Set<ReasoningEffort> unmodifiableLadder(Set<ReasoningEffort> rungs) {
        return Collections.unmodifiableSet(EnumSet.copyOf(rungs));
    }

    /**
     * The capabilities of a model nothing is known about: nothing the caller asked for is withheld, and nothing the
     * caller did not ask for is invented.
     *
     * <p>
     * Each flag falls out of that one rule. Sampling parameters a caller set are sent, because withholding them from a
     * model nobody has described would be fail-<em>closed</em>. No reasoning effort is sent, because that is a
     * parameter the framework would have to invent. Tools are not treated as conflicting with reasoning, because
     * clamping without evidence is a restriction nobody asked for. The accepted reasoning rungs are
     * {@code MINIMAL} through {@link ReasoningEffort#HIGH}, which withholds only {@link ReasoningEffort#NONE} — the
     * rung most likely to be absent, though measurement has stopped short of calling it universally absent: every
     * OpenAI o-series name probed on 2026-09-09 rejects it with a message naming the model, and {@code gpt-5-nano}
     * rejected it too, while {@code gpt-5.6-terra} <em>accepts</em> it and now states so in a row of its own. So this
     * default is a trade rather than a free choice, and it is made on the asymmetry of the two mistakes: withholding
     * a rung a model does have costs a reported omission and leaves the server's own default in force, while sending
     * a rung it does not have costs a 400 that fails the turn. A model that really does start at {@code NONE} is
     * describable — register a row with {@code lowestReasoningEffort(NONE)}, or with
     * {@code acceptedReasoningEfforts(...)} when the ladder has a gap in it rather than a floor. The thinking dialect
     * is {@link ThinkingDialect#UNKNOWN}, which is the only one of the seven that is not a permission at all: both
     * real dialects are a 400 on the model that speaks <em>only</em> the other, so the fail-open value here has to be
     * the <em>absence</em> of the fact rather than one of its values.
     * A reasoning summary is <em>allowed</em>, which is the same rule reaching the opposite boolean from
     * {@link #supportsReasoningEffort()}: a summary is only ever on a request because somebody set it, so withholding
     * it would be the fail-closed half. That default is also never consulted for a model nobody has described —
     * the parameter exists only on OpenAI's Responses endpoint, which a request reaches only when
     * {@link #supportsReasoningTraceRoundTrip()} is {@code true}, and that flag's own fail-open value is
     * {@code false}. So this one's job is to state what the rows that already exist mean, and they mean the summary
     * is accepted. Changing any of these silently changes the wire for every deployment
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
     * still reasons on a tool-less call: on a request that carries tools the client omits the effort and reports the
     * omission rather than sending {@link at.aimon.core.llm.ReasoningEffort#NONE}, which the measured models reject,
     * and on an endpoint where tools and reasoning coexist the flag is not read at all. Every reasoning row the
     * built-in table ships answers {@code true}, so the rule fires only where a caller or a configured declaration
     * states {@code false}.
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
     * The rungs this model accepts as a value for its reasoning-effort parameter.
     *
     * <p>
     * {@link #supportsReasoningEffort()} answers <em>whether the parameter exists</em>; this answers <em>which values
     * it takes</em>, and the two are independent facts that vendors get to disagree about per family. OpenAI's
     * {@code gpt-5.x} starts at {@code minimal} while its o-series starts at {@code low}.
     *
     * <p>
     * <strong>A set rather than a floor, and the difference is measured.</strong> This field named the lowest rung
     * until {@code gpt-5.6-terra} turned up with a ladder that has a hole in the middle of it — it rejects
     * {@code minimal} and accepts {@code none} — and no single boundary describes that. Writing one that produced the
     * right behaviour ({@code LOW}) would have meant writing down something untrue about the model, which is how the
     * next reader learns a wrong fact. A floor is still a fine way to <em>write</em> a ladder with no holes:
     * {@link Builder#lowestReasoningEffort(ReasoningEffort)} is shorthand for exactly that, and every built-in row
     * that has no hole still uses it.
     *
     * <p>
     * A requested effort that is not in this set is <strong>omitted and reported</strong>, never raised to the
     * nearest one: a clamp is a request the operator did not make, and it would arrive silently. Omission at least
     * leaves the server's own default in force, which is a state the divergence warning can describe honestly.
     *
     * <p>
     * Iteration follows {@link ReasoningEffort}'s declaration order, whose constants ascend — so a message that
     * prints this set prints a ladder.
     *
     * @return the accepted rungs (never null, never empty, unmodifiable; {@code MINIMAL} through {@code HIGH} when
     *         nothing is known)
     */
    public Set<ReasoningEffort> acceptedReasoningEfforts() {
        return acceptedReasoningEfforts;
    }

    /**
     * Which shape this model's thinking-request parameter takes.
     *
     * <p>
     * The axis this field describes is <em>mutually exclusive for most models</em> — each real dialect is an HTTP 400
     * on a model that speaks only the other — so unlike the five flags above it has no safe fail-open <em>value</em>.
     * What makes it safe is that two of the four constants are not dialects at all:
     * {@link ThinkingDialect#UNKNOWN} means <em>this table cannot answer</em>, and a client reading it leaves
     * whatever the caller configured exactly as it was, which is the behaviour every model had before this field
     * existed. {@link ThinkingDialect#EITHER} is the measured exception to the exclusivity — a model that accepts
     * both shapes — and it exists so that "measured to take either" and "not described" stop being the same value.
     *
     * <p>
     * Vendor-shaped, like {@link #supportsToolsWithReasoning()} and for the same reason: one table is read by every
     * client through one configuration translator, so a second registry for one vendor would double what a gateway
     * deployment has to declare and give the two a way to disagree.
     *
     * @return the model's thinking dialect (never null; {@link ThinkingDialect#UNKNOWN} when nothing is known)
     */
    public ThinkingDialect thinkingDialect() {
        return thinkingDialect;
    }

    /**
     * Whether the model accepts a request for a <em>reasoning summary</em>.
     *
     * <p>
     * <strong>Read on OpenAI's Responses path and nowhere else</strong> — it gates {@code reasoning.summary} on
     * {@code /v1/responses}, and no other client consults it, so declaring it {@code false} for a Claude model
     * does nothing today. The name is neutral rather than OpenAI-shaped because Anthropic expresses the same axis
     * as {@code thinking.display: summarized}, so a second consumer would not have to move the name — but nothing
     * measured asks for one yet, and until it does this flag describes one endpoint's parameter.
     *
     * <p>
     * The flag exists for the gateway case rather than for first-party OpenAI. A model served through an
     * OpenAI-compatible gateway that a deployment has declared
     * {@link #supportsReasoningTraceRoundTrip()} {@code true} for is one this framework will send to the Responses
     * endpoint; if that gateway implements {@code reasoning.effort} but rejects {@code reasoning.summary}, the
     * request is a 400 while the sibling parameter beside it is guarded twice. This is the per-model lever for that,
     * because the ask itself is per <em>client</em>.
     *
     * <p>
     * Deliberately <strong>not</strong> folded into {@link #supportsReasoningTraceRoundTrip()}. That flag means
     * something measured — this model replays reasoning traces — and a gateway can satisfy it and reject a summary
     * independently, so overloading it would make one declared row assert two facts an operator cannot separate.
     *
     * @return {@code true} when a client may ask for a reasoning summary; {@code false} when the parameter is
     *         omitted and the omission reported
     */
    public boolean supportsReasoningSummary() {
        return supportsReasoningSummary;
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
                && acceptedReasoningEfforts.equals(that.acceptedReasoningEfforts)
                && thinkingDialect == that.thinkingDialect && supportsReasoningSummary == that.supportsReasoningSummary;
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,
                supportsReasoningTraceRoundTrip, acceptedReasoningEfforts, thinkingDialect, supportsReasoningSummary);
    }

    @Override
    public String toString() {
        return "ModelCapabilities{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + ", supportsReasoningTraceRoundTrip=" + supportsReasoningTraceRoundTrip
                + ", acceptedReasoningEfforts=" + acceptedReasoningEfforts + ", thinkingDialect=" + thinkingDialect
                + ", supportsReasoningSummary=" + supportsReasoningSummary + '}';
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
        private Set<ReasoningEffort> acceptedReasoningEfforts = DEFAULT_ACCEPTED_REASONING_EFFORTS;
        private ThinkingDialect thinkingDialect = DEFAULT_THINKING_DIALECT;
        private boolean supportsReasoningSummary = DEFAULT_SUPPORTS_REASONING_SUMMARY;

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
         * Sets the rungs this model accepts as a value.
         *
         * <p>
         * The general form. {@link #lowestReasoningEffort(ReasoningEffort)} is the shorthand for the common case, and
         * both write this one field — so the last call wins, as it does for every other setter here.
         *
         * @param acceptedReasoningEfforts
         *            the rungs this model accepts (must not be null, must not be empty, must contain no null element)
         * @return This builder
         * @throws NullPointerException
         *             if the set or any element is null
         * @throws IllegalArgumentException
         *             if the set is empty. "This model takes the reasoning-effort parameter but accepts no value for
         *             it" is not a state a request surface can be in; the thing being described there is
         *             {@link #supportsReasoningEffort(boolean)} {@code false}
         */
        public Builder acceptedReasoningEfforts(Set<ReasoningEffort> acceptedReasoningEfforts) {
            Objects.requireNonNull(acceptedReasoningEfforts, "acceptedReasoningEfforts cannot be null");
            if (acceptedReasoningEfforts.isEmpty()) {
                throw new IllegalArgumentException("acceptedReasoningEfforts cannot be empty — a model that accepts no"
                        + " rung at all is one that takes no reasoning-effort parameter, which is"
                        + " supportsReasoningEffort(false) rather than an empty ladder.");
            }
            // EnumSet.copyOf would throw a NullPointerException of its own here, but not one that names the field.
            // This is the Java-caller half of a rule the two configuration surfaces enforce with an index on it: a
            // null rung is a mistake rather than a rung to skip, because skipping it narrows which requests the
            // client may send without saying so. A caller reaching here passed something an EnumSet cannot be --
            // new HashSet<>(Arrays.asList(NONE, null)) -- while an operator writes `[none, ~, high]`, and only the
            // surface knows which position that was.
            for (ReasoningEffort rung : acceptedReasoningEfforts) {
                Objects.requireNonNull(rung, "acceptedReasoningEfforts cannot contain a null rung");
            }
            this.acceptedReasoningEfforts = unmodifiableLadder(acceptedReasoningEfforts);
            return this;
        }

        /**
         * Sets the rungs this model accepts, as a floor: {@code lowest} and everything above it.
         *
         * <p>
         * Shorthand for {@link #acceptedReasoningEfforts(Set)} with {@code EnumSet.range(lowest, HIGH)}, kept because
         * it is what most rows want to say — a ladder that starts somewhere and runs to the top has no hole to
         * describe, and spelling the set out would be longer and no truer. A row whose ladder <em>does</em> have a
         * hole cannot use this form; that is the whole reason the field is a set.
         *
         * @param lowest
         *            the least effort this model accepts as a value (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if lowest is null
         */
        public Builder lowestReasoningEffort(ReasoningEffort lowest) {
            Objects.requireNonNull(lowest, "lowestReasoningEffort cannot be null");
            return acceptedReasoningEfforts(EnumSet.range(lowest, ReasoningEffort.HIGH));
        }

        /**
         * Sets which shape this model's thinking-request parameter takes.
         *
         * @param thinkingDialect
         *            the model's thinking dialect, or {@link ThinkingDialect#UNKNOWN} to say the table cannot answer
         *            (must not be null)
         * @return This builder
         * @throws NullPointerException
         *             if thinkingDialect is null
         */
        public Builder thinkingDialect(ThinkingDialect thinkingDialect) {
            this.thinkingDialect = Objects.requireNonNull(thinkingDialect, "thinkingDialect cannot be null");
            return this;
        }

        /**
         * @param supportsReasoningSummary
         *            {@code false} when the endpoint serving this model rejects a request for a reasoning summary
         * @return This builder
         */
        public Builder supportsReasoningSummary(boolean supportsReasoningSummary) {
            this.supportsReasoningSummary = supportsReasoningSummary;
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
