---
translated_from: docs/overview/architecture.md
source_commit: a56317a
---

# Architecture

The **reference for `aimon-core`'s core abstractions**. It covers what each interface
contracts, where it lives, and what you can swap in for it.

- To find out what features exist first → [`features.en.md`](features.en.md)
- What sits outside the boundary → [`context.en.md`](context.en.md)
- What happens once you run several nodes → [`deployment.en.md`](deployment.en.md)
- The meaning and lifetime of the terms → [`glossary.en.md`](glossary.en.md)
- Where to put a value and when to close it → [`scope-model.en.md`](scope-model.en.md)

---

## 1. Character

| Property | Detail | What it costs instead |
|------|------|-----------------|
| **Java 17** | Immutable value objects plus the builder pattern (`class` is preferred over `record`) | The boilerplate is written by hand. The one exception is the input DTO of a `GenericTool` |
| **Stateless tools** | A tool holds no state between executions — thread-safe by design | There is no cache between executions. When one is needed it moves to a collaborator **outside** the tool (`WebToolCacheRepository`) |
| **Fail-safe** | `Tool.execute()` never throws; it always returns a `ToolResult` | Failure becomes a value rather than control flow — the caller cannot branch on an exception type and reads `isError()` instead (the original exception stays in `getException()`) |
| **Multi-instance ready** | Stateful components separate their storage behind an interface, so they scale out | Even on a single node every state access passes through a layer of SPI. The price of ruling out sticky routing is accepting that **handles for the same session may be duplicated across nodes** ([`deployment.en.md` §4](deployment.en.md)) |
| **Pluggable backends** | The LLM, filesystem, shell, session store and scheduler are all abstracted | The core alone cannot even call an LLM — the assembly cost of choosing what to attach is always paid ([`context.en.md` §3](context.en.md)) |

The third column of this table is where arc42's quality goals (§1 · §10) belong. **A table
listing only what was gained is marketing copy, not a design document**, so what was given up
is written next to what was won.

## 2. Package layout

```
at.aimon.core/
├── agent/          agent execution — the body of the ReAct loop
│   ├── tool/         the tool abstraction (+ search, permission)
│   ├── session/      the session facade (+ store, transcript)
│   ├── budget/       execution budget and its measurement
│   ├── compact/      context compaction
│   ├── context/      context blocks refreshed during execution
│   ├── stream/       execution events (sealed)
│   ├── interrupt/    cooperative interruption
│   ├── interceptor/  the execution interceptor chain
│   ├── queue/        user input queued mid-turn
│   ├── input/        multimodal input
│   ├── artifact/     collection of execution artifacts
│   ├── prompt/       system prompt assembly
│   ├── definition/   Markdown+YAML agent definitions
│   ├── template/     Mustache rendering
│   ├── orca/         the public Orca tool-provider SPI
│   └── impl/orca/    the Orca executor implementation
├── llm/            the LLM client abstraction (content, cost, retry, streaming, token, usage)
├── tools/          built-in tool implementations (file, bash, web, todo, task, skill, …)
├── skill/          the skill system (execution, fork, hook, policy, render, repository)
├── hook/           lifecycle hooks (event, execution, rewake)
├── subagent/       subagents
├── workflow/       workflow orchestration
├── command/        user slash commands
├── memory/         long-term memory
├── knowledge/      the knowledge store + LLM wiki
├── mcp/            the MCP client
├── scheduling/     scheduled execution and routines
├── tracing/        execution tracing
├── filesystem/     the virtual filesystem
├── shell/          the virtual shell
├── credential/     the credential store
├── status/         system status reports
├── config/         configuration (hook hot reload)
├── toolinvocation/ one-shot tool invocation
└── base/           foundational types (Principal, the scope markers, …)
```

IMPORTANT (package convention): `at.aimon.core.<domain>` holds interfaces and value objects, and
`at.aimon.core.<domain>.impl` holds implementations. **Importing `*.impl` directly from outside
the domain tree is blocked by ArchUnit.** External modules and other core packages depend on the
neutral SPI packages instead (e.g. `at.aimon.core.agent.orca`).

