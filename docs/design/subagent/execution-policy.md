# 서브에이전트 실행 정책 (Subagent Execution Policy)

> Status: **IMPLEMENTED** — 코드가 공급하는 trusted 정책으로 포크의 iteration·누적 token·도구 호출 수·deadline 을
> 제한하고, 포크에 부모의 `ContextEngine`·compaction 힌트·요약 지시를 연결한다. 한계는 §7.
>
> 적용 대상: `aimon-core` — `at.aimon.core.subagent`(`SubagentExecutionPolicy`, `SubagentExecutionPolicyResolver`,
> `SubagentPolicyCapability`, `LingeringSubagentRun`, `DefaultSubagentExecutionManager`),
> `…subagent.execution`(`DefaultSubagentExecutor`, `SubagentExecutionRequest`, `SubagentExecutionResult`,
> `SubagentOutcomePayload`), `…subagent.internal`(`DeadlineScheduler`), `at.aimon.core.agent.budget`
> (`ExecutionDeadline`, `CompletionReason.TOOL_CALL_BUDGET_EXCEEDED`), `at.aimon.core.agent.context`·`…compact`
> (`compactionInstructions`), `at.aimon.core.tools.task.TaskTool`, `at.aimon.core.tools.ToolContextKeys`
> (`EXECUTION_DEADLINE`, `CONTEXT_ENGINE`), `OrcaAgentRuntimeFactory#withSubagentExecutionPolicies`.

---

## 1. 문제 — 포크에 예산을 담을 자리는 있었지만 아무도 채우지 않았다

`SubagentExecutionRequest` 에는 `ExecutionBudget` 자리가 있었고 `DefaultSubagentExecutor` 는 iteration 시작 전에
그 예산을 검사했다. 그러나 `DefaultSubagentExecutionManager` 는 요청을 만들 때 예산을 넣지 않았고, `Task` 에서
manager 까지 예산을 실어 나를 경로도 없었다. 포크를 묶는 것은 서브에이전트 정의의 `max-iterations` 하나뿐이었다.

나머지도 같은 모양이었다.

| 영역 | 이전 동작 |
|---|---|
| 벽시계 | iteration 경계에서만 검사 — 블로킹 LLM·도구 호출 중에는 아무것도 끝나지 않는다 |
| 도구 호출 수 | 축 자체가 없음 |
| `SHOULD_COMPACT` | `BudgetTracker` 가 내도 포크 루프가 무시 (`check() == STOP` 만 봄) |
| ContextEngine | `OrcaAgentExecutorFactory` 가 만든 포크 executor 는 `ContextEngine.passthrough()` 고정 |
| 결과 | 완료 사유·사용량이 Markdown 본문에만 있음 |

리뷰 verifier 한 번이 800초·LLM 26회를 쓴 trace 가 이 빈자리를 드러냈다. 필요한 것은 새 executor 가 아니라,
있는 경로에 **trusted 정책을 실어 나르고 실제로 강제하는 것**이었다.

## 2. 정책의 출처 — 코드만, 이름으로

`SubagentExecutionPolicy` 는 불변 값이다. 각 한도는 선택이며 서로 독립이다.

| 필드 | 강제 방식 | 완료 사유 |
|---|---|---|
| `maxIterations` | 포크 `ExecutionBudget` → iteration 전 STOP | `MAX_ITERATIONS` |
| `maxTokens` | 포크 `ExecutionBudget` → iteration 전 STOP | `TOKEN_BUDGET_EXCEEDED` |
| `maxToolCalls` | dispatch 전 admission (§4) | `TOOL_CALL_BUDGET_EXCEEDED` (신규) |
| `maxWallClock` | launch 시점에 고정한 `ExecutionDeadline` (§5) | `WALL_CLOCK_EXCEEDED` |
| `compactionTokenThreshold` | `SHOULD_COMPACT` → `ContextRequest.budgetForced` | — (힌트) |
| `contextTargetTokens` | 직전 호출 입력 ≥ target → `budgetForced` | — (힌트) |
| `compactionInstructions` | 모든 AUTO compaction 요약의 custom instructions | — |
| `inheritContextEngine` | 부모의 `ContextEngine` 으로 실행 | — |
| `exitGracePeriod` | 정지 요청 후 exit 를 기다리는 시간 (기본 5초) | — |

