# 백그라운드 모니터 (Monitor)

> **Status**: IMPLEMENTED — `aimon-core`(`at.aimon.core.tools.monitor`), `aimon-bootstrap`, `aimon-cli`. 기본으로 꺼져 있다.

사용법은 [모니터 가이드](../../features/tool/monitor-guide.md) 에 있다.

---

## 1. 문제

에이전트가 오래 걸리는 외부 상태를 지켜보며 다른 일을 계속해야 하는 경우가 있다.

1. **로그 감시** — 배포 중 `app.log` 에 `ERROR` 가 찍힐 때마다 알고 싶다
2. **외부 스트림** — 알람·이벤트 서버가 WebSocket 으로 밀어 주는 이벤트를 받고 싶다
3. **호스트 고유 소스** — 운영 앱이 이미 가진 Kafka 토픽, 쿠버네티스 watch, 알람 큐를 에이전트에게 이벤트로 넘기고 싶다

다른 도구로는 셋 다 되지 않는다.

| 수단 | 왜 안 되는가 |
|------|-------------|
| `Bash` 포그라운드 실행 | 10분 상한에 막히고, 그동안 턴이 묶인다 |
| `Bash(run_in_background)` + `BashOutput` | 출력이 명령이 끝나야 채워진다. `tail -f` 는 끝나지 않으므로 영원히 빈 결과다. 끝나더라도 모델이 폴링해야 한다 |
| 스케줄 루틴 · rewake | 미리 정한 시각이나 등록된 envelope 에 반응한다. 실행 중에 생기는 감시와 수명이 다르다 |

필요한 것은 이벤트가 생길 때마다 알림이 모델에게 밀려오는 원시다.

**경계.** 모니터는 쉬는 세션을 스스로 깨우지 않는다. 턴을 여는 일은 세션 계층의 기능이며
([쉬는 세션 깨우기](../session/idle-wake.md)) 기본으로 꺼져 있다. 켜면 기본으로 모니터 알림이 깨운다. 알림이 언제
모델에게 가는지는 §6.2 가 정한다.

---

## 2. 결정 요약

| # | 결정 | 근거 절 |
|---|------|--------|
| D1 | 이벤트 생산(source)과 정책(배칭·상한·소유권·전달)을 나눈다. 셸은 source 중 하나다 | §3 |
| D2 | source 는 `MonitorSourceFactory` 로 kind 이름에 등록한다. 기본 제공은 `shell` · `websocket` 둘, 나머지는 호스트가 Java 로 등록한다 | §4 |
| D3 | 매니저는 application-scoped, 소유권은 백그라운드 `Bash` 와 같은 `BackgroundTaskOwner` 로 정한다. 세션이 있는 소유자만 시작할 수 있고, 세션이 삭제되면 삭제한 노드의 그 세션 모니터는 멈춘다 | §5 |
| D4 | 알림은 메시지 큐(`NEXT`)로 가되 `QueuedInput` 의 선택 필드 `sessionId` 로 세션을 지정한다. 세션 지정 항목은 그 세션의 턴만 드레인한다 | §6.1 |
| D5 | 실행기는 세션 지정 항목을 턴 시작에도 드레인한다. CLI 의 턴 뒤 재생은 이 세션의 항목을 재생하되 모니터 항목은 남겨 둔다 | §6.2 |
| D6 | 큐에는 모니터당 미드레인 배치를 최대 하나만 둔다. 나머지 이벤트는 매니저 버퍼에 모았다가 드레인 뒤 한 항목으로 낸다 | §6.3 |
| D7 | 셸 스트리밍은 `ShellFeature.STREAMING` 과 `VirtualShell` default 메서드로 연다. 지원 여부는 실행 시점에 판정한다 | §7 |
| D8 | 도구 스키마는 상수다. `command` / `ws` / `source` 를 항상 광고하고, 쓸 수 없는 형태는 실행 시점에 오류로 거절한다 | §4.3 |
| D9 | 권한은 `command` 형태에만 명령 주체를 쓰고, `ws` · `source` 형태는 도구 자신의 규칙으로 판정한다 | §4.4 |
| D10 | `Monitor` 는 기본으로 꺼져 있다(`ToolSpec.monitorEnabled`). 입력되지 않은 메시지를 대화에 넣고, `command` 는 셸 명령을 최대 30분 돌린다 | §5.1 |
| D11 | 멈춘 모니터의 버퍼는 버린다. 모델이 스스로 멈췄거나 받을 턴이 없다 | §3.2 |