## 3. Layers

Each layer depends only on the layers below it.

```
┌───────────────────────────────────────────────────────────────┐
│ Application            aimon-cli, the user's application      │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Session                LiveSession / SessionRouter            │
│                        (turn submission, event subscription,  │
│                         multi-node routing)                   │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Agent execution        OrcaAgentExecutor (the ReAct loop)     │
│   Skill · Subagent · Workflow · Hook · Command · Scheduling   │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Core abstraction                                              │
│   Tool · LlmClient · VirtualFileSystem · VirtualShell         │
│   SessionRecordStore · KnowledgeStore · ObservationStore      │
└──────────────────────────────┬────────────────────────────────┘
┌──────────────────────────────▼────────────────────────────────┐
│ Implementation                                                │
│   built in: ReadTool, BashTool, LocalFileSystem, LocalShell   │
│   external: aimon-llm-*, aimon-filesystem-*, aimon-session-*, │
│             aimon-memory-*, aimon-sandbox-*, …                │
└───────────────────────────────────────────────────────────────┘
```

### The ReAct loop

```
one user input (= a turn)
  └─ OrcaAgentExecutor.execute(runtime, request)
       ├─ assemble the system prompt (Agent definition + skills + ContextProvider)
       ├─ OnStartHook
       ├─ ┌─ iteration (repeats while the budget allows) ──────┐
       │  │ 1. LlmClient.sendMessage(prompt, messages, tools)  │
       │  │ 2. response is text only → final answer, loop ends │
       │  │ 3. if there is a tool_use                          │
       │  │    ├─ PreToolHook  (may BLOCK)                     │
       │  │    ├─ Tool.execute()  (batch may run in parallel)  │
       │  │    ├─ PostToolHook                                 │
       │  │    └─ append the result to messages → back to 1    │
       │  └────────────────────────────────────────────────────┘
       ├─ OnStopHook
       └─ AgentExecutionResult
```

IMPORTANT: one pass of that repetition is an **iteration**, and the whole thing is a **turn**.
An **execution** is the concept above a turn and happens even without a session (subagent fork,
skill fork, rewake replay, scheduled routine). The three words are not interchangeable —
[`glossary.en.md` §4](glossary.en.md).

---

## 4. The core abstractions

### 4.1 Agent

An agent's **configuration**. An immutable definition holding no execution state.

**Package**: `at.aimon.core.agent`

```java
public interface Agent {
    default String getName() { return getMetadata().getName(); }
    AgentMetadata getMetadata();   // name, max iterations, variables
    AgentContent getContent();     // system prompt, model configuration
}
```

It can also be defined in Markdown with YAML frontmatter.

```yaml
---
name: my-agent
maxIterations: 10
model:
  name: claude-sonnet-4-5
  temperature: 0.5
  maxTokens: 40000
variables:
  language: Java
  tools: ["Read", "Write", "Bash"]
---

# System Prompt
You are a helpful assistant...
```

Related: `DefaultAgent` (the builder), `AgentMetadata`, `AgentContent`;
the parsers are in `agent.definition` / `agent.parser`.

### 4.2 AgentExecutor / AgentRuntime

**Package**: `at.aimon.core.agent`

```java
public interface AgentExecutor<
        CTX extends AgentRuntime,
        REQ extends AgentExecutionRequest,
        RES extends AgentExecutionResult> {

    RES execute(CTX agentRuntime, REQ executionRequest);
}
```

One call to this `execute` is **one turn**.

| Type | Role | Lifetime |
|------|------|------|
| `AgentRuntime` | the execution environment bundling the tool, hook and MCP registries | **agent-scoped** |
| `AgentRuntimeId` | `agent:<name>[:<discriminator>]` — deterministic | agent |
| `AgentRuntimeRegistry` | runtime lookup | **application-scoped** |
| `AgentExecutionRequest` / `AgentExecutionResult` | turn input / output | turn |
| `InterceptingAgentExecutor` | the cross-cutting-concern decorator | — |
| `AgentEnvironmentSnapshot` | working directory and environment snapshot (memoized by `AgentRuntimeId`) | agent |

