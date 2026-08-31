---
paths:
  - "modules/aimon-core/src/**/tool/**/*.java"
  - "modules/aimon-core/src/**/tools/**/*.java"
---

# Tool Development Rules

## Critical Rules
- **Never throw exceptions from `execute()`** — Always return `ToolResult.error()`
- **Use type-safe accessors** — `input.getRequiredString()`, `input.getInteger("key", defaultValue)`, `input.getStringOrNull()`
- **Stateless design** — No mutable state between executions
- **Immutable I/O** — ToolInput, ToolResult, ToolContext are all immutable

## Tool Structure
- Extend `AbstractTool` and define `public static final String TOOL_NAME`
- Constructor calls `super(TOOL_NAME, description, createInputSchema())`
- `createInputSchema()` returns JSON Schema as `Map<String, Object>` with `type`, `properties`, `required`
- **Built-in schemas must declare `"additionalProperties", false` in the top-level map** — `BuiltInToolSchemaArchitectureTest` enforces it with no exclusion list, but only over `at.aimon.core.tools`; tools in other packages (sandbox, browser, memory derivers) follow the same rule unenforced. MCP/third-party schemas are untouched: key present → strict, absent → permissive
- Use `"integer"` (not `"number"`) when a fractional value is meaningless
- Start `execute()` with `Objects.requireNonNull(input/context)`
- `GenericTool<I, O>` (`…tool.generic`) is the alternative base for wide inputs — a `record` + `@ToolParam` generates the schema and binds the input, so there is no `createInputSchema()` and no manual accessors. See the [tool development guide](../../docs/features/tool/tool-development-guide.md#어느-베이스-클래스를-고를-것인가)

## Schema Validation
- A schema gate runs **before** `execute()` (`…tool.schema`): `required` presence, `type`, `enum`, unknown parameter name
- It does **not** check ranges (`minimum`/`maxLength`/…) — keep those checks in `execute()`
- Modes: `OFF` / `WARN` (default — logs, still executes) / `ENFORCE` (rejects)

## Error Handling in Tools
- `IllegalArgumentException` → `log.warn()` + `ToolResult.error("Invalid parameter: ...")`
- Expected errors (FileNotFound, Security) → `log.warn()` + `ToolResult.error()`
- Unexpected errors → `log.error(msg, e)` (with stacktrace) + `ToolResult.error()`
- Log levels: DEBUG for normal flow, WARN for expected errors, ERROR for unexpected errors

## Permission System
- The common path is `ToolPermissionSubjectAware` — the tool returns the one value to be judged (`PermissionSubject.command(...)` or `PermissionSubject.path(...)`) and the framework matches it. `BashTool` and the file tools take this path
- `CustomToolPermissionAware` stays for tools whose permission target is not a single value (`BrowserTool`'s `action:url`). A tool implementing both is judged by its subject first
- Read the value with `input.get(key) instanceof String s` inside `permissionSubject(...)` — this runs before `execute()`, so `getStringOrNull`'s `IllegalArgumentException` has nowhere to land
- AllowedTool patterns: `"Read"` (simple), `"Bash(git:*)"` (COMMAND pattern), `"Read(/tmp/**)"` (PATH glob), `"Bash(npm install)"` (exact)
- An empty subject means "cannot be judged" → **denied** when a pattern is configured. A bare name mixed with a patterned entry for the same name is **not** unlimited

## Reference Implementation
- See `ReadTool` (`at.aimon.core.tools.file.ReadTool`) as the canonical example
