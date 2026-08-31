# 중단(Interrupt) — 실행 중인 도구를 안전하게 끊기

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core`, `aimon-cli`
> 사용 가이드: [`interruptible-tools-guide.md`](../../features/agent-execution/interruptible-tools-guide.md),
> [`command-queue-guide.md`](../../features/agent-execution/command-queue-guide.md)
> 관련 문서: [`compaction.md`](compaction.md), [`interceptor.md`](interceptor.md)

사용자가 Ctrl+C 를 누르거나 `QueuedInputPriority.NOW` 로 메시지를 투입했을 때, **진행 중인 도구
실행을 안전하게 끊고 다음 턴으로 깔끔히 넘어가는** 메커니즘이다.

시작점의 문제는 신호가 도구까지 도달하지 못한다는 것이었다. `Tool.execute()` 는 동기·블로킹이고
중단 채널이 없었으며, REPL 의 Ctrl+C 는 `CompletableFuture.cancel(true)` 만 호출해 future 만
완료 상태로 바꿨을 뿐 실행 스레드는 계속 돌았다. `QueuedInputPriority.NOW` 는 enum 으로 선언만
되어 있고 소비 경로가 없었다.

---

## 1. 설계 원칙

- **기존 Tool API 파괴 금지** — `Tool.execute(ToolInput, ToolContext)` 시그니처를 유지한다. 신호는
  `ToolContext` 로 전달하고, 도구의 선언은 `default` 메서드로만 추가한다.
- **Cooperative first, preemptive second** — 기본은 협조적(신호 읽기)이고, `Thread.interrupt()` 는
  명시적으로 opt-in 한 도구에만 전달한다. 도구가 보증하지 않은 상태에서 스레드를 강제로 끊으면
  반만 닫힌 파일/소켓이 남는다.
- **Iteration 경계 우선 중단** — 도구가 비중단형이어도 iteration 경계에서는 반드시 중단된다. 도구
  내부 중단은 "빠르게 반환" 을 위한 최적화이지 정확성의 근거가 아니다.
- **역할 분리** — 호스트는 *언제* 중단할지, 코디네이터는 *어떻게* 전파할지, 도구는 *자기 자원만*
  정리한다.

### 1.1 계층

| 계층 | 메커니즘 | 책임 |
|------|----------|------|
| Host (CLI/SDK) | SIGINT, UI 버튼, NOW enqueue, 스케줄 태스크 취소 | "중단하고 싶다" 는 의사 표시만 |
| Live session | `LiveSession.interrupt(...)` | 활성 턴 식별, coordinator 위임 |
| Routine run | `RoutineExecutor.interrupt(taskId, reason)` | 그 태스크의 진행 중인 실행 식별, coordinator 위임 (§12) |
| Coordinator | `CancellationSignal` + 전파 전략 | 신호 trip, 경계 전파, 이벤트 발행 |
| Execution | Orca 루프 · `ToolContext` 주입 | 경계마다 신호 검사, `ToolResult` 생성 |
| Tool | `InterruptBehavior` 선언 + 신호 읽기 | 자기 자원을 안전하게 정리 |

---

## 2. `InterruptBehavior` — 도구의 선언

각 도구가 "외부 중단 신호에 어떻게 반응할 수 있는가" 를 선언한다
(`at.aimon.core.agent.interrupt`).

| 값 | 뜻 | 코디네이터의 전파 행동 |
|----|-----|----------------------|
| `NON_INTERRUPTIBLE` | 무시 (빠르게 끝나거나 원자적) | 전파 없음. iteration 경계까지 대기 |
| `COOPERATIVE` | 신호를 폴링, 체크포인트마다 반환 | 신호 trip 만 (도구가 스스로 읽는다) |
| `THREAD_INTERRUPT` | `Thread.interrupt()` 에 안전 | 도구 스레드의 `Thread.interrupt()` 호출 |
| `EXTERNALLY_TERMINATED` | Terminator 핸들을 등록 | 등록된 `Terminator` 실행 |

`Tool` 의 `default` 메서드이고 기본값은 `NON_INTERRUPTIBLE` 이다 — 기존 구현은 한 줄도 고치지
않고 "iteration 경계 중단" 을 얻는다. 대부분의 도구(Read/Write/Edit/Todo …)는 수 ms 안에 끝나므로
그것으로 충분하고, `THREAD_INTERRUPT` 는 **도구가 안전하다고 명시적으로 선언할 때만** 켜진다.

현재 적용 현황:

| 도구 | 선언 | 근거 |
|------|------|------|
| `ReadTool` | `NON_INTERRUPTIBLE` | 파일 I/O 는 밀리초 단위 |
| `GrepTool` | `COOPERATIVE` | 라인 스트리밍 루프에 체크포인트 |
| `WebFetchTool` | `COOPERATIVE` | HTTP body read 루프에 체크포인트 |
| `GraalJsWorkflowTool` | `COOPERATIVE` | 스크립트 스텝 경계에 체크포인트 |
| `BashTool` | `THREAD_INTERRUPT` | 내부 `Future.cancel(true)` → 자식 프로세스 종료 |
| `TaskTool` | `EXTERNALLY_TERMINATED` | 자식 실행에 cascade (`PARENT_CANCELLED` 로 재분류) |

`DefaultParallelToolDispatcher` 와 `SingleToolInvoker` 도 같은 선언을 읽는다 — 병렬 게이트는
`NON_INTERRUPTIBLE` · `COOPERATIVE` 만 워커 풀에 넣는다(§7 이 그 사실에 의존한다).

---

## 3. `CancellationSignal` — 단방향 신호

한 실행에 바인딩된 1회용 "플래그 + 이유" 핸들이다.

```java
public interface CancellationSignal {
    boolean isCancelled();
    Optional<InterruptReason> getReason();
    void checkpoint();                       // 취소되었으면 CancelledExecutionException
    Registration onCancel(Runnable listener);
}
```

**신호를 trip 하는 메서드는 이 인터페이스에 없다.** `DefaultCancellationSignal.trip(InterruptReason)`
은 **package-private** 이고, 트립 권한을 가진 유일한 주체는 같은 패키지의 `InterruptCoordinator`
다. 도구에게 건네지는 것은 읽기 전용 절반뿐이라, 도구 결과나 LLM 응답이 세션을 중단시킬 경로가
구조적으로 없다.

불변 규칙은 셋이다 — 한 번 trip 되면 다시 clear 되지 않고(단방향), `onCancel` 등록은 thread-safe
이며 **이미 trip 된 상태면 즉시 실행**하고, 등록은 `Registration` 을 돌려주어 해제할 수 있다
(§6 이 이 해제에 의존한다).

`InterruptReason` 은 아홉 값이다. 앞의 여섯은 단일 노드 시나리오이고, 뒤의 셋은 멀티 노드
라우팅이 세션 소유권을 잃었을 때 쓴다.

```
USER_SIGINT · NOW_PRIORITY_INPUT · BUDGET_EXCEEDED · PARENT_CANCELLED · TASK_CANCELLED · SYSTEM_SHUTDOWN
LEASE_LOST · SESSION_RELEASED · HOLDER_LOST
```

`TASK_CANCELLED` 는 스케줄 태스크의 소유자가 그 태스크를 취소했거나 진행 중인 실행만 끊었을 때다(§12).
`PARENT_CANCELLED` 와 나누어 둔 것은 **cascade 가 아니기** 때문이다 — 상위 실행에서 흘러 내려온 것이
아니라 요청이 그 실행 자신의 태스크를 지목했다.

이 값은 로그·이벤트·`ToolResult.error()` 메시지에 표준 문자열로 실려 관측을 일관되게 만든다.

---

## 4. `InterruptCoordinator` — 실행당 하나

```java
public interface InterruptCoordinator {
    CancellationSignal getSignal();
    void requestInterrupt(InterruptReason reason);
    TerminatorRegistrar newTerminatorRegistrar();
    void close();
}
```

`requestInterrupt` 는 신호를 trip 하고, 등록된 terminator 를 실행하고, 이벤트를 발행한다.
**멱등**이다 — 이미 trip 된 상태면 전파를 건너뛴다. Terminator 호출은 try/catch 로 감싸 로그만
남긴다(콜백이 던진 예외가 코디네이터 자원을 누수시키지 않는다).

수명은 **실행 1회**다. 세션 레벨로 끌어올리지 않은 이유는 "이전 턴의 SIGINT 가 새 턴에 영향" 같은
버그를 원천 차단하기 위해서다 — 세션은 직렬 실행을 가정하므로 한 번에 한 턴만 중단하면 된다.
`ExecutionScope` 가 인스턴스를 보관하고 종료 시 `close()` 로 리스너·terminator 를 정리한다.

`newTerminatorRegistrar()` 는 도구 호출마다 새 registrar 를 발급한다. `register`/`unregister`/
`close()` 만 있는 좁은 인터페이스라, 도구는 자기 terminator 를 등록할 수 있을 뿐 코디네이터
자체(=trip 권한)에는 닿지 못한다.

---

## 5. 신호 전달 채널 — `ToolContext` 타입 키

`Tool.execute` 에 파라미터를 추가하면 프레임워크·SDK·외부 플러그인까지 전파되는 breaking change
다. 반면 `ToolContext` 는 이미 "실행 스코프 read-only dictionary" 이므로 여기에 전용 키를 넣는다.
**미지원 도구가 키를 무시하면 `NON_INTERRUPTIBLE` 과 등가**이고, 그것이 우리가 원하는 기본값이다.

키는 문자열이 아니라 **타입 키**(`ToolContextKey<T>`)다.

```java
// at.aimon.core.agent.tool.InterruptToolKeys
ToolContextKey<CancellationSignal>  CANCELLATION_SIGNAL
ToolContextKey<TerminatorRegistrar> TERMINATOR_REGISTRAR
```

키가 `at.aimon.core.agent.interrupt` 가 아니라 `…agent.tool` 에 사는 것은 패키지 순환을 피하기
위해서다 — `interrupt` 쪽에 두면 `tool → interrupt` 방향이 생긴다.

접근은 `InterruptAccess` 헬퍼로 한다. `signalOf(context)` 는 키가 없으면
`NoopCancellationSignal` 을 돌려주므로 도구 코드에 null 검사가 필요 없고,
`registrarOf(context)` 는 `Optional` 이다.

```java
CancellationSignal signal = InterruptAccess.signalOf(context);
while (!signal.isCancelled() && hasMoreChunks) { ... }
if (signal.isCancelled()) {
    return ToolResult.error("Interrupted: " + signal.getReason().orElse(null));
}
```

`ToolContext.getCancellationSignal()` 같은 전용 getter 는 두지 않았다 — 범용 컨테이너에 특정
도메인 API 를 섞지 않기 위해 static helper 로만 제공한다.

---

## 6. 호스트에서 신호까지

### 6.1 `LiveSession.interrupt` — 두 형태

```java
default void interrupt(InterruptReason reason);              // 활성 턴이 무엇이든
default void interrupt(TurnId turnId, InterruptReason reason); // 지정한 턴에만
```

둘 다 `default` no-op 이라 중단을 지원하지 않는 구현은 그대로 둔다. `DefaultLiveSession` 은 활성
턴의 coordinator 참조를 읽어 `requestInterrupt` 를 호출하고, 세 가지 "못 끊는" 경우를 한 자리에서
결정한다 — 활성 턴이 없거나(idle), 턴이 아직 ReAct 루프에 진입하지 않아 coordinator 가 없거나
(슬래시 커맨드 턴), 주소 지정 형태에서 `turnId` 가 활성 턴과 다르거나. 셋 다 debug 로그 + no-op 다.

주소 지정 형태가 필요한 이유는 경합이다. 사용자가 턴 A 를 끊으려는 사이에 A 가 끝나고 B 가 시작되면
비주소 형태는 **엉뚱한 턴을 끊는다**. `TurnId` 는 제출 시점에 발급되는 비영속 주소이며 이 확인
하나를 위해 존재한다(자세한 규칙은 [`glossary.md` §4](../../overview/glossary.md)).

### 6.2 Ctrl+C 는 두 번 두드린다

REPL 의 SIGINT 핸들러는 `session.interrupt(USER_SIGINT)` 를 **먼저** 호출하고 그다음
`future.cancel(true)` 를 호출한다. 순서가 중요하다 — `future.cancel(true)` 만 하면 future 는 즉시
완료로 바뀌지만 도구 스레드는 계속 running 이라 사용자 눈에 "아직 CPU 를 쓰고 있음" 으로 보인다.
먼저 신호를 주어 도구가 정리할 시간을 벌고, `future.cancel(true)` 는 REPL 이 `join()` 에서 빠르게
깨어나 다음 프롬프트로 돌아가기 위해 여전히 필요하다.

### 6.3 NOW-tier 큐

`MessageQueueManager` 에 NOW 우선순위 입력이 도착하면 리스너가 세션의
`interrupt(NOW_PRIORITY_INPUT)` 를 호출한다. `DefaultLiveSession` 이 자기 리스너를 등록하고
자신의 컨텍스트 id 와 일치할 때만 반응한다.

여기서 지켜야 할 관계는 **"신호는 중단, 메시지는 다음 턴 주입"** 이다. 인터럽트가 발생해도 큐
엔트리는 drain 시점까지 큐에 남는다(멱등·crash-safe). drain 은 iteration 끝에서 일괄 수행하고,
`<system-reminder>` 로 감싼 user 메시지로 투입한다. 도구 종료 직후 경계에서는 **drain 하지 않고
신호만 확인**한다 — 도구 실행 *중간*에 대화 기록을 수정하면 `tool_use` ↔ `tool_result` 페어링이
깨지기 때문이다. 이것이 중단 응답성(ms)과 대화 일관성을 양립시키는 지점이다.

---

## 7. Orca 루프의 경계 게이트

루프는 두 종류의 경계에서 신호를 본다 — **iteration 경계**(시작 직후, tail)와 **도구 배치의
per-tool 게이트**다.

도구 게이트가 `executeToolUses` 의 for 루프가 아니라 **`toolRunner(...)` 가 반환하는 per-tool
콜백 한 곳**에 있는 것이 설계의 핵심이다. 그래야 순차 디스패치 · 병렬 디스패치
(`DefaultParallelToolDispatcher`) · 스트리밍 eager/harvest 분할이라는 **세 디스패치 형태가 모두
같은 게이트를 상속**한다. for 루프에만 두면 병렬·eager 경로가 게이트를 통째로 우회한다.

게이트 범위는 **아직 시작하지 않은** `tool_use` 로 한정한다. 여기서 "시작" 의 기준은 **그 도구를
실행할 스레드**다 — 게이트를 평가하는 주체가 콜백 본문이고, 병렬/eager 디스패치에서 그 본문은 풀
워커에서 돌기 때문이다. 따라서 풀에 submit 됐지만 아직 워커가 집어가지 않은 태스크는 그대로
스킵된다. 이미 `executeSingleTool` 안으로 진입한 도구는 결과를 유지한다 — 버리면 실행 중인 작업이
누수되고 모델이 이미 일으킨 부수효과를 잃는다. 실행 중인 도구를 멈추는 것은 §4 의 Terminator
기계의 책임이지 게이트의 책임이 아니다.

스킵된 도구는 상수 `INTERRUPTED_TOOL_SKIP_MESSAGE`
(`"Interrupted — skipped: the turn was cancelled before this tool started"`)를 결과로 받는다.
스킵이든 실행이든 **`tool_use` ↔ `tool_result` 페어링은 항상 보존**된다.

경계 검사는 `signal.isCancelled()` 가 아니라 스레드 플래그까지 함께 소비하는
`isInterrupted(coordinator)` 를 쓴다. 이유가 §8 이다.

---

## 8. 스레드 인터럽트 플래그 위생

`THREAD_INTERRUPT` 전파는 도구 스레드의 인터럽트 플래그를 세운다. 이 플래그는 누군가
`Thread.interrupted()` 로 **읽어서 지우기 전까지** 그 스레드에 남는다. 루프가
`signal.isCancelled()` 만 검사하고 플래그를 소비하지 않으면 두 가지가 깨진다.

**(1) 훅 fail-open 우회 — 보안 성격.** 플래그가 살아 있는 스레드에서
`DefaultHookExecutor.invokeWithTimeout` 의 `future.get(timeout)` 은 기다리지 않고 즉시
`InterruptedException` 을 던진다. 그 예외를 `HookExecutionPolicy.onException` 으로 넘기면
`continueOnExceptionButStopOnBlocked()` 가 `HookResult.success()` 로 매핑하므로 **PreTool `BLOCKED`
가 조용히 allow 로 강등**된다 — 인터럽트가 도구 권한 차단을 무력화하는 것이다. 지금은 두 겹으로 막는다:
플래그 위생이 낡은 플래그를 실행기까지 보내지 않고(§8.1–§8.3), 그래도 도달했을 때는 실행기가 그 대기를
BLOCKED 로 답한다(§8.7).

**(2) 실행 스레드 오염.** 동기 진입점 `execute(OrcaAgentRuntime, OrcaAgentExecutionRequest)` 는
턴 전체를 **호출자 스레드**에서 돌린다. 임베더가 재사용되는 워커에서 턴을 구동하면 오염된 스레드가
다음 작업으로 넘어가 그 작업의 첫 blocking 호출이 즉시 깨진다. 프레임워크 내장 경로는 이 위험에
해당하지 않는다 — `executeAsync` 는 호출마다 단일 스레드 executor 를 만들어 완료 시 shutdown 하고,
`RoutineExecutor` 의 공유 풀은 턴이 아니라 개별 `Tool` 을 돌리며, 표준 `ThreadPoolExecutor` 도
다음 태스크 dispatch 전에 stale 플래그를 지운다. 위험한 것은 임베더의 스레드다.

### 8.1 소비

```java
// at.aimon.core.agent.interrupt.CancellationSignals
static boolean isCancelledOrInterrupted(CancellationSignal signal) {
    boolean threadInterrupted = Thread.interrupted();     // 무조건 평가 = 무조건 소비
    return signal.isCancelled() || threadInterrupted;
}
```

`Thread.interrupted()` 를 **무조건 선행 평가**하는 것이 요점이다.
`signal.isCancelled() || Thread.interrupted()` 로 쓰면 신호가 이미 떠 있을 때 단락 평가에 걸려
플래그가 그대로 남는다 — 즉 우리가 막으려던 (1)이 그대로 살아난다.

헬퍼가 `CancellationSignal` 바로 옆에 사는 이유는 의존 방향이다. 메인 ReAct 루프가
`at.aimon.core.subagent.execution` 에 의존할 수는 없다. 기존 `SubagentInterrupts` 는 subagent
호출부를 위해 남긴 **위임 alias** 일 뿐 별도 구현이 아니다.

### 8.2 승격

플래그를 읽어 지우는 순간 그 사실은 사라진다. 그래서 `isInterrupted(coordinator)` 는 소비 직후
신호가 아직 안 떠 있으면 `requestInterrupt(PARENT_CANCELLED)` 로 **턴 신호에 승격**시킨다. 이후
모든 하위 판단(iteration tail, 결과 빌드, `CompletionReason.INTERRUPTED`)은 정상적으로 trip 된
신호 하나만 보면 된다. 승격이 없으면 "플래그만 있던 인터럽트" 가 도구를 스킵시켜 놓고 tail 검사에는
보이지 않아, 턴이 어정쩡하게 계속되다 잘못된 `CompletionReason` 으로 끝난다.

**규율: 판단 지점당 1회.** 이 검사가 소비하는 것은 스레드 플래그 절반뿐이고 신호 절반은 sticky
다. 같은 지점에서 두 번 부르면 두 번째는 플래그를 못 본다 — 신호가 아직 안 떠 있었다면 `false` 로
뒤집혀 인터럽트 사실이 사라진다. 그래서 boolean 을 재사용하거나 승격으로 사실을 신호에 보존한다.
`CancellationSignalsTest` 가 이 뒤집힘을 테스트로 못박는다.

### 8.3 스윕과 복원

체크포인트를 지나지 않은 채 플래그가 세팅된 경로가 있다 — 체크포인트가 아예 없는 슬래시 커맨드
flow, 마지막 tail 검사 이후에 도착한 인터럽트, 체크포인트를 통째로 우회하는 예외 종료. 이들이
모두 살아서 도달하는 **스윕 지점이 둘**이다.

1. **`invokeOnStop(...)` 진입 직후.** OnStop 은 턴의 마지막 훅이고, 에러 경로에서는 루프
   체크포인트가 한 번도 쓸어내지 못한 스레드에서 돈다(플래그를 복원하고 던지는 `LlmClient` 는
   `handleExecutionError` 로 직행한다). 플래그가 살아 있으면 위 (1)의 fail-open 이 훅을 조용히
   스킵시킨다. `execute()` 의 `finally` 는 모든 종료 경로가 훅을 이미 호출한 **뒤에** 돌므로 이
   지점을 대신할 수 없다.
2. **`execute()` 의 `finally`, `transcriptManager.saveSilently(...)` 직전.** 인터럽트에 민감한
   저장소가 방금 끝낸 턴의 저장을 중단하는 것을 막는다.

**복원(re-arm).** 소비한 플래그를 그냥 삼키는 것은 턴이 그 취소를 **이미
`CompletionReason.INTERRUPTED` 로 보고한 경우에만** 무손실이다 — 결과가 사실을 담아 가므로 오염된
스레드를 돌려줄 이유가 없다. 그 외의 결과(체크포인트 없는 command flow 의 `COMPLETED`, 에러 종료)
에서는 호출자가 이 스레드에 취소를 요청했는데 그 요청을 담아 갈 채널이 아무 데도 없으므로,
`saveSilently` **이후**에 `Thread.currentThread().interrupt()` 로 되살려 호출자의 취소 프로토콜을
보존한다. 소비 여부는 `ExecutionScope.interruptConsumed` 래치가 기억한다 — 1번 지점에서 이미
소비했다면 `finally` 에서 플래그를 다시 읽는 것만으로는 알 수 없다.

### 8.4 비소비 읽기를 써야 하는 곳

stalled-iteration 가드는 `signal.isCancelled()` 를 **직접** 읽는다. 여기서 소비형 검사를 쓰면 바로
아래 iteration tail 이 플래그를 못 보므로, 스레드 플래그의 평가 지점은 tail 하나로 유지한다.
아울러 인터럽트된 배치는 스킵된 `tool_use` 가 전부 error 결과라 가드에 death-spiral 로 오인되므로,
취소된 턴에서는 가드 자체를 건너뛴다(가드는 [`orca-executor.md` §5](orca-executor.md)).

### 8.5 병렬 경로에서의 안전성

게이트 콜백은 병렬 디스패치 시 워커 스레드에서 돈다. 이것이 안전한 이유는
`DefaultParallelToolDispatcher` 가 `NON_INTERRUPTIBLE` · `COOPERATIVE` 도구만 병렬화하기 때문이다
— `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED` 도구는 애초에 워커 풀에 들어가지 않으므로, 워커에서
관측되는 플래그는 그 워커에 대한 실제 인터럽트뿐이다. **이 전제가 깨지면(=병렬화 대상 확대)
승격 규칙을 재검토해야 한다.**

### 8.6 대안을 기각한 이유

| 대안 | 기각 사유 |
|------|----------|
| 소비만 하고 승격 안 함 | 플래그만 있던 인터럽트가 도구를 스킵시킨 뒤 tail 에는 안 보여 턴이 잘못된 `CompletionReason` 으로 끝난다 |
| `isInterrupted()` 로 비파괴 검사만 | 검사는 순수 함수가 되지만 플래그가 계속 살아 있어 fail-open 우회가 그대로 남는다 |
| 훅 실행기에서 `InterruptedException` 을 BLOCKED 로 매핑 | **주 방어로는** 부족하다 — 훅을 부르지 않는 경로(슬래시 커맨드 flow, LLM 호출, iteration tail)는 그대로고 실행 스레드 오염도 남는다. 보완책으로는 유효해서 §8.7 로 채택했다 |

### 8.7 두 번째 겹 — 훅 실행기의 인터럽트 답

플래그 위생이 주 방어이지만 그것은 **루프의 성실함**에 기대는 방어다. 체크포인트를 새로 추가하는 것을
잊거나 새 경로가 스윕을 우회하면 다시 열린다. 그래서 발생 지점인 `DefaultHookExecutor.awaitHook` 이
한 겹 더 막는다 — 인터럽트로 끊긴 대기는 **정책과 무관하게 BLOCKED** 다.

요점은 그 판단이 `HookExecutionPolicy.onException` 을 **거치지 않는다**는 것이다. 예외 매퍼가 답하는
질문은 "훅이 *실패*하면 어떻게 할 것인가" 이고 기본 답은 가용성 우선(`success()`)이다. 인터럽트는 훅의
실패가 아니다 — 훅은 멀쩡할 수 있고 끊긴 것은 **그 판정을 기다릴 이쪽의 능력**이다. 매퍼로 넘기면 묻지도
않은 질문에 그 답이 적용된다. §8.6 이 이 대안에 걸었던 조건("정책 계층의 의미론을 인터럽트 사정으로 비틀게
된다")은 그래서 해소된다 — 정책을 비트는 대신 **묻지 않는다**.

BLOCKED 를 고른 이유는 `TimeoutBehavior.FAIL_CLOSED` 와 같다. 판정이 없다는 것은 진행해도 좋다는 허가가
없다는 뜻이다. 이 경로는 턴을 모는 스레드가 실제로 인터럽트됐을 때만 닿으므로, 즉 어차피 취소되는 턴에서만
닿으므로 멀쩡한 턴을 잘못 막지 않는다.

정책으로 갈라서는 안 되는 이유가 하나 더 있다. **`stopOnBlocked` 는 BLOCKED 를 강제하는 스위치가
아니다** — OnStart 체인은 never-stop 정책으로 돌지만 호출부(`checkOnStartHooks`)는 blocked 결과를 보고
턴을 중단시킨다. 두 축이 어긋나 있으므로 정책을 조건으로 삼으면 게이트가 있는 체인 하나를 빠뜨리게 된다.

그 OnStart 게이트가 이 변경의 유일한 관측 가능한 부작용이기도 하다. 오염된 스레드로 들어온 턴은
(§8 의 (2)) 이제 OnStart 에서 멈추고 `ExecutionBlockedByHookException` 의 메시지로 인터럽트를 말한다.
예전에는 그 체인이 통째로 fail-open 으로 통과한 덕분에 첫 체크포인트까지 굴러가서 `INTERRUPTED` 라는
더 정확한 사유를 달았다. **더 정확한 사유가 거짓말의 대가였다** — 훅은 하나도 돌지 않았는데 전부
성공으로 보고됐다. 정확한 사유가 필요하면 방법은 OnStart 앞에 체크포인트를 하나 더 두는 것이지, 훅
체인이 답을 지어내게 두는 것이 아니다. 어느 쪽이든 호출자의 플래그는 즉시 복원되므로 취소 자체는
유실되지 않는다(§8.3).

BLOCKED 를 강제하지 않는 자문 체인(PostTool·OnStop·세션/서브에이전트 라이프사이클)에서 이 변경은 보고의
정직함일 뿐이다 — 아무도 얻지 않은 "성공" 대신 "답을 못 받았다" 가 남는다. 이미 끝난 훅은 영향이 없다:
`FutureTask#get` 은 완료된 값을 플래그와 무관하게 돌려주므로 **진짜로 대기 중이던 것만** BLOCKED 가 된다.

