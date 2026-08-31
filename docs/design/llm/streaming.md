# 부분 텍스트 스트리밍 (Partial Text Streaming)

> Status: **IMPLEMENTED** — provider-neutral chunk 모델, 공용 aggregator, gateway 의 attempt 격리와
> reset 통지, 세 이벤트, `wasStreamed` 이중 출력 회피, 중단 시 프리픽스 보존이 전부 들어가 있다.
> **CLI 는 기본 ON**(`CliSettings.streaming = true`, `--no-streaming` 으로 opt-out)이고, core 실행기의
> 플래그 자체(`OrcaAgentExecutorFactory.withUseStreaming`)는 기본 false 이므로 **어셈블리가 켜는 구조**다.
> 남은 것은 §9.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm.streaming`(`LlmStreamChunk`, `LlmStreamSink`,
> `ChunkAggregator`, `LlmStreamingOptions`, `BufferingStreamSink`, `LlmStreamTarget`,
> `StreamingRetryListener`), `…llm.invoke`(gateway 오버로드),
> `at.aimon.core.agent.stream`(`AssistantTextDelta` / `…StreamReset` / `…StreamCompleted`),
> `…agent.impl.orca`(`StreamingEventSink`) · `aimon-llm-openai` / `aimon-llm-anthropic` ·
> `aimon-cli`(`OutputFormatter`, `--streaming`).

---

## 1. 무엇을 풀었는가

`LlmClient.sendMessage(...)` 는 완성된 `LlmResponse` 를 돌려주는 동기 API였다. 장문 응답 턴에서는 첫 토큰
생성부터 응답 완료까지 수 초의 침묵이 그대로 사용자에게 노출됐다 — provider SDK 는 스트리밍을 지원하는데
클라이언트가 non-streaming 경로만 쓰고 있었다.

목표는 **TTFT(Time To First Token) 단축** 하나다. 그리고 그것을 **provider 종류와 무관한 추상화**로
노출해 REPL·SDK 소비자가 같은 이벤트를 구독하게 한다.

**범위 밖으로 둔 것**도 처음부터 분명했다 — tool_use 인자(JSON)의 부분 스트리밍은 부분 상태로는 파싱조차
안 되므로 호출자에게 노출하지 않는다(§4.3). 그리고 backpressure 는 도입하지 않는다.

---

## 2. 설계 원칙

- **Provider-neutral** — chunk / sink / aggregator 추상화를 `aimon-core` 에 둔다. OpenAI·Anthropic SDK
  타입은 모듈 밖으로 누출하지 않는다(ArchUnit 강제)
- **Opt-in 확장** — 기존 `sendMessage(...)` 오버로드는 그대로. 스트리밍은 `default` 메서드로 얹고,
  미구현 provider 는 비스트리밍 결과를 단일 chunk 로 폴백한다
- **외부 의존 0 추가** — Reactor/RxJava 도입 금지. `Flow.Publisher` 마이그레이션 여지만 남기고 지금은
  좁은 콜백 인터페이스 하나
- **TTFT 우선** — buffering 은 기본 OFF. 재시도 화면 정리는 buffering 이 아니라 **reset 이벤트**로 푼다(§5)
- **aggregator 는 공용 유틸** — provider 가 호출하지만 실행기도 같은 인스턴스에 `peekText()` 를 건다.
  중단 시 "사용자가 이미 본 프리픽스"를 전사에 보존해야 하기 때문이다(§6)

---

## 3. 계층 — chunk 는 아래, event 는 위

```
aimon-cli (REPL)              OutputFormatter — delta 누적 출력, reset 시 화면 정리,
                              wasStreamed 이면 final answer 인쇄 skip
        ▲ AgentExecutionEvent
aimon-core (agent)            OrcaAgentExecutor.StreamingEventSink
                              chunk → AssistantTextDelta / …Reset / …Completed
        ▲ LlmStreamChunk (provider-neutral)
aimon-core (llm)              LlmCallGateway — attempt 격리 · retry 통지 · buffering 옵션
                              at.aimon.core.llm.streaming — chunk · sink · aggregator · target
        ▲
