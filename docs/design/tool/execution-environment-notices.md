# Design — environment notices on every tool result (issue #254)

> Status: **IMPLEMENTED** (2026-10-17). This is the design for issue #254 exactly as it was approved in design review
> (one round, passed with six non-blocking notes). The body below (§1–§7) is unchanged, including its open-question
> numbers. It is written in English because that is how it was reviewed; the `design/` directory is not a translation
> target (`docs/project/documentation-guide.md` §5.1).
>
> One section was added: §8 records where the implementation departed from this design, and why — including which
> review notes were applied. Note in particular §8.1: the helper lives in `at.aimon.core.tools`, not
> `at.aimon.core.toolinvocation`. The open questions in §7 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-106 ← Q6,
> EE-107 ← Q2 · Q3 and the review's fork / slash-skill notes, EE-108 ← Q4), which is the canonical open/closed list.
> The spec this extends is [`execution-environment.md`](execution-environment.md) §8.1 and §13.

## 1. Problem

The only channel an `ExecutionEnvironment` has to tell the model something is `ShellCommandResult.notices()` (and the
same list on `ShellExecutionException` / `ShellTimeoutException`, and `BackgroundBashTask.takeNotices()` for
`BashOutput`). `BashTool` renders them as `[environment] …` lines ahead of the output
(`BashTool.ENVIRONMENT_NOTICE_PREFIX`, `BashTool.renderNotices`). File tools (`Read`, `Write`, `Edit`, `Grep` — via
`VirtualFileSystem` / `ContentSearch`) have no such channel, and nothing delivers at turn start. Environment-level facts
("the sandbox was re-created and `/workspace` is empty", "provisioning took 94 s", "this session now runs in another
workspace") are not about any shell command, so aimon-sandbox queues them (`PendingNotices`) and can only drain them into
the next `Bash` result. A turn that only reads and edits files never hears them, and the sandbox must defer its own
bookkeeping (`lastWorkspaceId`) until a `Bash` result has actually carried the notice — so a file-only turn after a reset
edits an empty tree without being told, and the next turn repeats the notice. We need one environment-level notice path
that every model-facing tool result can carry: one-shot, never into hook results, and a no-op for environments that never
produce notices.

## 2. Approach

### 2.1 Chosen: `ExecutionEnvironment.drainNotices()` drained by `SingleToolInvoker` after every dispatch

1. **SPI.** Add one default method to `at.aimon.core.environment.ExecutionEnvironment`:

   ```java
   /**
    * Removes and returns the facts this environment has queued for the model ... Called by the core once after
    * each model-facing tool call, whichever tool it was; each notice must be returned by exactly one call.
    */
   default List<String> drainNotices() {
       return List.of();
   }
   ```

   No implementation in the core overrides it (`LocalExecutionEnvironment`, `LocalIsolatedEnvironment`,
   `UnavailableExecutionEnvironment` keep the default), so local and unavailable environments are unchanged.

2. **Delivery point: `at.aimon.core.toolinvocation.SingleToolInvoker.invoke`**, not `DefaultToolExecutor`. After the
   result is final — after `applyPostToolHooks` and alongside `attachFeedback` — the invoker reads the environment from
   `spec.getToolContext()` (the existing `environmentOf(spec)`), calls `drainNotices()` once, and prepends the notices to
   the result as `[environment] <notice>` lines (same prefix and shape as `Bash`). It does so on **every exit path** that
   returns a `ToolUseResult` to the model: success, tool error, unknown tool, permission deny, approval deny, PreTool
   block, and the catch-all `Tool execution error`. One `try/finally`-style helper applied to the returned value, like
   `attachFeedback`, keeps this from being forgotten on one branch.

3. **Shared rendering.** A small public final helper in `at.aimon.core.toolinvocation` (working name
   `EnvironmentNotices`) owns: draining (with the failure handling in §5), filtering null/blank entries, rendering the
   `[environment] ` lines, and rewriting a `ToolUseResult` while keeping `isError()` and the render payload (the same
   carry-over `attachFeedback` already does). `BashTool.renderNotices` / `ENVIRONMENT_NOTICE_PREFIX` stay where they are;
   the helper reuses the same prefix constant (moved or referenced — implementer's choice, but one string, not two).

4. **Second model-facing path: the inline skill loop's fallback.** `LlmSkillExecutor.dispatchWithoutHooks` runs tool
   batches straight through `ToolExecutionManager.executeAll` when no `SkillToolDispatcher` is bound. Its results go to
   an LLM too, so it applies the same helper per result. (When the agent executor binds a dispatcher — the normal case —
   that dispatcher already goes through `SingleToolInvoker`, so this is only the hook-less fallback.)

That is the whole core change. The decisive properties:

- **"Any tool that used the environment" → any tool call whose context carries an environment.** We do not try to
  detect whether the tool actually touched `fileSystem()` / `shell()`. A notice is a fact about the environment, not about
  the tool that happens to carry it; delivering it on a `TodoWrite` result is as useful as on a `Read` result, and the
  sandbox's lazily-triggered reset is queued *during* the triggering call, so draining after that same call still
  delivers it on the call that caused it.
- **Drained after PostTool hooks.** PostTool hooks never see the notices in `toolUseResult` (so a masking hook cannot
  rewrite or drop them, and a hook judging output is not confused by them), and a notice the hook's *own* shell action
  produced (the hook ran `ExecutionOptions.hook` in the same sandbox and triggered a re-creation) is still delivered on
  this tool's result instead of being lost — `ShellActionRunner` already ignores `ShellCommandResult.notices()`, and
  stays that way. This is the "never into hook results" requirement.
- **Placement at the head**, like `Bash`, so a long result or one the model skims does not bury it, and so a `Bash`
  result reads uniformly: the invoker's drained lines first, then `Bash`'s own per-command notices, then output.

### 2.2 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| **Drain in `DefaultToolExecutor`** (the issue's literal wording) | That layer is also reached by paths whose results are *not* model-facing: `RoutineExecutor` steps, `RecentFilesRestoreHook`, workflow scripts that call tools, and `executeAll` callers generally. Draining there would hand notices to a script or a hook and lose them — violating one-shot *delivery*. It also runs before PostTool hooks, which would then see (and could rewrite) the notices. |
| **Hook / reminder at turn start** (the issue's alternative) | The sandbox provisions lazily (§13 of `execution-environment.md`: `resolve()` creates nothing), so at turn start it usually does not yet know it was reset — the reset is discovered on the first file or shell call. A turn-start drain would miss exactly the case that motivates the issue, and mid-turn facts (a re-creation during the turn) would still need a per-tool path. Could be added later on top of the same `drainNotices()`; not needed now. |
| **Track whether the tool touched the environment** (a recording proxy around `fileSystem()` / `shell()` per call) | Invasive: tools capture `fileSystem()` references, `Grep` goes through `contentSearch()`, `Skill` stages, forks resolve their own environment — every accessor would need wrapping, and the proxy would have to be the instance published under the write-once `ToolContextKeys.EXECUTION_ENVIRONMENT`. It buys only "don't attach to a tool that didn't touch the env", which has no value per §2.1. |
| **Notice field on `ToolResult` / `ToolUseResult`** (a structured sidecar the provider renders) | Every LLM client and transcript codec would have to learn it; the model needs plain text in the tool result, and the existing `[environment]` text convention already exists. A sidecar can be added later without changing the SPI. |
| **Render as `<system-reminder key="environment">` appended at the tail** | Would give `Bash` two renderings of the same kind of fact in one result. `[environment]` lines are what the model already sees from `Bash` and `BashOutput`. |
| **Two-phase drain (peek, then acknowledge after the result is recorded)** | Lets a provider keep a notice until it is known to be in the transcript, but doubles the SPI surface and needs a core-side "recorded" hook on every path. At-most-once with "drained ⇒ committed to a result" is enough to fix the sandbox's bookkeeping (§5, §7 Q2). |
| **Route all notices through `drainNotices()` and deprecate `ShellCommandResult.notices()`** | Per-command notices ("the shell session was reopened; cwd reset") legitimately belong to one command and to `BashOutput`'s background result, which may be read in a later turn. Both channels stay; the provider chooses which one a fact goes to (§4). |

## 3. Concrete changes

**`modules/aimon-core` main**

- `environment/ExecutionEnvironment.java` — add `default List<String> drainNotices()` with javadoc stating the contract
  in §4 (atomic, one-shot, never throws, may be called on any thread, called by the core only from model-facing tool
  dispatch, not to be called by tools or hooks).
- `toolinvocation/EnvironmentNotices.java` (new, public final utility, private ctor) —
  `static ToolUseResult deliver(ToolUseResult result, ExecutionEnvironment environmentOrNull)`; renders with the shared
  prefix; preserves `isError` and render payload; logs a WARN and returns `result` unchanged if draining throws.
- `toolinvocation/SingleToolInvoker.java` — apply `EnvironmentNotices.deliver(…, environmentOf(spec))` to the value of
  every `return` in `invoke` (outermost, after `attachFeedback`; order of the two does not matter since notices go to the
  head and feedback to the tail). Update the class javadoc's pipeline list with a step 6 "deliver environment notices".
- `skill/execution/llm/LlmSkillExecutor.java` — in `dispatchWithoutHooks`, map each result through the helper using
  `ExecutionEnvironmentAccess.of(toolContext)`. Check `ArchitectureRulesTest` / package rules accept a
  `skill → toolinvocation` dependency; if not, put the helper in `at.aimon.core.tools` next to
  `ExecutionEnvironmentAccess` instead (it depends only on `llm.ToolUseResult` and `environment`).
- `tools/bash/BashTool.java` — no behaviour change. If the prefix constant moves to the helper, `BashTool` references it.

**Not changed:** `DefaultToolExecutor`, `DefaultToolExecutionManager`, `ShellActionRunner` (hook shell actions keep
ignoring notices), `BashOutputTool`, `ShellCommandResult`, local environment implementations, `RoutineExecutor`,
`RecentFilesRestoreHook`.

**Docs**

- `docs/design/tool/execution-environment.md` — §8 (rename to "환경의 알림" or add §8.1) describing the second channel
  and when a provider uses which; §13 contract table: new row (core: drains once after every model-facing tool call and
  shows the result; sandbox: `drainNotices` is atomic and one-shot, and environment-level facts go there, not into
  `ShellCommandResult`). No `.en.md` translation exists for this doc (checked).
- `docs/features/tool/tool-development-guide.md` **and** `tool-development-guide.en.md` — one short note that tools must
  not call `drainNotices()` and need not render environment notices (the invoker does). Keep heading/list structure in
  sync (`check-translation-structure.py`).
- `docs/backlog/execution-environment-open-items.md` — record the decision as the next free EE number (after EE-101) if
  the repo's practice is to log closed items there; reference #254.
- `CHANGELOG.md` `[Unreleased]` — "Added: environment notices on every tool result —
  `ExecutionEnvironment.drainNotices()`", with what changes for whom (nothing for local / custom environments; external
  providers may move environment-level notices off `ShellCommandResult`).

## 4. Data and interface shapes

- **New SPI method:** `List<String> ExecutionEnvironment.drainNotices()`, default `List.of()`.
  Contract:
  - Returns and removes; a notice returned once is never returned again (by this or a concurrent call).
  - Must not block on remote I/O for long and should not throw; the core tolerates a throw (§5) but treats it as
    "nothing delivered".
  - `null` return is treated as empty; `null` / blank entries are skipped.
  - Scope of the queue (per environment instance, per session, per runtime) is the provider's choice; the core drains
    whatever instance sits in the dispatching tool context.
  - Which channel to use: a fact about *one command's run* → `ShellCommandResult.notices()` (also reaches `BashOutput`
    for background commands); a fact about *the environment* → `drainNotices()`. A provider must not put the same fact
    in both.
- **Model-visible shape:** unchanged convention — each notice becomes one line `"[environment] " + notice + "\n"` at the
  head of the tool result content; error results stay errors. Example after a file-only call:

  ```text
  [environment] The sandbox was re-created; /workspace is empty.
  <Read's normal output>
  ```

- **No change** to `ToolResult`, `ToolUseResult`, `ToolExecutionResult`, transcripts, wire formats, hook contexts or
  events.

## 5. Failure modes

| Case | Handling |
|---|---|
| `drainNotices()` throws | WARN with tool name and toolUseId; result returned unchanged. The notice is lost or kept depending on whether the provider removed it before throwing — javadoc asks providers not to throw. Never costs the model its tool result. |
| Returns `null`, or null/blank entries | Treated as empty / skipped. |
| No environment in the tool context (hand-built context, some rewake paths) | Nothing to drain; unchanged. |
| `UnavailableExecutionEnvironment` | Default empty; its own error message already reaches the model via the tool. |
| Parallel tool batch (`ParallelToolDispatcher`) | Each invocation drains independently; the provider's atomic drain gives each notice to exactly one result. Which sibling carries it is unspecified — acceptable, all reach the model in the same assistant turn. |
| Tool denied / blocked / unknown | Still drained: the result reaches the model, so delivery is as good there. |
| Tool throws (caught by `DefaultToolExecutionManager` or the invoker's catch-all) | Drained onto the error result. |
| Execution interrupted after the tool returned | The result is still produced and recorded by the existing paths; if a path discards it, the notice is lost (at-most-once — see §7 Q2). |
| PostTool hook rewrites output | Notices are prepended *after* the rewrite, so they survive. |
| Hook (Java) calls `drainNotices()` on `HookContext.getExecutionEnvironment()` | Contract violation — documented in javadoc; not enforced (§7 Q4). |
| Subagent fork | The fork's tool calls drain the fork's environment and deliver to the fork's model. The parent hears only what its own environment instance still queues. Provider decides queue scope (§7 Q3). |
| Workflow script tool calls / scheduled routine steps (not via `SingleToolInvoker`) | Not drained there; notices stay queued for the next model-facing call. This is deliberate. |
| Very many / very long notices | Not truncated by the core (Bash doesn't either). §7 Q5. |

## 6. Test strategy

Unit tests in `aimon-core` (JUnit 5, existing fakes):

- `SingleToolInvokerTest` — with a test `ExecutionEnvironment` whose `drainNotices()` pops from a queue:
  - success result of a non-shell tool gets `[environment] …` lines at the head, content otherwise unchanged;
  - error result stays `isError()`; render payload is preserved;
  - drained once: a second invocation with the same environment carries nothing;
  - delivered on permission-deny, approval-deny, PreTool-block, unknown-tool and catch-all paths;
  - PostTool hook sees the result **without** notices (capture `PostToolContext.getToolUseResult()`), and a PostTool
    rewrite still ends with the notices prepended;
  - a notice queued *during* a PostTool hook (hook action pushes onto the fake queue) is delivered on this result;
  - `drainNotices()` throwing → original result, no exception; `null` return and blank entries ignored;
  - no environment in context → result identical (equals) to today's;
  - hook feedback block and notices coexist (notices head, `<system-reminder key="hook-feedback">` tail).
- `EnvironmentNotices` helper test — rendering, null/blank filtering, error/payload preservation.
- `LlmSkillExecutor` test for the hook-less fallback dispatch carrying notices.
- `BashTool` regression: a `Bash` result with both drained notices and `ShellCommandResult.notices()` shows drained lines
  first, then the command's, then output (exercised through `SingleToolInvoker`, since `BashTool` itself is unchanged).
- An `OrcaAgentExecutor`-level test (existing scripted-LLM harness) where a turn uses only `Read` against an environment
  that queues a notice: the tool_result message the next LLM call receives contains the `[environment]` line, and a
  subsequent `Read` does not repeat it.
- `ShellActionRunner` unchanged — existing tests already assert hook outcomes have no notices; add one assertion that a
  hook command's `ShellCommandResult.notices()` does not appear in the hook outcome if not already covered.
- Existing `ExecutionEnvironment` test doubles (`StagingEnvironment`, `ForeignEnvironment`, `RecordingEnvironment`) compile
  unchanged thanks to the default.
- `./gradlew checkAll`, `python3 scripts/check-translation-structure.py`, `scripts/check-doc-links.py`.

## 7. Open questions

1. **Tool-call paths outside `SingleToolInvoker` that are model-facing.** I found two dispatchers that feed an LLM
   (`SingleToolInvoker` via Orca / subagent / command dispatcher, and `LlmSkillExecutor.dispatchWithoutHooks`). The
   memory deriver (`ReActLlmDeriver`) runs its own tools against no execution environment as far as I can tell; the
   implementer should confirm no other loop feeds tool results to an LLM with an environment in context.
2. **At-most-once vs at-least-once.** The design treats "drained" as "committed to a result the model will see". If the
   turn is cancelled between the tool returning and the result being recorded, the notice is lost. Is that acceptable to
   aimon-sandbox (its `lastWorkspaceId` would move on), or does it need a peek/ack pair? Recommendation: accept
   at-most-once now; the window is tiny and the next reset produces a new notice anyway.
3. **Queue scope across forks.** If the sandbox keeps one `PendingNotices` per session and a subagent fork's environment
   shares it, the fork can consume a notice the parent needed (the parent then edits an empty tree unaware). That is the
   provider's call, but the design doc should state the recommendation: queue per environment instance, and seed a
   fork's queue from what the parent would also have to hear, rather than share one queue.
4. **Should hooks be prevented from draining?** `HookContext.getExecutionEnvironment()` hands hooks the raw environment.
   Enforcing "never drain from a hook" would need a view wrapper that forwards everything but `drainNotices()`; the
   design only documents the rule. Revisit if a hook is ever found doing it.
5. **Limits.** Should the core cap notice count or length per result (e.g. 10 lines × 500 chars, like other
   reminders)? Bash doesn't today; left uncapped for consistency.
6. **Turn-start delivery** for notices the provider already knows before any tool call (e.g. "this session moved to
   another workspace" decided at `resolve()`): a turn whose model makes no tool call never hears them. Out of scope here;
   the same `drainNotices()` could later be called once before the first LLM call and rendered as a
   `<system-reminder>` — needs a decision on whether that counts as a "hook result".
7. **Backlog entry number / CHANGELOG section wording** follow repo practice; the implementer picks the next free EE id.

## 8. Where the implementation departed from this design

### 8.1 The helper lives in `at.aimon.core.tools`, and owns the prefix

§2.1(3) and §3 put `EnvironmentNotices` in `at.aimon.core.toolinvocation`, with `at.aimon.core.tools` as the fallback
if `skill → toolinvocation` were a problem. The review asked for one place up front, and the fallback is the simpler
one: `LlmSkillExecutor` already imports `at.aimon.core.tools`, the helper depends only on `environment` and
`llm.ToolUseResult`, and it sits next to `ExecutionEnvironmentAccess`, which both callers use to find the environment.
So it is `at.aimon.core.tools.EnvironmentNotices`. `SingleToolInvoker` (in `toolinvocation`, which already imports
`tools`) calls it from there; no new package dependency appears.

"Moved or referenced" in §2.1(3) meant "moved": `BashTool.ENVIRONMENT_NOTICE_PREFIX` was package-private. The public
constant is now `EnvironmentNotices.PREFIX`, and `BashTool.ENVIRONMENT_NOTICE_PREFIX` is defined as that constant, so
`BashTool` and `BashOutputTool` (through `BashTool.renderNotices`) keep their code and print the same string.
`BashTool.renderNotices` itself is unchanged — it does not skip blank entries the way the helper does, because §3 said
no behaviour change for `Bash`.

### 8.2 `deliver` takes the tool name

§3's signature was `deliver(ToolUseResult, ExecutionEnvironment)`. §5 asks for a WARN "with tool name and toolUseId"
when draining throws, and a `ToolUseResult` carries no tool name, so the method is
`deliver(ToolUseResult result, ExecutionEnvironment environment, String toolName)`, with `environment` nullable. It catches
`RuntimeException` only.

### 8.3 One wrap in `invoke`, not one per `return`

§3 said to apply the helper to the value of every `return` in `invoke`. The body of `invoke` became a private
`dispatch(spec)`, and `invoke` is now one line: `EnvironmentNotices.deliver(dispatch(spec), environmentOf(spec), …)`.
Every exit path (permission deny, approval deny, PreTool block, unknown tool, success, catch-all) is covered by
construction, and a new `return` added later cannot miss it. One consequence the design did not state: the drain now
runs after the per-tool `TerminatorRegistrar` is closed, so a `THREAD_INTERRUPT` tool's interrupt terminator cannot
fire into the drain.

### 8.4 Review notes applied

All six non-blocking notes were adopted.

- **Fork queue sharing** — the rule "one queue per environment instance; seed a fork's queue instead of sharing" is
  in the `drainNotices()` javadoc and the §13 contract row of `execution-environment.md`, not only in Q3.
- **Slash-command skills** — `execution-environment.md` §8.1 says that a notice drained inside a user-typed `/skill`
  reaches only that skill's scratch LLM loop. It is recorded with the fork case in EE-107.
- **Prefix constant** — §8.1 above.
- **Same instance** — `deliver` returns `result` itself when there is no environment, nothing was queued, the drain
  returned `null` or only blank entries, or it threw. `EnvironmentNoticesTest` asserts `isSameAs`.
- **Helper placement** — §8.1 above.
- **Turn-start gap** — recorded as EE-106.

### 8.5 Test shape

The tests in §6 are all there, with these differences:

- `TestExecutionEnvironments.Builder` gained `notices(Queue<String>)`, so `drainNotices()` empties a queue the test
  owns. `SingleToolInvokerTest`, `LlmSkillExecutorTest`, `EnvironmentNoticesTest` and the new
  `OrcaAgentExecutorEnvironmentNoticesTest` all use it. The SPI default is checked against
  `TestExecutionEnvironments.withDescriptor`, which does not override `drainNotices()`.
- The `Bash` ordering check (drained lines, then the command's lines, then output) runs through the real Orca loop in
  `OrcaAgentExecutorEnvironmentNoticesTest` with a fixed-result shell, rather than a separate `SingleToolInvoker` test
  with a real `BashTool`.
- The invoker's "no notices" test compares with `isEqualTo` against a freshly built result, because the result the
  invoker returns is built inside the pipeline. The same-instance property is pinned at the helper.
- `ShellActionRunnerTest` gained a case: a hook command whose `ShellCommandResult` carries notices produces an outcome
  without them. No existing test covered that.

### 8.6 Open questions

- **Q1** (other model-facing loops): confirmed during implementation. The only two are `SingleToolInvoker` and
  `LlmSkillExecutor.dispatchWithoutHooks`. The review found the same.
- **Q2, Q3**: EE-107, together with the review's fork and slash-skill notes.
- **Q4**: EE-108.
- **Q5** (caps): not promoted. Left uncapped for consistency with `Bash`, as the design says. It changes nothing
  outside this change until a provider produces many notices.
- **Q6**: EE-106.
- **Q7**: the backlog numbers are EE-106 to EE-108 (EE-102 to EE-105 belong to the concurrent PR #257). The CHANGELOG entry is under `[Unreleased]` › Added.
