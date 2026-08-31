# Interruptible Tools Guide

> 사용자의 Ctrl+C, `QueuedInputPriority.NOW` 우선순위 메시지, 상위 에이전트의 취소 cascade 등 외부 중단 신호를 Tool이 어떻게 받아들이고 어떻게 반응할지 결정하는 가이드.

이 문서는 `at.aimon.core.agent.interrupt` 패키지의 공개 API를 Tool 작성자와 CLI 운영자 관점에서 설명합니다. 설계 근거와 이벤트 시퀀스는 [interrupt.md](../../design/agent-execution/interrupt.md)를 참고하세요.

## 목차

1. [개요](#개요)
2. [아키텍처](#아키텍처)
3. [공개 API 표면](#공개-api-표면)
4. [InterruptBehavior 선택 가이드](#interruptbehavior-선택-가이드)
5. [구현 패턴](#구현-패턴)
6. [운영자 관점 — Ctrl+C UX](#운영자-관점--ctrlc-ux)
7. [NOW 큐 우선순위 예](#now-큐-우선순위-예)
8. [테스트 레시피](#테스트-레시피)
9. [디자인 원칙](#디자인-원칙)

---

## 개요

AIMON의 ReAct 루프는 도중에 다음과 같은 이유로 중단될 수 있습니다.

| 원인 | `InterruptReason` | 트리거 위치 |
|------|-------------------|--------------|
| 사용자 Ctrl+C (CLI) | `USER_SIGINT` | `ReplSession` JLine SIGINT 핸들러 |
| NOW 우선순위 입력 mid-turn 투입 | `NOW_PRIORITY_INPUT` | `DefaultLiveSession#onQueueEvent` |
| 예산(iteration / token / wall-clock) 소진 | `BUDGET_EXCEEDED` | `OrcaAgentExecutor` 예산 가드 |
| 상위 에이전트의 cancel cascade | `PARENT_CANCELLED` | 부모 세션 → 자식 세션 |
| 스케줄 태스크 취소, 또는 실행만 중단 | `TASK_CANCELLED` | `ScheduledTaskManager#cancel` · `#interrupt` |
| 호스트 런타임 종료 | `SYSTEM_SHUTDOWN` | JVM shutdown hook, 매니지드 셧다운 |
| 세션 리스 갱신 실패 | `LEASE_LOST` | 멀티 노드 라우팅 계층 |
| 외부에서 세션을 명시적으로 릴리스 | `SESSION_RELEASED` | `SessionRouter` |
| 홀더 유실 감지 후 다른 노드가 인수 | `HOLDER_LOST` | 라우팅 계층의 holder-loss sweeper |

어떤 원인이든 중단 신호는 **단 하나의 경로** — 현재 실행의 `CancellationSignal` 트립 — 으로 도구 계층에 도달합니다. 표의 다섯째 줄이 그 "실행" 이 언제나 턴은 아니라는 증거입니다: 스케줄 루틴은 세션도 턴도 없이 도는 실행이고, 같은 신호 기계로 끊깁니다([interrupt.md §12](../../design/agent-execution/interrupt.md)). Tool 작성자는 `InterruptBehavior`를 선언해 "이 신호를 어떻게 해석해 달라"를 executor에게 알리고, 필요하다면 `InterruptAccess`로 신호를 직접 관찰합니다.

기본 동작은 `InterruptBehavior.NON_INTERRUPTIBLE` 입니다. Tool이 이 선언을 오버라이드하지 않으면 **진행 중인 실행은 끝까지 수행**되고, 중단은 다음 iteration 경계에서야 반영됩니다. 이것이 stat/read 같은 짧은 원자적 연산에는 오히려 안전한 기본값입니다.

## 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│  외부 트리거                                                │
│    REPL SIGINT handler / DefaultAgentSession NOW listener /  │
│    Budget guard / parent cascade / shutdown hook             │
└────────────────────────┬────────────────────────────────────┘
                         │ AgentSession#interrupt(InterruptReason)
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  InterruptCoordinator (per-turn)                            │
│   ─ 현재 턴의 CancellationSignal을 trip(reason)             │
│   ─ 등록된 Terminator들을 registration 순서로 1회 호출      │
└────────────────────────┬────────────────────────────────────┘
                         │
             ┌───────────┴────────────┐
             ▼                        ▼
  CancellationSignal          TerminatorRegistrar
  (ToolContext 에 주입)       (THREAD_INTERRUPT /
                               EXTERNALLY_TERMINATED 전용)
             │                        │
             ▼                        ▼
  Tool.execute(input, context)  ← InterruptAccess.signalOf / registrarOf
```

- **Coordinator**는 턴마다 새 `CancellationSignal`을 만들고, 신호는 single-shot입니다 (한번 트립되면 해당 턴 내내 유지, 다음 턴에서 새로 발급).
- **Tool**은 executor가 주입한 `ToolContext`에서 `InterruptAccess.signalOf(context)`로 신호를 얻습니다. context에 신호가 없으면 `NoopCancellationSignal` 싱글톤이 반환되므로 null 검사 없이 안전하게 `isCancelled()` / `checkpoint()`를 호출할 수 있습니다.
- **Registrar**는 `THREAD_INTERRUPT`·`EXTERNALLY_TERMINATED` 동작을 선언한 Tool에만 공급되며, Tool이 `close()` 책임을 져야 누수되지 않습니다 (executor가 tool 실행 종료 시 자동 close하지만, tool이 조기 return하더라도 `registrar.unregister(...)`로 정리하는 것이 안전합니다).

## 공개 API 표면

### `InterruptBehavior` (enum, 4 값)

| 값 | 의미 | Executor 동작 | 대표 사용처 |
|----|------|---------------|-----------|
| `NON_INTERRUPTIBLE` | 중단 신호 무시 | 신호를 전달하지 않음. 다음 iteration 경계에서만 중단 반영 | 파일 stat, in-memory 읽기, 캐시 조회 |
| `COOPERATIVE` | Tool이 폴링해서 조기 종료 | `CancellationSignal`만 트립; 스레드는 건드리지 않음 | `GrepTool`, `WebFetchTool` — 반복 루프/단계 경계에서 polling |
| `THREAD_INTERRUPT` | Tool 스레드에 `Thread.interrupt()` 허용 | 신호 트립 + pre-registered `Thread.interrupt()` 터미네이터 실행 | `BashTool` — `Future.get(timeout)`에서 블로킹 |
| `EXTERNALLY_TERMINATED` | 외부 핸들(Process/Future)을 Tool이 직접 등록 | Tool이 등록한 `Terminator`들을 신호 트립 시 1회 호출 | 서브 프로세스 관리자, 서브에이전트 실행 |

`Tool.getInterruptBehavior()`는 default로 `NON_INTERRUPTIBLE`을 반환합니다 — 기존 Tool 호환성을 깨지 않기 위한 선택.

### `InterruptReason` (enum, 9 값)

위 [개요](#개요) 표 참고. Tool은 reason을 **관측 용도**로만 사용합니다 — 동작 자체는 "취소됐다"만 알면 충분하고, 메시지 포맷/로깅에 reason을 포함해 사용자가 원인을 구분할 수 있게 돕습니다.

```java
String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
return ToolResult.error("Grep interrupted: " + reason);
```

### `CancellationSignal` (읽기 전용 뷰)

```java
public interface CancellationSignal {
    boolean isCancelled();
    Optional<InterruptReason> getReason();
    void checkpoint();               // 트립 상태면 CancelledExecutionException 던짐
    void onCancel(Runnable listener); // 트립 시 호출 (이미 트립이면 즉시 실행)
}
```

- **스레드 안전**: 모든 메서드는 어느 스레드에서도 호출 가능.
- **Single-shot**: 트립 상태는 턴 끝까지 유지. reset 없음.
- **Listener 순서**: 등록 순서 그대로 트립 시 실행. 이미 트립된 이후 등록하면 등록 스레드에서 즉시 실행.

### `TerminatorRegistrar` (Tool → coordinator write path)

```java
public interface TerminatorRegistrar extends AutoCloseable {
    void register(Terminator terminator);
    void unregister(Terminator terminator);
    @Override void close();
}
```

- `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED` 선언 Tool에만 주입됩니다.
- 트립 이후 `register()`하면 **즉시** 호출됩니다 (race 안전).
- `close()` 이후 `register()`는 `IllegalStateException` 던짐 — 등록은 tool 실행 범위 내에서만.
- Terminator는 **반드시 idempotent + non-blocking + non-throwing** 이어야 합니다.

### `InterruptAccess` (Tool 관점 헬퍼)

```java
public final class InterruptAccess {
    public static CancellationSignal signalOf(ToolContext context);       // 절대 null 아님
    public static Optional<TerminatorRegistrar> registrarOf(ToolContext context);
}
```

`signalOf`는 context에 신호가 없어도 `NoopCancellationSignal.INSTANCE`를 반환합니다. 따라서 unit test나 CLI에서 직접 호출된 diagnostic tool도 `signal.isCancelled()`를 호출해도 안전합니다.

`registrarOf`는 `Optional`입니다 — `NON_INTERRUPTIBLE` / `COOPERATIVE` 도구는 registrar를 받지 않습니다. 실수로 terminator를 등록하려 하면 `Optional.empty()`가 되어 조용히 무시되는 형태로 계약을 강제합니다.

## InterruptBehavior 선택 가이드

다음 flowchart로 자기 Tool에 맞는 값을 고르세요.

```
Tool.execute()가 평균 얼마나 걸리는가?
│
├── < 100ms (파일 stat, in-memory 계산, 로컬 캐시 조회)
│   └─► NON_INTERRUPTIBLE
│       (중단해도 얻는 이익이 없고 경쟁 조건 리스크만 큼)
│
└── >= 100ms 또는 I/O 포함
    │
    ├── 내부에 반복 루프/단계 경계가 있는가?
    │   (여러 파일 scan, 다단 파이프라인, 페이지네이션 …)
    │   │
    │   ├── YES
    │   │   └─► COOPERATIVE
    │   │       (각 반복 / 단계 앞에서 signal.isCancelled() 체크 후 ToolResult.error로 조기 종료)
    │   │
    │   └── NO — 하나의 블로킹 호출에 걸림
    │       │
    │       ├── 외부 프로세스 / 서브에이전트 / Future를 Tool이 명시적으로 보유하는가?
    │       │   │
    │       │   ├── YES — 명시적 kill 핸들이 더 효과적
    │       │   │   └─► EXTERNALLY_TERMINATED
    │       │   │       (TerminatorRegistrar에 process.destroy / future.cancel(true) 등록)
    │       │   │
    │       │   └── NO — 블로킹 API가 InterruptedException을 throw하는 성질
    │       │       └─► THREAD_INTERRUPT
    │       │           (executor가 pre-register한 Thread.interrupt() 터미네이터에 기대면 됨)
```

실례:

- `ReadTool` — 단일 파일 read, 평균 수 ms → `NON_INTERRUPTIBLE`
- `GrepTool` — 디렉토리 walk + 파일마다 scan → `COOPERATIVE`
- `WebFetchTool` — cache → fetch → extract 3단계 → `COOPERATIVE` (단계 경계마다 polling)
- `BashTool` — `Future.get(timeout)` 단일 블로킹 + kill handle 있음 → `THREAD_INTERRUPT` (+ registrar로 `future.cancel(true)` 이중 등록, belt-and-suspenders)

## 구현 패턴

### 패턴 1 — COOPERATIVE (`GrepTool` 스타일)

루프 경계, 단계 경계, 또는 배치 경계에서 signal을 폴링해서 조기 종료합니다.

```java
public class GrepTool extends AbstractTool {

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final CancellationSignal signal = InterruptAccess.signalOf(context);

        try {
            final String pattern = input.getRequiredString("pattern");
            // ... 파라미터 추출 / 검증 ...

            // 작업 전 한 번
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }

            final List<String> files = discoverFiles(path);

            // 루프 내부 — 배치 경계에서 폴링
            final List<SearchResult> results = new ArrayList<>();
            for (String file : files) {
                if (signal.isCancelled()) {
                    return interruptedResult(signal);
                }
                results.addAll(searchFile(file, pattern));
            }

            return ToolResult.success(formatOutput(results));

        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    private ToolResult interruptedResult(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        return ToolResult.error("Grep interrupted: " + reason);
    }
}
```

**체크포인트 빈도**는 **"한 번 폴링 후 유저 입장에서 참을 수 있는 최대 레이턴시"** 로 정합니다 — 너무 조밀하면 핫 루프 오버헤드, 너무 성기면 Ctrl+C 반응이 느립니다. 파일 단위, 100ms 블록 단위, 페이지 단위가 일반적인 타협점입니다.

`signal.checkpoint()`를 쓰면 `CancelledExecutionException`을 던져 stack propagation으로 풀리는 설계도 가능하지만, Tool은 **`ToolResult.error`를 반환해야 한다**는 큰 계약이 있으므로 외부 `catch (CancelledExecutionException)` 블록에서 반드시 `ToolResult.error`로 감싸주세요.

### 패턴 2 — THREAD_INTERRUPT (`BashTool` 스타일)

블로킹 호출이 `InterruptedException`을 throw하는 경우 적합합니다. Executor가 `Thread.interrupt()`를 pre-register하므로 Tool은 `InterruptedException` / `CancellationException`을 잡아서 깔끔히 풀기만 하면 됩니다.

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    Objects.requireNonNull(input, "Input cannot be null");
    Objects.requireNonNull(context, "Context cannot be null");

    try {
        final String command = input.getRequiredString("command");
        final Future<String> future = executorService.submit(() -> bashExecutor.execute(command));

        // 이중 보호: Future.cancel(true)를 terminator로도 등록.
        // 1) Thread.interrupt()  → future.get()이 InterruptedException으로 깨어남
        // 2) future.cancel(true) → 실제 shell process까지 종료
        final Optional<TerminatorRegistrar> registrar = InterruptAccess.registrarOf(context);
        final Terminator cancelFuture = () -> future.cancel(true);
        registrar.ifPresent(r -> r.register(cancelFuture));

        try {
            final String output = future.get(timeout, TimeUnit.MILLISECONDS);
            return ToolResult.success(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 인터럽트 상태 복원
            future.cancel(true);
            return interruptedResult(InterruptAccess.signalOf(context));
        } catch (CancellationException e) {
            // terminator가 future.cancel(true)를 친 경우
            return interruptedResult(InterruptAccess.signalOf(context));
        } finally {
            registrar.ifPresent(r -> r.unregister(cancelFuture));
        }

    } catch (Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ToolResult.error("Unexpected error: " + e.getMessage());
    }
}

@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.THREAD_INTERRUPT;
}
```

- **`Thread.currentThread().interrupt()` 복원**은 필수입니다 — 이후 framework 쪽 호출이 인터럽트 상태를 확인할 수 있도록.
- **`finally`에서 `unregister`** 하세요. close 책임은 executor가 지지만, 오래 사는 registrar를 재사용하는 테스트 구성에서 누수를 막아줍니다.

### 패턴 3 — EXTERNALLY_TERMINATED

서브 프로세스 관리자나 서브에이전트처럼 **명시적인 kill handle**이 있는 Tool은 `Thread.interrupt()`보다 직접 핸들 호출이 더 효과적입니다.

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    final Optional<TerminatorRegistrar> registrar = InterruptAccess.registrarOf(context);

    Process process = new ProcessBuilder("long-running-tool", "--foo").start();
    final Terminator killTerminator = () -> process.destroy();
    registrar.ifPresent(r -> r.register(killTerminator));

    try {
        final int exitCode = process.waitFor();
        return exitCode == 0 ? ToolResult.success(readStdout(process))
                : ToolResult.error("Process exited with code " + exitCode);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return interruptedResult(InterruptAccess.signalOf(context));
    } finally {
        registrar.ifPresent(r -> r.unregister(killTerminator));
    }
}

@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.EXTERNALLY_TERMINATED;
}
```

**Terminator 계약** (`Terminator.java` Javadoc 기준):

- Idempotent — coordinator는 트립당 1회 호출하지만 Tool 자신이 또 호출하는 경우가 많습니다 (e.g., timeout 처리).
- Non-blocking — coordinator의 signalling 스레드를 잡아두면 다른 terminator / listener가 지연됩니다.
- Non-throwing — 예외 던지면 coordinator의 iteration이 끊어집니다. 자체 로깅하고 삼키세요.

### 패턴 4 — NON_INTERRUPTIBLE (기본값)

명시적으로 선언할 필요 없음. `getInterruptBehavior()`를 오버라이드하지 않으면 자동으로 `NON_INTERRUPTIBLE`입니다.

```java
// ReadTool의 실제 선언
@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.NON_INTERRUPTIBLE;
}
```

선언을 **명시적으로** 넣어주는 것을 추천합니다 — 리뷰어가 "이 Tool은 중단 신호를 무시하도록 의도됐구나"를 코드에서 바로 읽을 수 있습니다.

## 운영자 관점 — Ctrl+C UX

CLI REPL에서 Ctrl+C가 눌리면 다음이 순서대로 일어납니다 (`ReplSession#requestInterruptWithFallback` 참고, `modules/aimon-cli/src/main/java/at/aimon/cli/repl/ReplSession.java`).

```
┌─────────────────────────────────────────────────────────────┐
│  1. JLine SIGINT handler가 session.interrupt(USER_SIGINT)   │
│     호출 → CancellationSignal이 즉시 트립.                  │
├─────────────────────────────────────────────────────────────┤
│  2. 진행 중인 Tool의 InterruptBehavior에 따라:              │
│     - COOPERATIVE: 다음 체크포인트에서 ToolResult.error     │
│     - THREAD_INTERRUPT: Thread.interrupt() 발사             │
│     - EXTERNALLY_TERMINATED: 등록된 terminator 호출         │
│     - NON_INTERRUPTIBLE: 이번 execute는 끝까지 수행         │
├─────────────────────────────────────────────────────────────┤
│  3. 최대 500ms grace period 내에 Tool이 풀리면 OrcaAgent-   │
│     Executor가 CompletionReason.INTERRUPTED로 턴 종료.      │
├─────────────────────────────────────────────────────────────┤
│  4. Grace 안에 안 풀리면 REPL이 future.cancel(true)로       │
│     hard cancel — Non-interruptible tool이 10분짜리 stat    │
│     을 돌리는 비상 상황에서도 프롬프트는 돌아옴.            │
├─────────────────────────────────────────────────────────────┤
│  5. OutputFormatter가 "[Interrupted] Execution interrupted" │
│     배너를 렌더 (빨간 "Error:" 접두사는 붙지 않음 —         │
│     사용자가 의도해서 끊은 것이므로).                       │
└─────────────────────────────────────────────────────────────┘
```

grace period (500ms)는 `ReplSession.SIGINT_FALLBACK_GRACE_MS` 상수에 정의되어 있습니다. cooperative 경로가 정상 동작할 때는 거의 0ms 안에 Tool이 풀리므로 사용자는 지연을 체감하지 않습니다. 500ms를 넘기는 경우는 일반적으로:

1. `NON_INTERRUPTIBLE` Tool이 예상보다 오래 걸림 → hard cancel로 풀림.
2. `COOPERATIVE` Tool인데 체크포인트가 너무 드문드문 박혀 있음 → **Tool을 리팩토링해 체크포인트를 조밀하게** 하세요.
3. `THREAD_INTERRUPT` Tool이 `InterruptedException`을 catch하고 복구 대신 재시도 루프를 돌림 → Tool 버그입니다.

### 중단된 턴의 렌더링

`OutputFormatter#displayResult`는 `CompletionReason.INTERRUPTED`를 감지해 전용 분기를 탑니다 (`OutputFormatter.java`).

```
[Interrupted] Execution interrupted

[Interrupted after 2 iteration(s)]     ← settings.showIterations=true 일 때만
```

- 일반 실패와 달리 빨간 `Error:` 접두사가 **없음** — 사용자가 의도해서 끊었기 때문.
- Iteration count는 진단용 보조 정보, 기본은 off.
- 대화 기록(`SessionSnapshot`)에는 INTERRUPTED로 끝난 턴도 포함되므로, 다음 턴에서 "방금 Ctrl+C로 끊은 그 작업 말고 이걸 해줘"처럼 연속성 있게 지시할 수 있습니다.

### `/retry` — 끊은 턴을 다시 실행

```
> 인시던트 정리해줘
  ⋯ (Ctrl+C)
[Interrupted] Execution interrupted

> /retry
[retrying] 인시던트 정리해줘
  ⋯
```

중단된 턴이 **이력에 남는 것**은 위에서 본 대로 의도된 것이지만, 그 위에 같은 요청을 다시 얹으면 안 됩니다 —
모델에게 "이미 반쯤 해 놓았다" 고 적힌 이력에서 그 일을 다시 하라고 시키는 것이 되기 때문입니다. `/retry` 는
그래서 **먼저 흔적을 걷어내고**(사용자 메시지, 그 앞의 합성 컨텍스트 블록, 멈추기 전의 어시스턴트 출력,
skipped 로 채워진 도구 결과) 원래 시작한 자리에서 다시 실행합니다.

- **마지막 턴이 `INTERRUPTED` 일 때만** 동작합니다. 아니면 `Nothing to retry —` 한 줄을 내고 아무것도 실행하지
  않습니다.
- 재시도된 턴은 **보통 턴과 완전히 같습니다** — 스트리밍도, Ctrl+C 도 그대로 듣습니다. 방금 끊은 그 턴이므로
  두 번째로 끊을 수 없다면 가장 곤란한 자리에서 그 기능을 잃는 셈입니다.
- 재시도가 또 끊겨도 흔적이 **쌓이지 않습니다**. 되감기가 제출보다 먼저 영속되므로 같은 자리에서 다시 시작합니다.
- 되감기 지점이 전사와 함께 영속되므로 **CLI 를 껐다 켜도** 그 세션은 여전히 재시도할 수 있습니다.
- **어떤 입력으로 시작한 턴이든** 재시도됩니다. 되감기 지점이 그 턴을 시작한 `UserInput` 을 그대로 들고 있으므로,
  이미지·첨부 파일·멀티모달 요청도 텍스트 요약이 아니라 **원본 그대로** 다시 제출됩니다.
- 재시도는 **원래 턴과 같은 조건에서** 돕니다. 되감기 지점이 그때의 `SubmitOptions` 도 함께 들고 있어서,
  principal 이나 시스템 프롬프트 변수를 실어 제출한 턴은 재시도에서도 같은 것을 답니다.

프로그램에서 쓸 때는 `LiveSession.retryLastTurn(...)`(되감고 제출까지) 또는 `rewindLastTurn()`(되감고 입력만
돌려받기 — `Optional<UserInput>`)입니다. 후자를 그대로 다시 제출하려면 `submit(UserInput, SubmitOptions)` 또는
`offerAsync(UserInput, ...)` 를 씁니다. 설계 근거는
[`interrupt.md` §15](../../design/agent-execution/interrupt.md).

## NOW 큐 우선순위 예

사용자가 Tool 실행 중에 mid-turn 정정 입력을 넣는 시나리오입니다.

```
시점 T0: Agent가 WebFetch(url1) 시작. (COOPERATIVE, 수 초 소요)
시점 T1: 사용자가 "잠깐, url2로 해줘" 입력 →
         MessageQueueManager#enqueue(NOW priority, ctx=main).
시점 T2: DefaultAgentSession#onQueueEvent가 NOW 우선순위 + 동일 컨텍스트
         감지 → interrupt(InterruptReason.NOW_PRIORITY_INPUT).
시점 T3: WebFetchTool이 다음 단계 경계에서 signal.isCancelled() 확인 →
         ToolResult.error("WebFetch interrupted: NOW_PRIORITY_INPUT") 반환.
시점 T4: Orca가 현재 턴을 INTERRUPTED로 종료.
시점 T5: 다음 턴 시작 시 Orca가 큐에서 drain된 "url2로 해줘"를
         system-reminder로 시드해 에이전트가 url2 작업을 시작.
```

Producer (CLI or another agent) 측 코드는 간단합니다.

```java
QueuedInput preempt = QueuedInput.builder()
    .inputText(userMessage)
    .priority(QueuedInputPriority.NOW)                 // ← mid-turn preempt
    .agentExecutionContextId(currentContextId)  // ← 반드시 현재 턴의 ctx id
    .metadata(Map.of("origin", "repl-correction"))
    .build();

messageQueueManager.enqueue(preempt);
```

- `QueuedInputPriority.NEXT`를 쓰면 **현재 턴은 그대로 진행**되고 턴 끝에서 새 유저 메시지로 흡수됩니다 — 무해한 추가 지시에 적합.
- `QueuedInputPriority.NOW`를 쓰면 **현재 턴을 즉시 선점**합니다 — "그 방향이 아니라"처럼 현재 진행을 멈춰야 하는 정정에만 써야 합니다. 남용하면 에이전트가 같은 작업을 반복 시작/중단합니다.
- NOW 이벤트는 **컨텍스트 id가 일치**할 때만 인터럽트를 발사합니다. 메인 에이전트가 서브 에이전트 컨텍스트에 NOW를 넣어도 메인은 건드리지 않습니다.

## 테스트 레시피

### 레시피 1 — COOPERATIVE Tool의 조기 종료 검증

```java
@Test
void grepTool_interruptsBetweenFiles() {
    VirtualFileSystem vfs = new InMemoryVfsWith(
            "/a.txt", largeContent(), "/b.txt", largeContent(), "/c.txt", largeContent());
    GrepTool tool = new GrepTool(vfs);

    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    CancellationSignal signal = coordinator.getSignal();   // per-turn fresh signal
    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, signal)
            .build();

    // 첫 파일 scan 중간에 인터럽트를 발사하도록 별도 스레드에서 trip.
    CompletableFuture.runAsync(() -> {
        sleep(Duration.ofMillis(50));
        coordinator.requestInterrupt(InterruptReason.NOW_PRIORITY_INPUT);
    });

    ToolResult result = tool.execute(
            ToolInput.of(Map.of("pattern", "TODO", "path", "/")),
            context);

    assertThat(result.isError()).isTrue();
    assertThat(result.getContent())
        .contains("Grep interrupted")
        .contains("NOW_PRIORITY_INPUT");
}
```

### 레시피 2 — THREAD_INTERRUPT Tool의 InterruptedException 경로

```java
@Test
void bashTool_honorsThreadInterrupt() throws Exception {
    BashTool tool = new BashTool(executor, /* background */ null);

    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    CancellationSignal signal = coordinator.getSignal();
    TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, signal)
            .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar)
            .build();

    // Pre-register Thread.interrupt() terminator (executor가 실제 런타임에서 하는 일).
    Thread worker = Thread.currentThread();
    registrar.register(worker::interrupt);

    CompletableFuture.runAsync(() -> {
        sleep(Duration.ofMillis(100));
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
    });

    ToolResult result = tool.execute(
            ToolInput.of(Map.of("command", "sleep 30")),
            context);

    assertThat(result.isError()).isTrue();
    assertThat(result.getContent()).contains("USER_SIGINT");
}
```

### 레시피 3 — NON_INTERRUPTIBLE의 No-op 검증

```java
@Test
void readTool_ignoresSignal() throws Exception {
    ReadTool tool = new ReadTool(vfs);

    // Trip the signal through the public coordinator API — tests should not reach into
    // DefaultCancellationSignal's package-private trip() the way the runtime executor does.
    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
    CancellationSignal pretripped = coordinator.getSignal();

    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, pretripped)
            .build();

    ToolResult result = tool.execute(
            ToolInput.of("file_path", "/hello.txt"),
            context);

    // 신호가 트립되어 있어도 NON_INTERRUPTIBLE Tool은 평소대로 성공.
    assertThat(result.isSuccess()).isTrue();
}
```

실제 프로젝트의 참조 테스트:

- `modules/aimon-core/src/test/java/at/aimon/core/ext/tools/web/WebFetchToolInterruptTest.java`
- `modules/aimon-core/src/test/java/at/aimon/core/ext/tools/bash/BashToolInterruptTest.java`
- `modules/aimon-core/src/test/java/at/aimon/core/agent/interrupt/DefaultCancellationSignalTest.java`

## 디자인 원칙

- **Single-shot, per-turn signal** — 턴마다 새 `CancellationSignal`을 발급해서 이전 턴의 중단이 다음 턴에 새지 않습니다. 리셋 없음 → race 단순화.
- **`InterruptAccess.signalOf()` never returns null** — Tool이 null 검사 보일러플레이트 없이 항상 폴링할 수 있게. 컨텍스트에 신호가 없으면 `NoopCancellationSignal.INSTANCE`가 자연스럽게 "취소 없음" 의미를 갖습니다.
- **Behavior 선언은 executor의 정책 입력** — Tool이 `InterruptBehavior`를 선언하면 executor가 어떻게 신호를 전파할지를 결정합니다. Tool 자신이 `Thread.interrupt()`를 호출하지 않고, executor가 대신 합니다 — 이중 호출/순서 꼬임을 피할 수 있음.
- **Terminator는 registration-order, one-shot, non-blocking** — coordinator가 신호 트립 시 한 번에 모두 호출합니다. blocking / throwing / non-idempotent한 terminator는 nightmare를 부릅니다. `Terminator.java` Javadoc의 3가지 계약을 반드시 지키세요.
- **Grace period는 UX 시한, 의미적 시한이 아님** — 500ms는 "cooperative 경로에 양보하는 시간"이지 "반드시 이 안에 종료"가 아닙니다. 진짜 의미적 deadline은 Tool별 timeout 파라미터로 처리하세요.

---

## 관련 문서

- [interrupt.md](../../design/agent-execution/interrupt.md) — 설계 배경, 이벤트 시퀀스, 시나리오
- [command-queue-guide.md](command-queue-guide.md) — `QueuedInputPriority.NOW`의 enqueue/drain 의미
- [tool-development-guide.md](../tool/tool-development-guide.md) — Tool 개발 기본 규약
- `at.aimon.core.agent.interrupt.package-info` — 패키지 차원의 Javadoc 요약
