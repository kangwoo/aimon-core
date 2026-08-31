# 스케줄 실행 — 요청 시점에 굳힌 routine 을 세션 없이 다시 돌린다

> Status: **IMPLEMENTED** — `at.aimon.core.scheduling`(main 41 / test 16) + `at.aimon.core.tools.scheduling`(main 4 /
> test 3) + `aimon-scheduling-quartz`(main 9 / test 9). **작업 정의를 영속하는 저장소 구현은 아직 없다** — 둘 다
> 인메모리다. `timezone` 은 저장·광고되지만 어느 스케줄러에도 전달되지 않는다. 둘 다 §9.1.
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
    └─ AgentDefinitionVersion 스탬프
    │
    ▼
ScheduledTaskManager ── cron 검증 · 할당량 검사 · 저장 · 스케줄 등록 · 이벤트 발행
    │
    ▼
TaskScheduler (InMemory | Quartz)   ← cron 트리거만. 실행 내용을 모른다
    │  트리거
    ▼
ScheduledTaskManager.executeTask(taskId)
    ├─ ScheduledExecutionGuard.tryBegin(taskId) ── 거부되면 그대로 건너뛴다
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
| `at.aimon.core.scheduling.quota` | `TaskQuotaManager`, `DefaultTaskQuotaManager` |
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
| `SESSION_ID` | **없음** | 세션이 없다 |
| `INVOKING_SESSION_ID` | **없음** | 같은 이유 |

`SessionId` 를 합성하지 **않는** 것이 결정이다(D3). 합성하면 세션 단위 상태 — 스킬 승인이 대표적이다 — 가
사용자가 본 적 없고 발화마다 바뀌는 값으로 키잉된다. 식별자가 필요한 실행은 자기가 무엇인지 인정하는
식별자를 받는다.

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

`TaskQuotaManager` 가 `Principal` 별 최대 작업 수를 강제한다. 기본값 10, `DefaultTaskQuotaManager` 는
`ConcurrentHashMap` + `AtomicInteger` 기반이며 소유자별 커스텀 할당량을 받는다. 등록 시 증가하고 취소 시
회수한다.

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

## 5. 실행

### 5.1 값 객체

| 타입 | 담는 것 | 비고 |
|------|---------|------|
| `RoutineStep` | `id`(선택) · `tool` · `toolParams`(JSON 문자열) · `maxRetries`(기본 3) · `retryDelay`(기본 5초) · `timeout`(기본 5분) | `of(tool, params)` / `withRetry(...)` 편의 팩토리 |
| `StepResult` | `stepIndex` · `step` · `success` · `stdout` · `errorMessage` · `attemptCount` · 시작·완료 시각 | `getDuration()` |
| `RoutineResult` | `taskId` · `success` · `stepResults` · `errorMessage` · 시작·완료 시각 | `getCompletedStepCount()` / `getTotalStepCount()` |
| `ScheduledTaskExecutionHistory` | 위를 이력으로 접은 것 — `status` · `completedSteps` · `totalSteps` | `fromRoutineResult(id, result)` |

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

### 5.3 템플릿 변수

`toolParams` 안의 `$step.{index}.result`(0-based)가 그 인덱스 단계의 출력으로 치환된다. 정규식은
`\$step\.([0-9]+)\.result` 하나뿐이다. 추가로 `_steps` 키에 지금까지의 출력 맵이 실린다.

```json
{ "tool": "send_email",
  "tool_params": "{\"to\": \"a@b.c\", \"body\": \"$step.1.result\"}" }
```

### 5.4 재시도와 타임아웃

```
executeWithRetry(tool, input, context, step):
    attempts = 0
    WHILE attempts <= step.maxRetries:
        result = timeout 을 걸고 tool.execute(input, context)
        IF result 성공: RETURN (result, attempts + 1)
        IF attempts < step.maxRetries: step.retryDelay 만큼 대기
        attempts++
    RETURN (마지막 result, attempts)
```

타임아웃은 별도 데몬 풀(`routine-timeout`, `newCachedThreadPool`)에 submit 하고 `Future.get(timeout)` 으로
기다린 뒤 초과 시 cancel 한다. `RoutineExecutor.shutdown()` 이 이 풀을 닫으며,
`SchedulingEngine.close()` 가 스케줄러 종료와 함께 호출한다.

### 5.5 이력 상태

| 조건 | Status |
|------|--------|
| 모든 단계 성공 | `SUCCESS` |
| 일부만 성공 | `PARTIAL` |
| 성공한 단계 없음 | `FAILURE` |

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
| `TaskQuotaManager` | `DefaultTaskQuotaManager(defaultMaxQuota)` — 기본 10 |
| `ScheduledExecutionGuard` | `InMemoryScheduledExecutionGuard` |
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

---

## 9. 남은 것 · 하지 말 것

### 9.1 아직 코드가 없는 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| **작업 정의 영속화** | `ScheduledTaskRepository` 구현이 인메모리 하나뿐이다. Quartz JDBC job store 를 써도 **트리거만** 남고 작업 정의는 재시작과 함께 사라진다 | RDB 백엔드. 여기에 더해 **기동 시 재등록** 경로 — `SchedulingEngine.start()` 는 스케줄러만 시작하고 저장된 활성 작업을 다시 걸지 않는다 |
| **timezone** | `ScheduledTask.timezone` 에 저장되고 `schedule_task` 스키마가 IANA 존을 광고하지만, `TaskScheduler.scheduleRecurrently(taskId, cronExpression)` 이 표현식만 받으므로 **어느 스케줄러에도 전달되지 않는다.** 인메모리 스케줄러는 시스템 기본 존으로 계산한다 | 시그니처에 존을 태우거나 표현식과 존을 한 값으로 묶는다. 필드를 지우는 선택지는 없다 — 모델에게 이미 광고했다 |
| **분산 `ScheduledExecutionGuard`** | 인메모리 구현뿐 (노드 로컬) | 공유 락/리스 저장소 기반 구현. 심은 이미 있으므로 구현체 하나를 주입하면 된다(§6) |
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
| `at/aimon/core/scheduling/SchedulingEngineBuilder.java` | 기본값 표(§7.2)와 순환 의존을 끊는 실제 코드 |
| `at/aimon/core/scheduling/cron/UnixCronExpression.java` | 정본 방언의 문법·빠진 것·요일 해석 (§4.1) |
| `at/aimon/core/scheduling/scheduler/TaskSchedulerFactory.java` | 고리를 어디서 끊어야 하는지에 대한 설명 (§7.1) |
| `at/aimon/core/scheduling/scheduler/InMemoryTaskScheduler.java` | 재스케줄 방식과 시스템 기본 존 계산 (§9.1 timezone) |
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
