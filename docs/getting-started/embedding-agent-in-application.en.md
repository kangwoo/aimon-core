---
translated_from: docs/getting-started/embedding-agent-in-application.md
source_commit: a9821d44
---

# Embedding an AIMON agent in your application

> How to run an AIMON agent directly inside your own application (web server / batch job / back-office
> tool). **The default path is `aimon-spring-boot-starter`** — three properties and turns are running.
> The two branches for hosts that cannot use the starter are
> [§14 Hosts that are not Spring](#14-hosts-that-are-not-spring--aimonstack) and
> [Appendix A Manual wiring](#appendix-a-manual-wiring--when-you-cannot-use-the-starter).

> The scope model in this guide follows [`docs/overview/scope-model.en.md`](../overview/scope-model.en.md) —
> `AgentRuntime` is agent-scoped and is **shared across sessions**. The design background is in
> [`agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md); the design background for
> the starter itself is in [`spring-boot-starter.md`](../design/integration/spring-boot-starter.md).

If all you need is a CLI conversation, read
[agent-session-guide.en.md](../features/session/agent-session-guide.en.md) (the `LiveSession` API) instead of
this document.

## Table of contents

1. [Which path to take](#1-which-path-to-take)
2. [5-minute start — the starter](#2-5-minute-start--the-starter)
3. [What the starter assembles for you](#3-what-the-starter-assembles-for-you)
4. [Properties](#4-properties)
5. [The four scopes](#5-the-four-scopes)
6. [Running a turn — `AimonSessions`](#6-running-a-turn--aimonsessions)
7. [Streaming event delivery](#7-streaming-event-delivery)
8. [ExecutionBudget policy](#8-executionbudget-policy)
9. [Multiple agents and tenants](#9-multiple-agents-and-tenants)
10. [Swapping parts — the extension points](#10-swapping-parts--the-extension-points)
11. [Multiple instances](#11-multiple-instances)
12. [Scheduling lifecycle — never touch it](#12-scheduling-lifecycle--never-touch-it)
13. [Observability and logging](#13-observability-and-logging)
14. [Hosts that are not Spring — `AimonStack`](#14-hosts-that-are-not-spring--aimonstack)
15. [Embedding checklist](#15-embedding-checklist)
- [Appendix A. Manual wiring — when you cannot use the starter](#appendix-a-manual-wiring--when-you-cannot-use-the-starter)
- [Appendix B. Old-name mapping](#appendix-b-old-name-mapping)

---

## 1. Which path to take

There are three branches, and **trying them from the top down is the right order**. The further down you
go, the more you have to assemble yourself.

| Path | When | What you do yourself |
|------|------|----------------------|
| **Starter** — `aimon-spring-boot-starter` | A Spring Boot 3 application | Three properties + `LlmClient` credentials. Auto-configuration does the rest |
| **Bootstrap** — `aimon-bootstrap` | A JVM host that is not Spring (Quarkus / Micronaut / plain `main` / batch) | Build an `AimonStackSpec` by hand and close the `AimonStack` (§14) |
| **Manual wiring** — `aimon-core` directly | When you have to change the shape of the assembly itself | Everything — executor, registries, factories, teardown order (Appendix A) |

The difference between the second and the third is **who knows the teardown order**. `AimonStack` closes
what it created in the reverse order of creation, and never closes what it borrowed. With manual wiring
that order is yours to keep.

> **Not on Maven Central yet.** `aimon-spring-boot-starter` and `aimon-bom` were created **after** the
> v0.2.0 tag, so they are not in the currently published artifacts; they ship from the next release.
> Until then, check out this repository, install with `./gradlew publishToMavenLocal`, and resolve from
> `mavenLocal()`.

---

## 2. 5-minute start — the starter

### 2.1 Dependencies

```kotlin
dependencies {
    // With the BOM you do not have to write versions for the coordinates below.
    implementation(platform("at.aimon.core:aimon-bom:<version>"))

    implementation("at.aimon.core:aimon-spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // The LLM vendor module is **yours to pick**. The starter knows both only as compileOnly, and the
    // slice wires whichever one is on the classpath via @ConditionalOnClass.
    implementation("at.aimon.core:aimon-llm-anthropic")   // or aimon-llm-openai
}
```

`aimon-spring-boot-starter` re-exports `aimon-core` / `aimon-bootstrap` / `aimon-session-routing` as
`api`, so your code can compile against `AgentExecutionResult` · `SessionId` · `LiveSessionOptions`
without declaring a separate dependency.

### 2.2 Three properties

```yaml
aimon:
  workspace:
    root: /var/lib/aimon          # the work tree the agent reads and writes (must be writable)
  llm:
    api-key: ${ANTHROPIC_API_KEY}
  agent-defaults:
    default-agent: ops            # reads agents/ops/agent.md from the classpath
```

**These three are the whole of what is required.** You do not need the `aimon.agents` map — when it is
empty the starter creates a single `AgentSpec.named(<default-agent>)`. That means "the bundle by that
name, exactly as it is, with nothing further to say". The agent definition comes not from properties but
from a **markdown bundle** on the classpath (`agents/<bundle>/agent.md`).

### 2.3 Inject one bean

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AimonSessions sessions;

    public AgentController(AimonSessions sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        // The SessionId comes from your domain — the framework does not provide a
        // (user, thread) → SessionId mapping. Calling again with the same id continues the conversation.
        AgentExecutionResult result = sessions.submit(SessionId.of(req.getThreadId()), req.getInput());

        // getFinalAnswer()/getErrorMessage() are nullable Strings, not Optional.
        return result.isSuccess()
                ? ChatResponse.ok(result.getFinalAnswer())
                : ChatResponse.error(result.getErrorMessage());
    }
}
```

A complete working application is in [`samples/aimon-sample-app`](../../samples/aimon-sample-app) —
running one real turn without credentials (`aimon.llm.provider: none` plus an `LlmClient` bean supplied
by the application) is that sample's reason to exist, and a `@Tag("packaging")` test keeps it behaving
the same way after it is packaged as a fat jar.

### 2.4 Supported deployment shapes — the executable jar

**The one deployment shape the first release supports is the executable jar.** The directory (exploded)
classpath used during development is supported alongside it — without that you could not launch the
application from an IDE.

| Shape | Supported | Verified by |
|-------|-----------|-------------|
| Spring Boot executable jar — nested loader (`jar:nested:`, Boot 3.2+) | ✅ | `FatJarPackagingTest` launches a real JVM and checks |
| Spring Boot executable jar — classic loader (`jar:file:`) | ✅ | the same test repeats the same assertions on a second jar |
| Directory (exploded) classpath — development and IDE | ✅ | the same test checks with a third process |
| A WAR deployed into a servlet container | ❌ | — |
| `jlink` runtime image (`jrt:`) | ❌ | — |
| GraalVM native image | ❌ | — |

There is **one** thing that decides support. Skill and agent bundles are resource **directories** on the
classpath, and the JDK has no portable way to list one. That is why `ClasspathResourceTreeWalker` splits
the work — `file:` by walking the directory, `jar:` by enumerating entries through `JarURLConnection`.
When a container uses its own protocol (`vfs:` · `wsjar:` …) or the URL arrives as `jrt:`, there is no way
to enumerate; and a native image has no classpath to enumerate at all.

It is worth knowing **what an unsupported shape looks like** — you get WARN logs rather than an
exception, and an agent boots **with no skills at all**. Startup succeeds and turns run, only the skills
are missing, which is easy to mistake for a prompt problem. If you need one of these shapes it means
there is no way today, and that line in the log is the only clue.

There are two lines, and neither of them **says the files are missing**.

```text
WARN  ClasspathResourceTreeWalker - Cannot enumerate classpath resource 'agents/default/skills/commit':
      unsupported URL protocol 'vfs'
WARN  BundledSkillMaterializer   - Bundled skill 'commit' cannot be materialized from
      'agents/default/skills/commit': class path layout 'vfs' cannot be enumerated (its files may well be
      present). See the supported deployment shapes in docs/getting-started/embedding-agent-in-application.md §2.4
```

For a while the second line read `has no files under ...; skipping` — because the walker answered
"cannot walk" and "is empty" with the same empty list, and the log claimed absence while the files sat
right there inside the archive. Now `ResourceTreeListing` carries the two events separately, so the
message no longer contradicts the facts.

> This is not "we have not tried yet" but a **scope decision** (2026-08-05). We will reopen it if the
> need actually appears — see B-19 (scope decision, closed) and B-29 (message split, closed) in
> [`docs/backlog/spring-boot-starter-open-items.md`](../backlog/spring-boot-starter-open-items.md).

---

## 3. What the starter assembles for you

### 3.1 The beans you inject

| Bean | What |
|------|------|
| **`AimonSessions`** | The facade that runs turns. **Most applications use only this one** (§6) |
| `AimonAgents` | Which agents exist in this deployment, and how to invalidate a tenant runtime (§9) |
| `AimonStack` | Everything underneath — `sessionRouter()`, `agentExecutor()`, `sessionRecordStore()`, `agentRuntimes()`, `health()`, `degradations()` … |

`AimonStack` is registered as `@Bean(destroyMethod = "close")`. **When the context closes, the stack
closes, and at that point only what the stack created is closed, in the reverse order of creation.**
Everything you pulled out through an accessor is **borrowed**, so you must not close it.

### 3.2 Auto-configuration slices

`AimonAutoConfiguration` stands the core up and the other seven each own one axis. All of them are
listed in `META-INF/spring/…AutoConfiguration.imports`.

| Slice | Turns on when | What it wires |
|-------|---------------|---------------|
| `AimonAutoConfiguration` | always (with disabled substitute beans when `aimon.enabled=false`) | `AimonStack`, `AimonSessions`, `AimonAgents`, the two-phase `SmartLifecycle`, `PendingTurnRegistry` |
| `AimonLlmAutoConfiguration` | `@ConditionalOnClass` (a vendor module) + `aimon.llm.provider` | `LlmClient` |
| `AimonFileSystemAutoConfiguration` | always | `FileSystemSpec` — a local tree under the workspace root by default |
| `AimonSessionAutoConfiguration` | always | `SessionSpec` — `aimon.session.store` / `mode` |
| `AimonSchedulingAutoConfiguration` | `aimon.scheduling.backend` | `SchedulingSpec`, the nested Quartz configuration |
| `AimonObservabilityAutoConfiguration` | its three switches are **independent** | tracing (property) · Actuator `HealthIndicator` (class) · Micrometer gauges (a `MeterRegistry` **bean**) |
| `AimonKnowledgeAutoConfiguration` | `aimon.knowledge.backend` | `KnowledgeStore` + `KnowledgeContribution` |
| `AimonMemoryAutoConfiguration` | `aimon.memory.backend` | `RepresentationStore` / `ObservationStore` + `MemoryContribution` |

IMPORTANT: the last two slices **refuse to start when a bean and its selector disagree**. Declare a
`KnowledgeStore` bean while `knowledge.backend: none` and — because no agent would be given a tool that
reaches that store — startup fails with a message saying "switch it to `supplied` or drop the bean". The
memory side is the same. It means no setting is ever silently ignored.

### 3.3 Startup and shutdown order

There are two `SmartLifecycle` phases, and they are chosen to **sandwich Boot's web-server phase from
both sides**.

```
AimonRuntimeLifecycle       MAX_VALUE - 4096
WebServerStartStopLifecycle MAX_VALUE - 2048   (Boot)
AimonSchedulingLifecycle    MAX_VALUE
```

So **the runtime stands up first → the web server opens → scheduling starts last**, and shutdown is
exactly the reverse. There is no moment where the runtime disappears while cron is firing, or where the
server accepts requests before the runtime exists. (With the same phase they would end up in one
`LifecycleGroup`, ordered by bean-factory iteration, and "runtime before socket" would be a coincidence
rather than a guarantee. That is why the gap is not 2048.)

The actual release of resources is done not by `stop()` but by **`AimonStack.close()`** — Spring destroys
beans after it stops lifecycles, so tearing the runtime down in `stop()` would pull the ground out from
under the stack while it is draining sessions. That is why the knob for shortening shutdown is
`aimon.session.shutdown-drain-timeout` and **not** `spring.lifecycle.timeout-per-shutdown-phase`.

### 3.4 Traps the starter has already closed

The two things this document used to warn loudly about, back in the manual-wiring era, **do not happen on
the starter path**.

- **`MessageQueueManager` / `HookExecutionManager` arriving as `null`** — the starter's
  `StackLiveSessionOpener` uses the 7-argument `DefaultLiveSession` constructor. Both the queue and the
  hooks really are injected. (This was only ever a problem on the path that calls
  `LiveSessionFactory.open` directly — see Appendix A.)
- **`SchedulingEngine` and `OrcaAgentRuntimeManager` each holding their own `AgentRuntimeRegistry`** —
  the stack owns the registry and hands the same instance to both.

### 3.5 fail-fast and degradation

The default for `aimon.fail-fast` is **`false`**. That is intentional, not an oversight — three of the
documented server defaults **deliberately** register reduced capability: the in-memory session store, the
deny-all skill approval channel, and disabled scheduling. Turn it to `true` and those three fail startup.

Reduced capability does not vanish; it is **reported as a degradation**. At startup the starter leaves one
line first — `AIMON started with reduced capability: …`. To read it programmatically:

```java
stack.degradations().asList()
        .forEach(d -> log.warn("degraded: {} — {}", d.getCapability(), d.getConsequence()));
```

With Actuator the same list appears as the `degradations` detail of `/actuator/health` (§13).

---

## 4. Properties

`AimonProperties` defines the full tree, and IDE auto-completion metadata is generated alongside it. Only
the frequently used ones are listed here.

```yaml
aimon:
  enabled: true                     # false → disabled substitute beans (AimonDisabledException on call)
  fail-fast: false                  # true → startup fails if there is even one degradation

  workspace:
    root: /var/lib/aimon            # required
    ensure-writable: true

  agent-defaults:
    default-agent: ops              # required when agents is empty or declares two or more
  agents:                           # optional — when empty, just the one default-agent
    ops:
      bundle: ops                   # omit and it equals the map key
    support:
      bundle: ops                   # the same bundle can run under a different ref

  agent-runtime:                    # the tenant runtime cache
    eviction: idle                  # idle (default) | never
    idle-ttl: 30m
    sweep-interval: 5m
    max-entries: 100

  llm:
    provider: anthropic             # anthropic (default) | openai | none
    api-key: ${ANTHROPIC_API_KEY}
    timeout: 60s

  credentials:                      # optional — values a tool asks for as 'profile.field' (§10)
    jira:
      username: svc-aimon
      password: ${JIRA_PASSWORD}

  budget:                           # the defaults are already finite (20 / 100000 / 120s) — narrow here (§8)
    max-iterations: 20
    max-tokens: 50000
    max-wall-clock: 60s

  session:
    store: in-memory                # in-memory (default) | postgres | mongodb | redis
    mode: single-node               # single-node (default) | distributed
    node-id: ${HOSTNAME}
    shutdown-drain-timeout: 30s
    cache:
      max-entries: 1000
      idle-ttl: 30m

  skill:
    approval:
      mode: deny                    # deny (default) | allow-list | suspend | channel
      allow: []
      pending-turn-ttl: 10m

  scheduling:
    backend: none                   # none (default) | in-memory | quartz
    auto-startup: true
    quartz:
      use-application-scheduler: true   # the default — borrow the application's Quartz scheduler
      instance-name: aimon              # the four below only matter when use-application-scheduler=false
      thread-count: 4
      daemon-threads: true
      wait-for-jobs-on-shutdown: false

  tracing:
    enabled: false
    payload-capture: none           # none (default) | full
    max-chars: 2000
    max-spans: 500

  tools:
    bash:
      enabled: false                # the default — on a server the shell must be turned on explicitly

  knowledge:
    backend: none                   # none (default) | keyword | supplied
    chunk-size: 1000
    chunk-overlap: 100
  memory:
    backend: none                   # none (default) | in-memory | supplied
    redaction: default              # default (default) | strict | none | supplied
```

- The selector values (`store`, `mode`, `backend`, `provider`, `eviction` …) all bind to enums, so **a
  typo fails at startup with a message you can read**.
- `default-agent` may be omitted only when `aimon.agents` declares **exactly one** entry. With two or
  more, startup fails — because picking the first entry of the map would mean that **traffic moves to a
  different agent without a property change** the day the YAML order shifts or a profile contributes one
  more agent.
- `aimon.llm.provider: none` means "the starter does not create an `LlmClient`". If you register an
  `LlmClient` bean yourself, that one is used (this is the shape the sample app takes). If nobody
  registers one, the starter's fallback remains, and it fails on call **naming the properties you have to
  fill in**.
- **The `Bash` tool is off by default** — a point where this differs from the CLI. The CLI simply hands
  over the shell because a human approves each command; a server process has nobody to ask, so arbitrary
  command execution has to be turned on explicitly.
- `aimon.credentials.<profile>.<field>` stands up the values a tool asks for under a name like
  `credential_ref: 'jira.password'`. Profile names and field names **may not contain a dot** — the
  reference syntax allows exactly one dot, so a name containing one would be unreachable after binding,
  and that is why it is rejected at startup. A profile with no fields at all is rejected for the same
  reason. The values are masked in `/env` and `/configprops` regardless of the `show-values` setting
  (§13.5). The tree is plural `credentials` because of that masking rather than because of the syntax —
  what Boot's word list catches is `credentials`, and since the rule looks at the whole key, one prefix
  covers **any leaf name** underneath it.
- `supplied` under `knowledge` / `memory` means "**you declare that bean and the starter only connects the
  tools to it**". Spring made it, so Spring closes it, and the stack merely borrows. The same reason is
  why `knowledge.backend` **deliberately has no OpenSearch value** — `aimon-knowledge-opensearch` exists
  and works, but turning TLS verification off installs a trust-all `X509TrustManager`, and making it a
  starter constant would hide that decision behind one line of properties. An application that wants it
  declares one `@Bean`, and the security decision stays where a person reads it.

---

## 5. The four scopes

Whether you use the starter or wire things by hand, **the lifetime rules are the same**. The full rules
are governed by [`docs/overview/scope-model.en.md`](../overview/scope-model.en.md) §1.

```
┌──────────────────────────────────────────────────────────────┐
│                    Your Application                          │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐     │
│  │ Live session scope (node-local, transient)          │     │
│  │  LiveSession — the handle that runs turns           │     │
│  │  With the starter, SessionRouter opens, caches,     │     │
│  │  and closes it                                      │     │
│  └─────────────────────────────────────────────────────┘     │
│                         │ borrowed reference — never close   │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐     │
│  │ Session scope (persistent, keyed by SessionId)      │     │
│  │  SessionRecord — message history, SessionTotals,    │     │
│  │                  budgetOverride                     │     │
│  │  1 SessionRecord : 0..N LiveSession (asymmetric)    │     │
│  └─────────────────────────────────────────────────────┘     │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐     │
│  │ Agent scope (per (Agent, discriminator))            │     │
│  │  AgentRuntimeId = "agent:<name>[:<disc>]"           │     │
│  │  OrcaAgentRuntime — ToolRegistry, HookRegistry,     │     │
│  │  SkillRegistry, SubagentRegistry, CommandRegistry,  │     │
│  │  CompactionEngine/Guard, McpClientManager           │     │
│  └─────────────────────────────────────────────────────┘     │
│                         │                                    │
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐     │
│  │ Application scope (singletons)                      │     │
│  │  OrcaAgentExecutor, LlmClient, SchedulingEngine,    │     │
│  │  AgentRuntimeRegistry, MessageQueueManager,         │     │
│  │  VirtualFileSystem, SessionRecordStore,             │     │
│  │  SessionLeaseStore, KnowledgeStore, CredentialStore │     │
│  │  ← with the starter, AimonStack owns them all       │     │
│  └─────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

| Scope | Lifetime | Examples |
|-------|----------|----------|
| **Application** | process start ~ shutdown | `OrcaAgentExecutor`, `LlmClient`, `SchedulingEngine`, `AgentRuntimeRegistry`, `MessageQueueManager`, `VirtualFileSystem`, `SessionRecordStore`, `SessionLeaseStore` |
| **Agent** | agent registration ~ agent removal or application shutdown | `OrcaAgentRuntime` (ToolRegistry, HookRegistry, SkillRegistry, MCP clients) |
| **Session** | as long as the session exists — **persistent** | `SessionId`, message history, `SessionTotals`, `budgetOverride` (all held by `SessionRecordStore`) |
| **Live session** | handle open ~ `close()` — node-local | `LiveSession`, the message queue listener, the event publisher |

IMPORTANT: **Session and live session are different lifetimes.** One `SessionId` may have zero live
handles (nobody is talking right now), or several handles may serve it in sequence over time (idle
expiry, process restart, node handoff). **Put values that must survive a restart in the
`SessionRecordStore`, and put only values that may die with the handle in the `LiveSession`.** Miss this
distinction and the accumulated token and turn counts silently go back to zero after a restart.

> Getting the scope wrong produces one of three bugs:
> - Closing `AgentRuntime` or the scheduling engine inside session `close()` → **destroys another session
>   of the same agent, or its scheduled tasks**
> - Closing only sessions at application shutdown and leaving the executor alone → **an LLM connection
>   leaked until the process exits**
> - Opening a session without a `SessionRecordStore` → history and totals **vanish with the handle** (not
>   restorable after a restart)

With the starter all three are handled by the stack. The reason you still have to know the rules is that
**closing an object you pulled out of the stack reproduces exactly the same bugs**.

---

## 6. Running a turn — `AimonSessions`

`AimonSessions` is **the only bean an application is expected to inject**. Everything under it
(`AimonStack`, `SessionRouter`, `LiveSession`) is public too and can be used as-is, but a host that only
wants to "send a message and get an answer" has no reason to learn that vocabulary.

```java
// Synchronous — runs one turn and blocks until it finishes
AgentExecutionResult submit(SessionId sessionId, String input);
AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options);

// Asynchronous — returns where it went along with how to wait for it
SubmitDisposition submitAsync(SessionId sessionId, String input);
SubmitDisposition submitAsync(SubmitRequest request);

// When you want to change exactly one field
SubmitRequest.Builder newRequest(SessionId sessionId, String input);

Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId);
void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason);
void release(SessionId sessionId);
```

### 6.1 What this facade fills in

`SessionRouter.submit(SubmitRequest)` demands an `agentRef` and an `initiator`, and it **quietly accepts**
`LiveSessionOptions.defaults()` — whose default budget is **unlimited**. Call the router directly while
building the request by hand and the moment you forget to attach a budget, an unbounded turn runs. That
is precisely the failure `aimon.budget.*` was meant to prevent.

`AimonSessions` fills **the configured agent · the configured default budget · a system initiator** into
every request it handles.

### 6.2 The escape hatch is not a hole

`newRequest(...)` returns a builder with **those defaults already filled in**. Override the one field you
need (idempotency key, priority, the real end-user `Principal`) and keep the rest — starting from an
empty `SubmitRequest.builder()` puts the budget back to unlimited and makes you retype `agentRef` and
`initiator`.

```java
SubmitDisposition disposition = sessions.submitAsync(
        sessions.newRequest(sessionId, input)
                .initiator(Principal.user(currentUser.getId()))   // replace only this one field
                .idempotencyKey(req.getRequestId())
                .build());
```

`submitAsync(SubmitRequest)` takes a finished request as-is, and it is the **single primitive** every
other submit method delegates to.

### 6.3 Reading `SubmitDisposition`

```java
SubmitDisposition d = sessions.submitAsync(sessionId, input);
switch (d.getKind()) {
    case EXECUTED_LOCALLY -> log.debug("this node took the session lock");
    case FORWARDED -> log.debug("another node is the holder — put in its inbox: {}", d.getInboxId().orElseThrow());
}
d.getFuture().whenComplete((result, err) -> render(result, err));
```

The two values are **this-node/other-node, not busy/queued**. In a single-node deployment it is always
`EXECUTED_LOCALLY`, and either way you wait for the result with `getFuture()`.

### 6.4 An interrupt targets a **turn**, not a session

```java
// InterruptReason is an enum. A cancel button pressed by a user is USER_SIGINT
// ("Ctrl+C or its equivalent" — not CLI-only).
sessions.interrupt(sessionId, disposition.getTurnId(), InterruptReason.USER_SIGINT);
```

By the time a user's cancel reaches the agent, that turn may already have finished and **the next one may
have started**. An interrupt with no address kills that next turn. The `TurnId` comes from
`SubmitDisposition.getTurnId()`, and if it does not match, the call is a quiet no-op.

The reason is not used only for observability — it carries into the result's `CompletionReason`, so how
you explain the interruption to the user can branch on `result.getCompletionReason()`.

### 6.5 `release` is not delete

`release(sessionId)` drops **only the node-local handle** and leaves the stored history — submit again
with the same id and the conversation continues. Real deletion is
`SessionRouter.deleteSession(sessionId)`, and that one takes the session lock first.

---

## 7. Streaming event delivery

`submit` returns only the final result. To stream the intermediate events of the ReAct loop (iteration
start/end, tool calls, assistant text deltas, the final message) over SSE or WebSocket, subscribe to
`events(sessionId)`.

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String threadId, @RequestParam String input) {
    SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
    SessionId sessionId = SessionId.of(threadId);

    sessions.events(sessionId).subscribe(new Flow.Subscriber<>() {
        private Flow.Subscription subscription;

        @Override public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentExecutionEvent event) {
            try {
                // AgentExecutionEvent is sealed, so the kind is identified by the simple class name
                // (IterationStarted, ToolUseStarted, ToolResultReady, AssistantMessageReceived,
                //  ExecutionCompleted, ...).
                emitter.send(SseEmitter.event().name(event.getClass().getSimpleName()).data(event));
            } catch (IOException e) {
                subscription.cancel();
            }
        }

        @Override public void onError(Throwable t) { emitter.completeWithError(t); }
        @Override public void onComplete() { emitter.complete(); }
    });

    sessions.submitAsync(sessionId, input).getFuture()
            .whenComplete((r, err) -> { if (err != null) emitter.completeWithError(err); else emitter.complete(); });

    return emitter;
}
```

- The publisher from `events(...)` can be **subscribed to by several observers at once**.
- Subscribers are a **side channel** — an exception thrown there does not cancel the turn. Design so that
  an observability failure does not break the user's answer.
- There are **15** event types in `at.aimon.core.agent.stream` (it is sealed, so this is all of them) —
  `IterationStarted` / `IterationCompleted` / `AssistantMessageReceived` / `AssistantTextDelta` /
  `AssistantTextStreamReset` / `AssistantTextStreamCompleted` / `ToolUseStarted` / `ToolResultReady` /
  `SubagentTaskCompleted` / `SkillTurnSuspendedEvent` / `CompactBoundary` / `InterruptedAt` / `RejectedAt` /
  `ExecutionCompleted` / `ExecutionError`. `getIteration()` is a `final` method on the sealed base class,
  so it can be read from every subtype.

### 7.1 The honest story about the queue

The router path (`AimonSessions.submitAsync`) **always uses `submitAsync` and never `offerAsync`.**
`offerAsync` returns a `SubmitOutcome` with no result stage when the input is simply queued, and then the
submitting node could never close the future it handed its caller. So on the router path **priority
decides only the queue *position*.**

Mid-turn injection (slipping a message into a turn already in progress) is **exclusive to the local
`offerAsync` path**. Callers on that path know they are calling something with no result stage. The
executor-side drain (`OrcaAgentExecutor.injectQueuedMessages`) scopes by `AgentRuntimeId`, collects the
`NEXT` tier and above, and leaves the `LATER` tier — **draining at the end of a turn is still the host's
responsibility (CQ-05)**. For details see
[command-queue-guide.en.md](../features/agent-execution/command-queue-guide.en.md).

---

## 8. ExecutionBudget policy

```yaml
aimon:
  budget:
    max-iterations: 20
    max-tokens: 50000
    max-wall-clock: 60s
```

These values become the **default budget `AimonSessions` attaches to every request**. To change it per
request, use the explicit overload.

```java
LiveSessionOptions tight = LiveSessionOptions.builder()
        .budget(ExecutionBudget.builder()
                .maxIterations(clamp(req.getMaxIterations(), 1, 50))
                .maxTokens(pricingPlanCap(principal))
                .maxWallClockDuration(Duration.ofSeconds(req.getTimeoutSec()))
                .build())
        .locale(Locale.forLanguageTag(req.getLocale()))
        .sourceAgentId("api:/agent/chat")
        .build();

sessions.submit(sessionId, "ops", req.getInput(), tight);
```

IMPORTANT: a budget applies **per execution (a turn or a subagent fork)** — not per session. The totals
for a whole session are held separately by `SessionTotals` (a side field of `SessionRecord`), so if you
need a cumulative token cap, read that value and decide **in your application layer**
([llm-usage-metering.en.md](../features/llm/llm-usage-metering.en.md)).

IMPORTANT: **the starter's default budget is finite** — `max-iterations: 20`, `max-tokens: 100000`,
`max-wall-clock: 120s`. It is **deliberately different** from the framework's own default of
`ExecutionBudget.unlimited()`. An unbounded ReAct loop is a defensible default in a CLI where a human is
watching and can press Ctrl-C; the same loop in a server process, inside a request handler nobody is
watching, is not. The YAML above is an example of **narrowing** that default, not of turning on a cap
that was not there.

- Each field applies **only when it is set**. Giving an empty value in a profile is not a way to **remove**
  a cap but a way to fall back to the default — to widen a cap, state a larger value.
- A budget changed at runtime is **persisted** as `budgetOverride` on the `SessionRecord`, so it survives
  reopening the handle (to undo it you must clear it explicitly — `clearBudgetOverride()` in Appendix A).

---

## 9. Multiple agents and tenants

### 9.1 Several agents

```yaml
aimon:
  agent-defaults:
    default-agent: ops
  agents:
    ops:
      bundle: ops
    support:
      bundle: ops        # same bundle, different ref — ref and bundle are separate
      properties:        # the starter does not read these — they are passed straight to the customizer
        escalation-channel: "#support-oncall"
```

**The map key is the ref** — the name a submit routes on and the identity everything else joins by.
`bundle` is the directory to read from the classpath (`agents/<bundle>/agent.md`) and equals the key when
omitted. The two are separate because sometimes you want **to run one definition under two refs** — build
`ops` and `ops-readonly` from a single bundle and separate them with a customizer and `properties`.

The starter **does not read a single character of `properties`.** They are simply passed to the customizer
via `AgentDescriptor.getProperties()` — a place to branch on deployment facts such as region, team, or
escalation channel, so that you do not have to build a second configuration tree keyed by agent ref and
keep it in sync by hand.

IMPORTANT: this tree has **no per-agent budget and no per-agent tool on/off.** All `AgentEntry` holds is
`bundle` and `properties`. Budgets are split by the global `aimon.budget.*` default and the
`LiveSessionOptions` at call time (§8); tool differences are split by
`AimonAgentCustomizer.supports(...)` (§10).

```java
sessions.submit(sessionId, "support", input, null);   // routed by agentRef, options at their defaults
```

`AimonAgents.list()` returns the agents **configured** in this deployment — a finite list, and it comes
from configuration.

```java
for (AgentDescriptor agent : agents.list()) {
    log.info("{} → bundle={} runtimeId={}", agent.getAgentRef(), agent.getBundleName(), agent.getRuntimeId());
}
```

### 9.2 Tenants

To split one agent definition per tenant, attach a `contextDiscriminator` — the `AgentRuntimeId` becomes
`agent:<name>:<discriminator>` and the runtimes separate.

```java
sessions.submitAsync(
        sessions.newRequest(sessionId, input)
                .agentRef("ops")
                .contextDiscriminator(tenantId)
                .build());
```

The number of tenant runtimes grows with traffic, so `AimonAgents.list()` does **not** report them — that
is a capacity fact, not a configuration fact. For the current holdings look at
`AimonStack.agentRuntimes()` or the gauges in §13. The cap and the eviction are decided by
`aimon.agent-runtime.*`.

### 9.3 Invalidation

```java
agents.invalidate("ops", tenantId);   // only this tenant runtime
agents.invalidate("ops");             // every tenant runtime for this ref
```

Invalidation is for **when the grounds on which a runtime was built have changed outside** (a tenant
rotated a key, an operator revoked an integration). It is not a way to reclaim memory — idle runtimes are
already collected by `aimon.agent-runtime.*`, and calling it here just makes the next request pay for
reassembly.

**It does not cut off a turn in progress.** The runtime is deregistered immediately so the next submit
builds a new one, and it closes when the last holder lets go. The startup runtimes of declared agents are
not subject to invalidation, and calling their names is a no-op rather than an error.

---

## 10. Swapping parts — the extension points

**Four extension points are used from day one**, and declaring any of them as a bean is enough for the
starter to pick it up.

| Bean | How it is collected | What it changes |
|------|---------------------|-----------------|
| `AimonAgentCustomizer` | **N** of them, collected via `ObjectProvider<>` | per-agent tools, commands, hooks |
| `VirtualFileSystem` **or** `VirtualFileSystemFactory` | **one of the two only** — 1 | the global file backend / a per-agent or per-tenant file backend (default: a local subtree of the workspace) |
| `CredentialStore` **or** `CredentialStoreFactory` | **one of the two only** — 1 | credentials (default: no implementation). **The only line that can also be stood up from properties** — see below |
| `SkillApprovalChannel` **or** `SkillApprovalChannelFactory` | **one of the two only** — 1 | the channel that asks a human for skill approval (when `approval.mode: channel`) |

IMPORTANT: in the last three rows the **singular bean and the factory are alternatives to each other**.
Define both and the starter cannot choose, so it refuses at startup with an **`IllegalStateException`**
(it does not quietly pick one). If you need to split per tenant, define the factory; if one global is
enough, define the singular bean — and leave the other one out.

Credentials alone have a third path — `aimon.credentials.*` (§4). Of the four, this is the only one that
is **data rather than code**: for the other three the answer is what you implemented, but which profiles
exist is a per-deployment value, and writing it as a `@Bean` makes that list a compile-time decision.
Keeping it in properties lets `${JIRA_PASSWORD}` pull straight from environment variables, Vault, or a
config server — which is what Boot already does.

IMPORTANT: these three paths are **alternatives to each other** as well, so filling in more than one is
refused — here we did not let a bean quietly beat a property. What would disappear is not a default you
could retype but a **secret**, and dropping it shows up not at startup but hours later as some tool's
"credential not found". At that moment the configuration file still reads as though the value is there.

`LlmClient` · `SessionSpec` · `FileSystemSpec` · `SchedulingSpec` · `KnowledgeStore` ·
`RepresentationStore` · `ObservationStore` · `TaskSchedulerFactory` are all `@ConditionalOnMissingBean`
too, so **if you define one, you win.** Defining a `*Spec` yourself means skipping the entire property
tree, which makes it a last resort — what is expressible as properties is better left as properties.

```java
@Component
public class TicketToolsCustomizer implements AimonAgentCustomizer {

    private final TicketApi api;   // your domain service

    public TicketToolsCustomizer(TicketApi api) {
        this.api = Objects.requireNonNull(api);
    }

    @Override
    public boolean supports(AgentDescriptor agent) {
        return "ops".equals(agent.getAgentRef());
    }

    @Override
    public List<OrcaToolProvider> toolProviders(AgentDescriptor agent) {
        // A discriminator means this is a tenant runtime — build the tools with that tenant's credentials.
        return List.of(new TicketToolProvider(api, agent.getDiscriminator().orElse(null)));
    }

    @Override
    public void registerHooks(AgentDescriptor agent, HookRegistry hooks) {
        // The registry is addressed by an event type token — there is no register(hook) overload.
        hooks.register(HookEventType.PRE_TOOL, new AuditHook());
    }

    @Override
    public int getOrder() {
        return 0;   // List<> injection order cannot be trusted, so state it when order matters
    }
}
```

IMPORTANT: customizers are **also called when a tenant runtime is built**. Once at startup for
`agent:ops`, and again for `agent:ops:acme` the first time that tenant appears — keeping the two runtimes
from having different tools is this extension point's reason to exist. So `supports(...)` may be called
**on request threads, concurrently, for several tenants**. Your implementation must be **thread-safe**,
and must not assume it sees each agent only once.

There are two further guarantees.

- **Hooks are registered before the runtime becomes reachable.** `registerHooks` is called after the
  runtime is fully built but while nobody can yet pick it up — no turn can start in between and be missed
  by a hook.
- **Contributions only add.** The providers you return are **appended after** the starter's default list.
  A customizer cannot take away a tool another customizer added. The only way to *not* give an agent a
  tool is to filter that agent out in `supports(...)`.

### 10.1 Deliberately excluded

- **HTTP endpoints** — authentication and routing are the application's domain. The starter creates no
  controllers.
- **Writing agent definitions as properties** — an agent is a markdown bundle. What `aimon.agents.<name>.*`
  holds is **wiring values** (bundle name, budget, tool on/off), not a prompt.
- **Agent CRUD** — managing agents in a database is the application's domain. The starter gives only one
  inlet, `AimonAgents.invalidate`.

---

## 11. Multiple instances

**Code that uses `AimonSessions` is identical on a single node and in a distributed deployment.** What
changes is only the properties.

```yaml
aimon:
  session:
    mode: distributed
    store: postgres           # postgres | mongodb | redis
    node-id: ${HOSTNAME}
```

What happens then:

- **`SessionLeaseStore`** elects, by lease, **exactly one node** to serve a given `SessionId`.
- **`SessionRecordStore`** goes to a distributed backend. Three implementations are in-tree —
  `PostgresSessionRecordStore` (`aimon-session-postgres`), `MongoSessionRecordStore`
  (`aimon-session-mongodb`), `RedisSessionRecordStore` (`aimon-session-redis`). (You have to put the
  module on your dependencies.)
- **`SessionStore`** ties the two together and **fences** them — only the node that won the lease writes
  the record. `claim` performs lease election → agent binding validation → record provisioning **in that
  order**, so a node that loses the election never touches the record at all (the reason no distributed
  transaction is needed).
- **`SessionRouter`** hands a request to the current holder. When another node is the holder, the
  disposition from `submitAsync` comes back as `FORWARDED` (§6.3). **No sticky routing is needed.**

IMPORTANT: putting `mode: distributed` together with `store: in-memory` **fails at startup**. Leases,
signals, and the inbox would be shared while the transcript sat in each node's own heap, so routing a
session to its holder would mean **sending it to a node that has never seen that conversation**. That is
worse than a single node, not a step toward multiple ones.

Still single-JVM only:

- `InMemorySessionRecordStore` / `InMemorySessionLeaseStore` / `InMemoryMessageQueueRepository`
- `DefaultAgentRuntimeRegistry` — usually this is fine as-is. Runtimes may exist per node, because the
  lease pins a session to one node.

> If one JVM has two session managers (a multi-node test harness), you must create **two `SessionStore`s
> over the same two backends** — sharing one makes each mistake the other's leases for its own.

For the design background see [`routing.md`](../design/session/routing.md).

---

## 12. Scheduling lifecycle — never touch it

The first two items of §4 ("Do not do this") in
[`docs/overview/scope-model.en.md`](../overview/scope-model.en.md), reduced to one line:

> **`LiveSession.close()` cleans up handle resources only. It closes none of `AgentRuntime`,
> `SchedulingEngine`, `ScheduledTaskManager`, `OrcaAgentExecutor`.**

`AgentRuntime` is on that list because it is **agent-scoped** — another session of the same agent may
still be using that runtime (and the `ToolRegistry` / `McpClientManager` inside it).

`ScheduledTask.boundRuntimeId` references an agent-scoped id, so when cron re-fires after the original
session has ended, the runtime still resolves. That is why `AgentRuntimeId` has to be **deterministic** as
`agent:<name>[:<disc>]`.

There is only one way to break this from the starter — **closing something you took out of the stack**.

```java
// ❌ closing what you borrowed
@PreDestroy
public void shutdown() {
    stack.schedulingEngine().ifPresent(SchedulingEngine::close);   // closes ahead of what the stack would close
}

// ❌ a bigger mistake — piling runtime destruction onto session close
public void endChat(SessionId id) {
    sessions.release(id);
    stack.runtime(runtimeId).ifPresent(OrcaAgentRuntime::close);   // destroys a runtime another session of the same agent was using
}

// ✅ the stack closes what it created, in the reverse order of creation. You close nothing.
```

`AimonStack` is a `@Bean(destroyMethod = "close")`, and the scheduling shutdown is handled by the
two-phase `SmartLifecycle` of §3.3 **after the web server has closed**.

---

## 13. Observability and logging

### 13.1 Actuator health

With `spring-boot-starter-actuator` on the classpath, `AimonHealthIndicator` is registered automatically.

```json
{
  "status": "UP",
  "details": {
    "aimonStatus": "SERVING",
    "not-closed": "UP",
    "agent-runtime-registered": "UP",
    "scheduling-running": "UP",
    "agent-runtime-capacity": "UP — 3 of 100 tenant runtime slot(s) in use",
    "degradations": [
      "session-store: ...",
      "skill-approval: ..."
    ]
  }
}
```

- There are four checks — `not-closed` (has the stack been closed), `agent-runtime-registered` (are the
  runtimes of every declared agent standing), `scheduling-running` (is scheduling **dead** rather than
  merely turned off), and `agent-runtime-capacity` (are the tenant slots saturated).
- Every check carries **both a verdict (UP/DOWN) and an explanation** — what an alert matches on is a
  boolean, and what the person woken by that alert needs is the explanation. When saturated, the capacity
  check's explanation even says what to raise ("… and none is idle, so the next new tenant is refused;
  raise maxEntries or shorten the idle TTL").
- **Status stays `UP` even when there are degradations.** A degradation is fixed at startup and does not
  resolve by itself, so flying `DOWN` forever would make the alert ring forever — instead the reason is
  carried in the details.
- `degradations` is the list of reduced capability from §3.5. The explanations on checks that passed are
  not noise either — that is how the capacity check carries its counters, and those are the numbers you
  judge a limit raise by.

### 13.2 Micrometer

If a `MeterRegistry` **bean** exists, the tenant runtime gauges are registered (prefix
`aimon.agent.runtimes`).

| Meter | Meaning |
|-------|---------|
| `aimon.agent.runtimes.active` (gauge) | how many tenant runtimes this node holds right now |
| `aimon.agent.runtimes.leased` (gauge) | how many of those **someone holds a lease on** — one live session handle counts as held |
| `aimon.agent.runtimes.max` (gauge) | the cap on what this node can hold |
| `aimon.agent.runtimes.saturated` (gauge) | 1 if a new tenant request would be refused right now — it comes back down by itself once a slot is reclaimed |
| `aimon.agent.runtimes.exhausted` (counter) | requests refused because there was nothing to reclaim |
| `aimon.agent.runtimes.provision.failed` (counter) | tenant runtimes that failed to assemble → **a provisioner or configuration problem** |

The last two are separate because **the prescriptions are completely different** — one is a capacity
problem, the other is a provisioner that throws or a tenant configuration that cannot be satisfied.

**When `.exhausted` rises, what to fix is decided by the gap between `.active` and `.leased`.** The same
refusal happens in three entirely different situations.

| When `.active` = `.max` | How to read it | Prescription |
|---|---|---|
| `.leased` is far lower | most of them are alive only because of the **runtime** idle TTL | shorten `aimon.agent-runtime.idle-ttl` — raising the cap only meets the same wall again |
| `.leased` is nearly `.max` too, and that many turns are running | every slot really is in use — the refusal is honest | raise `aimon.agent-runtime.max-entries` (or add nodes) |
| `.leased` is nearly `.max` but there are almost no turns | live session handles are floating in the cache holding leases | shorten `aimon.session.cache.idle-ttl` (or `.max-entries`) |

Looking at `.active` alone does not tell these three apart, and the prescriptions point in opposite
directions.

**What holds a lease is the live session, not the turn.** A `LiveSession` handle holds the lease on that
tenant runtime for as long as it exists (`LeasedLiveSession`), so the runtime idle TTL does **not even
start** until the session cache evicts the handle. The two timers are **serial, not parallel**, and both
default to 30 minutes — so in a deployment where nothing was touched, the time from the last turn to
runtime reclamation is over 60 minutes (plus the sweep interval). If `.leased` is stuck high, the first
thing to look at is not the runtime-side TTL but `aimon.session.cache.idle-ttl`.

**The refusal itself carries the same numbers.** The `AgentRuntimeExhaustedException` message writes down
"N are alive and K of those are held right now". The gauges above are only sampled at each scrape
interval, and a short-lived saturation loses the evidence for the second row when idle entries expire by
themselves before the next scrape arrives — at that moment this exception message is the only record left.

### 13.3 Tracing and LLM usage

```yaml
aimon:
  tracing:
    enabled: true
    payload-capture: none      # full leaves prompt/response bodies behind — beware PII
    max-chars: 2000
    max-spans: 500
```

For token and cost metering see [llm-usage-metering.en.md](../features/llm/llm-usage-metering.en.md).
`AgentExecutionResult` itself exposes no aggregate fields, so metering collects `TokenUsage` either by
**subscribing to `events(...)`** or from an `LlmClient` decorator. The completion reason is read with
`result.getCompletionReason()` (to tell budget exhaustion from hitting the token cap).

Session **cumulative** usage is `SessionTotals` (turn count, iteration count, `TokenUsage`), and it is
persisted on the `SessionRecord`, so it continues across reopening a handle.

### 13.4 Tagging the call path

Setting `LiveSessionOptions.sourceAgentId` to something meaningful makes the path easy to follow in logs
and traces — `"api:/agent/chat"`, `"batch:nightly-summary"`.

### 13.5 Secret masking in `/env` and `/configprops`

The starter registers a single `SanitizingFunction` bean (`aimonSanitizingFunction`) to hide the secrets
under its own prefix. **Because Boot does not do it for you** — while `show-values` sits at its default
`NEVER` every value is hidden, but the moment an operator moves it to `ALWAYS` or `WHEN_AUTHORIZED`, Boot
stops masking and applies only the `SanitizingFunction`s the application registered. And Boot 3.x
registers **none** (`SanitizingFunction.ifLikelyCredential()`, which people remember as hiding things by
name, is a **helper for writing** such a function, not a default). Without that bean,
`aimon.llm.api-key` is printed in full at exactly the moment an operator expected Boot's usual discretion.

- The rule is Boot's word list verbatim — under the `aimon` prefix, hide anything **ending** in
  `password` · `secret` · `key` · `token`, or **containing** `credentials`.
- So everything under `aimon.credentials.*` is hidden **whatever the leaf name is** (`username`, `pat`,
  anything). That is why the property tree is the plural `credentials` rather than the singular
  `credential` (§4).
- A value that merely reads like a secret, such as `aimon.memory.max-tokens`, is **not** hidden — the rule
  is a suffix rule, so a name ending in `tokens` does not match, and a masked iteration limit helps
  nobody.
- Masking is applied to the `aimon` prefix only. Hiding **your** properties from an operator who said they
  want to see values is answering a question nobody asked.
- A `SanitizingFunction` you register runs **alongside** the starter's (Actuator collects them all and
  applies them in order). To replace the starter's, you have to declare the **bean name
  `aimonSanitizingFunction`** — had we backed off by type, the API key would have been quietly exposed the
  day you created one function for your own properties.

