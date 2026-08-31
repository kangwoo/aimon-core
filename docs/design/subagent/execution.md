# 서브에이전트 실행 (Subagent Execution)

> Status: **IMPLEMENTED** — 태스크 수명 관리, 백그라운드 실행, 세그먼트 출력 로그, resume,
> cross-agent 격리, cross-node 정지·좀비 회수, 완료 알림이 모두 들어가 있다. 남은 것은 §11 —
> 샌드박스 연계 격리와 구조화 스트리밍 포맷.
>
> 적용 대상: `aimon-core` — `at.aimon.core.subagent`(`SubagentExecutionManager`,
> `SubagentTaskController`, `ScopedSubagentTaskController`, `CompositeSubagentRegistry`),
> `…subagent.task`(태스크 모델·스토어·리스·출력·스냅샷), `…subagent.execution`(`DefaultSubagentExecutor`,
> `SubagentOutputSink`), `at.aimon.core.tools.task`(`Task`·`TaskList`·`TaskStop`·`AgentOutput`),
> `at.aimon.core.toolinvocation`(공용 단일 도구 파이프라인) ·
> `aimon-session-{redis,mongodb,postgres}` — 분산 `BackgroundTaskStore`·`RedisTaskStopSignal` ·
> `aimon-bootstrap` — 조립.

---

## 1. 문제 — 포크는 있었는데 관리 표면이 없었다

`Task` 도구는 처음부터 서브에이전트를 포크할 수 있었다. 없었던 것은 **포크한 다음**이다. 백그라운드로
띄운 실행을 나열할 방법도, 중간 출력을 훔쳐볼 방법도, 멈출 방법도, 이어서 재개할 방법도 없었다.
`TaskTool.resume` 는 "not yet implemented" 하드 에러였다.

주목할 것은 **필요한 하위 primitive 가 이미 전부 있었다**는 점이다. 부족한 것은 그것들을 서브에이전트
경로에 **연결**하는 일이었다.

| 이미 있던 primitive | 위치 | 당시 서브에이전트 경로에서의 활용 |
|---|---|---|
| 스트리밍 이벤트 | `at.aimon.core.agent.stream.*`, `EventEmitter` | 미사용 — 포크는 이벤트를 내지 않았다 |
| 인터럽트/취소 | `CancellationSignal`, `InterruptCoordinator`, `Terminator` | 부모 턴이 살아 있는 동안만 — detached 태스크는 취소 불가 |
| 가상 파일시스템 | `VirtualFileSystem` (local/gridfs/s3) | 출력 영속화에 미사용 |
| 실행 예산 | `ExecutionBudget`, `BudgetTracker` | 요청 필드는 있었으나 `TaskTool` 이 채우지 않음 |
| resume plumbing | `SubagentExecutionRequest.previousSnapshot`, `TranscriptBuffer.toSnapshot()` | 존재했으나 도구가 하드 에러 |

그래서 이 설계는 새 스레드 모델이나 새 이벤트 버스를 만들지 않는다. 기존 primitive 를 태스크 수명이라는
축에 꿴다.

---

## 2. 설계 원칙

1. **저장소는 인터페이스 뒤로.** 태스크 메타데이터·출력 로그·스냅샷은 전부 SPI 로 분리하고 기본은
   in-memory / VFS 구현. 분산 전환이 리팩터링이 아니라 구현체 교체여야 한다
   (`.claude/rules/multi-instance-design.md`)
2. **기존 primitive 재사용 우선.** `agent.stream.*`, `InterruptCoordinator`, `VirtualFileSystem`,
   `ExecutionBudget` 을 연결한다
3. **실행은 노드-로컬, 상태·출력·취소신호는 공유 가능.** 완전 분산 실행은 비목표다 (§5)
4. **하위 호환.** 기본 동작(동기 실행, 기존 생성자)은 유지. 신규 기능은 전부 opt-in — 팩토리 오버로드나
   설정 주입이 없으면 예전과 같이 동작한다
5. **도구 규칙 준수.** `execute()` 는 예외를 던지지 않고 `ToolResult.error()` 를 반환한다

---

## 3. 태스크 수명 — 5-상태 기계

`BackgroundTaskState` 는 다섯 상태이고 전이는 단방향이다.

