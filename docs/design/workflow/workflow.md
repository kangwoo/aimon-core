# Workflow — 서브에이전트 오케스트레이션 설계

> **한 줄 요약**: 결정론적 스크립트가 다수의 LLM 서브에이전트를 팬아웃/조인하는 오케스트레이션
> 서브시스템. 제어흐름은 **순수 Java 함수형 DSL**(`WorkflowScript`)로 표현하고, 실행 기층은
> **인라인 서브에이전트 실행 프리미티브**(`SubagentExecutionManager.execute(env, Subagent, goal)`)
> 위에 얹는다.
>
> 적용 대상: `aimon-core` (`at.aimon.core.workflow`), `aimon-workflow-graaljs`
> Status: **구현 완료** — 아래 5개 능력 전부 main 에 반영됨

이 문서는 워크플로 서브시스템 전체의 설계 근거를 하나로 담는다. 원래는 다섯 개의 반복(Phase 1~5)으로
나뉘어 각각 별도 문서였으나, 전부 구현이 끝난 지금은 **능력별**로 읽는 편이 유용하다. 반복 순서는
§10 에 이력으로만 남긴다.

| 능력 | 내용 |
|---|---|
| **프리미티브** | `WorkflowContext` 의 `agent`/`parallel`/`pipeline`/`phase`/`log` + foreground 러너 + 제네릭 `BoundedFanoutDispatcher` |
| **구조화 출력·예산** | `AgentTask.resultSchema` → `AgentStepResult.structured()`, 서브에이전트 종료사유 채널, run-scoped `WorkflowBudget` |
| **백그라운드·resume** | `RunId`/`WorkflowRun`/`RunHandle`, 공용 팬아웃 풀, 구조적 스텝키 기반 `StepResultCache`, 협조적 취소 |
| **격리·중첩·패턴** | worktree 아날로그 파일 격리, 진짜 중첩 병렬, N-스테이지 `Pipeline` 빌더, `WorkflowPatterns`, 구조-prefix 가드 |
| **JS 프론트엔드** | `aimon-workflow-graaljs` — JS 리터럴 스크립트가 **동일 프리미티브**를 호출 |

---

## 1. 개요

### 1.1 목적

한 번의 요청으로 **여러 서브에이전트에게 추론 작업을 팬아웃**하고, 그 산출물을 결정론적 코드로
조율하는 실행 모델을 제공한다. 예: 리뷰 차원별 병렬 조사 → 각 발견을 adversarial 검증, 문서를
여러 관점으로 동시 요약 후 합성, 대량 항목을 아이템별 파이프라인으로 변환.

### 1.2 배경

- AIMON 은 이미 `TaskTool` 로 **런타임 LLM-driven** 서브에이전트 스폰을 지원한다. 그러나 이는 메인
  에이전트가 매 턴 판단해 부르는 것이지 **결정론적 스크립트**가 아니다.
- `SubagentExecutionManager.execute(SubagentExecutionEnvironment, Subagent, String)` — 레지스트리
  등록 없이 코드로 정의한 `Subagent` 를 한 줄로 실행하는 프리미티브 — 가 `agent()` 의 실행 단위다.
- 병렬 실행에 필요한 바운디드 풀·2-tier bound·입력순 재조립 패턴은 이미
  `DefaultParallelToolDispatcher` 에 존재했다(단, `ToolUse`/`ToolUseResult` 에 결합 → §6.2).

### 1.3 핵심 설계 원칙

1. **subagent SPI 위에만 의존** — `at.aimon.core.subagent.*`(중립 SPI)에만 의존하고
   `at.aimon.core.agent.impl.orca..` 는 임포트하지 않는다 (ArchUnit).
2. **재사용 우선, 결합 회피** — 병렬 기계는 검증된 패턴을 **제네릭 복사**로 흡수하되(§6.2),
   tool-domain 로직(`shouldParallelize` 게이트)은 가져오지 않는다.
3. **스코프 안전** — 러너는 application-scoped 컴포넌트를 **주입받아 빌리며 소유하지 않는다**.
   자기가 생성한 것(팬아웃 풀, run-hosting 풀)만 close 한다 (§7.1).
4. **불변 + 빌더 + 생성자 주입** — 값객체는 `final` + 빌더, 의존성은 `Objects.requireNonNull` 생성자
   주입. 단 **결과 래퍼(`AgentStepResult`)는 정적 팩토리**를 쓴다 —
   `SubagentExecutionResult`(success/failure)·`ToolResult`(success/error) 선례.
5. **never-throw 계승** — leaf `agent()` 는 실행 프리미티브를 그대로 쓰므로 실행 실패를 던지지 않고
   실패 결과로 반환한다. 예외는 오직 **run-fatal 제어 예외**(`WorkflowBudgetExceededException`)뿐이다.
6. **코어는 순수 Java** — GraalJS 는 별도 모듈이며 코어에 `org.graalvm` 컴파일 의존성을 들이지 않는다.

### 1.4 용어

| 용어 | 의미 |
|---|---|
| WorkflowScript | 저자가 제어흐름(루프/조건/팬아웃)을 인코딩하는 순수 Java 함수 `T run(WorkflowContext)` |
| WorkflowContext | 스크립트가 받는 실행 컨텍스트. `agent`/`parallel`/`pipeline`/`phase`/`log` 노출 |
| AgentTask | `agent()` 1콜 기술 — 인라인 `Subagent` + goal (+ label/phase/schema/isolate) |
| AgentStepResult | 한 서브에이전트 실행의 결과 래퍼 (`SubagentExecutionResult` + label/phase + structured) |
| BoundedFanoutDispatcher | tool-domain 에서 분리한 제네릭 `<I,R>` 바운디드 팬아웃/조인 엔진 |
| Run | 한 `WorkflowScript` 실행. run-scoped 상태(예산 카운터, 구조 경로, 취소 coordinator) 보유 |
| leaf | 한 번의 `manager.execute(...)` — 실제 LLM 이 도는 유일한 지점 |

---

## 2. 아키텍처

### 2.1 패키지 배치

```
at.aimon.core.workflow/                # SPI + 값객체 (중립 표면)
├── WorkflowRunner / WorkflowRunners / WorkflowRunnerOptions
├── WorkflowContext / WorkflowScript
├── AgentTask / AgentStepResult
├── WorkflowConcurrencyConfig / WorkflowBackgroundConfig / WorkflowBudget
├── WorkflowEventSink
├── RunId / WorkflowRun / WorkflowRunState / RunQuery / RunHandle
├── RunStore / WorkflowRunController
├── StepResultCache / StepKey / StepOutcome
├── Pipeline / Stage                   # N-스테이지 타입-보존 빌더
├── WorkflowPatterns / Verdict / JudgedResult
├── WorktreeEnvironmentFactory         # 격리 seam (호출자 주입)
├── exception/{WorkflowException, WorkflowBudgetExceededException}
└── impl/
    ├── DefaultWorkflowRunner / DefaultWorkflowContext / ContextExecutionOptions
    ├── BoundedFanoutDispatcher / LeafConcurrencyLimiter
    ├── InMemoryRunStore / RunningRunRegistry / RunControl / ResumeBinding
    ├── InMemoryStepResultCache / VfsStepResultCache / ScopedStepResultCache / StepOutcomeCodec
    └── StructuredOutputSupport

at.aimon.workflow.graaljs/             # 별도 모듈 aimon-workflow-graaljs
├── GraalJsWorkflowScript              # 유일 seam: JS 소스 → WorkflowScript<String>
├── JsContextFactory / JsSandboxConfig / GraalJsEngineHolder / CancellationWatchdog
├── WorkflowBindings / AgentTaskMarshaller / JsResultMarshaller / JsMarshalling
├── AgentResultView / SubagentResolver / InlineSubagentResolver / RunFatalCapture
└── GraalJsWorkflowTool
```

