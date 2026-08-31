---
translated_from: docs/features/tool/tool-development-guide.md
source_commit: c976edc7
---

# Tool Development Guide

> The complete guide to developing tools for the LLM agent

This document provides everything you need when developing a Tool in the aimon-core framework.

## Table of contents

1. [Overview](#overview)
2. [Core classes](#core-classes)
3. [Tool creation patterns](#tool-creation-patterns)
4. [Using ToolInput](#using-toolinput)
5. [ToolResult return patterns](#toolresult-return-patterns)
6. [Using ToolContext](#using-toolcontext)
7. [Defining the JSON Schema](#defining-the-json-schema)
8. [Error handling](#error-handling)
9. [The permission system](#the-permission-system)
10. [Concurrency safety (ConcurrencyBehavior)](#concurrency-safety-concurrencybehavior)
11. [A complete example](#a-complete-example)
12. [Checklist](#checklist)

---

## Overview

A Tool is the core component that lets an LLM agent interact with external systems.

### Core principles

| Principle | Description |
|-----------|-------------|
| **Single Responsibility** | One Tool performs exactly one clearly defined job |
| **Fail-Safe Design** | The `execute()` method never throws |
| **Immutability** | ToolInput, ToolResult and ToolContext are immutable objects |
| **Type Safety** | Use ToolInput's type-safe accessors |
| **Stateless** | A Tool keeps no state between executions |

### Package structure

```
at.aimon.core.agent.tool/
├── Tool.java                 # the Tool interface
├── AbstractTool.java         # the base implementation class
├── ToolInput.java            # the input-parameter wrapper
├── ToolResult.java           # the execution result
├── ToolContext.java          # the runtime context
├── ToolRegistry.java         # the Tool registry
└── exception/                # the exception classes
```

---

## Core classes

### The Tool interface

```java
public interface Tool {
    /**
     * Returns the Tool's definition (name, description, schema).
     */
    ToolDefinition getDefinition();

    /**
     * Executes the Tool.
     *
     * @param input   the input parameters
     * @param context the runtime context
     * @return the execution result (success or error)
     */
    ToolResult execute(ToolInput input, ToolContext context);
}
```

### The AbstractTool class

The base class for every Tool. It offers two constructors:

```java
// a static definition (when the metadata does not change)
public AbstractTool(String name, String description, Map<String, Object> inputSchema)

// a dynamic definition (when the description can change at runtime)
public AbstractTool(ToolDefinitionProvider definitionProvider)
```

---

## Tool creation patterns

### Which base class to choose

There are two base classes. **`AbstractTool` is the default**, and `GenericTool<I, O>`
(`at.aimon.core.agent.tool.generic`) is opt-in — it stands beside `AbstractTool` rather than replacing it.

| Situation | What to choose |
|-----------|----------------|
| Three or four parameters at most, mostly required | `AbstractTool` — writing the schema by hand is cheaper than creating one more type |
| **Five or more** parameters, or many optional ones | `GenericTool<I, O>` — the schema and the parameter extraction both come out of **a single declaration** (the input `record`), so the two cannot disagree |
| The set of input keys is decided at runtime (MCP delegation …) | `AbstractTool` — there is no compile-time type to bind to |

Choosing `GenericTool` brings three things with it.

- **The schema is derived from the input `record`** — `additionalProperties: false` included, down through
  nested `record`s. It becomes structurally impossible for a hand-written schema and a hand-written
  parameter extraction to say different things
- **The binding reports every violation at once** — and in the same wording as the executor's schema gate
- **`execute()` is `final`** — the tool contract "it does not throw" is enforced by the type rather than by
  a subclass's diligence. There are only two things to implement: `doExecute(I, ToolContext)` and `render(O)`

Write the input type as a `record`. This is the **only exception** to the project's `class`-preferring convention, and its scope is the input DTO of `GenericTool` alone — domain types, value objects and configuration objects are not included (`.claude/rules/code-style.md`).

```java
public record EchoInput(
        @ToolParam(required = true, description = "The message to repeat") String message,
        @ToolParam(description = "How many times (default: 1)") Integer times) {
}

public class EchoTool extends GenericTool<EchoInput, String> {

    public EchoTool() {
        super("Echo", "Repeats a message back", EchoInput.class);
    }

    @Override
    protected String doExecute(EchoInput input, ToolContext context) {
        return input.message().repeat(input.times() == null ? 1 : input.times());
    }

    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }
}
```

Wire names are **declared, not converted** — if a parameter's name is snake_case, write it as `@ToolParam(name = "file_path")`. The generator performs no automatic camelCase→snake_case conversion (some names, such as `Grep`'s `-i` and `-A`, cannot be identifiers in the first place).

From the next section onward, the `AbstractTool` path is described.

### Basic structure

```java
package at.aimon.core.tools.example;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * A description of the Tool's purpose and capabilities.
 *
 * <p>
 * This Tool provides the following:
 * <ul>
 * <li>capability 1
 * <li>capability 2
 * </ul>
 *
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * Tool tool = new ExampleTool(dependency);
 * ToolInput input = ToolInput.of(Map.of("param1", "value1"));
 * ToolResult result = tool.execute(input, ToolContext.empty());
 * }
 * </pre>
 */
public class ExampleTool extends AbstractTool {

    public static final String TOOL_NAME = "Example";

    private static final Logger log = LoggerFactory.getLogger(ExampleTool.class);

    // dependencies (if any)
    private final SomeDependency dependency;

    /**
     * Creates an ExampleTool.
     *
     * @param dependency the dependency required (must not be null)
     * @throws NullPointerException if dependency is null
     */
    public ExampleTool(SomeDependency dependency) {
        super(TOOL_NAME,
              "A concise, clear description of the Tool. " +
              "Written so the LLM can tell when it should use this Tool.",
              createInputSchema());
        this.dependency = Objects.requireNonNull(dependency, "Dependency cannot be null");
    }

    /**
     * Creates the input schema.
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "required_param", Map.of(
                    "type", "string",
                    "description", "a description of the required parameter"
                ),
                "optional_param", Map.of(
                    "type", "integer",
                    "description", "a description of the optional parameter (default: 10)"
                )
            ),
            "required", List.of("required_param")
        );
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        // 1. null checks
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // 2. extract the parameters
            final String requiredParam = input.getRequiredString("required_param");
            final int optionalParam = input.getInteger("optional_param", 10);

            log.debug("Executing with: required={}, optional={}", requiredParam, optionalParam);

            // 3. perform the business logic
            String result = performOperation(requiredParam, optionalParam);

            // 4. return the success result
            log.debug("Successfully completed operation");
            return ToolResult.success(result);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (SpecificException e) {
            log.warn("Operation failed: {}", e.getMessage());
            return ToolResult.error("Operation failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String performOperation(String param1, int param2) throws SpecificException {
        // the actual business logic
        return "Result";
    }
}
```

---

## Using ToolInput

ToolInput is the **immutable** wrapper around a Tool's input parameters.

### Parameter access methods

#### Required parameters

If the parameter is missing or its type does not match, it throws `IllegalArgumentException`.

```java
String value = input.getRequiredString("param_name");
int number = input.getRequiredInteger("count");
boolean flag = input.getRequiredBoolean("enabled");
```

#### Optional parameters (with a default)

If the parameter is missing, the default is returned.

```java
String value = input.getString("param_name", "default");
int number = input.getInteger("count", 100);
boolean flag = input.getBoolean("enabled", false);
long bigNumber = input.getLong("size", 1000L);
```

#### Nullable parameters

If the parameter is missing, null is returned.

```java
String value = input.getStringOrNull("param_name");
Integer number = input.getIntegerOrNull("count");
Boolean flag = input.getBooleanOrNull("enabled");
Long bigNumber = input.getLongOrNull("size");
```

### Validation methods

```java
// check whether a parameter is present
if (input.has("optional_param")) {
    // handle it
}

// look up all the keys
Set<String> keys = input.keys();

// check the size
int size = input.size();
boolean empty = input.isEmpty();
```

### Creating a ToolInput (for tests)

```java
// empty input
ToolInput empty = ToolInput.of();

// from a Map
ToolInput fromMap = ToolInput.of(Map.of("key1", "value1", "key2", 42));

// convenience methods (up to 10 parameters)
ToolInput simple = ToolInput.of("file_path", "/path/to/file");
ToolInput multi = ToolInput.of("path", "/dir", "recursive", true);
```

---

## ToolResult return patterns

ToolResult is the **immutable** value object holding a Tool's execution result.

### Success results

```java
// a plain success
return ToolResult.success("Operation completed successfully");

// a success carrying data
return ToolResult.success(formattedOutput);
```

### Error results

```java
// the error message alone
return ToolResult.error("File not found: " + path);

// the error message plus the exception (for debugging)
return ToolResult.error("Failed to read file: " + e.getMessage(), e);

// the exception alone (its message, or its class name)
return ToolResult.error(exception);
```

### Reading the result

```java
String content = result.getContent();         // the message/output
boolean isError = result.isError();           // whether it is an error
boolean isSuccess = result.isSuccess();       // whether it succeeded
Optional<Exception> ex = result.getException(); // the exception (if any)
```

---

## Using ToolContext

ToolContext is the **immutable** container holding runtime context information.

### Accessing the context

```java
// returns an Optional
Optional<Object> value = context.get("key");

// type-safe access
Optional<VirtualFileSystem> vfs = context.get("fileSystem", VirtualFileSystem.class);

// check for presence
if (context.containsKey("environment")) {
    // handle it
}

// the whole context (read-only)
Map<String, Object> all = context.getContext();
```

### Common context keys

| Key | Type | Description |
|-----|------|-------------|
| `fileSystem` | `VirtualFileSystem` | the filesystem instance |
| `environment` | `Environment` | the environment configuration |
| `executorType` | `InvokerType` | the kind of invoker (MAIN_AGENT, SUBAGENT …) |
| `read_tool.read_files` | `Set<String>` | the list of files that were read (set by ReadTool) |

### Creating a context (for tests/initialisation)

```java
// an empty context
ToolContext empty = ToolContext.empty();

// the builder pattern
ToolContext context = ToolContext.builder()
    .put("fileSystem", vfs)
    .put("environment", env)
    .put("executorType", InvokerType.MAIN_AGENT)
    .build();
```

---

## Defining the JSON Schema

A Tool's input schema follows the [JSON Schema](https://json-schema.org/) format.

### Basic structure

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            // the parameter definitions
        ),
        "required", List.of(/* the names of the required parameters */)
    );
}
```

### Supported types

#### String

```java
"param_name", Map.of(
    "type", "string",
    "description", "the parameter description"
)
```

#### Number (decimals allowed)

```java
"threshold", Map.of(
    "type", "number",
    "description", "the threshold (default: 0.5)"
)
```

#### Integer (integers only)

```java
"count", Map.of(
    "type", "integer",
    "description", "the number of items (default: 10)"
)
```

`number` lets `1.5` through, while `integer` **rejects anything with a fractional part**. Declare parameters that only make sense as integers — line numbers, counts, timeouts — as `integer`.

`3.0` does pass `integer`, though — JSON `3` parses as `Integer` and `3.0` as `Double`, but both mean the same integer, and the model cannot deliberately produce that difference. What is rejected is a genuine fractional part (`3.5`).

#### Boolean

```java
"enabled", Map.of(
    "type", "boolean",
    "description", "whether the feature is enabled"
)
```

#### Enum (a choice of strings)

```java
"output_mode", Map.of(
    "type", "string",
    "description", "the output mode: content, files_with_matches, count",
    "enum", List.of("content", "files_with_matches", "count")
)
```

### `additionalProperties: false` — built-in tools must include it

When the model sends a parameter that was not declared, JSON Schema's default is to **allow** it. Leave that default in place and a typo (`file_paht`) is silently ignored, so the tool raises the wrong error about a missing required parameter. That is why **we put this key into the schema of every tool we own, explicitly.**

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "additionalProperties", false,   // ← at the top level
        "properties", Map.of(/* ... */),
        "required", List.of("file_path")
    );
}
```

- **Every built-in tool declares it, without exception.** `BuiltInToolSchemaArchitectureTest` checks it at
  build time and there is no exclusion list — but **its scope is the `at.aimon.core.tools` package**.
  Built-in tools living elsewhere (`at.aimon.core.memory.deriver.tool`, `at.aimon.sandbox.tool`, the
  Playwright and GraalJS tools) follow the same rule, but this test does not protect them. Scoping it by
  package convention is deliberate, and the reason is in the test's javadoc — scanning every subtype of
  `Tool` catches `at.aimon.core.mcp.McpTool`, whose advertised schema is the server's, not ours. The check
  looks at **the top-level map only** — attaching the key to array item schemas alone leaves the place
  where the model's call is actually checked empty, so it does not pass.
- **Do not touch third-party schemas such as MCP's.** The validator does not ask "who made this tool";
  it decides purely on **strict if the key is present, lenient if it is not**. Turning strictness on is the
  schema owner's declaration to make.
- `GenericTool` inserts this key **automatically** (down through nested `record`s). You never write it by hand.

With the key present, a typo comes back like this —
`Unknown parameter 'file_paht'. Did you mean 'file_path'? The tool was not executed.`

### A complete schema example

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "additionalProperties", false,
        "properties", Map.of(
            "file_path", Map.of(
                "type", "string",
                "description", "the absolute path of the file to read"
            ),
            "offset", Map.of(
                "type", "integer",
                "description", "the line number to start from (1-based). Provide only for large files"
            ),
            "limit", Map.of(
                "type", "integer",
                "description", "the number of lines to read. Provide only for large files"
            ),
            "encoding", Map.of(
                "type", "string",
                "description", "the file encoding (default: UTF-8)",
                "enum", List.of("UTF-8", "ISO-8859-1", "EUC-KR")
            )
        ),
        "required", List.of("file_path")
    );
}
```

> The example above is how to write a new schema. The real `ReadTool`'s `offset`/`limit` are still declared as `"number"` — introducing the validator was not a job of rewriting the existing declarations wholesale. Moving them is a separate change that narrows the contract.

---

## Error handling

### The core principle

> **Never throw from the `execute()` method.**
> Every error must be returned as `ToolResult.error()`.

### Schema validation runs before `execute()`

`DefaultToolExecutor` checks the input against the schema the tool declared **before** calling the tool (`at.aimon.core.agent.tool.schema`). That is, the four things below do not need to be re-checked inside `execute()`.

| Check | What it catches |
|-------|-----------------|
| `required` | A declared required parameter is missing (a JSON `null` also reads as **missing** — `ToolInput` drops null values) |
| `type` | A type mismatch (a decimal for an `integer`, say) |
| `enum` | A value outside the declared allowed set |
| Undeclared names | Typos — but only when the schema has `additionalProperties: false` |

**You also need to know what the gate does not do.**

- **Ranges are the tool's business.** `minimum`/`maximum`/`minLength`/`minItems`/`default` are ignored even
  when declared, because tools handle them differently — `BashTool` **clamps rather than rejects** an
  oversized timeout, so a gate enforcing `maximum` would turn a call that quietly succeeds today into an
  error. The boundary is **shape at the gate, range at the tool**. Checks like `offset < 1` therefore stay
  inside `execute()`
- **Nesting is inspected one level deep.** For `object`/`array` properties it only checks that they are of
  that type, not what is inside. A tool whose nested contract matters is better bound with `GenericTool`
- **What it does not understand, it lets through.** Properties using `$ref`/`oneOf`/`anyOf`, or whose type
  name it cannot determine, are skipped (a deliberate leniency so that MCP schemas are not rejected)

How it reacts is set by `SchemaValidationMode` — `OFF` (no validation) / **`WARN` (the default: log the violation and run the tool anyway)** / `ENFORCE` (do not run, and return the violation sentence to the model as an error). Observation first, rejection second.

`GenericTool`'s parameter binding uses the same wording. The two layers catch things at different moments, but there is no reason for the model to learn the same complaint in two different voices.

> **One behaviour change.** `ToolInput`'s `"10"` → `10` string-to-number coercion is unreachable under `ENFORCE` — validation filters it out first. It is a deliberate tightening of the contract, which is why the default is `WARN`.

### The recommended pattern

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    Objects.requireNonNull(input, "Input cannot be null");
    Objects.requireNonNull(context, "Context cannot be null");

    try {
        // extract the parameters
        final String path = input.getRequiredString("file_path");

        // validate the parameters
        if (path.isEmpty()) {
            return ToolResult.error("file_path cannot be empty");
        }

        // the business logic
        String result = performOperation(path);
        return ToolResult.success(result);

    } catch (IllegalArgumentException e) {
        // a parameter-related error
        log.warn("Invalid parameter: {}", e.getMessage());
        return ToolResult.error("Invalid parameter: " + e.getMessage());

    } catch (FileNotFoundException e) {
        // an expected error (warning level)
        log.warn("File not found: {}", e.getMessage());
        return ToolResult.error("File not found: " + e.getMessage());

    } catch (SecurityException e) {
        // a security-related error
        log.warn("Access denied: {}", e.getMessage());
        return ToolResult.error("Access denied: " + e.getMessage());

    } catch (IOException e) {
        // an I/O error (error level + stack trace)
        log.error("I/O error: {}", e.getMessage(), e);
        return ToolResult.error("I/O error: " + e.getMessage());

    } catch (Exception e) {
        // an unexpected error (error level + stack trace)
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ToolResult.error("Unexpected error: " + e.getMessage());
    }
}
```

### Logging-level guidelines

| Level | When to use it |
|-------|----------------|
| `DEBUG` | Normal operational flow, parameter values |
| `WARN` | Expected errors, user input mistakes |
| `ERROR` | Unexpected errors, system failures (include the stack trace) |

---

## The permission system

An entry in the allow-list is either `"Name"` or `"Name(pattern)"`. **The name decides whether that tool may run**, and **the pattern decides which of its calls may run**. Only the tool can know what the pattern should be compared against (`Bash` compares the `command`, `Read` the `file_path`), so the tool hands that value out as a `PermissionSubject`.

### ToolPermissionSubjectAware — usually this one

A tool whose subject is determined by a single input field only has to implement this interface. Picking the matcher and comparing the pattern is the framework's job — no `CustomToolPermissionRule` is needed.

```java
public class BashTool extends AbstractTool implements ToolPermissionSubjectAware {

    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        // read the raw value: getStringOrNull throws on a type mismatch, and this code runs before
        // execute(), so there is nowhere to catch that exception. A non-string command cannot be
        // judged = it is as good as absent.
        if (!(input.get("command") instanceof String command) || command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PermissionSubject.command(command));
    }
}
```

**An empty value is not an abstention but "cannot be judged".** If a pattern is configured for that tool, the validator **denies** the call. Guessing and letting it through (resolving a relative path against the process CWD, say) would make the outcome depend on where the JVM started, which whoever wrote the pattern cannot predict.

### Kind — the kind of value picks the matcher

| Kind | What is judged | Matcher | Example |
|------|----------------|---------|---------|
| `COMMAND` | A shell command line | `ToolPattern` — only a trailing `:*` is a wildcard, everything else is an exact match | `Bash(git:*)` |
| `PATH` | A file path | `PathPattern` — globs (`**`, `*`) | `Read(/tmp/**)` |

The kind cannot be recovered from the spec string. The `AllowedTool` parser only looks at the parentheses and does not interpret `:`, so `Read(/tmp/**)` and `Bash(git:*)` are indistinguishable to it — which is why **the tool** ships the kind alongside.

There are two matchers because one cannot do both jobs. `ToolPattern` rejects a candidate containing shell metacharacters (`;` `|` `&` `` ` `` `$` `>` `<` `(` `)`), which is the right defence for a string headed to `bash -c` but, applied to paths, would put an ordinary file like `report(1).csv` permanently out of reach.

A `PATH` subject must be **absolute and lexically normalised**. File tools resolve a relative path against the `Environment`'s working directory and fold `..` before handing it out, so `/tmp/../etc/passwd` does not pass `Read(/tmp/**)`. Symbolic links are not resolved, though — a permission pattern narrows **what the agent may ask for**, and isolation is the sandbox's job.

### CustomToolPermissionAware — when one value is not enough

A tool that has to judge on a combination of inputs, or by consulting an external policy, implements the rule itself. The only current case is `Browser`, which judges with a grammar of its own, `action:url`. That `:` is not a wildcard marker but a separator joining two values, and it is one tool's notation, so it was not promoted to a third `Kind`.

```java
public class BrowserTool extends GenericTool<BrowserInput, String> implements CustomToolPermissionAware {

    private final CustomToolPermissionRule permissionRule = new BrowserToolPermissionRule();

    @Override
    public CustomToolPermissionRule getCustomPermissionRule() {
        return permissionRule;
    }

    // ... the rest of the implementation
}
```

A tool implementing both is judged **by its subject first**.

### The AllowedTool format

```
plain allow:       "Read"
COMMAND pattern:   "Bash(git:*)"
PATH pattern:      "Read(/tmp/**)"
specific allow:    "Bash(npm install)"
```

### Pattern examples

| Pattern | Kind | Description |
|---------|------|-------------|
| `Bash(git:*)` | COMMAND | allows every git command |
| `Bash(./gradlew:*)` | COMMAND | allows every Gradle command |
| `Bash(npm install)` | COMMAND | allows exactly `npm install` |
| `Read(/tmp/**)` | PATH | every file at any depth under `/tmp` |
| `Read(/tmp/*.log)` | PATH | only the `.log` files **directly under** `/tmp` — `*` does not cross a `/` |
| `Write(/etc/passwd)` | PATH | that one file, exactly |
| `Read` | — | unrestricted use of the Read Tool |

A caution — **a name-only entry is not an unrestricted allow once it is mixed with a pattern entry of the same name.** Register both `"Read"` and `"Read(/tmp/**)"` and reads outside `/tmp` are denied. It becomes unrestricted only when **not one** of the entries registered under that name carries a pattern.

And **a pattern that cannot be interpreted is a denial**. If a pattern is configured for a tool that has neither a subject nor a rule, that call is denied — this position used to be an unrestricted allow, which meant the strictest-looking configuration produced the weakest enforcement.

---

<!-- anchor alias: an untranslated Korean design doc links to the canonical heading id -->
<a id="동시-실행-안전성-concurrencybehavior"></a>

## Concurrency safety (ConcurrencyBehavior)

When the LLM returns several `tool_use` entries in one response, the framework can **run tools that are independent of one another and safe to run concurrently in parallel**, shortening one iteration's wall-clock time. Which tools are safe to parallelise is declared by the tools themselves through `getConcurrencyBehavior()`. This is the same `default`-method pattern as `getInterruptBehavior()`.

```java
public enum ConcurrencyBehavior {
    SEQUENTIAL,        // the default — never parallelised
    CONCURRENT_SAFE    // safe to run alongside other CONCURRENT_SAFE tools in the same batch
}
```

### The default is SEQUENTIAL

`Tool#getConcurrencyBehavior()` defaults to `ConcurrencyBehavior.SEQUENTIAL`. **Existing tools keep running sequentially without a single line changed.** Only the safe ones override it explicitly:

```java
@Override
public ConcurrencyBehavior getConcurrencyBehavior() {
    return ConcurrencyBehavior.CONCURRENT_SAFE;
}
```

### The CONCURRENT_SAFE declaration checklist

Before declaring `CONCURRENT_SAFE`, confirm all of the following. If even one is uncertain, leave it `SEQUENTIAL`.

- [ ] **Is it free of side effects, or idempotent?** It does not mutate files, the sandbox or external state
      (read-only, or the same input gives the same result). Mutating tools such as `Edit`/`Write`/`Bash`/
      `TodoWrite` must be `SEQUENTIAL`.
- [ ] **Does it touch shared mutable state only in a thread-safe way?** If the tool mutates a mutable object
      passed through the `ToolContext`, that object must be thread-safe. For example, the `READ_FILES_KEY` Set
      that `ReadTool` mutates is injected by the executor as `ConcurrentHashMap.newKeySet()`. **If a new tool
      puts mutable state into the `ToolContext` and mutates it, it must be declared `SEQUENTIAL`** or use a
      thread-safe data structure.
- [ ] **Is its InterruptBehavior `NON_INTERRUPTIBLE` or `COOPERATIVE`?** A `THREAD_INTERRUPT`/
      `EXTERNALLY_TERMINATED` tool registers its terminator against the executing thread, which becomes
      ambiguous on a shared worker thread. Such tools are excluded at the gate automatically and never enter
      the parallel path (even if they declare `CONCURRENT_SAFE`).
- [ ] **Are this tool's Pre/PostTool hooks thread-safe?** The hooks of a parallelisable tool can be invoked
      concurrently on worker threads. If a user-defined hook holds internal mutable state, it must be
      thread-safe.
- [ ] **Does it avoid hitting the same external resource concurrently?** Tools contending for the same file or
      the same rate-limited endpoint are safer left `SEQUENTIAL`.

### Gate behaviour (the two-layer judgement)

Parallel execution has to clear both layers — **the model's intent and the framework's safety**:

1. **Intent (Layer 1)** — the response must contain two or more `tool_use` entries, and the parallel feature
   must be switched on in the configuration (`ToolConcurrencyConfig.enabled=true`, off by default).
2. **Safety (Layer 2)** — **every** tool in the batch must be `CONCURRENT_SAFE` with a parallelisable
   InterruptBehavior. If even one is `SEQUENTIAL` or is an unregistered (hallucinated) name, **the whole batch
   runs sequentially**.

Even when run in parallel, the result list is always reassembled in the input (`tool_use`) order.

### Configuration

Parallel execution is **off** by default. Opt in with `OrcaAgentExecutorFactory.withToolConcurrencyConfig(...)`:

```java
TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withToolConcurrencyConfig(ToolConcurrencyConfig.enabled(4)) // maxConcurrency=4
        .create(llmClient, transcriptManager);
```

In an environment where several sessions/turns share one executor's pool concurrently, to stop a single batch from monopolising the pool, keep the global pool (`maxConcurrency`) and add a **per-batch cap, `perBatchMax`** (2-tier):

```java
ToolConcurrencyConfig.enabled(8, 2); // maxConcurrency=8 (the global pool), perBatchMax=2 (per batch)
```

Leave it unspecified and `perBatchMax = maxConcurrency`, behaving exactly like the single-tier form. The valid range is `[1, maxConcurrency]`.

For the subagent executor, inject it with `DefaultSubagentExecutor#withParallelToolDispatcher(...)`. If neither is configured, both keep running sequentially (no regression).

> For the full picture — quick start, operations, troubleshooting, limitations and so on — see the
> [parallel tool execution guide](parallel-tool-execution-guide.en.md).

---

## A complete example

### Analysing ReadTool

```java
package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * A Tool that reads the contents of a file.
 *
 * Capabilities:
 * - partial reads (offset, limit)
 * - line numbers (cat -n format)
 * - truncation of long lines (2000 characters)
 */
public class ReadTool extends AbstractTool {

    // the constants
    public static final String TOOL_NAME = "Read";
    public static final String READ_FILES_KEY = "read_tool.read_files";

    private static final Logger log = LoggerFactory.getLogger(ReadTool.class);
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final String LINE_NUMBER_FORMAT = "%6d→";

    // the dependencies
    private final VirtualFileSystem fileSystem;

    public ReadTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Read file contents from the filesystem. " +
                        "Returns file content with line numbers in cat -n format. " +
                        "Supports partial reading for large files using offset and limit parameters. " +
                        "By default, reads first 2000 lines. Lines longer than 2000 characters are truncated.",
                createInputSchema());
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of(
                                "type", "string",
                                "description", "The path to the file to read"
                        ),
                        "offset", Map.of(
                                "type", "number",
                                "description", "The line number to start reading from (1-based). " +
                                        "Only provide if the file is too large to read at once"
                        ),
                        "limit", Map.of(
                                "type", "number",
                                "description", "The number of lines to read. " +
                                        "Only provide if the file is too large to read at once"
                        )
                ),
                "required", List.of("file_path")
        );
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // 1. extract the parameters
            final String filePath = input.getRequiredString("file_path");
            log.debug("Reading file: {}", filePath);

            // 2. validate
            if (fileSystem.isDirectory(filePath)) {
                return ToolResult.error(
                        "Cannot read directory: " + filePath +
                                ". Use 'ls' command to list directory contents."
                );
            }

            // 3. extract the optional parameters (with defaults)
            final int offset = input.getInteger("offset", 1);
            if (offset < 1) {
                return ToolResult.error("offset must be >= 1, got: " + offset);
            }

            final int limit = input.getInteger("limit", DEFAULT_LIMIT);
            if (limit < 1) {
                return ToolResult.error("limit must be >= 1, got: " + limit);
            }

            // 4. perform the core work
            final String content = readFileContent(filePath, offset, limit);

            // 5. update the context (for other Tools to build on)
            markFileAsRead(context, filePath);

            // 6. return the result
            if (content.isEmpty()) {
                return ToolResult.success("[System Warning: This file is empty]");
            }

            log.debug("Successfully read file: {}", filePath);
            return ToolResult.success(content);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (FileNotFoundException e) {
            log.warn("File not found: {}", e.getMessage());
            return ToolResult.error("File not found: " + e.getMessage());
        } catch (InvalidPathException e) {
            log.warn("Invalid path: {}", e.getMessage());
            return ToolResult.error("Invalid path: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to read file: {}", e.getMessage(), e);
            return ToolResult.error("Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error reading file: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String readFileContent(String filePath, int offset, int limit) throws IOException {
        final StringBuilder result = new StringBuilder();

        try (InputStream inputStream = fileSystem.read(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            int currentLine = 1;
            int linesRead = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                // skip up to the offset
                if (currentLine < offset) {
                    currentLine++;
                    continue;
                }

                // stop once the limit is reached
                if (linesRead >= limit) {
                    break;
                }

                // truncate long lines
                String displayLine = line;
                if (line.length() > MAX_LINE_LENGTH) {
                    displayLine = line.substring(0, MAX_LINE_LENGTH) + "...";
                }

                // emit in cat -n format
                result.append(String.format(LINE_NUMBER_FORMAT, currentLine))
                        .append(displayLine)
                        .append('\n');

                currentLine++;
                linesRead++;
            }
        }

        return result.toString();
    }

    private void markFileAsRead(ToolContext context, String filePath) {
        @SuppressWarnings("unchecked") final Set<String> readFiles = context.get(READ_FILES_KEY, Set.class).orElse(null);
        if (readFiles != null) {
            readFiles.add(filePath);
            log.debug("Marked file as read: {}", filePath);
        }
    }
}
```

---

## Checklist

Check the following items when developing a new Tool.

### Required

- [ ] Does it extend `AbstractTool`?
- [ ] Is a `TOOL_NAME` constant defined?
- [ ] Does the constructor call `super(name, description, schema)`?
- [ ] Does `execute()` null-check with `Objects.requireNonNull()`?
- [ ] Does `execute()` never throw?
- [ ] Is every error returned as `ToolResult.error()`?
- [ ] Is the JSON Schema defined correctly?
- [ ] Are the required parameters listed in `required`?

### Recommended

- [ ] Are the class and constructor documented with Javadoc?
- [ ] Is a usage example included?
- [ ] Are the logging levels used appropriately? (DEBUG/WARN/ERROR)
- [ ] Do the optional parameters have defaults?
- [ ] Are the error messages meaningful?
- [ ] Is it thread-safe?

### Tests

- [ ] Is there a test for the happy path?
- [ ] Is there a test for the error cases?
- [ ] Is there a test for the boundary values?
- [ ] Is there a test for null input?

---

## Related documents

- [AbstractTool.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/AbstractTool.java)
- [ToolInput.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolInput.java)
- [ToolResult.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolResult.java)
- [ToolContext.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolContext.java)
- [ReadTool.java](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/file/ReadTool.java) - the reference implementation
