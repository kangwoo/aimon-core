# 백그라운드 모니터 가이드

에이전트가 로그, 이벤트 스트림, 알람 같은 **오래 이어지는 외부 상태를 지켜보면서 다른 일을 계속하게** 하는
`Monitor` / `MonitorStop` 도구의 사용법이다. 감시 대상이 이벤트를 낼 때마다 그 내용이 메시지로 에이전트에게
들어온다. 에이전트가 폴링할 필요가 없다.

왜 이렇게 만들었는지는 설계 문서 [`monitor.md`](../../design/tool/monitor.md) 에 있다.

---

## 1. 언제 쓰는가

| 하고 싶은 것 | 쓸 것 |
|-------------|------|
| 명령 하나를 돌리고 끝나면 결과를 본다 | `Bash` (오래 걸리면 `run_in_background` + `BashOutput`) |
| 정해진 시각·주기에 작업을 돌린다 | 스케줄링 (`schedule_task`) |
| **끝나지 않는 출력을 지켜보며, 이벤트가 생길 때마다 알고 싶다** | `Monitor` |

`Bash(run_in_background)` 로 `tail -f` 를 돌리면 아무것도 읽을 수 없다 — 백그라운드 `Bash` 의 출력은 명령이
**끝나야** 채워진다. `Monitor` 는 출력 한 줄, 메시지 하나가 생기는 즉시 그것을 이벤트로 넘긴다.

---

## 2. 켜기

모니터는 **기본으로 꺼져 있다.** 아무도 입력하지 않은 메시지를 대화에 넣고, 명령 형태는 셸 명령을 최대 30분
돌리기 때문이다.

### 2.1 Spring Boot

```yaml
aimon:
  tools:
    monitor:
      enabled: true
```

`MonitorSourceFactory` 빈은 모두 모니터 source 로 등록되고(§5), `MonitorStore` 빈이 있으면 실행 중인 모니터
기록을 그 저장소에 둔다(§8).

**명령 모니터는 `Bash` 를 따른다.** 내장 `shell` source 는 `aimon.tools.bash.enabled` 가 켜져 있을 때만 있다 —
Spring Boot 에서 `Bash` 는 기본으로 꺼져 있으므로, 모니터만 켜면 `ws` 와 호스트 source 만 쓸 수 있다. WebSocket
source 는 `aimon.tools.monitor.websocket-enabled: false` 로 끈다.

### 2.2 CLI

`aimon.yaml` 의 `cli` 절:

```yaml
cli:
  enableMonitor: true
```

### 2.3 `AimonStack` 을 직접 조립할 때

```java
AimonStackSpec spec = AimonStackSpec.builder()
        // ...
        .tools(ToolSpec.builder()
                .monitorEnabled(true)
                .addMonitorSource(new AlarmSourceFactory(alarmClient))   // 선택 — §5
                .build())
        .build();
```

스택이 `MonitorManager` 를 하나 만들어 모든 에이전트 런타임이 나눠 쓰게 하고, 스택이 닫힐 때 함께 닫는다.
`bashEnabled(false)` 인 스택에는 `shell` source 가 없고, `monitorWebSocketEnabled(false)` 는 `websocket` source 를
뺀다.

### 2.4 코어만 쓸 때

`MonitorManager` 는 알림을 넣을 **메시지 큐**를 생성 시점에 받는다. 실행기에 넘긴 것과 같은 큐여야 한다.

```java
MessageQueueManager queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
MonitorManager monitors = MonitorManager.builder(queue).build();   // 애플리케이션 수명 — 종료 시 close()

// 실행기는 같은 큐를 드레인해야 한다
OrcaAgentExecutorFactory executorFactory = new OrcaAgentExecutorFactory().withMessageQueueManager(queue);

// 런타임을 만들 때 넘기는 도구 provider 목록에 더한다
List<OrcaToolProvider> providers = new ArrayList<>(OrcaAgentRuntimeFactory.defaultToolProviders());
providers.add(new OrcaMonitorToolProvider(monitors));
```

---

## 3. 에이전트가 쓰는 법

