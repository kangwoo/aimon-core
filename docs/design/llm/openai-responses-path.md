# Design — the Responses API path (#43 phase 2)

> Status: **IMPLEMENTED.** This is the record of what landed, written against the approved design of
> round 4 and corrected where the code disagreed with it (§9 lists every departure). It is the
> sequel to [`openai-model-capabilities.md`](openai-model-capabilities.md), whose §10 records which
> of that document's open questions this round closed.
>
> Branch: `fix/openai-model-capabilities`. Gate: `./gradlew checkAll`.
>
> **No live API call was made**, in this round or any previous one. Every claim about the *SDK* was
> read out of `openai-java-core-4.52.0-sources.jar` or run against it; every claim about what the
> *server* accepts comes from the issue body and the SDK's javadoc, and is labelled where it
> matters. §8 is the complete list of what that leaves unverified.
>
> **That last paragraph describes the round this document records, and it is no longer the whole
> story.** A later round ran **#43's** own reproduction and every one of its six work items against
> the live API on 2026-09-10. §10 is that record, and it names which of §8's U-items it discharges
> and which it leaves standing. (That round also closed #71, which is about the reasoning *stream* on
> both providers — a different list, recorded in
> [`reasoning-delta-stream.md`](reasoning-delta-stream.md) §12.4.)

---

## 1. The problem, in one paragraph

Phase 1 made `gpt-5.x` usable with tools by sending `reasoning_effort: none`, which buys a working
request by turning off the thing the model was chosen for. It also left a quieter cost in place:
Chat Completions never returns reasoning items, so nothing carries across a tool call and the model
re-derives its chain of thought on every ReAct iteration — worse answers, and reasoning tokens
billed again each time, on exactly the multi-turn tool loops AIMON exists to run. The endpoint that
fixes this is `/v1/responses`, and reaching it needs six things that did not exist: a
`ResponseCreateParams` counterpart to the request builder; **somewhere to keep a reasoning item
between turns**, which `Message` did not have and which lands in the persisted transcript when it is
added; a second streaming mapper, because `OpenAIStreamingMapper` consumes `ChatCompletionChunk`
while Responses emits `ResponseStreamEvent`; a fourth `TokenUsage` field, because Responses reports
`reasoning_tokens` and three fields silently drop them; a stop-reason mapping, because
`OpenAiStopReasons` knows only the Chat vocabulary; and `call_id` handling, because
`OpenAIMessageConverter` writes `id`.

The risk was never any one of the six. It is that a Responses path is close to a from-scratch
client, and the four non-obvious behaviours `OpenAILlmClient` took real work to get right get
re-implemented slightly wrong in the copy. Most of the structure below exists to make copying them
impossible rather than merely discouraged.

---

## 2. The three decisions that carry beyond this round

### 2.1 The reasoning slot — a new ordered `List<ReasoningTrace>` on `Message`

**`MessageArtifact` was examined first and rejected**, for four reasons in descending order of how
fatal they are:

| # | Why the existing sidecar cannot carry a reasoning payload |
|---|---|
| 1 | **Its two required fields are lies for this content.** `path` and `fileName` are both `requireNonBlank`. A reasoning item has no filesystem path and no file name; storing `"/dev/null"` to satisfy a validator is how a type stops meaning anything. |
| 2 | **It is already consumed as a file reference.** It implements `ArtifactMetadata`, carries a `downloadToken`, and the CLI and session layers render it to a human as a downloadable file. A reasoning blob in that list would be offered to a user as a file. |
| 3 | **`size` is a byte count with a `>= 0` invariant**, and there is nothing to put in it. |
| 4 | Order and anchoring *could* be expressed there. Listed for completeness; rows 1–3 carry the rejection on their own. |

The nearer precedent argues the other way. `ToolUseResult.getRenderPayload()` is a deliberately
opaque, provider-shaped sidecar — and the snapshot codec's contract says in as many words that it is
**never persisted**. Reasoning traces need exactly the inverse guarantee: opaque *and* persisted.
That is a new slot, not a reuse.

```java
public final class ReasoningTrace {        // immutable class + builder
    String providerName();                 // required, non-blank — LlmClient.getProviderName()
    String payload();                      // required, non-blank — provider-owned opaque text
    Optional<String> toolUseId();          // optional — the tool use this trace precedes
}
```

Each field earns its place.

- **`payload` is a `String`**, not `byte[]` or `Map`. It survives every wire this repo already has,
  is inspectable by an operator reading a transcript, and forces the provider to own its encoding.
  `aimon-core` performs exactly one operation on it: copy.
- **`providerName` exists because a transcript outlives a client.** `LlmFallbackPolicy` can move a
  session onto another model mid-run, an operator can change the configured provider and resume, and
  a subagent snapshot can be replayed anywhere. Feeding an Anthropic thinking block to
  `/v1/responses` is a 400 at best, so a client replays only its own traces and drops the rest.
- **`toolUseId` is the anchor, and it is not provider leakage** — `MessageArtifact.getToolUseId()`
  already means "the tool use this thing belongs to". Here it means *this trace immediately precedes
  the tool use with this id in the provider's own output order*; empty means it precedes the
  assistant's text or the end of the turn.

#### Why an ordered list is not enough on its own

A turn whose output is `[reasoning, call_1, reasoning, call_2]` must be replayed with each reasoning
item in front of the call it produced. `Message` cannot express that: its text lives in
`contentBlocks` and its calls live in `toolUses`, two separate lists. So the rule is written down
once, in the provider, and driven by the anchor:

```
emit, for one ASSISTANT message:
  1. every trace with no anchor, in stored order
  2. the assistant text item, when there is text
  3. for each tool use in order:  its anchored traces (stored order), then the tool call itself
```

and the capture rule is its mirror: walking the provider's output array in order, each reasoning item
anchors to the **first tool call that follows it**, or to nothing if none does. `[r1, call_1, r2,
call_2]` round-trips exactly; `[r1, msg]` and `[r1, r2, call_1]` both degenerate correctly.

This is the one place the design defends against a server rule it could not test (§8, U-1). The
anchor costs one nullable field and about ten lines; not having it costs a 400 on multi-tool-call
turns that no unit test can discover.

#### It lands in persisted state, and both directions are tolerant

`Message` → `JsonSessionSnapshotCodec` → `SessionRecordCodec.encodeTranscript` → the Mongo /
Postgres / Redis transcript column. Widening `Message` widens a stored wire format, which is the
boundary [`frozen-names.md`](../../migration/frozen-names.md) guards. That document is about
*renaming* stored names; a new optional field is additive and is not on its list. What it does
demand is that the additivity be real in both directions:

| Direction | Why it holds |
|---|---|
| **Old reader, new document** | `decodeMessage` reads five field names explicitly and ignores everything else. A `"reasoning"` array it has never heard of is skipped. Same forward tolerance `compactionFailureCount` already relies on. |
| **New reader, old document** | The key is absent → empty list → the five-argument `restore`'s behaviour, unchanged. |

