# 스케줄 실행 — 요청 시점에 굳힌 routine 을 세션 없이 다시 돌린다

> Status: **IMPLEMENTED** — `at.aimon.core.scheduling`(main 41 / test 16) + `at.aimon.core.tools.scheduling`(main 4 /
> test 3) + `aimon-scheduling-quartz`(main 9 / test 9). **작업 정의를 영속하는 저장소 구현은 아직 없다** — 둘 다
> 인메모리다(§9.1). `timezone` 은 스케줄러까지 전달된다(§4.4).
> 클러스터 배포·웹 환경 설정은 [Quartz 스케줄링 웹 배포 가이드](../../features/scheduling/quartz-scheduling-web-deployment-guide.md)
> 참조 (본 문서는 설계 사양).

---

## 1. 개요

### 1.1 무엇을 푸는가

사용자가 자연어로 반복 작업을 말하면, LLM 이 그 자리에서 도구를 호출해 **cron 표현식 + 도구 호출 목록**으로
굳혀 저장한다. 이후 실행은 LLM 없이, 세션 없이, 원래 대화가 끝난 뒤에도 계속된다.

```
"매일 7시에 뉴스를 정리해서 이메일로 보내줘"
"매주 금요일 6시에 주간 리포트 Slack으로 보내줘"
"매월 1일에 비용 분석 리포트 생성해서 팀 채널에 공유해줘"
```

### 1.2 설계 목표

1. **예측 가능한 반복** — routine 은 등록 시점에 확정된다. 매 발화마다 LLM 이 다시 계획을 세우지 않는다(D1)
2. **기존 Tool 계약 준수** — 스케줄링 도구도 `Tool` / `ToolInput` / `ToolResult` / `ToolContext` 를 그대로 쓰고,
   routine 이 실행하는 것도 평범한 등록된 도구다. 스케줄 전용 실행 경로가 따로 있지 않다
3. **세션 없는 실행을 정직하게** — 스케줄 발화는 턴이 아니다. 없는 `SessionId` 를 지어내지 않고 `ExecutionId` 를
   발급한다(§3)
4. **cron 방언을 하나로** — 저장되는 표현식은 5필드 UNIX 하나뿐이고, 다른 방언을 쓰는 백엔드가 **들어오는 쪽에서**
   번역한다(§4)
5. **중복 발화를 구조로 막는다** — 겹침과 멀티 노드 중복을 인터페이스 한 자리에서 처리한다(§6)
6. **스케줄러 교체 가능** — `TaskScheduler` 뒤에 인메모리와 Quartz 가 나란히 선다(§7)

### 1.3 용어

| 용어 | 정의 |
|------|------|
| `ScheduledTask` | 등록된 반복 작업. cron 표현식 + routine + 소유자 + 바인딩된 런타임 |
| Routine | `ScheduledTask` 안에서 **순차** 실행되는 `RoutineStep` 목록 |
| `RoutineStep` | 개별 단계 — 도구 이름, 파라미터(JSON 문자열), 재시도·타임아웃 정책 |
| `TaskScheduler` | cron 트리거만 담당하는 추상화. 무엇을 실행할지는 모른다 |
| `ScheduledTaskExecutor` | 트리거 시점에 호출되는 콜백. 구현은 `ScheduledTaskManager` 다 |
| `UnixCronExpression` | 프레임워크의 **정본** cron 방언 — 5필드, 초 없음, 일요일 `0` |
| `ScheduledExecutionGuard` | 발화 직전에 물어보는 멱등성 심 |
| `SchedulingEngine` | 위 전부를 조립한 파사드 |

---

## 2. 아키텍처

### 2.1 계층

```
사용자 발화
    │
    ▼
LLM ── schedule_task 도구 호출 ─────────────── 여기까지만 LLM 이 관여한다
    │
    ▼
ScheduleTaskTool
    ├─ ToolContext 에서 PRINCIPAL(소유자) · AGENT_RUNTIME_ID(바인딩) 추출
    ├─ ScheduledTaskPolicy.resolveBoundRuntimeId · checkSchedule  ← 호스트 정책 (§6.4)
    └─ AgentDefinitionVersion 스탬프
    │
    ▼
ScheduledTaskManager ── cron 검증 · bound runtime 해석 검증(§6.3) · 할당량 검사 · 저장 · 스케줄 등록 · 이벤트 발행
    │
    ▼
TaskScheduler (InMemory | Quartz)   ← cron 트리거만. 실행 내용을 모른다
    │  트리거 — ScheduledFire(taskId, 예정 발화 시각, slot)
    ▼
ScheduledTaskManager.executeFire(fire)
    ├─ ScheduledExecutionGuard.tryBegin(fire) ── 거부되면 그대로 건너뛴다 (§6.1)
    │
    ▼
RoutineExecutor
    ├─ boundRuntimeId 로 AgentRuntimeRegistry 에서 ToolRegistry 해결
    ├─ step 1 → step 2 → step 3  (이전 출력이 다음 입력으로)
    └─ 이력 저장 + TaskCompletedEvent / TaskFailedEvent
```

### 2.2 패키지 배치

| 패키지 | 담는 것 |
|--------|---------|
| `at.aimon.core.scheduling` (root) | 도메인 값 객체(`ScheduledTask`, `RoutineStep`, `StepResult`, `RoutineResult`, `ScheduledTaskExecutionHistory`, `ScheduledTaskId`), 실행기(`RoutineExecutor`), 비즈니스 로직(`ScheduledTaskManager`), 파사드(`SchedulingEngine` + 빌더), 멱등성 심(`ScheduledExecutionGuard` + 인메모리 구현) |
| `at.aimon.core.scheduling.cron` | `UnixCronExpression` — 정본 방언(§4) |
| `at.aimon.core.scheduling.scheduler` | `TaskScheduler`, `ScheduledTaskExecutor`, `TaskSchedulerFactory`, `InMemoryTaskScheduler` |
| `at.aimon.core.scheduling.repository` | 작업·이력 저장소 인터페이스와 인메모리 구현 |
| `at.aimon.core.scheduling.quota` | `TaskQuotaManager`, `StoredTaskQuotaManager`, `DefaultTaskQuotaManager` |
| `at.aimon.core.scheduling.event` | 이벤트 7종 + 리스너 + 발행자(§5.6) |
| `at.aimon.core.scheduling.exception` | `SchedulingException` 과 그 하위 5종 |
| `at.aimon.core.tools.scheduling` | LLM 도구 3종 + 입력 파싱 DTO |
| `aimon-scheduling-quartz` | Quartz 백엔드 + cron 번역기 + rewake · dreamer 잡(§7.4) |

> 옛 문서는 이 자리에 `ext.scheduling` / `ext.tools.scheduling` 을 적고 있었다. 그 네임스페이스는 폐기되었고
> ArchUnit(`extPackageIsDecommissioned`)이 재도입을 막는다.

### 2.3 수명

`SchedulingEngine` · `ScheduledTaskManager` · `RoutineExecutor` 는 **application-scoped** 다.
`AgentRuntime` 이 소멸해도 살아 있어야 하며, 그것이 §3.1 의 `boundRuntimeId` 가 **id 로** 저장되는 이유다 —
런타임 객체를 붙들고 있었다면 cron 재발화 때 이미 죽은 참조를 들고 있었을 것이다.
`AgentRuntimeRegistry` 는 엔진 **바깥**에서 만들어 빌더로 주입되며 엔진이 소유하지 않는다
(`@ExternallyManaged`). 전체 규칙은 [스코프 모델](../../overview/scope-model.md).

