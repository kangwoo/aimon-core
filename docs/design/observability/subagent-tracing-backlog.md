# 설계 — 서브에이전트 트레이싱 백로그 ST-1 ~ ST-6

> Status: **IMPLEMENTED** (2026-10-17) — 승인된 설계를 옮겨 둔 문서다.
> **[§8](#8-구현-뒤--설계와-달라진-점)은 구현 뒤에 덧붙인 절이고, 구현이 이 문서에서 벗어난 자리는 거기에 있다.**
> 이 머리말과 §8 사이의 본문은 승인된 그대로 두었다 — 고치지 않고 §8 에서 정정한다. 현재의 트레이싱 설계 전체는
> [`tracing.md`](tracing.md) 가, 열린 항목의 정본은
> [`../../backlog/subagent-tracing-open-items.md`](../../backlog/subagent-tracing-open-items.md) 가 갖는다 — §7 의 열린
> 질문 가운데 이 변경 밖으로 결과가 나가는 것은 거기 ST-7 ~ ST-11 로 올라갔다(§8.10). 선행 설계는
> [`subagent-trace-nesting.md`](subagent-trace-nesting.md) 다.

> 대상 브랜치: `herdr/subagent-tracing-backlog` (base `d22d2908`, PR #255 머지 직후)
> 범위: `aimon-core` (tracing · subagent · subagent.execution · agent.impl.orca · scheduling · agent.compact 은 무변경),
> `aimon-bootstrap`(스케줄링 배선 한 줄), 문서. Spring starter · CLI 는 코드 변경 없음(둘 다 bootstrap 으로 수렴).
> 줄 번호는 전부 2026-10-17, `d22d2908` 기준.
>
> **사용자 추가 지시 반영**: 공개 API 변경 허용(하위호환 오버로드·deprecate 불필요). 이 설계는 그 허용을
> **필요한 곳에만** 쓴다 — 바꾼/더한 공개 API 는 §4.1 에 목록으로 남긴다. 매니저 생성자 11개 정리(설계 Q4)는
> 허용 범위 안이지만 이번 run 에서 하지 않기로 권고한다(근거 §2.3-H, 열린 질문 OQ-2).

---

## 1. 문제 재진술

PR #255 로 `TURN → ITERATION → TOOL(Task) → SUBAGENT → ITERATION → {LLM, TOOL}` 중첩이 섰지만, 백로그
`docs/backlog/subagent-tracing-open-items.md` 의 여섯 갈래가 남았다. (1) 도구 호출을 거치지 않는 sub-execution —
슬래시 `/skill` 포크는 SUBAGENT 가 TURN 바로 아래 붙어 어느 명령이 띄웠는지 안 보이고, 예약 루틴의 단계는 root
span 이 없어 그 포크가 **아무 span 도 남기지 않으며**, 앱이 에이전트 단위 `WorkflowRunner` 를 직접 부르는 경로도
같다. (2) tracer 를 매니저와 실행기에 따로 걸어야 해서 `OrcaAgentExecutorFactory` 밖에서 만든 매니저(특히
실행기를 안에서 만드는 `LlmClient` 편의 생성자)는 SUBAGENT 아래 ITERATION 이 빠진 납작한 트리를 조용히 만든다.
(3) 백그라운드 SUBAGENT span 은 worker 에서 열려 큐 대기 시간이 어디에도 없고, pool 이 거부한 작업은 span 이
없다. (4) SUBAGENT span 에 포크의 `ExecutionId` 가 없어 아티팩트·실행 환경·도구 상태로 건너갈 수 없다.
(5) compaction 요약 LLM 호출은 메인 턴이든 포크든 metadata 를 받지 못해 **span 이 아예 생기지 않는다**(아래 §2.1
ST-5 에서 확인한 사실). (6) 외부 저장소의 `LLM_CALL_METADATA_KEY` 독자 여부는 launcher 가 조사를 끝냈다(0건).
목표는 이 여섯을 각각 닫거나, 닫지 못하는 부분을 근거와 함께 열림으로 남기는 것이며, 트레이싱 오프에서 동작·비용
무회귀, tracer 예외 무해, orphan span 금지라는 기존 불변식을 지킨다.

---

## 2. 선택한 접근과 기각한 대안

원칙은 PR #255 그대로다 — **부모는 `LlmCallMetadata` 예약 태그로만 전파**(D1, thread-local 금지), span 시작·회전은
`tracing.Spans` 헬퍼로 fail-safe, 부모 태그가 없으면 span 을 만들지 않는다. 이번 설계가 하는 일은 "부모가 없던
자리에 부모를 만들어 주는 것"(ST-1, ST-5)과 "이미 있는 span 에 값을 더 싣는 것"(ST-3, ST-4), "배선 구멍 하나를
메우는 것"(ST-2)이다.

### 2.1 항목별 선택

#### ST-1.1 — 슬래시 명령: 새 `SpanType.COMMAND`

`OrcaAgentExecutor.executeCommand`(`:2144`)가 명령 도구 컨텍스트를 만들기 직전에 **COMMAND span** 을 턴의
metadata(`scope.llmCallMetadata` — TURN span 을 가리킴, `:1143`)의 자식으로 열고, 컨텍스트에 싣는
`LLM_CALL_METADATA_KEY`(`:2193`)를 그 span 으로 회전한다. 포크 진입점(`SkillTool` fork → 매니저)은 무변경이며
R2 가 자동으로 SUBAGENT 를 COMMAND 아래 둔다.

```
TURN "turn:ops-bot"
└─ COMMAND "command:/triage"       (command=triage, argsChars=…, success)
   └─ SUBAGENT "subagent:triage"   ← R2, 무변경
      └─ ITERATION #1 → LLM / TOOL …
```

- **새 상수를 택한 이유**: `TOOL` 재사용은 "도구 호출"이라는 의미를 깬다 — `ToolUse` id 도 iteration 도 없고,
  `invokerType` 도 없으며, 뷰어·집계가 TOOL 을 "모델이 고른 도구"로 세는 순간 틀린다. `SpanType` 은 enum 이라
  상수 추가는 소스 호환이다(이 저장소에 `SpanType` 을 `switch` 하는 main 코드는 없음 — grep 확인; CLI
  `ReplSession:412` 는 `== TURN` 필터뿐).
- **모든 슬래시 명령에 연다**(`/help`, `/compact` 포함). 포크 스킬에만 열면 "명령이 span 을 남기는지"가 명령
  종류에 따라 달라지고, 판별을 위해 명령 해석을 앞당겨야 한다. 명령 턴은 드물고 span 하나는 싸다.
- **이름·입력**: 이름은 `command:/<name>`. `name` 은 `CommandInputParser.parse(trimmed).name()`(공개,
  `agent/CommandInputParser.java:52`)으로 얻고, 파싱이 실패하면 `command` 로 둔다(실행 경로는 그대로 실패 결과를
  만든다). 입력은 `{command, argsChars}` 항상, `rawArguments` 는 `TracePayloadPolicy.capturesContent()` 일 때만
  `policy.truncate(...)` — SUBAGENT 의 goal 규칙(§8.3 Q1)과 같다. 입력 map 은 `startChildOfLazily` 로 부모 확인 뒤
  만든다.
- **출력·상태**: `{success, responseChars}`; `isFailure()` 면 `error(response)`. `finally` 에서 close(예외는
  `executeCommand` 의 catch 가 실패 결과로 바꾸므로 span 도 그 결과로 닫힌다; `Error` 는 `error("Command
  execution did not complete")` — SUBAGENT 의 `finally` 규칙과 같다).

#### ST-1.2 — 예약 루틴: 새 `SpanType.ROUTINE` root + 단계 시도마다 `TOOL`

`RoutineExecutor` 가 실행(fire) 1회마다 **ROUTINE root span** 을 열고, 단계의 **시도(attempt)마다 TOOL span** 을
그 아래 연 뒤, 도구가 받는 `ToolContext` 의 `LLM_CALL_METADATA_KEY` 를 그 TOOL span 으로 회전한다(R1 과 같은 일).
그러면 단계가 부르는 `Task` · `Workflow` · 포크 스킬이 아무 변경 없이 그 아래 SUBAGENT 를 남긴다.

```
ROUTINE "routine:<scheduledTaskId>"   [sessionId=routine:<scheduledTaskId>, traceId=root id]
├─ TOOL Bash        (stepIndex=0, attempt=1)  OK
├─ TOOL Task        (stepIndex=1, attempt=1)  ERROR (timed out)
├─ TOOL Task        (stepIndex=1, attempt=2)  OK
│  └─ SUBAGENT "subagent:reporter" → ITERATION → {LLM, TOOL}
```

- **TURN 을 재사용하지 않는 이유**: 용어집 §4 — 턴은 "세션에 들어온 사용자 입력 1건의 처리"이고 루틴은 세션이
  없는 execution 이다. TURN 이라 부르면 CLI `/trace`(TURN 필터) 같은 소비자가 루틴을 턴으로 센다.
- **root 의 `sessionId` 자리(그룹 키)는 `routine:<ScheduledTaskId>`**. `TraceSpan.sessionId` 는 문자열 그룹
  키일 뿐 `SessionId` 가 아니므로, `buildToolContext` javadoc 의 "합성 `SessionId` 를 만들지 않는다" 규칙과
  충돌하지 않는다(세션 키 상태는 `ToolContextKeys.SESSION_ID` 를 보고, 그건 여전히 비어 있다). 같은 작업의 N 번
  발화가 `bySession("routine:<id>")` 로 묶여 "한 세션의 N 턴"과 같은 모양이 된다. `tracing.md` §9.1 의 "스케줄
  재발화의 세션 입도" 질문을 이 결정으로 닫는다. 발화마다 새로 발급되는 `ExecutionId`(`:448`)는 root 의
  `executionId` 속성으로 남는다. (대안 — 발화별 `ExecutionId` 를 그룹 키로 — 는 OQ-1.)
- **base metadata (review-1 B1 반영)**: 루틴 컨텍스트에는 지금 `LLM_CALL_METADATA_KEY` 가 **없다**. 자식 span 의
  그룹 키는 예약 태그가 아니라 `LlmCallMetadata.traceId` 필드에서 온다(`SpanContext.readFrom` 은
  `metadata.getTraceId().orElse("")` 를 sessionId 로 쓰고, `writeInto` 는 두 태그만 쓴다 — `SpanContext.java:108-130`).
  그래서 시작점은 빈 metadata 가 아니라 **`base = LlmCallMetadata.builder().traceId("routine:" + taskId).build()`**
  이고, 그것을 `rootSpan.enrich(base)` 한다 — 메인 턴이 `.traceId(sessionId)` 를 넣은 뒤 enrich 하는 것
  (`OrcaAgentExecutor:1126-1144`)과 같은 순서다. 그러면 TOOL · SUBAGENT · ITERATION · LLM 이 전부 `sessionId =
  routine:<taskId>` 로 기록된다(빈 metadata 로 시작하면 root 만 그 키에 있고 자식은 모두 `""` 버킷에 떨어진다).
  각 시도의 컨텍스트에는 `ToolCallTracing.contextUnder` 와 같은 방식으로 키를 넣는다. 회전 결과가 base 와 같은
  인스턴스(no-op span)면 키를 넣지 않는다 — 트레이싱 오프의 컨텍스트는 오늘과 바이트 단위로 같다. 키에 싣는 필드는
  `traceId` 와 예약 태그뿐이다(component/feature 는 포크 쪽 `SubagentLlmDefaults` 가 채운다).
  - **사용량 귀속에 미치는 영향**: `SubagentLlmDefaults.effectiveMetadata` 는 `traceId` 를 부모에서 상속하므로
    (`SubagentLlmDefaults.java:110`), 트레이싱이 켜진 루틴에서 띄운 포크의 LLM 호출 metadata 는
    `traceId = routine:<taskId>` 를 갖게 된다(지금은 비어 있음). 트레이싱이 꺼져 있으면 키를 넣지 않으므로 오늘처럼
    비어 있다 — 즉 **이 필드는 트레이싱 켜짐 여부에 따라 달라진다.** 오프 경로를 바꾸지 않는다는 공통 수용 기준이
    더 강하다고 보고 이 차이를 받아들이며, 문서(가이드 "범위와 한계")에 적는다. 항상 싣는 쪽은 OQ-9.
- **TOOL span 을 시도마다 여는 이유**: 재시도는 실제로 도구를 다시 실행하고, 각 시도가 서로 다른 포크를 띄울 수
  있다. 단계 하나에 span 하나를 두면 두 시도의 SUBAGENT 가 한 부모에 섞이고 실패한 시도의 원인이 지워진다.
- **`ToolCallTracing.open` 은 재사용하지 않는다** — 그것은 `ToolUse`(id·input)와 iteration·`InvokerType` 을
  요구하는데 루틴 단계에는 셋 다 없다. `RoutineExecutor` 안의 작은 private 헬퍼가 `Spans.startChildSafely` 로
  연다. 결과 기록은 `ToolCallTracing.recordOutcome` 이 `ToolUseResult` 를 받으므로 역시 재사용하지 않고 같은
  모양(`isError`, `contentChars`, 정책상 `content`)을 따른다. 회전은 `ToolCallTracing.contextUnder` 를 그대로 쓴다
  (`ToolContext` 만 받음).
- **TOOL span 을 여는 위치(review-1 비차단 반영)**: `buildToolInput` 결과는 지금 `executeWithTimeout` 안(`:604`)에서야
  생기고, 그 앞의 runtime·도구 조회(`:595-599`)가 던질 수 있으며, `Tracer.Span` 에는 입력 setter 가 없다. 그래서
  **span 은 `executeWithTimeout` 안, `buildToolInput` 직후·`timeoutExecutor.submit` 직전**에 연다(span 홀더는
  `executeStep` 이 넘긴 `StepTrace` 가 받는다). 조회·파싱 실패(`IllegalStateException`/`IllegalArgumentException`/
  JSON 오류)는 도구가 실행되지 않은 시도이므로 **TOOL span 을 남기지 않는다** — 실패는 기존대로 `StepResult` 의
  `lastError` 로 남고, ROUTINE root 가 `error(...)` 로 닫힐 때 그 메시지를 싣는다. (모델이 고르지 않은 도구 이름을
  "TOOL" 로 기록하지 않는 것은 두 실행기의 "알 수 없는 도구" 처리와 다르지만, 루틴의 실패는 root 에서 보인다.)
- **TOOL span 입력·속성**: 입력 `{stepIndex, attempt, input}` — `input` 은 템플릿을 푼 뒤의 도구 파라미터 map
  (`buildToolInput` 결과)으로, 두 실행기의 TOOL span 이 `toolUse.getInput()` 을 항상 싣는 것과 같은 규칙(마스킹은
  `SpanRedactor`). `invokerType` 은 **싣지 않는다** — `InvokerType`(MAIN_AGENT/SUBAGENT/NESTED_SUBAGENT)은 에이전트
  계층의 어휘이고 루틴은 거기 없다. 대신 `invoker=ROUTINE` 문자열 속성을 둔다(enum 확장은 OQ-5).
- **상태 규칙**: 성공 `OK`; `ToolResult` 오류는 `error(content)`; `TimeoutException` 은 `error("Step timed out
  after …")`; 던진 예외는 실행 신호가 울렸으면 `interrupted()`, 아니면 `error(e)` (ToolCallTracing §8.2 와 동일).
  ROUTINE root 는 `RoutineResult` 성공이면 `OK`, 취소(`cancelled(...)`)면 `interrupted()`, 실패면
  `error(errorMessage)`. 출력 `{success, completedSteps, totalSteps}`. root 는 `execute()` 의 `finally` 에서 닫는다.
- **수명 규칙**: tracer 는 애플리케이션 수명 객체이고 `RoutineExecutor` 도 application-scoped 다. tracer 를
  `SchedulingEngineBuilder` 가 넘겨받아 `RoutineExecutor` 에 걸 뿐 아무도 그것을 소유·close 하지 않으며, runtime
  소멸과 무관하다 — "Scope & Scheduling Lifecycle" 규칙과 충돌 없음.

#### ST-1.3 — 앱이 직접 부르는 에이전트 단위 `WorkflowRunner`: **다루지 않는다(열림 유지)**

이 저장소 안에 그 경로의 호출자가 없다(`getWorkflowRunner()` main 호출자는 `OrcaSubagentToolProvider`(도구 경로 —
이미 TOOL span 아래), CLI `RunsCommandHandler`(목록·중지만), GraalJS 도구 프로바이더(도구 경로)). 고칠 모양이
둘로 갈린다 — (a) 러너가 실행마다 자기 root span(예: `WORKFLOW`)을 연다, (b) `run(...)` 이 호출자 metadata 를
받는다 — 어느 쪽이 맞는지는 실제 임베더의 호출 모양이 정한다. 확인할 대상 없이 공개 인터페이스(`WorkflowRunner`)를
바꾸는 것은 근거가 약하다. 백로그 ST-1 은 **(1)(2) 닫힘, (3) 열림**으로 쪼개 적는다.

#### ST-2 — 매니저가 **자기가 만든** 실행기에는 tracer 를 옮긴다

`DefaultSubagentExecutionManager` 에 `private final boolean ownsExecutor` 를 둔다. `LlmClient` 편의 생성자
셋(`:374`, `:391`, `:416`, `:442` — 네 개 중 `:374` 는 `:391` 로 위임)이 `new DefaultSubagentExecutor(...)` 를
만들어 넘기는 경로에서만 `true` 다. `withTracer` / `withTracePayloadPolicy` 는 매니저 필드를 설정한 뒤
`ownsExecutor` 이면 그 실행기(`DefaultSubagentExecutor` 로 알려진 인스턴스)에도 같은 값을 건다. 호출자가 넘긴
실행기는 건드리지 않는다는 기존 원칙(§8.5)은 그대로다.

- **"한 번에 만드는 한 가지 길"(수용 기준 2)** 은 이것이 된다:
  `new DefaultSubagentExecutionManager(llm, tools, hooks[, …]).withTracer(t).withTracePayloadPolicy(p)` —
  SUBAGENT → ITERATION → {LLM, TOOL} 이 나온다. 호출자가 실행기를 직접 만드는 경로는 "실행기에도 걸 것"을 javadoc
  에 둔 그대로(이미 `withTracer` javadoc 에 있음)이며, 그 차이를 javadoc 첫 문단에 명시한다.
- **구현 메모**: 위임 체인 때문에 편의 생성자가 "이 실행기는 내가 만들었다"를 canonical 본문까지 넘겨야 한다. 공개
  시그니처를 늘리지 않도록 **private** 생성자로 처리한다(모양은 아래 "같은 인스턴스를 두 필드에 넣는 법").
  `:442` 은 이미 options 경로, `:391`/`:416` 은 `executorService`/`taskStore` 를
  `SubagentBackgroundExecutionOptions` 로 묶어 private canonical 로 위임하도록 바꾼다(동작 동일 — `:283` 이 이미
  같은 묶음을 만든다; `:416` 의 "taskStore null 이면 NPE" 계약은 `Objects.requireNonNull` 로 유지).
  `ParameterNumber`(7) 안이다. 소유 실행기를 붙잡는 필드는 `private final DefaultSubagentExecutor ownedExecutor`
  (nullable)로 두어 cast 를 피한다 — `ownsExecutor` 불리언 대신 이것 하나로 충분하다.
  **같은 인스턴스를 두 필드에 넣는 법(review-1 비차단 반영)**: `this(new DefaultSubagentExecutor(...), …)` 한 번의
  위임으로는 새 인스턴스를 `subagentExecutor` 와 `ownedExecutor` 두 칸에 동시에 넘길 수 없다(두 번 `new` 하면
  실행기가 둘 생긴다). 그래서 private 생성자는 둘이다 —
  (a) 편의 생성자 전용 `(DefaultSubagentExecutor owned, HookExecutionManager, SubagentBehaviorRegistry, LlmClient,
  SubagentBackgroundExecutionOptions)`: `this(owned, owned, hooks, behaviors, llm, options)` 로 같은 참조를 두 번 넘긴다.
  (b) 공용 본문 `(SubagentExecutor executor, DefaultSubagentExecutor ownedOrNull, HookExecutionManager,
  SubagentBehaviorRegistry, LlmClient, SubagentBackgroundExecutionOptions)`(6개): 지금 공개 canonical(`:326`)의 본문을
  옮긴 것. 공개 canonical 은 `this(executor, null, …)`. 편의 생성자는 `this(new DefaultSubagentExecutor(llm, tools,
  hooks), hooks, behaviors, llm, options)` 로 (a) 를 부른다 — `new` 는 한 번뿐이다.
- **공장**: `OrcaAgentExecutorFactory` 는 실행기를 직접 만들어(비용 추정기·승인 게이트 때문에) 넘기므로 변경 없음.

#### ST-3 — `queuedMs` 속성 + 거부된 작업도 SUBAGENT(ERROR)

- **span 시작 시각의 의미는 바꾸지 않는다**(worker 시작 = 실행 시작). 대신 `executeInBackground` 가 이미 잡는
  `startTime`(`:984`, PENDING 스냅샷의 시각)을 worker 로 넘기고, SUBAGENT span 에 `queuedMs` 속성
  (`Duration.between(enqueuedAt, workerStart).toMillis()`, 문자열)을 남긴다. 포그라운드는 `queuedMs` 를 남기지
  않는다(없음 ≠ 0 — 대기 개념이 없다).
- `runResolvedSubagent` 의 파라미터는 이미 7개(checkstyle `ParameterNumber` 상한)이므로 `boolean background` 를
  **private 정적 중첩 값 클래스 `RunMode`**(`foreground()` / `background(Instant enqueuedAt)`, 필드 `background`,
  `enqueuedAt`)로 바꾼다. 파라미터 수는 그대로다.
- **pool 거부**(`:1033` 분기): SUBAGENT span 을 열고(같은 부모·같은 입력·속성, `background=true`,
  `rejected=true`, `codeBehavior` 는 해석 전이라 없음 — §8.3 의 "해석 실패면 없음"과 같은 규칙), 이미 만들어지는
  `failure` 결과로 `recordSubagentOutcome` → `close()` 한 뒤 기존 순서대로 `fireSubagentStop`. "span 은 Stop hook
  전에 닫는다"(Q9)가 거부 경로에서도 성립하고, SubagentStart/Stop 의 순서·횟수는 바뀌지 않는다. 이 span 의 길이는
  0 에 가깝고 `queuedMs` 는 남기지 않는다(대기열에 들어가지 못했다).
- span 열기·속성 설정·닫기를 `runResolvedSubagent` 와 거부 분기가 공유하도록 private 헬퍼 두 개
  (`openSubagentSpan(launchContext, subagentName, taskId, goal, mode, policy)` → `Tracer.Span`(nullable),
  `closeSubagentSpan(span, result, policy)`)로 뽑는다.
- **열림으로 남는 것**: 부모보다 늦게 시작하는 span 을 외부 뷰어(OTLP)가 어떻게 그리는지 — `aimon-tracing-otlp`
  가 없어 검증 불가.

#### ST-4 — `SubagentExecutionResult.executionId` + SUBAGENT 속성

- `SubagentExecutionResult` 에 nullable 필드 `executionId` 를 더하고 `Optional<ExecutionId> getExecutionId()` 와
  복사 메서드 `withExecutionId(ExecutionId)`(기존 `withQuotaViolation` 과 같은 패턴)를 둔다. 정적 팩토리
  (`success`/`failure`/`emptySuccess`/`emptyFailure`)는 **바꾸지 않는다** — 값은 결과를 만드는 열 군데가 아니라
  실행기의 한 지점에서 찍는 것이 맞다. `equals`/`hashCode`/`toString` 에 포함.
- **찍는 지점**: `DefaultSubagentExecutor.execute`(`:388`) 의 `return runReActLoop(lc);` →
  `return runReActLoop(lc).withExecutionId(executionId);`. `runReActLoop` 의 모든 반환(성공·절단·중단·예산·쿼터·
  stall·차단·실패)이 여기를 지나므로 한 줄로 전부 덮는다. 재개된 실행은 이어받은 id(`ExecutionId.of(label)`)가
  찍힌다 — 그것이 그 실행의 정체성이다.
- **매니저**: `closeSubagentSpan` 이 `result.getExecutionId().ifPresent(id -> span.setAttribute("executionId",
  id.value()))`. 값이 없는 경우 — 코드 behavior 경로(`SubagentBehaviorRunner` 는 `ExecutionId` 를 발급하지
  않음), 해석 실패·ceiling 거부·pool 거부, 실행기가 `runReActLoop` 전에 던진 경우 — 는 속성을 남기지 않는다.
- 결과는 직렬화되지 않는다(`TaskResultStore` 는 `TaskResult` 를 저장 — `SubagentExecutionResult` 코덱 없음, grep
  확인)라 저장 포맷 영향이 없다.

#### ST-5 — compaction 요약 호출에 **예약 태그만** 싣는다 (메인 턴 + 포크), COMPACTION span 은 열림

확인한 사실: `ContextRequest` 에는 이미 `callMetadata`(`agent/context/ContextRequest.java:112`)가 있고 엔진은
그것을 요약 호출로 넘긴다(`DefaultContextEngine:338,549`, `RollingContextEngine:568` →
`DefaultCompactionEngine:406-412`). 그러나 **메인 턴(`OrcaAgentExecutor.contextRequest`, `:3294`)도 포크
(`applyCompactionGate`, `:734`)도 그것을 채우지 않는다.** 그래서 요약 호출은 예약 태그가 없고 `TracingLlmClient`
(`:135-138`)는 span 을 만들지 않는다 — "트리 어디에 붙는지 모른다"가 아니라 **트리에 없다**.

- **함정**: 엔진은 `callerMetadata.withDefaults(engineDefaults)` 로 병합해 **호출자 필드가 이긴다**(`:407`).
  iteration metadata 를 통째로 넘기면 `feature` 가 `COMPACTION` 에서 `react-loop`/서브에이전트 feature 로
  바뀌어 **사용량 집계가 틀어진다.** 따라서 넘기는 것은 **span 위치(그룹 키 + 예약 태그)만 담은 metadata** 다.
- 새 공개 헬퍼 `Spans.traceCarrier(LlmCallMetadata) : Optional<LlmCallMetadata>` — 입력의 예약 태그가 부모를
  가리키면 `LlmCallMetadata.builder().traceId(<입력의 traceId, 있으면>).build()` 에 두 예약 태그를 얹은 값, 없으면
  empty. **`traceId` 를 함께 싣는 이유(review-1 B2 반영)**: span 의 그룹 키는 `traceId` 필드에서 읽힌다
  (`SpanContext.readFrom`). 태그만 넘기면 엔진이 `engineDefaults.traceId(sessionId.toString())`
  (`DefaultCompactionEngine:405-406`)로 채우는데, 그 `sessionId` 는 transcript buffer 의 것이고 포크에서는
  `forkTranscriptLabel(executionId)` — 실행 id 를 감싼 가짜 세션이다. 그러면 포크의 요약 LLM span 이 부모 ITERATION
  과 다른 그룹 키로 기록되어 `bySession(<실제 세션>)` 에서 빠지고, `applyCompactionGate` 주석(`:729-733`)이 경고한
  "세션 행세하는 실행 id" 가 그대로 드러난다(메인 턴은 buffer 세션과 TURN root 세션이 우연히 같아서 피할 뿐이다).
  `feature`/`component` 는 여전히 싣지 않으므로 엔진 기본값(`COMPACTION`/`compaction-engine`)이 남는다 — 대안 J 가드
  유지. 두 실행기가 서로 다른 패키지(`agent.impl.orca`, `subagent.execution`)라 SPI 패키지(`tracing`)에 둔다.
  트레이싱 오프면 empty → `ContextRequest.callMetadata` 를 설정하지 않음 → 오늘과 같은 요청.
  - **사용량 귀속 변화**: 트레이싱이 켜진 포크에서 요약 호출 metadata 의 `traceId` 는 포크 label
    (`subagent:<name>:…` 를 감싼 값)에서 **부모 세션 id**(포크 LLM 호출이 이미 상속하는 값)로 바뀐다. 포크의
    다른 LLM 호출과 같은 귀속이 되므로 의도된 변화로 본다. 메인 턴은 값이 같아 변화 없음. 트레이싱 오프는 무변화.
  - 메인 턴의 `contextRequest` 는 이미 `ContextCaller.principal(scope.getPrincipal())` 을 넘기지만(`:3298`), 요약
    호출의 **call metadata** 에는 principal 이 없다(`CompactCommand` 는 싣는다). 그것은 이번 범위가 아니다 —
    그룹 키와 태그만 싣는다(OQ-6).
- **포크**: `applyCompactionGate(lc, iterationCount)` 에 iteration 의 metadata(`trace.metadata`)를 넘기고
  (`applyCompactionGate(lc, iterationCount, trace.metadata)`), 요약 LLM span 은 **그 ITERATION 아래** 붙는다 —
  PR #255 가 ITERATION 을 게이트 앞에서 연 의도 그대로다.
- **메인 턴**: 게이트(`:1726`)는 ITERATION span 을 여는 지점(`:1777`)보다 **앞**이고, 그 시점의 `scope.activeSpan`
  은 TURN span 이다(`:1976` 에서 매 iteration 끝에 복원). 따라서 `contextRequest(scope, …)` 에
  `Spans.traceCarrier(scope.activeSpan.enrich(scope.llmCallMetadata))` 를 실으면 요약 LLM span 은 **TURN 아래**
  붙는다. 메인 루프의 ITERATION 여는 지점을 게이트 앞으로 옮기는 것은 BLOCK/중단 반환 경로의 span 닫기를 다시 짜야
  하는 리팩터라 하지 않는다. 이 비대칭(메인=TURN 아래, 포크=ITERATION 아래)은 문서에 적는다(OQ-4).
- **식별 가능성**: `TracingLlmClient.traced` 가 LLM span 에 `feature` 속성(`metadata.getFeature()`, 있으면)을
  남기게 한다. 그러면 COMPACTION span 없이도 요약 호출이 `feature=COMPACTION` 으로 구별된다. 한 줄, 모든 LLM
  span 에 이롭다.
- **COMPACTION span 은 열림으로 둔다.** 근거: (a) 게이트는 매 iteration 돌고 대부분 `NONE` 을 돌려주는데, span 을
  게이트 앞에서 열면 "compaction 이 없었던" span 이 iteration 마다 쌓인다 — `Tracer.Span` 에는 버리기(discard)가
  없다. (b) "실제로 compact 할 때만" 열려면 결정이 내려지는 엔진 **안**에서 열어야 하고, 그러려면 엔진이 tracer 를
  알아야 한다(`ContextRequest`/`CompactionEngine` 에 관찰성 의존을 넣는 SPI 변경) 또는 `PreCompact`/`PostCompact`
  hook 기반 `TracingHookAdapter`(`tracing.md` §9) — 둘 다 TASK 가 "범위가 과하면 1번만" 이라고 한 그 범위다.
  `tracing.md` §5 표의 COMPACTION 행("PreCompact/PostCompact Hook")은 **미구현**이라고 정정한다.

#### ST-6 — 문서로 닫는다

launcher 가 확인한 증거(저장소 · 커밋 · 검색어 · 날짜)를 백로그 ST-6 에 그대로 옮기고 닫힘으로 표시한다. 코드
변경 없음. 릴리스 노트 메모: "`aimon-sandbox` 등 외부 독자 0건 — 2026-10-17 확인".

### 2.2 종합: 백로그 결말

| 항목 | 결말 |
|---|---|
| ST-1 | (1) 슬래시 `COMMAND` · (2) 루틴 `ROUTINE`+`TOOL` **닫힘**, (3) 직접 호출 `WorkflowRunner` **열림** |
| ST-2 | **닫힘** — 소유 실행기로 전달 |
| ST-3 | (1) `queuedMs` · (3) 거부 span **닫힘**, (2) 외부 뷰어 렌더링 **열림** |
| ST-4 | **닫힘** |
| ST-5 | (1) 요약 호출의 metadata(메인+포크) **닫힘**, COMPACTION span **열림**(`TracingHookAdapter` 와 함께) |
| ST-6 | **닫힘** |

ST-1·ST-3·ST-5 는 항목 자체를 열림으로 두고 본문에 "닫힌 부분 / 남은 부분"을 나눠 적는다(항목을 쪼개 번호를 새로
매기지 않는다 — 외부에서 ST-n 으로 참조됨). 제목 카운트: **열림 3 · 닫힘 3**.

### 2.3 기각한 대안

| # | 대안 | 기각 이유 |
|---|---|---|
| A | 슬래시 명령 span 으로 `TOOL` 재사용 | §2.1 ST-1.1 — `ToolUse`·iteration·`invokerType` 없는 TOOL 은 "모델이 고른 도구"라는 의미를 깨고 집계를 틀리게 한다 |
| B | 포크 스킬 명령에만 COMMAND span | 명령 종류에 따라 span 유무가 갈리고, 판별에 명령 해석을 앞당겨야 한다. 이득 없음 |
| C | 루틴 root 로 `TURN` 재사용 | 턴 ≠ execution(용어집 §4). CLI `/trace` 등 TURN 소비자가 루틴을 턴으로 센다 |
| D | 루틴 단계당 TOOL span 1개(시도 무시) | 재시도마다 도구가 다시 돌고 서로 다른 포크를 띄울 수 있다. 실패 시도의 원인과 SUBAGENT 부모가 섞인다 |
| E | 루틴 tracer 를 `RoutineExecutor` 생성자 인자로 | 가능하지만 이 저장소의 tracing 소유 객체 넷(두 실행기·매니저·이번 루틴)이 모두 `withTracer`/`withTracePayloadPolicy` 사후 setter 라는 한 관례를 갖는 편이 읽기 쉽다(Q4 결말과 같음). `SchedulingEngineBuilder` 가 유일한 생성 지점이라 사후 setter 의 "잊기 쉬움" 위험도 작다 |
| F | 매니저가 **넘겨받은** 실행기에도 tracer 를 건다(`instanceof DefaultSubagentExecutor`) | 호출자의 객체를 몰래 바꾸지 않는다는 §8.5 원칙 위반. 공유 실행기에 다른 tracer 가 이미 걸려 있으면 덮어쓴다 |
| G | 백그라운드 SUBAGENT span 을 enqueue 시점에 연다(시작 시각 = 대기 포함) | PR #255 대안 G 와 같은 이유(worker 가 안 도는 모든 경로에서 close 보장) + `Tracer` SPI 에 시작 시각 인자가 없어 worker 에서 "과거 시각으로 시작"도 불가. 속성 `queuedMs` 가 같은 정보를 SPI 변경 없이 준다 |
| H | **매니저 생성자 11개를 빌더로 일원화**(Q4, 사용자 허용) | 허용 범위지만 이번 run 에선 하지 않는다: main 3곳 + 테스트 ~70곳(+ `aimon-llm-*`, `aimon-workflow-graaljs` 테스트, 외부 `aimon-sandbox`)을 기계적으로 바꾸는 diff 가 트레이싱 변경 리뷰를 덮는다. ST-2 는 그것 없이 닫힌다(소유 실행기 전달). 별도 변경으로 하는 것이 싸다 — OQ-2 |
| I | `SubagentExecutionResult` 정적 팩토리에 `ExecutionId` 인자 추가 | 결과를 만드는 지점이 열 군데 넘고(behavior·매니저 실패 포함), 그중 다수는 id 가 없다. 실행기 한 지점에서 `withExecutionId` 로 찍는 편이 누락이 없다 |
| J | compaction 요약 호출에 iteration metadata 를 **통째로** 넘김 | `withDefaults` 병합에서 호출자 `feature` 가 이겨 `Feature.COMPACTION` 집계가 깨진다(§2.1 ST-5) |
| K | 메인 루프의 ITERATION span 을 compaction 게이트 앞으로 이동(대칭) | BLOCK·중단으로 게이트에서 반환하는 경로의 span 닫기를 다시 짜야 하는 리팩터. TURN 아래 붙는 것으로도 "트리에 없다"는 결함은 닫힌다 |
| L | 엔진(`DefaultCompactionEngine`)이 tracer 를 받아 COMPACTION span 을 직접 연다 | `agent.compact`/`agent.context` SPI 에 관찰성 의존을 넣는 변경이고 엔진 구현 셋(Default·Rolling·Passthrough)과 생성 지점이 따라온다. `TracingHookAdapter` 작업과 함께 결정할 일 |
| M | 에이전트 단위 `WorkflowRunner` 에 root span(`WORKFLOW`)을 지금 넣는다 | 이 저장소에 호출자가 없어 모양(자기 root vs 호출자 metadata)을 정할 근거가 없다(§2.1 ST-1.3) |

---

## 3. 구체 변경 (파일·모듈별)

### 3.1 `at.aimon.core.tracing`

- **`SpanType`** — 상수 둘 추가: `COMMAND`("A slash command a turn runs instead of the ReAct loop; a fork it starts
  nests under it"), `ROUTINE`("The root span of one scheduled routine run (one fire); each step attempt is a TOOL
  child"). 클래스 javadoc 의 트리 설명에 두 root/중간 노드를 더한다. 순서: `SUBAGENT` 뒤, `COMPACTION` 앞이 아니라
  **맨 끝에 추가**(ordinal 에 기대는 코드는 없지만 관례상 append).
- **`Spans`** — 정적 메서드 둘 추가:
  - `startRootSafely(Tracer, String sessionId, SpanType, String name, Map<String,Object> inputs) : Tracer.Span` —
    `tracer.startRoot` 가 던지면 noop span(`startChildSafely` 와 대칭). `OrcaAgentExecutor:1141-1149` 의 인라인
    try/catch 는 **건드리지 않는다**(enrich 까지 한 블록으로 묶여 있고 동작이 같다 — 범위 밖 리팩터 금지).
  - `traceCarrier(LlmCallMetadata) : Optional<LlmCallMetadata>` — `traceId` + 예약 태그만(§2.1 ST-5).
- **`impl/TracingLlmClient`** — `traced(...)` 에서 span 을 연 뒤 `metadata.getFeature()` 가 있으면
  `span.setAttribute("feature", …)`. (`getFeature()` 반환 타입에 맞춰 문자열화.)

### 3.2 `at.aimon.core.agent.impl.orca.OrcaAgentExecutor`

- `executeCommand`(`:2144`): try 안 맨 앞에서 `payloadPolicy`/`tracer` 스냅샷 → `Spans.startChildOfLazily(tracer,
  scope.llmCallMetadata, SpanType.COMMAND, "command:/" + name, () -> commandSpanInputs(...))`. 회전된 metadata 를
  `:2193` 에 넣는다(span 이 없으면 기존 `scope.llmCallMetadata`). 결과/예외에 따라 outputs·status, `finally` 에서
  close. 메서드가 길어지면(checkstyle `MethodLength` 150) span 수명을 private 홀더(`CommandTrace`, 포크의
  `IterationTrace` 와 같은 모양)로 뺀다.
- `contextRequest(scope, budgetForced)`(`:3294`): `Spans.traceCarrier(scope.activeSpan.enrich(scope.llmCallMetadata))
  .ifPresent(builder::callMetadata)` — `enrich` 는 noop 에서 무변경이라 트레이싱 오프면 아무것도 싣지 않는다.
  `scope.activeSpan.enrich` 가 던질 수 있으므로 `Spans.enrichSafely(scope.activeSpan, …)` 를 쓴다.

### 3.3 `at.aimon.core.subagent.execution`

- **`SubagentExecutionResult`** — 필드 `executionId`, `getExecutionId()`, `withExecutionId(ExecutionId)`;
  private 생성자 인자 하나 추가(이미 `@SuppressWarnings("checkstyle:ParameterNumber")`); `withQuotaViolation` 이
  `executionId` 를 보존하도록, `withExecutionId` 가 `quotaViolation` 을 보존하도록. `equals`/`hashCode`/`toString`.
- **`DefaultSubagentExecutor`**
  - `execute`: `return runReActLoop(lc).withExecutionId(executionId);`
  - `applyCompactionGate(lc, iterationCount)` → `applyCompactionGate(lc, iterationCount, iterationMetadata)`;
    `ContextRequest.builder()…` 에 `Spans.traceCarrier(iterationMetadata).ifPresent(builder::callMetadata)`.
    호출부(`:534`)는 `trace.metadata` 를 넘긴다. 주석(`:531-533`)의 "once compaction calls carry metadata" 를 현재
    사실로 고친다.

### 3.4 `at.aimon.core.subagent.DefaultSubagentExecutionManager`

- 필드 `private final DefaultSubagentExecutor ownedExecutor;`(nullable). private canonical 생성자(§2.1 ST-2 메모).
  `LlmClient` 편의 생성자 넷이 그리로 위임. 공개 canonical(`:326`)과 그 위 체인은 `ownedExecutor = null`.
- `withTracer`/`withTracePayloadPolicy`: `ownedExecutor` 가 있으면 같은 값을 건다. javadoc 재작성 —
  "편의 생성자로 만든 매니저는 이것 하나로 전체 트리; 실행기를 넘긴 매니저는 실행기에도 따로".
- `RunMode` private static final 중첩 클래스(불변, 정적 팩토리 둘). `runResolvedSubagent(…, RunMode mode)`.
  `runExecute` → `RunMode.foreground()`, worker → `RunMode.background(startTime)`.
- `openSubagentSpan(...)` / `closeSubagentSpan(...)` 추출. 속성 추가: `queuedMs`(background 만), `rejected`
  (거부 분기만 `"true"`), `executionId`(결과에 있을 때).
- 거부 분기(`:1033`): `openSubagentSpan(…, RunMode.background(startTime) 의 거부 변형 …)` → `closeSubagentSpan(span,
  failure, policy)` → 기존 `fireSubagentStop(...)` 이하 그대로. `queuedMs` 를 남기지 않도록 `RunMode.rejected()` 를
  세 번째 상태로 둔다(`background=true`, `rejected=true`, `enqueuedAt=null`).

### 3.5 `at.aimon.core.scheduling`

- **`RoutineExecutor`**
  - 필드 `private volatile Tracer tracer = Tracer.noop();`, `private volatile TracePayloadPolicy tracePayloadPolicy
    = TracePayloadPolicy.summaryOnly();` + `withTracer` / `withTracePayloadPolicy`(fluent, null 거부).
  - `execute(task)`: 스냅샷 → `runRoutine(task, coordinator, routineTrace)` 를 감싸 ROUTINE root 를
    `Spans.startRootSafely(tracer, "routine:" + task.getId().value(), SpanType.ROUTINE, "routine:" + task.getId()…)`
    로 열고 `finally` 에서 결과로 닫는다. `base = LlmCallMetadata.builder().traceId("routine:" + taskId).build()`,
    rotated = `Spans.enrichSafely(root, base)` 이고, **rotated 가 base 와 다른 인스턴스일 때만** `buildToolContext` 가
    `LLM_CALL_METADATA_KEY` 를 넣는다(그룹 키가 자식에게 전파되는 근거는 §2.1 ST-1.2 base metadata).
  - `ExecutionId` 는 지금 `buildToolContext` 안에서 만든다(`:448`). root 속성에 싣기 위해 `runRoutine` 이 먼저
    발급해 `buildToolContext(task, signal, executionId, baseMetadata)` 로 넘긴다(발급 시점이 같은 실행 안에서
    몇 줄 앞당겨질 뿐 — 의미 동일).
  - `executeStep`: 시도마다 `StepTrace`(private 홀더: 부모 context·정책·stepIndex·attempt, 나중에 span)를 만들어
    `executeWithTimeout` 에 넘긴다. `executeWithTimeout` 이 `buildToolInput` 직후 span 을 열고 회전된 context 로
    `stepContext` 를 만든다(§2.1 ST-1.2 "TOOL span 을 여는 위치"). `executeStep` 은 결과/`TimeoutException`/예외로
    상태를 기록하고 `finally` 에서 (열렸으면) close 한다.
  - 파라미터 수: `executeStep` 은 이미 6개 → 루트 span/정책을 넘기면 7 이하로 맞춰야 한다. 실행 단위 값
    (`tracer` 스냅샷, `policy`, `executionId`)은 private 홀더 `RoutineTrace` 하나로 묶어 넘긴다(필요시
    `coordinator` 와 함께).
  - ROUTINE root 의 입력 `{taskId, totalSteps}`, 속성 `{taskId, agentRuntimeId, executionId, principal}`(principal 은
    `task.getOwner()` 의 문자열 표현 — `tracing.md` §5.2 상관키 규약).
- **`SchedulingEngineBuilder`** — `tracer(Tracer)`, `tracePayloadPolicy(TracePayloadPolicy)` 빌더 메서드; `build()`
  가 만든 `RoutineExecutor` 에 non-null 일 때만 건다(`:304`).

### 3.6 `aimon-bootstrap` — `AimonStackBuilder.createSchedulingEngine`(`:703`)

`spec.getExecutor().getTracer().ifPresent(builder::tracer)` / `getTracePayloadPolicy().ifPresent(builder::tracePayloadPolicy)`
— 실행기 공장(`:409-410`)과 **같은 tracer 인스턴스**. Spring starter · CLI 는 bootstrap 을 거치므로 무변경.

### 3.7 테스트·문서·기존 호출부

- 기존 공개 시그니처를 바꾸지 않으므로 이 저장소의 호출부 수정은 없다(사용자 허용은 쓰지 않음 — §2.3-H).
- **기존 테스트 갱신**: LLM span 의 `feature` 속성과 SUBAGENT 의 `executionId`(·백그라운드 `queuedMs`) 속성이 새로
  생기므로, 속성 map 을 정확히 단언하는 기존 테스트(`DefaultSubagentExecutionManagerTracingTest`,
  `OrcaAgentExecutorTracingTest`, `TracingLlmClient` 테스트 등)는 같은 커밋에서 함께 고친다. `SubagentExecutionResult`
  의 equals 를 단언하는 테스트도 마찬가지(실행기가 id 를 찍으므로).
- **패키지 의존(ArchUnit)**: `scheduling` 이 `tracing`(SPI)과 `toolinvocation`(`ToolCallTracing.contextUnder`)을 새로
  import 한다. 현재 `toolinvocation` · `tracing` 은 `scheduling` 을 import 하지 않으므로
  `PackageDependencyArchitectureTest.noNewTopLevelCorePackageCycles` 의 동결 기준선에 새 순환이 생기지 않는다 —
  구현 후 그 테스트로 확인한다. `impl` 패키지(`tracing.impl`)는 import 하지 않는다.
- 문서는 §6.4.

---

## 4. 데이터·인터페이스 변화

### 4.1 공개 API 변경 목록 (HANDOFF·커밋 메시지에 그대로 옮길 것)

| 대상 | 변경 | 호환 |
|---|---|---|
| `at.aimon.core.tracing.SpanType` | `COMMAND`, `ROUTINE` 추가 | 소스 호환(enum append). 외부 `switch` 는 default 필요 |
| `at.aimon.core.tracing.Spans` | `startRootSafely(...)`, `traceCarrier(LlmCallMetadata)` 추가 | 추가 |
| `at.aimon.core.tracing.impl.TracingLlmClient` | LLM span 에 `feature` 속성 | 동작 추가(속성 하나) |
| `at.aimon.core.subagent.execution.SubagentExecutionResult` | `getExecutionId()`, `withExecutionId(ExecutionId)`; `equals`/`hashCode`/`toString` 에 반영 | 추가. **equals 의미 변화**: id 가 찍힌 결과와 안 찍힌 결과는 다르다 |
| `at.aimon.core.subagent.DefaultSubagentExecutionManager#withTracer` / `#withTracePayloadPolicy` | 편의 생성자로 만든 매니저면 소유 실행기에도 적용 | **동작 변경**(시그니처 동일) |
| `at.aimon.core.scheduling.RoutineExecutor` | `withTracer(Tracer)`, `withTracePayloadPolicy(TracePayloadPolicy)` 추가 | 추가 |
| `at.aimon.core.scheduling.SchedulingEngineBuilder` | `tracer(Tracer)`, `tracePayloadPolicy(TracePayloadPolicy)` 추가 | 추가 |

제거·시그니처 변경 없음 → 외부 `aimon-sandbox`(`OrcaRuntimeSandboxE2ETest.java:105` 의 편의 생성자) 영향 없음.

### 4.2 span 속성·입력 (새로 생기는 것)

| span | 입력 | 출력 | 속성 |
|---|---|---|---|
| COMMAND | `command`, `argsChars`, (`rawArguments` — 내용 캡처 시) | `success`, `responseChars` | — |
| ROUTINE (root) | `taskId`, `totalSteps` | `success`, `completedSteps`, `totalSteps` | `taskId`, `agentRuntimeId`, `executionId`, `principal` |
| TOOL (루틴 단계) | `stepIndex`, `attempt`, `input` | `isError`, `contentChars`, (`content` — 내용 캡처 시) | `invoker=ROUTINE`, `stepIndex`, `attempt` |
| SUBAGENT (기존) | 무변경 | 무변경 | + `queuedMs`(백그라운드), + `rejected`(거부), + `executionId`(있을 때) |
| LLM (기존) | 무변경 | 무변경 | + `feature`(있을 때) |

`tracing.md` §4.1 의 `TraceSpan` 표에 COMMAND/ROUTINE 열을 더하지 않고, 표 아래 한 줄로 "COMMAND 는 TOOL 과,
ROUTINE 은 TURN 과 같은 필드 규칙(ROUTINE 의 `sessionId` 는 `routine:<taskId>`)"이라고 적는다(표 폭).

---

## 5. 실패 모드와 처리

| 실패 | 처리 |
|---|---|
| tracer 가 `startRoot`/`startChild` 에서 던짐 | `Spans.startRootSafely`/`startChildSafely` → noop span. 루틴·명령은 그대로 실행, span 없음 |
| `enrich` 가 던짐 | `Spans.enrichSafely` → base 그대로. 루틴은 키를 넣지 않고(base == empty), 명령은 TURN metadata 그대로 → 포크가 TURN 아래(오늘 모양) |
| span `close`/`setOutputs` 가 던짐 | 발생하지 않는다 — `Tracer` javadoc(`Tracer.java:13`)이 `Span` 수명 메서드는 던지지 않는다고 보장한다. 추가 try/catch 없음 |
| 트레이싱 오프(noop/미주입) | 명령: `startChildOfLazily` 가 부모 없음 → 입력 map 안 만듦, 컨텍스트 무변경. 루틴: noop root, `enrichSafely` 가 같은 인스턴스 → 키 미삽입, 시도 span 은 부모 태그 없으니 `startChildOf` 가 empty → 컨텍스트 무변경. compaction: `traceCarrier` empty → `callMetadata` 미설정. 매니저: 기존 경로. 루틴은 발화마다 noop root 를 하나 얻는다(NoopTracer 비용 — cron 빈도라 무시) |
| 루틴 단계 타임아웃 후에도 도구(포크)가 계속 돎 | TOOL span 은 `error(timed out)` 으로 닫히고, 늦게 끝난 SUBAGENT 는 닫힌 부모 아래 id 로 붙는다(백그라운드와 같은 모양 — ST-3 의 뷰어 질문에 포함) |
| 루틴이 취소됨(경계·단계 중) | 진행 중 시도 span: 예외면 신호를 보고 `interrupted()`, 오류 결과면 `error(content)`(도구가 낸 결과가 그렇다). root 는 `interrupted()` |
| 명령 실행이 `Error` 를 던짐 | `finally` 에서 결과가 없으면 `error("Command execution did not complete")` 로 닫고 전파 |
| pool 거부 경로에서 span 기록이 던짐 | `recordSubagentOutcome`/`close` 는 기존 경로와 같은 헬퍼 — 거부 처리(결과 저장·FAILED 전이·Stop hook·부모 통지)가 그 뒤에 그대로 실행되도록 span 처리를 맨 앞에 두되 try/catch 로 격리 |
| 요약 호출 태그가 잘못된 부모를 가리킴 | 태그는 그 iteration(포크)·TURN(메인)의 enrich 결과로만 만들어지므로 다른 실행의 span 을 가리킬 수 없다(병렬 iteration 없음) |
| `withExecutionId` 이후 `withQuotaViolation`(또는 역순) | 두 복사 메서드가 서로의 필드를 보존 — 단위 테스트로 고정 |
| `ownedExecutor` 가 있는 매니저에 `withTracer` 를 두 번 | 마지막 값이 둘 다에 — 매니저·실행기 tracer 가 어긋날 수 없다 |

orphan 금지: 새 span 은 전부 (a) `startChildOf*`(부모 태그 필수) 또는 (b) ROUTINE root 다. root 를 여는 지점은
`OrcaAgentExecutor`(TURN)와 `RoutineExecutor`(ROUTINE) 둘뿐이다.

---

## 6. 테스트 전략

모두 `DefaultTracer` + `InMemoryTraceSpanStore`(+ 고정 `Clock`)로 트리 모양·속성을 단언한다. 기존
`DefaultSubagentExecutorTracingTest` · `DefaultSubagentExecutionManagerTracingTest` · `OrcaAgentExecutorTracingTest`
의 지원 코드를 재사용한다.

### 6.1 ST-1

- `OrcaAgentExecutorTracingTest`(또는 `SlashSkillForkE2EIntegrationTest` 옆 새 클래스):
  1. 포크 스킬 `/my-skill args` → `TURN → COMMAND("command:/my-skill") → SUBAGENT → ITERATION → LLM`.
  2. 비포크 명령(`/help` 류 direct 명령) → `TURN → COMMAND`, 자식 없음, `OK`.
  3. 실패 명령(없는 이름) → COMMAND `ERROR`, 턴은 기존대로 실패 결과.
  4. summary-only 정책 → `rawArguments` 없음, `argsChars` 있음; content 정책 → 있음.
  5. 트레이싱 오프 → 명령 도구 컨텍스트의 `LLM_CALL_METADATA_KEY` 가 `scope.llmCallMetadata` 와 **같은 인스턴스**.
- 새 `RoutineExecutorTracingTest`:
  1. `Task` 를 부르는 단계(테스트 `SubagentExecutionManager` 또는 실제 매니저 + 스크립트 LLM) → `ROUTINE → TOOL(Task)
     → SUBAGENT → ITERATION → LLM`, **그 trace 의 모든 span**(root 만이 아니라 TOOL · SUBAGENT · ITERATION · LLM)의
     `sessionId == "routine:<id>"` 이고 store 에 `sessionId == ""` 인 span 이 없다(B1 회귀 가드). `executionId` 속성이
     단계가 받은 `ToolContextKeys.EXECUTION_ID` 와 같다. 포크 LLM 호출이 받은 metadata 의 `traceId` 가
     `routine:<id>` 다.
  2. 재시도(1회 실패 후 성공) → 같은 `stepIndex` 의 TOOL 두 개(`attempt=1` ERROR, `attempt=2` OK).
  3. 타임아웃 → TOOL `ERROR`("timed out"), root `ERROR`.
  4. 취소(`interrupt`) → root `INTERRUPTED`.
  5. 트레이싱 오프(미주입) → store 비어 있음, 단계가 받은 컨텍스트에 `LLM_CALL_METADATA_KEY` 없음(오늘과 동일).
  6. 던지는 tracer → 루틴 결과·이벤트 순서가 트레이싱 없는 실행과 같다.
  7. 두 번 발화 → `bySession("routine:<id>")` 가 두 발화의 **모든** span 을 돌려주고(자식 포함), `traceId` 로 둘로
     나뉜다.
- `SchedulingEngineBuilderTest`: `tracer(t)` 로 만든 엔진의 루틴이 span 을 남긴다.
- bootstrap: 기존 `AimonStackBuilder` 배선 테스트에 "executor spec 의 tracer 가 스케줄링 루틴에도 걸린다" 1건.

### 6.2 ST-2

- `DefaultSubagentExecutionManagerTracingTest`:
  1. **수용 기준 3**: `new DefaultSubagentExecutionManager(tracedLlm, tools, hooks).withTracer(t)` 만으로
     `SUBAGENT → ITERATION → LLM`.
  2. 편의 생성자 4종(위임 포함) 각각이 소유 실행기에 전달(파라미터화).
  3. 실행기를 넘긴 생성자 + 매니저 `withTracer` 만 → 기존 퇴화(SUBAGENT 바로 아래 LLM) 그대로 — 원칙 회귀 가드.
     (기존 `DefaultSubagentExecutionManagerTracingTest` 는 매니저를 실행기를 넘기는 생성자로만 만든다(`:278`, `:306`)
     — 편의 생성자로 납작한 모양을 단언하는 기존 테스트는 없으므로 ST-2 가 뒤집는 기존 단언은 없다.)
  4. `withTracePayloadPolicy(captureContent)` 가 소유 실행기의 TOOL span `content` 에 반영.

### 6.3 ST-3 · ST-4 · ST-5

- ST-3 (`DefaultSubagentExecutionManagerTracingTest` / `...BackgroundTest`):
  1. 1-스레드 pool 을 latch 로 막아 두 번째 작업이 대기 → 그 SUBAGENT 의 `queuedMs` 가 latch 대기 이상
     (하한만 단언 — 상한 단언은 flaky), `background=true`. 포그라운드는 `queuedMs` 없음.
  2. 큐 0 의 bounded pool 로 거부 유도 → SUBAGENT `ERROR`, `rejected=true`, 부모는 `Task` TOOL span. 기존
     SubagentStart/Stop 순서 단언(기존 테스트)을 그대로 통과 + "span close 가 Stop hook 보다 먼저"(hook 안에서 store
     조회).
- ST-4:
  1. `SubagentExecutionResultTest`: `withExecutionId` / `withQuotaViolation` 상호 보존, equals/hashCode.
  2. `DefaultSubagentExecutorTest`: 성공·실패·중단·예산 정지 결과 모두 `getExecutionId()` present; 재개 실행은
     이어받은 id.
  3. 매니저 tracing 테스트: SUBAGENT `executionId` 속성 == 포크 도구가 받은 `ToolContextKeys.EXECUTION_ID`;
     code behavior 경로·해석 실패에서는 속성 없음.
- ST-5:
  1. 포크: compaction 이 실제로 일어나도록 작은 window 의 `DefaultContextEngine` + 스크립트 LLM → 요약 LLM span 이
     해당 ITERATION 의 자식이고 `feature=COMPACTION` 속성, **그 `sessionId` 가 부모 ITERATION(=부모 턴의 세션)과
     같고 포크 label 이 아니다**(B2 회귀 가드).
  2. 메인 턴: 같은 구성으로 요약 LLM span 이 TURN 의 자식, `feature=COMPACTION`, `sessionId` 가 TURN 과 같다.
  3. 사용량 집계 회귀: 요약 호출이 받은 metadata 의 `feature` 가 `COMPACTION`, `component` 가 `compaction-engine`
     (엔진 기본값 유지 — 대안 J 가드).
  4. 트레이싱 오프: `ContextRequest.getCallMetadata()` empty(메인·포크).
  5. `SpansTest`: `traceCarrier` — 태그 있음/없음, `traceId` 는 복사되고 `component`/`feature`/`principal`/그 밖의
     태그는 복사되지 않음.

### 6.4 문서·품질 게이트

- 백로그 `subagent-tracing-open-items.md`: 각 항목 상태·결말(§2.2), 제목 "열림 3 · 닫힘 3", ST-6 증거, 줄 번호는
  새 확인 날짜와 함께.
- `tracing.md`: §4.1 enum 코드 블록, §5 표(COMMAND·ROUTINE 행 추가, COMPACTION 행을 "미구현 — 요약 LLM span 은
  `feature=COMPACTION` 속성으로 TURN/ITERATION 아래"로 정정), §5.1 예시에 COMMAND/ROUTINE 짧은 트리, §5.2 루틴의
  그룹 키, §9 표(서브에이전트 회전 행의 백로그 링크 문구, `TracingHookAdapter` 행에 "COMPACTION span 의 남은 갈래"),
  §9.1 "스케줄 재발화의 세션 입도" 해소 표기. (번역본 없음 — Q6.)
- `subagent-trace-nesting.md`: 본문·§8 은 고치지 않는다(승인 문서 + 구현 기록). §8.8 끝에 "이후 처리는 백로그
  정본을 볼 것" 한 줄, 또는 머리말 상태 줄에 백로그 처리 날짜. (번역본 없음.)
- `execution-tracing-guide.md` + `.en.md`: Span 모델 절(새 타입 둘, 새 속성), 프로그래밍 방식 배선 절(편의 생성자 +
  `withTracer` 한 줄로 충분, `SchedulingEngineBuilder.tracer`), 범위와 한계 절(직접 호출 `WorkflowRunner`, COMPACTION
  span, 외부 뷰어). 정본·번역본 같은 커밋, `source_commit` 은 수정 직전 정본 커밋, 구조(제목·표 행·펜스) 일치.
- `SpanType`·`Spans`·`RoutineExecutor`·`SchedulingEngineBuilder`·`SubagentExecutionResult` javadoc(ratchet:
  새 공개 요소 전부 javadoc).
- 실행: `python3 scripts/check-doc-links.py`, `check-translation-structure.py`, `check-translation-staleness.py`,
  `./gradlew checkAll`.

### 6.5 커밋 단위 권고

1. `tracing`: `SpanType`·`Spans`·`TracingLlmClient` feature (+ `SpansTest`)
2. ST-4 결과 + 실행기 스탬프 + SUBAGENT 속성
3. ST-2 소유 실행기
4. ST-3 `RunMode`·`queuedMs`·거부 span
5. ST-5 compaction 태그(메인+포크)
6. ST-1.1 COMMAND
7. ST-1.2 ROUTINE (+ scheduling builder, bootstrap)
8. 문서(백로그·tracing.md·nesting·가이드+번역)

---

## 7. 열린 질문 (TASK.md 로 해소 불가 — 가정하지 않고 표기)

| # | 질문 | 이 설계의 잠정안 |
|---|---|---|
| OQ-1 | 루틴 trace 의 그룹 키(`TraceSpan.sessionId`)를 작업 단위(`routine:<ScheduledTaskId>`)로 할지 발화 단위(`ExecutionId`)로 할지 | 작업 단위. 한 작업의 발화 이력이 `bySession` 하나로 나온다. 발화 식별은 `traceId`·`executionId` 속성으로 충분. 다만 cron 이 잦은 작업은 그룹이 무한히 커진다(보존 정책은 store 몫) |
| OQ-2 | 사용자가 허용한 **매니저 생성자 정리(Q4, 빌더 일원화)** 를 이번 run 에 넣을지 | 넣지 않는다(§2.3-H). 넣는다면 별도 커밋, 이 저장소 ~75 호출부 동반, 외부 `aimon-sandbox` 깨짐을 HANDOFF 에 기록. 오케스트레이터/사용자 판단 필요 |
| OQ-3 | 에이전트 단위 `WorkflowRunner` 직접 호출 경로의 모양 | 열림 유지(§2.1 ST-1.3) |
| OQ-4 | 메인 턴의 요약 LLM span 이 TURN 아래(포크는 ITERATION 아래)인 비대칭을 받아들일지 | 받아들인다(대안 K 기각). 문서에 명시 |
| OQ-5 | 루틴 단계 TOOL span 의 `invokerType` — `InvokerType` 에 `ROUTINE` 을 더할지 | 더하지 않고 `invoker=ROUTINE` 문자열 속성. `InvokerType` 은 에이전트 계층 어휘이고 다른 소비자(hook 등)가 있다 |
| OQ-6 | 메인 턴 요약 호출에 principal 을 실어 `CompactCommand` 경로와 맞출지 | 범위 밖 — 태그만 싣는다 |
| OQ-7 | `/compact` 명령의 요약 호출을 COMMAND span 아래 붙일지(`CompactCommand.buildCallMetadata` 가 컨텍스트 키의 태그를 합치도록) | 이번엔 하지 않음. COMMAND span 은 생기지만 그 요약 LLM span 은 여전히 없다 — 백로그 ST-5 본문에 한 줄로 적는다 |
| OQ-9 | 루틴 포크의 사용량 metadata `traceId`(`routine:<taskId>`)가 트레이싱 켜짐 여부에 따라 달라진다(§2.1 ST-1.2) — 트레이싱 오프에서도 `traceId` 만 싣는 키를 넣을지 | 넣지 않는다. 오프 경로 무변경 기준을 우선. 가이드에 명시 |
| OQ-8 | CLI `/trace` 가 TURN 만 보여 주므로 루틴 trace 는 CLI 에서 볼 수 없다 — 보기 명령을 더할지 | 이번 범위 밖(코어 store 로는 조회 가능). 가이드 "범위와 한계"에 적는다 |

---

## 8. 구현 뒤 — 설계와 달라진 점

> 이 절은 구현 뒤에 덧붙였다(2026-10-17). 위 본문은 승인된 그대로다. 줄 번호는 이 변경의 작업 트리 기준이다. 열림/닫힘의
> 현재 상태는 이 절이 아니라 [백로그](../../backlog/subagent-tracing-open-items.md)가 갖는다.

### 8.1 ST-3 — `queuedMs` 는 pool 에 넘기는 순간부터 잰다

§2.1 ST-3 은 `executeInBackground` 가 이미 잡는 `startTime`(PENDING 스냅샷 시각)을 쓰기로 했다. 그 시각은 **동기**
SubagentStart hook 과 저장소 쓰기보다 앞이라, 그대로 쓰면 `queuedMs` 가 hook 시간(셸 명령 hook 이면 수 초)까지 센다 —
ST-3 이 가르려던 "느린 실행 / 줄 선 실행" 이 다시 섞인다. 그래서 `supplyAsync` 바로 앞에서 따로 잰다
(`RunMode.background(Instant.now())`, `DefaultSubagentExecutionManager.java:1102`). 리뷰 2회차의 제안을 받았다.

### 8.2 ST-2 — 편의 생성자 전용 private 생성자는 실행기를 맨 뒤에 받는다

§2.1 ST-2 (a) 의 `(DefaultSubagentExecutor owned, Hook…, Behaviors, LlmClient, Options)` 는 공개 canonical 생성자와 개수 ·
타입이 같고 첫 인자만 하위 타입이라, 오버로드 해석이 조용히 그쪽을 고른다. 나중에 `this(...)` 인자의 정적 타입이 바뀌면 경로가
소리 없이 바뀔 수 있다. 실행기를 맨 뒤로 옮겨 `(Hook…, Behaviors, LlmClient, Options, DefaultSubagentExecutor owned)` 로
모양을 갈랐다. 공통 본문 (b) 는 설계대로 6인자다. 리뷰 2회차의 제안.

### 8.3 ST-1.2 — ROUTINE root 의 "끝나지 않음" 규칙, 오프 경로 무할당, 여는 순서

- **"끝나지 않음".** §2.1 의 상태 규칙은 `RoutineResult` 의 성공 · 취소 · 실패만 다뤘다. `runRoutine` 이 던지면(이벤트
  발행의 RuntimeException, `Error`) root 는 `execute()` 의 `finally` 에서 `error("Routine run did not complete")` 로 닫힌다 —
  COMMAND · SUBAGENT 와 같은 규칙. 리뷰 2회차의 제안.
- **오프 경로 무할당.** §5 는 트레이싱 오프에서도 발화마다 `base` metadata 를 만들고 noop root 를 얻는 것을 받아들였다.
  tracer 스냅샷이 `Tracer.noop()` 싱글턴이면 공유 `RoutineTrace.OFF` 를 돌려줘 root · `base` · 입력 map · 시도 span 을 하나도
  만들지 않게 했다(TASK 의 "할당 회귀 없음"). `startRoot` 가 던지는 실제 tracer 는 설계대로 noop root 로 떨어진다. 리뷰 2회차의
  제안.
- **여는 순서.** `ExecutionId` 발급과 `RoutineTrace.open` 을 coordinator 등록 **앞**으로 옮겼다(`RoutineExecutor.java:238`).
  `open` 이 등록 뒤에 던지면 coordinator 가 in-flight 레지스트리에 남는다.
- **홀더 이름.** §3.5 의 `StepTrace` 는 `StepAttemptTrace` 가 되었고(시도 번호를 갖는다), `executeStep` 을 6인자로 두려고
  coordinator 와 `RoutineTrace` 를 묶는 `StepRun` 을 더했다. 셋 다 private 중첩 클래스다.
- **`principal` 속성.** "`task.getOwner()` 의 문자열 표현" 은 `<Principal.Type>:<id>`(예: `USER:alice`)로 정했다 —
  `Principal.toString()` 은 표시 이름(자유 텍스트)을 담아 상관키로 맞지 않는다.

### 8.4 ST-1.1 — `CommandTrace` 는 패키지 전용 최상위 클래스다

§3.2 는 포크의 `IterationTrace` 처럼 중첩 홀더를 권했다. `OrcaAgentExecutor` 가 이미 3,700줄이라
`at.aimon.core.agent.impl.orca.CommandTrace`(package-private) 파일로 뺐다. 공개 API 변화는 없다.

### 8.5 ST-5 — `feature` 값과 `SpanType.COMPACTION` javadoc

- 본문은 `feature=COMPACTION` 으로 적었지만 속성은 metadata 의 값을 그대로 옮기므로 `feature=compaction`
  (`LlmCallMetadata.Feature.COMPACTION`)이다.
- §3.1 은 상수 둘만 다뤘지만, `SpanType.COMPACTION` 의 javadoc 도 "예약 — 지금 만드는 곳이 없다" 로 고쳤다. §6.4 가
  `tracing.md` §5 의 같은 주장을 정정하기로 한 것과 짝이다.

### 8.6 ST-3 거부 span 의 `RunMode`

§3.4 는 "`RunMode.background(startTime)` 의 거부 변형" 과 "세 번째 상태 `RunMode.rejected()`" 를 함께 적었다. 후자
(`background=true`, `rejected=true`, `enqueuedAt=null`, 공유 싱글턴)로 구현했다.

### 8.7 §4.1 공개 API 목록에 더한 것

§4.1 은 시그니처와 `withTracer` 의 동작 변화만 적었다. 공개 SPI 에서 관찰되는 동작 변화 셋을 더한다(리뷰 2회차의 제안) —
트레이싱이 켜져 있을 때: 슬래시 명령 도구 컨텍스트의 `LLM_CALL_METADATA_KEY` 가 TURN 대신 COMMAND span 을 가리킨다; 예약 루틴
단계의 도구 컨텍스트에 그 키가 **새로 생긴다**; 커스텀 `ContextEngine` · `CompactionEngine` 구현이
`ContextRequest.getCallMetadata()` 에 값을 받는다(메인 턴 · 포크). 백로그 ST-6 의 닫힘 절도 같은 셋을 적는다.

### 8.8 닫힘 문구에 붙인 단서

- **ST-5.** 이 저장소가 조립하는 포크 실행기(`OrcaAgentExecutorFactory`, 매니저의 편의 생성자)는
  `ContextEngine.passthrough()` 라 포크가 compact 하지 않는다 — 포크 쪽 변경은 실제 `ContextEngine` 을 건 실행기에서만 보인다.
  백로그와 가이드에 적었다.
- **ST-2.** 실행기를 넘겨받은 매니저(`Builder#subagentExecutionManager`, `createDefaultSubagentExecutionManager` 를 재정의한
  공장 하위 클래스)는 의도대로 여전히 임베더가 건다. 리뷰가 선택지로 낸 진단 로그("매니저 tracer 는 있는데 실행기는 noop")는
  넣지 않았다 — 실행기의 tracer 를 읽는 공개 getter 가 하나 더 필요하다.

### 8.9 ST-5 — 기본 엔진의 in-place AUTO compaction 은 `callMetadata` 를 버리고 있었다

§2.1 ST-5 는 두 엔진이 `ContextRequest.callMetadata` 를 요약 호출로 이미 넘긴다고 확인했다(`DefaultContextEngine:338,549`,
`RollingContextEngine:568`). 그 줄들은 `compactNow` 와 view 모드만 덮는다. 기본 경로 — transcript 를 제자리에서 고치는 AUTO
compaction — 은 `CompactionGuard.maybeCompact(CompactionGuardRequest)` 를 거치는데, 그 요청에는 `callMetadata` 칸이 없었고
`DefaultCompactionGuard.invokeEngine` 은 그것 없이 `CompactionRequest` 를 만들었다. 그래서 실행기가 싣는 span 위치가 기본
엔진의 요약 호출에 닿지 않았다 — 실제 `DefaultContextEngine` 으로 돌린 포크 테스트가 찾았다. `CompactionGuardRequest` 에
`callMetadata`(공개 추가: `Builder#callMetadata`, `getCallMetadata()`)를 더하고, `DefaultCompactionGuard` 가 그것을
`CompactionRequest` 로 넘기며(위치 인자 진입점은 `null` — 무변경), `DefaultContextEngine.prepare` 가 채운다. 트레이싱 오프는
`callMetadata` 가 없으므로 그대로다.

### 8.10 열린 질문의 결말

| # | 결말 |
|---|---|
| OQ-1 | 잠정안대로 작업 단위(`routine:<ScheduledTaskId>`). 그룹이 끝없이 커지는 비용은 백로그 ST-11 에 적었다 |
| OQ-2 | 잠정안대로 하지 않았다. 백로그 **ST-7** 로 올렸다 |
| OQ-3 | 잠정안대로 열림 — 백로그 ST-1 의 남은 부분 |
| OQ-4 | 잠정안대로 받아들였다. COMPACTION span 작업과 함께 다시 본다 — 백로그 ST-5 의 남은 부분 |
| OQ-5 | 잠정안대로 `invoker=ROUTINE` 문자열 속성. `InvokerType` 확장을 요구하는 소비자가 생기기 전에는 결과가 없어 올리지 않았다 |
| OQ-6 | 범위 밖 — 백로그 **ST-9** 로 올렸다 |
| OQ-7 | 하지 않았다 — 백로그 **ST-10** 으로 올렸다 |
| OQ-8 | 범위 밖 — 백로그 **ST-11** 로 올렸다(OQ-1 의 비용과 함께) |
| OQ-9 | 잠정안대로 넣지 않았다 — 백로그 **ST-8** 로 올렸다. 가이드 "범위와 한계" 에도 적었다 |

그래서 백로그 제목은 §2.2 의 "열림 3 · 닫힘 3" 이 아니라 **"등록 항목 11건 (열림 8 · 닫힘 3)"** 이고, `docs/backlog/README.md`
의 색인 행도 같은 수로 고쳤다(리뷰 2회차가 지적한 대로 `scripts/check-backlog-registers.py` 가 둘을 비교한다).

### 8.11 PR 리뷰 뒤 고친 것

PR #256 의 독립 리뷰가 낸 지적을 반영했다. 모두 동작 정정이며 공개 시그니처는 바뀌지 않았다.

- **루틴 단계 TOOL span 의 상태.** §ST-1 의 상태 규칙("실행 신호가 울렸으면 `interrupted()`")이 던진 예외에만 적용되고 있었다.
  terminator 가 끊은 `THREAD_INTERRUPT` 단계, 또는 신호를 보고 오류 결과를 돌려준 협조적 도구는 INTERRUPTED root 아래
  ERROR TOOL span 을 남겼다. 이제 신호가 울린 뒤의 오류 결과도 INTERRUPTED 다(`StepAttemptTrace.recordResult`).
- **루틴 단계 TOOL span 의 입력.** §4.2 의 `input`(템플릿을 푼 파라미터)은 `$step.N.result` 치환과 `_steps` 로 앞 단계의
  출력을 담는다. 그 출력은 앞 단계 자신의 span 에서는 내용 캡처 정책에서만 남으므로, 요약 정책에서 다음 단계의 입력으로
  새어 나가면 안 된다. 그래서 입력은 `{stepIndex, attempt, toolParams}`(작성된 파라미터, 템플릿 그대로)이고 `input` 은 내용
  캡처 정책에서만 더한다. 실행기의 `toolUse.getInput()` 을 늘 남기는 규칙은 모델이 쓴 인자에 대한 것이라 여기에 그대로
  옮겨지지 않는다.
- **`queuedMs` 의 끝점.** §8.1 이 시작점을 옮긴 것과 같은 이유로 끝점도 옮겼다. worker 의 첫 문장에서 재므로
  `attachWorker` 와 `RUNNING` 전이 쓰기는 대기에 들어가지 않는다(`RunMode.startedAt`).
- **슬래시 명령 오프 경로.** `CommandTrace.open` 이 부모 span 이 있는지 먼저 보고, 없으면 명령을 파싱하지 않는다.
- **이름.** §3.5 의 `StepTrace` 는 코드에서 `StepAttemptTrace`(시도 단위)와 `RoutineTrace`(실행 단위) 둘로 나뉘었다.