`FORMAT_VERSION` **stays at 1**, on the codec's own existing argument: bumping it would make every
stored snapshot undecodable to buy nothing, since both directions above are tolerant. The array is
written **only when non-empty**, mirroring `toolUses`/`artifacts`, so every document produced before
this field existed is byte-identical to one produced now.

**The three `SessionRecordCodec` backends need no change at all**, and that is a property of a
decision already made rather than luck: `encodeTranscript` hands the backend an **opaque string**
(BSON forbids `.` and `$` in field names; `jsonb` forbids U+0000), and every backend stores it in a
text column. A backend cannot see the new field, so it cannot mis-handle it.

#### The slot fits Anthropic — demonstrated, not asserted

Anthropic's shape is the harder one and the one to design against, because a thinking block is only
accepted back if its `signature` returns **byte-exact**.

| | Returned by the API | Sent back as | Fields |
|---|---|---|---|
| thinking | `ThinkingBlock` | `ThinkingBlockParam` (there is a `toParam()`) | `thinking`, `signature`, `type` |
| redacted | `RedactedThinkingBlock` | `RedactedThinkingBlockParam` (likewise) | `data`, `type` |

- **Opacity** — the client serialises the block to its own JSON and puts the string in `payload`.
  Nothing in core reads it, so `signature` is never re-encoded, re-escaped or normalised by core.
  Byte-exactness stays the provider's to keep, which is the only place it *can* be kept.
- **Ordering** — Anthropic requires thinking blocks to lead the assistant content on a tool-using
  turn, and a turn can carry several interleaved with tool_use blocks. The **anchor** transfers
  exactly (`toolUseId` is `ToolUseBlock.id()`, the same neutral id `ToolUse.getId()` already holds).
  The **emit rule does not**, and that is the one place the fit is a fit of the data rather than of
  the algorithm: OpenAI wants unanchored reasoning, then the message, then anchored reasoning before
  each call, whereas Anthropic wants every thinking block first. That is why the reconstruction rule
  is written down *in the provider* — one rule per provider, over one shared shape.
- **Two kinds in one slot** — `thinking` vs `redacted_thinking` needs no core-level discriminator,
  because each block's own JSON carries `"type"`. That is what opacity buys.

**The Anthropic implementation is a follow-up and was not started** (§7, F-1). `AnthropicLlmClient`
keeps its `// Ignore other block types` comment and `AnthropicStreamingMapper` keeps its "not yet
surfaced" note; neither file was opened.

#### What OpenAI puts in `payload`, and the one trap measured

The payload is the reasoning item's own JSON, produced and consumed by
**`com.openai.core.ObjectMappers.jsonMapper()`** — the SDK's mapper, not a fresh `ObjectMapper`.
That is not a style preference. Every SDK model carries a `@JsonAnySetter`/`@JsonAnyGetter` pair, so
the SDK mapper round-trips losslessly *including a field this SDK version has never heard of* — which
is what lets a payload stored by one build be replayed by another after the server adds a field. A
plain `new ObjectMapper()` parses the same bytes and re-emits them with two invented fields
(`"valid":true` from `isValid()`, `"content":null` from an `Optional` accessor lacking the SDK's
`NON_ABSENT` inclusion); sending that back is a corrupted item. `OpenAIMessageConverter` already
holds a plain `ObjectMapper`, so writing the wrong one here is the obvious mistake — three tests fail
on it (§6).

A second instruction follows from the same opacity: **a stored payload is never rebuilt through
`ResponseReasoningItem.builder()`.** That builder requires `id` and `summary`; the `@JsonCreator`
constructor does not, because its `JsonField`s default to missing. Deserialising therefore bypasses
`checkRequired` entirely and no required accessor ever fires. Rebuilding would introduce validation
failures on payloads the *server itself* produced, converting "drop one trace" into a thrown
exception on a shape we have no business validating.

### 2.2 One client, two endpoint exchanges — not a sibling `LlmClient`

**Decision: `OpenAILlmClient` stays the single `LlmClient` and branches internally, but the branch
is an injected collaborator — a package-private `OpenAIEndpointExchange` with two implementations —
not an `if` in the middle of a method.**

Issue #43 sketches `public class OpenAIResponsesLlmClient implements LlmClient` and leaves the choice
open. A sibling loses on two counts, and the second is decisive:

1. **A sibling cannot be selected per model, which is the requirement.** `LlmClient` methods take an
   `LlmModel` that may override the model name per request, so the endpoint decision is per
   *request*, not per client. Choosing a sibling at construction time gets the wrong endpoint the
   moment an agent overrides its model — and a `gpt-4o` compaction call inside a `gpt-5.6` session is
   the ordinary case, not an exotic one. Making it work needs a *third* type owning both siblings and
   dispatching, at which point the "sibling" is an implementation detail of a facade and we have the
   same structure with one more class and one more construction site for `LlmClientFactory` and
   `AimonLlmAutoConfiguration` to learn.
2. **A sibling duplicates the four preserved behaviours, which is the exact regression the issue
   names.** The cancellation fast path, `onCancel(...)` registered *after* the stream opens, the
   three-way catch that reclassifies a cancelled stream, the `SseException` routing, and
   `perRequestOptions` returning `null` — none of that varies by endpoint. Copying it into a second
   class means two places to keep right and one place the existing tests do not look.

What *does* vary is exactly four things: how the params are built, how the blocking call is made, how
the stream is opened and consumed, and how the result becomes an `LlmResponse`. So that is the seam:

```java
interface OpenAIEndpointExchange {
    LlmResponse callBlocking(RequestOptions options);                     // null -> single-arg overload
    OpenAIStreamHandle openStream(RequestOptions options, LlmStreamSink sink, ChunkAggregator aggregator);
}

interface OpenAIStreamHandle extends AutoCloseable {   // erases StreamResponse<T>'s generic
    void consume();
    @Override void close();                            // delegates to StreamResponse.close()
}
```

`OpenAIStreamHandle` exists for one reason: `StreamResponse<ChatCompletionChunk>` and
`StreamResponse<ResponseStreamEvent>` have no useful common supertype, and erasing the generic behind
two methods is what lets the client keep **one** try-with-resources, **one** `onCancel` registration
and **one** catch cascade. It has no `toLlmResponse()`: the aggregator is owned by the client, which
still ends with its own `return aggregator.toLlmResponse()` outside the try.

**Where the exchange is constructed matters and did not move.** `buildRequest(...)` ran *before* the
try-with-resources, so a failure while building params escaped unmapped. The exchange — and
therefore the params — is built at the same point, so that behaviour is byte-identical rather than
accidentally improved or accidentally worsened. And **neither exchange catches anything**, so every
failure still lands in the client's cascade and therefore still goes through `OpenAIExceptionMapper`.

Selection stays on the capability lookup phase 1 already does:

```java
final String modelName = modelConfig.getName().orElse(config.getModel());
final ModelCapabilities capabilities = capabilitiesFor(modelName);      // unchanged, fail-open, guarded
if (capabilities.supportsReasoningTraceRoundTrip() && config.isResponsesApiEnabled()) { ... }
```

There is still no model-name string anywhere in this decision, which was round 1's acceptance
criterion 4 and stays true.

### 2.3 `reasoningTokens` is reported **alongside** cost, never added to it

**Decision: `TokenUsage` gains a fourth field; `ModelPrice.costOf(...)` is not touched.**

The reason is arithmetic, and it was measured rather than assumed. `reasoning_tokens` lives **inside**
`output_tokens_details`: on a `{input:100, output:50, total:150, reasoning:30}` document, 30 of the
50 output tokens were reasoning and `total = input + output` still holds. They are already billed as
output tokens. Adding them to `costOf` would bill them twice; adding them to `totalTokens` would
break what that field means.

| Consumer | Change |
|---|---|
| `ModelPrice.costOf` | **none.** Its javadoc gains one paragraph saying so, because "we deliberately ignore a field" is exactly what a reader will otherwise take for a bug. A test pins it. |
| `TablePricedCostEstimator`, `CostSummary`, `ModelUsage` | **none.** They compose `costOf`. |
| `MeteringLlmClient` / `LlmUsageRecorder`, `TracingLlmClient` / `DefaultTracer` | **none in signature** — they pass the whole `TokenUsage` through, so the new field arrives at every recorder for free. That is the "reported alongside" half. |
| `LoggingLlmClient` | one extra field in the log line. |
| `SessionRecordCodec.encodeTotals` / `decodeTotals` | **carries it**, additively. `decodeTotals` already reads with `node.path(...).asInt()`, which is 0 for a missing field. |
| `AgentExecutionEventPayload.tokensToMap/FromMap`, `StatusSnapshotPayload` (twice) | **carries it**, additively — and this one has a rolling-upgrade trap, §5 row 6. |

**Source-breaking? No.** `TokenUsage.of(int,int,int)` is retained and yields `reasoningTokens = 0`; a
four-argument overload is added; the constructor is private. **Behaviour-visible? Yes**, in
`equals`/`hashCode`/`toString` — a usage carrying reasoning tokens is no longer equal to one without
— and the CHANGELOG says that plainly rather than only saying "additive".

**Validation: `>= 0` only.** No `reasoningTokens <= completionTokens` invariant, even though every
provider integrated so far reports containment. The value is filled by the *server*, and a new
throwing cross-field check on it would turn an accounting surprise into a failed LLM call.

---

## 3. Where the traces attach — all eight sites, generated rather than recalled

The rule is literal: **every place that builds an assistant `Message` out of an `LlmResponse`
attaches that response's traces, with no per-site judgement about whether the message will be read
back.** A rule with an exception is a rule nobody can check with one grep.

The list is the intersection of two greps over `modules/*/src/main/java`
(`Message\.assistant(\|addAssistantMessage(` and `getTextContent()`), and there are **eight** sites in
**four** files — the four ReAct loops the tree has.

| # | Site | Branch | Is that message read back? |
|---|---|---|---|
| 1 | `OrcaAgentExecutor` | terminal, truncated (`flaggedAnswer`) | **yes** — persisted in the `SessionRecord`; the next user turn replays it |
| 2 | `OrcaAgentExecutor` | terminal, clean | **yes** — same |
| 3 | `OrcaAgentExecutor` | tool-use iteration | **yes** — the next iteration's `sendMessage` |
| 4 | `DefaultSubagentExecutor` | terminal (no tool uses) | **yes** — the result snapshot a resumed fork rebuilds from |
| 5 | `DefaultSubagentExecutor` | tool-use iteration | **yes** — the next iteration's `sendMessage` |
| 6 | `LlmSkillExecutor` | tool-use iteration | **yes** — the buffer is re-sent |
| 7 | `LlmSkillExecutor` | terminal | **no** — the buffer is local and is dropped at the `return` |
| 8 | `ReActLlmDeriver` | tool-use iteration | **yes** — the conversation is re-sent |

**Site 7 is attached even though nothing reads it, and that is the point of the rule.** Excluding it
would cost nothing today and would replace a rule a reviewer can check with one grep by a rule with
one footnote.

**The six that look like sites and are not**, each excluded for a reason that can be checked:

| Not a site | Why |
|---|---|
| `OrcaAgentExecutor`'s two slash-command paths | the text is a command's output; no `LlmResponse` exists |
| `OrcaAgentExecutor`'s two mid-stream cancellation paths | the call was aborted, so `ChunkAggregator.toLlmResponse()` is never reached and **no `LlmResponse` is ever produced**. Right rather than merely convenient: a half-streamed reasoning item has no completed `encrypted_content` |
| `MessageStripper.rebuild` | **deliberately drops traces** while keeping tool uses — §5 row 3, pinned by a test |
| `DefaultCompactionEngine`'s summary | `CompactBoundary.summaryMessage(...)` returns a **user** message — a new message *about* the history, not the message a turn became, and not even the same role |
| `TranscriptBuffer.addAssistantMessage` | a published convenience over a `String`; site 7 is its one caller that passes response text |
| `LlmClient.sendMessageStreaming`'s default | it returns the very `LlmResponse` that `sendMessage` produced, so traces ride through untouched |

**Why this is tested at the executors and not only at the provider.** The provider-side round-trip
test drives `sendMessage` directly and then *reproduces* the executor's attachment by hand — which
makes it green for every possible behaviour of every executor, including one that attaches nothing.
It binds the provider, which is what it is for; it cannot bind a caller. Seven core-side tests over
the four loops close that, each failing on exactly one dropped site, with no OpenAI type in any of
them. Site 7 has no test and saying so is part of the claim: it writes into a buffer discarded at the
next statement, so there is nothing an assertion can observe.

---

## 4. Request building, conversion, and the required fields that are choices

### 4.1 The mapping

| Chat Completions | Responses |
|---|---|
| `messages` (system message first) | `instructions` = the system prompt; `input` = the converted item list |
| `maxCompletionTokens` | `maxOutputTokens` |
| `reasoningEffort(...)` (top level) | `reasoning(Reasoning.builder().effort(...))` |
| `tools(List<ChatCompletionTool>)` | `tools(List<Tool>)` via `Tool.ofFunction(FunctionTool...)` — §4.3 |
| `streamOptions.includeUsage` | **no counterpart** — that endpoint's `stream_options` carries only `includeObfuscation`, because usage is not opt-in there (§8, U-4) |
| `temperature` / `top_p` | same two — and **no presence or frequency penalty at all** (§4.4) |
| — | `store(false)` + `include(REASONING_ENCRYPTED_CONTENT)` |

