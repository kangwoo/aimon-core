# OpenAI Responses 경로 (OpenAI Responses API Path)

> Status: **IMPLEMENTED** — `OpenAILlmClient` 하나가 요청마다 `/v1/chat/completions` 와 `/v1/responses` 중 하나를
> 고른다. Responses 경로의 요청 조립, 메시지 변환, 응답·스트림 읽기, 오류 분류, reasoning summary 요청과 그 능력
> 게이트가 들어가 있다. 남은 것은 §12.
>
> 적용 대상: `aimon-llm-openai` — `at.aimon.core.llms.openai`(`OpenAILlmClient`, `OpenAIEndpointExchange`,
> `OpenAIStreamHandle`, `OpenAIChatCompletionsExchange`, `OpenAIResponsesExchange`, `OpenAIResponsesRequestFactory`,
> `OpenAIResponsesMessageConverter`, `OpenAIResponsesStreamingMapper`, `OpenAiResponseErrors`, `OpenAiResponseUsages`,
> `OpenAiResponseStopReasons`, `OpenAiReasoningSummary`, `OpenAIConfig`) ·
> `aimon-core` — `ModelCapabilities.supportsReasoningTraceRoundTrip()` · `supportsReasoningSummary()`.

---

## 1. 문제와 범위

Chat Completions 는 reasoning item 을 돌려주지 않는다. 그래서 도구 호출을 건너 넘어가는 것이 없고, ReAct 루프의
iteration 마다 모델이 사고를 처음부터 다시 파생하며 reasoning 토큰도 다시 과금된다. 도구를 여러 번 부르는 루프가
AIMON 이 도는 바로 그 모양이다. 이것을 푸는 엔드포인트가 `/v1/responses` 이므로, reasoning trace 를 다음 요청에
되실어야 하는 모델은 그리로 보낸다.

어려운 것은 새 엔드포인트의 개별 기능이 아니다. Responses 경로는 거의 새 클라이언트 하나에 가깝고, 그렇게 만들면
`OpenAILlmClient` 가 공들여 맞춘 비자명한 동작 네 개(§7.3)가 복사본에서 조금씩 틀리게 다시 구현된다. 이 문서의
구조 대부분은 그 복사를 "하지 말자" 가 아니라 **할 수 없게** 만들기 위해 있다.

**이 문서가 정하는 것.** 엔드포인트 이음매, 라우팅 술어, Responses 요청 매핑과 필수 필드, Chat 변환과의 패리티,
응답·스트림 읽기 규칙, SDK 가 던지지 않는 오류의 분류, 두 엔드포인트가 공유하는 보존 동작, reasoning summary
요청과 그 게이트, 그리고 이 경로의 전제를 세운 측정 결론.

**다른 문서가 정본인 것.**

| 개념 | 정본 |
|---|---|
| `ReasoningTrace` 슬롯, 부착 규칙, 영속, emit/capture 규칙, payload 직렬화, 드롭과 보고, `reasoningTokens` 회계 | [`reasoning-traces.md`](reasoning-traces.md) |
| 샘플링 생략 규칙, effort ladder 와 Chat Completions 의 도구 규칙, divergence 보고 | [`request-parameters.md`](request-parameters.md) |
| `ModelCapabilities` 필드의 뜻과 `unknown()` 값, 내장 표 행과 모델별 측정 결론 | [`model-capabilities.md`](model-capabilities.md) |
| 추론 델타 채널(전달 게이트, 두 번째 버퍼) | [`streaming.md`](streaming.md) |
| 취소 토큰과 abort 레버 | [`cancellation.md`](cancellation.md) |
| 문서 콘텐츠 블록 거부 규칙 | [`multimodal-content.md`](multimodal-content.md) |
| `reasoningSummary` 설정 키와 네임스페이스 | [`configuration-surface.md`](configuration-surface.md) |

**비목표.**

- **Chat Completions 경로를 바꾸지 않는다.** Chat 쪽 변환기와 요청 조립은 이 경로 때문에 열리지 않는다(§5)
- **서버 쪽 대화 상태를 쓰지 않는다.** `store: true` 와 `previous_response_id` 는 쓰지 않는다(§4.2)
- **Responses 에만 있는 표현력을 쓰지 않는다.** 네이티브 `input_file`, 도구 스키마 `strict: true` 는 같은 `Message`
  가 엔드포인트에 따라 다른 뜻이 되게 하므로 이 경로의 범위가 아니다(§5.1, §4.4)

---

## 2. 엔드포인트 이음매 — 형제 클라이언트가 아니라 요청 단위 exchange

`OpenAILlmClient` 는 유일한 OpenAI `LlmClient` 로 남고 안에서 갈라진다. 갈림은 메서드 한가운데의 `if` 가 아니라
주입되는 협력자다 — package-private `OpenAIEndpointExchange` 와 그 구현 둘(`OpenAIChatCompletionsExchange`,
`OpenAIResponsesExchange`).

### 2.1 형제 `LlmClient` 를 두지 않는 이유

`LlmClient` 를 하나 더 구현하는 형제 클라이언트는 두 가지에서 진다. 두 번째가 결정적이다.

1. **형제는 모델별로 고를 수 없는데, 요구는 모델별 선택이다.** `LlmClient` 메서드는 요청마다 모델 이름을 덮어쓸 수
   있는 `LlmModel` 을 받으므로, 엔드포인트 결정은 클라이언트 단위가 아니라 **요청 단위**다. `gpt-5.x` 세션 안에서
   `gpt-4o` 로 도는 컴팩션 호출은 흔한 경우다. 생성 시점에 형제를 고르면 에이전트가 모델을 덮어쓰는 순간 엔드포인트가
   틀린다. 이것을 맞추려면 두 형제를 소유하고 디스패치하는 세 번째 타입이 필요하고, 그러면 같은 구조에 클래스 하나와
   `LlmClientFactory` · 스타터 자동설정이 배워야 할 생성 지점 하나가 더 붙을 뿐이다
2. **형제는 보존 동작을 복제한다.** 이미 취소된 호출의 빠른 경로, 스트림을 연 **뒤에** 등록하는 `onCancel`, 취소된
   스트림을 재분류하는 세 갈래 catch, `SseException` 분류, `null` 을 돌려주는 `perRequestOptions` — 어느 것도
   엔드포인트에 따라 달라지지 않는다. 둘째 클래스에 복사하면 맞춰야 할 곳이 둘이 되고, 그중 하나는 기존 테스트가 보지
   않는 곳이다

### 2.2 `OpenAIEndpointExchange` 와 `OpenAIStreamHandle`

엔드포인트에 따라 달라지는 것은 정확히 넷이다 — 파라미터를 만드는 법, 블로킹 호출을 하는 법, 스트림을 열고 소비하는
법, 결과를 `LlmResponse` 로 바꾸는 법. 이음매는 그 넷만 담는다.

```java
interface OpenAIEndpointExchange {
    LlmResponse callBlocking(RequestOptions options);    // null → SDK 단일 인자 오버로드
    OpenAIStreamHandle openStream(RequestOptions options, LlmStreamSink sink, ChunkAggregator aggregator);
}

interface OpenAIStreamHandle extends AutoCloseable {   // StreamResponse<T> 의 제네릭을 지운다
    void consume();
    @Override void close();                            // StreamResponse.close() 에 위임
}
```

- **`OpenAIStreamHandle` 이 있는 이유는 하나다.** `StreamResponse<ChatCompletionChunk>` 와
  `StreamResponse<ResponseStreamEvent>` 에는 쓸모 있는 공통 상위 타입이 없다. 제네릭을 두 메서드 뒤로 지워야 클라이언트가
  try-with-resources 하나, `onCancel` 등록 하나, catch 연쇄 하나를 유지할 수 있다
