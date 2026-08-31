# 실행 트레이싱 (Tracing) 설계

> LangSmith 스타일의 계층형 실행 트레이스를 남겨 LLM 에이전트 실행을 사후 디버깅한다.
>
> 적용 대상: `aimon-core` (`at.aimon.core.tracing`), `aimon-cli`
> Status: **핵심 경로 구현 완료** — TURN→ITERATION→{LLM, TOOL} 트리 + payload 캡처 + 레닥션.
> 미구현 항목은 §9 에 명시한다.

---

## 1. 개요

### 1.1 목적

한 번의 **턴**을 계층형 Span 트리로 기록하여 다음을 사후에 재구성한다.

- 어떤 프롬프트로 LLM 을 호출했고 어떤 응답·토큰·지연이 나왔는가
- ReAct 루프가 몇 번 돌았고 각 iteration 에서 무엇을 했는가
- 어떤 도구를 어떤 입력으로 호출했고 무엇을 반환했는가 (성공/실패/지연)
- 서브에이전트가 어떤 목표로 spawn 됐고 내부에서 무엇을 했는가
- 어디서 에러·중단·compaction 이 발생했는가

### 1.2 배경 — 새 메커니즘이 아니라 횡단 소비자

AIMON 에는 이미 관찰·제어에 인접한 메커니즘이 **4종** 있다. 그러나 어느 것도 "한 턴 전체를 부모-자식
Span 트리로 영속화" 하지 못한다 — 특히 **LLM 호출 자체를 관측할 지점이 없다**.

| 메커니즘 | 패키지 | 경계 | 트레이싱 관점의 가치 |
|---|---|---|---|
| `AgentExecutionInterceptor` | `agent.interceptor` | `execute()` 1회 | 턴 root span 경계로 적합하나 ReAct 내부는 못 봄 |
| Hook 시스템 | `hook` | 도구·라이프사이클 | 풍부한 입출력 payload. tool/subagent/compaction span 소스 |
| `AgentExecutionEvent` | `agent.stream` | ReAct 내부 도달점 | iteration 경계를 주는 **유일한** 소스 |
| `LlmCallMetadata` | `llm` | LLM 호출당 | `traceId`/`component`/`parentComponent`/`tags` 보유 → **Span 컨텍스트 캐리어로 재활용** |

> **핵심 인사이트**: 트레이싱은 *새 관찰 메커니즘*이 아니라 *기존 4종을 소비하는 횡단 관심사*다.
> 새로 만드는 것은 ① Span 도메인 모델 ② Span 트리 조립기(`Tracer`) ③ 저장/반출 SPI 셋이고,
> 보강하는 코드 변경은 **LLM Span 캡처(데코레이터) 한 곳**과 **metadata enrich 한 곳**뿐이다.

### 1.3 핵심 설계 원칙

- **Fail-safe** — 트레이싱은 어떤 경우에도 에이전트 실행을 깨뜨리지 않는다. 모든 Span 연산은 격리되고
  실패 시 조용히 drop 한다. 기본값 `Tracer.noop()` 으로 오프 시 제로 오버헤드.
- **비침투 우선 (OCP)** — 데코레이터 + 기존 `EventEmitter`/Hook 경로를 우선하고, 정확한 중첩이
  필요한 곳만 executor 에 최소 주입점을 둔다.
- **명시적 컨텍스트 전파 (thread-local 금지)** — 병렬 도구 디스패치와 async 스트리밍 때문에
  thread-local 부모 추적은 깨진다. SpanContext 는 **이미 존재하는 캐리어**(`LlmCallMetadata`)에
  명시적으로 싣는다.
- **멀티 인스턴스 대응** — Span 저장은 `TraceSpanStore` 인터페이스로 분리하고 in-memory 기본 구현을
  제공한다. 백엔드 교체는 리팩토링이 아니라 구현체 교체.
- **벤더 중립** — Span 모델을 OpenTelemetry GenAI semantic conventions 에 호환되게 두어 자체 뷰어 대신
  외부 UI(Jaeger / Tempo / Langfuse / Phoenix)로 반출한다.

### 1.4 비목표

- 자체 트레이스 뷰어 UI (외부 OTLP 백엔드로 위임 — §6.5)
- 분산 추적용 W3C `traceparent` 헤더 전파 (HTTP 경계는 범위 밖)
- 프롬프트 평가/회귀 데이터셋(LangSmith 의 evaluation)

### 1.5 용어