---

## 3. 세션 없는 실행 — 무엇을 대신 들고 다니는가

스케줄 발화는 **턴이 아니다**. 아무도 결과를 기다리지 않고, 붙일 전사도 없다
([용어집 §4 › 실행 단위](../../overview/glossary.md)). 그래서 세션이 하던 일을 세 값이 나눠 맡는다.

### 3.1 세 축은 서로 다른 것을 뜻한다

| 값 | 타입 | 축 | 언제 정해지나 |
|----|------|-----|--------------|
| `owner` | `Principal` | **누구의 것인가** — 권한·할당량 | 등록 시점, 고정 |
| `boundRuntimeId` | `AgentRuntimeId` | **어디서 도는가** — 도구 해결 핸들 | 등록 시점, 고정 |
| `ExecutionId` | `ExecutionId` | **어느 실행인가** — 상관관계 | **발화마다 새로** |

앞의 둘은 작업에 저장되므로 원래 세션이 끝난 한참 뒤의 재발화도 그대로 들고 간다.
`boundRuntimeId` 가 재발화 때도 resolve 되는 것은 `AgentRuntimeId` 가 `agent:<name>` 형태로 **결정론적**이기
때문이다 — `generate()` 가 존재하지 않는 이유가 이것이다.

`owner` 를 `Principal` 로 둔 것은 두 축이 실제로 다르기 때문이다(D2). 같은 사람이 여러 에이전트에 작업을
걸 수 있고, 같은 에이전트를 여러 사람이 쓸 수 있다. 소유자 검증(`getById` / `cancel` / `getHistory` /
`setEnabled`)과 할당량은 전부 `Principal` 을 받는다.

### 3.2 툴 컨텍스트에 실리는 것 · 실리지 않는 것

`RoutineExecutor` 가 한 routine 실행 전체에 공유되는 `ToolContext` 를 만든다.

| 키 | 값 | 왜 |
|----|-----|-----|
| `AGENT_RUNTIME_ID` | `task.boundRuntimeId` | 도구를 해결한 바로 그 런타임. 이것이 없으면 routine 안에서 스케줄링 도구가 아예 못 쓰인다 |
| `PRINCIPAL` | `task.owner` | 없으면 중첩 등록이 `Principal.system()` 으로 떨어져 원래 사람을 지운다 |
| `EXECUTION_ID` | `ExecutionId.generate("routine:" + taskId)` | 발화마다 새로. 같은 작업의 두 발화를 구분하는 유일한 값 |
| `FILE_STAMPS_KEY` | 빈 `ConcurrentHashMap` | 발화마다 새로. `Read` 가 기록하고 `Edit` · `Write` 가 대조하는 read stamp — 아래 "read stamp" 문단 |
| `SESSION_ID` | **없음** | 세션이 없다 |
| `INVOKING_SESSION_ID` | **없음** | 같은 이유 |

`SessionId` 를 합성하지 **않는** 것이 결정이다(D3). 합성하면 세션 단위 상태 — 스킬 승인이 대표적이다 — 가
사용자가 본 적 없고 발화마다 바뀌는 값으로 키잉된다. 식별자가 필요한 실행은 자기가 무엇인지 인정하는
식별자를 받는다.

**read stamp 는 발화 단위다.** 두 실행기가 실행마다 stamp 맵을 넣듯이 `RoutineExecutor` 도 발화마다 빈 맵을 넣는다
([`execution-environment.md`](../tool/execution-environment.md) §7). 그래서 routine 의 파일 단계는 턴이나 포크의 파일
도구와 같은 규칙을 따른다.

- `Edit` 단계는 같은 발화의 앞 단계에서 그 파일을 `Read` 했으면 동작한다. 맵이 없던 때에는 언제나 "읽지 않았다" 로
  거절됐다.
- **기존 파일을 덮어쓰는 `Write` 단계는 같은 발화의 앞 단계에서 그 파일을 `Read` 해야 한다.** 읽지 않았으면
  `Read the file before modifying it: <경로>` 로 실패하고 파일은 바뀌지 않는다. 새 파일을 만드는 `Write` 와, 같은
  발화에서 자기가 쓴 파일을 다시 고치는 단계는 `Read` 가 필요 없다.
- 맵은 그 발화의 `ToolContext` 만 참조한다. `RoutineExecutor` · `ScheduledTask` · `AgentRuntime` 은 발화보다 오래 살기
  때문에 거기에 두지 않는다. 앞 발화에서 읽은 파일은 다음 발화에서 다시 읽어야 한다. 단계가 띄운 서브에이전트는
  포크와 마찬가지로 자기 맵을 새로 받는다.

**마이그레이션.** 맵이 없던 때에 등록한 routine 가운데 `Read` 없이 기존 파일을 덮어쓰는 `Write` 단계가 있으면 그
단계가 이제 실패한다. 그 단계 앞에 같은 `file_path` 의 `Read` 단계를 넣어 다시 등록한다(작업 수정 API 가 없으므로 취소 후
재등록이다). 읽은 내용이 필요 없어도 넣어야 한다 — 검사하는 것은 읽은 뒤 파일이 바뀌지 않았다는 사실이다. 실패한 단계의
`maxRetries` 는 같은 이유로 다시 실패하므로 재시도로는 넘어가지 않는다.

### 3.3 그래서 승인 체인은 어떻게 되는가

`AGENT_RUNTIME_ID` 를 싣는 것은 무해한 추가가 아니라 **의도한 확장**이다. routine step 이 부르는 스킬이
`AgentApprovalStore` 에 닿게 된다 — 컨텍스트가 비어 있던 시절에는 닿지 못했다.

나머지는 fail-closed 로 남는다. 세션 스코프 저장소는 세션 id 가 없으므로 미스이고, 규칙 fallback 은
`ASK` 이며 `SkillTool` 은 그것을 거부로 취급한다. 실제로 넓어지는 것은 하나 —
**"이 에이전트에서 항상 허용"(`a`) 이 그 에이전트의 무인 실행에도 적용된다.** 그것이 그 승인의 문서화된
의미(에이전트 전역, TTL 없음, `/clear` 로도 안 지워짐)와 정확히 일치하므로 그대로 두었다. 좁히려면
`/revoke`, 혹은 애초에 세션 단위로 승인한다.

### 3.4 에이전트 정의 드리프트 — 핀하지 않고 기록한다

cron 작업은 자기가 만들어진 순간보다 오래 산다. 그 사이 누군가 에이전트의 `agent.md` 를 고칠 수 있다.

`ScheduleTaskTool.forAgent(...)` 로 만든 도구는 등록 시점의 `AgentDefinitionVersion` 을 작업에 찍는다.
발화 시점에 `RoutineExecutor` 가 현재 정의와 비교해서, 같으면 debug, 다르면 **warn 을 남기고 그대로
현재 정의로 실행한다.**

핀하지 않는 것이 결정이다(D4) — 소유자가 몇 주 전에 고쳐 놓은 프롬프트를 작업이 조용히 옛것으로 돌리는
쪽이 더 나쁘다. 버전은 사후에 "왜 저렇게 돌았지" 를 설명할 수 있게 하는 용도이고, 기록이 없으면
(옛 작업이거나 호출자가 안 넘겼거나) 비교를 **조용히 건너뛴다**. 비교가 실행을 실패시키는 일은 없다.

