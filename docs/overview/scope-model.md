# 스코프 모델 (Scope Model)

AIMON 컴포넌트의 **수명(lifetime)**, **소유권(ownership)**, **소멸 책임(teardown)** 을 정의한다.

이 문서는 "이 값을 어디에 두어야 하는가", "이것을 언제 닫아야 하는가", "새 타입 이름을 무엇으로
지어야 하는가" 에 대한 기준점이다. 용어 하나하나의 뜻은 [`glossary.md`](glossary.md) 를,
이 모델이 나오게 된 설계 배경은
[`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md)
를 참조한다.

---

## 1. 스코프 계층

컴포넌트 수명은 **4단계**다. 여기에 실행 단위 3개(Execution, Turn, Iteration)가 얹힌다 —
실행 단위는 컴포넌트를 소유하지 않고, 한 번의 실행 구간만 가리킨다.

| Scope | 대표 컴포넌트 | 식별자 | Lifetime |
|-------|--------------|--------|----------|
| **Application** | `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`, `AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`, `KnowledgeStore`, `CredentialStore` | — | 앱 시작 ~ 종료 |
| **Agent** | `AgentRuntime` 과 그것이 소유한 `ToolRegistry` / `HookRegistry` / `McpClientManager`, `AgentEnvironmentSnapshot` | `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) | `(Agent, discriminator)` 단위, 세션들을 가로질러 유지 |
| **Session** | `SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript` | `SessionId` | 세션이 존재하는 동안 — **영속** |
| **Live session** | `LiveSession`, 메시지 큐, 이벤트 publisher | (바인딩된 `SessionId` 참조) | 노드 로컬, **일시적** (열기 ~ `close()`) |
| *(실행 단위)* **Execution** | 에이전트 작업 1회 일반 — **세션이 없을 수도 있다** (서브에이전트 포크, 스킬 포크, rewake 리플레이, 스케줄 루틴) | `ExecutionId` (**세션 없는 실행에만** 발급 — 턴은 `SessionId` + `TurnId` 로 식별된다) | 턴의 상위 개념 |
| *(실행 단위)* **Turn** | `AgentExecutionRequest` / `AgentExecutionResult`, `ExecutionBudget` · `BudgetTracker`(턴 전용은 아니다 — 아래) | `TurnId` (주소 지정용, **비영속**) | 사용자 입력 1건 처리 |
| *(실행 단위)* **Iteration** | ReAct 루프 1회 (LLM 호출 → 도구 실행 → 관찰) | — | 턴 내부 |

> 과거 문서의 "3-tier scope" 표현은 영속 세션과 노드 로컬 핸들을 한 칸으로 묶어 세던 시절의
> 잔재다. 둘은 수명이 다르므로 별도 tier 로 센다. 그 시절 두 칸의 이름은 각각
> "Conversation" 과 "Session" 이었다 — 지금은 "Session" 과 "live session" 이다(§7).

`ExecutionBudget` 은 **실행 단위(턴 또는 포크)당** 적용된다 — 세션 단위가 아니다. 세션 전체의 누적치는
`SessionTotals` 가 따로 들고 있다. "턴 또는 포크" 는 헤지가 아니라 전수다: main 소스에서
`new BudgetTracker(` 는 정확히 **2곳**뿐이며 `OrcaAgentExecutor`(턴)와 `DefaultSubagentExecutor`(포크)
가 전부다. (grep 은 3건을 뱉지만 세 번째는 `BudgetTracker` javadoc 안의 사용 예시 코드다.)

턴과 포크가 이렇게 예산 트래커를 공유하는 것은 우연이 아니다 — **턴은 실행(execution)의 한 종류**이고,
포크는 세션이 없는 다른 종류다. 두 경로가 공유하는 것을 설명할 때 `turn` 이라고 쓰면 안 되는 이유가
이것이다. 세 단어(`turn` / `iteration` / `execution`)의 규칙은
[`glossary.md` §4 › 실행 단위](glossary.md) 에 있다.

