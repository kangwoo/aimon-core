package at.aimon.cli.factory;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import at.aimon.cli.config.AnthropicProviderConfig;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.ModelCapabilityConfig;
import at.aimon.cli.config.OpenAiProviderConfig;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry;
import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.core.llms.anthropic.AnthropicConfig;
import at.aimon.core.llms.anthropic.AnthropicLlmClient;
import at.aimon.core.llms.openai.OpenAIConfig;
import at.aimon.core.llms.openai.OpenAILlmClient;

public class LlmClientFactory {

    /** capability 선언 블록의 yaml 키 경로. 거절 메시지가 사용자가 고쳐야 하는 자리를 이 이름으로 부른다. */
    private static final String MODEL_CAPABILITIES_KEY = "llm.modelCapabilities";

    /** Anthropic 전용 블록의 yaml 키 경로. 같은 이유로 상수다. */
    private static final String ANTHROPIC_KEY = "llm.anthropic";

    /** OpenAI 전용 블록의 yaml 키 경로. 이 라운드가 연 네임스페이스이고, 거절이 이 이름을 부른다. */
    private static final String OPENAI_KEY = "llm.openai";

    /** provider 선택자의 yaml 키 경로. {@link #ANTHROPIC_KEY} 를 거절할 때 함께 부른다. */
    private static final String PROVIDER_KEY = "llm.provider";

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
        refuseOpenAiBlock(config);
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

        // 공통 키. openAiConfig 의 같은 줄과 짝이며, 그 둘이 짝인 것이 `llm.reasoningEffort` 가 vendor 블록이 아니라
        // 여기 있는 이유다 — 두 provider 가 같은 뜻으로 읽는다.
        if (config.getReasoningEffort() != null) {
            builder.reasoningEffort(config.getReasoningEffort());
        }

