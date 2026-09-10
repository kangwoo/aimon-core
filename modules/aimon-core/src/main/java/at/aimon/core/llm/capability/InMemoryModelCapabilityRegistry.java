package at.aimon.core.llm.capability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.llm.ReasoningEffort;

/**
 * Default {@link ModelCapabilityRegistry} backed by an in-memory map of exact entries plus prefix patterns, both
 * matched ignoring case.
 *
 * <p>
 * Built via {@link Builder}. Look-ups first consult the exact entries, then the registered <em>prefix patterns</em> in
 * registration order; if nothing matches the model is reported as unknown ({@link Optional#empty()}) and
 * {@link ModelCapabilityRegistry#resolve} turns that into {@link ModelCapabilities#unknown()}. There is deliberately no
 * fallback default entry: a table cannot describe a model it has never heard of, and guessing would be the one thing
 * that turns fail-open into a silent wire change.
 *
 * <p>
 * Mirrors the structure of {@link at.aimon.core.llm.InMemoryModelContextWindowRegistry} and
 * {@link at.aimon.core.llm.cost.InMemoryModelPriceTable} so the three registries read the same way.
 *
 * <p>
 * <strong>Extending the built-in table.</strong> {@link #withDefaults()} describes models by their real names; a
 * gateway or Azure deployment that renames one is unknown to it and keeps hitting whatever the rename was meant to
 * avoid. Register the deployment's own name to close that:
 *
 * <pre>
 * {@code
 * InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
 *         .register("prod-assistant", ModelCapabilities.builder().supportsSamplingParameters(false)
 *                 .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build())
 *         .build();
 *
 * OpenAIConfig config = OpenAIConfig.builder().apiKey(key).model("prod-assistant")
 *         .modelCapabilityRegistry(registry).build();
 * }
 * </pre>
 *
 * <p>
 * That is the programmatic path. The same extension is reachable from configuration —
 * {@code llm.modelCapabilities.<model>} in the CLI's yaml, {@code aimon.llm.model-capabilities.<model>} in the Spring
 * Boot starter — and both surfaces arrive here through
 * {@link #withDefaultsExtendedBy(Map)}, which is where the rules governing a declared entry live.
 *
 * <p>
 * <strong>The table describes two vendors</strong>, and one instance of it is read by both clients. The
 * {@code claude-*} rows state two flags — the names they match refuse the sampling parameters and speak the
 * {@link ThinkingDialect#ADAPTIVE} thinking dialect — and the {@code gpt-*} / {@code o*} rows cannot match a
 * {@code claude-*} name or the other way round, so the two blocks do not interfere. What they do share is the
 * look-up: a {@code claude-*} name reaching {@code OpenAILlmClient} through an OpenAI-compatible gateway resolves to
 * the Anthropic rows and has its sampling suppressed too. That is the right answer arriving from an unexpected
 * direction, and it is stated here because it is invisible from either client's source. The dialect travels the same
 * way and costs nothing there: no OpenAI path reads it.
 *
 * <p>
 * <strong>The o-series is in the table</strong>, measured 2026-09-09: three prefix rows ({@code o1} / {@code o3} /
 * {@code o4}) <em>and</em> eight exact rows. Both shapes are needed because reasoning-item replay is known per
 * <em>name</em>, not per family — {@code o1-pro} and {@code o4-mini-deep-research} share a prefix with a measured
 * model and were never called, so the prefix rows keep
 * {@link ModelCapabilities#supportsReasoningTraceRoundTrip()} at {@code false} and only the exact rows carry the
 * {@code true}.
 *
 * <p>
 * That costs callers one thing, and the look-up order stated at the top of this javadoc is why:
 * {@code builderWithDefaults().registerPrefix("o1", ...)} still reaches {@code o1-pro} and every future
 * {@code o1*} name but <strong>no longer reaches {@code o1} or {@code o1-2024-12-17}</strong>, because the built-in
 * exact rows shadow it. Override a measured name with {@code register(...)}, which displaces the built-in exact row
 * — and so does a configured declaration for that name, which reaches the same call through
 * {@link #withDefaultsExtendedBy(Map)}, so an operator who has measured one of these names on their own deployment
 * can still state it.
 * It is also why there is no exact row for any {@code gpt-5*} name: one would disable the
 * {@code registerPrefix("gpt-5", ...)} override {@link #builderWithDefaults()} documents, for the very name that
 * override is demonstrated with.
 *
 * <p>
 * Whichever shape an entry for these models takes, {@link ModelCapabilities#supportsToolsWithReasoning()}
 * <strong>must stay {@code true}</strong>: they reject the effort value {@code none}, so a {@code false} there makes
 * the client send a value the API refuses.
 *
 * <pre>
 * {@code
 * InMemoryModelCapabilityRegistry.builderWithDefaults()
 *         .register("o3", ModelCapabilities.builder().supportsSamplingParameters(false)
 *                 .supportsReasoningEffort(true)
 *                 .supportsToolsWithReasoning(true) // MUST stay true: the o-series rejects effort "none"
 *                 .supportsReasoningTraceRoundTrip(false) // sends this one name back to Chat Completions
 *                 .lowestReasoningEffort(ReasoningEffort.LOW).build())
 *         .build();
 * }
 * </pre>
 */
