# LLM 호출 취소 (LlmClient Cancellation)

> Status: **IMPLEMENTED** — 중립 취소 토큰, 취소-aware `LlmClient` 오버로드, 양 provider 의 능동 abort,
> gateway 의 2겹 terminal 방어, 두 실행기 배선, 요청 단위 timeout 상한이 모두 들어가 있다.
> 남은 것은 §10 — 스트리밍 라우팅이 갖는 관측 가능한 잔여 차이 3건(의도적 수용)과 부분 과금 가시성.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm`(`LlmCancellation`, `NoopLlmCancellation`, `LlmClient`
> 오버로드, `LlmModel.requestTimeout`), `…llm.exception`(`LlmCallCancelledException`),
> `…llm.invoke`(`LlmCallGateway`), `…llm.retry`(정책 carve-out),
> `at.aimon.core.agent.interrupt`(`SignalBackedLlmCancellation`) ·
> `aimon-llm-anthropic` / `aimon-llm-openai` — abort 레버 구현.

---

## 1. 문제 — 취소 신호와 실제 중단 사이의 벽시계 간극

서브에이전트의 협조적 취소는 이미 있었다. `TaskStop`(또는 부모 취소 cascade)이 `CancellationSignal` 을
trip 하고 워커 스레드에 `future.cancel(true)` 로 인터럽트를 건다. 문제는 **그 인터럽트가 진행 중인 LLM
HTTP 호출을 깨우지 못한다**는 것이다 — OkHttp 소켓 read 는 `Thread.interrupt()` 에 신뢰성 있게 반응하지
않는다.

결과적으로 취소는 두 경로 모두에서 늦게 관측되었다.

| 경로 | 취소 관측 시점 | in-flight 중단 |
|---|---|---|
| 비스트리밍 (`sendMessage` → blocking `.create()`) | 호출이 **완전히 끝난 뒤** 다음 iteration 경계 | 없음 |
| 스트리밍 (`sendMessageStreaming` → `StreamResponse`) | **다음 chunk 가 도착한 시점**의 sink checkpoint | reactive — chunk 를 기다려야 함 |

스트리밍조차 수동적이다. 취소가 chunk 와 chunk 사이에 도착하면 다음 chunk 가 올 때까지 아무 일도 일어나지
않는다. 사용자가 정지를 눌러도 서브에이전트는 그 호출이 끝날 때까지 토큰(=비용)과 시간을 계속 쓴다.

**하지 않는 것**도 분명하다. 이것은 provider 에게 "생성을 그만두라"고 요청하는 것이 아니라 **클라이언트가
연결을 끊는 것**이다 — 이미 생성된 토큰은 과금될 수 있다. 그리고 다른 노드의 in-flight 호출은 끊을 수
없다 (§9).

---

## 2. 결정적 제약 — `llm` 은 `agent.interrupt` 를 알 수 없다

`PackageDependencyArchitectureTest` 의 규칙: **`at.aimon.core.llm` 은 `at.aimon.core.base` 와
`at.aimon.core.agent.prompt`(value 타입 carve-out)에만 의존할 수 있다.**

따라서 `CancellationSignal`(`at.aimon.core.agent.interrupt` 소속)을 `LlmClient` 시그니처에 넣을 수 없다.
넣으면 `llm → agent.interrupt` 역방향 의존이 생겨 빌드가 깨진다. `Terminator` / `TerminatorRegistrar` 도
같은 패키지라 같은 벽에 부딪힌다.

이 제약이 설계 전체를 결정한다 — **취소 토큰을 `llm` 패키지에 자체 정의하고, 두 세계는 경계에서만
어댑트한다.** 부수적으로 provider 모듈이 `agent.interrupt` 를 몰라도 되므로, provider 경계를 좁게 유지하는
llm-provider 규칙과도 정합한다.

---

## 3. 중립 취소 토큰 `LlmCancellation`

`CancellationSignal` 의 축소판이되 `agent.interrupt` 에 의존하지 않는다. provider 가 필요로 하는 최소
연산만 노출한다.

```java
public interface LlmCancellation {

    static LlmCancellation none() { return NoopLlmCancellation.INSTANCE; }

    boolean isCancelled();                          // 폴링

    default boolean isSupported() { return true; }  // 실제 abort 레버를 제공하는가 (§5.2)

