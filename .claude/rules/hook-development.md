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
  `HookExecutionPolicy#onException`, which under `failClosedStopOnBlocked` turns a bug into a block —
  and blocks outright, whatever the policy, for a hook that declares `FAIL_CLOSED` (next rule but one).
- Each hook gets `HookExecutionPolicy#timeout()` (30s default) as an outer net. A hook that owns a
  longer deadline of its own must declare it via `ExecutionHook#getExecutionBudget()`, otherwise the
  net cuts it off first and its graceful outcome is lost. A declared budget is a **floor, not an
  override**: `timeoutFor` ignores anything shorter than the policy timeout, and a budget that is
  **equal to or longer than** it gets `DECLARED_BUDGET_GRACE` (+5s) so the hook's own deadline fires
  first. Equality matters in practice — `ShellAction.DEFAULT_TIMEOUT` is also 30s. The declared budget
  is clamped at `MAX_DECLARED_BUDGET` (10 minutes); anything larger is truncated with a WARN.
- What the net means when it fires is `HookExecutionPolicy#timeoutBehaviorFor(hook)`: the hook's own
  `ExecutionHook#getTimeoutBehavior()` when it declares one, otherwise the policy's
  `timeoutBehavior()`. Every policy `DefaultHookExecutionManager` assigns by default is `FAIL_OPEN`
  (`HookExecutionPolicy.failClosedStopOnBlocked()` exists for a host to choose), so a hook whose job is to veto must
  declare `FAIL_CLOSED` itself — the declarative guard hooks do (unless `failOpen`). Do **not** change
  a policy default to close this for one hook: it changes every programmatically registered hook of
  that event. A programmatic `PreToolHook` / `OnStartHook` that declares nothing still reads as a
  pass when the net cuts it off, and a throwing `OnStartHook` is a success under the `onStart`
  policy.
- **The same declaration closes the two other roads to "no verdict"**
  (`HookExecutionPolicy#failsClosedWithoutVerdict`): a hook the pool refused to run
  (`RejectedExecutionException` — saturated or shut down) and a hook whose body threw. A declarative
  guard catches what its *action* throws itself, so what arrives here is from outside that — the
  matcher predicate, an `Error`. Both went through `onException`, i.e. success. (The matcher that
  used to die this way was the Bash sub-command splitter, one stack frame per nested `$(`. It now
  declines to split a command over `MAX_NESTING_DEPTH` / `MAX_SPLIT_LENGTH` / `MAX_UNPAIRED_SCAN` and answers *match*, so
  the hook is asked. Do not make it answer *no match* — one more level of nesting would then step
  around every `Bash(...)` matcher — or throw, which a `postTool` / `failOpen` hook reads as a
  pass. The same goes for text it cannot pair up: an unpaired quote is read as a character and
  splitting goes on, with the whole command kept as a piece too; a change there must never make
  the matcher match *less*.) This reads only the hook's own declaration and never falls back to
  the policy's `timeoutBehavior()` — a policy may pair a `FAIL_CLOSED` timeout with a lenient
  mapper, and a hook that declares nothing keeps the mapper. The
  block reason is a fixed string plus, for a throw, the throwable's simple type name (the deny-reason
  rule below). Rejection answers at once, so a pool closed by teardown (`TeardownPhase.HOOK_EXECUTOR`,
  after `SESSIONS` and `AGENT_RUNTIMES`) cannot make a caller wait; the events an orderly shutdown
  fires (`onStop`, `onSessionEnd`) are advisory, declare nothing and are not blocked.
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
  only the cause and the exception's type; its reader is the party the guard constrains. It carries
  two messages, both because the model is already told the same text by a tool call that fails the
  same way: `StagingException`'s on `STAGING_FAILED` (the staging layer's own — over the limit,
  changed since scanned — which the `Skill` tool reports), and
  `ExecutionEnvironmentUnavailableException`'s on `ENVIRONMENT_UNAVAILABLE`, passed through in
  `DefaultShellActionExecutor.run`, `ShellActionRunner.run` and `SkillHookDirectory.export`. That one
  is `UnavailableExecutionEnvironment#message()` — "Execution environment unavailable: " plus the
  provider's own failure message when the environment was built from a `Throwable` — and it is
  exactly what `Bash` / `Read` / `Write` / `Edit` return as their error
  (`ToolResult.error(e.getMessage())`) for any call in that execution, and what `Grep` and `Skill`
  carry inside theirs, so the guard discloses nothing
  the guarded party is not handed anyway. The exception is the *typed* one only: any other throwable
  from the environment (`shell()` throwing an `IllegalStateException`) gives its type, as before. A
  tool that stops passing that message to the model takes this exception with it. Any new detail must
  be a fixed string or a type name.
