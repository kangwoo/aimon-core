---
translated_from: docs/features/hook/hook-config-guide.md
source_commit: 4bb8ace0
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
7. [Template variables](#template-variables)
8. [Async Rewake (`asyncRewake`)](#async-rewake-asyncrewake)
9. [Examples](#examples)
10. [Troubleshooting](#troubleshooting)

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
- If bootstrap fails the CLI logs a WARN and carries on — hot reload is still attempted
  afterwards.

### Failure modes

| Situation                         | Behaviour                                                     |
|-----------------------------------|---------------------------------------------------------------|
| The new `hooks.json` fails to parse | No swap. The previous hooks stay. `OnConfigReload(failed)` fires |
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
> `preTool`-only, and placing it on another event makes the bootstrap skip the entry.

---

## Matcher syntax

`matcher` decides which tool calls a hook applies to. When it is empty or `"*"` it matches every
tool (`NameOnlyPredicate.ANY`).

| Pattern                               | Meaning                                                             |
|---------------------------------------|---------------------------------------------------------------------|
| `Bash`                                | The tool name is exactly `Bash`                                     |
| `Read\|Write\|Edit`                   | Any one of the three                                                |
| `mcp__.*`                             | A regular expression — every tool starting with `mcp__`             |
| `Bash(command=^git\\s+push)`          | Tool name plus an input-field match (`PredicateParser`)             |
| `Bash & input.command~^npm`           | Composition — name and input predicates joined with `&` / `\|`      |

- A pattern `PredicateParser` cannot interpret falls back to `name-only` and leaves a WARN log.
- Regular expressions follow Java `Pattern` syntax.

---

## Handler types

### `command`

Runs a shell command, handled by `ShellAction` + `ShellActionExecutor`. It is **the only handler
type usable on every event** (`http` / `mcp` are `preTool`/`postTool`-only and `deny` is
`preTool`-only — placing them elsewhere skips the entry with a WARN).

```jsonc
{
  "type": "command",
  "command": "jq -r '.tool_input.file_path'",
  "timeout": 5      // optional, in seconds (Claude Code parity). 30 seconds if omitted
  // "timeoutMs": 500  // alternative: a millisecond alias. If both are set, timeoutMs wins
}
```

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
| other | `WARN` log + fail-soft (proceeds normally). A broken script must not become a silent gatekeeper. |

A veto takes effect **only in the four events that have a decision channel**. Exit 2 on any
other event leaves a WARN log and proceeds (`AbstractDeclarativeShellHook#vetoResult`).

| Event                 | What exit 2 does                                                     |
|-----------------------|----------------------------------------------------------------------|
| `preTool`             | `block` — skips the tool call and hands stderr to the model as the tool result |
| `onStart`             | `block` — aborts the turn itself with `ExecutionBlockedByHookException` |
| `preCompact`          | `block` — skips AUTO compaction / reports the reason for MANUAL       |
| `permissionRequest`   | `deny` — denies before dispatch                                       |
| The other 9 events    | Ignored (WARN log, then proceeds normally)                            |

> The veto on `onStart` was added in this round of hardening. Before that, a declarative
> `onStart` hook exiting 2 had no effect whatsoever.

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
> `"timeout": 60` imported from Claude Code used to be 60 ms on the old binary). Hook timeouts
> are fail-soft, so the symptom is quiet — divide existing values by 1000 or rename the key to
> `timeoutMs`.

**`timeout` and the hook policy.** A declared budget is enforced by the executor, and when it is
**at least** the hook policy's timeout (30 seconds by default) the executor's outer net **widens**
to match (plus a 5-second grace). So a long-running handler such as `"timeout": 120` is not cut
off at 30 seconds. A value exactly equal to the policy timeout (for instance the default 30
seconds of a shell handler that omits `timeoutMs`) also gets the grace. Conversely, a declared
budget shorter than the policy is ignored — narrowing the net would only make it race the
handler's own deadline. A declared budget is **clamped to 10 minutes
(`MAX_DECLARED_BUDGET`)**, so a configuration mistake cannot hold a turn indefinitely (anything
larger is truncated to 10 minutes after a WARN log).

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
`{ "decision": "deny" | "allow", "reason": "...", "updatedInput": {...} }` it is mapped onto a
`HookResult` automatically. A response that does not follow the schema simply passes.

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

A response shaped like `{decision, reason, updatedInput}` is mapped onto a `HookResult`.

### `deny`

A `preTool`-only short circuit. Refuses immediately, with no transport involved.

```jsonc
{
  "type": "deny",
  "reason": "rm -rf in production is blocked by policy."
}
```

- Placing it on any event other than `preTool` makes the bootstrap skip the handler with a WARN
  log.
- To refuse on another event, use exit 2 from a `command` handler. But the events where exit 2
  actually leads to a decision are **only four — `preTool` / `onStart` / `preCompact` (block)
  and `permissionRequest` (deny)**. On `postTool`, `onStop`, `onSessionStart`, `onSessionEnd`,
  `subagentStart`, `subagentStop`, `postCompact`, `permissionDenied` and `onConfigReload`,
  exit 2 leaves a WARN log and is ignored — those nine events have no decision channel to carry
  a refusal at all.
- `reason` cannot be empty (a validation failure skips the entry).

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
            "timeout": 2
          }
        ]
      }
    ]
  }
}
```

### 2. Blocking a dangerous command outright

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(command=^rm\\s+-rf\\s+/)",
        "hooks": [
          { "type": "deny", "reason": "Dangerous rm -rf commands are blocked." }
        ]
      }
    ]
  }
}
```

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

