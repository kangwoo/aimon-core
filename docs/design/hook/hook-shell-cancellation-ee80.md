# EE-80 (remainder) — carry the execution's cancellation signal to the hook events that still lack it

> Status: **IMPLEMENTED** (2026-10-07) — `aimon-core`: the hook contexts under `hook/event/`,
> `CancellationSignals.liveOrEmpty`, `OrcaAgentExecutor` (the turn's coordinator is created before `onStart`),
> `DefaultSubagentExecutor`, `DefaultSubagentExecutionManager`, the four compaction request types and the engines that
> forward them, and `DefaultLiveSession.status()`. This is the design for the remainder of backlog item EE-80 in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md), which that
> change closed.
>
> **[§10](#10-where-the-implementation-departed-from-this-design), appended after the build, is where the
> implementation departs from this document.** Everything between this header and §10 is the body as approved in
> design review round 1 (PASS, no blocking findings, nine non-blocking notes), kept byte-exact rather than corrected —
> the house habit in this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its
> `file:line` citations are at `69862623`, as its first line says. The review transcript (`review-1.md`) and the run
> record the body names (`TASK.md`) are not in the repository; §10 says which review notes were taken. Three
> sentences of the body are known to overstate and are corrected in §10.1, not in place: "the pre-loop no-op is gone"
> (§5), "interrupt during the pre-loop window ends the turn" (§6) and "the same instant on every path" (§7).
>
> It is in English, like [`../tool/workflow-isolation-hardening.md`](../tool/workflow-isolation-hardening.md);
> `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1). What the hook system does
> today is in [`hook-system.md`](hook-system.md); the open questions of §9 that reach beyond this change went to the
> backlog as EE-93 – EE-97 (§10.3).
>
> **The report half of §3 is no longer what the code does.** EE-98 replaced "a report command is bound while the
> signal is live" with "a report command is never bound" before any of this was released —
> [§12](#12-what-ee-98-changed-the-report-rule).

Base: `69862623`. Paths are relative to `modules/aimon-core/src/main/java/at/aimon/core/` unless they start with
`docs/` or `modules/`.

## 1. Problem

Since `2f89df94`, `ShellActionRunner` ties a declarative hook's shell command to
`HookContext#getExecutionCancellation()`, so an interrupt stops the command through the shell's own cancellation
token instead of hoping the shell answers a thread interrupt. Only the fire sites that already held a signal pass one:
the four tool-scoped events and a fork's `onStart`. Six events still arrive empty — the main turn's `onStart`,
`onStop`, `preCompact`, `postCompact`, `subagentStart`, `subagentStop` — so on a shell that ignores thread interrupts
(a remote/sandbox shell) a hook command on those events runs to its own timeout after the user has interrupted. The
events are not alike: one is a gate that fires before the turn's signal exists, one fires when the execution is over
(often *because* it was cancelled), two report something that already happened, and some fire where no trippable
signal exists at all. Each needs its own answer.

## 2. What the code says today (findings the design rests on)

1. **The main turn's signal is born at loop entry, and so is interruptibility.** `OrcaAgentExecutor.execute()` calls
   `checkOnStartHooks` (≈`:1142`) and only then `executeReActLoop`, which creates the `InterruptCoordinator` (≈`:1514`)
   and publishes it through `getInterruptObserver()` (≈`:1525`). Until then `DefaultLiveSession.interrupt` is a
   debug-logged no-op (≈`:860-868`, "requested before loop entry — ignoring"). So during a main `onStart` hook an
   interrupt is not merely unbound from the command — it is **dropped**, and the turn runs on after the hook returns.
2. **`status()` derives RUNNING from the published coordinator *or* tracker** (`DefaultLiveSession` ≈`:441-443`) and
   documents IDLE for the pre-loop window and for a whole slash-command turn. Both handles are published on adjacent
   lines at loop entry, so today "coordinator published" ⇔ "tracker published".
3. **A slash-command turn has no trippable signal.** `executeCommand` mints its own coordinator (≈`:2026`) that
   "nothing trips today"; the command `ToolContext` carries no `InterruptToolKeys.CANCELLATION_SIGNAL`. Its `onStop`
   (≈`:1478`) and `/compact`'s compaction + `onStop` (`command/system/CompactCommand.java`) run there.
4. **Every `onStop` site of an execution still has its coordinator open.** Main: all `invokeOnStop` callers run inside
   `executeReActLoop`'s try-with-resources. Fork: three sites in `DefaultSubagentExecutor` (≈`:1102`, `:1144`,
   `:1237`) with `lc.coordinator` in hand. A code-behavior fork fires no `onStop`.
5. **Compaction requests carry the environment but no signal**, through four types:
   `ContextRequest` → `CompactionGuardRequest` → `CompactionRequest` / `SummaryRequest`. Builders:
   `OrcaAgentExecutor.contextRequest` (≈`:3130`), `DefaultSubagentExecutor.applyCompactionGate` (≈`:651`),
   `CompactCommand` (≈`:144`), `LlmSkillExecutor` (≈`:238`, compacts nothing); then `DefaultContextEngine` (≈`:185`,
   `:333`, `:541`), `RollingContextEngine` (≈`:560`), `DefaultCompactionGuard.invokeEngine` (≈`:422`). Hooks fire in
   `DefaultCompactionEngine` (`generateSummary` ≈`:355`; post at ≈`:240`, `:319`, `summaryInstalled`).
