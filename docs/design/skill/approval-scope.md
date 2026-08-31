# 스킬 승인 스코프

> Status: **IMPLEMENTED** — 승인의 기본 스코프가 세션이고(`SessionApprovalStore`), 에이전트 전역은
> 사용자가 `a` 를 눌렀을 때만이며(`AgentApprovalStore`), 포크는 `invokingSessionId` 로 상속받고,
> `/clear` · `/revoke` · `/revoke --agent` 가 각 스코프를 지운다. 남은 것은 §8 — 턴 단위
> `PendingApprovalStore` 는 아직 정책 체인에 배선되어 있지 않고, 저장소는 여전히 노드 로컬이다.
>
> 적용 대상: `at.aimon.core.skill.policy..`, `at.aimon.core.tools.InvokingSessionAccess`,
> `at.aimon.core.command.system`, `aimon-cli` 승인 채널, `aimon-bootstrap` 정책 체인 배선

"이 스킬을 실행해도 됩니까? [y/a/N]" 에 대한 답이 **어디까지 미치는가**를 정하는 설계다.

---

## 1. 문제 — 답이 보이지 않는 곳까지 미쳤다

한때 승인 결정은 전부 `AgentRuntimeId` 로 키잉되었다. 한 대화에서 `y` 를 누르면 **같은 에이전트의
모든 다른 대화**에 그 결정이 적용되고, TTL 도 없으며 `/clear` 로도 지워지지 않았다. 되돌리는 유일한
방법이 `/revoke` 였다.

문제는 사용자가 그 도달 범위를 **볼 수 없다는** 것이다. 프롬프트가 보여 주는 것은 지금 이 스킬 하나고,
답이 미칠 다른 세션들은 화면에 없다. 보이지 않는 것에 대한 동의를 평범한 "예"에서 추론하면 안 된다.

당시 타입 이름은 `SessionApprovalStore` 였는데 실제 키는 `AgentRuntimeId` 였다 — 이름이 거짓말을
하고 있었고, 그래서 [`scope-model.md` §6](../../overview/scope-model.md) 에 **알려진 오칭**으로
기록되어 있었다. 개명 이력은 §7 에 있다.

---

## 2. 왜 세션이 매달 자리인가

승인을 세션에 매다는 것이 성립하려면 **세션이 노드와 프로세스를 넘어 존재해야** 한다.
AIMON 에서 그 조건을 만족하는 것은 영속 애그리게이트인 `SessionRecord` 이지, 노드 로컬 핸들인
`LiveSession` 이 아니다.

| 관계 | 상황 |
|------|------|
| **1 : 0** | 살아 있는 핸들이 없는 세션에 메시지가 들어옴 (`SessionInbox.deliver`) |
| **1 : N** (시간축) | idle-TTL 축출 → 재시작 → 노드 이동. 같은 세션을 여러 핸들이 순차 서빙 |
| **1 : N** (동시) | 캐시가 노드 로컬이라 두 노드가 같은 세션에 대해 각자 엔트리를 가질 수 있음 |

핸들에 매달았다면 축출 한 번에 승인이 사라지고, 노드가 갈리면 승인 상태도 갈린다. 그래서 키는
`SessionId` 다 — 락·멱등성·바인딩·인박스가 모두 같은 값으로 조인되는, 이 시스템에서 가장 안정적인
식별자다.

> `InMemorySessionApprovalStore` 의 **인스턴스는 agent 스코프**(agent runtime 당 1개)이고
> **항목만 세션별로 분할**된다. 선례는 `InMemoryTodoRepository` —
> [`scope-model.md` §5.3](../../overview/scope-model.md) 의 "인스턴스 수명과 키 스코프는 다를 수 있다".

---

## 3. 스코프 모델과 체인 순서

