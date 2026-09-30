# LLM 호출 취소 (LLM Call Cancellation)

> Status: **IMPLEMENTED** — 중립 취소 토큰, 취소-aware `LlmClient` 오버로드, 양 provider 의 능동 abort 와
> 비스트리밍 호출의 스트리밍 라우팅, 게이트웨이의 2겹 terminal 방어, 두 실행기 배선, 요청 단위 timeout 상한이
> 들어가 있다. 남은 것은 §11.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm`(`LlmCancellation`, `LlmClient` 오버로드, `LlmModel.requestTimeout`) ·
> `…llm.exception`(`LlmCallCancelledException`) · `…llm.invoke`(`LlmCallGateway`, `Sleeper`) · `…llm.retry`(정책
> carve-out) · `at.aimon.core.agent.interrupt`(`SignalBackedLlmCancellation`) ·
> `aimon-llm-anthropic` / `aimon-llm-openai` — abort 레버와 예외 매핑.

---

## 1. 문제와 범위 — 취소 신호와 실제 중단 사이의 벽시계 간극

`CancellationSignal` 이 워커 스레드에 인터럽트를 걸어도 OkHttp 소켓 read 는 `Thread.interrupt()` 에 신뢰성 있게
반응하지 않는다. abort 레버가 없으면 비스트리밍 호출은 호출이 끝난 뒤 다음 iteration 경계에서, 스트리밍 호출은
다음 chunk 가 도착해야 취소를 관측하고, 그동안 토큰(=비용)과 시간을 계속 쓴다. 이 설계는 취소 신호가 진행 중인
HTTP 호출을 직접 끊게 해 그 간극을 없앤다.

취소는 `TaskStop`, 부모 취소 cascade, 턴 인터럽트에서 온다. 그 신호 자체(`CancellationSignal`,
`InterruptCoordinator`, 도구 인터럽트)는 [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) 가
정의한다. 이 문서는 신호가 LLM 호출에 닿는 경로만 다룬다.

**하지 않는 것**:

- provider 에게 "생성을 그만두라" 고 요청하지 않는다. **클라이언트가 연결을 끊는다** — 이미 생성된 토큰은 과금될
  수 있다
- 다른 노드의 in-flight 호출을 이 노드에서 끊지 않는다. 신호가 호출을 낸 노드로 가고, 그 노드가 로컬에서 끊는다(§8)

---

## 2. 중립 취소 토큰 — `llm` 은 `agent.interrupt` 를 모른다

### 2.1 의존 제약이 설계를 정한다

`PackageDependencyArchitectureTest` 의 규칙: **`at.aimon.core.llm` 은 `at.aimon.core.base` 와
`at.aimon.core.agent.prompt`(value 타입 carve-out)에만 의존할 수 있다.**

따라서 `CancellationSignal`(`at.aimon.core.agent.interrupt` 소속)을 `LlmClient` 시그니처에 넣을 수 없다. 넣으면
`llm → agent.interrupt` 역방향 의존이 생겨 빌드가 깨진다. `Terminator` / `TerminatorRegistrar` 도 같은 패키지라
같은 벽에 부딪힌다.

그래서 **취소 토큰을 `llm` 패키지에 자체 정의하고, 두 세계는 실행기 경계에서만 어댑트한다**(§6). provider 모듈도
`agent.interrupt` 를 몰라도 되므로 provider 경계를 좁게 유지하는 원칙과 맞는다 — 그 원칙은
[`streaming.md`](streaming.md) 가 정본이다.

### 2.2 `LlmCancellation` 계약

`CancellationSignal` 의 축소판이되 `agent.interrupt` 에 의존하지 않는다. provider 가 필요로 하는 최소 연산만
노출한다.

```java
public interface LlmCancellation {

    static LlmCancellation none() { return NoopLlmCancellation.INSTANCE; }

    boolean isCancelled();                          // 폴링

    default boolean isSupported() { return true; }  // trip 될 수 있는 실제 신호인가 (§4.2 라우팅 게이트)

