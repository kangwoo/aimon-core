---
translated_from: docs/features/observability/execution-tracing-guide.md
source_commit: a9821d44
---

# Execution Tracing Guide

> Record and inspect LLM agent execution as a **hierarchical span tree**, LangSmith style, for debugging.

This document covers **switching tracing on and using it** (operators/users) and **extending it** (developers). For the
design background, the alternatives and the decisions, see the [agent execution tracing design document](../../design/observability/tracing.md).

## Table of contents

1. [Overview](#overview)
2. [Quick start — the CLI](#quick-start--the-cli)
3. [Wiring it programmatically](#wiring-it-programmatically)
4. [The span model](#the-span-model)
5. [Querying traces](#querying-traces)
6. [Extending — the store / exporter SPIs](#extending--the-store--exporter-spis)
7. [Payload capture and masking (TRACE-02)](#payload-capture-and-masking-trace-02)
8. [Fail-safe guarantees](#fail-safe-guarantees)
9. [Scope and limits](#scope-and-limits)
10. [Related documents](#related-documents)

---

## Overview

A single turn (one `AgentSession.submit()` / `OrcaAgentExecutor.execute()`) is recorded as a **span tree**.

```
TURN  turn:<agent>                       ← one turn = one trace (root span)
├─ ITERATION iteration#1                 ← ReAct step
│  ├─ LLM  llm:<model>   (prompt/response/tokens/latency)
│  └─ TOOL <name>        (input/output/success·failure)
├─ ITERATION iteration#2
│  ├─ LLM  ...
│  └─ SUBAGENT ...        (nested when a TOOL spawns a subagent)
└─ ITERATION iteration#3
   └─ LLM  ...
```

| Concept | Meaning |
|------|------|
| **Session** | One conversation (`ConversationId`). The parent group tying several turns (traces) together |
| **Trace** | One turn. Identified by the turn root span id |
| **Span** | A single unit of work: `TURN` / `ITERATION` / `LLM` / `TOOL` / `SUBAGENT` / `COMPACTION` / `RETRIEVER` |

The essential properties:

- **Off by default, zero overhead** — inject no tracer and `Tracer.noop()` takes over, leaving execution untouched.
- **Fail-safe** — tracing never breaks agent execution, under any circumstances ([§7](#fail-safe-guarantees)).
- **Multi-instance ready** — storage sits behind the `TraceSpanStore` interface, so the in-memory default implementation
  can be swapped for an external backend.

---

## Quick start — the CLI

Switch tracing on in the config file (it defaults to `false`):

```yaml
cli:
  tracing: true
```

Run one conversation, then type `/trace` in the REPL and the most recent turn's span tree is printed:

```
> find the ERRORs in the logs
... (the agent's answer) ...

> /trace
Trace 7f3a1c9e-...
• TURN turn:ops-bot  [OK, 4210ms]
  • ITERATION iteration#1  [OK, 2050ms]
    • LLM llm:claude-opus-4-8  [OK, 1400ms, model=claude-opus-4-8, tokens=1540]
    • TOOL Grep  [OK, 38ms]
  • ITERATION iteration#2  [OK, 1900ms]
    • LLM llm:claude-opus-4-8  [OK, 1850ms, tokens=2100]
```

Each span line takes the shape `type name [status, latency, (model=…, tokens=…)]`. With tracing switched off, `/trace`
prints a notice instead.

---

## Wiring it programmatically

Outside the CLI (a web adapter, a test, a custom host) you **inject one and the same tracer in two places** — LLM spans
are produced by the `TracingLlmClient` decorator, turn/iteration/tool spans by the executor.

```java
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.TraceSpanStore;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

// 1) the store + the tracer (one shared instance)
TraceSpanStore store = new InMemoryTraceSpanStore();
Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

// 2) wrap the LLM client in the decorator (LLM spans)
LlmClient tracedClient = new TracingLlmClient(realLlmClient, tracer);

// 3) inject the same tracer into the executor (turn/iteration/tool spans)
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withTracer(tracer)
        .create(tracedClient, conversationManager);

// 4) query after execution
executor.execute(context, request);
List<TraceSpan> turnSpans = store.byTrace(turnRootSpanId);   // one turn
List<TraceSpan> allSpans  = store.bySession(conversationId); // every turn of one conversation
```

`OrcaAgentExecutor.builder().tracer(tracer)` injects it just as well. With no tracer injected, the executor keeps
`Tracer.noop()` (no regression).

> **The `TracingLlmClient` and the executor must hold the same tracer instance.** That is what puts LLM spans and
> turn/tool spans into the same store, and what lets the decorator read the span tags the executor planted in the
> metadata so it can hang LLM spans under the right parent.

---

## The span model

The principal fields of `TraceSpan` (an immutable value object):

| Field | Description |
|------|------|
| `sessionId` | The conversation-level group (= conversationId) |
| `traceId` | The turn level (= the turn root span id) |
| `spanId` / `parentSpanId` | This span / its parent (a root has no parent) |
| `type` | `TURN` / `ITERATION` / `LLM` / `TOOL` / `SUBAGENT` / `COMPACTION` / `RETRIEVER` |
| `name` | For example `turn:<agent>`, `iteration#3`, `llm:<model>`, a tool name |
| `startTime` / `endTime` / `latency()` | Timing and latency |
| `status` | `OK` / `ERROR` / `INTERRUPTED` |
| `inputs` / `outputs` | A **summary** of input and output (message count, tokens, tool input …) |
| `tokenUsage` / `model` | LLM spans only |
| `attributes` | Extra information such as `iteration` |

**Span context propagation** is not thread-local; it flows explicitly through the reserved tags on `LlmCallMetadata`
(`aimon.trace_id`, `aimon.parent_span_id`). Parent-child links therefore stay correct even under parallel tool dispatch
and async streaming.

---

## Querying traces

The `TraceSpanStore` interface:

```java
void record(TraceSpan span);                 // non-blocking, fail-safe
Optional<TraceSpan> get(String spanId);
List<TraceSpan> byTrace(String traceId);     // one whole turn (for rebuilding the tree)
List<TraceSpan> bySession(String sessionId); // every turn of one conversation
void deleteOlderThan(Instant cutoff);        // the retention policy
```

The tree is rebuilt through `parentSpanId`: take every span of a turn with `byTrace(traceId)`, then walk down from the
root — the one whose `parentSpanId == null` — following its children (the CLI's `renderTraceTree` behind `/trace` is
the worked example).

```java
List<TraceSpan> spans = store.byTrace(traceId);
TraceSpan root = spans.stream().filter(s -> s.getParentSpanId().isEmpty()).findFirst().orElseThrow();
// build a children index keyed by parentSpanId and render recursively from the root
```

`InMemoryTraceSpanStore` is bounded (`DEFAULT_MAX_SPANS = 10_000` by default, FIFO eviction) and meant for CLI
debugging. In production, swap it for a persistent backend ([§6](#extending--the-store--exporter-spis)).

---

## Extending — the store / exporter SPIs

### Swapping the store (`TraceSpanStore`)

A persistent store implements `TraceSpanStore` and lives in its own module (`aimon-tracing-postgres`, say). It must be
thread-safe and non-blocking in a multi-instance deployment.

```java
public final class PostgresTraceSpanStore implements TraceSpanStore {
    @Override public void record(TraceSpan span) { /* INSERT — never throw */ }
    @Override public List<TraceSpan> byTrace(String traceId) { /* SELECT ... WHERE trace_id = ? */ }
    // ... bySession, get, deleteOlderThan
}
```

### Exporting outward (`SpanExporter`)

To export into a mature LLM tracing UI — OpenTelemetry, Langfuse, Tempo, Phoenix — instead of using the built-in
viewer, implement `SpanExporter`. `DefaultTracer` calls both `record()` and `export()` at `close()`.

```java
SpanExporter otlp = span -> { /* map onto the OTel GenAI convention and send over OTLP — non-blocking, never throw */ };
Tracer tracer = new DefaultTracer(store, otlp);
```

A `SpanType.LLM` span carries `model` and `tokenUsage`, which map cleanly onto the `gen_ai.*` attributes.

### Deterministic tests

`DefaultTracer` takes an injected `Clock` and span-id generator. A test pins a fixed `Clock` and a counter-based id to
verify deterministically:

```java
AtomicInteger counter = new AtomicInteger();
Tracer tracer = new DefaultTracer(store, SpanExporter.noop(),
        Clock.fixed(instant, ZoneOffset.UTC), () -> "span-" + counter.incrementAndGet());
```

(Reference tests: `DefaultTracerTest`, `InMemoryTraceSpanStoreTest`, `TracingLlmClientTest`,
`OrcaAgentExecutorTracingTest`.)

---

## Payload capture and masking (TRACE-02)

A span carries only a **summary** by default (the length of a tool result, the length of the LLM's response text). To
put the **body of a tool result** and the **LLM's response text** into the span for debugging and evaluation, switch
`TracePayloadPolicy` to `FULL`. The body is always truncated at a ceiling (`maxChars`), and a `SpanRedactor` masks
sensitive keys immediately before storage.

```java
import at.aimon.core.tracing.SpanRedactor;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.impl.DefaultTracer;

// 1) the payload capture policy (summaryOnly by default = summary only). FULL captures the body as well, truncated at maxChars.
TracePayloadPolicy policy = TracePayloadPolicy.full(8192);

// 2) masking just before storage: *token*/*secret*/*password*/*credential*/*apikey*/*authorization* keys → ***REDACTED***
Tracer tracer = new DefaultTracer(store, SpanExporter.noop(), SpanRedactor.defaultRedactor());

// 3) inject the same policy at both capture points
LlmClient tracedClient = new TracingLlmClient(realLlmClient, tracer, policy);  // the LLM's response text
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withTracer(tracer)
        .withTracePayloadPolicy(policy)                                        // the body of a tool result
        .create(tracedClient, conversationManager);
```

In the CLI it is switched on through configuration (tracing itself has to be on):

```yaml
cli:
  tracing: true
  tracingCaptureContent: true   # capture tool-result and LLM-response bodies (false by default)
  tracingMaxPayloadChars: 8192  # the body truncation ceiling (8192 by default)
```

Under `tracing: true` the CLI **always** wires the default redactor (`SpanRedactor.defaultRedactor()`), so sensitive
keys stay masked even with payload capture on. The summary fields (`contentChars`/`textChars`) survive FULL mode too,
so the pre-truncation length is still knowable.

> **Careful — the cost and the limits of capture:** tool results can be large (file reads, command output), which
> drives up the memory, storage and export cost. Set `maxChars` conservatively when switching this on in production.
> The default redactor is **key-based**, so it cannot catch a secret embedded in free-text prose — in a sensitive
> environment, think hard before persisting, or harden it with a custom `SpanRedactor`.

For the design background, see the [Trace Payload Capture design](../../design/observability/tracing.md).

---

## Fail-safe guarantees

Tracing follows the principle that **observability must not threaten availability** (the same rule as a tool's *never
throw*).

- `Tracer.Span#close()` never throws — it swallows exceptions from `record()`/`export()` and logs a WARN.
- The executor wraps its tracer calls (`startRoot`/`startChild`) defensively. **Even if the tracer implementation
  throws**, it falls back to a no-op span, carries the turn through, and does not skip conversation persistence
  (`saveSilently`).
- So agent execution stays safe even when a custom `TraceSpanStore`/`SpanExporter`/`Tracer` throws through a bug.

When writing your own implementation, make `record`/`export` non-blocking and have them swallow their internal
exceptions too.

---

## Scope and limits

| Item | Today |
|------|------|
| What is traced | **Agent turns only** (the ReAct loop + subagents). Background subsystems (wiki indexing, peer memory, the dreamer) are deliberately untraced — those calls have no turn span context, so wrapping them would produce no span anyway |
| Subagents | A subagent's LLM and tool spans currently attach **flat, under the parent iteration**. A dedicated `SUBAGENT` nesting layer is future work |
| Input/output capture | By default only the summary is stored (message count/tokens/tool input). `TracePayloadPolicy.full(maxChars)` optionally captures the **tool-result and LLM-response bodies** (truncated at the ceiling). A `SpanRedactor` masks sensitive keys just before storage — [§payload capture](#payload-capture-and-masking-trace-02) |
| Sampling / a non-blocking worker | Not implemented. Under heavy production traffic, a follow-up `TraceSampler` plus a bounded-queue worker is recommended |
| External backends | The `aimon-tracing-otlp`/`aimon-tracing-postgres` modules are future work. Today there is the in-memory default plus the `SpanExporter` SPI |

> **A security note**: `InMemoryTraceSpanStore` currently keeps raw tool input in memory. Where credentials or PII
> reach tool input, think hard before persisting in production until redaction is added.

---

## Related documents

- [The agent execution tracing design document](../../design/observability/tracing.md) — architecture, decisions, alternatives
- [Tool development guide](../tool/tool-development-guide.en.md) — the shared fail-safe principles, *never throw* among them
- [Parallel tool execution guide](../tool/parallel-tool-execution-guide.en.md) — why span context is propagated explicitly
- [SOLID principles](../../project/solid-principles.md)
