# EE-42 design — workflow subagents carry definition attributes

> Status: **IMPLEMENTED** (2026-09-29). This is the design for backlog item EE-42 exactly as it was approved in design
> review (one round, passed with ten non-blocking notes). The body below is unchanged, including its own line
> citations (as of `d530c1d`) and its open-question numbers. It is written in English because that is how it was
> reviewed; the `design/` directory is not a translation target (`docs/project/documentation-guide.md` §5.1).
>
> Two things were added. §8 records where the implementation departed from this design, and why — including which
> review notes were applied. Note in particular §8.3: registered keys are now pinned, which replaces the
> "explicit wins on the same key" rule of §2.1 step 4 and §4 for graaljs steps. The open questions in §7 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-43 ← Q2,
> EE-44 ← Q5, EE-45 ← §8), which is the canonical open/closed list. The spec this extends is
> [`execution-environment.md`](execution-environment.md) §5.2.

Status: design only. Grounded against `d530c1d` (branch `herdr/ee42-workflow-subagent-attributes`).

## 1. Problem, restated

EE-40 made every fork's `EnvironmentRequest` carry a `ForkDefinition` (subagent name + flattened `attributes`), and
`EnvironmentRequest.definitionAttributes()` returns the fork's attributes whenever the request is for a fork — it does
**not** fall back to the agent's. Workflow steps are forks, but neither workflow frontend builds its subagent from a
registered definition: `InlineSubagentResolver.resolve` (graaljs, lines 37–50) synthesizes `graaljs:<agentType>` from
scratch, and `WorkflowTool` (core, lines 366–399) hard-codes five inline subagents (`workflow:perspective:<angle>`,
`workflow:synthesizer`, `workflow:candidate:<angle>`, `workflow:judge`, `workflow:skeptic`). Their
`SubagentMetadata.getAttributes()` is therefore always empty, so a provider that picks a sandbox slot from
`sandbox.slot` places every workflow step by its empty-attributes default, silently. The fix must (a) copy a registered
definition's attributes when a step names one via `agentType`, (b) let a script give attributes directly, and (c) stop
growing `SubagentResolver.resolve`'s positional parameter list — it becomes a single immutable descriptor argument.

## 2. Approach

### 2.1 Chosen

1. **Descriptor object for the resolver SPI.** New `at.aimon.workflow.graaljs.SubagentDescriptor` (immutable class +
   builder, no record) holding `agentType`, `systemPrompt`, `model`, `tools`, `maxIterations`, `attributes`.
   `SubagentResolver.resolve(SubagentDescriptor)` replaces the five-argument method. 0.x — no deprecated overload.
2. **Registry-aware default resolver.** `InlineSubagentResolver` takes an optional `SubagentRegistry`.
   `SubagentResolver.inline(SubagentRegistry)` is added; `SubagentResolver.inline()` stays and means "no registry"
   (used by existing unit tests and by callers that do not have one). `GraalJsWorkflowTool` defaults to
   `SubagentResolver.inline(subagentRegistry)` — it already holds the registry (`GraalJsWorkflowTool.java:83`), so no
   new builder input is needed.
3. **Only attributes are copied from the registered definition.** Name stays `graaljs:<agentType>`, prompt/model/
   tools/maxIterations keep today's rules. The registered definition contributes *attributes and nothing else*.
4. **Merge rule (one place, in core):** `effective = registered ⊕ explicit`, key by key, explicit wins on the same key.
   Implemented as a new `DefinitionAttributes.overlay(Map<String,String> base, Map<String,String> override)` that
   re-applies the "value and group at once" check across the merged map (a registered `sandbox.slot` + an explicit
   `sandbox` would otherwise produce an ambiguous map that `copyOf` does not reject).
   Consequence: a script cannot *remove* a registered key, only override its value (see open question Q3).
