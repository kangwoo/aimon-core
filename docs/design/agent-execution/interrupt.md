# 중단 — 진행 중인 실행을 안전하게 끊기

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core`(`agent.interrupt` · `agent.tool` · `agent.impl.orca` · `agent.session` · `toolinvocation` · `hook.execution`),
> `aimon-cli`(REPL Ctrl+C), `aimon-session-routing`(노드 간 중단 전달, 소유권 상실 중단).
> 남은 일은 [`backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) 가 정본이다.
> 사용 가이드: [`interruptible-tools-guide.md`](../../features/agent-execution/interruptible-tools-guide.md),
> [`command-queue-guide.md`](../../features/agent-execution/command-queue-guide.md)
> 관련 문서: [`hook-cancellation.md`](../hook/hook-cancellation.md)(훅 × 인터럽트 규칙),
> [`llm/cancellation.md`](../llm/cancellation.md)(LLM 전송 abort),
> [`scheduling/routine-interrupt.md`](../scheduling/routine-interrupt.md)(예약 루틴의 중단),
> [`session/turn-retry.md`](../session/turn-retry.md)(중단된 턴의 재시도),
> [`session/routing.md`](../session/routing.md)(노드 간 신호), [`compaction.md`](compaction.md)(요약 호출의 취소)

사용자가 Ctrl+C 를 누르거나 `QueuedInputPriority.NOW` 로 메시지를 넣었을 때, 또는 노드가 세션 소유권을 잃었을 때 **진행 중인
실행을 안전하게 끊고 다음 턴으로 깔끔히 넘어가는** 메커니즘이다.

`Tool.execute()` 는 동기·블로킹이고 `CompletableFuture.cancel(true)` 는 future 만 완료시킬 뿐 실행 스레드를 멈추지 않는다. 그래서
중단 신호를 루프와 도구까지 전달하는 채널을 따로 둔다.

---

## 1. 원칙과 계층

- **기존 Tool API 를 깨지 않는다.** `Tool.execute(ToolInput, ToolContext)` 시그니처를 유지한다. 신호는 `ToolContext` 로 전달하고,
  도구의 선언은 `default` 메서드로만 더한다.
- **협조가 먼저, 선점은 나중.** 기본은 도구가 신호를 읽는 협조적 방식이고, `Thread.interrupt()` 는 명시적으로 opt-in 한 도구에만
  전달한다. 도구가 보증하지 않은 상태에서 스레드를 강제로 끊으면 반만 닫힌 파일과 소켓이 남는다.
- **iteration 경계에서는 반드시 멈춘다.** 도구가 비중단형이어도 iteration 경계에서는 중단된다. 도구 안의 중단은 "빠르게 반환" 을
  위한 최적화이지 정확성의 근거가 아니다.
- **역할을 나눈다.** 호스트는 *언제* 중단할지, coordinator 는 *어떻게* 전파할지 정하고, 도구는 *자기 자원만* 정리한다.

### 1.1 계층

| 계층 | 메커니즘 | 책임 |
|------|----------|------|
| Host (CLI/SDK) | SIGINT, UI 버튼, NOW enqueue, 스케줄 태스크 취소 | "중단하고 싶다" 는 의사 표시만 |
| Live session | `LiveSession.interrupt(...)` | 활성 턴 식별, coordinator 위임 |
| Routine run | `RoutineExecutor.interrupt(taskId[, occurrenceKey], reason)` | 그 태스크(또는 그 회차)의 진행 중인 실행 식별, coordinator 위임 — [`routine-interrupt.md`](../scheduling/routine-interrupt.md) |
| Coordinator | `CancellationSignal` + 전파 전략 | 신호 trip, terminator 실행 |
| Execution | Orca 루프 · `ToolContext` 주입 | 경계마다 신호 검사, `ToolResult` 생성 |
| Tool | `InterruptBehavior` 선언 + 신호 읽기 | 자기 자원을 안전하게 정리 |

종단 이벤트는 coordinator 가 아니라 실행기와 라우팅 계층이 낸다(§10).

---

## 2. `InterruptBehavior` — 도구의 선언

각 도구가 "외부 중단 신호에 어떻게 반응할 수 있는가" 를 선언한다(`at.aimon.core.agent.interrupt`).

| 값 | 뜻 | coordinator 의 전파 행동 |
|----|-----|----------------------|
| `NON_INTERRUPTIBLE` | 무시한다 (빠르게 끝나거나 원자적) | 전파 없음. iteration 경계까지 기다린다 |
| `COOPERATIVE` | 신호를 폴링하고 체크포인트마다 반환한다 | 신호 trip 만 (도구가 스스로 읽는다) |
| `THREAD_INTERRUPT` | `Thread.interrupt()` 에 안전하다 | 도구 스레드의 `Thread.interrupt()` 호출 |
| `EXTERNALLY_TERMINATED` | `Terminator` 핸들을 등록한다 | 등록된 `Terminator` 실행 |

`Tool` 의 `default` 메서드이고 기본값은 `NON_INTERRUPTIBLE` 이다. 기존 구현은 한 줄도 고치지 않고 iteration 경계 중단을 얻는다.
대부분의 도구(Read · Write · Edit · Todo …)는 수 ms 안에 끝나므로 그것으로 충분하다. `THREAD_INTERRUPT` 는 **도구가 안전하다고
명시적으로 선언할 때만** 켜진다.

대표 사례는 셋이다 — `GrepTool` 은 라인 스트리밍 루프에 체크포인트를 둔 `COOPERATIVE`, `BashTool` 은 내부 `Future.cancel(true)` 로
자식 프로세스를 끝내는 `THREAD_INTERRUPT`, `TaskTool` 은 자식 실행에 cascade 하는 `EXTERNALLY_TERMINATED`(자식에서는
`PARENT_CANCELLED` 로 재분류)다. 전체 선언 목록은 코드가 정본이다.

---

## 3. `CancellationSignal` 과 `InterruptReason`

`CancellationSignal` 은 한 실행에 바인딩된 1회용 "플래그 + 사유" 핸들이다.

```java
public interface CancellationSignal {
    boolean isCancelled();
    Optional<InterruptReason> getReason();
    default Optional<String> getDetail();    // 자유 텍스트 설명 — §3.1
    void checkpoint();                       // 취소되었으면 CancelledExecutionException
    Registration onCancel(Runnable listener);
}
```

**신호를 trip 하는 메서드는 이 인터페이스에 없다.** `DefaultCancellationSignal.trip(InterruptReason)` 은 package-private 이고, trip
권한을 가진 유일한 주체는 같은 패키지의 `InterruptCoordinator` 다. 도구에게 건네지는 것은 읽기 전용 절반뿐이라, 도구 결과나 LLM
응답이 세션을 중단시킬 경로가 구조적으로 없다.