- **핸들에는 `toLlmResponse()` 가 없다.** aggregator 는 클라이언트가 소유하고, 클라이언트가 try 밖에서 직접
  `aggregator.toLlmResponse()` 로 끝낸다
- **sink 와 aggregator 는 생성자 상태가 아니라 `openStream` 인자다.** 블로킹 경로에는 둘 다 없고, 두 경로 중 하나에서만
  뜻이 있는 필드는 역참조를 기다리는 `null` 이다

### 2.3 exchange 는 try 앞에서 만들어지고, 아무것도 catch 하지 않는다

- **exchange — 따라서 요청 파라미터 — 는 클라이언트의 try 앞에서 만들어진다.** 파라미터를 만들다 실패하면 매핑되지 않은
  채로 빠져나가는 것이 Chat 경로의 동작이고, 같은 자리에서 만들어야 그 동작이 우연히 좋아지거나 나빠지지 않는다
- **두 exchange 모두 catch 가 없다.** 모든 실패가 클라이언트의 catch 연쇄에 닿고, 이 경로에서 `OpenAIExceptionMapper`
  를 부르는 곳은 그 연쇄뿐이다. 스트림 도중 오류의 분류가 두 번째 엔드포인트에서도 살아남는 것이 약속이 아니라 구조가
  된다

---

## 3. 라우팅 — `supportsReasoningTraceRoundTrip() && isResponsesApiEnabled()`

```java
final String modelName = modelConfig.getName().orElse(config.getModel());
final ModelCapabilities capabilities = capabilitiesFor(modelName);
if (capabilities.supportsReasoningTraceRoundTrip() && config.isResponsesApiEnabled()) { ... }   // → Responses
```

`OpenAILlmClient.exchangeFor` 가 이 결정을 요청마다 한 번 내린다. 두 항은 서로 다른 질문에 답한다.

| 항 | 질문 | 성격 |
|---|---|---|
| `ModelCapabilities.supportsReasoningTraceRoundTrip()` | 이 **모델**이 다음 요청에 되실을 reasoning trace 를 돌려주는가 | 모델 사실. provider 중립 |
| `OpenAIConfig.isResponsesApiEnabled()` | 이 **배포의 엔드포인트**가 `/v1/responses` 를 제공하는가 | 운영 사실. 기본 `true` |

**모델 이름은 요청에서 온다.** `LlmModel` 의 이름이 클라이언트의 설정 모델을 덮어쓰고, 그 이름으로 능력을 조회한다.
이것이 선택을 클라이언트 단위가 아니라 요청 단위로 만든다. 결정 어디에도 모델 이름 문자열은 없다 — 모델 지식은
레지스트리로만 들어온다([`model-capabilities.md`](model-capabilities.md)). 능력을 조회할 수 없는 모델은 `unknown()`
으로 떨어지고 그 답은 `false` 이므로, 설명되지 않은 모델은 새 엔드포인트로 가지 않는다. 레지스트리 조회 자체가
실패해도 같은 결과다(조회 오류의 fail-open 도 model-capabilities.md).

**첫 항은 엔드포인트가 아니라 중립 사실이다.** 그 사실에서 OpenAI 엔드포인트를 추론하는 일은 OpenAI 엔드포인트를 알아도
되는 `aimon-llm-openai` 안에서 한다. Anthropic 클라이언트는 같은 플래그를 "thinking 블록을 되보낸다" 로 읽고
엔드포인트는 바뀌지 않는다.

**둘째 항은 모델 사실이 아니라 배포 스위치다.** OpenAI 호환 게이트웨이 중에는 `/v1/chat/completions` 만 구현하고 실제
모델 이름을 그대로 넘기는 것이 많다. 그런 배포는 `gpt-5.x` 이름을 내장 행으로 해석해 `/v1/responses` 로 보내고 404 를
받는다. `responsesApiEnabled(false)` 는 모델에 대해 거짓말을 하지 않고 그 라우팅만 끄므로, 고쳐진 레지스트리 행은 그
운영자에게도 계속 닿는다. 게이트웨이가 모델 이름을 **바꾸는** 경우는 이름이 `unknown()` 으로 해석되어 애초에 영향이
없다. 기본 재시도 정책은 404 를 재시도하지 않으므로 이 실패는 한 번 크게 난다.

두 항 모두 참이 아니면 요청은 Chat Completions 로 간다. 거기서는 도구와 effort 를 함께 받지 못하는 모델의 effort 를
생략하는 도구 규칙이 다시 적용된다 — 그 규칙의 정본은 [`request-parameters.md`](request-parameters.md) 다.

**provider 이름도 같은 자리에서 한 번 해석된다.** `getProviderName()` 은 재정의할 수 있는 메서드이므로, 요청 팩토리가
어느 저장된 trace 가 자기 것인지 가리는 이름과 exchange 가 돌아온 trace 에 붙이는 이름이 같아야 한다. 규칙 자체는
[`reasoning-traces.md`](reasoning-traces.md) 가 정한다.

---

## 4. 요청 조립

`OpenAIResponsesRequestFactory` 가 `ResponseCreateParams` 를 만든다.

### 4.1 Chat ↔ Responses 매핑

| Chat Completions | Responses |
|---|---|
| `messages` (시스템 메시지가 맨 앞) | `instructions` = 시스템 프롬프트, `input` = 변환된 item 목록(§5) |
| `maxCompletionTokens` | `maxOutputTokens` — `LlmModel` 의 값, 없으면 `OpenAIConfig` 의 값 |
| 최상위 `reasoningEffort(...)` | `reasoning(Reasoning.builder().effort(...))` |
| — | `reasoning.summary` — Chat 에 대응물이 없다(§8) |
| `tools(List<ChatCompletionTool>)` | `tools(List<Tool>)` via `Tool.ofFunction(FunctionTool)` — `strict(false)`(§4.4) |
| `streamOptions.includeUsage` | **대응물 없음.** 이 엔드포인트의 `stream_options` 에는 `include_obfuscation` 만 있다. usage 는 요청하지 않아도 실린다(§9) |
| `temperature` / `top_p` | 같은 둘. **presence · frequency penalty 는 없다** |
| — | `store(false)` + `include(REASONING_ENCRYPTED_CONTENT)`(§4.2) |

샘플링 파라미터는 Chat 경로와 같은 규칙으로 싣는다 — 누군가 값을 넣었을 때만, 생략은 setter 를 부르지 않는 것으로.
두 엔드포인트가 같은 `OpenAiRequestParameters.applySampling` 을 거치므로 서로 어긋날 수 없다. 이 엔드포인트에 없는
두 penalty 는 조용히 사라지지 않고 divergence 로 보고된다. 규칙의 정본은
[`request-parameters.md`](request-parameters.md) 다.

### 4.2 `store(false)` 와 `include(reasoning.encrypted_content)`

**`store(false)` 는 기본값이 아니라 결정이다.** `store: true` 면 서버가 교환을 보관하고 item 을 되싣는 대신
`previous_response_id` 를 쓸 수 있게 한다. 그것은 어떤 `SessionRecord` 도 모르는 두 번째 진실 원천이다 — 세션을 다른
노드에서 재개하는 시스템에서 그렇다. 게다가 요청하지 않은 데이터 보관 변경을 끌어들인다.

**`include(reasoning.encrypted_content)` 는 명시적으로 요청한다.** SDK javadoc 이 그 항목이 필요한 경우로 지목하는 것이
바로 `store: false` 다. `include` 없이도 `encrypted_content` 가 돌아온 관측이 있지만(§9) 항목을 빼지 않는다. 모델 하나의
관측 한 번은 벤더의 문서 계약보다 약하고, 빼서 틀렸을 때의 결과 — reasoning item 이 다음 요청으로 조용히 되실리지
못하는 것 — 는 드러나지 않는 종류다.