- **A hook command is tied to the execution's cancellation signal, and a cancelled one blocks even
  with `failOpen`.** `ShellActionRunner` registers a per-call listener on
  `HookContext#getExecutionCancellation()` and removes it in `finally` (the signal outlives the
  command). `ShellCancelledException`, and a `ShellExecutionException` caused by
  `InterruptedException` (how `LocalShell` ends on a thread interrupt), are both `Unrun.CANCELLED`,
  which `ShellHookVerdicts` blocks regardless of `failOpen` — "allow and continue" is not an answer
  for an execution that is ending. The signal is an *execution* concept (turn, fork, routine), named
  accordingly. A firing site passes it only where it holds one: `SingleToolInvoker` for the four
  tool-scoped events (`postTool` / `permissionDenied` only while not yet cancelled, so an audit
  command fired after the interrupt still runs) and `DefaultSubagentExecutor` for a fork's
  `onStart`. A main turn's `onStart` fires before the turn's signal exists; `onStop`, compaction and
  subagent-lifecycle sites carry none.
- **`http` / `mcp` actions follow the same rule on `preTool`** (the only guard event they can sit on).
  `HttpActionExecutor#attempt` / `McpActionExecutor#attempt` return an `ActionCallOutcome`: a
  *verdict* (any readable 2xx / non-error answer) or *no verdict*, carried as a not-run outcome
  (`EXECUTOR_NOT_WIRED`, `CALL_FAILED`, `TIMEOUT`, `INVALID_RESPONSE`) and judged by the same
  `ShellHookVerdicts`. A non-2xx status is never a verdict, whatever its body says. `DecisionDocument`
  is the one reader of the answer for both transports and reads a verdict in up to three places: the
  native `decision` (`allow` / `defer` / `deny`, plus `block` as deny) on **both**, and the two Claude
  Code spellings, `hookSpecificOutput.permissionDecision` (`allow` / `deny` / `ask`) and
  `continue: false` (deny), on **`http` only**. The switch is `readClaudeCodeSpellings` (true from
  `HttpActionExecutor`, false from `McpActionExecutor`) and it covers everything only Claude Code
  writes — `hookSpecificOutput.*` (`permissionDecision`, `updatedInput`, `additionalContext`),
  `continue` / `stopReason`, `systemMessage`. On `mcp` those are not looked at, readable or not: an MCP
  tool result is not written for Claude Code, and a result with a field named `continue` must not
  become a deny or an `INVALID_RESPONSE`. A new Claude Code field goes behind the same switch.
  When statements disagree the strictest wins — deny > unreadable > ask > allow — and an unreadable
  statement (any other value, `permissionDecision: defer` included, and a `hookSpecificOutput` that
  is present and not an object) is `INVALID_RESPONSE`. `ask` is
  `Decision.ASK`, resolved on `preTool` by the manager's `AskPromptHandler` (default deny); it is a
  verdict, so `failOpen` does not open it. A deny reason comes from the reason field of a statement
  that **denied** (`reason` for `decision`, `permissionDecisionReason`, `stopReason`), else the
  default — never from a statement that did not deny (the `reason` beside `decision: allow`), and
  never from `feedback`, `systemMessage` or `additionalContext`. `updatedInput` is read at the top
  level and (http) inside `hookSpecificOutput`; non-object in either place, or two that differ, is
  `INVALID_RESPONSE` unless the document denies, and neither is picked. A new spelling goes into
  `DecisionDocument`, not into an executor. `run(...)` is the advisory
  reading (`attempt(...).orSuccess()`) and is what `postTool` calls — do not call `run` from a guard
  event. Nothing resolves an `ASK` there, so `orSuccess()` reads an ask as success carrying the
  document's own feedback (`ActionCallOutcome.ask(verdict, advisory)`): the ask's feedback slot is its
  prompt, and `HookFeedback.collectAdvisory` would hand that to the model as advice about a tool that
  already ran. A deny is still returned and `DeclarativePostToolHook#downgradeBlock` drops it. `failOpen` is read for `command`, `http` and `mcp` alike; only on `deny` is it ignored with
  a WARN — by both front-ends (`HookRegistryApplier`, `SkillHookSetParser#parseFailOpen`) and again by
  `DeclarativePreToolHook`'s constructor, so no source can build a deny hook that does not declare
  `FAIL_CLOSED`. A deny always has its verdict; all the flag could open is the pool refusing the hook
  or its matcher throwing.
