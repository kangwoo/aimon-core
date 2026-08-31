# 도구 병렬 실행 (ConcurrencyBehavior)

> Status: **IMPLEMENTED** — `OrcaAgentExecutor` 와 `DefaultSubagentExecutor` 양쪽에 들어가 있다.
> 기능 자체는 **기본 off** 이며 `OrcaAgentExecutorFactory.withToolConcurrencyConfig(...)` 로 opt-in 한다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.tool` (`ConcurrencyBehavior`,
> `ParallelToolDispatcher`, `DefaultParallelToolDispatcher`, `ToolConcurrencyConfig`,
> `StreamingToolScheduler`), `at.aimon.core.agent.impl.orca`, `at.aimon.core.subagent.execution`.
>
> 스트리밍 중첩(G7)의 **실행기 쪽 설계**는 [orca-executor.md §11](../agent-execution/orca-executor.md) 소관이고,
> 이 문서는 그것이 올라타는 **디스패처 계약**까지만 다룬다 (§7).

---

## 1. 무엇을 풀었는가

한 LLM 응답에 `tool_use` 가 여러 개 담겨 돌아왔을 때, 예전에는 두 실행기 모두 순차 for 루프로 돌렸다.
도구 N개가 각각 100ms 블로킹이면 한 iteration 의 도구 단계는 그대로 N×100ms 였다.

핵심 질문은 **"모델이 여러 도구를 한 번에 반환하면 곧바로 병렬 실행해도 되는가"** 였고, 답은
**아니오** 다. 한 응답에 여러 `tool_use` 가 담겼다는 것은 모델이 "이들은 서로의 결과를 기다릴 필요가
없다"고 본 **의도 신호**일 뿐, 프레임워크·도구 레벨의 부수효과 충돌까지 보장하지는 않는다. 그래서
**모델의 의도 + 프레임워크의 안전성 검증**이라는 2단 판단을 거친다 (§2).

도입 시점에 정리된 계약은 넷이다.

| 계약 | 내용 |
|------|------|
| 기존 `Tool` API 비파괴 | `execute(ToolInput, ToolContext)` 시그니처 그대로. 안전성 선언은 `getInterruptBehavior()` 와 동형의 `default` 메서드로만 추가 (`Tool.java:213`) |
| 보수적 기본값 | `getConcurrencyBehavior()` 기본이 `SEQUENTIAL`, 기능 자체도 기본 off → **기존 도구를 한 줄도 고치지 않아도 동작 불변** |
| 결과 순서 보존 | 병렬로 돌아도 `ToolUseResult` 리스트는 항상 입력 `toolUses` 순서로 재조립된다 |
| 책임 분리 | `Tool` 은 선언, `ParallelToolDispatcher` 는 분배·조정. Pre/PostTool 훅·권한·인터럽트 등록은 실행기가 그대로 소유하고 `runner` 콜백으로 넘긴다 |

마지막 줄은 `InterruptBehavior` / `InterruptCoordinator` 분리와 **같은 모양**이다 — 도구가 능력을
선언하고, 별도 조정자가 그것을 어떻게 쓸지 정한다.

---

## 2. 2단 게이트

```
        ┌─────────────────────────────────────────────┐
        │ Layer 1 — 의도 (모델) + 설정                  │
        │   enabled 인가?                              │
        │   한 응답에 tool_use 가 2개 이상인가?          │
        └───────────────────┬─────────────────────────┘
                            │ 통과
                            ▼
        ┌─────────────────────────────────────────────┐
        │ Layer 2 — 안전성 (프레임워크)                 │
        │   배치의 모든 도구가                          │
        │     · registry 에서 resolve 되고              │
        │     · CONCURRENT_SAFE 이며                   │
        │     · 병렬 가능 InterruptBehavior 인가?       │
        └───────────────────┬─────────────────────────┘
                            │ 전부 만족
                            ▼
                   병렬 실행 (공유 풀)
