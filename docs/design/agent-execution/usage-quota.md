# Usage Quota — 세션·주체·테넌트 단위의 누적 사용량 한도

> Status: **IMPLEMENTED** — `aimon-core`(쿼터 타입, in-memory 장부, 턴·포크·`/skill` 루프의 기록과 판정, 예약 루틴),
> `aimon-bootstrap`(`QuotaSpec`). 노드 간 공유 장부와 Spring Boot 프로퍼티는 없다. 남은 일은
> [backlog](../../backlog/usage-quota-open-items.md) 가 정본이다(UQ-1 ~ UQ-6).
> 관련 규칙: [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
> 관련 문서: [`orca-executor.md`](orca-executor.md) (ReAct 루프, `ExecutionBudget`, `CostEstimator`),
> [`session-model.md`](../session/session-model.md#61-누적-통계와-예산-오버라이드) (`SessionTotals` · `budgetOverride`),
> [`scope-model.md`](../../overview/scope-model.md) (수명 규칙),
> [`subagent/execution.md`](../subagent/execution.md) · [`subagent/execution-policy.md`](../subagent/execution-policy.md) (포크 루프와 결과 계약),
> [`programmatic-tool-calling.md`](../tool/programmatic-tool-calling.md#72-에이전트-설정) (`BackgroundSubmission`),
> [`llm-scheduling-agent.md`](../scheduling/llm-scheduling-agent.md) (예약 루틴)
> 사용 가이드: [`usage-quota-guide.md`](../../features/agent-execution/usage-quota-guide.md)

---

## 1. 문제와 원칙

### 1.1 무엇을 푸는가

`ExecutionBudget` 은 **실행 1회**의 한도다. 세션의 `budgetOverride` 도 누적 한도가 아니라 턴마다 새로 적용되는
`ExecutionBudget` 이고, `SessionTotals` 는 한도 없는 통계다(두 필드의 저장·복원 규칙은
[session-model §6.1](../session/session-model.md#61-누적-통계와-예산-오버라이드)). 10턴을 돌면 턴 예산도 10번 새로
시작된다.

그래서 예산만으로는 다음을 막을 수 없다.

- 한 세션이 여러 턴에 걸쳐 쓰는 총량
- 한 주체(`Principal`)가 여러 세션에 걸쳐 쓰는 총량 — 세션 한도는 새 세션을 열면 우회된다
- 한 테넌트가 쓰는 총량
- 위 셋의 기간별(일 단위) 총량

서브에이전트·스킬 포크는 자기 `BudgetTracker` 를 새로 만들고, 세션 누적은 부모 턴의 트래커만 접는다. 세션 누적에
한도를 거는 것만으로는 **포크가 우회로**가 된다. usage quota 는 이 넷을 하나의 장치로 막는다.

### 1.2 원칙

- **soft limit** — 정확한 원자적 차감이 아니라 "호출 전에 보고, 호출 뒤에 더한다". 초과량에는 상한이 있고 그 상한을
  문서화한다([§5.1](#51-초과-상한))
- **포크·루틴 포함** — 실행 트리 어디서 LLM 을 부르든, 턴이든 예약 루틴이든 같은 장부에 기록된다([§4.3](#43-포크-전파), [§9](#9-예약-루틴))
- **하나의 장부, 여러 범위** — 세션·주체·테넌트·기간은 키만 다르다. 범위마다 기계장치를 따로 두지 않는다
- **opt-in, 무변경 기본값** — 장부를 배선하지 않으면 동작은 쿼터가 없을 때와 한 비트도 다르지 않다
- **Multi-instance ready** — 장부는 SPI 이고 in-memory 가 기본 구현이다. `QuotaGuard` 는 상태가 없고 누적치는 장부에만
  있으므로, 노드 간 공유는 장부 구현체를 바꾸는 것으로 얻는다
- **기존 예산과 직교** — `ExecutionBudget` / `BudgetTracker` 는 그대로 둔다. 쿼터는 그 옆에서 따로 판정한다

---

## 2. 용어

| 용어 | 뜻 |
|------|-----|
| **scope** | 사용량이 귀속되는 주체 한 개. `SESSION` · `PRINCIPAL` · `TENANT` 세 종류와 그 id |
| **window** | 누적을 다시 시작하는 기간. `TOTAL`(리셋 없음) · `DAILY` |
| **quota key** | scope + window + 기간 버킷. 장부의 키. 예: `principal:user/alice:daily:2026-10-15` |
| **usage** | 토큰 수(`long`)와 추정 비용(`Money`, USD)의 쌍 |
| **limit** | 한 (scope 종류, window) 에 거는 상한. 토큰과 비용 중 하나 이상 |
| **charge** | LLM 호출 1회의 usage 를 그 실행에 걸린 모든 quota key 에 더하는 것 |

`PRINCIPAL` 범위의 id 는 `<type>/<id>` 다(`user/alice`). `Principal` 의 동일성은 종류와 id 둘이므로(`Principal#equals`),
같은 id 의 사용자와 서비스는 다른 주체이고 다른 키를 가져야 한다.

**quota 와 budget.** budget 은 계속 `ExecutionBudget`(실행 1회)의 뜻이다. 누적 한도는 quota 라고 부르고, 이 문서에서
"쿼터" 는 usage quota 를 줄인 말이다. 같은 단어를 쓰면 `/budget` 으로 턴 예산을 올려도 쿼터는 그대로라는 사실이 이름에서
숨는다.

이름이 비슷하지만 이 쿼터가 아닌 것이 둘 있다.

- 스케줄링의 **작업 수 할당량**(`TaskQuotaManager`, 소유자별 최대 작업 수)은 이 쿼터가 아니다. 정의는
  [llm-scheduling-agent §3.5](../scheduling/llm-scheduling-agent.md#35-할당량) 에 있다
- `ToolCallQuota` 는 이름과 달리 실행 1회의 도구 호출 상한이다. 누적 한도가 아니므로 이 문서의 쿼터가 아니라 budget
  무리이고, 넘으면 `TOOL_CALL_BUDGET_EXCEEDED` 로 끝난다

---

## 3. 타입

모두 `at.aimon.core.agent.quota` 에 있다(기본 구현은 `.impl`, 예외는 `.exception`). 값 객체는 빌더를 가진 불변 클래스다.

### 3.1 값 객체와 SPI

| 타입 | 역할 |
|------|------|
| `QuotaScope` | scope 한 개 — `Kind`(`SESSION` · `PRINCIPAL` · `TENANT`)와 id |
| `QuotaWindow` | `TOTAL` · `DAILY` |
| `QuotaKey` | scope + window + 버킷(`TOTAL` 은 빈 문자열, `DAILY` 는 날짜) |
| `QuotaUsage` | 토큰과 비용. `plus` 로 더한다 |
| `QuotaLimit` | window 와 최대 토큰·최대 비용(둘 다 비면 한도 없음) |
| `QuotaViolation` | 막은 key, 그 limit, 사용량 |
| `UsageLedger` | 장부 SPI — `charge(keys, usage)` 와 `get(keys)`. 저장소 교체 지점 |
| `QuotaScopeResolver` | 실행 하나에 걸리는 scope 목록을 뽑는다. 애플리케이션이 주입한다 |
| `QuotaPolicy` | scope 에 어떤 limit 들을 거는가. 정적 설정(`StaticQuotaPolicy`)이 기본이고 동적 구현이 가능하다 |
| `QuotaResolutionContext` | resolver 의 입력 — `agentRuntimeId`, `sessionId`(없을 수 있음), `principal`, `attributes` |

### 3.2 `QuotaGuard` 와 `QuotaGuardFactory`

루프가 부르는 객체는 `QuotaGuard` 하나다. `check()` 는 걸린 key 중 하나라도 limit 에 닿았으면 그 위반을
`Optional<QuotaViolation>` 으로 돌려주고, `charge(modelName, usage, cost)` 는 LLM 호출 1회분을 모든 key 에 기록한다.

`QuotaGuard` 는 생성 시점에 scope 목록과 limit 목록을 **고정**한다. 기간 버킷만은 `check` / `charge` 때마다 주입된
`Clock` 과 `ZoneId` 로 다시 계산한다 — 자정을 넘는 긴 실행이 다음 날 버킷에 기록되게 하기 위해서다.

`QuotaGuardFactory` 는 장부·정책·resolver·`Clock`·`ZoneId`·`failClosed` 를 든 객체이고, 실행기의 실행마다(턴도, rewake 리플레이 같은
세션 없는 실행도. 예약 루틴은 루틴 실행마다) `create(QuotaResolutionContext)` 로 guard 를 만든다. 실행기는 에이전트 범위의 싱글턴인데 guard 는 실행마다 새로
해석되어야 하기 때문이다. 실행기의 기본값은 `QuotaGuardFactory.disabled()` 이고, 그때 모든 실행은
`QuotaGuard.disabled()` 를 받는다. [§1.2](#12-원칙) 의 무변경 기본값은 이 한 자리에서 지켜진다.

`UsageLedger` 와 `QuotaGuardFactory` 는 application-scoped 다. 실행기·스케줄링 엔진은 빌려 쓸 뿐 닫지 않는다.
`QuotaGuard` 는 실행 1회의 값이라 닫을 것이 없다. 수명의 일반 규칙은 [scope-model](../../overview/scope-model.md) 에 있다.

### 3.3 충전 규칙

- 충전되는 토큰은 `TokenUsage#getTotalTokens()` 다
- 비용이 없거나(`CostEstimator.NOOP`) USD 가 아니면 비용은 0 으로 기록된다. 비용 예산 축(`ExecutionBudget.maxCostUsd`)과
  같은 규칙이다
- 토큰도 비용도 0 인 호출은 장부에 쓰지 않는다
- 한 범위에 같은 기간의 한도가 둘이면 키 하나를 공유하고 한 번만 충전된다. 판정은 한도마다 따로 한다

### 3.4 왜 `SessionTotals` 를 쓰지 않는가

세션 scope 도 같은 장부에 넣는다. `SessionTotals` 는 통계로 그대로 남는다. 이유는 셋이다.

- `SessionTotals` 는 **턴이 끝날 때** 접힌다. 턴 도중의 사용량을 보지 못한다
- 포크 사용량이 들어가지 않는다([§1.1](#11-무엇을-푸는가))
- 쓰기가 **절대값**이다. 세션 키에서는 리스가 단일 작성자를 보장해 괜찮지만, 주체·테넌트 키는 여러 노드가 동시에 쓰므로
  **delta increment** 가 필수다. 두 의미를 한 저장소에 섞지 않는다

---

## 4. 실행 경로

### 4.1 기록 지점

LLM 응답의 사용량을 장부에 기록하는 곳은 셋이다. 모두 호출 1회의 비용을 `CostEstimator` 로 계산하고, 같은 값을
`quotaGuard.charge(...)` 에 넘긴다.

| 경로 | 위치 |
|------|------|
| 턴 (메인 ReAct 루프) | `OrcaAgentExecutor` — `budgetTracker.recordTokens(...)` 직후 |
| 포크 (`Task` · `Workflow` · 스킬 포크) | `DefaultSubagentExecutor` — `budgetTracker.recordTokens(...)` 직후 |
| 인라인 LLM 스킬 (`/skill` 루프, 루틴 단계가 부르는 스킬) | `LlmSkillExecutor#sendAndCharge` |

`LlmSkillExecutor` 의 비용 estimator 는 `OrcaAgentExecutorFactory` 가 기본 명령 실행 매니저를 만들 때 붙인다. 명령 실행
매니저를 직접 넘기는 호스트는 `LlmSkillExecutor#withCostEstimator` 를 직접 불러야 `/skill` 호출에 비용이 매겨진다. 부르지
않으면 그 호출은 토큰만 기록된다.

이 셋을 지나지 않는 LLM 호출은 쿼터에 잡히지 않는다([§10](#10-범위-밖)).

### 4.2 판정 지점

세 루프 모두 LLM 호출 **전에** `quotaGuard.check()` 를 부른다. 턴 루프와 포크 루프는 iteration 경계에서
`budgetTracker.check()` 바로 뒤에 부르고, 포크 루프는 iteration 0 을 포함해 매 iteration 전에 판정한다.
`LlmSkillExecutor` 는 첫 호출 전과 그 뒤의 매 호출 전에 판정한다. 위반이면 `CompletionReason.QUOTA_EXCEEDED` 로
끝낸다([§6](#6-멈춘-실행--quota_exceeded)).

별도의 "턴 시작 전 거부" 경로는 두지 않는다. 이미 소진된 쿼터는 첫 iteration 의 판정에서 LLM 호출 없이 멈추고, 결과는
iteration 0 의 `QUOTA_EXCEEDED` 다. 같은 판정이 한 자리에만 있어야 두 경로가 어긋나지 않는다.

판정은 다음 LLM 호출을 막을 뿐 도구를 막지 않는다. 도구는 LLM 이 요청했기 때문에 돌므로 결과적으로 멈출 뿐이고, 한도를
넘긴 응답에 든 도구 호출은 그대로 돈다.

예약 루틴의 재시도 검사([§9.4](#94-재시도-검사))는 두 번째 판정 지점이 아니다. 이미 쿼터로 멈춘 시도를 다시 시작할지만
정하고, 새 LLM 호출을 막는 판정은 여전히 포크와 스킬의 호출 직전 한 자리에 있다.

### 4.3 포크 전파

포크는 guard 를 스스로 만들지도 해석하지도 않는다. 부모 실행의 guard 를 **그대로** 쓴다.

scope 를 포크에서 다시 해석하지 않는 이유: 포크에는 세션이 없다(정체성은 `SessionId` 가 아니라 `ExecutionId`). 다시
해석하면 세션 scope 가 빠지고, 그것이 바로 [§1.1](#11-무엇을-푸는가) 의 우회로다. 전달되는 것은 resolver 가 아니라
**이미 결정된 key 목록**이다.

스폰 도구는 부모 실행을 모르고 **도구 컨텍스트**만 안다. 그래서 guard 는 도구 컨텍스트를 거쳐 내려간다.

```
OrcaAgentExecutor ─┐
RoutineExecutor ───┴─ ToolContextKeys.QUOTA_GUARD ─▶ TaskTool · WorkflowTool · WorkflowJs
                                                     · fork skill · LlmSkillExecutor
                                                     └─▶ SubagentLaunchContext.quotaGuard
DefaultSubagentExecutionManager ─▶ SubagentExecutionRequest.quotaGuard ─▶ DefaultSubagentExecutor
DefaultSubagentExecutor ─ re-publishes QUOTA_GUARD as is ─▶ forks inside the fork
```

- `QUOTA_GUARD` 는 **write-once** 키다. `ToolContextEnricher` 가 `disabled()` 로 바꿔 끼워 그 아래의 포크 전체를 계량
  밖으로 빼는 것을 막기 위해서다
- 슬래시 명령(`/my-skill`) 턴이 만드는 도구 컨텍스트에도 같은 guard 를 싣는다. 포크 스킬이 그 길로도 포크를 띄우기 때문이다
- 중첩 포크와 병렬 포크는 모두 같은 guard 를 공유한다. guard 는 상태가 없으므로(key 목록과 limit 은 불변, 누적치는 장부에)
  장부 구현이 스레드 안전하면 충분하다
- 백그라운드 포크는 부모 실행이 끝난 뒤에도 그 guard 로 기록을 계속한다. 턴이든 루틴이든 같다 — 사용량은 사용량이다

**백그라운드 `Workflow`.** 백그라운드 `Workflow` 실행은 호출한 실행의 guard 를 `BackgroundSubmission` 에 실어 받는다.
에이전트 단위 runner 의 기본 launch context 는 호출자에게서 아무것도 물려받지 않으므로, guard 를 싣지 않으면 쿼터를 다 쓴
세션이 같은 일을 백그라운드로 띄워 계량을 피한다. 좁히는 규칙 전체는
[PTC §7.2](../tool/programmatic-tool-calling.md#72-에이전트-설정) 에 있다.

그 기본 launch context 자체는 비활성 guard 를 갖는다. 물려줄 호출한 실행이 없기 때문이다. 그래서 앱 코드가 runner 를
직접 부르면 계량되지 않는다 — backlog UQ-2.

### 4.4 scope 해석

`QuotaScopeResolver` 의 입력은 실행에서 나온다. 턴과 예약 루틴은 같은 `QuotaResolutionContext` 를 다른 출처로 채운다.

| 필드 | 턴 | 예약 루틴 | 없으면 |
|------|-----|-----------|--------|
| `sessionId` | 요청의 `getSessionId()` | 없음 | `SESSION` 범위를 걸지 않는다 |
| `principal` | 요청의 `getPrincipal()`(턴마다 `submitOptions` 에서 옴) | `task.getOwner()` | `PRINCIPAL` 범위를 걸지 않는다 |
| `agentRuntimeId` | 실행하는 런타임 | `task.getBoundRuntimeId()` | — |
| `attributes` | 요청의 `executionAttributes` | 실행 속성(`RoutineExecutionAttributes`) | — |

세션 범위는 요청의 `getSessionId()` 에서만 온다. 트랜스크립트가 세션 없는 요청에 발급하는 id 는 쓰지 않으므로, 세션 id
없이 들어온 실행(rewake 리플레이 등 — 턴이 아닌 세션 없는 실행)에는 세션 범위가 걸리지 않는다.

**테넌트는 코어가 정하지 않는다.** 이 저장소에서 테넌트는 bootstrap 의 관례(`AgentRuntimeId` 의 discriminator,
`agent:ops:acme`)일 뿐이다. 그 관례를 쓰는 앱도, `Principal` 속성이나 실행 속성을 쓰는 앱도 있으므로 코어는 기본 resolver
하나(`DefaultQuotaScopeResolver`)만 준다. 그것은 `SESSION` 과 `PRINCIPAL` 만 뽑고, 테넌트는 생성자에 넘긴 함수
(`Function<QuotaResolutionContext, Optional<String>>`)가 있을 때만 뽑는다. 예약 루틴의 `attributes` 가 턴의
`executionAttributes` 자리에 들어가는 것도 resolver 가 같은 방법으로 테넌트를 읽게 하기 위해서다.

---

## 5. soft limit

### 5.1 초과 상한

판정은 호출 **전**, 기록은 호출 **뒤**다. 호출 1회의 토큰 수는 응답이 끝나야 알 수 있으므로 정확한 차감은 원리적으로
불가능하다. 초과량의 상한은 다음과 같다.

```
최대 초과 ≈ (그 key 에 동시에 진행 중인 LLM 호출 수) × (호출 1회분 usage)
```

| scope | 동시 호출 | 상한 |
|-------|----------|------|
| `SESSION` | 세션 리스(fencing token)가 실행자를 하나로 보장한다. 다만 병렬 포크가 있으면 그 수만큼 | 1회분 × (1 + 동시 포크 수) |
| `PRINCIPAL` · `TENANT` | 그 주체의 동시 실행 수만큼 | 1회분 × 동시 실행 수 |

이 상한은 분석값이다. 병렬 포크가 같은 키를 동시에 넘길 때 초과량이 이 안에 드는지는 동시성 테스트로 확인하지 않았다.

이 정도면 "폭주를 끊는다" 와 "기간별 사용량을 대략 맞춘다" 라는 목적에는 충분하다. 쿼터 장부를 과금 정산의 정본으로 쓰지
않는다. 정산의 정본은 프로바이더 청구서다.

### 5.2 장부 장애

`UsageLedger` 호출이 예외를 던지면 기본은 **fail-open** 이다. WARN 을 한 줄 남기고 실행을 계속한다. 쿼터가 soft 인 이상,
장부 장애가 모든 에이전트를 멈추는 것은 지키려던 것보다 큰 손실이다.

`failClosed(true)` 로 뒤집을 수 있다. fail-closed guard 는 장부 예외를 `QuotaLedgerUnavailableException` 으로 던지고, 루프는
일반 오류 경로에서 `ERROR` 로 끝낸다. `QUOTA_EXCEEDED` 가 아닌 이유는 쿼터를 넘은 것이 아니라 판정할 수 없었던 것이기
때문이다. 예외로 전달하는 이유는 `check()` 의 `Optional<QuotaViolation>` 에 "판정할 수 없었다" 를 담을 칸이 없기 때문이다.
호출이 끝난 뒤의 기록(`charge`) 실패도 `ERROR` 다 — 기록되지 않은 사용량을 두고 다음 호출을 내는 것이 바로 fail-closed 가
막으려는 일이다.

**resolver·정책 실패는 장부 장애가 아니다.** `failClosed` 는 장부 장애만 다룬다. guard 를 만드는 중에 애플리케이션의
resolver 나 정책이 던지면 WARN 을 남기고 계량 없이 진행한다.

- 턴: guard 는 트랜스크립트를 저장하는 try/finally 보다 앞에서 만들어진다. 거기서 던지면 턴이 결과 없이 사라지므로,
  기록을 잃지 않게 계량을 포기한다. 같은 자리의 트레이싱 설정도 같은 이유로 fail-safe 다
- 예약 루틴: 고장 난 앱 resolver 가 실행을 멈추지 않게 한다([§9.2](#92-게시-조건))

---

## 6. 멈춘 실행 — `QUOTA_EXCEEDED`

쿼터로 멈춘 실행은 `CompletionReason.QUOTA_EXCEEDED` 로 끝난다. `COST_BUDGET_EXCEEDED` / `TOKEN_BUDGET_EXCEEDED` 를
재사용하지 않는 이유: 그 둘은 "실행 예산을 올리면 풀린다" 를 뜻하고, 쿼터는 그렇지 않다. 사용자에게 줄 다음 행동이 다르면
값도 달라야 한다.

**분류.** `QUOTA_EXCEEDED` 는 한도에 의한 종료로, 예산 멈춤(`COST_BUDGET_EXCEEDED` 등)과 같은 무리다. 실패한 실행
(`ERROR`)이 아니지만 성공도 아니다(`isSuccessful()` 거짓). 소비자 대부분은 `CompletionReason` 을 `COMPLETED` ·
`INTERRUPTED` · 그 밖으로 읽으므로 새 값을 따로 처리하지 않아도 이 분류가 성립한다. 한도 멈춤을 따로 묶는 자리
(`DefaultSubagentExecutionManager#isBoundedStop` — 포크 span 을 오류로 표시하지 않을 사유)는 `QUOTA_EXCEEDED` 를 예산 멈춤과
같은 줄에 넣는다. 새 한도 사유를 더하면 그 목록에도 넣어야 한다.

- `AgentStepResult#isComplete()` 는 거짓이다
- 노드 간 코덱은 `fromWireName` 으로 읽으므로, 이 값을 모르는 노드는 `ERROR` 로 받는다
- CLI 는 지금 이 무리를 오류 결과로 그린다. 위반을 따로 보이는 표시는 backlog UQ-5 다

**위반의 운반.** 어느 key 가 막았는지(`QuotaViolation` — scope, window, 사용량, 한도)는
`AgentExecutionResult#getQuotaViolation()`(기본 메서드, 빈 값)과 `OrcaAgentExecutionResult`, `SubagentExecutionResult` 에
실린다. 라우팅 와이어(`TurnResultPayload`)와 저장된 결과는 이름과 메시지만 싣고 위반은 싣지 않는다 — backlog UQ-3.

**포크가 부모 LLM 에게 하는 말.** 포크 결과의 `Completion reason:` 줄에서 `QUOTA_EXCEEDED` 에 붙는 문장은 "이 작업 전체가
걸린 쿼터를 다 썼다 — 다시 하거나 다른 서브에이전트를 띄워도 같은 한도에 막힌다, 가진 것으로 끝내라" 를 뜻한다. 예산
멈춤과 달리 재시도가 답이 아니라는 것을 부모가 알아야 같은 한도에 부딪히는 포크를 반복해 띄우지 않기 때문이다. 줄이 붙는
자리와 CLI 파서 호환은 [execution-policy §8](../subagent/execution-policy.md#8-결과-계약) 이 정본이다.

턴의 멈춤 메시지는 `Execution stopped by quota: <위반>` 이고, 포크와 인라인 스킬의 오류는
`Execution stopped: QUOTA_EXCEEDED — <위반>` 으로 시작한다. 예약 루틴의 재시도 검사는 뒤의 문구에 기댄다([§9.4](#94-재시도-검사)).

---

## 7. 설정 모양

쿼터는 `aimon-bootstrap` 의 `AimonStackSpec` 에 든 선택 항목 `QuotaSpec` 으로 켠다. 예시 코드는
[가이드](../../features/agent-execution/usage-quota-guide.md) 에 있고, 여기서는 모양이 곧 결정인 부분만 적는다.

- **`ledger` 를 생략하면 쿼터는 꺼진다.** `QuotaSpec#toGuardFactory()` 가 비활성 팩토리를 돌려준다
- **`policy`** 는 정적 정책(`StaticQuotaPolicy`)이 기본이다. scope 종류마다 limit 을 건다
- **테넌트는 `resolver` 의 함수로** 정한다([§4.4](#44-scope-해석))
- **`zone` 을 정하지 않으면 UTC 다.** 노드마다 시스템 시간대가 달라도 같은 키를 서로 다른 날짜 버킷에 쓰지 않게 하기
  위해서다
- **`clock`** 은 주입받는다. 테스트가 날짜 경계를 넘길 수 있게 하기 위해서다
- **`failClosed`** 의 기본은 `false` 다([§5.2](#52-장부-장애))

부트스트랩은 `QuotaSpec#toGuardFactory()` 를 한 번 계산해 실행기 팩토리(`OrcaAgentExecutorFactory#withQuotaGuardFactory`)와
스케줄링 엔진(`SchedulingEngineBuilder#quotaGuardFactory`)에 같은 인스턴스를 넘긴다. 턴과 루틴이 한 팩토리와 한 장부를
공유한다는 것이 구조로 드러나게 하기 위해서다.

**비용 축의 전제.** 비용 한도는 `CostEstimator` 가 배선되어야 의미가 있다. 없으면 모든 호출이 0원으로 기록되고 비용 한도는
절대 발동하지 않는다 — `ExecutionBudget.maxCostUsd` 와 같은 규칙이다. 비용 한도가 설정되었는데 estimator 가 `NOOP` 이면
bootstrap 이 `RuntimeDegradations` 에 `quota-cost` 항목을 남긴다. "설정되었지만 효과가 없다" 를 알리는 기존 방식을 따른
것이다. 비용 한도를 볼 수 있는 것은 `StaticQuotaPolicy#hasCostLimit()` 뿐이라, 사용자 정의 `QuotaPolicy` 의 비용 한도는
알리지 못한다.

Spring Boot starter 에는 쿼터 프로퍼티가 없다. starter 로 만든 스택은 `QuotaSpec` 장부가 없으므로 쿼터가 꺼진 채로 돈다 —
backlog UQ-5.

---

## 8. 장부 계약

`UsageLedger` 는 두 연산을 갖는다. `charge(keys, usage)` 는 keys 각각에 usage 를 더하고, `get(keys)` 는 현재 누적치를
돌려준다(없는 키는 zero). 원자성은 **키 단위로만** 요구한다. 키 사이의 트랜잭션은 필요 없다.

- **delta 를 원자적으로 더한다.** 공유 저장소 구현은 키마다 delta 를 원자적으로 더해야 하고, 절대값으로 덮어쓰지 않는다.
  여러 노드가 같은 주체·테넌트 키에 동시에 쓰기 때문이다
- **비용은 마이크로달러 `long` 으로 저장한다.** 부동소수점 누적 오차 없이 저장소의 정수 증가 연산을 쓰기 위해서다.
  `Money` 로의 변환은 경계에서 한 번 한다
- **`DAILY` 키의 보존 기간은 구현체 설정이다.** `InMemoryUsageLedger` 의 기본은 7일이다
- **`TOTAL` 키는 만료하지 않는다**
- **장부는 application-scoped 다**([§3.2](#32-quotaguard-와-quotaguardfactory))

기본 구현은 `aimon-core` 의 `InMemoryUsageLedger` 하나이고, 단일 노드에서 세 scope 와 일 단위 한도가 모두 동작한다. 노드
간 공유 장부 구현은 제공하지 않는다. 열린 일은 backlog UQ-5 · UQ-6 이다(공유 장부와 세션 삭제 때의 세션 `TOTAL` 키 정리는
UQ-6).

---

## 9. 예약 루틴

예약 루틴 실행은 턴처럼 guard 하나를 만들어 단계에 물려준다. 포크는 guard 를 스스로 해석하지 않고 물려받기만 하므로,
루틴이 guard 를 싣지 않으면 쿼터를 다 쓴 소유자가 같은 LLM 작업을 cron 으로 계량 없이 돌릴 수 있기 때문이다.

`RoutineExecutor` 는 application-scoped `QuotaGuardFactory` 를 `withQuotaGuardFactory` 로 받는다(부트스트랩에서는
`SchedulingEngineBuilder#quotaGuardFactory` 를 거쳐 실행기와 같은 인스턴스, [§7](#7-설정-모양)). 받지 않으면 기본값은
`QuotaGuardFactory.disabled()` 이고, 루틴은 쿼터가 없을 때와 똑같이 돈다.

### 9.1 실행 1회에 guard 하나

루틴은 단계마다가 아니라 실행 1회에 guard 하나를 만든다. 포크가 지니는 정체성(`ExecutionId routine:<taskId>`)이 실행
단위이고, 단계의 도구 컨텍스트는 실행마다 한 번 만들어지며 `QUOTA_GUARD` 는 write-once 이기 때문이다. 단계마다 만들어도
scope 와 limit 은 생성 때 고정되고 누적은 장부에 있으므로 집행은 달라지지 않는다. resolver·정책 호출만 늘고, 실행 도중
바뀐 정책이 실행의 일부에만 적용된다.

해석 입력은 새 타입 없이 `QuotaResolutionContext` 를 쓴다([§4.4](#44-scope-해석) 의 표). 세션이 없으므로 `SESSION` 범위는
걸리지 않고, 주체는 태스크 소유자다. 결과·이벤트·영속 모양에는 새 필드가 없다.

guard 는 실행 속성을 해석한 뒤, 도구 컨텍스트를 만들기 전에 만든다. 실행 하나가 팩토리 하나를 쓰도록 팩토리 필드는 한 번만
읽는다.

### 9.2 게시 조건

- **팩토리가 비활성이면** 아무것도 해석하지 않고 `QUOTA_GUARD` 도 싣지 않는다. 도구 컨텍스트의 키 집합이 쿼터가 없을 때와
  같다
- **팩토리가 활성이면** 해석한 guard 를 `QUOTA_GUARD` 로 싣는다
- **활성인데 resolver 나 정책이 던지면** WARN 을 남기고 그 실행은 계량 없이 돈다. 이때도 비활성 guard 를 싣는다. 키가
  write-once 이므로 자리를 정직하게 채워 두면 뒤에서 아무도 그 자리를 차지할 수 없다

`TaskTool`, 포그라운드·백그라운드 `WorkflowTool`, `WorkflowJs`, `SkillTool` 의 포크 분기(`SubagentBackedSkillForkExecutor`)는
이미 `QUOTA_GUARD` 를 읽으므로 그대로 계량된다. 루틴 단계가 부르는 인라인 LLM 스킬(`LlmSkillExecutor`)도 단계 컨텍스트의
`QUOTA_GUARD` 를 읽으므로 같은 guard 로 판정·기록된다.

### 9.3 단계 게이트를 두지 않는다

`RoutineExecutor` 는 쿼터를 이유로 단계를 막지 않는다. 판정은 이미 토큰을 쓰는 자리 — 포크의 매 iteration 전
(`DefaultSubagentExecutor`, iteration 0 포함)과 인라인 LLM 스킬의 매 호출 전(`LlmSkillExecutor`) — 에 있고, 턴의 판정도
다음 LLM 호출을 막을 뿐 도구는 막지 않는다([§4.2](#42-판정-지점)).

단계 게이트를 두면 더해지는 효과는 둘뿐이다. LLM 을 쓰지 않는 단계(`Bash` · `Read`)를 막는 것, 그리고 시스템 루틴 하나가
공유 키를 다 쓴 뒤 `Principal.system()` 소유 태스크 전체를 막는 것이다. `TOTAL` 창에서는 그 차단이 무기한 이어져 운영
자동화의 가용성을 해친다. 토큰을 쓰는 자리에서는 게이트가 더하는 보호가 없다.

그래서 쿼터를 넘은 소유자의 루틴에서도 LLM 을 부르지 않는 단계는 그대로 돌고, LLM 작업을 시작하는 단계는 그 안의 포크나
스킬이 첫 호출 전에 멈춘다.

### 9.4 재시도 검사

루틴이 쿼터에 대해 스스로 하는 일은 하나다. 실패한 시도를 재시도할지 정할 때 guard 를 묻는다.

- **어떤 시도에 걸리나.** 시도의 오류에 `Execution stopped: QUOTA_EXCEEDED` 가 들어 있을 때만 걸린다. 이 문구는 단계의
  오류에 닿을 수 있는 모든 쿼터 멈춤의 머리다 — 포크(`DefaultSubagentExecutor`), 인라인 스킬(`LlmSkillExecutor`), 포크의
  오류를 감싸는 포크 스킬. 낱말 `QUOTA_EXCEEDED` 만으로 맞추면 클라우드 CLI 처럼 자기 자원의 쿼터 오류를 내는 도구가
  재시도를 잃고 엉뚱한 쿼터로 보고된다. 포크 스킬은 포크의 오류를 `Skill fork failed for '…': ` 로 감싸므로 접두사가 아니라
  부분 문자열로 맞춘다
- **guard 가 확인한다.** 그때 guard 가 여전히 위반을 보고해야 재시도를 건너뛴다. 그 밖의 이유로 실패한 시도는 소유자가
  한도를 넘었어도 설정대로 재시도한다
- **백오프 전에 묻는다.** 쿼터로 멈춘 시도가 분 단위일 수 있는 `retryDelay` 를 다 기다린 뒤에야 같은 이유로 멈추지 않게
  하기 위해서다
- **취소된 실행에는 묻지 않는다.** 멈춘 실행을 고장 난 실행으로 기록하지 않는다는 루틴의 규칙을 따른다
- **장부가 답하지 못하면 재시도한다.** 검사 중 장부가 `QuotaLedgerUnavailableException` 을 던지면 답이 없는 것으로 보고
  WARN 을 남긴 뒤 설정대로 재시도한다. 재시도는 포크 안에서 장부를 직접 만난다

재시도를 건너뛴 단계의 오류는 `Execution stopped by quota: <위반> (last attempt: <시도의 오류>)` 다. 앞부분은 턴의 멈춤
문구를 그대로 써서 로그와 알림이 문자열 하나로 턴과 루틴의 쿼터 멈춤을 함께 잡게 하고, 괄호 안에는 마지막 실제 시도의
오류를 남겨 잃지 않게 한다.

### 9.5 성공으로 기록되는 쿼터 멈춤

`Task` 단계의 포크가 쿼터로 멈추면 그 단계는 성공이다. `TaskTool` 은 모든 포크 멈춤(`MAX_ITERATIONS`, 예산 멈춤 포함)을
성공 결과 안의 `Completion reason:` 줄로 알리기 때문이다. `Workflow` 도 같다. 그래서 `Task` 포크가 모두 쿼터로 멈춘 루틴은
토큰을 쓰지 않은 성공 실행으로 기록되고 `TaskFailedEvent` 가 나지 않는다. 이것을 구조로 드러내는 일은 backlog UQ-4 다.

그동안의 신호로, 계량되는 실행이 성공으로 기록되려 할 때 guard 를 한 번 더 물어 위반이면 태스크와 위반을 담은 WARN 을 한
줄 남긴다.

- 아무것도 막지 않는다. 성공한 계량 실행마다 장부를 한 번 읽는다
- 마지막 단계의 초과 뒤에도 남는다. 다음 실행의 포크가 멈출 것이므로 맞는 신호다
- guard 는 단계가 LLM 작업을 시작했는지 모른다. 그래서 `Bash` 만 쓰는 루틴에서도 소유자가 한도를 넘었으면 WARN 이 남고,
  문구는 단정하지 않는다("may have done less")
- 장부를 읽지 못하면 DEBUG 로 남기고 건너뛴다. 실행은 끝났고 포크가 자기 장부 장애를 이미 보고했다
- 실패·취소된 실행에는 남기지 않는다. 이미 실패로 기록되어 있다

### 9.6 실패 모드

| 경우 | 처리 |
|------|------|
| 팩토리 없음 / `disabled()` | resolver 를 부르지 않고 키를 싣지 않는다. 쿼터가 없을 때와 같다 |
| guard 를 만들다 resolver·정책이 던짐 | WARN, 그 실행은 비활성 guard 로 계량 없이 돈다. 키는 비활성 guard 로 싣는다 |
| 소유자가 이미 한도를 넘음 | LLM 을 쓰지 않는 단계는 돈다. 포크·스킬은 첫 호출 전에 `QUOTA_EXCEEDED` 로 멈춘다 |
| 포크가 단계 도중 한도를 넘김 | 포크가 자기 iteration 판정에서 멈춘다. 단계가 무엇을 보고하는지는 도구가 정한다(`Task`: 성공 결과 안의 사유 줄 / 포크 스킬: 오류). 오류로 보고된 쿼터 멈춤은 재시도 검사가 재시도를 건너뛴다 |
| 마지막 단계에서 포크가 한도를 넘김 | 실행은 성공으로 기록된다. soft limit 의 초과분이고, 실행이 끝난 뒤 다시 판정해 이미 끝난 일을 실패로 바꾸지 않는다 — 턴의 마지막 iteration 과 같은 규칙이다. 성공 실행 WARN 은 남는다 |
| fail-open 장부가 판정 중 실패 | guard 가 WARN 을 남기고 빈 값을 돌려준다. 실행은 계속된다 |
| fail-closed 장부가 포크 안에서 실패 | 포크가 `ERROR` 로 끝내고, 단계는 도구가 보고한 대로 기록된다 |
| fail-closed 장부가 재시도 검사에서 실패 | 답이 없는 것으로 보고 설정대로 재시도한다 |
| 취소와 쿼터가 겹침 | 취소된 실행에는 재시도 검사를 하지 않는다. 실행은 `cancelled` 로 기록된다 |
| 다중 노드 | guard 는 상태가 없고 누적치는 `UsageLedger` 에 있다. 공유 장부를 쓰면 노드를 넘어 계량된다. 노드 로컬 상태를 더하지 않는다 |
| 수명 | 팩토리와 장부는 빌린 것이다. `RoutineExecutor#shutdown` 은 건드리지 않는다. guard 는 실행 1회의 값이라 닫을 것이 없다 |
| 실행보다 오래 사는 백그라운드 포크 | 실행이 끝난 뒤에도 그 실행의 guard 로 충전을 계속한다([§4.3](#43-포크-전파)) |

---

## 10. 범위 밖

- **세 기록 지점([§4.1](#41-기록-지점))을 지나지 않는 LLM 호출**은 세지 않는다. compaction 요약 호출 등은 `BudgetTracker`
  에도 잡히지 않는 호출이며, 쿼터에 넣으려면 먼저 한 기록 경로로 모아야 한다. 남은 호출 목록은 backlog UQ-1 이 갖는다
- **hard limit**(예약 후 차감). 응답 크기를 미리 알 수 없어 예약량을 정할 근거가 없다
- **임베딩 · 지식 검색 비용**. `CostEstimator` 가 LLM 채팅 호출만 가격을 매긴다
- **도구 실행 횟수·시간** 쿼터
- **과금 정산**. 쿼터 장부는 폭주를 끊는 장치다([§5.1](#51-초과-상한))

---

## 11. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|------------------|
| 누적 한도의 이름 | quota. budget 은 실행 1회(`ExecutionBudget`)로 남긴다 | budget 을 넓혀 쓴다 — `/budget` 으로 턴 예산을 올려도 쿼터는 그대로라는 사실이 이름에서 숨는다 |
| 세션 누적을 어디서 재나 | 세션도 같은 장부의 한 scope 로 넣는다 | `SessionTotals` 위에 한도를 건다 — 턴이 끝날 때만 접히고, 포크 사용량이 없고, 절대값 쓰기라 여러 노드의 delta 와 섞을 수 없다 |
| 차감 방식 | soft limit — 호출 전 판정, 호출 뒤 기록, 상한을 문서화 | hard limit(예약 후 차감) — 응답 크기를 미리 알 수 없어 예약량의 근거가 없다 |
| guard 를 누가 만드나 | application-scoped `QuotaGuardFactory` 가 실행마다 `create` | 실행기가 guard 를 직접 든다 — 실행기는 에이전트 싱글턴이고 guard 는 실행마다 해석되어야 한다 |
| 포크의 scope | 부모의 guard(결정된 key 목록)를 그대로 쓴다 | 포크에서 다시 해석 — 포크에는 세션이 없어 세션 scope 가 빠지고, 그것이 우회로다 |
| 전파 키 | `QUOTA_GUARD` 는 write-once | 덮어쓸 수 있는 키 — enricher 가 `disabled()` 로 바꿔 끼워 그 아래 포크 전체를 계량 밖으로 뺄 수 있다 |
| 판정 자리 | 각 루프의 LLM 호출 직전 한 자리 | 턴 시작 전 별도 거부 경로 — 같은 판정이 두 곳에 있으면 어긋난다. 소진된 쿼터는 iteration 0 판정이 LLM 호출 없이 멈춘다 |
| 멈춘 실행의 사유 | 새 값 `QUOTA_EXCEEDED` | `COST_BUDGET_EXCEEDED` / `TOKEN_BUDGET_EXCEEDED` 재사용 — 그 둘은 "실행 예산을 올리면 풀린다" 를 뜻하고, 사용자에게 줄 다음 행동이 다르다 |
| 장부 장애의 기본 | fail-open(WARN 후 계속) | fail-closed 기본 — soft 한 쿼터를 지키려고 장부 장애로 모든 에이전트를 멈추는 것은 더 큰 손실이다 |
| fail-closed 의 결과 | `QuotaLedgerUnavailableException` → `ERROR` | `QUOTA_EXCEEDED` 로 끝낸다 — 쿼터를 넘은 것이 아니라 판정할 수 없었던 것이다. `Optional` 에는 그 칸이 없다 |
| resolver·정책 실패 | WARN 후 계량 없이 진행(턴·루틴 공통) | 실행을 실패시킨다 — 턴은 트랜스크립트 저장 전에 결과 없이 사라지고, 루틴은 앱 resolver 고장으로 멈춘다 |
| 테넌트 | 코어는 개념을 두지 않고 resolver 의 함수로 받는다 | 코어가 테넌트를 정의 — 지금의 테넌트는 bootstrap 의 discriminator 관례일 뿐이고 앱마다 출처가 다르다 |
| `DAILY` 버킷 시간대 | 기본 UTC, `zone` 으로 바꾼다 | 시스템 시간대 — 노드마다 달라 같은 키를 서로 다른 날짜 버킷에 쓴다 |
| 비용 저장 형식 | 마이크로달러 `long` | 부동소수점 — 누적 오차가 생기고 저장소의 정수 증가 연산을 못 쓴다 |
| 팩토리 공유 | 부트스트랩이 한 인스턴스를 실행기와 스케줄링 엔진에 넘긴다 | `toGuardFactory()` 를 두 번 부른다 — 팩토리가 불변이고 장부가 같아 동작은 같지만, 턴과 루틴이 한 장부를 공유한다는 사실이 구조로 보이지 않는다 |
| 루틴의 guard 단위 | 실행 1회에 하나 | 단계마다 하나 — 집행은 같고 resolver·정책 호출만 늘며, 실행 도중 바뀐 정책이 실행의 일부에만 적용된다 |
| 루틴 단계 게이트 | 두지 않는다 | 단계 경계에서 guard 로 단계를 막는다 — 토큰을 쓰는 자리에는 판정이 이미 있다. 더해지는 효과는 LLM 을 쓰지 않는 단계와 `Principal.system()` 소유 태스크 전체를 막는 것뿐이고, `TOTAL` 창에서는 무기한 이어져 가용성을 해친다 |
| 쿼터로 멈춘 `Task` 포크의 결과 | 성공 결과 안의 `Completion reason:` 줄 | `TaskTool` · `WorkflowTool` 이 `ToolResult.error` 를 돌려준다 — 모든 턴에서 부모 LLM 이 보는 결과가 바뀌고, 포크 멈춤을 사유 줄로 알리는 규칙(`MAX_ITERATIONS` · 예산 멈춤과 같은 처리)과 어긋난다 |
| 마지막 단계의 초과 | 성공으로 남긴다 | 실행 뒤 다시 판정해 성공을 실패로 바꾼다 — 이미 끝나고 값을 치른 일을 재라벨한다. 턴의 마지막 iteration 과도 어긋난다 |
| 재시도 검사의 매칭 | `Execution stopped: QUOTA_EXCEEDED` 부분 문자열 + guard 재확인 | 낱말 `QUOTA_EXCEEDED` — 자기 자원의 쿼터 오류를 내는 도구가 재시도를 잃고 엉뚱한 쿼터로 보고된다. 접두사 매칭 — 포크 스킬의 감싼 오류를 놓친다 |
| 재시도 검사의 시점 | 백오프 전 | 백오프 뒤 — 분 단위일 수 있는 `retryDelay` 를 다 기다린 뒤에야 같은 이유로 멈춘다 |
| 루틴의 멈춤 문구 | 턴과 같은 `Execution stopped by quota: …` | 루틴 전용 `Step stopped by quota: …` — 로그와 알림이 문자열 하나로 턴과 루틴의 쿼터 멈춤을 함께 잡지 못한다 |

---

## 12. 하지 말 것

- **포크에서 guard 를 새로 만들거나 scope 를 다시 해석하지 않는다.** 세션 scope 가 빠지는 우회로가 된다
- **`QUOTA_GUARD` 를 `disabled()` 로 바꿔 끼우지 않는다.** write-once 키이고, 바꿔 끼우면 그 아래 포크 전체가 계량 밖으로
  나간다
- **새 실행 경로를 만들면서 guard 를 빠뜨리지 않는다.** 백그라운드 실행처럼 호출한 실행에게서 아무것도 물려받지 않는
  경로도 guard 만은 실어야 한다. 빠뜨리면 그 경로가 우회로다
- **판정을 두 번째 자리에 두지 않는다.** 턴 시작 전 거부 경로도, 루틴의 단계 게이트도 두지 않는다
- **재시도 검사의 매칭을 낱말 `QUOTA_EXCEEDED` 로 넓히지 않는다.** 남의 쿼터 오류가 usage quota 멈춤으로 읽힌다
- **장부에 절대값을 쓰지 않는다.** 공유 저장소 구현은 키마다 delta 를 원자적으로 더한다
- **장부와 `QuotaGuardFactory` 를 런타임이나 스케줄링 엔진이 닫지 않는다.** application-scoped 이고 빌린 것이다
- **`QUOTA_EXCEEDED` 를 예산 멈춤 값이나 `ERROR` 로 바꿔 내지 않는다.** 사용자에게 줄 다음 행동이 다르다
- **resolver·정책 실패로 실행을 실패시키지 않는다.** guard 생성 실패는 WARN 후 계량 없이 진행한다
- **스케줄링·워크플로는 `agent.quota` 의 공개 타입(guard 와 팩토리)만 쓴다.** 장부나 `.impl` 에 기대지 않는다
- **쿼터 장부를 과금 정산의 정본으로 쓰지 않는다.** 초과량이 있는 soft limit 이다

---

## 13. 참조 파일 지도

경로는 `modules/` 기준이다.

| 절 | 파일 |
|----|------|
| §3 타입 | `aimon-core/src/main/java/at/aimon/core/agent/quota/` (`QuotaScope` · `QuotaWindow` · `QuotaKey` · `QuotaUsage` · `QuotaLimit` · `QuotaViolation` · `UsageLedger` · `QuotaScopeResolver` · `QuotaPolicy` · `QuotaResolutionContext` · `QuotaGuard` · `QuotaGuardFactory`) |
| §3 · §8 기본 구현 | `aimon-core/src/main/java/at/aimon/core/agent/quota/impl/` (`InMemoryUsageLedger` · `StaticQuotaPolicy` · `DefaultQuotaScopeResolver`) |
| §5.2 장부 장애 | `aimon-core/src/main/java/at/aimon/core/agent/quota/exception/QuotaLedgerUnavailableException.java` |
| §4.1 · §4.2 턴 | `aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java` (`Builder#quotaGuardFactory`, `createQuotaGuard`, `handleQuotaStop`) |
| §4.1 · §4.2 포크 | `aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java` |
| §4.1 · §4.2 `/skill` | `aimon-core/src/main/java/at/aimon/core/skill/execution/llm/LlmSkillExecutor.java` (`sendAndCharge`, `withCostEstimator`) |
| §4.1 · §7 배선 | `aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutorFactory.java` (`withQuotaGuardFactory`, `createDefaultCommandExecutionManager`) |
| §4.3 전파 | `aimon-core/src/main/java/at/aimon/core/tools/ToolContextKeys.java` (`QUOTA_GUARD`), `.../tools/task/TaskTool.java`, `.../tools/workflow/WorkflowTool.java`, `.../tools/workflow/BackgroundSubmissions.java`, `.../workflow/BackgroundSubmission.java`, `.../skill/fork/SubagentBackedSkillForkExecutor.java`, `.../subagent/SubagentLaunchContext.java`; `aimon-graaljs/src/main/java/at/aimon/graaljs/workflow/GraalJsWorkflowTool.java` |
| §6 멈춘 실행 | `aimon-core/src/main/java/at/aimon/core/agent/budget/CompletionReason.java`, `.../agent/AgentExecutionResult.java`, `.../agent/impl/orca/OrcaAgentExecutionResult.java`, `.../subagent/execution/SubagentExecutionResult.java`, `.../subagent/execution/SubagentResultFormatter.java`, `.../subagent/DefaultSubagentExecutionManager.java` (`isBoundedStop`); `aimon-session-routing/src/main/java/at/aimon/session/routing/internal/TurnResultPayload.java` |
| §7 설정 | `aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/QuotaSpec.java`, `aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java` (팩토리 공유, `quota-cost`), `aimon-bootstrap/src/main/java/at/aimon/bootstrap/RuntimeDegradations.java` |
| §9 예약 루틴 | `aimon-core/src/main/java/at/aimon/core/scheduling/RoutineExecutor.java` (`withQuotaGuardFactory`, `createQuotaGuard`, `buildToolContext`, `quotaStopsRetry`, `warnIfCompletedOverQuota`), `aimon-core/src/main/java/at/aimon/core/scheduling/SchedulingEngineBuilder.java` (`quotaGuardFactory`) |