`reasoning.summary` 는 `include` 항목을 따로 요구하지 않는다. 이것은 추론이 아니라 측정이다(§9).

### 4.3 도구 규칙은 없고, ladder 검사는 공유한다

**이 경로에는 도구 규칙이 없다.** 도구가 있다고 effort 를 생략하는 것은 Chat Completions 의 규칙이다. 여기서는 도구와
reasoning 이 함께 가는 것이 이 경로의 존재 이유이므로, 설정된 effort 는 그대로 가고 설정되지 않은 요청은 서버 기본값을
받는다.

**ladder 검사는 남는다.** 모델이 어떤 rung 을 받는지는 엔드포인트가 아니라 모델에 대한 사실이므로, 두 엔드포인트가 같은
`OpenAiRequestParameters.maySendEffort` 를 부른다. 도구 규칙과 함께 이 검사까지 빼면 모델이 받지 않는
`reasoning.effort` 가 이 엔드포인트에 400 으로 도착한다. `Reasoning.effort` 는 `supportsReasoningEffort()` 가 거짓이거나
요청 rung 이 모델의 `acceptedReasoningEfforts` 에 없으면 설정하지 않고 보고한다. 어느 모델이 어느 rung 을 받는지는
[`model-capabilities.md`](model-capabilities.md), 생략·보고 규칙은 [`request-parameters.md`](request-parameters.md) 다.

**`reasoning` 객체는 서로 독립인 두 부분에서 만든다** — effort 와 summary(§8). 둘 중 하나라도 실렸을 때만 객체를 설정한다.
effort 가 게이트에서 떨어져도 summary 요청은 살아남고, 그 반대도 같다.

### 4.4 `strict` 는 `false` — 안전해 보이는 선택이 아니라 패리티 선택

`FunctionTool.strict` 는 Responses 빌더에서 **필수**이고 Chat 에서는 **설정하지 않는다**. Chat 에서는 필드가 없으므로
서버의 비엄격 기본값이 적용된다. setter 를 생략하는 선택지는 없다 — `build()` 가 던진다.

**`false` 인 이유는 패리티가 엄격 검증의 부재이기 때문이다.** strict 모드는 JSON Schema 의 부분집합만 받는다(모든 객체에
`additionalProperties: false`, 모든 프로퍼티가 `required`). 깨질 집단은 작지도 가설적이지도 않다. 이 저장소의
`additionalProperties` 규칙은 `at.aimon.core.tools` 에만 걸리고 **MCP 스키마는 명시적으로 면제**된다. MCP 도구는 우리
스키마가 아니라 서버의 스키마를 광고하기 때문이다. `true` 를 쓰면 지금 동작하는 도구가 엔드포인트 전환만으로 서버 거부가
된다.

**`JsonNull` 도 쓰지 않는다.** 서버가 전에 보지 못한 필드는 값이 `null` 이든 `true` 든 동작 변경이다. `false` 는 필드가
없을 때 뜻하던 것을 이 빌더가 받는 유일한 어휘로 말한다.

도구 정의 변환 실패의 `ToolConversionException` 래핑은 **개선 없이** 복제한다 — 도구마다 같은 `log.error` 와 래핑, 같은
타입, 같은 메시지, 두 경로 모두 try 앞에서 던진다.

### 4.5 필수 필드 — 데이터가 정하는 값과 누군가 골라야 하는 값

이 경로가 만드는 모든 SDK 타입의 필수 필드를 읽어, **데이터가 정하는 값**과 **누군가 논증해야 하는 선택**으로 나눈다.
대부분은 데이터가 정한다. 선택인 칸은 아래뿐이고, 각각 결정과 이유가 있다.

| 타입 · 필드 | 결정 | 이유 |
|---|---|---|
| `FunctionTool.strict` | `false` | §4.4 |
| `ResponseInputImage.detail` | `AUTO` | Chat 이 필드를 생략해 사실상 보내는 값이다 |
| assistant 텍스트의 운반체 | `EasyInputMessage` | `ResponseInputItem.Message.Role` 에는 `USER`/`SYSTEM`/`DEVELOPER` 만 있고, 남은 후보(`ofResponseOutputMessage`)는 서버가 배정한 `id` 와 `status` 를 요구한다 |
| 되실은 `ResponseFunctionToolCall` 의 item `id` | 설정하지 않는다 | SDK 에서 선택 필드이고 서버가 배정한다. 지어낸 id 는 생략보다 나쁘다(§5.2) |
| 되실은 `ResponseReasoningItem` 의 `id` · `summary` | 빌더를 거치지 않는다 | 저장된 payload 를 빌더로 재구성하지 않는다 — [`reasoning-traces.md`](reasoning-traces.md) |
| `Reasoning.effort` | 모델의 `acceptedReasoningEfforts` 에 없는 rung 이면 설정하지 않는다 | §4.3 |

---

## 5. 메시지 변환 — Chat 변환기와 경우마다 같다

`OpenAIMessageConverter` 는 전부 Chat 타입을 돌려주므로 두 번째 변환기 `OpenAIResponsesMessageConverter` 가 필요하다.
**Chat 변환기는 열지 않는다.** 열지 않아야 "Chat Completions 는 바뀌지 않는다" 가 약속이 아니라 구조가 된다.

새 변환기를 재는 기준은 하나다 — **엔드포인트를 바꿔도 메시지의 뜻은 바뀌지 않는다. 모든 throw 를 포함해서.**

### 5.1 역할별 · 블록별 매핑

**역할별.**

| 역할 | Responses item |
|---|---|
| `USER` | `ofMessage(role=USER, …)` 하나에 콘텐츠 목록. 텍스트만 있는 경우에도 멀티모달 운반체를 써서 맞춰야 할 두 번째 모양을 만들지 않는다 |
| `ASSISTANT` | reasoning trace 와 호출의 순서 규칙대로(규칙은 [`reasoning-traces.md`](reasoning-traces.md)), 텍스트는 `EasyInputMessage`(§4.5) |
| `TOOL` | 결과마다 `function_call_output` 하나, 같은 순서, `call_id = getToolUseId()`. 실패한 결과는 Chat 과 **같은 `"Error: "` 접두어** |
| 그 밖 | Chat 과 같은 `IllegalArgumentException`, 같은 메시지 |

**콘텐츠 블록별.**

| 블록 | Responses content |
|---|---|
| 텍스트 | `input_text` |
| base64 이미지 | `input_image` — Chat 변환기가 만드는 것과 **같은 `data:` URL** |
| URL 이미지 | `input_image` |
| 텍스트 기반 문서 | `input_text` 로 인라인. `[File: …]` 머리를 붙이고, **파일 이름이 없으면 붙이지 않는다**(Chat 변환기가 접두어를 가드한다) |
| 텍스트가 아닌 문서 | Chat 과 **같은 `MessageConversionException`, 같은 메시지** |
| 모르는 블록 | 같은 throw |

텍스트가 아닌 문서의 throw 는 의도한 것이다. Responses API 에는 PDF 를 실을 수 있는 네이티브 `input_file` 자리가 있지만,
그것을 쓰면 같은 `Message` 가 두 엔드포인트에서 다른 뜻이 되고 어떤 라이브 호출도 확인하지 않은 능력을 주장하게 된다.
문서 블록 거부 규칙 자체는 [`multimodal-content.md`](multimodal-content.md) 가 정하고, 이 경로는 그 규칙을 그대로
따른다.

이 패리티가 막는 실패는 조용하다. `UserInputConverter` 는 사용자 입력마다 첨부 스크린샷을 `ImageContentBlock` 으로
만든다. 텍스트만 다루는 변환기라면 텍스트만 있는 item 을 내고, 모델은 첨부가 없는 것처럼 답하고, 빌드는 초록으로 남는다.