---

## 9. LLM 호출 자체의 중단

도구만 끊어서는 부족하다. 인터럽트가 LLM HTTP 호출 도중에 도착하면 응답이 끝날 때까지 아무 일도
일어나지 않는다. `SignalBackedLlmCancellation` 이 agent 쪽 `CancellationSignal` 을 llm 쪽
`LlmCancellation` 으로 잇는 어댑터다.

두 인터페이스가 다른 패키지에 있는 것은 의도다 — `at.aimon.core.llm` 은
`at.aimon.core.agent.interrupt` 에 의존할 수 없고(ArchUnit), 반대 방향은 허용되므로 어댑터는
agent 쪽에 산다.

설계의 요점은 **리스너를 정확히 하나만 등록**하는 것이다. 한 실행은 LLM 호출을 여러 번 한다
(iteration 당 1회 + 게이트웨이 재시도). 호출마다 자기 `StreamResponse.close()` 를 리스너로 등록하면
장수 실행에서 리스너가 무한 누적되고, trip 시 **이미 끝난 호출의 abort 까지 전부 발화**한다.
대신 어댑터가 생성자에서 리스너 하나를 등록하고, 각 호출은 자기 abort 레버를 단일
`AtomicReference` 에 swap 한다. 신호가 trip 되면 그 하나의 리스너가 **현재 활성인** abort 만
실행하고, 끝난 호출은 자기 레버를 비워 둔 상태다.