6. **`subagentStart` / `subagentStop` fire in the spawning execution's registry and environment**
   (`DefaultSubagentExecutionManager` ≈`:772`, `:790`), and the manager already holds the signal that governs the
   fork: `launchContext.getCancellationSignal()` (foreground) or the per-task coordinator's signal, which cascades
   from it and is also what `Task.stop` trips (background, ≈`:825-831`).
7. **The shipped "live only" rule is evaluated once per chain, at the fire site.**
   `SingleToolInvoker.liveCancellationOf` (≈`:283`) decides when the `PostToolContext` is built. With two `postTool`
   hooks run in sequence, an interrupt during the first hands the second the now-tripped signal and its command is
   never started — the exact outcome the rule exists to prevent, for chain position ≥ 2.
8. **A hook block does not count toward the AUTO-compaction circuit breaker**
   (`DefaultCompactionGuard.recordFailureIfTransient`), but a skipped compaction can still yield
   `ContextDecision.BLOCK`, which both executors turn into `ContextWindowExceededException`.
9. `DefaultHookExecutor.awaitHook` answers a *thread* interrupt on the firing thread with `future.cancel(true)` and a
   block. That is the thread-interrupt road; it does not trip the signal and is out of scope here (§8, Q6).

## 3. Approach

**One rule per kind of event, evaluated where the command starts; one signal per event, chosen by whose work the
hook is doing.**

Two kinds:

- **Gate** (`onStart`, `preCompact`, and the shipped `preTool` / `permissionRequest`): the hook is asked before
  something proceeds. The context carries the signal **always**. A command fired after the interrupt is not started,
  one running is stopped, and the result is `Unrun.CANCELLED`, which `ShellHookVerdicts` blocks regardless of
  `failOpen` (unchanged).
- **Report** (`onStop`, `postCompact`, `subagentStart`, `subagentStop`, and the shipped `postTool` /
  `permissionDenied`): the hook records something that already happened. The context carries the signal **only while
  it has not tripped**. A command running when the interrupt arrives is stopped; one that starts afterwards runs
  unbound, bounded by its timeout as before — so an audit/cleanup command on a cancelled execution always starts.

The report rule moves **into the context's getter**:

```java
// OnStopContext, PostCompactContext, SubagentStartContext, SubagentStopContext, PostToolContext, PermissionDeniedContext
@Override
public Optional<CancellationSignal> getExecutionCancellation() {
    return CancellationSignals.liveOrEmpty(executionCancellation);   // empty when null or already cancelled
}
```

The runner calls the getter once per command, at start, so the rule is applied per hook rather than per chain. Fire
sites pass the signal they hold and decide nothing; `SingleToolInvoker.liveCancellationOf` is deleted. This closes
finding 7 and keeps the six report events from each re-implementing the filter. `ShellActionRunner`,
`ShellHookVerdicts` and the two executors are **not changed**.

### Per-event decisions

| Event | Kind | Signal | Decision |
|---|---|---|---|
| `onStart`, main turn | gate | the turn's own, created before `onStart` | **Bind.** Needs the coordinator hoisted (below). |
| `onStop`, ReAct turn | report | the turn's | **Bind, live-only.** A cancelled turn's `onStop` starts unbound. |
| `onStop`, ReAct fork | report | the fork's (`lc.coordinator`) | **Bind, live-only.** |
| `onStop`, slash-command turn and `/compact` | report | — | **Not bound: no signal there can trip** (finding 3). Empty is the true answer, not a gap in plumbing. |
| `preCompact`, AUTO (turn or fork) | gate | the compacting execution's | **Bind.** Request types gain the field. |
| `postCompact`, AUTO | report | the compacting execution's | **Bind, live-only.** The transcript is already rewritten; the report must be able to start. |
| `preCompact` / `postCompact`, MANUAL (`/compact`) | — | — | **Not bound**, same reason as the command's `onStop`. |
| `preCompact`, rewake replay | — | — | **Not bound**: rebuilt outside the execution that fired it (already documented on `firesInsideExecution`). |
| `subagentStart` | report | the signal that governs the fork (finding 6) | **Bind, live-only.** Live-only keeps start/stop a balanced pair for an audit hook when the spawner is already cancelled. |
| `subagentStop` | report | same | **Bind, live-only.** A fork that ended *because* that signal tripped still gets its stop recorded. |
| `onSessionStart`, `onSessionEnd`, `onConfigReload` | — | — | **Not bound**: outside any execution. Unchanged. |

### The main turn's `onStart`: hoist the coordinator

`execute()` creates the turn's `InterruptCoordinator` before `checkOnStartHooks`, publishes it to the interrupt
observer there, keeps it on `ExecutionScope`, and `executeReActLoop` uses it instead of minting one. The budget
tracker is still published at loop entry. Consequences, each handled deliberately:

- `DefaultLiveSession.interrupt` now reaches a turn that is still in `onStart`. That is the point: without it there
  is nothing to bind the command to (finding 1).
- `status()` must keep reporting IDLE before loop entry and for a slash-command turn, so `turnActive` is derived from
  the **tracker alone**. By finding 2 this is the same answer as today in every state that exists today.