### 2.2 스코프 정합

- **Application** — `DefaultWorkflowRunner`. 부트스트랩에서 1회 생성, 앱 shutdown 시 close.
  공용 팬아웃 풀과 run-hosting 풀 **둘 다** 러너가 소유하며 `close()` 가 닫는다.
- **Agent** — 러너가 주입받는 base `SubagentExecutionEnvironment` 는 특정 `(Agent, discriminator)` 의
  agent-scoped 자원(`ToolRegistry`/`HookRegistry`/context)을 참조한다. **빌리며 close 하지 않는다.**
- **Run** — `run()` / `runInBackground()` 1회당 별도 컨텍스트. 예산 카운터, 구조 경로 스택,
  취소 coordinator, 파생 env 가 여기에 격리된다. run 은 **세션이 아니다** — `SessionId` 를 갖지 않고
  실행 정체성은 `RunId` 다.

> **pool 공유 ≠ run-state 공유.** 공용 팬아웃 풀을 공유해도 `agentCount`/`tokensSpent`/`costSpent`/
> 구조 경로는 run 마다 별도 `DefaultWorkflowContext` 에 격리된다.

### 2.3 의존 방향 (ArchUnit)

오케스트레이션은 base env 를 opaque 하게 pass-through 하므로 실제 임포트는 **subagent SPI 와 값 타입**
뿐이다: `SubagentExecutionManager`, `SubagentExecutionEnvironment`, `Subagent`,
`SubagentExecutionResult`, `CompletionReason`/`ExecutionMetadata`/`TokenUsage`.

> **강제 방식 주의** — `at.aimon.core.subagent` 도메인은 `.impl` 분리 관례를 따르지 않아
> `DefaultSubagentExecutionManager`·`DefaultSubagentExecutor` 가 SPI 와 **같은 패키지에 공존**한다.
> 따라서 "`subagent..impl` 금지" 는 매칭 대상이 없는 vacuous 규칙이라 쓸 수 없다. SPI-only 는
> **타입 단위 allow-list** 로 강제한다 (`PackageDependencyArchitectureTest`). `agent.impl.orca..` 유출
> 금지는 기존 `agentImplMustNotLeakOutsideAgentTree` 가 이미 커버한다.

---

## 3. 프리미티브

### 3.1 `WorkflowContext` — 5개 표면

```java
public interface WorkflowContext {

    /** 서브에이전트 1개를 끝까지 실행. 실행 실패는 던지지 않고 실패 결과로 반환. */
    AgentStepResult agent(AgentTask task);
    default AgentStepResult agent(Subagent subagent, String goal);

    /** 배리어 팬아웃: 모든 thunk 를 동시 실행하고 전부 완료까지 대기. 반환은 입력 순서. */
    <R> List<R> parallel(List<Supplier<R>> thunks);

    /** 아이템별 2-스테이지 파이프라인: 스테이지 간 배리어 없음. 반환은 입력 순서. */
    <I, A, R> List<R> pipeline(List<I> items, Function<I, A> stage1, BiFunction<A, I, R> stage2);

    void phase(String title);
    void log(String message);
}
```

`parallel` 의 thunk 가 **예상 밖 throw** 를 하면 해당 위치가 `null` 이 되고 배치는 계속된다.
leaf `agent()` 는 never-throw 이므로 실패는 대개 실패 `AgentStepResult`(non-null)로 돌아오고 null 은
오직 예상 밖 throw 경로에서만 생긴다 — **후속 stage/소비자는 null-safe 해야 한다.** 단
`WorkflowBudgetExceededException` 은 격리하지 않고 dispatch 밖으로 재-throw 되어 run 을 중단한다(§6.3).

> **왜 thunk 기반 `parallel` + 2-스테이지 `pipeline` 인가**
> `parallel(List<Supplier<R>>)` 은 저자가 `tasks.stream().map(t -> () -> ctx.agent(t)).toList()` 처럼
> 임의 작업을 팬아웃할 수 있어 가장 일반적이다. 단일 `R` 제약은 에이전트 팬아웃이 구조적으로
> 동종(`agent()` → 항상 `AgentStepResult`)이라 실무 제약이 아니다.
> `pipeline` 은 Java 제네릭으로 임의 N-스테이지를 타입안전하게 표현하기 어려워, 실사용의 대부분을
> 커버하는 **2-스테이지 타입 오버로드**를 SPI 표면으로 삼는다. N-스테이지는 SPI 를 늘리지 않고
> `Pipeline`/`Stage` **정적 빌더**로 desugar 한다(§5.1).

### 3.2 `BoundedFanoutDispatcher` — 팬아웃/조인 엔진

`DefaultParallelToolDispatcher` 에서 **type-agnostic 한 4조각을 리프트**한 제네릭 복사본이다:

1. lazy·guarded·daemon 풀 초기화(volatile 필드 + `poolLock` 이중검사 + close-inside-lock 재검사)
2. daemon `ThreadFactory`(`workflow-fanout-worker-N`)
3. 2-tier bound — 풀(`maxConcurrency`) + 호출당 스택-로컬 `Semaphore(perBatchMax)` 를
   **호출 스레드에서** submit 전에 acquire(backpressure — worker 를 pin 하지 않아 동시 run 을 굶기지 않음)
4. 입력순 positional 재조립 + 이중 실패격리(`runSafely`/`joinSafely`)

타입 파라미터는 **클래스가 아니라 메서드**에 둔다 — 한 dispatcher 인스턴스가 여러 `<I,R>` 조합을
unchecked 캐스트 없이 처리한다.

tool-domain 게이트(`shouldParallelize`/`isParallelizableInterrupt`/eager-streaming 표면)는 가져오지
않는다 — 병렬 여부는 오케스트레이션 연산의 **의도**(`parallel` 호출 자체)이지 per-item 정책 조회가
아니다. **소스와의 의도적 편차**: 원본은 `catch (Exception)` 으로 모든 thunk 예외를 격리하지만,
오케스트레이션 버전은 run-fatal `WorkflowException` 만 격리에서 제외하고 재-throw 한다.

### 3.3 값객체

`AgentTask` — 불변 + 빌더. `subagent`(인라인 정의), `goal`, `label`, `phase`, `resultSchema`,
`isolate`, `nonCacheable`. per-task 모델은 subagent frontmatter 로 표현하므로 base env 는
`modelOverride` 를 설정하지 않아야 한다(설정 시 frontmatter 를 outrank).

`AgentStepResult` — 정적 팩토리. `isSuccess()`, `completionReason()`, `isComplete()`, `text()`,
`structured()`, `raw()`. 원본 `SubagentExecutionResult` 를 노출해 토큰 사용량·전사 스냅샷에 접근한다.

---

## 4. 구조화 출력과 예산