| 용어 | 정의 |
|---|---|
| **Session** | 하나의 세션(`SessionId`) 전체. 여러 턴(trace)을 묶는 상위 그룹. 기존 `LlmCallMetadata.traceId` 에 대응 (LangSmith 의 thread/session) |
| **Trace** | 한 **턴**(`LiveSession.submit()` 1회 → `executor.execute()` 1회)에 대응하는 Span 트리 전체. **턴 root span id 로 식별** |
| **Span** | 트레이스 내 단일 작업 단위(turn/iteration/llm/tool/subagent/compaction) |
| **Root Span** | 트레이스 최상위 Span(turn). `parentSpanId == null`. 이 span id 가 곧 traceId |
| **SpanContext** | 자식에게 전파되는 경량 토큰 `(sessionId, traceId, spanId, parentSpanId)` |
| **Tracer** | Span 을 시작·종료하는 SPI. 절대 throw 하지 않음 |
| **Exporter** | 완료 Span 을 외부 시스템(OTLP 등)으로 반출하는 SPI |

> **id 용어 정리**: `LlmCallMetadata.traceId` 필드는 실제로 세션 id 이며 본 설계는 이를 **session id**
> 로 재해석한다(기존 의미·코드 무변경). per-turn **trace** 는 턴 root span id 로 별도 식별하고,
> 그 값을 예약 태그로 metadata 에 함께 싣는다 (§4.3).

---

## 2. 기존 자산과 공백

### 2.1 재사용 자산

**`LlmCallMetadata`** — 트레이싱 백본. `getTraceId()` / `getComponent()` / `getParentComponent()` /
`getTags()` / `withDefaults(defaults)`(비파괴 병합) + `Feature.REACT_LOOP`·`Feature.COMPACTION` 상수.

**`LlmResponse`** — LLM Span 데이터로 충분. `getTextContent()` / `getToolUses()` / `getTokenUsage()`.

**`LlmClient.sendMessage(systemPrompt, messages, tools, modelConfig, metadata)`** — 권위 진입점이자
데코레이터로 감쌀 유일한 지점.

**`AgentExecutionEvent`** (sealed) — `IterationStarted` · `AssistantMessageReceived` · `ToolUseStarted` ·
`ToolResultReady` · `IterationCompleted` · `ExecutionCompleted` · `ExecutionError` · `CompactBoundary` ·
`InterruptedAt`. 각 이벤트가 `timestamp` + `AgentRuntimeId` + `iteration` 보유.

**Hook (`HookEventType`)** — `PRE_TOOL`/`POST_TOOL`, `ON_START`/`ON_STOP`(`ExecutionMetadata`),
`SUBAGENT_START`/`SUBAGENT_STOP`, `PRE_COMPACT`/`POST_COMPACT`, `PERMISSION_*`.

**`SessionRecordStore`** — `TraceSpanStore` 가 그대로 모방할 멀티 인스턴스 저장 패턴.

### 2.2 공백

| 항목 | 문제 |
|---|---|
| **LLM 호출 관측** | Hook/Interceptor 모두 가로채지 못함 → **결정적 공백**. 유일한 길은 `LlmClient` 데코레이터 |
| 부모-자식 연결 | 이벤트는 iteration 번호만 제공. 병렬 도구·subagent 중첩의 부모 결정이 모호 |
| Span 영속화 | 없음 → 사후 디버깅·조회 불가 |
| 민감정보 | 없음 → 프롬프트/도구 입력의 credential·PII 노출 위험 |

### 2.3 기존 부분 구현 — 발명이 아니라 확장

AIMON 에는 이미 **coarse 한 이름 기반 usage-attribution 체인**이 있다. 트레이싱은 이를 대체하지 않고
그 위에 고유 invocation id·정확한 중첩·영속화를 얹는다.

| 자산 | 현재 동작 |
|---|---|
| traceId = sessionId | `OrcaAgentExecutor` 가 `effectiveMetadata = request.metadata.withDefaults(component=agentName, feature="react-loop", traceId=sessionId)` |
| 이름 기반 부모 체인 | `SubagentLlmDefaults.effectiveMetadata` — `component=subagentName`, `parentComponent=부모 component`, `feature="subagent"`, 나머지는 `withDefaults` 로 상속 |
| ToolContext 전파 스파인 | `ToolContextKeys.LLM_CALL_METADATA_KEY` — executor 가 effective metadata 를 주입 → `TaskTool` 등 sub-execution 진입점이 읽어 서브에이전트에 전파 |

| 측면 | 기존 (component 체인) | 트레이싱 (span 트리) |
|---|---|---|
| 식별 단위 | 컴포넌트 **이름** (저카디널리티) | invocation 별 **고유 span id** (고카디널리티) |
| 입도 | 세션 1개가 traceId 공유 | 턴 = trace, 세션 = session |
| 중첩 | 단일 레벨 부모(이름)만 | turn→iteration→llm/tool→subagent 완전 중첩 |
| 동일 컴포넌트 2회 호출 | 구분 불가 | span id 로 구분 |
| 영속·조회 | 없음 | `TraceSpanStore` 로 영속·트리 재구성 |
| 용도 | 토큰 사용량 **집계** | 실행 **디버깅** |

