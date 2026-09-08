package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ModelCapabilities;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;

@DisplayName("OpenAIConfig - Configuration Builder Tests")
class OpenAIConfigTest {

    @Test
    @DisplayName("Should build config with the two required fields only")
    void shouldBuildConfig_WithRequiredFieldsOnly() {
        // Given: the two required fields
        String apiKey = "sk-test-key";

        // When: Building config with nothing else
        OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).model("gpt-4o").build();

        // Then: Should use defaults for the optional fields
        assertThat(config.getApiKey()).isEqualTo(apiKey);
        assertThat(config.getModel()).isEqualTo("gpt-4o");
        // Unset, not 0.0: unset means the parameter is not sent at all, and the distinction is also what lets the
        // client tell "somebody asked for 0.0" from "nobody asked" when deciding whether a suppression is worth a
        // warning.
        assertThat(config.getTemperature()).isEmpty();
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getBaseUrl()).isNull();
    }

    @Test
    @DisplayName("Should reject a config with no model, naming what to set")
    void buildRejectsAConfigWithNoModel() {
        // There is no default model any more: gpt-4 was long superseded, and silently talking to it was worse than
        // refusing to start. The message has to tell the caller what to do, not just what is missing.
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Model is required").hasMessageContaining("OpenAIConfig.builder().model(");

        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").model("  ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Model cannot be blank");
    }

    @Test
    @DisplayName("Should build config with all parameters")
    void shouldBuildConfig_WithAllParameters() {
        // Given: All configuration parameters
        String apiKey = "sk-test-key";
        String model = "gpt-4-turbo";
        double temperature = 0.7;
        int maxTokens = 2048;
        Duration timeout = Duration.ofSeconds(30);
        String baseUrl = "https://api.custom.com";

        // When: Building config with all parameters
        OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).model(model).temperature(temperature)
                .maxTokens(maxTokens).timeout(timeout).baseUrl(baseUrl).build();

        // Then: All fields should match
        assertThat(config.getApiKey()).isEqualTo(apiKey);
        assertThat(config.getModel()).isEqualTo(model);
        assertThat(config.getTemperature()).contains(temperature);
        assertThat(config.getMaxTokens()).isEqualTo(maxTokens);
        assertThat(config.getTimeout()).isEqualTo(timeout);
        assertThat(config.getBaseUrl()).isEqualTo(baseUrl);
    }

    @Test
    @DisplayName("Should throw exception when API key is null")
    void shouldThrowException_WhenApiKeyIsNull() {
        // When/Then: Building without API key should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().build()).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("API key cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when model is null")
    void shouldThrowException_WhenModelIsNull() {
        // When/Then: Setting null model should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").model(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Model cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when timeout is null")
    void shouldThrowException_WhenTimeoutIsNull() {
        // When/Then: Setting null timeout should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").timeout(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Timeout cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too low")
    void shouldThrowException_WhenTemperatureIsTooLow() {
        // When/Then: Temperature below 0.0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").temperature(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 2.0");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too high")
    void shouldThrowException_WhenTemperatureIsTooHigh() {
        // When/Then: Temperature above 2.0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").temperature(2.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 2.0");
    }

    @Test
    @DisplayName("Should accept boundary temperature values")
    void shouldAcceptBoundaryTemperatureValues() {
        // Given: Boundary temperature values
        String apiKey = "test";

        // When/Then: 0.0 and 2.0 should be valid
        OpenAIConfig config1 = OpenAIConfig.builder().model("gpt-4o").apiKey(apiKey).temperature(0.0).build();
        assertThat(config1.getTemperature()).contains(0.0);

        OpenAIConfig config2 = OpenAIConfig.builder().model("gpt-4o").apiKey(apiKey).temperature(2.0).build();
        assertThat(config2.getTemperature()).contains(2.0);
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is zero")
    void shouldThrowException_WhenMaxTokensIsZero() {
        // When/Then: Max tokens = 0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").maxTokens(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is negative")
    void shouldThrowException_WhenMaxTokensIsNegative() {
        // When/Then: Negative max tokens should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").maxTokens(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should accept positive maxTokens")
    void shouldAcceptPositiveMaxTokens() {
        // Given: Positive max tokens
        String apiKey = "test";
        int maxTokens = 8192;

        // When: Building config
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey(apiKey).maxTokens(maxTokens).build();

        // Then: Should accept value
        assertThat(config.getMaxTokens()).isEqualTo(maxTokens);
    }

    @Test
    @DisplayName("Should support builder chaining")
    void shouldSupportBuilderChaining() {
        // Given/When: Chaining builder calls
        OpenAIConfig config = OpenAIConfig.builder().apiKey("test").model("gpt-3.5-turbo").temperature(0.5)
                .maxTokens(1024).timeout(Duration.ofSeconds(45)).baseUrl("https://custom.api.com").build();

        // Then: All values should be set
        assertThat(config).isNotNull();
        assertThat(config.getApiKey()).isEqualTo("test");
        assertThat(config.getModel()).isEqualTo("gpt-3.5-turbo");
        assertThat(config.getTemperature()).contains(0.5);
        assertThat(config.getMaxTokens()).isEqualTo(1024);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.getBaseUrl()).isEqualTo("https://custom.api.com");
    }

    @Test
    @DisplayName("Should handle null baseUrl gracefully")
    void shouldHandleNullBaseUrl() {
        // Given: Config without baseUrl
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test").build();

        // Then: baseUrl should be null (will use default)
        assertThat(config.getBaseUrl()).isNull();
    }

    @Test
    @DisplayName("Should handle empty baseUrl")
    void shouldHandleEmptyBaseUrl() {
        // Given: Config with empty baseUrl
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test").baseUrl("").build();

        // Then: baseUrl should be empty string
        assertThat(config.getBaseUrl()).isEmpty();
    }

    @Test
    @DisplayName("Should throw exception when API key is blank")
    void shouldThrowException_WhenApiKeyIsBlank() {
        // When/Then: Blank API key should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("   ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("API key cannot be blank");
    }

    @Test
    @DisplayName("Should throw exception when API key is empty string")
    void shouldThrowException_WhenApiKeyIsEmpty() {
        // When/Then: Empty API key should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("").build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API key cannot be blank");
    }

    @Test
    @DisplayName("Should leave every optional sampling parameter and the reasoning effort unset by default")
    void shouldLeaveOptionalParametersUnsetByDefault() {
        // The whole point of the nullable shape: a model that rejects these by presence must receive nothing, and
        // "nothing" has to be representable before the client can send it.
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test").build();

        assertThat(config.getTemperature()).isEmpty();
        assertThat(config.getTopP()).isEmpty();
        assertThat(config.getPresencePenalty()).isEmpty();
        assertThat(config.getFrequencyPenalty()).isEmpty();
        assertThat(config.getReasoningEffort()).isEmpty();
    }

    @Test
    @DisplayName("Should round-trip the new sampling parameters and the reasoning effort")
    void shouldRoundTripNewParameters() {
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test").topP(0.9).presencePenalty(1.5)
                .frequencyPenalty(-1.5).reasoningEffort(ReasoningEffort.HIGH).build();

        assertThat(config.getTopP()).contains(0.9);
        assertThat(config.getPresencePenalty()).contains(1.5);
        assertThat(config.getFrequencyPenalty()).contains(-1.5);
        assertThat(config.getReasoningEffort()).contains(ReasoningEffort.HIGH);
    }

    @Test
    @DisplayName("Should validate the new sampling parameters only when they are present")
    void shouldValidateNewParametersWhenPresent() {
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").topP(1.1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Top P must be between 0.0 and 1.0");
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").presencePenalty(2.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Presence penalty must be between -2.0 and 2.0");
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").frequencyPenalty(-2.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Frequency penalty must be between -2.0 and 2.0");
    }

    @Test
    @DisplayName("Should default the capability registry to the built-in table, not to an empty one")
    void shouldDefaultToTheBuiltInCapabilityTable() {
        // Asserting non-null would not be enough: ModelCapabilityRegistry.EMPTY is non-null too, and wiring that as
        // the default would leave the gpt-5 bug unfixed for every stock deployment.
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test").build();

        assertThat(config.getModelCapabilityRegistry()).isNotNull();
        assertThat(config.getModelCapabilityRegistry().resolve("gpt-5.6-terra").supportsSamplingParameters()).isFalse();
        assertThat(config.getModelCapabilityRegistry().resolve("gpt-4o")).isEqualTo(ModelCapabilities.unknown());
    }

    @Test
    @DisplayName("Should accept a caller-supplied capability registry and reject a null one")
    void shouldAcceptCallerSuppliedRegistry() {
        OpenAIConfig config = OpenAIConfig.builder().model("gpt-4o").apiKey("test")
                .modelCapabilityRegistry(ModelCapabilityRegistry.EMPTY).build();

        assertThat(config.getModelCapabilityRegistry()).isSameAs(ModelCapabilityRegistry.EMPTY);
        assertThatThrownBy(() -> OpenAIConfig.builder().model("gpt-4o").apiKey("test").modelCapabilityRegistry(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Model capability registry cannot be null");
    }

    @Test
    @DisplayName("the Responses API is enabled by default and can be turned off")
    void responsesApiEnabledDefaultsToTrue() {
        // Default true, because the whole point of the round is that a reasoning model reaches /v1/responses without
        // anyone configuring anything. False is the escape hatch for a gateway that implements only
        // /v1/chat/completions while passing real model names through -- a deployment that works today and that this
        // branch would otherwise 404.
        assertThat(OpenAIConfig.builder().apiKey("k").model("gpt-4o").build().isResponsesApiEnabled()).isTrue();
        assertThat(OpenAIConfig.builder().apiKey("k").model("gpt-4o").responsesApiEnabled(false).build()
                .isResponsesApiEnabled()).isFalse();
    }

}
