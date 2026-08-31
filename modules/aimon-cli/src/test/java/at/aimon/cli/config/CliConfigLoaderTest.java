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
}
