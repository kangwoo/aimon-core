# Design — a skill's loop and a fork read a cut response the way a turn does, and the Task tool names a cut fork (#115, #117)

> Status: **IMPLEMENTED** — `aimon-core` (the new `at.aimon.core.agent.budget.StalledIterationGuard`; the cut-response
> and stall paths of `LlmSkillExecutor`, `DefaultSubagentExecutor`, `OrcaAgentExecutor` and `ReActLlmDeriver`; the
> Task tool's result; `ChunkAggregator`'s parse WARN), one test comment in `aimon-llm-anthropic`, and the records:
> [`orca-executor.md`](orca-executor.md) §2.2, §5, §12 and §13, backlog `L-22` and `L-23` (closed), `L-25` and `L-26`.
> Sources: issues [#115](https://github.com/kangwoo/aimon-core/issues/115) and
> [#117](https://github.com/kangwoo/aimon-core/issues/117).
>
> **[§11](#11-after-the-build--departures-corrections-and-what-went-to-the-backlog), appended after the build, is
> where the build departs from this document.** Everything between this header and §11 is the body as approved in
> design review round 2 (PASS, no blocking findings, four non-blocking notes), kept byte-exact rather than corrected —
> the house habit in this directory, for the reason
> [`../llm/model-capability-binding-round-trip.md`](../llm/model-capability-binding-round-trip.md) gives. Its
> `file:line` citations are at `main` `c561e17`. The review transcripts (`review-1.md`, `review-2.md`) and the run
> records the body names (`TASK.md`, `$RUN_DIR/build/`) are not in the repository; §11 reproduces what was measured.
>
> It is in English, like its siblings in `docs/design/agent-execution/`; `docs/design/` is not a translation target
> (`docs/project/documentation-guide.md` §5.1). It continues
> [`max-tokens-truncation-reporting.md`](max-tokens-truncation-reporting.md), whose §11.3 registered the two backlog
> items this work closes. What this work left open is in
> [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), L-25 and L-26.

> Run `truncation-skill-loop-stall`. Base `main` at `c561e17` (the worktree HEAD, clean). Every `file:line` below was
> re-read at that commit unless it says otherwise. "Read" means established by reading source; "measured" means run.
> One unbilled measurement was made for this design (§2.5, Jackson in a scratch directory); no API call was made.

**Revision 2 — after `review-1.md`.** All six findings were accepted. Nothing was rebutted, so there is no `rebuttal-1.md`.

- **Blocking — D10 is re-decided.** Revision 1 declined to commit a design record because the task then said not to edit
  `docs/design/README.md`. The task's section on #122 was amended at 2026-09-11T04:38Z: #122 is decided (the verbatim
  approved body wins), and a run that commits a record adds its index row, as `babfc1a`, `784ae2f` and `5606b04` did.
  D10 now commits this design as `docs/design/agent-execution/skill-loop-truncation-and-fork-stall.md`, with a row in
  `docs/design/README.md` §2, and leaves §3 alone. §4.3, §8.2, §9 Q5 and §10 follow.
- **Non-blocking, all taken:**
  - the proposed `orca-executor.md` §2.2 text no longer says the deriver gives "the same answer" or is ended by the stall
    guard — it only refuses cut calls (§4.3, D9);
  - D1 no longer claims parity with the executors for what a person sees: a turn's refused call emits lifecycle events the
    REPL prints, and a skill's never does (D1, §8.1 F-7, the L-22 closure);
  - D5's observable change names the side-effect approval gate, and so does the changelog bullet;
  - D7 omits the offset clause when `getCharOffset()` is negative;
  - §7.0 lists the existing tests whose tool calls fail across iterations that were checked against the wider guards, and
    requires the full `aimon-core` and `aimon-workflow-graaljs` suites.

---

## 1. The problem, in one paragraph

#113 gave both agent executors one reading of a response cut at `max_tokens` (`at.aimon.core.agent.budget.TruncatedResponses`):
every tool call of a cut response is refused rather than run, a cut final answer is marked and ends `TRUNCATED`, and on a
turn three refused responses in a row trip the stalled-iteration guard and end `ERROR`. Three places next to that change
still answer differently. **A skill invoked as a slash command** runs its own loop in `LlmSkillExecutor`, which never reads
the stop reason: a cut tool call runs with whatever arguments arrived and a cut final answer returns as a plain success
(`ReActLlmDeriver` has the same loop and no construction site). **A subagent fork** refuses cut calls but has no
stalled-iteration guard, so a fork whose every response is cut makes up to `maxIterations` (1000 by default, and no
in-tree request budget) refused, `max_tokens`-sized requests; whatever `CompletionReason` a guard gives it is read by task
records, workflow verdicts, skill forks and the Task tool. **The Task tool** prints `Status: SUCCESS` for a fork cut at
`max_tokens`, leaving the parent model only the marker at the end of the summary. Four smaller records items from #113's
review come with it: a WARN that logs whole tool arguments, a `CHANGELOG.md` claim no test pins, a design section that
describes classes that were never built, and a test comment that over-claims.

---

## 2. What the code does today (read at `c561e17`)

### 2.1 Every tool loop in main sources

Census: `git grep -n 'hasToolUses()' -- 'modules/*/src/main/**'`, keeping the calls made on an `LlmResponse` (the rest are
`Message.hasToolUses()` in compaction, codecs and provider converters, none of which loops). Exactly four loops:

| Loop | Where | Reads the stop reason | Stall guard | What bounds it | Reachable |
|---|---|---|---|---|---|
| Turn | `OrcaAgentExecutor.executeReActLoop` — `:1725` | yes | yes — `:1818-1828`, threshold `:233` = 3 | agent `maxIterations`, request budget | yes |
| Fork | `DefaultSubagentExecutor.runReActLoop` — `:447` | yes | **no** — class javadoc says so (`:104-105`), and `refuseTruncatedToolUses`' javadoc (`:824-826`) | subagent `maxIterations` (`SubagentMetadata.java:26`, default 1000); the one main-source `SubagentExecutionRequest.builder()` (`DefaultSubagentExecutionManager.java:617`) sets no budget | yes |
| Skill | `LlmSkillExecutor.execute` — loop `:185-217`, `while` at `:191` | **no** | **no** | skill `max-iterations` (`SkillMetadata.java:54`, default 100); no budget | yes — `new LlmSkillExecutor(` twice, `DefaultCommandExecutionManager.java:123`, `:145`; `LlmSkillExecutor::new` zero; `OrcaAgentExecutorFactory.createDefaultCommandExecutionManager` (`:866-868`, called at `:650`) builds the `:123` one |
| Deriver | `ReActLlmDeriver.derive` — `for` at `:188`, dispatch at `:216` | **no** | no | `DEFAULT_MAX_ITERATIONS = 6` (`:122`) and the token budget | **no** — the name `ReActLlmDeriver` appears in no main source outside its own file, in any module (searched as `new ReActLlmDeriver(`, `ReActLlmDeriver::new` and the bare name). Tests only: `ReActLlmDeriverTest`, `ToolExecutionGateArchitectureTest`, `DeriverObservationCreateToolTest` |

Two more facts about the skill loop decide §3:

- **Nobody can stop it.** `OrcaAgentExecutor.executeCommand`'s own comment: *"Nothing trips this one today — a slash
  command is not interruptible"* (`:2024-2026`), and `LlmSkillExecutor` reads no cancellation signal.
- **It uses the blocking four-argument `sendMessage`** (`:185`, `:213`), so the response comes from
  `AnthropicLlmClient.convertResponse` (`:782-836`), which maps the stop reason (`:827-832`) and WARNs
  `Anthropic response was truncated due to max_tokens limit` (`:786`). What a cut `tool_use` block looks like on that
  path is **not measured** (§9 Q1).

### 2.2 Where a skill's result goes

`SkillExecutionResult` is `{success, response, error, metadata}`. Its one consumer in main,
`SkillBackedCommandExecutor.toCommandResult` (`:83-94`, `command/**`, not this run's), copies those four into
`CommandExecutionResult`, which has no slot for a reason. `OrcaAgentExecutor.executeCommandFlow` (`:1475-1510`) commits
`getResponse()` as the assistant message and returns `OrcaAgentExecutionResult.success(...)` or `.failure(...)` — so the
turn reads `COMPLETED` or `ERROR`. A **fork-mode** skill already surfaces a cut fork as a success whose text ends in the
marker: `SubagentBackedSkillForkExecutor` reads only `isSuccess()` (`:123-126`).

### 2.3 Who reads a fork's `CompletionReason`

| Reader | What it reads |
|---|---|
| `SubagentExecutionResult` | `getStatus()` is `success ? "SUCCESS" : "FAILURE"` (`:333-335`); `getSummary()` is the answer on success, the error on failure (`:321-323`); `getCompletionReason()` javadoc (`:340-355`) calls budget stops the "needs another pass" signal and says a `TRUNCATED` result reads `SUCCESS` |
| `TaskResult.from` (`:116`) + `JsonTaskResultCodec` | persists the name (`:58`); decodes an unknown name as `COMPLETED`/`ERROR` from the success flag (`:102-112`) |
| `AgentStepResult.isComplete()` | `== COMPLETED` (`:80-82`) |
| `DefaultWorkflowContext` step cache | saves only when `isComplete()` (`:162-165`) |
| `StepOutcome` / `StepOutcomeCodec` | only `COMPLETED` outcomes are ever encoded (`StepOutcome.java:25`); decode is a strict `valueOf` that throws on an unknown name (`StepOutcomeCodec.java:106-111`) |
| `WorkflowPatterns.loopUntilDry` | a round counts as quiet only if `isComplete()` (`:201`) |
| `WorkflowPatterns.completenessCritic` | stops on `!isSuccess() \|\| !isComplete()` (`:249`, `:257`) |
| `AgentResultView` (`aimon-workflow-graaljs`) | `isComplete` and `completionReason.name()` to scripts (`:34-36`) |
| `SubagentBackedSkillForkExecutor` | `isSuccess()` only (`:123-126`) |
| `DefaultSubagentExecutionManager.finalizeBackgroundTask` | `FAILED` iff `!isSuccess()` (`:855-860`); the notification detail is the summary or error message, head-cut to 500 chars (`:965-995`, `:107`) |
| `TaskTool.formatSubagentResult` | `getStatus()` (`:619`) and `getSummary()` (`:627-628`) |
| `AgentOutputTool.formatAgentResult` | `TaskResult.getStatus()` (`:378`); not this run's file |

### 2.4 The Task tool's output has a parser in another module

`TaskTool.formatSubagentResult` (`:613-630`) writes `=== Subagent Task Result ===`, `Subagent:`, `Task:`, `Status:`,
`Iterations:`, `Tokens:`, a blank line, `Result:`, then the tail-kept summary. `aimon-cli`'s
`SubagentResultDisplayHook` (`:33-36`, a file owned by the sibling run `anthropic-default-model-followups`) parses it:

```
=== Subagent Task Result ===\s+Subagent: (.+)\s+Task: (.+)\s+Status: (\w+)\s+Iterations: (\d+)\s+Tokens: (\d+)\s+\s+Result:\s+([\s\S]+)
```

Only whitespace may sit between `Status:` and `Result:`. A line added anywhere in that span stops the match, and the CLI
silently stops rendering subagent results. **Nothing would go red:** `SubagentResultDisplayHookTest` builds its own input
string (`:209-218`) rather than calling `TaskTool`. Group 6 captures everything after `Result:`.

`SubagentResultFormatter.truncateTailKeep` keeps the **tail** (`:53-76`), so the marker at the end of a cut answer
survives the Task tool's own elision.

### 2.5 `ChunkAggregator`'s parse WARN — what Jackson puts in the message (measured)

`parseArguments` WARNs `Failed to parse accumulated tool_call arguments as JSON: {} (json={})` with `e.getMessage()` and
the whole string (`:295`). Dropping `json=` is not enough. Measured with the build's own Jackson (`jackson-databind` /
`jackson-core` 2.22.2 from the Gradle cache, `libs.versions.toml:109`), `ObjectMapper.readValue(json, Map)` as at
`:292`, run from a scratch directory, not billed:

| Input | Exception | `getMessage()` carries argument text? | Location |
|---|---|---|---|
| `{"path": "/tmp/sea.txt"` (the shape #113's live probe saw) | `JsonEOFException` — *"Unexpected end-of-input: expected close marker for Object"* | no | char offset 23 = length |
| `{"path": "/tmp/x", "content": "SECRET-FILE-BODY-abc` | `JsonEOFException` — *"was expecting closing quote for a string value"* | no | 51 = length |
| `{"command": SECRETTOKENxyz}` | `JsonParseException` — *"Unrecognized token 'SECRETTOKENxyz': was expecting …"* | **yes** | 12 |
| `["SECRET-in-array"]` | `MismatchedInputException` | no | 0 |
| `{ not valid json SECRET` | `JsonParseException` — *"Unexpected character ('n' (code 110))"* | no (one character) | 2 |

Source content is `REDACTED` in the location (`INCLUDE_SOURCE_IN_LOCATION` is off by default), but the token excerpt in
the message is not. For the common cut, the exception type plus an offset equal to the length already says "ended early".

### 2.6 The records items

- `CHANGELOG.md:59` — *"No permission check and no PermissionRequest/PreTool/PostTool hook runs for a refused call"*;
  the only statement of it in tests is a comment (`OrcaAgentExecutorTruncationTest.java:283-285`).
- `docs/design/agent-execution/orca-executor.md` §2.2 (`:98-117`) presents `TruncationRecoveryStrategy`,
  `FlaggingTruncationRecoveryStrategy` as the default and an opt-in `ContinuingTruncationRecoveryStrategy`;
  `git grep TruncationRecoveryStrategy` matches docs only. Three sentences lean on it: the §2 premise that a cut response
  carries no `tool_use` (`:68`), §12's opt-in SPI list (`:370`), and §13's item 3 (`:404`). The file's header says
  **IMPLEMENTED**; `docs/design/` has no `.en.md` twins.
- `AnthropicThinkingBudgetsTest.java:72` — *"HIGH clamps out of the box"*; the javadoc it repeats was narrowed by #101 to
  *"whenever the call's `LlmModel` sets no `maxTokens` and `AnthropicConfig.Builder.maxTokens(int)` was not used"*
  (`AnthropicThinkingBudgets.java:123-125`).

---

## 3. Decisions

Each decision states the option taken, the options rejected, and why; each becomes its own heading in the PR body.

### D1 — The skill loop refuses every call of a cut response and marks a cut final answer in `getResponse()`; no typed field

**Taken.** `LlmSkillExecutor` reads each response once with `TruncatedResponses.isTruncated`, as both executors do.

- **A cut response with tool calls.** The assistant message is committed as today (every `tool_use` must be answered).
  The dispatcher — bound or fallback — is **not called**; each call is answered with `TruncatedResponses.refusal(toolUse)`,
  in order. One WARN names the skill, the 1-based iteration, the tool names (never their arguments) and
  `TruncatedResponses.reasoningClause(response.getTokenUsage())`. The loop continues, and D5's guard ends a streak.
- **A cut final answer.** The text gets `TruncatedResponses.TRUNCATION_MARKER` appended, the flagged message is committed
  to the scratch transcript with its reasoning traces, and the result is `SkillExecutionResult.success(flagged, metadata)`.
  One WARN names the skill and the reasoning clause.

**How it shows in a skill's result.** The marker is the suffix of `getResponse()`, which is what reaches
`CommandExecutionResult.getResponse()`, the turn's final answer and the turn's transcript.

**What a person sees of a refusal — less than on a turn.** A single refusal leaves nothing in the result, and nothing on
the terminal either. On a turn a refused call still emits `ToolUseStarted` and `ToolResultReady`, and the REPL prints
`Tool '<name>' failed: Cut off at max_tokens: …` from the latter (`CHANGELOG.md:65-68`;
`OrcaAgentExecutorTruncationTest.aRefusedCallStillEmitsItsLifecycleEvents`). The command path's bound dispatcher emits no
lifecycle events, even for calls it does run (`OrcaAgentExecutor.java:2079-2088`), and the REPL's usual tool-call line
comes from a PreTool hook that a refused call never reaches. So one refusal shows only in the WARN and in what the model
reads next. A streak ends the skill through D5 with a failure whose message names `max_tokens` (D4), and that failure is
the first thing a person running `/skill` sees. This is not a regression — the command path never emitted events — and it
is not widened here (§8.1 F-7).

**Rejected — a typed signal on the skill result** (a `CompletionReason`, or `isTruncated()` on the result or its metadata).
Nothing would read it. The only consumer copies four fields into `CommandExecutionResult`, which has no slot, and the turn
is built from `isSuccess()` (§2.2); carrying the signal to the turn means public additions to `CommandExecutionResult` and
changes in `command/**`, neither of which is this run's. The fork-mode skill path already delivers a cut fork as success
plus marker text, so the marker is the shape a skill result has for a cut answer today. A field no reader reads is a
second, unverified way of saying what the text already says.

**Rejected — return a failure for a cut final answer.** A failure's response is an error message, so the partial text the
turn and the fork both keep would be lost on this path alone.

**Consequence, recorded rather than changed:** a turn that runs a slash skill whose final answer was cut ends `COMPLETED`,
with the marker in its final answer (§8 F-2).

### D2 — `ReActLlmDeriver.derive` changes with it: cut tool calls are refused, and nothing else

**Taken.** Each response is read once with `isTruncated`. A cut response with tool calls has every call answered with
`refusal` **without `invokeTool`**, one WARN names the observer key, the iteration, the tool names and the reasoning
clause, and the loop continues inside its existing six-iteration and token-budget bounds. A cut text-only response ends
the loop as today: `derive` returns observations, never text, so a marker would have no reader. No stall guard: six
iterations and the budget already bound it.

**Reachability, measured by search:** zero main-source mentions outside the class, in every module (§2.1).

**Rejected — wait for a construction site** (L-22's *"`ReActLlmDeriver` 는 main 소스에서 처음 생성될 때"*). That trigger
never passes this place (`docs/backlog/README.md` rule seven): the day someone constructs the deriver is a change to some
other file — `aimon-cli`'s memory wiring, a starter — that touches neither this class nor L-22, so the item would not wake
and the defect would ship with the construction, silently. The loop runs unattended (its class javadoc) and its one tool
that matters writes to the observation store; whether a cut `deriver.observation.create` call arrives with no arguments or
with some depends on the unmeasured blocking-path shape, and refusing does not. The change is a few lines in a `final`
class, pinned with the existing stub harness. Rule six still governs how it is recorded: the L-22 closure says the fix was
made on a class no deployment constructs, not that a deployment was exposed.

### D3 — The fork gets the turn's guard, through one shared definition, and ends `ERROR`

**Taken.** A new `at.aimon.core.agent.budget.StalledIterationGuard` (§5.1) holds the threshold (3), the predicate (at least
one result, all of them errors) and the per-execution streak. `OrcaAgentExecutor` uses it in place of its local counter —
`MAX_CONSECUTIVE_STALLED_ITERATIONS` stays public with the same name and value, and the package-private
`isStalledIteration` stays as a delegate (`OrcaAgentExecutorStalledIterationTest` calls it). `DefaultSubagentExecutor`
records each tool iteration **after** its iteration-tail cancellation check (`:484-486`). On a trip the fork WARNs and
returns `createFailureResult(lc, guard.stopMessage(), iterationCount, accumulatedTokens, CompletionReason.ERROR)`, which
fires OnStop with `success=false` and streams `[ended: <message>]` (`:1011-1023`).

It lives in `agent.budget` for the reason `TruncatedResponses` does — the fork may not reference `agent.impl` — beside
`BudgetTracker`, the package's other mutable per-execution type.

**Why after the tail check.** The fork's tail check (`isCancelledOrInterrupted`) consumes the thread flag and returns
`INTERRUPTED`. Placed after it, a cancelled iteration can never be counted — the property the turn gets by reading
`isCancelled()` directly before counting (`OrcaAgentExecutor.java:1818`; `docs/design/agent-execution/interrupt.md:315-318`).
A fork has no queue drain, so nothing else sits between.

**Rejected — count only consecutive truncated responses.**
1. It is a second definition of "no progress" beside the turn's, the drift `TruncatedResponses` exists to prevent; the
   fork's javadoc makes parity with the turn its design and names the missing guard as the one difference (`:99-105`).
2. It misses a streak the turn catches: a fork alternating cut responses and failing retries resets a truncation-only
   counter on every uncut iteration and never trips.
3. It leaves the fork's non-truncation death spiral — up to 1000 unbudgeted iterations, most often in a background task
   nobody watches — which is the case the turn's guard was built for (`orca-executor.md` §5).

**The observable change, named in `CHANGELOG.md` and the PR body.** A fork whose every tool call fails for three
consecutive iterations — whatever the cause: a tool error, an unknown tool name, a schema `ENFORCE` rejection, a
PermissionRequest or PreTool block, or the side-effect approval gate denying because a fork has no channel to ask — now
ends `ERROR` with the stop message, where it used to continue to its `maxIterations`. One successful call resets the streak.

**`CompletionReason`: reuse `ERROR`.** Every reader in §2.3, read against it:

| Reader | A stall ending `ERROR` | A new `STALLED` constant instead |
|---|---|---|
| `SubagentExecutionResult` | `isSuccess()` false, `getStatus()` `FAILURE`, summary = stop message | same, plus a name |
| `TaskResult` + `JsonTaskResultCodec` | persists `"ERROR"`, which every node version decodes | persists `"STALLED"`; an older node decodes it as `ERROR`, so one record means two things across a rolling upgrade |
| `AgentStepResult.isComplete()` | false | false |
| Workflow step cache | not cached; a resume re-runs the step | same |
| `StepOutcome` / codec | never encoded (`COMPLETED` only) | never encoded — and its strict decode would throw if a future path let one through |
| `loopUntilDry` | not a quiet round; the empty-streak resets | same |
| `completenessCritic` | stops and keeps the previous draft | same |
| GraalJS `AgentResultView` | `isComplete: false`, `completionReason: "ERROR"` | script authors meet a new string |
| `SubagentBackedSkillForkExecutor` | `Skill fork failed for '<skill>': <stop message>` | same |
| Background task | `FAILED`; notification detail = stop message (~170 chars, under the 500 cap) | same |
| Task tool (D6) | `Status: FAILURE`, `Completion reason: ERROR` | `STALLED` |

No reader branches differently between a stall and any other error, so every one reads `ERROR` correctly. The one reader
that needs to tell a `max_tokens` stall from a failing-tool stall is the parent model reading the summary, and it is served
in text (D4). By the task's own rule — reuse unless a reader cannot tell the cases apart — `ERROR` stands.

- **Rejected `STALLED`:** a public, persisted enum name that no reader would branch on, a new string for scripts, and a
  rolling-upgrade hazard wherever a strict decoder meets it.
- **Rejected `TRUNCATED` for a stall made only of refusals:** #113 decided `ERROR` for the same stall on a turn
  (`max-tokens-truncation-reporting.md` §11.3 Q1), and `TRUNCATED` on a fork promises a success with the partial answer
  in `getSummary()` (`SubagentExecutionResult.java:349-352`). A stall has no final answer, so it would be a failure under
  a success reason.
- **Rejected `MAX_ITERATIONS`:** that reason is the "needs another pass" signal (`:345-346`); a stalled fork re-run with the
  same goal stalls again.
- **Rejected `ABORTED`:** framework-initiated cancellation.

### D4 — The stop message names `max_tokens` when every stalled iteration in the streak was a refused cut response

**Taken.** `StalledIterationGuard.stopMessage()` returns today's text byte for byte —
`Execution aborted: N consecutive tool-only iterations made no progress (all tool calls failed)`
(`OrcaAgentExecutor.java:2582-2583`) — and, when **every** iteration of the streak was a cut response whose calls were
refused, appends ` — each of those responses was cut off at max_tokens, and its tool calls were refused`. The turn, the
fork and the skill loop all use it. A mixed streak gets the plain text. The guards' WARNs stay free of the words
`max_tokens`, so #113's assertion that exactly one WARN per cut response names it
(`OrcaAgentExecutorTruncationTest.java:237-240`) keeps holding.

**Why.** D3 reuses `ERROR` because no programmatic reader branches on the difference. The parent model does: a failing
tool invites another tool, a cut response invites less output per response — and all it reads is `Status: FAILURE`,
`Completion reason: ERROR` and this message. Without the clause it cannot tell, and will likely resend the same task. The
turn's refused-only stall is #113's and still under `[Unreleased]`, so no released message changes; every other stall
message is unchanged.

**Rejected — the clause on the fork only:** two texts for one condition. **Rejected — no clause:** the reader that acts on
the difference cannot see it.

### D5 — The skill loop gets the same guard now

**Taken.** `LlmSkillExecutor` records each tool iteration after its results are committed and before the next
`sendMessage`. On a trip it WARNs (skill name, streak, tool iterations, tokens — no `max_tokens`) and returns
`SkillExecutionResult.failure(guard.stopMessage(), new IllegalStateException(message), metadata)`, with the metadata's
iteration count set to the tool iterations completed (the loop's `iterationCount + 1` at that point).

**Why now.** D1 creates, in the skill loop, the refusal repeat L-23 describes for the fork — bounded by `max-iterations`
(100 by default) and no budget, on the one loop a person is sitting in front of and cannot interrupt (§2.1). With D3's
shared guard it costs a few lines. Leaving it registered would keep L-23's defect on exactly that loop.

**Rejected — keep it registered:** above. **Rejected — a truncation-only counter here:** D3's reasons.

**The observable change.** A slash skill whose tool calls all fail for three consecutive iterations now fails with the
stop message instead of continuing to its `max-iterations`. The causes are the same breadth as a fork's:
- a tool error or an unknown tool;
- an allow-list refusal on the dispatcher path;
- a PermissionRequest or PreTool block;
- the side-effect approval gate denying a call — the bound dispatcher runs `SingleToolInvoker`, which consults the gate
  whenever one is wired (`SingleToolInvoker.java:192-200`), so a user who declines a mutating call three iterations
  running ends the skill.

A permission violation on the fallback path still throws and fails the skill at once (`LlmSkillExecutor.java:229-231`),
as today.

### D6 — The Task tool prints `Completion reason: <NAME>` after the Result block when the reason is not `COMPLETED`; `getStatus()` is unchanged

**Taken.** `formatSubagentResult` appends, after the tail-kept summary, a line `Completion reason: <NAME>` whenever
`!result.getCompletionReason().isSuccessful()`. When the result is also `isSuccess()` — today that is exactly `TRUNCATED` —
the line continues ` (the subagent's final answer is incomplete)`. Everything from the header through `Result:` is
byte-identical. Examples in §5.3.

- **After `Result:`,** because `SubagentResultDisplayHook` accepts nothing but whitespace between `Status:` and `Result:`
  (§2.4). Group 6 captures the new line, so the CLI shows it at the end of the rendered result.
- **Only when not `COMPLETED`,** because after `Result:` an always-present line would add to every CLI-rendered subagent
  result, and the line carries information exactly when the finish was not clean. For failures it adds the reason
  (`ERROR`, `MAX_ITERATIONS`, `INTERRUPTED`, a budget name) — the plain reading of "print the completion reason", and a rule
  that needs no revisiting when a reason is added.
- **`isSuccessful()` rather than naming `TRUNCATED`,** because it is the predicate `CompletionReason` documents for this
  distinction, `TaskTool` then encodes no constant, and it needs no new import — so `TaskTool.java:553` (`.defaultModel(`),
  which a sibling run's record cites, does not move.

**Rejected — change what the status says for a cut result** (`getStatus()` returning, say, `TRUNCATED`). It is a public
method whose javadoc promises two values (`:325-335`), `TaskResult.getStatus()` promises to match it (`:140-144`), and
`AgentOutputTool` and the CLI hook read the word. The smaller change reaches the parent model, so the larger one has no
reason. **Rejected — `Status: SUCCESS (TRUNCATED)`:** breaks the CLI regex.

### D7 — `ChunkAggregator`'s WARN names the failure and the size, never the text

**Taken.** `Failed to parse accumulated tool_call arguments as JSON: <ExceptionSimpleName>[ at offset N] (<length> chars);
the arguments are not logged`. The offset clause is written only when the exception is a `JsonProcessingException`, has a
location, and `getLocation().getCharOffset()` is not negative — `JsonLocation` reports `-1` for an unknown offset, and
`at offset -1` would read as a fact. Otherwise the clause is omitted. The returned empty map is unchanged.

**Why not keep `e.getMessage()`:** Jackson copies the offending token into it (§2.5). **Rejected — a length-bounded excerpt
of the arguments:** still content — a command line, a file body.

### D8 — #113's hook claim is pinned by tests on the turn, the fork and the skill loop; `CHANGELOG.md:59` stays as written

**Taken.** Each test registers counting PermissionRequest, PreTool and PostTool hooks and a counting tool, sends a cut
response calling the tool, then — the positive control — an uncut response with the same call. After the cut iteration
every counter and the tool read 0; after the uncut one they read 1. The control is what makes 0 mean "not reached" rather
than "not wired". The skill loop's test goes through the bound dispatcher (`SlashSkillToolDispatchE2EIntegrationTest`),
the only path on which a skill's call can reach a hook.

**Rejected — rewrite the sentence as "by construction":** the task prefers the test, and "by construction" is what a later
refactor of a refusal path silently stops being.

### D9 — `orca-executor.md`: rewrite §2.2 to what shipped, with the three sentences that lean on it

**Taken.** `docs/design/README.md` §3.1 lets the `Status` line carry implementation state, and §3.2 keeps revision notes
out of a design body (*"정정은 본문에 반영하고 흔적은 지운다"*). `orca-executor.md` is a living design document, not a record
committed from an approved review, so the body is corrected, not annotated:

- `:68` — the §2 premise becomes conditional: a cut response **with no** `tool_use` took the `!hasToolUses()` branch.
- §2.2 (`:98-117`) — new heading and body, sketched in §4.3. One definition, `TruncatedResponses`, is read by four loops:
  the turn, the fork and a skill's loop answer both shapes and stop a streak of refusals through the stall guard (§5);
  `ReActLlmDeriver` only refuses cut calls — it returns no text to mark, and six iterations and its token budget bound it.
  No strategy interface and no continuation were built; the reason continuation was not the default (merge and signature
  blocks) stays, and the reasons for refusing link to `max-tokens-truncation-reporting.md` and this design's record.
- §5 (`:150-162`) — the guard is `StalledIterationGuard`, shared by the turn, the fork and a skill's loop, and a streak of
  refusals names `max_tokens` in its stop message.
- §12 (`:370`) — `TruncationRecoveryStrategy` leaves the opt-in SPI list.
- §13 item 3 (`:404`) — continuation is not built and has no seam; building it would replace the refusal and the marker.

**Rejected — mark §2.2 "not built" and keep its text:** §3.2 keeps dead design out of the body, and the document's status
is `IMPLEMENTED`; a reader should find what exists. No inbound link targets §2.2 with a fragment (checked), and
`check-doc-links.py` confirms after the heading changes.

### D10 — Records: a new changelog entry that supersedes, two closures, one registration, and this design as a record

- **`CHANGELOG.md`.** A new `[Unreleased]` entry at the top (§4.3). It says it **supersedes two sentences of #113's entry
  below** and quotes them (`:81`, `:83-85`); #113's entry stays byte-identical. Precedent: #104's entry supersedes three
  sentences of #92's rather than rewriting it. The additive public type `StalledIterationGuard` is named, and so is the
  design record. No renames, so `docs/migration/rename-maps.md` is untouched.
- **Backlog.** `L-22` and `L-23` get `### 닫힘 (2026-09-11, #115 · #117)` blocks that say, per rules two, three and five,
  what held and what did not, each linking the record (§4.3); `L-25` is registered (§8 F-1); the register title and the
  `docs/backlog/README.md` index row are recounted from the body.
- **This design is committed as a record** at `docs/design/agent-execution/skill-loop-truncation-and-fork-stall.md`, beside
  `max-tokens-truncation-reporting.md`, whose decisions it extends to the next loops; the code it decides is the loops,
  which is why #113's record sits in `agent-execution/` too. It follows the practice on `main` (`babfc1a`, `784ae2f`,
  `5606b04`) and the task's amended section on #122:
  - a `Status` header written at commit time above the body (§4.3);
  - the body as approved in the final design review round, byte-exact, including its test strategy and `file:line`
    citations — which #122 decided a committed record keeps;
  - a closing section, `## 11. After the build — departures, corrections, and what went to the backlog`, appended after
    the build;
  - one row in the `agent-execution` table of `docs/design/README.md` §2, after `max-tokens-truncation-reporting.md`'s
    (`:43`). §3 of that file is not touched — the `design-record-exemption` run owns it. `docs/design/` is not a
    translation target and its README has no twin; `mkdocs.yml` has no explicit nav to extend.

  **Rejected — no record.** Revision 1's only reason, that this batch must not edit `docs/design/README.md`, was withdrawn
  by the task's amendment at 04:38Z, and nothing else holds. The PR body is not in the repository. `docs/design/README.md`
  opens by saying decisions, their reasons and the alternatives rejected live in that directory, and these ten include one
  that overturns a registered trigger (D2 against L-22) and one that settles how a persisted enum is read by eleven readers
  (D3). The L-22 and L-23 closures, the changelog entry and `orca-executor.md` §2.2 each need a document to point at, as
  #113's did.

---

## 4. Concrete changes, by module and file

### 4.1 `aimon-core` — main

| File | Change |
|---|---|
| `agent/budget/StalledIterationGuard.java` | **New** — §5.1 |
| `agent/budget/TruncatedResponses.java` | Javadoc only. "One definition for both ReAct loops" becomes the four loops (turn, fork, `LlmSkillExecutor`, `ReActLlmDeriver`). The final-answer bullet says it ends `TRUNCATED` on the two executors, carries the marker in a skill's result, and is discarded by the deriver; the tool-call bullet names `StalledIterationGuard` as what ends a streak on the turn, the fork and the skill loop |
| `agent/budget/package-info.java` | A bullet for `StalledIterationGuard`; the `TruncatedResponses` bullet stops saying "both ReAct loops" |
| `agent/budget/CompletionReason.java` | `ERROR`'s javadoc adds that an execution stopped by `StalledIterationGuard` ends with it. No new constant |
| `agent/impl/orca/OrcaAgentExecutor.java` | `MAX_CONSECUTIVE_STALLED_ITERATIONS` = the guard's constant (javadoc says it is kept for callers reading it here, like `TRUNCATION_MARKER`). The local counter (`:1559-1561`) becomes a `StalledIterationGuard`. `:1818-1828` becomes: if `cancellationSignal.isCancelled()` reset, else if `guard.recordToolIteration(toolUseResults, truncated)` emit `IterationCompleted` and return `handleStalledIteration(scope, iterationCount, accumulatedTokens, guard)`. `isStalledIteration` (`:2554`) delegates to `StalledIterationGuard.isStalled`. `handleStalledIteration` (`:2580`) takes the guard, uses `guard.stopMessage()`, and keeps its WARN wording |
| `subagent/execution/DefaultSubagentExecutor.java` | A guard beside `iterationCount` in `runReActLoop`; after the tail check at `:484-486`, `if (guard.recordToolIteration(toolUseResults, truncated)) return createStalledResult(...)`. New private `createStalledResult`: WARN `Subagent '{}' stalled-iteration guard tripped: consecutiveStalledIterations={}, iterations={}, tokens={}`, then `createFailureResult(..., guard.stopMessage(), ..., ERROR)`. Javadoc: class (`:99-105`) says the fork shares the turn's guard and drops "one difference remains"; `runReActLoop` (`:368-369`) adds the guard to its stop list; `refuseTruncatedToolUses` (`:824-826`) replaces "a fork has no stalled-iteration guard" |
| `skill/execution/llm/LlmSkillExecutor.java` | A `private static final Logger log`. In the loop, after the max-iterations check and the assistant commit: `truncated ? refuseTruncatedToolUses(skill, response, iterationCount) : toolDispatcher.dispatch(...)`; commit the results; `if (guard.recordToolIteration(results, truncated))` return the stall failure; then the next call. After the loop: if the last response is cut, flag, WARN, commit with traces, return `success(flagged, metadata)`. Class javadoc: a paragraph on `max_tokens` and the guard, and the stall in the flow list |
| `memory/deriver/ReActLlmDeriver.java` | In `derive`, after the no-tool-calls `break` (`:206`): if `isTruncated(response)`, WARN and answer each call with `refusal`; otherwise the `invokeTool` loop (`:215-218`). Class javadoc: one paragraph |
| `tools/task/TaskTool.java` | `formatSubagentResult` (`:613-630`) appends the D6 line after the summary; its javadoc gives the placement reason (the CLI parser). No import, nothing above `:613` changes |
| `llm/streaming/ChunkAggregator.java` | `parseArguments`' catch (`:294-296`) logs per D7; imports `com.fasterxml.jackson.core.JsonProcessingException` |

`SubagentExecutionResult.java`, `CompletionReason`'s values, `TaskResult`, both codecs, `AgentStepResult`, `StepOutcome`
and every workflow class: **no change** (D3's table).

### 4.2 `aimon-llm-anthropic` — test comment

`AnthropicThinkingBudgetsTest.budgetIsClampedBelowMaxTokens` (`:72`): *"The default maxTokens of 4096 makes this the common
case rather than an edge: HIGH clamps whenever the call's LlmModel sets no maxTokens — an agent definition with no
model.maxTokens — and AnthropicConfig.Builder.maxTokens(int) was not used."* Split to stay within the formatter's width.

### 4.3 Docs

**`docs/design/agent-execution/orca-executor.md`** (Korean canonical, no twin) — D9. Proposed §2.2:

> `### 2.2 한 정의, 두 모양 — 전략 인터페이스는 만들지 않았다`
>
> 판정은 `StopReason.isTruncated()` 하나이고, 그것을 읽는 곳은 `at.aimon.core.agent.budget.TruncatedResponses` 하나다.
> 도구 호출이 없는 잘린 최종 답은 부분 텍스트에 `[System: response truncated at max_tokens]` 를 붙여 노출한다 — 두 실행기에서는
> `CompletionReason.TRUNCATED` 로 끝나고(`isSuccess() == true`, `isSuccessful() == false`), 스킬 루프(`LlmSkillExecutor`)는 같은
> 마커를 스킬 결과의 텍스트 끝에 붙인다. 도구 호출이 있는 잘린 응답은 어느 호출도 실행하지 않고 호출마다 `max_tokens` 를 말하는
> 오류 결과로 답한 뒤 루프를 잇는다 — 응답은 어느 호출이 잘렸는지 말하지 않기 때문이다. 턴·포크·스킬 루프에서는 그런 응답이
> 이어지면 §5 의 정체 가드가 끝낸다. `ReActLlmDeriver` 는 잘린 도구 호출을 거절하는 것만 같다 — 텍스트를 돌려주지 않으므로 표시할
> 답이 없고, 반복은 여섯 iteration 과 토큰 예산이 끝낸다. `TruncationRecoveryStrategy` 같은 전략 인터페이스와 이어쓰기
> (continuation)는 **만들지 않았다**. 이어쓰기를 기본으로 두지 않은 이유(병합과 서명 블록)는 그대로이고, 호출을 거절하는 이유는
> [`max-tokens-truncation-reporting.md`](max-tokens-truncation-reporting.md) 에, 그 판정이 스킬 루프와 포크의 정체 가드에 닿은
> 결정은 [`skill-loop-truncation-and-fork-stall.md`](skill-loop-truncation-and-fork-stall.md) 에 있다.

**`CHANGELOG.md`** — a new entry at the top of `[Unreleased]`, titled along the lines of *"Agent loop: a skill's loop refuses
a cut tool call too, a fork stops after three stalled iterations, and the Task tool says when a fork's answer was cut"*,
with one bullet per behaviour change:

- the skill loop's refusal, marker and WARN (the client's own WARN still fires), and that a single refused skill call
  shows on no terminal — #115;
- the skill loop's stall guard, as the D5 observable change, naming the side-effect approval gate among its causes — #115;
- the fork's stall guard, as the D3 observable change: `ERROR`, and what reads it (task records, `isComplete()`, the step
  cache, `loopUntilDry`, `completenessCritic`, GraalJS, a fork-mode skill, the Task tool) — #115;
- the stop message's `max_tokens` clause on the turn, the fork and the skill loop, with the plain text unchanged — #115;
- `ReActLlmDeriver` refuses cut calls, and no in-tree code constructs it — #115;
- the Task tool's `Completion reason:` line: after the result, only when not `COMPLETED`, header unchanged, the CLI shows
  it at the end of the rendered result, `getStatus()` unchanged — #117;
- the parse WARN no longer logs arguments — #117;
- the entry below's *"No permission check and no PermissionRequest/PreTool/PostTool hook runs for a refused call"* is now
  pinned by tests on the turn, the fork and the skill loop — #117;
- new public `at.aimon.core.agent.budget.StalledIterationGuard`; `OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS`
  keeps its name and value;
- records: `orca-executor.md` §2.2, the test comment, `L-22` and `L-23` closed, `L-25` registered; the design is
  `docs/design/agent-execution/skill-loop-truncation-and-fork-stall.md`;
- **supersedes two sentences of #113's entry:** *"`TaskTool` still prints `Status: SUCCESS`; …"* and *"A fork has no
  stalled-iteration guard, so a fork whose every response is cut repeats the refusal until its `maxIterations` stops it …"*.

**`docs/backlog/llm-config-surface-open-items.md`** — owned only for `L-22`, `L-23` and the reserved `L-25`, `L-26`:

- **`L-22` 닫힘**, linking the record.
  - *무엇을* is met (D1).
  - The three *정할 것*: the stall guard (D5), how the marker shows (D1), and the deriver (D2 — changed, with the
    rule-seven reason and rule six's wording).
  - Rule five: the prescription was applied after failing tests (§7.0).
  - Rule three: say whether the blocking-path shape was measured (§7.12), and that the loop cannot be interrupted, which
    made the item heavier than it said.
  - *남은 것*: a single refused skill call shows on no terminal, because the command path emits no lifecycle events for any
    skill call (§8.1 F-7).
- **`L-23` 닫힘**, linking the record. The first prescription was taken (D3), and the closure says why the second was not;
  `ERROR`, with the readers checked; the observable change beyond truncation. Rule three: unmeasured whether a model keeps
  issuing cut calls after a refusal (§9 Q2).
- **`L-25` 등록** — §8 F-1, placed here as `L-23`'s pair, the reason `L-16`, `L-22` and `L-23` are in this register.
- **Title** recounted from the body. On this branch alone: 25 items, 13 open, 12 closed.

**`docs/backlog/README.md`** — the `llm-config-surface-open-items.md` index row (`:360`), recounted from the body
(`25 | 13 | 12 | 0` on this branch alone). The sibling `anthropic-default-model-followups` changes the same counts; merge
recounts.

**`docs/design/agent-execution/skill-loop-truncation-and-fork-stall.md`** — new, D10. Its first lines are a `Status`
header in the shape of `max-tokens-truncation-reporting.md`'s, written at commit time:

- `Status: IMPLEMENTED` and what it covers — the new `StalledIterationGuard`; the cut-response and stall paths of
  `LlmSkillExecutor`, `DefaultSubagentExecutor`, `OrcaAgentExecutor` and `ReActLlmDeriver`; the Task tool's result;
  `ChunkAggregator`'s WARN; one test comment in `aimon-llm-anthropic`; `orca-executor.md` §2.2; backlog `L-22` and `L-23`
  (closed) and `L-25`. Sources: #115 and #117.
- A pointer to §11 as where the build departed, and a statement that everything between the header and §11 is the body as
  approved in the final design review round, byte-exact, its `file:line` citations at `main` `c561e17`.
- A note that the review transcripts and run records the body names (`review-*.md`, `TASK.md`, `$RUN_DIR/build/`) are not
  in the repository, and that §11 reproduces what was measured.
- A line saying it is in English, like its siblings, and that `docs/design/` is not a translation target.

Then this document's body, unedited. After the build, `## 11. After the build — departures, corrections, and what went to
the backlog` is appended — departures, places the body is wrong, and the measurements (§7.0's pre-fix run, §7.11's counts,
§7.12 if run).

**`docs/design/README.md`** — one row in §2's `agent-execution` table, after `max-tokens-truncation-reporting.md`'s (`:43`),
in Korean like its neighbours:

> `` | [`skill-loop-truncation-and-fork-stall.md`](agent-execution/skill-loop-truncation-and-fork-stall.md) | 그 판정이 스킬 루프와 `ReActLlmDeriver` 에 닿는 자리 — 포크와 스킬 루프가 턴의 정체 가드를 함께 쓰는 이유, 멈춘 포크를 새 값이 아닌 `ERROR` 로 두고 열한 독자가 그것을 읽는 방식, `Task` 도구가 잘린 포크를 말하는 줄 | ``

No other part of that file changes; §3 belongs to `design-record-exemption`.

---

## 5. Data and interface shapes

### 5.1 The new type

```java
package at.aimon.core.agent.budget;

/**
 * The death-spiral guard every tool loop with one shares: a turn, a subagent fork and a skill's loop. Not thread-safe;
 * one instance per execution, like BudgetTracker.
 */
public final class StalledIterationGuard {

    /** Consecutive stalled iterations tolerated before the loop stops. */
    public static final int MAX_CONSECUTIVE_STALLED_ITERATIONS = 3;

    /** An iteration is stalled when it issued at least one tool call and every result is an error. */
    public static boolean isStalled(List<ToolUseResult> toolUseResults) { ... }

    /**
     * Records one completed tool iteration. A non-stalled iteration resets the streak.
     *
     * @param refusedAtMaxTokens whether this iteration's calls were refused because the response was cut at max_tokens
     * @return true iff this iteration brings the streak to the threshold
     */
    public boolean recordToolIteration(List<ToolUseResult> toolUseResults, boolean refusedAtMaxTokens) { ... }

    public void reset() { ... }

    public int getConsecutiveStalledIterations() { ... }

    /** The failure message of an execution this guard stopped (D4). Meaningful after recordToolIteration returned true. */
    public String stopMessage() { ... }
}
```

State: an `int` streak and a `boolean` "every iteration in the streak was refused", both cleared by a non-stalled iteration
and by `reset()`. Names follow `BudgetTracker`'s `record*` vocabulary; the build may adjust a name, not the shape.

### 5.2 Text that changes

**Stop message** (turn, fork, skill):

```
Execution aborted: 3 consecutive tool-only iterations made no progress (all tool calls failed)
Execution aborted: 3 consecutive tool-only iterations made no progress (all tool calls failed) — each of those responses was cut off at max_tokens, and its tool calls were refused
```

The first line is today's text, pinned byte for byte (§7.3); the second is used only when every stalled iteration was a
refusal.

**New WARNs:**

| Where | Text |
|---|---|
| Skill, cut tool calls | `Skill '{}' response truncated at max_tokens in iteration {} with tool calls {}: none of them is run; each is answered with an error result{}` |
| Skill, cut final answer | `Skill '{}' final answer truncated at max_tokens after {} tool iterations; returning flagged partial answer{}` |
| Skill, guard | `Skill '{}' stalled-iteration guard tripped: consecutiveStalledIterations={}, iterations={}, tokens={}` |
| Fork, guard | `Subagent '{}' stalled-iteration guard tripped: consecutiveStalledIterations={}, iterations={}, tokens={}` |
| Deriver, cut tool calls | `ReActLlmDeriver response truncated at max_tokens in iteration {} for {} with tool calls {}: none of them is run; each is answered with an error result{}` |
| `ChunkAggregator` | `Failed to parse accumulated tool_call arguments as JSON: {}{} ({} chars); the arguments are not logged` |

The trailing `{}` on the truncation WARNs is `TruncatedResponses.reasoningClause`. In `ChunkAggregator`'s, the second `{}`
is ` at offset N`, left empty when there is no location or N is negative (D7).

### 5.3 The Task tool's output

A cut fork:

```
=== Subagent Task Result ===
Subagent: explorer
Task: map the module
Status: SUCCESS
Iterations: 4
Tokens: 51234

Result:
The module has three packages: api, impl, and

[System: response truncated at max_tokens]
Completion reason: TRUNCATED (the subagent's final answer is incomplete)
```

A fork stopped by the guard ends with `Status: FAILURE`, the stop message as the result, then `Completion reason: ERROR`. A
`COMPLETED` result is byte-identical to today.

### 5.4 What does not change

`CompletionReason`'s values; `SubagentExecutionResult`, `SkillExecutionResult`, `SkillExecutionMetadata`, `TaskResult`,
`StepOutcome` and their codecs; every persisted or wire name; `getStatus()` on both result types;
`OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS`' name and value; the refusal text; the marker text; the executors'
existing truncation WARNs.

---

## 6. Failure modes

| # | Situation | What happens |
|---|---|---|
| F1 | Blocking path: a cut `tool_use` arrives as a tool call with `{}` or partial input | Skill and deriver refuse it (D1, D2) — the design holds |
| F2 | Blocking path: the cut block is dropped, leaving text plus `MAX_TOKENS` | The skill marks a cut final answer — the design holds |
| F3 | Blocking path: the SDK throws converting a malformed block | `LlmClientException`: the skill fails with its existing message and the deriver breaks, as today — no regression |
| F4 | A provider reports a cut as `UNKNOWN` | Not detected, as `TruncatedResponses` documents |
| F5 | Three refused responses in a row | Turn and fork end `ERROR`, the skill fails; the message names `max_tokens`; exactly three LLM calls |
| F6 | A mixed streak (refusal, failing tool, refusal) | Trips at three with the plain message |
| F7 | A successful call before the threshold | The streak resets and nothing trips (tested on fork and skill) |
| F8 | Cancellation lands on a would-be third stalled iteration | Turn `INTERRUPTED` (existing test); fork `INTERRUPTED` — the guard sits after the tail check (tested); the skill loop has no cancellation |
| F9 | `maxIterations` below 3 | `MAX_ITERATIONS` (fork) or the max-iterations failure (skill) comes first, as today |
| F10 | A budget exhausted mid-streak | The fork's budget check at the top of the next iteration stops it with the budget's reason |
| F11 | Fallback-path permission violation in a skill | Thrown, immediate `Permission denied` failure — never counted |
| F12 | A workflow resumes a run with a stalled step | Not cached, so re-run; it may stall again, like any `ERROR` step |
| F13 | A background fork stalls | Task `FAILED`; the notification carries the stop message within its 500-character cap |
| F14 | A fork's approval gate denies the same side-effect call three iterations running | Now `ERROR` — the named D3 change |
| F15 | A user declines a slash skill's mutating call three iterations running | The skill fails with the stop message — the named D5 change |
| F16 | A single refused call in a slash skill | Nothing on the terminal; the WARN only (D1, F-7) |
| F17 | Task tool, `COMPLETED` result | Byte-identical; the CLI parser still matches |
| F18 | Task tool, non-`COMPLETED` result | One trailing line, which the CLI shows at the end of the rendered result |
| F19 | Parse failure with a non-Jackson exception, or a Jackson location with offset `-1` | Class name and length, no offset clause |
| F20 | Deriver under a persistent cut | Each refusal spends an iteration and tokens; the loop ends at six or at the budget; nothing is persisted from a cut response |
| F21 | Turn with streaming overlap on | Refusal and eager-discard behaviour unchanged; the refused iteration counts, as now |

---

## 7. Test strategy

Every new behaviour is pinned with a stubbed `LlmClient` or chunk stream, as #113's tests are.

### 7.0 Order (backlog rule five), and the existing tests the wider guards could flip

1. Add `StalledIterationGuard` and its unit test, with no loop wired to it (as #113 added `TruncatedResponses` first).
2. Write §7.2–§7.10 and run them against the unchanged loops. Record in `build/measurements.md` which fail and on what
   value. Expected to fail: the skill's cut call runs, and its cut answer has no marker and no WARN; the skill's and the
   fork's stall tests do not trip; the refused-only stall message has no clause; the deriver runs a cut call; the Task tool
   has no `Completion reason:` line; the parse WARN contains `SECRETTOKENxyz`; the skill's hook-count test sees one
   PreTool call on the cut response. Expected to **pass** before the fix: the turn's and the fork's hook-count tests (they
   pin a property that already holds) and the plain-message byte test.
3. Implement, then re-run the same classes to green.

**Existing tests the wider guards could flip.** D3 and D5 stop any fork or skill whose tool calls all fail for three
consecutive iterations, not only cut ones. A test that scripts such a streak and expects the loop to carry on would flip.
Checked by reading — this design read the first two, and the review read all five — none does:

- `LlmSkillExecutorTest.shouldFailWhenMaxIterationsExceeded` — two failing iterations, and `max-iterations` (2) stops it first;
- `SlashSkillToolDispatchE2EIntegrationTest`'s three cases — one tool iteration each;
- `DefaultSubagentExecutorSkillApprovalTest` and `DefaultSubagentExecutorSideEffectFilterTest` — one call, then `done`;
- `OrcaAgentExecutorStalledIterationTest` and `OrcaAgentExecutorTerminalSessionStateTest` — the turn's guard, whose
  threshold, predicate and cancellation rule D3 keeps.

Reading is not the gate. Before the build claims nothing else changed, the full `aimon-core` and `aimon-workflow-graaljs`
suites run — both inside `checkAll` (§7.11) — with their counts reported per module. A test that flips because it encoded a
loop carrying on past three all-error iterations is reported in `build/deviations.md` as an instance of the named observable
change, not quietly rewritten.

### 7.1 `agent/budget/StalledIterationGuardTest` (new)

- `isStalled`: empty → false; all errors → true; one success → false.
- Two stalled then one success resets; three stalled trips on the third (the return is false, false, true).
- The plain message equals today's literal byte for byte, with the count.
- Three refused → the clause; refused, plain, refused → no clause; after a reset, the refused flag starts fresh.

### 7.2 `OrcaAgentExecutorTruncationTest` (existing class)

- **Hook-count test (D8):** counting PermissionRequest, PreTool and PostTool hooks on the runtime's registry (a
  `createContext` overload that takes a hook registry), a cut call then an uncut call to `CountingTool`. After: tool = 1,
  PreTool = 1, PostTool = 1, PermissionRequest = 1, and the first iteration's `tool_result` is the refusal.
- `threeCutToolResponsesInARowTripTheStalledIterationGuard` gains an assertion that the error message ends with the
  `max_tokens` clause.

### 7.3 `OrcaAgentExecutorStalledIterationTest` (existing)

`threeConsecutiveFailingIterationsTripTheGuard` gains an exact-equality assertion on the plain message, pinning that D4
changed nothing but the refused-only case.

### 7.4 `DefaultSubagentExecutorTruncationTest` (existing)

- **Hook-count test (D8)** — the fork fixture's `execute` gains an overload that takes a hook registry.
- **Three cut tool responses → `ERROR`:** exactly three LLM calls, tool invocations 0, the message's clause, three WARNs
  naming `max_tokens` with the guard's own WARN not among them, and `[ended: …]` in the stream.

### 7.5 `DefaultSubagentExecutorStalledIterationTest` (new; mirrors the turn's)

- Three all-error iterations with a failing tool → `ERROR`, `isSuccess()` false, plain message, iteration count 3, three
  LLM calls; OnStop fired with `success=false`.
- **Recovers before the threshold:** fail, fail, succeed, final answer → `COMPLETED`.
- A parent cancellation signal tripped by the tool on the third failing iteration → `INTERRUPTED`, not `ERROR` (F8).
- `maxIterations(2)` with failing tools → `MAX_ITERATIONS` (F9).

### 7.6 `skill/execution/llm/LlmSkillExecutorTruncationTest` (new)

Fixtures follow `LlmSkillExecutorTest` (queue-backed stub client, `CountingTool`, a recording `SkillToolDispatcher`):

- **Fallback path:** a cut response calling `CountingTool`, then an answer → tool 0; the second call's last message holds the
  refusal; WARN names the skill, `iteration 1`, the tool name; success.
- **Dispatcher path:** the bound dispatcher is never called for the cut response and is called once for an uncut one.
- **Cut final answer:** `isSuccess()`, the response ends with the marker, WARN names the skill.
- **Uncut, or no stop reason:** no marker, no WARN.
- **Three cut tool responses:** failure, clause in the message, metadata iteration count 3, three LLM calls, tool 0.
- **Recovers before the threshold:** a failing tool twice, then a success, then an answer → success.
- **Reasoning clause:** present on both WARNs when the cut response reports reasoning tokens, absent otherwise.

### 7.7 `SlashSkillToolDispatchE2EIntegrationTest` (existing)

A slash skill whose first scripted response is a cut call to `CountingTool`, then an uncut call, then text. PreTool = 1,
PostTool = 1, PermissionRequest = 1, executions = 1 (D8 on the skill loop).

### 7.8 `ReActLlmDeriverTest` (existing)

- A cut response calling `deriver.observation.create` with complete-looking input, then text → nothing in the store, the
  refusal in the next conversation, a WARN naming `max_tokens`, `created` empty.
- A cut text-only response → the loop ends with an empty result, as today.

### 7.9 `TaskToolTest` (existing)

- A `TRUNCATED` success result → output contains `Completion reason: TRUNCATED (the subagent's final answer is incomplete)`
  after `Result:`, and contains the contiguous header `Status: SUCCESS\nIterations: N\nTokens: N\n\nResult:` — the
  comment names `SubagentResultDisplayHook` as the reader that needs it.
- A `COMPLETED` result → no `Completion reason:`, header unchanged.
- An `ERROR` failure → `Completion reason: ERROR` with no gloss.

### 7.10 `ChunkAggregatorTest` (existing)

A `ListAppender` on `ChunkAggregator`'s logger. `{"command": SECRETTOKENxyz}` → the WARN contains `JsonParseException` and
`27 chars` and **does not contain** `SECRETTOKENxyz` (this input fails against a fix that only drops `json=`).
`{"path": "/tmp/sea.txt"` → `JsonEOFException`, `at offset 23`, `23 chars`.

### 7.11 Gates

`./gradlew format`, then `./gradlew checkAll` — report tests run, failed and skipped from the build output, per module that
ran, `aimon-core` and `aimon-workflow-graaljs` included. The four doc checks — `check-doc-links.py`,
`check-backlog-registers.py`, `check-translation-staleness.py`, `check-translation-structure.py` — run with the record and
its index row in the tree.

### 7.12 Optional live probe

One non-streaming Anthropic request in #113's shape — `claude-haiku-4-5`, a forced `write_file` tool, `max_tokens: 60`,
`"stream": false` — with the key passed through a process-substitution header, never exported. Record the request shape,
status, request id and billing in `build/measurements.md`, and what `content[]` holds for the cut block. **No decision
depends on it:** F1–F3 show the design holds for each possible shape. Its value is the L-22 closure's rule-three sentence.
Skip it, and write "unmeasured", if reading the key is refused.

---

## 8. Findings outside the issues, and merge notes

### 8.1 Findings (for `build/deviations.md` and the PR body)

| # | Finding | Disposition |
|---|---|---|
| **F-1** | **The background half of #117 item 1.** `AgentOutputTool.formatAgentResult` prints `TaskResult.getStatus()` — `SUCCESS` for a `TRUNCATED` task — and not the reason `TaskResult` already stores (`AgentOutputTool.java:378`). The completion notification labels it `COMPLETED` and head-cuts the summary to 500 characters (`DefaultSubagentExecutionManager.java:965-995`), cutting the marker off a long answer; `AgentOutput` itself tail-keeps, so the marker survives there | **Register `L-25`.** Same defect as #117 item 1, on the path a background fork takes; neither file is this run's |
| F-2 | A turn that runs a slash skill whose final answer was cut ends `COMPLETED`, with the marker in the text (D1) | Deviation and PR body; not registered — carrying a reason needs `command/**` and `CommandExecutionResult`, and no turn-level reader in tree branches on `TRUNCATED` (the CLI's `OutputFormatter` checks only `INTERRUPTED`) |
| F-3 | `thinking-reporting-and-dialect-records.md:1480` (*"a fork has no guard and repeats until its `maxIterations` (L-23)"*) becomes false, and `:1517` and `:1616-1617` speak of `L-22` and `L-23` as open | PR body — no run in this batch owns that record |
| F-4 | The CLI's `SubagentResultDisplayHook` parses `TaskTool`'s text, and its test never feeds it real `TaskTool` output; its status word stays `SUCCESS` for a cut fork | PR body — the core side is pinned by §7.9 |
| F-5 | A slash command, and so a skill's loop, cannot be interrupted | Deviation — pre-existing; D5 bounds the refusal case |
| F-6 | `max-tokens-truncation-reporting.md` §8 and §11.3 describe the Task tool line and `L-22`/`L-23` as open | None — an approved record, historically true |
| F-7 | A refused call in a skill's loop shows on no terminal: the command path's dispatcher emits no `ToolUseStarted`/`ToolResultReady` for any skill call (`OrcaAgentExecutor.java:2079-2088`), and the REPL's tool-call line is a PreTool hook. Only the WARN, and after a streak the stop message, reach a person | Deviation, the L-22 closure's *남은 것* and the changelog; not registered — the command path has no event channel for a skill's calls, refused or run, so it is not a truncation gap |

### 8.2 Merge notes (PR body)

- **`orca-executor.md:325`**, cited by `docs/design/llm/reasoning-delta-stream.md:842`, moves with the §2.2 rewrite.
- **`TaskTool.java:553`** (`.defaultModel(`), cited by `anthropic-default-model-followups`' `L-20` correction, must not move:
  D6 adds no import and edits only below `:613`. The build confirms with `git diff -U0` on the file.
- **Dated line citations** into `OrcaAgentExecutor.java`, `DefaultSubagentExecutor.java`, `LlmSkillExecutor.java`,
  `ReActLlmDeriver.java` and `ChunkAggregator.java` — in `L-16`, `L-22` and `L-23`'s bodies, in
  `max-tokens-truncation-reporting.md` (at `9b642cc`) and in `reasoning-delta-stream.md` /
  `reasoning-model-enablement.md` — drift as designed. The build lists the ones its diff moves.
- **`docs/design/README.md` §2**, the `agent-execution` table: this run adds one row after `:43`. Rows other runs add to the
  same tables conflict and are resolved at merge, as the task says. §3 is `design-record-exemption`'s and is not touched.
- **The committed record's own citations** stay at `c561e17`, byte-exact, as its header says; they are not recounted at merge.
- **`llm-config-surface-open-items.md`'s title and its `docs/backlog/README.md` row** conflict with
  `anthropic-default-model-followups` (`L-20`, `L-24`, `L-27`, `L-28`); recount from the body at merge.
- **`CHANGELOG.md`**: the new entry shifts every line below it.

---

## 9. Open questions

Not resolvable from the task statement or the code; none is silently assumed above.

- **Q1 — What a cut `tool_use` block looks like on the blocking Anthropic path, and on OpenAI's.** Unmeasured. §6 F1–F3 show
  the design does not depend on it; §7.12 can settle the Anthropic half.
- **Q2 — Whether a model keeps issuing cut calls after reading the refusal.** Decides how often the guard trips in practice
  (and whether D4's clause changes what a parent does next); not measurable here without multi-turn live runs. No decision
  depends on it.
- **Q3 — Who corrects `thinking-reporting-and-dialect-records.md` §16.8** (F-3). No run in this batch owns it.
- **Q4 — Should a turn that ran a slash skill end `TRUNCATED` when the skill's answer was cut?** (F-2) It needs changes this
  run may not make; the maintainer decides whether it is worth `L-26`.
- **Q5 — Closed in revision 2.** #122 was answered during the design phase and the task amended; this design is committed
  as a record with its index row (D10).
- **Q6 — `L-25`'s register.** It goes to `llm-config-surface-open-items.md` as `L-23`'s pair, because that is the register
  this run holds IDs in. If the maintainer would rather track it elsewhere, the move is theirs.

---

## 10. Acceptance criteria → where each is met

| # | Criterion | Met by |
|---|---|---|
| 1 | A skill's loop treats a cut response as decided in 1, pinned; `ReActLlmDeriver` decided and stated | D1, D2; §7.6, §7.7, §7.8; L-22 closure |
| 2 | A fork cannot repeat refused iterations to `maxIterations`, pinned; `CompletionReason` decided and every reader checked | D3 (readers table), D4; §7.4, §7.5 |
| 3 | The Task tool's output says when a fork's answer was cut, pinned on a `TRUNCATED` result | D6; §7.9 |
| 4 | #117 items 2–5 | D7 + §7.10; D8 + §7.2, §7.4, §7.7; D9; §4.2 |
| 5 | `L-22`/`L-23` closed only if met; title and index row recounted from the body | D10 — closures that link the committed record; §4.3 |
| 6 | Changelog entries for every behaviour change; `checkAll` green with numbers; four doc checks pass | §4.3; §7.0, §7.11 — the full suites reported per module, and the doc checks run over the new record and its `docs/design/README.md` row |

---

## 11. After the build — departures, corrections, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 2, and it is not edited to look prescient. Three sources feed this section: the run's `build/deviations.md`, the
two reviews' non-blocking notes, and what was measured while building.*

**No decision in §3 changed.** D1–D10 were built as chosen: the skill loop and the deriver refuse a cut response's
calls, the skill loop marks a cut answer in its text, one `StalledIterationGuard` stops the turn, the fork and the skill
loop, a stalled fork ends `ERROR`, the stop message names `max_tokens` for a streak of refusals, the Task tool prints a
trailing `Completion reason:` line, the parse WARN logs no argument text, the hook claim is pinned by tests, and
`orca-executor.md` §2.2 describes what shipped. What departed is where the fork keeps its guard, one registration the
body left to the maintainer, and wording.

### 11.1 Where the build departed from the body

- **DV-1 — the fork's guard lives in its `LoopContext`, and its check is an `else if` on the tail cancellation check.**
  §4.1 put the guard "beside `iterationCount` in `runReActLoop`" and the check after the tail check as an `if` of its
  own. `runReActLoop` was 148 lines against Checkstyle's `MethodLength` limit of 150, and that placement comes to about
  156. The turn's loop carries a justified `@SuppressWarnings("checkstyle:MethodLength")`; rather than add a second
  one, the guard sits in `LoopContext` beside `budgetTracker` — the fork's other mutable per-execution tracker — and
  the tail reads `if (cancelled) return INTERRUPTED; else if (the guard trips) return ERROR;`, which also puts D3's
  ordering in the shape of the code. The method is now exactly 150 lines. Behaviour is as D3 describes, and
  `DefaultSubagentExecutorStalledIterationTest` pins it.
- **DV-2 — `OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS` is fenced with `// spotless:off`.** Defined by the
  guard's constant, the declaration is 122 characters on one line. Checkstyle's `LineLength` is 120, and the Eclipse
  formatter joins a wrapped line (`join_wrapped_lines`), so the wrap is kept by the toggle Spotless already enables
  (`toggleOffOn()`). Name, value (3) and compile-time-constant status are unchanged. Keeping a literal `3` beside the
  guard's constant was rejected: two definitions of one number is the drift the shared guard exists to prevent.
- **DV-3 — `L-26` is registered** (§8.1 F-2, §9 Q4). The body left for the maintainer whether "a turn that ran a slash
  skill whose answer was cut should end `TRUNCATED`" is worth an item. The build was asked to promote every open
  question whose consequences reach past this run into the named register, under a reserved ID, and this one does: an
  application reading a turn's completion reason sees `COMPLETED`, and changing that needs `command/**`. It is
  registered as a decision item, not decided. With it the register is 26 items, 14 open, 12 closed.
- **DV-4 — the CHANGELOG's hook bullet says what the tests pin** (review 2, note 1). §4.3's draft said all of #113's
  *"No permission check and no PermissionRequest/PreTool/PostTool hook runs for a refused call"* is pinned on the turn,
  the fork and the skill loop. The tests count the three hooks and the tool on all three loops. The permission
  (allow-list) check is counted on the skill loop only: the slash-skill E2E test's allow-list entry carries a pattern,
  so the check must ask a counting `ToolPermissionSubjectAware` tool for its subject — no read after the cut iteration,
  at least one after the uncut one. ~~The turn and the fork pass an empty allow-list, the validator returns before it
  reads anything, and no positive control is possible there; the bullet says so.~~ *(2026-09-11, #133: not true of the
  fork — §12.1.)*
- **DV-5 — `orca-executor.md` wording.** §2.2 states the reason continuation is not the default (merge and signature
  blocks) rather than §4.3's "그대로이고", because the rewrite removes the paragraph that word pointed at. The `:68`
  sentence gained a clause sending the tool-call shape to §2.2. §1.1's diagram is untouched: its `else executeToolUses`
  line is incomplete, not false, and D9 did not list it.
- **DV-6 — `TaskTool`'s javadoc change sits above `:613`.** §4.1's row says both "its javadoc gives the placement reason"
  and "nothing above `:613` changes", and the method's javadoc is at `:602-612`. The javadoc was edited. What the second
  clause protected holds: `.defaultModel(` is still `TaskTool.java:553`.
- **DV-7 — tests beyond §7.** `LlmSkillExecutorTruncationTest` also pins D5's change beyond truncation (three iterations
  of a failing tool fail the skill with the plain message); `TaskToolTest` pins a `COMPLETED` result as the whole
  string; `ChunkAggregatorTest` also asserts `at offset 12` for the leaking-token input; and
  `DefaultSubagentExecutorStalledIterationTest` asserts `getStatus()` reads `FAILURE` and OnStop's final answer is the
  stop message.
- **DV-8 — a comment corrected in passing.** The turn's guard comment said a mis-counted cancellation would "finalise
  the turn as STALLED". There is no such reason; it now says "as a stall (ERROR)".
- **DV-9 — the dated note under the backlog index was not extended.** Earlier records appended each change of the
  `llm-config-surface-open-items.md` row to the blockquote below `docs/backlog/README.md`'s index. This work changed the
  row only: the rest of that file belonged to another run of the same batch. The note stays historically true.

### 11.2 Where the body is wrong

- **C-1 — §6 F1 and §9 Q1 left the blocking-path shape open.** It is measured (§11.4): a cut `tool_use` arrives as a
  well-formed object holding only the arguments that finished. F1 is the case that happened.
- **C-2 — §4.1's `DefaultSubagentExecutor` row** places the guard where it does not fit (DV-1).
- **C-3 — §4.1's `TaskTool` row** contradicts itself (DV-6).
- **C-4 — §4.3's changelog bullet on the hook claim** claims more than §7's tests pin (DV-4).
- **C-5 — §8.1 F-2's "not registered", and §9 Q4** (DV-3).
- **C-6 — §4.3's "On this branch alone: 25 items, 13 open, 12 closed"** and the index row `25 | 13 | 12 | 0` are
  26 / 14 / 12 with `L-26`.

### 11.3 The §8.1 findings and §9 questions, and which went to the backlog

The backlog items are in [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md); each links
back here.

| Item | Now | Why |
|---|---|---|
| F-1 — `AgentOutput` prints `SUCCESS` for a `TRUNCATED` background task, and the completion notification cuts the marker off a long answer | backlog `L-25` | #117 item 1's defect on a background fork's path; neither file was this work's. Re-read at `c561e17`: `AgentOutputTool.formatAgentResult` prints `TaskResult.getStatus()`, and `DefaultSubagentExecutionManager.truncateDetail` keeps the first 500 characters |
| F-2 / Q4 — a turn that ran a cut slash skill ends `COMPLETED` | backlog `L-26` | DV-3 |
| F-3 / Q3 — `thinking-reporting-and-dialect-records.md` §16.8 still says a fork has no guard, and the same record speaks of `L-22`/`L-23` as open | here, the `L-23` closure, and the PR body | No run in the batch owned that record, and both reserved IDs are used |
| F-4 — the CLI's `SubagentResultDisplayHook` parses `TaskTool`'s text, and its test never feeds it real output | here and the PR body | Review 2 adds that `OutputFormatter.displaySubagentResult` colours every summary line by the status word, so the new line prints in the success colour under `✓ SUCCESS` for a cut fork. Cosmetic; the file belonged to another run |
| F-5 — a slash command, and so a skill's loop, cannot be interrupted | here | Pre-existing; D5's guard bounds the refusal case |
| F-6 — `max-tokens-truncation-reporting.md` §8 and §11.3 describe the Task tool line and `L-22`/`L-23` as open | none | An approved record, historically true |
| F-7 — a single refused call in a skill's loop shows on no terminal | the `L-22` closure and the CHANGELOG | The command path has no event channel for any skill call, refused or run |
| Q1 — the blocking-path shape | measured (§11.4) | |
| Q2 — whether a model keeps issuing cut calls after a refusal | the `L-23` closure | Not measurable without multi-turn live runs; no decision depends on it |
| Q6 — `L-25`'s register | `llm-config-surface-open-items.md` | As proposed |

Review 2's note on `TurnVocabularyArchitectureTest` was checked: nothing added under `at.aimon.core.subagent..` or
`at.aimon.core.tools.task..` is named for a turn (`createStalledResult`, `stalledIterationGuard`), and the rule ran in
`checkAll` (§11.4).

### 11.4 What was measured

**Rule five, before the fix.** `StalledIterationGuard` and its unit test went in first, wired to no loop, and every §7
test ran against the unchanged loops: 126 tests in ten classes, 15 failed, 0 skipped. Each failure is one §7.0 predicted,
on the value it named:

- the skill loop — a cut call ran on the fallback path (`1` execution); the bound dispatcher was handed the cut call
  (`["t1", "t2"]` where `["t2"]` was expected); a cut answer had no marker; three cut tool responses and three failing
  tool iterations both ended in success; the reasoning-clause test found no `max_tokens` WARN at all;
- the slash-skill E2E test — `[1, 1, 1, 1, 1]` after the cut iteration, for PermissionRequest, PreTool, PostTool, the
  tool and the allow-list subject reads;
- the deriver — an observation was persisted from the cut call;
- the fork — both stall tests ended `COMPLETED`;
- the turn — the refused-only stop message had no clause;
- the Task tool — no `Completion reason:` line for `TRUNCATED` or for `ERROR`;
- the parse WARN — it held Jackson's message, `Unrecognized token 'SECRETTOKENxyz': …`, and no `(N chars)`.

As §7.0 predicted, the turn's and the fork's hook-count tests, the plain-message byte test, the fork's recovery,
cancellation and `maxIterations(2)` tests, the skill's uncut-answer and recovery tests and the `COMPLETED` Task tool test
passed before the fix. After it, the same ten classes were 126 of 126 green.

**The live probe (§7.12).** One billed request, 2026-09-11T05:48:54Z, `POST https://api.anthropic.com/v1/messages`.

- *Request:* `model: claude-haiku-4-5`, `max_tokens: 60`, `stream: false`, one tool `write_file(path, content)` with
  both properties required, forced by `tool_choice`, and one user message asking for a 400-word poem about the sea in
  `/tmp/sea.txt`. The key was read into a process-substitution header and never printed or exported.
- *Response:* HTTP 200, `request-id: req_011Cew8JyrxugAE6PL7atguf`, served as `claude-haiku-4-5-20251001`,
  `stop_reason: max_tokens`, `output_tokens: 60`.
- *`content[]`:* one block, `{"type": "tool_use", "name": "write_file", "input": {"path": "/tmp/sea.txt"}, …}`.

The cut block is neither dropped, nor empty, nor unparsable: it is a well-formed object holding the argument that
finished and missing the required one that did not. `AnthropicLlmClient.convertResponse` copies that input into the
`ToolUse` (read from the code, not run on this response), so before this change a skill's loop would have dispatched
`write_file` with a path and no content, and no parse WARN would have fired. #113's streaming probe saw the same prefix
as unclosed JSON. One request is an observation, not a guarantee, and no decision above depends on it.

**The gates.** `./gradlew format`, then `./gradlew checkAll` with no provider key in the environment: BUILD SUCCESSFUL,
240 actionable tasks (115 executed, 125 up to date), and every module's `test` task executed in that run. 21 modules
reported 10,979 tests, 0 failed, 72 skipped — `aimon-core` 8,201 (2 skipped) and `aimon-workflow-graaljs` 41 among
them, so the wider guards of D3 and D5 flipped no existing test. The other skips are `aimon-llm-anthropic` 25 and
`aimon-llm-openai` 17, whose live classes need a key, and `aimon-sandbox-docker` and `aimon-sandbox-kubernetes` 14 each.
The first Checkstyle run failed on DV-2's line, before the toggle. The four doc checks passed with this record in the
tree — `check-doc-links.py`, `check-backlog-registers.py` (`llm-config-surface-open-items.md` at 26 / 14 / 12 / 0),
`check-translation-staleness.py` and `check-translation-structure.py` — and so did `mkdocs build --strict`.

## 12. After #133 — DV-4's reason was false for the fork, and what became of DV-1, DV-2, F-3 and F-4

*Appended 2026-09-11 by #133, which collected three non-blocking notes from this work's build review and the two
findings §11.3 sent to the PR body (F-3, F-4). No decision in §3 changed, and nothing above is rewritten. §11.1 DV-4
carries one struck-through correction mark pointing here, the form [`../README.md`](../README.md) §3.4 allows. The
`file:line` citations below read at the commit that adds this section, as §3.4 dates a line after the boundary; #133
moves none of the lines they name.*

### 12.1 DV-4 — a fork does not pass an empty allow-list

DV-4 said the turn and the fork pass an empty allow-list, so the permission check could be counted on the skill loop
only. That is true of the turn: `OrcaAgentExecutor.dispatchSingleTool` passes `allowedTools(List.of())`
(`OrcaAgentExecutor.java:2498`). It is false of the fork: `DefaultSubagentExecutor.executeSingleTool` passes
`subagent.getAllowedTools()` (`DefaultSubagentExecutor.java:920`), which a definition's `tools:` fills
(`MarkdownSubagentParser.java:73`, `SubagentMetadata.java:144-147`). What was empty was the fork test's fixture, which
declared no tools. With an entry that carries a pattern, `DefaultToolPermissionValidator.isAllowed` gets past its
empty-list return (`:184`) and its pattern-less return (`:195`) and asks the tool for its subject (`:199`) — the
positive control §7.7's test built for the skill loop.

#133 added it for the fork. `DefaultSubagentExecutorTruncationTest.noHookAndNoAllowListCheckRunsForARefusedCall`
(formerly `noHookRunsForARefusedCall`) gives the subagent the entry `Checked(count)` and a tool that counts subject
reads: none after the cut iteration, at least one after the uncut one. Written without the pattern, the same test fails
on that last read (§12.4). The CHANGELOG bullet DV-4 wrote now counts the check on the fork and the skill loop and
names the turn alone as the loop that cannot. The slash-skill E2E test's comment no longer calls the skill loop the
only one with a non-empty allow-list. §11.2 C-4 and §11.4 stand as written.

### 12.2 The other follow-ups

| §11 | What it left | Now |
|---|---|---|
| DV-1 | `LoopContext`'s field comment called the fork's guard *"The turn's death-spiral guard"*, and `DefaultSubagentExecutorStalledIterationTest`'s class comment said a fork stops *"with the turn's guard"* | Both say the guard is the fork's own per-execution instance of the shared `StalledIterationGuard` ([`../../overview/glossary.md`](../../overview/glossary.md) §4) |
| DV-2 | The fence held the constant's fourteen-line javadoc as well as its two-line declaration, so an edit to that javadoc was neither formatted by `./gradlew format` nor flagged by `checkFormat` | The fence holds the declaration only (§12.4) |
| F-3 | `thinking-reporting-and-dialect-records.md` §16.8 and §16.10 still presented a fork as guardless and L-22 · L-23 as open | Corrected in that record's §16.11 |
| F-4 | The CLI rendered a cut fork's `Completion reason:` line in the success colour, and no `aimon-cli` test read real `TaskTool` output | §12.3 |

### 12.3 F-4 — how the CLI shows a cut fork's `Completion reason:` line

**The line gets its own colour.** With colour output on it is yellow when the status is `SUCCESS`, and red, as before,
when it is `FAILURE`. The header, the answer and every line with colour output off are unchanged.
`SubagentResultDisplayHook` splits a trailing reason line off the summary — only when the name is a `CompletionReason`
that is not `isSuccessful()`, the condition `TaskTool` prints the line on — and hands it to
`OutputFormatter.displaySubagentCompletionReason`.

**Rejected:** a warning glyph (still success-coloured without the colour change, and it alters plain-text output); the
summary coloured by the reason (it recolours the answer, not the one line that says it is incomplete); the header
coloured by the reason (the header prints the status word D6 kept `SUCCESS`).

**Pinned on `TaskTool`'s own output.** `SubagentResultDisplayHookTest` runs `TaskTool` over a mocked execution manager
and feeds the hook what it prints. The test supplies values, never labels, so a change to `TaskTool`'s text that the
hook stops parsing fails in `aimon-cli`.

### 12.4 What was measured

**The fork's control, with and without the pattern.** With the entry `Checked(count)`,
`DefaultSubagentExecutorTruncationTest` ran 6 tests with none failed, against fork code that #133 did not change. With
the entry written `Checked`, no pattern, the same class ran 6 and failed 1: the new test, at its last assertion,
*"Expecting actual: 0 to be greater than: 0"*. Its other counts read as they did with the pattern. So the fifth count
measures the allow-list check, and nothing else on the fork's path reads the subject. The slash-skill E2E class (4
tests) and `DefaultSubagentExecutorStalledIterationTest` (4) passed after their comment changes.

**The fence.** The probe was one javadoc line whose leading ` * ` sat two columns left: the formatter normalises that,
and Checkstyle does not check it.

| Step | File | `spotlessCheck` | `spotlessApply` |
|---|---|---|---|
| Control | Old fence; the probe in `TRUNCATION_MARKER`'s javadoc, outside any fence | fails, naming `OrcaAgentExecutor.java` | — |
| The defect | Old fence; the probe in the constant's javadoc, inside the fence | passes | leaves the probe in place |
| The fix | Fence narrowed to the declaration, no probe | passes | changes nothing, run twice; the declaration stays on two lines |
| The fix, probed | Narrowed fence; the probe in the constant's javadoc, now outside the fence | fails, naming `OrcaAgentExecutor.java` | restores the narrowed file byte for byte |

`checkstyleMain` on `aimon-core` passed with the narrowed shape: 1,138 files, no error, none in `OrcaAgentExecutor`.
The file keeps its 3,447 lines, so no line below the fence moves. The fallback — the wide fence with a comment saying
why the javadoc must sit inside — was not needed.

**Rule five, before the CLI fix.** `OutputFormatter.displaySubagentCompletionReason` went in with an empty body and the
hook unchanged, and the new tests ran against that: `SubagentResultDisplayHookTest` and `OutputFormatterTest`, 48
tests, 6 failed, 0 skipped. Each failure was the one predicted. The hook tests for a cut and for a stalled fork saw a
summary that still ended with the reason line. The colour test found the line printed `ESC[32m`, green. The formatter's
colour-off, yellow and red tests found nothing printed. The completed fork's hook test and the `showToolCalls` test
passed before and after. With the fix, the same two classes ran 48 of 48 green.

**The gates.** `./gradlew format` changed nothing, and `./gradlew checkAll` with no provider key in the environment
passed: BUILD SUCCESSFUL, 240 actionable tasks (100 executed, 140 up to date), and every module's `test` task that has
sources executed in that run. 21 modules reported 10,992 tests, 0 failed, 72 skipped — `aimon-core` 8,201 (2 skipped)
and `aimon-cli` 482 among them. The other skips are `aimon-llm-anthropic` 25 and `aimon-llm-openai` 17, whose live
classes need a key, and `aimon-sandbox-docker` and `aimon-sandbox-kubernetes` 14 each. The four doc checks and
`mkdocs build --strict` passed with this section in the tree.