### 5. Combining the 4-tier layers (USER + PROJECT + LOCAL)

`~/.aimon/hooks.json` (USER, broad audit):
```json
{ "hooks": { "PostToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "logger -t aimon-user \"$AIMON_TOOL_NAME\"" }] }] } }
```

`<project>/.aimon/hooks.json` (PROJECT, team policy):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "Bash(command=^git\\s+push.*--force)", "hooks": [{ "type": "deny", "reason": "force push is not allowed" }] }] } }
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
        "matcher": "Bash(command=^kubectl\\s+apply.*-prod)",
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

`onSessionStart` / `onSessionEnd` / `onConfigReload` fire outside a skill invocation (in the
session and application lifecycles), so frontmatter rejects them — declare them in `hooks.json`.

The hooks above apply only while `my-skill` is active and unregister automatically when it is
deactivated.

---

## Troubleshooting

| Symptom                                                                  | Cause / remedy                                                               |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------|
| Editing `hooks.json` has no effect                                        | Check which of the four layers it lives in. SKILL applies only while the skill is active. Environments other than the CLI (web) do not support hot reload — restart. |
| Nothing happens within 2 seconds of an edit                               | Check that mtime was updated (`stat`). On a filesystem with second resolution, saving twice within the same second can make the second save invisible. |
| `OnConfigReload` fires with `failed=true`                                | Read the parser error in `failureReason` and check the JSON syntax and required fields. The live registry keeps its previous state. |
| `WARN hooks: matcher '...' could not be parsed`                          | A `PredicateParser` syntax error. It is running with the name-only fallback.  |
| `WARN hooks: invalid handler in PROJECT on event 'preTool': ...`         | A required field is missing (`command`/`url`/`server+tool`/`reason`). Only that entry is skipped. |
| `WARN hooks: 'deny' is not valid on postTool ...`                        | `deny` is `preTool`-only. The handler is ignored on other events.             |
| `WARN hooks: '...' event is not supported by AIMON in this phase`        | Only `Notification` / `UserPromptSubmit` / `stop_hook_active`. Everything else is supported. |
| `WARN hooks: unknown event '...'`                                        | A typo. Use a name from the [supported-events table](#supported-events-and-their-mapping) (case-insensitive). |
| `WARN hooks: only 'command' actions are valid on ...`                    | Events other than `preTool`/`postTool` accept shell handlers only.            |
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
