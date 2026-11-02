# 도구 병렬 실행 (ConcurrencyBehavior)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.agent.tool` (`ConcurrencyBehavior`, `ParallelToolDispatcher`, `DefaultParallelToolDispatcher`, `ToolConcurrencyConfig`, `StreamingToolScheduler`), `at.aimon.core.agent.impl.orca`, `at.aimon.core.subagent.execution`. 기능은 기본으로 꺼져 있고 `OrcaAgentExecutorFactory.withToolConcurrencyConfig(...)` 로 켠다.

---

## 1. 의도와 안전성

한 응답의 여러 `tool_use` 는 모델의 의도 신호일 뿐 안전 보장이 아니다. 병렬 실행은 의도와 프레임워크의 안전성 검증을 모두 요구한다.

계약은 넷이다.

| 계약 | 내용 |
|---|---|
| `Tool` API 를 고치지 않는다 | `execute(ToolInput, ToolContext)` 시그니처는 그대로다. 안전성 선언은 `getInterruptBehavior()` 와 같은 모양의 `default` 메서드다 |
| 보수적 기본값 | `getConcurrencyBehavior()` 의 기본은 `SEQUENTIAL` 이고 기능도 기본으로 꺼져 있다 |
| 결과 순서 보존 | 병렬로 돌아도 `ToolUseResult` 목록은 언제나 입력 `toolUses` 순서다 |
| 책임 분리 | `Tool` 은 선언하고 `ParallelToolDispatcher` 는 분배·조정한다. Pre/PostTool 훅·권한·인터럽트 등록은 실행기가 소유하고 `runner` 콜백으로 넘긴다 |

책임 분리는 `InterruptBehavior` / `InterruptCoordinator` 와 같은 모양이다. 도구가 능력을 선언하고, 별도 조정자가 그것을 어떻게 쓸지 정한다.

---

## 2. 2단 게이트

```
Layer 1 — 의도(모델)와 설정
  · enabled 인가?
  · 한 응답에 tool_use 가 2개 이상인가?
        │ 통과
        ▼
Layer 2 — 안전성(프레임워크). 배치의 모든 도구가
  · registry 에서 resolve 되고
  · CONCURRENT_SAFE 이며
  · 병렬 가능 InterruptBehavior 인가?
        │ 전부 만족
        ▼
병렬 실행 (공유 풀)
```

어느 조건이든 어긋나면 배치 전체가 순차로 간다.

- **혼합 배치는 전체 순차다.** 하나라도 `SEQUENTIAL` 이면 배치 전부를 순차로 돌린다.
- **`DEDICATED_THREAD` 는 이 게이트에서 `SEQUENTIAL` 로 읽힌다.** 전용 스레드 데코레이터가 설치되었을 때만 그 위에서 연속 구간이 따로 펴진다. 그 설계는 [포그라운드 Task 병렬 실행](../subagent/parallel-foreground-task.md) 이다.
- **미등록 도구명은 게이트를 떨어뜨린다.** `registry.findByName` 이 비면 배치 전체가 순차 경로로 내려가 모델이 지어낸 이름에 평소의 도구 없음 에러를 돌려준다. `registry` 가 `null` 이어도 순차다.
- **병렬 가능 `InterruptBehavior` 는 `NON_INTERRUPTIBLE` 과 `COOPERATIVE` 뿐이다.** `THREAD_INTERRUPT`·`EXTERNALLY_TERMINATED` 도구는 terminator 를 실행 스레드 기준으로 등록하는데 공유 워커 스레드에서는 그 의미가 모호하다. `CONCURRENT_SAFE` 로 선언되어 있어도 게이트에서 빠진다.

게이트 판정은 dispatch 직전 호출 스레드에서 1회 한다. `DefaultToolRegistry` 는 thread-safe 가 아니지만 한 execution 동안 register·unregister 로 변경되지 않으므로 게이트와 워커의 동시 읽기는 안전하다.

---

## 3. 디스패처 계약

```java
List<ToolUseResult> dispatch(List<ToolUse> toolUses,
                             ToolRegistry registry,
                             Function<ToolUse, ToolUseResult> runner,
                             Consumer<ToolUse> onStarted,
                             BiConsumer<ToolUse, ToolUseResult> onCompleted);

default List<ToolUseResult> dispatch(List<ToolUse> toolUses,
                                     ToolRegistry registry,
                                     DispatchOptions options,
                                     Function<ToolUse, ToolUseResult> runner,
                                     Consumer<ToolUse> onStarted,
                                     BiConsumer<ToolUse, ToolUseResult> onCompleted);
```

`registry` 가 시그니처에 있는 이유는 그것이 실행기의 필드가 아니라 호출마다 들어오는 실행의 registry 이기 때문이다. 게이트가 정책을 찾으려면 같은 값을 받아야 한다.

