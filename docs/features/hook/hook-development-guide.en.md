---
translated_from: docs/features/hook/hook-development-guide.md
source_commit: d57e3e45
---

# Hook Development Guide

> A guide to programmatic hook development

This document covers what you need in order to write and register hooks in Java against the
aimon-core framework. For declarative hooks configured through `hooks.json` or SKILL.md
frontmatter, see the [Hook Config Guide](hook-config-guide.en.md).

## Table of contents

1. [Overview](#overview)
2. [Event types](#event-types)
3. [Implementing a hook](#implementing-a-hook)
4. [Registering a hook](#registering-a-hook)
5. [Context objects](#context-objects)
6. [Returning a HookResult](#returning-a-hookresult)
7. [Execution policy](#execution-policy)
8. [A complete example](#a-complete-example)

---

## Overview

A hook is an extension point invoked at a specific moment during agent execution. Through hooks
you can observe what the agent does (auditing, metrics) or block and transform tool calls.

### Core principles

| Principle | Description |
|------|------|
| **Single Responsibility** | One hook performs one clearly defined job |
| **Thread-safe** | The same instance may run concurrently across several agents and threads |
| **Never throw** | An escaping exception may be converted into a block, depending on the policy |
| **Blocking works in four chains only** | `block()` on every other event is silently ignored ([below](#event-types)) |
| **Stateless** | Keeps no state between executions (push it into an external store if you need it) |

### Package structure

```
at.aimon.core.hook/
├── HookRegistry.java              # Hook registry interface
├── DefaultHookRegistry.java       # Default registry implementation
├── HookEventType.java             # Typed event token (13 constants)
├── HookExecutionManager.java      # Hook execution manager
├── HookFeedback.java              # feedback → model-message renderer
├── event/                         # Per-event hook interfaces + contexts
│   ├── PreToolHook.java / PreToolContext.java
│   ├── PostToolHook.java / PostToolContext.java
│   ├── OnStartHook.java / OnStopHook.java
│   ├── PermissionRequestHook.java / PermissionDeniedHook.java
│   ├── SubagentStartHook.java / SubagentStopHook.java
│   ├── OnSessionStartHook.java / OnSessionEndHook.java
│   ├── PreCompactHook.java / PostCompactHook.java
│   └── OnConfigReloadHook.java
├── execution/                     # Execution model
│   ├── ExecutionHook.java         # Supertype of every hook
│   ├── HookContext.java           # Supertype of every context
│   ├── HookResult.java            # Decision × FlowControl result
│   ├── HookExecutionPolicy.java   # timeout / parallel / dedup policy
│   └── DefaultHookExecutor.java
└── rewake/                        # Asynchronous re-invocation (async rewake)
```

---

## Event types

`HookEventType<H>` is a typed token — the constant carries the hook interface type, so
`registry.register(HookEventType.PRE_TOOL, hook)` accepts a `PreToolHook` and nothing else.
There are 13 in total:

| Constant | Hook interface | Fires at | Can block |
|------|-----------------|-----------|-----------|
| `PERMISSION_REQUEST` | `PermissionRequestHook` | Tool permission decision | ✅ deny |
| `PRE_TOOL` | `PreToolHook` | Immediately before tool execution | ✅ block |
| `POST_TOOL` | `PostToolHook` | Immediately after tool execution | ❌ |
| `PERMISSION_DENIED` | `PermissionDeniedHook` | Post-processing after a permission denial | ❌ |
| `ON_START` | `OnStartHook` | Turn start | ✅ block |
| `ON_STOP` | `OnStopHook` | Turn end | ❌ |
| `ON_SESSION_START` | `OnSessionStartHook` | Conversation start | ❌ |
| `ON_SESSION_END` | `OnSessionEndHook` | Conversation end | ❌ |
| `SUBAGENT_START` | `SubagentStartHook` | Subagent start | ❌ |
| `SUBAGENT_STOP` | `SubagentStopHook` | Subagent end | ❌ |
| `PRE_COMPACT` | `PreCompactHook` | Immediately before context compaction | ✅ block |
| `POST_COMPACT` | `PostCompactHook` | Immediately after context compaction | ❌ |
| `ON_CONFIG_RELOAD` | `OnConfigReloadHook` | Immediately after a configuration hot reload | ❌ |

> ⚠️ **Returning `HookResult.block()` from an event whose "can block" column says ❌ does
> nothing at all.** The call sites that actually consume `BLOCK` are these four:
>
> - `PRE_TOOL` — skips the tool call and hands the reason to the model as the tool result
> - `PERMISSION_REQUEST` — denies before dispatch
> - `ON_START` — aborts the turn with `ExecutionBlockedByHookException`
> - `PRE_COMPACT` — skips AUTO compaction / reports the reason for MANUAL compaction
>
> Every other event is advisory. Do not design an audit or notification hook as a gate.
>
> Declarative hooks (`hooks.json` / SKILL.md) can refuse in the same four chains only, and a
> refusal is expressed as **exit 2** from the shell handler. The declarative veto on `ON_START`
> was added recently — before that, an `onStart` shell hook exiting 2 had no effect whatsoever.

Adding a new event means adding all of: the hook interface, the context type, the
`HookEventType` constant, the `HookExecutionManager` method, and **the firing site**. A constant
with no firing site is dead configuration.

---

## Implementing a hook

Every hook is a subinterface of `ExecutionHook<C extends HookContext>` with exactly one abstract
method, `HookResult execute(C context)`, so it can be written as a lambda.

### PreToolHook (can block / can transform the input)

```java
// Security hook — blocks dangerous commands
PreToolHook securityHook = context -> {
    if ("Bash".equals(context.getCurrentToolUse().getName())) {
        String command = (String) context.getCurrentToolUse().getInput().get("command");
        if (command != null && command.contains("rm -rf")) {
            return HookResult.block("Dangerous command blocked: " + command);
        }
    }
    return HookResult.success();
};

// Advisory hook — a word to the model without blocking
PreToolHook adviceHook = context -> HookResult
        .withFeedback("Use pnpm rather than npm in this repository.");

// Input-transforming hook — propagates to later hooks and to the actual tool call
PreToolHook normalizeHook = context -> {
    ToolInput current = ToolInput.of(context.getCurrentToolUse().getInput());
    if (!current.has("timeout")) {
        Map<String, Object> patched = new LinkedHashMap<>(current.toMap());
        patched.put("timeout", 30_000);
        return HookResult.withUpdatedInput(ToolInput.of(patched));
    }
    return HookResult.success();
};
```

### PostToolHook (observation / output transformation)

```java
PostToolHook auditHook = context -> {
    auditLogger.log(context.getInvokerName(), context.getToolUse().getName(),
            context.getCurrentToolUseResult().isError() ? "FAILURE" : "SUCCESS");
    return HookResult.success();
};

// Output masking — changes the result the model sees
PostToolHook redactHook = context -> {
    ToolResult out = context.currentOutput();
    if (out.getContent().contains("BEGIN PRIVATE KEY")) {
        return HookResult.withUpdatedOutput(ToolResult.success("[redacted: private key]"));
    }
    return HookResult.success();
};
```

### Lifecycle hooks

```java
OnStartHook initHook = context -> {
    log.info("Agent '{}' started at {}", context.getInvokerName(), context.getTimestamp());
    return HookResult.success();
};

OnStopHook summaryHook = context -> {
    notificationService.sendSummary(context.getFinalAnswer());
    return HookResult.success();
};
```

### Hooks that do long work

A hook that takes longer than the default hook timeout (30 seconds) must declare its own budget.
Without a declaration the executor's outer net cuts it off first, and the perfectly good result
the hook was about to produce is discarded.

```java
class ExternalPolicyHook implements PreToolHook {

    @Override
    public Optional<Duration> getExecutionBudget() {
        return Optional.of(Duration.ofSeconds(90));   // the net widens to 90s + grace
    }

    @Override
    public HookResult execute(PreToolContext context) {
        return policyClient.evaluate(context.getCurrentToolUse());   // has its own deadline
    }
}
```

A declared budget is a **floor, not an override** — it only widens the net, never narrows it.
The ceiling is `MAX_DECLARED_BUDGET`, 10 minutes; anything larger is clamped to 10 minutes with
a WARN log.

### `getHookId()`

Override `getHookId()` when several instances of one class may be registered. Async rewake
routing and configuration hot-reload cancellation both use this value as a key. The id must be
**derived from content and stable across reloads** (see `DeclarativeHookId` for declarative
hooks).

---

## Registering a hook

### Using the HookRegistry

`HookRegistry` exposes five generic methods rather than per-event ones.

```java
HookRegistry registry = new DefaultHookRegistry();

registry.register(HookEventType.ON_START, initHook);
registry.register(HookEventType.PRE_TOOL, securityHook);
registry.register(HookEventType.PRE_TOOL, adviceHook);   // several may be registered
registry.register(HookEventType.POST_TOOL, auditHook);
registry.register(HookEventType.ON_STOP, summaryHook);

List<PreToolHook> preTool = registry.getHooks(HookEventType.PRE_TOOL);  // type-safe
registry.unregister(HookEventType.PRE_TOOL, adviceHook);
```

A mismatched type does not compile — `register(HookEventType.PRE_TOOL, auditHook)` tries to put
a `PostToolHook` where a `PreToolHook` belongs, which is a compile error.

### Execution order

When several hooks are registered for the same event they run **in registration order**.

```java
// Execution order: securityHook -> loggingHook -> rateLimitHook
registry.register(HookEventType.PRE_TOOL, securityHook);
registry.register(HookEventType.PRE_TOOL, loggingHook);
registry.register(HookEventType.PRE_TOOL, rateLimitHook);
```

`updatedInput` / `updatedOutput` accumulate in that order and are reflected in the next hook's
`getCurrentToolUse()` / `currentOutput()`.

---

## Context objects

Each hook receives the context object that matches its firing point. Every context implements
`HookContext`.

### Common fields (`HookContext`)

| Accessor | Type | Description |
|--------|------|------|
| `getInvokerType()` | `InvokerType` | Invoker type (MAIN_AGENT, SUBAGENT, …) |
| `getInvokerName()` | `String` | Invoker name |
| `getHookRegistry()` | `HookRegistry` | The hook registry |
| `getEnvironment()` | `Environment` | Environment configuration |
| `getTimestamp()` | `Instant` | Timestamp |
| `getExecutionAttributes()` | `Map<String, Object>` | Supplementary execution information |

### `PreToolContext`

| Accessor | Type | Description |
|--------|------|------|
| `getOriginalToolUse()` | `ToolUse` | The original, before any hook touched it |
| `getCurrentToolUse()` | `ToolUse` | The current value with earlier hooks' `updatedInput` applied — use this to see the tool that will actually run |
| `getIterationCount()` | `int` | The current ReAct loop iteration count |

### `PostToolContext`

| Accessor | Type | Description |
|--------|------|------|
| `getToolUse()` | `ToolUse` | The tool that ran |
| `getOriginalToolUseResult()` | `ToolUseResult` | The original result |
| `getCurrentToolUseResult()` | `ToolUseResult` | The result with earlier hooks' `updatedOutput` applied |
| `originalOutput()` / `currentOutput()` | `ToolResult` | `ToolResult` views of the same values |
| `getIterationCount()` | `int` | The current ReAct loop iteration count |

### `OnStopContext`

| Accessor | Type | Description |
|--------|------|------|
| `isSuccess()` | `boolean` | Whether the turn succeeded |
| `getFinalAnswer()` | `String` | The final answer |
| `getMetadata()` | `ExecutionMetadata` | Execution metadata such as the iteration count |

The contexts for the remaining events follow the same rule — common fields plus event-specific
fields, all immutable.

---

## Returning a HookResult

`HookResult` is **two independent axes, not a single enum**.

- **`Decision`** — `ALLOW` / `ASK` / `DENY` (only the permission chain interprets `ASK`)
- **`FlowControl`** — `CONTINUE` / `BLOCK`

Results from several hooks are merged by `HookResult.merge(...)` under the precedence
`deny > ask > allow` and `block > continue`.

### Factories

```java
HookResult.success();                        // ALLOW + CONTINUE
HookResult.allow();                          // the explicit spelling of success()
HookResult.withFeedback("...");              // ALLOW + CONTINUE + a message for the model
HookResult.block("reason");                  // BLOCK — meaningful only in the four blockable chains
HookResult.deny("reason");                   // DENY + BLOCK (permission chain)
HookResult.ask("reason");                    // ASK — asks the user to confirm
HookResult.withUpdatedInput(toolInput);      // preTool input transformation
HookResult.withUpdatedOutput(toolResult);    // postTool output transformation
HookResult.asyncRewake(spec);                // a request to be woken again later
HookResult.builder()...build();              // set several axes at once
```

> The reason passed to `deny(reason)` / `block(reason)` is **stored in the feedback field.** The
> feedback of a blocked result *is* its refusal reason, so do not surface it a second time as
> advisory feedback.

### How feedback reaches the model

`withFeedback(msg)` is the only way to speak to the model. Do not assemble the text yourself —
render it through `HookFeedback`.

Rendering is only half the contract — **the firing site has to read the returned result** for
the feedback to reach the model. Today there are exactly three such paths; every other firing
site simply discards the result.

| Chain | Where the feedback goes | Rendered in |
|------|--------------------|-----------|
| `PERMISSION_REQUEST` / `PRE_TOOL` / `POST_TOOL` | Appended to that tool's result as `<system-reminder key="hook-feedback">` | `SingleToolInvoker` |
| `ON_START` | Added to the conversation as a user-role message (same wrapper) | `OrcaAgentExecutor`, `DefaultSubagentExecutor` |
| `PRE_COMPACT` | Not a message — merged into the summarisation prompt as a **custom instruction** | `DefaultCompactionEngine` |

Feedback from the tool-related chains cannot become a separate user message because no user turn
may sit between a `tool_use` and its `tool_result`.

> ⚠️ **The other 8 events silently drop feedback** — the firing sites for
> `PERMISSION_DENIED`, `ON_STOP`, `ON_SESSION_START`, `ON_SESSION_END`, `SUBAGENT_START`,
> `SUBAGENT_STOP`, `POST_COMPACT` and `ON_CONFIG_RELOAD` never read the return value; they call
> for side effects only. "Lifecycle chains append a user message" applies to `ON_START` alone.
> Opening a new path is a feature, not a bug fix — it needs a call site that decides where the
> block goes.

> The feedback of a blocked result is its refusal reason, so `HookFeedback.collectAdvisory(...)`
> filters it out: the blocking site already renders that reason as an error.

---

## Execution policy

`HookExecutionPolicy` decides how a chain runs.

| Item | Default | Description |
|------|--------|------|
| `timeout` | 30 seconds | The outer safety net for a single hook |
| `timeoutBehavior` | `FAIL_OPEN` | On timeout, pass (`FAIL_OPEN`) or block (`FAIL_CLOSED`) |
| `executionMode` | `SEQUENTIAL` | Parallel execution is opt-in |
| `stopOnBlocked` | Policy-dependent | Short-circuits the remaining hooks once a blocking result appears |
| `dedupKeyExtractor` | None | Removes duplicate hooks sharing a key |

- **`timeoutFor(hook)`** is the time actually applied. If a hook declares a budget through
  `getExecutionBudget()` that is **at least** the policy timeout, the net widens to it
  (plus `DECLARED_BUDGET_GRACE`, 5 seconds); a shorter declaration is ignored. The comparison is
  `<`, so **a budget exactly equal to the policy timeout still gets the grace** — because
  `ShellAction.DEFAULT_TIMEOUT` and `HookExecutionPolicy.DEFAULT_TIMEOUT` are both 30 seconds,
  every declarative shell hook that omits `timeoutMs` lands in exactly this case. Excluding
  equality would make the net race the hook's own deadline under the default settings.
- **A declared budget is clamped by `MAX_DECLARED_BUDGET` (10 minutes).** It comes from
  configuration (`timeoutMs` in `hooks.json` or frontmatter) and is therefore unvalidated;
  without a ceiling one hook could hold a turn indefinitely, and values near `Long.MAX_VALUE`
  overflow the executor's nanosecond conversion. Anything larger is truncated to 10 minutes with
  a WARN log.
- **In parallel mode** a timeout **bounds the wait; it does not discard work that already
  finished.** Results are always reassembled in registration order.
- **`stopOnBlocked` is meaningful only under `SEQUENTIAL`.** Under `PARALLEL` an already
  submitted hook cannot be cancelled, so it is a no-op and every result is returned as is.
- **A wait aborted by an interrupt is BLOCKED regardless of the policy.** Once the thread driving
  the turn is interrupted, `future.get` throws without waiting, so routing that exception through
  `onException` would let every hook in the chain pass fail-open **without having run**. No verdict
  means no permission to proceed, so the executor answers BLOCKED (the same reason
  `TimeoutBehavior.FAIL_CLOSED` exists). A hook that already finished is unaffected — its completed
  result comes back as is.

### Reattaching an async rewake

As with `getExecutionBudget()`, rewake has different reattachment rules per trigger kind
(`DeclarativeRewake`):

- **`delay` / `event`** — the envelope fires exactly once, so the spec must be reattached on
  every fire for the next link to exist. The chain is bounded by `RewakeSpec#getMaxAttempts()`.
- **`cron`** — the envelope is registered as the scheduler's native cron trigger and repeats by
  itself, so it is **not reattached.** Reattaching would add an extra chain envelope per fire,
  branching the live envelope count by 2× per fire (roughly `2^(maxAttempts-1)`).
- **Exceptions** are mapped through `onException`. Under a `failClosedStopOnBlocked` policy a bug
  in the hook turns into a block, so it is safer to catch the exception inside the hook and turn
  it into an explicit result.

---

## A complete example

### A security and audit hook system

```java
package at.aimon.example.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookResult;

/**
 * An example hook system set up for security and auditing.
 */
public class SecurityAuditHookSetup {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditHookSetup.class);

    public HookRegistry createSecurityAuditRegistry() {
        HookRegistry registry = new DefaultHookRegistry();

        // Turn start — session initialisation
        registry.register(HookEventType.ON_START, context -> {
            log.info("[AUDIT] Turn started: agent={}, type={}, time={}", context.getInvokerName(),
                    context.getInvokerType(), context.getTimestamp());
            return HookResult.success();
        });

        // PreTool — security checks (this chain really does block)
        registry.register(HookEventType.PRE_TOOL, context -> {
            String toolName = context.getCurrentToolUse().getName();
            var input = context.getCurrentToolUse().getInput();

            if ("Bash".equals(toolName)) {
                String command = (String) input.get("command");
                if (isDangerousCommand(command)) {
                    log.warn("[SECURITY] Blocked dangerous command: {}", command);
                    return HookResult.block("Security policy violation: dangerous command blocked");
                }
            }

            if ("Read".equals(toolName) || "Write".equals(toolName)) {
                String path = (String) input.get("file_path");
                if (isSensitivePath(path)) {
                    log.warn("[SECURITY] Blocked access to sensitive path: {}", path);
                    return HookResult.block("Security policy violation: access to sensitive path blocked");
                }
            }

            return HookResult.success();
        });

        // PreTool — audit logging (a second hook on the same event; runs in registration order)
        registry.register(HookEventType.PRE_TOOL, context -> {
            log.info("[AUDIT] Tool invocation: tool={}, iteration={}, input={}",
                    context.getCurrentToolUse().getName(), context.getIterationCount(),
                    context.getCurrentToolUse().getInput());
            return HookResult.success();
        });

        // PostTool — result auditing (cannot block; observation only)
        registry.register(HookEventType.POST_TOOL, context -> {
            log.info("[AUDIT] Tool completed: tool={}, error={}", context.getToolUse().getName(),
                    context.getCurrentToolUseResult().isError());
            return HookResult.success();
        });

        // Turn end — summary
        registry.register(HookEventType.ON_STOP, context -> {
            log.info("[AUDIT] Turn ended: agent={}, success={}", context.getInvokerName(), context.isSuccess());
            return HookResult.success();
        });

        return registry;
    }

    private boolean isDangerousCommand(String command) {
        if (command == null) {
            return false;
        }
        return command.contains("rm -rf") || command.contains("sudo") || command.contains("chmod 777")
                || command.contains("> /dev/");
    }

    private boolean isSensitivePath(String path) {
        if (path == null) {
            return false;
        }
        return path.contains("/etc/passwd") || path.contains("/etc/shadow") || path.contains(".ssh/")
                || path.contains(".env");
    }
}
```

---

## Related documents

- [Hook Config Guide](hook-config-guide.en.md) — declarative hooks in `hooks.json` / SKILL.md
- [HookEventType.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/HookEventType.java)
- [HookResult.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/execution/HookResult.java)
- [HookRegistry.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/HookRegistry.java)
- [Hook system upgrade design](../../design/hook/hook-system.md) — the Phase 1–5 implementation record
- [Tool development guide](../tool/tool-development-guide.en.md)
