package at.aimon.cli.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import at.aimon.cli.exception.ConfigurationException;

public class CliConfigLoader {
    private final ObjectMapper yamlMapper;
    private final Function<String, String> envVarResolver;

    /** CliConfigLoader를 생성한다. */
    public CliConfigLoader() {
        this(System::getenv);
    }

    /**
     * 테스트를 위한 환경 변수 리졸버를 지정하는 CliConfigLoader를 생성한다.
     *
     * @param envVarResolver
     *            환경 변수 이름을 값으로 변환하는 함수 (값이 없으면 null 반환)
     */
    CliConfigLoader(Function<String, String> envVarResolver) {
        // ACCEPT_CASE_INSENSITIVE_ENUMS so that `lowestReasoningEffort: low` works as well as `LOW`, matching what the
        // starter's relaxed binding already accepts. It widens four keys, and all four are the same enum:
        // ReasoningEffort, on llm.reasoningEffort and on the three capability spellings of a model's ladder
        // (lowestReasoningEffort, and each element of acceptedReasoningEfforts). Three other fields look like they
        // would reach this feature and none does. Two are not enums at all: MemoryDreamerConfig.ScorerConfig.type is a
        // String routed
        // through ScorerType.fromString (which already folds case itself), and McpServerEntry.transportType is a String
        // parsed by hand; a MapperFeature reaches neither. The third, AnthropicProviderConfig.thinkingMode, does bind
        // an
        // enum and still does not reach this feature: it carries @JsonDeserialize, which replaces the EnumDeserializer
        // this MapperFeature is consumed by, so that field folds case itself with equalsIgnoreCase. Measured -- with
        // this feature disabled, `thinkingMode: auto` still binds. It needs its own deserializer for a different
        // reason: `off` is a YAML 1.1 boolean and never arrives as a string at all.
        this.yamlMapper = JsonMapper.builder(new YAMLFactory()).enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .build();
        this.envVarResolver = envVarResolver;
    }

    /** 지정된 경로에서 CLI 설정을 로드한다. */
    public CliConfig load(String path) {
        if (path == null || path.trim().isEmpty()) {
            return loadDefault();
        }

        String expandedPath = expandPath(path);
        File file = new File(expandedPath);

        if (!file.exists()) {
            throw new ConfigurationException("Configuration file not found: " + expandedPath);
        }

        if (!file.canRead()) {
            throw new ConfigurationException("Configuration file is not readable: " + expandedPath);
        }

        try (JsonParser parser = new PlaceholderExpandingParser(yamlMapper.createParser(file), envVarResolver)) {
            CliConfig config = yamlMapper.readValue(parser, CliConfig.class);
            validateConfig(config);
            return config;
        } catch (JsonParseException e) {
            throw new ConfigurationException("Invalid YAML syntax in: " + expandedPath, e);
        } catch (JsonMappingException e) {
            // A placeholder failure thrown while a property was being read arrives wrapped in this; unwrapping it is
            // what keeps "Environment variable not set: X" instead of the generic structure message.
            throw PlaceholderExpandingParser.placeholderFailureIn(e).orElseGet(
                    () -> new ConfigurationException("Invalid configuration structure in: " + expandedPath, e));
        } catch (IOException e) {
            throw new ConfigurationException("Failed to read configuration from: " + expandedPath, e);
        }
    }

    /** 기본 설정 파일에서 CLI 설정을 로드한다. */
    public CliConfig loadDefault() {
        // Try loading local config first, then fall back to default
        InputStream input = getClass().getClassLoader().getResourceAsStream("default-config-local.yaml");
        String configSource = "default-config-local.yaml";

        if (input == null) {
            input = getClass().getClassLoader().getResourceAsStream("default-config.yaml");
            configSource = "default-config.yaml";
        }

        if (input == null) {
            throw new ConfigurationException("Default configuration file not found");
        }

        final String source = configSource;
        try (InputStream configInput = input;
                JsonParser parser = new PlaceholderExpandingParser(yamlMapper.createParser(configInput),
                        envVarResolver)) {
            CliConfig config = yamlMapper.readValue(parser, CliConfig.class);
            validateConfig(config);
            return config;
        } catch (IOException e) {
            // The only catch here is IOException, and a JsonMappingException is one -- so without the same unwrap
            // load(String) does, a placeholder failure would be reported as "Failed to load configuration from" and
            // lose the variable name.
            throw PlaceholderExpandingParser.placeholderFailureIn(e)
                    .orElseGet(() -> new ConfigurationException("Failed to load configuration from: " + source, e));
        }
    }

    private String expandPath(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }

        File file = new File(path);
        if (!file.isAbsolute()) {
            return new File(System.getProperty("user.dir"), path).getAbsolutePath();
        }

        return path;
    }

    private void validateConfig(CliConfig config) {
        LlmProviderConfig llmConfig = config.getLlmConfig();
        if (llmConfig == null) {
            throw new ConfigurationException("LLM configuration is required");
        }

        if (llmConfig.getProvider() == null || llmConfig.getProvider().trim().isEmpty()) {
            throw new ConfigurationException("LLM provider is required");
        }

        if (llmConfig.getApiKey() == null || llmConfig.getApiKey().trim().isEmpty()) {
            throw new ConfigurationException("LLM API key is required");
        }

        if (config.getAgentConfig() == null) {
            config.setAgentConfig(new AgentConfig());
        }

        if (config.getCliSettings() == null) {
            config.setCliSettings(new CliSettings());
        }
    }
}