### 3.5 할당량

`TaskQuotaManager` 가 `Principal` 별 최대 작업 수를 강제한다. 기본값 10. 엔진 빌더가 기본으로 넣는 것은
`StoredTaskQuotaManager` 로, 장부를 두지 않고 저장소에 있는 그 소유자의 작업 수를 센다 — 장부는 저장소가 재시작을
넘기거나 여러 노드가 공유하는 순간 저장소와 어긋난다. `DefaultTaskQuotaManager` 는 `ConcurrentHashMap` +
`AtomicInteger` 장부이고 소유자별 커스텀 할당량을 받으며, 직접 넘길 때만 쓰인다.

---

## 4. cron 방언 — 5필드가 정본이다

### 4.1 저장되는 방언

`ScheduledTask.cronExpression` 은 예외 없이 **5필드 UNIX cron** 이다 — 분·시·일·월·요일, 초 필드 없음,
**일요일이 `0`**. `ScheduleTaskTool` 이 모델에게 광고하는 것도, 모든 `TaskScheduler` 백엔드가 받는 것도
이 방언이다. `UnixCronExpression.parse(...)` 가 유일한 관문이고, 파싱은 cron-utils 의 UNIX 정의에 위임한다.

문법에서 **의도적으로 빠진 것**: `?`, `L`, `W`, `#`, `@daily` 류, 그리고 주 끝을 넘어 감기는 범위(`FRI-MON`).
cron-utils 의 UNIX 정의가 거부하기 때문이고, 여기서 파싱되는 작업은 **어디서나** 파싱되어야 하기 때문이다.

`UnixCronExpression` 은 텍스트를 그대로 돌려주지 않고 **해석된 값**을 내놓는다 —
`daysOfWeek()` 는 요일 집합을, `isDayOfMonthRestricted()` / `isDayOfWeekRestricted()` /
`restrictsBothDayFields()` 는 두 일자 필드의 제약 여부를 준다. 번역기가 텍스트를 만지지 못하게 하려는
설계다(§4.2).

### 4.2 번역은 들어오는 쪽에서, 한 방향으로만

다른 방언을 쓰는 백엔드는 **받는 쪽에서** 번역한다. 반대 방향은 없다 — 이쪽이 무손실이기 때문이다.
Quartz 의 초 필드, `L`, `W`, `#` 에는 5필드 대응이 없지만, 5필드 표현식은 전부 Quartz 로 사상된다.

`QuartzCronTranslator` 가 세 가지를 바꾼다.

| 바뀌는 것 | 내용 |
|----------|------|
| 초 필드 | 앞에 `0` 을 붙인다. 5필드의 1분 해상도를 보존한다 |
| **요일 번호** | 저장 방언은 일요일 `0`, Quartz 는 일요일 `1`. **모든 숫자 요일이 하루씩 어긋난다** |
| 일자 필드 하나 | Quartz 는 일·요일 중 하나가 반드시 `?` 여야 한다. 제약 없는 쪽을 `?` 로 바꾼다 |

이 중 **요일만이 조용히 틀린다.** 5필드 표현식을 Quartz 에 그냥 주면 토큰이 하나 모자라 항상 거부되므로
(에러 메시지는 엉뚱하다 — `0 0 * * MON` 은 "잘못된 *월*" 로 돌아온다) 눈에 띈다. 반면 요일 번호를 안 고치면
Quartz 가 받아들이고, 매주 정확히, 영원히, **하루 잘못된 요일에** 실행된다.

번역이 텍스트가 아니라 `daysOfWeek()` 집합에서 출발하는 이유가 이것이다. 텍스트에 1 을 더하는 방식은
`0-7`(매일 — 일요일 두 번이 아니다)과 `2/3`(화요일부터 주 끝까지)에서 틀린다.

### 4.3 건널 수 없는 것은 거부한다

두 일자 필드가 **모두** 제약된 표현식 — `0 0 15 * MON` = "월요일 그리고 15일" — 은 Quartz 로 갈 수 없다.
Quartz 의 두 일자 필드 중 하나는 반드시 `?` 이므로 반쪽씩은 표현해도 합집합은 표현하지 못한다.
`QuartzCronTranslator` 는 이 경우 설명과 함께 **거부**한다.

거부가 요점이다. 그럴듯한 두 수리(한쪽을 버리거나, 둘을 AND 로 묶거나)는 **성공적으로 실행되지만
요청받은 적 없는 스케줄**을 만든다.

---

### 4.4 시간대 — 작업의 존으로 계산한다

`ScheduledTask.timezone`(IANA 존 id)은 `TaskScheduler.scheduleRecurrently(taskId, cron, zone)` 으로 스케줄러에 간다.
`"0 9 * * *"` + `Asia/Seoul` 은 노드가 어느 존에서 돌든 서울 시각 09:00 에 발화하고, 그 존의 일광 절약 규칙을 따른다.

| 경우 | 동작 |
|------|------|
| 존 없음 | 2인자 `scheduleRecurrently` — 스케줄러(JVM)의 기본 존. 예전과 같다 |
| 존 있음 | 3인자 오버로드. 인메모리는 그 존에서 다음 실행을 계산하고, Quartz 는 trigger 에 `inTimeZone` 으로 싣는다(JDBC 저장소가 함께 보관) |
| 모르는 존 id | `register` 가 `IllegalArgumentException` 으로 거부하고 아무것도 저장하지 않는다 |
| 존을 모르는 외부 스케줄러 | 3인자 default 가 JVM 존이 아닌 존을 **거부**한다. `register` 는 그것을 되돌린다 |

마지막 행이 무시가 아니라 거부인 이유는 이 절이 고친 버그가 바로 무시였기 때문이다. 한때 존은 저장되고 모델에게
광고되었지만 스케줄러에 닿지 않아, "서울 09:00" 작업이 노드의 존으로 09:00 에 돌았다. Quartz 의 misfire slot
계산(§6.1)도 trigger 의 존을 쓰므로 같은 존으로 접힌다.

## 5. 실행

### 5.1 값 객체

| 타입 | 담는 것 | 비고 |
|------|---------|------|
| `RoutineStep` | `id`(선택) · `tool` · `toolParams`(JSON 문자열) · `maxRetries`(기본 3) · `retryDelay`(기본 5초) · `timeout`(기본 5분) | `of(tool, params)` / `withRetry(...)` 편의 팩토리 |
| `StepResult` | `stepIndex` · `step` · `success` · `stdout` · `errorMessage` · `attemptCount` · `haltReason` · `outcomeUnknown` · 시작·완료 시각 | `getDuration()` · `isHalted()` · `isOutcomeUnknown()` |
| `RoutineResult` | `taskId` · `success` · `stepResults` · `errorMessage` · `interruptReason` · `haltReason` · 시작·완료 시각 | `getCompletedStepCount()` / `getTotalStepCount()` · `isCancelled()` · `isHalted()` |
| `ScheduledTaskExecutionHistory` | 위를 이력으로 접은 것 — `status` · `completedSteps` · `totalSteps` · `outcomeUnknown` | `fromRoutineResult(id, result)` · `isOutcomeUnknown()` |

`ScheduledTaskId` 는 UUID 기반 **`final class`** 다(`record` 아님 — 프로젝트 불변성 규약).
모든 값 객체가 빌더 + `Objects.requireNonNull` 이고, `ScheduledTask` 의 상태 변경은
`withEnabled()` / `withLastExecutedAt()` 처럼 새 인스턴스를 돌려준다.