provider 모듈                 {OpenAI,Anthropic}LlmClient — SDK 스트림 → chunk 정규화
```

패키지 이름이 `llm.streaming`(전송 계층)과 `agent.stream`(이벤트 계층)으로 갈린 것은 의도적이다 — 두
계층의 책임이 다르다는 것을 이름에 박아 둔다.

---

## 4. provider-neutral chunk 모델

### 4.1 `LlmStreamChunk` — 세 가지 kind

| kind | 싣는 것 | 규칙 |
|---|---|---|
| `TEXT_DELTA` | 비어 있지 않은 `textDelta` | 빈 delta 는 **provider 매퍼가 사전 폐기**. `toolUse` 동반 금지 |
| `TOOL_USE_READY` | 완성된 `ToolUse` 1개 | **advisory** — 스트림 중간에 tool_use 블록 하나의 인자가 다 모였음을 알린다 |
| `STREAM_END` | 누적 `TokenUsage`, `finishReason`, 중립 `StopReason` | sink 수명당 **정확히 1회**. `textDelta`·`toolUse` 동반 금지 |

불변 규칙은 빌더가 `IllegalArgumentException` 으로 강제한다 — 문서상의 약속이 아니라 생성 시점 검증이다.

`TOOL_USE_READY` 는 초기 설계에는 없던 kind다. **스트리밍-도구 중첩(eager dispatch)** 이 올라탈 자리로
나중에 들어왔다 — 스트림이 아직 흐르는 동안 이미 완성된 도구 하나를 먼저 실행에 넘긴다. **advisory 인 것이
핵심**이다: 최종 응답의 tool uses 는 여전히 `ChunkAggregator.toLlmResponse()` 가 만든 것이고, 이 chunk 는
그것과 겹칠 뿐 대체하지 않는다. 모든 provider 가 내보내지도 않는다. 중첩 자체의 설계는
[`../agent-execution/orca-executor.md`](../agent-execution/orca-executor.md) 와
[`../tool/parallel-execution.md`](../tool/parallel-execution.md) 소관이고, 이 문서는 **그것을 실어 나르는
chunk 종류까지**만 다룬다.

`STREAM_END` 팩토리가 둘인 것도 같은 종류의 사후 보강이다 — 3-arg 는 provider 문자열
`finishReason` 만, 4-arg 는 거기에 중립 `StopReason` 을 더한다. provider 마다 다른 종료 사유 문자열을
호출자가 문자열 비교로 해석하지 않게 하려는 것이다.

### 4.2 `LlmStreamSink` — 좁게 유지한 이유

```java
@FunctionalInterface
public interface LlmStreamSink {
    void accept(LlmStreamChunk chunk);
}
```

메서드 하나뿐이므로 데코레이터 합성이 자유롭다 — `BufferingStreamSink`, gateway 내부의 attempt-aware
래퍼, 실행기의 `StreamingEventSink` 가 전부 같은 인터페이스를 구현한다. 여기에 누적 책임까지 얹었다면
(기각한 대안 중 하나) 인터페이스가 비대해지고 데코레이터마다 그 책임을 다시 구현해야 했다.

수명 계약: `TEXT_DELTA` 0회 이상 → `STREAM_END` 정확히 1회. **provider 오류 시에는 스트리밍 호출 자체가
throw 하고 sink 는 stream-end 를 받지 못한다** — 그래서 UI 가 "시작했는데 끝나지 않은 스트림"을 보지
않도록 실행기 쪽에서 합성 종료 이벤트를 낸다(§6).

`LlmStreamSink.NO_OP` 은 모든 chunk 를 버린다. 취소 가능한 **비스트리밍** 호출을 스트리밍 경로로
우회시켜 abort 레버만 얻고 집계 결과만 쓰는 경우에 쓴다 — 자세한 것은
[`cancellation.md` §5.2](cancellation.md).

### 4.3 `ChunkAggregator` — 왜 provider 안에 두지 않았나

```java
public void accept(LlmStreamChunk chunk);
public void appendToolCallDelta(int toolCallIndex, String id, String name, String argumentsFragment);
public String peekText();                       // 지금까지의 누적 — 중단 시 프리픽스 보존용
public Optional<ToolUse> finalizeToolCall(int toolCallIndex);   // eager dispatch 용
public LlmResponse toLlmResponse();             // STREAM_END 이후에만 유효
```

provider 매퍼가 호출하지만 **실행기도 같은 인스턴스를 본다.** 중단 시 사용자가 이미 화면에서 본 텍스트를
전사에 남기려면 누적 결과에 접근할 수 있어야 하고, aggregator 를 provider 내부에 캡슐화하면 그 경로가
아예 없다. 캡슐화를 조금 포기하고 얻은 것이 **중단해도 대화가 조각나지 않는다**는 성질이다.

tool_call delta(JSON 조각)는 buffer 에 누적했다가 **`STREAM_END` 시 1회 파싱**한다. 부분 JSON 은 파싱이
불가능하므로 호출자에게 노출할 의미가 없다. `finalizeToolCall(idx)` 만 예외인데, 이는 "그 인덱스의
tool_call 은 이미 완결됐다"가 확정된 시점에만 쓰이는 eager dispatch 전용 창구다.

### 4.4 provider 매퍼가 흡수하는 차이

| 항목 | OpenAI | Anthropic |
|---|---|---|
| 스트리밍 API | `chat().completions().createStreaming(...)` | `messages().createStreaming(...)` |
| 텍스트 delta | `choices[0].delta.content` | `content_block_delta.delta.text` |
| usage | `stream_options.include_usage=true` → 마지막 chunk `usage` | `message_delta.usage` + `message_start` 의 input |
| tool_use delta | `delta.tool_calls[*].function.arguments` (JSON 조각) | `content_block_start(tool_use)` + `delta.partial_json` |
| 종료 사유 | `choices[0].finish_reason` | `message_delta.delta.stop_reason` |

매퍼 책임: 빈 delta 폐기, arguments 조립, usage 추출. SDK 타입(`ChatCompletionChunk`,
`MessageStreamEvent`)은 모듈 밖으로 나가지 않는다.

---

## 5. gateway — retry 와 스트리밍의 충돌

스트리밍 도중 5xx 가 나면 사용자 화면에는 이미 시도 1의 텍스트가 남아 있다. 시도 2를 그 위에 이어 쓰면
글이 섞인다. 선택지는 둘이었다.

| 대안 | TTFT | 결정 |
|---|---|---|
| **직결 + reset 통지** — 시도 1을 그대로 흘려보내고, 폐기 시 호출자에게 알려 화면을 정리하게 한다 | 유지 | **채택 (기본)** |
| **buffering flush** — 시도별로 격리하고 성공한 시도만 한 번에 내보낸다 | 상실 | **옵션** (`bufferUntilFirstSuccess=true`) |
| 첫 시도만 스트리밍, retry 는 비스트리밍 | 부분 유지 | 기각 — 호출자가 보는 동작이 시도마다 달라진다 |

buffering 을 기본으로 하면 응답 완료 시점에야 화면에 뜬다 — **스트리밍을 도입한 이유 자체가 사라진다.**
게다가 이 프로젝트의 재시도 정책은 보수적(429 2회, 5xx 1회)이라 reset 은 드물게 발생한다. 드문 사건의
처리 부담을 UI 에 위임하고 흔한 경로의 이득을 지키는 쪽을 골랐다.

### `StreamingRetryListener` — "콜백"을 타입으로

폐기 통지는 익명 콜백이 아니라 인터페이스다.

```java
@FunctionalInterface
public interface StreamingRetryListener {
    void onRetry(int previousAttempt, int nextAttempt, String reason);   // "5xx_retry", "429_retry", "fallback_model"
    StreamingRetryListener NOOP = (prev, next, reason) -> { };
}
```

**다음 시도의 chunk 가 하나도 배달되기 전에** 정확히 1회 호출된다 — 이 순서가 계약이다. 어겼다면 UI 가
새 텍스트를 그린 뒤에 화면을 지우게 된다.

### `LlmStreamTarget` — 인자 셋을 값 하나로

gateway 스트리밍 오버로드는 원래 `options` + `sink` + `retryListener` 세 인자를 따로 받았다. 셋은 항상
함께 다니므로 하나의 불변 값 객체로 묶었다.

```java
LlmStreamTarget target = LlmStreamTarget.builder()
        .options(LlmStreamingOptions.defaults()).sink(sink).retryListener(sink::onRetry).build();
