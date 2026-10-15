# Usage Quota — 세션·사용자·테넌트 단위의 누적 사용량 한도

> Status: **IMPLEMENTED (1단계)** (2026-10-15) — §9 의 1단계가 `aimon-core` 와 `aimon-bootstrap` 에 들어갔다.
> 2단계(CLI 표시, Spring Boot 프로퍼티)와 3단계(Redis · Postgres 장부)는 아직이다.
> **[§11](#11-구현-뒤--설계와-달라진-점-그리고-백로그로-간-것)은 구현 뒤에 덧붙인 절이고, 구현이 이 문서에서 벗어난
> 자리는 거기에 있다.** 이 머리말과 §11 사이의 본문은 승인된 그대로 두었다 — 고치지 않고 §11 에서 정정한다.
> 적용 대상: `aimon-core` (1·2단계), `aimon-bootstrap` · `aimon-spring-boot-starter` (배선),
> `aimon-session-{redis,postgres}` (3단계 백엔드)
> 관련 규칙: [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
> 관련 문서: [`orca-executor.md`](orca-executor.md) (ReAct 루프와 `ExecutionBudget`),
> [`session-model.md`](../session/session-model.md) (`SessionTotals` · `budgetOverride`),
> [`scope-model.md`](../../overview/scope-model.md) (수명 규칙)

---

## 1. 무엇을 푸는가

`ExecutionBudget` 은 **실행 1회**의 한도다. 세션의 "예산"(`LiveSessionOptions.getBudget()`,
`SessionRecord.budgetOverride`)도 누적 한도가 아니라 **매 턴에 새로 적용되는 턴별 기본값**이다 — 10턴을
돌면 한도도 10번 리셋된다. `SessionTotals` 는 완료된 턴의 iteration·토큰을 누적하지만 주석이 밝히듯
"There is no budget here" 이고, 비용은 누적하지 않는다.

그래서 지금은 다음을 막을 수단이 없다.

- 한 세션이 여러 턴에 걸쳐 쓰는 총량
- 한 사용자(`Principal`)가 여러 세션에 걸쳐 쓰는 총량 — 세션 한도는 새 세션을 열면 우회된다
- 한 테넌트가 쓰는 총량
- 위 셋의 **기간별**(일 단위) 총량

그리고 서브에이전트·스킬 포크는 자기 `BudgetTracker` 를 새로 만들고
(`DefaultSubagentExecutor` 의 `new BudgetTracker(...)`), 세션 누적은 부모 턴의 트래커만 접는다
(`DefaultLiveSession` 의 `plusTurn`). 따라서 세션 누적에 한도를 거는 것만으로는 **포크가 우회로**가 된다.

### 1.1 원칙

- **soft limit** — 정확한 원자적 차감이 아니라 "호출 전에 보고, 호출 뒤에 더한다". 초과량에 상한이 있고
  그 상한을 문서화한다 (§5)
- **포크 포함** — 실행 트리 어디서 LLM 을 부르든 같은 장부에 기록된다 (§4.3)
- **하나의 장부, 여러 범위** — 세션·사용자·테넌트·기간은 키만 다르다. 범위마다 기계장치를 따로 두지 않는다
- **opt-in, 무변경 기본값** — 장부를 배선하지 않으면 동작은 지금과 한 비트도 다르지 않다
- **Multi-instance ready** — 장부는 SPI 이고 in-memory 가 기본 구현이다. 노드 간 공유는 구현체 교체로 얻는다
- **기존 예산과 직교** — `ExecutionBudget` / `BudgetTracker` 는 그대로 둔다. 쿼터는 그 옆에서 따로 판정한다

---

## 2. 용어

| 용어 | 뜻 |
|------|-----|
| **scope** | 사용량이 귀속되는 주체 한 개. `SESSION` · `PRINCIPAL` · `TENANT` 세 종류와 그 id |
| **window** | 누적을 리셋하는 기간. `TOTAL`(리셋 없음) · `DAILY` |
| **quota key** | scope + window + 기간 버킷. 장부의 키. 예: `principal:alice:daily:2026-10-15` |
| **usage** | 토큰 수(`long`)와 추정 비용(`Money`, USD)의 쌍 |
| **limit** | 한 quota key 에 대한 상한. 토큰과 비용 중 하나 이상 |
| **charge** | LLM 호출 1회의 usage 를 그 실행에 걸린 모든 quota key 에 더하는 것 |

기존 어휘와의 구분: **budget** 은 계속 `ExecutionBudget` (실행 1회) 의 뜻이다. 누적 한도는 **quota** 라고
부른다. 같은 단어를 쓰면 `/budget` 으로 턴 예산을 올려도 쿼터는 그대로라는 사실이 이름에서 숨는다.

---

## 3. 타입

모두 `at.aimon.core.agent.quota` 에 둔다(기본 구현은 `.impl`). 값 객체는 빌더를 가진 불변 클래스다.

```java
// scope 한 개 — 종류와 id
public final class QuotaScope {
    enum Kind { SESSION, PRINCIPAL, TENANT }
    Kind getKind(); String getId();
    static QuotaScope session(SessionId id); static QuotaScope principal(Principal p); static QuotaScope tenant(String id);
}

public enum QuotaWindow { TOTAL, DAILY }

// 장부의 키 — scope + window + 버킷("" for TOTAL, "2026-10-15" for DAILY)
public final class QuotaKey { QuotaScope getScope(); QuotaWindow getWindow(); String getBucket(); String asString(); }

public final class QuotaUsage { long getTokens(); Money getCost(); QuotaUsage plus(QuotaUsage o); static QuotaUsage zero(); }

// 한 (scope 종류, window) 에 거는 상한. 둘 다 비면 한도 없음
public final class QuotaLimit { QuotaWindow getWindow(); Optional<Long> getMaxTokens(); Optional<Money> getMaxCost(); }

// 장부 SPI — 저장소 교체 지점
public interface UsageLedger {
    /** keys 각각에 usage 를 더한다. 원자성은 키 단위로만 요구한다(키 간 트랜잭션 불필요). */
    void charge(Collection<QuotaKey> keys, QuotaUsage usage);
    /** 현재 누적치. 없는 키는 zero. */
    Map<QuotaKey, QuotaUsage> get(Collection<QuotaKey> keys);
}

// 실행 한 번에 걸리는 scope 를 뽑는다 — 애플리케이션이 주입
public interface QuotaScopeResolver {
    List<QuotaScope> resolve(QuotaResolutionContext ctx);   // sessionId?, principal?, agentRuntimeId, metadata
}

// scope 에 어떤 limit 들을 거는가 — 정적 설정이 기본, 동적 구현 가능
public interface QuotaPolicy {
    List<QuotaLimit> limitsFor(QuotaScope scope);
}
```

그리고 이들을 묶어 루프가 부르는 한 개의 객체:

```java
// 실행 1회에 하나. 부모에서 만들어져 포크에 그대로 전달된다
public final class QuotaGuard {
    static QuotaGuard disabled();
    /** 걸린 key 중 하나라도 limit 에 닿았으면 그 위반을 돌려준다. */
    Optional<QuotaViolation> check();
    /** LLM 호출 1회분을 모든 key 에 기록한다. */
    void charge(String modelName, TokenUsage usage, Money cost);
}

public final class QuotaViolation { QuotaKey getKey(); QuotaLimit getLimit(); QuotaUsage getUsed(); }
```

`QuotaGuard` 는 생성 시점에 scope 목록과 limit 목록을 **고정**한다. 기간 버킷만은 `check` / `charge` 때마다
주입된 `Clock` 과 `ZoneId` 로 다시 계산한다 — 자정을 넘는 긴 실행이 다음 날 버킷에 기록되도록.

### 3.1 왜 `SessionTotals` 를 쓰지 않는가

세션 scope 도 같은 장부에 넣는다. `SessionTotals` 는 통계로 그대로 남는다. 이유는 셋이다.

- `SessionTotals` 는 **턴이 끝날 때** 접힌다. 턴 도중의 사용량을 보지 못한다
- 포크 사용량이 들어가지 않는다 (§1)
- 쓰기가 **절대값**이다(`setTotalsAndBudgetOverride`). 세션 키에서는 리스가 단일 작성자를 보장해 괜찮지만,
  사용자·테넌트 키는 여러 노드가 동시에 쓰므로 **delta increment** 가 필수다. 두 의미를 한 저장소에 섞지 않는다

---

## 4. 실행 경로

### 4.1 기록 지점은 둘이다

LLM 응답의 토큰을 트래커에 기록하는 곳은 현재 두 군데뿐이다.

| 경로 | 위치 |
|------|------|
| 턴 (메인 ReAct 루프) | `OrcaAgentExecutor` — `scope.budgetTracker.recordTokens(...)` 직후 |
| 포크 (Task · Workflow · 스킬 포크) | `DefaultSubagentExecutor` — `lc.budgetTracker.recordTokens(...)` 직후 |

두 자리 모두에서 이미 `CostEstimator` 로 호출 1회의 비용을 계산한다. 같은 값을 `quotaGuard.charge(...)` 에 넘긴다.

### 4.2 판정 지점

두 루프 모두 iteration 경계에서 `budgetTracker.check()` 를 부른다. **그 바로 뒤에** `quotaGuard.check()` 를
부른다. 위반이면 `CompletionReason.QUOTA_EXCEEDED` 로 끝낸다 (§6).

별도의 "턴 시작 전 거부" 경로는 두지 않는다. 이미 소진된 쿼터는 첫 iteration 의 판정에서 LLM 호출 없이
멈추고, 결과는 iteration 0 의 `QUOTA_EXCEEDED` 다. 같은 판정이 한 자리에만 있어야 두 경로가 어긋나지 않는다.

### 4.3 포크로의 전파

포크는 셋 다 `SubagentLaunchContext` 를 거친다 (`TaskTool`, `WorkflowTool`, `SubagentBackedSkillForkExecutor`,
그리고 기본값을 만드는 `OrcaAgentRuntimeFactory`). 부모 실행의 `QuotaGuard` 를 이 컨텍스트에 실어
`DefaultSubagentExecutor` 가 **새로 만들지 않고 그대로 쓴다.**

scope 를 포크에서 다시 resolve 하지 않는 이유: 포크에는 세션이 없다(`SessionId` 가 아니라 `ExecutionId`).
다시 resolve 하면 세션 scope 가 빠지고 그것이 바로 §1 의 우회로다. 전달되는 것은 resolver 가 아니라
**이미 결정된 key 목록**이다.

중첩 포크와 병렬 포크도 같다 — 모두 같은 guard 를 공유한다. guard 자체는 상태가 없고(key 목록과 limit 은
불변, 누적치는 장부에) 장부 구현이 스레드 안전하면 충분하다.

백그라운드 포크는 부모 턴이 끝난 뒤에도 기록을 계속한다. 그것이 의도다 — 사용량은 사용량이다.

### 4.4 scope 해석

`QuotaScopeResolver` 의 입력은 실행 요청에서 나온다.

| scope | 출처 | 없으면 |
|-------|------|--------|
| `SESSION` | `OrcaAgentExecutionRequest.getSessionId()` (nullable) | 걸지 않는다 (스케줄 루틴, rewake 리플레이) |
| `PRINCIPAL` | `getPrincipal()` (턴마다 `submitOptions` 에서 옴) | 걸지 않는다 |
| `TENANT` | **코어에 개념이 없다.** resolver 가 정한다 | 걸지 않는다 |

테넌트를 코어가 정하지 않는 이유: 지금 저장소에서 테넌트는 bootstrap 의 관례(`AgentRuntimeId` 의
discriminator, `agent:ops:acme`)일 뿐이다. 그 관례를 쓰는 앱도, Principal 속성을 쓰는 앱도 있을 것이므로
코어는 기본 resolver 하나만 준다 — `SESSION` 과 `PRINCIPAL` 만 뽑고, 테넌트는 생성자에 넘긴 함수
(`Function<QuotaResolutionContext, Optional<String>>`)가 있을 때만 뽑는다.

---

## 5. soft limit — 얼마나 넘칠 수 있나

판정은 호출 **전**, 기록은 호출 **뒤**다. 호출 1회의 토큰 수는 응답이 끝나야 알 수 있으므로 정확한
차감은 원리적으로 불가능하다. 초과량의 상한은 다음과 같다.

```
최대 초과 ≈ (그 key 에 동시에 진행 중인 LLM 호출 수) × (호출 1회분 usage)
```

| scope | 동시 호출 | 상한 |
|-------|----------|------|
| `SESSION` | 세션 리스(fencing token)가 실행자를 하나로 보장. 다만 병렬 포크가 있으면 그 수만큼 | 1회분 × (1 + 동시 포크 수) |
| `PRINCIPAL` · `TENANT` | 그 주체의 동시 세션 수만큼 | 1회분 × 동시 실행 수 |

이 정도면 "폭주를 끊는다" 와 "기간별 사용량을 대략 맞춘다" 라는 목적에는 충분하다. 과금 정산의 정본으로
쓰지 않는다 — 정산은 프로바이더 청구서가 정본이다.

### 5.1 장부 장애

`UsageLedger` 호출이 예외를 던지면 기본은 **fail-open** 이다 — WARN 로그를 남기고 실행을 계속한다.
쿼터가 soft 인 이상, 장부 장애가 모든 에이전트를 멈추는 것은 지키려던 것보다 큰 손실이다.
`QuotaGuard` 빌더의 `failClosed(true)` 로 뒤집을 수 있고, 그때는 `QUOTA_EXCEEDED` 가 아니라
`ERROR` 로 끝난다(쿼터를 넘은 것이 아니라 판정할 수 없었던 것이므로).

---

## 6. 멈춘 실행이 말하는 것

`CompletionReason` 에 `QUOTA_EXCEEDED` 를 더한다. `COST_BUDGET_EXCEEDED` / `TOKEN_BUDGET_EXCEEDED` 를
재사용하지 않는 이유: 그 둘은 "턴 예산을 올리면 풀린다" 를 뜻하고, 쿼터는 그렇지 않다. 사용자에게 줄
다음 행동이 다르면 값도 달라야 한다.

결과에는 어느 key 가 막았는지(`QuotaViolation`)를 싣는다 — `scope`, `window`, 사용량, 한도. CLI 는 그것을
한 줄로 보여 준다.

`CompletionReason` 에 상수를 더하면 그 enum 을 `switch` 로 읽는 곳이 모두 영향을 받는다. 구현 시
`CompletionReason` 의 모든 소비자를 찾아 `QUOTA_EXCEEDED` 를 `COST_BUDGET_EXCEEDED` 와 같은 무리
("한도로 멈춘 정상 종료, 오류 아님")로 분류한다. 포크 결과를 읽는 `Task` 도구가 그것을 부모 LLM 에게
알리는 문장도 함께 정한다.

---

## 7. 설정

### 7.1 bootstrap

`AimonStackSpec` 에 선택 항목 하나를 더한다.

```java
QuotaSpec.builder()
        .ledger(new InMemoryUsageLedger())                 // 생략 시 쿼터 비활성
        .policy(StaticQuotaPolicy.builder()
                .limit(Kind.SESSION, QuotaLimit.total().maxTokens(2_000_000).build())
                .limit(Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build())
                .limit(Kind.TENANT, QuotaLimit.daily().maxCost(Money.usd(500)).build())
                .build())
        .resolver(DefaultQuotaScopeResolver.withTenant(ctx -> ctx.getAgentRuntimeId().discriminator()))
        .zone(ZoneId.of("Asia/Seoul"))                     // DAILY 버킷 경계
        .failClosed(false)
        .build();
```

`UsageLedger` 는 **application-scoped** 다 (`docs/overview/scope-model.md`). 여러 `AgentRuntime` 이 공유하고,
runtime 소멸 시 닫지 않는다. `QuotaGuard` 는 실행 1회의 값이다.

### 7.2 Spring Boot starter

`aimon.quota.*` 프로퍼티가 위의 `StaticQuotaPolicy` 를 만든다. 장부·resolver 빈은 사용자가 정의하면
그것이 이긴다(`@ConditionalOnMissingBean`).

### 7.3 비용 축의 전제

비용 한도는 `CostEstimator` 가 배선되어야 의미가 있다. 없으면 모든 호출이 0원으로 기록되고 비용 한도는
절대 발동하지 않는다 — `ExecutionBudget.maxCostUsd` 와 같은 규칙이다. 비용 limit 이 설정되었는데
estimator 가 `NOOP` 이면 bootstrap 이 시작 시 WARN 을 한 번 남긴다.

---

## 8. 백엔드

| 구현 | 위치 | `charge` |
|------|------|----------|
| `InMemoryUsageLedger` | `aimon-core` `agent.quota.impl` | `ConcurrentHashMap.merge` |
| `RedisUsageLedger` | `aimon-session-redis` | `HINCRBY tokens` + `HINCRBYFLOAT cost`, `DAILY` 키는 `EXPIRE` |
| `PostgresUsageLedger` | `aimon-session-postgres` | `INSERT … ON CONFLICT DO UPDATE SET tokens = tokens + ?` |

비용은 장부 안에서 마이크로달러 `long` 으로 저장한다 — 부동소수점 누적 오차를 피하고 Redis 정수 연산을
쓰기 위해서다. `Money` 로의 변환은 경계에서 한 번.

`DAILY` 키의 보존 기간은 구현체 설정이다(기본 7일). `TOTAL` 키는 만료하지 않는다 — 세션 scope 의
`TOTAL` 키는 세션 삭제 때 지우는 것이 맞지만, 그것은 3단계 범위다.

---

## 9. 단계

| 단계 | 범위 |
|------|------|
| **1** | §3 타입 전부, `InMemoryUsageLedger`, `StaticQuotaPolicy`, `DefaultQuotaScopeResolver`(SESSION·PRINCIPAL·선택 TENANT, `TOTAL`·`DAILY`), §4 의 두 기록·판정 지점, §4.3 포크 전파, `CompletionReason.QUOTA_EXCEEDED` 와 그 소비자, `AimonStackSpec` 배선, 단위 테스트 |
| **2** | CLI 표시(`/status` 에 쿼터 사용률, 멈춤 메시지), Spring Boot 프로퍼티 |
| **3** | Redis · Postgres 장부와 Testcontainers 테스트(`@Tag("docker")`), 세션 삭제 시 세션 키 정리 |

1단계만으로 단일 노드에서 세 scope 와 일 단위 한도가 모두 동작한다. 2·3단계는 그것을 보이게 하고 노드
간에 공유하게 한다.

---

## 10. 범위 밖

- **compaction 요약 호출** 등 두 기록 지점(§4.1)을 거치지 않는 LLM 호출. 지금 `BudgetTracker` 에도
  잡히지 않는 호출들이며, 쿼터에 넣으려면 그것들을 먼저 한 기록 경로로 모아야 한다. 별도 항목으로 둔다
- **hard limit** (예약 후 차감). 응답 크기를 미리 알 수 없어 예약량을 정할 근거가 없다
- **임베딩 · 지식 검색 비용**. `CostEstimator` 가 LLM 채팅 호출만 가격을 매긴다
- **도구 실행 횟수·시간** 쿼터

---

## 11. 구현 뒤 — 설계와 달라진 점, 그리고 백로그로 간 것

1단계 구현(2026-10-15)이 본문과 다르게 간 자리다. 본문은 승인된 그대로 두었고, 현재의 사실은 이 절이 갖는다.
1단계 밖으로 결과가 나가는 열린 질문은 백로그
[`../../backlog/usage-quota-open-items.md`](../../backlog/usage-quota-open-items.md) 의 `UQ-1` ~ `UQ-3` 으로 올렸다.

### 11.1 타입 하나가 늘었다 — `QuotaGuardFactory`

§3 은 `QuotaGuard` 를 "실행 1회에 하나" 로 두었지만, 그것을 **누가 만드는지**는 적지 않았다. 실행기는 에이전트 범위
싱글턴이고 guard 는 턴마다 새로 resolve 되어야 하므로, 장부 · 정책 · resolver · `Clock` · `ZoneId` · `failClosed` 를
들고 턴마다 `create(QuotaResolutionContext)` 를 부르는 application-scoped 객체가 하나 필요했다. 그것이
`at.aimon.core.agent.quota.QuotaGuardFactory` 다. 실행기의 기본값은 `QuotaGuardFactory.disabled()` 이고, 그때 모든
턴은 `QuotaGuard.disabled()` 를 받는다 — §1.1 의 "무변경 기본값" 이 이 한 자리에서 지켜진다.

배선은 `OrcaAgentExecutor.Builder#quotaGuardFactory` · `OrcaAgentExecutorFactory#withQuotaGuardFactory` 이고,
`aimon-bootstrap` 의 `QuotaSpec#toGuardFactory()` 가 §7.1 의 스펙을 이것으로 바꾼다. `QuotaSpec` 에는 본문에 없던
`clock(Clock)` 이 있다(테스트가 일 경계를 넘기기 위해). `zone` 을 정하지 않으면 **UTC** 다 — 노드마다 다른 시스템
시간대가 같은 키를 서로 다른 날짜 버킷에 쓰지 않게 하려는 것이다.

### 11.2 fail-closed 는 예외로 전달된다

§3 의 `check()` 는 `Optional<QuotaViolation>` 이라 "판정할 수 없었다" 를 담을 칸이 없다. 그래서 fail-closed guard 는
장부 예외를 `QuotaLedgerUnavailableException`(`agent.quota.exception`)으로 감싸 던지고, 두 루프는 그것을 이미 있는
일반 오류 경로로 받아 `ERROR` 로 끝낸다(§5.1 이 정한 결과 그대로). 본문은 `charge` 실패를 따로 말하지 않았는데,
fail-closed 에서는 **호출이 끝난 뒤의 기록 실패도** 실행을 `ERROR` 로 끝낸다 — 기록되지 않은 사용량을 두고 다음
호출을 내는 것은 fail-closed 가 막으려는 바로 그 일이다. fail-open 에서는 둘 다 WARN 한 줄이고 실행은 계속된다.

`failClosed` 가 다루는 것은 **장부** 장애뿐이다. 턴의 guard 를 만들다 애플리케이션의 resolver 나 정책이 던지면, 그
턴은 WARN 한 줄과 함께 계량 없이 돈다 — guard 는 트랜스크립트를 저장하는 try/finally 보다 앞에서 만들어지므로, 거기서
던지면 턴이 결과 없이 사라진다. 바로 위의 트레이싱 설정이 같은 이유로 fail-safe 다.

### 11.3 포크 전파는 도구 컨텍스트를 거친다

§4.3 의 "부모의 `QuotaGuard` 를 `SubagentLaunchContext` 에 싣는다" 는 그대로지만, 스폰 도구는 부모 실행을 모르고
**도구 컨텍스트**만 안다. 그래서 길은 이렇다.

```
OrcaAgentExecutor ── ToolContextKeys.QUOTA_GUARD ──▶ TaskTool · WorkflowTool · WorkflowJs · 포크 스킬
                                                     └─▶ SubagentLaunchContext.quotaGuard
DefaultSubagentExecutionManager ──▶ SubagentExecutionRequest.quotaGuard ──▶ DefaultSubagentExecutor
DefaultSubagentExecutor ── QUOTA_GUARD 를 그대로 다시 싣는다 ──▶ 포크 안의 포크
```

`QUOTA_GUARD` 는 **write-once** 키다 — `ToolContextEnricher` 가 `disabled()` 로 바꿔 끼워 아래의 포크 전체를 계량
밖으로 빼는 것을 막는다. 슬래시 명령(`/my-skill`) 턴이 만드는 도구 컨텍스트에도 같은 guard 를 싣는다. 포크 스킬이
그 길로도 포크를 띄우기 때문이다.

### 11.4 백그라운드 워크플로는 launch context 를 거치지 않았다

§4.3 이 꼽은 네 자리 가운데 `WorkflowTool` 은 **포그라운드**에서만 호출별 launch context 를 만든다.
`mode=background` 는 에이전트 단위 runner(`OrcaAgentRuntimeFactory` 가 만든다)의 **기본 launch context** 로 돌고,
그 컨텍스트는 의도적으로 호출한 실행에게서 아무것도 물려받지 않는다(주체 · 트레이스 · 취소 신호). 본문대로만
구현하면 백그라운드 워크플로가 바로 §1 의 우회로가 된다 — 쿼터를 다 쓴 세션이 같은 일을 백그라운드로 띄우면 계량되지
않는다.

그래서 `WorkflowRunner` 에 `runInBackground(script, runId, QuotaGuard)` 기본 메서드를 더했다. 기본 구현은 guard 를
무시하고 기존 메서드로 위임하며(서브에이전트를 띄우지 않는 runner 에게만 맞는 답이라고 javadoc 에 적었다),
`DefaultWorkflowRunner` 가 이것을 재정의해 실행별 launch context 에 guard 를 싣는다. `WorkflowTool` 과
`GraalJsWorkflowTool` 의 백그라운드 경로가 자기 도구 컨텍스트의 guard 를 넘긴다. 백그라운드 실행이 호출한 실행에게서
물려받는 것은 **이 하나뿐**이다 — 나머지를 물려받지 않는 이유는 그대로이고, 쿼터만은 물려받지 않으면 우회로라서다.
`workflow` 패키지의 ArchUnit 허용 목록(`workflowMayDependOnlyOnSubagentSpiTypes`)에 `QuotaGuard` 한 타입이 더해졌다.

`OrcaAgentRuntimeFactory` 의 기본 launch context 자체는 비활성 guard 를 갖는다. 거기에는 물려줄 호출한 실행이 없다.
앱 코드가 그 runner 를 직접 부르는 경우, 그리고 같은 이유로 `RoutineExecutor` 의 단계가 띄우는 포크는 1단계에서
계량되지 않는다 — `UQ-2`.

### 11.5 결과와 `CompletionReason` 의 소비자

`QUOTA_EXCEEDED` 는 `TOKEN_BUDGET_EXCEEDED` 와 `COST_BUDGET_EXCEEDED` 사이에 들어갔다. 소비자를 전수로 읽은
결과, `switch` 로 이 enum 을 나누는 곳은 없었고 모든 독자가 "`COMPLETED` / `INTERRUPTED` / 그 밖" 으로 읽는다. 그래서
`QUOTA_EXCEEDED` 는 코드 변경 없이 `COST_BUDGET_EXCEEDED` 와 같은 무리에 든다 — CLI 는 오류 결과로 보여 주고,
`AgentStepResult#isComplete()` 는 거짓이고, 노드 간 코덱들은 `fromWireName` 으로 읽으므로 옛 노드는 `ERROR` 로 받는다.
바꾼 독자는 하나다 — §6 이 정하라고 한 `Task` 도구의 문장.

```
Completion reason: QUOTA_EXCEEDED (a usage quota this whole task is charged to is used up; retrying or launching another subagent will stop the same way — finish with what you have)
```

`SubagentResultFormatter#completionReasonLine` 에서 만들어지므로 포그라운드 `Task`, 백그라운드 `AgentOutput`, 완료
알림이 같은 말을 한다. CLI 의 `SubagentResultDisplayHook` 은 `Completion reason: (\w+)` 뒤의 공백 이후를 허용하므로
그대로 읽는다.

`QuotaViolation` 은 `AgentExecutionResult#getQuotaViolation()`(기본 메서드, 빈 값)과 `OrcaAgentExecutionResult`,
`SubagentExecutionResult` 에 실린다. 라우팅 와이어(`TurnResultPayload`)와 저장된 결과는 이름과 메시지만 싣고 위반은
싣지 않는다 — `UQ-3`.

### 11.6 작은 차이

- **주체 범위의 id 는 `<type>/<id>` 다.** `Principal` 의 동일성은 종류와 id 둘이므로(`Principal#equals`), 같은 id 의
  사용자와 서비스는 다른 주체다. §2 의 예시 키 `principal:alice:daily:2026-10-15` 는 실제로
  `principal:user/alice:daily:2026-10-15` 다.
- **충전되는 토큰은 `TokenUsage#getTotalTokens()` 다.** 비용이 없거나(`CostEstimator.NOOP`) USD 가 아니면 비용은 0 으로
  기록된다 — 비용 예산 축이 같은 규칙이다. 토큰도 비용도 0 인 호출은 장부에 쓰지 않는다.
- **한 범위에 같은 기간의 한도가 둘이면 키 하나를 공유하고, 한 번만 충전된다.** 판정은 한도마다 따로 한다.
- **§7.3 의 WARN 은 bootstrap 의 `quota-cost` degradation 이다.** "설정되었지만 효과가 없다" 를 bootstrap 이 알리는
  기존 방식(`RuntimeDegradations`)을 따랐다. 비용 한도를 볼 수 있는 것은 `StaticQuotaPolicy`(`hasCostLimit()`) 뿐이라,
  사용자 정의 `QuotaPolicy` 의 비용 한도는 알릴 수 없다. bootstrap 의 기본 estimator 는 가격표 기반이므로 이 경고가 뜨는
  것은 `ExecutorSpec.costEstimator(CostEstimator.NOOP)` 를 명시했을 때다.
- **`InMemoryUsageLedger` 는 §8 의 `DAILY` 보존 기간을 갖는다**(기본 7일, 충전 때 UTC 하루 한 번 정리). 비용은 §8 대로
  마이크로달러 `long` 이며 충전마다 반올림(HALF_UP)한다. `TOTAL` 키는 정리하지 않는다.
- **`QuotaResolutionContext` 의 "metadata" 는 실행 요청의 `executionAttributes` 다**(`getAttributes()`).
- **세션 범위는 요청의 `getSessionId()` 에서 온다**(§4.4 그대로) — 트랜스크립트가 세션 없는 요청에 발급하는 id 가 아니다.
  그래서 세션 id 없이 들어온 턴에는 세션 범위가 걸리지 않는다.

### 11.7 확인한 것과 확인하지 못한 것

확인한 것은 테스트가 갖는다. `UsageQuotaIntegrationTest`(실제 실행기 · `Task` 도구 · 서브에이전트 실행기)가 여러 턴에
걸친 세션 `TOTAL` 누적과 LLM 호출 없는 iteration 0 멈춤, 포크와 포크 안 포크의 사용량이 부모 세션 키에 기록되는 것,
포크 안의 판정, 두 세션에 걸친 주체 누적, 장부 장애의 fail-open / fail-closed 를 보인다. `QuotaGuardTest` 가 주입
`Clock` 으로 `Asia/Seoul` 자정의 `DAILY` 리셋을, `AimonStackQuotaTest` 가 `AimonStackSpec` 배선과 `quota-cost` 를,
`DefaultWorkflowRunnerBackgroundTest` · `WorkflowToolBackgroundModeTest` 가 §11.4 를 본다.

확인하지 못한 것: 실행기를 통해 **비용** 한도로 멈추는 것(비용 한도의 판정과 충전은 `QuotaGuardTest` 가 보지만, 통합
테스트는 `CostEstimator` 없이 토큰 한도만 돌렸다), 그리고 병렬 포크가 같은 키를 동시에 넘길 때의 초과량이 §5 의 상한 안인지(장부의 동시 충전이 잃지
않는다는 것만 단위 테스트가 본다).