---

## 3. 구조

```text
 MonitorSource (SPI)              MonitorManager (application-scoped)        MessageQueueManager
 ───────────────────              ─────────────────────────────────          ───────────────────
 shell     (VirtualShell 스트리밍) ┐   소유권 · 배칭 · 속도 제한 · timeout ·        QueuedInput(NEXT,
 websocket (OkHttp WebSocket)    ├─▶ 동시 개수 상한 · 모니터별 대기 버퍼 ·   ─▶     runtime, session)
 호스트 등록 kind                  ┘   MonitorStore 메타데이터                      → 턴 시작 / iteration 끝
```

source 는 이벤트를 만들기만 한다. 배칭, 속도 제한, 타임아웃, 전달은 매니저가 정한다. 그래서 호스트가 등록한 source
도 기본 source 와 같은 상한 아래 있다.

### 3.1 SPI

```java
public interface MonitorSource {
    /** 이벤트 생산을 시작한다. 돌려준 핸들로 멈춘다. 블로킹하지 않는다. */
    MonitorHandle start(MonitorEventSink sink);
}

public interface MonitorEventSink {
    void emit(String event);      // 이벤트 한 건. 여러 줄이어도 한 건이다
    void complete(String detail); // 자연 종료 (예: "exit code 0", "socket closed 1000")
    void fail(String reason);     // 오류 종료
}

public interface MonitorHandle {
    void stop();                  // 멱등. stop 이후의 emit/complete/fail 은 무시된다
}

public interface MonitorSourceFactory {
    String kind();                                // "shell", "websocket", "alarms" ...
    String describe();                            // 모델에게 보일 한 줄 설명과 params 모양
    MonitorSource create(MonitorRequest request); // 거절은 MonitorRequestException
}
```

`MonitorRequest` 는 불변 클래스 + 빌더다. 소유자, 호출의 `ToolContext`(실행 환경을 읽는 데 필요), kind 별 파라미터
맵, 매니저가 정한 타임아웃을 싣는다.

**`fail` 이 계약에 있는 이유.** 성공 신호만 있는 감시는 소스가 죽어도 아무 말이 없고, 그 침묵은 "아직 도는 중" 과
구별되지 않는다. source 가 끝나는 모든 길은 `complete` 나 `fail` 을 지난다. 매니저는 타임아웃·속도 제한 중지도 같은
종료 알림으로 낸다(§6.4).

### 3.2 모니터 하나의 상태 기계

```text
 RUNNING ──emit──▶ RUNNING
    │
    ├── complete / fail / timeout / suppressed ───▶ ENDED  (종료 알림 1회)
    └── stop / session deleted / shutdown ────────▶ ENDED  (알림 없음)
```

전이는 모니터별 락 아래서 일어나고 락 안에서 외부를 호출하지 않는다. `ENDED` 는 흡수 상태다. 그래서 source 스레드의
`complete`, 타임아웃 타이머, `MonitorStop` 이 동시에 와도 이긴 하나만 전이한다. 종료 알림은 대기 버퍼를 비운 뒤에 큐에
들어가므로 마지막 줄보다 먼저 도착하지 않는다.

큐에 넣기와 source 중지는 모니터 락 밖에서 한다. 중지는 별도 스레드에서 한다. 외부 호출이 락을 쥔 채 정체하지 않게 하기
위해서다.

모델이 스스로 멈춘 경우(`MonitorStop`), 세션 삭제, 앱 종료를 빼면 모델은 종료 알림을 정확히 한 번 받는다. 이 세 경우에는
버퍼에 남은 이벤트를 버린다.

---

## 4. Source

### 4.1 기본 제공

