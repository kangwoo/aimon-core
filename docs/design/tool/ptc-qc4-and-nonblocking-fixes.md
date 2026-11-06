# Design — PTC defect Q-C4 and the open non-blocking findings (items 0–13)

> Status: **IMPLEMENTED** — `aimon-core` (`at.aimon.core.tools.code.shell` — the runtime `aimon_ptc.py`,
> `ShellCodeExecutionEngine`, `ShellProgramRun`, `Utf8Cut`; `at.aimon.core.tools.code` — `ExecuteCodeTool`,
> `ExecuteCodePlan`, `ExecuteCodeToolProvider`, `WarnOnce`; `at.aimon.core.agent.tool.ToolOfferContext`; javadoc of
> `ExecutionEnvironment.shell()` and `VirtualShell.supports`; `InternalPackageBoundaryTest`). The docs this changed are
> [`programmatic-tool-calling-shell.md`](programmatic-tool-calling-shell.md) §3, §6, §8, §11, §14 and
> [`programmatic-tool-calling.md`](programmatic-tool-calling.md) §8.3, §13.1, §13.2, §18, §21.
>
> **[§11](#11-after-the-build--where-the-implementation-departed), appended after the build, is where the build departs
> from this document.** Everything between this header and §11 is the body as approved in design review round 3 (PASS,
> no blocking findings, eight non-blocking notes), kept byte-exact rather than corrected — the house habit in this
> directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its citations (`file:line`) are at
> `2feb5d83`. The review transcripts and the run's `TASK.md` are not in the repository; §11 reproduces what was found
> while building and which review notes were taken. Its open questions that outlive this change are TC-18 – TC-20 in
> [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md).
>
> It is in English, like its siblings in this directory written from an approved run; `docs/design/` is not a
> translation target (`docs/project/documentation-guide.md` §5.1).

Base: `main` @ `2feb5d83` (worktree `herdr/ptc-nonblocking-core`). One PR. Items 0–13 follow TASK.md's numbering.
Every item is a **fix** here. None is closed without a fix.

Sources read: TASK.md; `build-C/review-4.md`, `build-D1/review-1.md` and `HANDOFF.md` of the
`ptc-execute-code-impl` run; `HANDOFF.md` of the `ptc-shell-provider-d2` run; aimon-sandbox
`docs/design/ptc-shell-provider.md` §9 Q-C4 and `workspace-sandbox.md` §20. In this repo: `aimon_ptc.py`,
`ShellCodeExecutionEngine`, `ShellProgramRun`, `ExecuteCodeTool`, `DefaultToolCallBridge`, `ToolOfferContext`,
`ExecuteCodePlan`, `ExecuteCodeToolProvider`, `LlmSkillExecutor`, `InternalPackageBoundaryTest`, `ExecutionEnvironment`,
`VirtualShell`, `CodeExecutionLimits`, the engine contract suite, the shell-engine tests, `CHANGELOG.md`,
`programmatic-tool-calling{,-shell}.md`, and `scope-model.md`.

---

## 1. Problem (one paragraph)

The shell engine's Python runtime (`aimon_ptc.py`) begins with `os.setpgid(0, 0)`. It does this so that, when the
runtime ends a run itself (output limit, protocol violation), it can kill its own process group without killing the
shell wrapper that still has to deliver the result (shell doc §6, step 1). The side effect is that the runtime leaves
the exec's process group. Every kill that aimon-sandbox performs (timeout, cancellation, the S2 line kill) reaches the
exec's process group and nothing beyond it. So after a `TIMED_OUT`, `CANCELLED` or bridge-`LIMIT_EXCEEDED` run, the
model's program and its children keep running until the sandbox goes away. The D2 run observed seven orphaned
`aimon_ptc.py` processes at about 100 % CPU, and the leaked runtime also kept sending `CALL` lines after the bridge had
stopped, which made `bridgeLimitStopsAtOnce` flaky. Core's tests never saw this: `LocalShell` kills by walking the
process tree, not by process group. Around this defect sit thirteen smaller findings from earlier reviews:
- an outcome that hides a limit (1);
- inconsistent null handling (2);
- a mis-wrapped javadoc (3);
- process-global warn-once state (4);
- a sweep age that ignores the configured limit (5);
- a reply write that races the directory delete (6);
- an unbounded `ERR` line (7);
- a CHANGELOG that contradicts the doc (8);
- a cleanup order that differs from the doc (9);
- a missing SPI promise (10);
- a UTF-8 cut (11);
- a package-prefix-only boundary test (12);
- two qualifiers lost from a doc table (13).

---

## 2. Item 0 — Q-C4: the runtime must die with the shell's kill

### 2.1 Requirements

- R1: A kill that reaches the **command's process** must also end the runtime (the model's program, same process) and
  every child the program started. This covers a process-group kill (aimon-sandbox, its testkit provider) and a
  tree-walk kill (`LocalShell`). It must hold for SIGTERM and for SIGKILL.
- R2 (shell doc §6 intent): when the runtime ends the run itself (output limit, violation), it must not kill the shell's
  wrapper. The wrapper still has to finish (exit status, trailer, cleanup of the run directory). `DONE` is sent before
  the self-kill, as today.
- R3: The fix must work on Linux (sandbox) and macOS (developer machines that run the local tiers). It must not depend
  on any provider feature beyond "kill the exec's process group" (WS §9 — execd offers nothing wider).
- R4: The key never sits in a process that does not need it, and no new process carries it in argv.

### 2.2 Chosen approach — an anchor in the shell's group and a watchdog process in the runtime's group

`main()` forks **first**, before it reads `key`, before `prctl` and before any thread exists. The runtime then forks
a **watchdog** before it reads `key` or runs any model code:

```
 shell's process group (the one the provider kills)        runtime's own group
 ───────────────────────────────────────────────────      ──────────────────────────────────────────
 wrapper (bash -c ...)                                     runtime  (child of the anchor; setpgid(0,0))
   └─ python3 -I -u aimon_ptc.py   == ANCHOR  ──fork──▶       reads key, runs main.py, channel on fd 1 dup
        holds write end of pipe P                              ├─ WATCHDOG (forked before key/program; holds
        waits for the runtime                                  │    only P's read end; blocks on read(P);
        then SIGKILLs the runtime's group,                     │    on EOF: killpg(runtime group, SIGKILL))
        exits with its status                                  └─ model's subprocesses (inherit the group)
```

Review 1 found the reason the watcher must be a separate process. A thread inside the runtime has to take the GIL back
before it can call `killpg`. A program inside one long C-level call that holds the GIL (`sum(range(10**12))`,
`10**(10**9)`, a catastrophic `re` backtrack, a C-extension loop) blocks that thread until the call returns. The
reviewer measured this on CPython 3.11: a wake-up of 0.52 s with `while True: pass`, but 15.46 s with
`sum(range(2 * 10**9))`, and unbounded for larger arguments. The watchdog is its own process and runs no model code,
so nothing the program does in the runtime's interpreter can delay it.

- **Anchor (parent of the runtime).**
  - It stays in the shell's process group and keeps the write end of an `os.pipe()`. The pipe is non-inheritable per
    PEP 446, so no `exec`'d process receives it.
  - It does `os.setpgid(child, child)` too: the parent and the child both call it, the classic way to close the race.
  - It waits for the runtime. Once the runtime has exited it SIGKILLs the runtime's group (the watchdog and anything the
    program left behind), then `os._exit`s with the runtime's status: the exit code, or `128 + signal` when the runtime
    was signalled.
  - It never reads `key`, `reply_dir`, `config.json` or `main.py`, so it holds no secret. It runs no model code, so the
    GIL argument does not apply to it.
- **Runtime (child of the anchor).**
  - It closes the write end and does `os.setpgid(0, 0)` (an `OSError` is ignored). A forked child is not a group
    leader, so the call normally succeeds.
  - It derives `leader` from the **result**, `leader = (os.getpgrp() == os.getpid())`, not from whether its own call
    raised. The anchor's `setpgid(pid, pid)` may have done the job already, and the review-2 corner case (the runtime
    leads a group but believes it does not, so it runs without a watchdog outside the shell's group) cannot arise.
  - When `leader` is true it forks the watchdog, then closes its own copy of `P_read`. The watchdog inherits the
    runtime's group.
  - Then it continues exactly as `main()` does today (`_no_dump`, read and delete `key`/`reply_dir`, move the channel,
    run the program).
