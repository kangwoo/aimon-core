# Execution Tracing Guide

> LangSmith 스타일로 LLM Agent 실행을 **계층형 span 트리**로 기록·조회하여 디버깅한다.

이 문서는 트레이싱을 **켜고 쓰는 방법**(운영/사용자)과 **확장하는 방법**(개발자)을 다룬다. 설계 배경·대안·
의사결정은 [Agent 실행 트레이싱 설계 문서](../../design/observability/tracing.md)를 참조한다.

## 목차

1. [개요](#개요)
2. [빠른 시작 — CLI](#빠른-시작--cli)
3. [프로그래밍 방식 배선](#프로그래밍-방식-배선)
4. [Span 모델](#span-모델)
5. [트레이스 조회](#트레이스-조회)
6. [확장 — 저장소/익스포터 SPI](#확장--저장소익스포터-spi)
7. [본문 캡처와 마스킹 (TRACE-02)](#본문-캡처와-마스킹-trace-02)
8. [Fail-safe 보장](#fail-safe-보장)
9. [범위와 한계](#범위와-한계)
10. [관련 문서](#관련-문서)

---

## 개요

한 번의 turn(`AgentSession.submit()` / `OrcaAgentExecutor.execute()` 1회)이 **span 트리**로 기록된다.

```
TURN  turn:<agent>                       ← 한 turn = 한 trace (root span)
├─ ITERATION iteration#1                 ← ReAct step
│  ├─ LLM  llm:<model>   (prompt/응답/토큰/지연)
│  └─ TOOL <name>        (입력/출력/성공·실패)
├─ ITERATION iteration#2
│  ├─ LLM  ...
│  └─ TOOL Task          (subagent를 띄운 도구 호출)
│     └─ SUBAGENT subagent:<name>   (goal/결과/토큰 합계)
│        └─ ITERATION iteration#1 ─ LLM / TOOL ...   (subagent 자신의 ReAct step)
└─ ITERATION iteration#3
   └─ LLM  ...
```

| 개념 | 의미 |
|------|------|
| **Session** | 한 대화(`ConversationId`). 여러 turn(trace)을 묶는 상위 그룹 |
| **Trace** | 한 turn. turn root span id로 식별 |
| **Span** | 단일 작업 단위: `TURN` / `ITERATION` / `LLM` / `TOOL` / `SUBAGENT` / `COMPACTION` / `RETRIEVER` |

핵심 성질:

- **기본 off, 제로 오버헤드** — 트레이서를 주입하지 않으면 `Tracer.noop()`이 동작하며 실행에 영향이 없다.
- **Fail-safe** — 트레이싱은 어떤 경우에도 agent 실행을 깨뜨리지 않는다([§7](#fail-safe-보장)).
- **멀티 인스턴스 대응** — 저장은 `TraceSpanStore` 인터페이스 뒤로 분리되어 in-memory 기본 구현 +
  외부 백엔드 교체가 가능하다.

---

## 빠른 시작 — CLI

설정 파일에서 트레이싱을 켠다(기본 `false`):

```yaml
cli:
  tracing: true
```

대화를 한 번 진행한 뒤 REPL에서 `/trace`를 입력하면 가장 최근 turn의 span 트리가 출력된다:

```
> 로그에서 ERROR를 찾아줘
... (에이전트 응답) ...

> /trace
Trace 7f3a1c9e-...
• TURN turn:ops-bot  [OK, 4210ms]
  • ITERATION iteration#1  [OK, 2050ms]
    • LLM llm:claude-opus-4-8  [OK, 1400ms, model=claude-opus-4-8, tokens=1540]
    • TOOL Grep  [OK, 38ms]
  • ITERATION iteration#2  [OK, 1900ms]
    • LLM llm:claude-opus-4-8  [OK, 1850ms, tokens=2100]
```

각 span 줄은 `타입 이름 [상태, 지연, (model=…, tokens=…)]` 형식이다. 트레이싱이 꺼져 있으면 `/trace`는
안내 메시지를 출력한다.

---

## 프로그래밍 방식 배선

CLI가 아닌 곳(웹 어댑터, 테스트, 커스텀 호스트)에서는 **하나의 트레이서를 두 곳에 동일하게 주입**한다 —
LLM span은 `TracingLlmClient` 데코레이터가, turn/iteration/tool span은 executor가 만든다.

```java
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

// 1) 저장소 + 트레이서 (한 인스턴스를 공유)
TraceSpanStore store = new InMemoryTraceSpanStore();
Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

// 2) LLM 클라이언트를 데코레이터로 감싼다 (LLM span)
LlmClient tracedClient = new TracingLlmClient(realLlmClient, tracer);

// 3) executor에 같은 트레이서 주입 (turn/iteration/tool span)
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withTracer(tracer)
        .create(tracedClient, conversationManager);

// 4) 실행 후 조회
executor.execute(context, request);
List<TraceSpan> turnSpans = store.byTrace(turnRootSpanId);   // 한 turn
List<TraceSpan> allSpans  = store.bySession(conversationId); // 한 대화의 모든 turn
```

`OrcaAgentExecutor.builder().tracer(tracer)` 로도 동일하게 주입할 수 있다. 트레이서를 주입하지 않으면
executor는 `Tracer.noop()`을 유지한다(회귀 없음).

> **`TracingLlmClient`와 executor 트레이서는 같은 인스턴스여야 한다.** 그래야 LLM span과 turn/tool span이
> 같은 store에 모이고, executor가 metadata에 심은 span 태그를 데코레이터가 읽어 LLM span을 올바른 부모
> 아래 매단다.

공장 밖에서 subagent 매니저를 직접 만들거나 예약 루틴을 쓰는 경우에도 **같은 트레이서**를 건다. `LlmClient` 편의
생성자로 만든 매니저는 안에서 만든 실행기에도 트레이서를 옮겨 주므로 `withTracer` 한 번으로 `SUBAGENT → ITERATION →
{LLM, TOOL}` 전체가 나온다. 실행기를 직접 넘긴 매니저는 실행기(`DefaultSubagentExecutor#withTracer`)에도 따로 건다 —
매니저는 넘겨받은 객체를 바꾸지 않는다.

```java
// 공장 밖의 subagent 매니저 — 편의 생성자면 이 한 줄로 실행기까지
DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(tracedClient, tools, hooks)
        .withTracer(tracer)
        .withTracePayloadPolicy(policy);

// 예약 루틴 — 발화마다 ROUTINE root, 단계 시도마다 TOOL
SchedulingEngine engine = SchedulingEngineBuilder.create()
        .agentRuntimeRegistry(registry)
        .tracer(tracer)
        .tracePayloadPolicy(policy)
        .build();
```

`aimon-bootstrap`(그 위의 Spring starter · CLI 포함)은 executor spec 의 트레이서를 스케줄링 엔진에도 같은 인스턴스로
건다.

---

## Span 모델

`TraceSpan`(불변 값 객체)의 주요 필드:

| 필드 | 설명 |
|------|------|
| `sessionId` | 대화 단위 그룹 (= conversationId) |
| `traceId` | turn 단위 (= turn root span id) |
| `spanId` / `parentSpanId` | 이 span / 부모 span (root는 parent 없음) |
| `type` | `TURN` / `ITERATION` / `LLM` / `TOOL` / `SUBAGENT` / `COMMAND` / `ROUTINE` (`COMPACTION` / `RETRIEVER` 는 예약 — 지금 만드는 곳이 없다) |
| `name` | 예: `turn:<agent>`, `iteration#3`, `llm:<model>`, 도구명 |
| `startTime` / `endTime` / `latency()` | 시각·지연 |
| `status` | `OK` / `ERROR` / `INTERRUPTED` |
| `inputs` / `outputs` | 입력·출력 **요약**(메시지 수, 토큰, 도구 입력 등) |
| `tokenUsage` / `model` | LLM span 한정 |
| `attributes` | `iteration` 등 부가 정보. LLM span 은 호출의 `feature`(예: compaction 요약 호출의 `compaction`), SUBAGENT 는 포크의 `executionId` · 백그라운드 대기 `queuedMs` · pool 거부 `rejected` |

도구 호출을 거치지 않는 두 경로에도 부모가 있다. 슬래시 명령 턴은 TURN 아래 `COMMAND` span(`command:/<name>`)을 열고,
그 명령이 띄운 fork 스킬의 `SUBAGENT` 가 그 아래 붙는다. 예약 루틴은 세션이 없으므로 실행(발화)마다 `ROUTINE` root
span 을 열고 단계 **시도**마다 그 아래 `TOOL` span(`invoker=ROUTINE`, `stepIndex`, `attempt`)을 둔다 — 그 trace 의
`sessionId` 는 `routine:<taskId>` 다.

**Span 컨텍스트 전파**는 thread-local이 아니라 `LlmCallMetadata`의 예약 태그(`aimon.trace_id`,
`aimon.parent_span_id`)로 명시적으로 흐른다. 따라서 병렬 도구 디스패치·async 스트리밍에서도 부모-자식
연결이 정확하다.

---

## 트레이스 조회

`TraceSpanStore` 인터페이스:

```java
void record(TraceSpan span);                 // 비차단, fail-safe
Optional<TraceSpan> get(String spanId);
List<TraceSpan> byTrace(String traceId);     // 한 turn 전체 (트리 재구성)
List<TraceSpan> bySession(String sessionId); // 한 대화의 모든 turn
void deleteOlderThan(Instant cutoff);        // 보존 정책
```

트리 재구성은 `parentSpanId`로 한다: `byTrace(traceId)`로 한 turn의 span을 모두 받아, `parentSpanId == null`
인 root부터 자식을 따라 내려간다(CLI `/trace`의 `renderTraceTree`가 그 예다).

```java
List<TraceSpan> spans = store.byTrace(traceId);
TraceSpan root = spans.stream().filter(s -> s.getParentSpanId().isEmpty()).findFirst().orElseThrow();
// parentSpanId로 children index를 만들어 root부터 재귀 렌더
```

`InMemoryTraceSpanStore`는 bounded(기본 `DEFAULT_MAX_SPANS = 10_000`, FIFO eviction)이며 CLI 디버깅용이다.
운영에서는 영속 백엔드로 교체한다([§6](#확장--저장소익스포터-spi)).

---

## 확장 — 저장소/익스포터 SPI

### 저장소 교체 (`TraceSpanStore`)

영속 store는 `TraceSpanStore`를 구현해 별도 모듈(`aimon-tracing-postgres` 등)에 둔다. 멀티 인스턴스
환경에서 thread-safe·비차단이어야 한다.

```java
public final class PostgresTraceSpanStore implements TraceSpanStore {
    @Override public void record(TraceSpan span) { /* INSERT, 절대 throw 금지 */ }
    @Override public List<TraceSpan> byTrace(String traceId) { /* SELECT ... WHERE trace_id = ? */ }
    // ... bySession, get, deleteOlderThan
}
```

### 외부 반출 (`SpanExporter`)

자체 뷰어 대신 OpenTelemetry/Langfuse/Tempo/Phoenix 같은 성숙한 LLM 트레이싱 UI로 반출하려면
`SpanExporter`를 구현한다. `DefaultTracer`는 `close()` 시점에 `record()`와 `export()`를 모두 호출한다.

```java
SpanExporter otlp = span -> { /* OTel GenAI 규약으로 매핑 후 OTLP 전송, 비차단·throw 금지 */ };
Tracer tracer = new DefaultTracer(store, otlp);
```

`SpanType.LLM` span은 `model`·`tokenUsage`를 가지므로 `gen_ai.*` 속성으로 매핑하기 좋다.

### 결정론적 테스트

`DefaultTracer`는 `Clock`과 span-id 생성기를 주입받는다. 테스트는 고정 `Clock` + 카운터 id로 결정론적
검증을 한다:

```java
AtomicInteger counter = new AtomicInteger();
Tracer tracer = new DefaultTracer(store, SpanExporter.noop(),
        Clock.fixed(instant, ZoneOffset.UTC), () -> "span-" + counter.incrementAndGet());
```

(참조 테스트: `DefaultTracerTest`, `InMemoryTraceSpanStoreTest`, `TracingLlmClientTest`,
`OrcaAgentExecutorTracingTest`.)

---

## 본문 캡처와 마스킹 (TRACE-02)

기본 span은 **요약**(도구 결과 길이·LLM 응답 텍스트 길이)만 담는다. 디버깅·평가를 위해 **도구 실행 결과
본문**과 **LLM 응답 텍스트**를 span에 담으려면 `TracePayloadPolicy`를 `FULL`로 켠다. 본문은 항상 상한
(`maxChars`)으로 절단되고, 저장 직전 `SpanRedactor`가 민감 키를 마스킹한다.

```java
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.impl.DefaultTracer;

// 1) 본문 캡처 정책 (기본 summaryOnly = 요약만). FULL은 본문을 maxChars로 절단해 추가 캡처.
TracePayloadPolicy policy = TracePayloadPolicy.full(8192);

// 2) 저장 직전 마스킹: *token*/*secret*/*password*/*credential*/*apikey*/*authorization* 키 → ***REDACTED***
Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), SpanRedactor.defaultRedactor());

// 3) 두 캡처 지점에 동일 정책 주입
LlmClient tracedClient = new TracingLlmClient(realLlmClient, tracer, policy);  // LLM 응답 텍스트
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withTracer(tracer)
        .withTracePayloadPolicy(policy)                                        // 도구 결과 본문
        .create(tracedClient, conversationManager);
```

CLI에서는 설정으로 켠다(트레이싱이 켜져 있어야 한다):

```yaml
cli:
  tracing: true
  tracingCaptureContent: true   # 도구 결과·LLM 응답 본문 캡처 (기본 false)
  tracingMaxPayloadChars: 8192  # 본문 절단 상한 (기본 8192)
```

`tracing: true`면 CLI는 기본 레닥터(`SpanRedactor.defaultRedactor()`)를 **항상** 배선하므로, 본문 캡처를
켜더라도 민감 키는 마스킹된다. FULL 모드에서도 요약 필드(`contentChars`/`textChars`)는 유지되어 절단 전
원본 길이를 알 수 있다.

> **주의 — 캡처 비용·한계:** 도구 결과는 클 수 있어(파일 읽기·명령 출력) 메모리·저장·반출 비용이 커진다.
> 운영에서 켤 때는 `maxChars`를 보수적으로 잡는다. 기본 레닥터는 **키 기반**이라 자유 텍스트 본문에 박힌
> 비밀은 잡지 못한다 — 민감 환경에선 영속 저장을 신중히 검토하거나 커스텀 `SpanRedactor`로 강화한다.

설계 배경은 [Trace Payload Capture 설계](../../design/observability/tracing.md)를 참조한다.

---

## Fail-safe 보장

트레이싱은 **관찰성이 가용성을 위협하지 않는다**는 원칙(도구의 `never throw`와 동일)을 따른다.

- `Tracer.Span#close()`는 절대 throw하지 않는다 — `record()`/`export()` 예외를 삼키고 WARN 로그만 남긴다.
- executor는 트레이서 호출(`startRoot`/`startChild`)을 방어적으로 감싼다. **트레이서 구현이 throw해도**
  no-op span으로 폴백하고 turn을 계속 진행하며, 대화 영속(`saveSilently`)을 건너뛰지 않는다.
- 따라서 커스텀 `TraceSpanStore`/`SpanExporter`/`Tracer`가 버그로 예외를 던져도 agent 실행은 안전하다.

직접 구현체를 작성할 때도 `record`/`export`는 비차단으로, 내부 예외는 삼키도록 작성하는 것을 권장한다.

---

## 범위와 한계

| 항목 | 현재 |
|------|------|
| 추적 대상 | **agent turn(ReAct 루프 + subagent)만** 추적. 배경 subsystem(wiki 인덱싱, peer memory, dreamer)은 의도적으로 미추적 — 해당 호출은 turn span 컨텍스트가 없어 wrap해도 span이 생기지 않는다 |
| subagent | subagent 실행마다 `SUBAGENT` span 하나가 그것을 띄운 도구 호출(`Task` · `Workflow` · fork 스킬)의 `TOOL` span(슬래시 명령이면 `COMMAND`, 루틴 단계면 그 시도의 `TOOL`) 아래 생기고, subagent 자신의 `ITERATION` / `LLM` / `TOOL` 이 그 아래 중첩된다(subagent 안의 subagent도 재귀적으로). 백그라운드 subagent의 span은 부모가 닫힌 뒤 시작할 수 있다 — 부모-자식은 id로 이어진다. 실행기를 직접 넘겨 만든 subagent 매니저에는 실행기에도 tracer를 걸어야 한다([배선](#프로그래밍-방식-배선)) |
| 예약 루틴 | 트레이서가 걸려 있으면 발화마다 `ROUTINE` trace 를 남긴다. 트레이싱이 켜진 루틴에서 띄운 포크의 LLM 호출 metadata 는 `traceId = routine:<taskId>` 를 갖고, 꺼져 있으면 오늘처럼 비어 있다 — 사용량 metadata 의 이 필드가 트레이싱 켜짐 여부에 따라 달라진다. CLI `/trace` 는 TURN 만 보여 주므로 루틴 trace 는 store(`bySession("routine:<taskId>")`)로 조회한다 |
| compaction | `COMPACTION` span 은 아직 없다. 요약 LLM 호출은 `feature=compaction` 속성의 LLM span 으로 남는다 — 메인 턴은 TURN 아래(게이트가 ITERATION 보다 먼저 돈다), 포크는 그 ITERATION 아래. 이 저장소가 조립하는 포크 실행기는 passthrough 엔진이라 포크가 compact 하지 않는다 — 포크 쪽은 실제 `ContextEngine` 을 건 실행기에서만 보인다. `/compact` 명령의 요약 호출은 COMMAND span 아래 붙지 않는다 |
| 미추적 경로 | 앱이 에이전트 단위 `WorkflowRunner` 를 직접 부르는 실행은 부모 span 이 없어 아무것도 남기지 않는다(orphan 금지). 부모보다 늦게 시작하는 백그라운드 SUBAGENT 를 외부 뷰어(OTLP)가 어떻게 그리는지는 확인하지 못했다 |
| 입출력 캡처 | 기본은 요약(메시지 수/토큰/도구 입력)만 저장. `TracePayloadPolicy.full(maxChars)`로 **도구 결과·LLM 응답 본문**을 옵션 캡처(상한 절단). 저장 직전 `SpanRedactor`로 민감 키 마스킹 — [§본문 캡처](#본문-캡처와-마스킹-trace-02) |
| 샘플링/비차단 워커 | 미구현. 운영 대량 트래픽에서는 후속 `TraceSampler` + bounded-queue 워커 권장 |
| 외부 백엔드 | `aimon-tracing-otlp`/`aimon-tracing-postgres` 모듈은 후속. 현재는 in-memory 기본 + `SpanExporter` SPI 제공 |

> **보안 주의**: 현재 `InMemoryTraceSpanStore`는 도구 입력 원문을 메모리에 보관한다. credential/PII가
> 도구 입력에 들어가는 환경에서는 레닥션이 추가되기 전까지 운영 영속 저장을 신중히 검토한다.

---

## 관련 문서

- [Agent 실행 트레이싱 설계 문서](../../design/observability/tracing.md) — 아키텍처·의사결정·대안
- [Tool 개발 가이드](../tool/tool-development-guide.md) — `never throw` 등 공통 fail-safe 원칙
- [도구 병렬 실행 가이드](../tool/parallel-tool-execution-guide.md) — span 컨텍스트 명시 전파의 근거
- [SOLID 원칙](../../project/solid-principles.md)
