# Design — a reasoning stream a person can watch (#62, Phase 3 of 4)

> Target: `aimon-core` (`at.aimon.core.llm.streaming`, `at.aimon.core.agent.stream`,
> `at.aimon.core.agent.impl.orca`), `aimon-llm-anthropic`, `aimon-llm-openai`,
> `aimon-session-routing`, `aimon-cli`, `aimon-spring-boot-starter`.
>
> **No API call was made for this document.** Every line number and every claim about the tree was
> read out of the worktree at `3dd56df` on **2026-09-10**, after #60 / #54 / #61 landed underneath.
> Every claim about a vendor's wire behaviour is either read out of the vendored SDK sources (and
> says so) or is quoted from a prior design document that says whether *it* measured the thing.
> §11 is the list of what that leaves unproven.
>
> Design of record for this phase:
> `docs/design/llm/reasoning-model-enablement.md` §5, decisions **D-8** and **D-9**. §2 below says where this follows it and where it departs;
> **this document does not rewrite it.** Namespace criterion:
> `docs/design/llm/model-capability-config-key.md` §2.7.

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **N-1** | Where does the OpenAI opt-in key go? | **Vendor.** `llm.openai.reasoningSummary` / `aimon.llm.openai.reasoning-summary`. §2.7 test 1 fires: `reasoning.summary` is the OpenAI request-body path and `auto`/`concise`/`detailed` is OpenAI's value vocabulary. This **opens** `llm.openai.*`, the slot `L-2` and `spring-boot-starter.md` §9.3 left empty. §3.2 |
| **N-2** | Where does the Anthropic opt-in key go? | **Vendor.** `llm.anthropic.thinkingDisplay` / `aimon.llm.anthropic.thinking-display`, a fourth key beside #54's three. §2.7 test 1 fires twice over: "thinking" is Anthropic's word for the phenomenon (this repository's neutral nouns are `ReasoningEffort` / `ReasoningTrace`) and `display` is the field name in Anthropic's request body. §3.3 |
| **N-3** | And the neutral umbrella key a reader will ask about? | **It would be shared — and it is not shipped.** Applying §2.7 to a hypothetical `llm.streamReasoning` gives *shared* on both tests, the same answer #61 got for `reasoningEffort`. It is still not shipped, because shipping it beside N-1/N-2 creates two user concepts for one intent plus a precedence rule (the shape `model-capability-config-key.md` §3 **R2** rejects), and shipping it *instead* of them forecloses `concise`/`detailed`/`updates` behind a boolean that could only be widened by a breaking type change on a config key. Filed as a follow-up with a named trigger. §3.4 |
| **T-1** | Does the reasoning text reach `ChunkAggregator.textBuffer`? | **No, and that is the load-bearing invariant.** A second buffer, `peekReasoningText()`, which `toLlmResponse()` does not read. The failure it prevents is a *privacy* failure, not a rendering one, and it is only observable on the mid-stream-cancel path — so that is the path the test drives. §4.2, §9.2 |
| **T-2** | What actually gates the forwarding? | **Configuration, not the arrival of deltas.** On the Anthropic **budgeted** dialect `thinking_delta` events already arrive today and are swallowed at `AnthropicStreamingMapper.java:195-203`. Forwarding "whatever arrives" would turn reasoning text on for every existing `thinkingMode: extended` deployment without it asking. §5.2 — **a departure from the design of record's §5.1, which is stated only of the adaptive generation.** |
| **T-3** | Does `isDroppable` become `instanceof A \|\| instanceof B`? | **No — it becomes a rank.** The one-line widening gives reasoning deltas *equal* rank with text deltas, so a reasoning burst evicts answer text: the opposite of what "ahead of text deltas" asks for. §4.5 |
| **T-4** | One new event, or three (delta + reset + completed)? | **One.** `AssistantTextStreamReset` / `…Completed` already mark the attempt boundary for the whole attempt; a renderer that handles both channels clears both. Three subtypes would triple the sealed-hierarchy blast radius to carry no information the existing pair does not already carry. The naming friction this leaves is recorded rather than papered over. §4.4 |
| **T-5** | Is this reachable from an agent definition (`model.reasoningSummary`)? | **No.** Whether a *terminal* shows thinking is a property of the deployment that runs the agent, not of the agent, and an agent definition is copied between deployments that do and do not have a watcher. `MarkdownAgentDefinitionParser` is untouched. Follow-up if someone asks. §3.5 |

---

## 1. The problem, in one paragraph

A reasoning model can deliberate for tens of seconds before it emits a visible token, and AIMON shows
nothing while it does — a silence that is indistinguishable from a hang on exactly the executions
where the user most wants to know something is happening. Two independent gaps produce it, and
closing either one alone leaves the feature inert. The transport gap: `LlmStreamChunk.Kind` has three
constants and no way to say "this text is deliberation, not answer", so both provider mappers
deliberately drop reasoning text on the floor rather than invent a channel — `AnthropicStreamingMapper`
says so at `:200-203`, and `openai-responses-path.md` §7 F-5 says so for the other provider. The
request gap: nothing asks for the text in the first place — `OpenAIResponsesRequestFactory` never sets
`reasoning.summary` (verified: `grep -n summary` over the file returns nothing at `3dd56df`), and on
the current Anthropic generation thinking text is omitted by default. So this change adds one chunk
kind, one sealed-hierarchy event, one opt-in key per provider, and the wiring between them — and the
one thing it must not do is let the model's private deliberation be committed to a transcript as the
model's public answer.

---

## 2. Where this follows the design of record, and where it departs

`reasoning-model-enablement.md` §5 is the design of record for this phase. It is short — three
subsections — and it is right about the shape. It is written against the tree as it stood on
2026-09-09, which is before #60, #54 and #61 landed, and it is written at a level that leaves several
decisions to whoever implements it. Both kinds of gap are recorded here rather than silently resolved.

### 2.1 Followed as written

| Item | §5 says | This design |
|---|---|---|
| The transport | A new `LlmStreamChunk.Kind.REASONING_DELTA` with its own factory, mirroring `textDelta` | Same. §4.1 |
| The invariant | *"It must not reach `ChunkAggregator.textBuffer`… The aggregator gets a second buffer that `toLlmResponse()` does not read."* | Same, and the test drives the cancel path specifically. §4.2, §9.2 |
| The event | `AssistantReasoningDelta extends AgentExecutionEvent`, a sibling of `AssistantTextDelta` carrying `delta` and `chunkIndex` "with the same ordering contract" | Same. §4.3 — note that *"the same ordering contract"* is read as *its own* monotone sequence, not a shared counter. §4.3.1 |
| One commit | The constant and both `switch` handlers land together | Same, and the verified site list is §4.6 |
| The ask ships with the transport | D-8 | Same. §5 |
| Not `AssistantTextDelta` with a flag | D-9 | Same, and A6 in §7 |
| Drop order | Reasoning ahead of text deltas | Same *intent*, different mechanism. §4.5 / T-3 |

### 2.2 Departures, each with its reason

| # | Departure | Why |
|---|---|---|
| **D-a** | §5.1's *"F-4 alone yields a channel that is always empty"* is true of the **adaptive** Anthropic generation and false of the **budgeted** one. On `thinkingMode: extended`, `thinking_delta` events arrive today. | Consequence: the forwarding must be gated on configuration, not on delta arrival, or the change is not additive for existing `extended` deployments. Criterion 6 is a stronger constraint than §5.1 read literally. §5.2 |
| **D-b** | §5.3's table lists **five** sites, *"of which the compiler catches one"*. Verified at `3dd56df`: there are **eight**, and the compiler catches **two**, in two different hierarchies. | The two the table omits are both inside `LlmStreamChunk` itself: the constructor's validation `switch` (`:158-183`, `default -> throw`) and `toString()`'s `switch` **expression** (`:237`), which has no `default` and therefore fails to compile the moment a fourth constant exists. The eighth is the `AgentExecutionEvent` class javadoc's `<ul>` of subtypes (`:29-45`), which nothing enforces. Full table: §4.6 |
| **D-c** | §5.3 says `isDroppable` "gains it". | Implemented as a three-value rank rather than a widened boolean, because the widened boolean does not produce the ordering the same sentence asks for. §4.5 |
| **D-d** | §6's Phase 3 table says *"ask for `reasoning.summary`; forward its deltas"* (singular family). | Two SDK event families carry reasoning text on the Responses path — `response.reasoning_summary_text.delta` and `response.reasoning_text.delta` (both verified present in `openai-java-core` 4.57.0). Both are forwarded, under the one gate. Forwarding only the first would leave a model that emits raw reasoning showing nothing to a deployment that asked to see reasoning — the always-empty channel this issue exists to remove, at one-model granularity. §5.1 |
| **D-e** | §5.3 and §6 do not name a configuration key at all; §4/D-6 answered only Phase 2's keys. | Phase 3 needs at least one opt-in switch (criterion 4) and the namespace question is this round's to answer. §3 |
| **D-f** | §6 assigns no work to `aimon-spring-boot-starter` for Phase 3. | It gets work: the starter is a configuration surface and criterion 4 says *both providers*, not *the CLI*. §6.6 |
| **D-g** | §5.2 leaves the second buffer's reader unstated. | It gets one accessor, `peekReasoningText()`, whose only in-tree production reader is diagnostics. Recorded as a known cost in §11 O-5 rather than presented as free. |

None of these change §5's shape. **`reasoning-model-enablement.md` is not rewritten**; the departures
go to `build/deviations.md` per the task's documentation obligations, with D-a and D-b flagged as the
two a reader of §5 would otherwise get wrong.

---

## 3. The namespace question — the criterion applied, key by key

`docs/design/llm/model-capability-config-key.md` §2.7 states the rule once, and it is quoted here in
full so the reasoning below can be checked against it rather than against a paraphrase:

