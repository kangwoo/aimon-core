# Design — ptc-features-core

> Status: **IMPLEMENTED** (PR 1 of two) — `aimon-core`: the tracing spans and attributes of programmatic tool calling
> (`at.aimon.core.tools.ToolSpans`, `at.aimon.core.tools.code.CodeSpanAttributes`, `at.aimon.core.tracing.SpanAttributes`,
> the tool-context keys `TRACER` and `TOOL_SPAN`), the language-change notice
> (`at.aimon.core.tools.code.ExecuteCodeLanguageNotice`), the shell engine's linear log buffer (`ByteTail`) and
> deterministic sweep (`aimon_ptc.py`), the fallback-kill assertions, and the comment-reflow guard
> (`CommentReflowTest`). The docs this changed are [`programmatic-tool-calling.md`](programmatic-tool-calling.md)
> §6.2, §12.1, §12.2, §21 and [`programmatic-tool-calling-shell.md`](programmatic-tool-calling-shell.md) §11, §12.2.
>
> **[§1](#1-after-the-build--where-the-implementation-departed), appended after the build, is where the build departs
> from this document.** Everything between this header and §1 is the body as approved — the document's preamble, §0
> and Part A, which design review round 3 passed with no blocking finding against them — kept byte-exact rather than
> corrected ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). The approved document also had a Part B
> (PR 2: `ExecuteCode` in forks and slash skills, CLI shell-engine assembly, a real-model end-to-end harness); it is
> being redesigned and is not reproduced here. The body's citations (`file:line`) are at `8d095d07`; its path
> shorthands (`core/`, `coreT/`, `ptc.md`, `shell.md`) are defined in the preamble. The review transcripts and the
> run's `TASK.md` are not in the repository. Its open questions that outlive this change are TC-21 – TC-23 in
> [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md).
>
> It is in English, like its siblings in this directory written from an approved run; `docs/design/` is not a
> translation target (`docs/project/documentation-guide.md` §5.1). For the same reason the preamble's "every doc edit
> also goes into its `.en.md` twin" had nothing to apply to: none of the PTC design documents has one.

Base: `main` @ `8d095d07`. This document has two separate parts:

- **Part A** covers PR 1 (TASK items 1–6). It is built on `herdr/ptc-features-core`.
- **Part B** covers PR 2 (items 7–9). It is built on a fresh branch from `origin/main` after PR 1 merges.

Each part can be implemented on its own. Part B depends on Part A only where it says so (§B.1.5 reuses the span helper from A.5).

Path shorthands:
- `core/` = `modules/aimon-core/src/main/java/at/aimon/core/`
- `coreT/` = `modules/aimon-core/src/test/java/at/aimon/core/`
- `ptc.md` = `docs/design/tool/programmatic-tool-calling.md`
- `shell.md` = `docs/design/tool/programmatic-tool-calling-shell.md`

Every doc edit below also goes into its `.en.md` twin, in the same commit, with the translation front-matter rules from CLAUDE.md.

---

## 0. Problem, restated

Programmatic tool calling (`ExecuteCode`) has been built in three pieces (B, C, D1) plus the Q-C4 fix. Review left several things open:

- Non-blocking findings:
  - javadoc and docs wrapped mid-sentence, again and again;
  - a quadratic log buffer in `ShellProgramRun`;
  - a housekeeping sweep whose tests can pass without the sweep ever running;
  - an unasserted fallback kill counter.
- Two features the design specifies but no piece implemented:
  - the tracing spans of §12.2;
  - the language-change notice of §6.2.

Beyond those, `ExecuteCode` cannot reach most of the tree:
- forks and slash skills never offer it, because their offer context has no settings source;
- the CLI does not assemble the shell engine.

Finally, nobody has run `ExecuteCode` against a real model.

The task is to close all of this in two PRs: (1) fixes plus observability, then (2) reach, assembly and an opt-in real-model end-to-end harness. Each PR must keep `checkAll`, the doc scripts and translations green and update the CHANGELOG.

---

# PART A — PR 1: non-blocking fixes and observability (items 1–6)

## A.1 Mid-sentence wraps (item 1)

**Root cause.** `config/eclipse/eclipse-formatter.xml` sets `comment.line_length=120` and `join_lines_in_comments=false`. When an author writes a comment line slightly over 120 columns, the formatter breaks it but never rejoins lines. One or two words end up stranded on their own line, for example `ToolOfferContext.java:206-207` (`returns` / `null`) and `VirtualShell.java:116` (`runs`).

A repo-wide heuristic scan found about 450 such lines. About 15 are in PTC code.

**Chosen approach: fix and guard, scoped to PTC.**

1. Fix the five named sites, plus every hit the new guard reports in its scope:
   - `ToolOfferContext.java:206,246` and the class javadoc at `:25`;
   - `VirtualShell.java:116`;
   - `InternalPackageBoundaryTest.java:33`;
   - `shell.md:237` (`모델` / `코드는`);
   - plus, from the scan: `ToolDescriptor.java:20,30`, `CodeExecutionEngine.java:64`, `ToolGrade.java:82`, `EngineAvailability.java:10`, `ExecutionOptions.java:155`, `LocalShell.java:73`, `LlmSkillExecutor.java:103` and `:385` (the latter is the C-review finding), and `LocalShellOutputDrainTest.java:33,39`.
