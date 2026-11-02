---
translated_from: docs/features/tool/tool-search-guide.md
source_commit: 3ff37d9e
---

# Tool Search Guide

How to use the feature that, in an agent with many tools, keeps some tool definitions out of the first LLM request and
adds them to the offered list when the agent finds them with `ToolSearch`.

Why it is built this way is in the design document [`tool-search.md`](../../design/tool/tool-search.md).

---

## 1. When to use it

| Situation | Use |
|------|------|
| Few tools, and most executions use them | The default registry (this feature is not needed) |
| Tool definitions take up a large part of the prompt | Tool search |
| So many candidates that the model picks the wrong tool | Tool search |

Keep frequently used tools eager and make only the occasional ones deferred. Using a deferred tool costs the model a
`ToolSearch` call and one more LLM round trip.

---

## 2. Turning it on

**It can only be turned on when you assemble the runtime by hand.** `OrcaAgentRuntimeFactory` creates its registry
itself and has no place to accept one. `AimonStack`, the CLI and Spring Boot have no setting either. This seam is
[TC-9](../../backlog/tool-contract-open-items.md) in the register.

```java
ToolSearchCatalog catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());

// ToolSearch must be registered as eager
catalog.register(SearchableTool.eager(new ToolSearchTool()));

// Frequently used tools
catalog.register(SearchableTool.eager(readTool));

// Tools that must be found before they are offered
catalog.register(SearchableTool.deferred(ticketTool));

OrcaAgentRuntime runtime = OrcaAgentRuntime.builder()
        .agent(agent)
        .toolRegistry(catalog)
        // The assembly fills in the remaining fields itself
        .build();
```

The assembly has two jobs to do itself.

- **Provider registration.** The tool registration the factory did through `OrcaToolProvider` falls to the assembly. A
  tool a provider registers with `register(Tool)` is eager.
- **Registering `ToolSearchTool`.** The catalog does not add it on its own.

The code does not stop you registering `ToolSearchTool` as deferred. Doing so removes the only route to the deferred
tools, and the model answers without knowing them and without any error.

The executor takes the search path only when the registry is a `ToolSearchCatalog`. There is nothing to set on the
executor.

---

## 3. How the agent uses it

`ToolSearch` takes two inputs.

| Parameter | Required | Description |
|----------|------|------|
| `query` | Yes | The search text |
| `max_results` | — | Default 5. Clamped to 1 – 20 |

The shape of `query` picks the mode.

| Mode | Example | Behaviour |
|------|-----|------|
| Keyword | `list directory` | By relevance across name, description and parameters |
| Direct selection | `select:WebFetch,Grep` | Exact name match |
| Required keyword | `+slack send` | Keeps only names containing `slack`, ranked by `send` |

The response is the full schema of each tool found.

```json
{"functions": [{"name": "WebFetch", "description": "...", "parameters": { }}]}
```

A tool found this way is in the offered list from the next iteration. There is no separate activation call. Finding
nothing is not an error and returns `{"functions": []}`. Eager tools are not search candidates, so naming one in
`select:` does not put it in the result.

---

## 4. How long activation lasts

Activation lasts for **one turn or one fork**.

- In the next turn of the same session the model must call `ToolSearch` again
- A fork does not inherit the tools its parent activated

The name `createSessionRegistry` and the "per-session" comments in the code do not match the real lifetime. Do not go by
them.

---

## 5. Permissions

`ToolSearch` is subject to the allow-list too. In an agent or subagent with an allow-list, `ToolSearch` must be on the
list for deferred tools to be found.

```text
allowed-tools: Read, ToolSearch, WebFetch
```

Activation is not permission.

- A tool that was found but is not on the allow-list is not offered, and running it is refused. The `ToolSearch` response can still carry its schema
- A deferred tool that was never activated still runs if the model names it and it passes the allow-list. Do not use deferred as access control

`ToolSearchTool` does not override its side-effect declarations, so it is `MUTATING` + `DESTRUCTIVE`.

- Under a `READ_ONLY` ceiling `ToolSearch` is not offered, so deferred tools cannot be found
- With `SideEffectApprovalGate` wired in, every `ToolSearch` call asks for approval

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|------|------|------|
| `ToolSearchRegistry not available in tool context` | `ToolSearchTool` is registered but the runtime's registry is not a `ToolSearchCatalog` | Assemble with `toolRegistry(catalog)`, or do not register `ToolSearchTool` |
| `Query cannot be empty` | `query` is blank | Give it search text |
| An `is offered no tools` warning in the log, and the agent answers without tools | Nothing is left to offer after the allow-list and the side-effect ceiling. A typo does this, so does a list naming only tools above the ceiling, and so does a list omitting `ToolSearch` in a deferred deployment | Fix the allow-list. The run is recorded as a success, so watch for the warning |
| The model does not use deferred tools | `ToolSearch` is not offered: it was registered as deferred, it is not on the allow-list, or the ceiling is `READ_ONLY` | Check §2 and §5 |
| A tool disappears on the next turn | Activation lasts one turn | This is expected. §4 |
| A `select:` result is empty | The name is wrong, or the tool is eager | This can be expected. §3 |
