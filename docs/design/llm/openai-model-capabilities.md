# Design — per-model capability descriptor (#44) + gpt-5.x phase-1 unblock (#43)

> Status: **IMPLEMENTED.** Sections 1-7 are the design as approved at review round 2, with seven
> factual claims corrected where that review disproved them (each correction is marked inline and
> listed in §8.1). **§8 is written after the fact** and records where the implementation departed
> from this document. Read §8 before trusting a detail in §3 or §6.
>
> Scope: **#44 in full, #43 phase 1 only.** #43 phase 2 (the Responses API path) and #45 are not
> here and were not stubbed. Branch: `fix/openai-model-capabilities`. Gate: `./gradlew checkAll`.

> **Revision 2 — what review round 1 changed.** One blocking finding, and it was correct: the
> absence assertions this document nominated (`params.temperature().isEmpty()`) cannot see the very
> trap §2.4 warns about, because `JsonField.getOptional` collapses `JsonMissing` **and** `JsonNull`
> to `Optional.empty()`. Absence is now asserted on the raw `_temperature()` / `_topP()` /
> `_presencePenalty()` / `_frequencyPenalty()` / `_reasoningEffort()` accessors (§5, §6.1), verified
> by running the SDK. Also changed: the o-series rows are **cut** from the built-in table (§3.1,
> O-2); `AgentDefinitionVersion.canonicalForm` gains the new field (§3.2); the neutral
> `ReasoningEffort` vocabulary now says why it is the common subset (§3.1); the additive
> `OpenAIConfig` shape is weighed as A12; and the CLI override gap is answered rather than left
> implicit (§2.6, O-8).

---

## 1. The problem, in one paragraph

Nothing in AIMON can answer "what does this model accept?", so `OpenAILlmClient.buildRequest()`
(`OpenAILlmClient.java:263-287`) sends the same request shape to every model: it always calls
`.temperature(...)` because `LlmModel.getTemperature()` falls back to `OpenAIConfig.getTemperature()`
whose default is `0.0` (`OpenAIConfig.java:28,125`) and which has no way to express "unset", and it
never sends `reasoning_effort` at all (`grep reasoningEffort` over the tree returns nothing). The
`gpt-5.x` family rejects `temperature`/`top_p` by the **presence** of the parameter and rejects
`tools` together with a non-`none` reasoning effort on `/v1/chat/completions`, where the server
default is `medium` — so every tool-calling turn, which is every agent turn, fails with HTTP 400 and
the documented `reasoning_effort: 'none'` workaround is unreachable from this client. Fixing that
with `if (model.startsWith("gpt-5"))` inside `buildRequest()` would put model knowledge in a place
the Anthropic client cannot reach, would be wrong for the Azure deployments and OpenAI-compatible
gateways that `OpenAIConfig.baseUrl` exists to support (they rename models freely), and would need a
new copy for the next family. This design introduces a provider-neutral, caller-overridable,
fail-open per-model capability descriptor in `aimon-core`, and rebuilds the two broken request
decisions on top of it.

---

## 2. Approach

### 2.1 The load-bearing decision: a sibling registry, not a fold into `ModelContextWindowRegistry`

**Decision: `ModelCapabilityRegistry` stands beside `ModelContextWindowRegistry`, in a new
`at.aimon.core.llm.capability` package, structurally mirroring the two per-model registries the tree
already has.**

The repo has already made this exact call once, and recorded why. `ModelPriceTable` is a *separate*
registry for a *separate* per-model fact, and `InMemoryModelPriceTable`'s javadoc says so out loud:
*"Mirrors the structure of `InMemoryModelContextWindowRegistry` so the two registries read the same
way."* Per-model facts get sibling registries with parallel structure; they do not get folded into
one god-registry. Capabilities are the third such fact.

Concretely, folding loses on four counts:

| | Folding into `ModelContextWindowRegistry` | Sibling registry |
|---|---|---|
| ISP (`docs/project/solid-principles.md`) | `DefaultCompactionGuard` would depend on a type it never uses (capabilities); `OpenAILlmClient` on `ModelContextLimits` it never uses | each consumer depends on exactly its own fact |
| SRP | "how much fits in the window" and "which parameters the endpoint accepts" change for different reasons and come from different sources | one reason to change each |
| API cost | `resolve()` already returns `ModelContextLimits`; a second fact means either changing that return type (compile break for every caller of a published SPI) or a second method on a published interface every external implementer must now think about | purely additive |
| Naming | `ModelContextWindowRegistry.resolveCapabilities(...)` makes the type name a lie — the same class of mistake `scope-model.md` §7 records two renames for | the name says what it holds |

The issue's own hint ("that existing registry is arguably the natural home") is honoured in the way
that actually matters: the new registry is **built like it** — exact entries, then case-insensitive
prefix entries in registration order, then a documented fallback — so an operator who has configured
one already knows how to configure the other.

### 2.2 Total-vs-`Optional`: implementers say "I don't know", callers get a total answer

```java
public interface ModelCapabilityRegistry {

    /** A registry that knows nothing. Every model resolves to {@link ModelCapabilities#unknown()}. */
    ModelCapabilityRegistry EMPTY = modelName -> Optional.empty();

    /** What this registry knows about the model, or empty when it does not know it. */
    Optional<ModelCapabilities> capabilitiesOf(String modelName);

    /** Total, fail-open view: an unknown model resolves to {@link ModelCapabilities#unknown()}. */
    default ModelCapabilities resolve(String modelName) {
        final Optional<ModelCapabilities> found = capabilitiesOf(modelName);
        return found == null || found.isEmpty() ? ModelCapabilities.unknown() : found.get();
    }
}
```

Two methods, because they answer to two different people, and because **fail-open is
non-negotiable** and this shape is the only one that makes it un-get-wrong-able:

