package at.aimon.core.llm.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
    @DisplayName("the o-series rows start their reasoning ladder at LOW, and the gpt-5 family keeps the default")
    void oSeriesLadderStartsAtLow() {
        // Measured 2026-09-09. Round 8 replaced the evidence for this row without moving it: round 6 quoted
        // "Supported values are: 'low', 'medium', 'high', and 'xhigh'" for o4-mini without recording that it came
        // from /v1/chat/completions, and on /v1/responses the same model answers "Unsupported value: 'minimal' is
        // not supported with the 'o4-mini' model. Supported values are: 'low', 'medium', and 'high'." The two
        // surfaces enumerate different sets; the FLOOR is 'low' on both, which is all this field is about.
        // gpt-5-nano answers "'minimal', 'low', 'medium', and 'high'" on Chat. Without the row, the neutral MINIMAL
        // would translate to the wire value 'minimal' for the o-series and earn a 400.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        final Set<ReasoningEffort> fromLow = EnumSet.range(ReasoningEffort.LOW, ReasoningEffort.HIGH);
        final Set<ReasoningEffort> fromMinimal = EnumSet.range(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH);

        assertThat(registry.resolve("o4-mini").acceptedReasoningEfforts()).isEqualTo(fromLow);
        assertThat(registry.resolve("o3-mini").acceptedReasoningEfforts()).isEqualTo(fromLow);
        assertThat(registry.resolve("o1").acceptedReasoningEfforts()).isEqualTo(fromLow);
        // The family PREFIX row, and the name is chosen for that: gpt-5.6-terra has its own row now and would be
        // asserting something else entirely.
        assertThat(registry.resolve("gpt-5-mini").acceptedReasoningEfforts()).isEqualTo(fromMinimal);
        // A model nobody describes keeps the fail-open ladder, so nothing it was ever sent starts being withheld.
        assertThat(registry.resolve("gpt-4o").acceptedReasoningEfforts()).isEqualTo(fromMinimal);
    }

    @Test
    @DisplayName("gpt-5.6-terra has a row of its own: the family row with a hole in its ladder")
    void terraStatesItsOwnLadder() {
        // Round 8, measured 2026-09-09: all seven rungs were sent individually and terra answered none / low /
        // medium / high / xhigh / max, REJECTING 'minimal' -- which the family prefix asserts it takes. This is the
        // measurement that made the capability a set: no single floor describes a ladder with a hole in the middle.
        // (xhigh and max have no ReasoningEffort constant by that enum's own decision, so the row states four.)
        final ModelCapabilities terra = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra");

        final ModelCapabilities family = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini");

        assertThat(terra.acceptedReasoningEfforts()).containsExactly(ReasoningEffort.NONE, ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH);
        assertThat(family.acceptedReasoningEfforts()).doesNotContain(ReasoningEffort.NONE)
                .contains(ReasoningEffort.MINIMAL);
        // ...and the ladder is the ONLY thing that differs. Asserted against the family row rather than against four
        // literals so that "terra is the gpt-5 row with a different ladder" stays a fact of the table: a later
        // change to the family row that forgets terra fails here instead of leaving two rows quietly disagreeing.
        assertThat(terra.supportsSamplingParameters()).isEqualTo(family.supportsSamplingParameters());
        assertThat(terra.supportsReasoningEffort()).isEqualTo(family.supportsReasoningEffort());
        assertThat(terra.supportsToolsWithReasoning()).isEqualTo(family.supportsToolsWithReasoning());
        assertThat(terra.supportsReasoningTraceRoundTrip()).isEqualTo(family.supportsReasoningTraceRoundTrip());
        assertThat(terra.thinkingDialect()).isEqualTo(family.thinkingDialect());
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
        // A name the family PREFIX still answers for. gpt-5.6-terra used to stand here and now has an exact row,
        // so it would keep this test green while changing its subject.
        final ModelCapabilities gpt5 = InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini");

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
            assertThat(caps.acceptedReasoningEfforts()).as("%s ladder", model)
                    .isEqualTo(EnumSet.range(ReasoningEffort.LOW, ReasoningEffort.HIGH));
        }
    }

    @Test
    @DisplayName("the six Anthropic models that refuse sampling resolve to a row that says so, and their dialect")
    void defaultsDescribeTheAnthropicRefusers() {
        // Measured 2026-09-09 against the account's own /v1/models listing: each of these answers 400 to a
        // non-default temperature and to top_p / top_k at any value. Five prefixes cover six names because
        // claude-fable-5 also matches claude-fable-5-1.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"claude-fable-5-1", "claude-fable-5", "claude-opus-5", "claude-opus-4-8",
                "claude-opus-4-7", "claude-sonnet-5"}) {
            final ModelCapabilities caps = registry.resolve(model);
            assertThat(caps.supportsSamplingParameters()).as("%s sampling", model).isFalse();
            // Two facts, two flags. The dialect is documentation rather than measurement -- the vendor's per-model
            // thinking table quoted in docs/design/llm/anthropic-thinking-traces.md section 2.1 lists every one of
            // these as adaptive-only, rejecting thinking.type=enabled with a 400.
            assertThat(caps.thinkingDialect()).as("%s dialect", model).isEqualTo(ThinkingDialect.ADAPTIVE);
            // The other three stay fail-open. The Anthropic client replays thinking blocks unconditionally and takes
            // no reasoning-effort parameter of the OpenAI shape, so anything else here would be an assertion nothing
            // consumes -- and nothing measured. A copy-paste from the o-series rows that also moved these would ship
            // silently otherwise.
            assertThat(caps.supportsReasoningEffort()).as("%s effort", model).isFalse();
            assertThat(caps.supportsToolsWithReasoning()).as("%s tools+reasoning", model).isTrue();
            assertThat(caps.supportsReasoningTraceRoundTrip()).as("%s replay", model).isFalse();
            assertThat(caps.acceptedReasoningEfforts()).as("%s ladder", model)
                    .isEqualTo(EnumSet.range(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH));
        }
    }

    @Test
    @DisplayName("the 2026-09-10 census: the budgeted dialect is exactly three prefixes over six names")
    void theBudgetedDialectIsStatedByExactlyThreePrefixes() {
        // This test used to assert the opposite -- that NO built-in row stated the budgeted dialect -- and the
        // reason it gave was "those models accept the sampling parameters, so nothing needs a row for them". The
        // conclusion was right and the reason was a different fact from the one that mattered: a row can carry a
        // dialect and suppress nothing, which is what the 2026-09-10 census rows do.
        //
        // The OpenAI rows say nothing about the dialect for a reason that has not changed: no OpenAI path reads it,
        // and this one table is shared, so a value there would be an assertion with no consumer.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"claude-opus-4-5", "claude-opus-4-5-20251101", "claude-sonnet-4-5",
                "claude-sonnet-4-5-20250929", "claude-haiku-4-5", "claude-haiku-4-5-20251001"}) {
            assertThat(registry.resolve(model).thinkingDialect()).as("%s dialect", model)
                    .isEqualTo(ThinkingDialect.BUDGETED);
        }
        // claude-sonnet-4-20250514 is AnthropicConfig's own default model and stays undescribed -- it does not start
        // with claude-sonnet-4-5, which is the collision the prefix comment says was checked rather than assumed.
        for (String model : new String[]{"claude-sonnet-4-20250514", "gpt-5", "gpt-5-chat-latest", "o3", "o4-mini",
                "prod-assistant"}) {
            assertThat(registry.resolve(model).thinkingDialect()).as("%s dialect", model)
                    .isEqualTo(ThinkingDialect.UNKNOWN);
        }
    }

    @Test
    @DisplayName("the 2026-09-10 census: the two models that take either dialect say so, not nothing")
    void theBothDialectModelsStateEither() {
        // Measured 2026-09-10: both request shapes return 200 on these two. Before this row they resolved to
        // UNKNOWN, which means "this table cannot answer" -- a different statement from "either works", and the
        // one a reader would have taken for "nobody has measured it".
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"claude-opus-4-6", "claude-sonnet-4-6"}) {
            assertThat(registry.resolve(model).thinkingDialect()).as("%s dialect", model)
                    .isEqualTo(ThinkingDialect.EITHER);
        }
    }

    @Test
    @DisplayName("the Claude models that accept sampling keep it, even now that rows describe them")
    void theAcceptingClaudeModelsKeepTheirSamplingParameters() {
        // This is the test that goes red if someone registers "claude-opus-4" as a family prefix. All five accept
        // temperature, top_p and top_k with thinking off (measured 2026-09-09), and a family prefix would suppress a
        // parameter they take -- a silent wire change, which is the failure the o-series rows were withheld for.
        //
        // Its INSTRUMENT changed on 2026-09-10 and its intent did not. It used to assert that no row matched these
        // names at all, which was a proxy for the sampling guard while no row could carry a dialect alone. Rows now
        // match, so the guard has to be asserted directly -- deleting this test instead would be the one way the
        // change loses it.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        for (String model : new String[]{"claude-opus-4-5-20251101", "claude-sonnet-4-5-20250929",
                "claude-haiku-4-5-20251001", "claude-sonnet-4-6", "claude-opus-4-6"}) {
            assertThat(registry.resolve(model).supportsSamplingParameters()).as("%s sampling", model).isTrue();
            // The other four flags are the fail-open ones too: the dialect is the whole row.
            assertThat(registry.resolve(model).supportsReasoningEffort()).as("%s effort", model)
                    .isEqualTo(ModelCapabilities.unknown().supportsReasoningEffort());
            assertThat(registry.resolve(model).supportsToolsWithReasoning()).as("%s tools+reasoning", model)
                    .isEqualTo(ModelCapabilities.unknown().supportsToolsWithReasoning());
            assertThat(registry.resolve(model).supportsReasoningTraceRoundTrip()).as("%s replay", model)
                    .isEqualTo(ModelCapabilities.unknown().supportsReasoningTraceRoundTrip());
            assertThat(registry.resolve(model).acceptedReasoningEfforts()).as("%s ladder", model)
                    .isEqualTo(ModelCapabilities.unknown().acceptedReasoningEfforts());
        }
    }

    @Test
    @DisplayName("a dated snapshot of a refuser inherits its prefix, and case is folded")
    void anthropicPrefixesCoverSnapshotsAndCasing() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("claude-opus-5-20260101").supportsSamplingParameters()).isFalse();
        assertThat(registry.resolve("Claude-Opus-5").supportsSamplingParameters()).isFalse();
        assertThat(registry.resolve("claude-opus-5-20260101").thinkingDialect()).isEqualTo(ThinkingDialect.ADAPTIVE);
    }

    @Test
    @DisplayName("the Mythos row ships as one family prefix, and it is the documentation-derived one")
    void theMythosRowIsAFamilyPrefix() {
        // No Mythos model is visible to the account the probes ran on, so this row is the vendor's enumeration rather
        // than a measurement -- carried because all six reachable names that sentence lists matched it exactly. A
        // family prefix asserts only the fact; a guessed "claude-mythos-5-1" would also assert an identifier.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("claude-mythos-5-1").supportsSamplingParameters()).isFalse();
        assertThat(registry.resolve("claude-mythos-preview").supportsSamplingParameters()).isFalse();
        // Preview is the one name the vendor table lists as accepting both dialects. It gets ADAPTIVE like its
        // siblings, because ADAPTIVE is a dialect it does speak -- the row has to name one, and naming the one the
        // whole family shares is what keeps a single prefix honest.
        assertThat(registry.resolve("claude-mythos-5-1").thinkingDialect()).isEqualTo(ThinkingDialect.ADAPTIVE);
        assertThat(registry.resolve("claude-mythos-preview").thinkingDialect()).isEqualTo(ThinkingDialect.ADAPTIVE);
    }

    @Test
    @DisplayName("a declaration beats a built-in Anthropic prefix, exactly as it does an OpenAI one")
    void aDeclarationBeatsAnAnthropicPrefix() {
        // The exact > prefix rule is not per-vendor, and one Anthropic row here is what shows it is shared. A gateway
        // that renamed claude-opus-5 to something the table cannot see is the case this exists for, arriving from the
        // other direction: an operator who measured their own deployment can state that it does accept sampling.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(
                Map.of("claude-opus-5", ModelCapabilityDeclaration.builder().supportsSamplingParameters(true).build()));

        assertThat(registry.resolve("claude-opus-5").supportsSamplingParameters()).isTrue();
        assertThat(registry.resolve("claude-opus-5-20260101").supportsSamplingParameters()).isFalse();
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
            assertThat(caps.acceptedReasoningEfforts()).as("%s ladder", model)
                    .isEqualTo(EnumSet.range(ReasoningEffort.LOW, ReasoningEffort.HIGH));
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
        assertThat(registry.resolve("gpt-5-mini").supportsReasoningEffort()).isTrue();
        assertThat(registry.resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("builderWithDefaults can override a built-in prefix without reordering it")
    void builderWithDefaultsOverridesAPrefixInPlace() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .registerPrefix("gpt-5", EVERYTHING_ALLOWED).build();

        // gpt-5-mini rather than gpt-5.6-terra: the subject here is the LinkedHashMap re-put keeping its position,
        // and terra now has an exact row that would shadow the override and make this assert the opposite thing.
        assertThat(registry.resolve("gpt-5-mini")).isEqualTo(EVERYTHING_ALLOWED);
        // Re-putting an existing key keeps its position in the LinkedHashMap, so gpt-5 cannot jump ahead of the more
        // specific gpt-5-chat and swallow it.
        assertThat(registry.resolve("gpt-5-chat-latest")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("an exact row shadows a prefix override for its own name, and register() takes it back")
    void anExactRowShadowsThePrefixOverrideAndIsItselfOverridable() {
        // The narrowed promise, pinned. Overriding the gpt-5 prefix reaches every gpt-5* name EXCEPT the one with a
        // measured row of its own -- the same shadowing the o-series exact rows produce for o1 and o1-2024-12-17 --
        // and the third assertion is the escape hatch that narrowing now depends on. It is also the call a
        // configured declaration makes through withDefaultsExtendedBy, so an operator reaches it too.
        final InMemoryModelCapabilityRegistry overridden = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .registerPrefix("gpt-5", EVERYTHING_ALLOWED).build();

        assertThat(overridden.resolve("gpt-5.6-terra")).isNotEqualTo(EVERYTHING_ALLOWED);
        assertThat(overridden.resolve("gpt-5.6-terra"))
                .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));

        final InMemoryModelCapabilityRegistry displaced = InMemoryModelCapabilityRegistry.builderWithDefaults()
                .register("gpt-5.6-terra", EVERYTHING_ALLOWED).build();

        assertThat(displaced.resolve("gpt-5.6-terra")).isEqualTo(EVERYTHING_ALLOWED);
    }

    @Test
    @DisplayName("reasoning traces round-trip for the gpt-5 family and for the measured o-series names only")
    void reasoningTraceRoundTripIsSetOnlyWhereItIsTrue() {
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(registry.resolve("gpt-5-mini").supportsReasoningTraceRoundTrip()).isTrue();
        // ...and terra's own row keeps it, which is the flag that would silently move that name off /v1/responses.
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
        // they used to stand for. Round 9 split the gpt-5 pair the same way: gpt-5-mini is the prefix row and
        // gpt-5.6-terra its own exact one. Dropping either pair would leave a built-in row this test no longer
        // watches.
        assertThat(extended.resolve("gpt-5-chat-latest")).isEqualTo(stock.resolve("gpt-5-chat-latest"));
        assertThat(extended.resolve("gpt-5-mini")).isEqualTo(stock.resolve("gpt-5-mini"));
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
        assertThat(InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(Map.of()).resolve("gpt-5-mini"))
                .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
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
    @DisplayName("a configured declaration displaces the built-in gpt-5.6-terra row too")
    void aDeclarationDisplacesTheBuiltInTerraRow() {
        // The escape hatch of the narrowed gpt-5 promise, as an operator reaches it rather than as a Java caller
        // does. Without this the narrowing would hold only in-process, and a gateway that renamed terra could not
        // restate what its own deployment really accepts.
        final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("gpt-5.6-terra", EVERYTHING_ALLOWED_DECLARATION));

        assertThat(registry.resolve("gpt-5.6-terra")).isEqualTo(EVERYTHING_ALLOWED_DECLARATION.capabilities());
    }

    @Test
    @DisplayName("every built-in row can be written as a declaration")
    void theConfigurationSurfaceCanExpressEveryBuiltInRow() {
        // The invariant behind exposing all five flags rather than the two an operator usually needs: if a row of the
        // shipped table could not be transcribed, the surface would be unable to describe a deployment that renamed
        // that family -- which is the entire problem. The o-series row is the one that needs lowestReasoningEffort.
        //
        // The claude-* rows below were NOT transcribable until #69: ADAPTIVE_REFUSING_SAMPLING carries a dialect, and
        // transcribe() had no way to write one, so this invariant was quietly false for six rows from the moment #60
        // put a dialect in that constant. It stayed green because it only covered OpenAI rows.
        final InMemoryModelCapabilityRegistry stock = InMemoryModelCapabilityRegistry.withDefaults();

        assertThat(transcribe(stock.resolve("gpt-5-chat-latest")).capabilities())
                .isEqualTo(stock.resolve("gpt-5-chat-latest"));
        assertThat(transcribe(stock.resolve("gpt-5-mini")).capabilities()).isEqualTo(stock.resolve("gpt-5-mini"));
        // The row that makes this invariant load-bearing rather than decorative: terra's ladder has a gap, so it is
        // transcribable only because acceptedReasoningEfforts exists on the surface beside lowestReasoningEffort.
        assertThat(transcribe(stock.resolve("gpt-5.6-terra")).capabilities()).isEqualTo(stock.resolve("gpt-5.6-terra"));
        assertThat(transcribe(stock.resolve("o1-x")).capabilities()).isEqualTo(stock.resolve("o1-x"));
        assertThat(transcribe(stock.resolve("o3-mini")).capabilities()).isEqualTo(stock.resolve("o3-mini"));
        assertThat(transcribe(stock.resolve("o4-mini")).capabilities()).isEqualTo(stock.resolve("o4-mini"));
        // The rows the dialect key makes expressible. Two of them, because the table states both real dialects.
        assertThat(transcribe(stock.resolve("claude-sonnet-5")).capabilities())
                .isEqualTo(stock.resolve("claude-sonnet-5"));
        assertThat(transcribe(stock.resolve("claude-3-5-sonnet-20241022")).capabilities())
                .isEqualTo(stock.resolve("claude-3-5-sonnet-20241022"));
    }

    /**
     * Writes a resolved row out as a declaration would state it -- every flag, explicitly.
     *
     * <p>
     * The ladder goes out through {@code acceptedReasoningEfforts} rather than {@code lowestReasoningEffort} for one
     * reason: the general form is the only one that can restate every shipped row. Transcribing through the floor
     * would fail on gpt-5.6-terra and pass on everything else, which is precisely the row this invariant exists for.
     */
    private static ModelCapabilityDeclaration transcribe(ModelCapabilities capabilities) {
        return ModelCapabilityDeclaration.builder()
                .supportsSamplingParameters(capabilities.supportsSamplingParameters())
                .supportsReasoningEffort(capabilities.supportsReasoningEffort())
                .supportsToolsWithReasoning(capabilities.supportsToolsWithReasoning())
                .supportsReasoningTraceRoundTrip(capabilities.supportsReasoningTraceRoundTrip())
                .acceptedReasoningEfforts(capabilities.acceptedReasoningEfforts())
                .thinkingDialect(capabilities.thinkingDialect())
                .supportsReasoningSummary(capabilities.supportsReasoningSummary()).build();
    }

    @Test
    @DisplayName("a declaration replaces the built-in row for that name rather than patching it")
    void aDeclarationReplacesRatherThanPatchesABuiltInRow() {
        // A decision pinned, not a fix. withDefaultsExtendedBy registers the declaration's resolved capabilities as
        // an exact entry, and capabilitiesOf answers with an exact hit before it looks at any prefix -- so a flag the
        // entry left unwritten falls back to fail-open rather than to what the built-in row said. The claude-*
        // prefix row states TWO flags, and one of them is a suppression, so this shape hands `temperature` back to a
        // model measured to refuse it: an HTTP 400, and with no divergence WARN, because that warning fires only
        // when supportsSamplingParameters is false.
        //
        // #69 is what makes this reachable from a configuration file for the first time, since thinkingDialect is the
        // first key whose documented target is a name the built-in table always describes. The mechanism is #46's
        // and is not changed here; the general remedy -- warn when a declaration shadows a row it does not restate --
        // is L-8 in docs/backlog/llm-config-surface-open-items.md. This assertion is expected to change only if that
        // mechanism does.
        final ModelCapabilities bare = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("claude-sonnet-5",
                        ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.UNKNOWN).build()))
                .resolve("claude-sonnet-5");

        assertThat(bare.thinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
        assertThat(bare.supportsSamplingParameters()).isTrue();
    }

    @Test
    @DisplayName("the full form the guides prescribe keeps the flag the built-in row stated")
    void theFullFormRestatesTheSuppression() {
        // The other half of the pair above, and the yaml both operator guides now print: an entry for a described
        // name restates every flag that row stated. Pinned here so the guides can be checked against a test rather
        // than against a reading of the registry.
        final ModelCapabilities full = InMemoryModelCapabilityRegistry
                .withDefaultsExtendedBy(Map.of("claude-sonnet-5", ModelCapabilityDeclaration.builder()
                        .thinkingDialect(ThinkingDialect.UNKNOWN).supportsSamplingParameters(false).build()))
                .resolve("claude-sonnet-5");

        assertThat(full.thinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
        assertThat(full.supportsSamplingParameters()).isFalse();
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