IMPORTANT: **do not create an `AgentRuntime` per session.** Register it once at bootstrap and
close it only at app shutdown or on explicit agent removal. If `LiveSession.close()` calls
`AgentRuntime.close()`, other sessions of the same agent break. The full rules are in
[`scope-model.en.md` §2](scope-model.en.md).

Nor should you mint a new `AgentRuntimeId` per execution — on a cron re-fire
`ScheduledTask.boundRuntimeId` would not resolve. That is why there is no `generate()` at all,
only `from(Agent)` / `from(Agent, String)`.

### 4.3 Tool

The unit of operation the LLM can call. One type holds both the schema definition and the
execution logic.

**Package**: `at.aimon.core.agent.tool`

```java
public interface Tool {
    ToolDefinition getDefinition();  // name, description, JSON schema
    ToolResult execute(ToolInput input, ToolContext context);
}
```

**The four contract terms**

1. `execute()` **never throws** — it always returns, via `ToolResult.error()`.
2. Use `ToolInput`'s type-safe accessors.
3. It holds **no state** between executions.
4. `ToolInput` / `ToolResult` / `ToolContext` are all **immutable**.

```java
// ToolInput — required / defaulted / nullable
String path      = input.getRequiredString("file_path");
int    limit     = input.getInteger("limit", 2000);
String filter    = input.getStringOrNull("filter");
if (input.has("optional_param")) { /* … */ }

// ToolResult
return ToolResult.success("Operation completed");
return ToolResult.error("File not found: " + path);
return ToolResult.error("I/O error: " + e.getMessage(), e);

// ToolContext — immutable, assembled with a builder
Optional<VirtualFileSystem> vfs = context.get("fileSystem", VirtualFileSystem.class);
```

`AbstractTool` is the recommended base class. The canonical document on how to write one is the
[tool development guide](../features/tool/tool-development-guide.en.md).

**Auxiliary mechanisms**

| Feature | Types | Default |
|------|------|--------|
| parallel execution within a batch | `ConcurrencyBehavior`, `ParallelToolDispatcher`, `ToolConcurrencyConfig` | `SEQUENTIAL` (parallel off) |
| declaring interrupt behaviour | `InterruptBehavior` | — |
| lazy loading + search exposure | `ToolLoadingMode`, `ToolSearchRegistry`, `ToolSearchStrategy` | — |
| input schema validation | `agent.tool.schema`, `SchemaValidationMode` | `WARN` (records only; execution proceeds) |
| declaring the permission subject | `ToolPermissionSubjectAware`, `PermissionSubject` | — |
| permission rules (when one value cannot express it) | `CustomToolPermissionAware`, `CustomToolPermissionRule` | — |

Permission patterns: `"Read"` (allow unconditionally) · `"Bash(git:*)"` (COMMAND prefix) ·
`"Read(/tmp/**)"` (PATH glob) · `"Bash(npm install)"` (exact match). The matcher is chosen by
`PermissionSubject.Kind` — `ToolPattern` when the tool offers a command, `PathPattern` when it
offers a path.

Schema validation stands **in front of** `execute()` — it looks at exactly four things: a
missing `required`, a type mismatch, a departure from `enum`, and an undeclared parameter name.
It does not look at ranges (`minimum` and the like), so those checks stay inside the tool.

### 4.4 LlmClient

**Package**: `at.aimon.core.llm`

```java
public interface LlmClient {
    LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                            List<ToolDefinition> tools, LlmModel modelConfig,
                            LlmCallMetadata metadata);

    String getProviderName();
}
```

The convenience overloads (`systemPrompt` only, via `SystemPromptParts`, with a cancellation
token) and streaming (`sendMessageStreaming`) are supplied as `default` methods, so a new
provider only has to implement the one above.