    void onCancel(Runnable abort);                  // abort 콜백 등록
}
```

**단발성(single-shot)** 이다 — 한 번 취소되면 끝까지 취소 상태다. 이미 취소된 뒤 등록된 리스너는 등록
스레드에서 **동기 발화**한다. 이 규칙이 "콜이 뜨기 전에 이미 취소됨" 케이스를 별도 분기 없이 처리한다.

리스너 계약은 **idempotent · non-blocking · no-throw** 다. 발화 스레드가 `sendMessage` 워커와 다르기
때문이다 — 취소는 `TaskStop` 핸들러나 부모 cascade 스레드에서 도착한다. 다행히 실제 abort 레버
(`StreamResponse.close()` → OkHttp `Call.cancel()`, `CompletableFuture.cancel`)가 전부 스레드-세이프하고
멱등하므로 계약이 자연히 충족된다.

### 파라미터 push-in — 핸들을 돌려받을 수 없기 때문

`sendMessage` 는 blocking 이다. 호출이 진행되는 동안 호출자에게 취소 핸들을 돌려줄 방법이 없다. 그래서
토큰을 **안으로 밀어넣고**, provider 가 콜 진입 시 자기 abort 레버를 `onCancel` 로 등록한다.
`CancellationSignal.onCancel(Runnable)` 의 발화 모델과 동형이다.

---

## 4. `LlmClient` 오버로드 — `default` 위임으로 회귀 0

기존 진입점에 `LlmCancellation` 파라미터를 더한 오버로드를 얹되, **전부 `default`** 로 기존 오버로드에
위임한다.

```java
default LlmResponse sendMessage(SystemPromptParts parts, List<Message> messages,
        List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
        LlmCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    return sendMessage(parts, messages, tools, modelConfig, metadata);   // 취소 무시
}
```

취소를 override 하지 않은 provider 는 컴파일도 통과하고 동작도 그대로다 — 자동으로 예전의 "iteration
경계 취소"로 회귀한다. 능력은 **override 할 때만** 생긴다.

**decorator 는 두 오버로드를 모두 override 해야 한다.** 5종(`Tagging` · `BoundMetadata` · `Logging` ·
`Metering` · `Tracing`)이 토큰을 delegate 에게 **같은 인스턴스로** 넘긴다. 초기 구현에서 `Logging` 과
`Metering` 이 비스트리밍 오버로드를 빠뜨려 `LlmClient` 의 default 가 토큰을 조용히 버렸다 — 데코레이터
체인에서 `default` 위임은 안전한 폴백이 아니라 **조용한 기능 소실**이라는 교훈이고, 그래서 전파는
회귀 테스트로 고정되어 있다.

---

## 5. provider 의 중단 레버

### 5.1 스트리밍 — `StreamResponse.close()`

Stainless SDK 의 `StreamResponse` 는 `AutoCloseable` 이고 `close()` 가 하부 OkHttp `Call` 을 cancel 한다.
**이미 존재하는 스레드-세이프한 중단 레버**였는데, 지금까지는 정상 소비 완료나 sink 예외로 인한
try-with-resources 해제 때만 호출되었다. 취소 신호에 능동적으로 반응하지 않았다.

```java
try (StreamResponse<RawMessageStreamEvent> streamResponse = client.messages().createStreaming(request)) {
    cancellation.onCancel(streamResponse::close);   // 다른 스레드에서 즉시 close 가능
    mapper.consume(streamResponse.stream());
}
```

한 줄이 chunk 간극 문제를 없앤다 — 취소가 trip 되면 chunk 를 기다리지 않고 즉시 `close()` 가 걸리고,
`stream()` 반복이 풀리며, provider 가 그것을 `LlmCallCancelledException` 으로 매핑한다.

### 5.2 비스트리밍 — 스트리밍으로 라우팅한다

blocking `.create()` 에는 취소 핸들이 없다. 두 갈래가 있었다.

- **A. async 브릿지** — `client.async()…create()` 로 `CompletableFuture` 를 받아 `cancel(true)` 를 건다.
  깔끔해 보이지만 **`future.cancel(true)` 가 실제로 OkHttp `Call` 을 끊는지가 SDK 버전 의존적**이다
- **B. 스트리밍 라우팅** — 내부적으로 `createStreaming()` 을 타고 `ChunkAggregator` 로 재조립해 하나의
  `LlmResponse` 로 돌려준다. §5.1 의 **검증된 레버를 재사용**한다

**B 를 택했다.** 취소의 신뢰성은 SDK 릴리스마다 실측해야 하는 성질의 것이 되어서는 안 된다.

라우팅 게이트는 `isSupported()` 다. `NoopLlmCancellation` 만 `false` 로 override 하고 실 토큰은 default
`true` 를 상속하므로 —

- 취소 가능한 호출 → `createStreaming()` + 집계
- inert `none()` 토큰 → **기존 blocking `.create()` 그대로**

비취소 호출자는 스트리밍 오버헤드를 한 톨도 지지 않는다. `useStreaming=false` 를 명시한 소비자의 의도도
보존된다.

프로덕션 주경로(`OrcaAgentExecutor`)는 이미 스트리밍이므로, 이 항목은 비스트리밍을 명시적으로 쓰는 잔여
경로를 위한 **대칭 완결**이다. 그 대가로 생기는 관측 가능한 차이는 §10 에 정리했다.

---

## 6. gateway 관통 — 취소는 terminal 이다

`LlmCallGateway`(retry · fallback · prompt-too-long 래퍼)는 두 실행기의 공통 경유지이고 **stateless** 다.
모든 상태가 단일 콜 스택에 있으므로 토큰을 **파라미터로 관통**시키기에 이상적이다. 한 곳만 배선하면 두
실행기가 동시에 이득을 본다.

두 가지 취소 인지 지점을 둔다.

1. **매 attempt 직전 short-circuit** — 취소됐으면 새 attempt 를 띄우지 않는다. 특히 **backoff sleep
   직전**에 체크해서 `Retry-After` 대기 중 취소가 즉시 반영되게 한다
2. **취소 예외는 non-retryable**

가장 위험한 실패 모드가 여기 있다 — **취소했는데 gateway 가 그것을 일시적 실패로 오인해 새 호출을 띄우는
것**이다. 취소가 새 요청을 만드는 모순이다. 그래서 `LlmCallCancelledException` 의 terminal 성질을 **서로
독립적인 두 층**으로 보장한다.

| 층 | 수단 | 무엇을 막나 |
|---|---|---|
| (a) catch-ordering | gateway 의 **모든** `sendMessage` 오버로드가 `LlmClientException` 보다 **먼저** catch → 즉시 rethrow | 정상 경로 |
| (b) 정책 carve-out | `LlmRetryPolicy.isRetryable` / `LlmFallbackPolicy.isActivating` 가 설정된 예외 집합과 **무관하게** 취소를 항상 `false` 로 판정 | 오버로드 하나를 빠뜨렸거나, 상위 타입 `LlmClientException` 이 재시도 집합에 등록된 경우 |

어느 한 층이 실수로 빠져도 취소는 retry / fallback 으로 새지 않는다.

`LlmCallCancelledException` 이 별도 타입인 이유는 gateway 가 "취소 vs 일시적 실패"를 **타입만으로**
구분해야 하기 때문이다. `LlmClientException` 하위로 두어 기존 catch 계약도 깨지 않는다 — 대신 그 상속
관계 때문에 **catch 순서가 계약**이 된다.

---

## 7. 실행기 배선 — 단일-리스너 브릿지

실행기는 이미 `CancellationSignal` 을 들고 있다. `SignalBackedLlmCancellation`
(`at.aimon.core.agent.interrupt` — llm 쪽이 아니라 **어댑트하는 쪽**에 둔다)이 두 세계를 잇는다.

여기에 조용한 누수가 하나 숨어 있다. **`CancellationSignal.onCancel` 에는 deregister 가 없다.** 한 실행은
LLM 호출을 여러 번(iteration 당 1회) 내는데, 매 콜이 리스너를 새로 등록하면 **리스너가 무한히 쌓이고**
이미 종료된 `StreamResponse` 참조가 함께 쌓인다.

해법은 리스너를 **정확히 하나만** 등록하고, 그 하나가 "현재 in-flight 콜"의 abort 로 팬아웃하는 것이다.

```java
public final class SignalBackedLlmCancellation implements LlmCancellation {
    private final AtomicReference<Runnable> currentAbort = new AtomicReference<>();