두 체계는 **공존**한다. `component`/`parentComponent` 는 저카디널리티 집계용으로 보존하고, 고유 span
id 는 예약 태그로 분리해 싣는다 (§4.3, §6.3).

---

## 3. 아키텍처

```
┌───────────────────────────────────────────────────────────────────────┐
│  관찰 소스                                                              │
│   Hook           AgentExecutionEvent        TracingLlmClient (신규)      │
│   (tool/subagent  (iteration 경계)          (LLM 호출 데코레이터)         │
│    /compaction)          │                          │                   │
└───────┬──────────────────┬──────────────────────────┬──────────────────┘
        ▼                  ▼                          ▼
┌───────────────────────────────────────────────────────────────────────┐
│  Tracer — Span 시작/종료, 부모-자식 조립, fail-safe                      │
│   startRoot(...) / startChild(parent, ...) ─▶ Span ─▶ close()           │
└───────────────────────────────────┬───────────────────────────────────┘
                                     │  완료된 TraceSpan (불변, 레닥션 후)
                       ┌─────────────┴─────────────┐
                       ▼                           ▼
        ┌──────────────────────────┐   ┌──────────────────────────┐
        │ TraceSpanStore (SPI)     │   │ SpanExporter (SPI)        │
        │  · InMemory (core 기본)   │   │  · noop (기본)            │
        │  · postgres / mongo (외)  │   │  · OTLP / Langfuse (외)   │
        └──────────────────────────┘   └──────────────────────────┘
                       │
                       ▼
              조회 (byTrace = 한 턴, bySession = 한 세션)
```

### 3.1 4계층에서의 위치

인터셉터 설계가 정의한 3계층에 트레이싱을 더한다. 트레이싱은 이들을 **대체하지 않고 소비**한다.

| 계층 | 메커니즘 | 관심사 | 트레이싱과의 관계 |
|---|---|---|---|
| 실행 체인 제어 | Interceptor | 차단·변환 | 턴 root span 경계로 활용 가능 |
| 도구 라이프사이클 | Hook | 도구 제어·감사 | tool/subagent/compaction Span **소스** |
| 진행 관찰 | Event | UI·로그 tail | iteration Span **소스** |
| **사후 디버깅** | **Tracing** | **Span 트리 영속화** | 위 3종을 소비 + LLM Span 보강 |

---

## 4. 핵심 설계

### 4.1 도메인 모델

```java
public enum SpanType   { TURN, ITERATION, LLM, TOOL, SUBAGENT, COMPACTION }
public enum SpanStatus { OK, ERROR, INTERRUPTED }
```

`TraceSpan` — 불변 값 객체(빌더, `record` 금지):

| 필드 | 타입 | TURN | ITER | LLM | TOOL | SUBAGENT | 설명 |
|---|---|:--:|:--:|:--:|:--:|:--:|---|
| `sessionId` | String | ● | ● | ● | ● | ● | 세션 단위 그룹 (= 기존 metadata.traceId) |
| `traceId` | String | ● | ● | ● | ● | ● | 턴 식별 (= 턴 root span id) |
| `spanId` | String | ● | ● | ● | ● | ● | Span 식별 |
| `parentSpanId` | String | – | ● | ● | ● | ● | root 는 null |
| `type` / `name` | SpanType / String | ● | ● | ● | ● | ● | 도구명 / 모델명 / `iteration#N` |
| `startTime` / `endTime` | Instant | ● | ● | ● | ● | ● | 지연 계산 (`latencyMillis()`) |
| `status` | SpanStatus | ● | ● | ● | ● | ● | OK / ERROR / INTERRUPTED |
| `inputs` / `outputs` | Object | ● | – | ● | ● | ● | userMessage·프롬프트·도구입력·goal / finalAnswer·응답·도구출력·summary |
| `errorMessage` | String | △ | △ | △ | △ | △ | ERROR 시 |
| `tokenUsage` | TokenUsage | △ | – | ● | – | △ | LLM 필수, turn/subagent 는 합계 |
| `model` | String | – | – | ● | – | – | LLM 한정 |
| `attributes` | Map | ● | ● | ● | ● | ● | agentRuntimeId, sessionId, iteration, principal, invokerType |

(● 필수 / △ 조건부 / – 해당 없음)

> **시각 주의**: 값 객체는 시각을 **받기만** 한다. `Instant.now()` 호출은 `DefaultTracer` 내부 한
> 지점으로 격리하여 고정 `Clock` 주입으로 테스트 가능하게 한다.

### 4.2 `Tracer` SPI

