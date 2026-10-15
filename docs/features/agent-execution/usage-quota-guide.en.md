---
translated_from: docs/features/agent-execution/usage-quota-guide.md
source_commit: 158e83c
---

# Usage Quota Guide — usage limits per session, user and tenant

> NOTE: What is in place today is phase 1 of the design — an in-memory ledger that counts within one node, and the
> `AimonStackSpec` wiring. CLI display and Spring Boot properties (phase 2) and a ledger shared across nodes (phase 3)
> do not exist yet.

This feature puts a **cumulative limit** on the LLM tokens and cost an agent uses, and stops execution when the limit
is reached. A limit can be set per session, per user (`Principal`) or per tenant, and counted over all time or per
day. The design rationale is in [`usage-quota.md`](../../design/agent-execution/usage-quota.md).

---

## 1. Budget and quota are different things

AIMON has two kinds of limit. The names are similar, but they count over different ranges.

| | `ExecutionBudget` (budget) | Usage Quota (quota) |
|---|---|---|
| What it counts | **one** execution (one turn, one fork) | the **total** across many executions |
| Reset | starts over with every execution | never (`TOTAL`) or every day (`DAILY`) |
| Unit | execution | session · user · tenant |
| Axes | iterations · tokens · cost · wall-clock | tokens · cost |
| When it stops | `MAX_ITERATIONS` · `TOKEN_BUDGET_EXCEEDED` etc. | `QUOTA_EXCEEDED` |
| Changing it at runtime | REPL `/budget` | changing the policy (`QuotaPolicy`) |

The "budget" you set on a session (`LiveSessionOptions.getBudget()`) is on the budget side too — it is a **per-turn
default applied afresh to every turn**, not a limit on the session as a whole. When you need a limit like "this
session may use 2 million tokens in total", use a quota.

The two limits work independently, and whichever is reached first stops the execution.

## 2. Turning it on

Giving `AimonStackSpec` a `QuotaSpec` turns it on. Without one the whole feature is off, and behaviour is the same as
if the feature did not exist.

```java
AimonStackSpec spec = AimonStackSpec.builder()
        // ...
        .quota(QuotaSpec.builder()
                .ledger(new InMemoryUsageLedger())
                .policy(StaticQuotaPolicy.builder()
                        // tokens one session may use over its lifetime
                        .limit(QuotaScope.Kind.SESSION, QuotaLimit.total().maxTokens(2_000_000).build())
                        // cost one user may spend per day
                        .limit(QuotaScope.Kind.PRINCIPAL, QuotaLimit.daily().maxCost(Money.usd(20)).build())
                        .build())
                .zone(ZoneId.of("Asia/Seoul"))   // where a day begins. UTC when omitted
                .build())
        .build();
```

There are three parts.

| Part | What it does | Provided |
|------|--------------|----------|
| `UsageLedger` | the ledger that stores cumulative usage | `InMemoryUsageLedger` |
| `QuotaPolicy` | which limits apply to each scope | `StaticQuotaPolicy` |
| `QuotaScopeResolver` | which scopes an execution belongs to | `DefaultQuotaScopeResolver` (session · user) |

Omitting `policy` gives a policy with no limits at all; omitting `resolver` gives `DefaultQuotaScopeResolver.create()`.
The ledger is treated as **borrowed** — the stack does not close it when it closes (`@ExternallyManaged`).

## 3. Scopes and windows

### 3.1 Scope

One execution belongs to several scopes at once. Its usage is recorded in all of them, and it stops when **any one**
reaches its limit.

| Scope | Where it comes from | When absent |
|-------|---------------------|-------------|
| `SESSION` | the execution request's `SessionId` | that scope does not apply — scheduled routines, rewake replays |
| `PRINCIPAL` | the `Principal` passed when submitting the turn (`submitOptions`) | that scope does not apply |
| `TENANT` | the function passed to the resolver (§4) | that scope does not apply |

IMPORTANT: To use per-user limits you must pass a `Principal` when you submit a turn. A turn submitted without one is
not counted against any user limit.

A user scope is identified by the `Principal`'s **type and id** (`user/alice`). A user and a service with the same id
are different scopes.

### 3.2 Window

| Window | Meaning |
|--------|---------|
| `TOTAL` | keeps accumulating, never resets |
| `DAILY` | starts over at every midnight in `QuotaSpec.zone(...)`. An execution running across midnight records its usage after midnight against the next day |

The same scope can carry both windows — for example "$20 a day, $500 in total" per user.

### 3.3 Limit axes

A `QuotaLimit` has at least one of tokens (`maxTokens`) and cost (`maxCost`, USD).

Tokens are counted as the call's total tokens (`TokenUsage#getTotalTokens()`, input + output).

IMPORTANT: A cost limit needs a `CostEstimator` to price each call. The bootstrap's default estimator is backed by a
price table, so unless you changed it, it already works. If you turn pricing off, as with
`ExecutorSpec.costEstimator(CostEstimator.NOOP)`, every call is recorded at zero and a cost limit never fires. When
that happens and the policy is a `StaticQuotaPolicy` with a cost limit, the stack records a `quota-cost` degradation.
A `QuotaPolicy` you implement yourself is not checked this way.

## 4. Tenant limits

The AIMON core has no notion of a tenant. The application decides what a tenant is and hands it to the resolver as a
function.

```java
// using the AgentRuntimeId discriminator as the tenant (agent:ops:acme → "acme")
QuotaSpec.builder()
        .resolver(DefaultQuotaScopeResolver.withTenant(ctx -> ctx.getAgentRuntimeId().discriminator()))
        // ...
```

