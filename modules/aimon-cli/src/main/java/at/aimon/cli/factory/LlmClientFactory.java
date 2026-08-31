package at.aimon.cli.factory;

import java.time.Duration;

import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAIConfig;
import at.aimon.core.llms.openai.OpenAILlmClient;

public class LlmClientFactory {

    /** 설정에 따라 LLM 클라이언트를 생성한다. */
    public LlmClient create(LlmProviderConfig config) {
        if (config == null) {
            throw new ConfigurationException("LLM provider config cannot be null");
        }

        final String provider = validateProvider(config.getProvider());

        return switch (provider) {
            case "anthropic" -> createAnthropicClient(config);
            case "openai" -> createOpenAIClient(config);
            default -> throw new ConfigurationException("Unsupported LLM provider: " + provider);
        };
    }

    private String validateProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new ConfigurationException("LLM provider is required");
        }
        return provider.toLowerCase();
    }

    private LlmClient createAnthropicClient(LlmProviderConfig config) {
        final String apiKey = validateApiKey(config.getApiKey());
        final AnthropicConfig.Builder builder = AnthropicConfig.builder().apiKey(apiKey);

        if (config.getModel() != null) {
            builder.model(config.getModel());
        }

        if (config.getTimeout() != null) {
            builder.timeout(Duration.ofSeconds(config.getTimeout()));
        }

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        return new AnthropicLlmClient(builder.build());
    }

    private LlmClient createOpenAIClient(LlmProviderConfig config) {
        final String apiKey = validateApiKey(config.getApiKey());
        final OpenAIConfig.Builder builder = OpenAIConfig.builder().apiKey(apiKey);

        if (config.getModel() != null) {
            builder.model(config.getModel());
        }

        if (config.getTimeout() != null) {
            builder.timeout(Duration.ofSeconds(config.getTimeout()));
        }

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        return new OpenAILlmClient(builder.build());
    }

    private String validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigurationException("API key is required for LLM provider");
        }
        return apiKey;
    }
}
