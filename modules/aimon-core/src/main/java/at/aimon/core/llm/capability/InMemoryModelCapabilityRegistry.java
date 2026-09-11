package at.aimon.core.llm.capability;

import java.util.EnumSet;
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
 * {@code claude-*} rows come in two shapes — six prefixes that state two flags (the names they match refuse the
 * sampling parameters <em>and</em> speak the {@link ThinkingDialect#ADAPTIVE} thinking dialect) and five that state
 * the dialect alone — and the {@code gpt-*} / {@code o*} rows cannot match a {@code claude-*} name or the other way
 * round, so the two blocks do not interfere. What they do share is the
 * look-up: a {@code claude-*} name reaching {@code OpenAILlmClient} through an OpenAI-compatible gateway resolves to
 * the Anthropic rows and has its sampling suppressed too. That is the right answer arriving from an unexpected
 * direction, and it is stated here because it is invisible from either client's source. The dialect travels the same
 * way and costs nothing there: no OpenAI path reads it.
 *
 * <p>
 * <strong>The dialect half of the Anthropic block is measured, 2026-09-10</strong>: five prefix rows carrying nothing
 * but a dialect — {@code claude-opus-4-5} / {@code claude-sonnet-4-5} / {@code claude-haiku-4-5} as
 * {@link ThinkingDialect#BUDGETED}, and {@code claude-opus-4-6} / {@code claude-sonnet-4-6} as
 * {@link ThinkingDialect#EITHER}, the two names measured to accept both request shapes. The three budgeted prefixes
 * cover six measured names, because the undated aliases resolve to the dated snapshots and are not in the model
 * listing at all. They state a dialect and nothing else on purpose: these are the names the {@code claude-opus-4}
 * warning below is about, and they accept the sampling parameters.
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
 * The {@code gpt-5} family pays the same price for one name, and it is stated the same way:
 * {@code builderWithDefaults().registerPrefix("gpt-5", ...)} reaches {@code gpt-5-mini}, {@code gpt-5-nano} and every
 * future {@code gpt-5*} name, but <strong>not {@code gpt-5.6-terra}</strong>, whose measured ladder earned it an
 * exact row of its own. The remedy is the o-series one: {@code register("gpt-5.6-terra", ...)}, or a configured
 * declaration for that name, displaces the built-in row. That promise used to be the stronger "every {@code gpt-5*}
 * name, always", which is the promise the exact row broke — it was worth keeping only while nothing measured
 * contradicted it, and round 8 measured a contradiction that costs a shipped HTTP 400.
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
     * The dialect and nothing else, in the two states the 2026-09-10 census measured that
     * {@link #ADAPTIVE_REFUSING_SAMPLING} cannot express. See the comment beside the registrations for why one fact is
     * the whole row — above all why {@code supportsSamplingParameters} stays at its fail-open {@code true} here, which
     * is the half of these rows that would silently change the wire if it did not.
     */
    private static final ModelCapabilities BUDGETED_DIALECT_ONLY = ModelCapabilities.builder()
            .thinkingDialect(ThinkingDialect.BUDGETED).build();

    /** @see #BUDGETED_DIALECT_ONLY */
    private static final ModelCapabilities EITHER_DIALECT_ONLY = ModelCapabilities.builder()
            .thinkingDialect(ThinkingDialect.EITHER).build();

    /**
     * The o-series names whose reasoning-item replay was measured on 2026-09-09, alias and served snapshot alike. The
     * dated names were never <em>sent</em> — they were returned, as the {@code model} of the response whose replayed
     * item was accepted — so registering them is the honest reading of what answered, with one assumption stated in
     * section 6.3 of {@code docs/design/llm/model-capabilities.md}: that requesting a snapshot reaches it.
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
     * <p>
     * <strong>One name that promise no longer reaches, named here because this is the method that makes it:</strong>
     * {@code registerPrefix("gpt-5", ...)} does not override {@code gpt-5.6-terra}, whose measured ladder earned it
     * an exact row that shadows every prefix. Use {@code register("gpt-5.6-terra", ...)} for that one name. This
     * class's javadoc states the exception in full, including why the promise was narrowed rather than the registry
     * reshaped.
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
                // The default accepted ladder (MINIMAL..HIGH) is right for this family and is left unset: it answers
                // "Supported values are: 'minimal', 'low', 'medium', and 'high'", so only NONE is off its ladder.
                //
                // supportsToolsWithReasoning is TRUE, and that reverses round 1. Measured 2026-09-09: gpt-5-nano with
                // tools and no reasoning_effort returns 200, so there is no conflict to work around; and the remedy
                // false used to trigger -- sending effort "none" -- is itself rejected, since "none" is not among the
                // accepted values ('minimal', 'low', 'medium', 'high'). See section 11 of
                // docs/design/llm/model-capabilities.md section 6 for the probe table.
                //
                // Round 8 measured one member this ladder gets wrong: gpt-5.6-terra resolves here and REJECTS
                // 'minimal' (its ladder is none/low/medium/high/xhigh/max), so a MINIMAL configured on that name was
                // a 400. Round 9 gave that name an exact row -- below, beside the o-series ones -- which shadows
                // this prefix for it alone; see this class's javadoc for what that costs an override, and
                // docs/design/llm/model-capabilities.md section 4.3 for why the promise was narrowed rather than
                // the registry reshaped.
                .registerPrefix("gpt-5", gpt5Family().build())
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
                .registerPrefix("o4", O_SERIES_REPLAY_UNMEASURED);
        registerAnthropicDefaults(builder);
        // Round 8, measured 2026-09-09: these eight names accept a replayed reasoning item on /v1/responses (HTTP
        // 200, turn completed). A 200 alone would only mean "tolerated", so a control corrupted 40 characters of the
        // encrypted payload and got a 400 -- the server decrypts and consumes the item. That control was run on
        // o4-mini (and on gpt-5.6-terra), not on all four; the check reads as platform-level, and that reading is an
        // inference. It is what routes these names to the Responses path, where reasoning survives a tool call
        // instead of being thrown away between turns.
        //
        // Unlike the prefix block above, registration order is NOT load-bearing here: an exact entry beats every
        // prefix whatever position it was registered in. That is also what an exact row costs: it shadows the
        // documented registerPrefix("gpt-5", ...) / registerPrefix("o1", ...) overrides for its own name, and both
        // blocks below accept that cost for the same reason -- a measured fact about one name beats a tidier
        // override recipe. The remedy is in this class's javadoc and is the same for both.
        MEASURED_O_SERIES_NAMES.forEach(name -> builder.register(name, O_SERIES_REPLAY_MEASURED));
        // Round 8, measured 2026-09-09 (docs/design/llm/model-capabilities.md section 6.1): all seven rungs
        // were sent to gpt-5.6-terra individually and it answered 'none', 'low', 'medium', 'high', 'xhigh' and
        // 'max' -- and REJECTED 'minimal', which is the one value the family prefix's ladder asserts it takes. A
        // floor cannot describe a ladder with a hole in the middle, which is why this field is a set.
        //
        // Two rungs are missing from the row on purpose: 'xhigh' and 'max' have no ReasoningEffort constant, since
        // that enum is deliberately the subset that survives translation to a second vendor. The set states the
        // four rungs the neutral vocabulary has.
        //
        // Exact rather than a prefix. Round 8 measured this NAME; gpt-5.6-luna and gpt-5.6-sol were seen in the
        // model listing and deliberately not called, so nothing is known about them, and nothing has seen a dated
        // terra snapshot at all. A gpt-5.6-terra-<date> name, if one ever appears, lands on the gpt-5 prefix and
        // inherits the ladder this row exists to correct, until somebody measures or declares it.
        //
        // The ladder is the ONLY thing that differs from the family row, and gpt5Family() is what keeps that true
        // rather than leaving two copies of four flags to drift. supportsReasoningTraceRoundTrip in particular has
        // to stay true here: false would quietly route this name off /v1/responses.
        builder.register("gpt-5.6-terra", gpt5Family().acceptedReasoningEfforts(
                EnumSet.of(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))
                .build());
        return builder;
    }

    /**
     * Registers the eleven {@code claude-*} prefixes, so that {@link #builderWithDefaults()} stays readable.
     *
     * <p>
     * Extracted for length rather than for structure: the rows below are two blocks measured a day apart and the
     * comments that say what each one does and does not assert are longer than the registrations. Registration order
     * is free for all eleven — see the second comment in the block — so lifting them out of the chain changes
     * nothing about what the table answers.
     */
    private static void registerAnthropicDefaults(Builder builder) {
        // Anthropic, measured 2026-09-09 against the account's own /v1/models listing. On these six the
        // server refuses temperature at any non-default value, and top_p / top_k at ANY value including
        // their defaults -- see docs/design/llm/model-capabilities.md section 6.2. Suppression
        // loses nothing: omitting temperature yields 1.0, which is the one value they accept. That is the
        // same argument the registerPrefix("gpt-5", ...) comment above makes, arriving from another vendor.
        //
        // Prefixes rather than exact names so that a dated snapshot (claude-opus-5-2026...) inherits the
        // row. Deliberately NOT "claude-opus-4": claude-opus-4-5 and claude-opus-4-6 accept all three
        // (measured), and a family prefix would suppress a parameter they take. Those two names now do have
        // rows of their own, added by the 2026-09-10 dialect census below -- and those rows carry a dialect
        // and nothing else, precisely so that this warning keeps holding. Five prefixes cover six measured
        // names because claude-fable-5 also matches claude-fable-5-1.
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
        // (see docs/design/llm/model-capabilities.md section 6.2): every name these five
        // prefixes reach is listed there as "adaptive only" and answers 400 to thinking.type=enabled.
        // It shipped as documentation rather than measurement; the 2026-09-10 census confirmed all five
        // live (adaptive 200, budgeted 400) across six model names. claude-mythos below is still
        // documentation alone, because no model with that prefix is reachable.
        //
        // The other three stay fail-open on purpose: the Anthropic client captures and replays thinking
        // blocks unconditionally and takes no reasoning-effort parameter of the OpenAI shape, so it never
        // reads them -- a value there would be an assertion nothing consumes and nothing measured.
        builder.registerPrefix("claude-fable-5", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-5", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-4-7", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-opus-4-8", ADAPTIVE_REFUSING_SAMPLING)
                .registerPrefix("claude-sonnet-5", ADAPTIVE_REFUSING_SAMPLING)
                // Documentation-derived, not measured: no Mythos model is visible to the account the probes ran on,
                // so neither the fact nor the identifier shape was called. It ships because the vendor sentence
                // enumerates nine names and all six reachable ones matched it exactly -- evidence about that
                // sentence rather than about a sibling's name prefix. One family prefix asserts only the fact; a
                // guessed "claude-mythos-5-1" would also assert an identifier nobody has seen.
                .registerPrefix("claude-mythos", ADAPTIVE_REFUSING_SAMPLING)
                // The dialect census, measured 2026-09-10 (docs/design/llm/model-capabilities.md section
                // 6.3). Probing surface: this account's GET /v1/models listing PLUS three undated aliases the
                // listing does not contain -- claude-opus-4-5, claude-sonnet-4-5 and claude-haiku-4-5 all resolve
                // (the response's `model` names the dated snapshot) and speak the same dialect, so the listing is
                // not the set of callable names. That correction is why these are prefixes: three rows cover six
                // measured names, and the alias is the name a deployment is most likely to write.
                //
                // Two request shapes, on every name: {"type":"adaptive"} + output_config.effort, and
                // {"type":"enabled","budget_tokens":1024}. The 4-5 families answered 400 / 200 -- BUDGETED. The two
                // 4-6 models answered 200 / 200 -- both, which is what EITHER records.
                //
                // What these five rows deliberately do NOT say is the load-bearing half.
                // supportsSamplingParameters stays at its fail-open true: these are exactly the names the prefix
                // comment above warns a family prefix must not catch, because they ACCEPT temperature, top_p and
                // top_k (measured 2026-09-09) and suppressing them would be a silent wire change. Nothing else was
                // measured either, so nothing else is stated -- same rule as the ADAPTIVE block's last paragraph.
                //
                // Registration order is free here too, and it was checked rather than assumed: none of these five
                // is a prefix of another or of an existing one (-4-5 / -4-6 / -4-7 / -4-8 are siblings, not
                // nested), and claude-sonnet-4-20250514 -- AnthropicConfig's default model until #116 measured it
                // unserved on 2026-09-11 -- does not start with claude-sonnet-4-5, so it stays undescribed. The
                // default is now claude-sonnet-4-5 itself, which the row below describes.
                .registerPrefix("claude-opus-4-5", BUDGETED_DIALECT_ONLY)
                .registerPrefix("claude-sonnet-4-5", BUDGETED_DIALECT_ONLY)
                .registerPrefix("claude-haiku-4-5", BUDGETED_DIALECT_ONLY)
                // EITHER rather than ADAPTIVE, and that is a decision rather than caution: an ADAPTIVE row would
                // translate a thinkingMode(EXTENDED) request -- a working, explicitly requested shape carrying the
                // operator's exact token budget -- on the strength of a vendor preference, and the translation
                // warning would then assert "which rejects the other one with HTTP 400" about a model measured to
                // accept it. The vendor's preference (its per-model table marks the budgeted shape deprecated on
                // these two, and the vendored SDK prints that at every call) is real, and it decides only what an
                // AUTO request sends -- which is a client policy, stated in AnthropicThinkingResolver, not a row.
                .registerPrefix("claude-opus-4-6", EITHER_DIALECT_ONLY)
                .registerPrefix("claude-sonnet-4-6", EITHER_DIALECT_ONLY);
    }

    /**
     * The {@code gpt-5} family row's four flags, as a builder the caller finishes.
     *
     * <p>
     * A builder rather than a finished descriptor, and that is the whole point: the family prefix calls
     * {@code build()} straight away and so leaves the ladder at its fail-open value — which is what the prefix
     * comment argues for and what measurement still supports for {@code gpt-5} / {@code gpt-5-mini} /
     * {@code gpt-5-nano} — while {@code gpt-5.6-terra} states its own before building. So "terra is the family row
     * with a different ladder" is a fact of this source rather than of two copies of four flags staying in step, and
     * the prefix row still says nothing it has not measured.
     */
    private static ModelCapabilities.Builder gpt5Family() {
        return ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(true);
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
     * differ from the historical default — {@code gpt-5-chat}, {@code gpt-5}, {@code gpt-5.6-terra} (the family row
     * with the one measured ladder that has a gap in it), the o-series, and eleven Anthropic prefixes. Six of those
     * refuse sampling parameters and speak the adaptive thinking dialect — five whose <em>sampling</em> refusal was
     * measured, plus the documentation-derived {@code claude-mythos} family; the adaptive dialect on five of the six
     * was read off the vendor's published per-model table and then confirmed live on 2026-09-10, and
     * {@code claude-mythos} remains documentation alone. The other five state a <em>dialect only</em>, measured
     * 2026-09-10: three {@link ThinkingDialect#BUDGETED} and two {@link ThinkingDialect#EITHER}. Everything else
     * resolves to {@link ModelCapabilities#unknown()}, whose dialect is {@link ThinkingDialect#UNKNOWN} — so a model
     * this table has never heard of keeps the thinking request it had before the dialect existed.
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