| kind | 이벤트 단위 | 파라미터 | 대상 검사 |
|------|-----------|---------|----------|
| `shell` | stdout 한 줄 | `command` | 실행 환경의 셸이 `STREAMING` 과 `CANCELLATION` 을 선언해야 한다(§7) |
| `websocket` | 텍스트 메시지 하나 (조각 프레임은 합쳐서 한 건) | `url`, `protocols` | `ws`/`wss` 만. `SsrfGuard` 로 사설망 차단 |

바이너리 메시지는 내용 대신 `[binary frame, N bytes]` 한 줄로 알린다. 30초마다 ping 을 보내, 조용히 죽은 연결을
타임아웃까지 기다리지 않는다.

WebSocket 은 OkHttp 로 연다. core 가 이미 의존하는 라이브러리이고 `WebHttpClientFactory` 가 만든 클라이언트를 그대로
쓰기 위해서다. `SsrfGuard.isSafe(String)` 는 `http`/`https` 외의 스킴을 거절하므로 검사할 때 `ws`→`http`, `wss`→`https`
로 바꿔 넘긴다. 핸드셰이크는 같은 호스트·포트로 가는 HTTP 요청이다. 그 클라이언트의 네트워크 인터셉터
(`SsrfRedirectInterceptor`)가 실제로 연결된 소켓 주소를 다시 검사하므로, URL 검사 뒤 DNS 가 사설 주소로 바뀌는
rebinding 도 막힌다. 모델이 소켓을 열면 안 되는 조립은 `websocket` 을 등록하지 않는다.

### 4.2 호스트 등록

호스트는 `MonitorManager.Builder#source` 로 kind 를 등록한다. 모델은 kind 와 파라미터만 고르고 원시 명령이나 URL 을
쓰지 않는다. 연결 정보·자격 증명·필터는 호스트 코드에 있고 모델이 바꿀 수 없다.

기본 kind 도 같은 경로를 탄다. `Builder` 는 `shell` 과 `websocket` 을 빌드 시점에 만들고(대체되거나 빠진 것은 만들지
않는다), 조립은 `excludeSource(kind)` 로 하나를 빼거나 `sources(...)` 로 목록 전체를 바꾼다.

**스택은 `Bash` 가 꺼져 있으면 `shell` 을 뺀다.** 허용 목록이 없는 배포에서는 `Bash` 위임 검사(§4.4)가 돌 항목이
없으므로, 빼지 않으면 `Bash` 를 끈 배포에서도 모니터로 명령을 돌릴 수 있다. `websocket` 은
`ToolSpec.monitorWebSocketEnabled` 로 끈다.

### 4.3 도구 입력

`command` / `ws` / `source`(+`params`) 중 정확히 하나다. 판별 필드를 셋으로 둔 것은 Claude Code 의 Monitor 와 같은
모양이라 모델이 이미 익숙하고, 각 형태의 필드를 스키마가 이름으로 말해 주기 때문이다. 입력 예는
[가이드](../../features/tool/monitor-guide.md) 에 있다.

**스키마는 상수다.** `Tool.getDefinition()` 은 `ToolContext` 를 받지 않고, 셸은 실행마다 `ExecutionEnvironment` 에서
온다. 그래서 "이 셸은 스트리밍을 못 하니 `command` 를 빼자" 는 정의 시점에 알 수 없다. 세 필드를 항상 광고하고, 쓸 수
없는 형태는 실행 시점에 이유를 붙여 오류로 돌려준다. 호스트 kind 목록은 매니저가 생성 시점에 고정하므로 도구 설명에
넣는다. `oneOf` 는 쓰지 않고 "정확히 하나" 는 `execute()` 가 검사한다.

`MonitorStop(monitorId)` 는 같은 소유자의 모니터만 찾는다. 도구를 따로 두는 이유는 §9.2 에 있다.

### 4.4 권한

권한 판정의 일반 규칙은 [도구 계약](tool-contract.md) 이 정본이다. 여기에는 모니터에 적용한 결과만 적는다.