public final class InMemoryModelCapabilityRegistry implements ModelCapabilityRegistry {

    // The o-series request surface, in the two variants that differ by exactly one bit. Both are stated once rather
    // than eleven times so that "the exact rows are the prefix rows plus a measured round trip" is a fact of the
    // source rather than of eleven copies staying in step.
    private static final ModelCapabilities O_SERIES_REPLAY_UNMEASURED = oSeries(false);
    private static final ModelCapabilities O_SERIES_REPLAY_MEASURED = oSeries(true);

    /**
     * The two facts every {@code claude-*} row in this table states, together once so the six prefixes that carry them
     * cannot drift apart. Every other flag stays fail-open — see the comment beside the registrations for why that is
     * the whole row.
     *
     * <p>
     * The pairing is not a coincidence to be tidied away later: the models measured to refuse the sampling parameters
     * are the current generation, and the current generation is the adaptive-only one. A model that accepts sampling
     * and speaks the budgeted dialect is describable — it just has no row here, because nothing in the built-in table
     * needs one.
     */
    private static final ModelCapabilities ADAPTIVE_REFUSING_SAMPLING = ModelCapabilities.builder()
            .supportsSamplingParameters(false).thinkingDialect(ThinkingDialect.ADAPTIVE).build();

    /**
     * The o-series names whose reasoning-item replay was measured on 2026-09-09, alias and served snapshot alike. The
     * dated names were never <em>sent</em> — they were returned, as the {@code model} of the response whose replayed
     * item was accepted — so registering them is the honest reading of what answered, with one assumption stated in
     * section 13 of {@code docs/design/llm/openai-model-capabilities.md}: that requesting a snapshot reaches it.
     */
    private static final List<String> MEASURED_O_SERIES_NAMES = List.of("o1", "o1-2024-12-17", "o3", "o3-2025-04-16",
            "o3-mini", "o3-mini-2025-01-31", "o4-mini", "o4-mini-2025-04-16");

    private final Map<String, ModelCapabilities> exactEntries;
    private final Map<String, ModelCapabilities> prefixEntries;

    private InMemoryModelCapabilityRegistry(Builder builder) {
        this.exactEntries = Map.copyOf(builder.exactEntries);
        this.prefixEntries = new LinkedHashMap<>(builder.prefixEntries);
    }