### 4.1 구조화 출력 — prompt-and-parse

`AgentTask.resultSchema`(중첩 `Map` JSON Schema)가 있으면 `agent()` 는 goal 에 "JSON only" 지시와
직렬화된 스키마를 덧붙이고, 성공 시 최종 답을 `StructuredOutputSupport` 로 fence-strip →
`ObjectMapper.readTree` → **최소 스키마 검증**(type/required/properties/items/enum, 재귀) 후 파싱된
객체를 `structured()` 로 노출한다. 검증 실패/비객체/비성공이면 `structured()` 는 비고 `text()` 는
원문을 유지한다.

**결정**: `emit_result` 툴 컨벤션 대신 prompt-and-parse 를 택했다 — 코어 subagent executor 무변경,
SPI 경계 유지, 저위험. 견고성이 더 필요해지면 `emit_result` 가 후속 업그레이드 경로다.

### 4.2 종료사유 채널

`SubagentExecutionResult.getCompletionReason()`(`at.aimon.core.agent.budget.CompletionReason`) 은
코어 최소 확장이다 — explicit-reason `success`/`failure` 오버로드를 추가하고 기존 3-arg 는
`COMPLETED`/`ERROR` 로 위임해 back-compat 를 지킨다. `DefaultSubagentExecutor` 가 네 지점에서 공급한다
(COMPLETED / budget stop reason / MAX_ITERATIONS / INTERRUPTED).

워크플로 쪽은 `AgentStepResult.completionReason()` + `isComplete()` 로 노출한다 → judge·loop-until-dry
패턴이 "DONE vs 예산소진 vs 실패" 를 `isSuccess()` boolean 이 아니라 정확히 구분한다.

### 4.3 `WorkflowBudget` — run-scoped 집계

`maxAgents`(기본 1000) + optional `maxTokens` + optional `maxCostUsd`(micros 로 보관).
`DefaultWorkflowContext` 가 run-scoped accumulator 에 누적하고, **다음** `agent()` 진입 시 상한 초과면
`WorkflowBudgetExceededException`(run-fatal)을 던진다.

**post-hoc 이다** — 상한을 넘긴 agent 는 완료·집계되고 *다음* agent 가 거부된다(mid-flight 중단 아님).
live 선점은 LLM 루프 중단 시맨틱을 요구하므로 과도하다고 판단했다. 동시 팬아웃에서 overshoot 창은
`≈ maxConcurrency × per-agent cost` 다(중첩 병렬 도입 후 `perBatchMax` 가 아니라 `maxConcurrency` 가
경계다 — §5.2).

에이전트 수 체크는 `manager.execute` **이전**이라 상한 초과 시 실제 LLM 지출은 발생하지 않는다.

### 4.4 비용 배선

USD 비용은 한때 inert 였다(서브에이전트 loop 이 `recordCost` 를 하지 않았다). 다섯 지점으로 관통시켰다:

1. `DefaultSubagentExecutor` 에 `CostEstimator`(기본 NOOP) 주입 seam — `runReActLoop` 의 `recordTokens`
   뒤에서 `OrcaAgentExecutor` 와 동형으로 가격 산정 → `budgetTracker.recordCost` + `CostSummary` 누적
2. `SubagentExecutionResult` 로 상향 노출
3. `DefaultWorkflowContext.agent()` 에 micros `LongAdder` 누적 (실행·재생 양쪽)
4. `WorkflowBudget.maxCostUsd` — 토큰과 동형 post-hoc 축
5. `DefaultSubagentExecutionManager` + `OrcaAgentRuntimeFactory` 로 estimator 관통

`NOOP` 기본이라 무설정 시 0원(무회귀).

---

## 5. 백그라운드 실행과 resume

### 5.1 run 제어 평면

```java
public interface WorkflowRunController {          // store 기반 · cross-node
    boolean stop(RunId runId);
    List<WorkflowRun> list(RunQuery query);
    Optional<WorkflowRun> status(RunId runId);
}

public interface WorkflowRunner extends AutoCloseable, WorkflowRunController {
    default <T> T run(WorkflowScript<T> script);              // DEFAULT_RUN_ID 로 위임
    <T> T run(WorkflowScript<T> script, RunId runId);         // foreground 동기
    <T> RunHandle<T> runInBackground(WorkflowScript<T> script, RunId runId);
    @Override void close();                                   // 공용 풀 2개 close
}
```

`RunId` 는 `AgentRuntimeId` 선례처럼 **비랜덤·결정론적**이다 (`RunId.from(scriptName, discriminator)`).

**멱등 재제출** — `runInBackground` 는 `RunStore.putIfAbsentOrTerminal(pending(runId))` 를 **원자적으로**
호출한다(check-then-act 아님 — TOCTOU 방지). 삽입되면 dispatch, 아니면 소유 노드는 node-local
`RunningRunRegistry` 에서 기존 `RunHandle` 을 반환하고, 핸들 부재(다른 노드/재기동)면 typed 예외로
reject 한다.

`WorkflowRun` 은 **타입 T 결과를 담지 않는다**(메타만: state, 시각, owner `Principal`,
`boundRuntimeId`, `lastHeartbeat`). typed 결과는 `RunHandle<T>.await()` — **소유 노드 전용**이다
(`T` 는 직렬화하지 않는다). `list`/`status`/`stop` 만 `RunStore` 기반 cross-node 다. cross-node 결과가
필요하면 동일 스크립트를 `StepResultCache` 위에서 재실행한다.

### 5.2 두 개의 풀과 취소

**run-hosting 풀과 팬아웃 풀은 물리적으로 분리한다.** run 본문(제어흐름)은 팬아웃 join 에서 park 하므로,
팬아웃 풀 위에서 호스팅하면 worker-starvation 을 재도입한다.

**취소 전파** — leaf 가 run 의 취소를 관측하려면 그 run 의 신호가 leaf 까지 가야 한다:

1. **per-run env** — `runInBackground` 는 run 마다 `InterruptCoordinator` 를 만들고, 빌려온 baseEnv
   협력자를 공유하되 `cancellationSignal` 만 교체한 **파생 env** 를 만들어 그 run 의 컨텍스트에 넘긴다.
   `agent()` 는 shared baseEnv 가 아니라 **이 per-run env** 로 `manager.execute` 를 호출한다.
   `SubagentExecutionEnvironment.toBuilder()` 가 그 seam 이며, 협력자를 공유하므로 소유권 규칙을
   깨지 않는다.
2. **부모→run cascade** — baseEnv 는 application-scoped 라 live 턴 신호를 담지 않는다. 취소 신호원은
   제출 시 호출자가 넘기는 live `CancellationSignal`(제출 턴/세션)이며, `onCancel(...)` 으로 run
   coordinator 에 **단방향 cascade** 하고 retained `Registration` 으로 정리한다.
3. **즉시성** — `stop(runId)` 은 coordinator 를 trip 한다. in-flight leaf 는 다음 관측점에서 협조적으로
   종료해 `AgentStepResult`(INTERRUPTED)를 반환하고 팬아웃 join 이 풀린다. **호스팅 워커 interrupt
   만으로는 `future.join()`(비인터럽트)이 안 풀리므로**, `joinSafely` 를 bounded `future.get(timeout)`
   루프로 만들어 run 신호를 폴링한다 → join 스레드는 signal trip 후 poll 1회 내 반환한다.
   다만 **full-run teardown 은 즉시가 아니라 cooperative-stop bound** 다: submit-loop 의
   `acquireUninterruptibly()` 와 실행 중 `manager.execute` leaf 는 `future.cancel` 로 인터럽트되지
   않으므로 협조 종료를 기다린다(데드락이 아니라 drain 이다).

