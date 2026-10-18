# Usage Quota 가이드 — 세션·사용자·테넌트별 사용량 한도

> NOTE: 지금 들어가 있는 것은 설계의 1단계다 — 노드 하나 안에서 세는 in-memory 장부와 `AimonStackSpec` 배선.
> CLI 표시와 Spring Boot 프로퍼티(2단계), 노드 간 공유 장부(3단계)는 아직 없다.

에이전트가 쓰는 LLM 토큰과 비용에 **누적 한도**를 걸어, 한도에 닿으면 실행을 멈추게 하는 기능이다. 한도는
세션 하나, 사용자(`Principal`) 하나, 테넌트 하나 단위로 걸 수 있고, 전체 누적이나 하루 단위로 셀 수 있다.
설계 근거는 [`usage-quota.md`](../../design/agent-execution/usage-quota.md) 에 있다.

---

## 1. budget 과 quota 는 다르다

AIMON 에는 한도가 두 종류 있다. 이름이 비슷하지만 세는 범위가 다르다.

| | `ExecutionBudget` (budget) | Usage Quota (quota) |
|---|---|---|
| 세는 범위 | 실행 **1회** (턴 하나, 포크 하나) | 여러 실행에 걸친 **누적** |
| 리셋 | 실행마다 새로 시작 | 리셋 없음(`TOTAL`) 또는 하루마다(`DAILY`) |
| 단위 | 실행 | 세션 · 사용자 · 테넌트 |
| 축 | iteration · 토큰 · 비용 · 경과 시간 | 토큰 · 비용 |
| 멈출 때 | `MAX_ITERATIONS` · `TOKEN_BUDGET_EXCEEDED` 등 | `QUOTA_EXCEEDED` |
| 런타임 변경 | REPL `/budget` | 동적 `QuotaPolicy` 직접 구현 (`StaticQuotaPolicy` 는 재시작) |

세션에 설정하는 "예산"(`LiveSessionOptions.getBudget()`)도 budget 쪽이다 — **매 턴에 새로 적용되는 턴별
기본값**이지 세션 전체의 한도가 아니다. "이 세션은 총 200만 토큰까지" 같은 한도가 필요하면 quota 를 쓴다.

두 한도는 독립적으로 동작하고, 먼저 닿는 쪽이 실행을 멈춘다.

## 2. 켜기

`AimonStackSpec` 에 `QuotaSpec` 을 주면 켜진다. 주지 않으면 기능 전체가 꺼져 있고, 동작은 이 기능이 없을 때와
같다.

```java
AimonStackSpec spec = AimonStackSpec.builder()
        // ...
        .quota(QuotaSpec.builder()
                .ledger(new InMemoryUsageLedger())
                .policy(StaticQuotaPolicy.builder()
                        // 한 세션이 평생 쓸 수 있는 토큰
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(2_000_000).build())
                        // 한 사용자가 하루에 쓸 수 있는 비용
                        .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build())
                        .build())
                .zone(ZoneId.of("Asia/Seoul"))   // 하루의 경계. 생략하면 UTC
                .build())
        .build();
```

구성 요소는 셋이다.

| 구성 요소 | 하는 일 | 기본 제공 |
|-----------|---------|-----------|
| `UsageLedger` | 누적 사용량을 저장하는 장부 | `InMemoryUsageLedger` |
| `QuotaPolicy` | 범위마다 어떤 한도를 거는지 | `StaticQuotaPolicy` |
| `QuotaScopeResolver` | 실행 하나가 어느 범위에 속하는지 | `DefaultQuotaScopeResolver` (세션 · 사용자) |

`policy` 를 생략하면 한도가 하나도 없는 정책이, `resolver` 를 생략하면 `DefaultQuotaScopeResolver.create()` 가
쓰인다. 장부는 **빌려 온 것**으로 취급된다 — 스택이 닫힐 때 장부를 닫지 않는다(`@ExternallyManaged`).

## 3. 범위와 기간

### 3.1 범위 (scope)

실행 하나는 여러 범위에 동시에 속한다. 사용량은 그 모든 범위에 기록되고, **하나라도** 한도에 닿으면 멈춘다.

| 범위 | 어디서 오나 | 없으면 |
|------|------------|--------|
| `SESSION` | 실행 요청의 `SessionId` | 그 범위는 걸리지 않는다 — 스케줄 루틴, rewake 리플레이 |
| `PRINCIPAL` | 턴을 넣을 때 준 `Principal` (`submitOptions`) | 그 범위는 걸리지 않는다 |
| `TENANT` | resolver 에 넘긴 함수 (§4) | 그 범위는 걸리지 않는다 |

