# Design — ptc-leftovers-core

> Status: **PR 1 IMPLEMENTED**; PR 2 and PR 3 not yet built. **PR 1** — `aimon-core` and `aimon-graaljs` (tests only):
> the language notice skips a refused `ExecuteCode` call (`ExecuteCodeLanguageNotice`, breaking signature), fail-safe
> span recording (`at.aimon.core.tracing.Spans.*Safely`), the Python runtime's loop-only-when-needed, pre-imports and
> program-only tracebacks (`aimon_ptc.py`, `ToolApiRenderer.PYTHON_RESERVED`), the script-error line with an excerpt
> (`ExecuteCodeObservation`), the nested-span payload key (`ToolContextKeys.TRACE_PAYLOAD_POLICY`), the measured reply
> drain (`ShellProgramRun.REPLY_DRAIN`, 2 s), the generalized `CommentReflowTest` predicate and the grandchild-fork
> ceiling test. The docs it changed are [`programmatic-tool-calling.md`](programmatic-tool-calling.md) §6.3, §12.1,
> §12.2, §21 and [`programmatic-tool-calling-shell.md`](programmatic-tool-calling-shell.md) §8, §11; it closes
> TC-20, TC-22, TC-28 and TC-29 in [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md).
>
> **[§4](#4-after-the-pr-1-build--where-the-implementation-departed), appended after the PR 1 build, is where the build
> departs from this document;** PR 2 and PR 3 each append a section after it. Everything between this header and §4 is
> the body as approved by design review round 3, kept byte-exact rather than corrected
> ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). The body's citations (`file:line`) are at
> `318805f5`; its path shorthands (`core/`, `coreT/`, `rt`, `ptc.md`, `shell.md`, `features.md`, `register`) are defined
> in the preamble. The review transcripts and the run's `TASK.md` are not in the repository. The open questions that
> outlive PR 1 are TC-30 and TC-31 in the register; OQ-3, OQ-4 and OQ-8 are answered in §4.
>
> It is in English, like its siblings in this directory written from an approved run; `docs/design/` is not a
> translation target (`docs/project/documentation-guide.md` §5.1).

Base: `main` @ `318805f5` (PTC PR 2 merged). Three PRs, built in order, each from the then-current `origin/main`.

Path shorthands (same as `docs/design/tool/ptc-features-core.md`):
- `core/` = `modules/aimon-core/src/main/java/at/aimon/core/`
- `coreT/` = `modules/aimon-core/src/test/java/at/aimon/core/`
- `rt` = `modules/aimon-core/src/main/resources/at/aimon/core/tools/code/shell/aimon_ptc.py` (the shell-engine Python runtime)
- `ptc.md` = `docs/design/tool/programmatic-tool-calling.md`
- `shell.md` = `docs/design/tool/programmatic-tool-calling-shell.md`
- `features.md` = `docs/design/tool/ptc-features-core.md`
- `register` = `docs/backlog/tool-contract-open-items.md`

Citations (`file:line`) are at `318805f5`.

---

## 0. Problem, restated

PTC (`ExecuteCode`) shipped in PRs #337 and #338, but some work is still open. Reviews of those PRs left ten
non-blocking findings. Six of them change behaviour:
- a false language notice after a refused call;
- unguarded span calls;
- a 2 s sweep join on the self-ending path;
- `gpt-4o-mini` Python tripping on asyncio;
- host paths in the model-visible `[logs]`;
- a GraalJS syntax error reaching the model as just `^`.

Four are hygiene:
- a stranded javadoc word;
- CHANGELOG width and the `ANTHROPIC_API_KEY` mention;
- a missing grandchild-fork test;
- a reflow guard that misses longer wraps.

Those PRs also registered backlog items TC-20…TC-29 (TC-27 is already closed):
- **Measurement and observability gaps:**
  - TC-20, the reply-drain bound;
  - TC-22, the nested-span payload policy;
  - TC-23, spans in the slash-skill loop.
- **Ceiling holes (both only narrow):**
  - TC-25, a background `Workflow` run escapes its skill or fork ceiling;
  - TC-26, a model-invoked inline skill does not enforce its own allow-list or programmatic settings.
- **One widening decision:** TC-24.
- **Model-visible error text:** TC-28 and TC-29.
- **A mechanical repo-wide reflow:** TC-21.

The task is to close all of it in sequential PRs. Each PR must:
- keep `checkAll`, `integrationTest`, the doc scripts and the translations green;
- update the CHANGELOG, recording breaking changes;
- close its backlog entries in the register's format.

The PR that changes model-visible text is re-measured with the real-model E2E harness.

## 0.1 PR split and what each PR closes

| PR | Closes | Theme |
|---|---|---|
| **PR 1** | Items A1–A10 (A7 = TC-29, A8 = TC-28); backlog **TC-20, TC-22, TC-28, TC-29** | Non-blocking fixes, model-visible error text, the asyncio fix, nested-span payload, the drain bound. Real-model E2E after it. |
| **PR 2** | Backlog **TC-23, TC-24 (decided "no"), TC-25, TC-26** | Slash-skill spans and the ceilings: background workflow, model-invoked inline skill |
| **PR 3** | Backlog **TC-21** | Repo-wide comment-reflow guard and the fixes it reports |

This refines TASK's suggested split in one place only: A10 (the reflow predicate) stays in PR 1, because PR 1's own
edits must pass the stronger predicate. PR 3 then widens only the scope, not the predicate.

**The approved design is committed.** As with `features.md`, PR 1 commits this document as
`docs/design/tool/ptc-leftovers-core.md`:
- the body byte-exact as approved, under a status header (`docs/design/README.md` §3.4);
- English, like its siblings (`docs/design/` is not a translation target, documentation guide §5.1);
- PR 2 and PR 3 each append a "where the build departed" section, as `features.md` §1 / §2 do;
- it is added to `CommentReflowTest.MARKDOWN` in PR 1.

---

# PR 1 — non-blocking fixes, error text, payload policy, drain bound

Closes: A1, A2, A3, A4, A5, A6, A7 (TC-29), A8 (TC-28), A9, A10; backlog TC-20, TC-22, TC-28, TC-29.

## 1.1 A1 — a refused `ExecuteCode` call triggers a false conservative language notice

**Today.** `ExecuteCodeLanguageNotice.check` (`core/tools/code/ExecuteCodeLanguageNotice.java:100-110`) treats any
`ExecuteCode` tool_use whose result has no readable summary line as "A-unknown". It then sets `unreadable = true`, and
with no known marker the check returns the conservative notice. Many calls never ran any language at all:
- `Invalid parameter` (`ExecuteCodeTool.java:232`);
- "not available here" (`:239`);
- no dispatcher (`:245`);
- probe failure or unavailable (`:252-258`);
- a PreTool hook deny, a permission deny, an approval rejection, a schema-validation refusal.

A fresh session whose first `ExecuteCode` call is refused therefore gets "earlier ExecuteCode programs … may be in
another language" once, although nothing ran.

**Chosen approach: an error result with no summary did not run, so skip it.**

Every program that ran gets `ExecuteCodeObservation.success` or `.failure` and ends with the summary line:
- a script error, a timeout, a cancellation, a limit, a protocol violation;
- the engine-failed path (`ExecuteCodeTool.java:286-289` builds a `SCRIPT_ERROR` outcome and goes through `failure`).

So a result that is **`isError` and has no summary** was produced before any program ran. That covers the tool's early
returns and every refusal made outside the tool. In the backwards scan:

```text
inView = result in view for this tool_use (content + isError)
ran    = languageOf(inView.content) or languageOf(logged content)
if ran present                        -> verdict
else if inView present && inView.isError -> skip (did not run; NOT unreadable)
else                                  -> unreadable = true
```

- `viewResults` changes from `Map<String,String>` to `Map<String, ToolUseResult>` so `isError` is available.
- The log lookup stays content-only. The view's `isError` is authoritative: `elideInView` and summary spans replace
  content, not the error flag. The builder confirms this on `TranscriptBuffer.elideInView` and records it in a code
  comment.
- When the result is missing from the view entirely (dropped), behaviour is unchanged: the lookup falls to the log, and
  if the log copy has no marker the call counts as unreadable. Extend the log lookup to return `isError` too, so that
  a refusal dropped from view also skips. `check(..., Function<String, Optional<ToolUseResult>>, ...)` and
  `loggedResults(...)` are `public static` and are called from `OrcaAgentExecutor` (`:2537-2538`), so this is a
  **public signature change**. Breaking changes are allowed, and it is recorded in CHANGELOG (review-1).

**Rejected.**
- *Make the tool's early returns end with a summary line.* That covers only the tool's own refusals. Permission, hook
  and approval denials are produced by the pipeline, which knows nothing about `ExecuteCode` markers. It would also put
  a "0 tool calls, 0.0s" summary on a call that never ran, a misleading observation.
- *A dedicated "did not run" marker in refusals.* Same coverage problem.

**Residual failure mode (accepted, documented in the class javadoc).** Some programs that did run end with an
**error** result that has no summary. They now read as "did not run" rather than "unreadable". There are three ways
this happens:
- a PostTool hook rewrites the error result and drops the summary;
- an `Error` (not a `RuntimeException`) is thrown out of `engine.execute`, and the pipeline turns it into a generic
  error result;
- a turn interrupt makes the loop synthesize the result (review-1).

All three are named in the javadoc. That
program is skipped silently instead of triggering the conservative notice. This needs a rewriting hook on an error
result, plus a language change, plus no older readable marker. The worst outcome is a missed notice line, with no
permission effect.

## 1.2 A2 — stranded javadoc word in `OrcaAgentExecutor.resolveEffectiveToolContext`

`core/agent/impl/orca/OrcaAgentExecutor.java:2601-2602`: join `unchanged` with the next line, then reflow the
paragraph. The file is outside `CommentReflowTest`'s PR 1 scope; PR 3 brings it in.

## 1.3 A3 — span setters and close are unguarded in nested-call spans

**Today.**
- `Spans.startChildSafely` and `Spans.enrichSafely` are fail-safe.
- `setAttribute`, `setOutputs`, `error`, `interrupted` and `close` are called raw in several places:
  - `CodeSpanAttributes.Nested.open` (`:108-113`) and `refused` (`:136-141`);
  - `DefaultToolCallBridge.dispatch` (`:250-262`), through `ToolSpans.recordOutcome` and `recordFailure`;
  - `ExecuteCodeTool.annotateSpan` (`:332-337`);
  - the loops via `ToolCallTracing`.
- A `Span` that throws on any of these would fail the nested call, or mask its real exception from the `finally`.
  `features.md` A.8 promises that tracing never fails a nested call.

**Change: fail-safe span operations in `core/tracing/Spans`, used by every PTC span site.**
- Add these static methods to `Spans`:
  - `setAttributeSafely(SpanAttributes, String, String)`;
  - `setOutputsSafely(Tracer.Span, Object)`;
  - `errorSafely(Tracer.Span, String)` and `errorSafely(Tracer.Span, Throwable)`;
  - `interruptedSafely(Tracer.Span)`;
  - `closeSafely(Tracer.Span)`.
