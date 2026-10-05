---
translated_from: docs/features/hook/hook-config-guide.md
source_commit: ca68a22
---

# Hook Configuration Guide (`hooks.json`)

> The Claude Code-compatible `hooks.json` schema, with usage examples.

This document covers how to write AIMON's declarative hook configuration. The configuration
uses the same shape as the [Claude Code `hooks.json`](https://docs.claude.com/en/docs/claude-code/hooks)
format, so an existing Claude Code configuration file can be carried over as is.

---

## Table of contents

1. [Configuration locations and the 4-tier layering](#configuration-locations-and-the-4-tier-layering)
2. [Hot reload](#hot-reload)
3. [Top-level structure](#top-level-structure)
4. [Supported events and their mapping](#supported-events-and-their-mapping)
5. [Matcher syntax](#matcher-syntax)
6. [Handler types](#handler-types)
   - [`command`](#command)
   - [`http`](#http)
   - [`mcp`](#mcp)
   - [`deny`](#deny)
7. [What a guard blocks](#what-a-guard-blocks)
8. [Template variables](#template-variables)
9. [Async Rewake (`asyncRewake`)](#async-rewake-asyncrewake)
10. [Examples](#examples)
11. [Troubleshooting](#troubleshooting)

---

## Configuration locations and the 4-tier layering

AIMON reads hook configuration from the following four sources and merges them cumulatively,
from lower precedence to higher (dispatch runs in the same low → high order).

| Source     | Path                                | Precedence | Notes                                               |
|------------|-------------------------------------|----------|-----------------------------------------------------|
| `USER`     | `~/.aimon/hooks.json`               | 10       | User-global configuration                           |
| `PROJECT`  | `<project>/.aimon/hooks.json`       | 20       | Shared across the project (committed)               |
| `LOCAL`    | `<project>/.aimon/hooks.local.json` | 30       | Personal override (`.gitignore` recommended)        |
| `SKILL`    | The `hooks:` block in a skill's frontmatter | 0 | Active only for the skill's scope, kept separate (never merged with USER/PROJECT/LOCAL) |

- A missing file is **silently ignored**, leaving only a DEBUG log.
- When several entries target the same event the merge is **additive** only — nothing is
  overwritten.
- The dispatch order is `USER → PROJECT → LOCAL`, so the narrower layer runs last.

---

## Hot reload

> Editing `hooks.json` takes effect without restarting the CLI.

The CLI bootstrap (`AgentSetupFactory`) wires `HookConfigWatcher` and `HookRegistryReloader`
at application scope and watches these three files for changes:

- `~/.aimon/hooks.json` (USER)
- `<project>/.aimon/hooks.json` (PROJECT)
- `<project>/.aimon/hooks.local.json` (LOCAL)

> The `hooks:` block in SKILL frontmatter is **not** hot-reloaded — it follows the skill's own
> activation/deactivation cycle.

### How it works

1. **Polling** — `HookConfigWatcher` checks mtime once per second (avoiding macOS WatchService
   latency).
2. **Debounce** — a 2-second window collapses a burst of edits into a single reload.
3. **Transactional swap** — `HookRegistryReloader` materialises the new layered config and
   replaces only the *managed* hooks of the live `DefaultHookRegistry`, LIFO. Hooks registered
   programmatically (from code) are untouched.
4. **Event firing** — immediately after the swap an `OnConfigReload` event fires, delivering the
   outcome (`successful` / `failureReason` / `reloadCounter` / `configSource`) to
   `OnConfigReloadHook` subscribers.

### SLA and guarantees

| Item                                          | Value                           |
|-----------------------------------------------|---------------------------------|
| Edit → `OnConfigReload` firing                | ≤ 2 s (verified by an E2E test) |
| Polling interval                              | 1 s (default)                   |
| Debounce window                               | 2 s (default)                   |
| Re-entrancy guard                             | monotonic counter, max depth 1  |
| On partial failure                            | automatic rollback to the previous registry state |

### How this differs from bootstrap

- **bootstrap**: runs once at CLI start. The `OnConfigReload` event does **not** fire — by
  contract this is an initial load, not a reload.
- **reload**: triggered by a file edit. The `OnConfigReload` event fires.
- If bootstrap fails, **startup stops.** When a file in any of the three layers does not parse or cannot be read,
  `HookHotReloadBootstrap.start()` throws `HookConfigParseException`, and the CLI prints
  `Configuration error: hooks config /…/.aimon/hooks.json (PROJECT layer) is invalid: … line: 3, column: 5 …` and
  exits (the REPL does not start). No hook is registered and no watcher is started. A file that is **missing** is not
  a failure — startup proceeds with that layer absent. There is no setting that starts with a broken file: fix the
  file or remove it. A host that calls `start()` itself and still wants to come up has to catch that exception
  explicitly in code.
- **What is and is not a failure.** An empty file (zero bytes, whitespace only, or `null`) is read as a layer with no
  hooks, and startup proceeds. When a component of the path is a regular file rather than a directory (say `~/.aimon`
  is a file), no config file can be there, so the layer is treated as absent and startup proceeds with a WARN naming
  that file. When whether the file exists cannot be determined — for instance the home or `.aimon` directory cannot
  be searched — startup stops with `… could not be read: cannot determine whether the file exists
  (java.nio.file.AccessDeniedException: …)`. A layer that cannot be judged is never read as "no hooks". A reload
  applies the same rules: on failure the previous configuration stays.

### Failure modes

| Situation                         | Behaviour                                                     |
|-----------------------------------|---------------------------------------------------------------|
| `hooks.json` fails to parse or cannot be read **at startup**, or has **an entry that cannot be applied** under a guard event | **Startup stops.** The exception message carries the file path, the layer and the cause (for an inapplicable entry, the event, the entry number and the handler number as well). A sound file in another layer does not help — starting without one layer would run with that layer's guards off |
| The new `hooks.json` fails to parse or cannot be read, or has an entry that cannot be applied under a guard event (reload) | No swap. **The previous configuration stays in force** (its hooks and guards unchanged). `OnConfigReload(failed)` fires, with the file path and layer in `failureReason` |
| Some hook fails to register mid-swap | LIFO undo removes the new hooks and re-registers the previous ones in their original order |
| A listener throws                 | Logged only; the watcher keeps running (poison-pill protection) |
| The watcher itself fails to start | The CLI carries on without hot reload (WARN log)              |

### A programmatic subscription example

`HookRegistry` has no per-event `register*` methods — there is a single generic
`register(HookEventType<H>, H)` that takes a typed token. `OnConfigReloadHook` is a
`@FunctionalInterface`, so it can be registered directly as a lambda.

```java
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookResult;

hookRegistry.register(HookEventType.ON_CONFIG_RELOAD, ctx -> {
    if (ctx.isSuccessful()) {
        log.info("hooks.json reloaded ({}): {}", ctx.getReloadCounter(), ctx.getConfigSource());
    } else {
        // getFailureReason() returns a String (not an Optional; empty string on success).
        log.warn("hooks.json reload failed: {}", ctx.getFailureReason());
    }
    return HookResult.allow();
});
```

> ⚠️ `ON_CONFIG_RELOAD` is an advisory chain. The feedback on the returned `HookResult` goes
> nowhere and is discarded, so use this hook for side effects only — logging, notifications,
> cache invalidation.

> ℹ️ The CLI turns hot reload on automatically during bootstrap. Other bootstraps (web, for
> instance) can adopt the same behaviour with a single
> `at.aimon.core.config.hook.HookHotReloadBootstrap.builder()...start()` call — see the
> canonical example in the `AgentSessionOpener` javadoc.

---

## Top-level structure

```jsonc
{
  "hooks": {
    "<EventName>": [
      {
        "matcher": "<tool matcher>",  // optional, defaults to "*"
        "hooks": [
          { "type": "<handler>", ... }, // one or more
          ...
        ]
      },
      ...
    ],
    ...
  }
}
```

- A file whose `hooks` field is empty or absent is treated as an empty configuration.
- **Unknown top-level or entry fields** are ignored with a `WARN` log — a newer configuration
  file does not break an older binary. (`asyncRewake` has been a recognised field since
  Phase 4A — see [Async Rewake](#async-rewake-asyncrewake).)

---

## Supported events and their mapping

`HookEventName` maps Claude Code names to AIMON's internal names in both directions, as follows.

| Claude Code (`hooks.json`) | AIMON internal event   | Description                             | Blocking? |
|----------------------------|-----------------------|-----------------------------------------|-----------|
| `PreToolUse`               | `preTool`             | Immediately before a tool call (allow/deny/input transformation) | ✅ |
| `PostToolUse`              | `postTool`            | Immediately after a tool call (audit/metrics) | ❌  |
| `Stop`                     | `onStop`              | At turn end                             | ❌        |
| `PreCompact`               | `preCompact`          | Immediately before compaction           | ✅        |
| `SessionStart`             | `onSessionStart`      | Conversation start                      | ❌        |
| `SessionEnd`               | `onSessionEnd`        | Conversation end                        | ❌        |
| `SubagentStop`             | `subagentStop`        | Subagent end                            | ❌        |
| (none)                     | `onStart`             | AIMON-only: turn start                  | ✅        |
| (none)                     | `postCompact`         | AIMON-only: immediately after compaction | ❌       |
| (none)                     | `subagentStart`       | AIMON-only: subagent start              | ❌        |
| (none)                     | `permissionRequest`   | AIMON-only: the permission decision     | ✅        |
| (none)                     | `permissionDenied`    | AIMON-only: post-processing after a denial | ❌     |
| (none)                     | `onConfigReload`      | AIMON-only: immediately after a configuration hot reload | ❌ |

For AIMON-specific events, just write the AIMON internal name directly in `hooks.json` (case is
ignored — both `"onStart"` and `"onstart"` work).

The following events are **not currently supported**; an entry for one is ignored with a WARN
log: `Notification`, `UserPromptSubmit`, `stop_hook_active`.

**An event name AIMON does not know** is ignored with a WARN as well — it may be an event of a newer AIMON or of Claude
Code. But a name **within two letters** of a known one is read as a typo: the WARN names the closest event (`unknown
event 'postTol' … did you mean 'postTool'?`), and when that closest event is a **guard event** (`preTool`, `PreToolUse`,
`onStart`, `preCompact`, `permissionRequest`) it is not a WARN but a **startup failure** (`… is invalid: unknown event
'preTol' - did you mean 'preTool'? …`). `preTol` is not a future event, and skipping it starts the host without the guard
that was written.

> The "(none)" for `permissionRequest` / `subagentStart` is copied straight from
> `HookEventName`'s reverse mapping, but both events do exist in the upstream spec. The forward
> resolve works fine; only the reverse direction is empty — the details are in
> [`hooks-specification.md` §4](../../references/hooks-specification.md).

> ⚠️ **A refusal has no effect on an event whose Blocking? column says ❌.** The call sites in
> the framework that actually consume a hook's `block`/`deny` are just four — `preTool`,
> `permissionRequest`, `onStart` and `preCompact` — and a refusal from any other event is
> ignored with only a WARN log. Do not try to use an audit or notification hook as a gate.
>
> In a declarative hook, a refusal in those four events is expressed as **exit 2** from a
> `command` handler (see [exit codes](#command)). `type: "deny"` is narrower still — it is
> `preTool`-only, and on another event it is an entry that cannot be applied: a startup failure
> under a guard event, skipped elsewhere.

---

## Matcher syntax

`matcher` decides which tool calls a hook applies to. When it is empty or `"*"` it matches every
tool (`NameOnlyPredicate.ANY`).

The grammar is exactly what `PredicateParser` accepts: a **tool name**, **`Tool(glob)`**, and **`|`** (OR) joining them.

| Pattern                               | Meaning                                                             |
|---------------------------------------|---------------------------------------------------------------------|
| `Bash`                                | The tool name is exactly `Bash` (case-sensitive)                    |
| `Read\|Write\|Edit`                   | Any one of the three — `\|` is OR, and spaces around it are ignored |
| `mcp__*`                              | A name glob — every tool starting with `mcp__`. `*` is the only wildcard |
| `Bash(git push*)`                     | A sub-command glob for `Bash` — one of the pieces `command` splits into matches the **whole** glob |
| `Edit(*.env)`                         | A path glob — the path argument of `Edit` matches the **whole** glob |
| `Bash(rm -rf*)\|Write(*.env)`         | The terms above joined with `\|`. A `\|` **inside** parentheses is part of the pattern |

- **Globs.** `*` is zero or more arbitrary characters (it crosses `/` too) and **every other character is a literal** —
  `.`, `?`, `^`, `\s` and `**` have no special meaning. The comparison is anchored at both ends, so "starts with" is
  written `git push*` and "contains" is written `*--force*`.
- **`Bash(glob)`.** The `command` string is split at `&&`, `||`, `;`, `|` and newlines, and the contents of backticks,
  `$(…)` and double quotes are looked at as pieces of their own (the `git push` in `bash -c "git push"`). The matcher
  matches when any one piece matches the glob. It **compares text**; it does not interpret the shell — `git  push` (two
  spaces) and `sudo git push` are not caught by `git push*`. A guard that has to hold against evasion should not lean
  on the glob: have a `command` handler inspect `tool_input.command` from stdin itself.
- **The tools that take `Tool(glob)`** are `Bash` and the eight path tools (`Read`, `Edit`, `Write`, `MultiEdit`, `Glob`,
  `Grep`, `LS`, `NotebookEdit`), and no others. A path tool looks at the path string exactly as the model passed it
  (`file_path` for `Read`, `Edit`, `Write` and `MultiEdit`; `pattern` then `path` for `Glob`; `path` then `pattern` for
  `Grep`; `path` for `LS`; `notebook_path` for `NotebookEdit`). It may be absolute or relative, so start the glob with
  `*`, as in `*.env`, to match whatever directory precedes it. Parentheses on any other tool (`WebFetch(…)`,
  `mcp__x(…)`) do not parse.
- **What is not there.** Regular expressions, AND (`&`), naming an input field (`command=…`) and negation are not part
  of the grammar. It is also **not the grammar** of tool-permission patterns (`Bash(git:*)` and `Read(/tmp/**)` in
  `allowed-tools`) — in a matcher `:` and `**` are literals.
- **A matcher that does not parse** — unbalanced parentheses, an empty pattern (`Bash()`), an empty term (`Read|`), text
  after the closing parenthesis, a tool that takes no parentheses — is a **startup failure** on `preTool`
  ([What stops startup](#what-stops-startup)). On `postTool` it falls back to `name-only`, reading the whole string as
  a tool name, and leaves a WARN (no tool has that name, so the hook does not fire).
- **A matcher that parses but can match nothing is not caught.** A term without parentheses is a tool name in its
  entirety and whatever is inside parentheses is a glob in its entirety, so a matcher written in a grammar that is not
  listed above registers without an error and **never fires**: `mcp__.*` matches only names starting with `mcp__.`,
  `Bash & input.command~^npm` matches only a tool with literally that name, and `Bash(command=^git\s+push)` matches only
  a command that is literally that text. This guide once listed those three forms as grammar — a `deny` guard copied
  from it has never fired, and should be rewritten in one of the forms in the table.

---

## Handler types

### `command`

Runs a shell command, handled by `ShellAction` + `ShellActionExecutor`. It is **the only handler
type usable on every event** (`http` / `mcp` are `preTool`/`postTool`-only and `deny` is
`preTool`-only — elsewhere they are entries that cannot be applied: a startup failure under a guard
event, skip + WARN on the other events. See "What stops startup" below).

```jsonc
{
  "type": "command",
  "command": "jq -r '.tool_input.file_path'",
  "timeout": 5      // optional, in seconds (Claude Code parity). 30 seconds if omitted
  // "timeoutMs": 500  // alternative: a millisecond alias. If both are set, timeoutMs wins
  // "failOpen": true  // optional, default false. Let the event proceed when the command could not run (see "Exit codes")
}
```

**Where it runs.** A `hooks.json` command runs on the **host shell** — the file is operator configuration, and it is
the only place that can declare the events that fire outside any execution (`onSessionStart`, `onSessionEnd`,
`onConfigReload`) (`HostShellActionExecutor`). A `shell` action declared by a skill's frontmatter is different: it
runs in the **execution environment's shell** of the execution the hook fires in, which is why it cannot be declared
on an event outside an execution. There is also one environment variable only a skill's hook receives:
`AIMON_SKILL_DIR` (the path that skill's directory was staged to in the environment the hook runs in). A `hooks.json`
command has no skill directory, so the variable is **not set** there — unset, not an empty string, and the stdin
payload carries no `skill_dir` field either.

**How input is passed.** The command string is **not template-rendered** — it goes to the shell
verbatim, so writing `${tool_input.x}` in a command gives you an (empty) shell variable rather
than a placeholder. That is deliberate: untrusted tool input must never end up on a command
line. Context arrives through two channels instead (identical to Claude Code):

1. **A stdin JSON payload** — the scalar fields of the `AIMON_*` env, with the prefix stripped
   and lowercased (`AIMON_TOOL_NAME` → `tool_name`), plus the nested `tool_input` object on tool
   events.
2. **`AIMON_*` environment variables** — a flat view of the same values.

```bash
payload=$(cat)
tool=$(echo "$payload" | jq -r '.tool_name')
path=$(echo "$payload" | jq -r '.tool_input.file_path')
```

**Exit codes.**

| exit | Meaning                                                                           |
|------|-----------------------------------------------------------------------------------|
| `0`  | Proceed normally.                                                                 |
| `2`  | **veto** — stderr becomes the refusal reason (Claude Code parity). Truncated beyond 4000 characters. |
| `126` · `127` | The shell **could not start** the command (not executable · not found). On an event with a decision channel this **counts as a veto** — see "When the command could not run" below. On the other events it reads like "other". |
| other | `WARN` log + fail-soft (proceeds normally). A broken script must not become a silent gatekeeper. |
| none | The command produced no exit code (timeout, shell failure). On an event with a decision channel this **counts as a veto** — see "When the command could not run" below. |

A veto takes effect **only in the four events that have a decision channel**. Exit 2 on any
other event leaves a WARN log and proceeds (`AbstractDeclarativeShellHook#vetoResult`).

| Event                 | What exit 2 does                                                     |
|-----------------------|----------------------------------------------------------------------|
| `preTool`             | `block` — skips the tool call and hands stderr to the model as the tool result |
| `onStart`             | `block` — a main execution aborts the turn itself with `ExecutionBlockedByHookException`; a fork (skill fork, `Task`, workflow subagent) **does not start** and returns to its parent as a failed result carrying the reason. Neither fires `onStop` |
| `preCompact`          | `block` — skips AUTO compaction / reports the reason for MANUAL       |
| `permissionRequest`   | `deny` — denies before dispatch                                       |
| The other 9 events    | Ignored (WARN log, then proceeds normally)                            |

> The veto on `onStart` was added in this round of hardening. Before that, a declarative
> `onStart` hook exiting 2 had no effect whatsoever.

**An `onStart` in `hooks.json` applies to every fork as well.** `onStart` fires not only for the main turn but each time
a fork starts, and the "user message" it sees then is the goal that fork was given. If the hook exits 2, or its command
cannot be run, the fork ends without a single LLM call and its parent receives
`Execution blocked by OnStart hook [SUBAGENT/<name>]: <reason>` (`Task` reports `Status: FAILURE`, a skill reports
`Skill fork failed for '<skill>': …`, a background task settles as `FAILED`). The fork's completion reason is `BLOCKED`,
not `ERROR` — read it from the `Completion reason: BLOCKED` line of a `Task` result or a workflow step's
`completionReason`. A hook that was only meant to check user
input should branch on `AIMON_INVOKER_TYPE` in its script — `MAIN_AGENT` for the main turn, `SUBAGENT` for a fork. That
is the only way to exempt forks: `failOpen: true` lets a hook through **only when its command could not run**, and an
exit 2 still blocks. A subagent whose name has a code behavior (`SubagentBehavior`) registered is no exception — it does
not run the ReAct loop, but `onStart` fires before the behavior is invoked, and a block means the behavior never runs.

**When the command could not run (fail-closed).** If a `command` handler on one of the four events above **produces no
exit code** — a timeout, a shell failure — the hook returns that event's refusal (`preTool`, `onStart` and `preCompact`
block; `permissionRequest` denies). A guard that could not decide does not let the operation through. The reason names
the cause, in the form `Blocked: guard hook '<source>#<n>' (<event>) could not run its command — timed out: …`, and never
contains the command string. **Exit 126 and 127 are the same case** — the shell ended with "cannot execute" or "not
found", so the guard gave no answer (`… — command not found: exit code 127`; the shell's stderr quotes the command line,
so it is left out of the reason). A script that itself exits 126 or 127 cannot be told apart and is blocked the same
way — a guard script reports "allow, but something went wrong" with another code, such as 1. For a handler that **observes** rather than guards (audit logging, metrics), declare
`"failOpen": true`: it then leaves a WARN and proceeds. `failOpen` does not weaken an exit 2. The only value that opens
the guard is the JSON boolean `true`: a value that is not a boolean, such as `"true"`, `1` or `null`, is not coerced and
**is read as `false`** — the handler is still registered with its guard closed, and a WARN names the file, the event and
the handler (the key takes a guard off, so it is not read loosely, and a typo in it does not cost the file its other
guards either). On the other 9 events a command that could not run still leaves a WARN and proceeds, as before.

**The unit of `timeout` (breaking change).** `timeout` is in **seconds**, matching Claude Code.
When you need milliseconds, use AIMON's own alias `timeoutMs`. If both are present the more
precise `timeoutMs` wins. Both values must be positive; zero or negative is rejected at parse
time.

| Spelling             | Meaning                               |
|----------------------|---------------------------------------|
| `"timeout": 60`      | 60 seconds (60000 ms)                 |
| `"timeoutMs": 1500`  | 1500 ms — when you need under a second |

> ⚠️ **Migration.** `timeout` used to be read as milliseconds. Leaving an old configuration
> untouched means `"timeout": 5000` is now read as **5000 seconds**, not 5 (and conversely a
> `"timeout": 60` imported from Claude Code used to be 60 ms on the old binary). A value read too
> short now **blocks** by timeout on the events with a decision channel and is cut off quietly on
> the others — divide existing values by 1000 or rename the key to `timeoutMs`.

**`timeout` and the hook policy.** A declared budget is enforced by the executor, and when it is
**at least** the hook policy's timeout (30 seconds by default) the executor's outer net **widens**
to match (plus a 5-second grace). So a long-running handler such as `"timeout": 120` is not cut
off at 30 seconds. A value exactly equal to the policy timeout (for instance the default 30
seconds of a shell handler that omits `timeoutMs`) also gets the grace. Conversely, a declared
budget shorter than the policy is ignored — narrowing the net would only make it race the
handler's own deadline. A declared budget is **clamped to 10 minutes
(`MAX_DECLARED_BUDGET`)**, so a configuration mistake cannot hold a turn indefinitely (anything
larger is truncated to 10 minutes after a WARN log).

**When the outer net fires first.** If a handler does not keep its own timeout — a remote shell that does not implement
cancellation, stuck I/O, an MCP client that does not answer the stop signal — and the outer net cuts the hook off, a declarative hook on
one of the four events with a decision channel **blocks** (`Hook timed out after …ms (limit=…ms)`). The event policy's
`timeoutBehavior` defaults to `FAIL_OPEN`, but a declarative guard declares `FAIL_CLOSED` per hook and the executor
follows that declaration over the policy (`ExecutionHook#getTimeoutBehavior()`). A handler with `"failOpen": true`, and
every handler on the other 9 events, declares nothing and so follows the event policy (`FAIL_OPEN` by default — it
proceeds). Hooks registered in code behave as they did.

### `http`

Calls an HTTP webhook. `HttpAction` + `HttpActionExecutor`.

```jsonc
{
  "type": "http",
  "url": "https://example.test/hooks/pre-tool",
  "method": "POST",                                    // optional, defaults to POST
  "headers": {                                         // optional
    "X-Auth-Token": "${env.AIMON_HOOK_TOKEN}"
  },
  "body": "{\"tool\":\"${context.tool_name}\",\"path\":\"${tool_input.file_path}\"}",
  "allowedEnvVars": ["AIMON_HOOK_TOKEN"],             // the whitelist referenceable via ${env.X}
  "timeout": 3                                          // seconds (use "timeoutMs": 3000 for milliseconds)
}
```

If the response body follows the JSON schema
`{ "decision": "allow" | "deny" | "defer", "reason": "...", "feedback": "...", "updatedInput": {...} }`, it is mapped to a
`HookResult` automatically. On `preTool` what matters is **whether a verdict came back** — a guard that got none blocks
([What a guard blocks](#what-a-guard-blocks)). `"failOpen": true` applies to this handler exactly as it does to `command`.

| Response | How it is read |
|----------|----------------|
| 2xx, a JSON object that refuses — `decision` is `deny` or `block`, `hookSpecificOutput.permissionDecision` is `deny`, `continue` is `false` | verdict: refuse (the reason is `reason`, `permissionDecisionReason`, `stopReason` respectively) |
| 2xx, a JSON object, `hookSpecificOutput.permissionDecision` is `ask` | verdict: ask — the `AskPromptHandler` answers (`permissionDecisionReason` is the question) |
| 2xx, a JSON object, `decision` is `allow` or `defer`, `permissionDecision` is `allow`, or no decision at all | verdict: allow (`feedback` and `updatedInput` are applied as given) |
| 2xx with an empty body, text that is not declared as JSON (`ok`), or JSON that is not an object | verdict: allow — a webhook that carries no decision |
| 2xx that cannot be read — a body whose `Content-Type` is JSON but does not parse, a `decision` (`"approve"`, say) or `permissionDecision` (`"defer"`, say) that is not a string or not a known value, a `continue` that is not a boolean, an `updatedInput` that is not an object | **no verdict** (`response could not be read`) |
| non-2xx (whatever the body says) | **no verdict** (`call failed: HTTP <status>`) — a refusal is spelled `decision: deny` in a 2xx |
| connection failure, transport error | **no verdict** (`call failed: <exception type>`) |
| `timeout` exceeded | **no verdict** (`timed out`) |
| executor not wired | **no verdict** (`action executor not wired`) |

A policy endpoint written for Claude Code can be used as it is — besides `decision`, the verdict is read from Claude
Code's two spellings (`hookSpecificOutput.permissionDecision`, `continue`). When one document carries several and they
disagree, **the strictest is the verdict**: refuse > unreadable > ask > allow. `decision: allow` next to
`permissionDecision: deny` is a refusal. The refusal reason shown to the model comes only from the reason field of the
spelling that refused — `feedback`, `systemMessage` and `additionalContext` never become the reason. An `ask` is turned
into allow or refuse by the hook execution manager's `AskPromptHandler`: unless the host supplied one it **refuses**,
and the environment variable `AIMON_HOOK_ASK_DEFAULT=allow` changes that default to allow. An `ask` is a verdict, so
`failOpen` does not open it. `permissionDecision: defer`, unlike `decision: defer`, is no verdict — Claude Code's `defer`
means "hold this tool call until the calling application resumes it", there is no means to do that, and so it is not
read as running the tool.

On `postTool` a missing verdict still leaves a WARN and proceeds, as before.

> ℹ️ **`aimon-cli` runs `http` and `mcp` handlers** — those in `hooks.json` and those in skill frontmatter. `http` always;
> `mcp` when the CLI configuration (`mcp.servers`) has at least one server. In a CLI with no server at all an `mcp`
> handler is an entry that cannot run — on `preTool` in `hooks.json` it **keeps the CLI from starting** (`… type=mcp cannot
> run: no McpActionExecutor is wired in this assembly`; with `failOpen: true` it is registered and every call leaves a
> WARN and proceeds), and a skill that declares such an action does not load.
> `aimon-bootstrap` and the Spring Boot starter **wire neither**: an embedding host wires them itself, with
> `HookHotReloadBootstrap.builder().httpExecutor(…).mcpExecutor(…)` and the `skillParser` of `AimonStackSpec`.

**What an `http` handler sends and receives.** This handler is where a configuration file makes the host process send a request out.

- **The URL is used as written** (it is not templated). Both `https://` and `http://` are accepted — use `https://` when
  a header carries a token.
- **Redirects are not followed.** A 3xx is no verdict, like any other non-2xx (`call failed: HTTP 307`). Following it
  would send the headers filled from `${env.X}` to a host the configuration never named, and make that host's answer the
  verdict. Write the final address in `url`.
- **At most 1 MiB of response body is read.** A larger one is not truncated and guessed at: it is no verdict (`response
  could not be read: response larger than 1048576 bytes`).
- **`timeout` is measured over the whole exchange — the response body included.** An endpoint that sends its headers
  and stalls, and one that drips its body a byte at a time under 1 MiB, are both cut off at the deadline (the request in
  flight is cancelled and its connection closed) and the result is `timed out`. The connect timeout is fixed at 5 seconds.
- **The proxy is the JVM default** — system properties such as `https.proxyHost` apply; the `HTTPS_PROXY` environment
  variable is not read.
- **`${env.X}` reads the host process's environment.** The names it may read are the `allowedEnvVars` the handler lists
  **for itself**. The list keeps a template from reading a variable it did not name; it does not limit what the author
  of the configuration can send out. An `http` action in skill frontmatter reads it the same way — the same authority a
  skill's `shell` action already has in the CLI, where it runs with that environment and network. Skill approval is per
  skill **name** and does not show what its hooks do, so do not install a skill you do not trust.
- **Template values are not escaped.** With `"${tool_input.command}"` in a JSON body, a command containing a quote goes
  out as broken JSON (if the server answers 4xx that is no verdict — an audit hook with `failOpen` then lets the call
  through **unrecorded**), and the model can insert fields into the body. To hand a guard a value the model chooses, the
  `args` of an `mcp` handler (each value is substituted on its own, so the structure cannot break) or a `command`
  handler reading JSON from stdin is the safe route.

> 🔒 Environment-variable references are substituted **only for keys on the whitelist
> (`allowedEnvVars`)**. A variable that is not on it becomes an empty string, with a WARN log.

### `mcp`

Calls a tool on an MCP server. `McpToolAction` + `McpActionExecutor`.

```jsonc
{
  "type": "mcp",
  "server": "policy-server",
  "tool": "evaluate_pre_tool",
  "args": {
    "tool_name": "${context.tool_name}",
    "command": "${tool_input.command}"
  },
  "timeout": 4
}
```

If the response has the shape `{decision, reason, feedback, updatedInput}`, it is mapped to a `HookResult`. The line
between a verdict and "no verdict" is the same as for `http`: a result that came back without an error is a verdict
(empty content, plain text and JSON that is not an object allow; a JSON object is read as a decision document), while a
server that is not registered or not connected, a transport error, an `isError` result (`call failed`), a decision
document that cannot be read (`response could not be read`) and an executor that is not wired are **no verdict** — on
`preTool` that blocks, and `"failOpen": true` lets it through.
The `timeout` of an `mcp` handler (10 seconds by default) **ends the call.** When it passes, the request in flight is
stopped and the result is **no verdict** (`timed out: no response within <n>ms`) — on `preTool` that blocks
(`"failOpen": true` lets it through), on `postTool` it leaves a WARN and proceeds. The `requestTimeout` configured per
MCP server (30 seconds by default) still applies underneath, so **the shorter of the two ends the call**: a long handler
`timeout` does not make a request wait past the server's `requestTimeout`, and when that one ends it first the reason
is `call failed`. Nothing goes on waiting for a request that was cut off — a stdio server has already received it, so
the server may keep working, and an answer that arrives late is discarded (no `notifications/cancelled` is sent). A call
that was still waiting for another request to the same server to finish is not cut off by this timeout; the hook
executor's outer net cuts it off, and a guard blocks then too.

### `deny`

A `preTool`-only short circuit. Refuses immediately, with no transport involved.

```jsonc
{
  "type": "deny",
  "reason": "rm -rf in production is blocked by policy."
}
```

- On any event other than `preTool` it is an entry that cannot be applied — a startup failure under a guard event
  (`onStart`, `preCompact`, `permissionRequest`), and elsewhere the handler is skipped with a WARN log.
- To refuse on another event, use exit 2 from a `command` handler. But the events where exit 2
  actually leads to a decision are **only four — `preTool` / `onStart` / `preCompact` (block)
  and `permissionRequest` (deny)**. On `postTool`, `onStop`, `onSessionStart`, `onSessionEnd`,
  `subagentStart`, `subagentStop`, `postCompact`, `permissionDenied` and `onConfigReload`,
  exit 2 leaves a WARN log and is ignored — those nine events have no decision channel to carry
  a refusal at all.
- `reason` cannot be empty (it is a guard on `preTool`, so a validation failure is a startup failure).

---

## What a guard blocks

One table for **what a declarative hook reads as a refusal** on the four events with a decision channel (`preTool`,
`onStart`, `preCompact`, `permissionRequest`), and **what `failOpen: true` changes**. There is one rule — **a guard that
could not decide blocks.** `failOpen: true` declares that the handler observes rather than guards; **it never weakens a
verdict.** On the other 9 events no row blocks (a WARN, then the event proceeds). The table applies to hooks in
`hooks.json` and in skill frontmatter alike.

| What happened to the handler | How it is read | Default | `failOpen: true` |
|------------------------------|----------------|---------|------------------|
| `command` exits 0 | verdict: allow | proceeds | proceeds |
| `command` exits 2 | verdict: refuse (stderr is the reason) | **blocks** | **blocks** |
| `command` exits 126 or 127 (not executable · not found) | could not run | **blocks** | proceeds (WARN) |
| `command` exits with any other code (1, 3, 130, …) | script malfunction | proceeds (WARN) | proceeds (WARN) |
| `command` produces no exit code — a timeout, a shell failure, no execution environment or an unavailable one, a skill directory that could not be staged, an executor without shell support, an executor that throws | could not run | **blocks** | proceeds (WARN) |
| `deny` handler | verdict: refuse | **blocks** | **blocks** — `failOpen` is not read on a `deny` (WARN). In the three "follows the event policy" rows below, a `deny` handler blocks as the default column says |
| `http` or `mcp` answers with a refusal (`decision: deny` or `block`, `permissionDecision: deny`, `continue: false`) | verdict: refuse (the server's reason field is the reason) | **blocks** | **blocks** |
| `http` or `mcp` answers `permissionDecision: ask` | verdict: ask | as the `AskPromptHandler` answers — by default **blocks** | the same — a verdict, so `failOpen` does not apply |
| `http` or `mcp` gives any other readable answer (`allow`, `defer`, no decision, an empty body, plain text) | verdict: allow | proceeds | proceeds |
| `http` or `mcp` gets no verdict — a connection failure, a timeout, a non-2xx status, an MCP server that is not registered or not connected or answers `isError`, an answer that cannot be read, an executor that is not wired, an executor that throws | no verdict | **blocks** | proceeds (WARN) |
| the handler runs past its own timeout and is cut off by the hook executor's outer net (declared timeout + 5 seconds) | no verdict | **blocks** | follows the event policy — the default policy proceeds (WARN) |
| the hook executor's pool does not take the hook — it is saturated, or closed because the stack is shutting down | could not run | **blocks** (`Hook could not be run (the hook executor rejected it) …`) | follows the event policy — the default policy proceeds (WARN) |
| the hook ends with an exception before it calls its handler — an exception while evaluating the `matcher`, a `StackOverflowError`, and the like | no verdict | **blocks** (`Hook failed before it returned a verdict (<exception type>)`) | follows the event policy — the default policy proceeds (WARN) |
| the execution is interrupted and the `command` is stopped (or the hook fires in an execution that is already cancelled) | execution cancelled | **blocks** | **blocks** — an execution that is ending does not take another step |

"Blocks" is a block on `preTool`, `onStart` and `preCompact` and a deny on `permissionRequest`. The reason for a guard
that could not run has the form `Blocked: guard hook '<name>' (<event>) could not run its command — <cause>. A guard that
cannot decide blocks (fail-closed).` (for `http` and `mcp`: `could not get a verdict from its http call` / `… its mcp
call`), and it never carries the command string, the shell's stderr, a URL, a header, a response body, an exception
message or the name `failOpen` — the reader of that reason is the party the guard constrains. Only two causes carry a
message: a skill directory that could not be staged, and an execution environment that is unavailable (`execution
environment unavailable: …`). Both are word for word what a tool call (`Skill`; `Bash`, `Read` and the rest) already
returns to the model for the same failure. `http` and `mcp` handlers
can only be placed on `preTool` and `postTool`, so the one guard event those four rows apply to is `preTool`.

A hook the pool did not take, and a hook that ended with an exception outside its handler, block by **the same
declaration** as a hook cut off by the outer net — the `FAIL_CLOSED` a declarative guard declares per hook
(`ExecutionHook#getTimeoutBehavior()`). "Slow", "never started" and "died" are one event from the caller's side (the
guard said nothing), so a guard closed against only one of them would leave the others as the way to switch it off. The
pool is closed on purpose when the stack shuts down, but by then the turns that fire a guard have ended, and the events
a shutdown does fire, `onStop` and `onSessionEnd`, are not guard events and are not blocked. A tool call that arrives
anyway is blocked at once, without waiting. A hook registered in code still follows the event policy's `onException`,
as before, unless it declares `FAIL_CLOSED` itself.

### What stops startup

The table above is about a hook **that fired**. There is one stage before it — an entry that parses but **cannot be
applied**. Under a guard event such an entry is not skipped: it **stops startup** (on a reload, the previous
configuration stays). Skipping it would start the host without the guard that was written, so it fails through the same
channel and in the same words as a broken file: `hooks config <path> (<LAYER> layer) is invalid: <event> entry #<n>,
handler #<m>: <reason>`. The numbers count from 0 within that layer and that event. The command or URL is never in the
message.

| Entry that cannot be applied | Guard events (`preTool`, `onStart`, `preCompact`, `permissionRequest`) | Other events |
|------------------------------|------------------------------------------------------------------------|--------------|
| A missing required field or a bad value — a `command` with no `command`, a `deny` with no `reason`, a `url` that is not a URI, an unknown `method`, an `mcp` with no `server` or `tool` | **startup failure** | skip + WARN |
| A handler type the event does not accept — `deny` outside `preTool`, `http` or `mcp` outside `preTool` / `postTool` | **startup failure** | skip + WARN |
| An entry with no handlers at all | **startup failure** | skip + WARN |
| A `matcher` that does not parse | `preTool`: **startup failure** | `postTool`: name-only fallback + WARN |
| A handler that cannot run on this host — a `command` with an executor that has no shell support, an `http` or `mcp` whose executor is not wired | **startup failure** (a handler with `"failOpen": true` is not a guard and is handled as before — a `command` is skipped with a WARN, an `http` or `mcp` is registered) | a `command` is skipped with a WARN; an `http` or `mcp` is registered and leaves a WARN when called |
| An event name within two letters of a guard event (`preTol`) | **startup failure** | — |
| Any other unknown event name, or an unsupported event | — | skip + WARN (naming the closest event when there is one) |

Hooks in skill frontmatter do not need this table — that parser has been strict from the start, and every case above is
a load failure of that skill.

**Interrupts.** When the user interrupts an execution, a `command` that is running is stopped **through the execution's
cancellation signal** — so it does not run on to its own timeout even on a shell that does not answer a thread interrupt
(a remote shell). The reason is `Blocked: hook '<name>' (<event>) was stopped — execution cancelled. An interrupted
execution does not proceed.`, with or without `failOpen`. The events that carry the signal are `permissionRequest` and
`preTool` (always), `postTool` and `permissionDenied` (only while the execution has not been cancelled — an audit
command fired after the cancellation runs to its end), and a fork's `onStart`. Commands on a main turn's `onStart`, on
`onStop`, on the compaction and subagent events and on the events outside any execution (`onSessionStart`,
`onSessionEnd`, `onConfigReload`) get no signal and stop, as before, only if the shell answers a thread interrupt.

---

## Template variables

The values of `http.body`, `http.headers.*` and `mcp.args` have the following variables
substituted. **`command` is not a substitution target** — for shell handlers see the stdin
payload / `AIMON_*` env described in the [`command`](#command) section above.

There are exactly three kinds of placeholder, all of the form `${<prefix>.<name>}`.

| Prefix               | Meaning                                                                  |
|----------------------|--------------------------------------------------------------------------|
| `${tool_input.X}`    | The tool input's key `X`. Nesting uses dot notation (`${tool_input.payload.id}`). |
| `${env.X}`           | The environment variable `X`, if whitelisted in `allowedEnvVars`. Anything off the whitelist is always `""`. |
| `${context.X}`       | A firing-context attribute from the table below.                         |

The values of `X` available under `${context.X}`:

| Name                  | Meaning                                           |
|-----------------------|---------------------------------------------------|
| `event`               | `preTool` or `postTool`                           |
| `skill_name`          | The name of the source that registered the hook (in the form `project#0`) |
| `invoker_name`        | The invoker's name                                |
| `invoker_type`        | `MAIN_AGENT` / `SUBAGENT` / …                     |
| `tool_name`           | The tool name                                     |
| `iteration`           | The ReAct loop's iteration number                 |
| `tool_result_status`  | (`postTool` only) `success` or `error`            |

- A `${...}` whose prefix is not recognised is **left as it is**, so it can be used literally in
  a shell snippet. A placeholder must use the `<prefix>.<name>` dot notation, so a spelling like
  `${session_id}` is not substituted and is sent literally — there is no context key called
  `session_id`.
- A recognised prefix with no value is substituted with an empty string.
- Substituted values are **not escaped.** Quoting for the target format (a JSON string, say) is
  the author's responsibility.

---

## Async Rewake (`asyncRewake`)

> Instead of deciding immediately, a hook can ask the framework to **wake it again later**. A
> handler's `asyncRewake` block expresses that promise declaratively.

`asyncRewake` is an orthogonal field that can be attached **optionally** to any handler type
(`command` / `http` / `mcp` / `deny`). It is valid only on events whose context can be
reconstructed at re-firing time, though — `preTool`, `preCompact`, `onSessionStart`,
`onSessionEnd` and `onConfigReload`. On any other event the spec is ignored with a WARN (the
hook itself still registers normally). For example:

- Retry in 5 minutes, until an external approval system responds (`delay`)
- Check status on the hour, every hour (`cron`, Quartz environments only)
- Wake up when an external webhook arrives (`event`)

```jsonc
{
  "type": "http",
  "url": "https://approvals.internal/check",
  "asyncRewake": {
    "trigger": { "delay": "5m" },     // or cron / event — exactly one of the three
    "timeout": "1h",                  // optional, defaults to 1h
    "maxAttempts": 4,                 // optional, defaults to 3
    "payload": { "ticket": "T-123" }, // optional, an arbitrary string→string map
    "reason": "awaiting human approval" // required
  }
}
```

### Trigger kinds (exactly one)

| Trigger                                | Meaning                                                 |
|----------------------------------------|---------------------------------------------------------|
| `{ "delay": "<duration>" }`            | Fires once, any time after `now + delay`                |
| `{ "cron": "<expr>", "zone": "<tz>" }` | Fires repeatedly, until `timeout` (Quartz environments only) |
| `{ "event": { "type": "...", "key": "..." } }` | Fires when a matching external event (`type, key`) arrives |

#### Duration notation

`delay` / `timeout` accept both notations:

- **shorthand** — `30s`, `5m`, `1h`, `1h30m`, `1h2m3s` (case-insensitive; zero or negative is
  rejected)
- **ISO-8601** — `PT5M`, `PT1H30M`, `PT0.5S` (recognised automatically when it starts with
  `P`/`p`)

#### Cautions with the `cron` trigger

- The expression is a **five-field cron** — minute hour day month day-of-week, with Sunday as
  `0` (for instance `"0 * * * *"` — on the hour; `"*/30 * * * *"` — every 30 minutes). It is the
  same dialect as `ScheduledTask`, and the Quartz backend translates it internally to six fields.
- A seconds field, `?`, `L`, `W`, `#` and `@daily` are not accepted. When you need one of those
  there is no way to express it, so split the trigger instead. An expression that restricts
  day-of-month and day-of-week **at the same time** parses but is rejected at schedule time,
  because Quartz cannot express their union — split it into two hooks.
- `zone` is an IANA time zone id (`UTC`, `Asia/Seoul`, …). UTC when unspecified.

> **Migration (six fields → five).** Older `hooks.json` files took Quartz's six fields directly.
> An expression such as `"0 0 * * * ?"` is now rejected with a `HookConfigParseException`
> **at the moment the file is read**. Drop the leading seconds field and change the trailing `?`
> to `*` (`"0 0 * * * ?"` → `"0 * * * *"`); if you specified the day of week numerically,
> subtract one, since Quartz's Sunday `1` is `0` here. Rejecting at load time is a deliberate
> change — a malformed cron used to load silently and then blow up the first time the hook
> fired, in the middle of an agent turn.
- `cron` works only in an environment where the **`aimon-scheduling-quartz` module** is wired.
  The in-memory `DefaultRewakeService` rejects cron envelopes with an
  `UnsupportedOperationException`.

#### The `event` trigger

When an external event whose `event.type` + `event.key` match exactly arrives through
`RewakeService.resolve(...)`, the envelope fires immediately and the hook is called again with
the caller's payload merged into the envelope payload (the caller's wins). `key` is a literal
string — the `asyncRewake` block does not go through template rendering, so writing
`${tool_input.X}` there substitutes nothing.

### Common fields

| Field         | Type            | Default     | Notes                                            |
|---------------|-----------------|-------------|--------------------------------------------------|
| `trigger`     | object          | (required)  | Exactly one of `delay` / `cron` / `event`        |
| `timeout`     | duration string | `1h`        | A fire past this time is discarded with a WARN   |
| `maxAttempts` | integer ≥ 1     | `3`         | The cumulative fire count. The envelope cancels itself once exceeded |
| `payload`     | string→string   | `{}`        | Arbitrary data the hook receives when it wakes again |
| `reason`      | string          | (required, non-blank) | A human-readable reason surfaced in logs and observability |

### Lifecycle

1. **Schedule** — when a hook first runs and returns a spec, either through
   `HookResult.asyncRewake(spec)` or through the handler config's `asyncRewake` block, the
   framework registers the envelope with `RewakeService.schedule(envelope)`.
   The original turn proceeds immediately with `ALLOW` — a rewake does not block the turn.
2. **Fire** — once the trigger condition is met, `RewakeService` hands the envelope to the
   listener. The listener re-hydrates the original context from `AgentRuntimeRegistry` and calls
   only the hook that fired, on its own (sibling hooks are not called again).
3. **Re-fire / termination** — a declarative hook reattaches its own spec every time,
   regardless of whether this is a fire (`DeclarativeRewake.attach` cannot tell a first run from
   a re-fire — filtering here would remove the very first envelope too). The chain's ceiling is
   set per trigger kind in `DefaultRewakeFireListener#chainFollowUps`:
   - `delay` / `event` are one-shot, so each fire chains the next link, bounded by
     `maxAttempts`.
   - A `cron` envelope is registered as the scheduler's native cron trigger and **repeats by
     itself**, stopping when `timeout` / `maxAttempts` is reached, so follow-ups are **not
     chained**. Chaining would create another self-repeating lineage per fire, branching the
     live envelope count by 2× per fire (`~2^(maxAttempts-1)`).

   Immediately after a fire, one-shot envelopes are removed from the pending list.
4. **Hot-reload cancellation** — when an edit to `hooks.json` makes the originating hook
   disappear (from Java's point of view, when its `hookId` no longer exists in the new config),
   every pending envelope that hook registered is cancelled automatically right after the swap.

### Limitations and known constraints

- **In-memory envelopes are lost when the JVM restarts.** If you need persistence, wire the
  Quartz-backed `RewakeService` from `aimon-scheduling-quartz`.
- **Rewake chaining is allowed only up to `maxAttempts` (resolves design §6.4)** — when a hook
  invoked by a rewake returns yet another `RewakeSpec`, the listener schedules a follow-up
  envelope. But if `previous.attemptNumber + 1 > spec.maxAttempts` it is discarded with a WARN
  log (with the default `maxAttempts=3`, that is 1 initial fire + 2 chained = 3). To enable
  chaining the bootstrap must inject the service through
  `DefaultRewakeFireListener.bindRewakeService(...)`; without it, follow-ups are discarded with
  an INFO log.
- **Only PRE_TOOL is re-dispatched** — in the Phase 4A iteration the listener re-invokes the
  hook only for `PreToolUse` envelopes. Envelopes for other event types are scheduled, but
  discarded with a WARN log at firing time.
- **Class-keyed `hookId`** — every declarative hook registered through `hooks.json` shares the
  same Java class, so their default `hookId` is identical too. That means a reload which removes
  only some hooks of that class triggers no cancellation — the class has to disappear entirely.
- **best-effort delivery** — if the agent context is gone (when the `AgentRuntime` has been
  unregistered) or the context is a stub that does not implement `RewakeCapableRuntime`, the
  fire leaves a WARN log and is silently discarded. Note that this is not a reliable channel
  with guaranteed delivery.
- **Per-context quota (resolves design §6.3)** — installing a `RewakeQuotaManager` through
  `DefaultRewakeService.withQuotaManager(...)` limits the number of concurrently pending
  envelopes per `agentExecutionContextId`. `DefaultRewakeQuotaManager` defaults to a cap of 64
  (changeable through the constructor, with per-context overrides via
  `setCustomQuota(contextId, cap)`). Once the cap is exceeded, `schedule(...)` discards the
  envelope and leaves a WARN log — protecting the scheduler from saturation by a runaway hook or
  a configuration reload. The default is `RewakeQuotaManager.NOOP` (unlimited), so enforcement
  is opt-in.

> For the design background in detail, see
> [`docs/design/hook/async-rewake.md`](../../design/hook/async-rewake.md).

---

## Examples

### 1. Sending every Bash call to an audit server

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "http",
            "url": "https://audit.internal/aimon/pre-bash",
            "headers": { "X-Auth": "${env.AUDIT_TOKEN}" },
            "body": "{\"cmd\":\"${tool_input.command}\",\"invoker\":\"${context.invoker_name}\",\"iteration\":\"${context.iteration}\"}",
            "allowedEnvVars": ["AUDIT_TOKEN"],
            "timeout": 2,
            "failOpen": true
          }
        ]
      }
    ]
  }
}
```

An audit hook is not a guard, so it declares `"failOpen": true` — without it, `Bash` is blocked whenever the audit
server cannot be reached ([What a guard blocks](#what-a-guard-blocks)). `aimon-cli` runs this handler. The body template
does not escape values, so a command containing a quote goes out as broken JSON — see "What an `http` handler sends and
receives" under [`http`](#http).

### 2. Blocking a dangerous command outright

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(rm -rf /*)",
        "hooks": [
          { "type": "deny", "reason": "Dangerous rm -rf commands are blocked." }
        ]
      }
    ]
  }
}
```

The matcher looks at the **text** of each sub-command — `rm -rf /` and `cd /tmp && rm -rf /var/lib` are caught, while
`rm -fr /` and `sudo rm -rf /` are not (`Bash(*rm -rf /*)` catches the latter as well). A block that must not be
avoidable by spelling the command differently belongs in a `command` handler that inspects `tool_input.command`
itself — see [Matcher syntax](#matcher-syntax).

### 3. Collecting metrics only, on PostTool (fail-soft)

Commands are not template-rendered, so read the context from the `AIMON_*` environment variables
(writing `${tool_name}` gets interpreted by the shell as its own variable and comes out empty).

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "logger -t aimon \"tool=$AIMON_TOOL_NAME status=$AIMON_TOOL_RESULT_STATUS\"",
            "timeout": 1
          }
        ]
      }
    ]
  }
}
```

You may equally read the same values from the stdin JSON payload — the two channels are derived
from the same map, so they never drift:

```json
{
  "type": "command",
  "command": "jq -r '\"tool=\\(.tool_name) status=\\(.tool_result_status) path=\\(.tool_input.file_path)\"' | logger -t aimon"
}
```

### 4. Routing to an MCP policy server

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "mcp",
            "server": "policy-server",
            "tool": "evaluate_write",
            "args": {
              "path": "${tool_input.file_path}",
              "invoker": "${context.invoker_name}"
            }
          }
        ]
      }
    ]
  }
}
```

`policy-server` is the name of an MCP server the host connected to that agent — in `aimon-cli`, an `mcp.servers[].name`
in the configuration file. It is a guard, so `Edit` and `Write` are blocked when the server cannot be reached or gives
no answer within `timeout` (10 seconds by default). A server name that is not configured is not caught as an error; it
is no verdict on every call (`call failed: MCP server not registered`), so spell the name as configured. That server's
tools appear in the model's tool list too, like any other MCP tool.

### 5. Combining the 4-tier layers (USER + PROJECT + LOCAL)

`~/.aimon/hooks.json` (USER, broad audit):
```json
{ "hooks": { "PostToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "logger -t aimon-user \"$AIMON_TOOL_NAME\"" }] }] } }
```

`<project>/.aimon/hooks.json` (PROJECT, team policy):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "Bash(git push*--force*)", "hooks": [{ "type": "deny", "reason": "force push is not allowed" }] }] } }
```

`<project>/.aimon/hooks.local.json` (LOCAL, personal debugging):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "echo PRE \"$AIMON_TOOL_NAME\" >&2" }] }] } }
```

→ Dispatch order: `USER PostTool log` → `PROJECT PreTool deny` → `LOCAL PreTool echo`.

### 6. Waiting for external approval, then retrying automatically (`asyncRewake` + an `event` trigger)

Rather than blocking a risky production deployment command outright, wait for an external
approval webhook and then wake the hook again. If no approval arrives, it times out after an
hour.

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(kubectl apply*-prod*)",
        "hooks": [
          {
            "type": "mcp",
            "server": "approval-gateway",
            "tool": "request_human_approval",
            "args": {
              "command": "${tool_input.command}",
              "invoker": "${context.invoker_name}"
            },
            "asyncRewake": {
              "trigger": { "event": { "type": "approval", "key": "prod-kubectl-apply" } },
              "timeout": "1h",
              "maxAttempts": 1,
              "reason": "awaiting human approval for prod kubectl apply"
            }
          }
        ]
      }
    ]
  }
}
```

When the external approval system calls
`RewakeService.resolve("approval", "prod-kubectl-apply", { "decision": "approved" })`, the
envelope fires, the hook is invoked again, and it decides the final ALLOW/DENY.

> ⚠️ The `asyncRewake` block does not go through template rendering, so `event.key` must be a
> **literal string**. A `${...}` is not substituted and becomes the matching key verbatim.

### 7. A skill frontmatter `hooks:` block

> ⚠️ SKILL.md frontmatter uses **a different schema** from `hooks.json`. `SkillHookSetParser`
> accepts neither the Claude Code event aliases (`PreToolUse` and friends) nor a nested `hooks:`
> handler array inside an entry. Event keys are AIMON's internal names, and each entry carries a
> single `action:` mapping instead of a handler array.

```yaml
---
name: my-skill
description: ...
hooks:
  preTool:
    - matcher: "Read"
      action: { type: shell, command: "echo skill-pre-read >&2", timeoutMs: 5000 }
    - matcher: "Bash"
      action: { type: deny, reason: "This skill does not use Bash" }
  postTool:
    - matcher: "*"
      action: { type: shell, command: "echo skill-post >&2" }
  onStart:
    - action: { type: shell, command: "echo skill-started >&2" }
---
```

A summary of the frontmatter schema:

| Item            | Rule                                                                            |
|-----------------|---------------------------------------------------------------------------------|
| Event key       | `onStart` / `preTool` / `postTool` / `onStop` / `subagentStart` / `subagentStop` / `permissionRequest` / `permissionDenied` / `preCompact` / `postCompact` |
| `matcher`       | Allowed on `preTool` / `postTool` only (`"*"` when omitted). On any other event, parsing fails |
| `action.type`   | `shell` / `deny` / `http` / `mcp`. `deny` is `preTool`-only; `http` and `mcp` are `preTool`/`postTool`-only |
| Timeout field   | `action.timeoutMs` (**milliseconds**). Frontmatter has no seconds-based `timeout` alias |
| `failOpen`      | An entry-level key (beside `matcher` and `action`). YAML boolean only, default `false`. Lets the event proceed when the action gives no answer — a `shell` action with no exit code or exit 126/127, an `http` or `mcp` action with no verdict ([What a guard blocks](#what-a-guard-blocks)) |

`onSessionStart` / `onSessionEnd` / `onConfigReload` fire outside a skill invocation (in the
session and application lifecycles), so frontmatter rejects them — declare them in `hooks.json`.

The hooks above apply only to the agent `my-skill` **forks** (and to forks that agent starts), and end when the skill
returns its answer. They do not fire for another session of the same agent or for the caller of the skill, and they are
not registered with the runtime's hook registry. An inline skill has no fork, so its hooks do not fire. The full rules
are in [`aimon-skill-extensions.md`](../../references/aimon-skill-extensions.md).

---

## Troubleshooting

| Symptom                                                                  | Cause / remedy                                                               |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------|
| Editing `hooks.json` has no effect                                        | Check which of the four layers it lives in. SKILL applies only while the skill is active. Environments other than the CLI (web) do not support hot reload — restart. |
| Nothing happens within 2 seconds of an edit                               | Check that mtime was updated (`stat`). On a filesystem with second resolution, saving twice within the same second can make the second save invisible. |
| `OnConfigReload` fires with `failed=true`                                | Read the parser error in `failureReason` and check the JSON syntax and required fields. The live registry keeps its previous state. |
| Startup exits with `Configuration error: hooks config … (… layer) is invalid: …` / `… could not be read: …` | Fix the file at the position the message points to, or remove the file. Broken JSON, an unknown `type`, a `timeout` of zero or less, an unreadable file and a directory where the file should be all end up here. It never starts without its file hooks. |
| A fork ends with `Execution blocked by OnStart hook [SUBAGENT/…]`         | An `onStart` hook in `hooks.json` (or in skill frontmatter) blocked that fork. If the hook is meant for user input, make it exit 0 when `AIMON_INVOKER_TYPE` is `SUBAGENT`. |
| The CLI does not start: `Configuration error: hooks config … is invalid: <event> entry #n, handler #m: …` | An entry under a guard event cannot be applied. Fix or remove the handler the message points at. If the handler only observes and may be left out when it cannot run, declare `"failOpen": true`. The full table is in [What stops startup](#what-stops-startup). |
| `WARN hooks: matcher '...' could not be parsed`                          | A `PredicateParser` syntax error on `postTool`. It is running with the name-only fallback. (On `preTool` this is a startup failure.) |
| A `deny` or guard hook is registered and never fires                     | Its matcher parses but matches no call — a regular expression (`mcp__.*`, `\s+`), `&`, `command=…` and a permission pattern (`Bash(git:*)`) are not matcher grammar, and none of them raises an error. Rewrite it in one of the forms under [Matcher syntax](#matcher-syntax). |
| `WARN hooks: invalid handler in PROJECT on event 'postTool': ...`        | A required field is missing (`command`/`url`/`server+tool`/`reason`) on an event that is not a guard event. Only that handler is skipped. |
| `WARN hooks: 'deny' is not valid on postTool ...`                        | `deny` is `preTool`-only. The handler is ignored on other events.             |
| `WARN hooks: '...' event is not supported by AIMON in this phase`        | Only `Notification` / `UserPromptSubmit` / `stop_hook_active`. Everything else is supported. |
| `WARN hooks: unknown event '...'`                                        | A typo, or an event AIMON does not know. Use a name from the [supported-events table](#supported-events-and-their-mapping) (case-insensitive). When a known name is close, `did you mean '…'?` is appended; when that name is a guard event this is a startup failure, not a WARN. |
| `WARN hooks: only 'command' actions are valid on ...`                    | Events other than `preTool`/`postTool` accept shell handlers only. It is a WARN only on an event that is not a guard event. |
| `WARN hooks: 'command' on ... cannot run: the configured shell executor does not support shell actions` | `hooks.json` was applied with an executor that has no shell support (`NoOpShellActionExecutor`). A `command` handler that is not a guard (an event that is not a guard event, or `failOpen: true`) is not registered — wire a `HostShellActionExecutor`. For a `command` on a guard event this is a startup failure, not a WARN. |
| `WARN hooks config at ...: ... has a 'failOpen' that is not a JSON boolean (...); it is read as false` | `failOpen` was written as `"true"`, `1` or `null`. The handler is registered with `failOpen: false` (it blocks when its command cannot run). If it only observes, change the value to `true`. |
| A tool is refused with `Blocked: guard hook '...' could not run its command` | A `command` handler on an event with a decision channel produced no exit code (a timeout, for one), or the shell could not start the command (`command not found: exit code 127`, `command not executable: exit code 126` — check the script path and its execute permission). Fix the cause named in the reason, or declare `"failOpen": true` if the handler only observes. The full table is in [What a guard blocks](#what-a-guard-blocks). |
| A tool is refused with `Blocked: guard hook '...' could not get a verdict from its http call` (or `mcp call`) | An `http` or `mcp` handler on `preTool` got no verdict. Read the cause in the reason — `action executor not wired` (the host wired no executor — `aimon-cli` wires them, so this is an embedding host), `call failed: MCP server not registered` (`server` is not the name of a configured MCP server), `call failed: HTTP 307` (redirects are not followed), `call failed: HTTP 503` or `call failed: ConnectException` (the policy server), `timed out`, `response could not be read` (the `decision` or `permissionDecision` value is not a known one — the table under [http](#http)). Declare `"failOpen": true` if the handler only observes. |
| `WARN hooks: 'asyncRewake' is not supported on event '...'`              | Rewake-capable events are `preTool`/`preCompact`/`onSessionStart`/`onSessionEnd`/`onConfigReload`. The hook itself registers normally. |
| A shell hook exited 2 but nothing was blocked                             | That event has no decision channel. A veto is effective only on `preTool`/`onStart`/`preCompact` (block) and `permissionRequest` (deny). |
| `${tool_input.x}` / `${tool_name}` inside a command is empty              | Intended behaviour. Commands are not rendered — use the stdin JSON payload or the `AIMON_*` env (`$AIMON_TOOL_NAME` and so on). |
| A long `timeout` still gets cut off at 30 seconds                         | That was the pre-Phase 5 behaviour. Today, when a handler's declared budget is at least the hook policy timeout, the net widens with it (+5-second grace). |
| A handler times out 1000× faster or slower than expected                  | `timeout` is now in **seconds** (Claude Code parity). Use `timeoutMs` when you need milliseconds. Divide old configuration values by 1000. |
| `WARN Hook declared an execution budget of ... exceeding the maximum`     | The declared budget exceeded `MAX_DECLARED_BUDGET` (10 minutes) and was clamped. Lower the configured value. |
| The `hooks:` block in SKILL.md frontmatter fails to parse                 | Frontmatter is not the `hooks.json` schema. Event keys are AIMON internal names, and an entry carries a single `action:` mapping rather than a nested `hooks:` array. See [example 7](#7-a-skill-frontmatter-hooks-block). |
| `${env.X}` substitutes to an empty string                                | `X` is not on the `allowedEnvVars` whitelist — blocked by security policy.     |
| `WARN Hook returned N rewake spec(s) but no RewakeService is wired`      | `RewakeService` is in its `NOOP` state in the application bootstrap. To make it work, wire `DefaultRewakeService` or a Quartz-based implementation. |
| `Cron triggers require the Quartz-backed RewakeService impl`             | A cron trigger was handed to the in-memory `DefaultRewakeService`. Add the `aimon-scheduling-quartz` module as a dependency and wire `QuartzRewakeService`. |
| `asyncRewake.trigger.cron is not a valid five-field cron expression`     | You are using a Quartz six-field expression. Drop the seconds field and change `?` to `*` (`"0 0 * * * ?"` → `"0 * * * *"`). Subtract one from numeric days of week (Quartz's Sunday `1` → `0` here). |
| `... are both restricted, which means "either day" here but cannot be expressed in Quartz` | Day-of-month and day-of-week were restricted at once. A five-field cron means the **union** of the two, but Quartz has to blank one out with `?` and cannot state a union. Split the hook in two. |
| A rewake fired but the hook was not called                                | Either (1) the agent context vanished from the registry, (2) the context does not implement `RewakeCapableRuntime`, or (3) hot reload removed the originating hook. The exact reason is in the WARN log. |

---

## Related documents

- [Hook Development Guide](hook-development-guide.en.md) — writing hooks programmatically
- [Hook system upgrade design](../../design/hook/hook-system.md) — why this configuration model looks the way it does
- [Async Rewake Design](../../design/hook/async-rewake.md) — the Phase 4A design background
- [Claude Code hooks.json reference](https://docs.claude.com/en/docs/claude-code/hooks)