2. Add a source-scan test, `coreT/architecture/CommentReflowTest.java`, modelled on `InternalPackageBoundaryTest` (plain file scan, not ArchUnit). It fails when a comment line is an **orphan**. A line is an orphan when all of these hold:
   - it is a javadoc or block-comment continuation line (`^\s*\*\s`);
   - its text has at most 2 whitespace-separated tokens and does not start with `@`, `<`, `{@code`, `-` or `|`;
   - the next line is a continuation of the same paragraph: a `*` line that is not blank and does not start a tag, `<p>`, `<li>`, `<pre>`, `</` or `*/`;
   - `len(orphan line) + 1 + len(first token of next line) <= 120`. In other words, a proper reflow would have joined them. This is the precise signature of the formatter artifact, and it excludes long single tokens such as `MemoryChatTool.java:147`.
   - **Scope** is a fixed list of PTC roots, each with the reason it is included:
     - `core/programmatic/`, `core/tools/code/`, `core/shell/`;
     - `core/agent/tool/ToolOfferContext.java`, `ToolDescriptor.java`;
     - `core/environment/RuntimeFileSystem.java`;
     - `core/skill/execution/llm/LlmSkillExecutor.java`;
     - their `src/test` mirrors;
     - `modules/aimon-graaljs/src/main/java/at/aimon/graaljs/code/`;
     - `modules/aimon-code-engine-testkit/src/main/java/`;
     - `coreT/architecture/InternalPackageBoundaryTest.java`.
   - The test reads source paths relative to the repo root the same way `InternalPackageBoundaryTest` does. It skips when the sources are not present.
   - The scanned sources from `aimon-graaljs`, `aimon-code-engine-testkit` and the docs are not declared inputs of `:aimon-core:test`. That is deliberate and has the same reason as `ReleaseGateMatchesCiGateTest`: declaring them would re-run core's tests after any edit there. So after an edit only in those trees, the test can be `UP-TO-DATE` locally; `--rerun` or CI (always clean) catches it. The test's javadoc says so.
3. Add a markdown variant to the same test, for `ptc.md`, `shell.md`, `ptc-qc4-and-nonblocking-fixes.md`, `tool-call-dispatcher.md` and their `.en.md`. It flags a prose line with at most 2 tokens when:
   - the lines on both sides are non-blank prose (not a list item, table row, heading, fence or quote);
   - the line is not inside a fence;
   - the previous line has 100 or more display columns, counting Hangul as 2.

**Rejected alternatives.**
- **Turn on `join_lines_in_comments=true`.** It would reflow every comment in the repo, about 450 sites and thousands of lines, in one formatting churn. It also risks breaking hand-laid javadoc (aligned `@param` hanging indents, lists without `<li>`). That is far beyond "non-blocking", and every open branch would conflict.
- **Make it repo-wide now.** About 430 non-PTC hits would join this PR. The guard's scope list is the extension point: a later sweep can widen it one package at a time. This is recorded as follow-up OQ-A1.
- **A Python script under `scripts/`.** Scripts there are not run by `checkAll`, while the unit test is. The doc scripts check docs, and they are a separate CI job.

## A.2 `ShellProgramRun` ERR buffer is quadratic (item 2)

**Today.** `log()` (`core/tools/code/shell/ShellProgramRun.java:262-280`) allocates `new byte[keep]` and copies the old tail on every ERR chunk. That costs O(n²) for n bytes when `maxLogBytes` (≈ 4 × `maxLogChars`) is large.

**Change.** Add a package-private helper, `core/tools/code/shell/ByteTail.java`: a bounded tail buffer that keeps the last `capacity` bytes it was given.

- **Storage.** A `byte[]` ring with `start` and `size` fields. It grows by doubling from 8 KiB up to `capacity`, so a huge `maxLogChars` does not pre-allocate. After the first wrap it is never reallocated.
- **`append(byte[] b)`** is amortized O(len(b)). When `len(b) >= capacity`, it keeps only the last `capacity` bytes of `b`, which is the existing semantics.
- **`toArray()`** returns the bytes in order, as one copy.

`ShellProgramRun` holds `private final ByteTail logBytes = new ByteTail(maxLogBytes)` under the existing `lock`. `logs()` reads it once through `toArray()`. Its UTF-8 trimming logic (`Utf8Cut.leadingContinuations` / `completeLength`) is unchanged.

**Rejected alternatives.**
- **A `ByteArrayOutputStream` trimmed when it exceeds 2× capacity.** It is also linear amortized, but it holds up to 2× the bound.
- **A `Deque<byte[]>` of chunks.** It needs per-chunk trimming and has more edge cases.

**Tests.** Add `coreT/tools/code/shell/ByteTailTest`:
- exact tail after mixed appends, wrap-around, an append larger than capacity, and growth steps;
- a linearity guard: 200 000 appends of 64 B at capacity 4 MiB finish under a generous bound, and `ByteTail` reports at most ⌈log2(cap/8KiB)⌉+1 allocations through a package-private counter. The counter, not wall time, is the assertion.

Existing `ShellProgramRun` log tests stay unchanged.

## A.3 The sweep is not deterministic (item 3)

**Today.** `aimon_ptc.py:503-505` starts `_sweep` on a daemon thread and never joins it. `os._exit(0)` after `_finish` can kill the thread before it runs. The "entry stays" tests in `ShellRuntimeSweepTest` (`ageFollowsMaxWallClock`, `floor`, `neverBesideTheRunDirectory`, `wrongBasename`, `symlinkedRoot`, `ownEntryMissing`) pass trivially in that case.

**Change: both make it deterministic and make the tests prove it.**

1. **Runtime.** `main()` keeps the thread handle in `_state["sweep"]`. At the top of `_finish()`, before anything is sent and outside `_finish_lock`, it calls `t.join(SWEEP_JOIN_SECONDS)`. `SWEEP_JOIN_SECONDS = 2.0`, a new constant.
   - Every exit path that sends `DONE` goes through `_finish`: normal completion, script error, the watchdog-error path and the output-limit path in `_watch`. So for any run that ends by itself, the sweep has finished, or 2 s have passed, before `DONE`.
   - Runs killed from outside (timeout, cancel, group kill) are unchanged: the sweep may be cut off, which is acceptable for housekeeping, and the next run continues it.
   - Joining from the `_watch` thread is safe because the sweep thread never takes `_finish_lock`.
   - The bound keeps a pathological `.aimon-ptc` (a huge directory, a slow FS) from delaying results by more than 2 s.
   - The sweep still starts **before** the program, so it usually overlaps the program and adds no latency.
