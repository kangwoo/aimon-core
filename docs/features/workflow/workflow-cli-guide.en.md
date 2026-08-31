---
translated_from: docs/features/workflow/workflow-cli-guide.md
source_commit: 8830d022
---

# Workflow CLI Guide (the aimon-cli view)

> How to turn the workflow feature on and use it from aimon-cli

This document is written for **someone using aimon-cli**.
For how to assemble a workflow directly in Java code, see the
[Workflow usage guide (the library view)](workflow-usage-guide.en.md).

## Table of contents

1. [What you get](#what-you-get)
2. [Turning the feature on](#turning-the-feature-on)
3. The `Workflow` [tool — the predefined strategies](#the-workflow-tool--the-predefined-strategies)
4. The `WorkflowJs` [tool — a JS script](#the-workflowjs-tool--a-js-script)
5. [Foreground and background](#foreground-and-background)
6. The `/runs` [command](#the-runs-command)
7. [What you see on screen](#what-you-see-on-screen)
8. [Troubleshooting](#troubleshooting)
9. [Limits worth knowing](#limits-worth-knowing)

---

## What you get

A workflow is a feature that handles one problem by **running several subagents in a fixed structure**.
Unlike an ordinary conversation, where the agent decides for itself as it goes, a workflow has its
fan-out width, its number of verification rounds and its synthesis stage **fixed in advance, so it is reproducible**.

aimon-cli exposes the feature through two tools. **Both are off by default.**

| Tool | Character | When |
|------|------|------|
| `Workflow` | Three predefined strategies (multi-angle analysis / a judge panel / refutation) | Most of the time. All you supply is a prompt |
| `WorkflowJs` | The user writes the control flow themselves in JavaScript | When there are N things to iterate over, or the stages are one of a kind |

On top of those sits the `/runs` command, for inspecting and stopping background runs.

---

## Turning the feature on

Turn it on in the `cli:` block of the configuration file. The two flags are independent.

```yaml
cli:
  colorOutput: true
  showToolCalls: true
  enableWorkflow: true      # registers the `Workflow` tool
  enableWorkflowJs: true    # registers the `WorkflowJs` tool (GraalJS)
```

Run with the configuration file named:

```bash
./gradlew :aimon-cli:run --args="--config ~/.aimon/aimon.yaml"
```

Without `--config`, the `default-config.yaml` built into the CLI is used (both flags `false`).

**Turning on either flag** also turns on the workflow runner that background execution needs, and
the `/runs` command starts working. With both off, `/runs` only prints a notice.

The GraalJS engine `WorkflowJs` needs is created only when `enableWorkflowJs: true`, and is closed
when the CLI exits. If you are not using it, there is no reason to turn it on.

---

## The `Workflow` tool — the predefined strategies

This is the side where all you supply is a prompt. The LLM calls it on its own when it judges that it
needs to, and the user can also steer it there by asking something like "review this from several angles".

### Parameters

| Parameter | Required | Default | Description |
|---------|:---:|------|------|
| `prompt` | ✅ | — | The question / task / claim to handle |
| `strategy` | | `perspectives` | `perspectives` \| `judge_panel` \| `adversarial_verify` |
| `perspectives` | | `technical,risk,user_impact` | Comma-separated perspective labels |
| `synthesize` | | `true` | Whether the `perspectives` strategy synthesizes |
| `mode` | | `foreground` | `foreground` \| `background` |

### The three strategies

**`perspectives` (the default)** — throws the prompt at several perspective subagents at once, then synthesizes the results into one.

```
3 perspectives analysed in parallel → a synthesizer agent → 1 answer
```

Passing `synthesize: false` skips the synthesis and returns each perspective's analysis as it is, with its label.
Use it when you want to compare the raw perspectives yourself.

**`judge_panel`** — produces several candidate answers, has two judges score each candidate, then synthesizes from the best.
It suits a problem whose solution space is wide enough that "make several and pick" beats "write one and revise it".
The `perspectives` values are used as the angles the candidates are generated from.

**`adversarial_verify`** — **reads the prompt as a claim** and has three skeptics each try to refute it.
Two or more rebuttals means it is rejected; otherwise the verdict is that it survived. This is for fact-checking and risk verification.

### Examples

```
> review this architecture change from the technical, cost and operational angles

[Subagent] workflow:perspective:technical: this architecture change ...
[Subagent] workflow:perspective:cost: this architecture change ...
[Subagent] workflow:perspective:operations: this architecture change ...
[Subagent] workflow:synthesizer: ...
```

```
> verify the claim "removing this cache layer improves p99"
  → adversarial_verify: 2 of 3 skeptics rebutted it → rejected
```

---

## The `WorkflowJs` tool — a JS script

You write the control flow yourself in JavaScript. **The script writes the structure, and the LLM runs only inside each subagent.**

### Parameters

| Parameter | Required | Default | Description |
|---------|:---:|------|------|
| `script` | ✅ | — | The JavaScript source |
| `args` | | `{}` | The input object exposed to the script as the read-only `args` |
| `max_agents` | | none | An upper bound on this run's agent count (**applies in the foreground only**) |
| `mode` | | `foreground` | `foreground` \| `background` |

### What the script can use

The following are injected as globals. This is **all of it** — there is no file access, no network, no Java reflection.

| Global | Signature | Description |
|------|---------|------|
| `agent` | `agent(descriptor)` or `agent(prompt, descriptor?)` | One subagent step (synchronous) |
| `parallel` | `parallel([descriptor, ...])` | A barrier fan-out. Results come back in input order |
| `pipeline` | `pipeline(items, stage1, stage2, ...)` | Stage-by-stage processing |
| `phase` | `phase(title)` | Groups the steps after it under this name |
| `log` | `log(message)` | A progress message |
| `args` | (an object) | The read-only input. Immutable all the way down |
| `console` | `console.log/warn/error/info` | **Disabled by default**. When enabled it is wired to `log` |

A top-level `return` and `await` are both legal (the script is wrapped in an async function to run).

### The agent descriptor

```js
{
  agentType: "reviewer",       // the logical type (the basis for the name and the default system prompt)
  systemPrompt: "...",         // an explicit system prompt (one of these two must be present)
  goal: "...",                 // or prompt: "..."
  schema: { ... },             // a JSON Schema — supply it and a structured object comes back
  isolation: "worktree",       // isolates parallel steps that mutate files
  label: "review:foo.java",    // the display label
  phase: "Review",             // the event group
  model: "...",                // a model override
  tools: ["Read", "Grep"],     // the tool allow-list
  maxIterations: 10
}
```

At least one of `agentType` and `systemPrompt` is required.
Supply a `schema` and `agent(...)` returns the **structured object** as it is. Without one it returns a result view:

```js
{ text, structured, isSuccess, isComplete, completionReason, label }
```

A failed fan-out slot becomes `null`. **Always filter before using the results.**

### Example 1 — a perspective fan-out, then synthesis

```js
phase('Analyze');
const angles = ['technical', 'risk', 'cost'];
const results = parallel(angles.map(a => ({
  agentType: a,
  systemPrompt: `You analyze strictly from the ${a} angle. Be concise.`,
  goal: args.question
})));

phase('Synthesize');
const combined = results
  .filter(Boolean)
  .map((r, i) => `## ${angles[i]}\n${r.text}`)
  .join('\n\n');

return agent({ agentType: 'synthesizer', goal: combined }).text;
```

`args` is passed along with the tool call: `{"question": "what are the risks of this change?"}`

### Example 2 — find, then verify by refutation (pipeline)

```js
const files = args.files;

const reviewed = pipeline(files,
  (prev, file) => ({
    agentType: 'reviewer',
    goal: `Find bugs in ${file}`,
    label: `review:${file}`,
    phase: 'Review'
  }),
  (prev, file) => prev && {
    agentType: 'skeptic',
    goal: `Try to refute these findings. Default to refuted=true if uncertain:\n${prev.text}`,
    schema: {
      type: 'object',
      properties: { refuted: { type: 'boolean' }, reason: { type: 'string' } },
      required: ['refuted']
    },
    label: `verify:${file}`,
    phase: 'Verify'
  }
);

log(`${reviewed.filter(Boolean).length}/${files.length} verified`);
return JSON.stringify(reviewed.filter(Boolean).map(r => r.structured));
```

> **Know exactly what `pipeline` does:** the CLI's JS `pipeline` has **a barrier at every stage**.
> Every item has to finish stage 1 before stage 2 starts. A stage function is called as
> `stage(previousResult, originalItem, index)`, and returning `null` drops that item from the later stages.
> (The Java API's `pipeline` is a barrier-free per-item parallel, so it means something different.)

### Sandbox limits

The script runs in an isolated GraalJS context. The CLI uses the defaults, and they cannot be changed from the configuration file.

| Item | Default |
|------|-------|
| Maximum statements executed | 10,000,000 |
| Wall-clock timeout | 30 minutes |
| `console` | Disabled |
| Host access | None (`HostAccess.NONE`) — no files, no network, no Java reflection |
| Determinism mode | `NONE` (set to `STRICT` it seals `Date`, `Math.random`, `Intl.DateTimeFormat` and `performance.now`) |

**A genuinely asynchronous script is also unsupported.** When a promise does not settle synchronously
you get a "workflow promise did not settle synchronously" error. `agent`/`parallel`/`pipeline` are
already synchronous calls, so this is not a constraint on writing an ordinary workflow.

As for the return value: a string comes back as it is, an object or array comes back as JSON, and `null`/`undefined` becomes an empty string.

---

## Foreground and background

**Foreground** (`mode: foreground`, the default)

- Waits until it finishes, then returns the result.
- Inherits the calling turn's execution context, principal and cancellation signal. Which means **Ctrl+C can interrupt it**.
- `WorkflowJs`'s `max_agents` applies in this mode only.

**Background** (`mode: background`)

- Hands back a run ID immediately so the conversation can carry on.
- Does **not** inherit the calling turn's context, principal or cancellation signal. Ctrl+C does not stop it →
  use `/runs stop <runId>`.
- The run ID is derived deterministically from the request's content. **If the same request is already running it is
  not run twice — the call joins the existing run.**
  - `Workflow`: `run:workflow:<hash>`
  - `WorkflowJs`: `run:graaljs:<hash>`

```
Started background workflow run 'run:workflow:3f2a...' over 3 perspective(s).
Track it with the /runs command (or /runs status run:workflow:3f2a...).
```

**A background run's result text does not come back as the tool's return value.** It is for checking status with `/runs`.
If you need the result, use the foreground.

---

## The `/runs` command

```
/runs                      # = /runs list
/runs list
/runs status <runId>
/runs stop <runId>
```

Example output:

```
Workflow runs (2):
  run:workflow:3f2a9c...         RUNNING   started 2026-07-27T04:12:33Z
  run:graaljs:81be04...          COMPLETED started 2026-07-27T04:02:11Z  ended 2026-07-27T04:07:48Z
```

The status values: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `KILLED`.

`stop` is **a cooperative cancel** and applies **only to a run alive on this node**:

```
Requested stop for run 'run:workflow:3f2a9c...'.
No live run 'run:workflow:xxxx' to stop on this node.
```

---

## What you see on screen

One line is printed each time a subagent starts:

```
[Subagent] workflow:perspective:technical: review this change technically ...
```

That line is not specific to workflows — it appears for the `Task` tool and for skill forks in exactly the same way.
`Workflow`'s subagent names use the following prefixes:

| Name | Role |
|------|------|
| `workflow:perspective:<label>` | Perspective analysis |
| `workflow:candidate:<label>` | Judge-panel candidate generation |
| `workflow:judge` | Judging |
| `workflow:skeptic` | Refutation |
| `workflow:synthesizer` | Synthesis |

`WorkflowJs`'s subagents appear in the form `graaljs:<agentType>`
(`graaljs:<hash>` where only a `systemPrompt` was given, with no `agentType`).

To see the tool calls themselves, keep `cli.showToolCalls: true`.

---

## Troubleshooting

| Message | Cause / fix |
|--------|-----------|
| `Background workflow runs are disabled. Enable them with 'cli.enableWorkflow: true' in your config.` | You used `/runs` with both flags off. Turn one on in the configuration |
| `Background mode is not available: no workflow runner is configured for this agent.` | The same cause. `mode: background` could not find a runner |
| `Invalid mode 'xxx'. Use 'foreground' (default) or 'background'.` | A typo in `mode` |
| `Invalid strategy 'xxx'. Use one of [perspectives, judge_panel, adversarial_verify].` | A typo in `strategy` |
| `prompt cannot be blank` / `script cannot be blank` | A required input is missing |
| `perspectives cannot be empty` | `perspectives` was given an empty string, or nothing but commas |
| `JS workflow failed: agent(...) requires a prompt string or a descriptor object` | `agent()` was called with no argument, or with `null` |
| `JS workflow failed: parallel(...) requires an array of descriptor objects` | `parallel` was passed something that is not an array |
| `JS workflow failed: pipeline stage N must be a function` | Something other than a function sits in a stage slot |
| `JS workflow failed: GraalJS statement limit exceeded ...` | The script loops forever, or is simply too heavy. Reduce the iteration count |
| `JS workflow failed: workflow promise did not settle synchronously ...` | You wrote genuinely asynchronous code (a timer, a wait on external I/O). It is unsupported |
| `Agent runtime ID not found in tool context` | Called from outside an agent execution context (this does not happen in normal REPL use) |
| The run finished but does not show up in `/runs list` | The run list is kept in memory only for the lifetime of the CLI process |

---

## Limits worth knowing

- **Both tools are an experimental opt-in.** They are not registered under the default configuration.
- The background run list and its statuses live in **the CLI process's memory**. Restart the CLI and they are gone.
- `/runs stop` only reaches a run alive on this node.
- The `WorkflowJs` sandbox values (the statement limit, the 30-minute timeout, `console` being disabled) cannot be
  adjusted from the configuration file. If you need to adjust them, embed the library and supply a `JsSandboxConfig` yourself.
- The `isolation: 'worktree'` descriptor needs a worktree factory to work. The CLI injects one when a file system is
  present; without it, that step is treated as an execution failure.
- A workflow is **non-interactive**. Asking the user something back mid-script is not possible.

---

## Related documents

- [Workflow usage guide (the library view)](workflow-usage-guide.en.md) — assembling and running it directly in Java
- [Built-in Agent/Skill guide](../skill/builtin-agent-skill-guide.en.md)
- [Subagent development guide](../subagent/subagent-development-guide.en.md) — defining a reusable subagent
- [aimon-core guide](../../overview/architecture.en.md) — a reference for the core abstractions