> **두 경우에 `aimon.llm.<provider>.*` 로 내린다 — 키가 벤더 개념을 이름에 담고 있거나, 같은 키가
> 벤더마다 다른 것을 뜻하거나. 오늘 소비자가 하나뿐이라는 사실은 쪼개는 이유가 아니다.**
>
> (*A key descends to `aimon.llm.<provider>.*` in two cases — its **name** carries a vendor concept, or
> the same key **means** something different per vendor. That there is only one consumer today is not
> a reason to split.*)

The rule has now answered in both directions: #54 split (`thinkingMode` et al.) and #61 shared
(`reasoningEffort`). Neither is copied below. There are three candidate keys and the rule is applied
to each on its own.

### 3.1 What this feature actually needs asked

Two things, and they are not the same thing said twice:

| Provider | The ask on the wire | Where it lives |
|---|---|---|
| OpenAI (Responses) | `reasoning.summary` = `auto` \| `concise` \| `detailed` | A **typed SDK field**: `Reasoning.Builder.summary(Reasoning.Summary)`, values `AUTO`/`CONCISE`/`DETAILED` — verified by `javap` over `openai-java-core` 4.57.0 |
| Anthropic (adaptive dialect) | `thinking.display` = `"summarized"` \| `"updates"` | **Not modelled in the SDK.** `ThinkingConfigAdaptive` in `anthropic-java-core` 2.13.0 carries `type` and nothing else — verified by reading `main/com/anthropic/models/messages/ThinkingConfigAdaptive.kt`. It carries `@JsonAnyGetter` + `putAdditionalProperty`, so the field can still be written; that is the same escape hatch `AnthropicUsages` already uses to *read* `output_tokens_details`, and `OpenAIMessageConverter:117,263` / `AnthropicMessageConverter:523` already use to *write* unmodelled properties |

`anthropic-thinking-traces.md` A11 recorded the SDK gap as *"moot on this SDK before it is declined"*.
That was true of a round told to stay out of `aimon-core`; it is not a blocker now, but the fact that
one ask is a typed field and the other is an untyped additional property is itself evidence for the
verdicts below.

### 3.2 Key 1 — the OpenAI ask → **vendor**

Proposed: CLI `llm.openai.reasoningSummary`, starter `aimon.llm.openai.reasoning-summary`.

| Test | Verdict | Reasoning |
|---|---|---|
| **Does the name carry a vendor concept?** | **Yes** | `reasoning.summary` is the literal path in the OpenAI request body, and the key's *values* are OpenAI's vocabulary — `auto` / `concise` / `detailed` are the three `Reasoning.Summary.Known` constants and mean nothing to Anthropic. More than a spelling coincidence: the concept behind the name is OpenAI's model of the world, in which the reasoning itself is `encrypted_content` (ciphertext by design, `ReasoningTrace.java:16-22`) and a *summary* is the only human-readable surrogate that exists. Anthropic has no summary; it has the model's own thinking text, gated by `display`. |
| **Does the same key mean something different per vendor?** | **Vacuously yes** | There is no Anthropic key with these three values for it to mean something else in. |

**Verdict: vendor.** One test firing is sufficient under the rule; both fire here.

**This opens `llm.openai.*` / `aimon.llm.openai.*`,** which is a namespace neither surface has today
(verified: `grep -rn "responsesApiEnabled\|responses-api" modules/aimon-cli/src/main
modules/aimon-spring-boot-starter/src/main` returns nothing). That is not an argument against it —
`spring-boot-starter.md`'s property tree (`:749-756`) has held the slot open since before #46, and
`llm-config-surface-open-items.md` `L-2` already writes down that `responsesApiEnabled` goes there
"because the **name** is an OpenAI endpoint". This key arrives by the same door for the same reason,
and it arrives first. Consequence in §6.7.

### 3.3 Key 2 — the Anthropic ask → **vendor**

Proposed: CLI `llm.anthropic.thinkingDisplay`, starter `aimon.llm.anthropic.thinking-display`.

| Test | Verdict | Reasoning |
|---|---|---|
| **Does the name carry a vendor concept?** | **Yes, twice** | "thinking" is Anthropic's word for the phenomenon — #54 already ruled exactly this for `thinkingMode` / `thinkingBudgetTokens` / `replayThinkingBlocks`, and `AnthropicProviderConfig`'s javadoc records the ruling: *"이 저장소의 중립 명사는 `ReasoningEffort` · `ReasoningTrace` 다"*. And `display` is the field name inside Anthropic's `thinking` object. Both halves of the key name are vendor wire vocabulary. |
| **Does the same key mean something different per vendor?** | **Vacuously yes** | OpenAI has no `display`. |

**Verdict: vendor**, and specifically a **fourth key in the block #54 opened** rather than a new one.
That placement is worth stating: it means an operator reading `llm.anthropic:` sees all four thinking
knobs together, and it means the existing `isEmpty()` / refusal machinery extends by one field rather
than being duplicated.

**The verdict does not depend on which of the two shapes the key takes.** `thinkingDisplay:
summarized` names Anthropic's field; a boolean `streamThinkingText: true` names Anthropic's *phenomenon*
("thinking"), which #54 already established is a vendor word. Both land in the vendor namespace. The
shape is chosen on other grounds — §6.5 — and the chosen shape is the enum, because it lets an operator
correct an unmeasured wire value (§11 O-1) without waiting for a release.

### 3.4 Key 3 — the neutral umbrella that is **not** shipped → would be **shared**

A reader who has just read #61's commit message will ask why there is no `llm.streamReasoning: true`
that both providers read. The rule is applied to it rather than the question being dodged.

| Test | Verdict | Reasoning |
|---|---|---|
| **Does the name carry a vendor concept?** | **No** | "reasoning" is this repository's own neutral noun (`at.aimon.core.llm.ReasoningEffort`, `at.aimon.core.llm.ReasoningTrace`) and "stream" is the neutral SPI's own word (`at.aimon.core.llm.streaming`, `LlmStreamChunk`, `LlmStreamSink`). Neither vendor's vocabulary appears. |
| **Does the same key mean something different per vendor?** | **No** | The question it asks — *"should this deployment receive the model's reasoning text as it is produced?"* — is the same question of either vendor. What comes back differs in **fidelity** (OpenAI: a server-written summary; Anthropic on the budgeted dialect: the model's own words), and that is a fact about what each vendor can deliver, not about what the key asks. That is the same distinction #61 drew for `reasoningEffort`: *"What each does with the answer differs, and translating that is what a neutral enum is for."* §2.7's test-2 example is `temperature`, where the **valid range** differs so the same written value produces different behaviour; here the same written value produces the same behaviour — reasoning text reaches the sink — at different fidelity. |

**Verdict: shared, if it existed.** It is not shipped, for two reasons that are about cost rather than
about the rule:

1. **Beside N-1/N-2 it creates the R2 shape.** `model-capability-config-key.md` §3 **R2** rejects
   shipping an alias alongside a full entry because *"사용자 개념이 둘이 되고, 둘이 동시에 지정될 때의
   우선순위라는 세 번째 규칙이 생긴다"* — the user concept becomes two, and a third rule appears for
   when both are written. `streamReasoning: true` + `openai.reasoningSummary: detailed` is exactly
   that pair, on a feature with zero users.
2. **Instead of them it forecloses the grain.** A boolean cannot say `concise` versus `detailed`, and
   widening a shipped boolean key into an enum later is a breaking type change on a configuration key
   — the failure mode `L-1` and #61's own commit message call out about *renaming*
   `lowest-reasoning-effort`, applied to a type instead of a name.

**Filed as a follow-up with a named trigger** (§10): *when a third provider arrives, or when someone
asks to move a deployment between providers without rewriting the key.* At that point the umbrella is
purely additive — a default the two vendor keys override — and the precedence rule is being written
deliberately rather than as a side effect.

### 3.5 Not a fourth key — the agent frontmatter

#61 put `reasoningEffort` in three places, the third being an agent definition's `model.reasoningEffort`.
This feature stops at two. An agent definition describes **the agent**; whether a terminal renders the
agent's deliberation describes **the deployment running it**, and the same definition file is checked
into a repository that a CLI user, a Spring service with no console, and a scheduled routine all load.
The counter-argument is real and stated: the ask costs output tokens on every request, which *is* a
model-behaviour knob and so *is* frontmatter's business. It is not decisive, because the token cost is
a consequence of the deployment's rendering choice rather than of the agent's design — and because
`MarkdownAgentDefinitionParser.extractModel` staying untouched keeps this change's frontmatter blast
radius at zero. Follow-up, §10.

---

## 4. The transport — core

### 4.1 `LlmStreamChunk.Kind.REASONING_DELTA`

```java
public enum Kind {
    TEXT_DELTA,
    REASONING_DELTA,   // new
    TOOL_USE_READY,
    STREAM_END
}

/**
 * Creates a reasoning-delta chunk. The text is the model's deliberation, never its answer —
 * ChunkAggregator keeps it out of peekText() and toLlmResponse().
 */
public static LlmStreamChunk reasoningDelta(int index, String reasoningDelta) { … }
```

A **separate field** `reasoningDelta` with its own accessor `getReasoningDelta()`, not a reuse of
`textDelta`. Reusing the field would make `getTextDelta()` non-empty on a reasoning chunk, which is
the same class of mistake as D-9's rejected flag one layer down: every existing caller that reads
`getTextDelta()` after checking nothing would start reading deliberation. The constructor's validation
`switch` gains an arm asserting the mirror-image constraints of `TEXT_DELTA` — non-empty
`reasoningDelta`, no `textDelta`, no `toolUse` — and each of the three existing arms gains a
`reasoningDelta == null` check, so the exclusivity is enforced in both directions at construction, as
`streaming.md` §4.1 promises the builder does.

Ordering placement inside the enum is deliberate: **immediately after `TEXT_DELTA`**, because the two
`switch`es read as a pair and `STREAM_END` stays last where every reader expects the terminal constant.
Nothing persists a `Kind` ordinal (`grep -rn "Kind.ordinal\|values()\[" ` over the streaming package
returns nothing), so insertion in the middle is safe.

### 4.2 `ChunkAggregator` — the second buffer, and what it is for

```java
private final StringBuilder textBuffer = new StringBuilder();
private final StringBuilder reasoningBuffer = new StringBuilder();   // new — never read by toLlmResponse()
```

```java
case REASONING_DELTA -> reasoningBuffer.append(chunk.getReasoningDelta().orElseThrow());
```

`peekReasoningText()` mirrors `peekText()` under the same lock. `toLlmResponse()` is **not** touched:
its `synchronized` block reads `textBuffer`, `tokenUsage`, `stopReason`, `toolCalls` and
`reasoningTraces`, and gains nothing.

Why the invariant is a privacy invariant and not a rendering one. There are **two** `ChunkAggregator`
instances alive during a streaming iteration and both matter, for different reasons:

| Instance | Created by | Read by | What a leak into `textBuffer` would do |
|---|---|---|---|
| The provider's | `AnthropicLlmClient:317`, `OpenAIResponsesExchange` via the client | `toLlmResponse()` | Deliberation becomes the assistant's answer text in the `LlmResponse` — rendered, persisted, and replayed to the model next iteration |
| The executor's | `OrcaAgentExecutor.StreamingEventSink:3122` | `peekText()` at `:3019` and `:3037` | Deliberation is committed to the transcript as `Message.assistant(partial, List.of())` on a mid-stream cancel (`:3024`, `:3039`) — **and the transcript is not recoverable** |

The second is the one criterion 2 is about and it is only reachable through cancellation, which is why
§9.2's test drives cancellation rather than the happy path. One fix in one class covers both.

`reasoningBuffer` is unbounded, like `textBuffer`. That is a deliberate non-decision: a reasoning
summary is the same order of magnitude as an answer, the buffer lives for one iteration, and adding a
cap would add a rule (and a truncation marker, and a decision about what a truncated deliberation
means) to a path with no measured volume. §11 O-5.

### 4.3 `AssistantReasoningDelta`

A sixteenth subtype of the sealed `AgentExecutionEvent`, built exactly as `AssistantTextDelta` is —
`final class`, private constructor from a fluent `Builder`, `delta` (non-null, non-empty) and
`chunkIndex` (`>= 0`), `equals`/`hashCode` over the five fields, `eventName()` returning
`"AssistantReasoningDelta"`, `detailString()` returning `"chunkIndex=… , deltaLength=…"`. **Not a
`record`** (CLAUDE.md, and `AssistantTextDelta` is the reference implementation next door).

The javadoc must carry the one sentence a copy of `AssistantTextDelta`'s would not: *this is the
model's deliberation, not its answer; it is never appended to the assistant message, never persisted,
and never summarised by `AssistantMessageReceived`.* A subscriber that appends both to one accumulator
reproduces the failure the type exists to prevent, one layer out.

#### 4.3.1 A separate chunk-index sequence

`AssistantTextDelta.getChunkIndex()`'s documented contract is *"strictly monotonically increasing
starting at 0"* within a streaming attempt, and `AssistantTextStreamReset` resets it. Sharing
`StreamingEventSink.nextChunkIndex` across the two event types would punch holes in the text-delta
sequence, breaking that contract for a consumer using it to detect loss. So the sink gets
`nextReasoningChunkIndex`, reset alongside `nextChunkIndex` in `onRetry` (`:3164-3178`). Two monotone
sequences, one contract each — which is what §5.3's *"the same ordering contract"* means read
carefully.

The two sequences cannot be interleaved by index. They do not need to be: both arrive on one
`Consumer<AgentExecutionEvent>` in emission order, and cross-node ordering was already best-effort for
text deltas.

Note that the mapper-side `LlmStreamChunk.index` is a **different** counter from the event-side one
(`OrcaAgentExecutor:3134` passes `nextChunkIndex++`, not `chunk.getIndex()`). On the mapper side the
reasoning chunks share the mapper's single ordinal with text chunks, because that field means "the
n-th chunk of this stream" and `TOOL_USE_READY` already departs from strict ordinality (it carries the
provider block index — `AnthropicStreamingMapper:232-234`).

