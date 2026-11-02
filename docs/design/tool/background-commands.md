# 백그라운드 명령

> **Status**: IMPLEMENTED. 적용 대상은 `aimon-core` (`at.aimon.core.tools`, `at.aimon.core.tools.bash`), `aimon-bootstrap`, `aimon-spring-boot-starter` 다. 다른 노드의 작업을 읽거나 멈추는 일과 저장소 레코드 만료는 열려 있다(§12).

## 1. 목적과 범위

`Bash(run_in_background=true)` 로 시작한 명령은 시작한 호출보다, 그리고 시작한 런타임보다 오래 살 수 있다. 이 문서는 그 명령의 수명, 소유, 종료를 정한다.

| 주제 | 정본 |
|---|---|
| 명령 단위 취소 신호, 프로세스 트리 종료, `background` 플래그 | [셸 계약](shell-contract.md) |
| `RuntimeBinding`, 제공자 수명, 축출 | [실행 환경](execution-environment.md) |
| 컴포넌트 수명 일반 규칙 | [`scope-model.md`](../../overview/scope-model.md) |
| 끝나지 않는 명령의 줄 단위 전달 | [Monitor](monitor.md) |

## 2. 시작 경로

`Bash` · `BashOutput` · `KillShell` 은 `OrcaBashToolProvider` 가 함께 등록하고 한 `BackgroundBashManager` 를 나눠 쓴다. 세 도구는 상태가 없고 매니저만 쥔다.

1. `BashTool` 이 호출의 환경에서 셸을 읽는다. 사용 불가 환경은 **시작하기 전에** 확인해 포그라운드와 같은 오류를 돌려준다. 백그라운드 경로는 셸 호출을 미루므로, 확인하지 않으면 "시작했다" 고 보고한 뒤 조회 때 실패한다.
2. `BashTool` 이 상한(§4)을 timeout 으로, `background` 를 켠 옵션을 만들어 `BackgroundBashManager.start(owner, command, shell, options)` 를 부른다.
3. 매니저가 셸의 `supports(CANCELLATION)` 을 묻는다. 선언한 셸이면 작업마다 `ShellCancellationSource` 를 만들어 옵션에 싣는다.
4. 매니저가 레코드를 저장소에 넣은 **뒤에** 자기 스레드 풀에 명령을 제출한다. 기록하지 못한 명령은 이 노드 말고는 아무도 그 존재를 모르므로 돌리지 않는다.
5. 시작 응답이 task id 와 `BashOutput` 사용법을 알린다. 셸이 취소를 선언하면 `KillShell` 사용법을, 선언하지 않으면 멈출 수 없다는 사실과 상한을 함께 적는다. 환경이 상한을 정했으면 그 값도 적는다. 기본 24시간은 적지 않는다. 모델이 계획에 쓸 정보가 아니기 때문이다.

task id 는 `bash_` 와 UUID 앞 8자다. `putIfAbsent` 가 거절하면 다시 뽑고, 16번 실패하면 `Bash` 가 오류로 답한다.

도는 명령은 시작할 때 받은 셸을 붙잡는다. 작업은 셸을 필드로 쥐지 않는다. 작업이 시작한 실행보다 오래 살 수 있으므로 캡처한 셸은 실행이 아니라 제공자의 수명에 기댄다. 이것이 환경이 셸을 소유하는 이유 가운데 하나다([실행 환경](execution-environment.md) 의 수명과 소유권).

출력은 명령이 끝날 때 작업의 버퍼에 한꺼번에 채워지고, `BashOutput` 은 그것을 한 번만 읽게 준다. 도는 작업을 조회하면 돌려줄 출력이 없다. 명령의 notice 는 `BackgroundBashTask` 가 보관했다가 `BashOutput` 의 완료·실패 보고에 한 번 싣는다.

## 3. KillShell

입력은 `taskId` 하나로 `BashOutput` 과 같은 이름이다. 도구는 작업의 취소 신호를 건다. 무엇이 멈추는지는 [셸 계약](shell-contract.md) 의 취소 계약과 프로세스 트리 종료가 정한다.