불변 규칙은 셋이다.

- **단방향이다.** 한 번 trip 되면 다시 clear 되지 않는다.
- **`onCancel` 등록은 thread-safe 이고, 이미 trip 된 상태면 즉시 실행한다.**
- **등록은 `Registration` 을 돌려주어 해제할 수 있다.** 긴 신호에 짧은 리스너를 걸었다가 거두는 쪽이 이 해제를 쓴다.

`InterruptReason` 은 열 값이다. 앞의 여섯은 단일 노드 시나리오, 다음 셋은 멀티 노드 라우팅이 세션 소유권을 잃었을 때(§11),
마지막 하나는 **읽는 쪽의 값**이다.

```text
USER_SIGINT · NOW_PRIORITY_INPUT · BUDGET_EXCEEDED · PARENT_CANCELLED · TASK_CANCELLED · SYSTEM_SHUTDOWN
LEASE_LOST · SESSION_RELEASED · HOLDER_LOST
UNKNOWN
```

- **`UNKNOWN` 으로 실행을 끊는 코드는 없다.** 다른 노드가 쓴 사유를 읽는 디코더(`InterruptReason.fromWireName`)가 이 빌드가 모르는
  이름을 만났을 때 돌려주는 값이다. 롤링 업그레이드에서 새 노드가 보낸 종료 프레임 `InterruptedAt` 을 버리지 않기 위해서다. 다른
  값으로 대신 읽으면 일어나지 않은 일을 말하게 된다.
- **`TASK_CANCELLED` 는 `PARENT_CANCELLED` 와 다르다.** 스케줄 태스크의 소유자가 그 태스크를 취소했거나 진행 중인 실행만 끊었을
  때다. 상위 실행에서 흘러 내려온 cascade 가 아니라 요청이 그 실행 자신의 태스크를 지목했다.

사유는 로그·이벤트·`ToolResult.error()` 메시지에 표준 문자열로 실려 관측을 일관되게 만든다.

### 3.1 설명(detail) — 사유는 분류하고 설명은 말한다

`InterruptReason` 은 열 칸짜리 분류라 "왜" 를 말하지 못한다. 사용자가 Ctrl+C 를 눌렀다는 것과 *잘못된 디렉터리를 지우려 해서*
눌렀다는 것은 다른 정보다. 그래서 중단 요청에 자유 텍스트 설명을 하나 붙일 수 있다(`requestInterrupt(reason, detail)`,
`LiveSession.interrupt(reason, detail)` 등).

- **관측 전용이다.** 설명은 `CancellationSignal.getDetail()`, 중단된 실행의 로그 한 줄, 종단 이벤트 `ExecutionCompleted`(§10)에만
  닿는다. **모델에는 보내지 않는다** — 다음 턴의 대화 기록에도, 도구 오류 메시지에도 넣지 않는다. 요청자의 텍스트가 프롬프트로
  들어가는 길을 열지 않기 위해서다. 다만 **잠깐 저장은 된다.** 노드 간 `INTERRUPT` 신호에 실리므로 Postgres 백엔드는
  `conversation_signal` 테이블에(나이로만 지운다), Mongo 백엔드는 capped 컬렉션과 oplog 에 사본이 남는다. 비밀을 설명에 넣지 않는다.
- **사유와 한 묶음이다.** 첫 trip 이 사유와 함께 설명도 정한다. 뒤에 온 요청의 설명은 그 요청과 함께 버려진다. coordinator 가
  공개되기 전에 라이브 세션이 쥐고 있던 중단(§6.1)도 사유와 설명을 한 값으로 쥔다 — 나중 요청의 설명이 먼저 온 사유에 붙지 않게.
- **신뢰하지 않는 입력으로 다룬다.** 설명은 노드를 넘고 로그에 찍히므로 `InterruptDetail.normalize` 를 거친다. 제어 문자(줄바꿈
  포함)와 유니코드 줄·문단 구분자, 보이지 않는 서식 문자(bidi override, zero-width, U+E0000 태그 문자), 짝 없는 서로게이트는
  공백으로 바꿔 로그 줄 위조와 뒤집어 보이기를 막는다. 앞뒤 공백을 걷고, 비면 없는 것으로 보고, `MAX_LENGTH`(500)를 넘으면
  서로게이트 쌍을 가르지 않고 잘라 말줄임표로 끝낸다. 설명이 들어오는 자리마다 적용한다 — coordinator, 공개 전 중단을 쥔 라이브
  세션, 버스로 보낼 때와 받을 때의 라우터, `ExecutionCompleted` 빌더(피어가 릴레이한 프레임도 이 빌더로 다시 만들어진다). 정규화는
  멱등이라 겹쳐 적용해도 값이 바뀌지 않는다. **거절하지 않는다** — 설명이 이상하다는 이유로 중단이 실패하면 멈춰야 할 실행이
  계속 돈다.
- **옛 구현을 깨지 않는다.** 설명을 받는 메서드는 모두 `default` 이고, 기본은 설명을 버리고 기존 메서드에 위임한다. 이 저장소 밖의
  `InterruptCoordinator` · `LiveSession` · `SessionRouter` 구현은 그대로 중단을 처리하고 설명만 비운다. 대가는 **위임하는 래퍼**다 —
  새 오버로드를 재정의하지 않은 데코레이터는 설명을 조용히 떨어뜨린다. 저장소 안의 래퍼(`LeasedLiveSession`)는 재정의했고, 밖의
  래퍼를 위해 각 기본 메서드의 javadoc 이 재정의를 요구한다.

스케줄 취소와 cascade(`PARENT_CANCELLED`) 경로는 설명을 싣지 않는다 — backlog 의 10번 항목이다.

---

## 4. `InterruptCoordinator` — 실행당 하나

```java
public interface InterruptCoordinator {
    CancellationSignal getSignal();
    void requestInterrupt(InterruptReason reason);
    default void requestInterrupt(InterruptReason reason, String detail); // §3.1
    TerminatorRegistrar newTerminatorRegistrar();
    void close();
    default boolean isClosed();   // close() 가 돌았는가 = requestInterrupt 가 이제 no-op 인가
}
```

`requestInterrupt` 는 신호를 trip 하고 등록된 terminator 를 실행한다. 이벤트는 내지 않는다(§10). **멱등**이다 — 이미 trip 된
상태면 전파를 건너뛴다. terminator 호출은 try/catch 로 감싸 로그만 남긴다. 콜백이 던진 예외가 coordinator 자원을 누수시키지 않는다.

