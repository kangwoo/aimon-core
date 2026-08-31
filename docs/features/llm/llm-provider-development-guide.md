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
     * Provider 이름을 반환합니다.
     */
    String getProviderName();
}
```

### LlmResponse

```java
public class LlmResponse {
    private final String textContent;      // 텍스트 응답
    private final List<ToolUse> toolUses;  // Tool 호출 요청
    private final TokenUsage tokenUsage;   // 토큰 사용량
}
```

### Message

```java
public class Message {
    private final Role role;                      // USER, ASSISTANT, TOOL
    private final String content;                 // 텍스트 내용
    private final List<ToolUse> toolUses;         // Tool 호출 (ASSISTANT)
    private final List<ToolUseResult> toolUseResults; // Tool 결과 (TOOL)
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
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    private CustomLlmConfig(Builder builder) {
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        this.model = builder.model != null ? builder.model : "default-model";
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
        private double temperature = 0.7;
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
        return "Custom Provider (" + config.getModel() + ")";
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

// 설정 병합: modelConfig가 기본 config보다 우선
String model = modelConfig.getName().orElse(config.getModel());
double temp = modelConfig.getTemperature().orElse(config.getTemperature());
```

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

            // 2. 요청 빌드 (설정 병합)
            ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model(modelConfig.getName().orElse(config.getModel()))
                .messages(chatMessages)
                .temperature(modelConfig.getTemperature().orElse(config.getTemperature()))
                .maxCompletionTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens()));

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
        return "OpenAI (" + config.getModel() + ")";
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