- **Watchdog (child of the runtime, in the runtime's group).**
  - Single-threaded. Right after the fork it points fds 0–2 at `/dev/null` and closes every other fd except `P_read`
    (`os.closerange`), so it never holds the shell's stdout or anything of the runtime.
  - It is forked before `key` is read, so it holds no secret.
  - Loop: `os.read(P_read, 1)`. On `b""` (EOF) it calls `os.killpg(runtime_pgid, SIGKILL)`, which kills the watchdog
    too, then `os._exit` in case the kill failed.
  - It never sees EOF while the anchor lives: the anchor holds the write end until it exits, and the anchor exits only
    after the runtime.
- **The shell's kill (R1).**
  - A process-group kill reaches the anchor. Whether it is SIGTERM (default action) or SIGKILL, the anchor dies, the
    kernel closes its pipe end, and the watchdog's `read` returns EOF.
  - The watchdog kills the runtime's group: the runtime, the program's children and itself. This happens within a
    scheduling quantum, whatever the program is doing, a GIL-holding builtin included.
  - A tree-walk kill (`LocalShell`) reaches the anchor, the runtime, the watchdog and the children directly.
- **The runtime's own end (R2).**
  - `_end_process` is unchanged: send `DONE`, then `killpg` of the **runtime's** group (the watchdog included).
  - The anchor is in the shell's group and survives. Its wait returns, it sweeps the (already empty) group and exits
    with `128 + 9`.
  - The wrapper sees the anchor's exit and finishes normally. This is the same exit status the wrapper saw before,
    because the old runtime killed itself too.
  - The output watcher (`_watch`) is still a thread in the runtime. A program holding the GIL in one builtin cannot
    write output through Python meanwhile. A child process writing output while the runtime holds the GIL delays only
    the runtime's own stop. The host's backstop (decoded `OUT` bytes) and the wall clock both end the run through the
    shell, and from there the watchdog takes over. No new gap.
- **Normal completion.** The runtime sends `DONE` and calls `os._exit(0)`. The anchor then SIGKILLs the runtime's group,
  so neither the watchdog nor a background process the program did not wait for outlives the run. The anchor exits 0.
  The background-process part is a behaviour change, called out in §2.5.
- **Group-id reuse when the anchor sweeps.** POSIX does not reuse a pid while a process group with that id still exists.
  - Where `os.waitid` exists (Linux, the sandbox target) the anchor waits with `waitid(P_PID, pid, WEXITED | WNOWAIT)`,
    calls `killpg`, then reaps. The unreaped zombie keeps the id taken, so the sweep cannot reach anyone else.
  - `os.waitid` is **absent** on macOS CPython (the reviewer checked 3.11.12), so developer machines reap first, then
    call `killpg`. That is still safe after a normal completion: the watchdog is a live member of the group until the
    sweep, so the group, and with it the id, still exists when `killpg` runs. Only after a self-end (the runtime already
    killed the whole group) can the group be empty at reap time. For that case the anchor follows a rule instead of a
    probability argument (review 2): **without `waitid`, the anchor skips its post-reap `killpg` when the runtime died
    of `SIGKILL`.** A runtime killed by `SIGKILL` either swept its own group (`_end_process`), or was killed from
    outside by a group or tree kill that also reached the group's other members. Either way nothing is left for the
    anchor to sweep, so it never signals an id that may already be free. In every other case (exit code, other
    signals) the watchdog is still alive, so the group exists. With `waitid` (Linux) the zombie holds the id, and the
    anchor always sweeps. Q5 is settled with this.

Illustrative shape (not final code):

```python
def main():
    read_end, write_end = os.pipe()          # non-inheritable
    pid = os.fork()
    if pid:                                  # anchor: stays in the shell's group, holds no secret
        os.close(read_end)
        _anchor(pid)                         # setpgid(pid, pid); wait; killpg(pid); reap; os._exit(status)
    os.close(write_end)
    try:
        os.setpgid(0, 0); _state["leader"] = True
    except OSError:
        _state["leader"] = False
    if _state["leader"]:
        if os.fork() == 0:                   # watchdog: no secret, no model code, single thread
            _watchdog(read_end, os.getpgrp())  # fds 0-2 -> /dev/null, closerange; read until EOF; killpg; _exit
        os.close(read_end)
    ...                                      # unchanged from today: _no_dump, key, reply_dir, channel, program
```

Failure handling inside the runtime:

| Case | Behaviour |
|---|---|
| The first `os.fork()` (anchor/runtime split) fails (`OSError`, e.g. a pid limit) | Fall back to today's single-process path **without** `setpgid`. The runtime stays in the shell's group, so the shell's kill reaches it (R1). A self-end kills only this process (`leader=False`), so children may survive a self-end in that rare case. A note goes to stderr (the log). |
| The runtime's `setpgid` fails | `leader=False`, no watchdog: the runtime and its children stay in the shell's group, so the shell's kill reaches them all directly (R1, no GIL dependence). A self-end cannot kill the group without killing the wrapper, so it kills only itself (today's `leader=False` path). The anchor checks `os.getpgid(pid) == pid` before its sweep and skips it when the check fails. |
| The watchdog fork fails | The runtime **does not run the program**. It still does every setup step that sending needs: read and delete `key` and `reply_dir`, move the channel to its own fd, redirect fds 1 and 2. Only then does it send `DONE{"status":"error","error":"the runtime could not start its watchdog: …"}` through the normal `_finish` path, and exit. It records the fork error in `_state`, and `main()` checks it after the channel exists, right before `_run_program`. An implementation must not exit before the channel exists, or the host would only see "the runtime ended without finishing". The anchor sweeps and exits. The host classifies it as `SCRIPT_ERROR` with that message. Running without a watchdog would reopen Q-C4 for GIL-holding code, and a pid limit that refuses a second fork is something the operator has to see. |
| The anchor dies before the watchdog reaches `read` | EOF on a pipe is sticky, so the first `read` returns EOF at once and the watchdog kills the group. That group may already hold the runtime before it has read `key`. That is correct: the shell call is over. |
| The program kills the watchdog or closes nothing it can reach | Same uid: this is not a boundary (§7 of the shell doc). Killing the watchdog only disables its own run's cleanup on a shell kill; the anchor's end-of-run sweep still runs if the anchor survives. The best-effort note stays. |
| A child of the program calls `setsid`/`setpgid` | Out of reach, as before (§8 "best-effort"). |