    SignalBackedLlmCancellation(CancellationSignal signal) {
        signal.onCancel(this::fireCurrentAbort);       // 실행당 리스너 1개
    }

    @Override public void onCancel(Runnable abort) {   // 콜마다 스왑
        currentAbort.set(abort);
        if (signal.isCancelled()) abort.run();
    }

    public void clearAbort() { currentAbort.set(null); }   // 콜 종료 시 — late-abort·참조 누수 방지
}
```

두 실행기가 같은 형태로 배선한다 — 브릿지를 **실행당 1회** 만들고 각 콜을
`try { … } finally { llmCancellation.clearAbort(); }` 로 감싼다.

| 실행기 | 신호 출처 | 취소 시 결말 |
|---|---|---|
| `OrcaAgentExecutor#invokeGateway` | 턴 신호 | `CancelledExecutionException` → 기존 `handleInterrupted` → `CompletionReason.INTERRUPTED`. 스트리밍이면 `sink.peekText()` 부분 텍스트를 먼저 보존 |
| `DefaultSubagentExecutor#runReActLoop` | `coordinator.getSignal()` — parent cascade 와 로컬 도구 협조 취소를 **모두** 포섭 | `createInterruptedResult(...)` → `KILLED` |

서브에이전트 쪽 catch 순서가 특히 중요하다. `LlmCallCancelledException` 은 `LlmClientException` 의
하위이므로, 뒤에 두면 취소가 **`FAILURE` 로 오분류**된다. `KILLED` 여야 할 것이 `FAILED` 로 보이는 이
회귀는 눈에 잘 띄지 않으므로 sharp 테스트로 고정되어 있다.