`Monitor` 는 `description` 과 함께 **`command` / `ws` / `source` 중 정확히 하나**를 받는다.

| 필드 | 형태 | 이벤트 단위 |
|------|------|-----------|
| `command` | 셸 명령 | 표준 출력 한 줄 |
| `ws` | `{"url": "wss://…", "protocols": ["v1"]}` | WebSocket 텍스트 메시지 하나 |
| `source` + `params` | 애플리케이션이 등록한 kind 와 그 파라미터 | source 가 정한다 |
| `timeout_ms` | 선택. 기본 5분, 최대 30분 | — |

```json
{
  "description": "errors in deploy.log",
  "command": "tail -f deploy.log | grep --line-buffered -E 'ERROR|FATAL|Traceback'",
  "timeout_ms": 1200000
}
```

```json
{ "description": "critical alarms", "source": "alarms", "params": { "severity": "critical" } }
```

시작하면 `Monitor mon_3f9a1c2e started (shell): errors in deploy.log` 처럼 id 를 돌려준다. 더 지켜볼 필요가
없으면 `MonitorStop(monitorId="mon_3f9a1c2e")` 로 멈춘다.

명령 형태에서 지켜야 할 것:

- **파이프의 모든 단계가 줄마다 내보내야 한다.** `grep` 은 `--line-buffered`, `awk` 는 `fflush()`. 그렇지 않으면
  줄이 버퍼에 머물러 이벤트가 오지 않는다
- **실패 신호도 거른다.** 성공 표시만 거르면 프로세스가 죽어도 조용하고, 그 조용함은 "아직 도는 중"과 구별되지
  않는다
- **걸러서 보낸다.** 거르지 않은 `tail -f` 는 속도 제한(§7)에 걸려 멈춘다

---

## 4. 알림이 도착하는 방식

### 4.1 언제

| 세션 상태 | 알림이 주입되는 시점 |
|----------|--------------------|
| 턴이 진행 중 | 다음 도구 호출 iteration 이 끝날 때 |
| 쉬는 중 | **다음 턴**의 첫 LLM 호출 직전 |

기본으로는 쉬는 세션을 모니터가 **깨우지 않는다.** 사용자의 다음 입력이 와야 그 턴에서 쌓인 알림을 읽는다. CLI 도
같다 — 모니터 알림은 턴 뒤에 따로 재생되지 않고 다음 턴의 시작에 들어간다.

**깨우기를 켜면**(라우터를 쓰는 스택: Spring Boot 의 `aimon.session.wake.enabled: true`, 또는
`SessionSpec.wake(WakePolicy)`) 쉬는 세션에 모니터 알림이 도착할 때 그 알림을 입력으로 턴이 하나 열린다. 그 턴은 세션
소유자의 권한으로 돌고 일반 턴처럼 예산·쿼터에 잡힌다. 소유자가 기록되지 않은 세션은 깨우지 않는다. 폭주를 막는 상한이
있다 — 세션당 시간당 6회, 사용자 턴 없이 연속 3회(넘으면 다음 사용자 턴까지 알림이 큐에 남는다). CLI 는 아직 깨우지
않는다. 설계는 [`idle-wake.md`](../../design/session/idle-wake.md).

알림은 그 모니터를 시작한 **세션에만** 간다. 같은 에이전트의 다른 세션은 받지 않는다.

### 4.2 모양

```
Monitor mon_3f9a1c2e (errors in deploy.log) — 2 events:
2026-10-22T10:01:02 ERROR connection refused
2026-10-22T10:01:02 ERROR retry 1/3 failed
```

1초 안에 들어온 이벤트는 한 메시지로 묶인다. 세션이 오래 쉬어도 모니터 하나당 대기 중인 메시지는 **하나**다 —
그 사이 들어온 이벤트는 다음 메시지에 모인다. 버퍼(이벤트 200개, 16,000자)를 넘으면 오래된 것부터 버리고
`(N earlier events dropped …)` 로 알린다.

### 4.3 끝날 때

모니터가 스스로 끝나면 남은 이벤트 뒤에 **종료 알림이 정확히 한 번** 온다.

