---
translated_from: docs/features/subagent/subagent-development-guide.md
source_commit: a9821d44
---

# Subagent Development Guide

> A guide to defining and registering subagents from code (Java)

This document explains how to **define and register a subagent from Java code**, with no `agents/*.md`
markdown file involved. For the design rationale and how the decisions were reached, see
[`design/subagent/code-defined-registration.md`](../../design/subagent/code-defined-registration.md).

## Table of contents

1. [Overview](#overview)
2. [When to use a code definition](#when-to-use-a-code-definition)
3. [Core concepts](#core-concepts)
4. [Using Subagent.builder()](#using-subagentbuilder)
5. [Registration: InMemorySubagentRegistry](#registration-inmemorysubagentregistry)
6. [Bootstrap wiring](#bootstrap-wiring)
7. [Composition priority — the code definition is authoritative](#composition-priority--the-code-definition-is-authoritative)
8. [Verifying the behaviour: the paths it is exposed on automatically](#verifying-the-behaviour-the-paths-it-is-exposed-on-automatically)
9. [A full example](#a-full-example)
10. [Code-behaviour (custom behavior) subagents](#code-behaviour-custom-behavior-subagents)
11. [The background fan-out pattern](#the-background-fan-out-pattern)
12. [Markdown vs a code definition](#markdown-vs-a-code-definition)
13. [Checklist](#checklist)

---

## Overview

A subagent is a specialised agent that `TaskTool` (`Task`) invokes. It shares the same ReAct execution model, but
carries its own system prompt and tool allow-list.

A subagent is **an immutable value object (`Subagent`) that is agnostic about its source (markdown or code)**. So
whether you define it in markdown (`agents/*.md`) or in code (`Subagent.builder()`), execution, hooks, cancellation
propagation, the tool permission gate and LLM usage attribution all behave **exactly the same**.

### Core principles

| Principle | Description |
|------|------|
| **Source-agnostic** | The execution layer never asks where a `Subagent` came from |
| **Immutability** | `Subagent` is an immutable value object — once built it cannot be changed |
| **Data definitions only** | A code definition supplies the prompt, tools and model/iteration/permission **settings** only. A per-subagent custom executor is not supported |
| **Markdown parity** | The builder's defaults are **identical** to the markdown parser's (`maxIterations=1000`, and so on) |
| **Authoritative** | A code definition **cannot be shadowed** by a user `.md` of the same name (security: it protects the curated tool allow-list) |

### Package structure

```
at.aimon.core.subagent/
├── Subagent.java                  # the immutable value object + Subagent.builder()
├── SubagentMetadata.java          # description / tools / model / iterations / permissions
├── SubagentContent.java           # the system prompt
├── SubagentRegistry.java          # the read-only registry interface
├── MutableSubagentRegistry.java   # writes (register/unregister) — CQRS
├── InMemorySubagentRegistry.java  # the in-memory implementation for code definitions
├── CompositeSubagentRegistry.java # layer composition (later-wins)
└── DefaultSubagentRegistry.java   # the file-based (agents/*.md) implementation
```

---

## When to use a code definition

| Situation | Recommended |
|------|------|
| A subagent your application **ships built in** (which the user must not be able to delete) | **A code definition** |
| You have to enforce a curated, **narrow tool allow-list** (security) | **A code definition** (it cannot be shadowed by a user `.md`) |
| The prompt is fixed at build/deploy time | **A code definition** |
| The user adds and edits them freely at runtime | `agents/*.md` (file-based) |
| It is updated by hot-reload while running | `agents/*.md` (a code definition is not a reload target — see below) |

> Code definitions and file definitions **coexist**. A code definition does not touch the existing file-based path at all.

---

## Core concepts

```
[file source]  agents/*.md ─► DefaultSubagentRegistry(user)  ──┐
                                                               │
[bundle]       AgentBundle.getSubagentRegistry()  ─────────────┤   CompositeSubagentRegistry(
                                                               │     [bundled, user, code])
[code]         Subagent.builder()...build()                    │          │  ← code wins
              └► InMemorySubagentRegistry.register(...) ───────┘          ▼
                                          (unchanged) TaskTool · SkillFork · /agents · the executor
```

- **`Subagent`** — an immutable value object made of a name plus `SubagentMetadata` and `SubagentContent`.
- **`InMemorySubagentRegistry`** — the `MutableSubagentRegistry` implementation that keeps code-registered `Subagent`s in memory.
- **`CompositeSubagentRegistry`** — composes the bundled, user and code registries into one view. **The later an entry sits in the list, the higher its priority**.

Since the consumers (`TaskTool`, the executor, `/agents`, a skill fork) see nothing but the read-only
`SubagentRegistry` abstraction, whatever you define in code is exposed, executed and listed automatically,
**with no change to the calling layer's code**.

---

## Using Subagent.builder()

```java
import java.util.List;

import at.aimon.core.subagent.Subagent;

Subagent dbTriage = Subagent.builder()
        .name("db-triage")                                   // required
        .description("First-pass DB incident triage. Narrows down likely causes after inspecting metrics and logs.")  // TaskTool exposes it to the LLM
        .whenToUse("When a DB incident has occurred and needs a first-pass triage")  // optional — exposed as a trigger in the TaskTool description
        .tools(List.of("Read", "Grep", "Bash(psql:*)"))      // parsed the same way as markdown allowed-tools
        .model("sonnet")                                     // a model alias
        .maxIterations(50)                                   // the ReAct loop ceiling
        .systemPrompt("You are a database triage specialist...")  // required
        .build();
```

### The builder methods

| Method | Required | The default when unset (markdown parity) |
|--------|------|-----------------------------------|
| `name(String)` | ✅ | — (null makes `build()` throw `NullPointerException`) |
| `systemPrompt(String)` | ✅ | — (null makes `build()` throw `NullPointerException`) |
| `description(String)` | | `null` |
| `whenToUse(String)` | | `null` (an optional trigger condition, exposed in the TaskTool description) |
| `tools(List<String>)` | | an empty list → `hasToolRestrictions() == false` (no tool restriction) |
| `model(String)` | | `null` (the executor's default model) |
| `maxIterations(int)` | | `1000` |

> **The tool string format** is the same as markdown's `allowed-tools`: `"Read"`, `"Bash(git:*)"`, `"Bash(npm install)"`
> and so on. Internally it goes through `AllowedTool.parse(...)`, so the parsing logic is not duplicated.

### Markdown parity (important)

The builder passes **only the fields the caller actually set** to `SubagentMetadata.builder()`. An unset field is
therefore left at the same default the markdown parser relies on. The two definitions below produce **equivalent
`Subagent`s**.

```java
// (1) code
Subagent.builder().name("plain").systemPrompt("You are a plain agent.").build();
```

```markdown
<!-- (2) agents/plain.md — equivalent -->
You are a plain agent.
```

Both end up with `maxIterations=1000`, `model=null`, `whenToUse=null` and no tool restriction.

---

## Registration: InMemorySubagentRegistry

`InMemorySubagentRegistry` is an implementation of `MutableSubagentRegistry` (read/write separation, CQRS).

```java
import at.aimon.core.subagent.InMemorySubagentRegistry;

InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
codeSubagents.register(dbTriage);                 // a name collision replaces
codeSubagents.register(Subagent.builder().name("log-analyzer").systemPrompt("...").build());

// remove where necessary (returns the removed entry, or Optional.empty())
codeSubagents.unregister("db-triage");
```

- **Thread-safe** — backed by a `ConcurrentHashMap`.
- **reload is a safe no-op** — a code definition has no "external source to re-read". `CompositeSubagentRegistry`
  **propagates reload unconditionally to every layer**, so `InMemorySubagentRegistry.reloadAll()`/`reloadSubagent()`
  leave the state exactly as it was (no throw ❌, no clear ❌). Were those methods to clear, a file hot-reload would
  evaporate the code definitions along with it.

> **A naming caution**: for subagents the `Default*` name (`DefaultSubagentRegistry`) is already taken by the
> **file-based** implementation. The in-memory implementation is therefore named `InMemory*` — the opposite of
> `DefaultToolRegistry`/`DefaultAgentRegistry` being the in-memory ones (an unavoidable asymmetry).

---

## Bootstrap wiring

Configure the code-definition registry once in the bootstrap as **application-scoped**, and inject it into
`OrcaAgentRuntimeFactory` with `withCodeSubagentRegistry(...)`.

### CLI (`AgentSetupFactory`)

```java
final InMemorySubagentRegistry codeSubagentRegistry = new InMemorySubagentRegistry();
codeSubagentRegistry.register(Subagent.builder()...build());   // the built-in code subagents (zero of them is fine)

final OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory(/* ... */)
        .withSkillRegistry(skillRegistry)
        .withCodeSubagentRegistry(codeSubagentRegistry)        // ← the wiring
        .withPendingTurnRegistry(pendingTurnRegistry)
        /* ... */;
```

### Web / an embedding application

aimon-core's web and session modules do not create the factory themselves — the application bootstrap injects an
**already-built** factory through `OrcaAgentRuntimeManager.Builder.agentRuntimeFactory(...)`. So on the web path too,
you call `withCodeSubagentRegistry(...)` **in exactly the same way** when you build the factory.

> ⚠️ **Careful (C2)**: if you ship code subagents as built-ins, you must wire them in **every bootstrap** (CLI and web).
> Wire only one of them and the code subagents go quietly missing on the other transport.

### happens-before

Build, finish **every** `register(...)`, and **only then** hand it over with `withCodeSubagentRegistry(...)`. That way
a partially filled registry is never observed at composition time. (A single-threaded bootstrap satisfies this naturally.)

---

## Composition priority — the code definition is authoritative

The composition order is **`[bundled < user < code]`**, and the **code layer — last in the list — has the highest
priority**. `CompositeSubagentRegistry.getSubagent` looks up from the back, and `getAllSubagents` lets a later layer
overwrite an earlier one, so where the same name exists, **the code definition always wins**.

| Layer | Source | Priority |
|--------|------|----------|
| bundled | `AgentBundle.getSubagentRegistry()` | lowest |
| user | `agents/*.md` (`DefaultSubagentRegistry`) | middle |
| **code** | `InMemorySubagentRegistry` | **highest (un-shadowable)** |

### A deliberate asymmetry

Skills and commands follow a **user-first** convention, yet subagents alone are the reverse — **code-first**. The
reason is **security**: it blocks a user from overwriting a code built-in that carries a curated tool allow-list with
an `.aimon/agents/*.md` that grants broader permissions.

---

## Verifying the behaviour: the paths it is exposed on automatically

A code subagent is included automatically on the following paths, **with no change to the calling layer's code**.

- **`TaskTool` (`Task`)** — the `subagent_name` input carries no enum constraint, and the tool description is generated
  dynamically from `registry.getAllSubagents()` on every call, so a code registration is exposed to the LLM.
- **The `/agents` command** — it shows the composite registry's list as it is.
- **A skill fork** — delegating by the target agent's name resolves against the composite registry.
- **A direct `@subagent` call** — the same.

---

## A full example

```java
import java.util.List;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;

// 1) define the code subagent
Subagent dbTriage = Subagent.builder()
        .name("db-triage")
        .description("A first-pass DB incident triage specialist. Use it to diagnose slow queries and lock contention.")
        .whenToUse("When a DB incident such as a slow query or lock contention needs a first-pass triage")
        .tools(List.of("Read", "Grep", "Bash(psql:*)"))   // reads plus psql only — a narrow allow-list
        .model("sonnet")
        .maxIterations(50)
        .systemPrompt("""
                You are a database triage specialist.
                1. Inspect slow-query and lock metrics.
                2. Narrow down likely root causes.
                3. Report findings; do NOT mutate production data.
                """)
        .build();

// 2) build the registry and register (application-scoped, once at bootstrap)
InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
codeSubagents.register(dbTriage);

// 3) inject it into the factory (the same for CLI and web)
OrcaAgentRuntimeFactory factory = new OrcaAgentRuntimeFactory(/* ... */)
        .withCodeSubagentRegistry(codeSubagents);

// from then on: callable as Task(subagent_name="db-triage", ...), and listed by /agents
```

---

## Code-behaviour (custom behavior) subagents

Everything so far has been a **code definition** (data: the prompt, the tools, the settings), with execution left to the
LLM ReAct loop. You can go one step further and **implement the subagent's behaviour itself in Java** — deterministic
or custom logic that bypasses the ReAct loop.

> When you need pure code logic, a **Tool** is generally the first choice. A code-behaviour subagent is for when you
> need something that "is invoked as a subagent (exposed to `Task`, `/agents` and `@name`) but whose internals are code
> rather than an LLM loop".

### The concept: a data + behaviour pair

A code-behaviour subagent is **a pair sharing one name**.

| Piece | Registry | Role |
|------|-----------|------|
| The `Subagent` data entry | `SubagentRegistry` (the code layer) | Discovery (the TaskTool list), the description, **the tool allow-list** |
| `SubagentBehavior` | `SubagentBehaviorRegistry` | Execution — it **replaces** the ReAct loop |

On dispatch the execution manager (`DefaultSubagentExecutionManager`) checks "is there a behaviour registered under this
name?" and runs the behaviour if there is, the existing ReAct loop if there is not. The data-subagent path is
**unchanged** (origin-agnosticism is preserved).

> ⚠️ Register a behaviour with no data entry and the manager cannot resolve the name, so it fails with
> `SubagentNotFoundException` (fail-fast). Always register **the pair** — `SubagentBehaviorRegistrar` does both at once.

### The `SubagentBehavior` SPI

```java
@FunctionalInterface
public interface SubagentBehavior {
    SubagentExecutionResult execute(SubagentExecutionContext context, SubagentExecutionRequest request,
            SubagentBehaviorSupport support);
}
```

- It receives **the same** `SubagentExecutionContext` (the subagent, the tool and hook registries, the environment, the
  cancellation signal, knowledge) and `SubagentExecutionRequest` (goal, principal, attributes, metadata) as the ReAct
  executor, and returns **the same** `SubagentExecutionResult` → `TaskTool` and the background consumers cannot tell the
  difference.
- `support` (`SubagentBehaviorSupport`) supplies the cancellation signal and the result builders:
  `cancellationSignal()`, `isCancelledOrInterrupted()`, `success(finalAnswer)`, `failure(errorMessage)` — there is no
  need to assemble the conversation snapshot or the metadata yourself.
- An implementation **may** use tools and the LLM through `context.getToolRegistry()`/`getEnvironment()` and the like,
  but the baseline expectation is pure code. As with `Tool.execute()`, returning `support.failure(...)` rather than
  throwing is recommended (the runner does provide a safety net that shapes a throw or a null into a failure).

### An LLM call plus ReAct-parity inputs (optional)

Deterministic logic is the default for a code behaviour, but it can also call the model directly. `support` supplies
**inputs resolved exactly as the ReAct path resolves them** — not the raw values of `getDefaultModel()`/`getToolRegistry()`:

| `support` accessor | Same as what ReAct uses? | Description |
|--------------------|--------------------------|------|
| `resolvedModel()` | ✅ | The **resolved model**, the subagent's `model` alias (say `sonnet`) merged with the default. (raw `ctx.getDefaultModel()` does not reflect the alias) |
| `scopedToolRegistry()` | ✅ | The registry filtered by the subagent's allow-list (**exposure only, not enforcement** — trusted code can still reach everything through `ctx.getToolRegistry()`) |
| `effectiveLlmCallMetadata()` | ✅ | The metadata for subagent usage attribution (component = the name, feature = `"subagent"`) |
| `llmGateway()` | ✅ | A gateway with the same config as ReAct's (default retries, no fallback). `Optional.empty()` when no `LlmClient` is wired |

```java
public SubagentExecutionResult execute(SubagentExecutionContext ctx, SubagentExecutionRequest req,
        SubagentBehaviorSupport support) {
    var gw = support.llmGateway().orElseThrow();          // Optional.empty() when unwired
    var model = support.resolvedModel();                  // the subagent's resolved model
    List<ToolDefinition> tools = support.scopedToolRegistry().findAll().stream()
            .map(Tool::getDefinition).toList();           // scoped to the allow-list

    // (A) the simple form — retries and fallback apply, but no usage attribution
    LlmResponse a = gw.sendMessage("You are ...", messages, tools, model);

    // (B) with attribution — the parts overload + effectiveLlmCallMetadata()
    SystemPromptParts parts = SystemPromptParts.of(List.of(SystemPromptPart.builder()
            .content("You are ...").staticness(Staticness.STATIC).kind("system").build()));
    LlmResponse b = gw.sendMessage(parts, messages, tools, model, support.effectiveLlmCallMetadata());

    return support.success(b.getTextContent());
}
```

> raw `ctx.getDefaultModel()` **does not reflect** the subagent's `model` alias, and `ctx.getToolRegistry()` is the full
> registry with **no allow-list applied**. To behave the way ReAct does, use `support.resolvedModel()` and
> `support.scopedToolRegistry()`.

### Registration and wiring

```java
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistrar;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;

InMemorySubagentRegistry codeData = new InMemorySubagentRegistry();
InMemorySubagentBehaviorRegistry codeBehavior = new InMemorySubagentBehaviorRegistry();

// register the data entry and the behaviour together under the same name (this prevents name drift)
SubagentBehaviorRegistrar.register(
        Subagent.builder().name("clock").description("Returns the current server time. Use it when you need the time.")
                .systemPrompt("(code behavior)").build(),
        (ctx, req, support) -> support.success("Current server time: " + java.time.Instant.now()),
        codeData, codeBehavior);

// wiring: the data goes to the context factory, the behaviour to the executor factory
factory.withCodeSubagentRegistry(codeData);                  // OrcaAgentRuntimeFactory
agentExecutorFactory.withSubagentBehaviorRegistry(codeBehavior);  // OrcaAgentExecutorFactory
```

> `systemPrompt` is a required field on the data entry so it has to be filled with something, but since the behaviour
> replaces the ReAct loop it is never actually used by a code-behaviour subagent (leave it as a placeholder).

### A limitation: the OnStart/OnStop hooks do not fire

The code path bypasses the ReAct loop, so **OnStart and OnStop — the hooks inside the loop — do not fire** (OnStart's
conversation-feedback injection only means anything when there is a conversation loop, and OnStop's termination signal
duplicates SubagentStop). The dispatch-boundary hooks **SubagentStart and SubagentStop still fire as they always did**,
so nothing is lost for observability or auditing.

### A full example

```java
public final class ClockSubagentBehavior implements SubagentBehavior {
    @Override
    public SubagentExecutionResult execute(SubagentExecutionContext ctx, SubagentExecutionRequest req,
            SubagentBehaviorSupport support) {
        if (support.isCancelledOrInterrupted()) {
            return support.failure("Execution interrupted");
        }
        return support.success("Current server time: " + java.time.Instant.now());
    }
}
```

---

## The background fan-out pattern

Sometimes you want to run several subagents **at once** to cut a turn's wall-clock time. Do not try to parallelise it
in the foreground by making the `Task` tool `CONCURRENT_SAFE` — `Task` carries the `EXTERNALLY_TERMINATED` interrupt
behaviour, so the parallelism gate (§ the parallel tool execution guide) **excludes it automatically**. Express a
fan-out on **the background path**.

### The mechanism

1. **Throw several into the background.** `Task(subagent_name=..., run_in_background=true)` returns immediately with a
   **task ID**. The subagent runs its ReAct loop on an independent thread.
2. **Poll for progress and completion.**
   - `TaskList` — lists the running and completed background tasks (with their states).
   - `AgentOutput(task_id=...)` — reads a particular task's live output and final result by offset.
   - `TaskStop(task_id=...)` — stops a task where necessary (a transition to `KILLED`, preserving the partial result).
3. **Collect the results.** Once every task you care about has finished, gather each result with `AgentOutput` and move
   on to the next step.

> **Consistent with the reference philosophy**: "a coordinator is all async" — the coordinator (the parent) does not
> block; it throws several jobs and converges by polling.

### Completion push notifications (G11) — you need not depend on polling

When a background task reaches a terminal state (COMPLETED/FAILED/KILLED), the framework **actively notifies the
parent**. Polling with `TaskList`/`AgentOutput` is still valid, but the notification means the polling loop does not
have to run tightly. Two channels fire at the same moment:

1. **Delivery to the message queue (guaranteeing the model notices).** On completion a `NEXT`-priority notification is
   pushed onto the parent's message queue, and it is injected on the parent's next ReAct iteration as a user turn
   wrapped in a `<system-reminder>`. The body is plain text saying which task ended with what result, plus a note to
   **retrieve the full result with `AgentOutput(taskId=...)`**. Which is to say: even if the parent was idle, it
   certainly learns of the completion on its next turn.
2. **An `agent.stream` event (live display and observation).** At the same moment a `SubagentTaskCompleted` event is
   emitted onto the parent executor's event stream. The CLI prints the completion line immediately, and a web embedding
   can push it over SSE. This channel is best-effort, though — it is dropped when the parent turn is idle and there is
   no listener, and in that case (1) is what guarantees the model notices.

> A practical tip: throw several into the background, then **keep doing other useful work until the completion
> notification is injected**. When it arrives, call `AgentOutput` with that `taskId` and collect the full result. A
> tight `TaskList` polling loop is no longer mandatory.

### A ceiling via a finite pool (important)

A background fan-out is not unbounded. `SubagentBackgroundConfig` **caps the number of concurrently running background
subagents**. Leave it unset and you get the old unbounded cached thread pool, where an explosive fan-out can generate
threads and LLM traffic without limit.

```java
import at.aimon.core.subagent.SubagentBackgroundConfig;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorFactory;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptManager;

// run at most 4 concurrently, the rest wait (the queue is effectively unbounded — a burst is delayed, not refused)
TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withSubagentBackgroundConfig(SubagentBackgroundConfig.of(4))
        .create(llmClient, transcriptManager);

// to refuse outright when saturated (load shedding), state the queue capacity too
// SubagentBackgroundConfig.of(4, 16);  // maxConcurrency=4, queueCapacity=16
```

- **The defaults** (`SubagentBackgroundConfig.defaults()`): `maxConcurrency = min(4, availableProcessors)`, an unbounded
  queue. → the thread and LLM fan-out is capped but a burst is not refused ("everything runs eventually").
- **State a queue** and the excess is refused when saturated, which behaves as load shedding.
- A spawn beyond the ceiling is **not refused; it waits in the queue** and runs when a slot frees up (under the default settings).

### When to use it

- When you want several **independent** sub-investigations or collections to proceed at once (with no dependency between them).
- When each subagent takes long enough that sequential execution dominates the wall clock.
- Since the capped finite pool prevents a resource runaway, even a wide fan-out converges safely.

> For the full design of the background lifecycle (lookup, listing, stopping), live output, result boundaries,
> cancellation and multi-instance sharing, see `docs/design/subagent/execution.md` (§5.1–5.3).

---

## Markdown vs a code definition

| Item | `agents/*.md` | `Subagent.builder()` |
|------|---------------|----------------------|
| Where it is defined | The file system | Java code |
| Priority | The user layer (middle) | The code layer (**highest**) |
| Can a user shadow it? | Yes (with a file of the same name) | **No** |
| hot-reload | Supported (`reloadAll`) | Not applicable (a no-op) |
| Tool parsing | `AllowedTool.parse` | `AllowedTool.parse` (identical) |
| Defaults | The `SubagentMetadata` defaults | **Identical** |
| Execution model | ReAct | ReAct (identical) |

---

## Checklist

Check these when you add a code subagent.

### The definition
- [ ] Have you set `name` and `systemPrompt`? (both are required)
- [ ] Does the `description` explain to the LLM when and how to use it?
- [ ] Is the tool allow-list narrowed to **the minimum necessary scope**? (security)
- [ ] Where you need markdown parity, have you left the unset fields alone?

### Registration and wiring
- [ ] Have you configured `InMemorySubagentRegistry` once, **application-scoped**?
- [ ] Have you handed it over with `withCodeSubagentRegistry(...)` after every `register(...)` finished?
- [ ] Have you wired it in **every bootstrap, CLI and web**? (C2 — do only one and it goes missing)

### Verification
- [ ] Does it appear in `getAllSubagents()` (the list `TaskTool` reads)?
- [ ] Does the code definition take priority over a user `.md` of the same name?

---

## Related documents

- [Code-based subagent registration design](../../design/subagent/code-defined-registration.md) — the rationale, the alternatives considered, the constraints
- [Tool development guide](../tool/tool-development-guide.en.md) — the Tools a subagent uses
- [SOLID principles](../../project/solid-principles.md)
- [Built-in Agent/Skill guide](../skill/builtin-agent-skill-guide.en.md) — built-in vs a user override
