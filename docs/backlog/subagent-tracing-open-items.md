# 서브에이전트 트레이싱 — 등록 항목 12건 (열림 7 · 닫힘 5)

출처는 [`../design/observability/subagent-trace-nesting.md`](../design/observability/subagent-trace-nesting.md) 의 구현이다.
그 문서 §7(Open questions)과 §8(구현 뒤 — 설계와 달라진 점)이 적은 것 가운데 **이번 변경 밖으로 결과가 나가는 것**만
여기로 올라왔다. 트레이싱 전체의 남은 작업(샘플러, 비차단 워커, 외부 store/exporter)은
[`../design/observability/tracing.md`](../design/observability/tracing.md) §9 에 있고 여기 옮겨 적지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §7 · §8 은 설계·구현 시점의 기록이다.

ST-1 ~ ST-6 은 같은 날 한 변경으로 처리되었다(2026-10-17, 설계
[`../design/observability/subagent-tracing-backlog.md`](../design/observability/subagent-tracing-backlog.md)). ST-2 · ST-4 ·
ST-6 은 닫혔고, ST-1 · ST-3 · ST-5 는 **닫힌 부분과 남은 부분**으로 나뉘어 열림으로 남는다 — 외부에서 ST-n 으로 참조되므로
항목을 쪼개 번호를 새로 매기지 않았다. 각 항목의 "처리" 절이 무엇을 어떻게 닫았는지와 착수해 보니 적힌 것과 달랐던 점을
적는다. ST-7 ~ ST-11 은 그 설계의 열린 질문(§7) 가운데 **이 변경 밖으로 결과가 나가는 것**을 올린 것이다. ST-12 는 그
뒤 백그라운드 결과의 수신 측 기록(#293)에서 남은 갈래다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 "어디" 칸의 인용은 등록 시점(2026-10-17, `d22d2908`) 기준이고, "처리"
절의 인용은 처리 시점(2026-10-17, 이 변경의 작업 트리) 기준이다.

---

## ST-1 — TOOL span 없이 시작되는 sub-execution 은 TOOL 단계가 빠지거나 span 이 없다 · **열림 (셋 중 둘은 닫힘)**

**무엇을.** 도구 호출을 거치지 않고 포크를 띄우는 경로에 합성 span(예: `COMMAND`, 혹은 루틴 단계마다 root)을 둘지 정한다.

**왜.** SUBAGENT span 의 부모는 그 실행이 받은 metadata 가 가리키는 span 이고, 도구 호출에서 온 포크만 그것이 TOOL span
이다. 그 밖의 경로는 셋이다.

- **슬래시 `/skill` 포크** — 명령 도구 컨텍스트에 턴의 metadata(TURN span)를 그대로 싣는다. SUBAGENT 가 TURN 바로 아래
  붙어, 어느 명령이 그 포크를 띄웠는지 트리에서 읽을 수 없다.
- **예약 루틴의 단계** — `RoutineExecutor` 는 턴을 돌리지 않으므로 root span 이 없고, 그 단계가 부르는 `Task` ·
  `Workflow` · 포크 스킬의 포크는 **span 을 하나도 남기지 않는다**(orphan 금지 규칙대로). 무인 실행이 가장 트레이스가
  필요한 자리인데 비어 있다.
- **에이전트 단위 `WorkflowRunner` 를 앱이 직접 부를 때** — 기본 launch context 에는 호출한 실행이 없어 같은 이유로 span 이 없다.

**어디.** (2026-10-17) `OrcaAgentExecutor.java:2194` (슬래시 명령 도구 컨텍스트의 `LLM_CALL_METADATA_KEY`),
`RoutineExecutor#buildToolContext`, `OrcaAgentRuntimeFactory` 의 기본 launch context.

**언제 다시 볼까.** 루틴 실행을 트레이스로 디버깅해야 하는 요청이 처음 들어올 때, 또는 `TracingHookAdapter`(설계
`tracing.md` §9)처럼 hook 경계에서 span 을 만드는 작업이 시작될 때.

### 처리 (2026-10-17) — 슬래시 명령 · 예약 루틴은 닫힘, 직접 호출 `WorkflowRunner` 는 열림

- **슬래시 명령 — 닫힘.** 새 `SpanType.COMMAND`. `OrcaAgentExecutor.executeCommand` 가 슬래시 명령 턴마다 TURN 아래
  `command:/<name>` span 을 열고(`CommandTrace`, `OrcaAgentExecutor.java:2148`), 명령 도구 컨텍스트의
  `LLM_CALL_METADATA_KEY` 를 그 span 으로 회전한다(`:2197`). 포크 진입점은 무변경이고 SUBAGENT 가 COMMAND 아래 붙는다.
  포크 스킬만이 아니라 **모든** 슬래시 명령에 연다 — 명령 종류에 따라 span 유무가 갈리지 않게. `TOOL` 재사용은 기각했다
  (`ToolUse` · iteration · `invokerType` 없는 TOOL 은 "모델이 고른 도구" 집계를 틀리게 한다).
- **예약 루틴 — 닫힘.** 새 `SpanType.ROUTINE`. `RoutineExecutor` 가 발화마다 ROUTINE root 를 열고
  (`RoutineExecutor.java:238`), 단계 **시도**마다 그 아래 TOOL span(`invoker=ROUTINE`, `stepIndex`, `attempt`)을 연 뒤 그
  도구의 컨텍스트를 회전한다. 재시도는 도구를 다시 돌리고 다른 포크를 띄울 수 있어 시도 단위다. tracer 는
  `SchedulingEngineBuilder.tracer(…)`(`:344` 에서 루틴 실행기에 건다)로 들어가고, `aimon-bootstrap` 이 executor spec 의 tracer
  를 같은 인스턴스로 건다(`AimonStackBuilder.java:722`). 둘 다 application-scoped 이고 누구도 tracer 를 소유·close 하지 않는다.
  trace 의 그룹 키는 `routine:<ScheduledTaskId>` 다 — `tracing.md` §9.1 의 "스케줄 재발화의 세션 입도" 질문을 이것으로 닫았다.
  tracer 가 없으면(기본 `Tracer.noop()`) 루틴 실행은 아무것도 만들지 않고 단계 컨텍스트도 그대로다.
- **직접 호출 `WorkflowRunner` — 열림.** 이 저장소에는 그 경로의 호출자가 없다(`getWorkflowRunner()` 의 main 호출자는 도구
  경로 둘과 CLI 목록·중지뿐). 고칠 모양이 (a) 러너가 실행마다 자기 root(예: `WORKFLOW`)를 연다, (b) `run(...)` 이 호출자
  metadata 를 받는다 로 갈리는데, 어느 쪽인지는 실제 임베더의 호출 모양이 정한다. 확인할 대상 없이 공개 인터페이스를 바꾸지
  않았다. **언제 다시 볼까**: 에이전트 단위 `WorkflowRunner` 를 직접 부르는 임베더가 트레이스를 요구할 때.

**착수해 보니.** 항목은 루틴 쪽 결함을 "root span 이 없다" 로 적었지만 root 만으로는 모자랐다 — 자식 span 의 그룹 키는
예약 태그가 아니라 `LlmCallMetadata.traceId` 에서 읽히므로(`SpanContext.readFrom`), root 를 열고 빈 metadata 를 회전하면
root 만 `routine:<id>` 에 있고 TOOL · SUBAGENT · ITERATION · LLM 은 전부 빈 키(`""`)에 떨어진다. 그래서 루틴이 단계에 넘기는
metadata 는 그룹 키를 `traceId` 로 실은 뒤 회전한 것이다. 그 결과 **트레이싱이 켜진 루틴**에서 띄운 포크의 LLM 호출 metadata
는 `traceId = routine:<taskId>` 를 갖게 되었다(꺼져 있으면 오늘처럼 비어 있다) — ST-8.

---

## ST-2 — 공장 밖에서 만든 매니저 · 실행기는 트리가 납작하다 · **닫힘**

**무엇을.** `DefaultSubagentExecutionManager` 와 그 안의 `DefaultSubagentExecutor` 에 같은 tracer 를 거는 길을 공장 밖에서도
하나로 만든다(예: 매니저 생성자 · 옵션 객체가 tracer 를 받거나, 매니저가 자기가 **만든** 실행기에는 옮겨 주기).

**왜.** tracer 는 두 객체에 따로 걸어야 하고, 그 일을 하는 곳은 `OrcaAgentExecutorFactory` 하나뿐이다. 그 밖에서는:

- 임베더가 `OrcaAgentExecutor.Builder#subagentExecutionManager(...)` 로 매니저를 직접 넣으면 **SUBAGENT 도 ITERATION 도
  없이** 서브에이전트의 LLM span 이 부모 `Task` TOOL span 아래 바로 붙는다.
- `DefaultSubagentExecutionManager(LlmClient, …)` 편의 생성자는 실행기를 안에서 만들어 손이 닿지 않는다. 매니저에
  `withTracer` 를 걸어도 SUBAGENT span 만 생기고 그 아래는 LLM span 뿐이다.
- `createDefaultSubagentExecutionManager` 를 재정의한 공장 하위 클래스도 스스로 걸어야 한다.

관측 가능한 결과는 "트리는 맞는데 한 층이 없다" 이고, 오류도 경고도 없다.

**어디.** (2026-10-17) `OrcaAgentExecutorFactory.java:798` · `:811` (`withSubagentTracing`),
`DefaultSubagentExecutionManager` 의 `LlmClient` 편의 생성자 셋, `DefaultSubagentExecutionManager#withTracer` javadoc.

**언제 다시 볼까.** Spring starter 나 bootstrap 이 매니저를 공장 밖에서 만들기 시작할 때, 또는 임베더가 납작한 트리를
보고할 때. 매니저 생성자 정리(생성자가 11개다 — 설계 Q4)와 함께 보는 것이 싸다.

### 닫힘 (2026-10-17)

매니저가 **자기가 만든** 실행기에는 tracer 를 옮긴다. `LlmClient` 편의 생성자 넷이 만든 `DefaultSubagentExecutor` 를
`ownedExecutor` 로 붙잡고(`DefaultSubagentExecutionManager.java:190`), `withTracer` · `withTracePayloadPolicy` 가 그것에도 같은
값을 건다(`:548`). 그래서 임베더가 공장 밖에서 한 번에 만드는 길은
`new DefaultSubagentExecutionManager(llm, tools, hooks).withTracer(t).withTracePayloadPolicy(p)` 하나이고, SUBAGENT → ITERATION
→ {LLM, TOOL} 이 나온다. 호출자가 넘긴 실행기는 건드리지 않는다는 원칙은 그대로다.

**남긴 것 (의도).** 실행기를 넘겨받은 매니저 — `OrcaAgentExecutor.Builder#subagentExecutionManager(...)` 로 직접 넣은 매니저,
`createDefaultSubagentExecutionManager` 를 재정의해 실행기를 직접 만든 공장 하위 클래스 — 는 여전히 실행기에 따로 걸어야 하고,
안 걸면 조용히 납작하다. `withTracer` javadoc 첫 문단과 가이드가 그 차이를 적는다. "매니저에 tracer 가 있는데 실행기는 noop"
을 알리는 진단 로그는 넣지 않았다 — 실행기의 tracer 를 읽으려면 `DefaultSubagentExecutor` 에 공개 getter 가 하나 더 필요하다.
매니저 생성자 11개 정리는 이 변경에서 하지 않았다 — ST-7.

---

## ST-3 — 백그라운드 SUBAGENT span 의 시각 · **열림 (셋 중 둘은 닫힘)**

**무엇을.** 백그라운드 서브에이전트의 SUBAGENT span 이 (1) 큐 대기 시간을 포함해야 하는지, (2) 부모보다 늦게 시작해
늦게 끝나는 span 을 외부 뷰어가 어떻게 그리는지, (3) pool 이 거부한 작업에 span 을 남길지 정한다.

**왜.** span 은 worker 에서 열린다(설계 대안 G 기각). 그래서

- bounded pool 에서 기다린 시간은 어느 span 에도 없다 — 느린 백그라운드 작업이 "실행이 느린" 것인지 "줄을 선" 것인지
  트리로 구분할 수 없다.
- 백그라운드 `Task` 는 즉시 반환하므로 TOOL · ITERATION · TURN 이 먼저 닫히고, SUBAGENT 는 그 **뒤에** 시작한다. 부모-자식은
  id 로만 이어지므로 store 에서는 맞지만, 시간 축으로 자식을 부모 안에 그리는 뷰어에서는 튀어나오거나 잘릴 수 있다.
  OTLP 자체는 허용하지만 `aimon-tracing-otlp` 가 없어 확인하지 못했다.
- pool 이 거부하면 `runResolvedSubagent` 가 돌지 않아 SUBAGENT span 이 없다. 실패는 `Task` TOOL span 의 결과 문자열로만 보인다.

**어디.** (2026-10-17) `DefaultSubagentExecutionManager#executeInBackground`(worker 람다와 `RejectedExecutionException` 분기),
`runResolvedSubagent`(`:662`).

**언제 다시 볼까.** `aimon-tracing-otlp`(설계 `tracing.md` §9)가 들어갈 때 — 그때 실제 뷰어에서 한 번 그려 본다. 또는
백그라운드 pool 포화가 운영에서 관측될 때.

### 처리 (2026-10-17) — (1) · (3) 닫힘, (2) 열림

- **(1) 큐 대기 — 닫힘.** span 시작 시각의 의미는 바꾸지 않았다(worker 시작 = 실행 시작; `Tracer` SPI 에 시작 시각 인자가
  없다). 대신 백그라운드 SUBAGENT span 에 `queuedMs` 속성(pool 에 넘긴 순간 → worker 시작)을 남긴다
  (`DefaultSubagentExecutionManager.java:718`). 포그라운드는 남기지 않는다 — 없음은 0 이 아니라 대기 개념이 없다는 뜻이다.
- **(3) pool 거부 — 닫힘.** 거부 분기도 SUBAGENT span 을 남긴다(`:1124`) — 같은 부모 · 입력 · 속성에 `rejected=true`,
  `queuedMs` 없음, 상태 ERROR. Stop hook 보다 먼저 닫히고, SubagentStart/Stop 의 순서와 횟수는 그대로다.
- **수신 측 — ST-12.** 백그라운드 결과가 부모에게 어디서 읽혔는지는 SUBAGENT span 이 아니라 수신 ITERATION 의
  `subagent.received.taskIds` 가 갖는다(#293). 그것이 남지 않는 경로는 ST-12 다.
- **(2) 외부 뷰어 렌더링 — 열림.** 부모보다 늦게 시작하는 span 을 OTLP 뷰어가 어떻게 그리는지는 `aimon-tracing-otlp` 가 없어
  여전히 확인하지 못했다. 위 "언제 다시 볼까" 가 그대로 남은 부분의 트리거다. 뷰어가 받을 **데이터**는 테스트로 고정했다 —
  부모가 닫힌 뒤 시작한 SUBAGENT 도 그 부모의 span id · trace id 를 가리키고 시작 시각이 부모 종료보다 뒤다
  (`RoutineExecutorTracingTest#lateSubagentAfterTimeoutNestsUnderTheClosedToolSpan`). 남은 것은 외부 UI 의 그리기뿐이며,
  이 저장소에는 아직 확인할 변환(exporter) 코드가 없다.
- **(1) 측정점 보정 (PR #256 리뷰).** worker 쪽 끝점은 worker 람다의 첫 문장이다 — `RUNNING` 전이 쓰기(원격 task store 면
  네트워크 왕복)를 대기로 세지 않는다.

**착수해 보니.** 대기를 재는 시작점이 항목이 짐작한 것과 달랐다. `executeInBackground` 가 이미 잡는 시각(PENDING
스냅샷)은 **동기** SubagentStart hook 과 저장소 쓰기보다 앞이라, 그것을 쓰면 `queuedMs` 가 hook 시간(셸 명령 hook 이면 수
초)까지 센다 — "느린 실행인가 줄을 선 것인가" 를 가르려던 값이 다시 섞인다. 그래서 pool 에 넘기기 **직전**에 따로 잰다.

---

## ST-4 — SUBAGENT span 에 포크의 `ExecutionId` 가 없다 · **닫힘**

**무엇을.** 실행기가 발급하는 포크의 `ExecutionId`(`subagent:<name>:…`)를 SUBAGENT span 속성으로 남긴다.

**왜.** span 을 여는 매니저는 그 값을 모른다 — 실행기 안에서 만들어지고 결과에도 실리지 않는다. 지금은 `taskId` 속성으로만
상관할 수 있는데, 포크의 아티팩트 보관 경로 · 실행 환경 · 도구 상태(todo 목록 등)는 `ExecutionId` 로 키가 잡혀 있어
트레이스에서 그쪽으로 건너갈 수 없다.

**어디.** (2026-10-17) `DefaultSubagentExecutor#execute`(`ExecutionId` 발급), `SubagentExecutionResult`(그 값이 없다).

**언제 다시 볼까.** 트레이스에서 포크의 아티팩트나 실행 환경을 찾아가야 하는 요청이 들어올 때. 결과 객체에 값을 노출하는
변경이 먼저다.

### 닫힘 (2026-10-17)

`SubagentExecutionResult` 에 `getExecutionId()` · `withExecutionId(ExecutionId)` 를 더했고(정적 팩토리는 그대로), 실행기가
한 지점에서 찍는다 — `DefaultSubagentExecutor.execute` 의 `return runReActLoop(lc).withExecutionId(executionId)`
(`DefaultSubagentExecutor.java:471`). 루프의 모든 반환이 그 줄을 지나므로 성공 · 절단 · 중단 · 예산 · 쿼터 · 실패가 다 덮인다.
재개된 실행은 이어받은 id 다. 매니저는 결과에 값이 있으면 SUBAGENT span 에 `executionId` 속성으로 남긴다
(`DefaultSubagentExecutionManager#closeSubagentSpan`, `:831`). 값이 없는 경우 — 코드 behavior 경로, 해석 실패 · ceiling 거부 ·
pool 거부, 루프 전에 던진 실행 — 는 속성이 없다. 결과는 직렬화되지 않아 저장 포맷 영향이 없다. `equals` 는 이제 id 를 본다 —
같은 내용이라도 id 가 찍힌 결과와 안 찍힌 결과는 다르다.

---

## ST-5 — 서브에이전트 compaction 호출과 COMPACTION span · **열림 (요약 호출의 위치는 닫힘)**

**무엇을.** 포크 루프의 compaction 요약 호출이 iteration metadata 를 싣게 하고, COMPACTION span 을 그 iteration 아래에 둔다.

**왜.** 포크의 ITERATION span 은 compaction 게이트보다 먼저 열리지만(코드 주석에 의도를 적었다), 엔진에 metadata 를 넘기지
않으므로 요약 호출이 트리 어디에 붙는지, 붙기는 하는지 확인하지 않았다. COMPACTION span 자체가 메인 턴에도 아직 없다
(`tracing.md` §9 의 `TracingHookAdapter` 행).

**어디.** (2026-10-17) `DefaultSubagentExecutor#applyCompactionGate`.

**언제 다시 볼까.** `tracing.md` §9 의 COMPACTION span 작업이 시작될 때 — 메인 턴과 포크를 같은 변경에서 다룬다.

### 처리 (2026-10-17) — 요약 호출의 span 위치는 닫힘, COMPACTION span 은 열림

- **요약 호출 — 닫힘 (메인 턴 + 포크).** 두 실행기가 `ContextRequest.callMetadata` 에 **span 위치만** 싣는다 — 새
  `Spans.traceCarrier(…)`(`Spans.java:162`)가 그룹 키(`traceId`)와 예약 태그 둘만 담은 metadata 를 만든다. 포크는 그 iteration
  의 metadata 에서(`DefaultSubagentExecutor.java:745`) 만들어 요약 LLM span 이 **그 ITERATION 아래** 붙고, 메인 턴은 게이트가
  ITERATION 을 열기 전이라 TURN 에서(`OrcaAgentExecutor.java:3306`) 만들어 **TURN 아래** 붙는다. 그리고
  `TracingLlmClient` 가 LLM span 에 호출의 `feature` 를 속성으로 남겨(`TracingLlmClient.java:148`) 요약 호출이
  `feature=compaction` 으로 구별된다. 트레이싱이 꺼져 있으면 아무것도 싣지 않는다.
- **COMPACTION span — 열림.** 게이트 앞에서 열면 compaction 이 없었던 iteration 마다 빈 span 이 쌓이고(`Tracer.Span` 에는
  버리기가 없다), 실제로 compact 할 때만 열려면 결정이 내려지는 엔진 안에서 열어야 해 엔진 SPI 에 관찰성 의존이 들어가거나
  `PreCompact`/`PostCompact` hook 기반 `TracingHookAdapter` 가 필요하다. 메인 턴의 요약 LLM span 이 TURN 아래(포크는 ITERATION
  아래)인 비대칭도 이 작업에서 함께 정한다. `tracing.md` §5 표의 COMPACTION 행은 "미구현" 으로 정정했다. 위 "언제 다시
  볼까" 가 그대로 남은 부분의 트리거다.

**착수해 보니 — 항목이 적은 것보다 나빴다.** 항목은 요약 호출이 "트리 어디에 붙는지 확인하지 않았다" 고 적었지만, 실제로는
**메인 턴이든 포크든 트리에 없었다.** 두 실행기 모두 `callMetadata` 를 채우지 않아 예약 태그가 없고, `TracingLlmClient` 는
태그가 없으면 span 을 만들지 않는다. 그리고 처방 "iteration metadata 를 싣게 한다" 를 글자대로 따르면 사용량 집계가 깨진다 —
엔진은 호출자 필드가 이기도록 병합하므로 `feature` 가 `compaction` 에서 `react-loop` · 서브에이전트 feature 로 바뀐다. 그래서
span 위치만 싣는다. 그리고 실어도 닿지 않는 경로가 하나 있었다 — 기본 엔진의 in-place AUTO compaction 은
`CompactionGuardRequest` 를 거치는데 거기에 metadata 칸이 없어 버려졌다. 그 칸을 더했다(`CompactionGuardRequest#callMetadata`,
`DefaultCompactionGuard` 가 넘긴다). 또 포크에서는 태그만 넘기면 엔진이 그룹 키를 transcript label(실행 id 를 감싼 가짜 세션)로 채워 요약 LLM
span 이 부모 세션의 trace 에서 빠지므로 `traceId` 도 함께 싣는다 — 그 결과 트레이싱이 켜진 포크의 요약 호출 metadata 의
`traceId` 는 포크 label 에서 부모 세션 id(포크의 다른 LLM 호출이 이미 갖는 값)로 바뀐다.

**적힌 것보다 좁게 보이는 것.** 이 저장소가 조립하는 포크 실행기(`OrcaAgentExecutorFactory` 와 매니저의 편의 생성자)는
`ContextEngine.passthrough()` 이라 포크가 compact 하지 않는다. 포크 쪽 변경은 맞지만 실제 `ContextEngine` 을 건 실행기를 쓰는
임베더에게만 보인다 — "닫힘" 을 "운영 트레이스에 포크의 요약 호출이 나온다" 로 읽지 말 것. 이때 `/compact` 명령의 요약 호출은
COMMAND span 아래 붙지 않았다 — ST-10 에서 닫았다 (2026-10-18, #265).

---

## ST-6 — `LLM_CALL_METADATA_KEY` 의 부모가 TOOL span 으로 바뀐 것을 외부 저장소의 독자가 견디는가 · **닫힘**

**무엇을.** 별도 저장소의 도구(`aimon-sandbox`, `aimon-browser-playwright` 등) 가운데 `ToolContextKeys.LLM_CALL_METADATA_KEY`
를 읽는 것이 있는지 확인하고, 있으면 그 LLM span 이 TOOL span 아래로 옮긴 것을 의도대로 받아들이는지 본다.

**왜.** 이번 변경으로 트레이싱이 켜져 있을 때 모든 도구가 받는 metadata 의 `aimon.parent_span_id` 가 ITERATION 이 아니라
그 도구의 TOOL span 을 가리킨다(설계 Q3). 이 저장소 안의 독자는 sub-execution 진입점 넷뿐이라 의도된 변화이지만, 그 키는
공개 SPI 이고 밖의 독자는 이 저장소에서 셀 수 없다. 태그 외의 필드(`component` · `traceId` 등)는 그대로라 사용량 집계는
영향이 없다.

**어디.** (2026-10-17) `OrcaAgentExecutor#executeSingleTool`(`:2542`), `DefaultSubagentExecutor#executeSingleTool`(`:1179`),
`ToolCallTracing#contextUnder`.

**언제 다시 볼까.** 다음 릴리스 노트를 쓸 때 — 그 전에 두 저장소를 한 번 grep 한다.

### 닫힘 (2026-10-17) — 외부 독자 0건

2026-10-17, 각 저장소의 로컬 체크아웃(모두 upstream 최신으로 확인) — `aimon-sandbox`@`ce2f496`, `aimon-browser`@`d590703`,
`aimon-memory`(upstream `@{u}`), `aimon-ops`@`22576b7a` — 를 `LLM_CALL_METADATA_KEY|SpanContext|aimon.parent_span_id` 로
grep 했다. **도구 쪽 독자는 0건이다.** `aimon-ops` 는 `AgentTracingConfiguration` / `AgentLlmConfiguration` 에서 tracer 와
`TracingLlmClient` 를 배선만 할 뿐 키를 읽지 않는다. 이 저장소들은 수정하지 않았다. 코드 변경 없음.

같은 키의 값은 이 처리에서 두 곳이 더 바뀌었다(이번에도 이 저장소 안의 독자는 sub-execution 진입점뿐이다): 슬래시 명령
도구 컨텍스트의 키가 TURN 대신 COMMAND span 을 가리키고(ST-1), 예약 루틴 단계의 도구 컨텍스트에 트레이싱이 켜져 있으면 키가
**새로 생긴다**(ST-1). 그리고 커스텀 `ContextEngine` · `CompactionEngine` 구현은 이제 트레이싱이 켜져 있으면
`ContextRequest.getCallMetadata()` 에 값을 받는다(ST-5). 릴리스 노트 메모: "외부 독자 0건 — 2026-10-17 확인; 위 세 변화는
공개 SPI 에서 관찰된다".

---

## ST-7 — `DefaultSubagentExecutionManager` 생성자 11개 · **열림**

**무엇을.** 매니저 생성자를 빌더(또는 옵션 객체 하나)로 일원화하고 tracer · payload policy 도 그 안에서 받는다.

**왜.** 생성자가 11개(공개)이고, 위임 체인이 길어 "이 실행기는 매니저가 만들었다" 같은 사실 하나를 끝까지 넘기는 데 private
생성자 둘이 더 필요했다(ST-2). tracer 를 받는 생성자가 없어 사후 setter 를 잊으면 조용히 납작해진다. 사용자가 공개 API 변경을
허용했지만 ST-2 는 그것 없이 닫혔고, main 3곳 + 테스트 ~70곳(+ `aimon-llm-*` · `aimon-workflow-graaljs` 테스트)의 기계적 diff
가 트레이싱 변경 리뷰를 덮기 때문에 따로 한다. 외부 `aimon-sandbox` 테스트(`OrcaRuntimeSandboxE2ETest.java:105`)가 편의 생성자
`(llm, tools, hooks)` 를 쓰므로 그것을 없애면 다음 버전 업 때 깨진다.

**어디.** (2026-10-17) `DefaultSubagentExecutionManager` 의 공개 생성자 11개와 private 생성자 2개.

**언제 다시 볼까.** 매니저에 생성자 인자가 하나 더 필요해질 때, 또는 임베더가 공장 밖 배선에서 tracer 를 잊어 납작한 트리를
보고할 때. 설계 [`subagent-tracing-backlog.md`](../design/observability/subagent-tracing-backlog.md) OQ-2 · 대안 H.

---

## ST-8 — 루틴 포크의 사용량 metadata `traceId` 가 트레이싱 켜짐 여부에 따라 달라진다 · **열림**

**무엇을.** 트레이싱이 꺼진 루틴에서도 단계 컨텍스트에 `traceId = routine:<taskId>` 만 실은 metadata 를 넣을지 정한다.

**왜.** ST-1 의 루틴 trace 는 그룹 키를 `traceId` 로 실은 metadata 를 단계에 넘기고, `SubagentLlmDefaults.effectiveMetadata`
는 `traceId` 를 부모에서 상속한다. 그래서 트레이싱이 켜진 루틴에서 띄운 포크의 LLM 호출 metadata 는
`traceId = routine:<taskId>` 이고 꺼져 있으면 비어 있다 — 사용량 집계 · 외부 로그 상관이 이 필드를 읽으면 같은 루틴이
트레이싱 설정에 따라 다르게 보인다. 오프 경로를 바이트 단위로 바꾸지 않는다는 기준을 우선해 이번에는 넣지 않았다.

**어디.** (2026-10-17) `RoutineExecutor#buildToolContext`(`RoutineExecutor.java:523`), `SubagentLlmDefaults#effectiveMetadata`.

**언제 다시 볼까.** 사용량 집계가 루틴 단위로 묶이기를 요구받을 때. 설계 OQ-9.

---

## ST-9 — 메인 턴의 compaction 요약 호출 metadata 에 principal 이 없다 · **닫힘** *(2026-10-18, #265)*

**무엇을.** 메인 턴의 요약 호출 metadata 에 호출자 principal 을 실어 `/compact`(`CompactCommand`) 경로와 맞출지 정한다.

**왜.** 메인 턴의 `ContextRequest` 는 `ContextCaller.principal(…)` 을 넘기지만 요약 호출의 **call metadata** 에는 principal 이
없다(`CompactCommand` 는 싣는다). ST-5 는 span 위치만 싣고 attribution 은 엔진 기본값에 맡겼으므로 이 차이는 그대로다 — 자동
compaction 의 요약 비용은 principal 별 사용량 집계에서 빠진다.

**어디.** (2026-10-17) `OrcaAgentExecutor#contextRequest`(`OrcaAgentExecutor.java:3306`), `DefaultCompactionEngine` 의 metadata
병합, `CompactCommand#buildCallMetadata`.

**언제 다시 볼까.** principal 별 사용량 · 쿼터가 compaction 비용을 세야 할 때. 설계 OQ-6.

**닫음 (2026-10-18, #265).** `/compact` 쪽에 맞췄다 — 메인 턴의 요약 호출도 호출자 principal 을 싣는다.
`OrcaAgentExecutor#contextRequest`(`OrcaAgentExecutor.java:3310`)가 span 위치 carrier 에 principal **하나만** 더해
`ContextRequest.callMetadata` 로 넘기고, 엔진은 그대로 병합한다(호출자 필드가 이긴다) — `component` · `feature` 는 여전히
엔진의 `compaction-engine` · `compaction` 이다. 그래서 자동 compaction 의 요약 비용이 principal 별 사용량 집계
(`LlmUsageKey`)에 그 턴의 주체로 잡힌다.

- **트레이싱이 꺼져 있어도 싣는다.** ST-5 는 트레이싱이 꺼지면 요청에 metadata 를 싣지 않았다. 이제 principal 이 있으면
  principal 만 담은 metadata 가 간다 — principal 도 span 위치도 없을 때만 예전처럼 비어 있다. 커스텀 `ContextEngine` ·
  `CompactionEngine` 구현은 트레이싱이 꺼진 턴에서도 `ContextRequest.getCallMetadata()` 에 값을 받는다(ST-6 의 릴리스
  노트 메모에 더할 한 줄).
- **포크는 그대로다.** 포크의 `ContextRequest` 는 `ContextCaller` 에도 principal 이 없다(실행 id 만 싣는다,
  `DefaultSubagentExecutor#applyCompactionGate`). 이 항목은 메인 턴만 물었고, 이 저장소가 조립하는 포크는 compact 하지
  않는다(ST-5 "적힌 것보다 좁게 보이는 것").
- **쿼터는 이 항목 밖이다.** principal 이 실린 것은 usage attribution 이지 `QuotaGuard` 기록이 아니다 — compaction 요약
  호출은 여전히 쿼터 장부에 기록되지 않는다(UQ-1).

---

## ST-10 — `/compact` 명령의 요약 호출은 COMMAND span 아래 붙지 않는다 · **닫힘** *(2026-10-18, #265)*

**무엇을.** `CompactCommand.buildCallMetadata` 가 명령 도구 컨텍스트 키의 span 위치를 합치게 해, `/compact` 의 요약 LLM span 이
COMMAND 아래 붙게 한다.

**왜.** ST-1 로 `/compact` 도 COMMAND span 을 남기지만, 그 명령의 요약 호출 metadata 는 명령이 스스로 만들어 예약 태그가 없고
`TracingLlmClient` 는 span 을 만들지 않는다. COMMAND span 은 있는데 그 안이 비어 보인다.

**어디.** (2026-10-17) `CompactCommand#buildCallMetadata`.

**언제 다시 볼까.** COMPACTION span(ST-5 의 남은 부분) 작업과 함께. 설계 OQ-7.

**닫음 (2026-10-18, #265).** COMPACTION span 작업을 기다리지 않았다 — 메인 턴(ST-5)과 같은 처방이면 됐다.
`CompactCommand#buildCallMetadata`(`CompactCommand.java:197`)가 명령 도구 컨텍스트의 `LLM_CALL_METADATA_KEY` 에서
`Spans.traceCarrier(…)` 로 span 위치(예약 태그 둘 + 그룹 키)만 꺼내 명령 자신의 attribution(`compact-command` ·
`compaction` · principal)에 합친다. 그래서 `/compact` 의 요약 LLM span 이 그 COMMAND span 아래, 턴과 같은 trace 에 붙는다.
그룹 키는 carrier 쪽이 이긴다 — 메인 턴에서는 둘 다 세션 id 라 값은 같다. 트레이싱이 꺼져 있거나 컨텍스트에 키가 없으면
metadata 는 예전과 한 글자도 다르지 않다. COMPACTION span 자체는 ST-5 의 남은 부분으로 그대로 열려 있다.

---

## ST-11 — 루틴 trace 를 CLI 에서 볼 수 없다 · **열림**

**무엇을.** CLI 에 루틴 trace 보기(예: `/trace routine <taskId>`)를 더할지 정한다.

**왜.** CLI `/trace` 는 TURN span 만 고른다(`ReplSession` 의 `== TURN` 필터). 루틴 trace 는 ROUTINE root 라 코어 store
(`bySession("routine:<taskId>")`)로만 조회된다. 그리고 그룹 키가 작업 단위라 cron 이 잦은 작업의 그룹은 끝없이 커진다 —
보존 정책은 store 몫이다(설계 OQ-1).

**어디.** (2026-10-17) `modules/aimon-cli/src/main/java/at/aimon/cli/repl/ReplSession.java:412` (`/trace` 의 TURN 필터).

**언제 다시 볼까.** CLI 사용자가 루틴 실행을 디버깅해야 할 때, 또는 `aimon-tracing-postgres` 의 보존 정책을 정할 때. 설계 OQ-8.

---

## ST-12 — 백그라운드 완료 알림을 받은 자리가 trace 에 남지 않는 두 경로 · **열림**

**무엇을.** 백그라운드 서브에이전트의 완료 알림을 부모가 읽은 자리를, 지금 `subagent.received.taskIds` 가 덮지 못하는 두
경로에서도 trace 에 남길지 정한다.

**왜.** #293 은 턴 도중 drain 된 알림의 task id 를 그 알림을 처음 읽는 ITERATION span 에 남긴다. 남지 않는 경로는 둘이다.

- **drain 직후 턴이 멈출 때** — 꼬리 drain 뒤 중단 확인, 다음 iteration 머리의 예산 · 쿼터 · compaction BLOCK, iteration 상한
  중 하나로 턴이 끝나면 알림은 transcript 에 이미 들어갔지만 읽을 ITERATION 이 없다. 다음 턴의 첫 LLM 호출이 그것을 읽지만,
  그 턴의 iteration 1 에는 표시가 없고 TURN `userMessage` 는 사용자의 새 입력이다.
- **부모가 쉬는 동안 도착할 때** — 호출자의 턴 사이 drain(CLI `ReplSession`)이 알림 본문을 새 턴의 입력으로 넘긴다. task id 는
  TURN `userMessage` 의 본문(`Task ID: …`)에만 있고, 큐 입력 metadata 의 `taskId` 를 span 에 기록하는 곳은 없다.

**어디.** (2026-10-20) `OrcaAgentExecutor#injectQueuedMessages` · `#annotateReceivedSubagentTasks`, `ReplSession` 의 턴 사이 drain
(`modules/aimon-cli/src/main/java/at/aimon/cli/repl/ReplSession.java:733`).

**언제 다시 볼까.** 운영에서 백그라운드 결과를 trace 로 따라가다 이 두 경로에서 끊긴다는 보고가 들어올 때. 앞의 것은 남은 id 를
TURN outputs 에 싣는 방식, 뒤의 것은 `OrcaAgentExecutionRequest` 가 큐 입력 metadata 를 받아 TURN 에 싣는 방식이 후보다.
