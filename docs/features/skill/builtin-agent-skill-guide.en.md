---
translated_from: docs/features/skill/builtin-agent-skill-guide.md
source_commit: 8830d022
---

# Built-in Agent/Skill Guide

## Overview

The AIMON framework ships **built-in Agents and Skills** that are usable straight away. You can use the default Agents and Skills immediately, without writing any file into a `.aimon/agents/` or `.aimon/skills/` directory.

Built-in Agents and Skills are packaged as an **AgentBundle**. Each Agent may carry a bundle of its own (subagents, skills), and the built-in and user-defined ones are composed through the **Composite Registry pattern**. Where the names are the same, the user-defined one overrides the built-in.

## Core concepts

### AgentBundle

`AgentBundle` is an immutable value object that ties an Agent together with its associated SubagentRegistry and SkillRegistry.

```java
AgentBundle bundle = AgentBundle.builder()
    .agent(myAgent)
    .subagentRegistry(subagentRegistry)  // optional
    .skillRegistry(skillRegistry)        // optional
    .build();
```

- `agent` (required): the Agent definition
- `subagentRegistry` (optional): the subagent registry bundled with this Agent
- `skillRegistry` (optional): the skill registry bundled with this Agent

### AgentBundleRegistry

`AgentBundleRegistry` is the central registry that manages AgentBundle instances by Agent name. `DefaultAgentBundleRegistry` provides a thread-safe implementation backed by a `ConcurrentHashMap`.

```java
AgentBundleRegistry registry = new DefaultAgentBundleRegistry();
registry.register(bundle);

Optional<AgentBundle> found = registry.findByName("default");
List<AgentBundle> all = registry.findAll();
```

### AgentBundleLoader

`AgentBundleLoader` is the interface for loading an AgentBundle from various sources. Three implementations are provided:

| Loader | Source | index file | Supporting files |
|--------|------|-----------|--------------|
| `ClasspathAgentBundleLoader` | The classpath (a JAR) | Required | Unsupported |
| `FileSystemAgentBundleLoader` | The file system (an NIO Path) | Not required | Fully supported |
| `AdaptiveAgentBundleLoader` | Detected automatically | Automatic | Automatic |

`AdaptiveAgentBundleLoader` detects the resource URL's protocol and uses `FileSystemAgentBundleLoader` for `file://`, and `ClasspathAgentBundleLoader` for anything else (a JAR, say).

## How to use them

Built-in Agents and Skills load automatically, with no configuration.

**Using an Agent (TaskTool):**

```
/task explore "find the project's main entry points"
```

**Using a Skill (SkillTool):**

```
/skill commit
```

## How to override

A user-defined Agent/Skill that uses **the same name** as a built-in overrides it automatically.

**An Agent override example:**

Create the file `.aimon/agents/explore.md` and the user definition is used instead of the built-in `explore`:

```markdown
---
name: explore
description: "A custom exploration agent"
allowed-tools: Read, Grep, Glob, Bash
model: sonnet
---
You are an agent that analyses a codebase in depth.
...
```

**A Skill override example:**

Create the file `.aimon/skills/commit/SKILL.md` and the user definition is used instead of the built-in `commit`:

```markdown
---
name: commit
description: "The team's commit convention guide"
---
# Team commit message rules
...
```

## Disabling a built-in

To disable a particular built-in, create a file of the same name with empty content:

```markdown
---
name: explore
description: "disabled"
---
```

## Adding a custom Agent/Skill

You can add new Agents and Skills alongside the built-ins. The built-in and the user-defined ones are offered together.

**Adding a custom Agent:**

`.aimon/agents/my-analyzer.md`:

```markdown
---
name: my-analyzer
description: "A performance analysis agent"
allowed-tools: Read, Grep, Bash
model: sonnet
---
You are an agent specialised in performance analysis.
...
```

**Adding a custom Skill:**

`.aimon/skills/review/SKILL.md`:

```markdown
---
name: review
description: "A code review guide"
---
# Code review checklist
...
```

## Skill frontmatter fields

The YAML frontmatter at the top of a skill's `SKILL.md` accepts the [Agent Skills standard](../../references/agentskills-specification.md) fields together with AIMON's extension fields. For the detailed semantics, see [AIMON Skill Extensions](../../references/aimon-skill-extensions.md).

| Field | Origin | Type | Required | Summary |
|------|------|------|:---:|------|
| `name` | Standard | string | ✓ | The skill identifier (lowercase/digits/hyphen, 1–64 characters) |
| `description` | Standard | string | ✓ | What it is for and when to use it (1–1024 characters) |
| `license` | Standard | string |  | An SPDX licence identifier |
| `compatibility` | Standard | string |  | A note on the compatible environment |
| `metadata` | Standard | mapping |  | Free-form key-value pairs |
| `allowed-tools` | Standard | string |  | A space-separated `AllowedTool` list (for example `Read Bash(git:*)`) |
| `arguments` | AIMON | list\<string\> |  | The positional argument names (mapped to `$1..$N`) |
| `invoke.user` / `invoke.model` | AIMON | boolean |  | Whether user (`/skill`) and model invocation are permitted |
| `max-iterations` | AIMON | integer |  | The ReAct loop ceiling for a user invocation (100 by default) |
| `execution.mode` | AIMON | `inline`·`fork` |  | Running inline in the parent agent vs forking a subagent (`inline` by default) |
| `execution.agent` | AIMON | string | (when fork) | The name of the SubAgent to delegate to when `mode: fork` |
| `hooks` | AIMON | mapping |  | The `deny`/`shell` actions per `preTool`/`postTool`/`onStart`/`onStop` event (fires in fork mode only) |

An example (fork mode):

```yaml
---
name: review
description: "Run code review via the code-reviewer subagent."
arguments: [target]
invoke:
  user: true
  model: false
execution:
  mode: fork
  agent: code-reviewer
---
Review the following: $1
```

An example (hooks — used together with fork mode):

```yaml
---
name: review
description: "Run code review via the code-reviewer subagent."
execution:
  mode: fork
  agent: code-reviewer
hooks:
  preTool:
    - matcher: "Bash"
      action: { type: deny, reason: "Bash is not allowed inside review skill" }
  onStop:
    - action: { type: shell, command: "echo review done success=$AIMON_SUCCESS" }
---
Review the following: $1
```

