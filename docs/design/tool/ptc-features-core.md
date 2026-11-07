# Design — ptc-features-core

> Status: **IMPLEMENTED** (both PRs). **PR 1** — `aimon-core`: the tracing spans and attributes of programmatic tool
> calling (`at.aimon.core.tools.ToolSpans`, `at.aimon.core.tools.code.CodeSpanAttributes`,
> `at.aimon.core.tracing.SpanAttributes`, the tool-context keys `TRACER` and `TOOL_SPAN`), the language-change notice
> (`at.aimon.core.tools.code.ExecuteCodeLanguageNotice`), the shell engine's linear log buffer (`ByteTail`) and
> deterministic sweep (`aimon_ptc.py`), the fallback-kill assertions, and the comment-reflow guard
> (`CommentReflowTest`). **PR 2** — `ExecuteCode` in forks and slash skills (`ProgrammaticToolSettings.narrowedBy`,
> `ProgrammaticFrontmatter`, `ToolContextKeys.CALLER_PROGRAMMATIC`, the breaking
> `ToolCallDispatcher.narrow(registry, allowedTools, programmatic)`), the CLI's shell-engine assembly
> (`programmatic.enablePython`, `programmatic.shellEngineToolScope`) and the real-model harness
> (`./gradlew :aimon-cli:llmTest`). The docs these changed are [`programmatic-tool-calling.md`](programmatic-tool-calling.md)
> §6.2, §7.2, §12.1, §12.2, §16, §18, §21, [`programmatic-tool-calling-shell.md`](programmatic-tool-calling-shell.md)
> §11, §12.2, and [`tool-call-dispatcher.md`](tool-call-dispatcher.md) §3, §4, §6, §7.
>
> **[§1](#1-after-the-build--where-the-implementation-departed) and [§2](#2-after-the-pr-2-build--where-the-implementation-departed),
> appended after each build, are where the builds depart from this document.** Everything between this header and §1 is
> the body as approved — the preamble, §0 and Part A (design review round 3 passed them), then Part B and the open
> questions (design review round 4 passed Part B) — kept byte-exact rather than corrected
> ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). The body's citations (`file:line`) are at
> `8d095d07`; its path shorthands (`core/`, `coreT/`, `ptc.md`, `shell.md`) are defined in the preamble. The review
> transcripts and the run's `TASK.md` are not in the repository. Its open questions that outlive this change are
> TC-21 – TC-28 in [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md).
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

# PART B — PR 2: reach, CLI assembly and real-model E2E (items 7–9)

Start from `origin/main` after PR 1 has merged. Part A's `CodeSpanAttributes`, `ToolSpans` and `TRACER` key exist by then.

## B.1 `ExecuteCode` in forks and slash skills (item 7)

**What the doc says.** ptc.md §21 (C-deviation, `:714`): "서브에이전트·스킬 정의에는 이 키가 없고, 부모의 설정을 물려줄지는 본문이 정하지 않았다". The doc is silent. Per TASK, choose the narrowest rule consistent with "agents can only narrow", and record it as a deviation.

### B.1.1 Chosen rule: own opt-in, narrowed by the caller's ceiling

A fork or slash skill offers `ExecuteCode` only if **both** of these hold:

1. **Its own definition opts in.**
   - A subagent's frontmatter or builder declares `programmatic-tools`.
   - A slash skill's frontmatter declares it.
   - For a skill fork (`execution: {mode: fork, agent: X}`), the source is subagent `X`'s definition, further narrowed by the skill's own keys when the skill declares any.
2. **The caller's effective settings allow it.**
   - The effective result is `own.narrowedBy(caller)`.
   - The caller is the main agent, or the parent fork for nested forks.
   - A caller without PTC is a ceiling of "nothing", so a fork under a non-PTC agent never gets `ExecuteCode`. This is fail-closed, unlike `CALLER_ALLOWED_TOOLS`, where absent means unrestricted.

`ProgrammaticToolSettings.narrowedBy(ProgrammaticToolSettings ceiling)` is a new method in `core/agent/tool/ProgrammaticToolSettings.java`:

| Field | Rule |
|---|---|
| `tools` | `own.tools ∩ ceiling.tools`, in own order. Empty → `none()` |
| `codeOnly` | `(own.codeOnly ∪ ceiling.codeOnly) ∩ tools`. A tool the caller withheld from direct use stays withheld. The narrower direct surface wins. Why a caller's withholding binds the fork even though `CALLER_ALLOWED_TOOLS` already bounds the fork's direct tools: `programmatic-only` is the caller's statement that the tool may be **called** only through code (decision 12 ④ — typically to keep bulk results out of the context). The allow-list cannot express "callable, but not directly", so without the union a fork could take a code-only tool back to direct use. That would widen exactly what the caller narrowed |
| `engine` | `auto` defers to the other side. Equal → that value. Conflicting explicit choices (`javascript` vs `python`) → `none()`, with a WARN once per agent (`WarnOnce` key `name\0engine-conflict`) |
| `shellEngineToolScope` | The narrower of the two present (`ShellEngineToolScope.narrower`). The application ceiling still applies afterwards in `ExecuteCodePlan.effectiveScope` |

**Rejected alternatives.**
- **Inherit the parent's settings verbatim.** Every subagent and skill would gain a tool its author never opted into. That widens the fork's surface without its definition saying so, against "agents can only narrow".
- **Own definition only, no caller ceiling.** A subagent could run code under a main agent whose operator never enabled PTC. The fork's direct tools are already caller-narrowed, but this is not the *narrowest* rule, and the TASK asks for the narrowest.
- **Inherit only when the definition says `programmatic-tools: inherit`.** That is a new sentinel syntax the doc never mentions. It can be added later on top of this rule.

### B.1.2 Data shapes

- **Shared frontmatter parsing.** Extract `MarkdownAgentDefinitionParser.extractProgrammatic` (`:509-545`) into a reusable package-private-or-public helper, `core/agent/tool/ProgrammaticFrontmatter.parse(Map<String,Object>, String sourceName)`. It keeps the same validation: camelCase rejected, subset rule, the "need 'programmatic-tools'" error, and `ProgrammaticEngineChoice.parse` / `ShellEngineToolScope.parse`. Three parsers call it: the agent parser, `SubagentContentParser` (`:189-255`) and `MarkdownSkillParser` (`:115-138`).
- **`SubagentMetadata`** gains `ProgrammaticToolSettings programmatic`, default `none()`.
  - `Subagent.Builder.programmatic(...)`.
  - `SubagentToolScope.withAllowedTools` (`:120-131`) must copy it, because it rebuilds the metadata field by field.
- **`SkillMetadata`** gains `ProgrammaticToolSettings programmatic`, default `none()`, plus its builder method.
- **New context key `ToolContextKeys.CALLER_PROGRAMMATIC`** (`ProgrammaticToolSettings`, not write-once). It is republished per run exactly like `CALLER_ALLOWED_TOOLS`. The accessor is `CallerProgrammatic.of(ctx)`, which returns `none()` when absent.
- **Publishers** (beside each `CALLER_ALLOWED_TOOLS` publish):
  - `SingleToolInvoker.java:189-191`, from `spec.getProgrammatic()`;
  - the ungated dispatcher `ToolCallDispatchers.java:79-81`, from its own `programmatic` field;
  - the user-slash command context `OrcaAgentExecutor.java:2400`, from `agentRuntime.getAgent().getMetadata().getProgrammatic()`.
  - The published value is **the settings of the loop whose gates ran the call**: the main agent's settings in the main loop, the fork's effective settings in a fork, and the skill's effective settings in a slash-skill loop. The first two publishers take it from the dispatcher that ran the call, which is where the value lives (B.1.3a). They overwrite the key on every call (`putAll` then `put`), so a caller cannot pre-seed it.
- **`SubagentLaunchContext`** gains `callerProgrammatic`. It is set at the four spawn sites listed by the explorer:
  - `TaskTool.java:661-672`;
  - `WorkflowTool.java:524-531`;
  - `SubagentBackedSkillForkExecutor.java:160-165`;
  - `OrcaAgentRuntimeFactory.java:1180-1183`, the workflow base, which uses `agent.getMetadata().getProgrammatic()` directly, as it does for allowed tools.
- **`DefaultSubagentExecutionManager.applyCallerCeiling`** (`:1267-1279`, called at `:1010`) also computes `effective = resolved.programmatic().narrowedBy(launch.callerProgrammatic())` and stores it on the scoped subagent.
  - The programmatic narrowing must run **before** the early `return subagent` taken when the allow-list ceiling is empty (`:1269-1271`). An empty allow-list ceiling means "unrestricted", but an absent programmatic ceiling means `none()`. Leaving the early return in front would let a caller with no allow-list hand its fork the fork's own PTC unnarrowed.
  - The scoped subagent is the one `forkDispatcher` reads (B.1.3a).
- **Skill fork.** `SubagentBackedSkillForkExecutor.java:109-138` first narrows the target subagent's settings by the skill's settings when the skill declares any (`skill.isEnabled()`); the manager then applies the caller ceiling.

### B.1.3 Offer contexts

- **Fork.** `DefaultSubagentExecutor.offerContextOf(lc)` (`:1486-1490`) becomes:
  ```java
  CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder().registry(lc.sessionRegistry)
          .admission(SubagentToolScope.admissionFilter(lc.subagent()))
          .ceiling(...).programmatic(lc.subagent().getMetadata().getProgrammatic())   // effective
          .agentName(lc.subagent().getName()),
      lc.toolContext, lc.subagent().getMetadata().getAllowedTools()).build();
  ```
  It is called at `:687` and in the grace iteration at `:905`. The fork's effective allowed tools feed the `Bash`-unconstrained fact (the D1 deviation row "`Bash` 조건의 허용 목록").