5. **Script-side attributes use the frontmatter reading.** The JS `attributes` member is deep-detached with
   `JsMarshalling.deepDetach` and passed to the existing `DefinitionAttributes.fromFrontmatter(Object)`, so nested and
   dotted spellings (`{sandbox: {slot: 'build'}}` ≡ `{'sandbox.slot': 'build'}`), scalar-to-text, and every rejection
   rule are identical to `AGENT.md` / `agents/*.md`. One reading, three sources.
6. **`WorkflowTool` built-in steps get role types looked up in the same registry.** Each built-in role gets a fixed
   lookup name — `workflow-perspective`, `workflow-synthesizer`, `workflow-candidate`, `workflow-judge`,
   `workflow-skeptic` (one per role, not per angle). If `subagentRegistry.getSubagent(role)` finds a definition, its
   attributes are copied onto the built-in step's inline subagent; otherwise the step stays attribute-free as today.
   The subagent names (`workflow:judge` …) and prompts do not change. `WorkflowTool` already holds `subagentRegistry`
   (field at `WorkflowTool.java:104`), so no constructor change. `WorkflowInput` is **not** given an `attributes`
   parameter (see rejected D).

   Lookup happens when the script closure runs (per call, per step construction), not at tool construction, so a
   registry `reload` is honored for the next run.

### 2.2 Rejected

| # | Alternative | Why rejected |
|---|---|---|
| A | Add a sixth positional parameter `Map<String,String> attributes` to `resolve(...)` | The backlog item's own reason: every external implementation breaks again on the next field. The descriptor makes future fields additive. |
| B | When `agentType` names a registered definition, use that `Subagent` wholesale (its prompt, tools, model, name) | Changes behavior of every existing script whose `agentType` happens to collide with a registered name (different prompt, tool set, and name → resume-cache misses on the `graaljs:` names). `workflow.md` records named-registry lookup as a non-goal for `AgentTask`; EE-42 asks for attributes only. Kept as open question Q1. |
| C | Builtin/graaljs steps inherit the **calling** execution's definition attributes (new write-once `ToolContextKeys.DEFINITION_ATTRIBUTES` published by the main and fork executors) | Two executors and a new write-once key for something the request already expresses: a fork's `EnvironmentRequest.parent` is the caller's environment, and design §5.2 makes "same sandbox as parent" the provider's default for a fork. Inheriting would also make an attribute-less registered definition ambiguous (inherit or empty?). Revisit if Q2 resolves the other way. |
| D | `attributes` parameter on the LLM-facing `WorkflowInput` | Lets the model choose where code runs (sandbox slot/profile) — placement is operator policy, not a model decision. Also widens a `GenericTool` schema the model sees on every turn. |
| E | Pass `agent.getMetadata().getAttributes()` into `WorkflowTool` from `OrcaSubagentToolProvider` | Wrong for nested calls: the tool registry is shared with forks, so a fork in slot X calling `Workflow` would send its steps to the *main agent's* slot. |
| F | Registry lookup only in `WorkflowBindings`/`AgentTaskMarshaller`, leaving `InlineSubagentResolver` registry-blind | A custom `SubagentResolver` would then silently lose the lookup; putting it inside the default resolver keeps "what a descriptor means" in one implementation that custom resolvers can wrap. |
| G | A per-angle lookup name for perspectives/candidates (`workflow-perspective-risk`) | Angles are free text from the model; mapping them to definition file names invites unbounded, model-chosen lookups. One name per role is enough to place a role. |

## 3. Concrete changes

### 3.1 `aimon-core`

- `at/aimon/core/base/DefinitionAttributes.java`
  - add `public static Map<String,String> overlay(Map<String,String> base, Map<String,String> override)`:
    `copyOf` both, `LinkedHashMap` base then `putAll(override)` (base order, overridden values in place, new keys
    appended), then the same group/value scan `fromFrontmatter` does (extract the existing loop into a private
    `requireNoValueGroupClash(Map, String source)` so both paths share it). Returns unmodifiable map.
  - Javadoc: explicit wins per key; cannot delete; rejection names the key.