`PermissionSubject.Kind` 는 `COMMAND` 와 `PATH` 둘뿐이다. URL 을 `COMMAND` 주체로 넘기면 `ToolPattern` 이 셸
메타문자(`?`, `&`)를 거절해 패턴이 설정된 순간 모든 URL 이 막힌다. 그래서 두 장치를 함께 쓴다.

- `ToolPermissionSubjectAware` — `command` 형태일 때만 `PermissionSubject.command(command)` 를 돌려준다. `Bash` 와
  같은 주체이므로 `Monitor(tail -f:*)` 같은 패턴이 그대로 통한다. 셸 메타문자가 든 명령은 어떤 명령 패턴에도 맞지
  않으므로 패턴으로 좁혀야 하면 호스트 source 를 쓴다
- `CustomToolPermissionAware` — 주체가 비었을 때(`ws`·`source` 형태) 규칙이 판정한다. 패턴을 `ws:<url>` /
  `source:<kind>` 문자열과 비교하고 끝의 `*` 만 와일드카드다. 패턴 없는 `Monitor` 항목은 전부 허용한다

`command` 형태는 **`Bash` 항목도 통과해야 한다.** 위임 대상은 `Bash` 이고 주체는 그 명령이다. 위임이 필요한 이유와 대상 도구
항목별 판정은 [도구 계약](tool-contract.md) 의 위임 절에 있다.

---

## 5. 수명과 소유권

### 5.1 매니저는 application-scoped

[스코프 모델](../../overview/scope-model.md) 기준으로 `MonitorManager` 는 Application 수명이다. 테넌트 runtime 이
축출되어도 감시는 계속되어야 하고, `LiveSession.close()` 가 감시를 닫으면 안 된다. 스택은
`TeardownPhase.BACKGROUND_COMMANDS` 에 매니저를 올려 종료 시 모든 source 를 멈춘다.

스택은 `ToolSpec.monitorEnabled` 가 켜졌을 때만 매니저를 만든다(D10).

모니터 하나의 수명은 셋 중 먼저 오는 것이다: source 의 종료, `MonitorStop`, 타임아웃. 타임아웃이 있는 이유는 잊힌
모니터가 노드 자원과 큐를 영원히 쓰지 않게 하기 위해서다. 더 오래 지켜보려면 모델이 다시 시작한다. source 는 매니저가
정한 타임아웃을 받는다.

`shell` source 는 시작 시점의 실행 환경을 붙잡는다([백그라운드 명령](background-commands.md)과 같은 규칙). 환경
제공자를 runtime 이 소유하는 조립에서는 runtime 이 닫힐 때 셸이 모니터 밑에서 닫힐 수 있다. 그때 명령은 실패하고
모니터는 `failed` 로 끝난다.

### 5.2 소유자

소유권은 `BackgroundTaskOwner.of(ToolContext)` 로 정한다. 타입의 정의는 [백그라운드 명령](background-commands.md) 이
정본이고, 모니터는 같은 타입을 쓴다.

**세션이 없는 소유자는 거절한다.** 예약 루틴이나 누구의 턴도 요청하지 않은 포크는 실행이 끝나면 알림을 받을 주체가
사라진다. 세션의 턴과 세션을 위해 생성된 포크(`INVOKING_SESSION_ID`)는 받아들이고, 포크가 시작한 모니터의 알림은 호출한
세션으로 간다.

**세션이 삭제되면 그 세션의 모니터를 멈춘다.** 스택은 세션 라우터의 `SessionLifecycleListener` 로
`MonitorManager.stopSession(id)` 를 건다. 모델에게 알리지 않는다. 삭제된 세션의 대기 항목은 큐에서 걷어낸다. 삭제 이벤트는
삭제한 노드에서만 발화하므로, 다른 노드에서 돌던 그 세션의 모니터는 타임아웃까지 돈다.

큐는 매니저가 생성 시점에 받는다. 호출의 `ToolContextKeys.MESSAGE_QUEUE_MANAGER` 에서 읽으면, 포크의 도구 컨텍스트에는 그
키가 없어서 포크가 시작한 모니터가 늘 거절된다. 스택의 큐는 애플리케이션에 하나이므로 매니저가 들고 있어도 수명이 맞다.