    /**
     * @return a new empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder pre-populated with the framework-default entries, so a caller can add or override a single
     * model without restating the table.
     *
     * <p>
     * A later {@code registerPrefix} for a prefix already present replaces its entry <em>in place</em> — the original
     * registration position is kept, because {@link LinkedHashMap} does not reorder on re-put. That is what makes
     * overriding {@code gpt-5} safe: it cannot accidentally jump ahead of the more specific {@code gpt-5-chat}.
     *
     * @return a builder carrying the {@link #withDefaults()} entries
     */
    public static Builder builderWithDefaults() {
        final Builder builder = builder()
                // Order matters: gpt-5-chat is the non-reasoning variant of the family and must be matched before the
                // family prefix, or it would inherit the family's suppression. Same reason InMemoryModelPriceTable
                // registers gpt-4o-mini before gpt-4o.
                //
                // gpt-5-chat-latest itself is deprecated (404 as of 2026-09-09), but the prefix stays: it is a prefix,
                // not that one name, and a deployment may still route other gpt-5-chat-* names through it.
                .registerPrefix("gpt-5-chat",
                        ModelCapabilities.builder().supportsSamplingParameters(true).supportsReasoningEffort(false)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(false).build())
                // gpt-5.x on /v1/chat/completions accepts temperature only at its default of 1; every other value is
                // rejected ("does not support 0.0 with this model. Only the default (1) value is supported"). Since
                // omitting the parameter yields that same default, suppression loses nothing on the wire and spares
                // every non-default value a 400. It also returns reasoning items a client must replay for the
                // reasoning to survive a tool call -- which is what supportsReasoningTraceRoundTrip says.
                //
                // The default lowestReasoningEffort (MINIMAL) is right for this family and is left unset: it answers
                // "Supported values are: 'minimal', 'low', 'medium', and 'high'", so only NONE is off its ladder.
                //
                // supportsToolsWithReasoning is TRUE, and that reverses round 1. Measured 2026-09-09: gpt-5-nano with
                // tools and no reasoning_effort returns 200, so there is no conflict to work around; and the remedy
                // false used to trigger -- sending effort "none" -- is itself rejected, since "none" is not among the
                // accepted values ('minimal', 'low', 'medium', 'high'). See section 11 of
                // docs/design/llm/openai-model-capabilities.md for the probe table.
                //
                // Round 8 measured one member this floor gets wrong: gpt-5.6-terra resolves here and REJECTS
                // 'minimal' (its ladder is none/low/medium/high/xhigh/max), so a programmatically configured MINIMAL
                // on that name is a 400. Known and deliberately unfixed -- no per-name row fixes it without
                // shadowing this prefix for the one name the override recipe above is demonstrated with. See
                // section 13 of docs/design/llm/openai-model-capabilities.md and
                // docs/backlog/openai-model-capabilities-open-items.md item L-1.
                .registerPrefix("gpt-5",
                        ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(true).build())
                // The o-series, measured 2026-09-09 and no longer inferred. Round 1 cut these rows because the belief
                // that they reject sampling was unverified and a wrong row is a *silent* change; the probes closed
                // that. o3-mini and o4-mini reject temperature 0.0 and accept 1.0, accept tools with no effort, and
                // reject effort "none" -- so supportsToolsWithReasoning MUST stay true here, exactly as this class's
                // own javadoc example has warned all along.
                //
                // These three keep supportsReasoningTraceRoundTrip=false and they are the rows every UNMEASURED name
                // under them lands on -- o1-pro, o4-mini-deep-research and any future o1*/o3*/o4* name. Round 8's
                // cost rules forbade calling the first two, so nothing is known about them, and a prefix flip would
                // assert a wire change on the strength of a sibling that merely shares a name prefix. The measured
                // names are registered exactly, below.
                //
                // lowestReasoningEffort is LOW rather than the default MINIMAL, and that is the second half of the
                // same probe. Round 8 replaced the evidence for it: round 6 quoted a Chat Completions reply without
                // saying so, and on /v1/responses -- the endpoint the measured names now reach -- the same model
                // answers "Unsupported value: 'minimal' is not supported with the 'o4-mini' model. Supported values
                // are: 'low', 'medium', and 'high'." The floor is 'low' on both surfaces; only the ceiling differed,
                // and this field is about the floor. Without the row the neutral MINIMAL would translate to the wire
                // value 'minimal' and 400.
                .registerPrefix("o1", O_SERIES_REPLAY_UNMEASURED).registerPrefix("o3", O_SERIES_REPLAY_UNMEASURED)
                .registerPrefix("o4", O_SERIES_REPLAY_UNMEASURED)
                // Anthropic, measured 2026-09-09 against the account's own /v1/models listing. On these six the
                // server refuses temperature at any non-default value, and top_p / top_k at ANY value including
                // their defaults -- see docs/design/llm/anthropic-sampling-capabilities.md section 2. Suppression
                // loses nothing: omitting temperature yields 1.0, which is the one value they accept. That is the
                // same argument the registerPrefix("gpt-5", ...) comment above makes, arriving from another vendor.
                //
                // Prefixes rather than exact names so that a dated snapshot (claude-opus-5-2026...) inherits the
                // row. Deliberately NOT "claude-opus-4": claude-opus-4-5 and claude-opus-4-6 accept all three
                // (measured), and a family prefix would suppress a parameter they take. Five prefixes cover six
                // measured names because claude-fable-5 also matches claude-fable-5-1.
                //
                // Registration order is free here: no claude-* prefix can collide with a gpt-*/o[134] one, and none
                // of these five is a prefix of another (-4-7 and -4-8 are siblings, not nested).
                //
                // This table is read by BOTH clients. A claude-* name reaching OpenAILlmClient through an
                // OpenAI-compatible gateway resolves to these rows and gets the same suppression -- correct, since
                // the underlying model does refuse, but it is a consequence of one shared table rather than of
                // anything either client says. Weigh it when editing a row: the blast radius is both providers.
                //
                // Two facts are stated, and the second arrived a round later. supportsSamplingParameters is the
                // measured one above. thinkingDialect is ADAPTIVE, read off the vendor's per-model thinking table
                // quoted in docs/design/llm/anthropic-thinking-traces.md section 2.1: every name these five
                // prefixes reach is listed there as "adaptive only" and answers 400 to thinking.type=enabled. It is
                // documentation rather than measurement, which is why it is a dialect rather than a permission --
                // getting it wrong costs the same 400 an operator gets today, now with the framework's name on it.
                //
                // The other three stay fail-open on purpose: the Anthropic client captures and replays thinking
                // blocks unconditionally and takes no reasoning-effort parameter of the OpenAI shape, so it never
                // reads them -- a value there would be an assertion nothing consumes and nothing measured.
                .registerPrefix("claude-fable-5", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-5", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-4-7", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-4-8", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-sonnet-5", ADAPTIVE_REFUSING_SAMPLING)
                // Documentation-derived, not measured: no Mythos model is visible to the account the probes ran on,
                // so neither the fact nor the identifier shape was called. It ships because the vendor sentence
                // enumerates nine names and all six reachable ones matched it exactly -- evidence about that
                // sentence rather than about a sibling's name prefix. One family prefix asserts only the fact; a
                // guessed "claude-mythos-5-1" would also assert an identifier nobody has seen.
                .registerPrefix("claude-mythos", ADAPTIVE_REFUSING_SAMPLING);
        // Round 8, measured 2026-09-09: these eight names accept a replayed reasoning item on /v1/responses (HTTP
        // 200, turn completed). A 200 alone would only mean "tolerated", so a control corrupted 40 characters of the
        // encrypted payload and got a 400 -- the server decrypts and consumes the item. That control was run on
        // o4-mini (and on gpt-5.6-terra), not on all four; the check reads as platform-level, and that reading is an
        // inference. It is what routes these names to the Responses path, where reasoning survives a tool call
        // instead of being thrown away between turns.
        //
        // Unlike the prefix block above, registration order is NOT load-bearing here: an exact entry beats every
        // prefix whatever position it was registered in. That is also the property that makes an exact row a poor
        // instrument for the gpt-5 family -- it would shadow the documented registerPrefix("gpt-5", ...) override --
        // which is why no gpt-5* name has one.
        MEASURED_O_SERIES_NAMES.forEach(name -> builder.register(name, O_SERIES_REPLAY_MEASURED));
        return builder;
    }