- **Who wires the http / mcp executors.** `aimon-cli` does, for both sources: `HookActionExecutors`
  hands one `HttpActionExecutor` and one late-bound `McpActionExecutor` to the hot-reload bootstrap
  (`hooks.json`) and to the skill parser (frontmatter). The skill parser is built before the runtime
  exists and a parsed hook keeps its executors, hence `McpActionExecutor.lateBound(supplier)`, bound
  to the runtime's `McpClientManager` once the stack is up; when the CLI configures no MCP server the
  MCP executor is `null`, so an `mcp` guard stops startup / fails the skill load instead of loading
  and blocking every call. The executors own nothing and are on no teardown phase: the manager is
  agent-scoped and its runtime closes it, and a Java 17 `HttpClient` has no `close()`.
  `aimon-bootstrap` and the starter wire neither — a stack can hold several runtimes and an executor
  resolves one manager without the firing context, and host-side HTTP from skill frontmatter crosses
  the sandbox boundary where the environment provider is a sandbox. Hosts wire them through
  `HookHotReloadBootstrap.Builder` and `AimonStackSpec#skillParser`.
- **`HttpActionExecutor.createDefault()` does not follow redirects and reads at most
  `MAX_RESPONSE_BYTES`.** The JDK client re-sends request headers to a redirect target, and hook
  headers are where `${env.X}` puts tokens. Do not relax either to make an endpoint work; a 3xx and an
  oversized body are both "no verdict". The URL is never templated; `allowedEnvVars` is declared by
  the same file that uses it, so it bounds templates, not authors.
- The user-facing table of what blocks and what `failOpen` changes lives in **one** place,
  `docs/features/hook/hook-config-guide.md` › "가드가 막는 경우" (and its `.en.md`). A change to guard
  semantics updates that table; other docs link to it rather than restating it.
- **An `onStart` block stops a fork as it stops a turn.** Both fork paths go through
  `SubagentOnStartGate` — `DefaultSubagentExecutor.checkOnStartHooks` for a ReAct fork and
  `SubagentBehaviorRunner` for a code-behavior subagent (`SubagentBehavior`, EE-73) — mirroring
  `OrcaAgentExecutor.checkOnStartHooks`: a blocked result ends the fork before its first LLM call
  (or before the behavior runs) as a failed `SubagentExecutionResult` (`CompletionReason.BLOCKED`
  since EE-75, the `ExecutionBlockedByHookException` message) and fires no `onStop`. So an
  `onStart` in `hooks.json` gates every fork, not only the main turn — a script that means the
  user's input branches on `AIMON_INVOKER_TYPE` — and `onStart` is one of
  `SkillHookSet.guardEvents()`. A behavior fork resolves no environment of its own, so its hooks see
  the spawning execution's, and its non-blocking feedback is dropped (there is no transcript to
  append it to).
- **A `hooks.json` that does not load stops startup.** `HookRegistryReloader.bootstrap()` and
  `HookHotReloadBootstrap.start()` propagate `HookConfigParseException` (file path, layer, cause) for
  a file that does not parse *or cannot be read*; only a missing file is an absent layer. Do not
  catch it to "start anyway" — that runs with every file guard off. A failed *reload* keeps the
  previous config instead.
