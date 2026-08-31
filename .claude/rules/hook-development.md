---
paths:
  - "modules/aimon-core/src/**/hook/**/*.java"
  - "modules/aimon-core/src/**/hooks/**/*.java"
---

# Hook Development Rules

## Event Types

`HookEventType<H>` is a typed token: the constant carries the hook interface, so
`registry.register(HookEventType.PRE_TOOL, hook)` only accepts a `PreToolHook`. There are 13:

| Group | Events |
|-------|--------|
| Tool-scoped | `PERMISSION_REQUEST`, `PRE_TOOL`, `POST_TOOL`, `PERMISSION_DENIED` |
| Turn lifecycle | `ON_START`, `ON_STOP` |
| Session lifecycle | `ON_SESSION_START`, `ON_SESSION_END` |
| Subagent | `SUBAGENT_START`, `SUBAGENT_STOP` |
| Compaction | `PRE_COMPACT`, `POST_COMPACT` |
| Config | `ON_CONFIG_RELOAD` |

Adding an event means adding the interface, the context type, the `HookEventType` constant, the
`HookExecutionManager` method, and the firing site — a constant with no firing site is dead config.

## Outcome Model

`HookResult` is two independent axes, not one enum:

- **Decision** — `ALLOW` / `ASK` / `DENY` (only the permission chain reads `ASK`)
- **FlowControl** — `CONTINUE` / `BLOCK`

`HookResult.deny(reason)` stores the reason **in the feedback field**; a blocked result's feedback
*is* its deny reason. Never surface it a second time as advisory feedback.

## Which Chains Can Block

Only four chains have a caller that acts on `BLOCK`:

- `PRE_TOOL` — blocks the tool call, reason goes back to the model as the tool result
- `PERMISSION_REQUEST` — denies the call before it is dispatched
- `ON_START` — aborts the turn via `ExecutionBlockedByHookException`
- `PRE_COMPACT` — AUTO compaction is skipped, MANUAL compaction reports the reason

Every other event is advisory: returning `block()` there is silently ignored, so do not model a veto
on one.

The declarative layer honours exactly the same four. `AbstractDeclarativeShellHook#vetoResult` maps
exit 2 to `block()` on `onStart` and `preCompact` and to `deny()` on `permissionRequest`;
`DeclarativePreToolHook` maps it inline to `block()` for `preTool`. `onStart` gained its declarative
veto only recently — before that a declarative `onStart` hook could not veto at all.

## Feedback

`HookResult.withFeedback(msg)` is the only way to say something to the model — but rendering is only
half the contract: the **firing site** has to read the returned results. Three routes exist; every
other site drops what it gets. Render through `HookFeedback`, never by hand:

- `PERMISSION_REQUEST` / `PRE_TOOL` / `POST_TOOL` → appended to that tool's result by
  `SingleToolInvoker`, wrapped in `<system-reminder key="hook-feedback">`. It cannot be a separate
  user message: no user turn may sit between a `tool_use` and its `tool_result`.
- `ON_START` → the **only** lifecycle chain whose feedback becomes a user-role message, appended by
  `OrcaAgentExecutor` and `DefaultSubagentExecutor`.
- `PRE_COMPACT` → not a message at all: `DefaultCompactionEngine` folds it into the summarization
  system prompt as custom instructions.

The remaining eight discard feedback entirely — `PERMISSION_DENIED`, `ON_STOP`, `ON_SESSION_START`,
`ON_SESSION_END`, `SUBAGENT_START`, `SUBAGENT_STOP`, `POST_COMPACT`, `ON_CONFIG_RELOAD` are invoked
for side effects only. Wiring one up is a feature, not a bug fix.

## Key Rules

- Hooks must be **thread-safe** — the same instance runs across agents, and `PARALLEL` mode plus
  parallel tool dispatch run chains on shared worker threads.
- Hooks should **not throw**. The executor maps an escaping exception through
  `HookExecutionPolicy#onException`, which under `failClosedStopOnBlocked` turns a bug into a block.
- Each hook gets `HookExecutionPolicy#timeout()` (30s default) as an outer net. A hook that owns a
  longer deadline of its own must declare it via `ExecutionHook#getExecutionBudget()`, otherwise the
  net cuts it off first and its graceful outcome is lost. A declared budget is a **floor, not an
  override**: `timeoutFor` ignores anything shorter than the policy timeout, and a budget that is
  **equal to or longer than** it gets `DECLARED_BUDGET_GRACE` (+5s) so the hook's own deadline fires
  first. Equality matters in practice — `ShellAction.DEFAULT_TIMEOUT` is also 30s. The declared budget
  is clamped at `MAX_DECLARED_BUDGET` (10 minutes); anything larger is truncated with a WARN.
- Override `getHookId()` whenever several instances of one class can be registered — async-rewake
  routing and hot-reload cancellation key off it. Ids must be **content-derived and reload-stable**;
  see `DeclarativeHookId`.
- Register / unregister through `HookRegistry`; read runtime state from the event's `HookContext`.

## Declarative Hooks (`hooks.json`, SKILL.md frontmatter)

- Shell handlers receive the firing context as JSON on **stdin** (`ShellHookPayload`) plus `AIMON_*`
  env vars. Commands are **not** templated — `${tool_input.x}` in a command is a shell variable, not
  a placeholder. `TemplateRenderer` applies to HTTP/MCP actions only.
- Exit **2** vetoes with stderr as the reason (Claude Code parity), on the events that own a decision
  channel: `preTool` / `onStart` / `preCompact` block, `permissionRequest` denies. Elsewhere it is
  logged and the event proceeds. Any other non-zero exit is allowed — a broken script must not become
  a silent gatekeeper. `onStart` is the newest of the four: a declarative `onStart` hook previously
  had no veto at all.
- A handler's declared timeout is enforced by the action executor and, via
  `ExecutionHook#getExecutionBudget()`, widens the hook's outer net — subject to the same floor,
  +5s grace and 10-minute clamp as any other declared budget. In `hooks.json` the `timeout` field is
  **seconds** (Claude Code parity) with `timeoutMs` as a millisecond alias that wins when both are
  present; SKILL.md frontmatter accepts `action.timeoutMs` only.
- A declarative hook re-attaches its `asyncRewake` spec on **every** fire — `DeclarativeRewake.attach`
  cannot tell the live turn from a re-fire, and filtering there would kill the initial envelope too.
  What bounds the chain is trigger-dependent, and lives in `DefaultRewakeFireListener#chainFollowUps`:
  `delay` / `event` follow-ups are chained until `maxAttempts`; `cron` follow-ups are **never** chained,
  because the cron trigger already repeats natively — chaining would fork a second repeating series on
  every tick (~`2^(maxAttempts-1)` live envelopes) instead of extending one.
