package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.config.CliConfigLoader;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.openai.OpenAIConfig;

/**
 * Backlog L-2, the sampling half, on the CLI: {@code llm.openai} carries the four sampling parameters
 * {@code OpenAIConfig} has, {@code llm.anthropic} carries the one {@code AnthropicConfig} has.
 *
 * <p>
 * Every case is read through the real loader rather than a setter, so the binder link — the key an operator writes
 * reaching the vendor config — is what is under test. The starter's twin is {@code AimonSamplingPropertiesTest}.
 */
@DisplayName("llm.<provider> sampling defaults (L-2)")
class LlmClientFactorySamplingTest {

    private final LlmClientFactory factory = new LlmClientFactory();

    @TempDir
    Path dir;

    private LlmProviderConfig load(String yaml) throws IOException {
        final Path configFile = dir.resolve("sampling.yaml");
        Files.writeString(configFile, yaml);
        return new CliConfigLoader().load(configFile.toString()).getLlmConfig();
    }

    private LlmProviderConfig openAi(String block) throws IOException {
        return load("""
                llm:
                  provider: "openai"
                  apiKey: "test-api-key"
                  model: "gpt-4o"
                """ + block);
    }

    private LlmProviderConfig anthropic(String block) throws IOException {
        return load("""
                llm:
                  provider: "anthropic"
                  apiKey: "test-api-key"
                """ + block);
    }

    @Test
    @DisplayName("openai: all four keys reach OpenAIConfig")
    void theFourOpenAiKeysReachTheVendorConfig() throws IOException {
        final OpenAIConfig config = factory.openAiConfig(openAi("""
                  openai:
                    temperature: 0.2
                    topP: 0.9
                    presencePenalty: -0.5
                    frequencyPenalty: 1.5
                """));

        assertThat(config.getTemperature()).contains(0.2);
        assertThat(config.getTopP()).contains(0.9);
        assertThat(config.getPresencePenalty()).contains(-0.5);
        assertThat(config.getFrequencyPenalty()).contains(1.5);
    }

    @Test
    @DisplayName("openai: an unwritten key stays unset, so the client still sends nothing nobody asked for")
    void anUnwrittenOpenAiKeyStaysUnset() throws IOException {
        final OpenAIConfig none = factory.openAiConfig(openAi(""));
        final OpenAIConfig one = factory.openAiConfig(openAi("""
                  openai:
                    temperature: 0
                """));

        assertThat(none.getTemperature()).isEmpty();
        assertThat(none.getTopP()).isEmpty();
        assertThat(none.getPresencePenalty()).isEmpty();
        assertThat(none.getFrequencyPenalty()).isEmpty();
        assertThat(one.getTemperature()).contains(0.0);
        assertThat(one.getTopP()).isEmpty();
    }

    @Test
    @DisplayName("anthropic: temperature reaches AnthropicConfig")
    void anthropicTemperatureReachesTheVendorConfig() throws IOException {
        final AnthropicConfig written = factory.anthropicConfig(anthropic("""
                  anthropic:
                    temperature: 0.3
                """));

        assertThat(written.getTemperature()).contains(0.3);
        assertThat(factory.anthropicConfig(anthropic("")).getTemperature()).isEmpty();
    }