    void onCancel(Runnable abort);                  // abort 콜백 등록
}
```

- **단발성(single-shot)** — 한 번 취소되면 끝까지 취소 상태다
- **이미 취소된 뒤 등록된 리스너는 등록 스레드에서 동기 발화한다.** 이 규칙이 "콜이 뜨기 전에 이미 취소됨" 케이스를
  별도 분기 없이 처리한다
- `none()` 토큰(`NoopLlmCancellation`)은 절대 취소되지 않고 등록된 콜백을 버리며, `isSupported()` 를 `false` 로
  답하는 유일한 토큰이다

리스너 계약은 **thread-safe · idempotent · non-blocking · no-throw** 다. 발화 스레드가 `sendMessage` 워커와
다르기 때문이다 — 취소는 `TaskStop` 핸들러나 부모 cascade 스레드에서 도착하고, 중단할 호출과 동시에 돌 수 있다.
실제 abort 레버(`StreamResponse.close()` → OkHttp `Call.cancel()`, `CompletableFuture.cancel`)가 전부
스레드-세이프하고 멱등하므로 계약이 자연히 충족된다.

### 2.3 토큰을 파라미터로 밀어넣는다 — 핸들을 돌려받을 수 없기 때문

`sendMessage` 는 blocking 이다. 호출이 진행되는 동안 호출자에게 취소 핸들을 돌려줄 방법이 없다. 그래서 토큰을
**안으로 밀어넣고**, provider 가 콜 진입 시 자기 abort 레버를 `onCancel` 로 등록한다.
`CancellationSignal.onCancel(Runnable)` 의 발화 모델과 동형이다.

---

## 3. `LlmClient` 오버로드 — `default` 위임과 데코레이터의 전달 의무

`LlmClient` 에 새 능력을 더할 때는 **`default` 오버로드로 얹어**, 그 능력을 구현하지 않은 provider 가 컴파일도
동작도 그대로이게 한다. 스트리밍과 취소가 이 방식이다.

취소는 두 진입점에 `LlmCancellation` 파라미터를 더한 오버로드를 둔다 —
`sendMessage(SystemPromptParts, …, LlmCallMetadata, LlmCancellation)` 와
`sendMessageStreaming(SystemPromptParts, …, LlmStreamSink, LlmCancellation)`. 둘 다 토큰을 무시하고 기존 오버로드에
위임한다.

```java
default LlmResponse sendMessage(SystemPromptParts parts, List<Message> messages,
        List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
        LlmCancellation cancellation) {
    Objects.requireNonNull(cancellation, "cancellation");
    return sendMessage(parts, messages, tools, modelConfig, metadata);   // 취소 무시
}
```

취소를 override 하지 않은 provider 는 iteration 경계 취소로 동작한다. 능력은 **override 할 때만** 생긴다.

**데코레이터는 두 오버로드를 모두 override 해야 한다.** 데코레이터 체인에서 `default` 위임은 안전한 폴백이 아니라
조용한 기능 소실이다 — 한 오버로드라도 override 하지 않은 데코레이터는 토큰을 조용히 버린다. 그래서 다섯
데코레이터(`TaggingLlmClient` · `BoundMetadataLlmClient` · `LoggingLlmClient` · `MeteringLlmClient` ·
`TracingLlmClient`)는 두 오버로드를 모두 override 해 같은 토큰 인스턴스를 delegate 에 넘기고, 전파는 회귀 테스트로
고정한다.

---

## 4. provider 의 abort 레버

### 4.1 스트리밍 — `StreamResponse.close()`

Stainless SDK 의 `StreamResponse` 는 `AutoCloseable` 이고 `close()` 가 하부 OkHttp `Call` 을 cancel 한다.
스레드-세이프하고 멱등한 이 메서드가 두 provider 의 abort 레버다. 두 클라이언트는 같은 규칙을 따른다.

1. 토큰이 이미 취소 상태면 HTTP 연결을 열기 전에 `LlmCallCancelledException` 을 던진다 — 곧 끊을 요청을 보내지
   않는다
2. 스트림을 연 try-with-resources 안에서, **소비를 시작하기 전에** `cancellation.onCancel(<stream>::close)` 를
   등록한다
3. 스트림이 abort 로 풀리며 나는 예외(SDK 의 stream-closed `IOException` 등)는 토큰이 취소 상태면
   `LlmCallCancelledException` 으로 매핑한다 — 일시적 실패로 분류되지 않게

```java
try (StreamResponse<…> stream = client.…createStreaming(request)) {
    cancellation.onCancel(stream::close);   // 다른 스레드에서 즉시 close 가능
    consume(stream);
}
```

취소가 trip 되면 다음 chunk 를 기다리지 않고 즉시 `close()` 가 걸리고 스트림 반복이 풀린다. 등록과 guard 사이에
취소가 도착해도 2번의 동기 발화 규칙(§2.2)이 이미 닫힌 스트림을 읽게 해 같은 경로로 풀린다.

OpenAI 클라이언트는 `OpenAIStreamHandle` 에 이 레버를 등록하고, 핸들의 `close()` 가 `StreamResponse.close()` 에
위임한다. Chat Completions 와 Responses 중 어느 엔드포인트로 가든 등록 · 비스트리밍 라우팅 · 오류 분류는 같은
클라이언트 코드이고 엔드포인트 차이는 `OpenAIEndpointExchange` 아래에 있다([`openai-responses-path.md`](openai-responses-path.md)).
Responses 경로에서 이 동작들은 코드로 확인됐을 뿐 실측되지 않았다 — [RD-9](../../backlog/reasoning-delta-stream-open-items.md).

### 4.2 비스트리밍 — 스트리밍 경로로 라우팅한다

blocking `.create()` 에는 진행 중에 걸 수 있는 취소 핸들이 없다. 취소 가능한 비스트리밍 호출은 내부적으로
스트리밍 경로(`createStreaming()`)를 타고 `ChunkAggregator` 로 재조립해 하나의 `LlmResponse` 로 돌려준다. §4.1 의
검증된 레버를 재사용하는 것이다. 취소의 신뢰성은 SDK 릴리스마다 실측해야 하는 성질의 것이 되어서는 안 된다(§9).

- 호출자가 원한 것은 증분 전달이 아니라 비스트리밍 **결과**이므로 chunk 는 `LlmStreamSink.discarding()` 으로 버린다
- usage 는 계속 요청하므로 토큰 회계는 blocking 경로와 같다

라우팅 게이트는 `isSupported()` 다.

| 토큰 | 경로 |
|---|---|
| trip 될 수 있는 토큰(`isSupported()` = `true`, 기본값) | `createStreaming()` + 집계 |
| `LlmCancellation.none()` | **blocking `.create()` 그대로** |

비취소 호출자(일반적인 경우)는 스트리밍 오버헤드를 지지 않는다.

스트리밍을 켠 어셈블리(CLI 기본)의 실행기는 이미 스트리밍으로 LLM 을 호출하므로 §4.1 만으로 충분하다. 이 라우팅은
스트리밍을 끈 실행기(core 기본값)처럼 비스트리밍 호출을 내는 경로를 위한 대칭 완결이다 — 그 실행기도 실행마다
브릿지 토큰(§6)을 넘기므로 호출이 스트리밍으로 라우팅된다. 스트리밍 기본값은 [`streaming.md`](streaming.md) 가 정본이다.

### 4.3 라우팅이 남기는 관측 가능한 차이

스트리밍으로 라우팅된 비스트리밍 호출은 blocking `.create()` 와 관측 가능한 차이가 생긴다. **전부 `isSupported()`
토큰을 넘긴 호출에만 나타나므로 비취소 경로는 그대로다.**

| 차이 | 발생 조건 | 방침 |
|---|---|---|
| mid-stream 서버 오류의 예외 분류 | 스트림이 열린 뒤(HTTP 200) SSE `error` 이벤트 | **blocking 경로와 맞춘다** — 양 provider 의 exception mapper 가 `SseException` 을 상태코드가 아니라 오류 페이로드로 분류해 재시도 가능 타입(overloaded / rate-limited)으로 매핑한다. `SseException.statusCode()` 는 200 을 돌려주므로 상태코드로 분류하면 비재시도 오류가 되어 blocking 경로의 5xx 처리와 어긋나기 때문이다 |
| malformed tool-call 인자 | 모델이 깨진 JSON 인자를 낸 `tool_use` | **수용(OpenAI 한정)** — `ChunkAggregator` 는 깨진 JSON 을 빈 인자 맵으로 관대 처리한다(양 provider 공유). 라우팅 시 OpenAI 가 Anthropic 스트리밍과 같은 관대 시맨틱으로 수렴한다 |
| `TokenUsage` 시맨틱 | usage 청크 없이 스트림 종료 | **수용** — `TokenUsage.empty()` 폴백. provider 는 스트림 말미에 usage 를 싣는다. 취소 경로 한정의 미터링 정확도 문제이지 정합성 문제가 아니다 |
| 빈 응답 표현 | 텍스트·`tool_use` 모두 없이 종료 | **수용** — 빈 콘텐츠를 정상 반환한다(throw 없음). blocking 경로도 빈 응답을 낼 수 있다 |

---

## 5. 게이트웨이 — 취소는 terminal 이다

`LlmCallGateway`(retry · fallback · prompt-too-long 래퍼)는 두 실행기의 공통 경유지이고 **stateless** 다. 모든
상태가 단일 콜 스택에 있으므로 토큰을 **파라미터로 관통**시키기에 알맞다. 한 곳만 배선하면 두 실행기가 함께 이득을
본다.

취소 인지 지점:

1. **매 attempt 직전 short-circuit** — 취소됐으면 새 attempt 를 띄우지 않고 `LlmCallCancelledException` 을 던진다
2. **backoff sleep 직전에도 확인하고, sleep 도중에는 깨어난다** — `Sleeper.sleep(Duration, LlmCancellation)` 은 토큰이
   trip 되면 대기를 일찍 끝내므로, 긴 `Retry-After` 대기 중 도착한 취소가 다음 루프에서 즉시 short-circuit 된다
3. **취소 예외는 retry · fallback 대상이 아니다.** 스트리밍 오버로드에 버퍼링 옵션이 켜져 있으면 버퍼에 쌓인 부분
   출력은 흘리지 않고 버린다

가장 위험한 실패 모드는 **취소했는데 게이트웨이가 그것을 일시적 실패로 오인해 새 호출을 띄우는 것**이다. 취소가 새
요청을 만드는 모순이다. 그래서 `LlmCallCancelledException` 의 terminal 성질을 **서로 독립적인 두 층**으로 보장한다.

| 층 | 수단 | 무엇을 막나 |
|---|---|---|
| (a) catch 순서 | 게이트웨이의 **모든** `sendMessage` / `sendMessageStreaming` 오버로드가 `LlmClientException` 보다 **먼저** 취소를 catch → 즉시 rethrow | 정상 경로 |
| (b) 정책 carve-out | `LlmRetryPolicy.isRetryable` / `LlmFallbackPolicy.isActivating` 가 설정된 예외 집합과 **무관하게** 취소를 항상 `false` 로 판정 | 오버로드 하나를 빠뜨렸거나, 상위 타입 `LlmClientException` 이 재시도·폴백 집합에 등록된 경우 |

어느 한 층이 빠져도 취소는 retry / fallback 으로 새지 않는다.

`LlmCallCancelledException` 이 별도 타입인 이유는 게이트웨이가 "취소 vs 일시적 실패" 를 **타입만으로** 구분해야
하기 때문이다. `LlmClientException` 하위로 두어 기존 `catch (LlmClientException …)` 계약을 깨지 않는다 — 대신 그
상속 관계 때문에 **catch 순서가 계약**이 된다.

---

## 6. 실행기 배선 — 단일 리스너 브릿지

실행기는 이미 실행의 `CancellationSignal` 을 들고 있다. `SignalBackedLlmCancellation` 이 두 세계를 잇는다. llm 쪽이
아니라 **어댑트하는 쪽**(`at.aimon.core.agent.interrupt`)에 둔다 — agent 는 llm 에 의존할 수 있고 반대는 안 되기
때문이다(§2.1).

**`CancellationSignal.onCancel` 에는 deregister 가 없다.** 한 실행은 LLM 호출을 여러 번(iteration 당 1회 + 게이트웨이
재시도) 내므로, 호출마다 리스너를 등록하면 리스너와 이미 끝난 스트림 참조가 무한히 쌓이고 trip 시 끝난 호출의 abort
까지 전부 발화한다. 그래서 리스너를 **실행당 정확히 하나** 등록하고, 그 하나가 "현재 in-flight 콜" 의 abort 로
팬아웃한다.

```java
public final class SignalBackedLlmCancellation implements LlmCancellation {
    private final CancellationSignal signal;
    private final AtomicReference<Runnable> currentAbort = new AtomicReference<>();