### 4.4 No `AssistantReasoningStreamReset` / `…Completed`

`AssistantTextStreamReset` and `AssistantTextStreamCompleted` mark the **attempt** boundary, not the
text channel's boundary: a retry discards the whole attempt, reasoning included, and a completed
stream ends both channels. A renderer handling both clears both on those two events. Adding two more
subtypes would take the sealed hierarchy from 15 to 18 and multiply §4.6's site list by three, to
carry no information the existing pair does not already carry.

The cost, stated: the two events' **names** now describe less than they do. `AssistantTextStreamReset`
resets the reasoning stream too. That is a misnomer-in-waiting of exactly the kind
`docs/overview/scope-model.md` §6 keeps a list of. It is not renamed here — renaming two published
event types (and their two payload frame names, which cross a node boundary) to carry a rendering
nuance would be a larger and more disruptive change than the feature. Recorded in §10 as a follow-up
and in the two classes' javadoc as a sentence.

### 4.5 `SessionEventRelay` — a rank, not a wider boolean

Today (`:143-160`):

```java
private boolean discardOldestDelta() { … if (isDroppable(it.next())) { it.remove(); return true; } … }
private static boolean isDroppable(AgentExecutionEvent event) { return event instanceof AssistantTextDelta; }
```

The obvious change — `return event instanceof AssistantTextDelta || event instanceof AssistantReasoningDelta;`
— is wrong, and the issue's own sentence says why: *"ahead of text deltas"*. A widened boolean makes
the two **equal**, so `discardOldestDelta` evicts whichever is oldest, and a burst of reasoning deltas
under buffer pressure evicts buffered answer text. Under pressure the choice is meant to go the other
way, and it is not close.

Proposed instead, a three-value rank with a two-pass scan:

```java
/** Lower is sacrificed first. Reasoning is worth least: nothing downstream needs it — no transcript,
 *  no summary, no result field — whereas a text delta builds an assistant message and a structural
 *  frame is how a remote subscriber learns the turn ended. */
private static int evictionRank(AgentExecutionEvent event) {
    if (event instanceof AssistantReasoningDelta) { return RANK_REASONING; }   // 0
    if (event instanceof AssistantTextDelta)      { return RANK_TEXT; }        // 1
    return RANK_STRUCTURAL;                                                    // 2
}
```

`handleOverflow` then scans ascending by rank for the oldest buffered frame whose rank is `<=` the
incoming frame's and below `RANK_STRUCTURAL`; failing that it keeps today's behaviour exactly
(structural incoming displaces the oldest frame; droppable incoming is itself dropped). The resulting
table — with today's two-value behaviour in the middle column so the additivity is checkable:

| Incoming | Buffer holds | Today | After |
|---|---|---|---|
| text delta | text deltas | oldest text delta evicted | unchanged |
| text delta | structural only | incoming dropped | unchanged |
| text delta | reasoning + text | *(n/a)* | **oldest reasoning evicted**, text kept |
| reasoning delta | text deltas only | *(n/a)* | **incoming dropped** — a text delta is never sacrificed for a reasoning delta |
| reasoning delta | reasoning deltas | *(n/a)* | oldest reasoning evicted |
| structural | anything droppable | oldest droppable evicted | **oldest reasoning first**, then oldest text |
| structural | structural only | oldest evicted | unchanged |

Every row that existed before is unchanged, which is the additivity claim and is what §9.4 pins.

`isDroppable` survives as `evictionRank(event) < RANK_STRUCTURAL` so the call at `:121` reads the same.
The class javadoc's overflow paragraph (`:40-49`) is rewritten to state the three ranks; it currently
asserts text deltas are *"the only frames safe to lose"*, which stops being true.

### 4.6 Blast radius — verified at `3dd56df`, 2026-09-10

TASK.md's table was written before three branches landed and its line numbers have drifted. This table
was produced by reading each file. **Two of the eight sites are caught by the compiler, in two
different hierarchies; the issue's "of which the compiler catches one" counts only the sealed class.**

| # | Site | File : line at `3dd56df` | Caught by | If missed |
|---|---|---|---|---|
| 1 | `permits` clause | `agent/stream/AgentExecutionEvent.java:56-70` | **compiler** | compile error |
| 2 | Class javadoc subtype `<ul>` | `agent/stream/AgentExecutionEvent.java:29-45` | nothing | documentation drifts from the `permits` clause it claims to mirror |
| 3 | `LlmStreamChunk.toString()` — `switch` **expression**, no `default` | `llm/streaming/LlmStreamChunk.java:237` | **compiler** | compile error (an enum switch expression must be exhaustive) |
| 4 | `LlmStreamChunk` constructor validation `switch` | `llm/streaming/LlmStreamChunk.java:158-183` (`default -> throw` at `:183`) | nothing | `IllegalStateException` the first time a chunk is **built** |
| 5 | `ChunkAggregator.accept` `switch` | `llm/streaming/ChunkAggregator.java:71-88` (`default -> throw` at `:87`) | nothing | `IllegalStateException` on the first chunk |
| 6 | `AgentEventDispatcher` sibling emit | `agent/impl/orca/AgentEventDispatcher.java:196` (`emitAssistantTextDelta`) | nothing | nothing is ever emitted — silent, and the whole feature is inert |
| 7 | `OrcaAgentExecutor.StreamingEventSink.accept` `switch` | `agent/impl/orca/OrcaAgentExecutor.java:3131-3157` (`default -> throw` at `:3156`) | nothing | `IllegalStateException` on the first chunk |
| 8a | `AgentExecutionEventPayload.flatten` chain | `session/routing/internal/AgentExecutionEventPayload.java:150` (`else` at `:216`) | nothing | **silent** drop crossing a node boundary — `flatten` returns `null` and the relay skips |
| 8b | …its decoder | `AgentExecutionEventPayload.java:300-302` (`default` at `:384-387`) | nothing | **silent** skip on the receiving node |
| 9 | `OutputFormatter.displayEvent` chain | `aimon-cli/repl/OutputFormatter.java:306` (final `else` throws at `:329`) | nothing | `IllegalStateException` in the REPL, on the first reasoning delta |
| 10 | `SessionEventRelay` drop policy | `session/routing/internal/SessionEventRelay.java:158-160` (+ `:40-49` javadoc, `:139` javadoc) | nothing | reasoning deltas are treated as structural and evict answer text under pressure |