### 3.1 응답

| 상황 | 결과 |
|---|---|
| 이 노드에서 도는 작업, 셸이 취소를 지원, 5초 안에 `KILLED` 로 정착 | `success`. 명령과 거기 붙어 있는 프로세스가 끝났고 분리된 프로세스는 남을 수 있다고 말한다 |
| 위와 같으나 5초 안에 정착하지 않음 | `success`. 멈춤을 요청했고 아직 내려가는 중이라고 말한다. 작업은 `RUNNING` 으로 남다가 상한에 끝날 수 있다 |
| 위와 같으나 신호가 나가는 순간 명령이 스스로 끝남 | `success`. 멈추기 전에 끝났다고 말하고 상태를 적는다 |
| 이 노드에서 도는 작업, 시작할 때 셸이 `CANCELLATION` 을 선언하지 않음 | `ToolResult.error`. 이 환경의 셸은 도는 명령을 멈출 수 없고 상한에 끝난다고 말한다 |
| 이미 끝난 작업 | `success`. 돌고 있지 않다고 말하고 상태를 적는다 |
| 다른 노드에서 도는 작업, 또는 이 노드가 재시작으로 잃은 작업 | `ToolResult.error`. 조회 결과(§7)의 문장에 여기서 멈출 수 없다고 덧붙인다 |
| 모르는 id, 또는 다른 소유자의 작업 | `ToolResult.error`. 글자까지 같은 not found 다 |

`success` 응답은 남은 출력을 `BashOutput` 으로 읽으라고 안내한다. 답의 범위를 넓히지 않는다. 멈추지 못하는 범위를 숨기면 모델이 끝났다고 믿는다.

매니저의 결과 타입은 `BackgroundBashKill.Outcome` 의 `REQUESTED` · `NOT_RUNNING` · `UNSUPPORTED` · `ELSEWHERE` · `NOT_FOUND` 다. 판단에 쓴 조회를 함께 싣는다.

### 3.2 작업 상태

취소된 작업은 `BashTaskStatus.KILLED` 로 정착한다. `BashOutput` 은 `Status: Killed` 와 명령, notice, 죽기 전까지의 출력을 보이고 `Exit Code` 줄을 내지 않는다. 레코드의 `exitCode` 도 비어 있다. 상한에 걸린 명령은 `ShellTimeoutException` 으로 끝나 `FAILED` 다.

### 3.3 한계

`KillShell` 은 `BashOutput` 과 별개의 도구라 허용 목록에 `BashOutput` 만 있는 에이전트와 스킬은 명령을 멈출 수 없다. 이때 안전장치는 상한이다.

## 4. 환경이 정하는 상한

모델이 끝내기를 잊은 명령과 멈출 수 없는 셸의 명령을 끝내는 안전장치는 `ExecutionEnvironment.backgroundCommandTimeout()` 이다.

- `BashTool` 은 백그라운드 경로에서 이 값을 timeout 으로 쓴다. 환경이 정하지 않으면 24시간이다.
- 환경의 값이 **양방향으로** 이긴다. 24시간보다 길어도 된다. 로컬 제공자의 스테이징 스윕 유예는 더 긴 상한을 따라 올라간다([스테이징](execution-environment-staging.md)).
- 0 이하는 두 겹으로 막는다. 셸이 그 값을 "무한" 으로 읽기 때문이다. 값을 설정하는 길인 `LocalExecutionEnvironmentProvider.Builder` 와 `ExecutionEnvironmentSpec.Builder` 는 `IllegalArgumentException` 을 던진다. 그래서 스타터의 `aimon.environment.background-command-timeout=0s` 는 기동 실패다. 임의의 `ExecutionEnvironment` 구현이 돌려준 값은 `BashTool` 이 WARN 후 무시하고 24시간을 쓴다.
- 1초보다 짧은 양수는 1초로 올린다. 포그라운드와 같은 하한이다.
- 격리 브랜치(`LocalIsolatedEnvironment`)는 부모의 값을 돌려준다.