```
PENDING ─▶ RUNNING ─▶ COMPLETED
                   ├─▶ FAILED
                   └─▶ KILLED
PENDING ───────────▶ KILLED     (시작 전에 정지된 경우)
```

`isTerminal()` (= `COMPLETED` · `FAILED` · `KILLED`) 하나가 세 가지를 게이트한다 — 축출 대상 판정,
완료 알림 중복 방지, 그리고 전이의 멱등성.

전 상태를 `PENDING` 부터 시작한 것은 큐 대기와 실제 실행을 구분하기 위해서다. 유계 풀
(`SubagentBackgroundConfig`, 기본 동시성 `min(4, availableProcessors)`, 큐는 기본 무제한
`UNBOUNDED_QUEUE`)에서 큐가 차면 `AbortPolicy` 가 던지고 태스크는 곧장 `FAILED` 로 떨어진다 —
조용히 유실되지 않는다.

### 전이는 상태를 먼저 쓴다 — 단, 결과 저장은 그 앞이다

`transition(taskId, to)` 는 **상태를 먼저 확정하고 그다음에 부수효과**(알림·큐 적재)를 낸다. 순서를
뒤집으면 `block=true` 로 대기 중인 `AgentOutput` 호출자가 상태를 다시 읽었을 때 아직 non-terminal 을
보고 한 바퀴 더 도는 창이 생긴다.

예외가 하나 있고, 그것은 부수효과가 아니다. **터미널 전이 직전에 `TaskResultStore` 로 결과를 먼저
저장한다** — 터미널 상태가 곧 "결과를 읽을 수 있다"는 신호가 되어야 하기 때문이다. 두 순서 규칙은
충돌하지 않는다: 결과 저장은 전이가 발행하는 사실의 일부이고, 알림·큐 적재는 그 사실에 대한 반응이다.
근거와 지켜야 하는 두 경로는 [`background-task-result-persistence.md` §3](background-task-result-persistence.md).

### terminal-guard 는 저장소 계약이다

터미널 상태에 도달한 태스크는 다시 전이하지 않는다. 이것을 호출자의 성실함이 아니라 **저장소의 원자적
연산**으로 강제한다 — 그래야 좀비 회수와 정상 완료가 동시에 도착해도 결과가 하나로 결정된다.

| 백엔드 | 원자성 수단 |
|---|---|
| in-memory | `ConcurrentHashMap.computeIfPresent` 안에서 `isTerminal()` 검사 |
| Redis | Lua 스크립트 |
| MongoDB | `findOneAndUpdate` + 상태 조건 |
| PostgreSQL | 단일 문장 `UPDATE … WHERE state NOT IN (…) RETURNING *` |

in-memory 구현은 터미널 태스크를 최대 `DEFAULT_MAX_TERMINAL_TASKS`(1000)개까지만 보관하고 FIFO 로
축출한다 — 완료된 태스크가 힙을 무한히 먹는 것을 막되, `/clear` 로는 사라지지 않는다.

---

## 4. 공유 가능한 것과 노드 로컬인 것

이 설계의 중심 결정이다. 태스크에 붙는 상태는 두 종류인데, **직렬화 가능한 것과 그렇지 않은 것**을
같은 타입에 담으면 분산이 불가능해진다.

| | 공유 가능 (직렬화됨) | 노드 로컬 |
|---|---|---|
| 타입 | `BackgroundTask` | `RunningTaskHandle` / `RunningTaskRegistry` |
| 내용 | `taskId`, `subagentName`, `description`, `state`, `startTime`, `endTime`, `outputOffset`, `owner`, `agentRuntimeId`, `lastHeartbeat` | `Future`, `CancellationSignal` |
| 저장 | `BackgroundTaskStore` (in-memory / redis / mongo / postgres) | JVM 힙 |

`BackgroundTask` 는 **핸들을 하나도 들지 않는다** — javadoc 에 명시되어 있다. 그래서 노드 B 가 노드 A 의
태스크를 `TaskList` / `AgentOutput` 으로 **조회**할 수 있고, 정지만 별도 채널이 필요하다.

조회 조건은 `TaskQuery` 로 표현한다 — `all()` / `byState()` / `byAgentRuntime()` 과 빌더
(`state` · `owner` · `agentRuntimeId`). 스토어가 서버 측에서 거를 수도, `matches(BackgroundTask)` 로
클라이언트 측에서 거를 수도 있게 술어를 값으로 만든 것이다.