`DispatchOptions` 를 받는 오버로드의 기본 구현은 옵션을 무시하고 원래 메서드로 간다. 옵션의 뜻과 `routesHarvestTail()` 은 [포그라운드 Task 병렬 실행](../subagent/parallel-foreground-task.md) 이 정한다.

| 보장 | 내용 |
|---|---|
| 결과 순서 | 완료 순서와 무관하게 입력 순서로 재조립한다 |
| `onStarted` | 언제나 호출 스레드에서 입력 순서로 발화한다. 병렬 경로도 같다 |
| `onCompleted` | 병렬 경로에서는 워커 스레드에서 완료 순, 순차 경로에서는 호출 스레드에서 입력 순이다. 양쪽 다 tool-use id 로 매칭한다. 리스너는 thread-safe 해야 한다 |
| 콜백 | `onStarted`·`onCompleted` 는 `null` 일 수 있다 |
| 예외 격리 | `runner`·`onStarted`·`onCompleted` 가 던지거나 `runner` 가 `null` 을 돌려줘도 디스패처가 잡아 에러 `ToolUseResult` 로 바꾸거나 로그만 남긴다. 한 도구·한 리스너의 실패가 배치 순서나 join 을 깨뜨리지 않는다 |
| 풀 종료 경합 | 배치 도중 `close()` 가 이겨 `RejectedExecutionException` 이 나면 이미 제출한 것을 join 한 뒤 남은 꼬리를 호출 스레드에서 순차 실행한다. `dispatch()` 는 예외를 밖으로 내보내지 않고 permit 도 새지 않는다 |

`DefaultParallelToolDispatcher` 는 실행기 수명이고 풀은 지연 생성되는 daemon 이다. 기능이 꺼져 있으면(`ToolConcurrencyConfig.disabled()`, 기본값) 풀을 만들지 않는다. 명시적 정리는 `close()` 가 30초 drain 으로 제공한다. 실행기가 디스패처를 주입받지 않았을 때의 기본값은 `DefaultParallelToolDispatcher.sequential()` 이다.

---

## 4. 두 개의 상한

`ToolConcurrencyConfig` 의 상한은 2단이다. 두 값이 서로 다른 것을 보호하므로 합칠 수 없다.

| 값 | 보호 대상 | 기본값 |
|---|---|---|
| `maxConcurrency` | **호스트.** 한 실행기를 공유하는 모든 execution 을 통틀어 동시에 도는 도구 수의 상한이고, 공유 워커 풀의 크기다 | `DEFAULT_MAX_CONCURRENCY = 4` |
| `perBatchMax` | **다른 execution.** 배치 하나가 공유 풀을 독점해 동시에 진행 중인 execution 을 굶기지 못하게 한다. 범위는 `[1, maxConcurrency]` | `maxConcurrency` |

`perBatchMax` 는 dispatch 호출마다 스택 로컬 `Semaphore` 하나로 구현한다. 공유 가변 상태도 종료 대상도 늘리지 않는다. permit 은 제출 전에 호출 스레드에서 획득하므로, 슬롯을 기다리는 도구가 공유 워커 스레드를 물고 있지 않다. 배치가 캡보다 크면 제출이 호출 스레드에서 막히고 그 도구의 `onStarted` 도 함께 늦어진다. 순서는 그대로 입력 순서다.

`maxConcurrency` 기본값은 4 다. `enabled` 가 단일 스위치이므로 그것만 켜도 병렬이 되어야 한다.

---

## 5. `ToolContext` 의 공유 가변 상태

실행기는 `ToolContext` 를 구성할 때 도구가 변조하는 상태를 thread-safe 구조로 주입한다. `FILE_STAMPS_KEY` 맵은 실행마다 새로 만들어 iteration 사이에 유지된다.

컨텍스트를 지나는 가변 값은 thread-safe 로 주입되거나(`FILE_STAMPS_KEY`, `ArtifactCollector`), 그것을 변조하는 도구가 `SEQUENTIAL` 이다(`ToolSearchRegistry` 의 활성화 상태).

새 도구가 가변 상태를 `ToolContext` 에 넣고 변조한다면 `SEQUENTIAL` 로 선언하거나 thread-safe 자료구조를 쓴다. Pre/PostTool 훅도 같다. 병렬 가능 도구의 훅은 워커 스레드에서 동시에 호출될 수 있다.

`FileStamp` 자체는 [파일 규칙](execution-environment-files.md) 가 정본이다.

---

## 6. 선언 규칙

`ConcurrencyBehavior` 의 값은 셋이다.

