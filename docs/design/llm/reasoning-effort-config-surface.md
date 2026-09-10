# Design — #61 Phase 2b: a configuration surface for `reasoningEffort`, and the two things it drags in

> Status: **IMPLEMENTED.**
>
> **§17, appended after the build, is where this document is wrong.** Everything above it is the body
> as reviewed and approved, kept byte-exact rather than corrected — including inventories that turned
> out to undercount and one internal contradiction (§4.2 against §3.4 / O-4). §17 records where the
> implementation departed from it and why, and lists the corrections the design review found.

> Target: `aimon-core` (`at.aimon.core.llm.capability`, `at.aimon.core.agent.definition.parser`),
> `aimon-llm-anthropic`, `aimon-llm-openai`, `aimon-cli`, `aimon-spring-boot-starter`.
>
> **No API call was made for this document.** Every fact below is read out of the tree on
> **2026-09-10** on branch `herdr/reasoning-effort-config` (base `320fbbc`), or quoted from a design
> document that says whether *it* measured the thing. §15 is the list of what that leaves unverified.
> Line numbers drift; they are anchors for review, not addresses to patch blindly.
>
> Prior documents this one continues and does not restate:
> `docs/design/llm/reasoning-model-enablement.md`
> §4 (Phase 2b, D-6/D-7) · §4.2 (the Anthropic asymmetry);
> `docs/design/llm/model-capability-config-key.md` §2.7 (the namespace rule);
> `docs/design/llm/anthropic-thinking-config-surface.md` (#54, the opposite namespace answer);
> `docs/design/llm/openai-model-capabilities.md` §13.3 (terra's measured ladder) · §13.4 (why the fix was deferred);
> `docs/backlog/openai-model-capabilities-open-items.md` `L-1`.
> In-tree paths are repository-relative from here on.
>
> This document is written to be published in-tree at
> `docs/design/llm/reasoning-effort-config-surface.md` — see §12 for why, and for the row it adds
> to `docs/design/README.md`. It is in English, matching its siblings in `docs/design/llm/`;
> `docs/design/` is not a translation target either way
> (`docs/project/documentation-guide.md` §5.1).

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **K-1** | Which namespace for `reasoningEffort`, given that #54 just split the other way? | **Shared.** `llm.reasoningEffort` / `aimon.llm.reasoning-effort` / `model.reasoningEffort`. Same criterion as #54, opposite input: the name is the neutral SPI's own (`at.aimon.core.llm.ReasoningEffort`) and the meaning does not differ per vendor, so §2.7's two tests both answer "no". §2 |
| **K-2** | What shape replaces `lowestReasoningEffort`? | **One field, `acceptedReasoningEfforts`, an immutable `Set<ReasoningEffort>`, never empty.** The floor survives as an *input* — `Builder.lowestReasoningEffort(X)` is shorthand for "the ladder starts at X and has no holes" — and dies as an *output*: the getter is removed. A floor is a fine way to **write** a ladder with no holes; it is not a fine way to **read** one, and reading one is where the 400 came from. §3 |
| **K-3** | What happens to the published `0.x` SPI? | One method removed (`ModelCapabilities.lowestReasoningEffort()`), one added (`acceptedReasoningEfforts()`), builders and the declaration type extended. Source-breaking, permitted by `docs/project/api-stability.md` §5, and taken in one go rather than through a `@Deprecated` adapter, which is that section's own stated habit for `0.x`. CHANGELOG entry + a `rename-maps.md` row. §3.3 |
| **K-4** | Does the configuration key `lowestReasoningEffort` change name? | **No, and that is not a style choice.** The starter ignores an unknown property silently (`llm-config-surface-open-items.md` `L-1`), so renaming that key would make every deployment that declared it lose the declaration *without a word*. The key stays and gains a sibling, `acceptedReasoningEfforts`. §3.5 |
| **K-5** | Exact-shadows-prefix: what row does terra get? | **An exact row**, `register("gpt-5.6-terra", …)`, and the promise the javadoc makes about the `gpt-5` override **narrows to the one it can keep** — the same narrowing the class already writes for `o1` vs `o1-2024-12-17`, with the same escape hatch. The javadoc example moves to a name the prefix still answers for, and `builderWithDefaultsOverridesAPrefixInPlace` moves with it and grows two assertions. §4 |
| **K-6** | How does the shared key reach Anthropic? | `AnthropicConfig.reasoningEffort` with `LlmModel`-first precedence, resolved in **one** private helper because `AnthropicLlmClient` reads the effort in **two** places (`resolveThinking:519` and `intendedEffort:648`) and changing one would let the gate and the warning disagree. §5 |
| **K-7** | A deployment-wide effort under Anthropic's default `thinkingMode: OFF` does nothing. Silent? | **No — one WARN, once per signature.** Without it, "the shared key means the same thing on both providers" is only half true: it *reaches* Anthropic and is inert under the shipped default. This is the "configured and never read" failure the repository refuses everywhere else, and a divergence report is the instrument this client already owns for it. §5.3 |
| **K-8** | Do the terra fixtures stay as they are? | **No.** `gpt-5.6-terra` is this repository's stand-in for the `gpt-5` prefix row in two modules. Every fixture that means "the gpt-5 row" is re-pointed to a name that still lands on it. Two tests go red on their own; the rest would stay green while testing a different row, and that is the hazard the backlog named. §4.4 |

---

## 1. The problem, in one paragraph

How hard a reasoning model should think is settable only from Java. `MarkdownAgentDefinitionParser.extractModel`
parses `name`, `temperature`, `maxTokens`, `topP` and stops (`:152-163`); neither configuration surface
carries a key (`grep -rn "reasoningEffort\|reasoning-effort" modules/aimon-spring-boot-starter/src/main
modules/aimon-cli/src/main` → 0 on 2026-09-10). So the one knob that decides how much reasoning a
reasoning model does is unreachable from the surfaces that exist precisely so an operator need not write
Java. Adding the key is three small edits; what makes this round bigger than that is what the key
*exposes*. `ModelCapabilities.lowestReasoningEffort` models a floor, `gpt-5.6-terra` resolves to the
`gpt-5` prefix row whose floor is `MINIMAL`, and terra **rejects** `minimal` while **accepting** `none`
(measured 2026-09-09, `openai-model-capabilities.md` §13.3). Today only a Java caller can walk into that
400; a yaml key hands it to every operator. And `AnthropicConfig` has no `reasoningEffort` field at all
(`:45-54`), so a deployment-wide effort would reach one provider and be silently dropped by the other —
which would make a *shared* key a lie for half its users. The three pieces are one change because each of
the other two is a precondition for shipping the first honestly.

---

## 2. K-1 — the shared namespace, and why that is not a contradiction of #54

A reviewer arriving from PR #65 will have just read a design that took the same criterion and split the
other way. It is the same criterion; the input differs, and the criterion is doing its job in both
directions.

`docs/design/llm/model-capability-config-key.md` §2.7 states it as two tests, and a key goes to
`llm.<provider>.*` when **either** answers yes:

| | does the **name** carry a vendor concept? | does the **meaning** differ per vendor? | lands |
|---|---|---|---|
| `thinkingMode` (#54) | **yes** — "thinking" is Anthropic's word for what this codebase calls `ReasoningEffort` / `ReasoningTrace` | no | vendor |
| `thinkingBudgetTokens` (#54) | **yes** — `budget_tokens` is a literal request-body field | **yes** — no other vendor expresses effort as a token count | vendor |
| `replayThinkingBlocks` (#54) | **yes** — a "thinking block" is a signed content block on that wire | no | vendor |
| **`reasoningEffort` (this round)** | **no** — it is the name of the neutral SPI type, `at.aimon.core.llm.ReasoningEffort`, which both provider modules already import and translate | **no** — "how hard should this model think" is the same question of both vendors; `OpenAiReasoningEfforts.toWire` and `AnthropicThinkingBudgets.requestedBudget` are two translations *of one intent*, which is exactly what a neutral enum is for | **shared** |

Three further checks, because a rule that only ever says one thing is not being applied:

- **The precedent #46 set for the shared namespace is met here in full.** §2.7 accepted
  `model-capabilities` as shared on the strength of "a second consumer is coming"; #52 turned that
  prediction into a fact, and the note appended to §2.7 records that what makes a shared key honest is
  **both branches actually reading it**. `reasoningEffort` arrives with both consumers already
  present — `OpenAiRequestParameters.requestedEffort` today, `AnthropicConfig.reasoningEffort` in this
  round (§5) — so it does not need the refusal guard that a one-branch shared key needed.
- **The starter's own ArchUnit rule agrees.** `noPropertiesSignatureNamesAVendorType`
  (`AimonAutoConfigurationTest:359`) allows `at.aimon.core.` and denies `at.aimon.core.llms.`.
  `ReasoningEffort` is `at.aimon.core.llm.ReasoningEffort` — allowed. That is the mechanical form of the
  same test, and it means the starter can bind **the enum itself** rather than a `String`, which is what
  #54 could not do (`aimon-llm-anthropic` is `compileOnly` there and `AnthropicThinkingMode` would be a
  `NoClassDefFoundError` waiting for the first operator to write the key). §6.2.
- **Symmetry is not an argument.** "#54 went to the vendor namespace, so this one should too" is the
  reasoning §2.7 exists to displace; so is its mirror. The criterion is about the key, not about the
  round it ships in.

`reasoning-model-enablement.md` D-6 had already reached this answer for this key; §2 is the working, not
a new decision.

---

## 3. K-2 / K-3 / K-4 — `lowestReasoningEffort` becomes a rung set

### 3.1 Why a floor cannot be patched

Terra's measured ladder is `none`, `low`, `medium`, `high`, `xhigh`, `max` — with `minimal` missing
(`openai-model-capabilities.md` §13.3; all seven rungs were sent individually, which makes it the one
fully exercised ladder in that table). A floor names a single boundary and asserts everything above it.
There is no floor value for a ladder with a gap in the middle:

| floor | what the framework then does on terra | verdict |
|---|---|---|
| `NONE` | `MINIMAL` passes the gate → `"effort":"minimal"` → **400** | fails the turn |
| `MINIMAL` (today) | same 400, **and** withholds `none`, which terra accepts | fails the turn and misdescribes |
| `LOW` | avoids the 400, still withholds `none`, still says terra has no `minimal` *because it starts at low* | correct outcome, false statement |

The third is the tempting one and it is the reason to change the shape rather than the value: it buys the
right behaviour by writing down something untrue, and the next person to read the row learns a wrong fact
about the model.

### 3.2 The shape

One field on `ModelCapabilities`, replacing one field:

```java
/** The rungs this model accepts as a value. Never empty; iteration order is the ladder's. */
public Set<ReasoningEffort> acceptedReasoningEfforts()
```

- Stored as an `EnumSet` copied defensively at build time and handed out through
  `Collections.unmodifiableSet`, so the descriptor stays immutable and iteration follows the enum's
  declaration order — which `ReasoningEffort`'s javadoc already declares load-bearing ("the constants are
  declared in ascending order of effort"). That order is what makes a warning message that prints the set
  read as a ladder rather than as a bag.
- **Fail-open value is byte-identical to today's**: `DEFAULT_ACCEPTED_REASONING_EFFORTS =
  EnumSet.range(MINIMAL, HIGH)` — i.e. `{MINIMAL, LOW, MEDIUM, HIGH}`, which is exactly what the floor
  `MINIMAL` meant. A model no registry describes is unchanged, and `ModelCapabilitiesTest`'s
  per-flag assertions on `unknown()` become one set assertion with the same content.
- **Empty is refused**, at every entry point (`ModelCapabilities.Builder`,
  `ModelCapabilityDeclaration.Builder`). "This model takes the reasoning-effort parameter but accepts no
  value for it" is not a state; the thing being described there is `supportsReasoningEffort: false`, and
  the refusal message says so.
- **`null` elements refused**, `NullPointerException`, as every other setter in the type does.

The builder keeps **two** ways in, writing the same field:

```java
Builder.acceptedReasoningEfforts(Set<ReasoningEffort> rungs)  // the general form
Builder.lowestReasoningEffort(ReasoningEffort lowest)         // shorthand: EnumSet.range(lowest, HIGH)
```

Both are setters for one field, so the last call wins, and the javadoc says so. That is how every other
setter on this builder behaves; inventing an order-sensitive refusal for one field would be a rule the
type does not otherwise have. (Configuration is the opposite case and is refused — §3.5. The two
differ because a Java caller writes a *sequence*, where "the last statement" is an unambiguous answer,
while a yaml file presents both keys at once with no order at all.)

Keeping the floor **as an input** is the whole of the compatibility story and it is honest: every row and
every declaration that uses it means precisely what it meant before — "the ladder starts here and has no
holes" — and every one of them is still true. What is deleted is the *reader*:

> **`ModelCapabilities.lowestReasoningEffort()` is removed.** Not deprecated, not left returning
> `min(set)`. A caller who has a floor writes `effort.compareTo(floor) >= 0`, which is precisely the
> expression that put `"effort":"minimal"` on terra's wire. Leaving a getter that invites it is leaving
> the bug reachable behind a new name for the field.

Rejected micro-alternative: a convenience `boolean accepts(ReasoningEffort)` beside the set. One call
site does not pay for a second way to ask the same question; `capabilities.acceptedReasoningEfforts()
.contains(effort)` is the whole of `maySendEffort`'s new body.

### 3.3 What happens to the published `0.x` SPI

`at.aimon.core.llm.capability` is a published package
(`docs/project/api-stability.md` §2). The change is source-breaking and §5 permits it,
with the note that this repository does not ship deprecation adapters in `0.x` — *"이름을 바꿀 때 어댑터를
남기는 대신 한 번에 옮겨 왔기 때문이며, `0.x` 에서는 그쪽이 정직하다"*. So: one commit, no adapter.

| type | removed | added | kept |
|---|---|---|---|
| `ModelCapabilities` | `lowestReasoningEffort()` | `acceptedReasoningEfforts()`; `Builder.acceptedReasoningEfforts(Set)` | `Builder.lowestReasoningEffort(ReasoningEffort)` — same signature, now shorthand |
| `ModelCapabilityDeclaration` | — | `Optional<Set<ReasoningEffort>> acceptedReasoningEfforts()`; `Builder.acceptedReasoningEfforts(Set)` | `Optional<ReasoningEffort> lowestReasoningEffort()`, `Builder.lowestReasoningEffort(...)` |
| `equals` / `hashCode` / `toString` | — | set-valued | shape unchanged |

An out-of-tree caller that reads the floor **fails to compile**, which is the outcome to want: the silent
alternative would be a getter that keeps answering while the model underneath it has a hole.

`docs/migration/rename-maps.md` gains a row under a new short section for this round, in the form that
file uses (old → new, plus the sentence naming why the old name was wrong):
`ModelCapabilities.lowestReasoningEffort()` → `ModelCapabilities.acceptedReasoningEfforts()`, *"a floor
is a boundary; the thing being described is a set, and one member of terra's is missing from the middle
of it"*. Degradation-key precedent from #54 says a value change belongs in the same row; here only the
Java name changes.

### 3.4 What every existing caller does

Exhaustive, from `grep -rl lowestReasoningEffort` on 2026-09-10 (33 files; the ones that are prose are in
§12).

**Readers of the getter — four in code, one in javadoc:**

| site | now | after |
|---|---|---|
| `OpenAiRequestParameters.maySendEffort` (`:162-171`) | `effort.compareTo(lowest) >= 0` | `capabilities.acceptedReasoningEfforts().contains(effort)`. The one judgement point both endpoints share, and it stays exactly that |
| `InMemoryModelCapabilityRegistryTest.oSeriesLadder…` (`:66-84`) | four floor assertions | set assertions, plus terra's own row (§4.4) |
| `ModelCapabilitiesTest` (fail-open, builder defaults) | floor equals `MINIMAL` | set equals `EnumSet.range(MINIMAL, HIGH)`; the "builder defaults equal `unknown()`" assertion is untouched in shape |
| `ModelCapabilityDeclarationTest` | floor round-trip | floor round-trip **and** set round-trip, plus the mutual-exclusion refusal |
| `AnthropicLlmClient:553` `{@link ModelCapabilities#lowestReasoningEffort()}` in `resolveDialect`'s javadoc | a link to a method that will not exist | re-pointed to `acceptedReasoningEfforts()`. **This one is load-bearing for the build**, not cosmetic: a dangling `@link` fails doclint. The sentence around it — *"omitted and reported, never raised"* — is still true and stays |

**Writers of the setter:**

| site | after |
|---|---|
| `InMemoryModelCapabilityRegistry.oSeries(boolean)` (`:~270`) | unchanged — `lowestReasoningEffort(LOW)` still says exactly what the o-series ladder is (`{LOW, MEDIUM, HIGH}`, no hole), and spelling it as a set would be longer and no truer |
| the `gpt-5` prefix row | unchanged, and **still unset** — the row leaves the field at the fail-open value, which the existing comment argues for explicitly and which measurement still supports for `gpt-5`/`gpt-5-nano` (`'minimal','low','medium','high'`). The hole belongs to terra, not to the family |
| `gpt-5-chat`, the three `o*` prefixes, the seven `claude-*` rows | unchanged; none states the field |
| `ModelCapabilityDeclaration.resolve(Builder)` | calls whichever of the two the operator wrote |
| **new**: `register("gpt-5.6-terra", …)` | `acceptedReasoningEfforts(EnumSet.of(NONE, LOW, MEDIUM, HIGH))` — §4 |

**Not a caller, and worth stating because a reader will ask:** `aimon-llm-anthropic` never consults the
ladder. Anthropic's request surface expresses effort as a token budget, and
`AnthropicThinkingBudgets.requestedBudget` already floors at 1024; the rungs are an OpenAI-shaped fact
about a request parameter that Anthropic does not have. Teaching the Anthropic client to read the set is
a non-goal (§14), and the two occurrences of the name in that module are both javadoc.

### 3.5 What every declared row does

A declared row is what an operator writes; three rules govern it and none of them is new.

**Both keys exist on both surfaces.** `lowestReasoningEffort` stays (K-4: renaming it would be a
*silent* loss on the starter, which is the failure mode this whole key family exists to remove), and
`acceptedReasoningEfforts` joins it. A row with a hole is now describable from configuration, which
matters more than it looks: the built-in table gains a row for terra, and an operator whose gateway
*renames* terra needs to be able to say the same thing about their own name. A built-in table that can
express something the configuration surface cannot is the shape §2.7 warned about from the other side.

**Writing both is refused, once, in the neutral type.**
`ModelCapabilityDeclaration.Builder.build()` already refuses the declaration that states nothing; it
gains the sibling refusal, with a message naming both keys and saying which to keep:

> `Model capability declaration states both lowestReasoningEffort and acceptedReasoningEfforts, and they
> describe the same fact two ways. Keep acceptedReasoningEfforts if this model's ladder has a gap (e.g.
> [none, low, medium, high]); keep lowestReasoningEffort if it starts at one rung and runs to the top.`

It lives there and not on either surface for the reason that type's javadoc already gives: *"the rules
governing a declaration have one implementation rather than one per surface: two copies of those rules
drift, and the drift is silent."* Both surfaces re-throw it with their own key path on it —
`ConfigurationException("Invalid \`llm.modelCapabilities.<name>\` …")` in the CLI (`LlmClientFactory
.declarationOf`), `IllegalStateException("aimon.llm.model-capabilities.<name> is invalid: …")` in the
starter (`AimonProperties.declarationOf`) — which is the shape both already use.

**An empty list is refused, not ignored.** `acceptedReasoningEfforts: []` binds to an empty set, which
is a thing an operator can write and which means nothing; it is refused by the message in §3.2. The
distinction that makes this reachable is that the field is left `null` when the key is absent — see
§15 U-2 for the one binder behaviour that assumption rests on and the test that measures it.

**A duplicate rung is folded silently.** `[low, low, high]` is a `Set`; there is no reading of a repeated
rung that differs from the single one, and refusing it would be a message about nothing.

---

## 4. K-5 — exact-shadows-prefix

### 4.1 The promise that is in the way

`InMemoryModelCapabilityRegistry.builderWithDefaults()`'s javadoc holds up overriding the `gpt-5` prefix
as *the* worked example of a position-safe override, and the class javadoc closes with:

> *It is also why there is no exact row for any `gpt-5*` name: one would disable the
> `registerPrefix("gpt-5", ...)` override `builderWithDefaults()` documents, for the very name that
> override is demonstrated with.*

`builderWithDefaultsOverridesAPrefixInPlace` (`InMemoryModelCapabilityRegistryTest:342-352`) pins it, and
the backlog's three-row table proves the blockage is not a matter of picking `register` over
`registerPrefix`: an exact row loses to nothing, a terra prefix registered *before* `gpt-5` survives the
later in-place re-put (which is the very property the javadoc promises), and one registered *after* is
unreachable. **Every** name-level row breaks it.

### 4.2 The answer: the promise is too strong, and the table already knows it

The blockage is not in the registry's shape. It is in a promise made for one family that the registry
does not make anywhere else — and the counter-example is eight rows further down the same method.

`builderWithDefaults()` ships eight **exact** o-series rows that shadow three prefix rows, and the class
javadoc states the consequence plainly and without apology:

> *`builderWithDefaults().registerPrefix("o1", ...)` still reaches `o1-pro` and every future `o1*` name
> but **no longer reaches `o1` or `o1-2024-12-17`**, because the built-in exact rows shadow it. Override a
> measured name with `register(...)`, which displaces the built-in exact row — and so does a configured
> declaration for that name.*

So "an exact row shadows a prefix override for that one name, and the operator's own `register(...)` or
configured declaration displaces it" is **already the documented contract**, already tested, and already
lived with for eight names. What blocked terra was not the rule; it was that the `gpt-5` family had been
given a *stronger* promise — "every `gpt-5*` name, always" — whose only purpose was to keep one javadoc
example tidy, and which is now costing a shipped 400.

**Decision: register terra exactly, and narrow the promise to the one the registry can keep.**

```java
// Round 9, from round 8's measurement (openai-model-capabilities.md §13.3): terra's ladder is
// none / low / medium / high / xhigh / max -- it REJECTS 'minimal', which the family prefix's
// fail-open floor asserts it takes. Everything else about the row is the gpt-5 family row, stated
// once by the helper so the two cannot drift.
.register("gpt-5.6-terra", gpt5Family(EnumSet.of(NONE, LOW, MEDIUM, HIGH)))
```

- **Exact, not a prefix.** The OpenAI block's convention is exact rows for measured names and prefixes
  for families, and the round-8 probe measured this *name*: `gpt-5.6-luna` and `gpt-5.6-sol` were seen in
  the model listing and deliberately **not** called, which is why they have no row. A
  `registerPrefix("gpt-5.6-terra", …)` would additionally claim the fact for dated snapshots nobody has
  seen; the Anthropic block does exactly that on purpose, but it does it for names the vendor's own table
  enumerates. Nothing enumerates a terra snapshot. The cost is stated in the row's comment: a
  `gpt-5.6-terra-2026-…` name, if one ever appears, lands on the `gpt-5` prefix and inherits the wrong
  ladder until someone measures it or declares it.
- **`xhigh` and `max` are not in the row**, and that is not an omission. `ReasoningEffort` stops at
  `HIGH` by an explicit decision its own javadoc records (the common subset that survives translation to
  a second provider). The set states the four rungs the neutral vocabulary has; the comment says the
  model has two more.
- **The row restates the family's four flags**, because a registration is a whole row and not a patch.
  To keep the two from drifting, `gpt5Family(Set<ReasoningEffort>)` is extracted exactly as
  `oSeries(boolean)` already is, and the prefix row is built from it too — so "terra is the gpt-5 row
  with a different ladder" is a fact of the source rather than of two copies staying in step. In
  particular `supportsReasoningTraceRoundTrip` stays `true`, without which terra would silently leave the
  `/v1/responses` route and half of `OpenAILlmClientEndpointSelectionTest` would change meaning.

### 4.3 The javadoc and the test, precisely

Both are named in the acceptance criteria as blocking if left stale. What each becomes:

**`builderWithDefaults()`'s javadoc** keeps the override recipe — the recipe is not what broke — and the
class javadoc's closing sentence ("*it is also why there is no exact row for any `gpt-5*` name*") is
replaced by the narrowed promise, worded as its `o1` sibling already is:

> `builderWithDefaults().registerPrefix("gpt-5", …)` reaches `gpt-5-mini`, `gpt-5-nano` and every future
> `gpt-5*` name, but **not `gpt-5.6-terra`**, whose measured ladder has an exact row of its own — the same
> shadowing the o-series rows produce for `o1` and `o1-2024-12-17`, and with the same remedy:
> `register("gpt-5.6-terra", …)`, or a configured declaration for that name, displaces the built-in row.

**`builderWithDefaultsOverridesAPrefixInPlace`** keeps its subject — the `LinkedHashMap` in-place re-put,
i.e. that overriding `gpt-5` cannot jump ahead of `gpt-5-chat` — and stops demonstrating it on a name
that now has its own row:

```java
final InMemoryModelCapabilityRegistry registry = InMemoryModelCapabilityRegistry.builderWithDefaults()
        .registerPrefix("gpt-5", EVERYTHING_ALLOWED).build();

assertThat(registry.resolve("gpt-5-mini")).isEqualTo(EVERYTHING_ALLOWED);          // was gpt-5.6-terra
assertThat(registry.resolve("gpt-5-chat-latest")).isEqualTo(ModelCapabilities.unknown()); // unchanged
```

and gains a second test — a sibling rather than more assertions in this one, because it pins a different
promise — named for what it pins:

```java
@DisplayName("an exact row shadows a prefix override for its own name, and register() takes it back")
void anExactRowShadowsThePrefixOverrideAndIsItselfOverridable() { … }
```

which asserts, in order: the `gpt-5` override does **not** reach `gpt-5.6-terra`; terra still resolves to
the built-in row with the hole in its ladder; and `builderWithDefaults().register("gpt-5.6-terra",
EVERYTHING_ALLOWED)` **does** reach it. The third assertion is the one that matters — it is the escape
hatch the narrowed promise now depends on, and it is the same call a configured declaration makes through
`withDefaultsExtendedBy`.

### 4.4 The stand-in hazard — the part that would otherwise stay green

The backlog is explicit that terra is this repository's stand-in for the `gpt-5` row *in two modules*, and
that a name-level row would "덤으로 `gpt-5` 행 이름을 단 테스트들을 **초록인 채로** 다른 행의 테스트로 바꿔
놓는다". That is the real cost of K-5 and it is paid by hand, in this change, not deferred.

Two tests go **red** on their own, which is the pleasant half:

| test | why it fails | what it becomes |
|---|---|---|
| `OpenAIResponsesRequestFactoryTest.effortBelowTheLadderIsOmitted` (`:118-127`) | its `GPT5` fixture is `resolve("gpt-5.6-terra")`; `NONE` **is** on terra's ladder now, so nothing is omitted and nothing is reported | the fixture becomes `resolve("gpt-5-mini")` and the assertion stands unchanged. A **new** case keeps terra: `NONE` on the terra row reaches the wire as `"effort":"none"` with no divergence — the half of the row that was wrong in the *other* direction, and the only place it is observable |
| `OpenAIResponsesRequestFactoryTest.minimalIsOnGpt5sLadderButNotTheOSeriesOne` (`:129-156`) | its `gpt-5` half asserts `minimal` reaches the wire; on the terra row it no longer does | same fixture change; the long comment explaining that the row is *knowingly wrong for terra* is deleted, because it is not any more, and a line in its place says where the fact now lives |

The rest stay green while quietly changing subject. They are re-pointed mechanically, one constant per
class, because a test whose name says "gpt-5 family" and whose body resolves a name with its own row is a
test nobody can read:

- `OpenAIResponsesRequestFactoryTest` — `GPT5` fixture and the model-name literal passed to `build(...)`.
- `OpenAILlmClientModelCapabilityTest` — fifteen occurrences; all of them mean "a gpt-5-family reasoning
  model" and become `gpt-5-mini`, except `withAnEmptyRegistryEvenALiteralGpt56TerraGetsTheUntouchedRequest`
  (`:452`), which means "the client holds no model-name knowledge of its own" and keeps the literal —
  that is the one case where the *name* is the point.
- `OpenAILlmClientEndpointSelectionTest`, `OpenAILlmClientParameterDivergenceTest`,
  `OpenAIResponsesParameterDivergenceTest`, `OpenAIResponses{ContentBlock,ErrorEvent,Usage,Cancellation,
  RequestTimeout,ExceptionRouting,ToolArgumentParity,ReasoningRoundTrip}Test`, `OpenAIConfigTest` — same
  rule; the name is incidental in all of them.
- `InMemoryModelCapabilityRegistryTest` — five occurrences. Two are *about* prefix matching on an
  arbitrary string (`prefixMatchingIsCaseInsensitive`, which registers its own `GPT-5` prefix into an
  empty builder) and stay. Three assert the built-in `gpt-5` row and move to `gpt-5-mini`. One new
  assertion in `aDeclaredEntryIsAddedAndTheBuiltInRowsAreAllStillThere` covers the terra row, so the
  "one name per built-in row" list that comment insists on keeps its property.
- `AimonPropertiesValidationTest`, `AimonAutoConfigurationTest`, `LlmClientFactoryTest` — terra appears
  as a model name in configuration fixtures; incidental, re-pointed for consistency of meaning.

`docs/design/llm/openai-model-capabilities.md` §12.2 and §13.4 and the backlog item quote terra as the
example of the defect; those are design-time records and are handled in §12, not rewritten.

---

## 5. K-6 / K-7 — `AnthropicConfig.reasoningEffort`

### 5.1 The field

`AnthropicConfig` gains `reasoningEffort`, in the shape `OpenAIConfig` already has it (`:52`, `:179`,
`:339`): a nullable field, `Optional<ReasoningEffort> getReasoningEffort()`, a builder setter that
accepts `null`, and no validation — every enum value is legal, and what a model does with one is the
client's business to report, not the config's to refuse. It joins `equals`/`hashCode`/`toString`, which
matters because `LlmClientFactoryTest` pins "an empty block builds a config equal to no block at all"
through `AnthropicConfig.equals` (#54's compatibility claim), and a field left out of `equals` would make
that identity say less than it says.

### 5.2 One helper, because there are two read sites

`AnthropicLlmClient` reads the call's effort in **two** places, and this is the part that is easy to get
half right:

| site | what it does | if only the other one is changed |
|---|---|---|
| `resolveThinking:519` | decides whether the request carries thinking at all (`NONE` → nothing) and feeds `resolveExtendedThinking` | a config-level `NONE` would not turn thinking off |
| `intendedEffort:648` | supplies the rung named in the dialect-translation WARN (#60's mode x dialect table) | the warning would name "no rung" while the gate acted on one |

So the precedence goes in one private helper and both call it:

```java
/** The effort somebody configured for this request: the LlmModel first, then the client config. */
private Optional<ReasoningEffort> requestedEffort(LlmModel modelConfig) {
    return modelConfig.getReasoningEffort().or(config::getReasoningEffort);
}
```

`intendedEffort` keeps its own rule on top — an explicit `thinkingBudgetTokens` still wins over any rung,
which its javadoc argues for and which this change does not touch — and delegates the rest.

This mirrors `OpenAiRequestParameters.requestedEffort(modelConfig, config)` (`:106-115`) line for line
without sharing code with it. Two lines in two modules over two unrelated config types is not worth a
core abstraction taking a `Supplier`; what makes the duplication safe is that the acceptance criterion
demands a test **on both providers** asserting the same precedence, and those two tests are the thing that
notices if one drifts. §11.

### 5.3 The inert-effort warning, and why it is not a refusal

With the field added, `aimon.llm.reasoning-effort: high` on `provider=anthropic` reaches the client and,
under the shipped default `thinkingMode: OFF`, **does nothing**: `resolveThinking` returns empty before it
ever reads the effort. That is "configured and never read", which this repository refuses everywhere
else, and shipping the shared key without noticing it would make §2's claim — the key means the same
thing on both providers — true only in the letter.

Three dispositions were weighed:

| | |
|---|---|
| **silence** | what happens today for an `LlmModel`-level effort. Rejected: this round is what makes the value settable deployment-wide, so this round is where the silence becomes a defect worth a line |
| **refuse at startup** | rejected. `OFF` is the shipped default (D-5, and #60 declined to move it), so a perfectly reasonable operator action — "make it think harder" — would fail the boot of a deployment that has not read the changelog, and the fix (`llm.anthropic.thinkingMode: auto`) is a *second* key. Refusing at boot for a combination whose remedy is another key is the shape `L-3` warns turns valid configuration into a boot failure |
| **one WARN, once per signature** (chosen) | the instrument this client already owns, and the exact counterpart of `OpenAiRequestParameters.reportUnsupportedEffort` on the other provider — *"an effort is set but this model takes no reasoning-effort parameter; it is being omitted"* |

Emitted from `resolveThinking`, before the `OFF` early return, when an effort is present and the mode is
`OFF`; signature `reasoningEffortWithThinkingOff=<effort>` so it is said once per process:

> `reasoningEffort {} is configured but thinkingMode is off, so this request carries no thinking
> parameter and the effort reaches nothing. Set llm.anthropic.thinkingMode (auto, adaptive or extended)
> to act on it — note that an always-on model may still think regardless.`

The trailing clause is not decoration: `resolveThinking`'s own javadoc already records that on a model
where thinking is on by default, `NONE` does not turn it off, and a warning that implied otherwise would
be the third variant of failure mode 3 in `reasoning-model-enablement.md` §7.

**One deliberate behaviour change falls out of this**: a deployment that sets `model.reasoningEffort` in
an agent definition today, with the default mode, gets a new WARN line. The rule is one rule — where the
value came from does not change whether it reached anything — and making the warning depend on the source
would be the asymmetry this repository dislikes. CHANGELOG names it.

---

## 6. K-1 in the concrete — the three surfaces

### 6.1 CLI — `llm.reasoningEffort`

```yaml
llm:
  provider: "openai"
  model: "gpt-5.1"
  reasoningEffort: medium      # none | minimal | low | medium | high -- case-insensitive
```

- A `ReasoningEffort` field on `LlmProviderConfig`, beside `model` / `timeout` / `baseUrl`, bound with no
  `@JsonProperty` (the package's convention: the empty name, camelCase, and the Java field name copied
  letter for letter so no translation table exists).
- **No custom deserializer**, unlike `AnthropicProviderConfig.thinkingMode`. That exception was forced by
  one thing — `off` is a YAML 1.1 boolean and never arrives as a string — and none of `none`, `minimal`,
  `low`, `medium`, `high` collides with YAML 1.1's boolean or null resolvers. Case-insensitivity comes
  from `CliConfigLoader`'s `ACCEPT_CASE_INSENSITIVE_ENUMS` (`:38`), which the loader's own comment says
  "widens exactly one key" today: this makes it two, and that comment is part of the change.
- Applied in **both** branches of `LlmClientFactory` — `anthropicConfig(...)` and `openAiConfig(...)` —
  each guarded by `!= null` so a deployment that writes nothing is byte-identical, which is the
  compatibility claim #54 made and pinned.

### 6.2 Starter — `aimon.llm.reasoning-effort`

```properties
aimon.llm.reasoning-effort=medium
```

- `private ReasoningEffort reasoningEffort;` on `AimonProperties.Llm`, **the enum itself**, not a
  `String`. `ReasoningEffort` is in `aimon-core`, a hard dependency of the starter, so the
  `NoClassDefFoundError` reasoning behind #54's `String` does not apply and
  `noPropertiesSignatureNamesAVendorType` agrees (§2). Consequences worth naming: relaxed binding folds
  case for free, and the configuration processor records the type, so the IDE offers the five constants
  with **no** hand-written entry in `additional-spring-configuration-metadata.json` — the same trade
  `ModelCapabilityProperties.lowestReasoningEffort` documents. `AimonConfigurationMetadataTest` needs no
  new selector row for the same reason.
- A `public static final String LLM_REASONING_EFFORT = PREFIX + ".llm.reasoning-effort";` constant beside
  `LLM_MODEL`, so any message that names the key names it once.
- Applied in **both** guarded slices — `AnthropicConfiguration.anthropicConfig` and
  `OpenAiConfiguration.openAiConfig` — guarded by `!= null`.

### 6.3 Agent frontmatter — `model.reasoningEffort`

```yaml
---
name: deep-reviewer
model:
  name: gpt-5.1
  reasoningEffort: high
---
```

`MarkdownAgentDefinitionParser.extractModel` (`:142-165`) gains a fifth `containsKey` branch. It does
**not** use the neighbouring `extractInt` / `extractDouble` helpers' shape, and that is a deliberate
divergence worth stating: those two silently substitute their default when the value is the wrong type
(`temperature: "hot"` becomes `1.0` and nobody hears about it). The new key folds
`value.toString().trim()` over `ReasoningEffort.values()` ignoring case — the same tolerance
`CliConfigLoader:38` applies for the same reason — and throws `AgentDefinitionParseException` naming the
key and every accepted spelling when nothing matches. Criterion 5 says bad values fail loudly on *every*
surface; the fact that its two neighbours do not is a pre-existing defect this round records and does not
fix (§14, §16 O-3).

`requestTimeout`, `presencePenalty` and `frequencyPenalty` are also missing from that method and stay
missing — `reasoning-model-enablement.md` §4.4 and G-5, unchanged.

---

## 7. Criterion 5 — what a bad value does, per surface

| surface | bad *value* | bad *key name* |
|---|---|---|
| CLI `llm.reasoningEffort` | `ConfigurationException("Invalid configuration structure in: …")` wrapping Jackson's `InvalidFormatException`, which enumerates the accepted constants. The exact shape `lowestReasoningEffort` already produces and `CliConfigLoaderTest.rejectsAnUnusableReasoningEffort` already pins | `ConfigurationException` — `FAIL_ON_UNKNOWN_PROPERTIES` is on |
| starter `aimon.llm.reasoning-effort` | Boot `BindException` at startup naming the property and the failed conversion | **silent.** `llm-config-surface-open-items.md` `L-1`, widened by one key here, not closed — §12 |
| frontmatter `model.reasoningEffort` | `AgentDefinitionParseException` naming the key and the five spellings | silent — an unknown key under `model:` is ignored, which is that parser's standing behaviour for every key and not something this round changes |
| both `modelCapabilities` surfaces, `acceptedReasoningEfforts` | bad element → the surface's own conversion failure; empty list → the §3.2 refusal; both-keys → the §3.5 refusal, each re-thrown with its key path | CLI loud, starter silent (same `L-1`) |

---

## 8. Concrete changes, by module and file

*(2026-09-10)*

### `aimon-core`

| file | change |
|---|---|
| `llm/capability/ModelCapabilities.java` | field `lowestReasoningEffort` → `acceptedReasoningEfforts` (`Set<ReasoningEffort>`, `EnumSet` copy, unmodifiable view); `DEFAULT_ACCEPTED_REASONING_EFFORTS = EnumSet.range(MINIMAL, HIGH)`; getter removed and getter added; builder gains `acceptedReasoningEfforts(Set)` and keeps `lowestReasoningEffort(...)` as shorthand; empty/null refusals; `equals`/`hashCode`/`toString`; the `unknown()` javadoc paragraph about the floor rewritten — terra stops being the counter-example the default is *wrong about* and becomes the in-tree instance of a row that states its own set |
| `llm/capability/ModelCapabilityDeclaration.java` | second optional field + getter + builder setter; `declaresAnything()` counts it; `resolve(...)` applies whichever was written; `build()` gains the mutual-exclusion refusal and its message; the "states at least one of …" message gains the seventh name |
| `llm/capability/InMemoryModelCapabilityRegistry.java` | `gpt5Family(Set<ReasoningEffort>)` extracted; `register("gpt-5.6-terra", …)` added beside the `MEASURED_O_SERIES_NAMES` loop, with the round-8 citation; the `gpt-5` prefix comment's *"Known and deliberately unfixed"* paragraph replaced by what was done; the class javadoc's closing "no exact row for any `gpt-5*` name" sentence replaced by §4.3's narrowed promise; `withDefaults()`'s summary paragraph gains the eighth row |
| `agent/definition/parser/MarkdownAgentDefinitionParser.java` | `model.reasoningEffort` in `extractModel`, strict fold, `AgentDefinitionParseException` on a miss; the class-javadoc frontmatter example gains the key |

### `aimon-llm-openai`

| file | change |
|---|---|
| `OpenAiRequestParameters.java` | `maySendEffort` reads the set; `EFFORT_BELOW_LADDER_MESSAGE` becomes `EFFORT_OFF_LADDER_MESSAGE`, printing the model's accepted rungs; signature `reasoningEffortBelowLadder=` → `reasoningEffortOffLadder=`. The rename is not cosmetic — "below" is provably not the reason any more (`MINIMAL` on terra is below nothing), and the signature is package-private (`OpenAIDivergenceReporter` is not public), so it is internal apart from the log text, which the CHANGELOG names |
| `OpenAILlmClient.java`, `OpenAIResponsesRequestFactory.java` | comments only — both quote "the floor"; neither's logic moves |

### `aimon-llm-anthropic`

| file | change |
|---|---|
| `AnthropicConfig.java` | `reasoningEffort` field, getter, builder setter, `equals`/`hashCode`/`toString` |
| `AnthropicLlmClient.java` | `requestedEffort(LlmModel)` helper; `resolveThinking` and `intendedEffort` call it; the §5.3 warning; the `{@link ModelCapabilities#lowestReasoningEffort()}` javadoc reference re-pointed |

### `aimon-cli`

| file | change |
|---|---|
| `config/LlmProviderConfig.java` | `reasoningEffort` field + accessors + `equals`/`hashCode`/`toString` |
| `config/ModelCapabilityConfig.java` | `acceptedReasoningEfforts` field + accessors + `equals`/`hashCode`/`toString` |
| `config/CliConfigLoader.java` | the `ACCEPT_CASE_INSENSITIVE_ENUMS` comment's "exactly one key" becomes two, with the new one named |
| `factory/LlmClientFactory.java` | the key applied in both branches; `declarationOf` passes the second capability key through |
| `resources/default-config.yaml` | `reasoningEffort` in the commented `llm` block; `acceptedReasoningEfforts` as the commented alternative under the `modelCapabilities` example, with the one-line note that the two are mutually exclusive. Both stay commented — an uncommented default would change every CLI request |

### `aimon-spring-boot-starter`

| file | change |
|---|---|
| `AimonProperties.java` | `LLM_REASONING_EFFORT` constant; `Llm.reasoningEffort` (the enum); `ModelCapabilityProperties.acceptedReasoningEfforts` + `toDeclaration()` |
| `AimonLlmAutoConfiguration.java` | the key applied in both guarded slices |
| `additional-spring-configuration-metadata.json` | **no change** — the processor records an enum's type and the IDE reads the constants off it |

---

## 9. Data and interface shapes that change

```java
// at.aimon.core.llm.capability.ModelCapabilities  -- was: ReasoningEffort lowestReasoningEffort()
Set<ReasoningEffort> acceptedReasoningEfforts();            // never empty, ladder-ordered, unmodifiable
Builder acceptedReasoningEfforts(Set<ReasoningEffort>);     // new
Builder lowestReasoningEffort(ReasoningEffort);             // kept: shorthand for range(lowest, HIGH)

// at.aimon.core.llm.capability.ModelCapabilityDeclaration  -- purely additive
Optional<Set<ReasoningEffort>> acceptedReasoningEfforts();
Builder acceptedReasoningEfforts(Set<ReasoningEffort>);     // null = not declared, as every setter here

// at.aimon.core.llms.anthropic.AnthropicConfig  -- purely additive
Optional<ReasoningEffort> getReasoningEffort();
Builder reasoningEffort(ReasoningEffort);
```

Configuration surfaces, all additive except where §3.5 says otherwise:

| | CLI (camelCase) | starter (kebab) | frontmatter |
|---|---|---|---|
| the effort | `llm.reasoningEffort` | `aimon.llm.reasoning-effort` | `model.reasoningEffort` |
| a row's ladder, no hole | `llm.modelCapabilities.<m>.lowestReasoningEffort` *(unchanged)* | `aimon.llm.model-capabilities.<m>.lowest-reasoning-effort` *(unchanged)* | — |
| a row's ladder, with a hole | `…​.acceptedReasoningEfforts: [none, low, medium, high]` | `…​.accepted-reasoning-efforts=none,low,medium,high` | — |

The built-in table's observable output changes for exactly one name:

| `resolve(...)` | before | after |
|---|---|---|
| `gpt-5.6-terra` | the `gpt-5` prefix row, ladder `{MINIMAL, LOW, MEDIUM, HIGH}` | its own row, ladder `{NONE, LOW, MEDIUM, HIGH}`, four other flags identical |
| every other name | — | unchanged, and `withDefaults()` equality per row is asserted name by name |

---

## 10. Failure modes

| # | shape | disposition |
|---|---|---|
| 1 | `reasoningEffort: minimal` on terra — the 400 this round exists to remove | omitted, one WARN naming the rung and the ladder terra does have. The operator can read the remedy out of the message |
| 2 | `reasoningEffort: none` on `gpt-5-mini` | unchanged: omitted and reported, because `none` is genuinely off that ladder. The two cases now differ in the message rather than being the same sentence |
| 3 | An out-of-tree caller reads `lowestReasoningEffort()` | **compile error.** The intended outcome; a compatibility shim returning `min(set)` would keep the terra bug reachable through a new spelling |
| 4 | A declared row states both ladder keys | refused at startup, message names both keys and which to keep (§3.5) |
| 5 | `acceptedReasoningEfforts: []` | refused; the message points at `supportsReasoningEffort: false`, which is what an empty ladder actually means |
| 6 | A misspelled `aimon.llm.reasoning-effort` | **silent** — `L-1` in `llm-config-surface-open-items.md`, widened by one key and recorded, plus a limitation test (§11). The CLI throws for the same typo |
| 7 | A deployment-wide effort under Anthropic's default `thinkingMode: OFF` | one WARN (§5.3). Not a refusal, because the remedy is a second key and `OFF` is the shipped default |
| 8 | Both a config effort and an agent-definition effort | the `LlmModel` wins, silently, on **both** providers — matching how the sampling parameters already resolve. A warning for a precedence that worked as documented would be noise |
| 9 | A future `gpt-5.6-terra-<date>` snapshot | lands on the `gpt-5` prefix and inherits the wrong ladder. Stated in the row's comment; not guessed at, because nothing has seen such a name (§4.2) |
| 10 | Someone later overrides `registerPrefix("gpt-5", …)` and expects it to reach terra | it does not, and the class javadoc plus the new test say so, with the `register("gpt-5.6-terra", …)` remedy beside it (§4.3) |
| 11 | A registry row states a ladder for a model the Anthropic client serves | inert — that client never reads the set (§3.4). No warning: the field describes a request parameter that provider does not have, and a warning per Anthropic call about an unread capability flag would fire for `supportsToolsWithReasoning` too |

---

## 11. Test strategy

**`aimon-core`**

- `ModelCapabilitiesTest`: fail-open set equals `{MINIMAL, LOW, MEDIUM, HIGH}`; builder defaults still
  equal `unknown()`; `lowestReasoningEffort(LOW)` and `acceptedReasoningEfforts(range(LOW, HIGH))` build
  **equal** descriptors — the assertion that pins "shorthand" as a fact rather than a comment; empty set
  and null element refused; the returned set is unmodifiable and a mutation of the caller's set after
  `build()` does not reach the descriptor.
- `ModelCapabilityDeclarationTest`: each key round-trips; each resolves into `capabilities()`; both
  together refused with a message naming both; neither still counts as "declares nothing".
- `InMemoryModelCapabilityRegistryTest`:
  - terra resolves to a row whose ladder contains `NONE` and not `MINIMAL`, and whose other four flags
    equal the `gpt-5` prefix row's — one assertion, so "terra is the family row with a different ladder"
    cannot drift;
  - `builderWithDefaultsOverridesAPrefixInPlace` on `gpt-5-mini`, `gpt-5-chat-latest` unchanged;
  - the new sibling: the override does not reach terra, terra keeps its row, and
    `register("gpt-5.6-terra", …)` takes it back (§4.3);
  - a **configured declaration** for `gpt-5.6-terra` through `withDefaultsExtendedBy` displaces the
    built-in row — the escape hatch as an operator reaches it, not just as a Java caller does;
  - the "one name per built-in row" list gains terra.
- `MarkdownAgentDefinitionParserTest`: `reasoningEffort: high` binds; `HIGH` / `High` / `high` all bind;
  an unknown value throws `AgentDefinitionParseException` whose message names the key and the five
  spellings; a definition with no `reasoningEffort` produces an `LlmModel` **equal** to today's.

**`aimon-llm-openai`**

- `OpenAiRequestParameters` / factory tests: a rung in the set reaches the wire; a rung outside it is
  omitted and reported once with the new signature; terra's `NONE` reaches the wire (the second half of
  the fixed row, and the only place it is observable);
- the fixture re-pointing of §4.4, which is mechanical but is where the change is most likely to leave
  something green and meaningless — the review checklist item is "no test whose display name says
  gpt-5 resolves a name with its own row".

**`aimon-llm-anthropic`**

- **precedence, provider one of two:** `AnthropicConfig.reasoningEffort(HIGH)` with an `LlmModel` that
  sets none produces the same `thinking` parameter as an `LlmModel` that sets `HIGH` with an unset
  config; an `LlmModel` effort beats a config one; a config `NONE` suppresses thinking exactly as a model
  `NONE` does — that last one is the assertion that fails if only `resolveThinking` were changed and
  `intendedEffort` were not, or vice versa;
- the §5.3 WARN fires once for `effort + OFF`, and does **not** fire when no effort is configured.

**`aimon-llm-openai`, the same precedence** — `OpenAIConfig.reasoningEffort` vs `LlmModel`, asserted in
the same shape so the pair reads as one claim about the framework rather than two about two clients. This
is the acceptance criterion's "pin the precedence with a test on both providers", and the point of
writing them alike is that the duplication in §5.2 is then visible to whoever breaks it.

**`aimon-cli`** — `CliConfigLoaderTest`: `llm.reasoningEffort` binds, folds case, and rejects `mediumish`
with `ConfigurationException`; a misspelled `reasoningEffor` is rejected too. `LlmClientFactoryTest`: the
key reaches `anthropicConfig(...)` **and** `openAiConfig(...)`, through the existing package-private
accessors; and `acceptedReasoningEfforts` reaches the registry, asserted by resolving the declared name.

**`aimon-spring-boot-starter`** — `AimonAutoConfigurationTest`: the property reaches both branches;
relaxed binding accepts `HIGH` and `high`; a bad value fails the context. `AimonPropertiesValidationTest`
gains `aMisspelledReasoningEffortIsSilentInTheStarter`, written in the same "limitation record, not a
guarantee" form as its two siblings — it pins a **third** binder shape (a scalar leaf on a bean that binds
regardless of this key, where the two existing tests pin a map-of-object leaf and a nested-object leaf),
and it goes red with the other two when someone closes `L-1`. `AimonDocumentedPropertiesTest` is an
existing gate and will fail until the guides carry the key (§12).

**Not added:** any test that calls a vendor API. Nothing here adds a billed call to `./gradlew test`;
`checkAll` is the gate.

---

## 12. Documentation, backlog, translations

**`CHANGELOG.md`, under `## [Unreleased]`**, one entry, naming in this order: the three surfaces; the
`0.x` SPI shape change with the removed method spelled out and the reason a floor was the wrong reader;
the terra row and the narrowed `gpt-5` override promise, with the `register(...)` remedy; the Anthropic
field and the new WARN under `thinkingMode: OFF`; and the divergence-signature rename.

**`docs/migration/rename-maps.md`** — a short section for this round with the one row from §3.3.

**`docs/backlog/openai-model-capabilities-open-items.md` `L-1` → closed** (criterion 6). Per
`docs/backlog/README.md` rules two and three, the closure records more than the
fact of closing:

- the trigger fired exactly as written — the grep in the item body stops returning zero, and that is the
  verification, not a claim;
- **the premise held.** Both prerequisites were real and neither could be split off: the rung set alone
  has nowhere to land, and the exact-row answer alone fixes nothing;
- **the severity was as recorded, and the shape of the fix differed in one place.** The item's blocking
  table concluded *"no shape in today's registry fixes terra without disabling the family's documented
  override"*, which is true and is not what was done: the registry's shape was not the thing that had to
  change — the promise was, and the registry already made the weaker, keepable version of it for `o1`
  eight rows away. Recording that is the point of rule two; the next person to read the table should not
  re-derive a blockage that dissolved when someone asked which of the two claims was load-bearing;
- **what was not re-measured**: the item says the round-8 key was rotated. Terra's ladder is not
  re-measured here; the row ships on the 2026-09-09 probe, and `gpt-5.6-luna` / `gpt-5.6-sol` remain
  uncalled and therefore unrowed. An empty cell, not a task.

**`docs/backlog/llm-config-surface-open-items.md` `L-1` → widened, not closed** (criterion 7). One
paragraph in the form #54's already uses: a fourth key, `aimon.llm.reasoning-effort`, plus
`accepted-reasoning-efforts` on the existing map entries; the cause is unchanged
(`ignoreUnknownFields = true`); the CLI still throws; the third limitation test is named; and the "when to
look again" trigger 2 still does **not** fire, because a scalar leaf is not a second map-of-object key.
The two items are in different files and only one of them is closed — the change must not blur them.

**Design records** — none of these is rewritten to match the code:

- `docs/design/llm/openai-model-capabilities.md` §13.4 gains a **pointer line** at its end saying the fix
  landed, in what shape, and where the reasoning is. Its body — including the three-row table and *"a fix
  deferred"* — stays as the design-time record it declares itself to be. Same treatment
  `anthropic-sampling-capabilities.md` §14 got in #54.
- `docs/design/llm/openai-responses-path.md` §7 **F-2**: a correction rather than a tick. F-2 enumerates
  *three* programmatic-only knobs — the registry override, `responsesApiEnabled`, the sampling parameters
  — and `reasoningEffort` **is not among them**; `grep -n "reasoningEffort" ` on that file returns one
  line and it is an SDK mapping table. So the issue body's phrase "the second of the two knobs F-2 left
  programmatic-only" is inaccurate, and the honest edit is one line under F-2 recording that a *fourth*
  knob on this path, which F-2 never counted, got its surface in #61 — so a reader counting what is left
  gets the right number. `L-2` (the two that really are F-2's) is untouched and stays open.
- `docs/design/llm/reasoning-model-enablement.md` — **not rewritten** (TASK.md forbids it). Departures go
  to `build/deviations.md`; the ones already visible from here are listed in §16.
- **This document** published at `docs/design/llm/reasoning-effort-config-surface.md`, with a row in
  `docs/design/README.md` beside `anthropic-thinking-config-surface.md`. Rejected alternative: fold §3
  and §4 into the backlog closure. Rejected because a closure note is read by whoever opens that item,
  while the two decisions here — a published SPI's shape, and a narrowed promise about override
  precedence — will be met by people reading the *registry*, and §13.4's "recorded so the follow-up does
  not restart from scratch" deserves a destination of the same kind.

**Operator documentation, and its translations.** Both pairs are Korean-canonical with `.en.md`
translations carrying `translated_from` / `source_commit` (both at `31e1c71` today):

| pair | what changes |
|---|---|
| `docs/getting-started/aimon-core-integration-via-cli-reference.{md,en.md}` | the `llm` key table gains `reasoningEffort`; the `modelCapabilities` flag table (`:252` / `:261`) gains `acceptedReasoningEfforts` and its `lowestReasoningEffort` row's description stops calling it a floor the framework reads; the "what is not silently ignored" sentence gains the both-keys refusal |
| `docs/getting-started/embedding-agent-in-application.{md,en.md}` | the starter property list gains `aimon.llm.reasoning-effort`; the five-flag sentence (`:385` / `:405`) becomes six |

Both pairs are updated **in the same commit**, with `source_commit` set to the canonical file's commit
immediately before this change; `check-translation-staleness.py` skips a commit that touches both sides,
so that one-commit lag is not staleness. Run `check-translation-structure.py` (heading, fence, table-row,
list-item, quote-block counts must match) and `check-doc-links.py` (no heading is renamed here, but the
new design document adds anchors). `AimonDocumentedPropertiesTest` independently fails until the starter
key appears in a guide, which is the machine half of this obligation.

---

## 13. Alternatives rejected

| # | alternative | why not |
|---|---|---|
| **A1** | **Ship the key and leave `L-1` open** | The issue's own answer: it knowingly ships the 400 the backlog was written to predict, to the exact population it predicted. The backlog names this round as its trigger |
| **A2** | **Keep the floor and add a second field for the holes** (`lowestReasoningEffort` + `excludedRungs`) | Two fields describing one fact, able to disagree, and every reader has to combine them correctly to get the right answer. The set *is* the combination |
| **A3** | **Keep `lowestReasoningEffort()` as a derived `min(set)`** | Source-compatible and quietly wrong: the expression it invites (`effort.compareTo(floor) >= 0`) is exactly the one that put `"effort":"minimal"` on terra's wire. A compile error is the better outcome (failure mode 3) |
| **A4** | **Rename the config key to match the new field** | The starter ignores an unknown property in silence, so every deployment that had declared `lowest-reasoning-effort` would lose it without a word — reproducing `L-1`'s failure mode inside the fix for a different one. K-4 |
| **A5** | **Fix terra by narrowing the `gpt-5` prefix row to the family intersection** (`{LOW, MEDIUM, HIGH}`) | No name-level row needed, and the javadoc example survives untouched — but it withholds `minimal` from `gpt-5`, `gpt-5-mini` and `gpt-5-nano`, which measurably accept it, to fix one sibling. It also states a ladder no model has. Degrading the majority to describe the minority inverts the row's own rule that a row states a measured fact about the names it matches |
| **A6** | **A `registerPrefix("gpt-5.6-terra", …)` before `gpt-5`** | Breaks the same promise (the backlog's table row two) and additionally claims the ladder for dated snapshots nobody has seen — the claim round 8 explicitly declined to make for `luna` and `sol`. §4.2 |
| **A7** | **Change the look-up so a later prefix registration can beat an exact row** | Would fix terra by breaking the o-series design on purpose, and "exact beats prefix" is the rule every configured declaration depends on to win |
| **A8** | **Refuse a `reasoningEffort` set under `thinkingMode: OFF`** | Fails the boot of a reasonable configuration whose remedy is a *second* key, with `OFF` as the shipped default. `L-3`'s warning about checks that turn valid configuration into a boot failure. §5.3 |
| **A9** | **A shared core helper for the `LlmModel`-then-config precedence** | Two lines over two unrelated config types in two modules; the abstraction would need a `Supplier` and would be read by two callers. The paired tests are the cheaper guard against drift. §5.2 |
| **A10** | **Bind the starter key as a `String` and fold by hand, as #54 does** | That shape exists to survive a `compileOnly` vendor class being absent. `ReasoningEffort` is core and always present, so the `String` would cost the IDE completion and the metadata type for nothing. §6.2 |
| **A11** | **A custom CLI deserializer, as `thinkingMode` has** | That exception was forced by `off` being a YAML 1.1 boolean. No `ReasoningEffort` spelling collides with YAML's boolean or null resolvers, and the mapper's `ACCEPT_CASE_INSENSITIVE_ENUMS` already covers case. §6.1 |
| **A12** | **Also make `thinkingDialect` declarable while in the capability files** | A different key family (what a model *is* vs. what this deployment *wants*), carried forward from #60 as a finding, and bundling it would make this round's capability change unreviewable against its stated reason |

---

## 14. Explicitly out of scope

- Anything belonging to #62: `LlmStreamChunk.Kind.REASONING_DELTA`, `AssistantReasoningDelta`,
  `reasoning.summary` on the OpenAI request, Anthropic's `display`.
- `llm-config-surface-open-items.md` `L-2` — `responsesApiEnabled` and the sampling parameters. Widened by
  nothing here; F-2's count is corrected, not closed (§12).
- Moving Anthropic's `thinkingMode` default off `OFF` (design §11 G-1).
- Teaching `AnthropicLlmClient` to read `acceptedReasoningEfforts` (§3.4). Its request surface has no rung
  parameter; the neutral rung is translated to a token budget, and `AnthropicThinkingBudgets` already
  applies the only floor that surface has.
- `requestTimeout` / `presencePenalty` / `frequencyPenalty` in agent frontmatter (§6.3, design G-5).
- Making the parser's `extractInt` / `extractDouble` strict (§6.3). A real defect, recorded as a
  carried-forward finding rather than fixed, because it changes the failure behaviour of three keys no
  issue asks about and would make this round's frontmatter change unreviewable against its reason.
- Re-measuring terra, `luna` or `sol`. The key that measured them is rotated (§12).
- Closing `llm-config-surface-open-items.md` `L-1`. It needs a starter-wide binding decision and has
  three limitation tests waiting to go red together.

---

## 15. What has **not** been measured

- **U-1 — no API call was made for this document.** Terra's ladder, the o-series ladders and the `gpt-5`
  family's are round 8's measurements (2026-09-09), quoted from `openai-model-capabilities.md` §13.3. The
  key that produced them is rotated, so nothing here re-measures them; the row ships on that evidence with
  its date on it.
- **U-2 — that Spring's binder leaves an unwritten `Set` property `null`** rather than materialising an
  empty one. The empty-set refusal (§3.2) depends on it: if Boot materialised an empty set, a declaration
  that never wrote the key would be refused. The acceptance test is a `model-capabilities` entry with no
  `accepted-reasoning-efforts` key that still builds; if it fails, the fallback is to keep the refusal on
  the CLI (where Jackson's behaviour is not in doubt) and treat the starter's empty set as undeclared,
  which is a documented asymmetry rather than a silent one.
- **U-3 — that Boot binds both `a,b,c` and an indexed list onto `Set<ReasoningEffort>` with relaxed
  case-folding.** Expected, standard, and pinned by the tests in §11 rather than asserted here.
- **U-4 — the §5.3 warning has no field evidence** that operators read it. It is the one place this
  design substitutes a log line for a behaviour, and it inherits U-2's standing in
  `reasoning-model-enablement.md` §9 for exactly the same reason.
- **U-5 — whether any `gpt-5.6-terra-<date>` snapshot exists.** Nothing in this tree has seen one; the row
  is exact partly because of that, and failure mode 9 states the cost if one appears.
- **U-6 — the blast radius of the fixture re-pointing (§4.4) is counted, not compiled.** Twenty-odd files
  contain the literal; the count of *behavioural* failures is two, by reading. If a third appears when the
  build runs, it is a place the terra row means something the test did not intend, which is information
  worth having rather than an accident.

---

## 16. Open questions — not resolved from the task statement

- **O-1 — is the divergence-signature rename wanted?** `reasoningEffortBelowLadder` →
  `reasoningEffortOffLadder`. It is internal (`OpenAIDivergenceReporter` is package-private) and the word
  "below" is now provably wrong, so this design renames it; the counter-argument is that an operator may
  have alerted on the string. Cheap to reverse, and named here so a reviewer can.
- **O-2 — the name `acceptedReasoningEfforts`.** Chosen over `reasoningEffortRungs` (ambiguous about
  whose) and `reasoningEfforts` (does not say accepted-by-whom), and it must be identical across the core
  field, both yaml/property keys and the declaration, because `ModelCapabilityConfig`'s javadoc makes the
  no-translation-table promise. If a reviewer prefers another name, it has to change in five places at
  once and the guides' tables with it.
- **O-3 — should the parser's lenient `extractInt` / `extractDouble` be a registered backlog item?** This
  design records it as a carried-forward finding (the shape runs 1 and 2 used) rather than opening an
  item, because the task's backlog obligations are two specific items and adding a third is scope a
  reviewer did not ask for. If the run's handoff should instead register it, that is a one-line decision.
- **O-4 — does the `gpt-5` prefix row's ladder stay *unset*?** This design leaves it unset, so it tracks
  the fail-open default, which is what the row's existing comment argues for and what measurement still
  supports. Stating `{MINIMAL, LOW, MEDIUM, HIGH}` explicitly would decouple the row from a default that
  might later move for unrelated reasons. Neither is wrong; the choice made is the smaller diff.
- **O-5 — whether the terra row belongs in `withDefaults()` at all, versus documentation telling operators
  to declare it.** Not seriously in doubt — the built-in table exists to spare operators exactly this —
  but it is the one question the backlog's blocking table could be read as leaving open, so it is named
  rather than assumed.


---

## 17. After the build — departures, and where this document is wrong

*Appended 2026-09-10, after implementation. Everything above this line is the body as approved; it is
not edited to look prescient. Two sources feed this section: the departures recorded in the run's
`build/deviations.md`, and the design review (`review-1.md`, PASS with no blocking findings and
thirteen non-blocking ones, most of them errors in this document's inventories rather than in what it
asks to be built).*

### 17.1 Where the implementation departed

**D-1 — the phase this field was supposed to land in.**
`reasoning-model-enablement.md:262` places `AnthropicConfig.reasoningEffort` in **Phase 2a** (#54); it
is taken here, in 2b. §12 promised that visible departures would be listed, and then listed none —
§16 is open questions, not departures. This is the one that was already visible from the design's own
desk. The reason is the one §5 argues: a *shared* key that reached one provider and was dropped by the
other would be a lie for half its users, so the field belongs to whichever round adds the shared key.
None of #54's three keys touches the effort ladder, so pulling it forward would have made that round
wait on nothing it needed. `reasoning-model-enablement.md` is deliberately **not** rewritten.

**D-2 — §4.2 and §3.4 / O-4 contradict each other, and O-4 won.** §4.2 says
`gpt5Family(Set<ReasoningEffort>)` is extracted and *"the prefix row is built from it too"*, which
forces the prefix registration to state a set; §3.4 and O-4 say the `gpt-5` prefix row's ladder stays
**unset**. Implemented as O-4: `gpt5Family()` returns a fresh `ModelCapabilities.Builder` carrying the
family's four flags, the prefix calls `.build()` on it directly, and terra calls
`.acceptedReasoningEfforts(...)` first. Both properties the two sections wanted survive — one source
for the four flags, and a prefix row that still says nothing it has not measured — and the two forms
are behaviourally identical, since `ModelCapabilities.equals` compares the resolved set either way.

**D-3 — §4.4's inventory undercounted, and the rule it states was applied unevenly on purpose.**
`InMemoryModelCapabilityRegistryTest` carries **13 terra literals over 11 lines**, not five; this
document names three. Six of the unnamed ones would have stayed green while changing subject, which is
the hazard §4.4 exists to prevent. The stated remedy — one constant per class — was applied to the
**nine OpenAI client test classes** where the name is incidental fixture plumbing
(`A_REASONING_MODEL = "gpt-5-mini"`). It was **not** applied to the registry test, where the model name
is the *subject* of every assertion and sits beside `"o4-mini"` and `"gpt-5-chat-latest"` as a literal;
each of the 13 was decided individually instead. `transcribe()` keeps terra deliberately — it is the
only shipped row whose ladder has a gap, so it is the row that proves §3.5's "the surface can express
every built-in row" invariant survives.

**D-4 — a fifth test went red, in a module neither this document nor the review looked at.**
`AnthropicThinkingDialectTest.offIgnoresTheDialectEntirely` asserts `warnings()).isEmpty()` while
setting an effort under `thinkingMode: OFF`, so §5.3's new WARN fires there. It was not loosened: the
empty-warnings assertion is what would notice a *dialect* warning leaking into a mode that never asks
the table anything, so it was split into "no warning names the dialect" plus "every warning present is
the effort one".

**D-5 — a constructor ordering §3.2 does not mention.** `ModelCapabilityDeclaration` copied the
declared rungs defensively before computing `capabilities`, so `acceptedReasoningEfforts: []` threw
`EnumSet.copyOf`'s own *"Collection is empty"* rather than the named refusal §3.2 specifies.
`resolve(...)` now runs first.

**D-6 — the surface types are `List`, not `Set`.** §9's shapes are what the two core types got. The
configuration classes bind a `List` on both surfaces, because that is what a yaml sequence and Boot's
relaxed binding produce; each translates to a set on the way to the declaration, where a duplicate rung
folds (§3.5).

**D-7 — two smaller ones.** The paired OpenAI precedence test could not also carry the "nothing
configured sends nothing" half, because that class's `capture()` helper verifies exactly one SDK call;
that claim already had a home. And §4.4's one exemption
(`withAnEmptyRegistryEvenALiteralGpt56TerraGetsTheUntouchedRequest`, *"where the name is the point"*)
was moved to the constant anyway: that test passes `ModelCapabilityRegistry.EMPTY`, so no row exists
for any name — terra's new one included — and the assertion is identical whichever gpt-5 name is
written.

### 17.2 What the design review corrected

`review-1.md` passed this design with no blocking findings, on the grounds that *"the errors are in
the document's inventories and stated justifications rather than in what it asks to be built"*. Four
of those corrections changed what had to be done:

- **§3.4's reader list is not exhaustive, and doclint would not have caught the gap.** §3.4 calls the
  `AnthropicLlmClient:553` javadoc link *"load-bearing for the build … a dangling `@link` fails
  doclint"*. It does not: `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:25` sets
  `Xdoclint:none` on every module. The review found a second site (`ReasoningEffort.java:29`) and the
  implementation found a **third** that both missed — `ModelCapabilityDeclaration.java:124`, whose
  surviving getter's javadoc pointed at the removed one. All three were found by grep and re-pointed.
- **§5.3's warning condition was backwards for one value.** As designed it fires whenever an effort is
  present under `OFF`, which would tell an operator who wrote `reasoningEffort: none` under the default
  mode to *turn thinking on*. `NONE` and `OFF` mean the same thing — `resolveThinking` already
  special-cases both — so the condition is `effort != null && effort != NONE`, and a test pins the
  silence.
- **§12's machine half does not exist.** `AimonDocumentedPropertiesTest` runs documentation → code: a
  documented key must exist, and nothing checks that an existing property is documented. The guide
  edits have no gate and were done by hand.
- **§11's "no new selector row" is true but argues the wrong way.**
  `AimonConfigurationMetadataTest.everySelectorOffersItsValues` carries a row for
  `MEMORY_INJECTION_MODE` precisely because that enum is a core type this module would not notice
  changing. `ReasoningEffort` is the second such selector, so a row was added.

Two further corrections are recorded rather than acted on, because they are about this document's own
arithmetic: the `grep -rl lowestReasoningEffort` count is 26–27 rather than §3.4's "33 files", and
`effortBelowTheLadderIsOmitted` starts at `:108` rather than §4.4's `:118`.

### 17.3 The one correction that reaches the backlog closure

§12 has the `L-1` closure lean on the item's own trigger — *"the grep in the item body stops returning
zero, and that is the verification, not a claim"*. **The grep returns 2 today, not 0**
(`AimonProperties.java:1408` and `default-config.yaml:21`, both prose), so a closure written as §12
describes would assert a transition that did not happen. The closure states the real numbers instead:
**2 → 26** for the grep as written, and **0 → 7** for a form that counts declarations rather than
prose. The substantive claim — that no key existed on either surface — is unaffected and is what the
item's severity rested on.

### 17.4 One thing this round could not verify, promoted to the backlog

§15 lists five unmeasured items and the review added a sixth: **terra's ladder was measured on
`/v1/responses` only** (`openai-model-capabilities.md:1348-1362`), while
`OpenAiRequestParameters.maySendEffort` is called by *both* endpoints. So a deployment forcing terra
onto Chat Completions — `responsesApiEnabled(false)`, which is programmatic-only — now sends
`reasoning_effort: none` on a cell nobody has measured, turning a reported omission into a possible
400. The row is still the right one, since the alternative is the measured 400 on `minimal`; the
inference is named rather than hidden.

Its consequence outlives this phase, so it is promoted to an **open** backlog item rather than left
here: `L-2` in
[`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md)
carries it, because that is the item that would make `responsesApiEnabled` reachable from
configuration and therefore the round in which this combination stops being programmatic-only. Whoever
takes `L-2` has to measure the cell or record that they chose not to. The `L-1` closure in
[`../../backlog/openai-model-capabilities-open-items.md`](../../backlog/openai-model-capabilities-open-items.md)
names it too, under "다시 측정하지 않은 것", so that a reader arriving at the closed item does not have
to find it here — but the live copy is the one on `L-2`, since a closed item is not where anyone looks
for open work.

The other five stay here: they are phase-local (fixture blast radius, binder shapes) or already
carried by the round-8 record (the rotated key, the uncalled `luna` / `sol`).

§16's five open questions are answered by what shipped — O-1 renamed, O-2 kept, O-3 recorded as a
carried-forward finding rather than registered, O-4 as D-2 above, O-5 not seriously in doubt — and
none of them has a consequence beyond this phase.
