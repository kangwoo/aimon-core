# LiveSession 튜토리얼 (입문자용)

> 처음 AIMON 을 배우는 사람을 위한 **에이전트 세션** 안내서. 용도, 필요한 이유, 그리고 단계별 구현 방법을 차례로 설명합니다.

이 문서는 한 가지 질문에서 출발합니다.

> "에이전트한테 말을 한 번 거는 것"과 "에이전트와 대화를 한다"는 어떻게 다를까?

`LiveSession` 은 바로 그 차이를 메우는 컴포넌트입니다.

> 📌 옛 이름을 본 적이 있다면: 이 타입은 예전에 `AgentSession` 이었고 대화 식별자는 `ConversationId` 였습니다. 지금은 `LiveSession` 과 `SessionId` 이며, 맨 단어 `AgentSession` 은 타입 이름으로 **금지**되어 ArchUnit 이 빌드에서 막습니다. 옛 이름 ↔ 새 이름 표는 [`CHANGELOG.md`](../../../CHANGELOG.md) 에 있습니다.

## 목차

1. [큰 그림: LiveSession 이 뭔가요](#1-큰-그림-livesession-이-뭔가요)
2. [왜 필요한가요 — 세션이 없을 때 생기는 문제](#2-왜-필요한가요--세션이-없을-때-생기는-문제)
3. [네 가지 수명(Scope) 이해하기](#3-네-가지-수명scope-이해하기)
4. [핵심 컴포넌트 한눈에 보기](#4-핵심-컴포넌트-한눈에-보기)
5. [단계별 구현 가이드](#5-단계별-구현-가이드)
   - [Step 1. Hello, LiveSession (한 턴)](#step-1-hello-livesession-한-턴)
   - [Step 2. 다중 턴 대화 만들기](#step-2-다중-턴-대화-만들기)
   - [Step 3. 옵션과 예산(Budget) 적용](#step-3-옵션과-예산budget-적용)
   - [Step 4. close() 안전하게 다루기](#step-4-close-안전하게-다루기)
   - [Step 5. 인터랙티브 CLI 루프 (조립)](#step-5-인터랙티브-cli-루프-조립)
6. [자주 하는 실수와 해결](#6-자주-하는-실수와-해결)
7. [다음 단계](#7-다음-단계)
8. [용어집](#8-용어집)

---

## 1. 큰 그림: LiveSession 이 뭔가요

`LiveSession` 은 **한 세션에 대해 대화를 진행하는 핸들**을 나타내는 객체입니다.

비유하자면:

| 비유 | AIMON 컴포넌트 |
|------|----------------|
| 식당 자체 (영업 시작~종료) | `OrcaAgentExecutor` (애플리케이션) |
| 셰프(요리 준비, 도구, 메뉴 보유) | `AgentRuntime` (에이전트) |
| 테이블에 남아 있는 주문 기록 | `SessionRecord` (세션 — 영속) |
| 그 테이블을 지금 담당하는 웨이터 | **`LiveSession`** (라이브 세션 — 일시적) |
| 손님이 한 마디 주문하는 행위 | `session.submit("...")` (한 턴) |

손님이 바뀌어도 셰프와 식당은 그대로 있고, 한 테이블에서는 여러 번 주문(턴)이 이어집니다. 웨이터가 교대해도 테이블의 주문 기록은 남습니다 — 이것이 `LiveSession`(웨이터)과 `SessionRecord`(기록)를 굳이 구분하는 이유입니다.

### 한 줄 정의

> **LiveSession 은 `SessionId` 한 개에 묶여 있는, 다중 턴(multi-turn) 대화를 안전하게 진행하기 위한 노드 로컬 퍼사드(facade) 다.**

---

## 2. 왜 필요한가요 — 세션이 없을 때 생기는 문제

`OrcaAgentExecutor` 만 가지고도 에이전트를 호출할 수 있습니다. 그런데 직접 쓰면 호출자가 **매번 다음을 챙겨야** 합니다:

1. `SessionId` 를 어디에 저장하고 어떻게 재사용할지
2. `OrcaAgentExecutionRequest` 를 빌더로 조립
3. `ExecutionBudget`(반복 횟수/토큰 상한 등)을 매 요청마다 주입
4. 다 끝났을 때 어떤 자원을 닫아야 하고, **어떤 것은 닫으면 안 되는지** 판단

```java
// LiveSession 없이 쓰는 경우 — 호출자가 모든 걸 신경써야 함
SessionId sessionId = SessionId.generate();          // 어디에 보관하지?
ExecutionBudget budget = ExecutionBudget.builder().maxIterations(10).build();

OrcaAgentExecutionRequest req = OrcaAgentExecutionRequest.builder()
        .userInput("안녕")
        .sessionId(sessionId)     // 매번 직접 주입
        .budget(budget)           // 매번 직접 주입
        .build();
OrcaAgentExecutionResult r = executor.execute(agentRuntime, req);

// 두 번째 턴: 같은 sessionId 를 또 직접 가져와서 빌더에…
// 종료할 때: agentRuntime 을 닫아야 하나? executor 를 닫아야 하나? 헷갈림.
```

`LiveSession` 은 이 모든 것을 **한 번 설정하고 잊어버리도록(set once, forget)** 만들어 줍니다.

```java
try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    session.submit("안녕");
    session.submit("방금 내가 뭐라고 했지?");   // sessionId/budget 자동 주입, 기록 자동 연결
}
```

### 정리

| 책임 | LiveSession 없이 | LiveSession 사용 시 |
|------|-------------------|----------------------|
| `SessionId` 보관 | 호출자가 직접 | 핸들이 한 번 갖고 재사용 |
| 요청(`OrcaAgentExecutionRequest`) 조립 | 호출자가 매번 | `submit(input)` 내부에서 자동 |
| 기본 `ExecutionBudget` | 매번 빌더에 추가 | `LiveSessionOptions` 에 1회 |
| 종료 시 자원 정리 | 무엇을 닫아야 하는지 판단 필요 | `try-with-resources` 한 줄 |

---

## 3. 네 가지 수명(Scope) 이해하기

세션을 제대로 다루려면 **무엇이 얼마나 오래 살아 있는가**를 먼저 이해해야 합니다. AIMON 은 컴포넌트를 4단계 수명으로 나눕니다.

```
[ Application 수명 ] ←—— 가장 오래 산다
   SchedulingEngine, OrcaAgentExecutor, LlmClient, AgentRegistry, SessionRecordStore …
        │
        ├─[ Agent 수명 ] ←—— (Agent, discriminator) 단위
        │   AgentRuntime (= ToolRegistry + HookRegistry + MCP …)
        │      │
        │      ├─[ Session 수명 ] ←—— SessionId 단위, 영속
        │      │   SessionRecord, SessionTotals, budgetOverride, SessionTranscript
        │      │      │
        │      │      ├─[ Live session 수명 ] ←—— 가장 짧다. 노드 로컬
        │      │      │   LiveSession 핸들, 메시지 큐, 이벤트 publisher
        │      │      │
        │      │      └─[ Live session 수명 ]
        │      │          (같은 세션을 나중에 다시 연 핸들)
        │      │
        │      └─[ Session 수명 ]
        │          (같은 에이전트의 다른 세션)
        │
        └─[ Agent 수명 ]
            (다른 에이전트의 runtime)
```

### 핵심 직관

- **Application** 은 앱이 켜지면 만들어지고, 앱이 종료될 때 닫힙니다.
- **Agent** 는 에이전트별 1개. 같은 에이전트의 모든 세션이 **공유**합니다 (도구·훅·MCP 연결을 매 세션마다 다시 만들면 너무 비쌉니다).
- **Session** 은 사용자에게 보이는 대화 단위이고 **영속**됩니다. 앱을 재시작해도 남습니다.
- **Live session** 은 그 세션을 "지금 이 프로세스에서" 진행하는 핸들입니다. 프로세스가 죽으면 같이 사라집니다.

> 💡 한 `SessionRecord` 에 `LiveSession` 은 **0..N** 개입니다. 아무도 대화 중이 아니면 0개이고, idle 축출·재시작·노드 이동을 거치며 여러 핸들이 차례로 같은 세션을 서빙할 수 있습니다. 그래서 **재시작을 넘어 살아남아야 하는 값은 핸들이 아니라 레코드에** 둡니다.

### 그래서 close() 규칙이 단순해집니다

> **`LiveSession.close()` 는 자기 수명(=라이브 세션)에 속한 것만 닫는다.**
> AgentRuntime 도, SchedulingEngine 도, Executor 도, SessionRecordStore 도 절대 건드리지 않는다.

이 규칙을 어기면 어떤 일이 벌어지는지는 [Step 4](#step-4-close-안전하게-다루기) 에서 다룹니다.

---

## 4. 핵심 컴포넌트 한눈에 보기

| 이름 | 역할 | 누가 만드나 |
|------|------|-------------|
| `LiveSession` | 대화 진행의 입구 (submit/offerAsync/close) | `LiveSessionFactory.open()` |
| `DefaultLiveSession` | `LiveSession` 의 표준 구현체 | 팩토리 내부 |
| `LiveSessionOptions` | 세션 기본 설정 (budget, locale, sourceAgentId) | 호출자 (빌더) |
| `LiveSessionFactory` | "에이전트 이름 → 라이브 세션" 변환 | 부트스트랩에서 1회 |
| `SessionId` | 세션 식별자. 기록 연속성의 키 | `SessionId.generate()` |
| `SessionRecordStore` | 세션 레코드(대화 기록·누적치)의 저장소 | 부트스트랩에서 1회 |
| `AgentRuntime` | 에이전트의 도구/훅/MCP 묶음 (공유 자원) | 부트스트랩에서 1회 |
| `OrcaAgentExecutor` | 실제 ReAct 루프를 돌리는 엔진 | 부트스트랩에서 1회 |

> 📌 **부트스트랩에서 1회**라는 표현이 반복됩니다. 이는 "앱 시작 시 한 번만 만들고, 그 이후 세션마다 새로 만들면 안 된다"는 뜻입니다.

---

## 5. 단계별 구현 가이드

### Step 1. Hello, LiveSession (한 턴)

가장 작은 동작 단위부터 시작합니다.

```java
// 가정: factory 는 부트스트랩에서 이미 만들어져서 주입됐다.
SessionId sessionId = SessionId.generate();

try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    AgentExecutionResult result = session.submit("안녕!");
    // getFinalAnswer() 는 nullable String 이다 (Optional 이 아니다).
    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(응답 없음)"));
}
```

이 코드가 하는 일:

1. 새로운 세션 ID 발급
2. `"default"` 이름으로 등록된 에이전트로 라이브 세션 오픈
3. 한 턴 실행 → 결과 출력
4. `try-with-resources` 가 `session.close()` 자동 호출

> **요점:** 이 시점에 `AgentRuntime` 와 `OrcaAgentExecutor` 는 이미 어딘가에서 살아 있어야 합니다. 핸들은 그것들을 "사용"할 뿐 "소유"하지 않습니다.

---

### Step 2. 다중 턴 대화 만들기

같은 핸들에서 여러 번 `submit()` 하면 대화 기록이 자동으로 이어집니다.

```java
try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    session.submit("내 이름은 앨리스야.");
    AgentExecutionResult r = session.submit("내 이름이 뭐였지?");
    // → 에이전트는 "앨리스" 라고 답할 수 있다.
}
```

### 어떻게 가능한가?

```
session.submit(input)
   │
   ▼
OrcaAgentExecutionRequest (sessionId = sessionId, …)
   │
   ▼
OrcaAgentExecutor.execute(agentRuntime, request)
   │
   ├─ TranscriptManager.initialize(sessionId, systemPrompt)   ← 과거 메시지 로드
   ├─ ReAct 루프 실행
   └─ TranscriptManager.save(transcriptBuffer)                ← 새 메시지 저장
        └─ 내부적으로 SessionRecordStore.mergeFromSnapshot(snapshot)
```

`SessionId` 가 "기록의 키" 역할을 합니다. 같은 핸들은 항상 같은 ID를 쓰므로 메시지 히스토리가 연속됩니다. 핸들을 닫고 나중에 **같은 `SessionId` 로 다시 열어도** 기록은 이어집니다 — 그것이 세션이 영속되는 이유입니다.

> ⚠️ **주의**: 같은 `SessionId` 로 라이브 세션을 **두 개 동시에** 열면 동일 기록을 두 핸들이 경쟁적으로 수정해 데이터가 꼬일 수 있습니다. 순차적으로 다시 여는 것(닫았다가 나중에 재개)은 정상 시나리오입니다.

---

### Step 3. 옵션과 예산(Budget) 적용

`LiveSessionOptions` 로 세션의 기본 동작을 설정합니다.

```java
LiveSessionOptions options = LiveSessionOptions.builder()
        .budget(ExecutionBudget.builder()
                .maxIterations(20)
                .maxTokens(100_000)
                .maxWallClockDuration(Duration.ofMinutes(5))
                .build())
        .locale(Locale.KOREAN)
        .sourceAgentId("aimon-cli")   // 관측/감사용 식별자
        .build();

try (LiveSession session = factory.open(sessionId, "default", options)) {
    session.submit("긴 작업 시작…");
}
```

| 옵션 | 의미 | 미지정 시 기본값 |
|------|------|------------------|
| `budget` | 한 턴에 허용할 ReAct 반복/토큰/시간 상한 | `ExecutionBudget.unlimited()` |
| `locale` | 시스템 프롬프트 지역화 힌트 | 없음 (`Optional.empty()`) |
| `sourceAgentId` | 호출 출처 라벨 (CLI / Web / sub-agent 등) | 없음 |

> 💡 **예산은 턴 단위로 리셋됩니다.** `maxIterations=20` 은 "한 번의 `submit()` 에서 20회 ReAct 까지" 라는 뜻이지, 세션 전체에 누적되지 않습니다. 세션 전체 누적치는 `SessionTotals` 가 따로 들고 있고 `session.status().getSessionTotals()` 로 볼 수 있습니다.

---

### Step 4. close() 안전하게 다루기

#### ✅ 옳은 close — 핸들 자기 것만 닫는다

`DefaultLiveSession.close()` 가 실제로 정리하는 것:

- 자기 자신의 `closed` 플래그를 `true` 로
- 자기 자신이 등록한 메시지 큐 리스너 해제
- 진행 중 턴 참조(인터럽트 코디네이터 / budget tracker / turn id) 비우기
- (옵션) `OnSessionEnd` 훅 발사

#### ❌ 잘못된 close — 공유 자원까지 부숴버린다

```java
// ⚠️ 절대 이렇게 작성하지 말 것
@Override
public void close() {
    agentRuntime.close();          // ❌ 같은 에이전트의 다른 세션도 이 runtime 을 쓰는 중!
    schedulingEngine.close();      // ❌ 다른 세션의 예약 작업이 모두 사라짐!
    orcaAgentExecutor.close();     // ❌ 앱 전체가 마비
}
```

#### 왜 이게 중요한가

같은 에이전트로 동시에 100개의 세션이 진행 중이라고 상상해 보세요. 그 중 하나가 끝났다고 `AgentRuntime` 를 닫아 버리면 **나머지 99개 세션이 모두 죽습니다** (MCP 서브프로세스까지 함께 내려갑니다). `SchedulingEngine` 을 닫으면 모든 예약 작업이 사라집니다.

> 규칙: **핸들은 빌려 쓰는 사람**이지, 공유 자원의 주인이 아닙니다. 주인은 부트스트랩(앱 시작 코드) 입니다.

#### 검증 패턴

테스트로 이 규칙을 강제할 수 있습니다.

```java
@Test
void closeDoesNotTouchSharedResources() {
    // 실제 runtime 을 spy 로 감싸 close() 가 전파되지 않는지 관찰한다.
    OrcaAgentRuntime runtime = spy(createRuntime());

    DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), runtime, executor,
            LiveSessionOptions.defaults());
    session.submit("hi");
    session.close();

    verify(runtime, never()).close();   // 핸들은 공유 runtime 을 닫지 않는다
}
```

---

### Step 5. 인터랙티브 CLI 루프 (조립)

지금까지 배운 것을 합쳐서 작은 REPL 을 만들어 봅니다.

```java
public class HelloAgentRepl {

    private final LiveSessionFactory sessionFactory;

    public HelloAgentRepl(LiveSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public void run(String agentRef) {
        SessionId sessionId = SessionId.generate();
        LiveSessionOptions options = LiveSessionOptions.builder()
                .budget(ExecutionBudget.builder().maxIterations(20).build())
                .sourceAgentId("hello-cli")
                .build();

        try (LiveSession session = sessionFactory.open(sessionId, agentRef, options);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("세션 시작: " + session.getSessionId());
            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine();
                if (input.isBlank() || "exit".equals(input)) break;

                AgentExecutionResult result = session.submit(input);
                if (result.isSuccess()) {
                    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(응답 없음)"));
                } else {
                    System.err.println("오류: " + Objects.requireNonNullElse(result.getErrorMessage(), "unknown"));
                }
            }
        }
        // try-with-resources 가 session.close() 호출
        // 공유 자원(executor, schedulingEngine, agentRuntime, sessionRecordStore) 은 그대로 살아 있음
    }
}
```

부트스트랩 쪽(예: Spring `@Configuration` 또는 `AgentSetupFactory`) 은 다음을 **앱 시작 시 1회** 수행합니다.

```java
// 1) 에이전트별 AgentRuntime 등록 (한 번만!)
runtimeManager.getOrCreateRuntime(bundle, fileSystem, credentialStore);

// 2) 세션 팩토리 생성 (마지막 인자는 생략 가능 — 생략하면 세션 누적치가 영속되지 않는다)
LiveSessionFactory factory = new LiveSessionFactory(
        agentRegistry,
        agent -> runtimeManager.getOrCreateRuntime(bundleFor(agent), fileSystem, credentialStore),
        executor,
        sessionRecordStore);

// 3) REPL 에 주입
new HelloAgentRepl(factory).run("default");
```

---

## 6. 자주 하는 실수와 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| `IllegalStateException: LiveSession has already been closed` | `close()` 이후 `submit()` 호출 | 핸들을 다시 `open()` 하거나, `try-with-resources` 범위를 넓혀라 |
| 두 번째 턴에서 첫 턴 내용을 기억 못 함 | 매 턴마다 `factory.open()` 으로 새 핸들 생성 → `SessionId` 가 매번 다름 | 같은 핸들에서 `submit()` 을 반복 호출하거나, 같은 `SessionId` 로 다시 열어라 |
| MCP/툴이 매번 재초기화됨 | 세션마다 `AgentRuntime` 를 새로 만들고 있음 | 부트스트랩에서 `getOrCreateRuntime` 로 한 번만 만들고 재사용 |
| 한 세션이 끝났더니 예약 작업이 모두 사라짐 | `close()` 가 `SchedulingEngine.close()` 를 호출 | 핸들은 공유 자원 close 하지 않는다 — 코드 제거 |
| 같은 사용자에 대해 응답이 섞임 | 같은 `SessionId` 로 라이브 세션을 동시에 두 개 열었음 | 한 세션에는 한 번에 한 핸들만 (멀티 노드라면 `SessionRouter` 사용) |
| `IllegalArgumentException: No agent registered under name` | `agentRef` 오타 또는 등록 누락 | `AgentRegistry` 에 등록된 이름 확인 |
| 앱을 재시작하니 세션 누적치가 0으로 돌아감 | `LiveSessionFactory` 를 `SessionRecordStore` 없이(3-arg) 만들었음 | 4-arg 생성자로 스토어를 주입 |
| `getFinalAnswer()` 에 `.orElse(...)` 를 쓰려니 컴파일 오류 | 이 접근자는 `Optional` 이 아니라 **nullable String** 반환 | `Objects.requireNonNullElse(...)` 등으로 처리 |

---

## 7. 다음 단계

이 튜토리얼을 끝낸 뒤 더 깊이 들어가고 싶다면:

- **개발 레퍼런스**: [`docs/features/session/agent-session-guide.md`](agent-session-guide.md) — API 계약, 동시성, 멀티 인스턴스 주의사항
- **수명 모델 전체 규칙**: [`docs/overview/scope-model.md`](../../overview/scope-model.md) — 무엇을 언제 닫아야 하는가
- **수명 모델 배경**: [`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md) — 왜 `AgentRuntime` 이 agent-scoped 인지
- **앱 임베딩**: [`docs/getting-started/embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md)
- **인터럽트 동작**: [`docs/features/agent-execution/interruptible-tools-guide.md`](../agent-execution/interruptible-tools-guide.md)
- **웹 환경 배포**: [`docs/features/session/web-session-deployment-guide.md`](web-session-deployment-guide.md)
- **스트리밍/큐**: `submitAsync`, `offerAsync`, `MessageQueueManager` 시그니처를 살펴보세요 ([`LiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java))

---

## 8. 용어집

| 용어 | 한 줄 설명 |
|------|-----------|
| **Agent** | 도구·프롬프트·정책의 묶음. 에이전트의 "정체성" |
| **AgentRuntime** | Agent 가 실제로 일하기 위한 런타임 환경 (도구 레지스트리, MCP 연결 등). agent-scoped, 공유 |
| **SessionRecord** | 세션의 영속 애그리게이트 (대화 기록, 누적치, budget override). `SessionId` 로 식별 |
| **LiveSession** | 그 세션을 지금 이 프로세스에서 진행하는 핸들. submit / close 만 알면 됨 |
| **SessionId** | 세션 식별자. 기록 연속성의 키 |
| **Turn** | 한 번의 `submit()` 호출 = 한 턴 (사용자 입력 → 에이전트 응답까지) |
| **TurnId** | 진행 중인 턴을 지목하기 위한 id (인터럽트 대상 지정용). 영속되지 않음 |
| **ReAct 루프** | "생각 → 도구 호출 → 관찰 → 생각" 의 반복. 한 턴 안에서 여러 번 돌 수 있음 |
| **ExecutionBudget** | 한 턴에 허용할 반복/토큰/시간 상한 |
| **SubmitOptions** | 한 턴에만 적용할 메타데이터 (사용자 정보, 시스템 프롬프트 변수 등) |
| **SubmitOutcome** | `offerAsync` 의 결과 — 지금 실행됐는지(`EXECUTED`) 큐에 들어갔는지(`QUEUED`) |
| **OrcaAgentExecutor** | ReAct 루프를 실제로 돌리는 엔진. 애플리케이션 수명 |
| **SessionRecordStore** | 세션 레코드의 저장소. 애플리케이션 수명. in-tree 구현체는 `InMemorySessionRecordStore` 뿐이며, 영속이 필요하면 인터페이스를 직접 구현해 주입한다 |
| **SchedulingEngine** | 예약된 작업을 실행하는 엔진. 애플리케이션 수명. **핸들이 절대 close 하지 않는다** |
| **Bootstrap** | 앱 시작 시 1회 실행되는 셋업 코드. AgentRuntime/Executor/팩토리 등을 준비 |

> 📌 "conversation(대화)" 이라는 단어는 이제 **LLM 과의 메시지 교환**만 뜻합니다 (`getConversationHistory()`). 수명을 가리킬 때는 쓰지 않습니다 — 그 자리는 `Session` / `LiveSession` 입니다.

---

> 마지막으로 한 줄만 기억하세요.
>
> **라이브 세션은 한 세션을 지금 진행하는 핸들이다. 자기 것만 닫고, 빌려 쓴 것은 살려둔다.**