- **Implementers** (an operator's Azure lookup, a config-backed table) naturally write a lookup that
  can miss. With a single total method they would have to *remember* to return
  `ModelCapabilities.unknown()` on a miss; returning `null` NPEs the provider, and returning a
  restrictive descriptor silently changes fail-open into fail-closed for every model they have not
  heard of. With `Optional` the miss case is type-directed.
- **Callers** want one non-null answer. `resolve` is that, and it is the single place the fail-open
  rule is written down — so it is also the single place a test can pin it.

`ModelPriceTable` returns `Optional` and deliberately has *no* total view, because there is no honest
default price ("cost is never silently fabricated from a guessed rate"). Capabilities do have an
honest default — "behave exactly as this client behaved before the registry existed" — so they get
both.

### 2.3 The descriptor

```java
public final class ModelCapabilities {          // immutable class + builder, per CLAUDE.md
    boolean supportsSamplingParameters();       // temperature / top_p / presence / frequency penalty
    boolean supportsReasoningEffort();          // the model takes a reasoning-effort parameter at all
    boolean supportsToolsWithReasoning();       // tools + a non-NONE effort on the same request
}
```

Three flags, the same three the issue sketched, with names sharpened from "what the model *is*" to
"what a client may *do*" (`isReasoningModel` → `supportsReasoningEffort`).

`ModelCapabilities.unknown()` is `(sampling = true, reasoningEffort = false, toolsWithReasoning =
true)`. Note that fail-open is **not** "all true" — it is "today's behaviour", which is a different
boolean per flag: today the client always sends sampling parameters and never sends
`reasoning_effort`. Those two values *are* the current behaviour, which is what makes the fail-open
regression test (§6) a byte-for-byte comparison rather than a judgement call.

The **builder is seeded with `unknown()`'s values**, so a partially specified entry is permissive by
omission. Fail-open is therefore the property of the type, not a rule each registry author has to
re-derive. **Correction (review round 2): "seeded with `unknown()`'s values" is the invariant, not
the mechanism.** Reading `unknown()` from inside the builder is a static-initialisation cycle, since
`unknown()` is itself built from a builder — the builder would observe a half-built object. The
builder seeds from private literals, and the `ModelCapabilitiesTest` row "builder defaults equal
`unknown()`" is what keeps the two definitions in step.

Sampling is one flag rather than four because nothing in the world today wants "temperature yes,
top_p no"; splitting it later is a builder method plus a getter, i.e. additive.

`supportsToolsWithReasoning` is the flag that encodes the actual 400: *on the request surface this
client uses*, this model cannot have tools and reasoning at once. That is endpoint-flavoured, and the
javadoc will say so — the built-in table describes Chat Completions because Chat Completions is the
only path `aimon-llm-openai` has. Phase 2 (Responses API) will need either a second table or an
endpoint-aware key; that is named in §7 as a known limitation, not solved here.

### 2.4 #43 phase 1 on top of the descriptor

**(a) Sampling.** `OpenAIConfig.temperature` becomes nullable-and-unset-by-default, and the `0.0`
fallback moves to request-build time behind the capability check:

```
effective temperature = LlmModel.temperature → OpenAIConfig.temperature → OpenAIConfig.DEFAULT_TEMPERATURE
```

applied **only when `capabilities.supportsSamplingParameters()`**. For every model in use today this
produces a byte-identical request (`modelConfig.orElse(config)` with config defaulting to `0.0` is
the same value as `modelConfig.or(config).orElse(0.0)`), which is what satisfies acceptance criterion
2. For a model the registry says rejects sampling, all four parameters are simply not set on the
builder.

> **The SDK trap this must avoid, and the trap in testing for it.**
> `ChatCompletionCreateParams.Builder.temperature(Double)` routes to `JsonField.ofNullable(value)`
> (`Values.kt:229`), which maps `null` to `JsonNull` — that serialises as `"temperature": null`,
> i.e. the parameter is **present**, which is exactly what gpt-5.x rejects. The same is true of the
> `Optional<Double>` overload (`temperature(temperature.getOrNull())`). Omission must be "do not
> call the setter", never "call it with null/empty" — so `applySamplingParameters` must branch
> before the setter and must never compute a nullable `Double effective` and hand it over.
>
> **The read-back accessor cannot tell the two apart.** `params.temperature()` delegates to
> `JsonField.getOptional`, which maps `JsonMissing` *and* `JsonNull` to `Optional.empty()`
> (`Values.kt:187-190`). Run against `openai-java-core:4.52.0`:
>
> ```
> omitted.temperature().isEmpty()  = true      nulled.temperature().isEmpty()  = true
> omitted._temperature()           = JsonMissing   nulled._temperature()       = JsonNull
> omitted body: … temperature=,  …              nulled body: … temperature=null, …
> ```
>
> An absence assertion must therefore use the **raw** accessor — `_temperature()`, `_topP()`,
> `_presencePenalty()`, `_frequencyPenalty()`, `_reasoningEffort()`, all public on
> `ChatCompletionCreateParams` — or compare the serialised body. `JsonMissing.of()` is a singleton,
> so `isSameAs(JsonMissing.of())` and `isInstanceOf(JsonMissing.class)` both bind. **Presence**
> assertions (`temperature()` contains `0.0`, `reasoningEffort()` contains `NONE`) are unaffected
> and stay as written. §6.1 spells this out for every absence row.

**(b) Reasoning effort.** A provider-neutral `at.aimon.core.llm.ReasoningEffort` enum, a new optional
field on `LlmModel` and on `OpenAIConfig`, and this rule in `buildRequest`:

```
if (!capabilities.supportsReasoningEffort())            -> send nothing            (today's behaviour)
else if (tools non-empty && !supportsToolsWithReasoning) -> send NONE explicitly    (the fix)
else                                                     -> send the requested effort, if any
```

"Explicitly" is scoped to the case that fails, which is what the issue describes: omission is a
problem *because* the server default is `medium` *and* tools are present. Compaction and
summarization call `sendMessage(..., List.of(), ...)`
(`DefaultCompactionEngine.java:223`), so a reasoning model can still reason on the tool-less path —
phase 1 does not disable reasoning further than the endpoint already forces.

Nothing in `buildRequest` reads the model name for anything except `.model(...)` and the registry
lookup, which is acceptance criterion 4 and is directly testable (§6, the `EMPTY`-registry test).

### 2.5 Suppression is reported, not silent — reusing the existing precedent

`LlmModel` states the rule itself, in the comment above its range validation (`LlmModel.java:54-56`
— revision 2 called it javadoc; the quote is verbatim either way and the rule it states is real):
*"What each provider then does with a legal-but-divergent
value is the provider's to report, and each one must say so at a level an operator sees (see
`AnthropicLlmClient#reportDivergence`)."* `AnthropicLlmClient` already implements it — `WARN`, once
per distinct signature, bounded at 32 entries in a `ConcurrentHashMap.newKeySet()`, with a javadoc
explaining why `WARN` and why once. `OpenAILlmClient` gets the same private helper, and reports:

- a sampling parameter that **carries a value on the request** (from `LlmModel` or `OpenAIConfig`)
  being suppressed;
- a non-`NONE` reasoning effort being clamped to `NONE` because tools are present;
- a reasoning effort set for a model whose capabilities say it takes none.

It stays **silent** when only the built-in `DEFAULT_TEMPERATURE` fallback is suppressed — nobody
configured that, so warning about it would be noise on every gpt-5 deployment. This is precisely why
`OpenAIConfig.temperature` must become genuinely unset-by-default rather than keeping `0.0` as the
builder seed: keeping the seed makes "the operator asked for 0.0" and "nobody asked" the same state.

Note the consequence, which is correct rather than unfortunate: subagent turns always carry an
explicit temperature (`SubagentLlmDefaults.java:73` sets `0.7`), so a gpt-5 deployment running
subagents *will* get one WARN line about a temperature that is not reaching the model. It is true,
and it is said once.

**Correction (review round 2): the message must not say "configured".** In that subagent path nobody
configured anything — `SubagentLlmDefaults` sets `0.7` from its own constant onto `LlmModel`, and at
the point the client decides to warn there is nothing distinguishing that from an operator's value.
The wording therefore states only that the value *is set on this request*: "temperature 0.7 is set on
this request but <model> does not accept sampling parameters; it is being omitted and the call will
succeed without it." True for both origins, and it still tells the operator exactly what to look
for.

### 2.6 Where the client gets the registry

`OpenAIConfig.Builder.modelCapabilityRegistry(ModelCapabilityRegistry)`, defaulting to
`InMemoryModelCapabilityRegistry.withDefaults()`. Both real assembly paths (`LlmClientFactory` in the
CLI, `AimonLlmAutoConfiguration` in the starter) already build an `OpenAIConfig`, and this sits
beside `baseUrl`/`timeout`, which are the other "this deployment's endpoint is not stock OpenAI"
knobs. `at.aimon.core.llm..` is on the `OpenAIArchitectureTest` whitelist, so a core type in the
config's signature is legal; `aimon-llm-openai` already exposes core types publicly on an
`implementation` dependency (`OpenAILlmClient implements LlmClient`), so nothing changes for
`PublishedModuleApiScopeTest`.