### 5.3 멀티 인스턴스

메타데이터(id, 소유자, 노드, kind, 설명, 시작·마감 시각, 상태)는 `MonitorStore` SPI 로 가고 기본 구현은 in-memory 다.
연결 자체(프로세스, 소켓, 호스트 구독)는 그 노드의 메모리에 있다. 다른 노드에서 `MonitorStop` 이 오면 "다른 노드에서 실행
중" 이라고 답한다. 마감이 지난 레코드는 죽은 노드가 남긴 것이므로 그렇게 답하지 않는다.

**알림도 노드 로컬이다.** 기본 큐 저장소는 in-memory 이므로, 세션이 다른 노드로 옮겨 가면 이 노드의 모니터 알림은 그
세션에 닿지 않는다. 모니터는 타임아웃까지 돌다 끝난다. 조립이 공유 `MessageQueueRepository` 를 주면 알림이 따라가는데,
그때 세션 간 누수를 막는 것이 `sessionId` 필터다(§6.1).

노드가 죽은 뒤 감시를 다른 노드에서 다시 여는 일은 범위 밖이다. 셸 프로세스는 옮길 수 없고, 호스트 source 의 재연결
의미는 호스트마다 다르다.

---

## 6. 전달

### 6.1 세션을 지정한 큐 항목

알림은 서브에이전트 완료 알림과 같은 길을 탄다. `QueuedInputPriority.NEXT` 로 `MessageQueueManager` 에 넣고, ReAct
루프가 드레인해 `<system-reminder>` 로 주입한다.

큐는 애플리케이션에 하나이고 `AgentRuntime` 은 agent-scoped 이므로, `agentRuntimeId` 만으로 드레인하면 같은 agent 의
다른 세션이 항목을 가져간다. 그래서 큐 항목에 선택 필드 `sessionId` 를 둔다. 값은 `SessionId.value()` 문자열이다.
`agent.session` 이 이미 `agent.queue` 에 의존하므로 타입 필드로 두면 패키지 순환이 생긴다.

```java
q -> agentRuntimeId.equals(q.getAgentRuntimeId())
        && q.getSessionId().map(sessionId::equals).orElse(true)
```

큐에 넣는 쪽은 셋 모두 세션을 지정한다. 모니터는 소유자의 세션, `DefaultLiveSession.offerAsync` 는 자기 세션, 서브에이전트
완료 알림은 작업을 띄운 세션(`SubagentLaunchContext.getInvokingSessionId()`, 없으면 지정하지 않는다)이다. `NOW` 우선순위
리스너(`DefaultLiveSession.onQueueEvent`)도 다른 세션을 지정한 항목에는 반응하지 않는다. `sessionId` 가 없는 항목은 runtime
의 어느 세션이든 가져간다. 호스트가 직접 넣은 항목이 그렇다.

### 6.2 언제 주입되는가

진행 중인 턴이면 다음 도구 iteration 끝에서, 쉬는 세션이면 깨우기가 꺼진 동안 다음 턴의 첫 LLM 호출 전에 모델에게 간다.
깨우기가 켜져 있으면 그 알림이 턴의 입력이 된다([쉬는 세션 깨우기](../session/idle-wake.md)). 웹·라우팅 경로도 같은 실행기를
쓰므로 같은 보장을 받는다.

실행기는 두 곳에서 드레인한다. 첫 LLM 호출 직전에는 이 세션을 지정한 항목만, 도구를 호출한 iteration 끝에는 이 세션 항목과
지정 없는 항목을 가져간다. 턴 시작 드레인이 없으면 쉬는 동안 쌓인 알림을 도구 없이 답하는 턴이 끝내 보지 못한다. 턴 시작에
지정 없는 항목을 가져가지 않는 이유는 누구의 것인지 모르는 항목을 이 턴이 먼저 가져갈 근거가 없기 때문이다.