| Type | Role |
|------|------|
| `Message`, `Role` | conversation messages (USER / ASSISTANT / TOOL) |
| `TextContentBlock`, `ImageContentBlock`, `DocumentContentBlock` | multimodal content blocks |
| `ToolUse`, `ToolUseResult` | tool call request / result |
| `LlmResponse`, `StopReason` | the response and why it stopped |
| `LlmModel` | model parameters |
| `TokenUsage`, `CostSummary`, `ModelPriceTable` | token and cost measurement |
| `LlmCallMetadata`, `BoundMetadataLlmClient` | call tagging |
| `LlmCancellation` | cancellation |
| `ModelContextWindowRegistry` | per-model context windows |

Implementations: `aimon-llm-openai`, `aimon-llm-anthropic`.
For a new provider see the [LLM Provider development guide](../features/llm/llm-provider-development-guide.en.md).

### 4.5 VirtualFileSystem

**Package**: `at.aimon.core.filesystem`

```java
public interface VirtualFileSystem extends AutoCloseable {
    // content
    void write(String path, InputStream content, long contentLength);
    void write(String path, byte[] content);
    void write(String path, String content);
    InputStream read(String path);
    void delete(String path);

    // metadata
    boolean exists(String path);
    boolean isDirectory(String path);
    FileMetadata getMetadata(String path);

    // directories
    List<String> list(String directory);
    List<String> listRecursive(String directory);
    void createDirectory(String path);
    void deleteRecursive(String path);

    // copy / move
    void copy(String sourcePath, String destinationPath, boolean overwrite);
    void move(String sourcePath, String destinationPath, boolean overwrite);

    // streaming
    OutputStream openOutputStream(String path);
    InputStream openInputStream(String path);

    // search / backend
    List<String> search(String directory, String pattern, int maxResults);
    String getWorkingDirectory();
    void initialize();
    BackendStatus getStatus();
    void close();
}
```

**Security requirements every implementation must meet**: block path escape (`../`), reject null
bytes and control characters, and block symbolic links (on a local filesystem). `PathValidator`
provides the shared validation.

Implementations: `filesystem.impl.local` (built into core), `ScopedVirtualFileSystem` (a
root-restricting wrapper), `aimon-filesystem-gridfs` (MongoDB GridFS), `aimon-filesystem-s3`
(AWS S3).

### 4.6 VirtualShell

**Package**: `at.aimon.core.shell`

```java
public interface VirtualShell extends AutoCloseable {
    ShellCommandResult execute(ShellCommand command);
    ShellCommandResult execute(ShellCommand command, ExecutionOptions options);
    String getWorkingDirectory();
    boolean supports(ShellFeature feature);
    void close();
}
```

`ShellCommandResult` holds the exit code, stdout, stderr and elapsed time.
The sandbox modules (`aimon-sandbox-docker`, `aimon-sandbox-kubernetes`) provide isolated
execution implementations.

### 4.7 Session

**Package**: `at.aimon.core.agent.session` (+ `.store`, `.transcript`)

The facade for submitting turns and subscribing to events is `LiveSession`; what is persisted is
`SessionRecord`.

```java
LiveSession session = /* an opener or a factory */;
AgentExecutionResult result = session.submit("Take a look at the deploy log");
session.events().subscribe(event -> { /* AgentExecutionEvent */ });
```

| Type | Role | Lifetime |
|------|------|------|
| `LiveSession` | the node-local handle — `submit` / `submitAsync` / `offerAsync` / `events()` / `status()` | **transient** |
| `SessionRecord` | the transcript plus side fields (`sessionTotals`, `budgetOverride`, …) | **persistent** |
| `SessionTranscript`, `SessionSnapshot` | message history (immutable) | session |
| `SessionRecordStore` | the record store | **application-scoped** |
| `SessionLeaseStore` | ownership election plus fencing between nodes | **application-scoped** |
| `SessionStore` | the node-scoped composite over the two above (`claim`) | node |
| `TurnId` | turn addressing (interrupt targeting, event tagging) | **not persisted** |

