package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ThinkingDialect;

/**
 * The mode × dialect table and the reporting contract, asserted on the resolution itself.
 *
 * <p>
 * <strong>Why this class exists next to {@link AnthropicThinkingDialectTest}, which asserts the same table.</strong>
 * That one asserts the serialised request body, which is the surface that cannot lie about what reaches the wire, and
 * it pays for that with a mocked SDK client and a sentinel exception thrown out of {@code create} to stop the call.
 * This one asserts the decision, which is pure and needs neither — so it can say things the body cannot show: that a
 * finding was <em>recorded and then dropped</em> rather than never made, and which of the two scopes each one carries.
 * The two are complementary and the split is deliberate: a change that logs from inside the resolution again would
 * pass every body assertion in the sibling class and fail {@link Contract} here.
 */
@DisplayName("AnthropicThinkingResolver - the mode × dialect table, and which findings survive")
class AnthropicThinkingResolverTest {

    private static final String MODEL = "some-model";

    private static final ModelCapabilities BUDGETED = ModelCapabilities.builder()
            .thinkingDialect(ThinkingDialect.BUDGETED).build();
    private static final ModelCapabilities ADAPTIVE = ModelCapabilities.builder()
            .thinkingDialect(ThinkingDialect.ADAPTIVE).build();
    private static final ModelCapabilities EITHER = ModelCapabilities.builder().thinkingDialect(ThinkingDialect.EITHER)
            .build();
    private static final ModelCapabilities UNKNOWN = ModelCapabilities.unknown();

    private static AnthropicThinkingResolution resolve(AnthropicConfig.Builder config, ModelCapabilities capabilities,
            LlmModel model) {
        return resolve(config, capabilities, model, 16_000);
    }

    private static AnthropicThinkingResolution resolve(AnthropicConfig.Builder config, ModelCapabilities capabilities,
            LlmModel model, int maxTokens) {
        return new AnthropicThinkingResolver(config.build()).resolve(model, maxTokens, capabilities, MODEL);
    }

    private static AnthropicConfig.Builder config(AnthropicThinkingMode mode) {
        return AnthropicConfig.builder().apiKey("test-key").model(MODEL).thinkingMode(mode);
    }

    private static LlmModel effort(ReasoningEffort effort) {
        return LlmModel.builder().reasoningEffort(effort).build();
    }

    private static List<String> signatures(AnthropicThinkingResolution resolution) {
        return resolution.findingsToReport().stream().map(AnthropicThinkingResolution.Finding::signature).toList();
    }

    private static String typeOf(AnthropicThinkingResolution resolution) {
        return resolution.parameter().map(p -> p.isAdaptive() ? "adaptive" : "enabled").orElse("none");
    }

    @Nested
    @DisplayName("§3.3's decision table, one case per row")
    class DecisionTable {

        @Test
        @DisplayName("OFF sends nothing whatever the dialect, and says nothing about it")
        void offIgnoresTheDialect() {
            for (ModelCapabilities capabilities : new ModelCapabilities[]{UNKNOWN, EITHER, BUDGETED, ADAPTIVE}) {
                final AnthropicThinkingResolution resolution = resolve(config(AnthropicThinkingMode.OFF), capabilities,
                        LlmModel.builder().build());

                assertThat(typeOf(resolution)).as("%s", capabilities.thinkingDialect()).isEqualTo("none");
                assertThat(signatures(resolution)).as("%s", capabilities.thinkingDialect()).isEmpty();
            }
        }

