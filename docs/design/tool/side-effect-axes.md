# 도구 부작용 축 (Tool Side-Effect Axes)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.agent.tool` (`SideEffectLevel`, `DestructiveBehavior`, `IdempotencyBehavior`, `Tool`), `at.aimon.core.toolinvocation.approval` (`SideEffectApprovalGate`), `at.aimon.core.scheduling.RoutineExecutor`. 승인 게이트는 opt-in 이고 프로덕션 배선 전의 인트리 도구 감사는 [TC-7](../../backlog/tool-contract-open-items.md) 이다.

---

## 1. 왜 한 사다리가 아닌가

도구가 선언하는 부작용은 한 사다리로 표현할 수 없다. "쓰는가" 는 크기이고 "재실행해도 안전한가" 는 다른 축의 값이다.

멱등성은 안전성이 아니라 수렴성이다. `rm -rf` 는 멱등이다. 그래서 승인 게이트의 면제선(`exemptAtOrBelow`)을 멱등 눈금에 두면 가장 물어봐야 할 부류가 면제된다. 파괴성도 같다. 삭제 도구는 로그를 덧붙이는 도구보다 "더 많이 쓰는" 것이 아니라 다르게 쓴다.

이름이 이 구분을 드러낸다. **`*Level` 은 순서 있는 형질, `*Behavior` 는 순서 없는 형질이다.** 순서가 있는 것은 "쓰는가" 하나뿐이다.

---

## 2. 두 축이 직교한다는 증거

파괴성과 멱등성은 서로를 함축하지 않는다. 이 저장소의 도구만으로 2×2 가 채워진다.

|  | 비파괴 | 파괴적 |
|---|---|---|
| **멱등** | `WikiIngestTool` — 페이지 id 로 색인 upsert | `CancelScheduledTaskTool` — 두 번 지워도 결과가 같다 |
| **비멱등** | `ScheduleTaskTool` — 호출마다 새 작업이 생긴다 | `BashTool` — 임의 명령 |

이 분류는 관찰이지 선언 현황이 아니다. 축이 직교함을 보이는 예시이고, 무엇을 선언했는지는 도구 소스가 정본이다.

사다리는 도구에 위치를 하나만 주므로 멱등·파괴 칸과 비멱등·비파괴 칸을 동시에 표현할 수 없다. MCP 가 `readOnlyHint`·`idempotentHint`·`destructiveHint` 를 독립 필드로 둔 것도 같은 이유다.

---

## 3. 사다리 1개 + 형질 N개

축과 어휘는 MCP 에서 가져오고, 형태와 신뢰 경계는 AIMON 이 정한다.

### 3.1 세 축의 모양

```java
// 상위 축. 순서가 진짜 있으므로 permits() 가 뜻을 갖는다. 실링이 쓰는 것은 이것뿐이다.
public enum SideEffectLevel { READ_ONLY(0), MUTATING(1) }

// 형질. 순서가 없다. 기본값은 MCP 스펙 기본값과 같은 쪽, 곧 최악이다.
public enum DestructiveBehavior { NON_DESTRUCTIVE, DESTRUCTIVE }   // 기본 DESTRUCTIVE
public enum IdempotencyBehavior { NON_IDEMPOTENT, IDEMPOTENT }     // 기본 NON_IDEMPOTENT
```

| MCP annotation | AIMON 축 | 형태 | 스펙 기본값 ↔ AIMON 기본값 |
|---|---|---|---|
| `readOnlyHint` | `SideEffectLevel` | 사다리 | `false` ↔ `MUTATING` |
| `destructiveHint` | `DestructiveBehavior` | 형질 | `true` ↔ `DESTRUCTIVE` |
| `idempotentHint` | `IdempotencyBehavior` | 형질 | `false` ↔ `NON_IDEMPOTENT` |
| `openWorldHint` | 대응 없음 | — | — |

기본값이 양쪽에서 일치하므로 어댑터의 "어노테이션 없음" 경로에 특수 처리가 없다. `openWorldHint` 를 다루는 결정은 [MCP 도구 통합](../integration/mcp-tool.md) 에 있다.