### 5.2 실행 알고리즘

```
RoutineExecutor.execute(task):
    logDefinitionDrift(task)                     # §3.4 — 실행을 막지 않는다
    toolRegistry = agentRuntimeRegistry.get(task.boundRuntimeId).toolRegistry
                                                  # 미등록이면 여기서 실패 결과
    context = buildToolContext(task)              # §3.2 — routine 전체가 공유
    previousResult = null ; stepOutputs = {}

    FOR (index, step) IN task.routine:
        tool = toolRegistry.find(step.tool)        # 없으면 실패 결과로 종료
        params = step.toolParams 의 $step.N.result 를 stepOutputs[N] 로 치환
        params["_steps"] = stepOutputs
        result = executeWithRetry(tool, params, context, step)

        IF result 에 halt 표시(ToolResult.haltingRoutine):
            publish TaskHaltedEvent
            RETURN RoutineResult.halted(...)       # 재시도 없음 · 이후 단계 없음 · 실패 아님

        IF result 가 에러:
            publish StepFailedEvent
            RETURN RoutineResult.failure(...)      # 즉시 중단

        publish StepCompletedEvent
        previousResult = result.content
        stepOutputs[index] = result.content

    RETURN RoutineResult.success(...)
```

한 단계가 실패하면 **즉시 중단**한다. 이어서 도는 선택지는 없다 — 다음 단계의 입력이 방금 실패한 단계의
출력이므로, 계속 돌면 빈 값으로 도는 것과 같다.

**중단(halt)은 실패와 다른 세 번째 끝이다.** 사람 승인을 기다리는 호출처럼, 도구는 할 수 있는 일을 했지만 그 일이
이 실행 안에서는 끝나지 않는 경우가 있다. 실패로 돌려주면 재시도되어 승인 요청이 쌓이고, 성공으로 돌려주면 다음
단계가 아직 없는 결과로 돈다. 도구는 결과에 `ToolResult.haltingRoutine(reason)` 을 붙여 이를 알린다. 실행기는 그
단계를 재시도하지 않고, 이후 단계를 시작하지 않으며, 실행을 `halted` 로 끝낸다. 다음 발화는 평소대로 돈다.
에이전트 턴에서는 이 표시가 아무 일도 하지 않는다 — 모델은 `content` 를 읽고 스스로 판단한다.

**routine 단계에는 hook 이 적용되지 않는다.** 실행기는 `tool.execute` 를 직접 부른다. PreTool/PostTool hook,
권한 규칙, 승인 게이트 어느 것도 예약된 단계를 보지 못하므로, 예약된 실행에 규칙을 두려면 등록 시점에 둔다(§6.4).

### 5.3 템플릿 변수

`toolParams` 안의 `$step.{index}.result`(0-based)가 그 인덱스 단계의 출력으로 치환된다. 정규식은
`\$step\.([0-9]+)\.result` 하나뿐이다. 추가로 `_steps` 키에 지금까지의 출력 맵이 실린다.

```json
{ "tool": "send_email",
  "tool_params": "{\"to\": \"a@b.c\", \"body\": \"$step.1.result\"}" }
```

### 5.4 재시도와 타임아웃

```
executeWithRetry(step):
    attempts = 0
    WHILE attempts <= step.maxRetries:
        attempts++
        tool = 실행 시점 해결                         # 실패하면 아무것도 실행하지 않았으므로 재시도 허용
        result = timeout 을 걸고 tool.execute(input, context)
        IF result 가 halt:  RETURN halted
        IF result 성공:     RETURN success
        IF tool 이 재시도 불안전 또는 result.notRetryable():  BREAK
        step.retryDelay 만큼 대기 (취소되면 즉시 깨어남)
    RETURN failure (마지막 시도가 timeout 이면 outcomeUnknown = true)
```

**재시도는 반복해도 안전한 도구에만 한다.** 판정은 도구의 선언이다 — `SideEffectLevel.READ_ONLY` 이거나
`IdempotencyBehavior.IDEMPOTENT` 를 선언한 도구만 `maxRetries` 를 쓴다. 쓰기 도구의 기본값은 `MUTATING` +
`NON_IDEMPOTENT` 이므로, **아무것도 선언하지 않은 도구는 한 번만 실행된다.** timeout 이나 예외로 끝난 시도도 실제로는
쓰기를 끝냈을 수 있고, 다시 실행하면 같은 메시지가 두 번 나가거나 같은 변경이 두 번 적용된다. 이것은 도구의 성질이지
routine 작성자가 매번 판단할 일이 아니므로 step 설정보다 선언이 우선한다. 축의 정의는
[도구 부작용 축 분리](../tool/side-effect-axes.md) §8.2.

예외는 둘이다. 도구를 **찾기 전에** 실패한 시도(런타임 미등록, 모르는 도구 이름)는 아무것도 실행하지 않았으므로
재시도한다 — 늦게 등록되는 런타임이 그 사이에 나타날 수 있다. 반대로 도구가 실패를 `notRetryable()` 로 표시했으면
멱등 도구라도 재시도하지 않는다. 쿼터에 막힌 시도도 재시도하지 않는다(§3.5).

마지막 시도가 timeout 으로 끝난 단계는 `StepResult.isOutcomeUnknown()` 이 참이다. 실패로 기록되지만 "아무 일도
일어나지 않았다" 는 뜻은 아니다. 이력에는 `ScheduledTaskExecutionHistory.isOutcomeUnknown()` 으로 남는다 — 단계 가운데
하나라도 그러면 참이므로, 운영 화면이 `errorMessage` 문구를 파싱하지 않아도 사람이 확인해야 할 실패를 가려낼 수 있다.

`schedule_task` 스키마가 광고하는 `max_retries` 기본값은 실제 기본값(`RoutineStep.DEFAULT_MAX_RETRIES` = 3)과
같다. 한때 스키마는 `0` 이라고 적고 실행기는 `3` 을 썼다.

타임아웃은 별도 데몬 풀(`routine-timeout`, `newCachedThreadPool`)에 submit 하고 `Future.get(timeout)` 으로
기다린 뒤 초과 시 cancel 한다. `RoutineExecutor.shutdown()` 이 이 풀을 닫으며,
`SchedulingEngine.close()` 가 스케줄러 종료와 함께 호출한다.

### 5.5 이력 상태

| 조건 | Status |
|------|--------|
| 모든 단계 성공 | `SUCCESS` |
| 단계가 중단(halt)을 요청 | `HALTED` — `errorMessage` 에 도구가 준 사유 |
| 인터럽트로 멈춤 | `CANCELLED` |
| 일부만 성공 | `PARTIAL` |
| 성공한 단계 없음 | `FAILURE` |

판정 순서는 표의 위에서 아래다. `HALTED` 와 `CANCELLED` 는 실패가 아니다 — 고장 난 단계가 없다. enum 이름으로
상태를 저장하는 영속 저장소는 `HALTED` 를 받아들여야 한다.

발화 1회마다 이력 1건이 저장되고 `task.lastExecutedAt` 이 갱신된다.

### 5.6 이벤트

`ScheduledTaskEventPublisher` 는 발행과 리스너 등록을 분리한 옵저버다. 기본 구현
`SimpleScheduledTaskEventPublisher` 는 `CopyOnWriteArrayList` 기반이다.