### cross-node 정지

실행 핸들이 A 에만 있으므로 B 의 `TaskStop` 은 스토어를 건드려서는 안 된다(상태만 바꾸면 A 의 스레드는
계속 돈다). 대신 `TaskStopSignal` 로 `taskId` 를 브로드캐스트하고, 소유 노드가 자기 `RunningTaskRegistry`
에서 핸들을 찾아 trip 한다. 코어 기본값은 `NoopTaskStopSignal`(단일 노드에서는 로컬 레지스트리로 충분),
분산 구현은 `RedisTaskStopSignal`(단일 pub/sub 채널)이다. 별도 인프라가 필요 없다 — 메타데이터 스토어와
백엔드를 공유한다.

### 좀비 회수

노드-로컬 실행의 대가: 노드 A 가 크래시하면 그 태스크는 공유 스토어에 non-terminal 로 영원히 남는다.
터미널로 전이시킬 주체가 사라졌기 때문이다. 이를 **lease/heartbeat** 로 닫는다.

- 소유 노드의 `TaskHeartbeatPublisher` 가 `heartbeatInterval` 마다 자기 `RunningTaskRegistry` 의 태스크에
  `store.heartbeat(...)` 를 찍는다 (역시 terminal-guarded)
- 살아 있는 **아무 노드**의 `ZombieTaskReaper` 가 `sweepInterval` 마다 heartbeat(없으면 `startTime` 폴백)가
  `leaseTtl` 을 넘긴 non-terminal 태스크를 `FAILED` 로 회수한다

회수 주체를 스케줄러도 스토어도 아닌 **실행 매니저가 소유한 노드-로컬 데몬**으로 둔 것이 핵심이다.
모든 노드가 각자 돌리므로 조율이 필요 없고, terminal-guard 가 중복 회수를 무해하게 만든다.

`TaskLeaseConfig` 는 `leaseTtl > heartbeatInterval` 을 **하한으로 강제**한다 (기본 10s / 30s / sweep 10s) —
GC pause 한 번에 살아 있는 태스크가 회수되는 오탐을 막기 위해서다. lease 는 기본 **off** 이며
(`config == null` → 스레드 미기동) 공유 스토어와 함께 쓸 때만 의미가 있다. 단일 인스턴스에서는 프로세스와
함께 스토어도 사라지므로 좀비가 생기지 않는다.

회수된 태스크의 부분 출력은 별도 노출 경로가 필요 없다 — 이미 `TaskOutputStore` 에 세그먼트로 쌓여 있어
회수 후에도 마지막 세그먼트까지 `AgentOutput` 으로 읽힌다.

---

## 5. 출력 — 세그먼트 로그

백그라운드 태스크의 출력은 **append-only 로그**이고, 독자는 "내가 마지막에 읽은 지점부터"를 요구한다.
그런데 기본 VFS 백엔드(S3, GridFS)에는 **append 연산이 없다.**

그래서 하나의 파일에 이어 쓰는 대신 **오프셋으로 이름 붙인 세그먼트 파일**을 쓴다.

```
.aimon/task-output/<taskId>/00000000000000000000.seg
                            00000000000000004096.seg
                            00000000000000008192.seg
```

파일명이 `OFFSET_FORMAT = "%020d"` 로 제로 패딩되므로 **사전순 정렬 = 오프셋 순서**이고, 요청 오프셋을
담은 세그먼트를 O(1) 로 고를 수 있다. 각 `append` 는 새 객체를 만들 뿐이므로 append 없는 스토리지에서도
성립한다.

`TaskOutputStore` 표면은 셋이다 — `append(taskId, chunk)`, `read(taskId, fromOffset, maxChars)` →
`OutputSlice`, `length(taskId)`.

### 오프셋 단위는 바이트가 아니라 **문자**다

의도적인 이탈이다. 소비자가 LLM 이고 절단 지점이 문자 경계여야 하기 때문에, 오프셋을 바이트로 두면
멀티바이트 문자 중간에서 잘린 슬라이스가 나온다. 문자 단위이면 세그먼트 파일 크기가 정확히 균일하지
않게 되지만, 그 대가로 어떤 인코딩에서도 슬라이스가 항상 유효한 문자열이 된다.

### 포그라운드는 비용이 0이어야 한다

