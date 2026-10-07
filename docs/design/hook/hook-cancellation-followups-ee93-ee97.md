# EE-93 – EE-97 — the follow-ups the hook-cancellation work (EE-80) registered

> Status: **IMPLEMENTED** (2026-10-07) — `aimon-core`: `DeclarativeHookOptions` and the declarative hook classes,
> `ExecutionHook`, `DefaultHookExecutor`, the two hook front-ends (`HookHandlerSpec` / `HookRegistryApplier`,
> `SkillHookSetParser`), `DefaultCompactionEngine` and the two compaction breakers, `SignalBackedLlmCancellation`,
> `DefaultSubagentExecutor`, `InterruptCoordinator`, `OrcaAgentExecutor.runTurn` and `DefaultLiveSession`. This is the
> design for backlog items EE-93 – EE-97 in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md): that change
> closed EE-94, EE-95 and EE-97, narrowed EE-93 and left EE-96 open with a decision. Open or closed is the backlog's to
> say, not this document's.
>
> **[§13](#13-where-the-implementation-departed-from-this-design), appended after the build, is where the
> implementation departs from this document.** Everything between this header and §13 is the body as approved in
> design review round 1 (PASS, no blocking findings, eleven non-blocking notes), kept byte-exact rather than corrected —
> the house habit in this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its
> `file:line` citations are at `52d36952`, as its first line says. The review transcript (`review-1.md`) and the run
> record the body's wording comes from (`TASK.md`) are not in the repository; §13 says which review notes were taken.
>
> It is in English, like [`hook-shell-cancellation-ee80.md`](hook-shell-cancellation-ee80.md), whose open questions
> these five items were; `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1). What
> the hook system does today is in [`hook-system.md`](hook-system.md); the open questions of §12 that reach beyond this
> change went to the backlog as EE-98 – EE-100 (§13.3).

Base: `52d36952`. Paths are relative to `modules/aimon-core/src/main/java/at/aimon/core/` unless they start with
`docs/`, `modules/` or `.claude/`. Line numbers are approximate (`≈`) and at the base commit. Where a statement was read
from the code and not run, it says so.

## 1. Problem

EE-80 tied a hook's shell command to the execution's cancellation signal on every event that has one, and left five
things open. **EE-97:** a report-event command (`onStop`, `subagentStop`, `postCompact`, …) that is running when an
interrupt arrives is now stopped on every shell, `LocalShell` included, and the hook author cannot opt out — so a
normally completed turn's cleanup command can be cut in half by the next NOW-priority input. That state is on `main`
(under `[Unreleased]` in `CHANGELOG.md`, so not yet in a release). **EE-95:** the AUTO-compaction summary LLM call
ignores the signal, so an interrupt during compaction waits the call out. **EE-94:** a fork interrupted during its
`onStart` chain ends `BLOCKED` or `INTERRUPTED` depending on what kind of hook was running, while a turn always ends
`INTERRUPTED`. **EE-93:** a slash-command turn cannot be interrupted after `onStart` yet reports
`isInterruptible() == true` (a value that crosses nodes), and an interrupt that arrives before `onStart` is dropped.
**EE-96:** a cancellation that reaches the firing thread only as `Thread.interrupt()` does not stop a hook command on
a shell that ignores thread interrupts. Each entry asks for either a change or a decision; this design gives each a
definite outcome.

## 2. Outcome per item

| Item | Outcome | Entry status after the change |
|---|---|---|
| EE-97 | **Build.** Per-hook option `ignoreInterrupt`, honoured on report events, covering both roads an interrupt takes to the command. | 닫힘 |
| EE-95 | **Build.** The summary call carries the signal as an `LlmCancellation`; a cancelled summary is not a compaction failure. | 닫힘 |
| EE-94 | **Build.** The fork reads its cancellation right after the `onStart` chain, before the blocks, and ends `INTERRUPTED`. | 닫힘 |
| EE-93 | **Split.** Build the two cheap halves — the flag tells the truth, and an interrupt before `onStart` is kept and delivered. **Do not** make a slash command interruptible now. | 열림 *(트리거 대기)*, narrowed to "a running slash command cannot be interrupted", with a dated note |
| EE-96 | **Do not build.** Decision and the sketch of the fix recorded as a dated note. | 열림 *(트리거 대기)*, with a dated note |

One commit per item, in this order: EE-97, EE-95, EE-94, EE-93, EE-96 (docs only).

## 3. Findings the design rests on

1. **`LiveSession.interrupt` reaches a finished turn's `onStop` through the signal only.**
   `DefaultInterruptCoordinator.requestInterrupt` trips the signal and fires the registrars of tools in flight; nothing
   interrupts the turn's own thread. During `onStop` the only listener is the one `ShellActionRunner` registered for
   the running command (`skill/hook/declarative/ShellActionRunner.java` ≈`:80-88`).
2. **A background fork's stop takes both roads.** `subagent/task/RunningTaskHandle.requestStop` (≈`:114-121`) trips the
   task's signal *and* calls `worker.interrupt()`; `workflow/impl/RunControl.requestStop` does the same. On the worker,
   `DefaultHookExecutor.awaitHook` (≈`:330-348`) answers the thread interrupt with `future.cancel(true)`, which
   interrupts the hook's pool thread, and `LocalShell` answers that. So unbinding the signal alone would not keep a
   background fork's `onStop` / `subagentStop` command alive on a local shell. *(Read, not run.)*
3. **Every in-tree thread interrupt of an execution's thread comes with a signal trip** (finding 2 — those are the two
   sites; the other `.interrupt()` calls in `modules/*/src/main` target tool threads via terminators, deadline
   watchdogs, or listener threads). A thread-interrupt-*only* cancellation can come from an embedder alone.
4. **Only the two shell executors read the hook's signal.** `DefaultShellActionExecutor` (≈`:93`) and
   `HostShellActionExecutor` (≈`:60`) pass `context.getExecutionCancellation()` to the runner. `http` / `mcp` actions
   do not bind to it. Both executors, and `SkillHookDirectory.export`, take the context as the `HookContext` interface
   and call nothing but interface methods on it.
5. **`failOpen` is the precedent for a per-hook option**, end to end: `config/hook/HookHandlerSpec` (JSON, strict
   boolean, a non-boolean is read as `false` and kept for a WARN) → `config/hook/HookRegistryApplier` ≈`:186-217`;
   `skill/parser/SkillHookSetParser.parseFailOpen` ≈`:252` (YAML, non-boolean is a parse error, WARN where the key
   cannot have an effect) → `DeclarativeHookOptions` → the hook classes.
6. **The summary call goes straight to the client with no token**: `agent/compact/DefaultCompactionEngine.java` ≈`:403`,
   `llmClient.sendMessage(systemPrompt, messages, List.of(), model, callMetadata)`, with a `catch (RuntimeException)`
   that turns anything into `CompactionResult.failure`. Both breakers count such a failure unless its type is exempt
   (`DefaultCompactionGuard.recordFailureIfTransient` ≈`:458`, `agent/context/RollingContextEngine.java` ≈`:655`).
7. **A provider handed a live token reroutes a blocking call through its streaming transport**
   (`AnthropicLlmClient` ≈`:284`, `OpenAILlmClient` ≈`:228`); the main loop's non-streaming mode already runs that way.
   The cancellation-aware `LlmClient.sendMessage` overload takes `SystemPromptParts`, not a `String`.
8. **`SignalBackedLlmCancellation` registers one listener and never removes it**; its Javadoc says the signal has no
   deregistration, which is no longer true (`CancellationSignal.onCancel` returns a `Registration`).
9. **A fork blocked in `onStart` hands back the transcript as it stood before the goal** and fires no `onStop`
   (`subagent/execution/DefaultSubagentExecutor.startFork` ≈`:592-602`, `createBlockedResult`); an interrupted fork
   hands back the live buffer, goal included, and fires `onStop(success=false)` (`createFailureResult`).
   No production code branches on `CompletionReason.BLOCKED` (grep over `modules/*/src/main`).
10. **Nothing in production reads `isInterruptible`** except `StatusSnapshotPayload`, which copies the boolean to and
    from the wire under the key `interruptible`. `DefaultLiveSession.status()` computes it as `coordinator != null`
    (≈`:461`), and the observer that sets the coordinator (≈`:780`) can only set it, never clear it. So the flag is
    also `true` for a ReAct turn between the coordinator's close and `clearActiveTurn` (persist, memory feed), where
    an interrupt lands on a closed coordinator.
11. **In the command flow, nothing reads the turn's coordinator after `onStart`.** `executeCommandFlow` passes an
    explicit `null` to `invokeOnStop`; `executeCommand` (≈`:2084`) mints its own. The other reads of
    `scope.coordinator` (`:1576`, `:2653`, `:3212`) are on the loop's path. *(From grep; the builder confirms.)*

## 4. EE-97 — `ignoreInterrupt`

### Approach

A hook declares that its command is not stopped by an interrupt of the execution:

```json
{ "type": "command", "command": "./cleanup.sh", "timeout": 20, "ignoreInterrupt": true }
```

```yaml
hooks:
  onStop:
    - action: { shell: "./cleanup.sh" }
      ignoreInterrupt: true
```

It is honoured on the **report** events — `onStop`, `subagentStop`, `postCompact`, `postTool`, `permissionDenied`,
`subagentStart` — and only for a shell command, because that is the only action bound to the signal (finding 4). It
does two things, one per road:

- **Signal road.** The hook hands its executor a view of the context whose `getExecutionCancellation()` is empty.
  The runner then builds options with no cancellation token and registers nothing, so the command starts and runs
  exactly as one fired on a cancelled execution already does under the report rule.
- **Thread road.** The hook declares `ExecutionHook#ignoresInterrupt()`. `DefaultHookExecutor.awaitHook`, on an
  `InterruptedException` while waiting for such a hook, does not cancel the task: it keeps waiting for what is left
  of the hook's budget, returns the hook's own result (or goes down the ordinary timeout path), and re-arms the
  thread's interrupt flag on the way out so the caller's cancellation still sees it.

What it does not do, stated in the guide: the hook's `timeout` still ends it; so does teardown of the hook pool and
the process exiting; and the execution's thread waits for the command, so the next input waits too — the author is
told to keep the timeout tight. The default is unchanged: without the option a report command still follows the
EE-80 rule (bound while live). The gate rule is untouched: on `onStart` / `preCompact` / `permissionRequest` /
`preTool` the option is dropped with a WARN at load, and the hook classes ignore it even if constructed with it, so a
cancelled guard still blocks regardless of `failOpen`.

### Alternatives rejected

| Alternative | Why not |
|---|---|
| Never bind `onStop` (EE-80's Q3 alternative), or bind it only for a turn that did not complete | Re-decides EE-80 for everyone to serve the hooks that need it. A remote shell's `onStop` would again run to its timeout after an interrupt. Listed as open question Q2, because EE-80 is unreleased and the default is the maintainer's call. |
| Signal road only | A background fork's `Task.stop` also interrupts the worker (finding 2), so on `LocalShell` the option would silently not hold for exactly the hooks `subagentStop` and a fork's `onStop` exist for. An opt-out with an unstated hole is worse than none. |
| Add a parameter to `ShellActionExecutor.run` | It is a public SPI with four arguments; every external executor would have to learn the flag, and one that did not would silently ignore it. A context view needs no executor to change. |
| Put the flag on `ShellAction` | The action is what to run; `failOpen` set the precedent that how the hook is treated lives in `DeclarativeHookOptions`. |
| Put the rule in the context's getter, as EE-80 did for gate vs report | The context is per chain and the option is per hook. |
| Honour it on gate events too | The turn would wait on a guard after the user stopped it, and the guard's answer would no longer be "cancelled ⇒ block". |
| Detach the command (do not wait for it) | Nothing then bounds or reports it, and the session could close under it. |

### Changes

- `skill/hook/declarative/DeclarativeHookOptions.java` — `ignoreInterrupt` field, builder setter, `isIgnoreInterrupt()`,
  in `equals` / `hashCode` / `toString`, class Javadoc bullet.
- `skill/hook/declarative/` — new package-private `SignalDetachedHookContext implements HookContext`: delegates every
  method to the wrapped context and answers `Optional.empty()` for `getExecutionCancellation()`.
- `skill/hook/declarative/AbstractDeclarativeShellHook.java` — keep `ignoreInterrupt = options.isIgnoreInterrupt() &&
  !canVeto()`; in `runShell`, pass the detached view to `SkillHookDirectory.export` and `shellExecutor.run` when set;
  override `ignoresInterrupt()`.
- `skill/hook/declarative/DeclarativePostToolHook.java` — the same for its shell path (≈`:189`). `http` / `mcp`
  actions there ignore the option. `DeclarativePreToolHook` does not read it.
- `hook/execution/ExecutionHook.java` — `default boolean ignoresInterrupt() { return false; }`, documented beside
  `getTimeoutBehavior()`.
- `hook/execution/DefaultHookExecutor.java` — the `InterruptedException` branch of `awaitHook` as above; `onInterrupted`
  Javadoc gains the exemption and why it is not a way round a guard (the wait yields the hook's real verdict or a
  timeout, never success-without-running).
- `config/hook/HookHandlerSpec.java` — `ignoreInterrupt`, bound like `failOpen`: `@JsonInclude(NON_DEFAULT)`, raw
  `JsonNode` in the creator, a non-boolean read as `false` and kept for the loader's WARN. Generalise the
  `rejectedFailOpen` plumbing rather than copy it; `config/hook/HookConfigLoader.java` ≈`:215` warns for both keys.
- `config/hook/HookRegistryApplier.java` — after the `failOpen` block: drop with a WARN when the event is a gate, is one
  of the three events outside any execution, or the action is not a shell command; otherwise put it on the options.
- `skill/parser/SkillHookSetParser.java` — `parseIgnoreInterrupt` beside `parseFailOpen`, same strictness and WARNs.
- The set of report events is defined once (next to `SkillHookSet.guardEvents()`), not listed in each front-end.
- Not changed: `ShellActionRunner`, `ShellHookVerdicts`, the shell executors, the hook contexts, `HookEventType`.

### Shapes

```java
// DeclarativeHookOptions
Builder ignoreInterrupt(boolean ignoreInterrupt);   // default false
boolean isIgnoreInterrupt();
// ExecutionHook (public SPI, additive)
default boolean ignoresInterrupt() { return false; }
```

`hooks.json` handler key and SKILL.md entry key `ignoreInterrupt`, boolean, default `false`. An older node reading a
`hooks.json` that carries the key: the builder checks whether `HookHandlerSpec` tolerates unknown properties and says
so in the CHANGELOG either way.

## 5. EE-95 — the summary call follows the signal

### Approach

In `DefaultCompactionEngine.generateSummary`, when the request carries a signal: if it has already tripped, fail as
cancelled without calling the model; otherwise call the cancellation-aware overload with a `SignalBackedLlmCancellation`
that lives for that one call. With no signal (`/compact`, a custom engine that does not forward it) the call is made
exactly as today — same overload, same transport. A cancelled call surfaces as `LlmCallCancelledException`; the engine
catches it before the generic `RuntimeException`, logs at debug, and returns `CompactionResult.failure` carrying it.
Both breakers exempt that type, as they exempt a hook block. Nothing new is needed to end the execution: at the
blocking limit the guard answers `BLOCK` and both executors already read the signal there (EE-80); otherwise the loop
goes on to its own LLM call, which the gateway short-circuits on the tripped token. The rolling engine reaches the
same code through `CompactionEngine.summarize`.

### Alternatives rejected

| Alternative | Why not |
|---|---|
| Route the summary through `LlmCallGateway` | Brings retry and fallback to a call that has neither today; a different change. |
| A new `CompactionCancelledException` | The breakers and callers would learn a second type for what `LlmCallCancelledException` already says; `agent.compact` already depends on `llm`. |
| Throw `CancelledExecutionException` out of the engine | `ContextEngine` / `CompactionGuard` return results; a throw through them is a new contract for custom engines. The existing result path already unwinds. |
| One `SignalBackedLlmCancellation` per request, never removed | Leaks one listener per compaction on a signal that lives for the whole execution (finding 8). |
| Thread the executor's own `SignalBackedLlmCancellation` through the four request types | Four more optional fields to carry an adapter over a signal the request already holds. |

### Changes

- `agent/interrupt/SignalBackedLlmCancellation.java` — keep the `Registration`; implement `AutoCloseable`, `close()`
  removes it (idempotent). Correct the Javadoc of finding 8. The executors' per-execution instances need no change.
- `agent/compact/DefaultCompactionEngine.java` — as above, in a small helper so `generateSummary` stays under the
  method-length limit. The system prompt is wrapped as `SystemPromptParts`; the builder must show the provider request
  is the same as the `String` overload produces (no added cache breakpoint) — if it cannot be made the same, that goes
  in the handoff, not silently shipped.
- `agent/compact/DefaultCompactionGuard.java`, `agent/context/RollingContextEngine.java` — exempt
  `LlmCallCancelledException` from the breaker.
- `agent/compact/CompactionEngine.java` / `SummaryRequest.java` Javadoc — the signal now also bounds the summary call.

Embedder-visible: while a signal is present the summary call uses the provider's streaming transport (finding 7), and
a custom `LlmClient` that does not override the cancellation overload behaves as today.

## 6. EE-94 — a fork interrupted in `onStart` ends `INTERRUPTED`

### Approach

`startFork` evaluates the loop's own checkpoint predicate, `isCancelledOrInterrupted(signal)`, once, right after the
`onStart` chain returns or throws its block — before looking at either. If it is true the fork ends through a new
`createInterruptedBeforeStartResult(lc, beforeGoal)`: `CompletionReason.INTERRUPTED`, the canonical "Execution
interrupted" message, `onStop(success=false)` fired like every other interrupted fork, and the **`beforeGoal`**
snapshot. Any block reasons are logged at debug, as the turn does. Using the checkpoint predicate (not a bare signal
read) matters: it consumes the thread flag `awaitHook` re-armed (finding 2), which would otherwise make every `onStop`
hook's wait throw at once.

The snapshot is the one real decision. A fork's goal is written by the parent model, and a background fork can be
stopped by that same model (`Task.stop`) and resumed later. If the interrupted result carried the live buffer, a goal
whose guard was cut off before answering would be persisted and replayed to the fork's model on resume, where
`onStart` is shown only the new goal. Handing back `beforeGoal` keeps "no verdict, not persisted" — what the `BLOCKED`
result does today. *(The resume path was read from `createBlockedResult`'s Javadoc, not run.)*

`SubagentBehaviorRunner` passes the gate no signal and has no loop; it is not changed.

### Alternatives rejected

| Alternative | Why not |
|---|---|
| Leave it, add a note | The result still depends on the hook's kind and on which road the stop took; the fix is a few lines and no reader depends on the old answer (finding 9). |
| Convert only the block (`catch` → interrupted), leave the non-blocking case to the loop's first checkpoint | Two paths again: one persists the goal and one does not. |
| Interrupted result with the live buffer, as a later interrupt gives | The resume replay above. |
| Move the read into `SubagentOnStartGate.check` | The gate is shared with the code-behavior path, which has no signal and no interrupted result to give. |

### Changes

- `subagent/execution/DefaultSubagentExecutor.java` — `startFork`, the new result method, Javadoc of
  `checkOnStartHooks` (drop "open (EE-94)") and of `runReActLoop`.
- `agent/impl/orca/OrcaAgentExecutor.java` — the `checkOnStartHooks` Javadoc sentence that says a fork does not do this.

Visible change: the parent model reads "Execution interrupted" instead of "…blocked by OnStart hook: …execution
cancelled…"; the fork's `onStop` hooks now fire in this case; the reason is `INTERRUPTED`. No new enum constant, so
nothing for a rolling upgrade to trip on (EE-83).

## 7. EE-93 — what is built and what is not

**Not built: making a slash command interruptible.** The trigger has not fired — no report of a command that could not
be stopped, and no host reads `isInterruptible` (finding 10). And it is not one change: handing the command the turn's
signal means deciding what an inline skill's loop does at a tripped signal, what an interrupted command leaves in the
transcript, how `CommandExecutionResult` says "interrupted", and what a MANUAL `preCompact` block (downgraded to a
warning today) means when the block is a cancelled guard. The dated note records this list as the work to do when the
trigger fires, and that EE-95 has already made `/compact`'s summary call stoppable once a signal reaches it.

**Built (a): the flag tells the truth.** `InterruptCoordinator` gains `default boolean isClosed() { return false; }`,
implemented by `DefaultInterruptCoordinator`. `runTurn` closes the turn's coordinator before `executeCommandFlow`
(finding 11; the try-with-resources close is idempotent). `DefaultLiveSession.status()` reports
`interruptible = coordinator != null && !coordinator.isClosed()`. A later `interrupt` on a command turn becomes the
coordinator's logged no-op instead of a trip nobody reads, and the flag is `false` for a command turn after `onStart`
and for any turn after its coordinator closed. The wire shape does not change — same key, same type — so a mixed
cluster only disagrees on the value for those two states, and no reader exists.

**Built (b): an interrupt before `onStart` is kept.** `ActiveTurn` gains a `pendingInterrupt`. `interrupt(turn, reason)`
with no coordinator yet stores the reason (first wins) and re-reads the coordinator to close the race; the observer
sets the coordinator and then delivers a stored reason. `requestInterrupt` is idempotent, so a double delivery is
harmless. The turn then meets a tripped signal at `onStart` and ends by the path EE-80 built: gate commands are not
started, the turn is `INTERRUPTED`, `onStop(success=false)` fires, no LLM call is made. This also applies to
NOW-priority preemption and `close()`, which share the method. The flag stays `false` in that window: it under-reports
for a moment rather than promising a delivery to an executor that may never publish a coordinator.

Rejected: clearing the published handle (needs a wider observer — EE-80 DV-3); deriving the flag from the tracker
(`false` during `onStart`, where an interrupt does end the turn); having the session ask whether the input is a
command (that knowledge is the executor's).

Changes: `agent/interrupt/InterruptCoordinator.java`, `DefaultInterruptCoordinator.java`;
`agent/impl/orca/OrcaAgentExecutor.java` (`runTurn`, and the comments at `executeCommand` and
`interruptCoordinatorFactory`); `agent/session/DefaultLiveSession.java` (`status()`, `interrupt`, `ActiveTurn`, their
Javadoc); `agent/session/LiveSessionStatus.java` and `agent/impl/orca/OrcaAgentExecutionRequest.java` Javadoc;
`hook/execution/HookContext.java` where it describes the command turn. `StatusSnapshotPayload` is not touched.

## 8. EE-96 — not built

The entry asks whether a thread interrupt on the firing thread should also trip the signal the hook command is bound
to. Decision: not now, for three reasons recorded in the note. (1) The trigger needs an interrupt-deaf shell *and* a
host that cancels by `Thread.interrupt()` alone; in-tree, every interrupt of an execution's thread is paired with a
signal trip (finding 3), so the command already stops by the signal's road. (2) The exposure is bounded: the command
runs to its own timeout, and the loop's checkpoints already promote a bare thread interrupt into the signal
(`OrcaAgentExecutor.isInterrupted`). (3) The hook executor holds a read-only signal and cannot trip it; the fix
belongs at the fire sites that own a coordinator — after the chain returns with the thread flag set, promote it with
`requestInterrupt`, which reaches the still-registered listener of the running command — and that covers `onStart`
and `onStop` but not the tool-scoped events (fired where only the signal is in hand) or compaction. A partial fix for
an unfired trigger is not worth a second cancellation path. The note also records what the other items changed about
this one: after EE-94 a fork interrupted this way in `onStart` ends `INTERRUPTED`; a turn still ends as
`ExecutionBlockedByHookException`; and an `ignoreInterrupt` hook is by design not stopped by either road.

## 9. Failure modes

| Failure | Handling |
|---|---|
| `ignoreInterrupt` on a guard event, a non-shell action, or an event outside any execution | Dropped at load with a WARN naming the entry; the gate hook classes ignore it as a second line. |
| `ignoreInterrupt` given as `"true"` / `1` / `null` | `hooks.json`: read as `false` with a WARN, as `failOpen` (a parse failure would drop the file's guards). Frontmatter: parse error, as `failOpen`. |
| The must-finish command hangs | Its `timeout` ends it; the option does not lengthen anything. The execution's thread and the next input wait that long — documented. |
| Firing thread already interrupted when an `ignoreInterrupt` hook is awaited | The wait continues uninterruptibly; the flag is re-armed afterwards, so later hooks in the chain and the caller see it as today. |
| `HookContext` gains a method and the detached view does not delegate it | A test asserts the view overrides every `HookContext` method. |
| Reload changes only `ignoreInterrupt` | It is in `DeclarativeHookOptions.equals` and in `HookHandlerSpec`; the builder confirms the reloader's diff sees it. |
| Summary call cancelled | Failure carrying `LlmCallCancelledException`, not counted by either breaker, logged at debug; the execution ends interrupted by existing paths (§5). |
| Signal trips, but the client ignores the token and returns a summary | The summary is installed and `postCompact` fires — finished work is kept. |
| Listener left on the signal after a summary call | `close()` in a try-with-resources; a test counts registrations. |
| A fork is interrupted during `onStart` and a hook also vetoed | Interrupt wins, reasons logged; the goal is not persisted either way. |
| Thread flag still set when the interrupted fork fires `onStop` | Consumed by the checkpoint predicate before the result is built (§6). |
| `interrupt` races the coordinator's publication | Store, then re-read; the observer delivers after setting; both may deliver, and the second is a no-op. |
| Turn throws before `onStart` with an interrupt pending | The pending reason dies with the `ActiveTurn`. |
| A custom `InterruptCoordinator` does not override `isClosed()` | Reports `false`; the flag behaves as today for that coordinator. |
| Something on the command flow does read the turn's coordinator after all | The builder greps before closing it early; if a read exists, the early close is dropped and (a) is reported as not done rather than worked around. |

## 10. Test strategy

Stops and non-stops are proved by what the fake shell / client observed — its cancellation token tripped or not, its
thread interrupted or not — never by elapsed time (`DeclarativeHookCancellationTest.cancellationOnlyShell()` style).
Each behavioural test fails on the base commit; for the new option that means its twin without the option stops.

- **EE-97, hook level** (`DeclarativeHookCancellationTest`): for each report event, with `ignoreInterrupt` the command's
  options carry no token and a trip while it runs leaves it running to completion; without it, stopped (existing).
  On each gate event the option changes nothing: stopped, `BLOCKED`, with `failOpen` true and false.
- **EE-97, executor** (`DefaultHookExecutor` tests): firing thread interrupted while an `ignoresInterrupt` hook runs —
  the hook's thread is not interrupted, the hook's own result comes back, the caller's flag is set on return; a hook
  that outlives its budget still times out; a hook without the declaration is `BLOCKED` as before.
- **EE-97, front-ends**: both parsers accept the key, drop it with a WARN in the three no-effect cases, and treat a
  non-boolean as specified; options equality; the detached view delegates every `HookContext` method.
- **EE-97, end to end** (`OrcaAgentExecutorHookCancellationTest`): a turn completes, its `onStop` command is running,
  the interrupt arrives through the observer's coordinator — the command finishes. And a background fork stopped via
  its task handle while `onStop` runs on a shell that answers thread interrupts — the command finishes.
- **EE-95**: a fake `LlmClient` whose cancellation overload blocks until its abort runs and records it. Engine: abort
  observed, result is the cancelled failure, registration count back to zero; already-tripped signal makes no call; no
  signal uses the old overload. Guard and rolling engine: the failure does not move the breaker. Executor: a turn
  interrupted during an AUTO summary ends `INTERRUPTED` (also over the blocking limit), and so does a fork.
- **EE-94**: a fork whose signal trips during a shell `onStart` guard, during a programmatic `onStart` hook, and via
  the task handle (signal + thread) — all three give `INTERRUPTED`, "Execution interrupted", an empty/`beforeGoal`
  snapshot, and an `onStop` that actually ran. A plain block still gives `BLOCKED` and no `onStop`.
- **EE-93**: `status()` for a command turn is interruptible during `onStart` and not after; not after a ReAct turn's
  coordinator closes; an `interrupt` before the coordinator is published ends the turn `INTERRUPTED` with no LLM call
  and no command run; the publication race in both orders. `DefaultLiveSessionStatusTest.turnBeforeLoopEntryIsIdleButInterruptible`
  is expected to change; any other edited existing test is listed in the handoff.
- **Regression (EE-80)**: the existing cancellation suites pass unchanged apart from the tests named above.
- `./gradlew checkAll`, `scripts/check-doc-links.py`, `check-translation-structure.py`, `check-translation-staleness.py`.

## 11. Docs, per commit

- `docs/backlog/execution-environment-open-items.md` — each entry's status and a dated note (§2). No translation exists
  for the backlog; the builder confirms.
- `docs/features/hook/hook-config-guide.md` + `.en.md` — the handler key list (≈`:286`), the "인터럽트" paragraph
  (≈`:588-597`, replacing "감안해 쓴다" with the option and its limits), the frontmatter key table (≈`:983`), the WARN
  rows in troubleshooting.
- `docs/features/hook/hook-development-guide.md` + `.en.md` — `ignoresInterrupt()` beside the timeout-behaviour notes,
  and the `getExecutionCancellation()` row.
- `docs/design/hook/hook-system.md` (no translation) — §3.3 and the EE-80 paragraph (≈`:271-295`).
- `docs/design/hook/hook-shell-cancellation-ee80.md` — body stays byte-exact; one line under §10.3 saying where
  EE-93 – 97 went.
- `.claude/rules/hook-development.md` — the cancellation bullet (≈`:156`).
- `CHANGELOG.md` `[Unreleased]` — one entry per item that changes behaviour, each naming what an embedder sees:
  the new key and SPI method; the summary call's transport and the breaker exemption; the fork's reason, message and
  `onStop`; `isInterruptible` values, `InterruptCoordinator#isClosed()`, and that an interrupt before `onStart` now
  ends the turn.
- Any compaction / session / subagent design doc that states the old behaviour is found by grep for the changed
  Javadoc phrases and fixed in the same commit, with its `*.en.md` where one exists.

## 12. Open questions

- **Q1 — the option's name.** `ignoreInterrupt` is a config key and hard to rename after release. Alternatives:
  `runToCompletion` (overpromises — the timeout still applies), `uninterruptible`. Built as `ignoreInterrupt` unless
  told otherwise.
- **Q2 — the default.** I kept EE-80's default (a report command is bound while live) and added an opt-out. Because
  EE-80 is still `[Unreleased]`, the other choice costs no compatibility: leave `onStop` unbound by default and make
  stopping it the opt-in. That re-decides EE-80's Q3 and is the maintainer's call; it goes in the handoff.
- **Q3 — the thread road in EE-97** adds a method to the public `ExecutionHook` SPI. Without it the option does not
  hold for a background fork stopped via `Task.stop` on `LocalShell` (finding 2, read not run). If the reviewer wants
  the smaller change, the hole must be written into the guide instead.
- **Q4 — EE-94's snapshot and `onStop`.** `beforeGoal` (goal not persisted) and `onStop` fired. The first rests on the
  resume path as read from Javadoc; the second follows the turn and every other interrupted fork, but a blocked fork
  fires none.
- **Q5 — EE-93 (a) adds `InterruptCoordinator#isClosed()`** to a public interface and changes the value of a field
  that crosses nodes; (b) changes what `interrupt`, NOW-priority preemption and `close()` do in the window before
  `onStart`. Both are built as the conservative reading of "decide"; either can be dropped without touching the other.
- **Q6 — the summary call's length was not measured.** The entry asks for it first. The builder looks for a figure in
  the pressure-rig baseline (`docs/backlog/context-engine-pressure-rig-open-items.md`) and records it in the note, or
  records that none exists; the change does not depend on the number.
- **Q7 — EE-93's bookkeeping.** I keep EE-93 open, narrowed, rather than closing it and registering a new item for
  "a running slash command cannot be interrupted". If the backlog prefers a fresh id, that is a rename in one commit.

## 13. Where the implementation departed from this design

*Appended 2026-10-07, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Three sources feed this section: the run's `build/deviations.md`, the design review's
non-blocking notes, and what was found while building.*

**No outcome in §2 changed.** EE-97, EE-95 and EE-94 are built; EE-93 is split as §7 says — the flag tells the truth
and an interrupt before `onStart` is kept, while a running slash command is still not interruptible; EE-96 is not
built. `ShellActionRunner`, `ShellHookVerdicts`, the two shell executors, the hook contexts and `HookEventType` are
untouched, as §4 said. What departed is below.

### 13.1 Departures

- **DV-1 — `ignoresInterrupt()` is honoured on any event for a hook registered in code.** §4 has `awaitHook` honour
  the declaration and the declarative gate classes never make it, and is silent on a programmatic `preTool` /
  `onStart` hook that returns `true`. The review asked for that to be chosen. It is honoured — the executor does not
  know the event — and what it buys is stated where a hook author reads: the wait yields the hook's own verdict or a
  timeout, never a pass, and an interrupted execution waits for that guard for up to its budget. The review's other
  option, letting the policy decide per event, was not taken: `HookExecutionPolicy` carries no event today.
- **DV-2 — the gate check is evaluated when asked.** §4 writes `ignoreInterrupt = options.isIgnoreInterrupt() &&
  !canVeto()` as a field. `canVeto()` is overridable, so `AbstractDeclarativeShellHook` keeps the option and answers
  `ignoresInterrupt()` as `ignoreInterrupt && !canVeto()` each time, the way `getTimeoutBehavior()` reads `failOpen`.
  A side effect §9 does not list: the three hook classes for events outside any execution cannot veto, so one built by
  hand with the option declares `ignoresInterrupt()`. Both front-ends drop the key first, and those events have no
  signal to detach; left.
- **DV-3 — the `rejectedFailOpen` plumbing is shared inside `HookHandlerSpec`, and the public accessor has a sibling.**
  Two private helpers bind either flag and `HookConfigLoader` has one WARN for both. `getRejectedFailOpen()` stays and
  `getRejectedIgnoreInterrupt()` joins it; a generic accessor would have changed a public method for no reader.
- **DV-4 — frontmatter has two no-effect cases, not three.** It cannot declare an event outside any execution, so
  `SkillHookSetParser` warns for a guard event and for a non-shell action. `hooks.json` has all three.
- **DV-5 — the breakers' exemption is made safe in the engine.** §5 has both breakers exempt
  `LlmCallCancelledException`, and the review pointed out that a client throwing it while the signal is live would
  then never move a breaker. The guards exempt by type as designed; `DefaultCompactionEngine` hands back that
  exception only when the request's signal has tripped, and otherwise a plain `LlmClientException` wrapping it, which
  both breakers count. The engine holds the signal; the breakers' counting methods do not.
- **DV-6 — the EE-80 record got a new §11 instead of a line under §10.3.** §11 here says "one line under §10.3".
  [`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록) says an appended section is not rewritten either.
- **DV-7 — `DefaultLiveSessionStatusTest.turnBeforeLoopEntryIsIdleButInterruptible` did not change, and `HookContext`
  was not edited.** §10 expected the first to change; a turn in its `onStart` hooks is still interruptible, and the two
  states that did change (coordinator closed, coordinator not yet published) have tests of their own. §7 lists
  `HookContext.java` "where it describes the command turn"; that sentence is still true as written.

### 13.2 What was found while building

- **Reload has no per-hook diff to "see" the flag** (§9's "the builder confirms the reloader's diff sees it").
  `HookRegistryReloader` re-materialises every hook from the merged config and swaps them. A handler whose only change
  is `ignoreInterrupt` keeps its hook id, so its pending rewakes are kept, and the new instance carries the option.
  Read, not pinned by a test.
- **`HookHandlerSpec` tolerates unknown properties** (§4's "the builder checks"), as the review had already read. An
  older node ignores the key and stops the command as before; the CHANGELOG says so.
- **The request is the same with and without the token, apart from the transport** (§5's "the builder must show").
  Shown by a test in each provider module, as the review asked, not by a statement: Anthropic's two requests are equal;
  OpenAI Chat Completions' are equal once the streaming call's `stream_options` is added to the blocking one. The
  OpenAI Responses endpoint was not compared.
- **What an interrupted turn reports** (review). Over the blocking limit the guard answers `BLOCK` and the result has
  no compaction event. In the auto band it answers `COMPACT` with the failed attempt, and the turn's result carries
  one event before the loop's own call is short-circuited — what any failed compaction does. Both are pinned in
  `OrcaAgentExecutorHookCancellationTest`; whether the second is wanted is EE-100.
- **The summary call's length was not measured** (Q6), and the pressure-rig baseline has no figure for it.
- **Who consumes a NOW-priority input after the preempted turn** (review). `MessageQueueManager.enqueue` notifies
  synchronously, so the input addresses the turn active at that instant and cannot arrive late for a later one. The
  one changed case is a turn still being set up: it used to run, and its mid-turn drain could take the input into the
  turn it was meant to preempt; it now ends `INTERRUPTED` and the input stays queued for the CLI's post-turn drain, or
  for the next turn or the host elsewhere — as after a mid-loop preemption. No in-tree code enqueues at NOW priority.
- **A shared-instance `interruptCoordinatorFactory`** now hands a slash command an already-closed coordinator (EE-80's
  DV-8 had the command's close closing the turn's). The field's Javadoc says so; no in-tree supplier does this.
- **Fire-site tests are narrower than §10 in one place.** The background-fork test for `ignoreInterrupt` stops the task
  while its `subagentStop` command runs, not a fork's own `onStop`: `subagentStop` of a background fork fires on the
  worker `Task.stop` interrupts, so it takes both roads, and the code-behavior fork the test uses to get there fires no
  `onStop`. A ReAct fork's own `onStop` goes through the same `awaitHook` and hook class and was not run end to end.
  The hook-level cases are in `DeclarativeHookIgnoreInterruptTest`, a new class beside the one §10 names.
- **Two docs §11 did not list stated the old behaviour**: `docs/design/llm/cancellation.md` ("`onCancel` has no
  deregister", finding 8) and `docs/design/agent-execution/compaction.md` (the failure table). Both were corrected.
- **Nothing was run against a real remote shell, a real `LocalShell` for the new option, or a live provider.** The
  docker tier (`integrationTest`) was not run.

### 13.3 Review notes and open questions — where each one went

| Item | Outcome |
|---|---|
| Review: `ignoresInterrupt()` on a programmatic gate hook | DV-1, with an executor test. |
| Review: the context view on a public SPI | Stated on `ShellActionExecutor.run`, in the development guide and the CHANGELOG. |
| Review: exempt a cancellation only when the signal tripped | DV-5, tested. |
| Review: what the interrupted result's `compactionEvents` contain | Pinned (§13.2); **EE-100**. |
| Review: prove the `SystemPromptParts` wrap by test | Done in both provider modules (§13.2). |
| Review: NOW-priority input in the pre-`onStart` window | Traced and tested (§13.2); named in the CHANGELOG. |
| Review: the code-behavior fork still ends `BLOCKED` | Left, said in EE-94's closing note, and registered as **EE-99**. |
| Review: say "not measured" plainly | Done in EE-95's closing note. |
| Review: the stale `ActiveTurn` field comment | Corrected. |
| Review: a negative twin for the fork test | Added. |
| Q1 — the option's name | Built as `ignoreInterrupt`. **EE-98.** |
| Q2 — the default (bind a running report command, opt out) | Kept. Free to change only before the release that carries EE-80. **EE-98.** |
| Q3 — the thread road adds a public SPI method | Built; without it the option would not hold for a background fork on a local shell, which the fork test shows. |
| Q4 — the fork's snapshot and `onStop` | Built as designed: `beforeGoal`, and `onStop` fired. The resume path was read from Javadoc and the existing blocked-resume test, not run against a real store. |
| Q5 — `isClosed()` and the pre-`onStart` interrupt | Both built; either can be dropped without the other. Recorded in EE-93's note as the maintainer's call. |
| Q6 — the summary call's length | Not measured (§13.2). |
| Q7 — EE-93's bookkeeping | Kept open and narrowed, with its heading changed to what is left; no new id. |

Q1, Q2 and Q5 are the maintainer's calls; the defaults above are what was built.