- Each catches `RuntimeException`, logs, and returns. `Error` is not caught, matching `startChildSafely`.
- **Logging does not flood** (review-1). The first failure of each operation name is logged at WARN
  (`"Tracer.Span.<op> failed: {} (further failures of this operation are logged at DEBUG)"`), and later ones at DEBUG.
  The bookkeeping is a static `Set<String>` with at most six entries, one per operation.
  `TracingLlmClient`'s TTFT attribute keeps its DEBUG level, because it calls the helper's DEBUG-only variant
  `setAttributeQuietly`.
- `TracingLlmClient.setAttributeSafely` (private, `:210`) delegates to the new helper, so there is one implementation.
- Call sites switched over:
  - `CodeSpanAttributes.Nested.open` and `refused`;
  - `ToolSpans.recordOutcome` and `recordFailure`, which also harden the loops because `ToolCallTracing` delegates to
    them;
  - the bridge's `finally`;
  - `ExecuteCodeTool.annotateSpan`;
  - `CodeSpanAttributes.annotateIteration`;
  - `ToolCallTracing.open` (`:66-68`).
- The loops' ITERATION and TURN span handling in `OrcaAgentExecutor` and `DefaultSubagentExecutor` is **not**
  swept here. It is a different subsystem with its own existing pattern, and the finding is about nested-call spans.
  CHANGELOG states the scope.

**Rejected.**
- *A fail-safe `Span` decorator returned by `startChildSafely`.* It would also cover every other site, but it changes
  the identity of every span object the tracer hands out. `enrich` relies on the span's own context, and tests compare
  span instances. That is a wider change than the finding.
- *Catching `Throwable`.* `Spans` deliberately lets `Error` through. Keep one rule.

## 1.4 A4 — the `_finish` sweep join can add up to 2 s on the `_watch` path

**Today.** `rt` `_finish` (`:156-165`) joins the sweep thread for up to `SWEEP_JOIN_SECONDS = 2.0` before sending `DONE`.
On `_watch`'s output-limit path (`:224`) and on `_violation` (`:209-211`), the program keeps running during that wait.
It can keep writing output, or be pushed past its wall clock into a kill.

**Change.**
- `_finish(status, error=None, join_sweep=True)`.
- `_watch` passes `join_sweep=False` on the output-limit stop.
- `_violation` passes `join_sweep=False`.
- The normal completion path, the script-error path and the watchdog-error path (`main`) keep the join. The program is
  already over on those paths, so the join only delays `DONE` for housekeeping, which is `features.md` A.3's intent.
- The docstring names the two non-joining callers and why: on those paths, an immediate stop matters more than the
  sweep; a cut-off sweep is continued by the next run (§8 of shell.md).

**Test-only knob.** `config.json` gains an optional `sweep_test_delay_seconds`. When it is present, `_sweep` sleeps that
long first. It follows the rules of `sweep_trace` (`features.md` A.3):
- the host writes it, and it is digest-verified;
- the engine's writer never emits it;
- only `runRuntimeDirectly` tests set it.

**Rejected.** *Lowering `SWEEP_JOIN_SECONDS`.* It shortens the join on every path, including the one where the join is
right.

## 1.5 A5 — `gpt-4o-mini` Python still trips on asyncio

**Root cause.** `rt` `_run_program` (`:311-320`) wraps the whole program in `asyncio.run(runner())`, and `runner`
evals the compiled program **inside the running loop**. So:
- a program with no top-level `await` still runs inside a loop, and its own `asyncio.run(main())` raises "cannot be
  called from a running event loop";
- a program that uses `asyncio.gather` without `import asyncio` fails with `NameError`.

The reviewer's run had one program in five fail the first way and two in five fail the second way (`pr2-review-2.md`).

**Change: two runtime fixes plus a matching description update.**
1. **Run the loop only when the program needs one.**
   - Compile as today, with `PyCF_ALLOW_TOP_LEVEL_AWAIT`.
   - Then `result = eval(code, program_globals)` **outside any loop**:
     - a program **without** top-level `await` runs synchronously right there, so its own `asyncio.run(main())`,
       `loop.run_until_complete(...)` etc. work;
     - a program **with** top-level `await` returns a coroutine, which is run with `asyncio.run(result)`.
   - `call_tool` only uses `asyncio.sleep` on whatever loop is current, so both shapes work.
   - A program that mixes top-level `await` with `asyncio.run` still gets Python's own error. That program is wrong in
     plain Python as well.
2. **Pre-import `asyncio` and `json`.**
   - `program_globals` gains `"asyncio": asyncio, "json": json`, next to `ToolError` and `call_tool`.
   - Reserved names win over a tool function of the same name; that is already the rule (`:576`,
     `name not in program_globals`).
   - **And `ToolApiRenderer.renderPython` must agree.** A tool whose name is a reserved global (`asyncio`, `json`,
     `ToolError`, `call_tool`) or a Python keyword is rendered in the `call_tool("name", args)` form. Without this, the
     description would advertise a function the program cannot call.
   - A new constant `ToolApiRenderer.PYTHON_RESERVED` and a test pin this. The constant's javadoc names `rt`'s
     `program_globals` as the other half; a test reads `rt` and asserts that both lists match. The comparison covers the
     **non-dunder** keys of `program_globals` (`ToolError`, `call_tool`, `asyncio`, `json`). `__name__` and
     `__builtins__` are covered by a rule instead: the renderer routes every name that starts with `__` through
     `call_tool`.
3. **Description** (`ExecuteCodeDescription.java:42-52`, model-visible). Replace "use top-level await, and never call
   asyncio.run()" with: "Use top-level await (a program without top-level await may run its own asyncio.run(main())
   instead). asyncio and json are already imported." Keep "do not import them" for the tool functions.

**Verification.** Re-run the real-model harness after this PR (§1.13). The success criterion is in §1.13.

**Rejected.**
- *Only make the description stronger.* TC-27 already tried wording, and `gpt-4o-mini` ignored it in five of eleven
  programs. A runtime that accepts both idioms removes the failure class rather than arguing with the model.
- *Intercept `asyncio.run` with a shim that runs inside the current loop.* That monkey-patches a stdlib module in the
  program's namespace (surprising), and needs nest_asyncio-style re-entrancy, which is fragile.

## 1.6 A6 — CHANGELOG width; `ANTHROPIC_API_KEY` where the E2E is documented

- **CHANGELOG.** Reflow every line over 120 columns in `## [Unreleased]` (47 lines at `318805f5`). Released sections are
  history and stay as they are. A line made of one unbreakable token (a URL) is left alone.
- **Key names.** Name `ANTHROPIC_API_KEY` as the fallback that `LiveKeys` accepts (`LiveKeys.java:21`):
  - in the CHANGELOG harness bullet (`CHANGELOG.md:50`);
  - in `CONTRIBUTING.md` "Real-model ExecuteCode E2E" (`:188-…`);
  - in **its translation `CONTRIBUTING.ko.md` (`:195-…`), same commit, with `source_commit` updated per CLAUDE.md**.

## 1.7 A7 = TC-29 — host paths and runtime frames in the model-visible `[logs]`

**Today.** `rt` `main` (`:587-589`) calls `traceback.print_exc()` on an uncaught exception, which goes to the captured
stderr, then to `[logs]`. The traceback includes:
- `rt`'s own frames (`_run_program`, `runner`, `call_tool`, `_await_reply`), with the run directory's host path
  (`/var/folders/…/aimon-run-…/aimon_ptc.py`);
- stdlib frames with the interpreter's install path (the user's pyenv).

Asyncio's "Task exception was never retrieved" log and a thread's unhandled exception print the same way.

**Change: the runtime prints only the program's own frames.**
- New helper `_print_program_exception(exc)` in `rt`:
  - It walks the exception chain: `__cause__` / `__context__`, honouring `__suppress_context__`, with depth capped
    at 5.
  - For each exception it prints `Traceback (most recent call last):`, then only the frames whose `filename ==
    "<program>"` (via `traceback.extract_tb` + `StackSummary.from_list` + `format`), then
    `traceback.format_exception_only`.
  - When no `<program>` frame is left, for example an error raised inside a tool function the program awaited, the
    exception line alone is printed. It is preceded by the program frame that awaited it, which is still in the stack.
- **Source lines.** `main` registers the program source in `linecache` under `<program>` before running. The kept frames
  then show the offending code line, which today shows nothing for `<program>`.
- **The three printers use it.**
  - The `except BaseException` branch in `main`.
  - `threading.excepthook`, set in `main`.
  - `sys.unraisablehook`, set in `main` (review-2). It covers "Exception ignored in: <coroutine …>" and failing
    `__del__`. It prints the hook's `err_msg` and object repr, then the exception through `_print_program_exception`.
  - A `logging.Formatter` subclass whose `formatException` uses the helper. It is installed on a stderr handler for
    the `asyncio` logger, with `propagate = False`. This covers "Task exception was never retrieved" whichever loop
    raised it — ours, or the program's own `asyncio.run` after 1.5.
  - `warnings.showwarning`, replaced in `main` (review-1). The never-awaited-coroutine `RuntimeWarning` is a common
    weak-model mistake (`Read({...})` without `await`), and Python can attribute it to a runtime frame or to `sys:1`.
    The replacement prints `<category>: <message>`, plus ` (<program>:N)` only when the warning's filename is
    `<program>`. Otherwise no location is printed, so no host path or runtime file name appears.
- **Out of scope here:**
  - `aimon_ptc: <note>` lines, which come from the runtime and carry no paths;
  - the program's own `print(..., file=sys.stderr)`, which is the program's data;
  - the shell wrapper's stderr tail (`ShellProgramRun.stderrTail`), which reaches only the host log and the
    no-`DONE` error path, not `[logs]`. The builder confirms that last point at `ShellProgramRun.java:473`; if it does
    reach the model, it is noted as a departure and not fixed here.

**Rejected.**
- *A Java-side path redactor in `ExecuteCodeObservation.appendSideChannels`.* `[logs]` is arbitrary program stderr. A
  program may print paths on purpose (a file listing), and a regex cannot tell a runtime frame from data. Fix the source
  that adds the noise.
- *Drop tracebacks entirely and keep only the exception line.* The `<program>` frame's line number and code line are
  what the model needs to fix its program.

## 1.8 A8 = TC-28 — a script error whose last line is the caret

**Today.** `ExecuteCodeObservation.failure` puts only `lastLine(errorMessage)` after `ExecuteCode script error:`
(`ExecuteCodeObservation.java:69-70`, `lastLine` `:162-166`). A GraalJS `PolyglotException` for a syntax error has a
multi-line message:
1. the message line, `SyntaxError: <program>:L:C Expected …`;
2. the source line;
3. the caret line.

So the model sees `ExecuteCode script error:  ^`.

**Change (core, engine-neutral).** Replace `lastLine` with `errorLine(String message)`, returning a header plus an
optional excerpt:

```text
lines = message.strip().split("\n")
if lines.size >= 3 and lines.last matches ^\s*\^[\^~\s]*$:      # a caret line
    header  = lines[0].strip()                                   # the message line
    source  = lines[size-2]; caret = lines.last
    excerpt = (source.length <= 200) ? source + "\n" + caret : none   # keep relative alignment; drop for minified code
else:
    header = last line, as today
```

