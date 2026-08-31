---
translated_from: docs/features/session/agent-session-guide.md
source_commit: b4427fc8
---

# LiveSession development guide

> A guide to `LiveSession`, the **node-local handle** that runs multiple turns against one session
> (`SessionRecord`). Based on the SESSION-01 ~ SESSION-04 API.

> The scope model in this guide follows [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md) — `AgentRuntime` is
> agent-scoped and is shared by the sessions of the same agent.

This document explains how to use `LiveSession`, `DefaultLiveSession`, `LiveSessionOptions` and
`LiveSessionFactory` from the `at.aimon.core.agent.session` package to orchestrate a **multi-turn conversation** safely.

IMPORTANT (naming): the old names were `AgentSession` / `DefaultAgentSession` / `AgentSessionOptions` /
`AgentSessionFactory`, and the conversation identifier was `ConversationId`. The session-first rework renamed them to `LiveSession*` and
`SessionId` respectively — the bare word `AgentSession` is now **forbidden as a type name**, and
`SessionNamingArchitectureTest` blocks it at build time. When searching by an old name, consult
the mapping table in [`CHANGELOG.md`](../../../CHANGELOG.md).

## Table of contents

1. [Why LiveSession](#why-livesession)
2. [The core components](#the-core-components)
3. [The session lifecycle](#the-session-lifecycle)
4. [The close() rule: never touch the SchedulingEngine](#the-close-rule-never-touch-the-schedulingengine)
5. [Cautions with multiple sessions](#cautions-with-multiple-sessions)
6. [A complete example](#a-complete-example)
7. [Checklist](#checklist)

---

## Why LiveSession

`OrcaAgentExecutor` is a long-lived, stateless execution engine. It receives the runtime and the request together on
every call, but the session-level **identity** and **lifecycle** had to be managed by the caller.

`LiveSession` is the facade that simplifies this.

| Responsibility | Before | With LiveSession |
|----------------|--------|------------------|
| Keeping the `SessionId` | passed by the caller every time | bound by the handle at open, then reused |
| Injecting the `ExecutionBudget` | assembled with a builder on every request | specified once in `LiveSessionOptions` |
| Assembling the `OrcaAgentExecutionRequest` | done by the caller | created inside `session.submit(input)` |
| Handling re-entry while running | the caller's external `QueryGuard` | the `SubmitOutcome` of `session.offerAsync(...)` |
| Persisting the session totals and budget override | done by the caller | hydrated / flushed automatically through `SessionRecordStore` |

### Design principles

- **Facade**: `LiveSession` wraps `OrcaAgentExecutor` + `OrcaAgentRuntime` behind a thin API.
- **Dependency Inversion**: `LiveSession` is the interface and `DefaultLiveSession` the implementation.
  `LiveSessionFactory` depends on the abstract `AgentRegistry` and on a `ContextBuilder` strategy.
- **Immutable options**: `LiveSessionOptions` is an immutable value object built with the builder pattern
  (it observes the `Objects.requireNonNull` + `Optional<T>` return convention).

---

## The core components

### LiveSession (interface)

```java
public interface LiveSession extends AutoCloseable {

    SessionId getSessionId();

    /** A best-effort snapshot for diagnostics and monitoring. Do not use it as a control gate. */
    default LiveSessionStatus status() { /* ... */ }

    /** Runs one turn — identical to submit(input, SubmitOptions.empty()). */
    default AgentExecutionResult submit(String input) {
        return submit(input, SubmitOptions.empty());
    }

    AgentExecutionResult submit(String input, SubmitOptions submitOptions);

    /** Text is handed to the method above; anything else throws UnsupportedOperationException. */
    default AgentExecutionResult submit(UserInput input, SubmitOptions submitOptions) { /* ... */ }

    CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener);

    /** Queues the input if a turn is running, runs it immediately if idle — judge busyness by this return value. */
    SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener);

    default void interrupt(InterruptReason reason) { /* no-op by default */ }

    default void interrupt(TurnId turnId, InterruptReason reason) { /* no-op by default */ }

    /** Takes the last interrupted turn back out of the history and runs it again. Empty if there is nothing to undo. */
    default Optional<AgentExecutionResult> retryLastTurn(SubmitOptions submitOptions) { /* ... */ }

    @Override
    void close();
}
```

**The contract**:
- `submit(String)` runs one turn. Internally it assembles an `OrcaAgentExecutionRequest` and calls
  `OrcaAgentExecutor.execute()`.
- **The three `String` overloads are what an implementation must provide; the `UserInput` ones default on top of
  them.** An image, an attached file or a multimodal request goes in through `submit(UserInput, ...)` /
  `offerAsync(UserInput, ...)`. The default unwraps a `TextInput` and hands it to the `String` method, so **text works
  on every session** (which is what lets an older session still retry a text turn); anything else throws
  `UnsupportedOperationException` — it does not flatten the input to a text placeholder and quietly run a different
  turn.
- **The queue is a text channel.** A deferred input is replayed as a `<system-reminder>` block, so offering a non-text
  input while a turn is running throws `IllegalStateException`: it can neither wait nor run without handing two turns
  the same transcript. A caller that means to run turns concurrently uses `submitAsync`.
- `close()` **cleans up live-session-scoped resources only**. It does not touch agent-scoped (`AgentRuntime`, `McpClientManager`) or
  application-scoped (`OrcaAgentExecutor`, `SchedulingEngine`, `ScheduledTaskManager`) resources.
- `status()` and `currentTurnId()` are **best-effort observation snapshots**. Whether a turn may be started must be
  judged by the `SubmitOutcome` of `offerAsync` — code that reads `status()` and branches on it is inherently a race.
- `events()` is the hook for the STREAM-03 extension. The default implementation returns an empty `Flow.Publisher` already in the
  completed state. If you need live events today, use the listener of `submitAsync(input, options, listener)`.
- `retryLastTurn(...)` answers only for a last turn that ended `INTERRUPTED`. Retrying is not the same as asking
  again: the stopped turn left a trail behind — the user message, the synthetic context blocks injected ahead of it,
  the assistant output produced before the stop, the tool results filled in as skipped — and the retry **removes that
  trail first**, then runs from where the turn originally started. Without that, the model would be asked to redo work
  in a history that says it already half-did it.
  There is no predicate to ask "can I retry?" first — the answer can change between the asking and the doing, so
  branching on it is a race. **The empty `Optional` is the answer** (the same reason as the `status()` rule above).
  The rewind point is persisted along with the transcript, so a session stays retryable after the handle is closed and
  reopened, and on another node. What it holds is the `UserInput` the turn was submitted with **and the
  `SubmitOptions` it was submitted under**, so a turn started by any input can be retried, and it runs again under its
  original principal and system-prompt variables. The no-argument `retryLastTurn()` reuses those options;
  `retryLastTurn(options)` replaces them. `rewindLastTurn()` returns a `RewoundTurn` carrying both — submitting only
  the input would be the same words from a different caller. The design note is
  [`interrupt.md` §15](../../design/agent-execution/interrupt.md).

### DefaultLiveSession

The standard implementation. It wraps `OrcaAgentRuntime` + `OrcaAgentExecutor` + `LiveSessionOptions`.

```java
public final class DefaultLiveSession implements LiveSession {
    private final SessionId sessionId;
    private final OrcaAgentRuntime agentRuntime;       // agent-scoped (shared by several sessions)
    private final AgentExecutor<...> executor;         // application-scoped
    private volatile LiveSessionOptions options;
    private final ExecutionBudget openerDefaultBudget;
    private final MessageQueueManager messageQueueManager;   // nullable — without it offerAsync does not enqueue
    private final HookExecutionManager hookExecutionManager; // nullable — without it session hooks do not fire
    private final SessionRecordStore sessionRecords;         // nullable — without it there is no durable state
    private volatile boolean closed;

    // submit() injects the sessionId and the effective budget into the request automatically
    // close() does not close the AgentRuntime — it is agent-scoped and lives until the app shuts down
}
```

There are four constructor overloads and all of them delegate to the longest, 7-arg one. The last three collaborators are all nullable, and
a `null` simply disables that one capability (auto-queue / session hooks / durable state respectively).

```java
new DefaultLiveSession(sessionId, agentRuntime, executor, options);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager, hookExecutionManager);
new DefaultLiveSession(sessionId, agentRuntime, executor, options, messageQueueManager, hookExecutionManager,
        sessionRecordStore);
```

**The important rules**:
- `submit()` injects the `sessionId` and the effective budget into the request internally. **The caller does not have to
  think about sessionId or budget.**
- `submit()` / `submitAsync()` / `offerAsync()` after `close()` raise `IllegalStateException`.
- `close()` is **idempotent**: calling it several times has no side effects (check with `isClosed()`).
- If `sessionRecords` is supplied, `sessionTotals` and the budget override are **hydrated** from the session record at
  construction time and written back at the end of every turn. A persisted override **wins** over the default budget the opener gave.

### LiveSessionOptions

The default settings at the moment the session is opened. An immutable builder pattern.

```java
LiveSessionOptions opts = LiveSessionOptions.builder()
    .budget(ExecutionBudget.builder().maxIterations(10).maxTokens(50_000).build())
    .locale(Locale.KOREAN)
    .sourceAgentId("cli-repl")
    .build();
```

- `budget` unspecified or null → `ExecutionBudget.unlimited()` (preserving the existing behaviour)
- `locale` and `sourceAgentId` are returned as `Optional<T>`
- `LiveSessionOptions.defaults()` is identical to `builder().build()` — an unlimited budget, no locale/sourceAgentId
- `withBudget(newBudget)` makes a copy that replaces only the budget while preserving locale / sourceAgentId
  (the runtime-override path such as `/budget` uses it)

### LiveSessionFactory

It looks the agent up in the `AgentRegistry`, obtains the agent runtime through the `ContextBuilder` strategy, and creates a
`DefaultLiveSession`.

```java
// once at bootstrap: register the per-agent runtime (separately from opening a session)
OrcaAgentRuntime runtime = manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);
// → runtimeId = AgentRuntimeId.from(bundle.getAgent())  // "agent:<name>"

// the 4th argument (SessionRecordStore) may be omitted — omit it and there is no durable state.
LiveSessionFactory factory = new LiveSessionFactory(agentRegistry,
        agent -> manager.getOrCreateRuntime(bundleFor(agent), fileSystem, credentialStore),
        executor,
        sessionRecordStore);

// sessions from here on reuse the agent runtime — it is not created afresh on every open
try (LiveSession session = factory.open(
        SessionId.generate(), "default", LiveSessionOptions.defaults())) {
    session.submit("Hello");
    session.submit("What did I just say?");
}
```

`ContextBuilder` is a `@FunctionalInterface`, that is, a **strategy pattern**. The factory does not depend on the concrete type of
`OrcaAgentRuntimeFactory`; it is injected with the runtime-lookup logic as a single lambda →
observing the Dependency Inversion Principle. The implementation must be **idempotent** and must not create a new runtime on every
call — the MCP connections and the hook registrations are shared between sessions deliberately.

`open()` throws `IllegalArgumentException` if the `agentRef` is not in the registry.

---

## The session lifecycle

```
[open]  ──────────►  [submit...submit]  ──────────►  [close]
  │                         │                           │
  │                         │                           │
AgentRegistry.            OrcaAgentExecutor.        unsubscribe from queue +
findByName(ref)           execute(runtime, req)     clear turn refs + OnSessionEnd
  │                         │                           │
contextBuilder.build()    SessionTranscript         NOT AgentRuntime,
  │                       (SessionId preserved)     NOT scheduling/executor
 DefaultLiveSession
  │
hydrate from SessionRecordStore
(sessionTotals, budgetOverride)
```

### Resources by scope

Resources are classified by the four-tier scope model. For the full rules see [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md)
§1–§2, and for the design background
[`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md).

| Scope | Resource | When close() applies |
|-------|----------|----------------------|
| **Live session** | the `LiveSession` handle, the message-queue subscription, the references to the running turn (coordinator/tracker/turnId) | `session.close()` — this is all it closes |
| **Session** | `SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript` | outlives the handle. On session deletion, `SessionRecordStore.delete(sessionId)` |
| **Agent** | `OrcaAgentRuntime` (ToolRegistry, HookRegistry, SkillRegistry, the MCP connections …) | `OrcaAgentRuntimeManager.destroyRuntime` when the agent is removed or the application shuts down (never from `session.close()`) |
| **Application** | `OrcaAgentExecutor`, `LlmClient`, `SchedulingEngine`, `ScheduledTaskManager`, `AgentRegistry`, `SessionRecordStore` | at application shutdown |

> **The key point**: `session.close()` does not close the `AgentRuntime`. The runtime is agent-scoped, so several sessions of
> the same agent share it. Closing a session leaves the agent's tool/hook/skill registries and its MCP connections alive.

IMPORTANT (session ≠ live session): one `SessionRecord` has **0..N** `LiveSession`s. The session outlives a closed handle,
and after a restart, an eviction or a move between nodes another handle can serve the same `SessionId` again. Values that must
survive across that boundary therefore belong on the **record**, not on the handle — which is why `SessionTotals` and
`budgetOverride` travel through the `SessionRecordStore`.

### Multi-turn continuity

Use the same `SessionId` and the previous turn's messages are reloaded automatically through the `TranscriptManager`
attached to the executor (internally a `SessionRecordStore`).

```java
try (LiveSession session = factory.open(sessionId, "default", options)) {
    session.submit("My name is Alice.");       // turn 1
    session.submit("What is my name?");        // turn 2 — answers "Alice"
}
```

Internally:
1. `session.submit()` sets `OrcaAgentExecutionRequest.sessionId = sessionId`
2. `OrcaAgentExecutor.execute()` loads the past messages with `TranscriptManager.initialize(sessionId, systemPrompt)`
   (despite the name it is called once **per turn**, not once per session — see the list of known misnomers in
   [scope-model §6](../../overview/scope-model.en.md))
3. after appending the new turn's messages, `TranscriptManager.save(...)`

---

## The close() rule: never touch the SchedulingEngine

> Identical to the "Scope & Scheduling Lifecycle" principle in **CLAUDE.md**.

### The rules

- `LiveSession.close()` → does not close the `AgentRuntime`. The runtime is agent-scoped and is shared between sessions.
- `AgentRuntime.close()` is called only when the agent is removed or the application shuts down, through
  `OrcaAgentRuntimeManager.destroyRuntime` (to release the MCP connections and so on).
- `SchedulingEngine` and `ScheduledTaskManager` are merely **injected through the builder**; the runtime does not own them → do not
  close them.
- `OrcaAgentExecutor` and `SessionRecordStore` are **not owned by the session** → do not close them.

### Why it matters

- While hundreds or thousands of sessions open and close in one application, the scheduling engine has to keep running
  (firing scheduled tasks, running periodic tasks).
- Closing the scheduling engine per session **loses the scheduled tasks of other sessions**. `ScheduledTask.boundRuntimeId`
  references an agent-scoped id, so the runtime must still resolve when cron re-fires after the original session has ended.
- Wrong code:
  ```java
  @Override
  public void close() {
      agentRuntime.close();      // ❌ kills the MCP subprocesses of the same agent's other sessions too
      schedulingEngine.close();  // ❌ destroys other sessions' scheduled tasks
  }
  ```

### Verification (unit test)

```java
// DefaultLiveSessionTest.CloseContract
@Test
@DisplayName("close() does NOT close the agent-scoped agentRuntime (scope contract)")
void closeDoesNotDelegateToAgentRuntime() {
    // wrap a real runtime in a spy to observe which lifecycle methods get called.
    final OrcaAgentRuntime context = spy(createContext());

    final DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), context, executor,
            LiveSessionOptions.defaults());
    session.submit("hi");

    session.close();
    verify(context, never()).close();

    session.close();                        // idempotent
    verify(context, never()).close();
}
```

The same `@Nested` class also holds the verification of `close()`'s idempotence and of the `IllegalStateException` from
`submit()` after `close()`.

---

## Cautions with multiple sessions

### 1. Concurrency

- `OrcaAgentExecutor` is designed to be thread-safe, so one instance can run several sessions concurrently.
- `DefaultLiveSession` manages its internal state (`closed`, `busy`, the references to the running turn) with `volatile` / `Atomic*`,
  but it assumes **one turn at a time per handle**. As the interface javadoc states, implementations are not required to be thread-safe.
- What happens to input that arrives while a turn is running is decided by `offerAsync` — with a `MessageQueueManager` attached it
  queues the input and returns `QUEUED`, and when idle it runs immediately and returns `EXECUTED`. Without a queue it does not enqueue and
  falls back to running directly.
- If you need concurrent conversations, **open N sessions**, one each.

### 2. Reusing a SessionId

- Opening two live sessions on the same `SessionId` **at the same time** makes both modify the same transcript and the same session
  record competitively → **a data race becomes possible**. Sequential resumption (closing a handle and reopening it later) is a normal scenario.
- To keep exactly one holder per session in a multi-instance (scale-out) environment, use the `SessionLeaseStore`-based routing
  (`SessionRouter` / `LiveSessionOpener` in `aimon-session-routing`). The store implementation
  (`SessionRecordStore`) also has to handle concurrency (a distributed lock, optimistic concurrency).

### 3. Budget initialisation

- `LiveSessionOptions.budget` is the **session default** and is injected identically into every turn. To change it at runtime use
  `DefaultLiveSession.setOptions(options.withBudget(newBudget))`, and to revert use `clearBudgetOverride()`, which returns to the
  default the opener gave. Both paths are written back to the `SessionRecordStore`.
- `BudgetTracker` is reset **per turn** — `maxIterations` is a constraint applied within one submit, and it does not accumulate
  across the session. The session-wide totals are held separately by `SessionTotals`
  (`status().getSessionTotals()`).

### 4. Multi-instance ready

Following the multi-instance principle in `CLAUDE.md`:
- `LiveSession`, `LiveSessionFactory` and `LiveSessionOptions` **provide abstractions only**.
- The stores needed for cross-node coordination (`SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`) can change backend by
  swapping the implementation alone — `MongoSessionLeaseStore` (`aimon-session-mongodb`), `PostgresSessionLeaseStore`
  (`aimon-session-postgres`), `RedisSessionLeaseStore` (`aimon-session-redis`).
- By contrast, **the only in-tree implementation of `SessionRecordStore` is `InMemorySessionRecordStore`.** If session records
  must survive a restart, the host implements the interface itself and injects it. If fencing is needed too, wrap it with
  `new DefaultSessionStore(leaseStore, recordStore)` (one per session manager).

---

## A complete example

### Running a conversation session in a CLI REPL

```java
public class CliAgentRunner {
    private final LiveSessionFactory sessionFactory;

    public CliAgentRunner(LiveSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public void runInteractive(String agentRef) {
        SessionId sessionId = SessionId.generate();
        LiveSessionOptions options = LiveSessionOptions.builder()
            .budget(ExecutionBudget.builder()
                .maxIterations(20)
                .maxTokens(100_000)
                .maxWallClockDuration(Duration.ofMinutes(5))
                .build())
            .locale(Locale.getDefault())
            .sourceAgentId("aimon-cli")
            .build();

        try (LiveSession session = sessionFactory.open(sessionId, agentRef, options);
             Scanner scanner = new Scanner(System.in)) {

            System.out.println("Session started: " + session.getSessionId());

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine();
                if (input.isBlank() || "exit".equals(input)) break;

                AgentExecutionResult result = session.submit(input);
                if (result.isSuccess()) {
                    // getFinalAnswer()/getErrorMessage() are nullable Strings (not Optionals).
                    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(no answer)"));
                } else {
                    System.err.println("Error: " + Objects.requireNonNullElse(result.getErrorMessage(), "unknown"));
                }
            }
        }
        // session.close() has been called — only live-session-scoped resources are cleaned up
        // AgentRuntime (agent-scoped), schedulingEngine, executor, registry and sessionRecordStore stay alive
    }
}
```

### Injecting the session factory in Spring Boot

```java
@Configuration
public class AgentConfig {

    // AgentRuntimeRegistry is created outside the manager and injected through the builder — the manager does not own it.
    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean
    public OrcaAgentRuntimeManager agentRuntimeManager(
            OrcaAgentExecutor executor,
            ScheduledTaskManager scheduledTaskManager,
            AgentRuntimeRegistry agentRuntimeRegistry) {
        return OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .build();
    }

    // bootstrap: register the agent runtime once for each AgentBundle
    @Bean
    public ApplicationRunner registerAgentRuntimes(
            OrcaAgentRuntimeManager manager,
            List<AgentBundle> bundles,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return args -> {
            for (AgentBundle bundle : bundles) {
                manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);
                // → runtimeId = "agent:<name>"  (the simple case, with no discriminator)
            }
        };
    }

    @Bean
    public LiveSessionFactory liveSessionFactory(
            AgentRegistry agentRegistry,
            OrcaAgentRuntimeManager manager,
            OrcaAgentExecutor executor,
            SessionRecordStore sessionRecordStore,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        // the agent runtime is already in the registry, so it is reused when a session is opened
        return new LiveSessionFactory(agentRegistry,
                agent -> manager.getOrCreateRuntime(AgentBundle.builder().agent(agent).build(), fileSystem,
                        credentialStore),
                executor,
                sessionRecordStore);
    }
}
```

> `AgentRuntimeRegistry` is created **outside** `OrcaAgentRuntimeManager` and injected through the builder — the manager does
> not own it, and there is no getter to fetch it back out. To find a registered runtime again, use
> `manager.getRuntime(AgentRuntimeId)`.

---

## Checklist

What to check at a new LiveSession integration point:

### Required

- [ ] Is the handle always closed, with `try-with-resources` or `finally { session.close(); }`?
- [ ] Is `submit()` / `offerAsync()` never called after `close()`?
- [ ] Is the same `SessionId` never open in two live sessions at once?
- [ ] Is busyness judged by the `SubmitOutcome` of `offerAsync` rather than by `status()`?
- [ ] Does the call to `LiveSessionFactory.open()` handle `NullPointerException` (args) and
      `IllegalArgumentException` (unknown agent) appropriately?
- [ ] Has it been taken into account that `getFinalAnswer()` / `getErrorMessage()` are **nullable Strings**? (not `Optional`)

### Design

- [ ] Does the team know that the session does **not** close `AgentRuntime`, `SchedulingEngine`, `ScheduledTaskManager`,
      `OrcaAgentExecutor` or `SessionRecordStore`?
- [ ] Are the values that must survive a restart placed on the `SessionRecord` rather than on the handle?
- [ ] In a multi-instance deployment, does the `SessionRecordStore` implementation support concurrency?
- [ ] Does `LiveSessionOptions.budget` reflect each session's user intent? (the default is unlimited!)
- [ ] Is `sourceAgentId` set, contributing to observability?

### Tests

- [ ] Multi-turn continuity test: check that messages accumulate when the `sessionId` is reused
- [ ] Check the `IllegalStateException` from calling `submit()` after `close()`
- [ ] Verify that `close()` does not close the `AgentRuntime`, with Mockito's `verify(context, never()).close()`
- [ ] Check that `LiveSessionOptions.defaults()` returns `ExecutionBudget.unlimited()`
- [ ] With a `SessionRecordStore` attached, check the hydrate / flush round trip (see `DefaultLiveSessionPersistenceTest`)

---

## Related documents

- [LiveSession.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java)
- [DefaultLiveSession.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/DefaultLiveSession.java)
- [LiveSessionOptions.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSessionOptions.java)
- [LiveSessionFactory.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSessionFactory.java)
- [SubmitOutcome.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/SubmitOutcome.java)
- [SessionRecordStore.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordStore.java)
- [OrcaAgentExecutor.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java)
- [OrcaAgentRuntime.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java) — see its `close()` policy
- [scope-model.en.md](../../overview/scope-model.en.md) — the full rules for lifetime, ownership and teardown
- [CLAUDE.md](../../../CLAUDE.md) — the Scope & Scheduling Lifecycle principle