| 이벤트 | 추가 데이터 | 발행 시점 |
|--------|-----------|----------|
| `TaskRegisteredEvent` | — | 등록 완료 |
| `TaskStartedEvent` | — | 실행 시작 |
| `TaskCompletedEvent` | `RoutineResult` | 모든 단계 성공 |
| `TaskFailedEvent` | `RoutineResult` | 단계 실패 |
| `TaskCancelledEvent` | — | 취소 |
| `TaskInterruptedEvent` | `RoutineResult`, `InterruptReason` | 실행 중 인터럽트로 멈춤 |
| `TaskHaltedEvent` | `RoutineResult`, 사유 | 단계가 중단을 요청(§5.2) — 실패 아님 |
| `TaskDisabledEvent` | 사유 | 엔진이 스스로 작업을 끔(§6.3 `DISABLE`) |
| `StepCompletedEvent` | step, stepResult, stepIndex | 개별 단계 성공 |
| `StepFailedEvent` | step, stepResult, stepIndex | 개별 단계 실패 |

전부 `ScheduledTaskEvent` 를 상속하며 `task` 와 `timestamp` 를 공통으로 갖는다. 단계 **시작** 이벤트는
없다 — 단계 시작은 그 자체로 관측 가치가 없고, 실패는 `StepFailedEvent` 가 이미 시각을 싣는다.

---

## 6. 중복 발화 — `ScheduledExecutionGuard`

`ScheduledTaskManager.executeTask` 는 무엇을 하기 전에 먼저 리스를 요청하고, 거부되면 **조용히 건너뛴다.**

```java
Optional<ExecutionLease> lease = executionGuard.tryBegin(taskId);
if (lease.isEmpty()) { return; }
try (ExecutionLease held = lease.get()) { ... }
```

막는 것은 둘이다.

| 위험 | 상황 |
|------|------|
| **겹침(overlap)** | 이전 실행이 아직 도는 중에 cron 이 다시 발화한다. 긴 routine 이면 실행이 쌓인다 |
| **멀티 인스턴스 중복** | 스케일아웃 배포에서 여러 노드의 스케줄러가 같은 cron 시각에 같은 작업을 발화한다 |

기본 구현 `InMemoryScheduledExecutionGuard` 는 `ConcurrentHashMap.newKeySet()` 위의 원자적
check-and-claim 이며 **노드 로컬**이다 — 겹침은 막고 멀티 노드 중복은 막지 못한다. 프로젝트의 멀티
인스턴스 설계 규칙대로 인터페이스 + 인메모리 기본값이므로, 공유 락/리스 저장소 기반 구현을 주입하면
리팩터링 없이 클러스터로 넘어간다.

`ScheduledExecutionGuard.ALLOW_ALL` 은 모든 요청을 허용하는 상수다 — 심이 생기기 전의 동작을 명시적으로
원하는 호출자를 위한 것이지 기본값이 아니다.

Quartz 를 쓰면 트리거 수준 dedup 이 네이티브로 붙는다. 그래도 이 심은 남는다 — **core 레벨의
심층 방어이자 주입 지점**이며, Quartz 없이 도는 배포에서는 이것이 유일한 방어다.

### 6.1 taskId 로는 겹침만 막는다 — `ScheduledFire`

taskId 로 잡은 리스는 **겹침**만 막는다. 노드 A 의 실행이 끝나 리스를 놓은 뒤 노드 B 의 같은 발화가 시차를 두고
도착하면(GC pause, CPU throttling, misfire 복구) B 는 리스를 얻어 한 번 더 실행한다. 리스를 놓지 않으면 다음 발화를
막는다. taskId 키로는 둘 중 하나를 고를 수밖에 없다.

그래서 스케줄러는 발화를 `ScheduledFire` 로 넘긴다 — taskId, **예정 발화 시각**, 그리고 그 시각을 cron 격자의
직전 점으로 접은 **slot**. 같은 회차라면 slot 은 모든 노드에서 같고, 늦게 온 발화도 같다. 반드시 **뒤로** 접는다 —
앞으로 접으면 다음 회차를 가로챈다. `getOccurrenceKey()` 는 `<taskId>@<slot epoch ms>` 이며 분산 guard 의 리스 키로
쓰라고 있는 것이다.

| 스케줄러 | 예정 발화 시각 | slot |
|---------|--------------|------|
| `InMemoryTaskScheduler` | 계산한 다음 실행 시각 | 같은 값 (격자 위) |
| `QuartzTaskScheduler` | `JobExecutionContext.getScheduledFireTime()` | job data 의 5필드 식을 trigger 의 존에서 `UnixCronExpression.previousOrSameExecution` 으로 접은 값. 식이 없는 옛 job 은 예정 시각 그대로 |

매니저는 언제나 `tryBegin(ScheduledFire)` 를 부른다. 그 default 는 `tryBegin(ScheduledTaskId)` 로 넘기므로 기존 guard 는
고치지 않아도 그대로 돈다(taskId 의미 그대로). 회차 의미가 필요한 guard 만 새 오버로드를 구현한다. `executeTask(taskId)`
로 직접 부르거나, `taskId -> ...` 람다로 배선한 스케줄러에서 온 발화는 slot 이 없다 — 람다는 executor 의 taskId 쪽
절반만 구현하기 때문이다. 외부에서 만든 스케줄러는 `ScheduledTaskManager.asExecutor()` 로 배선한다.

`InMemoryScheduledExecutionGuard` 는 여전히 taskId 로 겹침만 막는다. 한 노드에서는 그것이 위험의 전부다.

**일광 절약 시간.** 격자는 벽시계 시각이다. 시계가 뒤로 가는 날에는 같은 벽시계 시각이 두 번 오고, 스케줄러마다
어느 쪽에 발화하는지가 다르다 — cron-utils(인메모리)는 첫 번째, Quartz 는 두 번째. 그래서
`UnixCronExpression.previousOrSameExecution` 은 둘 다 격자 위로 보고(정시 발화는 자기 자신으로 접힌다), 늦은 발화는
두 순간 중 그보다 늦지 않은 쪽으로 접는다. 시계가 앞으로 가는 날에는 그 시각 자체가 없으므로 고를 것이 없다.
`QuartzTaskSchedulerTest.everyQuartzFireAcrossDaylightSavingFoldsOntoItself` 가 Quartz 의 실제 발화 시각으로 이것을
고정한다.

### 6.2 노드별 트리거의 수렴 — `reconcile()`

`register` · `cancel` · `setEnabled` 는 **그 노드의** 스케줄러에서만 트리거를 만들거나 지운다. 클러스터 공유가 아닌
스케줄러(`isClusterWide() == false`)로 여러 노드를 돌리면 노드 A 에서 만든 작업은 A 에서만 돌고, 노드 B 에서 취소한
작업은 A 에 트리거가 남아 매 발화 헛돈다.

`ScheduledTaskManager.reconcile()` 은 저장소의 활성 작업(desired)과 스케줄러가 쥔 트리거(observed,
`TaskScheduler.scheduledTaskIds()`)를 비교해 빠진 것은 걸고 남은 것은 지운다. 목록을 줄 수 없는 스케줄러
(default 는 빈 `Optional`)에서는 거는 쪽만 한다. **각 노드가 스스로 돌아야 한다** — 고치는 대상이 그 노드의
스케줄러이므로 리더 락을 걸면 선출된 노드만 수렴한다.