**공용 팬아웃 풀의 공정성** — "한 run 이 풀을 독점하지 못한다" 는 `perBatchMax < maxConcurrency` 일
때만 성립한다. `WorkflowConcurrencyConfig.defaults()` 는 `perBatchMax = maxConcurrency` 이므로,
공용-풀(백그라운드) 모드는 `maxConcurrency ≥ 2` 를 요구하고 팩토리가 `perBatchMax` 미설정 시
`max(1, floor(maxConcurrency / maxConcurrentRuns))` 로 자동 유도한다. 사이징 precondition 은
`maxConcurrency ≥ maxConcurrentRuns × perBatchMax`. `maxConcurrency == 1`(≤3-core CI)에서는 유효한
`perBatchMax < 1` 이 없으므로 공용-풀 백그라운드 모드를 **거부**하고 foreground 나 순차 모드를 안내한다.

**run-fatal orphan 정리** — 공용 풀은 per-run close 가 없다. 주 lever 는 **run 신호 trip**(실행 중 leaf
가 협조 종료)이고, `future.cancel()` 은 이미 실행 중인 `supplyAsync` body 를 인터럽트하지 못하므로
**아직 스케줄되지 않은 배치 tail 에만 유효한 best-effort** 다.

### 5.3 `StepResultCache` — 구조적 스텝키

resume 의 정의는 단순하다: **동일 스크립트를 동일 구조로 재실행하되, 각 `agent()` 가 구조적 키로
캐시를 조회해 정상완료 스텝은 재생한다.** 스크립트 재실행이 곧 재개다.

**(a) 구조적 스텝키** — 팬아웃에서 `agent()` 는 비결정 순서로 호출되므로 완료-순서 카운터를 쓸 수 없다.
대신 **프로그램-순서 기반 구조 경로**를 쓴다. 핵심 불변식: **각 경로 레벨의 본문은 단일 스레드가 순차
실행**한다(최상위 = 호스팅 워커, 팬아웃 자식 = 그 thunk 를 실행하는 워커). 그 스레드가 자식 construct 에
부여하는 ordinal 은 스케줄링과 무관하게 결정적이다.

- **construct ordinal** — 한 레벨에서 호출되는 각 construct 에 프로그램 순서로 ordinal 을 부여한다:
  `agent()`→`a<n>`, `parallel()`→`p<n>`, `pipeline()`→`q<n>`. 형제 construct 는 서로 다른 ordinal 을
  받아 절대 충돌하지 않는다.
- **팬아웃 자식의 list index** — 각 thunk 를 wrap 하여 실행 전에 워커-가시 경로를
  `parentPath + [p<n>, i]` 로 set/restore 한다(try-finally 프레임). wrap 시점(호출 스레드, 리스트 순서)에
  부모 경로를 캡처해 prepend 하므로 스케줄과 무관하며, 중첩 팬아웃에서도 save/restore 된다.
- **정규 키 문법** — `StepKey = runId "/" agentRuntimeId ("/" segment)*`,
  `segment = ("a"|"p"|"q") <ordinal> ("/" <listIndex>)?`. 예:
  최상위 순차 `agent()` 둘 → `…/a0`, `…/a1`. `parallel([judge,judge,judge])` → `…/p0/0/a0`,
  `…/p0/1/a0`, `…/p0/2/a0`(동일 입력이어도 list index 로 분리 → 팬아웃 폭 보존). 뒤이은 두 번째
  `parallel(...)` → `…/p1/…`.

**(b) 입력 해시는 검증용** — `inputHash = hash(goal + inline subagent 정의(systemPrompt+model+tools+
allowedTools) + resultSchema)`. 키는 **위치**로 식별하고, 로드한 엔트리의 `inputHash` 가 다르면 캐시
miss 로 간주해 stale 재생을 막는다. subagent **정의 전체**를 해싱한다 — 이름만으로는 서로 다른 subagent
가 같은 위치에서 충돌한다.

**(c) transcript-free 값** — `StepOutcome` 은 text/structured/totalTokens/costMicros/completionReason/
inputHash/structureFingerprint 만 담는다. `SessionSnapshot`/`Message`/inline `Subagent` 를
직렬화하지 않으므로 codec 의존도, ArchUnit 위반도, inline-subagent round-trip 문제도 없다. 재생 시
`AgentStepResult` 는 **caller 의 live `AgentTask` 를 재부착**하고 `raw()` 는 빈 `SessionSnapshot`
으로 재구성한다 → **재생된 스텝의 전사는 비어 있고 메타데이터는 zeroed** 다(문서화된 한계). 따라서
예산 회계는 `raw().getMetadata()` 가 아니라 캐시된 `StepOutcome.totalTokens/costMicros` 로 재수화한다.

**(d) COMPLETED-only 저장** — `raw.isSuccess() && completionReason() == COMPLETED` 일 때만 save 한다.
INTERRUPTED/FAILED/MAX_ITERATIONS/budget-stop 스텝은 캐시하지 않으므로 resume 가 그 스텝을 재실행한다
(특히 `stop(runId)` 후 resume 가 중단 스텝을 이어서 처리한다).

**(e) 격리** — `StepKey` 는 owning `AgentRuntimeId` 를 포함하고, `ScopedStepResultCache` 데코레이터가
load 를 owning context 로 제한한다. `VfsStepResultCache` 는 공유 백엔드라도 context 스코핑으로
cross-agent 노출을 막는다.

구현체는 `InMemoryStepResultCache`(LRU 256) 기본, `VfsStepResultCache`(key 당 1객체 envelope)로
cross-node. 미설정 시 `StepResultCache.NO_OP`(항상 miss)라 무설정 무회귀다.

**예산 재수화** — 캐시 hit 로 실행을 스킵해도 `agentCount.increment()` + `tokensSpent.add(...)` +
`costSpent.add(...)` 를 동일하게 수행한다. 안 그러면 runaway backstop 이 조용히 리셋된다.

---

## 6. 격리 · 중첩 · 패턴

### 6.1 N-스테이지 `Pipeline` 빌더

SPI 를 늘리지 않고 **정적 조립**으로 임의 N-스테이지를 타입-보존 표현한다:

```java
List<Verdict> out = Pipeline.over(findings)
        .then((item, orig) -> ctx.agent(reviewer, item))
        .then((reviewed, orig) -> ctx.agent(verifier, reviewed.text()))
        .run(ctx);
```

`Stage<I, C>` 의 `then(BiFunction<C, I, N>)` 이 타입을 이어 붙이고, `run(ctx)` 이 전체 체인을 하나의
thunk 로 만들어 `ctx.parallel` 에 desugar 한다. 결과적으로 **스테이지 간 배리어가 없고 wall-clock =
가장 느린 단일 체인**이다. 스테이지마다 배리어를 두면 느린 아이템이 다음 스테이지 전체를 막아
wall-clock 이 `Σ max(stage_k)` 로 늘어난다.

### 6.2 진짜 중첩 병렬