`DestructiveBehavior` 의 경계는 MCP 와 같은 시험이다. `NON_DESTRUCTIVE` 는 추가만 하는 도구다. 만들고 덧붙이되 있던 상태를 덮어쓰거나 지우거나 무효로 만들지 않는다.

### 3.2 상위 축은 소비 규칙으로 강제한다

`readOnlyHint` 는 나머지 둘과 직교하지 않는다. 읽기 전용이면 파괴성·멱등성 질문이 무의미하다. 불리언 셋으로 두면 `readOnly=true` + `destructive=true` 라는 모순이 컴파일된다.

이것을 타입으로 막지 않고 소비 규칙으로 막는다. **소비자는 형질을 `MUTATING` 일 때만 읽는다.**

```java
// SideEffectApprovalGate.denialReason
final SideEffectLevel declared = tool.getSideEffectLevel();
final DestructiveBehavior destructive = declared == SideEffectLevel.MUTATING
        ? tool.getDestructiveBehavior()
        : DestructiveBehavior.NON_DESTRUCTIVE;
```

`READ_ONLY` 면 두 번째 축을 조회하지 않으므로 모순 상태에 도달할 수 없다. 두 선언에 걸치는 유효성 검사가 필요 없고, `Tool.isReadOnly()` 는 `getSideEffectLevel()` 에서 파생된 값으로 남는다.

### 3.3 누가 무엇을 읽는가