수렴하면 모든 노드가 모든 트리거를 쥐고, 모든 노드가 발화한다. 그 발화를 하나로 줄이는 것은 guard 다. 그래서
`SchedulingEngine` 은 **`multiNode(true)` + 노드별 스케줄러 + 다른 노드를 보는 guard 주입** 일 때만
`reconcileInterval`(기본 1분, `Duration.ZERO` 면 끔)마다 이것을 돈다. 주입된 guard 가 노드 로컬인
`InMemoryScheduledExecutionGuard` 나 `ALLOW_ALL` 이면 돌지 않는다 — 그 guard 로는 모든 노드가 모든 발화를 실행한다.

`register` · `cancel` · `setEnabled` 와 reconcile 패스는 노드 로컬 락 하나로 직렬화된다. 각각이 저장소 쓰기와 트리거
변경을 짝으로 하므로, 그 사이에 패스가 끼면 저장소 쪽 절반만 보고 움직인다 — `register` 가 걸려던 트리거를 먼저
걸거나(Quartz 는 두 번째를 거부하고 늦은 쪽이 실패한다), `cancel` 이 내렸지만 아직 지우지 않은 작업을 다시 건다.

### 6.3 bound runtime 해석 검증

`ScheduleTaskTool` 은 ToolContext 의 `AGENT_RUNTIME_ID` 를 바인딩으로 저장한다. 그 id 를 레지스트리가 해석하지
못하면 작업은 "예약됐다" 고 응답한 뒤 발화마다 `"No agent runtime registered for binding"` 으로 실패한다 — 알릴
사람이 없는 실패다. 그래서 `register` 는 레지스트리에 없는 런타임에 묶인 작업을 `UnresolvableBoundRuntimeException`
으로 거부하고, 도구는 그것을 에러로 모델에게 돌려준다. 기본으로 켜져 있으며
`SchedulingEngineBuilder.validateBoundRuntime(false)` 로 끈다.

이미 저장된 작업이 발화 시점에 해석되지 않을 때는 `UnresolvableRuntimePolicy` 가 정한다.

| 정책 | 동작 |
|------|------|
| `KEEP_SCHEDULED` (기본) | 지금처럼 발화하고 첫 단계가 실패한다. 나중에 등록된 런타임을 다음 발화가 집는다 |
| `DISABLE` | 발화를 건너뛰고, 작업을 끄고 트리거를 지우고, `TaskDisabledEvent` 를 낸다. 레코드는 남는다 |

기본이 `KEEP_SCHEDULED` 인 이유는 늦게 등록되는 런타임이다. 웹 호스트는 그 에이전트의 첫 세션이 열릴 때 런타임을
등록하므로, 재시작 직후에는 세션이 올 때까지 런타임이 없다. 첫 미스에 끄면 재시작마다 그런 작업이 모두 꺼진다.

**여러 노드 가운데 하나**(`multiNode(true)`)에서는 둘이 달라진다. 이 노드에 런타임이 없는 발화는 guard 에 묻기
**전에** 건너뛴다 — 여기서 회차를 차지하고 실패하면 그 런타임을 가진 노드가 그 회차를 잃는다. 그리고 `DISABLE` 은
빌드 시점에 거부된다. 한 노드의 미스는 그 노드의 사실인데, 끄기는 모든 노드가 읽는 저장소에 쓰기 때문이다.

### 6.4 호스트 정책 — `ScheduledTaskPolicy`

routine 단계에는 hook 이 적용되지 않으므로(§5.2) 예약된 실행을 막을 수 있는 자리는 **예약 시점** 하나다. 그 자리를
호스트에게 여는 SPI 가 `ScheduledTaskPolicy` 다. `SchedulingEngineBuilder.taskPolicy(...)` 로 한 번 넣으면 도구 3종이
`ScheduledTaskManager.getTaskPolicy()` 로 읽으므로 모든 런타임의 도구가 같은 정책을 쓴다.

| 메서드 | 호출 지점 | 기본 |
|--------|----------|------|
| `resolveBoundRuntimeId(callerRuntimeId, context)` | `schedule_task` — 바인딩 결정 | 호출한 턴의 런타임. §6.3 의 검증은 이 결과에 대해 한다 |
| `checkSchedule(candidate, context)` | `schedule_task` — 등록 직전, 저장될 모습 그대로의 작업으로 | 허용 |
| `checkAccess(task, LIST \| CANCEL, context)` | `list_scheduled_tasks` · `cancel_scheduled_task` | 허용. 소유자 검사를 **좁힐** 뿐 넓히지 않는다 |

정책이 예외를 던지거나 null 을 돌려주면 **거부**로 다룬다. 예약된 실행에는 이것 말고 다른 방어선이 없으므로 고장 난
정책이 요청을 통과시키면 안 된다.

---

## 7. 조립

### 7.1 순환 의존 — `TaskSchedulerFactory`

세 조각이 고리를 만든다. `TaskScheduler` 는 발화 시 부를 `ScheduledTaskExecutor` 가 필요하고, 그 executor 는
`ScheduledTaskManager` 이며, 매니저는 스케줄러가 필요하다.

고리를 끊는 자리가 `TaskSchedulerFactory` 다 — 완성된 스케줄러가 아니라 **executor 를 받아 스케줄러를 만드는
함수**를 넘긴다.

```
TaskSchedulerFactory.create(executor) → TaskScheduler   (start 하지 않은 상태로)
```

완성된 스케줄러를 넘기는 경로(`taskScheduler(...)`)도 남아 있지만, 그 호출자는 매니저가 존재하기 전에
executor 를 만들어야 하므로 **아직 아무도 채우지 않은 가변 참조를 닫아 쥐는** 수밖에 없다. 그리고 그것을
틀리면 조용하다 — 스케줄러는 뜨고, 트리거는 발화하고, 발화마다 null executor 에서 죽는다.
팩토리는 그 구덩이를 제대로 팔 수 있는 유일한 자리(`SchedulingEngineBuilder`)로 옮긴다. 둘 다 설정하면
`build()` 가 `IllegalStateException` 을 던진다.

### 7.2 빌더 기본값

`SchedulingEngineBuilder` 는 모든 협력자에 기본 구현을 제공하므로 `create().build()` 만으로 돈다.

| 협력자 | 미설정 시 기본값 |
|--------|----------------|
| `ScheduledTaskRepository` | `InMemoryScheduledTaskRepository` |
| `ScheduledTaskExecutionHistoryRepository` | `InMemoryScheduledTaskExecutionHistoryRepository` |
| `ScheduledTaskEventPublisher` | `SimpleScheduledTaskEventPublisher` |
| `TaskQuotaManager` | `StoredTaskQuotaManager(taskRepository, defaultMaxQuota)` — 기본 10 |
| `ScheduledExecutionGuard` | `InMemoryScheduledExecutionGuard` |
| `ScheduledTaskPolicy` | `ScheduledTaskPolicy.PERMIT_ALL` |
| `validateBoundRuntime` | `true` (§6.3) |
| `UnresolvableRuntimePolicy` | `KEEP_SCHEDULED` (§6.3) |
| `reconcileInterval` | 1분 — 단 §6.2 의 조건에서만 돈다 |
| `AgentRuntimeRegistry` | `DefaultAgentRuntimeRegistry` — 단, 실제 배포는 반드시 주입한다 |
| `TaskScheduler` | `InMemoryTaskScheduler` |

