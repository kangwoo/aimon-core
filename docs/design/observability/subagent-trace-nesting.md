# 서브에이전트 trace 중첩 (SUBAGENT span 회전)

> Status: **IMPLEMENTED** (2026-10-17) — 승인된 설계를 옮겨 둔 문서다.
> **[§8](#8-구현-뒤--설계와-달라진-점-그리고-백로그로-간-것)은 구현 뒤에 덧붙인 절이고, 구현이 이 문서에서 벗어난
> 자리는 거기에 있다.** 이 머리말과 §8 사이의 본문은 승인된 그대로 두었다 — 고치지 않고 §8 에서 정정한다.
> 현재의 트레이싱 설계 전체는 [`tracing.md`](tracing.md) 가 갖는다. 열린 항목의 정본은
> [`../../backlog/subagent-tracing-open-items.md`](../../backlog/subagent-tracing-open-items.md) 다.

> 대상 브랜치: `herdr/subagent-trace-nesting` (base `d05f107b`)
> 범위: `aimon-core` (subagent · toolinvocation · tracing · agent.impl.orca), 문서. CLI/Spring/bootstrap 은
> 코드 변경 없음(배선이 이미 한 지점으로 모인다 — §3.6).

---

## 1. 문제 재진술

서브에이전트 LLM 호출은 이미 같은 `TracingLlmClient` 를 타고 부모 턴의 trace 에 기록되지만, 트리 모양이 틀렸다.
`TaskTool` 이 읽는 `ToolContextKeys.LLM_CALL_METADATA_KEY` 에는 **ITERATION span** 을 가리키는 예약 태그가 실려
있고(`OrcaAgentExecutor.resolveEffectiveToolContext`, `:2348`), TOOL span 은 `executeSingleTool` 래퍼(`:2537`)가
열기만 할 뿐 그 컨텍스트를 도구에 넘기지 않는다. `SubagentLlmDefaults.effectiveMetadata` 는 `withDefaults` 로 그
태그를 그대로 상속하므로 서브에이전트의 LLM span 이 부모 ITERATION 아래 평면으로 붙는다. `SpanType.SUBAGENT` 는
main 소스 어디에서도 생성되지 않고, `DefaultSubagentExecutor` 에는 tracer 가 없어 서브에이전트 내부의
ITERATION/TOOL span 도 없다. 목표는 `TURN → ITERATION → TOOL(Task) → SUBAGENT → ITERATION → {LLM, TOOL}` 가
재귀적으로(중첩 서브에이전트 포함) 성립하고, 백그라운드 서브에이전트도 올바른 부모를 가지며, 트레이싱 오프 시
동작·비용이 무회귀인 것이다.

---

## 2. 선택한 접근과 기각한 대안

### 2.1 선택: "세 지점 회전" — TOOL 컨텍스트 회전 · SUBAGENT span 은 manager · 내부 span 은 executor

span id 를 `LlmCallMetadata` 예약 태그로 명시 전파하는 기존 원칙(D1, thread-local 금지)을 그대로 쓰고, **부모를
바꿔야 하는 경계 세 곳**에서만 `span.enrich(metadata)` 로 `aimon.parent_span_id` 를 회전시킨다.

| # | 경계 | 위치 | 회전 결과 |
|---|---|---|---|
| R1 | 도구 호출 | `OrcaAgentExecutor.executeSingleTool` + `DefaultSubagentExecutor.executeSingleTool` | 도구가 받는 `ToolContext` 의 `LLM_CALL_METADATA_KEY` 가 **TOOL span** 을 가리킨다 |
| R2 | 서브에이전트 진입 | `DefaultSubagentExecutionManager.runResolvedSubagent` | SUBAGENT span 을 열고 `SubagentExecutionRequest.llmCallMetadata` 를 **SUBAGENT span** 으로 회전 |
| R3 | 서브에이전트 iteration | `DefaultSubagentExecutor.runReActLoop` | iteration 마다 ITERATION span 을 열고, 그 iteration 의 LLM 호출 metadata 와 도구 `ToolContext` 를 **ITERATION span** 으로 회전 |

R1 이 있으면 R2 의 부모가 자동으로 `Task`(또는 `Skill`/`Workflow`) TOOL span 이 되고, R3 안의 도구 호출이 다시 R1 을
타므로 중첩 서브에이전트가 별도 코드 없이 재귀적으로 맞는다.

**`withDefaults` 주의(§4.3)와의 관계** — 회전은 `SubagentLlmDefaults.effectiveMetadata` 를 **고치지 않는다**. R2 는
`effectiveMetadata` 보다 *앞에서* 부모 metadata 자체를 SUBAGENT span 으로 회전시킨다(`subagentSpan.enrich(parent)` =
`withTags`, 기존 값을 덮어씀 — `LlmCallMetadata.java:161-175`). 이어지는 `builder(...).build().withDefaults(rotated)`
의 `this` 쪽에는 태그가 없으므로 회전된 태그가 그대로 상속된다. 즉 문서의 "회전 태그는 `this` 쪽에 써야 한다"는
경고는 "**병합 뒤에 다시 덮어쓰거나, 병합 전에 부모를 회전시켜라**"와 같은 뜻이며, 본 설계는 후자를 택해
`SubagentLlmDefaults` 와 code-behavior 경로(`DefaultSubagentBehaviorSupport:47`)를 무변경으로 둔다.

### 2.2 SUBAGENT span 위치를 manager 로 둔 이유

`DefaultSubagentExecutionManager.runResolvedSubagent` 는 모든 서브에이전트 실행이 지나는 **유일한 지점**이다 —
foreground(`runExecute`) · background(`executeInBackground` 의 worker) · registry/inline 대상 · ReAct 경로
(`subagentExecutor.execute`)와 code behavior 경로(`subagentBehaviorRunner.run`) · Task/Skill fork/Workflow/GraalJS
workflow 가 모두 여기를 통과한다(`SubagentExecutionManager` 를 거치지 않고 `SubagentExecutor` 를 직접 부르는 main
코드는 없음 — grep 확인). 또한 subagent 해석 실패(`SubagentNotFoundException`)·caller ceiling 거부도 이 메서드의
try 안에서 실패 결과로 바뀌므로, 여기서 span 을 열면 "띄우려 했으나 실패한" 서브에이전트도 ERROR span 으로 남는다.

### 2.3 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| **A. SUBAGENT span 을 `DefaultSubagentExecutor.execute` 에서 연다** | code behavior 경로(`SubagentBehaviorRunner`)가 빠져 그 LLM 호출이 TOOL 아래 평면 부착된다. 해석 실패·ceiling 거부는 executor 에 도달하지 않아 span 자체가 없다. executor 는 manager 가 아는 `taskId`·background 여부도 모른다 |
| **B. `SubagentStart`/`SubagentStop` Hook 으로 span 을 만든다** (§5 표의 원래 계획) | Hook 컨텍스트에 metadata 가 없어 부모 span 을 알 수 없고, start/stop 을 짝지으려면 `taskId → Span` 맵 상태가 필요하다(멀티 인스턴스·누수 위험). 또 hook 은 **회전된 metadata 를 실행에 되돌려줄 수 없어** R2 를 구현할 수 없다. 백그라운드는 start 를 launching thread 에서, stop 을 worker 에서 쏘므로 상관 관리가 더 나빠진다 |
| **C. `SubagentLlmDefaults.effectiveMetadata` 에 SpanContext 파라미터를 추가해 `this` 쪽에 태그를 쓴다** | 공개 정적 API 시그니처 변경 + 두 호출부(ReAct·behavior) 수정이 필요한데, 병합 전에 부모를 회전하면(§2.1) 같은 결과를 무변경으로 얻는다 |
| **D. 새 `ToolContextKey<SpanContext>` 를 추가한다** | §4.3 결정("새 키를 만들지 않는다, 전파 스파인은 `LLM_CALL_METADATA_KEY`")과 충돌하고, Task/Workflow/Skill fork/GraalJS 네 진입점을 모두 고쳐야 한다. 기존 키의 값만 TOOL span 으로 바꾸면 네 진입점 무변경 |
| **E. TOOL span 을 `SingleToolInvoker.invoke` 안으로 옮긴다** (두 executor 공용) | 깔끔해 보이지만 tracer 는 executor 의 `volatile` 사후 주입 상태이고 invoker 는 생성자에서 만들어진다(`withApprovalGate` 가 재생성). `ToolInvocationSpec` 에 tracer 를 실으면 값 객체가 관찰성 의존을 갖게 되고, 기존 Orca TOOL span 의 위치를 옮기는 리팩터가 덤으로 따라온다. 공용 헬퍼(§3.3)로 중복만 제거하고 위치는 유지한다 |
| **F. ThreadLocal / OTel `Context` 로 활성 span 추적** | D1 에서 이미 기각(병렬 디스패처·async sink·백그라운드 worker 에서 부모 오염) |
| **G. 백그라운드 SUBAGENT span 을 launching thread(`executeInBackground`)에서 열고 worker 에서 닫는다** | 큐 대기 시간이 span 에 포함되는 장점은 있으나, pool 거부·`supplyAsync` 실패·lease 회수 등 worker 가 돌지 않는 모든 경로에 close 를 보장해야 한다. 우선은 worker 에서 열고 `background=true` 속성을 남긴다 (Open question Q2) |

---

## 3. 구체 변경 (파일·모듈별)

### 3.1 `at.aimon.core.tracing` — 공용 fail-safe 헬퍼 (신규, 소형)

세 클래스(Orca executor, subagent executor, subagent manager)가 같은 "부모 읽기 → 안전 시작 → 안전 enrich" 를 반복하므로
SPI 패키지에 상태 없는 정적 유틸을 둔다. 의존은 `llm` + `tracing` 뿐(TracingLlmClient 와 동일 방향).

```java
// at.aimon.core.tracing.Spans (final, private ctor)
/** parent 태그가 없으면 Optional.empty() — orphan span 금지. tracer 가 throw 하면 noop span. */
public static Optional<Tracer.Span> startChildOf(Tracer tracer, LlmCallMetadata parentCarrier,
        SpanType type, String name, Map<String, Object> inputs);

/** tracer.startChild 실패 시 Tracer.noop() span 으로 대체 (기존 Orca safeStartChild 와 동일 의미). */
public static Tracer.Span startChildSafely(Tracer tracer, SpanContext parent, SpanType type, String name,
        Map<String, Object> inputs);

/** span.enrich 가 throw 하면 base 를 그대로 반환. */
public static LlmCallMetadata enrichSafely(Tracer.Span span, LlmCallMetadata base);
```

`OrcaAgentExecutor.safeStartChild`(`:2577`) 는 `Spans.startChildSafely(tracer, …)` 위임으로 바뀐다(동작 동일).

### 3.2 `OrcaAgentExecutor` — R1 (TOOL span 컨텍스트 회전)

`executeSingleTool`(`:2534`) 에서 `toolSpan != null` 인 경우에만 도구에 넘기는 컨텍스트를 교체한다.

```java
final ToolContext toolScopedContext = ToolCallTracing.contextUnder(toolSpan, toolContext);
final ToolUseResult result = dispatchSingleTool(agentRuntime, toolScopedContext, toolUse, ...);
```

- 트레이싱 오프(`openToolSpan` 이 null)면 분기 자체가 없으므로 할당 0 — 기존 무회귀 보장 유지.
- `openToolSpan` / `recordToolOutcome`(`:2561`, `:2591`) 의 본문은 §3.3 헬퍼로 옮기고 Orca 는 위임만 한다.
- javadoc 의 "parented to the active turn span" 문구(실제로는 ITERATION)를 바로잡는다.

영향: `LLM_CALL_METADATA_KEY` 를 읽는 main 코드는 `TaskTool:537`, `WorkflowTool:516`,
`SubagentBackedSkillForkExecutor:128`, `GraalJsWorkflowTool:263` 네 곳뿐이며 모두 sub-execution 진입점이다 — 이들이
TOOL span 을 부모로 보게 되는 것이 의도된 변화다. `component`/`parentComponent`/`feature`/`traceId`(session) 는
`withTags` 가 건드리지 않으므로 사용량 집계·quota 귀속은 무변경.

### 3.3 `at.aimon.core.toolinvocation.ToolCallTracing` (신규)

두 executor 가 이미 공유하는 `toolinvocation` 패키지(`SingleToolInvoker`, `ToolInvocationSpec`)에 TOOL span 전용
헬퍼를 둔다. `ToolUseResult`·`ToolContext` 를 알아야 하므로 `tracing` 이 아니라 여기다.

```java
public final class ToolCallTracing {
    /** 컨텍스트 metadata 의 parent 아래 TOOL span. 태그 없으면 null (기존 openToolSpan 의미 그대로). */
    public static Tracer.Span open(Tracer tracer, ToolContext ctx, ToolUse toolUse, int iteration);
    /** toolSpan 을 가리키도록 LLM_CALL_METADATA_KEY 를 바꾼 사본. enrich 결과가 동일 인스턴스면 ctx 그대로. */
    public static ToolContext contextUnder(Tracer.Span toolSpan, ToolContext ctx);
    /** isError/contentChars (+FULL 일 때 content) — 기존 Orca recordToolOutcome 그대로. */
    public static void recordOutcome(Tracer.Span toolSpan, ToolUseResult result, TracePayloadPolicy policy);
}
```

TOOL span attributes 에 `invokerType`(`MAIN_AGENT`/`SUBAGENT`) 를 추가한다(§5.2 상관키 규약; 지금은 누락).

### 3.4 `DefaultSubagentExecutor` — R3 + 서브에이전트 TOOL span

**필드/주입** (기존 `withCostEstimator` 패턴과 동형, `volatile`, 기본값 = 현재 동작):

```java
private volatile Tracer tracer = Tracer.noop();
private volatile TracePayloadPolicy tracePayloadPolicy = TracePayloadPolicy.summaryOnly();
public DefaultSubagentExecutor withTracer(Tracer tracer);                       // requireNonNull
public DefaultSubagentExecutor withTracePayloadPolicy(TracePayloadPolicy policy); // requireNonNull
```

`execute()` 진입에서 두 값을 한 번 스냅샷해 `LoopContext` 에 싣는다(실행 도중 교체돼도 한 실행은 일관).

**iteration 루프** (`runReActLoop`, `:458` 의 while 본문):

1. `iterationCount++` 직후 `Spans.startChildOf(tracer, lc.effectiveMetadata, ITERATION, "iteration#" + n, null)`.
   부모 태그가 없으면(스킬 포크를 직접 부른 테스트, 트레이싱 오프) span 없음.
2. 있으면 `iterMetadata = Spans.enrichSafely(span, lc.effectiveMetadata)`,
   `iterToolContext = lc.toolContext` 에서 `LLM_CALL_METADATA_KEY` 만 `iterMetadata` 로 바꾼 사본. 없으면 둘 다 원본
   인스턴스 그대로(할당 0).
3. `gateway.sendMessage(..., iterMetadata, llmCancellation)` — LLM span 이 ITERATION 아래로.
4. `executeToolUses(lc, iterToolContext, toolUses, n)` / `refuseTruncatedToolUses` — tool context 를 파라미터로
   받도록 시그니처 변경(private). `LoopContext.toolContext` 는 불변 baseline 으로 유지.
5. iteration 본문을 `try { … } catch (RuntimeException e) { span.error(e); throw e; } finally { span.close(); }`
   로 감싼다. 루프 밖 catch 들(`LlmClientException` 등 `:556-581`)의 결과 셰이핑은 그대로.
6. 속성: `iteration`, `invokerType=SUBAGENT`, `subagent=<name>`.

**TOOL span** (`executeSingleTool`, `:1119`): 받은 `ToolContext` 로 `ToolCallTracing.open(...)` → 있으면
`contextUnder` 로 회전한 컨텍스트를 `ToolInvocationSpec.toolContext(...)` 에 넣고, `recordOutcome` 후 finally close.
병렬 디스패처(`parallelToolDispatcher.dispatch`)는 per-call 람다가 `iterToolContext` 를 캡처하므로 worker 스레드에서도
부모가 데이터로 전달된다(thread-local 없음).

**compaction 게이트**(`applyCompactionGate`, `:678`) 는 ITERATION span 을 연 *뒤*에 호출되도록 순서를 맞추되, 엔진에
metadata 를 넘기는지 여부는 바꾸지 않는다(Q8).

### 3.5 `DefaultSubagentExecutionManager` — R2 (SUBAGENT span)

**필드/주입**: executor 와 같은 `volatile` 두 필드 + `withTracer` / `withTracePayloadPolicy` 사후 setter. 생성자가
이미 11개라 생성자 오버로드는 늘리지 않는다(Q4).

**`runResolvedSubagent`(`:595`)** 를 다음처럼 감싼다.

```java
final Optional<Tracer.Span> span = Spans.startChildOf(tracer, launchContext.getParentLlmCallMetadata(),
        SpanType.SUBAGENT, "subagent:" + subagentName, subagentInputs(taskId, target, goal, launchContext));
final LlmCallMetadata forwarded = span.map(s -> Spans.enrichSafely(s, parentMetadata)).orElse(parentMetadata);
try {
    ... SubagentExecutionRequest.builder()....llmCallMetadata(forwarded) ...   // ← 유일한 실행 경로 변경
    result = behavior.isPresent() ? subagentBehaviorRunner.run(...) : subagentExecutor.execute(...);
} catch (...) { result = createFailureResult(...); }   // 기존 그대로
span.ifPresent(s -> { recordSubagentOutcome(s, result, policy); s.close(); });   // 예외 경로 포함(finally)
fireSubagentStop(...);   // span 종료 후 (Q9)
```

- **inputs**: `subagent`(name), `taskId`, `goal`, `goalChars`, `description`(있으면). goal 원문을 항상 싣는 근거:
  같은 goal 이 부모 TOOL span 의 `input.prompt` 에 이미 원문으로 기록되고, TURN 의 `userMessage` 도 항상 캡처된다.
  (정책 해석은 Q1.)
- **outputs**: `success`, `status`, `completionReason`, `iterations`, `finalAnswerChars`, 그리고
  `policy.capturesContent()` 일 때만 `finalAnswer`(truncate). `errorMessage` 는 실패 시.
- **tokenUsage**: `result.getMetadata()` 가 non-null 이고 `hasTokenUsage()` 일 때 `setTokenUsage(...)` — 서브에이전트
  루프가 이미 누적한 합계(`accumulatedTokens`)이므로 자식 LLM span 을 다시 합산하지 않는다(§9.1 "토큰 합산" 질문을
  SUBAGENT 에 한해 해소).
- **status**: `CompletionReason.INTERRUPTED` → `interrupted()`; 그 외 `!success` → `error(errorMessage)`.
  budget/quota stop 은 TURN 과 같은 규칙(outputs 로만 반영, ERROR 는 진짜 에러 메시지일 때) — 단 현재 서브에이전트
  budget stop 은 `failure` 로 셰이핑되므로 ERROR 로 보일 것이다(Q10).
- **attributes**: `invokerType=SUBAGENT`, `subagent`, `taskId`, `background`(`true`/`false`), `resumed`
  (`previousSnapshot` 존재 시), `codeBehavior`(behavior 경로 시).
- `background` 여부는 `runResolvedSubagent` 에 boolean 파라미터를 하나 추가해 `runExecute`(false) /
  `executeInBackground` worker(true) 가 넘긴다.

### 3.6 배선 — `OrcaAgentExecutorFactory.createDefaultSubagentExecutionManager` (`:758`)

```java
if (tracer != null) { subagentExecutor.withTracer(tracer); }
if (tracePayloadPolicy != null) { subagentExecutor.withTracePayloadPolicy(tracePayloadPolicy); }
final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(...);  // 두 분기 공통
if (tracer != null) { manager.withTracer(tracer); }  // policy 도 동일
return manager;
```

CLI(`AgentSetupFactory:623` → `ExecutorSpec.tracer`), Spring starter(`AimonAutoConfiguration:434`), bootstrap
(`AimonStackBuilder:409-410`) 은 모두 `executorFactory.withTracer/withTracePayloadPolicy` 로 수렴하고, factory 는
`create(llmClient)` 에서 이 메서드를 부르기 전에 필드가 이미 채워져 있다. 서브에이전트가 쓰는 `llmClient` 도
`create()` 에 넘어온 `TracingLlmClient` 그 자체다. 따라서 세 모듈은 **코드 변경 없음**; 수용 기준 8 은 factory
한 곳 + 각 모듈의 기존 배선 테스트 확장으로 충족한다. `createDefaultSubagentExecutionManager` 를 override 하는
하위 클래스나, `subagentExecutionManager(...)` 를 Builder 로 직접 주입하는 임베더는 스스로 `withTracer` 를 불러야
한다 — javadoc 에 명시.

### 3.7 문서

| 파일 | 변경 |
|---|---|
| `docs/design/observability/tracing.md` | 상단 Status 문구; §1.5/§4.3 전파 경로 다이어그램에 R1(TOOL 컨텍스트 회전)·R2(manager)·R3 추가, `withDefaults` 주의 문단을 "병합 전 부모 회전" 으로 보강; §5 표 SUBAGENT 소스 `SubagentStart/Stop Hook` → `DefaultSubagentExecutionManager.runResolvedSubagent`, TOOL 행에 "도구 컨텍스트를 TOOL span 으로 회전"; §5.1 트리 예시를 `TOOL Task → SUBAGENT → ITERATION → {LLM, TOOL}` 로 수정 + 백그라운드 주석; §7 에 결정 D11(회전 지점 3곳, Hook 기각); §8 패키지 트리에 `Spans`; §9 첫 행 "구현"(또는 행 삭제), §9.1 토큰 합산 항목 갱신 |
| `docs/design/observability/tracing.en.md` | **존재하지 않는다**(확인함). TASK.md 의 "있으면" 조건이 거짓이므로 갱신 대상 없음 |
| `docs/features/observability/execution-tracing-guide.md` **+ `.en.md`** | `:34` 트리 예시, `:280` "subagent 는 부모 iteration 아래 평면" 행을 새 동작으로. 번역본은 같은 커밋에서 갱신, `source_commit` 은 수정 직전 정본 커밋. `check-translation-structure.py`·`check-doc-links.py`·`check-translation-staleness.py` 실행 |

---

## 4. 데이터·인터페이스 변화 요약

| 대상 | 변화 | 호환성 |
|---|---|---|
| `TraceSpan` / `SpanContext` / `Tracer` / `TraceSpanStore` | **무변경** | — |
| `LlmCallMetadata`, `ToolContextKeys` | **무변경** (값만 다른 span 을 가리킴) | §8.2 원칙 유지 |
| `at.aimon.core.tracing.Spans` | 신규 public final 유틸 | 추가만 |
| `at.aimon.core.toolinvocation.ToolCallTracing` | 신규 public final 유틸 | 추가만 |
| `DefaultSubagentExecutor` | `withTracer`, `withTracePayloadPolicy` 추가 | 기본값 = 현재 동작 |
| `DefaultSubagentExecutionManager` | `withTracer`, `withTracePayloadPolicy` 추가; private `runResolvedSubagent` 파라미터 +1 | 기본값 = 현재 동작 |
| 도구가 받는 `LLM_CALL_METADATA_KEY` 의 `aimon.parent_span_id` | ITERATION span id → **TOOL span id** (트레이싱 on 일 때만) | 의도된 의미 변화. 태그 외 필드 동일 |
| 신규 span | SUBAGENT(서브에이전트 실행당 1), 서브에이전트 ITERATION/TOOL | 트레이싱 on 일 때만 |

목표 트리(한 턴, 중첩 1단 + 백그라운드 1개):

```
TURN turn:ops-bot
└─ ITERATION #1
   ├─ LLM
   ├─ TOOL Task                          (input.prompt = goal)
   │  └─ SUBAGENT subagent:log-analyzer  (taskId, goal, tokens=Σ, success)
   │     ├─ ITERATION #1
   │     │  ├─ LLM
   │     │  └─ TOOL Task
   │     │     └─ SUBAGENT subagent:grep-helper
   │     │        └─ ITERATION #1 └─ LLM
   │     └─ ITERATION #2 └─ LLM
   └─ TOOL Task (run_in_background)      ← 즉시 close
      └─ SUBAGENT subagent:indexer  background=true   ← 부모 close 이후 시작·종료, parentSpanId 는 유지
```

---

## 5. 실패 모드와 처리

| 상황 | 처리 |
|---|---|
| `Tracer.startChild` throw | `Spans.startChildSafely` 가 WARN 후 noop span — 실행 계속 (기존 Orca 와 동일) |
| `Span.enrich` throw | `Spans.enrichSafely` 가 base metadata 반환 → 그 하위는 한 단계 위 부모에 붙음(트리 일부 평탄화, 실행 무영향) |
| `Span.close`/레닥터 throw | `DefaultTracer` 계약상 삼킴 — 그 span 만 유실 (§4.5) |
| 부모 태그 없음 (트레이싱 오프, metadata 미주입 경로, 단위 테스트에서 manager 직접 호출) | SUBAGENT/ITERATION/TOOL 모두 생성 안 함. `TracingLlmClient` 도 이미 같은 규칙 → **orphan 0** |
| 트레이싱 오프 (`Tracer.noop()`) | noop span 의 `enrich` 는 같은 인스턴스를 반환 → `ToolContext` 재구성 분기 미진입, 할당 0. 단, noop 에서도 `startChildOf` 는 태그가 있으면 noop span 을 만든다 — 태그는 noop 에서 쓰이지 않으므로(noop `enrich` 무변경) 실질적으로 태그 없음 경로로 빠진다 |
| 서브에이전트 해석 실패 / caller ceiling 거부 / executor 예외 | span 은 이미 열려 있음 → 실패 결과로 `error(msg)` 후 close |
| 취소 (부모 turn 취소 캐스케이드, `TaskStop`) | `CompletionReason.INTERRUPTED` → `interrupted()`. iteration span 은 finally 로 닫힘 |
| 백그라운드: 부모 TOOL/ITERATION/TURN span 이 먼저 close | parent 는 **id 로만** 연결되므로 트리 관계 유지. span 시작 시각이 부모 종료 이후일 수 있음 — 뷰어가 이를 허용해야 함(OTLP 는 허용). `background=true` 로 식별 |
| 백그라운드: pool 거부 / `supplyAsync` 실패 | `runResolvedSubagent` 가 실행되지 않으므로 SUBAGENT span 없음. Task TOOL span 의 결과(에러 문자열)로 관찰 가능 |
| 백그라운드: in-memory store 의 FIFO 축출로 TURN 이 먼저 사라짐 | 늦게 온 SUBAGENT 와 자식은 그대로 기록되고 `byTrace` 로 조회됨(부분 트리). 영속 store 에서는 해당 없음 |
| 병렬 도구 (`ParallelToolDispatcher`) 안의 Task 여러 개 | 각 호출이 자기 TOOL span 으로 회전된 컨텍스트를 받음 → 형제 SUBAGENT 가 서로 섞이지 않음 |
| `Task(resume=<taskId>)` | 새 TOOL span 아래 새 SUBAGENT span(현재 턴 trace), `resumed=true`. 이전 실행 span 과는 `taskId` 속성으로만 연결 |
| manager 와 `TracingLlmClient` 에 다른 tracer 인스턴스 | 기존과 같은 위험(부모가 다른 store 에 있음). factory 가 같은 인스턴스를 넘기므로 표준 배선에서는 발생 안 함 |
| `/skill` 인라인·슬래시 명령 경로처럼 TOOL span 없이 sub-execution 이 시작되는 경우 | 부모는 그 시점 metadata 가 가리키는 span(TURN 또는 ITERATION). 깨지지 않으나 TOOL 단계가 빠짐 (Q5) |

---

## 6. 테스트 전략

모두 `DefaultTracer(InMemoryTraceSpanStore, SpanExporter.noop())` + `TracingLlmClient(fake)` 로 실제 span 을 기록하고
`parentSpanId` 체인을 검증한다. 부모 metadata 는 테스트에서 `tracer.startRoot(...)` → `startChild(TOOL)` →
`toolSpan.enrich(LlmCallMetadata.builder().traceId("s1").build())` 로 시드한다.

**신규 `DefaultSubagentExecutorTracingTest`** (`subagent/execution`)
1. tool_use → final 2-iteration 실행: ITERATION#1·#2 의 parent = 시드 span; LLM span parent = 각 ITERATION;
   서브에이전트 TOOL span parent = ITERATION#1; `invokerType=SUBAGENT` 속성.
2. 도구가 받은 `ToolContext` 의 `LLM_CALL_METADATA_KEY` 를 캡처하는 fake 도구 → `readFrom` 결과 = 그 TOOL span id.
3. 병렬 디스패처(`CONCURRENT_SAFE` 도구 2개): 두 TOOL span 모두 같은 ITERATION 부모.
4. 시드 없는 metadata → store 비어 있음(orphan 금지).
5. `Tracer.noop()` → 도구가 받은 `ToolContext` 인스턴스가 `lc.toolContext` 와 동일(`isSameAs`) — 할당 0 회귀 가드.
6. `startChild`/`enrich` 가 throw 하는 tracer → 결과 success, finalAnswer 동일.
7. LLM 예외로 실패하는 iteration → 해당 ITERATION span `ERROR`, 그래도 close 됨.

**신규 `DefaultSubagentExecutionManagerTracingTest`** (`subagent`)
1. foreground: SUBAGENT 1개, parent = 시드 TOOL span; 서브에이전트 ITERATION parent = SUBAGENT; inputs(goal/taskId),
   outputs(success/iterations), tokenUsage = 결과 metadata 합계.
2. `TracePayloadPolicy.full()` → outputs 에 `finalAnswer`; `summaryOnly()` → 없음, `finalAnswerChars` 만.
3. 존재하지 않는 subagent 이름 → SUBAGENT `ERROR`.
4. 취소 → `INTERRUPTED`.
5. background: 시드 TOOL span 을 **먼저 close** 한 뒤 future 완료 대기 → SUBAGENT parent 여전히 TOOL span id,
   `background=true`.
6. code behavior 등록 → behavior 가 부르는 LLM span parent = SUBAGENT (ITERATION 없음).
7. 시드 없음 → span 0.
8. 중첩: 서브에이전트 도구 레지스트리에 같은 manager 를 쓰는 `TaskTool` → `SUBAGENT → ITERATION → TOOL(Task) →
   SUBAGENT → ITERATION → LLM` 체인 (수용 기준 4).

**확장 `OrcaAgentExecutorTracingTest`** (factory 경유 end-to-end)
- `OrcaAgentExecutorFactory.withTracer(...)` 만 설정하고 Task 를 호출하는 스크립트 LLM →
  `TURN → ITERATION → TOOL(Task) → SUBAGENT → ITERATION → {LLM, TOOL}` 전체 체인 (수용 기준 1–3, 8 의 factory 부분).
- 기존 테스트 중 "도구가 받은 metadata 가 ITERATION 을 가리킨다"를 단언하는 것이 있으면 TOOL 로 갱신(구현 시
  `grep TAG_PARENT_SPAN_ID` 로 확인).

**회귀/배선**
- `SingleToolInvokerTest`·기존 `DefaultSubagentExecutor*Test`·`DefaultSubagentExecutionManager*Test` 무수정 통과.
- bootstrap: `AimonStackBuilder` 기존 tracing 배선 테스트가 있으면 서브에이전트 span 단언 1건 추가(없으면 factory
  테스트로 충분하다고 판단 — Q11).
- ArchUnit (`PackageDependencyArchitectureTest`, `InternalPackageBoundaryTest`): `subagent`→`tracing`(SPI),
  `toolinvocation`→`tracing`, `subagent`→`toolinvocation` 은 모두 `.impl` 을 import 하지 않으므로 통과 예상.
  `Spans` 가 `tracing.impl.NoopTracer` 를 직접 참조하지 않고 `Tracer.noop()` 만 쓰도록 주의.
- `./gradlew format checkAll` + 문서 스크립트 3종.

---

## 7. Open questions (TASK.md 로 해소 불가 — 가정하지 않고 표기)

| # | 질문 | 설계의 잠정 처리 |
|---|---|---|
| Q1 | 수용 기준 1 의 "내용 캡처는 정책이 허용할 때만" 이 **goal** 에도 적용되는가? | goal 은 항상 기록(TURN `userMessage`·TOOL `input` 과 일관, 부모 TOOL span 에 이미 원문 존재), **finalAnswer 만** FULL 에서 기록. goal 도 FULL 한정이어야 하면 `goalChars` 만 남기도록 한 줄 변경 |
| Q2 | 백그라운드 SUBAGENT span 이 큐 대기 시간을 포함해야 하는가? | 포함하지 않음(worker 에서 시작). 필요하면 대안 G |
| Q3 | 도구 컨텍스트 회전(R1)이 **모든 도구**에 적용되는 것이 맞는가? (외부 모듈 도구가 `LLM_CALL_METADATA_KEY` 로 직접 LLM 을 부르면 그 LLM span 도 TOOL 아래로 이동) | 적용함 — 더 정확한 트리이며 main 코드의 리더는 sub-execution 진입점 4곳뿐 |
| Q4 | `DefaultSubagentExecutionManager` 는 지금 모든 필드가 `final` 이다. 사후 `volatile` setter 를 들이는 것이 허용되는가, 생성자/`SubagentBackgroundExecutionOptions` 류 옵션 객체가 선호되는가? | executor 의 `withCostEstimator` 선례를 따라 setter |
| Q5 | TOOL span 없이 sub-execution 을 시작하는 경로(슬래시 `/skill` fork 등)에 합성 TOOL/COMMAND span 이 필요한가? | 범위 밖 — 기존 metadata 의 span 아래에 붙음 |
| Q6 | TASK.md 가 언급한 `tracing.en.md` 는 존재하지 않는다. 번역본 신규 작성이 기대되는가? | 작성하지 않음. 실제 번역본이 있는 `execution-tracing-guide.en.md` 만 갱신 |
| Q7 | SUBAGENT span 에 executor 가 발급하는 `ExecutionId`(`subagent:<name>:…`) 를 속성으로 남겨야 하는가? manager 는 그 값을 모른다 | 남기지 않음(`taskId` 로 상관). 필요하면 결과 객체에 노출하는 별도 변경 |
| Q8 | 서브에이전트 compaction LLM 호출이 metadata 를 싣는지 미확인 — COMPACTION span 은 §9 의 별도 항목 | 이번 범위에서 다루지 않음 |
| Q9 | SUBAGENT span 이 `SubagentStop` hook 실행 시간을 포함해야 하는가? | 포함하지 않음(hook 전에 close) |
| Q10 | 서브에이전트 budget/quota stop 은 `failure` 결과라 SUBAGENT 가 `ERROR` 로 보인다. TURN 처럼 "진짜 에러만 ERROR" 규칙을 적용할까? | `CompletionReason` 이 budget/quota 계열이면 `OK` + outputs 반영 으로 할지 구현 시 결정 필요 — 리뷰어 판단 요청 |
| Q11 | 수용 기준 8 이 CLI/Spring/bootstrap 각각에 **모듈별 테스트**를 요구하는가? | 세 모듈은 factory 로 수렴하므로 코드 변경 없음; factory end-to-end 테스트 + 기존 모듈 테스트 통과로 충족한다고 봄 |
| Q12 | 수용 기준 7 의 "(추정, 미검증) 백그라운드 서브에이전트는 부모 iteration span 이 닫힌 뒤 LLM span 을 남길 수 있다" — 코드상 확인됨: background Task 는 즉시 반환하므로 TOOL·ITERATION 이 먼저 닫힌다. 뷰어 측 처리(시간 역전)는 범위 밖 | §5 표대로 id 기반 관계만 보장 |

---

## 8. 구현 뒤 — 설계와 달라진 점, 그리고 백로그로 간 것

구현(2026-10-17)이 본문과 다르게 간 자리다. 본문은 승인된 그대로 두었고, 현재의 사실은 이 절이 갖는다. 설계 리뷰
1회차(PASS)가 남긴 비차단 지적 일곱 가운데 다섯을 구현에서 받아들였고, 그 결과가 §8.2 · §8.3 · §8.4 다. 이번 변경
밖으로 결과가 나가는 열린 질문은 백로그
[`../../backlog/subagent-tracing-open-items.md`](../../backlog/subagent-tracing-open-items.md) 의 `ST-1` ~ `ST-6` 으로
올렸다(§8.8). 아래 줄 번호는 전부 2026-10-17 기준이다.

### 8.1 `runReActLoop` 는 iteration 본문을 직접 감싸지 않는다 — `IterationTrace`

§3.4 의 1 ~ 5 단계를 루프 본문에 그대로 쓰면 `runReActLoop` 가 checkstyle `MethodLength`(150줄)를 넘는다(182줄).
그래서 iteration span 의 수명을 `DefaultSubagentExecutor` 안의 private 홀더 `IterationTrace`(`:1478`)로 옮겼다 —
`open(lc, n)` 이 span 을 열고 iteration 의 metadata 와 도구 컨텍스트를 만들고, 루프는
`try { … } catch (RuntimeException e) { trace.fail(e, signal); throw e; } finally { trace.close(signal); }` 만 쓴다.
같은 이유로 도구 호출이 없는 응답의 결말(정상 답 / `TRUNCATED`)을 `finishWithAnswer`(`:1218`)로 뺐다 — 동작은 그대로다.

이 때문에 §3.4 · §5 의 "트레이싱 오프면 할당 0" 은 **글자 그대로는** 틀렸다. 부모 태그가 없으면 metadata 와 도구
컨텍스트는 여전히 원본 인스턴스 그대로지만(재구성 없음), iteration 마다 필드 세 개짜리 홀더 객체 하나는 생긴다.

### 8.2 ITERATION span 의 상태 — 취소는 `INTERRUPTED`

본문 §3.4 의 5 단계는 예외를 모두 `span.error(e)` 로 표시했다. 그러면 `LlmCallCancelledException` 과
`CancelledExecutionException` — 바깥 catch 가 `INTERRUPTED` 결과로 바꾸는 두 예외 — 이 `ERROR` iteration 을
남겨, `INTERRUPTED` 인 SUBAGENT 아래에 `ERROR` 자식이 보인다(리뷰 지적). 구현은 둘 중 하나이거나 실행의 신호가 이미
울렸으면 `interrupted()`, 그 밖이면 `error(e)` 로 둔다. 또 예외 없이 끝났더라도 신호가 울린 iteration(꼬리의 취소 검사로
돌아간 경우)은 `interrupted()` 로 닫힌다.

### 8.3 SUBAGENT span — 입력, 상태, `finally`

- **`description` 은 입력에 없다.** §3.5 는 `description(있으면)` 을 입력에 넣었지만 `runResolvedSubagent` 는 그 값을
  받지 않고, 받게 하면 파라미터가 8개가 되어 checkstyle `ParameterNumber`(7)에 걸린다. 늘린 파라미터는 `background`
  하나뿐이다. `description` 은 부모 `Task` TOOL span 의 `input` 에 이미 원문으로 있다.
- **goal 은 정책 상한으로 자른다(Q1).** 두 모드 모두에서 기록하는 것은 본문대로지만 `policy.truncate(goal)` 을 거친다 —
  워크플로 fan-out 이 같은 goal 을 N 번 반복하기 때문이다(리뷰 지적). `goalChars` 는 자르기 전 길이다.
- **경계 정지는 `OK` 다(Q10, 리뷰 권고로 결정).** `MAX_ITERATIONS` · `TOKEN_BUDGET_EXCEEDED` · `COST_BUDGET_EXCEEDED` ·
  `WALL_CLOCK_EXCEEDED` · `QUOTA_EXCEEDED` 로 끝난 실행은 `OK` 이고 이유는 outputs 의 `completionReason` 에 남는다.
  `INTERRUPTED` 는 `interrupted()`, 그 밖의 실패(`ERROR` · `BLOCKED` · `ABORTED` …)는 `error(errorMessage)` 다.
  TURN 의 "진짜 에러만 ERROR" 규칙과 같은 방향이다. outputs 에는 본문에 없던 `status` 와(실패 시) `errorMessage` 가 있다.
- **close 는 진짜 `finally` 다.** 본문 의사코드는 try/catch 뒤에 close 를 두었는데, 그 catch 는 `Exception` 만 잡으므로
  `Error` 가 새면 span 이 열린 채 남는다(리뷰 지적). 구현은 `finally` 에서 닫고, 결과가 만들어지기 전에 `Error` 가 새면
  `error("Subagent execution did not complete")` 로 표시한다.
- `codeBehavior` 속성은 behavior 경로일 때만이 아니라 **항상** `true`/`false` 로 남는다(subagent 해석에 실패하면 없음).

### 8.4 `ToolCallTracing.open` 은 `InvokerType` 을 받는다

§3.3 의 시그니처에는 호출자 종류가 없는데, 같은 절이 TOOL span 에 `invokerType` 속성을 더하라고 한다. 헬퍼는 그것을
알 수 없으므로 `open(tracer, ctx, toolUse, iteration, invokerType)` 으로 받는다. `refuseTruncatedToolUses` 는 도구를
돌리지 않으므로 §3.4 의 4 단계와 달리 도구 컨텍스트를 받지 않는다.

### 8.5 배선 — 공장이 만든 매니저에만

§3.6 대로 CLI · Spring starter · bootstrap 은 코드 변경이 없다. `OrcaAgentExecutorFactory` 는 실행기에 tracer 를 건 뒤
매니저를 private `withSubagentTracing`(`:798`, `:811`)으로 감싸 두 분기에 같은 배선을 건다. `withTracer` 의 javadoc 에
"`createDefaultSubagentExecutionManager` 를 재정의한 하위 클래스는 스스로 배선한다" 를 적었다.

리뷰가 따로 적으라고 한 두 번째 퇴화가 실제로 있다. **매니저에만 tracer 가 있고 실행기에 없으면**(임베더가 매니저를
직접 만들거나, `DefaultSubagentExecutionManager(LlmClient, …)` 편의 생성자처럼 실행기를 내부에서 만들어 손이 닿지 않는
경우) SUBAGENT span 은 생기지만 그 아래 ITERATION/TOOL 은 없고, LLM span 이 SUBAGENT 바로 아래 붙는다. 매니저는 실행기에
tracer 를 옮겨 주지 않는다 — 호출자가 넘긴 실행기를 몰래 바꾸지 않으려는 것이다. 이 갈래는 `ST-2` 다.

### 8.6 테스트 — 본문 §6 과 다른 셋

- **"할당 0" 회귀 가드(§6 executor 5번)** 는 `ToolContext` 동일성이 아니라 **metadata 동일성**으로 단언한다.
  `SingleToolInvoker` 가 호출마다 도구 컨텍스트를 다시 만들어(`toolUseId` 등) 도구가 받는 `ToolContext` 는 어느 경로에서든
  새 인스턴스이고, 그래서 그 동일성은 밖에서 관찰할 수 없다. 대신 no-op tracer 에서 LLM 호출과 도구가 **같은
  `LlmCallMetadata` 인스턴스**를 받는지 본다.
- **스트리밍 eager 디스패치(리뷰 지적)** 는 `OrcaAgentExecutorTracingTest#eagerlyDispatchedToolSeesItsToolSpan` 이
  덮는다. 다만 그 도구가 실제로 토큰 스트림 도중에 시작되었는지는 단언하지 않는다 — overlap 이 켜진 구성에서 같은
  `executeSingleTool` 을 지난다는 것까지다.
- **bootstrap 배선 테스트는 더하지 않았다(Q11).** 세 모듈 모두 공장으로 모이고, 공장 end-to-end 테스트
  (`taskNestsTheSubagentTreeUnderItsToolSpan`)가 `withTracer` 하나만으로 `TURN → ITERATION → TOOL(Task) → SUBAGENT →
  ITERATION → {LLM, TOOL}` 를 확인한다.

### 8.7 열린 질문의 결말

| # | 결말 |
|---|---|
| Q1 | 본문 잠정안대로 goal 을 두 모드에서 기록하되 상한으로 자른다(§8.3) |
| Q2 | 본문대로 worker 에서 연다. 큐 대기 시간을 넣는 일은 `ST-3` |
| Q3 | 본문대로 모든 도구에 적용. 외부 저장소의 독자는 확인하지 못했다 — `ST-6` |
| Q4 | 본문대로 `volatile` 사후 setter |
| Q5 | 범위 밖. 슬래시 `/skill` 포크는 TURN 아래, 루틴 단계의 포크는 span 없음 — `ST-1` |
| Q6 | 본문대로 `tracing.en.md` 는 만들지 않았다 |
| Q7 | 남기지 않았다 — `ST-4` |
| Q8 | 다루지 않았다. ITERATION span 을 compaction 게이트보다 먼저 여는 의도는 코드 주석에 적었다 — `ST-5` |
| Q9 | 본문대로 hook 전에 닫는다 |
| Q10 | 경계 정지는 `OK`(§8.3) |
| Q11 | 공장 테스트로 충족(§8.6) |
| Q12 | id 기반 관계만 보장. 뷰어·반출기 쪽은 `ST-3` |

### 8.8 백로그로 간 것

이번 변경 밖으로 결과가 나가는 여섯 가지다. 열림/닫힘의 정본은 그 문서다.

- `ST-1` — TOOL span 없이 시작되는 sub-execution(슬래시 `/skill` 포크, 루틴 단계, 에이전트 단위 `WorkflowRunner`)
- `ST-2` — 공장 밖에서 만든 매니저 · 실행기는 트리가 납작하다
- `ST-3` — 백그라운드 SUBAGENT span 의 시각(큐 대기 제외, 부모 종료 뒤 시작, pool 거부 시 span 없음)
- `ST-4` — SUBAGENT span 에 포크의 `ExecutionId` 가 없다
- `ST-5` — 서브에이전트 compaction 호출과 COMPACTION span
- `ST-6` — `LLM_CALL_METADATA_KEY` 의 부모가 TOOL span 으로 바뀐 것을 외부 저장소의 독자가 견디는가

### 8.9 확인한 것과 확인하지 못한 것

- 확인: 새 테스트 클래스 둘(`DefaultSubagentExecutorTracingTest` 9건 · `DefaultSubagentExecutionManagerTracingTest`
  10건)과 `OrcaAgentExecutorTracingTest` 에 더한 2건(공장 end-to-end · eager 디스패치), 그리고 `./gradlew checkAll`.
- 확인하지 못함: 실제 LLM 프로바이더와 OTLP 같은 외부 뷰어에서 부모보다 늦게 시작한 백그라운드 SUBAGENT span 이 어떻게
  그려지는지(`ST-3`). macOS 한 플랫폼에서만 돌렸다. docker 계층은 돌리지 않았다(이 변경이 닿지 않는다).
