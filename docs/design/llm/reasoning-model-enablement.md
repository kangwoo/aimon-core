# Design — using a reasoning model end to end (F-1 · #54 · F-4/F-5/F-7)

> Status: **PROPOSED.** Target: `aimon-core` (`at.aimon.core.llm.capability`,
> `at.aimon.core.llm.streaming`, `at.aimon.core.agent.stream`,
> `at.aimon.core.agent.definition.parser`), `aimon-llm-anthropic`, `aimon-llm-openai`,
> `aimon-session-routing`, `aimon-cli`, `aimon-spring-boot-starter`.
>
> **No API call was made for this document.** Every claim below is read out of the tree on
> **2026-09-09** and cited with a line number, or quoted from a prior design document that says
> whether *it* measured the thing. §9 is the list of what that leaves unverified. Line numbers drift;
> the date is on every citation block for that reason.
>
> **This document opens by correcting itself.** The investigation that produced it first concluded
> that the Anthropic sampling regime still blocks the current model generation. It does not —
> [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) (#52) closed exactly
> that, and the gate is at `AnthropicLlmClient.java:582`. The correction is recorded here rather
> than quietly dropped because it changes what this round is for: the reasoning path is in far
> better shape than a first read suggests, and the work left is **three narrow things**, not a
> rebuild. §2 is the audit that establishes it.
>
> Prior designs this one continues and does not restate:
> [`openai-responses-path.md`](openai-responses-path.md) (#43) §2.1 the trace slot · §7 F-5;
> [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) (#47) §2.1 the dialect matrix ·
> §8 F-1/F-3/F-4/F-6/F-7 · §9 U-3;
> [`openai-model-capabilities.md`](openai-model-capabilities.md) (#44) §2.3 fail-open · §2.5 reporting;
> [`model-capability-config-key.md`](model-capability-config-key.md) (#46) §2.7 the namespace rule;
> [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) (#52) §14 handoff.
> Paths are repository-relative.
>
> This document is in English, matching its five siblings in `docs/design/llm/`. `docs/design/` is
> not a translation target either way ([`documentation-guide.md`](../../project/documentation-guide.md) §5.1).

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **D-1** | Does `OrcaAgentExecutor` need changing to use a reasoning model? | **No, and §2 is the proof.** The trace round trip — the hard half — is already wired at every site that builds an assistant message from an `LlmResponse`, persisted by the transcript codec, and deliberately shed at compaction. The executor is touched by exactly one item in this document, Phase 3, and then only as the last consumer of a new stream chunk. |
| **D-2** | How is the Anthropic thinking **dialect** modelled, given that `ModelCapabilities` has no field whose fail-open value is safe for a two-valued mutually exclusive axis? | **Make the axis three-valued.** `ThinkingDialect { UNKNOWN, BUDGETED, ADAPTIVE }` on the shared `ModelCapabilities`, fail-open at `UNKNOWN`, which means *the client does not choose* — i.e. today's behaviour exactly. The axis is only unsafe while it is forced to be two-valued. §3 |
| **D-3** | A vendor-shaped fact on a provider-neutral type? | **Yes, and there is precedent in that very type.** `supportsToolsWithReasoning()` is documented as "endpoint-flavoured" and is read on one OpenAI path only. The deciding cost is elsewhere: the table is already read by both clients, so a second Anthropic-only registry would make a gateway deployment declare its model twice, in two shapes, in two config namespaces. §3.2 |
| **D-4** | What happens when the operator's `thinkingMode` contradicts the model's dialect? | **Translate the intent, report at WARN, never send the certain 400.** The neutral intent is `ReasoningEffort`, and both directions of the mapping already exist in `AnthropicThinkingBudgets` (`requestedBudget` and `effortFor`). This does not violate the "omit, never clamp upward" rule of `lowestReasoningEffort`: there, omission leaves the server default in force and the call succeeds; here, honouring the operator is a guaranteed failed turn. §3.3 |
| **D-5** | Does the `thinkingMode` default change to something that uses the new fact? | **No.** Default stays `OFF`; a new `AUTO` value opts in. Flipping the default would turn thinking on — and bill for it — in every existing Anthropic deployment on an upgrade. That is a behaviour change that deserves its own round and its own CHANGELOG line. §3.4, §11 |
| **D-6** | Where do the configuration keys go? | **By [`model-capability-config-key.md`](model-capability-config-key.md) §2.7's rule, mechanically.** `reasoningEffort` is a neutral name with one meaning → shared namespace. `thinkingMode` / `thinkingBudgetTokens` / `replayThinkingBlocks` name a vendor concept → `llm.anthropic.*` / `aimon.llm.anthropic.*`, opening the namespace B-21 left empty. **This answers a question #54 raises and leaves open, and it answers it against the key names that issue's own body proposes.** §4.1 |
| **D-7** | Do the two config phases ship together? | **No — 2a and 2b split, and the split is forced by the backlog.** Backlog `L-1` states its own trigger as *"when a reasoning-effort configuration surface appears"*, because a configurable `MINIMAL` becomes a 400 on `gpt-5.6-terra`. So the vendor keys (2a) ship first and the neutral effort key (2b) **carries L-1's prerequisite work**. §4.3 |
| **D-8** | Is forwarding thinking text to the sink (F-4) worth doing on its own? | **No, and shipping it alone would be a defect.** Nothing is asked for on either request surface today: `OpenAIResponsesRequestFactory` never sets `reasoning.summary`, and on the current Anthropic generation thinking text is omitted by default (`AnthropicStreamingMapper.java:258-259`). F-4 alone yields a channel that is always empty. The ask (F-5/F-7) and the transport ship together. §5.1 |
| **D-9** | Does the new reasoning stream reuse `AssistantTextDelta`? | **No.** It gets its own chunk kind and its own event. Folding it into the text path would put reasoning text into `ChunkAggregator.peekText()`, which the executor commits to the transcript as assistant text on a mid-stream cancel (`OrcaAgentExecutor.java:3024`) — reasoning would silently become the answer. §5.2 |
| **D-10** | A `llm.anthropic.*` block declared in a deployment whose Anthropic branch never runs — what then? | **Refuse, and only from inside the branch that does run.** This is the shape #46 justified the shared namespace with and #52 deleted the last instance of; a vendor-named subtree brings it back with a clearer conscience, because there is no ambiguity about who the block is for. Refusing from outside a running branch is what backlog `L-3` warns turns valid configuration into a boot failure, so the check sits where `requireApiKey` sits. §4.5 |

---

## 1. The problem, in one paragraph

Asked whether the executor needs work to use a reasoning model, the honest answer turns out to be
that it does not, and that the question has been asked at the wrong altitude twice: once by whoever
asks it, and once by this investigation, which began by reporting a blocker that a previous round
had already removed. What is actually left is narrow and it is all outside the ReAct loop. **A
deployment can neither say how hard to think nor see that it is thinking.** The amount of reasoning
is settable only from Java — `MarkdownAgentDefinitionParser.extractModel` parses `name`,
`temperature`, `maxTokens` and `topP` and stops (`:152-163`), and neither config surface has a
reasoning-effort key. The Anthropic thinking dialect must be named by an operator who has to know a
per-model fact the framework could look up, and naming it wrong is an HTTP 400. And the reasoning
itself is invisible: both providers deliberately withhold reasoning text from the stream sink, so a
model that thinks for thirty seconds produces thirty seconds of nothing. Three phases, in that
order, and the executor is touched once.

---

## 2. What is already there — the audit that makes this round small

Written first because every decision below depends on it, and because two of the three phases would
have been designed differently against the wrong picture.

### 2.1 The trace round trip is complete, and the executor is a full participant

*(read 2026-09-09)*

| Fact | Where |
|---|---|
| Every site that builds an assistant message out of an `LlmResponse` attaches that response's traces | `OrcaAgentExecutor.java:1747,1755,1780`; `DefaultSubagentExecutor.java:442,448`; `ReActLlmDeriver.java:212`; `LlmSkillExecutor.java:223` |
| …and the rule is written down rather than left to be noticed | `LlmSkillExecutor.java:219-222` — *"the rule is that every site building an assistant message out of an `LlmResponse` attaches that response's traces, with no per-site judgement about whether the message will be read back. A rule with an exception is a rule nobody can check with one grep."* |
| Traces survive persistence | `JsonSessionSnapshotCodec.java:386-395` writes and reads a `reasoning` array, absent-tolerant for documents written before it existed |
| Reasoning tokens are accounted, as a **subset** of completion tokens rather than an addition | `TokenUsage.java:54-70`; persisted at `SessionRecordCodec.java:171,197` |
| Compaction sheds traces on purpose, in the safe direction | `MessageStripper.java:116-123` — calls are kept, so no reasoning item is ever left with its following call gone; and the reverse shape was measured acceptable ([`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §9 U-10) |
| An assistant turn that is nothing but thinking does not emit an empty text block | `AnthropicMessageConverter.java:257-260` |

The one executor path that does **not** attach traces is the mid-stream cancel at
`OrcaAgentExecutor.java:3024,3039`, and it is not a gap: the response has not been aggregated at
that point, so there are no traces to attach, and the preserved prefix carries no tool uses.

### 2.2 The Anthropic sampling blocker is closed

`AnthropicLlmClient.buildRequest` resolves the model's descriptor (`:378`, `capabilitiesFor` at
`:665`) and suppresses both sampling parameters when `supportsSamplingParameters()` is false
(`:582-588`). The built-in table carries six measured `claude-*` prefix rows plus a
documentation-derived `claude-mythos` row (`InMemoryModelCapabilityRegistry.java:226-234`), and
`AnthropicConfig.temperature` is now unset-by-default (`AnthropicConfig.java:239`) rather than a
manufactured `0.0`. Both config surfaces reach the Anthropic branch
(`LlmClientFactory.java:73-76`, `AimonLlmAutoConfiguration.java:135-137`).

**One artefact of that round is stale and this round fixes it in passing.**
`modules/aimon-cli/src/main/resources/default-config.yaml:13-14` still tells the reader
*"Only the openai provider reads this block"*. Since #52 both providers read it. A comment that
describes a guard which no longer exists is worse than no comment: it tells an Anthropic operator
not to bother writing the declaration that would fix their 400.

### 2.3 So what is missing is not machinery — it is a voice and a window

Everything in §2.1 carries reasoning *across* turns. Nothing lets a deployment ask for more or less
of it, and nothing shows it. That is the whole of this document.

---

## 3. Phase 1 — the thinking dialect as a per-model fact (F-1)

### 3.1 Why the axis looked unmodellable

`AnthropicThinkingMode.java:33-38` states the obstacle, and
[`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) §14 restates it after the
table gained `claude-*` rows: *"`ModelCapabilities` still has no field whose fail-open value is safe
for a two-valued mutually exclusive axis."* Both `{"type":"enabled"}` and `{"type":"adaptive"}` are
a 400 on the model that speaks the other dialect, so neither can be a default for a model nobody has
described.

That is true, and it is only true **because the axis was assumed to have two values.**

### 3.2 The decision — a third value, and the shared type

```java
package at.aimon.core.llm.capability;

/** Which shape this model's thinking-request parameter takes. */
public enum ThinkingDialect {
    /** Nothing is known. The client does not choose; whatever the caller configured stands. */
    UNKNOWN,
    /** A manual token budget accompanies the request. */
    BUDGETED,
    /** The model manages its own budget; the request carries an effort rung instead. */
    ADAPTIVE
}
```

`ModelCapabilities.thinkingDialect()` returns it, seeded at `UNKNOWN` in the builder exactly as the
other five fields are seeded at their fail-open literals (`ModelCapabilities.java:33-38`). The
fail-open value is safe because it is **not a dialect**: it means *this table cannot answer, so do
not act on it*, which is a behaviour the client already has today for every model.

Two things follow, and both are what makes this additive rather than a wire change:

- A model no row describes keeps today's behaviour byte for byte. `AnthropicThinkingMode` still
  decides, still defaults to `OFF`, and still sends nothing.
- A row that states a dialect changes nothing until an operator asks for thinking. The fact is
  consulted only inside `resolveThinking`, which returns empty for `OFF` before it looks
  (`AnthropicLlmClient.java:497-499`).

**On putting a vendor-shaped fact in a provider-neutral type.** The precedent is in the same class:
`supportsToolsWithReasoning()` is documented as *"endpoint-flavoured… the framework's built-in table
describes OpenAI's Chat Completions endpoint"* and is read by one client on one path. A flag only
one consumer reads is the house shape here, not a novelty. The argument that decides it is a cost
rather than a symmetry: `InMemoryModelCapabilityRegistry.java:53-56` records that **both clients read
this one table**, and both config surfaces feed it through one translator
(`withDefaultsExtendedBy`). A separate Anthropic registry would make a deployment that renames its
models behind a gateway declare each model twice, in two shapes, under two config keys — and it is
precisely the renaming deployment that these declarations exist for.

**Why not a fourth constant for OpenAI** (`EFFORT_ONLY`, say): `supportsReasoningEffort()` already
answers that question for that client. A second way to say it is a second thing to keep in step, and
the first divergence between them would be invisible.

### 3.3 What the client does with it

`resolveThinking` gains one step. Let *mode* be the operator's `AnthropicThinkingMode` and *dialect*
the resolved fact.

| mode | dialect | request | reported |
|---|---|---|---|
| `OFF` | any | nothing — unchanged | nothing; silence is correct, the operator turned it off |
| any | `UNKNOWN` | exactly today's behaviour | nothing |
| `EXTENDED` | `BUDGETED` | `{"type":"enabled","budget_tokens":N}` — unchanged | nothing |
| `ADAPTIVE` | `ADAPTIVE` | `{"type":"adaptive"}` + `output_config.effort` — unchanged | nothing |
| `EXTENDED` | `ADAPTIVE` | `{"type":"adaptive"}`, effort from the intent | WARN, once per signature |
| `ADAPTIVE` | `BUDGETED` | `{"type":"enabled"}`, budget from the intent | WARN, once per signature |
| `AUTO` | `BUDGETED` / `ADAPTIVE` | the model's dialect | nothing — this is what `AUTO` asked for |
| `AUTO` | `UNKNOWN` | nothing, as `OFF` | WARN — the operator asked the table and it had no answer |

**The translation is free.** Both directions already exist:
`AnthropicThinkingBudgets.requestedBudget(effort, configuredBudget)` (`:96`) turns a rung into a
budget, and `effortFor(effort)` (`:147`) turns a rung into `OutputConfig.Effort`. The neutral
`ReasoningEffort` in the middle is the intent both dialects are spellings of. The one lossy corner
is an operator who set an explicit `thinkingBudgetTokens` against an adaptive-only model: a token
count has no adaptive counterpart, so it becomes the nearest rung and the warning says so by name.

**Why translate rather than omit.** The repository's standing rule, from
`ModelCapabilities.lowestReasoningEffort()`'s javadoc, is *"omitted and reported, never raised to
meet it: a clamp upward is a request the operator did not make."* It does not transfer, and the
reason is in the same javadoc's next sentence: *"Omission at least leaves the server's own default
in force."* On the effort axis, omitting succeeds. On the dialect axis, honouring the operator is a
**certain 400** — the failure mode this fact was looked up to prevent — and omitting throws away the
thinking they asked for. Translating loudly is the only option that keeps the turn and the intent.
It is not silent substitution: that is what the WARN is for, and
`AnthropicLlmClient`'s once-per-signature register already exists to keep it from repeating on every
ReAct iteration.

### 3.4 `AUTO`, and the default that does not move

`AnthropicThinkingMode` gains `AUTO` — *use the model's dialect when the table knows it, otherwise
send nothing*. It is the value F-1 exists to make possible: the operator states an intent
("think"), not a per-model wire fact.

**`OFF` stays the default** (`AnthropicConfig.java:42`). Making `AUTO` the default would turn
thinking on, and bill for it, in every Anthropic deployment that upgrades without reading the
changelog. That is a behaviour change and it belongs to its own round with its own entry, not to a
round whose other two phases are additive. §11 carries it.

---

## 4. Phase 2 — a configuration surface for how hard to think (#54 and its neutral sibling)

### 4.1 Where each key goes, by the existing rule

[`model-capability-config-key.md`](model-capability-config-key.md) §2.7 settles this without a new
judgement: a key goes to the vendor namespace when **its name carries a vendor concept, or its
meaning differs per vendor**; otherwise it stays shared. Applied:

| Key | Namespace | Which condition |
|---|---|---|
| `reasoningEffort` | CLI `llm.reasoningEffort`, starter `aimon.llm.reasoning-effort`, agent frontmatter `model.reasoningEffort` | Neither. The name is the neutral SPI's own (`at.aimon.core.llm.ReasoningEffort`) and it means the same thing to both clients |
| `thinkingMode` | CLI `llm.anthropic.thinkingMode`, starter `aimon.llm.anthropic.thinking-mode` | **Name** — `thinking` is Anthropic's word for it |
| `thinkingBudgetTokens` | same | **Name**, and **meaning**: no other vendor expresses effort as a token count |
| `replayThinkingBlocks` | same | **Name** |

This opens the `llm.anthropic` / `aimon.llm.anthropic` subtree that §2.7's own table left standing
empty for `responsesApiEnabled` and the sampling parameters. Those two remain out of scope; the
namespace this creates is the one they will land in.

**This contradicts the key names #54 proposes, and that is the point of taking the question.** That
issue's *Proposed solution* lists `llm.thinkingMode` and `aimon.llm.thinking-mode` — the shared
namespace — and then, two paragraphs later, says the opposite is *"worth settling at the same
time"*: `thinkingMode` is *"the first key whose name carries a vendor concept, which is exactly the
criterion #46's design doc reopened B-21 with. This issue is the trigger that criterion was written
for."* So the issue poses the question and its own draft answers it the other way. Implementing #54
as literally written would put the first vendor-named key into the neutral namespace and make the
criterion unfalsifiable the first time it applied. The table above is that criterion applied.


### 4.2 One asymmetry to close while here

`OpenAiRequestParameters.requestedEffort(modelConfig, config)` (`:106-115`) resolves the effort
*"the `LlmModel` first, then the client config"*, because `OpenAIConfig` has a `reasoningEffort`
field (`:52`). **`AnthropicConfig` has no such field** (`:48-54`), so `resolveThinking` reads
`modelConfig.getReasoningEffort()` alone (`:500`). A deployment-wide effort therefore reaches one
provider and is silently ignored by the other.

Phase 2a adds `AnthropicConfig.reasoningEffort` with the same precedence, so that the one neutral key
means the same thing on both providers. Without it, `aimon.llm.reasoning-effort` would be a shared
key that half the deployments do not get — which is the exact dishonesty §2.7's rule exists to
prevent.

### 4.3 The split, and why the backlog forces it

**Phase 2a — the Anthropic keys.** `thinkingMode` (now including `AUTO`), `thinkingBudgetTokens`,
`replayThinkingBlocks`, plus `AnthropicConfig.reasoningEffort` from §4.2. This is #54, which
[`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) §14 records as *"blocked
on this one"* — and that block is now lifted. Both surfaces already carry a worked example of
threading a knob into `AnthropicConfig.Builder`.

**Phase 2b — the neutral `reasoningEffort` key, which wakes L-1.**
[`openai-model-capabilities-open-items.md`](../../backlog/openai-model-capabilities-open-items.md)
**L-1** — not the `L-1` in
[`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), which is a
different item that §4.5 handles, and the collision is why both are cited with their file here —
names its own trigger, in those words: *"when a reasoning-effort configuration surface appears
(a starter property or a CLI yaml key)"*, verifiable as the moment
`grep -rn "reasoningEffort\|reasoning-effort" modules/aimon-spring-boot-starter/src/main modules/aimon-cli/src/main`
stops returning zero. The reason is concrete: `gpt-5.6-terra` resolves to the `gpt-5` prefix row,
whose floor is `MINIMAL`, and it **rejects** `minimal` while **accepting** `none`. Today only Java
can request that value; a yaml key hands it to every operator.

So 2b carries L-1's two prerequisites rather than stepping over them:

1. `lowestReasoningEffort` becomes expressible as a **rung set**, because a floor cannot describe a
   ladder with a hole in it — terra's real floor is `NONE` and the rung above it is missing, and all
   three single values are wrong.
2. An answer to exact-shadows-prefix, since any name-level row for terra breaks the
   `registerPrefix("gpt-5", …)` override that `builderWithDefaults()`'s javadoc uses as its canonical
   position-safe example.

Shipping 2b without them means the first operator to write `reasoningEffort: minimal` against terra
gets a 400 that the framework knew about and documented. Shipping 2a first is free of that: none of
its keys touch the effort ladder.

### 4.4 The frontmatter key

`MarkdownAgentDefinitionParser.extractModel` gains `reasoningEffort`, parsed case-insensitively onto
`ReasoningEffort` — the same tolerance `CliConfigLoader.java:38` already applies to
`lowestReasoningEffort`, for the same reason. It belongs in 2b with the other effort key: an agent
definition is a configuration surface, and L-1's trigger says nothing about which one.

`requestTimeout`, `presencePenalty` and `frequencyPenalty` are also missing from that method. They
are **not** in scope — no issue asks for them and adding them here would make this round's frontmatter
change hard to review against its own reason. §11.

### 4.5 Two ways a new key can be set and do nothing

Both are already registered backlog items, and both get worse the moment this round adds keys.
Neither is closed here; what this section decides is that the new keys do not *widen* them.

**A vendor block under the wrong provider** —
[`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) `L-3`. A
deployment with `aimon.llm.provider=openai` that sets `aimon.llm.anthropic.thinking-mode` has
configured something no branch will read. #46 justified putting `model-capabilities` in the shared
namespace partly on *"the non-reading branch refuses it by name"*, and #52 **deleted** both of those
guards (`refuseModelCapabilities`, `refuseModelCapabilitiesForAnthropic`) because after that round
there was no non-reading branch left. A vendor-named subtree brings the situation back, and this
time with no ambiguity about whose block it is.

D-10: **the running branch refuses a populated foreign vendor block.** The check goes where
`requireApiKey` goes — *inside* `OpenAiConfiguration` for an `anthropic` block and vice versa — and
nowhere else, because `L-3`'s two remaining cases (`provider=none`, and an application that defines
its own `LlmClient` bean) are exactly the deployments where a check outside a running branch turns a
valid configuration into a boot failure. Those two stay open, unchanged and uncaused by this round.

**A misspelled property name in the starter** —
[`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) `L-1`. #54 asks
for this bar by name. Boot's `ignoreUnknownFields` default means
`aimon.llm.anthropic.thinking-mod: adaptive` binds nothing and says nothing, and the operator
believes they chose a dialect. The CLI does not share the defect — `CliConfigLoader.java:38` keeps
`FAIL_ON_UNKNOWN_PROPERTIES` on — so the asymmetry that item describes widens by three keys here.
This round does **not** close it: the fix is a starter-wide binding decision, it has a test recorded
as a limitation (`AimonPropertiesValidationTest.aMisspelledFlagIsSilentInTheStarter`) that is
supposed to go red when someone closes it, and riding that in on a feature is the pattern §4.3
splits phases to avoid. What this round owes it is one line in `L-1` recording that the surface it
covers grew.

---

## 5. Phase 3 — thinking as something a person can watch (F-4 with F-5 and F-7)

### 5.1 F-4 alone would ship an empty channel

[`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §8 F-4 is *"forwarding thinking text
to `LlmStreamSink`"*, and [`openai-responses-path.md`](openai-responses-path.md) §7 F-5 is the same
item on the other provider. Taken literally, either one is inert:

- **OpenAI.** The reasoning payload that crosses turns is `encrypted_content` — ciphertext, by
  design (`ReasoningTrace.java:16-22`). The only human-readable text is the reasoning *summary*, and
  `OpenAIResponsesRequestFactory` never asks for one: `grep -n "summary" ` over it returns nothing
  (2026-09-09). Nothing to forward.
- **Anthropic.** `AnthropicStreamingMapper.java:258-259` records the fact directly — *"on the newer
  models thinking text is omitted by default, so a block can legitimately stream zero
  `thinking_delta`s"*. That is why an unsigned block is dropped on the signature and not on the text.
  Forwarding deltas that are not sent forwards nothing.

Hence D-8: the request-side ask ships with the transport. `reasoning.summary` on the Responses path,
`display` on the adaptive Anthropic path, both opt-in and both off by default — they cost tokens for
text nothing renders until this phase renders it, which is exactly why F-7 was deferred the first
time.

### 5.2 The transport, and the one place it must not go

A new `LlmStreamChunk.Kind.REASONING_DELTA` with its own factory, mirroring `textDelta`.

**It must not reach `ChunkAggregator.textBuffer`.** That buffer is `peekText()`, and `peekText()` is
what the executor commits to the transcript as an assistant message when a turn is cancelled
mid-stream (`OrcaAgentExecutor.java:3024,3039`). Folding reasoning into it would make an interrupted
turn persist the model's private deliberation as its public answer — and persist it, since that
message goes through the transcript codec like any other. The aggregator gets a second buffer that
`toLlmResponse()` does not read.

**Both `switch`es on `Kind` end in `default -> throw new IllegalStateException("Unknown chunk kind")`**
— `ChunkAggregator.java:87` and `OrcaAgentExecutor.java:3156`. An unhandled new constant is therefore
a hard runtime failure on the first provider that emits one, not a silent skip. The constant and its
two handlers land in the same commit; this is stated as an acceptance criterion in §12 rather than
left to reviewer memory.

### 5.3 The event, and the five places a sealed hierarchy reaches

`AssistantReasoningDelta extends AgentExecutionEvent`, a sibling of `AssistantTextDelta` carrying
`delta` and `chunkIndex` with the same ordering contract.

`AgentExecutionEvent` is `sealed … permits` fifteen subtypes
(`AgentExecutionEvent.java:56-70`), so adding one is source-breaking for any out-of-tree exhaustive
consumer, and in-tree it reaches five places that the compiler will **not** all catch:

| Place | What happens if it is missed |
|---|---|
| `AgentExecutionEvent` `permits` clause | compile error — the only one the compiler catches |
| `AgentEventDispatcher` (`:196` is the sibling emit method) | nothing is ever emitted |
| `OrcaAgentExecutor.StreamingEventSink.accept` (`:3130-3156`) | `IllegalStateException` on the first chunk |
| `AgentExecutionEventPayload.flatten` (`:139-176`) and its decoder (`:301`) | the event is dropped crossing a node boundary, silently |
| `OutputFormatter.displayEvent` (`:302-330`) | `IllegalStateException` at `:329` — the chain's final `else` throws, deliberately, *"so exhaustiveness is maintained at runtime rather than compile time"* |

**`SessionEventRelay.isDroppable` (`:158-160`) gains it, ahead of text deltas.** Today text deltas
are the only droppable frame, justified as *"high-volume, and the assistant message they build is
summarised again by `AssistantMessageReceived`"*. Reasoning deltas are higher-volume still and
nothing downstream needs them at all — no transcript, no summary, no result field. Under buffer
pressure the choice is between dropping thinking and dropping answer text, and it is not close.

---

## 6. Concrete changes, by module

*(2026-09-09; line numbers are anchors for review, not addresses to patch blindly)*

### Phase 1

| Module | File | Change |
|---|---|---|
| `aimon-core` | `llm/capability/ThinkingDialect.java` | new enum |
| `aimon-core` | `llm/capability/ModelCapabilities.java` | field + getter + builder setter + `DEFAULT_THINKING_DIALECT = UNKNOWN` beside the five existing literals (`:33-38`) |
| `aimon-core` | `llm/capability/ModelCapabilityDeclaration.java` | the sixth flag, fail-open by omission |
| `aimon-core` | `llm/capability/InMemoryModelCapabilityRegistry.java` | dialect on the six measured `claude-*` rows and the Mythos row (`:226-234`), from [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §2.1's matrix |
| `aimon-llm-anthropic` | `AnthropicThinkingMode.java` | `AUTO`; the javadoc's *"deliberately a separate round"* paragraph (`:33-38`) becomes a pointer here |
| `aimon-llm-anthropic` | `AnthropicLlmClient.java` | `resolveThinking` (`:495`) takes the descriptor `buildRequest` already resolved at `:378`; §3.3's table; two new divergence signatures |

### Phase 2a

| Module | File | Change |
|---|---|---|
| `aimon-llm-anthropic` | `AnthropicConfig.java` | `reasoningEffort` field, `LlmModel`-first precedence (§4.2) |
| `aimon-cli` | `config/LlmProviderConfig.java` (`:8-13`), `factory/LlmClientFactory.java` (`:58-79`) | an `anthropic` sub-block |
| `aimon-cli` | `resources/default-config.yaml` | the new keys, **and the stale line 13-14 from §2.2** |
| `aimon-spring-boot-starter` | `AimonProperties.Llm` (`:1164-1196`), `AimonLlmAutoConfiguration.AnthropicConfiguration` (`:125-140`) | same keys; `additional-spring-configuration-metadata.json` for the enum hints |

### Phase 2b

| Module | File | Change |
|---|---|---|
| `aimon-core` | `llm/capability/ModelCapabilities.java` | `lowestReasoningEffort` → rung set (L-1 prerequisite 1) |
| `aimon-core` | `llm/capability/InMemoryModelCapabilityRegistry.java` | the exact-shadows-prefix answer (L-1 prerequisite 2) |
| `aimon-llm-openai` | `OpenAiRequestParameters.java` | `maySendEffort` reads a set rather than a floor |
| `aimon-core` | `agent/definition/parser/MarkdownAgentDefinitionParser.java` (`:152-163`) | `model.reasoningEffort` |
| `aimon-cli`, `aimon-spring-boot-starter` | as above | the one neutral key |

### Phase 3

| Module | File | Change |
|---|---|---|
| `aimon-core` | `llm/streaming/LlmStreamChunk.java` (`:43-54`) | `REASONING_DELTA` + factory |
| `aimon-core` | `llm/streaming/ChunkAggregator.java` (`:71-89`) | second buffer; **not** `textBuffer` |
| `aimon-core` | `agent/stream/AssistantReasoningDelta.java`, `AgentExecutionEvent.java` (`:56-70`) | new event + `permits` |
| `aimon-core` | `agent/impl/orca/AgentEventDispatcher.java` (`:196`), `OrcaAgentExecutor.java` (`:3130-3156`) | emit + handle |
| `aimon-llm-openai` | `OpenAIResponsesRequestFactory.java`, `OpenAIResponsesStreamingMapper.java` | ask for `reasoning.summary`; forward its deltas |
| `aimon-llm-anthropic` | `AnthropicLlmClient.java`, `AnthropicStreamingMapper.java` (`:195-204`) | ask for `display`; forward `thinking_delta` while still feeding the trace payload |
| `aimon-session-routing` | `AgentExecutionEventPayload.java` (`:139-176`, `:301`), `SessionEventRelay.java` (`:158-160`) | wire shape + droppable |
| `aimon-cli` | `repl/OutputFormatter.java` (`:302-330`) | render, dimmed and distinct from answer text |

---

## 7. Failure modes

| # | Shape | Disposition |
|---|---|---|
| 1 | A `claude-*` row states the wrong dialect | The 400 the operator gets today, now with the framework's name on it. Mitigated the way #52 mitigated the same class: rows come from the vendor's per-model table, and an operator can override one by name from configuration. **(2026-09-10, #69) That mitigation was true only of a Java caller until now** — no configuration surface bound a key for the dialect, so following it was a hard `ConfigurationException` on the CLI and a silent no-op in the starter. It is true of configuration as of #69: `llm.modelCapabilities.<model>.thinkingDialect` and `aimon.llm.model-capabilities.<model>.thinking-dialect`, both binding the enum, both accepting `unknown` as a real statement — *act on no built-in row for this name* — for an operator who knows the row is wrong and not what the right answer is. **Write the whole row, not the one key:** a declaration replaces the built-in entry rather than patching it, and every `claude-*` row states two flags, so an entry naming only the dialect hands `supportsSamplingParameters` back at fail-open `true` and buys the 400 #52 exists to prevent. The guides print the full form; the general remedy is `L-8` in [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) |
| 2 | A gateway renames a Claude model; the dialect resolves `UNKNOWN` | Today's behaviour exactly, which is the point of D-2. `AUTO` warns rather than guessing |
| 3 | `AUTO` + `UNKNOWN` sends nothing, and an operator reads that as thinking being on | The WARN is the only signal, and on an always-on model thinking *is* happening — it is only the request parameter that is absent. Wording must say which of the two it means |
| 4 | Translation (§3.3) surprises an operator who set an exact `thinkingBudgetTokens` | WARN naming both the token count and the rung it became. This is the lossiest corner in the document and it is one warning, not a silent change |
| 5 | Phase 2b lands without L-1's prerequisites | `reasoningEffort: minimal` on `gpt-5.6-terra` is a 400. Prevented structurally by the 2a/2b split, not by remembering |
| 6 | A new `Kind` reaches an unpatched `switch` | `IllegalStateException` at `ChunkAggregator.java:87` or `OrcaAgentExecutor.java:3156` — loud, and in the same commit by §12 |
| 7 | A new event subtype reaches an unpatched consumer | `IllegalStateException` at `OutputFormatter.java:329`; **or**, at `AgentExecutionEventPayload`, a silent drop across nodes. That asymmetry is why the payload is called out separately in §5.3 |
| 8 | Reasoning text reaches the transcript as answer text | Prevented by D-9. A test asserts `peekText()` is unchanged by a `REASONING_DELTA` |
| 9 | An out-of-tree consumer switches exhaustively on the sealed hierarchy | Source-breaking, CHANGELOG-visible. `0.x` permits it ([`api-stability.md`](../../project/api-stability.md) §5) |

---

## 8. Test strategy

- **Phase 1.** The §3.3 table is a parameterised test, one case per row, asserting the built
  `MessageCreateParams` and the divergence signature. `UNKNOWN` cases assert **byte-identical**
  requests to today's — that is the additive claim, and it is the one worth pinning.
- **Phase 2a.** Both surfaces bind each key onto `AnthropicConfig`; `AimonDocumentedPropertiesTest`
  (`modules/aimon-spring-boot-starter/src/test/java/…`) already fails on an undocumented property, so
  the metadata is covered by an existing gate rather than a new one. A CLI test asserts the yaml key
  reaches the client — the same shape `LlmClientFactory`'s package-private `anthropicConfig` exists
  for.
- **Phase 2b.** The rung set is tested against terra's measured ladder (`none`, `low`, `medium`,
  `high`, `xhigh`, `max` — no `minimal`), and the exact-shadows-prefix answer must keep
  `builderWithDefaultsOverridesAPrefixInPlace` green, since that test pins the javadoc's canonical
  override recipe.
- **Phase 3.** Three that matter: a `REASONING_DELTA` does not change `peekText()`; the event
  survives a round trip through `AgentExecutionEventPayload`; and `OutputFormatter.displayEvent`
  does not throw for it. The first is the one that would otherwise fail in production as a privacy
  incident rather than as a bug.
- **Live tests** stay where F-8 left them. Nothing here adds a billed call to `./gradlew test`.

---

## 9. What has **not** been measured

Written in the form the sibling documents use. **Not measured is not a task; it is an empty cell.**

- **U-1 — no API call was made for this document.** Every dialect claim comes from
  [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §2.1, which quotes the vendor's
  per-model table and whose §9 U-2 records that both dialect rejections *were* provoked live. So the
  matrix is measured; **which models are on it today is not.** The table was read on a date and models
  are added continuously. **Discharged for five of the six ADAPTIVE rows that ship, across six model
  names, 2026-09-10** — the registry carries six ADAPTIVE prefixes
  (`InMemoryModelCapabilityRegistry:243-253`), and probes after this document was written confirmed
  five of them live (adaptive 200, budgeted 400): `claude-fable-5`, `claude-opus-5`,
  `claude-opus-4-7`, `claude-opus-4-8`, `claude-sonnet-5`. Six *model names* were probed because
  `claude-fable-5-1` is not a row of its own — it matches the `claude-fable-5` prefix. The sixth row,
  `claude-mythos`, remains unverifiable because no model with that prefix exists in the probing
  account's `GET /v1/models`. **Rows and model names are not the same count, and this document says
  so explicitly because a set enumerated slightly wrong is the exact failure the change that added
  this note exists to correct.**
  Evidence and the two facts that fall outside this document are in
  [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md)
  **L-6** and **L-7**.
- **U-2 — the `AUTO` + `UNKNOWN` warning has no field evidence** that operators read it. It is the
  one place this design substitutes a log line for a behaviour.
- **U-3 — `reasoning.summary` has never been requested from this codebase**, so neither its token
  cost nor the shape of its stream events has been observed here. Phase 3's OpenAI half rests on the
  SDK's event union alone.
- **U-4 — `display` likewise.** [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) §8 F-7
  names it; nothing in this tree has sent it.
- **U-5 — the volume of reasoning deltas is unknown**, so §5.3's claim that they are "higher-volume
  still" than text deltas is a reasonable inference from how these models behave, not a measurement.
  If it is wrong, the drop-order decision is wrong with it and costs nothing but ordering.
- **U-6 — U-3 of [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) is untouched here.**
  `Message` concatenates all text into one string, so a turn with thinking interleaved between two
  text blocks is replayed with the thinking blocks consecutive. Phase 3 makes that shape *visible*
  for the first time, which may be how it finally gets measured. It does not fix it. §11.

---

## 10. Alternatives rejected

| # | Alternative | Why not |
|---|---|---|
| **A1** | **Change `OrcaAgentExecutor`** — the question that started this document | §2 is the answer. The loop already carries reasoning correctly at every site; the one executor change in this document is Phase 3's chunk handler, and it is a consumer of a new core type rather than a change to the loop |
| **A2** | Give Anthropic its own capability registry | D-3. One table, two clients, one config translator; a second table doubles what a gateway deployment has to declare and gives the two a way to disagree |
| **A3** | Model the dialect as `boolean adaptive` with a documented-unsafe default | This is the shape the prior rounds correctly refused. Both values are a 400 on the wrong model, so *every* default is wrong for half the models. `UNKNOWN` is not a third dialect — it is the absence of the fact, which is the state the table is actually in for most names |
| **A4** | Refuse the request when `thinkingMode` contradicts the dialect | Fails the turn to avoid failing the turn. The only thing it buys over letting the 400 happen is a better error message, and D-4 buys a working request for the same effort |
| **A5** | Ship one config phase | D-7. L-1 names a reasoning-effort key as its trigger and states two prerequisites; a single phase either drags them in or ships a known 400 |
| **A6** | Reuse `AssistantTextDelta` with a flag | D-9, and failure mode 8. Every consumer that forgot to read the flag would render or persist reasoning as the answer, and the transcript one is not recoverable |
| **A7** | Ship F-4 now and the request-side ask later | D-8. An always-empty channel is untestable end to end, and the first bug report would be about the feature rather than about the missing ask |

---

## 11. Follow-ups this round deliberately does not start

| # | Item | Why not now |
|---|---|---|
| **G-1** | **`AnthropicThinkingMode` default `OFF` → `AUTO`.** | D-5. It turns thinking on, and bills for it, in every deployment that upgrades. Its own round, its own CHANGELOG entry, and it wants Phase 1 shipped first so the dialect is known before the default relies on it |
| **G-2** | `interleaved-thinking-2025-05-14` — [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) F-3 | Unchanged: its own per-model matrix and its own `budget_tokens`/`max_tokens` interaction |
| **G-3** | Text-block position through `Message` — U-6 above, U-3 there | A core change to `Message` affecting every provider and the transcript codec. Phase 3 may be what finally measures how often the shape occurs |
| **G-4** | `responsesApiEnabled` and the sampling parameters into `llm.openai.*` / `llm.<vendor>.*` | Out of scope by [`model-capability-config-key.md`](model-capability-config-key.md) §2.7's own O-B, but Phase 2a creates the namespace they go to |
| **G-5** | `requestTimeout`, `presencePenalty`, `frequencyPenalty` in agent frontmatter | §4.4. Nothing asks for them, and bundling them makes Phase 2b's frontmatter change unreviewable against its stated reason |
| **G-6** | A `thinking` render mode in the REPL beyond dimmed text — collapsing, toggling, `/thinking` | Phase 3 ships the channel. What a terminal does with it is a CLI decision with no core dependency |

---

## 12. Staging and acceptance

Each phase is independently mergeable and leaves the tree green with no observable change beyond the
one named.

| Phase | Observable change when done | Acceptance |
|---|---|---|
| **1** | None for any model the table does not describe; a mismatched `thinkingMode` on a described `claude-*` model now succeeds with a WARN instead of a 400 | ① a `UNKNOWN`-dialect request is byte-identical to today's ② the §3.3 table is a test, one case per row ③ `AnthropicThinkingMode`'s "separate round" paragraph points here ④ CHANGELOG names the sixth capability field |
| **2a** | `thinkingMode` / `thinkingBudgetTokens` / `replayThinkingBlocks` settable from both surfaces; a deployment-wide `reasoningEffort` now reaches Anthropic too | ① both surfaces bind ② `AimonDocumentedPropertiesTest` green ③ **`default-config.yaml:13-14`'s stale "Only the openai provider" comment is gone** ④ the running branch refuses a populated foreign vendor block, and **only** the running branch (D-10, §4.5) ⑤ one line added to `llm-config-surface-open-items.md` `L-1` recording that its surface grew by three keys ⑥ #54 closed **with a note that its proposed key names were answered the other way**, and [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) §14's first bullet struck |
| **2b** | `reasoningEffort` settable from yaml, properties and agent frontmatter | ① L-1's two prerequisites land **in this phase** ② terra's ladder is a test ③ `builderWithDefaultsOverridesAPrefixInPlace` still green ④ backlog L-1 closed with what was measured, per [`backlog/README.md`](../../backlog/README.md) ⑤ the getting-started CLI reference **and its `.en.md`** updated in one commit with `source_commit` |
| **3** | Reasoning text streams to the UI when the deployment asks for it; nothing changes when it does not | ① `peekText()` unchanged by a `REASONING_DELTA` ② the new `Kind` and both its `switch` handlers in **one commit** ③ round trip through `AgentExecutionEventPayload` ④ `OutputFormatter` does not throw ⑤ CHANGELOG names the sealed-hierarchy addition as source-breaking |

Phase 1 before 2a: `AUTO` is a `thinkingMode` value, and exposing a config key for a value that does
not exist yet would be two changes to the same key. 2a before 2b: §4.3. Phase 3 last: it is the only
phase whose value depends on the other two — a reasoning stream is worth watching once a deployment
can say how much reasoning it wants.

---

## 13. Issue mapping — what is registered and what is not

*(`gh issue list --state all`, read 2026-09-09)*

| Phase | Issue | Relationship |
|---|---|---|
| **1** — thinking dialect | **#60 OPEN** | Filed from this document. F-1 had lived in design documents since #47 without ever being filed; it is the prerequisite the other Anthropic work kept deferring to, which is why it is numbered after #54 and ordered before it |
| **2a** — Anthropic keys | **#54 OPEN** — *"aimon-cli / starter: no config surface for Anthropic thinking"* | This phase **is** that issue, with two amendments it invites: its stated block (*"Ordering: #52, then this"*) is lifted — **#52 is CLOSED** — and its proposed key names are answered the other way by D-6, on the criterion the issue itself names. §4.1 |
| **2b** — neutral `reasoningEffort` key | **#61 OPEN** | Filed from this document, carrying `openai-model-capabilities-open-items.md` `L-1`'s two prerequisites in its own body — they are most of the phase, and L-1 named this issue's existence as its trigger |
| **3** — thinking stream | **#62 OPEN** | Filed from this document as **one** issue closing F-4, F-5 and F-7 — one feature across two providers, not three riders |
| *(adjacent)* | **#43 OPEN** — *"gpt-5.x reasoning models reject every tool-calling request"* | The origin of this whole track and **not** this round's work. Worth a look while here for a different reason: its own status comment says it stays open because the residual case *"is #46, and it needs a config surface rather than a client fix"* — and **#46 is CLOSED**. Whether anything of #43 survives that is a triage question, not a design one |
| *(unrelated)* | **#53 OPEN** | `${VAR}` in the CLI memory block. Shares a file with Phase 2a's edit (`default-config.yaml`) and nothing else |

Three of the four phases had no issue when this document was written; #60, #61 and #62 were filed
from it, and each body carries the phase's decisions rather than pointing at a document a reader may
not have. The tracker now shows the whole track. **#54 still carries the two amendments in its row
above** — its block is lifted and its proposed key names are answered the other way — and neither is
recorded on the issue itself.

## 관련 문서

- [`openai-responses-path.md`](openai-responses-path.md) — the `ReasoningTrace` slot this document does not rebuild, and F-5
- [`anthropic-thinking-traces.md`](anthropic-thinking-traces.md) — the dialect matrix, F-1, F-3, F-4, F-7
- [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) — F-2, closed; §14 hands off F-1 and #54
- [`openai-model-capabilities.md`](openai-model-capabilities.md) — the registry, fail-open, and the reporting convention
- [`model-capability-config-key.md`](model-capability-config-key.md) — §2.7, the namespace rule Phase 2 applies mechanically
- [`streaming.md`](streaming.md) — the chunk kinds and sink contract Phase 3 extends
- [`../../backlog/openai-model-capabilities-open-items.md`](../../backlog/openai-model-capabilities-open-items.md) — L-1, whose trigger Phase 2b fires
- [`../../backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md) — B-21, whose empty `llm.<vendor>.*` slot Phase 2a fills
- [`../../overview/glossary.md`](../../overview/glossary.md) — turn / iteration / execution, used strictly here