    /**
     * The o-series row, in the one variant that differs: whether this build has seen the model replay a reasoning
     * item. Everything else about the row is the same measurement and is shared by construction.
     */
    private static ModelCapabilities oSeries(boolean reasoningTraceRoundTrip) {
        return ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(reasoningTraceRoundTrip)
                .lowestReasoningEffort(ReasoningEffort.LOW).build();
    }

    /**
     * Returns a registry with the framework-default entries.
     *
     * <p>
     * The table is kept as small as the problem: it describes only the families whose request surface is known to
     * differ from the historical default — {@code gpt-5-chat}, {@code gpt-5}, the o-series, and the Anthropic models
     * that refuse sampling parameters and speak the adaptive thinking dialect — six whose <em>sampling</em> refusal
     * was measured, plus the documentation-derived {@code claude-mythos} family, with the dialect on all seven read
     * off the vendor's published per-model table rather than called. Everything else resolves to
     * {@link ModelCapabilities#unknown()}, whose dialect is {@link ThinkingDialect#UNKNOWN} — so a model this table
     * has never heard of keeps the thinking request it had before the dialect existed.
     *
     * @return a registry with framework-default capability entries
     */
    public static InMemoryModelCapabilityRegistry withDefaults() {
        return builderWithDefaults().build();
    }