trip 은 보통 LLM 워커와 다른 스레드에서 도착하므로 abort 는 멱등해야 한다.

---

## 10. 종료 사유와 이벤트

`CompletionReason.INTERRUPTED` 를 새로 추가했다. 기존 `ABORTED` 를 재사용하지 않은 이유는 뉘앙스가
다르기 때문이다 — `ABORTED` 는 "프레임워크가 자발적으로 조기 종료", `INTERRUPTED` 는 "사용자/NOW 가
강제로 끊었다" 이고, 관측·리포트·과금 분석에서 이 구분이 필요하다.

스트리밍 이벤트는 **하나**다. `InterruptedAt` 이 `ExecutionCompleted` / `ExecutionError` 와 함께
세 번째 종단 이벤트 flavor 로서 `AgentExecutionEvent` 의 `permits` 에 들어간다. 필드는
`reason`(트립 사유), `iterationIndex`(1-based), `partialOutput`(중단 시점까지 누적된 assistant
텍스트)이다. 구독자는 이것으로 "매달린 스피너" 대신 명확히 종료된 턴을 렌더한다.

설계 초안에는 `ToolInterruptRequested` 와 `ExecutionInterrupted` 두 이벤트가 있었다. 전자는 결국
넣지 않았다 — 도구 단위 중단 요청은 그 자체로 사용자에게 보여 줄 상태가 아니고, 관측이 필요한
사실(어느 iteration 에서 무슨 이유로 끊겼는가)은 종단 이벤트 하나에 다 담긴다. 이벤트를 늘리면
`AgentExecutionEvent` 가 sealed 이므로 모든 소비자의 switch 가 함께 늘어난다.