        @Test
        @DisplayName("a named mode against an undescribed model is honoured, unreported")
        void unknownHonoursTheNamedMode() {
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.EXTENDED), UNKNOWN, effort(ReasoningEffort.LOW))))
                    .isEqualTo("enabled");
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.ADAPTIVE), UNKNOWN, effort(ReasoningEffort.LOW))))
                    .isEqualTo("adaptive");
            assertThat(
                    signatures(resolve(config(AnthropicThinkingMode.EXTENDED), UNKNOWN, effort(ReasoningEffort.LOW))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a named mode the model agrees with is unchanged and unreported")
        void anAgreeingDialectIsUnchanged() {
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.EXTENDED), BUDGETED, effort(ReasoningEffort.LOW))))
                    .isEqualTo("enabled");
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.ADAPTIVE), ADAPTIVE, effort(ReasoningEffort.LOW))))
                    .isEqualTo("adaptive");
            assertThat(
                    signatures(resolve(config(AnthropicThinkingMode.ADAPTIVE), ADAPTIVE, effort(ReasoningEffort.LOW))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a named mode the model contradicts is translated, in either direction, and reported once")
        void aContradictedModeIsTranslated() {
            final AnthropicThinkingResolution toAdaptive = resolve(config(AnthropicThinkingMode.EXTENDED), ADAPTIVE,
                    effort(ReasoningEffort.LOW));
            assertThat(typeOf(toAdaptive)).isEqualTo("adaptive");
            assertThat(signatures(toAdaptive)).containsExactly("thinkingDialectTranslated=EXTENDED->ADAPTIVE@" + MODEL);

            final AnthropicThinkingResolution toBudgeted = resolve(config(AnthropicThinkingMode.ADAPTIVE), BUDGETED,
                    effort(ReasoningEffort.LOW));
            assertThat(typeOf(toBudgeted)).isEqualTo("enabled");
            assertThat(signatures(toBudgeted)).containsExactly("thinkingDialectTranslated=ADAPTIVE->BUDGETED@" + MODEL);
        }

        @Test
        @DisplayName("EITHER honours whichever mode was named, unreported")
        void eitherHonoursTheNamedMode() {
            final AnthropicThinkingResolution extended = resolve(config(AnthropicThinkingMode.EXTENDED), EITHER,
                    effort(ReasoningEffort.LOW));
            assertThat(typeOf(extended)).isEqualTo("enabled");
            assertThat(signatures(extended)).isEmpty();

            final AnthropicThinkingResolution adaptive = resolve(config(AnthropicThinkingMode.ADAPTIVE), EITHER,
                    effort(ReasoningEffort.LOW));
            assertThat(typeOf(adaptive)).isEqualTo("adaptive");
            assertThat(signatures(adaptive)).isEmpty();
        }

        @Test
        @DisplayName("AUTO sends the model's own dialect, and picks the adaptive one when the row says either")
        void autoReadsTheTable() {
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, effort(ReasoningEffort.LOW))))
                    .isEqualTo("enabled");
            assertThat(typeOf(resolve(config(AnthropicThinkingMode.AUTO), ADAPTIVE, effort(ReasoningEffort.LOW))))
                    .isEqualTo("adaptive");
            // The one policy in the table, and it is the client's rather than the row's.
            final AnthropicThinkingResolution either = resolve(config(AnthropicThinkingMode.AUTO), EITHER,
                    effort(ReasoningEffort.LOW));
            assertThat(typeOf(either)).isEqualTo("adaptive");
            assertThat(signatures(either)).isEmpty();
        }

        @Test
        @DisplayName("AUTO against an undescribed model sends nothing, and that silence is the loud one")
        void autoAgainstUnknownExplainsItself() {
            final AnthropicThinkingResolution resolution = resolve(config(AnthropicThinkingMode.AUTO), UNKNOWN,
                    effort(ReasoningEffort.LOW));

            assertThat(typeOf(resolution)).isEqualTo("none");
            assertThat(signatures(resolution)).containsExactly("thinkingDialectUnknown@" + MODEL);
        }

        @Test
        @DisplayName("EITHER and UNKNOWN are never the dialect a request speaks")
        void theTwoTableValuesNeverReachTheWire() {
            // The invariant ThinkingDialect's javadoc states. Every combination the table has, asserted as "the
            // parameter is one of the two real shapes, or there is none".
            for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
                for (ModelCapabilities capabilities : new ModelCapabilities[]{UNKNOWN, EITHER, BUDGETED, ADAPTIVE}) {
                    final AnthropicThinkingResolution resolution = resolve(config(mode), capabilities,
                            effort(ReasoningEffort.LOW));

                    assertThat(typeOf(resolution)).as("%s × %s", mode, capabilities.thinkingDialect()).isIn("adaptive",
                            "enabled", "none");
                    // output_config belongs to the adaptive shape alone -- a bare output_config.effort is a measured
                    // 400 on two of the three models the census gave a budgeted row.
                    assertThat(resolution.outputConfigEffort().isPresent())
                            .as("%s × %s output_config", mode, capabilities.thinkingDialect())
                            .isEqualTo("adaptive".equals(typeOf(resolution)));
                }
            }
        }
    }

    @Nested
    @DisplayName("§3.1's reporting contract: what survives the finished request")
    class Contract {

        @Test
        @DisplayName("a request that abandons thinking keeps only the finding that explains the absence")
        void abandonedThinkingDropsEveryDescriptionOfIt() {
            // The translation and the display-on-budgeted note are both made, and both describe a request that this
            // resolution goes on to abandon for want of a legal budget.
            final AnthropicThinkingResolution resolution = resolve(
                    config(AnthropicThinkingMode.ADAPTIVE).thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED),
                    BUDGETED, effort(ReasoningEffort.LOW), 512);

            assertThat(typeOf(resolution)).isEqualTo("none");
            assertThat(signatures(resolution)).containsExactly("thinkingBudgetImpossible=512");
        }

        @Test
        @DisplayName("the inert-configuration findings are the ones a thinking-less request keeps")
        void offKeepsTheInertFindings() {
            final AnthropicThinkingResolution resolution = resolve(
                    config(AnthropicThinkingMode.OFF).thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED), ADAPTIVE,
                    effort(ReasoningEffort.HIGH));

            assertThat(signatures(resolution)).containsExactly("reasoningEffortWithThinkingOff=HIGH",
                    "thinkingDisplayWithThinkingOff=SUMMARIZED");
        }

        @Test
        @DisplayName("the budget-beats-effort finding is raised on both dialects by one recorder")
        void theBudgetOverrideIsDialectNeutral() {
            final String signature = "thinkingBudgetOverridesEffort=2000@HIGH";

            assertThat(signatures(resolve(config(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(2000), BUDGETED,
                    effort(ReasoningEffort.HIGH)))).contains(signature);
            // The translated adaptive path: reachable only because a budget is legal under EXTENDED alone, and the
            // path that used to discard the rung in silence.
            assertThat(signatures(resolve(config(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(2000), ADAPTIVE,
                    effort(ReasoningEffort.HIGH)))).contains(signature);
        }

        @Test
        @DisplayName("reasoningEffort NONE with a display set is silent — the pair agrees")
        void noneWithADisplayIsSilent() {
            for (AnthropicThinkingMode mode : new AnthropicThinkingMode[]{AnthropicThinkingMode.ADAPTIVE,
                    AnthropicThinkingMode.EXTENDED, AnthropicThinkingMode.AUTO}) {
                final AnthropicThinkingResolution resolution = resolve(
                        config(mode).thinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED), ADAPTIVE,
                        effort(ReasoningEffort.NONE));

                assertThat(typeOf(resolution)).as("%s", mode).isEqualTo("none");
                assertThat(signatures(resolution)).as("%s", mode).isEmpty();
            }
        }

        @Test
        @DisplayName("a dropped finding is dropped at emission, so nothing about it is remembered")
        void droppingHappensAtEmissionRatherThanAtRecording() {
            // The class has no state at all between calls, which is the structural half of the guarantee: the
            // once-per-signature register lives in the client and only ever sees findingsToReport(). Two identical
            // resolutions therefore answer identically, and a client that deduped while collecting could not.
            final AnthropicThinkingResolver resolver = new AnthropicThinkingResolver(
                    config(AnthropicThinkingMode.ADAPTIVE).build());

            final AnthropicThinkingResolution abandoned = resolver.resolve(effort(ReasoningEffort.LOW), 512, BUDGETED,
                    MODEL);
            final AnthropicThinkingResolution sent = resolver.resolve(effort(ReasoningEffort.LOW), 16_000, BUDGETED,
                    MODEL);

            assertThat(signatures(abandoned)).containsExactly("thinkingBudgetImpossible=512");
            assertThat(signatures(sent)).containsExactly("thinkingDialectTranslated=ADAPTIVE->BUDGETED@" + MODEL);
        }
    }
}
