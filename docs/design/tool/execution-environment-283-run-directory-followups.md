# Design — aimon-core #283: RunDirectory follow-ups from the sandbox implementation (F1–F4)

> Status: **IMPLEMENTED** (2026-10-19, issue [#283](https://github.com/kangwoo/aimon-core/issues/283)). This is the
> design for #283 exactly as it was approved in design review (two rounds; the second passed with six non-blocking
> notes). The body below, §1–§7, is the body as approved, kept byte-exact — including its line citations (as of
> `f7c72fef`) and its open-question numbers — except that two elided link targets in §2.5 (`…/issues/33`,
> `…/pull/35`) are written out in full so the repository's link check resolves them. It is written in English because that is how it was reviewed; the
> `design/` directory is not a translation target (`docs/project/documentation-guide.md` §5.1).
>
> One thing was added. §8 records where the implementation departed from this design, and why, including which review
> notes were taken. The open questions in §7 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-117 ← Q2;
> EE-118 ← Q4 · Q3; Q6 in EE-116; the F1–F4 sub-block under EE-114), which is the source of truth for what is open. The
> record this follows is [`execution-environment-ee109-hook-tamper-narrowing.md`](execution-environment-ee109-hook-tamper-narrowing.md)
> (its §11.7 points here).


Base: `main` `f7c72fef`. Sandbox source read: kangwoo/aimon-sandbox `herdr/sandbox-run-directory` `f54809c` (PR #35,
open). Design record this extends: `docs/design/tool/execution-environment-ee109-hook-tamper-narrowing.md` (EE-109).

## 1. Problem