- `at/aimon/core/tools/workflow/WorkflowTool.java`
  - five role constants `ROLE_PERSPECTIVE = "workflow-perspective"`, … (package-private or private; they are the
    documented lookup names).
  - the five `private static Subagent xxxSubagent(...)` factories become instance methods (need `subagentRegistry`)
    and call `.attributes(roleAttributes(ROLE_X))` on the builder.
  - `private Map<String,String> roleAttributes(String role)` →
    `subagentRegistry.getSubagent(role).map(s -> s.getMetadata().getAttributes()).orElse(Map.of())`, catching
    `RuntimeException` from a misbehaving registry → debug-log and `Map.of()` (see §5).
  - class Javadoc: one paragraph naming the lookup names and saying only attributes are taken.
  - `runDiscriminator` unchanged (attributes come from the registry, not the request — see §5 on background runs).

### 3.2 `aimon-workflow-graaljs`

- **new** `SubagentDescriptor.java` (public, final, builder; `equals`/`hashCode`/`toString`):
  `Optional<String> agentType()`, `Optional<String> systemPrompt()`, `Optional<String> model()`,
  `List<String> tools()` (never null; empty = unrestricted), `Optional<Integer> maxIterations()`,
  `Map<String,String> attributes()` (never null; validated via `DefinitionAttributes.copyOf` in the builder).
  Blank strings normalized to absent in the builder so resolvers do not each re-implement `isBlank` checks.
  Accessor naming follows `ForkDefinition` (`name()`, `attributes()`), the nearest EE-40 precedent.
- `SubagentResolver.java`
  - `Subagent resolve(SubagentDescriptor descriptor);`
  - `static SubagentResolver inline()` — unchanged meaning (no registry).
  - `static SubagentResolver inline(SubagentRegistry registry)` — new.
  - Javadoc: replace "no named-registry lookup — a documented non-goal" with "the registry is consulted for
    `attributes` only".
- `InlineSubagentResolver.java`
  - constructor `(SubagentRegistry registryOrNull)`.
  - after building prompt/name as today:
    `registered = hasType && registry != null ? lookup(agentType) : Map.of()`;
    `builder.attributes(DefinitionAttributes.overlay(registered, descriptor.attributes()))`.
  - `IllegalArgumentException` from `overlay` → `JsScriptException("agent '<type>': " + msg)` so the script sees
    which step failed.
- `AgentTaskMarshaller.java`
  - build a `SubagentDescriptor` from the opts and call `resolver.resolve(descriptor)`.
  - new reader `attributes(opts)`: `member(opts, "attributes")`; absent/null → `Map.of()`; not an object →
    `JsScriptException("'attributes' must be an object")`; else
    `DefinitionAttributes.fromFrontmatter(JsMarshalling.deepDetach(v))`, `IllegalArgumentException` →
    `JsScriptException`.
  - class Javadoc descriptor shape gains `attributes?`.
- `GraalJsWorkflowTool.java`
  - default: `builder.subagentResolver != null ? builder.subagentResolver : SubagentResolver.inline(subagentRegistry)`.
  - tool description string / `script` param description: mention `attributes` in the `agent({...})` shape.
- `GraalJsWorkflowScript.java` convenience constructor keeps `SubagentResolver.inline()` (no registry available there).

### 3.3 Docs (same commit as code)

- `docs/backlog/execution-environment-open-items.md`: EE-42 header → `· **닫힘** *(date)*`, add a `### 닫힘 (date)`
  paragraph (what, merge rule, role names, tests), title line `열림 38 · 닫힘 4` → `열림 37 · 닫힘 5` (verified at
  design time by counting the `## EE-n … · **열림**/**닫힘**` headers: 38 / 4), and the intro paragraph's "EE-42 는 그 PR 의 리뷰가 남긴 것이다" gets a
  closing clause.
