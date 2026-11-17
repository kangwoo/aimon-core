# 예약 루틴의 중단과 취소

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core`(`at.aimon.core.scheduling`), `aimon-session-mongodb`(`MongoScheduledTaskInterruptBus`),
> `aimon-scheduling-testkit`(인터럽트 버스 계약 테스트).
> 남은 일은 [`backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) 가 정본이다.
> 관련 문서: [`agent-execution/interrupt.md`](../agent-execution/interrupt.md)(신호 · coordinator · `InterruptBehavior`),
> [`llm-scheduling-agent.md`](llm-scheduling-agent.md)(스케줄 실행 전반 — 실행 알고리즘 · 이력 · 이벤트 · 엔진 드레인),
> [`glossary.md`](../../overview/glossary.md)(중단과 취소, 실행 단위)

예약 루틴은 세션 없는 실행 중 유일하게 **밖에서 지목해 끊을 수 있는** 경로다. `RoutineExecutor` 가 실행마다 자기
`InterruptCoordinator` 를 만들고, 그것을 `ScheduledTaskId` 로(회차가 있으면 회차 키로도) 찾을 수 있게 노드 로컬 레지스트리에
등록한다. 신호·coordinator·terminator 의 계약은 [`interrupt.md`](../agent-execution/interrupt.md) 가 정본이고, 이 문서는 그것을
스케줄 저장소·버스·발화 위에 얹는 규칙을 정한다.

---

## 1. 중단과 취소

```java
// RoutineExecutor — 이 노드의 실행만 본다
public boolean interrupt(ScheduledTaskId taskId, InterruptReason reason);
public boolean interrupt(ScheduledTaskId taskId, String occurrenceKey, InterruptReason reason);   // §8

// ScheduledTaskManager — 소유자 검증 뒤 로컬 trip + 버스 발행
public void cancel(ScheduledTaskId taskId, Principal principal);
public boolean interrupt(ScheduledTaskId taskId, Principal principal);
public boolean interrupt(ScheduledTaskId taskId, String occurrenceKey, Principal principal);       // §8
```

`ScheduledTaskManager.cancel` 의 unschedule 과 삭제는 앞으로의 발화만 지배하므로, **진행 중인 실행을 끊는 것은 취소의 일부다.**
그렇지 않으면 스텝 중간의 루틴이 삭제된 태스크를 위해 파일을 쓰고 외부 시스템을 부르며 남은 스텝을 끝까지 돈다.

`cancel` 과 별개로 `interrupt(taskId, principal)` 를 둔다. 중단은 돌고 있는 것을 끊고 취소는 그것 자체를 그만둔다는
[용어집](../../overview/glossary.md)의 구분을 따른다. 한 번의 나쁜 실행에 물린 태스크는 전자가 필요하지 스케줄까지 지울 일이
아니다. 두 경로 모두 실행을 `TASK_CANCELLED` 로 trip 한다 — 상위 실행에서 흘러 내려온 cascade(`PARENT_CANCELLED`)가 아니라 요청이 그
실행 자신의 태스크를 지목했기 때문이다.