    /**
     * Returns a registry with the framework-default entries plus one exact entry per declaration.
     *
     * <p>
     * This is the one implementation of what a <em>configured</em> capability entry means, called by both
     * configuration surfaces. Three rules are decided here and nowhere else.
     *
     * <ul>
     * <li><strong>It extends, it does not replace.</strong> The starting point is {@link #builderWithDefaults()}, so an
     * operator who names one deployment keeps the other rows.
     * <li><strong>Declarations are exact entries.</strong> Which means the existing precedence rule already answers
     * "who wins": an exact entry beats every prefix. It also means a declaration names <em>one model</em> — declaring
     * {@code gpt-5} overrides that exact name and leaves {@code gpt-5-mini} on the built-in {@code gpt-5} prefix.
     * There is deliberately no way to declare a prefix from configuration: prefix precedence is registration order,
     * and an order that a yaml file's line order decided would be a rule this table does not have.
     * <li><strong>An omitted flag stays fail-open</strong>, by way of
     * {@link ModelCapabilityDeclaration#capabilities()}. A declaration is the whole row for that name, not a patch on
     * one: declaring a name the built-in table already carries — {@code o4-mini}, say — replaces every flag of it,
     * so the undeclared ones fall back to fail-open rather than to what the built-in row said. That is the safe
     * direction (the request keeps today's shape and stays on Chat Completions), but for the measured o-series names
     * it does cost {@code supportsReasoningTraceRoundTrip} and the {@code LOW} floor, which is worth restating in
     * the declaration if the deployment behind the name is the model that was measured.
     * </ul>
     *
     * <p>
     * Names are rejected rather than repaired. Each rejected shape is one whose failure would otherwise be silent: a
     * blank or padded name never matches anything, two names differing only in case leave one of them quietly beaten
     * by the other, and an entry that declares nothing registers the capabilities the model already had. Callers wrap
     * the {@link IllegalArgumentException} in whatever names their own key path — a CLI yaml key, a Spring property —
     * and none of them re-implement the judgement.
     *
     * @param declarations
     *            model name to declaration; {@code null} or empty yields plain {@link #withDefaults()}
     * @return a registry carrying the built-in entries plus the declarations
     * @throws IllegalArgumentException
     *             if a name is blank, is padded with whitespace, collides with another name once case is folded, or
     *             its declaration is null
     */
    public static InMemoryModelCapabilityRegistry withDefaultsExtendedBy(
            Map<String, ModelCapabilityDeclaration> declarations) {
        final Builder builder = builderWithDefaults();
        if (declarations == null || declarations.isEmpty()) {
            return builder.build();
        }
        final Map<String, String> seen = new LinkedHashMap<>();
        for (Map.Entry<String, ModelCapabilityDeclaration> entry : declarations.entrySet()) {
            final String name = requireUsableName(entry.getKey());
            final String previous = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (previous != null) {
                throw new IllegalArgumentException("Model capability declarations '" + previous + "' and '" + name
                        + "' differ only in case, and model names are matched ignoring case — one of them would"
                        + " silently win. Keep one.");
            }
            // A null declaration is what an entry with an empty body binds to, at least under Jackson, which keeps the
            // key and stores null. Caught here rather than on each surface so that the outcome does not depend on
            // which binder produced it, and reported as the same thing the operator did: they declared nothing.
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Model capability declaration '" + name + "' is empty. Such an"
                        + " entry registers the same fail-open capabilities the model already had, so it would bind"
                        + " and do nothing; state at least one flag, or remove it.");
            }
            builder.register(name, entry.getValue().capabilities());
        }
        return builder.build();
    }

    private static String requireUsableName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("A model capability declaration needs a model name, and one of them is"
                    + " blank. The name is the one this deployment calls the model by — the same value the LLM"
                    + " configuration's model setting carries.");
        }
        if (!modelName.equals(modelName.trim())) {
            throw new IllegalArgumentException("Model capability declaration '" + modelName + "' is padded with"
                    + " whitespace. Names are matched literally apart from case, so this entry would never match"
                    + " anything — a no-op nothing would report. Remove the surrounding spaces.");
        }
        return modelName;
    }

    @Override
    public Optional<ModelCapabilities> capabilitiesOf(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return Optional.empty();
        }
        // Both halves fold case, and they have to agree: an operator registering the name their Azure portal shows
        // ("Prod-Assistant") and a config carrying that same name must meet, or the one line that closes the
        // fail-open gap silently does not.
        final String lower = modelName.toLowerCase(Locale.ROOT);
        final ModelCapabilities exact = exactEntries.get(lower);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, ModelCapabilities> entry : prefixEntries.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /** Builder for {@link InMemoryModelCapabilityRegistry}. */
    public static final class Builder {
        private final ConcurrentHashMap<String, ModelCapabilities> exactEntries = new ConcurrentHashMap<>();
        private final LinkedHashMap<String, ModelCapabilities> prefixEntries = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registers an exact model-name to capabilities mapping. Exact entries beat every prefix.
         *
         * <p>
         * The match is <strong>case-insensitive</strong>, like {@link #registerPrefix}: a deployment name is
         * something an operator copies out of a portal, and having one half of this table fold case while the other
         * did not made a correct registration miss for a reason nothing reported.
         *
         * @param modelName
         *            the exact model identifier, matched ignoring case (must not be null)
         * @param capabilities
         *            the capabilities (must not be null)
         * @return this builder
         */
        public Builder register(String modelName, ModelCapabilities capabilities) {
            Objects.requireNonNull(modelName, "modelName cannot be null");
            Objects.requireNonNull(capabilities, "capabilities cannot be null");
            exactEntries.put(modelName.toLowerCase(Locale.ROOT), capabilities);
            return this;
        }

        /**
         * Registers a case-insensitive prefix match.
         *
         * <p>
         * Patterns are evaluated in registration order and the first match wins, so ordering is load-bearing: register
         * more specific prefixes (e.g. {@code gpt-5-chat}) before their broader siblings (e.g. {@code gpt-5}), or the
         * broader one swallows them.
         *
         * @param modelNamePrefix
         *            the case-insensitive model-name prefix (must not be null)
         * @param capabilities
         *            the capabilities (must not be null)
         * @return this builder
         */
        public Builder registerPrefix(String modelNamePrefix, ModelCapabilities capabilities) {
            Objects.requireNonNull(modelNamePrefix, "modelNamePrefix cannot be null");
            Objects.requireNonNull(capabilities, "capabilities cannot be null");
            prefixEntries.put(modelNamePrefix.toLowerCase(Locale.ROOT), capabilities);
            return this;
        }

        /**
         * @return a new {@link InMemoryModelCapabilityRegistry}
         */
        public InMemoryModelCapabilityRegistry build() {
            return new InMemoryModelCapabilityRegistry(this);
        }
    }
}
