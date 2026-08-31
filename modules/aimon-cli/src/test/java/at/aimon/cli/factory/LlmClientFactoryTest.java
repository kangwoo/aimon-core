package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAILlmClient;

@DisplayName("LlmClientFactory Tests")
class LlmClientFactoryTest {

    private LlmClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new LlmClientFactory();
    }

    @Nested
    @DisplayName("Successful Client Creation")
    class SuccessfulClientCreation {

        @Test
        @DisplayName("Should create Anthropic client with valid config")
        void shouldCreateAnthropicClient() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");

            LlmClient client = factory.create(config);

            assertThat(client).isNotNull().isInstanceOf(AnthropicLlmClient.class);
        }

        @Test
        @DisplayName("Should create OpenAI client with valid config")
        void shouldCreateOpenAIClient() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");

            LlmClient client = factory.create(config);

            assertThat(client).isNotNull().isInstanceOf(OpenAILlmClient.class);
        }

        @Test
        @DisplayName("Should handle case-insensitive provider name")
        void shouldHandleCaseInsensitiveProviderName() {
            LlmProviderConfig upperConfig = new LlmProviderConfig();
            upperConfig.setProvider("OpenAI");
            upperConfig.setApiKey("test-api-key");

            LlmClient upperClient = factory.create(upperConfig);

            assertThat(upperClient).isNotNull().isInstanceOf(OpenAILlmClient.class);

            LlmProviderConfig mixedConfig = new LlmProviderConfig();
            mixedConfig.setProvider("ANTHROPIC");
            mixedConfig.setApiKey("test-api-key");

            LlmClient mixedClient = factory.create(mixedConfig);

            assertThat(mixedClient).isNotNull().isInstanceOf(AnthropicLlmClient.class);
        }

        @Test
        @DisplayName("Should create client with optional fields (model, timeout, baseUrl)")
        void shouldCreateClientWithOptionalFields() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-api-key");
            config.setModel("gpt-4");
            config.setTimeout(120);
            config.setBaseUrl("https://custom-api.example.com/v1");

            LlmClient client = factory.create(config);

            assertThat(client).isNotNull().isInstanceOf(OpenAILlmClient.class);
        }
    }

    @Nested
    @DisplayName("Null Config Validation")
    class NullConfigValidation {

        @Test
        @DisplayName("Should throw ConfigurationException for null config")
        void shouldThrowForNullConfig() {
            assertThatThrownBy(() -> factory.create(null)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM provider config cannot be null");
        }
    }

    @Nested
    @DisplayName("Provider Validation")
    class ProviderValidation {

        @Test
        @DisplayName("Should throw ConfigurationException for null provider")
        void shouldThrowForNullProvider() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider(null);
            config.setApiKey("test-api-key");

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM provider is required");
        }

        @Test
        @DisplayName("Should throw ConfigurationException for blank provider")
        void shouldThrowForBlankProvider() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("   ");
            config.setApiKey("test-api-key");

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM provider is required");
        }

        @Test
        @DisplayName("Should throw ConfigurationException for unsupported provider")
        void shouldThrowForUnsupportedProvider() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("unknown-provider");
            config.setApiKey("test-api-key");

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Unsupported LLM provider: unknown-provider");
        }
    }

    @Nested
    @DisplayName("API Key Validation")
    class ApiKeyValidation {

        @Test
        @DisplayName("Should throw ConfigurationException for null API key")
        void shouldThrowForNullApiKey() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey(null);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("API key is required for LLM provider");
        }

        @Test
        @DisplayName("Should throw ConfigurationException for blank API key")
        void shouldThrowForBlankApiKey() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("   ");

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("API key is required for LLM provider");
        }
    }
}
