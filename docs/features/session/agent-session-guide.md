# LiveSession 개발 가이드

> 한 세션(`SessionRecord`)에 대해 다중 턴을 실행하는 **노드 로컬 핸들** `LiveSession` 사용 가이드.
> SESSION-01 ~ SESSION-04 API 기반.

> 본 가이드의 스코프 모델은 [`docs/overview/scope-model.md`](../../overview/scope-model.md) 를 따른다 — `AgentRuntime` 은
> agent-scoped 이며 같은 에이전트의 세션들이 공유한다.

이 문서는 `at.aimon.core.agent.session` 패키지의 `LiveSession`, `DefaultLiveSession`, `LiveSessionOptions`,
`LiveSessionFactory` 를 사용하여 **다중 턴 대화**를 안전하게 오케스트레이션하는 방법을 설명한다.

IMPORTANT (이름): 예전 이름은 `AgentSession` / `DefaultAgentSession` / `AgentSessionOptions` /
`AgentSessionFactory` 였고, 대화 식별자는 `ConversationId` 였다. 세션 우선 개편에서 각각 `LiveSession*` 과
`SessionId` 로 개명되었다 — 맨 단어 `AgentSession` 은 이제 **타입 이름으로 금지**되며
`SessionNamingArchitectureTest` 가 빌드에서 막는다. 옛 이름으로 검색할 때는
[`rename-maps.md`](../../migration/rename-maps.md) 의 매핑 표를 본다.

## 목차