**Omission is "never call the setter", on this endpoint too.** `temperature(Optional.empty())` and
`topP((Double) null)` both route through `JsonField.ofNullable` and put `"temperature": null` on the
wire, and a model that rejects the parameter by *presence* rejects the null form exactly like the
value form. The rule is implemented through a shared helper so the two endpoints cannot drift, with
the client's own `reportDivergence` passed in as a callback — that method's once-per-signature dedup
set lives in the client and would not survive being moved.

**`store(false)` is a decision, not a default.** With `store: true` the server retains the exchange
and offers `previous_response_id` as an alternative to replaying items — a second source of truth no
`SessionRecord` knows about, in a system that resumes sessions on other nodes, plus a data-retention
change nobody asked for arriving as a rider on a bug fix. `include(REASONING_ENCRYPTED_CONTENT)` is
requested explicitly because `store: false` is the case the SDK's javadoc singles out for it.

**The tools clamp is gone on this path; the ladder check is not.** Omitting the effort because tools
are present is a Chat Completions rule. Here tools and reasoning coexist — that is the entire point of
phase 2 — so the configured effort goes as asked and an unconfigured request gets the server's
default, which is now the desirable one.

What does **not** go away is `OpenAiRequestParameters.maySendEffort`: which rungs a model accepts is a
fact about the model, not about the endpoint, and no OpenAI model measured to date except
`gpt-5.6-terra` has a `none` rung on either surface. Dropping that check along with the clamp is how
`reasoning.effort: "none"` reached this endpoint as a 400 — see
[`openai-model-capabilities.md`](openai-model-capabilities.md) §12, and §13.3 for the one model that
does have the rung.

### 4.2 Message conversion is parity with the Chat converter, case for case

`OpenAIMessageConverter` returns Chat types throughout, so a second conversion had to be written from
scratch; keeping the Chat one **unopened** is what makes "Chat Completions is unchanged" structural
rather than promised. The standard the new converter is held to is: *switching endpoints never
changes what a message means*, including every throw.

**By role:** `USER` → one `ofMessage(role=USER, …)` carrying the content list (the multimodal
carrier is used for the text-only case too, so there is no second shape to keep in sync);
`ASSISTANT` → the emit rule of §2.1, with the text item carried by **`EasyInputMessage`** because
`ResponseInputItem.Message.Role` declares only `USER`/`SYSTEM`/`DEVELOPER` and the remaining
candidate requires a server-assigned `id` and `status`; `TOOL` → **one `function_call_output` per
result**, same order, `call_id = getToolUseId()`, and the **same `"Error: "` prefix** a failed result
gets on Chat; anything else → the same `IllegalArgumentException`, same message.

**By content block:** text → `input_text`; base64 image → `input_image` carrying the **same `data:`
URL** the Chat converter builds; URL image → likewise; text-based document → inlined as `input_text`
with the `[File: …]` header, **and without it when the document has no file name** (the Chat
converter guards the prefix, so a converter that always writes the header passes a test that only
ever supplies a name); non-text document → **the same `MessageConversionException`, same message**;
unknown block → same throw.

The non-text-document throw is the deliberate one. The Responses API *does* have a native
`input_file` slot, so it could carry a PDF where Chat cannot. Taking it would make the same `Message`
mean different things on the two endpoints and would claim a capability no live call has verified.
Recorded as **F-3**.

The failure this guards is silent: `UserInputConverter` turns an attached screenshot into an
`ImageContentBlock` on every user turn, so a converter that handled only text would emit a text-only
item, the model would answer as though nothing was attached, and the build would stay green.

### 4.3 `strict` is `false`, and that is the parity choice rather than the safe-looking one

`FunctionTool.strict` is **required** on the Responses builder and is **never set** on Chat, where
the absent field leaves the server's non-strict default in force. Three values satisfy
`checkRequired` — `true`, `false`, and an explicit `JsonNull` — and omitting the setter is not a
fourth outcome but an `IllegalStateException`.

**`false` is chosen because parity is the absence of strict validation, not its presence.** Strict
mode accepts only a subset of JSON Schema (it requires `additionalProperties: false` on every object
and every property listed in `required`), and the population that would break is neither small nor
hypothetical: this repo's own `additionalProperties` rule is scoped to `at.aimon.core.tools` and
**explicitly exempts MCP schemas**, because an MCP tool advertises the *server's* schema and not
ours. Writing `true` would turn tools that work today into server-side rejections tomorrow.

`JsonNull` is rejected for the same reason `temperature: null` is: a field the server did not
previously see is a behaviour change whether its value is `null` or `true`. `false` says what the
absent field said, in the only vocabulary this builder accepts. Turning it on is a real option with a
real argument, and it is **F-4** — it needs its own gate, an audit of the exempt population, and its
own CHANGELOG entry, not a ride on a bug fix.

The `ToolConversionException` wrapping is mirrored **without being improved**: the same per-tool
`log.error` + wrap, the same type, the same message, thrown from the same point in the same method,
escaping before the try on both paths exactly as it does today.

### 4.4 Every required field on this path, swept

Every SDK type this design constructs, with its `checkRequired` set read out of the jar and
classified as a value the data determines or a choice somebody has to argue:

| Type constructed | `checkRequired` | Determined or a choice |
|---|---|---|
| `ResponseCreateParams` | **none** — neither its builder nor `Body`'s calls `checkRequired`, and `model()` returns `Optional` | the model is supplied regardless, and two endpoint-selection tests bind it |
| `Reasoning` | none | effort is optional; no *tools* clamp, but a rung below the model's `lowestReasoningEffort` is omitted |
| **`FunctionTool`** | `name`, `parameters`, **`strict`** | name and parameters determined; **`strict` is a choice — §4.3** |
| `FunctionTool.Parameters` | none (a `@JsonValue` map) | determined — the same key-by-key copy Chat does |
| `ResponseInputItem.Message` (user) | `content`, `role` | determined |
| `EasyInputMessage` (assistant text) | `content`, `role` | determined — and it is the carrier **because** `Message.Role` has no `ASSISTANT` |
| `ResponseInputText` | `text` | determined |
| **`ResponseInputImage`** | **`detail`** | **a choice, and it was argued** — `AUTO`, because that is what Chat effectively sends by omitting the field |
| `ResponseInputItem.FunctionCallOutput` | `callId`, `output` | determined — including the `"Error: "` prefix |
| `ResponseFunctionToolCall` (replayed call) | `arguments`, `callId`, `name` | determined; the item `id` is optional and is **deliberately not invented** |
| `ResponseReasoningItem` (replayed trace) | `id`, `summary` | **never reached** — §2.1's deserialisation rule |