Sites 8a/8b are the asymmetric ones — every other omission is loud, and those two are silent. That is
why the round-trip test in §9.3 is not optional.

**Two related findings, neither in scope, both worth writing down.**

- `OutputFormatter.displayEvent` handles **13** of the 15 current subtypes. `InterruptedAt` and
  `RejectedAt` reach the final `else` and throw (`grep -rn "InterruptedAt\|RejectedAt" modules/aimon-cli/src/`
  returns nothing). The chain's javadoc claims the sealed hierarchy *"still forces us to update this
  chain"*; it does not, and two subtypes already prove it. Not fixed here — it is a pre-existing
  defect on paths this change does not touch, and fixing it means deciding what the REPL renders for
  each, which is its own change. Reported in §10.
- Exactly two exhaustive consumers exist outside the declaration site (verified by
  `grep -rn "instanceof AssistantMessageReceived\|instanceof IterationStarted"` over main sources):
  `AgentExecutionEventPayload` and `OutputFormatter`. Everything else that touches
  `AgentExecutionEvent` — `InProcessEventPublisher`, `EventEmitter`, `SessionRouter`, `AimonSessions`,
  `LeasedLiveSession`, `TaskTool`'s parent event sink — is type-agnostic and needs no change.

### 4.7 Scope and lifetime

Nothing here owns a resource, so nothing here closes one. Placement follows
`docs/overview/scope-model.md` §5.1 mechanically:

| New state | Scope | Where it lives |
|---|---|---|
| `reasoningBuffer` | one streaming attempt | `ChunkAggregator`, discarded with the aggregator on `onRetry` |
| `nextReasoningChunkIndex` | one streaming attempt | `StreamingEventSink`, reset in `onRetry` |
| `AssistantReasoningDelta` instances | one iteration | nowhere — emitted and forgotten |
| `reasoningLineOpen` | one REPL render run | `OutputFormatter` (§6.7) |

**Nothing reaches a `SessionRecord`, and that is the point of the whole change.** A reasoning delta
belongs to an *execution* — an iteration within one — and dies with it. It is not turn state, not
session state, and specifically not transcript state. The one place the `SessionRecord` /
`LiveSession` asymmetry would have bitten is the cancel path at `OrcaAgentExecutor:3024,3039`, where a
leak into `peekText()` would put deliberation into the persistent aggregate; §4.2 is the guard and
§9.2 is the test.

Vocabulary, per `docs/overview/glossary.md` §4: these are **execution**-scoped, they are counted per
**iteration**, and the word *turn* appears in this design only where a session's turn is genuinely what
is meant (the cancel path). New names contain neither `Session` nor `AgentSession`, so
`SessionNamingArchitectureTest` is satisfied by construction.

---

## 5. The ask — providers

### 5.1 OpenAI: `reasoning.summary`, and both delta families

**Request** — `OpenAIResponsesRequestFactory` (`:100-142`). `applyReasoningEffort` currently calls
`builder.reasoning(...)` **only** when an effort is present, so a deployment that asks for a summary
without an effort would get no `reasoning` object at all. It becomes `applyReasoning`, building one
`Reasoning` from up to two optional parts and setting it only if at least one landed:

```java
final Reasoning.Builder reasoning = Reasoning.builder();
boolean any = false;
// …existing effort gates unchanged: supportsReasoningEffort, then maySendEffort…
if (effortAccepted) { reasoning.effort(OpenAiReasoningEfforts.toWire(effort)); any = true; }
config.getReasoningSummary().ifPresent(s -> reasoning.summary(OpenAiReasoningSummaries.toWire(s)));
if (any || config.getReasoningSummary().isPresent()) { builder.reasoning(reasoning.build()); }
```

The two existing gates on the effort (`supportsReasoningEffort`, then `maySendEffort`) keep their
semantics exactly — an effort that fails either is still omitted and reported, and now simply does not
contribute to the object. No capability gate is added for the summary: `ModelCapabilities` says nothing
about summaries and inventing a seventh field for an unmeasured axis is the shape
`model-capability-config-key.md` R8 rejects. §11 O-2.

`OpenAiReasoningSummaries` is a new package-private translator beside `OpenAiReasoningEfforts` —
`at.aimon.core.llms.openai.OpenAiReasoningSummary` (`AUTO`, `CONCISE`, `DETAILED`) → the SDK's
`Reasoning.Summary`. A framework enum rather than the SDK type for the reason `AnthropicThinkingMode`
is one: an SDK type on a public config surface makes the vendor SDK part of our API.

**Stream** — `OpenAIResponsesStreamingMapper.onEvent` (`:84-110`) gains two arms under one gate:

```java
} else if (forwardReasoning && event.isReasoningSummaryTextDelta()) {
    emitReasoningDelta(event.asReasoningSummaryTextDelta().delta());
} else if (forwardReasoning && event.isReasoningTextDelta()) {
    emitReasoningDelta(event.asReasoningTextDelta().delta());
```

Both families verified present in `openai-java-core` 4.57.0 (`ResponseReasoningSummaryTextDeltaEvent`,
`ResponseReasoningTextDeltaEvent`, both with a required `String delta()`). Empty deltas are filtered
before emission, as `emitTextDelta` does. `emitReasoningDelta` mirrors `emitTextDelta` — build, feed
the aggregator, hand to the sink — and **never touches `addReasoningTrace`**: the trace on this path is
`encrypted_content`, and putting summary text into a trace would change what is replayed to the model
next iteration. That invariant is stated in the method's javadoc, because it is the kind of thing a
later reader "tidies up".

The `forwardReasoning` flag is threaded `OpenAIConfig` → `OpenAILlmClient.exchangeFor` (`:311-327`) →
`OpenAIResponsesExchange` constructor (`:38-45`) → the mapper it builds in `openStream` (`:60-62`).
`OpenAIChatCompletionsExchange` is untouched: Chat Completions has no reasoning-summary event family.

Gating the *forwarding* as well as the *ask* is deliberate even though OpenAI only sends summary events
when asked. An OpenAI-compatible gateway behind `baseUrl` may send them unasked, and a deployment that
set nothing must see no change (criterion 6).

### 5.2 Anthropic: `display` on the adaptive shape, and a gate on both dialects

This is the half where the design of record's §5.1 is incomplete, and getting it wrong breaks
criterion 6 for real existing deployments.

| Configured `thinkingMode` | Do `thinking_delta` events arrive today? | What the new key does |
|---|---|---|
| `OFF` (the shipped default) | No — no `thinking` parameter is sent | **Nothing reaches it.** One WARN, once per process |
| `EXTENDED` (budgeted) | **Yes** — they arrive and are swallowed at `AnthropicStreamingMapper:195-203` | Opens the sink gate. The request is **byte-identical** — no `display` is added, §5.3 |
| `ADAPTIVE`, or `AUTO` resolving to `ADAPTIVE` | No — `display` defaults to `"omitted"` on this generation | Adds `display` to the request **and** opens the sink gate |

So the forwarding gate is the configuration, not the arrival of the deltas. Wiring it the other way —
"forward whatever arrives" — would turn reasoning text on for every existing `thinkingMode: extended`
deployment on upgrade.

**Request.** `resolveThinking` (`AnthropicLlmClient:513-532`) returns `ThinkingConfigParam.ofAdaptive(
ThinkingConfigAdaptive.builder().build())` at `:529`. That becomes:

```java
final ThinkingConfigAdaptive.Builder adaptive = ThinkingConfigAdaptive.builder();
config.getThinkingDisplay()
      .ifPresent(d -> adaptive.putAdditionalProperty("display", JsonValue.from(d.wireValue())));
return Optional.of(ThinkingConfigParam.ofAdaptive(adaptive.build()));
```

`putAdditionalProperty` is the SDK's own mechanism (`@JsonAnySetter`/`@JsonAnyGetter` on
`ThinkingConfigAdaptive`, verified in the 2.13.0 sources) and it is already how this repository writes
unmodelled request fields — `AnthropicMessageConverter:523`, `OpenAIMessageConverter:117,263`. When the
SDK grows a typed `display`, one line changes.

**Stream.** `AnthropicStreamingMapper.onContentBlockDelta` (`:195-203`): the `delta.isThinking()` arm
keeps appending to the `ThinkingSlot` — the trace round trip is untouched and the block's `thinking`
text still reaches `closeThinkingBlock` — **and additionally**, when the gate is open, emits a
`REASONING_DELTA`. Two consumers of one delta, in that order. The `isSignature()` arm is unchanged: a
signature is not text a person watches. The comment at `:200-203` and the class javadoc's fourth
bullet (`:52-54`), which both say the deltas are *"deliberately not forwarded"* because the chunk kind
does not exist, are rewritten rather than left contradicting the code.

The gate reaches the mapper as a constructor argument from `AnthropicLlmClient:318`, where `config` is
already in scope.

Two consequences of the shared request builder, both stated rather than discovered later:

- `sendMessage(..., cancellation)` routes the non-streaming path through the streaming path with
  `LlmStreamSink.discarding()` (`:271-275`). Reasoning chunks are built and thrown away there. Correct,
  and cheap; noted so nobody reads it as a bug.