```
SessionScopedSkillInvocationPolicy      (SessionApprovalStore,  key: SessionId)
        │  miss
        ▼
ApprovalCachingSkillInvocationPolicy    (AgentApprovalStore,    key: AgentRuntimeId)
        │  miss
        ▼
RuleBasedSkillInvocationPolicy          (ALLOW / DENY / ASK)
```

배선은 `AimonStackBuilder` 한 곳이며, **중첩 구조가 곧 순서**다 — 순서를 뒤집으려면 괄호를 바꿔야
하므로 사고로 뒤집히지 않는다.

| 사용자의 답 | 저장소 | 도달 범위 | 지우는 법 |
|-------------|--------|----------|----------|
| `y` (이 세션에서 허용) | `SessionApprovalStore` | 그 `SessionId` + **그 세션이 위임한 실행** | `/clear`, `/revoke` |
| `a` (이 에이전트에서 항상) | `AgentApprovalStore` | 그 `AgentRuntimeId` 의 모든 세션, TTL 없음 | `/revoke --agent` |
| `N` (거부) | `SessionApprovalStore` | 그 세션 | 다음 세션에서 다시 물음 |

### 좁은 것 우선 — 기각한 대안

`PendingApprovalStore` 의 javadoc 은 원래 **넓은 것 우선**을 규정하고 있었다.

| 순서 | 결과 |
|------|------|
| 넓은 것 우선 (agent → session) | "이 세션에서는 거부" 라는 조작이 **구현 불가능**해진다. 과거의 전역 `ALLOW` 가 먼저 답해 버리므로 좁은 항목은 영원히 읽히지 않는 죽은 저장이 된다 |
| **좁은 것 우선** (채택) | 방금 이 세션에서 내린 `DENY` 가 과거의 전역 `ALLOW` 를 이긴다. 구체적인 답이 더 최근이고 더 의도적이라는 사용자 경험과도 맞는다 |

그래서 `PendingApprovalStore` javadoc 도 함께 고쳤다. 확정 순서는 좁은 것부터이며, 턴 단위 pending
저장소는 이 체인의 **더 앞자리**에 놓이도록 예약되어 있다(§8).

---

## 4. 포크는 어떻게 승인을 상속하는가

### 4.1 좁히기가 끊은 우연한 상속

승인이 agent 전역이던 시절, 포크는 부모의 `AgentRuntimeId` 를 공유하므로 부모의 승인을 **그냥 주웠다**.
스코프를 세션으로 좁히자 fork 모드 스킬이 전부 죽었다.

서브에이전트 포크에는 **자기 `SessionId` 가 아예 없다.** 포크는 세션의 턴이 아니므로 툴 컨텍스트에
`SESSION_ID` 가 실리지 않고 실행 정체성인 `EXECUTION_ID` 만 실린다. 따라서:

| 조회 | 결과 |
|------|------|
| `SessionApprovalStore` | 키가 없음 |
| `AgentApprovalStore` | miss — 이제 기본 경로가 아니다 |
| 규칙 폴백 | `ASK` |

그리고 **포크는 `ASK` 를 해소할 수 없다.** 포크에서 도달 가능한 승인 채널이 없고, 있어서도 안 된다 —
사용자는 포크를 보고 있지 않다. 즉 포크에서 `ASK` 는 사실상 `DENY` 다.

### 4.2 두 번째 축 — `invokingSessionId`

`SkillInvocationRequest` 는 축이 다른 두 id 를 나른다.

```
sessionId          = 이 실행 자신의 세션   (포크에는 없다)          → 수명
invokingSessionId  = 이 실행을 띄운 세션   (사용자가 실제로 답한 곳) → 도달 범위
```

`SessionScopedSkillInvocationPolicy` 는 `sessionId` → `invokingSessionId` → delegate 순으로 본다.
`sessionId` 가 앞인 이유는 "가장 직접적인 바인딩이 이긴다" 이지만, **오늘 존재하는 경로에서는 둘 중
하나만 실리므로** 이 순서가 실제로 타이를 깨는 일은 없다.