IMPORTANT: 사용자 한도를 쓰려면 턴을 넣을 때 `Principal` 을 넘겨야 한다. 넘기지 않은 턴은 사용자 한도에
잡히지 않는다.

사용자 범위는 `Principal` 의 **종류와 id** 로 식별한다(`user/alice`). id 가 같아도 사용자와 서비스는 서로 다른
범위다.

### 3.2 기간 (window)

| 기간 | 의미 |
|------|------|
| `TOTAL` | 리셋 없이 계속 누적한다 |
| `DAILY` | `QuotaSpec.zone(...)` 기준 자정마다 새로 센다. 자정을 넘겨 도는 실행은 넘긴 뒤의 사용량을 다음 날에 기록한다 |

같은 범위에 두 기간을 함께 걸 수 있다 — 예를 들어 사용자당 "하루 $20, 전체 $500".

### 3.3 한도 축

`QuotaLimit` 은 토큰(`maxTokens`)과 비용(`maxCost`, USD) 중 하나 이상을 갖는다.

토큰은 호출 1회의 전체 토큰(`TokenUsage#getTotalTokens()`, 입력 + 출력)으로 센다.

IMPORTANT: 비용 한도는 호출의 비용을 매기는 `CostEstimator` 가 있어야 동작한다. bootstrap 의 기본 estimator 는
가격표 기반이라 따로 설정하지 않았다면 이미 동작한다. `ExecutorSpec.costEstimator(CostEstimator.NOOP)` 처럼
가격 매기기를 끄면 모든 호출이 0원으로 기록되어 비용 한도가 절대 발동하지 않는다. 이때 정책이
`StaticQuotaPolicy` 이고 비용 한도를 갖고 있으면 스택이 `quota-cost` degradation 을 기록한다. 직접 구현한
`QuotaPolicy` 는 이 검사를 받지 않는다.

## 4. 테넌트 한도

AIMON 코어에는 테넌트라는 개념이 없다. 무엇을 테넌트로 볼지는 애플리케이션이 정해서 resolver 에 함수로 준다.

```java
// AgentRuntimeId 의 discriminator 를 테넌트로 쓰는 경우 (agent:ops:acme → "acme")
QuotaSpec.builder()
        .resolver(DefaultQuotaScopeResolver.withTenant(ctx -> ctx.getAgentRuntimeId().discriminator()))
        // ...
```

함수가 빈 값을 돌려주면 그 실행에는 테넌트 범위가 걸리지 않는다. 범위 결정 방식을 통째로 바꾸고 싶으면
`QuotaScopeResolver` 를 직접 구현한다.

## 5. 포크도 같이 센다

서브에이전트(`Task`), 워크플로, 스킬 포크가 쓴 토큰도 그것을 띄운 실행의 범위에 기록된다. 포크는 세션 없이
돌지만 부모의 범위를 물려받으므로, 포크로 한도를 우회할 수 없다.

- 포크 안에서도 한도 판정이 걸린다. 부모 세션의 한도가 포크 도중에 소진되면 포크가 `QUOTA_EXCEEDED` 로 멈춘다
- 백그라운드로 돌린 포크와 `Workflow` 도구의 `mode=background` 실행도 같은 범위에 기록하며, 부모 턴이 끝난 뒤에도
  계속 기록한다

예약 루틴(`RoutineExecutor`)도 같다. 루틴 실행 1회는 턴처럼 guard 를 하나 만든다 — 세션 없이, 태스크 소유자
(`task.getOwner()`)를 주체로. 그래서 루틴 단계가 부르는 `Task` · `Workflow` · 포크 스킬, 그리고 포크하지 않는 LLM
스킬도 소유자의 `PRINCIPAL`(과 해석기가 고른 `TENANT`) 범위에 기록된다. 세션 한도를 다 쓴 사용자가 같은 일을
cron 으로 걸어 우회할 수는 없다. 다만 루틴에는 세션이 없으므로 `SESSION` 한도는 걸리지 않는다.

반대로 **포크를 띄운 실행이 턴도 루틴도 아니면** 물려받을 범위가 없어서 쿼터 없이 돈다. 지금 그런 경로는
애플리케이션 코드가 에이전트의 `WorkflowRunner` 를 직접 부르는 경우 하나다
([UQ-2](../../backlog/usage-quota-open-items.md)).

