package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.ModelCapabilityConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
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
            config.setModel("gpt-4o");

            LlmClient client = factory.create(config);

            assertThat(client).isNotNull().isInstanceOf(OpenAILlmClient.class);
        }

        @Test
        @DisplayName("Should reject an openai provider with no model, naming the yaml key")
        void cliRejectsAnOpenAiProviderWithNoModel() {
            // OpenAIConfig.build() would reject it too, but its message names a builder argument. What the operator
            // has to change is the yaml key, so the message has to name that.
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Model is required for the openai provider")
                    .hasMessageContaining("model: gpt-4o");
        }

        @Test
        @DisplayName("Should handle case-insensitive provider name")
        void shouldHandleCaseInsensitiveProviderName() {
            LlmProviderConfig upperConfig = new LlmProviderConfig();
            upperConfig.setProvider("OpenAI");
            upperConfig.setApiKey("test-api-key");
            upperConfig.setModel("gpt-4o");

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

    @Nested
    @DisplayName("Model Capability Declarations")
    class ModelCapabilityDeclarations {

        private LlmProviderConfig openAi(String model) {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");
            config.setModel(model);
            return config;
        }

        private ModelCapabilityConfig samplingRejected() {
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setSupportsSamplingParameters(false);
            return capabilities;
        }

        @Test
        @DisplayName("Should carry a declaration through to the registry the client is built with")
        void aDeclarationReachesTheRegistry() {
            // The CLI half of the chain. The raw-request assertion the acceptance criterion asks for lives in
            // aimon-llm-openai, whose SDK is not on this module's compile classpath; the seam between the halves is
            // withDefaultsExtendedBy, which both sides call.
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", samplingRejected()));

            ModelCapabilityRegistry registry = factory.openAiConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
            // What the entry did not name stays fail-open, so the deployment is not routed anywhere new.
            assertThat(registry.resolve("prod-assistant").supportsReasoningTraceRoundTrip()).isFalse();
        }

        @Test
        @DisplayName("Should keep the built-in rows answering for the names nobody declared")
        void theBuiltInRowsSurvive() {
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", samplingRejected()));

            ModelCapabilityRegistry registry = factory.openAiConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("gpt-5.6-terra"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
            assertThat(registry.resolve("o3-mini"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("o3-mini"));
        }

        @Test
        @DisplayName("Should leave the shipped registry alone when nothing is declared")
        void noDeclarationsKeepsTheDefaultRegistry() {
            ModelCapabilityRegistry registry = factory.openAiConfig(openAi("gpt-4o")).getModelCapabilityRegistry();

            assertThat(registry.resolve("gpt-5.6-terra"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
        }

        @Test
        @DisplayName("Should match a declared name ignoring case")
        void aDeclaredNameIsMatchedIgnoringCase() {
            // The acceptance criterion, proved where it is actually at risk: Jackson hands the map key over verbatim,
            // so the name reaches the registry with the case an operator copied out of a portal.
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("Prod-Assistant", samplingRejected()));

            ModelCapabilityRegistry registry = factory.openAiConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
            assertThat(registry.resolve("PROD-ASSISTANT").supportsSamplingParameters()).isFalse();
        }

        @Test
        @DisplayName("Should reject a declaration that states nothing, naming the yaml key")
        void rejectsAnEmptyDeclaration() {
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", new ModelCapabilityConfig()));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities.prod-assistant");
        }

        @Test
        @DisplayName("Should reject an entry with no body, naming the yaml key")
        void rejectsAnEntryWithNoBody() {
            // `prod-assistant:` with nothing under it. The core factory refuses it with the same sentence as an empty
            // object, because to the operator they are the same mistake.
            LlmProviderConfig config = openAi("prod-assistant");
            Map<String, ModelCapabilityConfig> declared = new LinkedHashMap<>();
            declared.put("prod-assistant", null);
            config.setModelCapabilities(declared);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("prod-assistant");
        }

        @Test
        @DisplayName("Should reject a padded model name, naming the yaml key")
        void rejectsAPaddedName() {
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of(" prod-assistant", samplingRejected()));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("whitespace");
        }

        private LlmProviderConfig anthropic(String model) {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");
            config.setModel(model);
            return config;
        }

        @Test
        @DisplayName("Should carry a declaration through to the anthropic client's registry")
        void aDeclarationReachesTheAnthropicRegistry() {
            // This branch used to refuse the block by name, because only the OpenAI client read the registry. Both
            // read it now, so what keeps the shared `llm` block honest is that both branches consume it. The refusal
            // test that stood here is deleted rather than inverted: it asserted a message that no longer exists.
            LlmProviderConfig config = anthropic("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", samplingRejected()));

            ModelCapabilityRegistry registry = factory.anthropicConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("prod-assistant").supportsSamplingParameters()).isFalse();
            assertThat(registry.resolve("claude-opus-5").supportsSamplingParameters()).isFalse();
        }

        @Test
        @DisplayName("Should leave the anthropic client on the shipped registry when nothing is declared")
        void noDeclarationsKeepsTheDefaultAnthropicRegistry() {
            ModelCapabilityRegistry registry = factory.anthropicConfig(anthropic("claude-sonnet-5"))
                    .getModelCapabilityRegistry();

            assertThat(registry.resolve("claude-sonnet-5"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("claude-sonnet-5"));
        }

        @Test
        @DisplayName("Should reject a bad declaration under anthropic with the same yaml-key message as under openai")
        void rejectsABadDeclarationUnderAnthropic() {
            // The judgement is the core's and is stated once; both branches only rename the exception after the yaml
            // key. A second message here would be a second thing to keep in step.
            LlmProviderConfig config = anthropic("prod-assistant");
            config.setModelCapabilities(Map.of(" prod-assistant", samplingRejected()));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("whitespace");
        }
    }
}