IMPORTANT: the relationship `1 SessionRecord : 0..N LiveSession` is **asymmetric**. A value that
must survive a restart, an eviction or a move between nodes goes on the record side. And
**neither side takes the bare word `Session` as a type name** — `Session` and `AgentSession` are
blocked at build time by `SessionNamingArchitectureTest`. The reasoning is in
[`scope-model.en.md` §3](scope-model.en.md).

Multi-node routing (`SessionRouter`, `LiveSessionCache`, `LiveSessionOpener`) lives in
`aimon-session-routing`, and the backends in `aimon-session-{mongodb,postgres,redis}`.

---

## 5. The extension systems

### 5.1 Skill

A declarative package bundling prompts, tools and hooks. It follows the
[Agent Skills](https://agentskills.io/) standard.

**Package**: `at.aimon.core.skill`

```java
Skill skill = Skill.builder()
    .name("alert-analysis")
    .metadata(SkillMetadata.builder()
        .name("alert-analysis")
        .description("Analyzes monitoring alerts")
        .build())
    .content(SkillContent.of("# Alert Analysis\n..."))
    .putScript("query.py", "scripts/query.py")
    .putReference("patterns.md", "references/patterns.md")
    .build();
```

The disk layout:

```
skill-name/
├── SKILL.md      metadata (YAML frontmatter) + system prompt
├── scripts/      executable scripts
├── references/   documents
└── assets/       templates and static resources
```

| Type | Role |
|------|------|
| `Skill`, `SkillMetadata`, `SkillContent` | the immutable skill representation |
| `SkillRegistry` (+ `Default*`, `Composite*`) | discovery and lookup |
| `ClasspathSkillRepository` / `PathSkillRepository` / `VfsSkillRepository` | repositories |
| `MarkdownSkillParser` | SKILL.md parsing |
| `ExecutionMode`, `SubagentBackedSkillForkExecutor` | inline execution vs fork execution |
| `InvokePolicy`, `skill.policy.*` | invocation policy and approvals |
| `SkillTool` | exposing a skill to the LLM as a tool |

**Approval scopes**, narrowest to widest, are pending (this turn) → session (this session) →
agent (this agent), and the policy chain consults them in that order. Undoing is `/revoke`, or
`/revoke --agent` to reach the agent-wide ones.

### 5.2 Hook

**Package**: `at.aimon.core.hook` (event types in `hook.event`, the execution contract in
`hook.execution`)

Hooks are registered against `HookEventType` constants.

```java
HookRegistry registry = new DefaultHookRegistry();
registry.register(HookEventType.PRE_TOOL, context -> {
    log.info("Executing: {}", context.getCurrentToolUse().getName());
    return HookResult.allow();
});
```

**The 13 event types**

| Event type | Fires | Can block the flow |
|------------|----------|----------|
| `PRE_TOOL` / `POST_TOOL` | just before / just after tool execution | before only |
| `ON_START` / `ON_STOP` | execution start / end | — |
| `PRE_COMPACT` / `POST_COMPACT` | just before / just after context compaction | — |
| `PERMISSION_REQUEST` / `PERMISSION_DENIED` | on a permission request / on denial | request only |
| `SUBAGENT_START` / `SUBAGENT_STOP` | subagent start / end | — |
| `ON_SESSION_START` / `ON_SESSION_END` | opening / closing a **live session** | — |
| `ON_CONFIG_RELOAD` | on hook configuration hot reload | — |

| Type | Role |
|------|------|
| `HookRegistry`, `HookExecutionManager` | registration and execution |
| `HookResult`, `Decision` (`ALLOW`/`ASK`/`DENY`), `FlowControl` (`CONTINUE`/`BLOCK`) | a hook's verdict |
| `HookFeedback` | composes the advice a hook left into a `<system-reminder>` block |
| `HookExecutionPolicy` | timeouts and execution mode |
| `config.hook.*` | file-based configuration, layered merging, hot reload |
| `hook.rewake` | asynchronous rewake, waking an agent on an external event |

IMPORTANT (known misnomer): `ON_SESSION_START` / `ON_SESSION_END` fire on **the opening/closing
of a `LiveSession`**, not of the session (record). They fire again when the same session is
resumed.

### 5.3 Subagent

**Package**: `at.aimon.core.subagent`

```java
Subagent subagent = Subagent.builder()
    .name("log-analyzer")
    .description("Analyzes application logs for error patterns")
    .whenToUse("When a deployment fails and logs need triage")
    .systemPrompt("Analyze application logs...")
    .tools(List.of("Read", "Grep"))
    .maxIterations(8)
    .build();
```

They are registered through `SubagentRegistry` and run by `SubagentExecutionManager` /
`DefaultSubagentExecutor`. Markdown definitions live in `subagent.parser` /
`subagent.repository`, and background execution in `SubagentBackgroundConfig` /
`SubagentTaskController`.

IMPORTANT: a fork has **no `SessionId` of its own.** It is not a session's turn, so `SESSION_ID`
is not put on the tool context and only `EXECUTION_ID` is. To make the parent session's approval
decisions follow it, the id of the session that launched it is passed separately as
`invokingSessionId`.

### 5.4 Workflow

**Package**: `at.aimon.core.workflow`

Weaves several subagents together with **deterministic control flow**. You create a runner with
`WorkflowRunners`, assemble it from `Pipeline` / `Stage` / `AgentTask`, and can resume through
`RunHandle` and `RunStore`. Budgets are handled by `WorkflowBudget`, concurrency by
`WorkflowConcurrencyConfig`, and git isolation by `WorktreeEnvironmentFactory`. The JS scripting
frontend is `aimon-workflow-graaljs`.

IMPORTANT (teardown responsibility): `WorkflowRunner` has two variants — the agent-scoped variant
is created by `OrcaAgentRuntimeFactory` and closed by `OrcaAgentRuntime.close()`, while the
call-scoped variant is created per call by `WorkflowTool` and closed by try-with-resources.
**Whoever created it closes it.**

### 5.5 Command

**Package**: `at.aimon.core.command`

```java
CommandType.SYSTEM   // built into core (/help, /status, /compact …)
CommandType.CUSTOM   // user-defined
```

The built-in commands live in `command.system` — `/help`, `/commands`, `/version`, `/status`,
`/agents`, `/skills`, `/clear`, `/compact`, `/pending`, `/approve`, `/deny`, `/revoke`,
`/rewakes`. To expose a skill as a command, use `SkillBackedCommand`.

> In 0.1.0 `CustomCommand` was removed and folded into skills —
> [migration guide](../migration/custom-command-to-skill.md).

### 5.6 Scheduling

**Package**: `at.aimon.core.scheduling`

`SchedulingEngine` (assembled with a builder) / `ScheduledTaskManager` / `RoutineExecutor` are
the centre of it. Quotas (`scheduling.quota`) and an execution guard
(`ScheduledExecutionGuard`) prevent runaway. The cluster implementation is
`aimon-scheduling-quartz`.

IMPORTANT: these three are **application-scoped**. Do not close them when an `AgentRuntime` is
destroyed. `AgentRuntimeRegistry` too is created **outside** `SchedulingEngine` and injected
through the builder — `SchedulingEngine` does not own it.

---

## 6. The Orca agent

`OrcaAgentExecutor` is the production ReAct implementation.

**Package**: `at.aimon.core.agent.impl.orca` (the public SPI is `at.aimon.core.agent.orca`)

```java
public class OrcaAgentExecutor implements AgentExecutor<
    OrcaAgentRuntime,
    OrcaAgentExecutionRequest,
    OrcaAgentExecutionResult> { … }
```

### Tool providers

Orca assembles its tools from per-domain providers. An external module joins in by implementing
`OrcaToolProvider` / `OrcaToolProviderContext` / `OrcaProviderDependencies` from
`at.aimon.core.agent.orca` (it does not import the `impl` package).

| Provider | Tools supplied |
|-----------|----------|
| `OrcaFileToolProvider` | `Read`, `Write`, `Edit`, `Grep` |
| `OrcaBashToolProvider` | `Bash`, `BashOutput` |
| `OrcaSkillToolProvider` | `Skill` |
| `OrcaSubagentToolProvider` | `Task`, `TaskList`, `TaskStop`, `AgentOutput` |
| `OrcaTodoToolProvider` | `TodoWrite` |
| `OrcaSchedulingToolProvider` | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` |
| `OrcaKnowledgeToolProvider` | `KnowledgeSearch`, the wiki tools |

The full list of built-in tools is in [feature catalogue §3.2](features.en.md).

### The execution flow

1. Assemble the system prompt from the Agent definition + skills + `ContextProvider`
2. `OnStartHook`
3. The ReAct loop — iterate while the budget (`BudgetTracker`) allows
   - call the `LlmClient` (streaming possible)
   - text only means the final answer
   - a `tool_use` means `PreToolHook` (may BLOCK) → execute → `PostToolHook` → append the result
   - when the context reaches the threshold, `CompactionEngine` compacts it (firing `PRE_COMPACT` / `POST_COMPACT`)
4. `OnStopHook`
5. Return `OrcaAgentExecutionResult`

`BudgetTracker` is constructed in **exactly 2 places** in the main sources —
`OrcaAgentExecutor` (turn) and `DefaultSubagentExecutor` (fork). This is where the fact that a
budget is per **execution unit** rather than per session becomes visible.

---

## 7. Extension points at a glance

| What you want to extend | Interface | How |
|--------------|----------|------|
| a tool | `Tool` / `AbstractTool` | subclass, then register with `ToolRegistry` |
| a bundle of Orca tools | `OrcaToolProvider` | implement the `at.aimon.core.agent.orca` SPI |
| an LLM provider | `LlmClient` | implement in a separate module |
| a file backend | `VirtualFileSystem` | implement (see GridFS, S3) |
| a shell backend | `VirtualShell` | implement (see the sandbox modules) |
| a lifecycle hook | the 13 interfaces in `hook.event` | `HookRegistry.register(HookEventType, hook)` |
| a skill | `Skill` | write a SKILL.md per the Agent Skills standard |
| a subagent | `Subagent` | the code builder, or a Markdown definition |
| a command | `Command` | implement, then register with `CommandRegistry` |
| a session store | `SessionRecordStore` / `SessionLeaseStore` | implement (see Mongo/Postgres/Redis) |
| a knowledge store | `KnowledgeStore` | implement (see OpenSearch) |
| a memory store | `ObservationStore` / `RepresentationStore` | implement (see file/Postgres/Mongo) |
| a scheduler | the `scheduling.scheduler` SPI | implement (see Quartz) |
| an execution interceptor | `AgentExecutionInterceptor` | add it to the chain |
| a trace exporter | `SpanExporter` / `TraceSpanStore` | implement |

## 8. Design patterns in use

| Pattern | Where | Purpose |
|------|--------|------|
| **Builder** | `Agent`, `AgentMetadata`, `AgentContent`, `Skill`, `ToolContext` | assembling immutable objects |
| **Strategy** | `VirtualFileSystem`, `VirtualShell`, `LlmClient`, `ToolSearchStrategy` | swapping backends |
| **Template Method** | `AbstractTool` | a consistent execution structure |
| **Chain of Responsibility** | `AgentExecutionChain`, hook execution, the approval policy chain | sequential processing pipelines |
| **Composite** | `CompositeSkillRegistry`, `CompositeCommandExecutor` | composing several sources |
| **Factory** | the tool providers, `LiveSessionFactory`, `OrcaAgentRuntimeFactory` | concentrating the points of creation |
| **Decorator** | the `ArtifactAware*` tools, `InterceptingAgentExecutor`, `TracingLlmClient`, `BoundMetadataLlmClient` | adding behaviour without modification |
| **Registry** | `ToolRegistry`, `SkillRegistry`, `HookRegistry`, `AgentRuntimeRegistry` | managing collections of components |
| **Observer** | hooks, the `AgentExecutionEvent` stream | event notification |
| **Repository** | `SessionRecordStore`, `SkillRepository`, `SubagentRepository`, `RunStore` | abstracting data access |

For the design principles themselves see the [SOLID principles document](../project/solid-principles.md).

---

## 9. Rules the build enforces

A good number of this repository's architecture rules exist as **tests** rather than as prose.
Breaking one breaks the build, so the table below is the list of what is actually held to. That is
also why they are not redrawn as a diagram or restated in prose — a diagram is free to drift from
the code and these tests are not.

| Rule (test) | What it enforces |
|---|---|
| `ArchitectureTest` | Core architecture rules — `*.impl` encapsulation and the scope invariants |
| `ArchitectureRulesTest` | `aimon-core` depends on no sibling `aimon-*` module |
| `PackageDependencyArchitectureTest` | Layer dependency direction, the isolation of `at.aimon.core.config.hook`, and a **cycle baseline** between top-level packages — a new pair outside the list fails, and so does a listed pair that is no longer cyclic |
| `BuiltInToolSchemaArchitectureTest` | Every tool in `at.aimon.core.tools` declares `additionalProperties: false` at top level |
| `ToolExecutionGateArchitectureTest` | `Tool#execute` is reachable only through the schema-validation gate |
| `MemoryArchitectureTest` | Multi-tenant isolation in `at.aimon.core.memory` |
| `TurnVocabularyArchitectureTest` | `Turn` stays out of identifiers in five package trees. It cannot cover `agent.impl.orca`, where turns and iterations are both real |
| `YamlParserInstanceArchitectureTest` | No `Yaml` field anywhere in main sources (one per parse call). Reflection over field declarations rather than a source grep, so it also catches one reached through a wrapper |
| `PublishedModuleApiScopeTest` | Only a facade declares a sibling module on `api`; every other published module uses `implementation` |
| `PublishedModuleLoggingBindingTest` | A published library logs through the SLF4J API and does not choose the binding for its consumers |
| `ReleaseGateMatchesCiGateTest` | `scripts/release.sh` runs the **same** Gradle task the CI workflow does |
| `ExternalSchedulerWiringTest` | Performs the external-scheduler wiring from a different package, so `executeTask`'s visibility cannot quietly narrow |
| `SessionNamingArchitectureTest` | The bare names `Session` and `AgentSession` cannot be used as type names (`aimon-session-routing`) |
| `SessionRecordSoleWriterArchitectureTest` | No production code outside `agent.session.store` depends on the mutable `SessionRecord` (`aimon-session-routing`) |
| `AimonDocumentedPropertiesTest` | Every `@ConfigurationProperties` key the starter binds appears in the embedding guide, and every key documented there is bindable (`aimon-spring-boot-starter`) |

IMPORTANT: the last three live **outside** `aimon-core`. Looking only at the
`at.aimon.core.architecture` package will miss them — a rule lives in the module whose code it holds.

Several of these exist because they caught something, and what they caught is recorded in
[`CHANGELOG.md`](../../CHANGELOG.md).

---

## Related documents

- [`features.en.md`](features.en.md) — the feature catalogue (what it can do)
- [`context.en.md`](context.en.md) — the system boundary and the external systems (what is outside)
- [`deployment.en.md`](deployment.en.md) — the multi-node deployment view
- [`glossary.en.md`](glossary.en.md) — the dictionary of terms and lifetimes
- [`scope-model.en.md`](scope-model.en.md) — the lifetime, ownership and teardown rules
- [`../features/`](../features/) — the per-feature guides
- [tool development guide](../features/tool/tool-development-guide.en.md)
- [hook development guide](../features/hook/hook-development-guide.en.md)
- [LLM Provider development guide](../features/llm/llm-provider-development-guide.en.md)
- [Agent Skills standard specification](../references/agentskills-specification.md)
