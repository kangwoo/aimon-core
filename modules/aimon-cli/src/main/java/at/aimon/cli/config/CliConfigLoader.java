package at.aimon.cli.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import at.aimon.cli.exception.ConfigurationException;

public class CliConfigLoader {
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
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
        // starter's relaxed binding already accepts. It still widens exactly one key -- three other fields look like
        // they would and none does. Two are not enums at all: MemoryDreamerConfig.ScorerConfig.type is a String routed
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

        try {
            CliConfig config = yamlMapper.readValue(file, CliConfig.class);
            resolveEnvironmentVariables(config);
            validateConfig(config);
            return config;
        } catch (JsonParseException e) {
            throw new ConfigurationException("Invalid YAML syntax in: " + expandedPath, e);
        } catch (JsonMappingException e) {
            throw new ConfigurationException("Invalid configuration structure in: " + expandedPath, e);
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

        try (InputStream configInput = input) {
            CliConfig config = yamlMapper.readValue(configInput, CliConfig.class);
            resolveEnvironmentVariables(config);
            validateConfig(config);
            return config;
        } catch (IOException e) {
            throw new ConfigurationException("Failed to load configuration from: " + configSource, e);
        }
    }

    private void resolveEnvironmentVariables(CliConfig config) {
        if (config.getLlmConfig() != null) {
            LlmProviderConfig llmConfig = config.getLlmConfig();
            if (llmConfig.getApiKey() != null) {
                llmConfig.setApiKey(resolveEnvVars(llmConfig.getApiKey()));
            }
            if (llmConfig.getBaseUrl() != null) {
                llmConfig.setBaseUrl(resolveEnvVars(llmConfig.getBaseUrl()));
            }
            if (llmConfig.getModel() != null) {
                llmConfig.setModel(resolveEnvVars(llmConfig.getModel()));
            }
            resolveModelCapabilityKeys(llmConfig);
        }

        if (config.getMcpConfig() != null && config.getMcpConfig().hasServers()) {
            for (McpServerEntry entry : config.getMcpConfig().getServers()) {
                resolveMcpServerEnvVars(entry);
            }
        }
    }

    /**
     * capability 선언의 <b>맵 키</b>에서도 {@code ${VAR}} 를 푼다. {@code model} 이 이미 풀리므로, 여기서 풀지 않으면
     * {@code model: ${MODEL}} 을 쓰는 배포는 자기 모델을 서술할 방법이 없고 그 실패가 원래의 400 이다.
     *
     * <p>
     * 확장 결과가 겹치면 거절한다. yaml 은 같은 키를 두 번 적는 것을 막지만 {@code ${A}} 와 {@code ${B}} 가 같은 값으로
     * 풀리는 것은 막지 못하고, 그때 뒤엣것이 앞엣것을 덮으면 그 사실을 아무도 보고하지 않는다 — 레지스트리의
     * 대소문자 중복 검사도 이것만은 볼 수 없다. 충돌이 맵에 들어가기 <b>전에</b> 일어나기 때문이다.
     */
    private void resolveModelCapabilityKeys(LlmProviderConfig llmConfig) {
        final Map<String, ModelCapabilityConfig> declared = llmConfig.getModelCapabilities();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        final Map<String, ModelCapabilityConfig> resolved = new LinkedHashMap<>();
        final Map<String, String> sources = new LinkedHashMap<>();
        declared.forEach((name, capabilities) -> {
            final String expanded = resolveEnvVars(name);
            final String previous = sources.putIfAbsent(expanded, name);
            if (previous != null) {
                throw new ConfigurationException("Model capability declarations `" + previous + "` and `" + name
                        + "` both expand to `" + expanded + "`, so one would silently replace the other."
                        + " Keep one of them under `llm.modelCapabilities`.");
            }
            resolved.put(expanded, capabilities);
        });
        llmConfig.setModelCapabilities(resolved);
    }

    private void resolveMcpServerEnvVars(McpServerEntry entry) {
        if (entry.getCommand() != null) {
            entry.setCommand(resolveEnvVars(entry.getCommand()));
        }
        if (entry.getUrl() != null) {
            entry.setUrl(resolveEnvVars(entry.getUrl()));
        }
        if (entry.getEnv() != null) {
            Map<String, String> resolved = new LinkedHashMap<>();
            entry.getEnv().forEach((key, value) -> resolved.put(key, resolveEnvVars(value)));
            entry.setEnv(resolved);
        }
    }

    private String resolveEnvVars(String value) {
        if (value == null) {
            return null;
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = envVarResolver.apply(envVar);

            if (envValue == null) {
                throw new ConfigurationException("Environment variable not set: " + envVar);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(envValue));
        }

        matcher.appendTail(result);
        return result.toString();
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
