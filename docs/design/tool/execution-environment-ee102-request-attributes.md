# Design — per-execution attributes on `EnvironmentRequest` (issue #252)

> Status: **IMPLEMENTED** (2026-10-17, issue #252). This is the design for backlog item EE-102 exactly as it was
> approved in design review (two rounds; the second passed with five non-blocking notes). The body below, §1–§7, is the
> body as approved, kept byte-exact, including its own line citations (as of `63e5d564`) and its open-question numbers.
> It is written in English because that is how it was reviewed; the `design/` directory is not a translation target
> (`docs/project/documentation-guide.md` §5.1).
>
> One thing was added. §8 records where the implementation departed from this design, and why — including which review
> notes were applied. The open questions in §7 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-103 ← Q1 ·
> Q5, EE-104 ← Q6, EE-105 ← the second review's note on `executionId`, and Q3 as a line on EE-30), which is the
> canonical open/closed list. The spec this extends is [`execution-environment.md`](execution-environment.md) §4.1,
> §5.1, §5.2 and §13.

Base: `main` @ `63e5d564`. Every file path is relative to `modules/aimon-core/src/main/java/at/aimon/core/`
unless it says otherwise.

## 1. The problem

`ExecutionEnvironmentProvider.resolve(EnvironmentRequest)` decides where an execution's files and shell live.
The request carries identity (`agentRuntimeId`, `sessionId`, `executionId`, `invokingSessionId`, `principal`),
lineage (`parent`, `branchKey`, `fork`), and `definitionAttributes()`. Those attributes come from the agent or fork
**definition**, so they are the same for every run. Nothing in the request comes from the caller of *this* run. A host
that submits a turn can already attach per-run data through `SubmitOptions.getExecutionAttributes()`; that data reaches
`ToolContext`, `HookContext` and `QuotaResolutionContext`, but not the environment provider. The provider can key
placement on the definition or on the session, never on the run. That is why aimon-sandbox (#25) had to model
"run in workspace X" and "refuse if nothing is attached" as a per-session store the host writes before every turn.
For routines it could model nothing at all, because a routine's execution id is minted inside `RoutineExecutor.execute`.
The fix is to forward a reserved, string-valued, host-only subset of the run's attributes into the request, for main
turns and routines, without leaking it into forks.

## 2. Approach

### 2.1 Shape

- A new read-only accessor `EnvironmentRequest.requestAttributes(): Map<String, String>`. It is never null, it is
  unmodifiable, and it is empty by default. The builder gets a matching `requestAttributes(Map<String, String>)`.
- **Reserved prefix `environment.`** Only execution-attribute entries whose key starts with `environment.` are
  forwarded, and **the prefix is removed**. So `SubmitOptions.executionAttribute("environment.sandbox.workspace", "ws-7")`
  reaches the provider as `requestAttributes().get("sandbox.workspace") == "ws-7"`. Removing the prefix lets a provider
  read the same key name in both maps (`definitionAttributes()` already uses provider-chosen keys like `sandbox.slot`).
  It also lets the provider layer them as "the run overrides the definition" without rewriting keys.
- **Strings only.** A value under the prefix that is not a `String` is an error, not a silent drop and not a
  `String.valueOf`. See §5 for how the error surfaces. A key that is exactly `environment.` (empty after the prefix is
  removed) is also an error.
- **Backward-compatibility trade-off, stated explicitly.** Reserving a prefix is the one place this change can affect a
  host that does not use the feature. Such a host might already send a non-String `environment.*` attribute for
  another purpose, for example `executionAttribute("environment.debug", true)` read only by a hook. Those turns used to
  get a working environment; after this change their file and shell tools fail. Every other host is unchanged. The
  options were: (1) a less collision-prone prefix (Q5); (2) skipping non-String values with a WARN instead of failing,
  which keeps old hosts working but silently drops placement intent, so "refuse if nothing is attached" could be
  bypassed by a typo in type; (3) failing (chosen). Option (3) is chosen because a silent drop is the worse failure for
  a placement input. The exposure is limited to a key namespace that core has never documented or used. The CHANGELOG
  entry calls the reservation out as an operator-visible change. Q5 stays open for the maintainer to pick
  `aimon.environment.` if they judge the collision risk higher.
- **One extraction rule, in one place:** a new final utility class
  `environment/EnvironmentRequestAttributes` with
  `static Map<String, String> extract(Map<String, Object> executionAttributes)` and the constant
  `PREFIX = "environment."`. Every executor that fills the field calls this method. That way main turns and routines
  cannot drift apart, the same way `DefinitionAttributes` is the single parsing rule for definition files.
- Core forwards the attributes and does not read them. Like `definitionAttributes()`, the key names below the prefix
  belong to the provider.

Illustrative only:

```java
// OrcaAgentExecutor.execute, at the existing resolve site (line ~1080)
EnvironmentRequest.builder().agentRuntimeId(agentRuntime.getId()).agent(agent)
        .sessionId(executionRequest.getSessionId())
        .principal(executionRequest.getPrincipal().orElse(null))
        .requestAttributes(EnvironmentRequestAttributes.extract(executionRequest.getExecutionAttributes()))
        .build();
```

### 2.2 Which paths fill it

| Path | Request built at | `requestAttributes` |
|------|------------------|---------------------|
| Main turn, including slash-command and rewake-replay turns (they go through the same `execute`) | `agent/impl/orca/OrcaAgentExecutor.java:1082` | `extract(executionRequest.getExecutionAttributes())`. This is where `SubmitOptions` already lands (`DefaultLiveSession.buildRequest` → `OrcaAgentExecutionRequest.executionAttributes`). |
| Scheduled routine | `scheduling/RoutineExecutor.java:547` | From a new host-supplied resolver, §2.3. Empty when none is wired. |
| Subagent / skill fork, workflow step | `subagent/execution/DefaultSubagentExecutor.java:791` | **Always empty.** The fork's placement is the parent environment's business (`parent`, `fork()`). This holds even though the fork's own `SubagentExecutionRequest.executionAttributes` *does* still carry the parent's `environment.*` entries (they keep propagating to the fork's `ToolContext`, unchanged). |
| Workflow runner fallback resolve (no parent env, i.e. EE-30's agent-scoped background runner) | `workflow/impl/DefaultWorkflowContext.java:185` | **Empty** in this change. See Q3. |
| CLI bootstrap probe (`aimon-cli` `AgentSetupFactory.workingDirectoryOf`) | n/a | Empty. It belongs to no execution. |

### 2.3 Routines: a host-side resolver, not a persisted field

A routine has no `SubmitOptions`. `ScheduledTask` has no attribute field. Its `ToolContext` does not even carry
`EXECUTION_ATTRIBUTES_KEY`. So for routines the "execution attributes" have to come from somewhere new. Proposed:

```java
package at.aimon.core.scheduling;

/** Host-supplied, per-run execution attributes for a routine. Called once per run, after the execution id is minted. */
@FunctionalInterface
public interface RoutineExecutionAttributes {
    Map<String, Object> resolve(ScheduledTask task, ExecutionId executionId);

    RoutineExecutionAttributes NONE = (task, executionId) -> Map.of();
}
```

- `RoutineExecutor` gets `withExecutionAttributes(RoutineExecutionAttributes)`. It is fluent, `volatile`, and defaults
  to `NONE`, following the existing `withTracer` / `withTracePayloadPolicy` pattern. The constructors do not change.
- **Where the call sits.** The resolver is called once per run inside `runRoutine` (`RoutineExecutor.java:335`), right
  before `buildToolContext` (line 346). So it runs inside the `try` that `execute` opens at line ~242, after
  `registerRun` and with the trace open. A throwing resolver therefore cannot skip `trace.close` or `deregisterRun`.
  The call is independent of the `runtime.isEmpty()` / `provider == null` branches in `putExecutionEnvironment`
  (lines 533-552): it runs once whatever those branches decide, so (b) below is filled even when no provider exists.
  `buildToolContext` and `putExecutionEnvironment` take the resolved map as a parameter and never call the resolver
  themselves. A resolver that throws or returns null is caught at the call site and becomes an empty attribute map plus
  a stored cause. `putExecutionEnvironment` then puts `UnavailableExecutionEnvironment.of(cause)` instead of
  resolving (§5).
- The result is used in two places. (a) `extract(...)` of it goes into the routine's `EnvironmentRequest`, through the
  shared `ExecutionEnvironments` overload (§4). (b) The **whole** map is placed on the routine's `ToolContext` under
  `EXECUTION_ATTRIBUTES_KEY`, so routine steps see execution attributes the way turn tools do. If (b) looks like scope
  creep, the dev agent may drop it; it is an adjacent consistency fix, not required by the issue (see Q2).
- The resolver returns `Map<String, Object>`, the same type as `SubmitOptions`, so one `extract` rule applies and a
  host can reuse one attribute map for both turns and routines.
- **Wiring is required, not optional.** In production, `RoutineExecutor` is constructed in exactly one place:
  `SchedulingEngineBuilder.build()` (`scheduling/SchedulingEngineBuilder.java:342`). It builds the executor privately,
  and `SchedulingEngine` keeps it in a private field with no getter. A setter on `RoutineExecutor` alone would
  therefore be unreachable for every host. The resolver is threaded through all three layers, mirroring `tracer` /
  `tracePayloadPolicy` at each one:
  1. **`SchedulingEngineBuilder.routineExecutionAttributes(RoutineExecutionAttributes)`** — a new field. `build()`
     applies it next to lines 343-348:
     `if (routineExecutionAttributes != null) routineExecutor.withExecutionAttributes(routineExecutionAttributes);`.
     Null leaves `NONE`.
  2. **`aimon-bootstrap` `SchedulingSpec`** — an immutable wither `withRoutineExecutionAttributes(...)` plus
     `Optional<RoutineExecutionAttributes> getRoutineExecutionAttributes()`, matching
     `withExecutionGuard` / `getExecutionGuard`. **`AimonStackBuilder.createSchedulingEngine`**
     (`AimonStackBuilder.java:712-725`) adds
     `spec.getScheduling().getRoutineExecutionAttributes().ifPresent(builder::routineExecutionAttributes);` next to
     the existing `executionGuard` line.
  3. **`aimon-spring-boot-starter` `AimonSchedulingAutoConfiguration.aimonSchedulingSpec`** — takes an
     `ObjectProvider<RoutineExecutionAttributes>` resolved with `ApplicationBeans.resolve`, and applies it in
     `withApplicationBeans`, exactly like `ScheduledExecutionGuard`. If the backend is `none` and such a bean is
     defined, it throws the same "bean is defined but … builds no scheduling engine to consult it" error that the
     other three application beans throw.

This answers the issue's routine problem directly. The resolver runs at fire time with the run's `ExecutionId` and
the task, so a host can look up "the workspace bound to task T" without knowing the execution id in advance.

### 2.4 Trust (issue point 1)

Execution attributes are host-supplied. The paths that put entries into them are `SubmitOptions` (host), the
`OrcaAgentExecutionRequest` builder (host code), the new routine resolver (host), and replays of a persisted
`SubmitOptions` (session inbox codecs and transcript `beginTurn`, which store what the host originally submitted). No
tool input is ever copied into them. `TaskTool` and `WorkflowTool` copy the **parent's** map unchanged. The design
makes that rule enforceable and documented:

1. Javadoc on `requestAttributes()` and on `SubmitOptions.getExecutionAttributes()` states that the `environment.*`
   namespace is placement input. A host must never fill it from model output, including tool arguments, model-written
   routine parameters, and workflow script values.
2. The fork path never reads it (§2.2). So even a model-launched fork cannot select placement through it, and a fork
   cannot widen what its parent was given.
3. Routines take attributes from the host resolver, **not** from `ScheduledTask`. `ScheduleTaskTool` is model-driven:
   the model creates routines. A persisted field on `ScheduledTask` would be one step away from a model-writable field.
   That is the main reason §3 alternative B is rejected.
4. A test asserts that no `ToolContextEnricher` or hook can change the map the request was built from. The request is
   built before `createToolContext`, and from `executionRequest`, not from the `ToolContext` (§6).

### 2.5 Type (issue point 2)

Strings only, so providers do not depend on caller classes. This matches `definitionAttributes()`. The values are
strict (`instanceof String`), not converted. A boolean switch like "refuse if nothing is attached" is written as
`"true"`, and the provider parses it. Implicit conversion would make the value's string form depend on `toString()`
of caller classes (e.g. `Double` → `"1.0"`), which is the coupling the issue wants to avoid.

## 3. Alternatives rejected

- **A. Forward the whole `executionAttributes` map (`Map<String, Object>`).** Rejected. Providers would then depend on
  caller classes. Every feature flag and A/B key would become placement input, and any future attribute a host adds
  for a different reason could start moving executions. A reserved prefix makes the opt-in explicit.
- **B. A persisted `environmentAttributes` field on `ScheduledTask`.** Rejected. (1) Routines are created by the model
  through `ScheduleTaskTool`. Either the tool would accept the field, which violates trust, or it would copy the
  creating turn's attributes, which freezes one turn's placement into every future fire, invisibly. (2) It changes a
  persisted aggregate and every codec (`aimon-session-mongodb` `ScheduledTaskDocumentCodec`, the
  `aimon-session-testkit` contract, Quartz job data). (3) It changes `AgentDefinitionVersion`-style change detection
  questions for no gain over a resolver. A resolver can still read host-owned per-task data if a host wants that.
- **C. Keep the prefix in the forwarded keys** (`requestAttributes().get("environment.sandbox.workspace")`). Rejected,
  narrowly. It is harmless, but it makes the provider use two spellings for the same concept in the two maps. If
  reviewers prefer it, the change is cosmetic and localised in `extract`.
- **D. Inherit `requestAttributes` into forks.** Rejected, as the issue asks. `parent` already models fork placement,
  and §5.2 of `docs/design/tool/execution-environment.md` promises "a fork never leaves its parent's isolation unit".
  A per-run "workspace X" on a fork would let it ask for a different unit.
- **E. Validate in `SubmitOptions.Builder.build()`, failing the submit call.** Rejected as the *only* check. Execution
  attributes also enter through `OrcaAgentExecutionRequest.builder()` directly, through codec replays, and through the
  routine resolver, so the executor must check anyway. A second, eager check in `SubmitOptions` would also have to be
  placed in `agent`, which `environment` already depends on (`EnvironmentRequest` imports `agent.Agent`), so calling
  `EnvironmentRequestAttributes` from there risks a package cycle. Q4 asks whether an eager check is still wanted.
- **F. A dedicated `SubmitOptions.environmentAttributes(Map<String,String>)` field instead of a prefix.** Rejected for
  now. It is cleaner typing, but it is a second channel next to `executionAttributes`, needs codec support in three
  session-inbox codecs (Mongo, Postgres, Redis `InboundMessage*Codec`) plus `SubmitOptionsCodec`, and the issue
  explicitly proposes the namespaced subset. It is listed as Q1 in case the maintainer prefers typing over namespacing.

## 4. Concrete changes

### `aimon-core` main

| File | Change |
|------|--------|
| `environment/EnvironmentRequest.java` | New field `requestAttributes` (`Map.copyOf`, default `Map.of()`), accessor `requestAttributes()`, builder setter (null → empty; null keys or values → NPE from `Map.copyOf`). `toString()` prints the key **names** only, `requestAttributes=[sandbox.workspace]`, never the values. Values may be tenant or workspace identifiers, and the request is logged at WARN by `ExecutionEnvironments`. Class javadoc: one paragraph on "definition vs. this run". |
| `environment/EnvironmentRequestAttributes.java` (new, final, private ctor) | `PREFIX`, `extract(Map<String,Object>)`. Returns an unmodifiable, prefix-stripped map. It throws `IllegalArgumentException` naming the key and value class for a non-`String` value or an empty remainder. The message never includes the value. |
| `agent/impl/orca/OrcaAgentExecutor.java` (~1080) | Fill `requestAttributes` by calling the new `ExecutionEnvironments` overload below with the partially built request builder and `executionRequest.getExecutionAttributes()`. If `extract` throws, the environment is `UnavailableExecutionEnvironment.of(e)` and the turn continues with the WARN, the same outcome as a throwing provider (§5). |
| `environment/ExecutionEnvironments.java` | **Required:** a new overload `resolveOrUnavailable(ExecutionEnvironmentProvider provider, EnvironmentRequest.Builder request, Map<String,Object> executionAttributes)`. It runs extract → `requestAttributes(...)` → `build()` → the existing two-arg overload. An extraction failure becomes `UnavailableExecutionEnvironment.of(e)` with one WARN that names the key, never the value, and the provider is not called. Both the main-turn site and the routine site call it, so they share the WARN and unavailable handling. The existing two-arg overload is unchanged, and the fork, workflow and CLI sites keep using it. |
| `scheduling/RoutineExecutionAttributes.java` (new) | The functional interface from §2.3, with `NONE`. |
| `scheduling/RoutineExecutor.java` | `volatile RoutineExecutionAttributes executionAttributes = NONE`; `withExecutionAttributes(...)`. Resolve once per run in `runRoutine` before `buildToolContext` (§2.3, "Where the call sits"). Pass the result into `buildToolContext` → `putExecutionEnvironment` (request, through the new overload) and, per §2.3(b), into `EXECUTION_ATTRIBUTES_KEY`. A resolver that throws or returns null makes the run's environment unavailable, with the cause (§5). The run is not failed outright. |
| `scheduling/SchedulingEngineBuilder.java` | New field and setter `routineExecutionAttributes(RoutineExecutionAttributes)` with javadoc in the style of `tracer(...)`. `build()` applies it to the `RoutineExecutor` it creates at line 342, next to the tracer and payload-policy calls. This is the only production construction site, so without it the feature is unreachable. |
| `agent/SubmitOptions.java` | Javadoc only: the `environment.*` namespace, the String-only rule, and the host-only rule. |
| `subagent/execution/DefaultSubagentExecutor.java` | No behaviour change. One comment at line ~791 stating that `requestAttributes` is intentionally not forwarded (issue #252), so a later reader does not "fix" it. |
| `workflow/impl/DefaultWorkflowContext.java` | No change (Q3). |

### Other modules

| Module | Change |
|--------|--------|
| `aimon-bootstrap` `spec/SchedulingSpec.java` | New immutable wither `withRoutineExecutionAttributes(...)` and `getRoutineExecutionAttributes()` (Optional), matching `withExecutionGuard`. Unset means `NONE`. |
| `aimon-bootstrap` `AimonStackBuilder.java` (`createSchedulingEngine`, 712-725) | Forward `spec.getScheduling().getRoutineExecutionAttributes()` to `SchedulingEngineBuilder.routineExecutionAttributes`. Required. |
| `aimon-spring-boot-starter` `AimonSchedulingAutoConfiguration.java` | Accept an optional `RoutineExecutionAttributes` application bean in `aimonSchedulingSpec`, the same way as `ScheduledExecutionGuard` (`ApplicationBeans.resolve`, applied in `withApplicationBeans`). A bean defined while the backend is `none` is a startup error, consistent with the other three beans. Update the starter's docs or backlog wherever the existing beans are listed. |
| Session stores and codecs | None. Nothing new is persisted. |

### Docs (Korean canonical; translations checked)

- `docs/design/tool/execution-environment.md`: §4.1 table row for `EnvironmentRequest` adds `requestAttributes`. §5.2
  gets one sentence: forks do not carry it. §13 contract table gets a row. Core promises: main turns and routines carry
  the `environment.*` subset, prefix removed, strings only, never from model output, never on forks. Sandbox promises:
  treat it as placement input, layer it over `definitionAttributes()` however its policy says, and fail with an
  exception (→ unavailable) when it cannot honour the request. The provider also does not log the values: core's
  `EnvironmentRequest.toString()` prints key names only, but a provider that logs `requestAttributes()` itself is
  outside core's control. Routines get their attributes from `SchedulingEngineBuilder.routineExecutionAttributes` /
  `SchedulingSpec.withRoutineExecutionAttributes`; name both in §5.1's list of resolve sites.
- `docs/backlog/execution-environment-open-items.md`: a new closed entry for #252 (next free EE number), with the
  decisions in §2.4/§2.5 and the test names. The EE-30 entry gets one line: when EE-30 is fixed, decide whether the
  runner's fallback request carries the caller's `requestAttributes` (Q3).
- `docs/getting-started/aimon-core-integration-via-cli-reference.md` **and** its `.en.md`: where `executionAttributes`
  is described, add the `environment.*` note. Both files are changed in the same commit.
- Before committing, the dev agent runs `find docs -name '*.en.md'` against each touched file. At the time of writing,
  `docs/design/tool/` and `docs/backlog/` have no `.en.md` translations. The check script and structure script must pass.
- `CHANGELOG.md` `[Unreleased]`: an `### Added: per-execution environment attributes (EnvironmentRequest#requestAttributes)`
  entry. It covers: opt-in, no change by default; the prefix and String rule; forks excluded; routine resolver; a
  non-String value under `environment.*` now makes that execution's environment unavailable. That last item is an
  operator-visible change for anyone already using such keys.

## 5. Failure modes

| Failure | Handling | Why |
|---------|----------|-----|
| Non-`String` value, or bare `environment.` key, in a main turn's attributes | `extract` throws → environment is `UnavailableExecutionEnvironment` with the message (key + class, no value). WARN logged once. The turn still runs; file and shell tools fail with the cause, and the descriptor `notes` tell the model before its first tool call. | Same as a throwing provider (design §5.1). The host asked for a placement core cannot express. Running on the default placement would silently ignore "refuse if nothing is attached". Failing the whole turn would make it the only `resolve`-side error that does, which is inconsistent. |
| Same in a routine (resolver returns bad values) | Same: unavailable environment for the run, so steps that need files or a shell fail, and the routine's step results and events record it. | Same reasoning. A routine never falls back to the host. |
| Routine resolver throws or returns null | Unavailable environment with the cause. The `EXECUTION_ATTRIBUTES_KEY` entry is empty. WARN logged. | The resolver is host code. Its failure must not silently drop placement intent. |
| Provider rejects the attributes (unknown workspace, nothing attached and refusal requested) | The provider throws → existing `resolveOrUnavailable` path. | No change. It is already the contract (§13 "fail with an exception"). |
| Attribute values leak into logs | `EnvironmentRequest.toString()` prints key names only. Extraction errors print key and class only. | `ExecutionEnvironments` logs the request at WARN on every failure. |
| Host has no knowledge of the feature | `requestAttributes()` is empty. Providers that never call it are unaffected, and so are `LocalExecutionEnvironmentProvider` and `PerRuntimeLocalEnvironmentProvider`. | Backward compatible. A new builder setter and accessor only; no SPI method added to the provider interface. |
| A turn's `environment.*` entries reach a fork's `ToolContext` | Allowed, unchanged behaviour. They are visible to tools as before but not used for the fork's placement. | Changing propagation of `executionAttributes` would be a separate, behaviour-changing decision. |
| Rewake replay of an old turn | It replays `SubmitOptions` as submitted, so the replayed turn asks for the same placement as the original. This holds exactly for `String` values, the only accepted type, because they round-trip unchanged. `SubmitOptionsCodec` round-trips other value types only "best-effort" through JSON; a `java.time` value, for example, comes back as a String or number. So a turn whose non-String `environment.*` value was rejected (unavailable) could be *accepted* on replay, or the reverse. That only affects hosts that already break the String rule, and the original turn has already surfaced the error. | Consistent with "replay runs as the same caller under the same context" (OrcaAgentExecutor comment at `beginTurn`). |

## 6. Test strategy

All are unit tests (`./gradlew :aimon-core:test`); there are no Docker tests.

- `environment/EnvironmentRequestTest` (extend): default empty; builder copy is unmodifiable and defensive; null map →
  empty; `toString()` contains the key and not the value.
- `environment/EnvironmentRequestAttributesTest` (new): only prefixed keys pass; the prefix is removed; nested
  prefixes are kept (`environment.sandbox.workspace` → `sandbox.workspace`); `Boolean`/`Integer`/`List` value →
  `IllegalArgumentException` naming key and class but not the value; bare `environment.` → error; empty or null input
  → empty; the `environmentX.foo` near-miss is not matched.
- `agent/impl/orca`: an executor-level test with a recording `ExecutionEnvironmentProvider`, in the style of
  `OrcaAgentRuntimeFactoryEnvironmentWiringTest`. (1) `SubmitOptions` with `environment.sandbox.workspace=ws-7` plus
  a non-prefixed flag → the provider sees exactly `{sandbox.workspace=ws-7}`. (2) A non-String value → the provider is
  **not called**, the tool sees an unavailable environment whose message names the key, and the turn completes.
  (3) An enricher or hook cannot alter what the provider saw (the request is built before the tool context).
  Drive (1) through `DefaultLiveSession.submit` if a fixture exists (`DefaultLiveSessionTest`), so the
  `SubmitOptions → OrcaAgentExecutionRequest` hop is covered end to end.
- `subagent/execution/DefaultSubagentExecutorTest` (extend, next to `environmentRequestCarriesForkDefinition`): a
  fork whose `SubagentExecutionRequest.executionAttributes` contains `environment.*` → the fork's request has empty
  `requestAttributes()`, and the fork's `ToolContext` still has the original map.
- Workflow: assert in **both** `WorkflowToolAttributesTest` (core `WorkflowTool`, `WorkflowTool.java:527`) **and**
  `GraalJsEnvironmentRequestTest` (`aimon-workflow-graaljs`, `GraalJsWorkflowTool.java:272`) that steps' requests have
  empty `requestAttributes()` when the calling turn had `environment.*` attributes. They are different launch paths.
- `scheduling`: a routine test with a stub resolver → the provider sees the prefix-stripped subset, and the resolver
  receives the same `ExecutionId` as the run's `EXECUTION_ID` context key. Default `NONE` → empty, unchanged.
  A throwing resolver → unavailable environment and the run is not aborted. If §2.3(b) is kept, also check that the
  step's `ToolContext` carries `EXECUTION_ATTRIBUTES_KEY`.
- **Reachability through the public builders.** The `RoutineExecutor`-level tests above build the executor directly,
  so they cannot catch missing wiring. Add:
  - `aimon-core`: a `SchedulingEngineBuilder` test. It builds an engine with `.routineExecutionAttributes(stub)` and a
    registry holding a runtime with a recording provider, then fires a task (manually or through the in-memory
    scheduler, whichever the existing scheduling tests use). The provider's `EnvironmentRequest.requestAttributes()`
    must equal the prefix-stripped subset. A builder without the setter gives an empty map.
  - `aimon-bootstrap`: an `AimonStack` test with `SchedulingSpec.enabled().withRoutineExecutionAttributes(stub)` that
    asserts the same end to end. It follows the style of the existing `AimonStack*Test` classes that use a recording
    provider.
  - `aimon-spring-boot-starter`: an auto-configuration test. A `RoutineExecutionAttributes` bean appears on the
    resulting `SchedulingSpec`, and the same bean with `backend=none` fails startup with the "bean is defined but"
    message.
- Gate: `./gradlew checkAll`, `python3 scripts/check-translation-staleness.py`,
  `python3 scripts/check-translation-structure.py`, `python3 scripts/check-doc-links.py`.

## 7. Open questions

These were not resolvable from the task. Each has a default the dev agent should follow unless the orchestrator
overrides it.

- **Q1. Namespace or typed field?** The issue proposes a prefix inside `executionAttributes`, and this design follows
  it. A typed `SubmitOptions.environmentAttributes(Map<String,String>)` (alternative F) would be stricter but touches
  four codecs. *Default: prefix.*
- **Q2. Should routine steps also get `EXECUTION_ATTRIBUTES_KEY` (§2.3 b)?** Today they get none, which is a pre-existing
  gap next to this change. *Default: yes, from the same resolver result. Drop it if review calls it scope creep.*
- **Q3. Background workflow runners (EE-30), which the issue asks about directly.** The agent-scoped runner built in
  `OrcaAgentRuntimeFactory` (~1162) resolves without a parent and today knows only runtime id and principal. Passing
  the caller's `requestAttributes` there would let a background workflow land in the caller's workspace. But the
  real EE-30 fix is to pass the caller's **parent environment**, after which the attributes are redundant, and a
  fresh resolve from attributes could even disagree with the parent. *Default: not in this change. Record it on EE-30
  so it is decided when EE-30 is fixed.*
- **Q4. Eager validation at `SubmitOptions.build()`?** It would give an earlier, louder error at the submit site.
  Rejected as the sole check (§3 E), but it could be added as a convenience if a package-cycle-free home exists.
  *Default: no.*
- **Q5. Prefix spelling.** `environment.` follows the issue's example. An alternative would be `aimon.environment.` to
  reduce clashes with existing host keys. *Default: `environment.`; the CHANGELOG calls out the reservation.*
- **Q6. Should forks get an explicit, opt-in way to receive per-run attributes** (e.g. a host-set flag)? The issue says
  no implicit inheritance and is silent on explicit. *Default: none; out of scope.*

## 8. Where the implementation departed from this design

Written after the build, against the working tree on top of `63e5d564`. The approved body above is not edited; what
turned out differently is recorded here. Most of the design was built as written: the shape (§2.1), the paths (§2.2),
the routine resolver and its three-layer wiring (§2.3), the trust and type rules (§2.4, §2.5) and the failure table (§5).
The departures are small and listed in full.

### 8.1 Review notes applied

The second design review passed with five non-blocking notes. Three were folded in, one could not be, one was promoted.

- **Null values in `extract`.** A `null` value under the prefix is refused like any other non-`String` value, and the
  message names the class as `null` instead of dereferencing it. A `null` *key* is not under the prefix and is skipped,
  as is a `null` value under any other key. `EnvironmentRequestAttributesTest.nullValues` pins both.
- **Defensive copy of the resolver's map, inside the guarded call.** `RoutineExecutor.resolveExecutionAttributes` calls
  the resolver, null-checks the answer and takes `Map.copyOf` of it, all inside the one `try`. So a map with a null key
  or value is a resolver failure (unavailable environment, run continues), not an exception thrown later from
  `Map.copyOf` or `ToolContext` outside the guard. This widens §5's "Routine resolver throws or returns null" row by one
  case: a map `Map.copyOf` refuses. `RoutineExecutorExecutionAttributesTest.aResolverReturningNullOrANullValueIsTreatedAsAFailure`.
- **Resolver javadoc.** `RoutineExecutionAttributes` says it is called on every fire, on the firing node, on the run's
  own thread before the first cancellation gate, and so should be fast.
- **Cross-reference in the EE-42 record — not added.** `execution-environment-ee42-workflow-attributes.md` is an exempt
  record (`docs/design/README.md` §3.4): its body is not edited after approval, and nothing in it became wrong, so there
  is no correction to append either. The sentence the note asked for lives in the spec instead
  (`execution-environment.md` §5.2: fork and workflow-step requests always carry empty `requestAttributes`).
- **Main-turn requests carry no `executionId`.** Promoted to EE-105, as the note suggested; not fixed here.

### 8.2 Behaviour that differs from the body

1. **`EXECUTION_ATTRIBUTES_KEY` on a routine step is absent, not empty, when there are no attributes.** §2.3(b) and the
   §5 row say the entry is "empty" after a resolver failure. The executor puts the key only when the resolved map is
   non-empty, so a routine without a resolver keeps the exact `ToolContext` it had before this change (the design's
   "Default `NONE` → empty, unchanged"). Every reader in core uses `get(...).orElse(Map.of())` (`TaskTool`,
   `WorkflowTool`, `SubagentBackedSkillForkExecutor`), so absent and empty read the same.
2. **Which cause wins when two things are wrong.** The body does not order the runtime/provider checks against a
   resolver failure. In `putExecutionEnvironment` an unregistered runtime or a runtime without a provider is reported
   first; a resolver failure becomes the environment's cause only when a provider exists. The resolver failure is still
   logged at WARN either way, and the resolver is still asked once even when the runtime is missing (§2.3 "independent
   of the branches"), which `theResolverIsAskedEvenWhenTheRuntimeIsNotRegistered` pins.
3. **The extraction WARN does not print the request.** §4 says the new `ExecutionEnvironments` overload logs "one WARN
   that names the key, never the value". It does, but the request is not built yet at that point, so the WARN carries
   only the extraction message (key and class). The provider-failure WARN of the two-arg overload is unchanged and now
   prints `requestAttributes=[key, …]`, sorted, values never.
4. **The starter groups its optional beans.** Adding a fourth optional bean to `withApplicationBeans` would have made a
   five-parameter private helper; it now takes a small private holder (`ApplicationSchedulingBeans`). The refusal
   message for a `RoutineExecutionAttributes` bean under `backend=none` reads "builds no scheduling engine to ask it",
   in the family of the other three.

### 8.3 Tests that differ from §6

- **Enricher, not hook.** §6 asks that "an enricher or hook cannot alter what the provider saw". The test uses an
  enricher that rewrites `EXECUTION_ATTRIBUTES_KEY` (which is not a write-once key, so the rewrite succeeds and the
  tool sees it) and asserts the provider was asked once, with the original subset. No hook variant was written: hooks
  run after the context is built, from the same `executionRequest`, so the enricher case is the earlier and stronger of
  the two.
- **One executor-level class covers (1)–(3)** — `OrcaAgentExecutorRequestAttributesTest`. Case (1) runs through
  `DefaultLiveSession.submit` with a real `OrcaAgentExecutor`, so the `SubmitOptions` → `OrcaAgentExecutionRequest`
  hop is covered end to end; it also asserts the request's `toString()` names the key and not the value.
- **Routine and builder tests share fixtures.** `SchedulingEngineBuilderTest` reuses the recording provider, runtime
  and capturing tool of `RoutineExecutorExecutionAttributesTest` instead of declaring its own.
- **Bootstrap reachability is in `AimonStackBuilderTest`**, next to the other `SchedulingSpec` seams, with the provider
  supplied through `ExecutionEnvironmentSpec.shared(...)`. It fires one task through the stack and asserts the
  provider's `requestAttributes()`.

### 8.4 Open questions

- **Q1 and Q5** (typed field vs. prefix, prefix spelling) — built as their defaults (prefix, `environment.`). Both
  change a public contract that aimon-sandbox will build on, so they move to EE-103 to be settled before the release
  that ships this.
- **Q2** (`EXECUTION_ATTRIBUTES_KEY` on routine steps) — built as its default (yes, from the same resolver result).
  Closed here.
- **Q3** (EE-30's background runner) — built as its default (not carried). Recorded as a line on EE-30.
- **Q4** (eager validation in `SubmitOptions.build()`) — built as its default (no). Not promoted: it would only move
  the same error earlier, and no caller is affected by its absence.
- **Q6** (explicit, opt-in per-run attributes for forks) — built as its default (none). Moves to EE-104, waiting for a
  provider that needs it.
