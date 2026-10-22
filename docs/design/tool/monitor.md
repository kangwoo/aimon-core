# 백그라운드 모니터 (Monitor)

> Status: **IMPLEMENTED** — §10 의 네 단계가 모두 들어가 있다. 쉬는 세션 깨우기(§9.1)는 미뤘고, 설계에서
> 벗어난 점은 §12 에 있다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.tools.monitor` (SPI · 매니저 · 도구 · 기본 소스 2종),
> `at.aimon.core.shell` (`ShellFeature.STREAMING`, `ShellLineListener`), `…shell.impl.local.LocalShell`,
> `at.aimon.core.agent.queue.QueuedInput` (`sessionId`), `…agent.impl.orca.OrcaAgentExecutor` (드레인 두 곳),
> `…agent.impl.orca.tool.OrcaMonitorToolProvider` · `aimon-cli` — `ReplSession` (턴 뒤 재생 필터) ·
> `aimon-bootstrap` — `ToolSpec`, `AimonStackBuilder` 조립.

---

## 1. 문제

에이전트가 **오래 걸리는 외부 상태를 지켜보며 다른 일을 계속해야** 하는 경우가 있다.

1. **로그 감시** — 배포 중 `app.log` 에 `ERROR` 가 찍힐 때마다 알고 싶다
2. **외부 스트림** — 알람·이벤트 서버가 WebSocket 으로 밀어 주는 이벤트를 받고 싶다
3. **호스트 고유 소스** — 이 프레임워크를 쓰는 운영 앱이 이미 가진 Kafka 토픽, 쿠버네티스 watch,
   알람 큐를 에이전트에게 이벤트로 넘기고 싶다

지금 있는 도구로는 셋 다 제대로 되지 않는다.

| 수단 | 왜 안 되는가 |
|------|-------------|
| `Bash` 전경 실행 | 10분 상한에 막히고, 그동안 턴이 묶인다 |
| `Bash(run_in_background)` + `BashOutput` | 출력이 명령이 끝나야 한 번에 채워진다(`BackgroundBashManager` javadoc). `tail -f` 는 끝나지 않으므로 영원히 빈 결과다. 끝나더라도 모델이 **폴링**해야 한다 |
| 스케줄 루틴 · rewake | 미리 정한 시각이나 등록된 envelope 에 반응한다. 실행 중에 생기는 감시와 수명이 다르다 |

필요한 것은 "이벤트가 생길 때마다 모델에게 알림이 **밀려오는**" 원시다. Claude Code 의 Monitor 도구가
정확히 그것이고, 이 설계는 그 모양을 따르되 셸에 묶이지 않게 일반화한다.

**하지 않는 것**: 이벤트가 쉬고 있는 세션을 스스로 깨워 턴을 시작하는 일(§9.1). 알림은 진행 중인 턴이면
다음 iteration 경계에서, 쉬는 세션이면 **다음 턴의 첫 LLM 호출 전에** 주입된다(§6.2).

---

## 2. 결정 요약

| # | 결정 | 근거 절 |
|---|------|--------|
| D1 | 이벤트 **생산**(source)과 **정책**(배칭·상한·소유권·전달)을 나눈다. 셸은 source 중 하나다 | §3 |
| D2 | source 는 `MonitorSourceFactory` 로 kind 이름에 등록한다. 기본 제공은 `shell` · `websocket` 둘, 나머지는 호스트가 Java 로 등록한다 | §4 |
| D3 | 매니저는 application-scoped, 소유권은 `BackgroundBashOwner` 를 그대로 쓴다. 세션이 있는 소유자만 시작할 수 있다 | §5 |
| D4 | 알림은 기존 메시지 큐(`NEXT`)로 가되, `QueuedInput` 에 선택 필드 `sessionId` 를 더해 **세션을 지정한 항목**으로 넣는다. 세션 지정 항목은 그 세션의 턴만 드레인한다 | §6.1 |
| D5 | 실행기는 세션 지정 항목을 **턴 시작**에도 드레인한다. CLI 의 턴 뒤 재생은 세션 지정 항목을 건드리지 않는다 | §6.2 |
| D6 | 큐에는 모니터당 **미드레인 항목을 최대 하나**만 둔다. 나머지 줄은 매니저 버퍼에 모았다가 드레인 뒤 한 항목으로 낸다 | §6.3 |
| D7 | 셸 스트리밍은 `ShellFeature.STREAMING` + `VirtualShell` default 메서드로 연다. 지원 여부는 실행 시점에 판정한다 | §7 |
| D8 | 도구 스키마는 상수다. `command` / `ws` / `source` 를 항상 광고하고, 쓸 수 없는 형태는 실행 시점에 오류로 거절한다 | §4.3 |
| D9 | 권한은 `command` 형태에만 명령 주제를 쓰고, `ws` · `source` 형태는 도구 자신의 규칙으로 판정한다 | §4.4 |

---

## 3. 구조

```
 MonitorSource (SPI)              MonitorManager (application-scoped)        MessageQueueManager
 ───────────────────              ─────────────────────────────────          ───────────────────
 shell     (VirtualShell 스트리밍) ┐   소유권 · 배칭 · 속도 제한 · timeout ·        QueuedInput(NEXT,
 websocket (JDK WebSocket)       ├─▶ 동시 개수 상한 · 모니터별 대기 버퍼 ·   ─▶     runtime, session)
 호스트 등록 kind                  ┘   MonitorStore 메타데이터                      → 턴 시작 / iteration 끝
