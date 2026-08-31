---
translated_from: docs/overview/features.md
source_commit: 5a7e80ff
---

# Feature Catalog

This document gathers in one place, from the user's point of view, **what `aimon-core` can do**.

- Curious about the architecture → [`architecture.en.md`](architecture.en.md)
- Curious about what a term means and how long it lives → [`glossary.en.md`](glossary.en.md) / [`scope-model.en.md`](scope-model.en.md)
- Wondering **"I want to do this — does AIMON have it?"** → this document

## How to read it

Every feature is described by the three things below.

| Column | Meaning |
|-----|-----|
| **Feature** | what the user gets |
| **Entry point** | the first type or tool name to reach for in order to use it |
| **Where** | `core` = built into `aimon-core` / a module name = a separate dependency is required |

Anything marked `core` works with nothing but `at.aimon.core:aimon-core` on the path. Where a
module name is given you have to depend on that artifact as well — the core holds only the
interfaces and the implementations live outside it
([SOLID › DIP](../project/solid-principles.md)).

---

## 0. At a glance

| # | Area | One-line summary |
|---|------|-----------|
| [1](#1-agent-execution) | **Agent execution** | runs the ReAct loop to process one user input |
| [2](#2-sessions) | **Sessions** | persists a conversation and carries it across restarts and nodes |
| [3](#3-tools) | **Tools** | the unit through which an agent interacts with the outside world |
| [4](#4-skills) | **Skills** | a declarative capability package bundling prompts, tools and hooks |
| [5](#5-hooks) | **Hooks** | intervene at 13 points in the execution lifecycle |
| [6](#6-subagents) | **Subagents** | delegate a sub-task in an isolated context |
| [7](#7-workflows) | **Workflows** | orchestrate several subagents deterministically |
| [8](#8-commands) | **Commands** | user slash commands such as `/compact` and `/status` |
| [9](#9-llm-integration) | **LLM integration** | provider abstraction, streaming, retry, cost measurement |
| [10](#10-memory) | **Memory** | accumulates observations and promotes them to long-term memory |
| [11](#11-knowledge-and-the-wiki) | **Knowledge / wiki** | RAG search and the LLM wiki |
| [12](#12-mcp-integration) | **MCP** | attaches the tools of an external MCP server to the agent |
| [13](#13-scheduling) | **Scheduling** | cron and one-shot scheduled execution, routines |
| [14](#14-filesystem-and-shell) | **Filesystem / shell** | the virtual FS and virtual shell abstractions |
| [15](#15-sandbox-and-browser) | **Sandbox / browser** | isolated execution environments, web automation |
| [16](#16-observability) | **Observability** | tracing, status reports, usage |
| [17](#17-permissions-and-credentials) | **Permissions / credentials** | tool allow rules, approval scopes, secrets |

---

## 1. Agent execution

The part where the agent repeats "call the LLM → run a tool → observe" until one user input is
processed through to the end. One pass of that repetition is an **iteration**, and processing the
one input from end to end is a **turn** ([the rules for the words](glossary.en.md)).

| Feature | Entry point | Where |
|------|--------|------|
| defining an agent (name, system prompt, model, max iterations) | `Agent` (builder) | core |
| defining an agent in Markdown + YAML frontmatter | `agent.definition` / `agent.parser` | core |
| the ReAct execution loop | `AgentExecutor` → `OrcaAgentExecutor` | core |
| the agent execution environment (the tool, hook and MCP registries bundled) | `AgentRuntime`, `AgentRuntimeId` | core |
| the execution budget (iteration / token / time ceilings) | `ExecutionBudget`, `BudgetTracker` | core |
| automatic context-window compaction | `CompactionEngine`, `TimeBasedMicrocompact` | core |
| refreshing context blocks mid-execution (working directory, time, …) | `ContextAssembler`, `ContextProvider` | core |
| queueing extra user input mid-turn | `agent.queue`, `SubmitOptions` | core |
| cooperative interruption (Ctrl+C, NOW priority) | `agent.interrupt`, `InterruptBehavior` | core |
| streaming execution events | `AgentExecutionEvent` (sealed) | core |
| the interceptor chain around execution | `AgentExecutionInterceptor`, `InterceptingAgentExecutor` | core |
| multimodal input (text / image / audio / file) | `agent.input` | core |
| collecting execution artifacts | `ArtifactCollector`, `FileArtifact` | core |
| system prompt assembly · composing `<system-reminder>` | `SystemPromptPart`, `SystemReminderFormatter` | core |

**When you use it.** This is the road you necessarily travel to embed an agent in an
application. Mostly you create an `Agent`, register an `AgentRuntime` once at bootstrap, and then
feed turns in through a [session](#2-sessions).

**Watch out.** `AgentRuntime` is **agent-scoped** — do not create one per session. Take
[`scope-model.en.md`](scope-model.en.md) as the reference for the lifetime rules.

**Related documents**
- [embedding guide](../getting-started/embedding-agent-in-application.en.md) — Spring Boot / SDK integration
- [CLI reference integration example](../getting-started/aimon-core-integration-via-cli-reference.en.md)
- [command queue guide](../features/agent-execution/command-queue-guide.en.md)
- [interruptible tools guide](../features/agent-execution/interruptible-tools-guide.en.md)
- [the system-reminder convention](../features/agent-execution/system-reminder-convention.en.md)

---

## 2. Sessions

The part that persists a conversation and carries it across process restarts and moves between
nodes.

IMPORTANT: **a session (`SessionRecord`) and a live session (`LiveSession`) are not the same
thing.** The relationship is asymmetric — `1 SessionRecord : 0..N LiveSession` — and a value that
must survive goes on the record side. For the detailed rules see
[`scope-model.en.md` §3](scope-model.en.md).

| Feature | Entry point | Where |
|------|--------|------|
| the turn-submission / event-subscription facade | `LiveSession` (`submit`, `submitAsync`, `offerAsync`, `events()`) | core |
| the persistent session record (transcript + totals + budget override) | `SessionRecord`, `SessionRecordStore` | core (in-memory by default) |
| message history | `SessionTranscript`, `SessionSnapshot`, `TranscriptManager` | core |
| cumulative statistics (turns / iterations / tokens) | `SessionTotals` | core |
| session ownership election plus fencing between nodes | `SessionLeaseStore`, `SessionStore.claim` | core |
| multi-node routing / handle cache | `SessionRouter`, `LiveSessionCache`, `LiveSessionOpener` | `aimon-session-routing` |
| MongoDB backend | — | `aimon-session-mongodb` |
| PostgreSQL backend | — | `aimon-session-postgres` |
| Redis backend | — | `aimon-session-redis` |
| turn addressing (interrupt targeting / event tagging) | `TurnId` | core |
| telling apart what a submission did (ran immediately vs queued) | `SubmitOutcome` | core |

**When you use it.** When you are building a conversational UI (a REPL, a web chat), or when the
same conversation has to continue across several requests. For a one-shot batch run, a
[subagent fork](#6-subagents) or a [scheduled routine](#13-scheduling) is enough without a
session at all.

**Related documents**
- [session tutorial](../features/session/agent-session-tutorial.en.md) — for wiring it up the first time
- [the `LiveSession` API guide](../features/session/agent-session-guide.en.md) — including event streaming
- [multi-node deployment guide](../features/session/web-session-deployment-guide.en.md)

---

## 3. Tools

The unit through which an agent interacts with the outside world. The contract is that
`execute()` **never throws** and always returns a `ToolResult`.

### 3.1 The framework

| Feature | Entry point | Where |
|------|--------|------|
| the tool interface / base implementation | `Tool`, `AbstractTool` | core |
| type-safe input and output | `ToolInput`, `ToolResult`, `ToolContext` (all immutable) | core |
| tool registration and lookup | `ToolRegistry` | core |
| declarative input binding (record + `@ToolParam` → schema) | `GenericTool`, `ToolSchemaGenerator`, `ToolInputBinder` | core |
| input schema validation (**before** `execute()`) | `agent.tool.schema`, `SchemaValidationMode` | core (`WARN` by default) |
| parallel execution (safe tools within one batch) | `ConcurrencyBehavior`, `ParallelToolDispatcher`, `ToolConcurrencyConfig` | core (off by default) |
| lazy loading + exposing the schema through search | `ToolSearchRegistry`, `ToolSearchStrategy`, `ToolLoadingMode` | core |
| tool permission checking | `agent.tool.permission`, `ToolPermissionSubjectAware` | core |
| declaring interrupt behaviour | `InterruptBehavior`, `InterruptAccess` | core |
| invoking a tool directly, once | `SingleToolInvoker` | core |

### 3.2 Built-in tools

| Category | Tool names | Where |
|------|----------|------|
| file | `Read`, `Write`, `Edit`, `Grep` | core |
| shell | `Bash`, `BashOutput` (including background) | core |
| web | `WebSearch`, `WebFetch` | core |
| to-do | `TodoWrite` | core |
| delegation | `Task`, `TaskList`, `TaskStop`, `AgentOutput` | core |
| skill | `Skill` | core |
| scheduling | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` | core |
| knowledge | `KnowledgeSearch` | core |
| wiki | `WikiIngest`, `WikiSearch`, `WikiLint`, `WikiStatus` | core |
| memory | `Observe`, `MemoryRecall`, `MemorySearch`, `MemoryChat` | core |
| tool search | `ToolSearch` | core |
| console | `ConsoleOutput` | core |
| workflow | `Workflow` | core |
| workflow (JS script) | `WorkflowJs` | `aimon-workflow-graaljs` |
| browser | `Browser` | `aimon-browser-playwright` |
| sandbox | `RunSandbox`, `CopyToSandbox`, `RestartSandbox`, `DeleteSandbox` | `aimon-sandbox-*` |

**Related documents**
- [tool development guide](../features/tool/tool-development-guide.en.md) — the canonical document for writing a new tool
- [parallel tool execution guide](../features/tool/parallel-tool-execution-guide.en.md)
- [browser tool guide](../features/tool/browser-tool-guide.en.md)

---

## 4. Skills

A **declarative capability package** bundling prompts, tools and hooks into one. It follows the
[Agent Skills standard](../references/agentskills-specification.md) format and adds AIMON
extension frontmatter on top.

| Feature | Entry point | Where |
|------|--------|------|
| skill definition / metadata | `Skill`, `SkillMetadata`, `SkillContent` | core |
| registration and lookup | `SkillRegistry` | core |
| execution mode (inline / fork) | `ExecutionMode`, `SubagentBackedSkillForkExecutor` | core |
| invocation policy and approvals | `InvokePolicy`, `skill.policy.*` | core |
| approval scope — this turn / this session / this agent | `PendingApprovalStore`, `SessionApprovalStore`, `AgentApprovalStore` | core |
| skill repositories (classpath / path / VFS) | `ClasspathSkillRepository`, `PathSkillRepository`, `VfsSkillRepository` | core |
| materializing bundled skills | `BundledSkillMaterializer` | core |
| hooks a skill declares | `skill.hook.declarative`, `skill.hook.predicate` | core |
| template rendering (`${AIMON_*}` variables) | `skill.render` | core |
| validation | `skill.validation` | core |

**When you use it.** When you want to package a reusable procedure — a deploy sequence, a review
checklist, a repository-specific workflow — as Markdown rather than as code. Even the approval
policy can be attached declaratively.

**The reach of an approval scope** runs narrowest to widest as pending → session → agent, and
the policy chain consults them in that order. To undo one, use `/revoke` (add `--agent` to clear
the agent-wide ones as well). The full table is in [`glossary.en.md` §3](glossary.en.md).

**Related documents**
- [built-in Agent/Skill guide](../features/skill/builtin-agent-skill-guide.en.md)
- [Agent Skills standard specification](../references/agentskills-specification.md)
- [AIMON skill extension fields](../references/aimon-skill-extensions.md)
- [CustomCommand → Skill migration](../migration/custom-command-to-skill.md)

---

## 5. Hooks

The extension point for intervening in the execution lifecycle. There are **13 event types**.

| Event | Fires |
|--------|----------|
| `onStart` / `onStop` | execution start / end |
| `preTool` / `postTool` | just before / just after tool execution |
| `preCompact` / `postCompact` | just before / just after context compaction |
| `permissionRequest` / `permissionDenied` | on a permission request / on denial |
| `subagentStart` / `subagentStop` | subagent execution start / end |
| `onSessionStart` / `onSessionEnd` | opening / closing a **live session** (see the warning below) |
| `onConfigReload` | on hook configuration hot reload |

| Feature | Entry point | Where |
|------|--------|------|
| hook registration and execution | `HookRegistry`, `HookExecutionManager`, `HookEventType` | core |
| hook feedback (blocking execution, injecting context) | `HookFeedback` | core |
| file-based hook configuration + hot reload | `HookConfigLoader`, `HookConfigWatcher`, `HookRegistryReloader` | core |
| layered configuration merging (global / project / local) | `LayeredHookConfig`, `HookConfigMerger` | core |
| asynchronous rewake (waking an agent on an external event) | `RewakeSpec`, `RewakeEnvelope`, `hook.rewake` | core |
| rewake received over a webhook | — | `aimon-rewake-webhook` |

IMPORTANT (known misnomer): `OnSessionStartHook` / `OnSessionEndHook` fire on **the
opening/closing of a `LiveSession`**, not of the session (record). They fire again when the same
session is resumed.

**Related documents**
- [hook development guide](../features/hook/hook-development-guide.en.md)
- [hook configuration guide](../features/hook/hook-config-guide.en.md) — file-based configuration and hot reload
- [the boundary of hook spec parity](../references/hooks-specification.md) — the limits of Claude Code format compatibility

---

## 6. Subagents

A sub-agent that runs in an **isolated context** inside its parent execution. Use it to delegate
work such as exploration or verification without polluting the parent's transcript.

| Feature | Entry point | Where |
|------|--------|------|
| defining a subagent (in code) | `Subagent.builder()` | core |
| defining a subagent (in Markdown) | `subagent.parser`, `subagent.repository` | core |
| registration and lookup | `SubagentRegistry` | core |
| execution management | `SubagentExecutionManager`, `DefaultSubagentExecutor` | core |
| background execution / control | `SubagentBackgroundConfig`, `SubagentTaskController`, `BackgroundTaskStore` | core |
| persisting background results | `TaskResultStore` (`InMemory*` / `Vfs*`), `TaskResult`, `TaskResultCodec` | core |
| the invoking tools | `Task`, `TaskList`, `TaskStop`, `AgentOutput` | core |

**Watch out.** A subagent fork has **no `SessionId` of its own** — because it is not a session's
turn. Its identity is the `ExecutionId`, and it carries the id of the session that launched it
separately as `invokingSessionId` (so that the reach of the approval policy carries over). Its
budget is managed by a separate `BudgetTracker` too.

**Related documents**
- [subagent development guide](../features/subagent/subagent-development-guide.en.md)

---

## 7. Workflows

Orchestrates several subagents with **deterministic control flow** (loops, conditionals,
fan-out). "The script decides" rather than "the agent decides on its own" is what separates this
from calling a subagent by itself.

| Feature | Entry point | Where |
|------|--------|------|
| the workflow runner | `WorkflowRunner`, `WorkflowRunners` | core |
| assembling pipelines / stages | `Pipeline`, `Stage`, `AgentTask` | core |
| judgement patterns (judge panels and the like) | `JudgedResult`, `Verdict`, `WorkflowPatterns` | core |
| run handles · resumption | `RunHandle`, `RunId`, `RunStore`, `StepResultCache` | core |
| budget / concurrency limits | `WorkflowBudget`, `WorkflowConcurrencyConfig` | core |
| running isolated in a git worktree | `WorktreeEnvironmentFactory` | core |
| the JS scripting frontend | the `WorkflowJs` tool | `aimon-workflow-graaljs` |

**When you use it.** When coverage demands sweeping in parallel (an audit, a migration), when
you need independent perspectives cross-checking each other (a review, a design comparison), or
when the scale does not fit in one context.

**Related documents**
- [workflow CLI guide](../features/workflow/workflow-cli-guide.en.md) — the `Workflow`/`WorkflowJs` tools and `/runs`
- [workflow usage guide](../features/workflow/workflow-usage-guide.en.md) — assembling, running and resuming from code

---

## 8. Commands

The slash commands a user types. There are the system commands the core provides, and a path for
exposing a skill as a command.

| Feature | Entry point | Where |
|------|--------|------|
| defining and registering a command | `Command`, `CommandRegistry`, `CommandType` | core |
| the system command base class | `SystemCommand` | core |
| exposing a skill as a command | `SkillBackedCommand` | core |

**The built-in system commands**

| Command | What it does |
|------|--------|
| `/help`, `/commands`, `/version` | help · the command list · the version |
| `/status` | the system status report |
| `/agents`, `/skills` | the list of registered agents / skills |
| `/clear` | clears the transcript |
| `/compact` | compacts the context manually |
| `/pending`, `/approve`, `/deny` | list turns awaiting approval · approve · deny |
| `/revoke` | withdraws approvals (`--agent` to reach the agent-wide ones) |
| `/rewakes` | the list of registered rewakes |

---

## 9. LLM integration

The point of it is being able to swap providers. The core holds only the `LlmClient` interface.

| Feature | Entry point | Where |
|------|--------|------|
| the provider abstraction | `LlmClient`, `LlmModel`, `LlmResponse` | core |
| messages / content blocks | `Message`, `Role`, `TextContentBlock`, `ImageContentBlock`, `DocumentContentBlock` | core |
| the tool-call protocol | `ToolDefinition`, `ToolUse`, `ToolUseResult`, `StopReason` | core |
| streaming | `llm.streaming` | core |
| retry · backoff | `llm.retry` | core |
| the call gateway / handling an over-long prompt | `LlmCallGateway`, `PromptTooLongHandler` | core |
| cancellation | `LlmCancellation` | core |
| token measurement | `TokenUsage`, `llm.token`, `llm.usage` | core |
| cost calculation | `Money`, `ModelPriceTable`, `CostSummary` | core |
| the context-window registry | `ModelContextWindowRegistry` | core |
| tagging call metadata | `LlmCallMetadata`, `BoundMetadataLlmClient` | core |
| the OpenAI implementation | — | `aimon-llm-openai` |
| the Anthropic implementation | — | `aimon-llm-anthropic` |

**Related documents**
- [LLM Provider development guide](../features/llm/llm-provider-development-guide.en.md)
- [LLM usage and cost metering](../features/llm/llm-usage-metering.en.md)

---

## 10. Memory

The structure that accumulates observations, derives and reconciles them, and promotes them to
long-term memory. Unlike a session transcript it survives **across sessions**.

| Feature | Entry point | Where |
|------|--------|------|
| recording observations | `Observation`, `ObservationStore`, the `Observe` tool | core |
| representations (derived memory) | `Representation`, `RepresentationStore`, `memory.deriver` | core |
| workspaces / peer views | `Workspace`, `WorkspaceStore`, `PeerView`, `WorkspaceAccessPolicy` | core |
| prompt injection | `MemoryContextProvider`, `MemoryInjectionMode` | core |
| reconciliation · dialectic · dreamer | `memory.reconciler`, `memory.dialectic`, `memory.dreamer` | core |
| indexing / redaction | `memory.index`, `memory.redaction` | core |
| the retrieval tools | `MemoryRecall`, `MemorySearch`, `MemoryChat` | core |
| file backend | — | `aimon-memory-file` |
| PostgreSQL backend | — | `aimon-memory-postgres` |
| MongoDB backend | — | `aimon-memory-mongodb` |

**Related documents**
- [memory usage guide](../features/memory/memory-usage-guide.en.md)

---

## 11. Knowledge and the wiki

If memory is "what the agent went through", knowledge is "what was put in from outside".

| Feature | Entry point | Where |
|------|--------|------|
| the knowledge store abstraction | `KnowledgeStore`, `SearchQuery`, `SearchResult`, `KnowledgeScope` | core |
| the keyword-based default implementation | `KeywordKnowledgeStore` | core |
| document chunking / indexing options | `DocumentChunker`, `IndexOptions` | core |
| the embedding client abstraction | `EmbeddingClient` | core |
| the LLM wiki (ingest, synthesize, lint, merge) | `WikiKnowledgeBase`, `WikiKnowledgeStore` | core |
| the wiki tools | `WikiIngest`, `WikiSearch`, `WikiLint`, `WikiStatus` | core |
| the search tool | `KnowledgeSearch` | core |
| the OpenSearch backend (RAG) | — | `aimon-knowledge-opensearch` |

**Related documents**
- [OpenSearch Knowledge Store guide](../features/knowledge/opensearch-knowledge-store-guide.en.md)
- [the LLM Wiki pattern](../references/llm-wiki.md) — where the concept behind `WikiKnowledgeStore` comes from

---

## 12. MCP integration

Joins the tools an external [MCP](https://modelcontextprotocol.io) server provides into the
agent's tool list.

| Feature | Entry point | Where |
|------|--------|------|
| the client / lifetime management | `McpClient`, `McpClientManager`, `McpClientFactory` | core |
| server configuration | `McpServerConfig` | core |
| the MCP tool adapter | `McpTool`, `McpToolSchema` | core |
| the transport layer | `mcp.transport` | core |

**Watch out.** `McpClientManager` is owned by the `AgentRuntime` and closed by
`OrcaAgentRuntime.close()` — do not close it yourself.

---

## 13. Scheduling

Fires agent work off a cron expression or a one-shot schedule. The scheduling components are
**application-scoped** and stay alive independently of an `AgentRuntime` being destroyed.

| Feature | Entry point | Where |
|------|--------|------|
| the scheduling engine | `SchedulingEngine`, `SchedulingEngineBuilder` | core |
| scheduled tasks | `ScheduledTask`, `ScheduledTaskManager` | core |
| routines (multi-step scheduled execution) | `RoutineExecutor`, `RoutineStep` | core |
| the execution guard / quotas | `ScheduledExecutionGuard`, `scheduling.quota` | core |
| the repository abstraction | `scheduling.repository` | core (in-memory by default) |
| the tools | `schedule_task`, `list_scheduled_tasks`, `cancel_scheduled_task` | core |
| the Quartz cluster backend | — | `aimon-scheduling-quartz` |

**Watch out.** `ScheduledTask.boundRuntimeId` references an **agent-scoped** id, so the runtime
still resolves when the cron re-fires after the original session has ended. That is why you must
not mint a new `AgentRuntimeId` per execution.

**Related documents**
- [Quartz scheduling deployment guide](../features/scheduling/quartz-scheduling-web-deployment-guide.en.md)

---

## 14. Filesystem and shell

Abstracts the file world the agent sees, and its shell, so that remote storage or an isolated
environment can be swapped in for the local disk.

| Feature | Entry point | Where |
|------|--------|------|
| the virtual filesystem | `VirtualFileSystem`, `FileMetadata`, `PathValidator` | core |
| the local implementation / the scope-restricting wrapper | `filesystem.impl.local`, `ScopedVirtualFileSystem` | core |
| usage and backend status | `FileSystemUsage`, `BackendStatus`, `BackendType` | core |
| the virtual shell | `VirtualShell`, `ShellCommand`, `ShellCommandResult`, `ExecutionOptions` | core |
| the MongoDB GridFS backend | — | `aimon-filesystem-gridfs` |
| the AWS S3 backend | — | `aimon-filesystem-s3` |

---

## 15. Sandbox and browser

| Feature | Entry point | Where |
|------|--------|------|
| the sandbox abstraction | the sandbox SPI | `aimon-sandbox` |
| the Docker implementation | — | `aimon-sandbox-docker` |
| the Kubernetes implementation | — | `aimon-sandbox-kubernetes` |
| the sandbox tools | `RunSandbox`, `CopyToSandbox`, `RestartSandbox`, `DeleteSandbox` | `aimon-sandbox-*` |
| browser automation | the `Browser` tool, `BrowserSession` | `aimon-browser-playwright` |

**Related documents**
- [browser tool guide](../features/tool/browser-tool-guide.en.md)

---

## 16. Observability

| Feature | Entry point | Where |
|------|--------|------|
| execution tracing | `Tracer`, `TraceSpan`, `SpanContext`, `SpanType` | core |
| exporting / storing spans | `SpanExporter`, `TraceSpanStore` | core |
| redacting sensitive information | `SpanRedactor`, `KeyPatternSpanRedactor`, `TracePayloadPolicy` | core |
| automatic instrumentation of LLM calls | `TracingLlmClient` | core |
| the system status report | `SystemStatus`, `SystemStatusProvider`, `StatusSection` | core |
| the execution event stream | `AgentExecutionEvent` | core |
| token and cost aggregation | `SessionTotals`, `CostSummary` | core |

**Related documents**
- [execution tracing guide](../features/observability/execution-tracing-guide.en.md)
- [LLM usage and cost metering](../features/llm/llm-usage-metering.en.md)

---

## 17. Permissions and credentials

| Feature | Entry point | Where |
|------|--------|------|
| tool permission patterns (the `Bash(git:*)` · `Read(/tmp/**)` forms) | `agent.tool.permission`, `ToolPermissionSubjectAware` | core |
| tool permission rules (for tools no single value can express) | `CustomToolPermissionAware`, `CustomToolPermissionRule` | core |
| the permission hooks | `PermissionRequestHook`, `PermissionDeniedHook` | core |
| skill approval scopes (turn / session / agent) | `skill.policy.*` | core |
| identity representation | `Principal` (user / group / system / service) | core |
| the credential store | `CredentialStore`, `InMemoryCredentialStore` | core |

**Related documents**
- [tool development guide › the permission system](../features/tool/tool-development-guide.en.md)

---

## What is not in this document

- **why it was designed that way** → [`../design/`](../design/)
- **what was deliberately deferred** → [`../design/backlog/`](../design/backlog/)
- **version upgrade procedures** → [`../migration/`](../migration/)
- **contributing, building and publishing** → [`../project/`](../project/)

## Related documents

- [`architecture.en.md`](architecture.en.md) — the reference for the core abstractions
- [`glossary.en.md`](glossary.en.md) — the dictionary of terms and lifetimes
- [`scope-model.en.md`](scope-model.en.md) — the lifetime, ownership and teardown rules
- [`../features/`](../features/) — the index of per-feature guides
