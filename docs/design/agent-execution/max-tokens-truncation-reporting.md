# Design — a response cut at `max_tokens` is named by both agent executors (#108, #100, #101)

> Status: **IMPLEMENTED** — `aimon-core` (`at.aimon.core.agent.budget.TruncatedResponses` and the cut-response
> branches of `OrcaAgentExecutor` and `DefaultSubagentExecutor`), three statements in `aimon-llm-anthropic`, and the
> records: §16.8 and §16.10 of
> [`thinking-reporting-and-dialect-records.md`](../llm/anthropic-thinking.md#64-clamp-경고의-범위--clamp-만), backlog `L-16`
> (closed), `L-22` and `L-23`. Sources: issues [#108](https://github.com/kangwoo/aimon-core/issues/108),
> [#100](https://github.com/kangwoo/aimon-core/issues/100) and [#101](https://github.com/kangwoo/aimon-core/issues/101).
>
> **[§11](#11-after-the-build--departures-corrections-and-what-went-to-the-backlog), appended after the build, is
> where this document departs from what was built.** Everything between this header and §11 is the body as approved in
> design review round 1 (PASS, no blocking findings, ten non-blocking), kept byte-exact rather than corrected — the
> house habit in this directory, for the reason
> [`../llm/model-capability-binding-round-trip.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록) gives. Its
> `file:line` citations are at `main` `9b642cc`. The review transcript and run records it names (`review-1.md`,
> `$RUN_DIR/build/`, `TASK.md`) are not in the repository; §11 reproduces what was measured.
>
> What this work left open is in
> [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), L-22 and L-23.

Run `max-tokens-truncation-reporting` · base `main` at `9b642cc` · 2026-09-11.

Every `file:line` below was re-read at `9b642cc` for this design. Line numbers in files this change edits will move.
The build re-cites them, and the records cite by class and method name wherever a line would drift.

---

## 1. The problem, in one paragraph

Both agent ReAct loops handle the provider-neutral `StopReason` badly. The main executor (`OrcaAgentExecutor`) reads it
in exactly one place, behind `!response.hasToolUses()`. It names a cut there, but only for a final response with no
tool call: `CompletionReason.TRUNCATED`, a marker and a WARN. A response that stopped at `max_tokens` *with* tool calls
is dispatched as it is. On the streaming path a `tool_use` block becomes a tool call the moment it starts, and its cut
arguments parse to an empty map. So the operator sees a tool that failed, or ran with defaults, and nothing that says
`max_tokens` (#108, backlog `L-16`). The subagent fork (`DefaultSubagentExecutor`) never reads the stop reason at all.
A fork's final answer cut mid-sentence reaches its parent as `COMPLETED` (#100), and its cut tool calls run the same
way. The record of #89's decision, §16.8 of `docs/design/llm/thinking-reporting-and-dialect-records.md`, and `L-16`
both describe only the main executor. They also overstate three things: who reaches the Anthropic client's own
blocking-path WARN, whether one divergence register is shared by every divergence, and what `theOtherTwoConditions`
pins. Three statements in `aimon-llm-anthropic` code over-claim in the same way (#101). This change makes both
executors give one answer to `StopReason.MAX_TOKENS`, in both shapes, and then corrects §16.8 and `L-16` once to say
what the code does afterwards.

---

## 2. What the code does today (verified at `9b642cc`)

### 2.1 Main executor — `OrcaAgentExecutor`

| Fact | Where |
|---|---|
| The only read of `LlmResponse.getStopReason()` is inside `if (!response.hasToolUses())` | `:1737-1753` (read at `:1742`) |
| That branch appends `TRUNCATION_MARKER` (`"\n\n[System: response truncated at max_tokens]"`), emits `ExecutionCompleted(TRUNCATED)` and returns `success(…, TRUNCATED)` — `isSuccess()` true, `isSuccessful()` false | `:217`, `:1744-1752`, `createTruncatedResult` `:2287-2301` |
| Its WARN: `Agent execution truncated at max_tokens after {} iterations; surfacing flagged partial answer` | `:2289-2290` |
| A response with tool calls goes to `executeToolUses`, and no stop reason is read on that path | `:1768-1770`, `:2101-2126` |
| Before that, the skill pre-flight scan may *suspend* the turn for approval of a `Skill` call | `:1722-1734` |
| Streaming overlap may already have started calls from the response: `TOOL_USE_READY` → `StreamingToolScheduler.onToolUseReady`, only for `CONCURRENT_SAFE` tools, and `discardEagerToolUses` exists for the suspend path | `:3163-3172`, `StreamingToolScheduler.java:93`, `DefaultParallelToolDispatcher.isEagerEligible`, `:2229-2234` |
| The loop already answers a call it will not run: once a turn is interrupted, `toolRunner` returns `ToolUseResult.error(id, INTERRUPTED_TOOL_SKIP_MESSAGE)` — no hooks, no execution — and the dispatcher still fires `ToolUseStarted` / `ToolResultReady` | `:2155-2167`, `:233-234`, `:2123-2125` |
| Death-spiral guard: three consecutive iterations whose tool results are *all* errors end the turn as `ERROR` | `:226`, `:1799-1808`, `isStalledIteration` `:2483-2485`, `handleStalledIteration` `:2509-2526` |
| Every call, blocking or streaming, carries a live `SignalBackedLlmCancellation` | `:1529`, blocking `:2987-2988`, streaming `:3011` |

### 2.2 Fork — `DefaultSubagentExecutor`

| Fact | Where |
|---|---|
| `!response.hasToolUses()` → `createSuccessResult(…, COMPLETED)`, with no stop-reason read anywhere in the class | `:441-445`, `:865-879` (the only `getStopReason` is `BudgetTracker`'s, `:900`) |
| Tool calls are executed as they arrive | `:448-459` |
| **No death-spiral guard**: the loop ends at `maxIterations`, a budget stop, cancellation or an error | `:386-470` |
| It passes a live `SignalBackedLlmCancellation` to the gateway's six-argument overload, so on both providers it takes the streaming path — this answers #100's "not traced" | `:377`, `:419-420`; `AnthropicLlmClient.java:282-286`; `OpenAILlmClient.java:210-213` |
| Progress stream ends a success with `[completed: SUCCESS after N iterations]`, which only `DefaultSubagentExecutorTest:326` reads | `:875-876` |
| Its javadoc promises ReAct-loop parity with `OrcaAgentExecutor` | `:97-100` |

### 2.3 Streaming and the providers

| Fact | Where |
|---|---|
| Anthropic registers a tool slot when the `tool_use` block **starts** | `AnthropicStreamingMapper.java:171-175` |
| `TOOL_USE_READY` is emitted only on `content_block_stop` | `AnthropicStreamingMapper.java:247-253` |
| Every slot with an id and a name becomes a `ToolUse`; empty arguments give `Map.of()` silently, and unparseable ones give a WARN (with the raw JSON) and an empty map | `ChunkAggregator.java:247-253`, `:287-298` |
| The stream's stop reason reaches `LlmResponse` through `STREAM_END` | `ChunkAggregator.java:97-102`, `AnthropicStreamingMapper.java:293-298` |
| Thinking tokens land in `TokenUsage.getReasoningTokens()` on both Anthropic paths (`usage.output_tokens_details.thinking_tokens`) | `AnthropicStreamingMapper.java:303`, `:358`; `AnthropicUsages` |
| OpenAI maps `length` / `incomplete: max_output_tokens` to `StopReason.MAX_TOKENS` on both endpoints | `OpenAiStopReasons.java:33`, `OpenAiResponseStopReasons.fromStatus` |
| OpenAI Chat and Responses streams also register slots before the arguments finish | `OpenAIStreamingMapper.java:112`, `OpenAIResponsesStreamingMapper.java:170` |

### 2.4 Who reaches the clients' own truncation WARNs

`AnthropicLlmClient.convertResponse` WARNs `Anthropic response was truncated due to max_tokens limit` (`:786`). Its one
caller is the four-argument blocking `sendMessage` (`:225` → `:249`). `LlmClient`'s five-argument default delegates to
that overload (`LlmClient.java:93-96`). The six-argument overload reroutes to the stream whenever the token
`isSupported()` (`:282-286`). OpenAI's two WARNs (`OpenAIChatCompletionsExchange.java:84-85`,
`OpenAIResponsesExchange.java:99-100`) sit in the same position (`OpenAILlmClient.java:210-213`).

- **Neither executor reaches them**: both pass a live token (§2.1, §2.2).
- **Callers of the blocking overloads do reach them.** Several of those run during an agent's execution. A first
  grep at `9b642cc` (`git grep -n -E '[A-Za-z]+\.sendMessage\(' -- 'modules/*/src/main/**'`, four- or five-argument
  forms only) finds:
  - `DefaultCompactionEngine.java:223-224` (five-argument, #101's example)
  - `LlmSkillExecutor.java:185`, `:213`
  - `LlmDialecticEngine.java:98`
  - `RandomWalkDreamer.java:263`
  - `DefaultReconciler.java:168`
  - `LlmJudgeSurprisalScorer.java:111`
  - `LlmDeriver.java:167`, `:196`
  - `ReActLlmDeriver.java:195`
  - wiki: `LlmRerankSearchStrategy.java:283`, `LlmSynthesisStrategy.java:290`, `LlmWikiAnswerStrategy.java:132`,
    `LlmWikiLintStrategy.java:152`, `LlmWikiPageGenerator.java:286`/`:316`/`:354`, `LlmWikiPageMerger.java:128`

  The records must be recounted with backlog rule six (both `sendMessage(` and `::sendMessage`), not copied from this
  list.
- **The only main-source callers that get a stream are the two executors**: `OrcaAgentExecutor.java:3011` directly;
  `LlmCallGateway.java:568` and the decorators only forward; and the six-argument reroute serves callers holding a live
  token, which are the executors.

### 2.5 Constraints that shape the design

| Constraint | Where | Consequence |
|---|---|---|
| `at.aimon.core.agent.impl..` must not be referenced from outside `at.aimon.core.agent..` (main classes only) | `PackageDependencyArchitectureTest.java:318-320`, `:146` | The fork cannot use `OrcaAgentExecutor.TRUNCATION_MARKER`. A shared definition needs a neutral home (D5) |
| `OrcaAgentExecutorTruncationTest.maxTokensWithToolUsesIsNotTreatedAsTruncated` says, in its comment, that such a response "goes through the normal tool-execution path". Its assertions (`COMPLETED`, `"done"`) do not observe whether the tool ran | `:109-130` | It stays green under either decision. Backlog rule five needs a *new* assertion that fails first |
| Consumers of a fork's completion reason: `AgentStepResult.isComplete()` is `COMPLETED`-only; the workflow step cache saves only `isSuccess() && COMPLETED`; `TaskResult` / `JsonTaskResultCodec` persist the name; `TaskTool` prints `getStatus()` (SUCCESS/FAILURE from `isSuccess()`) and `getSummary()` | `AgentStepResult.java` `isComplete`; `docs/design/workflow/workflow.md:345-347`; `TaskResult.java:116`; `TaskTool.java:612`, `:621` | A fork that starts reporting `TRUNCATED` changes what a workflow does with that step — a CHANGELOG item |
| Two more tool loops never read the stop reason: `LlmSkillExecutor` (`while (currentResponse.hasToolUses())`, `:191`) and `ReActLlmDeriver` (`:188-215`). Both call the blocking overload, so on either provider the client WARN names the cut, but a cut tool call still runs | as cited | Out of this change's scope (§8). The records say "both agent executors", never "every path" |

---

## 3. Decisions

Each decision below gets its own heading in the PR body, in this form: taken / rejected / why.

### D1 — A tool call in a response that stopped at `max_tokens` is not executed

**Taken (B1):** when `StopReason.isTruncated()` and the response carries tool calls, **none** of them runs. Each
`tool_use` is answered, in order, with exactly one error `ToolUseResult` carrying one fixed text. Results are committed
after the assistant message exactly as executed results are, since every `tool_use` must be answered. The loop then
continues.

The refusal, precisely:

- **Nothing on the tool path runs.** There is no permission check, no PermissionRequest/PreTool/PostTool hook, no
  `ToolExecutionManager` and no TOOL span. This is the same shape as the interrupt skip (§2.1).
- **The main executor emits `ToolUseStarted` then `ToolResultReady` for each refused call**, so an event consumer (the
  REPL) shows the failed call and its reason — as the interrupt skip does through the dispatcher callbacks. The fork
  writes `→ name` / `← name [error]` plus the text to its output sink, as its `executeToolUses` does.
- **Eager work from the response is discarded first** (`discardEagerToolUses`). An eagerly started call is
  `CONCURRENT_SAFE` by the eligibility rule, so it may already have run; its result is dropped.
- **The skill pre-flight scan is skipped for a cut response**, so a cut `Skill` call never asks a person to approve
  it.
- **The stalled-iteration guard counts it.** A refused iteration is all-error, so three cut responses in a row end the
  turn as `ERROR` without a new counter (open question Q1 asks whether that should be `TRUNCATED`).
- **The operator's WARN** names `max_tokens`, the iteration and the tool names (not their arguments), and adds the
  reasoning-token clause from D2 when there is one.

**The model-facing text** (build may polish; it must keep all three parts):

> Not executed: your response was cut off at the max_tokens output limit before it finished, so its tool calls may be
> incomplete, and none of them were run. Sending the same calls again will be cut off the same way. Produce less output
> in one response: make fewer tool calls at once, or split a large argument across several smaller calls.

It names `max_tokens`. It says the call was not run. It does not invite repeating the same call, and it names the two
edits that fit under the limit.

**Why:**

1. **A cut call's arguments are not partial, they are absent.** Partial JSON fails to parse and becomes an empty map
   (`ChunkAggregator.java:287-298`); a call cut before any argument is an empty map silently. So "execute the
   truncated call" means running the tool with none of the arguments the model was writing. A tool whose parameters
   are all optional does something nobody asked for. A tool with required parameters still runs under the default
   `SchemaValidationMode.WARN` (`DefaultToolExecutor.java:194-201`) and fails with "missing parameter", which invites
   re-issuing the same call — the loop the task names.
2. **The loop already has this shape for a call it will not run** (the interrupt skip). The model learns a call failed
   through a `tool_result`, and that channel can carry the reason.
3. **It is bounded without new machinery** in the main executor (the stall guard). In the fork, it is bounded by the
   same things that bound a fork today (`maxIterations`, budget).
4. **It needs nothing but `StopReason`**, so it is the same answer for every provider that maps its cut to
   `MAX_TOKENS`.

**Rejected:**

- **A — keep executing, add a WARN.** Reason 1: runs calls with empty arguments, and the model's error invites the
  same call again. The operator would be told, but the model would not.
- **B2 — run the complete calls and refuse only the cut one.** The neutral `LlmResponse` does not say which call was
  cut. Anthropic and OpenAI Chat stream calls in order, so "the last one" is likely, but that is a provider fact the
  executor cannot see, and guessing wrong runs a call with empty arguments — the defect being fixed. Streaming overlap
  would also make it a partial harvest that mixes eager and refused results. B1's cost is small by comparison: complete
  calls in a cut response are re-issued one iteration later, and "make fewer tool calls at once" is also the remedy for
  the squeeze.
- **C — end the execution as `TRUNCATED` at the first cut tool call.** It throws away the recoverable case: a large
  `Write` argument can be split across calls, and only the model can do that. `TRUNCATED` also promises a partial
  answer with the marker, and a cut tool call has no answer to surface.

### D2 — Detect in the executors, from `StopReason.isTruncated()`; attribute through `TokenUsage.getReasoningTokens()`

**Taken:** each executor asks one shared predicate (D5) whether the response's stop reason is truncated, and branches
on that for both shapes. When the cut response's `TokenUsage.getReasoningTokens()` is above zero, the WARN adds a
clause such as `; 3990 of its 4000 output tokens were reasoning, so reasoning may have used most of the max_tokens
allowance`. When it is zero or unreported, the clause is omitted and the WARN reads as it would without the feature, so
the non-thinking case gets no worse. The clause goes on all three truncation WARNs (turn answer, turn tool calls, fork
answer and fork tool calls), from the *cut response's* usage, not the accumulated one.

**Why:**

- **The executor is the one place every path passes through**: blocking with no live token, blocking rerouted to a
  stream, and streaming.
- **`StopReason` exists so core can decide this without provider vocabulary** (its javadoc). `L-16`'s recorded shape
  is exactly this.
- **Attribution through the neutral fourth counter** works on both Anthropic paths (§2.3) and on any provider that
  fills it. OpenAI's usage converters appear to fill the same counter; the build verifies, and where one does not, the
  clause is simply absent.

**Rejected:**

- **Stream aggregation** (`ChunkAggregator` drops or marks slots when `STREAM_END` says `MAX_TOKENS`). It would not
  reach the blocking paths. It also cannot decide policy: the aggregator builds `LlmResponse`s for every streamed
  caller, and dropping tool uses there silently turns a cut tool response into a text-only final answer.
- **A per-`ToolUse` "complete" flag set by the mappers.** It changes a public value type and three mappers to carry
  information D1 does not need. It would only exist on streaming paths.
- **Attribution from Anthropic's wire field inside `aimon-llm-anthropic`.** Provider-specific, and the executor never
  sees it.

### D3 — Keep the blocking-path WARNs where they are

**Taken:** `AnthropicLlmClient.java:786` and OpenAI's two WARNs stay, unchanged. The records are corrected to say who
reaches them (§2.4).

**Why:**

- **Removing it takes the only signal from its real audience**: compaction, skill LLM execution, memory and the wiki
  (#101 item 1).
- **Moving it to (or duplicating it in) the stream adds no audience.** The only main-source callers that get a stream
  are the two executors (§2.4). After D1/D4 they log a WARN with the iteration, the tool names and the reasoning clause;
  a client line on the stream would double every one of those and tell nobody anything new.

**Rejected:** remove; move to `AnthropicStreamingMapper`; duplicate in both.

### D4 — The fork reports a cut final answer exactly as a turn does, and shares D1

**Taken:**

- A fork's final response with no tool calls and a truncated stop reason ends with the shared marker appended, both in
  the transcript and in the answer.
- It logs a WARN: `Subagent '{}' final answer truncated at max_tokens after {} iterations; returning flagged partial
  answer{reasoning clause}`.
- OnStop hooks fire with `success=true` and the flagged answer, as `OrcaAgentExecutor.createTruncatedResult` does.
- The progress stream ends `[completed: TRUNCATED at max_tokens after N iterations]`; nothing but a success-path test
  reads the old literal.
- It returns `SubagentExecutionResult.success(flagged, snapshot, metadata, CompletionReason.TRUNCATED, cost)`.
- A cut tool response in the fork is refused as in D1, with the same text, WARN shape and order.

**Why:**

- **Every consumer of a fork's answer needs to know it is partial**: a parent model reading the `Task` summary, a
  workflow judge reading `AgentStepResult.isComplete()`, a background task record. `TRUNCATED` is a value those
  consumers and codecs already carry.
- **`success(…)` rather than `failure(…)`** matches the turn's shape. It keeps the partial text in `getSummary()`,
  where `failure` would replace it with an error message.
- **The class promises parity** (§2.2), and no reason for a difference was found. The issue group exists because two
  executors gave two answers.

**Rejected:**

- `failure(…, TRUNCATED)` — loses the partial text for the parent, and diverges from the turn.
- Record the difference as deliberate — there is no reason to record.
- The fork executes cut calls — D1's reasons apply unchanged.

**What stays different, and is written down rather than fixed:** the fork has no stalled-iteration guard, so repeated
cut tool responses in a fork run to its `maxIterations` or budget. That gap predates this change and has nothing to do
with `max_tokens`; §8 routes it.

### D5 — One shared definition, in a new `at.aimon.core.agent.budget.TruncatedResponses`

**Taken:** a new `public final` utility class. It holds the marker text, the refusal text, the truncation predicate and
the reasoning clause (shape in §5). `OrcaAgentExecutor.TRUNCATION_MARKER` keeps its name and value and is initialised
from the shared constant, so it stays a compile-time constant. Both executors and both executors' tests reference the
shared constants, which is what pins parity.

**Why:**

- **ArchUnit forbids the fork from referencing `OrcaAgentExecutor`** (§2.5).
- **`at.aimon.core.agent.budget` already holds `CompletionReason`**, the terminal cause including `TRUNCATED`. Its
  package doc says it lets callers "interpret why an execution ended", and it already depends on `at.aimon.core.llm`
  (`BudgetTracker.recordTokens(TokenUsage)`), so no new package dependency pair appears.
- **No sibling run touches `agent/budget/**`**; this run owns its `CompletionReason.java`.

This is a new file (plus one bullet in `agent/budget/package-info.java`) outside the paths `TASK.md` lists. It is
flagged for the review gate as Q3.

**Rejected:**

- **`at.aimon.core.agent.loop`** — neutral and made for "both loop drivers", but its package doc says its types
  "never participate in control flow", and this one does.
- **A private literal in each executor, pinned equal by a test** — two definitions of model-facing text, which is the
  drift this issue group is about.
- **Constants on `CompletionReason`** — unrelated members on a public enum.
- **`at.aimon.core.llm`** — the text addresses the agent loop's model, not an LLM client.

### D6 — #101 item 3: add the missing paths rather than narrow the sentence

**Taken:** every missing path is one assertion row in `AnthropicThinkingResolverTest.ClampWarningCoverage.theOtherTwoConditions`
(`:353-391`), which is the case the task prefers. Expected directions, derived from `AnthropicThinkingResolver.resolve`
/ `resolveDialect` / `resolveNamedDialect`:

| New row | Dialect sent | Expected |
|---|---|---|
| `AUTO` on `EITHER` | ADAPTIVE (AUTO picks adaptive for `EITHER`) | no `thinkingBudgetClamped` — condition 1 fails |
| `EXTENDED` on `ADAPTIVE` | translated to ADAPTIVE, no budget | no `thinkingBudgetClamped` |
| `ADAPTIVE` on `ADAPTIVE` | ADAPTIVE | no `thinkingBudgetClamped` |
| `ADAPTIVE` on `EITHER` | ADAPTIVE (named mode honoured) | no `thinkingBudgetClamped` |
| `ADAPTIVE` on undescribed | ADAPTIVE (named mode honoured) | no `thinkingBudgetClamped` |
| condition 2 at `maxTokens` 1024 and 512 on each budget-sending path: `EXTENDED` on `BUDGETED` / `EITHER` / undescribed, `ADAPTIVE` translated onto `BUDGETED` | budgeted, but no legal budget | type `none`; `thinkingBudgetImpossible=<maxTokens>` present; no `thinkingBudgetClamped`. Use `contains`, not `containsExactly`: the translated path also records `thinkingDialectTranslated=…` |

If any row contradicts §16.8's condition 1 or 2 when run, that is a finding against §16.8, recorded in §16.10. The test
is not bent to fit. With the rows in, §16.8's "conditions 1 and 2 path by path in both directions" becomes true as
written, and §16.10 says it was not true when merged.

### D7 — How the records are corrected

- **§16.8 is edited in place** so it says what the code does. A new **§16.10** records each statement that was false,
  what is true, and what now reads differently — the bullet form of §16.7 and §16.9.
- **§16.9's fifth bullet** ("From an agent it never fires") carries the same over-reach as #101 item 1. It gets a
  dated pointer to §16.10 rather than a rewrite, following §16.8's own precedent of dated pointers into earlier
  subsections.
- **`L-16` is closed** with a `### 닫힘 (2026-09-11, #108 · #100 · #101)` subsection, in the shape of `L-6` and
  `L-12`. Its registration body is not rewritten: backlog rule two says a wrong basis stays written, marked wrong. A
  dated `> **정정**` blockquote directly after its table names the two rows that were wrong.
- **Record language:** §16 is written in English; the `L-16` register text is Korean.

---

## 4. Concrete changes, by module and file

### 4.1 `aimon-core` — main

**`agent/budget/TruncatedResponses.java` (new)** — see §5. Stateless, no logger, no instances. Also one bullet in
`agent/budget/package-info.java`'s type list.

**`agent/impl/orca/OrcaAgentExecutor.java`**

- Compute `truncated` once, right after token and cost accounting (`~:1686-1704`).
- Skill pre-flight (`:1722`) gains `&& !truncated`.
- The final-answer branch (`:1737-1763`) uses that variable; its behaviour is unchanged.
- The `executeToolUses` call (`:1768-1770`) becomes a conditional. Everything after it stays as it is: the artifact
  slice, the assistant commit, the tool-result commit, the stall guard, the queue drain and the iteration-tail checks.

  ```java
  final List<ToolUseResult> toolUseResults = truncated
          ? refuseTruncatedToolUses(scope, response, iterationCount)
          : executeToolUses(scope, toolContext, response.getToolUses(), iterationCount, coordinator, sessionRegistry);
  ```

- New private `refuseTruncatedToolUses`:
  1. `discardEagerToolUses(scope)`
  2. one WARN (D1, D2)
  3. per `tool_use`, in order: `emitToolUseStarted`, then `ToolUseResult.error(id, TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE)`,
     then `emitToolResultReady`
  4. return the list
- `createTruncatedResult`'s WARN (`:2289`) gains the reasoning clause from the cut response's usage.
- `TRUNCATION_MARKER` (`:217`) is initialised from `TruncatedResponses.TRUNCATION_MARKER`; its javadoc points there.
- The javadoc on `MAX_CONSECUTIVE_STALLED_ITERATIONS` / `isStalledIteration` gets one sentence: a refused cut response
  counts as stalled.

**`subagent/execution/DefaultSubagentExecutor.java`**

- Compute `truncated` after cost accounting (`~:425-438`).
- In the final-answer branch (`:441-445`), when `truncated`: commit `Message.assistant(flagged).withReasoningTraces(…)`
  and return a new private `createTruncatedResult` (D4). Otherwise unchanged.
- The tool path (`:447-459`): the assistant commit and preamble stay; `executeToolUses(…)` becomes
  `truncated ? refuseTruncatedToolUses(lc, response, iterationCount) : executeToolUses(…)`.
- The new private `refuseTruncatedToolUses` logs the WARN (with the subagent name), streams `→` / `←` per call using
  the existing `formatStreamedToolResult`, and returns the error results.
- Class javadoc: one sentence that `max_tokens` handling matches the main executor's.

**`agent/budget/CompletionReason.java`** — javadoc only.

- `TRUNCATED` covers a turn *or a fork*.
- The partial text carries `TruncatedResponses.TRUNCATION_MARKER`.
- A response cut inside a tool call does **not** end the execution with `TRUNCATED`: its calls are refused and the
  loop continues.

**`subagent/execution/SubagentExecutionResult.java`** — javadoc only, on `getCompletionReason()` (`:336-352`), which is
where subagent results are documented: add `TRUNCATED` (partial answer with the marker; `isSuccess()` still `true`, as
on a turn).

### 4.2 `aimon-llm-anthropic`

**`AnthropicThinkingBudgets.java:122-124`** — javadoc. Replace *"`HIGH` clamps to 4095 out of the box"* with a
condition, phrased like §16.8's condition 2: *"so `HIGH` clamps to 4095 whenever the call's `LlmModel` sets no
`maxTokens` and `AnthropicConfig.Builder.maxTokens(int)` was not used"*. A CLI agent definition that sets
`model.maxTokens` does not clamp.

**`AnthropicThinkingResolverTest`** — the rows in D6.

**`AnthropicThinkingDialectTest.java:374-375`** — narrow the comment to what `warnings()` reads (the appender on
`AnthropicLlmClient`'s logger, `:140-143`), e.g. *"a new line from `AnthropicLlmClient` on these requests, whatever it
says"*. No assertion change.

**`AnthropicThinkingBudgetsTest.java:89`** — rename `aBudgetThatFitsByOneTokenIsNotClamped` so the method name covers
both halves the display name already covers, e.g. `aBudgetUnderMaxTokensIsSentAsAskedAndOneAtMaxTokensIsClamped`. Do
not split it: its comment reads the four rows as one table. A grep found no citation of the old name outside the test.

### 4.3 `aimon-llm-openai`

No change (D3).

### 4.4 Docs

**`docs/design/llm/thinking-reporting-and-dialect-records.md`** (no translation twin)

- **§16.8, "Emitted is not the same as logged" (`:1403-1407`).** Say the once-per-signature register
  (`reportedDivergences`) is shared by the divergences the client reports once. Say traffic conditions go to the
  second register `recurringDivergences` and are reported at the 1st, 10th, 100th… occurrence. Re-cite
  `MAX_REPORTED_DIVERGENCES`, both fields and both report methods; `:681` no longer matches `reportDivergence` (now
  `:677`).
- **§16.8, reason 3 (`:1459-1473`).** Rewrite to *"reported when it happens … in both shapes, by both agent
  executors"*:
  - a cut final answer → `TRUNCATED` + marker + WARN, on a turn and on a fork;
  - a cut inside a tool call → no call runs, each gets an error result naming `max_tokens`, a WARN names `max_tokens`
    with the reasoning clause, and three in a row end a turn through the stall guard (a fork has no guard);
  - the client WARN: its one caller is the blocking overload. The two executors never reach it, because both pass a
    live token. Callers of the blocking overloads do reach it, several during an agent's execution; name them from the
    rule-six count, and say it is kept (D3).

  Cite classes and methods, not the executor's moving lines.
- **§16.8, "The cost" (`:1480-1486`).** Replace *"when the cut lands inside a tool call, not even that … (L-16)"* and
  *"The silence on a cut tool call is not accepted; it is open as L-16"* with what happens now, pointing to §16.10.
- **§16.8, "What would re-open this", last bullet (`:1495-1498`).** The truncation report changed in #108/#100 without
  touching this warning. Changing the report is not a trigger.
- **§16.8, "Where it is pinned" (`:1500-1504`).** Keep "path by path in both directions" once D6's rows are in. Narrow
  *"with no warning at all"* to *"with no warning from `AnthropicLlmClient`"* — the same finding as #101's test-comment
  item, applied to the record.
- **§16.9, fifth bullet (`:1526-1530`).** Append a dated pointer: *(2026-09-11: narrower than that — §16.10.)*
- **New §16.10** — `### 16.10 What §16.8 overstated, and the cut it left unnamed (#101, #108, #100, 2026-09-11)`.
  - An italic lead: not a change to #89's decision; option 3 stands; the clamp warning is untouched.
  - One bullet per false statement, in §16.9's form (**what it said.** what is true; what now reads differently):
    1. the client WARN "does not reach an agent at all";
    2. "every other divergence";
    3. `theOtherTwoConditions` over-claimed;
    4. reason 3's first shape held for the main executor only (#100);
    5. reason 3's second shape was open (#108), now closed;
    6. "no warning at all";
    7. the three code statements, one line each.
  - A short paragraph on what changed in code: D1 and D4 in one sentence each, pointing to `CHANGELOG.md`.
  - A line saying `L-16` is closed.
  - If the optional probe ran, one sentence of what it observed.
- **`## 관련 문서` (`:1550`).** *"L-16 opened by §16.8"* → *"L-16 opened by §16.8 and closed by §16.10"*.

**`docs/backlog/llm-config-surface-open-items.md`** (Korean)

- **`L-16`, after the table (`:888`).** A dated `> **정정** (2026-09-11, #101)` blockquote:
  - Row 3 (*"닿지 않는다"*) is true of the two executors' calls only; the blocking callers reach it.
  - Rows 1 and 2 describe `OrcaAgentExecutor` only; the fork never read the stop reason (#100).
- **`L-16`, a new `### 닫힘 (2026-09-11, #108 · #100 · #101)`**, following the README's closing rules:
  - one-line summary of what happens now, on both executors;
  - **rule five:** the prescription left execute-or-not open; D1 took "not executed", in one sentence with a link to
    §16.10. Attribution went through `TokenUsage.getReasoningTokens()`. The pre-existing test did not observe
    execution, so the new assertion failed first — state only what the build actually saw;
  - **rule two:** the table corrections, pointing to the blockquote;
  - **counted now:** OpenAI's two WARNs are bypassed the same way (`OpenAILlmClient.java:210-213`). The stall guard
    ends three consecutive refused responses as `ERROR` (name the test). The fork has no guard;
  - **rule three:** severity — the live probe's result, or *"still not observed; the decision does not depend on it"*;
  - **어디:** method names and a date.
- **Register title (`:1`) and `## 관련 문서` line for this doc (`:1098-1099`).** Recount the title from the body —
  never add to the old number. Siblings may land in between. If `L-22` is registered (§8), it is counted too.
  Extend the related-docs line: *"§16.10 이 L-16 을 닫은 기록이다"*.

**`docs/backlog/README.md`** — the `llm-config-surface-open-items.md` index row (`:347`), recounted from the body. Add
one sentence to the running parenthetical at `:376-378` saying which change closed `L-16`, with the recounted row.

**`docs/design/workflow/workflow.md`** (no twin; "wherever subagent results are documented")

- §4.2 (`:210-213`) says `DefaultSubagentExecutor` supplies four reasons "(COMPLETED / budget stop reason /
  MAX_ITERATIONS / INTERRUPTED)". Rewrite the parenthetical to what it supplies after this change: COMPLETED ·
  TRUNCATED · budget stop reason · MAX_ITERATIONS · INTERRUPTED · ERROR. The ERROR omission predates this change; §8
  notes it.
- (d) (`:345-347`): add `TRUNCATED` to the list of steps the cache does not store.

**`CHANGELOG.md` `[Unreleased]`** — a new `###` section. Draft:

> ### Agent loop: a response cut at `max_tokens` says so on both executors, and its tool calls are not run
>
> - **A tool call in a response that stopped at `max_tokens` is no longer executed** (#108).
>   - *Before:* the main executor ran such calls, and a call cut mid-argument reached the tool with empty arguments.
>     No log line, marker or completion reason said `max_tokens`.
>   - *Now:* none of the response's tool calls runs, and each is answered with an error result saying the response
>     was cut at `max_tokens` and asking for less output per response.
>   - No permission check or PreTool/PostTool hook runs for them, and a cut `Skill` call no longer suspends the turn
>     for approval.
>   - A call streaming overlap had already started from that response (only `CONCURRENT_SAFE` tools start early) has
>     its result discarded.
>   - The loop continues; three such responses in a row trip the existing stalled-iteration guard and end the turn as
>     `ERROR`.
> - **The operator is told.**
>   - One WARN per cut response names `max_tokens`, the iteration and the calls not run.
>   - `ToolUseStarted` / `ToolResultReady` events carry the error.
>   - When the provider reports reasoning tokens, this WARN and the existing truncated-answer WARN say how many of the
>     response's output tokens they were. When it reports none, the text is unchanged.
> - **A subagent fork's final answer cut at `max_tokens` is now `TRUNCATED`, not `COMPLETED`** (#100).
>   - The same marker is appended and a WARN is logged; `isSuccess()` stays `true`, as on a turn.
>   - For such a step, `AgentStepResult.isComplete()` is `false`, the workflow step cache does not store it (a resume
>     re-runs it), and background task results record `TRUNCATED`.
>   - A fork's cut tool calls are refused as above. Forks have no stalled-iteration guard, so repeated cuts end at the
>     fork's `maxIterations` or budget.
> - **Unchanged:**
>   - the Anthropic and OpenAI clients' own truncation WARNs, which callers of the blocking overloads (compaction,
>     skill LLM execution, memory, wiki) still reach;
>   - `CompletionReason` and `StopReason` values;
>   - `OrcaAgentExecutor.TRUNCATION_MARKER`'s name and text.
> - **Records.** §16.8 of `docs/design/llm/thinking-reporting-and-dialect-records.md` is corrected and §16.10 added
>   (#101). Backlog `L-16` is closed.

No `docs/migration/rename-maps.md` entry: nothing is renamed. The test-method rename is not API.

---

## 5. Data and interface shapes

### 5.1 The new type

```java
package at.aimon.core.agent.budget;

/** How an agent execution treats a response the provider cut at its max-output-token limit. Shared by both loops. */
public final class TruncatedResponses {
    public static final String TRUNCATION_MARKER = "\n\n[System: response truncated at max_tokens]"; // text unchanged
    public static final String REFUSED_TOOL_CALL_MESSAGE = "Not executed: …";                        // D1's text

    private TruncatedResponses() { }

    /** true iff the response carries a stop reason and it is StopReason#isTruncated(). */
    public static boolean isTruncated(LlmResponse response) { … }

    /** "" when usage reports no reasoning tokens; otherwise the WARN clause of D2, built from this response's usage. */
    public static String reasoningClause(TokenUsage usage) { … }
}
```

The build may add `static ToolUseResult refusal(ToolUse)` if both call sites want it. It adds nothing else public.

### 5.2 What does not change

- **Types:** `StopReason`, `LlmResponse`, `ToolUse`, `ToolUseResult`, `TokenUsage`, `ChunkAggregator`,
  `LlmStreamChunk`.
- **Contracts and values:** every `LlmClient` overload; `CompletionReason` values; `SubagentExecutionResult` and
  `OrcaAgentExecutionResult` factories.
- **Persistence and wire:** `TaskResult`, codecs, routing payloads; config keys, wire names, persisted identities.
- **Constants:** `OrcaAgentExecutor.TRUNCATION_MARKER`'s name and value; `MAX_CONSECUTIVE_STALLED_ITERATIONS`.

### 5.3 What an observer sees change

| Observer | Before | After |
|---|---|---|
| Tool with a cut call | runs with empty arguments | not called |
| Transcript after a cut tool response | assistant message + whatever the tools returned | assistant message + one error result per call with the refusal text |
| Events (main executor) | `ToolUseStarted`/`ToolResultReady` with the tool's result | the same two events with the refusal error |
| Hooks for a cut call | PermissionRequest/PreTool/PostTool fire | none fire |
| Log | at most the aggregator's parse WARN | one WARN naming `max_tokens`, the iteration and the calls |
| Fork, cut final answer | `COMPLETED`, no marker, no WARN, `[completed: SUCCESS …]` | `TRUNCATED`, marker, WARN, `[completed: TRUNCATED at max_tokens …]` |
| Workflow step with a cut fork answer | `isComplete()` true, cached | `isComplete()` false, not cached |
| Existing truncated-answer WARN | fixed text | same text, plus the reasoning clause only when reasoning tokens were reported |

---

## 6. Failure modes

| # | Situation | What happens | Accepted because |
|---|---|---|---|
| F1 | A provider reports `MAX_TOKENS` although every call in the response was complete (cut after the last block closed) | the complete calls are refused; the model re-issues them next iteration | costs one iteration; the alternative is B2's guess (D1) |
| F2 | A cut is reported as `UNKNOWN` or not at all (a gateway dropping the stop reason, an `incomplete` reason other than `max_output_tokens`) | no detection; calls run as today | `StopReason`'s contract: `UNKNOWN` never changes behaviour. The records must not claim more than "a stop reason the provider maps to `MAX_TOKENS`" |
| F3 | Every response is cut (a thinking budget that eats the allowance) | main: WARN ×3 with the reasoning clause, then `ERROR` from the stall guard. Fork: until `maxIterations` or budget | bounded as today; the WARNs name the cause (Q1 asks about `TRUNCATED`; §8 routes the fork's guard) |
| F4 | Refused and genuinely failing iterations interleave | they share the stall counter, like any all-error iteration | the guard's meaning is "no progress", and neither made any |
| F5 | Streaming overlap started a `CONCURRENT_SAFE` call from the cut response | it may have run; its result is discarded, and the transcript carries the refusal | the eligibility rule limits this to calls that are safe to run concurrently and drop |
| F6 | A cut response contains a `Skill` call that would need approval | not suspended, refused | asking a person to approve a call with no arguments is worse; the next complete call goes through the scan as usual |
| F7 | A hook or audit counts tool calls via PreTool/PostTool | refused calls are not seen | they did not run, as with the interrupt skip; named in `CHANGELOG.md` |
| F8 | A consumer compares a fork's reason with `== COMPLETED` | a cut fork answer now reads "not complete" | that is the point of D4; named in `CHANGELOG.md` |
| F9 | The reasoning counter is unreported or 0 | clause omitted | no-worse rule (D2) |
| F10 | The refused `tool_use` (input `{}`) and its error result are replayed on the next request | accepted by both providers | the same assistant shape is committed today; only the result content differs |
| F11 | A trip lands while refusing | refusing does no blocking work; the existing iteration-tail check handles it | unchanged path |
| F12 | Log volume | one WARN per cut response, bounded by the guard or iterations | a traffic condition, like the clients' recurring divergences; there is no config fact to say once |
| F13 | `LlmSkillExecutor` / `ReActLlmDeriver` receive a cut tool response | unchanged: calls run, the client WARN names the cut | out of scope (§8, registered as `L-22`) |

---

## 7. Test strategy

**Order (backlog rule five):** write each failing test against the current code first and see it fail for the stated
reason. Then implement.

### 7.1 `aimon-core` — `OrcaAgentExecutorTruncationTest` (existing fixtures: `SequencedLlmClient`, `NoopTool`)

1. **Keep** `maxTokensWithoutToolUsesIsFlaggedAsTruncated`. Add `endsWith(TruncatedResponses.TRUNCATION_MARKER)` next
   to the existing literal `contains(…)`, which pins that the text did not change.
2. **Replace** `maxTokensWithToolUsesIsNotTreatedAsTruncated` with a test that none of the calls runs.
   - A counting tool (`AtomicInteger`) is invoked 0 times.
   - A capturing client records the second call's messages; its last message holds exactly one error result whose
     content equals `REFUSED_TOOL_CALL_MESSAGE` and contains `max_tokens`.
   - The result is `COMPLETED` with `"done"`.
   - A logback `ListAppender` on `OrcaAgentExecutor` catches a WARN containing `max_tokens` and the tool name.
   - *Fails today:* invocation count is 1, and there is no refusal.
3. **Two calls in one cut response** (one with non-empty arguments): neither runs; two error results, in order.
4. **Three cut tool responses in a row trip the stalled guard:** `ERROR`, iteration count 3, the fourth queued response
   is not consumed, the tool is invoked 0 times.
5. **Reasoning clause:** with `TokenUsage.of(10, 4000, 4010, 3990)` the tool-call WARN and the final-answer WARN
   contain `3990`; with reasoning 0 neither contains the clause. Parametrise over both shapes.
6. **Events** — here, or in `OrcaAgentExecutorEventEmissionTest`'s harness: for a cut tool response,
   `AssistantMessageReceived` → `ToolUseStarted` → `ToolResultReady` (error, refusal text) →
   `IterationCompleted(willContinue=true)`.
7. **Streaming path, overlap off** (`useStreaming(true)`; stub on the `OrcaAgentExecutorReasoningStreamTest`
   pattern). The stub emits `textDelta`, `toolUseReady`, `streamEnd(…, MAX_TOKENS)` and returns the matching
   `LlmResponse`. Expect the same refusal.
8. **Optional:**
   - overlap on, where the parallel-dispatcher fixture makes it one test — a `CONCURRENT_SAFE` tool started from
     `TOOL_USE_READY` does not supply the transcript's result;
   - skill pre-flight, where `OrcaAgentExecutorSkillSuspendTest`'s fixture makes it one test — a cut response with a
     `Skill` call does not suspend.

   The PR says which optional tests were written.

### 7.2 `aimon-core` — `DefaultSubagentExecutorTruncationTest` (new; fixtures from `DefaultSubagentExecutorMetadataTest` / `DefaultSubagentExecutorTest`)

1. **The test #100 sketched:** a stub returning `LlmResponse.of("partial", List.of(), usage, MAX_TOKENS)`.
   - `getCompletionReason() == TRUNCATED`, `isSuccess()` true;
   - `getFinalAnswer()` ends with `TruncatedResponses.TRUNCATION_MARKER`;
   - the last assistant message in the snapshot carries the marker;
   - a WARN on `DefaultSubagentExecutor` contains `max_tokens`;
   - the output sink contains `[completed: TRUNCATED`.
   - *Fails today:* `COMPLETED`.
2. **`END_TURN`, and a response with no stop reason** → `COMPLETED` with no marker (regression guards).
3. **A cut tool response** → the counting tool is invoked 0 times; the error result equals `REFUSED_TOOL_CALL_MESSAGE`;
   the loop continues to `COMPLETED`.
4. **Reasoning clause present or absent**, as in 7.1 item 5.

Items 1 and 3 assert against the same constants as 7.1, which is the parity pin D5 relies on.

### 7.3 `aimon-core` — `TruncatedResponsesTest` (new)

- `isTruncated`: true for `MAX_TOKENS`; false for `END_TURN`, `TOOL_USE`, `UNKNOWN` and an absent stop reason.
- `reasoningClause`: `""` for 0; contains both numbers for > 0.
- The refusal text contains `max_tokens` and says the calls were not run.

### 7.4 `aimon-llm-anthropic`

- `theOtherTwoConditions` with D6's rows; run `:aimon-llm-anthropic:test`.
- Rename and comment only in the other two tests.
- If a D6 row fails, stop and record it (D6).

### 7.5 Gates

- `./gradlew format`, then `./gradlew checkAll`, reporting tests run, failed and skipped. This includes ArchUnit:
  `PackageDependencyArchitectureTest` must stay green with the new `agent.budget` type.
- `python3 scripts/check-doc-links.py`
- `python3 scripts/check-backlog-registers.py`
- `python3 scripts/check-translation-staleness.py`
- `python3 scripts/check-translation-structure.py`
- **Never `export` a key into that shell** (`TASK.md` › API keys).

### 7.6 Optional live probe

- **Request:** one or two Anthropic streaming requests forcing a tool call (`tool_choice` to a tool with a long string
  argument) with `max_tokens` ≤ 100. Pass the key inline to that one command.
- **Record** in `$RUN_DIR/build/measurements.md` (no key): the request shape, the status, and whether
  `content_block_stop` arrives for the cut `tool_use` block. Also record the last `input_json_delta` fragment's shape,
  and whether the aggregator's parse WARN fires.
- **It changes no code.** If `content_block_stop` arrives, F5 can happen in practice; if not, F5 cannot happen on
  Anthropic.
- **Skip it** rather than stall; the records then say "not observed".

---

## 8. Findings outside the issues — for `build/deviations.md` and the PR body

| Finding | Evidence | Route |
|---|---|---|
| **`LlmSkillExecutor` and `ReActLlmDeriver` run their own tool loops and never read the stop reason**: a cut tool call runs there, and a cut final answer returns as success. On either provider the blocking client WARN names the cut, but not which call | `LlmSkillExecutor.java:185-218`; `ReActLlmDeriver.java:188-215` | **Register as `L-22`** in `llm-config-surface-open-items.md`, beside the closed `L-16`: same defect class on the next loops, and `L-16`'s own placement rule puts a pair where its pair is. Before registering, the build reads both loops end to end (rules two and six) |
| **The fork has no stalled-iteration guard**, so any all-error tool loop in a fork, cut or not, runs to `maxIterations` or budget | `DefaultSubagentExecutor.java:386-470` | `deviations.md` + PR body **only**: it is not about `max_tokens`, so it does not belong in the LLM register reserved to this run |
| **`docs/design/agent-execution/orca-executor.md` §2.2** describes `TruncationRecoveryStrategy`, `FlaggingTruncationRecoveryStrategy` and `ContinuingTruncationRecoveryStrategy`, none of which exist in main sources, and describes only the final-answer shape | `git grep TruncationRecoveryStrategy` → that document only | `deviations.md` + PR body; the file is not this run's |
| **`TaskTool` prints `Status: SUCCESS` for a `TRUNCATED` fork** (`getStatus()` collapses on `isSuccess()`). The parent model's signal is the marker in the summary | `TaskTool.java:612`, `:621` | `deviations.md` + PR body; `TaskTool` belongs to `cli-model-followups` |
| **`workflow.md` §4.2 omitted `ERROR`** from the fork's reasons before this change | `docs/design/workflow/workflow.md:212-213` | fixed in passing, because this change rewrites that sentence (§4.4); named in `deviations.md` |
| **`ChunkAggregator`'s parse-failure WARN logs the whole partial JSON** | `ChunkAggregator.java:295` | `deviations.md` only; not changed |

---

## 9. Open questions

- **Q1 — Should a stall made only of refused cut responses end as `TRUNCATED` instead of `ERROR`?**
  **Recommendation: no.**
  - The guard is generic, and `TRUNCATED`'s contract carries a partial answer that a stall does not have.
  - Each of the three WARNs already names `max_tokens`.
  - Changing it would need a second counter.

  The maintainer can overturn this at review; the PR body lists it under D1.
- **Q2 — Run the optional probe?** Recommendation: only if the build has time left after the gates. Nothing depends on
  it (§7.6).
- **Q3 — `TruncatedResponses` is a new file (plus a `package-info.java` bullet) outside `TASK.md`'s listed paths.**
  D5 gives the reason and the rejected homes. No sibling touches `agent/budget/**`. Flagged so the review gate checks
  the placement rather than discovering it.
- **Q4 — Registering `L-22` (§8).** Recommended. If the review gate reads `L-16`'s placement rule differently, the
  fallback is `deviations.md` + PR body with no registration. Either way, `L-16` closes: its "무엇을" is about the agent
  loop.
- **Q5 — Should `TaskTool` print the completion reason for a fork?** Out of this run's files (§8). Recorded for the
  maintainer.
- **Q6 — The exact WARN and refusal wording.** §3 gives drafts with required content (`max_tokens`, "not run", no
  invitation to repeat, the two remedies). The build may polish the words, but not remove a required part.

---

## 10. Acceptance criteria → where each is met

| # | Criterion | Met by |
|---|---|---|
| 1 | Main executor: a cut response with tool calls gives an operator message containing `max_tokens` and follows the execute-or-not decision, pinned by tests | D1; §4.1; §7.1 items 2–7 |
| 2 | A fork's cut final answer is reported, pinned by a test | D4; §4.1; §7.2 item 1 |
| 3 | §16.8 and `L-16` say exactly what the code supports, by §16.7 / §16.9's convention; `L-16` closed iff met; register title and index row recounted from the body | D7; §4.4 |
| 4 | #101's three code statements corrected | §4.2 |
| 5 | `CHANGELOG.md` entries; `checkAll` green with numbers; four doc checks pass | §4.4; §7.5 |
| — | #101 item 3 (add paths or narrow) — "say which you did" | D6: paths added |
| — | PR body: `Closes #108`, `Closes #100`, `Closes #101`, one per line; one heading per decision (D1–D6, plus Q1) | build/PR phase |

---

## 11. After the build — departures, corrections, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 1 (PASS, no blocking findings, ten non-blocking notes), and it is not edited to look prescient. Three sources feed
this section: the run's `build/deviations.md`, the review's notes, and what was measured while building.*

**No decision in §3 changed.** D1–D6 were built as chosen: a cut response's tool calls are refused, detection is
`StopReason` in both executors through one shared type, the blocking-path WARNs stay, the fork reports a cut answer as a
turn does, and the missing resolver rows were added. What departed is the text the model and the operator read, how much
the records may claim, and two routings.

### 11.1 Where the build departed from the body

- **DV-1 — the refusal no longer says "none of them were run"** (review note 2). With streaming-tool overlap on,
  `StreamingToolScheduler.onToolUseReady` may already have run a `CONCURRENT_SAFE` call from the response — through the
  full tool path, hooks included — before the stop reason is read, and `cancelAll()` does not un-run it. The built text,
  `TruncatedResponses.REFUSED_TOOL_CALL_MESSAGE`, reads *"Cut off at max_tokens: … They were discarded: no call that
  changes anything was run, and none of them has a result. …"* Only a `CONCURRENT_SAFE` tool starts early, and that
  declaration says it changes nothing, so the sentence holds on both paths. The same note narrows, for that one case,
  D1's "Nothing on the tool path runs", §4.4's CHANGELOG draft ("No permission check or PreTool/PostTool hook runs for
  them") and F7.
- **DV-2 — the reasoning clause is counts only** (review note 4): `; the response's usage reports N output tokens and R
  reasoning tokens`. D2's "so reasoning may have used most of the max_tokens allowance" would be printed for 100 of 4000
  too, and no share can be justified as the line past which it is true — §16.8's option 2 refused exactly that number.
  "N of its M" is avoided as well, because `TokenUsage` allows a reasoning count above the output count.
- **DV-3 — the main executor's tool-call WARN says whether overlap had started calls** (review note 2). Its middle
  clause is `none of them is run`, or, when the iteration's scheduler held eager work, `calls streaming overlap had
  already started have their results discarded, and the others are not run`.
- **DV-4 — condition 2 is asserted with `containsExactly`** (review note 3). D6's reason for `contains` was wrong: at or
  below 1024 no thinking parameter is sent, so the translated finding is dropped at emission. Every D6 row passed as
  predicted, and none contradicted §16.8.
- **DV-5 — `L-22` is narrower than §8** (backlog rule six). `LlmSkillExecutor` is constructed twice, both in
  `DefaultCommandExecutionManager`, which `OrcaAgentExecutorFactory` builds, so a skill run as `/my-skill` takes that
  loop. `ReActLlmDeriver` has no construction site in main sources, so `L-22` records it as an observation.
- **DV-6 — `L-23` is registered** (review note 1). §8 sent the fork's missing stall guard to the PR body only. Review
  note 1 showed the bound is `SubagentMetadata`'s default of 1000 iterations, and no main-source
  `SubagentExecutionRequest` carries a budget, so a fork under a persistent squeeze can make up to 1000
  `max_tokens`-sized calls, each refused with a WARN. The records give that number where §4.4, D4 and F3 say
  "maxIterations or budget".
- **DV-7 — records wording from the review's remaining notes.**
  - The CHANGELOG names `WorkflowPatterns.loopUntilDry`, `WorkflowPatterns.completenessCritic` and GraalJS's
    `AgentResultView` (note 9).
  - The REPL claim is narrowed to the one line the REPL prints, `Tool '<name>' failed: …`, and the event test asserts
    that message (note 10).
  - `L-16`'s `정정` blockquote counts one row wrong and two incomplete (note 8).
  - The records say only OpenAI's Responses path fills the reasoning counter (note 6).
  - The clause is on four WARNs (note 5).
- **DV-8 — this file's home.** The body did not place itself. It lands in `agent-execution/` beside `orca-executor.md`,
  because the code change is to the two ReAct loops; the records it corrects are in `llm/`. It is indexed in
  [`../README.md`](../README.md).
- **DV-9 — tests beyond §7.** Both optional tests were written: overlap on, and the skill pre-flight (the latter in
  `OrcaAgentExecutorSkillSuspendTest`, for its fixtures). The reasoning-clause test runs a cut tool call and then a cut
  answer in one turn, so it also pins that each WARN reads the cut response's usage rather than the running total.
- **DV-10 — choices the body left open.**
  - `TruncatedResponses` gained `refusal(ToolUse)`, which §5.1 allowed.
  - The fork's class javadoc has a second sentence naming the stall guard as the one remaining difference.
  - `SubagentExecutionResult.getCompletionReason()`'s javadoc also says `getStatus()` reads `SUCCESS` for a truncated
    fork.
  - `workflow.md` §4.2 is rewritten as a plain statement, because [`../README.md`](../README.md) §3.2 keeps revision
    notes out of a design body.

### 11.2 Where the body is wrong

- **C-1 — D1 and §5.3 on a refused call's tool path.** "Nothing on the tool path runs", and the observer table's "Hooks
  for a cut call — none fire", are false for a call that streaming overlap started before the stop reason arrived
  (DV-1).
- **C-2 — D2's "all three truncation WARNs"** lists four.
- **C-3 — D2's "OpenAI's usage converters appear to fill the same counter".** Only the Responses path does; Chat
  Completions leaves it at zero, streamed and blocking.
- **C-4 — D2's first "Why" bullet lists "blocking with no live token"** as a path the executor sees. Neither executor
  takes it (§2.1, §2.2). The provider-neutral argument stands without it: it covers any `LlmClient`.
- **C-5 — D6's reason for `contains`** (DV-4).
- **C-6 — D7 against §4.4 on how many rows the `정정` blockquote corrects** (DV-7).
- **C-7 — §2.5, §6 F13 and §8 on `ReActLlmDeriver`.** "A cut tool call still runs" there is true of its code and of no
  deployment (DV-5).
- **C-8 — D1's REPL sentence** ("shows the failed call and its reason"). The REPL's tool-call line comes from
  `ToolCallDisplayHook`, a PreTool hook, which does not run for a refused call; the REPL prints only
  `Tool '<name>' failed: <message>` (DV-7).

### 11.3 The §8 findings, and which went to the backlog

The backlog items are in [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md); each links
back here.

| Finding | Now | Why |
|---|---|---|
| `LlmSkillExecutor` and `ReActLlmDeriver` never read the stop reason | backlog `L-22` | The same defect class on the next two tool loops; one of them is reachable (DV-5) |
| The fork has no stalled-iteration guard | backlog `L-23` | Its `max_tokens` consequence is up to 1000 refused iterations per fork (DV-6) |
| `orca-executor.md` §2.2 describes truncation strategy types that exist in no source file | here and the PR body | Not this run's file, and both reserved IDs are used |
| `TaskTool` prints `Status: SUCCESS` for a `TRUNCATED` fork | here and the PR body (Q5) | `TaskTool` belongs to a sibling run; the marker in the summary is the parent's signal |
| `workflow.md` §4.2 omitted `ERROR` | fixed | This change rewrote that sentence |
| `ChunkAggregator`'s parse WARN logs the whole partial JSON | here | Not changed |
| *(found in the build)* `AnthropicThinkingBudgetsTest.budgetIsClampedBelowMaxTokens` says *"HIGH clamps out of the box"*, the over-claim #101 corrected in the javadoc | here and the PR body | #101 names three statements, and this is not one of them |

**Open questions.**

- **Q1** stands as recommended. A stall made only of refused cut responses ends as `ERROR`, and each of its WARNs names
  `max_tokens`. It is local to this change.
- **Q2** was answered by running the probe (§11.4).
- **Q3** — `TruncatedResponses` lives in `at.aimon.core.agent.budget`, and `PackageDependencyArchitectureTest` stayed
  green.
- **Q4** — `L-22` is registered, narrowed (DV-5).
- **Q5** — recorded above; not this run's file.
- **Q6** — settled by DV-1 and DV-2.

### 11.4 What was measured

**Rule five, before the fix.** `TruncatedResponses` went in first, with no executor change, and the new tests ran
against the old loops. Of 40 tests in the four classes, 12 failed, each for the defect it names:

- the cut call ran — `1` invocation, `2` for two calls, and `1` on the streamed path;
- with overlap on, the eager `"ran"` result was harvested into the transcript;
- the stall guard never tripped (`COMPLETED`);
- a cut `Skill` call suspended the turn (`SUSPENDED`);
- the fork returned `COMPLETED`;
- no WARN named `max_tokens` for a cut tool call, on either executor.

After the fix the same four classes were 40 of 40 green.

**The live probe.** One billed request, 2026-09-11T02:11:04Z, `POST https://api.anthropic.com/v1/messages`.

- *Request:* `model: claude-haiku-4-5` (served as `claude-haiku-4-5-20251001`), `max_tokens: 60`, `stream: true`, one
  tool `write_file(path, content)` forced by `tool_choice`, and one user message asking for a 400-word poem in a file.
  The key was read inline into a header and never printed.
- *Response:* HTTP 200, `request-id: req_011Cevqhawj5VtNbJMQGcfSN`.

| # | Event | Payload |
|---|---|---|
| 1 | `message_start` | |
| 2 | `content_block_start` | `tool_use`, index 0, `input: {}` |
| 3 | `ping` | |
| 4–8 | `content_block_delta` ×5 | `input_json_delta`: `""`, `{"path"`, `: "/tmp`, `/sea.tx`, `t"` |
| 9 | `message_delta` | `stop_reason: max_tokens`, `output_tokens: 60` |
| 10 | `message_stop` | |

- **No `content_block_stop` arrived for the cut `tool_use` block.** On this observation the cut block never reaches
  `TOOL_USE_READY`, so streaming overlap cannot start the cut call early on Anthropic. F5 remains possible for blocks
  that closed before the cut.
- **The joined arguments are `{"path": "/tmp/sea.txt"`** — not valid JSON. `ChunkAggregator.parseArguments` would log
  its parse WARN and return an empty map. That was read from the code, not run on this stream.
- One request is an observation, not a guarantee, and no decision above depends on it.
