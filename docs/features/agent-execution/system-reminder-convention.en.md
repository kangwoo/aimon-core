---
translated_from: docs/features/agent-execution/system-reminder-convention.md
source_commit: a9821d44
---

# System Reminder Convention

> The AIMON project's standard convention for injecting synthetic user-role context using the `<system-reminder>` tag.

## Purpose

AIMON has to hand the LLM a few pieces of "supporting context" during an agent execution — the current working directory, today's date, the contents of `CLAUDE.md`, or a state reminder inserted midway through the ReAct loop, for instance. Such messages are **hints for the LLM**, and **not something the end user actually typed**, so they have to be marked explicitly for the model not to confuse the two.

This convention borrows the pattern the reference implementation uses and wraps synthetic context in a `<system-reminder key="...">...</system-reminder>` block. The implementation is available as the [`SystemReminderFormatter`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) utility.

## When to use it

Use `<system-reminder>` when injecting synthetic context **inside a user-role message**.

### When you should

- Session metadata (working directory, date, hostname and so on).
- Project memory (the body of `CLAUDE.md`, a skill's description).
- Ephemeral state appended to a tool's result (a "the file has been modified" notice, say).
- A one-off reminder that corrects the agent's behaviour.

### When you should not

- **A standing instruction to the assistant** → that belongs in the **system prompt**. `<system-reminder>` lives inside a user message, so it is not the right place to express a permanent policy.
- **Text the end user actually typed** → pass it through as it is. Do not wrap it.
- **A tool's return value itself** → return it as the content of `ToolResult.success(...)`; it reaches the LLM as a tool-result message.

## The tag format

A single block:

```
<system-reminder key="<key>">
<body>
</system-reminder>
```

Several blocks are concatenated, separated by a single blank line (`\n\n`).

### Rules for `key`

- Must match `[A-Za-z0-9._-]+`.
- Cannot be empty.
- Dot notation denoting the scope is recommended (`session.cwd`, `memory.project_md`, …).

### Rules for `body`

- XML metacharacters are escaped: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;` (in that order).
- It cannot already contain the substring `<system-reminder` or `</system-reminder>` (guarding against nesting and spoofing — an `IllegalArgumentException` is raised).
- An empty body is allowed (only a blank line is left between the markers).

## The list of reserved keys

Please fill in the table below once a follow-up PR starts using `<system-reminder>` for real. When introducing a new key, register it here first and include that in the PR review.

| key | purpose | introduced in |
|-----|---------|---------------|
| _(no key registered yet)_ | | |

## Examples

### A single reminder

```java
String block = SystemReminderFormatter.wrap(
        "session.cwd",
        "/home/kangwoo/projects/aimon-core");
```

Result:

```
<system-reminder key="session.cwd">
/home/kangwoo/projects/aimon-core
</system-reminder>
```

### Several reminders (order preserved)

```java
Map<String, String> parts = new LinkedHashMap<>();
parts.put("session.cwd", "/tmp");
parts.put("session.date", "2026-04-23");

String injected = SystemReminderFormatter.wrapMany(parts);
```

Result:

```
<system-reminder key="session.cwd">
/tmp
</system-reminder>

<system-reminder key="session.date">
2026-04-23
</system-reminder>
```

### Escaping XML metacharacters

```java
SystemReminderFormatter.wrap("diff", "a < b && c > d");
// → body: "a &lt; b &amp;&amp; c &gt; d"
```

## Related documents

- [`SystemReminderFormatter`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) — the implementation.
