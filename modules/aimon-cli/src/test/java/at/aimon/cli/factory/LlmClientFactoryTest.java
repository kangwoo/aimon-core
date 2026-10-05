package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import at.aimon.cli.config.AnthropicProviderConfig;
import at.aimon.cli.config.CliConfigLoader;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.ModelCapabilityConfig;
import at.aimon.cli.config.OpenAiProviderConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityRegistry;
import at.aimon.core.llm.capability.ThinkingDialect;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.anthropic.AnthropicThinkingDisplay;
import at.aimon.core.llms.anthropic.AnthropicThinkingMode;
import at.aimon.core.llms.openai.OpenAILlmClient;
import at.aimon.core.llms.openai.OpenAiReasoningSummary;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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

            // One name per built-in SHAPE, not just per row: gpt-5-mini lands on a prefix row and the other two on
            // exact ones. Round 9 gave gpt-5.6-terra an exact row of its own, so without the first line this test
            // would sample no prefix row at all.
            assertThat(registry.resolve("gpt-5-mini"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
            assertThat(registry.resolve("gpt-5.6-terra"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5.6-terra"));
            assertThat(registry.resolve("o3-mini"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("o3-mini"));
        }

        @Test
        @DisplayName("Should leave the shipped registry alone when nothing is declared")
        void noDeclarationsKeepsTheDefaultRegistry() {
            ModelCapabilityRegistry registry = factory.openAiConfig(openAi("gpt-4o")).getModelCapabilityRegistry();

            assertThat(registry.resolve("gpt-5-mini"))
                    .isEqualTo(InMemoryModelCapabilityRegistry.withDefaults().resolve("gpt-5-mini"));
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
        @DisplayName("Should carry a ladder written as a set through to the registry")
        void aLadderWrittenAsASetReachesTheRegistry() {
            // The general ladder form, end to end: yaml List -> ModelCapabilityConfig -> ModelCapabilityDeclaration
            // -> the registry row a client reads. Asserted by resolving the declared name rather than by inspecting
            // the declaration, because the translation from List to Set happens in between.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setAcceptedReasoningEfforts(
                    List.of(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH));
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", capabilities));

            ModelCapabilityRegistry registry = factory.openAiConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("prod-assistant").acceptedReasoningEfforts()).containsExactly(
                    ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH);
        }

        @Test
        @DisplayName("Should reject a declaration stating both ladder keys, naming the yaml key")
        void rejectsBothLadderKeys() {
            // The core makes the judgement; this branch only renames the exception after the yaml key an operator
            // has to go and edit.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setLowestReasoningEffort(ReasoningEffort.LOW);
            capabilities.setAcceptedReasoningEfforts(List.of(ReasoningEffort.NONE, ReasoningEffort.HIGH));
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", capabilities));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("prod-assistant")
                    .hasMessageContaining("acceptedReasoningEfforts");
        }

        @Test
        @DisplayName("Should reject a null rung, naming the yaml key and the position it sits at")
        void rejectsANullRung() {
            // #70. `acceptedReasoningEfforts: [none, ~, high]` used to bind as {NONE, HIGH} and say nothing, and a
            // silently narrowed ladder does not fail -- it makes the client refuse an effort the operator declared,
            // and the symptom turns up later as an omitted parameter rather than as a configuration error.
            // Arrays.asList rather than List.of: the latter throws on a null element, which is the very thing this
            // fixture has to carry.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setAcceptedReasoningEfforts(Arrays.asList(ReasoningEffort.NONE, null, ReasoningEffort.HIGH));
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", capabilities));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("prod-assistant")
                    .hasMessageContaining("acceptedReasoningEfforts[1]").hasMessageContaining("has no value");
        }

        @Test
        @DisplayName("Should reject an empty ladder rather than treating it as undeclared, naming the yaml key")
        void rejectsAnEmptyLadder() {
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setAcceptedReasoningEfforts(List.of());
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", capabilities));

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.modelCapabilities").hasMessageContaining("acceptedReasoningEfforts");
        }

        @Test
        @DisplayName("Should carry a declared thinking dialect through to the registry both branches read")
        void aDeclaredThinkingDialectReachesTheRegistry() {
            // #69, end to end and asserted by resolving rather than by inspecting the declaration. `prod-claude` is
            // a name the built-in table does not describe -- which is what makes the one-key form safe to write here
            // and is exactly the distinction aDeclaredDialectAloneReplacesABuiltInRow below is about.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setThinkingDialect(ThinkingDialect.ADAPTIVE);
            LlmProviderConfig config = anthropic("prod-claude");
            config.setModelCapabilities(Map.of("prod-claude", capabilities));

            assertThat(factory.anthropicConfig(config).getModelCapabilityRegistry().resolve("prod-claude")
                    .thinkingDialect()).isEqualTo(ThinkingDialect.ADAPTIVE);
        }

        @Test
        @DisplayName("Should let the dialect stand alone as a whole declaration, and reach the openai branch too")
        void aDialectAloneIsAWholeDeclarationAndOneTableServesBothBranches() {
            // One table, both branches -- which is what keeps the shared namespace honest (§2.7's own argument, now
            // a fact rather than a prediction). The openai client never reads this flag; it is registered all the
            // same, exactly as supports-tools-with-reasoning is for a model that never reaches Chat Completions.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setThinkingDialect(ThinkingDialect.BUDGETED);
            LlmProviderConfig config = openAi("prod-claude");
            config.setModelCapabilities(Map.of("prod-claude", capabilities));

            assertThat(
                    factory.openAiConfig(config).getModelCapabilityRegistry().resolve("prod-claude").thinkingDialect())
                    .isEqualTo(ThinkingDialect.BUDGETED);
        }

        @Test
        @DisplayName("Should carry a declared reasoning-summary refusal through to the registry")
        void aDeclaredSummaryRefusalReachesTheRegistry() {
            // #72's config half: the per-model lever for a gateway that takes reasoning.effort and 400s on
            // reasoning.summary. The ask itself lives on OpenAIConfig, per client -- this is the only per-model one.
            ModelCapabilityConfig capabilities = new ModelCapabilityConfig();
            capabilities.setSupportsReasoningSummary(false);
            LlmProviderConfig config = openAi("prod-assistant");
            config.setModelCapabilities(Map.of("prod-assistant", capabilities));

            assertThat(factory.openAiConfig(config).getModelCapabilityRegistry().resolve("prod-assistant")
                    .supportsReasoningSummary()).isFalse();
        }

        @Test
        @DisplayName("Should replace, not patch, the built-in row when a described name declares only its dialect")
        void aDeclaredDialectAloneReplacesABuiltInRow() {
            // The trap, asserted so the behaviour is chosen rather than discovered. An entry is the WHOLE row for
            // its name: the built-in claude-* prefix row states two flags, and naming only one hands the other back
            // at its fail-open value -- here supportsSamplingParameters true, for models measured to answer 400 to
            // temperature, and with no divergence WARN because that fires only when the flag is false.
            //
            // The mechanism is #46's and is not changed here. What L-8 added is that it is no longer silent: the
            // registry warns once at construction -- theShadowWarningReachesThisSurface below.
            ModelCapabilityConfig bare = new ModelCapabilityConfig();
            bare.setThinkingDialect(ThinkingDialect.UNKNOWN);
            LlmProviderConfig config = anthropic("claude-sonnet-5");
            config.setModelCapabilities(Map.of("claude-sonnet-5", bare));

            assertThat(factory.anthropicConfig(config).getModelCapabilityRegistry().resolve("claude-sonnet-5")
                    .supportsSamplingParameters()).isTrue();
        }

        @Test
        @DisplayName("Should warn, once, when a declared name shadows a built-in row it does not restate")
        void theShadowWarningReachesThisSurface() {
            // L-8, on this surface. The judgement and the sentence are the core's; what is asserted here is that a
            // yaml-shaped declaration going through this factory reaches the one place that makes them, and that the
            // complete form the reference prints is quiet. A name no other test in this module declares, because
            // the warning is once per process per finding.
            ModelCapabilityConfig bare = new ModelCapabilityConfig();
            bare.setThinkingDialect(ThinkingDialect.UNKNOWN);
            LlmProviderConfig shadowing = anthropic("claude-opus-4-8");
            shadowing.setModelCapabilities(Map.of("claude-opus-4-8", bare));

            ModelCapabilityConfig full = new ModelCapabilityConfig();
            full.setThinkingDialect(ThinkingDialect.UNKNOWN);
            full.setSupportsSamplingParameters(false);
            LlmProviderConfig restating = anthropic("claude-opus-4-8");
            restating.setModelCapabilities(Map.of("claude-opus-4-8", full));

            Logger logger = (Logger) LoggerFactory.getLogger(InMemoryModelCapabilityRegistry.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                factory.anthropicConfig(restating);
                assertThat(appender.list).isEmpty();

                factory.anthropicConfig(shadowing);
                factory.anthropicConfig(shadowing);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("'claude-opus-4-8'")
                    .contains("prefix row 'claude-opus-4-8'").contains("supportsSamplingParameters=false (now true)")
                    .contains("supportsSamplingParameters: false");
        }

        @Test
        @DisplayName("Should keep the suppression when the entry restates it — the form the guides print")
        void theFullFormForADescribedNameKeepsTheSuppression() {
            ModelCapabilityConfig full = new ModelCapabilityConfig();
            full.setThinkingDialect(ThinkingDialect.UNKNOWN);
            full.setSupportsSamplingParameters(false);
            LlmProviderConfig config = anthropic("claude-sonnet-5");
            config.setModelCapabilities(Map.of("claude-sonnet-5", full));

            ModelCapabilityRegistry registry = factory.anthropicConfig(config).getModelCapabilityRegistry();

            assertThat(registry.resolve("claude-sonnet-5").supportsSamplingParameters()).isFalse();
            assertThat(registry.resolve("claude-sonnet-5").thinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
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

    @Nested
    @DisplayName("Anthropic thinking settings")
    class AnthropicThinkingSettings {

        private LlmProviderConfig anthropic() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");
            config.setModel("claude-sonnet-5");
            return config;
        }

        @Test
        @DisplayName("Should leave the request exactly as it was when the block is absent")
        void nothingSetIsTodaysConfig() {
            // The compatibility claim, asserted as one equality rather than three getters: AnthropicConfig.equals
            // covers every value an operator can write (the registry is deliberately excluded from it), so a config
            // built with an empty `anthropic:` block being equal to one built without the block at all says that
            // no setter ran. Criterion 5 of the issue is exactly this sentence.
            LlmProviderConfig withoutBlock = anthropic();
            LlmProviderConfig withEmptyBlock = anthropic();
            withEmptyBlock.setAnthropic(new AnthropicProviderConfig());

            AnthropicConfig built = factory.anthropicConfig(withoutBlock);

            assertThat(built.getThinkingMode()).isEqualTo(AnthropicThinkingMode.OFF);
            assertThat(built.getThinkingBudgetTokens()).isNull();
            assertThat(built.isReplayThinkingBlocks()).isTrue();
            assertThat(factory.anthropicConfig(withEmptyBlock)).isEqualTo(built);
        }

        @ParameterizedTest
        @EnumSource(AnthropicThinkingMode.class)
        @DisplayName("Should bind every thinking mode the vendor enum has")
        void everyModeBinds(AnthropicThinkingMode mode) {
            // Sourced from values() rather than a literal list so that a fifth constant fails here instead of
            // being silently under-covered. Case folding is not exercised here at all: it belongs to the yaml
            // surface, where ThinkingModeDeserializer does it and CliConfigLoaderTest asserts it. This test starts
            // after binding, so all it asks is whether a bound mode reaches the vendor config.
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingMode(mode);
            if (mode == AnthropicThinkingMode.EXTENDED) {
                config.getAnthropic().setThinkingBudgetTokens(2048);
            }

            assertThat(factory.anthropicConfig(config).getThinkingMode()).isEqualTo(mode);
        }

        @Test
        @DisplayName("Should carry a budget through under the one mode that accepts it")
        void budgetReachesTheClient() {
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingMode(AnthropicThinkingMode.EXTENDED);
            config.getAnthropic().setThinkingBudgetTokens(4000);

            assertThat(factory.anthropicConfig(config).getThinkingBudgetTokens()).isEqualTo(4000);
        }

        @Test
        @DisplayName("Should let replay be turned off")
        void replayCanBeTurnedOff() {
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setReplayThinkingBlocks(false);

            assertThat(factory.anthropicConfig(config).isReplayThinkingBlocks()).isFalse();
        }

        @Test
        @DisplayName("Should refuse a budget under auto, naming the yaml block")
        void budgetUnderAutoIsRefused() {
            // The rule is AnthropicConfig's and is stated once there; this surface only adds the key path. Under
            // AUTO the dialect is not known until the request is built, so a number has nothing to mean yet.
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingMode(AnthropicThinkingMode.AUTO);
            config.getAnthropic().setThinkingBudgetTokens(4000);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic").hasMessageContaining("EXTENDED");
        }

        @Test
        @DisplayName("Should refuse a budget written on its own, naming the yaml block")
        void budgetWithoutAModeIsRefused() {
            // The likeliest of the three mistakes: the mode defaults to OFF, so the budget would reach nothing.
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingBudgetTokens(4000);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic").hasMessageContaining("OFF");
        }

        @Test
        @DisplayName("Should refuse a budget below the API's floor, naming the yaml block")
        void aBudgetBelowTheFloorIsRefused() {
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingMode(AnthropicThinkingMode.EXTENDED);
            config.getAnthropic().setThinkingBudgetTokens(512);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic").hasMessageContaining("1024");
        }

        @Test
        @DisplayName("Should refuse an anthropic block under the openai provider, naming both keys")
        void anAnthropicBlockUnderOpenAiIsRefused() {
            // "Configured and never read" is the failure this block's vendor namespace makes unambiguous: nothing
            // about `llm.anthropic` is open to interpretation under `provider: openai`. Refused from inside the
            // branch that runs, so a deployment with its own client is untouched.
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");
            config.setModel("gpt-4o");
            config.getAnthropic().setThinkingMode(AnthropicThinkingMode.AUTO);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.anthropic").hasMessageContaining("llm.provider");
        }

        @Test
        @DisplayName("Should carry a thinking display through to the vendor config")
        void thinkingDisplayReachesTheClient() {
            LlmProviderConfig config = anthropic();
            config.getAnthropic().setThinkingMode(AnthropicThinkingMode.ADAPTIVE);
            config.getAnthropic().setThinkingDisplay(AnthropicThinkingDisplay.SUMMARIZED);

            assertThat(factory.anthropicConfig(config).getThinkingDisplay())
                    .contains(AnthropicThinkingDisplay.SUMMARIZED);
        }

        @Test
        @DisplayName("Should leave the display unset when the key is absent")
        void anAbsentDisplayLeavesTheVendorDefault() {
            // The compatibility claim for the fourth key: no setter call, so no display on the wire and no
            // reasoning text on the stream.
            assertThat(factory.anthropicConfig(anthropic()).getThinkingDisplay()).isEmpty();
        }

        @Test
        @DisplayName("Should leave the openai branch alone when the anthropic block is empty")
        void anEmptyAnthropicBlockDoesNotTripTheOpenAiBranch() {
            // The block binds to an empty instance rather than null, so the refusal has to ask isEmpty() rather
            // than != null -- otherwise every OpenAI deployment fails at boot.
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");
            config.setModel("gpt-4o");

            assertThat(factory.openAiConfig(config).getModel()).isEqualTo("gpt-4o");
        }
    }

    @Nested
    @DisplayName("llm.openai — the vendor block this round opened")
    class OpenAiBlock {

        private LlmProviderConfig openai() {
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("openai");
            config.setApiKey("test-openai-api-key");
            config.setModel("gpt-5.1");
            return config;
        }

        @Test
        @DisplayName("Should leave the vendor default standing when the block is absent or empty")
        void anAbsentBlockChangesNothing() {
            LlmProviderConfig withEmptyBlock = openai();
            withEmptyBlock.setOpenai(new OpenAiProviderConfig());

            assertThat(factory.openAiConfig(openai()).getReasoningSummary()).isEmpty();
            assertThat(factory.openAiConfig(withEmptyBlock).getReasoningSummary()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(OpenAiReasoningSummary.class)
        @DisplayName("Should bind every summary level the vendor enum has")
        void everySummaryLevelBinds(OpenAiReasoningSummary summary) {
            // Sourced from values() rather than a literal list, for the reason the thinking-mode test beside it is:
            // a fourth constant fails here instead of being silently under-covered.
            LlmProviderConfig config = openai();
            config.getOpenai().setReasoningSummary(summary);

            assertThat(factory.openAiConfig(config).getReasoningSummary()).contains(summary);
        }

        @Test
        @DisplayName("Should refuse an openai block under the anthropic provider, naming both keys")
        void anOpenAiBlockUnderAnthropicIsRefused() {
            // The mirror of the anthropic-under-openai refusal, and the reason it can exist at all: until this
            // round there was no `llm.openai` block for the anthropic branch to refuse.
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");
            config.getOpenai().setReasoningSummary(OpenAiReasoningSummary.AUTO);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.openai").hasMessageContaining("llm.provider");
        }

        @Test
        @DisplayName("Should turn the Responses path off from yaml alone (L-2)")
        void yamlTurnsTheResponsesPathOff(@TempDir Path dir) throws IOException {
            // The whole point of L-2, end to end: the 404 a Chat-Completions-only gateway produces is reachable
            // through yaml (baseUrl plus a real gpt-5* name), so its remedy has to be reachable through yaml too.
            // Read through the real loader rather than a setter so the binder link is under test as well.
            final Path configFile = dir.resolve("chat-only-gateway.yaml");
            Files.writeString(configFile, """
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      baseUrl: https://gateway.internal/v1
                      model: "gpt-5.1"
                      openai:
                        responsesApiEnabled: false
                    """);

            final LlmProviderConfig loaded = new CliConfigLoader().load(configFile.toString()).getLlmConfig();

            assertThat(factory.openAiConfig(loaded).isResponsesApiEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should keep OpenAIConfig's own default when responsesApiEnabled is not written")
        void anUnwrittenSwitchKeepsTheVendorDefault() {
            // The field is a nullable Boolean for this: "not written" must not become a second place that knows
            // the default. A block carrying only the other key is the case a primitive would have got wrong.
            final LlmProviderConfig withTheOtherKeyOnly = openai();
            withTheOtherKeyOnly.getOpenai().setReasoningSummary(OpenAiReasoningSummary.AUTO);

            assertThat(factory.openAiConfig(openai()).isResponsesApiEnabled()).isTrue();
            assertThat(factory.openAiConfig(withTheOtherKeyOnly).isResponsesApiEnabled()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should carry responsesApiEnabled through as written")
        void theSwitchBindsBothWays(boolean written) {
            final LlmProviderConfig config = openai();
            config.getOpenai().setResponsesApiEnabled(written);

            assertThat(factory.openAiConfig(config).isResponsesApiEnabled()).isEqualTo(written);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Should refuse a block carrying only responsesApiEnabled under the anthropic provider")
        void theSwitchAloneUnderAnthropicIsRefused(boolean written) {
            // Both values, because `true` restates the default and is still a line nothing reads: isEmpty() asks
            // whether the key was written, not whether it changes anything.
            final LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");
            config.getOpenai().setResponsesApiEnabled(written);

            assertThatThrownBy(() -> factory.create(config)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("llm.openai").hasMessageContaining("llm.provider");
        }

        @Test
        @DisplayName("Should leave the anthropic branch alone when the openai block is empty")
        void anEmptyOpenAiBlockDoesNotTripTheAnthropicBranch() {
            // Same isEmpty() contract as the other direction: the block binds to an empty instance rather than
            // null, so a != null check would fail every Anthropic deployment at boot.
            LlmProviderConfig config = new LlmProviderConfig();
            config.setProvider("anthropic");
            config.setApiKey("test-anthropic-api-key");

            assertThat(factory.anthropicConfig(config).getThinkingMode()).isEqualTo(AnthropicThinkingMode.OFF);
        }
    }

    @Nested
    @DisplayName("llm.reasoningEffort — the shared key")
    class SharedReasoningEffort {

        @Test
        @DisplayName("Should reach BOTH vendor configs, which is what the shared namespace means")
        void theKeyReachesBothBranches() {
            // Written as one test over both branches rather than two tests, because the claim is about the pair:
            // a key in the shared `llm` block that only one branch read would be a lie for half its users, and that
            // is exactly the state llm.anthropic.* exists to keep this key out of.
            LlmProviderConfig openai = new LlmProviderConfig();
            openai.setProvider("openai");
            openai.setApiKey("test-openai-api-key");
            openai.setModel("gpt-5.1");
            openai.setReasoningEffort(ReasoningEffort.HIGH);

            LlmProviderConfig anthropic = new LlmProviderConfig();
            anthropic.setProvider("anthropic");
            anthropic.setApiKey("test-anthropic-api-key");
            anthropic.setModel("claude-sonnet-5");
            anthropic.setReasoningEffort(ReasoningEffort.HIGH);

            assertThat(factory.openAiConfig(openai).getReasoningEffort()).contains(ReasoningEffort.HIGH);
            assertThat(factory.anthropicConfig(anthropic).getReasoningEffort()).contains(ReasoningEffort.HIGH);
        }

        @Test
        @DisplayName("Should leave both vendor configs untouched when the key is absent")
        void anAbsentKeyChangesNeitherBranch() {
            // The compatibility claim, in the shape #54 pinned for its own three keys: a deployment that writes
            // nothing calls no setter, so the vendor default stands and the request is what it was.
            LlmProviderConfig openai = new LlmProviderConfig();
            openai.setProvider("openai");
            openai.setApiKey("test-openai-api-key");
            openai.setModel("gpt-5.1");

            LlmProviderConfig anthropic = new LlmProviderConfig();
            anthropic.setProvider("anthropic");
            anthropic.setApiKey("test-anthropic-api-key");

            assertThat(factory.openAiConfig(openai).getReasoningEffort()).isEmpty();
            assertThat(factory.anthropicConfig(anthropic).getReasoningEffort()).isEmpty();
        }
    }
}
