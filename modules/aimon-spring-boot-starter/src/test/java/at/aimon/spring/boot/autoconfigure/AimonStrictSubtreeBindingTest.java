package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ThinkingDialect;

/**
 * The measurement behind backlog L-1's third road: an unbound property fails startup under three subtrees of
 * {@code aimon.*}, and nothing else about binding changes.
 *
 * <p>
 * The claim has two halves and the second is the one that was unmeasured. That a handler <em>can</em> refuse an
 * unbound element is Boot's own {@code ignoreUnknownFields = false}; what had to be shown is that an advisor — which
 * Boot applies to every {@code @ConfigurationProperties} bind in the application — can be narrowed to a subtree
 * without touching the rest of this tree or anybody else's bean. Each numbered check from the backlog item is a
 * group of tests below.
 *
 * <p>
 * The context registers the properties bean and the binding slice and nothing else, for the reason
 * {@link AimonPropertiesValidationTest} gives: a context this empty either fails for the stated reason or does not
 * fail.
 */
class AimonStrictSubtreeBindingTest {

    private static final String CAPABILITIES = "aimon.llm.model-capabilities.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AimonPropertiesBindingAutoConfiguration.class))
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent");

    // ----------------------------------------------------------------------------------------------------------
    // 1. a misspelled leaf fails startup, and the message names the key
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("1: a misspelled capability flag fails startup and the failure names the key as written")
    void aMisspelledCapabilityFlagFailsNamingTheKey() {
        runner.withPropertyValues(CAPABILITIES + "prod-assistant.supports-sampling-parameter=false")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
                        .hasStackTraceContaining(CAPABILITIES + "prod-assistant.supports-sampling-parameter"));
    }

    @Test
    @DisplayName("1: a misspelled flag beside a correct one still fails — the entry arriving is not what was missing")
    void aMisspelledFlagBesideACorrectOneFails() {
        // The shape the old silence was worst at. With one flag right the entry is created, so nothing downstream
        // could have noticed that the second line did nothing.
        runner.withPropertyValues(CAPABILITIES + "prod-assistant.thinking-dialect=unknown",
                CAPABILITIES + "prod-assistant.supports-sampling-parameter=false")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasStackTraceContaining(CAPABILITIES + "prod-assistant.supports-sampling-parameter"));
    }

    @Test
    @DisplayName("1: a dotted model name written without brackets fails instead of binding nothing")
    void anUnbracketedDottedModelNameFails() {
        // Not a misspelled flag, but the same mechanism and the same old outcome: Boot reads the dot as a segment
        // separator, the entry is keyed `gpt-5`, and `7-x.supports-sampling-parameters` is a property it lacks.
        runner.withPropertyValues(CAPABILITIES + "gpt-5.7-x.supports-sampling-parameters=false")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
                        .hasStackTraceContaining("gpt-5.7-x.supports-sampling-parameters"));
    }

    @Test
    @DisplayName("1: a misspelled key in either vendor block fails startup, naming the key")
    void aMisspelledVendorKeyFailsNamingTheKey() {
        // The second of the three shapes the item says must be measured: a leaf of a nested object with a fixed
        // set of fields, rather than a leaf of a map entry.
        runner.withPropertyValues("aimon.llm.anthropic.thinking-mod=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
                        .hasStackTraceContaining("aimon.llm.anthropic.thinking-mod"));

        runner.withPropertyValues("aimon.llm.openai.reasoning-summarys=auto")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
                        .hasStackTraceContaining("aimon.llm.openai.reasoning-summarys"));
    }

    // ----------------------------------------------------------------------------------------------------------
    // 2. everything correctly spelled still binds
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("2: all eight leaves bind under a bracketed name with dots, and under a plain dashed one")
    void everyCapabilityLeafStillBinds() {
        // lowest-reasoning-effort and accepted-reasoning-efforts are mutually exclusive, so the eight are spread
        // over two entries -- which also puts both key spellings, and both list spellings, through the handler.
        final String dotted = "aimon.llm.model-capabilities[gpt-5.6-terra].";
        final String dashed = CAPABILITIES + "prod-assistant.";
        runner.withPropertyValues(dotted + "supports-sampling-parameters=false",
                dotted + "supports-reasoning-effort=true", dotted + "supports-tools-with-reasoning=false",
                dotted + "supports-reasoning-trace-round-trip=true", dotted + "lowest-reasoning-effort=low",
                dotted + "thinking-dialect=budgeted", dotted + "supports-reasoning-summary=false",
                dashed + "accepted-reasoning-efforts[0]=none", dashed + "accepted-reasoning-efforts[1]=high",
                CAPABILITIES + "other.accepted-reasoning-efforts=none,low").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getModelCapabilities()).containsOnlyKeys("gpt-5.6-terra", "prod-assistant", "other");

                    final AimonProperties.ModelCapabilityProperties entry = llm.getModelCapabilities()
                            .get("gpt-5.6-terra");
                    assertThat(entry.getSupportsSamplingParameters()).isFalse();
                    assertThat(entry.getSupportsReasoningEffort()).isTrue();
                    assertThat(entry.getSupportsToolsWithReasoning()).isFalse();
                    assertThat(entry.getSupportsReasoningTraceRoundTrip()).isTrue();
                    assertThat(entry.getLowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
                    assertThat(entry.getThinkingDialect()).isEqualTo(ThinkingDialect.BUDGETED);
                    assertThat(entry.getSupportsReasoningSummary()).isFalse();
                    assertThat(llm.getModelCapabilities().get("prod-assistant").getAcceptedReasoningEfforts())
                            .containsExactly(ReasoningEffort.NONE, ReasoningEffort.HIGH);
                    assertThat(llm.getModelCapabilities().get("other").getAcceptedReasoningEfforts())
                            .containsExactly(ReasoningEffort.NONE, ReasoningEffort.LOW);

                    final ModelCapabilities resolved = AimonProperties.modelCapabilityRegistry(llm)
                            .resolve("gpt-5.6-terra");
                    assertThat(resolved.supportsSamplingParameters()).isFalse();
                    assertThat(resolved.thinkingDialect()).isEqualTo(ThinkingDialect.BUDGETED);
                });
    }

    @Test
    @DisplayName("2: every key of both vendor blocks still binds")
    void everyVendorKeyStillBinds() {
        runner.withPropertyValues("aimon.llm.anthropic.thinking-mode=extended",
                "aimon.llm.anthropic.thinking-budget-tokens=4000", "aimon.llm.anthropic.thinking-display=summarized",
                "aimon.llm.anthropic.replay-thinking-blocks=false", "aimon.llm.openai.reasoning-summary=auto",
                "aimon.llm.openai.responses-api-enabled=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getAnthropic().getThinkingMode()).isEqualTo("extended");
                    assertThat(llm.getAnthropic().getThinkingBudgetTokens()).isEqualTo(4000);
                    assertThat(llm.getAnthropic().getThinkingDisplay()).isEqualTo("summarized");
                    assertThat(llm.getAnthropic().getReplayThinkingBlocks()).isFalse();
                    assertThat(llm.getOpenai().getReasoningSummary()).isEqualTo("auto");
                    assertThat(llm.getOpenai().getResponsesApiEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("2: a camelCase spelling of a real leaf binds, because relaxed binding is what decides 'known'")
    void aRelaxedSpellingIsNotAMisspelling() {
        runner.withPropertyValues(CAPABILITIES + "prod-assistant.supportsSamplingParameters=false",
                "aimon.llm.anthropic.thinkingMode=auto").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getModelCapabilities().get("prod-assistant").getSupportsSamplingParameters())
                            .isFalse();
                    assertThat(llm.getAnthropic().getThinkingMode()).isEqualTo("auto");
                });
    }

    @Test
    @DisplayName("2: a correctly spelled key with an empty value still means 'leave the default', not 'unknown key'")
    void anEmptyValueOnARealKeyIsNotRefused() {
        // Boot converts an empty value to null for anything but a String and reports no success for it, so the stock
        // NoUnboundElementsBindHandler calls the key unbound. Across this tree an empty value restores the default
        // (AimonPropertiesValidationTest.clearingACeilingRestoresTheDefault), and ${SOME_VAR:} is how a deployment
        // arrives at one -- so strictness here must not turn that into a startup failure.
        runner.withPropertyValues("aimon.llm.anthropic.thinking-budget-tokens=",
                "aimon.llm.openai.responses-api-enabled=", CAPABILITIES + "prod-assistant.supports-reasoning-effort=",
                CAPABILITIES + "prod-assistant.supports-sampling-parameters=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getAnthropic().getThinkingBudgetTokens()).isNull();
                    assertThat(llm.getOpenai().getResponsesApiEnabled()).isNull();
                    assertThat(llm.getModelCapabilities().get("prod-assistant").getSupportsReasoningEffort()).isNull();
                });
    }

    // ----------------------------------------------------------------------------------------------------------
    // 3. outside the subtrees nothing changed
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("3: an unknown key anywhere else under aimon.* is still ignored")
    void unknownKeysOutsideTheSubtreesAreStillIgnored() {
        // The third key is the third shape the item names -- a scalar leaf directly on aimon.llm -- and the reason
        // this is a prefix list rather than the whole tree: the only prefix that covers it is aimon.llm itself.
        runner.withPropertyValues("aimon.no-such-section.key=1", "aimon.budget.max-iteration=3",
                "aimon.llm.reasoning-effor=medium",
                "aimon.llm.model-capabilitie.prod.supports-sampling-parameters=false").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties properties = ctx.getBean(AimonProperties.class);
                    assertThat(properties.getLlm().getReasoningEffort()).isNull();
                    assertThat(properties.getLlm().getModelCapabilities()).isEmpty();
                    assertThat(properties.getBudget().getMaxIterations()).isEqualTo(20);
                });
    }

    @Test
    @DisplayName("3: a JVM system property is exempt, as it is under Boot's own ignoreUnknownFields=false")
    void aSystemPropertyIsExempt() {
        // Recorded as a limit, not offered as a feature: -Daimon.llm.anthropic.thinking-mod=auto is as wrong as the
        // yaml line and is still quiet. UnboundElementsSourceFilter leaves both system sources out because they are
        // shared with everything else on the machine.
        runner.withSystemProperties("aimon.llm.anthropic.thinking-mod=auto").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AimonProperties.class).getLlm().getAnthropic().isEmpty()).isTrue();
        });
    }

    @Test
    @DisplayName("3: the kill switch still starts from nothing, misspellings included")
    void theKillSwitchSkipsTheCheck() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AimonPropertiesBindingAutoConfiguration.class))
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("aimon.enabled=false", "aimon.llm.anthropic.thinking-mod=auto")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // ----------------------------------------------------------------------------------------------------------
    // 4. a host application's own @ConfigurationProperties beans are not touched
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("4: host beans — foreign prefix, under aimon., and under aimon.llm — bind as before, unknown keys and all")
    void hostConfigurationPropertiesAreUnaffected() {
        runner.withUserConfiguration(HostBeans.class)
                .withPropertyValues("host.app.region=eu", "host.app.no-such-key=1", "aimon.host.region=us",
                        "aimon.host.no-such-key=1", "aimon.llm.gateway-region=ap",
                        CAPABILITIES + "prod-assistant.supports-sampling-parameters=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(HostAppProperties.class).getRegion()).isEqualTo("eu");
                    assertThat(ctx.getBean(HostUnderAimonProperties.class).getRegion()).isEqualTo("us");
                    // A host bean sharing aimon.llm: its bind walks past the strict subtrees' real keys without
                    // having properties for them, and is not asked to account for them.
                    assertThat(ctx.getBean(HostUnderAimonLlmProperties.class).getGatewayRegion()).isEqualTo("ap");
                    assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities())
                            .containsOnlyKeys("prod-assistant");
                });
    }

    @Test
    @DisplayName("4: a host bean that asked for ignoreUnknownFields=false itself still gets exactly that")
    void aHostBeansOwnStrictnessIsUnchanged() {
        runner.withUserConfiguration(StrictHostBean.class).withPropertyValues("host.strict.region=eu")
                .run(ctx -> assertThat(ctx).hasNotFailed());

        runner.withUserConfiguration(StrictHostBean.class)
                .withPropertyValues("host.strict.region=eu", "host.strict.no-such-key=1").run(ctx -> assertThat(ctx)
                        .hasFailed().getFailure().hasStackTraceContaining("host.strict.no-such-key"));
    }

    @Test
    @DisplayName("4: without this slice the old silence is back — the advisor is the whole mechanism")
    void withoutTheSliceNothingIsRefused() {
        // The control. Same properties bean, same typo, no advisor: this is what every assertion above is measured
        // against, and it is also what an application gets by excluding the slice.
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent",
                        CAPABILITIES + "prod-assistant.supports-sampling-parameter=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AimonProperties.class).getLlm().getModelCapabilities()).isEmpty();
                });
    }

    // ----------------------------------------------------------------------------------------------------------
    // the slice is actually shipped
    // ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the starter's own auto-configuration list carries the slice, and the assembled stack refuses the typo")
    void theShippedAutoConfigurationsRefuseTheTypo(@TempDir Path workspace) throws ClassNotFoundException {
        // Every other test names the slice by class, which would stay green if the .imports line were deleted. This
        // one reads the file an application's classpath scan reads.
        final List<Class<?>> shipped = new ArrayList<>();
        for (String candidate : ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())) {
            if (candidate.startsWith("at.aimon.spring.boot.")) {
                shipped.add(Class.forName(candidate));
            }
        }
        assertThat(shipped).contains(AimonPropertiesBindingAutoConfiguration.class);

        final ApplicationContextRunner stack = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(shipped.toArray(Class<?>[]::new)))
                .withPropertyValues("aimon.workspace.root=" + workspace, "aimon.llm.api-key=test-key",
                        "aimon.agent-defaults.default-agent=test-agent");

        stack.withPropertyValues("aimon.llm.anthropic.thinking-mode=off").run(ctx -> assertThat(ctx).hasNotFailed());
        stack.withPropertyValues("aimon.llm.anthropic.thinking-mod=off").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining("aimon.llm.anthropic.thinking-mod"));
    }

    /** The binding target on its own — no slice but the one under test. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AimonProperties.class)
    static class PropertiesOnly {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({HostAppProperties.class, HostUnderAimonProperties.class,
            HostUnderAimonLlmProperties.class})
    static class HostBeans {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StrictHostProperties.class)
    static class StrictHostBean {
    }

    @ConfigurationProperties("host.app")
    static class HostAppProperties {

        private String region;

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    @ConfigurationProperties("aimon.host")
    static class HostUnderAimonProperties {

        private String region;

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }

    @ConfigurationProperties("aimon.llm")
    static class HostUnderAimonLlmProperties {

        private String gatewayRegion;

        public String getGatewayRegion() {
            return gatewayRegion;
        }

        public void setGatewayRegion(String gatewayRegion) {
            this.gatewayRegion = gatewayRegion;
        }
    }

    @ConfigurationProperties(prefix = "host.strict", ignoreUnknownFields = false)
    static class StrictHostProperties {

        private String region;

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }
}