정책이 포크에 닿는 길은 하나다 — 스폰 코드가 `SubagentLaunchContext.executionPolicy` 에 싣는다. `TaskTool` 은
`SubagentExecutionPolicyResolver` 에게 **모델이 고른 서브에이전트 이름**으로 묻는다. 이름은 모델이 고르지만 그
이름의 정책은 코드가 정한다. `Task` 입력 스키마에는 정책 필드가 없고(`additionalProperties: false`), 스키마
게이트가 `WARN` 모드로 미지 키를 통과시켜도 `TaskTool` 은 그 키를 읽지 않는다. 서브에이전트 정의 파일
(`agents/*.md`, 저장소의 instructions)도 정책에 닿지 않는다.

배선 지점:

- resolver 를 받는 `TaskTool` 생성자 — 자체 Task 도구를 만드는 provider 용.
- `OrcaAgentRuntimeFactory#withSubagentExecutionPolicies(resolver)` → `OrcaProviderDependencies` →
  `OrcaToolProviderContext#getSubagentExecutionPolicyResolver()` → `OrcaSubagentToolProvider` 가 등록하는 Task.

중첩 포크도 같은 `TaskTool` 인스턴스로 띄우므로 깊이에 관계없이 같은 resolver 가 적용된다.

## 3. 강제 전에 판정 — 조용히 무제한으로 낮추지 않는다

한도를 적용하지 못하는 것은 한도 무한대와 같다. 그래서 정책은 **전부 강제되거나 실행되지 않는다**.

- `SubagentPolicyCapability` — 정책이 쓰는 축 (`getCapabilities()`).
- `SubagentExecutor#supportedPolicyCapabilities()` — executor 가 강제하는 축. 기본값은 빈 집합이므로 정책 이전에
  작성된 executor 는 모든 정책을 거부한다. `DefaultSubagentExecutor` 는 전부를 강제하되, 자기 engine 이
  `ContextEngine.passthrough()` 이면(`OrcaAgentExecutorFactory` 가 만드는 모양) **context 축**
  (`SubagentPolicyCapability.contextAxes()` — 두 compaction 힌트와 요약 지시)을 뺀다. 아무 일도 하지 않을 설정을
  받아들이지 않기 위해서다. 정책이 compact 하는 engine 을 상속하면(`inheritContextEngine` + passthrough 아닌 부모
  engine) context 축은 그 engine 이 채운다 — `SubagentExecutionPolicy#unsupportedBy(supported, inherited)`.
- `SubagentExecutionManager#supportedPolicyCapabilities()` — `TaskTool` 이 spawn **전에** 비교한다. 빠진 축이
  있으면 `ToolResult.error` 를 돌려주고 아무것도 띄우지 않는다. 모델에게는 "이 런타임에서 띄울 수 없다" 만
  알리고, 정책 이름과 빠진 축은 로그에 남긴다(운영자의 설정 문제이므로).
- manager 가 실행 시점에 한 번 더 판정한다: 빠진 축, code behavior 로 등록된 이름(behavior 는 어떤 한도도 강제하지
  않는다), 부모 engine 이 없는데 `inheritContextEngine` 을 요구한 경우 — 모두 `SubagentSpawnException` 으로
  실패 결과가 되고 executor 는 호출되지 않는다.
- 정책 빌더 자체도 `compactionTokenThreshold ≥ maxTokens`(힌트가 영원히 관측되지 않음), 0 이하 값을 `build()`
  전에 거부한다.

정책 없는 launch 는 이전과 바이트 단위로 같은 경로를 탄다 — 호출 스레드, 예산 없음, deadline 없음.

## 4. 도구 호출 수