- `buildRequest` is shared by both entry points, so `display` is on the blocking path's request too and
  costs the same tokens with nothing to render them. §8 row 6.

### 5.3 Why `display` is not put on the budgeted shape

`ThinkingConfigEnabled` carries `budget_tokens` and `type`. Whether the budgeted dialect accepts a
`display` sibling is **unknown** — `anthropic-thinking-traces.md:165` says `display` defaults to
`"omitted"` *"on newer models"*, which is the adaptive generation, and nothing in this tree has ever
sent the field (that document's §9 U-4, carried forward as `reasoning-model-enablement.md` §9 U-4).
Writing an unknown field into a request whose text already arrives buys nothing and risks a 400 on a
dialect that works today. So: adaptive only, and the operator who writes `thinkingDisplay` under
`EXTENDED` is told once that the word did nothing while the forwarding did (§8 row 3).

---

## 6. Concrete changes, by module and file

Line numbers are anchors read at `3dd56df` on 2026-09-10, for review — not addresses to patch blindly.

### 6.1 `aimon-core` — transport

| File | Change |
|---|---|
| `llm/streaming/LlmStreamChunk.java` | `REASONING_DELTA` in `Kind` (`:41-52`); `reasoningDelta` field + `getReasoningDelta()`; `reasoningDelta(int, String)` factory; builder setter; constructor validation arm and three mirror checks (`:158-183`); `toString()` arm (`:237`); class javadoc's "one of two kinds" list (`:14-20`) corrected — it already undercounts at three |
| `llm/streaming/ChunkAggregator.java` | `reasoningBuffer` field (`:46`); `case REASONING_DELTA` (`:71-88`); `peekReasoningText()`; `accept`'s javadoc (`:56-63`), which currently states *"Only TEXT_DELTA contributes to peekText()"* — still true, now load-bearing and worth the sentence saying why |
| `llm/streaming/LlmStreamSink.java` | Lifecycle javadoc (`:13-18`) gains the reasoning kind |
| `llm/streaming/package-info.java` | `:48`'s event-layer sentence gains the new event |

### 6.2 `aimon-core` — events

| File | Change |
|---|---|
| `agent/stream/AssistantReasoningDelta.java` | **New.** Modelled on `AssistantTextDelta` |
| `agent/stream/AgentExecutionEvent.java` | `permits` (`:56-70`) and the subtype `<ul>` (`:29-45`) |
| `agent/stream/AssistantTextDelta.java` | One javadoc sentence distinguishing it from the new sibling |
| `agent/stream/AssistantTextStreamReset.java`, `AssistantTextStreamCompleted.java` | One javadoc sentence each: these bound the **attempt**, both channels (§4.4) |

### 6.3 `aimon-core` — executor

| File | Change |
|---|---|
| `agent/impl/orca/AgentEventDispatcher.java` | `emitAssistantReasoningDelta(int, String, int)` beside `:196`, same listener gate, one import |
| `agent/impl/orca/OrcaAgentExecutor.java` | `case REASONING_DELTA` in `StreamingEventSink.accept` (`:3131-3157`); `nextReasoningChunkIndex` field + reset in `onRetry` (`:3164-3178`); `StreamingEventSink`'s class javadoc (`:3086-3103`) |

**No `cancellationSignal.checkpoint()` in the reasoning arm.** The text arm checkpoints at `:3137` so a
trip lands promptly; adding a second checkpoint would make a cancel during reasoning unwind through a
different line for no behavioural gain, and the next text delta or `STREAM_END` checkpoint follows
closely. Stated because its absence looks like an oversight.

### 6.4 `aimon-session-routing`

| File | Change |
|---|---|
| `internal/AgentExecutionEventPayload.java` | `flatten` branch after `:153` — `type: "AssistantReasoningDelta"`, keys `"delta"` and `"chunk"`, mirroring the text frame; decoder `case` after `:302`; one import |
| `internal/SessionEventRelay.java` | `evictionRank` + two-pass `handleOverflow` (§4.5); `isDroppable` re-expressed; class javadoc `:40-49`; `discardOldestDelta` javadoc `:138-142` |

**Rolling-upgrade behaviour, both directions.** A new node publishing `"AssistantReasoningDelta"` to an
old node hits the decoder's `default` at `:384-387`, which logs at debug and yields `null` → the frame
is skipped. An old node's payloads are unaffected. So a mixed-version cluster loses reasoning frames on
old nodes and nothing else — which is the same outcome as the drop policy under pressure, and is the
graceful contract `fromPayload`'s javadoc (`:226-231`) already promises. `KEY_CONTEXT` (`"ctx"`) is the
only frozen name in this file (`frozen-names.md:49`) and is untouched.

### 6.5 `aimon-llm-anthropic`

| File | Change |
|---|---|
| `AnthropicThinkingDisplay.java` | **New** enum: `SUMMARIZED("summarized")`, `UPDATES("updates")`, each with `wireValue()`. Absent ≡ unset ≡ off — no `OFF` constant, both because a boxed null already says it (as `thinkingBudgetTokens` does) and because `off` is a YAML 1.1 reserved word, which is the entire reason `AnthropicProviderConfig.ThinkingModeDeserializer` exists |
| `AnthropicConfig.java` | `thinkingDisplay` field (`Optional<AnthropicThinkingDisplay>` getter, beside `:186-207`), builder setter, javadoc naming the dialect asymmetry |
| `AnthropicLlmClient.java` | `resolveThinking:529` writes `display` on the adaptive shape; the gate reaches `new AnthropicStreamingMapper(...)` at `:318`; a new divergence signature for "display set under `OFF`" and one for "display set under `EXTENDED`" (§8 rows 2-3), reported through the existing `reportRecurringDivergence` so each fires once per process |
| `AnthropicStreamingMapper.java` | Constructor gate; `onContentBlockDelta:195-203` also emits; `emitReasoningDelta` beside `emitTextDelta:288-292`; class javadoc bullet `:52-54` and the comment `:200-203` rewritten |

### 6.6 `aimon-llm-openai`

| File | Change |
|---|---|
| `OpenAiReasoningSummary.java`, `OpenAiReasoningSummaries.java` | **New** enum + wire translator, beside `OpenAiReasoningEfforts` |
| `OpenAIConfig.java` | `reasoningSummary` field + `Optional` getter (beside `:179`), builder setter |
| `OpenAIResponsesRequestFactory.java` | `applyReasoningEffort:124-142` → `applyReasoning`; class javadoc's "two differences from Chat" list gains a third |
| `OpenAIResponsesExchange.java` | `forwardReasoning` constructor arg (`:38-45`), passed to the mapper at `:60-62` |
| `OpenAILlmClient.java` | Passes it at `:321-323` |
| `OpenAIResponsesStreamingMapper.java` | Two arms in `onEvent:84-110`; `emitReasoningDelta`; class javadoc |

### 6.7 `aimon-cli`

| File | Change |
|---|---|
| `config/AnthropicProviderConfig.java` | Fourth field `thinkingDisplay` (`AnthropicThinkingDisplay`, bound directly — the CLI holds `aimon-llm-anthropic` at `implementation` scope, which is why `thinkingMode` can be enum-typed here); `isEmpty()`, `equals`, `hashCode`, `toString` |
| `config/OpenAiProviderConfig.java` | **New**, one field `reasoningSummary` (`OpenAiReasoningSummary`), same shape and same `isEmpty()` contract |
| `config/LlmProviderConfig.java` | `openai` sub-block beside `anthropic` (`:17`) |
| `factory/LlmClientFactory.java` | `applyThinking:119-132` gains the fourth key; a new `applyOpenAi` in `openAiConfig:146-169`; **`refuseOpenAiBlock` in the Anthropic branch**, and `refuseAnthropicBlock`'s javadoc corrected — `:177` currently reads *"반대 방향의 짝은 없다: 오늘 `llm.openai` 블록이 존재하지 않으므로 anthropic 분기가 거절할 것이 없다"*, and this change is precisely what makes that false |
| `resources/default-config.yaml` | The fourth commented key in the `anthropic:` block (`:45-62`), and a new commented `openai:` block |
| `repl/OutputFormatter.java` | `displayAssistantReasoningDelta`; a branch in `displayEvent:302-330`; the transition state below |

**REPL rendering.** Reasoning deltas print dim (`ansi().fgBrightBlack()`, the colour this file already
uses for de-emphasised output at `:355`) against the streamed answer's green (`:374`), with
`System.out.print` + `flush` per delta, exactly as `displayAssistantTextDelta` does.

The one thing this needs that a colour alone does not give: a **transition**. Reasoning and answer text
both print inline without newlines, so without one they run together on a line. `OutputFormatter` gains
a single `boolean reasoningLineOpen`:

- a reasoning delta with the flag false first prints `\n` and a dim `[thinking]` marker, then sets it;
- a text delta, a stream reset, or a stream completion with the flag true first prints `\n` and clears it.

This makes `OutputFormatter` **stateful for the first time** (it holds one `final` field today), which
is a change in the class's character and is called out for review rather than slipped in. It is
rendering state — cursor position, essentially — so it belongs to the renderer rather than to
`ReplSession`, whose one piece of state (`latestPendingTurnId`) is documented as living there
*"keeping the rendering layer pure"*, i.e. exactly the opposite direction. The field is written only
from the event-consumer path (`ReplSession.captureAndDispatchEvent:648-653`), one turn at a time; it is
not `volatile` and the javadoc says which thread owns it.

This is the whole of the CLI's rendering work. §11 G-6 of the design of record puts collapsing,
toggling and a `/thinking` command out of scope, and they stay out.

### 6.8 `aimon-spring-boot-starter`

| File | Change |
|---|---|
| `AimonProperties.java` | `Llm.Anthropic` (`:1362-1421`) gains `thinkingDisplay` as a **`String`**; a new nested `Llm.OpenAi` with `reasoningSummary` as a **`String`** and an `isEmpty()`; `Llm` gains the `openai` accessor; `LLM_OPENAI` constant beside `LLM_ANTHROPIC` |
| `AimonLlmAutoConfiguration.java` | `applyThinking:215+` gains the fourth key, folded onto the enum **inside** the `@ConditionalOnClass`-guarded slice; `openAiConfig` gains the summary, folded the same way inside its slice; `refuseOpenAiBlock` on the **enclosing** class beside `refuseAnthropicBlock:125-131`, called from the Anthropic branch |
| `resources/META-INF/additional-spring-configuration-metadata.json` | Value hints for `aimon.llm.anthropic.thinking-display` and `aimon.llm.openai.reasoning-summary` |

**Both new properties are `String` on this surface, and that asymmetry with the CLI is load-bearing,
not an oversight.** `AimonProperties.Llm.Anthropic`'s javadoc (`:1342-1354`) records the measurement:
both vendor modules are `compileOnly` here, and Spring's `JavaBeanBinder` calls
`getDeclaredMethods()` on every bean it binds, which resolves the signatures of the methods it finds —
so a vendor-typed **accessor** throws `NoClassDefFoundError` (an `Error`, which Boot's `BindException`
wrapping does not catch) in a deployment carrying only the other vendor's module. That reasoning
applies unchanged to `OpenAiReasoningSummary`. The fold is over `values()` in both cases so the two
surfaces cannot come to accept different spellings.

**The refusal becomes symmetric,** and that is a direct consequence of N-1 opening `llm.openai.*`. Today
one direction exists because only one vendor block does. After this change: the OpenAI branch refuses a
populated `llm.anthropic` (as now), and the Anthropic branch refuses a populated `llm.openai` (new),
each from **inside the branch that actually ran** — the constraint `refuseAnthropicBlock`'s javadoc
states, and the one `L-3` warns turns valid configuration into a boot failure when violated. Both
refusal bodies read boxed fields for null through `isEmpty()` and load no vendor class, which is what
lets the OpenAI one sit on the enclosing class.

---

## 7. Alternatives rejected

| # | Alternative | Why not |
|---|---|---|
| **A1** | **Reuse `AssistantTextDelta` with an `isReasoning` flag.** | D-9, and the issue rejects it. Every consumer that forgets the flag renders or persists deliberation as the answer, and the transcript one is not recoverable. A flag makes the safe behaviour opt-in for every future consumer; a separate type makes it structural. |
| **A2** | **Reuse `LlmStreamChunk`'s `textDelta` field for the new kind.** | A1 one layer down. `getTextDelta()` would return deliberation on a reasoning chunk, and the callers that read it without checking `getKind()` are exactly the ones that would not be updated. |
| **A3** | **Ship the transport (F-4) now and the ask later.** | D-8, and the issue rejects it explicitly. An always-empty channel is untestable end to end, and the first bug report is about the feature rather than about the missing ask. Made worse by §5.2's finding: the Anthropic budgeted dialect would make the channel *non*-empty for one configuration and empty for the rest, which is the hardest possible shape to diagnose. |
| **A4** | **Forward whatever the provider sends; no forwarding gate.** | Breaks criterion 6 on the Anthropic budgeted dialect, where `thinking_delta` already arrives (§5.2), and on any OpenAI-compatible gateway that emits summary events unasked. |
| **A5** | **One neutral boolean key read by both providers.** | §3.4. Would be shared by the rule, and is a follow-up rather than this round's shape — a boolean cannot express `concise` / `detailed` / `updates`, and widening it later is a breaking type change on a config key. |
| **A6** | **A neutral key *and* the two vendor keys.** | The R2 shape from `model-capability-config-key.md` §3: two user concepts for one intent, plus a precedence rule for when both are written, on a feature with no users. |
| **A7** | **A seventh `ModelCapabilities` field** — "does this model produce reasoning summaries". | R8's shape: a field nobody has measured, whose fail-open value is a guess, added to a published SPI so a gateway deployment has one more cell to fill. Nothing goes wrong today when the ask reaches a model that ignores it — the channel is quiet, exactly as when the key is unset. §11 O-2. |
| **A8** | **Widen `isDroppable` to `A \|\| B`.** | Makes the two ranks equal, so a reasoning burst evicts answer text: the opposite of the ordering the issue asks for (§4.5). |
| **A9** | **Three new events (delta + reasoning reset + reasoning completed).** | T-4. Triples the sealed blast radius to carry nothing the existing attempt-boundary pair does not already carry. |
| **A10** | **Put the reasoning text into `ReasoningTrace` as well**, so it survives the turn. | Changes what is replayed to the model. On Anthropic the trace is the signed block and modifying it is a documented 400; on OpenAI the trace is `encrypted_content` and a summary is not a substitute for it. The two payloads answer different questions and must not be merged. |
| **A11** | **Render reasoning through `AssistantMessageReceived`** (the existing dim inter-iteration summary). | That event is built from the assistant message and is summarised into the result; reasoning is neither. It would also arrive after the deliberation finished, which is the one thing this feature exists to avoid. |
| **A12** | **An `aimon-cli` `/thinking` toggle, collapsing, or a scrollback region.** | §11 G-6 of the design of record puts it out of scope, and the issue repeats it. The channel ships; what a terminal does with it beyond dimmed, distinct text is a CLI decision with no core dependency. |

---

## 8. Failure modes

| # | Shape | Disposition |
|---|---|---|
| 1 | **Reasoning text reaches the transcript as the assistant's answer** (the failure criterion 2 exists to prevent) | Structurally prevented: `REASONING_DELTA` never touches `textBuffer`, so `peekText()` cannot contain it, so `OrcaAgentExecutor:3024,3039` cannot commit it. Pinned by §9.2, which drives the cancel path rather than the happy path — on the happy path the guard is invisible |
| 2 | `thinkingDisplay` set under the shipped default `thinkingMode: OFF` | Nothing reaches it — no `thinking` parameter means no `display` and no `thinking_delta`. One WARN per process, the same shape #61 introduced for `reasoningEffort` under `OFF` (`reportInertEffort`, `AnthropicLlmClient:690`). Not a refusal: the remedy is a second key, and refusing would make a two-key configuration order-dependent |
| 3 | `thinkingDisplay` set under `thinkingMode: EXTENDED` | The forwarding works (deltas already arrive) but the *word* reached nothing — no `display` is sent on the budgeted shape (§5.3). One WARN saying exactly that, so an operator does not read working output as proof the field went out |
| 4 | The Anthropic server rejects the `display` value, or the field itself, with a 400 | The turn fails. Contained by three things: the key is opt-in and unset by default, so only a deployment that asked can reach it; the value is operator-settable, so a wrong constant is corrected in yaml rather than in a release (this is why §3.3 chose an enum over a boolean); and the divergence reporter names the key. **Unmeasured — §11 O-1** |
| 5 | An OpenAI model ignores `reasoning.summary` | Quiet: no summary events, no chunks, empty channel. Indistinguishable from the key being unset, which is honest — the model did not produce one. No capability gate (A7) |
| 6 | The ask costs tokens on non-streaming calls that render nothing | Real, on both providers: `buildRequest` / `applyReasoning` are shared by the blocking and streaming paths, and `AnthropicLlmClient:271-275` even routes a cancellable non-streaming call through the streaming path with a discarding sink. Named as an operational cost of an opt-in key rather than engineered away — splitting the request builders per path would duplicate every other decision in them |
| 7 | A new `Kind` reaches an unpatched `switch` | `IllegalStateException` at `ChunkAggregator:87`, `OrcaAgentExecutor:3156`, or `LlmStreamChunk:183` — loud, on the first chunk. Two more sites fail at **compile** time (`LlmStreamChunk:237`, the `permits` clause), which is why §4.6 counts eight sites and two catchers |
| 8 | A new subtype reaches an unpatched consumer | `IllegalStateException` at `OutputFormatter:329`; **or**, at `AgentExecutionEventPayload`, a silent drop crossing a node boundary. That asymmetry is why §9.3 tests the round trip rather than trusting review |
| 9 | Reasoning frames flood the relay's 1024-slot remote buffer | They are sacrificed first (§4.5) and counted in `getDroppedEventCount()`, reported at `close()`. The drop-order claim rests on an inference about volume, not a measurement — `reasoning-model-enablement.md` §9 U-5 says so, and it stays open. If the inference is wrong the cost is only ordering |
| 10 | Reasoning text crosses a node boundary through a shared signal bus (Redis / Postgres / Mongo) | Same exposure answer text already has — the frames go through `SessionSignalBus` exactly as `AssistantTextDelta` does, and the payload is not encrypted at either. Named because deliberation is more sensitive than an answer in some deployments, and because a deployment can decline it by leaving the key unset |
| 11 | An out-of-tree consumer switches exhaustively over the sealed hierarchy | Source-breaking. `docs/project/api-stability.md` §5 permits it at `0.x`; **CHANGELOG line required**, and no adapter and no deprecation window, which is this repository's practice |
| 12 | A mixed-version cluster during a rolling upgrade | Old nodes skip the unknown frame type at `AgentExecutionEventPayload:384-387` (debug log, `yield null`) — the graceful contract `fromPayload` already promises. Reasoning is invisible on old nodes; nothing else changes |
| 13 | A retry discards an attempt mid-reasoning | `onRetry` swaps the aggregator (dropping `reasoningBuffer`) and resets both chunk counters; the REPL closes the reasoning line on the reset banner. Same lifecycle as text, by construction |

---

## 9. Test strategy

Existing suites that must stay green unchanged are the additivity claim: `ChunkAggregatorTest`,
`LlmStreamChunkTest`, `BufferingStreamSinkTest`, `AgentExecutionEventPayloadTest`,
`SessionEventRelayOverflowTest`, `OutputFormatterTest`, `ReplSessionStreamingTest`,
`AnthropicThinkingRequestTest`, `AnthropicThinkingDialectTest`,
`OpenAIResponsesRequestFactoryTest`, `AimonDocumentedPropertiesTest`, `AimonPropertiesValidationTest`.

### 9.1 The chunk and the aggregator

`LlmStreamChunkTest`: the factory rejects an empty delta; the built chunk carries `getReasoningDelta()`
and an **empty** `getTextDelta()`; a builder that sets both `textDelta` and `reasoningDelta` is rejected
from either side; `toString()` names the kind.

`ChunkAggregatorTest`: a `REASONING_DELTA` leaves `peekText()` **empty**; it accumulates into
`peekReasoningText()`; it counts in `chunksAccepted()`; after `STREAM_END`, `toLlmResponse().getText()`
is exactly the text deltas and nothing else; a `REASONING_DELTA` after `STREAM_END` throws, like every
other kind.

### 9.2 The cancellation test — criterion 2's own test

> *"Test the cancellation path specifically — a test that only checks the happy path does not cover the
> failure this criterion exists to prevent."*

`OrcaAgentExecutorLlmCancellationTest` already has the seam: `streamingLlmCancelledPreservesPrefixAndInterrupts`
(`:69-85`) drives a `StreamingCancelLlmClient` that emits a partial and then throws
`LlmCallCancelledException`, and asserts the preserved prefix reaches `result.getConversationHistory()`.

A sibling test drives the same client extended to emit, in order: `TEXT_DELTA("Answer part one. ")`,
`REASONING_DELTA("SECRET-DELIBERATION")`, `TEXT_DELTA("Answer part two.")`, then the cancellation. It
asserts, on `result.getConversationHistory()`:

1. some message contains `"Answer part one. Answer part two."` — the prefix still survives; and
2. **no** message contains `"SECRET-DELIBERATION"` — the deliberation did not.

Assertion 2 is the whole point and it is written as a negative over the entire history rather than over
one message, because the failure it guards is "it ended up *somewhere* persistent".

The mirror test on the other cancel arm (`CancelledExecutionException` from the sink checkpoint at
`:3013-3027`, versus `LlmCallCancelledException` at `:3028-3042`) is worth having too: the two arms
duplicate the `peekText()` / `addMessage` pair and a future edit could fix one and not the other.

### 9.3 Cross-node round trip

`AgentExecutionEventPayloadTest`: an `AssistantReasoningDelta` survives `toPayload` → `fromPayload`
with `delta`, `chunkIndex`, `iteration`, `agentRuntimeId` and turn stamp intact; and — the site's real
hazard — `toPayload` returns a **non-null** map for it, so it is not the silent drop at `:216-222`.
A companion assertion that an unknown type name still yields `Optional.empty()` pins the old-node half
of §8 row 12.

### 9.4 Drop order

`SessionEventRelayOverflowTest`: fill the buffer with structural frames plus one text delta and one
reasoning delta, then push each of the three incoming kinds and assert the §4.5 table row by row. The
two rows that matter most are the ones a widened boolean would get wrong: an incoming **text** delta
evicts the buffered **reasoning** delta and leaves the buffered text delta; an incoming **reasoning**
delta with only text deltas buffered is **itself** dropped.

### 9.5 The two asks

`AnthropicThinkingRequestTest` (extended): `thinkingDisplay` unset → the adaptive request body is
**byte-identical** to today's (this is criterion 6's Anthropic half and is asserted on the serialised
params, not on a getter); set under `ADAPTIVE` → `display` is present with the configured wire value;
set under `EXTENDED` → the request is byte-identical **and** one divergence is reported; set under
`OFF` → byte-identical and a different divergence is reported.

