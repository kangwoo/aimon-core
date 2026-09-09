# Design — #54 Phase 2a: a configuration surface for Anthropic thinking

> Status: **IMPLEMENTED.** Target: `aimon-cli` (`at.aimon.cli.config`, `at.aimon.cli.factory`),
> `aimon-spring-boot-starter` (`at.aimon.spring.boot.autoconfigure`). `aimon-llm-anthropic` and
> `aimon-core` are read-only here — this round is two configuration surfaces and their documentation.
>
> **§14, appended after the build, is where this document is wrong.** Everything above it is the body
> as reviewed and approved, kept byte-exact rather than corrected. That includes **about ten
> internal section pointers that are off** — `§8` usually means §9, `§11 O-n` means §13, `§9.3` means
> §10.4, and `§10.1` / `§10.2` / `§10.3` mean §11.1 / §12 / §11.2. §14 lists them and records the
> three places the implementation departed from the design.

> Run 2 of 4 on `docs/design/llm/reasoning-model-enablement.md`. Base branch
> `herdr/thinking-dialect-lookup` (#60, commit `9c4c38c`).
>
> **Everything asserted about the tree below was read or executed on 2026-09-09** in this worktree.
> Two claims were *measured* rather than read, because getting them wrong changes the shape of the
> starter half of this change; both experiments are named where they are used (§5.2). Line numbers
> are review anchors, not addresses to patch blindly.
>
> Prior decisions this design applies rather than restates:
> `docs/design/llm/model-capability-config-key.md` §2.7 (the namespace rule),
> `reasoning-model-enablement.md` §4 (Phase 2a), `spring-boot-starter-open-items.md` §5 (B-21),
> `llm-config-surface-open-items.md` L-1/L-3.

---

## 1. The problem, in one paragraph

`AnthropicConfig` carries three knobs — `thinkingMode`, `thinkingBudgetTokens`,
`replayThinkingBlocks` — that decide whether a request asks for extended thinking, in which of the
two mutually exclusive Anthropic dialects, how much of it, and whether stored thinking blocks are
replayed on the next turn. All three are reachable only from Java. The two surfaces that exist
precisely so an operator need not write Java — `aimon.yaml` in `aimon-cli`, `aimon.llm.*` in
`aimon-spring-boot-starter` — bind none of them, so a deployment assembled from configuration cannot
choose a dialect, cannot set a budget, and cannot turn replay off when the "block is bound to a
different conversation" error starts firing. The default is not useless (capture and replay are
unconditional, so an always-on thinking model already benefits from #47 with nothing configured);
what is unreachable is *tuning*. This round makes all three reachable from both surfaces in the
notations #46 established, settles where the keys live — the B-21 question the issue itself names —
and says something coherent about the one pair of keys that interact, since `AnthropicConfig`
refuses an explicit budget under any mode but `EXTENDED`.

---

## 2. The decisions, first

| # | Question | Answer |
|---|---|---|
| **K-1** | `aimon.llm.*` or `aimon.llm.anthropic.*`? (B-21) | **`aimon.llm.anthropic.*` / `llm.anthropic.*`.** All three keys fail §2.7's first test — their *names* carry Anthropic concepts — and two also fail the second. This fills the `llm.anthropic: { … }` slot `spring-boot-starter.md` §9.3's property tree left reserved. §3 is the full weighing, including the four counter-arguments. |
| **K-2** | What binds `thinkingMode` on each surface? | **CLI: the vendor enum `AnthropicThinkingMode` directly. Starter: `String`, parsed onto that enum inside the guarded Anthropic slice.** Not a style choice — the CLI has `aimon-llm-anthropic` at `implementation` scope, the starter has it at `compileOnly`, and a vendor type in an `AimonProperties` method descriptor is a measured `NoClassDefFoundError` on an OpenAI-only classpath. §5.2 |
| **K-3** | How do `thinkingMode` and `thinkingBudgetTokens` interact on the surface? | **As one gated setting, not two independent knobs.** A budget is legal only with `thinkingMode: extended`; with `auto`, `adaptive` or the default `off` it is refused **at boot, by name, on both surfaces**. That is `AnthropicConfig`'s existing rule (`AnthropicConfig.java:86-89`); the surfaces re-throw it with the key path attached rather than restating it. §4 |
| **K-4** | Who refuses a vendor block nothing will read? | **Only the branch that runs** — the CLI's `openAiConfig(...)` and the starter's `OpenAiConfiguration`. This is D-10 of `reasoning-model-enablement.md`, and it is the answer L-3 demands: a check outside a running branch turns valid configuration (`provider=none`, an app with its own `LlmClient`) into a boot failure. §6.4 |
| **K-5** | What does a deployment that sets nothing get? | **Byte-for-byte today's request.** Every setter is called only when its property is non-null, so the vendor defaults (`OFF`, no budget, replay `true`) survive untouched. Pinned by an equality assertion rather than by three separate getter checks. §8, criterion 5 |
| **K-6** | Does `AnthropicConfig.reasoningEffort` (§4.2 of the design) land here? | **No — it is #61.** This is the one place this run departs from `reasoning-model-enablement.md`, on the task's explicit instruction, and it goes in `build/deviations.md`. §10.1 |
| **K-7** | Does a sixth `model-capabilities` key for `thinkingDialect` land here? | **No.** Different key family, different failure it fixes, and it is a `model-capabilities` entry rather than a `thinking*` one. Argument in §10.2 so that the omission is a decision on the record rather than a gap. |

---

## 3. B-21, settled

The task requires this to be decided here, against
`docs/design/llm/model-capability-config-key.md` §2.7's criteria, rather than deferred. It is.

### 3.1 The rule as §2.7 states it

> **두 경우에 `aimon.llm.<provider>.*` 로 내린다 — 키가 벤더 개념을 이름에 담고 있거나, 같은 키가
> 벤더마다 다른 것을 뜻하거나. 오늘 소비자가 하나뿐이라는 사실은 쪼개는 이유가 아니다.**

Two positive tests — **the name carries a vendor concept**, or **the meaning differs per vendor** —
and one explicit non-criterion: *having only one consumer today is not a reason to split.* §2.7
applied that rule once, to `model-capabilities`, and it came out **shared** because it failed both
tests. This is its second application, and the first time the answer is the other one.

### 3.2 The rule applied, key by key

| Key | Test 1 — name | Test 2 — meaning | Verdict |
|---|---|---|---|
| `thinkingMode` | **Fails.** "Thinking" is Anthropic's word for the phenomenon; this codebase's neutral nouns for the same axis are `ReasoningEffort` and `ReasoningTrace` (`at.aimon.core.llm`). The type the key binds to is literally named `AnthropicThinkingMode`, and two of its four constants — `EXTENDED`, `ADAPTIVE` — name Anthropic wire shapes (`thinking.type=enabled`, `thinking.type=adaptive`) that have no counterpart anywhere else. | Fails too, prospectively: an OpenAI "thinking mode" would not be this three-way choice, because OpenAI has one request shape and a rung. | **vendor** |
| `thinkingBudgetTokens` | **Fails.** `budget_tokens` is a literal field of the Anthropic request body. | **Fails.** No other vendor expresses how hard to think as a token count; OpenAI expresses it as a rung on a ladder. §4.1 of the design says exactly this. | **vendor** |
| `replayThinkingBlocks` | **Fails.** "Thinking blocks" is Anthropic's wire noun — signed `thinking` content blocks. The neutral name for the same payload in this codebase is `ReasoningTrace`, and `AnthropicMessageConverter.withoutReasoningTraces` is the seam that translates between the two vocabularies. | Arguably passes on its own ("replay traces or not" is a question either vendor could be asked), which is why the name test is the one doing the work here. | **vendor** |

Three for three on test 1. There is no reading of §2.7 under which these stay shared.

### 3.3 The strongest single argument, and it is structural

`replayThinkingBlocks` is worth one more sentence because it is the key most tempting to call
neutral, and because it shows the split is not merely permitted but *tidy*. The shared namespace
already owns the neutral half of this question:

```
aimon.llm.model-capabilities.<model>.supports-reasoning-trace-round-trip   # neutral: does this model round-trip traces?
aimon.llm.anthropic.replay-thinking-blocks                                 # Anthropic: strip them anyway, because signatures break
```

The first states a **fact about a model** in provider-neutral vocabulary and both clients read it.
The second is an **escape hatch from one vendor's signature-validation failure**, documented on
`AnthropicConfig.Builder.replayThinkingBlocks` by the verbatim error it exists for (*"Invalid
`signature` in `thinking` block. The block is bound to a different conversation."*). Those are two
different things, and after this round the namespace says which is which. A shared
`aimon.llm.replay-thinking-blocks` would sit one line away from the neutral flag and mean something
categorically different from it.

### 3.4 The four counter-arguments, weighed

**C1 — the issue body proposes the shared names (`llm.thinkingMode`, `aimon.llm.thinking-mode`).**
It does, and two paragraphs later it says the opposite is *"worth settling at the same time"*,
calls `thinkingMode` *"the first key whose name carries a vendor concept"*, and says *"this issue is
the trigger that criterion was written for."* The issue poses the question and its draft answers it
the other way. Implementing the draft literally would put the first vendor-named key into the
neutral namespace **on the first occasion the criterion applied**, which makes the criterion
unfalsifiable — a rule that has only ever been used to justify not splitting is not a rule. The
draft names lose. This must be said out loud when #54 is closed (acceptance ⑥ of the design's §12).

**C2 — `model-capabilities` stayed shared even though half its flags are about reasoning traces.**
Correct and consistent. `model-capabilities` passes both tests: its name is a provider-neutral SPI's
own (`at.aimon.core.llm.capability`), and "what does this model's request surface accept?" is the
same question for every vendor — which is why both clients now read it (#52). One rule, two
applications, opposite answers, no ad-hockery.

**C3 — splitting costs a breaking key move if another vendor grows the same knob.** This is the
argument that kept `model-capabilities` shared, and here it runs **backwards**. If OpenAI ever gains
a mode selector it will not be `AnthropicThinkingMode`, so `aimon.llm.anthropic.thinking-mode` never
has to move — a sibling appears beside it. Whereas a shared `aimon.llm.thinking-mode` would have to
move on that day, because one key cannot bind two vendor enums. The expensive move is the one the
shared namespace would incur, not the vendor one.

**C4 — a vendor subtree revives the "configured and never read" problem that #52 deleted.** It does,
and that is why K-4 is part of this change rather than a follow-up. Note the direction: #46 had to
*invent* a refusal to make a shared namespace honest, and #52 deleted it once both branches read the
key. A subtree literally named `anthropic` carries no ambiguity about whose block it is, so the
refusal is easier to justify here than it ever was there. What stays open is L-3's two remaining
shapes — `provider=none`, and an application that supplies its own `LlmClient` — where no branch
runs and a check would fail valid configurations. Those are untouched in kind; their **surface grows
by three keys**, which §9.3 records.

**C5 — three keys is a thin namespace.** It is not a new namespace.
`docs/design/integration/spring-boot-starter.md` §9.3's property tree reserved `llm.anthropic: { … }`
and `llm.openai: { … }` before either had an occupant, §2.7's own counter-example table names the
next two arrivals (`responsesApiEnabled`, the sampling parameters), and `llm-config-surface-open-items.md`
L-2 tracks them. This fills a reserved slot.

### 3.5 What is written down

`docs/backlog/spring-boot-starter-open-items.md` §5's B-21 subsection — the one titled
*"B-21 은 다시 살아났고, 다른 이유로 같은 답을 냈다 ✅"* — gains a dated block recording:

1. the criterion's **second** application and its **first split**, with the three-key table of §3.2;
2. that the decision contradicts #54's own proposed key names, and why (C1);
3. that the empty `llm.anthropic` slot §9.3 reserved now has occupants, so C5's "reserved but
   unused" observation has expired the way B-21's original dissolution reason expired.

The item stays **closed** and the number is not reused: the question ("do we split?") is the same
question, this is a second answer under the same rule, and the register's own convention is that a
closed item keeps its number and grows a dated block rather than reopening. That is a judgement
call and §11 O-1 records the alternative reading.

---

## 4. The pair that interacts — `thinkingMode` × `thinkingBudgetTokens`

`AnthropicConfig`'s constructor already decides this, and the surfaces must not invent a second
answer:

```java
// AnthropicConfig.java:78-90
if (thinkingBudgetTokens != null) {
    if (thinkingBudgetTokens < AnthropicThinkingBudgets.MINIMUM_BUDGET_TOKENS) { throw …; }
    if (thinkingMode != AnthropicThinkingMode.EXTENDED) {
        throw new IllegalArgumentException("Thinking budget applies only to EXTENDED thinking mode, "
                + "but the configured mode is " + thinkingMode);
    }
}
```

`AUTO` is included in that refusal and the reason is stated on the constant itself: *"a number has no
meaning until the dialect is known, and under `AUTO` it is not known until the request is built."*

**The surface says the same thing and adds the key path.** Concretely:

| yaml / properties | Result |
|---|---|
| nothing set | today's request, byte for byte (K-5) |
| `thinkingMode: extended` + `thinkingBudgetTokens: 8000` | the budget dialect with an explicit budget; the effort, if any, is overridden and the client reports it once (`thinkingBudgetOverridesEffort`, `AnthropicLlmClient.java:654`) |
| `thinkingMode: auto` + `thinkingBudgetTokens: 8000` | **boot fails**, naming `llm.anthropic` / `aimon.llm.anthropic` and quoting the core message, which already names `EXTENDED` and the configured mode |
| `thinkingBudgetTokens: 8000` alone | **boot fails** the same way — the mode is `OFF` by default, so the budget would reach nothing. This is the "설정했는데 안 읽히는 것이 가장 나쁘다" case, and it is the most likely operator mistake of the three |
| `thinkingMode: auto` alone | thinking is asked for in whichever dialect the capability table says the model speaks; how much comes from the call's `ReasoningEffort` |

Three consequences worth writing into the operator docs rather than leaving to be discovered:

1. **The budget is not a general "how hard to think" knob.** It is the `EXTENDED` dialect's spelling
   of one. Under `auto` and `adaptive`, the amount comes from the call's `ReasoningEffort`.
2. **There is no configuration key for that effort yet.** It is #61. So today, under `auto`, the
   amount of thinking is whatever the agent's `LlmModel` carries. Saying so is better than implying
   a knob exists; promising the future key here would be worse.
3. **`auto` against a model the capability registry cannot name sends nothing and warns.** That is
   `resolveAutoDialect` (`AnthropicLlmClient.java:578-592`), and the remedy — declare the deployment's
   real name under `model-capabilities` — is one line in a block both guides already document. The
   two config families meet exactly here, and the docs should cross-link at this sentence.

**Why not make `auto` accept a budget?** It could be made to: `intendedEffort` (`:643-649`) already
converts a configured budget into a rung via `AnthropicThinkingBudgets.nearestEffort`, so `AUTO` +
budget could resolve to "this much effort, spelled in whichever dialect wins". Rejected here for two
reasons. It is a change to `AnthropicConfig`'s validation and to client semantics, i.e. core
behaviour, in a round whose subject is a config surface — the same separation #47 used when it
deferred these keys as F-6. And it reintroduces silence in the adaptive case: a token count set
against an adaptive-only model would be converted to a rung and the number itself discarded, with no
construction-time signal. Recorded as a follow-up in §11 O-2.

---

## 5. Approach, and the alternatives rejected

### 5.1 Shape: one nested block per surface, mirroring the vendor config's own field names

```yaml
# aimon-cli — camelCase, as #46 fixed for this surface
llm:
  provider: anthropic
  apiKey: "${ANTHROPIC_API_KEY}"
  model: claude-sonnet-5
  anthropic:
    thinkingMode: auto              # off (default) | extended | adaptive | auto
    thinkingBudgetTokens: 8000      # extended only; >= 1024
    replayThinkingBlocks: true      # default true
```

```yaml
# aimon-spring-boot-starter — kebab-case, as #46 fixed for this surface
aimon:
  llm:
    provider: anthropic
    anthropic:
      thinking-mode: auto
      thinking-budget-tokens: 8000
      replay-thinking-blocks: true
```

The two notations do not mix and neither surface learns the other's. Field names are the Java field
names of `AnthropicConfig` transliterated, `thinking` prefix and all — the same rule
`ModelCapabilityConfig`'s javadoc states for `supports*` (*"같은 다섯 사실에 두 번째 어휘를 만들면
아무도 유지하지 않는 번역표가 생긴다"*). No key is shortened to `mode` / `budget` / `replay` on the
grounds that the `anthropic:` parent already says which family it belongs to; that would create the
translation table the rule forbids, and `AnthropicConfig` is where an operator reading the javadoc
lands.

**Rejected — R1: `llm.anthropic.thinking.{mode,budgetTokens,replayBlocks}`.** A third level reads
better as prose and is what a fresh design would probably choose, but it renames all three keys
relative to both the issue and `reasoning-model-enablement.md` D-6, and it splits a field family
that `AnthropicConfig` keeps flat. Not worth the divergence.

**Rejected — R2: flat vendor-prefixed keys, `llm.anthropicThinkingMode`.** Avoids the nested class
on both surfaces, but it defeats the whole point of the split: a prefix inside a leaf name is not a
namespace, nothing can refuse "the anthropic block" as a unit (K-4), and §9.3's reserved subtree
stays empty while its content sits beside it.

### 5.2 Type: the vendor enum where the module is guaranteed, `String` where it is not

This is the one place where the two surfaces genuinely differ, and the reason is measured rather
than assumed.

**Measurement 1 — `getDeclaredMethods()` on a class with a vendor-typed accessor throws when the
vendor class is absent.** Compiled a two-class fixture (`host.Props` with
`vendor.Vendor getMode()` / `setMode(vendor.Vendor)`), ran it with `vendor/` removed from the
classpath:

```
instantiated ok
isPopulated (reads vendor-typed field) = false
getDeclaredMethods THREW java.lang.NoClassDefFoundError: vendor/Vendor
```

Two facts fall out, and this design uses both. Reflecting over the *methods* of a properties class
resolves their signatures and fails; **reading a vendor-typed field for a null check does not** — no
class loading happens for `field != null`.

**Measurement 2 — Spring Boot's binder reaches `getDeclaredMethods()` on a nested type, but only
when something under it is set.** `JavaBeanBinder.java:166,173-174` (spring-boot 3.5.16 sources)
calls `type.getDeclaredMethods()` for every bean it binds; `Binder.java:403-408` short-circuits
first:

```java
ConfigurationProperty property = findProperty(name, target, context);
if (property == null && context.depth != 0 && containsNoDescendantOf(context.getSources(), name)) {
    return null;
}
```

So the failure is narrow and exact: **`AimonProperties` binds fine on an Anthropic-less classpath
until somebody writes `aimon.llm.anthropic.*`, and then it dies with a raw `NoClassDefFoundError`** —
an `Error`, so Boot's `BindException` wrapping does not even catch it. The most likely way to reach
that state is the most sympathetic one: copy the yaml out of the guide, forget the dependency.

That decides the starter. It also turns out to be an invariant the module already holds and nobody
had written down: **every type named in an `AimonProperties` signature comes from an `api`
dependency** — `aimon-core`, `aimon-bootstrap`, `aimon-session-routing` — or from the starter
itself. The `Quartz` nested block carries `String`/`Integer`/`Boolean`/`Duration` only, though
`aimon-scheduling-quartz` and `quartz` are `compileOnly`, and `SchedulingBackend` /
`KnowledgeBackend` / `MemoryBackend` are starter-local enums. §8 pins the invariant rather than this
one instance.

The CLI is the opposite case: `aimon-cli/build.gradle.kts:16-17` declares both vendor modules
`implementation`, so `AnthropicThinkingMode` is present at runtime in every CLI deployment.
`at.aimon.cli.config` already binds a framework enum directly (`ModelCapabilityConfig.lowestReasoningEffort`),
and `CliConfigLoader.java:38`'s `ACCEPT_CASE_INSENSITIVE_ENUMS` was enabled for exactly that field —
it is mapper-wide, so a new enum field inherits acceptance criterion 3 with no code.

| | CLI | Starter |
|---|---|---|
| `thinkingMode` binds to | `AnthropicThinkingMode` | `String`, folded onto `AnthropicThinkingMode.values()` inside `AnthropicConfiguration` |
| case-insensitive | free (`MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS`) | explicit `equalsIgnoreCase` over `values()` |
| bad value | Jackson `InvalidFormatException` → `JsonMappingException` → `ConfigurationException` at `CliConfigLoader.java:73` | `IllegalStateException` naming `aimon.llm.anthropic.thinking-mode` and listing the four spellings |
| IDE completion | n/a | a hand-written hint in `additional-spring-configuration-metadata.json`, as `aimon.llm.provider` already has |

**The two paths cannot drift**, because the starter's fold iterates `AnthropicThinkingMode.values()`
rather than a literal list, and §8 asserts both surfaces accept all four spellings from the same
parameterised source.

**Rejected — R3: the vendor enum on the starter too.** Symmetric, free metadata, free relaxed
binding — and measurement 1 plus measurement 2 say it converts a plausible operator mistake into a
`NoClassDefFoundError` that no `FailureAnalyzer` will dress up, on a classpath where the whole point
of `compileOnly` was that the type is never loaded. It would also be the first `AimonProperties`
signature to name a `compileOnly` type, breaking an invariant the module holds today.

**Rejected — R4: a starter-local mirror enum** (`AimonProperties.ThinkingMode` with the same four
constants), the shape `SchedulingBackend` and `MemoryBackend` already use. Safe on the classpath and
free for metadata, and a test could pin it against `AnthropicThinkingMode.values()` so drift goes
red. Rejected because it is precisely the second vocabulary for one set of facts that
`ModelCapabilityConfig`'s javadoc argues against, and the thing it buys — IDE completion — is bought
for less by one metadata hint that a test can pin just as tightly (§8).

**Rejected — R5: a separate `@ConfigurationProperties("aimon.llm.anthropic")` class registered only
inside the guarded slice.** This is the shape that would let the starter keep the enum. It costs
more than it saves: `AimonDocumentedPropertiesTest`'s walker only descends types whose name starts
with `AimonProperties$` (`:705-707`), so the whole subtree would fall outside the guard that keeps
documented keys honest *and* every documented key would become unknown, turning the guide's yaml
block red; `AimonProperties.afterPropertiesSet` could not validate it; and K-4's refusal from the
OpenAI branch would have nothing to read.

### 5.3 Validation: re-throw the core exception with the key path on it

Both surfaces already do this for the `model-capabilities` family —
`LlmClientFactory.registryFor`/`declarationOf` and `AimonProperties.modelCapabilityRegistry`/`declarationOf`
catch `IllegalArgumentException` from core and re-throw naming the key the operator must edit. The
same shape here, wrapping `AnthropicConfig.Builder.build()`:

```java
try {
    return builder.build();
} catch (IllegalArgumentException e) {                       // CLI
    throw new ConfigurationException("Invalid `" + ANTHROPIC_KEY + "` in the LLM config: " + e.getMessage(), e);
}
```

**Why a blanket catch around `build()` is safe here, rather than a hand-written pre-check.** A
pre-check would be a second copy of the rule, which is what §2.1 of `model-capability-config-key.md`
exists to prevent. The blanket catch is only safe if every `IllegalArgumentException` `build()` can
throw on these paths is in fact a thinking error, so enumerate them (`AnthropicConfig.java:67-90`):
blank `apiKey` — already refused earlier and by name (`validateApiKey`, `requireApiKey`);
`temperature` out of range — not settable from either surface; `maxTokens <= 0` — not settable from
either surface; budget below 1024; budget with a non-`EXTENDED` mode. Only the last two are
reachable, and both are this round's. A null `model` throws `NullPointerException`, not
`IllegalArgumentException`, so it is not swallowed by this catch. If a future round makes
`temperature` settable (L-2/G-4), the message prefix becomes wrong — which is why the catch is
narrow to the `build()` call and its javadoc names this enumeration.

---

## 6. Concrete changes, by module and file

### 6.1 `aimon-cli` — main

| File | Change |
|---|---|
| `config/AnthropicProviderConfig.java` | **new.** The `llm.anthropic` block: `AnthropicThinkingMode thinkingMode`, `Integer thinkingBudgetTokens`, `Boolean replayThinkingBlocks`, all null = "not written", plus `isEmpty()`, `equals`/`hashCode`/`toString`. A mutable POJO with setters, like every other type in `at.aimon.cli.config` — the deserialization exemption to the builder rule (`.claude/rules/immutability-pattern.md`, cited from `CLAUDE.md`). Named `AnthropicProviderConfig` rather than `AnthropicConfig` because `LlmClientFactory` imports the vendor type of that name. |
| `config/LlmProviderConfig.java` (`:8-13`) | a sixth field, `private AnthropicProviderConfig anthropic = new AnthropicProviderConfig();`, with a null-tolerant setter that re-creates the empty instance — the same shape `setModelCapabilities` uses, so `anthropic:` with no children binds to empty rather than null. Added to `equals`/`hashCode`/`toString` (nothing in it is a secret). |
| `config/LlmProviderConfig.java` (`:60-66`) | **stale javadoc.** `getModelCapabilities`'s comment still says *"openai provider 만 읽는다"*; both providers have read it since #52. Same defect as `default-config.yaml:13-14`, same file family, fixed in the same commit. |
| `factory/LlmClientFactory.java` (`:57-79`) | `anthropicConfig(...)` applies the three settings, each only when non-null, and wraps `build()` per §5.3. New constant `ANTHROPIC_KEY = "llm.anthropic"` beside `MODEL_CAPABILITIES_KEY`. |
| `factory/LlmClientFactory.java` (`:87-110`) | `openAiConfig(...)` refuses a populated `llm.anthropic` block (K-4). One direction only: there is no `llm.openai` block to refuse in the other. |
| `resources/default-config.yaml` (`:9-24`) | a commented `anthropic:` block beside the commented `modelCapabilities:` one, in the same style — every key, the effective default, and the `extended`-only caveat on the budget. **And the stale line 13-14** *"Only the openai provider reads this block"*, which `reasoning-model-enablement.md` §2.2 already flagged and §12's acceptance ③ requires. |

### 6.2 `aimon-spring-boot-starter` — main

| File | Change |
|---|---|
| `AimonProperties.java` (`:120-146`) | four constants: `LLM_ANTHROPIC` and one per key, so every message names a property rather than spelling one. |
| `AimonProperties.java` (`:1165-1246`) | `Llm` gains `private final Anthropic anthropic = new Anthropic();` with a getter (final field + getter, like `Scheduling.quartz`). |
| `AimonProperties.java`, new nested class `Llm.Anthropic` | `String thinkingMode`, `Integer thinkingBudgetTokens`, `Boolean replayThinkingBlocks`, plus `isEmpty()`. **Nested, at any depth, inside `AimonProperties`** — `AimonProperties$Llm$Anthropic` satisfies the doc walker's `isNested` (`AimonDocumentedPropertiesTest.java:705-707`). Javadoc carries the reason for `String` (§5.2) so the next person does not "improve" it to the enum. |
| `AimonLlmAutoConfiguration.java`, `AnthropicConfiguration` (`:124-141`) | applies the three settings; a `private static AnthropicThinkingMode thinkingMode(String)` fold; `build()` wrapped per §5.3 into `IllegalStateException`. Both the fold's descriptor and `anthropicConfig`'s already sit inside the `@ConditionalOnClass`-guarded nested class, which is the placement that file's javadoc spends two paragraphs establishing. |
| `AimonLlmAutoConfiguration.java`, enclosing class | `private static void refuseAnthropicBlock(AimonProperties.Llm, String provider)`, called from `OpenAiConfiguration.openAiConfig` (K-4). On the **enclosing** class deliberately: its descriptor names only starter types, and its body only null-checks through `Llm.Anthropic.isEmpty()` — which measurement 1 showed loads nothing. |
| `resources/META-INF/additional-spring-configuration-metadata.json` | a `hints` entry for `aimon.llm.anthropic.thinking-mode` with the four values and one line each, mirroring the `aimon.llm.provider` entry that is already there for the same reason (a `String`-typed selector carries no values in its metadata `type`). |

**Not changed: `AimonProperties.afterPropertiesSet` / `validateLlm`.** It cannot fold the string onto
the enum without naming the vendor type in a method that runs in every deployment, which is the
failure §5.2 measured. Validation of these three keys therefore happens at bean-creation time inside
the guarded slice rather than in `afterPropertiesSet`, unlike `model-capabilities`. That is a real
asymmetry: the failure arrives slightly later, after some beans exist. It is still before any request
is served and the message still names the property, which is the bar `requireApiKey` and
`requireModel` already sit at in that same file — both of them are there for the same class of
reason (*"the answer depends on a bean"*). Recorded in §10.3.

### 6.3 What is deliberately *not* touched

`aimon-llm-anthropic` gains nothing. `AnthropicConfig`, `AnthropicThinkingMode`, `AnthropicLlmClient`
are all read-only here — this round is two config surfaces and their documentation. That is what makes
it independently reviewable against #60's client change, and it is why the acceptance criteria are all
about binding rather than about requests.

### 6.4 The K-4 refusal, precisely

| Deployment | Anthropic block set | What happens |
|---|---|---|
| CLI `provider: anthropic` | yes | read |
| CLI `provider: openai` | yes | **`ConfigurationException`** naming `llm.anthropic` and `llm.provider` |
| starter `provider=anthropic` (or absent) | yes | read |
| starter `provider=openai` | yes | **`IllegalStateException`** naming `aimon.llm.anthropic` and `aimon.llm.provider` |
| starter `provider=none`, or the app supplies its own `LlmClient` | yes | **not read, not refused** — L-3's two open shapes, unchanged in kind, three keys wider in surface (§9.3) |
| starter, Anthropic module absent, `provider=openai` | yes | refused by the OpenAI branch with the message above. This is the case §5.2's type choice buys; with the vendor enum it would be `NoClassDefFoundError` |

---

## 7. Data and interface shapes that change

Nothing in `aimon-core` or `aimon-llm-anthropic` changes shape. Every new type is a binding target.

```java
// aimon-cli — at.aimon.cli.config
public class AnthropicProviderConfig {
    private AnthropicThinkingMode thinkingMode;      // null = not written -> AnthropicConfig's OFF stands
    private Integer thinkingBudgetTokens;            // null = not written -> derived from the call's effort
    private Boolean replayThinkingBlocks;            // null = not written -> true stands
    // getters + setters
    public boolean isEmpty() { … }                   // all three null
}
```

```java
// aimon-spring-boot-starter — AimonProperties.Llm
public static class Anthropic {
    private String  thinkingMode;                    // folded onto AnthropicThinkingMode inside the guarded slice
    private Integer thinkingBudgetTokens;
    private Boolean replayThinkingBlocks;
    // getters + setters
    public boolean isEmpty() { … }                   // reads three fields for null only -- loads no vendor class
}
```

**Every field is boxed and null means "not written".** This is the same decision
`ModelCapabilityProperties` documents for its five flags, and here it carries three jobs at once: the
vendor default survives an absent key (K-5), `isEmpty()` can answer K-4, and a primitive
`boolean replayThinkingBlocks = true` would make the last two impossible — it could not tell "not
written" from "written true", so a deployment with no anthropic block would look populated.

**Public surface added:** three CLI accessors + one nested class in `aimon-cli` (an application module,
not published); one nested class, one getter and four constants in `AimonProperties`
(`aimon-spring-boot-starter`, published — purely additive, so `0.x` needs only a CHANGELOG line, not a
rename-map row).

---

## 8. Failure modes

| # | Shape | Disposition |
|---|---|---|
| 1 | `thinkingMode: adaptiv` (bad value), CLI | Jackson `InvalidFormatException` → `ConfigurationException("Invalid configuration structure in: <file>")` at `CliConfigLoader.java:73`; the cause naming the field and the accepted values is printed under `--verbose`. **This is exactly #46's bar for `lowestReasoningEffort`, no better and no worse** — criterion 4 asks for that bar, and improving the shared message would change every CLI mapping error and four existing test assertions. §11 O-3. |
| 2 | `thinking-mode: adaptiv` (bad value), starter | `IllegalStateException` naming `aimon.llm.anthropic.thinking-mode` and listing `off, extended, adaptive, auto`. Strictly better than failure 1, because the fold is ours. |
| 3 | `thinkingMode: auto` + a budget | Boot/CLI fails naming the block and quoting the core message, which already names `EXTENDED` and the mode. §4. |
| 4 | a budget alone (mode defaults to `OFF`) | Same failure. The most likely mistake, and the one a silent-ignore design would have hidden. |
| 5 | `thinkingBudgetTokens: 512` | Refused by `AnthropicConfig`'s 1024 floor, re-thrown with the key path. |
| 6 | misspelled leaf, CLI (`thinkingMod`) | `ConfigurationException` — the CLI mapper keeps `FAIL_ON_UNKNOWN_PROPERTIES` on. |
| 7 | misspelled leaf, starter (`thinking-mod`) | **Silent.** Boot's `ignoreUnknownFields` default. This is backlog `L-1`, not closed here, widened by three keys, and §9.2 is the line this round owes it. |
| 8 | anthropic block under `provider: openai` | Refused by the running branch (K-4). |
| 9 | anthropic block with no branch running (`provider=none`, own `LlmClient`) | Not read, not refused — L-3, open, and refusing from outside a running branch is what L-3 warns turns valid configuration into a boot failure. §9.3. |
| 10 | `aimon.llm.anthropic.*` set with `aimon-llm-anthropic` off the classpath | The OpenAI branch refuses by name (failure 8). With R3's vendor enum this would have been a raw `NoClassDefFoundError` — measured, §5.2. |
| 11 | `${VAR}` in a CLI thinking key | **Not expanded, and it fails loudly rather than passing through.** `CliConfigLoader.resolveEnvironmentVariables` walks four named string fields plus the capability map keys, and expansion happens *after* Jackson has bound; a `${…}` on an enum or `Integer` field is an `InvalidFormatException` at bind time. The starter has no such limit — Spring resolves placeholders from the `Environment` before binding. Pre-existing asymmetry across the two surfaces, documented, not fixed here. |
| 12 | `auto` against a model the registry cannot name | Nothing is sent, and the client warns once per model (`AnthropicLlmClient.java:578-592`). Not a new failure — but it is the one an operator most needs pointed at from the docs, because `auto` is the value this round makes newly reachable. The remedy is a `model-capabilities` entry, which is why §4's docs cross-link there. |
| 13 | `replayThinkingBlocks: false` while thinking is on | Already handled: the client reports it, with the dialect-specific caveat (`AnthropicLlmClient.java:466-494`). Making the flag settable makes that warning reachable from configuration for the first time. |

---

## 9. Test strategy

Everything below runs in `./gradlew test` — no billed call, no Docker tag.

### 9.1 `aimon-cli` — `LlmClientFactoryTest` (extends the existing `anthropic(...)` fixture at `:294-301`)

1. **Nothing set is today's config.** `assertThat(factory.anthropicConfig(anthropic("claude-sonnet-5")))` has `getThinkingMode() == OFF`, `getThinkingBudgetTokens() == null`, `isReplayThinkingBlocks() == true`; and an empty `anthropic:` block produces a config `equals` to the one with no block at all. `AnthropicConfig.equals` excludes the registry, so this is a clean identity assertion rather than three getters — **criterion 5**.
2. **Each of the three keys reaches the client.** Three assertions through the package-private `anthropicConfig(...)`, which exists for exactly this (`:50-56`).
3. **All four constants bind, case-insensitively** — parameterised over `off/OFF/Off`, `extended`, `adaptive`, `auto/AUTO`, sourced from `AnthropicThinkingMode.values()` so a fifth constant makes the test fail rather than silently under-cover — **criterion 3**.
4. **`auto` + budget fails naming `llm.anthropic`**, and **budget alone fails**, and **budget 512 fails** — **criterion 4**, failures 3–5.
5. **An anthropic block under `provider: openai` is refused** naming both keys — K-4.
6. `CliConfigLoaderTest`: a yaml file with a bad `thinkingMode` value fails to load, and one with a misspelled leaf fails to load (failure 6) — asserted on the loader, not the factory, because that is where `FAIL_ON_UNKNOWN_PROPERTIES` lives.

### 9.2 `aimon-spring-boot-starter`

7. **`AimonPropertiesValidationTest`:** all four spellings bind (criterion 3, same parameterised source as test 3); a bad value fails with `aimon.llm.anthropic.thinking-mode` in the stack trace (mirrors `anInvalidReasoningEffortFailsAtBinding` at `:693-698`); `auto` + budget fails naming the block; budget alone fails.
8. **`AimonAutoConfigurationTest`:** the three settings reach `AnthropicConfiguration.anthropicConfig(...)` — the same access pattern `:218-231` already uses for the registry; and with nothing set the built config carries the vendor defaults (criterion 5, starter half).
9. **The `provider=openai` refusal**, and the same with `new FilteredClassLoader("at.aimon.core.llms.anthropic")` on the runner — the second is failure 10, and it is the assertion that would have gone red under R3.
10. **The invariant, stated as a rule rather than an instance:** walk `AimonProperties` and every nested class by reflection and assert no declared method's parameter or return type is in `at.aimon.core.llms..`. Plain JUnit — the starter does not depend on ArchUnit and adding it for one rule is not worth it. The javadoc on that test carries the measurement from §5.2, because the rule is unguessable from the code it protects.
11. **The metadata hint cannot drift:** read the generated `spring-configuration-metadata.json` the way `AimonConfigurationMetadataTest` already does and assert the `aimon.llm.anthropic.thinking-mode` hint values equal `AnthropicThinkingMode.values()` lower-cased. This is what buys back the IDE completion R4 was tempted by, for the price of one assertion.
12. **`AimonDocumentedPropertiesTest` is not extended** — it already fails on an undocumented or misspelled key in a guide, so the new keys are covered by an existing gate the moment §10 writes them down. Worth stating in the PR body: no new guard, an old one now has more to check.

### 9.3 What none of this covers

No test here asserts a request body. The dialect and budget behaviour those settings select is #60's,
pinned by `AnthropicThinkingDialectTest`'s whole-body literals; duplicating it from the config side
would test `AnthropicConfig`'s setters twice and the wire once. What this round owes is *"the key
reaches the setter"*, and that is what tests 2 and 8 assert.

---

## 10. Documentation and backlog obligations

### 10.1 `CHANGELOG.md`, under `## [Unreleased]`

One entry in the LLM section, after #60's. It must say three things a reader cannot get from the
diff: the keys and both notations; **that the namespace answer contradicts #54's own proposed key
names, and on which criterion**; and that a deployment setting nothing is unchanged.

### 10.2 `docs/backlog/spring-boot-starter-open-items.md` — B-21 (criterion 2)

Per §3.5. The item stays closed with its number; a dated block records the second application of the
rule, the first split, the three-key table, and the contradiction with the issue's draft names.

### 10.3 `docs/backlog/llm-config-surface-open-items.md` — L-1 (criterion 6)

One line: the silent-misspelling asymmetry now covers `aimon.llm.anthropic.*` as well, three keys
wider, closed by none of the three routes the item already weighs (R12 / R14 / N-1). **The item's
"when to look again" trigger 2 is worth re-reading when this lands** — it fires on "a second
map-of-object key under `aimon.*`", and this is not one, so the trigger does not fire and the item's
disposition does not change. Saying that explicitly is cheaper than leaving the next reader to
re-derive it.

Note the collision the task warns about: the `L-1` in
`docs/backlog/openai-model-capabilities-open-items.md` is a **different item**, belongs to #61, and
is not touched.

### 10.4 `docs/backlog/llm-config-surface-open-items.md` — L-3

**Recommended, and not required by the task's criteria.** L-3's two shapes (`provider=none`, an app
with its own `LlmClient`) are unchanged in kind but three keys wider in surface, exactly as L-1 is,
and §4.5 of `reasoning-model-enablement.md` calls them *"unchanged and uncaused by this round"* —
true of the shapes, not of the surface. One line, same form as L-1's. If the build phase declines it,
that goes in `build/deviations.md` rather than passing silently.

### 10.5 Operator documentation (criterion 7)

Two guides, four files, and the pairs move in the same commit.

| Canonical | Translation | What it gains |
|---|---|---|
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` §4.1 | `.en.md` | a `#### llm.anthropic` subsection after the `llm.modelCapabilities` one: the three keys, the four modes with one line each, the `extended`-only budget gate (§4), what happens under `auto` on an unnamed model with a link to the `modelCapabilities` block two paragraphs above, the `${VAR}` limit (failure 11), and the closing "the two notations do not mix" line the existing subsection already carries |
| `docs/getting-started/embedding-agent-in-application.md` §4 | `.en.md` | a bullet beside the `aimon.llm.model-capabilities` one, plus the three keys inside the big `aimon:` yaml block. **Every leaf of that block is checked by `AimonDocumentedPropertiesTest`**, so the keys must be exact |

Mechanics, from `CLAUDE.md` and `documentation-guide.md` §5:

- Both `.en.md` files carry `translated_from` + `source_commit`. Set `source_commit` to
  `git log -1 --format=%h -- <canonical>` **taken before committing** (`31e1c71` at the time of
  writing); the staleness checker skips commits that touch both halves, so the one-commit lag is not
  staleness.
- Structure must match: same heading count and level, same table row counts, same fenced-block count.
  `python3 scripts/check-translation-structure.py`.
- Korean is canonical under `docs/**`; the English file is the translation. Do not translate
  identifiers, key names, or enum constants; **do** translate comments inside the yaml examples.
- Run all three: `check-doc-links.py`, `check-translation-staleness.py`,
  `check-translation-structure.py`.

`docs/design/llm/reasoning-model-enablement.md` is **not rewritten** to match the code. Its Phase 2a
row and §12 acceptance line already describe this work; the two places the implementation departs
from it go in `build/deviations.md` (§11.1, §11.2), and only if a departure would mislead a future
reader does a short section get appended.

---

## 11. Departures from `reasoning-model-enablement.md` (for `build/deviations.md`)

### 11.1 `AnthropicConfig.reasoningEffort` is not in this round

§4.2 and the Phase 2a table put it here; §12's Phase 2a acceptance says *"a deployment-wide
`reasoningEffort` now reaches Anthropic too"*. The task removes it explicitly: it is #61, and #61's
issue text rejects bundling. **The design's reason for putting it in 2a survives the removal** — it
argued that `aimon.llm.reasoning-effort` would otherwise be a shared key half of whose deployments
silently ignore it — but that reason bites only when the *key* exists, which is 2b. So the field can
travel with the key it exists to serve, and 2a is smaller and reviewable on its own. Nothing in this
round depends on it.

### 11.2 Validation of the three keys does not run in `afterPropertiesSet`

`model-capabilities` is validated twice — once in `AimonProperties.afterPropertiesSet` for the
message, once in the slice for the effect — so the failure lands before any bean exists. These three
cannot follow it: the fold onto `AnthropicThinkingMode` would name a `compileOnly` type in a method
that runs in every deployment (§5.2). Validation therefore sits inside the guarded slice with
`requireApiKey` and `requireModel`, which are there for the same class of reason. §6.2.

### 11.3 Nothing else

§4.1's key table, D-6, D-10 and §12's acceptance items ①–⑥ (minus the `reasoningEffort` clause of the
"observable change" cell) are implemented as written.

---

## 12. Explicitly out of scope

- **`reasoningEffort` anywhere** — CLI key, starter property, agent frontmatter, or
  `AnthropicConfig.reasoningEffort`. #61.
- **Streaming reasoning text.** #62.
- **A sixth `model-capabilities` key for `thinkingDialect`.** Run 1 recorded it as a consequence
  (`deviations.md` §6): the flag is declarable on `ModelCapabilityDeclaration` and therefore
  programmatically, but neither `llm.modelCapabilities.<model>` nor
  `aimon.llm.model-capabilities.<model>` binds it. **It does not belong here**, for a reason that is
  not merely "different issue": it is a different key *family* answering a different question. The
  `thinking*` keys say **what this deployment wants**; a `thinking-dialect` capability key says
  **what a model is**, and it lands in the shared namespace under a per-model map, alongside the five
  flags whose validation, precedence and case-folding rules it would inherit. Bundling it would put a
  shared-namespace key and the first vendor-namespace keys in one change and make §3's decision
  harder to review, not easier. It has its own trigger — an operator behind a gateway whose renamed
  Claude model needs `auto` to work — and it should be filed then. **If the build phase concludes
  otherwise, it argues the case in `build/deviations.md` rather than adding it quietly.**
- **Closing L-1 or L-3.** Both are widened and recorded, neither is closed. Closing L-1 is a
  starter-wide binding decision with a limitation test (`aMisspelledFlagIsSilentInTheStarter`) that is
  supposed to go red when someone does it.
- **`llm.openai.*` / the sampling parameters / `responsesApiEnabled`** — L-2 and G-4. This round
  creates the namespace they land in and puts nothing of theirs in it.

---

## 13. Open questions

Listed because the task statement does not settle them, not because they block the build.

- **O-1 — does B-21 stay closed, or reopen and re-close?** §3.5 keeps it closed with a dated block,
  on the grounds that this is a second answer under the same rule rather than a new question. The
  register's own precedent cuts slightly the other way: B-21 was *reopened* in 2026-09-09 when its
  dissolution premise expired. The difference is that a dissolution premise expiring means the item
  was never truly answered, whereas here it was answered and the answer still stands — only its scope
  grew. **A reviewer may reasonably prefer reopen-and-re-close.** Either satisfies criterion 2; the
  decision and its reasoning are what the criterion asks for, and both are written down.
- **O-2 — should `AUTO` eventually accept a budget?** §4 rejects it for this round with reasons that
  are about scope and about silence, not about impossibility; `AnthropicThinkingBudgets.nearestEffort`
  already exists and would make it nearly free. If it is ever done it is a change to `AnthropicConfig`
  validation plus a client semantic, and it wants its own round for the same reason G-1 (the `OFF` →
  `AUTO` default flip) does.
- **O-3 — is the CLI's generic mapping-error message good enough?** Failure 1 leaves an operator with
  `Configuration error: Invalid configuration structure in: <file>` and nothing about *which* key,
  unless they re-run with `--verbose`. This is #46's established bar and criterion 4 asks for that
  bar, so this design matches it. Including the Jackson cause's short message in that
  `ConfigurationException` would fix it for every CLI key at once, and it would touch four existing
  test assertions on that string. **Deliberately not bundled**, and worth its own small issue.
- **O-4 — should `llm.anthropic` support `${VAR}`?** It does not (failure 11), because CLI expansion
  runs after binding and walks four named string fields. Extending it means either expanding before
  binding or adding these fields to the walk; neither is hard and neither is asked for. The starter
  has no such limit, so this is an asymmetry the guides now have to mention.
- **O-5 — the third-level naming.** R1 (`llm.anthropic.thinking.mode`) reads better and would age
  better once `temperature` and `topP` arrive in the same block under L-2. It is rejected here only
  because it diverges from both the issue and D-6. If the reviewer prefers it, it is a rename of three
  keys with no other consequence — and it is much cheaper to decide now than after a release.

---

## 14. Where the implementation departed from this design

*Appended after the build. Everything above this line is the document as it was reviewed and
approved — it is not edited to match what was written, which is the point of keeping it.*

Written in the form [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) §15
and [`openai-model-capabilities.md`](openai-model-capabilities.md) §8 use.

### D-1 — `off` is a YAML 1.1 boolean, and §5.2's "case-insensitive: free" is true for only three of the four values

§5.2 argued that the CLI binds `AnthropicThinkingMode` directly and inherits acceptance criterion 3
from `CliConfigLoader.java:38`'s mapper-wide `ACCEPT_CASE_INSENSITIVE_ENUMS` with no code. That is
right for `extended`, `adaptive` and `auto`, and **wrong for `off`** — which is both a documented
value and the default.

SnakeYAML resolves an unquoted `off` / `OFF` / `Off` to a boolean under YAML 1.1, so the parser hands
Jackson a `VALUE_FALSE` token and the stock enum deserializer refuses it:

```
Cannot deserialize value of type `at.aimon.core.llms.anthropic.AnthropicThinkingMode`
from Boolean value (token `JsonToken.VALUE_FALSE`)
```

The parameterised test of §9.1's item 3 failed on this on its first run. Left alone, one of the four
values this document tells operators to write would not work as written, and the message would talk
about a Boolean.

**Measured, then fixed.** On jackson-dataformat-yaml 2.18.2 with snakeyaml 2.5, `getText()` on that
same `VALUE_FALSE` token still returns the **written scalar** (`off`, `OFF`, `no`, `false`). So the
field carries a `@JsonDeserialize` pointing at `AnthropicProviderConfig.ThinkingModeDeserializer`,
which folds `getText()` over `AnthropicThinkingMode.values()`. That restores the documented spelling
and widens nothing else — `no` and `false` are not thinking modes and are still refused. It reports
through `DeserializationContext.weirdStringException`, whose `InvalidFormatException` is a
`JsonMappingException`, so `CliConfigLoader` still classifies it as *"Invalid configuration
structure"* rather than as a failure to read the file.

K-2 survives: the CLI field's type is still the vendor enum, the starter's is still a `String`. What
does not survive is the claim that criterion 3 costs the CLI no code.

### D-2 — the starter has the same collision and answers it differently, which §5.2's table did not have a row for

Boot's yaml loader resolves an unquoted `off` to `Boolean.FALSE` and the binder converts it to the
string `"false"` before the fold in `AnthropicConfiguration` runs. The original scalar is gone by
then, so D-1's remedy is unavailable, and mapping `"false"` onto `OFF` would be a guess rather than a
recovery.

The fold refuses it and its message carries one extra sentence when the value is `true` / `false`:
*YAML reads an unquoted `off` as a boolean, so write it quoted: `thinking-mode: "off"`*. A list of
four accepted spellings does not tell an operator that the one they wrote needs quotes, and this is
the value somebody writes to turn thinking off. Both guides say to quote it; the starter guide's
`aimon:` block shows it quoted.

So §5.2's table gains a row this document did not anticipate — **`off`**: the CLI reads the parser's
original scalar, the starter cannot and says so in the failure. It is a real asymmetry between the
two surfaces, documented rather than removed.

### D-3 — one existing metadata test had to be edited, which §9.2 did not list

`AimonConfigurationMetadataTest.enumSelectorsCarryTheirValuesInTheType` asserted
`containsExactly(LLM_PROVIDER)` over every hint block in the generated metadata, so §6.2's second
hand-written hint made it red. It now names both `String`-typed selectors, with its comment
recording that each is a `String` for a different reason. §9.2's item 11 is implemented on top of
that as written, deriving the expected values from `AnthropicThinkingMode.values()` rather than
listing them.

### D-4 — everything else is as designed

§6's file table, §7's shapes, §8's thirteen failure modes and §9's test plan are implemented as
written, with two additions worth naming because a later reader will look for them:

- **A fourteenth failure mode**, from review-1: a budget above `max_tokens - 1` is clamped and
  warned about, and since `max_tokens` defaults to 4096 a copied `thinkingBudgetTokens: 8000` goes
  out as 4095. Both guides and `default-config.yaml` say so, and say that the ceiling is raised in
  the agent definition's `model.maxTokens` — a third configuration surface neither §10.5 nor this
  document's failure table mentioned.
- **§9.2's item 10 pins the invariant rather than one instance of it**, also from review-1: the
  reflective test asserts every `AimonProperties` signature type against an allow-list of packages,
  so a future `org.quartz.*` or actuator type fails it too — the other two `compileOnly` families.

Two smaller notes on the constants. `LlmClientFactory` gained a `PROVIDER_KEY` beside §6.1's
`ANTHROPIC_KEY`, because the refusal names both keys. And §6.2 asked for four starter constants —
one per key plus the block — where **three** shipped: nothing can name `replay-thinking-blocks`,
since it is a boolean and no value of it is refusable, and it would have been the only unreferenced
public constant in `AimonProperties`. Chasing that down sharpened §5.3 as well: its own argument is
that both reachable `IllegalArgumentException`s are the *budget's*, so both surfaces now name
`thinking-budget-tokens` / `thinkingBudgetTokens` rather than the block, which is the key the
operator edits.

And **§2–§12's internal cross-references are off** in
about ten places (review-1 lists them): `§8` mostly means §9, `§11 O-n` means §13, `§9.3` means
§10.4, `§10.1`/`§10.2`/`§10.3` mean §11.1/§12/§11.2. They are left as approved rather than silently
corrected.

### The open questions of §13, as the build left them

| # | Disposition |
|---|---|
| **O-1** — does B-21 reopen? | **Closed with a dated block**, as §3.5 chose. The register now carries the criterion's second application and first split under the same number. |
| **O-2** — should `AUTO` accept a budget? | Not done, as §4 decided. `AnthropicConfig`'s validation is untouched. |
| **O-3** — is the CLI's generic mapping-error message good enough? | Not bundled, as decided. One side-effect: D-1's deserializer means a bad `thinkingMode` now names the property and the four spellings **in the cause**, which is more than `lowestReasoningEffort` has. The top-level `ConfigurationException` string is unchanged, so #46's bar is met rather than raised. |
| **O-4** — should `llm.anthropic` support `${VAR}`? | Not extended. Documented in both guides and pinned by a test. |
| **O-5** — the third-level naming | Not taken. §5.1's flat names shipped. |

**One of the five outlives this phase and was promoted.** O-3 is not about these keys — fixing the
CLI's generic mapping message improves every CLI key at once, and its cost is the four existing
assertions on that string. It is registered as **L-5** in
[`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), beside **L-1**,
because the two are the two halves of one table: the starter is silent about a misspelled key, and
the CLI is loud without saying which one. The other four are decided or phase-local and stay here.

**L-1** and **L-3** each gained a dated block in that same register: both are widened by these three
keys and closed by none of this.
