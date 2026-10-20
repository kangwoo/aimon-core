# 포그라운드 Task 병렬 실행 — 전용 스레드 디스패치 (`DEDICATED_THREAD`)

> Status: **IMPLEMENTED** (2026-10-20, `aimon-core` · `aimon-cli` · `aimon-workflow-graaljs`). 아래 §1 ~ §8 과
> "관련 문서" 는 리뷰 3 을 통과한 설계 v3 그대로다 — 승인 뒤 고친 것은 이 `Status` 문단뿐이고, 본문의 `file:line` 은
> 기준 커밋 `c2fa8fb8` 의 트리를 가리킨다. 구현이 이 설계와 갈라진 자리와 리뷰 3 의 non-blocking 지적을 어떻게
> 처리했는지는 [§9](#9-구현이-이-설계와-갈라진-자리) 가 적는다. 남은 항목의 정본은
> [`../../backlog/parallel-foreground-task-open-items.md`](../../backlog/parallel-foreground-task-open-items.md) 다.
> 같은 디렉터리에 `*.en.md` 가 없으므로 이 문서의 번역본은 없다(§3.15).
>
> 대상 브랜치: `herdr/parallel-foreground-task` (base `c2fa8fb8`).
> 범위: `aimon-core` — `at.aimon.core.agent.tool` (선언 · 설정 · 디스패처 데코레이터), `at.aimon.core.tools.task`
> (`TaskTool` 선언), `at.aimon.core.tools` (포크 접근자), `at.aimon.core.subagent[.execution]` (포크 깊이 ·
> task id 전달), `at.aimon.core.toolinvocation.approval` (승인 질문 조정), `at.aimon.core.agent.impl.orca`
> (배선 · harvest 경로) · `aimon-workflow-graaljs` (깊이 한 줄) · `aimon-cli` (렌더링 라벨) · 문서.
> `aimon-bootstrap` / `aimon-spring-boot-starter` 는 코드 변경 없음 (§8 Q4).
>
> 리뷰 1 에서 바뀐 것: 기능 off 시 바이트 동일성의 증명을 §3.16 의 **전수 목록**으로 다시 썼다 — 도구 설명 변경은
> 철회, 포크 task id 속성은 기능과 함께 켜지고, 승인 게이트의 스트라이프 락은 **질문 단위 in-flight 조정**으로
> 바꿨다(§3.12). 나머지 non-blocking 지적도 반영했다(§3.11 · §3.14 · §6.7 · §7). 반론은 `rebuttal-1.md`.
> 리뷰 2 에서 바뀐 것: 포크의 task id 속성을 manager 의 **두 진입점**에서 런치 컨텍스트에 한 번 싣는다 —
> 그래야 `SubagentStart`/`SubagentStop` 훅 컨텍스트도 그 포크 자신의 id 를 본다(§3.8 · §3.13 · §7). harvest 꼬리
> 라우팅은 데코레이터가 설치됐을 때만으로 조건부가 되었고(§3.11), 게이트 의사코드의 두 틈(`Error` · 재조회)을
> 메웠으며(§3.12), §3.16 ⑤ 의 전제를 고쳤다. 반론은 `rebuttal-2.md`(모두 수용).
>
> 아래 줄 번호는 전부 base 커밋 기준이고, 경로는 따로 적지 않으면
> `modules/aimon-core/src/main/java/at/aimon/core/` 아래다.

---

## 1. 문제 재진술

한 LLM 응답에 포그라운드 `Task`(`run_in_background=false`) 호출이 여러 개 담겨 와도 지금은 전부 **순차**로
돈다. `OrcaAgentExecutor` 의 기본 디스패처는 `DefaultParallelToolDispatcher.sequential()` 이고
(`agent/impl/orca/OrcaAgentExecutor.java:370`), 병렬 디스패처가 주입되어도 게이트
`shouldParallelize` (`agent/tool/DefaultParallelToolDispatcher.java:297-313`) 는 배치의 **모든** 도구가
`CONCURRENT_SAFE` 이며 interrupt 가 `NON_INTERRUPTIBLE | COOPERATIVE` 일 때만 통과시키는데, `TaskTool` 은
`getConcurrencyBehavior()` 를 오버라이드하지 않아 `SEQUENTIAL` 이고(`tools/task/TaskTool.java` 에 오버라이드
없음 — `grep ConcurrencyBehavior` 0건) interrupt 는 `EXTERNALLY_TERMINATED` 다(`:288-291`). 그래서 서브에이전트
셋을 띄우면 벽시계는 세 실행의 합이 된다. 공유 워커 풀에 그냥 올릴 수 없는 이유는 `runForeground` 가
`toolThread::interrupt` terminator 를 등록하기 때문이다(`:618-620`): 늦게 도착한 interrupt 가 풀 워커의 **다음**
작업에 걸린다(§6.7). 백그라운드 `Task` 는 이미 전용 풀에서 병렬이지만(`subagent/DefaultSubagentExecutionManager.java:1077`)
그 풀을 포그라운드가 나눠 쓰면 긴 백그라운드 작업 뒤에 줄을 서 부모 턴이 멈춘다. 목표는 합의된 B안 —
**배치 안의 연속된 포그라운드 `Task` 구간을 서브에이전트 1개당 재사용하지 않는 전용 스레드에서 병렬로 돌리고,
부모 스레드는 join 뒤 원래 `tool_use` 순서로 결과를 재조립하되, 설정을 켜지 않으면 기존 동작이 바이트 단위로
같아야 한다** — 를, 취소 전파 · 승인 프롬프트 · 훅 · 트레이싱 · 예산 · 중첩 상한의 아홉 항목을 코드로 검증한
위에 세우는 것이다(§6).

---

## 2. 선택한 접근과 기각한 대안

### 2.1 선택 — 도구가 `DEDICATED_THREAD` 를 선언하고, 디스패처 **데코레이터**가 연속 구간을 전용 스레드로 편다

네 문장으로 요약한다.

1. **선언**: `ConcurrencyBehavior` 에 세 번째 값 `DEDICATED_THREAD` 를 더하고 `TaskTool` 이 그것을 선언한다.
   뜻은 "같은 선언을 한 이웃 도구와 동시에 돌아도 되지만, 호출마다 새로 만들고 다시 쓰지 않는 **자기 스레드**를
   받아야 한다"이다. 기존 두 값과 게이트는 손대지 않는다 — `DEDICATED_THREAD` 는 `CONCURRENT_SAFE` 가 아니므로
   기존 게이트에서는 `SEQUENTIAL` 과 똑같이 떨어진다(기능 off 시 무회귀의 근거, §3.16 ①).
2. **조정**: 새 `DedicatedThreadDispatcher` 가 기존 `ParallelToolDispatcher` 를 감싼다. 배치를 "`DEDICATED_THREAD`
   연속 구간"과 "나머지 구간"으로 자르고, 앞의 것은 자기가 전용 스레드로 펴며, 뒤의 것은 감싼 디스패처에
   **그대로** 넘긴다(기존 규칙 — 순차, 또는 전부 `CONCURRENT_SAFE` 면 공유 풀). 구간 사이 순서는 join 으로
   보존된다.
3. **상한**: 전용 스레드 수는 `DedicatedThreadConfig.maxThreads` 짜리 세마포어 하나가 **런타임 조립 전체**
   (한 `OrcaAgentExecutorFactory` 가 만든 메인 실행기 + 포크 실행기)에 걸쳐 묶는다. 백그라운드 풀과는 완전히
   따로다. 허가를 **블로킹으로 기다리지 않는다** — 남은 허가가 없으면 그 호출은 오늘처럼 호출 스레드에서 inline
   으로 돈다(백프레셔 = 호출자가 자기 속도로 느려짐). 그래서 중첩 fan-out 이 어떤 깊이로 쌓여도 교착이 없다(§6.6).
4. **적용 범위**: 메인 실행기와 포크 실행기 **둘 다**에 설치하되, 포크 깊이 `forkDepth` 가
   `DedicatedThreadConfig.maxForkDepth` 미만인 실행만 편다. 기본 `maxForkDepth = 1` 이므로 기본값으로는 세션의
   턴(과 포크 조상이 없는 실행)만 fan-out 하고 포크는 오늘처럼 순차다.

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
  (`OrcaAgentExecutorFactory.applyExecutorOverrides`, §3.10). 켜져 있어도 배치에 `DEDICATED_THREAD` 도구가
  없으면 배치 전체를 **한 번의 호출**로 그대로 넘기므로, 기존 경로의 바이트 동일성은 "호출 1회를 위임한다"는
  사실로 증명된다.
- **포크 실행기에는 풀 없는 조합을 줄 수 있다.** 오늘 factory 는 `DefaultSubagentExecutor` 에 병렬 디스패처를
  주입하지 않는다(`agent/impl/orca/OrcaAgentExecutorFactory.java:768-823` 에 `withParallelToolDispatcher` 호출
  없음). 포크에는 `new DedicatedThreadDispatcher(DefaultParallelToolDispatcher.sequential(), …)` 를 주어 **풀
  경로는 오늘 그대로 순차**로 두고 전용 스레드 fan-out 만 더한다.

### 2.3 전용 스레드가 interrupt 문제를 푸는 방식

`TaskTool.runForeground` (`:612-626`) 는 `Thread.currentThread()::interrupt` 를 per-invocation 레지스트라에
등록한다. 전용 스레드에서는 `currentThread` 가 바로 그 전용 스레드이므로 terminator 의 의미가 오늘과 **글자
그대로 같다**. 늦은 interrupt(`DefaultTerminatorRegistrar.fireAll` 이 lock 밖에서 `terminate()` 를 부르고
`unregister` 는 `fired` 면 즉시 반환 — `agent/interrupt/DefaultTerminatorRegistrar.java:56-65, 84-97`)는 그
전용 스레드에 떨어지고, 그 스레드는 future 를 채우고 허가를 돌려준 뒤 **죽는다**. 재사용이 없으니 다음 작업이
없고, 부모 스레드에는 terminator 가 등록된 적이 없다. 이것이 B안이 "재사용하지 않는" 스레드를 요구한 이유이고,
`interrupt.md` §8.5 의 전제("워커에서 관측되는 플래그는 그 워커에 대한 실제 인터럽트뿐")가 깨지지 않는 이유다.

### 2.4 기각한 대안

| 대안 | 기각 사유 |
|------|----------|
| **A. `TaskTool` 을 `CONCURRENT_SAFE` 로 선언하고 공유 풀을 쓴다** | 게이트가 `EXTERNALLY_TERMINATED` 를 명시적으로 뺀다(`DefaultParallelToolDispatcher.java:320-322`). 빼는 이유가 바로 §2.3 의 늦은 interrupt 다. 풀 워커는 재사용되므로 `toolThread::interrupt` 가 다음 작업에 걸린다 |
| **B. 디스패처가 도구 이름 `Task` 와 `run_in_background` 를 직접 본다** | `agent.tool` 이 `tools.task` 를 알게 된다(OCP 위반, §8.1 선례와 충돌). 외부 모듈의 서브에이전트형 도구(`Workflow`, GraalJS)는 혜택을 못 받는다 |
| **C. 백그라운드 풀(`SubagentBackgroundConfig`)을 나눠 쓴다** | TASK.md 가 지적한 대로 긴 백그라운드 작업 뒤 큐에서 대기하며 부모 턴이 멈춘다. 또 그 풀은 기본 큐 무제한이라 "대기 중"이 보이지 않는다 |
| **D. 상한 초과 시 세마포어에서 블로킹 대기 (깊이별 사다리)** | 한 세마포어를 깊이와 무관하게 블로킹으로 쓰면 자식이 허가를 쥔 채 손자 허가를 기다려 교착한다. 깊이별 세마포어로 풀 수는 있으나 교착 없음이 **깊이 라벨이 정확하다**는 데 의존한다 — `SubagentLaunchContext` 를 만드는 곳이 다섯(`TaskTool`, `WorkflowTool`, GraalJS `GraalJsWorkflowTool`, `SubagentBackedSkillForkExecutor`, `OrcaAgentRuntimeFactory:1160`)이고 하나라도 라벨을 빠뜨리면 교착 조건이 되살아난다. 비블로킹 + inline fallback 은 라벨이 틀려도 교착이 **불가능**하다(§6.6). 수용 기준의 "대기" 와의 차이는 §8 Q1 |
| **E. 메인 실행기에만 적용, 포크는 영구 순차** | 깊이 플러밍이 빠져 단순하지만 `Plan → Explore ×3` 같은 쓰임을 영영 막는다. 기본값(`maxForkDepth=1`)이 사실상 E 와 같으므로, E 가 주는 안전은 그대로 갖고 확장은 설정 한 줄로 열어 둔다 |
| **F. `DefaultParallelToolDispatcher` 안에 직접 구현** | 풀 · 캡 · 종료 경합과 전용 스레드 로직이 한 클래스에 섞인다. 포크에 "풀 없음 + 전용 스레드" 조합을 주려면 어차피 두 축을 분리해야 한다 |
| **G. `ToolConcurrencyConfig` 에 필드를 더한다** | `enabled` 가 풀 경로의 마스터 스위치인데(`agent/tool/ToolConcurrencyConfig.java:105-107`) 전용 스레드는 풀과 독립으로 켜고 끌 수 있어야 한다. 두 스위치를 한 객체에 두면 `applyExecutorOverrides` 의 "enabled 면 풀 생성" 조건(`:725-727`)을 갈라야 하고 설명이 꼬인다 |
| **H. `Tool` 에 호출별 술어 `wantsDedicatedThread(ToolUse)` 를 둔다** | 백그라운드 `Task` 호출은 즉시 반환하므로 전용 스레드 하나를 잠깐 쓰는 비용이 무시할 만하다(§8 Q2). 호출별 술어는 `ToolPermissionSubjectAware` 같은 두 번째 선언 채널을 또 만든다 |
| **I. 같은 파일 동시 편집을 프레임워크 락으로 막는다** | 포크마다 `ExecutionEnvironment` 가 다를 수 있어(워크플로 브랜치 — `workflow/impl/DefaultWorkflowContext.java:200`) 경로 키가 환경을 넘어 같은 파일을 뜻하지 않는다. 이미 있는 stamp 가드가 lost update 를 명시적 거절로 바꾼다(§6.5) |
| **J. 이름 `SubagentForegroundConfig` (`at.aimon.core.subagent`)** | 설정을 읽는 쪽은 `agent.tool` 의 디스패처다. `agent.tool → subagent` 는 `PackageDependencyArchitectureTest` 가 동결한 순환 쌍 `agent <-> subagent` 안에 새 변을 긋는 일이고(`:98-101`), 이름도 메커니즘(전용 스레드)이 아니라 소비자 하나(서브에이전트)를 가리켜 거짓말한다. 사용자 문서에서 "포그라운드 Task 병렬 실행" 으로 부르고 타입 이름은 메커니즘을 따른다 |
| **K. `Task` 도구 설명에 "동시에 돌 수 있다" 를 적는다** (v1) | 설명은 매 요청의 `tools` 정의로 LLM 에 가므로 기능이 꺼진 조립에서도 요청 바이트가 달라지고, 그때는 거짓이 된다. `TaskTool` 은 설정을 모르므로 조건부로 만들 스위치도 없다 — 철회(§3.6) |
| **L. 승인 게이트의 32-스트라이프 락** (v1) | `channel.resolve` 가 사람의 답을 기다리는 동안 락을 쥐므로, 같은 스트라이프에 떨어진 **무관한** 세션의 모든 게이트 호출이 멈춘다(쌍당 1/32). 질문 단위 in-flight 조정으로 교체(§3.12) |
| **M. 포크 task id 속성을 무조건 싣는다** (v1) | 기능 off 에서 모든 포크의 훅 · 도구가 새 키를 본다. 기능이 켜진 manager 만 싣는다(§3.8) |

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
일 때만 병렬화된다" 문장에 "`DEDICATED_THREAD` 도구는 §(parallel-foreground-task) 의 데코레이터가 따로 편다"
한 줄을 보탠다. eager 적격 판정 `DefaultParallelToolDispatcher.isEagerEligible` 은 `CONCURRENT_SAFE` 만 보므로(`:206-207`,
`StreamingToolScheduler.java:100` 이 그것을 부른다) `DEDICATED_THREAD` 는 eager 대상이 아니다 — 변경 없음.

### 3.2 `agent/tool/DedicatedThreadConfig.java` (신규, 불변 + 빌더)

| 필드 | 기본값 | 검증 | 뜻 |
|------|--------|------|----|
| `enabled` | `false` | — | 마스터 스위치. `false` 면 factory 가 데코레이터도, §3.8 의 manager 플래그도 설치하지 않는다 |
| `maxThreads` | `4` | `>= 1` | 동시에 살아 있을 수 있는 전용 스레드 수. 호출 스레드(inline lane)는 **세지 않는다** |
| `maxForkDepth` | `1` | `>= 1` | `forkDepth < maxForkDepth` 인 실행만 편다. `1` = 세션의 턴과 포크 조상 없는 실행만 |

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

패키지: 이 클래스와 §3.5 의 `DedicatedThreadDispatcher` 는 구체 구현이지만 `agent.tool.impl` 이 아니라 `agent.tool` 에
둔다. CLAUDE.md 의 domain + impl 분리는 알지만 `agent.tool` 에는 `impl` 하위 패키지가 없고(`exception` · `execution` ·
`generic` · `permission` · `schema` · `search` 뿐), 같은 자리의 구체 구현 — `DefaultParallelToolDispatcher`,
`DefaultToolExecutionManager`, `DefaultToolRegistry` — 이 전부 `agent.tool` 바로 아래에 있다. 이 둘만 `impl` 로
보내면 `DefaultParallelToolDispatcher` 와 갈라져 오히려 관례를 깬다. 그 하위 패키지를 새로 여는 것은 별도 정리다.

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

`OrcaAgentExecutor` 는 계속 5-인자 메서드를 부른다(턴은 항상 깊이 0 — 라우틴 · rewake 리플레이도 포크 조상이
없다). `DefaultSubagentExecutor` 만 6-인자를 부른다(§3.9).

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
final List<Slot> slots = new ArrayList<>();
for (ToolUse toolUse : segment) {                       // 입력 순서
    safeOnStarted(onStarted, toolUse);                  // 호출 스레드, 입력 순서 — 기존 계약 그대로
    if (permits.tryAcquire()) {
        final CompletableFuture<ToolUseResult> future = new CompletableFuture<>();
        final Thread thread = permits.newThread(() -> {
            try {
                future.complete(runAndComplete(runner, onCompleted, toolUse));   // onCompleted 는 전용 스레드
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                permits.release();
            }
        });
        thread.start();
        slots.add(Slot.dedicated(toolUse, future, thread));
    } else {
        slots.add(Slot.inline(toolUse, runAndComplete(runner, onCompleted, toolUse)));   // (3) 호출 스레드, 오늘 그대로
    }
}
boolean callerInterrupted = false;
final List<ToolUseResult> results = new ArrayList<>(slots.size());
for (Slot slot : slots) {                               // (4) 입력 순서로 join
    if (slot.inline) { results.add(slot.result); continue; }
    while (true) {
        try {
            results.add(slot.future.get());
            break;
        } catch (InterruptedException e) {              // (5) 호출 스레드 자체가 interrupt 된 경우
            callerInterrupted = true;
            slots.stream().filter(s -> !s.inline && !s.future.isDone()).forEach(s -> s.thread.interrupt());
        } catch (ExecutionException e) {
            results.add(ToolUseResult.error(slot.toolUse.getId(), "Tool execution error: " + cause(e)));
            break;
        }
    }
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
  `maxThreads + 1`(호출 스레드 lane) 이다 — 문서와 javadoc 에 그렇게 적는다.
- (5)(6) 기존 풀 경로는 `join()` 으로 **비인터럽트** 대기한다(`DefaultParallelToolDispatcher.java:341-350`).
  여기서는 호출 스레드가 interrupt 되면 아직 끝나지 않은 전용 스레드에 같은 지렛대(`Thread.interrupt`)를 걸고
  계속 기다린 뒤, 끝나면 플래그를 **되돌려** 둔다. 되돌리는 이유는 `isInterrupted(coordinator)` 가 플래그를
  소비해 신호로 승격하는 유일한 지점이기 때문이다(`interrupt.md` §8.2). 자식에게는 협력 신호가 주 경로이고
  (§6.7) 이 interrupt 는 부가다.
- 스레드 안의 예외는 `runAndComplete` 가 `runSafely` 와 같은 격리를 하므로 `completeExceptionally` 는 `Error`
  계열에만 닿는다. join 쪽에서 에러 결과로 바꾼다 — 배치는 깨지지 않는다.
- `onCompleted` 는 전용 스레드에서 완료 순으로 — `ParallelToolDispatcher` javadoc `:50-52` 의 기존 계약과 같다.
  `runner` 가 돌아온 **뒤** 전용 스레드에서 돌므로, 늦은 interrupt 가 `onCompleted`(`emitToolResultReady`)에
  떨어질 수 있다 — 순차 경로가 오늘 부모 스레드에서 갖는 것과 같은 창이다(§6.7 잔여 목록).

### 3.6 `tools/task/TaskTool.java`

- `getConcurrencyBehavior()` 오버라이드 → `DEDICATED_THREAD` (javadoc 에 §2.3 의 근거). 기능 off 에서 관측되는
  유일한 차이는 이 getter 의 반환값이고, 그것을 읽는 둘(`shouldParallelize`, `isEagerEligible`)은
  `CONCURRENT_SAFE` 만 참으로 보므로 결정이 바뀌지 않는다(§3.16 ①).
- `buildLaunchContext` (`:525-586`) 에 `.forkDepth(ForkAccess.childDepthOf(context))` 한 줄.
- **도구 설명은 바꾸지 않는다.** v1 은 `buildDescription` (`:303-330`) 에 "포그라운드 태스크는 동시에 돌 수
  있다"는 문장을 더했는데, 설명은 매 요청의 `tools` 정의로 LLM 에 가므로 기능이 꺼진 조립에서도 요청 바이트가
  달라지고, 그때는 사실도 아니다(리뷰 1, §2.4 K). 같은 파일 지침(§6.5)은 사람에게 주는 가이드 두 곳(§3.15)에
  두고, 모델에게 주고 싶은 운영자는 에이전트 지시문이나 시스템 프롬프트에 적는다 — 그것이 설정과 같이 켜고 끌
  수 있는 자리다.
- `runForeground` 는 **무변경** — `Thread.currentThread()` 가 전용 스레드가 되는 것으로 충분하다.

### 3.7 `tools/ToolContextKeys.java` + `tools/ForkAccess.java` (신규)

- `ToolContextKeys.FORK_DEPTH` — `ToolContextKey<Integer>` (`"forkDepth"`). javadoc: "이 실행이 몇 단계 포크인가.
  세션의 턴과 포크 조상 없는 실행에는 없다(= 0). 포크는 자기 깊이를 싣고, 그 값은 스포너의 깊이 + 1 이다.
  `CALLER_ALLOWED_TOOLS` 처럼 실행마다 다시 실어 중첩을 어떤 깊이로도 따라간다."
- `ForkAccess` (신규, `CallerAllowedTools.of` / `InvokingSessionAccess.idToPropagate` 와 같은 자리의 같은 모양):
  - `int depthOf(ToolContext)` — 없으면 0. `int childDepthOf(ToolContext)` — `depthOf + 1`.
  - `String TASK_ID_ATTRIBUTE = "aimon.subagent.task_id"` — 포크의 실행 속성 키(§3.8). `tools` 에 두는 이유:
    읽는 쪽 `toolinvocation.approval`(§3.12)과 쓰는 쪽 `subagent`(§3.8)가 **둘 다 이미** `at.aimon.core.tools`
    를 import 한다(`toolinvocation/SingleToolInvoker.java:33-35`, `DefaultSubagentExecutionManager.java:76`).
    그래서 `PackageDependencyArchitectureTest` 의 동결 목록(`:98-101`)에 새 쌍이 생기지 않는다. `subagent` 에
    두면 `toolinvocation -> subagent` 가 목록에 없는 새 순환이다.
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
  v2 는 요청(`:766-767`)에만 덧붙였는데, `fireSubagentStart` 는 그보다 **먼저** `runExecute` 에서 스포너의 속성으로
  불리므로 `[Subagent]` 줄은 최상위 포크에서도 라벨을 받지 못했고, 중첩 포크의 Start/Stop 은 **부모 포크의** id 를
  실었다(리뷰 2). 두 진입점에서 한 번 싣고 아래로 흘리면 세 소비자가 같은 맵을 보고, 중첩 포크는 자기 진입점에서
  자기 id 로 덮어쓴다. factory 는 `DedicatedThreadConfig.enabled` 일 때만 켠다(§3.10) — 기능 off 에서 포크의
  훅 · 도구는 오늘과 같은 키 집합을 본다(§3.16 ④).

  실행 속성이 닿는 곳(선례 `SubagentTaskCompleted.RECEIVED_TASK_ID_ATTRIBUTE`,
  `agent/stream/SubagentTaskCompleted.java:74` 와 같은 채널): 그 포크의 `SubagentStart/Stop` 컨텍스트(`:1048, 1069`,
  위의 재구성 덕분에 **그 포크 자신의** id), 포크 안의 도구 훅 컨텍스트(`SingleToolInvoker.java:201, 226, 324, 347`),
  `OnStopContext` (`DefaultSubagentExecutor.java:1292`), 포크의 `EXECUTION_ATTRIBUTES_KEY`
  (`DefaultSubagentExecutor.java:910`), 그 포크가 띄우는 포크의 **스포너 속성**(자식은 자기 진입점에서 자기 id 로
  덮어쓴다). 닿지 **않는** 곳: span
  (`received_task_id` 한 키만 명시적으로 읽는다 — `OrcaAgentExecutor.java:2215-2222`; `openSubagentSpan` 은 속성을
  복사하지 않는다), 영속 `TaskResult`(`subagent/task/TaskResult.java:108-119` 는 기간 · 토큰만 옮긴다),
  `SubmitOptionsCodec`(턴 제출 전용), 선언형 훅의 `${context.X}`(고정 필드 여섯 — `skill/hook/declarative/DeclarativePreToolHook.java:340-348`),
  셸 훅 stdin(`ShellHookPayload` 에 속성 없음). 그래서 기존 트레이싱 테스트(ST-12 포함)의 span 속성 집합은
  변하지 않는다. `environment.*` 접두 규칙(EE-102)과도 충돌하지 않는다.

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

private boolean dedicatedThreadsEnabled() {
    return dedicatedThreadConfig != null && dedicatedThreadConfig.isEnabled();
}

private DedicatedThreadPermits dedicatedThreadPermits() {           // factory 당 하나, lazy
    …  // DedicatedThreadPermits.of(dedicatedThreadConfig)
}

private OrcaAgentExecutor applyExecutorOverrides(OrcaAgentExecutor executor) {           // :724
    ParallelToolDispatcher dispatcher = null;
    if (toolConcurrencyConfig != null && toolConcurrencyConfig.isEnabled()) {
        dispatcher = new DefaultParallelToolDispatcher(toolConcurrencyConfig);          // 기존 :726 그대로
    }
    if (dedicatedThreadsEnabled()) {
        dispatcher = new DedicatedThreadDispatcher(
                dispatcher != null ? dispatcher : DefaultParallelToolDispatcher.sequential(),
                dedicatedThreadConfig, dedicatedThreadPermits());
    }
    if (dispatcher != null) {
        executor.parallelToolDispatcher = dispatcher;
    }
    …
}

protected SubagentExecutionManager createDefaultSubagentExecutionManager(…) {            // :768
    final DefaultSubagentExecutor subagentExecutor = new DefaultSubagentExecutor(…);     // :778
    …
    if (dedicatedThreadsEnabled()) {
        subagentExecutor.withParallelToolDispatcher(new DedicatedThreadDispatcher(
                DefaultParallelToolDispatcher.sequential(), dedicatedThreadConfig, dedicatedThreadPermits()));
    }
    …
    // withSubagentTracing(...) 와 같은 자리: 켜졌을 때만 manager.withForkTaskIdAttribute()   (§3.8)
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
  `DedicatedThreadDispatcher` 뿐이다(§3.5). 그래서 이 경로는 데코레이터가 설치됐을 때만 — 즉 기능이 켜졌을 때만 —
  바뀐다(§3.16 ⑩ **조건부**).

v2 는 "poison 은 두 경우뿐이라 off 에서도 `dispatch(tail)` 이 순차로 떨어진다" 로 비조건부를 증명하려 했다. 그
증명은 응답의 모든 `tool_use` 가 `onToolUseReady` 를 거쳤다는 가정 위에 있었다. 스케줄러는 스트림 청크의
`TOOL_USE_READY` 로만 먹이고(`OrcaAgentExecutor.java:3626`), 그 이벤트를 내는 것은 공급자 매퍼다
(`AnthropicStreamingMapper.java:260` 은 `content_block_stop(tool_use)` 마다; `ChunkAggregator.finalizeToolCall` 이
값을 돌려줄 때만 — 리뷰 2). `LlmClient` 는 SPI 이므로 첫 도구만 READY 를 내고 뒤의 `CONCURRENT_SAFE` 도구는 내지
않는 공급자가 가능하고, 그러면 꼬리가 적격 도구로 시작해 풀 디스패처가 병렬화한다 — 기능 off 에서. 조건부가 이
가정을 없앤다. 기능 **on** 에서는 꼬리가 데코레이터로 가고, 그 안의 비-`DEDICATED_THREAD` 구간은 delegate(메인
실행기라면 풀 디스패처일 수 있다)가 기존 게이트로 판정한다 — 오늘 inline 이던 `CONCURRENT_SAFE` 구간이 풀로 갈 수
있는데, 그것은 opt-in 뒤의 동작이고 가이드에 적는다. §7 에 on/off 양쪽의 이벤트 순서 테스트.

### 3.12 `toolinvocation/approval/SideEffectApprovalGate.java` — 같은 질문은 한 번만, 다른 질문은 서로 막지 않는다

`denialReason` (`:181-215`) 의 "lookup → 프롬프트 → remember" (`:199-206`) 는 지금 원자적이지 않다. 같은 세션
스코프의 포크 둘이 같은 도구를 동시에 쓰면 둘 다 miss 하고 둘 다 묻는다. 그리고 `TaskTool` 자신이
`SideEffectLevel`/`DestructiveBehavior` 를 오버라이드하지 않아 기본 `MUTATING`/`DESTRUCTIVE`(`Tool.java:238-240,
278-280`)이므로 **`Task` 호출 자체가** 게이트 대상이다 — 병렬 `Task` N개가 동시에 "Allow 'Task(...)'?" 를 N번
띄운다.

- **질문 단위 in-flight 조정.** 게이트에 `ConcurrentHashMap<String, CompletableFuture<Boolean>> inFlight` 를
  둔다. 키는 `scopeKey + '\0' + toolName` — 기억(`store`)의 키와 같은 단위다.

  ```java
  // denialReason — 스코프 키가 있을 때
  remembered 가 있으면 그대로 (오늘과 같음)
  final CompletableFuture<Boolean> mine = new CompletableFuture<>();
  final CompletableFuture<Boolean> prior = inFlight.putIfAbsent(key, mine);
  if (prior == null && store.lookup(scopeKey, toolName).isPresent()) {   // 이겼지만 그 사이 답이 생겼다 (아래 ③)
      inFlight.remove(key, mine);
      return remembered 와 같은 처리;
  }
  if (prior != null) {                                   // 같은 질문이 이미 사람 앞에 있다
      try {
          allowed = prior.get();                         // 인터럽트 가능 대기
      } catch (InterruptedException e) {
          Thread.currentThread().interrupt();            // 플래그는 호출자(도구 스레드)의 것
          return Optional.of(cancelledMessage(toolName));
      } catch (ExecutionException e) {
          allowed = false;                               // 묻던 쪽이 실패 → fail-safe
      }
  } else {
      try {
          final Decision answer = channel.resolve(promptFor(label, toolName, toolUse, declared, destructive));
          allowed = answer == Decision.ALLOW;
          store.remember(scopeKey, toolName, allowed);   // complete 보다 먼저: 깨어난 대기자가 store 를 봐도 답이 있다
          mine.complete(allowed);
      } finally {
          if (!mine.isDone()) {                          // RuntimeException 이든 Error 든 — 대기자를 매달아 두지 않는다
              mine.completeExceptionally(new IllegalStateException("approval prompt did not complete"));
          }
          inFlight.remove(key, mine);
      }                                                  // 던져진 것은 오늘처럼 invoker 의 catch-all (SingleToolInvoker.java:253) 로
  }
  ```

  의사코드의 두 틈을 리뷰 2 가 찾았고 위에 메웠다. ① `resolve` 가 `Error` 를 던지면 v2 의 `catch (RuntimeException)`
  은 `mine` 을 완료하지 않아 대기자가 `get()` 에 영원히 매달렸다 — `finally` 에서 미완이면 예외 완료. ③ 재조회:
  X 가 `store.lookup` 에서 miss 한 뒤 멈춘 사이 A 가 묻고 `remember` → `complete` → `remove` 를 끝내면, X 의
  `putIfAbsent` 가 이겨 **같은 질문을 다시** 묻는다 — 이긴 직후 `store` 를 한 번 더 본다. 둘 다 §7 에 테스트.

  - 같은 스코프 · 같은 도구: 프롬프트 1회, 나머지는 그 답을 공유한다.
  - 다른 스코프 또는 다른 도구: **서로를 전혀 기다리지 않는다.** v1 의 32-스트라이프 락은 무관한 세션 둘이 같은
    스트라이프에 떨어지면(쌍당 1/32) 한쪽 사용자가 답하지 않는 동안 다른 세션의 모든 게이트 호출이 멈추는 새
    liveness 결함이었다(리뷰 1, §2.4 L) — 철회.
  - 맵은 진행 중인 질문 수만큼만 크고 `finally` 에서 비운다. 세션 종료 시 지울 것이 없다.
  - 대기자가 interrupt 되면(부모 취소가 전용 스레드를 interrupt — §6.7) 다른 포크의 프롬프트에 매달리지 않고
    플래그를 되돌린 뒤 DENY 사유("Tool '<name>' was not approved: this run was cancelled while waiting for the
    user's answer.")로 돌아온다 — 취소 중인 실행이 "allow" 로 읽히지 않는 fail-safe 방향. `store` 에는 아무것도
    남기지 않는다.
  - 스코프 없는 실행(`:85-92`)은 오늘처럼 캐시도 조정도 없이 매번 묻는다(키가 없으므로 `inFlight` 를 거치지 않는다).
  - 동시 호출이 없으면 — 기능 off 의 순차 실행 — `putIfAbsent` 는 항상 비어 있고 프롬프트 · 순서 · 답 · 저장
    내용이 오늘과 같다. 이 변경은 **의도적으로 비조건부**다(§3.16 ⑤): 조립이 만드는 게이트에는 기능 스위치가
    없고(`aimon-cli` `AgentSetupFactory`), 오늘 이미 동시일 수 있는 두 경로 — 백그라운드 포크, 그리고 풀이 켜진
    배치의 게이트 대상 `CONCURRENT_SAFE` 도구(`SessionHistoryTool` 은 `CONCURRENT_SAFE` 를 선언하면서
    `getSideEffectLevel`/`getDestructiveBehavior` 를 오버라이드하지 않아 기본 `MUTATING`/`DESTRUCTIVE` 로 게이트에
    걸린다 — `tools/session/SessionHistoryTool.java:145-146`; 외부 모듈 도구도 같은 선언이 가능하다) — 의 잠재적
    이중 프롬프트까지 같은 코드가 고친다. v2 는 "`CONCURRENT_SAFE` 도구는 읽기 전용이라 면제" 라고 적었는데
    틀렸다(리뷰 2).
- **프롬프트 라벨은 속성에서, 속성은 기능이 켜졌을 때만.** `promptFor` 는 `private static` 이고 컨텍스트를 받지
  않으므로(`:255-263`) 라벨 인자 하나를 **더한다** — public `denialReason(Tool, ToolUse, ToolContext)` 만
  그대로다(v1 의 "시그니처 무변경" 표현은 틀렸다). 라벨은 `ForkAccess.taskIdOf(context)` 가 있을 때만
  `"[task 3fa2c1d0] "` (앞 8자 — CLI 의 `[Subagent]` 줄과 같은 표기, §3.13). 그 속성은 §3.8 대로 기능이 켜진
  manager 만 싣는다 → off 에서 프롬프트 문자열은 바이트 동일. v1 의 `[executionId]` 접두(모든 포크, 무조건)는
  철회.
- `AskPromptHandler` 는 "구현은 thread-safe 해야 한다"고 계약한다(`hook/execution/AskPromptHandler.java:17`).
  CLI 의 `InteractiveAskPromptHandler.resolve` (`modules/aimon-cli/.../hook/InteractiveAskPromptHandler.java:68-94`)
  는 `out.print` 와 `console.readLine()` 을 동기화 없이 한다 — `resolve` 를 `synchronized` 로 만든다(다른 질문
  — 다른 스코프 · 다른 도구 — 이 한 콘솔에 동시에 오면 한 번에 하나씩). 비경합 시 차이 없음(§3.16 ⑥). 질문 단위
  dedupe 는 게이트, 콘솔 단위 직렬화는 핸들러 — 두 책임을 나눈다.

### 3.13 `aimon-cli` — 렌더링 (`modules/aimon-cli/src/main/java/at/aimon/cli/…`)

포그라운드 포크는 부모 이벤트 publisher 로 **아무 스트림 이벤트도 보내지 않는다**(§6.2). CLI 가 보는 것은
훅이다 — `SubagentLaunchDisplayHook`(SubagentStart) · `ToolCallDisplayHook`(PreTool, `InvokerType` 들여쓰기) ·
`SubagentResultDisplayHook`(부모 PostTool, `Task` 결과 텍스트 파싱). 병렬이 되면 세 훅의 줄이 포크 사이에서
섞이고, 같은 서브에이전트 종류의 포크 둘은 구분이 안 된다.

- **라벨은 속성이 있을 때만** — 즉 기능이 켜졌을 때만(§3.8). `ToolCallDisplayHook.execute`
  (`hook/ToolCallDisplayHook.java:41-48`): `ForkAccess.taskIdOf(context.getExecutionAttributes())` 가 있으면
  라벨 `invokerName + "#" + taskId 앞 8자` 를 `displayToolCall` 의 새 오버로드로 넘긴다 →
  `  [Tool] (Explore#3fa2c1d0) Read: {…}`. 없으면 오늘의 오버로드 — 출력 동일.
- `SubagentLaunchDisplayHook` (`:37-38`): `ForkAccess.taskIdOf(context.getExecutionAttributes())` 가 있을 때만 그
  값(= `context.getTaskId()` — §3.8 의 재구성 덕분에 Start 컨텍스트가 **이 포크의** id 를 싣는다) 앞 8자로 같은 라벨
  → `  [Subagent] Explore#3fa2c1d0: …`. `getTaskId()` 는 항상 있지만 라벨을 속성에 묶는 이유는 하나 — off 에서 출력이
  같아야 한다. v2 에서는 Start 컨텍스트가 스포너의 속성을 실어 최상위 포크의 launch 줄이 **라벨을 받지 못했고**
  `[Tool]` 줄만 id 를 달아 짝이 맞지 않았다(리뷰 2).
- `OutputFormatter.displaySubagentResult` (`repl/OutputFormatter.java:264-296`) 와
  `displaySubagentCompletionReason` (`:313`): 여러 번의 `System.out.println` 을 한 `StringBuilder` 로 모아
  **한 번** 출력한다. `PrintStream` 은 호출 단위로만 동기화되므로 멀티라인 블록은 한 호출이어야 섞이지 않는다.
  바이트는 같고 write 호출 수만 다르다 — 의도적으로 비조건부(§3.16 ⑦). `displayToolCall` / `displaySubagentLaunch`
  는 이미 한 줄 한 호출이다.
- `SubagentResultDisplayHook` 의 정규식(`:36-38`)은 손대지 않는다 — `Task` 결과 텍스트 형식을 바꾸면 깨진다는
  경고가 `TaskTool.formatSubagentResult` javadoc(`:633-638`)에 있다. 결과 블록에는 이미 `Task: <description>`
  이 있어 귀속에 충분하다.

### 3.14 다른 spawn 사이트 — 깊이 전달

`SubagentLaunchContext.builder()` 를 `ToolContext` 로부터 만드는 곳은 **넷**이고 전부 같은 한 줄을 받는다:
`tools/task/TaskTool.java:567` · `tools/workflow/WorkflowTool.java:522` ·
`skill/fork/SubagentBackedSkillForkExecutor.java:159` ·
`modules/aimon-workflow-graaljs/src/main/java/at/aimon/workflow/graaljs/GraalJsWorkflowTool.java:267` (별도
모듈 — `ForkAccess` 가 public 이어야 하는 이유) → `.forkDepth(ForkAccess.childDepthOf(context))`. 다섯 번째인
agent-scoped base 컨텍스트(`agent/impl/orca/OrcaAgentRuntimeFactory.java:1160`, 라우틴 · 런타임 러너)는
`ToolContext` 가 없으므로 기본값 1 을 그대로 둔다. v1 은 GraalJS 사이트를 빠뜨렸다 — 비블로킹 허가라 교착은
없지만 `maxForkDepth >= 2` 에서 깊이를 적게 세어 fan-out 을 더 허용했을 것이다(리뷰 1).

### 3.15 문서

| 문서 | 변경 | 번역본 |
|------|------|--------|
| `docs/design/subagent/parallel-foreground-task.md` | 이 문서 (신규 정본) | 없음 — 같은 디렉터리에 `*.en.md` 가 하나도 없다 |
| `docs/design/README.md` | `subagent` 표와 `tool` 표에 행 추가, §2 색인 | `README.en.md` 없음 |
| `docs/design/tool/parallel-execution.md` | §2 "혼합 배치는 전체 순차", §8.2 "값은 둘뿐", §9 표의 "`THREAD_INTERRUPT` 도구의 병렬 — 범위 밖" 에 이 문서로 가는 한 줄씩. 결론을 고치지 않고 "그 뒤 바뀌었다" 로 적는다(그 문서 §5 의 선례) | 없음 |
| `docs/design/agent-execution/interrupt.md` | §8.5 "이 전제가 깨지면 승격 규칙을 재검토해야 한다" 바로 아래에 §2.3 요약 한 문단 — 전제는 **유지**된다는 결론 | 없음 |
| `docs/features/tool/parallel-tool-execution-guide.md` | §2 에 "포그라운드 Task 병렬" 켜는 법, §6 표에 `DEDICATED_THREAD` · `DedicatedThreadConfig`, §9 제한사항에 세 줄 — 동시 포그라운드 포크 ≤ `maxThreads + 1`; 백그라운드 `Task` 호출도 허가를 잠깐 쓰고 그 `SubagentStart` 는 입력 순서 보장이 없다(§8 Q2); LLM 공급자 rate limit 은 `maxThreads` 로만 조절한다(Q10) | **`.en.md` 있음** — 같은 커밋에서 갱신, `source_commit` 은 수정 직전 정본 커밋 |
| `docs/features/subagent/subagent-development-guide.md` | "병렬 포그라운드 태스크와 파일" 한 문단(§6.5 지침) + 포크의 훅 · 도구가 보는 `aimon.subagent.task_id` 한 줄(기능이 켜졌을 때만) | **`.en.md` 있음** — 동일 |
| `CHANGELOG` | Added 항목 | — |

세 스크립트(`check-translation-structure.py`, `check-doc-links.py`, `check-translation-staleness.py`)는 TASK.md 가
요구한 대로 통과해야 한다.

### 3.16 기능 off 시 바이트 동일 — 변경 전수와 각각의 근거

TASK.md 의 "설정을 켜지 않으면 기존 동작이 바이트 단위로 동일" 은 §5 첫 줄 한 문장으로 증명되지 않는다 — 이
설계의 변경 하나하나가 조건부이거나, 비조건부라면 **동시 호출이 없을 때 관측 불가**여야 한다. 전수는 아래다.
"관측" 은 LLM 요청 바이트 · 도구 · 훅이 보는 키 · 프롬프트 문자열 · CLI 출력 바이트 · 이벤트 열 · 결과 순서를
뜻한다.

| # | 변경 | 조건부? | off 에서 관측 불가인 이유 |
|---|------|---------|---------------------------|
| ① | `TaskTool.getConcurrencyBehavior()` → `DEDICATED_THREAD` (§3.6) | 비조건부 (정적 선언) | 읽는 곳은 둘 — `shouldParallelize` (`:307`) 와 `isEagerEligible` (`:206`) — 둘 다 `== CONCURRENT_SAFE` 만 참. `SEQUENTIAL` 과 같은 결정. ArchUnit · 스위치문 · 위임 래퍼 없음(리뷰 1 확인) |
| ② | 데코레이터 · `DispatchOptions` · 6-인자 default 메서드 (§3.4 · §3.5 · §3.9) | **조건부** — factory 가 enabled 일 때만 설치 | 미설치 시 `executor.parallelToolDispatcher` 는 오늘 값. 6-인자 default 는 5-인자로 위임 |
| ③ | `FORK_DEPTH` 키 · `forkDepth` 필드 (§3.7 · §3.8 · §3.9 · §3.14) | 비조건부 | `ToolContext` 키 하나가 포크의 도구에 더 보인다. 읽는 코드는 spawn 사이트 넷뿐이고, 그 값은 런치 컨텍스트의 내부 필드로만 흐른다 — 훅 컨텍스트 · 속성 · span · 영속 레코드에 닿지 않는다. `ToolContext` 는 모듈 enricher 가 키를 더하는 **가산적** 채널이고(`createToolContext` 의 `applyEnrichers`), 키의 존재 자체는 동작이 아니다. 조건부로 만들려면 포크 실행기가 설정을 알아야 하는데 그 배관이 설계의 모든 이득보다 크다 |
| ④ | 포크 task id 실행 속성 (§3.8) | **조건부** — manager 플래그, factory 가 enabled 일 때만 | off 에서 훅 · 도구 · 중첩 포크가 보는 속성 집합이 오늘과 같다 |
| ⑤ | 승인 게이트의 질문 단위 in-flight 조정 (§3.12) | 비조건부 (게이트는 설정을 모른다) | 동시 호출이 없으면 `putIfAbsent` 가 항상 비어 있어 프롬프트 문자열 · 횟수 · 순서 · 저장 내용이 오늘과 같다. 오늘 동시일 수 있는 경로는 둘 — 백그라운드 포크, 그리고 `ToolConcurrencyConfig` 가 켜진 배치의 게이트 대상 `CONCURRENT_SAFE` 도구(`SessionHistoryTool.java:145-146` 은 기본 `MUTATING`/`DESTRUCTIVE` 라 게이트에 걸린다; 외부 도구도 가능). 그 둘에서 "같은 질문 두 번(두 `readLine` 이 섞일 수 있음)" 이 "한 번" 으로 바뀐다 — 이 기능이 꺼진 **도달 가능한** 구성에서의 동작 변화이며, 결함 수정으로 **의도적으로 받아들인다**(§8 Q13) |
| ⑥ | `InteractiveAskPromptHandler.resolve` `synchronized` (§3.12) | 비조건부 | 비경합 시 모니터 획득 외 차이 없음 |
| ⑦ | `OutputFormatter` 멀티라인 블록 단일 출력 (§3.13) | 비조건부 | 같은 바이트, 같은 순서, write 호출 수만 다름 |
| ⑧ | CLI 라벨 (§3.13) | **조건부** — ④ 의 속성이 있을 때만 | off 에서 오늘의 오버로드 호출 → 출력 동일 |
| ⑨ | 프롬프트 `[task …]` 라벨 (§3.12) | **조건부** — ④ 의 속성이 있을 때만 | off 에서 프롬프트 문자열 동일 |
| ⑩ | harvest 꼬리 라우팅 (§3.11) | **조건부** — `routesHarvestTail()` 이 참인 디스패처(데코레이터)일 때만 | off 에서 harvest 코드 경로가 오늘과 같다. v2 의 "poison 두 경우" 증명은 모든 `tool_use` 가 READY 를 거쳤다는 공급자 가정 위에 있었고(리뷰 2) 더는 필요 없다 |
| ⑪ | `Task` 도구 설명 변경 (v1) | — | **철회** (§3.6) |

① ③ ⑤ ⑥ ⑦ 의 "비조건부" 는 리뷰어가 요구한 **명시적 선언**이다 — §4 와 §5 첫 줄이 이 표를 가리킨다.
③ 은 "키가 하나 더 보인다" 는 뜻에서 엄밀한 바이트 동일이 아니지만 리뷰 2 가 받아들였다(§8 Q12). ⑤ 는 기능이
꺼진 도달 가능한 구성(풀 on + 게이트 대상 `CONCURRENT_SAFE` 도구, 또는 백그라운드 포크)에서 "두 번 묻던 것을 한 번
묻는" 동작 변화가 있다 — 유일하게 off 경로의 **동작**이 달라지는 항목이고, 결함 수정으로 받아들인다(§8 Q13).

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
| `ToolContextKeys.FORK_DEPTH` (신규) + `ForkAccess` (신규) | 포크 깊이, task id 속성 키와 reader | 턴에는 없음(=0). 포크에는 무조건 (§3.16 ③) |
| `SubagentLaunchContext` / `SubagentExecutionRequest` | `forkDepth` (기본 1) | 빌더 필드 추가, 기존 호출 무변경 |
| `DefaultSubagentExecutionManager` | `withForkTaskIdAttribute()` — 켜지면 두 진입점에서 런치 컨텍스트의 실행 속성에 `aimon.subagent.task_id` 를 싣는다(Start/Stop 훅 · 요청 · 포크 도구가 같은 맵을 본다) | 기본 off (§3.16 ④) |
| `OrcaAgentExecutorFactory` | `withDedicatedThreadConfig` | 기본 null |
| `SideEffectApprovalGate` | 질문 단위 in-flight 조정, 속성 기반 라벨, `promptFor` 에 라벨 인자(private) | public 시그니처 무변경. 비경합 시 동일 (§3.16 ⑤ ⑨) |
| `InteractiveAskPromptHandler` (CLI) | `resolve` `synchronized` | §3.16 ⑥ |
| `OutputFormatter` (CLI) | 라벨 오버로드, 멀티라인 단일 출력 | §3.16 ⑦ ⑧ |

바뀌지 **않는** 것: `DefaultParallelToolDispatcher`, `SingleToolInvoker`, `DefaultTerminatorRegistrar`,
`DefaultInterruptCoordinator`, `TaskTool.runForeground`, `TaskTool.buildDescription`, `BudgetTracker`, 트레이싱
전부, `SubagentResultDisplayHook` 의 파서.

---

## 5. 실패 모드와 처리

| 상황 | 처리 |
|------|------|
| 설정 off (기본) | 데코레이터와 manager 플래그 미설치. 조건부 변경(§3.16 ② ④ ⑧ ⑨ ⑩)은 꺼지고, 비조건부 변경(① ③ ⑥ ⑦)은 동시 호출이 없을 때 관측 불가, ⑤ 는 풀 on + 게이트 대상 `CONCURRENT_SAFE` 도구 / 백그라운드 포크에서만 "두 번 → 한 번" — 근거는 §3.16 표의 행마다 |
| 설정 on, 배치에 `DEDICATED_THREAD` 없음 | delegate 에 배치 전체 1회 위임 → 동일 |
| 설정 on, 깊이 ≥ `maxForkDepth` | 위 줄과 같음 (포크는 기본적으로 오늘처럼 순차) |
| 허가 없음 (다른 턴 · 다른 포크가 다 쥠) | 그 호출은 호출 스레드 inline. 결과 순서 · 이벤트 순서 불변. 교착 없음 |
| 전용 스레드에서 runner 가 던짐 / null | `runAndComplete` 가 에러 `ToolUseResult` 로 격리 (`runSafely` 와 동일) |
| 전용 스레드에서 `Error` | `completeExceptionally` → join 이 에러 결과로 변환, 허가는 `finally` 로 반환 |
| 부모 취소 (coordinator 경유) | 협력 신호 캐스케이드 + 각 전용 스레드 `interrupt` (§6.7). 아직 안 시작한 호출은 runner 게이트가 skip (`OrcaAgentExecutor.java:2454-2458`, 전용 스레드에서 평가) |
| 부모 **스레드** interrupt (join 중) | 미완 전용 스레드에 interrupt 전파, 계속 대기, 끝난 뒤 플래그 재무장 → 루프 체크포인트가 승격 |
| 늦은 interrupt | 전용 스레드에 떨어지고 그 스레드와 함께 사라진다. inline lane 은 오늘과 같은 체크포인트 흡수. 잔여 창은 §6.7 |
| 승인 프롬프트 중 취소 | `console.readLine()` 은 interrupt 로 깨지지 않는다 — 오늘 순차 `Task` 에서도 동일. 사용자가 답하면 묻던 스레드가 신호를 보고 끝난다. **같은 질문을 기다리던** 다른 포크는 interrupt 에 깨어나 DENY 로 돌아온다(§3.12) |
| 승인 동시 요청 (같은 스코프, 같은 도구) | in-flight future 공유 → 1회 프롬프트, 나머지는 같은 답 |
| 승인 동시 요청 (다른 스코프 · 다른 도구) | 서로 독립. CLI 는 핸들러의 `synchronized` 로 한 번에 하나씩 보여 준다 |
| 묻던 쪽이 예외로 끝남 | 대기자는 `ExecutionException` → DENY (fail-safe), 묻던 쪽은 오늘처럼 invoker 의 catch-all |
| 같은 파일 동시 편집 | 두 번째 포크의 `Edit` 가 `CHANGED_MESSAGE` 로 거절되고 다시 읽으라는 안내 (§6.5) |
| CLI 출력 섞임 | 줄 단위 원자성 + 라벨 + 멀티라인 블록 단일 출력 (§3.13). 줄 **사이** 인터리빙은 받아들인다 |
| 스트리밍 중첩 + eager 접두 | 꼬리를 디스패처로 (§3.11). 접두는 오늘처럼 join |
| 외부 `ParallelToolDispatcher` 구현체 | 6-인자 default 가 5-인자로 위임 → 깊이 무시, 동작 불변 |
| `DefaultSubagentExecutor` 가 데코레이터 없이 6-인자 호출 | `sequential()` 의 default 메서드가 위임 → 동작 불변 |
| 트레이싱 on | 부모 span 은 값으로 전달되므로 스레드와 무관 (§6.3) |
| factory 를 거치지 않는 조립 (`OrcaAgentExecutor.builder().parallelToolDispatcher(...)`) | 조립자가 직접 `DedicatedThreadDispatcher` 를 감싸 주입하고 manager 플래그도 직접 켠다. 포크 쪽은 `withParallelToolDispatcher` — 기존 feature 가이드 §2.2 와 같은 책임 분담 |

---

## 6. 설계 단계 검증 항목 아홉 가지 — 코드로 확인한 결론

### 6.1 부작용 승인 프롬프트(side-effect gate)의 동시 발생

**확인한 코드.** 게이트는 `SingleToolInvoker.dispatch` 의 PermissionRequest 뒤, PreTool 앞에서 호출되고
(`toolinvocation/SingleToolInvoker.java:213-219`) 그 파이프라인은 전용 스레드에서 돈다. `denialReason`
(`toolinvocation/approval/SideEffectApprovalGate.java:181-215`) 은 `store.lookup` (`:199`) → `channel.resolve`
(`:204`) → `store.remember` (`:206`) 를 락 없이 잇는다. 스코프 키는 턴이면 `SESSION_ID`, 포크면
`INVOKING_SESSION_ID` (`:240-244`) 라 같은 세션의 포크들은 **같은 키**를 쓴다. 저장소 `InMemoryToolApprovalStore`
는 `ConcurrentHashMap` 이라 thread-safe 지만(`:23, 37`) 원자적 "없으면 묻기" 는 아니다. `TaskTool` 은
`getSideEffectLevel`/`getDestructiveBehavior` 를 오버라이드하지 않으므로(`grep` 0건) 기본 `MUTATING`/`DESTRUCTIVE`
(`agent/tool/Tool.java:238-240, 278-280`) → **`Task` 호출 자체가 묻는 대상**이다. CLI 핸들러
`InteractiveAskPromptHandler.resolve` (`aimon-cli …/hook/InteractiveAskPromptHandler.java:68-94`) 는 javadoc 이
"Thread-safe" 라 하지만(`:33`) `out.print` + `console.readLine()` 에 동기화가 없다. 게이트 javadoc 자체가 "병렬
도구 배치가 한 게이트를 동시에 평가할 수 있다"고 적는다(`:95-96`).

**결론.** 그대로 두면 병렬 `Task` N개가 "Allow 'Task(...)'?" 를 N번, 포크 안의 같은 쓰기 도구도 포크 수만큼
묻는다. 두 프롬프트의 글자와 stdin 읽기가 섞일 수 있다.

**설계 반영.** §3.12 — 질문 단위(스코프 × 도구) in-flight future 로 "같은 질문은 한 번만", 다른 질문은 서로
기다리지 않음(스트라이프 락의 cross-session 정지는 철회); 기능이 켜졌을 때만 `[task …]` 라벨; CLI `resolve` 를
`synchronized`. UX 는 "한 번에 질문 하나, 누가 묻는지 보인다" 이고 부모 스레드는 join 중이라 토큰 스트림이
프롬프트 사이에 끼지 않는다. 남는 것: 다른 포크의 `[Tool]` 한 줄이 프롬프트 줄 **앞뒤**에 찍힐 수 있다 — 줄
단위라 읽을 수 있으므로 받아들인다.

### 6.2 부모 이벤트 publisher 로의 스트림 이벤트 인터리빙과 taskId 구분

**확인한 코드.** 포그라운드 포크의 출력 싱크는 `SubagentOutputSink.NO_OP` 이고
(`subagent/DefaultSubagentExecutionManager.java:623-631`) `DefaultSubagentExecutor.createToolContext`
(`:891-958`) 는 `AGENT_EVENT_SINK` 도 `MESSAGE_QUEUE_MANAGER` 도 싣지 않는다(grep: `AGENT_EVENT_SINK` 0건,
`emit` 0건). `SubagentLaunchContext.parentEventSink` 는 백그라운드 완료 알림에만 쓰인다
(`TaskTool.java:559-565`). 부모 publisher 에 닿는 것은 부모 디스패처가 내는 `Task` 호출 자신의
`ToolUseStarted`(호출 스레드, 입력 순서 — `OrcaAgentExecutor.java:2419`) 와 `ToolResultReady`(완료 스레드, 완료
순 — `:2420`) 뿐이고 둘 다 `toolUseId` 를 싣는다(`agent/stream/ToolUseStarted.java:38`,
`ToolResultReady.java:36`). `EventEmitter` 는 `CopyOnWriteArrayList` (parallel-execution.md §8.4). CLI 는 훅으로
그린다: `SubagentLaunchDisplayHook` (`:37-38`, 이름 + goal, taskId 미사용), `ToolCallDisplayHook` (`:41-48`,
`InvokerType` 들여쓰기만), `SubagentResultDisplayHook` (`:57-67`, `Task` 결과 텍스트 파싱, `Task:` 설명 포함).
`OutputFormatter` 는 `System.out.println` 을 줄마다 부른다(`repl/OutputFormatter.java:282-295`).

**결론.** **스트림 이벤트의 인터리빙 문제는 없다** — 포그라운드 포크는 이벤트를 내지 않고, 부모의 두 이벤트는
id 로 정합이 맞는다. 문제는 CLI 훅 출력이다: 줄은 섞이지 않지만(한 줄 = 한 `println`) 멀티라인 결과 블록은
섞일 수 있고, 같은 종류의 포크 둘은 라벨이 없어 구분이 안 된다.

**설계 반영.** §3.8 의 `aimon.subagent.task_id` 실행 속성(기능이 켜진 manager 만, 두 진입점에서 한 번 실어 Start/Stop 훅과 포크 안의 도구 훅이 같은 id 를 본다) + §3.13 의 라벨과 단일 출력.

### 6.3 트레이싱 span 부모 context 의 스레드 전파

**확인한 코드.** `at.aimon.core.tracing` 전체와 `toolinvocation.ToolCallTracing` 에 `ThreadLocal`/`ScopedValue`
가 **없다**(`grep -rn "ThreadLocal\|ScopedValue" modules/*/src/main/java` — tracing 패키지 0건). 부모는 값으로
간다: `resolveEffectiveToolContext` (`OrcaAgentExecutor.java:2470-2476`) 가 **호출 스레드에서 dispatch 전에**
ITERATION span 을 `LLM_CALL_METADATA_KEY` 에 실은 컨텍스트를 만들고 `toolRunner` 람다가 그것을 캡처한다
(`:2452`); `ToolCallTracing.open` (`:68`) 은 그 메타데이터의 부모로 TOOL span 을 열고 `contextUnder` 로 도구에
TOOL span 을 넘기며, `TaskTool.buildLaunchContext` 가 그것을 `parentLlmCallMetadata` 로 싣고(`:537-541, 572`)
manager 가 SUBAGENT span 을 그 아래에 연다. `DefaultTracer` 의 span 은 `setAttribute` 가 `synchronized`
(`tracing/impl/DefaultTracer.java:179`) 이고 저장소는 `ConcurrentHashMap` (`InMemoryTraceSpanStore.java:36-38`).
`ExecutionScope.activeSpan` 은 `volatile` (`:3778`) 이며 전용 스레드는 그것을 직접 읽지 않는다(캡처된 컨텍스트만).
코드베이스에서 유일한 메타데이터 `ThreadLocal` 은 `llm/tagging/LlmCallMetadataHolder.java:48` 인데 두 실행기
어느 쪽도 쓰지 않는다(main 소스에서 tagging 패키지 밖 사용 0건).

**결론.** 부모 span 은 **값 전달**이라 스레드가 바뀌어도 트리가 맞는다. 코드 변경 없음. `subagent-trace-nesting.md`
§5 의 "병렬 도구 안의 Task 여러 개 → 형제 SUBAGENT 가 섞이지 않음" 이 전용 스레드에도 그대로 성립한다.
한계 하나: 임베더가 `LlmCallMetadataHolder` 로 턴 스레드에 ambient 태그를 밀어 넣었다면 전용 스레드는 못 본다 —
풀 경로와 백그라운드 포크에 이미 있는 같은 공백이다(테스트 전략의 회귀 단언 1건).

### 6.4 `SubagentStart`/`SubagentStop` 훅과 훅 실행 관리자의 동시 호출

**확인한 코드.** 포그라운드 경로는 `runExecute` 가 **호출 스레드**에서 `fireSubagentStart` 를 부르고
(`DefaultSubagentExecutionManager.java:677-684`), `runResolvedSubagent` 끝에서 `fireSubagentStop`
(`:1059-1074`)을 부른다 — 전용 스레드에서는 그 스레드가 호출 스레드다. `DefaultHookExecutionManager` 는
"Thread-safe. 모든 실행은 `HookExecutor` 에 위임" (`hook/DefaultHookExecutionManager.java:57`),
`DefaultHookExecutor` 는 훅마다 자기 풀에 submit 하고 `Future.get(timeout)` 으로 기다리며 호출 간 공유 상태가
없다(`hook/execution/DefaultHookExecutor.java:56, 242-255`). `DefaultHookRegistry` 는 이벤트 타입별
`CopyOnWriteArrayList` (`hook/DefaultHookRegistry.java:23`). 포크의 레지스트리는 `launchContext.getHookRegistry()`
로 스포너의 것(스킬 훅 뷰 포함, `TaskTool.java:571`)이라 N개 포크가 **같은** 레지스트리를 읽는다 — 읽기 전용.
`PreTool`/`PostTool` 은 `SingleToolInvoker` 안에서 전용 스레드마다 돈다(`:222-227, 340-364`).

**결론.** 프레임워크 쪽은 동시 호출 안전하다. 사용자 훅의 thread-safety 는 이미 병렬 도구에서 요구된 계약이다
(parallel-execution.md §5, feature 가이드 §5.3). 순서 보장은 포크 **하나 안에서만** Start → (Pre/Post…) → Stop
이고, 포크 사이에서는 k 의 Start 가 j 의 Stop 뒤에 올 수 있다. CLI `SubagentLaunchDisplayHook` 은 한 줄 한 호출.

**설계 반영.** 코드 변경 없음. 문서 §3.15 (feature 가이드 §5.3 에 "SubagentStart/Stop 도 포함") 과 테스트(§7).

### 6.5 같은 파일 동시 편집 정책

**확인한 코드.** 파일 stamp 맵은 **실행마다 새로** 만든다 — 턴은 `OrcaAgentExecutor.java:902`, 포크는
`DefaultSubagentExecutor.java:917` ("a fork does not inherit its parent's reads"). `EditTool.execute` 는 쓰기 전
`FileStamps.checkBeforeModify(context, env, path, true)` 를 부르고(`tools/file/EditTool.java:170-174`), 그것은
기록된 stamp 와 현재 stamp 를 비교해 다르면 `CHANGED_MESSAGE` 를 돌려준다(`tools/file/FileStamps.java:118-131`).
파일 락 · 실행 간 편집 레지스트리는 없다(`grep FileLock\|lock(` 0건). 백그라운드 포크는 **이미** 부모와 동시에
같은 파일을 만질 수 있다 — 이 설계가 새 종류의 경합을 만드는 것은 아니다.

**결론.** 프레임워크는 **차단하지 않고 stamp 가드로 lost update 를 명시적 거절로 바꾼다**: 포크 A 와 B 가 같은
파일을 읽고(각자 stamp t0) A 가 고치면 B 의 `Edit` 는 "File changed since it was read; Read it again" 으로
거절된다. 검사 → 쓰기 사이의 TOCTOU 창은 남지만 오늘 사용자 편집기와 에이전트 사이에도 있는 같은 창이다. 락을
기각한 이유는 §2.4 I.

**설계 반영.** 지침은 사람에게 주는 두 가이드(§3.15)에만 둔다 — 도구 설명에는 넣지 않는다(§3.6, 기능 off 시
요청 바이트 동일). 코드 변경 없음.

### 6.6 중첩 Task fan-out 상한 (깊이 × 폭)

**확인한 코드.** 포크 깊이 개념이 **없다** — `subagent`, `tools/task`, `ToolContextKeys`, `agent/impl/orca`,
`toolinvocation` 에 depth/nesting 키 0건(유일한 `MAX_NESTING` 은 JSON 코덱 `UserInputCodec.java:83`).
`CALLER_ALLOWED_TOOLS` 는 "실행마다 다시 실어 어떤 깊이로도 따라간다"(`ToolContextKeys.java:373`) — 깊이를
실어 나르는 **같은 모양의 선례**다. 백그라운드 풀은 `n` 고정 스레드 + 큐(`DefaultSubagentExecutionManager.java:498-505`)
로 폭만 묶고 깊이는 모른다. 포크 실행기는 깊이와 무관한 한 인스턴스다(`OrcaAgentExecutorFactory.java:778`).
`SubagentLaunchContext` 를 `ToolContext` 에서 만드는 사이트는 넷(§3.14 — GraalJS 포함)이고 agent-scoped base 가
하나 더 있다.

**결론 (결정).** 폭 = `maxThreads` 세마포어 **하나**, 깊이 · 턴 · 세션에 걸쳐 전용 스레드 총수를 묶는다.
깊이 = `maxForkDepth`: `forkDepth < maxForkDepth` 인 실행만 편다(기본 1 = 턴만). **블로킹 대기가 없으므로 교착이
불가능하다** — 어떤 스레드도 허가를 쥔 채 허가를 기다리지 않는다(허가가 없으면 inline 으로 진행). 최악의 동시
포그라운드 포크 수는 `maxThreads + (동시에 dispatch 중인 호출 스레드 수)` 다. 깊이별 사다리를 기각한 이유는
§2.4 D. 이 결정이 수용 기준의 "대기(backpressure)" 를 "호출자가 inline 으로 느려짐" 으로 읽는다는 점은 §8 Q1.

**설계 반영.** §3.2 · §3.3 · §3.4 · §3.5 · §3.7 · §3.8 · §3.9 · §3.14.

### 6.7 부모 취소 시 자식 전파, 그리고 늦은 interrupt 가 부모 스레드에 남지 않음

**확인한 코드.** 협력 신호: `TaskTool.buildLaunchContext` 가 `InterruptToolKeys.CANCELLATION_SIGNAL`(턴의 신호,
`OrcaAgentExecutor.java:905`) 을 `cancellationSignal` 로 싣고(`:547-548, 573`), manager 가 그것을 포크의 부모
신호로 넘기며(`:627-630`), `DefaultSubagentExecutor.execute` 가 자기 코디네이터에 `parentSignal.onCancel(…
requestInterrupt(PARENT_CANCELLED))` 로 캐스케이드한다(`:458-467`) — 스레드와 무관. 그 뒤 `SignalBackedLlmCancellation`
이 진행 중 LLM 호출을 끊고(`:514`) 루프 체크포인트가 `INTERRUPTED` 로 끝낸다(`:529, 607, 646`).
Terminator: `SingleToolInvoker` 가 `EXTERNALLY_TERMINATED` 에 대해 **실행 스레드에서** 턴 코디네이터로부터
레지스트라를 발급하고(`:165-172`) finally 에서 닫는다(`:262-268`); `DefaultInterruptCoordinator` 는
`CopyOnWriteArrayList` 로 레지스트라를 쥐고(`:25`) `requestInterrupt` 가 스냅샷을 돌며 `fireAll` 한다
(`:33-50`); `runForeground` 는 `Thread.currentThread()::interrupt` 를 등록한다(`TaskTool.java:618-620`). 이미
트립된 코디네이터에 등록하면 등록 스레드에서 즉시 발화한다(`DefaultTerminatorRegistrar.java:44-53`). 늦은
interrupt: `fireAll` (`:84-97`) 은 lock 안에서 스냅샷 · `fired=true` 뒤 lock **밖에서** `terminate()`;
`unregister` (`:56-65`) 는 `fired` 면 즉시 반환 → 도구가 돌아온 뒤 interrupt 가 도착할 수 있다. 오늘은 그
스레드가 부모 루프 스레드라 `isInterrupted` (`OrcaAgentExecutor.java:2061-2071`, `CancellationSignals.java:53-57`
로 플래그 소비 + 승격)와 `consumeLingeringInterrupt` (`:1299-1306`) 가 흡수한다. 부모 스레드의 **자기** interrupt
는 풀 경로에서 `join()` 이 무시한다(`DefaultParallelToolDispatcher.java:343`).

**결론.** 전용 스레드에서 (a) 협력 신호 캐스케이드는 그대로 성립, (b) terminator 는 전용 스레드를 가리키고 턴
코디네이터의 `fireAll` 이 N개 전용 스레드를 전부 interrupt 한다, (c) 늦은 interrupt 는 전용 스레드에 떨어져 그
스레드와 함께 사라지며 **부모 스레드에는 등록된 terminator 가 없다** — 단 inline fallback lane(§3.5 ③)은
오늘과 같은 경로이고 오늘의 흡수가 그대로 적용된다. (d) 부모 스레드 자체의 interrupt 는 §3.5 ⑤⑥ 으로 자식에게
전파하고 끝난 뒤 되돌린다.

**교차 lane 효과 하나 (새롭지만 무해).** 허가가 모자라 inline lane 으로 돈 `Task`(§3.5 ③)의 terminator 는 호출
스레드를 가리킨다. 그 늦은 interrupt 가 호출 스레드가 전용 슬롯을 `future.get()` 으로 기다리는 사이에 떨어지면
§3.5 ⑤ 가 **미완 전용 형제 전부**에 interrupt 를 건다. 그 terminator 는 턴 코디네이터의 `requestInterrupt` 로만
발화하고(`:33-50`), 그 호출은 같은 스냅샷 안에서 형제들의 레지스트라도 이미 발화시켰으므로 형제들은 그때 이미
취소 중이다 — 두 번째 interrupt 는 같은 신호의 반복이다. §3.5 ⑥ 이 되돌린 플래그는 이미 트립된 신호 앞에서
체크포인트의 no-op 승격으로 끝난다(`DefaultInterruptCoordinator.java:41-44`).

**잔여 창 (이 설계가 넓히지 않는, 오늘도 있는 것).** `runForeground` 가 돌아온 뒤 전용 스레드에서 돌아가는
것이 둘 있다 — ⓐ `SingleToolInvoker` 의 PostTool 훅(`:252, 340-364`), ⓑ 디스패처의 `onCompleted`
(`emitToolResultReady`, §3.5). 늦은 interrupt 가 그 사이에 떨어지면 ⓐ 는 `DefaultHookExecutor` 의 fail-open 이
PostTool 을 건너뛸 수 있고(`interrupt.md` §8.7 의 두 번째 겹은 PreTool 쪽만 막는다), ⓑ 는 리스너가 블로킹 대기를
하지 않는 한 영향이 없다. 오늘 부모 스레드에서도 똑같은 두 창이 있다(§8 Q9).

**설계 반영.** `TaskTool`·코디네이터·레지스트라 무변경. §3.5 ⑤⑥. 테스트 §7 (늦은 terminator 를 **다른**
스레드에서 발화시키는 테스트 13 — v1 의 자기-interrupt 시뮬레이션은 구성상 실패할 수 없었다).

### 6.8 `BudgetTracker` / 토큰 · 비용 집계의 공유와 스레드 안전성

**확인한 코드.** `BudgetTracker` 는 동기화 없는 평범한 필드다(`agent/budget/BudgetTracker.java:56-64, 98-134`).
생성 지점은 둘 — 턴 `OrcaAgentExecutor.java:1170`, 포크 `DefaultSubagentExecutor.java:454` (scope-model.md 의
"정확히 2곳" 과 일치). 턴의 트래커는 LLM 호출 전후에 **루프 스레드**만 만지고(`:1724, 1746, 1860, 1874`) 도구
컨텍스트에 실리지 않는다 — 전용 스레드가 도는 동안 부모는 join 에 있다. 포크는 각자 자기 것. 포크의 토큰은
부모 트래커에 합산되지 않는다 — `TaskTool` 은 결과 텍스트에 찍을 뿐이다(`:656-657`). `CostSummary` 는 부모
스레드에서만 재할당(`:3764`). 포크 사이에 **실제로 공유되는** 집계는 `QuotaGuard` 하나 — 턴의 가드가
`QUOTA_GUARD` 로 실려(`:895`) 런치 컨텍스트 → 요청(`:772`)으로 포크에 그대로 간다. `QuotaGuard` 는 "스레드 간
안전은 ledger 가 안전한 한" 이라 명시하고(`agent/quota/QuotaGuard.java:39-41`) `charge` 는 ledger 에 위임
(`:159-176`); 기본 `InMemoryUsageLedger` 는 `ConcurrentHashMap` + `merge` (`agent/quota/impl/InMemoryUsageLedger.java:23, 39, 49`).

**결론.** 이 설계는 새로운 공유 집계를 만들지 않는다. `BudgetTracker` 는 실행 단위(턴 또는 포크)당 하나라는
scope-model 규칙 덕분에 동기화 없이 안전하고, 유일한 공유 집계 `QuotaGuard` 는 이미 계약상 thread-safe 다.
`BudgetTracker` 를 동기화하지 **않는다** — "실행당 하나" 를 잠금으로 가리면 규칙이 흐려진다.

**설계 반영.** 코드 변경 없음. 회귀 단언(§7): 병렬 포크 뒤 부모 `budgetTracker.tokens()` 가 부모 LLM 호출 합과
같고, `InMemoryUsageLedger` 에 포크 N개의 charge 가 전부 들어간다.

### 6.9 결과 순서 · Pre/PostTool 훅 이벤트 순서

**확인한 코드.** 결과는 디스패처가 입력 순서로 재조립하고(`DefaultParallelToolDispatcher.java:254-260`), 실행기는
그 리스트를 한 메시지로 커밋한다(`OrcaAgentExecutor.java:1966` `Message.toolUseResults(toolUseResults)`). 이벤트
계약: `onStarted` 는 호출 스레드 · 입력 순서, `onCompleted` 는 (병렬 시) 워커 · 완료 순, 둘 다 tool-use id 매칭
(`agent/tool/ParallelToolDispatcher.java:45-52`). Pre/PostTool 은 `SingleToolInvoker` 안에서 **호출 하나당**
Permission → 게이트 → PreTool → 실행 → PostTool 순서가 고정이고(`:195-252`), 훅 feedback 은 그 호출의 결과에만
붙는다(`:74-83`) — 병렬 배치에서의 귀속을 위해 이미 그렇게 설계되어 있다.

**결론.** 전용 스레드 경로도 같은 계약을 지킨다: 결과 = `tool_use` 순서(§3.5 ④), `ToolUseStarted` = 입력 순서
(§3.5 의 `safeOnStarted` 위치), `ToolResultReady` = 완료 순(전용 스레드). Pre/PostTool 은 **호출 하나 안에서**
순서가 고정이고 호출 **사이**에서는 순서가 없다 — 풀 경로와 같다. 섞인 배치에서는 구간이 순서대로 join 되므로
비-`Task` 도구의 상대 순서와 실행 의미(앞 구간이 끝난 뒤 시작)가 보존된다. harvest 경로(§3.11)는 접두의 이벤트를
오늘처럼 자기가 내고 꼬리의 이벤트를 디스패처에 한 번 맡기므로 열이 같다.

**설계 반영.** 계약 추가 없음. `DedicatedThreadDispatcher` javadoc 이 `ParallelToolDispatcher` 의 세 보장을
그대로 재진술한다. 테스트 §7.

---

## 7. 테스트 전략

모두 단위 테스트(`./gradlew :aimon-core:test`, Docker 불필요). 실제 LLM 은 쓰지 않고 기존 테스트의
`SequencedLlmClient` 류 스크립트 클라이언트와 Mockito 를 쓴다.

**`DedicatedThreadDispatcherTest`** (`agent/tool`, 신규) — delegate 는 Mockito spy 한 `DefaultParallelToolDispatcher.sequential()`.
1. 설정 off → delegate 의 5-인자 `dispatch` 가 **원본 리스트 인스턴스로 정확히 1회** 호출(`isSameAs`).
2. 설정 on, `DEDICATED_THREAD` 도구 없음 → 동일.
3. `forkDepth >= maxForkDepth` → 동일.
4. `[Task, Task, Task]`, `maxThreads=4`: 세 runner 가 `CyclicBarrier(3)` 에서 랑데부 → 동시 실행 증명;
   각 runner 의 스레드가 서로 다르고 호출 스레드가 아니며 이름이 `tool-dedicated-` 로 시작하고 daemon.
5. 벽시계: 각 runner 가 200ms 블로킹 → 전체 < 500ms (acceptance "≈ max(개별)").
6. 결과 순서: 완료 순서를 역전시켜도 결과 리스트는 입력 순서.
7. `onStarted` 가 호출 스레드에서 입력 순서로; `onCompleted` 가 전용 스레드에서 발화.
8. 섞인 배치 `[Read, Read, Task, Task, Grep, Task]` → delegate 가 `[Read, Read]` · `[Grep]` 으로 2회, 그 사이에
   dedicated 구간; 전체 결과 순서; Grep 의 시작이 두 Task 의 완료 뒤(순서 의미 보존).
9. `maxThreads=2`, `[Task×5]` → 전용 스레드는 동시 최대 2, 세 번째는 호출 스레드에서 inline (스레드 identity),
   결과 순서 유지, 모든 허가 반환(`availablePermits()==2`).
10. 두 dispatch 가 다른 스레드에서 동시에 같은 permits 를 씀 → 합계 동시 전용 스레드 ≤ `maxThreads`, 양쪽 완료,
    교착 없음(타임아웃 단언).
11. runner 가 throw / null → 에러 결과, 배치 계속, 허가 반환.
12. 호출 스레드 interrupt(join 중) → 미완 전용 스레드가 interrupt 를 본다(runner 가 `Thread.interrupted()` 기록),
    dispatch 반환 후 호출 스레드 `Thread.interrupted()==true` (재무장), 결과는 전부 채워짐.
13. **늦은 interrupt — 다른 스레드에서.** runner 가 자기 스레드를 기록하고 "돌아왔다" 래치를 내린 뒤 "계속해도
    된다" 래치를 기다린다; 테스트 스레드가 첫 래치 뒤 그 전용 스레드를 `interrupt()` 하고 둘째 래치를 내린다 →
    dispatch 반환 후 호출 스레드 플래그 `false`, 결과 정상, 허가 반환. (v1 의 "runner 가 자기 스레드를 interrupt"
    는 호출 스레드를 건드릴 수 없어 구성상 실패 불가였다.)
14. 미등록 도구명 → delegate 위임(보수성).
15. `close()` 가 `AutoCloseable` delegate 를 닫고, 아닌 delegate 는 건드리지 않는다.

**`DedicatedThreadConfigTest`** — 기본값, `maxThreads<1` / `maxForkDepth<1` 거절, `disabled()` 동일 인스턴스,
`toString`. **`DispatchOptionsTest`** — `root()` 깊이 0, 음수 거절.

**기존 `DefaultParallelToolDispatcherTest` 확장** — `DEDICATED_THREAD` 도구가 든 배치는 `shouldParallelize` 가
`false` (§3.16 ① 의 직접 증거); `routesHarvestTail()` 은 `false`. **`StreamingToolSchedulerTest`** — `DEDICATED_THREAD` 는
eager 비적격. **`DedicatedThreadDispatcherTest`** 에 16: `routesHarvestTail()==true`, delegate 의 것과 무관.

**`TaskToolTest` 확장** — `getConcurrencyBehavior()==DEDICATED_THREAD`; 컨텍스트 `FORK_DEPTH=1` 이면 런치
컨텍스트 `forkDepth==2`, 없으면 1; **도구 설명이 base 와 글자 단위로 같다**(§3.16 ⑪ — 설명 변경 철회의 회귀
가드). **`ForkAccessTest`** 신규 — 깊이, task id 읽기(문자열이 아니면 empty).

**`OrcaAgentExecutorDedicatedTaskTest`** (`agent/impl/orca`, 신규; `OrcaAgentExecutorParallelToolTest` 와 같은
조립 — factory `withDedicatedThreadConfig(enabled(4))`, 스크립트 LLM 이 `Task` 셋을 한 응답에 반환, fake
`SubagentExecutionManager.execute` 가 `CyclicBarrier(3)` 뒤에 성공 결과).
1. 턴 성공, 트랜스크립트의 `tool_result` 셋이 `tool_use` 순서; 세 `execute` 가 서로 다른 비-호출 스레드.
2. 취소: 첫 `execute` 안에서 `coordinator.requestInterrupt(USER_SIGINT)` → 세 fake 가 받은
   `launchContext.getCancellationSignal()` 전부 트립, `TERMINATOR_REGISTRAR` 로 등록한 terminator 가 전부 발화
   (fake 가 `Thread.currentThread()::interrupt` 를 등록하고 interrupt 수신 기록), 턴 `INTERRUPTED`,
   `execute()` 반환 뒤 호출 스레드 `Thread.interrupted()==false` (`OrcaAgentExecutorInterruptFlagHygieneTest`
   의 방식).
3. **늦은 terminator 끝까지.** `OrcaAgentExecutor.interruptCoordinatorFactory` (package-private, `:352`) 에
   테스트 코디네이터를 넣는다 — 레지스트라의 `fireAll` 이 "runForeground 가 돌아왔다" 래치를 기다린 뒤에야
   `terminate()` 를 부르는 것. fake manager 가 결과를 돌려주며 래치를 내린다 → interrupt 는 전용 스레드에 늦게
   떨어진다. 단언: 턴은 `INTERRUPTED`(신호는 트립됨), 호출 스레드 플래그 `false`, **같은 실행기로 바로 이어지는
   다음 턴**의 첫 도구(호출 스레드에서 도는 `SEQUENTIAL` 도구)가 interrupt 를 보지 않는다(TASK.md 의 "부모 스레드에
   stale interrupt 플래그가 남지 않음").
4. 승인: `SideEffectApprovalGate` 에 카운팅 `AskPromptHandler` → `Task` 셋에 대해 `resolve` **1회**; 프롬프트
   문자열에 `[task ` 접두(기능 on 이므로 속성 있음).
5. 트레이싱: `DefaultTracer(InMemoryTraceSpanStore)` → 세 TOOL span 모두 같은 ITERATION 부모, 세 SUBAGENT span 이
   각자의 TOOL span 아래 (`DefaultSubagentExecutionManagerTracingTest` 의 시드 방식); **SUBAGENT span 속성 집합이
   base 테스트의 기대와 같다**(task id 속성이 span 에 새지 않음).
6. 예산 · 쿼터: 부모 `BudgetTracker.tokens()` 가 부모 LLM 호출 합; `InMemoryUsageLedger` 에 포크 charge 3건.
7. **설정 off(factory 기본)** → 세 `execute` 가 전부 호출 스레드에서 순차(barrier 대신 순서 기록); 포크 실행
   속성에 `aimon.subagent.task_id` **없음**; 게이트 프롬프트 문자열이 base 와 같음(접두 없음).
8. 스트리밍 중첩 on + `[Read, Task, Task]`: 기능 on → Read eager, 두 Task 동시, `started(T1) started(T2)` 가
   호출 스레드 입력 순서, ready 는 완료 순; **기능 off** → `routesHarvestTail()` 이 거짓이라 `dispatch` 가 꼬리에
   대해 **호출되지 않고**(spy 로 단언) 이벤트 열 `started(Read) ready(Read) started(T1) ready(T1) started(T2)
   ready(T2)` 가 base 의 harvest 루프와 동일하며 전부 호출 스레드(§3.16 ⑩).
9. **launch 라벨 끝까지(리뷰 2).** 기능 on, `[Task(Explore), Task(Explore)]` + 테스트 `SubagentStartHook`/
   `SubagentStopHook` 등록 → 캡처된 Start/Stop 컨텍스트 둘의 `ForkAccess.taskIdOf(getExecutionAttributes())` 가
   각각 자기 `getTaskId()` 와 같고 서로 다르다; `SubagentLaunchDisplayHook` 가 그린 두 줄이 서로 다른 `#id` 라벨을
   단다. 중첩: 포크의 레지스트리에 같은 manager 를 쓰는 `TaskTool` 을 넣어(`DefaultSubagentExecutionManagerTracingTest:245-249`
   의 조립) 자식 포크를 띄우면 자식의 Start/Stop 컨텍스트는 **자식의** id, 부모 포크 안의 `PreToolContext` 는
   **부모 포크의** id, 자식 포크 안의 `PreToolContext` 는 자식의 id 를 싣는다.

**`DefaultSubagentExecutorTest` 확장** — `maxForkDepth=2` 데코레이터 주입 + 요청 `forkDepth=1` → 포크 안의 `Task`
둘이 동시; 기본 `maxForkDepth=1` → 순차; 포크 도구 컨텍스트에 `FORK_DEPTH==request.forkDepth`.
**`DefaultSubagentExecutionManagerTest` 확장** — 요청에 `forkDepth` 복사; 플래그 off 면 실행 속성 **불변**(맵
인스턴스 동일, Start 컨텍스트도 스포너의 맵 그대로), on 이면 `aimon.subagent.task_id` 가 Start 컨텍스트 · 요청 ·
Stop 컨텍스트 세 곳에 **같은 값**으로 있고 원본 맵은 불변; 백그라운드 경로와 풀 거부 경로(`:1154`)의 Stop 도 같다;
중첩 포크가 자기 id 로 덮어씀.
**`SubagentLaunchContextTest`** — `forkDepth` 기본 1, `toBuilder` 복사. **`WorkflowToolTest` /
`SubagentBackedSkillForkExecutorTest` / GraalJS 모듈 테스트** — 런치 컨텍스트 `forkDepth == 컨텍스트 깊이 + 1`.

**`SideEffectApprovalGateTest` 확장**
1. 같은 스코프 · 같은 도구 동시 `denialReason` 8개(핸들러는 래치에서 블록) → 핸들러 1회, 8개 모두 같은 답, 답이
   store 에 1건.
2. **다른 스코프가 서로를 막지 않는다** — 스코프 A 의 호출이 `resolve` 안에서 래치에 블록된 동안 스코프 B 의 호출이
   완료된다(타임아웃 단언). 같은 스코프 · 다른 도구도 동일. (v1 스트라이프 락의 회귀 가드.)
3. 대기자 interrupt → DENY 사유 "cancelled while waiting", 플래그 재무장, store 무기록; 묻던 쪽은 영향 없음.
4. 묻던 쪽이 `RuntimeException` **또는 `Error`** 를 던짐 → 대기자가 매달리지 않고 DENY, `inFlight` 비워짐(다음
   호출이 다시 묻는다), 던진 것은 호출자에게 전파.
5. 스코프 없음 → 매번 묻고 `inFlight` 미사용.
6. 라벨: 컨텍스트 속성에 task id 가 있으면 `[task xxxxxxxx] ` 접두, 없으면 base 와 같은 문자열.
7. **재조회 경합(리뷰 2).** `ToolApprovalStore` spy: 둘째 호출자의 `lookup` 이 래치에서 블록되어 첫 호출자가
   `remember` → `complete` → `remove` 를 **끝낸 뒤** 비어 있는 결과를 돌려준다 → 둘째 호출자는 `putIfAbsent` 를
   이기지만 재조회로 답을 찾아 **묻지 않는다**(핸들러 1회). 테스트 1 의 래치 구성은 이 창을 잡지 못한다.
**CLI** — `InteractiveAskPromptHandler.resolve` 동시 호출이 직렬화됨; `ToolCallDisplayHook` /
`SubagentLaunchDisplayHook` 가 속성 없을 때 base 와 같은 줄, 있을 때 라벨; `OutputFormatter.displaySubagentResult`
가 캡처한 `PrintStream` 에 **1회** 쓰고 내용은 base 의 줄 연결과 같다.

**회귀 전체** — `OrcaAgentExecutorParallelToolTest`, `OrcaAgentExecutorBatchInterruptTest`,
`OrcaAgentExecutorInterruptFlagHygieneTest`, `OrcaAgentExecutorConcurrentDispatcherCeilingTest`,
`DefaultSubagentExecutor*Test`, `DefaultSubagentExecutionManager*Test` (트레이싱 포함), `TaskTool*Test`,
`SingleToolInvokerTest`, ArchUnit 전부(`PackageDependencyArchitectureTest` — 새 타입은 전부 `agent.tool` /
`tools` / `subagent` 에 있어 새 패키지 쌍이 생기지 않는다; `TurnVocabularyArchitectureTest` — 새 이름에 `turn`
없음; `InternalPackageBoundaryTest`), `checkJavadocCoverage` (새 public 타입 전부 javadoc),
`./gradlew format checkAll`, 문서 스크립트 3종.

---

## 8. Open questions (TASK.md 로 해소 불가 — 가정하지 않고 표기)

| # | 질문 | 설계의 잠정 처리 |
|---|------|-----------------|
| Q1 | 수용 기준 "상한 초과 시 **대기**(backpressure)" 를 블로킹 대기로 읽어야 하는가? | 비블로킹 + inline fallback 으로 읽었다(§2.4 D, §6.6). 호출자는 자기 속도로 느려지므로 백프레셔이고, 라벨 정확성에 기대지 않는 교착 없음을 얻는다. 블로킹이 꼭 필요하면 깊이별 세마포어 사다리(D)로 바꿀 수 있으나 다섯 spawn 사이트의 라벨이 교착 조건이 된다 |
| Q2 | 백그라운드 `Task` 호출(즉시 반환)도 전용 스레드를 쓰는 것이 허용되는가? | 허용으로 두었다(정적 선언, §2.4 H). 비용 둘 — 백그라운드 launch 의 `SubagentStart` 가 입력 순서로 찍힌다는 보장이 사라지고(`DefaultSubagentExecutionManager.java:1111-1114` 주석의 의도), 백그라운드 launch 가 포그라운드 허가를 잠깐 써서 폭주하면 동시 포그라운드 `Task` 가 inline lane 으로 밀릴 수 있다. 둘 다 가이드 §9 제한사항에 적는다(§3.15). 거슬리면 호출별 술어가 필요하다 |
| Q3 | 기본 `maxForkDepth` 는 1(턴만)인가 2 인가? | 1. 포크의 fan-out 은 LLM 동시성을 곱하므로 운영자가 명시해야 한다 |
| Q4 | `AimonStackBuilder` / Spring `AimonProperties` 에 노출해야 하는가? | 이번 범위 밖 — 기존 `ToolConcurrencyConfig` 도 노출되지 않았다. 둘을 함께 노출하는 후속 과제로 백로그에 적는다 |
| Q5 | `OrcaAgentExecutor` 의 `maxThreads` 상한이 factory 전체(모든 세션)인 것이 맞는가, 실행기당인가? | factory 전체(§3.10). "호스트 보호" 가 목적이면 전체가 맞다. 실행기당으로 하려면 `applyExecutorOverrides` 에서 permits 를 새로 만들면 되고 포크와의 공유만 유지하면 된다 |
| Q6 | 이름 — `DEDICATED_THREAD` / `DedicatedThreadConfig` 대 TASK.md 의 예시 `SubagentForegroundConfig` | 메커니즘 이름을 택했다(§2.4 J). 사용자 문서 제목은 "포그라운드 Task 병렬 실행" |
| Q7 | 포크 실행 속성 `aimon.subagent.task_id`(§3.8)가 범위 안인가? 이것 없이는 CLI 가 같은 종류 포크 둘을 구분할 수 없다 | 포함하되 **기능이 켜진 manager 만** 싣는다 — 항목 2 가 "taskId 구분(CLI 렌더링 포함)" 을 묻고, off 경로는 바이트 동일이어야 하므로. 빼면 §3.13 의 라벨도 §3.12 의 프롬프트 접두도 없다 |
| Q8 | 승인 조정의 단위 — 질문(스코프 × 도구)별 in-flight future(채택) 대 스코프별 락 | 질문별. 멀티세션 서버에서 다른 사용자의 프롬프트가 서로를 막지 않아야 하고(v1 의 스트라이프 락은 그것을 깼다 — 리뷰 1), 같은 스코프의 다른 질문도 막을 이유가 없다. 콘솔 하나에 질문이 겹치는 것은 핸들러(`synchronized`)의 일이다 |
| Q9 | `runForeground` 반환 → PostTool / `onCompleted` 사이의 늦은 interrupt 창(§6.7)을 이번에 닫을 것인가? | 범위 밖 — 전용 스레드가 창을 넓히지 않는다. 닫으려면 `SingleToolInvoker` 가 실행 뒤 PostTool 전에 `Thread.interrupted()` 를 소비 · 승격해야 하고 그것은 `interrupt.md` §8 의 별도 변경이다 |
| Q10 | LLM 공급자 rate limit — `maxThreads` 외 별도 스로틀이 필요한가? | 없음. `LlmCallGateway` 의 retry 정책이 429 를 다루고, 운영자는 `maxThreads` 로 폭을 정한다. 가이드 §9 제한사항에 적는다 |
| Q11 | harvest 꼬리 라우팅(§3.11)을 이번에 넣을 것인가, 후속으로 미룰 것인가? | 넣는다 — 바이트 동일성이 §3.11 에서 증명되고 §7 에 off 이벤트 열 테스트가 있다. 리뷰어가 범위를 줄이자면 뺄 수 있는 독립 항목이다 |
| Q12 | §3.16 ③ (포크 `ToolContext` 에 `FORK_DEPTH` 키가 늘 보인다)을 "기능 off 시 바이트 동일" 로 받아들일 수 있는가? | **리뷰 2 가 받아들였다** — 포크의 `ToolContext` 는 복사만 되고 직렬화 · 열거되지 않는다(`HookRegistryAccess.withHookRegistry` 가 유일한 전체 맵 독자). 대안(`wantsForkDepth()` default 메서드)은 쓰지 않는다 |
| Q13 | §3.16 ⑤ — 기능이 꺼진 **도달 가능한** 구성(풀 on + 게이트 대상 `CONCURRENT_SAFE` 도구, 백그라운드 포크)에서 "같은 질문 두 번" 이 "한 번" 으로 바뀌는 것을 결함 수정으로 받아들이는가? | 받아들인다 — 두 `readLine` 이 섞이는 오늘의 동작은 누구도 의도하지 않았고, 답의 범위는 넓어지지 않는다(게이트의 답은 이미 (스코프, 도구) 단위 · "whatever its arguments" — `SideEffectApprovalGate.java:247-249`). 엄밀히 off 바이트 동일을 원하면 게이트에 스위치를 들여야 하는데, 그 결함을 꺼진 배치에 남기는 값이 그 엄밀함보다 비싸다. 리뷰 2 도 "the decision can stand" |

---

## 관련 문서

설치 뒤(`docs/design/subagent/`)에는 아래를 상대 링크(`../tool/parallel-execution.md` 등)로 바꾸고 `check-doc-links.py` 로 검증한다.

- `docs/design/tool/parallel-execution.md` — 2단 게이트와 디스패처 계약 (본 설계가 얹히는 자리)
- `docs/design/agent-execution/interrupt.md` — `InterruptBehavior` · 플래그 위생(§8) · 병렬 경로의 전제(§8.5)
- `docs/design/subagent/execution.md` — 포크 실행, 공용 단일 도구 파이프라인(§11)
- `docs/design/observability/subagent-trace-nesting.md` — span 부모의 값 전달(D1)
- `docs/overview/scope-model.md` · `docs/overview/glossary.md` §4 — 실행 단위(턴 · 포크 · 실행), `BudgetTracker` 의 수명
- `docs/features/tool/parallel-tool-execution-guide.md` — 사용자 가이드 (갱신 대상, 번역본 있음)
- `design/review-1.md` · `design/rebuttal-1.md` · `design/review-2.md` · `design/rebuttal-2.md` — 리뷰와 그 답 (설치 시 제외)

---

## 9. 구현이 이 설계와 갈라진 자리

구현(2026-10-20, 브랜치 `herdr/parallel-foreground-task`, 기준 `c2fa8fb8`)은 §3 의 변경을 전부 들였다. 본문과 다른
것은 아래가 전부이고, 각 줄은 **무엇이 달라졌는가 · 왜** 를 적는다. 이 절의 클래스·메서드 이름은 구현 트리 기준이다.

### 9.1 설계와 다르게 지은 것

| # | 본문 | 구현 | 왜 |
|---|------|------|----|
| 1 | §3.12 의사코드 — `putIfAbsent` 를 이긴 뒤 재조회로 답을 찾으면 `inFlight.remove(key, mine); return …` | 재조회로 찾은 답으로 **`mine` 을 완료한 뒤** 지운다(`SideEffectApprovalGate.denialReason` 의 `finally` 가 지운다) | 본문대로면 `putIfAbsent` 와 `remove` 사이에 그 `mine` 을 본 대기자가 **완료되지 않는 future** 에 영원히 매달린다. 리뷰 2 가 메운 두 틈(`Error` · 재조회)이 남긴 세 번째 틈이다. 테스트: `SideEffectApprovalGateTest.ConcurrentQuestions` |
| 2 | §3.12 · §3.16 ⑥ — `InteractiveAskPromptHandler.resolve` 를 `synchronized` | `ReentrantLock.lockInterruptibly()` 로 콘솔을 잡고, 잡은 뒤 스레드가 이미 인터럽트되어 있으면 **프롬프트를 찍지 않고** `DENY` | 리뷰 3 의 non-blocking 지적 2 를 받아들였다. 모니터는 인터럽트로 깨지 않아, 부모 취소 뒤에도 **다른 질문**을 들고 줄 선 포크들이 하나씩 프롬프트를 띄워 사용자가 이미 취소된 일에 최대 N−1 번 답해야 했다. **동작 변화**: 인터럽트 플래그를 가진 채 `resolve` 를 부른 호출자는 이제 묻지 않고 거절된다. 기능 off 의 순차 경로에서 게이트에 닿는 스레드는 플래그가 없다 — `toolRunner` 의 게이트(`isInterrupted(coordinator)`)가 도구 앞에서 플래그를 소비하기 때문이다. 테스트: `InteractiveAskPromptHandlerTest` |
| 3 | §7 `OrcaAgentExecutorDedicatedTaskTest` 4 — 승인 프롬프트에 `[task ` 접두 | 부모 수준의 `Allow 'Task(...)'?` 프롬프트에는 접두가 **없다**고 단언한다 | `Task` 호출 자체의 게이트는 **부모의** 도구 컨텍스트에서 평가되고, 부모는 포크가 아니라 task id 속성이 없다. 접두는 포크 **안의** 도구가 묻는 질문에만 붙는다 — 그 경로는 `SideEffectApprovalGateTest` 의 라벨 테스트가 덮는다. 설계의 기대가 틀렸고 코드는 §3.12 그대로다 |
| 4 | §5 "승인 프롬프트 중 취소" · §3.12 대기자 항목 — "같은 질문을 기다리던 다른 포크는 interrupt 에 깨어나 DENY" | 코드는 본문대로이고, **문장이 일부 대기자에게만 참이다** | 리뷰 3 의 지적 1. 부모 수준 `Allow 'Task(...)'?` 질문을 기다리는 전용 스레드에는 아직 terminator 가 없다 — 게이트는 `SingleToolInvoker` 에서 도구 실행 **전에** 돌고, `runForeground` 는 실행 중에야 `toolThread::interrupt` 를 등록한다. 그래서 부모 취소가 그 대기자를 깨우지 못하고, 묻던 쪽(`console.readLine()` 에 막힌, 오늘도 같은)이 답을 받을 때 함께 깨어난다. ALLOW 면 이미 트립된 레지스트라에 등록하는 순간 즉시 발화해 포크는 협력적으로 멈춘다. 깨어나는 것은 **포크 안의** 대기자(그 전용 스레드에 포크의 `Task` terminator 가 걸려 있다)뿐이다. 게이트 테스트 3 은 대기자를 직접 인터럽트하므로 부모 수준 경우를 덮는다고 인용하지 않는다. 열린 항목 PF-5 |
| 5 | §3.5 — 전용 스레드 생성 실패는 다루지 않음 | `DedicatedThreadDispatcher` 가 `newThread`/`start` 의 `RuntimeException`·`OutOfMemoryError`("unable to create native thread")를 잡아 허가를 돌려주고 그 호출을 inline 으로 돌린다 | 시작하지 못한 스레드의 `finally` 는 돌지 않으므로 허가가 샌다. inline fallback 은 §3.5 ③ 과 같은 경로라 새 동작이 아니다 |
| 6 | §3.11 — "첫 이연 인덱스 `k` 앞까지 오늘의 루프, `k` 부터 꼬리" | `OrcaAgentExecutor.deferredTailStart` 가 `k` 뒤에 eager 결과를 가진 도구가 하나라도 있으면 꼬리를 **넘기지 않고** 배치 전체를 오늘의 inline 루프로 돌린다 | prefix-safety 상 일어나지 않아야 하지만, 일어나면 그 도구가 두 번 돈다. 방어선 한 줄이다 |
| 7 | §3.8 — `forkDepth` 의 기본값 1 | `SubagentLaunchContext` · `SubagentExecutionRequest` 빌더가 1 미만을 `IllegalArgumentException` 으로 거절한다. 요청의 `forkDepth` 는 `equals`/`hashCode`/`toString` 에 넣지 않았다 | 포크는 정의상 1 이상이다. 요청의 동등성은 "무엇을 시키는가" 이고 깊이는 위치다 — `quotaGuard` 와 같은 처리다. `toString` 에 넣지 않은 것은 기능 off 의 로그 바이트를 바꾸지 않기 위해서다 |
| 8 | §3.13 — 라벨 앞 8자 | CLI 는 `ForkLabels.shortTaskId`(패키지 private), 게이트는 자기 상수로 같은 8자를 자른다 | 둘이 다른 모듈이라 상수를 공유할 자리가 `aimon-core` 의 공개 API 뿐이었다. 8 이라는 숫자를 공개 계약으로 만들 이유가 없다 |

### 9.2 리뷰 3 의 non-blocking 지적

| 지적 | 처리 |
|------|------|
| 1. 부모 수준 대기자는 깨어나지 않는다 | 문장 정정은 §9.1 의 4. 동작은 바꾸지 않았다 — 묻던 쪽이 이미 `readLine` 에 막혀 있으므로 대기자가 먼저 깨어도 턴은 그 답을 기다린다. PF-5 로 등록 |
| 2. `synchronized` 핸들러는 인터럽트로 깨지 않는다 | 받아들였다 — §9.1 의 2. PermissionRequest 훅의 `ASK` 도 같은 핸들러를 지나므로 이제 직렬화되지만 **중복 제거는 되지 않는다**(게이트의 in-flight 조정은 승인 게이트에만 있다). PF-4 로 등록 |
| 3. `createDefaultSubagentExecutionManager` 를 오버라이드한 서브클래스 | `OrcaAgentExecutorFactory.withDedicatedThreadConfig` javadoc 에 한 문장 — 그런 서브클래스의 포크는 fan-out 도 task id 라벨도 받지 않는다. 트레이싱 선례와 같은 책임 분담 |
| 4. 포크 쪽 `onCompleted` | `DedicatedThreadDispatcher` 클래스 javadoc 에 한 문장 — `maxForkDepth >= 2` 에서 포크 실행기의 `stream(lc, …)` 콜백도 전용 스레드에서 돌며, 풀 경로와 같은 이유(no-op 싱크 또는 thread-safe 저장소 append)로 안전하다 |

### 9.3 §7 테스트 전략과 다른 것

- `DedicatedThreadDispatcherTest` 의 delegate 는 Mockito spy 가 아니라 직접 쓴 기록용 구현이다 — `DefaultParallelToolDispatcher`
  는 `final` 이다. 단언은 같다(5-인자 호출 1회, 원본 리스트 인스턴스).
- `TaskToolTest` 의 "도구 설명이 base 와 글자 단위로 같다" 는 "설명이 동시성을 광고하지 않는다" 로 지었다. base 문자열을 테스트에 박으면
  서브에이전트 목록이 바뀔 때마다 깨진다. `buildDescription` 자체는 이 변경에서 한 줄도 바뀌지 않았다(diff 로 확인).
- `OrcaAgentExecutorDedicatedTaskTest` 는 가짜 `SubagentExecutionManager` 를 쓴다. 그래서 5(트레이싱)는 "TOOL span 셋이 한 ITERATION 아래,
  포크마다 자기 TOOL span 의 metadata" 까지를 단언하고 **SUBAGENT span 의 속성 집합은 단언하지 않는다**. 6(예산·쿼터)은 턴의
  토큰이 부모 LLM 호출의 합이라는 것만 단언하고 `InMemoryUsageLedger` 의 포크 charge 는 단언하지 않는다(가짜 포크는 LLM 을 부르지 않는다).
  9(launch 라벨 끝까지)는 둘로 나뉘었다 — 매니저 수준의 Start/Stop · 포크 도구 · 중첩 덮어쓰기는 `DefaultSubagentExecutionManagerForkLabelTest`,
  CLI 줄은 `SubagentLaunchDisplayHookTest` · `ToolCallDisplayHookTest` · `OutputFormatterTest`.
- `DefaultSubagentExecutorTest` 확장 대신 위의 `DefaultSubagentExecutionManagerForkLabelTest` 가 포크 깊이 전달과 `maxForkDepth` 에 따른 포크 안
  fan-out 을 함께 덮는다.

### 9.4 문서

§3.15 의 표대로 고쳤다. 본문의 "관련 문서" 는 승인본 그대로 코드 표기로 남기고, 이 문서에서 가는 링크는 여기 둔다:
[`../tool/parallel-execution.md`](../tool/parallel-execution.md) · [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) ·
[`execution.md`](execution.md) · [`../observability/subagent-trace-nesting.md`](../observability/subagent-trace-nesting.md) ·
[`../../overview/scope-model.md`](../../overview/scope-model.md) · [`../../overview/glossary.md`](../../overview/glossary.md) ·
[`../../features/tool/parallel-tool-execution-guide.md`](../../features/tool/parallel-tool-execution-guide.md) ·
[`../../features/subagent/subagent-development-guide.md`](../../features/subagent/subagent-development-guide.md).
리뷰 기록(`review-*.md` · `rebuttal-*.md`)은 저장소에 들이지 않았다. 인터럽트 백로그의 4번 항목("병렬화 대상이 넓어지면 승격 규칙을
다시 본다")에는 이 변경이 그 집합을 넓히지 **않았다**는 덧붙임을 달았다.

### 9.5 Open questions 의 행방

§8 가운데 **이 변경 밖으로 결과가 나가는 것**만
[`../../backlog/parallel-foreground-task-open-items.md`](../../backlog/parallel-foreground-task-open-items.md) 로 올렸다.

| §8 | 백로그 |
|----|--------|
| Q4 — `AimonStackBuilder` · Spring 노출 | PF-1 |
| Q2 — 백그라운드 `Task` 호출도 전용 허가를 쓴다 | PF-2 |
| Q9 — `runForeground` 반환 뒤 PostTool · `onCompleted` 의 늦은 interrupt 창 | PF-3 |
| (§6.3) `LlmCallMetadataHolder` 의 ambient 태그는 전용 스레드에서 보이지 않는다 | PF-6 |
| (리뷰 3) PermissionRequest 훅 `ASK` 의 중복 제거 | PF-4 |
| (리뷰 3) 부모 수준 승인 대기자는 부모 취소에 깨어나지 않는다 | PF-5 |

Q1 · Q3 · Q5 · Q6 · Q7 · Q8 · Q10 ~ Q13 은 본문의 잠정 처리대로 구현했고 이 변경 안에서 닫힌 결정이라 올리지 않았다.