`TurnId` 가 식별자 칸에 있다고 해서 Turn 이 스코프로 승격된 것은 아니다 — 턴은 여전히 컴포넌트를
소유하지 않는다. 이 id 는 **주소 지정 전용**이다(인터럽트를 특정 턴에 겨누고, 이벤트 프레임에 발생 턴을
표시). 제출 시점에 발급되고 턴이 끝나면 의미가 없으므로 **세션 상태로 영속하지 않는다**. 자세한 규칙은
[`glossary.md` §4](glossary.md) 의 `TurnId` 항목을 본다.

---

## 2. 소유권과 소멸 — 누가 만들고 누가 닫는가

수명보다 사고를 많이 내는 건 **소멸 책임**이다. 규칙은 하나다:
**만든 쪽이 닫는다. 빌려온 것은 닫지 않는다.**

| 컴포넌트 | 생성 | 소멸 |
|---------|------|------|
| `AgentRuntime` | 부트스트랩에서 1회 — CLI `AgentSetupFactory`, web `LiveSessionOpener` | 앱 shutdown 또는 명시적 agent 제거 시 `OrcaAgentRuntimeManager.destroyRuntime` |
| `McpClientManager` | `AgentRuntime` 생성 시 | `OrcaAgentRuntime.close()` 가 명시적으로 닫음 |
| `WorkflowRunner` (agent-scoped 변형) | `OrcaAgentRuntimeFactory` — `workflowRunnerEnabled` 일 때만 | `OrcaAgentRuntime.close()` |
| `WorkflowRunner` (call-scoped 변형) | `WorkflowTool` / `GraalJsWorkflowTool` 이 호출마다 | 각자의 try-with-resources |
| `VirtualShell` (core 기본값) | `OrcaAgentRuntimeFactory` — 어셈블리가 `withShell(...)` 로 주지 **않았을 때만** `LocalShells.create()` | `OrcaAgentRuntime.close()` (`ownedShell` 필드) |
| `VirtualShell` (어셈블리가 준 것) | 샌드박스 어셈블리 등 호출자 | **그 호출자** — `ownedShell` 은 null 이므로 런타임은 손대지 않는다 |
| `LiveSession` | `LiveSessionFactory` / opener | `LiveSession.close()` — **핸들 자원만** |
| `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` | 애플리케이션 부트스트랩 | 앱 shutdown |
| `AgentRuntimeRegistry` | `SchedulingEngine` **바깥**에서 생성해 빌더로 주입 | 앱 shutdown (`SchedulingEngine` 이 소유하지 않음) |

`RoutineExecutor` 가 들고 있는 `AgentRuntimeRegistry` 처럼 **빌려온 참조**는
`@ExternallyManaged` (`at.aimon.core.base`) 로 표시한다. 이 애노테이션이 붙은 필드는
문서화 목적이며 런타임 동작은 없지만, 규약상 **그 클래스가 닫으면 안 된다**.

### 마커 인터페이스는 문서일 뿐 — 자동 소멸이 아니다

`at.aimon.core.base` 에 두 개의 빈 마커가 있다.

| 마커 | 뜻 |
|------|-----|
| `AgentScoped` (`extends AutoCloseable`) | `AgentRuntime` 과 수명을 같이 함 |
| `ApplicationScoped` | 앱 시작 ~ 종료. `AgentRuntime` 소멸과 함께 닫으면 안 됨 |

IMPORTANT: **마커에 대한 fan-out 은 없다.** `OrcaAgentRuntime.close()` 는
`AgentScoped` 구현체를 스캔하지 않고 **하드코딩된 목록**(`mcpClientManager`, `workflowRunner`,
`ownedShell`)만 닫는다. 네이티브 자원(커넥션 풀, 워처 스레드)을 쥔 agent-scoped 컴포넌트를 새로
추가한다면 그 목록에 **직접 추가**해야 한다. 그러지 않으면 영원히 닫히지 않는다.