출력 수집은 `SubagentOutputSink` 라는 한 줄짜리 seam 뒤에 있고, 포그라운드 실행에는 `NO_OP` 이 주입된다.
"이벤트를 내지 않는다"는 기존 동작이 분기문이 아니라 구현체 교체로 유지된다.

### 델타 읽기

`AgentOutput` 은 `taskId` 하나만 필수이고, 한 번에 기본 `DEFAULT_DELTA_MAX_CHARS`(8,000)자,
최대 `MAX_DELTA_CHARS`(100,000)자를 돌려준다. `wait_up_to`(기본 150초)를 주면 터미널 전이가 올 때까지
블록한다 — 모델이 폴링 루프를 돌지 않게 하기 위한 것이고, 여기서 §3의 "상태 먼저" 순서가 효력을 낸다.

그 블록은 노드 로컬 퓨처에 대한 `join` 이 아니라 **저장소에 대한 바운드 폴링**이다(500ms 간격). 그래야
다른 노드가 돌리는 태스크도 기다릴 수 있다. 정착 판정과 함께 딸려 오는 세 가지 동작 변화 —
`wait_up_to <= 0` 의 단일 폴링 강등, 인터럽트 가능성, 축출된 태스크의 not-found — 는
[`background-task-result-persistence.md` §4](background-task-result-persistence.md) 에 있다.

---

## 6. 결과 절단 — tail-keep

서브에이전트의 최종 결과는 부모의 트랜스크립트에 들어간다. 무한정 들어갈 수 없으므로
`SubagentResultFormatter` 가 `DEFAULT_MAX_CHARS`(32,000)로 자른다.

**앞이 아니라 뒤를 남긴다.** ReAct 실행의 결론은 끝에 있고, 앞부분은 대개 탐색 로그다. 잘라낸 자리에는
전체 출력을 `AgentOutput` 으로 가져올 수 있다는 retrieval pointer 를 남긴다 — 절단이 정보 손실이 아니라
간접 참조가 되게 한다.

절단 지점이 서로게이트 쌍 중간에 떨어지면 한 칸 물러난다. 이모지 하나 때문에 깨진 문자가 트랜스크립트에
들어가는 것을 막는다.

**상한은 둘이고 이쪽이 좁은 쪽이다.** 같은 결과가 저장소로 갈 때는
`TaskResult.DEFAULT_MAX_SUMMARY_CHARS`(128,000)가 적용된다 — 저장이 표시보다 먼저 버리면 나중에 이
상한을 올려도 텍스트가 돌아오지 않기 때문이다
([`background-task-result-persistence.md` §5](background-task-result-persistence.md)).

---

## 7. resume — 스냅샷 저장소

`Task(resume=<taskId>)` 는 끝난 태스크의 대화를 이어받아 새 실행을 시작한다. 저장 대상은
`SessionSnapshot`(`at.aimon.core.agent.session.transcript`)이고, `SessionSnapshotStore` 가 그것을
`taskId` 로 보관한다. 조회 결과는 `ResumableSession` — 스냅샷과 그것을 만든 `subagentName` 을 함께 든다.

VFS 구현은 envelope 로 감싼다 (`ENVELOPE_VERSION = 1`).

```json
{ "v": 1, "subagentName": "...", "contextId": "agent:...", "snapshot": { … } }
```

`contextId` 키는 Java 필드가 `agentRuntimeId` 로 개명된 뒤에도 **의도적으로 동결**되었다 — 이미 저장된
스냅샷을 읽을 수 있어야 하기 때문이다. 전사 직렬화 자체는 `SessionSnapshotCodec` /
`JsonSessionSnapshotCodec` 에 위임한다.

### 네 가지 하드닝

resume 은 "저장해 두었다가 나중에 쓴다"는 성질 때문에 조용한 사고가 나기 쉬운 자리다. 네 가지를 건다.

1. **LRU 상한** — `InMemorySessionSnapshotStore` 는 `DEFAULT_MAX_SNAPSHOTS`(256)개까지만 들고
   `BoundedLruMap` 으로 축출한다. 무제한 보관은 in-memory 기본값에서 누수다
2. **백그라운드 실행만 저장** — 포그라운드 포크는 부모 턴 안에서 끝나고 resume 대상이 아니다
3. **비어 있지 않을 때만 저장** — 즉시 실패한 실행의 빈 스냅샷을 남기면, resume 이 "성공했으나 아무것도
   이어받지 못한" 실행을 만든다
