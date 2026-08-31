# AIMON Scheduling Quartz

Quartz 기반의 `TaskScheduler` 구현체로, 분산 환경에서 cron 스케줄링을 지원합니다.

## 특징

- **Quartz Scheduler 통합**: 검증된 엔터프라이즈급 스케줄링 라이브러리
- **클러스터링 지원**: JDBC JobStore를 통한 분산 환경 지원
- **TaskProvider**: 클러스터 환경에서 안전한 task 해결
- **Job 중복 실행 방지**: DB 락을 통해 동일 Job이 한 노드에서만 실행
- **Failover**: 노드 장애 시 다른 노드가 작업 인계
- **Misfire 처리**: 누락된 실행 자동 처리

## 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":aimon-scheduling-quartz"))
}
```

## 사용 방법

### 기본 사용 (RAM 기반)

단일 노드 환경에서 메모리 기반 JobStore를 사용합니다.

```java
// 스케줄러 생성 및 시작
QuartzTaskScheduler scheduler = new QuartzTaskScheduler();
scheduler.start();

// 매 분마다 실행되는 작업 등록
scheduler.scheduleRecurrently("my-task", "* * * * *", () -> {
    System.out.println("Task executed!");
});

// 작업 존재 여부 확인
boolean exists = scheduler.exists("my-task");

// 작업 취소
scheduler.unschedule("my-task");

// 모든 작업 제거
scheduler.clear();

// 스케줄러 종료
scheduler.shutdown();
```

### Builder를 이용한 커스텀 설정

```java
QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create()
    .instanceName("MyScheduler")
    .threadCount(4)
    .threadNamePrefix("my-scheduler")
    .build();

scheduler.start();
```

### 클러스터 모드 (JDBC 기반)

분산 환경에서 여러 노드가 동일한 스케줄을 공유합니다.

**중요**: 클러스터 환경에서는 `TaskProvider`를 사용하여 모든 노드에서 동일한 task를 해결할 수 있도록 해야 합니다.

```java
// TaskProvider 정의 - 모든 노드에서 동일하게 task를 반환
TaskProvider taskProvider = taskId -> switch (taskId) {
    case "daily-backup" -> () -> backupService.runBackup();
    case "cleanup" -> () -> cleanupService.cleanup();
    case "report" -> () -> reportService.generateReport();
    default -> null;
};

// 클러스터 스케줄러 생성
QuartzTaskScheduler scheduler = QuartzTaskSchedulerBuilder.create()
    .instanceName("ClusteredScheduler")
    .jdbcJobStore("jdbc:postgresql://localhost:5432/quartz", "org.postgresql.Driver")
    .clustered(true)
    .clusterCheckinInterval(15000)
    .taskProvider(taskProvider)  // 클러스터에서 필수
    .build();

scheduler.start();

// 스케줄 등록 (한 노드에서만 호출해도 DB에 저장됨)
scheduler.scheduleRecurrently("daily-backup", "0 2 * * *", () -> {});
```

### TaskProvider 동작 방식

```
Job 실행 시:
  1. TaskProvider.getTask(taskId) 호출
  2. TaskProvider가 null 반환하면 → 로컬 레지스트리에서 조회
  3. task 실행
```

| 환경 | TaskProvider | 동작 |
|------|-------------|------|
| 단일 노드 | 불필요 | 로컬 레지스트리만 사용 |
| 클러스터 | **필수** | 모든 노드에서 동일한 task 반환 |

### SchedulingEngine과 통합

aimon-core의 `SchedulingEngine`과 함께 사용할 수 있습니다.

```java
// Quartz 스케줄러 생성
QuartzTaskScheduler quartzScheduler = QuartzTaskSchedulerBuilder.create()
    .taskProvider(taskProvider)
    .clustered(true)
    .jdbcJobStore("jdbc:postgresql://localhost/quartz", "org.postgresql.Driver")
    .build();

// SchedulingEngine에 주입
SchedulingEngine engine = SchedulingEngineBuilder.create()
    .toolRegistry(toolRegistry)
    .taskScheduler(quartzScheduler)  // Quartz 스케줄러 사용
    .build();

