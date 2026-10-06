# 설계 — EE-13 + EE-7: 백그라운드 `Bash` 종료, 제공자와 작업 목록의 수명 상향

> Status: **IMPLEMENTED** (2026-10-03). 백로그 항목 EE-13 · EE-7 의 설계이고, 설계 리뷰에서 승인된 그대로다(한 차례, 차단
> 지적 없이 통과, 비차단 지적 열 건). 아래 본문(§1~§9)은 승인본에서 한 글자도 바꾸지 않았다 — 소스 인용(`1f8b53f` 기준)과
> 열린 질문 번호까지 그대로다. 그래서 본문에는 **구현 뒤에 사실이 아니게 된 서술**이 남아 있다. 고친 것은 본문이 아니라
> §10 에 적는다.
>
> 더한 것은 §10 하나다 — 구현이 이 설계에서 벗어난 곳과 그 이유, 반영한 리뷰 지적, 열린 질문이 어떻게 되었는지. 특히
> §10.1(§3.1 과 §6 이 서로 다르게 적은 "시작 전 취소" 는 §3.1 쪽으로 구현했다)과 §10.4(새 백로그 항목이 §8 의 넷이 아니라
> 일곱이다)를 먼저 볼 것. §9 의 열린 질문 가운데 이 변경 밖으로 결과가 번지는 것은
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) 로 옮겼다
> (EE-58 ← Q4, EE-59 ← Q5 · Q1; EE-53~EE-56 ← §8; EE-57 ← 설계 리뷰). 열림/닫힘의 정본은 그 문서다. 이 설계가 확장하는
> 명세는 [`execution-environment.md`](execution-environment.md) §4.1 · §4.3 · §5.3 · §13 · §15 다.
>
> **덧붙임 (2026-10-04, EE-58).** §9 Q4 가 남긴 EE-58(백그라운드 작업의 가시 범위가 런타임이다)은 닫혔다. 작업의 소유자는
> 이제 런타임과 **세션(세션이 없으면 실행)** 이고, 같은 런타임의 다른 세션은 작업을 읽지도 멈추지도 못한다.
> `BackgroundBashManager.start` · `find` · `kill` 의 소유자 인자는 `AgentRuntimeId` 가 아니라 `BackgroundBashOwner` 다. 아래
> 본문의 "같은 런타임의 어느 세션이든" 서술은 그 시점의 기록이다. 설계는
> [`execution-environment-ee49-ee51-ee58-isolation-boundary.md`](execution-environment-ee49-ee51-ee58-isolation-boundary.md).
>
> **덧붙임 (2026-10-05, EE-54).** §3.1 의 "이 변경에서 싣는 호출자는 백그라운드 `Bash` 뿐이다" 는 그 시점의 기록이다.
> 포그라운드 `Bash` 도 이제 명령마다 취소 신호를 싣는다. 그 신호를 거는 것은 `KillShell` 이 아니라 **실행 단위의**
> `CancellationSignal`(`InterruptAccess.signalOf`)이다 — 도구가 셸 호출 동안만 리스너를 걸고 호출이 끝나면 뗀다.
> `InterruptBehavior.THREAD_INTERRUPT` 선언과 스레드 인터럽트는 그대로 남아 있어 멈춤이 두 길로 나간다. 코디네이터가
> 신호를 먼저 걸고 스레드를 나중에 인터럽트하므로 `LocalShell` 의 포그라운드 명령은 대개 취소 쪽으로 끝나지만, 모델이
> 받는 결과는 어느 쪽이든 같다(`Bash command interrupted: <reason>`). 백그라운드 명령의 신호는 여전히 작업의 것이다 —
> 실행의 인터럽트는 백그라운드 명령을 멈추지 않는다.

- 대상 브랜치: `herdr/ee13-ee7-background-bash-lifecycle` (BASE `herdr/ee9-ee12-hook-env-shell`, HEAD `1f8b53f`)
- 소스 인용은 모두 2026-10-03, `1f8b53f` 기준이다. 줄 번호 대신 심볼 이름을 주로 적었다.
- 이 문서는 설계만 담는다. 코드 조각은 모양을 보이기 위한 것이고 구현이 아니다.

---

## 1. 문제

백그라운드 `Bash` 명령은 한번 시작하면 24시간 상한(`BashTool.BACKGROUND_TIMEOUT_MS`)까지 멈출 방법이 없다. 작업 목록에서
빼도 명령은 계속 돈다 — 명령은 `CompletableFuture.supplyAsync` 안의 동기 `VirtualShell.execute(...)` 이고, 셸 SPI 에는
취소 수단이 없다. 그 상한도 도구에 박힌 상수라 실행 환경(샌드박스)이 줄일 수 없다(EE-13). 한편 부트스트랩은
`ExecutionEnvironmentProvider` 를 런타임마다 만들어 런타임의 teardown 싱크에 올리므로 테넌트 런타임이 축출되면 제공자가
함께 닫히고, 작업 목록인 `BackgroundBashManager` 는 `OrcaBashToolProvider` 가 `ToolRegistry` 마다 새로 만드는 agent-scoped
객체라 축출 뒤 다시 만들어진 런타임의 `BashOutput` 은 옛 task id 를 모른다(EE-7). 종료 도구도 같은 작업 목록에서 작업을
찾으므로, 이 변경은 (1) 셸 SPI 에 취소 계약을 들이고 그것을 쓰는 종료 도구를 두고, (2) 실행 환경이 백그라운드 상한을
정하게 하고, (3) 제공자를 애플리케이션 수명으로 올려 요청의 런타임으로 워크스페이스를 고르게 하고, (4) 런타임 축출에는
그 런타임 몫만 정리하는 훅을 달고, (5) 작업 목록을 런타임보다 오래 사는 애플리케이션 수명 컴포넌트로 올리며 저장소를
인터페이스로 가른다.

---

## 2. 착수 전에 소스로 확인한 전제 (백로그 규칙 둘)

백로그의 두 결정문이 논거로 삼는 문장을 소스로 다시 읽었다. 넷이 적힌 것과 달랐고, 설계가 그 차이에 기댄다. 닫을 때
백로그에 그대로 옮겨 적어야 한다.

1. **EE-7 의 "왜" — "축출된 테넌트의 백그라운드 작업은 셸을 잃고 `BashOutput` 이 셸이 닫혔다는 오류를 보고한다" 는
   로컬 제공자에서는 일어나지 않는다(읽어서 확인, 실행해 보지 않음).** `LocalShell.close()` 는 본문이 비어 있고 닫힘
   상태가 없다. 축출 뒤에도 백그라운드 명령은 **계속 돈다**. 실제 증상은 둘이다 — 새 런타임의 `BashOutput` 이
   `Shell not found` 를 돌려주고(작업 목록이 사라졌으므로), 명령은 아무도 추적하지 못한 채 상한까지 남는다. 즉 로컬에서
   끊기는 것은 셸이 아니라 **작업 목록**이다. "셸이 닫혔다" 는 닫힘이 실제 동작인 셸(샌드박스)에서만 참일 수 있다.
   구현 첫 단계에서 이 동작을 특성화 테스트로 고정한 뒤 고친다(규칙 셋 — 돌려 봐야 나온다).
2. **`LocalShell` 은 이미 프로세스 트리를 죽이는 코드를 갖고 있다.** timeout 과 스레드 인터럽트 두 경로가
   `destroyForciblyQuietly(Process)` 로 자손 스냅숏 → SIGTERM → 200ms → SIGKILL 을 한다. 종료 도구가 로컬에서 새로
   필요로 하는 것은 죽이는 방법이 아니라 **그것을 바깥에서 부를 계약**이다. (자손 스냅숏이 경합한다는 기존 한계도 그대로
   물려받는다 — §7.)
3. **같은 `AgentRuntimeId` 의 런타임 둘이 잠시 함께 살 수 있다.** `AgentRuntimeResolver.invalidate` 는 id 를 즉시
   내리고 옛 런타임은 마지막 보유자가 놓을 때 닫는다. 그 사이의 요청은 새 런타임을 만든다. 그래서 축출 훅을
   "`onRuntimeEvicted(id)`" 같은 id 콜백으로 만들면 옛 런타임의 늦은 close 가 **새 런타임이 쓰는 몫을 정리한다.**
   훅은 id 가 아니라 바인딩 단위여야 한다(§3.3).
4. **작업 목록을 올리면 지금은 암묵적인 격리가 사라진다.** 지금은 매니저가 런타임마다 하나라서 다른 런타임의 task id
   를 조회할 길이 없다. 애플리케이션 수명으로 올리면 한 목록을 모든 테넌트가 본다. task id 는 UUID 앞 8자(32비트)라
   경계가 될 수 없으므로, 소유 런타임으로 조회를 좁혀야 한다(§3.4). `TaskStop` 이 같은 이유로
   `ScopedSubagentTaskController` 를 쓴다.

EE-13 의 전제(`CompletableFuture.cancel` 은 스레드를 인터럽트하지 않는다, `VirtualShell` 에 취소가 없다)는 적힌 대로다.
EE-21 의 닫힘 절이 정한 계약은 코어의 `OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory` 에 대한 것이고
(런타임이 소유, 함수는 런타임 전용 제공자를 돌려준다), 이 설계가 모양을 바꾸는 것은 부트스트랩의
`ExecutionEnvironmentSpec.factory` 다. 코어의 그 경로는 손대지 않으므로 충돌하지 않는다(§3.3).

확인하지 못한 것: aimon-sandbox 의 소스(로컬에 체크아웃이 없다). 그쪽 영향은 SPI 로부터 추론한 것이다(§8).

---

## 3. 접근

### 3.1 취소 계약 — `ExecutionOptions` 에 취소 신호를 싣는다 (EE-13 ①)

셸 SPI 에 명령 단위의 취소 신호를 들인다.

- `at.aimon.core.shell.ShellCancellation` — 읽는 쪽 인터페이스. `isCancelled()` 와 `onCancel(Runnable)` (등록 해제
  핸들을 돌려준다), 그리고 절대 걸리지 않는 `none()`. `at.aimon.core.llm.LlmCancellation` 과 같은 모양이고 같은 이유로
  따로 둔다 — 셸 패키지는 `agent.interrupt` 를 볼 수 없다.
- `ShellCancellationSource` — 거는 쪽. `cancel()` 은 한 번만 유효하고 등록된 리스너를 부른 스레드에서 돌린다.
- `ExecutionOptions.getCancellation()` — 기본값 `ShellCancellation.none()`. `Builder.cancellation(...)` 과
  `toBuilder()` 가 싣는다.
- `ShellFeature.CANCELLATION` — 이 셸이 취소 신호를 지킨다는 선언.
- `ShellCancelledException extends ShellExecutionException` — 취소된 `execute` 가 던진다. timeout 예외처럼 그때까지의
  stdout · stderr · 잘림 여부 · notice 를 싣는다.

