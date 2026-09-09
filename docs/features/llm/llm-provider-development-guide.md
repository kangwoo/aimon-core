# LLM Provider Development Guide

> LLM Provider 개발을 위한 가이드

이 문서는 aimon-core 프레임워크에서 새로운 LLM Provider를 추가할 때 필요한 정보를 제공합니다.

## 목차

1. [개요](#개요)
2. [핵심 인터페이스](#핵심-인터페이스)
3. [구현 단계](#구현-단계)
4. [메시지 변환](#메시지-변환)
5. [Tool Calling 지원](#tool-calling-지원)
6. [설정 클래스](#설정-클래스)
7. [에러 처리](#에러-처리)
8. [전체 예제](#전체-예제)

---

## 개요

LLM Provider는 `LlmClient` 인터페이스를 구현하여 다양한 LLM API (OpenAI, Anthropic, Google 등)와 통합합니다.

### 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **Thread-safe** | 동시 요청 처리 가능 |
| **Stateless** | 요청 간 상태 유지하지 않음 |
| **Tool Calling 지원** | Function/Tool calling 변환 필수 |
| **에러 처리** | 모든 에러를 `LlmClientException`으로 래핑 |

### 패키지 구조

```
at.aimon.llm/
├── core/                          # 핵심 추상화 (aimon-core)
│   ├── LlmClient.java             # LLM 클라이언트 인터페이스
│   ├── LlmResponse.java           # LLM 응답
│   ├── LlmModel.java              # 모델 설정
│   ├── Message.java               # 대화 메시지
│   ├── Role.java                  # 메시지 역할 (USER, ASSISTANT, TOOL)
│   ├── ToolDefinition.java        # Tool 정의
│   ├── ToolUse.java               # Tool 호출 요청
│   ├── ToolUseResult.java         # Tool 실행 결과
│   ├── TokenUsage.java            # 토큰 사용량
│   └── exception/
│       └── LlmClientException.java
└── openai/                        # OpenAI 구현 (aimon-llm-openai)
    ├── OpenAILlmClient.java
    ├── OpenAIConfig.java
    ├── OpenAIMessageConverter.java
    └── exception/
```

---

## 핵심 인터페이스

### LlmClient

```java
public interface LlmClient {

    /**
     * LLM에 메시지를 전송하고 응답을 받습니다.
     *
     * @param systemPrompt 시스템 프롬프트
     * @param messages     대화 이력
     * @param tools        사용 가능한 Tool 정의
     * @param modelConfig  모델 설정 (온도, 토큰 등)
     * @return LLM 응답
     * @throws LlmClientException API 호출 실패 시
     */
    LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                           List<ToolDefinition> tools, LlmModel modelConfig);

    /**
     * Provider 이름을 반환합니다. 인자가 없으므로 요청별 모델을 볼 수 없다 — 벤더 이름만 담는다.
     */
    String getProviderName();

    /**
     * 요청이 모델을 지정하지 않았을 때 이 클라이언트가 쓰는 모델. 관측이 읽는 곳이다.
     */
    default Optional<String> getDefaultModelName() {
        return Optional.empty();
    }
}
```

### LlmResponse

```java
public class LlmResponse {
    private final String textContent;                    // 텍스트 응답
    private final List<ToolUse> toolUses;                // Tool 호출 요청
    private final TokenUsage tokenUsage;                 // 토큰 사용량 (reasoningTokens 포함)
    private final List<ReasoningTrace> reasoningTraces;  // provider 가 만든 불투명 추론 페이로드
}
```

### Message

```java
public class Message {
    private final Role role;                            // USER, ASSISTANT, TOOL
    private final String content;                       // 텍스트 내용
    private final List<ToolUse> toolUses;               // Tool 호출 (ASSISTANT)
    private final List<ToolUseResult> toolUseResults;   // Tool 결과 (TOOL)
    private final List<ReasoningTrace> reasoningTraces; // 추론 페이로드 (ASSISTANT) — 아래 참조
}
```

---

## 구현 단계

### 1. 설정 클래스 생성

```java
public class CustomLlmConfig {
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final Double temperature;   // nullable — 미설정과 0.0 은 다른 상태다
    private final int maxTokens;
    private final Duration timeout;

    private CustomLlmConfig(Builder builder) {
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        // 기본 모델을 지어내지 않는다 — 필수로 받는다 (OpenAIConfig 가 gpt-4 기본값을 버린 이유와 같다)
        this.model = Objects.requireNonNull(builder.model, "Model is required - there is no default");
        this.baseUrl = builder.baseUrl;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout != null ? builder.timeout : Duration.ofSeconds(60);
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters...

    public static class Builder {
        private String apiKey;
        private String model;
        private String baseUrl;
        private Double temperature;     // 시드 없음 — 아무도 넣지 않았으면 보내지 않는다
        private int maxTokens = 4096;
        private Duration timeout;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        // Other builder methods...

        public CustomLlmConfig build() {
            return new CustomLlmConfig(this);
        }
    }
}
```

> **설정 유효성은 여기서 끝난다.** 생성자가 빈 키를 거부하므로, 생성에 성공한 설정은 정의상
> 유효하다. `LlmClient` 에 "지금 설정돼 있는가" 를 되묻는 메서드가 없는 이유가 이것이다 —
> 그런 메서드는 어떤 구현에서도 `false` 를 낼 수 없어 검사하는 시늉만 하게 된다. 클라이언트를
> 손에 쥐고 있다는 것이 곧 설정이 유효하다는 뜻이고, **키가 실제로 통하는지**는 설정이 아니라
> 호출 결과로만 알 수 있다(`LlmClientException`).

### 2. LlmClient 구현

```java
public class CustomLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(CustomLlmClient.class);

    private final CustomLlmConfig config;
    private final HttpClient httpClient;  // 또는 Provider SDK 클라이언트

    public CustomLlmClient(CustomLlmConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.httpClient = createHttpClient(config);
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                                   List<ToolDefinition> tools, LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        try {
            // 1. 요청 빌드
            var request = buildRequest(systemPrompt, messages, tools, modelConfig);

            // 2. API 호출
            var response = callApi(request);

            // 3. 응답 변환
            return convertResponse(response);

        } catch (Exception e) {
            logger.error("API call failed: {}", e.getMessage(), e);
            throw new LlmClientException("API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "Custom Provider";   // 벤더만. 모델을 넣으면 요청별 오버라이드를 덮어 쓴 이름이 로그에 남는다
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return Optional.of(config.getModel());
    }

    // Private helper methods...
}
```

### 3. 메시지 변환기 생성

```java
public class CustomMessageConverter {

    /**
     * aimon Message를 Provider 형식으로 변환합니다.
     */
    public List<ProviderMessage> convertMessages(List<Message> messages) {
        List<ProviderMessage> result = new ArrayList<>();

        for (Message message : messages) {
            switch (message.getRole()) {
                case USER -> result.add(convertUserMessage(message));
                case ASSISTANT -> result.add(convertAssistantMessage(message));
                case TOOL -> result.addAll(convertToolResults(message));
            }
        }

        return result;
    }

    /**
     * aimon ToolDefinition을 Provider 형식으로 변환합니다.
     */
    public List<ProviderTool> convertTools(List<ToolDefinition> tools) {
        return tools.stream()
            .map(this::convertTool)
            .toList();
    }

    // Private conversion methods...
}
```

---

## 메시지 변환

### Role 매핑

| aimon Role | OpenAI | Anthropic |
|------------|--------|-----------|
| `USER` | `user` | `user` |
| `ASSISTANT` | `assistant` | `assistant` |
| `TOOL` | `tool` | `user` (tool_result content) |

### User 메시지 변환

```java
private ProviderMessage convertUserMessage(Message message) {
    return ProviderMessage.builder()
        .role("user")
        .content(message.getContent())
        .build();
}
```

### Assistant 메시지 변환 (Tool 호출 포함)

```java
private ProviderMessage convertAssistantMessage(Message message) {
    var builder = ProviderMessage.builder()
        .role("assistant")
        .content(message.getContent());

    // Tool 호출이 있는 경우
    if (message.hasToolUses()) {
        List<ProviderToolCall> toolCalls = message.getToolUses().stream()
            .map(this::convertToolUse)
            .toList();
        builder.toolCalls(toolCalls);
    }

    return builder.build();
}
```

### Tool 결과 메시지 변환

Provider에 따라 변환 방식이 다릅니다:

```java
// OpenAI 스타일: 각 결과를 별도의 tool 메시지로
private List<ProviderMessage> convertToolResultsOpenAI(Message message) {
    return message.getToolUseResults().stream()
        .map(result -> ProviderMessage.builder()
            .role("tool")
            .toolCallId(result.getToolUseId())
            .content(result.getContent())
            .build())
        .toList();
}

// Anthropic 스타일: user 메시지 내 tool_result content로
private ProviderMessage convertToolResultsAnthropic(Message message) {
    List<ContentBlock> contents = message.getToolUseResults().stream()
        .map(result -> ContentBlock.toolResult(result.getToolUseId(), result.getContent()))
        .toList();

    return ProviderMessage.builder()
        .role("user")
        .content(contents)
        .build();
}
```

---

## Tool Calling 지원

### ToolDefinition → Provider Tool 변환

```java
private ProviderTool convertTool(ToolDefinition tool) {
    return ProviderTool.builder()
        .type("function")
        .function(ProviderFunction.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .parameters(tool.getInputSchema())  // JSON Schema
            .build())
        .build();
}
```

### Provider Tool Call → ToolUse 변환

```java
private ToolUse convertToolCall(ProviderToolCall toolCall) {
    Map<String, Object> input = parseJsonToMap(toolCall.getArguments());

    return ToolUse.of(
        toolCall.getId(),       // Tool 호출 ID
        toolCall.getName(),     // Tool 이름
        input                   // 파라미터
    );
}

private Map<String, Object> parseJsonToMap(String json) {
    try {
        return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
        throw new ToolConversionException("Failed to parse tool arguments: " + e.getMessage(), e);
    }
}
```

---

## 설정 클래스

### LlmModel (동적 설정)

요청마다 다른 설정을 사용할 수 있습니다:

```java
LlmModel modelConfig = LlmModel.builder()
    .name("gpt-4-turbo")          // 모델 이름 (Optional)
    .temperature(0.5)             // 온도 (Optional)
    .maxTokens(8192)              // 최대 토큰 (Optional)
    .topP(0.9)                    // Top-P (Optional)
    .presencePenalty(0.1)         // Presence Penalty (Optional)
    .frequencyPenalty(0.1)        // Frequency Penalty (Optional)
    .build();

// 설정 병합: modelConfig가 기본 config보다 우선. 체인은 config 에서 끝난다 — 프로바이더의 fallback 상수는 없다
String model = modelConfig.getName().orElse(config.getModel());
Optional<Double> temp = modelConfig.getTemperature().or(config::getTemperature);
```

### 파라미터를 설정하기 전에 능력을 확인한다

IMPORTANT: **샘플링 파라미터를 무조건 설정하지 말 것.** 어떤 모델은 값이 아니라 파라미터의 **존재**로
거절한다 — `"temperature": null` 도 `"temperature": 0.0` 과 똑같이 400 을 받는다. 그래서 생략은
"null 을 넘긴다" 가 아니라 **"세터를 부르지 않는다"** 여야 하고, 무엇을 부를지는 모델 이름 분기가 아니라
`ModelCapabilityRegistry` 가 답한다.

```java
final String modelName = modelConfig.getName().orElse(config.getModel());
final ModelCapabilities capabilities = config.getModelCapabilityRegistry().resolve(modelName);

if (capabilities.supportsSamplingParameters()) {
    temp.ifPresent(requestBuilder::temperature);   // 아무도 넣지 않았으면 이것도 세터를 부르지 않는다
}
// else: 세터를 아예 부르지 않는다
```

IMPORTANT: **프로바이더는 값을 지어내지 않는다.** `orElse(DEFAULT_TEMPERATURE)` 처럼 미설정을 자기
상수로 채우면, 아무도 요청하지 않은 샘플링 값이 매 요청에 실리고 서버 기본값이 영영 적용되지 않는다.
미설정은 **보내지 않는 것**이고, 그때 무엇이 적용될지는 서버가 정한다.

`resolve` 는 총함수이며 **fail-open** 이다 — 아무도 설명하지 않은 모델은 `ModelCapabilities.unknown()`,
즉 **호출자가 요청한 것은 하나도 빼지 않고, 요청하지 않은 것은 하나도 지어내지 않는다**. 값을 떨어뜨렸다면 그 사실을
[`LlmModel`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/LlmModel.java) 의 규칙대로
운영자가 보는 수준으로 보고한다(`AnthropicLlmClient#reportDivergence`, `OpenAILlmClient#reportDivergence`).

### 추론 페이로드는 채우고 되읽되, 절대 들여다보지 않는다

추론 모델은 도구 호출과 함께 불투명한 항목 하나를 돌려준다 — OpenAI 의 `encrypted_content` 를 실은
`reasoning` 항목, Anthropic 의 서명이 붙은 `thinking` 블록. 그것을 다음 요청에 되싣지 않으면 모델은 ReAct
이터레이션마다 사고 과정을 처음부터 다시 만든다. 답이 나빠지고 추론 토큰이 매번 다시 청구된다.
`ReasoningTrace` 가 그 항목이 턴 사이에 머무는 자리다.

프로바이더가 할 일은 넷이다.

1. **채운다** — 응답에서 항목을 뽑아 `LlmResponse.withReasoningTraces(...)` 로 붙인다. 스트리밍이면
   `ChunkAggregator.addReasoningTrace(...)` 가 같은 자리다
2. **태그한다** — `providerName` 은 `getProviderName()` 그대로다. 전사는 클라이언트보다 오래 살아서
   (`LlmFallbackPolicy`, provider 설정 변경, 서브에이전트 스냅샷 재생) 남의 페이로드가 도착할 수 있다.
   자기 것이 아니면 버리고 한 번 경고한다
3. **닻을 내린다** — `toolUseId` 는 *이 trace 가 그 도구 호출 바로 앞에 온다* 는 뜻이다. `Message` 는
   텍스트와 도구 호출을 서로 다른 리스트에 담으므로 순서를 그것만으로 표현할 수 없다
4. **되읽는다** — 순서 규칙은 프로바이더마다 하나씩 적는다. 슬롯은 두 순서를 다 표현할 수 있고, 어느
   프로바이더도 남의 규칙을 물려받지 않는다

IMPORTANT: **`payload` 를 파싱하거나 다시 직렬화하거나 정규화하지 말 것.** `aimon-core` 가 그것에 하는
연산은 복사 하나뿐이고, 프로바이더도 그래야 한다 — OpenAI 의 `encrypted_content` 는 암호문이고 Anthropic 의
`signature` 는 서명이라, 한 바이트만 달라져도 서버가 되읽기를 거절한다. SDK 모델을 직렬화한다면 그 SDK 자신의
매퍼를 쓴다(OpenAI 는 `com.openai.core.ObjectMappers.jsonMapper()`): 새 `ObjectMapper` 는 같은 바이트를
읽고 없던 필드를 붙여 내보낸다.

그 대가는 숨기지 않는다 — **`Message.mapText` 는 이 페이로드에 닿지 않으므로 레닥션도 닿지 않는다.**
고칠 수 있는 성질의 것이 아니고(고치면 되읽기가 깨진다), 받아들일 수 없는 배포에는 끄는 스위치가 있다
(OpenAI 는 `OpenAIConfig.responsesApiEnabled(false)`).

설계 전문은 [OpenAI Responses 경로](../../design/llm/openai-responses-path.md) 참조.

---

## 에러 처리

### LlmClientException

모든 에러는 `LlmClientException`으로 래핑합니다:

```java
@Override
public LlmResponse sendMessage(...) {
    try {
        // API 호출
        return callApi(...);

    } catch (ProviderRateLimitException e) {
        logger.warn("Rate limit exceeded: {}", e.getMessage());
        throw new LlmClientException("Rate limit exceeded. Please retry later.", e);

    } catch (ProviderAuthException e) {
        logger.error("Authentication failed: {}", e.getMessage());
        throw new LlmClientException("Authentication failed. Check your API key.", e);

    } catch (ProviderTimeoutException e) {
        logger.warn("Request timeout: {}", e.getMessage());
        throw new LlmClientException("Request timeout. Please retry.", e);

    } catch (Exception e) {
        logger.error("Unexpected error: {}", e.getMessage(), e);
        throw new LlmClientException("API call failed: " + e.getMessage(), e);
    }
}
```

### 커스텀 예외

```java
public class MessageConversionException extends RuntimeException {
    public MessageConversionException(String message) {
        super(message);
    }

    public MessageConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class ToolConversionException extends RuntimeException {
    public ToolConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## 전체 예제

### OpenAI 구현 참조

`aimon-llm-openai` 모듈의 구현을 참조하세요:

```java
public class OpenAILlmClient implements LlmClient {

    private final OpenAIConfig config;
    private final OpenAIClient client;
    private final OpenAIMessageConverter converter;

    public OpenAILlmClient(OpenAIConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = createOpenAIClient(config);
        this.converter = new OpenAIMessageConverter();
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                                   List<ToolDefinition> tools, LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        try {
            // 1. 메시지 빌드 (시스템 프롬프트 + 대화 이력)
            List<ChatCompletionMessageParam> chatMessages = buildChatMessages(systemPrompt, messages);

            // 2. 요청 빌드 (설정 병합 + 능력 확인)
            ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model(modelConfig.getName().orElse(config.getModel()))
                .messages(chatMessages)
                .maxCompletionTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens()));

            applySamplingParameters(requestBuilder, modelConfig);

            // 3. Tool 추가
            if (!tools.isEmpty()) {
                List<ChatCompletionTool> openaiTools = converter.convertTools(tools);
                requestBuilder.tools(openaiTools);
            }

            // 4. API 호출
            ChatCompletion result = client.chat().completions().create(requestBuilder.build());

            // 5. 응답 변환
            return convertResponse(result);

        } catch (Exception e) {
            logger.error("OpenAI API call failed: {}", e.getMessage(), e);
            throw new LlmClientException("OpenAI API call failed: " + e.getMessage(), e);
        }
    }

    private LlmResponse convertResponse(ChatCompletion result) {
        var choice = result.choices().get(0);
        var message = choice.message();

        String textContent = message.content().orElse("");
        List<ToolUse> toolUses = new ArrayList<>();

        // Tool 호출 변환
        if (message.toolCalls().isPresent()) {
            for (var toolCall : message.toolCalls().get()) {
                if (toolCall.function().isPresent()) {
                    var functionCall = toolCall.function().get();
                    Map<String, Object> input = converter.parseJsonToMap(functionCall.function().arguments());
                    toolUses.add(ToolUse.of(functionCall.id(), functionCall.function().name(), input));
                }
            }
        }

        TokenUsage tokenUsage = extractTokenUsage(result);
        return LlmResponse.of(textContent, toolUses, tokenUsage);
    }

    @Override
    public String getProviderName() {
        return "OpenAI";
    }

    @Override
    public Optional<String> getDefaultModelName() {
        return Optional.of(config.getModel());
    }
}
```

---

## 체크리스트

새 LLM Provider를 개발할 때 확인하세요:

### 필수 사항

- [ ] `LlmClient` 인터페이스 구현
- [ ] `sendMessage()` 메서드에서 null 검사 수행
- [ ] 모든 에러를 `LlmClientException`으로 래핑
- [ ] Thread-safe 구현
- [ ] Tool calling 지원
- [ ] `getProviderName()` 은 벤더만 반환하고, 기본 모델은 `getDefaultModelName()` 으로 노출

### 메시지 변환

- [ ] USER, ASSISTANT, TOOL 메시지 변환
- [ ] Tool 호출이 포함된 ASSISTANT 메시지 변환
- [ ] Tool 결과 메시지 변환 (Provider 형식에 맞게)

### 설정

- [ ] 설정 클래스 생성 (Builder 패턴)
- [ ] **설정 유효성을 생성자에서 끝내기** — 빈 API 키를 거부하고, 나중에 되물을 수 있는 여지를 남기지 않기
- [ ] `LlmModel` 동적 설정 지원
- [ ] 기본 설정과 요청별 설정 병합

### 테스트

- [ ] 단위 테스트 작성
- [ ] 통합 테스트 작성 (API 모킹)
- [ ] 에러 케이스 테스트

---

## 관련 문서

- [OpenAILlmClient.java](../../../modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAILlmClient.java) - 참조 구현
- [OpenAIMessageConverter.java](../../../modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAIMessageConverter.java) - 메시지 변환기
- [LlmClient.java](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/LlmClient.java) - 인터페이스 정의
- [Message.java](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/Message.java) - 메시지 클래스