```java
public interface Tracer {

    static Tracer noop();      // 기본값 — 제로 오버헤드

    /** 루트(턴) Span. */
    Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs);

    /** 자식 Span. parent 의 trace/session 을 상속한다. */
    Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs);

    interface Span extends AutoCloseable {
        SpanContext context();                  // 자식 Span 의 parent 로 전달
        void setOutputs(Object outputs);
        void setTokenUsage(TokenUsage usage);   // LLM Span
        void setModel(String model);            // LLM Span
        void setAttribute(String key, String value);
        void error(Throwable t);
        void error(String message);
        void interrupted();
        @Override void close();                 // endTime 확정 → 레닥션 → store.record() + exporter.export()
    }
}
```

`startRoot` / `startChild` 를 **분리한** 이유는 nullable `parent` 파라미터 하나로 두 의미를 겸하게
하면 호출부에서 "root 를 의도했는지, 부모를 잃어버렸는지" 를 구분할 수 없기 때문이다. 루트는
`sessionId` 를 반드시 요구하고 자식은 `SpanContext` 를 반드시 요구한다 — 타입이 의도를 강제한다.

- **`NoopTracer`** — 모든 메서드 무동작. `start*()` 는 빈 핸들을 반환한다. 트레이싱 오프 시 분기·할당
  최소화.
- **`DefaultTracer`** — `TraceSpanStore` + `SpanExporter` + `SpanRedactor` + `Clock` + id 생성기를
  생성자 주입. `start*()` 에서 `spanId` 생성, `close()` 에서 레닥션 후 store/exporter 로 기록.

### 4.3 SpanContext 전파 — 명시적, thread-local 금지

**이미 존재하는 단일 캐리어 `LlmCallMetadata` 에 SpanContext 를 싣는다.** 새 `ToolContext` 키를 만들지
않는다 — 전파 스파인(`LLM_CALL_METADATA_KEY`)이 이미 있다 (§2.3).

```java
public final class SpanContext {          // 경량 불변
    public static final String TAG_TRACE_ID       = "aimon.trace_id";
    public static final String TAG_PARENT_SPAN_ID = "aimon.parent_span_id";

    public static SpanContext root(String sessionId, String rootSpanId);   // trace=span=root, parent=null
    public static SpanContext of(String sessionId, String traceId, String spanId, String parentSpanId);
    public SpanContext child(String childSpanId);                          // parent=this.spanId, trace/session 상속

    public LlmCallMetadata writeInto(LlmCallMetadata base);                // 예약 태그로 직렬화
    public static Optional<SpanContext> readFrom(LlmCallMetadata metadata); // 예약 태그에서 복원
}
```

**예약 태그 매핑** — `component`/`parentComponent` 의 기존 저카디널리티 집계 의미를 그대로 두고, 고유
span id 만 태그로 분리한다.

```
metadata.traceId                       = sessionId  (= 기존 값 그대로)
metadata.tags["aimon.trace_id"]        = traceId    (턴 root span id)
metadata.tags["aimon.parent_span_id"]  = spanId     (이 호출의 부모 = 현재 활성 Span)
```

`TracingLlmClient` 는 이 태그를 읽어 LLM Span 을 `parent_span_id` 아래, `trace_id` 트리에 매단다.
`withDefaults()` 가 호출자 metadata 와 병합하므로 기존 호출부는 깨지지 않는다.

**전파 경로**

```
executor: effectiveMetadata 빌드 지점에서 root/iteration SpanContext 를 writeInto
   │  (= 기존 component/feature/traceId enrich 지점에 태그 2개 추가 — 변경 한 곳)
   ├─▶ LLM 호출:  gateway.sendMessage(..., metadata)     → TracingLlmClient 가 readFrom
   └─▶ ToolContext: LLM_CALL_METADATA_KEY = metadata     (이미 주입되어 있음)
            └─▶ TaskTool → SubagentLlmDefaults.effectiveMetadata(parentMetadata)
                   │  withDefaults(parentMetadata) 로 태그 자동 상속
                   └─▶ 서브에이전트는 자기 span 으로 parent_span_id 를 "회전"시켜 자식에 전파
```

> **`withDefaults` 태그 병합 주의**: `SubagentLlmDefaults.effectiveMetadata` 는
> `builder(...).build().withDefaults(parentMetadata)` 형태이고 `withDefaults` 는 **`this` 가 태그
> 충돌 시 승리**한다. 따라서 서브에이전트가 자기 span id 로 `aimon.parent_span_id` 를 회전시키려면
> 그 태그를 `builder(...)` 쪽(=`this`)에 써야 부모 값을 덮어쓴다. 회전 없이 단순 상속하면 부모 태그가
> 그대로 흘러 LLM 호출이 tool Span 아래 직접 매달린다 — **회전이 필요한 곳은 서브에이전트 경계뿐**이다.

### 4.4 `TraceSpanStore` / `SpanExporter`