계약(`VirtualShell` · `ExecutionOptions` · `ShellFeature` 의 Javadoc 에 적는다):

- `supports(CANCELLATION)` 이 참인 셸은, 도는 중인 `execute` 의 신호가 걸리면 **명령과 그 명령이 띄운 것 전부**(로컬은
  프로세스 트리, 원격은 원격 명령)를 멈추고, 그 `execute` 가 `ShellCancelledException` 을 던지게 한다. 시작 전에 이미
  걸려 있으면 명령을 띄우지 않고 던진다. 끝난 뒤에 걸리면 아무 일도 없다.
- 리스너는 거는 쪽 스레드에서 동기로 돌 수 있으므로 thread-safe · 멱등이어야 하고 던지지 않는다. `cancel()` 은 멈춤
  요청이 나갈 때까지만 걸린다(로컬은 SIGTERM 유예 200ms 포함). 원격 셸은 멈춤을 요청만 하고 돌아와도 된다.
- 참이 아닌 셸은 신호를 무시한다. 호출자는 `supports` 로 미리 묻는다.
- 포그라운드 호출에도 실을 수 있는 일반 계약이지만, 이 변경에서 싣는 호출자는 백그라운드 `Bash` 뿐이다. 포그라운드의
  인터럽트 경로(`InterruptBehavior.THREAD_INTERRUPT`)는 그대로다.

`LocalShell` 구현: `pb.start()` 직후 신호에 리스너를 등록한다. 리스너는 "내가 죽였다" 플래그를 세우고
`destroyForciblyQuietly(p)` 를 부른다. `waitFor` 에서 깨어난 실행 스레드는 그 플래그가 서 있으면 캡처 파일을 읽어
`ShellCancelledException` 을 던진다. `finally` 에서 등록을 해제한다. `supports(CANCELLATION)` 은 참.
`WorkingDirectoryShell` 은 `toBuilder()` 로 옵션을 넘기고 `supports` 를 위임하므로 격리 브랜치에서도 그대로 동작한다 —
단 `toBuilder()` 가 새 필드를 빠뜨리면 **조용히** 취소가 사라지므로 테스트로 고정한다(§6).

**기각한 대안**

| 대안 | 기각한 이유 |
|------|------------|
| 스레드 인터럽트를 계약으로 삼는다 (`LocalShell` 은 이미 반응한다) | 인터럽트하려면 실행 스레드를 쥐어야 하는데 그것은 실행기 구현의 사정이다. 원격 셸의 블로킹 호출은 인터럽트에 반응한다는 보장이 없고, 반응해도 원격 명령은 계속 돈다. 인터럽트는 "풀이 내려간다" 와 "이 명령을 끝내라" 를 구분하지 못하고, 지원 여부를 `ShellFeature` 로 물을 수도 없다 |
| 실행이 핸들을 돌려준다 (`VirtualShell.start(...)` → `cancel()` · `result()`) | `VirtualShell` 에 메서드가 하나 늘어, 모든 구현과 래퍼(`WorkingDirectoryShell`, 샌드박스의 래퍼, 테스트 더블)가 고쳐져야 한다. `ExecutionOptions` 에 싣는 쪽은 §5.3 이 `background` 플래그를 들인 것과 같은 길이고, 옵션을 그대로 넘기는 래퍼는 고칠 것이 없다. 지원하지 않는 셸은 무시하면 된다 |
| `agent.interrupt.CancellationSignal` 을 재사용한다 | `shell → agent` 역방향 의존이 생긴다. 그 신호는 실행 단위이고 `InterruptReason` 어휘를 싣는다. `LlmCancellation` 이 같은 이유로 따로 있다 |

### 3.2 종료 도구 `KillShell` 과 환경이 정하는 상한 (EE-13 ① · ②)

**도구.** 이름은 `KillShell`, 입력은 `taskId` 하나(`BashOutput` 과 같은 이름). `BashOutput` 의 설명과 오류 문구가 이미
그 대상을 "background shell" 이라고 부르고(`Shell not found: …`), 이 저장소의 도구 이름은 모델이 익숙한 이름을 따르고
있어(`Bash` · `BashOutput` · `TaskStop` …) 그 짝으로 골랐다. `OrcaBashToolProvider` 가 `Bash` · `BashOutput` 과 함께
등록하며 셋이 한 `BackgroundBashManager` 를 나눠 쓴다. 도구는 매니저만 쥐고 상태가 없으며 `execute()` 는 던지지 않는다.

`KillShell` 의 답:

| 상황 | 결과 |
|------|------|
| 이 노드에서 도는 작업, 셸이 취소를 지원 | 신호를 걸고 짧게(상한 5초) 정착을 기다린 뒤 `success` — "stopped" 또는 "stop requested, still shutting down". 남은 출력은 `BashOutput` 으로 읽으라고 안내 |
| 이 노드에서 도는 작업, 시작할 때 셸이 `CANCELLATION` 을 지원하지 않았음 | `ToolResult.error` — 이 환경의 셸은 도는 명령을 멈출 수 없고 상한(값을 적는다)에 끝난다 |
| 이미 끝난 작업 | `success` — 돌고 있지 않다(상태를 적는다). `TaskStop` 과 같은 처리 |
| 다른 노드에서 도는 작업 | `ToolResult.error` — 다른 노드에서 도는 작업이라 여기서 멈출 수 없다 |
| 모르는 id, 또는 다른 런타임의 작업 | `ToolResult.error` — not found |

`TaskStop` 에 합치지 않는다. 그 도구는 `SubagentTaskController` 로 서브에이전트 작업을 다루고 id 공간과 종료 의미(협조적
취소, `KILLED` 로 정착)가 다르다. `BashOutput` 에 `kill` 인자를 더하는 것도 하지 않는다 — 읽기 도구가 파괴적 동작을
겸하면 권한 규칙을 도구 이름으로 가를 수 없다.

취소된 작업은 `BashTaskStatus.KILLED` (새 상수)로 정착하고 `BashOutput` 은 `Status: Killed` 와 죽기 전까지의 출력을
보고한다. `Bash` 의 시작 응답에는 종료 방법 한 줄을 더한다(`KillShell(taskId=…)`).

**상한.** `ExecutionEnvironment` 에 기본 메서드를 더한다.

```java
/** 이 환경에서 백그라운드 명령이 돌 수 있는 최대 시간. 비어 있으면 호출자의 기본값(24시간). */
default Optional<Duration> backgroundCommandTimeout() { return Optional.empty(); }
```

`BashTool` 은 백그라운드 경로에서 이 값을 읽어 `BACKGROUND_TIMEOUT_MS` 대신 쓴다. 비었거나 0 이하면 24시간이다(셸은
0 이하를 "무한" 으로 읽으므로 그 값은 받지 않고 WARN). 1초 하한은 포그라운드와 같다. 환경이 정한 값이면 시작 응답에
"…뒤에 중단된다" 를 적어 모델이 알게 한다. 상한에 걸리면 지금처럼 `ShellTimeoutException` → `FAILED` 다.

