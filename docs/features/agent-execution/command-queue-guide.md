# Command Queue Guide

> 에이전트 실행 중 도착한 사용자 입력을 버리지 않고 다음 반복(iteration) 경계에서 주입하기 위한 **mid-turn command queue** 가이드.

이 문서는 `at.aimon.core.agent.queue` 패키지의 공개 API, 사용 패턴, 관측(observability), 그리고 멀티 인스턴스 환경에서 저장소를 교체하는 방법을 설명합니다.

## 목차

1. [개요](#개요)
2. [아키텍처](#아키텍처)
3. [공개 API 표면](#공개-api-표면)
4. [사용 패턴](#사용-패턴)
5. [관측 및 메트릭](#관측-및-메트릭)
6. [멀티 인스턴스 — Repository 교체](#멀티-인스턴스--repository-교체)
7. [테스트 레시피](#테스트-레시피)
8. [디자인 원칙](#디자인-원칙)

---

## 개요

참조 구현 REPL은 에이전트가 응답하는 동안 사용자가 추가로 타이핑한 입력을 **큐에 버퍼링**했다가 ReAct 루프의 반복 경계에서 시스템-리마인더로 주입합니다. AIMON도 동일 동작을 제공하며, 큐 계층은 다음 요구사항을 만족합니다.

- 입력을 **드랍하지 않고** 다음 iteration 경계에서 소비.
- **메인 에이전트 / 서브 에이전트의 큐를 교차 소비하지 않음** — 실행 컨텍스트 ID로 격리.
- **우선순위(priority) 티어 내에서는 FIFO**, 티어 간에는 `NOW → NEXT → LATER`.
- 기본 구현은 인메모리, 저장소 인터페이스 분리로 **Redis / Mongo 등으로 리팩토링 없이 교체 가능**.

관련 시나리오:

- 사용자가 긴 툴 실행 중 "실제로는 그 파일 말고 이걸 봐줘"라고 정정 입력.
- 서브 에이전트가 부모에게 중간 결과를 push-back.
- 턴 종료 시점에 큐에 남은 입력을 자동으로 다음 턴의 시드로 소비 (CQ-05).

## 아키텍처

```
┌──────────────────────────────────────────────────────────────┐
│                Producer (REPL / sub-agent)                   │
│    MessageQueueManager#enqueue(QueuedInput)                  │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│              MessageQueueManager (facade)                    │
│  ─ 리스너 fan-out (ENQUEUED/DRAINED/REMOVED)                 │
│  ─ drainForInjection(filter, maxPriority) 배치 drain         │
│  ─ snapshot() — 관측용 읽기 전용 뷰                          │
└────────────────────────┬─────────────────────────────────────┘
                         │ 위임
                         ▼
┌──────────────────────────────────────────────────────────────┐
│            MessageQueueRepository (storage)                  │
│  기본: InMemoryMessageQueueRepository                        │
│  교체 지점: Redis, Mongo, JDBC, … (멀티 인스턴스용)          │
└──────────────────────────────────────────────────────────────┘
                         ▲
                         │ 반복 경계에서 drain
                         │
┌──────────────────────────────────────────────────────────────┐
│        Consumer (OrcaAgentExecutor ReAct 루프)               │
│    injectQueuedMessages(scope) → Message.user(...)           │
└──────────────────────────────────────────────────────────────┘
```

**계층 분리 원칙**

- `MessageQueueRepository` — 순수 저장 추상화. 분산 백엔드를 붙일 때 유일하게 교체되는 지점.
- `MessageQueueManager` — 파사드. 리스너 fan-out과 **배치 drain** (주입 시 우선순위-FIFO 순서 보장) 등 "실행 시점에 필요한 의미"를 담당.
- 소비자(Orca ReAct 루프, REPL)는 **파사드에만 의존**합니다. Repository에 직접 의존해서는 안 됩니다.

## 공개 API 표면

### `QueuedInput` (불변 값 객체)

| 필드 | 타입 | 설명 |
|------|------|------|
| `uuid` | `UUID` | 식별자 — `equals()` / `hashCode()`의 기준 |
| `inputText` | `String` | 원문 (비어있을 수 없음) |
| `priority` | `QueuedInputPriority` | 우선순위 티어 (기본 `NEXT`) |
| `agentExecutionContextId` | `AgentRuntimeId` | 어느 컨텍스트가 소비할 메시지인지 |
| `sourceAgentId` | `Optional<String>` | 서브에이전트 origin (REPL이면 empty) |
| `enqueuedAt` | `Instant` | 큐잉 시각 |
| `metadata` | `Map<String,String>` | 프로듀서 임의 태그 (불변 복사본) |

빌더 사용:

```java
QueuedInput queued = QueuedInput.builder()
    .inputText(userMessage)
    .priority(QueuedInputPriority.NEXT)
    .agentExecutionContextId(ctxId)
    .metadata(Map.of("origin", "repl"))
    .build();
```

### `QueuedInputPriority` (enum, 순서 고정)

| 값 | 의미 |
|----|------|
| `NOW` | 가능한 빨리 — 현재 에이전트의 다음 iteration 경계에서 즉시 주입 |
| `NEXT` | 기본값. 현재 턴 종료 시점에 새 유저 메시지로 흡수 |
| `LATER` | `maxPriority=LATER`로 명시적으로 요청할 때만 나감 |

`ordinal()` 기반 "최대 priority X 이하"로 배치 drain에 사용되므로 **enum 순서를 바꾸지 마세요**.

### `MessageQueueRepository` (저장 추상화)

```java
public interface MessageQueueRepository {
    void enqueue(QueuedInput input);
    Optional<QueuedInput> dequeue(Predicate<QueuedInput> filter);
    Optional<QueuedInput> peek(Predicate<QueuedInput> filter);
    List<QueuedInput> listByMaxPriority(QueuedInputPriority maxPriority, Predicate<QueuedInput> filter);
    boolean remove(UUID uuid);
    void subscribe(MessageQueueListener listener); // 선택적 (in-memory에서는 no-op)
    int size();
}
```

분산 백엔드를 붙이는 개발자가 구현해야 하는 유일한 인터페이스입니다. FIFO-within-priority 보장은 이 계층의 책임입니다.

### `MessageQueueManager` (파사드)

```java
public interface MessageQueueManager {
    void enqueue(QueuedInput input);
    List<QueuedInput> drainForInjection(Predicate<QueuedInput> filter, QueuedInputPriority maxPriority);
    void addListener(MessageQueueListener listener);
    void removeListener(MessageQueueListener listener);
    List<QueuedInput> snapshot();
}
```

`drainForInjection`는 **하나의 논리적 배치 drain**입니다 — `filter` 통과 + `priority.ordinal() <= maxPriority.ordinal()`인 항목을 일괄 제거하고, 그 후 리스너에게 `DRAINED`를 우선순위-FIFO 순서로 통지합니다. `dequeue()`를 루프 도는 것과는 달리 중간에 고우선순위가 끼어들어 순서가 흔들리지 않습니다.

### `MessageQueueListener`

```java
@FunctionalInterface
public interface MessageQueueListener {
    void onEvent(Event event);

    enum ChangeType { ENQUEUED, DRAINED, REMOVED }

    final class Event {
        QueuedInput getInput();
        ChangeType getChangeType();
    }
}
```

- **상태 변경 스레드에서 동기적**으로 호출됩니다. 콜백은 빠르게 반환해야 하며, 어떤 스레드에서도 동작할 수 있어야 합니다.
- 리스너가 던진 예외는 매니저가 catch하고 WARN 로그만 찍습니다 — **다른 리스너와 프로듀서에게 전파되지 않습니다**.

## 사용 패턴

### 1. 프로듀서: 에이전트가 바쁠 때 입력을 큐잉

```java
// ReplSession — 에이전트 슬롯이 점유되어 있으면 드랍 대신 큐잉
private void enqueueWhileBusy(String userMessage) {
    QueuedInput queued = QueuedInput.builder()
        .inputText(userMessage)
        .priority(QueuedInputPriority.NEXT)
        .agentExecutionContextId(agentExecutionContext.getId())
        .build();
    messageQueueManager.enqueue(queued);
}
```

### 2. 소비자: iteration 경계에서 배치 drain

```java
// OrcaAgentExecutor#injectQueuedMessages — ReAct 루프 tail에서 호출
final AgentRuntimeId contextId = scope.executionContext.getId();
final List<QueuedInput> drained = messageQueueManager.drainForInjection(
        q -> contextId.equals(q.getAgentRuntimeId()),
        QueuedInputPriority.NEXT);

for (QueuedInput queued : drained) {
    final String wrapped = SystemReminderFormatter.wrap(MID_TURN_INJECTION_KEY, queued.getInputText());
    scope.conversationMemory.addMessage(Message.user(wrapped));
}
```

`filter`에 **반드시 `agentExecutionContextId`를 포함**하세요. 포함하지 않으면 메인/서브 에이전트가 서로의 입력을 집어삼킵니다.

### 3. 턴 종료 시 잔여 메시지 자동 소비

턴이 끝난 시점에 `snapshot()`으로 남은 항목을 확인하고, `/command`는 개별 턴으로, 일반 prompt는 `drainForInjection(...)`로 한 번에 소비하는 전략을 씁니다.

## 관측 및 메트릭

큐는 두 개의 관측 지점을 제공합니다. 서로 다른 질문에 답하므로 함께 쓰는 것이 일반적입니다.

| 지점 | 답하는 질문 | 대표 구현 |
|------|-------------|-----------|
| `MessageQueueListener` | 큐 이벤트 자체 — 언제 몇 건이 enqueue/drain 되었나? 개별 메시지의 age는? | `LoggingMessageQueueListener` |
| `AgentExecutionInterceptor` | 실행 턴과의 상관관계 — 턴 시작/종료 시점 큐 깊이, 턴 동안의 enqueue/drain, 턴 duration | `QueueMetricsInterceptor` |

큐 이벤트 **자체**는 `execute()` 경계에서 발생하지 않으므로 인터셉터로는 관측할 수 없습니다 — 이벤트를 잡으려면 리스너를 쓰세요. 반대로 "이 턴이 실행되는 동안" 처럼 **실행 경계와 연결된** 관측이 필요하면 인터셉터가 유일한 훅입니다.

### 기본 옵션: `LoggingMessageQueueListener`

패키지가 기본으로 제공하는 레퍼런스 리스너입니다. 이벤트마다 한 줄의 DEBUG 로그를 남기고, `ChangeType` 별로 `LongAdder` 카운터를 유지합니다.

```java
MessageQueueManager manager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
LoggingMessageQueueListener metrics = new LoggingMessageQueueListener();
manager.addListener(metrics);

// … 어느 시점이든 …
long enqueued = metrics.getEnqueuedCount();
long drained  = metrics.getDrainedCount();
```

로그 라인 형식 (DEBUG):

```
queue-event change=ENQUEUED uuid=... priority=NEXT ctx=... ageMs=0 source=repl
queue-event change=DRAINED  uuid=... priority=NEXT ctx=... ageMs=3421 source=repl
```

### 커스텀 메트릭 (Micrometer / OpenTelemetry)

`LoggingMessageQueueListener`는 **샘플 구현**입니다. 프로덕션 메트릭 파이프라인을 쓰려면 직접 `MessageQueueListener`를 구현해서 매니저에 붙이세요.

```java
public class MicrometerMessageQueueListener implements MessageQueueListener {

    private final Counter enqueued;
    private final Counter drained;
    private final Timer   ageOnDrain;

    public MicrometerMessageQueueListener(MeterRegistry registry) {
        this.enqueued   = Counter.builder("aimon.queue.enqueued").register(registry);
        this.drained    = Counter.builder("aimon.queue.drained").register(registry);
        this.ageOnDrain = Timer.builder("aimon.queue.age_on_drain").register(registry);
    }

    @Override
    public void onEvent(Event event) {
        switch (event.getChangeType()) {
            case ENQUEUED -> enqueued.increment();
            case DRAINED  -> {
                drained.increment();
                ageOnDrain.record(Duration.between(event.getInput().getEnqueuedAt(), Instant.now()));
            }
            case REMOVED  -> { /* 현재 디폴트 매니저는 발행하지 않음 */ }
        }
    }
}
```

**콜백 계약을 어기지 마세요** — 절대 블로킹 I/O를 하지 말고, 예외를 던지더라도 매니저가 격리해주지만 가능한 throw하지 않도록 설계하세요.

### 실행 경계 메트릭: `QueueMetricsInterceptor`

`QueueMetricsInterceptor`는 에이전트 실행 **턴 단위**의 큐 메트릭을 기록하는 `AgentExecutionInterceptor` 구현체입니다. 리스너와 달리 턴 시작/종료를 인지하므로 아래 질문에 답할 수 있습니다.

- 턴이 시작될 때 이 컨텍스트의 큐 깊이는 얼마였는가? 끝났을 때는?
- 이 턴이 실행되는 **동안에** 몇 건이 enqueue/drain 되었는가?
- 이 턴은 얼마나 걸렸는가?

구성:

```java
QueueMetricsInterceptor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>
        queueMetrics = new QueueMetricsInterceptor<>(messageQueueManager);

AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor =
        InterceptingAgentExecutor.builder(orcaExecutor)
                .addInterceptor(queueMetrics)   // 가장 바깥쪽에 두어 전체 턴 구간을 감싸세요
                .build();
```

턴이 끝날 때마다 다음과 같은 INFO 로그가 남습니다.

```
queue-metrics ctx=...id... preDepth=2 postDepth=0 enqueuedInTurn=1 drainedInTurn=3 durationMs=842
```

누적 카운터 조회:

```java
long turns    = queueMetrics.getExecutionsObserved();
long enqueued = queueMetrics.getEnqueuedDuringExecutions();
long drained  = queueMetrics.getDrainedDuringExecutions();
```

Micrometer로 브리지하려면 구현체를 감싸서 `finally` 구간에서 `registry.counter(...)` / `registry.timer(...)`에 기록하세요. 인터셉터는 `AgentRuntimeId`로 이벤트를 필터하므로 메인/서브 에이전트 턴이 서로의 메트릭을 오염시키지 않습니다. 내부적으로 턴 수명 동안만 살아있는 리스너를 매니저에 붙였다가 `finally`에서 제거하는 방식이므로, 체인이 예외를 던져도 리스너 누수는 발생하지 않습니다.

**리스너와 함께 쓰기** — 둘을 동시에 등록하는 것이 표준입니다. 리스너가 누적 총량을, 인터셉터가 턴 단위 상관관계를 담당합니다.

## 멀티 인스턴스 — Repository 교체

AIMON의 멀티 인스턴스 원칙에 따라 큐 저장소는 **인터페이스 교체만으로 분산 백엔드**로 옮길 수 있어야 합니다. Repository를 교체해도 `MessageQueueManager` / 프로듀서 / 소비자 코드는 바뀌지 않습니다.

### 구현 체크리스트

새 `MessageQueueRepository` 구현체를 만들 때 반드시 확인할 계약:

1. **FIFO-within-priority**: 같은 priority 내에서 insertion order 보존.
2. **Cross-priority ordering**: `NOW → NEXT → LATER` (ordinal 오름차순).
3. **`remove(UUID)` 멱등성**: 이미 없는 uuid면 `false` 반환, 예외 throw 금지.
4. **Predicate 원자성**: `dequeue(Predicate)`는 predicate 평가 + 제거를 원자적으로 수행해야 함 (동시성 환경에서 중복 드레인 방지).
5. **`listByMaxPriority` 일관성**: 반환 순서는 우선순위-FIFO, 결과는 defensive copy.
6. **스레드 안전성**: 모든 메서드가 임의의 스레드 조합에서 호출 안전.

### 교체 예시: 단일 JVM → Redis

기존 코드 (단일 인스턴스):

```java
MessageQueueRepository repo = new InMemoryMessageQueueRepository();
MessageQueueManager    mgr  = new DefaultMessageQueueManager(repo);
```

분산 전환:

```java
// aimon-queue-redis 모듈에 사는 가상의 구현체
MessageQueueRepository repo = new RedisMessageQueueRepository(
        redisClient,
        /*namespace=*/"aimon:queue");
MessageQueueManager    mgr  = new DefaultMessageQueueManager(repo);  // ← 파사드 동일
```

- 프로듀서/소비자 코드, 리스너 구현, ReAct 루프 코드 모두 **변경 없음**.
- 새 구현체는 `implementation(project(":aimon-core"))` 의존성만 걸고 `MessageQueueRepository`를 구현하면 됩니다.

### 주의 사항

- `subscribe(MessageQueueListener)`는 **저장소 계층의 선택적 이벤트 경로**입니다 (예: Redis keyspace notifications). 인메모리 구현은 no-op이며, 대부분의 프로젝트는 매니저 리스너만 쓰면 충분합니다.
- TTL/큐 상한선 (예: 50개, 30분) 정책은 저장소 구현체가 직접 처리하거나 리스너가 `REMOVED` 이벤트로 노출하도록 설계하세요. `QueuedInput`의 `enqueuedAt`을 기준으로 aging 판단이 가능합니다.
- 분산 환경에서는 여러 JVM이 같은 큐를 소비할 수 있습니다. `dequeue()`/`remove()`는 원자적이어야 하며, 중복 소비를 막기 위해 `filter` 조건에 반드시 `agentExecutionContextId` (이 JVM이 실제로 실행 중인 컨텍스트)를 포함하세요.

## 테스트 레시피

```java
@Test
void drainsOnlyMatchingContext() {
    InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
    DefaultMessageQueueManager mgr = new DefaultMessageQueueManager(repo);
    LoggingMessageQueueListener metrics = new LoggingMessageQueueListener();
    mgr.addListener(metrics);

    AgentRuntimeId main = AgentRuntimeId.of("main");
    AgentRuntimeId sub  = AgentRuntimeId.of("sub");

    mgr.enqueue(QueuedInput.builder().inputText("a").agentExecutionContextId(main).build());
    mgr.enqueue(QueuedInput.builder().inputText("b").agentExecutionContextId(sub).build());

    List<QueuedInput> drained = mgr.drainForInjection(
        q -> q.getAgentRuntimeId().equals(main),
        QueuedInputPriority.NEXT);

    assertThat(drained).hasSize(1).allMatch(q -> q.getInputText().equals("a"));
    assertThat(metrics.getEnqueuedCount()).isEqualTo(2);
    assertThat(metrics.getDrainedCount()).isEqualTo(1);
    assertThat(mgr.snapshot()).hasSize(1); // sub-agent 입력은 남아있음
}
```

인메모리 리포지토리는 테스트에도 그대로 쓸 수 있습니다 — 별도 목을 만들 필요가 없습니다.

## 디자인 원칙

- **Storage와 Facade의 분리**: 분산 백엔드가 들어올 때 Repository 계층만 바뀌고, 나머지 코드는 영향을 받지 않습니다. ([multi-instance design rule](../../../.claude/rules/multi-instance-design.md))
- **Interceptor vs Listener**: `AgentExecutionInterceptor`는 실행 체인을 **제어**하는 동기 계층이고, `MessageQueueListener`는 큐 이벤트를 **관측**하는 비동기 훅입니다. 두 계층은 상호 배타적이지 않습니다 — 턴 경계와 무관한 누적 지표(예: 총 enqueue 수)는 `LoggingMessageQueueListener`로 수집하고, 턴 경계와 맞물리는 지표(예: 실행 전후 큐 깊이, 턴당 enqueue/drain, 실행 시간)는 `QueueMetricsInterceptor`가 실행 경계에서 짧게 뜨는 내부 리스너로 수집합니다. 역할을 섞지 마세요 (`docs/design/agent-execution/interceptor.md` §9.2 참고).
- **Immutable I/O**: `QueuedInput`은 불변 + 빌더, `metadata`는 defensive copy. 큐에 넣은 뒤 외부에서 변조해도 내부 상태는 안전합니다.
- **Listener 격리**: 리스너 예외는 매니저가 잡고 WARN 로그만 찍습니다. 메트릭 구현의 버그가 ReAct 루프를 멈추게 하지 않습니다.

---

## 관련 문서

- [interceptor.md](../../design/agent-execution/interceptor.md) §9.2 StreamingEvents와의 관계
- [system-reminder-convention.md](system-reminder-convention.md) — 주입된 메시지의 `<system-reminder>` 래핑 규칙
- [solid-principles.md](../../project/solid-principles.md)
- `at.aimon.core.agent.queue.package-info` — 패키지 차원의 Javadoc 요약
