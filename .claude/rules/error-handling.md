---
paths:
  - "**/*.java"
---

# Error Handling Rules

## General Principles
- Base exception: `AimonException` (`at.aimon.core.base`)
- Fail fast with `Objects.requireNonNull()` for required parameters
- Never silently swallow exceptions — log or propagate appropriately

## In Tool execute() Methods
- **Never throw exceptions** — wrap all errors in `ToolResult.error()`
- Catch specific exceptions first, then general `Exception` as fallback
- Include meaningful error messages for LLM to understand what went wrong

## Logging Levels
| Level | When to Use |
|-------|-------------|
| DEBUG | Normal operation flow, parameter values |
| WARN  | Expected errors (bad input, file not found, access denied) |
| ERROR | Unexpected errors, system failures (include stacktrace via `log.error(msg, e)`) |

## Exception Wrapping
- Provider-specific exceptions (e.g., OpenAI SDK) must be wrapped in framework exceptions (e.g., `LlmClientException`)
- Never expose external SDK exception types across module boundaries