2. **Tests prove it ran.**
   - Every "stays" case where the sweep is **active** also places a **canary**: an entry older than that case's sweep age, which must be removed. Examples:
     - `ageFollowsMaxWallClock`: canary 61 min old, sweep age 60 min;
     - `floor`: canary 21 min old;
     - `neverBesideTheRunDirectory`: canary inside the real `.aimon-ptc`.
     The canary's removal proves the sweep ran in that run, so the survivor's presence is a real result.
   - The guard cases (`wrongBasename`, `symlinkedRoot`, `ownEntryMissing`) have no valid root for a canary. For these, the join makes the result deterministic. In addition, `runRuntimeDirectly` gains a test-only check. It writes `config.json` itself, and adds an optional key `sweep_trace: <path>` there. The runtime reads the key only when it is present, and `_sweep` appends one line naming the guard that stopped it (`basename`, `symlink`, `own-missing`) or `swept N`. Each guard test asserts the guard it expected.
   - The engine's `config.json` writer never emits `sweep_trace`. `config.json` is written by the host and digest-verified, so a model program cannot set the key. An environment variable was rejected: `LocalShell` passes the host environment through, so the variable would be steerable from outside the engine (review-1).

**Rejected alternatives.**
- **A synchronous sweep before the program.** It adds the sweep's full latency to every run.
- **Reporting the sweep result in the `DONE` payload.** It changes the protocol for a test concern.
- **Writing the trace to stderr.** Stderr lands in the model-visible `[logs]`.

## A.4 Fallback tree kill stays at zero (item 4)

`ProcessGroupKillShell` already counts `fallbackKills` (`coreT/tools/code/shell/ProcessGroupKillShell.java:66`).

- **`ShellEngineProcessCleanupTest`.** In every case where the shell's kill is expected (the two wall-clock cases, the two cancel cases and `bridgeLimit`), assert `shell.groupKills() >= 1` and `shell.fallbackKills() == 0`. Do this in a shared `assertKilledByGroup()` next to `assertAllDead()`. In `outputLimitSelfEnd` and the completed-run case, assert both counters are zero.
- **`ShellCodeEngineGroupKillContractTest`.** Keep the `ProcessGroupKillShell` it creates in `toolContext()` in a field, and add an `@AfterEach` that asserts `fallbackKills() == 0`.

A non-zero count would mean the pid file was not there within `PIDFILE_WAIT`, so the kill went through `LocalShell`'s tree walk and could hide a Q-C4 regression.

## A.5 Tracing spans (item 5)

What exists today (from the code):
- The `Tracer` / `Span` API lives in `core/tracing`. Parenting is explicit: the parent travels as a `SpanContext` tagged into the `LlmCallMetadata` under `ToolContextKeys.LLM_CALL_METADATA_KEY`. There is no thread-local.
- `DefaultSpan.setAttribute` is synchronized.
- Loops open TOOL spans through `toolinvocation/ToolCallTracing.open(...)`, which sets the attributes `iteration` and `invokerType`, and `contextUnder(span, ctx)` re-points the metadata key at the TOOL span. Inside `ExecuteCodeTool.execute()`, the metadata key therefore already names **ExecuteCode's TOOL span**.
- What a tool lacks is the `Tracer` itself.

### A.5.1 Tracer and current span in the tool context

New keys in `core/tools/ToolContextKeys.java`:

| Key | Type | Write-once | Bound by |
|---|---|---|---|
| `TRACER` | `Tracer` | yes | `OrcaAgentExecutor` where it puts `LLM_CALL_METADATA_KEY` into the tool context (around `:903` / `resolveEffectiveToolContext` `:2547`); `DefaultSubagentExecutor` fork tool context (`:1290-1372`); `RoutineExecutor` where it opens TOOL spans for direct tool steps (`:1376,1413`). Absent means no tracing. |
| `TOOL_SPAN` | `Tracer.Span` (narrowed — see below) | **no** (rotated per nested call) | `ToolCallTracing.contextUnder(span, ctx)` puts it beside the rotated metadata |

`TOOL_SPAN` is typed as a new one-method interface, `core/tracing/SpanAttributes` (`void setAttribute(String key, String value)`). A tool can annotate its own span but cannot close, error or re-parent it. `Tracer.Span` extends it, which is binary-compatible because `Span` already declares that method. The accessor `ToolCallTracing.currentSpan(ToolContext)` returns `Optional<SpanAttributes>`.

**Rejected alternatives.**
- **Inject the tracer into `ExecuteCodeTool` at construction.** The executor's tracer is set after assembly (`volatile Tracer tracer`), and the provider is created by the stack long before. This would also give a second source of truth.
- **A child span inside `ExecuteCode` that carries `ptc.tool_scope`.** The design puts the attribute on the ExecuteCode span itself.

### A.5.2 Nested TOOL span (code path)

The span is opened in `core/tools/code/DefaultToolCallBridge.dispatch(...)` (`:226-235`), on the bridge executor thread, around the `dispatcher.dispatch(...)` call:

```java
Tracer.Span span = ToolCallTracing.openNested(tracerOf(parentContext), parentContext, toolUse,
        ToolCallTracing.PATH_CODE);           // null when no tracer / no parent carrier
ToolContext ctx = ToolCallTracing.contextUnder(span, nestedContext);   // re-parent deeper spans
try { result = dispatcher.dispatch(toolUse, origin, ctx); ToolSpans.recordOutcome(span, result, TracePayloadPolicy.SUMMARY); }
catch (Throwable t) { ToolCallTracing.recordFailure(span, t); throw…; }
finally { if (span != null) span.close(); }
```

- `ToolCallTracing.openNested` is new and shares its body with `open`:
  - type `TOOL`, name = tool name, inputs `{toolUseId: "<parent>#n", input}`;
  - attributes `tool.path=code`, `ptc.parent_tool_use_id=<ExecuteCode id>`, `ptc.language=<engine language>`, and `iteration` copied from `ITERATION_COUNT` when present.
  - The parent is the `SpanContext` read from `parentContext`'s metadata, which is the ExecuteCode TOOL span. All calls go through `Spans.startChildSafely`, so a throwing tracer never fails a nested call.
- **Payload policy.** Nested spans always record a summary only: `{isError, contentChars}` with no content. No tool-context key carries a `TracePayloadPolicy`, and the nested results are data the program filters, often bulk and possibly sensitive. A later change can add a key next to `TRACER` if full payloads are wanted. The builder uses whichever `TracePayloadPolicy` constant means "no content" (the name here is illustrative).
- **Bridge refusals** never reach `dispatch`: closed, call limit, not callable, argument size, write cap, quota. Each still gets a zero-length TOOL span with `tool.path=code` and `ptc.refused=<reason code>`, ended with `error(message)`. The trace then lists the same calls as `renderPayload` (§12.2: "중첩 호출마다").
- **Results that arrive after the bridge is `CLOSED`.** The span is closed in the executor thread's `finally` whatever the bridge state. So a late result is still recorded in the span, as §13.2 requires ("기록·span 에만 남긴다").
- **Direct path.** `ToolCallTracing.open` (the loops) also sets `tool.path=direct`, so both paths carry the attribute. This is a small addition to existing spans and is noted in CHANGELOG.