상속은 **양방향**이다 — 포크는 거부도 물려받는다. 이 세션에서 거부한 스킬이 이 세션이 위임한 작업에서
돌고 있으면 사용자는 놀랄 것이므로 fail-closed 로 둔다.

### 4.3 전파

| 지점 | 하는 일 |
|------|--------|
| `TaskTool` · `SubagentBackedSkillForkExecutor` · `WorkflowTool` · `GraalJsWorkflowTool` | `InvokingSessionAccess.idToPropagate(context)` 로 실어 보냄 |
| `DefaultSubagentExecutionManager` | env → `SubagentExecutionRequest` |
| `DefaultSubagentExecutor.createToolContext` | request → 포크의 `ToolContext` (`INVOKING_SESSION_ID`) |
| `SkillTool` | `ToolContext` → `SkillInvocationRequest` |

`InvokingSessionAccess` 의 두 메서드가 비대칭인 것이 핵심이다.

- **`idToPropagate`** (spawner 용) — **상속받은 id 를 우선**하고 없을 때만 자기 세션 id 로 폴백한다.
  반대로 하면 도달 범위가 정확히 한 단계에서 조용히 끊긴다. 포크가 다시 포크를 띄우면 중간 포크의
  id 가 아니라 **사용자의** 세션 id 가 계속 흐른다.
- **`invokerOf`** (reader 용) — 폴백 없이 `INVOKING_SESSION_ID` 만 읽는다. 메인 에이전트는 invoker
  가 아니라 invoker **자신**이므로 자기 id 를 이 자리에 반복해 넣으면 안 된다.

`OrcaAgentExecutor` 의 커맨드 경로에도 `SESSION_ID` 를 실었다. 없으면 사용자가 직접 친
`/my-fork-skill` 이 아무것도 상속하지 못하는 포크를 만든다.

### 4.4 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| 포크 시점에 승인을 **복사** | 스냅샷이라 `/revoke` 가 진행 중인 포크에 닿지 못한다 |
| `SessionId` 문자열에 **계보를 인코딩** | 그 값은 영속된다. wire format 을 건드린다 |
| `executionAttributes` / `LlmCallMetadata.tags` 로 밀반입 | **인증 구멍**이다. 둘 다 호출자와 훅이 설정할 수 있으므로, 승인 판정이 그것을 읽으면 훅이 자기 승인을 위조할 수 있다 |

---

## 5. 승인 채널

### 5.1 스코프는 결정과 별개 타입으로 나른다

`SkillInvocationDecision` enum 은 건드리지 않았다. `ALLOW_SESSION` / `ALLOW_AGENT` 같은 값을 추가하면
**정책 판정과 저장 위치**라는 두 관심사가 한 타입에 엉킨다. 대신 불변 값 객체를 하나 둔다.

```java
public final class ApprovalGrant {          // 불변 + 빌더
    private final SkillInvocationDecision decision;   // ALLOW | DENY
    private final ApprovalScope scope;                // SESSION | AGENT
}
```

CLI 프롬프트는 `Allow skill 'X'? [y/a/N]:` 이고, 중단 경로(EOF · Ctrl+C)는 **세션 스코프 DENY** 를
쓴다 — Ctrl+C 한 번이 에이전트 전역 거부로 굳는 것은 과하다.

### 5.2 세션 없는 실행의 escalation은 시끄럽게

`ApprovalGrantWriter` 는 스코프를 구체적 저장소로 푸는 단 한 곳(package-private — 확장점이 아니다)이다.
`SESSION` 스코프인데 매달 `SessionId` 가 없으면 **agent 저장소로 넓혀 쓴다.**