```

세 가지를 못박아 둔다.

- **혼합 배치는 전체 순차다.** 하나라도 `SEQUENTIAL` 이면 배치 전부를 순차로 돌린다. "안전한 것만
  병렬 + 나머지 순차" 로 쪼개는 것은 순서 의존 가능성 때문에 미뤘다 (§9).
- **미등록(hallucinated) 도구명은 `false` 다.** `registry.findByName` 이 빈 `Optional` 이면 게이트가
  떨어지고, 배치 전체가 순차 경로로 내려가 기존과 똑같은 "tool not found" 를 돌려준다.
- **병렬 가능 InterruptBehavior 는 `NON_INTERRUPTIBLE` 과 `COOPERATIVE` 뿐이다**
  (`DefaultParallelToolDispatcher.java:320-322`). `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED` 도구는
  terminator 를 **실행 스레드 기준**으로 등록하는데 공유 워커 스레드에서는 그 의미가 모호해진다.
  그래서 `CONCURRENT_SAFE` 로 선언되어 있어도 게이트에서 빠진다.

게이트 판정은 dispatch 직전 **호출 스레드에서 1회** 수행된다. `DefaultToolRegistry` 는 thread-safe 가
아니지만, 한 턴 동안 register/unregister 로 변경되지 않으므로(동시 쓰기 없음) 게이트와 워커의 동시
**읽기**는 안전하다.

---

## 3. 디스패처 계약

```java
List<ToolUseResult> dispatch(List<ToolUse> toolUses,
                             ToolRegistry registry,
                             Function<ToolUse, ToolUseResult> runner,
                             Consumer<ToolUse> onStarted,
                             BiConsumer<ToolUse, ToolUseResult> onCompleted);
```

`registry` 가 시그니처에 있는 이유는 그것이 실행기의 필드가 아니라 **호출마다 들어오는 세션 registry**
이기 때문이다 (Orca `sessionRegistry`, subagent `lc.sessionRegistry`). 게이트가 정책을 lookup 하려면
같은 값을 받아야 한다.

| 보장 | 내용 |
|------|------|
| 결과 순서 | 완료 순서와 무관하게 **입력 순서**로 재조립 (`joinInto`) |
| `onStarted` | 항상 **호출 스레드에서 입력 순서**로 발화 — 병렬 경로에서도 |
| `onCompleted` | 병렬 경로에서는 워커 스레드에서 **완료 순**, 순차 경로에서는 호출 스레드에서 입력 순. 양쪽 다 tool-use id 로 매칭된다 → 리스너는 thread-safe 해야 한다 |
| 예외 격리 | `runner` / `onStarted` / `onCompleted` 가 던지거나 null 을 돌려줘도 dispatcher 가 잡아 에러 `ToolUseResult` 로 바꾸거나 log-and-swallow 한다. 한 도구·한 리스너의 실패가 배치 순서나 join 을 깨뜨리지 않는다 |
| 풀 종료 경합 | 배치 도중 `close()` 가 이겨 `RejectedExecutionException` 이 나면 이미 제출한 것을 join 한 뒤 **남은 꼬리를 호출 스레드에서 순차 실행**한다. `dispatch()` 는 절대 예외를 밖으로 내보내지 않고 permit 도 새지 않는다 |

`DefaultParallelToolDispatcher` 는 executor-scoped 이며 풀은 **lazy + daemon** 이다. 기능이 꺼져 있으면
(`ToolConcurrencyConfig.disabled()`, 기본값) **풀 자체를 만들지 않는다** — 회귀 0, 자원 0. 명시적
정리는 `AutoCloseable#close()` 가 30초 drain 으로 제공한다. 실행기가 dispatcher 를 주입받지 않았을 때의
기본값은 `DefaultParallelToolDispatcher.sequential()` 이다.

---

## 4. 두 개의 상한 — 전역 풀과 배치당 캡

`ToolConcurrencyConfig` 는 2-tier 다. 하나로 합칠 수 없는 이유는 두 값이 **서로 다른 것을 보호**하기
때문이다.

| 값 | 보호 대상 | 기본값 |
|----|----------|--------|
| `maxConcurrency` | **호스트** — 한 실행기를 공유하는 모든 턴을 통틀어 동시에 도는 도구 수의 상한. 공유 워커 풀의 크기 | `DEFAULT_MAX_CONCURRENCY = 4` |
| `perBatchMax` | **다른 턴** — 배치 하나가 공유 풀을 독점해 동시 진행 중인 턴을 굶기지 못하게 한다. `[1, maxConcurrency]` | `maxConcurrency` (= 단일 tier 와 바이트 동일) |