### A.5.3 `ptc.tool_scope` on the ExecuteCode span

In `ExecuteCodeTool.execute()`, after the plan is frozen, `ToolCallTracing.currentSpan(context)` is used to set:
- `ptc.language`;
- `ptc.tool_scope=<effective scope wire name>` whenever the chosen engine `isToolScopeBound()`.

The design (shell.md §12.2) asks for the attribute "when unrestricted". Setting it for every bound run is a superset, and makes `environment` / `host` visible as well. This is recorded as deviation A-DV1.

The provider's startup WARN for `unrestricted` stays: §12.2 lists it under "로그 (WARN, 시작 시)".

### A.5.4 `ptc.unavailable_reason` on the iteration span

The problem: `describe()` runs many times per iteration, and `ExecuteCodePlan.unavailableReason()` (`core/tools/code/ExecuteCodePlan.java:208`) never leaves it.

- **`ToolDescriptor`** gains `Optional<String> getUnavailableReason()`, plus the builder method `unavailableReason(String)`. It is meaningful only when `!isOfferable()`. `ExecuteCodeTool.describe` fills it from the plan in the unavailable branch (`:183-185`), but **only when the agent enabled PTC**, which is the same condition as the WARN in `ExecuteCodePlan.of` (`:78-83`). An agent without `programmatic-tools` gets no attribute on every iteration.
- **`ToolOfferContext.offer()`** collects, while it iterates, `name → reason` for admitted, non-offerable descriptors that have a reason. It exposes them as `Map<String,String> getUnavailableReasons()`, filled by the last `offer()`, the same way `codeHosted` is today.
- **New helper** `core/tools/code/CodeSpanAttributes.annotateIteration(SpanAttributes iterationSpan, ToolOfferContext offer)`. It sets `ptc.unavailable_reason` from `getUnavailableReasons().get(ExecuteCodeTool.TOOL_NAME)`, and does nothing when that value is absent. The attribute names are constants in that class: `UNAVAILABLE_REASON`, `TOOL_SCOPE`, `LANGUAGE`, `PATH`.
- **Call sites.** `OrcaAgentExecutor` calls the helper right after `offerContext.offer()` (`:1864-1871`), with the `iterationSpan` local. The fork loop (`DefaultSubagentExecutor`, after `availableToolDefinitions`) gets the call in **Part B** (§B.1.5), because before PR 2 a fork never has PTC settings, so the reason would never be set.
- **The slash-skill loop has no spans at all**, neither ITERATION nor TOOL. Adding a tracer to that loop is out of scope. Its nested calls are still traced, because the caller's COMMAND span carrier is in its context (`OrcaAgentExecutor.java:2390` puts `LLM_CALL_METADATA_KEY = commandTrace.metadata()`): the skill's `ExecuteCode` reads that carrier and its nested spans parent to it. A tracing test asserts this: a slash skill running `ExecuteCode` with a scripted engine yields nested TOOL spans parented to the COMMAND span. In PR 1 the skill does not offer `ExecuteCode`, so the test calls `ExecuteCodeTool` with the skill's per-call context. The end-to-end form follows in PR 2. Recorded as A-DV2.

**WARN stand-ins.** The design keeps both WARNs as operator logs (§12.2: "로그 (WARN, 한 번씩)", "시작 시"). Only the comment at `ExecuteCodePlan.java:76-77` ("the span attribute … is not implemented") is removed. "Replace the WARN-only stand-ins" therefore means adding the spans the WARNs were standing in for, not deleting doc-listed WARNs. Recorded in `build/deviations.md` so reviewers see the reading.

### A.5.5 Architecture

`tools.code..` may not depend on `toolinvocation..` (`ProgrammaticToolArchitectureTest`). `ToolCallTracing` lives in `core/toolinvocation/`, so its helpers cannot be called from the bridge. The fix:

- Move the span-only helpers (`openNested`, `contextUnder`, `currentSpan`, `recordOutcome`, `recordFailure`) into a neutral class, `core/tracing/ToolSpans`. `ToolCallTracing` delegates to it, keeping its signatures for the loops.
- `ToolSpans` may depend on `tracing`, `agent.tool` (`ToolResult`, `ToolDispatchResult`) and `tools.ToolContextKeys`.
- The builder must check the ArchUnit rules (`ProgrammaticToolArchitectureTest`, `PackageDependencyArchitectureTest`). If `tracing → tools` is forbidden, place `ToolSpans` in `core/agent/tool/` instead (the package of `ToolOfferContext`, which both sides already use).

## A.6 Language-change notice (item 6, ptc.md §6.2)

**Spec.** When the language changes between executions of the same session (isolation `HOST` ↔ `SANDBOXED`), "ExecuteCode now runs JavaScript (was Python)" goes into that iteration's observation as an environment notice.

**The hard part is state.** "Which language did this session last use" must survive restarts and node moves (scope-model), so it belongs on the session record. The D1 deviation stopped at exactly this point.

**Chosen approach: derive the previous language from the persisted conversation, with no new state.** The conversation messages are already record-side (`SessionRecord.getMessages()`). The notice is needed exactly when the model's own past `ExecuteCode` use in this conversation was in another language: the description it sees on every call is always current, and only its history can mislead it. So the history is both the right state and the right condition.

1. **Marker in every `ExecuteCode` observation.** The summary line becomes `[ExecuteCode (Python): 14 tool calls (Read x12, Grep x2), …, 3.2s]`.
   - The change is in `ExecuteCodeObservation.summary` (`:113-125`), which takes the display name from `ExecuteCodeDescription.displayName(language)`.
   - The summary is on both success and error results (§12.1 puts the call summary in error messages too), so every result carries it.
   - This changes the §12.1 shape; the doc example is updated. Recorded as A-DV3.