```

listener 를 생략하면 `NOOP` 이 들어간다. 호출자는 배달 대상을 한 번 조립해 단위로 들고 다닌다.

prompt-too-long 복구는 기존 경로 그대로다. 보통 첫 chunk 가 나가기 전에 발생하므로 outer sink 에 영향이
없고, 재발행 시에는 실행기가 attempt 상태를 다시 무장한다(§6).

---

## 6. 실행기 — chunk 를 이벤트로, 중단을 전사로

`OrcaAgentExecutor.StreamingEventSink` 가 iteration 당 하나 생기고 세 가지를 동시에 한다: 이벤트 발행,
aggregator 미러링, IRQ checkpoint.

**시도 단위 수명**이 이 클래스의 핵심 규칙이다.

- aggregator 는 **시도당 하나**. `onRetry` 에서 새 인스턴스로 교체하고 chunk 카운터도 0으로 되돌린다 —
  그래서 `AssistantTextDelta.getChunkIndex()` 는 각 시도 안에서 단조 증가하고 새 시도는 0부터 시작한다
- `AssistantTextStreamCompleted` 는 **시도당 정확히 1회**. 정상 종료면 `STREAM_END` chunk 에서,
  중단이면 `emitInterruptedCompletion()` 에서 나온다. 두 경로가 동시에 발화하지 않는다

세 개의 종료 경로가 모두 무언가를 보장한다.

| 예외 | 원인 | 처리 |
|---|---|---|
| `CancelledExecutionException` | sink 안의 checkpoint 가 취소를 관측 | eager 도구 작업 폐기 → `peekText()` 프리픽스를 `Message.assistant(partial, [])` 로 전사에 append → interrupted completion |
| `LlmCallCancelledException` | provider 가 HTTP 스트림을 능동 abort — `close()` 가 경합에서 이긴 경우 | **동일 처리** 후 `CancelledExecutionException` 으로 번역해 ReAct 루프의 `handleInterrupted` 로 보낸다 |
| 그 밖의 `RuntimeException` | provider 실패 / prompt-too-long 재발행 | eager 작업 폐기 → `emitErrorCompletion()` → rethrow |

세 번째 줄이 §4.2 의 공백을 메운다 — **시작된 모든 스트림은 종료 이벤트를 받는다.** provider 오류가 sink
를 침묵시키더라도 UI 소비자는 매달린 스트림을 보지 않는다.

취소 시 프리픽스를 전사에 넣는 이유는 화면과 이력을 일치시키기 위해서다. tool uses 는 의도적으로 비운다 —
스트림 중간의 취소는 tool_call 을 완결시킬 수 없다.

중단 자체의 표현은 **새 sealed 이벤트를 만들지 않는다.** `ExecutionCompleted(CompletionReason.INTERRUPTED)`
와 `AssistantTextStreamCompleted(finishReason="interrupted")` 의 조합으로 충분하고, `permits` 확장은 외부
exhaustive switch 를 깨뜨리므로 최소화하는 편이 낫다.

---

## 7. 이중 출력 회피 — `wasStreamed`

스트리밍으로 텍스트를 이미 뿌렸는데 턴 종료 시 `getFinalAnswer()` 를 또 인쇄하면 같은 글이 두 번 나온다.
결과에 플래그 하나를 얹어 푼다.

```java
default boolean wasStreamed() { return false; }     // AgentExecutionResult
```

`OrcaAgentExecutor` 는 **모든 completion reason**(COMPLETED / TRUNCATED / ERROR / INTERRUPTED)에서 이
값을 실어 보낸다. `OutputFormatter` 는 `wasStreamed && isSuccess` 일 때만 final-answer 인쇄를 건너뛰고,
줄바꿈 같은 턴 마무리 표시와 에러·중단 분기는 종전 그대로다.

세 이벤트도 여기서 렌더된다 — delta 는 즉시 append, reset 은 누적 라인 정리, completed 는 줄바꿈 정리.

이 플래그는 **노드 경계를 넘는다.** `TurnResultPayload`(routing)와 redis·postgres 의 idempotency 코덱이
`wasStreamed` 를 직렬화한다 — 다른 노드가 재구성한 결과에서도 인쇄 skip 판단이 유지되어야 하기 때문이다.
스트리밍 자체는 노드-로컬 단일 호출이라 분산 고려 사항이 없지만, **그 결과의 한 비트는 공유된다**.

---

## 8. 옵션과 기본값

| 옵션 | 위치 | 기본 | 뜻 |
|---|---|---|---|
| `useStreaming` | `OrcaAgentExecutorFactory.withUseStreaming` | `false` | 실행기의 스트리밍 분기 |
| `streamingOptions` | 같은 팩토리 | `defaults()` (useStreaming 시) | 아래 둘의 묶음 |
| `bufferUntilFirstSuccess` | `LlmStreamingOptions` | `false` | §5 의 직결 vs buffering |
| `includeUsage` | `LlmStreamingOptions` | `true` | provider 에 usage 요청 |
| `streaming` | `CliSettings` / `--streaming`·`--no-streaming` | **`true`** | CLI 사용자 노출 |

core 기본이 off 인데 CLI 기본이 on 인 것은 모순이 아니다 — **core 는 회귀 없는 중립 기본값을, 어셈블리는
그 제품의 UX 를 고른다.** `AimonStackBuilder` 가 `executorSpec.isStreaming()` 을 실행기 팩토리로 전달한다.
`useStreaming=false` 인데 `streamingOptions` 만 준 경우는 무시하고 debug 로 남긴다.

CLI 플래그는 picocli `negatable = true` 로 필드 하나가 `--streaming` / `--no-streaming` 둘을 만든다.
플래그가 생략되면(`null`) 설정 파일 값을 유지하고, **에이전트 셋업이 값을 읽기 전에** 덮어쓴다 — 이후에
`CliSettings` 를 변경해 봐야 `OutputFormatter` 와 실행기가 서로 다른 값을 보게 될 뿐이다.

---

## 9. 남은 것

- **tool_use 인자 partial 노출** — 지금은 `STREAM_END` 시 1회 파싱. 긴 인자를 가진 도구의 미리보기 UX 는
  별도 설계가 필요하다
- **reasoning/thinking trace 스트리밍** — provider 지원 시
- **backpressure** — 지금은 좁은 콜백. slow consumer 가 실제로 문제가 되면 `Flow.Publisher` 로 옮긴다
  (인터페이스를 좁게 유지한 이유가 이 여지다)
- **부분 텍스트 secret redaction** — 화면 출력 단계의 필터 훅. 스트리밍 고유 문제는 아니지만 노출
  시점이 빨라졌다

---

## 부록 — 참조 파일 지도

| 파일 | 확인할 것 |
|---|---|
| `llm/streaming/LlmStreamChunk.java:43-58` | 세 kind, `TOOL_USE_READY` 의 advisory 성격 |
| `llm/streaming/LlmStreamChunk.java:99-124` | `streamEnd` 3-arg / 4-arg(중립 `StopReason`) |
| `llm/streaming/LlmStreamSink.java` | 수명 계약, `NO_OP` |
| `llm/streaming/ChunkAggregator.java:62-210` | `peekText` · `toLlmResponse` · `finalizeToolCall` |
| `llm/streaming/BufferingStreamSink.java` | `flush` / `abort` / `isTerminated` |
| `llm/streaming/LlmStreamTarget.java` | 셋을 묶은 값 객체, listener 생략 시 `NOOP` |
| `llm/streaming/StreamingRetryListener.java` | 다음 시도 시작 **전** 1회 호출 계약 |
| `llm/invoke/LlmCallGateway.java:515-568` | 스트리밍 오버로드 2종(취소 토큰 유무) |
| `agent/impl/orca/OrcaAgentExecutor.java:2869-2944` | 세 종료 경로와 프리픽스 보존 |
| `agent/impl/orca/OrcaAgentExecutor.java:2971-` | `StreamingEventSink` 시도 단위 수명 |
| `agent/stream/AssistantText{Delta,StreamReset,StreamCompleted}.java` | 이벤트 필드 |
| `agent/AgentExecutionResult.java:157` | `default boolean wasStreamed()` |
| `session/routing/internal/TurnResultPayload.java:105,198` | 노드 경계를 넘는 `wasStreamed` |
| `cli/config/CliSettings.java:10-12`, `cli/AimonCli.java:46-83` | CLI 기본 ON 과 플래그 적용 시점 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준(마지막 둘은 각각
`modules/aimon-session-routing/…/at/aimon/`, `modules/aimon-cli/src/main/java/at/aimon/`).
provider 구현은 `modules/aimon-llm-{openai,anthropic}/…/{OpenAI,Anthropic}LlmClient.java`.

---

## 관련 문서

- [`cancellation.md`](cancellation.md) — 이 인프라의 abort 레버를 재사용하는 취소 설계
- [`../agent-execution/orca-executor.md`](../agent-execution/orca-executor.md) — 스트리밍-도구 중첩(eager dispatch)의 실행기 쪽 설계
- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — 그 중첩이 올라타는 디스패처 계약
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `CancellationSignal` · `CompletionReason.INTERRUPTED`
- [`../agent-execution/interceptor.md`](../agent-execution/interceptor.md) — 이벤트와 인터셉터의 역할 분리
- [`../agent-execution/compaction.md`](../agent-execution/compaction.md) — 보존된 프리픽스 메시지의 압축 정책