- The observation becomes `ExecuteCode script error: <header>`, followed by `\n` and the excerpt, each line indented by
  the same two spaces so the caret stays aligned. It does not show up when there is no excerpt.
- All other statuses and single-line messages are unchanged.
- **Engine side (`aimon-graaljs`) stays unchanged.** The message GraalJS builds is already right; only the core's choice
  of line was wrong. A graaljs test pins the shape the core relies on (§1.12).

**Rejected.**
- *Ship the whole message.* Engines may put long or multi-part text there (a host exception's message), and the
  last-line rule exists to keep that out.
- *Rewrite the message in the GraalJS engine.* It fixes one engine and leaves the rule wrong for the next.

## 1.9 A9 — end-to-end grandchild-fork test (fork → fork → `ExecuteCode` ceiling)

New test class `coreT/agent/impl/orca/NestedForkProgrammaticCeilingE2EIntegrationTest`, built on the fixtures of
`SlashSkillProgrammaticCeilingE2EIntegrationTest`: a scripted LLM, a real `OrcaAgentExecutor`, real
`DefaultSubagentExecutionManager`, and a scripted JavaScript engine that records the callable set it was given.
- The main agent:
  - `programmatic-tools [Read, Grep]`;
  - allowed `[Task, Read, Grep, ExecuteCode]`;
  - HOST isolation, and `programmatic-engine: javascript` so an engine is picked (`features.md` §2.1).
- Subagent `outer`: allowed `[Task, Read, Grep, ExecuteCode]`. Subagent `inner`: `programmatic-tools [Read, Grep]`.
- The run drives `main → Task(outer) → Task(inner) → ExecuteCode`.

Cases:
1. **Control.** `outer` declares `[Read, Grep]`. `inner` is offered `ExecuteCode` with `{Read, Grep}`.
2. **Narrowed by the middle fork.** `outer` declares `[Read]`. `inner` is offered `ExecuteCode` with `{Read}` only, and
   a program calling `Grep` is refused "not callable".
3. **Middle fork without PTC.** `outer` declares nothing. `inner` is not offered `ExecuteCode`, and the fork
   iteration span carries `ptc.unavailable_reason`.

**Depth.** `DedicatedThreadConfig.DEFAULT_MAX_FORK_DEPTH = 1` concerns dedicated threads, not nesting. The builder
confirms that a depth-2 fork is allowed by the default manager configuration, and sets the depth explicitly in the test
if it is not.

## 1.10 A10 — `CommentReflowTest` misses longer stranded wraps

**Today's predicate** (`coreT/architecture/CommentReflowTest.java:179-215`) flags a continuation line only when:
- it has **≤ 2 tokens**;
- the next line continues the same paragraph;
- line + 1 + the next line's first token ≤ 120.

It misses the formatter's other artefact. When an author's line was more than one or two words over 120, the formatter
leaves a **medium-length remainder** line that the next line could have filled. An example is
`ToolCallDispatcher.java:15-17` ("…is" / "fixed by the binding; what varies per call is an" / "argument.").

**Generalized predicate** (the "form B" below is new; form A is today's):

```text
line i is an orphan when it is a prose continuation line (as today: not <pre>, not a tag/element/list/table start),
the next line i+1 continues the same paragraph, and len(line i) + 1 + len(unit(i+1)) <= 120, and EITHER
  (A) tokens(line i) <= 2                                             -- today's rule, unchanged
  (B) len(line i) <= 100                                              -- clearly under-filled (>= 20 columns spare)
      and line i-1 is in the same paragraph
      and len(line i-1) + 1 + len(unit(i)) > 120                      -- the line before was broken by force
```

- `unit(j)` is the first **unbreakable unit** of line j's text: an inline tag `{@…}`, with any leading punctuation such
  as `(` and the trailing punctuation glued to it, or else the first whitespace token. Today's `tokens(next)[0]` splits
  `{@link Foo}` at the space and over-reports joinable lines. Form A switches to `unit` as well.
- **Calibrated on the repo at `318805f5`** with a throwaway scan of all `modules/*/src/*/java`:
  - form A: 546 hits;
  - form B without the `≤ 100` and forced-break conditions: 2 374 hits, mostly hand-wrapped prose at 110–116 columns,
    which is false;
  - with both conditions: 212 hits, which sampled as genuine artefacts ("a file whose current stamp", "is left of it on
    every way out of the read. Only at the loop's" / "own").

  The thresholds are constants with a javadoc that cites this calibration.
- PR 1 applies the generalized predicate **to today's scope only** and fixes what it reports there. The PTC trees
  contributed few hits; the builder fixes all of them, or adds a reasoned `ALLOWED` entry.
- **Also fixed in the same file:** the class javadoc's own broken block (`CommentReflowTest.java:44-51`). An
  `<h2>Inputs</h2>` and a `<p>` sit at column 0 without the ` * ` prefix, and one line runs past 120.
- The `predicate()` test gains form-B cases:
  - the `ToolCallDispatcher` shape (flagged);
  - a hand-wrapped 110-column paragraph (not flagged);
  - a `{@link …}` unit that does not fit (not flagged);
  - a forced break followed by a full line (not flagged).

**Rejected.** *Drop the token limit and flag every joinable line (pure greedy-fill).* That gives 2 374+ false
positives; authors hand-wrap.

## 1.11 TC-22 — tool-context key for the nested-span payload policy

**Today.**
- Nested TOOL spans always use `TracePayloadPolicy.summaryOnly()` (`DefaultToolCallBridge.java:251`).
- Direct TOOL spans follow the executor's policy (`OrcaAgentExecutor.tracePayloadPolicy`, `:416`;
  `RoutineExecutor.tracePayloadPolicy`, `:235`).
- So a deployment that turned content capture on sees no content for code-path calls only.

**Change.**
- New key `ToolContextKeys.TRACE_PAYLOAD_POLICY`: `ToolContextKey.writeOnce("tracePayloadPolicy",
  TracePayloadPolicy.class)`, declared beside `TRACER`.
- **Bound wherever `TRACER` is bound, with that executor's policy:**
  - `OrcaAgentExecutor.resolveEffectiveToolContext` (`:2615-2616`);
  - the command context (`:2397`);
  - `RoutineExecutor` (`:677`);
  - `DefaultSubagentExecutor.IterationTrace` (`:2312`), with the fork executor's policy if it has one, otherwise left
    unbound. The builder checks which.
- The bridge reads `context.get(TRACE_PAYLOAD_POLICY).orElse(TracePayloadPolicy.summaryOnly())`.
- Absent means summary only, which is today's behaviour. So an embedder that binds only `TRACER` sees no change.

**Decision: one knob, not a separate nested-only policy.**
- An operator who enabled content capture has already accepted the same tools' results in traces on the direct path.
  Nested results are results of those same tools.
- Bulk is bounded by `policy.truncate`, the same bound as on direct spans.
- A separate "nested only" policy would be a second setting with no requester. Listed as OQ-3.

CHANGELOG: with capture on, nested spans now carry content.

## 1.12 TC-20 — the reply-drain bound: measured, then decided

**What the bound bounds** (from the code, not the register's wording).
- `ShellProgramRun.close()` (`:387-418`) sets `closed`, then waits while `inFlight > 0`.
- `writeReply` (`:295-326`) increments `inFlight` around **one** `files.writeNew` attempt. It re-checks `closed` before
  each retry, and the gate is released between attempts.
- So after `close()` no attempt starts. The wait is bounded by the **slowest of the `writeNew` calls already in
  progress**, not by the three attempts or their sleeps. Concurrent replies write in parallel, so `inFlight` can be
  greater than 1, but each write contributes a single attempt (review-1).
- The right bound is therefore a generous upper bound on one reply write on the slowest supported backend. Past that
  bound, a write is stalled, not slow.

**Measurement (in this PR, recorded in the register's closure and in the committed design's departure section).**
- `close()` returns the waited `Duration` (package-private change, today `void`). It is also logged at DEBUG as today.
- New gated test `coreT/tools/code/shell/ShellReplyDrainMeasurementTest`, `@EnabledIfEnvironmentVariable(named =
  "AIMON_MEASURE", matches = "1")`. An environment variable reaches the forked test JVM without build changes. The test
  never runs in `test`, `checkAll` or CI.
- It runs 200 programs on `LocalShell`. Each program fires 8 tool calls without awaiting them and exits at once. The
  tools answer after a random 0–20 ms delay, so replies are being written while the run closes.
- It runs the same 200 programs again with a `RuntimeFileSystem` wrapper that adds latency to `writeNew`, to model a
  remote sandbox: a fixed 50 ms, a fixed 250 ms, and a heavy-tailed one (p99 ≈ 1 s).
- It records two distributions: `close()` waits, and per-`writeNew` latency. It prints p50, p99 and max for each.
- It asserts the structural claim: **every `close()` wait is ≤ the slowest single write observed in that series + 50
  ms**. That proves the "one attempt per in-flight write, in parallel" reading.

**Decision: `REPLY_DRAIN = 2 s`** (from 5 s), on these grounds:
- A missed drain costs a WARN and one file in a deleted run directory, which the next sweep removes.
- An over-long drain costs every `ExecuteCode` result on a stalled sandbox that many seconds, in front of the user.
- 2 s is ≥ 8× a 250 ms remote write, and about 2× the heavy-tailed p99.
- **The measurement gates the value.** If the local series' p99 write exceeds 100 ms, or any simulated series shows a
  `close()` wait above one write, the bound stays at 5 s and the departure section says why.
- The exact numbers go into the register's "어떻게 닫았나" and the design's departure section.
- The WARN text gains the in-flight count. The javadoc of `REPLY_DRAIN` cites the measurement and the asymmetry above.

**Not measurable here:** the real OpenSandbox write latency, because `aimon-sandbox` is a separate repository and needs
a running sandbox. The closure says so. The register's existing trigger — the WARN seen in operation — is restated as
the reason to revisit, in the closure text and not as a new item. This is OQ-4.

**Rejected.**
- *Make the bound configurable.* It is a new setting surface for a housekeeping wait that has one sane value per order
  of magnitude.
- *Derive it from `maxWallClock`.* An unrelated quantity.

## 1.13 PR 1 — real-model E2E (after items 5, 7, 8)

`OPENAI_KEY_FILE=~/.openai-key ./gradlew :aimon-cli:llmTest`, for `gpt-4o` and `gpt-4o-mini`, JavaScript and Python. That
is one run per model. A second `gpt-4o-mini` Python run is allowed only if the first one is ambiguous.

- **Record:** per program, the calls, the first error class, `asyncio.run`, `NameError`, and whether any `[logs]` has a
  path outside `<program>` frames. Use the existing `LIVE-E2E` line plus a grep of the printed results.
- **Success** for the PR:
  - Mechanical claims unchanged.
  - No `[logs]` in any result contains `aimon_ptc.py` or an absolute interpreter path.
  - No program fails on `asyncio.run` or on a missing `asyncio` / `json` import.
- **`gpt-4o-mini` Python reaching `COMPLETED` with the right answer is hoped for, not required.** It is a weak-model
  signal (OQ-B2), and the result is recorded either way.
- The key is never printed, logged or copied. The harness already checks this in memory. At the end there must be no
  `aimon_ptc.py` process left (`pgrep -f aimon_ptc.py` is empty).

## 1.14 PR 1 — changes by file

| File | Change |
|---|---|
| `core/tools/code/ExecuteCodeLanguageNotice.java` | 1.1: `isError` skip; `viewResults` / `loggedResults` carry `ToolUseResult`; javadoc residual |
| `core/agent/impl/orca/OrcaAgentExecutor.java` | 1.2 javadoc; 1.11 bind `TRACE_PAYLOAD_POLICY` (two sites) |
| `core/tracing/Spans.java` | 1.3: five `*Safely` helpers |
| `core/tracing/impl/TracingLlmClient.java` | 1.3: delegate `setAttributeSafely` |
| `core/tools/ToolSpans.java`, `core/tools/code/CodeSpanAttributes.java`, `core/tools/code/DefaultToolCallBridge.java`, `core/tools/code/ExecuteCodeTool.java`, `core/toolinvocation/ToolCallTracing.java` | 1.3 guarded span calls; 1.11 the bridge reads the policy |
| `rt` (`aimon_ptc.py`) | 1.4 `join_sweep`, `sweep_test_delay_seconds`; 1.5 loop-only-when-needed, `asyncio` / `json` globals; 1.7 program-only tracebacks, linecache, excepthook, asyncio logger |
| `core/tools/code/ToolApiRenderer.java` | 1.5 `PYTHON_RESERVED`; reserved / keyword names via `call_tool` |
| `core/tools/code/ExecuteCodeDescription.java` | 1.5 Python wording |
| `core/tools/code/ExecuteCodeObservation.java` | 1.8 `errorLine` + excerpt |
| `core/tools/ToolContextKeys.java`, `core/agent/tool/ToolContextKey.java` | 1.11 `TRACE_PAYLOAD_POLICY`, also added to `BUILT_IN_WRITE_ONCE_NAMES` (`ToolContextKeysWriteOnceTest`) |
| `core/scheduling/RoutineExecutor.java`, `core/subagent/execution/DefaultSubagentExecutor.java` | 1.11 bind the key |
| `core/tools/code/shell/ShellProgramRun.java` | 1.12 `REPLY_DRAIN` (decided value), `close()` returns `Duration`, WARN count, javadoc |
| `coreT/architecture/CommentReflowTest.java` | 1.10 predicate, `unit()`, its own javadoc, `MARKDOWN` += `ptc-leftovers-core` |
| PTC sources reported by the new predicate | reflow |
| tests (§1.15) | new / extended |
| `CHANGELOG.md` | 1.6 reflow of `[Unreleased]`; entries for this PR, breaking/behaviour notes (breaking: the `ExecuteCodeLanguageNotice.check` / `loggedResults` signatures; nested span content with capture on; observation error excerpt; Python runtime runs a loop only for top-level `await`; `asyncio` / `json` pre-imported; `REPLY_DRAIN`) |
| `CONTRIBUTING.md` + `CONTRIBUTING.ko.md` | 1.6 `ANTHROPIC_API_KEY` (both, same commit, `source_commit`) |
| `ptc.md` | §6.3 Python description text; §12.1 error-line rule with excerpt; §12.2 nested payload key; §21 rows for TC-22 / TC-28 / TC-29 |
| `shell.md` | runtime: loop-only-when-needed, pre-imports, program-only tracebacks, `join_sweep`, `REPLY_DRAIN` value |
| `docs/design/tool/ptc-leftovers-core.md` | **new**: this approved design |
| `register` | close TC-20, TC-22, TC-28, TC-29 (format §1.17); title counts |
| `docs/backlog/README.md` | the register's index row counts |

## 1.15 PR 1 — failure modes

| Failure | Handling |
|---|---|
| A refused call's view body was elided, and the log copy is gone too | The view still carries `isError`, so it is skipped. If the result was dropped from view and the log has no copy: unreadable, as today (bounded, one line) |
| A tracer whose span methods throw | Every PTC span op is `*Safely`. One WARN per failure; the call is unaffected. `Error` propagates (unchanged rule) |
| The sweep is very slow and the program hits the output limit | `_watch` no longer joins. `DONE` goes out at once and the group is killed; the sweep is cut off and continued next run |
| A program mixes top-level `await` and `asyncio.run` | Python's own `RuntimeError`, with a program-only traceback |
| A tool named `json` / `asyncio` / a keyword | Rendered as `call_tool("json", …)`; the global stays the module |
| An exception raised in `rt` code (no `<program>` frame) | The exception line only. The model still sees the type and message |
| A syntax-error excerpt over 200 chars (minified) | Header only, no misaligned caret |
| The measurement contradicts 2 s | The bound stays 5 s; recorded in the departure section (§1.12) |
| `TRACE_PAYLOAD_POLICY` unbound (embedder) | Summary only, which is today's behaviour |

## 1.16 PR 1 — test strategy

- **`ExecuteCodeLanguageNoticeTest`.** Add cases:
  - a fresh view with one refused `ExecuteCode` (error, no summary) → empty;
  - the same refused call followed by a known Python program, with JavaScript offered → "now runs JavaScript (was
    Python)";
  - a refused call whose view body is elided, with the log body missing → skipped;
  - a non-error, marker-less result (hook rewrite) → conservative notice, as today;
  - the permission-deny shape produced by the real pipeline (`ToolUseResult.error`).
- **`OrcaAgentExecutorProgrammaticTest`**, one end-to-end case: a scripted model whose first `ExecuteCode` call is
  denied by a PreTool hook, then calls a direct tool → no `[environment] ExecuteCode now runs` line on the second
  iteration.
- **`SpansTest`.** Each `*Safely` swallows a `RuntimeException` and lets an `Error` through.
- **`DefaultToolCallBridgeTracingTest`.** Use a `Span` whose `setAttribute`, `setOutputs`, `error` and `close` all
  throw. Nested calls still succeed, the program's result is intact, and a failing dispatch's own exception (not the
  close's) propagates.
- **`ShellRuntimeSweepTest` / `ShellEngineProtocolTest`.** With `sweep_test_delay_seconds: 5`:
  - an output-limit program gets `DONE` in < 1.5 s;
  - a normally completing program still waits for the join (`DONE` after ≥ 2 s, < 3 s).
- **Runtime tests (shell engine, `python3` required, existing skip rule):**
  - a program with no top-level `await` doing `asyncio.run(main())` with two `asyncio.gather`ed tool calls →
    completed;
  - a program using `json.dumps` and `asyncio.gather` with no imports → completed;
  - a program raising inside a nested function → `[logs]` contains `File "<program>", line N`, the code line and the
    exception line, and contains neither `aimon_ptc` nor any absolute path (regex `(^|\s)/[^\s]+\.py`);
  - an unawaited task that raises → the asyncio log is program-only;
  - a never-awaited tool call (`Read({...})` without `await`) → the `RuntimeWarning` line contains neither
    `aimon_ptc` nor an absolute path;
  - a thread that raises → program-only;
  - an unraisable exception (an object whose `__del__` raises) → program-only.
- **`ToolApiRendererTest`.** `json`, `asyncio`, `ToolError`, `call_tool` and `class` render via `call_tool`. A test
  reads `rt` and asserts that `PYTHON_RESERVED` matches the `program_globals` literal keys.
- **`ExecuteCodeToolTest#descriptionSaysWhereFunctionsComeFrom`.** Updated to the new wording, including "already
  imported".
- **`ExecuteCodeObservationTest`.** The three-line caret message gives the header plus the aligned excerpt; a long source
  line gives the header only; a single-line message is unchanged; a two-line message whose last line is not a caret
  keeps the last-line rule.
- **`aimon-graaljs` `GraalJsCodeExecutionEngineTest`.** A real syntax error's `errorMessage` has ≥ 3 lines and ends with
  a caret line. This pins the input shape the core rule depends on. A second test goes through `ExecuteCodeTool` with
  the GraalJS engine, in `aimon-graaljs`'s tests where both are on the classpath, and asserts that the observation
  contains `SyntaxError` and `Expected`.
- **`NestedForkProgrammaticCeilingE2EIntegrationTest`** (§1.9): three cases.
- **`CommentReflowTest#predicate`.** The form-B cases (§1.10).
- **`ShellProgramRunTest`.** `close()` returns the waited duration: zero with nothing in flight; ≈ the write's remaining
  time with one slow write in flight; capped at `REPLY_DRAIN` with a stalled write, using a `Control` with a short drain
  as the existing tests do.
- **`ShellReplyDrainMeasurementTest`** (gated; run once by the builder, numbers recorded).
- **TC-22 tests** (`DefaultToolCallBridgeTracingTest`):
  - `TRACE_PAYLOAD_POLICY` = capture → the nested span's outputs contain `content`, truncated;
  - absent → no `content`.

  An `OrcaAgentExecutor` tracing test asserts that the key is bound with the executor's policy.
- **Whole-build gates:** `./gradlew checkAll`, `./gradlew integrationTest`, `python3 scripts/check-doc-links.py`,
  `check-translation-structure.py`, `check-translation-staleness.py`, `check-backlog-registers.py`; then §1.13.

## 1.17 Register closure format (all PRs)

Follow TC-15 and TC-27:
- the heading state becomes `**닫힘** *(YYYY-MM-DD)*`;
- the body keeps 무엇을 / 왜 / 어디 / 언제 다시 볼까 as they were;
- a new **어떻게 닫았나.** paragraph says what was done, names the test, and — per README rules 2 and 3 — says if the
  premise or the severity turned out different.

Known corrections to write:
- **TC-20.** The bound covers one attempt of each write already in flight (they run in parallel), not "응답 쓰기
  몇 번".
- **TC-28.** The fix is core-side; the engine message was already right.

The register title (`등록 항목 29건 (열림 N · 닫힘 M)`) and the README index row are updated in the same commit.
`check-backlog-registers.py` must pass.

---

# PR 2 — slash-skill spans, the ceilings, and the TC-24 decision

Closes: backlog TC-23, TC-24 (decided "no"), TC-25, TC-26.

## 2.1 TC-23 — ITERATION and TOOL spans in the slash-skill loop

**Today.**
- `LlmSkillExecutor.execute` (`core/skill/execution/llm/LlmSkillExecutor.java:207-355`) opens no spans.
- Its LLM calls use the four-argument `sendMessage` with no metadata (`:446-447`), so no LLM span either.
- Its tool calls go through `dispatch` (`:569-588`) without TOOL spans.
- The command context carries `TRACER` and `LLM_CALL_METADATA_KEY = commandTrace.metadata()`
  (`OrcaAgentExecutor.java:2397,2407`), which points at the COMMAND span. That is why only a skill's `ExecuteCode`
  nested calls appear today, parented to the COMMAND span.

**Change.** A private nested class `SkillIterationTrace` in `LlmSkillExecutor`, modelled on
`DefaultSubagentExecutor.IterationTrace`:
- **Open.** Per iteration: `Spans.startChildOf(tracer, commandMetadata, SpanType.ITERATION, "iteration#" + n, null)`.
  - `tracer` comes from `context.getToolContext().get(TRACER)`; `commandMetadata` from `LLM_CALL_METADATA_KEY`.
  - No tracer or no parent means no span and zero allocation, as in the fork.
- **Attributes:**
  - `iteration`;
  - `invokerType=MAIN_AGENT`: a slash skill runs inside the main turn. No new `InvokerType` value; see the rejection
    below;
  - `skill=<name>`;
  - `ptc.unavailable_reason` via `CodeSpanAttributes.annotateIteration(span, offerContext)`. The offer context is
    computed once per execution (`:257`), so the same value is annotated on each iteration.
- **LLM call.**
  - `sendAndCharge` switches to the metadata overload `sendMessage(String, List, List, LlmModel, LlmCallMetadata)`.
  - The metadata passed is `Spans.traceCarrier(iterationMetadata).orElse(LlmCallMetadata.empty())`: span position
    only, so usage attribution does not change. Today the call carries no attribution; carrying the turn's attribution
    would silently change metering.
  - An LLM span then appears under the iteration when the skill's `LlmClient` is the tracing-wrapped one. The builder
    checks the CLI and bootstrap wiring and records whether it is.
  - **Metering** (review-1). `MeteringLlmClient` overrides the five-argument `sendMessage` and records usage with the
    metadata it is given (`llm/usage/MeteringLlmClient.java:122-129`); the four-argument path recorded with
    `LlmCallMetadata.empty()`. Two consequences:
    - The builder confirms that the two reserved span tags (`SpanContext.TAG_TRACE_ID` / `TAG_PARENT_SPAN_ID`) are not
      read as usage dimensions.
    - `LlmSkillExecutorTracingTest` pins the recorded usage record's attribution: identical to the four-argument
      path's, apart from those tags.

    If the tags do leak into usage dimensions, the call passes `LlmCallMetadata.empty()` to metering instead, and the
    departure section records it.
- **TOOL spans.**
  - In `dispatch`, for each tool use: `ToolCallTracing.open(tracer, iterationToolContext, toolUse, n,
    InvokerType.MAIN_AGENT)`, plus `skill=<name>`.
  - Then `ToolCallTracing.contextUnder(span, ctx)`, so a fork or an `ExecuteCode` the tool starts nests under the TOOL
    span, and a `TOOL_SPAN` binding lets `ExecuteCode` set `ptc.language` / `ptc.tool_scope`.
  - Then `recordOutcome(span, result, payloadPolicy)`, with the policy read from `TRACE_PAYLOAD_POLICY` (PR 1), default
    summary only.
  - The `finally` uses `closeSafely`.
  - `skill.execution` → `toolinvocation` is already a dependency (`ToolDispatchResults`, `:57`). The builder checks the
    ArchUnit rules.
- **Iteration context.** The per-iteration tool context puts `LLM_CALL_METADATA_KEY` = the iteration's metadata, so
  that TOOL spans parent to the ITERATION span, not to the COMMAND span.
- **Ending.**
  - Normal end → close.
  - Max-iterations, stalled or quota failure → `errorSafely(message)` then close.
  - An exception in the loop (`catch (Exception e)`, `:351`) → `errorSafely(e)`.
  - Every exit path closes the current span, through a `try/finally` around each iteration body.

**Rejected.**
- *A new `InvokerType.SKILL`.* `InvokerType` is an attribution enum read by usage and tracing consumers. A new value
  ripples into every `switch` and into stored attribution, while the loop really is the main agent's turn. The `skill`
  attribute carries the distinction.
- *Pass the full turn metadata to the LLM call.* It changes cost attribution, as above.

**A-DV2 is retired.** In `ptc.md` §21, A-DV2 (nested calls parent to the COMMAND span) becomes "parent to the skill's
TOOL span for `ExecuteCode`".

## 2.2 TC-25 — a background `Workflow` run inherits the launching skill or fork ceiling

**Today.** `WorkflowTool.runInBackground` (`core/tools/workflow/WorkflowTool.java:285-316`) and
`GraalJsWorkflowTool` (`modules/aimon-graaljs/…/GraalJsWorkflowTool.java:190-215`) submit work to the agent-scoped
runner. Its base launch context carries the **agent's** allow-list and programmatic settings
(`OrcaAgentRuntimeFactory.java:1169-1190`). The only thing a submission passes is the `QuotaGuard`
(`WorkflowRunner.runInBackground(script, runId, QuotaGuard)`, `core/workflow/WorkflowRunner.java:119`).

**Change: the submission carries the submitter's ceiling; the runner narrows its base by it.**

1. New immutable value `core/workflow/BackgroundSubmission`, a class with a builder (CLAUDE.md: prefer class over
   record). Fields:
   - `quotaGuard` (nullable → the base's);
   - `callerAllowedTools` (`Optional<List<AllowedTool>>`; empty → the base's);
   - `callerProgrammatic` (`Optional<ProgrammaticToolSettings>`; empty → the base's);
   - `static BackgroundSubmission of(ToolContext)` reads `QUOTA_GUARD`, `CallerAllowedTools.of`,
     `CallerProgrammatic.of`. Both tools call it.

   **ArchUnit.** `PackageDependencyArchitectureTest.workflowMayDependOnlyOnSubagentSpiTypes` (`:294-323`) is a type
   allow-list, and `agent.tool` is not on it.
   - Add `AllowedTool`, `AllowedTools` and `ProgrammaticToolSettings` to that list, with a justification comment in the
     style of the existing "Usage-quota addition": "Background-ceiling addition (TC-25): a background run is narrowed by
     its submitter's allow-list and programmatic settings."
   - The `of(ToolContext)` factory needs `tools.*` accessors, so it does **not** live in `workflow`. It goes in a small
     helper, `core/tools/workflow/BackgroundSubmissions.of(ToolContext)`, which both `WorkflowTool` and
     `GraalJsWorkflowTool` call. `BackgroundSubmission` itself keeps only the value and its builder.
2. `WorkflowRunner`:
   - **replace** `runInBackground(script, runId, QuotaGuard)` with `runInBackground(script, runId,
     BackgroundSubmission)`. The default delegates to the two-argument form ("right only for a runner that starts no
     subagent", as today);
   - **breaking** (allowed): recorded in CHANGELOG;
   - the two in-tree callers and `DefaultWorkflowRunner` change; the old overload is removed.
3. `DefaultWorkflowRunner.runInBackground` narrows **before the claim loop**, i.e. before
   `runStore.putIfAbsentOrTerminal` (`:230-233`). Throwing after the claim would leave a non-terminal store entry with
   no registration, and every later submit of that id would fail with "active but is not hosted on this node"
   (review-1). The narrowed values are then handed to `dispatch` (`:244-252`), which builds the per-run launch context:
   - `callerAllowedTools` = `AllowedTools.intersect(base, submitted)`. When the two are disjoint, the submission is
     refused with `IllegalArgumentException("background run refused: the caller's allow-list and the agent's share no
     tool")`. Both tools already turn exceptions into a tool error;
   - `callerProgrammatic` = `submitted.narrowedBy(base)`. Monotone, so `none` stays `none`;
   - `quotaGuard`, as today.
4. **Idempotency must not join across ceilings.**
   - The run id is derived from the request (`RunId.from("workflow", runDiscriminator(...))`). An identical request
     from a narrower skill would join an in-flight agent-wide run and get its handle, a quiet way to observe wider work
     it did not start.
   - Both tools therefore append `BackgroundSubmission.ceilingDigest()` to the discriminator: a stable SHA-256 prefix
     of the canonical allow-list and programmatic settings.
   - Same request and same ceiling still join idempotently.
   - The digest is computed from the *submitted* values before narrowing, so it is deterministic per caller.

**Effect today.**
- The allow-list half has effect now.
- The programmatic half has none until workflow subagents carry programmatic settings (`InlineSubagentResolver` does not
  copy them; register TC-25 says so). It is wired now so that change cannot reopen the hole.

**Rejected.**
- *Build a per-call background runner* (the register's alternative trigger). A background run must outlive the call, and
  its pool and resume cache are agent-scoped by design (`OrcaAgentRuntimeFactory.java:1162-1168`).
- *Refuse background mode inside skills and forks.* It removes a feature to avoid wiring a value.

## 2.3 TC-26 — model-invoked inline skills enforce their own allow-list and programmatic settings

**Today.**
- `SkillTool`'s inline branch (`core/tools/skill/SkillTool.java:407-411`) returns the rendered instructions. Afterwards,
  the main turn's tool calls and the forks it spawns are ceilinged by the **agent**.
- The user-typed `/skill` path runs `LlmSkillExecutor`, which narrows to `agent ∩ skill`, with programmatic
  `skill.narrowedBy(caller)`.
- The asymmetry is pinned by `SlashSkillProgrammaticCeilingE2EIntegrationTest` case 2 (`:179-198`).

**Chosen approach: an execution-scoped, monotone ceiling that `SkillTool` narrows and the loops read at each iteration
boundary.**

1. **New type `core/agent/tool/InlineSkillCeiling`.**
   - It lives in the neutral package: both `tools.skill` and the loops already depend on `agent.tool`.
   - It is a thread-safe class holding the execution's current effective `(List<AllowedTool> allowedTools,
     ProgrammaticToolSettings programmatic, List<String> skills, int version)`.
   - `static InlineSkillCeiling startingAt(List<AllowedTool> allowed, ProgrammaticToolSettings programmatic)`: the loop's
     own values.
   - `synchronized Optional<String> narrow(String skill, List<AllowedTool> declared, ProgrammaticToolSettings
     declaredProgrammatic)`:
     - allowed' = `AllowedTools.intersect(current, declared)`. When they are disjoint, it returns the refusal message
       and changes nothing, the same wording as `LlmSkillExecutor.disjointAllowLists`;
     - programmatic' = `declaredProgrammatic.narrowedBy(current)`. A skill without programmatic keys therefore yields
       `none()`, the same as the slash path (B-DV1, OQ-1);
     - `version++`;
     - it never widens.
   - `Snapshot snapshot()`: an immutable view, with `version == 0` meaning untouched.
   - It is not persisted: execution-scoped, like `ToolCallQuota`. A turn ends, and the next turn starts from the
     agent's values.
2. **New key** `ToolContextKeys.INLINE_SKILL_CEILING`, `writeOnce`. It is bound once per execution by:
   - `OrcaAgentExecutor`, per turn, in the tool context built at `:910-962`, starting at the agent's allow-list and
     programmatic settings;
   - `DefaultSubagentExecutor`, per fork execution, starting at the fork's effective scope;
   - `LlmSkillExecutor`, per slash-skill execution, starting at its `allowedTools` / `programmatic` (`:233-235`).
3. **`SkillTool` inline branch.** After policy, staging and rendering succeed, and before returning the instructions:
   - if the key is bound: `ceiling.narrow(skill.getName(), skill.getMetadata().getAllowedTools(),
     skill.getMetadata().getProgrammatic())`;
     - on refusal, the tool returns `ToolResult.error(...)` and the instructions are **not** injected;
     - on success, the formatted result gains one line after "Allowed Tools", for example `For the rest of this turn
       only these tools are available.`, or `ExecuteCode is not available for the rest of this turn.` when
       programmatic dropped to `none` from an enabled value. The model is told rather than left to discover refusals;
   - if the key is absent (an embedder calling `SkillTool` outside the three loops): unchanged behaviour, with a DEBUG
     line.

   A skill whose allow-list is empty and whose programmatic settings equal the current ones narrows nothing. `version`
   is not bumped, so the loops rebuild nothing.
4. **The loops apply the snapshot at the next iteration boundary.**
   - **Main loop** (`OrcaAgentExecutor.java:1860-1890`). When `snapshot.version() > 0`:
     - the offer context's admission becomes `agentAdmits ∧ name ∈ snapshot.allowedTools` (empty = no extra filter),
       and its `programmatic` becomes `snapshot.programmatic()`;
     - **the environment facts are recomputed from the narrowed allow-list** (review-2 blocking).
       `CodeOfferKeys.putEnvironmentFacts(builder, toolContext, allowedTools)` gets `snapshot.allowedTools()` as its
       third argument, in place of `scope.getAgent().getAllowedTools()` (`OrcaAgentExecutor.java:1869-1874`). That
       list alone decides `BASH_SETTINGS_UNCONSTRAINED` (`CodeOfferKeys.java:101-102`), which is the shell engine's gate
       (`ShellCodeExecutionEngine.availability`, `:176-180`). A skill that narrows `Bash` to a pattern must therefore
       turn the shell engine off for the rest of the turn, as the slash path already does
       (`LlmSkillExecutor.java:548-556`). Otherwise a shell-engine program, which is an unrestricted `python3` process,
       would bypass the skill's `Bash(git status*)`. **This rule applies to every snapshot-driven rebuild: main loop,
       fork loop and slash loop.** When the snapshot's allow-list is empty (the skill restricts nothing), the loop's own
       list is passed, as today;
     - the iteration dispatches through `toolCallDispatcher.narrow(agentRuntime.getToolRegistry(), snapshot.allowedTools(),
       snapshot.programmatic())`, cached per `version`. This dispatcher publishes the narrowed `CALLER_ALLOWED_TOOLS` /
       `CALLER_PROGRAMMATIC` to every call (`ToolCallDispatchers.java:105-109`, `SingleToolInvoker.java:193`), so forks
       spawned later are ceilinged by the skill;
     - `TOOL_CALL_DISPATCHER` is write-once in the turn's tool context, so the iteration's tool context is rebuilt from
       the turn context **minus** that key (the copy pattern `LlmSkillExecutor.dispatch` uses, `:571-580`), with the
       narrowed dispatcher put in;
     - **the builder locates every place the main loop reads the dispatcher.** Streaming eager dispatch and the
       parallel path must use the iteration's dispatcher, not a field.
   - **Fork loop** (`DefaultSubagentExecutor`, `IterationTrace.open` / `offerContextOf`, `:1500-1507`): the same,
     against the fork's scope. That includes `putEnvironmentFacts` receiving the snapshot's allow-list. Today the fork
     passes its effective allow-list, so the Bash fact already follows the fork's ceiling; after the change it follows
     the fork's ceiling narrowed by any inline skill.
   - **Slash-skill loop:** its offer context and dispatcher are fixed per execution today (`:250-257`). They become per
     `version`: rebuilt when the snapshot's version changes. `offerContextOf` receives the snapshot's allow-list, which
     also feeds `putEnvironmentFacts`. `LlmSkillExecutor.dispatch` copies the caller's context key by key, and it skips
     `INLINE_SKILL_CEILING` along with `TOOL_CALL_DISPATCHER` and `TOOL_OFFER_CONTEXT`. The skill's own ceiling is put
     afresh, so a caller context that carries one can never make the write-once `put` throw (review-2).
5. **Timing rule.** A narrowing takes effect from the **next iteration**:
   - Tool calls in the same response as the `Skill` call were chosen before the model read the skill, and are
     dispatched under the ceiling that response was offered (decision 13's freeze).
   - Deterministic under parallel dispatch, and documented in `ptc.md` §7.2.
6. **Scope rule.** Narrowing lasts until the execution ends: the turn, the fork or the slash skill. Several inline skills
   in one execution intersect cumulatively; narrowing only.
7. **A narrowed turn never suspends** (review-1 blocking).
   - **The problem.** An SK-11.4 suspension ends the execution (`handleSuspended` → `CompletionReason.SUSPENDED`,
     `OrcaAgentExecutor.java:1948-1966`). `/approve` then only caches ALLOW and removes the `PendingTurn`
     (`ApproveTurnCommand.java:136-150`). "Resuming" is simply the user's next execution in the same session: nothing
     links it to the suspended one, so it starts with a fresh ceiling. From the user's side, though, it is the same
     turn. Before this change, a suspension after an inline skill had narrowed the turn would therefore drop the
     narrowing while the skill's instructions are still in view.
   - **Rule.** In the main loop's pre-flight block, suspend only when `ceiling.snapshot().version() == 0`. When the
     ceiling has been narrowed, the scan's verdict is ignored, the response is dispatched normally, and the
     approval-requiring `Skill` call takes the existing fail-closed path inside `SkillTool`: decision `ASK`, so
     `ToolResult.error`.
   - **The error message.** `SkillTool`'s `ASK` message gains a variant when the bound ceiling's version is > 0:
     "Skill 'B' requires user approval, which cannot be asked for in a turn an inline skill has narrowed; ask for it
     again in a new turn." The executor logs one INFO line naming the turn and the skills.
   - **Effect.** The turn stays a single execution and stays narrowed to its end. A suspension *before* any narrowing
     (`version == 0`) is unchanged: nothing is committed, and the resumed execution correctly starts from the agent's
     values. If `Skill(A)` and an `ASK` `Skill(B)` arrive in the same response, the scan runs before dispatch,
     `version` is still 0, and the whole response suspends uncommitted, so A has not narrowed anything either.
   - **Forks and slash skills are unaffected.** Only the main loop has a pre-flight scanner.

**Rejected.**
- *Run model-invoked inline skills through `LlmSkillExecutor`'s loop.* That turns "inline" into a nested sub-agent with
  its own scratch conversation, which changes what inline means, adds an LLM round per activation, and duplicates fork
  mode.
- *Enforce at dispatch time against a live ceiling (no iteration boundary).* Calls in the same parallel batch would race
  the narrowing, and the offer the model saw would disagree with what is refused.
- *Narrow only forks (the B.6 case-2 reading) and leave the turn's direct calls alone.* Half a rule. The register says
  allow-list and programmatic are "the same change"; the allow-list governs direct calls.
- *Persist the narrowing across turns.* Inline skills have no end marker. Turn scope is the coarsest boundary the
  runtime can see, and it matches "execution-local" for forks and skills.
- *For the suspend case (item 7): carry the ceiling's snapshot on `PendingTurn` and start the resumed execution from
  it.* Resumption has no link back to the `PendingTurn`, which `/approve` deletes, and the next execution receives no
  turn id. Linking them would need new session-record state: "this session's next execution is a resumption, under
  this ceiling". That is a persisted schema change across the codec and three backends, for a corner case. It would
  also have to decide when such a carried-over ceiling expires (`/deny`, TTL reaping, a user who never resumes).
- *For the suspend case: rebuild the ceiling on resume from the inline `Skill` activations in the transcript.* The
  resumed execution cannot tell a resumption from a new turn: both are just the next user input. So it cannot tell
  which activations belong to "this turn", and rebuilding from all of them would narrow every later turn for good.

## 2.4 TC-24 — subagents usable where the caller has no PTC: decided **no**

**Premises checked against the source** (README rule 4):
- (a) *"PTC 없는 호출자는 아무것도 없음"*: true. `ProgrammaticToolSettings.narrowedBy` returns `NONE` when either side is
  disabled (`core/agent/tool/ProgrammaticToolSettings.java:145-147`).
- (b) *"운영자는 메인 에이전트에도 `programmatic-tools` 를 열어야 한다"*: true, and not only `programmatic-tools`. The
  allow-list ceiling also applies: a fork's allow-list is intersected with its caller's, so the caller's allow-list must
  admit `ExecuteCode` as well (`features.md` §2.1, the ceiling test's fixture).
- (c) The design docs do not argue for widening. `ptc.md` §7.2 and §21 B-DV1 state the narrowing as intentional ("이
  비대칭은 의도다").

**Decision: no widening declaration, neither a subagent `programmatic-tools: inherit`-style key nor a "self-declaration
only" operator mode.** Reasons, recorded in the closure:
1. **The invariant every ceiling rests on is "an agent only narrows."**
   - The allow-list, the side-effect ceiling, the programmatic settings and now TC-25 and TC-26 all assume that what a
     root agent's operator configured bounds everything beneath it.
   - A self-granting child is the one exception that makes every other ceiling conditional on reading every child
     definition.
2. **The operator-level permission the register asks for already exists, and it sits at the root.**
   - Opening `programmatic-tools` (and `ExecuteCode` in the allow-list) on the main agent *is* that permission.
   - A second "self-declaration only" mode would be a parallel knob for the same grant, visible on the child instead of
     the root. That weakens the audit trail: you can no longer read an agent file and know its ceiling.
3. **Subagent definitions come from user layers.** User `agents/*.md` (`OrcaAgentRuntimeFactory`'s composite registry)
   and skill frontmatter can be written by people who are not the operator. A widening key would make those files a
   path to the code-path data reach that §7.1 and `DataReach` gate deliberately.
4. **The trigger never fired.** No `caller-ceiling` WARN has been reported from a real deployment.

**Residual, named in the closure as not registered.**
- "PTC for children only": a root that wants forks to have `ExecuteCode` while its own model is not offered it is not
  expressible today.
- If it is ever needed, the shape that keeps the invariant is a **root-side** setting (for example, a root
  `programmatic-tools` that is published as a ceiling but not offered), not a child-side grant. It is not designed here.
- That is the decision; the register entry closes as **닫힘**, with the reasoning in "어떻게 닫았나" (README rule 4:
  decided and nothing left to do).

## 2.5 PR 2 — changes by file

| File | Change |
|---|---|
| `core/skill/execution/llm/LlmSkillExecutor.java` | 2.1 spans and LLM metadata; 2.3 bind `INLINE_SKILL_CEILING`, per-version offer and dispatcher |
| `core/workflow/BackgroundSubmission.java` | **new** (2.2) |
| `core/workflow/WorkflowRunner.java`, `core/workflow/impl/DefaultWorkflowRunner.java` | 2.2 overload replaced; narrowing; disjoint refusal |
| `core/tools/workflow/WorkflowTool.java`, `modules/aimon-graaljs/…/GraalJsWorkflowTool.java` | 2.2 pass the submission; digest in the discriminator |
| `core/agent/impl/orca/OrcaAgentRuntimeFactory.java` | 2.2 comment at `:1175-1180` updated (the exception is gone) |
| `core/agent/tool/InlineSkillCeiling.java` | **new** (2.3) |
| `core/tools/ToolContextKeys.java`, `core/agent/tool/ToolContextKey.java` | 2.3 `INLINE_SKILL_CEILING`, also added to `BUILT_IN_WRITE_ONCE_NAMES` (`ToolContextKeysWriteOnceTest`) |
| `core/tools/workflow/BackgroundSubmissions.java` | **new** (2.2): the `of(ToolContext)` factory |
| `coreT/architecture/PackageDependencyArchitectureTest.java` | 2.2: allow-list addition with its reason |
| `core/agent/impl/orca/OrcaAgentExecutor.java` (pre-flight block) | 2.3 item 7: no suspension once the ceiling is narrowed |
| `core/tools/skill/SkillTool.java` | 2.3 narrow on inline activation; refusal; the model-facing line; class javadoc |
| `core/agent/impl/orca/OrcaAgentExecutor.java`, `core/subagent/execution/DefaultSubagentExecutor.java` | 2.3 bind the ceiling; apply the snapshot per iteration (offer + narrowed dispatcher) |
| tests (§2.7) | new / changed |
| `CHANGELOG.md` | breaking: `WorkflowRunner.runInBackground(…, BackgroundSubmission)`; behaviour: model-invoked inline skills narrow the rest of the execution; background runs inherit the submitter's ceiling and do not join across ceilings; slash-skill spans |
| `ptc.md` | §7.2: inline skill rule (model-invoked too), timing and scope; §12.2: slash-skill spans; §21: B-DV1's two exceptions removed, A-DV2 updated, rows for TC-23 / TC-25 / TC-26, TC-24 decision referenced |
| `docs/design/tool/tool-call-dispatcher.md` | §6/§7: the iteration-scoped narrowed dispatcher for an active inline skill |
| skill feature docs | the guide that documents `allowed-tools` for skills, plus its `.en.md` twin if any. The builder finds it (e.g. `docs/features/skill/*`) and states that `allowed-tools` now binds model-invoked inline skills for the rest of the turn |
| `docs/design/tool/ptc-leftovers-core.md` | append "after the PR 2 build" |
| `register` + `docs/backlog/README.md` | close TC-23, TC-24, TC-25, TC-26; counts |

## 2.6 PR 2 — failure modes

| Failure | Handling |
|---|---|
| A tracer that throws in the skill loop | `*Safely` everywhere (PR 1 helpers); the loop is unaffected |
| The skill's `LlmClient` is not tracing-wrapped | No LLM span; ITERATION and TOOL spans still appear; recorded as a finding, not fixed here |
| Disjoint background ceiling | Submission refused with a tool error, before any run is claimed |
| An identical background request from a narrower caller | A distinct run id (the digest), so a new run under its own ceiling |
| An inline skill disjoint from the current ceiling | Tool error, no instructions injected, ceiling unchanged |
| An inline skill without programmatic keys in a PTC agent | `ExecuteCode` disappears from the next iteration for the rest of the turn; the model is told in the skill result. **Behaviour change — OQ-1** |
| `Skill` not in the inline skill's allow-list | The model cannot activate another skill in that turn; consistent with "narrow only" |
| The narrowed dispatcher's registry-subset precondition | `narrow` is given the dispatcher's own registry (`agentRuntime.getToolRegistry()`, the one the turn's dispatcher is built with, `OrcaAgentExecutor.java:2459-2462`). `DefaultToolCallDispatcher.narrow` then takes its `registry == toolRegistry` branch, and the narrowed dispatcher executes against exactly the turn's registries. `sessionRegistry` is *not* used: with ToolSearch it is a separate `ToolSearchRegistry` (review-2) |
| An inline skill narrows `Bash` to a pattern in an agent whose shell engine is enabled | The environment facts are recomputed from the snapshot's allow-list, so `BASH_SETTINGS_UNCONSTRAINED` becomes false. From the next iteration `ExecuteCode` is not offered on the shell engine (it falls back to GraalJS only where the existing engine choice already allows that), and `ptc.unavailable_reason` names the Bash constraint |
| `SkillTool` used by an embedder without the key | Old behaviour, plus a DEBUG line |
| An approval-requiring (`ASK`) skill in a turn already narrowed by an inline skill | No suspension (§2.3 item 7). The `Skill` call fails closed with the "ask again in a new turn" message, and the turn stays narrowed to its end. This is a conscious usability cost: no interactive approval mid-way through a narrowed turn |
| A suspension before any narrowing | Unchanged: nothing is committed, and the resumed execution starts from the agent's values, which is correct because nothing had been narrowed |

## 2.7 PR 2 — test strategy

- **`LlmSkillExecutorTracingTest`** (in-memory tracer, scripted LLM with two tool iterations, then an answer). Asserts:
  - the COMMAND → ITERATION#0, ITERATION#1 tree;
  - TOOL spans under each iteration, with `tool.path=direct`, `skill=<name>`, `invokerType=MAIN_AGENT`;
  - an `ExecuteCode` call's nested spans under its TOOL span;
  - `ptc.unavailable_reason` on the iterations when the skill opens PTC but no engine is available;
  - the LLM metadata carries only the span carrier tags;
  - max-iterations ends the last iteration span in error;
  - tracing off opens nothing and allocates nothing (same instance checks as the fork's tests).
- **`DefaultWorkflowRunnerTest`:**
  - a submission with a narrower allow-list → the per-run launch context's `callerAllowedTools` is the intersection;
  - programmatic is narrowed;
  - disjoint → refused, and no run is claimed in the store;
  - the default `WorkflowRunner` method delegates.
- **`WorkflowToolTest` / `GraalJsWorkflowToolTest`:**
  - the same request from two ceilings yields two run ids;
  - the same request from one ceiling yields one (joined).
- **`InlineSkillCeilingTest`:**
  - intersect and narrow semantics, including `none()` propagation;
  - disjoint refusal leaves the state unchanged;
  - idempotent re-narrow does not bump `version`;
  - thread-safety under concurrent `narrow`.
- **`SkillToolTest`:**
  - inline activation narrows a bound ceiling and adds the model-facing line;
  - disjoint → error, no instructions;
  - no key → unchanged.
- **`SlashSkillProgrammaticCeilingE2EIntegrationTest`:**
  - **case 2 flips**: model-called `Skill(probe)` then `Task` → the fork is ceilinged by the skill (not offered
    `ExecuteCode` when probe has no programmatic keys). It is renamed to say so;
  - new case: `Skill(probe)` with `allowed-tools [Read]`, then a direct `Grep` in the next iteration → `Grep` is not
    offered or is refused;
  - new case: `Grep` in the **same** response as `Skill(probe)` → dispatched (the timing rule);
  - new case: a fork-loop model calls an inline skill → the fork's next iteration is narrowed;
  - **new case (review-2): a patterned `Bash` in the skill turns off the shell engine.**
    - The agent has `allowed-tools [Bash, Read, ExecuteCode]`, PTC `[Read]`, the shell engine (`programmatic-engine:
      python`), and HOST isolation, with `python3` required under the existing skip rule. At the control step, the
      model is offered `ExecuteCode` (Python).
    - The model calls inline `Skill(git-helper)` with `allowed-tools [Bash(git status*), Read, ExecuteCode]` and
      `programmatic-tools [Read]`.
    - Asserts: the next iteration does not offer `ExecuteCode`, the iteration span's `ptc.unavailable_reason` names
      the Bash constraint, and `Bash` is still offered (patterned).
    - The same case runs through the fork loop, and a unit test of the slash loop asserts that the rebuilt offer context
      received the snapshot's allow-list;
  - **new case (review-1): an inline skill, then an approval-requiring skill, then `/approve`.** The test is configured
    with a pre-flight scanner and an `ASK` policy for skill `B`. It runs as follows:
    1. In iteration 1 the model calls inline `Skill(A)` with `allowed-tools [Read, Skill]` and no programmatic keys.
    2. In iteration 2 it calls `Skill(B)`.

    Asserts:
    - the turn does **not** end `SUSPENDED` and no `PendingTurn` is registered;
    - `B`'s result is the "ask again in a new turn" error;
    - iteration 3 is still offered only `{Read, Skill}` and no `ExecuteCode`.

    Then `/approve` reports nothing pending.

    **Control:** `Skill(B)` (`ASK`) in iteration 1, before any narrowing, still suspends. After `/approve`, the next
    execution starts from the agent's values.
  - `OrcaAgentExecutor` unit test: the pre-flight scan is skipped (the scanner is not consulted, or its verdict is
    ignored) exactly when the bound ceiling's `version > 0`.
- **`BackgroundWorkflowCeilingE2EIntegrationTest`** (new; scripted). The slash skill `/probe` with `allowed-tools [Read,
  Workflow]` launches a background `Workflow`. Its subagents' launch context `callerAllowedTools` ⊆ `[Read, Workflow]`.
  Before this PR the same test shows the agent's list. The builder writes it first to see it fail (README rule 5).
- **Whole-build gates** as in PR 1.

---

# PR 3 — repo-wide comment-reflow guard (TC-21)

Closes: backlog TC-21.

## 3.1 Scope change

- **Java.** `JAVA_ROOTS` becomes a walk of every `modules/*/src/main/java` and `modules/*/src/test/java`.
  - The per-entry reason map goes; the scope reason is the class javadoc.
  - The `files scanned > 50` sanity assertion becomes `> 1000`.
  - Nothing is excluded except generated sources, of which the builder confirms there are none.
- **Markdown.** `MARKDOWN` becomes `docs/**/*.md` plus the root `*.md` files (`README`, `CONTRIBUTING`, `SECURITY`,
  `CODE_OF_CONDUCT`, `CHANGELOG` and their `.ko.md`), translations included.
- **Expected volume at `318805f5`** (the PR 1 predicate; the throwaway scan):
  - about **760 Java hits**: about 546 form A plus about 212 form B, spread over most modules and concentrated in
    `aimon-core`. The builder records the per-module split when it reruns the scan;
  - about **130 Markdown hits**: about 109 canonical and about 23 translated.
- **Markdown false positives are expected**, and sampling showed some:
  - a line that ends a sentence before a link-only line;
  - a short paragraph-final line followed by a `**label**:` line.

  The builder first tightens `markdownOrphans` so that a next line starting with `[`, `**` or `` ` `` is a new block,
  and a previous line ending in `.`/`:`/`)` with the next line starting a capital or `**` is a sentence boundary. The
  goal is to calibrate so that the sampled false positives drop, then fix the rest. Any remaining deliberate short line
  gets a reasoned `ALLOWED` entry.
- The predicate itself is otherwise unchanged from PR 1.

## 3.2 How the fixes are made

- **A throwaway reflow script**, kept in the scratchpad and **not committed**.
  - It joins each reported orphan to its neighbour and re-wraps that paragraph greedily at 120 columns.
  - It never touches `<pre>`, tags or lists.
  - Then `./gradlew format` runs.
  - It is mechanical and reviewed by diff. No wording changes.
- **Every reflowed file must compile and pass `checkStyle` / `checkFormat`.** The diff is whitespace-and-newline-only
  inside comments, which a reviewer checks with `git diff -w --word-diff`: no word may change.
- **Translations.**
  - When a canonical doc is reflowed, its translation is reflowed in the **same commit** if it has hits.
  - If the translation has no hits and was **fresh** before this commit (by `check-translation-staleness.py`), its
    `source_commit` is bumped to the pre-edit canonical commit, in the same commit, so the whitespace-only change does
    not read as stale.
  - A translation that was already stale is left stale and listed in the PR body. Bumping it would hide real lag.
  - `check-translation-structure.py` is unaffected (it ignores line counts).
- **Commits.** One commit per module (or per docs top-level directory), so a conflict with another branch is local.
  Then one commit with the test's scope change, last, so every intermediate commit is green.

## 3.3 Failure modes and tests

| Failure | Handling |
|---|---|
| A reflow changes a word | `git diff -w --word-diff` shows no word changes; reviewer gate |
| A reflow breaks hand-laid javadoc (aligned `@param` continuation) | The predicate skips tag lines and `<pre>`; the reflow is limited to the reported paragraph; spot-check `javadoc` with `./gradlew javadocCoverage`, and `checkAll` runs the javadoc ratchet |
| An open branch conflicts | Accepted: the cost the register named. Per-module commits keep the conflicts local |
| Markdown false positives | Calibrated predicate plus a small `ALLOWED` list with reasons |
| A test from outside `aimon-core` edited after the run is `UP-TO-DATE` locally | As today (class javadoc): CI runs clean |

Tests:
- `CommentReflowTest` itself: the scope tests;
- `predicate()` gains the Markdown calibration cases;
- a test that the walk finds files in at least 15 modules.

Then `checkAll`, `integrationTest` (comments only, but the reflow touches test sources) and all doc scripts.

## 3.4 PR 3 — changes by file

| File | Change |
|---|---|
| `coreT/architecture/CommentReflowTest.java` | repo-wide scope; Markdown calibration; javadoc |
| ~400 Java files across modules, ~60 Markdown files | reflow only |
| translations whose canonical was reflowed | reflow or `source_commit` bump per §3.2 |
| `register` + `docs/backlog/README.md` | close TC-21. Correction to record: the register said ~450 hits; under the PR 1 predicate it was about 760 Java plus about 130 Markdown, and the old predicate itself was ~546 in Java alone, because the register's count came from a different heuristic |
| `docs/design/tool/ptc-leftovers-core.md` | append "after the PR 3 build" |
| `CHANGELOG.md` | one line: build-only, the guard is repo-wide |

---

## Open questions

Each is answered with the assumption the build takes unless the reviewer or the user overrides it.

- **OQ-1 (TC-26, behaviour change).** For a model-invoked inline skill **without** programmatic keys in an agent that
  has PTC: does `ExecuteCode` disappear for the rest of the turn (`none`, the same as the slash path), or are the
  caller's settings inherited?
  - **Assumed: `none`.** One skill should mean the same thing whichever path invoked it, and B-DV1 already made that
    choice for the slash path.
  - The cost: an existing PTC agent loses `ExecuteCode` after any inline skill until the turn ends. The model is told.
    It is recorded in CHANGELOG as a behaviour change.
- **OQ-2 (TC-26 scope).** Narrowing lasts until the execution ends (turn, fork, slash skill). Assumed. There is no finer
  boundary the runtime can observe.
  - The one way a user-perceived turn could have been split into two executions was an SK-11.4 suspension. That is
    closed by never suspending a narrowed turn (§2.3 item 7).
  - The trade-off: an `ASK` skill cannot be approved interactively in the middle of a narrowed turn, and the user asks
    again in a new turn. Assumed acceptable. The alternative, persisting the ceiling across a suspension, needs new
    session-record state (§2.3, rejected).
- **OQ-3 (TC-22).** One payload knob (the executor's policy) for direct and nested spans, rather than a separate
  nested-only policy. Assumed (§1.11).
- **OQ-4 (TC-20).** The real OpenSandbox reply-write latency cannot be measured in this repository. The 2 s decision
  rests on local plus simulated measurements and the cost asymmetry. Assumed acceptable; the existing WARN-based trigger
  is restated in the closure.
- **OQ-5 (PR 3).** The Markdown predicate's calibration may still leave borderline lines. Assumed: fix the true ones and
  allow-list the rest with reasons, rather than narrowing Markdown scope. If false positives dominate after
  calibration, the builder narrows Markdown to `docs/design/**` + `docs/backlog/**` and records that in the departure
  section. TC-21 still closes for Java, and the Markdown remainder is noted, not left silently.
- **OQ-6 (TC-23).** No new `InvokerType`; a slash skill's spans say `invokerType=MAIN_AGENT` plus `skill=<name>`.
  Assumed (§2.1).
- **OQ-7 (TC-25).** The ceiling digest in the background run id means a request that would have joined an in-flight run
  across an upgrade boundary starts a new run instead. Assumed negligible: the run store defaults to in-memory.
- **OQ-8 (A5).** The description now permits `asyncio.run(main())` for programs without top-level `await`. That is
  model-visible text; §1.13 measures it. If `gpt-4o` regresses, the builder reverts the wording and keeps the runtime
  change.

---

## 4. After the PR 1 build — where the implementation departed

*Appended 2026-11-07, after the PR 1 build. The body above is the design as approved by design review round 3, not
edited to look prescient. Sources: the run's `build/pr1/deviations.md`, review 3's non-blocking notes, and what was found
while building.*

**No PR 1 decision changed.** The refused-call skip (§1.1), the `Spans.*Safely` helpers (§1.3), `join_sweep` (§1.4),
loop-only-when-needed with pre-imports (§1.5), program-only tracebacks (§1.7), the core-side error line (§1.8), the
one-knob payload key (§1.11) and the measured drain (§1.12) are built as written. The model-visible changes are recorded
in [`programmatic-tool-calling.md`](programmatic-tool-calling.md) §21 (the "PTC 남은 일 PR 1" table).

### 4.1 Where the build departed from the body

- **§1.9 case 3 rested on a wrong premise.** The inner fork's ITERATION span does *not* carry `ptc.unavailable_reason`
  when the middle fork declares no programmatic settings. The inner fork's offer already carries the narrowed settings
  (`none`), and `ExecuteCodePlan.of` reports a reason only for an agent whose offer opens tools to code
  (`reportedReason`). What tells the operator is `DefaultSubagentExecutionManager`'s caller-ceiling WARN, once per
  subagent name. `NestedForkProgrammaticCeilingE2EIntegrationTest#middleForkWithoutPtc` pins the behaviour as it is —
  no span attribute, the WARN present — under a name that says so. It is an observability gap, not a ceiling hole, so it
  was not fixed here: [TC-30](../../backlog/tool-contract-open-items.md). Case 2's refusal reaches the program as
  `Tool 'Grep' cannot be called from code here; callable tools: Read`; "not callable" is the span's `ptc.refused` kind.
- **§1.7 needed one more step.** With program-only tracebacks, one host path was left: an asyncio log names a task's
  coroutine by where it was defined (`coro=<Read() done, defined at …/aimon_ptc.py:303>`), and the tool functions,
  `call_tool` and `_await_reply` are defined in the runtime file. Their code objects now report the file name
  `<runtime>`. Their frames were already dropped from tracebacks.
- **§1.7's "confirm at `ShellProgramRun.java:473`" came out the other way.** On the no-`DONE` path,
  `ShellCodeExecutionEngine.classify` uses the shell's own stderr tail as the outcome's `logs` when the runtime sent
  none, so it does reach the model's `[logs]`. As the body said, that is recorded and not fixed:
  [TC-31](../../backlog/tool-contract-open-items.md).
- **§1.6's numbers.** The 47 lines over 120 were bytes (`—` is three); by characters there were 32 in `[Unreleased]`.
  After the reflow two remain, both `###` headings, which cannot wrap without rewording, and rewording moves the anchor.
  `CONTRIBUTING.md` and `CONTRIBUTING.ko.md` already named `ANTHROPIC_API_KEY` (added in #338 after the body's
  citation), so only the CHANGELOG bullet changed and neither CONTRIBUTING file — nor its `source_commit` — was touched.
- **§1.10's example.** `ToolCallDispatcher.java:15-17` at `318805f5` is not an orphan under either form; the form-B case
  in `predicate()` is a synthetic line of that shape. In the PR 1 scope the generalized predicate reported nine sites
  (`ToolCallDispatcher`, `LocalShell`, `ShellKillCause`, `RunDirectory`, `ShellFeature`, `ShellExecutionException`,
  `ExecutionOptions`, `LlmSkillExecutor`, and one test), all reflowed with no word changed. `ALLOWED` stays empty.
- **§1.12's measurement reads the wait from the log.** `close()` runs inside `ShellCodeExecutionEngine.execute`, which
  drops the returned `Duration`, so the gated test reads each wait from the DEBUG line `close()` already writes
  (millisecond resolution; no line means no wait). Of each 200 runs, 56–69 had a write in flight when the run closed.
- **§1.16's test placement.** `ToolApiRendererTest` and `ExecuteCodeObservationTest` are new classes (neither existed).
  The runtime cases are in a new engine-driven `ShellRuntimeProgramTest`, not `ShellEngineProtocolTest`; the
  `join_sweep` timings are in `ShellRuntimeSweepTest` and time the runtime process, whose exit follows `DONE`.
- **Review 3's PR 1 notes, applied.** `TracingLlmClient`'s private `setAttributeSafely` delegates to
  `Spans.setAttributeQuietly`, so TTFT, the streaming flag and `feature` keep their DEBUG-only logging (§1.3 said both
  "delegates to the new helper" and "TTFT calls the quiet variant"). At `resolveEffectiveToolContext`,
  `TRACE_PAYLOAD_POLICY` is put under the same absent-only guard as `TRACER`. `PYTHON_RESERVED`'s javadoc names the gap
  that a tool named after a builtin (`print`, `len`) is still bound and shadows it.

### 4.2 Measured values and the open questions

- **TC-20.** 200 programs per series on `LocalShell`, each firing 8 unawaited calls and ending at once:

  | Series | write p50 / p99 / max | `close()` wait p99 / max |
  |---|---|---|
  | local | 0 / 1 / 1 ms | 0 / 0 ms |
  | +50 ms | 56 / 62 / 62 ms | 60 / 61 ms |
  | +250 ms | 258 / 262 / 262 ms | 262 / 262 ms |
  | heavy-tailed (p99 ≈ 1 s) | 48 / 1 070 / 1 070 ms | 68 / 1 070 ms |

  No wait exceeded the slowest single write of its series, and the local p99 write is under 100 ms, so the gate in §1.12
  holds and `REPLY_DRAIN` is 2 s. The register's closure has the same numbers.
- **Real-model E2E (§1.13).** One run per model, 2026-11-07: `gpt-4o-mini` and `gpt-4o`, JavaScript and Python — all
  four `result=PASS`, one program each with 10 of 10 nested calls succeeding, `completion=COMPLETED`. Python
  `answerMatches=true` for both models; JavaScript `answerMatches=false` for both (the final answer said the hosts were
  printed instead of naming them; logged, not asserted). Both Python programs used top-level `await main()` (and still
  wrote `import asyncio` / `import json`, which is harmless). No result had a `[logs]` section, none contained
  `aimon_ptc.py` or an interpreter path, and none failed on `asyncio.run` or a missing import — so the error paths of
  §1.7 and §1.8 were not exercised live; their tests are deterministic. No `aimon_ptc.py` process was left.
- **OQ-3** stands as assumed: one payload knob (TC-22's closure says why). **OQ-4** is restated in TC-20's closure:
  the real OpenSandbox write latency is unmeasured here, and the in-operation WARN is the trigger. **OQ-8**: `gpt-4o`
  did not regress on the new Python wording, so it stays.