초기 구현은 중첩 `parallel` 을 **호출 스레드에서 순차 폴백**시켰다 — 고정 풀에서 중첩 팬아웃은
worker-starvation 데드락이기 때문이다. 이를 세 장치로 대체했다:

- **cached daemon 풀** — 무제한이므로 워커발 중첩 팬아웃이 풀을 굶기는 것이 구조적으로 불가능하다.
  `ForkJoinPool` 은 기각했다: leaf 는 blocking LLM I/O 라 work-stealing 이 무의미하고,
  `maximumPoolSize == maxConcurrency` 로 좁히면 블록할 때 재-데드락 함정이 된다.
- **전역 leaf `Semaphore`**(`LeafConcurrencyLimiter`) — 팬아웃 풀을 공유하는 모든 run 에 걸친 동시
  `manager.execute`(LLM)의 유일한 하드 상한. **terminal leaf 만** 감싸고 join·thunk 를 가로질러
  보유하지 않는다(acyclic wait-for ⇒ 데드락 자유). thunk 를 게이트하면 permit 을 중첩 join 가로질러
  보유해 permit-starvation 데드락이 된다 — correctness-critical 이라 테스트와 주석으로 pin 했다.
  캐시-hit replay 는 permit 을 취득하지 않는다.
- **per-stack 깊이 게이트** — 깊이는 `PathFrame.nestingLevel`(부모 스레드가 submit 시 seed, 워커마다
  re-seed)이다. run-공유 카운터는 형제 동시성을 깊이로 오인해 mis-route 한다.
  `level > maxNestingDepth` 는 순차 팬아웃으로 **우아하게 degrade** 한다(run-fatal 아님).
  `maxNestingDepth = 1` 이 이전 동작의 정확한 재현이다(무회귀 기본값).

cached 풀은 무제한이므로 **스레드 수도 무제한**이다 — peak ≈ `perBatchMax^maxNestingDepth`(모두 leaf
Semaphore 에서 블록, native stack) → 병리적 설정에서 native-thread OOM 이 호스트를 무너뜨릴 수 있다.
따라서 **reserve-before-flip 절대 스레드 가드**를 둔다: `dispatch` 진입 시 `perBatchMax` 를 원자 예약하고,
초과하면 롤백 후 순차 팬아웃으로 flip 한다 → 성공 예약의 합이 cap 이하로 유지된다. 이 가드는
러너-공유라 **무관한 run 을 결합한다**(얕은 run 의 level-1 배치도 flip 될 수 있다) — 알려진 트레이드오프다.

> **공정성 re-base.** `perBatchMax` 는 per-batch 공정성/스레드 팬아웃 캡일 뿐, 중첩 하에서 run 의 총
> in-flight footprint 를 bound 하지 않는다. 중첩 무관 cross-run LLM 상한은 `maxConcurrency`(leaf
> Semaphore)다. 그래서 §4.3 의 post-hoc overshoot 창이 `perBatchMax` 가 아니라 `maxConcurrency` 다.

### 6.3 worktree 격리

병렬 서브에이전트가 같은 파일을 쓰면 안전 검증 없이는 위험하다. git worktree 의 아날로그로
**path-prefix 스코핑된 `VirtualFileSystem`** 을 브랜치별로 준다.

```java
@FunctionalInterface
public interface WorktreeEnvironmentFactory {
    SubagentExecutionEnvironment derive(SubagentExecutionEnvironment baseEnv, String branchKey);
}
```

- **호출자 주입** — per-branch 스코프 VFS 와 재바인딩된 파일-툴 `ToolRegistry` 는 **어셈블리의 factory
  만** 생성한다. workflow 패키지는 `agent.tool`/`tools.file`/`filesystem.impl` 을 import 하지 않고
  `factory.derive(baseEnv, branchKey)` + `env.toBuilder()` 만 호출한다 → ArchUnit allow-list 델타 0.
  파일 툴의 VFS 가 생성자-bound 라 컨텍스트로 교체할 수 없기 때문에 이 seam 이 필요하다.
- **disjoint 서브트리** — 구축상 zero-clobber, zero-copy 이며 Local/S3/GridFS 에 균일하게 적용된다.
  `ScopedVirtualFileSystem` 은 `list`/`listRecursive`/`search` **셋 다 결과에서 prefix 를 균일 strip**
  해 round-trip 불변식을 지키고, 파일 툴이 넘기는 **절대 경로 입력**도 브랜치 루트 기준으로 해석한다.
  `getWorkingDirectory()` 는 branch-relative `"."` 로 고정된다.
- **borrows-not-owns** — `ScopedVirtualFileSystem.close()`/`initialize()` 는 delegate 에 no-op 다.
  공유 백엔드 VFS 는 이를 생성한 부트스트랩만 close 하며, 브랜치 teardown 이나 `runner.close()` 는
  base VFS 를 절대 close 하지 않는다.
- **결정적 브랜치 이름** — 브랜치 서브트리는 결정적 구조 step-path 에서 명명되므로 형제/동일-입력
  브랜치가 서브트리를 공유하지 않고 재실행·cross-node 에서 안정적이다.
- **격리 착시 없음** — factory 미설정 상태에서 `isolate = true` 는 첫 사용 시 run-fatal
  `WorkflowException` 이다. 스크립트가 격리를 요청했는데 unscoped 로 실행하지 않는다.
- **VFS-only 경계** — 스코프되는 것은 `VirtualFileSystem` 뿐이다. Bash/shell/샌드박스 변조는
  `Environment.getWorkingDirectory()`(별도 필드)를 경유하므로 factory 가 스코프 Environment 도
  파생하지 않는 한 **격리되지 않는다**(문서화된 부분격리 caveat).

**캐시와의 상호작용이 load-bearing 하다.** `StepOutcome` 은 transcript-free 라 캐시 HIT 가 파일 델타를
재생하지 못한다 → base VFS 를 변조하는 스텝이 캐시되면 쓰기가 조용히 사라진다. 그래서
`AgentTask.build()` 가 `nonCacheable = nonCacheable || isolate` 를 유도하고, 캐시 우회는 오직
`isNonCacheable()` **하나**로 판정한다(두 플래그 분기로 인한 stale 재생 원천 차단). `isNonCacheable()`
이면 `agent()` 는 캐시 load 와 save 를 통째로 우회한다. canonical VFS 를 쓰는 **비격리 merge/promotion
스텝은 `nonCacheable(true)` 를 명시해야 한다** — `isolate = true` 는 worktree 로 돌려버리므로 대체재가
되지 못한다.

**병합은 명시적·비자동이다.** N-way 자동병합은 last-writer-wins 은닉과 snapshot 일관성 규칙을
요구하므로 과도하다고 판단했다. 복구 가능한 worktree/병합 실패는 `WorkflowException` 을 **절대 상속하지
않는다** — 실패 `AgentStepResult` 또는 merge-report 데이터로 표현한다. `WorkflowException` 은 진짜
run-fatal 전용이며, `BoundedFanoutDispatcher` 가 그것만 재-throw 해 run 을 abort 하기 때문이다.

### 6.4 `WorkflowPatterns` — 품질 패턴 헬퍼

정적 조립 라이브러리다. **SPI 성장 0** — GraalJS 프론트엔드가 default 메서드를 상속하게 만들지 않도록
프리미티브 seam 을 패턴 라이브러리에 결합시키지 않는다.