- After the `onStart` chain returns, `execute()` reads the signal once. **If it has tripped, the turn ends as
  `CompletionReason.INTERRUPTED`** via `handleInterrupted(scope, 0, TokenUsage.empty())` — before looking at blocks,
  for both the ReAct and the command flow. Not as `ExecutionBlockedByHookException`: the user stopped the turn, no
  guard refused the input; an interrupted turn keeps its retry mark (`finalisedAsInterrupted`) where a blocked one
  does not; and the outcome must not depend on whether the hook that was running happened to be a shell guard
  (blocks) or a programmatic hook (would fall through to the loop's first checkpoint). Hook-level semantics are
  untouched — the cancelled guard still returns BLOCKED.
- The command flow keeps its own untripped coordinator. A slash command stays uninterruptible after `onStart`;
  an interrupt then trips a signal nothing reads, which is today's "ignored" by another route.

### Compaction

`ContextRequest`, `CompactionGuardRequest`, `CompactionRequest`, `SummaryRequest` each gain an optional
`executionCancellation`, forwarded verbatim at every hop in finding 5; `DefaultCompactionEngine` puts it on
`PreCompactContext` / `PostCompactContext`. `OrcaAgentExecutor.contextRequest` and
`DefaultSubagentExecutor.applyCompactionGate` supply their coordinator's signal; `CompactCommand` and
`LlmSkillExecutor` supply none.

A cancelled AUTO `preCompact` blocks, the compaction is skipped, and if the view is over the blocking limit the engine
answers `BLOCK`. Both executors therefore check the signal in their `BLOCK` branch and finish as interrupted/cancelled
instead of throwing `ContextWindowExceededException` — a window error caused by the interrupt is the interrupt.

## 4. Alternatives rejected

| Alternative | Why not |
|---|---|
| Pass the signal on every event, always | A cancelled execution's `onStop` / `subagentStop` / `postCompact` command would never start. The acceptance criteria name this failure explicitly. |
| Keep the live-only filter at each fire site (`liveCancellationOf` × 6) | Evaluated per chain, so it already fails for the second hook (finding 7); and six copies of one rule drift. |
| Put the gate/report switch in `ShellActionRunner` (a `startEvenIfCancelled` flag) | The runner would need the event's kind threaded through both executors; the context type already *is* the event. Programmatic hooks reading the getter would also see a different answer from the shell path. |
| Leave main `onStart` unbound and record "fires before the signal exists" | True, but it restates the backlog entry rather than resolving it, and it leaves finding 1 (the interrupt is dropped) in place. Kept as the fallback if Q1 is answered "no". |
| A separate pre-loop signal just for `onStart`, cascaded into the loop's | Two signals for one turn, with `LiveSession.interrupt` having to know which is current. The scope model says one execution, one signal. |
| End an interrupted `onStart` as "blocked by hook" (what a fork does today) | See the bullet above: wrong cause shown to the user, loses the retry mark, outcome varies by hook kind. |
| Reuse the hoisted coordinator for the command flow too | Makes slash commands interruptible — inline-skill tools would start seeing a tripped signal. A real decision of its own, not a side effect of this item. |
| Add the signal to `ContextCaller` | That type is identity (who is asking). A cancellation signal is not identity. |
| Carry the signal on `ToolContext`/a thread-local and let the engine look it up | The compaction engine has no `ToolContext`; a thread-local is hidden coupling the request types exist to avoid. |

## 5. Changes by file

**`agent/interrupt/CancellationSignals.java`** — add `liveOrEmpty(CancellationSignal)` (null or cancelled → empty).

**`hook/event/`**
- `OnStopContext`, `PreCompactContext`, `PostCompactContext`, `SubagentStartContext`, `SubagentStopContext`: add the
  `executionCancellation` field, builder setter, getter override, and carry it through any copy/`toBuilder` path
  (as `PreToolContext` does). Gate context returns it as is; report contexts through `liveOrEmpty`.
- `PostToolContext`, `PermissionDeniedContext`: getter switches to `liveOrEmpty`.

**`hook/execution/HookContext.java`** — rewrite the `getExecutionCancellation()` Javadoc: the two kinds, the list of
events, and the cases that stay empty with their reason.

**`toolinvocation/SingleToolInvoker.java`** — `postTool` / `permissionDenied` pass `cancellationOf(spec)`; delete
`liveCancellationOf`.

**`agent/impl/orca/OrcaAgentExecutor.java`**
- `execute()`: create the coordinator (try-with-resources around the existing try/finally body), store on
  `ExecutionScope`, publish to the interrupt observer; after `checkOnStartHooks`' chain, the tripped-signal check.
- `invokeOnStart` / `invokeOnStop`: pass the scope's signal (command-flow `onStop` passes none — see Q4 for how).
- `executeReActLoop`: use the scope's coordinator; drop its own creation and publication; keep the budget publication.
- `contextRequest(...)`: add the signal. `BLOCK` branch: tripped signal → `handleInterrupted`.
- `agent/impl/orca/OrcaAgentExecutionRequest.java`: Javadoc of the interrupt observer ("published before `onStart`").

**`agent/session/DefaultLiveSession.java`** — `status()` phase from the tracker only; reword the `activeTurn` and
`interrupt(...)` comments (the pre-loop no-op is gone; the slash-command case now reads "trips a signal only its
`onStart` reads").

**`subagent/execution/DefaultSubagentExecutor.java`** — three `OnStopContext` builders and `applyCompactionGate`
pass `lc.coordinator.getSignal()`; `BLOCK` branch honours a tripped signal with the fork's existing cancelled result.

**`subagent/DefaultSubagentExecutionManager.java`** — `fireSubagentStart` / `fireSubagentStop` take the governing
signal: `runExecute` / `runResolvedSubagent` pass their `cancellationSignal` parameter; `executeInBackground` passes
the task coordinator's signal (start, and the pool-rejection stop).

**`agent/context/ContextRequest.java`, `agent/compact/{CompactionGuardRequest,CompactionRequest,SummaryRequest}.java`**
— optional field + builder + getter. **`agent/context/{DefaultContextEngine,RollingContextEngine}.java`,
`agent/compact/DefaultCompactionGuard.java`** — forward it. **`agent/compact/DefaultCompactionEngine.java`** — set it
on the pre/post contexts (`generateSummary` gains a parameter or takes the request).

**Not changed:** `skill/hook/declarative/*` (runner, verdicts, executors, the six `Declarative*Hook` classes),
`command/system/CompactCommand.java`, `skill/execution/llm/LlmSkillExecutor.java`, `HookEventType`.

**Docs (same change)**
- `docs/backlog/execution-environment-open-items.md` › EE-80: status and a dated note — what closed, and the
  not-bound cases with the reasons from §3's table (Q5 on the status word).
- `docs/design/hook/hook-system.md` › "취소는 '돌리지 못함' 이 아니다(EE-80)": replace the last two sentences with the
  gate/report rule and where it is evaluated.
- `docs/features/hook/hook-config-guide.md` + `.en.md` › "인터럽트" paragraph (≈`:588-594`): new event list; say that
  an interrupt arriving while an `onStop` / `subagentStop` / `postCompact` command runs stops it, and that one arriving
  during a main `onStart` now ends the turn as interrupted. The "가드가 막는 경우" table row needs no change.
- `docs/features/hook/hook-development-guide.md` + `.en.md` if it describes `getExecutionCancellation()`.
- `CHANGELOG.md`: extend the EE-80 bullet (≈`:337`) and the cross-reference at ≈`:511`; add the embedder-visible
  items — interrupt before loop entry is honoured, the interrupt observer fires before `onStart`, four request types
  gained an optional field.
- `.claude/rules/hook-development.md`: the "A hook command is tied to the execution's cancellation signal" bullet.
- Check `docs/overview/scope-model.md` / `glossary.md` for a statement that the turn's signal is created at loop entry.
- Run `check-translation-staleness.py`, `check-translation-structure.py`, `check-doc-links.py`.

## 6. Data and interface shapes

Additive, no wire format touched (none of these types is serialized):

```java
// 5 hook contexts
Builder executionCancellation(CancellationSignal signal);          // nullable
Optional<CancellationSignal> getExecutionCancellation();            // HookContext default overridden

// ContextRequest, CompactionGuardRequest, CompactionRequest, SummaryRequest
Builder executionCancellation(CancellationSignal signal);          // nullable
Optional<CancellationSignal> getExecutionCancellation();

// CancellationSignals
static Optional<CancellationSignal> liveOrEmpty(CancellationSignal signalOrNull);
```

Behavioural contract changes:

- `PostToolContext` / `PermissionDeniedContext#getExecutionCancellation()` can turn from present to empty over the
  life of one context object (it tracks the signal). Previously fixed at construction.
- `OrcaAgentExecutionRequest#getInterruptObserver()` is called before `onStart`, once per turn — now also for a
  slash-command turn, which never published one.
- `LiveSession.interrupt` during the pre-loop window ends the turn (`INTERRUPTED`, `onStop(success=false)` fired,
  `ExecutionCompleted` emitted with iteration 0) instead of being ignored.
- A custom `ContextEngine` / `CompactionEngine` that builds its own downstream requests must forward the new field or
  its compaction hooks stay unbound — silent, and equal to today's behaviour.

## 7. Failure modes

| Failure | Handling |
|---|---|
| Listener left on a long-lived signal | Unchanged: the runner removes its registration in `finally`. A background fork's `subagentStop` registers on the task signal for the length of one command only. |
| Signal trips between the report getter's check and the runner's `onCancel` | The listener runs at once, the shell sees a cancelled token and does not start the command; on a report event that is logged and the event proceeds. A window of one method call; accepted, stated in the Javadoc. |
| `NoopCancellationSignal` (a runtime-level runner spawned the fork) | Present but inert: never trips, `onCancel` registers nothing. No special case. |
| Shell without `ShellFeature.CANCELLATION` | Ignores the token; behaves as today. Same as the shipped events. |
| Cancelled AUTO `preCompact` → compaction skipped → `BLOCK` | Executors read the signal in the `BLOCK` branch and end as interrupted (§3). Builder to confirm the rolling engine does not count a `CompactionBlockedByHookException` toward its breaker either; the in-place guard already exempts it. |
| MANUAL `preCompact` block is downgraded to a warning and compaction proceeds | Not reachable with a tripped signal: MANUAL carries none. |
| Interrupt lands during `onStart`, a hook also vetoed (exit 2) | Interrupt wins; the veto reason is logged, not thrown. Either way the turn does not proceed. |
| Interrupt lands during `onStart` of a slash-command turn | Turn ends `INTERRUPTED` before the command runs. Without the check the command would run with its guard unanswered. |
| Advisory `onStart` feedback collected, then interrupted | Dropped — nothing will read it. |
| Hoisted coordinator leaked on an early throw | try-with-resources in `execute()`; the loop no longer closes it. Terminator registrars are dropped at turn end rather than loop end, which is the same instant on every path that enters the loop. |
| `status()` flips to RUNNING during `onStart` / a slash command | Prevented by keying the phase on the tracker; pinned by a test. |
| Interrupt during an `onStop` command on a **local** shell now stops it | Intended and new for every shell (before, nothing delivered the interrupt there). Documented for hook authors; see Q3. |
| An interrupt after `onStop` started for a *finished* turn marks nothing | The turn result is already decided; only the command is stopped. `handle*` callers do not re-read the signal after `invokeOnStop`. Builder to confirm no post-`onStop` checkpoint reclassifies a completed turn. |

## 8. Test strategy

All new cancellation tests use a shell whose blocking call ignores thread interrupts and honours only
`ExecutionOptions.getCancellation()` — `DeclarativeHookCancellationTest.cancellationOnlyShell()` /
`BashToolInterruptTest`'s style — and assert the command stopped well inside its runtime. Each must fail on the base
commit.

**Hook level** (extend `DeclarativeHookCancellationTest`, one case per newly covered event):
- `onStop`, `postCompact`, `subagentStart`, `subagentStop`: (a) interrupt while running → stopped promptly, result
  SUCCESS; (b) signal already tripped → the command **runs to completion** and its options carry no cancelled token.
- `preCompact`: (a) interrupt while running → stopped, BLOCKED, for `failOpen` true and false; (b) already tripped →
  never started, BLOCKED.
- `postTool` chain: two hooks in sequence, interrupt during the first → the second still runs (fails today, finding 7).

**Context level**: for each of the six report contexts, the getter is present while live and empty once tripped; the
gate contexts stay present.

**Fire sites** (the signal actually arrives — a recording hook asserts identity of the signal it was handed):
- `OrcaAgentExecutor`: main `onStart` and `onStop` carry the turn's signal; an interrupt requested through the
  observer during a blocking `onStart` shell hook ends the turn `INTERRUPTED`, stops the command, fires `onStop`, makes
  no LLM call; same for a slash-command turn (command not executed); command-flow `onStop` carries none.
- `DefaultSubagentExecutor`: the three `onStop` sites; a cancelled fork's `onStop` command still runs.
- `DefaultSubagentExecutionManager`: start/stop for foreground and background; `Task.stop` during a background fork →
  `subagentStop` command runs; spawner interrupt during a `subagentStart` command stops it.
- Compaction: signal reaches `PreCompactContext` / `PostCompactContext` through `DefaultContextEngine` (guard path and
  `summarize` path) and `RollingContextEngine`; AUTO compaction with a tripped signal over the blocking limit ends the
  turn interrupted, not `ContextWindowExceededException`; `/compact` contexts carry none.
- `DefaultLiveSession`: `status()` is IDLE during `onStart` and during a slash-command turn with the coordinator
  already published; `interrupt` before loop entry reaches the turn.

**Regression** (criterion 2): the existing `DeclarativeHookCancellationTest`, `DeclarativeShellHookBindingTest`,
`ShellActionRunnerTest`, `SingleToolInvokerTest` EE-80 cases pass unchanged, except assertions that pin *where* the
live rule is applied (`SingleToolInvokerTest` may assert the context is built without a signal after cancel — it
becomes "the getter answers empty"). Existing tests that pin "interrupt before loop entry is ignored" are expected to
change; each such edit should be listed in the handoff.

`./gradlew checkAll` (ArchUnit: `agent.compact` / `agent.context` / `hook.event` → `agent.interrupt` are all inside or
already-used edges; confirm).

## 9. Open questions

- **Q1 — Is honouring an interrupt before loop entry acceptable in this change?** It is an embedder-visible change to
  `LiveSession.interrupt` (and to NOW-priority preemption and `close()`, which share the path). I chose yes: it is the
  only way to give a main `onStart` a signal that can trip. **Fallback if no:** leave main `onStart` unbound, record
  "no interrupt is delivered before loop entry, so there is no signal to bind" in the EE-80 note, and drop the
  `execute()` / `DefaultLiveSession` / observer changes; everything else in this design stands unchanged.
- **Q2 — Fork asymmetry.** A fork interrupted during `onStart` ends `BLOCKED` with an "execution cancelled" reason
  (shipped); under this design a main turn ends `INTERRUPTED`. I left the fork alone to avoid changing shipped
  results. Aligning it (signal check after `SubagentOnStartGate.check` → the fork's cancelled result) is small and
  arguably right; needs a yes.
- **Q3 — `onStop` while live.** An interrupt that arrives while an `onStop` command is running stops that command, on
  every shell. The alternative is to never bind `onStop` ("cleanup always finishes"), leaving it bounded by its
  timeout only. I chose to bind: the task's stated concern is a tripped signal preventing the command from
  *starting*, which live-only covers, and it matches the shipped `postTool` rule. A hook author with a
  must-finish cleanup has no opt-out today.
- **Q4 — How the command flow's `onStop` stays empty once the scope holds a coordinator.** Either `invokeOnStop` takes
  the signal as a parameter and `executeCommandFlow` passes none, or it passes the (never-read) hoisted signal and the
  docs say "inert there". I prefer the explicit none; it keeps "empty means nothing can trip" true.
- **Q5 — Backlog status.** After this change nothing remains that is "a signal exists and is not carried". What
  remains — slash-command turns, `/compact`, rewake replay, events outside an execution — has no signal. I would mark
  EE-80 closed with those listed as by-design, and, if the backlog's conventions want it tracked, register "slash
  commands are not interruptible" as its own item rather than keeping EE-80 open for it. The user's call.
- **Q6 — Out of scope, observed, not designed here:** (a) the compaction *summary LLM call* is not tied to the signal
  either, so an interrupt during AUTO compaction waits for that call; (b) an interrupt that reaches the firing thread
  only as `Thread.interrupt` cancels the hook's pool task but does not stop a command on an interrupt-deaf shell,
  on any event. Both look like new backlog items; neither is "which signal does this hook event carry".
- **Q7 — Fixing finding 7 here.** It changes shipped behaviour in the direction its own comment promises, and it is
  what makes the getter the single home of the rule. If the reviewer wants it split out, the report rule would have
  to live at six fire sites in the meantime, which I would not recommend.

## 10. Where the implementation departed from this design

*Appended 2026-10-07, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Three sources feed this section: the run's `build/deviations.md`, the design review's
non-blocking notes, and what was found while building.*

**No decision in §3 changed.** The gate / report split, the rule living in the context's getter, one signal per event
chosen by whose work the hook does, the hoisted coordinator, and `INTERRUPTED` rather than "blocked by hook" for a
main turn interrupted in `onStart` are all as approved. `ShellActionRunner`, `ShellHookVerdicts`, the two shell
executors, the six `Declarative*Hook` classes, `CompactCommand`, `LlmSkillExecutor` and `HookEventType` are untouched,
as §5 said. What departed is below.

### 10.1 Departures

- **DV-1 — the turn is reachable from `onStart` on, not "before loop entry".** §5 and §6 say the pre-loop no-op is
  gone and that an interrupt during the pre-loop window ends the turn. The review pointed out that `execute()` renders
  the prompt, assembles context and opens the transcript before `checkOnStartHooks`. The coordinator is created and
  published immediately before the `onStart` chain, not at the top of `execute()`: before that point there is no
  `ExecutionScope`, no transcript and no event dispatcher, so there is nothing to finalise an interrupted turn with.
  An interrupt in that earlier stretch is still dropped, and the debug-logged branch in `DefaultLiveSession.interrupt`
  stays, reworded ("requested before its OnStart hooks"). The Javadoc, the hook guide and the CHANGELOG say "from
  `onStart` on". The remaining window is registered as part of EE-93.
- **DV-2 — the coordinator is closed before the turn is persisted.** §5 put a try-with-resources *around* the existing
  try/finally, and §7 claimed the registrars are then dropped at "the same instant on every path that enters the
  loop". The review showed that is wrong: the coordinator would have stayed open through `saveSilently` and the memory
  feed, so an interrupt there — which used to land on a closed coordinator — would trip the signal and cascade into
  background forks. The try-with-resources sits *inside* the existing `try` instead, in a helper (`runTurn`: the
  `onStart` check, then the command flow or the loop), so the coordinator is closed when the flow returns, before the
  `finally` persists the turn — as it was when the loop owned it. The window in which an interrupt lands grew at the
  front (the `onStart` hooks) and not at the back. The helper exists for a second reason: with the coordinator and the
  three-way dispatch inline, `execute()` went past Checkstyle's 150-line method limit.
- **DV-3 — `LiveSessionStatus#isInterruptible` is documented, not changed.** The design re-keyed `phase` on the tracker
  and did not mention `interruptible(coordinator != null)` on the same statement. With the coordinator published
  before `onStart`, a turn is `IDLE` and interruptible at once while its `onStart` hooks run — which is true, an
  interrupt ends it — and a slash-command turn keeps reporting `interruptible=true` after them, where the interrupt is
  inert. The review offered two answers; clearing the published handle when the command flow starts was not possible
  without widening the observer (`Consumer<InterruptCoordinator>`, called exactly once, with no way to retract). So
  the flag's Javadoc and `DefaultLiveSession.status()` now say it means "a coordinator exists", and
  `DefaultLiveSessionStatusTest.turnBeforeLoopEntryIsIdleButInterruptible` pins it. Making the flag honest for a
  command turn is the same work as making a command interruptible, and is EE-93.
- **DV-4 — the fork's `BLOCK` branch unwinds through the signal's own checkpoint.** §5 said the branch "honours a
  tripped signal with the fork's existing cancelled result" without a route; `applyCompactionGate` returns a
  `ContextView` and can only signal by throwing. The branch calls `lc.coordinator.getSignal().checkpoint()` first: on
  a cancelled fork it throws `CancelledExecutionException`, which the loop's existing catch turns into
  `createInterruptedResult`. No return shape changed.
- **DV-5 — Q4 was answered as the design preferred: an explicit none.** `invokeOnStop` gained an overload that takes
  the signal; the loop's callers go through the old signature and get the turn's, and `executeCommandFlow` passes
  `null`. "Empty means nothing can trip" stays true for the command flow's `onStop`.
- **DV-6 — the main loop's `BLOCK` branch reads the signal, not the checkpoint.** `isInterrupted(coordinator)` would
  also consume a thread interrupt and promote it. The branch asks only whether the interrupt caused this block, so it
  reads `cancellationSignal.isCancelled()`; a thread flag is left for the paths that already own it.
- **DV-7 — `checkOnStartHooks` reports the interrupt itself.** It returns `false` when the signal tripped during the
  chain (logging any block reasons at debug) instead of `execute()` re-reading the signal afterwards. `runTurn` then
  takes the `handleInterrupted(scope, 0, TokenUsage.empty())` branch and the result leaves through the ordinary
  return path — `withCostSummary`, `recordTurnOutcome`, `turnResult` — so the `finally` sees `INTERRUPTED` and keeps
  the retry mark. The test asserts the rewind point survives, and that a completed turn leaves none.
- **DV-8 — a slash-command turn now calls `interruptCoordinatorFactory` twice.** Once for the turn's coordinator and
  once for the one `executeCommand` hands the command's tools. The field's Javadoc says so, and says what a supplier
  returning one shared instance will see; the stale "mints its own at line ~1415" comment is gone.

### 10.2 What was found while building

- **No existing test pinned "an interrupt before loop entry is ignored".** §8 expected such tests to change. The whole
  `aimon-core` suite passed against the main-source change before any test was touched. One test helper changed
  meaning: `DefaultLiveSessionStatusTest`'s stub executor used to model a slash-command turn as publishing nothing
  (`withoutObservers`); it now publishes the coordinator and withholds the tracker (`withoutTracker`), which is what
  the executor does.
- **A fork cancelled during its final LLM call still ends `COMPLETED`.** The fork's loop has no checkpoint between a
  final answer and the result. Its `onStop` then fires with a tripped signal and, by the report rule, runs unbound.
  That is the rule working, but the outcome is older than this change and was not designed here.
- **`subagentStart` with a spawner that is already cancelled** (review note): the fork is still launched —
  `runExecute` calls `runResolvedSubagent` unconditionally, and the fork ends at its own first checkpoint — so start
  and stop stay a pair and the event was kept live-only as designed. Read from the code at first; the review
  follow-up pinned it for a foreground and a background fork.
- **Fire-site tests are narrower than §8 in two places.** `Task.stop` during a background fork is asserted on the
  context the hook manager receives (the getter is empty when `subagentStop` fires), not by running a shell command
  through it; and for a fork, "a `BLOCK` after an interrupt is the interrupt" drives the executor with a scripted
  `ContextEngine`, while the premise — a cancelled `preCompact` guard over the blocking limit yields `BLOCK` — is
  tested separately against the real guard and engine. The review follow-up joined the other two end to end: a
  spawner interrupt during a background fork's `subagentStart` command, and a main turn interrupted during an AUTO
  `preCompact` command, each run a shell command through the real hook manager (and, for the turn, the real guard and
  engine). The same follow-up replaced every "returned within half the command's runtime" assertion with the fake
  shell recording that its command's cancellation tripped.
- **The pool-rejection `subagentStop` was handed a signal that could no longer trip** (review follow-up). §5 passes
  the task coordinator's signal there, and the build did — after closing the coordinator and deregistering the
  cascade from the spawner's signal, so a spawner interrupt did not reach the command. The stop now fires before both,
  as it does on the path that ran.
- **The rolling engine's breaker** (§7's "builder to confirm") was confirmed by the review, not re-measured here.

### 10.3 Review notes and open questions — where each one went

| Item | Outcome |
|---|---|
| Review: `interruptible` | DV-3. |
| Review: "pre-loop no-op is gone" overstates | DV-1. |
| Review: coordinator open through the persist | DV-2. |
| Review: fork `BLOCK` needs a route | DV-4. |
| Review: the pre-loop result takes the normal return path | DV-7, asserted. |
| Review: factory Javadoc and the stale comment | DV-8. |
| Review: `subagentStart` when the spawner is cancelled | Kept; §10.2. |
| Review: widen the doc sweep | `docs/design/tool/execution-environment-ee70-ee71-fail-closed.md` is itself a byte-exact record, and its line ("block 검사가 루프 진입 전이다") is still true — the block check precedes the loop. `docs/backlog/interrupt-open-items.md`, `docs/overview/scope-model.md` and `glossary.md` say nothing about where the turn's signal is created. `docs/design/hook/hook-system.md` has no translation. |
| Q1 — honour an interrupt before loop entry | Default kept (yes), narrowed by DV-1. It also applies to NOW-priority preemption and `close()`, which share the path. **The fallback stands if the answer is no:** leave the main `onStart` unbound, record "no interrupt is delivered before loop entry, so there is no signal to bind", and drop the `execute()` / `DefaultLiveSession` / observer changes; the rest of this design does not depend on them. |
| Q2 — fork asymmetry | Not changed: a fork interrupted in `onStart` still ends `BLOCKED`. **EE-94.** |
| Q3 — an interrupt stops a running `onStop` command | Default kept (bind, live-only). The missing opt-out is **EE-97.** |
| Q4 — the command flow's `onStop` | DV-5. |
| Q5 — backlog status | Default kept: EE-80 is closed, with the unbound cases listed as by-design in its closing note, and "a slash command is not interruptible" is its own item, **EE-93.** |
| Q6 (a) — the compaction summary LLM call | **EE-95.** |
| Q6 (b) — a cancellation that arrives only as a thread interrupt | **EE-96.** |
| Q7 — fixing finding 7 here | Done here, as designed. |

Q1, Q3 and Q5 are the maintainer's calls; the defaults above are what was built. Open or closed is decided in the
backlog, not here.

## 11. What became of EE-93 – EE-97

*Appended 2026-10-07, by the change that took up the five items §10.3 sent to the backlog. §10 above is as it was
written.*

All five were given an outcome in one change: EE-94 (the fork asymmetry of Q2), EE-95 (the summary call of Q6 (a)) and
EE-97 (the opt-out Q3 lacked) were built and closed; EE-93 was built in part — `isInterruptible` no longer says `true`
for a running command, and the window DV-1 left ("an interrupt in that earlier stretch is still dropped") is closed —
and stays open for "a running slash command cannot be interrupted"; EE-96 (Q6 (b)) was decided against for now. Two
sentences of §10 are therefore no longer true of the code: DV-1's "still dropped" and DV-3's "a slash-command turn
keeps reporting `interruptible=true`". The design and its own departures are in
[`hook-cancellation-followups-ee93-ee97.md`](hook-cancellation-followups-ee93-ee97.md).

## 12. What EE-98 changed: the report rule

*Appended 2026-10-08, by the change that closed EE-98. §10 and §11 above are as they were written.*

§3's split into gates and reports stands, and so does everything about gates. The report half of the rule does not.
§3 bound a report event's command to the signal "while it has not tripped": a command running when the interrupt
arrived was stopped, one that started afterwards ran to completion. EE-97 then added a per-hook way out
(`ignoreInterrupt`). EE-98 removed both before either was released. **A declarative hook's shell command on a report
event — `onStop`, `postCompact`, `subagentStart`, `subagentStop`, `postTool`, `permissionDenied` — is handed no signal
and is never stopped by the execution's interrupt**; it runs until it finishes or its own timeout ends it. The rule is
one sentence now: gate commands always stop on an interrupt, report commands never do.

Why, in the order the maintainer weighed it:

- **Whether a cleanup finished depended on a race.** The same `onStop` command was cut in half if it had started just
  before the interrupt and ran to its end if it started just after. Q3 of §9 chose the default knowing the second half;
  it did not name the two halves as one race.
- **The two failures are not alike.** A half-done cleanup is silent — nothing reports it. A command that runs on is a
  delay that is bounded by its timeout and visible to whoever is waiting.
- **For a turn's `onStop` it is what was released.** Before this document's change nothing delivered an interrupt to
  that command, so it ran to its end. Stopping it was the new behaviour; not stopping it breaks nobody.

What it costs is the part of §1 that the report events were bound for: an interrupted execution — and the input queued
behind it — waits for a report command for up to that command's remaining timeout. That holds on every shell, not
only one that ignores thread interrupts, because the thread road is closed as well. The guide tells authors to keep
report hooks' timeouts short. An opt-in the other way ("stop this report command on an interrupt") was not added; it
is the thing to add if someone asks, and is registered as EE-101.

One released behaviour does change, and it is on the thread road, not the signal's. Since before EE-80,
`DefaultHookExecutor` answered an interrupt of the firing thread by cancelling the hook's task, and a shell that
answers thread interrupts (`LocalShell`) then stopped the command. `Task.stop` interrupts a background fork's worker,
so a fork's `onStop` or `subagentStop` command running on a local shell was cut short by it. It now runs to its end
and the stopped task finishes that much later. This was read from the released executor (`v0.3.1`), not run against
it.

What this does to the statements above:

- **§3, "Report … bound, live-only", and the per-event table's "Bind, live-only" rows** describe the context getter
  only. The getter is unchanged — a report context still answers the signal until it trips
  (`CancellationSignals.liveOrEmpty`) — and that answer is now for hooks written in code, which decide for themselves
  what to tie to it. The declarative shell hooks do not pass it on: on a report event they hand their
  `ShellActionExecutor` a view of the context whose `getExecutionCancellation()` is empty
  (`SignalDetachedHookContext`, from EE-97), and they declare `ExecutionHook#ignoresInterrupt()` so that a thread
  interrupt of the firing thread does not cancel them either. Both follow from the event, not from a setting.
- **§3's reason for putting the rule in the getter** ("the second hook of a chain") still holds for a hook written in
  code. For shell commands the question no longer arises.
- **§10's "the six `Declarative*Hook` classes … are untouched"** stopped being true with EE-97 and is less true now:
  `AbstractDeclarativeShellHook` and `DeclarativePostToolHook` are where the report rule lives.
- **§10.2, "Its `onStop` then fires with a tripped signal and, by the report rule, runs unbound"** is now simply how
  every `onStop` command runs. **"The pool-rejection `subagentStop` was handed a signal that could no longer trip"**:
  the reordering stays — the context a programmatic hook reads there carries a signal that can still trip — but the
  shell command it was found on no longer follows that signal, and the test that pinned it now asserts the command
  runs to its end.
- **§10.3's Q3 row** ("Default kept (bind, live-only). The missing opt-out is EE-97") is reversed: not bound, and no
  opt-out to miss.
- **The first half of EE-80** stopped a running `postTool` / `permissionDenied` command. That is gone with the rest.

Tests: `DeclarativeReportHookInterruptTest` holds the rule for all six report events on both roads (the signal; a
thread interrupt through `DefaultHookExecutor`, on a shell that answers thread interrupts), with the four gate events
as the other half; `DeclarativeHookCancellationTest` keeps the gate cases and "a report command fired after the
cancellation still starts". Each flipped test was run against the old rule restored and failed there.
