---
name: new-tool
description: Scaffold a new Tool implementation for the AIMON agent framework. Creates the tool class and its test class following project conventions.
user-invocable: true
allowed-tools: "Read Grep Glob Edit Write Bash"
---

# Create New Tool: $ARGUMENTS

Scaffold a new Tool for the AIMON framework.

## Prerequisites

Read the reference implementation first:
- `modules/aimon-core/src/main/java/at/aimon/core/ext/tools/file/ReadTool.java`
- `modules/aimon-core/src/test/java/at/aimon/core/ext/tools/file/ReadToolTest.java`

Also read the builder pattern reference:
- `modules/aimon-core/src/main/java/at/aimon/core/agent/AgentContent.java`

## Steps

1. **Determine placement**: Based on the tool's domain, place it in `at.aimon.core.ext.tools.{category}/`
2. **Create tool class**: Following `AbstractTool` pattern with:
   - `TOOL_NAME` constant
   - `private static final Logger log` (lowercase)
   - `createInputSchema()` with JSON Schema
   - `execute()` that never throws — returns `ToolResult.error()` on failure
   - Type-safe parameter access via `ToolInput` accessors
3. **Create test class**: `{ToolName}ToolTest` with:
   - Success cases
   - Error cases (invalid params, edge cases)
   - Null input handling
4. **Verify**: Run `./gradlew :aimon-core:test --tests "fully.qualified.{ToolName}ToolTest"`
5. **Format**: Run `./gradlew format`

## Tool Name Convention
- Class name: `{Name}Tool` (e.g., `SearchTool`, `HttpTool`)
- TOOL_NAME constant: `"{Name}"` (e.g., `"Search"`, `"Http"`)
