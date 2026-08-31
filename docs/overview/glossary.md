# 용어집 (Glossary)

AIMON 코드베이스에서 반복적으로 쓰이는 핵심 용어와 그 **수명(lifetime)** 을 정리한다.
같은 단어가 계층마다 다른 것을 가리키는 경우가 있어, 새 타입 이름을 정하거나 남의 코드를 읽을 때
이 문서를 기준으로 삼는다.

---

## 1. 한눈에 보기 — 수명 계층

| Scope | 대표 타입 | 식별자 | Lifetime |
|-------|----------|--------|----------|
| **Application** | `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`, `AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`, `KnowledgeStore`, `CredentialStore` | — | 앱 시작 ~ 종료 |
| **Agent** | `AgentRuntime` 및 그것이 소유한 `ToolRegistry` / `HookRegistry` / `McpClientManager`, `AgentEnvironmentSnapshot` | `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) | 세션들을 가로질러 유지 |
| **Session** | `SessionRecord`, `SessionTranscript`, `SessionTotals`, `budgetOverride` | `SessionId` | 세션이 존재하는 동안 — **영속** |
| **Live session** | `LiveSession`, 메시지 큐, 이벤트 publisher | (bound `SessionId`) | 한 노드의 프로세스 안, **일시적** |
| **Execution** | 에이전트 작업 1회 일반 — **세션이 없을 수도 있다** (서브에이전트 포크, 스킬 포크, rewake 리플레이, 스케줄 루틴) | `ExecutionId` (**세션 없는 실행에만** 발급 — §4) | 턴의 상위 개념 |
| **Turn** | `AgentExecutionRequest` / `AgentExecutionResult`, `ExecutionBudget` · `BudgetTracker`(턴 전용은 아니다 — §4) | `TurnId` (주소 지정용, **비영속**) | 사용자 입력 1건 처리 |
| **Iteration** | ReAct 루프 1회 (LLM 호출 + 도구 실행) | — | 턴 내부 |

IMPORTANT: 표의 **Session** 과 **Live session** 칸에 **`Session` 이나 `AgentSession` 이라는 타입은 없다.** 두 이름 모두
어느 쪽 수명인지 말해 주지 않아 실제로 혼동을 만들었기 때문에 금지되었고,
`SessionNamingArchitectureTest` 가 빌드를 깨뜨린다. 영속 쪽은 `Session*`, 노드 로컬 핸들은
`LiveSession*` 이다.

소유권과 소멸 책임(누가 만들고 누가 닫는가), 마커 인터페이스, 새 타입 배치 규칙은
[`scope-model.md`](scope-model.md) 에 있다. 이 문서는 **용어의 뜻**을, `scope-model.md` 는
**수명 규칙**을 담당한다.

자세한 배경은 [`design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) 참조.

---

## 2. 세션 vs 라이브 세션 — 가장 자주 혼동되는 쌍

**둘은 동의어가 아니다.** 관계는 비대칭이다.

```
one SessionRecord (영속, SessionId 로 식별)  :  0..N LiveSession (일시적, 노드 로컬)
```

| | `SessionRecord` | `LiveSession` |
|---|---|---|
| 정체 | 메시지 이력을 담는 **영속 애그리게이트** | 턴을 실행하는 **노드 로컬 핸들** |
| 저장 | `SessionRecordStore` (Mongo/Postgres/Redis/in-memory) | 저장 안 됨 — JVM 힙 |
| 식별 | `SessionId` | 바인딩된 `SessionId` 를 참조할 뿐, 자체 id 없음 |
| 개수 | 1 | 0개(아무도 대화 중이 아님)일 수도, 시간에 걸쳐 N개일 수도 |
| 소멸 | 명시적 삭제 | idle-TTL 축출, 프로세스 재시작, 다른 노드로 핸드오프 |

**왜 중요한가.** 라이브 세션은 언제든 사라지고 다시 만들어진다. 따라서 재시작·축출·노드 이동을 넘어
살아남아야 하는 상태는 반드시 **레코드 쪽**에 있어야 한다:

- `SessionTotals` — 완료된 턴들의 누적 통계. 같은 `SessionId` 로 새 핸들을 열면 0부터가 아니라 복원된다.
- `budgetOverride` — 런타임에 바뀐 `ExecutionBudget`(예: REPL `/budget`). 재개 시 opener 기본값을 이긴다.

둘 다 `SessionRecord` 의 side field 이고 `at.aimon.core.agent.session.store` 에 있다. 이름에
`LiveSession` 이 들어가면 "핸들이 죽으면 같이 죽는 값" 처럼 읽히지만 실제로는 그렇지 않기 때문이다.
라이브 세션은 이 둘을 좁은 SPI 가 아니라 **`SessionRecordStore` 를 직접 들고** 오간다 — 열 때 레코드에서
한 번 읽고, `setTotalsAndBudgetOverride` 로 두 값을 **한 쌍으로 절대값 기록**한다.

sticky routing 없이 멀티 노드로 확장할 수 있는 근거도 이 비대칭성이다 — 자세한 내용은
[`design/session/routing.md`](../design/session/routing.md).

### 새 타입 이름을 정할 때

| 값의 성격 | 이름에 쓸 단어 | 놓을 패키지 |
|-----------|---------------|------------|
| 재시작 후에도 복원되어야 함 | `Session*` | `agent.session.store` (전사 자체는 `agent.session.transcript`) |
| 프로세스가 죽으면 같이 사라져도 무방 | `LiveSession*` | `agent.session` |
| 에이전트 단위로 한 번 모으면 되는 값 | `Agent*` | `agent` |

비대칭이 눈에 걸릴 수 있다 — 영속 쪽은 `Session*` 인데 일시적 쪽만 `Live` 라는 수식어를 달고 있다.
의도한 것이다. 사용자에게 "세션"은 재시작을 넘어 이어지는 그것이므로 무표기(unmarked) 이름을 영속 쪽에
주고, 그 세션을 지금 이 노드에서 굴리고 있는 물체에만 표기를 붙인다.

### "conversation" 은 폐기어가 아니다

이 개편이 "conversation" 을 없앤 것으로 읽으면 잘못된 이름을 새로 만들게 된다. 그 단어는
**수명을 가리키는 자리에서만** 빠졌고, **LLM 과의 메시지 교환**을 가리키는 자리에는 그대로 남아 있다 —
`SessionSnapshot.getConversationHistory()`, `/compact` 의 "Conversation compacted", 컴팩션 프롬프트 문구.
메시지 이력을 다루는 새 타입이라면 `Transcript*` 또는 `*ConversationHistory*` 가 맞고, 수명을 말하려면
`Session*` 이 맞다.

---

## 3. "Session" 이라는 단어의 여러 용법

`Session` 은 AIMON에서 최소 다섯 가지 다른 수명을 가리킨다. 문맥 없이 이 단어만 보고 수명을 추측하면 안 된다.

| 타입 | 실제 의미 | 수명 |
|------|----------|------|
| `SessionRecord` (`aimon-core`) | `SessionId` 로 식별되는 영속 애그리게이트 | 명시적 삭제까지 — **영속** |
| `LiveSession` (`aimon-core`) | 그 세션 하나에 대해 턴을 실행하는 노드 로컬 핸들 | 열기 ~ `close()` |
| `LiveSessionCache` 항목 (`aimon-session-routing`) | 멀티 노드 라우팅·캐싱 계층(`SessionRouter`)이 캐시한 위 `LiveSession` | idle TTL 또는 `maxEntries` 축출까지 |
| `ReplSession` (`aimon-cli`) | CLI 프로세스 한 번의 대화형 실행 | CLI 실행 ~ 종료 |
| `BrowserSession` (`aimon-browser-playwright`) | Playwright `BrowserContext` + 활성 Page | 브라우저 컨텍스트 수명 |

바로 이 다의성 때문에 **맨 `Session` 과 `AgentSession` 은 타입 이름으로 쓸 수 없다** — 둘 다 위 다섯 줄
중 어느 것인지 말해 주지 않는다. `SessionNamingArchitectureTest` 가 강제한다.

### 알려진 오칭(misnomer)

- `OnSessionStartHook` / `OnSessionEndHook` — `LiveSession` 의 열기/닫기에 발화한다(세션 시작이 아니다).
  같은 세션이 재개되면 다시 발화할 수 있다.