1. [왜 LiveSession인가](#왜-livesession인가)
2. [핵심 컴포넌트](#핵심-컴포넌트)
3. [세션 라이프사이클](#세션-라이프사이클)
4. [close() 규칙: SchedulingEngine은 절대 건드리지 않는다](#close-규칙-schedulingengine은-절대-건드리지-않는다)
5. [다중 세션 주의사항](#다중-세션-주의사항)
6. [전체 예제](#전체-예제)
7. [체크리스트](#체크리스트)

---

## 왜 LiveSession인가

`OrcaAgentExecutor` 는 상태가 없는(long-lived, stateless) 실행 엔진이다. 매 호출에 runtime 과 request 를 함께
받지만, 세션 수준의 **identity** 와 **lifecycle** 은 호출자가 직접 관리해야 했다.

`LiveSession` 은 이를 단순화하는 퍼사드(facade)다.

| 책임 | 이전 | LiveSession 이후 |
|------|------|------------------|
| `SessionId` 유지 | 호출자가 매번 전달 | 핸들이 open 시점에 바인딩 후 재사용 |
| `ExecutionBudget` 주입 | 매 요청마다 빌더로 조립 | `LiveSessionOptions` 에 1회 지정 |
| `OrcaAgentExecutionRequest` 조립 | 호출자가 직접 | `session.submit(input)` 내부에서 생성 |
| 실행 중 재입력 처리 | 호출자의 외부 `QueryGuard` | `session.offerAsync(...)` 의 `SubmitOutcome` |
| 세션 누적치·budget override 영속 | 호출자가 직접 | `SessionRecordStore` 로 자동 hydrate / flush |

### 설계 원칙

- **Facade**: `LiveSession` 은 얇은 API 로 `OrcaAgentExecutor` + `OrcaAgentRuntime` 을 감싼다.
- **Dependency Inversion**: `LiveSession` 은 인터페이스, `DefaultLiveSession` 은 구현체.
  `LiveSessionFactory` 는 추상 `AgentRegistry` 와 `ContextBuilder` 전략에 의존한다.
- **Immutable options**: `LiveSessionOptions` 는 빌더 패턴의 불변 값 객체
  (`Objects.requireNonNull` + `Optional<T>` 반환 규약 준수).

---

## 핵심 컴포넌트

### LiveSession (interface)

```java
public interface LiveSession extends AutoCloseable {

    SessionId getSessionId();

    /** 진단·모니터링용 best-effort 스냅샷. 제어 게이트로 쓰지 말 것. */
    default LiveSessionStatus status() { /* ... */ }

    /** 한 턴 실행 — submit(input, SubmitOptions.empty()) 과 동일. */
    default AgentExecutionResult submit(String input) {
        return submit(input, SubmitOptions.empty());
    }

    AgentExecutionResult submit(String input, SubmitOptions submitOptions);

    /** 텍스트면 위로 내려보내고, 그 밖이면 UnsupportedOperationException. */
    default AgentExecutionResult submit(UserInput input, SubmitOptions submitOptions) { /* ... */ }

    CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener);

    /** 실행 중이면 큐에 넣고, 유휴면 즉시 실행한다 — busy 판단은 이 반환값으로 한다. */
    SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener);

    default void interrupt(InterruptReason reason) { /* no-op by default */ }

    default void interrupt(TurnId turnId, InterruptReason reason) { /* no-op by default */ }

    /** 중단된 마지막 턴을 이력에서 걷어내고 다시 실행한다. 되돌릴 것이 없으면 빈 Optional. */
    default Optional<AgentExecutionResult> retryLastTurn(SubmitOptions submitOptions) { /* ... */ }

    @Override
    void close();
}
```

**계약**:
- `submit(String)` 은 하나의 턴(turn)을 실행한다. 내부적으로 `OrcaAgentExecutionRequest` 를 조립하여
  `OrcaAgentExecutor.execute()` 를 호출한다.
- **구현해야 하는 것은 `String` 오버로드 셋이고, `UserInput` 오버로드는 그 위의 기본 구현이다.** 이미지·첨부
  파일·멀티모달 요청은 `submit(UserInput, ...)` / `offerAsync(UserInput, ...)` 로 넣는다. 기본 구현은
  `TextInput` 이면 벗겨서 `String` 메서드로 내려보내므로 **텍스트는 어떤 세션에서든 통하고**(그래서 옛 세션도
  텍스트 턴을 재시도할 수 있다), 그 밖의 입력은 `UnsupportedOperationException` 이다 — 텍스트 자리표시자로
  납작하게 만들어 조용히 다른 턴을 돌리지 않는다.
- **큐는 텍스트 채널이다.** 지연된 입력은 `<system-reminder>` 블록으로 재생되므로, 턴이 도는 중에 비텍스트
  입력을 `offerAsync` 에 넣으면 `IllegalStateException` 이다 — 미룰 수도 없고 실행하면 전사를 두 턴이 나눠
  쓰기 때문이다. 동시 실행을 의도했다면 `submitAsync` 를 쓴다.
- `close()` 는 **라이브 세션 스코프 리소스만 정리**한다. agent-scoped(`AgentRuntime`, `McpClientManager`) 또는
  application-scoped(`OrcaAgentExecutor`, `SchedulingEngine`, `ScheduledTaskManager`) 자원은 건드리지 않는다.
- `status()` 와 `currentTurnId()` 는 **best-effort 관찰 스냅샷**이다. "턴을 시작해도 되는가" 는 반드시
  `offerAsync` 의 `SubmitOutcome` 으로 판단한다 — `status()` 를 읽고 분기하는 코드는 본질적으로 race 다.
- `events()` 는 STREAM-03 확장을 위한 훅. 기본 구현은 빈 `Flow.Publisher` 를 즉시 완료 상태로 반환한다. 지금
  실시간 이벤트가 필요하면 `submitAsync(input, options, listener)` 의 listener 를 쓴다.
- `retryLastTurn(...)` 은 **`INTERRUPTED` 로 끝난 마지막 턴**에만 응답한다. 재시도는 같은 요청을 다시 묻는
  것과 다르다 — 중단된 턴이 남긴 흔적(사용자 메시지, 그 앞의 합성 컨텍스트 블록, 멈추기 전의 어시스턴트
  출력, skipped 로 채워진 도구 결과)을 **먼저 걷어내고** 원래 시작한 자리에서 다시 실행한다. 그러지 않으면
  모델에게 "이미 반쯤 해 놓았다" 고 적힌 이력에서 그 일을 다시 하라고 시키는 것이 된다.
  "재시도 가능한가" 를 먼저 묻는 술어는 없다 — 묻는 순간과 하는 순간 사이에 답이 바뀌므로 그 위의 분기는
  race 다. **빈 `Optional` 이 그 답이다** (`status()` 에 대한 위 규칙과 같은 이유).
  되감기 지점은 전사와 함께 영속되므로 핸들을 닫았다 다시 열어도, 다른 노드에서도 재시도할 수 있다.
  되감기 지점이 들고 있는 것은 그 턴을 시작한 `UserInput` **과 그때의 `SubmitOptions`** 이므로, 어떤 입력으로
  시작한 턴이든 재시도되고 **원래의 principal·시스템 프롬프트 변수 아래에서** 다시 돈다. 무인자
  `retryLastTurn()` 이 원래 옵션을 쓰고, `retryLastTurn(옵션)` 이 그것을 갈아 끼운다. `rewindLastTurn()` 은
  둘을 담은 `RewoundTurn` 을 돌려준다 — 입력만 집어 제출하면 같은 말을 다른 사람이 하는 것이 된다.
  설계 근거는 [`interrupt.md` §15](../../design/agent-execution/interrupt.md).

### DefaultLiveSession

표준 구현체. `OrcaAgentRuntime` + `OrcaAgentExecutor` + `LiveSessionOptions` 를 감싼다.

```java
public final class DefaultLiveSession implements LiveSession {
    private final SessionId sessionId;
    private final OrcaAgentRuntime agentRuntime;       // agent-scoped (여러 세션이 공유)
    private final AgentExecutor<...> executor;         // application-scoped
    private volatile LiveSessionOptions options;
    private final ExecutionBudget openerDefaultBudget;
    private final MessageQueueManager messageQueueManager;   // nullable — 없으면 offerAsync 가 enqueue 하지 않음
    private final HookExecutionManager hookExecutionManager; // nullable — 없으면 세션 훅을 발화하지 않음
    private final SessionRecordStore sessionRecords;         // nullable — 없으면 durable state 없이 동작
    private volatile boolean closed;

    // submit()은 sessionId + 유효 budget을 request에 자동 주입
    // close()는 AgentRuntime을 닫지 않음 — agent-scoped이므로 앱 종료 시점까지 유지
}
```

생성자는 4개 오버로드가 있고 모두 가장 긴 7-arg 생성자로 위임한다. 뒤쪽 3개 협력자는 모두 nullable 이며,
`null` 이면 그 기능만 비활성화된다(각각 auto-queue / 세션 훅 / durable state).

```java
new DefaultLiveSession(sessionId, agentRuntime, executor, options);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager, hookExecutionManager);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager, hookExecutionManager,
        sessionRecordStore);
```

**중요 규칙**:
- `submit()` 내부에서 `sessionId` 와 유효 budget 을 request 에 주입한다. **호출자는 sessionId/budget 을
  신경쓰지 않는다.**
- `close()` 이후의 `submit()` / `submitAsync()` / `offerAsync()` 는 `IllegalStateException`.
- `close()` 는 **idempotent**: 여러 번 호출해도 부작용이 없다 (`isClosed()` 로 확인 가능).
- `sessionRecords` 가 주어지면 생성 시점에 세션 레코드에서 `sessionTotals` 와 budget override 를 **hydrate**
  하고, 턴이 끝날 때마다 되쓴다. 영속된 override 가 있으면 opener 가 준 default budget 보다 **우선**한다.

### LiveSessionOptions

세션 open 시점의 기본 설정값. 불변 빌더 패턴.

```java
LiveSessionOptions opts = LiveSessionOptions.builder()
    .budget(ExecutionBudget.builder().maxIterations(10).maxTokens(50_000).build())
    .locale(Locale.KOREAN)
    .sourceAgentId("cli-repl")
    .build();
```

- `budget` 미지정 또는 null → `ExecutionBudget.unlimited()` (기존 동작 보존)
- `locale`, `sourceAgentId` 는 `Optional<T>` 로 반환
- `LiveSessionOptions.defaults()` 는 `builder().build()` 와 동일 — unlimited budget, locale/sourceAgentId 없음
- `withBudget(newBudget)` 은 locale / sourceAgentId 를 보존한 채 budget 만 교체한 복사본을 만든다
  (`/budget` 같은 런타임 override 경로가 쓴다)

### LiveSessionFactory

`AgentRegistry` 로 agent 를 조회한 뒤 `ContextBuilder` 전략으로 agent runtime 을 얻어 `DefaultLiveSession` 을
생성한다.

```java
// 부트스트랩 1회: agent별 runtime 등록 (세션 open과 분리)
OrcaAgentRuntime runtime = manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);
// → runtimeId = AgentRuntimeId.from(bundle.getAgent())  // "agent:<name>"

// 4번째 인자(SessionRecordStore)는 생략 가능 — 생략하면 durable state 없이 동작한다.
LiveSessionFactory factory = new LiveSessionFactory(agentRegistry,
        agent -> manager.getOrCreateRuntime(bundleFor(agent), fileSystem, credentialStore),
        executor,
        sessionRecordStore);

// 이후 세션은 agent runtime을 재사용한다 — 매 open마다 신규 생성되지 않음
try (LiveSession session = factory.open(
        SessionId.generate(), "default", LiveSessionOptions.defaults())) {
    session.submit("Hello");
    session.submit("What did I just say?");
}
```

`ContextBuilder` 는 `@FunctionalInterface` 로 **전략(strategy) 패턴**이다. 팩토리는
`OrcaAgentRuntimeFactory` 의 구체 타입에 의존하지 않고 람다 하나로 runtime 조회 로직을 주입받는다 →
Dependency Inversion Principle 준수. 구현은 **idempotent** 해야 하며 호출마다 새 runtime 을 만들어서는 안
된다 — MCP 연결과 훅 등록은 세션들 사이에서 의도적으로 공유된다.

`open()` 은 `agentRef` 가 registry 에 없으면 `IllegalArgumentException` 을 던진다.

---

## 세션 라이프사이클

```
[open]  ──────────►  [submit...submit]  ──────────►  [close]
  │                         │                           │
  │                         │                           │
AgentRegistry.            OrcaAgentExecutor.        큐 구독 해제 +
findByName(ref)           execute(runtime, req)     turn refs 정리 + OnSessionEnd
  │                         │                           │
contextBuilder.build()   SessionTranscript          NOT AgentRuntime,
  │                       (SessionId 유지)           NOT scheduling/executor
 DefaultLiveSession
  │
SessionRecordStore 에서 hydrate
(sessionTotals, budgetOverride)
```

### 스코프별 리소스 분류

4단계 스코프 모델에 따라 리소스를 분류한다. 전체 규칙은 [`docs/overview/scope-model.md`](../../overview/scope-model.md)
§1–§2, 설계 배경은
[`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md)
를 참조한다.

| 스코프 | 리소스 | close() 시점 |
|--------|--------|-------------|
| **Live session** | `LiveSession` 핸들, 메시지 큐 구독, 진행 중 턴 참조(coordinator/tracker/turnId) | `session.close()` — 여기서 닫는 것은 이것뿐이다 |
| **Session** | `SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript` | 핸들보다 오래 산다. 세션 삭제 시 `SessionRecordStore.delete(sessionId)` |
| **Agent** | `OrcaAgentRuntime` (ToolRegistry, HookRegistry, SkillRegistry, MCP 연결 등) | agent 제거 또는 애플리케이션 종료 시 `OrcaAgentRuntimeManager.destroyRuntime` (`session.close()` 에서 닫지 않음) |
| **Application** | `OrcaAgentExecutor`, `LlmClient`, `SchedulingEngine`, `ScheduledTaskManager`, `AgentRegistry`, `SessionRecordStore` | 애플리케이션 종료 시 |

> **핵심**: `session.close()` 는 `AgentRuntime` 을 닫지 않는다. runtime 은 agent-scoped 이므로 동일 agent 의
> 여러 세션이 공유한다. 세션을 닫아도 agent 의 툴/훅/스킬 registry 와 MCP 연결은 살아 있다.

IMPORTANT (session ≠ live session): `SessionRecord` 하나에 `LiveSession` 은 **0..N** 개다. 핸들이 닫혀도
세션은 남고, 재시작·축출·노드 이동 뒤에 다른 핸들이 같은 `SessionId` 로 다시 서빙할 수 있다. 따라서 그
경계를 넘어 살아남아야 하는 값은 핸들이 아니라 **레코드**에 둔다 — 그래서 `SessionTotals` 와
`budgetOverride` 가 `SessionRecordStore` 를 통해 오간다.

### 다중 턴 연속성

동일한 `SessionId` 를 쓰면 executor 에 연결된 `TranscriptManager`(내부적으로 `SessionRecordStore`)를 통해
이전 턴의 메시지가 자동으로 재적재된다.

```java
try (LiveSession session = factory.open(sessionId, "default", options)) {
    session.submit("My name is Alice.");       // turn 1
    session.submit("What is my name?");        // turn 2 — "Alice"를 답변
}
```

내부적으로는:
1. `session.submit()` 이 `OrcaAgentExecutionRequest.sessionId = sessionId` 설정
2. `OrcaAgentExecutor.execute()` 가 `TranscriptManager.initialize(sessionId, systemPrompt)` 로 과거 메시지 적재
   (이름과 달리 세션당 1회가 아니라 **턴당 1회** 호출된다 — [scope-model §6](../../overview/scope-model.md) 의
   알려진 오칭 목록 참조)
3. 새 턴의 messages 를 append 후 `TranscriptManager.save(...)`

---

## close() 규칙: SchedulingEngine은 절대 건드리지 않는다

> **CLAUDE.md** 의 "Scope & Scheduling Lifecycle" 원칙과 동일.

### 규칙

- `LiveSession.close()` → `AgentRuntime` 을 닫지 않는다. runtime 은 agent-scoped 이며 세션 간 공유 자원이다.
- `AgentRuntime.close()` 는 agent 제거 또는 애플리케이션 종료 시점에만
  `OrcaAgentRuntimeManager.destroyRuntime` 을 통해 호출한다 (MCP 연결 해제 등).
- `SchedulingEngine`, `ScheduledTaskManager` 는 **빌더를 통해 주입**될 뿐 runtime 이 소유하지 않는다 → close
  하지 않는다.
- `OrcaAgentExecutor`, `SessionRecordStore` 는 **세션이 소유하지 않는다** → close 하지 않는다.

### 왜 중요한가

- 하나의 애플리케이션에서 수백~수천 개의 세션이 열고 닫히는 동안, 스케줄링 엔진은 계속 동작해야 한다 (예약된
  작업 트리거, 주기 태스크 실행).
- 세션 단위로 스케줄링 엔진을 닫으면 **다른 세션의 예약 작업이 유실**된다. `ScheduledTask.boundRuntimeId` 는
  agent-scoped id 를 참조하므로, 원래 세션이 끝난 뒤 cron 이 재발화해도 runtime 이 resolve 되어야 한다.
- 잘못된 코드:
  ```java
  @Override
  public void close() {
      agentRuntime.close();      // ❌ 같은 agent의 다른 세션의 MCP 서브프로세스까지 죽인다
      schedulingEngine.close();  // ❌ 다른 세션의 예약 작업 파괴
  }
  ```

### 검증 (단위 테스트)

```java
// DefaultLiveSessionTest.CloseContract
@Test
@DisplayName("close() does NOT close the agent-scoped agentRuntime (scope contract)")
void closeDoesNotDelegateToAgentRuntime() {
    // 실제 runtime을 spy로 감싸 어떤 lifecycle 메서드가 호출되는지 관찰한다.
    final OrcaAgentRuntime context = spy(createContext());

    final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
            LiveSessionOptions.defaults());
    session.submit("hi");

    session.close();
    verify(context, never()).close();

    session.close();                        // idempotent
    verify(context, never()).close();
}
```

같은 `@Nested` 클래스에 `close()` 멱등성과 `close()` 이후 `submit()` 의 `IllegalStateException` 검증이 함께
있다.

---

## 다중 세션 주의사항

### 1. 동시성

- `OrcaAgentExecutor` 는 thread-safe 하게 설계되어 있으므로 하나의 인스턴스가 여러 세션을 동시에 실행 가능.
- `DefaultLiveSession` 은 내부 상태(`closed`, `busy`, 진행 중 턴 참조)를 `volatile` / `Atomic*` 으로 관리하지만,
  **한 핸들은 한 번에 한 턴**을 가정한다. 인터페이스 javadoc 이 명시하듯 구현체는 thread-safe 를 요구받지 않는다.
- 실행 중에 도착한 입력을 어떻게 할지는 `offerAsync` 가 결정한다 — `MessageQueueManager` 가 연결돼 있으면
  큐에 넣고 `QUEUED` 를, 유휴면 즉시 실행하고 `EXECUTED` 를 반환한다. 큐가 없으면 enqueue 하지 않고 직접
  실행으로 폴백한다.
- 동시 대화가 필요하면 **세션 N개를 각각 open** 한다.

### 2. SessionId 재사용

- 같은 `SessionId` 로 라이브 세션 두 개를 **동시에** 열면 둘이 같은 transcript 와 같은 세션 레코드를
  경쟁적으로 수정한다 → **데이터 경합 발생 가능**. 순차적 재개(핸들을 닫고 나중에 다시 열기)는 정상 시나리오다.
- 멀티 인스턴스(scale-out) 환경에서 한 세션에 한 홀더만 두려면 `SessionLeaseStore` 기반 라우팅
  (`aimon-session-routing` 의 `SessionRouter` / `LiveSessionOpener`)을 쓴다. 저장소 구현체
  (`SessionRecordStore`)도 동시성을 처리해야 한다 (distributed lock, optimistic concurrency).

### 3. Budget 초기화

- `LiveSessionOptions.budget` 은 **세션 default** 이며 모든 턴에 동일하게 주입된다. 런타임에 바꾸려면
  `DefaultLiveSession.setOptions(options.withBudget(newBudget))` 을 쓰고, 되돌리려면 `clearBudgetOverride()` 로
  opener 가 준 default 로 복귀한다. 두 경로 모두 `SessionRecordStore` 에 되쓰인다.
- `BudgetTracker` 는 **턴 단위**로 리셋된다 — `maxIterations` 는 한 번의 submit 내에서 적용되는 제약이며
  세션 전체에 누적되지 않는다. 세션 전체 누적치는 별도로 `SessionTotals` 가 들고 있다
  (`status().getSessionTotals()`).

### 4. Multi-instance ready

`CLAUDE.md` 멀티 인스턴스 원칙에 따라:
- `LiveSession`, `LiveSessionFactory`, `LiveSessionOptions` 는 **추상화만 제공**한다.
- 노드 간 조정에 필요한 저장소(`SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`)는 구현체 교체만으로
  백엔드를 바꿀 수 있다 — `MongoSessionLeaseStore` (`aimon-session-mongodb`), `PostgresSessionLeaseStore`
  (`aimon-session-postgres`), `RedisSessionLeaseStore` (`aimon-session-redis`).
- 반면 **`SessionRecordStore` 의 in-tree 구현체는 `InMemorySessionRecordStore` 하나뿐이다.** 재시작을 넘겨
  세션 레코드를 살려야 하면 호스트가 인터페이스를 직접 구현해 주입한다. 펜싱까지 필요하면
  `new DefaultSessionStore(leaseStore, recordStore)` 로 감싼다(세션 매니저당 1개).

---

## 전체 예제

### CLI REPL에서 대화 세션 운영

```java
public class CliAgentRunner {
    private final LiveSessionFactory sessionFactory;

    public CliAgentRunner(LiveSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public void runInteractive(String agentRef) {
        SessionId sessionId = SessionId.generate();
        LiveSessionOptions options = LiveSessionOptions.builder()
            .budget(ExecutionBudget.builder()
                .maxIterations(20)
                .maxTokens(100_000)
                .maxWallClockDuration(Duration.ofMinutes(5))
                .build())
            .locale(Locale.getDefault())
            .sourceAgentId("aimon-cli")
            .build();

        try (LiveSession session = sessionFactory.open(sessionId, agentRef, options);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Session started: " + session.getSessionId());

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine();
                if (input.isBlank() || "exit".equals(input)) break;

                AgentExecutionResult result = session.submit(input);
                if (result.isSuccess()) {
                    // getFinalAnswer()/getErrorMessage()는 nullable String이다 (Optional이 아니다).
                    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(no answer)"));
                } else {
                    System.err.println("Error: " + Objects.requireNonNullElse(result.getErrorMessage(), "unknown"));
                }
            }
        }
        // session.close() 호출됨 — 라이브 세션 스코프 리소스만 정리
        // AgentRuntime(agent-scoped), schedulingEngine, executor, registry, sessionRecordStore는 살아있다
    }
}
```

### Spring Boot에서 세션 팩토리 주입

```java
@Configuration
public class AgentConfig {

    // AgentRuntimeRegistry는 manager 바깥에서 만들어 빌더로 주입한다 — manager가 소유하지 않는다.
    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean
    public OrcaAgentRuntimeManager agentRuntimeManager(
            OrcaAgentExecutor executor,
            ScheduledTaskManager scheduledTaskManager,
            AgentRuntimeRegistry agentRuntimeRegistry) {
        return OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .build();
    }

    // 부트스트랩: 각 AgentBundle에 대해 1회 agent runtime 등록
    @Bean
    public ApplicationRunner registerAgentRuntimes(
            OrcaAgentRuntimeManager manager,
            List<AgentBundle> bundles,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return args -> {
            for (AgentBundle bundle : bundles) {
                manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);
                // → runtimeId = "agent:<name>"  (discriminator 없는 단순 케이스)
            }
        };
    }

    @Bean
    public LiveSessionFactory liveSessionFactory(
            AgentRegistry agentRegistry,
            OrcaAgentRuntimeManager manager,
            OrcaAgentExecutor executor,
            SessionRecordStore sessionRecordStore,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        // agent runtime은 이미 registry에 등록되어 있으므로 세션 open 시 재사용된다
        return new LiveSessionFactory(agentRegistry,
                agent -> manager.getOrCreateRuntime(AgentBundle.builder().agent(agent).build(), fileSystem,
                        credentialStore),
                executor,
                sessionRecordStore);
    }
}
```

> `AgentRuntimeRegistry` 는 `OrcaAgentRuntimeManager` **바깥**에서 만들어 빌더로 주입한다 — manager 가
> 소유하지 않으며, 꺼내오는 getter 도 없다. 등록된 runtime 을 다시 찾고 싶으면
> `manager.getRuntime(AgentRuntimeId)` 를 쓴다.

---

## 체크리스트

새로운 LiveSession 통합 지점에서 확인할 사항:

### 필수

- [ ] `try-with-resources` 또는 `finally { session.close(); }` 로 핸들을 반드시 닫는가?
- [ ] `close()` 호출 이후 `submit()` / `offerAsync()` 를 호출하지 않는가?
- [ ] 같은 `SessionId` 를 동시에 두 라이브 세션에서 열지 않는가?
- [ ] busy 여부를 `status()` 가 아니라 `offerAsync` 의 `SubmitOutcome` 으로 판단하는가?
- [ ] `LiveSessionFactory.open()` 호출 시 `NullPointerException`(args) 또는
      `IllegalArgumentException`(unknown agent) 을 적절히 처리하는가?
- [ ] `getFinalAnswer()` / `getErrorMessage()` 가 **nullable String** 임을 감안했는가? (`Optional` 이 아니다)

### 설계

- [ ] `AgentRuntime`, `SchedulingEngine`, `ScheduledTaskManager`, `OrcaAgentExecutor`, `SessionRecordStore` 를
      세션이 close 하지 **않는다**는 것을 팀이 인지하고 있는가?
- [ ] 재시작을 넘어 살아남아야 하는 값을 핸들이 아니라 `SessionRecord` 쪽에 두었는가?
- [ ] 멀티 인스턴스 배포 시 `SessionRecordStore` 구현체가 동시성을 지원하는가?
- [ ] `LiveSessionOptions.budget` 이 각 세션의 사용자 의도를 반영하는가? (default 는 unlimited!)
- [ ] `sourceAgentId` 를 설정하여 관측(observability)에 기여하는가?

### 테스트

- [ ] 다중 턴 연속성 테스트: `sessionId` 재사용 시 메시지 누적 확인
- [ ] `close()` 이후 `submit()` 호출 시 `IllegalStateException` 확인
- [ ] `close()` 가 `AgentRuntime` 을 닫지 않음을 Mockito `verify(context, never()).close()` 로 검증
- [ ] `LiveSessionOptions.defaults()` 가 `ExecutionBudget.unlimited()` 를 반환함을 확인
- [ ] `SessionRecordStore` 를 연결한 경우 hydrate / flush 왕복 확인 (`DefaultLiveSessionPersistenceTest` 참조)

---

## 관련 문서

- [LiveSession.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java)
- [DefaultLiveSession.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/DefaultLiveSession.java)
- [LiveSessionOptions.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSessionOptions.java)
- [LiveSessionFactory.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSessionFactory.java)
- [SubmitOutcome.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/SubmitOutcome.java)
- [SessionRecordStore.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordStore.java)
- [OrcaAgentExecutor.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java)
- [OrcaAgentRuntime.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java) — `close()` 정책 참조
- [scope-model.md](../../overview/scope-model.md) — 수명·소유권·소멸 책임의 전체 규칙
- [CLAUDE.md](../../../CLAUDE.md) — Scope & Scheduling Lifecycle 원칙