### 5.2 `call_id` 와 `id`

Responses 의 `function_call` 은 item `id`(`fc_…`, SDK 에서 선택)와 `call_id`(`call_…`, 필수)를 **둘 다** 갖는다.
`ToolUse.getId()` 는 **`call_id`** 를 담는다 — `function_call_output` 이 맞춰야 하는 것이 그것이기 때문이다. item `id` 는
되싣기를 건너 **보존하지 않고** 지어내지도 않는다. 원래 item id 없이 되실은 `function_call` 이 수용되는지는 측정했다(§9).

---

## 6. 응답 읽기

블로킹 경로는 `OpenAIResponsesExchange` 가 완성된 `Response` 를, 스트리밍 경로는 `OpenAIResponsesStreamingMapper` 가
`ResponseStreamEvent` 를 읽는다. item 목록을 text · 도구 호출 · reasoning trace 로 나누는 규칙은 두 경로가 같은 한
구현(`OpenAIResponsesMessageConverter` 의 출력 스캔)을 쓴다.

### 6.1 identity 는 던지고, accounting 은 강등한다

읽기는 SDK 의 필수 접근자가 접근 시점에 던지는 구조다. 그래서 어느 필드를 그 접근자로 읽을지를 **필드의 뜻**으로 가른다.

- **identity 는 던진다.** `call_id` 나 `name` 이 없는 `function_call` 은 provider 결함이다. 일반 접근자로 읽고, 그 throw 는
  클라이언트 try 안에서 다른 실패와 똑같이 분류된다
- **accounting 은 강등한다.** 토큰 카운터가 빠졌다고 나머지가 성공한 요청을 실패시키지 않는다. `OpenAiResponseUsages` 는
  `ResponseUsage` 의 모든 층 — 최상위 세 카운터, 그 옆의 details 객체, 그 안의 reasoning 카운터 — 을 raw `JsonField`
  접근자로 읽고 없는 값을 0 으로 센다. `long` → `int` 좁힘 가드는 Chat 경로와 같다
- **예외 하나: 누락된 `total_tokens` 는 0 이 아니라 합이다.** `TokenUsage` 는 `total >= prompt + completion` 을 강제하고
  어기면 던진다. input 과 output 을 보고하고 total 을 뺀 문서에서 total 을 0 으로 두면, 강등이 지키려던 바로 그 호출이
  실패한다

이 분할에 이름을 붙이는 것이 요점이다. 없으면 "raw 접근자를 쓴다" 가 어디에나 적용되어 깨진 도구 호출이 조용한 빈
`ToolUse` 가 된다. `reasoningTokens` 를 비용에 더하지 않는 규칙은 [`reasoning-traces.md`](reasoning-traces.md) 다.

### 6.2 도구 인자 — JSON `null` 은 없는 파라미터, 깨진 JSON 은 Chat 처럼 던진다

- **JSON `null` 인자 값은 깨진 입력이 아니라 없는 파라미터다.** 블로킹 읽기는 파싱한 인자 맵을
  `NullSafeMaps.withoutNullValues` 로 복사한다. `Map.copyOf` 는 `null` 값을 거부하므로, 선택 파라미터를 생략하지 않고
  `null` 로 채운 모델 하나가 요청 전체를 실패시킨다. Chat 변환기는 같은 결과에 `null` 을 `ToolUse` 로 넘기고 거기서
  버리는 길로 닿는다
- **블로킹 경로에서 깨진 인자 JSON 은 던진다** — Chat 변환기와 같은 타입, 같은 메시지로. 빈 맵으로 강등하면 설정 플래그
  하나로 바뀌는 엔드포인트가 같은 provider 출력에 다른 결과를 낸다. 게다가 빈 맵은 작은 답이 아니라 **다른** 답이다 —
  파라미터가 전부 선택인 도구는 모델이 아무것도 요청하지 않았다고 듣고 기본값으로 돈다
- **스트리밍 경로는 도구 인자와 텍스트를 이 변환기로 읽지 않는다.** 거기서는 `ChunkAggregator` 가 인자 델타를 모아
  파싱하고(깨진 JSON 은 경고와 함께 빈 맵으로 강등 — 모든 provider 공통의 스트리밍 규칙), 텍스트 버퍼는
  `response.output_text.delta` 가 채운다. 스트리밍 경로의 스캔(`convertStreamedOutput`)은 reasoning trace 와 도구 호출
  유무만 읽는다. 버려질 값을 다시 만들면 aggregator 가 이미 강등으로 처리한 도구 호출이 깨진 `arguments` 문자열 하나로
  스트림 전체를 죽이게 된다

### 6.3 스트림 — reasoning item 은 `output_item.done` 에서 취한다

- **reasoning item 은 종료 `Response` 가 아니라 `response.output_item.done` 에서 취한다.** SDK 가 `store: false` 에서 완성된
  item 과 `encrypted_content` 를 그 이벤트에서 쓰라고 지목한다. 측정으로 이유가 더 구체적이 되었다 — `.added` 와 `.done`
  의 `encrypted_content` 는 서로 다르고 둘 다 되실으면 수용된다(§9). `.added` 는 item 이 열린 순간의 짧은 봉투다. 그것을
  읽으면 던지지도 400 이 나지도 않고 **더 짧은 trace 를 조용히 되싣는다.** 그래서 `.done` 을 고르는 근거는 수용 여부가
  아니라 내용의 완전성이다
- **`response.output_text.done` 은 무시한다.** 텍스트는 델타로 이미 모였고, 그것까지 읽으면 텍스트가 두 번 센다
- **추론 델타는 reasoning item 과 섞이지 않는다.** 두 delta 계열(§8)은 사람이 보는 텍스트이고 `REASONING_DELTA` chunk 로
  나가며, reasoning trace 에는 절대 들어가지 않는다. 채널 규칙은 [`streaming.md`](streaming.md) 다

### 6.4 stop reason — `status` · `incomplete_details.reason` · 도구 호출 유무

`OpenAiResponseStopReasons` 는 Chat 의 `OpenAiStopReasons` 와 따로 있다. 두 엔드포인트가 어휘를 공유하지 않기 때문이다.
Chat 의 `finish_reason` 은 도구 호출 응답(`tool_calls`)과 일반 응답(`stop`)을 구분하지만, Responses 는 둘 다
`completed` 라고만 하고 구분은 출력 배열에 맡긴다. 그래서 매핑은 세 번째 입력으로 도구 호출 유무를 받는다 — 없으면 도구를
부른 모든 응답이 `END_TURN` 으로 보고되고, `OrcaAgentExecutor` 는 그 값으로 분기한다.

| 입력 | `StopReason` |
|---|---|
| `status` 가 `completed` 이거나 없음 | 도구 호출이 있으면 `TOOL_USE`, 없으면 `END_TURN` |
| `incomplete` + `max_output_tokens` | `MAX_TOKENS` |
| `incomplete` + `content_filter` | `REFUSAL` |
| 그 밖 | `UNKNOWN` |

매핑은 SDK enum 이 아니라 와이어 문자열 위의 순수 함수다 — SDK 버전이 모델링하지 않는 값이 와도 던지지 않는다.
`status` 가 없는 게이트웨이 응답은 실패가 아니라 완료로 관대하게 읽는다. `incomplete` 두 갈래는 실제 응답으로 확인되지
않았다(§12).

### 6.5 reasoning item 이 없는 응답, `encrypted_content` 가 없는 item

reasoning item 은 매 응답에 나오지 않는다 — 모델이 추론할 때만 나온다(§9). 그래서 **item 이 없는 응답은 정상이고 경고하지
않는다.**

