# Design — #267: scheduled routine forks run without a quota guard (UQ-2, routine half)

> Status: **IMPLEMENTED** — `aimon-core` (`RoutineExecutor#withQuotaGuardFactory`, the step-boundary quota gate,
> `SchedulingEngineBuilder#quotaGuardFactory`) and `aimon-bootstrap` (`AimonStackBuilder` hands the scheduling engine
> the executors' factory). Records: [`usage-quota.md`](usage-quota.md) §11.4 (dated note), and backlog UQ-2 (routine
> half closed, still open for the app-invoked `WorkflowRunner`) and UQ-4 in
> [`../../backlog/usage-quota-open-items.md`](../../backlog/usage-quota-open-items.md).
>
> **[§8](#8-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §8 is the body as approved in design review round 1
> (PASS, no blocking findings, eight non-blocking notes), kept byte-exact rather than corrected — the house habit in
> this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its citations are at `ea521169`.
> The review transcript and the run's `TASK.md` are not in the repository; §8 reproduces what was found while
> building and which review notes were taken.
>
> It is in English, like its siblings in this directory written from an approved run; `docs/design/` is not a
> translation target (`docs/project/documentation-guide.md` §5.1).


Grounded against `herdr/routine-fork-quota-guard` at `ea521169`. Line numbers are as of 2026-10-18.

## 1. Problem

Every fork (`Task` subagent, `Workflow` step, fork-mode skill) is metered by inheriting the spawning execution's
`QuotaGuard` from `ToolContextKeys.QUOTA_GUARD`. It never resolves a guard of its own (usage-quota design §4.3). Only
`OrcaAgentExecutor` puts that key in a tool context (`OrcaAgentExecutor.java:880`, `:2191`), so only a turn has one to
pass down. `RoutineExecutor` calls tools directly without a turn. Its `buildToolContext`
(`RoutineExecutor.java:541-554`) sets `AGENT_RUNTIME_ID`, `PRINCIPAL` (= `task.getOwner()`), `EXECUTION_ID`, the
cancellation signal and the environment, but never `QUOTA_GUARD`. So a routine step that forks runs under
`QuotaGuard.disabled()`: the fork checks nothing and charges nothing, even though the run has an owner. An owner who
has used up their principal quota in sessions can run the same LLM work through a cron task, unmetered. This is the
bypass §1 of the quota design exists to close. The fix is to give `RoutineExecutor` the application-scoped
`QuotaGuardFactory`, resolve one guard per run (no session, principal = owner, §4.4), and publish it on the step
context so forks inherit it. Without a configured factory, behaviour must stay exactly as it is today.

## 2. Approach

### 2.1 What changes

1. **Injection, shaped like the tracer.** `RoutineExecutor` gets a
   `volatile QuotaGuardFactory quotaGuardFactory = QuotaGuardFactory.disabled()` and a fluent
   `withQuotaGuardFactory(QuotaGuardFactory)`. `SchedulingEngineBuilder` gets `quotaGuardFactory(QuotaGuardFactory)`
   and forwards it in `build()` the way it forwards `tracer` (`SchedulingEngineBuilder.java:362-371`).
   `AimonStackBuilder.createSchedulingEngine` hands the builder the same factory the executor factory gets
   (`AimonStackBuilder.java:400-401`). The factory is borrowed and application-scoped. Neither the executor nor the
   engine owns or closes it, and the same goes for the ledger it holds (scope-model, CLAUDE.md "Scheduling").
2. **One guard per routine run.** In `runRoutine`, after `resolveExecutionAttributes` (the guard needs the attributes)
   and before `buildToolContext`, a private `createQuotaGuard(task, attributes)` mirrors
   `OrcaAgentExecutor#createQuotaGuard` (`OrcaAgentExecutor.java:2837-2850`):
   - read the volatile field once, so one run uses one factory;
   - if the factory is disabled, return `QuotaGuard.disabled()` without resolving anything;
   - otherwise call `factory.create(QuotaResolutionContext.builder().agentRuntimeId(task.getBoundRuntimeId())
     .sessionId(null).principal(task.getOwner()).attributes(attributes.values).build())`;
   - if the resolver or policy throws a `RuntimeException`, log a WARN ("Quota guard setup failed for task '{}'; this
     run is not metered") and return `QuotaGuard.disabled()`. This is fail-safe, as it is for a turn (§11.2): a broken
     application resolver does not stop the run.
3. **Publish on the step context.** `buildToolContext` takes the guard and runs
   `builder.put(ToolContextKeys.QUOTA_GUARD, guard)` **only when the factory is enabled**. With no factory the
   context has the same keys as today. That is the "not one bit different" default from §1.1, and it is what the
   unconfigured-factory test asserts. When a factory is enabled but resolution failed, the disabled guard is still
   put. The key is write-once, so the slot is filled honestly and nothing later can claim it.
   - What then works with no further changes: `TaskTool` (`TaskTool.java:585`), foreground and background
     `WorkflowTool` (`WorkflowTool.java:308`, `:536`), `GraalJsWorkflowTool` (`:206`, `:281`), and `SkillTool` → 
     `SubagentBackedSkillForkExecutor` (`:151`). The `SkillTool` FORK branch passes the step context through
     (`SkillTool.java:385-388`). All of them already read `QUOTA_GUARD` from the tool context. Forks inside forks
     re-publish it (`DefaultSubagentExecutor.java:874`).
4. **A quota gate at the step boundary.** In `executeStep`, right after the existing cancellation check and before
   each attempt, including retries, call `guard.check()`. If it returns a violation, start no attempt and make no
   further retries. The step is recorded as `StepResult.failure` with the error
   `"Execution stopped by quota: " + violation.describe()`. That is the same wording as a turn's stop
   (`OrcaAgentExecutor#handleQuotaStop`), and the attempt count is however many attempts already ran (0 if the first
   one was refused). The run then takes the existing failure path: `StepFailedEvent`, `RoutineResult.failure`,
   `TaskFailedEvent`, ROUTINE span closed as ERROR. On a disabled guard `check()` returns empty straight away, so
   unconfigured stacks behave exactly as before. Section 2.2 explains why the gate is needed and why it is placed
   here.

### 2.2 Decisions the task asked for

**One guard per run, not one per step.** Chosen: one per run.
- A turn has one guard, and every fork under it shares it (§4.3, `QuotaGuard` javadoc "One per turn, shared by its
  forks"). The routine run is the execution whose identity (`ExecutionId routine:<taskId>`) the forks carry, so it is
  the unit that matches.
- The step `ToolContext` is built once per run (`runRoutine` line 368) and `QUOTA_GUARD` is write-once. A per-step
  guard would mean rebuilding the context for each step.
- Limits and scopes are fixed when a guard is built, and the counters live in the ledger. A per-step guard would not
  enforce anything different. It would only call the resolver and policy N times, and it would let a policy change
  in the middle of a run apply partway through the run.
- Rejected, per step: more resolver and policy calls, a rebuilt context, and no gain in enforcement.

**What happens when the guard denies: the step fails and the run fails.** "Match existing semantics" has to be
reconciled with how each spawning tool reports a fork that stopped with `QUOTA_EXCEEDED`:
- `TaskTool` returns `ToolResult.success(...)` with a `Completion reason: QUOTA_EXCEEDED ...` line
  (`TaskTool.java:495`, `:665`). The parent LLM is meant to read it. To `RoutineExecutor` it is an ordinary
  **successful** step.
- `SkillTool`'s fork branch returns `ToolResult.error(...)` when the fork fails (`SkillTool.java:392`). Under the
  routine's retry policy that is a failing step that **gets retried**, and each retry stops again at iteration 0.

So wiring the guard alone does stop the spending (the fork stops at iteration 0 without calling the LLM), but the run
would report success for a quota-stopped `Task` step and spend its retries on a quota-stopped skill step. A turn
whose quota is reached stops, does no further iterations, and reports it. For a routine, the step boundary plays the
role of the turn's iteration boundary: it is where the run already checks for cancellation. Checking the shared guard
there gives the routine the turn's behaviour:
- an owner who is already over quota cannot start a forking step (acceptance);
- a fork that crosses the limit partway through a step is stopped by its own iteration check, and the next step or
  retry is refused at the gate;
- a refused step is not retried, because a retry would stop the same way;
- the run is filed as failed, with a message that names the scope and window. `RoutineResult` has only
  success/failure/cancelled, and a quota stop is not a cancellation (`CompletionReason.QUOTA_EXCEEDED` is grouped
  with the budget stops, not with `INTERRUPTED`), so it is filed as failure.

Rejected alternatives:
- *Wire the guard only, no gate.* The spending is blocked, but the run reports `success` for an over-quota `Task`
  step and burns retries on a skill step. That fails the acceptance's "cannot run" in spirit, because the step runs
  and is reported as having worked.
- *Change `TaskTool` / `WorkflowTool` to return `ToolResult.error` on `QUOTA_EXCEEDED`.* This changes what the parent
  LLM sees in every **turn** (§11.5 settled that wording deliberately). The blast radius goes well beyond #267.
- *Carry the fork's completion reason in `ToolResult` metadata and have the routine read it.* This touches every
  spawning tool, plus a metadata contract, to rebuild a verdict the shared guard already gives directly.
- *Check after each step instead of before.* The gate would run after the work has been done and paid for, so a step
  that "ran" would be relabelled as failed. A gate before the attempt is the honest record ("refused, 0 attempts"),
  the same way a turn's iteration-0 `QUOTA_EXCEEDED` is.

The tension with §4.2: §4.2 says not to add a separate "reject before the turn starts" path, so that two places
judging the same thing cannot drift apart. The gate does not add a second judgement. It calls the **same**
`QuotaGuard#check()` on the **same** instance the forks check. It also sits at the routine's only loop boundary,
exactly where §4.2 puts the turn's check (the iteration boundary). It is the routine's equivalent of that check, not
an extra pre-check in front of it.

**Order with cancellation.** The cancellation check comes first. A run that is both cancelled and over quota is
filed as `cancelled`. That keeps the existing rule ("a stopped run is not filed as a broken one",
`RoutineExecutor.java:403-405`) and the boundary check in `runRoutine` (line 375).

**No factory configured: unchanged.** The default field is `QuotaGuardFactory.disabled()`, no `QUOTA_GUARD` key is
put, the gate is a no-op on the disabled guard, and no resolver is called.

## 3. Concrete changes

### `aimon-core`

- `scheduling/RoutineExecutor.java`
  - field `private volatile QuotaGuardFactory quotaGuardFactory = QuotaGuardFactory.disabled();` with a comment
    worded like the tracer's.
  - `public RoutineExecutor withQuotaGuardFactory(QuotaGuardFactory)`: requires non-null, returns `this`, and has
    javadoc saying it is application-scoped and borrowed.
  - `runRoutine`: `final QuotaGuard quotaGuard = createQuotaGuard(task, attributes);` placed after the attributes
    are resolved. Pass it to `buildToolContext` and, through `StepRun` (which already bundles per-run
    coordinator + trace), to `executeStep`. Also pass whether the factory was enabled, or just put the key when
    `factory.isEnabled()`.
  - `createQuotaGuard(ScheduledTask, RunAttributes)` (private): see §2.1 item 2.
  - `buildToolContext`: the conditional `put(ToolContextKeys.QUOTA_GUARD, ...)`.
  - `executeStep`: the quota gate after the cancellation gate. A local `boolean quotaStopped` makes the existing
    `attemptCount == 0 → Optional.empty()` (meaning "never started, cancelled") apply only when the step was **not**
    quota-stopped. A step refused by quota at attempt 0 returns `Optional.of(StepResult.failure(..., 0, ...))`.
    Log a WARN: `"Usage quota reached for task '{}' at step {}: {}"`.
  - Class javadoc: a new `<h2>Usage quota</h2>` section after "Tracing", covering one guard per run, the resolution
    inputs, the step-boundary gate, and the unchanged default.
- `scheduling/SchedulingEngineBuilder.java`: a `private QuotaGuardFactory quotaGuardFactory;` field, a
  `quotaGuardFactory(QuotaGuardFactory)` setter, and in `build()`
  `if (quotaGuardFactory != null) routineExecutor.withQuotaGuardFactory(quotaGuardFactory);`. The javadoc must
  separate this **usage** quota (LLM tokens and cost per principal or tenant) from the builder's existing
  `quotaManager` / `defaultMaxQuota`, which is the per-runtime **task count** quota. The names are close enough to
  confuse a reader.
- Javadoc that currently says "per turn" and would become wrong:
  - `agent/quota/QuotaResolutionContext` ("Built once per turn", and `getSessionId` already names "a scheduled
    routine");
  - `QuotaGuardFactory` ("The executor calls create once per turn");
  - `QuotaScopeResolver` ("Called once per turn");
  - `agent/quota/package-info.java`.

  Each becomes "once per turn, or once per scheduled routine run". `QuotaGuard`'s "One per turn, shared by its forks"
  gets the same addition.

### `aimon-bootstrap`

- `AimonStackBuilder`: compute `quotaSpec.toGuardFactory()` once and use it for
  `executorFactory.withQuotaGuardFactory(...)` (line 401) and for `createSchedulingEngine(...)`. That means adding a
  `QuotaGuardFactory` parameter to that private static method, which is called at line 436 in the same `build` flow,
  after the executor section. Inside, call `builder.quotaGuardFactory(quotaGuardFactory)`, with a comment like the
  tracer's: "The executors' quota factory, so a routine's forks are charged to the task owner as a turn's are.
  Borrowed — never closed." Using one instance means the turn and the routine provably share one factory. (Calling
  `toGuardFactory()` twice would also be correct, because the factory is immutable and the ledger is shared, but
  one instance is easier to reason about.)
- No `QuotaSpec` / `SchedulingSpec` API change.

### `aimon-spring-boot-starter`, `aimon-cli`

- **No change.** The starter has no quota properties yet (design §9 stage 2), and both assemble through
  `AimonStackSpec` → `AimonStackBuilder`, so they pick up the bootstrap wiring automatically. A starter-built stack
  has no `QuotaSpec` ledger, so it gets the disabled factory, and routines behave as today. Nothing in the starter is
  touched, so no starter test is required by the task's "tested if you touch it".

### Docs and changelog (last commit, `Closes #267`)

- `docs/backlog/usage-quota-open-items.md` UQ-2:
  - keep it **열림** (open);
  - add a dated paragraph (2026-10-18) saying the routine half is closed by #267: `RoutineExecutor` resolves one
    guard per run (no session, principal = owner) and refuses a step at the step boundary when the guard reports a
    violation;
  - the remaining half is the app-invoked `WorkflowRunner` (`OrcaAgentRuntimeFactory` default launch context). Update
    its dated line citation;
  - rewrite **무엇을 / 어디 / 막힌 것 / 언제 다시 볼까** for that half only.

  The header count stays "열림 3 · 닫힘 0", and `README.md`'s register line does not change. Confirm with
  `python3 scripts/check-backlog-registers.py`. Touch nothing else in `docs/backlog/*` (parallel runs are editing it).
  The register has no `.en.md`.
- `docs/features/agent-execution/usage-quota-guide.md` and **`.en.md`**:
  - §5 currently lists routine steps as unmetered (lines 124-128). Move them to the metered side.
  - In §6, add a short paragraph on what a routine does at the limit: the step is refused and the run fails with
    "Execution stopped by quota: …".
  - Update the `.en.md` in the same commit and set `source_commit` per the translation rules.
- `docs/design/agent-execution/usage-quota.md` §11.4: the last paragraph says the `RoutineExecutor` steps are "not
  metered in phase 1 — UQ-2". See open question Q4.
- `CHANGELOG.md` `[Unreleased]`: one entry. Scheduled routine steps that fork are now charged to the task owner's
  usage quota, and a step is refused once the owner's quota is reached. This only applies when a `QuotaSpec` ledger
  is configured. New API: `RoutineExecutor#withQuotaGuardFactory` and `SchedulingEngineBuilder#quotaGuardFactory`.
- Run `check-doc-links.py`, `check-translation-structure.py`, `check-translation-staleness.py`, and
  `./gradlew checkAll`.

## 4. Data and interface shapes

New public API (additive only):

```java
// RoutineExecutor
public RoutineExecutor withQuotaGuardFactory(QuotaGuardFactory quotaGuardFactory); // NPE on null; returns this

// SchedulingEngineBuilder
public SchedulingEngineBuilder quotaGuardFactory(QuotaGuardFactory quotaGuardFactory); // null = leave disabled
```

Resolution context for a routine run (no new type):

| field | value |
|-------|-------|
| `agentRuntimeId` | `task.getBoundRuntimeId()` |
| `sessionId` | `null`, so no `SESSION` scope (§4.4) |
| `principal` | `task.getOwner()` |
| `attributes` | the run's resolved execution attributes (`RoutineExecutionAttributes`, #252). This is what a turn's `executionAttributes` are, so a resolver can read a tenant from them |

Tool context: adds `QUOTA_GUARD` only when the factory is enabled. Results and events: no new fields. A quota stop is
`RoutineResult.failure` / `StepResult.failure` with the message `Execution stopped by quota: <describe>` and an
attempt count that may be 0. No persisted shape changes (history repositories, Mongo codecs).

Snippet of the gate (illustrative):

```java
if (signal.isCancelled()) {
    break;
}
final Optional<QuotaViolation> violation = checkQuota(run.quotaGuard, task, stepIndex); // see 5: ledger failure
if (violation.isPresent()) {
    lastError = "Execution stopped by quota: " + violation.get().describe();
    quotaStopped = true;
    break;
}
attemptCount++;
```

## 5. Failure modes

| Case | Handling |
|------|----------|
| No factory / `disabled()` | No resolver call, no key, gate is a no-op. Identical to today. |
| Resolver or policy throws while the guard is built | WARN, `QuotaGuard.disabled()` for the run, the run proceeds unmetered (as a turn does, §11.2). The key is still put (write-once, disabled). |
| Owner already over quota at step 0 | Gate refuses step 0 with 0 attempts. The tool and the LLM are never called. The run fails. |
| Fork crosses the limit partway through a step | The fork stops itself with `QUOTA_EXCEEDED` (its own iteration check). What the step then reports depends on the tool (Task: success text; Skill fork: error). The next step, or the retry, is refused at the gate. On the **last** step the run can end `success`. That is the soft-limit overshoot from §5, and the same as a turn whose last iteration crossed the limit. See Q2. |
| Fail-open ledger fails during `check()` | Guard logs a WARN and returns empty, so the step runs. Unchanged guard behaviour. |
| Fail-closed ledger fails during `check()` at the gate | `QuotaLedgerUnavailableException` is caught at the gate. The step fails without retries, with the message `"Usage ledger unavailable: " + e.getMessage()`. This is the routine's equivalent of a turn ending `ERROR` (§5.1, §11.2). It is not a quota stop, and is not worded as one. |
| Fail-closed ledger fails inside a fork | Handled by the fork (ends `ERROR`). The tool reports it, and the routine treats it like any other step outcome. |
| Cancel and quota at once | Cancellation is checked first, so the run is filed `cancelled`. |
| Interrupt during the gate | `check()` is a quick ledger read with no blocking that would need termination. The next boundary handles it. |
| Multi-node | The guard is stateless and the counters live in the `UsageLedger` SPI, so a shared ledger gives cross-node metering. Nothing node-local is added. |
| Lifecycle | The factory and ledger are application-scoped and borrowed. `RoutineExecutor#shutdown` does not touch them. The guard is a per-run value and needs no close. |
| Background forks outliving the run | They keep charging the run's guard after the run ends. This is intended (§4.3, "usage is usage"). |

## 6. Test strategy

Core, new `scheduling/RoutineExecutorQuotaTest`. It follows the setup style of `RoutineExecutorTracingTest` and
`RoutineExecutorExecutionAttributesTest`: a registered fake runtime with a capturing fake tool, `InMemoryUsageLedger`
+ `StaticQuotaPolicy` with a `PRINCIPAL` limit, and an injected `Clock`.
1. **Over-quota owner cannot run a step** (acceptance). Pre-charge the owner's principal key over the limit. Result:
   `RoutineResult` failure, step 0 `StepResult.failure` with `attemptCount == 0`, error starting
   `Execution stopped by quota:`, the fake tool never invoked, `StepFailedEvent` + `TaskFailedEvent` published.
2. **Unconfigured factory keeps today's behaviour** (acceptance). No `withQuotaGuardFactory`, and separately an
   explicit `disabled()`. The tool runs, the captured context has no `QUOTA_GUARD`, and the result is success.
3. **The guard reaches the step context and is one per run.** Under quota, the tool sees an enabled guard; the same
   instance across steps and across retries of one step; a different instance on a second `execute`.
4. **Resolution inputs.** A capturing resolver sees runtime id = bound id, empty session, principal = owner, and
   attributes = those from `withExecutionAttributes`.
5. **Mid-run crossing and retries.** A fake tool that charges the guard over the limit and returns an error, with
   `maxRetries = 2`. Attempt 2 is refused, the final `attemptCount == 1`, and the error is the quota message, not
   the tool's.
6. **Next step refused after crossing.** Step 0 charges over and succeeds. Step 1 is refused with 0 attempts.
7. **Resolver throws.** WARN, the tool runs, `QUOTA_GUARD` is present and `!isEnabled()`.
8. **Fail-closed ledger at the gate.** The step fails with no retries and with the ledger message.
9. **Cancellation wins.** Cancelled and over quota gives a `cancelled` result.
10. **Non-forking step is gated too.** This documents the Q1 decision explicitly so that reversing it is a visible
    test change.

Core, end-to-end with real forks: add one case to `agent/impl/orca/it/UsageQuotaIntegrationTest`, or a sibling
`RoutineUsageQuotaIntegrationTest` built on its infrastructure (real `TaskTool`, `DefaultSubagentExecutor`, scripted
LLM). A routine with two `Task` steps for an owner under a principal limit. The first fork's usage lands on
`principal:<type>/<id>` (the fork inherited the routine's guard). The second step is refused, and the scripted LLM
gets no call for it. This is the test that would have caught #267.

Core, `SchedulingEngineBuilderTest` (or the closest existing builder test): `quotaGuardFactory(f)` reaches the built
engine's routine executor, observed through a fake tool run via `engine.getTaskManager()`, and leaving it unset keeps
the context free of the key.

Bootstrap, `AimonStackQuotaTest`: a stack with a `QuotaSpec` ledger and policy and scheduling enabled. Run a routine
task for an over-quota owner through `SchedulingEngine#getTaskManager()` and expect it refused. The same stack
without a ledger runs it. If running a task through the stack proves awkward, assert at least that the engine's
routine executor got the same `QuotaGuardFactory` instance as the executor factory.

Also: `./gradlew checkAll`, including ArchUnit. `scheduling → agent.quota` is a new edge inside the existing
`agent <-> scheduling` pair in `PackageDependencyArchitectureTest` (line 99). Verify that no rule rejects it.

## 7. Open questions

- **Q1: Should the gate refuse non-LLM steps too?** The design gates every step, because `RoutineExecutor` cannot
  tell which tools fork. A turn of an over-quota principal also does no further work, and one rule is easier to
  explain. The cost is that a quota-configured deployment's pure `Bash` / `Read` routines owned by an over-quota
  principal start failing, even though they spend no tokens. Quotas meter LLM usage ("실행 트리 어디서 LLM 을
  부르든", §1.1). The alternative is to gate only steps whose tool can start an LLM execution. That needs a marker on
  the tool, which does not exist today; a hard-coded name list (`Task`, `Workflow`, `WorkflowJs`, `Skill`) would be
  fragile. I chose "gate all" and pinned it with test 10. The reviewer or user should confirm.
- **Q2: Overshoot on the last step.** A run whose final forking step crosses the limit ends `success` (the tool
  reported success, and there is no next boundary). A final post-run `check()` could turn that into a failure, but it
  would relabel work that already completed. I left it as soft-limit overshoot (§5), consistent with a turn. Confirm
  this is acceptable.
- **Q3: Structured quota stop on `RoutineResult`.** Turns expose `CompletionReason.QUOTA_EXCEEDED` +
  `getQuotaViolation()`. A routine exposes only a failure and a message string, and the history repositories persist
  that. Adding a status or violation field touches persisted shapes and codecs, which is out of scope for #267. Should
  it become a new backlog item? Not added here, because other runs are editing `docs/backlog/*` in parallel.
- **Q4: Design doc §11.** `usage-quota.md` §11.4 (last paragraph) and §11.7 describe phase 1 as it was when it was
  implemented. The backlog README makes the register the canonical record of open/closed status. Options are to leave
  §11 alone (the register says the routine half is closed), or to add one dated sentence to §11.4 pointing at #267.
  Leaning toward adding the sentence. The design doc has no `.en.md`, so there is no translation cost.
- **Q5: Wording of the stop message.** I reused `"Execution stopped by quota: …"` from the turn path so logs and
  alerts can match one string. `"Step stopped by quota: …"` would be more precise for a routine. This is a cosmetic
  choice, but a user-visible one.

## 8. After the build — departures and what went to the backlog

*Appended 2026-10-18, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 1's non-blocking notes, and what was
found while building.*

**No decision in §2.2 changed.** Each routine run resolves one guard (no session, principal = owner, the run's
attributes), publishes it under `QUOTA_GUARD` only when a factory is wired, and checks it at the step boundary after
the cancellation gate. A refused step is not retried and fails the run with `Execution stopped by quota: …`. Without
a factory nothing is resolved, nothing is published and the gate never refuses.

### 8.1 Where the build departed from the body

- **DV-1 — the gate also runs before a retry's backoff.** §2.1 item 4 and §4 put the check only at the top of the
  retry loop, after `awaitRetryDelay`. A step whose fork crossed the limit and then returned an error (a skill fork,
  say) would have waited out its whole `retryDelay`, which can be minutes, only to be refused. So after a failed
  attempt that still has retries left, the guard is now asked **before** the backoff as well. The top-of-loop check
  stays, for the first attempt and for a limit crossed during the backoff by a background fork. The pre-backoff check
  is skipped once the run is cancelled, so the order in §2.2 ("Order with cancellation") holds. This changes
  behaviour: the refusal is immediate. A test pins it with a five-minute retry delay.
- **DV-2 — the refusal keeps the last attempt's error.** §4's snippet overwrote `lastError` with the quota message,
  so the tool's own error from the last real attempt was lost from `StepResult` / `RoutineResult`. When an attempt has
  already failed, the step's error is now `Execution stopped by quota: <describe> (last attempt: <tool error>)`. A
  step refused before any attempt carries the bare message, as designed. The fail-closed message in §5
  (`Usage ledger unavailable: …`) is joined the same way. This changes the message text only.
- **DV-3 — a `RunQuota` holder.** §3 left open whether to pass "factory enabled" alongside the guard. A private
  `RunQuota(guard, published)` is built once per run from a single read of the volatile factory. A resolver failure
  yields `(disabled, published)`, so the write-once slot is still filled (§2.1 item 3). This changes the shape only.
- **DV-4 — `checkQuota` returns the step's message, not the violation.** It also turns a fail-closed
  `QuotaLedgerUnavailableException` into §5's ledger message, so one helper serves both gate sites. This changes the
  shape only.
- **DV-5 — the end-to-end test is a sibling class.** §6 offered a case in `UsageQuotaIntegrationTest` or a sibling.
  `RoutineUsageQuotaIntegrationTest` drives two real `Task` steps. The routine runs without a tracer, so the forks
  carry no trace id and their calls land on the scripted client's unrouted queue. The test asserts the first fork's
  15 tokens on `principal:user/alice`, a zero-attempt refusal of step 1 with no LLM call for it, and no metering
  without a factory.
- **DV-6 — the bootstrap test fires a routine through the stack.** It uses §6's preferred form, not the
  instance-identity fallback. `AimonStackQuotaTest` runs a one-step routine for an over-quota owner through
  `SchedulingEngine#getTaskManager()` and expects it refused, and runs the same routine on a stack without a ledger.

### 8.2 Review notes: taken, and left

| Note | Outcome |
|------|---------|
| `ToolContextKeys.QUOTA_GUARD` javadoc still says a routine's context never carries the key | Taken. It now names a routine run with a factory wired as a publisher, and absence as "no quota factory wired" |
| `LlmSkillExecutor` (inline LLM skill) also reads the key, so the change is wider than "steps that fork" | Taken in the docs: the CHANGELOG and the guide §5 say an inline LLM skill called from a step is metered too. No dedicated test. Calling a skill from a routine goes through skill approval, which denies by default without a session or an agent-wide grant. The path reads the same key the end-to-end `Task` test covers |
| Check before the retry backoff, not only after it | Taken. DV-1 |
| Do not drop the last attempt's tool error | Taken. DV-2 |
| Q1 "gate all": spell out the deployment effect | Taken. The CHANGELOG and guide §6 (a WARNING) say that pure `Bash` / `Read` routines of an over-quota owner fail too, including `Principal.system()`-owned tasks if the policy limits every principal |
| Q4: add the dated sentence to `usage-quota.md` §11.4 | Taken. A dated note at the end of §11.4 points here; the §11.4 paragraph itself is not edited |
| Test 3: assert identity on the guard the tool receives, not on the context | Taken. The test compares `get(QUOTA_GUARD)` across steps, retries and runs |
| Test 8: assert the fail-closed error is not worded as a quota stop, and the run is a failure, not cancelled | Taken |

### 8.3 Open questions: decided, and what went to the backlog

- **Q1 (gate every step).** Decided as written, with the reviewer's agreement. `aStepThatStartsNoLlmWorkIsRefusedToo`
  pins it, so reversing it shows up as a test change. Nothing was filed. Narrowing the gate needs a marker for tools
  that can start an LLM execution, and no such marker exists. Reconsider if one is ever added for another reason.
- **Q2 (overshoot on the last step).** Decided as written: soft-limit overshoot, as for a turn.
  `aForkThatCrossesTheLimitOnTheLastStepLeavesTheRunSuccessful` pins it.
- **Q3 (structured quota stop on `RoutineResult`).** Filed as **UQ-4** in
  [`../../backlog/usage-quota-open-items.md`](../../backlog/usage-quota-open-items.md). The body said it would not be
  filed because other runs were editing `docs/backlog/*`. It went into the register this change already updates
  (UQ-2), so no other register was touched.
- **Q4.** Decided: a dated sentence was added to `usage-quota.md` §11.4.
- **Q5.** Decided: the turn's wording `Execution stopped by quota: …` is kept, so one string matches both in logs and
  alerts.

UQ-2 stays open for its other half: application code calling an agent's `WorkflowRunner` directly
(`OrcaAgentRuntimeFactory`'s default launch context carries no guard).
