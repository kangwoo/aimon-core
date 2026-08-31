package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAIConfig - Configuration Builder Tests")
class OpenAIConfigTest {

    @Test
    @DisplayName("Should build config with required API key only")
    void shouldBuildConfig_WithApiKeyOnly() {
        // Given: API key
        String apiKey = "sk-test-key";

        // When: Building config with only API key
        OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).build();

        // Then: Should use defaults for other fields
        assertThat(config.getApiKey()).isEqualTo(apiKey);
        assertThat(config.getModel()).isEqualTo("gpt-4");
        assertThat(config.getTemperature()).isEqualTo(0.0);
        assertThat(config.getMaxTokens()).isEqualTo(4096);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getBaseUrl()).isNull();
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
        assertThat(config.getTemperature()).isEqualTo(temperature);
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
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").timeout(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Timeout cannot be null");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too low")
    void shouldThrowException_WhenTemperatureIsTooLow() {
        // When/Then: Temperature below 0.0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").temperature(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 2.0");
    }

    @Test
    @DisplayName("Should throw exception when temperature is out of range - too high")
    void shouldThrowException_WhenTemperatureIsTooHigh() {
        // When/Then: Temperature above 2.0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").temperature(2.1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Temperature must be between 0.0 and 2.0");
    }

    @Test
    @DisplayName("Should accept boundary temperature values")
    void shouldAcceptBoundaryTemperatureValues() {
        // Given: Boundary temperature values
        String apiKey = "test";

        // When/Then: 0.0 and 2.0 should be valid
        OpenAIConfig config1 = OpenAIConfig.builder().apiKey(apiKey).temperature(0.0).build();
        assertThat(config1.getTemperature()).isEqualTo(0.0);

        OpenAIConfig config2 = OpenAIConfig.builder().apiKey(apiKey).temperature(2.0).build();
        assertThat(config2.getTemperature()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is zero")
    void shouldThrowException_WhenMaxTokensIsZero() {
        // When/Then: Max tokens = 0 should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").maxTokens(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should throw exception when maxTokens is negative")
    void shouldThrowException_WhenMaxTokensIsNegative() {
        // When/Then: Negative max tokens should fail
        assertThatThrownBy(() -> OpenAIConfig.builder().apiKey("test").maxTokens(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Max tokens must be positive");
    }

    @Test
    @DisplayName("Should accept positive maxTokens")
    void shouldAcceptPositiveMaxTokens() {
        // Given: Positive max tokens
        String apiKey = "test";
        int maxTokens = 8192;

        // When: Building config
        OpenAIConfig config = OpenAIConfig.builder().apiKey(apiKey).maxTokens(maxTokens).build();

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
        assertThat(config.getTemperature()).isEqualTo(0.5);
        assertThat(config.getMaxTokens()).isEqualTo(1024);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.getBaseUrl()).isEqualTo("https://custom.api.com");
    }

    @Test
    @DisplayName("Should handle null baseUrl gracefully")
    void shouldHandleNullBaseUrl() {
        // Given: Config without baseUrl
        OpenAIConfig config = OpenAIConfig.builder().apiKey("test").build();

        // Then: baseUrl should be null (will use default)
        assertThat(config.getBaseUrl()).isNull();
    }

    @Test
    @DisplayName("Should handle empty baseUrl")
    void shouldHandleEmptyBaseUrl() {
        // Given: Config with empty baseUrl
        OpenAIConfig config = OpenAIConfig.builder().apiKey("test").baseUrl("").build();

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
}
