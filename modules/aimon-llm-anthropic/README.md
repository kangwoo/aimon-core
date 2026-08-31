# AIMON LLM Anthropic

Anthropic Claude API를 사용하는 `LlmClient` 구현체입니다. Messages API와 tool calling을 지원합니다.

## 특징

- **Claude 모델 지원**: Claude Sonnet, Opus, Haiku 등 모든 Claude 모델 사용 가능
- **Tool Calling**: Anthropic tool calling을 통한 도구 실행 지원
- **멀티모달 콘텐츠**: 텍스트, 이미지(Base64/URL), 문서(텍스트/PDF) 지원
- **동적 모델 설정**: `LlmModel`을 통한 요청별 모델 파라미터 오버라이드
- **AutoCloseable**: try-with-resources 패턴 지원
- **스레드 안전**: 불변 설정과 상태 없는 변환기

## 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":aimon-llm-anthropic"))
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
│                  aimon-llm-anthropic (구현체)             │
│                                                         │
│  AnthropicLlmClient ──→ AnthropicMessageConverter       │
│         │                    (메시지/도구 변환)            │
│         ▼                                               │
│  AnthropicConfig                                        │
│  (API 키, 모델, 온도 등)                                  │
│                                                         │
│  exception/                                             │
│  ├── AnthropicException                                 │
│  ├── MessageConversionException                         │
│  └── ToolConversionException                            │
└─────────────────────────────────────────────────────────┘
```

## 사용 방법

### 기본 사용

```java
// 1. 설정
AnthropicConfig config = AnthropicConfig.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .model("claude-sonnet-4-20250514")
    .build();

// 2. 클라이언트 생성
LlmClient client = new AnthropicLlmClient(config);

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
    .name("claude-opus-4-20250514")
    .temperature(0.7)
    .maxTokens(2048)
    .topP(0.9)
    .build();

LlmResponse response = client.sendMessage(
    "You are a helpful assistant",
    messages,
    tools,
    modelConfig
);
```

### try-with-resources

```java
try (AnthropicLlmClient client = new AnthropicLlmClient(config)) {
    LlmResponse response = client.sendMessage(...);
}
```

## 설정 옵션

| 파라미터 | 타입 | 기본값 | 범위 | 설명 |
|---------|------|-------|------|------|
| `apiKey` | String | (필수) | - | Anthropic API 키 |
| `model` | String | `claude-sonnet-4-20250514` | - | 사용할 Claude 모델 |
| `temperature` | double | `0.0` | 0.0 ~ 1.0 | 샘플링 온도 |
| `maxTokens` | int | `4096` | > 0 | 최대 생성 토큰 수 |
| `timeout` | Duration | 60초 | - | 요청 타임아웃 |
| `baseUrl` | String | null | - | 커스텀 API 엔드포인트 (선택) |

## 예외 계층

```
LlmClientException (aimon-core)
└── AnthropicException
    ├── MessageConversionException   # 메시지/콘텐츠 변환 실패
    └── ToolConversionException      # 도구 정의 변환 실패
```

- `AnthropicException`: Anthropic SDK 에러를 래핑
- `MessageConversionException`: 지원하지 않는 역할/콘텐츠 타입, JSON 변환 실패
- `ToolConversionException`: 도구 스키마 변환 실패

## Anthropic 고유 동작

- **시스템 프롬프트**: 메시지가 아닌 전용 파라미터로 전달
- **도구 결과**: TOOL 역할 대신 USER 메시지의 `ToolResultBlockParam`으로 변환
- **미지원 파라미터**: `presencePenalty`, `frequencyPenalty`는 무시 (DEBUG 로그)
- **응답 절단 감지**: `max_tokens` 초과 시 WARN 로그
- **모델 거부 감지**: 모델이 응답을 거부한 경우 WARN 로그
- **온도 클램핑**: 범위 초과 시 자동으로 [0.0, 1.0]으로 조정 (WARN 로그)

## 빌드 및 테스트

```bash
# 모듈 빌드
./gradlew :aimon-llm-anthropic:build

# 단위 테스트 실행 (API 키 불필요)
./gradlew :aimon-llm-anthropic:test

# 통합 테스트 포함 실행 (API 키 필요)
ANTHROPIC_KEY=sk-ant-... ./gradlew :aimon-llm-anthropic:test

# 코드 포매팅
./gradlew :aimon-llm-anthropic:spotlessApply
```

## 참고

- [LLM Provider 개발 가이드](../../docs/features/llm/llm-provider-development-guide.md)
- [SOLID 원칙](../../docs/project/solid-principles.md)
- [Anthropic API 문서](https://docs.anthropic.com/en/api/messages)