경고하는 것은 다른 모양이다. 응답에 reasoning item 이 **있는데 그중 어느 것도** `encrypted_content` 를 싣지 않았으면, 다음
요청에 되실을 것이 없고 기능이 조용히 아무 일도 하지 않는다. 블로킹·스트리밍 두 경로 모두 이 모양을 한 번 보고한다(배포가
`include=reasoning.encrypted_content` 를 지키는지 확인하라는 문구).

---

## 7. 오류와 보존 동작

### 7.1 SDK 가 던지지 않는 오류 — `OpenAiResponseErrors`

두 엔드포인트는 SSE 디코더 하나를 공유하고, 그 디코더는 디코드된 payload 에 **최상위** `"error"` 키가 있을 때만
`SseException` 을 던진다. Chat Completions 의 스트림 도중 실패는 그 모양이므로 `OpenAIExceptionMapper` 의
`SseException` 분기가 거기서는 닿는다. Responses 의 실패 모양은 그렇지 않다.

| 모양 | payload | 최상위 `error`? | SDK 가 던지는가 |
|---|---|---|---|
| `ResponseErrorEvent` (`response.error`) | `{type, code, message, param, sequence_number}` | 없음 | 아니다 — 데이터로 온다 |
| `ResponseFailedEvent` (`response.failed`) | `{type, response:{…, error:{…}}, sequence_number}` | 없음(중첩) | 아니다 |
| 블로킹 `create(...)` 가 200 과 실패 `status` 를 돌려줌 | 평범한 `Response` | 해당 없음 | 아니다 — 정상 반환값 |

실패 `status` 는 `failed` · `cancelled` · `in_progress` · `queued` 넷이다. 스트림의 `response.completed` /
`response.incomplete` 가 싣는 중첩 `response.status` 도 같은 검사를 거친다 — 규격을 따르는 provider 는 그 조건에
`response.failed` 를 보내지만, 틀린 이벤트 타입을 고른 게이트웨이가 실패를 빈 답으로 바꿀 수 있어서는 안 된다.

처리하지 않으면, 블로킹/Chat 경로에서 재시도 가능한 `LlmOverloadedException` 을 내는 서버 조건이 여기서는 **예외를 전혀
내지 않는다.** 던지지 않는 두 방법이 각자 나쁘다.

- **aggregator 를 닫지 않고 두면** try **밖**에서 `IllegalStateException` 으로 빠져나간다 — 매핑되지 않고, provider 의 오류가
  아니라 AIMON 내부 상태를 보고한다
- **닫고 던지지 않으면** 실패가 **조용한 성공**이 되고, 실행기는 그것을 assistant 의 최종 답으로 받아들인다

그래서 `OpenAiResponseErrors` 가 예외를 만들고 exchange 나 매퍼가 **클라이언트 try 안에서** 던진다. 거기서 기존 catch 연쇄가
분류한다 — 두 번째 catch 의 `isCancelled()` 검사까지 포함해서. 그 검사 덕분에 로컬 취소와 겹친 provider 실패는 서버 결함이
아니라 취소로 보고된다. `OpenAiResponseErrors` 는 오류 객체의 필수 필드도 방어적으로 읽는다 — 이 클래스의 일은 provider 실패를
분류된 예외로 바꾸는 것이므로, 깨진 오류 객체가 도중에 `OpenAIInvalidDataException` 을 내어 provider 의 실패를 우리 것으로
바꿔치기해서는 안 된다.

종료 이벤트 없이 깨끗하게 끝난 스트림은 빈 성공 응답이 된다. 드레인 뒤에 aggregator 를 닫는 것이 닫히지 않은 aggregator 가
매핑 없이 빠져나가는 것을 막는데, 그 결과가 이것이다. 이것은 Chat 과 **패리티**다 — `finish_reason` 없는 Chat 스트림도 같다.

### 7.2 재시도 분류 — 기본은 Chat 패리티, 요청 내용 계열만 terminal

- **기본은 정확한 패리티다.** Chat 의 `SseException` 분기가 부르는 `OpenAIExceptionMapper.mapMidStreamError` 가
  `rate_limit_exceeded` 를 rate-limit 예외로, 나머지를 재시도 가능한 overloaded 예외로 바꾼다. 블로킹 경로가 같은 조건에 내는
  결과와 같다
- **의도한 이탈은 요청 내용 계열 하나다.** `ResponseError.Code` 가 열거하는 `invalid_prompt`, `invalid_image*` 묶음,
  `image_too_large`, `bio_policy`, `data_residency_mismatch` 같은 코드는 블로킹 호출이었다면 400 으로 거절되었을 것이다.
  이것을 스트림 도중 매퍼로 보내면 영구히 깨진 요청이 정책이 포기할 때까지 재시도된다. 이 계열은
  `LlmInvalidRequestException` 이 된다
- **규칙은 terminal 목록이 아니라 transient 집합으로 쓴다.** transient 는 `server_error` · `rate_limit_exceeded` ·
  `vector_store_timeout` 셋이고, **SDK 가 모델링하지 않는 코드는 패리티(재시도 가능)를 유지한다.** OpenAI 가 나중에 더하는
  코드는 조용히 재시도 불가가 되지 않고 오늘의 동작을 물려받는다. 틀렸을 때의 피해도 한정된다 — 재시도 가능한 코드가
  terminal 로 잘못 분류되면 그 코드만 재시도를 잃는다

이 분할은 관측된 응답이 아니라 enum 이름에서 추론한 것이다(§12).

### 7.3 보존해야 할 네 동작은 클라이언트에 한 번 있다

구조적 답은 §2 다. 네 동작 모두 `OpenAILlmClient` 에 있고, 어느 것도 exchange 로 옮겨지지 않으며, Responses 경로는 그것을
다시 구현하지 않고 물려받는다. 따로 포팅한 넷보다 강한 답이다 — 옮긴 적이 없으므로 조심스럽게 옮겼는지 물을 것도 없다.

| 보존 동작 | 두 엔드포인트에서 살아남는 이유 |
|---|---|
| **1.** `StreamResponse.close()` 가 abort 레버이고, `onCancel` 은 스트림을 연 **뒤에** 등록하며, 이미 취소된 호출은 연결 전에 빠른 경로로 끝난다 | 빠른 경로는 무엇도 열기 전인 `sendMessageStreaming` 첫머리에 그대로 있다. try-with-resources 가 `OpenAIStreamHandle` 을 쥐고, 그 `close()` 가 같은 스레드 안전·멱등 메서드에 위임한다. `onCancel(handle::close)` 는 try **안**에 있다 |
| **2.** 취소 가능한 비스트리밍 호출은 스트리밍 경로로 우회하고 chunk 는 버리며 usage 는 여전히 받는다 | 우회는 `sendMessage(SystemPromptParts, …, LlmCancellation)` 에서 `cancellation.isSupported()` 로 결정되고, 엔드포인트 선택(`exchangeFor`)은 그 아래에서 일어난다. 엔드포인트와 무관하다 |
| **3.** `SseException` 은 HTTP 200 이 아니라 payload 로 분류한다 | exchange 에 catch 가 없어 SDK 가 실제로 던진 실패는 매퍼가 불리는 유일한 곳인 클라이언트 연쇄에 닿는다. Responses provider 오류는 애초에 SDK 예외가 아니므로 §7.1 이 매퍼가 만들었을 예외를 try 안에서 던진다 |
| **4.** 요청 단위 timeout 은 `RequestOptions` 로 싣고, 설정이 없으면 `null` 을 돌려 SDK 단일 인자 오버로드를 유지한다 | `perRequestOptions` 는 클라이언트에 남고, 두 exchange 가 같은 `options == null ? svc.x(p) : svc.x(p, o)` 한 줄을 갖는다 |