        applyThinking(builder, config.getAnthropic());

        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            // 이 catch 가 좁은 이유. build() 가 이 경로에서 던질 수 있는 IllegalArgumentException 은 다섯이고
            // (빈 apiKey · 범위 밖 temperature · 0 이하 maxTokens · 1024 미만 예산 · EXTENDED 아닌 모드의 예산),
            // 앞의 셋은 여기까지 오지 않는다 — apiKey 는 validateApiKey 가 먼저 이름으로 거절하고, 나머지 둘은
            // 어느 표면에서도 설정할 수 없다. 남는 둘이 **둘 다 예산의 것**이므로 메시지가 블록이 아니라 그 키
            // 하나를 부른다. temperature 가 언젠가 설정 가능해지면 그것이 참이 아니게 되고, 그때 여기를 갈라야 한다.
            throw new ConfigurationException(
                    "Invalid `" + ANTHROPIC_KEY + ".thinkingBudgetTokens` in the LLM config: " + e.getMessage(), e);
        }
    }

    /**
     * {@code llm.anthropic} 의 세 키를 vendor config 에 옮긴다. <b>적힌 것만</b> 옮기므로, 블록이 없는 배포의
     * 요청은 이 변경 전과 글자 하나 다르지 않다 — 세 setter 중 하나도 불리지 않아 {@code AnthropicConfig} 의
     * 기본값(모드 {@code OFF}, 예산 없음, replay {@code true})이 그대로 선다.
     *
     * <p>
     * 두 키가 상호작용한다는 판단은 여기서 하지 않는다. 예산은 {@code EXTENDED} 아래에서만 뜻이 있고 그것을
     * 거절하는 것은 {@code AnthropicConfig} 의 생성자다 — 여기서 미리 검사하면 같은 규칙의 두 번째 사본이 생긴다.
     */
    private void applyThinking(AnthropicConfig.Builder builder, AnthropicProviderConfig anthropic) {
        if (anthropic == null || anthropic.isEmpty()) {
            return;
        }
        if (anthropic.getThinkingMode() != null) {
            builder.thinkingMode(anthropic.getThinkingMode());
        }
        if (anthropic.getThinkingBudgetTokens() != null) {
            builder.thinkingBudgetTokens(anthropic.getThinkingBudgetTokens());
        }
        if (anthropic.getThinkingDisplay() != null) {
            builder.thinkingDisplay(anthropic.getThinkingDisplay());
        }
        if (anthropic.getReplayThinkingBlocks() != null) {
            builder.replayThinkingBlocks(anthropic.getReplayThinkingBlocks());
        }
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
        refuseAnthropicBlock(config);
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

        if (config.getReasoningEffort() != null) {
            builder.reasoningEffort(config.getReasoningEffort());
        }

        applyOpenAi(builder, config.getOpenai());

        return builder.build();
    }

    /**
     * {@code llm.openai} 의 키를 vendor config 에 옮긴다. {@link #applyThinking} 와 같은 계약이다 —
     * <b>적힌 것만</b> 옮기므로, 블록이 없는 배포의 요청은 이 변경 전과 글자 하나 다르지 않다.
     */
    private void applyOpenAi(OpenAIConfig.Builder builder, OpenAiProviderConfig openai) {
        if (openai == null || openai.isEmpty()) {
            return;
        }
        if (openai.getReasoningSummary() != null) {
            builder.reasoningSummary(openai.getReasoningSummary());
        }
    }

    /**
     * 이 provider 가 읽지 않을 {@code llm.anthropic} 블록을 이름으로 거절한다.
     *
     * <p>
     * "설정했는데 안 읽히는 것이 가장 나쁘다" 의 적용이다. 거절은 <b>실제로 도는 분기 안에서만</b> 한다 —
     * 분기 밖에서 검사하면 그 블록을 정당하게 적어 둔 배포(자기 클라이언트를 쓰는 배포)를 기동 실패로 만든다.
     *
     * <p>
     * <b>이제 반대 방향의 짝이 있다</b> — {@link #refuseOpenAiBlock}. 예전에는 {@code llm.openai} 블록이
     * 존재하지 않아 anthropic 분기가 거절할 것이 없었고, 그것을 거짓으로 만든 것이 이 라운드다.
     */
    private void refuseAnthropicBlock(LlmProviderConfig config) {
        if (config.getAnthropic() != null && !config.getAnthropic().isEmpty()) {
            throw new ConfigurationException(
                    "`" + ANTHROPIC_KEY + "` is set but `" + PROVIDER_KEY + "` is `" + config.getProvider()
                            + "`, so nothing reads it. Remove the block, or set `" + PROVIDER_KEY + ": anthropic`.");
        }
    }

    /**
     * 이 provider 가 읽지 않을 {@code llm.openai} 블록을 이름으로 거절한다. {@link #refuseAnthropicBlock} 의
     * 대칭이며 같은 규칙을 따른다 — 실제로 도는 분기 안에서만, 빈 블록에는 발화하지 않는다.
     */
    private void refuseOpenAiBlock(LlmProviderConfig config) {
        if (config.getOpenai() != null && !config.getOpenai().isEmpty()) {
            throw new ConfigurationException(
                    "`" + OPENAI_KEY + "` is set but `" + PROVIDER_KEY + "` is `" + config.getProvider()
                            + "`, so nothing reads it. Remove the block, or set `" + PROVIDER_KEY + ": openai`.");
        }
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
     * yaml 의 여덟 키를 중립 선언 타입으로 옮긴다. 적히지 않은 키는 {@code null} 로 남아 "선언되지 않음" 이 되고,
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

    /**
     * yaml 한 항목을 중립 선언으로 옮긴다. 키마다 한 줄씩 손으로 적는 자리라서, 한 줄이 빠지면 운영자가 적은 키가 바인딩되고도
     * 선언에 실리지 않는다 — {@code ModelCapabilityConfigBindingTest} 가 모든 키에 대해 그것을 확인한다(#82). package-private 인
     * 이유는 {@link #openAiConfig} 와 같다 — 테스트가 잡아야 하는 것은 조립된 결과이고, 그것을 테스트 편의로 공개하는 것은
     * {@code docs/design/llm/configuration-surface.md} §9.1 이 기각한 일이다.
     *
     * @param name
     *            yaml 맵의 모델 이름 — 예외 메시지의 키 경로에만 쓰인다
     * @param capabilities
     *            그 이름 아래 적힌 키들 (본문이 빈 항목이면 null)
     * @return 옮긴 선언, 본문이 빈 항목이면 {@code null}
     */
    ModelCapabilityDeclaration declarationOf(String name, ModelCapabilityConfig capabilities) {
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
                    .lowestReasoningEffort(capabilities.getLowestReasoningEffort())
                    .acceptedReasoningEfforts(rungSetOf(capabilities.getAcceptedReasoningEfforts()))
                    .thinkingDialect(capabilities.getThinkingDialect())
                    .supportsReasoningSummary(capabilities.getSupportsReasoningSummary()).build();
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "Invalid `" + MODEL_CAPABILITIES_KEY + "." + name + "` in the LLM config: " + e.getMessage(), e);
        }
    }

    /**
     * yaml 의 rung 목록을 집합으로 옮긴다. 적히지 않았으면 {@code null} — 그것이 "선언되지 않음" 이다.
     *
     * <p>
     * <b>빈 목록은 여기서 빈 집합이 되고 코어가 거절한다.</b> 조용히 "선언되지 않음" 으로 접으면 운영자가 적은 것이
     * 아무 일도 하지 않게 되는데, 이 표면 전체가 그런 무언의 no-op 하나 때문에 생겼다.
     *
     * <p>
     * <b>빈 원소도 마찬가지로 거절한다 — 다만 <em>몇 번째</em>인지를 함께 부른다.</b> yaml 에서
     * {@code [none, ~, high]} 나 값 없는 {@code -} 는 Jackson 이 {@code null} 원소로 준다. 그것을 건너뛰면
     * 사다리가 조용히 좁아지고, 좁아진 사다리는 실패하지 않는다 — 나중에 파라미터 하나가 빠진 요청으로
     * 나타나므로 운영자는 그것을 모델의 성질로 읽는다. 예외 메시지에 yaml 키 경로를 얹는 것은
     * {@link #declarationOf} 의 {@code catch} 다.
     */
    private Set<ReasoningEffort> rungSetOf(List<ReasoningEffort> rungs) {
        if (rungs == null) {
            return null;
        }
        // yaml 은 시퀀스를 List 로 준다. 중복된 rung 은 뜻이 하나뿐이므로 접히고, EnumSet 이라 순서는 사다리 순이다.
        final Set<ReasoningEffort> set = EnumSet.noneOf(ReasoningEffort.class);
        for (int index = 0; index < rungs.size(); index++) {
            final ReasoningEffort rung = rungs.get(index);
            if (rung == null) {
                throw new IllegalArgumentException("acceptedReasoningEfforts[" + index + "] has no value. An empty"
                        + " list entry (a bare `-`, or `~`) is a configuration error rather than a rung to skip —"
                        + " skipping it would narrow which requests this model is allowed to send without saying so."
                        + " Remove the entry, or give it one of: none, minimal, low, medium, high.");
            }
            set.add(rung);
        }
        return set;
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
