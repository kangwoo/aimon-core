# Workflow 사용 가이드 (라이브러리 관점)

> `aimon-core`를 임베딩한 Java 애플리케이션에서 Workflow 서브시스템을 직접 사용하는 방법

이 문서는 **Java 코드로 워크플로를 조립·실행하는 개발자**를 대상으로 한다.
aimon-cli 사용자 관점(`Workflow` / `WorkflowJs` 도구, `/runs` 명령)은
[Workflow CLI 가이드](workflow-cli-guide.md)를 참조한다.

## 목차

1. [개요](#개요)
2. [언제 쓰고 언제 쓰지 않는가](#언제-쓰고-언제-쓰지-않는가)
3. [5분 시작](#5분-시작)
4. [러너 조립](#러너-조립)
5. [스크립트 작성 — WorkflowContext 프리미티브](#스크립트-작성--workflowcontext-프리미티브)
6. [AgentTask와 AgentStepResult](#agenttask와-agentstepresult)
7. [구조화 출력 (resultSchema)](#구조화-출력-resultschema)
8. [WorkflowPatterns — 품질 패턴 헬퍼](#workflowpatterns--품질-패턴-헬퍼)
9. [동시성 설정](#동시성-설정)
10. [예산 (WorkflowBudget)](#예산-workflowbudget)
11. [백그라운드 실행과 재개](#백그라운드-실행과-재개)
12. [이벤트 싱크](#이벤트-싱크)
13. [워크트리 격리](#워크트리-격리)
14. [라이프사이클과 소유권 규칙](#라이프사이클과-소유권-규칙)
15. [함정 체크리스트](#함정-체크리스트)

---

## 개요

Workflow는 **제어 흐름을 LLM이 아니라 코드가 결정하는** 서브에이전트 오케스트레이션 계층이다.

| | 결정 주체 | 쓰임 |
|---|---|---|
| ReAct 루프 (`OrcaAgentExecutor`) | LLM | 무엇을 할지 모르는 열린 문제 |
| `Task` 도구 (서브에이전트 1회 위임) | LLM | 한 덩어리를 떼어 위임 |
| **Workflow** | **코드(Java/JS 스크립트)** | 팬아웃/파이프라인/검증 루프처럼 **구조가 이미 정해진** 작업 |

핵심 아이디어는 하나다. **스크립트가 구조를 쓰고, LLM은 각 서브에이전트 안에서만 돈다.**
따라서 팬아웃 폭, 검증 라운드 수, 조기 종료 조건이 결정론적이고 재현 가능하다.

패키지 위치:

- `at.aimon.core.workflow` — 공개 SPI/값 타입 (이 문서가 다루는 전부)
- `at.aimon.core.workflow.impl` — 구현체. **외부에서 직접 import 금지** (ArchUnit이 차단).
  진입점은 항상 `WorkflowRunners` 팩토리다.

---

## 언제 쓰고 언제 쓰지 않는가

**쓸 만한 경우**

- N개 대상에 같은 작업을 반복 (파일별 리뷰, 서비스별 점검, 후보별 평가)
- "생성 → 검증 → 종합" 처럼 단계가 고정된 다단 처리
- 독립적인 시각 여러 개를 모아야 신뢰가 생기는 판단 (판정단, 반증단)
- 발견 개수를 모르는 탐색을 "K라운드 연속 무소득이면 종료"로 수렴시키고 싶을 때

**쓰지 말아야 할 경우**

- 서브에이전트 1개면 끝나는 일 → 그냥 `Task` 도구 또는 `SubagentExecutionManager` 직접 호출
- 단계 수·분기 조건 자체를 LLM이 판단해야 하는 일 → ReAct 루프가 맞다
- 스크립트 안에서 사용자와 상호작용해야 하는 일 → 워크플로는 비대화형이다

---

## 5분 시작

```java
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunners;

// 1) 서브에이전트가 실행될 기반 환경 (모든 스텝이 이 환경을 상속한다)
SubagentExecutionEnvironment baseEnv = SubagentExecutionEnvironment.builder()
        .contextId(contextId)                 // AgentRuntimeId
        .subagentRegistry(subagentRegistry)
        .toolRegistry(toolRegistry)
        .hookRegistry(hookRegistry)
        .environment(environment)
        .defaultModel(agent.getMetadata().getModel())
        .build();

// 2) 러너 조립 (subagentExecutionManager는 빌려 쓰는 것 — 러너가 닫지 않는다)
try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, baseEnv)) {

    // 3) 스크립트 실행
    String answer = runner.run(ctx -> {
        ctx.phase("Analyze");
        List<AgentStepResult> results = ctx.parallel(List.of(
                () -> ctx.agent(technical, question),
                () -> ctx.agent(risk, question),
                () -> ctx.agent(cost, question)));

        ctx.phase("Synthesize");
        return ctx.agent(synthesizer, combine(results)).text();
    });
}
```

서브에이전트는 평소대로 정의한다 (자세한 내용은
[서브에이전트 개발 가이드](../subagent/subagent-development-guide.md)):

```java
Subagent technical = Subagent.builder()
        .name("review:technical")
        .systemPrompt("You analyze strictly from a technical-correctness angle. Be concise.")
        .build();
```

---

## 러너 조립

### 팩토리

`WorkflowRunners`가 유일한 진입점이다. 오버로드는 네 가지다.

```java
// 최소 — 기본 동시성, 이벤트 싱크 없음, 기본 예산
WorkflowRunners.create(manager, baseEnv);

// 동시성 / 이벤트 / 예산 지정
WorkflowRunners.create(manager, baseEnv, concurrency, eventSink, budget);

// 위 + 스텝 결과 캐시(재개용)
WorkflowRunners.create(manager, baseEnv, concurrency, eventSink, budget, stepResultCache);

// 전체 옵션 (권장)
WorkflowRunners.create(manager, baseEnv, options);
```

### WorkflowRunnerOptions

모든 필드가 nullable이며, 지정하지 않으면 각 컴포넌트의 기본값이 쓰인다.

```java
WorkflowRunnerOptions options = WorkflowRunnerOptions.builder()
        .concurrency(WorkflowConcurrencyConfig.enabled(8, 2))
        .eventSink(mySink)
        .budget(WorkflowBudget.of(200, 2_000_000))
        .stepResultCache(WorkflowRunners.inMemoryStepResultCache())
        .runStore(myRunStore)                    // 백그라운드 런 제어 평면 저장소
        .backgroundConfig(WorkflowBackgroundConfig.of(4))
        .worktreeFactory(worktreeFactory)        // isolate=true 스텝을 쓸 때만
        .build();
```

| 옵션 | 없을 때 | 지정하는 이유 |
|------|--------|--------------|
| `concurrency` | `WorkflowConcurrencyConfig.defaults()` | 팬아웃 폭 / 공유 풀 분배 조정 |
| `eventSink` | `WorkflowEventSink.NO_OP` | phase/log/스텝 시작·완료를 UI에 흘리기 |
| `budget` | `WorkflowBudget.defaults()` (에이전트 1000개) | 폭주 방지, 토큰·비용 상한 |
| `stepResultCache` | `StepResultCache.NO_OP` (재개 불가) | 중단된 런을 재실행 시 캐시 히트로 건너뛰기 |
| `runStore` | in-memory | 멀티 인스턴스 런 목록/상태 공유 |
| `backgroundConfig` | `WorkflowBackgroundConfig.defaults()` | 동시 백그라운드 런 수·큐 용량 |
| `worktreeFactory` | 없음 → `isolate` 스텝은 런 치명적 실패 | 파일을 변조하는 병렬 스텝 격리 |

### 코어가 조립하는 방식 (참고)

`OrcaAgentRuntimeFactory#buildWorkflowRunner`가 표준 조립 예시다.
컨텍스트당 러너 하나를 만들고 in-memory 스텝 캐시와 워크트리 팩토리를 붙인다.

```java
return WorkflowRunners.create(subagentExecutionManager, baseEnv,
        WorkflowRunnerOptions.builder()
                .stepResultCache(WorkflowRunners.inMemoryStepResultCache())
                .worktreeFactory(worktreeFactory)
                .build());
```

---

## 스크립트 작성 — WorkflowContext 프리미티브

`WorkflowScript<T>`는 함수형 인터페이스다.

```java
@FunctionalInterface
public interface WorkflowScript<T> {
    T run(WorkflowContext ctx);
}
```

`WorkflowContext`가 제공하는 능력은 다섯 개뿐이다.

### `agent` — 서브에이전트 1스텝 (동기)

```java
AgentStepResult r = ctx.agent(subagent, "goal 문자열");   // 편의 오버로드
AgentStepResult r = ctx.agent(AgentTask.builder()...build());
```

### `parallel` — 배리어 팬아웃

```java
<R> List<R> parallel(List<Supplier<R>> thunks);
```

- 모든 thunk이 끝날 때까지 **기다린다** (배리어).
- 결과는 **입력 순서**로 재조립된다.
- thunk이 예외를 던지면 그 자리에 `null`이 들어간다 — 호출자는 반드시 null-safe해야 한다.
  ```java
  List<AgentStepResult> rs = ctx.parallel(thunks);
  rs.stream().filter(Objects::nonNull).forEach(...);
  ```
- 단, **런 치명적(run-fatal) `WorkflowException`은 격리되지 않고 그대로 전파된다** (예산 초과, 워크트리
  팩토리 부재, 취소 등). 이건 런 전체를 죽이는 것이 맞는 상황이다.

### `pipeline` — 배리어 없는 아이템 병렬

```java
<I, A, R> List<R> pipeline(List<I> items, Function<I, A> stage1, BiFunction<A, I, R> stage2);
```

각 아이템이 **자기 스테이지 체인을 독립적으로** 통과한다. 아이템 A가 2단계를 도는 동안 아이템 B는
아직 1단계일 수 있다. 벽시계 시간은 "가장 느린 단일 아이템 체인"이지 "스테이지별 최댓값의 합"이 아니다.

```java
List<String> verdicts = ctx.pipeline(diffs,
        diff -> ctx.agent(reviewer, "find bugs in " + diff),
        (result, diff) -> ctx.agent(verifier, "refute: " + result.text()).text());
```

> **주의:** GraalJS 프런트엔드의 `pipeline`은 의미가 다르다. JS 쪽은 **스테이지마다 배리어**가 있는
> 디슈가링이다. Java 쪽만 아이템 병렬이다. CLI 가이드의 해당 절을 참조.

3단계 이상은 제네릭으로 표현되지 않으므로 `Pipeline` 빌더를 쓴다 — 타입이 스테이지를 따라 이어진다.

```java
List<String> out = Pipeline.over(diffs)
        .then((d, orig) -> ctx.agent(reviewer, "find bugs in " + d))
        .then((r, orig) -> r.text())
        .then((text, orig) -> ctx.agent(verifier, "refute: " + text).text())
        .run(ctx);
```

`Pipeline`은 새 프리미티브가 아니라 `parallel` 위의 순수 정적 합성이다. `run()`이 아이템별 체인 하나를
thunk 하나로 만들어 팬아웃한다. 따라서 null 규칙도 `parallel`과 동일하다.

### 배리어가 정말 필요한가

`parallel`(배리어)은 **다음 단계가 이전 단계의 결과 전체를 봐야 할 때만** 옳다.

배리어가 맞는 경우:
- 전체 결과를 dedup/merge한 뒤 비싼 후속 작업을 돌린다
- 총 개수가 0이면 다음 단계를 통째로 건너뛴다
- 다음 단계 프롬프트가 "다른 발견들과 비교해서"를 요구한다

배리어가 필요 없는 경우 (→ `pipeline`으로):
- 단순히 flatten/map/filter만 하려고
- 단계가 개념적으로 분리돼 보여서
- 코드가 깔끔해 보여서

### `phase` / `log`

```java
ctx.phase("Verify");        // 이후 스텝을 이 이름으로 그룹핑 (이벤트 싱크로 전달)
ctx.log("12/30 검증 완료");   // 진행 메시지
```

`phase`는 전역 상태다. 병렬 스테이지 안에서 `phase`를 부르면 경합한다. 스텝별 그룹핑은
`AgentTask.builder().phase("Verify")`로 **태스크에 직접** 붙이는 편이 안전하다.

---

## AgentTask와 AgentStepResult

### AgentTask

불변 빌더. 최소 구성은 `subagent` + `goal`이다.

```java
AgentTask task = AgentTask.builder()
        .subagent(reviewer)
        .goal("이 diff에서 버그를 찾아라:\n" + diff)
        .label("review:" + file)        // 생략 시 서브에이전트 이름
        .phase("Review")                 // 이벤트 그룹
        .resultSchema(FINDINGS_SCHEMA)   // JSON Schema — 구조화 출력 강제
        .isolate(false)                  // true면 워크트리 격리 + 캐시 불가
        .nonCacheable(false)             // true면 재개 캐시에 저장하지 않음
        .build();

AgentTask simple = AgentTask.of(reviewer, goal);  // 축약
```

`isolate(true)`는 `nonCacheable`을 함의한다(격리 실행은 부수효과가 있으므로 캐시 재생이 무의미하다).

### AgentStepResult

```java
result.isSuccess();          // 스텝 성공 여부
result.isComplete();         // completionReason == COMPLETED
result.completionReason();   // 왜 끝났는가 (한도 도달/에러/취소 등)
result.text();               // 최종 텍스트
result.structured();         // Optional<Map<String,Object>> — resultSchema를 준 경우
result.getLabel();
result.getTask();
result.raw();                // 원본 서브에이전트 응답
```

`isSuccess()`와 `isComplete()`는 다르다. 반복 한도에 걸려 멈춘 스텝도 텍스트를 남길 수 있다.
품질이 중요한 단계에서는 `isComplete()`로 거른다.

---

## 구조화 출력 (resultSchema)

`resultSchema`를 주면 서브에이전트는 구조화 출력 도구를 호출하도록 강제되고, 결과는 검증된
`Map`으로 돌아온다. 파싱 코드를 쓸 필요가 없고, 스키마 불일치 시 모델이 재시도한다.

```java
static final Map<String, Object> VERDICT_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
                "refuted", Map.of("type", "boolean"),
                "reason", Map.of("type", "string")),
        "required", List.of("refuted"));

AgentStepResult r = ctx.agent(AgentTask.builder()
        .subagent(skeptic)
        .goal("다음 주장을 반증하라: " + claim)
        .resultSchema(VERDICT_SCHEMA)
        .build());

boolean refuted = Boolean.TRUE.equals(
        r.structured().map(m -> m.get("refuted")).orElse(null));
```

판정/투표/분류처럼 **결과를 코드가 읽어야 하는 스텝**에는 항상 스키마를 붙인다.

---

## WorkflowPatterns — 품질 패턴 헬퍼

자주 쓰는 검증 구조 6종이 정적 헬퍼로 제공된다. 전부 위의 프리미티브만으로 구현돼 있으므로,
필요하면 직접 조합해도 된다.

```java
// 1) 반증단 — n명이 독립적으로 반박, quorum 이상 반박하면 기각
Verdict v = WorkflowPatterns.adversarialVerify(ctx, finding, skeptic, 3, 2, REFUTE_SCHEMA);
v.isSurvived();       // 살아남았는가
v.isInconclusive();   // 유효표 부족
v.getRefutations();   // 반박 표 수

// 2) 판정단 — 후보 여러 개를 패널이 점수 매기고 최고안을 종합
JudgedResult jr = WorkflowPatterns.judgePanel(ctx, attempts, judge, 2, synthesizer, SCORE_SCHEMA);
jr.best(); jr.bestIndex(); jr.scores(); jr.synthesis();

// 3) 무소득까지 반복 — 개수를 모르는 탐색용. quietK 라운드 연속 빈 결과면 종료
List<AgentStepResult> rounds = WorkflowPatterns.loopUntilDry(
        ctx, i -> AgentTask.of(finder, "라운드 " + i + ": 새 버그를 찾아라"),
        r -> r == null || r.text().isBlank(), 2, 10);

// 4) 완결성 비평 — 비평가가 빠진 것을 지적하고 개정자가 고치는 루프
AgentStepResult finalDraft = WorkflowPatterns.completenessCritic(ctx, draft, critic, reviser, 3);

// 5) 시각 다양성 검증 — 검증자마다 다른 렌즈를 준다 (동일 검증자 N명보다 강하다)
List<AgentStepResult> lenses = WorkflowPatterns.perspectiveDiverseVerify(
        ctx, claim, List.of(correctnessLens, securityLens, reproLens), VERDICT_SCHEMA);

// 6) 다중 모드 탐색 — 서로 다른 방식으로 훑는 병렬 스윕
List<AgentStepResult> hits = WorkflowPatterns.multiModalSweep(ctx, modes);
```

반환 리스트에는 `null`이 섞일 수 있다(실패한 팬아웃 슬롯). 항상 필터링한다.

### 조합 예 — 발견 → 중복 제거 → 다중 렌즈 검증 → 무소득까지

```java
Set<String> seen = new HashSet<>();
List<Finding> confirmed = new ArrayList<>();
int dry = 0;

while (dry < 2) {
    ctx.phase("Find");
    List<AgentStepResult> found = ctx.parallel(finders.stream()
            .<Supplier<AgentStepResult>>map(f -> () -> ctx.agent(f, prompt)).toList());

    List<Finding> fresh = parse(found).stream()
            .filter(b -> seen.add(key(b)))   // 확정본이 아니라 '본 적 있는 것' 기준으로 dedup
            .toList();                        // ← 기각된 발견이 매 라운드 되살아나면 수렴하지 않는다

    if (fresh.isEmpty()) { dry++; continue; }
    dry = 0;

    ctx.phase("Verify");
    for (Finding b : fresh) {
        Verdict v = WorkflowPatterns.adversarialVerify(ctx, b.desc(), skeptic, 3, 2, REFUTE_SCHEMA);
        if (v.isSurvived()) confirmed.add(b);
    }
}
```

---

## 동시성 설정

```java
WorkflowConcurrencyConfig.disabled();          // 전부 순차
WorkflowConcurrencyConfig.defaults();          // 활성, max(1, min(16, cores-2))
WorkflowConcurrencyConfig.enabled(8);          // 전역 풀 8
WorkflowConcurrencyConfig.enabled(8, 2);       // 전역 풀 8, 배치당 2
WorkflowConcurrencyConfig.forSharedPool(4);    // 동시 런 4개가 풀을 나눠 쓰는 프리셋
```

| 파라미터 | 의미 | 기본 |
|---------|------|------|
| `maxConcurrency` | 러너 전역 워커 풀 크기 | `DEFAULT_MAX_CONCURRENCY = 4` |
| `perBatchMax` | 한 `parallel` 배치가 동시에 점유할 수 있는 상한 | `maxConcurrency` |
| `maxNestingDepth` | 팬아웃 중첩 허용 깊이 | `DEFAULT_MAX_NESTING_DEPTH = 1` |
| `maxLiveFanoutThreads` | 살아있는 팬아웃 스레드 총량 방어선 | `max(maxConcurrency, 256)` |

`build()`가 검증하는 불변식:

- `perBatchMax ∈ [1, maxConcurrency]`
- `perBatchMax ^ maxNestingDepth <= maxLiveFanoutThreads`

**여러 대화/턴이 러너 하나를 공유**하는 서버 환경이라면 전역 풀은 크게 두고 `perBatchMax`를 작게
잡는다. 한 배치가 풀을 독점해 다른 런을 굶기는 것을 막는 2단 구조다.

---

## 예산 (WorkflowBudget)

```java
WorkflowBudget.defaults();                 // 에이전트 1000개 (DEFAULT_MAX_AGENTS)
WorkflowBudget.ofAgents(50);
WorkflowBudget.of(50, 1_000_000);          // + 토큰 상한
WorkflowBudget.of(50, 1_000_000, 5.0);     // + 비용 상한(USD)
```

- 에이전트 수 상한은 **폭주 루프 방지선**이다. 정상 워크플로가 닿을 값이 아니다.
- 토큰/비용 상한은 **옵트인**이며 **사후(post-hoc) 집행**이다. 스텝이 끝난 뒤 누적치를 확인하므로
  대략 `perBatchMax × 스텝당 토큰` 만큼 초과할 수 있다. 하드 컷오프가 아니다.
- 초과 시 `WorkflowBudgetExceededException`이 던져지고 **런 전체가 실패**한다. 개별 스텝 실패처럼
  `null`로 격리되지 않는다.

예산에 맞춰 깊이를 조절하는 루프를 만들 때는 상한을 넘기기 전에 스스로 멈추도록 짠다.

---

## 백그라운드 실행과 재개

### RunId

```java
RunId.from("review");                  // run:review
RunId.from("review", "pr-1234");       // run:review:pr-1234
RunId.of("run:review:pr-1234");        // 문자열 파싱
```

형식은 `run:<scriptName>[:<discriminator>]`. 각 세그먼트는 공백이 아니어야 하고 `:`를 포함할 수 없다.

**RunId의 결정성이 두 기능을 지탱한다:**

1. **재개** — 같은 RunId로 다시 실행하면 `StepResultCache`에 저장된 완료 스텝이 즉시 히트한다.
2. **멱등 제출** — 같은 RunId의 백그라운드 런이 이미 떠 있으면 중복 실행하지 않고 합류한다.

`WorkflowRunner.DEFAULT_RUN_ID`(= `RunId.from("run")`)는 **일회성**이며 스텝을 캐시하지 않는다.
인자 없는 `run(script)` 오버로드가 이 id를 쓴다. 재개가 필요하면 반드시 자기 RunId를 준다.

### 포그라운드 vs 백그라운드

```java
// 블로킹
String result = runner.run(script, RunId.from("review", "pr-1234"));

// 논블로킹
RunHandle<String> handle = runner.runInBackground(script, RunId.from("review", "pr-1234"));
handle.runId();
handle.isDone();
String r = handle.await(Duration.ofMinutes(10));
```

> `handle.future()`는 **방어적 복사본**이다. 이 future를 cancel해도 런은 멈추지 않는다.
> 실제 중단은 `runner.stop(runId)`다. 또한 타입이 있는 결과는 **런을 소유한 노드에서만** 얻을 수 있다.

### 제어 평면

`WorkflowRunController` (러너가 구현):

```java
boolean stopped = runner.stop(runId);              // 협조적 취소, 이 노드에 한정
List<WorkflowRun> runs = runner.list(RunQuery.all());
Optional<WorkflowRun> one = runner.status(runId);
```

`RunQuery` 조합:

```java
RunQuery.all();
RunQuery.byState(WorkflowRunState.RUNNING);
RunQuery.byAgentRuntime(contextId);
RunQuery.builder().state(...).owner(...).contextId(...).build();
```

`WorkflowRun` 조회 필드: `getRunId()`, `getScriptName()`, `getState()`, `getStartTime()`,
`getEndTime()`, `getOwner()`, `getAgentRuntimeId()`, `getLastHeartbeat()`.

상태는 `PENDING → RUNNING → COMPLETED | FAILED | KILLED` 이며 `isTerminal()`로 종료 여부를 본다.

### 재개 캐시

```java
StepResultCache cache = WorkflowRunners.inMemoryStepResultCache();
```

- 계약: `load` / `save` / `evict`. **절대 예외를 던지지 않는다** — 캐시 장애가 런을 죽이면 안 된다.
- `COMPLETED` 스텝만 저장된다. 실패·취소 스텝은 재실행된다.
- `nonCacheable(true)` 또는 `isolate(true)` 태스크는 저장되지 않는다.
- 기본값은 `StepResultCache.NO_OP` — 명시적으로 주지 않으면 재개가 동작하지 않는다.

멀티 인스턴스에서 재개를 원하면 `StepResultCache`와 `RunStore`를 공유 저장소 구현으로 교체한다
(프로젝트 설계 원칙: 상태 보유 컴포넌트는 저장소를 인터페이스로 분리).

### 백그라운드 실행기 설정

```java
WorkflowBackgroundConfig.defaults();
WorkflowBackgroundConfig.of(4);            // 동시 런 4
WorkflowBackgroundConfig.of(4, 100);       // + 큐 용량 100
WorkflowBackgroundConfig.builder()
        .maxConcurrentRuns(4)
        .queueCapacity(WorkflowBackgroundConfig.UNBOUNDED_QUEUE)
        .shutdownDrain(Duration.ofSeconds(10))   // 기본 5초
        .build();
```

---

## 이벤트 싱크

```java
public interface WorkflowEventSink {
    void onPhase(String title);
    void onLog(String message);
    void onAgentStarted(AgentTask task);
    void onAgentCompleted(AgentTask task, AgentStepResult result);
}
```

기본값은 `WorkflowEventSink.NO_OP`.

> **스레드 안전 필수**: `onAgentStarted`/`onAgentCompleted`는 **워커 스레드에서 동시 호출**된다.
> 내부 가변 상태를 가진 싱크라면 반드시 thread-safe해야 한다. 콘솔에 바로 쓰는 싱크도
> 줄이 섞이지 않게 직렬화하는 편이 좋다.

---

## 워크트리 격리

파일을 변조하는 스텝을 병렬로 돌려야 할 때만 쓴다.

```java
AgentTask.builder().subagent(migrator).goal(...).isolate(true).build();
```

- 각 스텝이 **자기 git 워크트리**에서 돈다. 비용이 있다(스텝당 셋업 + 디스크).
- 변경이 없으면 워크트리는 자동 정리된다.
- `isolate(true)`는 캐시 불가다 (부수효과 재생 불가).
- **`worktreeFactory`를 주입하지 않은 러너에서 `isolate` 스텝을 만나면 런 치명적 실패(C30)** 다.
  옵션에 `WorktreeEnvironmentFactory`를 반드시 넣는다.

병렬 스텝이 서로 다른 파일만 건드린다면 격리는 불필요하다. 같은 파일을 다투는 경우에만 켠다.

---

## 라이프사이클과 소유권 규칙

| 규칙 | 이유 |
|------|------|
| `WorkflowRunner`는 **application-scoped** 로 두고 재사용한다 | 워커 풀을 런마다 만들면 누수 |
| 러너는 `SubagentExecutionManager`와 `baseEnv`를 **빌려 쓴다. 절대 닫지 않는다** | 소유자는 호출자 |
| 호출당 러너를 만들었다면 **반드시 닫는다** (try-with-resources) | 팬아웃 풀이 런마다 남는다 |
| `RunHandle.future()` 취소는 런을 멈추지 않는다 → `stop(runId)` | future는 방어적 복사본 |
| 백그라운드 런은 호출 턴의 컨텍스트/주체/취소 신호를 **상속하지 않는다** | 러너 자체의 base 환경에서 돈다 |

코어 내부 예시(`WorkflowTool`)를 보면 포그라운드 경로는 호출당 러너를 만들고 즉시 닫는다:

```java
try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, env)) {
    return ToolResult.success(runner.run(script(...)));
}
```

반면 백그라운드 경로는 주입받은 컨텍스트 스코프 러너를 재사용한다. 이 분리를 그대로 따르면 된다.

---

## 함정 체크리스트

- [ ] `parallel`/`pipeline` 결과에 `null`이 올 수 있다는 것을 코드가 처리하는가?
- [ ] 배리어(`parallel`)를 정말 써야 하는가, `pipeline`이면 충분한가?
- [ ] 코드가 읽어야 하는 결과에 `resultSchema`를 붙였는가?
- [ ] `isSuccess()`만 보고 `isComplete()`를 놓치지 않았는가?
- [ ] 재개가 필요한데 `stepResultCache`를 빼먹지 않았는가? (기본은 NO_OP)
- [ ] 재개를 원하면서 `DEFAULT_RUN_ID`를 쓰고 있지 않은가? (캐시 안 됨)
- [ ] 공유 러너에서 `perBatchMax`를 잡았는가?
- [ ] 이벤트 싱크가 thread-safe한가?
- [ ] `isolate(true)`를 쓰면서 `worktreeFactory`를 주입했는가?
- [ ] 호출당 만든 러너를 닫는가? 빌려온 매니저를 닫지 않는가?
- [ ] `loopUntilDry` 계열 루프의 dedup 기준이 "확정본"이 아니라 "본 적 있는 것"인가?
- [ ] 커버리지를 잘라냈다면(top-N, 샘플링) `ctx.log`로 무엇을 버렸는지 남겼는가?

---

## 관련 문서

- [Workflow CLI 가이드](workflow-cli-guide.md) — aimon-cli 사용자 관점 (`Workflow`/`WorkflowJs`, `/runs`)
- [서브에이전트 개발 가이드](../subagent/subagent-development-guide.md) — `Subagent.builder()` 정의·등록
- [Tool 개발 가이드](../tool/tool-development-guide.md) — 워크플로를 도구로 노출할 때
- [애플리케이션 임베딩 가이드](../../getting-started/embedding-agent-in-application.md) — 컨텍스트/스코프 조립
- [중단 가능한 도구 가이드](../agent-execution/interruptible-tools-guide.md) — 취소 신호 전파