- `TranscriptManager.initialize` — 세션당 1회가 아니라 **턴당 1회** 호출된다.
- 영속 이름의 `conversation` — Java 식별자는 `Session*` 로 개명되었지만 와이어 키
  (`"conversationId"`), Mongo 컬렉션(`conversation_locks` 등), Postgres 테이블·채널(`conversation_*`)은
  **의도적으로 동결**되었다. 어긋나 보이는 것이 정상이다.

### 해소된 오칭 — 스킬 승인 저장소

승인 저장소는 이름이 두 번 바뀌었고, 그 과정에서 **`SessionApprovalStore` 라는 이름이 다른 뜻으로
재사용되었다**. 옛 코드를 읽을 때 가장 헷갈리는 지점이다.

| 옛 이름 | 현재 이름 | 실제 키 |
|---------|----------|---------|
| `SessionApprovalStore` (이름이 거짓말을 하고 있었다) | `AgentApprovalStore` (`…policy.agent`) | `AgentRuntimeId` |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` | — |
| `ConversationApprovalStore` | **`SessionApprovalStore`** (`…policy.session`) | `SessionId` |
| `ConversationScopedSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` | — |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` | — |

승인의 도달 범위는 사용자가 고르고, **그 의미는 개명으로 바뀌지 않았다**.

| 사용자의 답 | 저장소 | 도달 범위 |
|-------------|--------|----------|
| 이번 턴만 허용 | `PendingApprovalStore` (`…policy.pending`) | 그 턴 |
| 이 세션에서 항상 허용 (`y`) | `SessionApprovalStore` (`…policy.session`) | 그 `SessionId` **와 그 세션이 위임한 실행**(서브에이전트 포크·스킬 포크·포그라운드 워크플로) — 세션이 릴리스·삭제되면 함께 사라짐 |
| 이 에이전트에서 항상 허용 (`a`) | `AgentApprovalStore` (`…policy.agent`) | 그 `AgentRuntimeId` 의 **모든 세션**, TTL 없음, `/clear` 로도 안 지워짐 |

정책 체인은 **좁은 것부터** 본다 — pending → session → agent → 규칙. 되돌리는 방법도 같은
경계를 따른다: `/revoke` 는 이 세션의 승인만, `/revoke --agent` 는 에이전트 전역 승인까지 지운다
(`RevokeApprovalsCommand`).

서브에이전트 포크는 부모의 `AgentRuntimeId` 는 공유하지만 `SessionId` 는 **아예 없다** — 포크는
세션의 턴이 아니므로 툴 컨텍스트에 `SESSION_ID` 가 실리지 않고 `EXECUTION_ID` 만 실린다. 따라서
`SkillInvocationRequest.getSessionId()` 는 포크 경로 전체에서 비어 있고, 포크는 자기를 띄운 세션의
id 를 `invokingSessionId` 로 따로 들고 다니며 세션 정책이 그것을 조회한다 — 이것이 없으면 포크의
모든 스킬 호출이 규칙 fallback 의 `ASK` 로 떨어지고, 포크는 물을 채널이 없으므로 사실상 `DENY` 가
된다. 상속은 **양방향**이다(거부도 함께 물려받는다).

---

## 4. 핵심 타입 사전

### Agent 계층

- **`Agent`** — 에이전트의 설정(이름, 시스템 프롬프트, 모델, max iterations). 실행 상태를 갖지 않는 불변 정의.
- **`AgentRuntime`** — 한 에이전트의 실행 환경(도구 레지스트리, 훅 레지스트리, MCP 클라이언트 등).
  **agent-scoped** — 세션마다 만들지 않는다.
- **`AgentRuntimeId`** — `agent:<name>` 또는 `agent:<name>:<discriminator>`. 결정론적으로 파생되므로
  cron 재발화나 다른 노드에서도 같은 값이 나온다. `from(Agent)` / `from(Agent, String)` 으로 발급하며
  `generate()` 는 존재하지 않는다.