## 6. 한도에 닿았을 때

판정은 **LLM 호출 직전**마다 이루어진다. 한도에 닿으면 실행은 다음 LLM 호출을 하지 않고
`CompletionReason.QUOTA_EXCEEDED` 로 끝난다. 오류가 아니라 한도에 의한 정상 종료로 분류된다.

```java
SubmitOptions options = SubmitOptions.builder().principal(Principal.user("u-123", "alice")).build();
AgentExecutionResult result = liveSession.submit("...", options);
if (result.getCompletionReason() == CompletionReason.QUOTA_EXCEEDED) {
    // 어느 범위가 막았는지 — scope, window, 사용량, 한도
    result.getQuotaViolation().ifPresent(v -> log.info("quota exceeded: {}", v));
}
```

이미 한도가 소진된 상태에서 새 턴을 넣으면, LLM 을 한 번도 부르지 않고 iteration 0 으로 같은 결과가 나온다.

포크가 쿼터로 멈추면 `Task` 도구는 그 사실을 부모 LLM 에게 알린다 — 다시 시도해도 같은 한도에 막힌다는 뜻이다.

**예약 루틴은 단계를 거절하지 않는다.** 쿼터가 막는 것은 LLM 호출이고, 루틴 단계가 닿을 수 있는 LLM 호출 — 포크의
iteration, 인라인 LLM 스킬의 호출 — 은 모두 물려받은 guard 를 호출 직전에 판정한다. 그래서 한도를 다 쓴 소유자의
루틴에서도 `Bash` · `Read` 처럼 LLM 을 부르지 않는 단계는 그대로 돌고, `Task` · `Workflow` · 포크 스킬 단계의 포크는
첫 LLM 호출 전에 멈춘다. 멈춤이 드러나는 모양은 도구마다 다르다. 포크 스킬은 `QUOTA_EXCEEDED` 를 담은 오류로 단계를
실패시키고, `Task` 는 다른 포크 멈춤과 똑같이 성공 결과 안의 `Completion reason: QUOTA_EXCEEDED` 줄로 알린다 — 그러니
루틴 실행 자체는 성공으로 기록될 수 있다([UQ-4](../../backlog/usage-quota-open-items.md)). 단계 도중에 포크가 한도를
넘기면 그 포크와 뒤의 포크들이 각자 다음 호출 전에 멈춘다. 마지막 단계에서 넘긴 것은 §7 의 초과분이다.

실행기가 더하는 것은 재시도 판정 하나다. 쿼터 때문에 실패한 시도(오류에 `QUOTA_EXCEEDED` 가 있고 guard 가 여전히
위반을 보고할 때)는 다시 해도 같은 한도에 막히므로, 백오프를 기다리지 않고 `"Execution stopped by quota: ...
(last attempt: ...)"` 메시지로 단계를 실패시킨다. 다른 이유로 실패한 시도는 소유자가 한도를 넘었어도 설정대로
재시도한다. 쿼터 장부를 붙이지 않은 스택에서는 아무것도 달라지지 않는다.

NOTE: `getQuotaViolation()` 은 턴이 **같은 노드에서** 실행됐을 때만 채워진다. `SessionRouter` 를 거쳐 다른 노드에서
실행된 턴의 결과와 멱등성 저장소에 남은 결과에는 완료 사유와 한 줄 메시지만 남는다
([UQ-3](../../backlog/usage-quota-open-items.md)).

`/budget` 으로 턴 예산을 올려도 quota 는 풀리지 않는다. 풀려면 정책의 한도를 올리거나, `DAILY` 라면 다음 날을
기다린다. 정책은 턴이 시작될 때 한 번 읽힌다. `StaticQuotaPolicy` 는 불변이라 한도를 바꾸려면 스택을 다시 띄워야
하고, 실행 중에 바꾸려면 매번 최신 한도를 돌려주는 `QuotaPolicy` 를 직접 구현한다.

## 7. 얼마나 넘칠 수 있나

quota 는 **soft limit** 이다. 호출 전에 누적치를 보고, 호출이 끝난 뒤에 사용량을 더한다. LLM 응답이 몇 토큰일지는
응답이 끝나야 알 수 있으므로 정확한 차감은 불가능하다. 그래서 한도를 조금 넘을 수 있고, 넘는 양에는 상한이 있다.

| 범위 | 최대 초과량 |
|------|------------|
| `SESSION` | LLM 호출 1회분 × (1 + 동시에 도는 포크 수) |
| `PRINCIPAL` · `TENANT` | LLM 호출 1회분 × 그 주체가 동시에 돌리는 실행 수 |

