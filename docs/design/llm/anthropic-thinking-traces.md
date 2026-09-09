# Design — Anthropic thinking blocks in the `ReasoningTrace` slot (#47)

> Status: **IMPLEMENTED.** This is the record of what landed, written against the approved design of
> round 1 and corrected where the code disagreed with it — §13 lists every departure. It is the
> Anthropic counterpart of [`openai-responses-path.md`](openai-responses-path.md), which built the
> `ReasoningTrace` slot this round fills.
>
> Branch: `herdr/anthropic-thinking-traces`. Gate: `./gradlew format && ./gradlew checkAll`, no network.
>
> **No live Anthropic API call was made** — this run has no key. Every claim about the *SDK* was read
> out of `anthropic-java-core-2.13.0-sources.jar`; every claim about what the *server* accepts is
> quoted from the official docs at `platform.claude.com` and is labelled where it matters. §9 is the
> complete list of what that leaves unverified, and **U-1 is the one that matters**: nothing here
> shows that Anthropic's verifier accepts a replayed signature, only that this client does not change
> it.
>
> This document is in English, matching its sibling. `docs/design/` is not a translation target either
> way (`documentation-guide.md` §5.1).

---

## 1. The problem, in one paragraph

Anthropic returns its chain of thought as `thinking` and `redacted_thinking` content blocks carrying a
`signature`, and a client that does not send those blocks back on the next request makes the model
re-derive its reasoning on every ReAct iteration — worse answers, and thinking tokens billed again each
turn, on exactly the multi-turn tool loops AIMON exists to run. `at.aimon.core.llm.ReasoningTrace` was
built for this in #43 phase 2 and is already persisted end to end, so the slot is not the work. The work
is that `AnthropicLlmClient` participates in none of it: `convertResponse` drops those blocks at
`AnthropicLlmClient.java:454`, `AnthropicStreamingMapper.java:145` records that thinking and signature
deltas are not surfaced, `AnthropicMessageConverter.convertAssistantMessage` (`:137`) has no idea traces
exist — and, one layer behind all three, `buildRequest` (`:311`) never sends a `thinking` parameter at
all, so on most models there is nothing to drop in the first place. Three further facts turn "read the
block, fill the slot" into a design round: since this SDK shipped, `thinking` has split into **two
mutually exclusive modes** whose availability is per-model and whose mismatch is a 400; `temperature`
is **incompatible with thinking** on every model that has it, while `buildRequest:325` sends it
unconditionally; and on the newest models thinking is **on by default**, which means the read half of
this feature is needed with no request-side configuration whatsoever.

---

## 2. What was verified, before anything was decided

Everything in this section is a citation, not an inference. It is here because four of the six decisions
below turn on it.

### 2.1 There are two thinking modes now, and the wrong one is a 400