    public SignalBackedLlmCancellation(CancellationSignal signal) {
        this.signal = signal;
        signal.onCancel(this::fireCurrentAbort);       // 실행당 리스너 1개
    }

    @Override public void onCancel(Runnable abort) {   // 콜마다 스왑
        currentAbort.set(abort);
        if (signal.isCancelled()) fireCurrentAbort();  // 이미 취소됨 계약(§2.2)
    }

    public void clearAbort() { currentAbort.set(null); }   // 콜 종료 시 — late-abort·참조 누수 방지
}
```

set 과 이미-취소됨 확인 사이의 경합으로 같은 abort 가 두 번 발화할 수 있다. abort 가 멱등이므로 무해하다.

두 실행기가 같은 모양으로 배선한다 — 브릿지를 **실행당 1회** 만들어 게이트웨이에 넘기고, 각 LLM 호출을
`try { … } finally { llmCancellation.clearAbort(); }` 로 감싼다.

| 실행기 | 신호 출처 | 취소 시 결말 |
|---|---|---|
| `OrcaAgentExecutor` (`invokeGateway`) | 턴의 신호 | `LlmCallCancelledException` → `CancelledExecutionException` → `handleInterrupted` → `CompletionReason.INTERRUPTED`. 스트리밍이면 사용자가 본 프리픽스를 전사에 먼저 보존한다 — [`streaming.md`](streaming.md) |
| `DefaultSubagentExecutor` (`runReActLoop`) | `coordinator.getSignal()` — 부모 cascade 와 로컬 도구 협조 취소를 **모두** 포섭 | `createInterruptedResult(...)` → `CompletionReason.INTERRUPTED`. 백그라운드 태스크는 정지가 요청됐으므로 `BackgroundTaskState.KILLED` 로 끝난다 |

서브에이전트 쪽 catch 순서가 특히 중요하다. `LlmCallCancelledException` 은 `LlmClientException` 의 하위이므로 뒤에
두면 취소가 `CompletionReason.ERROR` 의 LLM 실패 결과로 오분류된다.

llm → agent 방향의 예외 매핑은 **실행기에서만** 일어난다. 그래서 §2.1 의 ArchUnit 규칙을 위반하지 않는다 — 경계
어댑트가 정확히 이 지점이다.

---

## 7. 요청 단위 timeout 안전망

취소와 독립적인 hang 방어다. `LlmModel.requestTimeout`(`Optional<Duration>`, 0·음수는 생성 시 거부)을 두고, 값이
있을 때만 provider 가 SDK `RequestOptions.timeout(Duration)` 을 실은 2-arg 오버로드로 호출한다. blocking 과
스트리밍 호출 모두 이 상한을 받는다. 미설정이면 single-arg 호출 그대로이고 클라이언트 단위
`AnthropicConfig` / `OpenAIConfig` 의 `timeout`(기본 60초)이 상한이다.

새 게이트웨이 파라미터를 만들지 않는다. 값을 이미 caller → gateway → decorator → provider 로 흐르고 있는
`modelConfig` 에 싣는다. 새 표면을 만들지 않고 기존 통로에 태우는 쪽이 관통 지점마다 배선을 늘리는 것보다 낫다.

---

## 8. 노드 경계 — abort 는 노드 로컬이다

HTTP 호출 abort 는 본질적으로 노드 로컬이다 — OkHttp `Call` 은 그 호출을 낸 노드의 힙에만 있다. 서브에이전트
설계의 "실행은 노드 로컬, 신호는 공유 가능" 결정과 맞물린다.

```
노드 B: TaskStop(taskId)
   └─ TaskStopSignal.broadcastStop(taskId)
        └─ 소유 노드 A: RunningTaskHandle.requestStop()
             └─ InterruptCoordinator.requestInterrupt(PARENT_CANCELLED)   ← 실행의 CancellationSignal trip
                  └─ SignalBackedLlmCancellation 리스너 발화
                       └─ StreamResponse.close()   ← in-flight LLM 호출 즉시 abort
