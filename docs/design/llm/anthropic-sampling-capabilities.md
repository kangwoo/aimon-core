# Design — Anthropic sampling parameters behind the capability registry (#52)

> Status: **IMPLEMENTED.** Target: `modules/aimon-llm-anthropic`, `aimon-core`'s
> `at.aimon.core.llm.capability`, `aimon-cli`, `aimon-spring-boot-starter`.
> This is `anthropic-thinking-traces.md` §8 **F-2**, unblocked and now closed.
>
> **§15 is written after the fact** and records where the implementation departed from this document,
> including the one judgement call §8.2 deliberately left open. Read it before trusting a detail in §8.
>
> **§2 is measured, not inferred.** 58 live calls on 2026-09-09 against the account's own
> `/v1/models` list, with the negative controls that make the table mean something. One fact this
> repository has written down turns out to be wrong, in the two places it is written, and is
> corrected in place at §2.5 — it is itself a correction recorded as a correction, which is why that
> section says where the mistake came from rather than only what it was. §2.2 also records a
> per-parameter asymmetry no prior design names.
>
> Prior designs this one continues, and does not restate:
> `docs/design/llm/anthropic-thinking-traces.md`
> (#47) §2.3 · §3.5 · §8 A1/F-1/F-2 · §9 U-2;
> `openai-model-capabilities.md` (#44) §2.3 fail-open · §2.5 reporting · §2.6 the seam · §11.1
> rejection-by-value; `model-capability-config-key.md` (#46) §2.1 the shared translator · §2.7
> B-21. Paths in this document are repository-relative.

---

## 0. The decisions, first

The task named four things to settle. Here are the answers; the reasoning is one section each.

| # | Question | Answer |
|---|---|---|
| **D-1** | Reuse `ModelCapabilityRegistry`, or give Anthropic its own? | **Reuse, unchanged.** No new type, no new field, no new table class. The fact needed is `supportsSamplingParameters`, which already exists and is already vendor-neutral; the sibling per-model registries in the same core area (`InMemoryModelContextWindowRegistry`, `InMemoryModelPriceTable`) already carry `claude-*` rows beside `gpt-*` ones, so a mixed table is the house shape rather than a novelty. §3 |
| **D-2** | Are `top_p` and `top_k` in scope? | **`top_p`: yes** — it is named by the same flag, it is refused by the same six models, and it is the *only* residual 400 on the thinking-on path. **`top_k`: out of scope by absence** — `LlmModel` has no such field, so no code path in AIMON can set it; nothing to suppress. It was measured anyway (§2.2) so the table's one-bit flag rests on all three columns rather than two. The two penalties keep their existing "no Anthropic counterpart" treatment. §4 |
| **D-3** | `AnthropicConfig.temperature`: keep the manufactured `0.0`? | **No — it becomes unset-by-default**, the shape `OpenAIConfig` already has. The repository's own provider guide forbids what this class does today, in those words; the measurement makes it worse than a style violation (§2.6). Breaking, separable, and argued at §5 including what a reviewer who declines it gets instead. |
| **D-4** | Fail-open or fail-closed for an unknown model? | **Fail-open, inherited from `ModelCapabilities.unknown()` and deliberately not relitigated here** — the constant is shared with OpenAI and pinned by a test. The task's premise that *both directions can produce a 400* is **false for this axis, and measured false** (§2.3 Control A): omission never 400s. It is true for the *dialect* axis, which is F-1 and still open. §6 |
| **D-5** | CLI / starter config surface: now, or a follow-up? | **Now.** Not because it is cheap (it is: four production lines) but because both surfaces currently *refuse* an Anthropic capability declaration with a message that says only OpenAI reads the registry. Shipping D-1 without this leaves a guard that is false and that blocks exactly the deployment the fix is for. §7 |
| **D-6** | Ship a Mythos row this account cannot see? | **Yes, as one `claude-mythos` prefix, labelled documentation-derived in the source comment and in §11.** The evidence class is enumeration-verification, not sibling-inference: the vendor sentence names nine models, six are reachable, and all six matched exactly. §3.3 |

---

## 1. The problem, in one paragraph

`AnthropicLlmClient.buildRequest(...)` calls `.temperature(...)` on every request that does not
carry a `thinking` parameter, filling the value from `AnthropicConfig.getTemperature()`, whose type
is a primitive `double` seeded at `0.0` — so "nobody configured a temperature" and "somebody
configured 0.0" are the same state and the client has no way to express *do not send this field*.
Six of the eleven models this account can reach reject `temperature: 0.0` with HTTP 400, and
`AnthropicConfig`'s default thinking mode is `OFF`, so **in its default configuration this client
cannot reach the current Anthropic model generation at all**. The fix is the one the OpenAI side
already ships: read `ModelCapabilities.supportsSamplingParameters` for the resolved model name,
suppress the parameters that model refuses — and only those — report each suppression through the
existing `reportDivergence(...)` seam, and stop manufacturing a sampling value nobody asked for.

### 1.1 Two refinements to the issue's statement, and to the launcher's criteria

The task says the design phase may refine the acceptance criteria but must say so. Two refinements,
both narrowing rather than dropping:

1. **"Every request … thinking on or off" is true of the default configuration and imprecise about
   the thinking-on path.** #47 already omits `temperature` whenever a `thinking` parameter is
   present (`applySamplingParameters`, the `thinkingRequested` branch). So with thinking **on**, the
   residual exposure is `top_p` alone — sent whenever the configured value falls in `[0.95, 1.0]`,
   and refused by all six models at any value including `1.0` (§2.2). With thinking **off**, which
   is the default (`DEFAULT_THINKING_MODE = OFF`), the temperature 400 is exactly as described. The
   conclusion the issue draws — the client cannot talk to those models — stands; D-2 is what makes
   it stand on the thinking-on path too.
2. **Acceptance criterion 3's parenthetical is wrong for this field.** It says *"for this field both
   directions can produce a 400, which is why #47 called it out as needing a design round"*. #47
   called it out for a different reason. §8 A1 declined the registry because the *dialect* axis
   (`adaptive` vs `enabled`) is two-valued and mutually exclusive, so both values 400 on the wrong
   model and there is no safe default — a genuine design problem, and still open as F-1. Sampling is
   not that shape: **omission is universally safe**, measured (§2.3 Control A). The criterion's
   substance — decide and state the posture — is answered at §6; its stated reason is corrected
   here rather than inherited.

---

## 2. Measurement

**Method.** `POST https://api.anthropic.com/v1/messages`, `anthropic-version: 2023-06-01`,
`max_tokens: 16`, one user message `"hi"`, one parameter varied per cell. The key was read from
`~/.anthropic-key` at the point of use in each command and appears nowhere else. 58 accepted calls
plus 2 self-inflicted 404s from a shell-quoting mistake. Refusals are rejected at parameter
validation before generation, so the 400 cells cost nothing; the 200 cells generate at most 16
tokens.

The eleven models are the account's own `/v1/models` listing. **No Mythos model is visible to this
account**, so the three Mythos rows the vendor sentence names could not be probed; they are carried
as documentation-derived and labelled at §11.

### 2.1 `temperature`

| Model | `0.0` | `0.5` | `1.0` | Verdict |
|---|---|---|---|---|
| `claude-fable-5-1` | **400** | **400** | 200 | refuses non-default |
| `claude-fable-5` | **400** | **400** | 200 | refuses non-default |
| `claude-opus-5` | **400** | **400** | 200 | refuses non-default |
| `claude-opus-4-8` | **400** | **400** | 200 | refuses non-default |
| `claude-opus-4-7` | **400** | **400** | 200 | refuses non-default |
| `claude-sonnet-5` | **400** | **400** | 200 | refuses non-default |
| `claude-sonnet-4-6` | — | 200 | — | accepts |
| `claude-opus-4-6` | — | 200 | — | accepts |
| `claude-opus-4-5-20251101` | — | 200 | — | accepts |
| `claude-haiku-4-5-20251001` | — | 200 | — | accepts |
| `claude-sonnet-4-5-20250929` | — | 200 | 200 | accepts |

Every 400 carries the same message, verbatim:

```text
`temperature` is deprecated for this model.
```

### 2.2 `top_p` and `top_k`

| Model | `top_p: 0.9` | `top_p: 1.0` | `top_k: 5` |
|---|---|---|---|
| the six above | **400** | **400** (probed on `claude-opus-5`, `claude-opus-4-7`) | **400** |
| the five above | 200 | — | 200 |

Messages, verbatim: `` `top_p` is deprecated for this model. `` and
`` `top_k` is deprecated for this model. ``

### 2.3 The controls

Three, because a table of 400s on its own does not distinguish *the server refuses this parameter
for this model* from *my request was malformed*. This follows the form #47's live round used when a
reviewer made the same objection about signature replay (commit `200b05f`, "record the mutation that
proves the negative control earns its place"): the claim is carried by a **contrast**, and the
contrast is run rather than argued.

| Control | What was sent | Result | What it rules out |
|---|---|---|---|
| **A — the negative control** | The **identical body**, with no sampling parameter at all, against all six refusers plus `claude-haiku-4-5-20251001` and `claude-sonnet-4-5-20250929` | **200 on all eight** | That the 400 is a property of the request shape, the account, the API version header or `max_tokens`. The only variable left between a 200 and a 400 is the parameter — and, across §2.1, the model name |
| **B — presence vs value** | `temperature: 1.0` against all six refusers | **200 on all six** | That `temperature` is refused *outright*. It is not; see §2.5 |
| **C — the null form** | `"temperature": null` against `claude-opus-5` (refuser) and `claude-sonnet-4-5-20250929` (accepter) | **400 on both**, `temperature: Input should be a valid number` | That "omit" could be implemented by passing null. It cannot, on **either** kind of model — this is a schema error, not a capability one |

Control A is the one the design rests on. The same body that 400s on `claude-opus-5` with
`temperature: 0.5` returns 200 on `claude-sonnet-4-5-20250929`, and returns 200 on
`claude-opus-5` itself once the parameter is removed. Two contrasts, one along the model axis and
one along the parameter axis, from a single fixed request shape.

Control C is worth more than it looks. Both prior designs *assert* that omission must mean "never
call the setter" because a present-but-null key is rejected the same way a value is; the repository's
provider guide states it as a rule with `"temperature": null` in the sentence. Nobody had run it on
this vendor. It is now run, and it is stronger than the assertion: the null form fails on a model
that happily accepts `temperature: 0.5`, so it is not a capability rule at all but a type rule that
applies everywhere.

### 2.4 What the measurement confirms

- **The refusing set is exactly the six reachable models the vendor sentence names.** Zero false
  positives, zero false negatives among names this account can reach. That is the evidence D-6 rests
  on when it ships an unmeasurable Mythos row.
- **One flag covers all three parameters measured here.** No reachable model accepts a strict
  subset — the six refuse `temperature` (non-default), `top_p` (any) and `top_k` (any); the five
  accept all three. `ModelCapabilities`'s javadoc offers "no model in use today accepts a strict
  subset of them" as the justification for one flag rather than four, and the overlap is partial:
  its four members are `temperature` / `top_p` / `presence_penalty` / `frequency_penalty`, so what
  is measured here confirms **two of them, plus one the javadoc does not name** (`top_k`). The two
  penalties cannot be measured on this vendor at all — Anthropic has no counterpart, which is A8's
  own point — so for Anthropic the flag's justification is confirmed where it can be and is
  inapplicable where it cannot. No decision in this design turns on the difference.
- **`claude-opus-4-5-20251101`, `claude-sonnet-4-5-20250929`, `claude-haiku-4-5-20251001`,
  `claude-sonnet-4-6` and `claude-opus-4-6` must not be caught by any new row.** They accept
  everything with thinking off. This is the prefix trap §3.2 is built around: a `claude-opus-4`
  prefix would swallow `claude-opus-4-5` and `claude-opus-4-6`.

### 2.5 What the measurement **contradicts**, and where the mistake came from

> **Corrected by measurement.** `anthropic-thinking-traces.md` §2.3's own correction block, and
> §9 U-2, say this:
>
> > The real message is `` `temperature` is deprecated for this model. `` — the parameter is refused
> > outright rather than judged against a default, so `temperature: 0.0` is rejected like any other
> > value.
>
> The first half is right and the second half is wrong. `temperature: 1.0` returns **200 on all six**
> (Control B). The parameter is judged against a default exactly the way OpenAI's is; what misled
> the earlier round is that the *message* says "deprecated", which reads like a statement about the
> parameter rather than about the value. `temperature: 0.0` is indeed rejected — but because `0.0`
> is not `1.0`, not because the key is forbidden.

This is the second correction to the same sentence and it is worth naming the pattern: the original
design paraphrased the vendor as *"any non-default temperature 400s"*, which was **right**; #47's
live round overturned it to *"refused outright"*, which was **wrong**; the measurement restores the
original reading with evidence. The lesson is the one the o-series rounds kept learning — an error
message is a sentence about what the server wants to tell you, not a specification, and the only way
to learn which values are accepted is to send them.

Three consequences, all of which make the design *easier* rather than harder:

1. **Suppression loses nothing on the wire.** Omitting `temperature` yields the server's default,
   and the server's default is the one value it accepts. This is verbatim the argument the
   `registerPrefix("gpt-5", ...)` comment already makes in `InMemoryModelCapabilityRegistry`, and it
   now applies unchanged on this side.
2. **`top_p` is genuinely different from `temperature`, and this is new.** `top_p: 1.0` is a 400
   (§2.2). So on these six models `temperature` is refused **by value** and `top_p` is refused **by
   presence**. Neither the vendor sentence nor either prior design distinguishes them. One flag
   still covers both correctly — suppressing both is right — but the flag is an *over-approximation
   for `temperature` only*, which is the trade `OpenAiRequestParameters.applySampling`'s javadoc
   already documents on the other side ("a caller who explicitly sets 1.0 gets a divergence warning
   for a call the API would have accepted"). It is inherited here with the same reasoning and needs
   no new capability shape.
3. **`AnthropicThinkingLiveTest.offCannotReachAnAlwaysOnModel` stays valid but becomes a test of the
   old behaviour.** It asserts `LlmInvalidRequestException` containing `` `temperature` is deprecated
   for this model `` on `claude-sonnet-5` with a default config — which is exactly what this change
   removes. It must be **inverted**, not deleted; §9.5.

### 2.6 The measurement's verdict on the manufactured `0.0`

Stated separately because it is the argument for D-3 and it is not a matter of taste.
`AnthropicConfig` invents `temperature = 0.0` when nobody sets one. Of the values available to
invent, **`0.0` is the single worst**: it is refused by six of eleven reachable models, whereas
sending nothing is accepted by eleven of eleven (Control A) and yields the one value all eleven
accept (Control B). The default is not a neutral choice that happens to collide with a new model
generation; it is the most dangerous point in the space.

---

## 3. D-1 — reuse the registry

### 3.1 The argument, both ways

**For a new Anthropic-specific type.** #47 §8 A1 declined to extend the registry for two reasons.
The first — #48 concurrently editing `at.aimon.core.llm.capability` — has expired; #48 is closed. The
second is the one to check rather than inherit: *the fact that matters is not expressible in today's
`ModelCapabilities`*. That reason was about the **thinking dialect**, a two-valued mutually exclusive
axis with no fail-open value, and it is still correct — F-1 is still open and this change does not
touch it. It says nothing about sampling. So the surviving argument for a separate type would have to
be that a shared table mixing vendors is confusing, or that Anthropic's rules differ in kind. Neither
holds: §2.4 shows the rules are the same shape as OpenAI's (a set of models that refuses a set of
sampling parameters), and the confusion argument is answered by the tree itself.

**For reuse — decisive.** Four things, in ascending order of weight:

1. `ModelCapabilities.supportsSamplingParameters()`'s javadoc names `temperature` and `top_p` as its
   subject and says nothing vendor-specific. The type was designed to be the answer to this.
2. **The sibling registries already mix vendors in one table.**
   `InMemoryModelContextWindowRegistry.withDefaults()` carries seven `claude-*` prefixes beside its
   `gpt-*` ones; `InMemoryModelPriceTable.withDefaults()` carries eight. A capability table with
   `claude-*` rows is not a new pattern in this area — it is the *missing* member of a set of three
   that already agree.
3. `ModelCapabilities`'s own javadoc for `supportsReasoningTraceRoundTrip()` already writes
   Anthropic into the SPI's meaning: *"OpenAI's answer to it is 'use `/v1/responses`…', Anthropic's
   is 'send the thinking blocks back'"*. The package is not OpenAI's with a neutral name; it was
   built expecting this consumer.
4. **#46 spent B-21 on this promise.** `model-capability-config-key.md` §2.7 put
   `model-capabilities` in the shared `aimon.llm.*` namespace instead of `aimon.llm.openai.*`
   specifically because *"a second consumer is planned (#47)"* and moving the key later would be a
   breaking key move. A separate Anthropic type would make that decision retroactively wrong and
   would strand a shared key with one consumer forever.

There is no name collision to manage: the built-in `gpt-*` / `o[134]` rows cannot match a `claude-*`
name and the new rows cannot match an OpenAI one. The one shared surface — `withDefaults()` — grows
by six prefixes: five measured, plus `claude-mythos`.

**One consequence of a shared table with two consumers, and it is not a name collision.** The rows
are matched by name, but the table is read by *both* clients — so a `claude-*` model name routed
through an OpenAI-compatible gateway by `OpenAILlmClient` now resolves to the new rows and has its
sampling parameters suppressed. That is **benign to correct**: the underlying model does refuse
them, so the suppression is the right answer arriving from an unexpected direction. It is stated
here because it is invisible from either client's source, and §3.2 puts it in the registry comment
beside the prefix-trap warning, which is the only place a future editor of these rows will read.

**Verified, not assumed:** `AnthropicArchitectureTest` already whitelists `at.aimon.core.llm..`, so
`at.aimon.core.llm.capability` is legal from this module with no rule change; and
`aimon-llm-anthropic/build.gradle.kts` declares `implementation(project(":aimon-core"))`, identical
to the OpenAI module, so exposing a core type on `AnthropicConfig`'s public API is precedented.

### 3.2 The rows

Six prefixes — five measured, plus `claude-mythos` — appended to `builderWithDefaults()`. Five
cover the six measured names because `claude-fable-5` matches `claude-fable-5-1` as well as
`claude-fable-5`. Each declares **one flag** and leaves the other
four at their fail-open values, because that is the only fact measured and because
`ModelCapabilities.Builder` is documented to be seeded fail-open precisely so a partial row is safe.

```java
// Measured 2026-09-09 against the account's own /v1/models listing. On these six the server
// refuses temperature at any non-default value, and top_p / top_k at ANY value including their
// defaults -- see docs/design/llm/anthropic-sampling-capabilities.md §2. Suppression loses
// nothing: omitting temperature yields 1.0, which is the one value they accept.
//
// Prefixes rather than exact names so that a dated snapshot (claude-opus-5-2026...) inherits the
// row. Deliberately NOT "claude-opus-4": claude-opus-4-5 and claude-opus-4-6 accept all three
// (measured), and a family prefix would suppress a parameter they take.
//
// This table is read by BOTH clients. A claude-* name reaching OpenAILlmClient through an
// OpenAI-compatible gateway resolves to these rows and gets the same suppression -- correct, since
// the underlying model does refuse, but it is a consequence of one shared table rather than of
// anything either client says. Weigh it when editing a row: the blast radius is both providers.
.registerPrefix("claude-fable-5", SAMPLING_REFUSED)
.registerPrefix("claude-opus-5", SAMPLING_REFUSED)
.registerPrefix("claude-opus-4-7", SAMPLING_REFUSED)
.registerPrefix("claude-opus-4-8", SAMPLING_REFUSED)
.registerPrefix("claude-sonnet-5", SAMPLING_REFUSED)
// Documentation-derived: no Mythos model is visible to this account, so this row is the vendor's
// enumeration rather than a measurement. Labelled here and in §11 of the design.
.registerPrefix("claude-mythos", SAMPLING_REFUSED)
```

where `SAMPLING_REFUSED` is
`ModelCapabilities.builder().supportsSamplingParameters(false).build()`, one shared constant so the
six rows cannot drift.

**What is deliberately absent.** No row sets `supportsReasoningTraceRoundTrip(true)` for a Claude
model even though Anthropic models do round-trip thinking blocks. The Anthropic client captures and
replays **unconditionally** (#47 §3.1) and never reads that flag, so a `true` there would be an
assertion nothing consumes — and if a future consumer did read it, it would be reading a claim this
round never measured. Same for `supportsReasoningEffort`: the Anthropic client decides that from
`thinkingMode`, not from the registry. One measured fact, one flag.

**Registration order.** These go **after** the existing OpenAI prefixes. Order is load-bearing only
among prefixes that can both match one name, and no `claude-*` prefix can collide with a `gpt-*` or
`o[134]` one. Within the new block, none is a prefix of another (`claude-opus-4-7` and
`claude-opus-4-8` are siblings, not nested), so their relative order is free too — which a test
should pin rather than leave to reading.

### 3.3 D-6 — why an unmeasurable row still ships

#44 round 1 cut its o-series rows for exactly this reason: *"the belief that they reject sampling was
unverified and a wrong row is a silent change"*. That precedent is real and it points the other way
here, so it needs answering rather than ignoring.

The difference is the **evidence class**. The o-series case was sibling-inference — `o1-pro` was
never called, and its only connection to a measured model was sharing a name prefix, which is not
evidence about a request surface. The Mythos case is enumeration-verification: the vendor published a
list of nine model names that refuse these parameters, six of those nine are reachable from this
account, and **all six matched exactly, on all three parameters, with no false positives and no false
negatives**. That is evidence about the reliability of *this sentence*, and it is the strongest form
of it available short of the calls themselves.

The cost matrix agrees. A wrong Mythos row (Mythos actually accepts sampling) drops a configured
value and emits one WARN; the call succeeds. A missing Mythos row (Mythos refuses, as documented)
is HTTP 400 on every request. Both mistakes are possible; only one of them fails the turn.

The row is `claude-mythos`, one prefix covering the family, rather than three guessed identifiers.
That is the honest shape: guessing that Mythos 5.1's id is `claude-mythos-5-1` is guessing an
*identifier*, which is a different and worse kind of guess than trusting a *fact* about names the
vendor spelled out. A family prefix asserts only the fact.

---

## 4. D-2 — `top_p` in, `top_k` out, penalties unchanged

| Parameter | Decision | Why |
|---|---|---|
| `temperature` | **In.** Suppressed when the flag is false. | The issue. |
| `top_p` | **In.** Suppressed when the flag is false, and the capability gate sits **outside** the existing thinking-window gate. | It is refused by the same six models at any value (§2.2), it is settable from `LlmModel.getTopP()`, and it is the **only** residual 400 on the thinking-on path — where #47 already omits `temperature` but still sends `top_p` inside `[0.95, 1.0]`, a window that includes `1.0`, which is a measured 400. Leaving it out would ship a fix whose own release note has to say the models still 400 when `topP` is configured. |
| `top_k` | **Out, by absence rather than by choice.** | `LlmModel` has no `topK` field and `AnthropicLlmClient` never calls `MessageCreateParams.Builder.topK`. There is no code path in AIMON that can send it, so there is nothing to suppress and nothing to report. It was measured anyway (§2.2) so that the one-bit flag rests on all three columns; if `LlmModel` ever grows the field, the row already says the right thing and only the client's sink needs a line. |
| `presencePenalty`, `frequencyPenalty` | **Unchanged.** | Anthropic has no counterpart at all, and `buildRequest` already drops them with a divergence that says exactly that. Routing them through the capability check would replace a true and specific message ("no Anthropic counterpart") with a vaguer one ("this model does not accept sampling parameters") **and** would make them silent on the five models that do accept sampling — where they are still dropped. Keeping them out of the capability branch is not an omission; it is the more accurate report. |

### 4.1 Where the gate goes

The capability check is the **outer** gate; the thinking rules stay inside it. Reversing them would
send `top_p: 0.98` to `claude-opus-5` with adaptive thinking on, which is a measured 400.

```
applySamplingParameters(builder, modelConfig, modelName, capabilities, thinkingRequested)
  │
  ├─ !capabilities.supportsSamplingParameters()
  │     → report each value somebody set (temperature, topP), set nothing, return   ← new
  │
  └─ else  → exactly today's behaviour: temperature omitted-and-reported when thinking
             is on, clamped to [0,1] otherwise; topP gated by the [0.95, 1.0] window
             when thinking is on
```

One consequence worth stating rather than discovering: on a refusing model with thinking on, a
configured temperature now produces the **suppression** message rather than the
"incompatible with Anthropic thinking" one. Both are true; the suppression one is more useful,
because turning thinking off would not help. The thinking-specific wording remains reachable — and
correct — on the five accepting models, which is where it was always the right sentence.

---

## 5. D-3 — `AnthropicConfig.temperature` stops manufacturing a value

### 5.1 Why this is not scope creep

Three independent reasons, and the first is the issue's own text:

1. **The issue names it.** *"Setting `temperature` explicitly to the default does not help in the
   general case — the client has no way to express 'do not send this field at all'."* That sentence
   is about the config type, not about the registry.
2. **The repository's provider guide forbids exactly this, in these words.**
   `docs/features/llm/llm-provider-development-guide.md`:

   > IMPORTANT: **프로바이더는 값을 지어내지 않는다.** `orElse(DEFAULT_TEMPERATURE)` 처럼 미설정을 자기
   > 상수로 채우면, 아무도 요청하지 않은 샘플링 값이 매 요청에 실리고 서버 기본값이 영영 적용되지 않는다.

   `modelConfig.getTemperature().orElse(config.getTemperature())` against a primitive seeded at
   `0.0` **is** `orElse(DEFAULT_TEMPERATURE)`. The guide is a normative document with a worked
   example that this provider does not follow.
3. **`ModelCapabilities.unknown()` is a two-sided rule and the client can only honour one side.**
   *"Nothing the caller asked for is withheld, **and nothing the caller did not ask for is
   invented**."* A capability gate layered on a value-manufacturing config produces a client that
   claims to implement the SPI's contract and implements half of it.

And the measurement (§2.6) turns the style point into a defect: the invented value is the one value
most likely to be refused.

### 5.2 What changes

`AnthropicConfig.temperature`: `double` (seeded `0.0`) → `Double` (null = unset).
`getTemperature()`: `double` → `Optional<Double>`. The `[0.0, 1.0]` construction-time validation
stays and applies only when a value was set. `DEFAULT_TEMPERATURE` is deleted. This is exactly
`OpenAIConfig`'s shape, field for field.

### 5.3 The cost, stated plainly

**This changes the wire for existing deployments that never configured a temperature.** They send
`0.0` today and will send nothing, so Anthropic's own default (`1.0`) applies and output becomes
less deterministic. On the six refusing models that is moot — those deployments do not work at all
today. On the five accepting models it is a real, observable change, and it is the kind that shows up
as "the agent got chattier" rather than as an error.

It is also breaking API on a published module: `getTemperature()` changes return type. Under
`api-stability.md`'s `0.x` promise that is permitted with a CHANGELOG entry, and there is direct
precedent — `OpenAIConfig` made the identical change in #43/#44 and the CHANGELOG records it as
breaking-and-correct.

### 5.4 D-3 and D-1 are complementary, not redundant — and this is measurable

It would be reasonable to ask whether D-3 alone fixes the issue. It does not, and the reason is
`SubagentLlmDefaults.resolveModel`:

```java
return LlmModel.builder().name(modelName)
        .temperature(defaultModel.getTemperature().orElse(DEFAULT_TEMPERATURE))   // 0.7
```

Every subagent turn carries an **explicitly set** temperature, `0.7` when the parent has none. `0.7`
is a measured 400 on all six refusing models. So:

| | fixes the default agent turn | fixes a subagent turn | fixes a turn with an explicit `temperature` / `topP` | fixes an **unknown** refusing model |
|---|---|---|---|---|
| D-1 alone (registry) | yes | yes | yes | no |
| D-3 alone (unset default) | yes | **no** | **no** | yes, when nobody set a value |
| both | yes | yes | yes | yes, when nobody set a value |

D-1 is the primary fix and satisfies the acceptance criteria on its own. D-3 closes the unknown-model
row for the common case and stops the client breaking a documented house rule. **A reviewer who
declines D-3 gets a design that still works**: the two-wording device in `applySamplingParameters`
(`temperatureOmittedForThinking` vs `clientTemperatureOmittedForThinking`) stays, the suppression
report needs the same two wordings for the same reason, and criterion 3 holds only in the weak sense
(an unknown model is no worse off than today). Say so in the CHANGELOG either way.

---

## 6. D-4 — fail-open, and why that is not this change's decision to make

`ModelCapabilities.unknown()` answers `supportsSamplingParameters() == true`. That constant is
shared with the OpenAI client, is documented as "today's behaviour" rather than "everything
permitted", and is pinned flag-by-flag by `ModelCapabilitiesTest`. Flipping it to fail-closed would
silently stop sending `temperature` for every OpenAI model no registry describes — a large regression
in another module, arriving as a rider on an Anthropic bug fix. **The posture is inherited, and this
change does not relitigate it.**

Worth recording that the local cost matrix would have argued the other way, which is precisely why
the decision belongs to the shared constant and not to this consumer: wrongly suppressing costs a
reported omission and the server's own default, while wrongly sending costs a failed turn. On the
sampling axis alone, fail-closed is the cheaper mistake. It loses anyway, because the flag is not
this module's to define.

**The consequence, stated the way the package already states it.** A deployment behind a gateway that
renames a refusing model (`model: prod-assistant`) resolves to `unknown()`, keeps sending, and keeps
hitting the 400 — *"that is the price of never guessing"*, in `package-info.java`'s words. The
remedy is one line programmatically and, because of D-5, one block in yaml or properties. With D-3 it
narrows further: such a deployment works unless somebody set a sampling value or it runs subagents.

**And the criterion's premise is measured false.** Control A sent the identical body with no sampling
parameter to all six refusers and got 200 on all six. Omission cannot 400. The "both directions"
property belongs to the dialect axis — where `{"type":"enabled"}` and `{"type":"adaptive"}` each 400
on the other family's models, both verified live in #47 — and that axis is F-1, untouched here.

---

## 7. D-5 — the config surface ships with this change

### 7.1 The blocking fact

Both assembly paths currently **refuse to boot** when an Anthropic provider is configured alongside a
capability declaration:

- `LlmClientFactory.refuseModelCapabilitiesForAnthropic(...)` —
  *"`llm.modelCapabilities` is declared but the anthropic provider does not read it - only the openai
  client consults the model capability registry today."*
- `AimonLlmAutoConfiguration.refuseModelCapabilities(llm, PROVIDER_ANTHROPIC)` —
  *"…builds a client that does not consult the model capability registry — only the OpenAI client
  does."*

After D-1 both sentences are false, and both guards refuse the exact configuration that fixes a
renamed refusing deployment. So the choice is not "ship the surface or leave it alone"; it is "ship
the surface, or edit these two files anyway to reword a guard into something that is still a
dead end". Splitting is a legitimate answer in general — #47 split F-6 and #46 was the result — but a
split here costs an edit to both files and buys a false message.

Two further reasons:

- **It completes what B-21 was decided on.** #46 kept `model-capabilities` in the shared
  `aimon.llm.*` namespace *because a second consumer was planned*, and made the branch-level refusal
  the thing that "keeps the shared namespace honest". Delivering the second consumer is what makes
  that justification true rather than pending.
- **It changes the standing of an open backlog item without closing any of it.**
  `llm-config-surface-open-items.md` L-3 is about declarations that reach no branch at all, and its
  two cases are `provider=none` and an application that supplies its own `LlmClient` bean. **This
  change removes neither** — the Anthropic branch is not one of them. What it removes is the
  *mechanism* L-3's "어디" paragraph cites as the context for the whole item:
  `AimonLlmAutoConfiguration.refuseModelCapabilities`, which after this change no longer exists and
  can no longer be pointed at as the illustration of the two conflicting rules L-3 is built on. The
  item's substance is untouched and it stays open; only its worked example goes. That distinction has
  to be right before the note §8.5 promises is written, because that file is the canonical
  open/closed record and a note claiming a case was closed would make it wrong.

### 7.2 What it is, concretely

No new keys, no new types, no new spelling. The existing `llm.modelCapabilities.<model>` (CLI,
camelCase) and `aimon.llm.model-capabilities.<model>` (starter, kebab-case) already translate to
`Map<String, ModelCapabilityDeclaration>` and already reach
`InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)`. The Anthropic branches simply call
the same translator and pass the result to a new `AnthropicConfig.Builder.modelCapabilityRegistry(...)`.

Provider-scoping is **not** revisited: §2.7's two tests still both answer no — the key's name is a
neutral SPI's name, and its meaning ("what does this model's request surface accept") is identical
across vendors. That is now demonstrated rather than predicted.

---

## 8. Concrete changes, by module and file

### 8.1 `aimon-core`

| File | Change |
|---|---|
| `llm/capability/InMemoryModelCapabilityRegistry.java` | Add the five measured prefixes plus `claude-mythos` to `builderWithDefaults()` — five, not six, because `claude-fable-5` covers `claude-fable-5-1`, and one private `SAMPLING_REFUSED` constant. Extend the class javadoc's "what is in the table" paragraph: it currently says the table describes `gpt-5-chat`, `gpt-5` and the o-series, which stops being true. |
| `llm/capability/package-info.java` | One sentence: the table now describes two vendors. The existing fail-open and overridable rules are unchanged and need no edit. |

Nothing else in the package moves. `ModelCapabilities` gains no field; `ModelCapabilityRegistry`,
`ModelCapabilityDeclaration` and `withDefaultsExtendedBy` are untouched.

### 8.2 `aimon-llm-anthropic`

| File | Change |
|---|---|
| `AnthropicConfig.java` | **(D-3)** `temperature` → nullable `Double`, `getTemperature()` → `Optional<Double>`, delete `DEFAULT_TEMPERATURE`, keep range validation for a set value. **(D-5)** New `modelCapabilityRegistry` field defaulting to `InMemoryModelCapabilityRegistry.withDefaults()`, its builder setter and getter — copied from `OpenAIConfig` including the javadoc's reason (a gateway renames models and only its operator knows what they are). Update `equals`/`hashCode`/`toString`. |
| `AnthropicLlmClient.java` | Hoist the resolved model name into a local in `buildRequest` (it is currently computed inline in the builder call) and pass it down. Add `capabilitiesFor(String)` — the defensive wrapper `OpenAILlmClient` already has: a null or throwing registry degrades to `unknown()` **and reports**, because a silent degradation beside a reported one teaches an operator that lookups never fail. Rework `applySamplingParameters` per §4.1. |
| `AnthropicRequestParameters.java` *(new, package-private)* | The suppression rule and its two messages, so the client method stays about assembly. Mirrors `OpenAiRequestParameters` in role, not in shape — there is one endpoint here, so no `SamplingSink` indirection is warranted; a `MessageCreateParams.Builder` is passed directly. Create it only if the client method grows past readability; the alternative (leave it in the client, as today) is fine and is the smaller diff. |

The two new divergence signatures include the model name, matching OpenAI's convention
(`temperature=0.7@claude-opus-5`), so the same value against two models is said twice — the once-per-
signature register is per client and a client has one config but `LlmModel` can override the name.

Message wording, following #44 §2.5's correction that it must not say "configured" (a subagent's
`0.7` is set by `SubagentLlmDefaults`, not by an operator):

```text
temperature 0.7 is set on this request but claude-opus-5 does not accept sampling parameters;
it is being omitted and the call will succeed without it.
```

With D-3 there is no second wording to write: when nobody sets a value there is nothing to suppress
and nothing to say, so the request is silent by construction. **Without D-3**, the
`clientTemperatureOmittedForThinking` split has to be duplicated for the suppression path for the
same reason it exists today.

### 8.3 `aimon-cli`

| File | Change |
|---|---|
| `factory/LlmClientFactory.java` | Delete `refuseModelCapabilitiesForAnthropic` and its call. In `createAnthropicClient`, do what `openAiConfig` does: build declarations, and when non-empty pass `registryFor(declarations)` to the builder. Extract the Anthropic assembly into a package-private `anthropicConfig(LlmProviderConfig)` so a test can inspect the config without `AnthropicLlmClient` publishing it — the reason `openAiConfig(...)` is package-private, stated in its javadoc. |

`ModelCapabilityConfig`, `LlmProviderConfig` and `CliConfigLoader.resolveModelCapabilityKeys`
(which resolves `${VAR}` in map keys and rejects collisions) are unchanged and now serve both
providers.

### 8.4 `aimon-spring-boot-starter`

| File | Change |
|---|---|
| `autoconfigure/AimonLlmAutoConfiguration.java` | Delete `refuseModelCapabilities` (declared at `:118`) and its **single** call site (`:138`, inside `AnthropicConfiguration`); it is referenced nowhere else in the module. In `AnthropicConfiguration.aimonAnthropicLlmClient`, add the two lines `OpenAiConfiguration` has: `if (!llm.getModelCapabilities().isEmpty()) config.modelCapabilityRegistry(AimonProperties.modelCapabilityRegistry(llm));`. |

**The nested-class placement rule applies and must not be broken.** `openAiConfig(...)` lives inside
`OpenAiConfiguration` because Spring calls `getDeclaredMethods()` on a configuration class and would
otherwise load `OpenAIConfig` in a deployment carrying only the Anthropic module. Any new
Anthropic-side helper with `AnthropicConfig` in its descriptor must likewise stay inside
`AnthropicConfiguration`. `AimonProperties.modelCapabilityRegistry(...)` returns a core type, so it
is safe on the enclosing class where it already is.

### 8.5 Documentation

`docs/design/` is not a translation target, so the new design has no `.en.md`. The three
getting-started / features pairs do, and **both halves are edited in the same commit**, with the
translation's `source_commit` set to the canonical file's commit immediately before this one.

| File | Change |
|---|---|
| `docs/design/llm/anthropic-sampling-capabilities.md` *(new)* | This document, in English, matching the two siblings it continues. |
| `docs/design/README.md` | Index row. |
| `docs/design/llm/anthropic-thinking-traces.md` | §2.3's correction block and §9 U-2 corrected **in place** with the Control B measurement (§2.5 above) — the house form, and the same form those two lines already use. §8 F-2 marked done with a link. §10.2's mutation table gains the new rows. Also §3.5, whose "the existing clamp stays as it is on the non-thinking path" sentence now needs "when the model accepts sampling". |
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` **+ `.en.md`** | The `llm.modelCapabilities` section says the block is openai-only and that a declaration under `provider: anthropic` is a `ConfigurationException`. Both stop being true. |
| `docs/getting-started/embedding-agent-in-application.md` **+ `.en.md`** | Same, for `aimon.llm.model-capabilities`; the §4 example can now carry the block under `provider: anthropic`. |
| `docs/features/llm/llm-provider-development-guide.md` **+ `.en.md`** | Its "providers do not invent values" rule now has two conforming implementations instead of one. Small edit; optional but cheap, and the guide is the reason D-3 exists. |
| `docs/backlog/llm-config-surface-open-items.md` | L-3 narrows — record which branch case went away and why, per that file's own rule that the backlog is the canonical open/closed record. |
| `docs/backlog/spring-boot-starter-open-items.md` | One line under B-21: its decision has now been exercised by a second consumer, as predicted. No re-opening. |
| `CHANGELOG.md` | `[Unreleased]`. Must say four things: the six models are reachable now; `temperature` is refused **by value** and `top_p` **by presence**, correcting what the #47 entry says; `AnthropicConfig.getTemperature()` is breaking; and unknown/renamed models are still on the fail-open path with the one-line remedy. |

**The #47 CHANGELOG paragraphs have not shipped, and that changes what to do with them.** An
earlier draft of this section proposed leaving them standing as "an accurate record of what that
release shipped". They are not a record of a release: `## [Unreleased]` is `CHANGELOG.md:8` and the
next version heading is `## [0.2.4]` at `:974`, so **both** the wrong correction (`:274-275`,
*"the parameter is refused outright rather than compared against a default"*) and the original,
now-vindicated note (`:353-354`, *"any non-default `temperature` returns 400"*) sit in the same
unreleased section. Following the earlier instruction would ship one release note that says the
parameter is refused outright, that it is refused by value, and that the first statement corrects
the second.

So: **edit `:274-275` in place.** That block is a correction that turned out to be the error, it has
never been published, and editing an unreleased note that is simply wrong is what this repository
does rather than layering a third statement on top. The note at `:353-354` is **vindicated and
stays** — it was right before it was "corrected", and the new entry should say so, because a reader
who saw the intermediate state deserves to know which reading won. §2.5 is where the reasoning
lives; the CHANGELOG carries the outcome.

**Not needed:** `docs/migration/rename-maps.md`. Nothing is renamed; a return-type change is a
CHANGELOG matter under `api-stability.md` §5.

---

## 9. Failure modes

| # | Failure | Handling |
|---|---|---|
| 1 | A caller-supplied registry throws or returns null from `resolve` | `capabilitiesFor(...)` catches, reports a divergence, returns `unknown()`. Copied from `OpenAILlmClient`, and for its stated reason: the call site is outside the streaming try-with-resources, so an escaping exception would bypass both the exception mapper and the cancellation classification. |
| 2 | An unknown / renamed refusing model | Fail-open: still 400. §6. Remedy is the config block (D-5) or the programmatic setter. Documented, not silently absorbed. |
| 3 | A future Anthropic model that refuses sampling and matches no prefix | Same as 2. The price of never guessing; the table is a maintained fact, not an oracle. |
| 4 | The Mythos row is wrong | The configured value is dropped and one WARN is emitted; the call succeeds. The cheaper of the two possible mistakes (§3.3). |
| 5 | A caller explicitly sets `temperature: 1.0` on a refusing model | Suppressed and warned, though the API would have accepted it (§2.5). Known over-approximation of the one-bit flag; identical to the OpenAI side and documented in the same words. |
| 6 | `top_p` inside `[0.95, 1.0]` with thinking on, refusing model | Fixed by §4.1's gate ordering. This is the one live 400 that would survive a `temperature`-only fix, so it gets its own test. |
| 7 | Somebody "omits" by passing null | Unreachable by construction — `MessageCreateParams.Builder` exposes `temperature(Double)` and `temperature(JsonField<Double>)`, and the rule is *never call the setter*. Now measured rather than asserted (Control C), including on models that accept sampling. Pinned by asserting key **absence** on the serialised body. |
| 8 | The divergence register (32 signatures) fills | Pre-existing bound. Signatures now carry the model name, so a deployment cycling many model names could reach it sooner. Same exposure as the OpenAI client; not widened enough to redesign the register. |
| 9 | A key-holder's `checkAll` goes red on `AnthropicThinkingLiveTest` | Real, and the implementation must handle it: `offCannotReachAnAlwaysOnModel` asserts the defect. Inverted, not deleted (§9.5 below / §2.5). |
| 10 | A deployment relying on the CLI/starter refusal | Behaviour change: a config that failed fast now boots and works. Intended; noted in the CHANGELOG for completeness. |
| 11 | D-3 changes sampling on the five accepting models | Real and user-visible (§5.3). CHANGELOG, and the remedy is one line: set the temperature you want. |

---

## 10. Test strategy

**The two non-negotiables carried from both siblings.** Absence is asserted on the **serialised
request body** (`AnthropicFixtures.bodyTreeOf(params)` / `bodyOf(params)`), never on
`params.temperature().isEmpty()` — the SDK's `getOptional` collapses `JsonMissing` and `JsonNull`, so
an implementation that put `"temperature": null` on the wire and earned the exact 400 this design
removes would pass an `isEmpty()` assertion. And fixtures are deserialised from JSON, not built with
mocks. `AnthropicThinkingRequestTest` already works this way and says so in its javadoc.

### 10.1 `aimon-core` — `InMemoryModelCapabilityRegistryTest`

- Each of the six measured names resolves to `supportsSamplingParameters() == false`.
- **The five accepting names resolve to `unknown()`** — `claude-opus-4-5-20251101`,
  `claude-sonnet-4-5-20250929`, `claude-haiku-4-5-20251001`, `claude-sonnet-4-6`, `claude-opus-4-6`.
  This is the prefix-trap test and it is the one that would catch a `claude-opus-4` row.
- A dated snapshot inherits its prefix (`claude-opus-5-20260101`).
- Case folding (`Claude-Opus-5`).
- The OpenAI rows are byte-identical to before — the existing fail-open regression test extended, not
  replaced.
- A configured declaration for a `claude-*` name beats the built-in prefix (the exact > prefix rule,
  already tested for OpenAI; one Anthropic row so the shared rule is shown to be shared).

### 10.2 `aimon-llm-anthropic` — new `AnthropicLlmClientSamplingCapabilityTest`

- Refusing model + `LlmModel.temperature(0.7)` → body has **no** `temperature` key; one WARN naming
  the value and the model.
- Refusing model + `LlmModel.topP(0.9)` → no `top_p` key; one WARN.
- Refusing model + thinking on + `topP(0.98)` → no `top_p` key (failure mode 6).
- Accepting model + `temperature(0.7)` → `temperature == 0.7` on the body; silence.
- Unknown model + `temperature(0.7)` → `temperature == 0.7` on the body; silence. (Fail-open, byte
  comparison.)
- `ModelCapabilityRegistry` that throws → fail-open **and** one WARN.
- Two sends, one WARN (the once-per-signature register).
- Two different models, two WARNs (the signature carries the model).

### 10.3 Existing tests that must change

Naming them because each is a place the change is visible, and a test that has to be edited is a
claim about behaviour, not a chore:

| Test | Why it moves |
|---|---|
| `AnthropicThinkingRequestTest` line ~113 | A byte-identical body assertion containing `"temperature":0.0`. Under D-3 the key is gone. This is the single most direct expression of what D-3 changes and the new literal is the documentation. |
| `AnthropicThinkingRequestTest.temperatureOmissionNamesWhoseValueItWas` | Its second half asserts the "No temperature was set on this call…" wording. Under D-3 that state produces **silence**; the assertion becomes "nobody set anything, so nothing is said". |
| `AnthropicConfigTest` (three cases) | `getTemperature()` default is now empty; a set value round-trips through `Optional`; range validation still fires when set. |
| `AnthropicLlmClientParameterDivergenceTest.honouredConfigIsSilent` | Still passes, but its premise changes — worth re-reading rather than assuming. |
| `LlmClientFactoryTest` / `AimonPropertiesValidationTest` (or wherever the two refusals are pinned) | The refusal tests are deleted and replaced by "the declaration reaches the Anthropic client". |
| `AnthropicThinkingLiveTest.offCannotReachAnAlwaysOnModel` | §9.5. |

### 10.4 Verified by mutation

House form (`anthropic-thinking-traces.md` §10.2, and commit `200b05f`): introduce the bug, watch the
named test go red, revert. "The test exists" and "the test would have caught it" are different claims.

| Mutation | Goes red |
|---|---|
| Drop the capability check and always set `temperature` | the refusing-model body assertion in `AnthropicLlmClientSamplingCapabilityTest` |
| Suppress unconditionally (ignore the flag) | the **accepting-model** and **unknown-model** cases — the fail-open pair |
| Set `temperature(null)` instead of not calling the setter | the body-absence assertion (this is exactly what Control C measured, and what an `isEmpty()` assertion would miss) |
| Gate `top_p` on the thinking window before the capability check | the thinking-on + refusing-model case |
| Register `claude-opus-4` instead of `claude-opus-4-7` / `-4-8` | the five-accepting-names case in `InMemoryModelCapabilityRegistryTest` |
| Suppress without reporting | the WARN assertions (criterion 2) |
| Restore `DEFAULT_TEMPERATURE = 0.0` in `AnthropicConfig` | the byte-identical body case in `AnthropicThinkingRequestTest` |

### 10.5 Live, key-gated

`AnthropicThinkingLiveTest` gains one nested class and loses a pin:

- `offCannotReachAnAlwaysOnModel` → **inverted**: a default-config client against `claude-sonnet-5`
  with thinking off now **succeeds**. Its comment should keep the old message string as history —
  that sentence is why the test existed — while asserting the opposite outcome.
- New: an explicitly set `temperature(0.7)` against `claude-sonnet-5` **succeeds and warns**. This is
  the live half of criterion 2 and the only place the suppression is proven against the real server
  rather than against a serialised body.
- New negative control beside it, so acceptance is evidence: the same call with the registry replaced
  by `ModelCapabilityRegistry.EMPTY` **fails** with `` `temperature` is deprecated for this model ``.
  Without this, "the call succeeded" is equally satisfied by a client that sends nothing for
  unrelated reasons — the same asymmetry `mutatedSignatureIsRejected` exists to close.

**Cost note for the implementer:** the refusal half of that control is rejected before generation and
costs nothing; only the positive call generates, and it needs one short message and a small
`max_tokens`.

**And it widens F-8.** `anthropic-thinking-traces.md` §8 F-8 records that there is no fourth test
tier for live, paid, key-gated tests, so `AnthropicThinkingLiveTest` runs inside `./gradlew test`
whenever `ANTHROPIC_KEY` is exported and a key-holder's `checkAll` — and the release gate — makes
real billed calls. These two additions make that surface larger rather than smaller. Nothing here
fixes it; F-8 is blocked on a policy decision about the gate, not on code. Said so the item stays
honest about its own size.

---

## 11. What has **not** been measured

Kept in the form the sibling designs use, because the value of a measured table is entirely in
knowing which rows are not.

- **U-1 — the three Mythos rows.** `claude-mythos*` is documentation-derived. No Mythos model appears
  in this account's `/v1/models`, so neither the fact (do they refuse?) nor the identifier shape (is
  the id `claude-mythos-5-1`?) was probed. The prefix is chosen so that only the fact is asserted;
  §3.3 argues why the fact is worth asserting and failure mode 4 says what a wrong row costs.
- **U-2 — dated snapshots of the six refusers.** All six are undated aliases in the listing. The
  prefix rows assume a snapshot would be `claude-opus-5-<date>` and inherit the row. Unverified: no
  such name was visible to call.
- **U-3 — `top_k`'s boundary.** `top_k: 5` is a 400 on all six; no "default" value was tried because
  `top_k` has no documented default. So `top_k` is measured to be *refused at a real value*, not
  measured to be *refused by presence* the way `top_p` is (§2.2 probed `top_p: 1.0`). It changes
  nothing today — nothing in AIMON can send `top_k` (§4) — and is recorded so a future
  `LlmModel.topK` does not inherit an assumption.
- **U-4 — the sampling rules under thinking on the five accepting models.** The vendor says the older
  restriction applies only while thinking is on. #47's existing gate implements it and this round did
  not re-probe it; every §2 cell was measured with thinking off (no `thinking` parameter sent).
  Unchanged behaviour, but it means §2's table describes the thinking-**off** surface only.
- **U-5 — whether the six refuse `temperature` at values between 0.5 and 1.0.** Only `0.0`, `0.5` and
  `1.0` were sent. The inference that "only the default is accepted" comes from three points and the
  identical OpenAI finding, not from a sweep. It does not matter for the design — suppression is
  all-or-nothing — but the table should not be read as a boundary.
- **U-6 — nothing here was measured through `AnthropicLlmClient`.** The probes are raw HTTP. The
  claim that this client's request body reaches the same verdict rests on the unit tests asserting
  the serialised body plus the live tests at §10.5, and the live tests are the only place the two
  meet. On a machine with no key, the body assertions are the whole of it.
- **U-7 — the wire effect of D-3 on real output quality.** That temperature `1.0` replaces `0.0` for
  unconfigured deployments is certain; what it does to agent behaviour is not measured and is a
  judgement the CHANGELOG should let operators make for themselves.

---

## 12. Alternatives rejected

| # | Alternative | Why not |
|---|---|---|
| **A1** | **A new `AnthropicModelCapabilityRegistry` / `AnthropicModelFacts` type in the vendor module.** | The fact is already expressible and already vendor-neutral, the sibling per-model registries in core already carry `claude-*` rows, and #46 spent a backlog decision on the promise that this consumer was coming (§3.1). A second registry also splits the config surface, which would then need a second key and would make #46's B-21 answer retroactively wrong. |
| **A2** | **`model.startsWith("claude-opus-5") \|\| …` inside `buildRequest`.** | The thing #43 round 1 removed from the OpenAI builder and #47 §8 A2 refused on this side. It puts vendor knowledge where no other provider can reach it, and it is wrong for every gateway that renames a model — which is exactly the population the fix has to serve. Rejected on precedent, and the precedent is right. |
| **A3** | **Suppress sampling for all Anthropic models unconditionally.** | Fails criterion 1 ("not suppressed globally"), and it is measurably wrong: five of eleven reachable models accept all three parameters. It would silently drop a working setting on every Claude 4.x deployment. |
| **A4** | **Add a sixth `ModelCapabilities` field for the thinking dialect while in here** (i.e. do F-1 too). | Out of scope by instruction and by shape. The dialect axis is the one where both values 400 and there is no fail-open value — the reason #47 said it needs a design round. Sampling has a safe direction; conflating them would import that problem into a change that does not have it. F-1 stays open. |
| **A5** | **Keep `AnthropicConfig.temperature` at `0.0` and rely on the registry alone.** | Viable, and it is what a reviewer who declines D-3 gets — §5.4 says exactly what that costs. Not chosen because the client would keep breaking a rule the repository's own provider guide states in those words, and because `0.0` is the measurably worst value to invent (§2.6). |
| **A6** | **Split `supportsSamplingParameters` into per-parameter flags**, now that `temperature` (by value) and `top_p` (by presence) are measurably different. | The split changes no behaviour: both are suppressed on the same six models. It would buy only the ability to keep sending `temperature: 1.0`, i.e. to send the one value that is indistinguishable from omission. A new capability shape for zero wire difference. The over-approximation is documented instead, the way OpenAI's already is. |
| **A7** | **Fail-closed for unknown models** (suppress when nothing is known). | Locally the cheaper mistake (§6), but the constant is shared with OpenAI and pinned by a test; flipping it would stop sending `temperature` for every undescribed OpenAI model. A regression in another module riding on a bug fix. |
| **A8** | **Route the two penalties through the capability check.** | Replaces a true, specific message ("Anthropic has no counterpart") with a vaguer one, and makes them silent on the five models that accept sampling — where they are still dropped. §4. |
| **A9** | **Ship the config surface as a follow-up issue.** | Legitimate in general and the shape #47/#46 used. Rejected here on one fact: both surfaces already contain a guard that becomes false and that refuses the configuration this fix is for, so the split does not avoid touching those files — it only decides whether they end up correct. §7.1. |
| **A10** | **Delete `AnthropicThinkingLiveTest.offCannotReachAnAlwaysOnModel`** rather than invert it. | The test is the live record that the defect was real. Inverting it keeps the record and turns it into the regression guard; deleting it loses both. |

---

## 13. Open questions — what the task statement does not settle

Listed rather than assumed, per the brief.

1. ~~**Does D-3 ship here?**~~ **Shipped.** The task's scope covers `aimon-llm-anthropic`, the issue
   text names the defect, and the provider guide forbids the current shape in those words. §5.4's
   fallback was not needed. What that costs a deployment that never configured a temperature is in
   §5.3 and in the CHANGELOG.
2. ~~**Should the Mythos row ship at all?**~~ **Shipped**, as one `claude-mythos` family prefix,
   labelled documentation-derived in the source comment beside it and pinned by
   `theMythosRowIsAFamilyPrefix`. §3.3 is the argument; cutting it later still costs nothing but a
   `-1` in the table.
3. **Which prefix granularity for the Fable family?** `claude-fable-5` covers `claude-fable-5` and
   `claude-fable-5-1`, both measured. A bare `claude-fable` would also cover a future Fable 6 — which
   would be right if Fable 6 refuses and wrong if it does not. Chosen: `claude-fable-5`, asserting
   only what was measured. Same question will recur for `claude-opus-5` vs `claude-opus`.
4. **Should the CLI/starter also refuse a declaration when `provider=none`?** That is the untouched
   half of backlog L-3 and it is a policy question about the shared namespace, not about Anthropic.
   Left alone.
5. **Does `AnthropicConfig` need `topP` / penalties as config-level fields** the way `OpenAIConfig`
   has them? Today they exist only on `LlmModel`. Out of scope; it is backlog L-2's second row
   (`aimon.llm.<provider>.*`), and nothing in this issue needs it.
6. **Is `claude-opus-4-6` / `claude-sonnet-4-6` really safe to leave undescribed?** They accept all
   three with thinking off (measured) and they are the pair that accepts *both* thinking dialects, so
   under thinking they follow the older restriction that #47's gate already implements. No row is
   needed. Recorded because it is the one place where "no row" is a decision rather than an absence.
7. **`ANTHROPIC_KEY` vs `ANTHROPIC_API_KEY`.** The tests read `ANTHROPIC_KEY`; `AnthropicConfig`'s
   own javadoc example reads `ANTHROPIC_API_KEY`. Pre-existing, cosmetic, and not this issue's — but
   an implementer running the live suite will trip over it once.

---

## 14. Handoff — what this makes cheaper

- ~~**#54** (`thinkingMode` / `thinkingBudgetTokens` / `replayThinkingBlocks` config keys) says it is
  blocked on this one.~~ — **CLOSED** by
  [`anthropic-thinking-config-surface.md`](anthropic-thinking-config-surface.md). The block this
  bullet recorded was lifted when this document's own work landed, and the two things it predicted
  #54 would inherit are the two it actually used: the worked example of carrying a knob from both
  surfaces into `AnthropicConfig.Builder`, and a vendor config whose surface had just been reviewed.
  One prediction was wrong in a way worth keeping — the keys did **not** land beside the shared ones.
  All three names carry Anthropic concepts, so they went to `aimon.llm.anthropic.*` / `llm.anthropic.*`,
  the first split the `model-capability-config-key.md` §2.7 criterion has produced.
- ~~**F-1** (the thinking dialect as a per-model fact)~~ — **CLOSED** by
  [`reasoning-model-enablement.md`](reasoning-model-enablement.md) §3 (#60). This bullet said F-1 was
  unblocked in one respect and unchanged in the other: the table had gained `claude-*` rows, but
  `ModelCapabilities` still had no field whose fail-open value is safe for a two-valued mutually
  exclusive axis. The second half was answered by refusing its premise — the axis is three-valued now.
  `ThinkingDialect.UNKNOWN` is not a dialect but the absence of the fact, so it is safe as the value a
  model nobody has described falls back to, and the six `claude-*` rows this document put in the table
  are where the new field's `ADAPTIVE` now sits.

---

## 15. Where the implementation departed from this design

Written after the fact, in the form `openai-model-capabilities.md` §8 and `anthropic-thinking-traces.md`
§13 use. **Six entries, of which four are departures** — the four in `build/deviations.md`, namely §15.1
(a judgement call this document delegated), §15.2 (an instruction here that would have been a bug),
§15.3 (a defect this change created and then fixed) and §15.5 (a smaller instruction implemented as its
larger sibling). The remaining two are not departures and are recorded so nobody reads them as one:
§15.4 qualifies a prediction this document made that came out half right, and §15.6 records a choice
that looks like the opposite of §12 A10 and is not. **Nothing here reverses a decision.**

### 15.1 `AnthropicRequestParameters.java` was **not** created

§8.2 made this a judgement call — *"create it only if the client method grows past readability; the
alternative (leave it in the client, as today) is fine and is the smaller diff"*. It was not created.

The reason is that the two gates are **one rule**, not two. `OpenAiRequestParameters` exists because
its rule is shared by two endpoints and copying it would give it a second place to drift; there is one
endpoint here, so a new file would buy no de-duplication and would split a single nested decision —
capability first, thinking rules inside it — across two files, with the `MIN_TOP_P_WITH_THINKING`
constant and the `reportDivergence` register left behind in the client. `applySamplingParameters` grew
from 38 lines to 52 including the new branch. `reportSuppressedSampling` and `capabilitiesFor` are
siblings of it in the same class, which is where `reportDivergence` and the register they use already
live.

If a second Anthropic endpoint ever appears, the OpenAI reasoning applies unchanged and the extraction
becomes right for its own reason rather than for this one.

### 15.2 `modelCapabilityRegistry` is absent from `equals`, `hashCode` **and** `toString`

§8.2 said to update all three for the new field. The field is excluded from all three instead, and for
`equals`/`hashCode` the alternative is a bug: no `ModelCapabilityRegistry` implementation defines
`equals`, and `AnthropicConfig.Builder` calls `InMemoryModelCapabilityRegistry.withDefaults()` per
build, so counting the collaborator would make two configs that agree on every value an operator can
write unequal to each other — which is what **two** existing `AnthropicConfigTest` cases assert they are
not (`shouldBeEqualToConfigWithSameValues` and `thinkingSettingsParticipateInEquality`; both go red if
the field is added back). `OpenAIConfig` sidesteps the question by having no `equals` at all. Pinned by
the new `theRegistryDoesNotParticipateInEquality`.

`toString` is left out for a weaker reason and it has a cost worth stating: no registry defines
`toString` either, so the line would carry an identity hash and no information — but the consequence is
that an operator asking *"why is my capability declaration not taking effect"* gets no hint from a
logged config. What answers that question is the WARN naming the model whose value was dropped, or its
absence. The comment at the equality site says so.

### 15.3 The `ADAPTIVE` half of `reportIfReplayIsOffWhileThinking` said something this change made false

Not anticipated by this document, and it is the kind of thing it warns about elsewhere: a warning that
names a failure the server does not produce teaches an operator to distrust the next one. That message
read *"thinkingMode(OFF) is not an escape on this dialect: the models that accept adaptive thinking
reject the temperature this client sends when thinking is off"*. After D-1 and D-3, `OFF` **is** an
escape for every adaptive-only model the table describes — the client no longer sends the parameter.

**The reword was itself still over-claiming, and review round 1 caught it.** It said that for a model no
registry names, turning thinking off *"makes every request fail"*. After D-3 that is false in the common
case: with nothing configured the client sends no sampling parameter at all and the request succeeds.
The 400 needs **both** halves — an undescribed model *and* something actually carrying a value, which
means an operator-set temperature or a subagent turn, since `SubagentLlmDefaults.resolveModel` always
puts one on the request. The third and current wording names that condition, says the parameter is
simply not sent otherwise, and points at the one-line remedy (declare the deployment's real name).

The javadoc records all three wordings. Introducing this same fault twice — once by predicting a
rejection the server does not produce, once by surviving D-3 unchanged and over-claiming the other way —
is the argument for writing it down rather than quietly landing a third sentence.

### 15.4 Both temperature wordings survive, and the second one changed job

§8.2 predicted *"with D-3 there is no second wording to write"*, and that is true of the **suppression**
path: it has one sentence, and a request nobody set a value on is silent. The thinking path keeps both,
because the split is still real — it now separates "the call set this" from "the client config set
this", rather than "the call set this" from "nobody set this and the config invented 0.0". The third
state, nobody anywhere, is the one that became silence, and `noTemperatureAnywhereIsSilent` is the test
that used to be the second half of `temperatureOmissionNamesWhoseValueItWas`.

### 15.5 The starter got an `anthropicConfig(...)` helper, mirroring `openAiConfig(...)`

§8.4 called for two lines inside `AnthropicConfiguration.aimonAnthropicLlmClient`. The assembly moved
into a package-private `AnthropicConfiguration.anthropicConfig(AimonProperties.Llm)` instead — the same
shape `OpenAiConfiguration` already has, for the same two reasons: a test can inspect the built config
without `AnthropicLlmClient` publishing it, and **the nested-class placement rule §8.4 insists on is
what makes that safe**, since `AnthropicConfig` in the method descriptor must not be loaded in a
deployment carrying only the OpenAI module.

### 15.6 Both refusal tests were deleted rather than inverted

§10.3 said the two refusal tests are *"deleted and replaced by 'the declaration reaches the Anthropic
client'"*, and that is what happened —
`LlmClientFactoryTest.refusesADeclarationUnderAnthropic` and
`AimonAutoConfigurationTest.anthropicBranchRefusesCapabilityDeclarations` are gone. Recorded here
because it is the opposite treatment from A10's, which kept
`AnthropicThinkingLiveTest.offCannotReachAnAlwaysOnModel` by inverting it. The difference is what each
test was a record of: the live test recorded a **defect**, which is worth keeping as a regression
guard, while these two recorded a **policy** that no longer exists — inverting them would leave a test
asserting the absence of a message nothing ever emits.