2. **Detection.** Add `core/tools/code/ExecuteCodeLanguageNotice.java` with `static Optional<String> check(List<Message> view, Function<String, Optional<String>> loggedResult, ToolDescriptor offeredExecuteCode)`.
   - **Inputs.**
     - `view` is the conversation **this iteration actually sends to the model**: `compactionDecision.getView().getMessages()`. It is computed at `OrcaAgentExecutor.java:1788` and passed to `invokeGateway` at `:1886`, so it is in scope where the notice is computed. It reflects elision, summary spans and drops. It is **not** `TranscriptBuffer.getConversationMessages()`: that returns every `CONVERSATION`-origin log entry and ignores the view state (`TranscriptBuffer.java:500-508`; review-2).
     - `loggedResult` looks up a tool result's content **as recorded in the log** by `tool_use_id`. It is backed by `TranscriptBuffer.getMessages()`, which is the append-only log and is not rewritten by view operations (`elideInView`, `summarizeView`, `dropFromView`). It is rewritten only by the deprecated version-1 paths (`replaceWith`, `replaceMessageAt`, which `TimeBasedMicrocompact` uses), and sealing moves entries out of it.
     - It reads the offered language from a new typed descriptor field, `ToolDescriptor.getCodeLanguage(): Optional<String>`, set by `ExecuteCodeTool.describe` from `engine.language()`. This replaces any parsing of `approvalVia`.
   - **The condition is keyed on what the model can see: its past `ExecuteCode` *programs*, not its past results.** Review-1 pointed out that compaction can clear a result body while leaving the `tool_use` and its `code` input in view. The production path for that is the context engine eliding a body in the view (`RollingContextEngine` → `TranscriptBuffer.elideInView`, `RollingContextEngine.java:494`). The deprecated `TimeBasedMicrocompact` does the same thing by rewriting the log, but it has no production wiring. That is the case the notice exists for: the model still sees old code in the old language.
   - **Reading a marker from one tool_result's content** (the function `languageOf(content)`):
     1. Strip a **trailing hook-feedback block**. `ToolDispatchResults.toToolUseResult` (`:76-79`) appends the PostTool chain's feedback **after** the tool's text, as `"\n\n" + SystemReminderFormatter.wrap(HookFeedback.REMINDER_KEY, …)`. So when the content ends with that wrapper's closing tag, cut from the last opening tag for `REMINDER_KEY` together with the `"\n\n"` before it (review-2). The prepended `[environment]` lines are at the head and do not matter here.
     2. The marker is then the **last line of what remains**, which is the tool's own text, where `ExecuteCodeObservation.appendSummary` always puts the summary. It must match `^\[ExecuteCode \((JavaScript|Python)\): .*\]$`.
     - A program that prints a look-alike line, or a look-alike reminder block, cannot forge the marker: its stdout always comes **before** the real summary line.
     - `languageOf` is a single helper. Both this check and the E2E assertion B.3.3 2(b) use it.
   - **Invariant the algorithm relies on.** Every language change gets a notice no later than the first `ExecuteCode` result in the new language. The notice is attached to the first tool result of the iteration in which the change is first seen, and an `ExecuteCode` call in that iteration *is* such a tool result. So any program whose own marker is unreadable ran in the language of the most recent **readable** marker before it.
   - **The algorithm.** Scan `view` **backwards** and collect the first **known** marker, which is either:
     - (A-known) an assistant `tool_use` named `ExecuteCode` (id `u`) whose language is readable: from its result in `view`, or else from `loggedResult(u)` (an elided view body is still whole in the log);
     - (N) a delivered notice: a `tool_result` with a head line `[environment] ExecuteCode now runs (JavaScript|Python)…`. Its body is read from the view, or from `loggedResult` when the view body is a placeholder. This includes the conservative notice below.
     - **Unreadable programs are skipped.** An `ExecuteCode` tool_use whose result has no readable marker in either place is "A-unknown". Causes: the log copy was sealed or rewritten, the result predates A-DV3, or a PostTool hook rewrote the text. The scan remembers that it saw one and keeps going.
   - **Result:**
     - A known marker found, same language as offered → empty.
     - A known marker found, different language → `ExecuteCode now runs <New> (was <Old>)`.
     - No known marker found, but at least one A-unknown seen → the conservative notice `ExecuteCode now runs <New>; earlier ExecuteCode programs in this conversation may be in another language`. Once delivered, that notice is a known (N) marker newer than those programs, so later scans stop there. It is not repeated even when every later result is unreadable, for example under a hook that rewrites `ExecuteCode` output.
     - No `ExecuteCode` program and no notice in view → empty. Either `ExecuteCode` was never used, or full (L3) compaction replaced the programs with a summary in the view, so no old code is visible to imitate. Because `view` is the real view (review-2), the summarized programs are absent from it even though they are still in the log.
   - The check runs only when `ExecuteCode` is in this iteration's offer. It scans the view at most once, making one log lookup per program it meets, and stops at the first known marker.
3. **Delivery.** In `OrcaAgentExecutor`, the notice is computed once per iteration right after `offer()`, using the conversation that iteration sends. When it is present, it is prepended to the **first tool result in `tool_use` order** of that iteration (deterministic under parallel dispatch), as a notice entry in that result's `ToolDispatchResult.notices` list. It is then rendered by the existing `ToolDispatchResults.toToolUseResult` → `EnvironmentNotices.render` path as `[environment] ExecuteCode now runs …`, the same shape as the provider notices. The builder finds the exact spot where Orca turns `ToolDispatchResult`s into `ToolUseResult`s.
   - **An iteration with no tool call delivers nothing.** Because the condition is derived from history, it holds again on the next iteration or turn that offers `ExecuteCode`, so the notice is not lost. Once delivered, marker (b) stops it from repeating.
4. **Scope of loops.** Main loop only. A fork's and a slash skill's conversations are execution-local, and a single execution's environment (and so its language) is fixed. So the language cannot change inside them, even after Part B gives them `ExecuteCode`. Recorded in §21.