`DefaultSubagentExecutor` 는 응답의 tool use 를 dispatcher 에 넘기기 **전에** 남은 허용 수만큼만 admit 한다.

- 병렬 batch 의 각 호출이 1회다. 응답에 나열된 순서대로 앞의 것부터 실행된다.
- 넘치는 호출은 dispatcher 에 가지 않는다 — hook·권한 질의·실행 모두 없다. 각자 `Not run: ...` 오류
  `ToolUseResult` 로 답해 transcript 의 tool_use/tool_result 짝을 맞추며, **실행 수에 세지 않는다**.
- 거부가 한 번이라도 있었으면 그 iteration 꼬리(취소 검사 다음, stall guard 전)에서
  `TOOL_CALL_BUDGET_EXCEEDED` 로 끝난다 — 다음 LLM 호출을 쓰지 않는다.
- 정확히 한도만큼 쓴 포크는 다음 호출에서 답을 낼 수 있다. 그 호출이 또 도구를 요구하면 전부 거부되고 끝난다.
- `max_tokens` 로 잘린 응답의 호출은 원래대로 거부되며 역시 세지 않는다.
- **token 을 넘긴 응답의 호출도 실행하지 않는다.** 응답의 usage 는 응답이 온 뒤에야 알 수 있으므로, 정책 포크는
  도구를 넘기기 직전에 예산을 다시 본다. 이미 STOP 이면 그 batch 전부를 `Not run` 으로 답하고, 다음 iteration
  경계가 `TOKEN_BUDGET_EXCEEDED` 로 끝낸다(LLM 호출 없이).
- `SubagentExecutionResult#getToolCallCount()` 는 **도구 파이프라인에 넘긴 수**다 — 예산이 차감되는 수와 같다.
  파이프라인이 그 뒤에 거절한 호출(권한 거부, PreTool 차단)도 센다. 포크가 그 호출에 한도를 썼기 때문이다.

## 5. Deadline 과 실제 종료

### 5.1 언제부터, 어떤 시계로

`ExecutionDeadline` 은 `System.nanoTime()` 기반 monotonic 값이다. manager 가 **launch 진입 시점**(SubagentStart
hook 전)에 고정한다. 백그라운드 태스크는 worker 를 기다리는 큐 시간도 포함한다. deadline 은 정책의
`maxWallClock` 과 스폰한 실행의 deadline(`ToolContextKeys.EXECUTION_DEADLINE`, 정책 포크가 게시) 중 이른 쪽이다.
자식은 부모가 남긴 시간보다 많이 받지 못한다.

`ExecutionBudget.maxWallClockDuration` (`BudgetTracker` 가 wall `Clock` 으로 tracker 생성 시점부터 잼)은 그대로
두었다. 정책의 벽시계는 그 축을 쓰지 않는다 — 시작점이 다르고(launch vs tracker 생성) 시계가 다르다.

### 5.2 deadline 이 하는 일

포크 executor 는 `DeadlineScheduler` 로 남은 시간 뒤에 **자기 coordinator** 를 `BUDGET_EXCEEDED` 로 trip 한다.
signal 이 trip 되면 진행 중 LLM 호출의 abort lever(`SignalBackedLlmCancellation`)와 실행 중 도구의 terminator 가
발화한다. 다음 iteration 을 기다리지 않는다.

- **사유 보존.** signal 은 처음 기록한 사유를 유지한다. 그래서 deadline 이 먼저면 이어서 cascade 되는
  `PARENT_CANCELLED` 가 덮어쓰지 못하고, 부모 취소가 먼저면 뒤늦은 deadline 이 덮어쓰지 못한다. 포크는 signal 의
  사유가 `BUDGET_EXCEEDED` 일 때 `INTERRUPTED` 대신 `WALL_CLOCK_EXCEEDED` 를 보고한다. 같은 판정이 두 경우를 더
  덮는다.
  - signal 없이 thread interrupt 만으로 멈췄고 deadline 이 지났다 — §5.3 의 watchdog interrupt 가 executor timer
    보다 먼저 닿거나, 그 interrupt 를 HTTP client 가 자기 예외로 바꿔 던진 경우. `LlmClientException`·catch-all·
    compaction BLOCK 경로도 deadline 이 지났으면 deadline 으로 보고한다.
  - 사유가 `PARENT_CANCELLED` 이고 부모 signal 의 사유가 `BUDGET_EXCEEDED` 이며 자기 deadline 도 지났다 — 부모
    deadline 을 물려받은 자식은 같은 시각을 가지고, 대개 부모 timer 가 먼저 발화해 cascade 된다.