이 네 동작의 성립은 코드와 그 테스트로 확인한 것이지 라이브 호출로 실측한 것이 아니다 — 특히 3 은 스트림 도중의 서버 실패가
필요하다. 백로그 [`RD-9`](../../backlog/reasoning-delta-stream-open-items.md) 가 그 사실을 열어 둔다(그 항목은 1 의 빠른
경로를 따로 세어 다섯으로 적는다). 취소 설계 자체는 [`cancellation.md`](cancellation.md) 다.

---

## 8. reasoning summary 요청 — `supportsReasoningSummary` 게이트

**요청해야만 나온다.** 이 엔드포인트의 reasoning 은 `encrypted_content` — 설계상 암호문 — 이므로, 사람이 읽을 수 있는 유일한
형태는 서버가 쓰는 reasoning summary 다. 그리고 summary 는 요청하지 않으면 0개다(§9). 전달 채널만 있고 요청이 없으면 채널은
언제나 비어 있으므로, 요청 파라미터와 전달 채널을 함께 둔다(함께 두는 결정의 정본은 [`streaming.md`](streaming.md)).

**요청 모양.**

- `OpenAIConfig.reasoningSummary(OpenAiReasoningSummary)` — `AUTO` · `CONCISE` · `DETAILED`. 기본은 미설정(opt-in)이다.
  SDK 의 `Reasoning.Summary` 가 아니라 프레임워크 enum 인 이유는 공개 설정 표면에 SDK 타입을 두면 벤더 SDK 가 우리 API 의
  일부가 되기 때문이다. 설정 키는 [`configuration-surface.md`](configuration-surface.md) 가 정한다
- 요청은 클라이언트 단위(`OpenAIConfig`)이고 모델 이름은 요청 단위다. summary 는 §4.3 의 `reasoning` 객체에 effort 와 독립으로
  들어간다

**게이트.** 요청 팩토리는 summary 를 싣기 전에 `ModelCapabilities.supportsReasoningSummary()` 를 본다. 거짓이면 생략하고 한 번
보고한다 — `maySendEffort` 와 샘플링 생략이 이미 하는 방식이다.

- **게이트가 있어야 하는 이유는 게이트웨이다.** summary 요청을 **무시하는** 서버는 summary 이벤트를 내지 않고 끝나며, 그것은
  키가 설정되지 않은 것과 구별되지 않는 정직한 결과다. 그러나 운영자가 어떤 이름에 `supportsReasoningTraceRoundTrip` 을 참으로
  선언해 이 엔드포인트로 보낸 OpenAI 호환 게이트웨이가 `reasoning.effort` 는 구현하고 `reasoning.summary` 는 **거부하면**
  400 이다 — 그것을 잡았을 두 게이트는 옆 파라미터(effort)에 서 있다
- **플래그가 없을 때의 처방은 둘 다 결함보다 넓다.** `reasoningSummary` 를 끄면 이 클라이언트가 서비스하는 모든 모델에서
  사라지고(요청은 클라이언트 단위다), 그 이름의 `supportsReasoningTraceRoundTrip` 을 거짓으로 선언하면 선택 파라미터 하나를 막으려고 모델을 이
  엔드포인트에서 통째로 빼고 trace 왕복을 잃는다. 모델 단위 레버는 이 플래그뿐이다
- **`supportsReasoningTraceRoundTrip` 에 겹치지 않는다.** 그 플래그는 측정된 사실 — 이 모델이 trace 를 되싣는다 — 을 뜻하고,
  게이트웨이는 두 사실을 독립으로 만족한다. 겹치면 선언된 행 하나가 운영자가 가를 수 없는 두 사실을 주장한다
- **fail-open 값은 `true` 다.** summary 는 누군가 설정했을 때만 요청에 있으므로 보류는 fail-closed 가 된다. 그리고 이 플래그는
  이 엔드포인트에서만 읽히는데, 이 엔드포인트에는 fail-open 값이 `false` 인 `supportsReasoningTraceRoundTrip` 이 참일 때만
  들어오므로 설명되지 않은 모델에 대해서는 애초에 읽히지 않는다. 플래그의 정의와 `unknown()` 값은
  [`model-capabilities.md`](model-capabilities.md) 다

**전달.** 매퍼는 `response.reasoning_summary_text.delta` 와 `response.reasoning_text.delta` **두 계열 모두**를 하나의 게이트
아래 `REASONING_DELTA` 로 흘린다. 원문 계열을 빼면 summary 대신 원문을 내는 모델에서, 추론을 보겠다고 요청한 배포가 아무것도 보지
못한다. 게이트는 델타의 도착이 아니라 `reasoningSummary` 가 설정되었는지다 — `baseUrl` 뒤의 호환 게이트웨이는 요청 없이
summary 이벤트를 보낼 수 있고, 아무것도 설정하지 않은 배포는 달라지는 것이 없어야 한다. 전달 게이트의 정본은
[`streaming.md`](streaming.md) 다.

**Chat 으로 라우팅된 모델.** Chat Completions 에는 `reasoning.summary` 파라미터도 summary 이벤트도 없다. `reasoningSummary` 가
설정되었는데 요청이 Chat 으로 가면, 운영자에게 키가 아무 데도 닿지 않는다는 것을 알릴 곳이 달리 없으므로 클라이언트가 한 번
경고한다. 경고는 라우팅된 원인을 구분한다 — 모델이 trace 왕복을 하지 않는 경우(다른 모델이 필요하다)와
`responsesApiEnabled=false` 인 경우(운영자가 켠 스위치다)의 처방이 다르기 때문이다. 이 두 signature 는 요청이 Chat 으로 갔을 때만,
§8 게이트의 보고는 Responses 로 갔을 때만 발화하므로 서로 겹치지 않는다.

---

## 9. 측정된 결론 — 이 경로의 전제

`api.openai.com` 에 직접 보낸 요청과 `OpenAILlmClient` 를 통한 요청으로 얻었다. 모델별 사실 — 어느 이름이 trace 왕복 행을 갖는가,
rung 집합, `temperature` 값 거부 — 은 내장 표의 근거이므로 [`model-capabilities.md`](model-capabilities.md) 에 있다. 여기에는
경로의 모양이 기대는 결론만 둔다.

