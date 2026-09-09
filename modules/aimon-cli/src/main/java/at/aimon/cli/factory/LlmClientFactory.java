package at.aimon.cli.factory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.ModelCapabilityConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAIConfig;
import at.aimon.core.llms.openai.OpenAILlmClient;

public class LlmClientFactory {

    /** capability 선언 블록의 yaml 키 경로. 거절 메시지가 사용자가 고쳐야 하는 자리를 이 이름으로 부른다. */
    private static final String MODEL_CAPABILITIES_KEY = "llm.modelCapabilities";

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
        return new AnthropicLlmClient(anthropicConfig(config));
    }

    /**
     * yaml 을 {@code AnthropicConfig} 로 옮긴다. package-private 인 이유는 {@link #openAiConfig} 와 같다 —
     * {@code AnthropicLlmClient} 는 자기 config 를 공개하지 않고, 테스트 편의로 그것을 공개하는 것은 배포 모듈의 표면을 넓히는 일이다.
     *
     * @param config
     *            yaml 의 {@code llm} 블록
     * @return 조립된 Anthropic 설정
     */
    AnthropicConfig anthropicConfig(LlmProviderConfig config) {
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

        final Map<String, ModelCapabilityDeclaration> declarations = declarationsOf(config);
        if (!declarations.isEmpty()) {
            builder.modelCapabilityRegistry(registryFor(declarations));
        }

        return builder.build();
    }

    private LlmClient createOpenAIClient(LlmProviderConfig config) {
        return new OpenAILlmClient(openAiConfig(config));
    }

    /**
     * yaml 을 {@code OpenAIConfig} 로 옮긴다. package-private 인 것은 테스트가 만들어진 config 를 잡을 수 있게 하기 위해서다 —
     * {@code OpenAILlmClient} 는 자기 config 를 공개하지 않고, 그것을 테스트 편의로 공개하는 것은 배포 모듈의 표면을 넓히는 일이다.
     *
     * @param config
     *            yaml 의 {@code llm} 블록
     * @return 조립된 OpenAI 설정
     */
    OpenAIConfig openAiConfig(LlmProviderConfig config) {
        final String apiKey = validateApiKey(config.getApiKey());
        final OpenAIConfig.Builder builder = OpenAIConfig.builder().apiKey(apiKey).model(validateModel(config));

        if (config.getTimeout() != null) {
            builder.timeout(Duration.ofSeconds(config.getTimeout()));
        }

        if (config.getBaseUrl() != null) {
            builder.baseUrl(config.getBaseUrl());
        }

        final Map<String, ModelCapabilityDeclaration> declarations = declarationsOf(config);
        if (!declarations.isEmpty()) {
            builder.modelCapabilityRegistry(registryFor(declarations));
        }

        return builder.build();
    }

    /**
     * 내장 표를 선언으로 확장한 registry. 판정은 코어가 하고, 여기서는 그 예외를 <b>yaml 키를 부르는</b> 메시지로 다시 던진다 —
     * {@link #validateModel} 이 세운 것과 같은 규칙이다(사용자가 고쳐야 하는 것은 자바 필드가 아니라 yaml 키다).
     */
    private InMemoryModelCapabilityRegistry registryFor(Map<String, ModelCapabilityDeclaration> declarations) {
        try {
            return InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(declarations);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "Invalid `" + MODEL_CAPABILITIES_KEY + "` in the LLM config: " + e.getMessage(), e);
        }
    }

    /**
     * yaml 의 다섯 키를 중립 선언 타입으로 옮긴다. 적히지 않은 키는 {@code null} 로 남아 "선언되지 않음" 이 되고,
     * 그것을 fail-open 값으로 푸는 것은 {@link ModelCapabilityDeclaration} 의 일이다.
     */
    private Map<String, ModelCapabilityDeclaration> declarationsOf(LlmProviderConfig config) {
        final Map<String, ModelCapabilityConfig> declared = config.getModelCapabilities();
        final Map<String, ModelCapabilityDeclaration> translated = new LinkedHashMap<>();
        if (declared == null || declared.isEmpty()) {
            return translated;
        }
        declared.forEach((name, capabilities) -> translated.put(name, declarationOf(name, capabilities)));
        return translated;
    }

    private ModelCapabilityDeclaration declarationOf(String name, ModelCapabilityConfig capabilities) {
        if (capabilities == null) {
            // 본문이 빈 항목. 코어가 이 null 을 F4 와 같은 문장으로 거절하므로 여기서 두 번째 메시지를 만들지 않는다.
            return null;
        }
        try {
            return ModelCapabilityDeclaration.builder()
                    .supportsSamplingParameters(capabilities.getSupportsSamplingParameters())
                    .supportsReasoningEffort(capabilities.getSupportsReasoningEffort())
                    .supportsToolsWithReasoning(capabilities.getSupportsToolsWithReasoning())
                    .supportsReasoningTraceRoundTrip(capabilities.getSupportsReasoningTraceRoundTrip())
                    .lowestReasoningEffort(capabilities.getLowestReasoningEffort()).build();
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "Invalid `" + MODEL_CAPABILITIES_KEY + "." + name + "` in the LLM config: " + e.getMessage(), e);
        }
    }

    /**
     * OpenAI 는 기본 모델이 없으므로 여기서 먼저 막는다. {@code OpenAIConfig.build()} 도 거절하지만 그 메시지는 자바
     * 필드를 가리키고, 사용자가 고쳐야 하는 것은 yaml 키다.
     */
    private String validateModel(LlmProviderConfig config) {
        final String model = config.getModel();
        if (model == null || model.isBlank()) {
            throw new ConfigurationException(
                    "Model is required for the openai provider - set `model` in the LLM config (e.g. model: gpt-4o)");
        }
        return model;
    }

    private String validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigurationException("API key is required for LLM provider");
        }
        return apiKey;
    }
}
