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
import com.fasterxml.jackson.databind.ObjectMapper;
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
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
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
        }

        if (config.getMcpConfig() != null && config.getMcpConfig().hasServers()) {
            for (McpServerEntry entry : config.getMcpConfig().getServers()) {
                resolveMcpServerEnvVars(entry);
            }
        }
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