| 소비자 | `SideEffectLevel` | `DestructiveBehavior` | `IdempotencyBehavior` |
|---|---|---|---|
| `DefaultToolExecutionManager` — 실링 초과 도구의 실행 거부 | 읽음 (`permits()`) | 읽지 않음 | 읽지 않음 |
| `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `LlmSkillExecutor` — 실링 초과 도구의 정의를 LLM 에 주지 않음 | 읽음 (`permits()`) | 읽지 않음 | 읽지 않음 |
| `SideEffectApprovalGate` — 사용자에게 물음 | 읽음 (면제선) | 읽음 — 면제선을 무시하고 묻는다 | 읽지 않음 |
| `RoutineExecutor` — 실패한 단계의 재시도 | 읽음 (`READ_ONLY` 는 재시도) | 읽지 않음 | 읽음 — `IDEMPOTENT` 면 재시도 |

실링은 `permits()` 하나만 쓴다.

파괴성을 읽는 것은 게이트 하나다. **`DESTRUCTIVE` 면 면제선과 무관하게 묻는다.** 면제선의 어떤 값으로도 이 질문을 끌 수 없다. 면제선은 `SideEffectLevel` 이 순서 있는 축이라서 순서가 있고, 파괴성은 그 축 위에 없다. 아무 프롬프트도 원하지 않는 운영자는 게이트를 구성하지 않는다. 그쪽이 명시적 선택이기 때문이다.

읽기 전용인데 `READ_ONLY` 를 선언하지 않은 도구는 `READ_ONLY` 실링에서 숨겨지고 재시도되지 않는다. 확인된 사례는 [TC-8](../../backlog/tool-contract-open-items.md) 이다.

### 3.4 멱등성 축

멱등성 축의 첫 소비자는 routine 재시도다. `READ_ONLY` 이거나 `IDEMPOTENT` 인 도구의 단계만 재시도한다. 선언하지 않은 쓰기 도구는 재시도하지 않는다. timeout 으로 끝난 쓰기를 다시 실행하면 같은 부작용이 두 번 일어나기 때문이다.

재시도 규칙의 정본은 [스케줄 실행 설계](../scheduling/llm-scheduling-agent.md) 의 "재시도와 타임아웃" 절이다.

---

## 4. MCP 도구의 선언

MCP 어노테이션은 서버의 주장이고, 호스트가 서버 단위로 판정한 뒤에야 도구의 선언이 된다. 판정을 지난 뒤 아래쪽은 MCP 도구와 로컬 도구를 구분하지 못한다. 규칙은 [MCP 도구 통합](../integration/mcp-tool.md) 의 "신뢰 경계" 절과 설계 결정 절이 정본이다.

---

## 5. 승인 게이트의 배선 조건

`SideEffectApprovalGate` 는 opt-in 이다(`OrcaAgentExecutorFactory.withApprovalGate`, `DefaultSubagentExecutor.withApprovalGate`).

프로덕션 경로에는 승인 게이트를 생성하는 곳이 없고, `NON_DESTRUCTIVE` 나 `IDEMPOTENT` 를 선언한 인트리 도구도 없다. 이 두 사실이 바뀌기 전에 게이트를 배선하면 쓰는 도구 전부가 무인 경로에서 실패한다. `DestructiveBehavior` 의 기본값이 `DESTRUCTIVE` 이므로 면제선을 `MUTATING` 으로 올려도 면제되는 것이 없다. 사실의 최신 상태와 감사 작업은 [TC-7](../../backlog/tool-contract-open-items.md) 이 갖는다.

**무인 실행에서 파괴적 도구가 거부되는 것은 버그가 아니다.** 세션이 전혀 없는 실행(스케줄 루틴, rewake 리플레이)에는 답을 기억할 범위도 물을 사람도 없다. 호출마다 묻게 되고, 기본 핸들러(`AskPromptHandler`)에서는 거부로 떨어진다. 포크는 호출한 세션의 범위(`INVOKING_SESSION_ID`)를 쓴다.

무인으로 쓰기 도구를 돌리는 배포의 길은 다음과 같다.

- 게이트를 구성하지 않는다. 실링은 그대로 적용된다.
- 사람 없이 답할 수 있는 승인 핸들러를 붙인다(사전 승인 목록, 비대화형 정책 핸들러).
- 도구를 비파괴적으로 다시 설계한다.

묻지 않고 허용하는 설정은 두지 않는다. 게이트의 요점이 사람이 승인했다는 사실이기 때문이다.

---

## 6. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 사다리는 `READ_ONLY < MUTATING` 2단이다 | [§1](#1-왜-한-사다리가-아닌가). 사다리는 "쓰는가" 하나에 답한다 | `IDEMPOTENT` 눈금: 부작용이 더 작지 않고 재생이 안전할 뿐이다. 그 눈금을 면제선이나 실링으로 쓰면 멱등·파괴 도구가 통과한다 |
| 파괴성을 사다리가 아니라 형질로 둔다 | 2×2 를 표현해야 한다. 멱등·파괴 도구를 어느 눈금에 두어도 두 진실 중 하나를 잃는다. `MUTATING` 은 "기본값 = 미감사" 와 "무제한 실링" 을 겸하는데, 위에 눈금이 생기면 미감사 도구가 최악값을 뜻하지 않게 된다 | 4번째 등급 `DESTRUCTIVE`. 기본값을 거기로 올리면 모든 도구가 파괴적이라고 주장하게 된다 |
| 표면은 사다리 1개 + 형질이다. 어휘와 기본값 방향은 MCP 를 따른다 | "destructive" 는 운영자가 실제로 쓰는 단어이고, MCP 의 기본값 방향은 "미선언 = 최악" 과 같다 | MCP 필드를 불리언 셋으로 그대로: 모순이 컴파일되고, 파생값 `isReadOnly()` 의 불변식이 깨지며, 실링이 단일 노브를 잃는다. "Hint" 는 신뢰 수준을 박은 이름인데 `DefaultToolExecutionManager` 는 선언을 근거로 실행을 거부한다 |
| 축 간 불변식은 소비 규칙으로 지킨다 | 축 간 불변식은 소비 규칙(형질은 `MUTATING` 일 때만 읽는다)으로 충분하다. 격자 비교로 `permits()` 를 잃을 이유가 없다. 소비 규칙으로 표현되지 않는 불변식이 생기면 다시 본다 | 합성 값 객체 `ToolEffect`. 실링 비교가 격자가 되어 소비자가 다차원 비교를 직접 쓰고, `InterruptBehavior`·`ConcurrencyBehavior` 와 모양이 갈라진다 |
| 파괴성은 도구 단위다. 입력을 보지 않는다 | 입력별 판단 계층은 이미 있다. `BashTool` 의 `command` 를 `Bash(git:*)` 에 대조하는 [권한 판정](tool-contract.md) 이다. 레벨은 도구가 무엇을 하는지를 말하지 지금 여기서 얼마나 위험한지를 말하지 않는다. 플래그에 따라 가끔만 덮어쓰는 도구는 `DESTRUCTIVE` 다 | 호출 인자로 파괴성 판정 |
| 파괴성을 동시성에서 유도하지 않는다 | 둘은 상관이 있을 뿐 유도되지 않는다. 레이트 리밋 때문에 `SEQUENTIAL` 인 읽기 전용 도구와, 호출마다 다른 대상에 써서 `CONCURRENT_SAFE` 인 쓰기 도구가 반례다 | `ConcurrencyBehavior` 에서 유도 |
| `DestructiveBehavior` 는 2값이다 | 3값이면 소비자마다 셋째 분기가 생기고 게이트가 그 값에서 묻는가의 답이 자명하지 않다. 복구 가능성은 대개 도구가 아니라 환경의 성질이다. 파일 삭제는 휴지통 있는 파일 시스템에서는 복구되고 GridFS 에서는 아니다. MCP 입력은 불리언이라 어느 쪽이든 무손실이다. 복구 메커니즘을 스스로 구현한 도구가 생길 때가 셋째 값의 자리다 | 3번째 값 `RECOVERABLE` |

---

## 7. 하지 말 것

- **`SideEffectLevel` 에 눈금을 더하지 말 것.** 순서 없는 질문은 형질로 간다.
- **`READ_ONLY` 도구의 형질을 읽지 말 것.** 소비 규칙이 곧 불변식이다.
- **실링에서 `permits()` 외의 비교를 쓰지 말 것.**
- **`Tool.isReadOnly()` 를 재정의하지 말 것.** 파생값이고 재정의하면 선언과 어긋난다. `getSideEffectLevel()` 을 재정의한다.
- **쓰는 도구를 `READ_ONLY` 로 선언하지 말 것.** 적게 선언하는 쪽은 가용성만 잃지만, 많이 약속하는 쪽은 아무것도 바꾸지 않겠다는 약속을 깬다.
- **`IDEMPOTENT` 를 안전하다는 뜻으로 쓰지 말 것.** 재생해도 수렴한다는 뜻이다.
- **일부 입력에서만 그런 도구를 `NON_DESTRUCTIVE` 나 `IDEMPOTENT` 로 선언하지 말 것.** 축은 도구에 대한 것이다.
- **인트리 도구 감사 전에 승인 게이트를 프로덕션 경로에 배선하지 말 것.** [§5](#5-승인-게이트의-배선-조건) 이다.
- **무인 경로를 위해 묻지 않고 허용하는 설정을 넣지 말 것.**

---

## 8. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다. MCP 쪽 파일은 [MCP 도구 통합](../integration/mcp-tool.md) 의 지도에 있다.

| 관심사 | 파일 |
|---|---|
| 사다리 | `agent/tool/SideEffectLevel.java` |
| 파괴성 형질 | `agent/tool/DestructiveBehavior.java` |
| 멱등성 형질과 그 소비자 | `agent/tool/IdempotencyBehavior.java`, `scheduling/RoutineExecutor.java` (`retryRefusal`) |
| 도구의 선언 지점 | `agent/tool/Tool.java` |
| 실링 — 실행 거부 | `agent/tool/DefaultToolExecutionManager.java` |
| 실링 — 정의 필터 | `agent/impl/orca/OrcaAgentExecutor.java`, `subagent/execution/DefaultSubagentExecutor.java`, `skill/execution/llm/LlmSkillExecutor.java` |
| 승인 게이트 | `toolinvocation/approval/SideEffectApprovalGate.java` |
| 게이트 opt-in | `agent/impl/orca/OrcaAgentExecutorFactory.java` |

---

## 9. 관련 문서

- [도구 개발 가이드](../../features/tool/tool-development-guide.md) — 도구 작성자가 읽는 문서
- [MCP 도구 통합](../integration/mcp-tool.md) — 어노테이션의 신뢰 경계
- [병렬 실행](parallel-execution.md) — 같은 `*Behavior` 규약을 따르는 다른 축
- [도구 계약](tool-contract.md) — 호출 경로에서 실링과 게이트의 자리, 입력별 권한 판정
- [스케줄 실행 설계](../scheduling/llm-scheduling-agent.md) — routine 재시도
- [Orca 실행기](../agent-execution/orca-executor.md) — 실링이 정의 필터로 걸리는 자리
- [남은 작업 등록부](../../backlog/tool-contract-open-items.md)
