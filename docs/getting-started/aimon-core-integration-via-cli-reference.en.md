---
translated_from: docs/getting-started/aimon-core-integration-via-cli-reference.md
source_commit: a56317a
---

# aimon-core integration guide — following aimon-cli as the reference

> Walks through how `aimon-cli` assembles `aimon-core`, line by line, and explains how to port the same patterns into your own application.

## Where this document sits

The following documents already exist. They have different purposes, so read them together.

| Document | Purpose |
|----------|---------|
| [architecture.en.md](../overview/architecture.en.md) | Reference for `aimon-core`'s core abstractions (Tool, LlmClient, VirtualFileSystem, ...) |
| [embedding-agent-in-application.en.md](embedding-agent-in-application.en.md) | The recommended patterns for Spring Boot/SDK embedding, scope policy, multi-session |
| [agent-session-guide.en.md](../features/session/agent-session-guide.en.md) | How to use the `LiveSession` API and event streaming |
| [scope-model.en.md](../overview/scope-model.en.md) | The normative document for lifetime, ownership and teardown — every scope statement here follows it |
| **This document** | **Follows `aimon-cli`'s actual bootstrap code line by line and explains *why* it was assembled that way** |

`aimon-cli` is the most complete reference implementation: it plugs in every extension point the core offers (LLM, filesystem, tools, skills, hooks, scheduling, MCP) in a single place. This document reads that code as-is and points out the decisions you face when moving it into your own host application.

---

## Table of contents