- `docs/design/tool/execution-environment.md` §5.2 (line ~353): replace "워크플로 스크립트의 인라인 서브에이전트는 아직
  속성을 싣지 못한다(EE-42)" with the rule: `agentType` → registered attributes, script `attributes` overlays per key,
  built-in `Workflow` roles look up `workflow-<role>`.
- `docs/design/tool/execution-environment-implementation.md` §10 (departures): a short EE-42 subsection alongside the
  other "Later (EE-n)" notes — descriptor SPI break, `overlay`, role names.
- `docs/design/workflow/workflow.md`: the graaljs descriptor shape and the resolver's "non-goal" wording, where it
  mentions `SubagentResolver`.
- Translations: none of these four files has an `*.en.md` sibling today (checked `docs/design/tool/`,
  `docs/backlog/`, `docs/design/workflow/`). The implementer re-checks and runs the three `scripts/check-*.py`.
- `CHANGELOG` (if the repo keeps an Unreleased section): note the `SubagentResolver` SPI break.

## 4. Data and interface shapes

```java
// aimon-workflow-graaljs — before
Subagent resolve(String agentType, String systemPrompt, String model, List<String> tools, Integer maxIterations);

// after
Subagent resolve(SubagentDescriptor descriptor);

SubagentDescriptor d = SubagentDescriptor.builder()
        .agentType("builder")                 // goal stays on AgentTask, not here
        .attributes(Map.of("sandbox.slot", "build"))
        .build();
```

JS surface (additive):

```js
agent({ agentType: 'builder', goal: 'compile', attributes: { sandbox: { slot: 'build' } } })
parallel([{ agentType: 'reviewer', goal: 'x', attributes: { 'sandbox.profile': 'ro' } }])
```

Resolution table for one graaljs step (R = registered definition attrs, E = explicit):