**The override is programmatic only, and CLI operators on a *renamed* deployment are not covered by
phase 1.** Stating the gap precisely, because it is narrower than it first sounds:

| Who | Covered? |
|---|---|
| CLI with a real model name (`model: gpt-5.6-terra`) | **yes** — the name matches the built-in prefix |
| Spring starter, same | **yes**; and an application can supply its own `LlmClient` bean (`@ConditionalOnMissingBean(LlmClient.class)`) to inject any registry |
| An application assembling `OpenAIConfig` itself | **yes** — `modelCapabilityRegistry(...)` |
| CLI pointing `baseUrl` at a gateway whose gpt-5 deployment is renamed (`model: prod-assistant`) | **no** — `LlmClientFactory.createOpenAIClient` has no seam, so this deployment stays on the fail-open path |

I am not closing the last row in phase 1, and the reason is that **this branch does not make that
operator worse off — it leaves them exactly where they are today**, still hitting the 400, while
fixing everyone whose model is called what it is. Closing it means inventing a user-facing yaml
schema, and there are two credible shapes (full capability entries per model, versus a one-line
"resolve capabilities as if this model were named X" alias). Choosing between them is a config-surface
decision that deserves its own issue and its own review, not a rider on a bug fix. §7 O-8 records
both shapes and my recommendation so the next person does not re-derive them.

### 2.7 Alternatives rejected