상한을 서술자에 싣지 않는다. 서술자는 프롬프트에 렌더되고 `equals` 가 프롬프트 캐시의 안정성에 쓰인다. 상한은 정책이고 모델에게는 시작 응답으로 전달된다.

## 5. 매니저 수명

`BackgroundBashManager` 는 부트스트랩 스택에서 **application-scoped** 다. 스택이 하나 만들어 모든 런타임의 세 도구에 넘긴다. 이름이 아니라 키와 저장 위치로 수명을 판단한다는 규칙대로, 이 수명은 누가 만들고 무엇으로 찾느냐로 정해진다.

축출이 끊는 것은 셸이 아니라 작업 목록이다. 로컬 슬롯이 쥔 파일 시스템과 셸을 닫아도 도는 명령은 자기 프로세스만 쥐고 있어 계속 돈다. 그래서 작업 목록을 런타임보다 오래 살게 하고, 바인딩이 슬롯을 명령 끝까지 붙들지 않는다. 축출은 작업을 건드리지 않고, 축출 뒤 다시 만들어진 런타임의 `BashOutput` 이 옛 task id 를 찾는다. 바인딩의 계약은 [실행 환경](execution-environment.md) 이 정한다.

목록의 수명과 작업의 가시 범위는 다르다. 목록은 애플리케이션에 하나이고, 작업은 소유자에게만 보인다(§8).

명령을 돌리는 스레드 풀도 매니저에 있다. 축출된 런타임의 도구 인스턴스가 쥔 풀에서 명령이 돌면 수명이 거꾸로다. 스레드는 데몬이라 아무도 닫지 않는 매니저가 JVM 종료를 막지 않는다.

매니저를 `OrcaProviderDependencies` · `OrcaToolProviderContext` 로 흘리지 않는다. 쓰는 제공자가 하나뿐이고 `OrcaBashToolProvider(BackgroundBashManager)` 생성자로 충분하다. `StackAgentRuntimeProvisioner` 가 기본 제공자 목록의 `OrcaBashToolProvider` 를 스택의 매니저를 쥔 것으로 바꿔 끼운다. 매니저는 `bashEnabled` 일 때만 만든다.

## 6. 저장소와 노드 핸들

한 작업은 두 조각이다.

| 조각 | 타입 | 자리 | 내용 |
|---|---|---|---|
| 메타데이터 | `BackgroundBashRecord` | `BackgroundBashStore`. 기본은 `InMemoryBackgroundBashStore` | 누가 시작했고 어느 노드에서 도는가, 상태 |
| 노드 핸들 | `BackgroundBashTask` | 그 노드 매니저의 메모리 | future, 취소 신호, 출력 버퍼, notice, 명령 |

프로세스가 그 노드에만 있으므로 핸들은 옮길 수 없다. 노드 사이에 공유할 수 있는 것은 메타데이터뿐이다.

### 6.1 저장소 SPI

```java
public interface BackgroundBashStore {
    boolean putIfAbsent(BackgroundBashRecord record);   // id 가 이미 있으면 false, 매니저가 id 를 다시 뽑는다
    Optional<BackgroundBashRecord> find(String taskId);
    Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode, Instant at);
    void remove(String taskId);
}
```

- `settle` 은 `RUNNING` 에서 종료 상태로만 옮기고 멱등이다.
- 구현은 thread-safe 다. 실패한 메서드는 unchecked 예외를 던진다.
- 저장소 SPI 는 이 넷이다. 소비자 없는 메서드는 그 모양을 추측하게 되므로 열거·만료 메서드를 두지 않고, 영속 구현의 레코드 누적은 [EE-57](../../backlog/execution-environment-open-items.md) 에 남긴다.

### 6.2 레코드

`BackgroundBashRecord` 는 불변 class 와 빌더다. 필드는 `taskId`, `ownerRuntimeId`, `ownerSessionId`, `ownerExecutionId`, `nodeId`, `startedAt`, `expiresAt`, `status`, `exitCode`, `finishedAt` 이다. `expiresAt` 은 시작 시각에 상한을 더한 값이다.

