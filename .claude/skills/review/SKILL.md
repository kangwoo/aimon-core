---
name: review
description: Review code changes against AIMON project conventions and quality standards. Use for pre-commit reviews or PR reviews.
user-invocable: true
allowed-tools: "Read Grep Glob Bash"
---

# Code Review: $ARGUMENTS

Review code changes for AIMON project convention compliance and quality.

## Gather Context

Check what changed:
```bash
git diff --stat
```

```bash
git diff
```

## Review Checklist

### Conventions
- [ ] `class` over `record` (immutable classes with builder pattern)
- [ ] Logger: `private static final Logger log` (lowercase)
- [ ] Import order: java > javax > jakarta > org > com > (blank) > at.aimon.*
- [ ] `Objects.requireNonNull()` at constructor/method entry points
- [ ] Constants defined as `public static final String`

### Architecture
- [ ] `implementation()` not `api()` for core dependency in implementation modules
- [ ] No circular dependencies introduced
- [ ] Dependencies injected via constructor (Dependency Inversion)
- [ ] New interfaces in core, implementations in separate modules

### Tool Code (if applicable)
- [ ] `execute()` never throws — returns `ToolResult.error()`
- [ ] Type-safe ToolInput accessors used
- [ ] Stateless design, immutable I/O
- [ ] Extends `AbstractTool` with `TOOL_NAME`

### Error Handling
- [ ] Specific exceptions before general `Exception`
- [ ] Provider exceptions wrapped in framework exceptions
- [ ] Correct log levels: DEBUG/WARN/ERROR

### Tests
- [ ] Tests exist for new/modified code
- [ ] AssertJ assertions (not JUnit assertions)
- [ ] Error paths tested
- [ ] Edge cases covered

## Output

Report findings sorted by severity:
- **CRITICAL**: Must fix before merge
- **MAJOR**: Should fix before merge
- **MINOR**: Nice to have, can follow up

Include specific file:line references and suggested fixes.