| # | Alternative | Why rejected |
|---|---|---|
| A1 | **`if (model.startsWith("gpt-5"))` in `buildRequest()`** | Explicitly barred by acceptance criterion 4, and it is the thing #44 exists to prevent: unreachable from the Anthropic client, wrong for renamed Azure/gateway deployments, one new copy per model family. |
| A2 | **Fold capabilities into `ModelContextWindowRegistry`** | §2.1. Breaks ISP for both existing consumers, conflates two facts with different change reasons, forces either a breaking return-type change or a new method on a published SPI, and makes the type name wrong. The in-tree precedent (`ModelPriceTable`) already went the other way. |
| A3 | **Single total `ModelCapabilities resolve(String)`, no `Optional`** | Fewer methods, but it moves the fail-open obligation onto every third-party implementer, where getting it wrong is silent and turns fail-open into fail-closed. §2.2. |
| A4 | **`Optional` only, no total `resolve` — every caller writes `.orElse(...)`** | Puts the fail-open constant at each call site, where the next one can write `.orElse(restrictive())`. The rule must live in one testable place. |
| A5 | **Capabilities carried on `LlmModel` (a field per flag)** | `LlmModel` is built at agent-definition load time from user frontmatter; capabilities are facts about the vendor's API, not user configuration. It would also mean every construction site of `LlmModel` (17 in main sources) has to know them. |
| A6 | **Drop the sampling fallback entirely** (issue #43's literal "sent only when the caller explicitly set them") | Contradicts acceptance criterion 2: an unknown model would stop sending `temperature: 0.0` and silently move to the server default of `1.0`, changing agent determinism for every existing deployment. That is a much larger, unrelated behaviour change than the bug being fixed. The intent is still reachable on demand — an operator who wants no sampling parameters registers a capability entry saying so, which is the override mechanism doing its job rather than a second knob. **This is a deliberate deviation from the issue's prose; §7 O-1 flags it.** |
| A7 | **A boolean `omitSamplingParameters` knob on `OpenAIConfig`** | A second lever that answers the same question as the descriptor, so the two can disagree. The descriptor is the single lever. |
| A8 | **`ModelCapabilities.defaultReasoningEffort()` — always send an explicit effort** | Makes "the server default is medium" a per-model policy in the table, muddying a capability descriptor with a policy default, for a case (reasoning model, no tools, no configured effort) where omission works fine today. §7 O-3 keeps it open. |
| A9 | **Extract `reportDivergence` into a shared `at.aimon.core.llm` helper** | Right in the abstract, but it edits `AnthropicLlmClient`, which this branch has no other reason to touch, and the two clients arguably *should* have independent bounded report sets. Duplicating ten lines is the smaller blast radius. Follow-up, not now. |
| A10 | **Name it `ModelCapabilityResolver` (the issue's sketch)** | `Registry`/`Table` is what the two sibling per-model lookups are called; a third name for the same role costs recognisability. The issue's own prose says "the same way `ModelContextWindowRegistry` is already pluggable". |
| A11 | **Put the new types flat in `at.aimon.core.llm`** | That package already holds 25 types. `cost/`, `retry/`, `token/`, `invoke/`, `tagging/`, `streaming/` are all subpackages with a `package-info.java`; `capability/` follows. (Review round 2: revision 2 listed `usage/` among them — it has no `package-info.java`. The conclusion is unaffected.) Both ArchUnit whitelists use `at.aimon.core.llm..`, so a subpackage costs nothing. |
| A12 | **Keep `double getTemperature()` and add `Optional<Double> getConfiguredTemperature()` beside it** — the non-breaking shape for §4.1 | It would spare Maven Central consumers a compile error, and that is a real cost I am choosing to pay. Rejected because it leaves two getters answering almost the same question on a published type, where the one with the obvious name is the one that lies: `getTemperature()` would keep returning `0.0` for a model that is about to be sent no temperature at all. §2.5 needs the explicit/unset distinction to decide whether to warn, and a type whose primary accessor cannot express "unset" will have that distinction read from the wrong getter by the next caller. `0.x` explicitly permits the break, the in-tree blast radius is one line plus five test assertions, and the break is a compile error rather than a silent change — which is the kind of break the CHANGELOG exists to carry. |

---

## 3. Concrete changes, by module and file

### 3.1 `aimon-core` — new

| File | Contents |
|---|---|
| `at/aimon/core/llm/capability/ModelCapabilities.java` | `public final class`, three `boolean` fields, private ctor taking `Builder`, `unknown()` (cached singleton), `builder()`, three getters, `equals`/`hashCode`/`toString`. Builder fields seeded from private literals that a test pins equal to `unknown()` — see §2.3's correction; seeding from `unknown()` itself would be a static-init cycle. |
| `at/aimon/core/llm/capability/ModelCapabilityRegistry.java` | The interface of §2.2: `EMPTY` constant, `capabilitiesOf`, `default resolve`. |
| `at/aimon/core/llm/capability/InMemoryModelCapabilityRegistry.java` | Mirror of `InMemoryModelContextWindowRegistry`: `Map<String, ModelCapabilities> exactEntries` + `LinkedHashMap` `prefixEntries` (lowercased, first match wins), `builder()`, `register(...)`, `registerPrefix(...)`, `withDefaults()`, **plus `builderWithDefaults()`** so an operator can extend the built-in table in one line (`InMemoryModelCapabilityRegistry.builderWithDefaults().register("my-azure-deployment", caps).build()`). Uses `toLowerCase(Locale.ROOT)`, matching `InMemoryModelPriceTable` rather than the older registry's locale-sensitive `toLowerCase()`. |
| `at/aimon/core/llm/capability/package-info.java` | Package javadoc stating the fail-open contract and the override story. |
| `at/aimon/core/llm/ReasoningEffort.java` | `public enum { NONE, MINIMAL, LOW, MEDIUM, HIGH }`, flat beside `StopReason` because it is a request parameter value, not a capability. (Revision 2 added a cycle argument here; review round 2 showed it does not hold — the ArchUnit slice matcher is `at.aimon.core.llm.(*)..`, which never sees a class sitting directly in `at.aimon.core.llm`, so `LlmModel` referencing a type in `capability/` could not have formed a slice edge either. The first reason is the real one.) |

**Built-in table (`withDefaults()`)** — order matters, first prefix match wins:

| Prefix (in registration order) | sampling | reasoningEffort | toolsWithReasoning |
|---|---|---|---|
| `gpt-5-chat` | true | false | true |
| `gpt-5` | **false** | **true** | **false** |
| *(anything else, o-series included)* | — unknown, i.e. `unknown()` — |

`gpt-5-chat` is registered **before** `gpt-5` for the same reason `InMemoryModelPriceTable` registers
`gpt-4o-mini` before `gpt-4o`: it is the non-reasoning variant of the family and must not inherit the
family's suppression.

**The o-series rows are cut — this is a change from revision 1.** Revision 1 registered
`o1`/`o3`/`o4-mini` as `supportsSamplingParameters = false`, on the belief that those models reject
`temperature` the same way gpt-5.x does. I could not verify that against a live API from this
worktree, and the harms are asymmetric: shipping the row when the belief is wrong produces a
**silent** sampling change for o-series users, whereas omitting it leaves them exactly where they
are today — and if the belief is *right*, where they are today is a loud HTTP 400 that nobody can be
depending on. A loud pre-existing failure can wait for a verified follow-up; a silent behaviour
change cannot be taken back. It also keeps this branch's promise literal: the only wire change is
for the family the bug names. An operator who has verified the contract adds the row in one line
through `builderWithDefaults()`, and §7 O-2 carries it as the follow-up.

This table is the one part of the design that can be wrong about the world; it is also the one part
that is fully overridable and fails open. That is why it stays as small as the bug.

**Why the neutral vocabulary is `{NONE, MINIMAL, LOW, MEDIUM, HIGH}` and not the SDK's seven.** The
SDK enum also carries `XHIGH` and `MAX` (verified). `at.aimon.core.llm.ReasoningEffort` is
provider-neutral, so it takes the **common subset** — the levels that survive translation to a
second provider — rather than re-exporting one vendor's ladder, which is the same line
`OpenAiStopReasons` draws for finish reasons and the same one `LlmModel`'s javadoc draws for
sampling ranges. Anthropic expresses the same axis as a thinking *token budget*, so even five levels
are a mapping rather than a shared type. Adding a constant later is source-compatible; a caller who
needs `xhigh` today is asking for a provider-specific escape hatch, which is a different design.

### 3.2 `aimon-core` — modified

| File | Change |
|---|---|
| `at/aimon/core/llm/LlmModel.java` | Add `ReasoningEffort reasoningEffort` field, `Optional<ReasoningEffort> getReasoningEffort()`, `Builder.reasoningEffort(ReasoningEffort)`, and include it in `equals`/`hashCode`/`toString`. Purely additive; no validation needed (enum). |
| `at/aimon/core/agent/AgentDefinitionVersion.java` | Add `lines.add("model.reasoningEffort=" + render(model.getReasoningEffort()));` to `canonicalForm` (`:113-123`), after the `frequencyPenalty` line and before `requestTimeout`. |

`canonicalForm` enumerates **every** `LlmModel` field, one `lines.add` per field, and its javadoc
says why: absent optionals are rendered rather than skipped so "an absent key and an empty value"
cannot collide. A new field left out of it means two programmatically built agents differing only in
`reasoningEffort` digest identically — the change detector reporting "unchanged" about a definition
that changed. It cannot fire today (O-4: the field is unreachable from frontmatter), which is why
this is one line rather than a section, but a digest that is silently incomplete is worse than one
that is loudly wrong.

Side effect worth one CHANGELOG sentence: adding a line changes **every** agent's digest, so a cron
task scheduled before the upgrade logs "definition changed" once after it. That is log-only —
`AgentDefinitionVersion` is a change detector, not a gate; nothing refuses to run on a mismatch — and
it is a one-time effect of the upgrade, not a recurring one.

Deliberately **not** modified: `MarkdownAgentDefinitionParser.extractModel` (§7 O-4),
`SubagentLlmDefaults`, `OrcaAgentRuntimeFactory`, `LlmProviderConfig` / `AimonProperties.Llm`
(§2.6, §7 O-8).

### 3.3 `aimon-llm-openai` — modified

| File | Change |
|---|---|
| `OpenAIConfig.java` | `temperature` becomes `Double` (nullable), builder seed `null`, getter `Optional<Double> getTemperature()`. `DEFAULT_TEMPERATURE` promoted from `private` to `public static final double` with javadoc naming where it is applied (precedent: `ModelContextLimits.DEFAULT_CONTEXT_WINDOW`). **New** nullable `topP` / `presencePenalty` / `frequencyPenalty` with `Optional<Double>` getters and builder setters, all unset by default. **New** `reasoningEffort` (`Optional<ReasoningEffort>`, unset). **New** `modelCapabilityRegistry` (`ModelCapabilityRegistry`, default `InMemoryModelCapabilityRegistry.withDefaults()`, `Objects.requireNonNull` in the setter). Range validation runs only when a value is present, using the same bounds as `LlmModel` (`temperature` 0-2, `topP` 0-1, penalties -2..2). |
| `OpenAILlmClient.java` | `buildRequest` resolves the effective model name **once**, resolves capabilities from it, and delegates to two new private methods `applySamplingParameters(...)` and `applyReasoningEffort(...)`. New private `capabilitiesFor(String)` (fail-open wrapper, §5). New private `reportDivergence(String signature, String message, Object... args)` + `MAX_REPORTED_DIVERGENCES = 32` + `Set<String> reportedDivergences`, copied in shape and javadoc rationale from `AnthropicLlmClient:378-386`. Nothing outside `buildRequest` and these helpers is touched. |
| `OpenAiReasoningEfforts.java` **(new)** | Package-private `final class`, private ctor, `static com.openai.models.ReasoningEffort toWire(at.aimon.core.llm.ReasoningEffort)` with an exhaustive `switch`. Mirrors `OpenAiStopReasons` exactly, and is the single place the vocabulary is translated — so `OpenAILlmClient` never names the SDK enum and the two same-named types never collide in one import list. |

Illustrative `buildRequest` core (not implementation):

```java
final String modelName = modelConfig.getName().orElse(config.getModel());
final ModelCapabilities capabilities = capabilitiesFor(modelName);

final ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
        .model(modelName).messages(buildChatMessages(systemPrompt, messages))
        .maxCompletionTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens()));

applySamplingParameters(requestBuilder, modelConfig, capabilities);
applyReasoningEffort(requestBuilder, modelConfig, capabilities, tools);
// tools / streamOptions blocks unchanged
```

### 3.4 Not changed — checked, and worth stating

- `aimon-llm-anthropic` — untouched. It ignores the new `LlmModel.reasoningEffort`; §7 O-5.
- `OpenAIExceptionMapper`, `OpenAIStreamingMapper`, `OpenAIMessageConverter`, `OpenAiStopReasons`,
  `TokenUsage`, `perRequestOptions`, `convertResponse` — untouched.
- `libs.versions.toml` — **no SDK bump.** The declared `com.openai:openai-java` (currently `4.52.0`
  in `gradle/libs.versions.toml:91`; the issue text says `4.18.0`, which is stale but makes the same
  point) already ships `com.openai.models.ReasoningEffort` with `NONE`/`MINIMAL`/`LOW`/`MEDIUM`/
  `HIGH`/`XHIGH`/`MAX` and `ChatCompletionCreateParams.Builder.reasoningEffort(...)`. Verified in the
  cached jar and sources jar.
- CLI `LlmProviderConfig` / starter `AimonProperties.Llm` — **no new yaml/property keys.** §7 O-4.

### 3.5 Docs

- **`CHANGELOG.md` — required.** Two entries under `[Unreleased]`: the new capability SPI, and the
  **breaking change** to `OpenAIConfig.getTemperature()` (`double` → `Optional<Double>`) with the
  before/after table this file's existing entries use. Per `docs/migration/rename-maps.md`'s own
  preamble, a signature change is not a rename, so it gets **no** row in `rename-maps.md`. The entry
  must also state the release consequence, because that is the part a releaser reads:
  `docs/project/api-stability.md` §1 says a breaking change to a published module cannot ship as a
  `0.2.x` patch, so **this forces the next release to be a minor bump.**
- **`docs/overview/features.md` + `features.en.md`** — one table row each
  (`| 모델 능력 레지스트리 | ModelCapabilityRegistry | core |` /
  `| the model-capability registry | ... |`) next to the existing context-window row, **in the same
  commit**, with `features.en.md`'s `source_commit` set to the canonical file's commit immediately
  before this one. **Correction (review round 2): the build does not enforce this.**
  `check-translation-structure.py` exits 1 only on a *fresh* mismatch — a pair whose canonical and
  translation are at the same commit yet disagree structurally. A canonical edited alone goes
  **stale**, which that script reports and exits 0 on, deliberately, so that editing a canonical
  never hands anyone a red build; `check-translation-staleness.py` does not fail either. The
  prescribed action is unchanged and is what `CLAUDE.md` requires — both files, same commit,
  `source_commit` set to the canonical's immediately preceding commit — but it is a rule to follow,
  not a net that will catch a slip.
- **`docs/overview/architecture.md` + `.en.md`** — same treatment, same reason (it already lists
  `ModelContextWindowRegistry`).
- `docs/features/llm/llm-provider-development-guide.md` (+ `.en.md`) — a short "consult the
  capability registry before setting a sampling parameter" note. Optional; if skipped, say so rather
  than leaving it implied.
- Run `python3 scripts/check-doc-links.py`, `check-translation-staleness.py`,
  `check-translation-structure.py` before committing.

---

## 4. Shapes that change

### 4.1 `OpenAIConfig` (breaking)

| Was | Is |
|---|---|
| `double getTemperature()` — always a value, default `0.0` | `Optional<Double> getTemperature()` — empty means "not configured" |
| `private static final double DEFAULT_TEMPERATURE = 0.0` | `public static final double DEFAULT_TEMPERATURE = 0.0`, documented as the fallback the client applies when the target model accepts sampling parameters |
| — | `Optional<Double> getTopP() / getPresencePenalty() / getFrequencyPenalty()`, unset by default |
| — | `Optional<ReasoningEffort> getReasoningEffort()`, unset by default |
| — | `ModelCapabilityRegistry getModelCapabilityRegistry()`, never null |
| `Builder.temperature(double)` unchanged | plus `topP` / `presencePenalty` / `frequencyPenalty` / `reasoningEffort` / `modelCapabilityRegistry` setters |

In-tree blast radius: one line in `OpenAILlmClient` and five assertions in `OpenAIConfigTest`
(`:26,50,106,109,153`). Out of tree: a compile error, which is the point — nothing breaks quietly.

### 4.2 `LlmModel` (additive)

`Optional<ReasoningEffort> getReasoningEffort()` + `Builder.reasoningEffort(ReasoningEffort)`, joined
into `equals`/`hashCode`/`toString`.

### 4.3 Wire effect, by case

| Model / registry state | `temperature` | `top_p` etc. | `reasoning_effort` |
|---|---|---|---|
| Any model, `EMPTY` or unknown | `0.0` (or configured) — **as today** | as today | absent — **as today** |
| `gpt-4o` (unknown to the table) | as today | as today | absent |
| `gpt-5.6-terra`, tools present | **absent** | **absent** | **`none`** |
| `gpt-5.6-terra`, no tools, no configured effort | absent | absent | absent (server default) |
| `gpt-5.6-terra`, no tools, effort `HIGH` | absent | absent | `high` |
| `o3` **with the shipped table** (no entry — see O-2) | as today | as today | absent — **as today** |
| `o3` with a *caller-registered* entry, tools present, effort `HIGH` | absent | absent | `high` (not clamped — the entry sets `toolsWithReasoning = true`) |
| `gpt-5-chat-latest` | as today | as today | absent |

---

## 5. Failure modes

| Failure | Handling |
|---|---|
| **A caller-supplied registry throws** from `capabilitiesOf`/`resolve`. In the streaming path `buildRequest` runs *outside* the try-with-resources, so a raw exception would escape unmapped and bypass the cancellation classification. | `capabilitiesFor(...)` catches `RuntimeException`, reports once via `reportDivergence`, and returns `ModelCapabilities.unknown()`. This is fail-open applied to the registry itself, and it is what keeps a third-party bug from reaching the four preserved behaviours. |
| **A registry returns `null`** from `capabilitiesOf`. | `default resolve` treats `null` as unknown (§2.2). |
| **A model name the table does not match** — the common Azure/gateway case: a deployment called `prod-assistant` that is really `gpt-5.6`. | Resolves to `unknown()`, so it behaves exactly as today — meaning **the gpt-5 bug is still present for that deployment until an entry is registered**. This is the accepted cost of fail-open and the single most important operational consequence of this design; it goes in the CHANGELOG entry and the package javadoc, with `builderWithDefaults()` as the one-line remedy. That remedy is programmatic, so a **CLI** deployment in this state has no way out in phase 1 — §2.6 and O-8 say so plainly rather than leaving it to be discovered. |
| **A gateway that renames a model and *does* accept sampling** but whose name matches a built-in prefix. | Parameters are suppressed and the call still succeeds with different sampling. Reported at `WARN` when anyone configured a value; overridable by registering an entry. |
| **`temperature(null)` / `temperature(Optional.empty())` on the SDK builder** sends `"temperature": null`, which gpt-5.x counts as present — the single most likely way this branch regresses, since computing a nullable effective value and passing it is the natural way to write `applySamplingParameters`. | Two things, and the second is the one that matters. (1) Omission is implemented as "do not call the setter" — the capability check branches *before* the builder call. (2) The test asserts `params._temperature()` is `JsonMissing`, **not** `params.temperature().isEmpty()`: the latter is `Optional.empty()` for `JsonNull` too (`Values.kt:187-190`, reproduced against 4.52.0), so it would stay green on exactly this regression. §2.4 and §6.1. |
| **Sending `reasoning_effort: none` to a model that rejects `none`** (o1/o3 accept `low`/`medium`/`high` but not `none`). | Cannot arise from the built-in table, which no longer carries o-series entries at all (§3.1) — they resolve to `unknown()`, whose `supportsReasoningEffort = false` means no effort is sent. An operator who adds an o-series entry must set `toolsWithReasoning = true`; the package javadoc says so at the point of registration, and the ready-to-paste entry in O-2 has it. |
| **Prefix ordering mistake** (`gpt-5` registered before `gpt-5-chat`). | Ordering is load-bearing and documented on `registerPrefix`; a unit test pins the `gpt-5-chat` vs `gpt-5` resolution specifically. |
| **`OpenAIConfig` built with an out-of-range temperature.** | Same `IllegalArgumentException` as today, now conditional on presence. Existing test coverage retained. |
| **Log noise** from divergence reporting on a hot path (`buildRequest` runs every ReAct iteration). | Once per distinct signature, bounded at 32, exactly as `AnthropicLlmClient`. Signature includes the model name so a two-model deployment reports both. |

### 5.1 How the four "please preserve" behaviours survive

All four live outside `buildRequest`, and `buildRequest` is the only method whose body changes.

1. **`StreamResponse.close()` as the abort lever, `cancellation.onCancel(...)` registered *after* the
   stream opens (`:228-235`), and the already-cancelled fast path (`:216-218`).** Untouched — no line
   between `:202` and `:254` changes except that the `buildRequest(...)` call at `:220` now returns a
   differently-populated params object. The fast path still runs before `buildRequest`. The one way
   this could have been disturbed is a throw from capability resolution before the stream opens,
   which is why §5's first row exists.
2. **Cancellable non-streaming calls routed through the streaming path (`:170-184`)** — untouched.
   `buildRequest` is shared by both paths, so the rerouted call gets exactly the same params as the
   blocking one; a test asserts the captured `createStreaming` params carry the same suppression.
3. **`OpenAIExceptionMapper`'s `SseException` branch classifying from the payload, not the HTTP 200
   stream-open status** — the file is not opened. No new exception type is introduced into the
   streaming path (the registry wrapper swallows rather than rethrows).
4. **Per-request timeout via `RequestOptions`, returning `null` to keep the single-argument SDK
   overload** — `perRequestOptions` (`:299-302`) and both call sites are unchanged; capabilities do
   not participate in overload selection.

A regression test for each already exists (`OpenAILlmClientCancellationTest`,
`OpenAILlmClientRequestTimeoutTest`, `OpenAIExceptionMapperTest`) and must stay green unmodified —
"unmodified" is part of the acceptance, since editing them would hide exactly the regression they
guard.

---

## 6. Test strategy

Every new test is a plain JUnit 5 + AssertJ + Mockito unit test; no new dependency. The existing
`OpenAILlmClientRequestTimeoutTest` shows the mocking recipe (`OpenAIClient` → `ChatService` →
`ChatCompletionService`, sentinel exception from the stub, `ArgumentCaptor<ChatCompletionCreateParams>`),
and `AnthropicLlmClientParameterDivergenceTest` shows the logback `ListAppender` recipe — logback is
on the test classpath of every module through the conventions plugin, so it is available here too.

### 6.1 The assertion vocabulary — absence must be asserted on the raw field

**This is the correction from review round 1 and the implementer must not undo it.** Revision 1
nominated `params.temperature().isEmpty()` for every absence assertion. That assertion is blind:
`ChatCompletionCreateParams.temperature()` delegates to `JsonField.getOptional`, which maps
`JsonMissing` **and** `JsonNull` to `Optional.empty()` (`Values.kt:187-190`). So the exact regression
§2.4 warns about — writing `requestBuilder.temperature(effective)` with a null `effective`, which
emits `"temperature": null` and earns the same HTTP 400 this branch exists to fix — would have left
the suppression test green. Reproduced against `openai-java-core:4.52.0`:

```
                    omitted (setter never called)   nulled (setter called with null)
temperature()       Optional.empty                  Optional.empty     <- indistinguishable
_temperature()      JsonMissing                     JsonNull           <- binds
serialised body     temperature=                    temperature=null
```

Therefore, for the five suppressible parameters:

| Asserting | Use | Not |
|---|---|---|
| **absence** | `assertThat(params._temperature()).isSameAs(JsonMissing.of())` (`JsonMissing` is a singleton; `isInstanceOf(JsonMissing.class)` binds equally). Likewise `_topP()`, `_presencePenalty()`, `_frequencyPenalty()`, `_reasoningEffort()` — all public on `ChatCompletionCreateParams`. | `params.temperature().isEmpty()` — passes on `JsonNull` |
| **presence** | `assertThat(params.temperature()).contains(0.0)`, `assertThat(params.reasoningEffort()).contains(ReasoningEffort.NONE)` — unchanged from revision 1, these were never blind | — |

A small `assertOmitted(params)` / `assertSamplingOmitted(params)` helper in the test class keeps the
five raw accessors in one place, so the next parameter added cannot be asserted the weak way by
accident. `com.openai.core.JsonMissing` is importable in this module's tests (`com.openai..` is on
the `OpenAIArchitectureTest` whitelist, and that rule excludes tests anyway).

### 6.2 `aimon-core`

| Test | Asserts |
|---|---|
| `ModelCapabilitiesTest` | `unknown()` pins each flag to today's behaviour — `sampling = true`, `reasoningEffort = false`, `toolsWithReasoning = true` — with a comment naming the fail-open contract, so a future edit has to argue with the test. Builder round-trip; builder defaults equal `unknown()`; `equals`/`hashCode`. |
| `ModelCapabilityRegistryTest` | `resolve` is total and fail-open for: `EMPTY`; a registry returning `Optional.empty()`; a registry returning `null`; a registry that *does* know the model. |
| `InMemoryModelCapabilityRegistryTest` | exact beats prefix; prefix first-match-wins in registration order; case-insensitivity; `null`/empty name → empty; `gpt-5-chat-latest` resolves to the chat entry and **not** the `gpt-5` entry; `gpt-5.6-terra` resolves to the reasoning entry; `gpt-4o` and `o3` are unknown; `builderWithDefaults()` lets a caller override a built-in prefix. |
| `LlmModelTest` (extend) | `reasoningEffort` empty by default, round-trips, participates in `equals`/`hashCode`/`toString`. |
| `AgentDefinitionVersionTest` (extend) | two agents differing only in `reasoningEffort` produce **different** versions — the collision §3.2 exists to prevent. |

### 6.3 `aimon-llm-openai` — new `OpenAILlmClientModelCapabilityTest`

Params captured on both the blocking and streaming paths.

| Test | Acceptance criterion |
|---|---|
| unknown model → `temperature()` **contains** `0.0`; `_topP()`/`_presencePenalty()`/`_frequencyPenalty()`/`_reasoningEffort()` are `JsonMissing` | **2 (fail open)** |
| model registered `supportsSamplingParameters = false`, with `LlmModel` setting all four → all four raw accessors are `JsonMissing` | **2 (suppression)** |
| **the trap test**: same as above, asserted specifically as "`_temperature()` is `JsonMissing`, and is *not* `JsonNull`" with a comment naming §2.4 | the guard that a null-writing implementation fails, which revision 1's assertion would not have caught |
| same, reasoning model + non-empty tools + `toolsWithReasoning = false` → `reasoningEffort()` **contains** the SDK `NONE` | **3** |
| reasoning model + tools + `toolsWithReasoning = true` + requested `HIGH` → `high` sent, not clamped | guards against over-clamping a caller-registered o-series entry |
| reasoning model, **no** tools, no requested effort → `_reasoningEffort()` is `JsonMissing` | behaviour preservation on the compaction path |
| **`EMPTY` registry + a model literally named `gpt-5.6-terra`** → full sampling sent, `_reasoningEffort()` `JsonMissing` | **4** — proves the client holds no model-name knowledge of its own |
| a registry whose `capabilitiesOf` throws → request identical to the unknown-model case | §5 row 1 |
| the streaming path (`createStreaming` captor) produces the same suppression, asserted on the same raw accessors | preserved behaviour 2 |
| `OpenAIConfig`-level (not `LlmModel`-level) temperature is suppressed too | resolution-chain coverage |
| `o3` with the shipped defaults → behaves exactly as an unknown model (temperature `0.0`, no reasoning effort) | pins the §3.1 decision to cut the o-series rows, so re-adding them has to change a test that says why |

Plus:

- `OpenAILlmClientParameterDivergenceTest` (mirroring the Anthropic one): a configured temperature
  suppressed by capabilities logs one `WARN`; the same value on a second call logs nothing more; a
  suppressed *fallback* (nothing configured) logs nothing at all.
- `OpenAiReasoningEffortsTest`: exhaustive neutral → wire mapping.
- `OpenAIConfigTest`: updated for the five changed assertions — default temperature is now
  **unset**, an explicitly set value round-trips, range validation still rejects out-of-range values
  when present, the new fields default to unset, and the default capability registry is non-null.
- `OpenAIArchitectureTest`, `OpenAILlmClientCancellationTest`, `OpenAILlmClientRequestTimeoutTest`,
  `OpenAIExceptionMapperTest`, `OpenAILlmClientTest` must pass **unmodified**.

Gate: `./gradlew format` then `./gradlew checkAll`. Checkstyle notes for the implementer:
`LineLength` 120, `FinalClass` + `HideUtilityClassConstructor` (the new helper class needs a private
ctor), `MissingSwitchDefault` / `DefaultComesLast` in `OpenAiReasoningEfforts`, `UnusedImports`, and
the import order from CLAUDE.md (java, javax, jakarta, org, com, blank line, project).

---

## 7. Open questions — what I could not settle from the task statement

**O-1 — the sampling fallback contradicts the issue's prose, and I chose the acceptance criteria.**
Issue #43's *Expected* says "sampling parameters are sent only when the caller explicitly set them",
which would mean dropping `DEFAULT_TEMPERATURE` entirely. TASK.md acceptance criterion 2 says an
unknown model must send "exactly what it sends today", which requires keeping it. These cannot both
hold. I kept the fallback (A6). If the intent really was to stop sending a defaulted `0.0`
everywhere, that is a separate, larger behaviour change (every existing deployment moves from
`temperature: 0` to the server default) and should be its own issue.

**O-2 — the o-series entries are deferred rather than shipped (decision changed in revision 2).**
Revision 1 seeded `o1`/`o3`/`o4-mini` as `supportsSamplingParameters = false`. That rests on two
claims I cannot verify against a live API from this worktree — that those models reject
`temperature`, and that they accept `tools` with a non-`none` `reasoning_effort` on Chat Completions.
Review round 1 pointed out that this is the only place the design changes the wire for a model the
bug does not name, and I agree, because the harms are asymmetric: a wrong entry is a **silent**
sampling change for o-series users, while no entry leaves them exactly where they are — and if the
claim is right, where they are is a **loud** 400 that nobody can be depending on. A loud pre-existing
failure can wait for a verified follow-up; a silent behaviour change cannot be taken back. So the
table ships `gpt-5-chat` + `gpt-5` only, and §6.3 pins that with an `o3`-behaves-as-unknown test so
re-adding the rows has to change a test that says why.

What stays open is *when* those rows land: when someone has run them against the real API. The entry
is one line, and it belongs in the package javadoc as a worked example so its shape is not
re-derived — note in particular that `supportsToolsWithReasoning` must stay `true`, because the
o-series accepts tools with reasoning and rejects the effort value `none`:

```java
InMemoryModelCapabilityRegistry.builderWithDefaults()
        .registerPrefix("o3", ModelCapabilities.builder()
                .supportsSamplingParameters(false)   // o-series rejects temperature / top_p
                .supportsReasoningEffort(true)
                .supportsToolsWithReasoning(true)    // MUST stay true: o-series rejects effort "none"
                .build())
        .build();
```

`gpt-5-chat-latest`'s existence is still an assumption, but the asymmetry runs the other way there:
if the model does not exist the entry is inert, whereas omitting it would wrongly suppress sampling
for a non-reasoning model. Keeping it is the safer of the two errors.

**O-3 — should a reasoning model *always* receive an explicit `reasoning_effort`?** I send it only
when tools are present and the model cannot combine them (§2.4). Reading criterion 3 at its most
literal — "a reasoning model sends `reasoning_effort` explicitly rather than relying on omission" —
would mean always sending one, which needs a per-model default value in the descriptor (A8) and
encodes "the server default is medium" as data. I judged the narrow reading correct because the
issue's justification for "explicitly" is that the request *fails* otherwise, which only happens with
tools. Worth a second opinion.

**O-4 — no configuration surface for reasoning effort.** `LlmModel.reasoningEffort` is not readable
from agent-definition frontmatter (`MarkdownAgentDefinitionParser.extractModel` reads only `name`,
`temperature`, `maxTokens`, `topP` — it already ignores **three** existing `LlmModel` fields:
`presencePenalty`, `frequencyPenalty` and `requestTimeout`; revision 2 said two, and the correction
strengthens the point rather than weakening it), and
`OpenAIConfig.reasoningEffort` is not readable from CLI yaml or starter properties. Both are
programmatically reachable only. I left it that way because in phase 1 a non-`NONE` effort is clamped
on every tool-calling turn anyway, so a yaml key would look like a knob that does nothing; phase 2 is
the natural time to add it. Say so if a phase-1 config key is wanted.

**O-5 — `LlmModel.reasoningEffort` is silently ignored by Anthropic.** `LlmModel`'s javadoc sets the
rule that a provider which drops a configured value must report it
(`AnthropicLlmClient#reportDivergence` does this for the penalties). Adding a neutral field that
Anthropic ignores silently opens a small honesty gap. The fix is ~6 lines in `AnthropicLlmClient`,
but it is a module this branch otherwise does not touch. Flagged rather than done.

**O-6 — `supportsToolsWithReasoning` is endpoint-flavoured in an endpoint-agnostic type.** The flag
means "on the request surface the resolving client uses", which is fine while `aimon-llm-openai` has
exactly one such surface. Phase 2 adds a second (Responses), where the same model's answer flips to
`true`. That will need either a second registry instance selected per endpoint or an endpoint
argument in the lookup key. Not solved here, and deliberately not stubbed — but the phase-2
implementer should know the flag is where the seam will have to open.

**O-7 — doc footprint.** I recommend adding the one-row entries to `features.md`/`features.en.md` and
`architecture.md`/`architecture.en.md` (both already list `ModelContextWindowRegistry`, so omitting
capabilities makes those tables quietly incomplete). This drags four files and two `source_commit`
bumps into the change. If the human would rather keep the diff to code + `CHANGELOG.md`, that is a
reasonable trim — but it should be a stated decision, not an omission.

**O-8 — no CLI override for a renamed gateway deployment, and I chose not to invent one here.**
§2.6 states the gap: `LlmClientFactory.createOpenAIClient` has no seam, so a CLI deployment whose
gpt-5 model is renamed (`model: prod-assistant` behind a gateway `baseUrl`) stays on the fail-open
path and keeps hitting the 400. Raised as non-blocking in review round 1, and I am keeping the
answer, for three reasons stated so they can be argued with: this branch does not make that operator
worse off; it *does* fix every CLI operator whose model is called what it is, which is the ordinary
case; and closing the last case means choosing a user-facing yaml schema, which deserves review on
its own terms rather than as a rider on a bug fix. Note this is a different reason from O-4's — that
one declines a `reasoningEffort` key because it would be a knob that does nothing in phase 1, and the
registry is the one override that *does* something.

The two candidate shapes, so the follow-up starts from here rather than from scratch:

| Shape | Cost | Trade-off |
|---|---|---|
| **Capability alias** — `modelCapabilitiesAs: gpt-5`, resolved through the built-in table and registered under the deployment's real name | one optional string on `LlmProviderConfig`, ~5 lines in `createOpenAIClient`, the same 5 in the starter | **My recommendation.** Reuses the built-in table instead of copying vendor facts into every operator's yaml, so a corrected table entry reaches them for free. Costs one new user-facing concept. |
| **Full capability entries** — a yaml list of `{model, samplingParameters, reasoningEffort, toolsWithReasoning}` | a new config type, parsing, validation, tests, docs in two languages | More expressive, and the only option for a model the built-in table has never heard of. But it freezes three flag names into a user-facing schema before a second provider has weighed in on them. |

The Spring starter is less exposed either way: an application can already supply its own `LlmClient`
bean past `@ConditionalOnMissingBean(LlmClient.class)` and inject whatever registry it likes.

---

## 8. What the implementation changed

Written after the code landed. The seam, the type shapes, the fail-open rule, the built-in table and
every row of the §4.3 wire-effect table are as approved; nothing load-bearing moved. What follows is
the complete list of differences.

### 8.1 Seven claims in this document were wrong, and are corrected above

Review round 2 passed the design and listed these as non-blocking defects it would otherwise carry
into implementation. Each is corrected in place, marked as a round-2 correction so the original
claim and its replacement are both visible.

| § | The claim that was wrong | What is true |
|---|---|---|
| §3.5 | `check-translation-structure.py` fails the build on a one-sided doc edit | It exits 1 only on a *fresh* mismatch; a stale pair is reported and exits 0, on purpose. The rule stands; the enforcement does not |
| §3.5 / §4.1 | the CHANGELOG entry needs the break and a before/after table | it also needs to say the break **forces a minor bump** — `api-stability.md` §1 bars a `0.2.x` patch — because that is the part a releaser reads |
| §2.5 | the WARN says a *configured* value is being suppressed | in the subagent path nobody configured anything (`SubagentLlmDefaults` sets `0.7` from its own constant) and the client cannot tell that apart. The message says only that the value "is set on this request" |
| §2.3 / §3.1 | the builder is seeded with `unknown()`'s values | that is the *invariant*. Implemented literally it is a static-init cycle, since `unknown()` is itself built from a builder. It seeds from literals; a test pins the two equal |
| A11 | `usage/` carries a `package-info.java`, and a flat `ReasoningEffort` avoids a slice edge | `usage/` does not. And the slice matcher is `at.aimon.core.llm.(*)..`, which never sees a class directly in `at.aimon.core.llm` — there was no edge to avoid. The placement is still right for the first reason given |
| O-4 | `extractModel` already ignores two existing `LlmModel` fields | three (`presencePenalty`, `frequencyPenalty`, `requestTimeout`) — which strengthens the point |
| §2.5 | the "each provider must report" rule is `LlmModel`'s javadoc | it is the comment above its range validation (`LlmModel.java:54-56`). Verbatim either way |

### 8.2 Where the code differs from §3 and §6

- **`LlmModelTest` was not extended.** §6.2 nominated extending it; that class states its own focus
  as the per-request `requestTimeout`, so the new coverage went to a sibling
  `LlmModelReasoningEffortTest` rather than widening a class that says what it is about.
- **`applySamplingParameters` / `applyReasoningEffort` take the resolved model name.** §3.3's
  illustrative call omits it, but §5 requires the divergence signature to include the model ("so a
  two-model deployment reports both") and the message names it. Resolving the name twice, or reaching
  back into `config`, would be wrong for a request whose `LlmModel` overrides it.
- **The reasoning-effort resolution chain is `LlmModel → OpenAIConfig`, spelled out.** §2.4 says only
  "the requested effort, if any"; §3.3 adds the field to `OpenAIConfig`, which has a purpose only if
  the client reads it. Implemented symmetrically with sampling.
- **Absence is asserted with `isInstanceOf(JsonMissing.class)` in the shared helper**, with
  `isSameAs(JsonMissing.of())` and `isNotInstanceOf(JsonNull.class)` kept in the dedicated trap test.
  §6.1 offers both and says they bind equally; the helper takes the form that does not depend on the
  singleton, the trap test still exercises the singleton claim.
- **One test exists that §6.3 did not nominate**, and it is the most important one:
  `stockConfigFixesTheReportedFourHundred` — a stock `OpenAIConfig`, no registry override, model
  `gpt-5.6-terra`, one tool, asserting `_temperature()` is `JsonMissing` and `reasoningEffort()`
  contains `NONE`. Review round 2 pointed out that every other suppression test builds its registry by
  hand, so wiring the config default to `ModelCapabilityRegistry.EMPTY` would leave the whole
  nominated suite green with the reported 400 unfixed. This is the test that fails on that.
  `OpenAIConfigTest` gained the matching assertion for the same reason: "the default registry is
  non-null" is satisfied by `EMPTY`.
- **The doc footprint includes the LLM provider guide** (§3.5 marked it optional), and it had to:
  that guide's example contained `modelConfig.getTemperature().orElse(config.getTemperature())`,
  which stops compiling the moment `getTemperature()` returns `Optional<Double>`. Both languages
  updated in the same commit.

### 8.3 The two traps were reproduced, not assumed

Both mistakes review round 2 warned about were introduced into the working tree and confirmed to fail
before being reverted.

| Mutation | Result |
|---|---|
| `OpenAIConfig`'s default registry wired to `ModelCapabilityRegistry.EMPTY` | **12 tests fail**, including `stockConfigFixesTheReportedFourHundred` |
| suppression implemented as `requestBuilder.temperature((Double) null)` | **7 tests fail** on the raw accessors — while a throwaway probe asserting `params.temperature().isEmpty()` **passed** on the same mutation |

The second row is the whole reason §6.1 exists: the weak assertion is blind to exactly the bug it
would be guarding, measured rather than argued.

### 8.4 What stayed open

Every open question in §7 is still open, and none of them grew. O-1 (the fallback kept, deviating
from #43's prose), O-2 (the o-series rows deferred until someone runs them against the real API — a
test pins `o3` as behaving like an unknown model, so re-adding them has to change a test that says
why), O-3, O-4 (no yaml key for reasoning effort), O-5 (`AnthropicLlmClient` ignores the new field
silently), O-6 (`supportsToolsWithReasoning` is endpoint-flavoured and is where the phase-2 seam will
have to open) and O-8 (no CLI override for a renamed gateway deployment) are unchanged. O-7 was
answered by doing it: the `features` and `architecture` rows are in, in both languages, plus the
provider guide.