**수명은 실행 1회다.** 세션 단위로 끌어올리지 않는 이유는 "이전 턴의 SIGINT 가 새 턴에 영향" 같은 결함을 원천 차단하기
위해서다. 세션은 직렬 실행을 가정하므로 한 번에 한 턴만 중단하면 된다. 실행의 스코프가 인스턴스를 보관하고 종료 시 `close()` 로
리스너와 terminator 를 정리한다.

`newTerminatorRegistrar()` 는 도구 호출마다 새 registrar 를 발급한다. `register` · `unregister` · `close()` 만 있는 좁은 인터페이스라,
도구는 자기 terminator 를 등록할 수 있을 뿐 coordinator 자체(trip 권한)에는 닿지 못한다. 이미 trip 된 coordinator 에 등록하면
terminator 가 등록 스레드에서 즉시 발화한다.

---

## 5. 신호 전달 채널 — `ToolContext` 타입 키

`Tool.execute` 에 파라미터를 더하면 프레임워크·SDK·외부 플러그인까지 번지는 breaking change 다. `ToolContext` 는 이미 실행 스코프의
read-only dictionary 이므로 여기에 전용 키를 넣는다. **키를 모르는 도구가 그것을 무시하면 `NON_INTERRUPTIBLE` 과 같고**, 그것이
원하는 기본값이다.

키는 문자열이 아니라 타입 키(`ToolContextKey<T>`)다.

```java
// at.aimon.core.agent.tool.InterruptToolKeys
ToolContextKey<CancellationSignal>  CANCELLATION_SIGNAL
ToolContextKey<TerminatorRegistrar> TERMINATOR_REGISTRAR
```

- **키는 `agent.tool` 에 산다.** `agent.interrupt` 에 두면 `tool → interrupt` 방향이 생겨 패키지가 순환한다.
- **접근은 `InterruptAccess` 헬퍼로 한다.** `signalOf(context)` 는 키가 없으면 `NoopCancellationSignal` 을 돌려주므로 도구 코드에
  null 검사가 필요 없고, `registrarOf(context)` 는 `Optional` 이다.
- **`ToolContext.getCancellationSignal()` 같은 전용 getter 는 두지 않는다.** 범용 컨테이너에 특정 도메인 API 를 섞지 않는다.

도구 쪽 폴링 루프의 모양은 [사용 가이드](../../features/agent-execution/interruptible-tools-guide.md) 에 있다.

---

## 6. 호스트에서 신호까지

### 6.1 `LiveSession.interrupt` — 두 형태

```java
default void interrupt(InterruptReason reason);                // 활성 턴이 무엇이든
default void interrupt(TurnId turnId, InterruptReason reason); // 지정한 턴에만
// 두 형태 모두 설명을 받는 오버로드가 있다 — interrupt(reason, detail), interrupt(turnId, reason, detail) (§3.1)
```

둘 다 `default` no-op 이라 중단을 지원하지 않는 구현은 그대로 둔다. `DefaultLiveSession` 은 활성 턴의 coordinator 참조를 읽어
`requestInterrupt` 를 부르고, 곧바로 넘기지 못하는 경우를 한 자리에서 정한다.

| 상황 | 처리 |
|---|---|
| 활성 턴이 없다(idle), 주소 지정 형태의 `turnId` 가 활성 턴과 다르다 | debug 로그 + no-op |
| 턴은 있는데 coordinator 가 아직 공개되지 않았다(프롬프트 렌더, 전사 열기) | 사유를 턴에 쥐고 있다가 공개되는 순간 넘긴다 |
| 슬래시 명령 턴의 `onStart` 뒤, 턴의 coordinator 가 닫힌 뒤 | 닫힌 coordinator 의 no-op |

쥐고 있는 사유는 먼저 온 것이 이기고, 쥔 뒤에 coordinator 를 다시 읽어 공개와의 경합을 닫는다. 양쪽이 모두 넘겨도
`requestInterrupt` 가 멱등이라 괜찮다. coordinator 를 공개하기 전에 턴이 던지면 사유는 턴과 함께 사라진다. NOW 입력과 `close()` 도
이 길을 쓴다.

