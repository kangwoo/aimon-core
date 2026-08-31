---
translated_from: docs/features/tool/parallel-tool-execution-guide.md
source_commit: a9821d44
---

# Parallel tool execution guide

> A developer and operator guide to the feature that runs the several `tool_use` entries in one LLM
> response in parallel — but only when they are independent of one another and safe to run concurrently —
> to cut the wall-clock latency of tool execution per iteration.

Related documents: [design document](../../design/tool/parallel-execution.md) ·
[Tool development guide §Concurrency safety](tool-development-guide.en.md#concurrency-safety-concurrencybehavior)

---

## 1. Overview

When the LLM returns several `tool_use` entries in one response, that is a **signal of parallel intent** — "these do not need to wait for one another's results". Acting on that signal alone and going straight to parallel execution can produce side-effect collisions and races on shared state, though. So AIMON parallelises only after a two-layer judgement: **the model's intent plus the framework's safety check**.

- **The default is OFF.** Merely adopting the feature changes no behaviour (zero regression). It parallelises only once you turn it on explicitly.
- **It is conservative.** A tool declares `CONCURRENT_SAFE` for itself, and the batch runs in parallel only when *every* tool in it is safe. If even one is uncertain, the whole batch runs sequentially.
- **Result order is always preserved.** Even executed in parallel, results are reassembled in `tool_use` input order.
- **A two-tier concurrency limit.** `maxConcurrency` is the **global ceiling** on the worker pool the executor shares (host protection); `perBatchMax` limits **how much of that pool a single batch may occupy at once** (so one batch cannot monopolise it). The default is `perBatchMax = maxConcurrency`, which behaves identically to a single tier (§2.3).

Scope: both the main agent (`OrcaAgentExecutor`) and subagents (`DefaultSubagentExecutor`).

---

## 2. Quick start

### 2.1 Turning it on for the main agent

```java
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;

TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withToolConcurrencyConfig(ToolConcurrencyConfig.enabled(4)) // maxConcurrency = 4
        .create(llmClient, transcriptManager);
```

If you use `OrcaAgentExecutor.builder()` directly:

```java
OrcaAgentExecutor executor = OrcaAgentExecutor.builder()
        .llmClient(llmClient)
        .conversationManager(conversationManager)
        // ... the required managers ...
        .parallelToolDispatcher(new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4)))
        .build();
```

### 2.2 Turning it on for subagents

`DefaultSubagentExecutor` has no builder, so you inject it with a fluent setter:

```java
SubagentExecutor executor = new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager)
        .withParallelToolDispatcher(new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4)));
```

Configure neither and both keep running **sequentially** (the existing behaviour).

### 2.3 The per-batch fairness cap (`perBatchMax`) — optional

`maxConcurrency` is the global ceiling on **the pool the executor shares**. When several conversations or turns pass through one executor at the same time (multi-conversation web hosting, say) they share that pool, so a turn that asks for N safe tools at once can monopolise the slots and starve the other concurrent turns. To prevent that, cap **how many slots a single batch may hold in the pool at once** with `perBatchMax`:

```java
// the global pool is 8; a single batch may hold at most 2 of those slots at once
ToolConcurrencyConfig.enabled(8, 2);

// the same through the builder
ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(8).perBatchMax(2).build();
```

- Leave `perBatchMax` unset and it is set **equal to `maxConcurrency`** → identical to a single tier (bit-for-bit compatible).
- The valid range is `[1, maxConcurrency]`. Outside it (including an explicit `0`), `build()` throws `IllegalArgumentException`.
- Permits are acquired **on the calling thread, just before submit**, so a tool waiting for a slot does not occupy a shared worker thread (it does not starve other turns). If a batch is larger than `perBatchMax`, submission simply serialises at that point through backpressure — the global pool and result-order preservation are untouched.

> **Note:** `perBatchMax` is a cap per *batch (within a turn)*. It cannot stop a chatty conversation from firing batches back to back — fairness per conversation or tenant, and cluster-wide ceilings, belong to the session/request admission layer rather than to the tool dispatcher.

---

## 3. How it works

### 3.1 The two-layer judgement gate

```
the tool_use list (batch) of one LLM response
        │
        ▼
Layer 1 — intent (LLM)
   · is the parallel feature turned on? (ToolConcurrencyConfig.enabled)
   · is the batch size 2 or more?          ── no → sequential
        │ yes
        ▼
Layer 2 — safety (framework)
   for "every" tool in the batch
   · is ConcurrencyBehavior == CONCURRENT_SAFE, and
   · is InterruptBehavior ∈ { NON_INTERRUPTIBLE, COOPERATIVE }?
   · do they all resolve in the registry? (hallucinated/misspelt name → sequential)
        │ all pass
        ▼
   parallel execution (bounded daemon pool)
```

If even one condition fails, **the whole batch runs sequentially** (partial parallelisation of a mixed batch is deliberately excluded — §6).

### 3.2 Result-order preservation · event order

- **Results**: even executed in parallel, the `ToolUseResult` list is reassembled in input `tool_use` order (by index).
- **`ToolUseStarted` events**: emitted **in input order** on the calling thread (deterministic).
- **`ToolResultReady` events**: emitted in actual **completion order** (non-deterministic under parallelism). Renderers match on `toolUseId`, so consistency is not at risk. **A UI or renderer must not depend on the temporal order of Ready events.**
- The conversationMemory append happens after the join, on a single thread, as a single batch.

### 3.3 Exception isolation

The dispatcher catches every exception and null result escaping from `runner` (executing a single tool), `onStarted` and `onCompleted`, and either converts it into an error `ToolUseResult` or logs and swallows it. One tool's or one listener's failure does not break batch order, the join, or the other tools.

---

## 4. Making a tool CONCURRENT_SAFE

The default is `ConcurrencyBehavior.SEQUENTIAL`. Override it explicitly only for tools that are safe:

```java
@Override
public ConcurrencyBehavior getConcurrencyBehavior() {
    return ConcurrencyBehavior.CONCURRENT_SAFE;
}
```

### The checklist before declaring — if anything is uncertain, leave it `SEQUENTIAL`

- [ ] **Is it side-effect free or idempotent?** It does not mutate files, the sandbox or external state.
      Mutating tools such as `Edit`/`Write`/`Bash`/`TodoWrite` must be `SEQUENTIAL`.
- [ ] **Does it touch shared mutable state only in a thread-safe way?** If any value handed over through
      `ToolContext` is a mutable object the tool mutates, it has to be thread-safe (§5).
- [ ] **Is its InterruptBehavior `NON_INTERRUPTIBLE` or `COOPERATIVE`?** `THREAD_INTERRUPT`/
      `EXTERNALLY_TERMINATED` tools register their terminator against the executing thread, which is
      ambiguous on a shared worker thread → the gate **excludes them automatically** (they are not
      parallelised even if they declare `CONCURRENT_SAFE`).
- [ ] **Are this tool's Pre/PostTool hooks thread-safe?** The hooks of a parallelisable tool can be called
      concurrently from worker threads.
- [ ] **Does it avoid contending for the same external resource?** Tools that fight over the same file or the
      same rate-limited endpoint are safer left `SEQUENTIAL`.

### Tools currently declared CONCURRENT_SAFE

| Tool | InterruptBehavior | Rationale |
|------|-------------------|-----------|
| `ReadTool` | NON_INTERRUPTIBLE | Read-only. Its only shared state (the `READ_FILES_KEY` Set) is injected by the executor as a thread-safe set (§5) |
| `GrepTool` | COOPERATIVE | Read-only. Allocates all mutable state per call, locally |
| `WebFetchTool` | COOPERATIVE | Idempotent external GET. The cache is synchronized. Concurrent fetches of the same URL may each miss (a duplicate request), which is harmless because it is idempotent |

> When you add a new tool, verify it individually against the checklist above before deciding.

---

## 5. The thread-safety contract

### 5.1 Shared mutable state in `ToolContext`

`ToolContext` is structurally immutable (the map itself is unmodifiable), but **the stored values** are not deep-copied. Under parallel execution, a tool that mutates a mutable value in `ToolContext` produces a race.

The only mutable value the framework identified is the `ReadTool.READ_FILES_KEY` Set. Both executors **inject it as a thread-safe set** at `createToolContext` time:

```java
builder.put(ReadTool.READ_FILES_KEY, ConcurrentHashMap.newKeySet());
```

- This set is created once per turn and kept across iterations (so read-before-edit works across several iterations).
- (A side effect) Before this injection, `READ_FILES_KEY` was never injected in production at all, so `EditTool`'s read-before-edit guard was effectively a no-op — this change is what makes it work at last.

> **A caution for new tools:** a tool that puts mutable state into `ToolContext` and mutates it must either declare `SEQUENTIAL` or use a thread-safe data structure.

### 5.2 Framework infrastructure that is already parallel-safe

`ToolInput`/`ToolResult`/`ToolContext` (immutable), `ArtifactCollector`/`EventEmitter` (CopyOnWriteArrayList) and `DefaultHookExecutionManager` (stateless) are all safe under parallel execution.

### 5.3 User-defined hooks

On the parallel path the whole of `executeSingleTool` (the call site of the Permission/Pre/PostTool hooks) runs **concurrently** on a `tool-dispatch-worker-N` thread for every tool in the batch. That is, **the same hook instance is called several times concurrently within one turn**. On the sequential path hooks within a turn were always serial, so turning parallelism on creates the new exposure points below.

The framework plumbing (`DefaultHookRegistry` = `CopyOnWriteArrayList`, `DefaultHookExecutionManager`/`DefaultHookExecutor` = per-call local state plus immutable context/result, `executionAttributes` = `Map.copyOf` immutable sharing) is all safe under concurrent calls. **What remains is the hook author's responsibility.**

#### (1) Mutable state inside a hook → races

If a user-registered Pre/PostTool hook holds internal mutable state (a turn counter, "previous tool" tracking, an unsynchronised collection …), it is called concurrently from worker threads and races. **Hooks attached to parallelisable tools have to be thread-safe** (`AtomicInteger`, `ConcurrentHashMap`, synchronisation …).

#### (2) Reversed side-effect order (happens even when thread-safe)

The dispatcher reassembles **the result list** in input order, but **a hook's side effects** (audit logs, metrics, console output, notifications) interleave in **completion order**. Even a thread-safe hook breaks the assumption that "records appear in tool order". If order matters to a hook, either (a) record a sort key alongside (the tool index or `iterationCount`), or (b) keep the tool that hook attaches to `SEQUENTIAL` and force serial execution.

> **Example (a hook in real deployment):** the CLI's `ToolCallDisplayHook` (PreTool) prints straight to the shared `OutputFormatter` console. Turn parallelism on with that hook registered and the tool-call banners interleave or come out of order (not data corruption, but poorer readability).

#### (3) Concurrent interactive ASK

When a PreTool/PermissionRequest hook returns `Decision.ASK` it is promoted to `AskPromptHandler.resolve()`, and an interactive handler can block on user input. If two tools raise ASK at once, the prompts contend for the same stdin (rare, since `CONCURRENT_SAFE` mostly means read-only, but possible). If you use interactive ASK together with parallelism, make sure `AskPromptHandler` serialises its prompts.

#### (4) Concurrent `RewakeService.schedule()`

An async rewake in a PostTool hook calls `RewakeService.schedule(...)` concurrently from worker threads. The default `RewakeService.NOOP` is safe, but plug in a real implementation and it has to be thread-safe — and the order in which rewakes are scheduled becomes non-deterministic.

#### (5) Care when injecting a bounded `hookExecutor`

The default `DefaultHookExecutor` is an unbounded cached pool, which is safe. Inject a **bounded pool** through `DefaultHookExecutor(ExecutorService)`, however, and `executeParallel` nests `supplyAsync→get→supplyAsync` on that same pool, so tool parallelism multiplies with hook parallelism and the risk of starvation grows. Size an injected pool generously, or keep the cached pool.

---

## 6. Core API

| Type | Package | Role |
|------|---------|------|
| `ConcurrencyBehavior` | `at.aimon.core.agent.tool` | The `SEQUENTIAL` (default) / `CONCURRENT_SAFE` enum |
| `Tool#getConcurrencyBehavior()` | `at.aimon.core.agent.tool` | Per-tool policy declaration (default `SEQUENTIAL`) |
| `ToolConcurrencyConfig` | `at.aimon.core.agent.tool` | Immutable configuration: `enabled` (false by default) + `maxConcurrency` (the global pool, 4 by default) + `perBatchMax` (the per-batch cap, = maxConcurrency by default) |
| `ParallelToolDispatcher` | `at.aimon.core.agent.tool` | The gating, distribution and order-reassembly interface |
| `DefaultParallelToolDispatcher` | `at.aimon.core.agent.tool` | The bounded daemon-pool implementation, `AutoCloseable` |

```java
// creating the configuration
ToolConcurrencyConfig.disabled();        // default: parallelism OFF (no pool is created)
ToolConcurrencyConfig.enabled(8);        // ON, maxConcurrency=8, perBatchMax=8 (= max)
ToolConcurrencyConfig.enabled(8, 2);     // ON, maxConcurrency=8, perBatchMax=2 (the per-batch cap)
ToolConcurrencyConfig.builder().enabled(true).maxConcurrency(6).perBatchMax(3).build();

// the dispatcher
DefaultParallelToolDispatcher.sequential();                       // always sequential
new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(4));
```

> `DefaultParallelToolDispatcher` lives in `at.aimon.core.agent.tool` rather than `at.aimon.core.agent.tool.impl` — like `DefaultToolRegistry`, this domain does not use an `.impl` sub-package.

---

## 7. Operations guide

- **Tuning `maxConcurrency`**: the default is 4. With many I/O-bound tools (files, HTTP), 4–8 is a reasonable range. Set it too high and you can put pressure on external resources (the filesystem, a remote rate limit). COOPERATIVE tools are not preempted in the middle of a single file or HTTP call, so a few slow tools can occupy the pool's threads.
- **Preventing batch monopolisation with `perBatchMax`**: where several conversations or turns share one executor's pool (multi-conversation web hosting, say), set `perBatchMax` below `maxConcurrency` (`enabled(8, 2)`, for instance) so one turn's large batch cannot monopolise the pool. Where there are no concurrent turns, as in a single-agent CLI, the default (= `maxConcurrency`) is enough (§2.3).
- **Pool lifecycle**: the pool is executor-scoped, **lazy and made of daemon threads**. When the feature is inactive (the default) no pool is created at all (zero resources). Every `dispatch()` joins all its tasks before the turn ends, so worker activity does not leak outside the turn, and the pool's lifecycle is not coupled to the per-turn `InterruptCoordinator`.
- **Explicit shutdown**: `DefaultParallelToolDispatcher` is `AutoCloseable`. `close()` drains in-flight tasks for up to 30 seconds before shutting down, and it is idempotent. The threads are daemons and so do not block JVM exit, which usually makes an explicit call unnecessary — but in an environment that repeatedly creates and removes agents at runtime, consider wiring the dispatcher's `close()` into the agent-removal path (there is no automatic wiring today — §8).
- **Multiple instances**: parallel execution is in-memory concurrency within a single turn, so it is not something you swap a store for. Size each instance's pool independently.

---

## 8. Troubleshooting — "why is it not running in parallel?"

If a batch runs sequentially, it is one of the following (the gate did not pass):

1. `ToolConcurrencyConfig` is inactive (the default). → check `withToolConcurrencyConfig(enabled(N))`.
2. The batch has only one `tool_use`. → parallelism is meaningless.
3. The batch contains at least one `SEQUENTIAL` tool (`Edit`/`Write`/`Bash`/`TodoWrite`/MCP …).
4. A tool is declared `THREAD_INTERRUPT`/`EXTERNALLY_TERMINATED` → automatically excluded by the gate.
5. A tool name does not resolve in the registry (an LLM hallucination or typo) → conservatively sequential.
6. The dispatcher has been `close()`d → sequential fallback.

---

## 9. Limitations / known issues

- **A mixed batch runs entirely sequentially.** Partial parallelisation — splitting into "the safe ones in parallel, the rest sequentially" — was deliberately excluded because of order-dependence risk (a candidate for a future extension).
- **`THREAD_INTERRUPT`/`EXTERNALLY_TERMINATED` tools are excluded from parallelism.** Cross-thread interrupt semantics are ambiguous, so they were left out of the initial scope.
- **MCP tools (`McpTool`) stay `SEQUENTIAL`.** An MCP server's tools can have arbitrary side effects (writes included), so the framework cannot guarantee safety for them wholesale. Review a specific MCP tool individually, and only when it is certainly read-only and idempotent.
- **Concurrent WebFetch of the same URL**: the cache is check-then-act, so concurrent misses can send duplicate requests. It is an idempotent GET, so there is no consistency problem — just one wasted request.
- **No automatic wiring of pool close**: the threads are daemons so nothing hangs, but frequent runtime agent hot-reloads can accumulate pool threads. Wire the dispatcher's `close()` into the agent-removal path if you need to.
- **(A pre-existing issue, unrelated to this feature) SSRF**: `WebFetchTool`'s SSRF protection is now configurable through `SsrfGuardConfig` — `SsrfGuard()` (the default, secure) / `SsrfGuard(SsrfGuardConfig.disabled())` (entirely off) / `SsrfGuardConfig.builder().allowHost("internal.host").build()` (a trusted-host allow-list). Registering the DNS-rebinding TOCTOU mitigation (`SsrfRedirectInterceptor`) on the production OkHttpClient remains a separate task, though.

---

## 10. Related documents

- [Design document: ConcurrencyBehavior — parallel tool execution](../../design/tool/parallel-execution.md)
- [Tool development guide](tool-development-guide.en.md)
- [InterruptBehavior design document](../../design/agent-execution/interrupt.md) — the precedent for separating capability from coordinator
- [SOLID principles](../../project/solid-principles.md)