4. **owner 태그 검사** — 스냅샷에 기록된 `subagentName` 과 resume 요청의 `subagent_name` 이 다르면 거부한다

도구 쪽에서도 같은 층위로 막는다 — `resume` 이 공백이거나, 스토어가 없거나, id 를 못 찾거나,
서브에이전트가 어긋나면 실행 전에 `ToolResult.error()` 다.

---

## 8. cross-agent 격리

태스크 id 는 전역 유니크이므로 공유 스토어는 **모든 에이전트의 태스크를 하나의 평평한 키공간**에 담는다.
충돌은 없지만 격리도 없다 — 스코핑이 없으면 한 에이전트의 `TaskList` 가 남의 태스크를 열거하고
`TaskStop` 이 남의 태스크를 죽인다. 스토어를 넓게 공유할수록(단일 JVM 공유 매니저 → redis/mongo) taskId
하나로 도달할 표면이 넓어진다.

그래서 각 태스크가 스폰 시점에 기록한 `AgentRuntimeId` 로 제어 평면을 게이트한다 —
`ScopedSubagentTaskController` 가 `list` 질의에 자기 `agentRuntimeId` 를 **강제 주입**하고,
`stop` / `status` 는 다른 컨텍스트의 태스크면 거부한다. 스냅샷도 같은 방식으로
`ScopedSessionSnapshotStore` 가 감싼다.

**스코핑 축은 세션이 아니라 에이전트다.** 같은 에이전트의 두 세션은 컨텍스트 id 를 공유하므로 서로의
백그라운드 태스크를 계속 본다 — `AgentRuntime` 의 agent-scoped 수명과 일치하는 선택이고, 그 덕에 태스크가
`/clear` 를 넘어 살아남는다. 테넌트 단위로 더 쪼개려면 `TaskQuery.Builder#owner` 를 조합한다.

데코레이터가 네 가지 공통 설계 선택을 공유한다.

1. **존재를 비노출한다** — 남의 태스크는 "권한 없음"이 아니라 "없음"으로 답한다. 존재 여부가 사이드
   채널이 되지 않게 한다
2. **passthrough seam** — `scopeOrPassThrough(delegate, maybeAgentRuntimeId)` 는 컨텍스트 id 가 없으면
   원본을 그대로 돌려준다. 게이트를 켤 수 없는 경로(테스트, 미배선 어셈블리)에서 회귀가 나지 않는다
3. **best-effort 강등** — 격리는 authz 레이어이지 샌드박스가 아니다. 판정할 수 없으면 막는 것이 아니라
   스코핑을 걸지 않는다
4. **저장은 위임한다** — 게이트는 **읽기 경로에만** 얹고 쓰기는 원본에 그대로 보낸다. 그래서 스토어
   구현이 in-memory 든 분산이든 격리가 동일하게 성립한다 — 격리와 백엔드 선택은 직교한다

스코핑 키가 **소유 노드가 아니라 태스크에 저장된 `agentRuntimeId`** 라는 점도 결과에 나타난다 — 같은
컨텍스트의 태스크는 다른 노드에서 돌고 있어도 보이고 멈출 수 있으며, 다른 컨텍스트의 태스크는 어느
노드에서도 거부된다.

---

## 9. 도구 표면

| 도구 | 필수 | 선택 |
|---|---|---|
| `Task` | `subagent_name`, `prompt`, `description` | `model`, `run_in_background`, `resume` |
| `TaskList` | — | `state` |
| `TaskStop` | `taskId` | — |
| `AgentOutput` | `taskId` | `wait_up_to`, 델타 크기 |

네 도구 모두 스키마에 `additionalProperties: false` 를 선언한다 — 오타가 조용히 무시되는 대신
"Unknown parameter … Did you mean …?" 로 되돌아온다.

제어 평면 셋(`TaskList` / `TaskStop` / `AgentOutput`)은 매니저 전체가 아니라 좁은
`SubagentTaskController`(`stop` / `list` / `status`)에만 의존한다. `SubagentExecutionManager` 가 그것을
extends 하므로, 스폰 권한 없이 조회·정지만 필요한 호출자에게 넘길 수 있는 인터페이스가 생긴다 (ISP).
`AgentOutput` 만 저장소 둘(`TaskOutputStore`, `TaskResultStore`)을 추가로 받는데, 둘 다 읽기 전용이고
인가는 여전히 컨트롤러가 한다.