- **Slash skill.** `LlmSkillExecutor.execute` computes the skill's effective settings **once**, right after the allow-list intersection (`:226-232`):
  ```java
  final ProgrammaticToolSettings programmatic = skill.getMetadata().getProgrammatic()
          .narrowedBy(CallerProgrammatic.of(context.getToolContext()));
  ```
  It hands this one value to both consumers, so what the skill is offered and what bounds the forks it spawns cannot drift apart:
  - the narrowing at `:247-248` becomes `boundDispatcher.get().narrow(context.getToolRegistry(), allowedTools, programmatic)` (B.1.3a);
  - `offerContextOf(allowedTools, programmatic, context)` (`:530-537`) adds:
    - `.programmatic(programmatic)`;
    - `.agentName("skill:" + skill.getName())`;
    - `putEnvironmentFacts(..., context.getToolContext(), allowedTools)` with the intersected allow-list from `:226-232`.
  - The offer is still built once per execution (piece-B deviation row 5).
  - Fork-mode skills return at `:221-224` before this code runs. They go through `SubagentBackedSkillForkExecutor` (B.1.2).
- **Every loop publishes its own settings to the calls it runs.** `CALLER_PROGRAMMATIC` in a call's context must be the settings of the loop that made the call:
  - in a fork, the fork's effective settings, so a grandchild fork is narrowed by its parent fork;
  - in a slash skill, the skill's effective settings, so a fork spawned from inside a skill is ceilinged by the skill and not by the main agent.
  This does not happen on its own. `SingleToolInvoker` publishes from `ToolInvocationSpec` (`:189-191`), and the spec is built in exactly one place, `DefaultToolCallDispatcher.dispatch` (`:79-83`), from the dispatcher's own builder fields. The slash-skill loop builds neither a spec nor a dispatcher. It narrows the caller's bound dispatcher (`LlmSkillExecutor.java:242-248`) and dispatches through the narrowed one (`:549-565`). Today `narrow` can carry only a registry and an allow-list (`ToolCallDispatcher.java:59`). So the value lives **on the dispatcher**, and narrowing carries it. That is B.1.3a. It is not a per-spec-site value: there are no other spec sites.

### B.1.3a `ToolCallDispatcher` carries the caller's programmatic settings (breaking SPI change)

This is review-3's option (a). TASK and the launcher's decision allow breaking API compatibility (NEXT-RESUME item 1).

**Why the SPI must change** (review-3 Blocking):
- Without it, every call a slash skill makes runs through the main agent's dispatcher, narrowed only by registry and allow-list, and publishes the **main agent's** settings.
- Scenario:
  1. The main agent enables PTC for `[Read, Grep]`.
  2. A slash skill declares no `programmatic-tools`, so its effective setting is `none()`.
  3. The skill calls `Task`.
  4. The subagent declares `programmatic-tools: [Read, Grep]`.
  5. The fork computes own ∩ main, which is non-empty, so it would be offered `ExecuteCode`.
- That breaks B.1.1's rule that a caller without PTC is a ceiling of "nothing".
- Options (b), where `SingleToolInvoker` narrows by a skill-published context value, and (c), where the skill's caller is the ceiling, were rejected:
  - (b) adds a second, context-borne source for a value the dispatcher already owns. Any tool could also publish it.
  - (c) is weaker than the narrowest rule.

**New SPI** (`core/agent/tool/ToolCallDispatcher.java`, replacing `:43-59`):
```java
/**
 * Returns a dispatcher that can only do less than this one: ... (registry and allow-list rules unchanged) ...
 * Its programmatic settings, published to every call as ToolContextKeys.CALLER_PROGRAMMATIC and so the ceiling of
 * any fork a call spawns, become programmatic.narrowedBy(this dispatcher's settings). An empty result is none(),
 * not an error: unlike an empty allow-list, none() reads as "nothing", never as "unrestricted".
 */
ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
        ProgrammaticToolSettings programmatic);
```
- The two-argument `narrow` is **removed**. It is not deprecated and not kept as a `default` method:
  - a default that kept this dispatcher's settings is exactly the leak above;
  - a default that passed `none()` would silently change what an existing caller's forks can do.
  - Removing it forces every implementation and caller to choose, at compile time.
- The class javadoc lists what the binding fixes (`:12-17`: "the registries, the allow-list, the hooks, …"). It gains "the programmatic settings published to each call".
- `ProgrammaticToolSettings` is in the same package (`at.aimon.core.agent.tool`). No new dependency direction, and nothing in `ProgrammaticToolArchitectureTest` changes.

**`DefaultToolCallDispatcher`** (`core/toolinvocation/DefaultToolCallDispatcher.java`):
- New field `private final ProgrammaticToolSettings programmatic`, required in the constructor (`:51-62`, `Objects.requireNonNull(builder.programmatic, "programmatic cannot be null")`). The class javadoc says every field is required (`:35`). That stays true: there is no default, so a builder site that forgets it fails loudly rather than publishing `none()` by accident.
- `Builder.programmatic(ProgrammaticToolSettings)`. Its javadoc: "the settings of the agent, fork or skill whose loop this dispatcher gates; published to every call as `CALLER_PROGRAMMATIC`, the ceiling of any fork the call spawns".
- `dispatch` (`:79-83`) adds `.programmatic(programmatic)` to the spec chain. This is the single spec construction site.
- `toBuilder()` (`:110-114`) copies it, so it survives `narrow`.
- `narrow` (`:100-108`) becomes three-argument and sets `.programmatic(programmatic.narrowedBy(this.programmatic))` next to the bounded allow-list.
- The two production build sites set it:
  - `OrcaAgentExecutor.gatedDispatcher` (`:2433-2441`) sets `.programmatic(agent.getMetadata().getProgrammatic())`. It serves both the turn (`:1704`) and the user-slash command context (`:2405`). On the slash path this is the same value the command context publishes by hand at `:2400`. So an inline skill sees the same caller ceiling whether the user typed `/skill` or the model called `Skill`.
  - `DefaultSubagentExecutor.forkDispatcher` (`:604-613`) sets `.programmatic(subagent.getMetadata().getProgrammatic())`. `subagent` is the scoped subagent from `applyCallerCeiling`, so this is the fork's effective settings (B.1.2), the same subagent whose `getAllowedTools()` the dispatcher already reads at `:612`.

**`ToolInvocationSpec`** (`core/toolinvocation/ToolInvocationSpec.java`):
- Gains `programmatic`, which defaults to `none()`.
- The spec is internal to `toolinvocation` and built once in main code. The default keeps `SingleToolInvokerTest`'s three direct builders (`:127`, `:150`, `:305`) compiling, and it is fail-closed.
- `SingleToolInvoker` (`:189-191`) adds `.put(ToolContextKeys.CALLER_PROGRAMMATIC, spec.getProgrammatic())` after the `putAll`, beside `CALLER_ALLOWED_TOOLS`.

**`ToolCallDispatchers`** (`core/toolinvocation/ToolCallDispatchers.java`):
- New public `ungated(ToolExecutionManager, ToolRegistry, ProgrammaticToolSettings)`.
- The existing `ungated(executionManager, registry)` (`:51-54`) stays. It delegates with `ProgrammaticToolSettings.none()`, which is fail-closed: an embedder with no gates gets no code-running forks unless it says so. Its javadoc says this.
- `Ungated` gains the field. `dispatch` adds `.put(CALLER_PROGRAMMATIC, programmatic)` at `:79-81`, and `narrow` (`:93-99`) becomes three-argument with `programmatic.narrowedBy(this.programmatic)`.

**`LlmSkillExecutor`**: as in B.1.3, `narrow(registry, allowedTools, programmatic)` with the skill's effective settings.
- The dispatcher intersects that value once more with its own settings. In production the two sources agree:
  - `CALLER_PROGRAMMATIC` was published by that same dispatcher (tool-call path);
  - or it was published by `:2400` from the same agent metadata as `gatedDispatcher` (slash path).
- `narrowedBy` is idempotent: `x.narrowedBy(c).narrowedBy(c) = x.narrowedBy(c)`. Each row of the B.1.1 table shows it: ∩, (union) ∩ tools, the engine table, and the narrower scope. So the second intersection is a no-op except when an embedder binds a dispatcher and publishes a key that disagree. Then the narrower one wins.
- The skill loop's `dispatch` copies the caller's context, including the caller's `CALLER_PROGRAMMATIC` (`:551-557`). That is harmless and needs no change, because the gates overwrite the key per call.
- A program that `ExecuteCode` runs inside the skill dispatches its nested calls through the narrowed dispatcher bound at `:558`. Those calls also publish the skill's settings.

**Test doubles and callers that change** (all in `aimon-core` tests; no `ToolCallDispatcher` implementation exists outside `aimon-core`, per grep over `modules/`):
- Implementations that take the new parameter:
  - `DefaultToolCallBridgeTest` `:37`, `:108`;
  - `LlmSkillExecutorTruncationTest` `:340`;
  - `OrcaAgentExecutorDispatchTest` `:39`;
  - `DefaultSubagentExecutorDispatchTest` `:41`;
  - `LlmSkillExecutorTest.RecordingToolCallDispatcher` `:857`, which also records it in a new `narrowedProgrammatic` field next to `narrowedAllowedTools` (`:845`), so the skill tests can assert what the skill passed.
- `narrow` callers that pass `ProgrammaticToolSettings.none()` (they test the registry and allow-list rules):
  - `DefaultToolCallDispatcherTest` `:146`, `:192`, `:195`, `:206`, `:216`, `:229`, `:244`, `:250`;
  - `ToolCallDispatchersTest` `:94`, `:97`.