---

## 11. 멀티 인스턴스

`CancellationSignal` 은 실행당 메모리 객체이므로 저장소 교체 대상이 아니다. 분산이 필요한 것은
**신호를 만드는 사건**뿐이다.

| 사건 | 전파 |
|------|------|
| NOW-tier enqueue | `MessageQueueManager` 리스너 인터페이스. 분산 구현은 큐 저장소의 책임 |
| 다른 노드의 Ctrl+C / 명시적 중단 | `SessionSignalBus` 의 `INTERRUPT` 신호 |
| 세션 소유권 상실 | `LEASE_LOST` · `SESSION_RELEASED` · `HOLDER_LOST` 로 라우팅 계층이 로컬 턴을 끊는다 |
| 다른 노드에서 도는 스케줄 루틴의 취소 | `ScheduledTaskInterruptBus` 의 팬아웃 (§12.7) |

세 번째 줄이 `InterruptReason` 이 다섯에서 여덟로 늘어난 이유다. 인터럽트는 원래 사용자 의도의
표현이었지만, 멀티 노드에서는 **"이 노드가 더 이상 이 세션을 돌릴 자격이 없다"** 도 같은 기계로
표현된다 — 둘 다 "진행 중인 작업을 안전하게 접고 자원을 놓아라" 이기 때문이다.