- **시작 전 만료.** executor 진입 시 이미 지났으면 환경 해석·OnStart hook·LLM 호출 없이
  `WALL_CLOCK_EXCEEDED` 로 끝난다 ("…before it started"). 큐에서 만료된 백그라운드 태스크가 이 경로다.
- **늦은 interrupt 없음.** `DeadlineScheduler.Handle#close()` 와 action 은 같은 lock 을 잡고, 닫힌 handle 의
  action 은 아무것도 하지 않는다. timer 스레드(JVM 에 하나)는 action 을 넘기기만 하고 action 은 daemon pool 에서
  돈다 — 느린 terminator 하나가 다른 포크의 deadline 을 늦추지 않도록. executor 는 반환 전에 handle 을 닫으므로 그 뒤에는 trip 이 없다. deadline 이
  발화했고 부모가 취소되지 않았다면 반환 전에 자기 스레드의 interrupt flag 를 지운다 — 재사용 worker 가 다음
  작업에서 그것을 받지 않도록.
- **compaction 도 포함.** compaction 은 포크의 cancellation signal 을 받으므로 deadline·취소에 걸린다.

### 5.3 취소 요청 ≠ 종료 — foreground watchdog

interrupt 를 무시하는 도구는 JVM 안에서 안전하게 멈출 수 없다(`Thread.stop` 은 쓰지 않는다). 그래서 deadline 이
있는 **foreground** 포크는 전용 스레드(`subagent-fg-<taskId>`)에서 돌고, 호출 스레드는 다음 중 먼저인 것까지
기다린다.

1. 포크가 반환 → 그 결과.
2. deadline 경과 또는 부모 signal trip → grace 의 **앞 절반은 trip 된 signal 만으로** 기다린다. 이미 정리 중인
   포크(OnStop hook 실행 중)를 불필요한 interrupt 로 끊지 않기 위해서다. 그때까지 반환하지 않으면 포크 스레드를
   interrupt 하고(전용 스레드라 남의 작업에 떨어질 수 없다) 나머지 절반을 기다린다. 반환하면 그 결과.
3. 그래도 반환하지 않으면 **exit 미확인 결과**: `isExitConfirmed() == false`, 사유는 deadline 이면
   `WALL_CLOCK_EXCEEDED`, 취소면 `INTERRUPTED`. 포크는 `SubagentExecutionManager#lingeringRuns()` 에
   `LingeringSubagentRun` 으로 올라가고, 실제로 반환하면 내려간다. 늦게 나온 결과는 로그만 남기고 버린다.

호출 스레드가 기다리는 중 interrupt 되면(Task 도구의 terminator) 그것은 정지 요청으로 취급하고 grace 를 마저
기다린 뒤 interrupt 를 복원해 돌려준다. SubagentStop hook 은 대기가 만든 결과로 **한 번** 발화한다.

deadline 이 있는 **background** 태스크는 worker 가 이미 전용이므로 스레드를 바꾸지 않는다. deadline + grace 가
지나도 future 가 끝나지 않았으면 lingering 으로 올린다. 그동안 task store 상태는 `RUNNING`, worker 는 점유된
채로 남는다 — 둘 다 사실이고, slot 이 재사용되지 않는 이유이기도 하다.