| 값 | 게이트에서 | 쓰는 경우 |
|---|---|---|
| `SEQUENTIAL` (기본값) | 배치 전체를 순차로 만든다 | 파일·샌드박스 상태를 바꾸거나, 공유 상태를 비원자적으로 바꾸거나, 이웃 `tool_use` 와 순서 의존이 있다 |
| `CONCURRENT_SAFE` | 병렬 후보 | 관측 가능한 부작용이 없거나 멱등이고, 공유 가변 상태를 thread-safe 하게만 만진다 |
| `DEDICATED_THREAD` | `SEQUENTIAL` 로 읽는다 | [§2](#2-2단-게이트) |

`CONCURRENT_SAFE` 선언은 도구 소스가 정본이다. 대표 예는 `ReadTool`, `GrepTool` 이다. 선언 체크리스트는 [도구 개발 가이드](../../features/tool/tool-development-guide.md) 에 있다. 하나라도 불확실하면 `SEQUENTIAL` 이다.

`ConcurrencyBehavior` 는 `SideEffectLevel` 과 다른 축이고 어느 쪽도 다른 쪽에서 유도되지 않는다. 근거는 [부작용 축](side-effect-axes.md) 에 있다.

---

## 7. eager dispatch

`dispatch()` 는 배치 전체를 미리 받는다. 스트리밍 툴 중첩은 완성된 `tool_use` 블록을 하나씩 넘겨 그 실행이 아직 도착 중인 토큰 스트림과 겹치게 한다. `ParallelToolDispatcher` 는 이를 위해 `default` 메서드 넷을 연다.

| 메서드 | 역할 | 기본값 |
|---|---|---|
| `supportsEagerDispatch()` | 이 디스패처가 조기 실행을 할 수 있는가 | `false` |
| `isEagerEligible(toolUse, registry)` | 게이트 Layer 2 를 도구 하나에 적용한 슬라이스. 배치 크기는 보지 않는다 | `false` |
| `submitEager(toolUse, runner)` | 배치 부기 없이 공유 풀에 1건 제출하고 future 를 돌려준다 | `Optional.empty()` |
| `eagerPermits()` | 호출자가 세마포어를 맞출 배치당 캡 | `1` |

기본 구현이 전부 opt-out 이므로 풀 없는 디스패처는 영향을 받지 않는다. 안전성 판정을 새로 만들지 않고 §2 의 게이트를 도구별로 재사용한다. 배치 단위의 prefix-safety 와 결과 재조립은 호출자(`StreamingToolScheduler`)가 소유한다.

스케줄러와 켜는 조건의 설계는 [Orca 실행기](../agent-execution/orca-executor.md) 의 "스트리밍 툴 중첩" 절이 정본이다.

---

## 8. 의도적으로 제외한 것

| 항목 | 판단 |
|---|---|
| 부분 병렬화 (혼합 배치에서 안전한 부분집합만 병렬) | 순서 의존 분석이 필요해 보류한다. 혼합 배치는 전체 순차다 |
| `THREAD_INTERRUPT` 도구의 풀 실행 | 범위 밖이다. 스레드를 넘는 terminator 의 의미가 모호하다 |
| 도구 간 의존성 그래프 자동 추론 | 복잡도에 비해 얻는 것이 적어 채택하지 않는다 |
| 모델 신호만으로의 무조건 병렬 | 채택하지 않는다. [§1](#1-의도와-안전성) 이다 |
| virtual thread 전환 | Java 21 을 채택하면 bounded platform pool 을 교체할 수 있다. `ParallelToolDispatcher` 인터페이스는 바뀌지 않는다 |

입력 인지 동시성과 리소스 키 기반 세분화는 [TC-4](../../backlog/tool-contract-open-items.md), 서브에이전트 스트리밍 중첩은 [TC-17](../../backlog/tool-contract-open-items.md) 이다.

병렬 실행은 단일 실행 안의 in-memory 동시성이므로 저장소 추상화 대상이 아니다. 풀은 실행기 수명에 종속되고 클러스터에서는 인스턴스별로 독립 설정한다.

---

## 9. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 게이트를 의도와 안전성 2단으로 둔다 | 한 응답에 담겼다는 것은 프레임워크·도구 수준의 부수효과 충돌까지 보장하지 않는다 | 모델 신호만으로 병렬 |
| 안전성 선언 채널은 `Tool` 의 `default` 메서드다 | `getInterruptBehavior()` 와 같은 모양이다. 도구를 고칠 필요가 없고, 자기 도구의 안전성은 도구 작성자가 가장 잘 안다 | 실행기가 도구명 화이트리스트를 보유: 도구를 추가할 때마다 실행기를 고쳐야 한다 |
| 값은 `SEQUENTIAL`, `CONCURRENT_SAFE`, `DEDICATED_THREAD` 셋이다 | 세 번째는 안전성의 세분화가 아니라 다른 실행 메커니즘에 대한 동의이고, 게이트는 그것을 `SEQUENTIAL` 로 읽는다 | "다른 읽기와만 안전" 같은 세분화 |
| 이름은 `Behavior` 접미사다 | 이 값을 `InterruptBehavior` 와 같은 자리, 곧 도구가 자기에 대해 선언하는 순서 없는 형질에 놓는다. 기준은 [부작용 축](side-effect-axes.md) 이 정한다 | `Policy`, `Level` |
| `ToolUseStarted` 는 순서를 보존하고 `ToolResultReady` 는 완료 순이다 | 시작 이벤트는 호출 스레드에서 순서대로 나가므로 렌더러가 배치를 입력 순서로 그릴 수 있다. 완료 이벤트는 실제 완료 순으로 나가지만 `EventEmitter` 가 thread-safe 이고 이벤트에 tool-use id 가 있어 렌더러가 맞춘다 | 완료 이벤트도 입력 순서로 지연 |
| 상한을 전역 풀과 배치당 캡 둘로 둔다 | [§4](#4-두-개의-상한) | 상한 하나 |
| permit 은 제출 전에 호출 스레드에서 얻는다 | 기다리는 도구가 공유 워커를 물지 않는다 | 워커 안에서 대기 |
| `maxConcurrency` 기본값은 4 다 | [§4](#4-두-개의-상한) | 기본 1: 켰는데도 순차가 된다 |

---

## 10. 하지 말 것

- **확신 없이 `CONCURRENT_SAFE` 를 선언하지 말 것.** `execute` 와 그 도구의 Pre/PostTool 훅이 워커 스레드에서 동시에 불린다는 계약이다.
- **`THREAD_INTERRUPT`·`EXTERNALLY_TERMINATED` 를 병렬 가능 집합에 넣지 말 것.** 늦은 인터럽트가 풀 워커의 다음 작업에 떨어진다.
- **thread-safe 하지 않은 가변 값을 `ToolContext` 로 넘겨 병렬 가능 도구가 변조하게 하지 말 것.**
- **`onCompleted` 리스너가 호출 스레드에서 돈다고 가정하지 말 것.**
- **완료 순서로 결과를 조립하지 말 것.** 입력 순서가 계약이다.
- **eager 경로에 안전성 판정을 따로 만들지 말 것.** `isEagerEligible` 은 §2 의 슬라이스다.
- **`SideEffectLevel` 에서 동시성을 유도하지 말 것.** 독립적으로 선언한다.
- **슬롯 대기를 워커 안에 두지 말 것.** 공유 풀이 대기로 찬다.

---

## 11. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 관심사 | 파일 |
|---|---|
| 안전성 선언 enum | `agent/tool/ConcurrencyBehavior.java` |
| 도구 선언 채널 | `agent/tool/Tool.java` (`getConcurrencyBehavior`) |
| 디스패처 계약과 eager seam | `agent/tool/ParallelToolDispatcher.java`, `agent/tool/DispatchOptions.java` |
| 게이트, 풀, 순서 재조립, 2단 상한 | `agent/tool/DefaultParallelToolDispatcher.java` |
| 설정 | `agent/tool/ToolConcurrencyConfig.java` |
| 스트리밍 중첩 스케줄러 | `agent/tool/StreamingToolScheduler.java` |
| Orca 배선과 `FILE_STAMPS_KEY` 주입 | `agent/impl/orca/OrcaAgentExecutor.java` |
| opt-in 지점 | `agent/impl/orca/OrcaAgentExecutorFactory.java` |
| 서브에이전트 배선 | `subagent/execution/DefaultSubagentExecutor.java` |
| 이벤트 발행 | `agent/impl/orca/EventEmitter.java`, `agent/stream/ToolUseStarted.java` |

---

## 12. 관련 문서

- [도구 병렬 실행 가이드](../../features/tool/parallel-tool-execution-guide.md) — 사용, 운영, 문제 해결
- [도구 개발 가이드](../../features/tool/tool-development-guide.md) — `CONCURRENT_SAFE` 선언 체크리스트
- [Orca 실행기](../agent-execution/orca-executor.md) — 스트리밍 툴 중첩
- [인터럽트](../agent-execution/interrupt.md) — `InterruptBehavior` / `InterruptCoordinator` 분리
- [부작용 축](side-effect-axes.md) — `SideEffectLevel` 과의 축 분리
- [도구 계약](tool-contract.md) — `runner` 가 감싸는 호출 경로
- [포그라운드 Task 병렬 실행](../subagent/parallel-foreground-task.md) — `DEDICATED_THREAD` 와 그것을 펴는 데코레이터
- [artifact](../agent-execution/artifact.md) — `ArtifactCollector` 의 병렬 안전 설계
- [SOLID 원칙](../../project/solid-principles.md)
- [남은 작업 등록부](../../backlog/tool-contract-open-items.md)