### 2.3 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| **Drop `setpgid`; the runtime stays in the shell's group** | Fixes R1, but a self-end (output limit, violation) can then either kill the whole group, which takes the wrapper with it and breaks R2, or kill only itself, which leaves the program's children running. The children could be put in a group of their own by patching `subprocess.Popen`, but `os.fork`, `os.system`, `pty` and C extensions bypass that. |
| **Watch the parent** (`getppid()` changes → the shell is gone; Q-C4's second suggestion) | Polling adds up to one interval of latency. It also proves the right thing only when the immediate parent is in the killed group. That holds for execd's `bash -c`, but it is an assumption about the provider's wrapper shape. The anchor *is* the process the shell started, so "the shell's kill reached the command" is exactly what wakes the runtime, whatever the wrapper looks like. |
| **`prctl(PR_SET_PDEATHSIG, SIGKILL)` in the runtime** | Linux only (R3). Worse, it SIGKILLs the runtime **process** before it can `killpg` its group, so the program's children are orphaned. That is the very defect being fixed. |
| **A wider kill on the provider side** (session or cgroup) | aimon-sandbox's D2 design ruled it out: execd ends process groups only, and the image contract cannot promise more (provider doc §9 Q-C4: "does not work around it"). |
| **The pipe watcher as a daemon thread inside the runtime** (this design's first version) | It needs the GIL to call `killpg`. A program inside one long GIL-holding C call delays it until the call returns, possibly forever (review 1 measured 15 s for `sum(range(2 * 10**9))`). That is the Q-C4 symptom again. |
| **Linux `PR_SET_PDEATHSIG` on the watchdog as a second trigger** | The watchdog's parent is the runtime, not the anchor, so the death signal would fire on the wrong event. It is also Linux only, and the pipe EOF already covers every way the anchor can die. |
| **A SIGTERM handler in the anchor that kills the runtime's group** | It adds nothing: SIGKILL cannot be caught, so the pipe EOF is needed anyway, and it covers SIGTERM too. One mechanism, not two. |

### 2.4 Host-side hardening (the second Q-C4 symptom)

The fix kills the runtime, but asynchronously. On OpenSandbox, lines already in execd's spool (read every 100 ms) can
still arrive after the stop. To make "stop" mean stop on the host too:
- `ShellProgramRun` gains a `stopping` flag. It is set in each of the three ways a run ends from outside:
  - when it calls `stop` itself (violation, output limit, reply failure);
  - when the request's cancellation trips: the engine registers `signal.onCancel(run::markStopping)` beside
    `stop::cancel`;
  - when the **wall clock** runs out (review 2). `ShellProgramRun` is given the run's deadline (`System.nanoTime()` at
    the start plus `request.getWallClock()`), and `CALL` lines handled after it count as stopping. No timer is needed:
    the check happens on the line-handling path, which is the only place it matters.
- After `stopping`, `CALL` lines are still verified (MAC and seq), so a violation is still caught, but they are **not
  forwarded** to the bridge and get no reply. `OUT`/`ERR`/`DONE` are processed as before, so partial stdout survives
  (§12.1).
- This makes `bridgeLimitStopsAtOnce` deterministic on every tier: no call after the stop reaches the bridge.

### 2.5 Behaviour change to call out

On normal completion the runtime's process group is now killed: background processes the program started and did not
wait for no longer survive `ExecuteCode`. Reasons:
- §13.2 already says every task of the script stops at its end.
- `Bash` with `run_in_background` is the tool for long-lived processes.
- A process the program left behind could keep writing into the workspace after the observation was taken.

This goes in the CHANGELOG and in shell doc §8. If the user disagrees, the anchor can skip the sweep when the runtime
exited 0 (§10 Q1).

### 2.6 Doc changes for item 0 (shell doc, Korean original; it has no translation)

- §6 start steps:
  - Step 1 becomes "fork: the parent (the anchor) stays in the shell's process group, holds one end of a pipe and waits
    for the runtime. The child makes its own process group and becomes the runtime."
  - New steps:
    - "before reading the key, the runtime forks a watchdog into its group. The watchdog holds only the pipe's read end
      and, when the anchor dies (a shell kill), kills the runtime's group. It is a separate process, so model code
      holding the GIL cannot delay it";
    - "the anchor kills the runtime's group when the runtime ends, then exits with its status".
  - Keep the intent sentence: the shell wrapper survives a self-end to finish the result.
- §8: replace "`setsid` 로 빠져나간 자손은 셸이 끝내지 못한다" with the two guarantees (a shell kill reaches the whole
  runtime group; at the run's end the anchor kills the group) plus the remaining best-effort note (`setsid`/`setpgid`
  children of the program).
- §11: "자기 프로세스 그룹" → the runtime's process group, not the shell's.
- Status header: record that Q-C4 is fixed in core. aimon-sandbox's WS §20 item and the two tests disabled on its
  testkit tier are a follow-up there (§10 Q3).
- Main doc §21 D1 table, row "감시 스레드는 … 자기 프로세스 그룹을 끝낸다": add that the group is the runtime's own,
  under an anchor in the shell's group. Add a new row for Q-C4 (the anchor and watchdog design, why the watchdog is a process (the GIL), and the normal-completion
  sweep).
- `aimon_ptc.py` module docstring: describe the three processes (anchor, runtime, watchdog).

---

## 3. Items 1–13

### Item 1 — a caught quota/call-limit refusal must not report `COMPLETED`

**Where:** `ExecuteCodeTool.java:232-236`.

**Fix:** a bridge limit (`bridge.limitExceeded()`, which is set only by the call limit and the fork quota, both of which
call `stop()`) overrides every engine status **except `PROTOCOL_VIOLATION`**:

```java
if (limit.isPresent() && outcome.getStatus() != CodeExecutionStatus.PROTOCOL_VIOLATION) { ... LIMIT_EXCEEDED ... }
```

- Today's guard (`!= COMPLETED`) also *replaced* `PROTOCOL_VIOLATION` with the retryable `LIMIT_EXCEEDED`. That dropped
  the ERROR log and the not-retryable mark (decision 7). This change fixes that too.
- The `MUTATING` write cap does not set `limitExceeded`, so a program that catches a write-cap refusal still completes.
  That is intended (§8.3: the cap refuses later calls, it does not stop the program).
- stdout is kept: `LIMIT_EXCEEDED` shows `[output before the stop]` (§12.1).

**Doc:** PTC §8.3 quota row and §13.1 call-limit row gain the sentence "프로그램이 거절을 잡고 끝까지 돌아도 결과는
`LIMIT_EXCEEDED` 다".

**Tests (`ExecuteCodeToolTest` with `ScriptedEngine`):**
- An engine that calls past the limit, swallows the refusal and returns `COMPLETED` → `LIMIT_EXCEEDED`, error result,
  retryable, stdout under `[output before the stop]`.
- The same with the quota.
- An engine returning `PROTOCOL_VIOLATION` with the limit set → stays `PROTOCOL_VIOLATION`, not retryable.

### Item 2 — inconsistent null-descriptor guard in `ToolOfferContext`

**Contract:** `Tool.describe` "never null". `RoutineExecutor`, `SingleToolInvoker` and the approval gate already rely
on that.

**Fix:**
- Route all three internal call sites (`offer()`, `eligible()`, `codeHosted()`) through one private
  `describe(Tool)` that returns `tool.describe(this)`.
- Remove the lone `described != null` guard, so every path behaves the same.
- The javadoc of `offer()`/`eligible()` says a tool that breaks the contract fails the offer with an NPE naming the
  tool: `Objects.requireNonNull(d, () -> tool.getName() + ".describe returned null")`.
- Rejected: treating null as "not offerable" or as `ToolDescriptor.of(tool)`. Either would silently hide a broken
  override and diverge from every other reader of `describe`.

**Check during the build:** the two tests that `mock(Tool.class)` (`SingleToolInvokerTest`,
`SubagentBehaviorRunnerTest`) may reach `codeHosted()` through the guard. If they fail, stub `describe` in those tests
(`thenAnswer(i -> ToolDescriptor.of(tool))`). Do not restore the guard.

**Test:** `ToolOfferContextTest` — a tool whose `describe` returns null fails `offer()`, `eligible()` and the
code-only check alike, with the tool's name in the message.

### Item 3 — `LlmSkillExecutor:385` javadoc wrapped mid-sentence

**Fix:** rewrap by hand so that "the tool execution manager" stays one phrase.

**Verify:** run `./gradlew format` and check that Eclipse's javadoc formatter does not re-break it. That formatter
produced the artifact in the first place, at the `{@code ExecuteCode}` boundary. If it does re-break it, reword the
sentence, for example moving the parenthetical, until the formatted output reads correctly.

### Item 4 — warn-once sets are process-global statics

**Rule applied:** scope-model — state lives with its owner.

| Set | Owner after the fix | Why |
|---|---|---|
| `ExecuteCodeToolProvider.WARNED` (agent has `programmatic-tools`, no engine) | an instance field of the provider | The provider is application-scoped: built once at assembly, one per stack. Two stacks in one JVM (tests, embedding) now each warn, and the set goes away with the stack. |
| `ExecuteCodePlan.WARNED` (unavailable reason, scope widening, left-out tool) | a `WarnOnce` instance owned by the provider and handed to every `ExecuteCodeTool` it registers, which passes it to `ExecuteCodePlan.of(...)` | The plan is computed per call and has no owner of its own. The tool is agent-scoped and rebuilt on runtime eviction, so a set on the tool would warn again on every rebuild. The provider is the longest-lived owner on the path. Public `ExecuteCodeTool` constructors (used without a provider) create their own instance, so warnings are once per tool. |
| `ToolOfferContext.WARNED_CODE_ONLY` (mutating tool named `programmatic-only`) | **stays static, bounded, justified in a comment** | `ToolOfferContext` is a per-iteration value. The only longer-lived owner it reaches is the `ToolRegistry`, which is an SPI interface: giving it a warn set would add a method to every implementation for a log line. Follow the in-tree precedent `DefaultShellActionExecutor.WARNED_FALLBACKS`: a cap (256 keys, then DEBUG), a comment saying why it is static (dedupes operator log lines only, no behaviour depends on it, bounded), and a package-private `resetCodeOnlyWarnings()` for tests. |