EE-109 (#275 / PR #280) added the shell SPI `RunDirectory` — bytes core has checked, which a shell declaring
`ShellFeature.RUN_DIRECTORY` places in a fresh directory for one command. When aimon-sandbox implemented that SPI
(PR #35), four places came up where core's contract is looser than what a shell has to enforce, or less precise than
what a shell reports. **F1:** `RunDirectory.Builder.file` accepts path sets no filesystem can place (`a` and `a/b`)
and names a line-based digest manifest cannot carry (`\n`, `\r`). Each shell has to refuse these itself, and on the
sandbox they come back as a generic `ShellExecutionException`, which core reports as `EXECUTION_FAILED`.
**F2:** `RunDirectoryIntegrityException` and `ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED` say "the hook's files", but
the sandbox throws the same exception for a tampered *uploaded stdin*, which can happen even for a hook with no run
directory at all. **F3:** the 4 MiB cap (`HookRunFiles.MAX_BYTES`) is package-private, so the sandbox guesses 8 MiB.
**F4:** since #35 the sandbox kills a hook's process group shortly after the hook's trailer arrives, so a `cmd &` ends
with the hook. Core's docs say nothing about this, and `hook-config-guide` §`command` currently *advises* hooks to
hand long work to the background. The task is to tighten the builder, widen the wording, publish the cap, document
the background-job behaviour, and record all of this in the backlog, the design record and the CHANGELOG.

## 2. Approach and rejected alternatives

`RunDirectory` and everything around it is still `[Unreleased]` (CHANGELOG line 30). Its builder and exception docs
can therefore change without breaking compatibility. The one constraint is that the sandbox PR already compiles against
these names (`RunDirectory`, `RunDirectoryIntegrityException`, `ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED` is
core-internal in use but public in type). No public name is renamed.

### 2.1 F1 — the builder refuses unplaceable paths, eagerly, in `file(...)`

**Chosen.** `RunDirectory.Builder.file(relPath, bytes)` gains two refusals besides the existing confinement check.
Both throw `IllegalArgumentException`, the exception `file` already documents and throws:

1. **Control characters.** Refuse any `char c` in `relPath` with `Character.isISOControl(c)`: C0 `U+0000–U+001F`
   (this includes `\n`, `\r`, `\t` and ESC), DEL `U+007F`, and C1 `U+0080–U+009F`. `\0` is already refused. It moves
   from `isConfinedRelativePath` into this check, so there is one rule.
   - Why the whole set and not just `\n` / `\r`: `\n` and `\r` are what `sha256sum -c` cannot carry. The rest break
     other line- or terminal-oriented things the path passes through: the WARN log, the deny reason a CLI user reads
     (ESC / C1 CSI give terminal escape injection), and tar/shell-script inlining a shell may use. No legitimate skill
     or `hooks/` file name contains any of them. `Character.isISOControl` is a JDK-defined set, so a shell can name it
     exactly instead of copying a list. The check runs per UTF-16 `char`, which is exact because every ISO control is
     in the BMP. The `file` javadoc and `ShellFeature.RUN_DIRECTORY` state the set as **code points** U+0000–U+001F,
     U+007F and U+0080–U+009F, so a shell that walks code points or UTF-8 bytes matches it exactly. In UTF-8, C1
     characters are the two-byte sequences `C2 80`–`C2 9F`, never single bytes.
   - Not refused: Unicode format characters (`Cf`: bidi overrides U+202A–U+202E, U+2066–U+2069, ZWJ) and the line /
     paragraph separators U+2028 / U+2029. None of them breaks `sha256sum -c` or a byte-level placement. Refusing them
     is a policy about spoofed names, not about placeability. That is listed as open question Q2.
2. **A file that is also a directory.** Refuse a path when an existing entry is a proper parent of it (`a` present,
   adding `a/b` or `a/b/c`), or when it is a proper parent of an existing entry (`a/b` present, adding `a`). The
   `TreeMap` keeps both checks cheap: for the parents, `files.containsKey(prefix)` for each `/`-prefix of the new path
   (O(depth)). For the children, `String c = files.ceilingKey(relPath + "/")` and `c != null && c.startsWith(relPath +
   "/")`. That is correct because every key with the prefix `relPath + "/"` sorts at or after it, and the first such
   key is the ceiling. The keys `a.txt` and `a-b` sort between `a` and `a/` (`.`=0x2E and `-`=0x2D are below
   `/`=0x2F) and do not match the prefix.

The check is eager, in `file(...)`, rather than in `build()`. The second add fails in either order, at the call that
introduces the conflict, which is the call a caller can attribute.

Exception messages escape control characters, because the message reaches logs and (through `HookRunFiles`) a deny
reason: each `isISOControl` char is rendered as `\uXXXX`. Shapes:

```
Not placeable in a run directory: 'scripts/a\u000ab.sh' contains a control character (U+000A)
Not placeable in a run directory: 'lib' is a file and also the parent directory of 'lib/x.py'
```

(The second wording is the same whichever of the two was added first. It names both paths.)

Rejected alternatives:
- **Leave it to each shell** (status quo). This is what F1 exists to remove. Two shells already disagree: the local
  shell places `a\nb` fine and fails `a`+`a/b` with a raw `IOException` text, while the sandbox refuses both.
- **Check in `build()`**. This loses the "which add caused it" attribution and adds a second pass for no gain.
- **Only `\n` / `\r`** (the issue's minimum). This leaves ESC/C1 terminal injection through deny reasons, and makes the
  next shell (tar, a JSON manifest) find its own character.
- **Case-folding / Unicode-normalization collisions** (`A` vs `a`, NFC vs NFD). Whether two names collide depends on the
  filesystem that places them. `ShellFeature.RUN_DIRECTORY` item 2 already makes that the shell's error, and
  `LocalShell` already reports it. It stays there.
- **A dedicated `RunDirectoryPathException`**. `IllegalArgumentException` is what `file` throws today for the same
  kind of input error, and `HookRunFiles.read` already catches it. A new type earns nothing.

**Callers and fail-closed semantics.** The only production caller of `Builder.file` is `HookRunFiles.read` (grep:
`RunDirectory.builder()` appears in main only there). It already catches the builder's `IllegalArgumentException` and
throws `StagingException`, so the refusal already flows into the existing refusals:

| Path | Today, with an unplaceable path | After F1 |
|---|---|---|
| `hooks.json` handler naming `AIMON_HOOK_DIR` (`IsolatedShellActionExecutor` → `ConfigHookDirectory.runDirectory` → `HookRunFiles.forHookDirectory`) | `\n`: runs on local, `EXECUTION_FAILED` on the sandbox. `a`+`a/b`: `EXECUTION_FAILED` (shell `IOException` / exit 70) | `HOOK_DIR_STAGING_FAILED`, detail = the builder's message |
| Skill hook (`DefaultShellActionExecutor.run(..., skillDirectory)` → `HookRunFiles.forSkill`) | same as above | `STAGING_FAILED`, detail = the builder's message; **no** staging fallback |

Both rows apply only on a shell that declares `RUN_DIRECTORY`. Without it, a `hooks.json` handler is
`RUN_DIRECTORY_UNSUPPORTED` before anything is read, and a skill goes straight to `stageInto`. In neither case is the
builder reached, so nothing changes there.

Both outcomes are the existing "could not run" class in `ShellHookVerdicts`. A guard blocks (fail-closed) unless the
handler declares `failOpen`, and an advisory event logs WARN and skips. **This is not `HOOK_FILES_TAMPERED`.** An
unplaceable name is a defect in the hook's own files, which the operator or skill author can see and fix. It is not
evidence that something rewrote bytes after core checked them. It must not override `failOpen`. Making it override
would also hand anyone who can drop a file into a workspace skill a way to make every `failOpen` hook of that skill
block, which is a worse lever than the one it closes. No new bypass is opened either: someone who can create a file
in the hook's directory can already rewrite the hook script itself.

Why no staging fallback for a skill (unlike over-the-limit): the size fallback exists because a large skill is
legitimate and staging can carry it. An unplaceable path set is not legitimate. Staging cannot place `a`+`a/b` either,
and quietly moving a skill with a `\n` name from the run directory to the weaker staged copy would widen the EE-109
window to work around a malformed file name. The refusal message says how to fix it (rename, or `.stageignore`).

`HookRunFiles.read` changes its wrapper text, because "is not a relative path inside it" is now wrong for two of the
three causes. It passes the builder's (escaped, model-safe) message through:

```java
} catch (IllegalArgumentException e) {
    throw new StagingException("Cannot read '" + resource.getName() + "' for the hook: " + e.getMessage()
            + ". Rename the file, or exclude it with " + StagedResource.STAGE_IGNORE_FILE, e);
}
```

The builder's message names only the relative path inside the skill or `hooks/` directory, never a host path, so it
may become a deny reason. The `ConfigHookDirectory.runDirectory` comment "fixed text plus sizes, no host path" stays
true. Note that `read()` hashes before it adds to the builder. The throw ends the read at once, nothing is cached, and
every firing refuses the same way until the file is renamed.

`ShellFeature.RUN_DIRECTORY` item 2 is updated to say that the builder guarantees no control characters and no
file/directory overlap, so a shell need not check those. Collisions the shell's filesystem makes (letter case, Unicode
form) remain its error.

### 2.2 F2 — widen the wording; no kind enum

**Chosen.** Text only. No new type and no renamed constant:

- `RunDirectoryIntegrityException` class doc. Old: "the files a shell placed in a run directory do not hold the bytes
  it was given". New: "what a shell placed for the run from bytes it was given — the
  {@linkplain ExecutionOptions#getRunDirectory() run directory}'s files, or the command's
  {@linkplain ExecutionOptions#getStdin() stdin} when the shell moved it through something the agent can write (an
  upload) — no longer holds those bytes". Add one sentence saying it may be thrown for a command with no run directory
  (a hook whose stdin was uploaded). Keep the "evidence, not infrastructure failure" paragraph.
- `ExecutionOptions.getStdin()` doc gets one sentence: a shell that passes the payload through anything the agent can
  write checks it as `RUN_DIRECTORY` item 3 describes, and reports a mismatch as `RunDirectoryIntegrityException`, not
  as a generic failure. This is worded as "a shell that does so", not as a new obligation on shells that pipe stdin
  directly (`LocalShell` pipes it).
- `ShellFeature.RUN_DIRECTORY` item 3: add a back-reference saying the same exception covers stdin (see
  `getStdin()`).
- `ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED`: description `"the hook's files or input changed before the run"`, and
  the javadoc says "the hook's files or its input (stdin payload)". The constant name stays. It is public, the sandbox
  test code may reference it, and "files" remains the main case.
- `ShellHookVerdicts`: the deny reason becomes `"Blocked: hook '…' (…) was not run — the hook's files or input changed
  before the run."`. The class doc and the WARN log line ("its files or input changed between their check and the
  run") change to match.
- `ShellActionRunner`: the javadoc of the `runDirectory` overload and the WARN text ("the shell found the hook's files
  or input changed before the run"). The log keeps `e.getMessage()`. The sandbox's message already says "a hook file or
  the hook's input".

Rejected: **a kind/cause enum on the exception** (`FILES` / `STDIN`). Nothing in core decides differently on it. Both
block regardless of `failOpen`, and both have the same remediation (find what writes into the environment's upload
path). The sandbox cannot cheaply tell them apart either: one wrapper exit code (`RUN_FILES_TAMPERED_EXIT`) covers
both checks. An enum would be an SPI shape every shell must fill with no consumer. **Renaming the exception**
(`RunInputIntegrityException`) is rejected because it breaks the sandbox PR's compile for a cosmetic gain. The package
doc says the run directory family covers "what was handed over for the run".

### 2.3 F3 — publish the cap on `RunDirectory`, and enforce it there

**Chosen.**

```java
/**
 * The most a run directory holds, summed over its files: 4 MiB. The builder refuses more, so a shell that
 * declares {@link ShellFeature#RUN_DIRECTORY} can rely on it and size its transfer for it. It is written, and on a
 * remote shell uploaded, at every hook firing.
 */
public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024;
```

- The name follows `getTotalBytes()`.
- `Builder.file` keeps a running total and throws `IllegalArgumentException` ("would hold N bytes, over the
  `MAX_TOTAL_BYTES`-byte limit of a run directory") when an add would exceed it. Enforcing it in the builder is what
  turns the number into a contract ("no `RunDirectory` is larger") rather than a hint. It is free now, while the type
  is unreleased.
- `HookRunFiles.MAX_BYTES` is **removed**. Every use refers to `RunDirectory.MAX_TOTAL_BYTES`: `HookRunFiles` (the
  class doc, four comparisons, `overLimit`) and `DefaultShellActionExecutor` (the class doc at `:51`, the fallback WARN
  at `:136`). The tests that size a file at `MAX_BYTES + 1` (`IsolatedShellActionExecutorTest:184`,
  `SkillHookRunDirectoryTest:252`) switch too. `HookRunFiles` keeps its pre-checks (`getTotalBytes() >` and the running
  total in `read`), so the builder's refusal is unreachable from it: a skill still falls back to staging, and a
  `hooks/` directory still gets the `.stageignore` message. `MAX_CACHED_BYTES` stays package-private, since it is not
  part of the SPI.
- `build/sandbox-followup.md` (written by the builder, not here) holds the one-line sandbox change:
  `static final long MAX_RUN_DIRECTORY_BYTES = RunDirectory.MAX_TOTAL_BYTES;` in `SandboxShell`, replacing the 8 MiB
  guess and its "slack" comment. The file also records what the core bump (F1 together with F3) does to the sandbox
  without any sandbox edit. The task hands the sandbox only this note, so the note must cover it:
  - **Three existing assertions in `SandboxShellRunDirectoryIT` go red.** Each one builds the `RunDirectory` inside its
    `assertThatThrownBy` lambda, through the `files(...)` / `hookFiles(...)` helper (`:81-92`). After F1,
    `Builder.file` throws `IllegalArgumentException` before `SandboxShell` is reached:
    - `aFileAndADirectoryOfTheSameNameAreRefused` (`:242-248`): `hookFiles("lib", …, "lib/x", …)` expects
      `ShellExecutionException` "cannot place lib/x".
    - `badArgumentsAreRefusedBeforeConnecting` (`:274`, `:276`): `hookFiles("a\nb", …)` and `hookFiles("a\rb", …)`
      expect `ShellExecutionException` "newline". The third assertion in that test (a hook command over 64 KiB) is
      unaffected.

    The fix is the sandbox maintainer's choice. One option is to rewrite them to assert the core refusal
    (`assertThatThrownBy(() -> hookFiles("a\nb", "1")).isInstanceOf(IllegalArgumentException.class)`), which pins
    that the sandbox relies on it. The other is to delete them as covered by core's `RunDirectoryTest`. Either way,
    the "command did not run / no leftovers" half of test 22 still holds trivially and can go.
  - **Two sandbox checks become backstops that core's builder never lets through:** the newline / carriage-return
    branch of `SandboxShell.checkRunDirectory` (`:261-264`) and the `MAX_RUN_DIRECTORY_BYTES` branch (`:270-273`).
    They can stay as defence in depth or be removed; the note recommends keeping them, since they cost nothing.
    Placement failures that come from the filesystem itself (case or Unicode-form collisions) are still the
    sandbox's to report.
  - I grepped the other sandbox tests for builders with control characters or more than 4 MiB and found none:
    `:164` is 40 KiB and `:293` is 1500 one-byte files.
  - Design Q-F in `workspace-sandbox-run-directory.md` is answered.

Compile-time inlining: a `public static final long` is a constant expression, so the sandbox copies `4194304` into its
bytecode at compile time. That is acceptable. With builder enforcement, a newer core can only make the builder refuse
*earlier* than an older shell expects (if the cap is lowered), never hand it something larger than the cap the shell
was compiled against, unless the cap is *raised*. A raise is a contract change. A shell compiled against the old value
that keeps its own `> cap` check refuses the new range **at run time**, not at compile time. So the javadoc says: a
raise ships only after every shell that checks the cap has been rebuilt *and released* against the new value.

Rejected:
- **A shell-side hint** (`VirtualShell.maxRunDirectoryBytes()`). This inverts the dependency: core would have to ask
  every shell and pick the smaller value, and it adds an SPI method every shell must implement, for one number that
  core decides.
- **A constant on `ShellFeature` / docs only on `ExecutionOptions`**. The cap is a property of the value, so it
  belongs on the value's type. `ShellFeature.RUN_DIRECTORY` and `ExecutionOptions.getRunDirectory()` link to it.
- **Publish without enforcing**. The shell would still have to check, and "follow core" would mean "trust that every
  caller remembered".
- **Keep `HookRunFiles.MAX_BYTES` as an alias**. That leaves two names for one number, which is the drift F3 removes.

EE-116 ("make the two limits configurable") interacts with this. A configurable hook limit can now only be *at most*
`RunDirectory.MAX_TOTAL_BYTES`. The backlog entry says so (see §3.7). The cache cap remains freely configurable.

### 2.4 F4 — say that an environment may end what a hook leaves running

What core says today:
- `ExecutionOptions.isHook()` / `isIsolated()` say nothing about background children.
- `LocalShell` class doc: cancellation and timeout kill "the process and its descendants", and a detached process is
  not reached. Nothing is said about a normal exit.
- `hook-config-guide.md:670` / `.en.md:742` (the paragraph "A report command runs to its end"): **"have the command hand
  long work to the background and return at once"**. Since #35, that advice silently fails on the sandbox.
- `aimon-skill-extensions.md` (ko only) covers background `Task` / `Workflow`, not a hook command's `&`.

What `LocalShell` actually does, from `execute` (`:582–660`): after `p.waitFor()` returns normally, nothing is
destroyed, so a child the hook's command backgrounded **keeps running** after the hook. Its output goes to the
capture files, which are unlinked for a hook on Unix, so it is lost but harmless. On a timeout, a cancellation or an
interrupt, `destroyForciblyQuietly` ends the tree that is still reachable as descendants. A process that detached
(double fork, `setsid` + parent exit) is not reached. What the sandbox does (since #35, `SandboxShell:79-81, 559`):
it kills the exec's process group `TRAILER_KILL_GRACE` (500 ms) after the trailer arrives, for both hook and isolated
runs. A job moved into its own session or group (`setsid`, `set -m`) outlives it until the sandbox goes.

**Chosen.**
- `ExecutionOptions.isHook()` gets a paragraph: **"What the command leaves running.** When such a command ends, a
  shell may end what it started and left running (a `cmd &`), for instance by ending the command's process group: a
  hook must not rely on a background job outliving it. `LocalShell` does not do this. A descendant of a hook's command
  that exited by itself keeps running, and only a timeout, a cancellation or an interrupt ends the descendants still
  reachable." `isIsolated()` gets one sentence: "The same holds for an isolated command (see `isHook()`)."
- `hook-config-guide.md` / `.en.md`, the same paragraph: replace "hand long work to the background and return at once"
  with advice that is true on both providers. Keep the timeout short and return at once. Work that must outlive the
  hook goes to something that is not the hook's process (a queue, a service the hook calls, an `http` handler), because
  **an execution environment may end what a hook leaves running when the hook ends**. On the local provider a
  background job keeps running. On aimon-sandbox its process group is ended about half a second after the hook exits.
  Add the same one-line fact in the `command` section's "어디서 도는가 / Where it runs" bullets, next to the existing
  provider bullets, so a guard author meets it too. `setsid` is not recommended as an escape. It is a
  provider-specific way around a behaviour the provider chose, and Q3 records it.
- `aimon-skill-extensions.md` (ko only, no translation): one line in the skill-hook `command` lifetime bullet, because
  skill hooks are `hook(true)` too and the same kill applies.

Rejected: **making `LocalShell` kill a hook's descendants on normal exit** (for parity). That is a behaviour change the
task does not ask for. It would reach detached work only partly (descendants, not detached processes), and it would
change local CLI users' working hooks. The task asks for the local behaviour to be *stated*. **Making the kill a
`ShellFeature`** (declared). No caller would branch on it, since the advice is the same either way. That is open
question Q4.

### 2.5 Backlog, design record, CHANGELOG

- **Backlog (`docs/backlog/execution-environment-open-items.md`, ko only).** No new IDs, so the counts in the title and
  in `docs/backlog/README.md` stay as they are and `check-backlog-registers.py` is unaffected. Instead:
  - EE-114 gets a dated sub-block `### 후속 — 코어 #283 (F1–F4) · 닫힘 (2026-10-19)`. It is a `###` under the item,
    as other items use `### 닫힘` (it is not a `##`, so it is not an item for the checker). It has one bullet each for
    F1–F4 and what closed them, plus "F5 was resolved on the sandbox side in #35 and needs no core change". Its header
    note links [kangwoo/aimon-sandbox#33](https://github.com/kangwoo/aimon-sandbox/issues/33) and [PR #35](https://github.com/kangwoo/aimon-sandbox/pull/35). EE-114 itself stays **열림**, because
    PR #35 is not merged (checked: `state: OPEN`).
  - EE-114's **정할 것** Q8 gets "결정됨 (샌드박스 #35): 트레일러 뒤 프로세스 그룹을 끝낸다 — 코어 문서는 #283 F4". Q8 is
    pending the maintainer on the sandbox side, so it is phrased as "the sandbox does since #35".
  - EE-113's header note: replace "등록할 이슈다" with a link to
    [kangwoo/aimon-sandbox#34](https://github.com/kangwoo/aimon-sandbox/issues/34) (checked: open, titled "Run
    policy-hook execs under a separate uid (core EE-113)").
  - EE-116's **무엇을**: `HookRunFiles.MAX_BYTES` → `RunDirectory.MAX_TOTAL_BYTES` (public, enforced by the builder). Add
    one sentence: a configurable hook limit can only lower it, and raising it is an SPI change that shells must rebuild
    for.
- **Design record.** Add `### 11.7 Follow-ups from the sandbox implementation (#283)` after §11.6, in English, with the
  same bullet style. It contains the four decisions in two to four lines each, with the test names. The status
  blockquote at the top already says §11 records departures, so it gets one added clause ("and §11.7 the follow-ups of
  #283"). The file has no translation (`design/` is not a translation target, as its own header says).
- **CHANGELOG `[Unreleased]`.** Amend the existing EE-109 bullets rather than adding a parallel story, because the
  types are unreleased and a reader should see the final contract. The bullet at `:30` gains "`RunDirectory.MAX_TOTAL_BYTES`
  (4 MiB, enforced by the builder); the builder refuses control characters (`Character.isISOControl`) and a path that
  is both a file and a directory". The `HOOK_FILES_TAMPERED` bullet (`:57` area) gets "files or input". Add one
  **Changed** line: on a shell that declares `RUN_DIRECTORY`, a skill or `hooks/` directory with a file name that
  contains a control character (a tab `\t` included, not only `\n` / `\r`) or that is both a file and a directory is
  now refused (`STAGING_FAILED` / `HOOK_DIR_STAGING_FAILED`; a guard blocks unless `failOpen`) instead of being placed
  by the shell or failing in it. Before, such a skill ran on `LocalShell`. The fix is to rename the file or list it in
  `.stageignore`. Add one **Docs** line for F4,
  including the corrected "hand to the background" advice.

## 3. Concrete changes by file

All paths are under `modules/aimon-core/src/main/java/at/aimon/core/` unless marked otherwise.

| # | File | Change |
|---|---|---|
| 3.1 | `shell/RunDirectory.java` | + `public static final long MAX_TOTAL_BYTES`. Builder: running total, control-char refusal (`isISOControl`, replacing the `\0` clause in `isConfinedRelativePath`), file/dir overlap refusal in either order, escaped messages. Javadoc of `file` (`@throws` lists all four causes) and the class doc (one sentence on what the builder guarantees). |
| 3.2 | `shell/ShellFeature.java` | `RUN_DIRECTORY` item 2: say what the builder guarantees, and that the shell's own collisions remain its error. Item 3: "the same exception covers a stdin the shell moved that way". Link `MAX_TOTAL_BYTES`. |
| 3.3 | `shell/ExecutionOptions.java` | `getStdin()`: integrity sentence (F2). `isHook()`: "What the command leaves running" paragraph (F4). `isIsolated()`: one cross-reference sentence. `getRunDirectory()`: link the cap. |
| 3.4 | `shell/exception/RunDirectoryIntegrityException.java` | Class doc widened to files or stdin (F2). |
| 3.5 | `skill/hook/declarative/HookRunFiles.java` | Remove `MAX_BYTES` and use `RunDirectory.MAX_TOTAL_BYTES`. New `IllegalArgumentException` wrap text (§2.1). Class doc: one line saying an unplaceable file name is refused, not staged. |
| 3.6 | `skill/hook/declarative/DefaultShellActionExecutor.java` | `HookRunFiles.MAX_BYTES` → `RunDirectory.MAX_TOTAL_BYTES` (doc `:51`, WARN `:136`). |
| 3.7 | `skill/hook/declarative/ShellHookOutcome.java` | `HOOK_FILES_TAMPERED` description and javadoc. `STAGING_FAILED` / `HOOK_DIR_STAGING_FAILED` javadoc: add "or holds a file name that cannot be placed" to their cause lists. |
| 3.8 | `skill/hook/declarative/ShellHookVerdicts.java` | Deny reason, log, and class doc ("files or input"). |
| 3.9 | `skill/hook/declarative/ShellActionRunner.java` | Javadoc and WARN text. |
| 3.9a | `skill/hook/declarative/IsolatedShellActionExecutor.java` | Class doc `:49-51` ("found the files changed") → "files or input". Before finishing, the builder greps main, docs and tests for `files changed` and `hook's files` to catch any copy this table missed. |
| 3.10 | `skill/hook/declarative/ConfigHookDirectory.java` | Comment at `:75`: "(over the limit, changing on disk, an unplaceable file name)". |
| 3.11 | `docs/features/hook/hook-config-guide.md` + `.en.md` | F2: the outcome-table row (`:601` / `:661`) and the troubleshooting row (`:1092` / `:1179`, which quotes the deny text and must match it exactly). F1: the troubleshooting row `hook script directory could not be staged` (`:1090` / `:1177`) adds "a file name with a control character, or a file and a directory of one name". The `AIMON_HOOK_DIR` bullet (`:328` / `:357`) adds the same to its causes list. F4: the "report command" paragraph (`:670` / `:742`) and the "where it runs" bullets. The structure is identical in both languages: same bullets, same table rows. |
| 3.12 | `docs/references/aimon-skill-extensions.md` (ko only) | F4 one line in the skill-hook lifetime bullet. F1: if the `AIMON_SKILL_DIR` bullet (`:153`) lists refusal causes, add the unplaceable-name one. |
| 3.13 | `docs/backlog/execution-environment-open-items.md` | §2.5. |
| 3.14 | `docs/design/tool/execution-environment-ee109-hook-tamper-narrowing.md` | §11.7 and the status clause. |
| 3.15 | `CHANGELOG.md` | §2.5. |
| 3.16 | `$RUN_DIR/build/sandbox-followup.md` | §2.3 (the builder writes it; it is not in the repo). |

`docs/design/tool/execution-environment.md`: nothing in it describes the builder's checks, the cap, or hook background
jobs (grep). No change is needed, and the builder should confirm that.

## 4. Data and interface shapes that change

| Element | Before | After |
|---|---|---|
| `RunDirectory.MAX_TOTAL_BYTES` | — | `public static final long` = 4 MiB |
| `RunDirectory.Builder.file` | IAE on: not confined, `\0`, duplicate | IAE on: not confined, any `isISOControl` char, duplicate, file/dir overlap (either order), total over `MAX_TOTAL_BYTES` |
| `HookRunFiles.MAX_BYTES` | package-private constant | removed |
| `ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED.description()` | `the hook's files changed before the run` | `the hook's files or input changed before the run` |
| Guard deny reason on tampering | `… was not run — the hook's files changed before the run.` | `… was not run — the hook's files or input changed before the run.` |
| Unplaceable hook file name, on a shell that declares `RUN_DIRECTORY` | shell-dependent: placed, or `EXECUTION_FAILED` | `HOOK_DIR_STAGING_FAILED` / `STAGING_FAILED`, failOpen-respecting |
| The same, on a shell without `RUN_DIRECTORY` | `hooks.json`: `RUN_DIRECTORY_UNSUPPORTED`. Skill: staged copy (`stageInto`) | unchanged: the builder is never reached |
| Docs-only contracts | — | `getStdin()` integrity, `isHook()`/`isIsolated()` background jobs |

Wire and persistence: none. No serialized form carries these.

## 5. Failure modes

| Scenario | Handling |
|---|---|
| A skill file named with `\n` (local FS allows it) | `forSkill` → builder IAE → `StagingException` → `STAGING_FAILED`. The guard blocks unless `failOpen`, and the advisory event logs and skips. Every firing repeats it (nothing cached). The message names the escaped relative path and the fix. |
| `a` and `a/b` from a VFS that allows both (S3/GridFS keys, an in-memory VFS) | Same, through the overlap refusal, at whichever path is added second (files come in scan order). |
| The path in the message contains ESC / C1 | Escaped as `\uXXXX` before it reaches the log or the deny reason. |
| A `hooks/` directory over 4 MiB | Unchanged: `HookRunFiles` pre-check → `.stageignore` message. The builder cap is not reached. |
| A skill over 4 MiB | Unchanged: falls back to staging (the pre-check returns empty before the builder is involved). |
| A file grows between scan and read past the cap | Unchanged: the running total in `read` returns empty → read again / refuse. |
| Another caller builds a `RunDirectory` that F1 or F3 now refuses (sandbox tests) | Builder IAE, intentionally. Three sandbox assertions build exactly such directories and go red on the core bump: `SandboxShellRunDirectoryIT.aFileAndADirectoryOfTheSameNameAreRefused` (`:242-248`) and `badArgumentsAreRefusedBeforeConnecting` (`:274`, `:276`). `sandbox-followup.md` lists them and what they become (§2.3). No sandbox test builds more than 4 MiB. |
| Sandbox-uploaded stdin tampered, hook with no run directory | `RunDirectoryIntegrityException` → `HOOK_FILES_TAMPERED` → blocks whatever `failOpen` says. This is unchanged behaviour; now it is documented and worded accurately. |
| The hook leaves `sleep 600 &` | Local: it keeps running after the hook (documented). Sandbox: its process group is killed 500 ms after the trailer (documented as "may"). |
| An older sandbox build (8 MiB guess) against the new core | Harmless: the builder never hands it more than 4 MiB. |
| A future core raises the cap; a shell compiled against 4 MiB | That shell's own check (if it kept one) refuses the new range. This is called out in the constant's javadoc and would be a CHANGELOG "rebuild shells" item. |

## 6. Test strategy

Unit tests (`./gradlew :aimon-core:test`):

- `shell/RunDirectoryTest`:
  - `\n`, `\r`, `\t`, ESC (`\u001b`), DEL, and C1 (`\u009b`), each refused, with a message that contains `\u000a`
    etc. and never the raw char. A parameterized test over the set, plus `\0` still refused.
  - Characters just outside the set are accepted: space, `~`, `é`, `U+00A0`, `U+202E` (pins Q2's decision).
  - Overlap: `a` then `a/b`, `a/b` then `a`, deep `a` then `a/b/c`, `a/b/c` then `a`, and the sort-order traps
    `a.txt`+`a/b`, `a-b`+`a/b`, `ab`+`a/b` all accepted. The message names both paths in both orders.
  - Cap: exactly `MAX_TOTAL_BYTES` is accepted, one more byte (across two files) is refused, and an empty directory is
    accepted. A refused `file` call leaves the builder unchanged (the next valid add and `build()` work, and the total
    does not count the refused file). That last point is a design requirement: check before mutating.
- `skill/hook/declarative/SkillHookRunDirectoryTest`:
  - A skill (in the test's control VFS) containing `bad\nname.sh` → `STAGING_FAILED`, the reason contains the escaped
    name and `.stageignore`, **no** staging fallback happened (no staged copy, no fallback WARN), and the second firing
    gives the same result.
  - If the test VFS can hold `lib` and `lib/x.py`, the overlap case gets the same test. If it cannot (a real directory
    tree), the builder test is the pin and the builder notes that.
  - The over-limit test is switched to `RunDirectory.MAX_TOTAL_BYTES`.
- `IsolatedShellActionExecutorTest`: a `hooks/` file with `\n` in its name (a real temp dir; `assumeTrue` the FS
  accepts it, since Windows does not) → `HOOK_DIR_STAGING_FAILED`. Through `DeclarativePreToolHookTest`: a guard blocks
  without `failOpen` and proceeds with it. This pins "not tampering". The over-limit test is switched to the new
  constant.
- F2 text: update the three assertions on `"the hook's files changed before the run"`
  (`DeclarativeShellHookBindingTest:320`, `RunDirectoryUploadContractTest:110`, `DeclarativePreToolHookTest:128`) to
  `"the hook's files or input changed before the run"`. Add one `ShellActionRunner`-level test: a fake shell that
  throws `RunDirectoryIntegrityException` for a call with **no** run directory (only stdin) → `HOOK_FILES_TAMPERED`,
  blocking with `failOpen: true`. This pins the stdin case core now documents.
- F4: there is no behaviour change in core, but the docs state the local behaviour as a fact, so a test pins it.
  Add a **required** `LocalShell` test (Unix only, `@EnabledOnOs`). With `hook(true)`, run `sleep 30 & echo $!`, and
  after `execute` returns check that the printed pid is alive (`ProcessHandle.of(pid).map(ProcessHandle::isAlive)`, or
  `kill -0`). Kill it in `finally`. A companion test with a timeout (`sleep 30 & wait`, timeout 200 ms) shows that the
  backgrounded `sleep` is gone afterwards, which pins the "timeout ends the reachable descendants" half.

Quality gates: `./gradlew format`, `./gradlew checkAll`, then `python3 scripts/check-translation-structure.py`,
`check-translation-staleness.py`, `check-doc-links.py` and `check-backlog-registers.py`. Anchors: no heading is
renamed. If §11.7 is added, the links to it are checked.

## 7. Open questions

- **Q1 — Does an unplaceable file name ever deserve the staging fallback for skills?** This design says no (§2.1). It
  is a behaviour change from today for one narrow case: a skill whose file name holds a control character worked on
  `LocalShell` via the run directory, and now it is refused. The task asks to "decide fail-closed semantics consistent
  with EE-109", and refusal is the consistent answer. If the maintainer prefers "skip the file with a WARN", note that
  this changes the content key and makes the run directory differ from what the registry loaded.
- **Q2 — Unicode format characters (bidi overrides, U+2028/2029).** They are not refused, because they do not break
  placement or a manifest. Refusing them would be a spoofing policy. Should the builder also refuse them, or should
  that be left to skill loading if anyone wants it?
- **Q3 — Should the hook guide name `setsid` / `nohup` as the way to outlive the hook on the sandbox?** This design says
  no, because it works around the provider's choice and is not portable. Is that the maintainer's intent with #35 (Q8
  on the sandbox side is still "pending the maintainer")?
- **Q4 — Should "ends what a hook leaves running" become a declared `ShellFeature`?** It is not done here: no caller
  branches on it. Revisit this if a host needs to know.
- **Q5 — EE-114's status.** It stays **열림** because kangwoo/aimon-sandbox PR #35 is not merged. The F1–F4 sub-block is
  marked closed on the core side only. Confirm that this is the intended recording ("record F1–F4 … as closed by this
  work (or reference them from EE-114)": this design does both, under EE-114).
- **Q6 — Should the cap be enforced in the builder** (chosen), or only published? Enforcement is what lets a shell drop
  its check. The cost is that EE-116 can then only make the hook limit smaller than the SPI cap. If the maintainer
  wants EE-116 to be able to raise it, publish without enforcing and say "a caller in core never exceeds it".

## 8. Where the implementation departed from this design

Written after the build, against the working tree on top of `f7c72fef`. The approved body above (§1–§7) is not edited.
Most of it was built as written: the builder's control-character, overlap and cap refusals with escaped messages
(§2.1, §2.3); `RunDirectory.MAX_TOTAL_BYTES` replacing `HookRunFiles.MAX_BYTES` everywhere (§2.3); the "files or input"
wording with no kind enum (§2.2); the `isHook()` / `isIsolated()` paragraph and the corrected guide advice (§2.4); and
the CHANGELOG, backlog and §11.7 records (§2.5). The departures are listed in full.

### 8.1 A C0 control from a local source never reaches the builder

§2.1's table and §5's first row say a `\n` file name "runs on local" today and becomes `STAGING_FAILED` /
`HOOK_DIR_STAGING_FAILED` through the builder. In the code, `LocalFileSystem` checks every path with `PathValidator`,
which refuses `[\x00-\x1F<>:"|?*]`, and `StagedResource.scan` reads each file to hash it. So a local skill or `hooks/`
directory with a C0 control in a name (`\n`, `\r`, `\t`, ESC) already fails **in the scan**, before the builder, and
did before this change too: a `hooks.json` handler that names `AIMON_HOOK_DIR` is `HOOK_DIR_STAGING_FAILED` with the
exception's type as the detail, and a skill does not load (`DefaultSkillRegistry` → "Failed to scan skill"). What a local
source hands the builder is DEL and C1 (U+007F, U+0080–U+009F), which the validator lets through, and a non-local source
(S3/GridFS keys, an in-memory VFS, a hand-assembled resource) can hand it anything.

The builder is as designed — it is the SPI contract, and it is what shells and non-local sources rely on. The tests
follow the code: `SkillHookRunDirectoryTest.unplaceableNameIsRefusedWithoutFallback` uses U+009B (C1 CSI) through the
local control filesystem, `IsolatedShellActionExecutorTest.run_aFileNameARunDirectoryCannotPlace_isNotRun` and
`DeclarativePreToolHookTest.execute_hookFileARunDirectoryCannotPlace_blocksUnlessFailOpen` use DEL, and
`IsolatedShellActionExecutorTest.run_aFileNameWithANewline_isRefusedByTheScan` pins today's `\n` outcome (not
`EXECUTION_FAILED`, not tampering). The CHANGELOG's **Changed** line names the names that newly reach the refusal
instead of "a tab included … ran on `LocalShell`".

### 8.2 Review notes taken

- **The run directory is gone when the hook returns** (review-2, F4). `ExecutionOptions.isHook()`, both hook guides and
  `aimon-skill-extensions.md` say so: a background job cannot read the hook's files after the hook, even on the local
  provider. This strengthens the "hand it to something that is not the hook's process" advice.
- **The control-character check runs first** (review-2, escaping). No other branch of `Builder.file` (confinement,
  duplicate) can then print a control character raw. Test: `RunDirectoryTest.controlCharacterIsCheckedFirst`.
- **The timeout companion test prints the pid** (review-2, F4 test): `sleep 30 & echo $!; wait`, with the pid read from
  `ShellTimeoutException.stdout()` (`LocalShellHookBackgroundJobTest`).
- **The sandbox grep names every run-directory test** (review-2), including `SandboxShellRunDirectoryIT:139` and
  `OpenSandboxWorkspaceIT:244-251`, in the sandbox follow-up note.
- **Nothing in the `hooks.json` load path scans `hooks/`** (review-2): `HookRegistryApplier.scriptDirectoryFor` only checks
  that the directory exists, so the refusal surfaces at firing, as the tests pin.

### 8.3 Open questions moved to the backlog

§2.5 said "no new IDs". Two open questions reach beyond this change and fit no existing item, so they became items:
**EE-117** ← Q2 (Unicode format characters in names, a spoofing policy, not a placement rule) and **EE-118** ← Q4 with Q3
(a `ShellFeature` for "ends what a command leaves running", and whether the guide names `setsid`). The register title
and the backlog index row moved to 118 (35 open · 83 closed). Q6 is recorded in EE-116 as §2.5 says. Q1 and Q5 are
settled here as the body recommends: an unplaceable name is refused with no staging fallback, and EE-114 stays open
with its F1–F4 sub-block closed.

### 8.4 What the design left to the builder

- **Two link targets in §2.5 are written out.** The approved text elided them as `…/issues/33` and `…/pull/35`, which
  `scripts/check-doc-links.py` reads as relative paths that do not exist. They are now the full aimon-sandbox URLs;
  nothing else in §1–§7 changed.
- **The F1 overlap case through the skill path is tested**, with a hand-assembled resource over a filesystem that serves
  both `lib` and `lib/x.py`, as an object store would (`SkillHookRunDirectoryTest.fileAndDirectoryOfOneNameIsRefused`).
- **The F4 line in `aimon-skill-extensions.md`** went into the `AIMON_SKILL_DIR` run-directory bullet, beside "the
  directory does not survive between firings", rather than the skill-call lifetime bullet, which is about a different
  lifetime.
- **`ExecutionOptions.getStdin()`** also says `LocalShell` pipes stdin straight to the command and has nothing to check.
- **`docs/design/tool/execution-environment.md`** needed no change: nothing in it describes the builder's checks, the cap
  or hook background jobs (grepped).