**Rejected alternatives.**
- **A new `SessionRecord` field** (`lastExecuteCodeLanguage`). It is a persisted schema change across the codec and three backends (Mongo, Postgres, Redis) for one string. It duplicates what the conversation already proves.
- **A `LiveSession`-scoped field.** It is lost on a node move or eviction, which violates scope-model ("재시작·축출·노드 이동을 넘어 살아남아야 하는 값은 레코드 쪽"). The notice would silently be missed after a move.
- **Read the language from `renderPayload`.** It is not persisted (`ToolUseResult` javadoc: excluded from serialization and from the session snapshot codec).
- **Push the notice into `ExecutionEnvironment.drainNotices()`.** That queue is provider-owned and has no enqueue SPI. Adding one for a core-originated notice would invert ownership.

**Failure modes.**
- **Forged marker.** A tool result whose text contains a fake (b) line at its head, for example a `Read` of a crafted file, can suppress or trigger one notice line. The impact is a misleading line, with no permission effect. Requiring (a) to belong to an `ExecuteCode` tool_use limits forgery to (b), and (b) must be a head line of the form `EnvironmentNotices.render` produces.
- **Compaction clears result bodies but keeps the programs** (`elideInView`). The language is read from the log copy. When the log copy is gone as well (sealed, a version-1 rewrite such as `TimeBasedMicrocompact`, or pre-A-DV3 history), the program is skipped and the scan reaches an older known marker. If there is none, the conservative "may be in another language" notice is sent once. The model is never left looking at old-language code without a warning.
- **A PostTool hook adds feedback to `ExecuteCode` results.** The trailing hook-feedback block is stripped before the marker is read, so no false notice is sent (review-2). A hook that **rewrites** the text and drops the summary makes those programs unreadable. They are skipped, and at most one conservative notice is sent.
- **Full compaction removes the programs from view.** No notice is sent. Nothing old is left in view to copy. A summary might mention the language in prose; that is accepted.
- **A delivered notice's own body is cleared.** It is read from the log. If the log copy is gone too, the (N) marker is lost and the next scan reaches the (A) program again, so the notice may be sent once more. That is bounded by compaction events and costs one line.

## A.7 Part A — changes by file

| File | Change |
|---|---|
| `config/...` | none |
| `coreT/architecture/CommentReflowTest.java` | **new** (A.1) |
| PTC sources listed in A.1, `shell.md:237` (+ `.en.md` if the same wrap exists there) | reflow |
| `core/tools/code/shell/ByteTail.java` | **new** (A.2) |
| `core/tools/code/shell/ShellProgramRun.java` | use `ByteTail` |
| `core/resources/.../shell/aimon_ptc.py` | sweep join, optional `config.json` key `sweep_trace` (A.3) |
| `coreT/tools/code/shell/ShellRuntimeSweepTest.java` | canaries, guard trace asserts |
| `coreT/tools/code/shell/ShellEngineProcessCleanupTest.java`, `ShellCodeEngineGroupKillContractTest.java` | counter asserts (A.4) |
| `core/tracing/SpanAttributes.java` | **new**; `Tracer.Span extends SpanAttributes` |
| `core/tracing/ToolSpans.java` (or `core/agent/tool/`) | **new** (A.5.5) |
| `core/toolinvocation/ToolCallTracing.java` | delegate; `tool.path=direct` |
| `core/tools/ToolContextKeys.java` | `TRACER`, `TOOL_SPAN` |
| `core/agent/impl/orca/OrcaAgentExecutor.java` | bind `TRACER`; `CodeSpanAttributes.annotateIteration`; language notice |
| `core/subagent/execution/DefaultSubagentExecutor.java` | bind `TRACER` |
| `core/tools/code/DefaultToolCallBridge.java` | nested spans, refusal spans |
| `core/tools/code/ExecuteCodeTool.java` | `ptc.tool_scope`/`ptc.language` attrs; descriptor `unavailableReason`, `codeLanguage` |
| `core/tools/code/ExecuteCodePlan.java` | drop stale comment; expose reason for descriptor |
| `core/tools/code/CodeSpanAttributes.java` | **new** |
| `core/tools/code/ExecuteCodeObservation.java` | language in summary |
| `core/tools/code/ExecuteCodeLanguageNotice.java` | **new** |
| `core/agent/tool/ToolDescriptor.java` | `getUnavailableReason()`, `getCodeLanguage()` |
| `core/agent/tool/ToolOfferContext.java` | `getUnavailableReasons()` |
| `ptc.md` (+en) | §12.1 example; §12.2 attribute list; §21: the C-deviation rows for trace and language notice become "implemented" with the A-DVs; the D1 rows for `ptc.tool_scope` and the language notice are updated |
| `shell.md` (+en) | §12.2: `ptc.tool_scope` set for every bound run |
| `CHANGELOG.md` `[Unreleased]` | Added: spans/attrs, notice. Changed: summary line format, `tool.path` on direct spans, sweep join. Fixed: ERR buffer |

## A.8 Part A — failure modes

| Situation | Handling |
|---|---|
| No tracer, or tracer throws | `Spans.*Safely` returns null or no-op. A nested call never fails because of tracing. Test with the existing `ThrowingTracer` pattern. |
| Nested call outlives bridge close | The span closes in the executor `finally`; the result is recorded only in the span. |
| Tool calls `TOOL_SPAN` after its span closed | `DefaultSpan.setAttribute` after close is a no-op or harmless (the builder verifies this; if it is not harmless, guard it in `ToolSpans.currentSpan`). |
| Sweep hangs | Join bound of 2 s. The daemon dies at `os._exit`. |
| Huge `maxLogChars` | `ByteTail` grows lazily up to the clamp `MAX_ARRAY_BYTES`. |
| Reflow guard false positive | A per-line escape: a trailing `// reflow-ok` is not possible inside javadoc, so the test has an `ALLOWED` set of `path:line-text` entries, each with a reason, like `InternalPackageBoundaryTest.ALLOWED`. |

## A.9 Part A — test strategy

- **Unit tests:**
  - `ByteTailTest`;
  - `CommentReflowTest`, which is itself the check; plus a small self-test on synthetic strings for the orphan predicate (positive: `returns`/`null`; negative: a long token, a `<p>` boundary, a list);
  - `ExecuteCodeLanguageNoticeTest`:
    - no history;
    - same language;
    - change → notice;
    - notice already delivered → empty;
    - a marker in a non-ExecuteCode result is ignored for (a);
    - error-result marker.