---

## 12. 스케줄 루틴

세션 없는 실행 중 유일하게 **밖에서 지목해 끊을 수 있는** 경로다. `RoutineExecutor` 가 실행마다
자기 `InterruptCoordinator` 를 만들고, 그것을 `ScheduledTaskId` 로 찾을 수 있게 등록한다.

```java
public boolean interrupt(ScheduledTaskId taskId, InterruptReason reason);
```

### 12.1 취소가 취소가 아니었던 자리

`ScheduledTaskManager.cancel` 은 unschedule + 레코드 삭제였다. 그것은 **앞으로의 발화**만
지배하므로, 스텝 중간이던 루틴은 방금 삭제된 태스크를 대신해 파일을 쓰고 외부 시스템을 호출하며
남은 스텝을 끝까지 돌았다. 그래서 실행을 끊는 것은 취소에 덧붙인 배려가 아니라 **취소의 일부**다.

`cancel` 과 별개로 `interrupt(taskId, principal)` 를 둔 것은 용어집의 구분을 그대로 따른 것이다 —
**중단**은 돌고 있는 것을 끊고, **취소**는 그것 자체를 그만둔다. 한 번의 나쁜 실행에 물린 태스크는
전자가 필요하지 실행 스케줄까지 지울 일이 아니다.

### 12.2 전파 사다리

턴의 iteration 경계에 해당하는 것이 루틴에서는 **스텝 경계**다.

| 층 | 무엇을 보장하는가 |
|----|------------------|
| 스텝 경계 게이트 | 이후 스텝은 **시작되지 않는다**. 도구가 중단을 전혀 모르는 경우에도 성립하며, 아래 둘은 이것의 최적화다 |
| `COOPERATIVE` 스텝 | `ToolContext` 의 `CANCELLATION_SIGNAL` 을 폴링해 스텝 *안*에서 조기 반환 |
| `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED` 스텝 | terminator 로 그 자리에서 종료 |

`THREAD_INTERRUPT` 의 terminator 가 `SingleToolInvoker` 와 다른 점 하나 — 루틴 스텝은 호출 스레드가
아니라 timeout 풀 워커에서 돌므로, "도구 스레드를 인터럽트한다" 는 것이 여기서는
`future.cancel(true)` 다. 등록은 submit **뒤에** 하는데, 이미 trip 된 코디네이터에 등록하면
terminator 가 등록 스레드에서 즉시 발화한다는 registrar 계약이 그 순서를 안전하게 만든다.

### 12.3 재시도 백오프 — 아무것도 안 돌고 있는 구간

스텝 사이는 갇히기 가장 쉬운 자리다. 폴링할 도구도, 종료할 future 도 없으므로 평범한
`Thread.sleep` 은 취소된 실행을 남은 지연만큼 붙잡는다. 지연이 분 단위로 설정되는 것을 감안하면
"멈춘다" 와 "언젠가 멈춘다" 의 차이다. 그래서 백오프는 `CountDownLatch.await` 이고 래치를 내리는
것은 신호 자신의 리스너다 — 이미 trip 된 신호에 등록하면 리스너가 동기 실행되므로 "먼저 도착한
인터럽트" 를 위한 별도 분기가 필요 없다.

### 12.4 결과는 실패가 아니다

스텝 안에서 인터럽트를 맞으면 그것은 **평범한 스텝 실패로 도착한다** — 종료된 도구도 다른 도구와
똑같이 error 를 반환하기 때문이다. 신호를 실패보다 **먼저** 읽지 않으면 멈춰 세운 실행이 고장 난
실행으로 기록되고, 아무 잘못도 없는 태스크에 결함이 붙는다.

| | 실행 결과 | 이력 status | 이벤트 |
|---|---|---|---|
| 정상 | `isSuccess()` | `SUCCESS` | `TaskCompletedEvent` |
| 스텝 고장 | `isSuccess()==false` | `FAILURE` / `PARTIAL` | `TaskFailedEvent` |
| 중단 | `isCancelled()` + `InterruptReason` | `CANCELLED` | `TaskInterruptedEvent` |

`CANCELLED` 를 `PARTIAL` 과 합치지 않은 이유가 마지막 줄이다. 둘 다 마지막 스텝에 못 미쳐 끝나지만
태스크에 대해 무언가를 말하는 것은 한쪽뿐이며, 합치면 **누가 멈춘 태스크가 자기 이력에서 간헐적으로
실패하는 태스크와 똑같이 읽힌다**. `TaskInterruptedEvent` 를 `TaskCancelledEvent` 와 나눈 것도 같은
이유다 — 후자는 *스케줄이 사라졌다*, 전자는 *실행 하나가 멈췄다* 이고, 중단만 했을 때 스케줄은
그대로 남는다.

### 12.5 취소가 지운 것을 실행이 되살리지 못하게

멈추는 것만으로는 부족했다. `ScheduledTaskManager.executeTask` 는 발화 시점에 태스크를 읽고 실행이
끝나면 되쓰므로, 그 사이에 `cancel` 이 지운 레코드를 **되살린다**. 남는 것은 낡은 행보다 나쁘다 —
unschedule 되어 다시는 발화하지 않는데 목록에는 보이고 id 로도 찾히며, 쿼터는 이미 환급된 유령
태스크다.

`cancel` 안의 순서로는 못 막는다. 실행의 쓰기는 **언제나 나중**이기 때문이다. 그래서 보장은 반대편,
쓰기 쪽에 둔다 — `ScheduledTaskRepository.updateIfPresent` 는 저장돼 있는 것만 교체하고 지워진 것은
되살리지 않는다. 검사와 쓰기가 **원자적**이어야 한다는 것이 그 메서드의 계약이고, `default` 를 주지
않은 이유이기도 하다(`findById` 후 `save` 는 같은 창을 좁힐 뿐 닫지 않는다).

두 리포지토리에 공유 트랜잭션이 없으므로 이력 쪽에는 창이 남는다 — 게이트를 통과한 직후 `cancel` 이
들어오면 존재하지 않는 태스크의 이력 행이 남는다. 그 행은 **도달 불가능**하고(`getHistory` 는
`getById` 로 인가하므로 태스크가 없으면 던진다) 틀린 답이 아니라 누수이므로, 쓰기 직후 태스크가
사라졌으면 쓸어낸다.