```

source 는 **이벤트를 만들기만** 한다. 배칭, 속도 제한, 타임아웃, 누구에게 보낼지는 전부 매니저가 한 곳에서
정한다. 그래서 source 를 하나 더 붙여도 정책 코드는 한 줄도 늘지 않고, 모든 source 가 같은 상한 아래 있다.

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

`MonitorRequest` 는 불변 클래스 + 빌더다(record 아님 — 프로젝트 규칙). 소유자, 호출의 `ToolContext`
(실행 환경을 읽는 데 필요), kind 별 파라미터 맵을 싣는다.

**`fail` 이 계약에 있는 이유**: 성공 신호만 있는 감시는 소스가 죽어도 아무 말이 없고, 그 침묵은 "아직 도는
중"과 구별되지 않는다. source 가 끝나는 모든 길은 `complete` 나 `fail` 중 하나를 반드시 지나게 하고,
매니저는 타임아웃·속도 제한 중지도 같은 종료 알림으로 낸다(§6.4).

### 3.2 모니터 하나의 상태 기계

```
 RUNNING ──emit──▶ RUNNING
    │
    ├── complete / fail / timeout / suppressed ──▶ ENDED  (종료 알림 1회)
    └── stop (MonitorStop · 앱 종료) ─────────────▶ ENDED  (알림 없음)
```

전이는 모니터마다 하나인 락 아래서 일어나고 `ENDED` 는 흡수 상태다. 그래서 source 스레드의 `complete`,
타임아웃 타이머, `MonitorStop` 이 동시에 와도 이긴 하나만 전이하고 나머지는 무시된다. **종료 알림은 그
전이 안에서 대기 버퍼를 먼저 비운 뒤** 붙는다 — 마지막 줄들이 종료 알림보다 늦게 도착하는 일이 없다.
모델이 스스로 멈춘 경우와 앱 종료를 빼면, 어떤 이유로 끝나든 모델은 종료 알림을 정확히 한 번 받는다.

---

## 4. Source

### 4.1 기본 제공

| kind | 이벤트 단위 | 파라미터 | 대상 검사 |
|------|-----------|---------|----------|
| `shell` | stdout 한 줄 | `command` | 실행 환경의 셸이 `STREAMING` 과 `CANCELLATION` 을 선언해야 함(§7) |
| `websocket` | 텍스트 메시지 하나 (조각 프레임은 합쳐서 한 건) | `url`, `protocols` | `ws`/`wss` 만. `SsrfGuard` 로 사설망 차단 |

바이너리 메시지는 내용 대신 `[binary frame, N bytes]` 한 줄로 알린다. 30초마다 ping 을 보내, 조용히 죽은
연결을 타임아웃까지 기다리지 않는다.

WebSocket 은 OkHttp 로 연다 — core 가 이미 의존하는 라이브러리이고, `WebHttpClientFactory` 가 만든 클라이언트를
그대로 쓰기 위해서다. `SsrfGuard.isSafe(String)` 는 `http`/`https` 외의 스킴을 모두 거절하므로, 검사할 때 `ws`→
`http`, `wss`→`https` 로 바꿔 넘긴다(핸드셰이크는 같은 호스트·포트로 가는 HTTP 요청이다). 그리고 그 클라이언트의
네트워크 인터셉터(`SsrfRedirectInterceptor`)가 **실제로 연결된 소켓 주소**를 다시 검사한다. 그래서 URL 을 검사한
뒤 연결 시점에 DNS 가 사설 주소로 바뀌는 rebinding 도 막힌다. 모델이 소켓을 열면 안 되는 조립은 `websocket`
소스를 등록하지 않으면 된다(§4.2 의 `sources(...)`).

### 4.2 호스트 등록

```java
MonitorManager.builder(queue)
        .source(new KafkaAlarmSourceFactory(consumerFactory))   // kind = "alarms"
        .build();
