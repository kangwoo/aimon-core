---
translated_from: docs/features/agent-execution/command-queue-guide.md
source_commit: a9821d44
---

# Command Queue Guide

> A guide to the **mid-turn command queue** — the mechanism that keeps user input arriving during an agent execution instead of dropping it, and injects it at the next iteration boundary.

This document describes the public API of the `at.aimon.core.agent.queue` package, the usage patterns, observability, and how to swap the storage out in a multi-instance deployment.

## Table of contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [The public API surface](#the-public-api-surface)
4. [Usage patterns](#usage-patterns)
5. [Observability and metrics](#observability-and-metrics)
6. [Multiple instances — swapping the repository](#multiple-instances--swapping-the-repository)
7. [A test recipe](#a-test-recipe)
8. [Design principles](#design-principles)

---

## Overview

The reference REPL implementation **buffers into a queue** whatever the user types while the agent is answering, then injects it as a system reminder at an iteration boundary of the ReAct loop. AIMON offers the same behaviour, and its queue layer satisfies the following requirements.

- Input is **never dropped**; it is consumed at the next iteration boundary.
- **The main agent's queue and a sub-agent's queue are never consumed across each other** — they are isolated by execution context id.
- **FIFO within a priority tier**, and `NOW → NEXT → LATER` across tiers.
- The default implementation is in-memory, and because the storage sits behind an interface it can be **swapped for Redis / Mongo / … without a refactor**.

Related scenarios:

- The user corrects course mid-way through a long tool run — "actually, look at this file instead of that one".
- A sub-agent pushes an intermediate result back to its parent.
- Whatever is left in the queue when the turn ends is consumed automatically as the seed of the next turn (CQ-05).

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                Producer (REPL / sub-agent)                   │
│    MessageQueueManager#enqueue(QueuedInput)                  │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│              MessageQueueManager (facade)                    │
│  ─ listener fan-out (ENQUEUED/DRAINED/REMOVED)               │
│  ─ drainForInjection(filter, maxPriority) batch drain        │
│  ─ snapshot() — a read-only view for observability           │
└────────────────────────┬─────────────────────────────────────┘
                         │ delegates to
                         ▼
┌──────────────────────────────────────────────────────────────┐
│            MessageQueueRepository (storage)                  │
│  default: InMemoryMessageQueueRepository                     │
│  swap point: Redis, Mongo, JDBC, … (many instances)          │
└──────────────────────────────────────────────────────────────┘
                         ▲
                         │ drained at the iteration boundary
                         │
┌──────────────────────────────────────────────────────────────┐
│        Consumer (the OrcaAgentExecutor ReAct loop)           │
│    injectQueuedMessages(scope) → Message.user(...)           │
└──────────────────────────────────────────────────────────────┘
```

**The layering principle**

- `MessageQueueRepository` — a pure storage abstraction. The single point that gets replaced when a distributed backend is attached.
- `MessageQueueManager` — the facade. It owns the "meaning needed at execution time": listener fan-out, **batch drain** (guaranteeing priority-FIFO order on injection), and so on.
- Consumers (the Orca ReAct loop, the REPL) **depend on the facade only**. They must not depend on the repository directly.

## The public API surface

### `QueuedInput` (an immutable value object)

| Field | Type | Description |
|------|------|------|
| `uuid` | `UUID` | The identifier — the basis of `equals()` / `hashCode()` |
| `inputText` | `String` | The original text (cannot be empty) |
| `priority` | `QueuedInputPriority` | The priority tier (`NEXT` by default) |
| `agentExecutionContextId` | `AgentRuntimeId` | Which context is to consume this message |
| `sourceAgentId` | `Optional<String>` | The originating sub-agent (empty for the REPL) |
| `enqueuedAt` | `Instant` | When it was queued |
| `metadata` | `Map<String,String>` | Arbitrary producer tags (an immutable copy) |

Using the builder:

```java
QueuedInput queued = QueuedInput.builder()
    .inputText(userMessage)
    .priority(QueuedInputPriority.NEXT)
    .agentExecutionContextId(ctxId)
    .metadata(Map.of("origin", "repl"))
    .build();
```

### `QueuedInputPriority` (an enum whose order is fixed)

| Value | Meaning |
|----|------|
| `NOW` | As soon as possible — injected immediately at the current agent's next iteration boundary |
| `NEXT` | The default. Absorbed as a new user message when the current turn ends |
| `LATER` | Leaves the queue only when explicitly requested with `maxPriority=LATER` |

Batch drain uses `ordinal()` to mean "at most priority X", so **do not reorder the enum**.

### `MessageQueueRepository` (the storage abstraction)

```java
public interface MessageQueueRepository {
    void enqueue(QueuedInput input);
    Optional<QueuedInput> dequeue(Predicate<QueuedInput> filter);
    Optional<QueuedInput> peek(Predicate<QueuedInput> filter);
    List<QueuedInput> listByMaxPriority(QueuedInputPriority maxPriority, Predicate<QueuedInput> filter);
    boolean remove(UUID uuid);
    void subscribe(MessageQueueListener listener); // optional (a no-op in the in-memory impl)
    int size();
}
```

This is the only interface a developer attaching a distributed backend has to implement. The FIFO-within-priority guarantee is this layer's responsibility.

### `MessageQueueManager` (the facade)

```java
public interface MessageQueueManager {
    void enqueue(QueuedInput input);
    List<QueuedInput> drainForInjection(Predicate<QueuedInput> filter, QueuedInputPriority maxPriority);
    void addListener(MessageQueueListener listener);
    void removeListener(MessageQueueListener listener);
    List<QueuedInput> snapshot();
}
```

`drainForInjection` is **a single logical batch drain** — it removes in one go every item that passes `filter` and satisfies `priority.ordinal() <= maxPriority.ordinal()`, then notifies listeners with `DRAINED` in priority-FIFO order. Unlike looping over `dequeue()`, a higher-priority arrival cannot cut in midway and disturb the order.

### `MessageQueueListener`

```java
@FunctionalInterface
public interface MessageQueueListener {
    void onEvent(Event event);

    enum ChangeType { ENQUEUED, DRAINED, REMOVED }

    final class Event {
        QueuedInput getInput();
        ChangeType getChangeType();
    }
}
```

- It is called **synchronously, on the thread that made the state change**. The callback must return quickly and must work on any thread.
- An exception thrown by a listener is caught by the manager, which only logs a WARN — **it does not propagate to the other listeners or to the producer**.

## Usage patterns

### 1. The producer: queueing input while the agent is busy

```java
// ReplSession — when the agent slot is occupied, queue rather than drop
private void enqueueWhileBusy(String userMessage) {
    QueuedInput queued = QueuedInput.builder()
        .inputText(userMessage)
        .priority(QueuedInputPriority.NEXT)
        .agentExecutionContextId(agentExecutionContext.getId())
        .build();
    messageQueueManager.enqueue(queued);
}
```

### 2. The consumer: a batch drain at the iteration boundary

```java
// OrcaAgentExecutor#injectQueuedMessages — called at the tail of the ReAct loop
final AgentRuntimeId contextId = scope.executionContext.getId();
final List<QueuedInput> drained = messageQueueManager.drainForInjection(
        q -> contextId.equals(q.getAgentRuntimeId()),
        QueuedInputPriority.NEXT);

for (QueuedInput queued : drained) {
    final String wrapped = SystemReminderFormatter.wrap(MID_TURN_INJECTION_KEY, queued.getInputText());
    scope.conversationMemory.addMessage(Message.user(wrapped));
}
```

**Always include `agentExecutionContextId` in the `filter`.** Leave it out and the main agent and its sub-agents will swallow each other's input.

### 3. Consuming the leftovers automatically when the turn ends

At the moment a turn ends, check what remains with `snapshot()`; the strategy is to run each `/command` as its own turn and to consume ordinary prompts all at once with `drainForInjection(...)`.

## Observability and metrics

The queue offers two observation points. They answer different questions, so using both together is the norm.

| Point | The question it answers | A representative implementation |
|------|-------------|-----------|
| `MessageQueueListener` | The queue events themselves — when, and how many, were enqueued/drained? What is an individual message's age? | `LoggingMessageQueueListener` |
| `AgentExecutionInterceptor` | Correlation with the execution turn — queue depth at turn start and end, enqueues/drains during the turn, turn duration | `QueueMetricsInterceptor` |

Queue events **themselves** do not occur at an `execute()` boundary, so an interceptor cannot observe them — to catch events, use a listener. Conversely, when you need an observation **tied to an execution boundary** ("while this turn was running"), the interceptor is the only hook.

### The default option: `LoggingMessageQueueListener`

The reference listener the package ships with. It leaves one DEBUG log line per event and keeps a `LongAdder` counter per `ChangeType`.

```java
MessageQueueManager manager = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
LoggingMessageQueueListener metrics = new LoggingMessageQueueListener();
manager.addListener(metrics);

// … at any point …
long enqueued = metrics.getEnqueuedCount();
long drained  = metrics.getDrainedCount();
```

The log line format (DEBUG):

```
queue-event change=ENQUEUED uuid=... priority=NEXT ctx=... ageMs=0 source=repl
queue-event change=DRAINED  uuid=... priority=NEXT ctx=... ageMs=3421 source=repl
```

### Custom metrics (Micrometer / OpenTelemetry)

`LoggingMessageQueueListener` is **a sample implementation**. To feed a production metrics pipeline, implement `MessageQueueListener` yourself and attach it to the manager.

```java
public class MicrometerMessageQueueListener implements MessageQueueListener {

    private final Counter enqueued;
    private final Counter drained;
    private final Timer   ageOnDrain;

    public MicrometerMessageQueueListener(MeterRegistry registry) {
        this.enqueued   = Counter.builder("aimon.queue.enqueued").register(registry);
        this.drained    = Counter.builder("aimon.queue.drained").register(registry);
        this.ageOnDrain = Timer.builder("aimon.queue.age_on_drain").register(registry);
    }

    @Override
    public void onEvent(Event event) {
        switch (event.getChangeType()) {
            case ENQUEUED -> enqueued.increment();
            case DRAINED  -> {
                drained.increment();
                ageOnDrain.record(Duration.between(event.getInput().getEnqueuedAt(), Instant.now()));
            }
            case REMOVED  -> { /* the current default manager never publishes this */ }
        }
    }
}
```

**Do not break the callback contract** — never do blocking I/O, and although the manager isolates a thrown exception, design the listener so that it does not throw at all.

### Execution-boundary metrics: `QueueMetricsInterceptor`

`QueueMetricsInterceptor` is an `AgentExecutionInterceptor` implementation that records queue metrics **per agent execution turn**. Unlike a listener it knows where a turn begins and ends, so it can answer questions like these.

- What was this context's queue depth when the turn started? And when it ended?
- How many items were enqueued/drained **while** this turn was running?
- How long did this turn take?

Wiring it up:

```java
QueueMetricsInterceptor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult>
        queueMetrics = new QueueMetricsInterceptor<>(messageQueueManager);

AgentExecutor<OrcaAgentRuntime, OrcaAgentExecutionRequest, OrcaAgentExecutionResult> executor =
        InterceptingAgentExecutor.builder(orcaExecutor)
                .addInterceptor(queueMetrics)   // put it outermost so it wraps the whole turn
                .build();
```

An INFO log like the following is left every time a turn ends.

```
queue-metrics ctx=...id... preDepth=2 postDepth=0 enqueuedInTurn=1 drainedInTurn=3 durationMs=842
```

Reading the cumulative counters:

```java
long turns    = queueMetrics.getExecutionsObserved();
long enqueued = queueMetrics.getEnqueuedDuringExecutions();
long drained  = queueMetrics.getDrainedDuringExecutions();
```

To bridge to Micrometer, wrap the implementation and record into `registry.counter(...)` / `registry.timer(...)` in the `finally` block. The interceptor filters events by `AgentRuntimeId`, so main-agent and sub-agent turns cannot pollute each other's metrics. Internally it attaches a listener to the manager that lives only for the turn's lifetime and removes it in a `finally`, so no listener leaks even when the chain throws.

**Using it together with a listener** — registering both at once is the standard arrangement. The listener owns the cumulative totals, the interceptor the per-turn correlation.

## Multiple instances — swapping the repository

Following AIMON's multi-instance principle, the queue's storage must be movable **to a distributed backend purely by swapping the interface**. Replacing the repository leaves `MessageQueueManager`, the producers and the consumers unchanged.

### An implementation checklist

The contract to verify when writing a new `MessageQueueRepository`:

1. **FIFO-within-priority**: insertion order is preserved within a priority.
2. **Cross-priority ordering**: `NOW → NEXT → LATER` (ascending ordinal).
3. **`remove(UUID)` is idempotent**: return `false` for a uuid that is already gone; never throw.
4. **Predicate atomicity**: `dequeue(Predicate)` must evaluate the predicate and remove atomically (preventing a duplicate drain under concurrency).
5. **`listByMaxPriority` consistency**: the returned order is priority-FIFO, and the result is a defensive copy.
6. **Thread safety**: every method is safe to call from any combination of threads.

### A swap example: a single JVM → Redis

The existing code (a single instance):

```java
MessageQueueRepository repo = new InMemoryMessageQueueRepository();
MessageQueueManager    mgr  = new DefaultMessageQueueManager(repo);
```

Going distributed:

```java
// a hypothetical implementation living in an aimon-queue-redis module
MessageQueueRepository repo = new RedisMessageQueueRepository(
        redisClient,
        /*namespace=*/"aimon:queue");
MessageQueueManager    mgr  = new DefaultMessageQueueManager(repo);  // ← the facade is unchanged
```

- Producer and consumer code, listener implementations and the ReAct loop code all stay **unchanged**.
- A new implementation only needs the `implementation(project(":aimon-core"))` dependency and an implementation of `MessageQueueRepository`.

### Cautions

- `subscribe(MessageQueueListener)` is **an optional event path at the storage layer** (Redis keyspace notifications, say). The in-memory implementation makes it a no-op, and for most projects the manager's listeners are enough on their own.
- Let the storage implementation handle TTL / queue-cap policy (50 items, 30 minutes, …) itself, or design it to surface removals to a listener as `REMOVED` events. `QueuedInput`'s `enqueuedAt` is what makes an aging decision possible.
- In a distributed deployment several JVMs may consume the same queue. `dequeue()` / `remove()` must be atomic, and to prevent duplicate consumption the `filter` condition must include `agentExecutionContextId` (the context this JVM is actually running).

## A test recipe

```java
@Test
void drainsOnlyMatchingContext() {
    InMemoryMessageQueueRepository repo = new InMemoryMessageQueueRepository();
    DefaultMessageQueueManager mgr = new DefaultMessageQueueManager(repo);
    LoggingMessageQueueListener metrics = new LoggingMessageQueueListener();
    mgr.addListener(metrics);

    AgentRuntimeId main = AgentRuntimeId.of("main");
    AgentRuntimeId sub  = AgentRuntimeId.of("sub");

    mgr.enqueue(QueuedInput.builder().inputText("a").agentExecutionContextId(main).build());
    mgr.enqueue(QueuedInput.builder().inputText("b").agentExecutionContextId(sub).build());

    List<QueuedInput> drained = mgr.drainForInjection(
        q -> q.getAgentRuntimeId().equals(main),
        QueuedInputPriority.NEXT);

    assertThat(drained).hasSize(1).allMatch(q -> q.getInputText().equals("a"));
    assertThat(metrics.getEnqueuedCount()).isEqualTo(2);
    assertThat(metrics.getDrainedCount()).isEqualTo(1);
    assertThat(mgr.snapshot()).hasSize(1); // the sub-agent's input is still there
}
```

The in-memory repository can be used as-is in tests too — there is no need to build a separate mock.

## Design principles

- **Separating storage from the facade**: when a distributed backend arrives only the repository layer changes, and the rest of the code is untouched. ([multi-instance design rule](../../../.claude/rules/multi-instance-design.md))
- **Interceptor vs listener**: `AgentExecutionInterceptor` is a synchronous layer that **controls** the execution chain, while `MessageQueueListener` is an asynchronous hook that **observes** queue events. The two layers are not mutually exclusive — collect the cumulative figures that have nothing to do with turn boundaries (total enqueues, say) with `LoggingMessageQueueListener`, and collect the ones that are interlocked with them (queue depth before and after an execution, enqueues/drains per turn, execution time) with the short-lived internal listener that `QueueMetricsInterceptor` raises at the execution boundary. Do not mix the two roles (see `docs/design/agent-execution/interceptor.md` §9.2).
- **Immutable I/O**: `QueuedInput` is immutable with a builder, and `metadata` is a defensive copy. Tampering with it from outside after it has been queued cannot corrupt the internal state.
- **Listener isolation**: an exception from a listener is caught by the manager, which only logs a WARN. A bug in a metrics implementation cannot stop the ReAct loop.

---

## Related documents

- [interceptor.md](../../design/agent-execution/interceptor.md) §9.2 — the relationship with StreamingEvents
- [system-reminder-convention.en.md](system-reminder-convention.en.md) — the `<system-reminder>` wrapping rules for injected messages
- [solid-principles.md](../../project/solid-principles.md)
- `at.aimon.core.agent.queue.package-info` — a package-level Javadoc summary