```java
public interface TraceSpanStore {                // application-scoped, thread-safe
    void record(TraceSpan span);
    Optional<TraceSpan> get(String spanId);
    List<TraceSpan> byTrace(String traceId);     // 트리 재구성 — 한 턴 전체
    List<TraceSpan> bySession(String sessionId); // 한 세션의 모든 턴
    void deleteOlderThan(Instant cutoff);        // 보존 정책
}

public interface SpanExporter {
    void export(TraceSpan span);
    default void flush() {}
    static SpanExporter noop();                  // 기본값
}
```

- **`InMemoryTraceSpanStore`**(core 기본) — `ConcurrentHashMap` + traceId/sessionId 보조 인덱스.
  CLI 디버깅용으로 **bounded**(최근 N 트레이스)하여 메모리 상한을 둔다.
- 외부 모듈 — `aimon-tracing-postgres` / `aimon-tracing-mongodb` 가 동일 인터페이스를 구현
  (`aimon-session-*` 구성과 동형).
- **`aimon-tracing-otlp`** — OTel GenAI semantic conventions 로 매핑해 OTLP 반출
  (`SpanType.LLM` → `gen_ai.*`) → Jaeger / Tempo / Phoenix / Langfuse 재사용.

### 4.5 Fail-safe

- `Tracer` 의 모든 public 메서드는 내부 try/catch 로 예외를 삼키고 WARN 로그만 남긴다(Hook 격리와 동일
  원칙).
- 레닥터가 throw 하면 `close()` 의 try/catch 가 삼켜 **그 span 만 유실**되고 실행은 안전하다 —
  마스킹 실패 시 원문 저장보다 유실이 보안상 안전한 실패 모드다.

---

## 5. 계측 지점

| Span | 소스 | 시작 → 종료 | 비고 |
|---|---|---|---|
| **TURN** (root) | `execute()` 진입/`finally` | userMessage → finalAnswer + `ExecutionMetadata` | `traceId` 발급 지점 |
| **ITERATION** | `executeReActLoop` 의 step try/finally | ReAct step 경계 | iteration 번호 보유 |
| **LLM** | `TracingLlmClient` 데코레이터 | `sendMessage` 진입 → 응답 | **유일한 LLM 캡처 경로**. 프롬프트·토큰·모델·지연 |
| **TOOL** | executor 의 `executeSingleTool` 얇은 래퍼 | 도구 입력 → 출력 | Event 보다 payload 가 풍부 |
| **SUBAGENT** | `SubagentStart` / `SubagentStop` Hook | goal → summary·success | 내부 Span 은 상속한 SpanContext 로 중첩 |
| **COMPACTION** | `PreCompact` / `PostCompact` Hook | trigger → 결과 | `Feature.COMPACTION` LLM 호출이 자식으로 매달림 |

**턴 root 발급 시점** — `OrcaAgentExecutor.execute()` 는 **턴당 정확히 1회** 호출된다
(`DefaultLiveSession` 이 "Submitting turn" 과 함께 호출하고, `execute()` 가 `SessionId` 로 전사를
resume 하며, `OnStart`/`OnStop` 도 턴 단위로 bracket 된다). 따라서 턴 root span 은 `execute()` 진입에서
mint 하고 **기존 `try/finally` 에서 close** 한다 — OnStart 차단·에러를 포함한 모든 경로에서 정확히
닫히며 Hook 에 의존하지 않는다. metadata enrich 도 root mint 직후라 같은 위치에 응집된다.

### 5.1 트리 조립 예시 (한 턴)

```
TURN  "turn:ops-bot"                               [traceId=T1]
├─ ITERATION #1
│  ├─ LLM  anthropic/claude   (prompt=..., tokens=1.2k/340, 1.4s)
│  └─ TOOL Read  (file_path=/etc/app.conf)         OK   38ms
├─ ITERATION #2
│  ├─ LLM  anthropic/claude   (tokens=1.5k/210, 1.1s)
│  ├─ TOOL Grep  (pattern=ERROR)   ┐ 병렬(CONCURRENT_SAFE)
│  └─ TOOL Read  (file_path=...)   ┘  ← SpanContext 로 부모 정확히 연결
└─ ITERATION #3
   ├─ LLM  anthropic/claude   (tokens=1.8k/512, 1.9s)
   └─ SUBAGENT "log-analyzer"  (goal="200줄 로그 요약")
      ├─ ITERATION #1 └─ LLM ...
      └─ TOOL ...
```

**중첩 배선** — `ExecutionScope.activeSpan`(volatile)을 통해 LLM 은 `invokeGatewayOnce` 에서,
TOOL 은 `executeToolUses` 의 per-iteration `ToolContext` 재구성으로 iteration 아래에 매달린다.
Span 시작은 `safeStartChild` 로 감싸 fail-safe 를 유지한다.

### 5.2 상관키 규약

