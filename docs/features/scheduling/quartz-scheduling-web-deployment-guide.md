# Quartz Scheduling — Web 기반 Agent 시스템 배포 가이드

`aimon-scheduling-quartz`는 `aimon-core`가 정의한 `TaskScheduler` 인터페이스의 Quartz 기반 구현체다. Web/멀티 노드 환경에서 `SchedulingEngine`과 `SessionRouter`를 함께 운영할 때 필요한 배포·운영 사항을 정리한다.

> 본 가이드의 scope 모델은 [`docs/overview/scope-model.md`](../../overview/scope-model.md) 를 따른다 — `AgentRuntime` 는 agent-scoped 이며 세션들을 가로질러 공유된다. 그 모델이 나오게 된 배경은 [`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md) 에 있다.

설계 배경은 다음 문서를 참고한다.

- `modules/aimon-scheduling-quartz/README.md` — 모듈 자체의 사용법
- `docs/features/session/web-session-deployment-guide.md` — 멀티 노드 세션 배포 모델
- `docs/features/session/agent-session-guide.md` — 단일 노드 `LiveSession` API

---

## 1. 왜 Web에서는 Quartz인가

`aimon-core`는 기본 구현으로 `InMemoryTaskScheduler`를 제공한다. 단일 노드/개발 환경에서는 충분하지만, web agent 시스템을 멀티 노드로 운영하면 다음 문제가 발생한다.

| 상황 | InMemory | Quartz (JDBC + cluster) |
|---|---|---|
| 노드가 여러 개 | 노드별로 동일 cron이 **중복 실행** | DB 락으로 한 노드에서만 실행 |
| 노드 재시작 | 등록된 task 소실 | JDBC JobStore에서 복구 |
| 노드 장애 | task 정지 | 다른 노드가 자동 인계 |
| 누락된 실행 | 무시 | misfire 정책에 따라 처리 |

따라서 **2개 이상의 인스턴스가 로드밸런서 뒤에 떠 있는 모든 web 배포**는 `QuartzTaskScheduler`(JDBC + clustered)로 전환해야 한다.

---

## 2. 컴포넌트 수명 모델

`SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`는 **application-scoped**(long-lived)다. `AgentRuntime`는 **agent-scoped**(agent 정의 수명)이며, 그 위에서 돌던 라이브 세션이 닫혀도 살아 있어야 한다. 이 수명 분리 덕분에 cron 재발화 시점에도 `boundRuntimeId`가 registry에서 계속 resolve 된다.

전체 스코프 계층(Application / Agent / Session / Live session, 그리고 실행 단위 Execution·Turn·Iteration)은 [`docs/overview/scope-model.md`](../../overview/scope-model.md) 를 기준으로 삼는다. 아래 그림은 그중 스케줄링에 걸리는 두 칸만 그린 것이다.

```
┌──────────────────── JVM 프로세스 (application-scoped) ─────────────────┐
│                                                                        │
│  SchedulingEngine                                                      │
│   ├─ ScheduledTaskManager   ┐                                          │
│   ├─ RoutineExecutor        │  애플리케이션 시작 시 1회 생성            │
│   └─ QuartzTaskScheduler ───┘  (close는 JVM 종료 시점)                  │
│                                                                        │
│  AgentRuntimeRegistry (외부에서 주입, SchedulingEngine은                │
│   소유하지 않음 — runtime 등록/해제는 부트스트랩 코드가 관리)            │
│                                                                        │
│  ┌────── AgentRuntime (agent-scoped, long-lived) ──────────────────┐   │
│  │  ID = "agent:<name>" 또는 "agent:<name>:<discriminator>"         │   │
│  │  부트스트랩에서 agent별 1회 register.                             │   │
│  │  라이브 세션이 닫혀도 runtime 은 유지됨                            │   │
│  │  → ScheduledTask.boundRuntimeId 가 cron 재발화 시에도             │   │
│  │    registry 에서 조회 가능.                                       │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

**원칙**

1. `SchedulingEngine.close()`는 **JVM 종료** 또는 명시적 운영 작업에서만 호출한다. 세션/요청 단위에서 호출하지 않는다.
2. `AgentRuntime.close()`는 agent 제거 또는 앱 종료 시점에만 호출한다. `LiveSession.close()`에서는 호출하지 않는다 — 동일 agent 의 다른 세션이 아직 그 runtime 을 쓰고 있을 수 있다.
3. `AgentRuntimeRegistry`는 `SchedulingEngineBuilder.agentRuntimeRegistry(...)`로 **외부 주입**한다. 세션 쪽도 **동일 인스턴스**를 보아야 scheduled task 가 agent runtime 을 찾을 수 있다 — 자세한 결선은 §4.
4. Quartz scheduler 자체도 long-lived이므로 `QuartzTaskScheduler`는 애플리케이션 부트스트랩에서 한 번 생성한다.

> **조용한 실패 모드**: `agentRuntimeRegistry(...)` 를 아예 주입하지 않으면 `SchedulingEngineBuilder` 가 자체 `DefaultAgentRuntimeRegistry` 를 만들어 쓴다. 예외는 나지 않고, 부트스트랩이 등록한 runtime 이 그 registry 에는 없으므로 **cron 이 발화할 때가 되어서야** `IllegalStateException("No agent runtime registered for binding: ...")` 로 드러난다.

---

## 3. 클러스터 모드 배포 절차

### 3.1 데이터베이스 스키마

Quartz는 자체 테이블 11개를 사용한다. PostgreSQL 기준 공식 스키마는 다음에서 받는다.

- https://github.com/quartz-scheduler/quartz/tree/master/quartz-core/src/main/resources/org/quartz/impl/jdbcjobstore

`tables_postgres.sql` 등 사용 DB에 맞는 파일을 적용한다. 테이블 prefix는 `QuartzTaskSchedulerBuilder` 가 `QRTZ_` 로 고정하며 빌더 옵션으로 노출되어 있지 않다(필요하면 별도 PR로 노출).

> **권장**: Quartz 테이블은 web agent의 비즈니스 DB와 **분리된 schema**(혹은 별도 DB)에 둔다. Migration 도구의 충돌과 DBA의 검토 부담을 줄인다.

### 3.2 의존성 추가

```kotlin
// 애플리케이션 모듈 build.gradle.kts
dependencies {
    implementation(project(":aimon-core"))
    implementation(project(":aimon-scheduling-quartz"))
    runtimeOnly("org.postgresql:postgresql:42.7.1")
}
```

`aimon-scheduling-quartz`는 `implementation(project(":aimon-core"))`로 의존하며, 코어 인터페이스만 다시 노출하지 않는다(`api()` 금지 — 모듈 경계 규칙).

### 3.3 ScheduledTaskExecutor — 클러스터에서 taskId만 건너간다

Quartz JDBC JobStore는 **task 식별자(`ScheduledTaskId`)**만 직렬화한다. 람다(`Runnable`)는 노드 간에 옮길 수 없으므로, 발화한 노드는 taskId만 손에 쥔 채로 실행 로직을 스스로 재구성해야 한다. 그 재구성 지점이 `ScheduledTaskExecutor`다 — `QuartzTaskSchedulerBuilder.taskExecutor(...)` 로 넘기는 `(ScheduledTaskId) -> void` 함수 하나뿐이며, `QuartzTaskScheduler` 는 job 이 발화할 때마다 이것을 호출한다.

`SchedulingEngine`을 쓰는 경우 재구성은 이미 코어가 해 준다. task 정의는 `ScheduledTaskRepository`에 있고, `ScheduledTaskManager`가 taskId로 그것을 조회해 `RoutineExecutor`에 넘긴다. 따라서 애플리케이션이 할 일은 **`ScheduledTaskManager` 로 위임하는 executor 를 빌더에 넘기는 것**뿐이다(§4 코드 참조).

직접 cron task를 등록하는 케이스(예: `SchedulingEngine` 을 거치지 않는 운영 도구)에서만 자체 매핑을 작성한다.

```java
ScheduledTaskExecutor opsExecutor = taskId -> {
    switch (taskId.value()) {
        case "ops.daily-cleanup" -> opsService.cleanup();
        case "ops.metric-rollup" -> metricsService.rollup();
        default -> log.warn("Unknown scheduled task on this node: {}", taskId);
    }
};
```

모든 노드가 동일한 매핑을 알고 있어야 한다. 누락된 노드에서 발화하면 그 실행은 그대로 유실된다 — Quartz 는 trigger 를 소비한 것으로 처리하므로 다른 노드가 대신 실행해 주지 않는다.

---

## 4. SchedulingEngine + SessionRouter 와이어링

다음은 두 컴포넌트를 한 프로세스에서 함께 띄우는 표준 부트스트랩 코드다. 세션 쪽 빌더 옵션의 의미와 튜너블은 [`web-session-deployment-guide.md`](../session/web-session-deployment-guide.md) 를 따른다.

```java
public final class AgentSchedulingBootstrap implements AutoCloseable {

    private final AgentRuntimeRegistry agentRuntimeRegistry;
    private final QuartzTaskScheduler quartzScheduler;
    private final SchedulingEngine schedulingEngine;
    private final SessionRouter sessionRouter;

    public AgentSchedulingBootstrap(AgentSchedulingProps props,
                                    LiveSessionFactory sessionFactory,
                                    SessionRecordStore sessionRecordStore,
                                    DistributedSpis spis) {
        // 1. Registry는 스케줄링과 세션 양쪽이 공유해야 한다 — 외부에서 생성
        this.agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();

        // 2. Quartz scheduler — 클러스터 + JDBC
        //    ScheduledTaskExecutor는 빌더 시점에 lazy ref로 연결한다.
        AtomicReference<ScheduledTaskManager> managerRef = new AtomicReference<>();
        this.quartzScheduler = QuartzTaskSchedulerBuilder.create()
                .instanceName("WebAgentScheduler")
                .threadCount(props.threadCount())
                .jdbcJobStore(props.jdbcUrl(), props.jdbcDriver())
                .clustered(true)
                .clusterCheckinInterval(15_000)
                .taskExecutor(taskId -> managerRef.get().executeTask(taskId))
                .build();

        // 3. SchedulingEngine — 외부 scheduler 주입
        this.schedulingEngine = SchedulingEngineBuilder.create()
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .taskScheduler(quartzScheduler)
                .defaultMaxQuota(props.maxQuotaPerRuntime())
                .build();
        managerRef.set(schedulingEngine.getTaskManager());

        // 4. SessionRouter — 멀티 노드 SPI 4종은 전부 명시 주입
        this.sessionRouter = SessionRouter.builder()
                .sessionFactory(sessionFactory)          // runtime 은 이 팩토리가 registry 에서 얻는다
                .sessionRecordStore(sessionRecordStore)
                .mode(DeploymentMode.DISTRIBUTED)
                .nodeId(props.nodeId())
                .sessionLeaseStore(spis.leaseStore())
                .signalBus(spis.signalBus())
                .sessionInbox(spis.inbox())
                .idempotencyStore(spis.idempotency())
                .metrics(spis.metrics())
                .build();

        // 5. 시작 순서: scheduling 먼저, 그다음 session router
        schedulingEngine.start();
    }

    @PreDestroy
    @Override
    public void close() {
        // 종료 순서는 시작의 역순
        boolean drained = sessionRouter.closeGracefully(Duration.ofSeconds(20));
        if (!drained) {
            log.warn("SessionRouter forced shutdown");
        }
        schedulingEngine.close();   // <-- Quartz scheduler도 함께 shutdown
    }
}
```

**핵심 포인트**

- `AgentRuntimeRegistry`는 부트스트랩에서 한 번 생성해서 `SchedulingEngine`과 **세션 개설 경로**가 모두 같은 인스턴스를 보게 한다. `SessionRouterBuilder` 에는 registry 세터가 없다 — 라우터는 runtime 을 직접 다루지 않고, `sessionFactory(LiveSessionFactory)` 의 `ContextBuilder`(또는 `sessionOpener(LiveSessionOpener)` 가 받는 `AgentRuntimeId`)를 통해 간접적으로 닿는다. 그 경로가 별도 registry 를 쓰면 cron 재발화 시 `RoutineExecutor` 가 `boundRuntimeId` 로 agent runtime 을 찾지 못한다.
- `sessionFactory(...)` 와 `sessionOpener(...)` 는 **정확히 하나만** 설정한다. 둘 다 또는 둘 다 아님은 `build()` 가 거부한다. 호출자 도메인 속성(tenant id 등)을 세션 컨텍스트로 넘겨야 하면 opener 쪽을 쓴다.
- `taskExecutor`에 넘기는 람다는 `managerRef.get().executeTask(...)` 형태로 **lazy 참조**한다. `QuartzTaskScheduler`와 `ScheduledTaskManager`의 순환 의존을 끊기 위함이다.
- `schedulingEngine.close()` 한 번으로 내부의 Quartz scheduler까지 안전하게 내려간다(`SchedulingEngine.close()` → `taskScheduler.shutdown()` → `Scheduler.shutdown(true)` → `RoutineExecutor.shutdown()`). 별도 호출 금지.
- 종료는 **session router → scheduling engine** 순. 반대로 하면 진행 중이던 turn에서 scheduled task tool이 호출될 때 이미 내려간 엔진을 치게 된다.

> **HEAD 기준 제약**: `ScheduledTaskManager.executeTask(ScheduledTaskId)` 는 package-private(`at.aimon.core.scheduling`) 이다. 위 `taskExecutor(...)` 람다를 애플리케이션 패키지에 그대로 두면 **컴파일되지 않는다.** 코어가 가시성을 넓히거나 공개 브리지를 노출하기 전까지의 우회는, 그 람다만 `at.aimon.core.scheduling` 패키지에 둔 얇은 브리지 클래스로 감싸는 것이다(이 프로젝트에는 `module-info.java` 가 없으므로 클래스패스 기준으로 동작한다). `InMemoryTaskScheduler` 경로는 `SchedulingEngineBuilder` 가 같은 패키지 안에서 람다를 만들기 때문에 이 문제가 없다.

---

## 5. Agent-scoped AgentRuntime과 scheduled task

`AgentRuntime`이 **agent-scoped**이므로 `ScheduledTask.boundRuntimeId`(`"agent:<name>"`)는 세션이 끝난 뒤에도 registry에 계속 유효하다. `RoutineExecutor`는 실행 시점에 `agentRuntimeRegistry.get(boundRuntimeId)`로 runtime을 조회하며, 부트스트랩이 등록한 runtime이 아직 destroy되지 않았다면 성공한다.

| 시점 | 세션 활성 여부 | Scheduled task |
|---|---|---|
| 사용자 요청 | 라이브 세션 open, runtime은 이미 registry에 있음 | tool로 등록됨 (`schedule_task`) |
| 사용자 idle | 라이브 세션 close, **runtime은 registry에 유지** | DB에 영속, cron에 따라 발화 |
| Cron 발화 | 라이브 세션 없어도 무관 | `RoutineExecutor`가 runtime 조회 성공 → 정상 실행 |
| 사용자 재요청 | 새 라이브 세션 open, 동일 runtime 재사용 | 기존 task는 owner ID로 계속 매핑 |

**의미**

1. `ScheduledTask.boundRuntimeId`는 `"agent:<name>"` 형식의 결정론적 ID이므로, 인스턴스 재시작이나 세션 종료 후에도 Quartz JDBC JobStore에 저장된 값이 그대로 유효하다. 실행마다 새로 발급되는 id 였다면 재발화가 resolve 되지 않았을 것이다.
2. Cron 발화 시 사용자가 오프라인이어도 task는 실행된다. UI에 결과를 push하려면 `ScheduledTaskEventListener`로 `TaskCompletedEvent`/`TaskFailedEvent`를 받아 `signalBus`로 fan-out하는 별도 어댑터가 필요하다(레퍼런스 구현 미포함, 애플리케이션 책임).

---

## 6. 시간 동기화·인증·운영

### 6.1 NTP 필수

Quartz 클러스터는 **노드 간 시계가 5초 이상 차이나면** misfire/leader-election 동작이 망가진다. 모든 노드에 `chrony` 또는 `systemd-timesyncd`를 켠다. 컨테이너 환경에서는 호스트 시계를 신뢰할 수 있는지 확인.

### 6.2 DataSource 공유

Spring/Helidon 등 프레임워크의 DataSource 풀을 재사용하려면 `dataSourceClass(String)`를 사용한다.

```java
QuartzTaskSchedulerBuilder.create()
    .taskExecutor(taskExecutor)
    .jdbcJobStore(jdbcUrl, jdbcDriver)
    .dataSourceClass("org.example.QuartzDataSourceProvider")  // 자체 ConnectionProvider
    .clustered(true)
    .build();
```

지정한 클래스는 `org.quartz.utils.ConnectionProvider`를 구현해야 한다. 이렇게 해야 connection 풀이 두 벌이 되지 않는다(기본 동작은 Quartz가 `maxConnections=10` 짜리 자체 풀을 만든다).

그 기본 풀은 **HikariCP** 이고 `aimon-scheduling-quartz` 가 함께 싣는다. Quartz 2.5 가 c3p0 와 HikariCP 를
`provided` 로 내리면서 스케줄러에 풀이 딸려 오지 않게 되었으므로, JDBC job store 를 쓰는 쪽이 풀을 대야 한다 —
이 모듈이 그 몫을 한다. 다만 **JDBC 드라이버는 여전히 사용하는 쪽이 클래스패스에 올려야 한다.** HikariCP 는
드라이버 클래스를 풀 구성 시점에 로드하므로, 없으면 첫 DB 접근이 아니라 `build()` 에서 실패한다.

### 6.3 권장 빌더 값

| 옵션 | 기본값 | 권장값 | 이유 |
|---|---|---|---|
| `instanceName` | JVM 내 유일하게 파생 (`AimonScheduler-<n>`) | 환경별 고유 (`web-prod`, `web-stg`) — **클러스터에서는 필수** | 클러스터의 노드는 "같은 이름을 공유하는 스케줄러들"이므로, 파생 이름이면 노드마다 1노드 클러스터가 된다. `clustered(true)` 는 명시하지 않으면 `build()` 에서 거부한다 |
| `instanceId` | `AUTO` | `AUTO` 유지 | 노드별 자동 부여 |
| `threadCount` | 가용 CPU 코어 수 | CPU 코어 × 2 | 대다수 task가 LLM I/O 대기 |
| `clusterCheckinInterval` | 20_000ms | 15_000ms | 기본값 대비 failover 속도 향상 |
| `defaultMaxQuota` (`SchedulingEngineBuilder`) | 10 | agent runtime당 **10–20** | LLM 비용·과도한 cron 등록 방어 |
| `executionGuard` (`SchedulingEngineBuilder`) | `InMemoryScheduledExecutionGuard` (노드 로컬) | 분산 구현 | 노드 간 중복 발화에 대한 코어 레벨 2차 방어선 |
| `interruptBus` (`SchedulingEngineBuilder`) | `ScheduledTaskInterruptBus.LOCAL_ONLY` (노드 로컬) | 분산 구현 | 취소가 **발화한 노드**의 실행까지 멈추게 한다 |

`defaultMaxQuota`는 **agent runtime 단위** 상한이다(`TaskQuotaManager` 를 직접 주입하면 무시된다).

`executionGuard`는 발화 직전에 실행 권한을 묻는 자리다. 기본값은 이 노드 안에서의 겹침만 막으므로, 클러스터에서는
공유 저장소를 보는 구현을 넘겨야 §7의 "동일 task가 두 노드에서 동시에 실행됐다" 항목이 말하는 2차 방어선이 생긴다.
스타터에서는 `ScheduledExecutionGuard` 빈 하나로 배선된다(`SchedulingSpec.withExecutionGuard`).

`interruptBus`는 반대 방향 — 이미 돌고 있는 것을 **멈추는** 자리다. `RoutineExecutor` 의 in-flight 레지스트리는
노드 로컬이므로, 버스가 없으면 `cancel` 은 취소를 입력한 노드의 실행만 끊는다. 클러스터에서 그 노드는 대개 cron 이
발화한 노드가 아니고, 그러면 저쪽 실행은 방금 삭제된 태스크를 위해 남은 스텝을 끝까지 돌린다. 스타터를 쓰면
`ScheduledTaskInterruptBus` 빈 하나로 배선되고(`SchedulingSpec.withInterruptBus`), 코어가 싣는 구현은 노드 하나
(`LOCAL_ONLY`)와 JVM 하나(`InMemoryScheduledTaskInterruptBus`)까지다 — 클러스터용은 애플리케이션이 쓴다.
설계 근거는 [`interrupt.md` §12.7](../../design/agent-execution/interrupt.md).

### 6.4 모니터링

`SchedulingEngine.addEventListener(...)`로 받은 이벤트를 Micrometer로 보낸다. 실행 결과 수치는 이벤트가 직접 노출하지 않고 `RoutineResult`(`getResult()`)에 실려 온다.

```java
schedulingEngine.addEventListener(new ScheduledTaskEventListener() {
    @Override public void onTaskRegistered(TaskRegisteredEvent e) {
        registered.increment(); }
    @Override public void onTaskCompleted(TaskCompletedEvent e) {
        completed.increment();
        durations.record(e.getResult().getDuration()); }
    @Override public void onTaskFailed(TaskFailedEvent e) {
        failed.increment();
        log.warn("Scheduled task failed: {} — {}", e.getTask().getId(),
                 e.getResult().getErrorMessage().orElse("<no message>")); }
});
```

`ScheduledTaskEventListener`의 모든 메서드는 default 빈 구현이므로 관심 있는 이벤트만 override하면 된다. 스텝 단위 관측이 필요하면 `onStepCompleted`/`onStepFailed`도 있다.

또한 Quartz 자체 JMX MBean(`org.quartz.scheduler.jmx.export=true` Properties)을 켜면 `MisfiresPerSecond`, `JobsExecuted`, `RunningSince` 등을 외부에서 관찰할 수 있다. 단, `QuartzTaskSchedulerBuilder`는 이 property를 노출하지 않으므로 `QuartzTaskScheduler(Scheduler, ScheduledTaskExecutor)` 생성자에 직접 구성한 `Scheduler`를 넘겨야 한다.

---

## 7. 운영 플레이북

### "동일 task가 두 노드에서 동시에 실행됐다"

- `clustered(true)`가 빠져 있거나 두 노드가 **다른 DB schema**를 보고 있다.  
  Properties 덤프에서 `org.quartz.jobStore.isClustered=true`와 `org.quartz.dataSource.aimonDS.URL` 값을 비교 확인.
- NTP가 깨져 있으면 leader election이 흔들린다 — 호스트 `timedatectl status`로 검증.
- 코어 쪽 2차 방어선인 `ScheduledExecutionGuard`를 **주입했는지** 확인한다. `SchedulingEngineBuilder`의 기본값은 노드 로컬 `InMemoryScheduledExecutionGuard`이며, 이것은 같은 노드에서의 중복/겹침만 막고 노드 간 중복은 막지 못한다. 즉 아무것도 주입하지 않았다면 클러스터 단일 실행 보장은 Quartz의 DB 락이 전부다. 공유 락/리스 저장소로 뒷받침되는 분산 guard 를 `.executionGuard(...)` 로 넘기면 그 위에 코어 레벨 방어선이 하나 더 생긴다.

### "Cron 등록은 됐는데 실행되지 않는다"

1. `SchedulingEngine.start()` 호출 누락 — DB에는 들어가지만 trigger 이벤트가 dispatch되지 않는다. `QuartzTaskScheduler.scheduleRecurrently`는 스케줄러가 start 상태가 아니면 `TaskSchedulerException("Scheduler is not running")`을 던진다.
2. JDBC JobStore에서 task가 `disabled`로 저장됐는지 확인 (`task.isEnabled()` false면 발화해도 실행 직전에 skip된다).
3. 노드의 `threadCount`가 0/1이고 다른 task가 점유 중인 경우 — 스레드풀을 키운다.
4. `agentRuntimeRegistry` 를 공유하지 않아 `No agent runtime registered for binding: agent:<name>` 이 나는 경우 — §2의 "조용한 실패 모드" 참조.

### "노드 재시작 후 일시적으로 동일 task가 두 번 실행됐다"

- Misfire 정책이 `FireAndProceed`이므로 의도한 동작이다(`QuartzTaskScheduler`가 cron trigger에 `withMisfireHandlingInstructionFireAndProceed()`를 건다). 멱등이 아닌 task는 `ScheduledTask` 핸들러 내부에서 idempotency key를 검사하도록 작성한다(예: 세션 쪽 `IdempotencyStore` 재사용).

### "JDBC JobStore에 잠금(`QRTZ_LOCKS`)이 풀리지 않는다"

- 노드가 `kill -9`로 죽으면 `clusterCheckinInterval` 후 다른 노드가 인계한다. 5분 이상 풀리지 않으면 해당 row를 수동으로 삭제하기 전에 **모든 노드의 `instanceName`이 동일한지** 먼저 확인 — 다르면 다른 클러스터로 인식되어 leader가 인계되지 않는다.

---

## 8. 단일 노드 → 클러스터 마이그레이션 체크리스트

기존에 `InMemoryTaskScheduler`로 운영하던 web 시스템을 클러스터로 전환할 때 점검 항목.

- [ ] DB에 Quartz 스키마 적용
- [ ] `aimon-scheduling-quartz` 의존성 추가, JDBC 드라이버 `runtimeOnly`
- [ ] `SchedulingEngineBuilder.taskScheduler(quartzScheduler)`로 교체
- [ ] `clustered(true)` + `jdbcJobStore(...)` 설정
- [ ] 모든 노드의 `instanceName` 동일, `instanceId=AUTO`
- [ ] `AgentRuntimeRegistry`를 세션 개설 경로(`LiveSessionFactory` / `LiveSessionOpener`)와 공유
- [ ] `SessionRouter`를 `DeploymentMode.DISTRIBUTED`로 올리고 SPI 4종 + `nodeId` 명시 주입
- [ ] `interruptBus`에 분산 구현 주입 — 없으면 취소가 발화 노드의 실행을 멈추지 못한다
- [ ] `executionGuard`에 분산 구현 주입 — 코어 레벨 중복 발화 방어선
- [ ] NTP 활성화
- [ ] `closeGracefully` → `schedulingEngine.close()` 종료 순서
- [ ] Quartz 테이블에 대한 백업·모니터링 구성
- [ ] 단일 노드에서 cron 동작 확인 → 2노드로 스케일아웃 시 중복 실행 없음 검증

---

## 9. 참고

- `modules/aimon-scheduling-quartz/README.md`
- `docs/overview/scope-model.md`
- `docs/features/session/web-session-deployment-guide.md`
- `modules/aimon-core/src/main/java/at/aimon/core/scheduling/SchedulingEngineBuilder.java`
- `modules/aimon-session-routing/src/main/java/at/aimon/session/routing/builder/SessionRouterBuilder.java`
- `modules/aimon-scheduling-quartz/src/main/java/at/aimon/scheduling/quartz/QuartzTaskSchedulerBuilder.java`
- [Quartz Scheduler Configuration Reference](http://www.quartz-scheduler.org/documentation/quartz-2.3.0/configuration/)