목록이 둘에서 셋으로 늘어난 것이 이 규칙의 실사례다 — `ownedShell` 은 마커를 달아서가 아니라
`close()` 본문에 한 줄이 추가되어서 닫힌다. 셋 중 `ownedShell` 만 **조건부**로, 즉 어셈블리가
셸을 주지 않아 런타임이 직접 만든 경우에만 닫는다(§2 표).

마커를 붙이지 않아도 수명은 그대로다. `ToolRegistry` / `HookRegistry` 는 agent-scoped 이지만
닫을 자원이 없어 `AgentScoped` 를 구현하지 않는다.

---

## 3. 세션 ≠ 라이브 세션

가장 자주 혼동되는 쌍이고, 잘못 놓으면 **데이터가 조용히 사라지는** 유일한 지점이다.

```
one SessionRecord (영속, SessionId 로 식별)  :  0..N LiveSession (일시적, 노드 로컬)
```

관계는 **비대칭**이다. 한 세션은 살아 있는 핸들이 0개일 수도 있고(아무도 대화 중이 아님),
시간에 걸쳐 여러 핸들이 순차적으로 서빙할 수도 있다(idle-TTL 축출, 프로세스 재시작,
노드 간 핸드오프).

따라서 **재시작·축출·노드 이동을 넘어 살아남아야 하는 값은 레코드 쪽에 둔다.**
`SessionTotals` 와 `budgetOverride` 가 `SessionRecord` 의 side field 로
`at.aimon.core.agent.session.store` 패키지에 있는 이유가 이것이다 — 이름에 `Live` 가 붙으면
"핸들이 죽으면 같이 죽는 값"으로 읽히지만 실제로는 핸들보다 오래 살아야 한다.
라이브 세션은 이 둘을 `SessionRecordStore.setTotalsAndBudgetOverride` 로 한 쌍씩 되쓴다.

**두 수명 중 어느 것도 맨 단어 `Session` 을 갖지 않는다.** `Session` 과 `AgentSession` 은
정확히 이 두 수명이 서로를 사칭하게 만드는 이름이므로 타입 이름으로 금지되고,
`SessionNamingArchitectureTest` (`aimon-session-routing`) 가 빌드에서 막는다.

비교표와 멀티 노드 확장 근거는 [`glossary.md` §2](glossary.md) 참조.

---

## 4. 하지 말 것

이 목록은 전부 실제로 코드 주석에 박혀 있는 금지 사항이다.

- **`LiveSession.close()` 에서 `AgentRuntime.close()` 를 호출하지 말 것.**
  동일 agent 의 다른 세션이 아직 그 runtime 을 쓰고 있을 수 있다.
  `DefaultLiveSession.close()` 는 이 사실을 주석으로 명시하고 핸들 자원만 정리한다.
- **`AgentRuntime` 소멸 시 스케줄링 컴포넌트를 닫지 말 것.**
  `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` 는 application-scoped 이고,
  `ScheduledTask.boundRuntimeId` 는 agent-scoped id 를 참조하므로 원래 세션이 끝난 뒤
  cron 이 재발화해도 runtime 이 resolve 된다.
- **`WorkflowRunner` 를 애플리케이션 셸에서 닫지 말 것.** 반대로, 다른 계층이 닫아줄 거라
  가정하고 안 닫아서도 안 된다 — 만든 쪽이 닫는다.
- **빌려온 협력자를 닫지 말 것.** `WorkflowRunner` 는 `SubagentExecutionManager` 와
  base `SubagentExecutionEnvironment` 를 빌려 쓰며, 자기 소유 풀만 닫는다.