`AnthropicStreamingReasoningTest` (extended): with the gate closed, a fixture stream carrying
`thinking_delta` events reaches the sink with **zero** `REASONING_DELTA` chunks and still produces its
signed trace (the existing assertions, unchanged); with the gate open, the same stream produces the
reasoning chunks **and** the same trace. The pair is what proves the trace path and the sink path are
independent.

`OpenAIResponsesRequestFactoryTest` (extended): summary unset and effort unset → no `reasoning` object
at all (today's behaviour); summary unset and effort set → today's object exactly; summary set and
effort unset → a `reasoning` object carrying only `summary` (the case the old `applyReasoningEffort`
shape could not produce); both set → both fields. Plus: an effort that fails `maySendEffort` is still
omitted while the summary is still sent.

A new `OpenAIResponsesReasoningStreamTest` drives a fixture stream containing both
`response.reasoning_summary_text.delta` and `response.reasoning_text.delta`, and asserts zero chunks
with the gate closed and one per delta with it open.

### 9.6 Configuration surfaces

CLI (`LlmClientFactoryTest`, `CliConfigLoaderTest`): each new key reaches its vendor config, spelled
in camelCase and case-insensitively in its value; a populated `llm.openai` block under
`provider: anthropic` fails startup by name, and the existing reverse case still does; an **empty**
block of either kind does not (the `isEmpty()` contract).

Starter (`AimonAutoConfigurationTest`, `AimonPropertiesValidationTest`,
`AimonConfigurationMetadataTest`, `AimonDocumentedPropertiesTest`): the same four claims, in
kebab-case; the metadata hints exist; and — the one that catches documentation drift — every
`aimon.llm.openai.*` / `aimon.llm.anthropic.*` key written into a `docs/**` yaml block is a real
property with the stated default, which `AimonDocumentedPropertiesTest` already enforces over
`docs/**/*.md`.

### 9.7 The REPL

`OutputFormatterTest`: a reasoning delta renders dim and distinct from a text delta; the
reasoning → text transition emits exactly one newline and does not duplicate it on the following
deltas; a stream reset and a stream completion each close an open reasoning line;
`displayEvent(new AssistantReasoningDelta(...))` does **not** throw (criterion: `OutputFormatter:329`).

`ReplSessionStreamingTest`'s scripted sequence — whose comment claims it *"covers every branch in
OutputFormatter.displayEvent"* — gains the new event, so the claim stays true for one more subtype.

### 9.8 The "sets nothing sees byte-identical behaviour" claim (criterion 6)

Verified rather than asserted, at three altitudes:

1. **Request bytes.** The two request tests in §9.5 assert byte-identity of the serialised params with
   the keys unset, on both providers. This is the strongest form and is where the claim actually lives.
2. **Config defaults.** A test that an `AnthropicConfig` / `OpenAIConfig` built without the new setter
   is `equals` to one built before — i.e. the new field's default is genuinely absent, not a
   manufactured value. (`AnthropicConfig.temperature` is the cautionary precedent: it shipped a
   manufactured `0.0` and that cost a release.)
3. **Event stream.** With both gates closed, the sequence of `AgentExecutionEvent`s a scripted
   streaming turn produces is unchanged — the same assertion `ReplSessionStreamingTest` already makes,
   which goes red if a reasoning event ever appears unasked.

No test in `./gradlew test` makes a billed call; the live tier stays where F-8 left it.

---

## 10. Documentation obligations

| Document | What |
|---|---|
| `CHANGELOG.md` `## [Unreleased]` | The feature, **and the sealed-hierarchy source break stated as such** — a sixteenth `AgentExecutionEvent` subtype breaks any out-of-tree exhaustive consumer, permitted at `0.x` by `api-stability.md` §5, no adapter and no deprecation window. Also: the two new configuration keys, the `TeardownPhase`-style behaviour note that the relay's drop order changed, and that a deployment setting nothing is unchanged |
| `docs/design/llm/anthropic-thinking-traces.md` | **§8 F-4 and F-7 struck** (`~~F-4~~ … **DONE**`, the format F-1/F-2 already use), each naming this issue and this design. A11's *"moot on this SDK"* line gains a correction: the SDK still has no `display` field, and `putAdditionalProperty` is how the round got past that. Not translated — `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1) |
| `docs/design/llm/openai-responses-path.md` | **§7 F-5 struck**, same format, noting that both delta families are forwarded rather than the summary alone |
| `docs/design/llm/streaming.md` | §4.1's three-kind table becomes four; §4.2's lifecycle contract line (`:103`) admits reasoning deltas; the §3 diagram line (`:53`) names the new event. Korean-canonical, no `.en.md` pair |
| `docs/design/session/routing.md` | `:315`'s overflow-policy paragraph — currently *"`AssistantTextDelta` 는 양이 많고…"* — states the three ranks |
| `docs/design/agent-execution/orca-executor.md` | `:325`'s chunk-invariant sentence gains the new kind |
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` **and `.en.md`** | The two new keys in the yaml reference, in the same commit, with `source_commit: 3dd56df` on the translation — `3dd56df` is the canonical file's last commit and therefore the one *immediately before* this change (verified: `git log -1 --format=%h -- <file>`). The structure axes must match, so the two files gain the same number of headings, table rows, list items and fences |
| `docs/getting-started/embedding-agent-in-application.md` **and `.en.md`** | The event enumeration at `:685` / `:719` gains `AssistantReasoningDelta`. Same `source_commit` rule |
| `docs/backlog/llm-config-surface-open-items.md` | `L-2` gains a line: `llm.openai.*` is no longer an empty namespace — this round opened it, and the person who takes `responsesApiEnabled` is adding a second key to an existing block rather than creating one. `L-3`'s "two remaining cases" is unaffected but the refusal table it references now has a fourth row |
| `docs/design/llm/reasoning-model-enablement.md` | **Not rewritten.** §12's Phase 3 row is the acceptance list this change is measured against and it stays as written |
| `build/deviations.md` | §2.2's seven departures, with **D-a** (budgeted deltas already arrive) and **D-b** (eight sites, two compiler catches) flagged as the two a reader of §5 would otherwise get wrong |

Follow-ups to file rather than fix (each with the trigger that would make it worth doing):

- **A neutral `llm.streamReasoning` umbrella** (§3.4) — when a third provider arrives, or when someone
  asks to move a deployment between providers without rewriting the key.
- **`model.reasoningSummary` in agent frontmatter** (§3.5) — when someone wants per-agent control of
  the token cost.
- **`AssistantTextStreamReset` / `…Completed` now bound both channels** (§4.4) — a naming follow-up for
  `scope-model.md` §6's misnomer list, not a rename now.
- **`OutputFormatter.displayEvent` throws for `InterruptedAt` and `RejectedAt`** (§4.6) — a pre-existing
  defect on paths this change does not touch.
- **Collapsing / toggling / `/thinking`** — §11 G-6 of the design of record, unchanged.

Gates: `python3 scripts/check-doc-links.py`, `check-translation-staleness.py`,
`check-translation-structure.py` (all three green at `3dd56df`: 233 files / 2300 links, 32 pairs up to
date, 32 structurally identical), then `./gradlew format` and `./gradlew checkAll`.

---

## 11. Open questions and what has not been measured

Stated as open questions rather than assumed. Items marked **O** need an answer before or during
implementation; items marked **U** are measurements this round does not make, in the form the sibling
documents use.

- **O-1 — the `display` wire values are unverified, and one of them is on our request.** `F-7` names
  `"summarized"` and `"updates"`; `anthropic-thinking-traces.md:165` names `"omitted"` as the default.
  Nothing in this tree has ever sent the field, the SDK does not model it, and no API call was made for
  this document. If the accepted spelling differs, an opted-in adaptive request 400s (§8 row 4). The
  design's answer is to make the value operator-settable so a wrong constant is a yaml edit rather than
  a release — but **whoever implements this should send one real request before merging if a key is
  available**, and record the result either way.
- **O-2 — whether `display` is legal on `ThinkingConfigEnabled` (the budgeted shape) is unknown.**
  §5.3 assumes not and sends nothing there. If it *is* legal, a budgeted deployment could ask for a
  richer form of text it already receives — a small missed opportunity, not a defect.
- **O-3 — whether `reasoning.summary` needs an `include` entry.** The Responses request already sets
  `include: [reasoning.encrypted_content]` (`OpenAIResponsesRequestFactory:107`). `ResponseIncludable.Known`
  in 4.57.0 has **no** reasoning-summary constant (verified by `javap`), which suggests the summary
  arrives without one — but that is an inference from an absent enum constant, not a measurement.
- **O-4 — should `AssistantReasoningDelta` cross a node boundary at all?** This design says yes: a
  remote subscriber (a web UI on another node) is precisely who wants to watch thinking, and the frame
  is the first thing sacrificed under pressure. The alternative — local fan-out only — would make the
  feature CLI-only for no stated reason. Raised because §8 row 10 (deliberation on a shared bus) is a
  real deployment consideration and the answer might differ for someone.
- **O-5 — `ChunkAggregator.reasoningBuffer` has no production reader.** The design of record calls for
  it (§5.2, "a second buffer"), it makes the §9.1 invariant positively assertable, and it is the seam a
  future "what was it thinking when I interrupted it" would use. It is still, today, a `StringBuilder`
  whose only readers are tests. Dropping it and making the aggregator arm a documented no-op — the
  shape `TOOL_USE_READY` already has — would satisfy criterion 2 equally well through the negative
  assertion alone. **Recommendation: keep it**, but this is a judgement and a reviewer may reasonably
  take the other side.
- **O-6 — the `[thinking]` marker's wording and whether it is emitted at all.** §6.7 proposes one dim
  marker line at the start of a reasoning run because a colour alone is not distinguishable on a
  monochrome terminal (`settings.isColorOutput() == false`), which is a supported mode here. That is a
  rendering choice inside G-6's boundary but close to its edge.
- **U-1 — no API call was made for this document**, on either provider. Every wire claim above is
  either read out of a vendored SDK's sources (`anthropic-java-core` 2.13.0,
  `openai-java-core` 4.57.0) or quoted from a prior design that states its own evidence.
- **U-2 — reasoning-delta volume is unmeasured**, so §4.5's rank order rests on the same inference
  `reasoning-model-enablement.md` §9 U-5 records. If it is wrong, the cost is ordering only.
- **U-3 — the token cost of either ask is unmeasured.** Both are documented as costing output tokens;
  neither has been billed from this codebase. This is why both keys are off by default and why §8 row 6
  names the non-streaming waste rather than hiding it.
- **U-4 — interleaving fidelity.** `reasoning-model-enablement.md` §9 U-6 notes that `Message`
  concatenates all text into one string, so a turn with thinking between two text blocks replays with
  the thinking blocks consecutive. This change makes that shape **visible for the first time** — a
  watcher will see the true interleaving live and the replayed order afterwards. It does not fix it
  (that is G-3), and it may be what finally measures how often the shape occurs.

---

## 12. Where the implementation departed from this document

**Everything above §12 is the document as approved.** It is not rewritten to match the code, so a
reader can see what was decided in advance and what was decided at the keyboard. This section is the
second list. The full form, with the reasoning, is `build/deviations.md` in the task record; the nine
below are the ones a reader of §§1-11 would otherwise get wrong. Four came from the design review's
non-blocking findings and are marked *(review)*.

| # | Where | What the code does instead |
|---|---|---|
| **1** | §6.5 | The two new Anthropic divergences go through **`reportDivergence`**, not `reportRecurringDivergence`. §8 rows 2-3 describe them as firing "once per process" and only the first reporter does that — the second reports on the 1st, 10th, 100th … occurrence. Both conditions are properties of the configuration, not of the traffic. *(review)* |
| **2** | §6.7 | Of the rule's three triggers, **only the text delta prints the newline.** `displayAssistantTextStreamCompleted` already prints an unconditional one and `displayAssistantTextStreamReset` already prefixes its banner with one — with a comment warning against a second. Those two paths clear the flag without printing, or every stream that ends mid-thought leaves a stray blank line. *(review)* |
| **3** | §4.6 | The site list is **nine, not eight**, and the ninth is a third in-tree catcher: `AgentExecutionEventTest.permitsExactly15Subtypes` asserts the count under a matching display name and method name. All three moved to 16. It was the only failure in the first full test run after the sealed hierarchy grew — which is also the cheapest evidence for criterion 6. *(review)* |
| **4** | §8 | A **row 5b**: a deployment that sets `llm.openai.reasoningSummary` and then runs a model routed to Chat Completions gets one WARN, in two variants. Row 5 covers a model that *received* the ask and ignored it, which is honestly quiet; this one is the "configured and never read" state, which is not. *(review)* |
| **5** | §5.1 | `applyReasoning`'s guard is `any` alone. Both contributors set it, so the pseudo-code's `any \|\| summary.isPresent()` double-counts. Same behaviour. |
| **6** | §5.1 / §7 A7 | Both defer the un-taken OpenAI capability gate to "§11 O-2", but that item is about Anthropic's `ThinkingConfigEnabled`; no O-item covers the OpenAI decision. It is recorded in `applyReasoning`'s javadoc and pinned by a test instead. *(review)* |
| **7** | §9.8 | `OpenAIConfig` defines no `equals`, so its half of altitude 2 is asserted as an empty getter. The request-byte assertions of altitude 1 carry the claim on that provider. `AnthropicConfig` does define `equals` and is asserted as written. |
| **8** | §6.1, §8 row 2 | Two anchors had drifted — the `Kind` enum is at `LlmStreamChunk:43-54` and `reportInertEffort` starts at `AnthropicLlmClient:685`. Inside this document's own "anchors, not addresses" caveat; left as written. *(review)* |
| **9** | §4.5, §9.3 | `discardOldestDelta` became `discardOldestDroppable(int)` (`isDroppable` survives as the section says). `AgentExecutionEventPayloadTest` gained a `samplesCoverEveryPermittedSubtype` assertion the document did not ask for, because this codec is the one site whose omission is silent. |
| **10** | §8 | The failure table is missing a row, added below rather than in the frozen body. |

### 12.1 A row §8 does not have — `thinkingDisplay` with `reasoningEffort: none`

| # | Shape | Disposition |
|---|---|---|
| **2b** | `thinkingDisplay` set together with `reasoningEffort: none` | Nothing reaches it — `resolveThinking` returns on that value before either display reporter runs — and, unlike §8 rows 2 and 3, **nothing is said**. Deliberate, and the same exclusion `reportInertEffort` already makes for the same value: `NONE` is the operator saying *do not reason*, so there is no deliberation for a display to show, and telling them to turn thinking on would be advice in the wrong direction. The three conditions this round does report are ones where two settings **disagree**; this is a pair that agrees. Recorded because it is a fourth "configured and never read" state and the reader of §8 should not have to infer it from the code |

Note the boundary this row sits on. It is **not** the same as the AUTO-against-an-unnamed-model case,
which is also silent on the display axis but is not silent at all — `resolveDialect` already reports
`thinkingDialectUnknown@<model>`, so the operator is told that AUTO answered nothing and therefore
that the rider on it did too. Row 2b has no adjacent warning of any kind, which is why it earns a row
and that case does not.

**§11 O-1 was not closed, and it asked to be.** It says *"whoever implements this should send one real
request before merging if a key is available, and record the result either way"*. No key was
available and **no billed call was made on either provider**, so the two `display` wire values are
exactly as unverified as this document found them — and one of them now rides an opted-in adaptive
request. The mitigation §3.3 chose still stands (the value is operator-settable, so a wrong constant
is a yaml edit rather than a release), and the item is promoted rather than left here:
[`../../backlog/reasoning-delta-stream-open-items.md`](../../backlog/reasoning-delta-stream-open-items.md)
**RD-1**, with O-3 as **RD-2** and four more. Per `docs/backlog/README.md`'s first rule, that file —
not §11 — is now the canonical record of what is open.
