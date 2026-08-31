---
paths:
  - "**/*.java"
---

# Immutability & Builder Pattern Rules

- All domain/value objects must be immutable: `final` class, `final` fields, no setters
- Use builder pattern for object construction:
  ```java
  public final class MyClass {
      private final String field;

      private MyClass(Builder builder) {
          this.field = Objects.requireNonNull(builder.field);
      }

      public static Builder builder() { return new Builder(); }

      public String getField() { return field; }

      public static class Builder {
          private String field;
          public Builder field(String field) { this.field = field; return this; }
          public MyClass build() { return new MyClass(this); }
      }
  }
  ```
- Exception — **deserialization targets are builder-exempt.** A type that the framework only ever materializes from wire data (JSON, `ToolInput`) and that application code never assembles by hand has no assembly step for a builder to guard, so it may expose a single all-args constructor instead. Immutability itself is unchanged — `final` class, `final` fields, no setters. In-tree precedent: `Todo` has one `@JsonCreator` constructor and no builder (`Todo.java:44-46`). `GenericTool` input DTOs (`at.aimon.core.agent.tool.generic`) are the same case, written as `record`s per `.claude/rules/code-style.md`; a `record` is that shape expressed by the language. This exemption does not extend to types the application constructs — those still need the builder.
- ToolInput, ToolResult, ToolContext are all immutable — never add mutable state
- Dependencies must be injected via constructor with `Objects.requireNonNull()`