1. [The big picture](#1-the-big-picture)
2. [Module dependencies and build setup](#2-module-dependencies-and-build-setup)
3. [Bootstrap flow — `AimonCli.call()`](#3-bootstrap-flow--aimonclicall)
4. [`AgentSetupFactory.create()` line by line](#4-agentsetupfactorycreate-line-by-line)
5. [Adaptation guide, component by component](#5-adaptation-guide-component-by-component)
6. [Lifecycle and scopes](#6-lifecycle-and-scopes)
7. [Minimal embedding example](#7-minimal-embedding-example)
8. [Moving to a web application](#8-moving-to-a-web-application)
9. [Other adaptation scenarios](#9-other-adaptation-scenarios)
10. [Checklist](#10-checklist)

---

## 1. The big picture

`aimon-cli` works in three large stages.

```
[Picocli entry point]       [Factory bootstrap]                   [Session execution]
AimonCli.call()      ──▶    AgentSetupFactory.create(config)  ──▶ ReplSession.start()
- parse options             - create the LLM client               - LiveSession.submit(input)
- load configuration        - initialise VirtualFileSystem        - subscribe to events
- open AgentSetup           - register Tool/Skill/Hook            - queue user input
- AgentSetup.close()        - start SchedulingEngine
                            - create LiveSession
```

Your application follows the same three stages. **Only swap the Picocli entry point for your own entry point (a web handler, a batch job, ...) and the REPL for your own interaction loop.** The bootstrap stage in the middle carries over almost unchanged.

Layer diagram:

```
┌─────────────────────────────────────────────────────────────┐
│  Application                                                │
│  AimonCli (CLI)  /  HTTP handler  /  Batch job              │
└──────────────────────────────┬──────────────────────────────┘
                               │ create(CliConfig)
┌──────────────────────────────▼──────────────────────────────┐
│  Composition root (AgentSetupFactory)                       │
│  LlmClient + VirtualFileSystem + AgentBundle +              │
│  ToolRegistry + SkillRegistry + HookRegistry +              │
│  SchedulingEngine + AgentRuntime +                          │
│  AgentExecutor + SessionRecordStore + LiveSession           │
└──────┬────────────┬──────────────┬──────────────┬───────────┘
       │            │              │              │
┌──────▼──┐    ┌────▼────┐   ┌─────▼──────┐  ┌────▼─────────┐
│ aimon-  │    │ aimon-  │   │ aimon-     │  │ aimon-       │
│ llm-*   │    │ filesys │   │ scheduling │  │ knowledge-*  │
└─────────┘    │ -*      │   │ -quartz    │  └──────────────┘
               └─────────┘   └────────────┘
```

`aimon-core` defines interfaces only. The actual implementations are pulled in from separate modules and assembled — that is the central pattern `aimon-cli` demonstrates.

---

## 2. Module dependencies and build setup

### `aimon-cli`'s `build.gradle.kts`

`modules/aimon-cli/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    application
}

application {
    mainClass.set("at.aimon.cli.AimonCli")
}

dependencies {
    // Core module (interfaces + the Orca executor)
    implementation(project(":aimon-core"))

    // LLM implementations — pick only the ones you need
    implementation(project(":aimon-llm-anthropic"))
    implementation(project(":aimon-llm-openai"))

    // CLI only (not needed in your own application)
    implementation(libs.picocli)
    implementation(libs.jline)
    implementation(libs.jansi)

    // Configuration parsing (optional)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.snakeyaml)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
}
```

### Dependencies for your own application

**Minimal setup:**

```kotlin
dependencies {
    implementation(project(":aimon-core"))      // or the published artifact
    implementation(project(":aimon-llm-openai")) // at least one LLM
}
```

**Add as needed:**

| Module | When you need it |
|--------|------------------|
| `aimon-llm-openai` / `aimon-llm-anthropic` | LLM calls (at least one is required) |
| `aimon-filesystem-gridfs` | MongoDB GridFS as the virtual filesystem backend |
| `aimon-filesystem-s3` | S3/MinIO as the backend |
| `aimon-scheduling-quartz` | If you need task scheduling |
| `aimon-knowledge-opensearch` | A vector-search-backed KnowledgeStore |
| `aimon-sandbox-docker` / `aimon-sandbox-kubernetes` | If you need isolated shell execution |
| `aimon-browser-playwright` | If you need browser automation tools |

> **Module dependency rule** (.claude/rules/architecture.md): implementation modules reference the core only through `implementation(project(":aimon-core"))`. They do not expose it with `api()` — that would leak core types as a transitive dependency.

---

## 3. Bootstrap flow — `AimonCli.call()`

`modules/aimon-cli/src/main/java/at/aimon/cli/AimonCli.java:67-136`

```java
public Integer call() {
    // (1) Load the configuration
    CliConfigLoader configLoader = new CliConfigLoader();
    CliConfig config = loadConfiguration(configLoader);

    // (2) Fold the CLI options into the configuration (streaming toggle, initial budget)
    if (streaming != null) {
        config.getCliSettings().setStreaming(streaming);
    }
    ExecutionBudget initialBudget = buildInitialBudget();

    // (3) Open the AgentSetup (try-with-resources)
    AgentSetupFactory agentFactory = new AgentSetupFactory();
    try (AgentSetupFactory.AgentSetup agentSetup = agentFactory.create(config)) {

        // (4) Start the interaction loop — a REPL in the CLI
        ReplSession replSession = new ReplSession(agentSetup, cliSettings, initialBudget);
        replSession.start();
    }
    return 0;
}
```

The mapping when you port this into your own application:

| Stage in `AimonCli` | Your code |
|---------------------|-----------|
| `configLoader.load(path)` | Spring `@ConfigurationProperties`, env-vars, your own YAML parser, ... |
| `factory.create(config)` | **Keep it** — either call `AgentSetupFactory` directly, or move its internals into your own composition root |
| `try-with-resources` | Delegate the lifecycle to Spring `@Bean(destroyMethod = "close")` / Quarkus `@PreDestroy` / ... |
| `replSession.start()` | An HTTP handler, a batch job, a WebSocket receive loop |

---

## 4. `AgentSetupFactory.create()` line by line

`modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java:687-902`

This one method contains every assembly pattern in `aimon-core`. We go through it stage by stage.

> The line numbers quoted below are only a snapshot of that moment, and this file keeps growing
> (tracing, peer memory, dreamer, rewake, session checkpoints and GraalJS arrived one after another).
> If the numbers no longer line up, **find things by method name** — every helper this document
> references is prefixed `create*` / `build*` / `configure*` / `register*`.

### 4.1 Creating the LLM client (line 690)

```java
final LlmClient llmClient = createLlmClient(config);
```

Internally `LlmClientFactory.create()` branches on the provider string (`LlmClientFactory.java:16-28`):

```java
return switch (provider) {
    case "anthropic" -> createAnthropicClient(config);
    case "openai"    -> createOpenAIClient(config);
    default          -> throw new ConfigurationException("Unsupported LLM provider: " + provider);
};
```

Each builder constructs the SDK-specific configuration object (`AnthropicConfig`, `OpenAIConfig`) and injects `apiKey`, `model`, `timeout` and `baseUrl`.

If `cli.tracing` is on, one more layer goes on top (line 697-712) — `TracingLlmClient` wraps the original
client, and the same `Tracer` is injected into the executor factory as well, so turn/iteration/tool spans
all gather in one tree. What gets wrapped is **the agent turn path only**. Background subsystems (wiki
indexing, peer memory, dreamer) deliberately receive the unwrapped `llmClient` — those calls have no turn
span context, so wrapping them would not produce spans anyway.

**Your adaptation points:**
- If you have your own LLM gateway, implement `LlmClient` directly and inject it. You do not have to go through `LlmClientFactory`.
- The `LlmClient` instance is **application-scoped**. Create it once and share it across every session.
- If you are stacking a decorator, do as the CLI does and **hold both the original and the wrapper**, then decide which goes where. Merging them into one drags background work into your traces.

### 4.2 Output formatter + shell + skill parser (line 713-725)

```java
final OutputFormatter outputFormatter = createOutputFormatter(config);
final LocalShell skillHookShell = new LocalShell();
final SkillParser skillParser = createShellAwareSkillParser(skillHookShell);
final AgentBundleLoader effectiveBundleLoader = (this.agentBundleLoader != null)
        ? this.agentBundleLoader
        : new AdaptiveAgentBundleLoader(DEFAULT_AGENT_BUNDLE_BASE_PATH,
                new MarkdownAgentDefinitionParser(),
                Thread.currentThread().getContextClassLoader(), skillParser);
final AgentBundle agentBundle = effectiveBundleLoader.load(extractAgentName(config));
```

- **`OutputFormatter`** — owns console colouring and formatting. In your own application, replace it with an SSE streamer, a log appender, a WebSocket sender, ...
- **`LocalShell`** — the shell that runs the `shell` action in a skill's frontmatter. It is `AutoCloseable` and is cleaned up in `AgentSetup.close()`.
- **`SkillParser`** — the markdown skill definition parser. Injecting `LocalShell` is what makes `shell` hooks actually run.
- **`AgentBundleLoader`** — loads `agents/<name>/agent.md` together with the subagents and skills underneath it. It reads from the classpath, so it packages into a jar.

**Your adaptation points:**
- If you want to build agent definitions dynamically from code or a database, build the `AgentBundle` yourself and inject it through `AgentSetupFactory`'s package-private constructor.
- To isolate shell execution in a container, swap in the `VirtualShell` implementation from `aimon-sandbox-docker` / `aimon-sandbox-kubernetes`.

### 4.3 Session record store, transcript manager, message queue, filesystem (line 726-733)

```java
final SessionCheckpointMailbox sessionCheckpoints = createSessionCheckpointMailbox();
final InMemorySessionRecordStore sessionRecordStore = new InMemorySessionRecordStore();
final TranscriptManager transcriptManager = createTranscriptManager(sessionRecordStore, sessionCheckpoints);
final MessageQueueManager messageQueueManager = createMessageQueueManager();
final LocalFileSystem fileSystem = createFileSystem();
```

The default implementations (`AgentSetupFactory.java:1033, 1044, 1076, 1083`):

```java
private TranscriptManager createTranscriptManager(InMemorySessionRecordStore repository,
        SessionCheckpointMailbox checkpoints) {
    return new DefaultTranscriptManager(repository, checkpoints);
}

private SessionCheckpointMailbox createSessionCheckpointMailbox() {
    return SessionCheckpointMailbox.background();
}

private MessageQueueManager createMessageQueueManager() {
    return new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
}

private LocalFileSystem createFileSystem() {
    final String workingDirectory = getJarDirectory();
    final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(workingDirectory));
    fileSystem.initialize();
    return fileSystem;
}
```

Separating three names here makes everything that follows easier.

| Type | What it holds | Lifetime |
|------|---------------|----------|
| `SessionRecordStore` | The **persistent session record** identified by `SessionId` — message history, `SessionTotals`, `budgetOverride` | The store itself is **application-scoped**; its entries are per-session |
| `TranscriptManager` | The manager that reads and writes the LLM **message exchange** (conversation) on top of that record | Application-scoped |
| `SessionCheckpointMailbox` | The mailbox that flushes an in-progress session asynchronously on its own thread, between end-of-turn saves | Application-scoped |

**Hoisting `sessionRecordStore` into a factory-local variable is deliberate** (see the comment at line 727-730).
The same instance has to reach both the transcript manager (message history) and the `LiveSession` built
below (the session's two persistent side fields — `sessionTotals` and `budgetOverride`). Split it into two
and the transcript side stops seeing the totals the live session wrote back.

**The governing principle** (CLAUDE.md): a stateful component separates its store behind an interface. In-memory implementations are enough for the CLI, but in a multi-instance environment you swap `SessionRecordStore` / `MessageQueueRepository` for distributed-backend implementations.

**Your adaptation points:**
- Multi-instance: `InMemorySessionRecordStore` → a persistent implementation from `aimon-session-mongodb` / `aimon-session-postgres` / `aimon-session-redis`.
- Multi-user: `LocalFileSystem` → `GridFSFileSystem` or `S3FileSystem`. Separate the working directory per user.
- The producer (the REPL) and the consumer (the executor's ReAct loop) **must share the same `MessageQueueManager` instance** within one session — that is what makes mid-turn user input injection possible.

> **The Java names are `Session*` but the stored names are `conversation_*`.** The Mongo collections
> (`conversation_locks` / `conversation_inbox` / `conversation_signals`), the Postgres tables and channels
> (`conversation_*`), the wire keys (`"conversationId"`, `"invokingConversationId"`) and the Redis key
> prefixes are **deliberately frozen** — the rename happened in Java identifiers only, so that already
> deployed data would not be forced through a migration. The boundary is the "Not changed (deliberately
> frozen)" list, [`../migration/frozen-names.md`](../migration/frozen-names.md). If your own store implementation uses those names, **leaving them alone
> is the correct move.**

### 4.4 Skill policy and the pending-turn registry (line 738-767)

This owns the skill invocation approval flow. In the CLI the user approves or rejects at an interactive prompt, but in your own application you can decide automatically by policy or delegate to an external approval system.

```java
final PendingTurnRegistry pendingTurnRegistry = new InMemoryPendingTurnRegistry();
final PendingTurnReaper pendingTurnReaper = createPendingTurnReaper(pendingTurnRegistry, outputFormatter);
// Approvals split into two scopes — the default is per-session, and only an answer where the user
// explicitly said "always in this agent" goes to the agent-scoped store. The policy chain looks at
// the narrow one (the session) first.
final AgentApprovalStore agentApprovalStore = new InMemoryAgentApprovalStore();
final SessionApprovalStore sessionApprovalStore = new InMemorySessionApprovalStore();
// Materialise bundled (classpath) skills into the working VFS so that their attached files
// (scripts, references, templates) become real files the agent can read and ${AIMON_SKILL_DIR} resolves.
final SkillRegistry skillRegistry = OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry(
        agentBundle, fileSystem, ".aimon/skills", ".aimon/bundled-skills",
        DEFAULT_AGENT_BUNDLE_BASE_PATH + "/" + extractAgentName(config) + "/skills",
        Thread.currentThread().getContextClassLoader(), skillParser);
final SkillInvocationPolicy skillInvocationPolicy =
        createSkillInvocationPolicy(sessionApprovalStore, agentApprovalStore);
final InteractiveSkillApprovalChannel skillApprovalChannel = new InteractiveSkillApprovalChannel(
        sessionApprovalStore, agentApprovalStore, outputFormatter);
final SkillPreflightScanner skillPreflightScanner = SkillPreflightScanner.builder()
        .policy(skillInvocationPolicy)
        .registry(skillRegistry)
        .approvalChannel(skillApprovalChannel)
        .build();
```

`createSkillInvocationPolicy` (`AgentSetupFactory.java:998-1003`) assembles the policy chain. **The order is a contract**:

```java
return new SessionScopedSkillInvocationPolicy(sessionApprovalStore,          // 1. per-session (narrow)
        new ApprovalCachingSkillInvocationPolicy(agentApprovalStore,         // 2. agent-wide
                RuleBasedSkillInvocationPolicy.builder()                     // 3. rules
                        .defaultDecision(SkillInvocationDecision.ASK).build()));
```

**The narrow scope going first** is not a matter of taste; it is the only order that works. Reverse it and an
agent-wide allow granted earlier answers first, so "deny in this session" can never be reached.

> **The name `SessionApprovalStore` was retired once and then reused with a different meaning.** In old code
> and old documents this name referred to the **agent-wide** store keyed by `AgentRuntimeId` (the name was
> lying), and that store is now `AgentApprovalStore` (`…skill.policy.agent`). **Today's
> `SessionApprovalStore` (`…skill.policy.session`) is the per-session store keyed by `SessionId`**, the
> successor of the old `ConversationApprovalStore`. This is the easiest place in this file to get backwards,
> so consult the mapping table in [scope-model.en.md §6](../overview/scope-model.en.md). **The meaning of an
> approval did not change at all** — an agent-wide decision still has no TTL and is not cleared by `/clear`.

**Adaptation points:**
- Headless environments (batch, a web API): auto-allow or auto-deny with, for example, `RuleBasedSkillInvocationPolicy.builder().defaultDecision(SkillInvocationDecision.ALLOW)`.
- External approval systems: implement `SkillApprovalChannel` yourself and send approval requests to Slack / email / a dashboard.
- If you do not need the skills' attached files, the non-materialising `buildSkillRegistry(...)` overload is enough.

### 4.5 Creating the AgentExecutor (line 806-808)

```java
final OrcaAgentExecutor agentExecutor = createAgentExecutor(
        effectiveLlmClient, transcriptManager, messageQueueManager,
        config.getCliSettings().isStreaming(),
        skillPreflightScanner, pendingTurnRegistry, memoryContextProvider);
```

`OrcaAgentExecutorFactory` builds the ReAct loop executor — the final call is
`create(llmClient, transcriptManager)`, and everything else is a `with*` setter in front of it. This
instance is **application-scoped** too — every session shares it.

A few things are already stacked on the factory before `create()` (line 795-804): `withRewakeService`,
`withSubagentBehaviorRegistry`, `withCostEstimator`, and, if tracing is on, `withTracer` /
`withTracePayloadPolicy`. **`with*` mutates the factory and returns itself**, so building two executors from
the same factory instance means the second inherits the first's configuration. If you plan to build several
executors, build several factories.

### 4.6 Creating the SchedulingEngine (line 809, 862)

```java
final SchedulingEngine schedulingEngine = createSchedulingEngine(agentRuntimeRegistry);
// ...
schedulingEngine.start();  // line 862 — after the runtime is registered
```

```java
// AgentSetupFactory.java:1571
private static SchedulingEngine createSchedulingEngine(AgentRuntimeRegistry agentRuntimeRegistry) {
    return SchedulingEngineBuilder.create().agentRuntimeRegistry(agentRuntimeRegistry).build();
}
```

> **Lifecycle rule** ([scope-model.en.md §2](../overview/scope-model.en.md)): scheduling components are **application-level (long-lived)**. The scheduling engine must survive the teardown of an `AgentRuntime`. The CLI closes it inside `AgentSetup.close()` because there the process lifetime *is* the session lifetime, but **in an embedding you have to separate them.**

The `SchedulingEngineBuilder.agentRuntimeRegistry(...)` parameter carries `@ExternallyManaged` — meaning
"this is a borrowed reference and the engine does not close it". The annotation has no runtime behaviour and
exists for documentation, but by convention it marks something **that class must not close**.

**Adaptation points:**
- If you need distributed/clustered scheduling, swap in the Quartz-based implementation from `aimon-scheduling-quartz`.
- `AgentRuntimeRegistry` **must be created outside and injected** — the engine does not own it.

### 4.7 Assembling the AgentRuntime (line 833-860)

```java
final OrcaAgentRuntimeFactory agentRuntimeFactory =
    new OrcaAgentRuntimeFactory(
        "1.0.0",
        ".aimon/commands",
        ".aimon/agents",
        ".aimon/skills",
        createWikiKnowledgeStore(agentRuntimeRegistry, llmClient))
        .withSkillRegistry(skillRegistry)
        .withCodeSubagentRegistry(codeSubagentRegistry)
        .withPendingTurnRegistry(pendingTurnRegistry)
        .withAgentApprovalStore(agentApprovalStore)
        .withSessionApprovalStore(sessionApprovalStore)
        .withSkillInvocationPolicy(skillInvocationPolicy)
        .withToolContextEnrichers(toolContextEnrichers)
        .withRewakeService(rewakeService)
        .withWorkflowRunnerEnabled(enableWorkflow || enableWorkflowJs);

// AgentRuntimeId is not an argument — it is derived from the agent inside createAgentRuntime.
final OrcaAgentRuntime agentRuntime = createAgentRuntime(
    agentRuntimeFactory, agentExecutor,
    schedulingEngine.getTaskManager(), agentBundle, fileSystem,
    config, graalJsEngines);

configureHooks(agentRuntime, outputFormatter);
final HookHotReloadBootstrap.Started hookHotReload = setupHookHotReload(...);
registerCliTools(agentRuntime, outputFormatter, ...);
configureSchedulingEventListener(schedulingEngine, config);
agentRuntimeRegistry.register(agentRuntime);
```

**The caller does not build an `AgentRuntimeId` and pass it in.** `createAgentRuntime` derives it internally
with `AgentRuntimeId.from(agentBundle.getAgent())`. Being deterministic is the point — the form is fixed as
`agent:<name>` or `agent:<name>:<discriminator>`, so when cron re-fires long after the original session
ended, `ScheduledTask.boundRuntimeId` still resolves to the same runtime. **There is no such thing as
`generate()`** — had there been, that re-fire is exactly what would have broken. Use
`from(agent)` / `from(agent, discriminator)` / `fromName(name)` / `of(value)` as needed.

`OrcaAgentRuntime` is an **agent-scoped** object — one per `(Agent, discriminator)`, shared by every session of that agent. Do not build one per session. It holds:

- The `Agent` definition + the system prompt
- `ToolRegistry` (the built-in tools + the CLI tools)
- `SkillRegistry`
- `HookRegistry`
- `CommandRegistry`, `SubagentRegistry`
- `VirtualFileSystem`
- `McpClientManager` (if MCP servers are configured)
- `KnowledgeStore` (the wiki store)

The default tool providers come from `OrcaAgentRuntimeFactory.defaultToolProviders()` — `Read`, `Write`, `Edit`, `Bash`, `Grep`, `Glob`, `Todo`, `Subagent`, `Skill`, `Scheduling` and so on.

**Adaptation points:**
- Adding your own tool: `agentRuntime.getToolRegistry().register(myTool)` (see the `registerCliTools` pattern).
- Adding your own hook: `agentRuntime.getHookRegistry().register(HookEventType.PRE_TOOL, hook)` (see the `configureHooks` pattern). There are no per-event `register*` methods; you register with a single type token.
- Disabling some tools: pass a custom `ToolProvider` list to `agentRuntimeFactory.create(...)`.

### 4.8 Creating the LiveSession (line 865-866)

```java
final LiveSession liveSession = new DefaultLiveSession(
    sessionId,                       // SessionId.of("default") — line 773
    agentRuntime,
    agentExecutor,
    LiveSessionOptions.defaults(),
    messageQueueManager,
    null,                            // HookExecutionManager (OnSessionStart/End hooks) — unused by the CLI
    sessionRecordStore);             // where the session's persistent side fields live
```

`DefaultLiveSession` offers 4-, 5-, 6- and 7-arg constructors. The last three arguments are switches that turn
on `MessageQueueManager` (mid-turn queueing), `HookExecutionManager` (session hooks) and `SessionRecordStore`
(hydrating the persistent side fields) respectively; omit one and only that feature is off. Use the 4-arg form,
for example, and `offerAsync` never queues — it always returns `SubmitOutcome.Kind.EXECUTED`.

`LiveSession` is the entry API you will meet most often from the outside. One `submit(input)` call runs one turn (which contains several ReAct iterations). For details see [agent-session-guide.en.md](../features/session/agent-session-guide.en.md).

> **A `LiveSession` is not a session; it is a handle on one.** The persistent aggregate is the
> `SessionRecord` identified by `SessionId`, and `LiveSession` is the **node-local, transient** object that
> runs turns against that session. The relationship is 1 : 0..N — a session may have zero live handles
> (nobody is talking to it) or several serving it in sequence over time (idle-TTL eviction, process restart,
> handoff between nodes). That distinction is the premise of §6.

### 4.9 Bundling it into an AgentSetup and returning (line 880-889)

`AgentSetup` is the handle on every resource the CLI process created — not only the live session, but the agent scope and the application scope as well (a simplification the CLI can afford because process = one session). It is `AutoCloseable`, so closing it with try-with-resources tears things down in this order (`AgentSetupFactory.java:320-428`):

```java
 1. memoryFinalDerivation.run()      // queue the final derivation while the transcript is still alive
 2. memoryQueue.stop()               // drain in-flight derivations (before the stores they depend on)
 3. dreamerSubsystem.close()
 4. memoryMaintenance.close()
 5. liveSession.close()              // handle resources only — does not close OrcaAgentRuntime
 6. sessionCheckpoints.close()       // after liveSession — the last end-of-turn save has drained
 7. agentRuntime.close()             // the app is exiting, so agent-scoped resources (MCP, ...) go here
 8. graalJsEngines.close()           // after runtime teardown — no script meets a half-closed engine
 9. agentRuntimeRegistry.unregister(agentRuntime.getId())
10. schedulingEngine.close()         // (CLI only — an embedding must separate this)
11. rewakeService.close()
12. pendingTurnReaper.close()
13. hookHotReload.close()            // before skillHookShell — reload callbacks use the shell
14. skillHookShell.close()
```

Four places in that order have a reason behind them, and every one is backed by a code comment:
**derivations → stores** (2 before 3 and 4), **live session → checkpoint mailbox** (5 before 6),
**runtime → GraalJS engines** (7 before 8), and **hook hot reload → shell** (13 before 14). When you reorder
this in your own shell, preserve those four pairs.

> **The live session closes, but that is not why the runtime closes.** `liveSession.close()` must not call
> `OrcaAgentRuntime.close()` — another session of the same agent may still be using that runtime (the MCP
> subprocesses, the `KnowledgeStore`). **The point is that 5 and 7 are listed separately**: the runtime
> actually closes because the CLI discards the agent as the process exits, not as a consequence of the
> session ending. In an embedding, runtime teardown is the job of
> `OrcaAgentRuntimeManager.destroyRuntime`, on application shutdown or on explicit agent removal.

---

## 5. Adaptation guide, component by component

| Component | What aimon-cli chose | Common alternative in your application |
|-----------|----------------------|----------------------------------------|
| `LlmClient` | An OpenAI or Anthropic SDK wrapper | Your own implementation wrapping an in-house LLM gateway |
| `VirtualFileSystem` | `LocalFileSystem` (relative to the jar directory) | `GridFSFileSystem` / `S3FileSystem` / a per-user isolated instance |
| `VirtualShell` | `LocalShell` | The container-isolated shell from `aimon-sandbox-docker` |
| `SessionRecordStore` | `InMemorySessionRecordStore` | A persistent implementation from `aimon-session-mongodb` / `-postgres` / `-redis` |
| `TranscriptManager` | `DefaultTranscriptManager` (+ the background checkpoint mailbox) | Usually unchanged — what you swap is the `SessionRecordStore` underneath |
| `MessageQueueManager` | in-memory | A distributed queue backend |
| `AgentBundleLoader` | `AdaptiveAgentBundleLoader` (classpath) | Inject an `AgentBundle` built from code or a database |
| `SkillInvocationPolicy` | The interactive ASK policy | A rule-based automatic policy or external approval |
| `SchedulingEngine` | in-memory (the default) | `aimon-scheduling-quartz` (distributed) |
| `KnowledgeStore` | `WikiKnowledgeStore` (file-based) | `aimon-knowledge-opensearch` (vector search) |
| `HookRegistry` | `ToolCallDisplayHook`, `SubagentResultDisplayHook` | Metrics / audit-log / request-response tracing hooks |
| Adding a `Tool` | `ConsoleOutputTool` and friends | Your own business tools (database lookups, in-house API calls, ...) |
| The interaction loop | `ReplSession` (JLine) | An HTTP handler / WebSocket / batch job |

For writing tools follow [tool-development-guide.en.md](../features/tool/tool-development-guide.en.md), for hooks [hook-development-guide.en.md](../features/hook/hook-development-guide.en.md), and for LLM adapters [llm-provider-development-guide.en.md](../features/llm/llm-provider-development-guide.en.md).

---

## 6. Lifecycle and scopes

`aimon-cli` is one process = one session, so it builds everything at once and closes everything at once. **An embedding has to separate the four scopes cleanly** — copy the CLI verbatim and the agent scope collapses into the live-session scope.

The full normative rules are in [scope-model.en.md](../overview/scope-model.en.md). What follows is a summary mapped onto the CLI code.

### Application scope (process lifetime)

Created once and shared by every agent and every session:

- `LlmClient`
- `OrcaAgentExecutor`
- `SchedulingEngine` + `ScheduledTaskManager`, `RoutineExecutor`
- `AgentRuntimeRegistry`
- `SessionRecordStore`, `SessionLeaseStore`, `TranscriptManager`
- `AgentBundleLoader`, `AgentBundle` (if the definition does not change)
- The pool of `MessageQueueManager` instances

### Agent scope (`(Agent, discriminator)` lifetime)

Created **once per agent** and shared by every session of that agent. Not closed when a session ends:

- `OrcaAgentRuntime`
- That runtime's `McpClientManager` and MCP clients (the subprocesses outlive a session)
- `KnowledgeStore` (when it is split per agent)
- The per-runtime `ToolRegistry` / `HookRegistry`
- `WorkflowRunner` (the agent-scoped variant — when enabled with `withWorkflowRunnerEnabled`)

Create and look up with `OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)` — as the name says, an existing one is reused. Tear down only with `destroyRuntime`, on application shutdown or explicit agent removal.

> **`OrcaAgentRuntime.close()` does not scan for `AgentScoped` implementations** — it closes a hardcoded
> list only (`mcpClientManager`, `workflowRunner`, `ownedShell`). If you add a new agent-scoped component
> holding a native resource (a connection pool, a watcher thread), you have to add it to that list yourself.
> The marker interface is documentation, not automatic teardown. `ownedShell` is the only conditional one of
> the three — it is null when an assembly handed in a shell with `withShell(...)`, and closing that shell is
> then the giver's job.

### Session scope (`SessionId` lifetime — **persistent**)

Kept for as long as the session exists, and **surviving** restarts, evictions and node moves:

- `SessionRecord` (the message history)
- `SessionTotals`, `budgetOverride` — the record's side fields
- `SessionTranscript`

The point is that these values live on the record and not on the `LiveSession`. The live session writes the latter two back, one pair at a time, with `SessionRecordStore.setTotalsAndBudgetOverride`.

### Live-session scope (the lifetime of one connection — node-local)

Created per handle, cleaned up with `close()`:

- `LiveSession`
- The message-queue subscription and the event publisher
- The turn-tracking state that handle created

`LiveSession` is a **node-local, transient** handle. One session (`SessionId`) may have zero live handles, or several over time (idle-TTL eviction, restart, node move). **Values that must survive a restart belong on the `SessionRecord`, not on the handle.**

> When naming a new type: if it must persist, `Session*` (`at.aimon.core.agent.session[.store|.transcript]`);
> if it may die with the process, `LiveSession*` (`at.aimon.core.agent.session`); if it is gathered once per
> agent, `Agent*` (`at.aimon.core.agent`). **The bare word `Session` and `AgentSession` are forbidden as type
> names** and `SessionNamingArchitectureTest` blocks them at build time — those two names are precisely what
> make the two lifetimes impersonate each other. By contrast "conversation" is still a valid word and means
> **the message exchange with the LLM** (`getConversationHistory()`, `/compact`'s "Conversation compacted").
> Do not use it to mean a lifetime.

> **Do not infer a lifetime from the last noun in a name.** `*Store` / `*Registry` / `*Manager` / `*Factory`
> is a **container** that manages X, and the container's own lifetime is not X's lifetime — `SessionRecordStore`
> has per-session entries but an application-scoped instance, and so does `AgentRuntimeRegistry`. Judge by
> **what it is keyed by**, not by the name: `Map<AgentRuntimeId, _>` is agent-scoped, `Map<SessionId, _>` is
> session-scoped.

### Wrong patterns

```java
// Wrong (1): closing the SchedulingEngine from a live session's close()
//            kills every other session's scheduled tasks
try (AgentSetup setup = factory.create(config)) {
    // ...
}
// → setup.close() calls schedulingEngine.close() (the CLI assumption)

// Wrong (2): building a runtime per session and closing it in the session's close()
OrcaAgentRuntime rt = factory.create(...);   // restarts the MCP subprocesses every session
liveSession.close();
rt.close();   // cuts off the MCP/KnowledgeStore another session of the same agent was using

// Wrong (3): holding the session totals inside the live session
//            they vanish silently when the handle is evicted or the node changes. Put them on the SessionRecord.
```

### The embedding pattern

```java
// Once, at application startup
SchedulingEngine engine = SchedulingEngineBuilder.create()
    .agentRuntimeRegistry(registry).build();
engine.start();
LlmClient llmClient = new OpenAILlmClient(openAiConfig);
OrcaAgentExecutor executor = ...;
SessionRecordStore sessionRecords = ...;   // app-scoped. Persistent if it must survive a restart

// Once per agent (reused if it already exists)
OrcaAgentRuntime runtime = runtimeManager.getOrCreateRuntime(agentBundle, ...);

// Per connection — the runtime is neither built nor closed here
LiveSession session = new DefaultLiveSession(
    SessionId.of(userId), runtime, executor, LiveSessionOptions.defaults(),
    queueManager, null, sessionRecords);
try {
    AgentExecutionResult result = session.submit(input);   // synchronous — returns when the turn ends
} finally {
    session.close();              // handle resources only; the runtime stays alive
}

// Once, at application shutdown
runtimeManager.destroyRuntime(runtime.getId());   // only now are MCP/KnowledgeStore released
engine.close();
```

For the embedding patterns in detail see [embedding-agent-in-application.en.md](embedding-agent-in-application.en.md).

---

## 7. Minimal embedding example

`aimon-cli`'s bootstrap compressed into its simplest possible form. Minimal code that actually runs.

```java
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliConfigLoader;
import at.aimon.cli.factory.AgentSetupFactory;
import at.aimon.cli.factory.AgentSetupFactory.AgentSetup;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.LiveSession;

public class MyEmbeddedAgent {
    public static void main(String[] args) throws Exception {
        // 1. Load the configuration (your own YAML, or built in code)
        CliConfig config = new CliConfigLoader().loadDefault();

        // 2. Create the AgentSetup exactly once (application scope)
        try (AgentSetup setup = new AgentSetupFactory().create(config)) {

            // 3. Submit input through the live session.
            //    submit(...) is synchronous — it blocks until the turn ends and returns the result.
            LiveSession session = setup.getLiveSession();
            AgentExecutionResult result =
                session.submit("Read the README.md and summarize it");

            // 4. Use the result
            if (result.isSuccess()) {
                System.out.println(result.getFinalAnswer());
            } else {
                System.err.println(result.getErrorMessage());
            }
        }
    }
}
```

If you want to run asynchronously while receiving events, `submitAsync(input, listener)` returns a
`CompletionStage<AgentExecutionResult>` — that is the counterpart to the synchronous `submit` above.

```java
CompletionStage<AgentExecutionResult> stage = session.submitAsync(
    "Read the README.md and summarize it",
    event -> System.out.println(event));   // token deltas, tool calls, iteration progress ...
AgentExecutionResult result = stage.toCompletableFuture().get();
```

For a **more aggressive embedding** (swapping in your own components), move the body of `AgentSetupFactory`'s `create()` into your own composition root and inject your implementations stage by stage. Follow the [4. `AgentSetupFactory.create()` line by line](#4-agentsetupfactorycreate-line-by-line) section above as-is.

---

## 8. Moving to a web application

`aimon-cli`'s `AgentSetupFactory.create()` builds the application, agent and session scopes as one lump — possible only because of the single-user / single-process assumption. On the web you have to split them apart so that **app-scoped beans are built once, agent-scoped runtimes once per agent, and only the live session per user connection**. This section shows that split in four steps.

1. [Component scope separation table](#81-component-scope-separation-table)
2. [The Spring Boot composition root](#82-the-spring-boot-composition-root)
3. [`LiveSession` ↔ HTTP/SSE adapter](#83-livesession--httpsse-adapter)
4. [Non-interactive skill approval channel](#84-non-interactive-skill-approval-channel)

> This section shows the **hand-assembled** path. If a multi-node deployment also needs session routing,
> leases and handoff, `aimon-session-routing`'s `SessionRouter` (`SessionRouter.builder()`) already
> implements that layer — for the operational configuration see
> [web-session-deployment-guide.en.md](../features/session/web-session-deployment-guide.en.md).

### 8.1 Component scope separation table

| Component | Scope | Kind of bean | Notes |
|-----------|-------|--------------|-------|
| `LlmClient` | app | `@Bean(destroyMethod = "close")` | Holds the SDK connection pool. Shared by every user |
| `OrcaAgentExecutor` | app | `@Bean` singleton | Stateless. Shared by every session |
| `SchedulingEngine` | app | `@Bean(initMethod = "start", destroyMethod = "close")` | **Never tie it to a session's close()** |
| `AgentRuntimeRegistry` | app | `@Bean` singleton | Used by `SchedulingEngine` for lazy lookup |
| `AgentBundleLoader`, `AgentBundle` | app | `@Bean` singleton | Load once if the definition is static |
| `PendingTurnReaper` | app | `@Bean(initMethod = "start", destroyMethod = "close")` | One daemon thread is enough |
| `LocalShell` (for skill hooks) | app | `@Bean(destroyMethod = "close")` | Shares an I/O thread pool |
| `SessionRecordStore` | app | `@Bean` singleton | **Entries are per-session, the instance is app-scoped.** A persistent Mongo/Postgres/Redis implementation is mandatory across multiple instances |
| `TranscriptManager` | app | `@Bean` singleton | The default implementation wrapping the store above |
| `PendingTurnRegistry`, `AgentApprovalStore`, `SessionApprovalStore` | app | `@Bean` singleton | Entries are keyed by pending turn / `AgentRuntimeId` / `SessionId` respectively, but **the instances are app-scoped**. Use distributed backends if routing is not guaranteed in a cluster |
| **`OrcaAgentRuntime`** | **agent** | `OrcaAgentRuntimeManager.getOrCreateRuntime()` | One per `(Agent, discriminator)`. Owns MCP and the KnowledgeStore. **Do not close it from a live session's close()** |
| **`OrcaAgentRuntimeManager`** | app | `@Bean` singleton | Owns the creation, caching and teardown of agent-scoped runtimes |
| **`MessageQueueManager`** | **live session** | Created fresh by a factory each time | The producer (HTTP) and the consumer (the executor) must share the same instance |
| **`LiveSession`** | **live session** | Created fresh by a factory each time | 0..1 at a time per `SessionId`, N over time |
| **`VirtualFileSystem`** | **user/agent** | Per-user separation recommended | Separate GridFS buckets / S3 prefixes |

> The four most common mistakes:
> - Creating `OrcaAgentExecutor`/`LlmClient` per connection — expensive and pointless.
> - Making `MessageQueueManager` an app-scoped singleton — another user's mid-turn input leaks in.
> - **Making `OrcaAgentRuntime` live-session-scoped** — MCP subprocesses restart on every connection, and when one handle closes it cuts off the MCP and KnowledgeStore another session of the same agent was using. If you need to split `VirtualFileSystem` per user, pass the user as a discriminator and use `getOrCreateRuntime(bundle, userId, fs, store)` — one per user, not one per connection.
> - **Holding values that must survive a restart inside `LiveSession`** — accumulated tokens/cost and the
>   budget override belong on the record in `SessionRecordStore`. Kept inside the handle, they vanish
>   silently on the first idle-TTL eviction.

### 8.2 The Spring Boot composition root

#### App scope — `@Configuration`

```java
@Configuration
public class AimonAppConfig {

    @Bean(destroyMethod = "close")
    public LlmClient llmClient(@Value("${aimon.openai.key}") String apiKey,
                               @Value("${aimon.openai.model:gpt-5.1}") String model) {
        return new OpenAILlmClient(OpenAIConfig.builder()
            .apiKey(apiKey)
            .model(model)
            .timeout(Duration.ofSeconds(60))
            .build());
    }

    @Bean
    public AgentRuntimeRegistry agentRuntimeRegistry() {
        return new DefaultAgentRuntimeRegistry();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public SchedulingEngine schedulingEngine(AgentRuntimeRegistry registry) {
        return SchedulingEngineBuilder.create().agentRuntimeRegistry(registry).build();
    }

    @Bean
    public SessionRecordStore sessionRecordStore() {
        // In a multi-instance environment, swap for an aimon-session-mongodb / -postgres / -redis
        // implementation. The instance is app-scoped; only the entries split by SessionId.
        return new InMemorySessionRecordStore();
    }

    @Bean(destroyMethod = "close")
    public SessionCheckpointMailbox sessionCheckpoints() {
        // Flushes asynchronously on its own thread so appended messages survive a mid-turn crash.
        return SessionCheckpointMailbox.background();
    }

    @Bean
    public TranscriptManager transcriptManager(SessionRecordStore store,
                                               SessionCheckpointMailbox checkpoints) {
        return new DefaultTranscriptManager(store, checkpoints);
    }

    @Bean
    public OrcaAgentExecutor agentExecutor(LlmClient llmClient,
                                           TranscriptManager transcriptManager) {
        return new OrcaAgentExecutorFactory()
            .withUseStreaming(true)
            .create(llmClient, transcriptManager);
    }

    @Bean(destroyMethod = "close")
    public VirtualShell skillHookShell() {
        return new LocalShell();
    }

    @Bean
    public SkillParser skillParser(VirtualShell skillHookShell) {
        return new MarkdownSkillParser(
            new ShellArgumentTokenizer(),
            new SkillHookSetParser(new DefaultShellActionExecutor(skillHookShell)));
    }

    @Bean
    public AgentBundle defaultAgentBundle(SkillParser skillParser) {
        return new AdaptiveAgentBundleLoader(
            "agents", new MarkdownAgentDefinitionParser(),
            getClass().getClassLoader(), skillParser).load("default");
    }

    @Bean
    public PendingTurnRegistry pendingTurnRegistry() {
        return new InMemoryPendingTurnRegistry();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public PendingTurnReaper pendingTurnReaper(PendingTurnRegistry registry) {
        return PendingTurnReaper.builder()
            .registry(registry)
            .interval(Duration.ofSeconds(60))
            .expirationListener(turns -> { /* metrics/logging */ })
            .build();
    }

    @Bean
    public SessionApprovalStore sessionApprovalStore() {
        // at.aimon.core.skill.policy.session — the narrow one, keyed by SessionId
        return new InMemorySessionApprovalStore();
    }

    @Bean
    public AgentApprovalStore agentApprovalStore() {
        // at.aimon.core.skill.policy.agent — the wide one, keyed by AgentRuntimeId
        return new InMemoryAgentApprovalStore();
    }

    @Bean
    public SkillInvocationPolicy skillInvocationPolicy(
            SessionApprovalStore sessionApprovals, AgentApprovalStore agentApprovals) {
        // See 8.4 — an automatic policy, or ASK + suspend/resume.
        // Narrow first: session approvals → agent-wide approvals → rules. Reverse this order
        // and a per-session denial can never be reached.
        return new SessionScopedSkillInvocationPolicy(sessionApprovals,
            new ApprovalCachingSkillInvocationPolicy(agentApprovals,
                RuleBasedSkillInvocationPolicy.builder()
                    .defaultDecision(SkillInvocationDecision.ASK).build()));
    }
}
```

#### Agent-scoped runtime — once per user (not once per request)

`OrcaAgentRuntimeManager` is an app-scoped singleton bean. `getOrCreateRuntime()` does exactly what the name says — a cache lookup first, creation only on a miss — and registers with the registry internally, so the caller does not need a separate `register()` call. If you need per-user VFS separation, **pass the userId as a discriminator instead of creating a new runtime per connection**.

```java
@Bean
public OrcaAgentRuntimeManager agentRuntimeManager(
        OrcaAgentExecutor executor, AgentRuntimeRegistry registry,
        SchedulingEngine schedulingEngine,
        SkillInvocationPolicy skillPolicy, SessionApprovalStore sessionApprovals,
        AgentApprovalStore agentApprovals, PendingTurnRegistry pendingTurnRegistry) {

    // withSkillRegistry() is deliberately not called — the VFS differs per user, so the skill
    // registry has to differ per runtime too. Omit it and the factory builds a fresh one per
    // runtime from (agentBundle, fileSystem).
    OrcaAgentRuntimeFactory runtimeFactory =
        new OrcaAgentRuntimeFactory("1.0.0",
            ".aimon/commands", ".aimon/agents", ".aimon/skills",
            /* knowledgeStore */ null)
            .withSessionApprovalStore(sessionApprovals)
            .withAgentApprovalStore(agentApprovals)
            .withPendingTurnRegistry(pendingTurnRegistry)
            .withSkillInvocationPolicy(skillPolicy);

    return OrcaAgentRuntimeManager.builder()
        .agentExecutor(executor)
        .agentRuntimeRegistry(registry)
        .agentRuntimeFactory(runtimeFactory)
        .scheduledTaskManager(schedulingEngine.getTaskManager())
        .toolProviders(OrcaAgentRuntimeFactory.defaultToolProviders())
        .commandProviders(OrcaAgentRuntimeFactory.defaultCommandProviders())
        .build();
}
```

#### The live-session factory — a new handle per connection (the runtime is reused)

```java
@Component
public class WebLiveSessionOpener {

    private final OrcaAgentExecutor executor;
    private final OrcaAgentRuntimeManager runtimeManager;
    private final AgentBundle agentBundle;
    private final CredentialStore credentialStore;
    private final SessionRecordStore sessionRecords;   // app-scoped — only injected
    private final VirtualFileSystemProvider fsProvider;

    // constructor injection omitted

    public LiveSession openFor(String userId, SessionId sessionId) {
        VirtualFileSystem userFs = fsProvider.forUser(userId);  // per-user GridFS bucket / S3 prefix

        // Agent scope: actually created only once per userId. From the second session on, the cached
        // instance comes back, so the MCP subprocesses are not restarted either.
        OrcaAgentRuntime runtime =
            runtimeManager.getOrCreateRuntime(agentBundle, userId, userFs, credentialStore);

        // Live-session scope: new per handle. The producer (HTTP) and the consumer (the executor)
        // have to see the same instance.
        MessageQueueManager queueManager = new DefaultMessageQueueManager(
            new InMemoryMessageQueueRepository());

        // The last argument is the SessionRecordStore. Pass it and the session totals (SessionTotals)
        // and budgetOverride are restored on open and written back at the end of every turn. Omit it
        // (null) and those values disappear the moment this handle closes — they have to survive
        // restarts and evictions, so on the web you always pass it.
        return new DefaultLiveSession(sessionId, runtime, executor,
            LiveSessionOptions.defaults(), queueManager, /* hookExecutionManager */ null, sessionRecords);
    }

    public void close(LiveSession session) {
        session.close();   // cleans up the live-session scope only
        // Do not unregister/close the runtime — another session of the same user is still using it.
        // Never close the SchedulingEngine either — that is @PreDestroy's job.
    }
}
```

> Call `runtimeManager.destroyRuntime(AgentRuntimeId.from(agent, userId))` only when the user logs out or when you reclaim idle users, and only after **every** live session of that user has closed. The reason you can **recompute** the id as `AgentRuntimeId.from(agent, userId)` and pass it in is that issuing it is deterministic — there is no `generate()`.

### 8.3 `LiveSession` ↔ HTTP/SSE adapter

A web client has to receive several events per message — token deltas, tool calls, iteration progress, completion. `LiveSession.submitAsync(input, listener)` supports exactly that (`LiveSession.java:162`).

#### The live-session store

```java
@Component
public class LiveSessionRegistry {

    private final Map<SessionId, LiveSession> live = new ConcurrentHashMap<>();
    private final WebLiveSessionOpener opener;

    public LiveSession getOrOpen(String userId, SessionId sessionId) {
        return live.computeIfAbsent(sessionId, id -> opener.openFor(userId, id));
    }

    public Optional<LiveSession> peek(SessionId sessionId) {
        return Optional.ofNullable(live.get(sessionId));
    }

    public void close(SessionId sessionId) {
        LiveSession session = live.remove(sessionId);
        if (session != null) {
            opener.close(session);   // only the handle closes. The runtime and the session record live on
        }
    }
}
```

> In production, add policies such as TTL eviction (30 minutes idle, say), a maximum number of handles per user, and a bulk close on instance shutdown. Binding `opener.close()` to a Caffeine/Guava cache removalListener is a common pattern. **This eviction policy applies to live sessions only** — when a handle disappears on TTL the agent-scoped runtime stays, so the same user reconnecting resumes immediately with no MCP restart. If you need to reclaim runtimes, hang `destroyRuntime` off a separate (and much longer) idle policy.

> The point of this whole subsection is that this map is keyed by `SessionId` while its values have a shorter lifetime. **For one `SessionId` there are 0..1 live handles at a time and N over time** — every eviction, reconnect and process restart hands the same session to a new handle. So values that must survive that handover, such as accumulated tokens and cost or the budget override, belong on the record in `SessionRecordStore` rather than in this map (the very store `WebLiveSessionOpener` passes as its last argument in 8.2).

#### The SSE controller (Spring WebMVC)

```java
@RestController
@RequestMapping("/agent/sessions/{sessionId}")
public class AgentChatController {

    private final LiveSessionRegistry sessions;

    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable String sessionId,
                                  @AuthenticationPrincipal Principal user,
                                  @RequestBody MessageRequest body) {

        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        LiveSession session = sessions.getOrOpen(user.getName(), SessionId.of(sessionId));

        // offerAsync stacks input into the mid-turn queue if the session is already running a turn
        SubmitOutcome outcome = session.offerAsync(body.text(), event -> {
            try {
                emitter.send(SseEmitter.event()
                    .name(event.getClass().getSimpleName())
                    .data(EventDto.from(event)));   // serialise with your own DTO
            } catch (IOException ignored) {
                // if the client disconnects the next event fails again — clean up then
            }
        });

        if (outcome.getKind() == SubmitOutcome.Kind.QUEUED) {
            try {
                emitter.send(SseEmitter.event().name("queued")
                    .data("Session busy, queued at position " + outcome.getQueuePosition()));
            } catch (IOException ignored) {}
        }

        // Send the id of the turn that just started down to the client — /interrupt below gets it back.
        // Only when EXECUTED: if it was QUEUED the turn currently running is someone else's, and telling
        // the user it is this response's turn means their "stop" click kills an innocent turn.
        // This is a best-effort value too (see §Concurrency notes), so if it is empty, just do not send it.
        if (outcome.getKind() == SubmitOutcome.Kind.EXECUTED) {
            session.currentTurnId().ifPresent(turnId -> {
                try {
                    emitter.send(SseEmitter.event().name("turn").data(turnId.value()));
                } catch (IOException ignored) {}
            });
        }

        // getResultStage() is an Optional — a QUEUED outcome has no stage to attach yet.
        // The result of queued input arrives over the event stream when the session actually starts that turn.
        outcome.getResultStage().ifPresent(stage -> stage.whenComplete((result, ex) -> {
            try {
                if (ex != null) {
                    emitter.completeWithError(ex);
                } else {
                    emitter.send(SseEmitter.event().name("done")
                        .data(ResultDto.from(result)));
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }));

        return emitter;
    }

    @PostMapping("/interrupt")
    public ResponseEntity<Void> interrupt(@PathVariable String sessionId,
                                          @RequestBody InterruptRequest body) {
        // Use the addressed form. The no-arg interrupt(reason) cuts "whatever turn is running right now",
        // which fits administrative purposes (eviction, shutdown, lease loss) but is wrong for
        // "stop the turn I sent" — that turn may have finished and the next one started before the
        // user's click arrived. Pass a turnId and a mismatch becomes a quiet no-op instead of
        // killing an innocent turn.
        sessions.peek(SessionId.of(sessionId)).ifPresent(session ->
            session.interrupt(TurnId.of(body.turnId()), InterruptReason.USER_SIGINT));
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> close(@PathVariable String sessionId) {
        sessions.close(SessionId.of(sessionId));   // closes the handle only — the session record remains
        return ResponseEntity.noContent().build();
    }
}
```

> `InterruptReason` is an **enum** — there is no factory taking a free-form reason string. A web "stop" button should use `USER_SIGINT` ("SIGINT on the CLI host, **or equivalent**"), and for administrative purposes there are separate `SESSION_RELEASED` / `SYSTEM_SHUTDOWN` / `LEASE_LOST` / `HOLDER_LOST`. This call cannot tell you whether the interrupt actually landed (that is inherently racy) — observe the turn's completion event instead.

#### Concurrency notes

- `LiveSession` **offers no thread-safety guarantee** (`LiveSession.java:42-47`). One handle must run one turn at a time.
- When concurrent requests arrive for the same `SessionId`, use **`offerAsync` rather than `submitAsync` so they stack into the mid-turn queue**. Check `SubmitOutcome.getKind()` for immediate execution (`EXECUTED`) versus waiting (`QUEUED`).
- **`status()` and `currentTurnId()` are not control gates.** Both are best-effort observations read without synchronisation, so they can be briefly out of step with a turn that is settling. "May I start a turn" is answered only by the `SubmitOutcome` that `offerAsync` returns.
- An interrupt is not a synchronous call — for one turn **only the first trip means anything** (later calls are idempotent no-ops), and the actual stop lands at the next ReAct iteration or when the tool finishes. With no active turn it is a quiet no-op and throws nothing.
- Across multiple instances there are two roads:
  - **Session-affinity routing**: a `SessionId` → instance mapping (sticky sessions, gateway routing rules). If you also need leases and handoff, do not write it yourself — use `SessionRouter`.
  - **Making the handle stateless**: rebuild `LiveSession` on every request and push all state into `SessionRecordStore`. Be clear about the trade-off, though: in-memory state such as mid-turn interrupts and queueing disappears.

### 8.4 Non-interactive skill approval channel

`SkillApprovalChannel` is a **synchronous interface** (the "Stay synchronous" contract in `SkillApprovalChannel.java`):

> "Stay synchronous. The scanner blocks on this call. Implementations that genuinely need async resolution should not implement this interface; they should let the suspend/resume path run instead."

In a web environment this synchronous contract forks the road in two.

#### Option A — rule-based automatic decisions (automated workflows)

If your own policy can decide immediately, the synchronous channel is clean.

```java
public class PolicyBasedApprovalChannel implements SkillApprovalChannel {

    private final SessionApprovalStore sessionApprovals;   // the narrow one — keyed by SessionId
    private final AgentApprovalStore agentApprovals;       // the wide one — keyed by AgentRuntimeId
    private final SkillPolicyEvaluator evaluator;

    // The 2-arg form is the interface's abstract method. An implementation that knows about sessions
    // overrides the 3-arg one and lets the 2-arg one delegate as "a call with no session".
    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests,
                                AgentRuntimeId agentRuntimeId) {
        requestApproval(pendingRequests, agentRuntimeId, null);
    }

    @Override
    public void requestApproval(List<PendingSkillRequest> pendingRequests,
                                AgentRuntimeId agentRuntimeId, SessionId sessionId) {
        for (PendingSkillRequest req : pendingRequests) {
            // Never throw — on failure record the safe default (DENY) (SkillApprovalChannel's "Never throw" contract)
            SkillInvocationDecision decision;
            try {
                decision = evaluator.evaluate(req.getSkillName(), req.getArgs());
            } catch (Exception e) {
                decision = SkillInvocationDecision.DENY;
            }
            // The scanner does not read the channel's return value. You must write into a store the
            // policy chain reads, and a skill you did not write becomes a plain ASK again at the next check.
            if (sessionId != null) {
                sessionApprovals.put(sessionId, req.getSkillName(), decision);
            } else {
                // Calls with a null sessionId really do happen — executions that are not a user-driven
                // turn, such as scheduled tasks. Do not silently drop them; record on the wide side at least.
                agentApprovals.put(agentRuntimeId, req.getSkillName(), decision);
            }
        }
    }
}
```

> **IMPORTANT — an approval put into `AgentApprovalStore` never expires**: the key is
> `AgentRuntimeId` (`agent:<name>[:<discriminator>]`), so a decision recorded here **applies to every later
> session of that agent**, has no TTL, and is not cleared by `/clear`. Use this store only when the user has
> **explicitly answered "always in this agent"** — the user cannot see the other sessions their answer will
> reach, so an ordinary "yes" must not be promoted into this scope. For "allow in this session only", use the
> per-session `SessionApprovalStore` (`at.aimon.core.skill.policy.session`); wrap it in
> `SessionScopedSkillInvocationPolicy` and the policy chain looks there first (see the chain in 8.2).
> The way back is each store's `invalidate(...)`, which the CLI exposes as `/revoke` (session) and
> `/revoke --agent` (agent-wide) — if you build a web UI, you must offer the equivalent cancel buttons too.
>
> The reach of a per-session approval is **that session and the executions that session delegated**
> (subagent forks, skill forks, foreground workflows). It is easy to misread *how* it reaches them, though —
> **a fork does not have its own `SessionId`.** `DefaultSubagentExecutor` does not put `SESSION_ID` into the
> tool context at all; it exposes the execution identity `ExecutionId` as `EXECUTION_ID`, and the id of the
> **user session** that launched it as `INVOKING_SESSION_ID`. The policy finds its answer through the latter.
> When a fork launches another fork, the user's session id is passed straight through rather than the
> intermediate fork's. A fork has no channel to ask a human — nor should the channel be reachable from a
> fork, since the user is not looking at that screen — so without this path every skill call from a fork is
> blocked.
>
> The two ids run on different axes: `sessionId` is *lifetime* (what my session is) and `invokingSessionId`
> is *reach* (whose decision applies to me). And **the wire keys are still `"conversationId"` /
> `"invokingConversationId"`** — only the Java identifiers were renamed; the serialised names are
> deliberately frozen for compatibility. The stored names looking out of step with the type names is normal.
>
> **Take special care here, because the name was reused**: `SessionApprovalStore` used to be the name of the
> **agent-wide** store (keyed by `AgentRuntimeId` while the name said session). That is now
> `AgentApprovalStore`, and the vacated name was reattached to **the real per-session store**. If you see
> `SessionApprovalStore` in old code or old documents, it may be today's `AgentApprovalStore` — tell them
> apart by package (`…policy.agent` vs `…policy.session`) and key type. The old
> `ConversationApprovalStore` is today's `SessionApprovalStore`, and the old
> `ConversationAwareSkillInvocationPolicy` is today's `SessionScopedSkillInvocationPolicy`.

When the rules are simple, you often need no channel at all and `RuleBasedSkillInvocationPolicy` finishes the job — if the policy returns ALLOW/DENY directly instead of ASK, the channel is never called. Rules are **glob patterns on skill names**, not arbitrary lambdas, and they evaluate in the order deny → allow → safe-by-default → `defaultDecision`.

```java
SkillInvocationPolicy autoPolicy = RuleBasedSkillInvocationPolicy.builder()
    .addDenyPattern("dangerous-*")          // highest priority
    .addAllowPattern("report-*")
    .safeByDefault(false)                   // defaults to true — turn it off and ALLOW comes only from explicit allow patterns
    .defaultDecision(SkillInvocationDecision.DENY)  // when no rule matched. The default is DENY too (fail-closed)
    .build();
```

> `defaultDecision` defaults to `DENY` — the CLI uses `ASK` because an interactive shell has someone to ask, not because that is the framework default. Leave it at `ASK` in an unattended workflow and, absent a channel, it falls into option B's suspend path and the turn stops.

#### Option B — external approval (when a human has to decide)

For flows that need a human click, **do not build a synchronous channel**; use the scanner's fallback, the suspend/resume path. It is the same mechanism as `aimon-cli`'s `/approve`, `/deny` and `/pending` commands — only the input channel changes from the terminal to HTTP.

The flow:

1. The scanner finds no channel (or falls back to DENY) → the turn is registered in `PendingTurnRegistry` and suspended
2. The client receives the pending event from the `events()` stream
3. The user approves or rejects in a separate UI
4. The decision is sent to a backend API
5. The controller records the decision in **the store matching the scope** (`SessionApprovalStore` or `AgentApprovalStore`), then removes the pending entry with `pendingTurnRegistry.remove(turnId)`
6. The client resubmits the same prompt → the scanner asks the policy again, this time gets the cached ALLOW/DENY → the turn proceeds

> A caution about steps 5–6: there is no API such as `resume(turnId)`. `PendingTurnRegistry` is a pure store
> and does not resume execution — recording the approval and removing the entry is the server's share, and the
> actual re-run happens because the client submits the turn again (the CLI's `/approve` likewise only says
> "Resume the agent to continue"; it does not resume by itself).

```java
@RestController
@RequestMapping("/agent/pending/{turnId}")
public class PendingApprovalController {

    private final PendingTurnRegistry pendingTurns;
    private final SessionApprovalStore sessionApprovals;
    private final AgentApprovalStore agentApprovals;

    @PostMapping("/decide")
    public ResponseEntity<Void> decide(@PathVariable String turnId,
                                       @RequestBody ApprovalRequest body) {
        PendingTurn pending = pendingTurns.get(PendingTurnId.of(turnId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // A pending turn knows which session's turn it was — but it is an Optional.
        // It is empty when the turn was not user-driven (a scheduled task, say).
        Optional<SessionId> sessionId = pending.getSessionId();

        for (PendingSkillRequest req : pending.getPendingSkills()) {
            SkillInvocationDecision decision = body.allows(req.getSkillName())
                ? SkillInvocationDecision.ALLOW
                : SkillInvocationDecision.DENY;

            if (sessionId.isPresent() && !body.forWholeAgent()) {
                // The default path. Applies only to this session and the executions it delegated.
                sessionApprovals.put(sessionId.get(), req.getSkillName(), decision);
            } else {
                // Careful: this decision lands on all of pending.getAgentRuntimeId(), not this session,
                // and never expires. Come down this branch only when the user explicitly answered
                // "always in this agent".
                agentApprovals.put(pending.getAgentRuntimeId(), req.getSkillName(), decision);
            }
        }
        pendingTurns.remove(PendingTurnId.of(turnId));
        return ResponseEntity.accepted().build();
    }
}
```

It is a good idea to open an endpoint for revoking approvals as well — the equivalent of the CLI's `/revoke`. Open it for **both scopes**. Open only the narrow one and the UI has no way back from an agent-wide approval the user clicked by mistake.

```java
@DeleteMapping("/agent/sessions/{sessionId}/approvals")
public ResponseEntity<Void> revokeSession(@PathVariable String sessionId) {
    sessionApprovals.invalidate(SessionId.of(sessionId));      // /revoke
    return ResponseEntity.noContent().build();
}

@DeleteMapping("/agent/{agentRuntimeId}/approvals")
public ResponseEntity<Void> revokeAgent(@PathVariable String agentRuntimeId) {
    agentApprovals.invalidate(AgentRuntimeId.of(agentRuntimeId));   // /revoke --agent
    return ResponseEntity.noContent().build();
}
```

> **A TTL caution**: `PendingTurnReaper` sweeps up pending turns periodically (`AgentSetupFactory.createPendingTurnReaper`, a 60-second sweep by default). If the client leaves the approval UI up too long, the turn expires and the user's decision is ignored — the UX has to show a countdown or an automatic rejection. The sweep interval decides **how quickly expired entries are collected**, not whether something has expired — the actual expiry time is `PendingTurn.getExpiresAt()`.

---

## 9. Other adaptation scenarios

General patterns, CLI or web alike.

### 9.1 Registering your own tool

```java
public class CompanyDirectoryTool extends AbstractTool {
    public static final String TOOL_NAME = "CompanyDirectory";
    private final DirectoryService directory;

    public CompanyDirectoryTool(DirectoryService directory) {
        super(TOOL_NAME,
              "Look up an employee by email or employee ID.",
              createInputSchema());
        this.directory = Objects.requireNonNull(directory);
    }
    // ... the execute implementation
}

// registration
agentRuntime.getToolRegistry().register(new CompanyDirectoryTool(svc));
```

Exactly the pattern in `AgentSetupFactory.registerCliTools()`. There is one `ToolRegistry` per runtime, so **registration happens once, when the runtime is created** — register per connection and the same tool is registered repeatedly. On the web the canonical route is to hand it to `OrcaAgentRuntimeManager.builder().toolProviders(...)` as an `OrcaToolProvider`, and the manager then registers it exactly once for every runtime it creates. (Hooks go in at the same place, via `hookRegistrars(...)`.)

### 9.2 Audit-log hook

```java
// There is no per-event-type register* method — you register with a single type token.
hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) ctx -> {
    auditLog.info("invoker={} tool={} input={} attrs={}",
        ctx.getInvokerName(), ctx.getCurrentToolUse().getName(),
        ctx.getCurrentToolUse().getInput(), ctx.getExecutionAttributes());
    return HookResult.allow();
});
```

For the kinds of hook and what blocking means, follow [hook-development-guide.en.md](../features/hook/hook-development-guide.en.md). **Only `PreToolHook` has a meaningful block (`HookResult.block(reason)`)** — every other hook is non-blocking.

> If you want the user's identity in the audit log, note that you cannot pull it out of the hook context — there is no `getUserId()` on `PreToolContext`. Load it at submit time with `SubmitOptions.builder().executionAttribute("userId", ...)` and it arrives intact in `getExecutionAttributes()`. Hook registration happens against the **agent-scoped `HookRegistry`**, so this too is once per runtime rather than once per connection — user identity has to ride along at submit time, not at registration time.

---

## 10. Checklist

What to check when integrating `aimon-core` into a new host application.

### Dependencies
- [ ] Did you add `aimon-core` as `implementation()`?
- [ ] Did you add at least one LLM implementation module?
- [ ] Did you pick and add the filesystem / scheduling / knowledge modules you need?

### Composition
- [ ] Are `LlmClient`, `OrcaAgentExecutor` and `SchedulingEngine` **application-scoped**?
- [ ] Did you create `AgentRuntimeRegistry` outside and inject it into `SchedulingEngine`?
- [ ] Is `OrcaAgentRuntime` **agent-scoped** and obtained only through `OrcaAgentRuntimeManager.getOrCreateRuntime()`? (You are not creating one per session?)
- [ ] Do you derive `AgentRuntimeId` with `from(agent)` / `from(agent, discriminator)`? (`generate()` does not exist)
- [ ] Do you create a new `LiveSession` per connection but open it with **the same `SessionId`** so it picks up the previous session?
- [ ] Did you pass `SessionRecordStore` to `LiveSession` so totals and the budget override outlive the handle?
- [ ] Is `MessageQueueManager` a **single instance** within the same live session?
- [ ] (web only) You are not creating `LlmClient`/`OrcaAgentExecutor` per user request?
- [ ] (web only) You are not making `MessageQueueManager` an app-scoped singleton?

### Lifecycle
- [ ] On live-session teardown do you call only `liveSession.close()`, and **neither close nor unregister the `AgentRuntime`**?
- [ ] Do you **avoid closing `SchedulingEngine` on live-session teardown**? (the difference from the CLI)
- [ ] Do you tear down `AgentRuntime` only via `destroyRuntime()` at app shutdown or on explicit agent removal?
- [ ] Does your application shutdown hook close `SchedulingEngine`, `LlmClient` and any shared `VirtualFileSystem`?
- [ ] If you added a new agent-scoped component, did you **add it by hand to the hardcoded list in `OrcaAgentRuntime.close()`**? (the `AgentScoped` marker is documentation only — there is no fan-out)
- [ ] (web only) Does your live-session store have idle-TTL eviction, and does eviction close the handle only? (not the runtime as well?)

### Tools / hooks / skills
- [ ] Do your own tools honour the `AbstractTool` contract (throw nothing, return `ToolResult.error()`)?
- [ ] Do you avoid attempting to block from anything but `PreToolHook`?
- [ ] In a headless environment, does the skill approval policy decide automatically or delegate to an external approval system?
- [ ] When recording an approval decision, is the **narrow scope (`SessionApprovalStore`) the default**, with agent-wide used only when the user explicitly answered that way?
- [ ] Is the policy chain arranged **narrow first** (session → agent → rules)? Reverse the order and a per-session denial can never be reached
- [ ] Did you open the revoke path for **both scopes**? (the equivalents of `/revoke` and `/revoke --agent`)
- [ ] (web only) For skills that need a human decision, do you use suspend/resume plus an HTTP decision endpoint rather than a synchronous channel?

### Concurrency / HTTP
- [ ] (web only) Do you queue concurrent requests for the same `SessionId` with `offerAsync`, or reject them explicitly?
- [ ] (web only) Do you judge with `SubmitOutcome` rather than using `status()` / `currentTurnId()` as a control gate?
- [ ] (web only) Does your SSE/WebSocket stream detect client disconnects (`IOException`) and clean up?
- [ ] (web only) Do you send user-initiated interrupts **addressed to a turn** with `interrupt(turnId, reason)`? (the no-arg form is for administrative purposes)
- [ ] (web only) Have you surfaced to the client that `interrupt()` does not guarantee immediate termination?

### Multiple instances (optional)
- [ ] Did you replace the in-memory implementations (`InMemorySessionRecordStore`, `InMemoryMessageQueueRepository`, `InMemoryPendingTurnRegistry`, `InMemoryAgentApprovalStore`, `InMemorySessionApprovalStore`) with distributed backends?
- [ ] Does `SchedulingEngine` use a clusterable implementation (`aimon-scheduling-quartz`)?
- [ ] (web only) Do you use session-affinity routing (`SessionRouter`), or rebuild `LiveSession` statelessly on every request?

---

## References

- Core abstraction reference: [architecture.en.md](../overview/architecture.en.md)
- **The reference document for lifetime, ownership and teardown**: [scope-model.en.md](../overview/scope-model.en.md) — read it before you create a new type or call `close()`
- Glossary: [glossary.en.md](../overview/glossary.en.md)
- SDK embedding patterns (scopes, multi-session, streaming): [embedding-agent-in-application.en.md](embedding-agent-in-application.en.md)
- The `LiveSession` API and event streaming: [agent-session-guide.en.md](../features/session/agent-session-guide.en.md)
- Multi-node session routing and lease operations: [web-session-deployment-guide.en.md](../features/session/web-session-deployment-guide.en.md)
- Tool development: [tool-development-guide.en.md](../features/tool/tool-development-guide.en.md)
- Hook development: [hook-development-guide.en.md](../features/hook/hook-development-guide.en.md)
- LLM provider development: [llm-provider-development-guide.en.md](../features/llm/llm-provider-development-guide.en.md)
- `aimon-cli` entry point: `modules/aimon-cli/src/main/java/at/aimon/cli/AimonCli.java`
- `aimon-cli` composition root: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java`
- `aimon-cli` LLM factory: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/LlmClientFactory.java`
- Default configuration: `modules/aimon-cli/src/main/resources/default-config.yaml`
