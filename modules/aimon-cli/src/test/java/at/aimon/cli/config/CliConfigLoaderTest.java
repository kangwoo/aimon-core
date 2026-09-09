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
                          lowestReasoningEffort: minimal
                    """);

            ModelCapabilityConfig declared = loader.load(configFile.toString()).getLlmConfig().getModelCapabilities()
                    .get("prod-assistant");

            assertThat(declared).isNotNull();
            assertThat(declared.getSupportsSamplingParameters()).isFalse();
            assertThat(declared.getSupportsReasoningEffort()).isTrue();
            assertThat(declared.getSupportsToolsWithReasoning()).isTrue();
            assertThat(declared.getSupportsReasoningTraceRoundTrip()).isFalse();
            assertThat(declared.getLowestReasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
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
            assertThat(declared.getLowestReasoningEffort()).isNull();
        }

        @Test
        @DisplayName("Should accept a reasoning effort in either case")
        void reasoningEffortIsCaseInsensitive() throws IOException {
            // The only key the mapper's ACCEPT_CASE_INSENSITIVE_ENUMS reaches, and the only reason it is on: nothing
            // else in at.aimon.cli.config binds to an enum. Both spellings pass so that this surface feels like the
            // starter's, whose relaxed binding already accepts them.
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
}