    @Test
    @DisplayName("the same number is in range for one vendor and out of range for the other, and the key is named")
    void eachVendorRefusesByItsOwnRange() throws IOException {
        assertThat(factory.openAiConfig(openAi("""
                  openai:
                    temperature: 1.5
                """)).getTemperature()).contains(1.5);

        final LlmProviderConfig tooHot = anthropic("""
                  anthropic:
                    temperature: 1.5
                """);
        assertThatThrownBy(() -> factory.create(tooHot)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("`llm.anthropic.temperature`").hasMessageContaining("0.0 and 1.0");
    }

    @Test
    @DisplayName("openai: each key out of range is refused naming that key")
    void eachOpenAiKeyIsRefusedByName() throws IOException {
        final String[][] cases = {{"temperature", "2.5", "0.0 and 2.0"}, {"temperature", "-0.1", "0.0 and 2.0"},
                {"topP", "1.5", "0.0 and 1.0"}, {"presencePenalty", "3.0", "-2.0 and 2.0"},
                {"frequencyPenalty", "-3.0", "-2.0 and 2.0"}};
        for (String[] each : cases) {
            final LlmProviderConfig config = openAi("  openai:\n    " + each[0] + ": " + each[1] + "\n");

            assertThatThrownBy(() -> factory.create(config)).as("%s: %s", each[0], each[1])
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("`llm.openai." + each[0] + "`")
                    .hasMessageContaining(each[2]);
        }
    }

    @Test
    @DisplayName("NaN, however it is spelled, fails startup naming the key — it is not in any range")
    void nanIsRefusedByName() {
        // `.nan` is YAML's own spelling; "NaN" in quotes is what a ${VAR} placeholder expands to, and Jackson reads
        // that string as a double. Whichever layer refuses — the loader or the vendor's range — the key is named.
        for (String written : new String[]{".nan", ".NaN", "\"NaN\"", "NaN"}) {
            for (String key : new String[]{"temperature", "topP", "presencePenalty", "frequencyPenalty"}) {
                assertThatThrownBy(() -> factory.create(openAi("  openai:\n    " + key + ": " + written + "\n")))
                        .as("%s: %s", key, written).isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("llm.openai." + key);
            }
            assertThatThrownBy(() -> factory.create(anthropic("  anthropic:\n    temperature: " + written + "\n")))
                    .as("anthropic temperature: %s", written).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic.temperature");
        }
    }

    @Test
    @DisplayName("an infinity, however it is spelled, fails startup naming the key")
    void infinityIsRefusedByName() {
        for (String written : new String[]{".inf", "-.inf", "\"Infinity\"", "\"-Infinity\""}) {
            assertThatThrownBy(() -> factory.create(openAi("  openai:\n    temperature: " + written + "\n")))
                    .as("openai temperature: %s", written).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.openai.temperature");
            assertThatThrownBy(() -> factory.create(anthropic("  anthropic:\n    temperature: " + written + "\n")))
                    .as("anthropic temperature: %s", written).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic.temperature");
        }
    }

    @Test
    @DisplayName("anthropic: a bad temperature beside a valid budget names the temperature, and the reverse")
    void aBadAnthropicTemperatureIsNotBlamedOnTheBudget() throws IOException {
        final LlmProviderConfig badTemperature = anthropic("""
                  anthropic:
                    temperature: 1.5
                    thinkingMode: extended
                    thinkingBudgetTokens: 4000
                """);
        final LlmProviderConfig badBudget = anthropic("""
                  anthropic:
                    temperature: 0.5
                    thinkingMode: extended
                    thinkingBudgetTokens: 512
                """);

        assertThatThrownBy(() -> factory.create(badTemperature)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("`llm.anthropic.temperature`").hasMessageNotContaining("thinkingBudgetTokens");
        assertThatThrownBy(() -> factory.create(badBudget)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("`llm.anthropic.thinkingBudgetTokens`").hasMessageContaining("1024");
    }

    @Test
    @DisplayName("anthropic: a parameter that client never sends from its config is an unknown key")
    void anthropicHasNoKeyForWhatItsClientDoesNotSend() {
        for (String key : new String[]{"topP", "presencePenalty", "frequencyPenalty"}) {
            assertThatThrownBy(() -> anthropic("  anthropic:\n    " + key + ": 0.5\n")).as(key)
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("llm.anthropic." + key);
        }
    }

    @Test
    @DisplayName("a sampling key alone makes its block one the other provider's branch refuses")
    void aSamplingKeyAloneIsRefusedByTheOtherBranch() throws IOException {
        final LlmProviderConfig openAiKeyUnderAnthropic = anthropic("""
                  openai:
                    temperature: 0.2
                """);
        final LlmProviderConfig anthropicKeyUnderOpenAi = openAi("""
                  anthropic:
                    temperature: 0.2
                """);

        assertThatThrownBy(() -> factory.create(openAiKeyUnderAnthropic)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("llm.openai").hasMessageContaining("llm.provider");
        assertThatThrownBy(() -> factory.create(anthropicKeyUnderOpenAi)).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("llm.anthropic").hasMessageContaining("llm.provider");
    }
}