- Builder sites that add `.programmatic(ProgrammaticToolSettings.none())`:
  - `DefaultToolCallDispatcherTest` `:104`, `:259`;
  - `ExecuteCodeEndToEndTest` `:112`;
  - `ExecuteCodeShellEndToEndTest` `:106`;
  - `ExecuteCodeUnavailableTest` `:118`.
  These tests bind settings through the offer context, not through `CALLER_PROGRAMMATIC`. The new fork variant in `ExecuteCodeEndToEndTest` (B.6) builds its main dispatcher with the main agent's settings.

**Docs.** `docs/design/tool/tool-call-dispatcher.md` has no `.en.md` twin (`ls docs/design/tool/tool-call-dispatcher*` lists only the `.md`), so there is no translation to update:
- §3, decision row 5 "좁히기" (`:54`): `narrow` also takes settings and intersects them with `narrowedBy`. An empty result is `none()`, not an error. Why: a fork spawned from a skill is bounded by the skill. The rejected alternative: keeping the caller's settings would let a skill without PTC give its forks the caller's PTC.
- §4 (`:63-70`): the interface block gets the new signature. The slash-skill row (`:77`) says the skill loop narrows with `스킬 자신의 programmatic 설정 ∩ 호출자`.
- §6 "호환을 깨는 변경" (`:126-133`): a new row. `narrow(registry, allowedTools)` → `narrow(registry, allowedTools, programmatic)`; `DefaultToolCallDispatcher.Builder.programmatic` is required; there is a new three-argument `ToolCallDispatchers.ungated`, and the two-argument one publishes `none()`.
- §7 (`:144`): a new guarantee row. "`narrow` cannot widen the programmatic settings, and every dispatch publishes `CALLER_PROGRAMMATIC` from the dispatcher" → `DefaultToolCallDispatcherTest`, `ToolCallDispatchersTest`, `SlashSkillProgrammaticCeilingE2EIntegrationTest`.