llm → agent 방향의 매핑은 **실행기에서만** 일어난다. 그래서 §2 의 ArchUnit 규칙을 위반하지 않는다 —
경계 어댑트가 정확히 이 지점이다.

---

## 8. 요청 단위 timeout 안전망

취소와 독립적인 hang 방어다. `LlmModel.requestTimeout`(`Optional<Duration>`, 0·음수는 생성 시 거부)을
두고, 값이 있을 때만 provider 가 SDK `RequestOptions.timeout(Duration)` 2-arg 오버로드로 라우팅한다.
미설정이면 기존 single-arg 콜 그대로이고 per-client `Config.timeout`(기본 60초)이 계속 상한이다.

새 gateway 파라미터가 **필요 없다**는 것이 이 설계의 요점이다. 값을 이미 caller → gateway → decorator →
provider 로 흐르고 있는 `modelConfig` 에 실었기 때문이다. 새 표면을 만들지 않고 기존 통로에 태우는 쪽이
관통 지점마다 배선을 늘리는 것보다 낫다.

---

## 9. cross-node 정합

HTTP 호출 abort 는 **본질적으로 노드-로컬**이다 — OkHttp `Call` 은 그 호출을 낸 노드의 힙에만 있다.
서브에이전트 설계의 "실행은 노드-로컬, 신호는 공유 가능" 결정과 정확히 맞물린다.

```
노드 B: TaskStop(taskId)
   └─ TaskStopSignal.broadcastStop(taskId)
        └─ 노드 A: 로컬 RunningTaskHandle.requestStop()
             └─ CancellationSignal.trip(PARENT_CANCELLED)
                  └─ SignalBackedLlmCancellation 리스너 발화
                       └─ StreamResponse.close()   ← in-flight LLM 호출 즉시 abort
```

이 설계는 **cross-node 취소 스토리의 마지막 한 칸**이다. 이전에는 신호가 소유 노드에 도달해도 그 노드가
in-flight 호출이 끝날 때까지 기다렸다. 새 분산 인프라는 없다 — 기존 `TaskStopSignal` 이 신호를 나르고
abort 는 로컬에서 발화한다.

`LlmCancellation` 자체는 **저장·직렬화 대상이 아니다.** 상태가 아니라 살아 있는 노드-로컬 실행 핸들이므로
multi-instance 규칙의 "저장소 인터페이스 분리"가 적용되지 않는다 — `RunningTaskHandle` 과 같은 부류다.

---

## 10. 스트리밍 라우팅의 잔여 차이

§5.2 의 대안 B 는 취소 가능한 비스트리밍 호출을 SSE 로 라우팅한 뒤 재조립한다. blocking `.create()` 와
관측 가능한 차이가 생긴다. **전부 `isSupported()` 토큰에만 나타나므로 비취소 경로는 종전 그대로다.**