| 무엇 | 결과 | 측정 |
|---|---|---|
| `/v1/responses` 가 도구와 reasoning 을 한 요청에 받는가 | 받는다(200) — `gpt-5-nano`, `o4-mini`, `o3-mini`, `o3`, `o1`, `gpt-5.6-terra` | 2026-09-09 |
| 같은 도구 요청을 Chat Completions 로 강제하면 | `gpt-5.6-terra` 에 `responsesApiEnabled(false)` 로 Chat 을 강제하고 `temperature` 도 `reasoning_effort` 도 없이 도구만 실은 요청이 **HTTP 400** — *"Function tools with reasoning_effort are not supported for gpt-5.6-terra in /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'."* 기본 설정(Responses 로 라우팅)의 같은 요청은 수용. 파라미터를 바꾸는 것이 아니라 **라우팅이 수정이다** | 2026-09-10 |
| `store: false` 에서 `encrypted_content` 가 오는가 | 온다 | 2026-09-09 |
| 되실은 reasoning item 을 서버가 소비하는가 | 온전한 item 은 수용, `encrypted_content` 의 40자를 덮어쓴 대조군은 400(*"could not be verified"*) — 수용은 무시가 아니라 검증된 소비다. 손상 대조군은 `o4-mini` · `gpt-5.6-terra` 에서만 돌렸고, 이것이 모델별이 아닌 플랫폼 수준 검사라는 해석은 추론이다 | 2026-09-09 · 2026-09-10 |
| reasoning item 을 빼고 다시 보내면 | 200 — item 은 요청의 개선이지 전제조건이 아니다. 그래서 trace 왕복을 켜도, 되던 요청이 item 부족으로 실패하지 않는다 | 2026-09-09 |
| `call_id` 와 item `id` | 다르다. 캡처한 trace 의 `toolUseId` 가 그 응답의 `call_id` 와 같고, 그 키로 되실은 `function_call_output` 과 item id 없는 `function_call` 이 수용된다 | 2026-09-09 · 2026-09-10 |
| reasoning item 은 매 응답에 나오는가 | 아니다 — `gpt-5.6-terra` 는 추론이 필요 없는 질문에 `reasoning_tokens: 0` 과 `function_call` 만 낸다. item 없는 응답을 되실어도 수용 | 2026-09-09 · 2026-09-10 |
| 스트림 이벤트 | 측정한 다섯 이름이 같은 이벤트 순서를 낸다. 매퍼가 처리해야 하는데 처리하지 않는 이벤트는 없다 | 2026-09-09 |
| `output_item.added` 와 `.done` 의 `encrypted_content` | 다르다(`.added` 가 짧다). 둘 다 되실으면 수용 — `o4-mini` | 2026-09-09 |
| 스트림 종료 `response.completed` 의 `usage` | 요청하지 않아도 실린다 — `o4-mini` 에서만 확인, 다른 이름은 미측정 | 2026-09-09 |
| `reasoning.summary` 와 `include` | summary 는 `include` 없이 온다. `include: ["reasoning.summary"]` 는 유효하지 않은 값으로 400. summary 를 요청하지 않으면 summary 0개 — 비스트리밍, `gpt-5-mini` | 2026-09-10 |
| summary 델타가 스트림으로 오는가 | `response.reasoning_summary_text.delta` 가 도착해 `REASONING_DELTA` 로 sink 에 닿는다 — `gpt-5-mini`. `response.reasoning_text.delta` 는 어느 서버에서도 관측되지 않았다 | 2026-09-10 |
| `reasoning_tokens` 의 크기 | 스트리밍 응답에서 양수이고 `completionTokens` 이하 — 비용에 더하지 않는 회계 규칙의 근거([`reasoning-traces.md`](reasoning-traces.md)) | 2026-09-10 |
| 응답 `reasoning` 객체의 추가 필드 | `gpt-5.6-terra` 는 `context` · `mode` 등의 필드를 에코한다. 파싱은 깨지지 않고 이 경로는 그 객체를 읽지 않는다 — 존재만 측정, 의미는 미측정 | 2026-09-09 |
| stop reason | 도구를 부른 응답은 `TOOL_USE`, 텍스트 응답은 `END_TURN` | 2026-09-10 |

---

## 10. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 형제 `LlmClient`(`OpenAIResponsesLlmClient`) | 엔드포인트는 요청 단위 결정인데 형제는 생성 시점에 고른다. 맞추려면 두 형제를 디스패치하는 세 번째 타입이 필요하다. 그리고 보존 동작 네 개를 복제한다(§2.1) |
| Chat 변환기 `OpenAIMessageConverter` 를 열어 Responses 도 다루게 하기 | 그 클래스는 전부 Chat 타입을 돌려준다. 열지 않아야 "Chat 경로는 바뀌지 않는다" 가 구조가 된다(§5) |
| 중립 타입의 플래그를 `usesResponsesApi` 로 부르기 | provider 중립 타입 안에 벤더 하나의 엔드포인트 이름이 들어간다. 중립 사실에서 엔드포인트를 추론하는 일은 `aimon-llm-openai` 의 몫이다(§3) |
| `store: true` + `previous_response_id` | `SessionRecord` 가 모르는 두 번째 진실 원천이고, 세션을 다른 노드에서 재개하는 시스템과 맞지 않는다. 요청하지 않은 데이터 보관 변경이다(§4.2). 측정이 아니라 구조와 보관을 근거로 기각했다 |
| 관측 한 번을 근거로 `include(reasoning.encrypted_content)` 빼기 | 모델 하나의 관측은 벤더 문서 계약보다 약하고, 틀렸을 때 trace 가 조용히 되실리지 못한다(§4.2) |
| 이 경로에서도 도구 규칙과 함께 ladder 검사 빼기 | rung 은 모델 사실이다. 빼면 받지 않는 `reasoning.effort` 가 400 으로 도착한다(§4.3) |
| `strict: true` | strict 호환이 아닌 스키마 — MCP 스키마를 포함한 우리가 소유하지 않는 집단 — 가 엔드포인트 전환만으로 거부된다(§4.4) |
| `strict` 를 `JsonNull` 로 | 서버가 전에 보지 못한 필드는 값이 `null` 이어도 동작 변경이다(§4.4) |
| assistant 텍스트를 `ofResponseOutputMessage` 로 | 서버가 배정하는 `id` 와 `status` 를 지어내야 한다(§4.5) |
| 네이티브 `input_file` 로 PDF 싣기 | 같은 `Message` 가 두 엔드포인트에서 다른 뜻이 되고, 확인되지 않은 능력을 주장한다(§5.1) |
| 블로킹 경로에서 깨진 인자 JSON 을 빈 맵으로 강등 | 같은 provider 출력에 엔드포인트마다 다른 결과를 낸다. 빈 맵은 작은 답이 아니라 다른 답이다(§6.2) |
| trace 를 `output_item.added` 나 종료 `Response.output()` 에서 취하기 | `.added` 는 더 짧은 trace 를 조용히 되싣는다. SDK 는 `store: false` 에서 `.done` 을 지목한다(§6.3) |
| Responses provider 오류를 로그로만 남기거나 aggregator 를 닫고 던지지 않기 | 앞엣것은 try 밖에서 매핑 없는 `IllegalStateException`, 뒤엣것은 실행기가 최종 답으로 받는 조용한 성공이다(§7.1) |
| 재시도 분류를 terminal 코드 목록으로 쓰기 | OpenAI 가 더하는 코드가 조용히 재시도 불가가 된다. transient 집합 + 미인식은 패리티가 틀렸을 때의 피해를 한정한다(§7.2) |
| summary 에 능력 게이트를 두지 않기 | 요청을 무시하는 서버에는 맞지만 거부하는 게이트웨이에서는 400 이다(§8) |
| summary 게이트를 `supportsReasoningTraceRoundTrip` 에 겹치기 | 측정된 사실 하나에 독립인 두 번째 사실을 얹어, 운영자가 둘을 가를 수 없게 된다(§8) |

---

## 11. 하지 말 것

- **exchange 안에 catch 를 두지 않는다.** 실패가 클라이언트 연쇄에 닿지 않으면 `OpenAIExceptionMapper` 분류와 취소 재분류를
  둘 다 건너뛴다
- **취소 빠른 경로, `onCancel` 등록, catch 연쇄, `perRequestOptions` 를 exchange 로 옮기지 않는다.** 두 엔드포인트가 공유하는
  것을 한 곳에 두는 것이 이 구조의 전부다
- **엔드포인트를 `config.getModel()` 로 고르지 않는다.** 요청의 `LlmModel` 이름으로 고른다. 설정 모델로 고르면 모델을 덮어쓴
  요청이 틀린 엔드포인트로 간다
