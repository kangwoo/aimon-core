---
translated_from: docs/features/skill/builtin-agent-skill-guide.md
source_commit: 710a611
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
# No model: runs on the main agent's. Write one only to run on another model: an id the configured provider serves, sent as written (no alias is resolved).
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
# No model: runs on the main agent's. Write one only to run on another model: an id the configured provider serves, sent as written (no alias is resolved).
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

> The `shell` action only works in an environment where the host has wired `DefaultShellActionExecutor` (aimon-cli, for instance). The command runs not on the host but in **the execution environment's shell of the execution the hook fires in** — where the same skill's `Bash` calls run, with the workspace as its working directory. The hooks fire **only in the agent this skill forks (and in forks that agent starts)** — not in another session of the same agent, and not for the caller of the skill. A `shell` hook on a guard event such as `preTool` blocks when its command **could not run** (no execution environment, a timeout, a shell failure); for a hook that only observes, put `failOpen: true` on the entry. A hook command reaches the scripts in its own skill directory through the environment variable `$AIMON_SKILL_DIR` (`bash "$AIMON_SKILL_DIR/scripts/guard.sh"`) — the path the skill was staged to in the environment the hook runs in; if it cannot be staged, the command does not run. For the available environment variables and the action semantics, see [AIMON Skill Extensions / hooks](../../references/aimon-skill-extensions.md#hooks--스킬-단위-hook-스코프).

## Invoking a fork-mode skill

What happens when the `review` example above (`execution.mode: fork`, `agent: code-reviewer`) is invoked along each of the two paths.

### Preconditions

1. A SubAgent named `code-reviewer` must be registered (`.aimon/agents/code-reviewer.md`, or a built-in bundle). If it is not, the fork fails immediately without any LLM or SubAgent call.
2. The host must wire the whole SubAgent infrastructure (the six pieces: `Agent`, `SubagentRegistry`, `ToolRegistry`, `HookRegistry`, `UserLocale`, `SubagentExecutionManager`). `aimon-cli` satisfies this by default. Miss any one of them and a fork-mode skill invocation fails with `fork execution is not configured` — deliberate behaviour, so that an inline-only deployment remains possible.

### A fork applies both allow-lists together

A skill's `allowed-tools` does not stop at the fork boundary — it binds **alongside** the target SubAgent's list. A
call either side refuses is not permitted.

| Skill | SubAgent | What the fork gets |
|------|----------|-----------------|
| (none) | `Read, Grep` | `Read, Grep` — the skill restricts nothing, so the SubAgent's list stands |
| `Read` | (none) | `Read` — the SubAgent restricts nothing, so the skill's list stands |
| `Read, Write` | `Read, Grep` | `Read` — only what is on both |
| `Bash` | `Bash(git:*)` | `Bash(git:*)` — the skill does not restrict arguments, so the SubAgent's pattern wins |
| `Read` | `Write` | **the fork fails** — nothing is common to both |

The last row fails for safety. An empty allow-list means **"no restrictions"** in this codebase, so handing on the
intersection of two disjoint lists as an empty list would **invert the strictest configuration into the loosest**. It
is refused instead of handed on, and the error names both lists.

Two different patterns (`Bash(git:*)` and `Bash(npm:*)`) are **dropped**. The intersection of two globs is not
computable in general, and guessing wide would grant what one side refused. An identical pattern survives as it is.

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

> The bootstrap copies the bundled skill tree into the control store once and rebuilds the VFS-based registry on top of
> that. The bundle registry a loader produces (`ClasspathSkillRepository` / `PathSkillRepository`) stays as the fallback
> for skills the copy skipped, and either way the supporting files reach the model through `${AIMON_SKILL_DIR}`
> staging — see [Materializing bundled skill resources](#materializing-bundled-skill-resources) below.

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
**materialized (copied)** into the control store (`.aimon/bundled-skills/<name>/`) at bootstrap and staged into the
workspace's `.aimon-staged/` when the skill is used, so the Agent's `Read` and `Bash` tools can reach them whichever
loader — FileSystem or JAR — brought the bundle in. For how that works, see
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

The bootstrap uses `BundledSkillMaterializer` to copy the bundled skill tree into the **control store** at
`bundled-skills/<skill-name>/` (locally `{workspace}/.aimon/bundled-skills/`). The control store is invisible to the
model's file tools, so this copy is not a file the agent reads directly — it is the source the staging of the next
section reads from.

- **Independent of how it was loaded**: `ClasspathResourceTreeWalker` handles both `file:` and `jar:` URLs, so it
  behaves identically whether you run from an IDE, from `gradle run`, or from a packaged JAR.
- **Overwritten at boot**: every boot empties the target directory and copies again, so the control-store copy always
  matches the deployed classpath contents.
- **Registry priority**: the final `CompositeSkillRegistry` is composed in the order
  `[the classpath bundle (fallback) < the materialized VFS bundle < the user's .aimon/skills]`. The materialized VFS
  layer shadows the classpath layer of the same name, and the user's skill shadows that. Where materialization failed
  for a skill, the classpath fallback goes on providing at least the body.

### Referring to a skill's own files with `${AIMON_SKILL_DIR}`

When a skill body refers to a file relative to its own directory, using the `${AIMON_SKILL_DIR}` variable is
recommended:

```markdown
Load this skill's template: @${AIMON_SKILL_DIR}/templates/report.md
Run the helper: !`python ${AIMON_SKILL_DIR}/scripts/run.py`
```

`${AIMON_SKILL_DIR}` is always **the path the execution environment returned when it staged the skill**
(`ExecutionEnvironment.stage`, design [`design/tool/execution-environment.md`](../../design/tool/execution-environment.md)
§4.4). On the skill's first use its directory is copied into the workspace at
`{workspace}/.aimon-staged/<skill-name>/<contentKey>/`, and the renderer (`DefaultSkillContentRenderer`) substitutes that
path. It is the same whichever repository the skill came from — a user skill (VFS), a host path
(`PathSkillRepository`, read through a read-only local VFS), or the classpath — so the model's shell and file tools
see the same path. Activating a skill also lists its supporting files in the ToolResult's `Available Files` section as
`name → staged path` (an arbitrary directory is exposed under `Other Files`), so the model can `Read` one directly by
that path as well.

- **A read-only copy.** The file tools cannot write under `.aimon-staged/`. The copy carries no execute bit, so run
  scripts through an interpreter — `bash x.sh`, `python3 x.py` (not `./x.sh`).
- **Content-addressed.** `contentKey` hashes the whole skill directory: the same content gives the same path, a change
  gives a new one. The hash is computed once when the registry reads the skill — so startup reads every skill file once.
- **After editing a skill on disk**, the skill still works, and what gets copied depends on when you edited it. If this
  process has **not used the skill yet** and no copy of the loaded version exists, the files on disk now are copied to the
  `contentKey` path of that content, with one warning in the log. If you edited it **after it was used**, the copy of the
  loaded version keeps being served. Either way, what was read from `SKILL.md` — the body, tool restrictions, hooks —
  changes only when the registry is reloaded or the application is restarted.
- **`.stageignore`** (a gitignore subset: globs, `dir/`, `!`, `#`) in the skill directory keeps large assets out of the
  copy. One skill directory stages at most 50 MB by default (starter property `aimon.environment.staging.max-bytes`).
- **A skill installed as a link.** In the `skills/` directory of a bundle read from disk, a skill directory — or a
  file or directory inside one — may be a symbolic link. A link is followed only when its real path lies inside
  `skills/` or inside an **allowed link root**; a skill with a link that points anywhere else is not loaded (that skill
  only, with a warning naming the link). There are no allowed link roots by default. If a shared helper is linked as
  `skills/foo -> /opt/shared-skills/foo`, name that directory — starter `aimon.skill.allowed-link-roots`, CLI
  `agent.allowedSkillLinkRoots`, or `AimonStackSpec.builder().allowedSkillLinkRoots(...)` when assembling by hand.
  Absolute paths only: a relative path, an empty entry or `/` fails startup, and a directory that does not exist is
  accepted and allows nothing. A bundle inside a jar has no links, so the setting does not reach it, and user skills
  under `.aimon/skills` are read through the workspace file system, which follows no link at all.
- `.aimon-staged/` holds copies only. The local provider writes `.aimon-staged/.gitignore` (`*`) with the first
  copy, so it does not need a line in the project's `.gitignore` (an existing file there is left alone).

### The skill body's render variables (`${AIMON_*}`)

The five below are all the built-in variables `DefaultSkillContentRenderer` substitutes in a skill body. They are for
**substitution in the body text**, and are an entirely separate channel from the shell process environment variables
injected into declarative hooks (`SkillHookEnv`'s `AIMON_*`) — the renderer never reads `System.getenv`. One name
exists on both sides, `AIMON_SKILL_DIR`: in the body, `${AIMON_SKILL_DIR}` is replaced with the path staged into the
environment of the execution that invoked the skill, and in a hook command the environment variable `$AIMON_SKILL_DIR`
is the path staged into the environment of **the execution the hook fires in** (the skill's fork). When the fork is
placed in another environment the two values differ.

| Variable | Value | Scope |
|------|----|------|
| `${AIMON_SKILL_DIR}` | The path the skill directory was staged to in this execution's environment (`ExecutionEnvironment.stage`) | Per skill |
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

NOTE: `RenderContext` is filled from the tool context of the run that invoked the skill (`SkillRenderContextAccess`).
`SkillTool`, which the model calls as a tool, and the `/skill-name` slash invocation (`SkillBackedCommandExecutor`) go
through the same helper, so the two paths substitute the same values. A slash invocation carries the session id, and
`${AIMON_USER}` is the `Principal` of the caller who typed the command. A routine step that calls the `Skill` tool
renders with the step's tool context (`AGENT_RUNTIME_ID` · `PRINCIPAL` · `EXECUTION_ID`).