| 차이 | 발생 조건 | 방침 |
|---|---|---|
| mid-stream 서버 오류의 예외 분류 | 스트림 오픈(HTTP 200) 후 SSE `error` 이벤트 | **교정함** — `SseException.statusCode()` 가 200 을 반환해 mapper 가 비재시도 base 로 오분류하던 것을, 양 mapper 에 `instanceof SseException` 분기를 넣어 재시도 가능 타입(overloaded / rate-limited)으로 바로잡았다. blocking 경로의 등가 5xx 처리와 일치한다 |
| malformed tool-call 인자 | 모델이 깨진 JSON 인자를 낸 `tool_use` | **수용(OpenAI 한정)** — `ChunkAggregator.parseArguments` 는 깨진 JSON 을 빈 맵으로 관대 처리한다(양 provider 공유). 라우팅 시 OpenAI 가 Anthropic 과 같은 관대 시맨틱으로 **수렴**한다. 집계기를 throw 로 바꾸면 Anthropic 스트리밍이 회귀하므로 불가 |
| `TokenUsage` 시맨틱 | usage 청크 없이 스트림 종료 | **수용** — `TokenUsage.empty()` 폴백. 프로덕션 provider 는 말미에 usage 를 싣는다. 취소 경로 한정의 미터링 정확도 문제이지 정합성 문제가 아니다 |
| 빈 응답 표현 | 텍스트·`tool_use` 모두 없이 종료 | **수용** — 빈 콘텐츠를 정상 반환(throw 없음). blocking 경로도 동일하게 빈 응답을 낼 수 있다 |

---

## 11. 남은 것

- **부분 과금 가시성** — abort 시 이미 생성된 토큰은 과금될 수 있다. `LlmCallCancelledException` 이 부분
  `TokenUsage`(스트리밍이면 관측된 usage)를 실어 관측·청구 정합에 노출할지는 열려 있다
- **토큰 위치 승격** — `LlmCancellation` 은 지금 llm 전용 관심사라 `at.aimon.core.llm` 에 있다. 여러
  도메인이 취소 개념을 공유할 요구가 생기면 `at.aimon.core.base` 로 승격하는 편이 중립적이다

---

## 부록 — 참조 파일 지도

| 파일 | 확인할 것 |
|---|---|
| `llm/LlmCancellation.java:50-89` | `isCancelled` / `isSupported` default true / `onCancel` 계약 |
| `llm/NoopLlmCancellation.java` | `isSupported()` 를 `false` 로 override — §5.2 라우팅 게이트 |
| `llm/exception/LlmCallCancelledException.java` | `LlmClientException` 하위 marker |
| `llm/LlmClient.java` | 취소-aware `default` 오버로드 2종 |
| `llm/LlmModel.java:36,72,151` | `requestTimeout` optional, 0·음수 거부 |
| `llm/invoke/LlmCallGateway.java:259,360-381,403` | 토큰 관통, `none()` 위임 오버로드, 취소 먼저 catch |
| `llm/retry/LlmRetryPolicy.java:33,173-186` | 설정 집합과 무관한 non-retryable carve-out |
| `llm/retry/LlmFallbackPolicy.java:208-222` | non-activating carve-out |
| `llm/streaming/ChunkAggregator.java` | 스트리밍 라우팅의 재조립기 |
| `agent/interrupt/SignalBackedLlmCancellation.java:20-77` | deregister 부재 문제, 단일 리스너, `clearAbort` |
| `llm/logging/LoggingLlmClient.java`, `llm/usage/MeteringLlmClient.java`, `llm/tagging/{Tagging,BoundMetadata}LlmClient.java`, `tracing/impl/TracingLlmClient.java` | 5종 decorator 의 same-instance 전파 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준. provider 구현은
`modules/aimon-llm-anthropic/…/AnthropicLlmClient.java`, `modules/aimon-llm-openai/…/OpenAILlmClient.java`
및 각 모듈의 exception mapper.

---

## 관련 문서

- [`streaming.md`](streaming.md) — `StreamResponse` · `LlmStreamSink` 인프라 (이 설계가 그 위에 선다)
- [`../subagent/execution.md`](../subagent/execution.md) — `TaskStopSignal` 로 신호를 나르는 상위 경로
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `CancellationSignal` · `Terminator` 원본
- [`.claude/rules/llm-provider.md`](../../../.claude/rules/llm-provider.md) — provider SDK 타입 비노출 규칙
- [`.claude/rules/architecture.md`](../../../.claude/rules/architecture.md) — `llm` 패키지 의존성 규칙(§2)
