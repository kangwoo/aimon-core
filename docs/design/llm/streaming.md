# 부분 텍스트 스트리밍 (Partial Text Streaming)

> Status: **IMPLEMENTED** — provider 중립 chunk 네 종류, 공유 누적기와 두 버퍼, 게이트웨이의 시도 격리와
> reset 통지, 실행기 이벤트와 중단 시 프리픽스 보존, `wasStreamed` 이중 출력 회피, 설정으로 게이트되는
> 추론 델타 채널이 들어가 있다. 남은 것은 §12.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm.streaming`(`LlmStreamChunk` · `LlmStreamSink` ·
> `ChunkAggregator` · `LlmStreamingOptions` · `BufferingStreamSink` · `LlmStreamTarget` ·
> `StreamingRetryListener`), `at.aimon.core.llm.invoke`(`LlmCallGateway`), `at.aimon.core.agent.stream`
> (`AssistantTextDelta` · `AssistantReasoningDelta` · `AssistantTextStreamReset` · `AssistantTextStreamCompleted`),
> `at.aimon.core.agent.impl.orca`(`OrcaAgentExecutor` 의 `StreamingEventSink`) · `aimon-llm-openai` ·
> `aimon-llm-anthropic`(스트리밍 매퍼) · `aimon-session-routing`(`wasStreamed` · 이벤트 코덱) ·
> `aimon-cli`(`OutputFormatter`, `--streaming`)

---

## 1. 문제와 범위

완성된 `LlmResponse` 만 돌려주는 호출로는 첫 토큰 생성부터 응답 완료까지의 침묵이 사용자에게 그대로
노출된다. 스트리밍의 목표는 **TTFT(Time To First Token) 단축** 하나이며, 그것을 provider 종류와 무관한
추상화로 노출해 REPL · SDK 소비자가 같은 이벤트를 구독하게 한다.

추론 모델에서는 이 침묵이 더 길다. 모델은 보이는 토큰을 내기 전에 수십 초 동안 숙고할 수 있고, 그동안
아무것도 보이지 않으면 hang 과 구분되지 않는다. 그래서 같은 전송 위에 **숙고를 답과 구별해 나르는
채널**을 둔다(§5). 이 채널이 절대 해서는 안 되는 일은 모델의 사적 숙고를 모델의 공개 답으로 전사에
커밋하는 것이다.

스트리밍은 두 가지를 동시에 지킨다.

- **재시도해도 화면이 섞이지 않는다** — 폐기된 시도의 텍스트는 reset 이벤트로 정리된다(§6)
- **중단해도 대화가 조각나지 않는다** — 사용자가 이미 본 프리픽스가 전사에 남는다(§7)

**비목표.**

- **tool_use 인자(JSON)의 부분 노출** — 부분 JSON 은 파싱할 수 없으므로 호출자에게 내보내지 않는다. 인자는
  `STREAM_END` 에서 한 번 파싱한다(§4.2)
- **backpressure** — sink 는 좁은 콜백 하나다. 느린 소비자를 위한 흐름 제어는 두지 않는다
- **스트림 자체의 분산** — 스트리밍은 노드 로컬 단일 호출이다. 노드 경계를 넘는 것은 스트림이 아니라 그
  결과로 발행된 이벤트 프레임과 결과의 한 비트(`wasStreamed`)다(§5.5, §8.1)
- **citation 델타** — Anthropic 매퍼는 citation 델타를 chunk 로 모델링하지 않는다

---

## 2. 계층 — chunk 는 아래, 이벤트는 위

```
aimon-cli (REPL)              OutputFormatter — delta 누적 출력, reset 시 배너, completed 시 줄바꿈,
                              숙고는 흐리게, wasStreamed 이면 최종 답 인쇄 생략
        ▲ AgentExecutionEvent
aimon-core (agent)            OrcaAgentExecutor.StreamingEventSink
                              chunk → AssistantTextDelta / AssistantReasoningDelta / …Reset / …Completed
        ▲ LlmStreamChunk (provider 중립)
aimon-core (llm)              LlmCallGateway — 시도 격리 · 재시도 통지 · buffering 옵션
                              at.aimon.core.llm.streaming — chunk · sink · aggregator · target
        ▲
