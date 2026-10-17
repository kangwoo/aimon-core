# 서브에이전트 트레이싱 — 등록 항목 6건 (열림 6 · 닫힘 0)

출처는 [`../design/observability/subagent-trace-nesting.md`](../design/observability/subagent-trace-nesting.md) 의 구현이다.
그 문서 §7(Open questions)과 §8(구현 뒤 — 설계와 달라진 점)이 적은 것 가운데 **이번 변경 밖으로 결과가 나가는 것**만
여기로 올라왔다. 트레이싱 전체의 남은 작업(샘플러, 비차단 워커, 외부 store/exporter)은
[`../design/observability/tracing.md`](../design/observability/tracing.md) §9 에 있고 여기 옮겨 적지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §7 · §8 은 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-10-17 기준이다.

---

## ST-1 — TOOL span 없이 시작되는 sub-execution 은 TOOL 단계가 빠지거나 span 이 없다 · **열림**

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

---

## ST-2 — 공장 밖에서 만든 매니저 · 실행기는 트리가 납작하다 · **열림**

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

---

## ST-3 — 백그라운드 SUBAGENT span 의 시각 · **열림**

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

---

## ST-4 — SUBAGENT span 에 포크의 `ExecutionId` 가 없다 · **열림**

**무엇을.** 실행기가 발급하는 포크의 `ExecutionId`(`subagent:<name>:…`)를 SUBAGENT span 속성으로 남긴다.

**왜.** span 을 여는 매니저는 그 값을 모른다 — 실행기 안에서 만들어지고 결과에도 실리지 않는다. 지금은 `taskId` 속성으로만
상관할 수 있는데, 포크의 아티팩트 보관 경로 · 실행 환경 · 도구 상태(todo 목록 등)는 `ExecutionId` 로 키가 잡혀 있어
트레이스에서 그쪽으로 건너갈 수 없다.

**어디.** (2026-10-17) `DefaultSubagentExecutor#execute`(`ExecutionId` 발급), `SubagentExecutionResult`(그 값이 없다).

**언제 다시 볼까.** 트레이스에서 포크의 아티팩트나 실행 환경을 찾아가야 하는 요청이 들어올 때. 결과 객체에 값을 노출하는
변경이 먼저다.

---

## ST-5 — 서브에이전트 compaction 호출과 COMPACTION span · **열림**

**무엇을.** 포크 루프의 compaction 요약 호출이 iteration metadata 를 싣게 하고, COMPACTION span 을 그 iteration 아래에 둔다.

**왜.** 포크의 ITERATION span 은 compaction 게이트보다 먼저 열리지만(코드 주석에 의도를 적었다), 엔진에 metadata 를 넘기지
않으므로 요약 호출이 트리 어디에 붙는지, 붙기는 하는지 확인하지 않았다. COMPACTION span 자체가 메인 턴에도 아직 없다
(`tracing.md` §9 의 `TracingHookAdapter` 행).

**어디.** (2026-10-17) `DefaultSubagentExecutor#applyCompactionGate`.

**언제 다시 볼까.** `tracing.md` §9 의 COMPACTION span 작업이 시작될 때 — 메인 턴과 포크를 같은 변경에서 다룬다.

---

## ST-6 — `LLM_CALL_METADATA_KEY` 의 부모가 TOOL span 으로 바뀐 것을 외부 저장소의 독자가 견디는가 · **열림**

**무엇을.** 별도 저장소의 도구(`aimon-sandbox`, `aimon-browser-playwright` 등) 가운데 `ToolContextKeys.LLM_CALL_METADATA_KEY`
를 읽는 것이 있는지 확인하고, 있으면 그 LLM span 이 TOOL span 아래로 옮긴 것을 의도대로 받아들이는지 본다.

**왜.** 이번 변경으로 트레이싱이 켜져 있을 때 모든 도구가 받는 metadata 의 `aimon.parent_span_id` 가 ITERATION 이 아니라
그 도구의 TOOL span 을 가리킨다(설계 Q3). 이 저장소 안의 독자는 sub-execution 진입점 넷뿐이라 의도된 변화이지만, 그 키는
공개 SPI 이고 밖의 독자는 이 저장소에서 셀 수 없다. 태그 외의 필드(`component` · `traceId` 등)는 그대로라 사용량 집계는
영향이 없다.

**어디.** (2026-10-17) `OrcaAgentExecutor#executeSingleTool`(`:2542`), `DefaultSubagentExecutor#executeSingleTool`(`:1179`),
`ToolCallTracing#contextUnder`.

**언제 다시 볼까.** 다음 릴리스 노트를 쓸 때 — 그 전에 두 저장소를 한 번 grep 한다.