`InMemoryTaskScheduler` 는 `UnixCronExpression.nextExecution(...)` 으로 다음 시각을 계산해
`ScheduledExecutorService` 에 **한 번** 걸고, 실행이 끝나면 다음 회차를 다시 건다. 데몬 스레드
(`task-scheduler`)를 쓰고 `ConcurrentHashMap` 으로 항목을 관리한다.

### 7.3 Quartz 백엔드 — 소유와 차용

`QuartzTaskScheduler` 는 `Scheduler` 를 어디서 받았는지에 따라 종료 책임을 나눈다.

| 팩토리 | 뜻 | 종료 |
|--------|-----|------|
| `owning(scheduler, executor)` | AIMON 을 위해 만들어진 스케줄러 | AIMON 이 shutdown 한다 |
| `borrowing(scheduler, executor)` | 애플리케이션의 것(Spring 의 `quartzScheduler` 빈 등) | **건드리지 않는다** |

차용 규칙은 대칭이다 — **시작도 하지 않는다.** 애플리케이션이 스케줄러를 일부러 standby 로 두었다면
(`spring.quartz.auto-startup=false`) 그것은 의도다. 결과를 분명히 말해 둘 필요가 있다: 애플리케이션이
끝내 스케줄러를 시작하지 않으면 **AIMON cron 은 하나도 발화하지 않고, 아무것도 그렇다고 말해 주지 않는다.**
잡은 정상 등록되고 그냥 기다린다.

### 7.4 Quartz 모듈이 함께 얹은 것

`aimon-scheduling-quartz` 는 `TaskScheduler` 구현 하나만 있는 모듈이 아니다. Quartz 가 이미 있어야 하는
다른 두 기능이 여기 산다.

| 구성 요소 | 하는 일 |
|----------|---------|
| `QuartzCronTranslator` | 5필드 → 6필드 번역(§4.2) |
| `rewake/QuartzRewakeService` | `RewakeService` 의 Quartz 구현. delay 는 one-shot 트리거, cron 은 번역을 거친 트리거. 시각·횟수 양쪽으로 바운드된다 — `endAt(첫 발화 + timeout)` 과 `maxAttempts` 초과 시 잡 취소. 번역은 **어떤 상태를 건드리기 전에** 하므로 Quartz 가 표현 못 하는 표현식은 봉투 맵을 그대로 둔다 |
| `dreamer/DreamerJob` · `DreamerJobRegistrar` | 워크스페이스별 Dreamer 통합 사이클을 cron 으로 건다. 의존성은 `JobDataMap` 이 아니라 `SchedulerContext` 로 넘긴다 — JDBC job store 는 `JobDataMap` 을 직렬화하는데 LLM 클라이언트·커넥션 풀은 직렬화되지 않는다 |

### 7.5 부트스트랩 수명

`aimon-bootstrap` 의 `SchedulingSpec` 이 켜기/끄기와 스케줄러 선택(팩토리 또는 완성품)을 표현하고,
`SchedulingLifecycle` 이 엔진을 감싸 **두 번 호출해도 되는** start/stop 을 준다. 컨테이너 라이프사이클과
스택의 순차 teardown 이 각각 종료를 부르는 것이 정상 경로이므로, "이미 멈췄는가" 라는 상태를 엔진 옆
한 자리에 둔 것이다.

`stop` 이 timeout 을 받지 않는 것도 의도다 — `SchedulingEngine` 에는 `start()` 와 `close()` 밖에 없고
`close()` 는 각자 고정 시간을 기다리는 두 shutdown 이다. 넘겨받아도 무시할 수밖에 없는 인자를 받는 것은
안 받는 것보다 나쁘다.

---

## 8. 설계 결정 사항

| # | 쟁점 | 결정 |
|---|------|------|
| D1 | **routine 을 언제 만드는가** | **등록 시점에 확정**한다. 실행 시점 생성은 매 발화마다 LLM 을 부르고, 예측 불가능하며, LLM 장애가 곧 스케줄 장애가 된다. 동적 판단이 필요하면 routine 안에 판단하는 도구를 한 단계로 넣는다 |
| D2 | **소유자를 무엇으로 식별하는가** | `Principal`. 한때 `AgentRuntimeId` 였으나 두 축이 실제로 다르다 — 소유(권한·할당량)와 실행 위치(도구 해결)는 독립이다. 지금은 `owner`(Principal)와 `boundRuntimeId`(AgentRuntimeId)가 나란히 저장된다(§3.1) |
| D3 | **스케줄 실행에 SessionId 를 발급하는가** | **하지 않는다.** 합성한 세션 id 는 사용자가 본 적 없고 발화마다 바뀌므로, 세션 단위 상태가 그것에 키잉되면 조용히 무의미해진다. 대신 `ExecutionId` 를 준다(§3.2) |
| D4 | **에이전트 정의를 핀하는가** | 하지 않는다. 버전을 **기록**해 두고 발화 시 비교해 warn 을 남긴다. 소유자가 이미 고친 프롬프트를 작업이 조용히 옛것으로 돌리는 쪽이 더 나쁘다(§3.4) |
| D5 | **cron 방언을 몇 개 두는가** | 하나. 5필드 UNIX 가 정본이고 번역은 들어오는 쪽에서 한 방향으로만. 반대 방향이 무손실이 아니기 때문이다(§4.2) |
| D6 | **번역할 수 없는 표현식을 어떻게 하는가** | **거부**한다. 두 일자 필드가 모두 제약된 표현식에 대한 두 가지 "합리적" 수리는 모두 성공적으로 실행되는 잘못된 스케줄을 만든다(§4.3) |
| D7 | **도구 파라미터 저장 형식** | JSON **문자열**. 직렬화가 단순하고 임의 구조를 담으며 DB 저장이 쉽다. 타입 안전성은 도구 자신의 스키마 게이트가 실행 직전에 본다 |
| D8 | **도구를 언제 해결하는가** | 실행 시점에 `AgentRuntimeRegistry` 에서 동적으로. 등록과 발화 사이에 도구 구성이 바뀔 수 있고, 바뀌었다면 최신 것이 맞다(D4 와 같은 이유) |
| D9 | **순환 의존을 누가 끊는가** | `TaskSchedulerFactory`. 호출자가 직접 끊으면 실패가 조용하다 — 트리거는 발화하고 매번 null 에서 죽는다(§7.1) |
| D10 | **중복 발화 방어를 어디에 두는가** | Quartz 에 맡기지 않고 core 인터페이스로. Quartz 없는 배포에서는 그것이 유일한 방어이고, 있는 배포에서는 심층 방어다(§6) |
| D11 | **guard 의 키** | taskId 만으로는 겹침과 늦은 중복 중 하나만 막을 수 있으므로 발화에 slot 을 싣는다. slot 은 직전 격자점으로 **뒤로** 접는다(§6.1) |
| D12 | **어떤 단계를 재시도하는가** | 도구가 반복해도 안전하다고 선언한 것만. 기본값이 `NON_IDEMPOTENT` 이므로 미선언 쓰기 도구는 한 번만 돈다 — step 의 `maxRetries` 보다 선언이 우선한다(§5.4) |
| D13 | **승인 대기를 어떻게 표현하는가** | 실패도 성공도 아닌 `halted`. 재시도하면 요청이 쌓이고, 진행하면 빈 결과로 돈다(§5.2) |
| D14 | **예약 규칙을 어디서 두는가** | 도구를 래퍼로 바꾸지 않고 `ScheduledTaskPolicy` 로. 단계에 hook 이 없으므로 예약 시점이 유일한 자리이고, 래퍼는 base 도구가 바뀔 때마다 따라 고쳐야 한다(§6.4) |