- **`AgentRuntimeId` 를 실행마다 새로 만들지 말 것.** `agent:<name>` /
  `agent:<name>:<discriminator>` 형식으로 결정론적이며 `from(Agent)` / `from(Agent, String)`
  으로 발급한다. `generate()` 는 **존재하지 않는다** — 있었다면 cron 재발화가
  `boundRuntimeId` 를 resolve 하지 못했을 것이다.
- **`LiveSessionStatus` 를 제어 게이트로 쓰지 말 것.** best-effort 관찰 스냅샷이다.
  턴 시작 가능 여부는 `offerAsync` 의 `SubmitOutcome` 으로 판단한다.
- **타입 이름을 정확히 `Session` 또는 `AgentSession` 으로 짓지 말 것.** ArchUnit 이 막는다.
- **"conversation" 을 수명 단어로 쓰지 말 것.** 그 단어는 이제 **LLM 과의 메시지 교환**만 뜻한다
  (`getConversationHistory()`, `/compact` 의 "Conversation compacted"). 수명을 말하려면 `Session*`.

---

## 5. 새 타입을 만들 때

### 5.1 값의 수명부터 정한다

| 값의 성격 | 이름 | 패키지 |
|-----------|------|--------|
| 재시작 후에도 복원되어야 함 | `Session*` | `at.aimon.core.agent.session[.store\|.transcript]` |
| 프로세스가 죽으면 같이 사라져도 무방 | `LiveSession*` | `at.aimon.core.agent.session` |
| 에이전트 단위로 한 번 모으면 되는 값 | `Agent*` | `at.aimon.core.agent` |
| LLM 메시지 이력 자체를 가리키는 값 | `Transcript*` / `*Conversation*History*` | `at.aimon.core.agent.session.transcript` |

두 수명이 같은 접두어(`Session*` / `LiveSession*`)를 공유하는 것은 의도적이다 — 실수하기 쉬운
쪽(영속)이 짧은 이름을 갖고, 노드 로컬 핸들은 매번 `Live` 를 적어야 한다. 그리고 **어느 쪽도 맨 단어
`Session` 이 아니다**: 그 이름이 비어 있으므로 둘 중 하나로 착각될 수 있는 타입이 생기지 않는다.

### 5.2 이름의 마지막 명사로 수명을 추론하지 말 것

IMPORTANT: **"이름에 들어간 스코프 명사 = 그 타입의 수명" 은 거짓이다.**
`*Manager` / `*Registry` / `*Factory` / `*Repository` / `*Store` 는 **X 를 관리하는 컨테이너**이고,
컨테이너 자신의 수명은 X 의 수명이 아니다.

| 타입 | 담는 값의 스코프 | 자기 자신의 스코프 |
|------|-----------------|-------------------|
| `SessionRecordStore` / `SessionLeaseStore` | Session | **Application** |
| `AgentRuntimeRegistry` | Agent | **Application** |
| `SessionRouter` / `LiveSessionFactory` / `LiveSessionCache` | Live session | Live session 이 아님 |
| `InMemoryTodoRepository` | Session (세션 id 로 키잉) | **Agent** (agent runtime 당 1개) |

`ApplicationScoped` javadoc 의 표현대로: *스코프는 컴포넌트 자신의 수명을 말하는 것이지
그 내용물의 수명을 말하는 것이 아니다.*

### 5.3 판단은 이름이 아니라 **키와 저장 위치**로

어떤 스토어의 스코프가 궁금하면 이름이 아니라 **무엇으로 키잉되는가**를 본다.
`Map<AgentRuntimeId, _>` 면 agent-scoped, `Map<SessionId, _>` 면 session-scoped 다.
`InMemoryTodoRepository` 처럼 **인스턴스 수명과 키 스코프가 다를 수 있다**는 점에 주의한다
(인스턴스는 agent 당 1개, 항목은 세션별로 분할).

---

## 6. 알려진 오칭 (misnomer)

이름이 수명을 잘못 말하고 있는, **아직 개명되지 않은** 것들이다. 코드를 읽을 때 주의한다.