| 끝난 이유 | 알림 |
|----------|------|
| source 가 끝남 (명령이 0 으로 종료, 소켓이 닫힘) | `ended: the source ended (exit code 0)` |
| source 실패 (0 이 아닌 종료 코드, 연결 실패) | `ended: the source failed: exit code 2; stderr: …` |
| 타임아웃 | `ended: timed out after 20m` |
| 속도 제한 초과 | `ended: stopped — more than 600 events in a minute…` |
| `MonitorStop` · 세션 삭제 · 애플리케이션 종료 | 알림 없음 |

더 오래 지켜봐야 하면 에이전트가 다시 시작한다.

---

## 5. 셸 없이 쓰기 — 호스트 source

운영 자동화에서는 셸 명령보다 **애플리케이션이 이미 가진 이벤트 소스**를 모니터로 내주는 쪽이 안전하다. 모델은
kind 이름과 파라미터만 고르고, 연결 정보·자격 증명·필터는 애플리케이션 코드에 남는다.

```java
public final class AlarmSourceFactory implements MonitorSourceFactory {

    private final AlarmClient client;

    public AlarmSourceFactory(AlarmClient client) {
        this.client = client;
    }

    @Override
    public String kind() {
        return "alarms";                      // 모델이 source 로 고르는 이름
    }

    @Override
    public String describe() {                // Monitor 도구 설명에 한 줄로 들어간다
        return "alarms — platform alarms; params: severity (critical|warning)";
    }

    @Override
    public MonitorSource create(MonitorRequest request) {
        String severity = request.getStringParam("severity").orElse("critical");
        if (!severity.equals("critical") && !severity.equals("warning")) {
            throw new MonitorRequestException("severity must be critical or warning");   // 모델에게 그대로 간다
        }
        return sink -> {
            AlarmSubscription sub = client.subscribe(severity,
                    alarm -> sink.emit(alarm.summary()),          // 이벤트 하나
                    () -> sink.complete("stream closed"),          // 정상 종료
                    error -> sink.fail(error.getMessage()));       // 오류 종료
            return sub::cancel;                                    // MonitorStop · 타임아웃 · 종료 시 호출
        };
    }
}
```

source 가 지켜야 할 계약:

- **`start` 는 막히지 않는다.** 스레드가 필요하면 스스로 띄운다. sink 는 어느 스레드에서 불러도 된다
- **끝나는 모든 길은 `complete` 나 `fail` 을 지난다.** 말없이 죽은 source 는 모델이 타임아웃까지 기다리게 만든다
- **`MonitorHandle.stop()` 은 멱등이고 오래 막히지 않으며 던지지 않는다.** stop 뒤의 sink 호출은 무시된다
- `create` 에서 확인할 수 있는 것은 다 확인하고, 거절은 `MonitorRequestException` 으로 — 메시지가 모델에게 간다

등록은 `ToolSpec.Builder.addMonitorSource(...)`, Spring Boot 에서는 빈 하나면 된다. 기본 kind 는 `shell` 과
`websocket` 이고, 같은 kind 이름으로 등록하면 기본 것을 대체한다.

---

## 6. 권한

허용 도구 목록(allowed-tools)을 쓰는 에이전트라면 `Monitor` 를 거기 적어야 쓸 수 있다.

| 항목 | 허용되는 것 |
|------|-----------|
| `Monitor` | 모든 형태 — 단, 명령은 아래 `Bash` 규칙을 함께 따른다 |
| `Monitor(source:alarms)` | `alarms` source 만 |
| `Monitor(ws:wss://events.example.com/*)` | 그 접두사로 시작하는 WebSocket URL 만 (끝의 `*` 만 와일드카드 — `/*` 로 끝낼 것: `…example.com*` 은 `…example.com.evil.net` 에도 맞는다) |
| `Monitor(tail -f:*)` | 그 명령 패턴에 맞는 명령만 |

**명령 형태는 `Bash` 항목도 통과해야 한다.** `Bash(git:*)` 와 `Monitor` 를 함께 허용한 에이전트는 `git` 으로
시작하는 명령만 모니터로 돌릴 수 있고, `Bash` 항목이 아예 없는 에이전트는 명령 모니터를 쓸 수 없다. `Bash` 에
걸어 둔 제한이 `Monitor` 로 우회되지 않게 하려는 것이다.

