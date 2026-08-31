---
translated_from: docs/features/README.md
source_commit: a9821d44
---

# Feature Documentation (Features)

This is where you come in when you have to go deep on one feature. Each directory corresponds to **one feature area**, and that area's usage guide, development guide and operations guide sit together in the one folder (the split is by **feature**, not by reader role).

- To skim what exists first → [`../overview/features.en.md`](../overview/features.en.md) (the feature catalogue)
- For why it was designed that way → the **design rationale** links in each section

---

## [`agent-execution/`](agent-execution/) — agent execution

The ReAct loop, turn handling, interruption, the input queue.

| Document | Contents |
|------|------|
| [`command-queue-guide.en.md`](agent-execution/command-queue-guide.en.md) | How user input that arrives mid-turn is queued and handled |
| [`interruptible-tools-guide.en.md`](agent-execution/interruptible-tools-guide.en.md) | External interruption — Ctrl+C, NOW priority input — and `InterruptBehavior` |
| [`system-reminder-convention.en.md`](agent-execution/system-reminder-convention.en.md) | The convention for injecting `<system-reminder>` synthetic context |

Design rationale: [`agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) ·
[`orca-executor.md`](../design/agent-execution/orca-executor.md) ·
[`interceptor.md`](../design/agent-execution/interceptor.md) ·
[`interrupt.md`](../design/agent-execution/interrupt.md) ·
[`compaction.md`](../design/agent-execution/compaction.md) ·
[`artifact.md`](../design/agent-execution/artifact.md)

## [`session/`](session/) — sessions

Conversation persistence, the live session handle, multi-node deployment.

| Document | Contents |
|------|------|
| [`agent-session-tutorial.en.md`](session/agent-session-tutorial.en.md) | Attaching a session for the first time — starting from the smallest example |
| [`agent-session-guide.en.md`](session/agent-session-guide.en.md) | The `LiveSession` API reference and event streaming |
| [`web-session-deployment-guide.en.md`](session/web-session-deployment-guide.en.md) | `SessionRouter` single-node / multi-node deployment |

IMPORTANT: read the difference between a session (`SessionRecord`) and a live session (`LiveSession`) first —
[`../overview/scope-model.en.md` §3](../overview/scope-model.en.md).

Design rationale: [`session-model.md`](../design/session/session-model.md) ·
[`session-model.md`](../design/session/session-model.md) ·
[`routing.md`](../design/session/routing.md) ·
[`backends.md`](../design/session/backends.md) ·
[`backends.md`](../design/session/backends.md)

## [`tool/`](tool/) — tools

The unit through which an agent interacts with the outside world.

| Document | Contents |
|------|------|
| [`tool-development-guide.en.md`](tool/tool-development-guide.en.md) | **The canonical text for building a new tool** — the `Tool` contract, schemas, error handling, permissions |
| [`parallel-tool-execution-guide.en.md`](tool/parallel-tool-execution-guide.en.md) | Running tools in parallel within one batch (`ConcurrencyBehavior`) |
| [`browser-tool-guide.en.md`](tool/browser-tool-guide.en.md) | Configuring and using the Playwright-backed `Browser` tool |

Design rationale: [`parallel-execution.md`](../design/tool/parallel-execution.md) ·
[`tool-search.md`](../design/tool/tool-search.md)

## [`skill/`](skill/) — skills

A declarative capability package bundling prompts, tools and hooks.

| Document | Contents |
|------|------|
| [`builtin-agent-skill-guide.en.md`](skill/builtin-agent-skill-guide.en.md) | The built-in Agent/Skill system and user-defined overrides |

The standard specification: [`agentskills-specification.md`](../references/agentskills-specification.md) ·
[`aimon-skill-extensions.md`](../references/aimon-skill-extensions.md)

Design rationale: [`command-unification.md`](../design/skill/command-unification.md) ·
[`approval-scope.md`](../design/skill/approval-scope.md)

Migration: [`custom-command-to-skill.md`](../migration/custom-command-to-skill.md)

## [`hook/`](hook/) — hooks

Intervening at thirteen points in the lifecycle.

| Document | Contents |
|------|------|
| [`hook-development-guide.en.md`](hook/hook-development-guide.en.md) | Writing a hook — the event types, `HookFeedback` |
| [`hook-config-guide.en.md`](hook/hook-config-guide.en.md) | File-based hook configuration, hot reload, layered merging |

The parity boundary: [`hooks-specification.md`](../references/hooks-specification.md)

Design rationale: [`hook-system.md`](../design/hook/hook-system.md) ·
[`async-rewake.md`](../design/hook/async-rewake.md)

## [`subagent/`](subagent/) — subagents

A sub-agent running in an isolated context.

| Document | Contents |
|------|------|
| [`subagent-development-guide.en.md`](subagent/subagent-development-guide.en.md) | Defining and registering a subagent in code (Java) with `Subagent.builder()` |

Design rationale: [`code-defined-registration.md`](../design/subagent/code-defined-registration.md) ·
[`execution.md`](../design/subagent/execution.md)

## [`workflow/`](workflow/) — workflows

Deterministic orchestration of several subagents.

| Document | Contents |
|------|------|
| [`workflow-cli-guide.en.md`](workflow/workflow-cli-guide.en.md) | The `Workflow`/`WorkflowJs` tools and the `/runs` command from the CLI |
| [`workflow-usage-guide.en.md`](workflow/workflow-usage-guide.en.md) | Assembling, running, backgrounding and resuming in code |

Design rationale: [`workflow.md`](../design/workflow/workflow.md) and
phase [3](../design/workflow/workflow.md) ·
[4](../design/workflow/workflow.md) ·
[5](../design/workflow/workflow.md)

## [`llm/`](llm/) — LLM integration

The provider abstraction and its instrumentation.

| Document | Contents |
|------|------|
| [`llm-provider-development-guide.en.md`](llm/llm-provider-development-guide.en.md) | Implementing a new `LlmClient` |
| [`llm-usage-metering.en.md`](llm/llm-usage-metering.en.md) | Token and cost metering |

Design rationale: [`streaming.md`](../design/llm/streaming.md) ·
[`multimodal-content.md`](../design/llm/multimodal-content.md) ·
[`cancellation.md`](../design/llm/cancellation.md)

## [`memory/`](memory/) — memory

Long-term memory that persists across sessions.

| Document | Contents |
|------|------|
| [`memory-usage-guide.en.md`](memory/memory-usage-guide.en.md) | From recording an observation through to recall |

Design rationale: [`peer-memory.md`](../design/memory/peer-memory.md)

## [`knowledge/`](knowledge/) — knowledge / wiki

Indexing and searching documents fed in from outside.

| Document | Contents |
|------|------|
| [`opensearch-knowledge-store-guide.en.md`](knowledge/opensearch-knowledge-store-guide.en.md) | The OpenSearch RAG Knowledge Store |

A reference pattern: [`llm-wiki.md`](../references/llm-wiki.md)

Design rationale: [`knowledge-and-rag.md`](../design/knowledge/knowledge-and-rag.md) (the interface + keyword search) ·
[`knowledge-and-rag.md`](../design/knowledge/knowledge-and-rag.md) (vector/RAG — supersedes the Phase 2 proposal of the previous document)

## [`scheduling/`](scheduling/) — scheduling

Cron and one-shot scheduled execution, and routines.

| Document | Contents |
|------|------|
| [`quartz-scheduling-web-deployment-guide.en.md`](scheduling/quartz-scheduling-web-deployment-guide.en.md) | Deploying the Quartz cluster scheduler |

Design rationale: [`llm-scheduling-agent.md`](../design/scheduling/llm-scheduling-agent.md)

## [`observability/`](observability/) — observability

| Document | Contents |
|------|------|
| [`execution-tracing-guide.en.md`](observability/execution-tracing-guide.en.md) | Configuring, querying and redacting execution traces |

Design rationale: [`tracing.md`](../design/observability/tracing.md) ·
[`tracing.md`](../design/observability/tracing.md)

---

## Areas without a dedicated guide yet

Features that are in the catalogue but have no separate guide document. For now **the design documents and the Javadoc are canonical**.

| Area | Where to look for now |
|------|-----------|
| MCP integration | [`mcp-tool.md`](../design/integration/mcp-tool.md) |
| Sandboxes | [`sandbox.md`](../design/integration/sandbox.md) · `modules/aimon-sandbox/README.md` |
| Filesystem / shell | the `package-info.java` of `at.aimon.core.filesystem` · `at.aimon.core.shell` |
| Commands (`/compact` and the rest) | [`command-unification.md`](../design/skill/command-unification.md) |
| Permissions / credentials | [`tool-development-guide.en.md` › the permission system](tool/tool-development-guide.en.md) |

## When adding a new document here

- Put it in the feature-area directory. Create one if it does not exist (name the directory after the area name in the catalogue).
- Add one line to the matching section of this index.
- If that feature is not in the [`../overview/features.en.md`](../overview/features.en.md) catalogue, add it there too.
- **Design rationale does not go here** — put it under [`../design/`](../design/) and link to it.