- **`OnSessionStartHook` / `OnSessionEndHook`** — 세션(레코드)의 시작/종료가 아니라 `LiveSession` 의
  열기/닫기에 발화한다. 같은 세션이 재개되면 다시 발화할 수 있다. 이름대로 읽으면 "세션당 1회"로
  오해하게 된다.
- **`TranscriptManager.initialize`** — 세션당 1회가 아니라 **턴당 1회** 호출된다.
  `beginTurn` / `endTurn` 으로 쪼개는 것은 별도 변경으로 남겨 두었다
  (`agent.session.transcript` 의 `package-info` 에 명시).
- **`${AIMON_AGENT_RUNTIME_ID}`** — 스킬 본문에서 쓸 수 있는 렌더 변수지만 **agent-scoped** 값이다.
  실행별 유니크 discriminator 로 쓰면 (`/tmp/work/${AIMON_AGENT_RUNTIME_ID}` 같은 식) 동시 진행 중인
  세션들이 같은 디렉토리를 공유한다. 세션별로 갈라야 하면 `${AIMON_SESSION_ID}` 를 쓴다.
- **영속 필드·컬렉션·채널 이름의 `conversation`** — `ToolContextKeys.SESSION_ID` 의 와이어 키는
  여전히 `"conversationId"` 이고, Mongo 컬렉션은 `conversation_locks` / `conversation_inbox` /
  `conversation_signals`, Postgres 테이블·채널도 `conversation_*` 다. **의도적으로 동결**한 것이다(§7).
  자바 식별자만 개명되었으므로 이름이 어긋나 보이는 것이 정상이다.

`Session` 이라는 단어는 여전히 여러 수명을 가리킨다 — 영속 `SessionRecord`,
`LiveSessionCache` 의 캐시 항목, `ReplSession`(CLI 실행 1회), `BrowserSession`(Playwright 컨텍스트).
목록은 [`glossary.md` §3](glossary.md) 참조.

### 이름이 재사용된 자리 — `SessionApprovalStore`

`SessionApprovalStore` 라는 이름은 **한 번 폐기됐다가 다른 뜻으로 다시 쓰였다.** 옛 코드나 옛 문서를
읽을 때 가장 헷갈리는 지점이므로 표로 못박는다.

| 시점 | `SessionApprovalStore` 가 가리킨 것 | 키 |
|------|-----------------------------------|-----|
| 개명 이전 | agent 전역 승인 저장소 (이름이 거짓말을 하고 있었다) | `AgentRuntimeId` |
| 그 다음 | — (`AgentApprovalStore` 로 개명, 이름이 비었음) | — |
| **현재** | **세션 단위 승인 저장소** (`…skill.policy.session`) | `SessionId` |

따라서 옛 이름으로 검색할 때의 대응은 이렇다.