- **`discriminator`** — 같은 `Agent` 정의를 테넌트/사용자 등으로 쪼개고 싶을 때 컨텍스트 id 에 덧붙이는 문자열.
- **`AgentEnvironmentSnapshot`** — 작업 디렉토리, 스냅샷 시각, `Environment`, 사용자 확장 맵을 담은 불변 값.
  `AgentRuntimeId` 로 memoize 되므로 **agent-scoped** 다(세션마다 다시 모으지 않는다).
  `AgentEnvironmentSnapshotProvider` 가 collect-once 를 보장한다.
- **`AgentExecutor`** — 컨텍스트 + 요청을 받아 ReAct 루프를 도는 실행기. 기본 구현은 `OrcaAgentExecutor`.

### Session 계층 (영속)

패키지는 `at.aimon.core.agent.session` 아래 다섯으로 갈린다 — `store` (레코드·리스·저장소·와이어 코덱),
`transcript` (메시지 이력), `inbox` (크로스 노드 우편함), `signal` (크로스 노드 pub/sub),
`idempotency` (at-most-once 제출). 뒤의 셋은 `aimon-session-base` 에 있던 것을 core 로 내린 것이며,
그 결과 분산 백엔드(`aimon-session-{redis,postgres,mongodb}`)는 라우팅 모듈 없이 `aimon-core` 만 보고
구현한다. 소스 레벨 이동이므로 와이어 포맷·키 prefix·DDL 은 하나도 바뀌지 않았다.

- **`SessionId`** — 세션의 영속 식별자. 대화 이력, 리스, 인박스, 승인, 이벤트 프레임이 모두 이 값으로 조인된다.
- **`SessionRecord`** — `SessionTranscript` + side field(`sessionTotals`, `budgetOverride`,
  `compactionFailureCount`, `agentRef`). `SessionRecordView` 는 그 읽기 전용 뷰다.
- **`SessionTranscript`** — 시스템 프롬프트 + 메시지 이력을 담는 **불변** 값. `withSystemPrompt` /
  `append` 는 새 인스턴스를 돌려준다.
- **`SessionRecordStore`** — 레코드 저장소 추상화. 구현체 교체로 in-memory ↔ Mongo/Postgres/Redis 전환.
  담는 값은 session-scoped 지만 **저장소 자신은 application-scoped** 다 — 모든 세션보다 오래 산다.
- **`SessionLeaseStore`** — 어느 노드가 어느 세션을 쥐고 있는지에 대한 공유 권위(홀더 선출 + 펜싱).
  역시 application-scoped.
- **`SessionStore`** — 위 두 저장소를 문 하나로 묶은 **노드 스코프** 합성물. `claim` 이 리스 선출 →
  에이전트 바인딩 검증 → 레코드 프로비저닝을 **그 순서로** 수행한다(선출에서 진 노드는 레코드를 아예
  건드리지 않으므로 분산 트랜잭션이 필요 없다). 이 노드가 쥔 리스를 추적하므로 `records()` 가 호출자마다
  펜싱 토큰을 ReAct 콜체인으로 흘리지 않고도 쓰기를 펜싱할 수 있다 — 한 JVM 에 매니저가 둘이면
  **같은 두 백엔드 위에 스토어도 둘** 만들어야 한다(공유 금지).
- **`SessionSnapshot`** — 전사(`sessionId` + 시스템 프롬프트 + `getConversationHistory()`)의 불변
  스냅샷이며 side field 는 **하나도 담지 않는다**. 그래서 `SessionRecordStore.mergeFromSnapshot` 이 저장 시
  기존 레코드에서 `compactionFailureCount` / `agentRef` / `sessionTotals` / `budgetOverride` 네 개를 되살린다.
- **`SessionTotals`** — 완료된 턴들의 누적 (턴 수, iteration, 토큰). 진행 중인 턴은 제외.
- **`SessionRecordStore.setTotalsAndBudgetOverride`** — 라이브 세션이 자기가 소유한 두 side field
  (`sessionTotals`, `budgetOverride`)를 되쓰는 단 하나의 원자적 primitive. 델타가 아니라 **절대값**이므로
  중복 호출이 턴을 두 번 세지 않는다. 레코드가 없으면 no-op. 한때 이 자리에 `ConversationStatePersistence` 라는
  좁은 SPI 가 있었으나, 라이브 세션이 레코드를 직접 소유하게 되면서 ISP 좁히기가 무의미해져 삭제되었다.