CLI 의 `ReplSession.drainQueueAfterTurn` 은 턴이 끝난 직후 남은 항목을 꺼내 각각을 새 턴으로 재생한다. 사용자가 턴 도중
입력한 후속 메시지와 서브에이전트 완료 알림에는 맞는 동작이다. 모니터 알림까지 재생하면 묶음마다 LLM 턴이 하나씩 생겨
깨우기 설정과 무관하게 세션이 깨어난다. 그래서 재생 필터는 이 세션의 항목(이 세션 지정 또는 지정 없음)을 꺼내되 모니터
항목(`metadata.kind = monitor-event`)은 남겨 두고, 다음 턴 시작 드레인이 가져가게 한다.

### 6.3 큐에는 모니터당 하나

배치마다 큐 항목을 하나씩 넣으면 세션이 오래 쉬는 동안 항목이 쌓이고(in-memory 저장소에는 만료가 없다), 돌아온 턴은 수십
개의 메시지를 한꺼번에 받는다. 그래서:

- 매니저는 모니터마다 대기 버퍼를 둔다. 이벤트는 먼저 버퍼에 들어가고, 배칭 창이 닫히면 버퍼를 큐 항목 하나로 낸다
- 그 모니터의 배치 항목이 드레인되지 않은 채 남아 있으면 새 항목을 넣지 않는다. 큐 리스너의 `DRAINED` · `REMOVED` 이벤트로 항목이 나간 것을 알면, 그동안 모인 버퍼를 다음 항목 하나로 낸다
- 버퍼는 상한(§6.5)을 넘으면 오래된 이벤트부터 버리고, 다음 메시지가 버린 수를 말한다

그래서 큐에는 모니터당 배치 항목이 최대 하나이고, 모니터가 끝나면 종료 알림 항목이 그 뒤에 붙을 수 있다. 메모리는 모니터당
버퍼 상한만큼만 쓴다.

### 6.4 알림 모양

알림은 평문이다. `wrap()` 이 본문을 XML 이스케이프하므로 `<monitor-event>` 같은 태그는 모델에게 `&lt;monitor-event&gt;`
로 보인다. 서브에이전트 완료 알림이 평문인 이유와 같다.

```text
Monitor mon_3f9a1c2e (errors in deploy.log) — 2 events:
2026-10-22T10:01:02 ERROR connection refused
2026-10-22T10:01:02 ERROR retry 1/3 failed
```

```text
Monitor mon_3f9a1c2e (errors in deploy.log) ended: timed out after 10m. 14 events in total.
```

종료 사유는 넷이다. `completed`(source 의 `complete`, 예: `exit code 0`), `failed`(사유와 stderr 마지막 몇 줄), `timeout`,
`suppressed`(속도 제한). `stopped`(`MonitorStop`) 와 `shutdown`(앱 종료), 세션 삭제는 알리지 않는다. 앞의 것은 모델이 스스로
한 일이고 나머지는 받을 턴이 없다.

source 가 준 텍스트는 `SystemReminderFormatter.sanitizeBody` 로 예약 표식을 무력화한다. `wrap()` 은 본문에
`<system-reminder>` 표식이 있으면 예외를 던지고 드레인 루프는 그 항목을 버린다. 감시 대상 로그에 그 문자열이 찍혔다는
이유로 알림이 사라지면 안 된다.

### 6.5 정책

| 항목 | 기본값 | 이유 |
|------|-------|------|
| 배칭 창 | 1초 | 한 사건이 낸 여러 줄을 한 묶음으로. 큐에 하나만 두므로(§6.3) 짧을 필요가 없다 |
| 이벤트 하나의 상한 | 2,000자, 넘으면 `…` | 개행 없는 진행 표시줄(`\r`) 같은 출력 |
| 버퍼 · 알림 하나의 상한 | 200건 · 16,000자 | 큐와 컨텍스트 보호 |
| 속도 제한 | 1분에 이벤트 600건 초과 시 `suppressed` 로 중지 | 필터 없이 `tail -f` 한 감시. 버퍼가 넘침을 흡수하므로 이 한도는 "필터가 없다" 의 신호다 |
| 소유자당 동시 모니터 | 8개 | 잊힌 모니터 누적 방지 |
| 노드 전체 동시 모니터 | 64개 | `shell` 은 모니터마다 스레드 하나를 최대 30분 쓴다 |
| timeout | 기본 5분, 상한 30분, 하한 1초 | §5.1 |