### 12.6 shutdown 도 취소다

`RoutineExecutor.shutdown()` 은 풀을 재우기 **전에** 진행 중인 실행을 전부 `SYSTEM_SHUTDOWN` 으로
trip 한다. 멈추라는 말을 듣지 못한 스텝은 멈출 수 없으므로, 그러지 않으면 30초 유예는 그냥
흘러가고 매번 `shutdownNow()` 로 떨어진다.

### 12.7 노드를 넘는 취소 — `ScheduledTaskInterruptBus`

in-flight 레지스트리는 그 인스턴스의 것이다. 그래서 `RoutineExecutor.interrupt` 는 같은 JVM 의 실행만
끊을 수 있는데, 스케일아웃에서 사용자가 취소를 넣는 노드는 대개 cron 이 발화한 노드가 아니다. 그대로
두면 저쪽 실행은 방금 삭제된 태스크를 위해 남은 스텝을 끝까지 돌린다 — 파일을 쓰고, 외부 시스템을
호출하면서.

전파되는 것은 **신호가 아니라 사건**이다(§11). `CancellationSignal` 은 실행당 메모리 객체로 남고,
와이어를 건너는 것은 "누가 멈추라고 했다" 는 사실뿐이다. 그래서 SPI 는 코디네이터가 아니라
`(ScheduledTaskId, InterruptReason)` 을 나른다.

**세션 버스를 재사용하지 않은 이유.** 조인 키가 `SessionId` 가 아니라 `ScheduledTaskId` 다 — 스케줄
루틴은 세션이 아예 없는 실행이므로(§12, [`glossary.md` §4](../../overview/glossary.md)) `SessionSignalBus`
를 쓰려면 세션이 아닌 것을 가리키는 `SessionId` 를 발급해야 하고, 그것은 session-first 개편이 없앤
바로 그 혼동이다. 대신 `ScheduledExecutionGuard` 의 모양을 따랐다 — 같은 패키지의 인터페이스,
in-memory 구현, 분산 전환은 리팩토링이 아니라 구현체 교체.

| 구현 | 도달 범위 |
|------|----------|
| `ScheduledTaskInterruptBus.LOCAL_ONLY` (기본값) | 이 노드. 단일 노드에서는 로컬 `interrupt` 가 이미 전부이므로 팬아웃할 곳이 없다 |
| `InMemoryScheduledTaskInterruptBus` | 이 JVM. 한 프로세스 안의 엔진 여럿, 그리고 브로커 없이 계약을 테스트하는 자리 |
| 분산 구현 | 클러스터. 애플리케이션이 빈/스펙으로 주입한다 |

**발행은 매니저가, 구독은 엔진이 한다.** 둘을 갈라 놓은 것이 설계의 핵심이다 — `RoutineExecutor` 가
스스로 발행까지 하면 그것의 `shutdown()` 이 여기 있는 실행을 전부 끊으면서 그 사실을 클러스터에
방송해, 종료하지도 않는 노드의 실행을 멈추게 된다. 그래서 `SchedulingEngine` 이 구독자이고
(`close()` 가 구독을 먼저 반납한다), 발행은 사용자 의사가 있는 자리 —
`ScheduledTaskManager.cancel` / `.interrupt` — 에서만 일어난다.

전달은 at-least-once 이고 **발행한 노드로 되돌아올 수 있다**. 핸들러가 하는 일은 이미 트립된 신호를
다시 트립하는 것뿐이라 무해하며, 에코를 없애려 들면 in-memory 구현이 실제 브로커보다 관대해져 테스트가
덜 정직해진다.

**`interrupt` 의 반환값이 뜻하는 것이 좁아졌다.** `ScheduledTaskManager.interrupt` 의 `false` 는 이제
"아무것도 안 멈췄다" 가 아니라 **"이 노드에는 돌고 있는 것이 없었다"** 뿐이다. 팬아웃에는 돌려받을
답이 없기 때문이며, `ScheduledTaskCrossNodeInterruptTest` 가 이 구분을 테스트로 못박는다.

**실패해도 취소는 취소다.** 분산 구현에서 발행은 원격 I/O 이므로 던질 수 있다. 그때 이미 일어난
것들 — 로컬 실행 트립, 언스케줄, 곧 이어질 삭제 — 을 되돌리지 않는다. 브로커가 안 닿는다고 취소를
거부하는 것이 더 이상하기 때문이며, 대가는 저쪽 실행이 **늦게** 멈추는 것뿐이다. 그 늦은 정지도
§12.5 의 조건부 되쓰기가 노드와 무관하게 걸리므로 유령을 남기지는 않는다.

---

## 13. 의도적으로 제외한 것

- **모든 도구를 별도 `ExecutorService` 로 강제 오프로드** — 대부분의 도구는 수 ms 다. 전 호출을
  Future 로 감싸는 비용이 정당화되지 않는다. wrapper 는 `THREAD_INTERRUPT` 도구에만 적용한다.
- **도구 내부 상태 rollback** — 파일 쓰기나 외부 API 호출을 중단하면 불완전 상태가 남을 수 있다.
  이것은 도구 설계의 책임이지 중단 기계의 책임이 아니다.
- **인터럽트 이후 자동 재시도** — 사용자 의도(abort 인지 retry 인지)가 모호하므로 명시적 입력을
  요구한다.

## 14. 남은 것