- **Tracing:**
  - `DefaultToolCallBridgeTracingTest`, using `DefaultTracer` + `InMemoryTraceSpanStore`, pattern from `ToolCallTracingTest`:
    - two nested calls → two TOOL spans whose parent is the ExecuteCode span, with `tool.path=code`, ids `#1` / `#2`;
    - a refusal span with `ptc.refused`;
    - a throwing tracer → calls succeed;
    - concurrent calls (`CONCURRENT_SAFE`) produce distinct spans.
  - `OrcaAgentExecutorTracingTest` additions:
    - an agent with `programmatic-tools` and no engine → the iteration span has `ptc.unavailable_reason` = "no javascript engine is assembled";
    - an agent without PTC → no attribute;
    - an end-to-end run with `ScriptedEngine` → the ExecuteCode span has `ptc.language`;
    - with a tool-scope-bound scripted engine → `ptc.tool_scope`;
    - direct spans have `tool.path=direct`.
- **Language notice, end to end:** an `OrcaAgentExecutorTestSupport` scripted LLM plus a test `ExecutionEnvironmentProvider` whose isolation flips between executions (HOST on turn 1, SANDBOXED on turn 2), with two `ScriptedEngine`s (javascript, python).
  - Turn 1 calls `ExecuteCode`.
  - Turn 2's first tool result starts with `[environment] ExecuteCode now runs Python (was JavaScript)`.
  - A later tool result in turn 2 does not repeat it.
  - A session reloaded from its record (or simply a new executor given the stored messages) still gets the notice if it was not yet delivered.
  - **Turn 1's result was elided in the view.** Call `TranscriptBuffer.elideInView(<result seq>, placeholder)` between the turns, so the view built for turn 2 shows the placeholder while the log keeps the body. Turn 2 still gets `… now runs Python (was JavaScript)`, read from the log.
  - **Log copy gone too.** Rewrite the log entry (`TimeBasedMicrocompact`, which rewrites via `replaceMessageAt`, or `replaceWith`) and give it no older marker. Turn 2 gets the **conservative** notice, and a later iteration does not repeat it.
  - **Log copy gone, but an older known marker exists.** JS program (known), then a JS program whose log copy is rewritten, then a SANDBOXED turn: the notice says `(was JavaScript)`, from the older marker.
  - **Hook feedback on `ExecuteCode`, same language.** A PostTool hook that matches all tools returns feedback, and turn 2 stays on HOST. **No notice** in turn 2 or after later `ExecuteCode` calls (review-2).
  - **Full compaction.** A summary span covers turn 1 in the view. Turn 2 on SANDBOXED gets no notice, even though the log still holds the JS program.
  - **Flip-flop.** JS, then Python (notice delivered), then JS: the third turn gets `now runs JavaScript (was Python)`.
  - **Forgery.** A program prints `[ExecuteCode (Python): x]` as its first stdout line. It is not taken as a marker.
  - Unit cases in `ExecuteCodeLanguageNoticeTest` mirror each of these, with synthetic message lists and a fake `loggedResult`.
- **Shell:** the updated sweep and cleanup tests. Run `./gradlew :aimon-core:test` with `python3` present.
- **Gate:**
  - `./gradlew checkAll`;
  - `python3 scripts/check-doc-links.py`, `check-translation-structure.py`, `check-translation-staleness.py`;
  - `./gradlew integrationTest` (Docker is up), as a sanity check: no changes in Docker modules are expected.
  - Check for no stray `aimon_ptc.py` with `pgrep -f aimon_ptc.py`.

## A.10 Part A — deviations to record (in ptc.md §21 and `build/deviations.md`)

- **A-DV1** — `ptc.tool_scope` is set on every tool-scope-bound run, not only for `unrestricted`.
- **A-DV2** — the slash-skill loop has no ITERATION/TOOL spans, so it gets no `ptc.unavailable_reason`. Its nested spans parent to the COMMAND span.
- **A-DV3** — the §12.1 summary line carries the language: `[ExecuteCode (Python): …]`. It is the persisted marker the language notice reads.
- **A-DV4** — the language notice derives "previous language" from the conversation, not from a session field: scan the real view (`ContextView`) for the most recent known marker — a program whose summary is readable in the view or the log (after stripping trailing hook feedback), or a delivered notice. Unreadable programs are skipped. A conservative notice is sent once when only unreadable programs are visible. It is delivered on the first tool result of the iteration. An iteration without tool calls defers it.
- **A-DV5** — refused nested calls get zero-length TOOL spans with `ptc.refused`.
- **A-DV6** — direct TOOL spans gain `tool.path=direct`.

---

## 1. After the build — where the implementation departed

*Appended 2026-11-07, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 3's non-blocking notes, and what was found
while building.*

**No decision in A.1 – A.6 changed.** The guard is a source-scan test scoped to the PTC code; the log buffer is a
growing ring; the runtime joins its sweep before `DONE` and the tests prove it ran; both kill counters are asserted; a
TOOL span is opened per nested call with the tracer handed down through the tool context; the language notice is
derived from the conversation the iteration sends, with no new session state. A-DV1 – A-DV6 are implemented as
written and recorded in [`programmatic-tool-calling.md`](programmatic-tool-calling.md) §21.

### 1.1 Where the build departed from the body

- **`ToolSpans` is in `at.aimon.core.tools`** (A.5.5). Both places the body allows are infeasible:
  `ToolContextKeys` now names `Tracer` and `SpanAttributes`, so a `ToolSpans` in `tracing` that reads the tool context
  closes a `tools <-> tracing` cycle, and one in `agent.tool` would need `agent -> tools`; neither pair is in
  `PackageDependencyArchitectureTest`'s exact baseline. `tools` is where `ToolContextKeys` and `EnvironmentNotices`
  live, and both the bridge (`tools.code`) and `ToolCallTracing` (`toolinvocation`) may depend on it. The nested-call
  specifics — `openNested` and the refusal span, with `ptc.parent_tool_use_id`, `ptc.language`, `ptc.refused` — are
  package-private in `CodeSpanAttributes`; `ToolSpans` keeps the generic halves (`tracerOf`, `currentSpan`, `openTool`,
  `contextUnder`, `recordOutcome`, `recordFailure`), and `ToolCallTracing` delegates with its signatures unchanged.