| 헬퍼 | 하는 일 |
|---|---|
| `adversarialVerify(ctx, finding, skeptic, n, quorum, …)` | 독립 회의론자 N명이 REFUTE 를 시도, quorum 미달이면 kill → `Verdict` |
| `judgePanel(ctx, attempts, judge, panelSize, …)` | 독립 시도들을 병렬 채점하고 승자에서 합성 → `JudgedResult` |
| `loopUntilDry(ctx, round, …)` | 새 발견이 없을 때까지 라운드를 반복(미지 크기 탐색) |
| `completenessCritic(ctx, draft, critic, …)` | "무엇이 빠졌는가" 를 묻는 최종 비평자 |
| `perspectiveDiverseVerify(ctx, claim, …)` | 동일 검증자 N명 대신 서로 다른 렌즈로 검증 |
| `multiModalSweep(ctx, modes)` | 서로 다른 검색 방식으로 동시 탐색 |

세 가지 규율이 헬퍼 전반에 공통이다: **교차곱 flatten**(순진한 중첩 `parallel` 은 깊이 게이트에 걸려
동시성을 잃는다), **null-tolerant**(격리된 실패 슬롯 무시 — 안 그러면 NPE 로 run 이 손상된다),
**`WorkflowException` 미포착**(broad catch 는 run-fatal 예산 abort 를 삼킨다).

### 6.5 구조-prefix 가드

구조적 스텝키만으로는 한 가지 오재생이 남는다 — **동일-정의 동종 시퀀스의 shift**. 위치와 inputHash 가
모두 맞아 형제 outcome 을 재생할 수 있다. leaf 가 결정론적·무부수효과면 무해하지만, worktree 격리가
들어온 뒤로는 무해하지 않다.

`StepOutcome.structureFingerprint` 를 검증자로 추가했다. 각 leaf 는 **좌측 문맥 다이제스트**(선행 형제
inputHash 체인 + 팬아웃 childCount fold)를 갖고, `agent()` 의 load 는 **위치 + inputHash + fingerprint
3중 일치** 시에만 replay 한다. 다이제스트는 기존 ordinal 과 동일한 단일-스레드-per-level 불변식 위에
계산되므로 신규 lock 이 없다.

- **구조-only 가 아니라 내용 fold** 인 이유: 동종 시퀀스 shift 는 kind-시퀀스와 ordinal 이 동일하다 —
  내용을 fold 해야만 갈라진다.
- **fail-safe 레거시 miss** — fingerprint 가 없는 레거시 outcome 은 replay 하지 않는다. 코덱
  `FORMAT_VERSION` 을 올려 레거시가 자동으로 decode-fail → miss 가 되게 했다(mixed-version 재생 창 없음).
- **over-invalidation 은 항상 안전** — 상류 순차 내용변경이 이후 동일-레벨 스텝을 재실행시킨다.
  재실행은 절대 오답이 아니며, 병렬 브랜치 격리는 유지된다. 반면 순수 REORDER(동일 inputHash)는
  원리적으로 탐지 불가하므로 `inputHash` 가 per-step 입력을 완전히 포착해야 한다(클로저-은닉 입력은
  caller 버그다).
- **`StepKey` 문법은 불변** — 검증자형은 최소이고 back-compat 가 무료다. `StepKey` 에 embed 하면
  문법·봉투·retention 비용이 든다.

---

## 7. GraalJS 프론트엔드

별도 모듈 `aimon-workflow-graaljs`(JDK-17 라인 `graal-sdk` + `js` 23.0.8)가 JS 리터럴 스크립트로
**동일 프리미티브**를 호출한다. **코어는 무변경이고 5-프리미티브 SPI 는 자라지 않는다.**

### 7.1 유일한 seam

`GraalJsWorkflowScript implements WorkflowScript<String>` 하나가 접합점이며, 기존 `WorkflowRunner` 가
그대로 실행한다.

**결과 캡처** — GraalJS 에서 모듈 eval 은 `undefined`/exports 를 반환하고 top-level `return` 은
SyntaxError 다. 그래서 저자 본문을 **`(async () => { … })()` 래퍼로 감싸** owner 스레드에서 실행하고,
반환된 Promise 를 job 큐 드레인 후 resolve → detach 한다. `return`/`await` 가 래퍼 함수 안이라 합법이며
ESM/실험 옵션이 필요 없다.

`WorkflowScript<String>` 인 이유는 `detach` 가 **항상 String** 을 반환하기 때문이다(스칼라는
`String.valueOf`, Map/List 는 JSON) → 도구가 `ToolResult.success(runner.run(script, runId))` 로 그대로
mirror 한다. 모든 JS 실행이 이 동기 `run()` 1콜 안에서 owner 스레드로만 진행되므로 반환 시 어떤
스레드에도 parked microtask 가 없다.

### 7.2 데이터-디스크립터 동기 팬아웃

**JS Value 는 워커 스레드를 절대 만지지 않는다.** JS `agent()` 는 동기이고, `parallel`/`pipeline` 은
**디스크립터 데이터**를 받아 **owner 스레드에서 `AgentTask` 로 마샬링한 뒤** 순수-Java `Supplier` 로
`ctx.parallel` 에 넘긴다.

```java
Object parallel(Value... a) {
    final List<Value> descriptors = a[0].as(LIST_OF_VALUE);
    final List<Supplier<AgentStepResult>> suppliers = new ArrayList<>(descriptors.size());
    for (final Value d : descriptors) {
        final AgentTask task = AgentTaskMarshaller.toTask(d, subagents);  // ★ owner 스레드에서 마샬
        suppliers.add(() -> ctx.agent(task));                             // 순수 Java thunk
    }
    return AgentResultView.array(ctx.parallel(suppliers));                // owner 스레드에서 JS array 로
}
```

**deep-detach 가 필수다.** `Value.as(Map.class)` 는 **shallow live view** 라 중첩 노드가 Context-bound
`PolyglotMap`/`PolyglotList` 로 남는다 — 워커가 만지면 GraalVM 단일-스레드 규칙 위반이다. 마샬러는
Value 를 **재귀 walk 하여 plain Java 만**(String/Long/Double/Boolean/LinkedHashMap/ArrayList) 만들어
polyglot 참조 0 을 보장한다. 그제서야 워커 thunk 는 Java 저자가 손으로 쓰는 것과 동일한 모양이 되고
단일-스레드 규칙이 **한 번도 시험되지 않는다**.

> **dispatcher 는 이 위반을 조용히 삼킨다.** 마샬이 얕아 워커가 guest 뷰를 만지면 GraalVM 이
> `IllegalStateException`(non-`WorkflowException`)을 던지는데, `runSafely`/`joinSafely` 는 이를
> WARN + null 슬롯으로 격리한다 → **조용한 기능 실패**. 그래서 deep-detach 를 마샬 시점에 강제하고,
> 테스트를 **양성 검증**(non-null 구조화 결과 + `AgentTask`/`Subagent` 에 `org.graalvm.*` 타입 부재
> 단언)으로 작성한다.

**무료 계승** — `ctx.parallel` 을 그대로 쓰므로 leaf ceiling, run-fatal 예산 carve-out, 입력순 null-격리,
중첩 degrade, 신호-폴링 join, resume 결정성, worktree 격리가 전부 변경 없이 계승된다.

