package at.aimon.core.llm.capability;

import java.util.LinkedHashMap;
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
 * The o-series ({@code o1} / {@code o3} / {@code o4-mini}) is deliberately <em>not</em> in the built-in table: those
 * models take a reasoning effort but reject the value {@code none}, so an entry for them must keep
 * {@link ModelCapabilities#supportsToolsWithReasoning()} at {@code true}:
 *
 * <pre>
 * {@code
 * InMemoryModelCapabilityRegistry.builderWithDefaults()
 *         .registerPrefix("o3", ModelCapabilities.builder().supportsSamplingParameters(false)
 *                 .supportsReasoningEffort(true)
 *                 .supportsToolsWithReasoning(true) // MUST stay true: the o-series rejects effort "none"
 *                 .build())
 *         .build();
 * }
 * </pre>
 */
public final class InMemoryModelCapabilityRegistry implements ModelCapabilityRegistry {

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
        return builder()
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
                .registerPrefix("gpt-5",
                        ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(true).build())
                // The o-series, measured 2026-09-09 and no longer inferred. Round 1 cut these rows because the belief
                // that they reject sampling was unverified and a wrong row is a *silent* change; the probes closed
                // that. o3-mini and o4-mini reject temperature 0.0 and accept 1.0, accept tools with no effort, and
                // reject effort "none" -- so supportsToolsWithReasoning MUST stay true here, exactly as this class's
                // own javadoc example has warned all along. They are not routed to /v1/responses: this build has not
                // measured reasoning-item replay for them, and asserting a round trip we have not seen is how round 1
                // got the gpt-5 row wrong.
                //
                // lowestReasoningEffort is LOW rather than the default MINIMAL, and that is the second half of the
                // same probe: this family answers "Supported values are: 'low', 'medium', 'high', and 'xhigh'", so
                // MINIMAL is off its ladder exactly as NONE is. Without the row the neutral MINIMAL would translate
                // to the wire value 'minimal' and 400.
                .registerPrefix("o1",
                        ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(false)
                                .lowestReasoningEffort(ReasoningEffort.LOW).build())
                .registerPrefix("o3",
                        ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(false)
                                .lowestReasoningEffort(ReasoningEffort.LOW).build())
                .registerPrefix("o4",
                        ModelCapabilities.builder().supportsSamplingParameters(false).supportsReasoningEffort(true)
                                .supportsToolsWithReasoning(true).supportsReasoningTraceRoundTrip(false)
                                .lowestReasoningEffort(ReasoningEffort.LOW).build());
    }

    /**
     * Returns a registry with the framework-default entries.
     *
     * <p>
     * The table is kept as small as the problem: it describes only the families whose request surface is known to
     * differ from the historical default. Everything else — including the o-series — resolves to
     * {@link ModelCapabilities#unknown()} and keeps behaving exactly as it does today.
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
     * {@link ModelCapabilityDeclaration#capabilities()}.
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