| agentType registered? | `attributes` given? | effective |
|---|---|---|
| no / absent | no | `{}` (today's behavior) |
| no / absent | yes | E |
| yes | no | R |
| yes | yes | R overlaid by E (E wins per key) |

`WorkflowTool` built-in: effective = attributes of registered `workflow-<role>`, else `{}`.

`EnvironmentRequest`/`ForkDefinition`: **unchanged** — `DefaultSubagentExecutor` already copies
`subagent.getMetadata().getAttributes()` into `ForkDefinition`; this change only makes that map non-empty.

`DefinitionAttributes`: + `overlay(Map, Map)`.

## 5. Failure modes

| Case | Handling |
|---|---|
| `attributes` not an object (string/array) | `JsScriptException` naming the field → tool returns `ToolResult.error("JS workflow failed: …")`. Loud, like a bad `schema`. |
| `attributes` has list/null/empty-map/blank key/value-and-group | `fromFrontmatter`'s `IllegalArgumentException` → `JsScriptException` with the key. Same rule as definition files, so a script cannot hold what a file could not. |
| Explicit key clashes as value/group with a registered key (`sandbox` vs `sandbox.slot`) | `overlay` rejects, message names both keys and says which came from the registered definition → `JsScriptException`. |
| Numbers/booleans in JS | Become text (`deepDetach` → `Long`/`Double`/`Boolean` → `toString`). Integral doubles arrive as `Long` (`1.0` → `"1"`); documented. |
| `agentType` not registered | Not an error — `agentType` is also a free label today. Debug-log once per step. (Q4 asks whether to warn.) |
| Registry `getSubagent` throws | Default resolver lets it propagate as `JsScriptException` (graaljs); `WorkflowTool.roleAttributes` logs and uses `{}` — a built-in tool must not fail a user's workflow over an optional placement hint. Asymmetry is deliberate: the script author asked for that type. |
| No registry (`inline()`) | Registered layer is `{}`; explicit attributes still apply. |
| Background run | Resolution happens when the script executes, against the tool's registry, same as foreground. `WorkflowTool.runDiscriminator` and graaljs `discriminator` do not include attributes: an in-flight identical request is still joined even if a definition changed its slot mid-flight — acceptable, the join is by request. |
| Resume cache | Step input hash covers "goal + inline subagent definition + result schema" (`StepOutcome.java:104`). The implementer must confirm whether that hash includes `SubagentMetadata` attributes. If it does, a slot change forces a re-run (correct). If not, a resumed run can replay a step computed in another slot — the answer is still valid; note it rather than widen the hash here. |
| Attribute leakage to the model | None: attributes go to `ForkDefinition` only; nothing renders them into prompts. |

## 6. Test strategy

`aimon-core`:
- `DefinitionAttributesTest`: `overlay` — disjoint keys merge; same key → override wins; order preserved; value/group
  clash across base/override rejected with both keys named; empty/empty; null args NPE.
- `WorkflowToolTest`: with an `InMemorySubagentRegistry` holding `workflow-judge` (attributes
  `sandbox.slot=judge`) and a capturing `SubagentExecutionManager`, run `judge_panel` → the judge step's `Subagent`
  carries the attributes, candidates carry `{}`. Plus an end-to-end assertion through a real
  `DefaultSubagentExecutor` with a capturing `ExecutionEnvironmentProvider` that
  `EnvironmentRequest.definitionAttributes()` equals the registered map (acceptance 4). If wiring a real executor in
  `WorkflowToolTest` is too heavy, place that assertion next to `DefaultSubagentExecutorTest.environmentRequestCarriesForkDefinition`
  by feeding it a subagent produced by the tool's factory.
- Registry throwing → step runs with `{}`.

`aimon-workflow-graaljs`:
- `SubagentResolverTest` (existing, adapt to descriptor): the four rows of the §4 table; `inline()` ignores registry;
  clash → `JsScriptException`; existing name/prompt determinism tests kept.
- `SubagentDescriptorTest`: builder normalization (blank → absent), `tools` defensive copy, attribute validation,
  `equals`/`hashCode`.
- `MarshallingUnitTest`: `attributes` nested vs dotted equivalence; number → text; array value / non-object rejected.
- A run-level test on `AbstractGraalJsRunTest` with a capturing provider: `agent({agentType:'builder', ...})` against a
  registry holding `builder` → provider saw `definitionAttributes() == {sandbox.slot: build}`; `parallel` with
  per-descriptor attributes → each request carries its own (acceptance 4).

Gates: `./gradlew format`, `./gradlew checkAll`; ArchUnit — `aimon-workflow-graaljs` already imports
`at.aimon.core.subagent.SubagentRegistry`; `at.aimon.core.base.DefinitionAttributes` must be allowed by
`workflowMayDependOnlyOnSubagentSpiTypes` (or equivalent) for graaljs — verify, it is a `base` type.
Docs: the three `scripts/check-*.py`.

## 7. Open questions

- **Q1.** Should a registered `agentType` also supply prompt/tools/model (full definition reuse), or only attributes
  (this design)? The backlog text says "그 정의의 속성을 복사", read here as attributes only.
- **Q2.** What does the target provider do with a fork whose attributes are empty — parent's sandbox (design §5.2
  "기본은 부모와 같은 샌드박스") or the default slot (the backlog's "조용히 기본 슬롯에 둔다")? If the latter, rejected
  alternative C (inherit the caller's attributes) becomes worth its cost, at least for `WorkflowTool` built-ins.
- **Q3.** Should a script be able to *clear* a registered key (e.g. `attributes: {'sandbox.slot': null}`)? This design
  rejects null values (same as definition files), so it cannot.
- **Q4.** Unregistered `agentType` with no explicit attributes: debug-log (this design), warn, or error? Error would
  break scripts that use `agentType` as a label.
- **Q5.** Role lookup names `workflow-<role>` for `WorkflowTool`: defining such a file also makes it a Task-callable
  subagent the model can see (the registry has no "hidden" flag). Acceptable, or should the names/visibility be
  handled differently (e.g., a configured map on the tool instead of the registry)?

## 8. Where the implementation departed from this design

Everything not listed here was built as §2–§6 describe. Each entry says what the design said, what was done instead,
and why. The first group is the design review's non-blocking notes; the second is what came up in the code; the third
is what the review of the implementation changed.

### 8.1 Review notes applied

- **graaljs test harness (acceptance 4).** §6 planned "a run-level test on `AbstractGraalJsRunTest` with a capturing
  provider", but that harness mocks `SubagentExecutionManager`, so no `EnvironmentRequest` is ever built. Both options
  the review offered were taken: a new `GraalJsEnvironmentRequestTest` runs `agent()` and `parallel()` steps through
  a real `DefaultSubagentExecutionManager` and `DefaultSubagentExecutor` over a stub LLM and asserts
  `EnvironmentRequest.definitionAttributes()` per step; and `AbstractGraalJsRunTest` gained
  `run(js, SubagentResolver)` so `WorkflowBindingsFanoutTest` asserts on the `Subagent` the mock receives. On the core
  side the end-to-end assertion lives in a new `WorkflowToolAttributesTest` (real executor, capturing provider) rather
  than next to `DefaultSubagentExecutorTest`, because `WorkflowToolTest`'s fixture uses code behaviors, which never
  build an `EnvironmentRequest`.
- **User guide.** `docs/features/workflow/workflow-cli-guide.md` and its `.en.md` document `attributes` in the
  descriptor shape, the merge rule, that the step keeps its `graaljs:<agentType>` name, and the built-in role names.
- **`workflow.md` citation.** The "non-goal" wording was in the `SubagentResolver` Javadoc, not in `workflow.md`; the
  Javadoc now says the registry is consulted for attributes only. `workflow.md` only gained `SubagentDescriptor` in its
  file tree — and the resume-cache note below.
- **ArchUnit.** No rule was widened; `at.aimon.core.base.DefinitionAttributes` was already reachable from graaljs.
- **Resume cache (§5).** There is nothing to confirm: no main-source code computes `StepOutcome.inputHash`
  (`StepOutcomeCodec` only decodes stored values). `workflow.md` §(b) now lists `attributes` in what the hash must cover
  once it is computed, so a resumed step cannot replay a result computed in another slot.
- **Operator warning for role definitions (Q5).** The `WorkflowTool` class Javadoc, the guide and the backlog closing
  note say plainly that a `workflow-<role>` definition gives `Workflow` its attributes only, and is also an ordinary
  `Task`-callable subagent whose own prompt is used only when the model calls it that way. Q5 itself is EE-44.
  *(2026-10-05: EE-44 added `hidden: true` — `SubagentMetadata.isHidden()` — and the warning became an instruction to
  set it; a hidden definition is not listed by `Task` and is refused there, and is still looked up by name here.)*
- **§5.2 wording (Q2).** The spec's sentence that a sandbox provider's default for a fork is the parent's sandbox is
  kept; the new text adds that an empty-attributes step follows that default. Q2 itself is EE-43.
- **Design text nits.** The registry field is at `GraalJsWorkflowTool.java:73`, not `:83`. The `WorkflowTool`
  constructor `@param subagentRegistry` now says it is also looked up for role attributes (both constructors).
- **Clash inside the registered definition.** A code-built definition goes through `copyOf`, which skips the
  value/group scan, so its own clash would surface only at `overlay`. `overlay` now says whether the clash lies
  "within the base attributes themselves" or "between the base and override attributes".

### 8.2 Departures found in the code

- **The LLM-facing descriptions do not advertise `attributes`.** §3.2 said to mention `attributes` in
  `GraalJsWorkflowTool`'s description and `script` parameter. Not done: the model writes `WorkflowJs` scripts, and
  rejected alternative D keeps placement away from the model on exactly that ground. The field works (the backlog item
  asks for it, and scripts can be operator-authored through `GraalJsWorkflowScript`), but it is not offered to the
  model. Whether a model-written script should be able to set it at all is EE-45.
- **A registry that throws in `WorkflowTool` is logged at WARN, not DEBUG.** §3.1 / §5 said debug-log. The repo's
  error-handling rule forbids swallowing silently, and a failing registry is not normal flow; the step still runs
  with no attributes, as designed.
- **A rejected run now carries the host exception's message.** §5 says a bad `attributes` becomes a
  `JsScriptException` naming the field, surfaced as `ToolResult.error("JS workflow failed: …")`. In a run the binding
  throws inside the async script body, and `JsResultMarshaller` reported only the rejection reason's guest
  `toString()` — the exception's type, with no message. This was true of every binding error (a missing `goal` too).
  `JsResultMarshaller.settle` now reads a host exception's message, so the promised field name reaches the tool
  result. `WorkflowBindingsFanoutTest.invalidAttributesFailRun` pins it.
- **`attributes` that is a function is rejected** as "not an object", like a string or array. `deepDetach` would
  otherwise turn it into its source text and `fromFrontmatter` would report a less direct error.
- **`SubagentDescriptor.toString()` omits `systemPrompt`**, which can be long; the other fields are printed.
- **Q1, Q3 and Q4 were not promoted to the backlog.** Their consequences stay inside this change's surface: Q1 (full
  definition reuse) would change graaljs step semantics only, Q3 (clearing a key) is a rule of `overlay`, and Q4
  (unregistered `agentType`) is a log level. They remain here as the record.

### 8.3 Implementation review fixes

- **Registered keys are pinned; the script no longer wins per key.** §2.1 step 4 and the §4 table say
  `effective = registered ⊕ explicit` with the explicit value winning on the same key. The implementation review
  showed why that is unsafe: an operator registers `untrusted-runner` with `sandbox.slot: isolated`, and a
  model-written script passes `attributes: { 'sandbox.slot': 'privileged' }` to escape to the privileged slot. Now a
  script attribute whose key the registered definition already sets fails the script with a `JsScriptException`
  naming the `agentType`, the key, the registered value and the script's value; an identical value is accepted as a
  no-op, and keys the registered definition does not set may still be added. *(2026-10-05, EE-45: no longer — a key
  the registered definition does not set, and every key of an unregistered or absent `agentType`, is accepted only if
  the operator listed it in `scriptAttributeKeys`, which is empty by default. Pinning left the escape open: rename the
  step and ask for the slot.)* The check lives in
  `InlineSubagentResolver`, before it calls `overlay`. `DefinitionAttributes.overlay` keeps its generic
  override-wins semantics (§3.1) — the pinning is a graaljs policy about who wrote the override, not a merge rule.
  The residual gap is recorded in EE-45: an **unregistered** `agentType` (or none) has nothing to pin, so a script
  can still request any attributes that way; whether model-written scripts may set `attributes` at all is still open.
- **`WorkflowTool` resolves role attributes once per run.** §3.1 said roles are looked up "per run", but the lookup
  sat in the subagent factories, which the perspectives fan-out called inside each thunk on a worker thread — a
  registry reload mid-fan-out could give sibling perspectives different slots. Each built-in script now resolves the
  roles it uses at its start, on the script thread, and hands the maps to the factories.
  `WorkflowToolAttributesTest.roleAttributesResolvedOncePerRun` pins it.
- **The failing-registry contrast is documented.** `WorkflowTool` logs a failing registry and runs that role's steps
  without attributes (default placement); graaljs fails the script. The behaviour is unchanged (8.2); the class and
  `roleAttributes` Javadoc now say so and why the two frontends differ.
- **Non-finite numbers are rejected as attribute values.** `NaN` and `±Infinity` would have become the text `"NaN"` /
  `"Infinity"`; YAML cannot produce them, so they fail the script naming the key. Large finite doubles are unchanged.
- **Tests.** `pipeline()` stages are now covered end to end (`GraalJsEnvironmentRequestTest`), next to `agent()` and
  `parallel()`; the tests that relied on a script overriding a registered key now add a new key instead.