- **라우팅에 모델 이름 문자열을 쓰지 않는다.** 모델 지식은 레지스트리로만 들어온다
- **`getProviderName()` 을 요청 안에서 두 번 읽지 않는다.** 한 번 해석해 요청 팩토리와 exchange 에 함께 넘긴다
- **필수 필드를 채우려고 값을 지어내지 않는다** — item `id`, `strict` 의 `JsonNull`, 서버가 배정하는 `status` 모두
- **`strict` 를 `true` 로 바꾸지 않는다** — 그것은 이 경로의 부속 변경이 아니라 별도 결정이다(§12)
- **도구 규칙을 없앤다고 `maySendEffort` 까지 빼지 않는다**
- **`include(REASONING_ENCRYPTED_CONTENT)` 를 빼지 않는다.** 한 번의 200 은 계약이 아니다
- **스트리밍 경로에서 도구 인자와 텍스트를 변환기로 다시 읽지 않는다.** 그 둘의 권위는 `ChunkAggregator` 다
- **`response.output_text.done` 을 텍스트로 읽지 않는다.** 텍스트가 두 번 센다
- **summary 텍스트를 reasoning trace 에 넣지 않는다.** 다음 iteration 에 모델로 되실리는 것이 바뀐다
- **Responses provider 오류를 try 밖에서 던지거나, 던지지 않고 스트림을 끝내지 않는다**
- **`supportsReasoningSummary` 가 할 일을 `supportsReasoningTraceRoundTrip` 에 맡기지 않는다**

---

## 12. 남은 것

백로그에 등록된 것.

- **`response.reasoning_text.delta` 가 어느 서버에서도 관측되지 않았다** — 원문 계열 분기가 실제 이벤트 이름과 맞는지 모른다.
  이름이 틀려도 400 이 아니라 빈 채널이다. [`RD-7`](../../backlog/reasoning-delta-stream-open-items.md)
- **`incomplete` stop reason 두 갈래(`max_output_tokens`, `content_filter`)가 실제 응답으로 확인되지 않았다.**
  [`RD-8`](../../backlog/reasoning-delta-stream-open-items.md)
- **보존 동작(§7.3)은 코드로 확인했을 뿐 실측하지 않았다.** [`RD-9`](../../backlog/reasoning-delta-stream-open-items.md)
- **`responsesApiEnabled` 에 설정 표면이 없다.** Chat 전용 게이트웨이 배포는 설정만으로 404 에 닿을 수 있는데 처방은 자바
  전용이다. 이 스위치를 설정으로 내리면 `gpt-5.6-terra` 를 Chat 에 강제하는 조합이 운영자 경로로 내려오고, 그 모델의
  rung 집합은 `/v1/responses` 에서만 측정되었다. [`L-2`](../../backlog/llm-config-surface-open-items.md)

등록되지 않은 것.

- **OpenAI 로 텍스트가 아닌 문서를 보내는 길** — 네이티브 `input_file` 을 포함한 선택지는
  [`multimodal-content.md`](multimodal-content.md) 의 남은 것에 있다. 이 경로가 지금 `input_file` 을 쓰지 않는 이유는 §5.1
- **도구 스키마 `strict: true`** — 도구 호출 정확도를 높이지만 strict 호환이 아닌 스키마를 거부한다. 면제된 집단(MCP 스키마)의
  감사와 별도 결정이 필요하다
- **terminal/transient 분할은 관측이 아니라 `ResponseError.Code` enum 이름에서 추론했다** — 기본 갈래가 패리티라 틀렸을 때의
  피해는 이름이 불린 코드의 재시도 상실로 한정된다
- **`store: true` / `previous_response_id` 대안은 측정하지 않았다** — 요청을 줄일 수 있지만 구조와 보관 근거로 기각했다
- **종료 이벤트 없이 끝난 스트림이 조용한 빈 성공이 된다** — Chat 과 패리티인 현재 동작이고, 강화하면 두 엔드포인트가 함께
  바뀐다

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `OpenAILlmClient.java` | `exchangeFor` 의 라우팅 술어와 provider 이름 1회 해석, `reportInertReasoningSummary` 의 두 signature, `capabilitiesFor` 의 조회 오류 fail-open, `sendMessage(SystemPromptParts, …, LlmCancellation)` 의 스트리밍 우회, `sendMessageStreaming` 의 빠른 경로 · 단일 try · `onCancel` · catch 연쇄, `perRequestOptions` |
| `OpenAIEndpointExchange.java` · `OpenAIStreamHandle.java` | 이음매의 네 갈래, 제네릭 지우기, `openStream` 인자, catch 없음 |
| `OpenAIChatCompletionsExchange.java` · `OpenAIResponsesExchange.java` | 두 구현의 대칭, `null` options 규칙, 블로킹 결과의 실패 `status` 처리, `forwardReasoning` |
| `OpenAIResponsesRequestFactory.java` | `store(false)` · `include`, `applyReasoning` 의 두 부분과 summary 게이트, `ResponsesSamplingSink` 의 penalty 보고 |
| `OpenAIResponsesMessageConverter.java` | 역할·블록별 패리티, `EasyInputMessage`, `detail(AUTO)`, `strict(false)`, item id 미설정, `parseArguments` 의 `NullSafeMaps`, `convertOutput` / `convertStreamedOutput` |
| `OpenAIResponsesStreamingMapper.java` | `output_item.done` 수집, 두 delta 계열 게이트, 실패 이벤트와 중첩 `status` 의 throw, `encrypted_content` 없는 item 경고 |
| `OpenAiResponseErrors.java` | 세 실패 모양, 실패 `status` 넷, transient 집합과 미인식 코드 패리티 |
| `OpenAiResponseUsages.java` | raw 접근자 강등, 누락 `total_tokens` 의 합 |
| `OpenAiResponseStopReasons.java` | `status` · `incomplete_details.reason` · 도구 호출 유무 매핑 |
| `OpenAiRequestParameters.java` | 두 엔드포인트가 공유하는 `applySampling` · `maySendEffort` |
| `OpenAIExceptionMapper.java` | `SseException` 분기, `mapMidStreamError` |
| `OpenAIConfig.java` · `OpenAiReasoningSummary.java` | `responsesApiEnabled`(기본 `true`)의 뜻, `reasoningSummary` 의 뜻과 enum |
| `aimon-core` `llm/capability/ModelCapabilities.java` | `supportsReasoningTraceRoundTrip`(fail-open `false`), `supportsReasoningSummary`(fail-open `true`) |
| `aimon-core` `base/NullSafeMaps.java` | `withoutNullValues` |
| `OpenAIReasoningLiveTest.java` (테스트) | §9 결론 일부를 키가 있는 환경에서 다시 단언하는 라이브 테스트 |

경로는 `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/` 기준(테스트는 `src/test/…`). `aimon-core` 파일은
`modules/aimon-core/src/main/java/at/aimon/core/` 기준.

---

## 관련 문서

- [`reasoning-traces.md`](reasoning-traces.md) — 이 경로가 캡처하고 되싣는 `ReasoningTrace` 의 슬롯·영속·순서 규칙
- [`request-parameters.md`](request-parameters.md) — 샘플링 생략, effort ladder, Chat 도구 규칙, divergence 보고
- [`model-capabilities.md`](model-capabilities.md) — 라우팅과 게이트가 읽는 플래그, 내장 표와 모델별 측정
- [`streaming.md`](streaming.md) — `REASONING_DELTA` 채널과 전달 게이트
- [`cancellation.md`](cancellation.md) — abort 레버와 비스트리밍 우회
- [`multimodal-content.md`](multimodal-content.md) — 콘텐츠 블록과 문서 거부 규칙
- [`configuration-surface.md`](configuration-surface.md) — `reasoningSummary` 키와 네임스페이스
- [`../../backlog/reasoning-delta-stream-open-items.md`](../../backlog/reasoning-delta-stream-open-items.md) — RD-7 · RD-8 · RD-9
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — L-2
- [LLM Provider 개발 가이드](../../features/llm/llm-provider-development-guide.md) — provider 가 슬롯을 채우고 읽는 방법
- [`.claude/rules/llm-provider.md`](../../../.claude/rules/llm-provider.md) — provider SDK 타입 비노출 규칙