```

신호는 기존 `TaskStopSignal` 이 노드 사이로 나르고, abort 는 호출을 낸 노드에서 로컬로 발화한다. OkHttp `Call` 은
그 노드의 힙에만 있으므로 취소에 새 분산 인프라가 필요 없다. 앞의 두 단계(브로드캐스트와 소유 노드의 핸들 조회)는
[`../subagent/execution.md`](../subagent/execution.md#cross-node-정지) 가 정본이다.

`LlmCancellation` 은 **저장·직렬화 대상이 아니다.** 상태가 아니라 살아 있는 노드 로컬 실행 핸들이므로 멀티
인스턴스 규칙의 "저장소 인터페이스 분리" 가 적용되지 않는다 — `RunningTaskHandle` 과 같은 부류다.

---

## 9. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `CancellationSignal` 을 `LlmClient` 시그니처에 그대로 쓴다 | `llm → agent.interrupt` 역방향 의존이 생겨 ArchUnit 규칙이 빌드를 깨뜨린다(§2.1) |
| 호출이 취소 핸들을 돌려준다 | blocking `sendMessage` 는 진행 중에 호출자에게 아무것도 돌려줄 수 없다 |
| 비스트리밍 취소를 async 브릿지(`client.async()…create()` 의 `CompletableFuture` 에 `cancel(true)`)로 | `future.cancel(true)` 가 실제로 OkHttp `Call` 을 끊는지가 SDK 버전 의존적이다. 취소의 신뢰성을 SDK 릴리스마다 실측하게 된다 |
| 모든 비스트리밍 호출을 스트리밍으로 라우팅한다 | `none()` 토큰에는 끊을 것이 없는데 모든 비취소 호출자가 조용히 스트리밍 호출로 바뀐다 |
| 호출마다 `CancellationSignal.onCancel` 에 abort 를 등록한다 | deregister 가 없어 리스너와 종료된 스트림 참조가 실행 내내 쌓이고, trip 시 끝난 호출의 abort 까지 발화한다 |
| terminal 보장을 게이트웨이 catch 순서 하나에만 맡긴다 | 오버로드 하나를 빠뜨리거나 상위 타입이 재시도 집합에 들어가면 취소가 새 요청이 된다 |
| `LlmCallCancelledException` 을 `LlmClientException` 계층 밖에 둔다 | 기존 `catch (LlmClientException …)` 계약이 깨진다 |
| 깨진 tool-call 인자에서 `ChunkAggregator` 가 던지게 한다 | 집계기는 두 provider 가 공유하므로 Anthropic 스트리밍이 회귀한다 |
| mid-stream `SseException` 을 상태코드로 분류한다 | 상태코드가 200 이라 비재시도 오류가 되어 blocking 경로의 5xx 처리와 어긋난다 |
| 요청 단위 timeout 을 새 게이트웨이 파라미터로 받는다 | 관통 지점마다 배선이 늘어난다. 이미 흐르는 `modelConfig` 에 싣는다 |

---

## 10. 하지 말 것

- **데코레이터에서 취소 오버로드를 하나라도 override 없이 두지 말 것.** `default` 위임이 토큰을 조용히 버린다
- **`LlmCallCancelledException` 을 `LlmClientException` 뒤에서 catch 하지 말 것** — 게이트웨이에서는 재시도·폴백으로
  새고, 실행기에서는 LLM 실패로 오분류된다
- **`LlmRetryPolicy` / `LlmFallbackPolicy` 의 취소 carve-out 을 설정 집합으로 대체하지 말 것.** 두 층 중 하나가 된다
- **`at.aimon.core.llm` 시그니처에 `agent.interrupt` 타입을 넣지 말 것.** 경계 어댑트는 실행기에서만 한다
- **LLM 호출마다 `CancellationSignal` 에 리스너를 등록하지 말 것.** 실행당 브릿지 하나를 만들고 호출이 끝나면
  `clearAbort()` 한다
- **`none()` 토큰의 비스트리밍 호출을 스트리밍으로 라우팅하지 말 것.** 게이트는 `isSupported()` 다
- **abort 콜백에서 블로킹하거나 던지지 말 것.** 콜백은 다른 스레드에서, 중단할 호출과 동시에 돈다
- **스트림 소비를 시작한 뒤에 abort 레버를 등록하지 말 것.** 등록 전 구간의 취소가 다음 chunk 까지 관측되지 않는다
- **`LlmCancellation` 을 저장하거나 직렬화하지 말 것.** 노드 로컬 핸들이다

---

## 11. 남은 것

- **부분 과금 가시성** — abort 시 이미 생성된 토큰은 과금될 수 있다. `LlmCallCancelledException` 이 부분
  `TokenUsage`(스트리밍이면 관측된 usage)를 실어 관측·청구에 노출할지는 정하지 않았다(백로그 미등록)
- **토큰 위치 승격** — `LlmCancellation` 은 지금 llm 전용 관심사라 `at.aimon.core.llm` 에 있다. 여러 도메인이 취소
  개념을 공유할 요구가 생기면 `at.aimon.core.base` 로 옮기는 편이 중립적이지만, 그 요구가 아직 없다(백로그 미등록)
- **Responses 경로의 취소 동작 실측** — 코드 공유로 답했을 뿐 라이브 호출로 지나가 보지 않았다 —
  [RD-9](../../backlog/reasoning-delta-stream-open-items.md)

---

## 부록 — 참조 파일 지도

core 경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준, provider 는
`modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/` ·
`modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/` 기준이다.

| 파일 | 무엇을 확인하나 |
|---|---|
| `llm/LlmCancellation.java` | 단발성 · 이미 취소됨 동기 발화 · 리스너 계약 · `isSupported()` 기본값 |
| `llm/NoopLlmCancellation.java` | `isSupported()` = `false`, 콜백 폐기 — §4.2 라우팅 게이트 |
| `llm/exception/LlmCallCancelledException.java` | `LlmClientException` 하위의 terminal 타입 |
| `llm/LlmClient.java` | 취소-aware `default` 오버로드 2종 |
| `llm/LlmModel.java` | `requestTimeout` optional, 0·음수 거부 |
| `llm/invoke/LlmCallGateway.java` | 토큰 관통, attempt·backoff 직전 short-circuit, 취소를 먼저 catch |
| `llm/invoke/Sleeper.java` | 취소 시 일찍 깨는 backoff sleep |
| `llm/retry/LlmRetryPolicy.java`, `llm/retry/LlmFallbackPolicy.java` | 설정 집합과 무관한 non-retryable · non-activating carve-out |
| `llm/streaming/ChunkAggregator.java` | 스트리밍 라우팅의 재조립, 깨진 인자 관대 처리, usage 폴백 |
| `llm/logging/LoggingLlmClient.java`, `llm/usage/MeteringLlmClient.java`, `llm/tagging/TaggingLlmClient.java`, `llm/tagging/BoundMetadataLlmClient.java`, `tracing/impl/TracingLlmClient.java` | 다섯 데코레이터의 same-instance 전파 |
| `agent/interrupt/SignalBackedLlmCancellation.java` | deregister 부재, 단일 리스너, `clearAbort` |
| `agent/impl/orca/OrcaAgentExecutor.java` | 턴 배선, 취소 예외 매핑, `handleInterrupted` |
| `subagent/execution/DefaultSubagentExecutor.java` | 포크 배선, catch 순서, `createInterruptedResult` |
| `subagent/task/TaskStopSignal.java`, `subagent/task/RunningTaskHandle.java` | cross-node 정지 경로의 끝 |
| `AnthropicLlmClient.java`, `OpenAILlmClient.java` | 이미 취소됨 빠른 경로, abort 레버 등록, 비스트리밍 라우팅, `RequestOptions` timeout |
| `OpenAIStreamHandle.java`, `OpenAIEndpointExchange.java` | 두 엔드포인트가 공유하는 abort 레버 |
| `AnthropicExceptionMapper.java`, `OpenAIExceptionMapper.java` | mid-stream `SseException` 페이로드 분류 |
| `AnthropicConfig.java`, `OpenAIConfig.java` | 클라이언트 단위 `timeout` 기본값 |
| `modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java` | `llm` 패키지 의존 규칙(§2.1) |

---

## 관련 문서

- [`streaming.md`](streaming.md) — `StreamResponse` · `LlmStreamSink` · `ChunkAggregator` 인프라, 실행기의 프리픽스 보존과 스트리밍 기본값
- [`openai-responses-path.md`](openai-responses-path.md) — 두 엔드포인트가 취소 코드를 공유하는 구조(보존 동작)
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `CancellationSignal` · `InterruptCoordinator` · `Terminator` 원본
- [`../subagent/execution.md`](../subagent/execution.md) — `TaskStopSignal` 로 신호를 나르는 상위 경로
- [`.claude/rules/llm-provider.md`](../../../.claude/rules/llm-provider.md) — provider SDK 타입 비노출 규칙
- [`.claude/rules/architecture.md`](../../../.claude/rules/architecture.md) — 패키지 의존성 규칙(§2.1)