- **The language notice is applied where results are published, keyed on the first `tool_use` id** (A.6 step 3;
  review 3, note 1). The body puts it in the first result's `ToolDispatchResult.notices` and asks the builder to find
  the spot. There is no such spot: results are converted per tool inside `dispatchSingleTool`, on pool or
  eager-dispatch threads, and each one is published to the event stream (`emitToolResultReady`) from the dispatcher's
  `onCompleted` callback or the harvest loop **before** `executeToolUses` returns. So `executeToolUses` builds a
  `UnaryOperator<ToolUseResult>` for the first `tool_use` id and applies it at every publication point and to the
  returned list, in all three dispatch shapes. The text is `EnvironmentNotices.render(List.of(notice))` at the head of
  the result — what the body's path would have rendered. A response cut off at `max_tokens` delivers nothing, like an
  iteration without tool calls.
- **A notice body is read from the log whenever the log copy differs from the view's** (A.6 step 2). Nothing marks a
  view body as a placeholder. The log is read once per iteration, lazily, into a map
  (`ExecuteCodeLanguageNotice.loggedResults`), so each lookup is constant time and an iteration whose view resolves its
  marker never reads the log.
- **The Markdown reflow predicate is wider** (A.1 step 3). The site the body names, `shell.md:237`, is a stranded word
  at the *end* of a short line (`아니다 — §6)을 끝낸다. 모델`), not a line of at most two tokens. The guard also flags a
  prose line of at most 60 display columns between prose lines whose previous one is at least 100 columns; over the
  four PTC documents that rule matches exactly that line. The Java predicate also ends a paragraph at the other block
  elements (`<ul>`, `<ol>`, headings, tables, `<blockquote>`, definition lists), skips an orphan starting with `<`, and
  skips `<pre>` regions. `coreT/programmatic` does not exist and is not in the scope; this document is.
- **More orphan sites than the body listed were fixed** — the guard reported `ExecuteCodeToolProvider.java:19`,
  `LocalShell.java:78,122`, `shell/impl/local/package-info.java:16`, `ShellTimeoutException.java:70`,
  `shell/package-info.java:90`, and one more in `ToolOfferContext`. `OrcaAgentExecutor`'s
  `resolveEffectiveToolContext` javadoc, outside the scope but edited anyway, was rejoined too.
- **Where `TRACER` is bound** (A.5.1). The main loop binds it in `resolveEffectiveToolContext`, the one place each
  iteration's context gets metadata that names the ITERATION span, not where the turn's metadata (which names no span
  yet) goes in. The slash-command context also gets it when a COMMAND span was opened — a site the table omits but
  A-DV2 needs. The fork binds it in `IterationTrace.open` beside the iteration's rotated metadata, from the same
  per-execution snapshot its loop uses. It is write-once and listed in `ToolContextKey`'s built-in names.
- **Small shapes.** `ExecuteCodePlan` gained `reportedReason()` (the reason, only under the WARN's condition) and
  `toolScope()`; `CodeSpanAttributes` also names `PARENT_TOOL_USE_ID` and `REFUSED`, and its nested-call helpers are a
  per-bridge object (`CodeSpanAttributes.Nested`) rather than static methods of eight parameters; `ptc.refused`
  carries the existing refusal kinds verbatim (`closed`, `call limit`, …); a nested call found cancelled before it ran
  gets its span too, ended in error; `ExecuteCodeObservation` takes the language as a parameter. A span's
  `setAttribute` after close (A.8) is harmless in `DefaultTracer` — the span is already recorded — so no guard was
  added.
- **The sweep tests' programs are `pass`** (A.3). They slept 0.5 s, which let a "stays" case pass on the program's
  length rather than on the join. `_finish` joins only when the run is not yet finished and the caller is not the
  sweep thread.
- **Some language-notice cases are tested on synthetic views, not through the loop** (A.9). Through
  `OrcaAgentExecutor`: change, no repeat (also under streaming overlap with both calls eligible for eager dispatch),
  same language under PostTool feedback, a new executor over the stored record after a turn without tool calls
  deferred the notice, flip-flop, forgery. The elided-body, rewritten-log (conservative
  notice and older marker) and full-compaction cases are in `ExecuteCodeLanguageNoticeTest`, where their logic lives;
  driving `elideInView` or a version-1 rewrite between two turns needs a handle on the buffer the executor loads
  internally, which this build did not add.

### 1.2 Review 3's notes: taken, and left

| Note | Outcome |
|------|---------|
| Delivery under the streaming scheduler — confirm nothing is published before `executeToolUses` returns | Something is (`ToolResultReady`); the notice travels with the first `tool_use` id (§1.1) |
| `_finish` is reachable from `_violation` on the program's thread | Taken: the docstring names all three callers; the join is skipped once finished and never runs on the sweep thread |
| The release `SKILL.md` rewording must keep `ReleaseGateMatchesCiGateTest` green | Not applicable to PR 1 — the rewording is Part B. The file is untouched and the test is green |
| The `programmatic` field belongs on the dispatcher builder; the E2E `pgrep` check is vacuous | Part B; carried to its redesign |

### 1.3 Open questions that went to the backlog

- **OQ-A1 (widen the reflow guard beyond PTC)** — **TC-21**. The guard's scope list is the extension point; turning
  on `join_lines_in_comments` stays rejected.
- **Full payloads on nested spans** (A.5.2: "a later change can add a key next to `TRACER`") — **TC-22**.
- **The slash-skill loop has no ITERATION or TOOL spans** (A-DV2) — **TC-23**.

The three are in [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md), the
register that holds the other PTC items.

### 1.4 Not verified here

- The CI run of the shell tests (that they ran rather than skipped for want of `python3`) belongs to the PR.
- A real tracer exporter: spans were checked through `DefaultTracer` and `InMemoryTraceSpanStore` only.
- That the streaming-overlap test's two calls were in fact run eagerly, rather than by the harvest's inline fallback,
  is not asserted; both paths apply the same operator.