**CHANGELOG** (`[Unreleased]`, inside PR 2's entry, as a bullet in the style of `CHANGELOG.md:153` and `:184`):
> - **Breaking:** `ToolCallDispatcher.narrow(ToolRegistry, List<AllowedTool>)` is now `narrow(ToolRegistry, List<AllowedTool>, ProgrammaticToolSettings)`. The narrowed dispatcher publishes `programmatic.narrowedBy(<its own settings>)` to every call as `ToolContextKeys.CALLER_PROGRAMMATIC`, the ceiling of any fork the call spawns. A subagent started from inside a slash skill is therefore bounded by the skill's programmatic settings, not by the agent's. Implementations add the parameter and must not return settings wider than their own. Callers that do not mean to pass programmatic settings pass `ProgrammaticToolSettings.none()`. `DefaultToolCallDispatcher.Builder.programmatic(...)` is new and required (`build()` throws without it). `ToolCallDispatchers.ungated(manager, registry, programmatic)` is new; the two-argument `ungated` publishes `none()`.

### B.1.4 Interaction with quota and spans

- The fork quota (`ToolCallQuota.admit(1)` in the bridge) is already implemented and end-to-end tested. Forks now reach it for real.
- The nested spans from A.5.2 work in forks, because PR 1 binds `TRACER` in the fork context.

### B.1.5 Fork iteration attribute

`DefaultSubagentExecutor`'s `IterationTrace` calls `CodeSpanAttributes.annotateIteration(trace.span, offerContext)` after `availableToolDefinitions`. The slash-skill loop still has no span (A-DV2).

### B.1.6 Docs

- **ptc.md §7.2.** Add a paragraph: subagent and skill frontmatter take the same four keys; the effective setting is own ∩ caller; the table of narrowing rules from B.1.1.
- **ptc.md §16.** Add a "서브에이전트 / 스킬 frontmatter" column (or extend the agent column's text) with "자기 선언 ∩ 호출자".
- **ptc.md §21.** The C-deviation row "포크·슬래시 스킬은 제안하지 않는다" and the D1 row "`Bash` 조건의 허용 목록" become implemented, with **B-DV1**: the doc was silent, and this is the chosen rule and its reason. B-DV1 states that "caller" includes a slash skill: a fork spawned from inside a skill is bounded by the skill's effective settings, carried by `ToolCallDispatcher.narrow` (B.1.3a).
- **`docs/design/tool/tool-call-dispatcher.md`**: the SPI change, as listed in B.1.3a. There is no translation twin.
- **Other docs.**
  - `docs/features/subagent/subagent-development-guide.md`: Builder table `:142-150` and "마크다운 패리티" `:155`.
  - `docs/references/aimon-skill-extensions.md`: the new skill keys.
  - The `.en.md` twin of each.

## B.2 CLI assembles the shell engine (item 8)

**Facts.**
- The CLI passes `ToolSpec.codeExecutionEngines(List.of(new GraalJsCodeExecutionEngine(holder)))` (`AgentSetupFactory.java:829-838`). Engines passed by the caller suppress the stack's ServiceLoader discovery, so the CLI must add the shell engine itself.
- The CLI's environment is always `LocalExecutionEnvironmentProvider` → isolation `HOST`. That makes the Python engine statically unavailable (`ShellCodeExecutionEngine.java:155-158`) until a sandbox environment exists. Assembling it is still what the stack and starter do. It turns "no python engine is assembled" into the accurate "the python engine cannot run here: …" for `programmatic-engine: python` agents, and it makes a future sandbox environment work without another assembly change.

**Changes.**
- **`modules/aimon-cli/.../config/ProgrammaticConfig.java`**:
  - `enablePython` (boolean, default `false`);
  - `shellEngineToolScope` (String, default `"environment"`), parsed with `ShellEngineToolScope.parse` and failing at config load with the key name on a bad value;
  - both added to `equals`, `hashCode`, `toString`, and to `CliConfig.equals` if it delegates.
- **`AgentSetupFactory.buildToolSpec`**:
  - builds the engine list: JS when `enableJs`, plus `ShellCodeExecutionEngine.create()` when `enablePython`;
  - `.shellEngineToolScope(config.getProgrammaticConfig().toShellEngineToolScope())`;
  - when the list is empty, keeps today's behaviour (no `codeExecutionEngines`, which leaves it to the stack's off state);
  - updates the comment at `:829-831` to say why Python is inert on the local host.
  - The shell engine is CLI-owned and must be closed on shutdown: register it with the same CLI teardown that closes the `GraalJsEngineHolder`, which the builder locates (`AgentSetupFactory` / CLI shutdown hook). It owns reply-writer threads (`ShellCodeExecutionEngine.java:79,130`).
- **`default-config.yaml:247-255`.** Add `enablePython: false` and a commented `shellEngineToolScope: environment` with one line of explanation.
- **Docs.**
  - ptc.md §16: the CLI column gets `programmatic.enablePython`.
  - ptc.md §21, D1 row "CLI 는 셸 엔진을 조립하지 않고": now assembled behind `enablePython`, with **B-DV2**: an opt-in flag rather than always-on like the stack, matching the CLI's `enableJs` opt-in convention and avoiding idle reply-writer threads in every CLI run.
  - The CLI config reference doc, if one lists `programmatic.*` (builder greps `enableJs` under `docs/`).

**Rejected alternative.** Always assemble the shell engine whenever any programmatic flag is on. It creates idle threads for an engine that cannot run locally. The stack does it because it discovers engines by classpath; the CLI already uses explicit flags.

**Tests.** In `modules/aimon-cli/src/test/.../AgentSetupFactoryGraalJsTest.java`, or a new `AgentSetupFactoryShellEngineTest`:
- `enablePython` → the tool spec has a python engine, and the stack registers `ExecuteCode` once;
- `enablePython` + `enableJs` → two engines, a single JS holder;
- default → no python engine, and the existing `programmaticEnableJsRegistersExecuteCode` assertion ("exactly one engine") still holds with defaults;
- `shellEngineToolScope: host` reaches the provider;
- a bad scope value fails config load naming the key;
- the shell engine is closed on teardown.

## B.3 Real-model E2E harness (item 9)

### B.3.1 Where it lives and how it is gated

**Facts.**
- Only `aimon-cli` has core, bootstrap, graaljs and both LLM clients on its test classpath (`modules/aimon-cli/build.gradle.kts:12-21`). `aimon-core` cannot see the LLM clients.
- `@Tag("llm")` would fail `ReleaseGateMatchesCiGateTest.everyTestTagIsGated` (`coreT/architecture/ReleaseGateMatchesCiGateTest.java:171,392`). That test requires every tag to map to a gate task that CI runs, and running in CI is exactly what we must avoid.
- The repo's live-test precedent is untagged and uses `@EnabledIfEnvironmentVariable(named = "OPENAI_KEY" | "ANTHROPIC_KEY")`. Examples are `OpenAIContextEngineLiveTest` and `AnthropicContextEngineLiveTest`, described in CONTRIBUTING.md `:64-90`.

**Chosen approach.** The tests have no tag and a double gate:

1. **A system property set only by a dedicated task.** The test classes carry `@EnabledIfSystemProperty(named = "aimon.llmTest", matches = "true")`. `modules/aimon-cli/build.gradle.kts` registers:
   ```kotlin
   val llmTest by tasks.registering(Test::class) {
       description = "Runs ExecuteCode against a real model (needs OPENAI_KEY and/or ANTHROPIC_KEY). Not part of check/checkAll/CI."
       group = "verification"
       testClassesDirs = sourceSets.test.get().output.classesDirs
       classpath = sourceSets.test.get().runtimeClasspath
       useJUnitPlatform()
       filter { includeTestsMatching("*LiveE2ETest") }
       systemProperty("aimon.llmTest", "true")
       outputs.upToDateWhen { false }        // always re-run (billed, nondeterministic)
       testLogging { showStandardStreams = true; events("passed", "skipped", "failed") }
   }
   ```
   It is not wired into `check`, `checkAll` or CI. The normal `test` task also sees the classes but skips them, because the property is absent, even when a key is set locally. That keeps billed calls out of `test`, `checkAll` and the release run. The skip is cheap.
2. **A per-provider key assumption inside the test**, resolved by a test-only helper `LiveKeys` (`modules/aimon-cli/src/test/java/at/aimon/cli/e2e/LiveKeys.java`).
   - **OpenAI** (the provider the user's key is for; TASK addendum):
     - `OPENAI_KEY` (env) wins when it is non-blank;
     - otherwise `OPENAI_KEY_FILE` (env) names a file whose contents, trimmed of surrounding whitespace and newline, are the key. A leading `~/` in the value is expanded with `System.getProperty("user.home")`, in case the shell did not expand it (for example when it is quoted). **No path is hard-coded in committed code.** The user's own path (`~/.openai-key`) appears only in run summaries and the PR body, never in the repo.
     - The file is read in place. It is never copied, and the test never writes it anywhere.
     - If neither variable is set, OpenAI is skipped. If `OPENAI_KEY_FILE` is set but the file is missing, unreadable or empty, the test **fails** (it does not skip). The message names the variable and the path but never any content. Silently skipping on a typo would let a "green" run hide that nothing ran.
   - **Anthropic** (optional, nobody has supplied a key): `ANTHROPIC_KEY`, falling back to `ANTHROPIC_API_KEY` (OQ-B1). There is no key-file variant; it is skipped when neither is set.
   - Done with `Assumptions.assumeTrue`, so a missing key skips that provider and the other still runs.
   - `LiveKeys` is called only from test methods of classes gated by the class-level `@EnabledIfSystemProperty`. JUnit evaluates that condition before any instance, `@BeforeAll` or field initialiser runs, so under the plain `test` task `LiveKeys` is never reached. A developer with a stale `OPENAI_KEY_FILE` exported therefore does not break `test` or `checkAll`. `ExecuteCodeLiveFixtureTest` (offline) never touches `LiveKeys`.
3. **The key is never printed, logged, committed or pasted** (TASK addendum):
   - `LiveKeys` returns the key as a `String` that goes straight into the client builder (`OpenAIConfig.builder().apiKey(...)`). It is not stored in a field with a `toString`, not put into the assertion messages, and not passed to `@DisplayName` or parameter names.
   - Failure diagnostics dump only the conversation, render payloads and span tree. None of them contains the key: the key travels in the HTTP client header, not in messages or span inputs. The builder confirms that `OpenAIConfig` / `AnthropicConfig` `toString()` does not include the key; if one does, the test never logs a config object.
   - `testLogging.showStandardStreams = true` is safe under these rules. The builder greps the test output of one real run for the key's first 8 characters (in memory, without printing them) and records in `build/summary.md` that nothing was found.
   - The Gradle test JVM gets `OPENAI_KEY_FILE` / `OPENAI_KEY` from the build environment (the daemon takes the client's environment per build). No `systemProperty` or `environment(...)` line in `build.gradle.kts` copies the key.

**Command:** `OPENAI_KEY_FILE=<path to key file> ./gradlew :aimon-cli:llmTest`, or `OPENAI_KEY=… ./gradlew :aimon-cli:llmTest`. For this run, the user's file is `~/.openai-key`, so: `OPENAI_KEY_FILE=~/.openai-key ./gradlew :aimon-cli:llmTest`. The TASK suggested `:aimon-core:llmTest`, but core cannot host it (the addendum allows "or equivalent"); recorded as **B-DV3**.

**Rejected alternatives.**
- **`@Tag("llm")` excluded from `test`.** It breaks `ReleaseGateMatchesCiGateTest`. Allow-listing the tag there would weaken a guard whose purpose is "no test tier silently outside CI". Here the opt-in tier is deliberate, and it is documented in CONTRIBUTING beside the existing live tier.
- **A new module `aimon-e2e`.** BOM, publishing opt-out, `checkTestClasspathVersions` and ReleaseGate module lists all churn for one test class.
- **Key-only gating, as in the precedent.** The test would run inside `test` and `checkAll` whenever a developer has a key exported, which is billed and nondeterministic inside the quality gate. The release script already refuses to run while the keys are set (`PROVIDER_KEY_VARIABLES`), but local `checkAll` does not.

The builder also checks that `ReleaseGateMatchesCiGateTest.refusedKeysAreTheProviderModulesKeyGates` (which scans `modules/aimon-llm-*`) is unaffected by an `aimon-cli` test that reads the same variables. If that test scans more widely than the explorer saw, it records the result.

### B.3.2 What the test runs

There is one class per engine, each parameterized over provider: `modules/aimon-cli/src/test/java/at/aimon/cli/e2e/ExecuteCodeJsLiveE2ETest` and `ExecuteCodeShellLiveE2ETest`. Shared fixtures live in `ExecuteCodeLiveFixtures`.

- **Model client.**
  - OpenAI: `new OpenAILlmClient(OpenAIConfig.builder().apiKey(LiveKeys.openAi()).model(M).maxTokens(4000).build())`.
  - Anthropic: `new AnthropicLlmClient(AnthropicConfig.builder()...)`, `AutoCloseable`.
  - Default models follow the live-test precedent (`gpt-4o-mini`, `claude-haiku-4-5-20251001`). They can be overridden with the env vars `AIMON_LLM_TEST_OPENAI_MODEL` and `AIMON_LLM_TEST_ANTHROPIC_MODEL`, so the launcher can pick a stronger model if a weak one will not write code (OQ-B2).
- **Stack.** The same shape as `AgentSetupFactoryGraalJsTest.programmaticEnableJsThroughTheStack` (`:124-157`): `AimonStackBuilder.build(AimonStackSpec.builder().llm(LlmSpec.of(client)).fileSystem(...).agent(...).tools(toolSpec).build())`, then `stack.runtime(stack.primaryRuntimeId())` and one turn.
- **Tools.** Two in-test tools, `GetHostStatus({host})` and `GetHostLoad({host})`, provided through `ToolSpec.addProvider`.
  - Both declare `READ_ONLY`, `dataReach=HOST` and `isCodeCallable()=true`, and are deterministic: a fixed table of 5 hosts.
  - Each result is padded with a 3 000-character `details` field containing a unique marker `RAW-<uuid>`.
  - In the shell variant, `GetHostStatus` also feeds the fixture's `RunDirectoryRecorder` on every invocation (B.3.3, stray-process check). This has no effect on the tool's result.
- **Agent.** `DefaultAgent` with `programmatic-tools: [GetHostStatus, GetHostLoad]` and `programmatic-only: [GetHostStatus, GetHostLoad]`, and an explicit `allowed-tools`, with every name bare (no argument pattern). JS variant: `[ExecuteCode, GetHostStatus, GetHostLoad]`. Shell variant: `[ExecuteCode, GetHostStatus, GetHostLoad, Bash]`. The two tools are then not offered directly, so the only way to the answer is `ExecuteCode`. The system prompt is short. The user prompt asks: "For each of the hosts h1..h5 get status and load, and print only the names of hosts that are down or have load above 0.8, one per line."
- **JS variant.** `ToolSpec.codeExecutionEngines(List.of(new GraalJsCodeExecutionEngine(holder)))` with `holder = GraalJsEngineHolder.create()`, local environment (HOST → `auto` picks JavaScript). The caller owns engines it passes in (`AimonStackBuilder.java:517`), so the test closes `holder` in `@AfterAll`, after the stack.
- **Shell variant.**
  - Feasibility: `LocalShell`, `LocalRuntimeFileSystem` and `LocalExecutionEnvironmentProvider` are public classes in `core/*/impl`. Test code in `aimon-cli` may use them, since the ArchUnit `impl` rule is a core-side rule over main code; the builder confirms this.
  - The test adds `SandboxedLocalTestEnvironmentProvider`, an `aimon-cli` test-only equivalent of core's package-private `coreT/tools/code/shell/SandboxedLocalEnvironment`. It wraps `LocalShell(root)` and declares `isolation(SANDBOXED)`, `runtime(RUNTIME_PYTHON3)`, `shellName("bash")` and `runtimeFileSystem(new LocalRuntimeFileSystem(root))`. It is passed through `ExecutionEnvironmentSpec.provider(...)`.
  - `staticAvailability` also needs:
    - the shell features `STREAMING`, `RUN_DIRECTORY` and `ISOLATED_EXECUTION`, which `LocalShell` declares;
    - **unconstrained `Bash`.** `ShellCodeExecutionEngine.staticAvailability` (`:175-179`) requires `Bash` to be offered **and named in `allowed-tools` without an argument pattern**. An empty or absent allow-list means "unrestricted", which is not unconstrained (`CodeOfferKeys.allowListLeavesBashUnconstrained`, `:150-153`). Hence the explicit allow-list with bare `Bash` above (review-1). The builder confirms two things:
      - the stack registers `Bash` with the default `ToolSpec`; if it does not, the test enables it;
      - no default stack hook is a `PermissionRequest` or `PreTool` hook that `mayApplyTo("Bash")`. The CLI's `ToolCallDisplayHook` would block it, but the test assembles an `AimonStack`, not the CLI factory, so it is not present.
      - Consequence: the model can also call `Bash` directly on the developer's host. That is acceptable for an opt-in local test and is stated in the doc section. It does not affect assertion 2(a), which concerns the two host tools.
    - **a tool scope that admits the tools.** The two test tools truthfully declare `dataReach=HOST`, since they model a host-side data store. The stack's default `shell-engine-tool-scope` is `ENVIRONMENT`, and `ExecuteCodePlan` drops tools outside the scope for a tool-scope-bound engine (`ExecuteCodePlan.java:140`). Left alone, the callable set would be empty and `ExecuteCode` not offered (review-1). So the shell variant sets **`ToolSpec.shellEngineToolScope(ShellEngineToolScope.HOST)`**. Declaring the tools `ENVIRONMENT` instead was rejected: it would misstate what they model, and would not exercise the scope setting.
  - The engine is `ShellCodeExecutionEngine.create()`, closed in `@AfterAll`.
  - `assumeTrue(Python3.available())`. If `Python3` is test-only in core, the test re-implements the two-line check (`python3 -c 'import sys; sys.exit(sys.version_info < (3,8))'`).
  - This runs the model's Python on the developer's host. It is acceptable for an opt-in local test, and is stated in the doc section.
- **Offline fixture check** (runs in plain `test`: no key, no cost, not gated by `aimon.llmTest`). `ExecuteCodeLiveFixtureTest` builds **the same fixture** for both variants — same `ExecuteCodeLiveFixtures` factory, tools, allow-lists, scope and environment provider — and swaps the real client for a scripted `LlmClient`.
  - **Offer shape.** It captures the tool definitions of the first LLM request and asserts that:
    - `ExecuteCode` is offered, and its description declares both `GetHostStatus` and `GetHostLoad` as callable functions;
    - neither tool is offered directly (`programmatic-only` is in effect, which proves `ExecuteCode` was offerable);
    - for the shell variant, the iteration span has no `ptc.unavailable_reason`.
  - **One scripted run.** The scripted LLM returns an `ExecuteCode` call with a fixed program in the variant's language that calls both tools and prints one line. The test asserts B.3.3 assertions 1 and 2 against it. The shell part uses `assumeTrue(python3 ≥ 3.8)`.
  - The real-model test must not run on a fixture this check has not validated. Both share the factory, so a fixture regression fails `test` first.
- **Observation capture.** A `PostToolHook` registered in the test records each `ToolUseResult` for `ExecuteCode` (content plus `renderPayload`). The test also keeps the final conversation (`runtime`/session messages) and the TOOL spans from a `DefaultTracer(new InMemoryTraceSpanStore(), …)`, which exercises Part A.

### B.3.3 Assertions

These are mechanical. They do not judge answer quality, except the last.

1. At least one `ExecuteCode` call has `renderPayload.calls` with **at least 2 dispatched nested calls** (not refused), and those calls cover both tools. Cross-check: at least 2 TOOL spans with `tool.path=code` parented to an ExecuteCode span.
2. **Only stdout came back:**
   - (a) the conversation contains no direct `tool_use` of `GetHostStatus` or `GetHostLoad`, and no `tool_result` whose id has the form `<id>#n`;
   - (b) for every `ExecuteCode` result, `ExecuteCodeLanguageNotice.languageOf(content)` returns the engine's language. The summary is the last line of the tool's own text once any trailing hook-feedback block is removed (A-DV3, A.6);
   - (c) `RAW-<uuid>` markers appear in the content only as far as the program printed them. Concretely, the `ExecuteCode` content minus its summary, `[environment]`, `[hook feedback]` and `[logs]` sections equals the stdout recorded in the render payload or outcome. The builder exposes stdout in `renderPayload` if it is not already there (`ExecuteCodeTool.renderPayload` `:331-336`; a small additive key `stdout_chars` or similar suffices for the comparison) — OQ-B3.
3. Soft check, logged and not asserted: the final answer names the expected hosts (fixed table → known answer). It is logged so the launcher can judge model quality without making the test flaky.

**Diagnostics.** On failure, the test prints the whole conversation, the render payloads and the span tree. None of them carries the key (B.3.1 item 3).

**Result line.** Every run, pass or fail, prints one line per provider and engine, so agents can record the outcome without reading logs by hand:

```
LIVE-E2E provider=openai model=gpt-4o-mini engine=javascript result=PASS executeCodeCalls=1 nestedCalls=10 inputTokens=… outputTokens=…
```

The token counts come from the LLM spans' token usage (`Span.setTokenUsage`, already recorded by the tracing LLM client) or from the responses' usage, summed over the turn. When neither is available, the line says `tokens=n/a`. Cost is not computed in code; the agent recording the run may estimate it from the tokens and the model's published price, labelled as an estimate. The line holds no key material.

**Teardown: stray-process check (shell variant).** The `SandboxedLocalTestEnvironmentProvider` root is a JUnit `@TempDir`. It holds the workspace and the engine's `.aimon-ptc/` reply directories. The runtime's **command line never contains it**, so the check cannot match on it (review-3):
- `LocalShell` places `RUN_DIR` with `Files.createTempDirectory("aimon-run-")` under `java.io.tmpdir` (`LocalShell.java:115,511`).
- The command is `python3 -I -u "$AIMON_RUN_DIR/aimon_ptc.py"` (`ShellCodeExecutionEngine.java:84,87`).
- The program's capture directory is `tempfile.mkdtemp(prefix="aimon-ptc-")` in the system temp (`aimon_ptc.py:519`).
- `LocalShell` is `final` (`:106`), and its run-directory seam `onRunDirectoryWritten` is package-private (`:558-561`), so a test in `aimon-cli` can neither subclass it nor hook the placement.

So the test records the run directories that **actually ran**, from the live processes, and checks those:
1. **Capture, while the runtime is alive.** A nested call is answered on the host while the Python runtime blocks waiting for the reply. So `GetHostStatus`, on each invocation, walks `ProcessHandle.current().descendants()`. For every process whose command line matches `(\S*/aimon-run-[^/\s]+)/aimon_ptc\.py`, it records group 1 (the exact `RUN_DIR` string bash expanded) and the PID into the fixture's `RunDirectoryRecorder`, a concurrent set. If `ProcessHandle.Info.commandLine()` is empty, which macOS can do, it falls back to `ps -ww -o args= -p <pid>`. The anchor, middle, runtime and watchdog processes are all `os.fork()`s of that Python process with no `exec` (`aimon_ptc.py:408-446`), so they keep its argv and are matched too. Bash's own command line holds the unexpanded `$AIMON_RUN_DIR` and does not match.
2. **Non-vacuity.** After the turn, the test asserts the recorder holds at least one run directory if any `ExecuteCode` call dispatched a nested call (assertion 1 requires one). A pattern that matches nothing therefore fails the test instead of passing it.
3. **Check, after the stack closes** (`@AfterAll`, polled for up to 5 s for kill latency):
   - for each recorded directory, `pgrep -f <ERE-escaped directory>` returns exit status 1. The value holds only path characters; `.` and any other ERE metacharacter is backslash-escaped;
   - every recorded PID is no longer alive: `ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)` is false.
   - `pgrep -f` matches by argv, so it still finds a runtime orphaned to `launchd` or `init`, which `descendants()` no longer lists. `createTempDirectory` never reuses a name, so the pattern cannot match another run's or another JVM's `aimon_ptc.py`.
4. **Limit, stated in the test's javadoc and the doc section.** A subprocess the model's program starts with its own `exec` (for example `subprocess.run(["sleep", "60"])`) has its own argv and is not matched. Q-C4 kills it with the runtime's process group. Only the run-level check covers it.

The offline `ExecuteCodeLiveFixtureTest` runs the same recorder and check in its shell part, so the mechanism is proven in plain `test` before any billed run. The run-level stray-process check (`pgrep -f aimon_ptc.py` returning nothing) is still done by the agent after each run (B.6).

### B.3.4 Doc section

- **`CONTRIBUTING.md` (+ `CONTRIBUTING.ko.md`).** In the live-test section (`:64-90`), add a subsection "Real-model ExecuteCode E2E" covering:
  - the command: `OPENAI_KEY_FILE=<path to a file holding the key> ./gradlew :aimon-cli:llmTest`, or `OPENAI_KEY=…` (and/or `ANTHROPIC_KEY`). The doc uses a placeholder path, not the user's;
  - that a key file is read in place, trimmed, and never copied, and that a set-but-unreadable `OPENAI_KEY_FILE` fails the run rather than skipping it;
  - the `LIVE-E2E …` result line;
  - what it asserts;
  - that it is billed, nondeterministic and never in CI;
  - the model override variables;
  - that the shell variant runs model-written Python on the local host and needs `python3` ≥ 3.8.
- **ptc.md §18 (+en).** Add one sentence pointing at the harness as the manual check for "실패 유형" and as the place to add measurements.

## B.4 Part B — changes by file

| File | Change |
|---|---|
| `core/agent/tool/ProgrammaticToolSettings.java` | `narrowedBy` |
| `core/agent/tool/ProgrammaticFrontmatter.java` | **new** (extracted parser) |
| `core/agent/definition/parser/MarkdownAgentDefinitionParser.java` | use helper |
| `core/subagent/SubagentMetadata.java`, `Subagent.java`, `subagent/parser/SubagentContentParser.java`, `subagent/SubagentToolScope.java` | `programmatic` field, parse, copy |
| `core/skill/SkillMetadata.java`, `skill/parser/MarkdownSkillParser.java` | `programmatic` field, parse |
| `core/tools/ToolContextKeys.java`, `core/tools/CallerProgrammatic.java` (**new**) | `CALLER_PROGRAMMATIC` |
| `core/agent/tool/ToolCallDispatcher.java` | **Breaking:** `narrow(registry, allowedTools, programmatic)` replaces the two-argument form; javadoc (B.1.3a) |
| `core/toolinvocation/DefaultToolCallDispatcher.java` | required `programmatic` builder field; set on the spec at `:79-83` (the only spec construction site); copied by `toBuilder()`; intersected by `narrow` |
| `core/toolinvocation/ToolCallDispatchers.java` | new `ungated(manager, registry, programmatic)`; the two-argument one delegates with `none()`; `Ungated` field, publish at `:79-81`, three-argument `narrow` |
| `core/toolinvocation/ToolInvocationSpec.java` | `programmatic` field, default `none()` |
| `core/toolinvocation/SingleToolInvoker.java` | publish `CALLER_PROGRAMMATIC` from the spec (`:189-191`) |
| `core/agent/impl/orca/OrcaAgentExecutor.java` | `gatedDispatcher` (`:2433-2441`) sets `.programmatic(agent metadata)`; the user-slash command context publishes `CALLER_PROGRAMMATIC` at `:2400` |
| `core/subagent/execution/DefaultSubagentExecutor.java` | `forkDispatcher` (`:604-613`) sets `.programmatic(scoped subagent's effective settings)` |
| `core/subagent/SubagentLaunchContext.java` + 4 spawn sites | `callerProgrammatic` |
| `core/subagent/DefaultSubagentExecutionManager.java` | narrow in `applyCallerCeiling` |
| `core/skill/fork/SubagentBackedSkillForkExecutor.java` | skill narrows target |
| `core/subagent/execution/DefaultSubagentExecutor.java` | offer with settings and facts; iteration attribute |
| `core/skill/execution/llm/LlmSkillExecutor.java` | compute the skill's effective settings once; pass them to `narrow` (`:247-248`) **and** to the offer (`offerContextOf`, `:530-537`) with facts and agent name |
| Test doubles and callers (B.1.3a list): `DefaultToolCallBridgeTest`, `LlmSkillExecutorTest`, `LlmSkillExecutorTruncationTest`, `OrcaAgentExecutorDispatchTest`, `DefaultSubagentExecutorDispatchTest`, `DefaultToolCallDispatcherTest`, `ToolCallDispatchersTest`, `ExecuteCodeEndToEndTest`, `ExecuteCodeShellEndToEndTest`, `ExecuteCodeUnavailableTest` | new `narrow` signature / `.programmatic(...)` on builders |
| `coreT/tools/code/ScriptedEngine.java` | class and `javascript()` made `public` (test source only; today package-private, `:21`, `:39`), so the orca-package E2E test below can register it |
| `coreT/agent/impl/orca/SlashSkillProgrammaticCeilingE2EIntegrationTest.java` | **new** (B.6) |
| `docs/design/tool/tool-call-dispatcher.md` | SPI change (B.1.3a); no `.en.md` twin |
| `modules/aimon-cli/.../config/ProgrammaticConfig.java`, `CliConfig.java`, `factory/AgentSetupFactory.java`, `resources/default-config.yaml` | B.2 |
| `modules/aimon-cli/build.gradle.kts` | `llmTest` task |
| `scripts/release.sh:262`, `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:118-127` (tier note), `.claude/skills/release/SKILL.md:96` | Reword "no tier is opt-in" so it names the two deliberate out-of-CI tiers: the provider-key live classes and `:aimon-cli:llmTest`. These are the three files `ReleaseGateMatchesCiGateTest.everyTestTagIsGated` says to reword first when a tier is meant to stay out (review-1). The `llmTest` tier is untagged, so the guard's tag set is unchanged. `ReleaseGateMatchesCiGateTest.releaseSkillDescribesTheRealGate` and `releaseSkillDoesNotCallAGatedTierUngated` parse `SKILL.md`, so the rewording must keep both green. `llmTest` is named there only as a deliberately un-run tier, never in a command line the skill tells the releaser to run, so it cannot read as a gated tier called ungated (review-3). |
| `modules/aimon-cli/src/test/java/at/aimon/cli/e2e/*` | **new** harness: `ExecuteCodeLiveFixtures` (shared fixture, including `RunDirectoryRecorder`), `ExecuteCodeJsLiveE2ETest`, `ExecuteCodeShellLiveE2ETest`, `ExecuteCodeLiveFixtureTest` (offline, in `test`), `SandboxedLocalTestEnvironmentProvider`, `LiveKeys` + `LiveKeysTest` |
| docs listed in B.1.6, B.2, B.3.4 (+ translations) | |
| `CHANGELOG.md` `[Unreleased]` | Added: fork/skill PTC keys, CLI `enablePython` / `shellEngineToolScope`, `llmTest`. **Breaking:** the `ToolCallDispatcher.narrow` bullet quoted in B.1.3a |

## B.5 Part B — failure modes

| Situation | Handling |
|---|---|
| Fork definition opts in, caller has no PTC | No `ExecuteCode`. Its iteration span gets `ptc.unavailable_reason` = "programmatic-tools is not set" only if the effective setting is enabled — it is not, so nothing is set. For the operator: WARN once per subagent (`WarnOnce` `name\0caller-ceiling`): "subagent X sets programmatic-tools, but its caller allows none of them". That lets a misconfiguration be found |
| Engine conflict (own `python`, caller `javascript`) | `none()` + WARN once |
| `programmatic-only` names a tool the fork cannot call directly (not in its allowed tools) | Already handled by the offer: §7.3 step 1 drops it, and the existing per-tool WARN fires |
| Skill frontmatter has PTC keys but `allowed-tools` excludes them | Same as above |
| CLI bad `shellEngineToolScope` | Config load error naming `programmatic.shellEngineToolScope` |
| CLI shell engine not closed | Teardown registration; the test asserts close |
| E2E: model does not use ExecuteCode or calls only once | Assertion 1 fails, with the full conversation printed. The model override lets the launcher retry with a stronger model. No automatic retry, to keep cost bounded |
| E2E: no key | That provider is skipped; with no key at all, the whole class is skipped, and the task reports "skipped", not "passed" |
| E2E: `OPENAI_KEY_FILE` set but missing, unreadable or empty | The test fails, naming the variable and path, never content |
| E2E: key would leak into output | Prevented by B.3.1 item 3. A real run's output is checked for the key prefix in memory, and the check is recorded |
| Slash skill without PTC (or with a narrower set) spawns a fork via `Task` | The narrowed dispatcher publishes the skill's effective settings as `CALLER_PROGRAMMATIC` (B.1.3a). The fork's own opt-in is ceilinged by them, so it is not offered `ExecuteCode` (or only the narrower set), and the `name\0caller-ceiling` WARN fires |
| Embedder binds a dispatcher whose settings disagree with the `CALLER_PROGRAMMATIC` it publishes by hand | `narrow` intersects with the dispatcher's own settings, so the narrower of the two wins. Fail-closed, no error |
| A `ToolCallDispatcher` implementation outside core | Compile error on the removed two-argument `narrow` (CHANGELOG Breaking bullet). Intended: each implementation must decide what it publishes |
| E2E shell variant leaves processes behind | The teardown check (B.3.3) fails the test. It matches each recorded `RUN_DIR`, and the non-vacuity assertion fails the test if no run directory was recorded at all. A program-spawned `exec`'d subprocess is covered only by the run-level `pgrep -f aimon_ptc.py` and Q-C4's group kill |

## B.6 Part B — test strategy

- **Unit:**
  - `ProgrammaticToolSettingsTest.narrowedBy`: intersection order, empty → none, codeOnly union ∩ tools, engine table, scope narrowing, `none()` ceiling, and **idempotence** (`x.narrowedBy(c).narrowedBy(c)` equals `x.narrowedBy(c)`), which B.1.3a relies on;
  - `DefaultToolCallDispatcherTest`:
    - `dispatchPublishesCallerProgrammatic`: a probe tool reads `CallerProgrammatic.of(ctx)` and sees the builder's value, even when the caller's context pre-seeds a different one;
    - `narrowIntersectsProgrammatic`: `[Read, Grep]` narrowed by `[Read]` gives `[Read]`, and narrowed by `none()` gives `none()`;
    - `narrowCannotWidenProgrammatic`: a dispatcher with `[Read]` narrowed with `[Read, Grep, Write]` still publishes `[Read]`;
    - `programmaticSurvivesRepeatedNarrow`: narrowed twice, through `toBuilder()`;
    - `buildWithoutProgrammaticFails`.
  - `ToolCallDispatchersTest`: the same publish, intersect and cannot-widen cases for `Ungated`, plus `twoArgumentUngatedPublishesNone`;
  - `LlmSkillExecutorTest`: through `RecordingToolCallDispatcher.narrowedProgrammatic`:
    - a skill without PTC keys under a caller with `[Read, Grep]` narrows with `none()`;
    - a skill with `[Read]` under the same caller narrows with `[Read]`;
    - a skill with `[Read]` under a caller with no `CALLER_PROGRAMMATIC` narrows with `none()`;
    - the offer context's settings equal the value passed to `narrow`;
  - `ProgrammaticFrontmatterTest`: moved from the agent parser tests, with the same cases for subagent and skill;
  - parser tests for `SubagentContentParser` and `MarkdownSkillParser`;
  - `SubagentToolScopeTest`: the field is copied.
- **Loop paths:**
  - **Fork.** `DefaultSubagentExecutorSideEffectFilterTest` gets `executeCodeOfferedWhenOwnAndCallerAllow`, `notOfferedWithoutCallerCeiling`, `notOfferedWithoutOwnOptIn`, `callableSetIsIntersection` and `nestedForkNarrowedByParentFork`.
  - **Slash skill.** `LlmSkillExecutorSideEffectFilterTest` gets the same four cases.
  - **Skill fork.** A test in the `SubagentBackedSkillForkExecutor` tests.
  - **Caller with no allow-list.** In `DefaultSubagentExecutionManager`'s tests, a launch with an empty `callerAllowedTools` and `callerProgrammatic = none()` still yields a scoped subagent with `none()`. This proves the narrowing is not skipped by the early return at `:1269-1271`.
  - **Slash skill without PTC spawns a fork (review-3 Blocking): `coreT/agent/impl/orca/SlashSkillProgrammaticCeilingE2EIntegrationTest`.**
    - **Fixture.** It is modelled on `SlashSkillToolDispatchE2EIntegrationTest`: the same real `OrcaAgentExecutor` / `DefaultSubagentExecutionManager` set-up (`:105-123`) and the same `InMemorySubagentRegistry` fork fixture (`:306-310`). On top of that:
      - the Task tool and `ExecuteCodeTool(List.of(ScriptedEngine.javascript()), …)` are registered alongside test tools `Read` and `Grep`;
      - the main agent's metadata has `programmatic-tools: [Read, Grep]`;
      - subagent `reader` declares `programmatic-tools: [Read, Grep]` and `allowed-tools: [ExecuteCode, Read, Grep]`;
      - inline skill `probe` has `allowed-tools: [Task, Read, Grep]` and **no** programmatic keys.
    - **LLM client.** A local client records the `tools` list of every call, keyed by system prompt. The test cannot reuse the private `ScriptedLlmClient` (`:556`). The skill loop's first call answers `Task(subagent_type="reader", …)`. The fork's first call is captured, and the fork then answers with a final text.
    - **Cases:**
      1. **User-typed `/probe`.** `CALLER_PROGRAMMATIC` comes from the command context (`OrcaAgentExecutor.java:2400`). The fork's offered tools contain `Read` and `Grep` and **do not contain `ExecuteCode`**. The `caller-ceiling` WARN is logged once.
      2. **Model-called `Skill(probe)`** in the main turn. `CALLER_PROGRAMMATIC` comes from `SingleToolInvoker`. The result is the same as case 1.
      3. **Control, so cases 1–2 are not vacuous.** The same fixture, but `probe` declares `programmatic-tools: [Read, Grep]`. The fork **is** offered `ExecuteCode`, and its description lists `Read` and `Grep` as callable.
      4. **Narrower skill.** `probe` declares `programmatic-tools: [Read]`. The fork is offered `ExecuteCode` with only `Read` callable.
      5. **Main agent without PTC, skill with PTC.** The fork is not offered `ExecuteCode`, because the caller ceiling reaches the skill first.
    - Case 1 is the test the launcher asked for. On today's code, cases 1 and 2 would offer `ExecuteCode`, because the skill's calls would publish the main agent's settings.
  - **End to end.** `ExecuteCodeEndToEndTest` gets a fork variant: the main agent calls `Task`, and the fork's `ExecuteCode` runs a `ScriptedEngine` program that makes 2 nested calls. The test asserts that the quota is counted and that nested spans parent under the fork's ExecuteCode span.
- **CLI:** the B.2 tests.
- **E2E fixture, offline:** `ExecuteCodeLiveFixtureTest` runs in `test` and `checkAll` (B.3.2). It proves, **before** any billed run:
  - both variants offer `ExecuteCode` with both tools callable;
  - a scripted program passes the B.3.3 assertions;
  - in the shell part, the stray-process mechanism works: the `RunDirectoryRecorder` captured at least one `aimon-run-*` directory while the runtime was alive, and after close `pgrep -f` on it returns exit status 1. This is the positive and negative half of B.3.3's check.
- **E2E:** the live classes are compile-checked in `test` (they skip). The builder verifies these paths without spending money:
  - `./gradlew :aimon-cli:test` shows the classes as skipped, even with `OPENAI_KEY_FILE` set;
  - `./gradlew :aimon-cli:llmTest` without keys reports every test skipped and exits 0;
  - `OPENAI_KEY_FILE=/nonexistent ./gradlew :aimon-cli:llmTest` fails with a message naming the path;
  - `LiveKeysTest` covers the resolver (a temp key file with a trailing newline, a `~/` value, `OPENAI_KEY` taking precedence, blank values). It is a plain unit test that uses fake keys. It reads environment through an injectable `Function<String,String>` so the test does not depend on the real environment.
- **Real runs before merging PR 2** (TASK addendum). The dev agent, and the reviewer if it wants an independent result, **should** run `OPENAI_KEY_FILE=~/.openai-key ./gradlew :aimon-cli:llmTest` before PR 2 merges. Runs are kept few because they cost money: at most 3 per build round across both agents, and no reruns only to turn a flaky result green. A failure is diagnosed, fixed if the harness or code is at fault, and recorded either way.
  - Each run's outcome goes into `build/summary.md` and the PR 2 body: model, engine, pass/fail, number of `ExecuteCode` calls and nested tool calls, tokens (and an estimated cost if computed), copied from the `LIVE-E2E` lines. Skipped engines are noted with the reason (for example, the shell variant without `python3`).
  - The key file is never printed, `cat`-ed, copied or pasted, including in agent transcripts. Agents pass only its path.
  - Each run ends with the stray-process check (`pgrep -f aimon_ptc.py` returns nothing).
- **Gate:** `checkAll`, the doc scripts, `integrationTest`, and no stray `aimon_ptc.py`.

---

## Open questions

These were not resolvable from TASK or the docs. Each records the assumption the design makes, so the reviewer can overturn it.

- **OQ-A1 — Reflow guard scope.** The guard covers PTC code only, per "sweep the PTC code". About 430 orphan lines elsewhere stay. Should a follow-up widen it repo-wide? Assumed: not in this task.
- **OQ-A2 — `ptc.tool_scope` value set.** It is set for every bound run (A-DV1), not only `unrestricted`. Assumed acceptable as a superset.
- **OQ-A3 — Changing the model-visible summary line** to `[ExecuteCode (Python): …]` (A-DV3). The only alternatives that persist the language are a session-record schema change or keeping no marker at all. Assumed acceptable. It slightly changes prompt-cache-stable text only in new results.
- **OQ-A4 — Sweep trace seam.** Resolved: the trace path is an optional `config.json` key (`sweep_trace`), which the engine never writes. There is no environment variable.
- **OQ-B1 — Anthropic key name.** TASK says `ANTHROPIC_API_KEY`; the repo uses `ANTHROPIC_KEY`. Both are accepted. The addendum supplies only an OpenAI key file, so Anthropic runs stay unexercised in this task. Its path is compiled and skip-tested only.
- **OQ-B2 — Default models.** The precedent uses `gpt-4o-mini` and `claude-haiku-4-5-20251001`. Weak models may decline to write code. Overridable through env; the launcher may want stronger defaults.
- **OQ-B3 — Exposing stdout length or text in `renderPayload`** for assertion 2(c). It is additive and not LLM-visible. Assumed acceptable. If rejected, 2(c) falls back to "the content does not contain any `RAW-` marker the program did not print", checked against the code text.
- **OQ-B4 — CLI flag shape.** The design uses `enablePython`, an opt-in like `enableJs` (B-DV2), rather than "assemble whenever programmatic is on" like the stack. Assumed in keeping with CLI conventions.
- **OQ-B5 — Fork/skill rule** (B-DV1): own opt-in ∩ caller ceiling, fail-closed when the caller has no PTC. This is the narrowest rule. It means a PTC-only subagent cannot be used under a non-PTC main agent. Assumed intended by "agents can only narrow". "Caller" includes a slash skill (B.1.3a).
- **OQ-B6 — Two-argument `ToolCallDispatchers.ungated` kept.** It delegates with `none()`, which is fail-closed and avoids churning its existing callers (all in tests). Only `narrow` loses its two-argument form, because there a default would either leak or silently change behaviour. The alternative is to remove the two-argument `ungated` as well, for symmetry. Assumed: keep it.
- **OQ-B7 — `narrow` intersects rather than replaces.** The narrowed dispatcher publishes `argument.narrowedBy(own)`, not the argument verbatim, so `narrow` can never widen, matching the allow-list rule. In production this is a no-op by idempotence (B.1.3a). Assumed correct.

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

---

## 2. After the PR 2 build — where the implementation departed

*Appended 2026-11-07, after the PR 2 build. Part B above is the body as approved by design review round 4, not edited
to look prescient. Sources: the run's `build/pr2-deviations.md`, review 4's non-blocking notes, and what was found
while building.*

**No decision in B.1 – B.3 changed.** The rule is own opt-in narrowed by the caller (B-DV1), with the narrowing table of
B.1.1; the value lives on the dispatcher and `narrow` carries it (B.1.3a, the breaking SPI change); the CLI assembles the
shell engine behind `programmatic.enablePython` (B-DV2); the harness is `:aimon-cli:llmTest`, gated by a system property
only that task sets (B-DV3). B-DV1 – B-DV3 are recorded in [`programmatic-tool-calling.md`](programmatic-tool-calling.md)
§21, with the render-payload addition below.

### 2.1 Where the build departed from the body

- **A model-called `Skill` has no skill loop** (B.6 case 2, B.1.3a "whether the user typed `/skill` or the model called
  `Skill`"). `SkillTool` injects an inline skill's rendered instructions into the main turn; only the user-typed
  `/skill` path runs `LlmSkillExecutor`. So when the model calls `Skill(probe)` and then `Task`, the main loop runs
  `Task` and the agent's settings are the ceiling — no narrowed dispatcher exists to publish the skill's. The test
  (`SlashSkillProgrammaticCeilingE2EIntegrationTest`) pins that behaviour under a name that says so, B-DV1 names it as
  an exception, and [TC-26](../../backlog/tool-contract-open-items.md) records it; the allow-list has the same
  asymmetry. The SPI change is still what makes the one path with a skill loop correct.
- **The ceiling test's fixture differs in two places** (B.6). The skill allows `ExecuteCode` as well
  (`[Task, Read, Grep, ExecuteCode]`): a skill's allow-list is also the allow-list ceiling of its forks, so without it
  the control case fails and the negative cases pass for the wrong reason. And its environment declares HOST isolation,
  without which `programmatic-engine: auto` picks no engine; executor-level offer tests set `javascript` for the same
  reason.
- **The WARNs are the execution manager's, not `narrowedBy`'s** (B.1.1, B.5). `narrowedBy` stays a pure value method;
  `DefaultSubagentExecutionManager.applyProgrammaticCeiling` warns once per `name\0caller-ceiling` or
  `name\0engine-conflict` (a bounded static set), and tells the two apart with a new
  `ProgrammaticToolSettings.hasEngineConflict(other)`. It runs before the early return for an empty allow-list ceiling,
  as B.1.2 requires.
- **Small shapes.** `ShellEngineToolScope.narrower` is the existing `min`. `ProgrammaticFrontmatter.parse(Map)` takes no
  source name: its messages are the agent parser's old ones, and each parser wraps its `IllegalArgumentException` in
  its own exception (review 4). `SubagentToolScope.withProgrammatic` is new and shares one rebuild with
  `withAllowedTools`; `SubagentContentResult` carries the value through `withProgrammatic(...)`, a copy like
  `withHidden(...)`. B.1.5 is `IterationTrace.annotateUnavailableCode()`, after each offer, the grace iteration included.
- **CLI** (B.2). The shell engine is created in `create()` through a package-private seam
  (`newShellCodeExecutionEngine()`, so a test sees it closed), handed to `buildToolSpec` as a fourth argument, closed on
  the stack-build failure path, and enrolled on `SCRIPT_ENGINES` before `decorate` runs, so a decorate failure releases
  it too. `ProgrammaticConfig.setShellEngineToolScope` throws without a cause, because Jackson reports a failed setter's
  root cause and would drop the key name.
- **The render payload gained the full `stdout` text, and `returnValue` when present** (OQ-B3). Assertion 2(c) is then
  "every raw marker in a result is one the program printed or returned", plus a size bound, rather than an exact
  "content minus its sections equals stdout": the observation cuts stdout to its budget and joins the return value, so
  equality would re-implement `ExecuteCodeObservation`.
- **Assertion 1 is judged per program, and only successful calls count** (B.3.3; PR 2 review 1). The first build
  added up dispatched calls and covered tools over every `ExecuteCode` result, so two programs that each called one
  tool passed. Now one program must make at least two nested calls that were dispatched (not refused) and answered
  without error, covering both tools, and the span cross-check counts the code-path TOOL spans whose
  `ptc.parent_tool_use_id` is that program's id. "Without error" is stricter than the body's "not refused". The
  `LIVE-E2E` line reports `nestedCallsBestProgram`, `nestedCallsAllPrograms`, `perProgram=[…]` and the turn's
  `completion`, and each program's own numbers are printed above it.
- **The `ExecuteCode` description changed** (TC-27, closed in this PR after review 1). Python: the functions are
  predefined globals (do not import them), the program already runs in an event loop (top-level `await`, never
  `asyncio.run()`), and a JSON string is parsed with `json.loads`; the list header says "already defined; do not
  import them". JavaScript: a JSON string is parsed with `JSON.parse`. This is model-visible text the body did not
  plan to touch; the real-model runs (§2.4) found the gap and measured the fix.
- **Harness additions** (B.3). A `LiveModels` helper builds the clients; the `LIVE-E2E` line also carries
  `answerMatches=` (the soft check); each live run prints its `ExecuteCode` results and programs, not only on failure;
  diagnostics print on any `Throwable`. Token counts come from the turn's result metadata.
- **Not added:** `nestedForkNarrowedByParentFork` as an end-to-end grandchild test (B.6). Both halves are tested — a
  fork's dispatcher publishes its scoped subagent's effective settings, and the manager narrows any fork by
  `callerProgrammatic` — but no test runs three levels.

### 2.2 Review 4's notes: taken, and left

| Note | Outcome |
|------|---------|
| The fifth spawn site (`GraalJsWorkflowTool`), `SubagentLaunchContext.toBuilder`, the default | Taken: all five sites set `callerProgrammatic`, `toBuilder` copies it, the default is `none()` (javadoc) |
| `MarkdownSubagentParser` wiring and its test | Taken |
| A neutral exception for the shared helper | Taken (§2.1) |
| Only the translation twin that exists | Taken: `subagent-development-guide.en.md`; `aimon-skill-extensions.md` has no twin |
| Name the background-`Workflow` exception in B-DV1 | Taken; [TC-25](../../backlog/tool-contract-open-items.md) |
| State the fork-mode vs inline skill asymmetry | Taken: ptc.md §7.2 and B-DV1, the skill-extensions reference |
| Reset the warn-once state between cases | Distinct subagent names per case; unit tests reset the manager's set |
| CLI limits for any engine; close the shell engine on the failure path | Taken; both tested |
| Is `Bash` (and so the shell `ExecuteCode`) offered without a human under the stack's defaults? | Yes. `ExecuteCodeLiveFixtureTest` runs the shell variant through the stack's defaults with a scripted model: offered, both tools callable, ten nested calls, no approval prompt, no `ptc.unavailable_reason`. The fixture needed no change |
| The release `SKILL.md` rewording keeps `ReleaseGateMatchesCiGateTest` green | Taken; green |

### 2.3 Open questions that went to the backlog

- **A PTC subagent under a non-PTC caller** (OQ-B5) — [TC-24](../../backlog/tool-contract-open-items.md).
- **The background-`Workflow` ceiling** — [TC-25](../../backlog/tool-contract-open-items.md).
- **A model-called inline skill has no loop to narrow** — [TC-26](../../backlog/tool-contract-open-items.md).
- [TC-23](../../backlog/tool-contract-open-items.md) (slash-skill spans) is now due: slash skills offer `ExecuteCode`.
- **What the real-model runs found** (§2.4): the Python description did not say the functions are predefined
  globals or that the program already runs in an event loop — [TC-27](../../backlog/tool-contract-open-items.md),
  closed by the description change in §2.1; a GraalJS syntax error reaches the model as its caret line only —
  [TC-28](../../backlog/tool-contract-open-items.md); the `[logs]` section shows the model host paths —
  [TC-29](../../backlog/tool-contract-open-items.md).

OQ-B1, B2, B4, B6 and B7 were taken as assumed. OQ-B2's default models stay; see §2.4.

### 2.4 Real-model runs, and what is not verified here

Five runs of `OPENAI_KEY_FILE=… ./gradlew :aimon-cli:llmTest` on 2026-11-07, OpenAI only (no Anthropic key was
available, so that path is compiled and skip-tested only). Runs 1–3 are before review 1, on the first harness and the
old description; their per-program numbers were read back from each result's summary line. Runs 4–5 are after it, on
the per-program assertion and the TC-27 description. "Best program" is the successful nested calls of the best single
program that called both tools — what assertion 1 judges.

| Run | Model | Engine | Result | Programs | Calls per program | Best program | All programs | Tokens in / out | Answer right |
|-----|-------|--------|--------|----------|-------------------|--------------|--------------|-----------------|--------------|
| 1 | `gpt-4o-mini` | JavaScript | PASS | 1 | 10 | 10 | 10 | 1 322 / 223 | no |
| 1 | `gpt-4o-mini` | Python | error — `NoClassDefFoundError`: a parallel build in the same worktree replaced the core jar mid-run; not a harness or product failure | 2 | — | — | 0 | — | — |
| 2 | `gpt-4o-mini` | JavaScript | PASS | 6 | 0, 2, 2, 2, 2, 2 | 2 | 10 | 2 667 / 663 | no |
| 2 | `gpt-4o-mini` | Python | PASS | 10 | 0, 0, 10, 0 ×7 | 10 | 10 | 15 873 / 1 170 | no |
| 3 | `gpt-4o` | JavaScript | PASS | 1 | 10 | 10 | 10 | 1 392 / 262 | yes |
| 3 | `gpt-4o` | Python | FAIL — every program `from functions import …` (`ModuleNotFoundError`) | 4 | 0 ×4 | 0 | 0 | 20 561 / 2 146 | no |
| 4 | `gpt-4o` | JavaScript | PASS, `COMPLETED` | 1 | 10 | 10 | 10 | 1 403 / 243 | yes |
| 4 | `gpt-4o` | Python | PASS, `COMPLETED` | 1 | 8 | 8 | 8 | 1 873 / 241 | yes |
| 5 | `gpt-4o-mini` | JavaScript | PASS, `COMPLETED` | 1 | 10 | 10 | 10 | 1 382 / 222 | yes |
| 5 | `gpt-4o-mini` | Python | PASS, `MAX_ITERATIONS` | 11 | 0, 10, 10, 0, 10, 10, 0, 10, 10, 0, 0 | 10 | 60 | 23 290 / 2 605 | no |

The mechanical claims held wherever a program ran its calls: no host tool was called directly, every result's summary
named its language, and no raw marker came back that a program had not printed. The failures are what the harness is
for (ptc §18, "실패 유형"): `gpt-4o-mini` read the tools' JSON-string results as objects in JavaScript (every host
compared `undefined`), and both models guessed a `functions` module or `asyncio.run` in Python (TC-27). Every PASS of
runs 1–3 also holds under the per-program rule. After the description change, `gpt-4o` solved both variants in one
program each, and `gpt-4o-mini` solved JavaScript in one program; `gpt-4o-mini`'s Python still opened with
`asyncio.run` in five of eleven programs and kept re-running programs that printed nothing until the iteration cap.
OQ-B2's defaults stay — a stronger default would hide exactly these signals — and `AIMON_LLM_TEST_OPENAI_MODEL` exists
for a run that wants a pass. No output of any run contained the key's prefix (checked in memory), and no
`aimon_ptc.py` process was left after any run.

**Not verified here:**
- The Anthropic path of the harness (no key).
- CI's run of the new shell-engine tests (that they ran rather than skipped for want of `python3`) belongs to the PR.
- A grandchild fork end to end (§2.1).
- Whether the TC-27 wording helps across providers and over many runs: one run per model is a sample, not a measure.