턴은 coordinator 를 `onStart` 체인 직전에 공개한다. 그 전에 온 인터럽트는 라이브 세션이 쥐고 있다가 공개 순간 넘기므로, 그 턴은
훅을 돌리지 않고 `INTERRUPTED` 로 끝난다. 훅 쪽 결과 규칙은 [`hook-cancellation.md` §4.1](../hook/hook-cancellation.md#41-메인-턴)
이다. 도는 슬래시 명령을 인터럽트하는 일은 [EE-93](../../backlog/execution-environment-open-items.md) 이 추적한다.

**`LiveSessionStatus#isInterruptible` 은 `coordinator != null && !coordinator.isClosed()` 다.** 값이 노드를 넘어가므로
(`StatusSnapshotPayload`) 사실만 말해야 한다. 슬래시 명령 턴의 `onStart` 뒤와 영속 중인 턴에서는 `false` 다. coordinator 가
공개되기 전에도 `false` 다 — 인터럽트를 쥐고는 있지만 공개하지 않을 수도 있는 실행기를 대신해 약속하지 않고, 잠깐 적게 말하는
쪽을 택한다. 플래그와 달리 `phase` 는 `BudgetTracker` 로 정하므로 `onStart` 중의 턴은 `IDLE` 이면서 interruptible 이다. 플래그를
tracker 에서 끌어오면 인터럽트가 턴을 끝내는 `onStart` 중에 `false` 가 된다. 공개한 핸들을 지우려면 관찰자를 넓혀야 하고, 세션이
입력이 명령인지 묻는 것은 실행기의 지식을 옮기는 일이다. 어느 쪽이든 이 플래그는 관찰값이지 제어 게이트가
아니다([`glossary.md` › Live session 계층](../../overview/glossary.md#live-session-계층-일시적)).

**주소 지정 형태가 필요한 이유는 경합이다.** 사용자가 턴 A 를 끊으려는 사이에 A 가 끝나고 B 가 시작되면 비주소 형태는 엉뚱한
턴을 끊는다. `TurnId` 는 이 확인 하나를 위해 제출 시점에 발급되는 비영속 주소다([`glossary.md` › 실행 단위](../../overview/glossary.md#실행-단위)).

### 6.2 Ctrl+C 는 두 번 두드린다

REPL 의 SIGINT 핸들러는 `session.interrupt(USER_SIGINT)` 를 **먼저** 부르고 그다음 `future.cancel(true)` 를 부른다. 순서가
중요하다. `future.cancel(true)` 만 하면 future 는 즉시 완료로 바뀌지만 도구 스레드는 계속 돌아 사용자 눈에 "아직 CPU 를 쓰고 있음"
으로 보인다. 먼저 신호를 주어 도구가 정리할 시간을 벌고, `future.cancel(true)` 는 REPL 이 `join()` 에서 빠르게 깨어나 다음
프롬프트로 돌아가기 위해 여전히 필요하다.

### 6.3 NOW-tier 큐

`MessageQueueManager` 에 NOW 우선순위 입력이 도착하면 리스너가 세션의 `interrupt(NOW_PRIORITY_INPUT)` 를 부른다. `DefaultLiveSession`
이 자기 리스너를 등록하고 자신의 컨텍스트 id 와 일치할 때만 반응한다. 통지는 동기이므로 입력은 그 순간의 활성 턴을 겨눈다. 아직
준비 중인 턴이면 그 턴이 `INTERRUPTED` 로 끝나고 입력은 큐에 남는다.

지켜야 할 관계는 **"신호는 중단, 메시지는 다음 iteration 주입"** 이다. 인터럽트가 발생해도 큐 엔트리는 drain 시점까지 큐에 남는다
(멱등·crash-safe). drain 자리와 `<system-reminder>` 주입은 루프 뼈대의 일이다([`orca-executor.md`](orca-executor.md)). 이 문서가
정하는 것은 **도구 종료 직후 경계에서는 drain 하지 않고 신호만 확인한다**는 것이다. 도구 실행 *중간*에 대화 기록을 고치면
`tool_use` ↔ `tool_result` 페어링이 깨지기 때문이다. 이것이 중단 응답성(ms)과 대화 일관성을 함께 지키는 자리다.

---

## 7. 루프 게이트

루프는 두 종류의 경계에서 신호를 본다 — **iteration 경계**(시작 직후, tail)와 **도구 배치의 per-tool 게이트**다.

**도구 게이트는 `executeToolUses` 의 for 루프가 아니라 `toolRunner(...)` 가 돌려주는 per-tool 콜백 한 곳에 있다.** 그래야 순차
디스패치 · 병렬 디스패치(`DefaultParallelToolDispatcher`) · 스트리밍 eager/harvest 분할이라는 세 디스패치 형태가 모두 같은 게이트를
상속한다. for 루프에만 두면 병렬·eager 경로가 게이트를 통째로 우회한다.

게이트 범위는 **아직 시작하지 않은** `tool_use` 로 한정한다. "시작" 의 기준은 그 도구를 실행할 스레드다 — 게이트를 평가하는
주체가 콜백 본문이고, 병렬·eager 디스패치에서 그 본문은 풀 워커에서 돌기 때문이다. 그래서 풀에 submit 됐지만 아직 워커가
집어가지 않은 태스크는 스킵된다. 이미 `executeSingleTool` 안으로 들어간 도구는 결과를 유지한다. 버리면 실행 중인 작업이 누수되고
모델이 이미 일으킨 부수효과를 잃는다. 실행 중인 도구를 멈추는 것은 §4 의 terminator 의 책임이지 게이트의 책임이 아니다.

스킵된 도구는 상수 `INTERRUPTED_TOOL_SKIP_MESSAGE`(`"Interrupted — skipped: the turn was cancelled before this tool started"`)를
결과로 받는다. 스킵이든 실행이든 **`tool_use` ↔ `tool_result` 페어링은 항상 보존**된다.

경계 검사는 `signal.isCancelled()` 가 아니라 스레드 플래그까지 함께 소비하는 `isInterrupted(coordinator)` 를 쓴다. 이유가 §8 이다.

---

## 8. 스레드 인터럽트 플래그 위생

`THREAD_INTERRUPT` 전파는 도구 스레드의 인터럽트 플래그를 세운다. 이 플래그는 누군가 `Thread.interrupted()` 로 **읽어서 지우기
전까지** 그 스레드에 남는다. 루프가 `signal.isCancelled()` 만 검사하고 플래그를 소비하지 않으면 두 가지가 깨진다.

**(1) 훅 fail-open 우회 — 보안 성격.** 플래그가 살아 있는 스레드에서 `DefaultHookExecutor` 의 `future.get(timeout)` 은 기다리지 않고
즉시 `InterruptedException` 을 던진다. 그 예외를 `HookExecutionPolicy.onException` 으로 넘기면 `continueOnExceptionButStopOnBlocked()`
가 `HookResult.success()` 로 매핑하므로 **PreTool `BLOCKED` 가 조용히 allow 로 강등**된다. 인터럽트가 도구 권한 차단을 무력화하는
것이다. 두 겹으로 막는다. 플래그 위생이 낡은 플래그를 훅 실행기까지 보내지 않고(§8.1 ~ §8.3), 그래도 도달하면 실행기가 그 대기를
BLOCKED 로 답한다(§8.7).

**(2) 실행 스레드 오염.** 동기 진입점 `execute(OrcaAgentRuntime, OrcaAgentExecutionRequest)` 는 턴 전체를 **호출자 스레드**에서
돌린다. 임베더가 재사용되는 워커에서 턴을 구동하면 오염된 스레드가 다음 작업으로 넘어가 그 작업의 첫 blocking 호출이 즉시 깨진다.
프레임워크 내장 경로는 이 위험에 해당하지 않는다 — `executeAsync` 는 호출마다 단일 스레드 executor 를 만들어 완료 시 shutdown
하고, `RoutineExecutor` 의 공유 풀은 턴이 아니라 개별 `Tool` 을 돌리며, 표준 `ThreadPoolExecutor` 도 다음 태스크 dispatch 전에 낡은
플래그를 지운다. 위험한 것은 임베더의 스레드다.

### 8.1 소비

```java
// at.aimon.core.agent.interrupt.CancellationSignals
static boolean isCancelledOrInterrupted(CancellationSignal signal) {
    boolean threadInterrupted = Thread.interrupted();     // 무조건 평가 = 무조건 소비
    return signal.isCancelled() || threadInterrupted;
}
```

`Thread.interrupted()` 를 **무조건 먼저 평가**하는 것이 요점이다. `signal.isCancelled() || Thread.interrupted()` 로 쓰면 신호가 이미
떠 있을 때 단락 평가에 걸려 플래그가 그대로 남고, 막으려던 (1)이 살아난다.

헬퍼가 `CancellationSignal` 바로 옆에 사는 이유는 의존 방향이다. 메인 ReAct 루프가 `at.aimon.core.subagent.execution` 에 의존할 수는
없다. `SubagentInterrupts` 는 subagent 호출부를 위한 위임 alias 일 뿐 별도 구현이 아니다.

### 8.2 승격

플래그를 읽어 지우는 순간 그 사실은 사라진다. 그래서 `isInterrupted(coordinator)` 는 소비 직후 신호가 아직 안 떠 있으면
`requestInterrupt(PARENT_CANCELLED)` 로 **실행의 신호에 승격**한다. 이후 모든 하위 판단(iteration tail, 결과 빌드,
`CompletionReason.INTERRUPTED`)은 정상적으로 trip 된 신호 하나만 보면 된다. 승격이 없으면 "플래그만 있던 인터럽트" 가 도구를
스킵시켜 놓고 tail 검사에는 보이지 않아, 턴이 어정쩡하게 계속되다 잘못된 `CompletionReason` 으로 끝난다.

**규율: 판단 지점당 1회.** 이 검사가 소비하는 것은 스레드 플래그 절반뿐이고 신호 절반은 sticky 다. 같은 지점에서 두 번 부르면
두 번째는 플래그를 못 본다 — 신호가 아직 안 떠 있었다면 `false` 로 뒤집혀 인터럽트 사실이 사라진다. 그래서 boolean 을 재사용하거나
승격으로 사실을 신호에 보존한다.

### 8.3 스윕과 복원

체크포인트를 지나지 않은 채 플래그가 세팅된 경로가 있다 — 체크포인트가 아예 없는 슬래시 커맨드 flow, 마지막 tail 검사 이후에
도착한 인터럽트, 체크포인트를 통째로 우회하는 예외 종료. 이들이 모두 살아서 도달하는 **스윕 지점이 둘**이다.

1. **`invokeOnStop(...)` 진입 직후.** OnStop 은 턴의 마지막 훅이고, 에러 경로에서는 루프 체크포인트가 한 번도 쓸어내지 못한
   스레드에서 돈다(플래그를 복원하고 던지는 `LlmClient` 는 `handleExecutionError` 로 직행한다). 플래그가 살아 있으면 (1)의
   fail-open 이 훅을 조용히 스킵시킨다. `execute()` 의 `finally` 는 모든 종료 경로가 훅을 이미 부른 **뒤에** 돌므로 이 지점을 대신할
   수 없다.
2. **`execute()` 의 `finally`, `transcriptManager.saveSilently(...)` 직전.** 인터럽트에 민감한 저장소가 방금 끝낸 턴의 저장을
   중단하는 것을 막는다.

**복원(re-arm).** 소비한 플래그를 그냥 삼키는 것은 턴이 그 취소를 **이미 `CompletionReason.INTERRUPTED` 로 보고한 경우에만**
무손실이다 — 결과가 사실을 담아 가므로 오염된 스레드를 돌려줄 이유가 없다. 그 밖의 결과(체크포인트 없는 command flow 의
`COMPLETED`, 에러 종료)에서는 호출자가 이 스레드에 취소를 요청했는데 그 요청을 담아 갈 채널이 없으므로, `saveSilently` **이후**에
`Thread.currentThread().interrupt()` 로 되살려 호출자의 취소 프로토콜을 지킨다. 소비 여부는 실행 스코프의 `interruptConsumed` 래치가
기억한다 — 1번 지점에서 이미 소비했다면 `finally` 에서 플래그를 다시 읽는 것만으로는 알 수 없다.

### 8.4 비소비 읽기를 써야 하는 곳

정체 가드(`StalledIterationGuard`)는 `signal.isCancelled()` 를 **직접** 읽는다. 여기서 소비형 검사를 쓰면 바로 아래 iteration tail
이 플래그를 못 보므로, 스레드 플래그의 평가 지점은 tail 하나로 유지한다. 또 인터럽트된 배치는 스킵된 `tool_use` 가 전부 error
결과라 가드에 진전 없는 반복으로 오인되므로, 취소된 iteration 은 가드가 세지 않는다. 가드 자체는
[`truncation-and-stall.md`](truncation-and-stall.md) 가 정본이다.

### 8.5 병렬 경로의 전제

게이트 콜백은 병렬 디스패치에서 워커 스레드에서 돈다. 이것이 안전한 이유는 워커 풀에 `NON_INTERRUPTIBLE` · `COOPERATIVE` 도구만
오르기 때문이다 — `THREAD_INTERRUPT` · `EXTERNALLY_TERMINATED` 도구는 워커 풀에 들어가지 않으므로, 워커에서 관측되는 플래그는 그
워커에 대한 실제 인터럽트뿐이다. 게이트 자체는 [`tool/parallel-execution.md` §2](../tool/parallel-execution.md#2-2단-게이트) 가
정한다. **이 전제가 넓어지면(병렬화 대상 확대) 승격 규칙을 다시 본다** — backlog 의 4번 항목이다.

포그라운드 `Task` 는 `EXTERNALLY_TERMINATED` 이지만 워커 풀에 오르지 않는다. `ConcurrencyBehavior.DEDICATED_THREAD` 로 호출마다
새로 만들고 다시 쓰지 않는 전용 스레드에서 돌므로, 늦게 도착한 interrupt 는 그 스레드와 함께 사라지고 이 절의 전제는 유지된다.
근거는 [`parallel-foreground-task.md` §2.3](../subagent/parallel-foreground-task.md#23-전용-스레드가-interrupt-문제를-푸는-방식) ·
[§6.7](../subagent/parallel-foreground-task.md#67-부모-취소-시-자식-전파-그리고-늦은-interrupt-가-부모-스레드에-남지-않음) 이다.

### 8.6 기각한 대안

| 대안 | 기각 사유 |
|------|----------|
| 소비만 하고 승격 안 함 | 플래그만 있던 인터럽트가 도구를 스킵시킨 뒤 tail 에는 안 보여 턴이 잘못된 `CompletionReason` 으로 끝난다 |
| `isInterrupted()` 로 비파괴 검사만 | 검사는 순수 함수가 되지만 플래그가 계속 살아 있어 fail-open 우회가 그대로 남는다 |
| 훅 실행기에서 `InterruptedException` 을 BLOCKED 로 매핑하는 것만 | **주 방어로는** 부족하다 — 훅을 부르지 않는 경로(슬래시 커맨드 flow, LLM 호출, iteration tail)는 그대로이고 실행 스레드 오염도 남는다. 보완책으로는 유효해서 §8.7 로 둔다 |

### 8.7 두 번째 겹 — 훅 실행기의 인터럽트 답

플래그 위생이 주 방어이지만 그것은 **루프의 성실함**에 기대는 방어다. 체크포인트를 새로 더하는 것을 잊거나 새 경로가 스윕을
우회하면 다시 열린다. 그래서 발생 지점인 `DefaultHookExecutor.awaitHook` 이 한 겹 더 막는다 — 인터럽트로 끊긴 대기는 정책과
무관하게 BLOCKED 다. 규칙의 전문(어느 훅이 멈추고 어느 훅이 끝까지 도는가)은 [`hook-cancellation.md`](../hook/hook-cancellation.md)
가 소유하고, 이 절은 플래그 위생의 관점에서 그 답의 이유만 둔다.

- **`HookExecutionPolicy.onException` 을 거치지 않는다.** 예외 매퍼가 답하는 질문은 "훅이 *실패*하면 어떻게 할 것인가" 이고 기본
  답은 가용성 우선(`success()`)이다. 인터럽트는 훅의 실패가 아니다 — 훅은 멀쩡할 수 있고, 끊긴 것은 **그 판정을 기다릴 이쪽의
  능력**이다. 매퍼로 넘기면 묻지도 않은 질문에 그 답이 적용된다. 정책을 비트는 대신 묻지 않는다.
- **BLOCKED 를 고르는 이유는 `TimeoutBehavior.FAIL_CLOSED` 와 같다.** 판정이 없다는 것은 진행해도 좋다는 허가가 없다는 뜻이다. 이
  경로는 실행을 모는 스레드가 실제로 인터럽트됐을 때만, 즉 어차피 취소되는 실행에서만 닿으므로 멀쩡한 실행을 잘못 막지 않는다.
- **정책을 조건으로 삼지 않는다.** `stopOnBlocked` 는 BLOCKED 를 강제하는 스위치가 아니다 — OnStart 체인은 never-stop 정책으로
  돌지만 호출부(`checkOnStartHooks`)는 blocked 결과를 보고 턴을 멈춘다. 두 축이 어긋나 있으므로 정책을 조건으로 삼으면 게이트가
  있는 체인 하나를 빠뜨린다.

스레드가 오염된 채 들어온 턴(§8 의 (2))은 OnStart 에서 멈추고 `ExecutionBlockedByHookException` 의 메시지로 인터럽트를 말한다.
첫 체크포인트까지 진행해 `INTERRUPTED` 를 다는 대신 이 결과를 택한다 — 훅이 하나도 돌지 않았는데 성공으로 보고하는 것보다 정직하기
때문이다. 정확한 사유가 필요하면 OnStart 앞에 체크포인트를 더하지, 훅 체인이 답을 지어내게 하지 않는다. 호출자의 플래그는
복원되므로(§8.3) 취소는 유실되지 않는다.

BLOCKED 를 강제하지 않는 자문 체인(PostTool · OnStop · 세션/서브에이전트 라이프사이클)에서 이 답은 아무도 얻지 않은 "성공" 대신
"답을 못 받았다" 를 남기며, 이미 끝난 훅은 영향이 없다. 기다려 달라고 선언한 훅(`ExecutionHook#ignoresInterrupt()`)은 예외로 끝까지
기다린 뒤 플래그를 다시 세운다 — 둘 다 [`hook-cancellation.md` §2.2](../hook/hook-cancellation.md#22-스레드의-길) 가 정한다.

### 8.8 PostTool 앞의 소비

도구가 돌아온 뒤 PostTool 전에 플래그를 쓸어내는 지점이 하나 더 있다. `SingleToolInvoker` 는 registrar 가 있었던 도구
(`THREAD_INTERRUPT` · `EXTERNALLY_TERMINATED`)에 한해, 호출을 보고하는 훅(PostTool, 거부 경로의 PermissionDenied) **전에** registrar 를
닫고, **실행의 신호가 이미 떠 있을 때만** 플래그를 소비한다. 그렇지 않으면 늦게 떨어진 terminator 의 interrupt 가 §8.7 의 BLOCKED 를
불러, `ignoresInterrupt()` 를 선언하지 않은 PostTool 훅(코드로 쓴 출력 가림 훅 같은)의 효과가 이미 만들어진 결과에 적용되지 않는다.

- **장벽.** `TerminatorRegistrar.close()` 는 돌아온 뒤 그 registrar 의 terminator 가 돌지 않음을 보장한다 — 이미 발화 중인 것을
  기다린다. 그래서 close 뒤에 읽은 플래그는 그 registrar 가 다시 바꾸지 않는다. terminator 는 trip 에서만 발화하므로, close 뒤 신호가
  떠 있지 않다면 플래그는 terminator 의 것일 수 없다.
- **승격하지 않는다.** 안 뜬 신호 위의 플래그는 호출자의 interrupt 다. 여기서 승격하면 그 사실이 이 호출의 coordinator 로 옮겨지는데,
  슬래시 커맨드 경로의 coordinator 는 아무도 읽지 않고 커맨드 끝에 닫힌다 — §8.3 의 스윕이 플래그를 못 찾아 취소가 사라진다. 그래서
  그 플래그는 그것을 소유한 체크포인트·스윕(§8.2, §8.3)에 남긴다. 신호가 떠 있다면 소비해도 잃는 것이 없다 — 루프의 체크포인트가
  하는 것과 같다(§8.1).
- **registrar 가 없는 도구는 손대지 않는다.** 워커 풀에서 도는 도구의 플래그는 그 워커에 대한 실제 인터럽트다(§8.5).
- **남은 창.** 장벽은 자기 registrar 만 막는다. 다른 registrar 나 `DedicatedThreadDispatcher` 가 같은 스레드에 넘기는 interrupt 는
  여전히 PostTool 을 BLOCKED 로 만들 수 있다. 어느 쪽이든 이미 취소되는 실행이다.
- **대가.** registrar 가 PostTool 전에 닫히므로, 취소가 PostTool 전에 왔든 도중에 왔든 보고 훅은 끝까지 돈다. 보고 훅은 trip 뒤
  신호를 받지 않아 스스로 끊지도 못하므로(그 규칙은 [`hook-cancellation.md` §1](../hook/hook-cancellation.md#1-규칙)) 취소는 호출마다
  최대 훅 타임아웃만큼 늦어질 수 있다. 훅을 끊지 않는 것이 이 소비의 목적이므로 정의상의 대가다.

---

## 9. LLM 호출 중단

도구만 끊어서는 부족하다. 인터럽트가 LLM HTTP 호출 도중에 도착하면 응답이 끝날 때까지 아무 일도 일어나지 않는다.
`SignalBackedLlmCancellation` 이 agent 쪽 `CancellationSignal` 을 llm 쪽 `LlmCancellation` 으로 잇는다. 어댑터가 agent 쪽에 사는
것은 `at.aimon.core.llm` 이 `at.aimon.core.agent.interrupt` 에 의존할 수 없기 때문이다(ArchUnit). 리스너는 실행당 하나이고 각 호출은
자기 abort 레버를 그 하나에 바꿔 끼운다 — 상세와 기각한 대안은
[`llm/cancellation.md` §6](../llm/cancellation.md#6-실행기-배선--단일-리스너-브릿지) 이 정본이다.

---

## 10. 종료 사유와 이벤트

중단된 실행은 `CompletionReason.INTERRUPTED` 로 끝난다. `ABORTED` 를 쓰지 않는다 — `ABORTED` 는 프레임워크가 스스로 일찍 끝낸 것이고
`INTERRUPTED` 는 사용자 · NOW · 라우팅이 밖에서 끊은 것이며, 관측 · 리포트 · 과금 분석이 둘을 구분해야 하기 때문이다. 중단된 턴은
재시도할 수 있는 흔적을 남긴다 — [`turn-retry.md`](../session/turn-retry.md).

종단 이벤트는 누가 끊었는가에 따라 둘로 갈린다.

- **노드 로컬 중단 — `ExecutionCompleted(INTERRUPTED)`.** 실행기가 낸다. 무엇이 trip 했는지(`interruptReason`)와 요청에 붙은
  설명(`interruptDetail`, §3.1)을 함께 싣는다. 둘 다 선택 필드라 노드 간 페이로드(`AgentExecutionEventPayload`)에서 옛 빌드는 쓰지도
  읽지도 않고 나머지를 그대로 주고받는다. 모르는 사유 이름은 `UNKNOWN` 으로 읽는다(§3).
- **라우팅 계층의 중단 — `InterruptedAt`.** 라우팅 계층(`DefaultSessionRouter` 의 축출, `HolderLossSweeper` 의 holder 상실)이 합성한다.
  `ExecutionCompleted` · `ExecutionError` 와 함께 `AgentExecutionEvent` 의 세 번째 종단 flavor 이고, 필드는 `reason`(trip 사유),
  `iterationIndex`(1-based), `partialOutput`(중단 시점까지 누적된 assistant 텍스트)이다. 라우터가 합성하므로 크로스 노드 구독자는
  스트림이 완료되기 전에 언제나 `InterruptedAt` 을 보고, 매달린 스피너 대신 종료된 턴을 렌더한다. 살아 있는 세션 컨텍스트가 없어도
  내므로 sentinel 컨텍스트 id 를 쓴다. 홀더의 턴이 실제로 돌고 있었다면 릴레이된 이벤트와 합쳐 두 번 보일 수 있다 — 축출이 릴레이를
  앞질러 이벤트를 잃는 것보다 낫다.

도구 단위 중단 이벤트는 두지 않는다. 도구 단위 중단 요청은 사용자에게 보여 줄 상태가 아니고 관측할 사실(어느 iteration 에서 무슨
사유로 끊겼는가)은 종단 이벤트에 다 담기며, `AgentExecutionEvent` 가 sealed 라 이벤트를 늘리면 모든 소비자의 switch 가 함께 늘어나기
때문이다.

---

## 11. 멀티 노드 전파

`CancellationSignal` 은 실행당 메모리 객체이므로 저장소 교체 대상이 아니다. **분산되는 것은 신호가 아니라 신호를 만드는 사건**이다.

| 사건 | 전파 |
|------|------|
| NOW-tier enqueue | `MessageQueueManager` 리스너. 분산 구현은 큐 저장소의 책임 |
| 다른 노드의 Ctrl+C · 명시적 중단 | `SessionSignalBus` 의 `INTERRUPT` 신호. 설명은 보내는 노드가 정규화해 선택 키 `detail` 로 싣는다 |
| 세션 소유권 상실 | 라우팅 계층이 `LEASE_LOST` · `SESSION_RELEASED` · `HOLDER_LOST` 로 로컬 턴을 끊는다 |
| 다른 노드에서 도는 예약 루틴의 취소 | `ScheduledTaskInterruptBus` 팬아웃 — [`routine-interrupt.md`](../scheduling/routine-interrupt.md) |

신호 kind(`INTERRUPT` · `EVICT` · `YIELD`)의 구분과 와이어는 [`session/routing.md` §5](../session/routing.md#5-크로스-노드-시그널),
수신 노드가 멈출 세션을 고르는 규칙은 [§10](../session/routing.md#10-인터럽트-전파) 이 정본이다.

`InterruptReason` 에 멀티 노드 사유 셋이 있는 이유가 세 번째 줄이다. 멀티 노드에서는 "이 노드가 더 이상 이 세션을 돌릴 자격이
없다" 도 사용자의 중단과 같은 기계로 표현한다 — 둘 다 "진행 중인 작업을 안전하게 접고 자원을 놓아라" 이기 때문이다.

---

## 12. 예약 루틴

예약 루틴은 세션 없는 실행 중 유일하게 **밖에서 지목해 끊을 수 있는** 경로다. `RoutineExecutor` 가 실행마다 자기
`InterruptCoordinator` 를 만들고 `ScheduledTaskId`(와 회차 키)로 찾을 수 있게 등록한다. 턴의 iteration 경계에 해당하는 것이 스텝
경계다. 중단과 취소의 관계, 전파 사다리, 결과 분류, 노드를 넘는 취소, 회차 단위 중단, 드레인은
[`scheduling/routine-interrupt.md`](../scheduling/routine-interrupt.md) 가 정본이다.

---

## 13. 의도적으로 제외한 것

- **모든 도구를 별도 `ExecutorService` 로 강제 오프로드** — 대부분의 도구는 수 ms 다. 전 호출을 Future 로 감싸는 비용이 정당화되지
  않는다. wrapper 는 `THREAD_INTERRUPT` 도구에만 적용한다.
- **도구 내부 상태 rollback** — 파일 쓰기나 외부 API 호출을 중단하면 불완전 상태가 남을 수 있다. 이것은 도구 설계의 책임이지 중단
  기계의 책임이 아니다.
- **인터럽트 이후 자동 재시도** — 사용자 의도(abort 인지 retry 인지)가 모호하므로 명시적 입력을 요구한다. 그 입력의 처리는
  [`turn-retry.md`](../session/turn-retry.md) 다.

---

## 14. 설계 결정

| 쟁점 | 결정 | 기각한 대안 |
|------|------|------------|
| 신호를 도구에 전달하는 채널 | `ToolContext` 의 타입 키(§5) | `Tool.execute` 에 파라미터 추가: 프레임워크·SDK·플러그인까지 번지는 breaking change. `ToolContext` 전용 getter: 범용 컨테이너에 도메인 API 가 섞인다 |
| 기본 전파 방식 | 협조적 신호 읽기, `Thread.interrupt()` 는 opt-in(§2) | 모든 도구에 스레드 인터럽트: 반만 닫힌 파일·소켓. 모든 도구를 `ExecutorService` 로 오프로드: 수 ms 도구에 Future 비용(§13) |
| trip 권한 | coordinator 만. 도구는 읽기 전용 절반을 받는다(§3) | 신호 인터페이스에 trip 공개: 도구 결과나 LLM 응답이 세션을 중단시킬 경로가 생긴다 |
| coordinator 수명 | 실행 1회(§4) | 세션 단위: 이전 턴의 SIGINT 가 새 턴에 영향 |
| 설명의 지위 | 관측 전용, 정규화하되 거절하지 않음(§3.1) | 모델에 전달: 요청자 텍스트의 프롬프트 유입 경로. 이상한 설명 거절: 멈춰야 할 실행이 계속 돈다 |
| 도구 게이트 위치 | `toolRunner(...)` 의 per-tool 콜백(§7) | `executeToolUses` 의 for 루프: 병렬·eager 경로가 우회한다 |
| 경계 검사 | 스레드 플래그를 무조건 소비하고 신호에 승격(§8.1, §8.2) | 승격 없는 소비, 비파괴 검사: §8.6 |
| fail-open 우회의 방어 | 플래그 위생이 주 방어, 훅 실행기의 BLOCKED 답이 보완(§8.7) | 훅 실행기의 답만: §8.6. 예외 정책(`onException`)으로 매핑: 인터럽트는 훅의 실패가 아니다 |
| PostTool 앞 플래그 | registrar 를 닫고, 신호가 떠 있을 때만 소비, 승격하지 않음(§8.8) | 승격: 슬래시 커맨드의 coordinator 로 사실이 옮겨져 스윕이 못 찾고 취소가 사라진다 |
| 종료 사유 | `CompletionReason.INTERRUPTED`(§10) | `ABORTED` 재사용: 자발적 조기 종료와 밖에서 끊은 것이 구분되지 않는다 |
| 도구 단위 중단 이벤트 | 두지 않는다(§10) | 별도 이벤트: 보여 줄 상태가 아니고 sealed 이벤트의 모든 소비자 switch 가 늘어난다 |
| 모르는 사유 이름 | `UNKNOWN` 으로 읽는다(§3) | 프레임을 버린다: 롤링 업그레이드 중 종료를 잃는다. 다른 값으로 읽는다: 일어나지 않은 일을 말한다 |
| 분산 대상 | 신호를 만드는 사건(§11) | 신호 자체를 분산 저장: 실행당 메모리 객체이고 교체할 저장소가 없다 |

---

## 15. 하지 말 것

- **`signal.isCancelled() || Thread.interrupted()` 순서로 쓰지 말 것.** 단락 평가가 플래그를 남긴다(§8.1).
- **한 판단 지점에서 소비형 검사를 두 번 부르지 말 것.** 두 번째가 `false` 로 뒤집혀 인터럽트 사실이 사라진다(§8.2).
- **정체 가드에서 소비형 검사를 쓰지 말 것.** tail 이 플래그를 못 본다(§8.4).
- **`THREAD_INTERRUPT` · `EXTERNALLY_TERMINATED` 도구를 워커 풀에 올리면서 승격 규칙을 그대로 두지 말 것.** 워커에서 본 플래그가
  더 이상 그 워커의 것이 아니다(§8.5).
- **인터럽트로 끊긴 훅 대기를 `HookExecutionPolicy.onException` 으로 넘기지 말 것.** PreTool `BLOCKED` 가 allow 로 강등된다(§8.7).
- **PostTool 앞 소비에서 승격하지 말 것**(§8.8).
- **도구 실행 중간에 큐를 drain 하지 말 것.** `tool_use` ↔ `tool_result` 페어링이 깨진다(§6.3).
- **설명을 모델에 보내거나 비밀을 담지 말 것.** 노드 간 신호와 함께 백엔드에 잠깐 저장된다(§3.1).
- **설명을 받는 오버로드를 재정의하지 않은 위임 래퍼를 두지 말 것.** 설명이 조용히 떨어진다(§3.1).
- **coordinator 를 세션 단위로 끌어올리지 말 것**(§4).
- **LLM 호출마다 abort 리스너를 신호에 등록하지 말 것.** 리스너가 누적되고 끝난 호출의 abort 까지 발화한다(§9).
- **`LiveSessionStatus#isInterruptible` 을 제어 게이트로 쓰지 말 것.** 관찰값이다(§6.1).

---

## 16. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|------|------|------|
| 도구의 선언 | `InterruptBehavior` | `agent/interrupt/` |
| 신호 · 사유 · 설명 | `CancellationSignal`, `InterruptReason`, `InterruptDetail` | `agent/interrupt/` |
| coordinator · terminator | `InterruptCoordinator`, `DefaultInterruptCoordinator`, `TerminatorRegistrar` | `agent/interrupt/` |
| 소비 헬퍼 | `CancellationSignals` | `agent/interrupt/` |
| LLM 어댑터 | `SignalBackedLlmCancellation` | `agent/interrupt/` |
| 컨텍스트 채널 | `InterruptToolKeys`, `InterruptAccess` | `agent/tool/` |
| 루프 게이트 · 스윕 · 복원 | `OrcaAgentExecutor` | `agent/impl/orca/` |
| PostTool 앞 소비 | `SingleToolInvoker` | `toolinvocation/` |
| 훅 실행기의 인터럽트 답 | `DefaultHookExecutor` | `hook/execution/` |
| 호스트 진입점 · NOW 리스너 · 관찰 플래그 | `LiveSession`, `DefaultLiveSession`, `LiveSessionStatus` | `agent/session/` |
| NOW-tier 큐 | `MessageQueueManager` | `agent/queue/` |
| 종료 사유 · 종단 이벤트 | `CompletionReason`, `ExecutionCompleted`, `InterruptedAt` | `agent/budget/`, `agent/stream/` |
| 대표 `THREAD_INTERRUPT` 사례 | `BashTool` | `tools/bash/` |
| REPL Ctrl+C | `ReplSession` | `modules/aimon-cli/src/main/java/at/aimon/cli/repl/` |
| 라우팅 계층의 중단 | `DefaultSessionRouter`, `HolderLossSweeper` | `modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/` |