**표면 발산 두 가지** (의도적으로 문서화):

- `pipeline(items, ...stages)` 은 **barrier-per-stage desugar** 다 — JS 스테이지 함수가 워커에서
  result→descriptor 변환을 못하므로 무배리어 아이템-파이프라인이 불가능하다.
- `parallel([() => agent(a)])` **클로저 sugar 는 미지원** 이다. `parallel` 은 디스크립터 배열만 받는다.
  클로저 구문은 collecting-mode/sentinel 기계를 요구하는데 조용한 오작동 위험이 있어 명시적 후속으로
  미뤘다.
- `await` 는 cosmetic 이다 — `agent()` 가 동기로 값을 반환하므로 `await` 는 non-thenable 을 1 microtask
  로 resolve 한다. ultracode 구문이 그대로 포팅되되 실제 async 는 아니다.

임의 multi-step `await` 클로저와 진짜 무배리어 pipeline 은 신규 코어 프리미티브
`CompletableFuture<AgentStepResult> agentAsync(AgentTask)` 를 요구한다 — 동결한 SPI 를 키우므로 범위
밖으로 두었다.

### 7.3 계층 샌드박스

신뢰-저자 콘텐츠를 전제로 `JsContextFactory` 에 계층 방어를 구성한다.

```java
Context.newBuilder("js").engine(sharedEngine)
    .allowAllAccess(false)
    .allowHostAccess(HostAccess.NONE)                 // ★ 진짜 tightening — 기본은 EXPLICIT
    .allowHostClassLookup(n -> false).allowHostClassLoading(false)
    .allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false)
    .allowNativeAccess(false).allowEnvironmentAccess(EnvironmentAccess.NONE)
    .allowPolyglotAccess(PolyglotAccess.NONE)
    .resourceLimits(ResourceLimits.newBuilder()       // ★ JS runaway 백스톱 (CE 가용)
        .statementLimit(cfg.maxStatements(), s -> true).onLimit(ev -> ...).build())
    .build();
```

- **L1 · deny-by-default host 표면** — `HostAccess.NONE` + **proxy-only 바인딩**
  (`ProxyExecutable`/`ProxyObject`). 모든 코어 결과는 fresh `ProxyObject` 스냅샷으로 넘어가므로
  guest 에 raw host 객체가 전달되지 않는다. **어떤 코어 값 타입에도 `@HostAccess.Export` 를 붙이지
  않는다** — 이유는 host 표면 축소가 아니라(NONE 하에서 `@Export` 는 inert) **`org.graalvm` 컴파일
  의존성이 코어에 침투하는 것을 막는 순수-Java-코어 불변식**이다.
- **L2 · 예산 backstop 재사용** — 에이전트 수/토큰/비용 backstop 과 전역 leaf ceiling 은 `ctx.agent`
  안에서만 발화하므로 JS-레벨 CPU 를 막지 않는다. `agent()` 를 호출하는 스크립트는 기존 기계로 bound
  되며 중복 limit 코드가 없다.
- **L3 · JS runaway 백스톱** — `agent()` 를 전혀 부르지 않는 순수-JS runaway(`while(true){}`)는 예산이
  잡지 못하므로 `ResourceLimits.statementLimit`(CE 가용, 필수) + wall-clock `Context.close(true)`
  watchdog(run `CancellationSignal` 연동)으로 막는다.
- **determinism prelude** — `Object.freeze(Math)` 는 속성만 잠글 뿐 `Math.random()` 을 **못 막는다**.
  결정성 모드는 (a) `Date`/`Math.random` **fail-closed 삭제**(사용 시 loud `ReferenceError`) 또는
  (b) **seeded 대체**(권장 — `runId` 로 seed 한 PRNG + fixed/logical clock 을 설치한 뒤 그 *대체 구현*을
  freeze) 중 하나여야 한다. 이것은 보안이 아니라 순수한 **결정성 제어**다.

**정직한 한계** — `sandbox.MaxHeapMemory`/`MaxCPUTime`·ISOLATED/UNTRUSTED 는 CE/JDK-17/23.0.x 에서
불가능하다(isolate/EE·JDK-21+ 필요). `while(true) arr.push(x)` 는 watchdog 발화 전에 공유 JVM 을 OOM
시킬 수 있다 → **trusted-author 전용**이다. 부트스트랩 capability 체크가 하드 limit 부재를 표면화한다
(조용한 degrade 금지).

### 7.4 resume 태세

**순서 결정성은 성립한다** — JS 가 owner 스레드에서 프로그램 순서로 프리미티브를 몰고 팬아웃이
`ctx.parallel` 의 구조 step-path 를 타므로 step-path 순서는 결정적이다.

**내용 결정성은 미보장이다** — JS `Date`/`Math.random`/객체 key 순서가 `inputHash` 를 흔들면 오재생이나
spurious miss 가 난다. 그래서 **기본 JS run 은 `StepResultCache.NO_OP`**(비-resume)이고, determinism
prelude 를 켠 결정성 모드에서만 resume 를 허용한다.

---

## 8. 설계 결정 요약