Quoting the per-model table on
[Troubleshooting thinking](https://platform.claude.com/docs/en/build-with-claude/thinking-troubleshooting):

| Model | Thinking types | Default | Rejected with 400 |
|---|---|---|---|
| Claude Fable 5.1 | Adaptive only | Always on | `"enabled"`, `"disabled"` |
| Claude Mythos 5.1 | Adaptive only | Always on | `"enabled"`, `"disabled"` |
| Claude Fable 5 | Adaptive only | Always on | `"enabled"`, `"disabled"` |
| Claude Mythos 5 | Adaptive only | Always on | `"enabled"`, `"disabled"` |
| Claude Mythos Preview | Adaptive, extended | Always on | `"disabled"` |
| Claude Opus 5 | Adaptive only | On | `"enabled"`, `"disabled"`¹ |
| Claude Opus 4.8 | Adaptive only | Off | `"enabled"` |
| Claude Opus 4.7 | Adaptive only | Off | `"enabled"` |
| Claude Sonnet 5 | Adaptive only | On | `"enabled"` |
| Claude Opus 4.6 | Adaptive, extended (deprecated) | Off | None |
| Claude Sonnet 4.6 | Adaptive, extended (deprecated) | Off | None |
| Claude Opus 4.5 | Extended only | Off | `"adaptive"` |
| Claude Haiku 4.5 | Extended only | Off | `"adaptive"` |
| Claude Sonnet 4.5 | Extended only | Off | `"adaptive"` |

¹ Opus 5 accepts `"disabled"` only at effort `high` or below.

Earlier Claude 4 models (Opus 4.1, Sonnet 4, Opus 4) are extended-only. **`AnthropicConfig`'s default
model is `claude-sonnet-4-20250514`** (`AnthropicConfig.java:28`), which sits in that last group.

The two shapes:

```jsonc
{ "thinking": { "type": "enabled", "budget_tokens": 10000 } }            // extended / manual
{ "thinking": { "type": "adaptive" }, "output_config": { "effort": "high" } }  // adaptive
```

Both exist in the vendored SDK: `ThinkingConfigParam.ofEnabled/ofDisabled/ofAdaptive`,
`ThinkingConfigEnabled.budgetTokens(Long)`, `OutputConfig.Effort` with `LOW`/`MEDIUM`/`HIGH`/`MAX`,
and `MessageCreateParams.Builder.thinking(...)` / `.outputConfig(...)`. The SDK even ships
`com.anthropic.helpers.ThinkingWarnings`, which prints a deprecation warning to stderr for
`thinking.type=enabled` on `claude-opus-4-6`.

The 400 messages are exact and quotable:

```text
"thinking.type.enabled" is not supported for this model. Use "thinking.type.adaptive" and
"output_config.effort" to control thinking behavior.
```
```text
adaptive thinking is not supported on this model
```

### 2.2 `budget_tokens` bounds (SDK javadoc *and* docs agree)

`ThinkingConfigEnabled.budgetTokens`'s own javadoc: *"Must be ≥1024 and less than `max_tokens`."*
The docs add the reason and one exception: *"**Minimum of 1,024 tokens.** The API rejects smaller
values."* / *"**Less than `max_tokens`.** Thinking tokens count toward the `max_tokens` limit for the
turn… The one exception is interleaved thinking, where `budget_tokens` can exceed `max_tokens`."*

Tuning guidance, which is the only vendor-published anchor for a ladder: *"For simple tasks, start near
the 1,024-token minimum… For complex tasks, start with a larger budget of 16,000 tokens or more"* and
*"For thinking budgets above 32k, use batch processing."*

### 2.3 The temperature constraint is real — TASK item 3, answered from the docs, not guessed

[Thinking → Limits and feature compatibility → Sampling parameters](https://platform.claude.com/docs/en/build-with-claude/thinking#limits-and-feature-compatibility), verbatim:

> On Claude Fable 5.1, Claude Mythos 5.1, Claude Fable 5, Claude Mythos 5, Claude Mythos Preview,
> Claude Opus 5, Claude Opus 4.8, Claude Opus 4.7, and Claude Sonnet 5, non-default `temperature`,
> `top_p`, or `top_k` values return a 400 error on every request, regardless of whether thinking is
> used. On older models, the restriction applies only while thinking is on: `temperature` and `top_k`
> are incompatible with thinking, and `top_p` is allowed at values between 0.95 and 1.

The **SDK javadoc says none of this** — `MessageCreateParams.temperature`'s doc is the generic
*"Amount of randomness injected into the response. Defaults to `1.0`. Ranges from `0.0` to `1.0`"*, and
`thinking`'s doc never mentions sampling. So this is a server rule only, which is exactly why the task
said not to guess.

Two consequences, and they are different in kind:

1. **Turning thinking on with today's `buildRequest` is a guaranteed 400.** `:325` calls
   `.temperature(temperature)` unconditionally, defaulting to `0.0`.
2. **A pre-existing, unrelated breakage is now visible.** On Sonnet 5 / Opus 5 / Opus 4.7 / 4.8 and the
   Fable/Mythos family, *any* non-default temperature 400s on every request — thinking or not — so
   `AnthropicLlmClient` cannot talk to those models today at all. That is a model fact, not a thinking
   fact; §8 F-2 records it as a follow-up rather than smuggling a second fix into this one.

### 2.4 The round-trip contract

- *"Pass every `thinking` block back to the API complete and unmodified, alongside the `tool_use` block
  it accompanied."* — **Required** within a tool-use turn, **recommended** across turns, **allowed** to
  omit outside tool use.
- *"Within the latest assistant message, the sequence of consecutive `thinking` blocks must match what
  the model generated in the original request: you can't rearrange, edit, or partially drop them. This
  includes `redacted_thinking` blocks."*
- Modified blocks return 400 ``…`thinking` or `redacted_thinking` blocks in the latest assistant message
  cannot be modified``, and the docs name our exact hazard as the usual cause: *"your code filters
  content blocks by type and drops `redacted_thinking` blocks, or rebuilds the assistant message
  instead of echoing it."*
- Extended mode adds: *"the final assistant turn of a thinking-enabled request must begin with a
  thinking block"* (adaptive drops that requirement).
- Ordering within a turn: a thinking block *"sits immediately before the `tool_use` or
  `server_tool_use` block it introduces"*.
- Mid-turn conflicts *"degrade gracefully… the API doesn't error. Instead, it silently disables thinking
  for that request… the API may strip thinking blocks that would create an invalid turn structure."*
- Old blocks are safe to pass: *"You don't need to prune old thinking yourself… the API automatically
  filters them."* Models either keep all prior turns or keep only the last; both are automatic.

### 2.5 Streaming

*"Thinking blocks stream as `thinking_delta` events inside `content_block_delta` events, followed by a
single `signature_delta` event just before the block's `content_block_stop`."* All present in the SDK:
`RawContentBlockDelta.isThinking()/asThinking()` → `ThinkingDelta.thinking()`,
`isSignature()/asSignature()` → `SignatureDelta.signature()`, and
`RawContentBlockStartEvent.ContentBlock.isThinking()/isRedactedThinking()`.

`display` defaults to `"omitted"` on newer models, which *"returns thinking blocks with an empty
`thinking` field"* — so a stream can produce **zero** `thinking_delta` events and still produce a block
that must be replayed. The signature is the load-bearing half, not the text.

### 2.6 The token counter — TASK item 6

The docs name it: *"monitor the `usage.output_tokens_details.thinking_tokens` field in the response,
which reports how many of the billed output tokens were internal reasoning. When streaming, this
breakdown appears only on the final `message_delta` event."* And thinking tokens *"are billed as output
tokens"*, so they are contained in `output_tokens`, not additional to it.

**The vendored SDK 2.13.0 has no typed accessor for it.** `Usage` exposes `inputTokens`,
`outputTokens`, `cacheCreation*`, `cacheRead*`, `inferenceGeo`, `serverToolUse`, `serviceTier` — and
nothing else; `MessageDeltaUsage` likewise. `grep -rl "output_tokens_details\|thinking_tokens"` over the
sources jar returns **nothing**. Both types do carry `@JsonAnySetter`/`@JsonAnyGetter`, so the field
lands in `_additionalProperties()` under `"output_tokens_details"`.

### 2.7 The SDK mapper, and why the union type is the right parse target

`com.anthropic.core.ObjectMappers.jsonMapper()` has the same shape as OpenAI's — `NON_ABSENT`
inclusion, every `AUTO_DETECT_*` disabled — so the same rule applies: **serialise and parse a thinking
block with the SDK's own mapper, never a fresh `ObjectMapper`.**

Better than OpenAI's case in one respect. `ContentBlockParam`'s deserializer dispatches on the `type`
discriminator (`"thinking"` → `ThinkingBlockParam`, `"redacted_thinking"` → `RedactedThinkingBlockParam`)
and **falls back to `ContentBlockParam(_json = json)` for a type it has never heard of**, whose
serializer writes the raw JSON straight back out. So one parse target handles both kinds *and* survives
a block type Anthropic adds later. `tryDeserialize` swallows exceptions and the `@JsonCreator`
constructors default every `JsonField` to missing, so `checkRequired` is never reached — the same
property `OpenAiReasoningTraces` relies on, for the same reason.

---

## 3. The approach

Six pieces, all inside `modules/aimon-llm-anthropic`.

### 3.1 Capture and replay are unconditional; only the request parameter is configured

This is the load-bearing split, and it falls straight out of §2.1: on Opus 5, Sonnet 5, Fable 5.1,
Mythos 5.1, Fable 5, Mythos 5 and Mythos Preview, **thinking is on with no configuration**. A client
that gates its *read* path on a config flag does nothing on the models where the feature matters most,
and every test the author writes stays green.

So:

| Half | Gate |
|---|---|
| Read `ThinkingBlock`/`RedactedThinkingBlock` into `ReasoningTrace` | **none** — always |
| Emit stored traces back into the assistant message | `AnthropicConfig.replayThinkingBlocks` (default `true`) |
| Send the `thinking` request parameter | `AnthropicConfig.thinkingMode` (default `OFF`) |

**"A thinking-off deployment does not change by one character" still holds**, and structurally rather
than by promise: a model that is not thinking returns no thinking blocks, so the capture loop appends
nothing; a transcript written before this change carries no Anthropic traces, so the emit loop emits
nothing; and with `thinkingMode = OFF` the request body is byte-identical to today's, temperature
included. A test asserts the serialised body, not the absence of a setter call.

The replay switch exists because this change lets AIMON reach an error class it currently cannot — §7
row 7, the preserved-thinking prefix check. It is the same escape hatch, for the same reason, as
`OpenAIConfig.responsesApiEnabled(false)`.

### 3.2 The capture rule diverges from OpenAI's, and that divergence is the point

OpenAI's rule is *"each reasoning item anchors to the first tool call that follows it"*. Transplanted
here it is wrong, because Anthropic puts **text between the thinking block and the tool call** on the
ordinary shape `[thinking, text, tool_use]`. Anchoring that thinking block to the tool call and then
running OpenAI's emit rule (unanchored → text → per-call anchored) reconstructs it as
`[text, thinking, tool_use]`: the turn no longer begins with a thinking block (§2.4, an extended-mode
requirement) and the consecutive-sequence check sees a rearrangement.

**Anthropic capture rule:** walking the response content in order, a thinking block anchors to the first
`tool_use` that follows it, **unless a `text` block intervenes first**, in which case it is unanchored.

**Anthropic emit rule** (identical in shape to OpenAI's — the divergence is entirely in capture):

```
emit, for one ASSISTANT message:
  1. every trace with no anchor, in stored order          -> leads the message
  2. the text block, when there is text
  3. for each tool use in order: its anchored traces (stored order), then the tool_use block
```

The pair reproduces the provider's own order exactly for every single-text-block shape (two shapes are
reordered, both because `Message` carries one concatenated text — U-3):

| Response content | Anchors | Emitted |
|---|---|---|
| `[think, text, tool_use]` | think → none | `[think, text, tool_use]` ✅ |
| `[think, tool_use]` | think → tu | `[think, tool_use]` ✅ |
| `[think1, think2, tool_use]` | both → tu | `[think1, think2, tool_use]` ✅ |
| `[think1, tu1, think2, tu2]` (interleaved / progress updates) | t1→tu1, t2→tu2 | `[think1, tu1, think2, tu2]` ✅ |
| `[think, text, tu1, think2, tu2]` | t1→none, t2→tu2 | `[think, text, tu1, think2, tu2]` ✅ |
| `[think, text]` (no tools) | think → none | `[think, text]` ✅ |

The two it would reorder — text *after* a tool_use, and thinking interleaved with **more than one** text
block — are both shapes AIMON cannot represent in the first place: `Message` concatenates all text into
one `getContent()` string, and the existing converter (`:143-155`) already emits
text-then-all-tool-uses. The loss predates this change and is not widened by it. U-3 has both.

**Both paths run the same resolution function.** A new package-private `AnthropicOutputBlocks` takes an
ordered list of `(kind, payload | toolUseId)` records and returns `List<ReasoningTrace>`; the blocking
converter feeds it `Message.content()` and the streaming mapper feeds it the block sequence it observed.
Two implementations of an ordering rule that must agree is precisely how the OpenAI round trip was
nearly lost on the streaming path.

### 3.3 The provider name is resolved once per request

TASK's landmine, and it is wide open here: `AnthropicLlmClient` is public and non-final,
`getProviderName()` (`:405`) is public, and `AnthropicMessageConverter` is built in the constructor
(`:124`) with no provider name at all. Resolving it in two places at two times is how a subclass tags a
trace `"MyAnthropic"` and then drops it as foreign, leaving a feature that compiles, tests green, and
does nothing.

The fix is the same as OpenAI's and no larger: `sendMessage`/`sendMessageStreaming` read
`getProviderName()` **once**, pass it into `buildRequest(...)` → `converter.convertMessages(messages,
providerName, reporter)` for the replay half, and into `convertResponse(result, providerName)` /
`new AnthropicStreamingMapper(sink, aggregator, providerName, reporter)` for the capture half. The model
name is already resolved this way (`:324`). A test with a subclass that overrides `getProviderName()`
binds it.

### 3.4 The `thinking` parameter — mode named by the operator, depth by the call

```
AnthropicConfig.thinkingMode : OFF (default) | EXTENDED | ADAPTIVE     // which dialect this model speaks
AnthropicConfig.thinkingBudgetTokens : Integer (nullable)              // EXTENDED only, explicit override
LlmModel.reasoningEffort : NONE | MINIMAL | LOW | MEDIUM | HIGH        // how much, per call
```

Resolution, in `buildRequest`:

| mode | effort | request carries |
|---|---|---|
| `OFF` | any | nothing (today's body, byte for byte) |
| `EXTENDED` | `NONE` | nothing — extended mode's off state *is* the absent parameter |
| `EXTENDED` | absent | `thinking: {enabled, budget_tokens: config budget ?: MEDIUM rung}`, clamped |
| `EXTENDED` | a rung | `thinking: {enabled, budget_tokens: rung}`, clamped; explicit config budget wins and the ignored effort is reported |
| `ADAPTIVE` | `NONE` | nothing |
| `ADAPTIVE` | absent | `thinking: {adaptive}` |
| `ADAPTIVE` | a rung | `thinking: {adaptive}` + `output_config: {effort: …}` |

`NONE` suppressing the parameter in both modes is uniform and cannot 400. It under-delivers on exactly
one population — the always-on models, which think whether or not we ask — and the alternative,
`thinking: {type: "disabled"}`, is rejected by five of them with a 400 (§2.1). Stated, not hidden.

### 3.5 Sampling parameters are omitted, never substituted, and the operator is told

When this request carries a `thinking` parameter:

- `temperature` — **the setter is not called.** Omission is "never call the setter", the rule the OpenAI
  path already follows: any route that leaves the key present with a null value puts
  `"temperature": null` on the wire, and a server that rejects the parameter rejects the null form the
  same way. (`MessageCreateParams` exposes only `temperature(Double)` and
  `temperature(JsonField<Double>)`, so the null form is not even reachable here — the rule still names
  what must not be done, because `JsonField` has a nullable route in general.)
- `top_p` — sent only when the configured value is within `[0.95, 1.0]`; otherwise omitted.
- `top_k` — nothing to do; `LlmModel` has no such field.

Each omission goes through the existing `reportDivergence(...)` (`:379`), at WARN, once per distinct
signature, because the observable outcome is a request that **succeeds with settings other than the ones
configured** — no status code, nothing else in the system that would tell an operator. That method
exists for exactly this and the task named it. The existing clamp for `temperature > 1.0` stays as it is
on the non-thinking path.

### 3.6 The token counter, read untyped

`AnthropicUsages` (new, package-private) is the single place `output_tokens_details.thinking_tokens` is
read, from `_additionalProperties()`, for both the blocking `Usage` and the streaming
`MessageDeltaUsage`. One shared class rather than a copy in each mapper, for the reason
`OpenAiResponseUsages` exists (that design's departure #4). It never throws: a missing key, a wrong
shape, a value that is not a number, an overflow — all yield `0`, because a missing counter must not
fail a turn that otherwise succeeded. It is **not** added to `totalTokens` and **not** priced, matching
`TokenUsage`'s documented containment semantics and `ModelPrice.costOf`'s deliberate blind spot.

---

## 4. Alternatives rejected

| # | Alternative | Why not |
|---|---|---|
| **A1** | **Extend `ModelCapabilityRegistry` to Anthropic and derive the mode from the model.** | Two reasons, and the second is decisive. (i) Out of scope by instruction, and RUN.md has #48 concurrently editing `aimon-core/.../llm/capability/`. (ii) **The fact that matters is not expressible in today's `ModelCapabilities`.** Its five fields are `supportsSamplingParameters`, `supportsReasoningEffort`, `supportsToolsWithReasoning`, `supportsReasoningTraceRoundTrip`, `lowestReasoningEffort`; none carries a two-valued, mutually-exclusive *dialect* axis. "Extend the registry" really means "add a sixth field, an Anthropic table, and a fail-open story for a field whose fail-open value is a 400 either way" — a design round, not a rider. §8 F-1. |
| **A2** | **Sniff the model name** (`model.startsWith("claude-sonnet-4-5")` → extended, else adaptive). | Reintroduces exactly what #43's round-1 acceptance criterion 4 removed from the OpenAI request builder, and it is worse here: the table is 14 rows, changes with every release, and a gateway or Bedrock deployment renames the model anyway. The failure is silent for a while and then a 400 nobody can trace to a string comparison. |
| **A3** | **A single boolean `thinkingEnabled` that sends `{type: "enabled", budget_tokens: N}`.** The obvious reading of the issue. | It is the *legacy* mode. It 400s on Opus 4.7, Opus 4.8, Opus 5, Sonnet 5, Fable 5/5.1, Mythos 5/5.1 — i.e. every model released after the SDK's own examples — and is deprecated on the 4.6 pair. Shipping a knob whose only value is rejected by the current generation is a knob that has to be redesigned before anyone uses it. |
| **A4** | **A single boolean that sends `{type: "adaptive"}`.** | The mirror image: 400 (`adaptive thinking is not supported on this model`) on Sonnet 4.5, Opus 4.5, Haiku 4.5 and every earlier Claude 4 — including `AnthropicConfig`'s own default model. |
| **A5** | **Gate the read path on the thinking config too** ("if we did not ask for thinking, do not look for it"). | Cheaper, symmetric, and wrong: on the seven always-on models the blocks arrive unasked, so the feature would be inert on the models it matters most for while every unit test passed. §3.1. |
| **A6** | **Reuse OpenAI's capture rule verbatim** (anchor to the first following tool call, unconditionally). | Produces `[text, thinking, tool_use]` on the ordinary shape, breaking the "must begin with a thinking block" rule and the consecutive-sequence check. §3.2. The rule looks portable and is not; that is why the OpenAI design put reconstruction *in the provider*. |
| **A7** | **Store a discriminator (`thinking` vs `redacted_thinking`) alongside the payload**, e.g. a payload prefix or a second `ReasoningTrace` field. | Unnecessary and it would widen a persisted core type for nothing. Each block's own JSON carries `"type"`, and `ContentBlockParam`'s deserializer dispatches on it (§2.7). That is what opacity buys, and the phase-2 design predicted this exact outcome. |
| **A8** | **Rebuild the block through `ThinkingBlockParam.builder()`** from `signature` + `thinking` strings. | Two costs. It drops `additionalProperties` — any field the server adds that this SDK does not model — and it re-introduces `checkRequired` validation on a payload the *server itself* produced, converting "drop one trace" into a thrown exception. Deserialising into the union bypasses both. (The streaming path is the one place a rebuild is unavoidable; §5.4 says how it is minimised.) |
| **A9** | **Emit new `LlmStreamChunk` kinds for thinking text** so a UI can show a live "thinking" stream. | Needs a new `Kind` in `aimon-core`, which this run is told to stay out of, and it is a *user-facing feature* rather than the cross-turn carry this issue is about — the same split that made reasoning-summary streaming F-5 on the OpenAI side. §8 F-4. |
| **A10** | **Add the `interleaved-thinking-2025-05-14` beta header** so extended-mode models think *between* tool calls. | A beta header is a deployment-visible change with its own per-model matrix (accepted-and-ignored on Haiku 4.5, no effect on Opus 4.6 manual mode, deprecated on Sonnet 4.6) and it changes how `budget_tokens` is counted against `max_tokens`. It rides on nothing; it needs its own gate. §8 F-3. |
| **A11** | **Set `display: "summarized"`** so thinking text comes back. | **Moot on this SDK before it is declined:** `ThinkingConfigAdaptive` in 2.13.0 carries `type` and nothing else, so there is no `display` field to set. Were there one, it would still be declined — it costs output tokens for text nothing in AIMON currently renders, and changes the cached prompt prefix. The round trip needs the `signature`, not the summary. Leave the server default. |
| **A12** | **Fix the always-400 sampling regime for Sonnet 5 / Opus 5 / Fable / Mythos** while in here (§2.3 consequence 2). | It is a *model* fact needing a per-model source of truth — i.e. A1 — and it is not caused by this change. Fixing it here would mean shipping A1 under a different name. §8 F-2. |

---

## 5. Concrete changes, by file

All production changes are inside `modules/aimon-llm-anthropic`. **Nothing in `aimon-core`,
`aimon-cli`, `aimon-spring-boot-starter`, `aimon-core/.../llm/capability/` or `aimon-llm-openai` is
touched**, which keeps this run clear of the two concurrent ones. `CHANGELOG.md` gets one section — the
only file this round shares with the two concurrent rounds, so the inventory of what was touched outside
this module is handed over separately rather than committed here.

### 5.1 New files

| File | Visibility | What it is |
|---|---|---|
| `AnthropicThinkingMode.java` | **public** | `OFF`, `EXTENDED`, `ADAPTIVE`. Public because `AnthropicConfig.Builder` takes it. Its javadoc carries the §2.1 table and both 400 messages verbatim — an operator who picks wrong should be able to grep the error text and land here. |
| `AnthropicReasoningTraces.java` | package-private | The **only** place a thinking block is serialised or parsed. `toTrace(ContentBlock, String providerName, String toolUseId)`, `toBlockParam(ReasoningTrace, String providerName) -> Optional<ContentBlockParam>`, `isOurs(...)`. Holds `ObjectMappers.jsonMapper()` and a javadoc paragraph saying why it is not a fresh `ObjectMapper`. Mirrors `OpenAiReasoningTraces` one-for-one. |
| `AnthropicOutputBlocks.java` | package-private | The shared anchor resolver of §3.2. Input: an ordered `List<Block>` of `(THINKING payload | TEXT | TOOL_USE id)`. Output: `List<ReasoningTrace>` in stored order with anchors set. Pure, no SDK types in the signature, trivially unit-testable against every row of the §3.2 table. |
| `AnthropicThinkingBudgets.java` | package-private | `budgetFor(ReasoningEffort, Integer configuredBudget, int maxTokens)` → `OptionalInt`, and `effortFor(ReasoningEffort)` → `Optional<OutputConfig.Effort>`. The ladder, the clamp, and the give-up case (§6.2). Pure. |
| `AnthropicUsages.java` | package-private | `thinkingTokens(Usage)` and `thinkingTokens(MessageDeltaUsage)`, untyped, never throwing (§3.6). |
| `AnthropicDivergenceReporter.java` | package-private | One-method functional interface, implemented by `AnthropicLlmClient::reportDivergence`. Exists so the converter can report **on the client's logger with the client's dedup set** — the two properties the divergence tests attach to. Copied in shape from `OpenAIDivergenceReporter`, whose javadoc explains why a collaborator that reported for itself would have neither. |

### 5.2 `AnthropicConfig.java`

Three new builder setters, taking the count from six to nine.

```java
AnthropicConfig.builder()
        .apiKey(key)
        .model("claude-sonnet-4-5")
        .thinkingMode(AnthropicThinkingMode.EXTENDED)   // default OFF
        .thinkingBudgetTokens(10_000)                   // optional; EXTENDED only
        .replayThinkingBlocks(true)                     // default true
        .maxTokens(16_000)
        .build();
```

Constructor validation, alongside the existing three checks:

- `thinkingBudgetTokens`, when set, must be `>= 1024` — the API rejects less unconditionally (§2.2), so
  failing at construction beats failing on every request.
- `thinkingBudgetTokens` set while `thinkingMode != EXTENDED` throws: adaptive mode has no budget, and a
  value that is silently ignored is the failure `reportDivergence` exists to remove — but at
  construction time we can do better than a warning.
- `equals`/`hashCode`/`toString` extended. `toString` prints the mode and budget; it already prints no
  secrets.

### 5.3 `AnthropicLlmClient.java`

1. `getProviderName()` resolved **once** in each of the two public entry points and threaded through
   (§3.3).
2. `buildRequest(...)` gains the thinking parameter (§3.4) and the sampling omission (§3.5). Its
   signature grows by `String providerName`.
3. `convertResponse(result, providerName)` walks `result.content()` recording `(kind, payload | id)` in
   order, feeds `AnthropicOutputBlocks`, and attaches the result with
   `LlmResponse…withReasoningTraces(traces)`. The `// Ignore other block types (ThinkingBlock,
   RedactedThinkingBlock, etc.)` comment at `:454` is deleted, not amended — it is no longer true of
   two of the three types it names.
4. `extractTokenUsage` adds the fourth counter via `AnthropicUsages`, keeping
   `totalTokens = input + output`.

### 5.4 `AnthropicMessageConverter.java`

- New `public List<MessageParam> convertMessages(List<Message>, String providerName,
  AnthropicDivergenceReporter reporter)`.
- The existing one-argument `convertMessages(List<Message>)` **stays**, `@Deprecated`, delegating with
  replay disabled, with a javadoc sentence saying it cannot replay because it has no provider name to
  match against. `at.aimon.core.llms.anthropic` is this published module's public API
  (`api-stability.md` §2); `0.x` permits the break, but a deprecated delegate costs three lines and one
  CHANGELOG sentence, and the alternative is an external caller whose build breaks for a feature they
  did not ask for.
- `convertAssistantMessage` implements the emit rule of §3.2. Two shape changes worth naming: an
  assistant message with **traces but no tool uses** must now take the block-list branch rather than
  the string-content fast path at `:138-140`, and an assistant message with **traces and no text** must
  not emit an empty text block.
- Drop reasons are reported, never swallowed, with three distinct signatures so the log says which
  happened: `foreignReasoningTrace@<other>`, `unparseableReasoningTrace@Anthropic`,
  `orphanedReasoningTrace@Anthropic`.

### 5.5 `AnthropicStreamingMapper.java`

- Constructor gains `providerName` and the reporter.
- `onContentBlockStart` records **every** block index and kind, not only `tool_use`:
  - `thinking` → open a slot seeded from the `ThinkingBlock` the event carries (which is where any
    `additionalProperties` live), so the eventual payload is `block.toBuilder().thinking(text)
    .signature(sig).build()` rather than a bare rebuild — A8's cost is paid down to the two fields the
    protocol genuinely re-streams.
  - `redacted_thinking` → the block arrives **whole** on `content_block_start`; it is complete
    immediately. (Unverified — §9 U-4.)
  - `text` → record the index so the "text intervenes" half of the capture rule works.
- `onContentBlockDelta` handles `delta.isThinking()` → append to the slot's text, and
  `delta.isSignature()` → set the slot's signature. The `// Other delta variants (thinking, signature,
  citations) are not yet surfaced to the framework.` comment at `:145` is rewritten to say what is now
  true: thinking and signature deltas feed the trace payload, citations are still not modelled, and no
  thinking text is emitted to the sink (A9 / §8 F-4).
- `onContentBlockStop` closes a thinking slot. **A slot that never received a `signature_delta` is
  dropped with one warning** — a thinking block without a signature is a guaranteed 400 on replay
  (§7 row 9).
- `emitStreamEnd()` **flushes the resolved traces into the aggregator before** constructing the
  terminal chunk. Order matters and is not cosmetic: `ChunkAggregator.addReasoningTrace` throws
  `IllegalStateException` once the aggregator is closed, and `toLlmResponse()` is called *outside* the
  client's try-with-resources, so the throw would escape unmapped. Both callers of `emitStreamEnd()` —
  `message_stop` and the defensive early-close fallback — go through the same flush.
- `onMessageDelta` / `buildTokenUsage` carry `thinkingTokens` via `AnthropicUsages`, which is where the
  docs say the streaming breakdown appears (§2.6).

### 5.6 Documentation and changelog

| File | Change |
|---|---|
| `docs/design/llm/anthropic-thinking-traces.md` | **new** — this document, `Status` rewritten to record what landed and a §"where the implementation departed" added, as its sibling has. |
| `docs/design/README.md` | one row in the `llm/` table. |
| `docs/design/llm/streaming.md` | §9's open item currently says reasoning-trace streaming is done "on the OpenAI side". One sentence: the Anthropic half now consumes thinking and signature deltas into the trace, and still does not forward thinking text to the sink. Korean, no `.en.md` pair — `design/` is not a translation target. |
| `docs/features/llm/llm-provider-development-guide.md` + `.en.md` | **not touched.** Its four-step rule already covers this and gains nothing from a second worked example; touching it costs a paired translation edit and a `source_commit` bump on both files, for prose that would restate §3.2. Recorded as a decision rather than an oversight. |
| `CHANGELOG.md` | one `### LLM:` section under `[Unreleased]`, this run's only edit to a file the other two runs also touch. |

---

## 6. Data and interface shapes that change

### 6.1 Nothing in `aimon-core` changes shape

`ReasoningTrace`, `Message`, `LlmResponse`, `ChunkAggregator`, `TokenUsage` and the persisted transcript
format are all used exactly as they are. The eight assistant-message construction sites already attach
`response.getReasoningTraces()`; this provider fills the same list the OpenAI one fills. That is what
#43 phase 2 bought and it is worth stating that the bill came out at zero.

`payload` for a thinking block:

```json
{"signature":"EqMBCkYICxIM...","thinking":"Let me check the second constraint first...","type":"thinking"}
```

and for a redacted one:

```json
{"data":"EvgBCkYIA...","type":"redacted_thinking"}
```

`toolUseId` holds `ToolUseBlock.id()` (`toolu_…`) — the same neutral id `ToolUse.getId()` already
carries and the same id a `tool_result` must match. `providerName` is `"Anthropic"`, or whatever a
subclass reports.

### 6.2 `AnthropicThinkingBudgets` — the ladder, and what is arbitrary about it

`ReasoningEffort` is a **five**-rung ladder (`NONE, MINIMAL, LOW, MEDIUM, HIGH`), not three; the task
statement says three. It does not change the argument — the axis mismatch is the point either way — and
the enum's own javadoc already anticipates this exact translation: *"Anthropic expresses the same axis
as a thinking token budget, so even these five are a mapping rather than a shared type."*

**EXTENDED mode** — effort → `budget_tokens`:

| rung | budget | where the number comes from |
|---|---|---|
| `NONE` | — | no `thinking` parameter at all (§3.4) |
| `MINIMAL` | **1024** | the API's documented floor, and the docs' own advice for simple tasks: *"start near the 1,024-token minimum"* |
| `LOW` | **2048** | doubled from the floor — **arbitrary** |
| `MEDIUM` | **4096** | doubled again — **arbitrary** |
| `HIGH` | **16000** | the docs' own starting point for complex tasks: *"start with a larger budget of 16,000 tokens or more"* |

Both ends are the vendor's published numbers; the two middle rungs are not, and saying which is which
is the whole of the honesty here. The gap between `MEDIUM` and `HIGH` is deliberately the widest,
because the vendor's guidance is itself bimodal — near-1024 for simple, 16000+ for complex — rather than
a linear scale. Nothing above 16000 is reachable, which keeps every request clear of the
*"above 32k, use batch processing"* warning.

Three properties are **not** arbitrary and each gets a test:

1. **Monotonic non-decreasing** across the ladder. `ReasoningEffort`'s declaration order is documented
   as load-bearing; a mapping that inverted two rungs would silently invert the caller's intent.
2. **Floor.** Never below 1024. A value in `1..1023` is rejected by the API on every request.
3. **Clamp.** `budget = min(mapped, maxTokens - 1)`, from `ThinkingConfigEnabled.budgetTokens`'s own
   javadoc *"less than `max_tokens`"*. This bites immediately: `AnthropicConfig`'s default
   `maxTokens` is **4096**, so `HIGH` clamps to 4095 out of the box. The clamp is reported through
   `reportDivergence`, because a caller who asked for `HIGH` and silently got the `LOW` budget has no
   other way to find out.
4. **Give up loudly.** If `maxTokens <= 1024`, no legal budget exists: the `thinking` parameter is
   **omitted entirely** and the divergence is reported. Sending a request the server is certain to
   reject is worse than not asking for thinking.

**ADAPTIVE mode** — effort → `output_config.effort`, a ladder-to-ladder mapping and much less arbitrary.
The target is **the vendored SDK's ladder, not the API's**: `OutputConfig.Effort` in 2.13.0 offers
`LOW`/`MEDIUM`/`HIGH`/`MAX`, while the current API also has `xhigh`, documented as the best setting for
coding and agentic work on Opus 4.7/4.8, Sonnet 5 and Fable 5. The mapping is monotonic, bounded and
correct against the SDK; the top of the vendor's range is simply not expressible here.

| `ReasoningEffort` | `OutputConfig.Effort` |
|---|---|
| `NONE` | — (parameter omitted, §3.4) |
| `MINIMAL` | `LOW` |
| `LOW` | `LOW` |
| `MEDIUM` | `MEDIUM` |
| `HIGH` | `HIGH` |

`MINIMAL` and `LOW` collapsing is a real loss of one rung: Anthropic's ladder starts at `low`. `MAX` —
and, above it, the API's `xhigh` — is unreachable for two independent reasons: nothing in AIMON's ladder
sits above `HIGH`, and `xhigh` is not in this SDK version at all. The first is deliberate, since `HIGH`
is documented as the API default and inventing a rung above the caller's maximum would over-spend on
their behalf; the second lifts when the SDK is bumped.

### 6.3 `TokenUsage`

Existing behaviour-visible note from #43 applies unchanged: a usage carrying reasoning tokens is not
`equals` to one without. Nothing new. `reasoningTokens` is reported, never priced, never added to
`totalTokens`.

---

## 7. Failure modes

| # | Failure | Handling |
|---|---|---|
| 1 | **A foreign trace reaches this client** — an OpenAI `reasoning` item in a session moved by `LlmFallbackPolicy`, an operator switching providers and resuming, a subagent snapshot replayed elsewhere. | Dropped, warned once at `foreignReasoningTrace@OpenAI`. Sending it to `/v1/messages` is a 400 at best. A test drives exactly this. |
| 2 | **A stored payload this build cannot parse** — a transcript from a newer build, a corrupted row. | Dropped, warned once, turn proceeds. Trace decoding **never rethrows**: a dropped trace costs re-derived reasoning, a thrown exception costs the session. Note the SDK makes this rare rather than common — an unknown `type` still round-trips through `ContentBlockParam`'s `_json` fallback (§2.7). |
| 3 | **A trace anchored to a tool call this message no longer carries.** | Dropped, warned. Not reachable in-tree: `MessageStripper` drops traces while keeping tool uses, and `DefaultCompactionEngine` carries survivors whole. Reported anyway, because a silent omission is the one shape an operator cannot diagnose from outside. |
| 4 | **The configured mode is the one this model rejects** — `EXTENDED` on Sonnet 5, `ADAPTIVE` on Sonnet 4.5. | A 400 → `LlmInvalidRequestException` (`AnthropicExceptionMapper:201`), which is **not retryable**, so it fails loudly and once rather than burning a retry budget. The two exact server messages are quoted in `AnthropicThinkingMode`'s javadoc and in the CHANGELOG, so the error text leads to the one-line fix. This is the price of A1's rejection and it is named rather than minimised. |
| 5 | **The budget does not fit under `max_tokens`.** The default `maxTokens` of 4096 makes this the common case, not the edge case. | Clamped to `maxTokens - 1` and reported; if no legal budget exists, `thinking` is omitted and reported. §6.2. |
| 6 | **`temperature` / `top_p` incompatible with thinking.** | Omitted — setter never called — and reported. Never silently substituted with a "safe" value: a request that succeeds with sampling the operator did not choose is exactly what `reportDivergence` was built to surface. §3.5. |
| 7 | **The preserved-thinking prefix check rejects a replayed block.** From Claude Fable 5.1, *"a block stays valid only while the top-level `system` prompt, the `tools`, and the messages before it are unchanged."* AIMON re-renders its system prompt every iteration (memory injection, skill hooks) and compacts client-side; both are prefix edits. Error: ``Invalid `signature` in `thinking` block. The block is bound to a different conversation.`` Enforced by default only for accounts created on or after 2026-08-31, and only on Fable 5.1 and later. | **This change is what makes the error reachable** — today the blocks are dropped, so there is nothing to invalidate. That is why `AnthropicConfig.replayThinkingBlocks(false)` exists, and it is precisely the vendor's own documented remedy (*"strip every `thinking` and `redacted_thinking` block from the history"*). Named in the config javadoc, in §9 U-5, and in the CHANGELOG. The `thinking-binding-controls-2026-08-01` beta header with `prefix_mismatch_behavior: "drop_block"` is the better long-term answer and is §8 F-5. |
| 8 | **A partially-dropped thinking sequence** — the shape the vendor says is rejected. | Two structural guards. `MessageStripper` drops **all** of a message's traces while keeping its tool uses, so it cannot produce a partial sequence; and the emit rule walks the stored list in order, so it cannot reorder one. The reverse mistake — keeping traces while dropping tool uses — is the thing to review for, and it is the same one the OpenAI design flagged. |
| 9 | **A streamed thinking block with no `signature_delta`** — a stream aborted mid-block, or a protocol change. | The slot is dropped with one warning rather than replayed unsigned. An unsigned thinking block is a guaranteed 400; a dropped one costs re-derived reasoning. Same trade as row 2. |
| 10 | **`output_tokens_details` moves, is renamed, or becomes a typed SDK accessor.** | `AnthropicUsages` returns `0` for anything it does not recognise and never throws. The counter is reported, not priced, so a zero under-reports one number in metering and fails nothing. When the SDK grows the accessor, one class changes. |
| 11 | **Thinking consumes the whole `max_tokens`** and the text never lands — `stop_reason: "max_tokens"`. | Already warned by `convertResponse` (`:432`); the neutral `StopReason` already reaches the executor. The budget clamp (row 5) makes it less likely by construction, since the budget can never equal `max_tokens`. |
| 12 | **Transcripts grow.** On the keep-all models (Opus 4.5+, Sonnet 4.6+, Fable/Mythos), prior turns' thinking stays in context and is billed as input. | Named as an operational cost, not engineered away — capping or truncating a signed block breaks the round trip, which is the feature. Compaction sheds them, which bounds a long session, and `replayThinkingBlocks(false)` removes them entirely. §9 U-6. |
| 13 | **Token estimation under-counts by roughly the thinking tokens.** `HeuristicTokenEstimator` and `TikTokenEstimator` count `0` for reasoning traces while `DefaultCompactionGuard` drives every threshold from the estimator. | **Deliberately not fixed**, identically to the OpenAI side, and for the same reason: counting the blob as text would over-count against the true input cost and force premature compaction, which destroys the traces. Blast radius is bounded — compaction drops the traces so the error resets. Recorded so the next person reading a context-length 400 has the sentence. |
| 14 | **Mid-turn thinking toggle** — an operator changes the config between a tool call and its result. | The API *"doesn't error… it silently disables thinking for that request"* and may strip blocks that would create an invalid turn structure. Nothing to do; noted so a reviewer does not build a guard against a case the server already handles. |
| 15 | **Extended-only models produce thinking before the first tool call but not between calls**, because the interleaved beta header is not sent. | Correct but incomplete, by choice (A10). The blocks that do arrive round-trip properly. §8 F-3. |

---

## 8. Follow-ups this round deliberately does not start

| # | Item | Why not now |
|---|---|---|
| **F-1** | **A per-model capability source for Anthropic** — which thinking dialect, whether sampling is accepted at all. | A1. It needs a sixth `ModelCapabilities` field with a fail-open story and an Anthropic table; #48 is in that package concurrently. |
| **F-2** | **The always-400 sampling regime on Sonnet 5 / Opus 5 / Opus 4.7 / 4.8 / Fable / Mythos** (§2.3 consequence 2) — `AnthropicLlmClient` cannot reach those models today, thinking or not. | Blocked on F-1: suppressing `temperature` for those and only those needs the model fact. Not caused by this change and not fixed by it; the CHANGELOG says so plainly rather than letting a reader infer that thinking support implies those models now work. |
| **F-3** | **`interleaved-thinking-2025-05-14`** for extended-mode models. | A10. Its own per-model matrix and its own `budget_tokens`/`max_tokens` interaction. |
| **F-4** | **Forwarding thinking text to `LlmStreamSink`** as a user-visible "thinking" stream. | A9. Needs a new `LlmStreamChunk.Kind` in `aimon-core`, and it is a different feature from carrying the item across turns — the exact split that made reasoning summaries F-5 on the OpenAI side. |
| **F-5** | **`thinking-binding-controls-2026-08-01`** with `prefix_mismatch_behavior: "drop_block"`. | The principled answer to row 7: it makes an invalidated prefix drop a block instead of failing a request, and reports what was dropped in `input_transformations`. A beta header plus a new config axis; it needs its own round. |
| **F-6** | **A yaml / starter property surface** for `thinkingMode`, `thinkingBudgetTokens` and `replayThinkingBlocks`. | Decision 1 below. Programmatic only this round, following #43's F-2 precedent — and #46 is editing `aimon-cli` and `aimon-spring-boot-starter` right now. |
| **F-7** | **`display: "summarized"` / `"updates"`.** | A11. Costs tokens for text nothing renders yet; pairs naturally with F-4. |

---

## 9. What has **not** been measured

**No Anthropic API call was made in this run — there is no key.** Everything below is the honest list of
what fixtures and unit tests cannot establish, kept in the form
`openai-model-capabilities.md` §11/§15 and `openai-responses-path.md` §8 use.

- **U-1 — the byte-exactness of a replayed `signature` has never been verified against the server.**
  This is the one property the whole feature rests on and the one property only a live call can
  demonstrate. What the tests *can* bind is the invariant we control: the decoded `signature` string
  that leaves this client is `equals()` to the one that arrived, and the surrounding object gains and
  loses no fields (asserted as a tree). What they cannot bind is whether Anthropic's verifier accepts
  the re-serialised object — key ordering and JSON string escaping may differ from the bytes the server
  sent, and the assumption that the verifier reads decoded values rather than raw bytes is exactly the
  assumption every official SDK makes when it echoes a block back, but it is an assumption.
  **Precision worth keeping:** the acceptance criterion is byte-exactness of the *signature*, not of the
  *document*. This design does not claim the latter and no implementation could.
- **U-2 — no request built here has ever been accepted or rejected by the API.** The mode/model matrix
  in §2.1, the two 400 messages, the `[0.95, 1.0]` `top_p` window and the `budget_tokens` bounds are all
  read from the vendor's documentation. They are recent, specific and internally consistent, which is
  the best available evidence and is not the same as a measurement.
- **U-3 — the capture rule's "text intervenes" clause is reasoned from documented ordering, not
  observed, and §3.2's table covers the documented shapes rather than all shapes.** It is built from
  what the vendor documents (thinking-then-text-then-tool_use; progress updates sitting *"immediately
  before the `tool_use` block"*). **Two shapes are reordered by the emit rule**, and both are losses
  `Message` makes unavoidable rather than ones this rule introduces:
  - `[think1, text1, think2, text2, tool_use]` — thinking interleaved with **more than one** text block,
    which adaptive-mode progress updates can produce. Both thinking blocks go unanchored and lead the
    message ahead of the single concatenated text, so two blocks the model emitted non-consecutively are
    replayed as consecutive — the shape the vendor's "cannot rearrange the sequence of consecutive
    thinking blocks" rule bumps against. **This is the likelier of the two to occur.**
  - `[think, tool_use, text]` — text *after* a tool_use. Not a documented shape.

  Neither is fixable at this layer: `Message` concatenates all text into one `getContent()` string, so
  **no** emit rule could reproduce a multi-text turn. The loss predates this change and is not widened
  by it; carrying text position through `Message` would be a core change of its own.
- **U-4 — `redacted_thinking` streaming has not been observed.** The design assumes the block arrives
  whole on `content_block_start`, since there is no redacted-thinking delta variant in
  `RawContentBlockDelta` (`text`, `input_json`, `citations`, `thinking`, `signature`). That is strong
  circumstantial evidence from the SDK's own union and it is not a measurement. If the block instead
  arrives empty and is filled some other way, the streaming path drops it under row 9 — a lost trace,
  not a failed turn.
- **U-5 — the preserved-thinking prefix check has not been triggered.** Row 7 is reasoned from the
  vendor's description of what invalidates a block plus knowledge of how AIMON rebuilds its system
  prompt. Nobody has run a Fable 5.1 session against a re-rendered prompt to see the 400. The off switch
  exists because the failure would be expensive to discover in production, not because it has been seen.
- **U-6 — transcript growth is stated, not budgeted.** Nobody has measured what a 40-turn thinking-enabled
  tool loop does to a session row on a keep-all model. The mitigations exist; the number does not.
  Same as the OpenAI side's U-5.
- **U-7 — the effort → budget ladder has not been calibrated against output quality.** §6.2 says which
  two rungs are the vendor's numbers and which two are not. Whether `LOW = 2048` buys anything over
  `MINIMAL = 1024` on real work is unmeasured, and the honest statement is that the ladder is
  *ordered and bounded* rather than *tuned*.
- **U-8 — `output_tokens_details.thinking_tokens` has never been seen on a real response.** The field
  name comes from the docs; the SDK does not model it (§2.6). The tests assert the untyped read against a
  hand-written JSON fixture, which proves the parser and not the field name. If the name is wrong the
  counter reads 0 and nothing else changes.
- **U-9 — no docker-backed session-store round trip was run.** As on the OpenAI side, the encoding is
  decided in `JsonSessionSnapshotCodec` and `SessionRecordCodec` — both in the gate — and the backends
  store the result as an opaque string, so they cannot see the field. Unchanged by this work, since the
  slot already exists.

---

## 10. Test strategy

Non-negotiables carried over from the sibling design, because they are the assertions that would
otherwise pass while the feature does nothing:

- **Absence is asserted on the serialised request body or the raw `_xxx()` accessor**, never on
  `xxx().isEmpty()` — the SDK's `getOptional` collapses `JsonMissing` and `JsonNull`, so an
  implementation that put `"temperature": null` on the wire and earned the exact 400 this design removes
  would still pass an `isEmpty()` assertion. `MessageCreateParams._body()` is public and serialises
  through the SDK mapper; that is the surface that cannot lie.
- **The reasoning payload is compared as a tree, never with `contains`.** A wrong mapper's corruption is
  an *addition*, so only a comparison that notices extra keys can fail on it.
- **Fixtures are deserialised from JSON through `ObjectMappers.jsonMapper()`, not built with builders.**
  `AnthropicFixtures` (new, test-scope) mirrors `ResponsesFixtures`: `contentBlock(json)`,
  `message(json)`, `event(json)`, `bodyOf(MessageCreateParams)`, `bodyTreeOf(...)`, and a recording
  `StreamResponse<RawMessageStreamEvent>` that counts closes. This matters more here than it did there:
  a Mockito-mocked `ThinkingBlock` — the style the existing `AnthropicLlmClientTest` uses — serialises
  to nothing, so a byte-exactness assertion over a mock would be vacuous.
- **Existing tests are not edited — with one named exception, and the exception is the trap.**
  `AnthropicMessageConverterTest`, `AnthropicLlmClientCancellationTest`,
  `AnthropicLlmClientRequestTimeoutTest`, `AnthropicLlmClientParameterDivergenceTest` (it builds the
  client with the real converter), `AnthropicStopReasonsTest`, `AnthropicExceptionMapperTest` and
  `AnthropicArchitectureTest` all stay green **unmodified**; editing them would hide exactly the
  regression they guard. `AnthropicConfigTest` is **extended** with cases for the three new setters, not
  edited. `AnthropicLlmClientIntegrationTest` stays gated on `ANTHROPIC_KEY` and is not run.

  **`AnthropicLlmClientTest` must be edited, in exactly one place.**
  `AnthropicLlmClientTest.java:466` (`shouldPropagateMessageConversionException`) stubs the *one-argument*
  `convertMessages(any())`. Once `buildRequest` calls the three-argument overload (§5.3(2), §5.4) that
  stub never fires, the mock returns `null`, `requestBuilder.messages(null)` throws an NPE that the
  generic `catch (Exception)` at `AnthropicLlmClient.java:203` wraps in `LlmClientException`, and the
  test's `isExactlyInstanceOf(MessageConversionException.class)` fails. **The fix is to migrate the stub
  to the three-argument overload**, which weakens no guarantee.

  IMPORTANT: **the wrong way out of that red build is to route `buildRequest` through the deprecated
  one-argument overload.** It compiles, it turns every listed test green, and it silently disables
  replay — the exact silent-no-op class this design's §3.3 landmine exists to prevent. If that stub is
  the only thing standing between the implementer and a green gate, the stub is what moves.

### 10.1 New test classes

| Class | Binds |
|---|---|
| `AnthropicOutputBlocksTest` | Every row of §3.2's table, and the two degenerate ones (`[think]` alone, `[]`). Pure, no SDK types. This is where the capture rule is actually pinned. |
| `AnthropicThinkingBudgetsTest` | Monotonicity across all five rungs; the 1024 floor; the clamp at `maxTokens - 1`; the give-up at `maxTokens <= 1024`; the `MEDIUM` default when no effort is set; explicit config budget winning over an effort. |
| `AnthropicReasoningTracesTest` | Round trip of a `thinking` block **and** a `redacted_thinking` block through `toTrace`/`toBlockParam`; `additionalProperties` survival; an unknown `"type"` surviving via the `_json` fallback; a foreign trace returning empty; an unparseable payload returning empty rather than throwing; **and the mapper test** — a plain `new ObjectMapper()` substituted for the SDK's makes it red. |
| `AnthropicReasoningRoundTripTest` | **The headline.** Drives `sendMessage` against a mocked `MessageService` returning a fixture response, reproduces the executor's attachment by hand, sends the next turn, and asserts on the serialised body: the block is back, in front of *its own* `tool_use`, and the decoded `signature` string is `equals()` to the fixture's. Plus: two blocks staying next to their own calls; an unanchored block leading the message ahead of the text; a redacted block surviving alongside a signed one; a foreign trace dropped with exactly one warning; an unparseable payload costing the trace and not the turn; and **a subclass overriding `getProviderName()` still replaying its own traces** — the landmine test. |
| `AnthropicThinkingRequestTest` | `thinkingMode = OFF` produces a body byte-identical to today's, temperature included. `EXTENDED` produces `thinking.type = "enabled"` with the clamped budget and **no `temperature` key at all**. `ADAPTIVE` produces `thinking.type = "adaptive"` and `output_config.effort`. `top_p` at `0.5` omitted, at `0.97` sent. `reasoningEffort = NONE` produces no `thinking` key in either mode. Each divergence warns exactly once across two sends. |
| `AnthropicStreamingReasoningTest` | A fixture stream `content_block_start(thinking)` → `thinking_delta`×2 → `signature_delta` → `content_block_stop` → `content_block_start(tool_use)` → `input_json_delta` → `content_block_stop` → `message_delta` → `message_stop` yields one anchored trace on the aggregated `LlmResponse`. A stream whose thinking block emits **no** `thinking_delta` (the `display: "omitted"` case) still yields a trace. A block with no `signature_delta` yields none, with one warning. Traces are flushed **before** `STREAM_END`, asserted by the absence of `IllegalStateException` and by `toLlmResponse()` carrying them. A `redacted_thinking` start with no deltas yields a trace. |
| `AnthropicUsageTest` | `thinking_tokens` read from a blocking `Usage` fixture and from a streaming `message_delta` fixture; `totalTokens == input + output` in both; a missing `output_tokens_details`, a non-object one, and a non-numeric `thinking_tokens` each yield `0` without throwing. |

### 10.2 Verified by mutation

Each of these bugs is to be introduced, the named test observed red, and the bug reverted — the same
discipline the sibling round used, because "the test exists" and "the test would have caught it" are
different claims:

| Mutation | Goes red |
|---|---|
| Resolve `getProviderName()` inside the converter instead of once per request | `AnthropicReasoningRoundTripTest`'s subclass case |
| Anchor a thinking block across an intervening text block (OpenAI's rule) | `AnthropicOutputBlocksTest`, and the ordering assertion in the round trip |
| Filter capture on `isThinking()` only, dropping `isRedactedThinking()` | the redacted cases in `AnthropicReasoningTracesTest` and the round trip |
| A plain `new ObjectMapper()` in `AnthropicReasoningTraces` | `AnthropicReasoningTracesTest`'s tree comparison |
| Keep calling `.temperature(...)` when `thinking` is present | `AnthropicThinkingRequestTest`'s body assertion |
| Drop the clamp, or clamp to `maxTokens` instead of `maxTokens - 1` | `AnthropicThinkingBudgetsTest` |
| Flush streamed traces after `emitStreamEnd` instead of before | `AnthropicStreamingReasoningTest` (an `IllegalStateException` escaping the mapper) |
| Add `thinkingTokens` into `totalTokens` | `AnthropicUsageTest` |
| Send `thinking` when `thinkingMode = OFF` | `AnthropicThinkingRequestTest`'s byte-identical body case |

### 10.3 What is not covered, and why that is part of the claim

- **U-1 above.** No test proves the server accepts a replayed signature. The tests prove we do not
  change it.
- **The `IllegalArgumentException("Unsupported role: …")` arm** stays untested, as it is today: `Role`
  has three constants and all three are handled.
- **The `thinkingMode` mismatch 400s** are not tested end to end, because reproducing them needs the
  server. `AnthropicExceptionMapperTest` already binds `400 → LlmInvalidRequestException`, which is the
  half we own.

---

## 11. The six defined decisions, answered

**1. How is thinking turned on, and does the config surface follow?**
`AnthropicConfig` gains **`thinkingMode`** (`OFF` default / `EXTENDED` / `ADAPTIVE`),
**`thinkingBudgetTokens`** (optional, `EXTENDED` only, `>= 1024`) and **`replayThinkingBlocks`**
(default `true`). A three-valued mode rather than a boolean because the two wire shapes are mutually
exclusive per model and picking the wrong one is a 400 (§2.1); a boolean would have to guess.
**The yaml and starter surfaces are deliberately not opened this round** — F-6, following #43's F-2
precedent that a config surface is its own issue rather than a rider on a behaviour fix, and reinforced
by RUN.md: #46 is editing `aimon-cli` and `aimon-spring-boot-starter` concurrently. The consequence is
stated rather than left implicit: **a CLI or starter deployment cannot turn thinking on until F-6
lands** — though it still gets the capture-and-replay half for free on the always-on models, because
that half is not configured (§3.1).

**2. `ReasoningEffort` → `budget_tokens`.**
§6.2 in full. The ladder is `MINIMAL 1024 / LOW 2048 / MEDIUM 4096 / HIGH 16000`, with `NONE` meaning
"send no `thinking` parameter". **`MINIMAL` and `HIGH` are the vendor's own published numbers** — the
documented floor and the documented complex-task starting point; **`LOW` and `MEDIUM` are arbitrary**,
chosen as doublings of the floor, and the document says so rather than dressing them up. What is not
arbitrary, and what the tests pin, is monotonicity (the enum's declaration order is documented as
load-bearing), the 1024 floor, the `maxTokens - 1` clamp, and the loud give-up when no legal budget
exists. In `ADAPTIVE` mode the mapping is ladder-to-ladder onto `output_config.effort` and is barely a
mapping at all; the one real loss is `MINIMAL` and `LOW` collapsing onto `low`.
Two corrections to the question's premises, neither of which changes the answer: `ReasoningEffort` has
**five** rungs, not three; and the axis mismatch only exists in `EXTENDED` mode, because the SDK gained
an effort ladder of its own.

**3. `temperature`.**
**The constraint is real, and it is verified from the vendor docs rather than guessed** (§2.3, quoted in
full): on thinking-capable older models, *"`temperature` and `top_k` are incompatible with thinking, and
`top_p` is allowed at values between 0.95 and 1"*. The SDK javadoc says nothing about it, which is why
guessing would have been wrong in both directions. Handling: when the request carries a `thinking`
parameter, `temperature`'s setter is **not called** and `top_p` is sent only inside `[0.95, 1.0]`; each
omission goes through **`reportDivergence`** (`AnthropicLlmClient.java:317`/`:379`) — nothing is silently
substituted, exactly as the task required. `top_k` needs no handling: `LlmModel` has no such field.
Separately and importantly: the same paragraph shows that on Sonnet 5, Opus 5, Opus 4.7/4.8 and the
Fable/Mythos family, *any* non-default temperature 400s on **every** request — so today's unconditional
`.temperature(0.0)` at `:325` already blocks those models. That is a pre-existing bug this design
**surfaces but does not fix** (F-2), and the CHANGELOG will say so rather than let a reader infer
otherwise.

**4. Which models think?**
**`ModelCapabilityRegistry` is not extended** (A1). The operator names the mode; nothing in the request
builder branches on a model-name string, preserving #43's round-1 acceptance criterion 4. Two reasons,
the second decisive: scope and concurrency; and the fact that today's
`ModelCapabilities` has no field that could carry a two-valued mutually-exclusive dialect axis, so
"extend the registry" is a second design round wearing a small name. The cost is honest — an operator
who names the wrong mode gets a 400 — and it is mitigated by quoting the two exact server messages in
`AnthropicThinkingMode`'s javadoc, by the failure being non-retryable and therefore loud, and by the
default being `OFF`. The asymmetry that makes this affordable: on the seven always-on models **no
configuration is needed at all**, because capture and replay are unconditional.

**5. `redacted_thinking`.**
Same slot, same anchor, same replay rule, **no core-level discriminator** — each block's own JSON carries
`"type"` and `ContentBlockParam`'s deserializer dispatches on it (§2.7), which is what the phase-2
design predicted. Three differences the implementation must respect: it carries `data` instead of
`signature`/`thinking`; it appears to arrive whole on `content_block_start` with no deltas (U-4); and the
vendor names our exact hazard — *"Filtering on `block.type == "thinking"` alone silently drops
`redacted_thinking` blocks and breaks the multi-turn protocol"* — so the capture predicate is
`isThinking() || isRedactedThinking()` and a dedicated test pins the redacted case on both the blocking
and the streaming path.

**6. `TokenUsage.reasoningTokens`.**
**Anthropic reports a counterpart: `usage.output_tokens_details.thinking_tokens`** (documented), and on
the streaming path it appears *"only on the final `message_delta` event"*. **The vendored SDK 2.13.0 does
not model it** — neither `Usage` nor `MessageDeltaUsage` has the accessor, and the string appears nowhere
in the sources jar. So it is read untyped from `_additionalProperties()` in one place (`AnthropicUsages`),
degrading to `0` for anything unrecognised, and it becomes a one-class change when the SDK catches up.
It is reported and **not** priced and **not** added to `totalTokens`, because thinking tokens *"are
billed as output tokens"* and are therefore contained in `output_tokens` — the same containment
`TokenUsage`'s javadoc already documents for OpenAI. The field name itself is unverified against a live
response (U-8); if it is wrong the counter reads zero and nothing else changes.

---

## 12. Open questions — things the task statement does not settle

Listed rather than silently assumed. None blocks implementation; each is a judgement a reviewer may
want to overturn.

- **Q-1 — is `replayThinkingBlocks` defaulting to `true` the right call?** It is what makes the feature
  work with no configuration on the always-on models, and it is what makes failure mode 7 reachable.
  Defaulting to `false` would be strictly safer and would leave the feature inert for most users unless
  they also read a design document. The argument for `true`: the risk is triple-gated (Fable 5.1+,
  accounts created on or after 2026-08-31, an actually-changed prefix) and produces a precise error
  message that names the remedy, while the cost of `false` is a feature nobody turns on.
  **And the strongest argument is one the risk framing hides: today the default cannot change behaviour
  for anybody.** By §2.3 consequence 2, every always-on model — Sonnet 5, Opus 5, the Fable/Mythos
  family — already 400s on this client's unconditional `.temperature(0.0)` at
  `AnthropicLlmClient.java:325`, so no deployment is talking to those models at all. Replay has nothing
  to act on until F-2 lands, which makes `true` a decision that can be revisited with real traffic
  rather than one that ships unobserved.
- **Q-2 — should `EXTENDED` + `reasoningEffort = NONE` mean "omit `thinking`" or "send
  `thinking: {type: "disabled"}"`?** This design omits, uniformly across both modes, because `disabled`
  is rejected by five models and omission cannot 400. The cost is that on always-on models `NONE` does
  not disable anything. A defensible alternative is to send `disabled` in `ADAPTIVE` mode only and let
  the 400 teach the operator, but that trades a silent under-delivery for a loud failure on a path the
  caller may not have intended to exercise.
  **A second cost belongs here too, and it is the sharper one.** `ADAPTIVE` + `NONE` sends no `thinking`
  parameter, so §3.5's sampling omission never fires and the request goes out carrying `temperature` —
  which on every adaptive-capable model is a 400 (§2.3). An operator who configured the feature and then
  suppressed it per call gets a *sampling* rejection rather than the quiet non-thinking turn they asked
  for. It is F-2 territory and the escape is trivial (leave the effort unset), but it is worth naming,
  because §3.5 exists precisely so a wrong sampling regime is reported rather than discovered.
- **Q-3 — does `DefaultCompactionEngine` ever rewrite the *latest* assistant message while its tool
  uses are still pending?** Failure mode 8 relies on compaction never producing a partial thinking
  sequence in the position the vendor's check inspects. `MessageStripper` drops all traces per message,
  which is sufficient, but the interaction with a mid-turn compaction was not traced end to end in this
  round. If it can happen, the guard is still correct (all-or-nothing), so this is a confidence question
  rather than a correctness one.
- **Q-4 — should `AnthropicMessageConverter.convertMessages(List<Message>)` be deprecated or removed?**
  This design deprecates and delegates. `0.x` permits removal with a CHANGELOG note, and a deprecated
  method that silently cannot replay is arguably worse than a compile error that tells the caller to
  pass a provider name. Three lines either way.
- **Q-5 — is `HIGH = 16000` too large for the default `maxTokens = 4096`?** It clamps to 4095 and warns,
  which is correct and also means the top rung is unreachable out of the box. An alternative is to raise
  `AnthropicConfig`'s default `maxTokens` when a thinking mode is selected — quieter, but it changes a
  documented default as a side effect of an unrelated setting, which this design declines to do.
- **Q-6 — the design doc's eventual filename.** `docs/design/llm/anthropic-thinking-traces.md` is
  proposed. `anthropic-extended-thinking.md` would be wrong now that "extended thinking" names one of two
  modes; `anthropic-thinking-path.md` would echo `openai-responses-path.md` but this is not a second
  endpoint. Naming it after the slot it fills seems right and is cheap to change.

---

## 13. Where the implementation departed from this design

Four departures. Three resolve a tension inside this document's own file-by-file plan; one closes a hole §7
row 2 claims is already closed. None reverses a decision.

### 13.1 `AnthropicReasoningTraces` exposes `payloadOf(...)`, not `toTrace(...)`

§5.1 gives the class `toTrace(ContentBlock, String providerName, String toolUseId)`. It shipped as
`payloadOf(ThinkingBlock)` / `payloadOf(RedactedThinkingBlock)` instead, with `ReasoningTrace` construction
inside `AnthropicOutputBlocks.resolve(List<Block>, String providerName)`.

**The two §5.1 rows could not both hold.** `AnthropicOutputBlocks` is specified as pure, free of SDK types in
its signature, and as returning `List<ReasoningTrace>` — so it builds the traces, so it already holds the
payload, so `toTrace(ContentBlock, …)` has no caller left. Splitting *serialise a block* (SDK-typed) from
*build the trace and its anchor* (SDK-free) satisfies both. Serialisation and parsing still happen in exactly
one class, which is what §5.1 was protecting, and `ReasoningTrace` lives in `at.aimon.core.llm`, so the purity
claim is intact.

### 13.2 A payload that is not a JSON object is rejected

§7 row 2 says an unparseable payload is dropped. It needed one more line to be true. `ContentBlockParam`'s
deserializer accepts *anything* and keeps what it does not recognise as raw JSON — the forward compatibility
§2.7 relies on — which also means a corrupted payload of `[1,2,3]` parses fine and would go straight back onto
the wire as an array. `toBlockParam` now checks `isObject()` first. A content block is always a JSON object, so
this rejects nothing §2.7 promises: an unknown *object* type still round-trips through the `_json` fallback, and
`AnthropicReasoningTracesTest` pins both halves.

### 13.3 `replayThinkingBlocks` is applied in the client, not passed into the converter

§3.1 gates the emit half on the config flag; §5.4 fixes the converter's signature at three arguments.
`buildRequest` strips the traces before converting rather than passing a fourth argument:

```java
final List<Message> outbound = config.isReplayThinkingBlocks() ? messages
        : AnthropicMessageConverter.withoutReasoningTraces(messages);
```

A fourth argument would have changed the very signature §10's non-negotiable tells the implementer to migrate
the `AnthropicLlmClientTest` stub onto. Stripping up front also leaves the converter one rule — *replay what you
are given* — and makes `replayThinkingBlocks(false)` produce a body identical to the pre-thinking one rather
than one the emit rule assembled and then emptied. The deprecated one-argument overload delegates through the
same helper, so there is one strip and one emit rule rather than two of either.

### 13.4 `AnthropicThinkingBudgets` also exposes `requestedBudget(...)`

Purely additive. §6.2 requires the clamp to be *reported*, and a caller can only detect a clamp by comparing the
resolved budget against the one asked for. Exposing the un-clamped value keeps the ladder pure rather than
returning a pair or reporting from inside a class this document specifies as pure.

### 13.5 Things that could look like departures and are not

- **`@Deprecated(since = "0.2.5")` carries no `forRemoval`.** §5.4 says `@Deprecated`; scheduling removal is
  what Q-4 leaves open, and the flag would have turned 15 untouched call sites in
  `AnthropicMessageConverterTest` into on-by-default build warnings.
- **`AnthropicOutputBlocks.Block` uses named factories rather than a builder.** It is a three-variant
  discriminated union, and a builder would permit a `THINKING` block carrying a tool use id — a state the rule
  has no meaning for. Immutable final class, final fields, private constructor, per
  `.claude/rules/immutability-pattern.md`.
- **A redacted block is parked at `content_block_start` and appended at `content_block_stop`,** like every other
  kind, even though §5.5 correctly notes it is complete on arrival. One place appends to the ordered list, so
  block order cannot depend on which kinds a stream happens to contain.
- **`AnthropicUsages` reads through `Object` and `instanceof` rather than casting.** `JsonValue` reaches Java as
  a raw subtype of `JsonField`, so `asObject()` and `asNumber()` return raw `Optional`s; a cast would be
  unchecked and the pattern match performs the same test honestly. The behaviour is §3.6's.

### 13.6 What the gate actually reported

`./gradlew format` then `./gradlew checkAll`, offline. `aimon-llm-anthropic`: **208 tests, 0 failures, 13
skipped** — the 13 are `AnthropicLlmClientIntegrationTest`, which is gated on `ANTHROPIC_KEY` and did not run.
Seven test classes are new (`AnthropicOutputBlocksTest`, `AnthropicThinkingBudgetsTest`,
`AnthropicReasoningTracesTest`, `AnthropicReasoningRoundTripTest`, `AnthropicThinkingRequestTest`,
`AnthropicStreamingReasoningTest`, `AnthropicUsageTest`); `AnthropicConfigTest` gained seven cases; and exactly
one existing test changed — the mock-signature migration §10 names.