`perBatchMax` 는 dispatch 호출마다 스택 로컬 `Semaphore` 하나로 구현된다 — 공유 가변 상태도, 종료
대상도 늘리지 않는다. permit 은 **제출 전에 호출 스레드에서** 획득하므로, 슬롯을 기다리는 도구가 공유
워커 스레드를 물고 있는 일이 없다. 배치가 캡보다 크면 제출이 호출 스레드에서 막히고(백프레셔),
그 도구의 `onStarted` 도 함께 지연된다 — **순서는 그대로 입력 순서**이고 발화 시점만 늦는다.

`maxConcurrency` 가 전역이라는 것은 테스트로 못박혀 있다 — `OrcaAgentExecutorConcurrentDispatcherCeilingTest`
는 두 세션이 latch 로 랑데부한 상태에서 각각 3개짜리 배치를 던져, 풀 캡이 2일 때 여섯 개 중 동시에 도는
것이 **결코 2를 넘지 않으면서도 2에는 도달**함을 확인한다(두 번째 단언이 없으면 "그냥 직렬화됐다"와
구분되지 않는다).

> `maxConcurrency` 기본값이 `1` 이 아니라 `4` 인 것은 의도다. `enabled` 가 단일 마스터 스위치이므로
> operator 가 그것만 켜도 의미 있는 병렬도를 얻어야 한다 — 기본 1은 "켰는데도 순차"라는 함정이었다.

---

## 5. `read_files` — 공유 가변 상태 하나

`ToolContext` 를 지나는 값은 대부분 불변이다(`ToolInput`/`ToolResult`/`ToolContext` 자체,
`EXECUTION_ATTRIBUTES_KEY`). 도구가 **변조**하는 것으로 식별된 값은 `ReadTool.READ_FILES_KEY` 하나뿐이고,
그래서 두 실행기 모두 `ToolContext` 를 구성할 때 thread-safe set 을 주입한다.

```java
builder.put(ReadTool.READ_FILES_KEY, ConcurrentHashMap.newKeySet());
```

`OrcaAgentExecutor.java:833`, `DefaultSubagentExecutor.java:637`.

이것은 "caller 가 넘기던 plain `HashSet` 을 교체" 한 것이 **아니다**. 그전까지 두 실행기 중 어느 쪽도
이 키를 주입하지 않았고 오직 테스트만 넣고 있었다. 그래서 이 주입은 신규 추가이고, 부수 효과로
**그동안 프로덕션에서 한 번도 동작하지 않던 `EditTool` 의 read-before-edit 가드가 비로소 작동한다** —
set 이 없으면 `EditTool` 이 항상 "미열람"으로 판정해 사실상 no-op 이었다.

`createToolContext` 는 턴당 1회 호출되므로 set 은 iteration 사이에 유지되고, read-before-edit 가 여러
iteration 에 걸쳐 정상 동작한다.

**새 도구가 가변 상태를 `ToolContext` 에 넣고 변조한다면 반드시 `SEQUENTIAL` 로 선언하거나 thread-safe
자료구조를 써야 한다.** 사용자 정의 Pre/PostTool 훅도 마찬가지다 — 병렬 가능 도구의 훅은 워커 스레드에서
동시 호출될 수 있다.

---

## 6. 도구별 선언 현황

`CONCURRENT_SAFE` 를 선언한 내장 도구는 넷이다.

| 도구 | InterruptBehavior | 근거 |
|------|-------------------|------|
| `ReadTool` | `NON_INTERRUPTIBLE` | 읽기 전용. 유일한 공유 가변 상태인 `READ_FILES_KEY` set 은 §5 로 thread-safe |
| `GrepTool` | `COOPERATIVE` | 읽기 전용 |
| `WebFetchTool` | `COOPERATIVE` | 외부 GET, 멱등, 캐시 동기화됨 |
| `TaskListTool` | (기본) `NON_INTERRUPTIBLE` | thread-safe 스토어에 대한 읽기 전용 |

나머지는 전부 기본값 `SEQUENTIAL` 이다 — `EditTool`/`WriteTool`(파일 변조 충돌), `BashTool`(부수효과 +
`THREAD_INTERRUPT`), `TodoWriteTool`(공유 상태 변조).