IMPORTANT: **이 목록은 미룬 근거이지 현재 상태가 아니다.** 무엇이 아직 열려 있는지의 정본은
[`backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) 다 — 이 절은 설계 시점의
기록으로 두고, 항목이 닫히거나 근거가 틀린 것으로 드러나면 거기에 적는다
([`backlog/README.md`](../../backlog/README.md) 규칙 하나).

1. **병렬화 대상 확대 시 승격 규칙 재검토** — §8.5 의 전제.
2. **크로스 노드 취소의 분산 구현** — §12.7 로 SPI 는 났고 그 자리에 어떤 SPI 를 낼지도 정해졌지만,
   `aimon-core` 가 싣는 구현은 노드 하나(`LOCAL_ONLY`)와 JVM 하나
   (`InMemoryScheduledTaskInterruptBus`)까지다. 클러스터용은 지금은 애플리케이션이 쓴다.
   `aimon-session-{redis,postgres,mongodb}` 가 `SessionSignalBus` 에 대해 하는 것과 같은 모양의
   백엔드를 낼 수 있겠지만, 소비자가 나타나기 전까지는 만들지 않는다.
3. **크로스 노드 제출의 멀티모달** — §15.6 의 마지막 문단. `LiveSession` 은 `UserInput` 을 받지만
   `SubmitRequest` 는 아직 `String` 이다. 인코딩은 이미 있으므로(`JsonSessionSnapshotCodec` 의
   `userInput`) 인박스 와이어 포맷에 얹기만 하면 되고, 이것도 소비자가 나타나기 전까지는 하지 않는다.
4. **`SubmitOptions` 매핑의 사본 넷** — 되감기 지점이 그 옵션을 기억하게 되면서(§15.7) 공용
   `SubmitOptionsCodec` 이 생겼지만, 인박스 코덱 셋은 아직 각자의 사본을 들고 있다. 와이어 포맷은
   이미 같게 맞춰 두었으므로 Redis·Postgres 는 삭제로 끝나고, BSON 인 Mongo 만 별도 판단이 필요하다.

---

## 15. 재시도 — 되감고 다시 실행하기

중단된 턴은 흔적을 남긴다. 사용자 메시지, 그 앞에 주입된 합성 컨텍스트 블록, 멈추기 전에 나온 어시스턴트
출력, 그리고 도구 결과들 — "Interrupted — skipped" 로 채워진 것까지(§7). 그 흔적은 **일부러 남긴다**.
사용자가 그 일이 벌어지는 것을 봤기 때문이다.

하지만 그 위에 같은 요청을 다시 얹으면 안 된다. 모델에게 **"이미 반쯤 해 놓은 일"이라고 적힌 이력에서
그 일을 다시 하라**고 시키는 것이 되기 때문이다. 그래서 재시도는 먼저 흔적을 걷어낸다 —
`LiveSession.retryLastTurn()`.

### 15.1 경계를 어떻게 아는가

되감으려면 "이 턴이 어디서 시작했는가" 를 알아야 하는데, 전사에는 턴 경계가 없었다. **마지막 user
메시지를 찾는 휴리스틱은 이 코드베이스에서 틀린다** — `checkOnStartHooks` 의 훅 조언과 CTX-06 의 합성
컨텍스트 블록이 둘 다 `Message.user(...)` 로 들어가므로, 마지막 user 메시지가 턴 경계가 아니다.

그래서 경계를 **기록한다**. `SessionRewindPoint`(`agent.session.transcript`)는 턴이 시작하기 전의
메시지 개수와 그 턴을 시작한 `UserInput` 을 담는다. 후자를 인덱스로 찾지 않고 통째로 들고 있는 이유가
방금의 그것이다 — 사이의 메시지들이 role 로 구별되지 않는다.

담는 것이 `Message` 가 아니라 **요청 자체**인 것은 별개의 이유다. 실행기는 `UserInput` 을 받아
`UserInputConverter` 로 `Message` 를 만들고, 그 변환은 **되돌릴 수 없다** — 이미지는 자기 텍스트가 없어서
`[Image: image/png, 1024 bytes]` 라는 자리표시자로 읽히고, 그것을 다시 제출하면 그림에 대해 묻던 턴이
그림을 묘사한 문장에 대해 묻는 **다른 턴**이 된다. 요청을 들고 있으면 재시도가 같은 변환을 다시 태우므로
재구성할 것이 없고, 재생할 수 없는 입력 종류도 없다.

### 15.2 왜 전사 안에 두는가

`SessionTotals` 나 `budgetOverride` 처럼 레코드의 side field 로 둘 수도 있었지만 두지 않았다. **이 값은
메시지 목록에 대한 인덱스**이므로 메시지를 갈아치우는 쓰기를 넘어 살아남으면 안 된다. 컴팩션이 바로 그
쓰기다 — side field 였다면 `mergeFromSnapshot` 이 이전 값을 복원해, 자기가 세지 않은 이력을 가리키게 된다.

`SessionTranscript` 안에 두면 메시지와 **한 덩어리로** 교체되므로 그럴 수 없다. 부수 효과로 저장소 SPI 가
하나도 늘지 않는다 — `SessionSnapshot` → `mergeFromSnapshot` 이라는 기존 경로를 그대로 탄다.

### 15.3 표시와 지우기

| 시점 | 하는 일 |
|------|---------|
| 턴 시작 (`transcriptManager.initialize` 직후) | `beginTurn(userMessage)` — 합성 블록이 들어가기 **전**에 찍는다 |
| 턴 종료, `INTERRUPTED` 가 아님 | `endTurn()` — 지운다 |
| 턴 종료, `INTERRUPTED` | 아무것도 안 한다 — 남아 있는 것이 곧 "재시도 가능" |

지우기는 `saveSilently` **앞**에서 한다. 뒤에서 하면 그 사이에 죽은 프로세스가 완료된 턴을 중단된 것처럼
보이게 남긴다.

### 15.4 재시도는 평범한 턴이다

`retryLastTurn` 은 짧아진 이력을 **먼저 영속**시키고 나서 제출한다. 그래야 재시도가 또 중단돼도 흔적이
쌓이지 않고 같은 자리에서 다시 시작한다. 그 외에는 보통 턴과 같다 — 예산, 훅, 이벤트, 인터럽트 전부.

"재시도할 수 있나?" 를 먼저 묻는 술어는 **일부러 두지 않았다**. 묻는 순간과 하는 순간 사이에 답이 바뀔 수
있어서 그 위에 분기를 쓰면 경합이 된다. 빈 `Optional` 이 그 답이다 — `LiveSessionStatus` 를 제어 게이트로
쓰지 말라는 §4 의 규칙과 같은 이유다.

되감기 지점이 영속되므로 **핸들이 죽어도 재시도는 살아남는다**. 인터럽트는 프로세스가 사라지기 가장 쉬운
순간(SIGINT·축출·노드 이동)이므로, 핸들에만 있었다면 가장 필요할 때 없었을 것이다.

### 15.5 컴팩션이 이기고, 동시 턴은 거절된다

되감기 지점을 전사 안에 둔 것이 정확성 문제를 하나 없애 주지만(§15.2), **컴팩션이 그 전사를 다시 쓰는 것**은
여전히 처리해야 한다. `replaceWith` 는 메시지를 훨씬 적게 남길 수 있으므로, 세어 둔 개수가 더 이상 전사의
위치가 아니게 된다. 그래서 `replaceWith` 는 **지점을 버린다**. 그냥 두면 엉뚱한 자리로 되감는 정도가 아니라
— 개수는 전사를 재구성하는 자리에서 검증되므로 — 턴 종료 저장이 던지고, 그것을 `saveSilently` 가 삼켜서
**그 턴의 이력 전체가 말없이 사라진다**. 그 턴 하나를 재시도할 수 없게 되는 것이 정직한 대가다.

**턴이 도는 중의 되감기는 거절한다.** 될 수가 없기 때문이다 — 도는 턴은 자기 이력 사본을 들고 있다가 끝날 때
되쓰므로, 방금 걷어낸 흔적을 그대로 되돌려 놓는다. `DefaultLiveSession` 은 진행 중인 턴이 보이면
`IllegalStateException` 을 던진다. 검사 직후에 시작하는 턴까지 막지는 못하지만, 흔한 실수를 **조용히 아무것도
안 한 되감기** 대신 에러로 바꾼다. 먼저 인터럽트하고 나서 되감을 것.

### 15.6 멀티모달 요청도 재시도된다

이미지 하나만 보낸 턴, 첨부 파일이 붙은 턴, 텍스트와 스크린샷을 함께 보낸 턴도 재시도된다. 되감기 지점이
`UserInput` 을 들고 있으므로(§15.1) 재시도는 **같은 요청을 다시 제출**하는 것이고, 변환은 첫 시도 때와
똑같이 그 자리에서 다시 일어난다.

그러려면 `LiveSession` 이 `UserInput` 을 받을 수 있어야 했다. 그래서 `submit` / `submitAsync` /
`offerAsync` 에 `UserInput` 오버로드가 생겼다. 그 전에는 `LiveSession` 을 통해 멀티모달 턴을 **시작하는 것
자체가 불가능**했다 — 요청 빌더는 줄곧 `UserInput` 을 받고 있었는데 세션 파사드가 그것을 좁히고 있었다.

**필수 메서드는 여전히 `String` 쪽이다.** 세 개의 `String` 오버로드는 추상으로 남고, `UserInput` 오버로드가
그 위에 기본 구현으로 얹힌다 — `TextInput` 이면 벗겨서 `String` 메서드로 내려보내고, 그 밖이면
`UnsupportedOperationException` 이다. 방향을 이렇게 잡은 이유가 둘이다.

- **컴파일 타임 검사가 남는다.** 셋 다 기본 구현으로 바꿨더니 `LiveSession` 의 추상 메서드가
  `getSessionId()` 와 `close()` 둘만 남아, 제출 메서드를 하나도 구현하지 않은 세션이 **컴파일되고 런타임에
  터지는** 상태가 됐다.
- **텍스트는 어디서나 통한다.** `String` 오버로드만 구현한 옛 세션에서도 `submit(UserInput)` 이 동작한다.
  이것이 없으면 `retryLastTurn` 이 되감은 것이 **평범한 텍스트여도** 그런 세션에서 거절된다 — 되감기 지점은
  이제 언제나 `UserInput` 이기 때문이다.

납작하게 만들지 않는 것이 요점이다. 이미지를 `asText()` 자리표시자로 바꿔 제출하면 그림에 대해 묻던 턴이
그림을 묘사한 문장에 대해 묻는 다른 턴이 되므로, **못 한다고 말하는 쪽**이 낫다.

같은 이유가 큐에도 적용된다. **큐는 텍스트 채널이다** — 지연된 입력은 `<system-reminder>` 블록으로
재생되므로(`QueuedInput.getInputText()`) 이미지가 기다릴 수 있는 형태가 없다. 그래서 턴이 도는 중에 들어온
비텍스트 입력에 대해 `offerAsync` 는 **줄 수 있는 답이 하나도 없다**: 미룰 수 없고, 그렇다고 실행하면 도는
턴이 쓰고 있는 전사에 두 번째 턴을 얹는다 — busy 플래그가 막으려던 바로 그것이다. 그래서 조용히 틀린 쪽을
고르는 대신 `IllegalStateException` 으로 거절한다. 동시 실행을 **의도한** 호출자에게는 `submitAsync` 가
따로 있다. 이 저장소에서 이 분기에 닿는 코드는 없다 — REPL 은 한 번에 한 턴이고, 재시도는 되감기가 도는
턴을 먼저 거절하므로(§15.5) 도달할 수 없다.

**아직 텍스트인 경계 하나.** `aimon-session-routing` 의 크로스 노드 제출(`SubmitRequest.getUserInput()`)은
여전히 `String` 이고, 라우터는 재시도를 노출하지 않는다. 즉 멀티모달은 **핸들을 직접 쥔 호스트**(임베더,
CLI)의 것이고, 인박스를 거쳐 다른 노드로 넘어가는 제출은 텍스트다. 큐와 같은 이유는 아니다 — 큐는 재생
형식이 `<system-reminder>` 라 못 담는 것이고, 이쪽은 그냥 아직 넓히지 않은 것이다. 넓히려면 인박스
와이어 포맷에 입력 인코딩이 들어가야 하고(`JsonSessionSnapshotCodec` 의 `userInput` 과 같은 모양이면 된다),
그것은 소비자가 생겼을 때 할 일이다.

### 15.7 턴은 요청만이 아니라 **누가 어떤 맥락에서** 제출했는지까지다

입력을 기억하게 하고 나서 같은 축이 하나 더 남아 있는 것이 드러났다. 되감기 지점은 `UserInput` 을
기억했지만 그 턴의 `SubmitOptions` — principal, 시스템 프롬프트 변수, 실행 속성, LLM 호출 메타데이터,
user-context 주입 여부 — 는 기억하지 않았고, 무인자 `retryLastTurn()` 은 `SubmitOptions.empty()` 로
돌았다.

**결과가 조용하다.** principal 은 `ToolContextKeys.PRINCIPAL` 로 도구 컨텍스트에 실리고
`MemoryContextRequest` 로 메모리 조립에 들어간다. 즉 principal 을 싣고 제출한 턴이 중단되면 재시도는
같은 요청을 **다른 정체성으로** 돌렸다 — 도구는 요청자를 못 보고, 메모리는 다른 사람 것으로 조립된다.
이미지를 자리표시자로 납작하게 만들던 것과 같은 종류이고, 더 알아채기 어렵다.

그래서 되감기 지점이 옵션도 함께 든다. 무인자 `retryLastTurn()` 은 **원래 옵션으로** 다시 제출하고,
`retryLastTurn(옵션)` 은 그것을 갈아 끼운다 — 후자가 이 오버로드의 존재 이유다.
`rewindLastTurn()` 은 둘을 함께 담은 `RewoundTurn` 을 돌려준다. 입력만 받아 가면 같은 말을 다른
사람이 하는 것이 되기 때문에, 반쪽만 집어 가기 어려운 모양으로 만들었다.

**옵션을 요청에서 되짚지 않고 통째로 나른다.** `OrcaAgentExecutionRequest` 는 이미 다섯 값을 펼쳐서
갖고 있지만, 거기서 `SubmitOptions` 를 재구성할 수는 없다 — 그 시점엔 기본값이 적용된 뒤라
**"지정하지 않음" 과 "기본값으로 지정함" 이 구별되지 않는다.** `llmCallMetadata` 가 특히 그렇다:
지정하지 않으면 에이전트·세션에서 재도출되는데, 되짚어 재구성하면 재시도가 component 와 traceId 를
못 박아 버린다. 그래서 요청이 원본 `SubmitOptions` 를 따로 들고 다니며(`getSubmitOptions()`), 그
필드는 **실행에는 한 줄도 쓰이지 않는다** — 되감기 지점에 넘겨주기 위해서만 있다.

영속화는 이미 있던 것을 재사용한다. `SubmitOptions` 의 JSON 인코딩은 인박스용으로 redis·postgres·
mongodb 가 **각자 하나씩** 갖고 있었으므로, 네 번째 사본을 만드는 대신 `SubmitOptionsCodec` 으로
뽑고 필드 이름·모양을 기존 것과 똑같이 맞췄다. 그래서 남은 셋을 합치는 것은 마이그레이션이 아니라
삭제다(§14 의 4번). `Map<String, Object>` 두 개는 값이 JSON 스칼라로 왕복하고 커스텀 POJO 는 중첩
`Map` 으로 돌아온다 — `ToolUse` 의 입력 맵이 이미 지고 있는 것과 같은 대가이며, 숨기지 않고 적는다.

---

## 관련 소스

- `at/aimon/core/agent/interrupt/` — 신호·코디네이터·terminator·`CancellationSignals`·LLM 어댑터
- `at/aimon/core/agent/tool/InterruptToolKeys.java`, `InterruptAccess.java` — 컨텍스트 채널
- `at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java` — 경계 게이트와 스윕
- `at/aimon/core/agent/session/DefaultLiveSession.java` — 호스트 진입점과 NOW 리스너
- `at/aimon/core/tools/bash/BashTool.java` — 대표 `THREAD_INTERRUPT` 사례

계약을 못박는 테스트: `CancellationSignalsTest`(소비 규율),
`OrcaAgentExecutorInterruptFlagHygieneTest`(fail-open 우회 차단·승격·플래그 누출 없음),
`OrcaAgentExecutorBatchInterruptTest`(배치 게이트의 위치별 분기와 페어링 보존),
`DefaultLiveSessionInterruptTest`(NOW 도착 → 코디네이터 트립),
`DefaultHookExecutorTest`(인터럽트된 대기는 정책과 무관하게 BLOCKED · 끝난 훅은 판정 보존),
`TurnRetryIntegrationTest`(재시도가 흔적을 지운다 · 핸들을 다시 열어도 재시도된다 · 두 번 중단돼도 흔적이 쌓이지 않는다 ·
이미지로 시작한 턴이 이미지로 재생된다),
`LiveSessionUserInputTest`(텍스트는 `String` 메서드로 내려가고, 비텍스트는 납작해지지 않고 거절된다),
`SubmitOptionsCodecTest`(옵션이 왕복하고, 지정하지 않은 것은 지정하지 않은 채로 돌아온다).