이것은 선택이 아니라 `SkillApprovalChannel` 계약이 강제하는 것이다 — 채널은 반환 전에 요청된 모든
스킬에 대해 구체적 `ALLOW`/`DENY` 를 **정책 체인이 읽는 곳에** 써야 한다. 쓰지 않으면 스킬은 `ASK` 로
남고 `SkillTool` 이 거부하는데, 거부에는 결과가 같지만 허용 목록 기반 `ALLOW` 는 조용히 거부로
뒤집힌다.

그럼에도 이것은 **아무도 요청하지 않은 넓히기**이므로 그런 쓰기는 전부 **WARN** 으로 남긴다 —
스킬·결정·런타임 이름과 함께, 그 항목이 TTL 없이 모든 세션에 적용되고 `/clear` 를 견디며
`/revoke --agent` 로만 지워진다는 사실까지. 평범한 세션 스코프 쓰기는 DEBUG 다.

### 5.3 쓰기 실패는 스킬 단위로 격리하고 fail-closed

배치 중 한 스킬의 쓰기가 실패해도 배치를 중단하지 않고 로그 후 넘어간다. 쓰이지 않은 결정은 정책
체인에서 `ASK` 로 남고 `SkillTool` 이 실행 시점 재검사에서 거부하므로, **격리가 곧 fail-closed** 다.
중단시켰다면 깨진 쓰기 하나가 나머지 전부를 조용히 거부시킨다.

---

## 6. 커맨드 정합

| 커맨드 | 동작 |
|--------|------|
| `/clear` | 전사 초기화와 함께 **그 세션의 승인을 무효화**한다. "대화를 초기화했는데 예전 승인이 남아 있다" 가 오히려 놀라운 쪽이다 |
| `/revoke` | 그 세션의 승인만 지운다 (기본이 좁은 쪽) |
| `/revoke --agent` | 에이전트 전역 승인까지 지운다 |

두 커맨드 모두 세션 저장소 없이도 생성 가능한 생성자를 남겨 두어(세션 스토어 `null`), 승인 저장소가
배선되지 않은 어셈블리에서도 no-op 으로 동작한다.

---

## 7. 개명 이력

이름이 두 번 바뀌었고 그 과정에서 **`SessionApprovalStore` 라는 이름이 다른 뜻으로 재사용되었다.**
옛 코드를 읽을 때 가장 헷갈리는 지점이다.

| 옛 이름 | 현재 이름 | 실제 키 |
|---------|----------|---------|
| `SessionApprovalStore` (이름이 거짓말을 하던 것) | `AgentApprovalStore` (`…policy.agent`) | `AgentRuntimeId` |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` | — |
| `ConversationApprovalStore` | **`SessionApprovalStore`** (`…policy.session`) | `SessionId` |
| `ConversationScopedSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` | — |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` | — |

순서가 이렇게 짜인 덕에 개명은 **한 번만** 일어났다 — 먼저 `SessionApprovalStore` 라는 이름을 비워
두고, 세션 개편 시점에 좁은 스코프 저장소가 그 이름을 가져갔다. **승인의 의미는 하나도 바뀌지 않았다.**

---

## 8. 남은 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| 턴 단위 승인 | `PendingApprovalStore` 는 타입과 in-memory 구현만 있고 **정책 체인에 배선되어 있지 않다** | 채널이 "이번 턴만" 답을 제공할 때 체인 맨 앞에 얹는다 |
| 멀티 노드 | 두 저장소 모두 in-memory · 노드 로컬 | 공용 백엔드(Redis/Postgres). 인터페이스로 분리되어 있으므로 구현체 교체로 가능 |
| 코드 서브에이전트 | `SubagentBehavior` 등록 경로는 `createToolContext` 를 거치지 않아 **상속하지 않는다** | 자기 툴 컨텍스트를 구성할 때 직접 실어야 한다 |
| 백그라운드 워크플로 | agent-scoped 러너는 모든 세션보다 오래 살아 물려받을 "그 세션" 이 없다 | 설계상 상속 대상이 아니다 — 규칙으로 허용하려면 별도 근거가 필요 |
| 영속화 | 승인은 재시작 시 사라진다 | 의도된 비목표. 되살리려면 스코프별 TTL 설계가 먼저다 |