호출자에게 주는 규칙: exit 미확인 결과는 완료가 아니다. 같은 작업을 다시 띄우기 전에 `lingeringRuns()` 를 본다.
core 는 재실행을 막지 않는다 — 무엇이 "같은 작업" 인지는 호출자만 안다. 호출자는 결과 payload 의 `taskId` 를
자기 작업 키(finding 등)와 함께 보관하고, 그 `taskId` 가 `lingeringRuns()` 에 남아 있는 동안 다시 띄우지 않는다.
`SubagentExecutionManager` 는 `OrcaToolProviderContext#getSubagentExecutionManager()` 로 얻는다.

grace 는 deadline 이 있는 포크에만 적용된다. deadline 이 없는 정책 포크(자기 `maxWallClock` 도, 부모 deadline 도
없음)는 이전처럼 호출 스레드에서 돌고 반환할 때까지 기다린다. deadline 을 가진 백그라운드 태스크는 deadline +
grace 시점에 아직 큐에 있어도 lingering 으로 올라간다 — 그 태스크는 worker 를 얻는 즉시 "시작 전 만료" 로 끝나고
목록에서 내려간다.

## 6. Context 관리

### 6.1 부모 engine 상속

`OrcaAgentExecutor` 는 tool context 에 런타임의 `ContextEngine` 을 `ToolContextKeys.CONTEXT_ENGINE` 으로 게시하고,
포크는 자기가 쓰는 engine 을 같은 키로 다시 게시한다. `TaskTool` 이 launch context 의 `parentContextEngine` 으로
읽고, 정책이 `inheritContextEngine` 일 때만 manager 가 `SubagentExecutionContext.contextEngine` 으로 넘긴다.
executor 는 그 engine 을 자기 필드 대신 쓴다. 정책 없는 포크는 전과 같이 executor 자기 engine(Orca 조립에서는
passthrough)이다 — 기존 동작이 바뀌지 않는다.

engine 인스턴스는 공유하지만 상태는 섞이지 않는다. `DefaultCompactionGuard` 의 lock 과 실패 카운터(circuit
breaker)는 transcript 의 `SessionId` 로 키잉되고, 포크의 transcript label 은 포크마다 고유한 실행 id 다. 그래서
여러 포크가 동시에 compact 해도 서로의 lock·실패 상태를 보지 않으며, 한 포크에서 요약이 반복 실패하면 그 포크의
breaker 만 열린다 — 같은 transcript 를 무한히 compact 하지 않는다.

### 6.2 힌트 — `budgetForced`

두 힌트 모두 다음 `ContextRequest` 의 `budgetForced` 를 켤 뿐이다. engine 은 그것을 "auto-compact 문턱 대신 낮은
warning 문턱을 써라" 로 읽는다(`DefaultCompactionGuard`). 무조건 compact 하라는 뜻이 아니다.

- `compactionTokenThreshold` — `BudgetTracker.check()` 가 `SHOULD_COMPACT` 를 낼 때. STOP 이 항상 우선이다.
- `contextTargetTokens` — 직전 LLM 호출의 provider 보고 입력(`promptTokens`)이 target 이상일 때. 모델 전체
  context window 와 별개의 축이다.

COMPACTION span 에는 강제된 경우 `budgetForced=true` 속성이 붙는다.

### 6.3 근거 보존 — 요약 지시

`ContextRequest.compactionInstructions` → (in-place) `CompactionGuardRequest.compactionInstructions` →
`CompactionRequest.customInstructions`, (view mode) `SummaryRequest.customInstructions`. `/compact <지시>` 가 수동
compaction 에 주는 그 자리다. 정책의 `compactionInstructions` 가 포크의 **모든 AUTO compaction** 에 실린다.

호출자(ops)가 이 지시로 보존을 요구할 대상: finding ID·검증 질문·가정과 반증 조건, repository 와 pinned SHA,
path·range·blob SHA, 실제로 본 fragment 범위·hash·continuation cursor·미완료 range, reviewed/unfinished diff
unit, limitations 와 UNVERIFIED 결과, 원문을 다시 읽을 식별 정보.

## 7. 보장 범위와 한계