| 옛 이름 | 현재 이름 |
|---------|----------|
| `SessionApprovalStore` (agent 전역이던 것) | `AgentApprovalStore` (`…skill.policy.agent`) |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` |
| `ConversationApprovalStore` | `SessionApprovalStore` (`…skill.policy.session`) |
| `ConversationScopedSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` |

승인 스코프의 **의미는 하나도 바뀌지 않았다**. `AgentApprovalStore` 에 들어간 결정은 여전히 같은
에이전트의 모든 세션에 적용되고 TTL 이 없으며 `/clear` 로 지워지지 않는다. 기본 경로는 여전히 좁은 쪽,
즉 세션 단위인 `SessionApprovalStore` 이고 정책 체인이 그쪽을 먼저 본다. 되돌리는 방법도 여전히
`/revoke` (`RevokeApprovalsCommand`) — `--agent` 를 붙이면 에이전트 전역까지 지운다.

세션 단위 승인의 도달 범위는 **그 세션과 그 세션이 위임한 실행**이다. 서브에이전트 포크는 **자기
`SessionId` 가 아예 없다** — 포크는 세션의 턴이 아니므로 `DefaultSubagentExecutor` 는 툴 컨텍스트에
`SESSION_ID` 를 넣지 않고, 대신 실행 정체성인 `ExecutionId` 를 `EXECUTION_ID` 로 공개한다. 그래서 포크는
자기를 띄운 세션의 id 를 `invokingSessionId` 로 따로 들고 다닌다 (`SkillInvocationRequest`,
`ToolContextKeys.INVOKING_SESSION_ID` — 와이어 키는 동결되어 `"invokingConversationId"` 그대로다).
두 id 는 **축이 다르다** — `sessionId` 는 *수명*(자기 세션이 무엇인가), `invokingSessionId` 는
*도달 범위*(누구의 결정이 적용되는가). 포크가 다시 포크를 띄우면 중간 포크가 아니라 **사용자의** 세션 id
를 그대로 물려준다 (`InvokingSessionAccess.idToPropagate`).

> 예전에는 포크가 자기 `SessionId` 를 새로 발급받았고, 그 id 가 툴에게는 사용자 세션과 똑같이 읽히면서
> 실제로는 아무 권한도 뜻하지 않았다. 지금 남아 있는 변환은 `forkTranscriptLabel` 하나뿐이며 —
> `TranscriptBuffer` 가 `SessionId` 로 타입되어 있어서 생기는 **라벨**이고 조회 키가 아니다.

---

## 7. 이름의 유래

두 번의 개명이 있었고, 이유는 매번 같았다 — **이름이 수명을 잘못 말하고 있었다.**

1. `AgentExecutionContext` → **`AgentRuntime`**. "context" 가 실행마다 새로 생기는 값처럼 읽혔지만
   실제로는 agent 당 하나 살아 있는 장수명 런타임이었다.
2. `Conversation` → **`SessionRecord`**, `AgentSession` → **`LiveSession`**. 영속 애그리게이트가
   "대화"라고 불리는 동안 사용자에게 보이는 단위(세션)와 이름이 어긋나 있었고, 노드 로컬 핸들이
   `AgentSession` 이라 불리는 동안은 그것이 영속 단위처럼 읽혔다. 둘을 헷갈리게 만든 원인이 **맨
   `Session` 이라는 이름**이었으므로, 개편 후 어느 쪽도 그 이름을 갖지 않는다 (§3, §5.1).

두 번째 개편에서 "conversation" 이라는 단어가 사라진 것은 아니다 — **수명을 가리키는 자리에서만**
빠졌고, LLM 메시지 교환을 가리키는 자리에는 그대로 남아 있다 (`getConversationHistory()`,
"Conversation compacted"). 이 구분이 §4 의 마지막 금지 항목이다.

옛 이름 ↔ 새 이름 매핑표는 [`../../CHANGELOG.md`](../../CHANGELOG.md) 의 `[Unreleased]` 항목에 있다.
두 리팩터 모두에서 **wire format / DDL / 채널명 / 키 prefix / 영속 필드는 하나도 바뀌지 않았다** —
CHANGELOG 의 "Not changed (deliberately frozen)" 목록이 그 경계다. 그래서 Java 식별자는 `Session*`
인데 저장된 이름은 `conversation_*` 인 자리가 남아 있다 (§6).

---

## 관련 문서

- [`glossary.md`](glossary.md) — 용어별 정의와 수명 사전
- [`architecture.md`](architecture.md) — 핵심 추상화 레퍼런스
- [`../develop/agent-session-guide.md`](../features/session/agent-session-guide.md) — `LiveSession` API 와 이벤트 스트리밍
- [`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) — agent scope 재정의 배경
- [`../design/session/session-model.md`](../design/session/session-model.md) — 세션 상태 영속화 설계
- [`../design/session/routing.md`](../design/session/routing.md) — 멀티 노드 세션 라우팅
