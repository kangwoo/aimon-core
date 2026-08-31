# AIMON Core

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Maven Central](https://img.shields.io/maven-central/v/at.aimon.core/aimon-core.svg)](https://central.sonatype.com/artifact/at.aimon.core/aimon-core)
[![Build](https://github.com/kangwoo/aimon-core/actions/workflows/build.yml/badge.svg)](https://github.com/kangwoo/aimon-core/actions/workflows/build.yml)

**AIMON** is a ReAct (Reasoning and Acting) agent framework for Java, designed for building autonomous LLM-powered agents. While it ships with operations-focused defaults (alert triage, root cause analysis, runbook execution), the core framework is general-purpose and can be embedded in any Java application.

> **Project status:** `0.2.x` — public API may change between minor versions until `1.0`.
> See [CHANGELOG.md](CHANGELOG.md) for release notes,
> [docs/project/api-stability.md](docs/project/api-stability.md) for what `0.x` does and does not promise,
> and [docs/project/roadmap.md](docs/project/roadmap.md) for what `1.0` is waiting on.

## Highlights

- **ReAct loop** — iterative reason → act → observe cycle with 13 pluggable hook points
- **Multi-agent orchestration** — delegate to sub-agents, or script deterministic multi-agent workflows
- **Pluggable LLM providers** — OpenAI and Anthropic out of the box; add your own via `LlmClient`
- **Skill system** — reusable Markdown-based knowledge packages teach agents domain tasks
- **Tool ecosystem** — Read/Write/Edit/Grep, Bash, WebSearch/WebFetch, Task, Todo, Browser (Playwright), Sandbox (Docker/Kubernetes), and custom tools
- **Persistent sessions** — pluggable session storage (Redis, PostgreSQL, MongoDB, or in-memory) with multi-node routing
- **Long-term memory** — observations accrue into derived representations across sessions
- **Knowledge & RAG** — keyword store built in, OpenSearch backend optional, plus an LLM wiki
- **Distributed scheduling** — Quartz-based scheduler for clustered cron tasks
- **Virtual filesystem** — abstract VFS with local, GridFS, and S3 backends
- **Sandboxed execution** — run untrusted shell commands in Docker or Kubernetes pods
- **Observability** — execution tracing with redaction, LLM usage/cost metering, streamed execution events

## Module Overview

This is a multi-module Gradle project. The framework core is in `aimon-core`; everything else is opt-in.

### Core

| Module | Purpose |
|---|---|
| `aimon-core` | Agent execution engine, tools, skills, hooks, scheduling abstractions, VFS interfaces |
| `aimon-bom` | `java-platform` bill of materials — import it once and the other modules need no version (from the next release) |
| `aimon-cli` | Interactive REPL CLI built on `aimon-core` (not published — reference application) |

### LLM Providers

| Module | Purpose |
|---|---|
| `aimon-llm-openai` | OpenAI Chat Completion API client (tool calling, multimodal) |
| `aimon-llm-anthropic` | Anthropic Claude Messages API client (tool calling) |

### Storage / Filesystem

| Module | Purpose |
|---|---|
| `aimon-filesystem-gridfs` | MongoDB GridFS implementation of `VirtualFileSystem` |
| `aimon-filesystem-s3` | AWS S3 implementation of `VirtualFileSystem` |

### Knowledge & Memory

| Module | Purpose |
|---|---|
| `aimon-knowledge-opensearch` | OpenSearch-backed knowledge store / retrieval |
| `aimon-memory-file` | File-backed agent memory store |
| `aimon-memory-postgres` | PostgreSQL-backed agent memory store |
| `aimon-memory-mongodb` | MongoDB-backed agent memory store |

### Workflow

| Module | Purpose |
|---|---|
| `aimon-workflow-graaljs` | GraalJS frontend — JS-scripted multi-subagent workflows (`WorkflowJs` tool) |

### Browser & Sandbox

| Module | Purpose |
|---|---|
| `aimon-browser-playwright` | Playwright-driven browser automation Tool |
| `aimon-sandbox` | Persistent sandbox abstraction (run shell commands in isolated environments with VFS sync) |
| `aimon-sandbox-docker` | Docker container backend for `aimon-sandbox` |
| `aimon-sandbox-kubernetes` | Kubernetes Pod backend for `aimon-sandbox` |

### Scheduling

| Module | Purpose |
|---|---|
| `aimon-scheduling-quartz` | Quartz-based `TaskScheduler` for distributed cron scheduling |

### Session Persistence

| Module | Purpose |
|---|---|
| `aimon-session-routing` | Multi-node routing (`SessionRouter`, `LiveSessionCache`, `LiveSessionOpener`) |
| `aimon-session-redis` | Redis-backed `SessionRecordStore` / `SessionLeaseStore` |
| `aimon-session-postgres` | PostgreSQL-backed `SessionRecordStore` / `SessionLeaseStore` |
| `aimon-session-mongodb` | MongoDB-backed `SessionRecordStore` / `SessionLeaseStore` |

The SPIs those backends implement (`SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`,
`SessionSignalBus`, `IdempotencyStore`) live in `aimon-core` under `at.aimon.core.agent.session.*`,
so a distributed backend needs `aimon-core` alone — not `aimon-session-routing`.

### Async Wake-up

| Module | Purpose |
|---|---|
| `aimon-rewake-webhook` | Webhook receiver that wakes suspended executions (async rewake) |

## Getting Started

### Prerequisites

- Java 17 or higher
- An LLM API key — `OPENAI_KEY` or `ANTHROPIC_API_KEY`

### Installation (Maven Central)

Add `aimon-core` and at least one LLM provider:

```kotlin
// build.gradle.kts
dependencies {
    implementation("at.aimon.core:aimon-core:0.2.0")
    implementation("at.aimon.core:aimon-llm-openai:0.2.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>at.aimon.core</groupId>
    <artifactId>aimon-core</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>at.aimon.core</groupId>
    <artifactId>aimon-llm-openai</artifactId>
    <version>0.2.0</version>
</dependency>
```

### Quickstart — Run the CLI

```bash
git clone https://github.com/kangwoo/aimon-core.git
cd aimon-core
export OPENAI_KEY=sk-...
./gradlew :aimon-cli:run
```

Or build a fat JAR:

```bash
./gradlew :aimon-cli:shadowJar
java -jar modules/aimon-cli/build/libs/aimon-cli-*.jar
```

### Embedding in Your Application

The supported path is `aimon-spring-boot-starter`: three properties (`aimon.workspace.root`,
`aimon.llm.api-key`, `aimon.agent-defaults.default-agent`), one injected bean (`AimonSessions`), and a turn
runs. Hosts that are not Spring Boot assemble the same stack from `aimon-bootstrap`.

See [docs/getting-started/embedding-agent-in-application.md](docs/getting-started/embedding-agent-in-application.md)
— the starter path, the property tree, the four scopes, multi-instance deployment, and an appendix for wiring
`OrcaAgentExecutor` by hand. A working application lives in [`samples/aimon-sample-app`](samples/aimon-sample-app).

## Configuration

The CLI reads YAML configuration. Default location: `modules/aimon-cli/src/main/resources/default-config.yaml`.

```yaml
llm:
  provider: "openai"          # or "anthropic"
  baseUrl: "https://api.openai.com/v1"
  apiKey: "${OPENAI_KEY}"     # ${ENV} interpolation supported
  model: "gpt-4o-mini"
  timeout: 60

agent:
  name: "default"

cli:
  colorOutput: true
  showIterations: true
  showToolCalls: true
  streaming: true
```

Run with a custom config:

```bash
./gradlew :aimon-cli:run --args="-c /path/to/config.yaml"
```

### REPL Commands

- Type a request and press Enter
- End a line with `\` for multi-line input
- `/quit` or `/exit` — exit the REPL
- `/help`, `/commands` — list available commands
- `/clear` — clear the conversation history
- `/compact` — compact the conversation to reclaim context
- `/status`, `/agents`, `/skills` — inspect the running system
- `/pending`, `/approve`, `/deny`, `/revoke` — skill approval flow
- `Ctrl+C` — interrupt the current operation
- `Ctrl+D` — exit

## Authoring Agents

Agents are defined as Markdown files with YAML frontmatter:

```yaml
---
name: my-agent
maxIterations: 10
model:
  name: gpt-4o-mini
  temperature: 0.5
  maxTokens: 40000
variables:
  language: Java
  tools: ["Read", "Write", "Bash", "Grep"]
---

# System Prompt
You are a helpful assistant specialized in ...
```

Place definitions under `modules/aimon-cli/src/main/resources/agents/` (or your own resource path when embedding).

## Authoring Skills

Skills package reusable knowledge as Markdown + supporting files:

```
skill-name/
├── SKILL.md        # Frontmatter (name, description, allowed-tools) + system prompt
├── root-files/     # Files materialized into the agent's working directory
├── scripts/        # Executable scripts
├── references/     # Documentation
└── assets/         # Templates and static resources
```

Example `SKILL.md`:

```yaml
---
name: alert-triage
description: Analyze and triage operational alerts
version: 1.0.0
allowed-tools:
  - Read
  - Grep
  - Bash
---

# Alert Triage Process
1. Assess severity and impact
2. Gather relevant context
3. Identify potential root causes
```

Skills live under `<workspace>/skills/`. The bundled `ops-agent` workspace is at `modules/aimon-cli/src/main/resources/workspaces/ops-agent/`.

## Extending

| Extension point | Interface | Guide |
|---|---|---|
| Custom LLM provider | `LlmClient` | [llm-provider-development-guide.md](docs/features/llm/llm-provider-development-guide.md) |
| Custom tool | `Tool` / `AbstractTool` | [tool-development-guide.md](docs/features/tool/tool-development-guide.md) |
| Custom hook | 13 event types (`PreToolHook`, `OnStopHook`, `PermissionRequestHook`, …) | [hook-development-guide.md](docs/features/hook/hook-development-guide.md) |
| Session storage | `SessionRecordStore` / `SessionLeaseStore` | [agent-session-guide.md](docs/features/session/agent-session-guide.md) |
| Filesystem backend | `VirtualFileSystem` | (see GridFS / S3 implementations) |
| Knowledge store | `KnowledgeStore` | [opensearch-knowledge-store-guide.md](docs/features/knowledge/opensearch-knowledge-store-guide.md) |
| Memory store | `ObservationStore` / `RepresentationStore` | [memory-usage-guide.md](docs/features/memory/memory-usage-guide.md) |
| Sub-agent | `Subagent` | [subagent-development-guide.md](docs/features/subagent/subagent-development-guide.md) |
| Task scheduler | `TaskScheduler` | [quartz-scheduling-web-deployment-guide.md](docs/features/scheduling/quartz-scheduling-web-deployment-guide.md) |

### Minimal Tool Example

```java
public class GreetTool extends AbstractTool {

    public GreetTool() {
        super("Greet",
              "Greets the given name.",
              Map.of(
                  "type", "object",
                  "properties", Map.of(
                      "name", Map.of("type", "string", "description", "Name to greet")
                  ),
                  "required", List.of("name")
              ));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        try {
            String name = input.getRequiredString("name");
            return ToolResult.success("Hello, " + name + "!");
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
```

See [docs/features/tool/tool-development-guide.md](docs/features/tool/tool-development-guide.md) for the full pattern (error handling, schema, permission system).

## Build & Test

```bash
./gradlew build                    # Build everything
./gradlew test                     # Run all tests
./gradlew :aimon-core:test         # Tests for a single module
./gradlew format                   # Apply Spotless (Eclipse formatter)
./gradlew checkAll                 # format check + Checkstyle + unit tests (pre-commit verification)
```

Quality tooling:

- **Spotless** with Eclipse formatter (`config/eclipse/eclipse-formatter.xml`)
- **Checkstyle** on main sources (`config/checkstyle/checkstyle.xml`)
- **JaCoCo** code coverage
- **JUnit 5 + AssertJ + Mockito + Testcontainers** for tests

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    User / Application                            │
└───────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
                ┌────────────────────────┐
                │  LiveSession (REPL     │
                │  or programmatic API)  │
                └───────────┬────────────┘
                            │
                            ▼
        ┌────────────────────────────────────────┐
        │  OrcaAgentExecutor (ReAct loop)        │
        │   build prompt → call LLM → run tools  │
        │   → loop until done                    │
        └──────┬──────────────────────────┬──────┘
               │                          │
               ▼                          ▼
        ┌─────────────┐          ┌─────────────────────┐
        │  LlmClient  │          │ ToolExecutionMgr    │
        │  (OpenAI /  │          │  (Read, Write, Bash,│
        │  Anthropic) │          │   Browser, custom…) │
        └─────────────┘          └─────────────────────┘
```

## Documentation

Start at [`docs/README.md`](docs/README.md) for the documentation index. Docs are organized by
**feature**, not by reader role.

- [`docs/overview/features.md`](docs/overview/features.md) — **feature catalog**: what `aimon-core` can do, where each entry point lives, and whether it is built in or a separate module
- [`docs/overview/`](docs/overview/) — architecture reference, glossary, scope/lifetime model
- [`docs/getting-started/`](docs/getting-started/) — embedding AIMON, annotated integration reference
- [`docs/features/`](docs/features/) — per-feature guides (tools, skills, hooks, sessions, workflows, memory, …)
- [`docs/references/`](docs/references/) — Hooks specification, AgentSkills specification
- [`docs/design/`](docs/design/) — design documents and rationale
- [`docs/migration/`](docs/migration/) — version migration guides
- [`docs/project/`](docs/project/) — SOLID principles, publishing, coverage priorities

Note: most documents under `docs/` are written in Korean, matching the project's house style.

## Project Status

This is an early-stage project under active development. The public API may change between `0.x` minor versions; we follow Semantic Versioning starting at `1.0`.

See [CHANGELOG.md](CHANGELOG.md) for release notes.

## Contributing

Contributions are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request, and note our [Code of Conduct](CODE_OF_CONDUCT.md).
[MAINTAINERS.md](MAINTAINERS.md) says who reviews, how long that usually takes, and how to become a maintainer.

To report a security vulnerability, see [SECURITY.md](SECURITY.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