> The `shell` action only works in an environment where the host has wired `DefaultShellActionExecutor` (aimon-cli, for instance). For the available environment variables and the action semantics, see [AIMON Skill Extensions / hooks](../../references/aimon-skill-extensions.md#hooks--스킬-단위-hook-스코프).

## Invoking a fork-mode skill

What happens when the `review` example above (`execution.mode: fork`, `agent: code-reviewer`) is invoked along each of the two paths.

### Preconditions

1. A SubAgent named `code-reviewer` must be registered (`.aimon/agents/code-reviewer.md`, or a built-in bundle). If it is not, the fork fails immediately without any LLM or SubAgent call.
2. The host must wire the whole SubAgent infrastructure (the six pieces: `Agent`, `SubagentRegistry`, `ToolRegistry`, `HookRegistry`, `Environment`, `SubagentExecutionManager`). `aimon-cli` satisfies this by default. Miss any one of them and a fork-mode skill invocation fails with `fork execution is not configured` — deliberate behaviour, so that an inline-only deployment remains possible.

### Invoking with a slash (when `invoke.user: true`)

In the REPL:

```
> /review src/main/java/Foo.java
```

The flow:

1. `OrcaAgentExecutor` recognises input beginning with `/` as a command.
2. `SkillBackedCommandRegistry` finds the `review` skill and routes it through `SkillBackedCommandExecutor` → `LlmSkillExecutor`.
3. The body is rendered — `src/main/java/Foo.java` is substituted at `$1`, giving `Review the following: src/main/java/Foo.java`.
4. `OrcaAgentExecutor` resolves the fork executor on every slash invocation and passes it in the `ToolContext`, so `LlmSkillExecutor` delegates the fork to that executor.
5. The `code-reviewer` SubAgent is spawned in a fresh context, and the rendered body becomes that SubAgent's goal (its first user message).
6. The SubAgent's final answer is printed to the screen as the response to `/review`, exactly as it is (with no `=== Skill Forked ===` wrapper).

### An LLM invocation (when `invoke.model: true`)

The LLM invokes it through the `Skill` tool on its own judgement — the user need not type `/review`:

```
LLM: This looks like it needs a code review — I will call Skill(skill="review", args="src/main/java/Foo.java").
```

The flow is the same as the slash path, but when the result goes back to the LLM, `SkillTool` wraps it in this form:

```
=== Skill Forked ===
Skill: review
Agent: code-reviewer

Final Answer:
<the SubAgent's final answer>
```

Seeing that block, the LLM recognises that the fork has finished and composes a tidied answer for the user.

### The failure messages you meet most often

| Message | Cause |
|--------|------|
| `Skill 'review' references unknown subagent 'code-reviewer'` | `execution.agent` is not in the SubagentRegistry. A missing SubAgent file, or a typo in the name. |
| `Skill 'review' declares execution.mode=fork but fork execution is not configured` | An environment where the host has not wired the SubAgent infrastructure (the NoOp fallback). |
| `Skill 'review' is declared as fork mode but has no execution.agent set` | The YAML has `execution.mode: fork` but is missing `agent`. |
| `Cannot fork skill 'review': agent runtime ID not available in tool context` | A non-standard path calling `LlmSkillExecutor` directly from outside `OrcaAgentExecutor`. It does not occur on a normal invocation. |

For the internal wiring details (which component is resolved where), see [AIMON Skill Extensions / Fork executor wiring](../../references/aimon-skill-extensions.md#fork-executor-와이어링).

## Architecture

### Loading an AgentBundle

```
AdaptiveAgentBundleLoader
├── file:// protocol → FileSystemAgentBundleLoader
│   ├── Agent ← {basePath}/{name}/agent.md
│   ├── SubagentRegistry ← PathSubagentRepository ({basePath}/{name}/agents/)
│   └── SkillRegistry ← PathSkillRepository ({basePath}/{name}/skills/)
│       └── supporting files supported (scripts/, references/, assets/)
│
└── JAR protocol → ClasspathAgentBundleLoader
    ├── Agent ← {basePath}/{name}/agent.md
    ├── SubagentRegistry ← ClasspathSubagentRepository (an index file is required)
    └── SkillRegistry ← ClasspathSkillRepository (an index file is required)
```

> The bundled SkillRegistry a loader produces reliably provides nothing but the SKILL.md body (`ClasspathSkillRepository`
> returns an empty map for the supporting files, and `PathSkillRepository` points at an OS absolute path outside the
> workspace). So the bootstrap copies the bundled skill tree into the workspace VFS once and rebuilds the VFS-based
> registry on top of that — see [Materializing bundled skill resources](#materializing-bundled-skill-resources) below.

### The AgentBundle registry

```
AgentBundleRegistry (the central registry)
└── DefaultAgentBundleRegistry (ConcurrentHashMap-backed)
    ├── register(AgentBundle)
    ├── findByName(agentName) → Optional<AgentBundle>
    ├── findAll() → List<AgentBundle>
    └── unregister(agentName)
```

### The Composite Registry (composing built-in + user-defined)

```
CompositeSubagentRegistry / CompositeSkillRegistry
├── DefaultSubagentRegistry (builtin)     ← the AgentBundle's bundled registry
│   └── ClasspathSubagentRepository / PathSubagentRepository
└── DefaultSubagentRegistry (user)        ← .aimon/agents/
    └── VfsSubagentRepository

Lookup priority: user > builtin (later in the list is higher priority)
Listing: both summed (user overrides an identical name)
```

## The bundle directory structure

Each Agent bundle follows this directory structure:

```
{basePath}/{agent-name}/
├── agent.md              ← the Agent definition file (required)
├── agents/               ← the bundled subagent definitions (optional)
│   ├── index             ← the subagent list (required for classpath loading)
│   └── explore.md
└── skills/               ← the bundled skill definitions (optional)
    ├── index             ← the skill list (required for classpath loading)
    └── commit/
        ├── SKILL.md
        ├── scripts/      ← supporting scripts
        ├── references/   ← supporting reference files
        ├── assets/       ← supporting asset files
        └── templates/    ← an arbitrary directory is preserved as it is too
```

The supporting files (`scripts/`, `references/`, `assets/`, and an arbitrary directory such as `templates/`) are
**materialized (copied)** into the workspace VFS (`.aimon/bundled-skills/<name>/`) at bootstrap, so the Agent's `Read`
and `Bash` tools can reach them whichever loader — FileSystem or JAR — brought the bundle in. For how that works, see
[Materializing bundled skill resources](#materializing-bundled-skill-resources) below.

### Where the classpath resources live

The built-in bundles are included in the `aimon-core` module's classpath resources:

```
modules/aimon-core/src/main/resources/
└── agents/
    └── {agent-name}/
        ├── agent.md
        ├── agents/
        │   ├── index
        │   └── *.md
        └── skills/
            ├── index
            └── {skill-name}/
                └── SKILL.md
```

To add a built-in Agent/Skill, add the resource files to match the structure above, and update the `index` file for classpath loading.

## Materializing bundled skill resources

A bundled skill's supporting files (`scripts/`, `references/`, `assets/`, `templates/` and other arbitrary
directories) live on the classpath — inside a JAR, or in the `build/resources` tree. That location is unreachable from
the workspace `VirtualFileSystem` the Agent's `Read` and `Bash` tools see: a JAR entry is not a file, and an unpacked
resource resolves to an OS absolute path outside the workspace sandbox.

To solve it, the bootstrap (`AgentSetupFactory`) uses `BundledSkillMaterializer` to copy the bundled skill tree into
the workspace VFS at `.aimon/bundled-skills/<skill-name>/`.

- **Independent of how it was loaded**: `ClasspathResourceTreeWalker` handles both `file:` and `jar:` URLs, so it
  behaves identically whether you run from an IDE, from `gradle run`, or from a packaged JAR.
- **Overwritten at boot**: every boot empties the target directory and copies again, so the workspace copy always
  matches the deployed classpath contents.
- **Registry priority**: the final `CompositeSkillRegistry` is composed in the order
  `[the classpath bundle (fallback) < the materialized VFS bundle < the user's .aimon/skills]`. The materialized VFS
  layer shadows the classpath layer of the same name, and the user's skill shadows that. Where materialization failed
  for a skill, the classpath fallback goes on providing at least the body.

### Referring to a skill's own files with `${AIMON_SKILL_DIR}`

After materialization each skill has a trustworthy base directory (`Skill#getBaseDir()`). When a skill body refers to a
file relative to its own directory, using the `${AIMON_SKILL_DIR}` variable is recommended:

```markdown
Load this skill's template: @${AIMON_SKILL_DIR}/templates/report.md
Run the helper: !`python ${AIMON_SKILL_DIR}/scripts/run.py`
```

The renderer (`DefaultSkillContentRenderer`) substitutes `${AIMON_SKILL_DIR}` with the skill's base directory. And
activating a skill also lists every supporting file in the ToolResult's `Available Files` section as
`name → full VFS path` (an arbitrary directory is exposed under `Other Files`), so the model can `Read` one directly by
its full path as well.

### The skill body's render variables (`${AIMON_*}`)

The five below are all the built-in variables `DefaultSkillContentRenderer` substitutes in a skill body. They are for
**substitution in the body text**, and are an entirely separate channel from the shell process environment variables
injected into declarative hooks (`SkillHookEnv`'s `AIMON_*`) — the renderer never reads `System.getenv`.

| Variable | Value | Scope |
|------|----|------|
| `${AIMON_SKILL_DIR}` | The skill's base directory (`Skill#getBaseDir()`) | Per skill |
| `${AIMON_AGENT_RUNTIME_ID}` | The `AgentRuntimeId` value — `agent:<name>` or `agent:<name>:<discriminator>` | **Per agent** |
| `${AIMON_SESSION_ID}` | The `SessionId` value. Filled in **only when the rendering execution is a session's turn** | **Per session** |
| `${AIMON_EXECUTION_ID}` | The `ExecutionId` value — the identity of an execution with no session of its own (a subagent fork, a skill fork, a scheduled routine). It is node-local, and **no persistent store is keyed by this id** — it is written as a fork's transcript label and so does survive a restart, but that snapshot is looked up by task id, so what remains is a name, not a key. **Empty** when the execution is a session's turn | **Per execution** |
| `${AIMON_USER}` | The caller's `Principal#getDisplayName()` | Per caller |

Where a value cannot be found, the empty string is substituted and a WARN is left behind. A `${VAR}` not in the list
above is looked up in `RenderContext#getAdditionalVariables()`, and if it is not there either it stays in the body as it is.

IMPORTANT: the three id variables **have different lifetimes and are not substitutes for one another.**
`${AIMON_AGENT_RUNTIME_ID}` is deterministic **per agent** — every session of the same agent and every cron re-fire see
the same string, so use it where something must be unique per execution (a working directory like
`/tmp/work/${AIMON_AGENT_RUNTIME_ID}`, say) and concurrently running sessions share the same path.

`${AIMON_SESSION_ID}` and `${AIMON_EXECUTION_ID}` are **a mutually exclusive pair** — the renderer always substitutes
both literals, but exactly one of them receives a value. When the rendering execution is a session's turn the session
id side is filled and the execution id side becomes `""`; for an execution with no session of its own (a subagent fork,
a skill fork, a scheduled routine) it is the other way round. The WARN left on the empty side **names the opposite
variable for you** (`resolveSessionId` / `resolveExecutionId`). Not falling back to the other side when one is absent is
deliberate: a fork used to render a freshly issued session id in this slot, and the body could not tell that value apart
from the user session's id. Now, ask for a session id and you get a session id or you get nothing.

So for **a working directory unique per execution**, use this pair rather than `${AIMON_AGENT_RUNTIME_ID}`. For a skill
that is only ever activated on a session turn, `${AIMON_SESSION_ID}` alone is enough, but a skill that may also be
activated from a fork or a routine writes **both** variables — exactly one expands in any situation, so the path stays
unique:

```markdown
Working directory: /tmp/work/${AIMON_SESSION_ID}${AIMON_EXECUTION_ID}
```

> `${AIMON_SESSION_ID}` was a deprecated alias of `${AIMON_AGENT_RUNTIME_ID}` for one release. The session-first
> overhaul **completed** that deprecation — the alias branch was deleted, and the literal is bound to the session id its
> name promised from the start. A body that ignored the WARN and went on using the alias now receives *a different value*.

NOTE (a current limitation): the production path that actually fills `RenderContext` is `SkillTool` (the path where the
model invokes a skill as a tool) and **that one alone**. The `/skill-name` slash invocation
(`SkillBackedCommandExecutor`) and a routine step (`RoutineExecutor`) render with an empty context, so all five
variables above are substituted with `""` — `${AIMON_SKILL_DIR}` is no exception. Connecting a context to those paths is
separate work.