| # | 결정 | 근거 / 기각 대안 |
|---|---|---|
| D1 | 제어흐름은 **순수 Java 함수형 DSL** | 완전한 타입안전·디버깅·스택트레이스·테스트, 추가 의존성 0, 결정론적, loop-until-dry 같은 임의 제어흐름 자유. 데이터-주도 DAG/YAML 은 표현력 부족(scheduling `RoutineStep` 이 이미 그 한계)으로 기각 |
| D2 | 병렬 기계는 **제네릭 복사**(`BoundedFanoutDispatcher`) | `DefaultParallelToolDispatcher` 는 `ToolUse`/`ToolUseResult` 에 얕지만 광범위하게 결합. 서브에이전트를 `ToolUse` 로 어댑트하는 것은 의미 왜곡 |
| D3 | scheduling `RoutineExecutor` 를 확장하지 않고 **병치** | cron `ScheduledTask` 강결합, 툴-스텝 그래뉼래리티, 실행 중 LLM 미동작 — 개념이 다르다 |
| D4 | `parallel` 실행 실패는 **격리(null 대체)**, run-fatal 예외만 전파 | 한 발견의 실패가 배치를 무너뜨리지 않아야 리뷰/판정 패턴이 성립. 백스톱이 팬아웃 안에서 삼켜지면 런어웨이가 조용히 무한 생성 |
| D5 | `pipeline` 은 **parallel-over-chains** (스테이지 배리어 없음) | 배리어를 두면 wall-clock 이 `Σ max(stage_k)` 로 늘어남 |
| D6 | run 타입은 subagent 타입을 **clone/genericise**(재사용 아님) | `RunningTaskHandle.future` 는 `CompletableFuture<SubagentExecutionResult>` 라 `T` 를 수용 못 함. 알고리즘만 1:1 모방 |
| D7 | run-hosting 풀과 팬아웃 풀 **물리 분리** | 팬아웃 풀 위 호스팅은 worker-starvation 재도입 |
| D8 | 스텝키는 **구조적 경로**, 입력 해시는 **검증용**, inline `Subagent` 미직렬화 | 완료-순서 카운터는 팬아웃에서 비결정. 형제는 ordinal, 동일-입력 병렬은 list index 로 분리 |
| D9 | 캐시는 **transcript-free · COMPLETED-only** | 실패/중단 스텝을 캐시하면 resume 가 실패를 재생. transcript 저장은 codec 의존·inline-subagent round-trip 문제를 부름 |
| D10 | 토큰·비용 예산은 **post-hoc** | live 선점은 LLM 루프 중단 시맨틱을 요구 — 과도 |
| D11 | typed `T` 는 **소유 노드 전용**, 제어/상태만 cross-node | `T` 직렬화 경로가 없다. cross-node 결과가 필요하면 캐시 위에서 재실행 |
| D12 | 중첩 병렬은 **cached 풀 + 전역 leaf Semaphore + per-stack 깊이 게이트** | leaf 는 blocking LLM I/O — ForkJoinPool work-stealing 무의미. 무제한 풀의 스레드 폭발은 별도 절대 가드로 |
| D13 | leaf permit 은 **terminal leaf 만** 감싼다 (thunk 아님) | thunk 게이트는 permit 을 중첩 join 가로질러 보유 → permit-starvation 데드락 |
| D14 | 격리는 **path-prefix 스코프 VFS + 주입 factory + opt-in** | 파일 툴 VFS 는 생성자-bound. disjoint 서브트리 = 구축상 zero-clobber·zero-copy·백엔드 균일 |
| D15 | 병합은 **명시적·비자동** | N-way 자동병합은 last-writer-wins 은닉 + snapshot 일관성 규칙 필요 |
| D16 | 구조 가드는 **`StepOutcome` 검증자(fingerprint)**, `StepKey` 문법 불변 | 검증자형은 최소이고 back-compat 가 무료(레거시→miss). key-embed 는 문법·봉투·retention 비용 |
| D17 | N-스테이지·패턴 헬퍼는 **정적 조립, SPI 성장 0** | GraalJS 프론트엔드가 default 를 상속 — 프리미티브 seam 을 패턴 라이브러리에 결합 금지 |
| D18 | JS 는 **데이터-디스크립터 동기 팬아웃**(마샬-before-fan-out) | JS Value 가 워커를 만지면 GraalVM 단일-스레드 위반이고, dispatcher 가 그것을 null 슬롯으로 조용히 삼킨다 |

---

## 9. 불변식 (구현 시 지켜야 하는 것)

### 9.1 스코프 — 절대 close 하면 안 되는 것

| 불변식 | 근거 |
|---|---|
| 러너는 `AgentRuntime`(및 그 자식 `ToolRegistry`/`HookRegistry`/`McpClientManager`)을 close 하지 않는다 | agent-scoped — 같은 agent 의 다른 세션이 사용 중 |
| 러너는 빌려온 `SubagentExecutionManager` 를 close 하지 않는다 | `DefaultSubagentExecutionManager` 는 background 풀을 소유 — 소유자가 닫는다 |
| 러너는 `AgentRuntimeRegistry`/`SchedulingEngine`/`RoutineExecutor`/`KnowledgeStore`/`CredentialStore` 를 건드리지 않는다 | application-scoped 장수명. 닫으면 cron 재발화와 타 세션이 무너진다 |
| `ScopedVirtualFileSystem.close()`/`initialize()` 는 delegate 에 no-op | 공유 백엔드 VFS 는 부트스트랩만 close |
| 러너가 소유·close 하는 것은 **팬아웃 풀과 run-hosting 풀뿐** | §2.2 |

### 9.2 스레드 안전

| 불변식 | 근거 |
|---|---|
| 병렬 서브에이전트는 agent-scoped `ToolRegistry`(`LinkedHashMap`, 비동기화)를 **읽기만** 한다 | 부트스트랩 1회 등록 후 read-only 규율에서만 안전 |
| per-execution 가변 `ToolContext`(`ReadTool.READ_FILES_KEY` 등)는 실행마다 새로 생성되어 격리된다 | 각 `manager.execute` 가 독립 컨텍스트를 만든다. executor 가 넣는 read-files set 은 `ConcurrentHashMap.newKeySet()` |
| 오케스트레이션은 `LiveSession` 을 만지지 않고 매니저로 서브에이전트를 직접 실행한다 | 라이브 세션은 thread-safe 가 아니다 |
| 사용자 정의 `WorkflowEventSink` 와 Pre/PostTool 훅은 thread-safe 여야 한다 | worker 스레드에서 동시 호출된다 |
| 전역 leaf `Semaphore` permit 은 **terminal leaf 만** 감싸고 join 을 가로질러 보유하지 않는다 | acyclic wait-for ⇒ 데드락 자유 (D13) |

### 9.3 캐시·resume

| 불변식 | 근거 |
|---|---|
| 캐시 hit 스텝도 agent/토큰/비용을 회계에 반영한다 | 안 그러면 runaway backstop 이 조용히 리셋된다 |
| `isNonCacheable()` 이면 load 와 save 를 **둘 다** 우회한다 | save-only 억제는 stale cacheable 엔트리의 LOAD-replay 를 못 막는다(플래그는 `inputHash` 에 없다) |
| replay 는 위치 + `inputHash` + `structureFingerprint` **3중 일치** 시에만 | 동종 시퀀스 shift 오재생 차단 |
| fingerprint 부재/레거시 outcome 은 replay 하지 않는다 | fail-safe — 재실행은 절대 오답이 아니다 |
| cross-node 로 나가는 것은 **상태뿐**, typed `T` 는 소유 노드 전용 | `T` 직렬화 경로 없음 |

### 9.4 알려진 한계 (문서화된 것)

- **재생된 스텝의 전사는 비어 있고 메타데이터는 zeroed** 다 — 소비자는 `text()`/`structured()` 를 쓰고,
  회계는 `StepOutcome` 에서 재수화한다.
- **post-hoc overshoot** — 동시 팬아웃에서 `maxConcurrency` 만큼 초과한 뒤에 관측된다.
- **절대 스레드 가드는 러너-공유라 무관한 run 을 결합한다** — 얕은 run 의 level-1 배치도 flip 될 수 있다.
- **worktree 격리는 VFS-only** — shell/샌드박스 변조는 격리되지 않는다.
- **순수 REORDER(동일 inputHash)는 원리적으로 탐지 불가** — `inputHash` 가 per-step 입력을 완전히
  포착해야 하며 클로저-은닉 입력은 caller 버그다.
- **GraalJS 는 trusted-author 전용** — 하드 메모리/CPU limit 이 이 런타임 라인에서 불가능하다.

---

## 관련 문서

- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — 재사용한 병렬 기계
  (`ConcurrencyBehavior`, `ToolConcurrencyConfig`, `DefaultParallelToolDispatcher`)의 설계
- [`../subagent/code-defined-registration.md`](../subagent/code-defined-registration.md) — 코드로
  `Subagent`/`SubagentBehavior` 를 정의하는 선례. `WorkflowScript` 의 근거
- [`../subagent/execution.md`](../subagent/execution.md) — 서브에이전트 실행·백그라운드·resume 기반
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `CancellationSignal` /
  `InterruptCoordinator` 계약
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임
- [`../../features/tool/tool-development-guide.md`](../../features/tool/tool-development-guide.md) —
  `ConcurrencyBehavior` 체크리스트, `ToolContext` 가변 상태 thread-safe 주입 규율