레코드에는 명령 문자열도 출력도 싣지 않는다. 명령줄에 자격 증명이 들어갈 수 있고 공유 저장소에서는 프로세스보다 오래 남는다. 명령은 그 노드의 `BackgroundBashTask` 에만 있다.

저장소는 소유 필드 셋을 그대로 돌려줘야 한다. 조회는 셋을 호출자와 비교하므로, 하나라도 잃으면 보이는 범위가 넓어지지 않고 그 작업은 주인을 포함해 누구에게도 보이지 않는다.

### 6.3 조립

저장소는 `ToolSpec.Builder.backgroundBashStore(...)` 로 바꾼다. 스타터는 `BackgroundBashStore` 빈이 있으면 그것을 넘긴다. 노드 id 는 `SessionSpec.getNodeId()` 가 있으면 그것이고 없으면 매니저가 뽑은 임의 값이다. 공유 저장소에서는 안정적인 id 를 줘야 "다른 노드의 작업" 과 "재시작 전 이 노드의 작업" 을 가른다.

## 7. 조회 결과

`BackgroundBashManager.find` 의 답은 `BackgroundBashLookup.Kind` 셋 중 하나다.

| 결과 | 뜻 | `BashOutput` |
|---|---|---|
| `LOCAL` | 이 노드에 핸들이 있다 | 상태와 출력 |
| `ELSEWHERE` | 저장소에 레코드만 있다 | `ToolResult.error`. 출력을 주지 못했으므로 오류다 |
| `NOT_FOUND` | 없는 id 이거나 다른 소유자의 작업이다 | `ToolResult.error`. `Shell not found` |

`ELSEWHERE` 에는 표지가 둘 붙는다.

- `lostByThisNode()` — 레코드의 노드 id 가 이 노드다. 재시작으로 핸들을 잃었다.
- `expired()` — `RUNNING` 인 채 `expiresAt` 을 넘겼다. 상한이 명령을 끝냈을 시각이 지났지만 끝을 알린 노드가 없어 결과를 알 수 없다. 이 답을 끝으로 매니저가 레코드를 치운다.

문구는 어디서 도는지, 이 노드가 잃었는지, 결과를 모르는지를 말하고 `Shell not found` 와 구분된다. 노드 id 는 모델에게 보이지 않는다. 배포의 내부 이름이고 모델에게는 뜻이 없다.

## 8. 소유 범위

한 목록을 모든 테넌트가 보므로 소유자로 조회를 좁힌다. task id 는 32비트라 경계가 될 수 없다.

소유자는 런타임과, 호출이 대신하는 세션(`SESSION_ID`, 없으면 `INVOKING_SESSION_ID`)이다. 세션이 없으면 그 실행(`EXECUTION_ID`)이다. 세 id 가 다 없으면 런타임만이다. 조회와 종료는 전부 같을 때만 찾고, 다르면 없는 id 와 글자까지 같은 답을 한다.

`BackgroundTaskOwner.of(ToolContext)` 가 호출의 소유자를 계산하는 유일한 지점이다. 시작하는 호출과 읽거나 멈추는 호출이 어긋나지 않게 하려는 것이다. 타입이 `at.aimon.core.tools` 에 있는 것은 Monitor 도 같은 규칙을 쓰기 때문이다.

| 호출 | 소유자 | 결과 |
|---|---|---|
| 세션의 턴 | 런타임 + 그 세션 | 같은 런타임의 다른 세션은 읽지도 멈추지도 못한다 |
| 그 세션을 위해 띄운 포크 | 런타임 + 호출한 세션 | 부모와 형제 포크가 서로의 작업을 읽고 멈춘다. 모두 같은 사용자 세션을 대신해 돌므로 인가 경계 안이다 |
| 스케줄 루틴 | 런타임 + 그 발화의 `ExecutionId` | 발화가 끝나면 아무도 닿지 못한다. 작업은 상한과 스택 종료로 끝난다. 다음 발화는 이전 발화의 작업을 멈출 수 없다. 발화 사이에 공유되는 정체성은 런타임뿐이고 그것이 닫으려는 범위이기 때문이다 |
| 호출자 없는 포크 | 런타임 + 자기 `ExecutionId` | 그 포크를 띄운 실행이 작업을 읽지 못한다. `EXECUTION_ID` 는 포크에 전달되지 않는다. 잇는 문제는 [EE-67](../../backlog/execution-environment-open-items.md) 에 열려 있다 |
| 세션도 실행 id 도 없는 호출 | 런타임만. 런타임 id 도 없으면 `BackgroundTaskOwner.none()` | 소유자가 같은 호출끼리 서로 보고 멈춘다. 소유자 필드 셋이 다 비면 같은 처지의 모든 호출자가 그렇다 |