모든 Span 은 `attributes` 에 `agentRuntimeId`, `iteration`, `invokerType`(MAIN_AGENT/SUBAGENT),
`principal` 을 태깅한다. **`sessionId` 는 `LlmCallMetadata.traceId` 에서 오고**, **`traceId` 는 턴
시작 시 root span id 로 발급**되어 예약 태그 `aimon.trace_id` 로 하위 호출에 전파된다. 한 세션의 N개
턴은 같은 `sessionId` 아래 N개 trace 로 묶인다.

---

## 6. Payload 캡처와 레닥션

초기 구현에서 span 의 입출력 캡처는 **비대칭**이었다.

| Span | 입력 | 출력 |
|---|---|---|
| `TOOL` | **원문 전체** (`toolUse.getInput()`) | `{isError, contentChars}` — 길이·에러 플래그만 |
| `LLM` | `{messages, tools, model}` 카운트 | `{textChars, toolUses, totalTokens}` 카운트 |

즉 **도구가 무엇을 돌려줬는지**(파일 내용, 명령 출력, 검색 결과)가 길이로만 남아 "에이전트가 왜 이
판단을 했는가" 를 재구성할 수 없었다. 디버깅·리플레이·평가 모두에서 결과 본문이 핵심 신호다.

### 6.1 캡처 정책은 값 객체 — `TracePayloadPolicy`

```java
public final class TracePayloadPolicy {
    public enum Mode { SUMMARY, FULL }          // 기본 SUMMARY
    public static final int DEFAULT_MAX_CHARS = 8192;

    public static TracePayloadPolicy summaryOnly();
    public static TracePayloadPolicy full();
    public static TracePayloadPolicy full(int maxChars);

    public boolean capturesContent();           // Mode.FULL?
    public int getMaxChars();
    public String truncate(String text);        // 초과 시 절단 마커 부착, null-safe
}
```

캡처 여부와 상한은 **캡처 지점**(요약을 만드는 곳)의 관심사다. 본문에 실제로 접근하는 곳은
`TracingLlmClient.outputsSummary`(LLM)와 `OrcaAgentExecutor.recordToolOutcome`(TOOL) **둘뿐**이므로
정책을 이 두 곳에 주입한다. `ToolConcurrencyConfig` 처럼 작은 불변 값 객체로 옵션을 표현하는 기존
패턴과 동형이며, enum 한 단계 + 상한이라 향후 확장(예: `INPUTS_ALSO`)도 열려 있다.

**요약은 유지한다** — FULL 에서도 `contentChars`/`textChars` 요약 필드는 남긴다(절단 **전** 원본 길이를
알 수 있어 디버깅에 유용). FULL 은 거기에 `content`/`text`(절단본)를 **추가**한다.

### 6.2 마스킹은 close() 직전 SPI — `SpanRedactor`

```java
public interface SpanRedactor {
    Object redact(Object payload);            // inputs/outputs 를 받아 마스킹된 사본 반환, 비파괴
    static SpanRedactor noop();               // 기본값
    static SpanRedactor defaultRedactor();    // KeyPatternSpanRedactor
}
```

`DefaultTracer.close()` 직전에 `inputs`/`outputs` **양쪽**에 적용한다. `KeyPatternSpanRedactor` 는 Map 을
**재귀 순회**하며 키가 민감 패턴(`*token*`, `*secret*`, `*password*`, `*credential*`,
`*apikey*`/`*api_key*`, `*authorization*`)에 매칭되면 값을 `"***REDACTED***"` 로 치환한다. 중첩
Map/List 도 처리한다.

도구 입력은 처음부터 **원문**이 들어가므로(`Bash(token=...)` 등) 캡처 모드와 무관하게 마스킹이
필요하다. 그래서 캡처 정책이 아니라 tracer 파이프라인에 두고, 한 곳(close)에서 일괄 적용해 모든 span
타입을 균일하게 보호한다.

**의도적 한계** — 키 기반이므로 자유 텍스트 본문(예: 파일 내용에 박힌 비밀번호)은 잡지 못한다. SPI 라
구현체 교체만으로 강화할 수 있다.

### 6.3 책임 분리

```
캡처 (무엇을 담을지)   →  TracePayloadPolicy  →  TracingLlmClient / OrcaAgentExecutor
마스킹 (무엇을 가릴지) →  SpanRedactor        →  DefaultTracer.close()
```

두 관심사를 분리해 **캡처를 끈 채 마스킹만 켤 수 있고**(원문 도구 입력 보호) 그 반대도 가능하다.

---

## 7. 설계 결정

