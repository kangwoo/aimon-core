---
translated_from: docs/features/workflow/workflow-usage-guide.md
source_commit: 8830d022
---

# Workflow Usage Guide (the library view)

> How to use the Workflow subsystem directly from a Java application that embeds `aimon-core`

This document is written for **a developer assembling and running workflows in Java code**.
For the aimon-cli user's view (the `Workflow` / `WorkflowJs` tools, the `/runs` command), see the
[Workflow CLI guide](workflow-cli-guide.en.md).

## Table of contents

1. [Overview](#overview)
2. [When to use it and when not to](#when-to-use-it-and-when-not-to)
3. [A five-minute start](#a-five-minute-start)
4. [Assembling the runner](#assembling-the-runner)
5. [Writing a script — the WorkflowContext primitives](#writing-a-script--the-workflowcontext-primitives)
6. [AgentTask and AgentStepResult](#agenttask-and-agentstepresult)
7. [Structured output (resultSchema)](#structured-output-resultschema)
8. [WorkflowPatterns — the quality-pattern helpers](#workflowpatterns--the-quality-pattern-helpers)
9. [Concurrency settings](#concurrency-settings)
10. [The budget (WorkflowBudget)](#the-budget-workflowbudget)
11. [Background execution and resume](#background-execution-and-resume)
12. [The event sink](#the-event-sink)
13. [Worktree isolation](#worktree-isolation)
14. [Lifecycle and ownership rules](#lifecycle-and-ownership-rules)
15. [A checklist of traps](#a-checklist-of-traps)

---

## Overview

Workflow is a subagent orchestration layer in which **the control flow is decided by code, not by the LLM**.

| | Who decides | What it is for |
|---|---|---|
| The ReAct loop (`OrcaAgentExecutor`) | The LLM | An open problem where what to do is not known |
| The `Task` tool (one delegation to a subagent) | The LLM | Carving off one chunk and delegating it |
| **Workflow** | **Code (Java / a JS script)** | Work whose **structure is already fixed** — a fan-out, a pipeline, a verification loop |

There is one core idea. **The script writes the structure, and the LLM runs only inside each subagent.**
Which makes the fan-out width, the number of verification rounds and the early-exit condition deterministic and reproducible.

Where the packages live:

- `at.aimon.core.workflow` — the public SPI and value types (everything this document covers)
- `at.aimon.core.workflow.impl` — the implementations. **Do not import them directly from outside** (ArchUnit blocks it).
  The entry point is always the `WorkflowRunners` factory.

---

## When to use it and when not to

**Worth using when**

- The same work repeats over N targets (a review per file, a check per service, an evaluation per candidate)
- The processing is multi-stage with fixed stages, along the lines of "generate → verify → synthesize"
- The judgement only becomes trustworthy once several independent views are collected (a judge panel, a panel of refuters)
- You want a search of unknown yield to converge with "stop after K consecutive rounds that found nothing"

**Not to be used when**

- One subagent finishes the job → just use the `Task` tool, or call `SubagentExecutionManager` directly
- The number of stages, or the branching condition itself, is for the LLM to decide → the ReAct loop is the right thing
- You need to interact with the user inside the script → a workflow is non-interactive

---

## A five-minute start

```java
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunners;

// 1) the base environment the subagents run in (every step inherits it)
SubagentExecutionEnvironment baseEnv = SubagentExecutionEnvironment.builder()
        .contextId(contextId)                 // AgentRuntimeId
        .subagentRegistry(subagentRegistry)
        .toolRegistry(toolRegistry)
        .hookRegistry(hookRegistry)
        .environment(environment)
        .defaultModel(agent.getMetadata().getModel())
        .build();

// 2) assemble the runner (subagentExecutionManager is borrowed — the runner does not close it)
try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, baseEnv)) {

    // 3) run the script
    String answer = runner.run(ctx -> {
        ctx.phase("Analyze");
        List<AgentStepResult> results = ctx.parallel(List.of(
                () -> ctx.agent(technical, question),
                () -> ctx.agent(risk, question),
                () -> ctx.agent(cost, question)));

        ctx.phase("Synthesize");
        return ctx.agent(synthesizer, combine(results)).text();
    });
}
```

Subagents are defined as usual (for the details, see the
[subagent development guide](../subagent/subagent-development-guide.en.md)):

```java
Subagent technical = Subagent.builder()
        .name("review:technical")
        .systemPrompt("You analyze strictly from a technical-correctness angle. Be concise.")
        .build();
```

---

## Assembling the runner

### The factory

`WorkflowRunners` is the only entry point. There are four overloads.

```java
// minimal — default concurrency, no event sink, the default budget
WorkflowRunners.create(manager, baseEnv);

// concurrency / events / budget named
WorkflowRunners.create(manager, baseEnv, concurrency, eventSink, budget);

// the above + a step result cache (for resume)
WorkflowRunners.create(manager, baseEnv, concurrency, eventSink, budget, stepResultCache);

// every option (recommended)
WorkflowRunners.create(manager, baseEnv, options);
```

### WorkflowRunnerOptions

Every field is nullable; leave one unset and that component's default is used.

```java
WorkflowRunnerOptions options = WorkflowRunnerOptions.builder()
        .concurrency(WorkflowConcurrencyConfig.enabled(8, 2))
        .eventSink(mySink)
        .budget(WorkflowBudget.of(200, 2_000_000))
        .stepResultCache(WorkflowRunners.inMemoryStepResultCache())
        .runStore(myRunStore)                    // the background run control-plane store
        .backgroundConfig(WorkflowBackgroundConfig.of(4))
        .worktreeFactory(worktreeFactory)        // only when you use isolate=true steps
        .build();
```

| Option | Without it | Why you would set it |
|------|--------|--------------|
| `concurrency` | `WorkflowConcurrencyConfig.defaults()` | Tuning the fan-out width / how the shared pool is divided |
| `eventSink` | `WorkflowEventSink.NO_OP` | Streaming phase/log/step start and completion to a UI |
| `budget` | `WorkflowBudget.defaults()` (1000 agents) | Runaway protection, a token and cost ceiling |
| `stepResultCache` | `StepResultCache.NO_OP` (no resume) | Skipping past cache hits when re-running an interrupted run |
| `runStore` | in-memory | Sharing the run list and statuses across instances |
| `backgroundConfig` | `WorkflowBackgroundConfig.defaults()` | The number of concurrent background runs, the queue capacity |
| `worktreeFactory` | absent → an `isolate` step is a run-fatal failure | Isolating parallel steps that mutate files |

### How the core assembles it (for reference)

`OrcaAgentRuntimeFactory#buildWorkflowRunner` is the standard assembly example.
It creates one runner per context and attaches an in-memory step cache and a worktree factory.

```java
return WorkflowRunners.create(subagentExecutionManager, baseEnv,
        WorkflowRunnerOptions.builder()
                .stepResultCache(WorkflowRunners.inMemoryStepResultCache())
                .worktreeFactory(worktreeFactory)
                .build());
```

---

## Writing a script — the WorkflowContext primitives

`WorkflowScript<T>` is a functional interface.

```java
@FunctionalInterface
public interface WorkflowScript<T> {
    T run(WorkflowContext ctx);
}
```

`WorkflowContext` offers no more than five capabilities.

### `agent` — one subagent step (synchronous)

```java
AgentStepResult r = ctx.agent(subagent, "a goal string");   // the convenience overload
AgentStepResult r = ctx.agent(AgentTask.builder()...build());
```

### `parallel` — a barrier fan-out

```java
<R> List<R> parallel(List<Supplier<R>> thunks);
```

- It **waits** until every thunk has finished (the barrier).
- The results are reassembled in **input order**.
- When a thunk throws, `null` goes into its slot — the caller must be null-safe.
  ```java
  List<AgentStepResult> rs = ctx.parallel(thunks);
  rs.stream().filter(Objects::nonNull).forEach(...);
  ```
- One exception: **a run-fatal `WorkflowException` is not isolated and propagates as it is** (the budget running out,
  a missing worktree factory, a cancellation, and so on). These are the situations where killing the whole run is right.

### `pipeline` — barrier-free per-item parallelism

```java
<I, A, R> List<R> pipeline(List<I> items, Function<I, A> stage1, BiFunction<A, I, R> stage2);
```

Each item passes through **its own stage chain independently**. Item A can be running stage 2 while item B is
still in stage 1. The wall-clock time is "the slowest single item chain", not "the sum of the per-stage maxima".

```java
List<String> verdicts = ctx.pipeline(diffs,
        diff -> ctx.agent(reviewer, "find bugs in " + diff),
        (result, diff) -> ctx.agent(verifier, "refute: " + result.text()).text());
```

> **Careful:** the GraalJS front end's `pipeline` means something different. The JS side is desugaring with
> **a barrier at every stage**. Only the Java side is per-item parallel. See the corresponding section of the CLI guide.

Three stages or more cannot be expressed generically, so use the `Pipeline` builder — the type flows along the stages.

```java
List<String> out = Pipeline.over(diffs)
        .then((d, orig) -> ctx.agent(reviewer, "find bugs in " + d))
        .then((r, orig) -> r.text())
        .then((text, orig) -> ctx.agent(verifier, "refute: " + text).text())
        .run(ctx);
```

`Pipeline` is not a new primitive but pure static composition over `parallel`. `run()` turns each item's chain into
a single thunk and fans them out. So the null rule is the same as `parallel`'s.

### Do you really need a barrier

`parallel` (the barrier) is right **only when the next stage has to see the whole of the previous stage's results**.

When a barrier is right:
- You dedup/merge the full result set before running expensive downstream work
- You skip the next stage entirely when the total count is zero
- The next stage's prompt asks for "compared against the other findings"

When a barrier is not needed (→ use `pipeline`):
- Merely to flatten/map/filter
- Because the stages look conceptually separate
- Because the code looks cleaner

### `phase` / `log`

```java
ctx.phase("Verify");         // groups the steps after it under this name (passed to the event sink)
ctx.log("12/30 verified");   // a progress message
```

`phase` is global state. Calling `phase` inside a parallel stage races. For per-step grouping it is safer to attach it
**to the task itself** with `AgentTask.builder().phase("Verify")`.

---

## AgentTask and AgentStepResult

### AgentTask

An immutable builder. The minimum is `subagent` + `goal`.

```java
AgentTask task = AgentTask.builder()
        .subagent(reviewer)
        .goal("Find bugs in this diff:\n" + diff)
        .label("review:" + file)         // defaults to the subagent's name
        .phase("Review")                 // the event group
        .resultSchema(FINDINGS_SCHEMA)   // a JSON Schema — forces structured output
        .isolate(false)                  // true means worktree isolation + no caching
        .nonCacheable(false)             // true means it is not stored in the resume cache
        .build();

AgentTask simple = AgentTask.of(reviewer, goal);  // the short form
```

`isolate(true)` implies `nonCacheable` (an isolated run has side effects, so replaying it from a cache is meaningless).

### AgentStepResult

```java
result.isSuccess();          // whether the step succeeded
result.isComplete();         // completionReason == COMPLETED
result.completionReason();   // why it ended (a limit reached / an error / a cancellation …)
result.text();               // the final text
result.structured();         // Optional<Map<String,Object>> — where a resultSchema was given
result.getLabel();
result.getTask();
result.raw();                // the raw subagent response
```

`isSuccess()` and `isComplete()` are different. A step that stopped at an iteration limit can still have left text behind.
At a stage where quality matters, filter on `isComplete()`.

---

## Structured output (resultSchema)

Give a `resultSchema` and the subagent is forced to call the structured-output tool, and the result comes back as a
validated `Map`. There is no parsing code to write, and the model retries when the schema does not match.

```java
static final Map<String, Object> VERDICT_SCHEMA = Map.of(
        "type", "object",
        "properties", Map.of(
                "refuted", Map.of("type", "boolean"),
                "reason", Map.of("type", "string")),
        "required", List.of("refuted"));

AgentStepResult r = ctx.agent(AgentTask.builder()
        .subagent(skeptic)
        .goal("Refute the following claim: " + claim)
        .resultSchema(VERDICT_SCHEMA)
        .build());

boolean refuted = Boolean.TRUE.equals(
        r.structured().map(m -> m.get("refuted")).orElse(null));
```

Always attach a schema to **a step whose result the code has to read** — a verdict, a vote, a classification.

---

## WorkflowPatterns — the quality-pattern helpers

Six frequently used verification structures come as static helpers. All of them are built out of nothing but the
primitives above, so you can compose your own where you need to.

```java
// 1) a panel of refuters — n of them rebut independently; a quorum of rebuttals rejects it
Verdict v = WorkflowPatterns.adversarialVerify(ctx, finding, skeptic, 3, 2, REFUTE_SCHEMA);
v.isSurvived();       // did it survive
v.isInconclusive();   // not enough valid votes
v.getRefutations();   // the number of rebutting votes

// 2) a judge panel — a panel scores several candidates and the best is synthesized
JudgedResult jr = WorkflowPatterns.judgePanel(ctx, attempts, judge, 2, synthesizer, SCORE_SCHEMA);
jr.best(); jr.bestIndex(); jr.scores(); jr.synthesis();

// 3) loop until dry — for a search of unknown yield. Ends after quietK consecutive empty rounds
List<AgentStepResult> rounds = WorkflowPatterns.loopUntilDry(
        ctx, i -> AgentTask.of(finder, "Round " + i + ": find new bugs"),
        r -> r == null || r.text().isBlank(), 2, 10);

// 4) a completeness critic — a loop where a critic points out what is missing and a reviser fixes it
AgentStepResult finalDraft = WorkflowPatterns.completenessCritic(ctx, draft, critic, reviser, 3);

// 5) perspective-diverse verification — each verifier gets a different lens (stronger than N identical verifiers)
List<AgentStepResult> lenses = WorkflowPatterns.perspectiveDiverseVerify(
        ctx, claim, List.of(correctnessLens, securityLens, reproLens), VERDICT_SCHEMA);

// 6) a multi-modal sweep — a parallel sweep that searches each in a different way
List<AgentStepResult> hits = WorkflowPatterns.multiModalSweep(ctx, modes);
```

The returned lists can contain `null` (a failed fan-out slot). Always filter.

### A composed example — find → dedup → multi-lens verify → until dry

```java
Set<String> seen = new HashSet<>();
List<Finding> confirmed = new ArrayList<>();
int dry = 0;

while (dry < 2) {
    ctx.phase("Find");
    List<AgentStepResult> found = ctx.parallel(finders.stream()
            .<Supplier<AgentStepResult>>map(f -> () -> ctx.agent(f, prompt)).toList());

    List<Finding> fresh = parse(found).stream()
            .filter(b -> seen.add(key(b)))   // dedup against 'everything seen', not the confirmed set
            .toList();                        // ← if rejected findings revive every round it never converges

    if (fresh.isEmpty()) { dry++; continue; }
    dry = 0;

    ctx.phase("Verify");
    for (Finding b : fresh) {
        Verdict v = WorkflowPatterns.adversarialVerify(ctx, b.desc(), skeptic, 3, 2, REFUTE_SCHEMA);
        if (v.isSurvived()) confirmed.add(b);
    }
}
```

---

## Concurrency settings

```java
WorkflowConcurrencyConfig.disabled();          // everything sequential
WorkflowConcurrencyConfig.defaults();          // enabled, max(1, min(16, cores-2))
WorkflowConcurrencyConfig.enabled(8);          // a global pool of 8
WorkflowConcurrencyConfig.enabled(8, 2);       // a global pool of 8, 2 per batch
WorkflowConcurrencyConfig.forSharedPool(4);    // the preset for 4 concurrent runs sharing the pool
```

| Parameter | Meaning | Default |
|---------|------|------|
| `maxConcurrency` | The size of the runner's global worker pool | `DEFAULT_MAX_CONCURRENCY = 4` |
| `perBatchMax` | The most one `parallel` batch may occupy at once | `maxConcurrency` |
| `maxNestingDepth` | The fan-out nesting depth permitted | `DEFAULT_MAX_NESTING_DEPTH = 1` |
| `maxLiveFanoutThreads` | The defensive ceiling on total live fan-out threads | `max(maxConcurrency, 256)` |

The invariants `build()` checks:

- `perBatchMax ∈ [1, maxConcurrency]`
- `perBatchMax ^ maxNestingDepth <= maxLiveFanoutThreads`

In a server environment where **several conversations or turns share one runner**, keep the global pool large and
`perBatchMax` small. That two-tier structure keeps one batch from monopolising the pool and starving the other runs.

---

## The budget (WorkflowBudget)

```java
WorkflowBudget.defaults();                 // 1000 agents (DEFAULT_MAX_AGENTS)
WorkflowBudget.ofAgents(50);
WorkflowBudget.of(50, 1_000_000);          // + a token ceiling
WorkflowBudget.of(50, 1_000_000, 5.0);     // + a cost ceiling (USD)
```

- The agent-count ceiling is **a runaway-loop backstop**. It is not a value a healthy workflow reaches.
- The token and cost ceilings are **opt-in** and **enforced post-hoc**. The accumulated figure is checked after a step
  ends, so it can overshoot by roughly `perBatchMax × tokens per step`. It is not a hard cutoff.
- On overshoot a `WorkflowBudgetExceededException` is thrown and **the whole run fails**. It is not isolated to a `null`
  the way an individual step failure is.

When you write a loop that scales its depth to the budget, write it to stop on its own before it crosses the ceiling.

---

## Background execution and resume

### RunId

```java
RunId.from("review");                  // run:review
RunId.from("review", "pr-1234");       // run:review:pr-1234
RunId.of("run:review:pr-1234");        // parsed from a string
```

The format is `run:<scriptName>[:<discriminator>]`. Each segment must be non-blank and may not contain a `:`.

**RunId's determinism is what holds up two features:**

1. **Resume** — running again with the same RunId hits the completed steps stored in the `StepResultCache` immediately.
2. **Idempotent submission** — when a background run with the same RunId is already up, it is not run twice; the call joins it.

`WorkflowRunner.DEFAULT_RUN_ID` (= `RunId.from("run")`) is **one-shot** and does not cache steps.
The no-argument `run(script)` overload uses that id. If you need resume, you must supply your own RunId.

### Foreground vs background

```java
// blocking
String result = runner.run(script, RunId.from("review", "pr-1234"));

// non-blocking
RunHandle<String> handle = runner.runInBackground(script, RunId.from("review", "pr-1234"));
handle.runId();
handle.isDone();
String r = handle.await(Duration.ofMinutes(10));
```

> `handle.future()` is **a defensive copy**. Cancelling that future does not stop the run.
> The actual stop is `runner.stop(runId)`. And the typed result is obtainable **only on the node that owns the run**.

### The control plane

`WorkflowRunController` (implemented by the runner):

```java
boolean stopped = runner.stop(runId);              // a cooperative cancel, limited to this node
List<WorkflowRun> runs = runner.list(RunQuery.all());
Optional<WorkflowRun> one = runner.status(runId);
```

The `RunQuery` combinations:

```java
RunQuery.all();
RunQuery.byState(WorkflowRunState.RUNNING);
RunQuery.byAgentRuntime(contextId);
RunQuery.builder().state(...).owner(...).contextId(...).build();
```

The `WorkflowRun` accessors: `getRunId()`, `getScriptName()`, `getState()`, `getStartTime()`,
`getEndTime()`, `getOwner()`, `getAgentRuntimeId()`, `getLastHeartbeat()`.

The states are `PENDING → RUNNING → COMPLETED | FAILED | KILLED`, and `isTerminal()` tells you whether it has ended.

### The resume cache

```java
StepResultCache cache = WorkflowRunners.inMemoryStepResultCache();
```

- The contract: `load` / `save` / `evict`. **It never throws** — a cache failure must not kill a run.
- Only `COMPLETED` steps are stored. Failed and cancelled steps are re-run.
- A `nonCacheable(true)` or `isolate(true)` task is not stored.
- The default is `StepResultCache.NO_OP` — without supplying one explicitly, resume does not work.

For resume across instances, swap `StepResultCache` and `RunStore` for shared-storage implementations
(the project's design principle: a stateful component separates its storage behind an interface).

### Configuring the background executor

```java
WorkflowBackgroundConfig.defaults();
WorkflowBackgroundConfig.of(4);            // 4 concurrent runs
WorkflowBackgroundConfig.of(4, 100);       // + a queue capacity of 100
WorkflowBackgroundConfig.builder()
        .maxConcurrentRuns(4)
        .queueCapacity(WorkflowBackgroundConfig.UNBOUNDED_QUEUE)
        .shutdownDrain(Duration.ofSeconds(10))   // 5 seconds by default
        .build();
```

---

## The event sink

```java
public interface WorkflowEventSink {
    void onPhase(String title);
    void onLog(String message);
    void onAgentStarted(AgentTask task);
    void onAgentCompleted(AgentTask task, AgentStepResult result);
}
```

The default is `WorkflowEventSink.NO_OP`.

> **Thread safety is mandatory**: `onAgentStarted`/`onAgentCompleted` are **called concurrently from worker threads**.
> A sink with internal mutable state must be thread-safe. Even a sink that writes straight to the console does better
> to serialize, so the lines do not interleave.

---

## Worktree isolation

Use it only when you have to run file-mutating steps in parallel.

```java
AgentTask.builder().subagent(migrator).goal(...).isolate(true).build();
```

- Each step runs in **its own git worktree**. That costs something (setup plus disk, per step).
- Where nothing changed, the worktree is cleaned up automatically.
- `isolate(true)` cannot be cached (side effects cannot be replayed).
- **An `isolate` step met by a runner with no `worktreeFactory` injected is a run-fatal failure (C30).**
  Be sure to put a `WorktreeEnvironmentFactory` in the options.

If the parallel steps touch nothing but distinct files, isolation is unnecessary. Turn it on only when they contend for the same file.

---

## Lifecycle and ownership rules

| Rule | Why |
|------|------|
| Keep the `WorkflowRunner` **application-scoped** and reuse it | Creating a worker pool per run leaks |
| The runner **borrows** `SubagentExecutionManager` and `baseEnv`. **It must never close them** | The caller owns them |
| If you created a runner per call, **you must close it** (try-with-resources) | Otherwise a fan-out pool is left behind per run |
| Cancelling `RunHandle.future()` does not stop the run → `stop(runId)` | The future is a defensive copy |
| A background run does **not** inherit the calling turn's context, principal or cancellation signal | It runs in the runner's own base environment |

Look at the core's own example (`WorkflowTool`) and you will see the foreground path creating a runner per call and closing it at once:

```java
try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, env)) {
    return ToolResult.success(runner.run(script(...)));
}
```

The background path, by contrast, reuses the injected context-scoped runner. Following that separation as it stands is enough.

---

## A checklist of traps

- [ ] Does the code handle the fact that a `parallel`/`pipeline` result can be `null`?
- [ ] Do you really need the barrier (`parallel`), or would `pipeline` do?
- [ ] Did you attach a `resultSchema` to the results the code has to read?
- [ ] Did you look only at `isSuccess()` and miss `isComplete()`?
- [ ] You need resume — did you forget the `stepResultCache`? (the default is NO_OP)
- [ ] You want resume — are you using `DEFAULT_RUN_ID`? (it is not cached)
- [ ] Did you set `perBatchMax` on a shared runner?
- [ ] Is the event sink thread-safe?
- [ ] You use `isolate(true)` — did you inject a `worktreeFactory`?
- [ ] Do you close a runner you created per call? Do you leave a borrowed manager unclosed?
- [ ] In a `loopUntilDry`-style loop, does the dedup key on "everything seen" rather than "the confirmed set"?
- [ ] If you bounded coverage (top-N, sampling), did you record what you dropped with `ctx.log`?

---

## Related documents

- [Workflow CLI guide](workflow-cli-guide.en.md) — the aimon-cli user's view (`Workflow`/`WorkflowJs`, `/runs`)
- [Subagent development guide](../subagent/subagent-development-guide.en.md) — defining and registering with `Subagent.builder()`
- [Tool development guide](../tool/tool-development-guide.en.md) — for exposing a workflow as a tool
- [Application embedding guide](../../getting-started/embedding-agent-in-application.en.md) — assembling contexts and scopes
- [Interruptible tools guide](../agent-execution/interruptible-tools-guide.en.md) — how the cancellation signal propagates