- **규칙.** 세션과 실행 id 가 함께 오면 세션이 이기고 실행 id 는 버린다. 팩터리는 던지지 않는다. 도구의 `execute()` 안에서, 그리고 레코드를 비교하는 `find` 안에서 불리기 때문이다.
- **`none()`.** `start` · `find` · `kill` 은 null 소유자를 거절한다. 런타임 밖의 호출자는 `none()` 을 넘긴다. 매니저를 런타임끼리 나눠 쓰는 조립은 모든 도구 컨텍스트에 `AGENT_RUNTIME_ID` 와 세션 또는 실행 id 를 실어야 한다. 소유자 비교가 유일한 경계이기 때문이다.
- **노드.** 저장소 레코드의 소유자를 "다른 노드" 판정보다 **먼저** 비교한다. 그래서 답이 묻는 노드에 달리지 않는다. 같은 세션이 다른 노드에서 다시 열리면 `ELSEWHERE` 를 듣고, 다른 세션은 어느 노드에서든 "없음" 을 듣는다. 실행 id 소유자는 그 실행이 사는 노드에서만 물을 수 있으므로 다른 노드에서는 언제나 "없음" 이다.
- **세션의 끝.** 세션이 끝나도 작업의 수명은 바뀌지 않는다. 소유 범위는 가시 범위만 정하고, 수명은 상한과 보존 기간과 스택 종료가 정한다.
- **id 추측.** 범위 밖 요청은 같은 not found 문구로 답한다. 응답 시간의 차이는 다루지 않는다.

이 규칙의 모양은 선례를 따른다. "자기 세션, 없으면 호출한 세션" 은 `SideEffectApprovalGate.scopeKeyOf` 의 인가 키이고, "범위 밖은 없음과 같은 답, 동작 전에 인가" 는 `ScopedSubagentTaskController` 의 형태다.

`INVOKING_SESSION_ID` 는 상태 분할 키로 쓰지 말라고 문서화되어 있다. 포크의 상태가 부모와 합쳐지기 때문이다. 여기서는 그 합쳐짐이 요구 사항이고, "이 실행은 어느 세션을 대신하는가" 라는 인가 질문이 이 키의 본래 용도다.

`Monitor` 는 같은 타입을 쓰되 세션 없는 소유자를 거절한다. 그 제약은 [Monitor](monitor.md) 가 소유한다.

## 9. 보존

끝난 지 보존 기간(`DEFAULT_RETENTION`, 24시간)이 지난 작업은 다음 `start` 때 레코드와 함께 치운다. 애플리케이션 수명의 목록에서 끝난 작업이 프로세스 수명 동안 쌓이지 않게 하려는 것이다. 보존 기간이 지난 작업을 조회하면 not found 다.

`registerTask` 로 넣은 작업은 소유자가 `none()` 이고 취소 핸들이 없는 노드 로컬 작업이다. 저장소에 쓰지 않고 `KillShell` 은 `UNSUPPORTED` 로 답한다. `removeTask` 와 `clear()` 는 로컬 작업과 함께 레코드도 지운다. 남기면 "이 노드가 재시작으로 잃은 작업" 으로 보인다. 둘 다 도는 명령을 멈추지 않는다. id 만 받는 메서드(`getTask`, `readNewOutput` 등)는 소유 범위를 보지 않는다. 매니저를 직접 쥔 호출자를 위한 것이고 세 도구는 쓰지 않는다.