| # | 결정 | 근거 / 기각 대안 |
|---|---|---|
| D1 | **명시적 SpanContext 전파** (`LlmCallMetadata` 태그) | ThreadLocal 부모 스택은 `ParallelToolDispatcher` 워커와 async sink 에서 부모가 오염된다. OpenTelemetry `Context`(Scope)는 동일 thread-local 문제 + 무거운 의존성을 코어에 끌어들이므로 기각 — OTel 은 반출 단계에서만 쓴다 |
| D2 | LLM Span 은 **`LlmClient` 데코레이터**로 캡처 | Hook·Interceptor 어느 것도 LLM 호출을 가로채지 못한다. 데코레이터는 ReAct 루프를 한 줄도 건드리지 않고 프롬프트·응답·토큰·모델·지연을 통째로 캡처한다 |
| D3 | `component` 의미를 **오버로드하지 않는다** — span id 는 예약 태그로 분리 | `component` 는 `"orca-agent"`·서브에이전트 이름 같은 **저카디널리티** 분류값으로 토큰 집계에 쓰인다. 고유 spanId 를 넣으면 집계가 깨지고, 같은 컴포넌트의 두 invocation 도 구분되지 않는다 |
| D4 | metadata enrich 를 **첫 단계에 포함** ("완전 비침투" 폐기) | enrich 없이는 데코레이터가 아는 게 sessionId 뿐이라 **어느 턴·어느 iteration 의 호출인지 알 수 없다** → iteration Span 과 LLM Span 을 timestamp 로 추측 매칭해야 하는 취약한 구조가 된다. enrich 는 기존 `effectiveMetadata` 빌드 지점에 태그 2개를 얹는 한 줄 수준 변경이다 |
| D5 | 트레이싱 실패는 **실행에 영향 없음** | 도구 `never throw`·Hook 격리와 동일한 프레임워크 철학. 관찰성이 가용성을 위협해서는 안 된다 |
| D6 | **자체 뷰어를 만들지 않는다** — OTel 호환 + 외부 반출 | 성숙한 LLM 트레이싱 UI 를 무료로 활용하고 코어를 경량으로 유지한다. 자체 웹 뷰어는 UI 유지보수 비용 대비 본질 가치가 낮아 기각. in-memory store 는 CLI 디버깅 보조로만 |
| D7 | Trace 입도 = **세션(session) + 턴(trace) 2층** | LangSmith thread/session 매핑과 일치하고, 턴별 독립 트리이면서 세션 전체 조회도 가능하며, 기존 `traceId` 의미가 무변경이다. `trace = 세션`(턴이 형제 root)은 단일 root 가 없어 턴 경계가 흐려지고, `traceId` 를 턴별로 재정의하는 것은 기존 사용량 집계를 회귀시켜 둘 다 기각 |
| D8 | 캡처(`TracePayloadPolicy`)와 마스킹(`SpanRedactor`)을 **분리** | 캡처는 요약을 만드는 두 지점의 관심사이고 마스킹은 모든 span 에 균일해야 한다. 분리하면 캡처를 끈 채 마스킹만 켤 수 있다 |
| D9 | `startRoot` / `startChild` **분리** (nullable parent 금지) | nullable 하나로 겸하면 "root 의도" 와 "부모 유실" 을 호출부에서 구분할 수 없다 |
| D10 | 패키지는 **top-level `at.aimon.core.tracing`** | 트레이싱은 `agent` 뿐 아니라 `llm`(데코레이터)·`subagent`·`knowledge`(retriever)를 가로지른다. `agent.tracing` 하위로 넣으면 의존 방향이 뒤틀린다. `observability` umbrella 는 메트릭 체계가 없는 현재로선 `tracing` 이 더 정확하며, 메트릭이 생기면 `observability.tracing` 승격은 순수 이동 리팩터로 가능하다 |

`TracingLlmClient` 를 `tracing.impl` 에 두는 것도 D10 의 따름정리다 — `LlmClient`(llm 도메인 인터페이스)를
구현하지만 본질이 트레이싱 데코레이터이고 `tracing` SPI 에 의존한다. `OpenAiLlmClient` 가 별도 모듈에
사는 것과 같은 논리(인터페이스는 `llm`, 구현은 구현자 네임스페이스)다.

---

## 8. 패키지 구조

```
at.aimon.core.tracing/
├── TraceSpan.java            # 불변 Span 값 객체 (빌더)
├── SpanType.java             # TURN, ITERATION, LLM, TOOL, SUBAGENT, COMPACTION
├── SpanStatus.java           # OK, ERROR, INTERRUPTED
├── SpanContext.java          # (sessionId, traceId, spanId, parentSpanId) 전파 토큰 + 예약 태그 상수
├── Tracer.java               # startRoot/startChild SPI (+ Tracer.Span)
├── TraceSpanStore.java       # 저장/조회 SPI
├── SpanExporter.java         # 외부 반출 SPI
├── SpanRedactor.java         # 민감정보 마스킹 SPI
├── TracePayloadPolicy.java   # 본문 캡처 정책 값 객체
└── impl/
    ├── NoopTracer.java             # 기본값 (제로 오버헤드)
    ├── DefaultTracer.java          # Clock·idGen·redactor 주입, fail-safe close
    ├── InMemoryTraceSpanStore.java # bounded, traceId/sessionId 인덱스
    ├── KeyPatternSpanRedactor.java
    └── TracingLlmClient.java       # LlmClient 데코레이터 → LLM Span

(외부 모듈, 미구현)
aimon-tracing-otlp/      # SpanExporter → OpenTelemetry OTLP
aimon-tracing-postgres/  # TraceSpanStore → PostgreSQL
```