**파이프가 든 명령은 어떤 명령 패턴에도 맞지 않는다.** 명령 패턴은 셸 메타문자(`|` `;` `&` `$` `>` `<` `(` `)`
…)가 든 명령을 언제나 거절한다 — `Bash` 와 같은 규칙이다. 그래서 `tail -f app.log | grep --line-buffered ERROR`
같은 전형적인 감시 명령은 `Monitor` 와 `Bash` 가 둘 다 **패턴 없이** 허용되어 있어야 돌 수 있다. 명령을 패턴으로
좁혀야 하는 환경이라면 셸 명령 대신 호스트 source(§5)로 내주는 것이 맞다.

WebSocket 은 공개 호스트에만 연결된다. 사설·루프백·링크 로컬 주소는 URL 검사에서, 그리고 연결된 실제 주소
검사에서 한 번 더 거절된다.

---

## 7. 한도

| 항목 | 기본값 |
|------|-------|
| 타임아웃 | 기본 5분, 최대 30분 |
| 배칭 창 | 1초 |
| 이벤트 하나의 길이 | 2,000자 (넘으면 잘림) |
| 대기 버퍼 | 이벤트 200개 · 16,000자 |
| 속도 제한 | 1분에 이벤트 600개 — 넘으면 모니터를 멈춘다 |
| 소유자(세션)당 동시 모니터 | 8개 |
| 노드당 동시 모니터 | 64개 |

`ToolSpec` 과 Spring Boot 는 기본값으로 매니저를 만든다. 값을 바꾸려면 코어 조립(§2.4)에서
`MonitorManager.Builder` 의 같은 이름 메서드(`maxEventsPerMinute`, `maxTimeout` …)를 쓴다.

---

## 8. 수명과 여러 노드

- 매니저는 **애플리케이션 수명**이다. 에이전트 런타임이 축출되어도 모니터는 계속 돈다
- **세션이 삭제되면** 그 세션의 모니터를 멈춘다. 다만 삭제를 처리한 노드의 모니터만이다
- **세션이 없는 실행**(예약 루틴, 누구의 턴도 요청하지 않은 포크)은 모니터를 시작할 수 없다. 알림을 받을 곳이 없다.
  세션의 턴이 띄운 서브에이전트는 시작할 수 있고, 알림은 그 세션으로 간다
- 모니터는 **시작한 노드에서** 돈다. `MonitorStore` 를 노드 사이에 공유하면 다른 노드에서 `MonitorStop` 했을 때
  "다른 노드에서 실행 중" 이라고 답한다. 세션이 다른 노드로 옮겨 가면 기본 in-memory 큐로는 알림이 따라가지 않는다

---

## 9. 문제 해결

| 증상 | 원인과 해결 |
|------|-----------|
| `This environment's shell cannot stream …` | 실행 환경의 셸이 줄 단위 스트리밍(`ShellFeature.STREAMING`)이나 취소를 지원하지 않는다. 로컬 셸은 둘 다 지원한다. 원격 샌드박스는 구현 여부를 확인할 것 |
| `A monitor needs a session …` | 세션이 없는 실행에서 시작했다 (§8) |
| 시작은 됐는데 이벤트가 안 온다 | 파이프 중간 단계가 버퍼링한다 (`--line-buffered`, `fflush()`). 또는 세션이 쉬는 중이다 — 다음 턴에 들어온다 |
| `stopped — more than 600 events in a minute` | 필터 없이 흘려보냈다. 필터를 좁혀 다시 시작한다 |
| `ws.url points at an address this application does not allow` | 사설·루프백 주소다. 공개 호스트만 허용된다 |
| `Tool 'Monitor' not allowed (command: …): it must also be allowed for 'Bash'` | 명령이 `Bash` 허용 항목에 맞지 않는다 (§6) |
| `You already run 8 monitors, the limit` | 쓰지 않는 모니터를 `MonitorStop` 으로 멈춘다 |
