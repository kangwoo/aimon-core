---
translated_from: docs/features/session/agent-session-tutorial.md
source_commit: a9821d44
---

# LiveSession tutorial (for beginners)

> A guide to **agent sessions** for people learning AIMON for the first time. It covers what they are for, why you need them and how to implement them step by step.

This document starts from a single question.

> What is the difference between "speaking to an agent once" and "having a conversation with an agent"?

`LiveSession` is exactly the component that bridges that gap.

> 📌 If you have seen the old names: this type used to be `AgentSession` and the conversation identifier used to be `ConversationId`. They are now `LiveSession` and `SessionId`, and the bare word `AgentSession` is **forbidden** as a type name — ArchUnit blocks it at build time. The old ↔ new mapping table is in [`CHANGELOG.md`](../../../CHANGELOG.md).

## Table of contents

1. [Big picture: what is a LiveSession](#1-big-picture-what-is-a-livesession)
2. [Why do you need one — the problems when there is no session](#2-why-do-you-need-one--the-problems-when-there-is-no-session)
3. [Understanding the four lifetimes (scopes)](#3-understanding-the-four-lifetimes-scopes)
4. [The core components at a glance](#4-the-core-components-at-a-glance)
5. [A step-by-step implementation guide](#5-a-step-by-step-implementation-guide)
   - [Step 1. Hello, LiveSession (one turn)](#step-1-hello-livesession-one-turn)
   - [Step 2. Building a multi-turn conversation](#step-2-building-a-multi-turn-conversation)
   - [Step 3. Applying options and a budget](#step-3-applying-options-and-a-budget)
   - [Step 4. Handling close() safely](#step-4-handling-close-safely)
   - [Step 5. An interactive CLI loop (putting it together)](#step-5-an-interactive-cli-loop-putting-it-together)
6. [Common mistakes and how to fix them](#6-common-mistakes-and-how-to-fix-them)
7. [Next steps](#7-next-steps)
8. [Glossary](#8-glossary)

---

## 1. Big picture: what is a LiveSession

`LiveSession` is the object that represents **a handle running a conversation against one session**.

By analogy:

| Analogy | AIMON component |
|---------|-----------------|
| The restaurant itself (from opening to closing time) | `OrcaAgentExecutor` (application) |
| The chef (holding the prep, the tools and the menu) | `AgentRuntime` (agent) |
| The order history left on the table | `SessionRecord` (session — persistent) |
| The waiter currently serving that table | **`LiveSession`** (live session — transient) |
| The act of a guest placing one order | `session.submit("...")` (one turn) |

The chef and the restaurant stay the same as guests come and go, and a single table sees one order (turn) after another. Even when the waiters change shifts the table's order history remains — which is precisely why `LiveSession` (the waiter) and `SessionRecord` (the history) are kept apart.

### The one-line definition

> **A LiveSession is a node-local facade, bound to exactly one `SessionId`, for running a multi-turn conversation safely.**

---

## 2. Why do you need one — the problems when there is no session

You can call an agent with nothing but `OrcaAgentExecutor`. Use it directly, though, and the caller has to **take care of the following every single time**:

1. where to keep the `SessionId` and how to reuse it
2. assembling the `OrcaAgentExecutionRequest` with a builder
3. injecting the `ExecutionBudget` (the iteration/token caps and so on) into every request
4. deciding, once everything is done, which resources to close and **which ones must never be closed**

```java
// without LiveSession — the caller has to think about everything
SessionId sessionId = SessionId.generate();          // where do I keep this?
ExecutionBudget budget = ExecutionBudget.builder().maxIterations(10).build();

OrcaAgentExecutionRequest req = OrcaAgentExecutionRequest.builder()
        .userInput("Hello")
        .sessionId(sessionId)     // injected by hand every time
        .budget(budget)           // injected by hand every time
        .build();
OrcaAgentExecutionResult r = executor.execute(agentRuntime, req);

// the second turn: fetch the same sessionId by hand again and feed the builder…
// at shutdown: do I close agentRuntime? do I close executor? unclear.
```

`LiveSession` turns all of this into something you **set once and forget**.

```java
try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    session.submit("Hello");
    session.submit("What did I just say?");   // sessionId/budget injected automatically, history wired up automatically
}
```

### In summary

| Responsibility | Without LiveSession | With LiveSession |
|----------------|---------------------|------------------|
| Keeping the `SessionId` | the caller does it by hand | the handle holds it once and reuses it |
| Assembling the request (`OrcaAgentExecutionRequest`) | the caller, every time | automatic inside `submit(input)` |
| The default `ExecutionBudget` | added to the builder every time | once in `LiveSessionOptions` |
| Cleaning up at the end | you have to work out what to close | one line of `try-with-resources` |

---

## 3. Understanding the four lifetimes (scopes)

To handle sessions properly you first have to understand **what lives how long**. AIMON divides its components into four lifetime tiers.

```
[ Application lifetime ] ←—— lives the longest
   SchedulingEngine, OrcaAgentExecutor, LlmClient, AgentRegistry, SessionRecordStore …
        │
        ├─[ Agent lifetime ] ←—— per (Agent, discriminator)
        │   AgentRuntime (= ToolRegistry + HookRegistry + MCP …)
        │      │
        │      ├─[ Session lifetime ] ←—— per SessionId, persistent
        │      │   SessionRecord, SessionTotals, budgetOverride, SessionTranscript
        │      │      │
        │      │      ├─[ Live session lifetime ] ←—— the shortest. node-local
        │      │      │   the LiveSession handle, the message queue, the event publisher
        │      │      │
        │      │      └─[ Live session lifetime ]
        │      │          (a handle that reopened the same session later)
        │      │
        │      └─[ Session lifetime ]
        │          (another session of the same agent)
        │
        └─[ Agent lifetime ]
            (another agent's runtime)
```

### The key intuitions

- **Application** components are created when the app starts and closed when it shuts down.
- **Agent** components are one per agent. Every session of the same agent **shares** them (rebuilding the tools, the hooks and the MCP connections for each session would be far too expensive).
- **Session** is the conversational unit the user sees, and it is **persistent**. It survives a restart of the app.
- **Live session** is the handle running that session "in this process, right now". It disappears when the process dies.

> 💡 One `SessionRecord` has **0..N** `LiveSession`s. There are zero when nobody is talking, and across idle eviction, restarts and moves between nodes several handles can serve the same session one after another. That is why **values that must survive a restart go on the record, not on the handle**.

### Which is what makes the close() rule simple

> **`LiveSession.close()` closes only what belongs to its own lifetime (the live session).**
> It never touches the AgentRuntime, the SchedulingEngine, the Executor or the SessionRecordStore.

What happens when you break this rule is covered in [Step 4](#step-4-handling-close-safely).

---

## 4. The core components at a glance

| Name | Role | Who creates it |
|------|------|----------------|
| `LiveSession` | the entry point for running a conversation (submit/offerAsync/close) | `LiveSessionFactory.open()` |
| `DefaultLiveSession` | the standard implementation of `LiveSession` | inside the factory |
| `LiveSessionOptions` | the session defaults (budget, locale, sourceAgentId) | the caller (a builder) |
| `LiveSessionFactory` | turns "an agent name" into "a live session" | once at bootstrap |
| `SessionId` | the session identifier. The key to history continuity | `SessionId.generate()` |
| `SessionRecordStore` | the store for session records (the transcript and the totals) | once at bootstrap |
| `AgentRuntime` | the agent's bundle of tools/hooks/MCP (a shared resource) | once at bootstrap |
| `OrcaAgentExecutor` | the engine that actually runs the ReAct loop | once at bootstrap |

> 📌 The phrase **once at bootstrap** keeps recurring. It means "create it exactly once when the app starts, and never create it again per session".

---

## 5. A step-by-step implementation guide

### Step 1. Hello, LiveSession (one turn)

Start from the smallest unit of behaviour.

```java
// assumption: factory was already created at bootstrap and injected here.
SessionId sessionId = SessionId.generate();

try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    AgentExecutionResult result = session.submit("Hello!");
    // getFinalAnswer() is a nullable String (not an Optional).
    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(no answer)"));
}
```

What this code does:

1. issues a new session id
2. opens a live session on the agent registered under the name `"default"`
3. runs one turn → prints the result
4. `try-with-resources` calls `session.close()` automatically

> **The point:** at this moment the `AgentRuntime` and the `OrcaAgentExecutor` must already be alive somewhere. The handle merely "uses" them; it does not "own" them.

---

### Step 2. Building a multi-turn conversation

Call `submit()` several times on the same handle and the conversation history carries over automatically.

```java
try (LiveSession session = factory.open(sessionId, "default", LiveSessionOptions.defaults())) {
    session.submit("My name is Alice.");
    AgentExecutionResult r = session.submit("What was my name?");
    // → the agent can answer "Alice".
}
```

### How is that possible?

```
session.submit(input)
   │
   ▼
OrcaAgentExecutionRequest (sessionId = sessionId, …)
   │
   ▼
OrcaAgentExecutor.execute(agentRuntime, request)
   │
   ├─ TranscriptManager.initialize(sessionId, systemPrompt)   ← loads the past messages
   ├─ runs the ReAct loop
   └─ TranscriptManager.save(transcriptBuffer)                ← saves the new messages
        └─ internally SessionRecordStore.mergeFromSnapshot(snapshot)
```

The `SessionId` plays the role of "the key to the history". The same handle always uses the same id, so the message history is continuous. Even when you close the handle and **reopen it later with the same `SessionId`**, the history carries over — that is what it means for a session to be persistent.

> ⚠️ **Careful**: opening two live sessions on the same `SessionId` **at the same time** lets the two handles modify the same history competitively and the data can end up tangled. Reopening sequentially (closing and resuming later) is a normal scenario.

---

### Step 3. Applying options and a budget

`LiveSessionOptions` configures the session's default behaviour.

```java
LiveSessionOptions options = LiveSessionOptions.builder()
        .budget(ExecutionBudget.builder()
                .maxIterations(20)
                .maxTokens(100_000)
                .maxWallClockDuration(Duration.ofMinutes(5))
                .build())
        .locale(Locale.KOREAN)
        .sourceAgentId("aimon-cli")   // an identifier for observability/auditing
        .build();

try (LiveSession session = factory.open(sessionId, "default", options)) {
    session.submit("Starting a long job…");
}
```

| Option | Meaning | Default when unspecified |
|--------|---------|--------------------------|
| `budget` | the ReAct iteration/token/time caps allowed in one turn | `ExecutionBudget.unlimited()` |
| `locale` | a localisation hint for the system prompt | none (`Optional.empty()`) |
| `sourceAgentId` | a label for the call's origin (CLI / web / sub-agent …) | none |

> 💡 **The budget resets per turn.** `maxIterations=20` means "up to 20 ReAct iterations within one `submit()`"; it does not accumulate across the session. The session-wide totals are held separately by `SessionTotals` and can be read with `session.status().getSessionTotals()`.

---

### Step 4. Handling close() safely

#### ✅ The right close — it closes only what is the handle's own

What `DefaultLiveSession.close()` actually cleans up:

- sets its own `closed` flag to `true`
- unregisters the message-queue listener it registered itself
- clears the references to the running turn (the interrupt coordinator / the budget tracker / the turn id)
- (optionally) fires the `OnSessionEnd` hook

#### ❌ The wrong close — it smashes the shared resources too

```java
// ⚠️ never write it like this
@Override
public void close() {
    agentRuntime.close();          // ❌ the same agent's other sessions are using this runtime!
    schedulingEngine.close();      // ❌ every other session's scheduled tasks vanish!
    orcaAgentExecutor.close();     // ❌ the whole application is paralysed
}
```

#### Why this matters

Imagine 100 sessions running concurrently on the same agent. If closing one of them also closes the `AgentRuntime`, **the other 99 sessions all die** (the MCP subprocesses go down with them). Closing the `SchedulingEngine` makes every scheduled task disappear.

> The rule: **the handle is a borrower**, not the owner of the shared resources. The owner is the bootstrap (the app's startup code).

#### A verification pattern

You can enforce this rule with a test.

```java
@Test
void closeDoesNotTouchSharedResources() {
    // wrap a real runtime in a spy to observe that close() is not propagated.
    OrcaAgentRuntime runtime = spy(createRuntime());

    DefaultLiveSession session = new DefaultLiveSession(SessionId.generate(), runtime, executor,
            LiveSessionOptions.defaults());
    session.submit("hi");
    session.close();

    verify(runtime, never()).close();   // the handle does not close the shared runtime
}
```

---

### Step 5. An interactive CLI loop (putting it together)

Let us combine everything so far into a small REPL.

```java
public class HelloAgentRepl {

    private final LiveSessionFactory sessionFactory;

    public HelloAgentRepl(LiveSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory);
    }

    public void run(String agentRef) {
        SessionId sessionId = SessionId.generate();
        LiveSessionOptions options = LiveSessionOptions.builder()
                .budget(ExecutionBudget.builder().maxIterations(20).build())
                .sourceAgentId("hello-cli")
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
                    System.out.println(Objects.requireNonNullElse(result.getFinalAnswer(), "(no answer)"));
                } else {
                    System.err.println("Error: " + Objects.requireNonNullElse(result.getErrorMessage(), "unknown"));
                }
            }
        }
        // try-with-resources calls session.close()
        // the shared resources (executor, schedulingEngine, agentRuntime, sessionRecordStore) stay alive
    }
}
```

The bootstrap side (a Spring `@Configuration`, an `AgentSetupFactory` …) does the following **once, at app startup**.

```java
// 1) register the per-agent AgentRuntime (only once!)
runtimeManager.getOrCreateRuntime(bundle, fileSystem, credentialStore);

// 2) create the session factory (the last argument is optional — omit it and the session totals are not persisted)
LiveSessionFactory factory = new LiveSessionFactory(
        agentRegistry,
        agent -> runtimeManager.getOrCreateRuntime(bundleFor(agent), fileSystem, credentialStore),
        executor,
        sessionRecordStore);

// 3) inject it into the REPL
new HelloAgentRepl(factory).run("default");
```

---

## 6. Common mistakes and how to fix them

| Symptom | Cause | Fix |
|---------|-------|-----|
| `IllegalStateException: LiveSession has already been closed` | `submit()` called after `close()` | reopen the handle with `open()`, or widen the `try-with-resources` scope |
| The second turn does not remember the first | a new handle is created with `factory.open()` on every turn → a different `SessionId` each time | call `submit()` repeatedly on the same handle, or reopen with the same `SessionId` |
| MCP/tools are re-initialised every time | an `AgentRuntime` is being created per session | create it exactly once at bootstrap with `getOrCreateRuntime` and reuse it |
| One session ended and every scheduled task disappeared | `close()` called `SchedulingEngine.close()` | the handle does not close shared resources — delete that code |
| Responses for the same user get mixed up | two live sessions were opened concurrently on the same `SessionId` | one handle at a time per session (with several nodes, use `SessionRouter`) |
| `IllegalArgumentException: No agent registered under name` | a typo in `agentRef`, or a missing registration | check the names registered in the `AgentRegistry` |
| The session totals go back to zero after a restart | `LiveSessionFactory` was built without a `SessionRecordStore` (the 3-arg form) | inject the store with the 4-arg constructor |
| Trying to use `.orElse(...)` on `getFinalAnswer()` fails to compile | this accessor returns a **nullable String**, not an `Optional` | handle it with `Objects.requireNonNullElse(...)` or similar |

---

## 7. Next steps

Once you have finished this tutorial and want to go deeper:

- **The development reference**: [`docs/features/session/agent-session-guide.en.md`](agent-session-guide.en.md) — the API contract, concurrency, and the cautions for multi-instance deployments
- **The full rules of the lifetime model**: [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md) — what to close, and when
- **The background of the lifetime model**: [`docs/design/agent-execution/agent-runtime-scope.md`](../../design/agent-execution/agent-runtime-scope.md) — why `AgentRuntime` is agent-scoped
- **Embedding in an app**: [`docs/getting-started/embedding-agent-in-application.en.md`](../../getting-started/embedding-agent-in-application.en.md)
- **Interrupt behaviour**: [`docs/features/agent-execution/interruptible-tools-guide.en.md`](../agent-execution/interruptible-tools-guide.en.md)
- **Deploying on the web**: [`docs/features/session/web-session-deployment-guide.en.md`](web-session-deployment-guide.en.md)
- **Streaming/queueing**: have a look at the signatures of `submitAsync`, `offerAsync` and `MessageQueueManager` ([`LiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java))

---

## 8. Glossary

| Term | One-line explanation |
|------|----------------------|
| **Agent** | a bundle of tools, prompts and policies. The agent's "identity" |
| **AgentRuntime** | the runtime environment an Agent needs in order to actually work (the tool registry, the MCP connections …). Agent-scoped, shared |
| **SessionRecord** | the session's persistent aggregate (the transcript, the totals, the budget override). Identified by `SessionId` |
| **LiveSession** | the handle running that session in this process right now. You only need to know submit / close |
| **SessionId** | the session identifier. The key to history continuity |
| **Turn** | one call to `submit()` = one turn (from the user input through to the agent's answer) |
| **TurnId** | an id for pointing at the running turn (used to target an interrupt). Not persisted |
| **The ReAct loop** | the repetition of "think → call a tool → observe → think". It can go round several times within one turn |
| **ExecutionBudget** | the iteration/token/time caps allowed in one turn |
| **SubmitOptions** | metadata that applies to one turn only (user information, system-prompt variables …) |
| **SubmitOutcome** | the result of `offerAsync` — whether it ran right away (`EXECUTED`) or went into the queue (`QUEUED`) |
| **OrcaAgentExecutor** | the engine that actually runs the ReAct loop. Application lifetime |
| **SessionRecordStore** | the store for session records. Application lifetime. The only in-tree implementation is `InMemorySessionRecordStore`; if you need persistence, implement the interface yourself and inject it |
| **SchedulingEngine** | the engine that runs scheduled tasks. Application lifetime. **A handle never closes it** |
| **Bootstrap** | the setup code that runs once at app startup. It prepares the AgentRuntime, the Executor, the factories and so on |

> 📌 The word "conversation" now means **the message exchange with the LLM** only (`getConversationHistory()`). It is not used to refer to a lifetime — that place belongs to `Session` / `LiveSession`.

---

> Finally, remember just one line.
>
> **A live session is the handle running one session right now. It closes what is its own and leaves what it borrowed alive.**