### 8.1 배선

```java
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withTracer(new DefaultTracer(store, SpanExporter.noop(), SpanRedactor.defaultRedactor()))
        .withTracePayloadPolicy(TracePayloadPolicy.full(4096))
        .create(new TracingLlmClient(llmClient, tracer), transcriptManager);
```

CLI 는 `cli.tracing: true` 일 때 store + tracer + `TracingLlmClient` 를 배선하고 `/trace` 명령으로 최근
턴의 span 트리를 렌더한다. 본문 캡처는 `tracingCaptureContent`(기본 false) /
`tracingMaxPayloadChars`(기본 8192)로 제어한다.

### 8.2 하위 호환성

- 모든 신규 옵션의 기본값이 **현재 동작**이다: `Tracer.noop()` + `TracePayloadPolicy.summaryOnly()` +
  `SpanRedactor.noop()` + `SpanExporter.noop()`. 아무것도 주입하지 않으면 실행은 무회귀다.
- 이벤트 리스너 미등록 시 `EventEmitter.isEmpty()` 가드로 발행 비용이 0 이다.
- `LlmCallMetadata` / `ToolContextKeys` **시그니처 무변경** — 예약 태그·`traceId`·`withDefaults` 를
  있는 그대로 활용하므로 기존 호출부와 서브에이전트 전파에 영향이 없다.
- `DefaultTracer` / `TracingLlmClient` 는 기존 생성자를 보존하고 오버로드를 추가했다.

---

## 9. 남은 작업

| 항목 | 상태 | 비고 |
|---|---|---|
| 서브에이전트 span "회전" | 미구현 | 서브에이전트 LLM 은 현재 부모 iteration 아래 평면 부착(`withDefaults` 상속). 전용 SUBAGENT span 중첩은 후속 |
| `TracingHookAdapter` (compaction span) | 미구현 | tool span 은 executor 직접 계측으로 대체됨 |
| `TraceSampler` (트레이스 단위 샘플링) | 미구현 | 운영에서 100% 저장은 비현실적 — head 샘플링 vs tail 중 **에러 trace 100% 보존**을 권장 |
| 비차단 워커 (bounded queue + 손실 카운터) | 미구현 | `record()`/`export()` 를 fire-and-forget 으로. 큐 포화 시 가장 오래된 것을 drop 하고 카운터 증가(관측 가능한 손실). 직렬화·레닥션도 워커 스레드로 옮겨 호출 경로를 막지 않게 한다 |
| `aimon-tracing-otlp` / `aimon-tracing-postgres` | 미구현 | 외부 모듈. 보존 정책(`deleteOlderThan`) 스케줄링 포함 |

### 9.1 미해결 질문

- **스케줄 재발화의 세션 입도** — cron 재발화는 `boundRuntimeId`(agent-scoped)로 resolve 되지만 새
  세션일 수 있다. `sessionId` 가 매 발화마다 바뀌는지 정책 확인 필요.
- **스트리밍 LLM Span** — `sendMessageStreaming` 은 부분 텍스트를 sink 로 흘린다. Span 은 완료 응답
  기준 1개로 기록하되 첫 토큰까지의 지연(TTFT)을 별도 속성으로 남길지 검토.
- **토큰 합산** — turn/subagent Span 의 `tokenUsage` 는 자식 LLM Span 의 합이다. close 시점 집계 책임의
  위치를 정해야 한다.
- **MCP 도구 호출** — 원격 MCP 도구도 tool Span 으로 자연히 잡히나, 원격 측 트레이스와의 연결
  (W3C `traceparent`)은 범위 밖이다 (§1.4).

---

## 관련 문서

- [`../../features/observability/execution-tracing-guide.md`](../../features/observability/execution-tracing-guide.md) — 사용·배선·범위
- [`../agent-execution/interceptor.md`](../agent-execution/interceptor.md) — 4계층 관계 (§3.1)
- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — SpanContext 명시 전파의 근거
- [`../../overview/glossary.md`](../../overview/glossary.md) — 턴·iteration·실행 어휘
- [`../../project/solid-principles.md`](../../project/solid-principles.md)
