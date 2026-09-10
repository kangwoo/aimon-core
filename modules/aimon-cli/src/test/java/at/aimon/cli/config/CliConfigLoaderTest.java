package at.aimon.cli.config;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ThinkingDialect;
import at.aimon.core.llms.anthropic.AnthropicThinkingDisplay;
import at.aimon.core.llms.anthropic.AnthropicThinkingMode;
import at.aimon.core.llms.openai.OpenAiReasoningSummary;

@DisplayName("CliConfigLoader Tests")
class CliConfigLoaderTest {

    private CliConfigLoader loader;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        loader = new CliConfigLoader(name -> "stub-" + name);
        tempDir = Files.createTempDirectory("config-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // Ignore
                }
            });
        }
    }

    @Nested
    @DisplayName("Default Configuration Loading")
    class DefaultConfigurationLoading {
        @Test
        @DisplayName("Should load default configuration when path is null")
        void testLoadDefaultWithNullPath() {
            CliConfig config = loader.load(null);

            assertThat(config).isNotNull();
            assertThat(config.getLlmConfig()).isNotNull();
            assertThat(config.getAgentConfig()).isNotNull();
            assertThat(config.getCliSettings()).isNotNull();
        }

        @Test
        @DisplayName("Should load default configuration when path is empty")
        void testLoadDefaultWithEmptyPath() {
            CliConfig config = loader.load("");

            assertThat(config).isNotNull();
            assertThat(config.getLlmConfig()).isNotNull();
        }

        @Test
        @DisplayName("Should load default configuration directly")
        void testLoadDefault() {
            CliConfig config = loader.loadDefault();

            assertThat(config).isNotNull();
            assertThat(config.getLlmConfig()).isNotNull();
            assertThat(config.getLlmConfig().getProvider()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("File Loading")
    class FileLoading {
        @Test
        @DisplayName("Should load valid configuration file")
        void testLoadValidConfigFile() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = loader.load(configFile.toString());

            assertThat(config).isNotNull();
            assertThat(config.getLlmConfig().getProvider()).isEqualTo("openai");
            assertThat(config.getLlmConfig().getApiKey()).isEqualTo("test-api-key");
            assertThat(config.getLlmConfig().getModel()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("Should throw exception when file not found")
        void testLoadNonExistentFile() {
            String nonExistentPath = tempDir.resolve("non-existent.yaml").toString();

            assertThatThrownBy(() -> loader.load(nonExistentPath)).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Configuration file not found");
        }

        @Test
        @DisplayName("Should throw exception for invalid YAML syntax")
        void testLoadInvalidYaml() throws IOException {
            String invalidYaml = """
                    llm:
                      provider: "openai
                      apiKey: invalid
                    """;

            Path configFile = tempDir.resolve("invalid.yaml");
            Files.writeString(configFile, invalidYaml);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .satisfies(e -> assertThat(e.getMessage()).matches(msg -> msg.contains("Invalid YAML syntax")
                            || msg.contains("Invalid configuration structure")));
        }

        @Test
        @DisplayName("Should throw exception for invalid configuration structure")
        void testLoadInvalidStructure() throws IOException {
            String invalidStructure = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      timeout: "not-a-number"
                    """;

            Path configFile = tempDir.resolve("invalid-structure.yaml");
            Files.writeString(configFile, invalidStructure);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }
    }

    @Nested
    @DisplayName("Environment Variable Resolution")
    class EnvironmentVariableResolution {
        @Test
        @DisplayName("Should resolve environment variable in API key")
        void testResolveEnvVarInApiKey() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> {
                if ("MY_API_KEY".equals(name)) {
                    return "resolved-api-key";
                }
                return null;
            });

            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "${MY_API_KEY}"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config-with-env.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = envLoader.load(configFile.toString());

            assertThat(config.getLlmConfig().getApiKey()).isEqualTo("resolved-api-key");
        }

        @Test
        @DisplayName("Should resolve environment variable in baseUrl")
        void testResolveEnvVarInBaseUrl() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> {
                if ("API_BASE_URL".equals(name)) {
                    return "https://custom.api.com/v1";
                }
                return null;
            });

            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      baseUrl: "${API_BASE_URL}"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config-baseurl-env.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = envLoader.load(configFile.toString());

            assertThat(config.getLlmConfig().getBaseUrl()).isEqualTo("https://custom.api.com/v1");
        }

        @Test
        @DisplayName("Should resolve environment variable in model")
        void testResolveEnvVarInModel() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> {
                if ("MODEL_NAME".equals(name)) {
                    return "gpt-4-turbo";
                }
                return null;
            });

            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "${MODEL_NAME}"
                    """;

            Path configFile = tempDir.resolve("config-model-env.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = envLoader.load(configFile.toString());

            assertThat(config.getLlmConfig().getModel()).isEqualTo("gpt-4-turbo");
        }

        @Test
        @DisplayName("Should throw exception for unset environment variable")
        void testUnsetEnvVar() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> null);

            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "${MISSING_VAR}"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config-missing-env.yaml");
            Files.writeString(configFile, configContent);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Environment variable not set: MISSING_VAR");
        }

        @Test
        @DisplayName("Should resolve multiple environment variables in same value")
        void testMultipleEnvVarsInSameValue() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> switch (name) {
                case "HOST" -> "api.example.com";
                case "PORT" -> "8080";
                default -> null;
            });

            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      baseUrl: "https://${HOST}:${PORT}/v1"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config-multi-env.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = envLoader.load(configFile.toString());

            assertThat(config.getLlmConfig().getBaseUrl()).isEqualTo("https://api.example.com:8080/v1");
        }
    }

    @Nested
    @DisplayName("Path Expansion")
    class PathExpansion {
        @Test
        @DisplayName("Should expand home directory (~) in path")
        void testExpandHomePath() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            String homeDir = System.getProperty("user.home");
            Path homeConfigDir = Path.of(homeDir, ".aimon-test");
            Files.createDirectories(homeConfigDir);

            try {
                Path configFile = homeConfigDir.resolve("config.yaml");
                Files.writeString(configFile, configContent);

                // Test with ~ path
                String tildeConfig = configFile.toString().replace(homeDir, "~");
                CliConfig config = loader.load(tildeConfig);

                assertThat(config).isNotNull();
                assertThat(config.getLlmConfig().getProvider()).isEqualTo("openai");
            } finally {
                Files.deleteIfExists(homeConfigDir.resolve("config.yaml"));
                Files.deleteIfExists(homeConfigDir);
            }
        }

        @Test
        @DisplayName("Should handle absolute path")
        void testAbsolutePath() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("config.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = loader.load(configFile.toAbsolutePath().toString());

            assertThat(config).isNotNull();
            assertThat(config.getLlmConfig().getProvider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("Should handle relative path")
        void testRelativePath() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            // Create config in current directory
            Path currentDir = Path.of(System.getProperty("user.dir"));
            Path configFile = currentDir.resolve("test-config.yaml");
            Files.writeString(configFile, configContent);

            try {
                CliConfig config = loader.load("test-config.yaml");

                assertThat(config).isNotNull();
                assertThat(config.getLlmConfig().getProvider()).isEqualTo("openai");
            } finally {
                Files.deleteIfExists(configFile);
            }
        }
    }

    @Nested
    @DisplayName("Configuration Validation")
    class ConfigurationValidation {
        @Test
        @DisplayName("Should require LLM configuration")
        void testRequireLlmConfig() throws IOException {
            String configContent = """
                    agent:
                      name: "test-agent"
                    """;

            Path configFile = tempDir.resolve("no-llm.yaml");
            Files.writeString(configFile, configContent);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM configuration is required");
        }

        @Test
        @DisplayName("Should require LLM provider")
        void testRequireLlmProvider() throws IOException {
            String configContent = """
                    llm:
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("no-provider.yaml");
            Files.writeString(configFile, configContent);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM provider is required");
        }

        @Test
        @DisplayName("Should require LLM API key")
        void testRequireLlmApiKey() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("no-apikey.yaml");
            Files.writeString(configFile, configContent);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("LLM API key is required");
        }

        @Test
        @DisplayName("Should create default agent config if missing")
        void testDefaultAgentConfig() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("no-agent.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = loader.load(configFile.toString());

            assertThat(config.getAgentConfig()).isNotNull();
        }

        @Test
        @DisplayName("Should create default CLI settings if missing")
        void testDefaultCliSettings() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-key"
                      model: "gpt-4"
                    """;

            Path configFile = tempDir.resolve("no-cli-settings.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = loader.load(configFile.toString());

            assertThat(config.getCliSettings()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Complete Configuration")
    class CompleteConfiguration {
        @Test
        @DisplayName("Should load complete configuration with all sections")
        void testLoadCompleteConfig() throws IOException {
            String configContent = """
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-4"
                      timeout: 120
                      baseUrl: "https://api.openai.com/v1"
                    agent:
                      name: "my-agent"
                    cli:
                      prompt: ">>> "
                      colorOutput: false
                      showIterations: false
                      showToolCalls: false
                    """;

            Path configFile = tempDir.resolve("complete.yaml");
            Files.writeString(configFile, configContent);

            CliConfig config = loader.load(configFile.toString());

            // Verify LLM config
            assertThat(config.getLlmConfig()).isNotNull();
            assertThat(config.getLlmConfig().getProvider()).isEqualTo("openai");
            assertThat(config.getLlmConfig().getApiKey()).isEqualTo("test-api-key");
            assertThat(config.getLlmConfig().getModel()).isEqualTo("gpt-4");
            assertThat(config.getLlmConfig().getTimeout()).isEqualTo(120);
            assertThat(config.getLlmConfig().getBaseUrl()).isEqualTo("https://api.openai.com/v1");

            // Verify agent config
            assertThat(config.getAgentConfig()).isNotNull();
            assertThat(config.getAgentConfig().getName()).isEqualTo("my-agent");

            // Verify CLI settings
            assertThat(config.getCliSettings()).isNotNull();
            assertThat(config.getCliSettings().getPrompt()).isEqualTo(">>> ");
            assertThat(config.getCliSettings().isColorOutput()).isFalse();
            assertThat(config.getCliSettings().isShowIterations()).isFalse();
            assertThat(config.getCliSettings().isShowToolCalls()).isFalse();
        }
    }

    @Nested
    @DisplayName("llm.reasoningEffort")
    class SharedReasoningEffort {

        private Path write(String body) throws IOException {
            final Path configFile = tempDir.resolve("effort.yaml");
            Files.writeString(configFile, body);
            return configFile;
        }

        private String withEffort(String written) {
            return """
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                      reasoningEffort: %s
                    """.formatted(written);
        }

        @Test
        @DisplayName("Should bind onto the neutral enum, in either case")
        void bindsAndFoldsCase() throws IOException {
            // No custom deserializer, unlike llm.anthropic.thinkingMode. That exception was forced by `off` being a
            // YAML 1.1 boolean; none of these five spellings collides with YAML's boolean or null resolvers, so the
            // mapper's ACCEPT_CASE_INSENSITIVE_ENUMS is the whole of the tolerance.
            for (String written : new String[]{"medium", "MEDIUM", "Medium"}) {
                assertThat(loader.load(write(withEffort(written)).toString()).getLlmConfig().getReasoningEffort())
                        .as("written as %s", written).isEqualTo(ReasoningEffort.MEDIUM);
            }
        }

        @Test
        @DisplayName("Should leave the key null when it is not written")
        void anAbsentKeyIsNull() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getReasoningEffort()).isNull();
        }

        @Test
        @DisplayName("Should reject an unusable value rather than falling back")
        void rejectsAnUnusableValue() throws IOException {
            assertThatThrownBy(() -> loader.load(write(withEffort("mediumish")).toString()))
                    .isInstanceOf(ConfigurationException.class).hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should reject a misspelled key name rather than ignoring it")
        void rejectsAMisspelledKey() throws IOException {
            // FAIL_ON_UNKNOWN_PROPERTIES is on for this mapper, so the CLI gets this for free. The starter cannot --
            // it is the fourth key on the record in docs/backlog/llm-config-surface-open-items.md item L-1.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                      reasoningEffor: medium
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }
    }

    @Nested
    @DisplayName("Model Capability Declarations")
    class ModelCapabilityDeclarations {

        private Path write(String body) throws IOException {
            final Path configFile = tempDir.resolve("capabilities.yaml");
            Files.writeString(configFile, body);
            return configFile;
        }

        @Test
        @DisplayName("Should bind a full declaration under its model name")
        void bindsAFullDeclaration() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          supportsSamplingParameters: false
                          supportsReasoningEffort: true
                          supportsToolsWithReasoning: true
                          supportsReasoningTraceRoundTrip: false
                          supportsReasoningSummary: false
                          thinkingDialect: adaptive
                          lowestReasoningEffort: minimal
                    """);

            ModelCapabilityConfig declared = loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()
                    .get("prod-assistant");

            assertThat(declared).isNotNull();
            assertThat(declared.getSupportsSamplingParameters()).isFalse();
            assertThat(declared.getSupportsReasoningEffort()).isTrue();
            assertThat(declared.getSupportsToolsWithReasoning()).isTrue();
            assertThat(declared.getSupportsReasoningTraceRoundTrip()).isFalse();
            assertThat(declared.getSupportsReasoningSummary()).isFalse();
            assertThat(declared.getThinkingDialect()).isEqualTo(ThinkingDialect.ADAPTIVE);
            assertThat(declared.getLowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        }

        @Test
        @DisplayName("Should bind the thinking dialect in any case, and tell unknown apart from an omitted key")
        void bindsTheThinkingDialect() throws IOException {
            // #69's key. The value an operator writes when a gateway renames a Claude model and `thinkingMode: auto`
            // has nothing to answer with. Case folding comes from ACCEPT_CASE_INSENSITIVE_ENUMS, and is asserted
            // rather than inherited -- this is the second enum that feature now reaches.
            for (String written : new String[]{"adaptive", "ADAPTIVE", "Adaptive"}) {
                Path configFile = write("""
                        llm:
                          provider: "anthropic"
                          apiKey: "test-api-key"
                          model: "prod-claude"
                          modelCapabilities:
                            prod-claude:
                              thinkingDialect: %s
                        """.formatted(written));

                assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities().get("prod-claude")
                        .getThinkingDialect()).as("written as %s", written).isEqualTo(ThinkingDialect.ADAPTIVE);
            }

            // `unknown` is a value, not an absence: it says "act on no built-in row for this name". If it bound the
            // same as an omitted key the escape hatch would not exist.
            Path unknown = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      model: "prod-claude"
                      modelCapabilities:
                        prod-claude:
                          thinkingDialect: unknown
                    """);

            assertThat(loader.load(unknown.toString()).getLlmConfig().getModelCapabilities().get("prod-claude")
                    .getThinkingDialect()).isEqualTo(ThinkingDialect.UNKNOWN);
        }

        @Test
        @DisplayName("Should hand a null element straight through to the factory rather than dropping it here")
        void aNullRungReachesTheBoundList() throws IOException {
            // The reachability half of #70, measured rather than assumed. The loader binds and validates, and
            // neither step looks inside this list -- so `~` arrives as a null element in the bound List and the
            // refusal has to live where the list is folded, which is LlmClientFactory.rungSetOf. That refusal is
            // asserted in LlmClientFactoryTest.rejectsANullRung; this test is why it is asserted there.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          acceptedReasoningEfforts: [none, ~, high]
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities().get("prod-assistant")
                    .getAcceptedReasoningEfforts()).containsExactly(ReasoningEffort.NONE, null, ReasoningEffort.HIGH);
        }

        @Test
        @DisplayName("Should hand a valueless block-sequence entry through as a null element too")
        void aBareDashReachesTheBoundListAsNull() throws IOException {
            // The other spelling of the same mistake -- a commented-out entry that left its dash behind.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          acceptedReasoningEfforts:
                            - none
                            -
                            - high
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities().get("prod-assistant")
                    .getAcceptedReasoningEfforts()).containsExactly(ReasoningEffort.NONE, null, ReasoningEffort.HIGH);
        }

        @Test
        @DisplayName("Should bind the ladder written as a set, for a model whose ladder has a gap")
        void bindsTheLadderAsASet() throws IOException {
            // The general form. gpt-5.6-terra is the shipped row that needs it -- it takes `none` and rejects
            // `minimal` -- and an operator whose gateway renames that model needs to be able to say the same thing.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          acceptedReasoningEfforts: [none, low, medium, high]
                    """);

            ModelCapabilityConfig declared = loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()
                    .get("prod-assistant");

            assertThat(declared.getAcceptedReasoningEfforts()).containsExactly(ReasoningEffort.NONE,
                    ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH);
            assertThat(declared.getLowestReasoningEffort()).isNull();
        }

        @Test
        @DisplayName("Should leave an omitted flag null rather than false")
        void anOmittedFlagIsNull() throws IOException {
            // The difference between "not declared" and "declared false" is the whole reason these fields are boxed:
            // a one-line entry has to change one thing.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          supportsSamplingParameters: false
                    """);

            ModelCapabilityConfig declared = loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()
                    .get("prod-assistant");

            assertThat(declared.getSupportsSamplingParameters()).isFalse();
            assertThat(declared.getSupportsReasoningEffort()).isNull();
            assertThat(declared.getSupportsToolsWithReasoning()).isNull();
            assertThat(declared.getSupportsReasoningTraceRoundTrip()).isNull();
            assertThat(declared.getSupportsReasoningSummary()).isNull();
            assertThat(declared.getThinkingDialect()).isNull();
            assertThat(declared.getLowestReasoningEffort()).isNull();
            assertThat(declared.getAcceptedReasoningEfforts()).isNull();
        }

        @Test
        @DisplayName("Should accept a reasoning effort in either case")
        void reasoningEffortIsCaseInsensitive() throws IOException {
            // One of the four keys the mapper's ACCEPT_CASE_INSENSITIVE_ENUMS reaches -- all four bind the same
            // enum. `llm.anthropic.thinkingMode` binds
            // an enum too and does not inherit this -- its @JsonDeserialize replaces the EnumDeserializer the
            // feature acts on, so that field folds case itself. Both spellings pass here so that this surface
            // feels like the starter's, whose relaxed binding already accepts them.
            for (String written : new String[]{"low", "LOW", "Low"}) {
                Path configFile = write("""
                        llm:
                          provider: "openai"
                          apiKey: "test-api-key"
                          model: "prod-assistant"
                          modelCapabilities:
                            prod-assistant:
                              lowestReasoningEffort: %s
                        """.formatted(written));

                assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()
                        .get("prod-assistant").getLowestReasoningEffort()).isEqualTo(ReasoningEffort.LOW);
            }
        }

        @Test
        @DisplayName("Should reject an unusable reasoning effort value")
        void rejectsAnUnusableReasoningEffort() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          lowestReasoningEffort: lowish
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should reject a misspelled flag name rather than ignoring it")
        void rejectsAMisspelledFlag() throws IOException {
            // FAIL_ON_UNKNOWN_PROPERTIES is on for this mapper, so the CLI gets this for free. The starter cannot --
            // Boot ignores an unknown property, and that asymmetry is documented rather than papered over.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                          supportsSamplingParameter: false
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should resolve environment variables in the model name key")
        void resolvesEnvironmentVariablesInTheKey() throws IOException {
            // `model` is already resolved, so a deployment writing `model: ${MODEL}` would otherwise have no way to
            // describe its own model -- and that failure is the original 400.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "${DEPLOYMENT}"
                      modelCapabilities:
                        ${DEPLOYMENT}:
                          supportsSamplingParameters: false
                    """);

            CliConfig config = loader.load(configFile.toString());

            assertThat(config.getLlmConfig().getModel()).isEqualTo("stub-DEPLOYMENT");
            assertThat(config.getLlmConfig().getModelCapabilities()).containsOnlyKeys("stub-DEPLOYMENT");
        }

        @Test
        @DisplayName("Should refuse two keys that expand to the same model name")
        void refusesTwoKeysThatExpandToTheSameName() throws IOException {
            // The one duplicate shape nothing downstream can see. yaml refuses a repeated key and the registry
            // refuses two names differing only in case, but two *different* keys expanding to one name collide in
            // the map this loader rebuilds -- before either of those guards is reached -- and the later entry would
            // simply replace the earlier one. Silence there is the failure mode this whole key exists to remove.
            CliConfigLoader envLoader = new CliConfigLoader(name -> "prod-assistant");
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        ${PRIMARY}:
                          supportsSamplingParameters: false
                        ${SECONDARY}:
                          supportsReasoningEffort: true
                    """);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("${PRIMARY}").hasMessageContaining("${SECONDARY}")
                    .hasMessageContaining("prod-assistant").hasMessageContaining("llm.modelCapabilities");
        }

        @Test
        @DisplayName("Should keep an entry with an empty body rather than dropping it")
        void anEntryWithNoBodyBindsToNull() throws IOException {
            // Jackson keeps the key and stores null. Recorded here because it is what makes the core factory's null
            // guard load-bearing: without it the translation loop would throw an NPE, which is noisy and says nothing.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "prod-assistant"
                      modelCapabilities:
                        prod-assistant:
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities())
                    .containsOnlyKeys("prod-assistant").containsEntry("prod-assistant", null);
        }

        @Test
        @DisplayName("Should default to an empty map when nothing is declared")
        void noDeclarationsIsAnEmptyMap() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-4o"
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Anthropic thinking block")
    class AnthropicThinkingBlock {

        private Path write(String body) throws IOException {
            final Path configFile = tempDir.resolve("anthropic.yaml");
            Files.writeString(configFile, body);
            return configFile;
        }

        @Test
        @DisplayName("Should bind all four keys")
        void bindsAllFourKeys() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      model: "claude-sonnet-4-5"
                      anthropic:
                        thinkingMode: adaptive
                        thinkingDisplay: summarized
                        replayThinkingBlocks: false
                    """);

            AnthropicProviderConfig anthropic = loader.load(configFile.toString()).getLlmConfig().getAnthropic();

            assertThat(anthropic.getThinkingMode()).isEqualTo(AnthropicThinkingMode.ADAPTIVE);
            assertThat(anthropic.getThinkingDisplay()).isEqualTo(AnthropicThinkingDisplay.SUMMARIZED);
            assertThat(anthropic.getReplayThinkingBlocks()).isFalse();

            // The budget is on the other dialect, so it gets its own file rather than an illegal combination here.
            Path budgeted = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingMode: extended
                        thinkingBudgetTokens: 4000
                    """);
            assertThat(loader.load(budgeted.toString()).getLlmConfig().getAnthropic().getThinkingBudgetTokens())
                    .isEqualTo(4000);
        }

        @Test
        @DisplayName("Should accept every display spelling, in any case")
        void everyDisplaySpellingBinds() throws IOException {
            // Sourced from values() for the same reason the mode test beside it is. This key rides the default enum
            // deserializer rather than a hand-written one — none of its spellings is a YAML boolean — so what these
            // casings exercise is the mapper-wide ACCEPT_CASE_INSENSITIVE_ENUMS.
            for (AnthropicThinkingDisplay display : AnthropicThinkingDisplay.values()) {
                for (String written : new String[]{display.name(), display.name().toLowerCase(java.util.Locale.ROOT)}) {
                    Path configFile = write("""
                            llm:
                              provider: "anthropic"
                              apiKey: "test-api-key"
                              anthropic:
                                thinkingDisplay: %s
                            """.formatted(written));

                    assertThat(loader.load(configFile.toString()).getLlmConfig().getAnthropic().getThinkingDisplay())
                            .as("thinkingDisplay written as `%s`", written).isEqualTo(display);
                }
            }
        }

        @Test
        @DisplayName("Should accept every mode spelling, in any case")
        void everyModeSpellingBinds() throws IOException {
            // Sourced from values() so a fifth constant fails here rather than going untested, and written in three
            // casings because the folding is this key's own. @JsonDeserialize takes the field off the
            // EnumDeserializer path, so the mapper-wide ACCEPT_CASE_INSENSITIVE_ENUMS never applies to it and what
            // these three casings actually exercise is ThinkingModeDeserializer's equalsIgnoreCase.
            for (AnthropicThinkingMode mode : AnthropicThinkingMode.values()) {
                for (String written : new String[]{mode.name(), mode.name().toLowerCase(java.util.Locale.ROOT),
                        mode.name().charAt(0) + mode.name().substring(1).toLowerCase(java.util.Locale.ROOT)}) {
                    Path configFile = write("""
                            llm:
                              provider: "anthropic"
                              apiKey: "test-api-key"
                              anthropic:
                                thinkingMode: %s
                            """.formatted(written));

                    assertThat(loader.load(configFile.toString()).getLlmConfig().getAnthropic().getThinkingMode())
                            .as("thinkingMode written as `%s`", written).isEqualTo(mode);
                }
            }
        }

        @Test
        @DisplayName("Should bind an unquoted off, which YAML reads as a boolean")
        void unquotedOffBinds() throws IOException {
            // The reason AnthropicProviderConfig.ThinkingModeDeserializer exists. `off` is a YAML 1.1 boolean, so
            // the parser hands Jackson a VALUE_FALSE token and the stock enum deserializer refuses it -- one of the
            // four documented spellings would not work as documented. getText() still carries the written scalar.
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingMode: off
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getAnthropic().getThinkingMode())
                    .isEqualTo(AnthropicThinkingMode.OFF);
        }

        @Test
        @DisplayName("Should still refuse the other spellings YAML reads as booleans")
        void otherYamlBooleansAreStillRefused() throws IOException {
            // The deserializer restores the spellings this project documents, not every token YAML types as false.
            // `no` and `false` are not thinking modes and must not quietly become one.
            for (String written : new String[]{"no", "false"}) {
                Path configFile = write("""
                        llm:
                          provider: "anthropic"
                          apiKey: "test-api-key"
                          anthropic:
                            thinkingMode: %s
                        """.formatted(written));

                assertThatThrownBy(() -> loader.load(configFile.toString())).as("thinkingMode written as `%s`", written)
                        .isInstanceOf(ConfigurationException.class)
                        .hasMessageContaining("Invalid configuration structure");
            }
        }

        @Test
        @DisplayName("Should reject an unusable thinking mode rather than falling back")
        void rejectsAnUnusableMode() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingMode: adaptiv
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should reject a misspelled key rather than ignoring it")
        void rejectsAMisspelledKey() throws IOException {
            // FAIL_ON_UNKNOWN_PROPERTIES is on for this mapper. The starter cannot do this -- Boot ignores an
            // unknown property -- and that asymmetry now covers three more keys; it is backlog item L-1.
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingMod: auto
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should leave every key null when the block is absent")
        void anAbsentBlockIsEmpty() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      model: "claude-sonnet-5"
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getAnthropic().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Should bind the openai block, which this round opened")
        void bindsTheOpenAiBlock() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                      openai:
                        reasoningSummary: DeTaIlEd
                    """);

            // Case-insensitively, through the mapper-wide setting: unlike thinkingMode, no spelling of this key's
            // values collides with a YAML boolean, so it needs no deserializer of its own.
            assertThat(loader.load(configFile.toString()).getLlmConfig().getOpenai().getReasoningSummary())
                    .isEqualTo(OpenAiReasoningSummary.DETAILED);
        }

        @Test
        @DisplayName("Should treat an absent or childless openai block as empty")
        void anAbsentOpenAiBlockIsEmpty() throws IOException {
            Path absent = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                    """);
            Path childless = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      model: "gpt-5.1"
                      openai:
                    """);

            // Same contract as the anthropic block: the setter turns yaml's null back into an empty instance, so
            // the anthropic branch's refusal does not fire on a block nobody wrote anything into.
            assertThat(loader.load(absent.toString()).getLlmConfig().getOpenai().isEmpty()).isTrue();
            assertThat(loader.load(childless.toString()).getLlmConfig().getOpenai().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Should treat a block with no children as absent")
        void aChildlessBlockIsEmpty() throws IOException {
            // yaml binds `anthropic:` with nothing under it to null, and the setter turns that back into an empty
            // instance -- otherwise every read of this block would need a null check and the openai branch's
            // refusal would fire on a block nobody wrote anything into.
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getAnthropic().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Should reject a display value the enum does not have")
        void anUnknownDisplayIsRejected() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingDisplay: verbose
                    """);

            assertThatThrownBy(() -> loader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Invalid configuration structure");
        }

        @Test
        @DisplayName("Should expand ${VAR} onto the enum, which binding used to make impossible")
        void environmentVariablesAreExpandedHere() throws IOException {
            // Replaces environmentVariablesAreNotExpandedHere, whose name and comment inverted with #53. Expansion
            // used to run after Jackson had bound and walk named String fields, so a ${VAR} on an enum or an
            // Integer failed at bind time. It now runs on the token stream, before binding, and reaches every
            // scalar -- which is what lets the rule be stated in one sentence.
            CliConfigLoader envLoader = new CliConfigLoader(name -> "adaptive");
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "test-api-key"
                      anthropic:
                        thinkingMode: "${THINKING_MODE}"
                    """);

            assertThat(envLoader.load(configFile.toString()).getLlmConfig().getAnthropic().getThinkingMode())
                    .isEqualTo(AnthropicThinkingMode.ADAPTIVE);
        }
    }

    @Nested
    @DisplayName("${VAR} expansion, everywhere")
    class PlaceholderExpansion {

        private Path write(String body) throws IOException {
            final Path configFile = tempDir.resolve("placeholders.yaml");
            Files.writeString(configFile, body);
            return configFile;
        }

        @Test
        @DisplayName("Should expand the embedding apiKey the shipped default config demonstrates")
        void expandsTheMemoryEmbeddingApiKey() throws IOException {
            // Issue #53's reproduction, verbatim: default-config.yaml has demonstrated `apiKey: "${OPENAI_KEY}"`
            // inside the memory block since the block was written, and the loader never visited it -- so the
            // literal seven characters reached the embedding provider and the 401 arrived half an hour later
            // inside a Quartz job, naming a workspace and no configuration key.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                    memory:
                      workspaceId: "default"
                      peerId: "agent-default"
                      storagePath: ".aimon/memory/representations.jsonl"
                      dreamer:
                        enabled: true
                        scorer:
                          type: embedding
                          embedding:
                            apiKey: "${OPENAI_KEY}"
                    """);

            assertThat(loader.load(configFile.toString()).getMemoryConfig().getDreamer().getScorer().getEmbedding()
                    .getApiKey()).isEqualTo("stub-OPENAI_KEY");
        }

        @Test
        @DisplayName("Should expand a variable embedded in a longer value")
        void expandsInsideALongerValue() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                    memory:
                      workspaceId: "default"
                      peerId: "agent-default"
                      storagePath: "${HOME}/memory/representations.jsonl"
                    """);

            assertThat(loader.load(configFile.toString()).getMemoryConfig().getStoragePath())
                    .isEqualTo("stub-HOME/memory/representations.jsonl");
        }

        @Test
        @DisplayName("Should expand inside an array, which no field list ever reached")
        void expandsInsideAnArray() throws IOException {
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                    mcp:
                      servers:
                        - name: "github"
                          transportType: "STDIO"
                          command: "npx"
                          args: ["-y", "${PACKAGE}"]
                          env:
                            GITHUB_TOKEN: "${GITHUB_TOKEN}"
                    """);

            McpServerEntry entry = loader.load(configFile.toString()).getMcpConfig().getServers().get(0);
            assertThat(entry.getArgs()).containsExactly("-y", "stub-PACKAGE");
            assertThat(entry.getEnv()).containsEntry("GITHUB_TOKEN", "stub-GITHUB_TOKEN");
        }

        @Test
        @DisplayName("Should expand onto a non-String field")
        void expandsOntoAnInteger() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> "45");
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                      timeout: "${TIMEOUT}"
                    """);

            assertThat(envLoader.load(configFile.toString()).getLlmConfig().getTimeout()).isEqualTo(45);
        }

        @Test
        @DisplayName("Should expand a value written without quotes")
        void expandsAnUnquotedValue() throws IOException {
            // A quoted scalar is the case that survives a lossy mechanism; an unquoted one is not. Both forms are
            // exercised because the difference is invisible until the mechanism is wrong.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: ${OPENAI_KEY}
                      model: ${MODEL}
                    memory:
                      workspaceId: "default"
                      peerId: "agent-default"
                      storagePath: ${HOME}/x.jsonl
                    """);

            CliConfig config = loader.load(configFile.toString());
            assertThat(config.getLlmConfig().getApiKey()).isEqualTo("stub-OPENAI_KEY");
            assertThat(config.getLlmConfig().getModel()).isEqualTo("stub-MODEL");
            assertThat(config.getMemoryConfig().getStoragePath()).isEqualTo("stub-HOME/x.jsonl");
        }

        @Test
        @DisplayName("Should name the variable and the key when the variable is not set")
        void anUnsetVariableNamesTheKeyItWasWrittenOn() throws IOException {
            // The general pass makes this failure reachable from every key, so the message has to say which one.
            CliConfigLoader envLoader = new CliConfigLoader(name -> null);
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "test-api-key"
                    memory:
                      workspaceId: "default"
                      peerId: "agent-default"
                      storagePath: "x.jsonl"
                      dreamer:
                        enabled: true
                        scorer:
                          type: embedding
                          embedding:
                            apiKey: "${OPENAI_KEY}"
                    """);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Environment variable not set: OPENAI_KEY")
                    .hasMessageContaining("memory.dreamer.scorer.embedding.apiKey");
        }

        @Test
        @DisplayName("Should name an array element's key path without an index")
        void anUnsetVariableInsideAnArrayNamesTheKeyPath() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> null);
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "key"
                    mcp:
                      servers:
                        - name: "github"
                          transportType: "STDIO"
                          command: "npx"
                          args: ["-y", "${PACKAGE}"]
                    """);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("Environment variable not set: PACKAGE")
                    .hasMessageContaining("mcp.servers[].args[]");
        }

        @Test
        @DisplayName("Should refuse two sibling keys outside the capability map that expand to one name")
        void refusesACollisionOutsideTheCapabilityMap() throws IOException {
            // The guard used to be a property of llm.modelCapabilities. It is now a property of every mapping,
            // which is what makes the one-sentence rule true of the whole file.
            CliConfigLoader envLoader = new CliConfigLoader(name -> "TOKEN");
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "key"
                    mcp:
                      servers:
                        - name: "github"
                          transportType: "STDIO"
                          command: "npx"
                          env:
                            ${PRIMARY}: "one"
                            ${SECONDARY}: "two"
                    """);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("${PRIMARY}").hasMessageContaining("${SECONDARY}")
                    .hasMessageContaining("TOKEN").hasMessageContaining("mcp.servers[].env");
        }

        @Test
        @DisplayName("Should refuse a literal key a placeholder expands onto")
        void refusesALiteralKeyAPlaceholderExpandsOnto() throws IOException {
            // The shape the old post-bind guard already refused, kept: it ran every declared key through the
            // expander, so a literal `prod` beside a `${P}` that expands to `prod` collided there too.
            CliConfigLoader envLoader = new CliConfigLoader(name -> "prod-assistant");
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "key"
                      modelCapabilities:
                        prod-assistant:
                          supportsSamplingParameters: false
                        ${SECONDARY}:
                          supportsReasoningEffort: true
                    """);

            assertThatThrownBy(() -> envLoader.load(configFile.toString())).isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("prod-assistant").hasMessageContaining("${SECONDARY}")
                    .hasMessageContaining("llm.modelCapabilities");
        }

        @Test
        @DisplayName("Should leave a key written twice to yaml's own last-wins")
        void twoLiteralDuplicateKeysAreLeftAlone() throws IOException {
            // The other half of the decision above. Recording every field name is what preserves the literal +
            // placeholder refusal, and it also makes a plain duplicate visible for the first time -- which is a
            // different defect, in a different layer, that this change deliberately does not start failing on.
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "key"
                      modelCapabilities:
                        prod-assistant:
                          supportsSamplingParameters: false
                        prod-assistant:
                          supportsReasoningEffort: true
                    """);

            assertThat(loader.load(configFile.toString()).getLlmConfig().getModelCapabilities())
                    .containsOnlyKeys("prod-assistant");
        }

        @Test
        @DisplayName("Should keep the written scalar for a value YAML types as a boolean, in a file that expands")
        void aWrittenScalarSurvivesOnAStreamThatExpands() throws IOException {
            // The property a re-serialised JsonNode tree loses. `off` is a YAML 1.1 boolean and arrives as
            // VALUE_FALSE; only the written text tells it apart from `no` and `false`, which the same enum must
            // keep refusing. The ${VAR} in the same file proves the decorator is active on this stream.
            Path configFile = write("""
                    llm:
                      provider: "anthropic"
                      apiKey: "${ANTHROPIC_KEY}"
                      anthropic:
                        thinkingMode: off
                    """);

            CliConfig config = loader.load(configFile.toString());
            assertThat(config.getLlmConfig().getApiKey()).isEqualTo("stub-ANTHROPIC_KEY");
            assertThat(config.getLlmConfig().getAnthropic().getThinkingMode()).isEqualTo(AnthropicThinkingMode.OFF);
        }

        @Test
        @DisplayName("Should hand a String field the scalar exactly as written")
        void aStringFieldSeesTheScalarExactlyAsWritten() throws IOException {
            for (String written : new String[]{"0755", "1.10", "1e3", "yes", "off"}) {
                Path configFile = write("""
                        llm:
                          provider: "openai"
                          apiKey: "${OPENAI_KEY}"
                        cli:
                          prompt: %s
                        """.formatted(written));

                assertThat(loader.load(configFile.toString()).getCliSettings().getPrompt())
                        .as("cli.prompt written as `%s`", written).isEqualTo(written);
            }
        }

        @Test
        @DisplayName("Should not expand a second time")
        void expansionIsASinglePass() throws IOException {
            CliConfigLoader envLoader = new CliConfigLoader(name -> "${OTHER}");
            Path configFile = write("""
                    llm:
                      provider: "openai"
                      apiKey: "${OUTER}"
                    """);

            assertThat(envLoader.load(configFile.toString()).getLlmConfig().getApiKey()).isEqualTo("${OTHER}");
        }
    }
}