---

## 9. 남은 것 · 하지 말 것

### 9.1 아직 코드가 없는 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| **작업 정의 영속화** | 코어의 `ScheduledTaskRepository` 구현은 인메모리 하나이고, 영속 구현은 `aimon-session-mongodb` 의 `MongoScheduledTaskRepository` 하나다. **기동 시 재등록**은 있다 — `SchedulingEngine.start()` 가 저장된 활성 작업 가운데 스케줄러가 쥐고 있지 않은 것을 다시 건다(`ScheduledTaskManager.rehydrate`). 여러 노드 가운데 하나이고 스케줄러의 트리거가 노드별이면 걸지 않는다(`SchedulingEngineBuilder.multiNode`). 쿼터는 장부 없이 저장소에서 센다(`StoredTaskQuotaManager`). 실행 이력 저장소는 여전히 인메모리뿐이다 | RDB 백엔드, 실행 이력의 영속 구현 |
| **분산 `ScheduledExecutionGuard`** | 인메모리 구현뿐 (노드 로컬). SPI 는 slot 을 받는다(§6.1) | 공유 락/리스 저장소 기반 구현. `aimon-scheduling-testkit` 의 `AbstractScheduledExecutionGuardContractTest` 를 `dedupsOccurrences() == true` 로 통과해야 한다 |
| **작업 변경 이벤트 버스** | 없음 — 노드 간 수렴은 주기 `reconcile()`(§6.2)로만 | register · cancel · enable 을 노드 간에 fan-out 하는 SPI. 놓친 이벤트 때문에 reconcile 은 그대로 둔다 |
| **작업 수정 API** | 없음 — 취소 후 재등록 | 부분 수정이 정말 필요한지부터. cron 만 바꾸는 것과 routine 을 바꾸는 것은 다른 작업에 가깝다 |
| **조건부 · 병렬 step** | routine 은 무조건 순차, 실패 시 즉시 중단 | `skipIf` 나 병렬 그룹. 실패 처리 의미를 먼저 정해야 한다 — 병렬 그룹에서 하나가 실패하면 무엇이 `PARTIAL` 인가 |

### 9.2 하지 말 것

- **`ext.scheduling` / `ext.tools.scheduling` 을 되살리지 말 것.** 폐기된 네임스페이스이며 ArchUnit 이 막는다.
- **`AgentRuntime` 소멸과 함께 스케줄링 컴포넌트를 닫지 말 것.** application-scoped 다(§2.3).
- **`AgentRuntimeId` 를 발화마다 새로 만들지 말 것.** `boundRuntimeId` 가 재발화 시점에 resolve 되는 것은
  그 id 가 결정론적이기 때문이다. `generate()` 는 존재하지 않는다.
- **스케줄 실행에 `SessionId` 를 합성하지 말 것.** `ExecutionId` 를 쓴다(D3).
- **5필드 표현식을 Quartz 에 그대로 넘기지 말 것.** 요일이 하루 어긋난 채 조용히 성공한다(§4.2).
- **번역할 수 없는 표현식을 "적당히" 고치지 말 것.** 거부한다(D6).
- **차용한 Quartz `Scheduler` 를 시작하거나 종료하지 말 것.** 그 프로세스의 다른 잡까지 멈춘다(§7.3).
- **`RoutineExecutor` 가 `AgentRuntime` 을 참조로 붙들게 하지 말 것.** id 로 저장하고 발화 때 조회한다(§2.3).

---

## 부록. 참조 파일 지도

| 위치 | 무엇을 보나 |
|------|------------|
| `at/aimon/core/scheduling/ScheduledTask.java` | 세 축(`owner` / `boundRuntimeId` / `agentDefinitionVersion`)의 javadoc — §3 의 근거 |
| `at/aimon/core/scheduling/ScheduledTaskManager.java` | 등록·취소·이력·토글의 소유자 검증과 `executeTask` 의 리스 획득 |
| `at/aimon/core/scheduling/RoutineExecutor.java` | 툴 컨텍스트 구성(§3.2)·드리프트 로깅(§3.4)·순차 실행·재시도·타임아웃 |
| `at/aimon/core/scheduling/ScheduledExecutionGuard.java` | 멱등성 심의 계약과 `ALLOW_ALL` (§6) |
| `at/aimon/core/scheduling/scheduler/ScheduledFire.java` | 발화의 slot 과 접는 방향 (§6.1) |
| `at/aimon/core/scheduling/ScheduledTaskPolicy.java`, `UnresolvableRuntimePolicy.java` | 호스트 정책과 발화 시점 미해석 정책 (§6.3, §6.4) |
| `modules/aimon-scheduling-testkit/` | 저장소 · 이력 저장소 · guard · 인터럽트 버스의 계약 테스트 (배포 대상) |
| `at/aimon/core/scheduling/SchedulingEngineBuilder.java` | 기본값 표(§7.2)와 순환 의존을 끊는 실제 코드 |
| `at/aimon/core/scheduling/cron/UnixCronExpression.java` | 정본 방언의 문법·빠진 것·요일 해석 (§4.1) |
| `at/aimon/core/scheduling/scheduler/TaskSchedulerFactory.java` | 고리를 어디서 끊어야 하는지에 대한 설명 (§7.1) |
| `at/aimon/core/scheduling/scheduler/InMemoryTaskScheduler.java` | 재스케줄 방식과 존별 다음 실행 계산 (§4.4) |
| `at/aimon/core/tools/scheduling/` | LLM 도구 3종 — 스키마, `PRINCIPAL` 해석, 버전 스탬프 |
| `modules/aimon-scheduling-quartz/cron/QuartzCronTranslator.java` | 두 방언의 차이와 거부 규칙 (§4.2, §4.3) |
| `modules/aimon-scheduling-quartz/QuartzTaskScheduler.java` | 소유/차용 구분 (§7.3) |
| `modules/aimon-scheduling-quartz/rewake/`, `dreamer/` | Quartz 위에 얹힌 다른 두 기능 (§7.4) |
| `modules/aimon-bootstrap/spec/SchedulingSpec.java`, `runtime/SchedulingLifecycle.java` | 조립과 두 번 호출 가능한 종료 (§7.5) |

## 관련 문서

- [Quartz 스케줄링 웹 배포 가이드](../../features/scheduling/quartz-scheduling-web-deployment-guide.md) — 클러스터 설정·운영
- [비동기 rewake 설계](../hook/async-rewake.md) — `QuartzRewakeService` 가 구현하는 `RewakeService` (§7.4)
- [Peer Memory 설계](../memory/peer-memory.md) — Dreamer 사이클을 cron 으로 거는 쪽 (§7.4)
- [Spring Boot Starter 설계](../integration/spring-boot-starter.md) — 차용 `Scheduler` 가 오는 자리 (§7.3)
- [스코프 모델](../../overview/scope-model.md) — application-scoped 결정의 근거 (§2.3)
- [용어집](../../overview/glossary.md) — turn / iteration / execution 구분 (§3)
- [Tool 개발 가이드](../../features/tool/tool-development-guide.md)