### 하지 말 것

- **`SkillInvocationDecision` 에 스코프를 섞지 말 것** — 스코프는 `ApprovalGrant` 로 나른다(§5.1).
- **`sessionId` 를 필수(non-null)로 만들지 말 것** — 스케줄 실행·포크에는 세션이 없다. 부재 시 폴백이
  정상 경로다.
- **포크를 "세션 id 가 없어서 아무 데도 못 미치는 호출자"로 취급하지 말 것** — 초판이 그렇게 적었고
  그것이 실제로 fork 모드 스킬을 전부 죽였다(§4.1).
- **wire format 을 건드리지 말 것** — `ToolContextKeys.INVOKING_SESSION_ID` 의 와이어 키는 여전히
  `"invokingConversationId"` 로 동결되어 있다.

---

## 부록 — 참조 파일 지도

| 파일 | 역할 |
|------|------|
| `skill/policy/SkillInvocationRequest.java` | `sessionId` + `invokingSessionId` 두 축을 나르는 요청 |
| `skill/policy/session/SessionApprovalStore.java` | 세션 스코프 저장소 SPI (`SessionId` 키) |
| `skill/policy/session/InMemorySessionApprovalStore.java` | 기본 구현 — `BoundedLruMap` |
| `skill/policy/session/SessionScopedSkillInvocationPolicy.java` | 체인 최상단 데코레이터, 2단 조회 |
| `skill/policy/agent/AgentApprovalStore.java` | 에이전트 전역 저장소 (`AgentRuntimeId` 키) |
| `skill/policy/agent/ApprovalCachingSkillInvocationPolicy.java` | 그 데코레이터 |
| `skill/policy/RuleBasedSkillInvocationPolicy.java` | 룰 평가 — 체인의 바닥 |
| `skill/policy/approval/ApprovalScope.java` · `ApprovalGrant.java` | 답의 도달 범위 |
| `skill/policy/approval/ApprovalGrantWriter.java` | 스코프 → 저장소 해석, escalation WARN |
| `skill/policy/approval/SkillApprovalChannel.java` | 채널 계약 ("반드시 쓴다") |
| `skill/policy/pending/PendingApprovalStore.java` | 턴 단위 저장소 — 아직 미배선(§8) |
| `skill/policy/SkillPreflightScanner.java` | 턴 시작 전 스킬 스캔, `(toolUses, runtimeId, sessionId, principal)` |
| `tools/InvokingSessionAccess.java` | `idToPropagate` (spawner) / `invokerOf` (reader) |
| `command/system/ClearCommand.java` · `RevokeApprovalsCommand.java` | 세션/에이전트 스코프 무효화 |
| `at/aimon/cli/skill/InteractiveSkillApprovalChannel.java` | `[y/a/N]` 프롬프트 |
| `at/aimon/bootstrap/AimonStackBuilder.java` | 체인 중첩 배선 |

경로는 `at/aimon/...` 로 시작하는 두 항목을 제외하면 모두
`modules/aimon-core/src/main/java/at/aimon/core/` 이하다.

---

## 관련 문서

- [스코프 모델](../../overview/scope-model.md) — 수명·소유권·소멸 책임의 기준 문서
- [용어집](../../overview/glossary.md) — "Session" 의 여러 뜻과 승인 스코프 표
- [세션 모델](../session/session-model.md) — `SessionRecord` : `LiveSession` 비대칭
- [세션 라우팅](../session/routing.md) — 멀티 노드에서 세션이 노드를 옮기는 방식
- [서브에이전트 실행](../subagent/execution.md) — 포크의 실행 정체성(`ExecutionId`)
- [스킬 커맨드 통합](command-unification.md) — 스킬과 슬래시 커맨드의 단일 표면