선언 체크리스트(부수효과·공유 상태·InterruptBehavior·훅 thread-safety·외부 리소스 경합)는 사용자
문서인 [도구 개발 가이드](../../features/tool/tool-development-guide.md#동시-실행-안전성-concurrencybehavior)
에 있다. **하나라도 불확실하면 `SEQUENTIAL`.**

`ConcurrencyBehavior` 는 `SideEffectLevel` 과 **다른 축**이며 어느 쪽도 다른 쪽에서 유도되지 않는다 —
읽기 전용인데 `SEQUENTIAL` 일 수 있고(rate-limited 엔드포인트 경합), 멱등한 *변조* 도구가
`CONCURRENT_SAFE` 일 수 있다. 둘은 독립적으로 선언한다. 두 축의 관계는
[side-effect-axes.md](side-effect-axes.md) 를 본다.

---

## 7. eager dispatch — 스트리밍 중첩이 올라타는 자리

`dispatch()` 는 배치 전체를 미리 받아야 한다. 스트리밍 툴 중첩(G7)은 반대로 **완성된 `tool_use` 블록을
하나씩** — 스트리밍이 끝나는 즉시 — 넘겨 그 실행이 아직 도착 중인 토큰 스트림과 겹치게 한다. 그래서
`ParallelToolDispatcher` 에 `default` 메서드 네 개가 seam 으로 열려 있다.

| 메서드 | 역할 | 기본값 |
|--------|------|--------|
| `supportsEagerDispatch()` | 이 dispatcher 가 조기 실행을 할 수 있는가 | `false` |
| `isEagerEligible(toolUse, registry)` | 게이트 **Layer 2 를 도구 하나에 적용**한 슬라이스. 배치 크기는 보지 않는다 | `false` |
| `submitEager(toolUse, runner)` | 배치 부기 없이 공유 풀에 1건 제출, future 반환 | `Optional.empty()` |
| `eagerPermits()` | 호출자가 세마포어를 맞출 배치당 캡 | `1` |

기본 구현이 전부 opt-out 이므로 풀 없는 dispatcher 는 영향을 받지 않는다. 배치 단위의 prefix-safety 와
결과 재조립은 **호출자(`StreamingToolScheduler`)** 소유이고 dispatcher 는 관여하지 않는다 —
안전성 판정을 새로 만들지 않고 §2 의 게이트를 per-tool 로 재사용한다는 것이 이 분할의 요점이다.

세 개의 knob(streaming · `concurrency.enabled` · `streamingOverlap`) 중 하나라도 꺼지면 스케줄러가 아예
설치되지 않는다. 스케줄러 쪽 설계 — prefix-safety poison, 이벤트를 harvest 로 이연해 결과가 non-overlap
경로와 바이트 동일해지는 결정성, 재시도 안전성 — 은
[orca-executor.md §11](../agent-execution/orca-executor.md) 에 있다.

---

## 8. 설계 결정

### 8.1 안전성 선언 채널은 `Tool` 의 `default` 메서드

`getInterruptBehavior()` 와 동형. 기존 도구 무수정, API 비파괴이고, 자기 도구의 안전성은 도구 작성자가
가장 잘 안다(SRP). 대안이던 "실행기가 도구명 화이트리스트를 보유" 는 도구를 추가할 때마다 실행기를
고쳐야 하므로 OCP 위반이라 기각했다.

### 8.2 값은 둘뿐

"읽기지만 다른 읽기와만 안전" 같은 세분화는 넣지 않았다. 리소스 키 기반 충돌 회피
(`RESOURCE_EXCLUSIVE(key)` 류)는 실제로 그것을 요구하는 도구가 나타나면 그때 확장한다 (§9).

### 8.3 이름이 `ConcurrencyPolicy` 가 아니라 `ConcurrencyBehavior` 인 이유

`Behavior` 접미사는 이 값을 `InterruptBehavior` 와 같은 자리 — 도구가 자기에 대해 선언하는 **무순서
trait** — 에 놓는다. 반대편의 `SideEffectLevel` 은 **순서가 있고 비교되는** 값이라 `Level` 을 쓴다.
그 구분이 그어지기 전까지 이 타입은 `ConcurrencyPolicy` 였다.

### 8.4 `ToolUseStarted` 는 순서 보존, `ToolResultReady` 는 완료 순

시작 이벤트는 호출 스레드에서 순서대로 emit 하므로 렌더러가 배치를 입력 순서로 그릴 수 있다. 완료
이벤트는 실제 완료 순으로 나가지만 `EventEmitter` 가 thread-safe(`CopyOnWriteArrayList`)이고 이벤트에
tool-use id 가 있어 렌더러가 정합을 맞춘다.

---

## 9. 의도적으로 제외한 것 / 남은 것

| 항목 | 상태 |
|------|------|
| 부분 병렬화 (혼합 배치에서 안전한 부분집합만 병렬) | 순서 의존 분석이 필요해 보류. 지금은 전체 순차 |
| 리소스 키 기반 세분화 (`RESOURCE_EXCLUSIVE(key)`) | 요구하는 도구가 나타나면 |
| `THREAD_INTERRUPT` 도구의 병렬 | 범위 밖. cross-thread terminator 의미가 모호 |
| 도구 간 의존성 그래프 자동 추론 | 과도한 복잡성 — 채택하지 않음 |
| 모델 신호만으로의 무조건 병렬 | 채택하지 않음 (§2 가 이 문서의 결론) |
| virtual thread 전환 | Java 21 채택 시 bounded platform pool 을 교체 가능. `ParallelToolDispatcher` 인터페이스는 불변 |
| `DefaultSubagentExecutor` 로의 스트리밍 중첩 확장 | 같은 dispatcher 를 주입하면 SPI 상 가능하지만 별도 과제 |

병렬 실행은 단일 실행 안의 in-memory 동시성이므로 저장소 추상화 대상이 아니다. 풀은 실행기 생명주기에
종속되며 클러스터에서는 인스턴스별로 독립 설정된다.

---

## 부록 — 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 안전성 선언 enum | `agent/tool/ConcurrencyBehavior.java` |
| 도구 선언 채널 | `agent/tool/Tool.java:213` (`getConcurrencyBehavior`) |
| 디스패처 계약 + eager seam | `agent/tool/ParallelToolDispatcher.java` |
| 게이트·풀·순서 재조립·2-tier 캡 | `agent/tool/DefaultParallelToolDispatcher.java` |
| 설정 (enabled / maxConcurrency / perBatchMax / streamingOverlap) | `agent/tool/ToolConcurrencyConfig.java` |
| 스트리밍 중첩 스케줄러 | `agent/tool/StreamingToolScheduler.java` |
| Orca 와이어링 + `read_files` 주입 | `agent/impl/orca/OrcaAgentExecutor.java:833,2012` |
| opt-in 지점 | `agent/impl/orca/OrcaAgentExecutorFactory.java:389,729` |
| subagent 와이어링 | `subagent/execution/DefaultSubagentExecutor.java:247,637,786` |
| `CONCURRENT_SAFE` 선언 도구 | `tools/file/ReadTool.java:312`, `tools/file/GrepTool.java:522`, `tools/web/WebFetchTool.java:201`, `tools/task/TaskListTool.java:91` |
| 회귀 방어 | `agent/tool/DefaultParallelToolDispatcherTest`, `ToolConcurrencyConfigTest`, `StreamingToolSchedulerTest`, `agent/impl/orca/OrcaAgentExecutorParallelToolTest`, `OrcaAgentExecutorConcurrentDispatcherCeilingTest`, `OrcaAgentExecutorBatchInterruptTest`, `tools/file/ReadToolConcurrencyTest` |

---

## 관련 문서

- [도구 병렬 실행 가이드](../../features/tool/parallel-tool-execution-guide.md) — 사용·운영·트러블슈팅
- [Tool 개발 가이드](../../features/tool/tool-development-guide.md) — `CONCURRENT_SAFE` 선언 체크리스트
- [orca-executor.md](../agent-execution/orca-executor.md) — §11 스트리밍 툴 중첩(G7)
- [interrupt.md](../agent-execution/interrupt.md) — `InterruptBehavior` / `InterruptCoordinator` 분리 (본 설계의 선례)
- [side-effect-axes.md](side-effect-axes.md) — `SideEffectLevel` 과의 축 분리
- [artifact.md](../agent-execution/artifact.md) — `ArtifactCollector` 의 병렬 안전 설계
- [SOLID 원칙](../../project/solid-principles.md)