---

## 14. Hosts that are not Spring — `AimonStack`

For Quarkus / Micronaut / a plain `main` / a batch worker, use `aimon-bootstrap` directly. What the
starter does amounts to **translating properties into an `AimonStackSpec`**, so building that spec by hand
gets you the same stack.

```java
AimonStackSpec spec = AimonStackSpec.builder()
        .workspaceRoot("/var/lib/aimon")
        .llm(LlmSpec.of(myLlmClient))
        .agent(AgentSpec.named("ops"))
        .build();

try (AimonStack stack = AimonStack.from(spec)) {
    stack.start();

    SubmitDisposition d = stack.sessionRouter().submit(/* SubmitRequest */);
    // ...
}
// close() closes what the stack created, in the reverse order of creation.
```

The defaults of this minimal spec:

| Axis | Default |
|------|---------|
| Filesystem | a local tree under the workspace root |
| Session store | in-memory `SessionRecordStore` |
| Skill approval | fail-closed (`SkillApprovalSpec::denyAll`) |
| Tools | all core tools |
| Scheduling | off |
| Budget | `ExecutionBudget::unlimited` ← **change this in production** (`.defaultBudget(...)`) |

IMPORTANT: the last row is **different from §8**. The starter's default budget is finite
(20 / 100000 / 120s), but using bootstrap directly gives you the framework default, `unlimited` — the
finite default is something the starter adds because it knows it is a server, and `aimon-bootstrap` could
be a CLI, a batch job, or a server, so it does not. On this path `.defaultBudget(...)` is **yours**.