| 항목 | 보장 | 한계 |
|---|---|---|
| token | 한도를 넘긴 응답의 도구 batch 도, 다음 LLM 호출도 시작하지 않는다 (`TOKEN_BUDGET_EXCEEDED`) | 사용량은 응답 후에만 알 수 있어 **한 호출만큼 overshoot** 할 수 있다 |
| compaction 비용 | deadline·취소에 걸린다; `COMPACTION` feature 로 별도 집계 | 요약 호출의 token 은 포크의 `maxTokens` 에 **포함되지 않는다** |
| 도구 호출 | 파이프라인에 넘긴 수가 한도를 넘지 않는다 | 어느 호출을 실행할지는 응답 순서다; 파이프라인이 거절한 호출도 센다 |
| deadline | 기준은 launch, monotonic; LLM abort·terminator 발화 | 협조하지 않는 I/O 는 멈추지 않는다 — §5.3 의 exit 미확인으로 드러날 뿐이다 |
| 부모/자식 | 자식 deadline ≤ 부모 deadline (정책 포크가 부모일 때) | 세션 **턴**은 deadline 을 게시하지 않는다 — 턴의 벽시계는 iteration 경계 검사뿐이라 고정점이 없다 |
| 사용량 합산 | 자식의 provider 호출은 자식 결과·SUBAGENT span 에 한 번 | 부모 `BudgetTracker` 에 **더하지 않는다** (중복 없음, 대신 부모 token 예산이 자식을 포함하지 않음). 실행 트리 전체 상한은 공유 `QuotaGuard` 의 몫이다 |
| 요약 | 지시가 모든 AUTO 요약에 실린다 | 자연어 요약은 원문이나 COMPLETE proof 를 대체하지 않는다 — 원문이 사라졌으면 pinned 도구로 다시 읽어야 한다 |
| context target | warning 문턱으로 낮추는 신호 | target 이 모델의 warning 문턱보다 낮으면 그 사이 구간에서는 compact 되지 않는다 |
| lingering | 노드 로컬 목록, 실제 exit 시 제거 | 프로세스 재시작을 넘지 않는다 (JVM 스레드에 대한 사실이므로). 재실행 차단은 호출자의 몫이다 (§5.3) |
| exit 미확인 결과의 사용량 | — | 포크가 반환하기 전이므로 iteration·token·도구 호출 수가 **0** 으로 보고된다. 실제 사용량은 포크가 끝날 때 SUBAGENT span 에 남는다 |
| 상속 engine | passthrough 가 아닌 engine 은 context 축을 지킨다고 본다 | 제3자 engine 이 `budgetForced`·`compactionInstructions` 를 무시하면 그 축은 강제되지 않는다 — `ContextRequest` 계약의 일부다 |
| 거부 메시지 | 모델에게는 "이 런타임에서 실행 정책으로 띄울 수 없다" 만 | 정책 이름·빠진 축은 WARN 로그에만 남는다 |
| 스레드 | — | deadline 포크는 새 스레드에서 돌므로 spawner 의 `ThreadLocal`(예: ambient LLM 태그)을 물려받지 않는다. 명시적으로 전달되는 metadata·signal·principal 은 그대로다. 그 스레드에서 던져진 `Error` 는 전파되지 않고 실패 결과가 된다 |
| 정책 없는 부모 deadline | — | 정책 없는 포크에는 부모 deadline 도 적용하지 않는다 (기존 동작 유지) |

## 8. 결과 계약

`SubagentExecutionResult` 에 `getToolCallCount()`, `isExitConfirmed()` 가 더해졌다. `CompletionReason` 에
`TOOL_CALL_BUDGET_EXCEEDED` 가 더해졌다(구 버전 peer 는 `fromWireName` 으로 `ERROR` 로 읽는다). SUBAGENT span 은
이 사유를 오류가 아니라 bounded stop 으로 기록한다.

