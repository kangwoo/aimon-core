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
- What the net means when it fires is `HookExecutionPolicy#timeoutBehaviorFor(hook)`: the hook's own
  `ExecutionHook#getTimeoutBehavior()` when it declares one, otherwise the policy's
  `timeoutBehavior()`. Every shipped policy is `FAIL_OPEN`, so a hook whose job is to veto must
  declare `FAIL_CLOSED` itself — the declarative guard hooks do (unless `failOpen`). Do **not** change
  a policy default to close this for one hook: it changes every programmatically registered hook of
  that event. A programmatic `PreToolHook` / `OnStartHook` that declares nothing still reads as a
  pass when the net cuts it off, and a throwing `OnStartHook` is a success under the `onStart`
  policy.
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
  a silent gatekeeper — **except 126 and 127**, which the next rule reads as "not run". `onStart` is
  the newest of the four: a declarative `onStart` hook previously had no veto at all.
- **No answer is a veto too (fail-closed).** On those four events a shell handler whose command
  never produced an exit status — no execution environment, an unavailable one, a skill directory
  that could not be staged for `AIMON_SKILL_DIR` (`STAGING_FAILED`), a timeout, a shell failure, an
  executor without shell support, an executor that throws (`LinkageError` included) — blocks/denies,
  with the cause in the reason. So does a command the shell could not start: **exit 126 / 127**
  (`COMMAND_NOT_EXECUTABLE` / `COMMAND_NOT_FOUND`) is read through `ShellHookOutcome.asGuardAnswer()`
  on a guard event only — the runner still reports the code as observed, and an advisory event just
  logs it. The executor reports a missing exit status as `ShellHookOutcome.notRun(cause, detail)`
  (there is no cause-less factory) and `ShellHookVerdicts` decides; do not interpret an outcome on a
  guard event anywhere else. A hook opts out per declaration with `failOpen: true` (entry level in
  frontmatter, handler level in `hooks.json`; only a boolean `true` opens it — a non-boolean is a
  parse error in frontmatter and is read as `false` with a WARN in `hooks.json`). The deny reason
  never names `failOpen`, the command, the shell's stderr or an *unexpected* exception's message —
  only the cause and the exception's type; its reader is the party the guard constrains. The one
  message it does carry is `StagingException`'s on `STAGING_FAILED`: that text is the staging
  layer's own (over the limit, changed since scanned) and is what the `Skill` tool already tells the
  model for the same failure. Any new detail must be a fixed string or a type name.
- **`http` / `mcp` actions follow the same rule on `preTool`** (the only guard event they can sit on).
  `HttpActionExecutor#attempt` / `McpActionExecutor#attempt` return an `ActionCallOutcome`: a
  *verdict* (any readable 2xx / non-error answer — only `decision: deny` blocks) or *no verdict*,
  carried as a not-run outcome (`EXECUTOR_NOT_WIRED`, `CALL_FAILED`, `TIMEOUT`, `INVALID_RESPONSE`)
  and judged by the same `ShellHookVerdicts`. A non-2xx status is never a verdict, whatever its body
  says, and neither is a `decision` outside `allow` / `deny` / `defer`. `run(...)` is the advisory
  reading (`attempt(...).orSuccess()`) and is what `postTool` calls — do not call `run` from a guard
  event. `failOpen` is read for `command`, `http` and `mcp` alike; only on `deny` is it ignored with
  a WARN. Neither in-tree assembly (`aimon-cli`, `aimon-bootstrap`) wires an http or mcp executor.
- The user-facing table of what blocks and what `failOpen` changes lives in **one** place,
  `docs/features/hook/hook-config-guide.md` › "가드가 막는 경우" (and its `.en.md`). A change to guard
  semantics updates that table; other docs link to it rather than restating it.
- **An `onStart` block stops a fork as it stops a turn.** `DefaultSubagentExecutor.checkOnStartHooks`
  mirrors `OrcaAgentExecutor.checkOnStartHooks`: a blocked result ends the fork before its first LLM
  call as a failed `SubagentExecutionResult` (`CompletionReason.ERROR`, the
  `ExecutionBlockedByHookException` message) and fires no `onStop`. So an `onStart` in `hooks.json`
  gates every fork, not only the main turn — a script that means the user's input branches on
  `AIMON_INVOKER_TYPE` — and `onStart` is one of `SkillHookSet.guardEvents()`. A code-behavior
  subagent (`SubagentBehavior`) fires no `onStart` at all (EE-73).
- **A `hooks.json` that does not load stops startup.** `HookRegistryReloader.bootstrap()` and
  `HookHotReloadBootstrap.start()` propagate `HookConfigParseException` (file path, layer, cause) for
  a file that does not parse *or cannot be read*; only a missing file is an absent layer. Do not
  catch it to "start anyway" — that runs with every file guard off. A failed *reload* keeps the
  previous config instead. Handler-level problems found at apply time (missing `command`, unknown
  event name) are still WARN-and-skip (EE-72).
- **Skill hooks are not registered with the runtime's `HookRegistry`.** `ScopedSkillHookActivator`
  layers them over the registry the skill's fork dispatches against (`SkillScopedHookRegistry`), so
  they fire in that fork and its descendants only. Code that spawns a fork passes
  `HookRegistryAccess.of(toolContext)` (the write-once `ToolContextKeys.HOOK_REGISTRY`) before any
  registry it holds itself; a background workflow run cannot carry it, so `Workflow` / `WorkflowJs`
  refuse background mode while a skill guard is active, and `ScheduleTask` refuses for the same
  reason (a routine fires later on the runtime's registry). The guard check only sees the view when
  the context's registry *is* the `SkillScopedHookRegistry` — a decorator around it disables these
  refusals.
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
