# 포그라운드 Task 병렬 실행 — 전용 스레드 디스패치 (`DEDICATED_THREAD`)

> Status: **IMPLEMENTED** (2026-10-20). 기준 커밋 `c2fa8fb8` — 본문의 `file:line` 은 그 트리를 가리키고, 경로는 따로
> 적지 않으면 `modules/aimon-core/src/main/java/at/aimon/core/` 아래다. 구현이 본문과 다른 자리는
> [§9](#9-구현이-이-설계와-갈라진-자리), 남은 항목의 정본은
> [`../../backlog/parallel-foreground-task-open-items.md`](../../backlog/parallel-foreground-task-open-items.md) 다.
> `design/` 은 번역 대상이 아니므로 번역본은 없다([`documentation-guide.md` §5.1](../../project/documentation-guide.md#51-무엇을-번역하는가--디렉토리로-정한다)).
>
> 범위: `aimon-core` — `at.aimon.core.agent.tool` (선언 · 설정 · 디스패처 데코레이터), `at.aimon.core.tools.task`
> (`TaskTool` 선언), `at.aimon.core.tools` (포크 접근자), `at.aimon.core.subagent[.execution]` (포크 깊이 ·
> task id 전달), `at.aimon.core.toolinvocation.approval` (승인 질문 조정), `at.aimon.core.agent.impl.orca`
> (배선 · harvest 경로) · `aimon-workflow-graaljs` (깊이 한 줄) · `aimon-cli` (렌더링 라벨) · 문서.
> `aimon-bootstrap` / `aimon-spring-boot-starter` 는 코드 변경 없음 (§8 Q4).

---

## 1. 문제 재진술

한 LLM 응답에 포그라운드 `Task`(`run_in_background=false`) 호출이 여러 개 담겨 와도 지금은 전부 **순차**로
돈다. `OrcaAgentExecutor` 의 기본 디스패처는 `DefaultParallelToolDispatcher.sequential()` 이고
(`agent/impl/orca/OrcaAgentExecutor.java:370`), 병렬 디스패처가 주입되어도 게이트
`shouldParallelize` (`agent/tool/DefaultParallelToolDispatcher.java:297-313`) 는 배치의 **모든** 도구가
`CONCURRENT_SAFE` 이며 interrupt 가 `NON_INTERRUPTIBLE | COOPERATIVE` 일 때만 통과시키는데, `TaskTool` 은
`getConcurrencyBehavior()` 를 오버라이드하지 않아 `SEQUENTIAL` 이고 interrupt 는 `EXTERNALLY_TERMINATED` 다
(`tools/task/TaskTool.java:288-291`). 그래서 서브에이전트 셋을 띄우면 벽시계는 세 실행의 합이 된다. 공유 워커
풀에 그냥 올릴 수 없는 이유는 `runForeground` 가 `toolThread::interrupt` terminator 를 등록하기 때문이다
(`:618-620`): 늦게 도착한 interrupt 가 풀 워커의 **다음** 작업에 걸린다(§6.7). 백그라운드 풀을 나눠 쓸 수 없는
이유는 §2.4 C.

목표: **배치 안의 연속된 포그라운드 `Task` 구간을 서브에이전트 1개당 재사용하지 않는 전용 스레드에서 병렬로
돌리고, 부모 스레드는 join 뒤 원래 `tool_use` 순서로 결과를 재조립한다. 설정을 켜지 않으면 기존 동작이 바이트
단위로 같아야 한다(§3.16).** 취소 전파 · 승인 프롬프트 · 훅 · 트레이싱 · 예산 · 중첩 상한의 아홉 항목은 코드로
확인했다(§6).

---

## 2. 선택한 접근과 기각한 대안

### 2.1 선택 — 도구가 `DEDICATED_THREAD` 를 선언하고, 디스패처 **데코레이터**가 연속 구간을 전용 스레드로 편다

1. **선언**: `ConcurrencyBehavior` 에 세 번째 값 `DEDICATED_THREAD` 를 더하고 `TaskTool` 이 그것을 선언한다.
   뜻은 "같은 선언을 한 이웃 도구와 동시에 돌아도 되지만, 호출마다 새로 만들고 다시 쓰지 않는 **자기 스레드**를
   받아야 한다"이다. 기존 두 값과 게이트는 손대지 않는다 — `DEDICATED_THREAD` 는 `CONCURRENT_SAFE` 가 아니므로
   기존 게이트에서는 `SEQUENTIAL` 과 똑같이 떨어진다(§3.16 ①).
2. **조정**: 새 `DedicatedThreadDispatcher` 가 기존 `ParallelToolDispatcher` 를 감싼다. 배치를 "`DEDICATED_THREAD`
   연속 구간"과 "나머지 구간"으로 자르고, 앞의 것은 자기가 전용 스레드로 펴며, 뒤의 것은 감싼 디스패처에
   **그대로** 넘긴다(기존 규칙 — 순차, 또는 전부 `CONCURRENT_SAFE` 면 공유 풀). 구간 사이 순서는 join 으로
   보존된다.
3. **상한**: 전용 스레드 수는 `DedicatedThreadConfig.maxThreads` 짜리 세마포어 하나가 **런타임 조립 전체**
   (한 `OrcaAgentExecutorFactory` 가 만든 메인 실행기 + 포크 실행기)에 걸쳐 묶는다. 백그라운드 풀과는 완전히
   따로다. 허가를 **블로킹으로 기다리지 않는다** — 남은 허가가 없으면 그 호출은 오늘처럼 호출 스레드에서 inline
   으로 돈다(백프레셔 = 호출자가 자기 속도로 느려짐). 그래서 중첩 fan-out 이 어떤 깊이로 쌓여도 교착이 없다(§6.6).
4. **적용 범위**: 메인 실행기와 포크 실행기 **둘 다**에 설치하되, 포크 깊이 `forkDepth` 가
   `DedicatedThreadConfig.maxForkDepth` 미만인 실행만 편다. 기본 `maxForkDepth = 1` 이므로 기본값으로는 포크
   조상이 없는 실행(세션의 턴 · 루틴 · rewake 리플레이)만 fan-out 하고 포크는 오늘처럼 순차다.

```
LLM batch:   [ Read | Read | Task | Task | Task | Grep | Task ]
                 \______/   \________________/   \__/   \__/
              segment A        segment B        seg C   seg D
             (delegate)     (dedicated x3)   (delegate) (dedicated x1)

caller thread:  onStarted(A..) -> delegate.dispatch(A) -> join
                onStarted(T1) spawn d1 ; onStarted(T2) spawn d2 ; onStarted(T3) spawn d3
                join d1, d2, d3  (input order)
                delegate.dispatch(C) -> join
                onStarted(T4) spawn d4 ; join d4
results:        A1 A2 T1 T2 T3 C1 T4      (= tool_use order)
```

### 2.2 왜 "선언 + 데코레이터" 인가

- **선례와 같은 모양이다.** `parallel-execution.md` §8.1 은 "도구가 선언하고 별도 조정자가 그것을 어떻게 쓸지
  정한다"를 원칙으로 못박았고, 실행기가 도구 이름 화이트리스트를 갖는 안을 OCP 위반으로 기각했다. 이 설계는
  같은 원칙의 세 번째 값이다. `InterruptBehavior.THREAD_INTERRUPT` 가 "프레임워크가 써도 되는 지렛대"를
  이름으로 쓰듯, `DEDICATED_THREAD` 도 도구가 **동의하는 메커니즘**을 이름으로 쓴다.
- **기존 디스패처를 한 줄도 고치지 않는다.** `DefaultParallelToolDispatcher` 는 425줄짜리 풀 · 2-tier 캡 ·
  종료 경합 처리이고 테스트가 두텁다. 데코레이터는 그 위에 얹히며, 기능이 꺼져 있으면 설치조차 되지 않는다
  (§3.10). 켜져 있어도 배치에 `DEDICATED_THREAD` 도구가 없으면 배치 전체를 **한 번의 호출**로 그대로 넘긴다.
- **포크 실행기에는 풀 없는 조합을 줄 수 있다.** 오늘 factory 는 `DefaultSubagentExecutor` 에 병렬 디스패처를
  주입하지 않는다(`agent/impl/orca/OrcaAgentExecutorFactory.java:768-823`). 포크에는
  `new DedicatedThreadDispatcher(DefaultParallelToolDispatcher.sequential(), …)` 를 주어 **풀 경로는 오늘 그대로
  순차**로 두고 전용 스레드 fan-out 만 더한다.

### 2.3 전용 스레드가 interrupt 문제를 푸는 방식

`TaskTool.runForeground` (`:612-626`) 는 `Thread.currentThread()::interrupt` 를 per-invocation 레지스트라에
등록한다. 전용 스레드에서는 `currentThread` 가 바로 그 전용 스레드이므로 terminator 의 의미가 오늘과 **글자
그대로 같다**. 늦은 interrupt(`DefaultTerminatorRegistrar.fireAll` 이 lock 밖에서 `terminate()` 를 부르고
`unregister` 는 `fired` 면 즉시 반환 — `agent/interrupt/DefaultTerminatorRegistrar.java:56-65, 84-97`)는 그
전용 스레드에 떨어지고, 그 스레드는 future 를 채우고 허가를 돌려준 뒤 **죽는다**. 재사용이 없으니 다음 작업이
없고, 부모 스레드에는 terminator 가 등록된 적이 없다. 스레드를 재사용하지 않는 이유가 이것이고,
`interrupt.md` §8.5 의 전제("워커에서 관측되는 플래그는 그 워커에 대한 실제 인터럽트뿐")가 깨지지 않는 이유다.

### 2.4 기각한 대안

| 대안 | 기각 사유 |
|------|----------|
| **A. `TaskTool` 을 `CONCURRENT_SAFE` 로 선언하고 공유 풀을 쓴다** | 게이트가 `EXTERNALLY_TERMINATED` 를 명시적으로 뺀다(`DefaultParallelToolDispatcher.java:320-322`). 빼는 이유가 바로 §2.3 의 늦은 interrupt 다. 풀 워커는 재사용되므로 `toolThread::interrupt` 가 다음 작업에 걸린다 |
| **B. 디스패처가 도구 이름 `Task` 와 `run_in_background` 를 직접 본다** | `agent.tool` 이 `tools.task` 를 알게 된다(OCP 위반, §8.1 선례와 충돌). 외부 모듈의 서브에이전트형 도구(`Workflow`, GraalJS)는 혜택을 못 받는다 |
| **C. 백그라운드 풀(`SubagentBackgroundConfig`)을 나눠 쓴다** | 긴 백그라운드 작업 뒤 큐에서 대기하며 부모 턴이 멈춘다. 또 그 풀은 기본 큐 무제한이라 "대기 중"이 보이지 않는다 |
| **D. 상한 초과 시 세마포어에서 블로킹 대기 (깊이별 사다리)** | 한 세마포어를 깊이와 무관하게 블로킹으로 쓰면 자식이 허가를 쥔 채 손자 허가를 기다려 교착한다. 깊이별 세마포어로 풀 수는 있으나 교착 없음이 **깊이 라벨이 정확하다**는 데 의존한다 — `SubagentLaunchContext` 를 만드는 곳이 다섯(§3.14)이고 하나라도 라벨을 빠뜨리면 교착 조건이 되살아난다. 비블로킹 + inline fallback 은 라벨이 틀려도 교착이 **불가능**하다(§6.6). 수용 기준의 "대기" 와의 차이는 §8 Q1 |
| **E. 메인 실행기에만 적용, 포크는 영구 순차** | 깊이 플러밍이 빠져 단순하지만 `Plan → Explore ×3` 같은 쓰임을 영영 막는다. 기본값(`maxForkDepth=1`)이 사실상 E 와 같으므로, E 가 주는 안전은 그대로 갖고 확장은 설정 한 줄로 열어 둔다 |
| **F. `DefaultParallelToolDispatcher` 안에 직접 구현** | 풀 · 캡 · 종료 경합과 전용 스레드 로직이 한 클래스에 섞인다. 포크에 "풀 없음 + 전용 스레드" 조합을 주려면 어차피 두 축을 분리해야 한다 |
| **G. `ToolConcurrencyConfig` 에 필드를 더한다** | `enabled` 가 풀 경로의 마스터 스위치인데(`agent/tool/ToolConcurrencyConfig.java:105-107`) 전용 스레드는 풀과 독립으로 켜고 끌 수 있어야 한다. 두 스위치를 한 객체에 두면 `applyExecutorOverrides` 의 "enabled 면 풀 생성" 조건(`:725-727`)을 갈라야 하고 설명이 꼬인다 |
| **H. `Tool` 에 호출별 술어 `wantsDedicatedThread(ToolUse)` 를 둔다** | 백그라운드 `Task` 호출은 즉시 반환하므로 전용 스레드 하나를 잠깐 쓰는 비용이 무시할 만하다(§8 Q2). 호출별 술어는 `ToolPermissionSubjectAware` 같은 두 번째 선언 채널을 또 만든다 |
| **I. 같은 파일 동시 편집을 프레임워크 락으로 막는다** | 포크마다 `ExecutionEnvironment` 가 다를 수 있어(워크플로 브랜치 — `workflow/impl/DefaultWorkflowContext.java:200`) 경로 키가 환경을 넘어 같은 파일을 뜻하지 않는다. 이미 있는 stamp 가드가 lost update 를 명시적 거절로 바꾼다(§6.5) |
| **J. 이름 `SubagentForegroundConfig` (`at.aimon.core.subagent`)** | 설정을 읽는 쪽은 `agent.tool` 의 디스패처다. `agent.tool → subagent` 는 `PackageDependencyArchitectureTest` 가 동결한 순환 쌍 `agent <-> subagent` 안에 새 변을 긋는 일이고(`:98-101`), 이름도 메커니즘(전용 스레드)이 아니라 소비자 하나(서브에이전트)를 가리켜 거짓말한다. 사용자 문서에서 "포그라운드 Task 병렬 실행" 으로 부르고 타입 이름은 메커니즘을 따른다 |
| **K. `Task` 도구 설명에 "동시에 돌 수 있다" 를 적는다** | 설명은 매 요청의 `tools` 정의로 LLM 에 가므로 기능이 꺼진 조립에서도 요청 바이트가 달라지고, 그때는 거짓이 된다. `TaskTool` 은 설정을 모르므로 조건부로 만들 스위치도 없다(§3.6) |
| **L. 승인 게이트를 스트라이프 락(예: 32개)으로 직렬화한다** | `channel.resolve` 가 사람의 답을 기다리는 동안 락을 쥐므로, 같은 스트라이프에 떨어진 **무관한** 세션의 모든 게이트 호출이 멈춘다(쌍당 1/32) — 새 liveness 결함이다. 질문 단위 in-flight 조정을 택했다(§3.12) |
| **M. 포크 task id 속성을 무조건 싣는다** | 기능 off 에서 모든 포크의 훅 · 도구가 새 키를 본다. 기능이 켜진 manager 만 싣는다(§3.8) |
| **N. 포크 task id 속성을 `runResolvedSubagent` 의 요청에만 싣는다** | `fireSubagentStart` 는 그보다 **먼저** `runExecute` 에서 스포너의 속성으로 불리므로 `[Subagent]` 줄이 최상위 포크에서도 라벨을 받지 못하고, 중첩 포크의 Start/Stop 은 **부모 포크의** id 를 싣는다. manager 의 두 진입점에서 싣는다(§3.8) |
| **O. 승인 프롬프트에 `[executionId]` 접두를 모든 포크에 무조건 붙인다** | 기능 off 에서 프롬프트 문자열이 달라진다. task id 속성이 있을 때만 `[task …]` 를 붙인다(§3.12) |
| **P. harvest 꼬리를 디스패처로 넘기는 것을 비조건부로 한다** | "poison 은 두 경우뿐이라 off 에서도 꼬리가 순차로 떨어진다" 는 논증은 응답의 모든 `tool_use` 가 `onToolUseReady` 를 거쳤다는 공급자 가정 위에 있다. 그 가정이 깨지면 off 에서 풀 디스패처가 꼬리를 병렬화한다(§3.11) |

---

## 3. 구체 변경 (파일·모듈별)

### 3.1 `agent/tool/ConcurrencyBehavior.java` — `DEDICATED_THREAD`

```java
/**
 * 같은 값을 선언한 이웃 도구와 동시에 돌아도 되지만, 호출마다 새로 만들고 다시 쓰지 않는 자기 스레드를 받아야
 * 한다. 실행 스레드에 묶인 terminator 를 등록하는 도구(THREAD_INTERRUPT / EXTERNALLY_TERMINATED)가 긴 블로킹
 * 작업(서브에이전트 실행)을 감쌀 때 쓴다. 공유 워커 풀에는 절대 올라가지 않는다: 기존 게이트에서는 SEQUENTIAL
 * 과 같이 취급된다.
 */
DEDICATED_THREAD
```

`Tool.getConcurrencyBehavior()` javadoc(`agent/tool/Tool.java:205-215`)의 "배치는 모든 도구가 CONCURRENT_SAFE
일 때만 병렬화된다" 문장에 "`DEDICATED_THREAD` 도구는 데코레이터가 따로 편다" 한 줄을 보탠다. eager 적격 판정
`DefaultParallelToolDispatcher.isEagerEligible` 은 `CONCURRENT_SAFE` 만 보므로(`:206-207`,
`StreamingToolScheduler.java:100` 이 그것을 부른다) `DEDICATED_THREAD` 는 eager 대상이 아니다 — 변경 없음.

### 3.2 `agent/tool/DedicatedThreadConfig.java` (신규, 불변 + 빌더)

| 필드 | 기본값 | 검증 | 뜻 |
|------|--------|------|----|
| `enabled` | `false` | — | 마스터 스위치. `false` 면 factory 가 데코레이터도, §3.8 의 manager 플래그도 설치하지 않는다 |
| `maxThreads` | `4` | `>= 1` | 동시에 살아 있을 수 있는 전용 스레드 수. 호출 스레드(inline lane)는 **세지 않는다** |
| `maxForkDepth` | `1` | `>= 1` | `forkDepth < maxForkDepth` 인 실행만 편다. `1` = 포크 조상이 없는 실행(세션의 턴 · 루틴 · rewake 리플레이)만 |

`disabled()` · `enabled(int maxThreads)` · `builder()` — `ToolConcurrencyConfig` 와 같은 모양. `toString` 포함.
`SubagentBackgroundConfig` 처럼 `availableProcessors` 에 묶지 않는다: 전용 스레드는 거의 내내 LLM 응답을
기다리는 I/O 대기라 CPU 수가 적절한 상한이 아니다.

### 3.3 `agent/tool/DedicatedThreadPermits.java` (신규)

설정에서 만든 **공유 런타임 객체**. `DefaultSubagentExecutionManager.newBackgroundExecutor(config)` 가 설정에서
풀을 만들 듯, `DedicatedThreadPermits.of(config)` 가 세마포어와 스레드 팩토리를 만든다.

- `boolean tryAcquire()` — 비블로킹. `void release()`.
- `Thread newThread(Runnable)` — daemon, 이름 `tool-dedicated-<n>`. **호출마다 새 스레드**, 풀 없음.
- `int availablePermits()` — 테스트 · 진단용.

닫을 것이 없다(스레드는 호출 안에서 join 되고, 세마포어는 자원이 아니다). 그래서 `AutoCloseable` 이 아니며
`AimonStack` teardown 에 등록할 것도 없다.

패키지: 이 클래스와 §3.5 의 `DedicatedThreadDispatcher` 는 구체 구현이지만 `agent.tool` 에 둔다 — `agent.tool` 에는
`impl` 하위 패키지가 없고 `DefaultParallelToolDispatcher` · `DefaultToolRegistry` 같은 구체 구현이 전부 그 바로 아래에
있다. 이 둘만 `impl` 로 보내면 오히려 관례를 깬다.

### 3.4 `agent/tool/DispatchOptions.java` (신규) + `ParallelToolDispatcher` 오버로드

디스패처는 호출마다 "이 실행이 몇 단계 포크인가"를 알아야 하는데(§2.1 ④) 시그니처에 컨텍스트가 없다. 포크
실행기 `DefaultSubagentExecutor` 는 깊이와 무관하게 **한 인스턴스**가 모든 포크를 돌리므로 인스턴스 생성 시점에
정할 수도 없다. 그래서 호출별 옵션을 더한다.

```java
public final class DispatchOptions {           // 불변 + 빌더, SubagentBackgroundExecutionOptions 선례
    public static DispatchOptions root();      // forkDepth = 0
    public int getForkDepth();
}

// ParallelToolDispatcher — default 메서드이므로 외부 구현체 무영향
default List<ToolUseResult> dispatch(List<ToolUse> toolUses, ToolRegistry registry, DispatchOptions options,
        Function<ToolUse, ToolUseResult> runner, Consumer<ToolUse> onStarted,
        BiConsumer<ToolUse, ToolUseResult> onCompleted) {
    return dispatch(toolUses, registry, runner, onStarted, onCompleted);   // 깊이를 모르는 구현은 무시
}
```

`OrcaAgentExecutor` 는 계속 5-인자 메서드를 부른다(`OrcaAgentExecutor` 가 돌리는 실행은 항상 깊이 0 — 세션의 턴 ·
루틴 · rewake 리플레이 모두 포크 조상이 없다). `DefaultSubagentExecutor` 만 6-인자를 부른다(§3.9).

### 3.5 `agent/tool/DedicatedThreadDispatcher.java` (신규) — 알고리즘

`ParallelToolDispatcher, AutoCloseable`. 생성자 `(ParallelToolDispatcher delegate, DedicatedThreadConfig config,
DedicatedThreadPermits permits)`. eager 메서드 넷과 `close()` 는 delegate 에 위임(delegate 가 `AutoCloseable` 일
때만 닫는다).

```java
List<ToolUseResult> dispatch(toolUses, registry, options, runner, onStarted, onCompleted) {
    if (!config.isEnabled() || registry == null || options.getForkDepth() >= config.getMaxForkDepth()
            || toolUses.stream().noneMatch(t -> isDedicated(t, registry))) {
        return delegate.dispatch(toolUses, registry, runner, onStarted, onCompleted);   // (1) 바이트 동일 경로
    }
    final List<ToolUseResult> results = new ArrayList<>(toolUses.size());
    for (Segment segment : segments(toolUses, registry)) {                              // (2) 최대 연속 구간
        results.addAll(segment.dedicated
                ? runDedicated(segment.toolUses, runner, onStarted, onCompleted)
                : delegate.dispatch(segment.toolUses, registry, runner, onStarted, onCompleted));
    }
    return results;
}
```

`isDedicated(toolUse)` = `registry.findByName(name)` 이 있고 `getConcurrencyBehavior() == DEDICATED_THREAD`.
미등록 이름은 `false`(기존 게이트와 같은 보수성).

`runDedicated(segment)`:

```java
for (ToolUse toolUse : segment) {                       // 입력 순서
    safeOnStarted(onStarted, toolUse);                  // 호출 스레드, 입력 순서 — 기존 계약 그대로
    if (permits.tryAcquire()) {                         // 새 스레드: future.complete(runAndComplete(...)) —
        slots.add(Slot.dedicated(toolUse, future, permits.newThread(...)));   // onCompleted 는 전용 스레드, finally 에서 release
    } else {
        slots.add(Slot.inline(toolUse, runAndComplete(runner, onCompleted, toolUse)));   // (3) 호출 스레드, 오늘 그대로
    }
}
for (Slot slot : slots) {                               // (4) 입력 순서로 join
    // inline 이면 그 결과. 아니면 future.get() 을 끝날 때까지 —
    //   InterruptedException (5): callerInterrupted = true, 미완 전용 스레드 전부 thread.interrupt(), 계속 대기
    //   ExecutionException: ToolUseResult.error(toolUse.getId(), "Tool execution error: " + cause)
}
if (callerInterrupted) {
    Thread.currentThread().interrupt();                 // (6) 루프 체크포인트가 승격하도록 되돌려 둔다
}
return results;
```

짚어 둘 것.

- (3) **inline fallback 은 오늘의 동작이다.** 허가가 없으면 그 호출만 호출 스레드에서 돈다 — terminator 도
  오늘처럼 부모 스레드를 가리키고, 늦은 interrupt 도 오늘처럼 `isInterrupted` 체크포인트가 흡수한다
  (`OrcaAgentExecutor.java:2061-2071`). 한 배치에서 동시에 도는 포그라운드 서브에이전트는 최대
  `maxThreads + 1`(호출 스레드 lane) 이다.
- (5)(6) 기존 풀 경로는 `join()` 으로 **비인터럽트** 대기한다(`DefaultParallelToolDispatcher.java:341-350`).
  여기서는 호출 스레드가 interrupt 되면 아직 끝나지 않은 전용 스레드에 같은 지렛대(`Thread.interrupt`)를 걸고
  계속 기다린 뒤, 끝나면 플래그를 **되돌려** 둔다. 되돌리는 이유는 `isInterrupted(coordinator)` 가 플래그를
  소비해 신호로 승격하는 유일한 지점이기 때문이다(`interrupt.md` §8.2). 자식에게는 협력 신호가 주 경로이고
  (§6.7) 이 interrupt 는 부가다.
- 스레드 안의 예외는 `runAndComplete` 가 `runSafely` 와 같은 격리를 하므로 `completeExceptionally` 는 `Error`
  계열에만 닿는다. join 쪽에서 에러 결과로 바꾼다 — 배치는 깨지지 않는다.
- `onCompleted` 는 전용 스레드에서 완료 순으로 — `ParallelToolDispatcher` javadoc `:50-52` 의 기존 계약과 같다.
  `runner` 가 돌아온 **뒤** 전용 스레드에서 돌므로, 늦은 interrupt 가 `onCompleted`(`emitToolResultReady`)에
  떨어질 수 있다 — 순차 경로가 오늘 부모 스레드에서 갖는 것과 같은 창이다(§6.7 잔여 창).

### 3.6 `tools/task/TaskTool.java`

- `getConcurrencyBehavior()` 오버라이드 → `DEDICATED_THREAD` (javadoc 에 §2.3 의 근거). 결정이 바뀌지 않는 이유는
  §3.16 ①.
- `buildLaunchContext` (`:525-586`) 에 `.forkDepth(ForkAccess.childDepthOf(context))` 한 줄.
- **도구 설명은 바꾸지 않는다.** 설명은 매 요청의 `tools` 정의로 LLM 에 가므로, 동시 실행을 적으면 기능이 꺼진
  조립에서도 요청 바이트가 달라지고 그때는 사실도 아니다(§2.4 K). 같은 파일 지침(§6.5)은 사람에게 주는 가이드
  두 곳(§3.15)에 두고, 모델에게 주고 싶은 운영자는 에이전트 지시문이나 시스템 프롬프트에 적는다 — 그것이 설정과
  같이 켜고 끌 수 있는 자리다.
- `runForeground` 는 **무변경** — `Thread.currentThread()` 가 전용 스레드가 되는 것으로 충분하다.

### 3.7 `tools/ToolContextKeys.java` + `tools/ForkAccess.java` (신규)

- `ToolContextKeys.FORK_DEPTH` — `ToolContextKey<Integer>` (`"forkDepth"`). javadoc: "이 실행이 몇 단계 포크인가.
  포크 조상이 없는 실행에는 없다(= 0). 포크는 자기 깊이를 싣고, 그 값은 스포너의 깊이 + 1 이다.
  `CALLER_ALLOWED_TOOLS` 처럼 실행마다 다시 실어 중첩을 어떤 깊이로도 따라간다."
- `ForkAccess` (신규, `CallerAllowedTools.of` / `InvokingSessionAccess.idToPropagate` 와 같은 자리의 같은 모양):
  - `int depthOf(ToolContext)` — 없으면 0. `int childDepthOf(ToolContext)` — `depthOf + 1`.
  - `String TASK_ID_ATTRIBUTE = "aimon.subagent.task_id"` — 포크의 실행 속성 키(§3.8). 트레이싱 쪽의
    `aimon.subagent.received_task_id`(`SubagentTaskCompleted.RECEIVED_TASK_ID_ATTRIBUTE`, 완료 알림을 전달하는 턴이
    받은 **백그라운드** 작업의 id, 그 턴의 포크가 그대로 물려받는다)와 다르다 — 이 키는 실행 **자신의** task id 라
    포크 경계마다 바뀐다. `tools` 에 두는 이유: 읽는 쪽 `toolinvocation.approval`(§3.12)과 쓰는 쪽 `subagent`(§3.8)가
    **둘 다 이미** `at.aimon.core.tools` 를 import 한다(`toolinvocation/SingleToolInvoker.java:33-35`,
    `DefaultSubagentExecutionManager.java:76`). 그래서 `PackageDependencyArchitectureTest` 의 동결 목록(`:98-101`)에
    새 쌍이 생기지 않는다. `subagent` 에 두면 `toolinvocation -> subagent` 가 목록에 없는 새 순환이다.
  - `Optional<String> taskIdOf(Map<String, Object> executionAttributes)` / `taskIdOf(ToolContext)` — 값이
    문자열일 때만.

### 3.8 `subagent/SubagentLaunchContext.java` · `subagent/execution/SubagentExecutionRequest.java` · `subagent/DefaultSubagentExecutionManager.java`

- `SubagentLaunchContext.forkDepth(int)` — **기본값 1**. 포크는 포크이므로 아무도 안 적어도 최소 1 이다
  (`OrcaAgentRuntimeFactory.java:1160` 의 agent-scoped base 컨텍스트가 그 경우). `toBuilder()` 가 복사한다.
- `SubagentExecutionRequest.forkDepth(int)` — 기본값 1. `DefaultSubagentExecutionManager.runResolvedSubagent`
  (`:766`) 가 `launchContext.getForkDepth()` 를 복사한다.
- **포크 task id 실행 속성 — 기능이 켜졌을 때만, 그리고 manager 의 두 진입점에서 한 번.**
  `DefaultSubagentExecutionManager` 에 `withForkTaskIdAttribute()` 를 더한다(`withTracer` / `withTracePayloadPolicy`
  와 같은 사후 volatile 세터, `:546-563`). 켜진 manager 는 **`runExecute` (`:677`) 와 `executeInBackground`
  (`:1077`) 의 첫 줄**에서 런치 컨텍스트를 한 번 다시 만든다 —
  `launchContext.toBuilder().executionAttributes(withTaskId(launchContext.getExecutionAttributes(), taskId)).build()`
  (`toBuilder()` `:465`; 원본 맵은 불변일 수 있으니 복사 후 put) — 그리고 그 뒤의 모든 것이 다시 만든 컨텍스트를
  쓴다: `fireSubagentStart` (`:683` / `:1114`, 컨텍스트는 `launchContext.getExecutionAttributes()` 로 짓는다 —
  `:1048`), `runResolvedSubagent` 의 요청(`:766-767`), `fireSubagentStop` (`:1069`, 풀 거부 경로 `:1154` 포함).
  진입점에서 싣는 이유는 `fireSubagentStart` 가 요청을 만들기 **전에** 불리기 때문이다(§2.4 N). 그러면 세
  소비자가 같은 맵을 보고, 중첩 포크는 자기 진입점에서 자기 id 로 덮어쓴다. factory 는 `DedicatedThreadConfig.enabled`
  일 때만 켠다(§3.10, §3.16 ④).

  닿는 곳(`SubagentTaskCompleted.RECEIVED_TASK_ID_ATTRIBUTE` 와 같은 채널): 그 포크의 `SubagentStart/Stop` 컨텍스트,
  포크 안의 도구 훅 컨텍스트(`SingleToolInvoker.java:201, 226, 324, 347`), `OnStopContext`, 포크의
  `EXECUTION_ATTRIBUTES_KEY`(`DefaultSubagentExecutor.java:910`), 그 포크가 띄우는 포크의 스포너 속성. 닿지 **않는**
  곳: span(`received_task_id` 한 키만 명시적으로 읽는다 — `OrcaAgentExecutor.java:2215-2222`), 영속 `TaskResult`,
  `SubmitOptionsCodec`, 선언형 훅의 `${context.X}`(고정 필드 여섯), 셸 훅 stdin. 그래서 기존 트레이싱 테스트(ST-12
  포함)의 span 속성 집합은 변하지 않고, `environment.*` 접두 규칙(EE-102)과도 충돌하지 않는다.

### 3.9 `subagent/execution/DefaultSubagentExecutor.java`

- `createToolContext` (`:891-958`) 에 `builder.put(ToolContextKeys.FORK_DEPTH, request.getForkDepth())`.
  `EXECUTION_ID` (`:900`) 옆, write-once 는 아니다(포크 안 도구가 읽기만 한다). 무조건 싣는다 — §3.16 ③.
- `LoopContext.forkDepth` (빌더 필드 하나) — `execute` (`:391`) 가 `request.getForkDepth()` 를 넣는다.
- `executeToolUses` (`:1116-1127`) 의 `dispatch` 호출을 6-인자로:
  `parallelToolDispatcher.dispatch(toolUses, lc.sessionRegistry, DispatchOptions.builder().forkDepth(lc.forkDepth).build(), …)`.
  기본 `sequential()` 디스패처는 default 메서드로 5-인자에 위임하므로 동작 불변.

### 3.10 `agent/impl/orca/OrcaAgentExecutorFactory.java` — 배선

```java
public OrcaAgentExecutorFactory withDedicatedThreadConfig(DedicatedThreadConfig config);   // null 로 해제

// applyExecutorOverrides (:724) — 기존 풀 생성(:726) 뒤에
if (dedicatedThreadsEnabled()) {
    dispatcher = new DedicatedThreadDispatcher(
            dispatcher != null ? dispatcher : DefaultParallelToolDispatcher.sequential(),
            dedicatedThreadConfig, dedicatedThreadPermits());     // permits: factory 당 하나, lazy
}

// createDefaultSubagentExecutionManager (:768) — 포크 실행기(:778)에
if (dedicatedThreadsEnabled()) {
    subagentExecutor.withParallelToolDispatcher(new DedicatedThreadDispatcher(
            DefaultParallelToolDispatcher.sequential(), dedicatedThreadConfig, dedicatedThreadPermits()));
    manager.withForkTaskIdAttribute();                            // §3.8 — withSubagentTracing(...) 와 같은 자리
}
```

허가 객체가 **factory 당 하나**이므로 상한은 그 factory 가 만든 모든 실행기(메인 · 포크, 모든 세션)에 걸친다.
풀의 "`create(...)` 마다 자기 풀" 계약과 다른 점이며 javadoc 에 적는다. 두 설정이 모두 꺼져 있으면 (기본)
`executor.parallelToolDispatcher` 도 manager 의 플래그도 손대지 않는다 — 오늘과 동일.

`aimon-bootstrap` 의 `AimonStackBuilder` (`modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java:394-399`)
와 Spring `AimonProperties` 는 기존 `ToolConcurrencyConfig` 도 노출하지 않는다(grep 0건). 새 설정도 같은 수준
— factory 의 프로그램적 opt-in — 에 두고 노출은 후속으로 남긴다(§8 Q4).

### 3.11 `agent/impl/orca/OrcaAgentExecutor.java` — harvest 꼬리를 디스패처로

스트리밍 중첩이 켜져 있고 eager 접두가 있으면 `harvestEagerToolUses` (`:2485-2497`) 가 접두와 꼬리 **모두**에
대해 자기가 `emitToolUseStarted` (`:2489`) → 결과 → `emitToolResultReady` (`:2493`) 를 부르고, 이연된 꼬리는
호출 스레드에서 inline 으로 돌린다(`runToolOnCallingThread`, `:2504`). 그대로 두면 `[Read, Task, Task]` 에서
Read 가 eager 로 돈 iteration 은 `Task` 둘이 순차가 된다. 바꾼다.

- 첫 이연 인덱스 `k`(= poison 지점; prefix-safety 상 eager 집합은 접두다) **앞**까지는 오늘의 루프 그대로 —
  호출 스레드에서 입력 순서로 started → `joinEager` → ready.
- `k` 부터 끝까지의 꼬리는 **`parallelToolDispatcher.routesHarvestTail()` 이 참일 때만**
  `parallelToolDispatcher.dispatch(tail, sessionRegistry, runner, onStarted, onCompleted)` 로 **정확히 한 번** 넘긴다.
  콜백은 `:2418-2420` 과 같은 람다이고 그 뒤 꼬리의 이벤트는 디스패처가 낸다 — harvest 는 꼬리의 도구에 대해 이벤트를
  직접 내지 않으므로 중복도 누락도 없다. 계약은 같다(started 호출 스레드 입력 순서, ready 완료 순). 거짓이면
  오늘의 inline 루프 그대로.
- `ParallelToolDispatcher` 에 `default boolean routesHarvestTail() { return false; }` 를 더한다. 참을 돌려주는 것은
  `DedicatedThreadDispatcher` 뿐이다. 그래서 이 경로는 기능이 켜졌을 때만 바뀐다(§3.16 ⑩).

조건부로 두는 이유(§2.4 P): 스케줄러는 스트림 청크의 `TOOL_USE_READY` 로만 먹고(`OrcaAgentExecutor.java:3626`) 그
이벤트를 내는 것은 공급자 매퍼라, 첫 도구만 READY 를 내는 `LlmClient` 구현이 가능하다. 그러면 꼬리가 적격 도구로
시작해 기능 off 에서도 풀 디스패처가 병렬화한다. 기능 **on** 에서는 꼬리의 비-`DEDICATED_THREAD` 구간을
delegate(메인 실행기라면 풀 디스패처일 수 있다)가 기존 게이트로 판정하므로 오늘 inline 이던 `CONCURRENT_SAFE` 구간이
풀로 갈 수 있다 — opt-in 뒤의 동작이고 가이드에 적는다.

### 3.12 `toolinvocation/approval/SideEffectApprovalGate.java` — 같은 질문은 한 번만, 다른 질문은 서로 막지 않는다

`denialReason` (`:181-215`) 의 "lookup → 프롬프트 → remember" (`:199-206`) 는 지금 원자적이지 않다. 같은 세션
스코프의 포크 둘이 같은 도구를 동시에 쓰면 둘 다 miss 하고 둘 다 묻는다. 그리고 `TaskTool` 자신이
`SideEffectLevel`/`DestructiveBehavior` 를 오버라이드하지 않아 기본 `MUTATING`/`DESTRUCTIVE`(`Tool.java:238-240,
278-280`)이므로 **`Task` 호출 자체가** 게이트 대상이다 — 병렬 `Task` N개가 동시에 "Allow 'Task(...)'?" 를 N번
띄운다.

- **질문 단위 in-flight 조정.** 게이트에 `ConcurrentHashMap<String, CompletableFuture<…>> inFlight` 를 둔다. 키는
  `scopeKey + '\0' + toolName` — 기억(`store`)의 키와 같은 단위다.

  ```java
  // denialReason — 스코프 키가 있을 때 (remembered 가 있으면 오늘처럼 그대로)
  final CompletableFuture<Boolean> mine = new CompletableFuture<>();
  final CompletableFuture<Boolean> prior = inFlight.putIfAbsent(key, mine);
  if (prior != null) {                                   // 같은 질문이 이미 사람 앞에 있다
      // prior.get() — 인터럽트 가능 대기. InterruptedException: 플래그를 되돌리고 cancelledMessage 로 거절.
      // ExecutionException: fail-safe 로 allowed = false  (구현은 다르다 — §9.1 의 10)
  } else if (store.lookup(scopeKey, toolName).isPresent()) {
      inFlight.remove(key, mine); return remembered 와 같은 처리;   // 이겼지만 그 사이 답이 생겼다 (§9.1 의 1)
  } else {
      try {
          allowed = channel.resolve(promptFor(label, …)) == Decision.ALLOW;   // 취소된 프롬프트는 §9.1 의 9
          store.remember(scopeKey, toolName, allowed);   // complete 보다 먼저: 깨어난 대기자가 store 를 봐도 답이 있다
          mine.complete(allowed);
      } finally {
          if (!mine.isDone()) mine.completeExceptionally(…);   // RuntimeException 이든 Error 든 — 대기자를 매달아 두지 않는다
          inFlight.remove(key, mine);                     // 던져진 것은 오늘처럼 invoker 의 catch-all (SingleToolInvoker.java:253) 로
      }
  }
  ```

  `finally` 의 예외 완료는 `resolve` 가 `Error` 를 던져도 대기자가 `get()` 에 매달리지 않게 한다. 이긴 직후의
  재조회는, X 가 `store.lookup` 에서 miss 한 뒤 멈춘 사이 A 가 묻고 `remember` → `complete` → `remove` 를 끝내면
  X 의 `putIfAbsent` 가 이겨 **같은 질문을 다시** 묻는 창을 닫는다.

  - 같은 스코프 · 같은 도구: 프롬프트 1회, 나머지는 그 답을 공유한다.
  - 다른 스코프 또는 다른 도구: **서로를 전혀 기다리지 않는다**(스트라이프 락을 기각한 이유 — §2.4 L).
  - 맵은 진행 중인 질문 수만큼만 크고 `finally` 에서 비운다. 세션 종료 시 지울 것이 없다.
  - 대기자가 interrupt 되면(부모 취소가 전용 스레드를 interrupt — §6.7) 다른 포크의 프롬프트에 매달리지 않고
    플래그를 되돌린 뒤 DENY 사유("Tool '<name>' was not approved: this run was cancelled while waiting for the
    user's answer.")로 돌아온다 — 취소 중인 실행이 "allow" 로 읽히지 않는 fail-safe 방향. `store` 에는 아무것도
    남기지 않는다.
  - 공유하고 기억하는 것은 **사람의 답**뿐이다. 묻던 쪽의 실행이 묻는 사이 취소되었거나(핸들러가 돌려준 값은 사람의
    답이 아니다) 묻던 쪽이 던졌으면, 아무것도 기억하지 않고 대기자는 그 질문을 스스로 다시 묻는다(§9.1 의 9 · 10).
  - 스코프 없는 실행(`:85-92`)은 오늘처럼 캐시도 조정도 없이 매번 묻는다(키가 없으므로 `inFlight` 를 거치지 않는다).
  - 이 변경은 **의도적으로 비조건부**다 — 조립이 만드는 게이트에는 기능 스위치가 없다(`aimon-cli`
    `AgentSetupFactory`). 기능 off 에서 무엇이 같고 무엇이 달라지는지는 §3.16 ⑤.
- **프롬프트 라벨은 속성에서, 속성은 기능이 켜졌을 때만.** `promptFor` 는 `private static` 이고 컨텍스트를 받지
  않으므로(`:255-263`) 라벨 인자 하나를 **더한다** — public `denialReason(Tool, ToolUse, ToolContext)` 만 그대로다.
  라벨은 `ForkAccess.taskIdOf(context)` 가 있을 때만 `"[task 3fa2c1d0] "` (앞 8자 — CLI 의 `[Subagent]` 줄과 같은
  표기, §3.13). 그 속성은 §3.8 대로 기능이 켜진 manager 만 싣는다(§3.16 ⑨).
- `AskPromptHandler` 는 "구현은 thread-safe 해야 한다"고 계약한다(`hook/execution/AskPromptHandler.java:17`).
  CLI 의 `InteractiveAskPromptHandler.resolve` (`modules/aimon-cli/.../hook/InteractiveAskPromptHandler.java:68-94`)
  는 `out.print` 와 `console.readLine()` 을 동기화 없이 한다 — `resolve` 를 직렬화한다(다른 질문이 한 콘솔에 동시에
  오면 한 번에 하나씩; 구현은 §9.1 의 2). 질문 단위 dedupe 는 게이트, 콘솔 단위 직렬화는 핸들러 — 두 책임을 나눈다.

### 3.13 `aimon-cli` — 렌더링 (`modules/aimon-cli/src/main/java/at/aimon/cli/…`)

포그라운드 포크는 부모 이벤트 publisher 로 **아무 스트림 이벤트도 보내지 않는다**(§6.2). CLI 가 보는 것은
훅이다 — `SubagentLaunchDisplayHook`(SubagentStart) · `ToolCallDisplayHook`(PreTool, `InvokerType` 들여쓰기) ·
`SubagentResultDisplayHook`(부모 PostTool, `Task` 결과 텍스트 파싱). 병렬이 되면 세 훅의 줄이 포크 사이에서
섞이고, 같은 서브에이전트 종류의 포크 둘은 구분이 안 된다.

- **라벨은 속성이 있을 때만** — 즉 기능이 켜졌을 때만(§3.8). `ToolCallDisplayHook.execute`
  (`hook/ToolCallDisplayHook.java:41-48`): `ForkAccess.taskIdOf(context.getExecutionAttributes())` 가 있으면
  라벨 `invokerName + "#" + taskId 앞 8자` 를 `displayToolCall` 의 새 오버로드로 넘긴다 →
  `  [Tool] (Explore#3fa2c1d0) Read: {…}`. 없으면 오늘의 오버로드를 부르고, 색 출력 경로에서도 빈 라벨의 SGR
  시퀀스를 끼우지 않는다 — 출력 바이트 동일.
- `SubagentLaunchDisplayHook` (`:37-38`): 속성이 있을 때만 그 값(= `context.getTaskId()` — §3.8 의 재구성 덕분에
  Start 컨텍스트가 **이 포크의** id 를 싣는다) 앞 8자로 같은 라벨 → `  [Subagent] Explore#3fa2c1d0: …`.
  `getTaskId()` 는 항상 있지만 라벨을 속성에 묶는 이유는 하나 — off 에서 출력이 같아야 한다.
- `OutputFormatter.displaySubagentResult` (`repl/OutputFormatter.java:264-296`) 와
  `displaySubagentCompletionReason` (`:313`): 여러 번의 `System.out.println` 을 한 `StringBuilder` 로 모아
  **한 번** 출력한다. `PrintStream` 은 호출 단위로만 동기화되므로 멀티라인 블록은 한 호출이어야 섞이지 않는다.
  바이트는 같고 write 호출 수만 다르다(§3.16 ⑦). `displayToolCall` / `displaySubagentLaunch` 는 이미 한 줄 한 호출이다.
- `SubagentResultDisplayHook` 의 정규식(`:36-38`)은 손대지 않는다 — `Task` 결과 텍스트 형식을 바꾸면 깨진다는
  경고가 `TaskTool.formatSubagentResult` javadoc(`:633-638`)에 있다. 결과 블록에는 이미 `Task: <description>`
  이 있어 귀속에 충분하다.

### 3.14 다른 spawn 사이트 — 깊이 전달

`SubagentLaunchContext.builder()` 를 `ToolContext` 로부터 만드는 곳은 **넷**이고 전부 같은 한 줄을 받는다:
`tools/task/TaskTool.java:567` · `tools/workflow/WorkflowTool.java:522` ·
`skill/fork/SubagentBackedSkillForkExecutor.java:159` ·
`modules/aimon-workflow-graaljs/src/main/java/at/aimon/workflow/graaljs/GraalJsWorkflowTool.java:267` (별도
모듈 — `ForkAccess` 가 public 이어야 하는 이유) → `.forkDepth(ForkAccess.childDepthOf(context))`. 다섯 번째인
agent-scoped base 컨텍스트(`agent/impl/orca/OrcaAgentRuntimeFactory.java:1160`, 루틴 · 런타임 러너)는
`ToolContext` 가 없으므로 기본값 1 을 그대로 둔다. 한 사이트라도 빠지면 비블로킹 허가라 교착은 없지만
`maxForkDepth >= 2` 에서 깊이를 적게 세어 fan-out 을 더 허용한다 — 그래서 넷 모두에 테스트를 둔다(§7).

### 3.15 문서

| 문서 | 변경 |
|------|------|
| `docs/design/README.md` | `subagent` · `tool` 표에 행, §2 색인 |
| `docs/design/tool/parallel-execution.md` | §2 "혼합 배치는 전체 순차", §8.2 "값은 둘뿐", §9 "`THREAD_INTERRUPT` 도구의 병렬 — 범위 밖" 에 이 문서로 가는 한 줄씩 — 결론을 고치지 않고 "그 뒤 바뀌었다" 로 적는다(그 문서 §5 의 선례) |
| `docs/design/agent-execution/interrupt.md` | §8.5 아래에 §2.3 요약 한 문단 — 전제는 **유지**된다 |
| `docs/features/tool/parallel-tool-execution-guide.md` (+ `.en.md`) | §2 에 켜는 법, §6 표에 `DEDICATED_THREAD` · `DedicatedThreadConfig`, §9 제한사항 셋 — 동시 포그라운드 포크 ≤ `maxThreads + 1`; 백그라운드 `Task` 도 허가를 잠깐 쓰고 그 `SubagentStart` 는 입력 순서 보장이 없다(§8 Q2); 공급자 rate limit 은 `maxThreads` 로만 조절한다(Q10) |
| `docs/features/subagent/subagent-development-guide.md` (+ `.en.md`) | "병렬 포그라운드 태스크와 파일" 한 문단(§6.5 지침) + 기능이 켜졌을 때 포크가 보는 `aimon.subagent.task_id` |
| `CHANGELOG` | Added · Changed 항목 |

`design/` 아래 문서는 번역하지 않는다(`documentation-guide.md` §5.1). 번역본이 있는 두 가이드는 같은 커밋에서 함께 고치고,
`check-translation-structure.py` · `check-doc-links.py` · `check-translation-staleness.py` 가 통과해야 한다.

### 3.16 기능 off 시 바이트 동일 — 변경 전수와 각각의 근거

"설정을 켜지 않으면 기존 동작이 바이트 단위로 동일" 은 데코레이터가 설치되지 않는다는 한 문장으로 증명되지
않는다 — 이 설계의 변경 하나하나가 조건부이거나, 비조건부라면 **동시 호출이 없을 때 관측 불가**여야 한다. 이 표가
그 논증의 유일한 자리이고 다른 절은 이 표를 가리킨다. "관측" 은 LLM 요청 바이트 · 도구 · 훅이 보는 키 · 프롬프트
문자열 · CLI 출력 바이트 · 이벤트 열 · 결과 순서를 뜻한다.

| # | 변경 | 조건부? | off 에서 관측 불가인 이유 |
|---|------|---------|---------------------------|
| ① | `TaskTool.getConcurrencyBehavior()` → `DEDICATED_THREAD` (§3.6) | 비조건부 (정적 선언) | 읽는 곳은 둘 — `shouldParallelize` (`:307`) 와 `isEagerEligible` (`:206`) — 둘 다 `== CONCURRENT_SAFE` 만 참. `SEQUENTIAL` 과 같은 결정. 이 값을 읽는 ArchUnit · 스위치문 · 위임 래퍼는 없다 |
| ② | 데코레이터 · `DispatchOptions` · 6-인자 default 메서드 (§3.4 · §3.5 · §3.9) | **조건부** — factory 가 enabled 일 때만 설치 | 미설치 시 `executor.parallelToolDispatcher` 는 오늘 값. 6-인자 default 는 5-인자로 위임 |
| ③ | `FORK_DEPTH` 키 · `forkDepth` 필드 (§3.7 · §3.8 · §3.9 · §3.14) | 비조건부 | `ToolContext` 키 하나가 포크의 도구에 더 보인다. 읽는 코드는 spawn 사이트 넷뿐이고, 그 값은 런치 컨텍스트의 내부 필드로만 흐른다 — 훅 컨텍스트 · 속성 · span · 영속 레코드에 닿지 않는다. 포크의 `ToolContext` 는 복사만 되고 직렬화 · 열거되지 않으며(`HookRegistryAccess.withHookRegistry` 가 유일한 전체 맵 독자), 모듈 enricher 가 키를 더하는 **가산적** 채널이라(`createToolContext` 의 `applyEnrichers`) 키의 존재 자체는 동작이 아니다. 엄밀한 바이트 동일은 아니지만 받아들인다 — 조건부로 만들려면 포크 실행기가 설정을 알아야 하는데 그 배관이 설계의 모든 이득보다 크다(§8 Q12) |
| ④ | 포크 task id 실행 속성 (§3.8) | **조건부** — manager 플래그, factory 가 enabled 일 때만 | off 에서 훅 · 도구 · 중첩 포크가 보는 속성 집합이 오늘과 같다 |
| ⑤ | 승인 게이트의 질문 단위 in-flight 조정 (§3.12) | 비조건부 (게이트는 설정을 모른다) | 동시 호출이 없으면 `putIfAbsent` 가 항상 비어 있어 프롬프트 문자열 · 횟수 · 순서 · 저장 내용이 오늘과 같다. 오늘 동시일 수 있는 경로는 둘 — 백그라운드 포크, 그리고 `ToolConcurrencyConfig` 가 켜진 배치의 게이트 대상 `CONCURRENT_SAFE` 도구(`SessionHistoryTool` 은 `CONCURRENT_SAFE` 를 선언하면서 기본 `MUTATING`/`DESTRUCTIVE` 라 게이트에 걸린다 — `tools/session/SessionHistoryTool.java:145-146`; 외부 도구도 가능). 그 둘에서 "같은 질문 두 번(두 `readLine` 이 섞일 수 있음)" 이 "한 번" 으로 바뀐다 — off 경로의 **동작**이 달라지는 유일한 항목이며 결함 수정으로 받아들인다(§8 Q13) |
| ⑥ | `InteractiveAskPromptHandler.resolve` 직렬화 (§3.12, §9.1 의 2) | 비조건부 | 비경합 시 락 획득 외 차이 없음. 인터럽트된 호출자를 묻지 않고 거절하는 것은 off 의 순차 경로에서 일어나지 않는다(§9.1 의 2) |
| ⑦ | `OutputFormatter` 멀티라인 블록 단일 출력 (§3.13) | 비조건부 | 같은 바이트, 같은 순서, write 호출 수만 다름 |
| ⑧ | CLI 라벨 (§3.13) | **조건부** — ④ 의 속성이 있을 때만 | off 에서 오늘의 오버로드 호출 → 출력 동일(색 출력 경로 포함 — 빈 SGR 시퀀스 없음) |
| ⑨ | 프롬프트 `[task …]` 라벨 (§3.12) | **조건부** — ④ 의 속성이 있을 때만 | off 에서 프롬프트 문자열 동일 |
| ⑩ | harvest 꼬리 라우팅 (§3.11) | **조건부** — `routesHarvestTail()` 이 참인 디스패처(데코레이터)일 때만 | off 에서 harvest 코드 경로가 오늘과 같다. 공급자의 READY 이벤트 가정에 기대지 않는다(§2.4 P) |
| ⑪ | `Task` 도구 설명 | — | **바꾸지 않는다** (§3.6, §2.4 K) |

---

## 4. 데이터·인터페이스 변화 요약

| 타입 | 변화 | 호환성 / off 동작 |
|------|------|-------------------|
| `ConcurrencyBehavior` | `DEDICATED_THREAD` 추가 | enum 확장. 기존 게이트는 `SEQUENTIAL` 로 취급 (§3.16 ①) |
| `DedicatedThreadConfig` (신규) | `enabled` / `maxThreads` / `maxForkDepth` | — |
| `DedicatedThreadPermits` (신규) | `of(config)` · `tryAcquire` · `release` · `newThread` · `availablePermits` | — |
| `DispatchOptions` (신규) | `root()` · `forkDepth` | — |
| `ParallelToolDispatcher` | 6-인자 `dispatch(…, DispatchOptions, …)` 와 `routesHarvestTail()` — 둘 다 **default** 메서드 | 외부 구현체 무변경 (깊이 무시, 꼬리 라우팅 없음) |
| `DedicatedThreadDispatcher` (신규) | 데코레이터 | factory 가 enabled 일 때만 설치 |
| `Tool`/`TaskTool` | `TaskTool.getConcurrencyBehavior()` → `DEDICATED_THREAD` | 결정 불변 (§3.16 ①) |
| `ToolContextKeys.FORK_DEPTH` (신규) + `ForkAccess` (신규) | 포크 깊이, task id 속성 키와 reader | 포크 조상 없는 실행에는 없음(=0). 포크에는 무조건 (§3.16 ③) |
| `SubagentLaunchContext` / `SubagentExecutionRequest` | `forkDepth` (기본 1) | 빌더 필드 추가, 기존 호출 무변경 |
| `DefaultSubagentExecutionManager` | `withForkTaskIdAttribute()` — 켜지면 두 진입점에서 런치 컨텍스트의 실행 속성에 `aimon.subagent.task_id` 를 싣는다(Start/Stop 훅 · 요청 · 포크 도구가 같은 맵을 본다) | 기본 off (§3.16 ④) |
| `OrcaAgentExecutorFactory` | `withDedicatedThreadConfig` | 기본 null |
| `SideEffectApprovalGate` | 질문 단위 in-flight 조정, 속성 기반 라벨, `promptFor` 에 라벨 인자(private) | public 시그니처 무변경. 비경합 시 동일 (§3.16 ⑤ ⑨) |
| CLI `InteractiveAskPromptHandler` · `OutputFormatter` | `resolve` 직렬화 · 라벨 오버로드 · 멀티라인 단일 출력 | §3.16 ⑥ ⑦ ⑧ |

바뀌지 **않는** 것: `DefaultParallelToolDispatcher`, `SingleToolInvoker`, `DefaultTerminatorRegistrar`,
`DefaultInterruptCoordinator`, `TaskTool.runForeground`, `TaskTool.buildDescription`, `BudgetTracker`, 트레이싱
전부, `SubagentResultDisplayHook` 의 파서.

---

## 5. 실패 모드와 처리

| 상황 | 처리 |
|------|------|
| 설정 off (기본) | 데코레이터와 manager 플래그 미설치. 무엇이 같고 무엇이 달라지는지는 §3.16 |
| 설정 on, 배치에 `DEDICATED_THREAD` 없음 · 깊이 ≥ `maxForkDepth` | delegate 에 배치 전체 1회 위임 → 동일 (포크는 기본적으로 오늘처럼 순차) |
| 허가 없음 (다른 턴 · 다른 포크가 다 쥠) | 그 호출은 호출 스레드 inline. 결과 순서 · 이벤트 순서 불변. 교착 없음 |
| 전용 스레드에서 runner 가 던짐 / null | `runAndComplete` 가 에러 `ToolUseResult` 로 격리 (`runSafely` 와 동일) |
| 전용 스레드에서 `Error` | `completeExceptionally` → join 이 에러 결과로 변환, 허가는 `finally` 로 반환 |
| 부모 취소 (coordinator 경유) | 협력 신호 캐스케이드 + 각 전용 스레드 `interrupt` (§6.7). 아직 안 시작한 호출은 runner 게이트가 skip (`OrcaAgentExecutor.java:2454-2458`, 전용 스레드에서 평가) |
| 부모 **스레드** interrupt (join 중) | 미완 전용 스레드에 interrupt 전파, 계속 대기, 끝난 뒤 플래그 재무장 → 루프 체크포인트가 승격 |
| 늦은 interrupt | 전용 스레드에 떨어지고 그 스레드와 함께 사라진다. inline lane 은 오늘과 같은 체크포인트 흡수. 잔여 창은 §6.7 |
| 승인 프롬프트 중 취소 | `console.readLine()` 은 interrupt 로 깨지지 않는다 — 오늘 순차 `Task` 에서도 동일. 사용자가 답하면 묻던 스레드가 신호를 보고 끝난다. 같은 질문을 기다리던 **포크 안의** 대기자는 interrupt 에 깨어나 DENY 로 돌아온다(§3.12; 부모 수준 대기자는 §9.1 의 4). 묻던 쪽이 취소된 채 돌아온 답은 기억하지도 공유하지도 않는다 — 대기자는 스스로 다시 묻는다(§9.1 의 9) |
| 승인 동시 요청 (같은 스코프, 같은 도구) | in-flight future 공유 → 1회 프롬프트, 나머지는 같은 답 |
| 승인 동시 요청 (다른 스코프 · 다른 도구) | 서로 독립. CLI 는 핸들러의 직렬화로 한 번에 하나씩 보여 준다 |
| 묻던 쪽이 예외로 끝남 | 묻던 쪽은 오늘처럼 invoker 의 catch-all. 대기자는 "사용자가 거절했다" 를 받지 않고 다시 조회한 뒤 스스로 묻는다(§9.1 의 10) |
| 같은 파일 동시 편집 | 두 번째 포크의 `Edit` 가 `CHANGED_MESSAGE` 로 거절되고 다시 읽으라는 안내 (§6.5) |
| CLI 출력 섞임 | 줄 단위 원자성 + 라벨 + 멀티라인 블록 단일 출력 (§3.13). 줄 **사이** 인터리빙은 받아들인다 |
| 스트리밍 중첩 + eager 접두 | 꼬리를 디스패처로 (§3.11). 접두는 오늘처럼 join |
| 외부 `ParallelToolDispatcher` 구현체 · 데코레이터 없는 포크 실행기의 6-인자 호출 | 6-인자 default 가 5-인자로 위임 → 깊이 무시, 동작 불변 |
| 트레이싱 on | 부모 span 은 값으로 전달되므로 스레드와 무관 (§6.3) |
| factory 를 거치지 않는 조립 (`OrcaAgentExecutor.builder().parallelToolDispatcher(...)`) | 조립자가 직접 `DedicatedThreadDispatcher` 를 감싸 주입하고 manager 플래그도 직접 켠다. 포크 쪽은 `withParallelToolDispatcher` — 기존 feature 가이드 §2.2 와 같은 책임 분담 |

---

## 6. 설계 단계 검증 항목 아홉 가지 — 코드로 확인한 결론

각 항목은 확인한 코드와 결론만 적는다. 결론이 낳은 변경은 괄호 안의 절에 있다.

### 6.1 부작용 승인 프롬프트(side-effect gate)의 동시 발생

게이트는 `SingleToolInvoker.dispatch` 의 PermissionRequest 뒤, PreTool 앞에서 돌고(`toolinvocation/SingleToolInvoker.java:213-219`)
그 파이프라인은 전용 스레드에서 돈다. `denialReason` 은 `store.lookup` (`:199`) → `channel.resolve` (`:204`) →
`store.remember` (`:206`) 를 락 없이 잇고, 스코프 키는 턴이면 `SESSION_ID`, 포크면 `INVOKING_SESSION_ID` (`:240-244`)
라 같은 세션의 포크들이 **같은 키**를 쓴다. `InMemoryToolApprovalStore` 는 thread-safe 지만 원자적 "없으면 묻기" 는
아니고, CLI 핸들러는 javadoc 의 "Thread-safe"(`:33`)와 달리 `out.print` + `console.readLine()` 에 동기화가 없다.

**결론.** 병렬 `Task` N개가 "Allow 'Task(...)'?" 를 N번, 포크 안의 같은 쓰기 도구도 포크 수만큼 묻고 프롬프트와
stdin 읽기가 섞일 수 있다(→ §3.12). 다른 포크의 `[Tool]` 한 줄이 프롬프트 앞뒤에 찍히는 것은 줄 단위라 받아들인다.

### 6.2 부모 이벤트 publisher 로의 스트림 이벤트 인터리빙과 taskId 구분

포그라운드 포크의 출력 싱크는 `SubagentOutputSink.NO_OP` 이고(`subagent/DefaultSubagentExecutionManager.java:623-631`)
포크의 도구 컨텍스트에는 `AGENT_EVENT_SINK` 가 없다(`DefaultSubagentExecutor.java:891-958`). 부모 publisher 에 닿는
것은 `Task` 호출 자신의 `ToolUseStarted`(입력 순서, `OrcaAgentExecutor.java:2419`)와 `ToolResultReady`(완료 순, `:2420`)
뿐이고 둘 다 `toolUseId` 를 싣는다. CLI 는 훅으로 그리고 `OutputFormatter` 는 줄마다 `println` 한다(`:282-295`).

**결론.** 스트림 이벤트 인터리빙 문제는 없다. CLI 의 멀티라인 결과 블록이 섞일 수 있고 같은 종류의 포크 둘이
구분되지 않는다(→ §3.8 · §3.13).

### 6.3 트레이싱 span 부모 context 의 스레드 전파

트레이싱에는 `ThreadLocal`/`ScopedValue` 가 없다. 부모는 값으로 간다 — `resolveEffectiveToolContext`
(`OrcaAgentExecutor.java:2470-2476`) 가 호출 스레드에서 dispatch 전에 ITERATION span 을 실은 컨텍스트를 만들고
`toolRunner` 가 캡처하며(`:2452`), TOOL span → `parentLlmCallMetadata`(`TaskTool.java:537-541, 572`) → SUBAGENT span
으로 이어진다. span 의 `setAttribute` 는 `synchronized`(`tracing/impl/DefaultTracer.java:179`).

**결론.** 스레드가 바뀌어도 트리가 맞는다 — 코드 변경 없음. 유일한 메타데이터 `ThreadLocal` 인
`LlmCallMetadataHolder` 의 ambient 태그는 전용 스레드에서 보이지 않는다 — 풀 경로와 백그라운드 포크에 이미 있는
공백이다(PF-6).

### 6.4 `SubagentStart`/`SubagentStop` 훅과 훅 실행 관리자의 동시 호출

`fireSubagentStart`/`fireSubagentStop` 은 포크를 돌리는 스레드에서 불린다(`DefaultSubagentExecutionManager.java:677-684,
1059-1074`). `DefaultHookExecutionManager` 는 thread-safe(`:57`), `DefaultHookExecutor` 는 호출 간 공유 상태가 없고
(`:56, 242-255`), `DefaultHookRegistry` 는 `CopyOnWriteArrayList`(`:23`)이며 N개 포크가 같은 스포너 레지스트리를
읽기만 한다(`TaskTool.java:571`).

**결론.** 프레임워크 쪽은 안전하다 — 코드 변경 없음. 사용자 훅의 thread-safety 는 이미 병렬 도구가 요구한
계약이고(parallel-execution.md §5), 순서 보장은 포크 **하나 안에서만** Start → (Pre/Post…) → Stop 이다.

### 6.5 같은 파일 동시 편집 정책

파일 stamp 맵은 실행마다 새로 만들고(턴 `OrcaAgentExecutor.java:902`, 포크 `DefaultSubagentExecutor.java:917`),
`EditTool` 은 쓰기 전 stamp 가 다르면 `CHANGED_MESSAGE` 로 거절한다(`tools/file/FileStamps.java:118-131`). 파일 락은
없고, 백그라운드 포크는 이미 부모와 동시에 같은 파일을 만질 수 있다.

**결론.** 차단하지 않고 stamp 가드가 lost update 를 명시적 거절로 바꾼다. 검사 → 쓰기 사이 TOCTOU 창은 오늘 사용자
편집기와 에이전트 사이에도 있다. 락 기각은 §2.4 I, 지침을 두는 자리는 §3.6.

### 6.6 중첩 Task fan-out 상한 (깊이 × 폭)

포크 깊이 개념이 없었다(depth/nesting 키 0건). `CALLER_ALLOWED_TOOLS` 가 "실행마다 다시 실어 어떤 깊이로도 따라간다"
(`ToolContextKeys.java:373`) — 같은 모양의 선례다. 백그라운드 풀은 폭만 묶고(`DefaultSubagentExecutionManager.java:498-505`)
포크 실행기는 깊이와 무관한 한 인스턴스다(`OrcaAgentExecutorFactory.java:778`).

**결론 (결정).** 폭 = `maxThreads` 세마포어 하나, 깊이 = `maxForkDepth`. 어떤 스레드도 허가를 쥔 채 허가를 기다리지
않으므로 **교착이 불가능하다**. 최악의 동시 포그라운드 포크 수는 `maxThreads + (동시에 dispatch 중인 호출 스레드
수)` 다(→ §3.2 ~ §3.5 · §3.7 ~ §3.9 · §3.14; 사다리 기각은 §2.4 D, "대기" 의 해석은 §8 Q1).

### 6.7 부모 취소 시 자식 전파, 그리고 늦은 interrupt 가 부모 스레드에 남지 않음

협력 신호: 턴의 `CANCELLATION_SIGNAL`(`OrcaAgentExecutor.java:905`)이 런치 컨텍스트(`TaskTool.java:547-548, 573`) →
포크의 부모 신호(`:627-630`) → `parentSignal.onCancel(… requestInterrupt(PARENT_CANCELLED))`
(`DefaultSubagentExecutor.java:458-467`)로 캐스케이드한다 — 스레드와 무관. Terminator: `SingleToolInvoker` 가
`EXTERNALLY_TERMINATED` 에 대해 **실행 스레드에서** 레지스트라를 발급하고(`:165-172`) `requestInterrupt` 가 스냅샷을
돌며 `fireAll` 한다(`DefaultInterruptCoordinator.java:33-50`); 이미 트립된 코디네이터에 등록하면 즉시 발화한다
(`DefaultTerminatorRegistrar.java:44-53`). `fireAll` (`:84-97`) 은 lock 밖에서 `terminate()` 하고 `unregister`
(`:56-65`) 는 `fired` 면 즉시 반환하므로 도구가 돌아온 뒤 interrupt 가 도착할 수 있다 — 오늘은 부모 루프 스레드의
`isInterrupted` (`OrcaAgentExecutor.java:2061-2071`)와 `consumeLingeringInterrupt` (`:1299-1306`)가 흡수한다.

**결론.** 전용 스레드에서 (a) 협력 신호 캐스케이드는 그대로, (b) `fireAll` 이 N개 전용 스레드를 전부 interrupt,
(c) 늦은 interrupt 는 전용 스레드와 함께 사라지고 부모 스레드에는 terminator 가 없다 — inline lane(§3.5 ③)은 오늘과
같은 흡수, (d) 부모 스레드 자체의 interrupt 는 §3.5 ⑤⑥ 으로 전파하고 되돌린다. `TaskTool` · 코디네이터 ·
레지스트라는 무변경.

**교차 lane 효과 (무해).** inline lane `Task` 의 늦은 interrupt 가 호출 스레드의 `future.get()` 에 떨어지면 §3.5 ⑤ 가
미완 전용 형제에 interrupt 를 건다. 그 terminator 는 턴 코디네이터의 `requestInterrupt` 로만 발화하고 그 호출이 같은
스냅샷에서 형제들의 레지스트라도 이미 발화시켰으므로 같은 신호의 반복이다. 되돌린 플래그는 체크포인트의 no-op
승격으로 끝난다(`DefaultInterruptCoordinator.java:41-44`).

**잔여 창 (오늘도 있는 것).** `runForeground` 가 돌아온 뒤 같은 스레드에서 ⓐ PostTool 훅(`SingleToolInvoker.java:252,
340-364`)과 ⓑ `onCompleted` 가 돈다. 늦은 interrupt 가 그 사이에 떨어지면 ⓐ 는 `DefaultHookExecutor` 의 fail-open 이
PostTool 을 건너뛸 수 있고(`interrupt.md` §8.7 의 두 번째 겹은 PreTool 쪽만 막는다), ⓑ 는 리스너가 블로킹하지 않는 한
영향이 없다. 오늘 부모 스레드에도 똑같이 있다(§8 Q9, PF-3).

### 6.8 `BudgetTracker` / 토큰 · 비용 집계의 공유와 스레드 안전성

`BudgetTracker` 는 동기화 없는 필드이고(`agent/budget/BudgetTracker.java:56-64`) 생성 지점은 턴(`OrcaAgentExecutor.java:1170`)
과 포크(`DefaultSubagentExecutor.java:454`) 둘이다. 턴의 트래커는 루프 스레드만 만지고, 포크의 토큰은 부모에 합산되지
않는다(`TaskTool.java:656-657`). 포크 사이에 공유되는 집계는 `QuotaGuard` 하나이고(`:895` → `:772`) "ledger 가 안전한 한"
thread-safe 이며(`agent/quota/QuotaGuard.java:39-41`) 기본 `InMemoryUsageLedger` 는 `ConcurrentHashMap` + `merge` 다.

**결론.** 새 공유 집계는 없다 — 코드 변경 없음. `BudgetTracker` 는 동기화하지 **않는다** — "실행당 하나" 라는
scope-model 규칙을 잠금으로 가리면 규칙이 흐려진다.

### 6.9 결과 순서 · Pre/PostTool 훅 이벤트 순서

결과는 입력 순서로 재조립되어(`DefaultParallelToolDispatcher.java:254-260`) 한 메시지로 커밋된다(`OrcaAgentExecutor.java:1966`).
`onStarted` 는 호출 스레드 · 입력 순서, `onCompleted` 는 완료 순, 둘 다 tool-use id 매칭(`ParallelToolDispatcher.java:45-52`).
Permission → 게이트 → PreTool → 실행 → PostTool 순서는 호출 하나 안에서 고정이고(`SingleToolInvoker.java:195-252`) 훅
feedback 은 그 호출의 결과에만 붙는다(`:74-83`).

**결론.** 전용 스레드 경로도 같은 계약을 지킨다(§3.5 ④, `safeOnStarted` 위치). 섞인 배치는 구간 순서대로 join 되어
비-`Task` 도구의 상대 순서와 실행 의미가 보존되고, harvest 경로는 꼬리의 이벤트를 디스패처에 한 번 맡긴다(§3.11).
계약 추가 없음 — `DedicatedThreadDispatcher` javadoc 이 세 보장을 재진술한다.

---

## 7. 테스트

모두 단위 테스트(`./gradlew test`, Docker 불필요)이고 실제 LLM 대신 스크립트 클라이언트를 쓴다. 아래는 구현에
들어간 테스트가 실제로 덮는 것이다.

| 테스트 | 덮는 것 |
|--------|---------|
| `DedicatedThreadDispatcherTest` | 설정 off · `DEDICATED_THREAD` 없음 · 깊이 ≥ 상한 · 미등록 이름 · `registry == null` 이면 delegate 의 5-인자 `dispatch` 가 원본 리스트 인스턴스로 정확히 1회(기록용 delegate — `DefaultParallelToolDispatcher` 는 `final` 이라 spy 가 아니다). `[Task×3]` 이 서로 다른 새 daemon 스레드(`tool-dedicated-`)에서 동시에 돌고, 벽시계가 최장 호출 정도, 완료 순서를 뒤집어도 결과는 입력 순서, `onStarted` 는 호출 스레드 · 입력 순서, `onCompleted` 는 전용 스레드. 섞인 배치의 구간 순서. 상한 초과분 inline · 허가 전부 반환. 두 dispatch 가 한 허가 집합을 나눠 써도 상한 안. runner 의 throw/null · 전용 스레드의 `Error` 격리. 호출 스레드 interrupt 의 전파와 재무장. 늦은 interrupt 를 **다른 스레드에서** 전용 스레드에 걸어도 호출 스레드 플래그는 깨끗함. `close()` 위임. `routesHarvestTail()==true`. 연달은 두 배치가 스레드를 재사용하지 않고 앞 배치 스레드는 끝나 있음. 중첩 fan-out(상한 2 · 깊이 2 · 공유 허가)이 교착 없이 끝나고 허가 전부 반환. 스레드 생성 실패(`ThreadFactory` 주입) 시 그 호출만 inline · 순서 유지 · 허가 반환. inline 레인의 `Error` 는 시작된 전용 스레드를 interrupt · join 한 뒤 다시 던짐. 전용 스레드의 `Error` 도 `onCompleted` 를 오류 결과로 발화. 진입 시 이미 interrupt 된 호출자는 구간을 delegate 순차 경로로 넘김. runner 가 이미 반환한 슬롯은 전달 interrupt 를 받지 않음(`onCompleted` 리스너 보호). 비전용 구간에도 `DispatchOptions` 전달 |
| `DedicatedThreadConfigTest` | 기본값(off · 4 · 1), `disabled()` 공유 인스턴스, `maxThreads<1` · `maxForkDepth<1` 거절, `toString`, `DedicatedThreadPermits` 의 비블로킹 획득과 호출마다 새 스레드, `DispatchOptions.root()` 깊이 0 · 음수 거절, 6-인자 default 가 5-인자로 위임 |
| `DefaultParallelToolDispatcherTest` 확장 | `DEDICATED_THREAD` 는 게이트에서 `SEQUENTIAL`(§3.16 ① 의 직접 증거), eager 비적격, 풀 디스패처는 harvest 꼬리를 라우팅하지 않음 |
| `OrcaAgentExecutorDedicatedTaskTest` | 가짜 `SubagentExecutionManager` 로: on 이면 `Task` 셋이 동시 · `tool_result` 는 `tool_use` 순서, off(기본 디스패처)면 호출 스레드에서 순차. 부모 취소가 모든 자식의 신호와 terminator 에 닿고 호출 스레드 플래그는 깨끗함. 늦은 terminator(`interruptCoordinatorFactory` 로 넣은 지연 코디네이터)가 전용 스레드에 남고 이어지는 다음 턴의 도구가 interrupt 를 보지 않음. 같은 세션의 병렬 `Task` 셋이 `Allow 'Task(...)'?` 를 **한 번**, 접두 **없이** 묻는다(부모 수준 질문 — §9.1 의 3). TOOL span 셋이 한 ITERATION 아래이고 포크마다 자기 TOOL span 의 metadata. 스트리밍 중첩: on 이면 Read eager · 꼬리가 디스패처로 가 fan-out, off 면 꼬리가 `dispatch` 에 닿지 않고 이벤트 열이 inline harvest 와 같음(§3.16 ⑩). factory 배선: enabled 면 데코레이터와 포크 라벨, 기본이면 둘 다 없음. 상한 2 · `Task` 셋(inline 레인 포함)에서 부모 취소가 셋 모두에 닿고 허가가 2 로 돌아옴. `withDedicatedThreadConfig` 재설정이 이후 executor 의 허가 상한을 바꿈. 가짜 포크는 LLM 을 부르지 않으므로 SUBAGENT span 속성 집합과 `InMemoryUsageLedger` 의 포크 charge 는 여기서 단언하지 않는다 |
| `DefaultSubagentExecutionManagerForkLabelTest` | 런치 컨텍스트의 깊이가 포크 도구의 `FORK_DEPTH` 로 닿음; `maxForkDepth=2` 면 깊이 1 포크가 펴고 기본이면 순차. off 면 Start · 포크 도구 · Stop 이 스포너의 속성 그대로; on 이면 세 곳이 같은 자기 task id, 스포너의 맵은 불변; 백그라운드 진입점과 풀 거부 경로의 Stop 도 같음; 중첩 포크는 자기 id 로 덮어쓰고 바깥 포크는 자기 id 유지 |
| `SideEffectApprovalGateTest` (`ConcurrentQuestions` 외) | 같은 질문 8개 동시 → 프롬프트 1회 · 모두 같은 답; `remember()` 가 아무것도 하지 않는 저장소로도 동시 호출 6개에 프롬프트 1회(저장소가 아니라 in-flight 조정의 증거); 묻던 쪽이 interrupt 되면 아무것도 기억하지 않고 대기자가 스스로 다시 묻는다(§9.1 의 9); 다른 스코프 · 다른 도구는 열린 프롬프트에 막히지 않음; 대기 중 interrupt 된 호출자는 거절되고 아무것도 기억하지 않음; 던지는(`RuntimeException` · `Error`) 묻던 쪽이 대기자를 매달아 두지 않고 다음 호출이 다시 묻는다; 스코프 없는 실행은 매번 묻는다; 답이 저장된 뒤 슬롯을 이긴 호출자는 묻지 않는다; task id 라벨 유무 |
| `InteractiveAskPromptHandlerTest` (CLI) | 콘솔 프롬프트가 한 번에 하나; 콘솔을 기다리다 interrupt 된 호출자와 이미 interrupt 된 호출자는 아무것도 찍지 않고 거절 |
| `TaskToolTest` 확장 | `DEDICATED_THREAD` 선언; 설명이 동시성을 광고하지 않음(base 문자열을 박지 않는다 — 서브에이전트 목록이 바뀔 때마다 깨지므로; `buildDescription` 은 diff 상 무변경); 런치 컨텍스트가 호출자보다 한 단계 깊음(포크 안에서도) |
| `WorkflowToolTest` · `SubagentBackedSkillForkExecutorTest` · `GraalJsWorkflowToolTest` · `SubagentLaunchContextTest` · `SubagentExecutionRequestTest` | spawn 사이트 넷의 깊이 + 1(§3.14), `forkDepth` 기본 1 · `toBuilder` 복사 · 1 미만 거절 |
| `ForkAccessTest` | 깊이(없으면 0, 자식은 +1), 문자열일 때만 task id, `shortTaskId` 8자 |
| CLI `SubagentLaunchDisplayHookTest` · `ToolCallDisplayHookTest` · `OutputFormatterTest` | 속성이 있을 때만 라벨, 없으면 라벨 없는 줄; `displaySubagentResult` 가 블록을 한 번에 쓰고 바이트는 줄 단위 출력과 같음; 라벨 없는 3-인자 · `null` 라벨 `displayToolCall` 이 base 의 출력과 바이트 단위로 같음(§3.16) |

회귀로는 기존 병렬 · 인터럽트 · 서브에이전트 · 트레이싱 테스트, ArchUnit 전부(`PackageDependencyArchitectureTest` —
새 타입은 전부 `agent.tool` / `tools` / `subagent` 에 있어 새 패키지 쌍이 없다; `TurnVocabularyArchitectureTest`;
`InternalPackageBoundaryTest`), `checkJavadocCoverage`, `./gradlew checkAll`, 문서 스크립트를 돌린다.

---

## 8. Open questions (요구사항으로 해소되지 않는 것 — 가정하지 않고 표기)

| # | 질문 | 설계의 잠정 처리 |
|---|------|-----------------|
| Q1 | 수용 기준 "상한 초과 시 **대기**(backpressure)" 를 블로킹 대기로 읽어야 하는가? | 비블로킹 + inline fallback 으로 읽었다(§2.4 D, §6.6). 호출자는 자기 속도로 느려지므로 백프레셔이고, 라벨 정확성에 기대지 않는 교착 없음을 얻는다. 블로킹이 꼭 필요하면 깊이별 세마포어 사다리(D)로 바꿀 수 있으나 다섯 spawn 사이트의 라벨이 교착 조건이 된다 |
| Q2 | 백그라운드 `Task` 호출(즉시 반환)도 전용 스레드를 쓰는 것이 허용되는가? | 허용으로 두었다(정적 선언, §2.4 H). 비용 둘 — 백그라운드 launch 의 `SubagentStart` 가 입력 순서로 찍힌다는 보장이 사라지고(`DefaultSubagentExecutionManager.java:1111-1114` 주석의 의도), 백그라운드 launch 가 포그라운드 허가를 잠깐 써서 폭주하면 동시 포그라운드 `Task` 가 inline lane 으로 밀릴 수 있다. 둘 다 가이드 §9 제한사항에 적는다. 거슬리면 호출별 술어가 필요하다(PF-2) |
| Q3 | 기본 `maxForkDepth` 는 1(포크 조상 없는 실행만)인가 2 인가? | 1. 포크의 fan-out 은 LLM 동시성을 곱하므로 운영자가 명시해야 한다 |
| Q4 | `AimonStackBuilder` / Spring `AimonProperties` 에 노출해야 하는가? | 이번 범위 밖 — 기존 `ToolConcurrencyConfig` 도 노출되지 않았다. 둘을 함께 노출하는 후속 과제(PF-1) |
| Q5 | `maxThreads` 상한이 factory 전체(모든 세션)인 것이 맞는가, 실행기당인가? | factory 전체(§3.10). "호스트 보호" 가 목적이면 전체가 맞다. 실행기당으로 하려면 `applyExecutorOverrides` 에서 permits 를 새로 만들면 되고 포크와의 공유만 유지하면 된다 |
| Q6 | 이름 — `DEDICATED_THREAD` / `DedicatedThreadConfig` 대 요구사항의 예시 `SubagentForegroundConfig` | 메커니즘 이름을 택했다(§2.4 J). 사용자 문서 제목은 "포그라운드 Task 병렬 실행" |
| Q7 | 포크 실행 속성 `aimon.subagent.task_id`(§3.8)가 범위 안인가? 이것 없이는 CLI 가 같은 종류 포크 둘을 구분할 수 없다 | 포함하되 **기능이 켜진 manager 만** 싣는다 — 검증 항목 2 가 "taskId 구분(CLI 렌더링 포함)" 을 묻고, off 경로는 바이트 동일이어야 하므로. 빼면 §3.13 의 라벨도 §3.12 의 프롬프트 접두도 없다 |
| Q8 | 승인 조정의 단위 — 질문(스코프 × 도구)별 in-flight future(채택) 대 스코프별 락 | 질문별. 멀티세션 서버에서 다른 사용자의 프롬프트가 서로를 막지 않아야 하고(§2.4 L), 같은 스코프의 다른 질문도 막을 이유가 없다. 콘솔 하나에 질문이 겹치는 것은 핸들러의 일이다 |
| Q9 | `runForeground` 반환 → PostTool / `onCompleted` 사이의 늦은 interrupt 창(§6.7)을 이번에 닫을 것인가? | 범위 밖 — 전용 스레드가 창을 넓히지 않는다. 닫으려면 `SingleToolInvoker` 가 실행 뒤 PostTool 전에 `Thread.interrupted()` 를 소비 · 승격해야 하고 그것은 `interrupt.md` §8 의 별도 변경이다(PF-3) |
| Q10 | LLM 공급자 rate limit — `maxThreads` 외 별도 스로틀이 필요한가? | 없음. `LlmCallGateway` 의 retry 정책이 429 를 다루고, 운영자는 `maxThreads` 로 폭을 정한다. 가이드 §9 제한사항에 적는다 |
| Q11 | harvest 꼬리 라우팅(§3.11)을 이번에 넣을 것인가, 후속으로 미룰 것인가? | 넣는다 — 기능 off 에서 경로가 같고(§3.16 ⑩) off 이벤트 열 테스트가 있다(§7). 범위를 줄여야 하면 뺄 수 있는 독립 항목이다 |
| Q12 | 포크 `ToolContext` 에 `FORK_DEPTH` 키가 늘 보이는 것을 "기능 off 시 바이트 동일" 로 받아들일 수 있는가? | 받아들인다 — 근거는 §3.16 ③. 대안(`wantsForkDepth()` default 메서드)은 쓰지 않는다 |
| Q13 | 기능이 꺼진 **도달 가능한** 구성에서 "같은 질문 두 번" 이 "한 번" 으로 바뀌는 것을 결함 수정으로 받아들이는가? | 받아들인다(§3.16 ⑤). 두 `readLine` 이 섞이는 오늘의 동작은 누구도 의도하지 않았고, 답의 범위는 넓어지지 않는다(게이트의 답은 이미 (스코프, 도구) 단위 · "whatever its arguments" — `SideEffectApprovalGate.java:247-249`). 엄밀한 off 바이트 동일을 원하면 게이트에 스위치를 들여야 하는데, 그 결함을 꺼진 배치에 남기는 값이 그 엄밀함보다 비싸다 |

---

## 9. 구현이 이 설계와 갈라진 자리

구현(2026-10-20, 기준 `c2fa8fb8`)은 §3 의 변경을 전부 들였다. 본문과 다른 것은 아래가 전부이고, 각 줄은
**무엇이 달라졌는가 · 왜** 를 적는다. 이 절의 클래스·메서드 이름은 구현 트리 기준이다.

### 9.1 설계와 다르게 지은 것

| # | 본문 | 구현 | 왜 |
|---|------|------|----|
| 1 | §3.12 의사코드 — `putIfAbsent` 를 이긴 뒤 재조회로 답을 찾으면 `inFlight.remove(key, mine); return …` | 재조회로 찾은 답으로 **`mine` 을 완료한 뒤** 지운다(`SideEffectApprovalGate` 의 `finally` 가 지운다) | 본문대로면 `putIfAbsent` 와 `remove` 사이에 그 `mine` 을 본 대기자가 **완료되지 않는 future** 에 영원히 매달린다. 테스트: `SideEffectApprovalGateTest.ConcurrentQuestions` |
| 2 | §3.12 · §3.16 ⑥ — `InteractiveAskPromptHandler.resolve` 를 `synchronized` | `ReentrantLock.lockInterruptibly()` 로 콘솔을 잡고, 잡은 뒤 스레드가 이미 인터럽트되어 있으면 **프롬프트를 찍지 않고** `DENY` | 모니터는 인터럽트로 깨지 않아, 부모 취소 뒤에도 **다른 질문**을 들고 줄 선 포크들이 하나씩 프롬프트를 띄워 사용자가 이미 취소된 일에 최대 N−1 번 답해야 했다. **동작 변화**: 인터럽트 플래그를 가진 채 `resolve` 를 부른 호출자는 묻지 않고 거절된다 — 그 호출에 한해서다. 게이트는 그 거절을 기억하지 않는다(9). 기능 off 의 순차 경로에서 게이트에 닿는 스레드는 플래그가 없다 — `toolRunner` 의 게이트(`isInterrupted(coordinator)`)가 도구 앞에서 플래그를 소비하기 때문이다. PermissionRequest 훅의 `ASK` 도 같은 핸들러를 지나 직렬화되지만 중복 제거는 되지 않는다(PF-4). 테스트: `InteractiveAskPromptHandlerTest` |
| 3 | §7 의 기대 — 부모 수준 승인 프롬프트에 `[task ` 접두 | 부모 수준의 `Allow 'Task(...)'?` 프롬프트에는 접두가 **없다** | `Task` 호출 자체의 게이트는 **부모의** 도구 컨텍스트에서 평가되고, 부모는 포크가 아니라 task id 속성이 없다. 접두는 포크 **안의** 도구가 묻는 질문에만 붙는다 — 그 경로는 `SideEffectApprovalGateTest` 의 라벨 테스트가 덮는다. 코드는 §3.12 그대로다 |
| 4 | §5 "승인 프롬프트 중 취소" · §3.12 대기자 항목 — "같은 질문을 기다리던 다른 포크는 interrupt 에 깨어나 DENY" | 코드는 본문대로이고, **문장이 일부 대기자에게만 참이다** | 부모 수준 `Allow 'Task(...)'?` 질문을 기다리는 전용 스레드에는 아직 terminator 가 없다 — 게이트는 `SingleToolInvoker` 에서 도구 실행 **전에** 돌고, `runForeground` 는 실행 중에야 `toolThread::interrupt` 를 등록한다. 그래서 부모 취소가 그 대기자를 깨우지 못하고, 묻던 쪽(`console.readLine()` 에 막힌, 오늘도 같은)이 답을 받을 때 함께 깨어난다. ALLOW 면 이미 트립된 레지스트라에 등록하는 순간 즉시 발화해 포크는 협력적으로 멈춘다. 깨어나는 것은 **포크 안의** 대기자뿐이다. 동작은 바꾸지 않았다 — 묻던 쪽이 이미 `readLine` 에 막혀 있으므로 대기자가 먼저 깨어도 턴은 그 답을 기다린다. PF-5 |
| 5 | §3.5 — 전용 스레드 생성 실패는 다루지 않음 | `DedicatedThreadDispatcher` 가 `newThread`/`start` 의 `RuntimeException`·`OutOfMemoryError`("unable to create native thread")를 잡아 허가를 돌려주고 그 호출을 inline 으로 돌린다 | 시작하지 못한 스레드의 `finally` 는 돌지 않으므로 허가가 샌다. inline fallback 은 §3.5 ③ 과 같은 경로라 새 동작이 아니다 |
| 6 | §3.11 — "첫 이연 인덱스 `k` 앞까지 오늘의 루프, `k` 부터 꼬리" | `OrcaAgentExecutor.deferredTailStart` 가 `k` 뒤에 eager 결과를 가진 도구가 하나라도 있으면 꼬리를 **넘기지 않고** 배치 전체를 오늘의 inline 루프로 돌린다 | prefix-safety 상 일어나지 않아야 하지만, 일어나면 그 도구가 두 번 돈다. 방어선 한 줄이다 |
| 7 | §3.8 — `forkDepth` 의 기본값 1 | `SubagentLaunchContext` · `SubagentExecutionRequest` 빌더가 1 미만을 `IllegalArgumentException` 으로 거절한다. 요청의 `forkDepth` 는 `equals`/`hashCode`/`toString` 에 넣지 않았다 | 포크는 정의상 1 이상이다. 요청의 동등성은 "무엇을 시키는가" 이고 깊이는 위치다 — `quotaGuard` 와 같은 처리다. `toString` 에 넣지 않은 것은 기능 off 의 로그 바이트를 바꾸지 않기 위해서다 |
| 8 | §3.13 — 라벨 앞 8자 | 게이트와 CLI 가 모두 `ForkAccess.shortTaskId` 로 자른다 | 프롬프트의 `[task …]` 와 CLI 의 `#…` 라벨이 같은 글자를 보여야 하고, 두 모듈이 함께 볼 수 있는 자리가 `aimon-core` 의 `ForkAccess` 다 |
| 9 | §3.12 의사코드 — 묻던 쪽은 `resolve` 의 답을 그대로 `store.remember` 하고 대기자에게 넘긴다 | 묻던 스레드가 인터럽트된 채 `resolve` 가 돌아오면 그 답을 **기억하지도 공유하지도 않고**, 묻던 쪽은 "cancelled" 사유로 거절되며 대기자는 다시 조회한 뒤 스스로 묻는다 | 그 답은 사람의 것이 아니다 — CLI 핸들러는 인터럽트된 호출자를 묻지 않고 `DENY` 한다(2). 그것을 세션 저장소에 남기면 취소 한 번이 같은 세션의 그 도구를 "사용자가 거절함" 으로 굳히고, 같은 질문을 기다리던 취소되지 않은 호출자까지 거절된다 |
| 10 | §3.12 의사코드 — 묻던 쪽이 던지면 대기자는 `ExecutionException` → `allowed = false` (fail-safe DENY) | 대기자는 거절되지 않고 다시 조회한 뒤 스스로 묻는다. 묻던 쪽의 예외는 오늘처럼 invoker 의 catch-all 로 간다 | 실패한 프롬프트는 답이 없는 것이지 거절이 아니다. 대기자에게 "not approved by the user" 를 주면 모델이 사용자가 거절한 것으로 읽고 재시도하지 않는다. 다시 묻는 쪽도 fail-safe 다 — 승인은 여전히 사람의 답으로만 생긴다 |
| 11 | §3.10 — `createDefaultSubagentExecutionManager` 를 오버라이드한 서브클래스는 다루지 않음 | `OrcaAgentExecutorFactory.withDedicatedThreadConfig` javadoc 에 한 문장 — 그런 서브클래스의 포크는 fan-out 도 task id 라벨도 받지 않는다 | 트레이싱 선례와 같은 책임 분담이다 |
| 12 | §3.5 — 포크 쪽 `onCompleted` 는 다루지 않음 | `DedicatedThreadDispatcher` 클래스 javadoc 에 한 문장 — `maxForkDepth >= 2` 에서 포크 실행기의 `stream(lc, …)` 콜백도 전용 스레드에서 돈다 | 풀 경로와 같은 이유(no-op 싱크 또는 thread-safe 저장소 append)로 안전하다는 것을 적어 둔다 |

### 9.2 문서

§3.15 의 표대로 고쳤다. 인터럽트 백로그의 4번 항목("병렬화 대상이 넓어지면 승격 규칙을 다시 본다")에는 이 변경이
그 집합을 넓히지 **않았다**는 덧붙임을 달았다.

### 9.3 Open questions 의 행방

§8 가운데 **이 변경 밖으로 결과가 나가는 것**만
[`../../backlog/parallel-foreground-task-open-items.md`](../../backlog/parallel-foreground-task-open-items.md) 로 올렸다.

| 출처 | 백로그 |
|------|--------|
| §8 Q4 — `AimonStackBuilder` · Spring 노출 | PF-1 |
| §8 Q2 — 백그라운드 `Task` 호출도 전용 허가를 쓴다 | PF-2 |
| §8 Q9 — `runForeground` 반환 뒤 PostTool · `onCompleted` 의 늦은 interrupt 창 | PF-3 |
| §9.1 의 2 — PermissionRequest 훅 `ASK` 의 중복 제거 | PF-4 |
| §9.1 의 4 — 부모 수준 승인 대기자는 부모 취소에 깨어나지 않는다 | PF-5 |
| §6.3 — `LlmCallMetadataHolder` 의 ambient 태그는 전용 스레드에서 보이지 않는다 | PF-6 |

Q1 · Q3 · Q5 · Q6 · Q7 · Q8 · Q10 ~ Q13 은 본문의 잠정 처리대로 구현했고 이 변경 안에서 닫힌 결정이라 올리지 않았다.

---

## 관련 문서

- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — 2단 게이트와 디스패처 계약 (본 설계가 얹히는 자리)
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `InterruptBehavior` · 플래그 위생(§8) · 병렬 경로의 전제(§8.5)
- [`execution.md`](execution.md) — 포크 실행, 공용 단일 도구 파이프라인(§11)
- [`../observability/subagent-trace-nesting.md`](../observability/subagent-trace-nesting.md) — span 부모의 값 전달(D1)
- [`../../overview/scope-model.md`](../../overview/scope-model.md) · [`../../overview/glossary.md`](../../overview/glossary.md) §4 — 실행 단위(턴 · 포크 · 실행), `BudgetTracker` 의 수명
- [`../../features/tool/parallel-tool-execution-guide.md`](../../features/tool/parallel-tool-execution-guide.md) — 사용자 가이드: 켜는 법과 제한사항
- [`../../features/subagent/subagent-development-guide.md`](../../features/subagent/subagent-development-guide.md) — 병렬 포그라운드 태스크와 같은 파일, `aimon.subagent.task_id`
- [`../../backlog/parallel-foreground-task-open-items.md`](../../backlog/parallel-foreground-task-open-items.md) — 남은 항목 PF-1 ~ PF-6
