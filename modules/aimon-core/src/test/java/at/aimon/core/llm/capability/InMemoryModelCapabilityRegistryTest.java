package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;

@DisplayName("InMemoryModelCapabilityRegistry - lookup and the built-in table")
class InMemoryModelCapabilityRegistryTest {

    private static final ModelCapabilities SAMPLING_REJECTED = ModelCapabilities.builder()
            .supportsSamplingParameters(false).build();
    private static final ModelCapabilities EVERYTHING_ALLOWED = ModelCapabilities.builder()
            .supportsSamplingParameters(true).supportsReasoningEffort(true).supportsToolsWithReasoning(true).build();

    @Test
    @DisplayName("an exact entry beats a prefix that would also match")
    void exactBeatsPrefix() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("gpt-5", SAMPLING_REJECTED).register("gpt-5-special", EVERYTHING_ALLOWED).build();

        assertThat(registry.capabilitiesOf("gpt-5-special")).contains(EVERYTHING_ALLOWED);
        assertThat(registry.capabilitiesOf("gpt-5-other")).contains(SAMPLING_REJECTED);
    }

    @Test
    @DisplayName("prefixes are evaluated in registration order and the first match wins")
    void prefixFirstMatchWins() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("gpt-5-chat", EVERYTHING_ALLOWED).registerPrefix("gpt-5", SAMPLING_REJECTED).build();

        assertThat(registry.capabilitiesOf("gpt-5-chat-latest")).contains(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("prefix matching is case-insensitive")
    void prefixMatchingIsCaseInsensitive() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .registerPrefix("GPT-5", SAMPLING_REJECTED).build();

        assertThat(registry.capabilitiesOf("gpt-5.6-terra")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("GPT-5.6-TERRA")).contains(SAMPLING_REJECTED);
    }

    @Test
    @DisplayName("exact matching is case-insensitive too, so a portal-cased deployment name resolves")
    void exactMatchingIsCaseInsensitive() {
        // The whole point of the exact map is that an operator can name a deployment their gateway renamed, and the
        // name they have is the one their portal shows. While this half was case-sensitive, registering
        // "prod-assistant" for a deployment configured as "Prod-Assistant" missed, no prefix caught it, the model
        // resolved to unknown() -- and the one line that closes the fail-open gap did nothing, silently.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builder()
                .register("prod-assistant", SAMPLING_REJECTED).register("OTHER-Deployment", EVERYTHING_ALLOWED).build();

        assertThat(registry.capabilitiesOf("Prod-Assistant")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("prod-assistant")).contains(SAMPLING_REJECTED);
        assertThat(registry.capabilitiesOf("other-deployment")).contains(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("the o-series rows start their reasoning ladder at LOW, and gpt-5.x keeps the default MINIMAL")
    void oSeriesLadderStartsAtLow() {
        // Measured 2026-09-09. Round 8 replaced the evidence for this row without moving it: round 6 quoted
        // "Supported values are: 'low', 'medium', 'high', and 'xhigh'" for o4-mini without recording that it came
        // from /v1/chat/completions, and on /v1/responses the same model answers "Unsupported value: 'minimal' is
        // not supported with the 'o4-mini' model. Supported values are: 'low', 'medium', and 'high'." The two
        // surfaces enumerate different sets; the FLOOR is 'low' on both, which is all this field is about.
        // gpt-5-nano answers "'minimal', 'low', 'medium', and 'high'" on Chat. Without the row, the neutral MINIMAL
        // would translate to the wire value 'minimal' for the o-series and earn a 400.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("o4-mini").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("o3-mini").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("o1").lowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
        assertThat(registry.resolve("gpt-5.6-terra").lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        // A model nobody describes keeps the fail-open floor, so nothing it was ever sent starts being withheld.
        assertThat(registry.resolve("gpt-4o").lowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
    }

    @Test
    @DisplayName("a null or empty model name is unknown")
    void nullOrEmptyNameIsUnknown() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.capabilitiesOf(null)).isEmpty();
        assertThat(registry.capabilitiesOf("")).isEmpty();
        assertThat(registry.resolve(null)).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("registering a null name or null capabilities is rejected")
    void nullRegistrationsRejected() {
        final InMemoryModelCapabilityRegistry.Builder builder = InMemoryModelCapabilityRegistry.builder();

        assertThatThrownBy(() -> builder.register(null, EVERYTHING_ALLOWED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.register("gpt-5", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.registerPrefix(null, EVERYTHING_ALLOWED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.registerPrefix("gpt-5", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the built-in table suppresses sampling and enables reasoning for the gpt-5 family")
    void defaultsDescribeGpt5() {
        final ModelCapabilities gpt5 = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra");

        assertThat(gpt5.supportsSamplingParameters()).isFalse();
        assertThat(gpt5.supportsReasoningEffort()).isTrue();
        // Reversal, measured 2026-09-09: this asserted isFalse() until live probes showed gpt-5-nano answers 200 to
        // tools with no reasoning_effort, and rejects the "none" that false made the client send. Do not flip it back
        // without a probe -- false is not the cautious choice here, it is the one that produces a 400.
        assertThat(gpt5.supportsToolsWithReasoning()).isTrue();
        assertThat(gpt5.supportsReasoningTraceRoundTrip()).isTrue();
    }

    @Test
    @DisplayName("gpt-5-chat does not inherit the gpt-5 family's suppression")
    void defaultsKeepGpt5ChatSampling() {
        // Ordering inside withDefaults() is load-bearing: gpt-5-chat is the non-reasoning variant and is registered
        // first, exactly as InMemoryModelPriceTable registers gpt-4o-mini before gpt-4o.
        final ModelCapabilities chat = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-chat-latest");

        assertThat(chat.supportsSamplingParameters()).isTrue();
        assertThat(chat.supportsReasoningEffort()).isFalse();
        assertThat(chat).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("models the table does not name stay unknown - gpt-4o and gpt-4-turbo")
    void defaultsLeaveEverythingElseUnknown() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.capabilitiesOf("gpt-4o")).isEmpty();
        assertThat(registry.capabilitiesOf("gpt-4-turbo")).isEmpty();
        assertThat(registry.resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("the o-series names whose replay was measured round-trip, and nothing else about their row moved")
    void measuredOSeriesNamesRoundTrip() {
        // Reversal, measured 2026-09-09. The rows themselves are round 6's: they went in because live probes showed
        // o3-mini and o4-mini answer 400 to temperature 0.0 and 200 to 1.0, accept tools with no reasoning_effort,
        // and reject effort "none". supportsToolsWithReasoning MUST stay true -- false makes the client omit an
        // effort it need not omit, and it is what the class javadoc's own example has warned about all along.
        //
        // Round 8 flipped the fourth flag for these eight names ONLY, after measuring what round 6 could not: each
        // accepts a replayed reasoning item on /v1/responses, and a corrupted payload 400s, so the item is consumed
        // rather than tolerated. The other four flags are asserted here as well, because a copy-paste that also
        // moved sampling would otherwise ship silently -- the failure mode the o-series rows were withheld for in
        // the first place. See docs/design/llm/openai-model-capabilities.md section 13.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"o1", "o1-2024-12-17", "o3", "o3-2025-04-16", "o3-mini", "o3-mini-2025-01-31",
                "o4-mini", "o4-mini-2025-04-16"}) {
            final ModelCapabilities caps = registry.resolve(model);
            assertThat(caps.supportsReasoningTraceRoundTrip()).as("%s replay", model).isTrue();
            assertThat(caps.supportsSamplingParameters()).as("%s sampling", model).isFalse();
            assertThat(caps.supportsReasoningEffort()).as("%s effort", model).isTrue();
            assertThat(caps.supportsToolsWithReasoning()).as("%s tools+reasoning", model).isTrue();
            assertThat(caps.lowestReasoningEffort()).as("%s floor", model).isEqualTo(ReasoningEffort.LOW);
        }
    }

    @Test
    @DisplayName("a prefix sibling nobody was allowed to call keeps the prefix row and stays off the Responses path")
    void unmeasuredPrefixMembersDoNotRoundTrip() {
        // This is the test that goes red if someone later "simplifies" eight exact rows into three prefix flips.
        // o1-pro and o4-mini-deep-research sit under prefixes whose other members were measured, and the 2026-09-09
        // probe was forbidden from calling them on cost grounds -- so nothing at all is known about their
        // reasoning-item replay. o1 passing says nothing about o1-pro: they are different models that happen to
        // share a name prefix, and treating one as evidence for the other is the inference issue #48 exists to
        // delete. The other four flags stay exactly as round 6 set them, which is the half this test also pins.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"o1-pro", "o1-pro-2025-03-19", "o4-mini-deep-research",
                "o4-mini-deep-research-2025-06-26"}) {
            final ModelCapabilities caps = registry.resolve(model);
            assertThat(caps.supportsReasoningTraceRoundTrip()).as("%s replay", model).isFalse();
            assertThat(caps.supportsSamplingParameters()).as("%s sampling", model).isFalse();
            assertThat(caps.supportsReasoningEffort()).as("%s effort", model).isTrue();
            assertThat(caps.supportsToolsWithReasoning()).as("%s tools+reasoning", model).isTrue();
            assertThat(caps.lowestReasoningEffort()).as("%s floor", model).isEqualTo(ReasoningEffort.LOW);
        }
    }

    @Test
    @DisplayName("a future name under a measured prefix keeps today's behaviour until somebody measures it")
    void anUnmeasuredNameUnderAMeasuredPrefixStaysOffTheResponsesPath() {
        // A prefix is not a promise about names nobody has measured, and this says so as an assertion rather than as
        // a comment. The intended failure direction: the table under-delivers a capability until the name is probed,
        // and never asserts a wire change nobody has seen.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("o3-2099-01-01").supportsReasoningTraceRoundTrip()).isFalse();
        assertThat(registry.resolve("o3-pro").supportsReasoningTraceRoundTrip()).isFalse();
    }

    @Test
    @DisplayName("a built-in exact row shadows a caller's prefix override, and register(...) displaces it")
    void anExactRowShadowsACallerPrefixOverride() {
        // The one behaviour regression the exact-row shape introduces, and its escape hatch, pinned together. A
        // caller who overrode registerPrefix("o1", ...) before round 8 reached every o1* name; now the built-in
        // exact rows win for the two measured ones whatever position the override was registered in. The remedy is
        // register(...) -- exact beats exact, last write wins -- and it has to keep working, or the table has a trap
        // with no way out.
        final InMemoryModelCapabilityRegistry viaPrefix = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .registerPrefix("o1", EVERYTHING_ALLOWED).build();

        assertThat(viaPrefix.resolve("o1").supportsReasoningTraceRoundTrip()).isTrue();
        assertThat(viaPrefix.resolve("o1-2024-12-17").supportsReasoningTraceRoundTrip()).isTrue();
        assertThat(viaPrefix.resolve("o1-pro")).isEqualTo(EVERYTHING_ALLOWED);

        final InMemoryModelCapabilityRegistry viaExact = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register("o1", EVERYTHING_ALLOWED).build();

        assertThat(viaExact.resolve("o1")).isEqualTo(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("builderWithDefaults lets a caller name a renamed gateway deployment")
    void builderWithDefaultsAddsADeployment() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register("prod-assistant", ModelCapabilities.builder().supportsSamplingParameters(false)
                        .supportsReasoningEffort(true).supportsToolsWithReasoning(false).build())
                .build();

        assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
        // ... without losing the built-in entries.
        assertThat(registry.resolve("gpt-5.6-terra").supportsReasoningEffort()).isTrue();
        assertThat(registry.resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("builderWithDefaults can override a built-in prefix without reordering it")
    void builderWithDefaultsOverridesAPrefixInPlace() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .registerPrefix("gpt-5", EVERYTHING_ALLOWED).build();

        assertThat(registry.resolve("gpt-5.6-terra")).isEqualTo(EVERYTHING_ALLOWED);
        // Re-putting an existing key keeps its position in the LinkedHashMap, so gpt-5 cannot jump ahead of the more
        // specific gpt-5-chat and swallow it.
        assertThat(registry.resolve("gpt-5-chat-latest")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("reasoning traces round-trip for the gpt-5 family and for the measured o-series names only")
    void reasoningTraceRoundTripIsSetOnlyWhereItIsTrue() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("gpt-5.6-terra").supportsReasoningTraceRoundTrip()).isTrue();
        // gpt-5-chat is the non-reasoning variant, and this false is what keeps the existing assertion that it
        // resolves equal to unknown() green -- i.e. it is the line that fails if the chat variant is ever routed to
        // the Responses API.
        assertThat(registry.resolve("gpt-5-chat-latest").supportsReasoningTraceRoundTrip()).isFalse();
        assertThat(registry.resolve("gpt-4o").supportsReasoningTraceRoundTrip()).isFalse();
        // Round 8: measured, so true. Its prefix sibling was never called, so false. The pair in one place is the
        // shortest statement of what the o-series table actually claims.
        assertThat(registry.resolve("o3").supportsReasoningTraceRoundTrip()).isTrue();
        assertThat(registry.resolve("o1-pro").supportsReasoningTraceRoundTrip()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------
    // withDefaultsExtendedBy -- the configuration surfaces' entry point
    // ---------------------------------------------------------------------------------------------------------

    private static final ModelCapabilityDeclaration SAMPLING_REJECTED_DECLARATION = ModelCapabilityDeclaration.builder()
            .supportsSamplingParameters(false).build();

    @Test
    @DisplayName("a declared entry is added and the built-in rows are all still there")
    void declarationsExtendRatherThanReplace() {
        final InMemoryModelCapabilityRegistry extended = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("prod-assistant", SAMPLING_REJECTED_DECLARATION));
        final InMemoryModelCapabilityRegistry stock = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(extended.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
        // One name per built-in row, stated as assertions rather than as a count so that a row silently changing
        // value also fails. The list grew when round 8 added eight exact o-series rows beside the three prefix ones:
        // o3-mini and o4-mini now land on exact rows, so o3-x and o4-x are here to keep sampling the prefix rows
        // they used to stand for. Dropping either pair would leave a built-in row this test no longer watches.
        assertThat(extended.resolve("gpt-5-chat-latest")).isEqualTo(stock.resolve("gpt-5-chat-latest"));
        assertThat(extended.resolve("gpt-5.6-terra")).isEqualTo(stock.resolve("gpt-5.6-terra"));
        assertThat(extended.resolve("o1-x")).isEqualTo(stock.resolve("o1-x"));
        assertThat(extended.resolve("o3-x")).isEqualTo(stock.resolve("o3-x"));
        assertThat(extended.resolve("o4-x")).isEqualTo(stock.resolve("o4-x"));
        assertThat(extended.resolve("o3-mini")).isEqualTo(stock.resolve("o3-mini"));
        assertThat(extended.resolve("o4-mini")).isEqualTo(stock.resolve("o4-mini"));
    }

    @Test
    @DisplayName("no declarations is the plain built-in table")
    void noDeclarationsIsTheDefaultTable() {
        assertThat(InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(Map.of()).resolve("gpt-5.6-terra"))
                .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
        assertThat(InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(null).resolve("o3-mini"))
                .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("o3-mini"));
    }

    @Test
    @DisplayName("a declaration wins over a built-in prefix for that one name, and only that one")
    void aDeclarationNamesOneModelRatherThanAFamily() {
        // The consequence of registering declarations as exact entries, and the reason configuration has no prefix
        // form: exact beats every prefix, so no new precedence rule was invented -- but the win is one name wide.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("gpt-5-nano", EVERYTHING_ALLOWED_DECLARATION));

        assertThat(registry.resolve("gpt-5-nano")).isEqualTo(EVERYTHING_ALLOWED_DECLARATION.capabilities());
        assertThat(registry.resolve("gpt-5-mini"))
                .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
    }

    private static final ModelCapabilityDeclaration EVERYTHING_ALLOWED_DECLARATION = ModelCapabilityDeclaration
            .builder().supportsSamplingParameters(true).supportsReasoningEffort(true).supportsToolsWithReasoning(true)
            .build();

    @Test
    @DisplayName("every built-in row can be written as a declaration")
    void theConfigurationSurfaceCanExpressEveryBuiltInRow() {
        // The invariant behind exposing all five flags rather than the two an operator usually needs: if a row of the
        // shipped table could not be transcribed, the surface would be unable to describe a deployment that renamed
        // that family -- which is the entire problem. The o-series row is the one that needs lowestReasoningEffort.
        final InMemoryModelCapabilityRegistry stock = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(transcribe(stock.resolve("gpt-5-chat-latest")).capabilities())
                .isEqualTo(stock.resolve("gpt-5-chat-latest"));
        assertThat(transcribe(stock.resolve("gpt-5.6-terra")).capabilities()).isEqualTo(stock.resolve("gpt-5.6-terra"));
        assertThat(transcribe(stock.resolve("o1-x")).capabilities()).isEqualTo(stock.resolve("o1-x"));
        assertThat(transcribe(stock.resolve("o3-mini")).capabilities()).isEqualTo(stock.resolve("o3-mini"));
        assertThat(transcribe(stock.resolve("o4-mini")).capabilities()).isEqualTo(stock.resolve("o4-mini"));
    }

    /** Writes a resolved row out as a declaration would state it -- all five flags, explicitly. */
    private static ModelCapabilityDeclaration transcribe(ModelCapabilities capabilities) {
        return ModelCapabilityDeclaration.builder()
                .supportsSamplingParameters(capabilities.supportsSamplingParameters())
                .supportsReasoningEffort(capabilities.supportsReasoningEffort())
                .supportsToolsWithReasoning(capabilities.supportsToolsWithReasoning())
                .supportsReasoningTraceRoundTrip(capabilities.supportsReasoningTraceRoundTrip())
                .lowestReasoningEffort(capabilities.lowestReasoningEffort()).build();
    }

    @Test
    @DisplayName("a declared name is matched ignoring case, in both directions")
    void declaredNamesFoldCase() {
        // The name is something an operator copies out of a portal. Both halves of the table fold case, so the
        // configuration path has to as well -- otherwise the one line that closes the fail-open gap silently does not.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("Prod-Assistant", SAMPLING_REJECTED_DECLARATION));

        assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
        assertThat(registry.resolve("PROD-ASSISTANT").supportsSamplingParameters()).isFalse();
        assertThat(registry.resolve("Prod-Assistant").supportsSamplingParameters()).isFalse();
    }

    @Test
    @DisplayName("a blank name is refused")
    void aBlankNameIsRefused() {
        final Map<String, ModelCapabilityDeclaration> blank = new LinkedHashMap<>();
        blank.put("  ", SAMPLING_REJECTED_DECLARATION);

        assertThatThrownBy(() -> InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(blank))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model name");
    }

    @Test
    @DisplayName("a padded name is refused, because it would never match anything")
    void aPaddedNameIsRefused() {
        assertThatThrownBy(() -> InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of(" prod-assistant", SAMPLING_REJECTED_DECLARATION)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prod-assistant")
                .hasMessageContaining("whitespace");
    }

    @Test
    @DisplayName("two names differing only in case are refused rather than letting one win")
    void twoNamesDifferingOnlyInCaseAreRefused() {
        final Map<String, ModelCapabilityDeclaration> clashing = new LinkedHashMap<>();
        clashing.put("Prod-Assistant", SAMPLING_REJECTED_DECLARATION);
        clashing.put("prod-assistant", EVERYTHING_ALLOWED_DECLARATION);

        assertThatThrownBy(() -> InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(clashing))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Prod-Assistant")
                .hasMessageContaining("prod-assistant").hasMessageContaining("differ only in case");
    }

    @Test
    @DisplayName("a null declaration is refused as an empty entry, not as a NullPointerException")
    void aNullDeclarationIsRefusedAsAnEmptyEntry() {
        // What an entry with no body binds to, at least under Jackson. Caught here rather than on each surface so the
        // outcome does not depend on which binder produced it, and reported as what the operator actually did.
        final Map<String, ModelCapabilityDeclaration> withNull = new LinkedHashMap<>();
        withNull.put("prod-assistant", null);

        assertThatThrownBy(() -> InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(withNull))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prod-assistant")
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("nothing is registered when one entry is refused")
    void aRefusedEntryLeavesNoHalfAppliedTable() {
        final Map<String, ModelCapabilityDeclaration> mixed = new LinkedHashMap<>();
        mixed.put("prod-assistant", SAMPLING_REJECTED_DECLARATION);
        mixed.put(" padded", SAMPLING_REJECTED_DECLARATION);

        assertThatThrownBy(() -> InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(mixed))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