## 10. 티어다운과 스택 종료

스택 종료는 도는 백그라운드 명령을 멈춘다. 애플리케이션 수명 매니저에는 닫는 시점이 있어야 하고, 스택을 다시 만드는 호스트에서 명령이 쌓인다. 런타임 축출은 건드리지 않는다. 그래서 CLI 를 끝내면 모델이 띄운 명령도 끝난다.

`BACKGROUND_COMMANDS` 는 `AGENT_RUNTIMES` 뒤다. 마지막으로 배출되는 실행이 명령을 시작할 수 있다. `AGENT_RESOURCES` 앞이다. 취소가 제공자 소유 셸을 거친다. 환경의 셸은 제공자 소유라 `AGENT_RESOURCES` 에서 닫힌다. 테넌트 바인딩은 그보다 먼저 닫히지만 `RuntimeBinding` 계약이 도는 명령을 멈추거나 그것이 쥔 것을 놓지 못하게 하므로 순서가 안전하다. 훅이 닫힌 셸을 만나는 경우는 [훅 환경](../hook/hook-environment.md) 이 소유한다.

`BackgroundBashManager.close()` 는 멱등이고 다음을 한다.

1. 새 작업을 거절한다.
2. 이 노드에서 도는 작업 가운데 취소할 수 있는 것에 신호를 **나란히** 건다. 신호 하나가 셸의 유예 전체를 기다릴 수 있어 차례로 걸면 종료가 명령 수에 비례해 늘어난다.
3. 풀을 내리고 최대 5초 기다린다. 남은 스레드는 `shutdownNow()` 의 인터럽트를 받는다. 취소를 선언하지 않았지만 인터럽트에 반응하는 셸의 명령은 여기서 끝난다. 인터럽트에도 반응하지 않는 셸의 명령은 남는다.

작업은 닫힌 뒤에도 읽을 수 있다. `start` 가 열림 확인을 통과한 뒤 `close()` 가 돌면 그 작업을 놓칠 수 있으므로, `start` 는 작업을 넣은 뒤 닫힘을 다시 보고 그 자리에서 신호를 건다. 셸이 명령을 띄우기 전에 걸린 신호면 명령은 시작되지 않는다.

같은 단계에 `MonitorManager` 도 있다. 이유는 같다.

## 11. 코어만 쓰는 조립

인자 없는 `OrcaBashToolProvider()` 는 도구 레지스트리마다 매니저를 만든다. 아무도 닫지 않고 레지스트리와 함께 잊힌다. 런타임보다 오래 사는 목록과 바인딩이 필요한 조립은 스스로 챙겨야 한다. 이 공백은 [EE-56](../../backlog/execution-environment-open-items.md) 에 열려 있다.

## 12. 멀티 인스턴스 한계

- 다른 노드의 작업은 보고만 한다. 출력을 읽거나 명령을 멈출 수 없다. 저장소가 메타데이터만 들기 때문이다. [EE-53](../../backlog/execution-environment-open-items.md) 에 열려 있다.
- 저장소에는 죽은 노드의 레코드를 열거하거나 만료시킬 수단이 없다. `expired()` 는 그 레코드를 소유자가 물을 때만 치운다. [EE-57](../../backlog/execution-environment-open-items.md) 에 열려 있다.

저장소 구현을 바꾸는 것만으로 "다른 노드에서 도는 작업" 보고가 동작한다. 멀티 인스턴스 설계 원칙대로 교체는 구현체 교체다.

## 13. 실패 처리