- **`SessionRecordCodec`** (`…session.store`) — 분산 `SessionRecordStore` 백엔드가 공유하는 인코딩.
  중립 문서는 `StoredSessionRecord`, 결과 투영은 `StoredAgentExecutionResult` 다. 전사 절반은
  `at.aimon.core.subagent.task.codec` 의 스냅샷 코덱에 위임한다(그 패키지 이름은 첫 소비자를 기록한
  것이지 제약이 아니다).
- **`SessionInbox`** (`…session.inbox`) — 세션의 크로스 노드 우편함. 아무 노드나 `deliver` 할 수 있고
  리스를 쥔 노드만 `collect` 한다 — 그 규칙을 강제하는 것은 SPI 가 아니라 라우팅 계층이다.
  application-scoped.
- **`SessionSignalBus`** (`…session.signal`) — 한 `SessionId` 를 구독한 모든 노드에게 `SessionSignal`
  (INTERRUPT · EVICT · MESSAGE_ENQUEUED · EVENT · STATUS …)을 팬아웃한다. 발행은 at-least-once 이므로
  수신자는 같은 신호가 두 번 와도 견뎌야 한다. application-scoped.
- **`IdempotencyStore`** (`…session.idempotency`) — 클라이언트가 고른 키로 at-most-once 제출을 보장하고,
  `IN_FLIGHT` 항목의 짧은 2차 TTL 로 홀더 유실을 감지한다. **메시지 내용으로 dedup 하지 않는다** —
  그것은 `SessionInbox` 의 일도, 이 저장소의 일도 아니다. application-scoped.

### Live session 계층 (일시적)

- **`LiveSession`** — `submit` / `submitAsync` / `offerAsync` 로 턴을 실행하고, `events()` 로 관찰하고,
  `status()` 로 진단하는 파사드. `close()` 는 **핸들 자원만** 정리한다 —
  `AgentRuntime` 이나 스케줄링 컴포넌트를 닫으면 안 된다.
- **`LiveSessionOptions`** — 핸들의 기본 `ExecutionBudget`, locale, source agent id. 레코드에
  `budgetOverride` 가 있으면 그것이 이 기본값을 이긴다.
- **`LiveSessionStatus`** — best-effort 관찰 스냅샷. **제어 게이트로 쓰지 말 것**(읽는 순간과 행동하는 순간
  사이에 상태가 바뀔 수 있다). 턴 시작 가능 여부는 `offerAsync` 의 `SubmitOutcome` 으로 판단한다.
- **`SubmitOutcome`** — 입력이 즉시 실행됐는지(`EXECUTED`), 큐에 적재됐는지(`QUEUED`).
- **`OpenAttributes`** — 핸들을 여는 시점에만(캐시 미스 시에만) opener 로 전달되는 호출자 도메인 속성.
  턴 단위 메타데이터는 `SubmitOptions` 를 쓴다.
- **`SessionRouter` / `LiveSessionCache` / `LiveSessionOpener`** (`aimon-session-routing`) — 요청을 세션의
  현재 홀더로 보내고, 핸들을 캐시하고, 캐시 미스 시 새로 여는 멀티 노드 계층. 이름의 `Session` 은
  라우팅 대상(영속 세션)을, `LiveSession` 은 캐시된 물체를 가리킨다.

### 실행 단위

IMPORTANT: **`turn` · `iteration` · `execution` 은 서로 바꿔 쓸 수 없다** — 단어 하나에 뜻 하나다.
이름·주석·로그·사용자 가시 문자열 전부에 적용된다.

| 단어 | 뜻 | 단위 |
|------|-----|------|
| **turn(턴)** | 세션에 들어온 사용자 입력 1건의 처리 | `executor.execute(runtime, request)` 1회 |
| **iteration(이터레이션)** | ReAct 루프 1회 (LLM 호출 → 도구 실행 → 관찰) | 턴 내부 |
| **execution(실행)** | 에이전트 작업 1회 일반 — **세션이 없을 수도 있다** (서브에이전트 포크, 스킬 포크, rewake 리플레이, 스케줄 루틴) | 턴의 상위 개념 |

