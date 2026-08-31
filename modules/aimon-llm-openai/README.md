# AIMON LLM OpenAI

OpenAI Chat Completion API를 사용하는 `LlmClient` 구현체입니다. Tool calling과 멀티모달 콘텐츠를 지원합니다.

## 특징

- **GPT 모델 지원**: GPT-4, GPT-4o, GPT-3.5-turbo 등 모든 Chat Completion 모델 사용 가능
- **Tool Calling**: OpenAI function calling을 통한 도구 실행 지원
- **멀티모달 콘텐츠**: 텍스트, 이미지(Base64/URL), 문서(텍스트) 지원
- **동적 모델 설정**: `LlmModel`을 통한 요청별 모델 파라미터 오버라이드
- **OpenAI 호환 API**: 커스텀 `baseUrl`로 OpenAI 호환 서비스 연동 가능
- **스레드 안전**: 불변 설정과 상태 없는 변환기

## 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":aimon-llm-openai"))
}
```

## 아키텍처

```
┌─────────────────────────────────────────────────────────┐
│                    aimon-core (추상화)                    │
│  LlmClient    Message    ToolDefinition    LlmResponse  │
└──────────────────────────▲──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│                   aimon-llm-openai (구현체)               │
│                                                         │
│  OpenAILlmClient ──→ OpenAIMessageConverter             │
│         │                (메시지/도구 변환)                │
│         ▼                                               │
│  OpenAIConfig                                           │
│  (API 키, 모델, 온도 등)                                  │
│                                                         │
│  exception/                                             │
│  ├── OpenAIException                                    │
│  ├── MessageConversionException                         │
│  └── ToolConversionException                            │
└─────────────────────────────────────────────────────────┘
```

## 사용 방법

### 기본 사용

```java
// 1. 설정
OpenAIConfig config = OpenAIConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .model("gpt-4")
    .build();

// 2. 클라이언트 생성
LlmClient client = new OpenAILlmClient(config);

// 3. 메시지 전송
LlmResponse response = client.sendMessage(
    "You are a helpful assistant",
    List.of(Message.user("What is 2+2?")),
    List.of()
);

System.out.println(response.getTextContent());
```

### Tool Calling

```java
// 도구 정의
ToolDefinition weatherTool = ToolDefinition.of(
    "get_weather",
    "Get the current weather for a location",
    Map.of(
        "type", "object",
        "properties", Map.of(
            "location", Map.of("type", "string", "description", "The city name")
        ),
        "required", List.of("location")
    )
);

// 도구와 함께 메시지 전송
LlmResponse response = client.sendMessage(
    "You are a weather assistant.",
    List.of(Message.user("What's the weather in Seoul?")),
    List.of(weatherTool)
);

// 도구 호출 결과 처리
if (response.hasToolUses()) {
    ToolUse toolUse = response.getToolUses().get(0);
    System.out.println("Tool: " + toolUse.getName());       // get_weather
    System.out.println("Input: " + toolUse.getInput());     // {location=Seoul}
}
```

### 동적 모델 설정

```java
LlmModel modelConfig = LlmModel.builder()
    .name("gpt-4o")
    .temperature(0.7)
    .maxTokens(2048)
    .topP(0.9)
    .presencePenalty(0.5)
    .frequencyPenalty(0.3)
    .build();

LlmResponse response = client.sendMessage(
    "You are a helpful assistant",
    messages,
    tools,
    modelConfig
);
```

### OpenAI 호환 API 사용

```java
OpenAIConfig config = OpenAIConfig.builder()
    .apiKey("your-api-key")
    .baseUrl("https://your-compatible-api.com/v1")
    .model("your-model")
    .build();
```

## 설정 옵션

| 파라미터 | 타입 | 기본값 | 범위 | 설명 |
|---------|------|-------|------|------|
| `apiKey` | String | (필수) | - | OpenAI API 키 |
| `model` | String | `gpt-4` | - | 사용할 모델 |
| `temperature` | double | `0.0` | 0.0 ~ 2.0 | 샘플링 온도 |
| `maxTokens` | int | `4096` | > 0 | 최대 생성 토큰 수 |
| `timeout` | Duration | 60초 | - | 요청 타임아웃 |
| `baseUrl` | String | null | - | 커스텀 API 엔드포인트 (선택) |

## 예외 계층

```
LlmClientException (aimon-core)
└── OpenAIException
    ├── MessageConversionException   # 메시지/콘텐츠 변환 실패
    └── ToolConversionException      # 도구 정의 변환 실패
```

- `OpenAIException`: OpenAI SDK 에러를 래핑
- `MessageConversionException`: 지원하지 않는 역할/콘텐츠 타입, JSON 변환 실패
- `ToolConversionException`: 도구 스키마 변환 실패

## OpenAI 고유 동작

- **시스템 프롬프트**: 시스템 역할 메시지로 전달
- **도구 결과**: TOOL 역할 메시지(`ChatCompletionToolMessageParam`)로 변환
- **지원 파라미터**: `topP`, `presencePenalty`, `frequencyPenalty` 모두 지원
- **응답 절단 감지**: `finish_reason`이 `length`인 경우 WARN 로그
- **토큰 사용량**: 프롬프트, 완료, 총 토큰 수 제공

## 빌드 및 테스트

```bash
# 모듈 빌드
./gradlew :aimon-llm-openai:build

# 단위 테스트 실행 (API 키 불필요)
./gradlew :aimon-llm-openai:test

# 통합 테스트 포함 실행 (API 키 필요)
OPENAI_KEY=sk-... ./gradlew :aimon-llm-openai:test

# 코드 포매팅
./gradlew :aimon-llm-openai:spotlessApply
```

## 참고

- [LLM Provider 개발 가이드](../../docs/features/llm/llm-provider-development-guide.md)
- [SOLID 원칙](../../docs/project/solid-principles.md)
- [OpenAI API 문서](https://platform.openai.com/docs/api-reference/chat)