값을 채우는 길: `LocalExecutionEnvironmentProvider.Builder.backgroundCommandTimeout(Duration)` →
`ExecutionEnvironmentSpec.Builder.backgroundCommandTimeout(Duration)` → 스타터 `aimon.environment.background-command-timeout`.
`LocalIsolatedEnvironment` 는 부모의 값을 돌려준다. 로컬 제공자의 스테이징 스윕 유예(기본 24시간 — Javadoc 이 "백그라운드
명령이 살 수 있는 가장 긴 시간" 이라고 근거를 든다)는 상한이 그보다 길게 설정되면 상한으로 끌어올린다.

서술자(`EnvironmentDescriptor`)에 싣지 않는다. 서술자는 프롬프트에 렌더되는 값이고 `equals` 가 프롬프트 캐시의
안정성에 쓰인다. 상한은 정책이고, 모델에게는 시작 응답으로 충분히 전달된다.

### 3.3 제공자를 애플리케이션 수명으로, 축출에는 바인딩 훅 (EE-7 ① · ②)

**제공자 SPI 에 런타임 바인딩을 더한다.**

```java
// ExecutionEnvironmentProvider
/** 어셈블리가 런타임을 만들 때 부른다. 돌려받은 핸들을 닫는 것이 "그 런타임이 축출되었다" 는 통지다. */
default RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) { return RuntimeBinding.NONE; }
```

`RuntimeBinding` 은 `close()` 하나짜리 함수형 핸들이다(`CancellationSignal.Registration` 과 같은 모양, `NONE` 포함).
계약: 닫으면 **그 바인딩만을 위해 쥐고 있던 것**을 놓는다. 같은 id 의 다른 바인딩이 아직 쓰는 것은 놓지 않는다(§2-3).
그 런타임을 위해 내준 셸에서 **아직 도는 명령은 멈추지 않는다** — 놓을 수 없는 것은 명령이 끝날 때나 제공자의
`close()` 때 놓는다. 멱등이고 던지지 않는다.

바인딩은 **어셈블리가** 한다. 런타임은 하지 않는다 — 설계 §4.3 원칙("런타임은 아무것도 닫지 않는다")과 EE-21 의 예외
(런타임이 소유하는 것은 런타임별 함수가 돌려준 제공자 하나)를 그대로 둔다. `OrcaAgentRuntime.close()` 의 하드코딩된
목록은 늘지 않는다.

**로컬 기본 구현 — `PerRuntimeLocalEnvironmentProvider` (`at.aimon.core.environment.impl`, 신규).**
`Function<AgentRuntimeId, LocalExecutionEnvironmentProvider>` 를 받아 런타임 id 별 슬롯(자식 제공자 + 바인딩 수)을 둔다.

- `resolve(request)` — `request.parent()` 가 있으면 그대로 돌려준다(포크). 없으면 `request.agentRuntimeId()` 의 슬롯에
  위임한다. 슬롯이 없으면 그 자리에서 만든다(바인딩 수 0 — `close()` 때까지 남는다). 닫힌 뒤에는 던지고, 실행기가
  `UnavailableExecutionEnvironment` 로 바꾼다.
- `bindRuntime(id)` — 슬롯을 (없으면) 만들고 바인딩 수를 올린다. 핸들을 닫으면 내리고, 0 이면 슬롯을 지우고 자식을 닫는다.
- `workspace(id)` — 슬롯의 자식 제공자. 어셈블리가 `AimonStack.fileSystem(id)` 와 제어 저장소를 얻는 데 쓴다.
- `close()` — 모든 자식을 닫는다.

자식을 닫아도 도는 명령은 멈추지 않는다는 것이 이 구현이 계약을 지키는 근거다. 로컬 슬롯이 쥔 것은 `LocalFileSystem`
(닫히면 이후 호출이 던질 뿐) 과 `LocalShell`(자원 없음, 명령마다 프로세스)이고, 도는 명령이 쥔 것은 자기 프로세스뿐이다.
이 근거는 **자식이 `LocalExecutionEnvironmentProvider` 일 때만** 참이므로 함수의 반환 타입을 그 타입으로 못 박는다.

**부트스트랩 SPI 의 새 모양 (`ExecutionEnvironmentSpec`).**

| 지금 | 바뀐 뒤 |
|------|--------|
| `defaults()` — 런타임마다 로컬 제공자 | `defaults()` — 스택이 `PerRuntimeLocalEnvironmentProvider` **하나**를 만들고 소유한다 |
| `factory(Function<AgentRuntimeId, ExecutionEnvironmentProvider>)` — 런타임마다 제공자, 런타임 teardown 이 닫음 | **없어진다.** `provider(Supplier<ExecutionEnvironmentProvider>)` — 스택이 한 번 불러 얻은 **하나**를 소유하고 앱 종료에 닫는다. 그 제공자가 `request.agentRuntimeId()` 로 워크스페이스를 고르고, 런타임별 정리는 `bindRuntime` 으로 받는다 |
| `shared(provider)` — 호출자 소유 | 그대로. 이제 `bindRuntime` 통지도 받는다 |

`StackAgentRuntimeProvisioner` 가 생성 시점에 제공자를 하나 정하고(`shared` → 그것, `provider` → 공급자의 답, 아니면
로컬 라우터), `createStores` 는 제공자를 만드는 대신 `provider.bindRuntime(id)` 를 **가장 먼저** 부르고 그 핸들을
`created` 목록에 올린다. 그래서 EE-23 의 규칙이 그대로 적용된다 — 만들다 실패하면 역순으로 닫히고, 성공하면 싱크로
넘어가 테넌트는 `ProvisionedAgentRuntime` 이(런타임 다음, 제어 저장소 다음 **마지막으로**), 시작 런타임은 teardown 계획이
닫는다. 스택이 소유한 제공자 자체는 `AimonStackBuilder` 가 프로비저너를 만든 직후 `AGENT_RESOURCES` 에 올린다(런타임
루프보다 먼저 등록하므로 그 단계에서 마지막에 닫힌다).

로컬 라우터의 자식 함수는 파일 시스템 배치 셋을 그대로 옮긴다.

- 로컬 루트: `workspaceRoot(AgentWorkspaceLayout.resolve(root, id))` — 지금의 `createLocalStores` 와 같다. 제어 저장소는
  지금처럼 런타임마다 따로 만든 `LocalFileSystem` 이다.
- 호출자 공급 공유 파일 시스템: `fileSystem(shared)` (빌림).
- 파일 시스템 팩토리: 팩토리가 만든 파일 시스템을 **슬롯이 소유한다**(자식 빌더에 "소유하는 파일 시스템" 옵션을 더한다).
  지금은 런타임의 싱크가 소유하는데, 같은 id 의 런타임 둘이 겹치면(§2-3) 옛 런타임의 close 가 새 런타임이 쓰는 슬롯의
  파일 시스템을 닫게 되기 때문이다. 제어 저장소는 슬롯의 (경로 규칙을 거치지 않은) 파일 시스템 위의 `.aimon/` 뷰다.

런타임 팩토리에는 제공자를 한 번만 준다 — `instantiate` 의 `synchronized (runtimeFactory)` 블록에서
`withExecutionEnvironmentProvider` 를 런타임마다 다시 부르던 줄이 없어진다.

CLI(`AgentSetupFactory`)는 `factory(id -> Local…workspaceRoot(cwd))` 를 `provider(() -> Local…workspaceRoot(cwd).build())`
로 바꾼다. 워크스페이스가 프로젝트 디렉터리 하나이므로 라우터가 필요 없다. 스타터는 `ExecutionEnvironmentProvider` 빈이
있으면 `shared`, 없으면 프로퍼티로 조율한 기본값 — 지금과 같고 새 프로퍼티 하나가 더해진다.

**기각한 대안**

| 대안 | 기각한 이유 |
|------|------------|
| `LocalExecutionEnvironmentProvider` 안에 런타임별 슬롯을 넣는다 | 그 클래스의 생성자는 소유/빌림, 스윕, 실패한 빌드의 되감기까지 갈래마다 테스트로 고정되어 있다. 그것을 슬롯으로 다시 쓰는 일은 합성이 주지 못하는 동작을 하나도 주지 않는다. CLI 와 테스트는 단일 워크스페이스 제공자를 그대로 쓴다 |
| `factory(Function)` 을 남기고 임의의 자식 제공자를 감싸는 범용 라우터를 둔다 | 축출 때 자식을 닫으면 닫힘이 실제 동작인 셸에서는 지금과 같은 일이 벌어진다 — 이 항목이 고치려는 것이다. 닫지 않으면 테넌트마다 샌다. 범용 라우터는 계약을 지킬 수 없다 |
| 축출 훅을 `onRuntimeEvicted(AgentRuntimeId)` 콜백으로 | §2-3 — 같은 id 의 런타임이 겹칠 때 옛 것의 통지가 새 것의 몫을 정리한다 |
| 런타임이 스스로 바인딩한다(`OrcaAgentRuntime.close()` 가 닫는다) | 모든 조립이 훅을 공짜로 얻는 장점이 있지만, 부트스트랩은 런타임을 만들기 **전에** 슬롯이 필요하다(제어 저장소). 결국 프로비저너도 바인딩해야 해서 훅이 두 군데가 되고, 런타임의 닫기 목록이 늘며 §4.3 의 원칙에 예외가 하나 더 생긴다. 코어만 쓰는 조립이 얻지 못하는 것은 §8 에 백로그 후보로 남긴다 |
| 제공자가 바인딩 수 대신 "도는 명령이 없을 때" 까지 정리를 미룬다(매니저와 연동) | 로컬에서는 미룰 필요가 없고(자식을 닫아도 명령이 돈다), 원격 제공자는 자기 셸이 도는 명령을 안다. 코어에 임대 계수 장치를 들일 이유가 없다 |

### 3.4 작업 목록의 수명 상향과 저장소 분리 (EE-7 ③)

`BackgroundBashManager` 를 애플리케이션 수명의 컴포넌트로 만든다. 이름과 패키지(`at.aimon.core.tools.bash`)는 그대로 둔다
— `*Manager` 의 수명은 이름이 아니라 키와 저장 위치로 판단한다는 규칙대로, 달라지는 것은 누가 만들고 무엇으로 찾느냐다.
서브에이전트 쪽의 선례(`BackgroundTaskStore` + 노드 로컬 `RunningTaskRegistry`)와 같은 가름이다.

- **저장소 `BackgroundBashStore` (인터페이스) + `InMemoryBackgroundBashStore` (기본).** 작업의 **메타데이터**를 든다 —
  누가 시작했고 어느 노드에서 도는가. 여러 노드가 공유할 수 있는 것은 이것뿐이다.
- **노드 로컬 핸들.** future, 취소 신호, 출력 버퍼, notice 는 그 노드의 매니저가 메모리에 든다(`BackgroundBashTask`).
  프로세스는 그 노드에만 있으므로 옮길 수 없다.
- **실행.** 백그라운드 스레드 풀이 `BashTool` 에서 매니저로 옮겨 온다. 축출된 런타임의 도구 인스턴스가 쥔 풀에서 명령이
  도는 것은 수명이 거꾸로다. `BashTool` 은 이제 풀이 없다(`close()` · `shutdown()` 은 소스 호환을 위해 빈 채로 남긴다).
- **소유 범위.** 작업은 시작한 실행의 `ToolContextKeys.AGENT_RUNTIME_ID` 를 소유자로 기록하고, `BashOutput` · `KillShell`
  은 자기 컨텍스트의 id 와 소유자가 같을 때만 찾는다. id 가 없는 컨텍스트(손으로 만든 것)는 소유자 없는 작업만 본다.
  같은 런타임의 다른 세션은 지금처럼 볼 수 있다 — 세션 범위로 좁히지 않는다(§9 Q4).
- **조회 결과는 셋 중 하나** — 이 노드의 살아 있는 핸들 / 저장소에만 있는 레코드(다른 노드의 작업, 또는 이 노드가
  재시작으로 핸들을 잃은 작업) / 없음. 둘째가 "다른 노드에서 도는 작업" 보고다.
- **id 충돌.** `putIfAbsent` 가 거절하면 id 를 다시 뽑는다. 지금의 `put` 은 덮어쓴다.
- **보존.** 지금은 끝난 작업이 영영 남지만 런타임과 함께 GC 되었다. 올리면 프로세스 수명 동안 쌓이므로, 끝난 지 보존
  기간(기본 24시간)이 지난 작업은 `start` 때마다 치운다. 저장소 레코드도 함께 지운다.
- **종료.** `close()` 는 새 작업을 거절하고, 이 노드에서 도는 작업 가운데 취소할 수 있는 것에 신호를 걸고, 풀을 내린다.

배선: `OrcaBashToolProvider(BackgroundBashManager)` 생성자를 더한다. 인자 없는 생성자는 지금처럼 그 레지스트리 전용
매니저를 만든다(코어만 쓰는 조립과 테스트의 기존 동작). `StackAgentRuntimeProvisioner.instantiate` 가 기본 제공자 목록의
`OrcaBashToolProvider` 를 스택의 매니저를 쥔 것으로 바꿔 끼운다 — `ToolSpec.resolveProviders` 가
`OrcaFileToolProvider(artifactPolicy)` 를 바꿔 끼우는 것과 같은 방식이다. 매니저는 `AimonStackBuilder` 가 만든다
(`bashEnabled` 일 때만). 저장소는 `ToolSpec.Builder.backgroundBashStore(...)` 로 바꿀 수 있고, 스타터는
`BackgroundBashStore` 빈이 있으면 그것을 넘긴다. 노드 id 는 `SessionSpec.getNodeId()` 가 있으면 그것, 없으면 매니저가
뽑은 임의 값이다.

**teardown.** 새 단계 `TeardownPhase.BACKGROUND_COMMANDS` 를 `AGENT_RUNTIMES` 와 `AGENT_RESOURCES` 사이에 둔다.
`AGENT_RUNTIMES` 뒤인 이유: 마지막으로 배출되는 실행이 아직 백그라운드 명령을 시작할 수 있다. `AGENT_RESOURCES` 앞인
이유: 취소는 제공자가 소유한 셸을 거쳐 나가므로 제공자가 닫히기 전이어야 한다. 이 단계는 **관찰 가능한 종료 동작을
바꾼다** — 지금은 스택이 닫혀도 백그라운드 명령이 남는다(§9 Q2).

**기각한 대안**

| 대안 | 기각한 이유 |
|------|------------|
| 매니저를 agent-scoped 로 두고, 축출된 런타임의 매니저를 id 별로 보관했다가 다시 붙인다 | 수명이 둘인 객체가 된다(런타임이 소유하는데 런타임보다 오래 산다). 저장소 분리가 되지 않고 다른 노드 보고도 할 수 없다 |
| 출력까지 저장소에 넣는다 | 스트림당 최대 1MB 이고 한 번 읽기 커서가 원자적 "가져가기" 를 요구한다. 결정문이 정한 한계는 "다른 노드에서 도는 작업으로 보고" 다. 교차 노드 출력·종료는 백로그 후보로 남긴다(§8) |
| 매니저를 `OrcaProviderDependencies` / `OrcaToolProviderContext` 로 흘린다 | 공개 SPI 의 접근자가 열여덟이 된다. 쓰는 제공자는 하나뿐이고 생성자로 충분하다 |

---

## 4. 바뀌는 인터페이스와 데이터

### 4.1 셸 SPI (`at.aimon.core.shell`)

```java
public interface ShellCancellation {
    static ShellCancellation none();
    boolean isCancelled();
    Registration onCancel(Runnable listener);   // 이미 걸렸으면 등록하는 스레드에서 바로 돈다
    interface Registration { void remove(); }   // 멱등
}
public final class ShellCancellationSource {     // 명령 하나에 하나
    public static ShellCancellationSource create();
    public ShellCancellation token();
    public boolean cancel();                    // 처음 부를 때만 true. 던지지 않는다
}
// ExecutionOptions:  ShellCancellation getCancellation()  /  Builder.cancellation(ShellCancellation)
// ShellFeature:      CANCELLATION
// exception:         ShellCancelledException extends ShellExecutionException  (stdout, stderr, outputTruncated, notices)
```

### 4.2 실행 환경 SPI (`at.aimon.core.environment`)

```java
// ExecutionEnvironment
default Optional<Duration> backgroundCommandTimeout() { return Optional.empty(); }
// ExecutionEnvironmentProvider
default RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) { return RuntimeBinding.NONE; }

@FunctionalInterface
public interface RuntimeBinding extends AutoCloseable {
    RuntimeBinding NONE = () -> { };
    @Override void close();                      // 멱등, 던지지 않는다
}
```

`environmentSpiDependenciesAreCurated` 가 허용하는 의존(`AgentRuntimeId`, `java..`)만 쓴다.

### 4.3 작업 목록 (`at.aimon.core.tools.bash`)

```java
public interface BackgroundBashStore {
    boolean putIfAbsent(BackgroundBashRecord record);
    Optional<BackgroundBashRecord> find(String taskId);
    Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode, Instant at);
    void remove(String taskId);
}
```

`BackgroundBashRecord` — 불변 class + 빌더(`record` 아님): `taskId`, `ownerRuntimeId`(없을 수 있음), `nodeId`, `command`,
`startedAt`, `expiresAt`(시작 + 상한 — 다른 노드의 `RUNNING` 레코드가 이 시각을 넘겼으면 "더는 알 수 없다" 로 보고하고
치운다), `status`, `exitCode`, `finishedAt`. `settle` 은 `RUNNING` 에서 종료 상태로만 옮기고 멱등이다.

`BackgroundBashManager` 의 새 표면(모양):

```java
BackgroundBashManager.builder().store(...).nodeId(...).retention(...).clock(...).build();   // AutoCloseable
BackgroundBashTask start(AgentRuntimeId owner, String command, VirtualShell shell, ExecutionOptions options);
BackgroundBashLookup find(AgentRuntimeId owner, String taskId);      // local(task) | elsewhere(record) | notFound
BackgroundBashKill kill(AgentRuntimeId owner, String taskId);        // REQUESTED | NOT_RUNNING | UNSUPPORTED | ELSEWHERE | NOT_FOUND
```

`start` 가 취소 소스를 만들어 옵션에 싣고, 셸의 `supports(CANCELLATION)` 을 작업에 기록하고, 레코드를 넣은 **뒤에**
풀에 제출한다. 셸은 메서드 인자로만 받아 람다가 붙잡는다 — `BackgroundBashTask` 의 필드나 생성자 인자로 두면
`toolsHoldNoFileSystemOrShellFields` 가 실패한다(지금 `BashTool.executeInBackground` 가 통과하는 것과 같은 모양).
기존 공개 메서드(`registerTask(taskId, command, future)`, `getTask`, `hasTask`, `awaitCompletion` …)는 소유자 없는 작업으로
계속 동작한다 — `registerTask` 로 넣은 작업은 취소 핸들이 없으므로 `KillShell` 이 `UNSUPPORTED` 로 답한다.
`BashTaskStatus` 에 `KILLED` 가 더해진다.

### 4.4 부트스트랩

- `ExecutionEnvironmentSpec`: `factory(Function)` 제거, `provider(Supplier)` 추가, `Builder.backgroundCommandTimeout(Duration)`.
- `ToolSpec.Builder.backgroundBashStore(BackgroundBashStore)`.
- `TeardownPhase.BACKGROUND_COMMANDS`.
- `ProvisionedAgentRuntime` 이 소유하는 것: 제어 저장소와 **바인딩 핸들**. 제공자는 더는 들어가지 않는다.

---

## 5. 파일·모듈별 변경

**aimon-core**

| 파일 | 변경 |
|------|------|
| `shell/ShellCancellation.java`, `ShellCancellationSource.java`, `exception/ShellCancelledException.java` (신규) | §4.1 |
| `shell/ExecutionOptions.java` | `cancellation` 필드 · getter · builder · **`toBuilder()`** |
| `shell/ShellFeature.java`, `VirtualShell.java`, `shell/package-info.java` | `CANCELLATION` 과 취소 계약 Javadoc |
| `shell/impl/local/LocalShell.java` | 신호 리스너로 트리 종료, `ShellCancelledException`, `supports(CANCELLATION)` |
| `environment/ExecutionEnvironment.java`, `ExecutionEnvironmentProvider.java`, `RuntimeBinding.java` (신규) | §4.2. 제공자 Javadoc 의 수명 문단을 고친다 |
| `environment/impl/PerRuntimeLocalEnvironmentProvider.java` (신규) | §3.3 |
| `environment/impl/LocalExecutionEnvironmentProvider.java` | 빌더에 `backgroundCommandTimeout`, 소유하는 파일 시스템 옵션, 경로 규칙을 거치지 않은 파일 시스템 접근자. 스윕 유예 조정. 클래스 Javadoc 의 "24 hours … the longest a background command can outlive" 문장 |
| `environment/impl/LocalExecutionEnvironment.java`, `LocalIsolatedEnvironment.java` | `backgroundCommandTimeout()` (브랜치는 부모 값) |
| `tools/bash/BackgroundBashStore.java`, `InMemoryBackgroundBashStore.java`, `BackgroundBashRecord.java`, `KillShellTool.java` (신규) + 조회·종료 결과 타입 | §3.2, §4.3 |
| `tools/bash/BackgroundBashManager.java` | 빌더, 풀, 저장소, 소유 범위, 보존, `close()` |
| `tools/bash/BackgroundBashTask.java`, `BashTaskStatus.java` | 취소 가능 여부, `KILLED`, 취소 예외 처리 |
| `tools/bash/BashTool.java` | 백그라운드 경로가 `manager.start(...)` 를 부른다. 환경의 상한. 풀 제거. `BACKGROUND_TIMEOUT_MS` Javadoc 의 "there is no kill tool" 문장 |
| `tools/bash/BashOutputTool.java` | 소유 범위 조회, `Killed`, 다른 노드 보고 |
| `agent/impl/orca/tool/OrcaBashToolProvider.java` | 매니저를 받는 생성자, `KillShell` 등록 |
| `tools/bash/package-info.java`, `agent/impl/orca/tool/package-info.java`, `agent/tool/package-info.java` | 도구 목록 |

**aimon-bootstrap**

| 파일 | 변경 |
|------|------|
| `spec/ExecutionEnvironmentSpec.java`, `spec/ToolSpec.java` | §4.4 |
| `assemble/StackAgentRuntimeProvisioner.java` | 제공자를 한 번 정한다. `createStores` · `createLocalStores` · `createProvider` 를 바인딩 중심으로 다시 쓴다. Bash 제공자 바꿔 끼우기. 제공자 접근자 |
| `AimonStackBuilder.java` | 매니저 생성과 `BACKGROUND_COMMANDS` 등록, 스택 소유 제공자를 `AGENT_RESOURCES` 에 등록 |
| `TeardownPhase.java` | 새 상수와 그 자리의 이유. `AGENT_RESOURCES` · `HOOK_CONFIG_SHELL` 의 Javadoc 이 "런타임별 제공자" 를 전제로 쓴 문장 |
| `runtime/ProvisionedAgentRuntime.java` | Javadoc 만 — 소유 목록의 내용이 바뀐다 |

**aimon-cli** — `factory/AgentSetupFactory.java`: `factory(...)` → `provider(...)`.

**aimon-spring-boot-starter** — `AimonProperties.EnvironmentProperties` 에 `background-command-timeout`,
`AimonAutoConfiguration` 이 그것과 `ObjectProvider<BackgroundBashStore>` 를 스펙에 넘긴다. 설정 메타데이터가 있으면 함께.

**문서** (번역본이 있는 것은 같은 커밋에서 고친다)

| 문서 | 변경 |
|------|------|
| `docs/backlog/execution-environment-open-items.md` | EE-7 · EE-13 을 닫힘으로. 머리 카운트와 머리말("결정됨이되 열린 항목" 넷 → 둘). §2 의 네 가지를 "착수해 보니 달랐던 점" 으로. 새 항목 등록(§8) |
| `docs/design/tool/execution-environment.md` | §4.1 타입, §4.3 수명 표와 문단, §5.3, §13 계약 표(취소 · 상한 · 바인딩 세 행), §14 머리 인용과 "끝낼 수단" 불릿, §15 |
| `docs/design/tool/execution-environment-ee13-ee7-background-lifecycle.md` (신규) | 이 설계와 구현이 벗어난 점 — #204 의 설계 노트와 같은 자리 |
| `docs/overview/scope-model.md` + `.en.md` | §1 Application 행에 `BackgroundBashManager` · `BackgroundBashStore`, §2 표의 제공자 행(부트스트랩은 스택당 하나, 축출은 바인딩)과 새 두 행 |
| `docs/overview/architecture.md` + `.en.md`, `features.md` + `.en.md` | `OrcaBashToolProvider` 의 도구 목록에 `KillShell` |
| `docs/backlog/multi-instance-readiness.md` | 백그라운드 `Bash` 작업 목록의 현재 위치(메타데이터는 저장소, 프로세스와 출력은 노드 로컬) — 해당 절이 있으면 |
| `CHANGELOG.md` `[Unreleased]` | §8 의 목록 |

`docs/design/**` 에는 번역본이 없다(확인: `docs/design/tool` 에 `*.en.md` 없음). 스타터 가이드에 `aimon.environment.*`
프로퍼티 표가 있으면 새 프로퍼티를 더한다 — 구현 때 `grep` 으로 찾는다.

---

## 6. 실패 모드

| 상황 | 처리 |
|------|------|
| 취소를 지원하지 않는 셸에서 `KillShell` | `ToolResult.error`. 명령은 상한까지 돈다. 시작 응답에도 미리 적는다 |
| 취소와 정상 종료가 겹친다 | `LocalShell` 은 리스너가 죽이기 **전에** 세운 플래그로 판정한다. 플래그가 없으면 정상 결과다. 어느 쪽이든 작업은 한 번만 정착한다 |
| 시작 전에 취소 | 프로세스를 띄운 직후 등록하는 리스너가 그 자리에서 돈다(이미 걸린 신호). 명령은 곧바로 죽고 `KILLED` |
| 취소 뒤에도 손자 프로세스가 남는다 | 자손 스냅숏의 기존 경합. `KillShell` 은 "stop requested" 이상을 약속하지 않는다. 백로그 후보(§8). 그 뒤 좁혀졌다 — §10.8 |
| 원격 셸이 멈춤을 요청했으나 `execute` 가 돌아오지 않는다 | `KillShell` 은 5초 뒤 "still shutting down" 으로 답하고, 작업은 `RUNNING` 으로 남다가 상한에 끝난다 |
| `toBuilder()` 가 취소를 빠뜨린다 | 격리 브랜치에서 취소가 조용히 사라진다 → 테스트로 고정 |
| 환경이 0 이하의 상한을 준다 | 받지 않는다(WARN, 24시간) — 셸이 "무한" 으로 읽는다 |
| 저장소가 `putIfAbsent` 에서 던진다 | 명령을 시작하지 않고 `ToolResult.error` |
| 저장소가 `settle` 에서 던진다 | WARN. 이 노드의 핸들이 정본이므로 `BashOutput` 은 맞게 답한다. 다른 노드는 `expiresAt` 까지 `RUNNING` 으로 본다 |
| 저장소가 `find` 에서 던진다 | 로컬 핸들이 먼저이므로 이 노드의 작업은 영향이 없다. 로컬에 없을 때만 `ToolResult.error` |
| 노드가 재시작했다(영속 저장소) | 레코드는 남고 핸들은 없다. 같은 노드 id 면 "이 노드가 재시작하면서 잃었다" 로, 다른 id 면 다른 노드의 작업으로 보고한다. `expiresAt` 뒤에는 치운다 |
| 스택이 닫힌 뒤 `Bash(run_in_background)` (스케줄 루틴이 `SCHEDULING` 에서 배출 중) | 매니저가 거절 → `ToolResult.error`. 포그라운드 `Bash` 가 그 시점에 닫힌 셸을 만나는 것과 같은 급의 기존 동작 |
| 같은 id 의 런타임 둘이 겹친다 | 바인딩 수로 슬롯이 유지된다. 옛 것이 닫혀도 새 것의 슬롯은 남는다 |
| 슬롯 생성 실패(디렉터리를 만들 수 없음 등) | `bindRuntime` 이 던진다 → 프로비저닝 실패 → EE-23 의 되감기. 지금 제공자 빌드가 실패할 때와 같다 |
| 바인딩 없이 `resolve` | 슬롯을 만들어 `close()` 까지 둔다. 실패시키지 않는다 |
| 제공자가 닫힌 뒤 `resolve` | 던진다 → `UnavailableExecutionEnvironment` |
| `bindRuntime` 이 `null` 을 돌려준다 | `NONE` 으로 취급하고 WARN |
| 끝난 작업을 보존 기간 뒤에 조회 | not found. 지금은 영영 남으므로 달라지는 동작이다(CHANGELOG) |
| 외부 구현이 `ShellFeature` 를 `default` 없는 `switch` 식으로 다룬다 | 다시 컴파일하면 오류, 옛 바이너리는 새 상수에서 런타임 오류 — CHANGELOG 와 PR 설명에 적는다 |
| 도구 허용 목록에 `BashOutput` 만 있는 에이전트·스킬 | `KillShell` 을 못 쓴다. 상한이 안전장치다. CHANGELOG 에 적는다 |

---

## 7. 테스트 전략

수정 전 코드에서 실패하는 것을 먼저 확인할 행은 ★ 로 표시했다(백로그 닫힘 절의 관례).

**셸 (`aimon-core`)**
- `ShellCancellationSourceTest` — 한 번만 걸린다, 걸린 뒤 등록은 즉시 돈다, 해제된 리스너는 돌지 않는다, 리스너 예외가 `cancel()` 을 깨지 않는다.
- `ExecutionOptionsTest` — 기본은 `none()`, **`toBuilder()` 가 신호를 보존한다.**
- `LocalShellCancellationTest` (Unix 한정, `LocalShellUnixShellTest` 의 조건 관례) — ★ `sleep` 을 띄운 명령을 취소하면
  `ShellCancelledException` 이고 **부모와 자식 pid 가 모두 죽어 있다**(`ProcessHandle.of(pid)`). 죽기 전 출력이 예외에
  실린다. 끝난 뒤의 취소는 결과를 바꾸지 않는다. 임시 캡처 파일이 남지 않는다.
- `WorkingDirectoryShell` 을 거친 취소(격리 브랜치).

**도구**
- `KillShellToolTest` — §3.2 표의 다섯 행. 던지지 않는다(잘못된 입력, 매니저 예외).
- `BashToolTest` — 환경의 상한이 옵션의 timeout 으로 간다 / 없으면 24시간 / 0 이하면 24시간 / 시작 응답 문구. 매니저가 닫혔으면 오류.
- `BashOutputToolTest` — `Killed` 렌더, 다른 런타임의 작업은 not found, 다른 노드의 레코드 보고, 기존 케이스 전부 유지.
- `BackgroundBashManagerTest` — 소유 범위, id 충돌 재시도, 보존 기간 정리, `close()` 가 도는 작업을 취소하고 새 작업을 거절, 저장소 실패 세 경로.
- `InMemoryBackgroundBashStoreTest` — 인터페이스 계약(`putIfAbsent` 거절, `settle` 의 멱등·단방향). 추상 계약 테스트로 써서 다른 구현이 재사용할 수 있게 한다.
- `BashToolTurnIntegrationTest` — 한 턴에서 `Bash(background)` → `KillShell` → `BashOutput` 이 `Killed`.
- `OrcaToolProvidersTest` — 도구 셋 등록, 주입한 매니저를 셋이 공유.

**환경 (`aimon-core`)**
- `PerRuntimeLocalEnvironmentProviderTest` — 런타임 id 마다 다른 워크스페이스, 포크는 부모 그대로, 바인딩 둘 중 하나를
  닫아도 슬롯이 남고 둘 다 닫으면 자식이 닫힌다, 다른 id 의 슬롯은 영향이 없다, 바인딩 없는 `resolve`, 닫힌 뒤 `resolve`,
  핸들의 멱등.

**부트스트랩**
- `AimonStackTenantRuntimeTest` — ★ **축출 뒤 다시 만든 런타임에서 `BashOutput` 과 `KillShell` 이 옛 task id 를 찾는다**
  (수용 기준 5). ★ 테넌트 A · B 가 각각 백그라운드 명령을 돌리는 중에 A 를 축출해도 B 의 셸과 작업이 그대로이고, A 의
  명령도 계속 돌며, A 의 슬롯만 닫힌다(수용 기준 4). `invalidate` 로 같은 id 가 겹칠 때 옛 런타임의 close 가 새 런타임의
  환경을 깨지 않는다.
- `AimonStackProvisioningRollbackTest` — 실패한 프로비저닝이 바인딩을 닫는다. 시작 경로는 한 번만 닫는다.
- teardown 순서 테스트 — `BACKGROUND_COMMANDS` 가 `AGENT_RUNTIMES` 뒤 `AGENT_RESOURCES` 앞. 스택을 닫으면 도는 명령이 죽는다.
- 스펙 테스트 — `provider(...)` 는 스택이 닫고 `shared(...)` 는 닫지 않는다.

**CLI · 스타터** — 기존 조립 테스트가 통과한다. 스타터: 프로퍼티 바인딩, `BackgroundBashStore` 빈 주입.

**ArchUnit** — 기존 규칙 통과(특히 `toolsHoldNoFileSystemOrShellFields`, `environmentSpiDependenciesAreCurated`,
`shellImplMustNotLeakOutsideShellTree`). 새 규칙은 두지 않는다.

**게이트** — `./gradlew checkAll`, `check-translation-staleness.py`, `check-translation-structure.py`, `check-doc-links.py`.

---

## 8. 공개 표면의 변경과 저장소 밖 영향

`CHANGELOG.md` `[Unreleased]` 에 적을 것:

1. **깨짐** — `ExecutionEnvironmentSpec.factory(Function)` 제거. `provider(Supplier)` 또는 `shared(...)` 로 옮긴다.
2. **깨질 수 있음** — `ShellFeature.CANCELLATION` 추가(`default` 없는 `switch`).
3. **동작 변화** — 스택이 닫힐 때 도는 백그라운드 명령을 죽인다(`TeardownPhase.BACKGROUND_COMMANDS`).
4. **동작 변화** — 부트스트랩의 제공자가 축출이 아니라 스택 종료에 닫힌다. 축출은 `bindRuntime` 핸들을 닫는다.
5. **동작 변화** — 끝난 백그라운드 작업은 보존 기간(24시간) 뒤 조회되지 않는다. 다른 런타임의 task id 는 not found.
6. **추가** — `KillShell` 도구, `ShellCancellation` · `ShellCancellationSource` · `ShellCancelledException`,
   `ExecutionOptions.cancellation`, `ExecutionEnvironment.backgroundCommandTimeout()`,
   `ExecutionEnvironmentProvider.bindRuntime()` · `RuntimeBinding`, `BackgroundBashStore`, `BashTaskStatus.KILLED`,
   `PerRuntimeLocalEnvironmentProvider`, `aimon.environment.background-command-timeout`.
7. `BashTool` 은 더는 스레드 풀을 갖지 않는다(`close()` 는 아무것도 하지 않는다).

**aimon-sandbox 에 미치는 영향 (추론 — 그쪽 소스를 보지 못했다).** PR 설명과 백로그에 같은 내용을 적는다.

- 그대로 두어도 컴파일된다(새 SPI 는 모두 기본 메서드이거나 옵션의 새 필드). 예외는 `ShellFeature` 를 `default` 없이
  `switch` 하는 코드와, 문서나 예제가 `ExecutionEnvironmentSpec.factory` 를 쓰는 경우다.
- 그대로 두면 샌드박스의 백그라운드 명령은 `KillShell` 로 멈출 수 없다(오류로 답한다). 멈추게 하려면 셸이
  `CANCELLATION` 을 선언하고 원격 명령 종료와 `ShellCancelledException` 을 구현한다.
- "도는 명령이 슬롯을 하루 동안 깨워 둔다" 는 문제(EE-13 의 "왜")는 환경이 `backgroundCommandTimeout()` 을 돌려주는
  것으로 푼다 — 코어는 이제 그 값을 쓴다.
- 런타임별 자원을 쥔다면 `bindRuntime` 을 구현해 축출 통지를 받는다. 계약의 요점은 "도는 명령은 멈추지 않는다" 다.
- 설계 §13 의 계약 표에 세 행이 더해지므로 그쪽 설계 문서 §7 도 따라와야 한다.

**새 백로그 항목 후보** (번호는 착수 시 문서를 보고 확정 — 지금 마지막은 EE-52):

- EE-53 — 백그라운드 `Bash` 의 교차 노드 종료와 출력. 저장소는 메타데이터만 들고, 다른 노드의 작업은 보고만 한다.
  서브에이전트의 `TaskStopSignal` 같은 신호 SPI 가 필요하다.
- EE-54 — 포그라운드 `Bash` 는 여전히 스레드 인터럽트에 기댄다. 인터럽트에 반응하지 않는 원격 셸에서는 취소 신호를
  포그라운드에도 실어야 한다.
- EE-55 — `LocalShell` 의 트리 종료는 스냅숏 뒤에 태어난 손자를 놓친다(프로세스 그룹이 필요). `KillShell` 이 생기면서
  timeout 때만 보이던 한계가 모델의 요청으로도 보인다.
- EE-56 — 부트스트랩을 거치지 않는 조립(`OrcaAgentRuntimeManager` 를 직접 쓰는 경우)은 기본이 여전히 agent-scoped
  매니저이고 `bindRuntime` 도 스스로 불러야 한다.

---

## 9. 풀지 못한 것 — 열린 질문

과제 문서만으로는 정할 수 없어서, 아래는 **가정으로 두지 않고** 질문으로 남긴다. 각 항목에 설계가 일단 택한 쪽을
적었고, 답이 다르면 바뀌는 범위도 적었다.

- **Q1. `ExecutionEnvironmentSpec.factory` 를 없앨지, 폐기 표시만 하고 남길지.** 결정문은 "SPI 의 모양이 바뀐다" 고만
  한다. 설계는 **제거**를 택했다 — 남기면 그 경로를 쓰는 배포는 고치려던 동작(축출이 제공자를 닫는다)을 그대로 갖는다.
  배포된 아티팩트의 공개 API 이므로 메인테이너의 확인이 필요하다. 남기기로 하면 옛 동작을 그대로 지닌 어댑터와 그
  사실을 알리는 degradation 항목이 더해진다.
- **Q2. 스택이 닫힐 때 백그라운드 명령을 죽일지.** 지금은 남는다(CLI 를 끝내도 모델이 띄운 서버가 계속 돈다). 설계는
  **죽인다** — 애플리케이션 수명의 매니저가 풀을 갖게 되면 닫는 시점이 있어야 하고, 스택을 다시 만드는 호스트(Spring
  컨텍스트 새로 고침, 테스트)에서 명령이 쌓인다. `VirtualShell.close()` 의 Javadoc 도 그렇게 요구해 왔다. 하지만 과제
  문서에 없는 관찰 가능한 변화다. 남기기로 하면 `BACKGROUND_COMMANDS` 단계는 풀만 내리고 신호는 걸지 않는다.
- **Q3. 환경의 상한이 24시간을 넘어도 되는가.** 과제는 "환경이 정하지 않으면 24시간" 이라고만 한다. 설계는 **환경의
  값이 양방향으로 이긴다**고 읽었다(스윕 유예를 따라 올리는 것이 그 귀결이다). 24시간이 천장이라면 `min` 한 줄과 스윕
  조정 삭제로 끝난다.
- **Q4. 작업의 가시 범위는 런타임인가 세션인가.** 지금은 같은 런타임의 어느 세션이든 task id 를 알면 읽을 수 있다.
  설계는 **그 범위를 유지**하고(런타임 id 로만 좁힌다) 종료에도 같은 범위를 적용했다. 세션으로 좁히면 한 테넌트 안의
  다른 사용자가 남의 명령을 죽이는 일을 막지만, 포크·루틴처럼 세션이 없는 실행이 시작한 작업의 주인을 정해야 한다.
- **Q5. aimon-sandbox 쪽 작업의 범위와 시점.** 그 저장소를 보지 못했다. §8 의 영향 목록이 맞는지, 그쪽이 `factory` 를
  쓰는지(Q1 과 얽힌다), 취소·상한·바인딩 구현을 이 PR 과 맞춰 낼지는 메인테이너가 정한다. 이 PR 은 코어 쪽 계약과
  PR 설명·백로그의 영향 기록까지만 한다.
- **Q6. EE-7 의 원래 증상을 재현하지 못했다.** §2-1 은 소스를 읽어 내린 판단이다. 구현의 첫 단계가 특성화 테스트이고,
  결과가 다르면(로컬에서도 축출이 명령을 끊는다면) §3.3 의 "자식을 닫아도 명령이 돈다" 는 근거가 무너지므로 바인딩이
  도는 명령이 끝날 때까지 슬롯을 붙들도록 설계를 고쳐야 한다.

---

## 10. 구현이 이 설계에서 벗어난 점

2026-10-03, `1f8b53f` 위에서 구현했다. 본문과 다르게 한 것, 본문이 정하지 않아 구현이 정한 것, 그리고 하지 못한 것을
적는다. 적지 않은 것은 본문대로다.

### 10.1 본문과 다르게 한 것

- **시작 전에 걸린 신호는 명령을 띄우지 않는다 — §3.1 대로이고 §6 과 다르다.** §3.1 의 계약은 "시작 전에 이미 걸려
  있으면 명령을 띄우지 않고 던진다" 인데 §6 의 "시작 전에 취소" 행은 "프로세스를 띄운 직후 등록하는 리스너가 그 자리에서
  돈다. 명령은 곧바로 죽고" 라고 적었다. 둘은 양립하지 않는다 — 부작용이 있는 명령이 죽이는 데 걸리는 동안 돌고, 외부
  셸이 따라야 할 Javadoc 을 기준 구현이 어긴다(설계 리뷰). `LocalShell.execute` 는 `pb.start()` **전에**
  `isCancelled()` 를 보고 `ShellCancelledException` 을 던진다. 프로세스를 띄운 뒤 등록하는 리스너는 그 사이의 경합을 위해
  남겼다. `LocalShellCancellationTest.cancelledBeforeStartDoesNotRunTheCommand` 가 부작용(파일)이 생기지 않음을 고정한다.
- **취소와 정상 종료가 겹칠 때의 판정에 조건을 하나 더했다.** §6 은 "리스너가 죽이기 전에 세운 플래그로 판정한다" 고만
  했다. 그러면 프로세스가 이미 끝난 뒤 리스너가 돌았는데 실행 스레드가 아직 플래그를 읽기 전이면, 정상 종료한 명령이
  `KILLED` 로 보고된다(설계 리뷰). 리스너는 **프로세스가 아직 살아 있을 때만** 플래그를 세운다
  (`p.isAlive() && cancelled.compareAndSet(false, true)`). 남는 경합은 반대쪽이다 — 살아 있음을 확인한 직후 명령이 제
  힘으로 끝나면 `KILLED` 다. 종료 요청이 살아 있는 명령에 닿은 것이므로 받아들였다.
- **`ShellCancellation` 은 `LlmCancellation` 과 같은 모양이 아니다.** §3.1 은 "같은 모양" 이라고 했지만
  `LlmCancellation.onCancel` 은 `void` 이고 `isSupported()` 가 있다. 이쪽은 §4.1 대로 `onCancel` 이 `Registration` 을
  돌려주고(`CancellationSignal.Registration` 의 모양) `isSupported()` 가 없다 — 지원 여부는 `ShellFeature` 가 답한다.
  Javadoc 에는 "같은 이유로 따로 둔다" 만 적었다.
- **★ 테넌트 테스트는 `AimonStackTenantRuntimeTest` 가 아니라 새 클래스에 있다.** `AimonStackBackgroundBashLifecycleTest`
  다. 실제 프로세스를 띄우는 행(Unix 한정)이라 기존 클래스의 다른 행과 조건이 다르다.
- **`closingTheStackStopsRunningCommands` 는 시작 런타임이 아니라 테넌트 런타임으로 돌린다.** 설계 리뷰가 지적한 대로,
  `BACKGROUND_COMMANDS` 가 `AGENT_RESOURCES` 앞인 이유("제공자가 열려 있어야 한다")는 절반이다. 테넌트의 바인딩은 그보다
  먼저, `AGENT_RUNTIMES` 에서 리졸버가 닫힐 때 닫힌다. 그래도 성립하는 것은 `RuntimeBinding` 계약이 도는 명령을 멈추거나
  그것이 쥔 것을 놓지 못하게 하기 때문이다. `TeardownPhase.BACKGROUND_COMMANDS` 의 Javadoc 에 그렇게 적었다.
- **`close()` 는 신호를 차례로가 아니라 나란히 건다.** §3.4 는 순서를 말하지 않았다. 로컬 셸의 신호 하나는 SIGTERM 유예
  200ms 를 기다릴 수 있으므로, 도는 명령이 많으면 종료가 N × 200ms 가 된다(설계 리뷰). 매니저는 취소를 자기 풀에 올려
  나란히 건 뒤 풀을 내리고 최대 5초 기다린다. 그래도 남은 스레드는 `shutdownNow()` 의 인터럽트를 받는다 — 취소를 선언하지
  않았지만 인터럽트에 반응하는 셸의 명령은 여기서 끝난다(옛 `BashTool.shutdown()` 과 같은 동작).

### 10.2 본문이 정하지 않아 구현이 정한 것

- **조회 결과 타입.** `BackgroundBashLookup` 은 §3.4 의 셋(`LOCAL` · `ELSEWHERE` · `NOT_FOUND`)이고, `ELSEWHERE` 에 두 표지가
  붙는다 — `lostByThisNode()`(레코드의 노드 id 가 이 노드: 재시작으로 핸들을 잃었다)와 `expired()`(`RUNNING` 인 채
  `expiresAt` 을 넘겼다: 결과를 알 수 없고, 매니저가 레코드를 치웠다). `BackgroundBashKill` 은 §4.3 의 다섯 결과와 그
  판단에 쓴 조회를 싣는다.
- **`ELSEWHERE` 에 대한 `BashOutput` 의 답은 `ToolResult.error` 다.** §3.4 는 "보고" 라고만 했다. 출력을 주지 못했으므로
  오류이고, 문구가 어디서 도는지(또는 이 노드가 잃었는지, 상한을 넘겨 결과를 모르는지)를 말한다. `Shell not found` 와는
  다른 문구다. `KillShell` 도 같은 문장에 "여기서 멈출 수 없다" 를 붙인다.
- **`KILLED` 작업에는 exit code 가 없다.** `BashOutput` 은 `Status: Killed` 와 명령, notice, 출력을 보이고 `Exit Code` 줄을
  내지 않는다. 레코드의 `exitCode` 도 비어 있다.
- **시작 응답의 문구.** 셸이 취소를 선언하면 `Use KillShell(taskId="…") to stop it.` 한 줄. 선언하지 않으면 그 줄 대신 "이
  환경의 셸은 도는 명령을 멈출 수 없고 끝나거나 상한(값)에 멈춘다" 를 적는다(§6). 상한은 **환경이 정했을 때만** 말한다 —
  기본 24시간은 모델이 계획에 쓸 정보가 아니다.
- **1초 하한.** 환경이 1초보다 짧은 양수를 주면 1초로 올린다(§3.2 "1초 하한은 포그라운드와 같다").
- **0 이하의 상한은 두 군데서 막는다.** §3.2 는 `BashTool` 이 WARN 후 24시간으로 간다고 했고 그렇게 한다(임의의
  `ExecutionEnvironment` 구현을 위한 방어). 그에 더해 값을 **설정하는** 길 — `LocalExecutionEnvironmentProvider.Builder` 와
  `ExecutionEnvironmentSpec.Builder` 의 `backgroundCommandTimeout(Duration)` — 은 0 이하에 `IllegalArgumentException` 을
  던진다. 그래서 스타터의 `aimon.environment.background-command-timeout=0s` 는 기동 실패다. 조용히 24시간이 되는 것보다
  낫다고 봤다.
- **로컬 제공자 빌더에 더한 이름.** §3.3 의 "소유하는 파일 시스템 옵션" 은 `Builder.ownedFileSystem(VirtualFileSystem)`,
  "경로 규칙을 거치지 않은 파일 시스템 접근자" 는 `rawFileSystem()` 이다. 소유한 파일 시스템도 빌린 것처럼 스테이징
  스윕을 하지 않는다(원격일 수 있다).
- **호출자 제공자 + 파일 시스템 팩토리 배치의 소유.** §3.3 은 로컬 라우터의 경우만 적었다(설계 리뷰). `shared(...)` 나
  `provider(...)` 와 `FileSystemSpec.factory` 를 함께 쓰면 파일 시스템을 맡길 슬롯이 없으므로 **지금처럼 런타임의 싱크가
  소유한다**. 이 경우 겹침 문제는 생기지 않는다 — 파일 시스템이 id 별 슬롯이 아니라 `createRuntime` 호출마다 만들어지므로
  겹친 두 런타임은 각자의 인스턴스를 쥐고 닫는다. `StackAgentRuntimeProvisioner.createStores` 의 주석에 적었다.
- **제공자가 생기는 시점과 등록.** 스택의 제공자는 `StackAgentRuntimeProvisioner.Builder.build()` 에서 생긴다(공급자를 그때
  부른다). `AimonStackBuilder` 가 곧바로 `provisioner.ownedEnvironmentProvider()` 를 `AGENT_RESOURCES` 에 올린다. 그 사이에
  던질 수 있는 코드는 없다.
- **`registerTask` 로 넣은 작업은 저장소에 쓰지 않는다.** 소유자도 취소 핸들도 없는 노드 로컬 작업이다. `removeTask` 와
  `clear()` 는 로컬 작업과 함께 레코드도 지운다(남기면 "이 노드가 재시작으로 잃은 작업" 으로 보인다).
- **id 재시도는 16번까지.** 그 뒤에는 `IllegalStateException` → `Bash` 가 오류로 답한다.
- **매니저의 스레드는 데몬이다.** 인자 없는 `OrcaBashToolProvider()` 의 매니저는 아무도 닫지 않는다(설계 리뷰). 옛
  `BashTool` 의 풀과 같은 이유로 데몬 스레드라 JVM 종료를 막지 않는다.
- **`KillShell` 의 파괴성을 적었다.** 가시 범위를 런타임으로 둔 것(Q4)은 종료에도 그대로 적용되어, 한 테넌트 런타임의 어느
  세션이든 다른 세션이 띄운 명령을 멈출 수 있다. 도구 Javadoc 과 CHANGELOG 에 한 줄씩 적고 EE-58 로 올렸다.

### 10.3 설계 리뷰의 지적 가운데 구현하지 않은 것

- **`BackgroundBashStore` 에 열거·만료 메서드를 더하지 않았다.** 리뷰는 `removeExpired(Instant)` 를 지금 넣거나 백로그에
  올리라고 했다. 승인된 SPI(§4.3)는 네 메서드이고, 소비자가 없는 메서드를 계약에 미리 넣는 것은 그 모양을 추측하는
  일이다. EE-57 로 올렸다 — 영속 저장소를 처음 구현할 때가 그 모양이 드러나는 때다.

### 10.4 열린 질문과 새 백로그 항목

§8 은 EE-53~EE-56 넷을 열라고 했다. 일곱을 열었다.

| 항목 | 출처 | 내용 |
|------|------|------|
| EE-53 | §8 | 백그라운드 `Bash` 의 교차 노드 종료와 출력 |
| EE-54 | §8 | 포그라운드 `Bash` 는 여전히 스레드 인터럽트에 기댄다 |
| EE-55 | §8 | `LocalShell` 의 트리 종료는 스냅숏 뒤에 태어난 손자를 놓친다 |
| EE-56 | §8 | 부트스트랩을 거치지 않는 조립은 작업 목록과 바인딩을 스스로 챙겨야 한다 |
| EE-57 | 설계 리뷰 | `BackgroundBashStore` 에는 레코드를 열거하거나 만료시킬 수단이 없다 |
| EE-58 | Q4 | 백그라운드 작업의 가시 범위가 런타임이라 다른 세션의 명령을 멈출 수 있다 |
| EE-59 | Q5 · Q1 | aimon-sandbox 가 취소 · 상한 · 바인딩을 구현해야 하고, `factory` 제거의 영향을 그쪽에서 확인해야 한다 |

카운트는 **59 = 열림 43 · 닫힘 16** 이다. 착수 시점의 최고 번호는 EE-52 였다.

나머지 질문:

- **Q1 (`factory` 를 없앨지 남길지)** — 설계가 택한 대로 **없앴다**. CHANGELOG 에 깨지는 변경으로 적었다. 메인테이너의
  확인이 필요한 결정이라는 점은 그대로이고, aimon-sandbox 쪽 확인과 함께 EE-59 에 적었다.
- **Q2 (스택이 닫힐 때 명령을 죽일지)** — 설계가 택한 대로 **죽인다**. 과제 문서에 없는 관찰 가능한 변화이므로 CHANGELOG
  에 별도 줄로 적었다(CLI 를 끝내면 모델이 띄운 서버도 끝난다). 백로그 항목으로 올리지는 않았다 — 남은 일이 없고,
  되돌린다면 `BackgroundBashManager.close()` 에서 신호를 걸지 않는 한 줄이다.
- **Q3 (상한이 24시간을 넘어도 되는가)** — 설계가 읽은 대로 **환경의 값이 양방향으로 이긴다**. Javadoc 과 명세 §5.3 에
  적었다. 24시간이 천장이어야 한다면 `BashTool.backgroundCeiling` 에 `min` 한 줄과 스윕 유예 조정 삭제다.
- **Q6 (EE-7 의 원래 증상)** — **돌려서 확인했다.** `AimonStackBackgroundBashLifecycleTest.evictedRuntimesTaskIsFoundByItsSuccessor`
  를 구현 전 코드(`1f8b53f`)에서 먼저 돌렸다: 축출 뒤에도 명령의 pid 는 살아 있었고, 실패는 그다음 줄의
  `Shell not found: bash_…` 였다. §2-1 이 읽어서 내린 판단 그대로다 — 로컬에서 끊기는 것은 셸이 아니라 작업 목록이다.
  §3.3 의 "자식을 닫아도 명령이 돈다" 는 근거가 성립하므로 바인딩이 슬롯을 붙들도록 설계를 고칠 필요는 없었다.

### 10.5 쓰지 않았거나 다르게 덮은 테스트

- **★ 표시 가운데 구현 전 코드에서 실패를 확인한 것은 하나다** — 위 Q6 의 행. 나머지 ★ 둘은 그렇게 확인하지 못했다.
  `LocalShellCancellationTest` 는 새 API(`ExecutionOptions.cancellation`)를 쓰므로 옛 코드에서 컴파일되지 않는다. "A 를
  축출해도 B 가 그대로" 는 옛 코드에서도 참이다(제공자가 런타임마다 하나였으므로 B 의 것은 원래 닫히지 않았다) — 이 행이
  고정하는 것은 회귀이고, 새로 참이 된 부분은 같은 테스트의 "다른 테넌트의 task id 는 not found" 다.
- **`invalidate` 로 같은 id 가 겹치는 행**은 백그라운드 명령 없이 포그라운드 `Bash` 와 파일 시스템 상태로 확인한다
  (`overlappingRuntimesOfOneIdShareTheSlot`). 슬롯 단위의 같은 성질은 `PerRuntimeLocalEnvironmentProviderTest` 가 프로세스
  없이 고정한다.
- **CLI 는 새 테스트가 없다.** `AgentSetupFactory` 의 변경은 `factory(...)` → `provider(...)` 한 줄이고 `create()` 는 스택
  전체를 띄운다. 기존 CLI 테스트가 통과하는 것으로 확인했다.
- **스타터의 설정 메타데이터**(`additional-spring-configuration-metadata.json`)에는 `aimon.environment.*` 항목이 원래
  없어서 더하지 않았다. 프로퍼티 바인딩과 `BackgroundBashStore` 빈 주입은
  `AimonApplicationContributionsTest.backgroundCommandSettingsReachTheSpec` 이 확인한다.
- **aimon-sandbox 쪽은 아무것도 확인하지 못했다.** 로컬에 체크아웃이 없다. §8 의 영향 목록은 SPI 로부터의 추론 그대로다.

### 10.6 문서

§5 의 문서 표에 없던 것을 고쳤다(설계 리뷰가 `grep` 으로 찾았다).

- [`execution-environment-implementation.md`](execution-environment-implementation.md) — `ExecutionEnvironmentSpec.factory` 를
  적은 §10.6 에 "그 뒤 EE-7 로 바뀌었다" 는 주석을 달았다. 그 문서의 나머지는 승인본이라 고치지 않았다.
- [`../../getting-started/embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md) 와 그
  번역본 — `aimon.environment.*` 블록에 새 프로퍼티를 **주석 줄로** 더했다. 기본값이 "미설정" 이라 값이 있는 줄로 적으면
  `AimonDocumentedPropertiesTest` 가 기본값 불일치로 읽는다.
- [`../integration/spring-boot-starter.md`](../integration/spring-boot-starter.md) 의 프로퍼티 블록에 같은 줄과
  `BackgroundBashStore` 빈.
- `docs/backlog/multi-instance-readiness.md` 의 축별 표에 백그라운드 `Bash` 작업 목록 행.

리뷰가 함께 꼽은 `docs/features/skill/builtin-agent-skill-guide.md` 는 고치지 않았다. 그 문서가 적는 것은
`aimon.environment.staging.max-bytes` 하나이고 스테이징 설명의 일부다 — 백그라운드 상한이 들어갈 자리가 아니다.

### 10.7 PR #205 리뷰에서 고친 것

본문(§3 · §4 · §6)은 승인본이라 그대로 두고, 리뷰가 찾아 고친 것을 여기 적는다.

- **SIGTERM 을 무시하는 자식도 죽인다.** `LocalShell.destroyForciblyQuietly` 는 SIGKILL 로 올리는 조건이 "부모가 200ms
  유예 뒤에도 살아 있다" 하나였다. 부모 셸은 SIGTERM 에 곧바로 죽고 자식은 `trap '' TERM` 으로 버티면, 자식이 살아남은 채
  `KillShell` 이 "stopped" 라고 답했다. 이제 스냅숏의 모든 핸들과 부모가 **한 유예를 나눠 쓰고**, 유예가 끝났을 때 아직
  살아 있는 것은 부모의 생사와 상관없이 하나하나 SIGKILL 한다. timeout · 인터럽트 · 취소가 같은 코드를 타므로 셋 다
  바뀐다. `LocalShellCancellationTest.cancelKillsAChildThatIgnoresTerm` 이 고정한다. 스냅숏 뒤에 태어난 손자(EE-55)는 여전히
  남는다(이 문장은 PR #205 시점의 것이다 — 지금 남는 범위와 답의 문구는 §10.8). `KillShell` 의 답은 "stopped: the command and the processes it was running were terminated" 로, §6 표의 "stop
  requested 이상을 약속하지 않는다" 보다 한 걸음 더 말한다 — 열거한 트리는 이제 확실히 죽기 때문이다.
- **슬롯 생성이 제공자 전체의 락 밖에서 돈다.** `PerRuntimeLocalEnvironmentProvider` 는 워크스페이스 함수(디렉터리 생성,
  스테이징 스윕, 호출자의 `FileSystemSpec.factory` 와 그 원격 연결)를 `synchronized (lock)` 안에서 불렀고, 모든 실행의
  `resolve()` 가 같은 락을 잡았다. 한 테넌트의 느린 생성이 다른 모든 테넌트의 턴을 막았고, 다른 스레드의 `workspace()` 를
  기다리는 함수는 교착했다. 이제 id 별 진행 중 빌드(`CompletableFuture`)를 락 아래에서 하나만 등록하고 함수는 락 없이
  부른다. 같은 id 의 동시 첫 요청은 그 빌드를 기다려 같은 슬롯을 받고, 실패한 빌드는 지워져 다음 요청이 다시 짓는다. 슬롯
  맵과 바인딩 수는 여전히 락 아래에서만 바뀐다. 짓는 사이에 `close()` 가 돌면 지은 것을 닫고 "closed" 로 던진다. 같은 id 의
  `workspace()` 를 기다리는 함수는 여전히 교착한다 — 자기 자신을 기다리는 초기화이고, 다른 id 는 영향이 없다.
  `PerRuntimeLocalEnvironmentProviderTest` 의 네 테스트(느린 생성이 다른 id 를 막지 않음, 동시 첫 요청은 빌드 한 번, 실패한
  빌드의 재시도, 생성 중 `close()`)가 고정한다. 바인딩 없이 슬롯이 생기면(축출 뒤 늦게 온 `resolve` 포함) DEBUG 로 남긴다.
- **레코드에서 `command` 를 뺐다.** §4.3 은 `BackgroundBashRecord` 에 명령 문자열을 넣었지만 읽는 곳이 없었다. 명령줄에는
  자격 증명이 들어갈 수 있고(`curl -H "Authorization: …"`, `PGPASSWORD=… psql`), 공유 저장소에 넣으면 프로세스보다 오래
  남는다. 아직 릴리스되지 않은 SPI 라 지금 뺐다. `BackgroundBashStore` 의 Javadoc 이 "레코드에 명령 문자열이나 출력을 싣지
  않는다" 를 계약으로 적는다. 명령은 그 노드의 `BackgroundBashTask` 에만 있다.
- **닫히는 중에 시작된 작업.** `start` 가 `ensureOpen()` 을 통과한 뒤 `close()` 가 돌면 `close()` 의 신호 순회가 그 작업을
  놓칠 수 있었다(5초 뒤 `shutdownNow` 의 인터럽트에 기댐). 이제 `start` 가 작업을 넣은 뒤 `closed` 를 다시 보고 그 자리에서
  신호를 건다. 경합 창을 결정적으로 재현할 갈고리가 없어 테스트는 더하지 않았다.
- **소유자 없는 작업의 가시 범위를 적었다.** `AGENT_RUNTIME_ID` 가 없는 컨텍스트에서 시작한 작업은 소유자가 없고, 그런
  컨텍스트의 모든 호출자가 보고 멈출 수 있다. `OrcaBashToolProvider(BackgroundBashManager)` · `BackgroundBashManager.find`
  의 Javadoc 과 CHANGELOG 에 적었다.
- **모델에게 노드 id 를 보이지 않는다.** `BashOutput` · `KillShell` 의 "다른 노드에서 도는 작업" 답에서 노드 id 를 뺐다.
  배포의 내부 이름이고 모델에게는 뜻이 없다.
- **CHANGELOG 와 CLI 문서.** 호출자 제공자 + `FileSystemSpec.localAt` 조합에서 `AimonStack.fileSystem(id)` 가 이제 `.aimon/`
  제어 저장소를 돌려준다는 이전 줄을 더했다(전에는 `LocalExecutionEnvironmentProvider` 를 돌려주는 `factory` 면 그 작업
  공간이었다 — `StackAgentRuntimeProvisioner.createLocalStores`). `modules/aimon-cli/README.md` 에 CLI 를 끝내면 모델이 띄운
  백그라운드 명령도 끝난다는 줄을 더했다.

### 10.8 EE-55 — 유예 뒤에 자손을 다시 열거한다 (2026-10-05)

§2-2 · §6 이 "그대로 물려받는다" 고 적은 스냅숏 경합을 좁혔다. 본문은 그대로 두고 바뀐 사실을 여기 적는다.

- **무엇이 바뀌었나.** `LocalShell.destroyForciblyQuietly` 는 유예가 끝난 뒤, 강제 종료 직전에 자손을 한 번 더 열거한다
  (`sweepLateDescendants`). 걷는 출발점은 부모만이 아니라 **그 시점에 살아 있는 모든 핸들**이다 — 죽은 프로세스의
  `descendants()` 는 비어 있으므로(자식이 그 순간 재부모화된다) "부모를 죽인 뒤 다시 훑는다" 는 아무것도 찾지 못한다.
  새로 찾은 프로세스는 멈춤 요청 뒤에 태어난 것이라 SIGTERM 과 유예 없이 곧바로 SIGKILL 한다. timeout · 인터럽트 ·
  취소 · 스택 종료가 같은 코드를 타므로 넷 다 바뀐다.
- **한 번만 걷는다.** "새 것이 안 나올 때까지 반복" 은 남은 틈을 줄이지 않는다. `descendants()` 는 프로세스 테이블을 한
  시점에 읽으므로 살아 있는 뿌리에서 한 번 걸으면 그 시점 기준으로 완전하고, 중요한 것은 **마지막 열거와 kill 사이의
  시간**뿐이다. 반복은 그 자리에 열거를 한 번 더 끼워 넣는다. 조상이 이미 걸린 핸들은 건너뛰어, 보통은 테이블을 한 번
  읽고 트리가 SIGTERM 에 다 죽었으면 한 번도 읽지 않는다.
- **프로세스 그룹을 고르지 않은 이유.** `ProcessBuilder` 에는 새 그룹으로 띄우는 길이 없고, 그 일을 하는 래퍼
  `setsid(1)` 는 macOS 에 없다(확인: `which setsid` → not found, Darwin 25.6). 래퍼를 끼우면 **모든** 명령의 기동 방식이
  바뀐다. 그룹도 완전하지 않다 — 스스로 `setsid` 를 부른 프로세스는 그룹을 떠난다.
- **지금도 남는 것.** ① 부모가 이미 죽은 프로세스 — 이중 fork, `( cmd & )`, 데몬화, 그리고 `TERM` 핸들러가 fork 한 뒤
  유예 안에 끝난 경우. ② 마지막 열거와 그 부모의 kill 사이에 태어난 프로세스. `TERM` 을 무시하고 쉬지 않고 fork 하는
  명령으로 재 보면(macOS, 한 번의 실측) 50ms 간격에서 100회 중 2회, fork 만 반복할 때 40회 중 6회, 매번 프로세스
  하나가 남았다. 고치기 전에는 같은 명령이 20회 중 20회, 합쳐 112개를 남겼다.
- **항목 본문이 틀렸던 것.** `nohup` 과 `setsid` 는 그 자체로는 트리를 벗어나지 않는다. `ProcessHandle.descendants()` 는
  부모 pid 로 걷고, 둘은 시그널 처리나 세션을 바꿀 뿐 부모를 바꾸지 않는다. 부모 셸이 살아 있는 동안 둘 다 열거되고
  죽는다(실측). 벗어나는 것은 **부모가 끝난** 프로세스다.
- **모델에게 하는 말.** `KillShell` 의 답은 "stopped: the command and the processes still attached to it were terminated.
  A process it detached (a daemon, anything started with a double fork) is not stopped by this and may still be running."
  이고, 도구 설명도 같은 범위를 말한다.
- **테스트.** `LocalShellCancellationTest` 의 `cancelKillsAProcessBornAfterTheStopRequest`(셸이 `TERM` 에 fork 하고 남는다),
  `cancelKillsAProcessBornUnderASurvivingChild`(부모 셸은 죽고 자식만 남아, 그 자식의 핸들로만 닿는다),
  `timeoutKillsAProcessBornAfterTheStopRequest`. 셋 다 고치기 전 코드에서 늦게 태어난 프로세스가 살아남아 실패했다.
  쉬지 않고 fork 하는 경우는 위 ②가 남아 결정적이지 않으므로 테스트로 고정하지 않았다.