세션 없는 실행에는 `SessionId` 가 아예 없고 `ExecutionId` 가 그 정체성이다(§3). 따라서 **모든 실행이
턴인 것이 아니라 턴이 실행의 한 종류**다 — 두 경로가 공유하는 것(취소 신호, 예산 트래커)을 설명할 때는
`turn` 이 아니라 `execution` 이라고 쓴다.

예외 하나: `assistant turn` / `user turn` 은 트랜스크립트의 **메시지 role** 을 가리키는 LLM API 표준
어휘이므로 허용한다. 단 **반드시 한정어를 붙인다** — 맨 `turn` 은 언제나 표의 첫 줄 뜻이다.

- **Turn(턴)** — 사용자 입력 1건에 대한 처리 전체. 세션 전체의 누적치는 `SessionTotals` 가 따로 들고 있다.
- **`TurnId`** (`at.aimon.core.agent.session`) — 턴 1건의 식별자. 턴은 컴포넌트를 소유하는 스코프가 아니라 실행
  단위이므로 이 id 는 **주소 지정용**이다 — 인터럽트를 특정 턴에 겨누고(`interrupt(sessionId, turnId, reason)`),
  크로스 노드 `SignalKind.EVENT` 프레임을 어느 턴이 냈는지 표시한다. 제출 시점에 `TurnId.generate()` 로 발급되며
  턴이 끝나면 의미가 없다 — **세션 상태로 영속하지 말 것**. id 가 **없는 것은 "알 수 없음 → 드롭" 이 아니라 옛 의미**다
  (인터럽트는 라이브 세션 스코프, 이벤트는 세션 전체 전달). 승인 대기로 *중단된* 턴에만 발급되어 `/approve` 의 핸들이 되는
  `skill.policy.pending.PendingTurnId` 와는 다른 타입이다(같은 턴을 가리키는 무관한 두 식별자).
- **Iteration(이터레이션)** — 턴 안에서의 ReAct 루프 1회 (LLM 호출 → 도구 실행 → 관찰).
- **`ExecutionBudget` / `BudgetTracker`** — **실행 단위(턴 또는 포크)당** iteration·토큰·시간 상한과 그
  실시간 계측. 세션 단위가 아니다. "턴 또는 포크" 는 헤지가 아니라 전수다 — main 소스에서
  `new BudgetTracker(` 는 정확히 **2곳**뿐이다: `OrcaAgentExecutor`(턴)와 `DefaultSubagentExecutor`(포크).
  (grep 은 3건을 뱉지만 세 번째는 `BudgetTracker` javadoc 안의 사용 예시 코드이지 생성 지점이 아니다.)

### 확장점

- **`Tool`** — 에이전트가 외부와 상호작용하는 단위. `execute()` 는 예외를 던지지 않고 `ToolResult` 를 반환한다.
- **`Skill`** — 프롬프트·도구·훅을 묶은 선언적 패키지 (Agent Skills 표준 + AIMON 확장).
- **`Hook`** — 라이프사이클 개입점 (`OnStart`, `PreTool`, `PostTool`, `OnStop`, `OnSessionStart/End` …).
- **`Subagent`** — 부모 턴 안에서 격리된 컨텍스트로 실행되는 하위 에이전트.
- **`Principal`** (`at.aimon.core.base`) — 신원 표현 (user / group / system / service).

### 스케줄링

- **`ScheduledTask`** — cron/일회성 예약 작업. `boundRuntimeId` 는 **agent-scoped** id 를 참조하므로 원래 세션이
  끝난 뒤 재발화해도 런타임이 resolve 된다.
- **`SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor`** — **application-scoped**.
  `AgentRuntime` 소멸이나 라이브 세션 종료와 함께 닫으면 안 된다.

---

## 관련 문서

- [`scope-model.md`](scope-model.md) — 수명·소유권·소멸 책임 규칙
- [`architecture.md`](architecture.md) — 핵심 추상화 레퍼런스
- [`../develop/agent-session-guide.md`](../features/session/agent-session-guide.md) — `LiveSession` API 와 이벤트 스트리밍
- [`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) — agent scope 재정의 배경
- [`../design/session/session-model.md`](../design/session/session-model.md) — 세션 상태 영속화 설계
