---
translated_from: docs/features/agent-execution/interruptible-tools-guide.md
source_commit: b4427fc8
---

# Interruptible Tools Guide

> A guide to deciding how a tool takes in an external interrupt signal — the user's Ctrl+C, a `QueuedInputPriority.NOW` message, a parent agent's cancellation cascade — and how it reacts.

This document describes the public API of the `at.aimon.core.agent.interrupt` package from the point of view of a tool author and a CLI operator. For the design rationale and the event sequences, see [interrupt.md](../../design/agent-execution/interrupt.md).

## Table of contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [The public API surface](#the-public-api-surface)
4. [Choosing an InterruptBehavior](#choosing-an-interruptbehavior)
5. [Implementation patterns](#implementation-patterns)
6. [The operator's view — Ctrl+C UX](#the-operators-view--ctrlc-ux)
7. [An example of NOW queue priority](#an-example-of-now-queue-priority)
8. [Test recipes](#test-recipes)
9. [Design principles](#design-principles)

---

## Overview

AIMON's ReAct loop can be interrupted midway for the following reasons.

| Cause | `InterruptReason` | Where it is triggered |
|------|-------------------|--------------|
| The user's Ctrl+C (CLI) | `USER_SIGINT` | The `ReplSession` JLine SIGINT handler |
| A NOW-priority input submitted mid-turn | `NOW_PRIORITY_INPUT` | `DefaultLiveSession#onQueueEvent` |
| The budget (iterations / tokens / wall-clock) running out | `BUDGET_EXCEEDED` | The `OrcaAgentExecutor` budget guard |
| A parent agent's cancel cascade | `PARENT_CANCELLED` | Parent session → child session |
| A scheduled task cancelled, or just its run stopped | `TASK_CANCELLED` | `ScheduledTaskManager#cancel` · `#interrupt` |
| The host runtime shutting down | `SYSTEM_SHUTDOWN` | A JVM shutdown hook, a managed shutdown |
| The session lease failing to renew | `LEASE_LOST` | The multi-node routing layer |
| The session explicitly released from outside | `SESSION_RELEASED` | `SessionRouter` |
| A peer node taking over after holder loss was detected | `HOLDER_LOST` | The routing layer's holder-loss sweeper |

Whatever the cause, the interrupt signal reaches the tool layer through **a single path** — the current execution's `CancellationSignal` being tripped. The fifth row is the evidence that this execution is not always a turn: a scheduled routine runs with neither a session nor a turn behind it, and is stopped by the same signal machinery ([interrupt.md §12](../../design/agent-execution/interrupt.md)). A tool author declares an `InterruptBehavior` to tell the executor "interpret this signal for me like so", and where necessary observes the signal directly through `InterruptAccess`.

The default behaviour is `InterruptBehavior.NON_INTERRUPTIBLE`. When a tool does not override that declaration, **the execution in flight runs to completion** and the interrupt is only reflected at the next iteration boundary. For short atomic operations such as a stat or a read, that is in fact the safer default.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  External triggers                                          │
│    REPL SIGINT handler / DefaultAgentSession NOW listener / │
│    Budget guard / parent cascade / shutdown hook            │
└────────────────────────┬────────────────────────────────────┘
                         │ AgentSession#interrupt(InterruptReason)
                         ▼
┌─────────────────────────────────────────────────────────────┐
│  InterruptCoordinator (per-turn)                            │
│   ─ trips the current turn's CancellationSignal (reason)    │
│   ─ calls registered Terminators once, in registration order│
└────────────────────────┬────────────────────────────────────┘
                         │
             ┌───────────┴────────────┐
             ▼                        ▼
  CancellationSignal          TerminatorRegistrar
  (injected into ToolContext) (THREAD_INTERRUPT /
                               EXTERNALLY_TERMINATED only)
             │                        │
             ▼                        ▼
  Tool.execute(input, context)  ← InterruptAccess.signalOf / registrarOf
```

- **The coordinator** creates a new `CancellationSignal` per turn, and the signal is single-shot (once tripped it stays tripped for that turn; the next turn gets a fresh one).
- **A tool** obtains the signal from the `ToolContext` the executor injected, with `InterruptAccess.signalOf(context)`. When the context carries no signal the `NoopCancellationSignal` singleton is returned instead, so `isCancelled()` / `checkpoint()` can be called safely without a null check.
- **The registrar** is supplied only to tools that declared `THREAD_INTERRUPT` or `EXTERNALLY_TERMINATED`, and the tool has to take responsibility for `close()` to keep it from leaking (the executor closes it automatically when the tool run ends, but even so, cleaning up with `registrar.unregister(...)` is the safe thing to do when the tool returns early).

## The public API surface

### `InterruptBehavior` (an enum with 4 values)

| Value | Meaning | What the executor does | Typical users |
|----|------|---------------|-----------|
| `NON_INTERRUPTIBLE` | Ignores the interrupt signal | Does not deliver the signal. The interrupt takes effect only at the next iteration boundary | A file stat, an in-memory read, a cache lookup |
| `COOPERATIVE` | The tool polls and ends early | Trips the `CancellationSignal` only; never touches the thread | `GrepTool`, `WebFetchTool` — polling at loop and stage boundaries |
| `THREAD_INTERRUPT` | Permits `Thread.interrupt()` on the tool's thread | Trips the signal and runs the pre-registered `Thread.interrupt()` terminator | `BashTool` — blocked in `Future.get(timeout)` |
| `EXTERNALLY_TERMINATED` | The tool registers an external handle (a process, a future) itself | Calls the `Terminator`s the tool registered, once, when the signal trips | A sub-process manager, a subagent execution |

`Tool.getInterruptBehavior()` returns `NON_INTERRUPTIBLE` by default — a choice made so as not to break compatibility with existing tools.

### `InterruptReason` (an enum with 9 values)

See the table in the [overview](#overview) above. A tool uses the reason **for observation only** — its behaviour needs no more than "it was cancelled", and including the reason in a message format or a log helps the user tell the causes apart.

```java
String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
return ToolResult.error("Grep interrupted: " + reason);
```

### `CancellationSignal` (a read-only view)

```java
public interface CancellationSignal {
    boolean isCancelled();
    Optional<InterruptReason> getReason();
    void checkpoint();               // throws CancelledExecutionException when tripped
    void onCancel(Runnable listener); // called on trip (runs immediately if already tripped)
}
```

- **Thread-safe**: every method is callable from any thread.
- **Single-shot**: the tripped state holds until the turn ends. There is no reset.
- **Listener order**: listeners run on a trip in registration order. Registering one after the trip runs it immediately, on the registering thread.

### `TerminatorRegistrar` (the tool → coordinator write path)

```java
public interface TerminatorRegistrar extends AutoCloseable {
    void register(Terminator terminator);
    void unregister(Terminator terminator);
    @Override void close();
}
```

- It is injected only into tools that declared `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED`.
- A `register()` after the trip is called **immediately** (race-safe).
- A `register()` after `close()` throws `IllegalStateException` — registration only belongs inside the tool's run.
- A terminator **must be idempotent, non-blocking and non-throwing**.

### `InterruptAccess` (a helper for the tool's point of view)

```java
public final class InterruptAccess {
    public static CancellationSignal signalOf(ToolContext context);       // never null
    public static Optional<TerminatorRegistrar> registrarOf(ToolContext context);
}
```

`signalOf` returns `NoopCancellationSignal.INSTANCE` even when the context carries no signal. So a unit test, or a diagnostic tool called directly from the CLI, can call `signal.isCancelled()` safely too.

`registrarOf` returns an `Optional` — `NON_INTERRUPTIBLE` and `COOPERATIVE` tools do not receive a registrar. An accidental attempt to register a terminator becomes an `Optional.empty()` and is quietly ignored, which is how the contract is enforced.

## Choosing an InterruptBehavior

Use the flowchart below to pick the right value for your tool.

```
How long does Tool.execute() take on average?
│
├── < 100ms (a file stat, an in-memory computation, a local cache lookup)
│   └─► NON_INTERRUPTIBLE
│       (there is nothing to gain by interrupting, only race-condition risk)
│
└── >= 100ms, or it involves I/O
    │
    ├── Is there a loop or a stage boundary inside?
    │   (scanning several files, a multi-stage pipeline, pagination …)
    │   │
    │   ├── YES
    │   │   └─► COOPERATIVE
    │   │       (check signal.isCancelled() before each iteration / stage, then end early with ToolResult.error)
    │   │
    │   └── NO — it is stuck in one blocking call
    │       │
    │       ├── Does the tool explicitly hold an external process / subagent / future?
    │       │   │
    │       │   ├── YES — an explicit kill handle is more effective
    │       │   │   └─► EXTERNALLY_TERMINATED
    │       │   │       (register process.destroy / future.cancel(true) with the TerminatorRegistrar)
    │       │   │
    │       │   └── NO — the blocking API is of the kind that throws InterruptedException
    │       │       └─► THREAD_INTERRUPT
    │       │           (just lean on the Thread.interrupt() terminator the executor pre-registers)
```

Real examples:

- `ReadTool` — a single file read, a few ms on average → `NON_INTERRUPTIBLE`
- `GrepTool` — a directory walk plus a scan per file → `COOPERATIVE`
- `WebFetchTool` — the three stages cache → fetch → extract → `COOPERATIVE` (polling at each stage boundary)
- `BashTool` — a single `Future.get(timeout)` block, and a kill handle is available → `THREAD_INTERRUPT` (plus `future.cancel(true)` registered as a second line through the registrar, belt and braces)

## Implementation patterns

### Pattern 1 — COOPERATIVE (the `GrepTool` style)

Poll the signal at a loop, stage or batch boundary and end early.

```java
public class GrepTool extends AbstractTool {

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final CancellationSignal signal = InterruptAccess.signalOf(context);

        try {
            final String pattern = input.getRequiredString("pattern");
            // ... extract and validate parameters ...

            // once before the work starts
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }

            final List<String> files = discoverFiles(path);

            // inside the loop — poll at the batch boundary
            final List<SearchResult> results = new ArrayList<>();
            for (String file : files) {
                if (signal.isCancelled()) {
                    return interruptedResult(signal);
                }
                results.addAll(searchFile(file, pattern));
            }

            return ToolResult.success(formatOutput(results));

        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    private ToolResult interruptedResult(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        return ToolResult.error("Grep interrupted: " + reason);
    }
}
```

Set the **checkpoint frequency** by **"the largest latency after one poll that a user can put up with"** — too dense and you pay hot-loop overhead, too sparse and Ctrl+C feels slow. Per file, per 100 ms block and per page are the usual compromises.

Using `signal.checkpoint()` makes a design that throws `CancelledExecutionException` and unwinds by stack propagation possible too, but a tool is under the larger contract that it **must return a `ToolResult.error`**, so be sure to wrap it in one in an outer `catch (CancelledExecutionException)` block.

### Pattern 2 — THREAD_INTERRUPT (the `BashTool` style)

Suitable where the blocking call throws `InterruptedException`. The executor pre-registers `Thread.interrupt()`, so the tool only has to catch `InterruptedException` / `CancellationException` and unwind cleanly.

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    Objects.requireNonNull(input, "Input cannot be null");
    Objects.requireNonNull(context, "Context cannot be null");

    try {
        final String command = input.getRequiredString("command");
        final Future<String> future = executorService.submit(() -> bashExecutor.execute(command));

        // Double protection: register Future.cancel(true) as a terminator too.
        // 1) Thread.interrupt()  → future.get() wakes up with an InterruptedException
        // 2) future.cancel(true) → terminates the actual shell process as well
        final Optional<TerminatorRegistrar> registrar = InterruptAccess.registrarOf(context);
        final Terminator cancelFuture = () -> future.cancel(true);
        registrar.ifPresent(r -> r.register(cancelFuture));

        try {
            final String output = future.get(timeout, TimeUnit.MILLISECONDS);
            return ToolResult.success(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // restore the interrupt state
            future.cancel(true);
            return interruptedResult(InterruptAccess.signalOf(context));
        } catch (CancellationException e) {
            // the terminator hit future.cancel(true)
            return interruptedResult(InterruptAccess.signalOf(context));
        } finally {
            registrar.ifPresent(r -> r.unregister(cancelFuture));
        }

    } catch (Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ToolResult.error("Unexpected error: " + e.getMessage());
    }
}

@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.THREAD_INTERRUPT;
}
```

- **Restoring with `Thread.currentThread().interrupt()`** is mandatory — so that later framework-side calls can see the interrupt state.
- **`unregister` in the `finally`.** The executor owns the close responsibility, but this prevents a leak in a test setup that reuses a long-lived registrar.

### Pattern 3 — EXTERNALLY_TERMINATED

For a tool that has an **explicit kill handle** — a sub-process manager or a subagent — calling the handle directly is more effective than `Thread.interrupt()`.

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    final Optional<TerminatorRegistrar> registrar = InterruptAccess.registrarOf(context);

    Process process = new ProcessBuilder("long-running-tool", "--foo").start();
    final Terminator killTerminator = () -> process.destroy();
    registrar.ifPresent(r -> r.register(killTerminator));

    try {
        final int exitCode = process.waitFor();
        return exitCode == 0 ? ToolResult.success(readStdout(process))
                : ToolResult.error("Process exited with code " + exitCode);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return interruptedResult(InterruptAccess.signalOf(context));
    } finally {
        registrar.ifPresent(r -> r.unregister(killTerminator));
    }
}

@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.EXTERNALLY_TERMINATED;
}
```

**The terminator contract** (as stated in the `Terminator.java` javadoc):

- Idempotent — the coordinator calls it once per trip, but the tool itself often calls it again as well (during timeout handling, for instance).
- Non-blocking — holding on to the coordinator's signalling thread delays the other terminators and listeners.
- Non-throwing — a thrown exception breaks the coordinator's iteration. Log it yourself and swallow it.

### Pattern 4 — NON_INTERRUPTIBLE (the default)

No need to declare it explicitly. Not overriding `getInterruptBehavior()` gives you `NON_INTERRUPTIBLE` automatically.

```java
// ReadTool's actual declaration
@Override
public InterruptBehavior getInterruptBehavior() {
    return InterruptBehavior.NON_INTERRUPTIBLE;
}
```

Writing the declaration out **explicitly** is nonetheless recommended — a reviewer can then read "this tool is meant to ignore interrupt signals" straight off the code.

## The operator's view — Ctrl+C UX

When Ctrl+C is pressed in the CLI REPL, the following happens in order (see `ReplSession#requestInterruptWithFallback`, `modules/aimon-cli/src/main/java/at/aimon/cli/repl/ReplSession.java`).

```
┌─────────────────────────────────────────────────────────────┐
│  1. The JLine SIGINT handler calls session.interrupt(       │
│     USER_SIGINT) → the CancellationSignal trips at once.    │
├─────────────────────────────────────────────────────────────┤
│  2. Depending on the running Tool's InterruptBehavior:      │
│     - COOPERATIVE: ToolResult.error at the next checkpoint  │
│     - THREAD_INTERRUPT: fires Thread.interrupt()            │
│     - EXTERNALLY_TERMINATED: calls registered terminators   │
│     - NON_INTERRUPTIBLE: this execute runs to completion    │
├─────────────────────────────────────────────────────────────┤
│  3. If the Tool unwinds within the 500 ms grace period,     │
│     OrcaAgentExecutor ends the turn as INTERRUPTED.         │
├─────────────────────────────────────────────────────────────┤
│  4. If it does not, the REPL hard-cancels with              │
│     future.cancel(true) — so even in the emergency where a  │
│     non-interruptible tool runs a 10-minute stat, the       │
│     prompt comes back.                                      │
├─────────────────────────────────────────────────────────────┤
│  5. OutputFormatter renders the banner                      │
│     "[Interrupted] Execution interrupted" (no red "Error:"  │
│     prefix — the user cut it off on purpose).               │
└─────────────────────────────────────────────────────────────┘
```

The grace period (500 ms) is defined in the `ReplSession.SIGINT_FALLBACK_GRACE_MS` constant. When the cooperative path works properly the tool unwinds in very nearly 0 ms, so the user feels no delay at all. Going past 500 ms usually means one of:

1. A `NON_INTERRUPTIBLE` tool took longer than expected → the hard cancel unwinds it.
2. A `COOPERATIVE` tool has its checkpoints planted too sparsely → **refactor the tool to make them denser**.
3. A `THREAD_INTERRUPT` tool catches `InterruptedException` and spins a retry loop instead of recovering → that is a bug in the tool.

### Rendering an interrupted turn

`OutputFormatter#displayResult` detects `CompletionReason.INTERRUPTED` and takes a dedicated branch (`OutputFormatter.java`).

```
[Interrupted] Execution interrupted

[Interrupted after 2 iteration(s)]     ← only when settings.showIterations=true
```

- Unlike an ordinary failure there is **no** red `Error:` prefix — because the user cut it off on purpose.
- The iteration count is supplementary diagnostic information, off by default.
- The conversation history (`SessionSnapshot`) includes turns that ended INTERRUPTED too, so the next turn can carry on with continuity — "not that thing I just cut off with Ctrl+C, do this instead".

### `/retry` — run the turn you stopped again

```
> summarise the incident
  ⋯ (Ctrl+C)
[Interrupted] Execution interrupted

> /retry
[retrying] summarise the incident
  ⋯
```

Keeping the stopped turn **in the history** is deliberate, as above — but the same request must not be laid on top of
it, because that asks the model to redo work in a history that says it already half-did it. So `/retry` **removes the
trail first** (the user message, the synthetic context blocks injected ahead of it, the assistant output produced
before the stop, the tool results filled in as skipped) and runs from where the turn originally started.

- Works **only when the last turn ended `INTERRUPTED`**. Otherwise it prints a single `Nothing to retry —` line and
  runs nothing at all.
- The retried turn is **exactly an ordinary turn**: streaming and Ctrl+C both still work. It is the turn you just
  stopped, so being unable to stop it a second time would lose that at the worst possible moment.
- Interrupting the retry too does **not** stack trails. The rewind is persisted before the submission, so the next
  attempt starts from the same place.
- The rewind point is persisted along with the transcript, so a session stays retryable **across a CLI restart**.
- A turn started by **any** input can be retried. The rewind point keeps the `UserInput` the turn was submitted with,
  so an image, an attached file or a multimodal request is submitted **as itself** rather than as a text summary of
  itself.
- The retry runs **under the same conditions as the original**. The rewind point keeps that turn's `SubmitOptions`
  too, so a turn submitted with a principal or system-prompt variables carries the same ones the second time.

Programmatically it is `LiveSession.retryLastTurn(...)` (rewind and submit) or `rewindLastTurn()` (rewind and hand the
input back, as an `Optional<UserInput>`). To submit that input again, use `submit(UserInput, SubmitOptions)` or
`offerAsync(UserInput, ...)`. The design note is
[`interrupt.md` §15](../../design/agent-execution/interrupt.md).

## An example of NOW queue priority

The scenario where the user submits a mid-turn correction while a tool is running.

```
At T0: the agent starts WebFetch(url1). (COOPERATIVE, takes several seconds)
At T1: the user types "hold on, do url2 instead" →
       MessageQueueManager#enqueue(NOW priority, ctx=main).
At T2: DefaultAgentSession#onQueueEvent spots the NOW priority plus the matching
       context → interrupt(InterruptReason.NOW_PRIORITY_INPUT).
At T3: WebFetchTool checks signal.isCancelled() at the next stage boundary →
       returns ToolResult.error("WebFetch interrupted: NOW_PRIORITY_INPUT").
At T4: Orca ends the current turn as INTERRUPTED.
At T5: as the next turn starts, Orca seeds the drained "do url2 instead" as a
       system reminder, and the agent begins working on url2.
```

The producer-side code (the CLI or another agent) is simple.

```java
QueuedInput preempt = QueuedInput.builder()
    .inputText(userMessage)
    .priority(QueuedInputPriority.NOW)                 // ← mid-turn preempt
    .agentExecutionContextId(currentContextId)  // ← must be the current turn's ctx id
    .metadata(Map.of("origin", "repl-correction"))
    .build();

messageQueueManager.enqueue(preempt);
```

- With `QueuedInputPriority.NEXT` **the current turn carries on as it is** and the input is absorbed as a new user message at the end of the turn — right for a harmless additional instruction.
- With `QueuedInputPriority.NOW` **the current turn is preempted at once** — reserve it for corrections that must stop what is in progress, of the "no, not in that direction" kind. Overuse it and the agent will keep starting and abandoning the same work.
- A NOW event fires an interrupt only when **the context ids match**. A main agent putting a NOW into a sub-agent's context does not touch the main agent.

## Test recipes

### Recipe 1 — verifying that a COOPERATIVE tool ends early

```java
@Test
void grepTool_interruptsBetweenFiles() {
    VirtualFileSystem vfs = new InMemoryVfsWith(
            "/a.txt", largeContent(), "/b.txt", largeContent(), "/c.txt", largeContent());
    GrepTool tool = new GrepTool(vfs);

    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    CancellationSignal signal = coordinator.getSignal();   // per-turn fresh signal
    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, signal)
            .build();

    // Trip from a separate thread so the interrupt fires midway through the first file scan.
    CompletableFuture.runAsync(() -> {
        sleep(Duration.ofMillis(50));
        coordinator.requestInterrupt(InterruptReason.NOW_PRIORITY_INPUT);
    });

    ToolResult result = tool.execute(
            ToolInput.of(Map.of("pattern", "TODO", "path", "/")),
            context);

    assertThat(result.isError()).isTrue();
    assertThat(result.getContent())
        .contains("Grep interrupted")
        .contains("NOW_PRIORITY_INPUT");
}
```

### Recipe 2 — the InterruptedException path of a THREAD_INTERRUPT tool

```java
@Test
void bashTool_honorsThreadInterrupt() throws Exception {
    BashTool tool = new BashTool(executor, /* background */ null);

    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    CancellationSignal signal = coordinator.getSignal();
    TerminatorRegistrar registrar = coordinator.newTerminatorRegistrar();
    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, signal)
            .put(InterruptToolKeys.TERMINATOR_REGISTRAR, registrar)
            .build();

    // Pre-register the Thread.interrupt() terminator (what the executor does at real runtime).
    Thread worker = Thread.currentThread();
    registrar.register(worker::interrupt);

    CompletableFuture.runAsync(() -> {
        sleep(Duration.ofMillis(100));
        coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
    });

    ToolResult result = tool.execute(
            ToolInput.of(Map.of("command", "sleep 30")),
            context);

    assertThat(result.isError()).isTrue();
    assertThat(result.getContent()).contains("USER_SIGINT");
}
```

### Recipe 3 — verifying that NON_INTERRUPTIBLE is a no-op

```java
@Test
void readTool_ignoresSignal() throws Exception {
    ReadTool tool = new ReadTool(vfs);

    // Trip the signal through the public coordinator API — tests should not reach into
    // DefaultCancellationSignal's package-private trip() the way the runtime executor does.
    InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
    coordinator.requestInterrupt(InterruptReason.USER_SIGINT);
    CancellationSignal pretripped = coordinator.getSignal();

    ToolContext context = ToolContext.builder()
            .put(InterruptToolKeys.CANCELLATION_SIGNAL, pretripped)
            .build();

    ToolResult result = tool.execute(
            ToolInput.of("file_path", "/hello.txt"),
            context);

    // Even with the signal tripped, a NON_INTERRUPTIBLE tool succeeds as usual.
    assertThat(result.isSuccess()).isTrue();
}
```

The reference tests in the actual project:

- `modules/aimon-core/src/test/java/at/aimon/core/ext/tools/web/WebFetchToolInterruptTest.java`
- `modules/aimon-core/src/test/java/at/aimon/core/ext/tools/bash/BashToolInterruptTest.java`
- `modules/aimon-core/src/test/java/at/aimon/core/agent/interrupt/DefaultCancellationSignalTest.java`

## Design principles

- **A single-shot, per-turn signal** — issuing a new `CancellationSignal` per turn keeps one turn's interrupt from leaking into the next. No reset → simpler races.
- **`InterruptAccess.signalOf()` never returns null** — so a tool can always poll without null-check boilerplate. When the context carries no signal, `NoopCancellationSignal.INSTANCE` naturally carries the meaning "not cancelled".
- **The behaviour declaration is policy input to the executor** — when a tool declares an `InterruptBehavior`, the executor decides how to propagate the signal. The tool does not call `Thread.interrupt()` itself; the executor does it on its behalf — which avoids double calls and tangled ordering.
- **Terminators are registration-ordered, one-shot and non-blocking** — the coordinator calls them all at once when the signal trips. A blocking, throwing or non-idempotent terminator invites nightmares. Be sure to honour the three contract points in the `Terminator.java` javadoc.
- **The grace period is a UX deadline, not a semantic one** — the 500 ms is "the time yielded to the cooperative path", not "it must end within this". Handle a genuine semantic deadline with the tool's own timeout parameter.

---

## Related documents

- [interrupt.md](../../design/agent-execution/interrupt.md) — the design background, event sequences and scenarios
- [command-queue-guide.en.md](command-queue-guide.en.md) — what enqueue/drain mean for `QueuedInputPriority.NOW`
- [tool-development-guide.en.md](../tool/tool-development-guide.en.md) — the basic conventions for developing a tool
- `at.aimon.core.agent.interrupt.package-info` — a package-level Javadoc summary