provider 모듈                 {OpenAI,Anthropic} 스트리밍 매퍼 — SDK 스트림 → chunk 정규화
```

패키지가 `llm.streaming`(전송 계층)과 `agent.stream`(이벤트 계층)으로 갈린다. 두 계층의 책임이 다르다는
것을 이름에 박아 두기 위해서다 — 전송 계층은 한 LLM 호출의 조각을 나르고, 이벤트 계층은 실행이 무엇을
했는지를 알린다.

계층 전체가 따르는 원칙은 넷이다.

- **provider 중립** — chunk · sink · aggregator 추상화는 `aimon-core` 에 있다. OpenAI · Anthropic SDK 타입은
  provider 모듈 밖으로 나가지 않으며, 각 provider 모듈의 ArchUnit 테스트(`OpenAIArchitectureTest`,
  `AnthropicArchitectureTest`)가 이것을 강제한다
- **외부 의존 0** — Reactor · RxJava 같은 reactive 스택을 들이지 않는다. 좁은 콜백 인터페이스 하나로
  충분하고, 나중에 `Flow.Publisher` 로 옮길 여지는 인터페이스를 좁게 유지하는 것으로 남긴다
- **TTFT 우선** — buffering 은 기본 off 다. 재시도 시 화면 정리는 buffering 이 아니라 reset 이벤트로
  푼다(§6)
- **opt-in 확장** — 스트리밍은 `LlmClient` 의 `default` 오버로드로 얹힌다. 스트리밍을 구현하지 않은
  provider 는 비스트리밍 결과를 텍스트 chunk 하나와 `STREAM_END` 로 흘려보낸다. `default` 오버로드와
  데코레이터의 전달 의무는 [`cancellation.md`](cancellation.md) 가 정본이다

**provider 매퍼의 책임**은 셋이다 — 빈 delta 를 사전에 버리고, tool_call 인자 조각을 aggregator 로
조립하고, usage 를 추출한다. 매퍼 쪽 `LlmStreamChunk` 의 `index` 는 "이 스트림의 n 번째 chunk" 이며 텍스트와
추론 chunk 가 한 서수를 공유한다. `TOOL_USE_READY` 는 예외로 provider 의 블록(슬롯) 인덱스를 싣는다. 이
인덱스는 실행기 이벤트의 `chunkIndex` 와 다른 카운터다(§7.1).

---

## 3. `LlmStreamChunk` — 네 kind 와 생성 시점 불변식

| kind | 싣는 것 | 규칙 |
|---|---|---|
| `TEXT_DELTA` | 비어 있지 않은 `textDelta` | 빈 delta 는 provider 매퍼가 미리 버린다 |
| `REASONING_DELTA` | 비어 있지 않은 `reasoningDelta` | 모델의 **숙고**이지 답이 아니다. 배포가 요청했을 때만 나온다(§5) |
| `TOOL_USE_READY` | 완성된 `ToolUse` 1개 | **advisory** — 스트림 도중 tool_use 블록 하나의 인자가 다 모였음을 알린다 |
| `STREAM_END` | 누적 `TokenUsage` · `finishReason` · 중립 `StopReason` | sink 수명당 **정확히 1회** |

**kind 사이의 필드는 배타적이다.** 각 kind 는 자기 필드만 싣고 다른 kind 의 필드(`textDelta` ·
`reasoningDelta` · `toolUse`)를 싣지 않는다. 이 규칙은 문서의 약속이 아니라 생성자의 `switch` 가
`IllegalArgumentException` 으로 강제하며, 배타는 양방향이다 — `TEXT_DELTA` 가 `reasoningDelta` 를 거절하고
`REASONING_DELTA` 가 `textDelta` 를 거절한다.

`REASONING_DELTA` 가 `textDelta` 필드를 재사용하지 않고 자기 필드와 팩토리(`reasoningDelta(int, String)`)를
갖는 것이 추론 채널 설계의 출발점이다. 필드를 재사용하면 kind 를 확인하지 않고 `getTextDelta()` 를 읽는
기존 호출자가 전부 숙고를 답으로 읽게 된다. 그 결과가 왜 렌더링 사고가 아니라 프라이버시 사고인지는
§5.2 에 있다.

**`TOOL_USE_READY` 는 advisory 다.** 최종 응답의 tool uses 는 여전히 `ChunkAggregator.toLlmResponse()` 가 만든
것이고, 이 chunk 는 그것과 겹칠 뿐 대체하지 않는다. 스트림이 아직 흐르는 동안 완성된 도구 하나를 먼저 실행에
넘기는 스트리밍-도구 중첩(eager dispatch)이 이 chunk 를 소비한다. 세 스트리밍 매퍼(OpenAI Chat Completions ·
OpenAI Responses · Anthropic)가 내보내고, `LlmClient` 의 비스트리밍 폴백은 내보내지 않는다. 중첩 자체의 설계는
[`../agent-execution/orca-executor.md`](../agent-execution/orca-executor.md) 와
[`../tool/parallel-execution.md`](../tool/parallel-execution.md) 소관이며, 이 문서는 그것을 실어 나르는 chunk
종류까지만 다룬다.

**`STREAM_END` 팩토리는 둘이다.** 3-인자는 provider 문자열 `finishReason` 만 싣고(`StopReason.UNKNOWN`),
4-인자는 중립 `StopReason` 을 더한다. provider 마다 다른 종료 사유 문자열을 호출자가 문자열 비교로 해석하지
않게 하기 위해서다.

---

## 4. `LlmStreamSink` 와 `ChunkAggregator` — 좁은 콜백, 공유되는 누적기

### 4.1 `LlmStreamSink` — 메서드 하나

```java
@FunctionalInterface
public interface LlmStreamSink {
    void accept(LlmStreamChunk chunk);
    static LlmStreamSink discarding() { … }
}
```

메서드가 하나뿐이라 데코레이터 합성이 자유롭다 — `BufferingStreamSink`, 게이트웨이 내부의 시도 인지 래퍼,
실행기의 `StreamingEventSink` 가 전부 같은 인터페이스를 구현한다. sink 에 누적 책임까지 얹으면 인터페이스가
비대해지고 데코레이터마다 그 책임을 다시 구현해야 한다.

**수명 계약.** `TEXT_DELTA` 0회 이상(배포가 요청했다면 `REASONING_DELTA` 가 그 사이에 섞인다) → 성공한
스트림마다 `STREAM_END` 정확히 1회. **provider 오류에서는 스트리밍 호출 자체가 던지고 sink 는 `STREAM_END` 를
받지 못한다.** 텍스트가 이미 흐른 시도라면 UI 가 "시작했는데 끝나지 않은 스트림" 을 보지 않도록 실행기가 합성 종료
이벤트를 낸다(§7.2).

`LlmStreamSink.discarding()` 은 모든 chunk 를 버린다. 취소 가능한 비스트리밍 호출이 abort 레버를 얻으려고
스트리밍 경로를 탈 때 쓴다([`cancellation.md`](cancellation.md)). 그 경로에서도 추론 chunk 는 만들어졌다가
버려진다 — 정상이다.

### 4.2 `ChunkAggregator` — provider 안에 숨기지 않는다

```java
public void accept(LlmStreamChunk chunk);
public void appendToolCallDelta(int toolCallIndex, String id, String name, String argumentsFragment);
public void addReasoningTrace(ReasoningTrace trace);
public String peekText();                        // 누적된 답 텍스트 — 중단 시 프리픽스 보존용
public String peekReasoningText();               // 누적된 숙고 — toLlmResponse() 가 읽지 않는다
public Optional<ToolUse> finalizeToolCall(int toolCallIndex);   // eager dispatch 용
public LlmResponse toLlmResponse();              // STREAM_END 이후에만 유효
```

provider 매퍼가 호출하지만 **실행기도 자기 인스턴스를 들고 같은 chunk 를 먹인다.** 중단 시 사용자가 이미
화면에서 본 텍스트를 전사에 남기려면 누적 결과에 접근할 수 있어야 하고, aggregator 를 provider 안에
캡슐화하면 그 경로가 아예 없다. 캡슐화를 조금 포기하고 얻는 것이 **중단해도 대화가 조각나지 않는다**는
성질이다.

tool_call 인자 조각은 버퍼에 누적했다가 **`STREAM_END` 에서 1회 파싱**한다. 부분 JSON 은 파싱할 수 없으므로
호출자에게 노출할 의미가 없다. `finalizeToolCall(idx)` 만 예외이며, "그 인덱스의 tool_call 은 이미
완결됐다" 가 확정된 시점에만 쓰이는 eager dispatch 전용 창구다. 깨진 인자 JSON 을 빈 맵으로 관대하게
처리하는 동작과 그것이 취소 라우팅에 주는 영향은 [`cancellation.md`](cancellation.md) 에 있다.

aggregator 는 **버퍼를 둘** 갖는다 — `TEXT_DELTA` 만 쌓는 `textBuffer`(`peekText()` · `toLlmResponse()` 가
읽는다)와 `REASONING_DELTA` 만 쌓는 `reasoningBuffer`(`peekReasoningText()` 만 읽는다). 이 분리가 추론 채널의
불변식이다(§5.2). provider 가 캡처하는 reasoning trace 는 `addReasoningTrace` 로 들어가며, 그 규칙은
[`reasoning-traces.md`](reasoning-traces.md) 가 정본이다.

---

## 5. 추론 델타 채널 — 숙고는 답이 아니다

### 5.1 요청과 전송을 함께 둔다

사람이 모델의 숙고를 보려면 두 가지가 함께 있어야 한다.

| 쪽 | 무엇 | 없으면 |
|---|---|---|
| **요청(ask)** | provider 에게 읽을 수 있는 숙고를 달라는 opt-in — OpenAI Responses 의 `reasoning.summary`, Anthropic adaptive 모양의 `thinking.display` | 채널이 빈다. OpenAI 는 턴을 건너는 payload 가 암호문이고, Anthropic 의 새 세대는 thinking 텍스트를 기본으로 생략한다 |
| **전송(transport)** | 숙고를 답과 구별해 나르는 chunk kind(`REASONING_DELTA`)와 이벤트(`AssistantReasoningDelta`) | 요청한 텍스트가 매퍼에서 버려진다 |

그래서 둘을 한 기능으로 둔다. 요청은 두 provider 모두 **opt-in 이고 기본은 끈다** — 렌더되지 않는 텍스트에
출력 토큰을 쓰지 않기 위해서다. 요청 쪽의 모양과 게이트는 각 provider 문서가 정본이다 — `reasoning.summary`
와 `supportsReasoningSummary` 게이트는 [`openai-responses-path.md`](openai-responses-path.md), `display` 가
adaptive 모양에만 붙는 규칙은 [`anthropic-thinking.md`](anthropic-thinking.md). 두 키(`llm.openai.reasoningSummary`,
`llm.anthropic.thinkingDisplay` 와 그 스타터 대응)가 벤더 네임스페이스에 있는 이유는
[`configuration-surface.md`](configuration-surface.md) 에 있다.

요청하면 채널은 실제로 비지 않는다 — §5.7 의 측정 결론.

### 5.2 별도 kind · 별도 버퍼 — 프라이버시 불변식

**`REASONING_DELTA` 는 `textBuffer` 에 닿지 않는다.** `peekText()` 는 스트림 중간 취소 때 실행기가
`Message.assistant(partial, [])` 로 전사에 커밋하는 값이다(§7.2). 숙고가 거기에 섞이면 중단된 실행이 모델의
사적 숙고를 공개 답으로 영속하고, 그 메시지는 다른 메시지처럼 전사 코덱을 지나므로 되돌릴 수 없다. 그래서
이것은 렌더링 규칙이 아니라 프라이버시 불변식이다.

스트리밍 iteration 동안 aggregator 인스턴스는 둘 살아 있고, 누출은 둘 모두에서 다른 사고가 된다.

| 인스턴스 | 만드는 쪽 | 읽는 쪽 | `textBuffer` 로 샜다면 |
|---|---|---|---|
| provider 의 것 | provider 클라이언트 | `toLlmResponse()` | 숙고가 `LlmResponse` 의 답 텍스트가 된다 — 렌더되고, 영속되고, 다음 iteration 에 모델에게 되실린다 |
| 실행기의 것 | `StreamingEventSink` | `peekText()` | 스트림 중간 취소에서 숙고가 assistant 메시지로 전사에 커밋된다 |

두 번째 사고는 **스트림 중간 취소 경로에서만 관측된다** — 정상 경로에서는 이 불변식이 보이지 않는다. 한
클래스(`ChunkAggregator`)의 두 버퍼 분리가 두 인스턴스를 함께 막는다.

`reasoningBuffer` 는 `textBuffer` 처럼 상한이 없다. 숙고 요약은 답과 같은 크기 규모이고 버퍼는 한 시도만
살며, 상한을 두면 절단 표지와 "잘린 숙고가 무엇을 뜻하는가" 라는 규칙이 측정된 양이 없는 경로에 붙는다.

`peekReasoningText()` 에는 **프로덕션 독자가 없다.** 이것은 비용으로 인정하고 유지한다 — 불변식을 음성
단언(`peekText()` 에 숙고가 없다)만이 아니라 양성 단언(숙고는 저쪽 버퍼에 있다)으로도 확인할 수 있게 하고,
"중단했을 때 무엇을 생각하고 있었나" 같은 장래 기능이 쓸 이음매이기 때문이다.

### 5.3 전달은 설정으로 게이트한다 — delta 의 도착이 아니다

**매퍼가 `REASONING_DELTA` 를 내보낼지는 배포의 설정이 정한다.** delta 가 도착했다는 사실로 정하지 않는다.

- Anthropic **budgeted** 방언(`thinkingMode: extended`)에서는 `thinking_delta` 가 요청 없이도 이미 도착한다.
  "도착하는 것을 전달한다" 로 배선하면 기존의 모든 extended 배포가 업그레이드만으로 숙고 스트림을 켜게 된다
- OpenAI 는 요청했을 때만 요약 이벤트를 보내지만, `baseUrl` 뒤의 OpenAI 호환 게이트웨이는 요청 없이 보낼 수
  있다

아무것도 설정하지 않은 배포는 아무것도 바뀌지 않아야 한다. 그래서 게이트는 **요청 키의 존재**다 — OpenAI
Responses 경로는 `OpenAIConfig.getReasoningSummary().isPresent()`, Anthropic 은
`AnthropicConfig.getThinkingDisplay().isPresent()` 를 매퍼 생성 시 넘긴다. 값이 아니라 존재로 여는 이유와
그것이 `AnthropicThinkingDisplay` 의 상수 집합에 주는 제약은 [`anthropic-thinking.md`](anthropic-thinking.md) 에
있다.

provider 별로 게이트가 서는 자리:

- **OpenAI Responses** — 추론 텍스트를 싣는 두 SDK 이벤트 계열 `response.reasoning_summary_text.delta` 와
  `response.reasoning_text.delta` 를 **한 게이트 아래 모두** 전달한다. 첫째만 전달하면 원문 추론을 내는 모델에서
  숙고를 보겠다고 한 배포가 아무것도 보지 못한다 — 이 채널이 없애려는 빈 채널이 모델 단위로 되살아난다. 매퍼의
  추론 전달은 **`addReasoningTrace` 를 건드리지 않는다.** 이 경로의 trace 는 reasoning item 의
  `encrypted_content` 이고, 요약을 trace 에 넣으면 다음 iteration 에 모델에게 되실리는 것이 바뀐다
- **OpenAI Chat Completions** — 추론 요약 이벤트 계열이 없으므로 `REASONING_DELTA` 를 내지 않는다. 요약 키를
  설정했는데 모델이 이 경로로 라우팅될 때의 경고는 [`openai-responses-path.md`](openai-responses-path.md) 에 있다
- **Anthropic** — `thinking_delta` 하나에 **소비자가 둘, 이 순서로** 있다. 첫째는 trace 왕복을 위한 thinking
  블록 누적이고 게이트와 무관하게 늘 돈다([`reasoning-traces.md`](reasoning-traces.md)). 둘째는 사람이 보는
  채널이며 게이트가 열렸을 때만 돈다. 두 기능은 여전히 다르다 — 서명된 블록을 다음 요청에 되싣는 것은
  사람에게 텍스트를 보여 주는 것이 아니다. `signature_delta` 는 전달하지 않는다. 사람이 보는 텍스트가 아니라
  다음 요청이 되싣는 불투명 자격이기 때문이다

### 5.4 reasoning delta 는 `ReasoningTrace` 가 아니다 — 수명

두 이름은 둘 다 "추론" 이지만 답하는 질문이 다르다.

| | reasoning delta | reasoning trace |
|---|---|---|
| 무엇 | 사람이 보는 숙고 텍스트 조각 | 다음 요청에 되실어야 하는 provider 소유 불투명 payload |
| 싣는 곳 | `REASONING_DELTA` chunk → `AssistantReasoningDelta` 이벤트 | `Message` 의 `ReasoningTrace` 리스트 |
| 영속 | 하지 않는다 | 전사 코덱이 영속한다 |
| 정본 | 이 문서 | [`reasoning-traces.md`](reasoning-traces.md) |

둘을 합치면 모델에게 되실리는 것이 바뀐다. Anthropic 에서 trace 는 서명된 블록이고 수정하면 거절되며, OpenAI
에서 trace 는 `encrypted_content` 이고 요약은 그 대체물이 아니다.

추론 채널이 새로 만드는 상태는 전부 **실행(execution)에 속하고 `SessionRecord` 에 닿지 않는다.**

| 상태 | 수명 | 사는 곳 |
|---|---|---|
| `reasoningBuffer` | 스트리밍 시도 1회 | `ChunkAggregator` — 재시도 시 aggregator 와 함께 버려진다 |
| 추론 chunk index | 스트리밍 시도 1회 | `StreamingEventSink` — `onRetry` 에서 텍스트 index 와 함께 리셋 |
| `AssistantReasoningDelta` | iteration 1회 | 어디에도 — 발행되고 잊힌다 |
| 추론 줄 열림 상태 | REPL 렌더 실행 | `OutputFormatter`(§8.2) |

`SessionRecord` 와 `LiveSession` 의 비대칭이 물 수 있었던 유일한 자리가 스트림 중간 취소의 프리픽스 보존이며,
§5.2 의 불변식이 그 자리를 막는다.

### 5.5 노드 경계와 드롭

**`AssistantReasoningDelta` 는 노드 경계를 넘는다.** 다른 노드의 웹 UI 같은 원격 구독자가 바로 숙고를 보고
싶어 하는 주체이고, 로컬 fan-out 으로만 제한하면 이 기능이 이유 없이 CLI 전용이 된다. 이벤트는
`AgentExecutionEventPayload` 가 텍스트 delta 와 같은 모양의 프레임으로 평평하게 만든다.

그 대가로 숙고는 답 텍스트와 **같은 노출**을 갖는다 — 공유 `SessionSignalBus`(Redis · Postgres · Mongo)를
`AssistantTextDelta` 와 똑같이 지나고, 어느 백엔드에서도 페이로드는 암호화되지 않는다. 숙고가 답보다 민감한
배포는 요청 키를 비워 두어 이 채널 자체를 거절한다.

원격 버퍼가 넘칠 때 추론 delta 는 **가장 먼저 희생된다**(추론 < 텍스트 < 구조 프레임). 등급과 그 이유, 그리고
"둘 다 버릴 수 있다" 로 넓힌 boolean 이 왜 틀린지는
[`../session/routing.md` §5.5.1](../session/routing.md#551-프로듀서는-절대-블록하지-않는다) 가 정본이다.

롤링 배포 중 옛 노드는 모르는 프레임 타입을 건너뛴다. 섞인 버전의 클러스터에서 옛 노드는 추론 프레임만 잃고
그 밖에는 바뀌지 않는다 — 드롭 정책이 압박 아래에서 내는 결과와 같다.

### 5.6 설정 키를 두지 않는 두 자리

**중립 우산 키(예: `llm.streamReasoning`)를 두지 않는다.** 네임스페이스 규칙([`configuration-surface.md`](configuration-surface.md))
으로 판정하면 이 키는 공유 네임스페이스에 속한다 — 이름에 벤더 개념이 없고, "이 배포가 숙고 텍스트를 받아야
하는가" 라는 질문은 두 벤더에게 같다(돌아오는 충실도가 다를 뿐이다). 그래도 싣지 않는 이유는 규칙이 아니라
비용이다.

1. **벤더 키 둘 옆에 두면** 사용자 개념이 둘이 되고, 둘을 함께 적었을 때의 우선순위라는 세 번째 규칙이 생긴다
2. **벤더 키 둘 대신 두면** boolean 이 `concise` · `detailed` 같은 입도를 표현하지 못하고, 출하된 boolean 설정
   키를 나중에 enum 으로 넓히는 것은 깨지는 타입 변경이다

다시 볼 조건은 백로그 RD-4 에 있다.

**에이전트 frontmatter 에 두지 않는다.** 에이전트 정의는 에이전트를 서술하고, 터미널이 숙고를 렌더하는지는
그 에이전트를 돌리는 배포를 서술한다. 같은 정의 파일을 CLI 사용자, 콘솔 없는 Spring 서비스, 스케줄 루틴이 함께
읽는다. 반대 논거도 실재한다 — 요청은 매 요청 출력 토큰을 쓰고, 토큰 비용은 모델 동작 노브이므로 frontmatter
의 소관이기도 하다. 결정적이지 않은 이유는 그 비용이 에이전트 설계가 아니라 배포의 렌더링 선택의 결과이기
때문이다. 결정 항목으로 백로그 RD-6 에 있다.

### 5.7 측정 결론

| 무엇 | 결과 | 측정 날짜 |
|---|---|---|
| Anthropic adaptive `thinking` + `display: summarized`, 숙고할 만한 프롬프트 | thinking 텍스트가 실제로 온다 — 요청하면 채널은 비지 않는다 | 2026-09-10 |
| 스트리밍 숙고 이벤트 이름 — Anthropic | `content_block_delta` 안에 `thinking_delta` · `signature_delta` 가 온다 | 2026-09-10 |
| 스트리밍 숙고 이벤트 이름 — OpenAI Responses | `response.reasoning_summary_text.delta` 가 온다 | 2026-09-10 |
| `response.reasoning_text.delta` | 어느 서버에서도 관측되지 않았다 — 그것을 내는 모델을 위해 같은 게이트로 전달한다(RD-7) | 미측정 |

매퍼는 이벤트 이름 문자열이 아니라 이 와이어 타입에 대응하는 SDK 술어(`isThinking()` · `isSignature()` ·
`isReasoningSummaryTextDelta()`)로 분기하므로, 위 결론은 매퍼가 읽는 이벤트가 실제로 도착한다는 뜻이다.

---

## 6. 게이트웨이 — 재시도와 스트리밍의 충돌

스트리밍 도중 5xx 가 나면 화면에는 이미 시도 1의 텍스트가 남아 있다. 시도 2를 그 위에 이어 쓰면 글이 섞인다.

| 대안 | TTFT | 결정 |
|---|---|---|
| **직결 + reset 통지** — 시도 1을 그대로 흘려보내고, 폐기 시 호출자에게 알려 화면을 정리하게 한다 | 유지 | **기본** |
| **buffering flush** — 시도별로 `BufferingStreamSink` 에 격리하고 성공한 시도만 한 번에 내보낸다 | 상실 | **옵션** (`bufferUntilFirstSuccess=true`) |
| 첫 시도만 스트리밍하고 재시도는 비스트리밍 | 부분 유지 | 기각 — 호출자가 보는 동작이 시도마다 달라진다 |

buffering 을 기본으로 하면 응답 완료 시점에야 화면에 뜬다 — 스트리밍을 둔 이유 자체가 사라진다. 게다가 기본
재시도 정책은 보수적이어서(한 호출에 최대 세 번 시도, 재시도 대상은 rate limit 과 overload 뿐) reset 은 드물다.
드문 사건의 처리 부담을 UI 에 넘기고 흔한 경로의 이득을 지킨다.

**`StreamingRetryListener` — 폐기 통지를 타입으로.** 통지는 익명 콜백이 아니라 인터페이스다.
`onRetry(previousAttempt, nextAttempt, reason)` 은 폐기된 시도마다 정확히 1회, **다음 시도의 chunk 가 하나도
배달되기 전에** 호출된다. 이 순서가 계약이다 — 어기면 UI 가 새 텍스트를 그린 뒤에 화면을 지운다. `reason` 은
`"5xx_retry"` · `"429_retry"` · `"fallback_model"` 같은 짧은 문자열이다.

**`LlmStreamTarget` — 인자 셋을 값 하나로.** 게이트웨이 스트리밍 오버로드는 `options` · `sink` ·
`retryListener` 를 불변 값 객체 `LlmStreamTarget` 하나로 받는다. 셋은 언제나 함께 다니기 때문이다. listener 를
생략하면 `StreamingRetryListener.NOOP` 이 들어간다. 오버로드는 취소 토큰 유무로 둘이다.

취소는 재시도하지 않는 terminal 이다. 게이트웨이는 취소 예외에서 buffering sink 의 부분 chunk 를 버리고 그대로
던진다 — 두 겹의 terminal 보장은 [`cancellation.md`](cancellation.md) 가 정본이다.

prompt-too-long 복구는 스트리밍에서도 같은 경로를 탄다. 대개 첫 chunk 가 나가기 전에 발생하므로 바깥 sink 에
영향이 없고, 복구 후 재발행에서는 실행기가 시도 상태를 다시 무장한다(§7.2).

---

## 7. 실행기 — chunk 를 이벤트로, 중단을 전사로

### 7.1 `StreamingEventSink` — 시도 단위 수명

`OrcaAgentExecutor.StreamingEventSink` 가 iteration 당 하나 생기고 세 가지를 함께 한다 — 이벤트 발행, 자기
aggregator 에 chunk 미러링, 텍스트 delta 에서의 IRQ checkpoint. 핵심 규칙은 **시도 단위 수명**이다.

- **aggregator 는 시도당 하나.** `onRetry` 에서 새 인스턴스로 바꾸고 chunk 카운터를 0 으로 되돌린다
- **chunk index 는 채널마다 따로 단조 증가한다.** `AssistantTextDelta.getChunkIndex()` 와
  `AssistantReasoningDelta.getChunkIndex()` 는 각자 시도 안에서 0 부터 엄격히 증가하고, `onRetry` 에서 함께
  리셋된다. 하나의 카운터를 공유하면 텍스트 delta 시퀀스에 구멍이 생겨, 그 시퀀스로 유실을 감지하는 소비자의
  계약이 깨진다. 두 시퀀스는 index 로 섞어 정렬할 수 없지만 그럴 필요가 없다 — 둘 다 한 소비자에게 발행 순서대로
  도착한다
- **`AssistantTextStreamCompleted` 는 시도당 정확히 1회.** 정상 종료면 `STREAM_END` chunk 에서, 중단이면
  `emitInterruptedCompletion()` 에서 나오고, 두 경로가 함께 발화하지 않는다

### 7.2 세 종료 경로와 프리픽스 보존

| 예외 | 원인 | 처리 |
|---|---|---|
| `CancelledExecutionException` | sink 안의 checkpoint 가 취소를 관측 | eager 도구 작업 폐기 → `peekText()` 프리픽스가 비어 있지 않으면 `Message.assistant(partial, [])` 로 전사에 append → interrupted completion → rethrow |
| `LlmCallCancelledException` | provider 가 HTTP 스트림을 능동 abort(`close()` 가 경합에서 이긴 경우) | **같은 처리** 후 `CancelledExecutionException` 으로 번역해 ReAct 루프의 중단 처리로 보낸다 |
| 그 밖의 `RuntimeException` | provider 실패 / prompt-too-long 재발행 | eager 작업 폐기 → `emitErrorCompletion()` → rethrow |

세 번째 줄이 §4.1 의 공백을 메운다 — provider 오류가 sink 를 침묵시키더라도 UI 소비자는 매달린 스트림을 보지
않는다. 합성 `finishReason="error"` completion 은 **그 시도에서 텍스트 delta 가 하나 이상 발행되었을 때만**
나간다 — UI 가 스트림 시작을 보지 못했다면 닫을 것도 없다.

취소 시 프리픽스를 전사에 넣는 이유는 화면과 이력을 일치시키기 위해서다 — 최종 세션 스냅샷과 다음 LLM 호출이
사용자가 본 것과 정확히 같은 텍스트를 싣는다. tool uses 는 의도적으로 비운다. 스트림 중간의 취소는 tool_call 을
완결시킬 수 없다. 이 경로는 trace 도 붙이지 않는다 — 응답이 아직 집계되지 않았으므로 붙일 trace 가 없다. 보존된
프리픽스 메시지의 압축은 [`../agent-execution/compaction.md`](../agent-execution/compaction.md) 소관이다.

취소 토큰이 실행기에서 provider 까지 가는 배선과 서브에이전트 실행기의 결말은
[`cancellation.md`](cancellation.md) 가 정본이다. 스트리밍을 켠 어셈블리(CLI 기본)에서는 턴의 LLM 호출이 이
스트리밍 경로를 탄다.

### 7.3 추론 arm 에는 cancellation checkpoint 를 두지 않는다

텍스트 delta arm 은 매 delta 뒤에 `cancellationSignal.checkpoint()` 를 불러 스트리밍 도중의 취소가 곧바로
착지하게 한다. **추론 delta arm 은 checkpoint 를 부르지 않는다.**

이유는 "다음 chunk 가 곧 온다" 가 아니다 — 긴 숙고가 바로 이 채널이 존재하는 이유이므로, 숙고만 흐르는 동안
다음 텍스트 delta 가 곧 뒤따른다는 보장은 없다. 성립하는 것은 **abort 레버**다. 두 provider 는 스트림을 소비하기
전에 `cancellation.onCancel(<stream>::close)` 를 등록하므로, 숙고만 흐르는 창에서 취소가 걸리면 인터럽트를 건
스레드가 HTTP 스트림을 닫고 호출은 `LlmCallCancelledException` 으로 풀린다. 그것은 여기에 checkpoint 를 두었다면
닿았을 프리픽스 보존 경로와 같은 곳이며, 예외 하나 늦을 뿐이다.

### 7.4 새 이벤트는 최소로 — `AssistantReasoningDelta` 하나

**중단에 새 sealed 이벤트를 만들지 않는다.** `ExecutionCompleted(CompletionReason.INTERRUPTED)` 와
`AssistantTextStreamCompleted(finishReason="interrupted")` 의 조합으로 충분하고, `permits` 확장은 바깥의
exhaustive switch 를 깨뜨린다.

**추론 채널이 더하는 이벤트는 `AssistantReasoningDelta` 하나다.** `AssistantTextDelta` 의 형제이고 `delta`
(비어 있지 않음)와 `chunkIndex` 를 싣는다. 이 타입은 모델의 숙고이지 답이 아니며, assistant 메시지에 붙지 않고,
영속되지 않고, `AssistantMessageReceived` 가 요약하지 않는다. 두 delta 를 한 누적기에 이어 붙이는 구독자는 이
타입이 막으려는 사고를 한 층 바깥에서 재현한다.

추론 전용 reset · completed 이벤트는 만들지 않는다. **`AssistantTextStreamReset` 과
`AssistantTextStreamCompleted` 는 텍스트 채널이 아니라 시도(attempt)의 경계를 표시한다** — 재시도는 추론을 포함한
시도 전체를 버리고, 완료된 스트림은 두 채널을 함께 끝낸다. 두 채널을 보여 주는 렌더러는 이 두 이벤트에서 둘 다
정리한다. 이벤트를 셋으로 늘리면 sealed 계층의 파급이 세 배가 되면서 기존 두 이벤트가 이미 싣지 않는 정보는
하나도 싣지 않는다.

대가는 이름이다 — 두 이벤트의 이름이 하는 일보다 좁게 말한다. 두 클래스의 javadoc 이 그 사실을 적고 있고,
개명하지 않는다. 두 이름은 공개 이벤트 타입이면서 노드 경계를 넘는 페이로드 프레임 이름이라, 개명은 sealed
계층의 소스 파괴에 더해 롤링 업그레이드 중 프레임 유실을 뜻하기 때문이다. 열린 항목은 RD-5.

---

## 8. 결과와 렌더링 — `wasStreamed` 와 CLI

### 8.1 `wasStreamed` — 이중 출력 회피

스트리밍으로 텍스트를 이미 뿌렸는데 턴 종료 시 최종 답을 또 인쇄하면 같은 글이 두 번 나온다. 결과에 플래그
하나를 얹어 푼다 — `AgentExecutionResult.wasStreamed()`(`default false`).

`OrcaAgentExecutor` 는 **모든 completion reason**(COMPLETED · TRUNCATED · ERROR · INTERRUPTED)의 결과에 자기
스트리밍 모드 값을 싣는다. `OutputFormatter` 는 성공 결과를 보여 줄 때 `wasStreamed` 가 참이면 최종 답 본문을
건너뛰고, iteration 푸터 같은 턴 마무리 표시와 에러 · 중단 분기는 그대로 둔다.

이 한 비트는 **노드 경계를 넘는다.** `TurnResultPayload`(라우팅)와 Redis · Postgres 의 idempotency 코덱이
`wasStreamed` 를 직렬화한다 — 다른 노드가 재구성한 결과에서도 인쇄 생략 판단이 유지되어야 하기 때문이다.
스트리밍은 노드 로컬이지만 그 결과의 한 비트는 공유된다.

### 8.2 CLI 렌더링

| 이벤트 | 렌더 |
|---|---|
| `AssistantTextDelta` | 줄바꿈 없이 즉시 append 하고 flush |
| `AssistantReasoningDelta` | 흐리게(`fgBrightBlack`) append. 추론 줄이 처음 열릴 때 줄바꿈과 흐린 `[thinking]` 표지를 한 번 찍는다 |
| `AssistantTextStreamReset` | 누적된 줄 위를 지우지 않고 새 줄에 흐린 재시도 배너 |
| `AssistantTextStreamCompleted` | 종결 줄바꿈 |

재시도 배너가 이전 출력을 ANSI 로 지우지 않는 것은, 텍스트가 터미널 폭을 넘어 감긴 뒤에는 지우기를 믿을 수 없기
때문이다 — 폐기된 텍스트는 보이는 채로 괄호 쳐진다.

숙고는 **두 가지로** 답과 구별한다 — 색(흐림 vs 답의 초록)과 `[thinking]` 표지. 색만으로는
`isColorOutput() == false` 인 흑백 터미널(지원되는 모드다)에서 아무것도 구별되지 않는다.

**`OutputFormatter` 는 줄 상태 하나(`reasoningLineOpen`)를 갖는다.** 숙고와 답이 둘 다 줄바꿈 없이 인라인으로
찍히므로, 전환이 없으면 한 줄에 붙어 버린다. 이것은 렌더 상태 — 사실상 커서 위치 — 이므로 렌더러에 둔다.
`ReplSession` 은 렌더 계층을 순수하게 두려고 상태를 자기 쪽에 두는 반대 방향의 선례이고, 이 상태는 그 대상이
아니다. 필드는 이벤트 소비 경로에서 한 턴씩만 쓰인다.

전환 규칙: 추론 줄이 열린 채 **텍스트 delta** 가 오면 줄바꿈을 찍고 닫는다. **reset** 과 **completed** 는
줄바꿈을 찍지 않고 상태만 닫는다 — 두 핸들러가 이미 자기 줄바꿈을 하나씩 내므로, 하나를 더 찍으면 숙고 도중 끝난
스트림 뒤에 빈 줄이 남는다.

REPL 렌더는 흐리게 구별된 텍스트까지다. 접기 · 토글 · `/thinking` 명령은 코어 의존이 없는 CLI 결정이며 이 설계에
포함하지 않는다.

---

## 9. 옵션과 기본값

| 옵션 | 위치 | 기본 | 뜻 |
|---|---|---|---|
| `useStreaming` | `OrcaAgentExecutorFactory.withUseStreaming` | `false` | 실행기의 스트리밍 분기 |
| `streamingOptions` | `OrcaAgentExecutorFactory.withStreamingOptions` | `LlmStreamingOptions.defaults()`(스트리밍일 때) | 아래 둘의 묶음 |
| `bufferUntilFirstSuccess` | `LlmStreamingOptions` | `false` | §6 의 직결 vs buffering |
| `includeUsage` | `LlmStreamingOptions` | `true` | provider 에 usage 를 요청 |
| `streaming` | `ExecutorSpec`(`aimon-bootstrap`) | `false` | `AimonStackBuilder` 가 `withUseStreaming` 으로 전달 |
| `streaming` | `CliSettings` / `--streaming` · `--no-streaming` | **`true`** | CLI 사용자 노출 |

core 기본이 off 이고 CLI 기본이 on 인 것은 모순이 아니다 — **core 는 회귀 없는 중립 기본값을, 어셈블리는 그
제품의 UX 를 고른다.** 스타터에는 스트리밍 프로퍼티가 없으므로 `ExecutorSpec` 기본값을 따른다.
`useStreaming=false` 인데 `streamingOptions` 만 준 경우는 무시하고 debug 로 남긴다.

`includeUsage` 는 Responses 경로에서 효과가 없다 — 그 엔드포인트는 usage 를 opt-in 으로 두지 않는다
([`openai-responses-path.md`](openai-responses-path.md)).

CLI 플래그는 picocli `negatable = true` 로 필드 하나가 `--streaming` / `--no-streaming` 둘을 만든다. 플래그를
생략하면(`null`) 설정 파일 값을 유지하고, 주면 **에이전트 셋업이 값을 읽기 전에** 덮어쓴다. 그 뒤에 `CliSettings`
를 바꾸면 `OutputFormatter` 와 실행기가 서로 다른 값을 보게 될 뿐이다.

---

## 10. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| buffering flush 를 기본으로 | 응답 완료 시점에야 화면에 뜬다 — 스트리밍을 둔 이유가 사라진다. 옵션으로만 둔다(§6) |
| 첫 시도만 스트리밍, 재시도는 비스트리밍 | 호출자가 보는 동작이 시도마다 달라진다 |
| sink 에 누적 책임을 얹는다 | 인터페이스가 비대해지고 데코레이터마다 누적을 다시 구현해야 한다(§4.1) |
| aggregator 를 provider 안에 캡슐화 | 실행기가 중단 시 사용자가 본 프리픽스에 닿을 경로가 없어진다(§4.2) |
| Reactor · RxJava 기반 publisher | 외부 의존이 늘고, 좁은 콜백으로 지금의 요구가 충족된다. `Flow.Publisher` 로 옮길 여지만 남긴다 |
| 중단 전용 sealed 이벤트 | 기존 두 이벤트의 조합으로 충분하고, `permits` 확장은 바깥 exhaustive switch 를 깬다(§7.4) |
| `AssistantTextDelta` 에 `isReasoning` 플래그 | 플래그를 잊은 모든 소비자가 숙고를 답으로 렌더하거나 영속한다. 플래그는 안전한 동작을 미래의 모든 소비자에게 opt-in 으로 만들고, 별도 타입은 구조로 만든다 |
| `LlmStreamChunk` 의 `textDelta` 필드를 재사용 | 위 대안의 한 층 아래 판. kind 를 확인하지 않고 `getTextDelta()` 를 읽는 호출자가 바로 갱신되지 않을 호출자다(§3) |
| 전송을 먼저 싣고 요청은 나중에 | 항상 빈 채널은 끝에서 끝까지 확인할 수 없다. budgeted 방언에서는 한 설정에서만 채널이 차고 나머지는 비어, 진단하기 가장 어려운 모양이 된다(§5.1) |
| 전달 게이트 없이 도착하는 것을 전달 | budgeted 방언의 기존 배포와 요청 없이 요약을 보내는 호환 게이트웨이에서, 아무것도 설정하지 않은 배포의 동작이 바뀐다(§5.3) |
| 두 provider 가 읽는 중립 boolean 키 하나 | boolean 은 입도를 표현하지 못하고 나중에 넓히면 깨지는 타입 변경이다(§5.6) |
| 중립 키 **와** 벤더 키 둘 | 한 의도에 사용자 개념이 둘, 둘을 함께 적었을 때의 우선순위 규칙이 하나 더 생긴다(§5.6) |
| 추론 reset · completed 이벤트를 따로 | sealed 파급이 세 배가 되고, 기존 시도 경계 두 이벤트가 이미 싣지 않는 정보는 없다(§7.4) |
| 추론 텍스트를 `ReasoningTrace` 에도 넣는다 | 모델에게 되실리는 것이 바뀐다. 서명된 블록은 수정하면 거절되고, 요약은 `encrypted_content` 의 대체물이 아니다(§5.4) |
| 숙고를 `AssistantMessageReceived` 로 렌더 | 그 이벤트는 assistant 메시지로 만들어져 결과에 요약되는데 숙고는 둘 다 아니다. 게다가 숙고가 끝난 뒤에 도착한다 — 이 기능이 피하려는 바로 그것이다 |
| 원격 드롭 정책의 `isDroppable` 을 두 delta 로 넓힌다 | 두 등급이 같아져 숙고 폭주가 답 텍스트를 밀어낸다 — [`../session/routing.md`](../session/routing.md#551-프로듀서는-절대-블록하지-않는다) |
| REPL 의 `/thinking` 토글 · 접기 · 스크롤백 영역 | 채널이 제공되면 터미널이 그것으로 무엇을 할지는 코어 의존이 없는 CLI 결정이다(§8.2) |

---

## 11. 하지 말 것

- **`REASONING_DELTA` 를 `textBuffer` 에 넣지 말 것.** `peekText()` 와 `toLlmResponse()` 는 숙고를 읽지 않는다.
  섞이면 스트림 중간 취소가 숙고를 답으로 전사에 커밋하고, 전사는 되돌릴 수 없다
- **추론 전달에서 `addReasoningTrace` 를 부르지 말 것.** 사람이 보는 숙고와 모델에게 되싣는 trace 는 다른
  payload 다
- **추론 전달을 delta 의 도착으로 게이트하지 말 것.** 요청 키의 존재로 게이트한다 — 아니면 기존 배포가
  업그레이드만으로 숙고 스트림을 켠다
- **`AgentExecutionEvent` 에 서브타입을 더하면서 `AgentExecutionEventPayload` 의 flatten · decoder 를 빠뜨리지
  말 것.** 누락이 조용한 자리는 그곳뿐이다 — 빠진 프레임은 relay 와 수신 노드에서 건너뛰어지고 아무것도
  실패하지 않는다. 그래서 payload 코덱은 모든 permitted 서브타입을 왕복으로 확인한다. 반대로 chunk kind 를
  분기하는 `switch`(`LlmStreamChunk` 생성자, `ChunkAggregator.accept`, `StreamingEventSink.accept`)나 REPL
  렌더러에서 빠진 분기는 `IllegalStateException` 으로 시끄럽게 실패한다
- **텍스트와 추론의 chunk index 카운터를 공유하지 말 것.** 텍스트 시퀀스에 구멍이 생겨 유실 감지 계약이 깨진다
- **`StreamingRetryListener` 를 다음 시도의 chunk 가 배달된 뒤에 부르지 말 것.** UI 가 새 텍스트를 그린 다음에
  화면을 지운다
- **한 시도에서 `AssistantTextStreamCompleted` 를 두 번 내지 말 것.** 정상 종료와 중단 · 오류의 합성 completion
  은 배타적이다
- **스트리밍한 결과의 최종 답을 다시 인쇄하지 말 것.** `wasStreamed` 를 본다
- **부분 tool_call JSON 을 호출자에게 노출하지 말 것.** `STREAM_END` 에서 1회 파싱하고, 완결이 확정된 슬롯만
  `finalizeToolCall` 로 꺼낸다
- **에이전트 셋업이 값을 읽은 뒤 `CliSettings.streaming` 을 바꾸지 말 것.** 렌더러와 실행기가 다른 값을 본다
- **reactive 스택을 들이지 말 것.** sink 는 좁은 콜백으로 둔다

---

## 12. 남은 것

**백로그에 등록된 것** — [`../../backlog/reasoning-delta-stream-open-items.md`](../../backlog/reasoning-delta-stream-open-items.md)

- **RD-3** — REPL 의 `OutputFormatter.displayEvent` 가 `InterruptedAt` · `RejectedAt` 에서 던진다
- **RD-4** — 중립 우산 키를 열 것인가(세 번째 provider, 또는 벤더 사이 이동 요구가 트리거)
- **RD-5** — `AssistantTextStreamReset` / `…Completed` 의 이름이 시도 경계라는 실제 역할보다 좁다
- **RD-6** — 에이전트 정의의 `model.reasoningSummary`
- **RD-7** — `response.reasoning_text.delta` 는 어느 서버에서도 관측된 적이 없다

**등록되지 않은 것**

- **tool_use 인자의 부분 노출** — 긴 인자를 가진 도구의 미리보기 UX 는 별도 설계가 필요해 아직 열지 않았다
- **backpressure** — 느린 소비자가 실제 문제로 관측되지 않았다. 관측되면 `Flow.Publisher` 로 옮긴다
- **부분 텍스트의 시크릿 레닥션** — 화면 출력 단계의 필터 훅. 스트리밍 고유 문제는 아니지만 노출 시점이 빨라졌다
- **추론 delta 의 양이 미측정** — 원격 드롭에서 추론을 가장 먼저 버리는 순서는 "텍스트보다 양이 많다" 는 추론에
  기댄다. 틀려도 비용은 순서뿐이다
- **두 요청(ask)의 토큰 비용이 미측정** — 두 키가 기본 off 이고 opt-in 인 이유이며, 비스트리밍 호출에서도 요청이
  토큰을 쓴다는 점은 운영 비용으로만 인정되어 있다

---

## 부록 — 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준. 다른 모듈은 모듈 이름을 앞에 적는다.

| 파일 | 무엇을 확인하나 |
|---|---|
| `llm/streaming/LlmStreamChunk.java` | 네 kind, 생성자 `switch` 의 양방향 배타 검증, `STREAM_END` 팩토리 둘, `TOOL_USE_READY` 의 advisory 성격 |
| `llm/streaming/LlmStreamSink.java` | 수명 계약, `discarding()` |
| `llm/streaming/ChunkAggregator.java` | 두 버퍼, `peekText` · `peekReasoningText` · `toLlmResponse` · `finalizeToolCall`, `STREAM_END` 1회 파싱 |
| `llm/streaming/BufferingStreamSink.java` | `flush` / `abort` — buffering 모드의 시도 격리 |
| `llm/streaming/LlmStreamTarget.java` | 세 인자를 묶은 값 객체, listener 생략 시 `NOOP` |
| `llm/streaming/StreamingRetryListener.java` | 다음 시도 전 1회 호출 계약, reason 문자열 |
| `llm/streaming/LlmStreamingOptions.java` | `bufferUntilFirstSuccess` · `includeUsage` 기본값 |
| `llm/LlmClient.java` | 스트리밍 `default` 오버로드와 비스트리밍 폴백 |
| `llm/invoke/LlmCallGateway.java` | 스트리밍 오버로드 둘(취소 토큰 유무), 직결 vs buffering, 재시도 전 `onRetry` |
| `llm/retry/LlmRetryPolicy.java` | 기본 재시도 정책의 시도 횟수와 재시도 대상 |
| `agent/impl/orca/OrcaAgentExecutor.java` | `StreamingEventSink` 의 시도 단위 수명과 두 index 시퀀스, 추론 arm 에 checkpoint 가 없는 이유(주석), `invokeGatewayOnce` 의 세 종료 경로와 프리픽스 보존, completion reason 별 `wasStreamed` |
| `agent/impl/orca/OrcaAgentExecutorFactory.java` | `withUseStreaming` · `withStreamingOptions` 기본값 |
| `agent/stream/AssistantReasoningDelta.java` | "답이 아니다" 불변식, 자기 index 시퀀스 |
| `agent/stream/AssistantTextStreamReset.java`, `agent/stream/AssistantTextStreamCompleted.java` | 시도 전체의 경계라는 javadoc 문장 |
| `agent/stream/AssistantTextDelta.java` | `chunkIndex` 순서 계약 |
| `agent/AgentExecutionResult.java` | `default boolean wasStreamed()` |
| `aimon-llm-anthropic` — `AnthropicStreamingMapper.java` | `forwardReasoning` 게이트, `thinking_delta` 의 두 소비자, `signature_delta` 미전달 |
| `aimon-llm-anthropic` — `AnthropicLlmClient.java` | `getThinkingDisplay().isPresent()` 를 매퍼에 넘기는 자리, `onCancel(<stream>::close)` |
| `aimon-llm-openai` — `OpenAIResponsesStreamingMapper.java` | 두 추론 이벤트 계열, `addReasoningTrace` 를 부르지 않는 `emitReasoningDelta` |
| `aimon-llm-openai` — `OpenAILlmClient.java`, `OpenAIResponsesExchange.java` | `getReasoningSummary().isPresent()` 게이트 전달 |
| `aimon-llm-openai` — `OpenAIStreamingMapper.java` | Chat Completions 매퍼 — 추론 chunk 없음, `TOOL_USE_READY` |
| `aimon-llm-{openai,anthropic}` — `*ArchitectureTest.java` | SDK 타입 비노출 규칙 |
| `aimon-session-routing` — `internal/AgentExecutionEventPayload.java` | 추론 delta 프레임, 모르는 타입을 건너뛰는 decoder |
| `aimon-session-routing` — `internal/SessionEventRelay.java` | 드롭 등급(정본 서술은 `../session/routing.md`) |
| `aimon-session-routing` — `internal/TurnResultPayload.java` | 노드 경계를 넘는 `wasStreamed` |
| `aimon-session-{redis,postgres}` — idempotency 코덱 | `wasStreamed` 직렬화 |
| `aimon-bootstrap` — `spec/ExecutorSpec.java`, `AimonStackBuilder.java` | `streaming` 기본값과 실행기 팩토리 전달 |
| `aimon-cli` — `config/CliSettings.java`, `AimonCli.java` | CLI 기본 on, `--streaming` / `--no-streaming` 적용 시점 |
| `aimon-cli` — `repl/OutputFormatter.java` | 네 이벤트 렌더, `[thinking]` 표지, `reasoningLineOpen` 전환 규칙, `wasStreamed` 인쇄 생략 |

---

## 관련 문서

- [`cancellation.md`](cancellation.md) — 이 전송의 abort 레버를 쓰는 취소 설계, `default` 오버로드와 데코레이터 전달 의무
- [`reasoning-traces.md`](reasoning-traces.md) — 다음 요청에 되싣는 reasoning trace 슬롯과 provider 별 캡처 규칙
- [`openai-responses-path.md`](openai-responses-path.md) — `reasoning.summary` 요청, `supportsReasoningSummary` 게이트, Chat 라우팅 경고
- [`anthropic-thinking.md`](anthropic-thinking.md) — `display` 가 adaptive 모양에만 붙는 규칙과 그 보고
- [`configuration-surface.md`](configuration-surface.md) — 요청 키의 위치와 네임스페이스 규칙
- [`../session/routing.md`](../session/routing.md) — `SessionEventRelay` 드롭 등급의 정본
- [`../agent-execution/orca-executor.md`](../agent-execution/orca-executor.md) — 스트리밍-도구 중첩(eager dispatch)의 실행기 쪽 설계
- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — 그 중첩이 올라타는 디스패처 계약
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `CancellationSignal` · `CompletionReason.INTERRUPTED`
- [`../agent-execution/interceptor.md`](../agent-execution/interceptor.md) — 이벤트와 인터셉터의 역할 분리
- [`../agent-execution/compaction.md`](../agent-execution/compaction.md) — 보존된 프리픽스 메시지의 압축 정책
- [`../../backlog/reasoning-delta-stream-open-items.md`](../../backlog/reasoning-delta-stream-open-items.md) — 추론 스트림의 열린 항목
