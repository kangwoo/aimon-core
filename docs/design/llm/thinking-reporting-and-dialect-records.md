# Design — what the Anthropic thinking path tells an operator, and the records behind it

> Status: **IMPLEMENTED.**
>
> **§15, appended after the build, is where this document is wrong.** Everything above it is the body
> as reviewed and approved, kept byte-exact rather than corrected — including one "confirmed against
> history" that history does not confirm (§12) and one miscount of the reads that had to change (§7).
> §15 records where the implementation departed from it and why, and carries the corrections the
> design review found.

> Target: `aimon-core` (`at.aimon.core.llm.capability`), `aimon-llm-anthropic`, `aimon-cli`,
> `aimon-spring-boot-starter`, and four documents.
>
> **The measurement in §2 and §5 was made against the live API**, on 2026-09-10, three times: once for
> this document (the raw record is §14 below), once by the design review, and once more before any row
> was written. All three agree in every cell. No key appears in this repository, in a fixture, in a log
> or in a commit.
>
> Prior documents this one continues and does not restate:
> [`reasoning-model-enablement.md`](reasoning-model-enablement.md) §3 (the mode × dialect table this
> extends), §7 (the failure modes it adds to), §9 U-1 (the discharge it completes);
> [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §2.1 (the vendor's per-model table);
> [`reasoning-effort-config-surface.md`](reasoning-effort-config-surface.md) (the
> `lowestReasoningEffort` → `acceptedReasoningEfforts` precedent §10 A2 weighs and refuses).

*(#68 · #73 · #75. Companion measurement record: §14.)*

---

## 1. The problem, in one paragraph

Three warning paths in `AnthropicLlmClient` describe a request other than the one that is sent — one
announces a dialect translation before a later step may abandon the whole thinking parameter, one
drops a configured effort on a translated adaptive request and says nothing, and one is silent about a
combination nobody decided was worth silence in writing (#68). The capability table those warnings
read is half-empty and half-wrong about itself: its `BUDGETED` side has no shipped row while three
model families are measured to speak that dialect, and two models are measured to accept **both**
dialects — a fact `ThinkingDialect` has no value for, so they read as `UNKNOWN`, which means *the table
cannot answer* and is now doing double duty (#73). Around them sit three small records the same PR
stack left behind: two duplicated `values()` folds with no cross-reference, a `builderWithDefaults()`
javadoc that does not name the one model its documented override no longer reaches, and a backlog
index whose numbers disagree with the files it indexes (#75). All of it lives in five files, and #73's
measurement is what forces #68's contract to be decided rather than patched three times, because the
new rows create the first request that is translated *out of preference* rather than out of necessity —
and the existing warning text asserts necessity.

---

## 2. The measurement comes first, because two decisions turn on it

Full method, raw results and two reproducibility passes are in §14. What the
design needs from it:

**The live API agrees with issue #73 on every cell the issue states.** All eleven models, both request
shapes, two passes, identical answers. Nothing below rests on the pasted table; §5 says how the
citation records that.

**Four things the issue does not contain, two of which change the shape of the change:**

| # | Measured | Consequence for this design |
|---|---|---|
| M-1 | The undated aliases `claude-opus-4-5` / `claude-sonnet-4-5` / `claude-haiku-4-5` **resolve** (to the dated snapshots) and speak `BUDGETED`. They are not in `GET /v1/models`. | The new rows are **prefixes**, covering six measured names with three rows — and `claude-sonnet-4-5`, the string two test classes use as their *undescribed* model, stops being undescribed. §6.2 |
| M-2 | `thinking:{"type":"adaptive"}` **alone** gives the same 400 as with `output_config`, on all eleven. | Issue #73's shape A varied two fields; the control says the 400 is attributable to `thinking.type`. A control that passed, recorded because it could have failed. |
| M-3 | `output_config.effort` **without** `thinking` is a 400 on `claude-haiku-4-5` and `claude-sonnet-4-5` (*"This model does not support the effort parameter."*) and a 200 on `claude-opus-4-5`. | `buildRequest`'s existing `thinking.filter(isAdaptive)` guard around `outputConfig` is now **measured** load-bearing rather than tidy. It gains a test; it does not change. §9 |
| M-4 | Guessed dated 4-6 snapshots (`claude-opus-4-6-20260204`, `claude-sonnet-4-6-20260217`) **404**. | Nothing is claimed about dated 4-6 names; the prefix rows cover them by inheritance if they appear, which is the reason the `ADAPTIVE` block already gives for using prefixes. |

**And one thing that is not a probe but decides §4.** The vendor's per-model table (quoted at
`anthropic-thinking-traces.md` §2.1) marks Opus 4.6 and Sonnet 4.6 as `Adaptive, extended
(deprecated)`, and the vendored SDK's `com/anthropic/helpers/ThinkingWarnings.kt` says which way the
preference runs and why:

> `WARNING: Using Claude with claude-opus-4-6 and 'thinking.type=enabled' is deprecated. Use
> thinking.type=adaptive instead **which results in better model performance in our testing**`

It is called from `MessageServiceImpl` / `MessageServiceAsyncImpl` on `params.thinking()` at **every**
`create` / `createStreaming`. So a `thinkingMode: extended` deployment on `claude-opus-4-6` gets that
line **once per LLM call, on stderr, outside SLF4J and outside this client's once-per-signature
register**, today. That is the second-best argument for giving these two models a row, and it is not in
either issue.

**One counting correction to #73's body.** It writes *"All six `ADAPTIVE` rows are confirmed"* and then
lists six model **names**. The registry has six ADAPTIVE prefix **rows**; the six names land on five of
them (`claude-fable-5-1` matches the `claude-fable-5` prefix), and `claude-mythos` remains unreachable.
Six names, five of six rows — which is exactly the distinction backlog L-6 records having been
corrected for in review. The design uses the corrected counts throughout.

---

## 3. Decision (a) — the reporting contract, two rules

Decided once, here, before any code moves. Both rules are stated generally enough to settle the next
pair without re-deriving, then applied to all three of #68's cases in §3.3.

### 3.1 R-WHEN — nothing is reported while the request is being assembled

> **A step that finds something *records* it. The finished request decides which records are
> *emitted*.** A record whose subject is the thinking parameter is emitted only if the finished request
> agrees with it: records that describe a thinking parameter are dropped when the finished request
> carries none, except the one record that says why it carries none.
>
> **Generally:** buffer a finding whenever a later step in the same resolution can change or discard
> what the finding describes. Report from the outside of that resolution, where the outcome is known.

Every finding is therefore one of two kinds, and the kind is part of the record:

| kind | meaning | emitted when |
|---|---|---|
| `REQUIRES_THINKING` | *the request differs from what you configured* | the finished request carries a thinking parameter |
| `EXPLAINS_ABSENCE` | *your thinking configuration reaches nothing at all* | it does not |

**This is not a new pattern in the file — it is a pattern the file already follows in one place.**
`reportIfReplayIsOffWhileThinking(thinking)` is called from `buildRequest` with the *final* parameter
(`AnthropicLlmClient.java:405`, resolved at `:371`). The three defective paths are the ones that report
from inside the resolution. The contract makes the outside the only place that can report.

**Dedup happens at emission, never at recording.** A signature consumed by a dropped record would
suppress that message for the life of the process. `reportDivergence` already adds-and-logs together
(`:967-970`); the contract only ever hands it surviving records, so this holds by construction — but it
is written down because the obvious mistake (dedup while collecting) reintroduces exactly the silence
this work removes. **The per-triple signatures are unchanged**: `thinkingDialectTranslated=<mode>-><dialect>@<model>`,
`thinkingBudgetOverridesEffort=<budget>@<effort>`, `thinkingBudgetImpossible=<maxTokens>`,
`thinkingBudgetClamped=<a>-><b>`, `thinkingDisplayOnBudgetedDialect=<display>`,
`thinkingDisplayWithThinkingOff=<display>`, `reasoningEffortWithThinkingOff=<effort>`,
`thinkingDialectUnknown@<model>`. The aim is fewer wrong messages, not fewer messages.

### 3.2 R-WHAT — when an inert or reshaped combination is worth a line

> An inert or reshaped combination is reported when **either**
>
> **(a)** there is a change the operator can make that would make the configured key take effect
> **without reversing another value they set** — the configuration is *incomplete*; or
>
> **(b)** without the report the operator would draw a **false conclusion from what they can
> observe** — absent output that looks like a bug, or present output that looks like proof.
>
> When the only available remedy is *"undo the other thing you asked for"*, the two keys are a
> **mutually exclusive pair rather than an incomplete configuration**, the operator has already chosen
> between them, and the client is silent.

The rule is decidable **from the configured values alone** — deliberately, because the tempting
alternative ("report when the neutralising setting is a default the operator did not write") is not:
`AnthropicConfig` carries no provenance for `thinkingMode`, whose default *is* `OFF`, so that rule
could not be implemented without widening the config type.

The rule reproduces every judgement already in the tree, which is the test of a rule stated after the
fact:

| combination | (a) remedy that reverses nothing? | (b) false conclusion available? | verdict | matches today |
|---|---|---|---|---|
| `reasoningEffort: <rung>` + `thinkingMode: off` | yes — *set a mode* completes the effort | — | **report** | ✅ `reportInertEffort` |
| `thinkingDisplay` + `thinkingMode: off` | yes — *set a mode* | — | **report** | ✅ `reportInertDisplay` |
| `thinkingDisplay` + `BUDGETED` dialect | no remedy is needed | **yes** — thinking text streams anyway, and would read as proof `display` went out | **report** | ✅ `reportDisplayOnBudgetedDialect` |
| `reasoningEffort: none` + `thinkingMode: off` | no — the only remedy reverses one of them | no | **silent** | ✅ `reportInertEffort`'s `NONE` exclusion |
| `thinkingDisplay` + `reasoningEffort: none` | **no** — the only remedy is *stop asking for no reasoning* | **no** — absent text is what `none` asked for | **silent** | ✅ #62's stated choice, now derived |

### 3.3 The contract applied to #68's three cases

**Case 1 — a translation announced before the request may still be abandoned. Fixed by R-WHEN, and it
has a second victim the issue does not name.**

Today, `thinkingMode: adaptive` against a `BUDGETED` model with `maxTokens ≤ 1024` emits, in order:
`thinkingDialectTranslated…` (*"the request is being translated to BUDGETED so the turn succeeds
instead of failing"*), then `thinkingDisplayOnBudgetedDialect…` (*"this request speaks the BUDGETED
dialect"*) — and only then does `resolveExtendedThinking` find no legal budget, emit
`thinkingBudgetImpossible…` and return empty. **The request speaks no dialect at all**, so the first
two lines are both false. The issue names `resolveNamedDialect`; `reportDisplayOnBudgetedDialect`
(`:539`, called before `:540`) is the same defect and the contract fixes both without naming either.

Under R-WHEN the translation, the display-on-budgeted, the budget-overrides-effort and the clamp are
all `REQUIRES_THINKING` and are dropped; `thinkingBudgetImpossible` is `EXPLAINS_ABSENCE` and is the
one line emitted. **The cost, stated:** an operator whose mode is also wrong learns one thing at a
time, and the translation warning appears on the next run once `maxTokens` is raised. That is the
better order — one actionable message beats two, one of which is describing a request that does not
exist.

**Case 2 — an effort dropped on a translated adaptive request. Warned, and the warning it gets is the
one that already exists.**

The path: `thinkingBudgetTokens` is legal only under `thinkingMode: extended`
(`AnthropicConfig.java:91-93`), so it reaches an *adaptive* request only by translation. There,
`buildRequest` reads `intendedEffort(modelConfig)`, which returns `nearestEffort(configuredBudget)` the
moment a budget is set (`:653-658`) — silently discarding the call's `reasoningEffort`.
`resolveExtendedThinking`, which owns `thinkingBudgetOverridesEffort`, is never reached on this path.
Confirmed by reading; untested, as the issue says.

By R-WHAT (a): the remedy is *remove one of the two* — but that is **not** the "mutually exclusive
pair" case, because the two keys do not contradict each other in intent. They both say *think this
hard*, in two vocabularies, and the operator almost certainly does not know one silences the other.
Report.

The existing message is already dialect-neutral and true on both paths — *"Both a thinking budget ({}
tokens) and a reasoning effort ({}) are set; the explicit budget wins and the effort is ignored on this
provider."* — so **the message and the signature do not change**; only the place that decides it does.
It moves to the one step that applies the precedence, so it fires once for either dialect. Keeping the
signature model-free is deliberate: the condition is a property of the configuration pair, and the
outcome ("the number wins, your rung is ignored") is the same in both dialects, so one line is a
complete statement.

Note what the translation warning already says and what it does not: it names the budget and the rung
it became, but never that a *different* rung was set and discarded. That is the gap.

**Case 3 — `thinkingDisplay` + `reasoningEffort: none`: deliberately silent. Verdict, by R-WHAT.**

Both clauses fail. (a) The only remedy is *stop asking for no reasoning*, which reverses a value the
operator explicitly wrote — `reasoningEffort` has no default, so `none` is a deliberate statement, and
`thinkingDisplay` and `none` are a mutually exclusive pair. (b) No false conclusion is available:
the operator sees no thinking text, and no thinking text is what `none` asks for. #62's judgement
stands, and it is now **derived rather than remembered** — and it gains what a comment cannot have:
**a test that pins the silence**, so a future author who adds a fourth reporter goes red and reads the
rule.

**And the contract's first new case, decided by it rather than by discussion.** §4 gives
`claude-opus-4-6` a row that *honours* `thinkingMode: extended` on a model where the vendor deprecates
that shape. Nothing is inert and nothing is reshaped, so R-WHAT reports nothing — and the SDK's own
stderr line already tells the operator, in the vendor's words. The client does not paraphrase a
vendor deprecation notice.

---

## 4. Decision (b) — `ThinkingDialect` gains a fourth constant, `EITHER`

### 4.1 The reasoning, which is not "both needs a name"

Issue #73 argues a fourth constant is not obviously right: *"under `AUTO` 'both' has to resolve to a
choice anyway, and whichever it picks is a policy, not a fact about the model."* Both halves of that
are true. The fourth constant is right anyway, and for a reason the issue does not consider: **the two
behaviours a both-dialect model needs differ from `UNKNOWN`'s in exactly one cell, and that cell is
`AUTO`.**

Read the client as it stands. `UNKNOWN` is not one behaviour; it is two:

| where | what `UNKNOWN` does today |
|---|---|
| `resolveNamedDialect` (`:606`) | **honour the named mode** — `known == UNKNOWN` returns `named`, unreported |
| `resolveAutoDialect` (`:587`) | **send nothing and warn** — there is no dialect to guess |

A model measured to accept both wants the *first* and not the second: honour whichever shape the
operator named (both work — translating a working, explicitly-requested shape is the opposite of what
§3.3's own justification permits), and under `AUTO` pick one rather than send nothing. Three constants
cannot express that. `ADAPTIVE` would translate a working `extended` request; `UNKNOWN` would leave
`AUTO` empty-handed on a model that accepts everything.

So the fourth constant is not a name for a fact that wanted naming. **It is what splits the two jobs
`UNKNOWN` was doing**, which is precisely the task's requirement that `UNKNOWN` stop covering two
different situations — met by decomposition rather than by relabelling:

| constant | what it says about the table | resolved request dialect | `AUTO` |
|---|---|---|---|
| `UNKNOWN` | the table **cannot answer** | — | send nothing, warn |
| `EITHER` | the table says **both shapes are accepted** | — | the preferred one (§4.2) |
| `BUDGETED` | this model's requests speak the budgeted shape | ✔ | it |
| `ADAPTIVE` | …the adaptive shape | ✔ | it |

The four split into two clean pairs — two describe the table's knowledge and are never a wire shape,
two describe a wire shape. `resolveDialect` consumes `UNKNOWN` and `EITHER` and **returns only a
concrete dialect or empty**, so neither can leak into `resolveThinking`'s `dialect.get() == ADAPTIVE`
branch. That is an invariant worth stating in the enum's javadoc and worth a test.

And the issue's objection is answered rather than dodged: yes, `AUTO` must pick, and yes the pick is a
policy — the fourth constant is what lets the **fact** (both accepted, in the registry, with a
measurement date) and the **policy** (`AUTO` prefers adaptive, in the client, with a citation) be
written in different places, each answerable on its own. Folding the policy into an `ADAPTIVE` row
would make the row assert something no probe measured.

`EITHER` rather than `BOTH`: "both" invites *send both*, which is impossible. Rejected alternatives are
in §10.

### 4.2 What `AUTO` picks for `EITHER`, and why that is citable rather than arbitrary

**`ADAPTIVE`**, and the citation is the vendor's:

- its per-model table marks the budgeted shape `(deprecated)` on exactly these two models
  (`anthropic-thinking-traces.md` §2.1);
- the vendored SDK prints *"'thinking.type=enabled' is deprecated. Use thinking.type=adaptive instead
  which results in better model performance in our testing"* for `claude-opus-4-6` (§2);
- and the table **already** resolves a documented both-dialect model this way: the vendor lists *Claude
  Mythos Preview* as `Adaptive, extended`, and the shipped `claude-mythos` prefix says `ADAPTIVE`. The
  pick is the policy the table has been applying since #60, now written down.

The policy lives in `resolveAutoDialect` — one branch in the client — and **not** as a method on
`ThinkingDialect`. The enum states facts and names no vendor; a preference between two accepted shapes
is a client decision about what to send. Put it on the enum and a second provider inherits an
Anthropic policy through a neutral type.

`EITHER` is deliberately **not** given a preferred-dialect field or a second enum. If a future
both-dialect model prefers the budgeted shape, that is one row's worth of new fact and it needs
something this design does not have — recorded as an open question, §11 O-3.

### 4.3 The §3.3 decision table, extended

| mode | dialect | request | reported |
|---|---|---|---|
| `OFF` | any | nothing — unchanged | nothing about the dialect |
| any | `UNKNOWN` | exactly today's behaviour | nothing (except `AUTO`, below) |
| `EXTENDED` | `BUDGETED` | `{"type":"enabled","budget_tokens":N}` | nothing |
| `ADAPTIVE` | `ADAPTIVE` | `{"type":"adaptive"}` + `output_config.effort` | nothing |
| `EXTENDED` | `ADAPTIVE` | `{"type":"adaptive"}`, effort from the intent | WARN, once per signature |
| `ADAPTIVE` | `BUDGETED` | `{"type":"enabled"}`, budget from the intent | WARN, once per signature |
| **`EXTENDED`** | **`EITHER`** | **`{"type":"enabled","budget_tokens":N}` — honoured, not translated** | **nothing** |
| **`ADAPTIVE`** | **`EITHER`** | **`{"type":"adaptive"}` + `output_config.effort` — honoured** | **nothing** |
| `AUTO` | `BUDGETED` / `ADAPTIVE` | the model's dialect | nothing |
| **`AUTO`** | **`EITHER`** | **`{"type":"adaptive"}` + `output_config.effort`** | **nothing — `AUTO` asked the table and it answered** |
| `AUTO` | `UNKNOWN` | nothing, as `OFF` | WARN |

`EITHER`'s two named-mode rows report nothing because nothing diverges. That the vendor deprecates one
of them is said by the SDK, per §3.3's last paragraph.

### 4.4 What this is not

**Not a set-valued `thinkingDialect()`.** Backlog L-7 offers it, with the `lowestReasoningEffort →
acceptedReasoningEfforts` precedent behind it, and it would be a defensible answer in a different week.
It is **out of scope here for two independent reasons**: it changes `ModelCapabilities`, which the
sibling run `llm-capability-config-gaps` is editing, and it changes the shape of `thinkingDialect`'s
configuration binding, which that run is deciding about and which this task forbids touching. Recorded
when closing L-7, so the next reader knows it was weighed and not overlooked. §10 A2.

**Not a config-surface change.** `ModelCapabilityDeclaration` already carries a `thinkingDialect` field
(`:55`, `:160`, `:305`) and **neither surface binds it** — `grep -rn "thinking-dialect\|thinkingDialect"
modules/aimon-spring-boot-starter/src/main modules/aimon-cli/src/main` → 0. So a fourth constant widens
a Java-only enum today. If the sibling run gives the field a key, the constant rides along and that
run's documented value list must gain it: a coordination note, not a conflict. §11 O-1.

---

## 5. Decision (c) — how the measured rows cite their measurement

The neighbouring rows have one shape, and the new rows take it:

| existing row | how it cites |
|---|---|
| the Anthropic `ADAPTIVE` block | inline comment: *"Anthropic, **measured 2026-09-09** against the account's own `/v1/models` listing"* + the design section carrying the probes |
| the o-series prefixes | *"The o-series, **measured 2026-09-09** and no longer inferred"* + what each row deliberately withholds |
| `MEASURED_O_SERIES_NAMES` | javadoc: *"measured **on 2026-09-09**"* + *"with one assumption stated in section 13 of `docs/design/llm/openai-model-capabilities.md`"* |
| `gpt-5.6-terra` | *"Round 8, **measured 2026-09-09** (`docs/design/llm/openai-model-capabilities.md` section 13.3)"* |
| the class javadoc | restates the date and the row shapes: *"measured 2026-09-09: three prefix rows **and** eight exact rows"* |

So the form is: **date, probing surface, the two request shapes and their two outcomes, what the row
deliberately does *not* say, and a design-document section that carries the probe table** — with the
class javadoc and `withDefaults()`'s javadoc restating the date because both enumerate what the table
describes.

Applied:

- **Inline, beside the five new registrations:** `measured 2026-09-10`, naming the surface as *this
  account's `GET /v1/models` listing **plus three undated aliases the listing does not contain*** (M-1
  — the honest surface, and a correction to a method three documents inherit), the two shapes
  (`{"type":"adaptive"}` + `output_config.effort` versus `{"type":"enabled","budget_tokens":1024}`), the
  two outcomes, and the two facts each row **withholds** and why (sampling stays fail-open; nothing
  else is measured).
- **The probe table** goes to `docs/design/llm/reasoning-model-enablement.md` **§3.5, new — "The
  dialect census, measured 2026-09-10"**, which the inline comment cites the way the terra row cites
  `openai-model-capabilities.md` §13.3. That document is where the dialect was designed and where §9
  U-1 already carries a dated discharge note appended after the fact, so appending a measurement
  section is that document's own established habit rather than a new one. §3.3's table stays what it
  is — mode × dialect — and gains only the three `EITHER` rows.
- **§9 U-1's discharge extends** from "five of six rows" to the whole census, and says what remains
  undischarged: `claude-mythos` (no reachable model) and dated 4-6 snapshots (M-4, 404).
- **The class javadoc and `withDefaults()`'s javadoc** gain the date and the new row count, because
  both enumerate what the table describes and both would otherwise be a stale census.

Two things this citation deliberately does **not** do. It does not cite the backlog: L-6 and L-7 close
in this change, and a live row citing a closed backlog item points at a record of the decision rather
than at the evidence. And it does not cite the GitHub issue: the issue's table is a paste, this run
re-measured it, and §2 says which of the two the row rests on.

---

## 6. Concrete changes, by module and file

### 6.1 `modules/aimon-core/src/main/java/at/aimon/core/llm/capability/`

**`ThinkingDialect.java`** — the fourth constant and two javadoc corrections.

- `EITHER` added, between `UNKNOWN` and `BUDGETED` so the two table-knowledge constants sit together.
  Its javadoc says: both shapes are accepted, the request keeps the one the mode named, and under a
  mode that asks the table the client picks — naming §4.2's citation without naming the client.
- The class javadoc's *"Both real dialects are an HTTP 400 on a model that speaks the other one"* is
  **narrowed to "…that speaks only the other one"**. The sentence's argument (the fail-open value must
  be the absence of the fact) survives and gets stronger: there are now two non-dialect constants and
  they exist for different reasons.
- The paragraph *"There is deliberately no fourth constant for the vendors that express the same axis
  as an effort rung alone"* stays true and is **kept**, with a clause distinguishing the constant that
  did arrive: it is not an alternative spelling of an existing flag, it is a fourth state of *this*
  axis. That paragraph is about `EFFORT_ONLY` and `supportsReasoningEffort()`, and nothing here
  weakens it.
- A sentence stating the invariant: `UNKNOWN` and `EITHER` are values a **row** may hold and a
  **request** never speaks.

**`InMemoryModelCapabilityRegistry.java`** — five prefix rows, two shared descriptors, and #75 item 2.

```java
/** The dialect, and nothing else — see the comment beside the registrations for why that is the whole row. */
private static final ModelCapabilities BUDGETED_DIALECT_ONLY =
        ModelCapabilities.builder().thinkingDialect(ThinkingDialect.BUDGETED).build();

private static final ModelCapabilities EITHER_DIALECT_ONLY =
        ModelCapabilities.builder().thinkingDialect(ThinkingDialect.EITHER).build();
```

```java
.registerPrefix("claude-opus-4-5",   BUDGETED_DIALECT_ONLY)
.registerPrefix("claude-sonnet-4-5", BUDGETED_DIALECT_ONLY)
.registerPrefix("claude-haiku-4-5",  BUDGETED_DIALECT_ONLY)
.registerPrefix("claude-opus-4-6",   EITHER_DIALECT_ONLY)
.registerPrefix("claude-sonnet-4-6", EITHER_DIALECT_ONLY)
```

- **Registration order is free**, and the comment says so with the check: none of the five is a prefix
  of another or of an existing prefix (`-4-5` / `-4-6` / `-4-7` / `-4-8` are siblings, not nested;
  `claude-sonnet-4-5` does not match `claude-sonnet-4-20250514`, which is `AnthropicConfig`'s own
  default model and stays undescribed).
- **`supportsSamplingParameters` stays at its fail-open `true`**, and that is the load-bearing half of
  the row. The existing block's comment already warns *"Deliberately NOT `claude-opus-4`:
  `claude-opus-4-5` and `claude-opus-4-6` accept all three (measured)"* — these five rows are the
  narrow prefixes that comment implies, carrying the dialect and suppressing nothing. That is why the
  new descriptors are **not** reuses of `ADAPTIVE_REFUSING_SAMPLING`.
- The measurement citation per §5.
- **#75 item 2:** `builderWithDefaults()`'s javadoc gains one sentence naming `gpt-5.6-terra` as the
  name its documented `registerPrefix("gpt-5", …)` override no longer reaches, pointing at the class
  javadoc's fuller statement. The method makes the promise; today only the class javadoc, two
  paragraphs up, states the exception.

**`package-info.java`** — the same narrowing as `ThinkingDialect`'s class javadoc, one clause
(`:27-29` carries the identical universal). Named by neither run; a trivial rebase if it conflicts.

**`ModelCapabilities.java` — a two-word narrowing in two javadoc sentences, and this is the one
deliberate touch to a file the sibling run owns.** `:96-98` (*"both real dialects are a 400 on the
model that speaks the other"*) and `:212-214` (*"The axis this field describes is mutually
exclusive"*) both assert as a universal something this same change measures to be false for two
models. Leaving them is shipping the failure mode #73 exists to remove — a record that says
"mutually exclusive" about an axis measured not to be. The edit is prose-only, does not touch a
signature or a value, and is stated here line by line so a rebase is mechanical. **Fallback if a
reviewer prefers zero touches to shared files:** say so plainly in the PR body and register a backlog
line — but say it, rather than leaving the sentence and hoping.

### 6.2 `modules/aimon-llm-anthropic/`

**New: `AnthropicThinkingResolver` and `AnthropicThinkingResolution`** (package-private, same package).
This is how R-WHEN becomes structural rather than disciplinary — see §7 for the shapes and §10 A5 for
the smaller alternative and why it was rejected.

The resolver takes `AnthropicConfig` once and answers one question:

```java
AnthropicThinkingResolution resolve(LlmModel modelConfig, int maxTokens,
        ModelCapabilities capabilities, String modelName);
```

It owns what moves out of `AnthropicLlmClient`: `resolveThinking`, `resolveDialect`,
`resolveAutoDialect`, `resolveNamedDialect`, `resolveExtendedThinking`, `reportInertEffort`,
`reportInertDisplay`, `reportDisplayOnBudgetedDialect`, `intendedEffort`, `requestedEffort` — the last
two because the resolution now also answers *which effort accompanies the parameter*, which is what
kills case 2 by construction. The reporters keep their messages and signatures and become finding
factories. **The resolver has no logger and no access to `reportDivergence`**, which is a private
method of the client; that is the enforcement.

**`AnthropicLlmClient.java`**

```java
final AnthropicThinkingResolution thinking = thinkingResolver.resolve(modelConfig, maxTokens,
        capabilities, modelName);

thinking.parameter().ifPresent(requestBuilder::thinking);
thinking.outputConfigEffort()
        .ifPresent(e -> requestBuilder.outputConfig(OutputConfig.builder().effort(e).build()));
thinking.findingsToReport().forEach(f -> reportDivergence(f.signature(), f.message(), f.args()));
```

- The `thinking.filter(ThinkingConfigParam::isAdaptive)` guard around `outputConfig` moves *into* the
  resolution, where it belongs — the resolution knows which shape it produced. M-3 makes that guard
  measured: two of the three 4-5 models reject a bare `output_config.effort`.
- Dialect-related resolution logic and the `intendedEffort` re-read leave the client. `applySamplingParameters`,
  `reportIfReplayIsOffWhileThinking` (already contract-compliant), the penalty reports and both
  registers stay exactly as they are.
- New in the resolver: `resolveNamedDialect`'s guard becomes
  `known == UNKNOWN || known == EITHER || known == named`; `resolveAutoDialect` maps `EITHER →
  ADAPTIVE` with the §4.2 citation in a comment; the `thinkingBudgetOverridesEffort` finding is raised
  once from the step that applies the precedence rather than from the budgeted branch only.

**`AnthropicThinkingMode.java`** — `:39` and `:97` explain the mode × dialect interaction in terms of
`UNKNOWN`; both gain the `EITHER` row in one clause each. No behaviour, no new value: the mode enum
keeps its four constants and `OFF` keeps being the default (G-1 is still its own round).

### 6.3 Docs

- **`docs/design/llm/reasoning-model-enablement.md`** — §3.3's table gains the three `EITHER` rows;
  new **§3.5** carries the census and the citation target; **§7** gains three failure rows (§8); §9
  U-1's discharge extends and names what stays undischarged. Not a translation target
  (`docs/README.md`: `design/` is *대상 아님*), and `ls docs/design/llm/*.en.md` is empty — so nothing
  paired to update, which the PR body should state rather than leave to inference.
- **`docs/backlog/llm-config-surface-open-items.md`** — **L-6 and L-7 close**, per
  `docs/backlog/README.md`'s rules: what was done, and where the item's own reasoning turned out
  different. L-6 said three rows for three dated names; it became three prefix rows covering six
  measured names, because the aliases resolve (M-1). L-7 offered `EITHER` or a set; the set was
  weighed and refused for a scope reason, not on merit (§4.4). Title `등록 항목 7건 (열림 7)` →
  `(열림 5 · 닫힘 2)`.
- **`docs/backlog/README.md`** — the index, twice: `llm-config-surface-open-items.md` from `5 | 5 | 0 |
  0` to `7 | 5 | 2 | 0`, and `spring-boot-starter-open-items.md`'s `25 | 5` to `26 | 4` (#75 item 3,
  §12).
- **`CHANGELOG.md`** — one `[Unreleased]` block; the behaviour changes are §8's B-1…B-4 and the
  source-breaking enum addition.
- **`docs/features/**` — nothing.** `grep -rln ThinkingDialect docs/` hits only `design/` and
  `backlog/`, both non-translation directories, so **no `.en.md` pair is touched by #68 or #73.** #75
  item 1 touches only javadoc.

### 6.4 #75 item 1 — where the cross-reference actually goes

The two twelve-line folds are **not** in the files the task names. They are:

| surface | file | member |
|---|---|---|
| CLI | `modules/aimon-cli/.../config/AnthropicProviderConfig.java` | `ThinkingModeDeserializer.deserialize` |
| starter | `modules/aimon-spring-boot-starter/.../AimonLlmAutoConfiguration.java` | `thinkingMode(String)` |

The task lists `LlmClientFactory.java` and `AimonProperties.java` as the shared files to touch lightly
— the CLI's *assembly* site and the *properties* class whose javadoc discusses the fold, neither of
which contains one. The proposal: **put the sentence in each of the two fold javadocs and leave both
named shared files untouched.** That satisfies the issue's stated purpose (*"a reader who finds one
copy has no way to know the other exists"* — a cross-reference not beside the code fails it) at zero
touches to either file the sibling run is editing. Registered as an open question, §11 O-2.

Each side already has half of it, which is why one sentence finishes the job rather than starting it:
`AimonProperties.java:1441` says *"over `AnthropicThinkingMode.values()` so the two surfaces cannot
accept different spellings"* without naming the other copy, and `AnthropicProviderConfig`'s javadoc
says *"접는 방식은 스타터의 fold 와 같이 `values()` 를 돈다"* without naming its class. The missing
half in both is the **address**, and the reassurance the issue says nothing states: both derive from
`values()`, so a fifth constant cannot silently miss one.

---

## 7. Data and interface shapes that change

**`ThinkingDialect`** — a fourth constant. Public enum in a published `0.x` module: **source-breaking
for any out-of-tree exhaustive `switch`**, which `docs/project/api-stability.md` §5 permits and this
repository takes in one step rather than through a deprecation window (precedent in the same
`[Unreleased]` block: `AgentExecutionEvent`'s sixteenth subtype). **In-tree the compiler catches
nothing**, and that is a fact to state rather than a comfort: `grep -rn ThinkingDialect modules/
--include='*.java'` shows every main-source read is an `==` comparison, and no test enumerates
`values()`. So the three reads that must change are found by reading, not by the build — they are
named in §6.2 and each gets a test.

The constant goes **between `UNKNOWN` and `BUDGETED`**, which shifts two ordinals, and that was checked
rather than assumed safe: no `ordinal()` call exists anywhere under `at.aimon.core.llm`, the value is
persisted nowhere, and `ThinkingDialect` appears in no configuration metadata or resource file on
either surface. Declaration order is therefore free, and grouping the two table-knowledge constants
together is what makes §4.1's two-pair reading visible in the source.

**`AnthropicThinkingResolution`** (new, package-private, immutable) — the resolution's whole answer:

```java
final class AnthropicThinkingResolution {
    Optional<ThinkingConfigParam>   parameter();          // what goes on the wire, or empty
    Optional<OutputConfig.Effort>   outputConfigEffort();  // non-empty only for the adaptive shape
    List<Finding>                   findingsToReport();    // filtered by parameter().isPresent()

    static final class Finding {   // signature · message · args · Scope
        enum Scope { REQUIRES_THINKING, EXPLAINS_ABSENCE }
    }
}
```

`final` class, `final` fields, no setters, built through a `Builder` — the outer type because findings
genuinely accumulate step by step, which is what a builder is for; `Finding` through a static factory
because it is created in one shot from a call site's varargs and a builder over varargs args is
ceremony without a guard (`.claude/rules/immutability-pattern.md`'s single-assembly reading). No
`record`, per `.claude/rules/code-style.md` — the one exception there is `GenericTool` input DTOs.

`findingsToReport()` is the gate, so the filter cannot be forgotten at a call site: there is no
accessor that returns the unfiltered list.

**`AnthropicThinkingResolver`** (new, package-private) — stateless apart from the `AnthropicConfig` it
is constructed with, thread-safe, no logger.

**Unchanged:** `ModelCapabilities` and `ModelCapabilityDeclaration`'s shapes and signatures (prose
only, §6.1); every divergence signature; every warning message except the two `EITHER` clauses added
to `AnthropicThinkingMode`'s javadoc; `AnthropicThinkingBudgets` entirely; both configuration surfaces;
`AnthropicConfig`.

---

## 8. Failure modes

New rows for `reasoning-model-enablement.md` §7, in that table's voice.

| # | Shape | Disposition |
|---|---|---|
| **10** | A row states `EITHER` for a model that in fact rejects one shape | The 400 the operator gets today, now with the framework's name on it — §7 row 1's disposition, unchanged. Mitigated the same way: rows come from measurement, and a declared row for the name displaces a built-in one. The blast radius is smaller than row 1's, because `EITHER` only ever *honours* what the mode named, so the 400 is the one the mode would have earned without any row |
| **11** | `AUTO` on an `EITHER` model picks adaptive; the operator wanted their token budget | Not silent and not a guess: the pick and its citation are in §3.3, in `ThinkingDialect`'s javadoc and in one client branch. The remedy is `thinkingMode: extended`, which `EITHER` honours **without translation** — which is the property that makes this row cheap rather than a trap |
| **12** | `output_config.effort` reaches a request whose shape is budgeted | Measured 400 on `claude-haiku-4-5` and `claude-sonnet-4-5`, 200 on `claude-opus-4-5` (M-3). Prevented by the shape check that now lives inside the resolution, and pinned by a test asserting no `output_config` on either translated or `EITHER`-honoured budgeted requests. Before this change the guard was correct and untested |
| **13** | A future author reports from inside the resolution again | Structurally unavailable: the resolver has no logger and cannot reach the client's private `reportDivergence`. The residual risk is somebody adding a logger to the resolver, which is a reviewable line rather than an easy accident |
| **14** | A dropped finding's signature is consumed, silencing that message for the process | Cannot happen: dedup lives in `reportDivergence`, which only ever sees surviving findings. Written down because the natural mistake — dedup while collecting — reintroduces the silence this work removes, and it would be invisible in tests that assert one warning at a time |

**And the four observable behaviour changes**, each a CHANGELOG line:

- **B-1.** `thinkingMode: auto` on the 4-5 families and the 4-6 pair now **sends a thinking parameter
  and bills for it**, where it previously sent nothing and warned. That is what `AUTO` asks for and what
  a row is for, but it is new spending on upgrade and must be said out loud.
- **B-2.** `thinkingMode: adaptive` on a 4-5 family model was a **certain 400**; it is now a translated
  budgeted request that succeeds, with one WARN.
- **B-3.** `thinkingMode: extended` on `claude-opus-4-6` / `claude-sonnet-4-6` is **unchanged on the
  wire** (`EITHER` honours it) — stated because "we gave these models a row" would otherwise read as a
  change. The SDK's per-call stderr deprecation line therefore also continues; this change neither
  suppresses nor duplicates it.
- **B-4.** A request that asks for thinking and then abandons it now emits **one** warning explaining
  the absence instead of up to four describing a request that is not sent. Fewer lines, and the ones
  that remain are true.

---

## 9. Test strategy

Everything runs under `./gradlew checkAll`; nothing here adds a billed call to `./gradlew test`.

**The resolution, in isolation — the new capability.** `AnthropicThinkingResolver` is package-private
and pure, so the §4.3 table becomes a parameterised test over `(mode, dialect)` asserting the returned
parameter, the returned effort and the finding signatures **without** a mocked SDK client or the
`SENTINEL` exception trick the existing dialect test needs. The existing request-level tests stay as
the end-to-end check that the wire body matches.

**#68, one test per case:**

1. **No warning describes an abandoned request.** `thinkingMode: adaptive` + a `BUDGETED` model +
   `maxTokens: 512`: assert the body has no `thinking`, and that `warnings()` contains the
   "leaves no room" line and **neither** the translation line **nor** the display-on-budgeted line.
   The negative half is the test — a positive-only assertion passes today.
2. **The dropped effort, the case the issue says is untested.** `thinkingMode: extended` +
   `thinkingBudgetTokens(N)` + `LlmModel.reasoningEffort(HIGH)` against an `ADAPTIVE` model: assert the
   body is adaptive with `output_config.effort` from `nearestEffort(N)`, and that both the translation
   warning **and** `"the explicit budget wins"` are present. Plus the budgeted-dialect counterpart, so
   the one finding covering both paths is pinned on both.
3. **The silence, pinned.** `thinkingDisplay(SUMMARIZED)` + `reasoningEffort(NONE)` under a mode that
   is not `OFF`: assert `warnings()` is empty. Its comment states R-WHAT's clause and names §3.2, so a
   future fourth reporter goes red and reads the rule rather than deleting the test.
4. **Dedup survives a drop.** Two sends: the first abandons thinking (nothing about the translation is
   said), the second raises `maxTokens` so thinking is sent — assert the translation warning appears on
   the second. This is the test that catches dedup-while-collecting, which no single-send test can.

**#73:**

5. **The census is a test.** One parameterised case per measured name — all six BUDGETED names
   (dated **and** alias, M-1) and both `EITHER` names — asserting `resolve(name).thinkingDialect()`.
   Named for the date so a re-measurement knows what it is re-measuring.
6. **The prefix trap survives, with its instrument changed.**
   `InMemoryModelCapabilityRegistryTest.defaultsLeaveTheAcceptingClaudeModelsUnknown` asserts today
   that **no row matches** these five names, because a family prefix would suppress a sampling
   parameter they accept. Its *intent* survives this change and its *instrument* cannot: rows now
   match. It must become an assertion that `supportsSamplingParameters()` is `true` for all five —
   which is the thing the test was protecting — and **not** be deleted. Deleting it is the one way
   this change loses a guard.
7. **`noBuiltInRowStatesTheBudgetedDialect` inverts.** Rewritten as: the budgeted dialect is now
   stated by exactly these three prefixes, the OpenAI rows and `claude-sonnet-4-20250514` still say
   `UNKNOWN`, and the reason the OpenAI rows say nothing is unchanged (no OpenAI path reads it).
8. **`EITHER` never reaches the wire.** Assert `resolveDialect` returns only a concrete dialect or
   empty, through the request body: an `EITHER` model under every mode produces either no `thinking`
   or one of the two real shapes — never a budgeted body from an `EITHER` row that was meant to be
   honoured as adaptive.
9. **No `output_config` on a budgeted body** (M-3), on both the translated and the `EITHER`-honoured
   paths.
10. **`builderWithDefaultsOverridesAPrefixInPlace` stays green** — it pins the javadoc's canonical
    override recipe and the five new prefixes must not disturb registration order.

**The test-fixture cost #73 imposes, which is the largest mechanical item in this design.** M-1 makes
`claude-sonnet-4-5` a described model, and two Anthropic test classes use that exact string as their
*undescribed* one:

| file | what breaks |
|---|---|
| `AnthropicThinkingDialectTest` | `UNKNOWN_MODEL = "claude-sonnet-4-5"` plus the three golden bodies `TODAYS_EXTENDED_BODY` / `TODAYS_ADAPTIVE_BODY` / `TODAYS_THINKING_OFF_BODY`, which embed the name |
| `AnthropicThinkingRequestTest` | `config()` builds on `model("claude-sonnet-4-5")`; the class's ADAPTIVE tests would start seeing a translation and a warning, and two golden bodies embed the name |

Both re-point at **`claude-sonnet-4-20250514`** — `AnthropicConfig`'s own `DEFAULT_MODEL`, currently
`UNKNOWN` and already pinned as such by the registry test — which makes the golden bodies describe the
shipped default and so *more* meaningful than an arbitrary 4-5 name. A synthetic name would be more
future-proof and is the fallback; it was not chosen because a golden request body with a fake model in
it reads as a fixture rather than as a request. Either way, **each class gains one assertion that its
undescribed constant really is undescribed** (`capabilitiesOf(UNKNOWN_MODEL)` is empty), so the next
row that describes it fails with a sentence instead of a mysterious golden-body diff.

Two more classes need a prose fix, not a behaviour fix:
`AnthropicLlmClientSamplingCapabilityTest`'s `ACCEPTING_MODEL = "claude-opus-4-5-20251101"` is
described as *"deliberately [absent from the table]"* — it is now described, by a row that states only
the dialect, and its assertions (sampling applied) are exactly what proves the row withholds nothing.
`AnthropicThinkingRequestTest:169`'s comment says `claude-sonnet-4-5` *"is not in the table"*.

**Counted assertions to re-check.** `warnings()` is asserted 20× `isEmpty`, 11× `singleElement`, 7×
`hasSize` across the module, and no assertion is order-sensitive (`containsExactly` / `element(0)`: 0
hits). Dropping abandoned findings can only lower a count and adding case 2's finding can only raise
one on a path the issue says is untested, so no existing count is expected to move — but "expected" is
not "measured", and `./gradlew :aimon-llm-anthropic:test :aimon-core:test` is the measurement.

**#75** verifies as the issue says: `python3 scripts/check-doc-links.py`, plus reading the two files
that must agree — and for item 3, the count in §12 rather than either number.

---

## 10. Alternatives rejected

| # | Alternative | Why not |
|---|---|---|
| **A1** | **Leave `claude-opus-4-6` / `claude-sonnet-4-6` out of the table** and record "measured to accept both" in a comment only | It does not close L-7 and it does not meet the task's requirement. With no row the two models resolve to `UNKNOWN` — the table *literally* cannot answer for them — so `UNKNOWN` keeps covering both "measured to accept both" and "unmeasured", which is the double duty that had to end. It also leaves `AUTO` empty-handed on the two models that accept everything, and leaves the SDK's per-call stderr line as the only thing an `extended` deployment hears |
| **A2** | **Set-valued `thinkingDialect()`** — `Set<ThinkingDialect>`, the `acceptedReasoningEfforts` shape L-7 points at | The precedent is real and this would be defensible in another week. Out of scope twice over: it changes `ModelCapabilities`, which the sibling run `llm-capability-config-gaps` is editing, and it changes the shape of `thinkingDialect`'s configuration binding, which that run is deciding about and this task forbids touching. Recorded in L-7's closing note so it reads as weighed rather than missed |
| **A3** | **A fifth/sixth constant pair — `ADAPTIVE_PREFERRED` / `BUDGETED_PREFERRED`** | It puts the `AUTO` policy in the row, where no probe measured it, and it multiplies: every future both-dialect model with a different preference is another constant. §4.2 keeps the fact in the registry and the policy in one client branch, each separately citable |
| **A4** | **`ADAPTIVE` rows for the 4-6 pair**, no new constant | Cheapest diff, and wrong twice. It would translate `thinkingMode: extended` — a working, explicitly-requested shape carrying the operator's exact token budget — on the strength of a vendor *preference*, when §3.3's own justification for translating is *"honouring the operator is a certain 400"*, which is false here. And it would make the translation warning assert *"which rejects the other one with HTTP 400"* about a model measured to accept it: a warning describing a request that is not the one sent, which is #68's subject. The two issues collide precisely here, and `EITHER` is what stops them |
| **A5** | **#68 by a local finding list inside `AnthropicLlmClient`**, no new classes | Half the size and it does fix all three cases. Rejected because the contract stays a discipline: a future author can still call the private `reportDivergence` from inside the resolution, and nothing but review would catch it. This repository's habit is to make the wrong state unrepresentable — `MemoryCapabilities.of(...)` is a static utility for exactly this reason. Moving the resolution behind a class with no logger converts "documented not to" into "cannot". It also buys the isolated table test in §9 |
| **A6** | **#68 by recomputing the reports from the finished request**, no buffer | The literal reading of *"report after the request is final"*, and it would need the dialect decision re-derived in a second place to know whether a translation happened — two implementations of the mode × dialect table, whose first divergence would be invisible. The buffer keeps one decision and moves only the emission |
| **A7** | **Pass a mutable `List<Finding>` down as an out-parameter** | Smaller than A5's carrier and it enforces the same thing, but it makes the resolution's answer two values, one of them by mutation, and there is then no single place to put the `parameter().isPresent()` filter. `findingsToReport()` exists so the gate cannot be forgotten at a call site |
| **A8** | **Warn about `thinkingDisplay` + `reasoningEffort: none`** (#68 case 3 the other way) | Fails both clauses of R-WHAT: the only remedy reverses a value the operator explicitly wrote, and there is no false conclusion to prevent because absent thinking text is what `none` asked for. Reporting it would be advice in the wrong direction, which is the reason `reportInertEffort` already excludes `NONE` |
| **A9** | **Warn that `thinkingMode: extended` on an `EITHER` model uses the deprecated shape** | Nothing is inert and nothing is reshaped, so R-WHAT reports nothing — and the SDK already prints the vendor's own words at every call. A paraphrase of a vendor deprecation notice, deduped differently from the notice itself, is two voices on one subject |
| **A10** | **Exact rows for the three dated 4-5 names**, no prefixes | Leaves the undated aliases `UNKNOWN` — and M-1 measured those as live, callable, budgeted-dialect names, and they are what a deployment is most likely to write. That is the defect L-6 names, reproduced in the fix for it. Prefixes also cost less: three rows instead of six |
| **A11** | **Add rows for `supportsReasoningEffort` from M-3's finding** (`claude-opus-4-5` accepts a bare `output_config.effort`; the other two reject it) | Real, measured, and consumed by nothing: no Anthropic path reads `supportsReasoningEffort()`. A value there would be an assertion with no consumer, which is the reason the existing `claude-*` rows leave three flags fail-open. Recorded in §14.3 and left there |

---

## 11. Open questions — stated, not assumed

| # | Question | What was assumed, and how to unpick it |
|---|---|---|
| **O-1** | Does the sibling run `llm-capability-config-gaps` give `thinkingDialect` a configuration key, and if so does its documented value list need `EITHER`? | Assumed: it might, and this design does not touch the binding's shape. `ModelCapabilityDeclaration` already has the field and neither surface binds it today, so `EITHER` is Java-only on merge. If that run lands a key, its accepted-value list and its property metadata must gain the fourth constant. A coordination item at rebase, not a conflict |
| **O-2** | Should #75 item 1's cross-reference sentences go where the task says (`LlmClientFactory.java`, `AimonProperties.java`) or where the folds are (`AnthropicProviderConfig.ThinkingModeDeserializer`, `AimonLlmAutoConfiguration.thinkingMode`)? | The task names two files that do **not** contain a fold (§6.4). Chosen: put them beside the folds and touch neither named file — best for the issue's purpose and for the rebase. If the reviewer reads the task's file list as binding, `AimonProperties.java:1441` already carries half the sentence and needs only the CLI's address added |
| **O-3** | What happens when a both-dialect model prefers the **budgeted** shape? | Assumed not to exist: both measured both-dialect models deprecate the budgeted shape, and the vendor's table shows no counterexample. `EITHER` carries no preference field, so such a model needs a new fact — a fifth constant, a second field, or A2's set. Trigger: a vendor table row marking `adaptive (deprecated)` |
| **O-4** | Is the resolver extraction (A5) within the review appetite of a change these issues describe as small? | Assumed yes, because #68's stated purpose is that three call sites cannot drift, and visibility is the only thing that guarantees it. A5 records the smaller shape if the answer is no; the two are behaviourally identical and the tests in §9 pass either way |
| **O-5** | Should `reasoning-model-enablement.md` gain a **§3.5** at all, given the task names §3.3 and §7? | Assumed yes: §5's citation form needs a design-document section to point at, that document is the one that designed the dialect, and its §9 already carries a dated discharge appended after the fact. If a reviewer reads §3.3/§7 as exhaustive, the census can live inside §3.3 under a subheading at the cost of mixing a per-model census into a mode × dialect table |
| **O-6** | `input_tokens` varied with model and request shape for a constant `messages` array — 14/16/18/21, 43 on every budgeted call, **115** on one cell (§14.6). | Unexplained and deliberately not guessed at. Load-bearing for nothing here: every dialect verdict is a status code |
| **O-7** | Is `claude-mythos` still right, and is it `ADAPTIVE` or `EITHER`? | Unchanged and unverifiable: no model with that prefix exists in this account's listing. The vendor's table lists *Mythos 5.1* and *Mythos 5* as adaptive-only and *Mythos Preview* as `Adaptive, extended` — so the one prefix covers two documented dialect states, and `ADAPTIVE` is right for the first two and is `EITHER`'s `AUTO` answer for the third. Left alone; noted because it is the second reason the `AUTO`-prefers-adaptive policy is not new |
| **O-8** | Does #75 item 3's `spring-boot-starter-open-items.md` also want its §5 subtotal touched? | No — see §12. §5's `5건` excludes a dissolved item its own prose explains; that is presentation, not drift, and "fixing" it would break a total that currently closes exactly |

---

## 12. #75 item 3 — counted, not reconciled

The issue says one of the two numbers is wrong and it is not obvious which. Counted from the file, item
by item, `B-1` … `B-34`:

| section | items | open | closed | dissolved |
|---|---|---|---|---|
| §1 배포하면 실제로 물리는 것 | B-1,2,3,4,5,6,7 | B-7 | B-1,3,4,5,6 | B-2 |
| §2 YAML 파서와 스킬 로딩 | B-8,26,27,28,29 | — | all 5 | — |
| §3 운영·설정 | B-9,10,11,12,13,14,15,30,31,32,33,34 | B-15 | B-10,12,13,14,30,31,32,33,34 | B-9, B-11 |
| §4 빌드·릴리스·문서 위생 | B-16,17,18 | — | all 3 | — |
| §5 범위 결정이 먼저인 것 | B-19,20,21,22,23,24 | B-23 | B-19,20,21,24 | B-22 |
| §6 기능으로 접어 둔 것 | B-25 | B-25 (접힘) | — | — |
| **total** | **34** | **4** | **26** | **4** |

**The file's own title is right** (`34건 (열림 4 · 닫힘 26 · 해소 4)`) and **`docs/backlog/README.md`'s
index row is wrong** (`34 | 4 | 25 | 5`). 4 + 26 + 4 = 34 closes exactly, and every per-section header's
**status** breakdown agrees with the table above (§1 `닫힘 5 · 해소 1`, §3 `닫힘 9 · 해소 2`, §5
`닫힘 4 · 대기 1`). One *item* subtotal does not, and it is not drift: §5's header says `5건` for six
numbered items because it excludes the dissolved `B-22`, which its own prose explains — see O-8.

**The wrong cell is identifiable, which is what makes this a fix rather than a coin toss.** `B-21` was
dissolved, then reopened on 2026-09-09 and closed in §5 (*"해소 → 2026-09-09 다시 열렸다가 결정되고
닫혔다(✅)"*). It moved from 해소 to 닫힘 and the index kept the pre-move numbers. Confirmed against
history: the index row was **added** by `d3500f6` (#62, 2026-09-10), after that move, from stale
figures.

Fix: `25 | 5` → `26 | 4`. Do **not** touch §5's `5건` subtotal (O-8).

**And the second half of item 3 is out of date.** The issue says
`llm-config-surface-open-items.md` *"has no index row at all"*. It has one — `d3500f6` added it, and
that commit's own message says *"The backlog index was missing `llm-config-surface-open-items.md`
entirely… both rows are filled in."* What is wrong now is different and smaller: the row reads `5 | 5 |
0 | 0` while the file's title says `7건 (열림 7)`, because the very next commit (`3a41fcd`) added L-6
and L-7 without updating the index. Since this run closes both, the row becomes **`12 | 9 | 3 | 0`** once the sibling run `llm-capability-config-gaps` has also landed its own item and the merge has closed L-9 (it was `7 | 5 | 2 | 0` against `main` as this was written) —
so the stale row is corrected and consumed in the same change, and the PR body should say the issue's
description of this half was superseded rather than silently write a different fix.

---

## 13. Staging

Four commits, each leaving the tree green. `./gradlew format` before each; `./gradlew checkAll` reported
with its measured numbers, not with "it passed".

| # | Content | Closes |
|---|---|---|
| **1** | The reporting contract: `AnthropicThinkingResolver` + `AnthropicThinkingResolution`, all three #68 cases, their four tests. No new dialect value, no new rows — so the contract is reviewable against #68's three cases alone | `Closes #68` |
| **2** | `ThinkingDialect.EITHER`, the three javadoc narrowings, the client's three reads, `AnthropicThinkingMode`'s two clauses | (part of #73) |
| **3** | The five prefix rows with their §5 citation, the census test, the two rewritten registry tests, the re-pointed test fixtures, `reasoning-model-enablement.md` §3.3/§3.5/§7/§9, backlog L-6 + L-7 closed, `docs/backlog/README.md`'s two index rows | `Closes #73`, `Closes #75` (item 3) |
| **4** | #75 items 1 and 2 — three javadoc sentences, no behaviour | `Closes #75` (items 1–2) |

Commit 1 before 2–3 is load-bearing, not cosmetic: commit 3 creates the first request that is
translated out of preference rather than necessity, and A4 is the record of what happens when that
lands on the old reporting. Commits 3 and 4 could merge; they are separate because #75 item 3's count
wants to be readable on its own, per its own issue's suggestion.

`CHANGELOG.md` gets one `[Unreleased]` block covering B-1…B-4 and the source-breaking enum addition.
No rename, so `docs/migration/rename-maps.md` is untouched. No `.en.md` pair is touched, for the reason
in §6.3 — stated in the PR body rather than left to inference.

---

---

## 14. The measurement record

*Verbatim from the run's `probes.md`, folded in so this document carries its own evidence rather than
pointing at a file outside the repository. Section numbers inside it are its own.*

Issue #73 pastes a measurement table into its body. This document is the re-run the task asked for,
against the live API rather than against that table. **The live API agrees with the issue on every
cell the issue states**, and the re-run additionally measured four things the issue does not contain,
two of which change the shape of the change (§4, §5).

No key appears in this file, in the scripts it describes, in the repository, or in any commit. The
key was sourced into the probing shell from `~/.local/state/herdr-task/secrets/anthropic.env` and read
from `os.environ` by the probe scripts, which live in the session scratchpad and are not part of the
deliverable.

---

### 1. Method

`POST https://api.anthropic.com/v1/messages`, `anthropic-version: 2023-06-01`, plain `urllib` (no SDK,
so no SDK-side validation or warning could mask a server answer).

Constant across every request:

```jsonc
{ "model": "<name>", "max_tokens": 2048,
  "messages": [ { "role": "user", "content": "Reply with the single word: ok" } ] }
```

`max_tokens: 2048` because `budget_tokens` must be strictly below it and the floor is 1024.

Four request shapes. **A and B are the two the issue names**; C and D are controls the issue does not
have, and they are the reason §4 and §5 exist.

| shape | added to the body | what it isolates |
|---|---|---|
| **A** | `"thinking":{"type":"adaptive"}` + `"output_config":{"effort":"low"}` | issue #73's adaptive probe |
| **B** | `"thinking":{"type":"enabled","budget_tokens":1024}` | issue #73's budgeted probe |
| **C** | `"thinking":{"type":"adaptive"}` alone | is A's 400 about `thinking`, or about `output_config`? |
| **D** | `"output_config":{"effort":"low"}` alone | is `output_config.effort` accepted without `thinking`? |

The model set is every entry of this account's `GET /v1/models?limit=100` (11 models, `has_more:
false`) — the same eleven the issue names, listed here in the order the API returned them:

```
claude-fable-5-1  claude-opus-5  claude-sonnet-5  claude-fable-5  claude-opus-4-8  claude-opus-4-7
claude-sonnet-4-6  claude-opus-4-6  claude-opus-4-5-20251101  claude-haiku-4-5-20251001
claude-sonnet-4-5-20250929
```

**Two passes.** Pass 1 ran all four shapes on all eleven (44 calls). Pass 2 repeated A and B on all
eleven (22 calls) to check that the answers are validation, not sampling. **Pass 2 reproduced pass 1
in all 22 cells**, status code and error string identical. A third pass (§4) probed five names that
are *not* in the model listing.

---

### 2. Results — shapes A and B, the two the issue names

`200` / `400` are HTTP status codes. `blocks` is the content-block type sequence of a 200.

| model | A adaptive | B budgeted | dialect | issue #73 says | agree? |
|---|---|---|---|---|---|
| `claude-fable-5-1` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-opus-5` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-sonnet-5` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-fable-5` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-opus-4-8` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-opus-4-7` | 200 `[text]` | 400 | ADAPTIVE | ADAPTIVE (confirmed) | ✅ |
| `claude-sonnet-4-6` | 200 `[text]` | 200 `[thinking, text]` | **both** | both | ✅ |
| `claude-opus-4-6` | 200 `[text]` | 200 `[thinking, text]` | **both** | both | ✅ |
| `claude-opus-4-5-20251101` | 400 | 200 `[thinking, text]` | **BUDGETED** | BUDGETED | ✅ |
| `claude-haiku-4-5-20251001` | 400 | 200 `[thinking, text]` | **BUDGETED** | BUDGETED | ✅ |
| `claude-sonnet-4-5-20250929` | 400 | 200 `[thinking, text]` | **BUDGETED** | BUDGETED | ✅ |

**No cell disagrees with the issue body.** The live API is the authority and it confirms the paste;
nothing in the design rests on the pasted table.

The two 400 messages are exact, stable across both passes, and are the ones
`anthropic-thinking-traces.md` §2.1 already quotes:

```text
"thinking.type.enabled" is not supported for this model. Use "thinking.type.adaptive" and
"output_config.effort" to control thinking behavior.
```
```text
adaptive thinking is not supported on this model
```

Both are `"type": "invalid_request_error"`, i.e. request validation — which is why one repeat was
enough to call them deterministic.

#### 2.1 One counting correction to the issue body

Issue #73 writes *"All six `ADAPTIVE` rows are confirmed"* and then lists six **model names**. The
registry has **six ADAPTIVE prefix rows** — `claude-fable-5`, `claude-opus-5`, `claude-opus-4-7`,
`claude-opus-4-8`, `claude-sonnet-5`, `claude-mythos` — and the six names above land on **five** of
them, because `claude-fable-5-1` matches the `claude-fable-5` prefix rather than having a row.
`claude-mythos` has no reachable model in this account and is still unconfirmed.

So: **six model names confirmed, five of six rows confirmed.** That is exactly the distinction
`docs/backlog/llm-config-surface-open-items.md` L-6 spells out and says it was corrected in review
for getting wrong the first time; the issue body reintroduces it.

---

### 3. Results — shapes C and D, the controls

| model | C adaptive alone | D effort alone |
|---|---|---|
| `claude-fable-5-1` | 200 `[text]` | 200 `[text]` |
| `claude-opus-5` | 200 `[text]` | 200 `[text]` |
| `claude-sonnet-5` | 200 `[text]` | 200 `[text]` |
| `claude-fable-5` | 200 `[thinking, text]` | 200 `[text]` |
| `claude-opus-4-8` | 200 `[text]` | 200 `[text]` |
| `claude-opus-4-7` | 200 `[text]` | 200 `[text]` |
| `claude-sonnet-4-6` | 200 `[text]` | 200 `[text]` |
| `claude-opus-4-6` | 200 `[text]` | 200 `[text]` |
| `claude-opus-4-5-20251101` | **400** *adaptive thinking is not supported on this model* | 200 `[text]` |
| `claude-haiku-4-5-20251001` | **400** *adaptive thinking is not supported on this model* | **400** *This model does not support the effort parameter.* |
| `claude-sonnet-4-5-20250929` | **400** *adaptive thinking is not supported on this model* | **400** *This model does not support the effort parameter.* |

Two findings, and the second is new information.

- **C attributes every 400 in the A column to `thinking.type=adaptive`, not to `output_config`.**
  A and C give the same status and the same message on all eleven, so the issue's shape A — which
  varies two fields at once — was not measuring a conflated thing after all. This is a control that
  passed, which is worth recording precisely because it could have failed.

- **`output_config.effort` is a *third*, independent axis, and the three BUDGETED models do not agree
  on it.** `claude-opus-4-5-20251101` accepts an effort with no `thinking` parameter (200);
  `claude-haiku-4-5-20251001` and `claude-sonnet-4-5-20250929` reject it outright with a message of
  its own. The dialect axis does not predict it.

  Nothing in AIMON sends that combination — `AnthropicLlmClient.buildRequest` sets `outputConfig`
  only inside `thinking.filter(ThinkingConfigParam::isAdaptive).isPresent()` — so today this measures
  a guard rather than a bug. It does mean the guard is **load-bearing and now measured**: drop it and
  two of the three models this design adds rows for answer 400.

---

### 4. Not in the model listing, and callable anyway — the undated 4-5 aliases

The issue names only the dated 4-5 snapshots, because those are what `GET /v1/models` returns. A third
pass asked whether the undated aliases resolve. **They do**, and the `model` field of the response says
what they resolved to:

| name sent | status | `model` in the response | dialect |
|---|---|---|---|
| `claude-opus-4-5` | A **400**, B **200** | `claude-opus-4-5-20251101` | **BUDGETED** |
| `claude-sonnet-4-5` | A **400**, B **200** | `claude-sonnet-4-5-20250929` | **BUDGETED** |
| `claude-haiku-4-5` | A **400**, B **200** | `claude-haiku-4-5-20251001` | **BUDGETED** |
| `claude-opus-4-6-20260204` *(guessed snapshot)* | 404 | — | unknown |
| `claude-sonnet-4-6-20260217` *(guessed snapshot)* | 404 | — | unknown |

Three consequences, all of which reach the design.

1. **Six names are measured BUDGETED, not three.** The alias is the name a deployment is most likely
   to write, and it is the name the model listing does not mention.
2. **`GET /v1/models` is not the set of callable names.** The probing method every document in this
   stack inherits ("every `claude-*` model this account can reach", read off the listing) understates
   the reachable set. Recorded here because the same method will be used again.
3. **The undated 4-6 snapshots do not exist**, so a dated `claude-opus-4-6-*` name is unmeasured.
   The two guesses were of the shape the listing's `created_at` implies; both 404. Nothing is claimed
   about dated 4-6 names.

Finding 1 is what settles prefix-vs-exact for the new rows: a prefix `claude-sonnet-4-5` covers the
alias **and** the dated snapshot with one row, which is the reason the ADAPTIVE block already gives
for using prefixes.

Finding 1 also has a cost the design has to pay: `claude-sonnet-4-5` is the literal string two
Anthropic test classes use as their *undescribed* model. It was a real, callable, and now measured
model name all along.

---

### 5. The vendor's own preference for the two both-dialect models

Not a probe — two artefacts already in the tree, found while deciding what "both" should record.

**The vendor's per-model table**, quoted at `docs/design/llm/anthropic-thinking-traces.md` §2.1, lists
Opus 4.6 and Sonnet 4.6 as `Adaptive, extended (deprecated)` with `Rejected with 400: None`. That is
the same "both" the probes measured, plus a direction: one of the two is deprecated.

**The vendored SDK says which, and why.** `com/anthropic/helpers/ThinkingWarnings.kt` in
`anthropic-java-core:2.13.0`:

```kotlin
private val MODELS_TO_WARN_WITH_THINKING_ENABLED = setOf("claude-opus-4-6")
...
"WARNING: Using Claude with $model and 'thinking.type=enabled' is deprecated. Use
 thinking.type=adaptive instead which results in better model performance in our testing: ..."
```

Three observations that matter:

- It is called from `MessageServiceImpl` / `MessageServiceAsyncImpl` (blocking and async, plain and
  beta) at **every** `create` / `createStreaming`, on `params.thinking()`. So a deployment on
  `thinkingMode: extended` with `claude-opus-4-6` gets this line **once per LLM call, on stderr,
  outside SLF4J and outside `AnthropicLlmClient`'s once-per-signature register** — today, unchanged
  by anything in this repository.
- The SDK's set names **only `claude-opus-4-6`**, while the vendor's table marks Sonnet 4.6 deprecated
  too. The SDK set is narrower than the documentation.
- My probes used raw HTTP, so this warning was **not** observed firing — it is read from the SDK
  source and its call sites, not measured.

---

### 6. Raw per-model records

Pass 1, all four shapes. `in`/`out` are `usage.input_tokens` / `usage.output_tokens`; every 200 ended
`stop_reason: end_turn` and echoed back the model name it was sent (aliases excepted — see §4).

```
claude-fable-5-1            A 200 [text]            in=18 out=4
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [text]            in=18 out=4
                            D 200 [text]            in=18 out=4
claude-opus-5               A 200 [text]            in=16 out=4
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [text]            in=16 out=4
                            D 200 [text]            in=16 out=4
claude-sonnet-5             A 200 [text]            in=16 out=4
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [text]            in=16 out=4
                            D 200 [text]            in=16 out=4
claude-fable-5              A 200 [text]            in=16 out=4
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [thinking, text]  in=16 out=14
                            D 200 [text]            in=16 out=4
claude-opus-4-8             A 200 [text]            in=16 out=4
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [text]            in=16 out=4
                            D 200 [text]            in=16 out=4
claude-opus-4-7             A 200 [text]            in=21 out=6
                            B 400 invalid_request_error  "thinking.type.enabled" is not supported ...
                            C 200 [text]            in=21 out=6
                            D 200 [text]            in=21 out=6
claude-sonnet-4-6           A 200 [text]            in=14 out=4
                            B 200 [thinking, text]  in=43 out=13
                            C 200 [text]            in=14 out=4
                            D 200 [text]            in=14 out=4
claude-opus-4-6             A 200 [text]            in=14 out=4
                            B 200 [thinking, text]  in=43 out=13
                            C 200 [text]            in=14 out=4
                            D 200 [text]            in=14 out=4
claude-opus-4-5-20251101    A 400 invalid_request_error  adaptive thinking is not supported on this model
                            B 200 [thinking, text]  in=43 out=31
                            C 400 invalid_request_error  adaptive thinking is not supported on this model
                            D 200 [text]            in=115 out=4      <-- see note
claude-haiku-4-5-20251001   A 400 invalid_request_error  adaptive thinking is not supported on this model
                            B 200 [thinking, text]  in=43 out=46
                            C 400 invalid_request_error  adaptive thinking is not supported on this model
                            D 400 invalid_request_error  This model does not support the effort parameter.
claude-sonnet-4-5-20250929  A 400 invalid_request_error  adaptive thinking is not supported on this model
                            B 200 [thinking, text]  in=43 out=45
                            C 400 invalid_request_error  adaptive thinking is not supported on this model
                            D 400 invalid_request_error  This model does not support the effort parameter.
```

Two oddities recorded rather than explained, because neither is load-bearing and guessing at them
would be the kind of inference this record exists to avoid:

- **`input_tokens` is not constant for a constant `messages` array** — 14/16/18/21 across models with
  thinking, 43 on every successful budgeted call, and **115** on `claude-opus-4-5-20251101` shape D
  alone. Something server-side is added to the prompt and its size depends on the model and on the
  request shape. Unexplained.
- **A successful adaptive call usually returned no `thinking` block** (`[text]` on 7 of 8 adaptive
  200s; `claude-fable-5` shape C was the exception). Every successful budgeted call returned
  `[thinking, text]`. On a one-word prompt at `effort: low` this is unsurprising and it is **not**
  evidence about either dialect's quality — it is not used as an argument anywhere in the design.

### 7. Cost

66 requests, all `max_tokens: 2048` against a one-sentence prompt. Largest single response was 46
output tokens.

---

## 15. After the build — departures, and where this document is wrong

*Appended 2026-09-10, after implementation. Everything above this line is the body as approved; it is
not edited to look prescient. Two sources feed this section: the departures recorded in the run's
`build/deviations.md`, and the design review (PASS, no blocking findings, seven non-blocking ones,
most of them errors in this document's inventories rather than in what it asks to be built).*

### 15.1 Where this document states something false

**C-1 — §12's "confirmed against history" is not confirmed by history.** The sentence reads *"the
index row was **added** by `d3500f6` (#62, 2026-09-10), after that move, from stale figures."* It is
wrong about the row it names. `git log -L306,306:docs/backlog/README.md` shows the
`spring-boot-starter-open-items.md` row present at **`eec9ccd` (Initial commit)** already reading
`25 | 5` and **never modified since**; `d3500f6` adds only the `llm-config-surface` and
`reasoning-delta-stream` rows.

The real provenance is better evidence for the same conclusion. **`6a07573` (#46, 2026-09-09)**
changed that file's *own title* from `닫힘 25 · 해소 5` to `닫힘 26 · 해소 4` when B-21 was reopened
and closed, and left the index alone — so the two records diverged at a nameable commit that touched
one and not the other. The issue body's own *"introduced by #54"* is wrong too: `320fbbc` never
touches `docs/backlog/README.md`.

The fix is unaffected, because §12 settled it by counting rather than by provenance, and the count was
reproduced twice more (by the review and by the build) before it was written down. A document that
spends §2 and §12 correcting other people's counts had no business shipping this sentence, and the
corrected version is what went into `docs/backlog/README.md`.

**C-2 — §7 says three reads must change; two must, and the third must not.** §6.2 names two
(`resolveNamedDialect`'s guard, `resolveAutoDialect`'s). The third `ThinkingDialect` read — the
branch that picks the wire shape — is precisely the one §4.1's invariant exists to protect, since
`EITHER` must never reach it. An implementer counting to three would have gone and "fixed" the one
line that had to stay. Two changed; the shape branch is untouched and is now pinned by two tests.

**C-3 — §3.1's "except the one record that says why it carries none" is singular, and there are
four.** `EXPLAINS_ABSENCE` covers `thinkingDialectUnknown@<model>`, `thinkingBudgetImpossible`,
`reasoningEffortWithThinkingOff` and `thinkingDisplayWithThinkingOff`. The first is the one the
singular reading would have silenced, and it is the whole of
[`reasoning-model-enablement.md`](reasoning-model-enablement.md) §7 row 3's mitigation. Each
classification is now stated at its call site, and the resolver's own test asserts both survivor sets
rather than leaving a `singleElement` assertion elsewhere to notice by luck.

**C-4 — §9's fixture-cost table omits the worst javadoc of the four.**
`AnthropicThinkingDialectTest`'s class javadoc said both *"`claude-sonnet-4-5` is deliberately not in
it"* and *"the built-in table has none"* of the `BUDGETED` rows. This change makes both false. The
table lists that class's constant and three golden bodies but names only two other classes as needing
prose. Rewritten, and it now records why the fixture moved.

### 15.2 Where the implementation departed

**D-1 — two javadoc touches in `at/aimon/core/llm/capability/` the task's division of labour did not
authorise.** §6.1 proposed the `ModelCapabilities.java` narrowing with a fallback ("say so plainly if
a reviewer prefers zero touches"), and the review's fifth finding is that this is a judgement the task
did not grant and so belongs said out loud rather than only in a fallback clause. Taken: two
prose-only sentences in `ModelCapabilities.java` (`unknown()` and `thinkingDialect()`) and one clause
in `package-info.java`, all of which assert as a universal that this axis is *mutually exclusive* —
which this same change measures to be false for two models. No signature, no value, no field; a
rebase against the sibling run is line-local, and reverting them is a two-line revert that leaves the
rest of the change standing.

**D-2 — #75 item 1 went to three places, not §6.4's two.** §6.4 and O-2 chose to put the sentence
beside each fold and touch neither file the task names, because neither named file contains a fold.
That is right and it is also incomplete: `AimonProperties.java:1441` already carries half the sentence
and is a file the task explicitly permitted for this. All three now name the other copy and state the
reassurance — both folds derive from `values()`, so a fifth constant cannot reach one surface and miss
the other.

**D-3 — B-1's blast radius is wider than §8 says.** Sending thinking also opens the inner gate in
`applySamplingParameters`, so on the five newly-described models under `thinkingMode: auto` a
configured `temperature` is now **omitted** with a `temperatureOmittedForThinking` warning where the
request previously carried it silently, and `top_p` outside `[0.95, 1.0]` goes the same way. The
CHANGELOG says so. No existing test breaks.

**D-4 — `builderWithDefaults()` exceeded Checkstyle's 150-line method limit**, which no section
anticipated. The eleven `claude-*` prefixes moved into a private `registerAnthropicDefaults(Builder)`.
Registration order is free for all eleven, so lifting them out of the chain changes nothing the table
answers, and the comment says so and says how it was checked.

**D-5 — the §9 fixture replacement carries the caveat the review asked for.**
`claude-sonnet-4-20250514` is undescribed **by the table**, not undialected in fact — the vendor's own
per-model table puts the Claude 4 generation in the extended-only group, so a documentation-derived
row will eventually move it. Both classes gained an explicit assertion that the constant really is
undescribed, and both javadocs now say which of the two facts the fixture depends on, so the next
reader reaches for the synthetic-name fallback knowingly.

### 15.3 One thing neither this document nor the review found

`AnthropicThinkingLiveTest.DialectMismatchesAreRejected` was **already red on `main`**, and this change
would have reddened its twin. Both tests are gated on `ANTHROPIC_KEY`, which CI does not have, so
nothing noticed.

The cause is structural rather than incidental: both assert the *server's* two rejection sentences,
and the client's dialect translation exists precisely to stop a request in that shape from ever being
sent. `claude-sonnet-5` has had an `ADAPTIVE` row since #60, so `thinkingMode: extended` against it
comes out adaptive and succeeds — that half has been red since the day the table shipped. §5's new
`claude-haiku-4-5` row does the same to the other half.

Fixed by suppressing the capability table in both, which is the instrument the sibling nested class
already uses for its own negative control, plus a **new positive control** asserting that with the
table in force neither request is sent in the shape that earns the rejection. Without that third test,
"we removed the table to make them fail" would be indistinguishable from "we removed the table to make
them pass".

### 15.4 What §11's open questions became

Three of the eight have consequences beyond this phase and are registered in
[`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md),
which is this repository's canonical record of what is open:

| §11 | backlog | why it outlives this phase |
|---|---|---|
| **O-1** | **L-9** | whether the sibling run gives `thinkingDialect` a configuration key, and whether its value list then needs `EITHER`. A coordination item that survives the merge |
| **O-3** | **L-10** | a both-dialect model that prefers the *budgeted* shape needs a fact this design has no room for. Trigger: a vendor table row marking `adaptive (deprecated)` |
| **O-7** | **L-11** | `claude-mythos` ships one prefix over two documented dialect states and no reachable model to measure |

The other five stay here and are answered rather than open. **O-2** and **O-5** are settled by what
shipped (three cross-reference sentences; §3.5 exists in
[`reasoning-model-enablement.md`](reasoning-model-enablement.md)). **O-4** was answered by the review,
which took the resolver extraction. **O-8** was answered by the count. **O-6** is a recorded
measurement oddity with no consumer — §14.6 carries it, and guessing at it is the kind of inference
that record exists to avoid.

---

## 16. The `AUTO` budget policy, decided (#83, 2026-09-10)

*Appended 2026-09-10 by a later issue. Not a correction — §15 remains the one place this document is wrong, and
nothing above is edited. This section records a decision #73 left open: that build noticed the behaviour below and
handed it on as "worth a maintainer's eye" rather than decide a budget policy inside a change about the dialect
table. It lives here because this is the change that made the behaviour reachable, and §8 is the table that should
have carried it.*

**In one line: `thinkingMode: auto` on a model the table marks `BUDGETED` may resolve to a request whose thinking
budget consumes all but one output token, and that is allowed.** Issue #83 offered three policies and the maintainer
chose the first — **leave the behaviour exactly as it is, and record why**. No production behaviour changed with it.
What follows is the why, written so the next reader finds the argument already had.

### 16.1 The arithmetic, checkable without the issue

Line numbers as of 2026-09-10.

- **`AUTO` on a budgeted row takes `EXTENDED`'s branch.** `AnthropicThinkingResolver.java:165` hands `AUTO` to
  `resolveAutoDialect`, which returns the row's own dialect when it is neither `EITHER` nor `UNKNOWN`
  (`:182-183`), and `resolve` then takes the budgeted branch (`:92-96`) — the one `EXTENDED` reaches on the same
  row.
- **The rows are the three §14 measured.** `InMemoryModelCapabilityRegistry.java:355-357` registers
  `claude-opus-4-5`, `claude-sonnet-4-5` and `claude-haiku-4-5` as prefixes carrying `BUDGETED_DIALECT_ONLY`
  (`:141-142`); §14.4 is why they are prefixes and why they cover six measured names.
- **An unset effort is the middle rung.** `AnthropicThinkingBudgets.java:105-107` answers a `null` effort with
  `MEDIUM_BUDGET`, which is 4096 (`:84`).
- **The clamp.** `AnthropicThinkingBudgets.java:140` is `Math.min(requested, maxTokens - 1)`, floored at 1024
  (`:141`). `max_tokens` is `modelConfig.getMaxTokens().orElse(config.getMaxTokens())`
  (`AnthropicLlmClient.java:375`), and `AnthropicConfig`'s default is 4096 (`AnthropicConfig.java:41`, applied at
  `:290`). A requested 4096 against 4096 sends `budget_tokens: 4095`; thinking tokens count against `max_tokens`,
  so one token is left for the answer.
- **The warning.** Recorded at `AnthropicThinkingResolver.java:419-424` as `REQUIRES_THINKING` — it always
  survives the filter, because the parameter it describes is set immediately after (`:426-427`) — under the
  signature `thinkingBudgetClamped=4096->4095` (`:420`); emitted at `AnthropicLlmClient.java:400-401`; logged by
  `reportDivergence` as `log.warn(message, args)` (`:684`). **The signature is the dedup key and is not in the log
  line.** What an operator reads starts *"A thinking budget of 4096 tokens does not fit under maxTokens 4096;
  sending 4095 instead"* and carries *"Raise maxTokens"* (`AnthropicThinkingResolver.java:421-423`). It is logged
  **once per client instance** (`AnthropicLlmClient.java:681`, over the set declared at `:141`), for the reason
  `:147-148` gives: what it describes was set once, in a configuration file.

The `MINIMAL` and `LOW` rungs, 1024 and 2048 (`AnthropicThinkingBudgets.java:82-83`), fit under 4096 and produce no
clamp and no warning. Reason 4 below depends on that.

The row §8 should have carried, in that table's voice:

| # | Shape | Disposition |
|---|---|---|
| **15** | `AUTO` on a budgeted family, with a `max_tokens` above 1024 and no larger than the budget — `AnthropicConfig`'s default 4096 is the common case — spends the whole output allowance on thinking | Left as is (#83). One WARN per client, naming `Raise maxTokens`; the second remedy, a lower `reasoningEffort`, is named by both operator guides and not by the warning. §8's B-1 stopped at *"sends a thinking parameter and bills for it"* and did not reach the budget |

`AnthropicThinkingDialectTest.autoOnABuiltInBudgetedRowClampsUnderTheConfigDefaultMaxTokens` pins the request end to
end — a built-in row, no effort, no `maxTokens` on the call: `max_tokens` 4096, `budget_tokens` 4095, exactly one
warning, asserted on its message. It is the guard against this section quietly becoming false: a headroom policy
added to `AUTO` turns it red.

### 16.2 Why it stays — five reasons

1. **It is what `extended` has always done on this dialect.** The clamp arrived with #47 (`5cbc6da`), the day
   before this document's rows, and #47's record weighed this exact arithmetic:
   [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §12 `Q-5` answers *"It clamps to 4095 and warns,
   which is correct"* and declines to raise `AnthropicConfig`'s default as a side effect of choosing a thinking
   mode. This document's change did not alter what the clamp does. It altered who reaches it: before the rows,
   `auto` had no dialect to send on these families, so it sent nothing and warned.
2. **Consistency between the two modes has value, and divergence is what option 2 costs.** `AUTO`'s contract is that
   the table decides the dialect — [`reasoning-model-enablement.md`](reasoning-model-enablement.md) §3.4: *"use the
   model's dialect when the table knows it"* — not that the client decides how much. A headroom rule under `AUTO`
   alone would send two different budgets to the same model, at the same `max_tokens` and effort, depending on
   which word the operator wrote, and `AnthropicThinkingResolver` would hold two budget policies where it holds one.
3. **"Usable headroom" is a number nobody has measured.** Every candidate — a fraction of `max_tokens`, a floor for
   the answer, a fixed reserve — would be chosen the way the ladder's two middle rungs were, and
   `AnthropicThinkingBudgets.java:36-44` labels those **arbitrary** in its own javadoc. There is no measurement to
   choose it from, so option 2 would do what the issue itself warns against: it *"replaces a loud, correct warning
   with a quiet, arbitrary policy."*
4. **The warning names its own remedy.** *"Raise maxTokens: thinking tokens are counted against it, so the reply is
   what this squeezes out, not the reasoning"* (`AnthropicThinkingResolver.java:421-423`) — an operator who reads it
   is one edit from a request that can answer. Two precisions keep this reason honest. **"Loud" means once per
   client**, at the first request that clamps, not on every call: right for a configuration fact, and a reason not
   to read a quiet log after that first request as a fixed deployment. **And the warning names one of two edits
   that work.** Lowering the effort to `low` (2048) or `minimal` (1024) also fits under 4096 — `llm.reasoningEffort`
   / `aimon.llm.reasoning-effort`, which `AnthropicThinkingResolver.java:301-303` reads after the agent
   definition's own `model.reasoningEffort`. Both operator guides name the second remedy; whether the warning
   should, and its `only 1 tokens` grammar, are backlog `L-15`. **2026-09-10 (#89):** this reason covers the
   requests the warning fires on — a clamp — and no others. A budget that fits under `max_tokens` by a single token
   leaves the same one-token answer and is sent with no warning; which requests the warning covers, and why that
   stays, is §16.8.
5. **Nothing here is reachable without opting in.** `AnthropicConfig`'s default `thinkingMode` is `OFF`
   (`AnthropicConfig.java:43`, applied at `:294`), and keeping it there was its own decision —
   [`reasoning-model-enablement.md`](reasoning-model-enablement.md) §3.4: *"Making `AUTO` the default would turn
   thinking on, and bill for it, in every Anthropic deployment that upgrades without reading the changelog."* A
   deployment that never wrote a thinking mode sends no thinking parameter and never meets this.

### 16.3 The two alternatives, and why each is refused

- **Option 2 — reserve headroom under `AUTO`**, a fraction or a floor, so that `auto` means *a request that can
  actually answer*. Refused for reasons 2 and 3: it makes `auto` and `extended` diverge on the same model, and the
  reserve is a number nobody has measured.
- **Option 3 — refuse: send nothing under `AUTO` when the budget cannot leave an answer, and warn as before the
  rows.** The safest, and in the issue's own words it *"partly un-does what #73 was for"*. The rows exist so that
  `AUTO` acts on a measured fact; declining to act on the only three rows that say `BUDGETED` hands back, on exactly
  those models, the defect backlog `L-6` recorded and this document's change closed — `thinkingMode: auto` getting
  nothing on them. And it leaves `extended` clamping as before, so it too makes the two modes behave differently on
  the same model.

### 16.4 Who actually meets it

The issue calls 4096 *"the shipped default"* and describes the exposed deployment as one that *"only ever wrote
`thinkingMode: auto` and never touched `maxTokens`"*. That is true of the library and not of the CLI as shipped, and
the difference is the population.

- **`max_tokens` is the agent's before it is the client's.** `AnthropicLlmClient.java:375` prefers
  `LlmModel.getMaxTokens()`, which an agent definition sets from `model.maxTokens`
  (`MarkdownAgentDefinitionParser.java:164-165`); `AnthropicConfig`'s 4096 applies only when the agent sets none.
  Neither configuration surface binds an LLM `maxTokens`: `LlmClientFactory` never sets it, and
  `AimonLlmAutoConfiguration.java:191-209` builds `AnthropicConfig` without it.
- **Every agent definition bundled with the CLI sets `maxTokens: 40000`**
  (`modules/aimon-cli/src/main/resources/agents/{default,default-anthropic,default-openai,ops-agent}/agent.md`),
  which clears every rung, `HIGH`'s 16000 included. The CLI loads `agents/default` unless configured otherwise
  (`default-config.yaml:124`, `AgentSetupFactory.java:140`, `:507`, `:872`), but that bundle names `gpt-5.1`; the
  only bundle naming a budgeted family is `default-anthropic` (`claude-sonnet-4-5`), where `AUTO` sends
  `budget_tokens: 4096` unclamped. No CLI deployment on a bundled definition meets this.
- **The exposed population is a shape, not a count:** any agent whose model settings carry no `maxTokens`, or one no
  larger than the requested budget (4096 with no effort set, 16000 at `high`) — which includes every starter
  application that leaves it out, since the starter ships no agent definition and binds no LLM `maxTokens`. Above
  1024 and up to that budget the answer is left one token; at or below 1024 no budget fits at all and the thinking
  parameter is omitted instead, with its own warning (`thinkingBudgetImpossible`,
  `AnthropicThinkingResolver.java:404-409`). Nothing in this repository can count how many deployments that shape
  holds. **2026-09-10 (#89):** above the requested budget the answer is left `max_tokens` minus the budget — one
  token at exactly one above it — and nothing is said; §16.8.

This narrows who is affected; it does not reverse the decision.

### 16.5 What would re-open this

The decision is meant to be falsifiable, and these are its triggers:

- **A measurement of usable headroom** — an answer allowance that is measured rather than chosen removes reason 3.
- **A vendor change that stops counting thinking tokens against `max_tokens`** — the arithmetic stops existing.
- **`O-2` being taken** ([`anthropic-thinking-config-surface.md`](anthropic-thinking-config-surface.md) §13). An
  explicit budget under `AUTO` changes the shape, because the operator would then have written the number the
  warning is about. #83 does not answer `O-2`, and that document's §14 says so beside it.
- **A change to `AnthropicConfig`'s default `maxTokens`**, which `Q-5` refused once as a side effect and which would
  move §16.4's population.

Naming the second remedy in the warning is **not** a trigger: it changes what the warning says, not what `AUTO` does.
It is backlog `L-15`.

### 16.6 Where else this is written

One rationale, here; everywhere else a sentence and a pointer back.

| Where | What it carries |
|---|---|
| `CHANGELOG.md` `[Unreleased]` | the #73 behaviour-change bullet names the clamp, scoped to `AnthropicConfig`'s default, and points here; the #54 budget bullet's *"untouched deployment"* and the effort-ladder bullet's *"out of the box"* are scoped the same way |
| [`aimon-core-integration-via-cli-reference.md`](../../getting-started/aimon-core-integration-via-cli-reference.md) (+ `.en.md`) | a paragraph after the `thinkingMode` table — the budget under `auto`, both remedies, a link here — and the `thinkingBudgetTokens` ceiling scoped |
| [`embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md) (+ `.en.md`) | the same paragraph in the starter's keys; its ceiling sentence was already true, since the starter ships no agent definition |
| `modules/aimon-cli/src/main/resources/default-config.yaml` | a clause on the `auto` value, and the `thinkingBudgetTokens` comment scoped — comments only |
| [`anthropic-thinking-config-surface.md`](anthropic-thinking-config-surface.md) §14 | two dated notes: beside `O-2`, which #83 does not answer, and beside D-4's *"defaults to 4096"* |
| [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) `L-15` | the warning's wording, and whether it should name the second remedy |
| `AnthropicThinkingDialectTest`, `AnthropicThinkingBudgetsTest` | the request pinned end to end on a built-in row, and the unset-effort clamp stated directly |

### 16.7 Where the build departed from #83's reviewed design

The design this section was built from was reviewed twice outside the repository. Four of its statements would have
landed false — three found by its second review, one by the build — and each was corrected here. None changes the
decision.

- **It counted five sentences calling 4096 the default unconditionally; there were six.** The effort-ladder bullet
  in `CHANGELOG.md` also said *"The clamp bites out of the box"*, false on the CLI for §16.4's reason, and was
  scoped with the rest.
- **It described the exposed population as agents that omit `model.maxTokens`.** One that sets a value no larger
  than the requested budget meets the same clamp, and §16.4 says so.
- **It had the CLI guide name the default agent bundle** as a deployment that sends a budget unclamped. That bundle
  names `gpt-5.1` and never reaches a budgeted family, so the guide says "the bundled agent definitions" and §16.4
  names `default-anthropic` as the one that does.
- **It had the dated D-4 note in `anthropic-thinking-config-surface.md` say all three surfaces named there were
  scoped.** Two were; the starter guide's sentence is true as written and was left alone, and the note says which.

### 16.8 Which requests the clamp warning covers, decided (#89, 2026-09-10)

*Appended 2026-09-10 by a later issue. Not a correction: §16.1–§16.7 stand as written, and the only edits to them are
two dated pointers, in §16.2 reason 4 and §16.4. #83's build review raised this as its most important non-blocking
finding, and #83 deliberately left it out of a documentation-only change. Issue #89 then offered three policies, and
**the maintainer delegated the choice to that issue's run**: what follows is that run's decision, merged on the
maintainer's confirmation.*

*2026-09-11 (#101, #108, #100): edited in place where it stated more than the code supports, and where the code under
reason 3 changed. What each sentence said before, and why it changed, is §16.10. The decision is untouched.*

**In one line: `thinkingBudgetClamped` covers a clamp — a request whose budget was reduced to fit under `max_tokens` —
and not every request whose answer allowance is small. A budget that fits by one token is sent without a warning, and
that is allowed.** #89's third option was taken: leave the code, narrow the record. No production behaviour changed
with it.

**Exactly which requests the warning covers.** Line numbers as of 2026-09-10 (`ade5978`). The
`thinkingBudgetClamped=<requested>-><sent>` finding is **recorded and emitted** for a request — §3.1's two terms —
**if and only if** all three hold:

1. **The request carries a budgeted `thinking` parameter.** `thinkingMode` is not `off`; the effective
   `reasoningEffort` (the call's `LlmModel` first, then `AnthropicConfig`) is not `none`; and the dialect resolves to
   `BUDGETED`. That means `extended` on a row that is `BUDGETED`, `EITHER` or undescribed; `auto` on a `BUDGETED` row;
   or `adaptive` translated on a `BUDGETED` row (`AnthropicThinkingResolver.java:78-96`, `:182-183`, `:207`,
   `:213-234`).
2. **`max_tokens` is above 1024** — the call's `LlmModel.maxTokens`, else `AnthropicConfig.getMaxTokens()`, which is
   4096 unless `AnthropicConfig.Builder.maxTokens(int)` set it (`AnthropicLlmClient.java:375`,
   `AnthropicConfig.java:41`). At or below 1024 no budget is sent, and `thinkingBudgetImpossible` is reported instead
   (`AnthropicThinkingBudgets.java:136-137`).
3. **The requested budget is at least `max_tokens`.** The requested budget is the explicit `thinkingBudgetTokens` if
   one is set (legal under `extended` only), else the effort's rung (1024 / 2048 / 4096 / 16000), else 4096 when no
   effort is set (`AnthropicThinkingBudgets.java:101-116`).

When it is recorded, the budget sent is `max_tokens − 1` and exactly one token is left for the answer, every time,
which is why the message always reads `only 1 tokens` (L-15). **Emitted is not the same as logged.**
`reportDivergence` logs an emitted finding at WARN at most once per `AnthropicLlmClient` instance per distinct
`requested->sent` pair, and not at all once that client's once-per-signature register, `reportedDivergences`, holds
`MAX_REPORTED_DIVERGENCES` (32) signatures. That register is shared by the divergences the client reports once — facts
of the configuration. It is not shared by every divergence: conditions of the traffic (a stream that loses a
`signature_delta`, a stored payload this build cannot parse, a trace whose tool use is gone, a trace from another
provider) go to a second register, `recurringDivergences`, which `reportRecurringDivergence` reports at the 1st, 10th,
100th … occurrence under the same cap. A second covered request on the same client is covered, and adds nothing to the
log.

**It covers nothing else.** A requested budget below `max_tokens` is sent as asked, however little it leaves —
including one that fits by exactly one token, which leaves the same one-token worst case as a clamp.

**The issue's table, re-derived.** The design run compiled this repository's own main sources and ran the resolver
over #89's rows. Every cell of the issue is correct:

| Request | `max_tokens` | `budget_tokens` sent | nominal tokens left | warning |
|---|---|---|---|---|
| `auto`, `BUDGETED` row, no effort | 4096 | 4095 | 1 | `thinkingBudgetClamped=4096->4095` |
| `auto`, `BUDGETED` row, no effort | 4097 | 4096 | 1 | none |
| `auto`, `BUDGETED` row, no effort | 4100 | 4096 | 4 | none |
| `extended`, `thinkingBudgetTokens: 8000`, a `BUDGETED`, `EITHER` or undescribed row | 8001 | 8000 | 1 | none |
| the same | 8000 | 7999 | 1 | `thinkingBudgetClamped=8000->7999` |

Two things the issue does not say. The `extended` rows hold only off `ADAPTIVE` rows: there, `extended` is translated
and carries no budget at all. And "fits by one token, no warning" holds for every source of a budget, including the
`LOW` and `HIGH` rungs and a translated `adaptive` request, not only for `auto` at 4096.

The "tokens left" column is **nominal**. The vendor's extended-thinking page (fetched 2026-09-10) says *"The budget is
a target rather than a strict cap. Actual token usage varies with the task, and Claude may stop reasoning well before
the budget is exhausted; `max_tokens` remains the hard ceiling on total output."* It publishes no answer allowance,
headroom or ratio. Its only pairing is an example, 16000 and 10000.

**The three options.**

- **Option 1 — warn when `max_tokens − budget` is below a floor. Refused.** Every floor this repository could source
  was examined:
  - **1** is the API's own rule (budget `<` `max_tokens`). It is the only floor that is not invented, and it fails
    #89's own scenario by one token: it catches 4097, then goes silent at 4098 with two tokens left, which answer no
    better than one. It draws the line where requests stop being *legal*, not where they stop being able to answer.
  - **1024** is the thinking minimum, borrowed for the answer.
  - **6000** is the vendor's example pair, and citing it would dress an arbitrary pick.
  - **24000** is the CLI bundles' 40000 minus `HIGH`, a configuration choice rather than a measurement.
  - **The answer's real size** is the right quantity. In this ReAct loop that is usually a `tool_use` block sized by
    its schema and its turn, and nothing has measured it. That is reason 3 of §16.2 again.

  The figure being compared is also not the outcome, because the budget is a target.
- **Option 2 — warn when the budget exceeds a share of `max_tokens`. Refused.** No share has a source: 50% has none,
  62.5% is the vendor example, 40% is the CLI bundles, and "all but one token" is option 1 as a ratio. The issue offers
  scaling as the advantage, and that is the objection: what has to fit, a tool call or a reply, does not grow with
  `max_tokens` or with the budget, so a ratio of those two settings measures neither.
- **Option 3 — leave the code and narrow this record. Taken**, for four reasons:
  1. **The number does not exist, and a threshold is the number #83 refused.** Operators edit until a warning stops,
     which is how #89's 4097 case arises. So any threshold becomes the deployment's de facto reserve, and reason 3 of
     §16.2 refused to choose that reserve for the request. Choosing it for the log is the same act.
  2. **These requests are not divergences.** The clamp warning is a `REQUIRES_THINKING` finding, *the request differs
     from what you configured* (`AnthropicThinkingResolution.java:24-25`). 4097 and 8000/8001 are sent exactly as
     configured, or as documented when no effort is set. §3.2's R-WHAT reports inert or reshaped combinations, and
     §3.3 already declined to report a request that is honoured and judged unwise. A warning here would be a third
     kind of finding with no rule, and the rule would need the number.
  3. **The realised squeeze is reported when it happens, and needs no number — in both of its shapes, by both agent
     executors.** Both read the provider-neutral `StopReason` through one definition,
     `at.aimon.core.agent.budget.TruncatedResponses`, so the answer is the same for every provider that maps its cut to
     `MAX_TOKENS`, blocking or streamed.
     - **A final answer cut off** — no tool call — ends as `CompletionReason.TRUNCATED`, with
       `[System: response truncated at max_tokens]` appended and a WARN, on a turn (`OrcaAgentExecutor`) and on a
       subagent fork (`DefaultSubagentExecutor.createTruncatedResult`). A streamed stop reason reaches both through
       `ChunkAggregator`'s `STREAM_END` handling.
     - **A cut inside a `tool_use` block** runs no tool. A call cut short still arrives as a tool call — its slot is
       registered when the block starts (`AnthropicStreamingMapper.onContentBlockStart`), and arguments that never
       finished parse to an empty map (`ChunkAggregator.parseArguments`) — and the response does not say which call was
       cut. So neither executor runs any call of a response that stopped at `max_tokens`: each is answered with an
       error result that names `max_tokens` (`TruncatedResponses.refusal`), a WARN names `max_tokens`, the iteration
       and the tool names, and the loop continues. On a turn, three such responses in a row end the execution as
       `ERROR` through the stalled-iteration guard; ~~a fork has no guard and repeats until its `maxIterations`
       (L-23).~~ *(2026-09-11, #133: no longer true — §16.11.)* This shape was backlog L-16 until #108 closed it, and
       closing it needed no number either (§16.10).
     - **The thinking share is given as counts.** When the cut response's usage reports reasoning tokens — both
       Anthropic paths fill `TokenUsage.getReasoningTokens()` from `usage.output_tokens_details.thinking_tokens`, and so
       does OpenAI's Responses path; Chat Completions does not — the four truncation WARNs end with the response's
       output and reasoning counts. No share is turned into a verdict: that would need the number reason 1 says does
       not exist.
     - **The Anthropic client's own** `Anthropic response was truncated due to max_tokens limit` is kept, and it is not
       what reports the squeeze to either executor. Its one caller, `convertResponse`, runs on the four-argument
       blocking `sendMessage`; the six-argument overload reroutes a call carrying a supported cancellation token
       through the stream, and both executors pass a live `SignalBackedLlmCancellation` on every call. OpenAI's two
       WARNs sit in the same position in `OpenAILlmClient`. Callers of the blocking overloads do reach them, and for
       them it is the only signal: compaction (`DefaultCompactionEngine`, through `LlmClient`'s five-argument default),
       ~~skill LLM execution (`LlmSkillExecutor`),~~ *(2026-09-11, #133: no longer true — §16.11.)* peer memory as the
       CLI assembles it (`LlmDialecticEngine`, `LlmDeriver`, `DefaultReconciler`, `RandomWalkDreamer`,
       `LlmJudgeSurprisalScorer`) and the wiki strategies. Counted 2026-09-11 in main sources as `sendMessage(` and
       `::sendMessage`, then by construction site: `ReActLlmDeriver` makes the same call and nothing constructs it.
  4. **The warning's text stays true.** Every request it fires on leaves exactly one token, and raising `maxTokens` is
     a remedy that works for it. Nothing here changes what it says (L-15).

  §16.2's reason 2 is **not** among these, and this has to be said. A warning would fire the same under `auto` and
  `extended`, so it does not make the two modes diverge.

**The cost.** An operator who follows the warning by the smallest step, 4096 to 4097, loses the warning and keeps a
one-token worst case. If thinking then spends its whole budget, the first thing they see is a truncated answer rather
than a line before the request — or, when the cut lands inside a tool call, a WARN naming `max_tokens` beside calls
that were refused rather than run. The trap is accepted, because the alternative is a line drawn by a number with no
source, which moves the same trap to wherever the line is. The silence on a cut tool call was not accepted, and it is
gone (§16.10). Where the warning's advice could say *how far* to raise `maxTokens` is L-15's question, not this one.

**What would re-open this.**

- **A measured, or vendor-published, answer allowance.** This removes reason 1, and it is §16.5's first trigger too.
- **Interleaved thinking being sent** (`anthropic-thinking-traces.md` A10 / F-3). The budget may then exceed
  `max_tokens`, and both the clamp and this coverage change.
- **A change to the clamp's target** away from `max_tokens − 1`. "Exactly one token" stops being true, and so does
  L-15's premise.
- **Evidence that the outcome signals above miss a squeeze ~~that L-22 does not already record~~.** *(2026-09-11,
  #133: no longer true — §16.11.)* A change to how a truncation is reported is not a trigger: #108 and #100 changed
  the report — a `max_tokens` stop named wherever the cut lands, on both executors, with the reasoning count beside
  it — without touching this warning or needing a number.

**Where it is pinned.** `AnthropicThinkingResolverTest.ClampWarningCoverage` asserts condition 3 as a property over
every rung, conditions 1 and 2 path by path in both directions, and the table's rows. `AnthropicThinkingDialectTest`
asserts end to end that 4097, 4100 and `extended` 8000/8001 go out with no warning from `AnthropicLlmClient`.
`AnthropicThinkingBudgetsTest` asserts that a fit by one token is not a clamp. A floor or proportion warning added
without revisiting this section turns them red. Reason 3's two shapes are pinned by `OrcaAgentExecutorTruncationTest`
and `DefaultSubagentExecutorTruncationTest`, which assert against the same `TruncatedResponses` constants.

### 16.9 Where the build departed from #89's reviewed design

The design §16.8 was built from was reviewed once outside the repository — PASS, no blocking findings, seven
non-blocking ones. Six of its statements would have landed false or incomplete — four found by that review, two by the
build — and each was corrected here. Two placements moved as well. None changes the decision.

- **It said the warning "is emitted for a request if and only if" the three conditions hold.** A second request that
  meets all three on the same client logs nothing, and nothing is logged once the 32-signature register is full, so
  the "iff" was false of the log. §16.8 now uses §3.1's two terms — *recorded and emitted* iff the three hold — and
  states the once-per-signature rule separately.
- **It gave condition 2's fallback as `AnthropicConfig`'s 4096.** The fallback is `AnthropicConfig.getMaxTokens()`,
  which a library caller can set; 4096 is only its default. Without that, "exactly which requests" would not have held
  for programmatic assembly.
- **Its table named "a `BUDGETED` or undescribed row" for `extended` 8000/8001**, while condition 1 names `EITHER`
  too. The row names all three, and `ClampWarningCoverage` asserts all three.
- **It left the cut inside a `tool_use` block "not verified", and said the operator's first signal is a truncated
  answer.** Reading answers it: on the streaming path such a cut is a tool call with empty arguments, and nothing names
  `max_tokens`. Reason 3, "The cost" and the last re-open trigger now say so, and the gap is backlog L-16 rather than
  an open question here — it is how every truncated tool call is reported, with thinking or without, so it outlives
  this decision.
- **It cited the Anthropic client's `max_tokens` WARN as firing "on every non-streaming response". From an agent it
  never fires.** Found by the build, and missed by the design and its review alike, because neither traced the
  cancellation token: `convertResponse` is reached only by a blocking call without a live token, the ReAct executor
  always passes one, and the Anthropic client routes such a call through the streaming path. So the non-streaming half
  of reason 3 did not exist for an agent either; L-16's table records where each signal stops. *(2026-09-11: narrower
  than that — the two ReAct loops' own calls never reach it, and callers of the blocking overloads do; §16.10.)*
- **It said `ClampWarningCoverage` asserts "the three conditions as a property over every rung".** Its three planned
  tests asserted condition 3 that way, plus the table's rows; conditions 1 and 2 were asserted nowhere as coverage.
  The build added a fourth test, `theOtherTwoConditions`, which asserts both directions path by path, and "Where it is
  pinned" says which test pins what. *(2026-09-11: not on every path until §16.10's rows were added.)*

The two placements. The second dated pointer was to follow the §16.4 sentence that ends at
`AnthropicThinkingResolver.java:404-409`; there it would have come before *"Nothing in this repository can count how
many deployments that shape holds"* and changed what "that shape" refers to, so it ends the bullet instead, and its
*"above that budget"* reads *"above the requested budget"* to keep a referent there. And `## 관련 문서` names L-16
beside L-15; the design left that line alone because it registered nothing.

### 16.10 What §16.8 overstated, and the cut it left unnamed (#101, #108, #100, 2026-09-11)

*Appended 2026-09-11. Not a change to #89's decision: option 3 stands, and the clamp warning is untouched. #96's build
review left non-blocking findings against §16.8 unapplied; issue #101 collected them, and #108 and #100 changed the code
under reason 3. §16.8 now says what the code does. This section keeps what it said, in §16.7's and §16.9's form: what it
said, what is true, and what now reads differently.*

- **"does not reach an agent at all".** Reason 3 said the Anthropic client's `max_tokens` WARN cannot reach an agent,
  because its one caller is a blocking call and the agent loop always passes a live cancellation token. The argument
  showed something narrower: the two ReAct loops' own calls arrive as a stream. Callers of the blocking overloads do
  reach the WARN — compaction, while a turn runs, through `LlmClient`'s five-argument default, and also skill LLM
  execution, peer memory and the wiki. Reason 3 now names them and says why the WARN stays. §16.9's fifth bullet made the
  same claim and carries a dated pointer here; so does L-16's table, as a `정정` blockquote.
- **"which every other divergence the client reports shares".** `AnthropicLlmClient` keeps two registers.
  `reportedDivergences` holds the divergences reported once; `recurringDivergences` holds conditions of the traffic,
  reported at the 1st, 10th, 100th … occurrence. "Emitted is not the same as logged" now says so, and names fields and
  methods rather than line numbers.
- **"conditions 1 and 2 path by path in both directions".** When §16.8 was merged, `theOtherTwoConditions` did not cover
  `AUTO` on `EITHER`, `EXTENDED` on `ADAPTIVE`, or `ADAPTIVE` on `ADAPTIVE`, `EITHER` or undescribed rows, and it
  asserted condition 2 for `AUTO` on `BUDGETED` only. The rows were added rather than the sentence narrowed: five
  condition-1 rows, and condition 2 at `maxTokens` 1024 and 512 on every path that sends a budget (`EXTENDED` on
  `BUDGETED`, `EITHER` and undescribed rows, and `ADAPTIVE` translated onto `BUDGETED`), each asserting the one surviving
  finding exactly. Every row passed as the resolver's code predicted, so the sentence in "Where it is pinned" is kept,
  and it is now true. §16.9's last bullet gets a dated pointer for the same reason.
- **Reason 3's first shape held for the main executor only** (#100). `DefaultSubagentExecutor` never read the stop
  reason, so a fork's final answer cut mid-sentence reached its parent as `COMPLETED`, with no marker. It now ends as
  `TRUNCATED`, with the same marker and a WARN.
- **Reason 3's second shape was open** (#108; L-16). A cut inside a `tool_use` block reached the operator as a tool that
  ran with whatever arguments had arrived, and nothing said `max_tokens`. L-16 left open whether a truncated tool call
  should still run; it does not. The complete-looking calls of the same response are refused too, because the response
  does not say which call was cut, and a wrong guess would run a call with no arguments — the defect being fixed.
- **"with no warning at all".** `AnthropicThinkingDialectTest`'s `warnings()` reads only the appender on
  `AnthropicLlmClient`'s logger. "Where it is pinned" now says *with no warning from `AnthropicLlmClient`*.
- **Three statements in `aimon-llm-anthropic` code:**
  - the comment in `AnthropicThinkingDialectTest.autoOnABuiltInBudgetedRowThatFitsIsSilent` said `isEmpty()` catches
    "a new line on these requests, whatever it says"; it now says a new WARN from `AnthropicLlmClient`, and that
    `warnings()` reads nothing else;
  - `AnthropicThinkingBudgetsTest.aBudgetThatFitsByOneTokenIsNotClamped` also asserted the clamp at 8000 / 8000 →
    7999. It is renamed `aBudgetUnderMaxTokensIsSentAsAskedAndOneAtMaxTokensIsClamped` and not split, because its
    comment reads the four rows as one table;
  - `AnthropicThinkingBudgets.budgetFor`'s javadoc said `HIGH` clamps to 4095 "out of the box". It now says that
    happens when the call's `LlmModel` sets no `maxTokens` and `AnthropicConfig.Builder.maxTokens(int)` was not used.

**What changed in code** — the `CHANGELOG.md` `[Unreleased]` entry "Agent loop: a response cut at `max_tokens` says so
on both executors, and its tool calls are not run". Both executors read one definition,
`at.aimon.core.agent.budget.TruncatedResponses`. A response cut inside its tool calls runs none of them: each gets an
error result that names `max_tokens` and asks for less output per response. A subagent fork's cut final answer is
`TRUNCATED`. When the cut response reports reasoning tokens, the four truncation WARNs carry the output and reasoning
counts, and no share. The design, the alternatives it refused and where the build departed from it are in
[`../agent-execution/max-tokens-truncation-reporting.md`](../agent-execution/max-tokens-truncation-reporting.md).

**Backlog.** L-16 is closed. ~~L-22 (two more tool loops that never read the stop reason, one of them reachable) and
L-23 (a fork has no stalled-iteration guard, so a persistent cut repeats up to its iteration limit) are registered
beside it.~~ *(2026-09-11, #133: no longer true — §16.11.)*

**Measured once.** A single streaming request to the Anthropic Messages API on 2026-09-11, forcing a tool call under
`max_tokens: 60`, returned `input_json_delta` fragments and then `message_delta` with `stop_reason: max_tokens`, and
**no `content_block_stop` for the cut `tool_use` block**. The joined input was `{"path": "/tmp/sea.txt"`, which does not
parse. The details are in the design document's §11.4. One request is an observation, not a guarantee, and nothing
above depends on it.

### 16.11 What §16.8 and §16.10 still said after #115 and #117 (#133, 2026-09-11)

*Appended 2026-09-11 by #133. Not a change to #89's decision: option 3 stands, and the clamp warning is untouched.
#115 and #117 changed the code under reason 3 once more and closed L-22 and L-23. That work left this record as it
was, because the record was not among that run's files
([`../agent-execution/skill-loop-truncation-and-fork-stall.md`](../agent-execution/skill-loop-truncation-and-fork-stall.md)
§11.3, F-3). This section keeps the form of §16.7, §16.9 and §16.10 — what was said, what is true — but not §16.10's
placement.*

**Why §16.8 is not edited in place this time.** §16.10 rewrote §16.8 and kept the old wording in its own section
(`babfc1a`). [`../README.md`](../README.md) §3.4 did not exist then; it arrived with `c646fd2` (#122). This record's
`Status` carries §3.4's marker — §15 is the boundary, and the body above it is the text as approved — so §3.4 governs
it. §3.4 treats a section appended after the boundary as it treats the body: the section is not rewritten, and a later
correction is a new section. So each stale sentence below keeps its words where it stands, struck through, followed by
a dated pointer here. §16.10's in-place edits stay: they were made before the rule, and undoing them would be a rewrite
too.

- **§16.8 reason 3 — *"a fork has no guard and repeats until its `maxIterations` (L-23)"*.** Since #115 a fork stops on
  `at.aimon.core.agent.budget.StalledIterationGuard`, the one definition the turn, a fork and a skill's loop use, each
  execution holding its own instance. Three consecutive iterations whose tool calls all fail, refused cut responses
  included, end the fork as `ERROR`. When every one of them was a refused cut response, the stop message ends
  ` — each of those responses was cut off at max_tokens, and its tool calls were refused`. L-23 is closed.
- **§16.8 reason 3 — *"for them it is the only signal: … skill LLM execution (`LlmSkillExecutor`) …"*.** Since #115 a
  skill's loop reads the stop reason itself. It refuses every call of a cut response, with a WARN naming the skill, the
  iteration and the tool names, and returns a cut final answer marked `[System: response truncated at max_tokens]`,
  with a WARN naming the skill. The client's WARN still fires on that blocking path, but it is no longer the only
  signal there. The rest of the list is unchanged. `ReActLlmDeriver`, named in the next sentence, now refuses a cut tool
  call with a WARN of its own; a cut response with no tool calls still ends its loop without one, and nothing constructs
  it.
- **§16.8 reason 3 names "both agent executors"** as the loops that read `TruncatedResponses`. A skill's loop and
  `ReActLlmDeriver` read it too. That is incomplete, not false, so the sentence carries no mark.
- **§16.8 "What would re-open this" — *"a squeeze that L-22 does not already record"*.** L-22 is closed. The squeezes
  the outcome signals are known to miss are now recorded as L-25 and L-26. Under L-25, a background fork's cut answer
  reaches its parent through `AgentOutput` and the completion notification as though it were complete. Under L-26, a
  turn that ran a slash skill whose final answer was cut ends `COMPLETED`. The trigger is evidence of a squeeze neither
  records.
- **§16.10 "Backlog." — *"L-22 (…) and L-23 (…) are registered beside it."*** They were, then. Both were closed on
  2026-09-11 by #115 and #117. The decisions are D1–D5 of that work's record, and the closures are in
  [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md). That work
  registered L-25 and L-26.

**Unchanged.** "Where it is pinned" holds: `OrcaAgentExecutorTruncationTest` and
`DefaultSubagentExecutorTruncationTest` pin reason 3's two shapes. L-23's closure names the first sentence above under
*남은 것*; that note belongs to the closure's date and was left as written.

---

## 관련 문서

- [`reasoning-model-enablement.md`](reasoning-model-enablement.md) — §3.3's table, §3.5's census, §7's failure modes, §9 U-1's discharge
- [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) — §2.1's vendor per-model table and the two quoted 400s; §12 `Q-5`, §16's first reason
- [`anthropic-thinking-config-surface.md`](anthropic-thinking-config-surface.md) — §13 `O-2`, which §16 does not answer, and the two dated §14 notes that point here
- [`reasoning-effort-config-surface.md`](reasoning-effort-config-surface.md) — the set-valued precedent §10 A2 weighs and refuses
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — L-6 and L-7 closed here; L-9, L-10 and L-11 opened by §15.4; L-15 opened by §16; L-16 opened by §16.8 and closed by §16.10
- [`../agent-execution/max-tokens-truncation-reporting.md`](../agent-execution/max-tokens-truncation-reporting.md) — the design behind §16.10: why a cut tool call is refused rather than run, the fork's `TRUNCATED`, and L-22 and L-23
- [`../../backlog/README.md`](../../backlog/README.md) — the rules for closing an item, and the index this change corrects twice
- [`../../project/api-stability.md`](../../project/api-stability.md) — §5, which permits the enum addition at `0.x`