폭주를 끊고 기간별 사용량을 대략 맞추는 데는 충분하다. 다만 **과금 정산의 근거로 쓰지는 않는다** — 정산은
프로바이더 청구서가 기준이다.

## 8. 장부에 장애가 나면

`UsageLedger` 호출이 실패하면 기본은 **fail-open** 이다 — WARN 을 남기고 실행을 계속한다. 장부 장애로 모든
에이전트가 멈추는 것을 막기 위해서다.

반대로 하려면 `QuotaSpec.failClosed(true)` 를 준다. 그때 장부 장애는 판정(호출 전)에서든 기록(호출 후)에서든
실행을 `ERROR` 로 끝낸다(`QUOTA_EXCEEDED` 가 아니다 — 한도를 넘은 것이 아니라 판정을 못 한 것이므로).

기록(호출 후) 단계의 장애로 끝날 때는 **이미 비용을 낸 LLM 응답이 버려진다** — 대화 기록에 들어가기 전에 실행이
끝나기 때문이다. fail-closed 를 고를 때 이 비용을 감안한다.

`failClosed` 는 **장부 장애에만** 적용된다. 애플리케이션이 준 `QuotaScopeResolver` 나 `QuotaPolicy` 가 턴 시작
시점에 예외를 던지면, 그 턴은 `failClosed` 와 상관없이 WARN 을 남기고 쿼터 없이 실행된다. 그 자리에서 실패시키면
턴의 대화 기록을 잃기 때문이다.

## 9. 여러 노드에서 쓰기

`InMemoryUsageLedger` 는 **노드 하나 안에서만** 센다. 노드가 여럿이면 각 노드가 따로 세므로, 사용자·테넌트
한도는 사실상 노드 수만큼 늘어난다. 세션 한도도 세션이 다른 노드로 옮겨 가면 0 부터 다시 센다.

`InMemoryUsageLedger` 는 지난 `DAILY` 키를 보존 기간(기본 7일)이 지나면 지우지만, **`TOTAL` 키는 지우지 않는다.**
세션 `TOTAL` 한도를 걸면 세션마다 키가 하나씩 남아, 오래 떠 있는 노드에서는 메모리가 계속 늘어난다. 세션 삭제 시
키 정리는 3단계 범위다.

여러 노드에서 한도를 공유하려면 공유 저장소에 기록하는 `UsageLedger` 가 필요하다. Redis · Postgres 구현은
아직 제공되지 않는다(3단계). 그때까지는 `UsageLedger` 를 직접 구현할 수 있다 — 지켜야 할 계약은 둘이다.

- `charge` 는 키마다 **delta 를 원자적으로 더한다**(절대값으로 덮어쓰지 않는다). 여러 노드가 같은 사용자 키에
  동시에 쓰기 때문이다. 키 사이의 트랜잭션은 필요 없다
- 장부는 **애플리케이션 수명**이다. 여러 `AgentRuntime` 이 공유하며, runtime 이 닫힐 때 닫지 않는다

## 10. 아직 세지 않는 것

쿼터는 턴 루프와 포크 루프의 LLM 호출만 센다. 그 밖의 호출은 쿼터를 다 쓴 뒤에도 나가고, 장부의 사용량은
프로바이더 청구서보다 늘 적다([UQ-1](../../backlog/usage-quota-open-items.md)).

- **대화 압축(compaction) 요약 호출**
- 포크하지 않는 슬래시 명령 스킬(`/skill`)의 LLM **비용** — 판정과 토큰 기록은 되지만 가격이 매겨지지 않는다
- 코드 동작(code behavior) 서브에이전트가 직접 부르는 LLM 호출
- 메모리 파생 · 위키 합성 같은 실행 밖의 LLM 호출
- 임베딩 · 지식 검색 비용
- 도구 실행 횟수와 시간

## 관련 문서

- [`usage-quota.md`](../../design/agent-execution/usage-quota.md) — 설계 근거. 구현이 설계와 달라진 점은 §11
- [`usage-quota-open-items.md`](../../backlog/usage-quota-open-items.md) — 열린 항목 (UQ-1 ~ UQ-4)
- [`orca-executor.md`](../../design/agent-execution/orca-executor.md) — ReAct 루프와 `ExecutionBudget`
- [`scope-model.md`](../../overview/scope-model.md) — 컴포넌트 수명 규칙