When the function returns empty, no tenant scope applies to that execution. To replace how scopes are decided
altogether, implement `QuotaScopeResolver` yourself.

## 5. Forks are counted too

Tokens used by a subagent (`Task`), a workflow or a skill fork are recorded in the scopes of the execution that
launched it. A fork runs without a session, but it inherits its parent's scopes, so a fork cannot be used to get
around a limit.

- The limit is checked inside the fork as well. If the parent session's limit runs out while a fork is running, the
  fork stops with `QUOTA_EXCEEDED`
- A fork running in the background, and a `Workflow` tool run with `mode=background`, record to the same scopes and
  keep recording after the parent turn has ended

Conversely, **when the execution that launches a fork is not a turn**, there are no scopes to inherit and the fork runs
without a quota. There are two such paths today ([UQ-2](../../backlog/usage-quota-open-items.md)).

- `Task` · `Workflow` · forked skills called by a step of a scheduled routine (`RoutineExecutor`)
- application code calling the agent's `WorkflowRunner` directly

## 6. When a limit is reached

The check runs **before every LLM call**. When a limit is reached, the execution makes no further LLM call and ends
with `CompletionReason.QUOTA_EXCEEDED`. It is classed as a normal, limit-driven end, not an error.

```java
SubmitOptions options = SubmitOptions.builder().principal(Principal.user("u-123", "alice")).build();
AgentExecutionResult result = liveSession.submit("...", options);
if (result.getCompletionReason() == CompletionReason.QUOTA_EXCEEDED) {
    // which scope stopped it — scope, window, usage, limit
    result.getQuotaViolation().ifPresent(v -> log.info("quota exceeded: {}", v));
}
```

Submitting a new turn when the limit is already exhausted gives the same result at iteration 0, without calling the
LLM at all.

When a fork stops on a quota, the `Task` tool tells the parent LLM so — retrying would hit the same limit.

NOTE: `getQuotaViolation()` is filled only when the turn ran **on the same node**. The result of a turn run on another
node through `SessionRouter`, and a result kept in the idempotency store, carry only the completion reason and a
one-line message ([UQ-3](../../backlog/usage-quota-open-items.md)).

Raising the turn budget with `/budget` does not lift a quota. To lift it, raise the limit in the policy, or, for
`DAILY`, wait for the next day.

## 7. How far it can overshoot

A quota is a **soft limit**. It looks at the running total before a call and adds the usage after the call finishes.
How many tokens an LLM response will take is only known once the response is done, so an exact deduction is
impossible. The limit can therefore be exceeded slightly, and the overshoot has an upper bound.

| Scope | Maximum overshoot |
|-------|-------------------|
| `SESSION` | one LLM call's worth × (1 + forks running concurrently) |
| `PRINCIPAL` · `TENANT` | one LLM call's worth × executions that principal runs concurrently |

That is enough to cut off a runaway and to keep per-period usage roughly in line. It is **not a basis for billing**,
though — the provider's invoice is the reference for that.

## 8. When the ledger fails

When a `UsageLedger` call fails, the default is **fail-open** — it logs a WARN and execution continues. This keeps a
ledger outage from stopping every agent.

To do the opposite, set `QuotaSpec.failClosed(true)`. A ledger failure then ends the execution with `ERROR`, whether it
happens at the check (before the call) or at the charge (after it) — not `QUOTA_EXCEEDED`, because the limit was not
exceeded, it could not be checked.

`failClosed` applies **to ledger failures only**. If the `QuotaScopeResolver` or `QuotaPolicy` the application supplied
throws when a turn starts, that turn logs a WARN and runs without a quota, whatever `failClosed` says. Failing there
would lose the turn's transcript.

## 9. Running on several nodes

`InMemoryUsageLedger` counts **within one node only**. With several nodes each counts separately, so user and tenant
limits are effectively multiplied by the number of nodes. A session limit also starts again from zero when the
session moves to another node.

To share limits across nodes you need a `UsageLedger` that records to a shared store. Redis and Postgres
implementations are not provided yet (phase 3). Until then you can implement `UsageLedger` yourself — there are two
contracts to keep.

- `charge` **adds a delta atomically** per key (it never overwrites with an absolute value), because several nodes
  write to the same user key at once. No transaction across keys is needed
- The ledger is **application-scoped**. Several `AgentRuntime`s share it, and it is not closed when a runtime closes

## 10. What is not counted yet

A quota counts only the LLM calls of the turn loop and the fork loop. Other calls still go out after the quota is
spent, and the ledger's usage is always lower than the provider's invoice
([UQ-1](../../backlog/usage-quota-open-items.md)).

- **compaction** summary calls
- LLM calls of a slash-command skill that does not fork (`/skill`)
- LLM calls a code-behavior subagent makes directly
- LLM calls outside any execution, such as memory derivation and wiki synthesis
- Embedding and knowledge search cost
- Tool execution count and time

## Related documents

- [`usage-quota.md`](../../design/agent-execution/usage-quota.md) — design rationale. Where the implementation departs
  from the design is in §11
- [`usage-quota-open-items.md`](../../backlog/usage-quota-open-items.md) — open items (UQ-1 to UQ-3)
- [`orca-executor.md`](../../design/agent-execution/orca-executor.md) — the ReAct loop and `ExecutionBudget`
- [`scope-model.en.md`](../../overview/scope-model.en.md) — component lifetime rules