전부 `MonitorManager.Builder` 설정이다. 도구 입력이 바꿀 수 있는 것은 상한 안의 `timeout_ms` 뿐이다.

---

## 7. 셸 스트리밍

스트리밍 SPI(`ShellFeature.STREAMING`, `ShellLineListener`, `LocalShell` 의 구현)의 정본은 [셸 계약](shell-contract.md) 이다.
`shell` source 가 `STREAMING` 과 `CANCELLATION` 을 둘 다 요구하는 이유도 거기 있다.

`shell` source 는 `at.aimon.core.tools..` 아래 있으므로 `VirtualShell` 을 필드나 생성자 인자로 들 수 없다
(`PackageDependencyArchitectureTest.toolsHoldNoFileSystemOrShellFields`). 시작 시점의 `ExecutionEnvironment` 를 들고, 실행할 때
`environment.shell()` 을 읽는다.

캡처 파일은 명령이 도는 동안 디스크에서 자란다(최대 30분). `grep --line-buffered` 로 거른 감시라면 작고, 거르지 않은 감시는
§6.5 의 속도 제한에 먼저 걸린다.

---

## 8. 하지 말 것

- source 안에 배칭·속도 제한·타임아웃을 넣지 않는다. 정책은 매니저 한 곳에 있다.
- source 를 `complete` 나 `fail` 없이 끝내지 않는다. 침묵은 "아직 도는 중" 과 구별되지 않는다.
- 모니터 락을 쥔 채 큐에 넣거나 source 를 멈추지 않는다.
- 세션 지정 없이 모니터 알림을 큐에 넣지 않는다. 같은 agent 의 다른 세션이 가져간다.
- 모니터 알림을 CLI 의 턴 뒤 재생에 태우지 않는다.
- `sanitizeBody` 를 거치지 않은 source 텍스트를 큐에 넣지 않는다.
- `LiveSession.close()` 나 `AgentRuntime` 소멸에서 `MonitorManager` 를 닫지 않는다.
- 큐를 호출의 `ToolContextKeys.MESSAGE_QUEUE_MANAGER` 에서 읽지 않는다.
- 도구 스키마를 실행 환경에 따라 바꾸지 않는다.
- `Bash` 를 끈 스택에 `shell` source 를 남기지 않는다. `command` 형태의 `Bash` 위임을 빼지 않는다.
- URL 을 `COMMAND` 주체로 넘기지 않는다.

---

## 9. 설계 결정 표

§2 의 결정 외에, 기각하거나 미룬 대안은 다음과 같다.

### 9.1 모니터 안에서 깨우기 — 기각

깨우기를 모니터에 넣지 않는다. 모니터가 라우터·세션 레코드·principal 을 알게 되고, 서브에이전트 알림은 혜택을 받지
못하며, 사용자 입력 없는 LLM 호출의 예산·쿼터·principal 결정이 필요하다. 턴을 시작하려면 노드 로컬이고 축출될 수 있는
`LiveSession` 핸들이 필요하고, 다른 노드에 있으면 `SessionInbox` 와 라우팅을 거쳐야 한다. 그래서 깨우기는
[세션 계층](../session/idle-wake.md) 에 있다.

### 9.2 다른 대안