```

이것이 **셸을 쓰지 않는 방식의 핵심**이다. 모델은 kind 와 파라미터만 고르고(`source: "alarms",
params: {"severity": "critical"}`), 원시 명령이나 URL 을 쓰지 않는다. 연결 정보·자격 증명·필터는 호스트
코드에 있고, 모델이 그것을 바꿀 수 없다. 운영 자동화에서는 이쪽이 셸보다 안전하고 흔할 것이다.

기본 kind 도 같은 등록 경로를 탄다. `MonitorManager.Builder` 는 `shell` 과 `websocket` 을 기본으로 넣고,
조립이 `sources(...)` 로 목록 전체를 바꿀 수 있다.

### 4.3 도구 입력

```json
{
  "description": "errors in deploy.log",
  "timeout_ms": 600000,
  "command": "tail -f deploy.log | grep --line-buffered -E 'ERROR|FATAL'"
}
```

`command` / `ws` / `source`(+`params`) 중 **정확히 하나**. 판별 필드를 셋으로 둔 것은 Claude Code 와 같은
모양이라 모델이 이미 익숙하고, 각 형태의 필드를 스키마가 이름으로 말해 주기 때문이다.

**스키마는 상수다.** `Tool.getDefinition()` 은 `ToolContext` 를 받지 않고, 셸은 실행마다
`ExecutionEnvironment` 에서 온다(`docs/overview/scope-model.md`). 그래서 "이 셸은 스트리밍을 못 하니
`command` 를 빼자"는 정의 시점에 알 수 없다. 세 필드를 항상 광고하고, 쓸 수 없는 형태는 실행 시점에
이유를 붙여 오류로 돌려준다. 호스트 kind 목록은 매니저가 생성 시점에 고정하므로 도구 **설명**에 넣는다.
`oneOf` 는 쓰지 않는다 — "정확히 하나" 는 `execute()` 가 검사한다.

`MonitorStop(monitorId)` 는 같은 소유자의 모니터만 찾는다. 기존 `TaskStop` 을 넓히지 않은 이유: 그것은
`SubagentTaskController` 의 제어면이고, 두 종류의 id 공간을 한 도구가 판별하게 하면 오류 메시지가 둘 중
어느 쪽 이야기인지 흐려진다.

### 4.4 권한

`PermissionSubject.Kind` 는 `COMMAND` 와 `PATH` 둘뿐이고, 그 javadoc 은 세 번째가 없다고 못 박는다. URL 을
`COMMAND` 주제로 넘기면 `ToolPattern` 이 셸 메타문자(`?`, `&`)를 거절해 패턴이 설정된 순간 모든 URL 이
막힌다. 그래서 두 장치를 함께 쓴다.

- `ToolPermissionSubjectAware` — `command` 형태일 때만 `PermissionSubject.command(command)` 를 돌려준다.
  `Bash` 와 같은 주제이므로 `Monitor(tail -f *)` 같은 패턴이 그대로 통한다
- `CustomToolPermissionAware` — 주제가 비었을 때(=`ws`·`source` 형태) 규칙이 판정한다. 패턴을
  `ws:<url>` / `source:<kind>` 문자열과 비교하고, 끝의 `*` 만 와일드카드다(예:
  `Monitor(ws:wss://alarms.example.com/*)`, `Monitor(source:alarms)`). 패턴 없는 `Monitor` 항목은 전부 허용

`Bash` 에 걸어 둔 패턴이 `Monitor` 로 우회되지 않게 하려면 운영자가 `Monitor` 에도 패턴을 적어야 한다 —
권한은 도구 이름별로 설정되기 때문이다(§11 M-3).

---

## 5. 수명과 소유권

### 5.1 매니저는 application-scoped

`docs/overview/scope-model.md` 기준으로 `MonitorManager` 는 **Application** 수명이다. `BackgroundBashManager`
와 같은 이유다 — 테넌트 runtime 이 축출되어도 감시는 계속되어야 하고, `LiveSession.close()` 가 감시를
닫으면 안 된다. 스택은 `TeardownPhase.BACKGROUND_COMMANDS` 에 매니저를 올려 종료 시 모든 source 를 멈춘다.

모니터 하나의 수명은 셋 중 먼저 오는 것이다: source 의 종료 · `MonitorStop` · 타임아웃. 타임아웃 기본값
5분, 상한 30분(Claude Code 와 같다). 더 오래 지켜보려면 모델이 다시 시작한다 — 잊힌 모니터가 노드 자원과
큐를 영원히 쓰지 않게 하기 위해서다.

`shell` source 는 시작 시점의 실행 환경을 붙잡는다(`Bash` 백그라운드와 같은 규칙, execution-environment
설계 §5.3). 환경 제공자를 runtime 이 소유하는 조립에서는 runtime 이 닫힐 때 셸이 모니터 밑에서 닫힐 수
있다. 그때 명령은 실패하고 모니터는 `failed` 로 끝난다 — 조용히 사라지지 않는다.

### 5.2 소유자

소유권은 `BackgroundBashOwner.of(ToolContext)` 를 그대로 쓴다. "누가 시작했는가"를 정하는 규칙이 두 곳에서
갈라지지 않게 하려는 것이다. 이름이 `Bash` 를 달고 있는 것은 역사적 이유이고, 공용 이름으로 바꾸는 것은
§11 M-1.

**세션이 없는 소유자는 거절한다.** 예약 루틴이나 누구의 턴도 요청하지 않은 포크는 실행이 끝나면 알림을
받을 주체가 사라진다. 세션의 턴과 세션을 위해 생성된 포크(`INVOKING_SESSION_ID`)는 받아들이고, 포크가
시작한 모니터의 알림은 **호출한 세션**으로 간다.

큐는 매니저가 생성 시점에 받는다. 호출의 `ToolContextKeys.MESSAGE_QUEUE_MANAGER` 에서 읽지 않는 이유:
포크의 도구 컨텍스트에는 그 키가 없어서(`DefaultSubagentExecutor` 는 큐를 싣지 않는다) 포크가 시작한
모니터는 늘 거절될 것이기 때문이다. 스택의 큐는 애플리케이션에 하나이므로 매니저가 들고 있어도 수명이 맞다.

### 5.3 멀티 인스턴스

`BackgroundBashStore` 와 같은 분할이다. 메타데이터(id, 소유자, 노드, kind, 설명, 시작·마감 시각, 상태)는
`MonitorStore` SPI 로 가고 기본 구현은 in-memory 다. 연결 자체(프로세스, 소켓, 호스트 구독)는 그 노드의
메모리에 있다. 다른 노드에서 `MonitorStop` 이 오면 "다른 노드에서 실행 중"이라고 답한다.

**알림도 노드 로컬이다.** 기본 큐 저장소는 in-memory 이므로, 세션이 다른 노드로 옮겨 가면 이 노드의 모니터
알림은 그 세션에 닿지 않는다. 모니터는 타임아웃까지 돌다 끝난다. 조립이 공유 `MessageQueueRepository` 를
주면 알림이 따라가는데, 그때 세션 간 누수를 막는 것이 바로 `sessionId` 필터다(§6.1).

노드가 죽은 뒤 감시를 다른 노드에서 다시 여는 일은 범위 밖이다. 셸 프로세스는 옮길 수 없고, 호스트 source 의
재연결 의미는 호스트마다 다르다.

---

## 6. 전달

### 6.1 세션을 지정한 큐 항목

알림은 서브에이전트 완료 알림과 같은 길을 탄다 — `QueuedInputPriority.NEXT` 로 `MessageQueueManager` 에
넣고, ReAct 루프가 드레인해 `<system-reminder>` 로 주입한다.

**발견한 문제.** `AimonStackBuilder` 는 `MessageQueueManager` 를 애플리케이션에 하나 만들고,
`OrcaAgentExecutor.injectQueuedMessages` 는 `agentRuntimeId` **만으로** 걸러 드레인한다. `AgentRuntime` 은
agent-scoped 이고 웹 세션들은 한 runtime id 를 공유하므로, 같은 agent 의 세션 A 를 향한 항목을 B 의 턴이
가져갈 수 있다(`AimonStackSpec` 의 큐 저장소 javadoc 에도 적혀 있다). 모니터 알림은 세션이 시작한 감시의
결과라 이 누수를 그대로 둘 수 없다.

`QueuedInput` 에 선택 필드 `sessionId` 를 더하고, 실행기의 드레인 조건을 이렇게 바꾼다. 값은
`SessionId.value()` 문자열이다 — `agent.session` 이 이미 `agent.queue` 에 의존하므로, 타입 필드로 두면 패키지
순환이 생긴다(`PackageDependencyArchitectureTest.noCorePackageCycles`).

```java
q -> agentRuntimeId.equals(q.getAgentRuntimeId())
        && q.getSessionId().map(sessionId::equals).orElse(true)
```

`sessionId` 가 없는 항목은 지금과 똑같이 동작하므로 기존 생산자(`offerAsync`, 서브에이전트 완료 알림)는
행동이 바뀌지 않는다. 모니터만 `sessionId` 를 채운다. 기존 두 생산자와 `NOW` 우선순위 리스너
(`DefaultLiveSession.onQueueEvent`)를 세션 단위로 바꾸는 것은 별도 변경이다(§11 M-2).

### 6.2 언제 주입되는가

지금 실행기가 큐를 드레인하는 곳은 **도구를 호출한 iteration 의 끝** 한 곳뿐이다. 도구 호출 없이 답하는
응답은 그 앞에서 반환한다. 그래서 쉬는 세션에 쌓인 알림은 "다음 턴" 이 아니라 "다음 턴 중 도구를 쓰는
첫 iteration 끝" 에야 보이고, 바로 답하는 턴은 끝내 보지 못한다. 두 군데를 고친다.

1. **턴 시작 드레인** — 실행기가 첫 LLM 호출 직전에 **세션 지정 항목만** 드레인해 주입한다. 세션 지정이
   없는 기존 항목은 손대지 않는다 — 그것들은 CLI 재생(아래)의 몫이고, 여기서 가져가면 그 경로의 동작이 바뀐다
2. **CLI 의 턴 뒤 재생** — `ReplSession.drainQueueAfterTurn` 은 runtime 의 `NEXT`/`LATER` 항목을 전부 꺼내
   각각을 새 턴으로 재생한다. 그대로 두면 모니터 알림 한 묶음마다 LLM 턴이 하나씩 생기는데, 그것은 §9.1 에서
   미룬 "깨우기" 를 CLI 에서만 몰래 하는 것이다. 재생 필터에 `q.getSessionId().isEmpty()` 를 더해 세션 지정
   항목은 남겨 두고, 다음 턴 시작 드레인이 가져가게 한다

결과적으로 모니터 알림은 진행 중인 턴이면 다음 도구 iteration 끝에서, 쉬는 세션이면 다음 턴의 첫 LLM 호출
전에 모델에게 간다. 웹·라우팅 경로도 같은 실행기를 쓰므로 같은 보장을 받는다.

### 6.3 큐에는 모니터당 하나

배치마다 큐 항목을 하나씩 넣으면, 세션이 오래 쉬는 동안 항목이 쌓이고(in-memory 저장소에는 만료가 없다)
돌아온 턴은 수십 개의 사용자 메시지를 한꺼번에 받는다. 그래서:

- 매니저는 모니터마다 **대기 버퍼**를 둔다. 배칭 창이 닫히면 줄을 버퍼에 넣는다
- 그 모니터의 큐 항목이 드레인되지 않은 채 남아 있으면 새 항목을 넣지 않는다. 큐 리스너의 `DRAINED`
  이벤트로 항목이 나간 것을 알면, 그동안 모인 버퍼를 다음 항목 하나로 낸다
- 버퍼는 상한(§6.5)을 넘으면 오래된 줄부터 버리고 `(N earlier lines dropped)` 를 붙인다

그래서 큐에는 모니터당 항목이 최대 하나, 메모리는 모니터당 버퍼 상한만큼만 쓴다.

### 6.4 알림 모양

알림은 평문이다. `wrap()` 이 본문을 XML 이스케이프하므로 `<monitor-event>` 같은 태그는 모델에게
`&lt;monitor-event&gt;` 로 보인다 — 서브에이전트 완료 알림이 평문인 이유와 같다.

```
Monitor mon_3f9a1c2e (errors in deploy.log) — 2 events:
2026-10-22T10:01:02 ERROR connection refused
2026-10-22T10:01:02 ERROR retry 1/3 failed
```

```
Monitor mon_3f9a1c2e (errors in deploy.log) ended: timeout after 10m, 14 events delivered.
```

종료 사유는 `completed`(source 의 `complete`, 예: `exit code 0`) · `failed`(사유와 stderr 마지막 몇 줄) ·
`timeout` · `suppressed`(속도 제한) 중 하나다. `stopped`(`MonitorStop`) 와 `shutdown`(앱 종료)은 알리지
않는다 — 전자는 모델이 스스로 한 일이고, 후자는 받을 턴이 없다.

source 가 준 텍스트는 `SystemReminderFormatter.sanitizeBody` 로 예약 표식을 무력화한다. `wrap()` 은 본문에
`<system-reminder>` 표식이 있으면 예외를 던지고, 드레인 루프는 그 항목을 버린다. 감시 대상 로그에 그 문자열이
찍혔다는 이유로 알림이 사라지면 안 된다.

### 6.5 정책

| 항목 | 기본값 | 이유 |
|------|-------|------|
| 배칭 창 | 1초 | 한 사건이 낸 여러 줄을 한 묶음으로. 큐에 하나만 두므로(§6.3) 짧을 필요가 없다 |
| 줄 하나의 상한 | 2,000자, 넘으면 `…` | 개행 없는 진행 표시줄(`\r`) 같은 출력 |
| 버퍼 · 알림 하나의 상한 | 200줄 · 16,000자 | 큐와 컨텍스트 보호 |
| 속도 제한 | 1분에 이벤트 600건 초과 시 `suppressed` 로 중지 | 필터 없이 `tail -f` 한 감시. 버퍼가 넘침을 흡수하므로 이 한도는 "필터가 없다" 의 신호다 |
| 소유자당 동시 모니터 | 8개 | 잊힌 모니터 누적 방지 |
| 노드 전체 동시 모니터 | 64개 | `shell` 은 모니터마다 스레드 하나를 최대 30분 쓴다 |
| timeout | 기본 5분, 상한 30분 | §5.1 |

전부 `MonitorManager.Builder` 설정이다. 도구 입력이 바꿀 수 있는 것은 `timeout_ms`(상한 이하)뿐이다.

---

## 7. 셸 스트리밍

### 7.1 SPI

```java
public enum ShellFeature { ..., STREAMING }

public interface ShellLineListener {
    void onStdoutLine(String line);
}

// VirtualShell
default ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
        throws ShellExecutionException {
    throw new UnsupportedOperationException("Streaming output is not supported by " + getClass().getName());
}
```

`CANCELLATION` 과 같은 방식이다 — 기능을 선언한 셸만 그 메서드를 구현한다. `shell` source 는 `STREAMING` 과
`CANCELLATION` 을 **둘 다** 선언한 셸에서만 시작한다. 멈출 수 없는 셸에서 끝나지 않는 명령을 돌리면 타임아웃까지
프로세스가 남는데, 모델이 `MonitorStop` 에 기대하는 것과 다르다. default 메서드라 기존 구현(원격 샌드박스
포함)은 바뀌지 않고, 그 셸에서 `command` 형태는 실행 시점에 거절된다(§4.3).

listener 가 받는 것은 stdout 뿐이다. stderr 는 결과에 남고, 비정상 종료 시 마지막 몇 줄을 `failed` 알림에
싣는다 — "명령이 조용히 죽었다"를 모델이 볼 수 있게.

`shell` source 는 `at.aimon.core.tools..` 아래 있으므로 `VirtualShell` 을 필드나 생성자 인자로 들 수 없다
(`PackageDependencyArchitectureTest.toolsHoldNoFileSystemOrShellFields`). 시작 시점의 `ExecutionEnvironment` 를
들고, 실행할 때 `environment.shell()` 을 읽는다.

### 7.2 `LocalShell`

`LocalShell` 은 자식의 stdout 을 임시 파일로 리다이렉트하고, 끝난 뒤 그 파일을 읽는다. 스트리밍 경로는
프로세스 시작 **전에** 그 파일에 읽기 채널을 하나 더 열고(hook·isolated 모드가 조기 unlink 전에 채널을 여는
것과 같은 순서), `waitFor` 대신 짧은 주기(100ms)로 **위치 지정 읽기**를 해서 완성된 줄만 listener 에 넘긴다.
프로세스가 끝나면 남은 바이트를 마저 읽고, 개행 없이 남은 꼬리도 한 줄로 넘긴다. 위치 지정 읽기라서 결과를
만드는 기존 `readCapped` 의 채널 위치와 섞이지 않는다. 파이프로 바꾸지 않는 이유는 그 클래스 javadoc 이
적은 그대로다 — 백그라운드 자식이 쓰기 끝을 쥐고 있으면 EOF 가 오지 않는 상속 파이프 문제를 다시 들이지 않는다.

스트리밍 경로의 결과에는 stdout 을 싣지 않는다. 이미 줄 단위로 넘겼으니 다시 메모리로 읽을 이유가 없다.
캡처 **파일**은 명령이 도는 동안 디스크에서 자란다(최대 30분). `grep --line-buffered` 로 거른 감시라면 작고,
거르지 않은 감시는 §6.5 의 속도 제한에 먼저 걸린다.

---

## 8. 테스트 범위

| 대상 | 검증 |
|------|------|
| `MonitorManager` | 배칭, 버퍼 상한·드롭 표시, 큐에 모니터당 하나, `DRAINED` 뒤 다음 항목, 종료 알림이 마지막 배치 뒤에 정확히 한 번, 경쟁(`complete` vs timeout vs stop), 소유자 범위, 동시 개수 상한, 세션 없는 소유자 거절 |
| `MonitorTool` / `MonitorStopTool` | 형태 판별(정확히 하나), timeout 클램프, 권한 주제·규칙, 예외 없이 `ToolResult.error` |
| `LocalShell` 스트리밍 | 줄 단위 전달, 개행 없는 꼬리, 취소, 타임아웃, 결과 stdout 비움 |
| `websocket` source | 로컬 테스트 서버로 텍스트·조각·바이너리 프레임, 정상 close, 오류, SSRF 거절 |
| 실행기 드레인 | 세션 지정 항목은 그 세션만, 턴 시작 드레인, 미지정 항목은 기존과 동일 |
| CLI 재생 | 세션 지정 항목은 재생하지 않음 |

---

## 9. 기각하거나 미룬 것

### 9.1 쉬는 세션 깨우기 — 미룸

Claude Code 의 Monitor 는 쉬고 있는 에이전트를 다시 호출한다. 그 동작이 이 기능의 가치 절반인 것은 맞다.
그래도 이번에 뺀 이유:

- **사용자 입력 없이 LLM 호출이 생기는 첫 경로**가 된다. 예산(`BudgetTracker`)·사용량 쿼터·rewake 쿼터
  중 무엇이 그것을 세는지, 누구의 `Principal` 로 호출하는지를 정해야 한다
- 턴을 시작하려면 `LiveSession` 핸들이 필요하다. 그것은 노드 로컬이고 축출될 수 있으며, 다른 노드에 있으면
  `SessionInbox` + 라우팅을 거쳐야 한다. 즉 깨우기는 모니터가 아니라 **세션 계층의 기능**이다
- 큐 주입만으로도 진행 중인 턴(가장 흔한 경우 — 모델이 감시를 걸고 일을 계속하는 동안)은 바로 알림을 받는다

다음 단계에서는 세션 계층이 "쉬는 세션에 세션 지정 큐 항목이 들어오면 턴을 연다" 정책을 opt-in 으로
제공하는 방향을 검토한다. 그때는 모니터만이 아니라 서브에이전트 완료 알림도 같은 혜택을 받는다.

### 9.2 rewake 를 전달 경로로 — 기각

`RewakeService.resolve(ExternalEvent)` 는 미리 등록된 envelope 에만 반응하고, 그 envelope 는 훅 설정이
만든다. 실행 중에 모델이 시작하는 감시와는 등록 주체·수명·쿼터가 모두 다르다. 그 위에 얹으면 감시마다
envelope 를 만들고 지우는 배관이 생기고, 훅이 다시 발화되는 것이지 모델에게 알림이 가는 것도 아니다.

### 9.3 `BackgroundBashManager` 를 넓히기 — 기각

출력이 끝나야 채워진다는 그 매니저의 전제(그리고 `BashOutput` 의 read-once 계약)는 끝나는 명령에 맞춘
것이다. 끝나지 않는 명령의 줄 단위 push 를 거기 넣으면 두 의미가 한 타입에 섞이고, 셸이 아닌 source 는
어차피 들어갈 수 없다. 대신 그 매니저의 **구조**(application-scoped, 소유자 범위, 메타데이터 저장소 분리)를
그대로 따른다.

### 9.4 kind 마다 도구를 따로 — 기각

`ShellMonitor`, `WebSocketMonitor` … 로 나누면 정책 설명이 도구마다 반복되고, 호스트 kind 가 늘 때마다
도구가 는다. 하나의 도구에 판별 필드를 두는 쪽이 모델에게 더 적게 말한다.

### 9.5 `PermissionSubject` 에 URL 종류 추가 — 미룸

`ws` 형태 하나를 위해 권한 모델의 닫힌 종류 목록을 여는 것은 이 기능의 범위를 넘는다. 도구 자신의 규칙(§4.4)으로
충분하고, 다른 도구(`WebFetch`)까지 URL 패턴이 필요해지면 그때 따로 결정한다.

---

## 10. 단계

한 브랜치에서 단계마다 커밋을 나눈다. 각 단계는 그 자체로 빌드와 테스트를 통과한다.

1. **큐 세션 범위** — `QueuedInput.sessionId`, 실행기 드레인 필터와 턴 시작 드레인, CLI 재생 필터
2. **셸 스트리밍** — `ShellFeature.STREAMING`, `ShellLineListener`, `VirtualShell` default 메서드, `LocalShell`
3. **모니터 코어** — SPI, `MonitorManager`, `MonitorStore`, `Monitor`·`MonitorStop` 도구, `shell` source,
   `OrcaMonitorToolProvider`, `ToolSpec.monitorEnabled` · 스택 조립과 teardown, `scope-model.md` 행 추가
4. **WebSocket** — `websocket` source

Spring Boot starter 속성과 원격 샌드박스 셸의 `STREAMING`(별도 저장소)은 그 뒤다.

---

## 11. 열린 항목

| id | 항목 |
|----|------|
| M-1 | `BackgroundBashOwner` 를 `BackgroundTaskOwner` 같은 공용 이름으로 옮긴다 — 지금은 모니터가 bash 패키지의 타입을 빌려 쓴다 |
| M-2 | `offerAsync`, 서브에이전트 완료 알림, `NOW` 우선순위 리스너를 세션 단위로 바꿔 §6.1 의 세션 간 누수를 막는다 |
| M-3 | `Monitor(command)` 의 권한 패턴을 `Bash` 패턴에서 물려받게 할지 — 지금은 도구 이름별로 따로 적어야 한다 |
| M-4 | 세션이 삭제(`SessionDeletedEvent`)되면 그 세션의 모니터를 멈춘다 — 지금은 타임아웃까지 돈다 |

---

## 12. 구현이 설계에서 벗어난 점

| 항목 | 설계 | 구현 | 이유 |
|------|------|------|------|
| WebSocket 클라이언트 | JDK `java.net.http.WebSocket`, rebinding 틈은 남긴다 | OkHttp + `WebHttpClientFactory` 클라이언트, rebinding 도 막는다 | §4.1. 네트워크 인터셉터가 연결된 주소를 검사하는 길이 이미 있었다 |
| `QueuedInput.sessionId` 의 타입 | `SessionId` 로 읽힐 수 있게 적었다 | `Optional<String>` | §6.1. 패키지 순환 |
| 기본 활성화 | 정하지 않았다 | `ToolSpec.monitorEnabled` 기본 꺼짐 | 아무도 입력하지 않은 메시지를 대화에 넣고, `command` 형태는 셸 명령을 최대 30분 돌린다 |
| 멈춘 모니터의 버퍼 | 정하지 않았다 | `MonitorStop` · 앱 종료 시 버퍼에 남은 이벤트는 버린다 | 모델이 스스로 멈췄거나 받을 턴이 없다. 종료 알림을 보내지 않는 것과 같은 이유 |