**모델 해석 우선순위**는 `Task(model=…)` > 서브에이전트 frontmatter > 기본값이다. 호출 시점 오버라이드가
선언을 이기되, 선언이 없으면 프레임워크 기본값으로 내려간다.

---

## 10. 완료 알림 — 하이브리드

백그라운드 태스크가 끝났다는 사실은 두 경로로 나간다. 하나로 합치지 않은 이유는 **보장 수준이 다르기
때문**이다.

| 경로 | 대상 | 보장 |
|---|---|---|
| 메시지 큐 적재 | 모델 | 보장 — 다음 턴에 반드시 읽힌다 |
| `SubagentTaskCompleted` 스트림 이벤트 | 사람(UI) | best-effort — 구독자가 없으면 사라진다 |

이벤트만 쓰면 아무도 구독하지 않는 배치 실행에서 모델이 완료를 영영 모른다. 큐만 쓰면 사용자가 다음 턴
전까지 아무 피드백도 못 받는다. 이벤트는 `taskId` · `subagentName` · `outcome` · `detail` 만 싣는다 —
출력 본문은 `AgentOutput` 으로 가져가라는 뜻이다.

중복 발화는 §3 의 terminal-guard 가 막는다. 터미널 전이가 한 번만 성립하므로 알림도 한 번이다.

---

## 11. 공용 단일 도구 파이프라인

`OrcaAgentExecutor`(턴)와 `DefaultSubagentExecutor`(포크)는 서로 다른 루프를 돌지만 **도구 하나를
실행하는 절차**는 같다 — 조회, 권한, 훅, 실행, 관찰 변환. 이것이 두 벌로 갈라져 있었다.

추출 범위를 신중하게 좁혔다. 루프 전체를 통합하는 대신 **단일 도구 파이프라인만**
`SingleToolInvoker` / `ToolInvocationSpec` 로 뽑고 두 실행기가 위임한다. 루프 본문과 시그니처는
건드리지 않았다 — 프롬프트 구성·예산·컴팩션은 두 경로에서 실제로 다르게 동작해야 하는 것들이다.

패키지는 `at.aimon.core.agent.impl..` 도 `at.aimon.core.subagent..` 도 아닌 **중립 최상위**
`at.aimon.core.toolinvocation` 이다. 둘 중 한쪽에 두면 다른 쪽이 그 패키지를 import 하게 되고, 그것은
ArchUnit 이 막는 방향의 의존이다. 소비자가 둘인 코드는 어느 쪽 소유도 아니어야 한다.

---

## 12. 남은 것

- **샌드박스 연계 격리** — 현재 격리는 제어 평면 authz(§8)까지다. `at.aimon.sandbox.model.Sandbox` 를
  서브에이전트 실행에 물려 프로세스 수준으로 가르는 것은 실제 요구가 생길 때의 과제로 남겼다.
  (미사용이던 `subagent/context`·`subagent/permission` 死코드는 이미 제거되었다)
- **구조화 스트리밍 포맷** — 라이브 출력의 1차 포맷을 사람이 읽는 진행 로그로 두었다. JSONL 구조화
  이벤트를 1차로 올릴지는 열려 있다
- **부트스트랩 스토어 승격** — 단일 JVM 에서는 실행기 1개가 여러 `AgentRuntime` 에 공유 주입되므로
  스토어가 사실상 application-scoped 로 동작한다. 실행기를 여러 개 만드는 배치에서만 부트스트랩에서
  스토어를 명시 생성·주입하는 승격이 필요하다
- **`TaskResultStore` 의 분산 백엔드** — 결과 저장은 들어왔지만 구현은 in-memory 와 VFS 둘뿐이다
  ([`background-task-result-persistence.md` §8](background-task-result-persistence.md))

---

## 부록 — 참조 파일 지도

