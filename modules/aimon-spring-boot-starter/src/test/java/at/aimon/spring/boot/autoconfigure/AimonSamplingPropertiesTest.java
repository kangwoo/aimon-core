package at.aimon.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.openai.OpenAIConfig;

/**
 * Backlog L-2, the sampling half: a deployment default for the sampling parameters, under the vendor that sends them.
 *
 * <p>
 * The keys differ per vendor because the clients do. {@code OpenAIConfig} carries all four and its client falls back
 * to each; {@code AnthropicConfig} carries {@code temperature} alone — the Anthropic API has no penalties, and that
 * client reads {@code top_p} from the request's {@code LlmModel} only. So {@code aimon.llm.openai} has four keys and
 * {@code aimon.llm.anthropic} has one, and a key the vendor's client would never send is an unknown key there rather
 * than a quiet one.
 *
 * <p>
 * The ranges differ too, and each vendor config owns its own: these tests assert that the refusal names the
 * <em>property</em>, and that the same number is accepted under one vendor and refused under the other.
 *
 * <p>
 * The context is the properties bean and the binding slice, as in {@link AimonStrictSubtreeBindingTest}; the vendor
 * config is built by calling the slice's own assembly method on what was bound.
 */
@DisplayName("aimon.llm.<provider> sampling defaults (L-2)")
class AimonSamplingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AimonPropertiesBindingAutoConfiguration.class))
            .withUserConfiguration(AimonStrictSubtreeBindingTest.PropertiesOnly.class)
            .withPropertyValues("aimon.workspace.root=/workspace", "aimon.agent-defaults.default-agent=test-agent",
                    "aimon.llm.api-key=test-key");

    private void openAi(Consumer<OpenAIConfig> assertions, String... properties) {
        runner.withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o").withPropertyValues(properties)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertions.accept(AimonLlmAutoConfiguration.OpenAiConfiguration
                            .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()));
                });
    }

    private void anthropic(Consumer<AnthropicConfig> assertions, String... properties) {
        runner.withPropertyValues(properties).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertions.accept(AimonLlmAutoConfiguration.AnthropicConfiguration
                    .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()));
        });
    }

    private void openAiRefused(String property, String value, String... expected) {
        runner.withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o", property + "=" + value)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThatThrownBy(() -> AimonLlmAutoConfiguration.OpenAiConfiguration
                            .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()))
                            .isInstanceOf(IllegalStateException.class).hasMessageStartingWith(property + "=" + value)
                            .hasMessageContainingAll(expected);
                });
    }

    @Test
    @DisplayName("openai: all four keys reach OpenAIConfig")
    void theFourOpenAiKeysReachTheVendorConfig() {
        openAi(config -> {
            assertThat(config.getTemperature()).contains(0.2);
            assertThat(config.getTopP()).contains(0.9);
            assertThat(config.getPresencePenalty()).contains(-0.5);
            assertThat(config.getFrequencyPenalty()).contains(1.5);
        }, "aimon.llm.openai.temperature=0.2", "aimon.llm.openai.top-p=0.9", "aimon.llm.openai.presence-penalty=-0.5",
                "aimon.llm.openai.frequency-penalty=1.5");
    }

    @Test
    @DisplayName("openai: an unwritten key stays unset, so the client still sends nothing nobody asked for")
    void anUnwrittenOpenAiKeyStaysUnset() {
        openAi(config -> {
            assertThat(config.getTemperature()).isEmpty();
            assertThat(config.getTopP()).isEmpty();
            assertThat(config.getPresencePenalty()).isEmpty();
            assertThat(config.getFrequencyPenalty()).isEmpty();
        });
        openAi(config -> {
            assertThat(config.getTemperature()).contains(0.0);
            assertThat(config.getTopP()).isEmpty();
        }, "aimon.llm.openai.temperature=0");
    }

    @Test
    @DisplayName("anthropic: temperature reaches AnthropicConfig")
    void anthropicTemperatureReachesTheVendorConfig() {
        anthropic(config -> assertThat(config.getTemperature()).contains(0.3), "aimon.llm.anthropic.temperature=0.3");
        anthropic(config -> assertThat(config.getTemperature()).isEmpty());
    }

    @Test
    @DisplayName("the same number is in range for one vendor and out of range for the other, and the key is named")
    void eachVendorRefusesByItsOwnRange() {
        // 1.5 is a legal OpenAI temperature and no Anthropic temperature at all. The bound belongs to the vendor
        // config; what this surface adds is which property to fix.
        openAi(config -> assertThat(config.getTemperature()).contains(1.5), "aimon.llm.openai.temperature=1.5");

        runner.withPropertyValues("aimon.llm.anthropic.temperature=1.5").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> AimonLlmAutoConfiguration.AnthropicConfiguration
                    .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("aimon.llm.anthropic.temperature=1.5").hasMessageContaining("0.0 and 1.0");
        });
    }

    @Test
    @DisplayName("openai: each key out of range is refused naming that key")
    void eachOpenAiKeyIsRefusedByName() {
        openAiRefused("aimon.llm.openai.temperature", "2.5", "0.0 and 2.0");
        openAiRefused("aimon.llm.openai.temperature", "-0.1", "0.0 and 2.0");
        openAiRefused("aimon.llm.openai.top-p", "1.5", "0.0 and 1.0");
        openAiRefused("aimon.llm.openai.presence-penalty", "3.0", "-2.0 and 2.0");
        openAiRefused("aimon.llm.openai.frequency-penalty", "-3.0", "-2.0 and 2.0");
    }

    @Test
    @DisplayName("anthropic: a bad temperature beside a valid budget names the temperature, not the budget")
    void aBadAnthropicTemperatureIsNotBlamedOnTheBudget() {
        // The config's build() can now fail for two written keys. Each has to be named for its own fault.
        runner.withPropertyValues("aimon.llm.anthropic.temperature=1.5", "aimon.llm.anthropic.thinking-mode=extended",
                "aimon.llm.anthropic.thinking-budget-tokens=4000").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThatThrownBy(() -> AimonLlmAutoConfiguration.AnthropicConfiguration
                            .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()))
                            .hasMessageStartingWith("aimon.llm.anthropic.temperature=1.5")
                            .hasMessageNotContaining("thinking-budget-tokens");
                });
        runner.withPropertyValues("aimon.llm.anthropic.temperature=0.5", "aimon.llm.anthropic.thinking-mode=extended",
                "aimon.llm.anthropic.thinking-budget-tokens=512").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThatThrownBy(() -> AimonLlmAutoConfiguration.AnthropicConfiguration
                            .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()))
                            .hasMessageStartingWith(AimonProperties.LLM_ANTHROPIC_THINKING_BUDGET_TOKENS)
                            .hasMessageContaining("1024");
                });
    }

    @Test
    @DisplayName("anthropic: a parameter that client never sends from its config is an unknown key, not a quiet one")
    void anthropicHasNoKeyForWhatItsClientDoesNotSend() {
        for (String key : new String[]{"top-p", "presence-penalty", "frequency-penalty"}) {
            runner.withPropertyValues("aimon.llm.anthropic." + key + "=0.5")
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                            .hasRootCauseInstanceOf(UnboundConfigurationPropertiesException.class)
                            .hasStackTraceContaining("aimon.llm.anthropic." + key));
        }
    }

    @Test
    @DisplayName("a sampling key alone makes its block one the other provider's branch refuses")
    void aSamplingKeyAloneIsRefusedByTheOtherBranch() {
        runner.withPropertyValues("aimon.llm.openai.temperature=0.2").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> AimonLlmAutoConfiguration.AnthropicConfiguration
                    .anthropicConfig(ctx.getBean(AimonProperties.class).getLlm()))
                    .hasMessageContaining(AimonProperties.LLM_OPENAI)
                    .hasMessageContaining(AimonProperties.LLM_PROVIDER);
        });
        runner.withPropertyValues("aimon.llm.provider=openai", "aimon.llm.model=gpt-4o",
                "aimon.llm.anthropic.temperature=0.2").run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThatThrownBy(() -> AimonLlmAutoConfiguration.OpenAiConfiguration
                            .openAiConfig(ctx.getBean(AimonProperties.class).getLlm()))
                            .hasMessageContaining(AimonProperties.LLM_ANTHROPIC)
                            .hasMessageContaining(AimonProperties.LLM_PROVIDER);
                });
    }

    @Test
    @DisplayName("the sampling keys bind with the other vendor's module absent")
    void theKeysAreJdkTypes() {
        // A Double on the properties bean loads no vendor class, so writing the key cannot be what breaks a
        // deployment that carries one vendor module -- the reason the selector keys beside these are Strings.
        runner.withClassLoader(new FilteredClassLoader("at.aimon.core.llms.openai"))
                .withPropertyValues("aimon.llm.openai.temperature=0.2", "aimon.llm.anthropic.temperature=0.3")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    final AimonProperties.Llm llm = ctx.getBean(AimonProperties.class).getLlm();
                    assertThat(llm.getOpenai().getTemperature()).isEqualTo(0.2);
                    assertThat(llm.getAnthropic().getTemperature()).isEqualTo(0.3);
                });
    }
}
