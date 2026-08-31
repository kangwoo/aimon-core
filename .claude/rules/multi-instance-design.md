---
paths:
  - "modules/aimon-core/src/main/**/*.java"
---

# Multi-Instance Design Rules

## Core Principle
Stateful components must separate storage behind interfaces so they work in scale-out (multi-instance) environments. Default implementations are in-memory, but swapping storage must be an implementation change, not a refactoring.

## Architecture Pattern
```
aimon-core (interfaces/abstractions)
    ^
    |
aimon-*-impl (concrete implementations)
```

## Rules
- Core module defines interfaces: `LlmClient`, `VirtualFileSystem`, `Tool`, scheduling interfaces
- Implementations go in separate modules: `aimon-llm-openai`, `aimon-filesystem-gridfs`, `aimon-filesystem-s3`, etc.
- Depend on abstractions, not concretions (Dependency Inversion Principle)
- All dependencies injected via constructor — no `new ConcreteClass()` in business logic
- In-memory implementations serve as defaults and reference implementations