| 상황 | 처리 |
|---|---|
| 취소를 지원하지 않는 셸에서 `KillShell` | `ToolResult.error`. 명령은 상한까지 돈다. 시작 응답이 미리 말한다 |
| 원격 셸이 멈춤을 요청했으나 `execute` 가 돌아오지 않는다 | `KillShell` 은 5초 뒤 내려가는 중이라고 답한다. 작업은 `RUNNING` 으로 남다가 상한에 끝난다 |
| 저장소가 `putIfAbsent` 에서 던진다 | 명령을 시작하지 않고 `ToolResult.error` |
| 저장소가 `settle` 에서 던진다 | WARN. 이 노드의 핸들이 정본이므로 `BashOutput` 은 맞게 답한다. 다른 노드는 `expiresAt` 까지 `RUNNING` 으로 본다 |
| 저장소가 `find` 에서 던진다 | 로컬 핸들을 먼저 보므로 이 노드의 작업은 영향이 없다. 로컬에 없을 때만 `ToolResult.error` |
| 노드가 재시작했다(영속 저장소) | 레코드는 남고 핸들은 없다. 같은 노드 id 면 이 노드가 잃었다고, 다른 id 면 다른 노드의 작업이라고 보고한다. `expiresAt` 뒤에는 치운다 |
| 스택이 닫힌 뒤 `Bash(run_in_background)` | 매니저가 거절하고 `ToolResult.error` |
| 저장소가 소유 필드를 잃는다 | 호출자와 일치하지 않아 "없음". 과하게 닫히는 쪽으로 실패한다 |

## 14. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| `KillShell` 을 별도 도구로 둔다 | `TaskStop` 은 `SubagentTaskController` 로 서브에이전트 작업을 다루고 id 공간과 종료 의미(협조적 취소)가 다르다. 읽기 도구가 파괴적 동작을 겸하면 권한 규칙을 도구 이름으로 가를 수 없다. 이름은 `BashOutput` 의 "background shell" 어휘와 짝을 이룬다 | `TaskStop` 에 합친다. `BashOutput` 에 `kill` 인자를 더한다 |
| 매니저를 application-scoped 로 둔다 | 축출이 작업 목록을 끊으면 안 된다(§5) | 매니저를 agent-scoped 로 두고 축출된 런타임의 매니저를 id 별로 보관했다가 다시 붙인다. 수명이 둘인 객체가 되고 저장소 분리와 다른 노드 보고가 되지 않는다 |
| 저장소에 메타데이터만 넣는다 | 프로세스와 출력은 한 노드의 것이다. 레코드에 비밀이 남으면 안 된다(§6.2) | 출력까지 저장소에 넣는다. 스트림당 최대 1MB 이고 한 번 읽기 커서가 원자적 "가져가기" 를 요구한다 |
| 생성자로 매니저를 넘긴다 | 쓰는 제공자가 하나다(§5) | `OrcaProviderDependencies` · `OrcaToolProviderContext` 로 흘린다. 공개 SPI 접근자만 는다 |
| 소유자를 (런타임, 세션 또는 실행) 으로 두고 전부 같아야 찾는다 | 세션을 대신한 실행끼리는 나누고 다른 세션은 막는다(§8) | `Principal` 로 둔다. 같은 사용자의 두 세션이 서로를 멈추고, 루틴의 `PRINCIPAL` 은 작업 소유자라 루틴끼리 합쳐지며, 인증 없는 배포에는 값이 없다 |
| 포크의 작업을 호출한 세션의 것으로 친다 | 부모 세션이 포크의 작업을 읽고 멈춰야 한다 | 포크의 `ExecutionId` 로만 소유한다 |
| 소유자 인자의 타입을 넓힌다 | 비교할 값이 레코드에 없으면 감쌀 수 없다. 스키마가 먼저다 | `ScopedSubagentTaskController` 처럼 매니저를 감싸는 데코레이터 |
| 호출자 없는 계보를 잇지 않는다 | 사용자가 없는 실행의 가장자리 하나를 위한 것이다(열림은 §8) | "루트 실행 id" 를 새로 만든다. id 가족에 네 번째가 더해진다 |
| 소유자를 타입 있는 필드 셋으로 둔다 | `SessionId` 와 `ExecutionId` 구분이 타입으로 남는다 | 불투명 문자열 하나(`"session:<id>"`). 구분이 문자열 접두사 규약으로 내려간다 |
| 런타임만 받는 오버로드를 두지 않는다 | 호출처 하나가 조용히 넓은 범위로 남는다 | 런타임만 받는 호환 오버로드 |
| 스택 종료가 명령을 멈춘다 | §10 | 풀만 내리고 신호를 걸지 않는다. 명령이 호스트에 쌓인다 |
| 환경의 상한이 양방향으로 이긴다 | 도는 명령이 슬롯을 깨워 두는 배치는 하루를 감당하지 못해 더 짧게 정하고, 명령이 정당하게 더 오래 도는 배치는 더 길게 정한다 | 24시간을 천장으로 둔다 |
| 저장소에 열거·만료 메서드를 두지 않는다 | 소비자가 없다(§6.1) | `removeExpired(Instant)` 를 미리 넣는다 |

