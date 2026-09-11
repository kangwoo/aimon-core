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

    private static long budgetOf(AnthropicThinkingResolution resolution) {
        return resolution.parameter().orElseThrow().asEnabled().budgetTokens();
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

    /**
     * Which requests {@code thinkingBudgetClamped} covers, asserted in both directions.
     *
     * <p>
     * #89, and a decision rather than an oversight: a budget that fits under {@code max_tokens} by one token leaves the
     * answer as little as a clamp does and is sent without a finding.
     * {@code docs/design/llm/thinking-reporting-and-dialect-records.md} §16.8 states the three conditions under which
     * the finding is recorded and why nothing else is covered. A floor or proportion warning added without revisiting
     * that section turns this class red, which is the point of it.
     */
    @Nested
    @DisplayName("§16.8: the clamp warning covers a clamp, not a small answer allowance")
    class ClampWarningCoverage {

        private static final String HEADROOM = "a finding here is a headroom policy; read §16.8 first";

        @Test
        @DisplayName("#89's table: AUTO with no effort is warned about at maxTokens 4096 and not at 4097 or 4100")
        void theIssuesTable() {
            final LlmModel noEffort = LlmModel.builder().build();

            final AnthropicThinkingResolution clamped = resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, noEffort,
                    4096);
            assertThat(budgetOf(clamped)).isEqualTo(4095);
            assertThat(signatures(clamped)).containsExactly("thinkingBudgetClamped=4096->4095");

            for (int maxTokens : new int[]{4097, 4100}) {
                final AnthropicThinkingResolution fits = resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, noEffort,
                        maxTokens);
                assertThat(budgetOf(fits)).as("budget at maxTokens %d", maxTokens).isEqualTo(4096);
                assertThat(signatures(fits))
                        .as("maxTokens %d, %d tokens left: %s", maxTokens, maxTokens - 4096, HEADROOM).isEmpty();
            }
        }

        @Test
        @DisplayName("#89's extended case: 8000 under maxTokens 8001 is sent as asked on every row that sends a budget")
        void anExplicitBudgetThatFitsByOneToken() {
            for (ModelCapabilities capabilities : new ModelCapabilities[]{BUDGETED, EITHER, UNKNOWN}) {
                final AnthropicThinkingResolution fits = resolve(
                        config(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(8000), capabilities,
                        LlmModel.builder().build(), 8001);

                assertThat(typeOf(fits)).as("%s", capabilities.thinkingDialect()).isEqualTo("enabled");
                assertThat(budgetOf(fits)).as("%s", capabilities.thinkingDialect()).isEqualTo(8000);
                assertThat(signatures(fits)).as("%s: %s", capabilities.thinkingDialect(), HEADROOM).isEmpty();
            }

            final AnthropicThinkingResolution clamped = resolve(
                    config(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(8000), BUDGETED,
                    LlmModel.builder().build(), 8000);
            assertThat(budgetOf(clamped)).isEqualTo(7999);
            assertThat(signatures(clamped)).containsExactly("thinkingBudgetClamped=8000->7999");
        }

        @Test
        @DisplayName("on every rung the finding is recorded exactly when the requested budget is at least maxTokens")
        void theFindingIsRecordedExactlyWhenTheRequestedBudgetDoesNotFit() {
            // The rungs are derived rather than written out: two of them are labelled arbitrary on
            // AnthropicThinkingBudgets, and this test must not be what makes them hard to change.
            int clamps = 0;
            int fits = 0;
            for (ReasoningEffort rung : new ReasoningEffort[]{null, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM, ReasoningEffort.HIGH}) {
                // No effort is a model that sets none, not reasoningEffort(null): the builder's null handling is not
                // what is under test.
                final LlmModel model = rung == null ? LlmModel.builder().build() : effort(rung);
                final int requested = AnthropicThinkingBudgets.requestedBudget(rung, null);
                for (int maxTokens : new int[]{requested - 1, requested, requested + 1, requested + 4}) {
                    // At or below the floor no budget is sent at all, which is condition 2 and the next test.
                    if (maxTokens <= AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS) {
                        continue;
                    }
                    final AnthropicThinkingResolution resolution = resolve(config(AnthropicThinkingMode.AUTO), BUDGETED,
                            model, maxTokens);
                    final String row = "effort " + rung + ", requested " + requested + ", maxTokens " + maxTokens;

                    if (requested >= maxTokens) {
                        clamps++;
                        assertThat(budgetOf(resolution)).as(row).isEqualTo(maxTokens - 1);
                        assertThat(signatures(resolution)).as(row)
                                .containsExactly("thinkingBudgetClamped=" + requested + "->" + (maxTokens - 1));
                    } else {
                        fits++;
                        assertThat(budgetOf(resolution)).as(row).isEqualTo(requested);
                        assertThat(signatures(resolution)).as("%s: %s", row, HEADROOM).isEmpty();
                    }
                }
            }
            // Both halves have to have run, or the property above holds vacuously.
            assertThat(clamps).as("rows that clamp").isPositive();
            assertThat(fits).as("rows that fit").isPositive();
        }

        @Test
        @DisplayName("conditions 1 and 2: without a budgeted parameter, or without room for one, nothing is clamped")
        void theOtherTwoConditions() {
            // At maxTokens 4096 an unset effort's budget does not fit, so each of these would clamp if it sent a
            // budgeted parameter at all. The positive half first: every path that does send one reaches the clamp.
            final int clamping = 4096;
            final LlmModel noEffort = LlmModel.builder().build();
            final String clamp = "thinkingBudgetClamped=4096->4095";

            assertThat(signatures(resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, noEffort, clamping)))
                    .as("AUTO on a BUDGETED row").contains(clamp);
            for (ModelCapabilities capabilities : new ModelCapabilities[]{BUDGETED, EITHER, UNKNOWN}) {
                assertThat(
                        signatures(resolve(config(AnthropicThinkingMode.EXTENDED), capabilities, noEffort, clamping)))
                        .as("EXTENDED on %s", capabilities.thinkingDialect()).contains(clamp);
            }
            assertThat(signatures(resolve(config(AnthropicThinkingMode.ADAPTIVE), BUDGETED, noEffort, clamping)))
                    .as("ADAPTIVE translated onto a BUDGETED row").contains(clamp);

            // Condition 1 fails: no budgeted parameter, so there is no budget to have clamped.
            assertThat(signatures(resolve(config(AnthropicThinkingMode.OFF), BUDGETED, noEffort, clamping))).as("OFF")
                    .noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            assertThat(signatures(
                    resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, effort(ReasoningEffort.NONE), clamping)))
                    .as("effort NONE").noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            assertThat(signatures(resolve(config(AnthropicThinkingMode.AUTO), ADAPTIVE, noEffort, clamping)))
                    .as("AUTO on an ADAPTIVE row").noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            assertThat(signatures(resolve(config(AnthropicThinkingMode.AUTO), UNKNOWN, noEffort, clamping)))
                    .as("AUTO on an undescribed row").noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            assertThat(signatures(resolve(config(AnthropicThinkingMode.AUTO), EITHER, noEffort, clamping)))
                    .as("AUTO on an EITHER row, which picks adaptive")
                    .noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            assertThat(signatures(resolve(config(AnthropicThinkingMode.EXTENDED), ADAPTIVE, noEffort, clamping)))
                    .as("EXTENDED translated onto an ADAPTIVE row")
                    .noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            for (ModelCapabilities capabilities : new ModelCapabilities[]{ADAPTIVE, EITHER, UNKNOWN}) {
                assertThat(
                        signatures(resolve(config(AnthropicThinkingMode.ADAPTIVE), capabilities, noEffort, clamping)))
                        .as("ADAPTIVE on %s", capabilities.thinkingDialect())
                        .noneMatch(s -> s.startsWith("thinkingBudgetClamped"));
            }

            // Condition 2 fails: at or below the floor no budget fits, and the finding is a different one — on every
            // path the positive half above shows sending a budgeted parameter.
            for (int maxTokens : new int[]{AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS, 512}) {
                assertNoBudgetFits("AUTO on a BUDGETED row",
                        resolve(config(AnthropicThinkingMode.AUTO), BUDGETED, noEffort, maxTokens), maxTokens);
                for (ModelCapabilities capabilities : new ModelCapabilities[]{BUDGETED, EITHER, UNKNOWN}) {
                    assertNoBudgetFits("EXTENDED on " + capabilities.thinkingDialect(),
                            resolve(config(AnthropicThinkingMode.EXTENDED), capabilities, noEffort, maxTokens),
                            maxTokens);
                }
                assertNoBudgetFits("ADAPTIVE translated onto a BUDGETED row",
                        resolve(config(AnthropicThinkingMode.ADAPTIVE), BUDGETED, noEffort, maxTokens), maxTokens);
            }
        }

        // containsExactly, not contains: at or below the floor no thinking parameter is sent, so the translated path's
        // thinkingDialectTranslated finding is dropped at emission and the impossibility is the only one left.
        private void assertNoBudgetFits(String path, AnthropicThinkingResolution none, int maxTokens) {
            assertThat(typeOf(none)).as("%s, maxTokens %d", path, maxTokens).isEqualTo("none");
            assertThat(signatures(none)).as("%s, maxTokens %d", path, maxTokens)
                    .containsExactly("thinkingBudgetImpossible=" + maxTokens);
        }
    }
}