`WarnOnce` is a tiny final class in `at.aimon.core.tools.code`, package-private. It wraps a concurrent set with a cap
(1,024 keys; past the cap it logs at DEBUG) and has a method `boolean first(String key)`.

**Tests:**
- Two providers in one JVM each log their warning once (log capture; the existing tests show which appender helper to
  use).
- `ExecuteCodePlan` warnings through two tools from one provider are logged once.
- The `ToolOfferContext` warning is logged once until reset.

### Item 5 — sweep age fixed at 20 min

**Problem:** the probe's `find … -mmin +20` cannot see the configured `maxWallClock`. `probe(ExecutionEnvironment)` is
an SPI method with no limits, and the `ServiceLoader` factory has no arguments.

**Fix:** move the sweep out of the probe and into the **runtime**, with the age taken from the run's own request.
- `config.json` gains `sweep_age_seconds` = `max(2 × request.getLimits().getMaxWallClock(), 20 min)`.
  - The first term is §8's "twice the wall clock limit". A run's wall clock never exceeds `maxWallClock`, because
    `wallClockFor` caps it and `EXECUTION_DEADLINE` only shortens it.
  - The 20-minute floor is the old fixed age, so the default configuration (600 s → 20 min) behaves as before. It keeps
    a small configured limit (`CodeExecutionLimits` has no minimum) from sweeping a run directory that another run or
    node has written but not yet started on a slow sandbox (review 1, non-blocking).
- **The sweep root, exactly (review 2).**
  - The runtime handles two unrelated directories:
    - `RUN_DIR` = `dirname(__file__)`, which the **shell** placed through `RunDirectory`. Under `LocalShell` it is a
      temp directory in `java.io.tmpdir`, so its parent is the system temp directory.
    - The **reply directory** whose path the host writes into the `reply_dir` file. That path is
      `<working dir>/.aimon-ptc/<hex>`, and the host writes replies to its `r/` subdirectory.
  - The sweep root is `SWEEP_ROOT = os.path.dirname(<the value read from reply_dir>)`, after stripping a trailing `/`.
    It is **never** derived from `RUN_DIR`, `__file__` or the working directory. The code names the variables
    `reply_root`/`sweep_root`, and a comment says why `RUN_DIR` must not be used.
  - **Fail-safe guard.** The sweep runs only when all of these hold; otherwise it does nothing (no log line needed, it
    is housekeeping):
    - `os.path.basename(SWEEP_ROOT) == ".aimon-ptc"` exactly;
    - `os.lstat(SWEEP_ROOT)` is a directory and not a symlink;
    - the runtime's own entry (`basename(reply_dir value)`) exists in it.

    So a malformed `reply_dir`, a relative path, or an implementation slip that reaches for `RUN_DIR` (whose parent is
    named `T`, `tmp` or similar, never `.aimon-ptc`) sweeps nothing.
- At startup, after reading `reply_dir`, the runtime starts a daemon thread that lists `SWEEP_ROOT`.
- It skips its own entry and every `probe-*` entry. A probe file lives only between the probe's `writeNew` and its
  `test -f`, and removing it there would make the probe exit 4, which `ShellCodeExecutionEngine` **remembers** as a
  definite "unavailable" for the environment object. A probe file that a crashed probe left behind is a few bytes;
  leaving it is harmless.
- For every other entry whose `lstat` mtime is older than the age, it removes the entry:
  - a symlink or plain file → `os.unlink`;
  - a directory → `shutil.rmtree(ignore_errors=True)`, which is fd-based and does not follow links on Linux.
- Errors are ignored: the sweep is housekeeping.
- The probe command loses its `find` line. `DEFAULT_SWEEP_AGE` and the `sweepAge` field go away.

**Why this shape:**
- The age now follows the configuration of the run that sweeps, so a live run of the same configuration is never older
  than the age.
- No host-side state per environment object is added, so the scope-model line "셸 엔진이 호출을 넘어 드는 것은 탐침
  결과뿐" stays true and scope-model is untouched.
- No extra shell exec is added: the probe used to carry the sweep, and now each run does a `listdir` plus `lstat` on a
  handful of entries.

**Rejected:**
- Passing limits into the probe: changes the SPI signature.
- Remembering the largest wall clock the engine has seen: the first probe in a fresh process still uses the default,
  which is the bug.
- Sweeping from the host in `execute`: an extra shell exec per environment, plus new per-environment state.
- A heartbeat (the runtime touches its directory every minute and the sweep age becomes a constant): it removes the
  dependence on the limit altogether, but adds a liveness protocol with its own failure mode (a stalled watcher gets a
  live run swept). The task asked for the age to come from the limit.

**Residual (documented):** two processes with *different* `maxWallClock` settings above 10 min that share one sandbox (multi-node,
differently configured) can still sweep each other's longer run. Recorded in shell doc §8 and main doc §21 (§10 Q2).

**Doc:** main doc §21 D1 row "오래된 `.aimon-ptc/` 항목의 정리" is rewritten: each run's runtime sweeps the reply
directory's parent `.aimon-ptc/`, and only that, under the basename and no-symlink guard. The age is
`max(2 × maxWallClock, 20 분)`, and `probe-*` entries are skipped. Shell doc §8 says the same, floor included, so the
docs and the code agree.

**Tests:**
- `ShellCodeExecutionEngineTest:69` (the probe sweeps) is replaced by a runtime test. Pre-create
  `.aimon-ptc/<old>` with a back-dated mtime (`Files.setLastModifiedTime`) and `.aimon-ptc/<fresh>`. Run a program
  with the default limits (age 20 min) and an old entry dated 25 min ago: the old entry is gone after the run, the
  fresh one stays.
- A second case with `maxWallClock` 30 min (age 60 min) and an entry 25 min old: the entry stays. This is the review's
  scenario, and it fails on the old code.
- A third case with `maxWallClock` 5 s: an entry 1 min old stays (the floor).
- An old `probe-<hex>` entry stays.
- **Wrong-root guard (review 2).** Put an old sibling (back-dated 25 min) **next to the shell-placed run directory**.
  The test finds `RUN_DIR`'s parent from inside the program (`os.path.dirname(os.path.dirname(__file__))` written to a
  file) and creates the sibling from the test. To keep this from touching the real `java.io.tmpdir`, run this test with
  a `LocalShell` whose run directories go under a JUnit `@TempDir` if `LocalShell` allows it. Otherwise create the
  sibling with a unique name in that parent and delete it in `@AfterEach`. The sibling must survive the run.
- A reply-directory value whose parent is not named `.aimon-ptc` (a test-only engine hook writes a different
  `reply_dir`), with an old entry beside it: nothing is swept.
- A `.aimon-ptc` that is a symlink to a directory with an old entry: nothing is swept.
- A symlink entry pointing outside is unlinked without touching its target.

### Item 6 — a reply write can race `close()` and the directory delete

**Fix (in `ShellProgramRun`):** drain the in-flight writers.
- One monitor guards `closed` and an `inFlight` counter.
- Each write **attempt** does: `synchronized(lock) { if (closed) return; inFlight++; }`, then `files.writeNew(...)`
  outside the lock, then in `finally`: `synchronized { inFlight--; lock.notifyAll(); }`. The retry sleep stays outside
  the counted section.
- `close()`: `synchronized { closed = true; wait until inFlight == 0 or REPLY_DRAIN (5 s) elapses; }`.
- When the drain actually waited, `close()` logs the measured wait at DEBUG. That gives the 5 s bound (§10 Q6) a number
  to be tuned against later.