`TaskTool` 은 텍스트 옆에 `SubagentOutcomePayload` 를 `ToolResult` render payload 로 붙인다 — 모델과 영속
transcript 에는 가지 않는다. 키: `kind=subagent-outcome`, `taskId`, `subagent`, `background`,
`completionReason`, `success`, `exitConfirmed`, `truncated`, `iterations`, `toolCalls`, `totalTokens`,
`policyRole`. **Tool 전송 성공(`ToolResult.isSuccess()`)은 포크 완료가 아니다** — `completionReason == COMPLETED`
이고 `exitConfirmed == true` 일 때만 완료다. 텍스트에는 미완료일 때 `Completion reason:` 줄, exit 미확인일 때
`Exit: UNCONFIRMED` 줄이 결과 뒤에 붙는다. `COMPLETED` 결과의 텍스트는 이전과 같다.

| 결과 의미 | 구조화 결과 |
|---|---|
| 정상 완료 | `COMPLETED`, `exitConfirmed=true` |
| 예산 소진 | `MAX_ITERATIONS` / `TOKEN_BUDGET_EXCEEDED` / `TOOL_CALL_BUDGET_EXCEEDED` / `WALL_CLOCK_EXCEEDED` |
| 부모 취소 | `INTERRUPTED` |
| 실패 | `ERROR`, `BLOCKED` |
| 출력 잘림 | `TRUNCATED` (`success=true`, `truncated=true`) |
| exit 미확인 | `exitConfirmed=false` + `lingeringRuns()` 항목 |

결과 공개 직전의 권한 재확인(OU·capability·lease)은 core 의 일이 아니다 — wrapper 의 경계로 남는다.

## 9. 검증

| 시나리오 | 테스트 |
|---|---|
| Task → launch 로 정책·부모 deadline·engine 전달, LLM 입력으로 상한 override 불가 | `TaskToolExecutionPolicyTest` |
| 미지원 capability 사전 거부, code behavior·부모 engine 부재 거부 | `TaskToolExecutionPolicyTest`, `DefaultSubagentExecutionManagerPolicyTest` |
| launch → request 로 같은 정책 인스턴스·예산·deadline, 부모 deadline clamp | `DefaultSubagentExecutionManagerPolicyTest` |
| iteration/token/도구 호출 한도, 병렬 batch 부분 실행 | `DefaultSubagentExecutorPolicyTest` |
| 시작 전 만료(큐 대기 포함), 블로킹 LLM 중 deadline, 부모 취소 선행 시 사유 보존 | `DefaultSubagentExecutorPolicyTest`, `DefaultSubagentExecutionManagerPolicyTest` |
| interrupt 무시 포크의 exit 미확인·lingering 등재·해제, Stop hook 1회 | `DefaultSubagentExecutionManagerPolicyTest` |
| `budgetForced`(threshold·target), 정책 없을 때 미설정, 요약 지시 전달 | `DefaultSubagentExecutorPolicyTest`, `DefaultCompactionGuardObserverTest` |
| 닫힌 timer 는 늦게 발화하지 않는다 | `DeadlineSchedulerTest` |
| 실제 Task → `DefaultSubagentExecutionManager` → `DefaultSubagentExecutor` 경로의 도구 한도와 구조화 결과 | `TaskToolExecutionPolicyTest.endToEndThroughTheRealManagerAndExecutor` |
| deadline 선행 후 부모 취소, 부모 deadline cascade, 블로킹 도구 interrupt, token 초과 batch 미실행 | `DefaultSubagentExecutorPolicyTest` |
| passthrough engine 에서 context 축 거부, compact 하는 engine 상속 시 수락 | `DefaultSubagentExecutionManagerPolicyTest` |

외부 LLM 호출 없이 fake client 와 latch 를 쓰고, deadline 판정은 조절 가능한 nano clock 으로 검증한다. 다만
timer 가 실제로 발화하는 경로(블로킹 호출 중단, watchdog, 큐 만료)는 `DeadlineScheduler` 가 시계를 주입받지 않으므로
50–200 ms 의 실제 deadline 을 쓴다. 그 테스트들도 경과 시간이 아니라 latch·사유·exit 여부를 assert 한다.