## 15. 하지 말 것

- **`RuntimeBinding.close()` 에서 도는 명령을 멈추지 말 것.** 축출은 종료 요청이 아니다. 멈추는 것은 `KillShell`, 상한, 스택 종료다.
- **백그라운드 작업이 셸을 필드로 쥐지 말 것.** 셸은 `BackgroundBashManager.start(...)` 의 인자로만 받고 도는 명령이 붙잡는다. `toolsHoldNoFileSystemOrShellFields` 가 강제한다.
- **실행의 인터럽트를 백그라운드 명령에 잇지 말 것.** 명령이 실행보다 오래 사는 것이 이 경로의 목적이다. 백그라운드 명령의 신호는 작업의 것이다.
- **레코드에 명령이나 출력을 싣지 말 것.** 저장소 구현도 더하면 안 된다.
- **저장소에서 소유 필드를 잃지 말 것.** 작업이 주인에게도 보이지 않는다.
- **범위 밖 id 에 다른 문구로 답하지 말 것.** 답이 달라지면 id 의 존재가 새어 나간다. 소유자 비교를 "다른 노드" 판정 뒤로 옮기는 것도 같다.
- **도구에서 id 만 받는 매니저 메서드를 쓰지 말 것.** 소유 범위를 보지 않는다.
- **세션 범위를 `AGENT_RUNTIME_ID` 만으로 판단하지 말 것.** 한 런타임의 세션은 서로 다른 소유자다.
- **0 이하의 상한을 셸에 넘기지 말 것.** 셸이 무한으로 읽는다.
- **기록에 실패한 명령을 시작하지 말 것.** 이 노드 밖에서는 존재를 알 수 없는 명령이 된다.
- **모델에게 노드 id 를 보이지 말 것.**

## 16. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다. 다른 모듈은 모듈 경로를 함께 적었다.

| 항목 | 파일 |
|---|---|
| 도구 | `tools/bash/BashTool.java`, `tools/bash/BashOutputTool.java`, `tools/bash/KillShellTool.java` |
| 도구 등록 | `agent/impl/orca/tool/OrcaBashToolProvider.java` |
| 매니저와 핸들 | `tools/bash/BackgroundBashManager.java`, `tools/bash/BackgroundBashTask.java`, `tools/bash/BashTaskStatus.java` |
| 결과 타입 | `tools/bash/BackgroundBashLookup.java`, `tools/bash/BackgroundBashKill.java` |
| 저장소 | `tools/bash/BackgroundBashStore.java`, `tools/bash/InMemoryBackgroundBashStore.java`, `tools/bash/BackgroundBashRecord.java` |
| 소유자 | `tools/BackgroundTaskOwner.java` |
| 상한 | `environment/ExecutionEnvironment.java`, `environment/impl/LocalExecutionEnvironmentProvider.java`, `environment/impl/LocalIsolatedEnvironment.java` |
| 상한 설정 | `aimon-bootstrap` `spec/ExecutionEnvironmentSpec.java`, `aimon-spring-boot-starter` `AimonProperties.java` |
| 조립과 종료 | `aimon-bootstrap` `AimonStackBuilder.java`, `assemble/StackAgentRuntimeProvisioner.java`, `TeardownPhase.java`, `spec/ToolSpec.java` |
| 선례 | `toolinvocation/approval/SideEffectApprovalGate.java`, `subagent/ScopedSubagentTaskController.java` |
| 아키텍처 가드 | `modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java` |