**On the read path the rule is the other way round, and that is deliberate.** Reading is not
`checkRequired` but `getRequired` accessors throwing at access time, and the split is by what the
field means: **identity throws** (a `function_call` missing its `call_id` is a provider fault, and
the throw lands inside the client's try where it is classified like any other failure), while
**accounting degrades** (a missing token counter must not fail a turn that otherwise succeeded).
Naming the split is the point — without it, "use raw accessors" applied everywhere would turn a
malformed tool call into a silent empty `ToolUse`.

### 4.5 `call_id` versus `id` (work item 6)

A Responses `function_call` carries **both** an item `id` (`fc_…`, `Optional` in the SDK) and a
`call_id` (`call_…`, required). `ToolUse.getId()` holds the **`call_id`**, because that is the one a
`function_call_output` must match. The item `id` is **deliberately not preserved** across a replay
and must not be invented. Whether a replayed `function_call` without its original item id is accepted
is the same unverified server question as the anchor (§8, U-1).

---

## 5. Failure modes

| # | Failure | Handling |
|---|---|---|
| 1 | **A gateway implements only `/v1/chat/completions`** and is now sent to `/v1/responses`. Today it works; this branch would 404 it. | `OpenAIConfig.responsesApiEnabled(false)`. **Say the asymmetry plainly:** the *breakage* is fully yaml-creatable (`baseUrl` is a CLI key and a starter property, and any real `gpt-5*` name hits the built-in row) while the *fix* is Java-only. That is different in kind from the other programmatic-only knobs, which override things that already work. A 404 still fails loudly and once — the retry policy retries only rate-limit and overloaded. |
| 2 | **Every counter on `ResponseUsage` is a required accessor, not just the reasoning one** — five deep: three at the top level, the details object beside them, and the reasoning counter one level down inside that. A document missing any top-level counter throws *before* the details are reached. | Raw accessors at every level; anything absent counts as zero, plus the narrowing guard the Chat path already has for `long` → `int`. One exception cannot be taken literally: `TokenUsage` enforces `total >= prompt + completion`, so a document that reports input and output but omits `total` reports the sum rather than 0 — reporting 0 would fail the very call this degradation protects. |
| 3 | **A reasoning item replayed without the tool call it preceded** — the shape OpenAI is documented to reject. | Two guards. The anchor keeps them adjacent, and `MessageStripper` drops traces **while keeping tool uses**, so compaction cannot produce the dangling shape. The reverse mistake — keeping traces while dropping calls — is the one to review for. |
| 4 | **A stored payload this build cannot parse** — a transcript written by a newer build, or a corrupted row. | Drop that trace, warn once, send the turn without it. A dropped trace costs re-derived reasoning; a thrown exception costs the session. Trace decoding never rethrows. |
| 5 | **Provider swap mid-session.** | `providerName` is compared to `getProviderName()`; a foreign trace is dropped and warned once. Without this, an Anthropic thinking block reaches `/v1/responses`. |
| 6 | **A rolling upgrade drops reasoning tokens on the cross-node wire — or NPEs.** `PayloadValues.asInt` is `((Number) requireNonNull(value)).intValue()`, and a map written by an old node has no reasoning key. Both decoders turn any `RuntimeException` into a dropped signal, so the obvious `asInt(map.get("reasoning"))` would discard the whole status update rather than report one counter as zero. | A new `PayloadValues.asIntOrZero` for the new key **only**. The existing three keep `asInt`, because their absence really is a malformed payload. Note the asymmetry with `SessionRecordCodec`, whose `node.path(...).asInt()` already defaults to 0 — one wire is tolerant by construction and the other is not. |
| 7 | **Redaction does not reach a reasoning payload.** `Message.mapText` is documented as the single entry point for whole-message text rewriting, and the payload is deliberately outside it. | Stated, not hidden — in `mapText`'s javadoc, here, and in the CHANGELOG. It cannot be fixed by mapping the text: OpenAI's carrier is `encrypted_content` (ciphertext a rewritten byte invalidates) and Anthropic's `signature` has the same property. What is offered instead is an off switch, and the note that what leaves the process is a payload the provider itself produced and already holds. §8, U-2. |
| 8 | **A provider error that the SDK does not raise** — `response.error`, `response.failed`, or a 200 `Response` with `status: "failed"`. The SSE decoder throws only on a **top-level** `"error"` key, which none of those three shapes has. | §5.1 in full. Both ways of getting it wrong are named there. |
| 9 | **A streamed turn produces reasoning items with no `encrypted_content`** — the feature silently doing nothing. | One bounded warning. Traces are taken from `output_item.done` precisely because the SDK names that as the source populated under `store: false`. |
| 10 | **Transcripts grow.** An `encrypted_content` blob is far larger than the text of the turn, and every assistant turn in a reasoning session carries one. | Named as an operational cost, not engineered away: capping or truncating breaks the round trip, which is the whole feature. Compaction sheds them, which bounds a long session, and the switch removes them entirely. §8, U-5. |
| 11 | **Token estimation under-counts on this path.** `HeuristicTokenEstimator` and `TikTokenEstimator` walk content blocks, tool uses and tool results, and count **0** for reasoning traces — while `DefaultCompactionGuard` drives every threshold from the estimator rather than from provider usage. So the guard under-counts by roughly `reasoning_tokens` on the axis it is watching. | **Deliberately not fixed**, and the obvious fix is wrong: counting the base64 blob as text would over-count by an order of magnitude against the true input cost and force premature compaction, destroying the traces. The blast radius is bounded — compaction drops the traces so the error resets, the context limits already reserve headroom for estimator error, and the same estimator already omits tool *definitions*, a larger and equally uncounted term. Recorded here so the next person to read a context-length 400 has the sentence. |
| 12 | **A tool that works on Chat is rejected on Responses** because an implementer supplied `strict: true`. | §4.3, and a test that is red on `true` and on an omitted setter alike. |

### 5.1 Provider errors that are not SDK exceptions

Both endpoints share one SSE decoder, and it throws `SseException` on exactly one condition: a
**top-level** `"error"` key on the decoded payload. Chat Completions' mid-stream failure has that
shape, which is why `OpenAIExceptionMapper`'s `SseException` branch is reachable there at all. The
Responses shapes do not:

| Shape | Payload | Top-level `error`? | SDK throws? |
|---|---|---|---|
| `ResponseErrorEvent` | `{type, code, message, param, sequence_number}` | **no** | **no** — delivered as data |
| `ResponseFailedEvent` | `{type, response:{…, error:{…}}, sequence_number}` | **no** — nested | **no** |
| Blocking `create(...)` returning 200 with `status: "failed"` | a plain `Response` | n/a | **no** — a normal return value |

Unhandled, the same server-side condition that yields a retryable `LlmOverloadedException` on the
blocking/Chat path yields *no exception at all* here — which is precisely the divergence the
preserved behaviour exists to prevent. Both ways of not-throwing are bad in their own way: leaving
the aggregator unclosed escapes as `IllegalStateException` from **outside** the try, unmapped and
reporting AIMON's internal state instead of the provider's error; closing it without throwing turns
the failure into a **silent success** the executor accepts as the assistant's final answer.

So `OpenAiResponseErrors` builds the exception and the caller throws it **from inside the try**,
where the existing cascade classifies it — including the second catch's `isCancelled()` check, which
is what makes a provider failure coinciding with a local cancellation report as a cancellation rather
than a server fault.

Classification is **exact parity by default**: `mapMidStreamError(...)`, already package-private and
already what the Chat `SseException` branch calls, turns `rate_limit_exceeded` into a rate-limit
exception and everything else into the retryable overloaded one. The one deliberate divergence is the
request-content family — `invalid_prompt`, the `invalid_image*` group, `image_too_large`,
`bio_policy`, `data_residency_mismatch` — which a blocking call would have rejected as a 400, and
which sending through the mid-stream mapper would make retry until the policy gives up. The rule is
stated as a three-element **transient** set with "unrecognised keeps parity", so a code OpenAI adds
later inherits today's behaviour instead of silently becoming non-retryable (§8, U-3).

### 5.2 How each preserved behaviour survives

The structural answer is §2.2: all four live in `OpenAILlmClient`, none moves into an exchange, and
the Responses path inherits them rather than re-implementing them.

| Preserved behaviour | Survives because | Chat test | New test |
|---|---|---|---|
| **1. `StreamResponse.close()` as the abort lever, `onCancel` registered *after* the stream opens, fast path for already-cancelled** | The fast path stays where it is, before anything opens. The try-with-resources now holds an `OpenAIStreamHandle` whose `close()` delegates to the same thread-safe idempotent method, one indirection away. `onCancel(handle::close)` stays *inside* the try. | `OpenAILlmClientCancellationTest`, **unmodified** | `OpenAIResponsesCancellationTest` |
| **2. Cancellable non-streaming rerouted through streaming, chunks discarded, usage still requested** | Not touched at all: the reroute is a decision about `cancellation.isSupported()` taken before any endpoint is chosen. | same class, **unmodified** | same class — including an assertion that the reassembled response carries non-empty usage |
| **3. `SseException` classified from the payload, not the HTTP 200** | (a) **Neither exchange contains a `catch`**, so a genuinely thrown SDK failure reaches the client's cascade, the only place the mapper is called. (b) A Responses provider error is **not an SDK exception at all**, so §5.1 builds the exception the mapper would have produced and throws it from inside the try. | `OpenAIExceptionMapperTest`, **unmodified** | `OpenAIResponsesErrorEventTest` — every input an **event or a status**, never a pre-thrown exception |
| **4. Per-request timeout via `RequestOptions`, `null` keeping the single-argument overload** | `perRequestOptions(modelConfig)` stays in the client; each exchange has the same one-line `options == null ? svc.x(p) : svc.x(p, o)`. | `OpenAILlmClientRequestTimeoutTest`, **unmodified** | `OpenAIResponsesRequestTimeoutTest`, whose `never()` assertions are the load-bearing half |

"Unmodified" is part of the acceptance: editing those classes would hide exactly the regression they
guard.

---

## 6. What the tests bind, and how each one goes red

Test quality is the acceptance criterion this job has failed on before, so each of these was
**verified by mutation** — the bug was introduced, the test observed to go red, the bug reverted.

| Mutation | Tests that go red |
|---|---|
| Attach `List.of()` instead of the response's traces in `OrcaAgentExecutor` | its three seam tests |
| …in `DefaultSubagentExecutor` | its two |
| …in `LlmSkillExecutor` / `ReActLlmDeriver` | one each |
| Resolve the endpoint from `config.getModel()` rather than the per-request name | `OpenAILlmClientEndpointSelectionTest` cases 5 and 6, each with two independent reds |
| `.strict(true)` on the tool conversion | `toolSchemaIsCopiedVerbatimAndStrictIsFalse` |
| A plain `ObjectMapper` for the reasoning payload | three tests, two in `OpenAiReasoningTracesTest` and the headline round-trip |
| Log `response.error` instead of throwing (the round-1 defect) | seven tests in `OpenAIResponsesErrorEventTest` |

Three assertion rules are load-bearing and are followed everywhere:

- **Absence is asserted on the raw `_xxx()` accessor or on the serialised body**, never on
  `xxx().isEmpty()`, because the SDK's `getOptional` collapses `JsonMissing` and `JsonNull` — an
  implementation that put `"temperature": null` on the wire and earned the exact 400 this branch
  removes would still pass an `isEmpty()` assertion.
- **The reasoning payload is compared as a tree, never with `contains`.** A plain `ObjectMapper`
  *preserves* `encrypted_content`; its corruption is an *addition*, so only a comparison that
  notices extra keys can fail on it.
- **A `never()` on an SDK service names its parameter type**, because `ResponseService` declares four
  `create` and four `createStreaming` overloads and a bare `any()` is ambiguous across them.

Two facts about coverage that are part of the claim rather than omissions:

- **Site 7 has no test**, because it writes into a buffer discarded at the next statement — no
  snapshot, no second call, no return value carrying it. It is protected by review.
- **The `IllegalArgumentException("Unsupported role: …")` arm has no test**, because `Role` has
  exactly three constants and all three are handled. It is mirrored anyway, for the same reason the
  Chat converter has it: parity kept only where it is currently observable is not parity.

**The docker-backed backend round trip was not run** (§8, U-6). The encoding is decided in
`JsonSessionSnapshotCodec` and `SessionRecordCodec`, both covered in the gate; the backends store the
result as an opaque string and cannot see the field.

---

## 7. Follow-ups this round deliberately does not start

| # | Item | Why not now |
|---|---|---|
| **F-1** | **Anthropic thinking blocks** — filling the same `ReasoningTrace` slot with `ThinkingBlock`/`RedactedThinkingBlock` JSON, anchored the same way. | Out of scope by instruction. The fit is demonstrated in §2.1; half-building it would put a second provider's byte-exactness requirement into a round that cannot test it. |
| **F-2** | A yaml/property surface for the registry override, `responsesApiEnabled`, and the sampling parameters. | Three programmatic-only knobs now. One config issue, not three riders. **Partly done: #46 took the registry override alone** — CLI `llm.modelCapabilities`, starter `aimon.llm.model-capabilities`. The other two are still Java-only. That issue's design also settled where they go when someone takes them: `responsesApiEnabled` moves down to `aimon.llm.openai.*` / CLI `llm.openai.*` because it names a vendor endpoint, while the sampling keys stay in the shared namespace because they mean the same thing for every vendor. See [`model-capability-config-key.md`](model-capability-config-key.md) §2.7. |
| **F-2 (a correction, 2026-09-10)** | This row counts **three** knobs and `reasoningEffort` is not among them — it is on `LlmModel` and `OpenAIConfig` rather than on this path's own surface, and the only mention of it in this document is the SDK mapping table in §3. **#61 gave that fourth knob its surface** (`llm.reasoningEffort` / `aimon.llm.reasoning-effort` / frontmatter `model.reasoningEffort`), so a reader counting what is left here gets the right number: the two this row still names — `responsesApiEnabled` and the sampling parameters — remain Java-only, and remain `L-2` in [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md). Design: [`reasoning-effort-config-surface.md`](reasoning-effort-config-surface.md). |
| **F-3** | Native `input_file` for non-text documents on this path. | Taking it would make the same `Message` mean different things on the two endpoints and claim an unverified capability. |
| **F-4** | **`strict: true` for tool schemas.** Strict mode improves tool-call accuracy and the field is already being written. | Turning it on rejects every schema that is not strict-compliant, and §4.3 shows that population is large and partly not ours. Needs its own gate, an audit, and its own CHANGELOG entry. |
| ~~**F-5**~~ | ~~Reasoning *summary* deltas forwarded to the sink.~~ | **Done — [`reasoning-delta-stream.md`](reasoning-delta-stream.md) (#62).** Still a separate feature; it simply got its own round, together with the Anthropic half, because forwarding without asking would have shipped a channel that is always empty — `OpenAIResponsesRequestFactory` never set `reasoning.summary`, and on this endpoint the reasoning itself is `encrypted_content`, so the summary is the only readable form there is. Two corrections to this row's wording: **both** delta families are forwarded, not the summary alone (`response.reasoning_summary_text.delta` and `response.reasoning_text.delta` — forwarding only the first would leave a model that emits raw reasoning text showing nothing to a deployment that asked to see reasoning), and the ask itself opened `aimon.llm.openai.*` / CLI `llm.openai.*`, the namespace F-2 above reserves for `responsesApiEnabled`. Opt-in and unset by default on both surfaces; a model routed to Chat Completions, which has no such parameter, gets one WARN rather than silent inertness. |
| ~~**F-6**~~ | ~~The o-series rows in the built-in table.~~ **Done — round 8.** | Was *"blocked on live-API verification"*. That verification happened on 2026-09-09: four o-series names accept a replayed reasoning item on `/v1/responses`, with a corruption control proving the item is consumed, so the flag flipped for the eight measured names and they now reach this path. It did **not** flip per prefix — `o1-pro` and `o4-mini-deep-research` were never called and keep the old behaviour through their prefix rows. [`openai-model-capabilities.md`](openai-model-capabilities.md) §13. |

---

## 8. What could not be resolved

- **U-1 — the anchor's exact necessity is inferred from a javadoc, not measured.**
  `ResponseReasoningItem`'s own doc says these items must be included in `input` for subsequent
  turns, and the API is widely reported to reject a reasoning item arriving without the item it
  produced. The anchor is designed for the strict reading because the failure mode of guessing wrong
  that way is a 400 no unit test can find, while the cost of being over-careful is one unused
  nullable field.
- **U-2 — a reasoning payload is outside the redaction gate and cannot be brought inside.** What is
  offered is disclosure plus an off switch. A deployment with a hard redaction requirement on
  everything leaving the process should set `responsesApiEnabled(false)` and accept phase 1's
  behaviour. If that trade is unacceptable, the answer is a policy that refuses to *produce* traces
  rather than one that rewrites them, and that is a separate design.
- **U-3 — the terminal/transient split is reasoned from enum names**, not from observed responses.
  The default arm is deliberately the parity arm, so the failure mode of being wrong is bounded: a
  retryable code mis-listed as terminal loses its retries, and only for codes actually named.
- ~~**U-4 — "`response.completed` carries usage without being asked" is a server claim.**~~
  **Measured — round 8, 2026-09-09, on `o4-mini`.** The terminal event of a streamed `/v1/responses`
  turn does carry a full `usage` object, with no `stream_options` equivalent having been sent. That is
  the one name whose terminal payload was captured; the other four were recorded as event sequences
  only, so the claim is closed for `o4-mini` and unmeasured for them. The rest of the entry stood as
  written: `Response.usage()` is `Optional` in the SDK and the tests supply usage in their own
  fixtures, so no test here would have failed if the server omitted it; what changes is that the claim
  is no longer only a claim, for one name.
  [`openai-model-capabilities.md`](openai-model-capabilities.md) §13.5.
- **U-5 — transcript growth is stated, not budgeted.** Nobody has measured what a 40-turn `gpt-5`
  tool loop does to a session row. The mitigations exist; the number does not.
- **U-6 — the docker-backed backend round trip has never been run in this task.** §6 says what the
  gate does prove.
- **U-7 — `store: false` was chosen without measuring the alternative.** `previous_response_id` with
  `store: true` would let the server keep the reasoning and would shrink the request. It was rejected
  on architecture and on retention, not on measurement.
- **U-8 — a stream that ends cleanly with no terminal event is a silent empty success.** Closing the
  aggregator on drain is what keeps an unclosed aggregator from escaping unmapped; the consequence is
  that a protocol-violating stream produces an empty successful response rather than an error. This
  is **parity** — a Chat stream with no `finish_reason` behaves the same way today — and tightening
  it is a change to both endpoints.

---

## 9. Where the implementation departed from the approved design

Small, and each for a reason found in the code rather than chosen for convenience.

1. **`openStream` takes the sink and aggregator as arguments** rather than as exchange constructor
   state. The blocking path has neither, and a field meaningful on only one of two paths is a null
   waiting to be dereferenced. Everything the design cares about is unchanged: params are still built
   at construction (before the try), the client still keeps one try-with-resources, one `onCancel`
   and one cascade, and neither exchange catches anything.
2. **`MessageStripper`'s identity fast path now also checks `hasReasoningTraces()`.** The design said
   the stripper "keeps dropping" traces, which was true only of `rebuild(...)`: a text-only assistant
   message took the fast path and carried its payloads — far larger than the text of the turn — into
   the summarization call. One line in the guard makes the stated invariant actually true.
3. **The Responses sampling sink reports the two penalties instead of swallowing them.** That
   endpoint has no `presence_penalty` or `frequency_penalty` at all, which the design's mapping table
   did not cover. On a model that accepts sampling, a configured penalty would otherwise vanish with
   no error and no log line — the "succeeds with settings other than the configured ones" failure the
   divergence reporting exists to remove.
4. **`OpenAiResponseUsages` is a class the design's file list did not name.** The usage-degradation
   rule is identical on the blocking and streaming paths and had to be shared; putting it in either
   mapper would have made the other one's copy the place it drifts.
5. **A missing `total_tokens` reports the sum, not zero.** §5 row 2 explains why "degrade to zero"
   cannot be taken literally for that one counter.
6. **The routing predicate is one term, not two.** The design's prose says "round-trip-capable +
   reasoning-capable ⇒ use `/v1/responses`" in one place and implements
   `supportsReasoningTraceRoundTrip() && isResponsesApiEnabled()` in two others. They agree on the
   built-in table, so no test distinguishes them; the predicate as implemented is the one written
   here, and the second term is the *deployment* question, not a second model fact.
7. **This document is in English**, matching the round-1 design it continually cross-references
   rather than the Korean the design specified. `docs/design/` is not a translation target either
   way.

---

## 10. Live verification — #43's own lists, walked (2026-09-10)

Everything above §10 was written without a billed call. This section is the call, and it is here
because #43 asks for the reproduction to be *run* rather than reasoned about. Every row names a
request and what came back; the request-by-request log is the task record's `measurements.md`.

### 10.1 The reproduction

The issue's snippet, unchanged: a config carrying nothing but the key and `model("gpt-5.6-terra")`, a
default `LlmModel`, a one-line question, and any non-empty tool list.

| request | result |
|---|---|
| the snippet as written, through `OpenAILlmClient` | **accepted**; usage populated on both counters, stop reason present and not `UNKNOWN` |
| the same request with `responsesApiEnabled(false)`, forcing Chat Completions | **HTTP 400**, body verbatim: *"Function tools with reasoning_effort are not supported for gpt-5.6-terra in /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'."* |

**The second row is what makes the first mean something.** It carries no `temperature` and no
`reasoning_effort` — nothing was sent and the server still refused — so the issue's note that omitting
the effort is not a workaround holds a year later, and **routing is the fix rather than a change of
parameters**. Both rows are `OpenAIReasoningLiveTest.TheReproduction`.

One correction to the issue's *wording*, which the code already had right: #43 says these models
reject sampling parameters "by the presence of the parameter, regardless of value". On
`/v1/responses`, `gpt-5.6-terra` refuses `temperature: 0.0` (*"Unsupported parameter: 'temperature' is
not supported with this model."*) and **accepts `temperature: 1.0`**. It is the non-default value that
is refused — which is what `InMemoryModelCapabilityRegistry`'s `gpt-5` row says, and why suppression
loses nothing on the wire.

### 10.2 The six work items, one by one

| # | Work item | Evidence |
|---|---|---|
| 1 | Request building, sampling omitted rather than defaulted | 10.1 row 1 accepted on a model that refuses a non-default `temperature`; §4.1 and `OpenAiRequestParameters.applySampling` are the code, and the 400 in 10.1's last paragraph is why omission is load-bearing rather than tidy |
| 2 | Reasoning item round trip | A captured `[reasoning, function_call]` turn replayed on turn two: **accepted**. The control below is the half that makes it a claim |
| 3 | Streaming — a second mapper for `ResponseStreamEvent` | 611 `response.reasoning_summary_text.delta` events off the wire, and the same request through `sendMessageStreaming` reaching the sink as `REASONING_DELTA`. [`reasoning-delta-stream.md`](reasoning-delta-stream.md) §12.4 |
| 4 | `TokenUsage.reasoningTokens` | The streamed turn reported `reasoning_tokens: 1280` of 1502 output tokens, read back through `getReasoningTokens()` as positive and `<= completionTokens` — §2.3's containment, on a live number rather than a fixture |
| 5 | Stop reasons from `status` + `incomplete_details.reason` | `TOOL_USE` on the captured tool-calling turn, `END_TURN` on the streamed one, and *present and not `UNKNOWN`* on the reproduction. The `incomplete` arms are **not** measured — reaching them costs a deliberately truncated turn |
| 6 | `call_id` versus `id` | The captured trace's `toolUseId` equals the turn's `call_id`, and the replay carrying a `function_call_output` keyed by it was accepted. Two ends agreeing on one identifier |

**The negative control for item 2.** Replaying the item with the last 40 characters of its
`encrypted_content` overwritten is refused: *"The encrypted content for item rs_… could not be
verified. Reason: Encrypted content could not be decrypted or parsed."* Without it the acceptance
above proves nothing, and that is not hypothetical here — see 10.3.

### 10.3 A measurement that changed a test rather than the code

`gpt-5.6-terra` asked *"What is the weather in Seoul? Use the get_weather tool."* with that tool
available returns `output: [function_call]` and `output_tokens_details.reasoning_tokens: 0` — **no
reasoning item at all.** Nothing was dropped; the model decided the question needed no thought.

Two consequences worth carrying. A turn that has to anchor a reasoning item must **earn the reasoning
and require the tool**, which is why the captured turn is arithmetic reported through a tool rather
than a weather lookup. And a turn carrying no item is accepted on replay, which is exactly why item
2's positive case needs its control.

### 10.4 The "please preserve when porting" list

#43 names four behaviours a from-scratch Responses client would regress. **There is no from-scratch
client** — §2.2's single-client decision means all four live above the endpoint seam and run
unchanged on both paths, which is a stronger answer than four separate ports would be.

| Preserved behaviour | Where it is now | Bound by |
|---|---|---|
| `StreamResponse.close()` as the thread-safe idempotent abort lever, registered through `cancellation.onCancel(...)` after the stream opens | `OpenAILlmClient.sendMessageStreaming`, one `try` for both endpoints; `OpenAIStreamHandle.close()` delegates to it | `OpenAIResponsesCancellationTest` |
| The fast path for "already cancelled before the connection opens" | the same method's first statement | same |
| Cancellable non-streaming calls routed through the streaming path with a discarding sink | `OpenAILlmClient.sendMessage(SystemPromptParts, …)` — endpoint-independent, since `exchangeFor` runs below it | same |
| `OpenAIExceptionMapper`'s `SseException` branch, classifying from the payload rather than the stream-open status | unchanged, and this endpoint needed **more** than it: three failure shapes here are events or statuses rather than thrown exceptions | `OpenAIResponsesExceptionRoutingTest`, `OpenAIResponsesErrorEventTest` |
| Per-request timeout via `RequestOptions`, returning `null` to keep the single-argument overload | `perRequestOptions`, read by both exchanges | `OpenAIResponsesRequestTimeoutTest` |

**These five rows are read off the code and its tests, not measured.** A live call exercises none of
them except incidentally, and manufacturing a mid-stream server failure to bill one is not something
this round did.

### 10.5 What §8 still says

**This round discharges none of §8's U-items.** That is worth stating plainly, because a section
headed "live verification" invites the opposite assumption: what was measured here is the
reproduction and the six work items, which are a different list. §8's only struck-through entry,
**U-4**, was closed in round 8 and not by this one.

**U-1** is the item this round comes closest to and still does not reach: the reasoning round trip is
now measured as *working*, which says nothing about whether omitting the anchor would fail — that
needs a request built deliberately wrong. **U-2**, **U-3**, **U-5**, **U-6**, **U-7** and **U-8**
stand exactly as written.

---

## 관련 문서

- [Per-model capability descriptor (#44) + gpt-5.x phase 1](openai-model-capabilities.md) — round 1
  and 2, and §10 there records what this round closed
- [스트리밍 설계](streaming.md) — §9 records the OpenAI half of reasoning-trace streaming as done
- [LLM Provider 개발 가이드](../../features/llm/llm-provider-development-guide.md) — how a provider
  fills and reads the slot
- [LLM 사용량 계측](../../features/llm/llm-usage-metering.md) — `reasoningTokens` is recorded, not priced
- [Frozen names](../../migration/frozen-names.md) — the boundary the persisted-state change respects