The spec **rejects some things at build() time**, before assembly — zero agents, two agents that resolve
to the same runtime identity (an `agent:<name>[:<discriminator>]` collision — the place where the second
spec's bundle, filesystem, and tools would be silently discarded), and both members of a pair that are
alternatives (`knowledgeStore`/`knowledgeStoreFactory`, `credentialStore`/`credentialStoreFactory`, a
`MemorySpec` store vs. `ExecutorSpec.memoryContextProvider`). All of it turns "a mystery at request time"
into "a message at build time".

IMPORTANT: **the stack closes only itself.** Everything reachable through the accessors
(`sessionRouter()`, `agentExecutor()`, `sessionRecordStore()`, `runtime(id)` …) is **borrowed**, and
closing it reproduces the bugs of §12 exactly.

Note that when you call the router directly **you have to attach the budget yourself** — the budget in
`LiveSessionOptions.defaults()` is unlimited (§6.1). What the starter's `AimonSessions` was filling in is
yours to fill in here.

---

## 15. Embedding checklist

### The starter path

- [ ] Set the three properties `aimon.workspace.root` / `aimon.llm.api-key` /
      `aimon.agent-defaults.default-agent`.
- [ ] Put the LLM vendor module (`aimon-llm-anthropic` or `aimon-llm-openai`) on your dependencies
      **yourself**.
- [ ] Inject and use `AimonSessions` — do not hand-build requests against `SessionRouter`. (If you do,
      start from `newRequest(...)`.)
- [ ] Check that the `aimon.budget.*` defaults (20 / 100000 / 120s) suit this workload — leaving them
      unset means these finite defaults, not unlimited.
- [ ] Read the degradation list in the startup log, and know which of them you intended.
- [ ] **Do not close** anything you took out of `AimonStack`.

### Using sessions

- [ ] Derive the `SessionId` deterministically from your domain (user + thread).
- [ ] End a conversation with `release(sessionId)` — to erase the history too, `SessionRouter.deleteSession`.
- [ ] Cancel by **targeting the turn**: `interrupt(sessionId, turnId, reason)` (the `turnId` comes from
      the disposition).
- [ ] Check `!result.isSuccess()` and build the user-facing answer from `getErrorMessage()`
      (`AgentExecutionResult` has no `isError()`, and both getters are **nullable Strings**, not
      `Optional`).

### Multiple agents / tenants

- [ ] Your `AimonAgentCustomizer` implementation is **thread-safe** (it is called concurrently while
      tenant runtimes are assembled).
- [ ] `aimon.agent-runtime.max-entries` is set to what this node can carry.
- [ ] Alerts are wired on `aimon.agent.runtimes.exhausted` / `.provision.failed`.
- [ ] `aimon.agent.runtimes.active` and `.leased` are **on the same graph** so you can see them when that
      alert wakes you — the gap between the two decides whether to raise the cap or shorten a TTL.
- [ ] You know that when `.leased` is high the thing to shorten is `aimon.session.cache.idle-ttl` — the
      runtime idle TTL does not start while a live session handle holds the lease (§13.2).

### Multiple instances

- [ ] `aimon.session.mode: distributed` + a distributed `store` + that backend module on your dependencies.
- [ ] `aimon.session.node-id` differs per instance.
- [ ] You do not depend on sticky routing (a `FORWARDED` disposition is the normal path).

### Observability

- [ ] If you use Actuator, the `degradations` from `/actuator/health` are surfaced on a dashboard.
- [ ] LLM usage is collected either by subscribing to `events(...)` or from an `LlmClient` decorator.
- [ ] `LiveSessionOptions.sourceAgentId` is set to something meaningful.

---

## Appendix A. Manual wiring — when you cannot use the starter

This appendix is needed **only when you can use neither the starter nor `AimonStack`**. Consult it when
you have to change the shape of the assembly itself, or when you have to read existing code that was
already wired by hand. The traps described here **do not occur on the starter path** (§3.4).

### A.1 Minimal example

```java
public final class MinimalEmbeddingExample {

    public static void main(String[] args) {
        // 1) Application-scoped singletons (in practice a DI container manages these)
        LlmClient llmClient = /* e.g., new OpenAILlmClient(...) */;
        SessionRecordStore sessionRecords = new InMemorySessionRecordStore();
        TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecords);
        OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
                .create(llmClient, transcriptManager);

        Agent agent = /* load your agent definition */;
        AgentRegistry agentRegistry = new DefaultAgentRegistry();
        agentRegistry.register(agent);

        VirtualFileSystem fileSystem = /* LocalFileSystem or GridFs... */;
        CredentialStore credentialStore = /* InMemoryCredentialStore */;
        ScheduledTaskManager scheduledTaskManager = /* ... */;

        // Once at bootstrap: register a runtime per agent (AgentRuntimeId = "agent:<name>")
        OrcaAgentRuntimeManager manager = OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeFactory(new OrcaAgentRuntimeFactory())
                .build();
        AgentBundle bundle = AgentBundle.builder().agent(agent).build();
        manager.getOrCreateRuntime(bundle, fileSystem, credentialStore);

        // contextBuilder must be idempotent — it must not create a new runtime per session,
        // it must return the already-registered agent-scoped runtime.
        LiveSessionFactory factory = new LiveSessionFactory(agentRegistry,
                a -> manager.getOrCreateRuntime(AgentBundle.builder().agent(a).build(), fileSystem, credentialStore),
                executor,
                sessionRecords);

        try (LiveSession session = factory.open(
                SessionId.generate(), agent.getName(), LiveSessionOptions.defaults())) {

            AgentExecutionResult result = session.submit("Hello, what tools do you have?");
            System.out.println(result.isSuccess() ? result.getFinalAnswer() : "(error) " + result.getErrorMessage());
        }
        // session.close() → handle resources only.
        // The SessionRecord (persistent), the OrcaAgentRuntime (agent-scoped), the executor, and the
        // scheduling components are all still alive.
    }
}
```

> The 3-argument constructor of `LiveSessionFactory` opens sessions without a `SessionRecordStore` — and
> then `SessionTotals` and the budget override **vanish with the handle** and are not restored on resume.
> Use the **4-argument constructor**, as above.

### A.2 Wiring by hand in Spring Boot

<details>
<summary>The whole @Configuration (expand)</summary>

```java
@Configuration
public class AgentConfiguration {

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        return new OpenAILlmClient(props.toOpenAIConfig());
    }

    @Bean
    public SessionRecordStore sessionRecordStore() {
        return new InMemorySessionRecordStore();
    }

    @Bean
    public TranscriptManager transcriptManager(SessionRecordStore sessionRecordStore) {
        return new DefaultTranscriptManager(sessionRecordStore);
    }

    @Bean
    public MessageQueueManager messageQueueManager() {
        return new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
    }

    @Bean
    public OrcaAgentExecutor orcaAgentExecutor(LlmClient llmClient,
            TranscriptManager transcriptManager,
            MessageQueueManager messageQueueManager) {
        // The withXxx methods of OrcaAgentExecutorFactory mutate the factory and return this.
        // So do not share it as a singleton bean — create it locally here.
        return new OrcaAgentExecutorFactory()
                .withMessageQueueManager(messageQueueManager)
                .create(llmClient, transcriptManager);
    }

    @Bean(destroyMethod = "close")
    public SchedulingEngine schedulingEngine(AgentRuntimeRegistry registry) {
        // The AgentRuntimeRegistry is created outside the engine and injected — the engine does not own
        // it, so engine.close() does not close the registry.
        SchedulingEngine engine = SchedulingEngineBuilder.create()
                .agentRuntimeRegistry(registry)
                .build();
        engine.start();
        return engine;
    }

    @Bean
    public ScheduledTaskManager scheduledTaskManager(SchedulingEngine engine) {
        // Expose the task manager the engine owns — building a separate one splits them in two.
        return engine.getTaskManager();
    }

    @Bean
    public AgentRegistry agentRegistry(AgentBundleLoader loader, AgentProperties props) {
        DefaultAgentRegistry registry = new DefaultAgentRegistry();
        for (String name : props.getAgents()) {
            registry.register(loader.load(name).getAgent());
        }
        return registry;
    }

    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean
    public OrcaAgentRuntimeManager agentRuntimeManager(OrcaAgentExecutor executor,
            ScheduledTaskManager scheduledTaskManager,
            AgentRuntimeRegistry agentRuntimeRegistry) {
        // The registry has to be shared with the scheduling engine, so pass the same instance explicitly.
        return OrcaAgentRuntimeManager.builder()
                .agentExecutor(executor)
                .scheduledTaskManager(scheduledTaskManager)
                .agentRuntimeFactory(new OrcaAgentRuntimeFactory())
                .agentRuntimeRegistry(agentRuntimeRegistry)
                .build();
    }

    @Bean
    public ApplicationRunner registerAgentRuntimes(OrcaAgentRuntimeManager manager,
            AgentBundleLoader loader,
            AgentProperties props,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return args -> {
            for (String name : props.getAgents()) {
                manager.getOrCreateRuntime(loader.load(name), fileSystem, credentialStore);
                // → AgentRuntimeId = "agent:<name>"
            }
        };
    }

    @Bean
    public LiveSessionFactory liveSessionFactory(AgentRegistry agentRegistry,
            OrcaAgentRuntimeManager manager,
            OrcaAgentExecutor executor,
            SessionRecordStore sessionRecordStore,
            VirtualFileSystem fileSystem,
            CredentialStore credentialStore) {
        return new LiveSessionFactory(agentRegistry,
                agent -> manager.getOrCreateRuntime(
                        AgentBundle.builder().agent(agent).build(), fileSystem, credentialStore),
                executor,
                sessionRecordStore);
    }
}
```

</details>

> **What the factory does not inject**: `LiveSessionFactory.open(...)` internally calls
> `new DefaultLiveSession(sessionId, runtime, executor, options, null, null, sessionRecords)` — the
> `SessionRecordStore` is injected, but **`MessageQueueManager` and `HookExecutionManager` are `null`**.
> If you need queue auto-enqueue or the `OnSessionStart`/`OnSessionEnd` hooks, you have to **call the
> 7-argument `DefaultLiveSession` constructor yourself** (A.4). The starter already uses the 7-argument
> constructor.

### A.3 Choosing a session model

In all three patterns **the `SessionId` is persistent**; what differs is how long you hold the
`LiveSession` handle.

| Model | Shape | Suits | Watch out |
|-------|-------|-------|-----------|
| **A — a handle per request** | one HTTP request = one handle | REST, GraphQL resolvers, batch, serverless | handle open/close cost on every request. (The agent-scoped runtime is reused, so MCP/Knowledge initialization is not repeated) |
| **B — a handle per thread** | kept while the conversation is open (WebSocket/SSE) | chat UIs | needs an idle expiry policy. One handle runs **one turn at a time** |
| **C — a singleton handle** | opened once at application start | cron / worker / dev sandbox | only one `SessionId` — unsuitable for multiple users |

```java
// Model B
@Component
public class ChatSessionRegistry {

    private final LiveSessionFactory factory;
    private final Map<SessionId, LiveSession> handles = new ConcurrentHashMap<>();

    public LiveSession getOrOpen(SessionId id, String agentRef, LiveSessionOptions opts) {
        return handles.computeIfAbsent(id, sid -> factory.open(sid, agentRef, opts));
    }

    public void close(SessionId id) {
        LiveSession s = handles.remove(id);
        if (s != null) s.close();
    }

    @PreDestroy
    public void closeAll() {
        handles.values().forEach(LiveSession::close);
        handles.clear();
    }
}
```

**Do not open two handles on the same `SessionId` at once** — the two handles would race to modify the
same `SessionRecord`. Several handles serving the same `SessionId` **in sequence** over time is normal
(restarts, node moves), and the history and totals are then restored from the `SessionRecordStore`.
Running different `SessionId`s concurrently is allowed.

### A.4 Queue-enabled handles and draining

Decide busy/idle from **the `SubmitOutcome` of `offerAsync`** — the `LiveSessionStatus` returned by
`status()` is a best-effort observational snapshot, not a control gate.

```java
SubmitOutcome outcome = session.offerAsync(input, listener);
switch (outcome.getKind()) {
    case EXECUTED -> outcome.getResultStage().orElseThrow().whenComplete(this::renderOrError);
    case QUEUED -> notifyClient("Queued. Current queue depth: " + outcome.getQueuePosition());
}
```

- `QUEUED` → the input goes into the queue at `QueuedInputPriority.NEXT`. **The session does not drain
  automatically** — after the in-progress turn finishes, the host has to drain for events to flow to the
  listener.
- `getQueuePosition()` is the **queue depth** observed right after enqueue. With several producers, treat
  it as an upper-bound estimate only.
- For `offerAsync` to enqueue, the session must have been created with a `MessageQueueManager`. With no
  queue, `offerAsync` always returns `EXECUTED` and turns overlap.

To attach a queue, call the 7-argument constructor yourself — the 5-argument constructor does attach the
queue, but it leaves out the `SessionRecordStore` and loses the persistent state.

```java
public LiveSession open(SessionId sessionId, String agentRef, LiveSessionOptions opts) {
    Agent agent = agentRegistry.findByName(agentRef)
            .orElseThrow(() -> new IllegalArgumentException("Unknown agent: " + agentRef));
    // contextBuilder returns the agent-scoped runtime — it does not build a new one per session.
    OrcaAgentRuntime runtime = Objects.requireNonNull(contextBuilder.build(agent));
    return new DefaultLiveSession(sessionId, runtime, executor, opts,
            messageQueueManager, null, sessionRecords);
}
```

You must inject **the same queue instance** into the executor as well, with
`OrcaAgentExecutorFactory.withMessageQueueManager(...)` (CQ-04). Draining after a turn ends (CQ-05) is the
host's responsibility.

```java
// LiveSession does not expose the runtime, so the host has to hold the id it filters on.
AgentRuntimeId agentRuntimeId = AgentRuntimeId.from(agent);

List<QueuedInput> drained = messageQueueManager.drainForInjection(
        q -> agentRuntimeId.equals(q.getAgentRuntimeId()), QueuedInputPriority.LATER);
for (QueuedInput entry : drained) {
    session.submit(entry.getInputText());
}
```

- The second argument is the **lowest priority tier to include** (`NOW` → `NEXT` → `LATER`). Passing
  `LATER` collects everything.
- Put a re-entrancy guard in place so the re-submission loop does not call itself again (see
  `ReplSession.draining`).

### A.5 Swapping the budget at runtime

This is an extension API that exists only on `DefaultLiveSession`, not on the `LiveSession` interface.

```java
DefaultLiveSession live = (DefaultLiveSession) session;
live.setOptions(live.getOptions().withBudget(tighterBudget));  // persisted as an override on the SessionRecord
live.clearBudgetOverride();                                     // back to the opener default
```

An override set with `setOptions` is written to the `SessionRecordStore` as well, so it **survives
reopening the handle**. To undo it you have to call `clearBudgetOverride()` explicitly.

### A.6 Manual-wiring checklist

- [ ] `OrcaAgentExecutor` is declared as an application singleton.
- [ ] `SchedulingEngine` is managed as `@Bean(destroyMethod = "close")` so that it is **closed exactly
      once**.
- [ ] `SchedulingEngine` and `OrcaAgentRuntimeManager` share **the same instance** of
      `AgentRuntimeRegistry`.
- [ ] `LiveSessionFactory` is built with the **4-argument constructor that takes a `SessionRecordStore`**.
- [ ] The `ContextBuilder` **returns the agent-scoped runtime** instead of building a new one per session.
- [ ] Handles are always closed with `try-with-resources`, and nothing is submitted after `close()`
      (`IllegalStateException: LiveSession has already been closed`).
- [ ] Several threads do not `submit` on one handle at the same time.
- [ ] If you need the queue, you injected **the same queue on both sides** with the 7-argument
      `DefaultLiveSession` + `withMessageQueueManager`, and you put drain logic after a turn ends.

---

<a id="부록-b-옛-이름-매핑"></a>

## Appendix B. Old-name mapping

Every old type name that used to appear in this document has been renamed.

> **If you are moving a whole 0.1.x application over, this table alone is not enough.** Renaming is one
> thing; *removing the wiring that used to build a new `AgentRuntime` per session* is another, and the
> procedure for the latter — Before / After code, all the way to "if your host is Spring, that wiring is
> deleted, not moved" — is governed solely by
> [`agent-runtime-scope.md` §4 Migration](../design/agent-execution/agent-runtime-scope.md#4-마이그레이션).
> The table below is the dictionary you read that section with.

| Old name | Current name |
|----------|--------------|
| `Conversation` | `SessionRecord` |
| `ConversationId` | `SessionId` |
| `ConversationRepository` | `SessionRecordStore` |
| `ConversationManager` | `TranscriptManager` |
| `AgentSession` | `LiveSession` |
| `AgentSessionFactory` | `LiveSessionFactory` |
| `AgentSessionOptions` | `LiveSessionOptions` |
| `DefaultAgentSession` | `DefaultLiveSession` |
| `AgentExecutionContext` | `AgentRuntime` |

IMPORTANT: **`Session` and `AgentSession` may not be used as type names** — they are the names that let
the two lifetimes impersonate each other, so `SessionNamingArchitectureTest` (`aimon-session-routing`)
blocks them at build time.

**Persisted names, by contrast, are deliberately frozen** and unchanged — the Mongo collections
`conversation_*`, the Postgres tables and channels `conversation_*`, the wire keys `"conversationId"` /
`"invokingConversationId"`. Only the Java identifiers were renamed, so it is normal for them to look out
of step.

The word "conversation" itself is not retired — it remains wherever it means **the exchange of messages
with the LLM** (`getConversationHistory()`, "Conversation compacted"). Just do not use it to mean a
lifetime.

---

## Related documents

- [`docs/design/integration/spring-boot-starter.md`](../design/integration/spring-boot-starter.md) — the full starter design
- [`docs/overview/scope-model.en.md`](../overview/scope-model.en.md) — the full rules for lifetime, ownership, and teardown
- [`docs/overview/glossary.en.md`](../overview/glossary.en.md) — per-term definitions and the lifetime dictionary
- [LiveSession development guide](../features/session/agent-session-guide.en.md) — handle API / lifecycle / close rules
- [LiveSession tutorial](../features/session/agent-session-tutorial.en.md) — a step-by-step walkthrough for beginners
- [Command Queue guide](../features/agent-execution/command-queue-guide.en.md) — CQ-01 ~ CQ-06, how `MessageQueueManager` behaves
- [Hook development guide](../features/hook/hook-development-guide.en.md) — pre/post tool hooks, event interceptors
- [LLM Provider development guide](../features/llm/llm-provider-development-guide.en.md) — adding your own LLM client
- [LLM usage metering](../features/llm/llm-usage-metering.en.md) — token/cost observability
- [Tool development guide](../features/tool/tool-development-guide.en.md) — adding a custom Tool
- [`samples/aimon-sample-app`](../../samples/aimon-sample-app) — a working starter application