| 대안 | 판정 | 이유 |
|------|------|------|
| rewake 를 전달 경로로 | 기각 | `RewakeService.resolve(ExternalEvent)` 는 미리 등록된 envelope 에만 반응하고 그 envelope 는 훅 설정이 만든다. 모델이 시작하는 감시와 등록 주체·수명·쿼터가 다르다. 감시마다 envelope 를 만들고 지우는 배관이 생기고, 훅이 다시 발화되는 것이지 모델에게 알림이 가는 것도 아니다 |
| `BackgroundBashManager` 를 넓히기 | 기각 | 출력이 끝나야 채워진다는 전제와 `BashOutput` 의 read-once 계약은 끝나는 명령에 맞춘 것이다. 줄 단위 push 를 넣으면 두 의미가 한 타입에 섞이고, 셸이 아닌 source 는 들어갈 수 없다. 대신 구조(application-scoped, 소유자 범위, 메타데이터 저장소 분리)를 따른다 |
| kind 마다 도구를 따로 | 기각 | `ShellMonitor`, `WebSocketMonitor` 로 나누면 정책 설명이 도구마다 반복되고 호스트 kind 가 늘 때마다 도구가 는다 |
| `TaskStop` 을 넓혀 모니터도 멈춘다 | 기각 | `TaskStop` 은 `SubagentTaskController` 의 제어면이다. 두 id 공간을 한 도구가 판별하면 오류 메시지가 어느 쪽 이야기인지 흐려진다 |
| JDK `java.net.http.WebSocket` | 기각 | 네트워크 인터셉터가 연결된 주소를 검사하는 길이 OkHttp 쪽에 있다. JDK 클라이언트로는 rebinding 틈이 남는다 |
| `PermissionSubject` 에 URL 종류 추가 | 미룸 ([TC-16](../../backlog/tool-contract-open-items.md)) | `ws` 형태 하나를 위해 권한 모델의 닫힌 종류 목록을 열지 않는다. 도구 자신의 규칙(§4.4)으로 충분하다 |

---

## 10. 참조 파일 지도

| 파일 | 확인할 것 |
|------|----------|
| `modules/aimon-core/.../tools/monitor/MonitorManager.java` | 상태 기계, 버퍼, 큐 전달, 정책 기본값, `QUEUED_KIND` |
| `modules/aimon-core/.../tools/monitor/MonitorSource.java` · `MonitorEventSink.java` · `MonitorHandle.java` · `MonitorSourceFactory.java` · `MonitorRequest.java` | SPI |
| `modules/aimon-core/.../tools/monitor/ShellMonitorSourceFactory.java` | `shell` source, 기능 비트 검사 |
| `modules/aimon-core/.../tools/monitor/WebSocketMonitorSourceFactory.java` | `websocket` source, SSRF, ping |
| `modules/aimon-core/.../tools/monitor/MonitorTool.java` · `MonitorStopTool.java` | 입력, 권한 세 인터페이스 |
| `modules/aimon-core/.../tools/monitor/MonitorStore.java` · `InMemoryMonitorStore.java` · `MonitorRecord.java` | 메타데이터 저장소 |
| `modules/aimon-core/.../tools/BackgroundTaskOwner.java` | 소유자 |
| `modules/aimon-core/.../agent/queue/QueuedInput.java` | `sessionId` |
| `modules/aimon-core/.../agent/impl/orca/OrcaAgentExecutor.java` | `injectSessionQueuedMessages`, `injectQueuedMessages` |
| `modules/aimon-core/.../agent/session/DefaultLiveSession.java` | `offerAsync`, `onQueueEvent` |
| `modules/aimon-core/.../agent/impl/orca/tool/OrcaMonitorToolProvider.java` | 도구 등록 |
| `modules/aimon-cli/.../repl/ReplSession.java` | `drainQueueAfterTurn` 의 재생 필터 |
| `modules/aimon-bootstrap/.../spec/ToolSpec.java` · `AimonStackBuilder.java` | 켜기, `shell` 빼기, teardown, 삭제 연결 |

`modules/aimon-core/...` 는 `src/main/java/at/aimon/core`, `modules/aimon-cli/...` 는 `src/main/java/at/aimon/cli`,
`modules/aimon-bootstrap/...` 는 `src/main/java/at/aimon/bootstrap` 를 줄인 것이다.

---

## 11. 관련 문서

- [모니터 가이드](../../features/tool/monitor-guide.md)
- [쉬는 세션 깨우기](../session/idle-wake.md)
- [셸 계약](shell-contract.md) — 스트리밍 SPI
- [백그라운드 명령](background-commands.md) — `BackgroundTaskOwner`, 매니저 구조
- [도구 계약](tool-contract.md) — 권한 위임
- [스코프 모델](../../overview/scope-model.md)