engine.start();
```

## Cron 표현식

**스케줄은 프레임워크의 5필드 UNIX 방언으로 쓴다** — Quartz 의 6필드 방언이 아니다. `ScheduledTask` 에 실리는
표현식과 이 스케줄러가 받는 표현식은 같은 것이므로, 백엔드를 무엇으로 깔았든 같은 문자열이 같은 스케줄을 뜻한다.

```
분 시 일 월 요일
```

| 표현식 | 설명 |
|--------|------|
| `* * * * *` | 매 분 실행 |
| `0 * * * *` | 매 시 정각에 실행 |
| `0 9 * * *` | 매일 오전 9시에 실행 |
| `0 9 * * MON-FRI` | 평일 오전 9시에 실행 |
| `*/30 * * * *` | 30분마다 실행 |

이 어댑터가 표현식을 Quartz 방언으로 **번역**한다(`QuartzCronTranslator`) — 초 필드 `0` 을 앞에 붙이고, 요일
번호를 Quartz 의 1=일요일 기준으로 다시 매기고, 두 요일 필드 중 하나를 `?` 로 만든다. 로그에는 원문과 번역문이
함께 남는다.

두 가지 결과가 따라온다.

- **가장 짧은 주기는 1분이다.** 5필드 방언에는 초 필드가 없다. 초 단위 스케줄이 필요하면 cron 이 아니라
  다른 수단을 쓴다.
- **"매달 15일 **그리고** 매주 월요일" 은 표현할 수 없다.** UNIX cron 은 두 요일 필드를 OR 로 읽지만 Quartz 는
  둘 중 하나가 반드시 `?` 여야 한다. 이런 표현식(`0 0 15 * MON`)은 잘못 번역되는 대신 `InvalidCronExpressionException`
  으로 거부되므로, 두 개의 task 로 나눠 등록한다.

## 클러스터링 요구사항

JDBC 클러스터 모드를 사용하려면:

1. **데이터베이스 테이블 생성**: Quartz 테이블 스키마 필요
   - [Quartz 공식 스키마](https://github.com/quartz-scheduler/quartz/tree/master/quartz-core/src/main/resources/org/quartz/impl/jdbcjobstore)

2. **시간 동기화**: 모든 노드의 시스템 시간이 동기화되어야 함 (NTP 권장)

3. **TaskProvider 설정**: 모든 노드에서 동일한 TaskProvider 구성

4. **JDBC 드라이버**: 사용하는 DB의 JDBC 드라이버 의존성 추가

```kotlin
// PostgreSQL 예시
dependencies {
    runtimeOnly("org.postgresql:postgresql:42.7.1")
}
```

## Builder 옵션

| 메서드 | 설명 | 기본값 |
|--------|------|--------|
| `instanceName(String)` | 스케줄러 인스턴스 이름 | `AimonScheduler-<n>` (JVM 내 유일하게 파생) |
| `instanceId(String)` | 인스턴스 ID (`AUTO`로 자동 생성) | `AUTO` |
| `threadCount(int)` | 스레드 풀 크기 | CPU 코어 수 |
| `threadNamePrefix(String)` | 스레드 이름 접두사 | `aimon-quartz` |
| `daemonThreads(boolean)` | 스케줄러/워커 스레드를 데몬으로 | `true` |
| `jdbcJobStore(url, driver)` | JDBC JobStore 설정 | - |
| `dataSourceClass(String)` | 커스텀 DataSource 클래스 | - |
| `clustered(boolean)` | 클러스터링 활성화 | `false` |
| `clusterCheckinInterval(long)` | 클러스터 체크인 간격 (ms) | `20000` |
| `taskProvider(TaskProvider)` | Task 해결 제공자 | - |

### `instanceName` — 언제 직접 지정해야 하는가

Quartz 는 스케줄러를 **JVM 전역 레지스트리**에서 이름으로 찾는다. 이미 등록된 이름으로 다시 빌드하면 새로
만드는 대신 **기존 인스턴스를 그대로 돌려준다** — 두 번째 빌드의 스레드 풀·job store·task executor 는 조용히
버려지고, 둘 중 아무 쪽의 `shutdown()` 이나 양쪽을 함께 멈춘다. 그래서 기본값은 고정 문자열이 아니라 JVM 안에서
유일한 파생 이름이며, **한 프로세스에 여러 스케줄러(예: 애플리케이션 컨텍스트 2개)를 띄워도 서로 간섭하지 않는다.**

클러스터링은 이 요구를 뒤집는다. Quartz 에서 **한 클러스터란 같은 DB 위에서 같은 이름을 공유하는 스케줄러들**
이므로, 노드마다 다른 이름이 붙으면 각 노드가 1노드짜리 클러스터가 되어 모든 트리거가 모든 노드에서 발화한다.
따라서 `clustered(true)` 는 명시적 `instanceName` 을 **요구**하며, 없으면 `build()` 가 `IllegalStateException`
으로 실패한다 (조용히 파생 이름을 쓰지 않는다).

### 데몬 스레드

`daemonThreads` 는 기본 `true` 다 — 아무도 `shutdown()` 을 부르지 않은 스케줄러가 JVM 종료를 막는 이유가 되면
안 되기 때문이다. 프로세스가 스케줄러를 명시적으로 멈출 때까지 살아 있어야 한다면 `daemonThreads(false)` 로
끈다.

## 참고

- [Quartz Scheduler 공식 문서](http://www.quartz-scheduler.org/documentation/)
- [aimon-core TaskScheduler 인터페이스](../aimon-core/src/main/java/at/aimon/core/scheduling/scheduler/TaskScheduler.java)