두 경로 모두 자유 텍스트 설명(detail)은 싣지 않는다. 설명의 계약과 이 공백은
[`interrupt.md` §3.1](../agent-execution/interrupt.md#31-설명detail--사유는-분류하고-설명은-말한다) 에 있다.

---

## 2. 전파 사다리

턴의 iteration 경계에 해당하는 것이 루틴에서는 **스텝 경계**다.

| 층 | 무엇을 보장하는가 |
|----|------------------|
| 스텝 경계 게이트 | 이후 스텝은 **시작되지 않는다**. 도구가 중단을 전혀 모르는 경우에도 성립하며, 아래 둘은 이것의 최적화다 |
| `COOPERATIVE` 스텝 | `ToolContext` 의 `CANCELLATION_SIGNAL` 을 폴링해 스텝 *안*에서 일찍 반환한다 |
| `THREAD_INTERRUPT` · `EXTERNALLY_TERMINATED` 스텝 | terminator 로 그 자리에서 끝낸다 |

`THREAD_INTERRUPT` 의 terminator 는 에이전트 루프 쪽(`SingleToolInvoker` — 턴 · 포크 · 스킬 루프의 도구 호출)과 다르다. 루틴 스텝은 호출 스레드가 아니라 timeout 풀 워커에서 돌므로
([`llm-scheduling-agent.md` §5.4](llm-scheduling-agent.md#54-재시도와-타임아웃)), "도구 스레드를 인터럽트한다" 는 것이 여기서는
`future.cancel(true)` 다. 등록은 submit **뒤에** 한다. 이미 trip 된 coordinator 에 등록하면 terminator 가 등록 스레드에서 즉시
발화한다는 registrar 계약이 그 순서를 안전하게 만든다.

---

## 3. 재시도 백오프 — 아무것도 돌지 않는 구간

스텝의 재시도 사이는 갇히기 가장 쉬운 자리다. 폴링할 도구도, 종료할 future 도 없으므로 평범한 `Thread.sleep` 은 취소된 실행을
남은 지연만큼 붙잡는다. 지연은 분 단위로 설정될 수 있으므로 "멈춘다" 와 "언젠가 멈춘다" 의 차이다.

그래서 백오프는 `CountDownLatch.await` 이고 래치를 내리는 것은 신호 자신의 리스너다. 이미 trip 된 신호에 등록하면 리스너가 동기로
실행되므로 "먼저 도착한 인터럽트" 를 위한 별도 분기가 필요 없다. 어떤 단계를 재시도하는가는
[`llm-scheduling-agent.md` §5.4](llm-scheduling-agent.md#54-재시도와-타임아웃) 가 정한다.

---

## 4. 결과 3분류 — 중단은 실패가 아니다

스텝 안에서 인터럽트를 맞으면 그것은 **평범한 스텝 실패로 도착한다** — 종료된 도구도 다른 도구와 똑같이 error 를 돌려주기
때문이다. 실행기는 신호를 실패보다 **먼저** 읽는다. 그러지 않으면 멈춰 세운 실행이 고장 난 실행으로 기록되고, 아무 잘못도 없는
태스크에 결함이 붙는다.

| | 실행 결과 | 이력 status | 이벤트 |
|---|---|---|---|
| 정상 | `isSuccess()` | `SUCCESS` | `TaskCompletedEvent` |
| 스텝 고장 | `isSuccess()==false` | `FAILURE` / `PARTIAL` | `TaskFailedEvent` |
| 중단 | `isCancelled()` + `InterruptReason` | `CANCELLED` | `TaskInterruptedEvent` |

- **`CANCELLED` 를 `PARTIAL` 과 합치지 않는다.** 둘 다 마지막 스텝에 못 미쳐 끝나지만 태스크에 대해 무언가를 말하는 것은 한쪽뿐이다.
  합치면 누가 멈춘 태스크가 자기 이력에서 간헐적으로 실패하는 태스크와 똑같이 읽힌다.
- **`TaskInterruptedEvent` 를 `TaskCancelledEvent` 와 나눈다.** 후자는 *스케줄이 사라졌다*, 전자는 *실행 하나가 멈췄다* 이고, 중단만
  했을 때 스케줄은 그대로 남는다.

halt 를 포함한 이력 상태 전체와 판정 순서는 [`llm-scheduling-agent.md` §5.5](llm-scheduling-agent.md#55-이력-상태), 이벤트 전체는
[§5.6](llm-scheduling-agent.md#56-이벤트) 이 정본이다.

---

## 5. 취소 뒤 되쓰기 방지

실행을 멈추는 것만으로는 충분하지 않다. `ScheduledTaskManager.executeTask` 는 발화 시점에 태스크를 읽고 실행이 끝나면 되쓰므로, 그
사이에 `cancel` 이 지운 레코드를 되살릴 수 있다. 남는 것은 낡은 행보다 나쁘다 — unschedule 되어 다시는 발화하지 않는데 목록에는
보이고 id 로도 찾히며, 작업 수 할당량은 이미 환급된 유령 태스크다. 취소가 실행을 끊으므로 이 경합은 드문 경우가 아니라 정상 경로다.

`cancel` 안의 순서로는 막지 못한다. 실행의 쓰기는 **언제나 나중**이기 때문이다. 그래서 보장은 반대편, 쓰기 쪽에 둔다 — **발화가
하는 저장소 쓰기는 모두 조건부다.**

| 쓰기 | 메서드 | 지워진 태스크에 대해 |
|------|--------|-------------------|
| 끝난 실행의 기록 | `ScheduledTaskRepository.recordExecution(taskId, executedAt)` | 아무것도 쓰지 않고 `false` |
| 해석할 수 없는 런타임으로 태스크를 끔 | `ScheduledTaskRepository.updateIfPresent(task)` | 아무것도 쓰지 않고 `false` |

- **검사와 쓰기는 원자적이어야 한다.** 두 메서드의 계약이고, `default` 를 주지 않는 이유다. `findById` 뒤 `save` 는 같은 창을 좁힐 뿐
  닫지 않는다. 영속 백엔드는 조건부 갱신·compare-and-set·트랜잭션 가운데 무엇으로 원자성을 얻을지 스스로 정해야 한다.
- **끝난 실행은 태스크 전체가 아니라 자기 값 하나만 쓴다.** 실행이 들고 있는 것은 발화 시점에 읽은 사본이다. 그것을 통째로 되쓰면
  실행 중에 소유자가 끈 태스크가 켜진 채 저장되어 다음 기동 때 다시 스케줄되고, 새 빌드가 쓴 필드를 그것을 모르는 옛 빌드가
  지운다.
- **이력 쪽에는 창이 남는다.** 두 저장소에 공유 트랜잭션이 없으므로, 게이트를 통과한 직후 `cancel` 이 들어오면 존재하지 않는 태스크의
  이력 행이 남는다. 그 행은 도달 불가능하고(`getHistory` 는 `getById` 로 인가하므로 태스크가 없으면 던진다) 틀린 답이 아니라
  누수이므로, 쓰기 직후 태스크가 사라졌으면 쓸어낸다.

저장소 인터페이스의 자리와 기본 구현은 [`llm-scheduling-agent.md` §2.2](llm-scheduling-agent.md#22-패키지-배치) ·
[§7.2](llm-scheduling-agent.md#72-빌더-기본값) 에 있다.

---

## 6. shutdown 도 취소다

`RoutineExecutor.shutdown()` 은 풀을 재우기 **전에** 진행 중인 실행을 전부 `SYSTEM_SHUTDOWN` 으로 trip 한다. 멈추라는 말을 듣지 못한
스텝은 멈출 수 없으므로, 그러지 않으면 유예 시간은 그냥 흘러가고 매번 `shutdownNow()` 로 떨어진다. 유예는 30초다 — trip 한 뒤
timeout 풀에 `shutdown()` 을 걸고 30초 동안 `awaitTermination` 으로 기다리며, 그 안에 비지 않거나 기다리던 스레드가
인터럽트되면 `shutdownNow()` 로 남은 워커를 인터럽트한다. trip 덕분에 협조적 스텝과 종료할 수 있는 스텝은 대개 그 30초를 쓰지
않고 바로 빠진다.

---

## 7. 노드를 넘는 중단 — `ScheduledTaskInterruptBus`

in-flight 레지스트리는 그 인스턴스의 것이다. 그래서 `RoutineExecutor.interrupt` 는 같은 JVM 의 실행만 끊을 수 있는데, 스케일아웃에서
사용자가 취소를 넣는 노드는 대개 cron 이 발화한 노드가 아니다. 그대로 두면 저쪽 실행은 방금 삭제된 태스크를 위해 남은 스텝을
끝까지 돌린다.

분산되는 것은 신호가 아니라 사건이라는 원칙([`interrupt.md` §11](../agent-execution/interrupt.md#11-멀티-노드-전파))에 따라, SPI 는 coordinator 가
아니라 `(ScheduledTaskId, InterruptReason)`(회차가 있으면 회차 키까지)을 나른다.

**세션 버스를 재사용하지 않는다.** 조인 키가 `SessionId` 가 아니라 `ScheduledTaskId` 이고, 예약 루틴은 세션 없는 실행이므로(정체성은
`ExecutionId` — [glossary › 실행 단위](../../overview/glossary.md#실행-단위)) `SessionSignalBus` 를 쓰려면 세션이 아닌 것을 가리키는
`SessionId` 를 발급해야 하기 때문이다. 대신 `ScheduledExecutionGuard` 의 모양을 따른다 — 같은 패키지의 인터페이스, in-memory 구현,
분산 전환은 리팩토링이 아니라 구현체 교체.

| 구현 | 도달 범위 |
|------|----------|
| `ScheduledTaskInterruptBus.LOCAL_ONLY` (기본값) | 이 노드. 단일 노드에서는 로컬 `interrupt` 가 이미 전부이므로 팬아웃할 곳이 없다 |
| `InMemoryScheduledTaskInterruptBus` | 이 JVM. 한 프로세스 안의 엔진 여럿, 그리고 브로커 없이 계약을 테스트하는 자리 |
| `MongoScheduledTaskInterruptBus` (`aimon-session-mongodb`) | 클러스터. capped 컬렉션 `scheduled_task_interrupts` 위의 change stream — `MongoSessionSignalBus` 와 같은 기제 |
| 그 밖의 분산 구현 | 클러스터. 애플리케이션이 빈·스펙으로 주입한다. 계약은 `aimon-scheduling-testkit` 의 `AbstractScheduledTaskInterruptBusContractTest` |

**발행은 매니저가, 구독은 엔진이 한다.** `RoutineExecutor` 가 스스로 발행까지 하면 그것의 `shutdown()` 이 이 노드의 실행을 전부
끊으면서 그 사실을 클러스터에 방송해, 종료하지도 않는 노드의 실행을 멈추게 된다. 그래서 `SchedulingEngine` 이 구독자이고(`close()`
가 구독을 먼저 반납한다), 발행은 사용자 의사가 있는 자리 — `ScheduledTaskManager.cancel` / `.interrupt` — 에서만 일어난다.

**전달은 at-least-once 이고, 계약은 발행한 노드로의 에코를 허용한다.** 핸들러가 하는 일은 이미 trip 된 신호를 다시 trip 하는
것뿐이라 무해하다. in-memory 구현은 에코를 걸러 내지 않는다 — 걸러 내면 그 구현이 실제 브로커보다 관대해져 계약 테스트가 덜
정직해진다. 에코를 걸러 내는 것은 구현의 선택이다(`MongoScheduledTaskInterruptBus` 는 발행 노드를 걸러 낸다, §8.4).

**`ScheduledTaskManager.interrupt` 의 `false` 는 "이 노드에는 돌고 있는 것이 없었다" 를 뜻하지 "아무것도 안 멈췄다" 를 뜻하지
않는다.** 팬아웃에는 돌려받을 답이 없기 때문이다.

**발행이 실패해도 취소는 취소다.** 분산 구현에서 발행은 원격 I/O 이므로 던질 수 있다. 그때 이미 일어난 것들 — 로컬 실행 trip,
unschedule, 곧 이어질 삭제 — 을 되돌리지 않는다. 브로커가 안 닿는다고 취소를 거부하는 것이 더 이상하기 때문이며, 대가는 저쪽 실행이
**늦게** 멈추는 것뿐이다. 그 늦은 정지도 §5 의 조건부 쓰기가 노드와 무관하게 걸리므로 유령을 남기지 않는다.

---

## 8. 회차 하나만 끊기 — occurrence key 와 묘비

태스크 단위 `interrupt` 는 **지금 그 태스크로 돌고 있는 것**을 끊는다. 소유자가 꼬인 실행을 멈출 때나 노드가 내려갈 때는 그게
맞다. 하지만 호스트가 큐에 넣었던 발화 하나를 취소할 때는 틀린다 — 취소가 (버스를 건너) 도착했을 때 그 회차는 이미 끝났고 다음
회차가 돌고 있을 수 있으며, 태스크 단위 요청은 그 다음 회차를 끊는다. 턴 쪽에서 `SessionRouter.interrupt(SessionId, TurnId,
InterruptReason)` 가 막는 것과 같은 실패다.

### 8.1 키와 진입점

```java
public RoutineResult execute(ScheduledTask task, ScheduledFire fire);
public boolean interrupt(ScheduledTaskId taskId, String occurrenceKey, InterruptReason reason);
```

`ScheduledFire` 가 slot 을 가지면 그 실행은 `getOccurrenceKey()`(`<taskId>@<slot epoch ms>`) 아래에 등록되고, 키를 받는 `interrupt`
는 키가 같은 실행만 trip 한다. 어긋나면 아무것도 하지 않고 `false` 다. slot 이 없는 실행 — `execute(ScheduledTask)` 나
`ScheduledFire.untimed` 로 시작한 것 — 은 키가 없으므로 태스크 단위 형태로만 닿는다. slot 과 회차 키의 정의는
[`llm-scheduling-agent.md` §6.1](llm-scheduling-agent.md#61-taskid-로는-겹침만-막는다--scheduledfire) 이 정본이다.

**진입점은 하나다.** 매니저는 `executeFire` 에서 키가 있든 untimed 든 **모든** 발화를 두 인자 `execute(task, fire)` 로 넘긴다. 한 인자
`execute(ScheduledTask)` 는 untimed 발화로 두 인자 형태에 위임하며 직접 호출(수동 실행)용으로만 남는다. 진입점을 둘로 나누지 않는
이유는, 한 인자 형태만 재정의한 하위 클래스나 테스트 대역이 cron 발화는 못 보고 untimed 발화만 보게 되기 때문이다. 스케줄된 실행을
관찰하거나 바꾸려면 두 인자 형태를 재정의한다. `fire` 는 null 일 수 없다 — slot 이 없다는 뜻은 `ScheduledFire.untimed` 하나로만 쓴다.

### 8.2 실행보다 먼저 온 취소 — 묘비(tombstone)

호스트가 큐에 넣은 발화를 취소할 때는 취소가 발화보다 빠를 수 있다. 발화가 받아들여진 뒤 실행이 레지스트리에 등록되기 전(guard
왕복, 태스크 로드)에 키 있는 `interrupt` 가 도착하면 맞는 실행이 없다. 그래서 아무것도 맞지 않으면 정확히 `(taskId, occurrenceKey)`
하나에 대한 묘비를 노드 로컬로 남긴다.

- 그 회차의 실행이 `OCCURRENCE_TOMBSTONE_TTL`(10분) 안에 등록되면 등록 직후 그 요청의 reason 으로 coordinator 를 trip 하므로, 첫 단계
  전에 취소로 끝난다. 묘비는 처음 등록한 실행이 소비한다.
- 레지스트리 스캔과 묘비 기록, 실행 등록과 묘비 소비는 같은 락 안에서 일어나므로 경합하는 쪽 중 나중 것이 앞의 것을 반드시 본다.
- 묘비는 회차 하나를 가리키므로 다른 회차나 slot 없는 실행에는 닿지 않는다 — "늦은 취소가 다음 회차를 끊지 않는다" 는 보장은
  그대로다.
- 크기는 `OCCURRENCE_TOMBSTONE_LIMIT`(1024)로 묶여 가장 오래된 것부터 밀려난다. 다른 노드에서 돈 회차나 이미 끝난 회차의 묘비는 그냥
  만료된다.
- 끝난 뒤에 도착한 취소의 묘비가 남아 있는 동안 같은 회차가 이 노드에서 다시 실행되면(스케줄러가 이미 준 발화를 다시 주는 경우) 그
  반복은 취소된다. 취소가 가리킨 것이 바로 그 회차이기 때문이다.
- `interrupt` 의 반환값은 여전히 "지금 여기서 신호를 보냈는가" 이므로 묘비만 남긴 호출은 `false` 다.

### 8.3 빈 키는 받지 않는다

`RoutineExecutor.interrupt`, `ScheduledTaskManager.interrupt`, 두 버스의 키 있는 `publish` 가 모두 빈(blank) 키에
`IllegalArgumentException` 을 던진다. 로컬에서는 어떤 실행과도 맞지 않아 아무것도 멈추지 않지만, 버스를 건넌 쪽은 쓸 수 있는 키가
없는 요청을 태스크 단위로 읽으므로 같은 호출이 원격에서는 태스크의 모든 실행을 멈추게 되기 때문이다. 코덱이 외부 문서의 빈 키를
태스크 단위로 읽는 관대한 디코딩은 그대로 둔다.

### 8.4 버스를 건너는 회차 키

노드를 건너는 쪽은 `ScheduledTaskManager.interrupt(taskId, occurrenceKey, principal)` 이 로컬 trip 뒤에
`ScheduledTaskInterruptBus.publish(taskId, occurrenceKey, reason)` 으로 방송하고, 엔진의 구독자는
`InterruptListener.onInterruptRequested(taskId, occurrenceKey, reason)` 로 받아 같은 메서드로 넘긴다.

**두 메서드 모두 default 가 태스크 단위로 넓힌다.** 키를 실을 자리가 없는 옛 버스와 람다로 쓴 옛 리스너가 그대로 돌게 하려는
것이며, 정지를 버리는 것보다 넓게 멈추는 쪽을 고른다. 버스 계약 테스트는 키가 그대로 건너야 통과하므로, 분산 구현이 default 에
기대면 거기서 실패한다.

default 로 넓히는 버스가 자기가 발행한 요청을 자기에게도 전달한다면(구독 계약상 허용된다 — `MongoScheduledTaskInterruptBus` 는 발행
노드를 걸러 내지만 커스텀 버스는 아닐 수 있다) 로컬 쪽도 정확하지 않다. 매니저가 직접 하는 로컬 trip 은 정확하지만, 뒤따라 돌아온
태스크 단위 요청이 이 노드에서 다음 회차의 실행까지 멈출 수 있다. 요청 전체가 정확한 것은 키를 싣는 버스뿐이다.

`MongoScheduledTaskInterruptBus` 는 키를 문서의 선택 필드 `occurrenceKey` 에 싣는다. 그 필드가 없는 문서(옛 노드가 발행한 것)는
태스크 단위로 읽히고, 옛 노드는 새 문서의 그 필드를 무시하고 태스크 단위로 멈춘다 — 롤링 업그레이드 동안 넓어질 수는 있어도
사라지지는 않는다.

---

## 9. 드레인 — 끊지 않고 기다리기

워커를 내리는 호스트에게 필요한 것은 "지금 몇 개가 돌고 있나" 와 "끝날 때까지 기다리기" 이고, 어느 것도 무언가를 끊어서는 안 된다.

- **`RoutineExecutor.inFlightRunCount()` 와 `awaitIdle(Duration)` 은 보기만 한다.** 타임아웃이 나도 `false` 를 돌려줄 뿐 실행의
  신호는 건드리지 않는다. 남은 것을 끊을지는 호스트가 정하고, 끊는 길은 여전히 `interrupt` 와 `shutdown()`(§6) 이다.
- **실행은 무슨 일이 있어도 레지스트리에서 빠진다.** 실행 하나가 끝날 때 트레이서가 루트 스팬을 닫다가 던지더라도, 해제와
  coordinator 닫기는 중첩된 `finally` 안에 있다. 그렇지 않으면 진행 중 카운트가 영영 내려가지 않아 `awaitIdle` 이 언제나 타임아웃
  나고 `isRunning` 이 계속 `true` 를 돌려준다.
- **나노초로 나타낼 수 없을 만큼 큰 타임아웃**은 예외 대신 `Long.MAX_VALUE` 로 잘라 그냥 기다린다.

새 발화를 막는 것은 엔진 쪽이다(`SchedulingEngine.pauseFires()`) — 엔진 드레인의 순서와 스케줄러별 동작은
[`llm-scheduling-agent.md` §7.6](llm-scheduling-agent.md#76-노드-드레인--발화를-멈추고-끝나기를-기다린다) 이 정본이다. 버스로 들어오는
취소는 일시정지 중에도 그대로 처리된다.

---

## 10. 설계 결정

| 쟁점 | 결정 | 기각한 대안 |
|------|------|------------|
| 취소가 진행 중인 실행을 어떻게 하나 | 끊는다 — 취소의 일부다(§1) | unschedule + 삭제만: 스텝 중간의 루틴이 삭제된 태스크를 위해 끝까지 돈다 |
| 실행만 끊는 API | `cancel` 과 별개의 `interrupt(taskId, …)`(§1) | 취소만 제공: 한 번의 나쁜 실행 때문에 스케줄까지 지워야 한다 |
| 루틴의 중단 경계 | 스텝 경계 게이트가 정확성의 근거, 스텝 안 중단은 최적화(§2) | 도구 안의 중단에 기댄다: 중단을 모르는 도구에서 멈추지 않는다 |
| 재시도 사이의 대기 | 신호 리스너가 내리는 래치(§3) | `Thread.sleep`: 취소된 실행을 남은 지연만큼 붙잡는다 |
| 중단의 기록 | `CANCELLED` + `TaskInterruptedEvent`, 신호를 실패보다 먼저 읽는다(§4) | `PARTIAL` 과 합침: 멈춘 태스크가 간헐 실패 태스크처럼 읽힌다. `TaskCancelledEvent` 재사용: 스케줄이 사라진 것과 실행 하나가 멈춘 것이 구분되지 않는다 |
| 취소 뒤 되쓰기 | 발화의 모든 쓰기를 원자적 조건부로, 끝난 실행은 자기 값 하나만(§5) | `cancel` 안의 순서로 막는다: 실행의 쓰기가 언제나 나중이다. `findById` 뒤 `save`: 창을 좁힐 뿐 닫지 않는다. 사본 전체를 되쓰기: 실행 중의 비활성화를 되돌린다 |
| shutdown | 풀을 재우기 전에 `SYSTEM_SHUTDOWN` 으로 trip(§6) | 풀만 내린다: 유예가 흘러가고 매번 `shutdownNow()` 로 떨어진다 |
| 노드를 넘는 취소의 SPI | 전용 `ScheduledTaskInterruptBus`(§7) | `SessionSignalBus` 재사용: 세션이 아닌 것에 `SessionId` 를 발급해야 한다 |
| 발행 주체 | 매니저(사용자 의사가 있는 자리), 구독은 엔진(§7) | 실행기가 발행: 자기 `shutdown()` 이 다른 노드의 실행까지 멈춘다 |
| 에코 | 계약이 허용한다. 걸러 낼지는 구현이 정한다(§7) | 계약이 에코 없음을 요구: in-memory 구현이 실제 브로커보다 관대해진다 |
| 발행 실패 | 취소를 되돌리지 않는다(§7) | 거부한다: 브로커 장애가 취소를 막는다 |
| 회차 지정 | occurrence key 와 묘비(§8) | 태스크 단위만: 늦게 온 취소가 다음 회차를 끊는다 |
| 실행 진입점 | 두 인자 `execute(task, fire)` 하나(§8.1) | 진입점 둘: 한쪽만 재정의한 하위 클래스가 cron 발화를 못 본다 |
| 빈 키 | 거부한다(§8.3) | 받아들인다: 로컬에서는 아무것도 안 멈추고 원격에서는 태스크 전체가 멈춘다 |
| 키를 모르는 옛 버스·리스너 | default 가 태스크 단위로 넓힌다(§8.4) | 키 없는 요청을 버린다: 멈춰야 할 실행이 돈다 |
| 드레인 | 보기만 하고 끊지 않는다(§9) | 타임아웃에 끊는다: 끊을지는 호스트의 결정이다 |

---

## 11. 하지 말 것

- **`RoutineExecutor` 가 버스에 발행하게 하지 말 것.** 종료하지 않는 노드의 실행이 멈춘다(§7).
- **발화가 하는 저장소 쓰기를 무조건 `save` 로 하지 말 것.** 취소된 태스크가 되살아난다(§5).
- **`updateIfPresent` · `recordExecution` 을 `findById` + `save` 로 구현하지 말 것**(§5).
- **재시도 대기를 `Thread.sleep` 으로 하지 말 것**(§3).
- **스텝 실패를 신호보다 먼저 읽지 말 것.** 멈춘 실행이 고장으로 기록된다(§4).
- **빈 회차 키를 보내지 말 것**(§8.3).
- **분산 버스에서 키 있는 `publish` 를 default 에 맡기지 말 것.** 회차 지정이 태스크 단위로 넓어진다(§8.4).
- **드레인 메서드가 실행의 신호를 건드리게 하지 말 것**(§9).
- **브로커 실패를 이유로 취소를 되돌리지 말 것**(§7).

---

## 12. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|------|------|------|
| 실행별 coordinator · 레지스트리 · 스텝 게이트 · 백오프 · 묘비 · 드레인 · shutdown | `RoutineExecutor` | `scheduling/` |
| 취소 · 중단 API, 조건부 되쓰기, 이력 쓸기, 버스 발행 | `ScheduledTaskManager` | `scheduling/` |
| 구독 · 일시정지 | `SchedulingEngine` | `scheduling/` |
| 노드 간 버스 | `ScheduledTaskInterruptBus`, `InMemoryScheduledTaskInterruptBus` | `scheduling/` |
| 클러스터 버스 | `MongoScheduledTaskInterruptBus` | `modules/aimon-session-mongodb/src/main/java/at/aimon/session/mongodb/` |
| 버스 계약 | `AbstractScheduledTaskInterruptBusContractTest` | `modules/aimon-scheduling-testkit/src/main/java/at/aimon/scheduling/testkit/` |
| 조건부 쓰기 계약 | `ScheduledTaskRepository` | `scheduling/repository/` |
| 발화와 회차 키 | `ScheduledFire` | `scheduling/scheduler/` |
| 신호 · 사유 | `InterruptCoordinator`, `InterruptReason` | `agent/interrupt/` |