- **An entry that parses but cannot be applied stops startup too — under a guard event only.**
  `HookRegistryApplier` throws the same `HookConfigParseException` (file, layer, `<event> entry #n,
  handler #m`, reason) for an entry under `preTool` / `onStart` / `preCompact` / `permissionRequest`
  that it used to skip: a missing or bad field, a handler type the event does not accept, an empty
  handler list, an unparseable `preTool` matcher, and a handler that cannot run in this assembly (a
  `command` with no shell support, an `http` / `mcp` with no executor wired). Under any other event
  the same entry is still WARN-and-skip — do not turn those into failures. A handler with
  `failOpen: true` is not a guard, so "cannot run here" keeps its old handling. The message never
  quotes the command or URL. `HookConfigMerger` applies the same line to event names: unknown names
  are WARN-and-skip on purpose (forward compatibility; `HookEventName.UNSUPPORTED` exists so Claude
  Code configs import), with the nearest known name in the WARN — but a name within
  `HookEventName.NEAR_MISS_DISTANCE` (2) of a *guard* event is a typo and fails the load. When adding
  an event, check that no deliberately-accepted name sits that close to a guard name. Skill
  frontmatter needs none of this: `SkillHookSetParser` throws on every such case already.
- **Skill hooks are not registered with the runtime's `HookRegistry`.** `ScopedSkillHookActivator`
  layers them over the registry the skill's fork dispatches against (`SkillScopedHookRegistry`), so
  they fire in that fork and its descendants only. Code that spawns a fork passes
  `HookRegistryAccess.of(toolContext)` (the write-once `ToolContextKeys.HOOK_REGISTRY`) before any
  registry it holds itself; a background workflow run cannot carry it, so `Workflow` / `WorkflowJs`
  refuse background mode while a skill guard is active, and `ScheduleTask` refuses for the same
  reason (a routine fires later on the runtime's registry). A background `Task` does carry the view,
  but outlives the skill — the layer is switched off when the `Skill` tool returns and the subagent
  runs on under the runtime's hooks only — so `TaskTool` refuses `run_in_background` under an active
  skill guard too (foreground `Task` is untouched; observe-only skill hooks get a WARN). All four use
  the one judgment, `HookRegistryAccess.activeSkillGuards`, and its refusal wordings; a new tool that
  starts work which can outlive or escape the calling fork asks it too rather than deciding on its
  own. The guard check only sees the view when the context's registry *is* the
  `SkillScopedHookRegistry` — a decorator around it disables these refusals.
- A handler's declared timeout is enforced by the action executor and, via
  `ExecutionHook#getExecutionBudget()`, widens the hook's outer net — subject to the same floor,
  +5s grace and 10-minute clamp as any other declared budget. "Enforced by the executor" holds for all
  three transports: the shell's `ExecutionOptions` timeout, and for `http` and `mcp` one `CallDeadline`
  the executor keeps itself, because neither transport bounds the whole call — `McpClient.callTool`
  takes no timeout, and `HttpRequest.timeout` stops at the response headers, so a body that stalls or
  drips under `MAX_RESPONSE_BYTES` was unbounded. `CallDeadline` interrupts the calling thread when
  the time is up (`HttpClient.send` cancels the exchange on an interrupt, the stdio MCP transport
  stops polling), reported as `TIMEOUT`. `arm` goes right before the call and `disarm` in a
  `finally` — a call that leaves through an `Error` must not leave its timer to interrupt the pool
  thread later — and `disarm` leaves the interrupt flag as the call found it. The per-server
  `requestTimeout` (mcp) and the request timeout (http) still bound the request underneath, so the
  smaller wins; an interrupt that is not the executor's own is `CANCELLED` and stays set. Do not move
  the call to another thread to bound it — the hook already runs on a pool thread whose wait the
  outer net bounds, and that net is the fallback for a client that ignores the interrupt. A new
  remote transport uses `CallDeadline` rather than a second mechanism. In `hooks.json` the `timeout`
  field is
  **seconds** (Claude Code parity) with `timeoutMs` as a millisecond alias that wins when both are
  present; SKILL.md frontmatter accepts `action.timeoutMs` only.
- A declarative hook re-attaches its `asyncRewake` spec on **every** fire — `DeclarativeRewake.attach`
  cannot tell the live turn from a re-fire, and filtering there would kill the initial envelope too.
  What bounds the chain is trigger-dependent, and lives in `DefaultRewakeFireListener#chainFollowUps`:
  `delay` / `event` follow-ups are chained until `maxAttempts`; `cron` follow-ups are **never** chained,
  because the cron trigger already repeats natively — chaining would fork a second repeating series on
  every tick (~`2^(maxAttempts-1)` live envelopes) instead of extending one.