| 파일 | 확인할 것 |
|---|---|
| `subagent/task/BackgroundTaskState.java:16-40` | 5-상태 다이어그램, `isTerminal()` 의 세 용도 |
| `subagent/task/BackgroundTask.java` | 직렬화 가능한 10개 필드 — 핸들 없음 |
| `subagent/task/BackgroundTaskStore.java` | `put`/`find`/`list`/`transition`/`heartbeat`/`remove` |
| `subagent/task/InMemoryBackgroundTaskStore.java:30,87-99` | `DEFAULT_MAX_TERMINAL_TASKS` 1000, `computeIfPresent` terminal-guard |
| `subagent/task/RunningTaskRegistry.java` | 노드-로컬 핸들 (비공유) |
| `subagent/task/TaskQuery.java` | `all`/`byState`/`byAgentRuntime` + `matches` |
| `subagent/task/TaskLeaseConfig.java` | 10s/30s/10s, `leaseTtl > heartbeatInterval` 하한 |
| `subagent/task/ZombieTaskReaper.java:21-46` | 노드-로컬 데몬 sweep, `startTime` 폴백 |
| `subagent/task/TaskHeartbeatPublisher.java` | 소유 노드의 heartbeat 갱신 |
| `subagent/task/TaskStopSignal.java`, `NoopTaskStopSignal.java` | cross-node 정지 seam과 기본값 |
| `subagent/task/TaskOutputStore.java:46,64,73` | `append` / `read(fromOffset, maxChars)` / `length` |
| `subagent/task/VfsTaskOutputStore.java` | `.aimon/task-output`, `%020d` + `.seg` |
| `subagent/task/TaskResultStore.java`, `TaskResult.java` | 결과 저장 계약 (순서·비태깅), 128,000자 저장 상한 |
| `subagent/task/SessionSnapshotStore.java`, `ResumableSession.java` | resume 조회 계약 |
| `subagent/task/VfsSessionSnapshotStore.java` | envelope v1, 동결된 `contextId` 키 |
| `subagent/task/InMemorySessionSnapshotStore.java` | `DEFAULT_MAX_SNAPSHOTS` 256, `BoundedLruMap` |
| `subagent/task/ScopedSessionSnapshotStore.java:71` | `scopeOrPassThrough` |
| `subagent/SubagentTaskController.java` | 좁은 제어 평면 3개 메서드 |
| `subagent/ScopedSubagentTaskController.java:11-40` | 격리 근거와 스코핑 축(agent, 노드 아님) |
| `subagent/execution/SubagentOutputSink.java:17-29` | 포그라운드 `NO_OP` |
| `subagent/execution/SubagentResultFormatter.java` | `DEFAULT_MAX_CHARS` 32,000, `truncateTailKeep` |
| `subagent/SubagentBackgroundConfig.java` | 유계 풀, `UNBOUNDED_QUEUE`, 기본 동시성 |
| `tools/task/TaskTool.java:360-400` | resume 거부 4종 |
| `tools/task/AgentOutputTool.java` | 8,000 / 100,000자, `wait_up_to` 150, 500ms 폴링 루프 |
| `tools/task/TaskListTool.java:124`, `TaskStopTool.java:80` | 제어 평면 스코핑 지점 |
| `toolinvocation/package-info.java` | 중립 최상위 패키지 배치 근거 |
| `agent/stream/SubagentTaskCompleted.java:61-64` | 알림 이벤트 4개 필드 |
| `agent/impl/orca/OrcaAgentExecutorFactory.java:489-545` | 스토어·정지신호·리스 opt-in 배선 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준. 분산 구현은 —
`modules/aimon-session-redis/…/RedisBackgroundTaskStore.java`·`RedisTaskStopSignal.java`,
`modules/aimon-session-mongodb/…/MongoBackgroundTaskStore.java`,
`modules/aimon-session-postgres/…/PostgresBackgroundTaskStore.java`.

---

## 관련 문서

- [`background-task-result-persistence.md`](background-task-result-persistence.md) — 백그라운드 태스크 결과의 저장·순서 계약·대기 의미
- [`code-defined-registration.md`](code-defined-registration.md) — 서브에이전트를 코드로 등록하는 경로
- [`subagent-development-guide.md`](../../features/subagent/subagent-development-guide.md) — 서브에이전트 작성법
- [`../llm/cancellation.md`](../llm/cancellation.md) — 진행 중 LLM 호출을 실제로 끊는 취소 토큰
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `InterruptBehavior` 와 terminator 등록
- [`../tool/parallel-execution.md`](../tool/parallel-execution.md) — 한 iteration 안의 도구 병렬 실행
- [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md) — 저장소 분리 규칙
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 포크는 세션이 아니라 `ExecutionId` 로 식별된다