- On a drain timeout `close()` logs WARN ("a reply write was still in flight; the run directory may stay until a later
  sweep") and returns. The engine then deletes; a failed delete already logs WARN, and the next run's sweep (item 5)
  removes the directory.
  - The residual is small. `RuntimeFileSystem.writeNew`'s contract says "the parent directory must exist", and the local
    implementation creates no parents. So a late write that lands after a *successful* delete fails instead of
    recreating the tree. Only a write that lands while the delete is still running can leave a file, which then fails
    the delete (WARN) and is swept later.
- Bounded on purpose: `RuntimeFileSystem.writeNew` has no timeout of its own, and a hung sandbox must not hang
  `ExecuteCode` past its wall clock.

`close()` is called in the engine's `finally` before `deleteQuietly` (unchanged), so once `close()` returns, no new write
starts and every started write has finished (barring the timeout).

**Test (`ShellEngineProtocolTest` or a focused `ShellProgramRunTest`):**
- Use a `RuntimeFileSystem` double whose `writeNew` blocks on a latch.
- Start a reply write, then call `close()` on another thread. `close()` must not return while the write is blocked,
  and must return once the latch is released.
- A write that begins after `close()` never reaches `writeNew`.
- With a write that never finishes, `close()` returns after the drain bound and logs WARN.

### Item 7 — the `ERR` tail as one line can pass 64 KiB

**Runtime fix:** send the encoded tail in chunks of `OUT_CHUNK_BYTES` (32 KiB raw, about 43.7 KiB base64, under the 64
KiB line limit). This uses the same loop as `OUT`.

**Host fix:** `ShellProgramRun.log()` accumulates the decoded **bytes** of all `ERR` lines in a buffer bounded to
`min((long) maxLogChars × 4 + 4, maxScriptOutputBytes)` bytes, trimmed from the front. The product is computed in
`long` and clamped by the output cap, which also bounds what the runtime can send, so an absurd `maxLogChars` cannot
overflow it. `logs()` decodes once and returns the last `maxLogChars` chars.
- It skips leading UTF-8 continuation bytes left by the front trim, and drops an incomplete trailing sequence (see
  item 11).
- A multi-byte character split across two `ERR` chunks therefore decodes correctly. Today each line is decoded on its
  own.
- Runtime side, `_read_tail` (review 2): after its seek, it drops leading UTF-8 continuation bytes (`0b10xxxxxx`, at
  most 3) before decoding. A tail that starts in the middle of a character then yields no U+FFFD.

**Test:** a program writes about 40,000 non-ASCII characters to stderr with `maxLogChars` = 50,000. The tail arrives
whole (more than one `ERR` line), with no U+FFFD. Today the line exceeds 64 KiB, `_Channel.send` returns 0 and the log
is empty.

### Item 8 — CHANGELOG "No breaking public-API change" vs shell doc §3 "호환을 깨는 변경"

The truth is that both are partly right: the change is source- and binary-compatible, and it is a **behavioural contract
change for `VirtualShell` implementations**. A shell that streams must stream `isolated` calls, and must cap a line
before materializing it.

**Fix:**
- CHANGELOG D1 section: replace the bullet with "**No source- or binary-breaking public-API change.** One behavioural
  contract change for `VirtualShell` implementors: …". The existing sentence on line 43 moves into this bullet. This
  follows the CHANGELOG's precedent wording "Changed (behaviour change)".
- Shell doc §3: retitle the section to "호환성 변경과 추가". Add a column "종류" with values `동작 계약 (구현자)`, `추가`
  and `추가 (SPI 기본 메서드)` for each row.
- Shell doc status header: "공개 API 호환을 깨는 항목이 있다(§3)" → "소스 호환은 깨지 않는다. `VirtualShell` 구현자에게는
  동작 계약 변경이 하나 있다(§3)".
- The main PTC doc's status still says "호환을 깨는 항목이 있다(§5)". That is true for pieces B and C (§5.1), so it is
  untouched.

### Item 9 — bridge-close vs run-directory-delete order differs from shell §8

**Decision:** change the doc to match the code, plus item 6's drain. Do not move `bridge.close()` into the engine.
- The bridge is created and owned by `ExecuteCodeTool`.
- Its `close(remaining)` waits for nested calls up to the remaining wall clock and returns the "still running" names
  for the observation (§13.2).
- Closing it inside the engine would take that wait and that report away from their owner and change the SPI contract
  (an engine does not close what it was handed).
- The invariant §8 actually needs is "nothing is written into the sandbox after the run directory is deleted". The
  only writer into the sandbox is `ShellProgramRun`, and its gate (`close()` with drain) closes before the delete.

**Doc (shell §8):**
- New text: "실행이 끝나면(성공·실패·취소 모두) 엔진은 먼저 실행의 응답 쓰기를 닫고 — 이미 시작한 쓰기는 끝날 때까지
  (상한 5 초) 기다린다 — 그 **뒤에** `.aimon-ptc/<run>/` 을 지운다. 브리지는 엔진이 돌아온 뒤 `ExecuteCode` 가
  `CLOSED` 로 둔다. 그 사이에 끝난 중첩 호출의 결과는 실행이 이미 닫혀 있으므로 응답 파일로 쓰이지 않는다".
- Main doc §13.2, row "브리지 `CLOSED`": add that for the shell engine the run's own gate closes before the bridge.
- Main doc §21: a D1 deviation row with the reason (the bridge's owner and the wait).

**Test:** covered by item 6's test, plus an engine-level assertion in `ShellEngineProtocolTest`. With a held bridge
call, cancel the run: the reply directory is deleted, and after the held call then completes, no reply file appears
(the directory stays absent).

### Item 10 — the `shell().supports()` no-provisioning promise is missing from the SPI javadoc

**Fix (javadoc only):**
- `ExecutionEnvironment.shell()`: "Returning the shell provisions nothing. An environment that provisions lazily does so
  on the first command or file operation, never here, because an offer asks for the shell on every iteration
  (`programmatic-tool-calling-shell.md` §4)."
- `VirtualShell.supports(ShellFeature)`: "A static answer: it runs no command, touches no file and provisions nothing.
  Offers call it on every iteration." This matches the sentence `runtimeFileSystem()` already has.

`LocalShell` and the in-tree shells already comply, so there is no code change and no test. The aimon-sandbox provider
is held to it by the javadoc (§10 Q3 follow-up).

### Item 11 — a cut at a byte cap can split a UTF-8 character

**Where:**
- `ShellProgramRun.output()` cuts at `maxOutputBytes - stdout.size()` bytes.
- The runtime's `_read_head(out, limit)` cuts at `limit` bytes.
- `stdout()` then decodes the bytes, so a split character becomes U+FFFD.

**Fix:** a package-private helper in `at.aimon.core.tools.code.shell`, `Utf8Cut.completeLength(byte[], int len)`. It
returns `len` minus an incomplete trailing UTF-8 sequence, looking at most 3 bytes back.
- `stdout()` decodes `[0, completeLength)`. The same helper serves item 7's `logs()`.
- It is applied at decode time on the whole buffer, so it also covers a cut that fell inside an earlier chunk boundary
  and the runtime-side cut, without needing to know who cut.
- A program that writes genuinely invalid UTF-8 at the very end loses at most 3 bytes it would otherwise have seen as
  U+FFFD. That is acceptable for an observation.

**Tests:**
- Unit tests of `completeLength` for 2-, 3- and 4-byte sequences cut at each offset.
- A program printing `"한" * N` with `maxScriptOutputBytes` not divisible by 3 → `LIMIT_EXCEEDED`, and stdout ends with
  a whole `한`, with no U+FFFD.

### Item 12 — `InternalPackageBoundaryTest` matches by package prefix only

**Fix:** also require the importing source to be in a module that **declares** the internal package.
- First pass: build `Map<String, Set<String>> declaringModules` (internal package → modules) from every main source
  whose package is `X.internal` or starts with `X.internal.`.
- Second pass: an import of `owner.internal…` is accepted only if the importer's package is in `owner`'s tree (as today)
  **and** the importer's module is in `declaringModules(owner + ".internal")`.
- An internal package declared nowhere in the repo is a violation ("internal to a package this repository does not
  declare").
- The `ALLOWED` escape hatch and its "every entry must match" check are unchanged.
- Factor the decision into a static method `violation(importerModule, importerPackage, imported, owner,
  declaringModules)` and unit-test it with synthetic cases:
  - same module and tree → ok;
  - another module under the same prefix (`aimon-graaljs` file in `at.aimon.core.foo` importing
    `at.aimon.core.internal.X`) → violation;
  - another tree → violation.
- Today's tree passes: every `at.aimon.core.*.internal` import comes from `aimon-core`, every `at.aimon.graaljs.internal`
  import from `aimon-graaljs`, and the one cross-module `session.routing.internal` import is the `ALLOWED`
  `aimon-session-testkit` entry. That was checked by grep at the base.
- Update the class javadoc ("by nothing else" → "by nothing else, and only from the module that holds it").

### Item 13 — PTC doc §18 lost two baseline qualifiers

**Fix:** restore the wording from `74719a56` and earlier into today's two-column table:
- "프롬프트 캐시 — PTC 를 켠 세션**과 끈 세션**의 캐시 적중률".
- "지연 — 샌드박스**에서** 중첩 호출 하나**와 응답 쓰기 하나의 p50/p99**, 순차 **N** 호출의 총 시간".

There is no translation of this doc, so there is no structure check to satisfy beyond the existing doc scripts.

---

## 4. Concrete changes by file

| File | Change | Items |
|---|---|---|
| `modules/aimon-core/src/main/resources/at/aimon/core/tools/code/shell/aimon_ptc.py` | fork into anchor and runtime, and the runtime's watchdog fork; `_anchor`, `_watchdog`; fork-failure handling; docstring; chunked `ERR`; `_sweep(age)` thread from `config.json` | 0, 5, 7 |
| `…/tools/code/shell/ShellCodeExecutionEngine.java` | probe loses the `find` sweep, `sweepAge`/`DEFAULT_SWEEP_AGE` removed; `config()` adds `sweep_age_seconds`; register `run::markStopping` on the request signal; javadoc of the cleanup order | 0, 5, 9 |
| `…/tools/code/shell/ShellProgramRun.java` | `stopping` flag (no CALL forwarding after it); in-flight counter and bounded drain in `close()`; `ERR` bytes buffer; `Utf8Cut` at decode | 0, 6, 7, 11 |
| `…/tools/code/shell/Utf8Cut.java` (new, package-private) | `completeLength(byte[], int)` | 7, 11 |
| `…/tools/code/ExecuteCodeTool.java` | limit overrides every status but `PROTOCOL_VIOLATION`; package-private constructor taking `WarnOnce` | 1, 4 |
| `…/tools/code/ExecuteCodePlan.java` | `of(...)` takes a `WarnOnce`; static `WARNED` removed | 4 |
| `…/tools/code/ExecuteCodeToolProvider.java` | instance `WarnOnce` for both its own and the plans' warnings | 4 |
| `…/tools/code/WarnOnce.java` (new, package-private) | bounded once-set | 4 |
| `…/agent/tool/ToolOfferContext.java` | one `describe(Tool)` helper, no lone null guard; `WARNED_CODE_ONLY` bounded with justification and test reset | 2, 4 |
| `…/skill/execution/llm/LlmSkillExecutor.java` | javadoc rewrap | 3 |
| `…/environment/ExecutionEnvironment.java`, `…/shell/VirtualShell.java` | javadoc: no provisioning | 10 |
| `modules/aimon-core/src/test/java/at/aimon/core/architecture/InternalPackageBoundaryTest.java` | module-membership rule and its unit cases | 12 |
| tests (see §7) | new and changed tests | all |
| `docs/design/tool/programmatic-tool-calling-shell.md` | status header, §3, §6, §8, §11 | 0, 5, 8, 9 |
| `docs/design/tool/programmatic-tool-calling.md` | §8.3, §13.1, §13.2, §18, §21 rows (Q-C4, sweep, cleanup order, outcome rule) | 0, 1, 5, 9, 13 |
| `CHANGELOG.md` [Unreleased] | D1 bullet rewording (8); new section "Fixed: …" (below) | all |

Neither PTC doc has a translation (`*.en.md` absent). `scope-model.md` and its `.en.md` are untouched; §3 item 5
explains why. If an implementer does touch scope-model, the `.en.md` must change in the same commit.

CHANGELOG — a new section at the top of [Unreleased], "Fixed: the shell engine's runtime no longer outlives the
shell's kill (Q-C4), and D1/C review follow-ups". Bullets:
- the anchor process (and the normal-completion sweep, as a behaviour change);
- a caught limit is `LIMIT_EXCEEDED`, and a violation is no longer masked by a limit;
- the sweep age follows `maxWallClock`;
- reply writes drain before the directory delete;
- `ERR` chunking;
- UTF-8-safe cuts;
- per-provider warn-once;
- `InternalPackageBoundaryTest` module rule (build-only, no API).

---

## 5. Data and interface shapes that change

| Shape | Before | After |
|---|---|---|
| Runtime process structure | one process, its own process group | anchor (shell's group, no secret) → runtime (own group) → watchdog (runtime's group, no secret, no model code) and the program's children (runtime's group) |
| Runtime exit status seen by the shell | runtime's own (0, or -9 after a self-kill) | anchor's: the runtime's code, or `128 + signal`; equivalent to before |
| `config.json` | `tools`, `max_output_bytes`, `max_reply_bytes`, `max_log_chars`, `wall_clock_seconds` | + `sweep_age_seconds` (number). The runtime treats it as absent → no sweep, so an older `config.json` stays valid |
| Channel | one `ERR` line | 0..n `ERR` lines (the host already accepted several; the protocol table says "`ERR` 줄") |
| Probe command | includes `find … -exec rm -rf` | no sweep |
| `ExecuteCodeTool` constructors | public ones unchanged | + one package-private constructor taking `WarnOnce` |
| `ExecuteCodePlan.of` | `(offer, engines, selfName, scope)` | + `WarnOnce` (package-private type, no API change) |
| `ToolOfferContext` | public API unchanged | — (internal helper, package-private test reset) |
| `ShellProgramRun` (package-private) | `close()` returns at once | `close()` waits for in-flight writes (bounded 5 s); new `markStopping()` |
| Public API | — | **none changes** (javadoc only on `ExecutionEnvironment.shell()`, `VirtualShell.supports`) |

---

## 6. Failure modes

| Failure | Handling |
|---|---|
| Provider SIGTERM/SIGKILLs the exec's group | The anchor dies → pipe EOF → the **watchdog process** SIGKILLs the runtime's group. Bounded by scheduling, not by the program: a GIL-holding builtin does not delay it. |
| Watchdog fork fails | The program is not run; `DONE{error}` → `SCRIPT_ERROR` naming the cause. |
| `LocalShell` tree-walk kill | Reaches the anchor, the runtime and the children (descendant snapshot). Unchanged. |
| Output limit or violation (self-end) | `DONE` first, then the runtime kills its own group. The anchor survives and the wrapper finishes. Unchanged result path. |
| Program finishes and leaves a background process | The anchor kills the runtime's group before it reaps (behaviour change, §2.5). |
| `fork` fails | Single-process fallback without `setpgid` (R1 holds; a self-end may leave children). Logged to stderr. |
| `setpgid` fails in the child | `leader=False`. Everything stays in the shell's group, R1 holds, and the anchor skips its sweep. |
| A `setsid` child of the program | Escapes, as documented before (best-effort). |
| CALL lines arriving after stop | Verified, not forwarded, not answered. A violation is still detected. |
| Reply write in flight at close | `close()` waits ≤ 5 s. On timeout: WARN, delete may fail with WARN, the next run's sweep removes it. |
| Sweep removing a live run | Prevented for runs of the same configuration (age = `max(2 × maxWallClock, 20 min)`, `probe-*` skipped). Mixed configurations above 10 min in one sandbox are a documented residual (§10 Q2). |
| Sweep aimed at the wrong directory | The root is `dirname(reply_dir value)` only, guarded by basename `.aimon-ptc`, no symlink, and the runtime's own entry present; otherwise there is no sweep. |
| Sweep meets a planted symlink | `unlink` of the link, never followed. |
| Big `maxLogChars` | `ERR` is chunked under the line limit, and the host buffer is bounded. |
| A UTF-8 cut at a cap | Incomplete trailing sequence dropped at decode. |
| A tool returns a null descriptor | Fails every offer path identically, with the tool's name. |
| Bridge limit plus violation | `PROTOCOL_VIOLATION` wins (not retryable, ERROR log). |

---

## 7. Test strategy

### 7.1 The Q-C4 regression test — fails on the old code

Core's own shell kills by tree walk, which is why the defect never showed in core. The test therefore needs a shell
that kills the way aimon-sandbox does.

**New test double `ProcessGroupKillShell`** (test sources, `at.aimon.core.tools.code.shell`). It is a `VirtualShell`
decorator over `LocalShell` that reproduces aimon-sandbox's testkit provider: the command runs in a process group of
its own, under a wrapper that is **not** the runtime, and timeout or cancel kills that group only.

- **Wrapping.** It runs
  `python3 -c '<os.setpgid(0,0); write getpid() to PIDFILE; os.execvp("bash", ["bash","-c", CMD + "\nexit $?"])>' PIDFILE CMD`.
  The trailing `exit $?` stops bash from `exec`ing the last command. Without it the runtime would *be* the group leader,
  and in the old code `setpgid(0,0)` would then be a no-op and the bug would hide.
- **Delegation.** It delegates to `LocalShell` with the original options except the cancellation token, which it
  replaces with its own, and the timeout, which it sets to the original + 60 s, so that `LocalShell`'s tree-walk kill
  never fires.
- **Kill.** It waits on the delegate (on a helper thread) against the original timeout and token. On expiry or
  cancellation it does `kill -TERM -- -<pgid>`, then `-KILL` after 1.5 s (the testkit provider's timings).
  - **PIDFILE race (review 2).** A timeout or cancel can fire before the wrapper has written the PIDFILE, for example
    with a zero-length timeout or a token already tripped. The decorator then polls for the file for up to 2 s.
    - If it appears, the group kill proceeds.
    - If it does not, the wrapper has not started its group. The decorator falls back to cancelling the delegate through
      its own token (`LocalShell`'s tree kill), counts that separately (`fallbackKills()`), and still throws the
      contract's exception.
    - A token already tripped before `execute` follows the `VirtualShell` contract: throw without starting the command.
- **Ending.** It waits for the delegate for at most 10 s more, then cancels the delegate through its own token. It then
  throws
  `ShellTimeoutException` or `ShellCancelledException` as the contract says.
  - The extra cancel is a safety net, not a fix for a known hang. `LocalShell` captures output through files, not pipes
    (`LocalShell.java:71-77`), so a surviving runtime does not hold `execute` open by itself. The step only bounds the
    test if the delegate stays open for some other reason (review 1, non-blocking).
- **Group-kill counter.** It exposes `groupKills()`.

**New `ShellEngineProcessCleanupTest`** (assumes `Python3`, the existing helper). The program records its own pid and
the pid of a child it starts in a file in the workspace, then does what the case needs:

```python
import os, subprocess
child = subprocess.Popen(["sleep", "600"])
open("pids", "w").write("%d %d" % (os.getpid(), child.pid))
...  # case-specific
```

Once the `pids` file appears, and before any kill, the test snapshots
`ProcessHandle.of(runtimePid).parent()` (the anchor), `.descendants()` (the watchdog and the `sleep` child) and the
runtime itself. Every case below asserts that **all** of those processes are dead, so the watchdog and the anchor
cannot outlive the run either.

| Case | Program tail | Expect | Old code | Thread-watcher design (review 1) |
|---|---|---|---|---|
| wall clock 1 s, bytecode loop | `while True: pass` | `TIMED_OUT`; all dead within 5 s | **fails** (runtime and child alive) | passes |
| wall clock 1 s, **GIL-holding builtin** | `sum(range(10**13))` | `TIMED_OUT`; all dead within 5 s | **fails** | **fails** (the builtin runs for hours) |
| cancellation, waiting on a call | `await call_tool(T)` held by `ScriptedToolCallBridge.holdCalls()`, then `bridge.cancel()` | `CANCELLED`; all dead | **fails** | passes |
| cancellation, **GIL-holding builtin** | `sum(range(10**13))`; the test calls `bridge.cancel()` once `pids` exists | `CANCELLED`; all dead within 5 s | **fails** | **fails** |
| bridge limit | `callSequentially` past `stopAfter(3)` | `LIMIT_EXCEEDED`; all dead | **fails** | passes |
| output limit (self-end, R2) | print forever | `LIMIT_EXCEEDED` with `DONE`; child dead; `groupKills() == 0` (the wrapper survived and the shell call returned normally) | passes (regression guard for §6's intent) |
| normal completion with a background child | return at once | `COMPLETED`; child dead (§2.5) | **fails** |

- **Stop-flag assertion (item 0 §2.4), stated exactly.** It is a unit test of `ShellProgramRun` with a recording
  bridge and the test key:
  - after `markStopping()`, a valid `CALL` line with the next seq causes **zero** `callAsync` invocations and no reply
    write;
  - a `CALL` line with a bad MAC after `markStopping()` still records a violation;
  - `OUT` and `DONE` lines after `markStopping()` are still accumulated.

  At engine level the bridge-limit row keeps the suite's existing bound (`bridge.calls()` ≤ 5) and adds the
  all-processes-dead check. The unit test is what makes the behaviour deterministic.
- "Dead" is `ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false`, polled for up to 5 s, which
  allows for reaping by init or launchd.
- `@AfterEach` force-kills (`destroyForcibly`) every recorded pid that is still alive. A failing run on the old code
  must not leave strays (TASK acceptance). The test also runs `pgrep -f <the test's run marker>` and fails if anything
  remains.

**Second contract run.** `ShellCodeEngineGroupKillContractTest` runs the whole engine contract suite over
`SandboxedLocalEnvironment(root, new ProcessGroupKillShell(new LocalShell(root)))`. That is the core-side equivalent of
the two tests aimon-sandbox had to disable (`wallClock`, `bridgeLimitStopsAtOnce`).

### 7.2 Other tests (per item, as listed above)

- 1: `ExecuteCodeToolTest` (three cases).
- 2: `ToolOfferContextTest`.
- 4: warn-once scoping tests.
- 5: runtime sweep (old entry removed, a 25-min entry under a 30-min `maxWallClock` kept, symlink unlinked). The probe
  test loses its sweep assertion.
- 6 and 9: the drain tests and the no-reply-after-close assertion.
- 7: large stderr tail.
- 11: `Utf8Cut` unit tests and the `한` output cut.
- 12: the decision method's synthetic cases plus the real-tree scan.
- 3, 8, 10, 13: no tests (docs and javadoc). `checkAll`'s javadoc ratchet and the doc scripts cover them.

### 7.3 Gates

- `./gradlew checkAll`.
- `python3 scripts/check-doc-links.py`, `check-translation-staleness.py`, `check-translation-structure.py`, and
  `check-backlog-registers` (as the earlier reviews ran them).
- CI green.
- Shell tests skip without `python3` ≥ 3.8. The build agent must confirm from the CI test report (the XML `skipped`
  count) that the new process-cleanup tests **ran** on CI, not skipped. HANDOFF noted this was never confirmed for D1.
- Before finishing: `pgrep -fl aimon_ptc.py` and `pgrep -fl "sleep 600"` show nothing left by the run. Report anything
  found.

---

## 8. Ordering inside the PR

1. Item 0, runtime and host hardening, with its regression test (write the test first and watch it fail on the old
   runtime).
2. Items 5, 7, 11 (also runtime/host protocol changes; they share `ShellProgramRun` and `aimon_ptc.py`).
3. Items 6 and 9.
4. Items 1, 2, 4.
5. Items 3, 10, 12.
6. Docs (items 0, 5, 8, 9, 13, and §21 rows), then the CHANGELOG.

One PR is enough: the changes are confined to `tools.code`, `agent.tool`, two javadocs, one architecture test and
docs.

---

## 9. Out of scope (per TASK)

Tracing spans, the §6.2 language-change notice, `ExecuteCode` in forks and skills, CLI shell-engine assembly, piece E,
and any release or publish.

---

## 10. Open questions (not resolved from the task statement)

1. **Normal-completion sweep (§2.5).** The design kills processes the program left running when it completes normally.
   That follows from §13.2's spirit, but TASK names only timeout, cancel and limit. If the user wants background
   processes to survive a completed run, the anchor skips its sweep on exit code 0. That is a one-line change, and the
   corresponding test row flips. Whatever the answer, the PR body must state this behaviour change explicitly to the user
   (review 2), and the CHANGELOG lists it.
2. **Sweep with mixed configurations (item 5).** Two nodes or processes with different `maxWallClock` sharing one
   sandbox can still sweep each other's longer run. The design documents this rather than adding a heartbeat. Is that
   acceptable, or should the runtime also refresh its directory's mtime (heartbeat) so that the age no longer matters?
3. **aimon-sandbox follow-up.** The fix lives in core, as Q-C4 asked. The other repository then still needs to:
   - re-enable `wallClock` and `bridgeLimitStopsAtOnce` on its testkit tier;
   - close WS §20's Q-C4 item and provider doc §9 Q-C4;
   - check its `SandboxShell` and provider against item 10's javadoc.

   That needs this core change published, and TASK forbids publishing, so it is a separate task after the launcher
   republishes the snapshot. Should this PR's body list it as a follow-up? The design assumes yes.
4. **Stale D2 status lines.** The shell doc's status header and main doc §21 ("D2 미구현") predate aimon-sandbox#49
   (merged, `c6f3ece`). The design updates them while editing those headers for item 8. That edit states a fact about
   the other repository, and it can be dropped if the user prefers core docs to change only through core PRs.
5. **`os.waitid` on macOS — settled (review 1).** It is absent on macOS CPython, so developer machines reap first and
   then call `killpg`. §2.2 "Group-id reuse" explains why that is safe: the watchdog keeps the group, and so its id,
   alive until the sweep. Linux has `waitid`. Nothing is left open.
6. **Drain bound value (item 6).** A 5 s drain on `close()` was chosen as "a few reply-write attempts on OpenSandbox".
   It has not been measured; `close()` now logs the measured drain at DEBUG so that it can be tuned. It only matters when
   a sandbox write hangs.

---

## 11. After the build — where the implementation departed

*Appended 2026-11-06, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 3's non-blocking notes, and what was found
while building.*

**No decision in §2 or §3 changed.** The anchor stays in the shell's group and holds no secret; the runtime leads its
own group; a watchdog process in that group kills it when the anchor dies; the anchor sweeps the group when the runtime
ends, normal completion included (§2.5, §10 Q1 — implemented as designed, called out as a behaviour change in the
CHANGELOG). Every item 1 – 13 is fixed as §3 says, with the departures below.

### 11.1 Where the build departed from the body

- **The watchdog is double-forked** (review 3, note 1). §2.2 and §5 make it a direct child of the runtime, and a
  program that reaps "any child" (`os.wait()`) would then wait on it until the wall clock. The runtime forks an
  intermediate process that forks the watchdog and exits; the runtime reaps it. The watchdog keeps the runtime's
  pgid, so `killpg` still reaches it. It is reparented to init, so a tree-walk kill no longer reaches it directly; it
  dies all the same, because the anchor's death closes the pipe and the watchdog then kills its own group. The
  intermediate's exit code carries the errno of a failed second fork, so the `DONE{error}` message still names it.
  Pinned by `ShellEngineProcessCleanupTest.watchdogIsNotTheProgramsChild`.
- **The anchor's "grouped" check is taken at fork time, not before the sweep** (review 3, note 2). The anchor keeps
  its `setpgid(child, child)`, ignoring `OSError`, and asks `getpgid(child) == child` right after it. §2.2 put the
  check before the sweep, but without `os.waitid` (macOS) the anchor reaps first and cannot ask about a reaped child.
  The state review 3 named — the runtime's own `setpgid` fails, the anchor's succeeds afterwards — stays theoretically
  reachable; both calls are the same operation.
- **"Normally"** (review 3, note 3). The macOS rule's comment says the watchdog *normally* still lives when the
  runtime did not die of `SIGKILL`.
- **Fork-failure notes go to the program's log** (§2.2 failure table, first row). At the first fork stderr is still
  the shell's, which the host shows only without a `DONE`; the runtime writes the note after the redirect, so it lands
  in the `ERR` tail.
- **The host's log buffer is bounded by `4 × maxLogChars + 4` bytes, clamped to the largest array** — not by
  `maxScriptOutputBytes` (§3 item 7; review 3, note 7, taken further). Clamping by the output cap would cut the log
  below `maxLogChars` whenever the cap is under `4 × maxLogChars` bytes, as in the contract suite's `outputLimit`
  case (10,000 bytes, 4,000 characters). `logs()` also avoids splitting a surrogate pair.
- **`ShellProgramRun` takes a `Control` value** — the engine's stop, the run's deadline and the drain bound — instead of
  one constructor parameter each, which would have passed checkstyle's seven-parameter limit. `Control.of` uses the
  5 s bound; `withReplyDrain` lets a test use a shorter one. `close()` sets `stopping` too, and the engine's
  cancellation listener calls `markStopping()` before `stop.cancel()`.
- **The provider's own warn-once key is prefixed `no-engine\0`**, since its `WarnOnce` is shared with the plans' keys
  (§3 item 4).
- **The same javadoc wrap artifact in `VirtualShell`** ("the\n three flags") was rewrapped alongside item 10's
  sentence. `./gradlew format` keeps both rewraps (§3 item 3's check).
- **Sweep guard tests run the runtime directly** (review 3, note 5). No test-only engine hook was added; the guards
  live in `aimon_ptc.py`, and `ShellRuntimeSweepTest` runs it over a hand-written run directory. One guard case was
  added: a `reply_dir` whose own entry is missing sweeps nothing.

### 11.2 Review 3's notes: taken, and left

| Note | Outcome |
|------|---------|
| 1 — double-fork the watchdog | Taken (§11.1) |
| 2 — drop the anchor's `setpgid`, or say it ignores `OSError` | The second: kept, `OSError` ignored, check moved to fork time (§11.1) |
| 3 — "normally", not an invariant | Taken (code comment) |
| 4 — say which sweep assertion fails on the old code | Taken. Swapping the old runtime in fails `removesPastTheAge` and `symlinkEntryUnlinked`; the "stays" cases pass on both, as the test's javadoc says. The probe test now asserts that the probe sweeps nothing |
| 5 — no production hook for a guard test | Taken (§11.1) |
| 6 — name the `pgrep` marker | Taken. The program writes the run directory's basename (`aimon-run-*`, from `sys.argv[0]`); `@AfterEach` runs `pgrep -f` on it and `pgrep -g` on the runtime's group (the double-forked watchdog is not a descendant), force-kills what it finds and fails if anything was found |
| 7 — the log clamp overstates what bounds the runtime | Taken further (§11.1) |
| 8 — the CHANGELOG should say a null `describe` now fails the whole offer | Taken |

### 11.3 Open questions: decided, and what went to the backlog

- **Q1 (normal-completion sweep).** Implemented as designed; the CHANGELOG lists it as a behaviour change, and the
  PR body states it. `completedProgramLeavesNoChild` pins it, so reversing it shows up as a test change.
- **Q2 (mixed configurations sharing a sandbox).** Filed as **TC-18**.
- **Q3 (the aimon-sandbox follow-up).** Filed as **TC-19** — it needs this change published.
- **Q4 (stale D2 status lines).** Done: both PTC docs now cite aimon-sandbox#49.
- **Q5.** Settled in the body.
- **Q6 (drain bound unmeasured).** Filed as **TC-20**; `close()` logs the measured wait at DEBUG.

The three are in [`../../backlog/tool-contract-open-items.md`](../../backlog/tool-contract-open-items.md), the
register that already holds the PTC item TC-15.

### 11.4 Not verified here

- The watchdog-fork failure path (§2.2 failure table, third row) has no automated test: a fork that fails only at the
  second or third fork cannot be provoked portably from JUnit. It was checked by reading.
- §7.3's CI check — that the shell tests *ran* on CI rather than skipped for want of `python3` — belongs to the PR,
  not to this build.
- Every Linux-only branch (`os.waitid`, `/proc/self/fd`) ran only by reading; the build machine is macOS, which takes
  the other branches.
