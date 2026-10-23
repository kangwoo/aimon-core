---
translated_from: docs/features/llm/llm-usage-metering.md
source_commit: 96839e2
---

# LLM Usage Metering Guide

> The metadata + recorder guide for measuring LLM call volume per component, per feature and per user

## 1. Overview

Two components exist so that the token usage an `LlmClient` produces can be attributed — where it came from, who caused it and why.

| Component | Role |
|----------|------|
| `LlmCallMetadata` | An immutable value object holding the call's attribution (component/feature/principal/traceId/tags) |
| `LlmUsageRecorder` | The sink interface that receives a call's outcome (provider/model/TokenUsage + metadata) |
| `MeteringLlmClient` | An `LlmClient` decorator. Intercepts the response and emits an event to the recorder |
| `InMemoryLlmUsageRecorder` | The default in-memory implementation, following the multi-instance-ready principle |
| `LlmUsageEvent` | One call's measurements (applied tier, cache counters, actual model, completion time and latency) — §4 |
| `ServiceTier` | A provider-neutral representation of the processing tier the provider actually applied |

The provider implementations (`OpenAILlmClient`, `AnthropicLlmClient`) do not need to know that metering exists. Wrapping them in the decorator is enough.

IMPORTANT: `TokenUsage.getReasoningTokens()` is **recorded and deliberately not priced.** Reasoning
tokens are a *subset* of the completion tokens rather than an addition to them (OpenAI reports them
inside `output_tokens_details`), so `ModelPrice.costOf`'s completion term has already priced them —
adding them again here would bill them twice. A recorder receives the whole `TokenUsage`, so the
field simply arrives.

`provider` is the vendor name (`"OpenAI"`, `"Anthropic"`) and `model` is the model the call **actually used** — the name the request's `LlmModel` gave, or the client's default (`LlmClient.getDefaultModelName()`) when it gave none.

## 2. Getting started

### 2.1 Wiring

```java
InMemoryLlmUsageRecorder recorder = new InMemoryLlmUsageRecorder();
LlmClient client = new MeteringLlmClient(new OpenAILlmClient(apiKey), recorder);

// from here on, inject client into OrcaAgentExecutor, SubagentExecutor, the wiki components and so on
```

Using `LlmUsageRecorder.NOOP` lets you keep the decorator chain in place while metering is switched off.

### 2.2 Attaching attribution at the call site

```java
LlmCallMetadata meta = LlmCallMetadata.builder()
        .component("orca-agent")
        .feature("react-loop")
        .traceId(conversationId)
        .principal(currentUser)
        .tag("tenant", "acme")
        .build();

client.sendMessage(systemPrompt, messages, tools, model, meta);
```

The plain `sendMessage(...)` overload that omits `metadata` works fine too — `LlmCallMetadata.empty()` is applied automatically.

### 2.3 Reading the aggregates

```java
for (LlmUsageSnapshot snap : recorder.snapshot()) {
    System.out.printf("%s → calls=%d, totalTokens=%d%n",
            snap.getKey(), snap.getCallCount(), snap.getTotalUsage().getTotalTokens());
}
```

`LlmUsageKey` aggregates along five axes: provider/model + (component, feature, principal). Tags are left out of the key to keep cardinality from exploding; if you need per-event analysis, implement your own recorder.

## 3. Framework integration

The framework fills the metadata in automatically along the main call paths. A caller only overrides the fields it cares about.

### 3.1 The Orca agent

`OrcaAgentExecutionRequest` carries an `llmCallMetadata` field.

```java
OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder()
        .userInput("…")
        .llmCallMetadata(LlmCallMetadata.builder()
                .principal(currentUser)
                .traceId(externalRequestId)
                .tag("tenant", "acme")
                .build())
        .build();

executor.execute(context, request);
```

`OrcaAgentExecutor.execute` fills in the following fields when the caller left them empty (anything already set is preserved):

| Field | Automatic value |
|------|--------|
| `component` | `agent.getName()` |
| `feature` | `"react-loop"` |
| `traceId` | `conversationId.value()` |

`principal` is the one exception. A `principal(…)` on the request **wins** over the metadata's `principal`, because it
is the identity the turn runs as. The metadata's value is used only when the request has no principal. Compaction
summary calls are booked to the same principal.

This effective metadata applies to every LLM call in the ReAct loop, and propagates into sub-executions through the ToolContext.

### 3.2 Subagents

When Orca runs a subagent through TaskTool, the parent metadata propagates automatically as `SubagentLaunchContext.parentLlmCallMetadata`. `DefaultSubagentExecutor` builds its effective metadata like this:

| Field | Value |
|------|---|
| `component` | `subagent.getName()` — **the subagent always wins** (overridden even if the parent set it) |
| `feature` | `"subagent"` — **always wins** |
| `traceId` | inherits the parent's traceId as-is |
| `principal` | `SubagentExecutionRequest.principal` when set, otherwise inherits the parent's value |
| `tags` | inherits the parent's values |

> **The merge direction is the opposite of Orca's**: Orca is "caller-supplied wins, auto-derived is the fallback" (except `principal`, §3.1), whereas a subagent is "the subagent's identity wins, the parent is the fallback". The asymmetry is deliberate — a sub-execution's token usage is only meaningful to track when it is aggregated under the name of the subagent that actually ran.

The same rules apply when you build a `SubagentExecutionRequest` and invoke it yourself. If you want to force a different component/feature tagging, it is cleaner to put another decorator in front of `MeteringLlmClient`.

### 3.3 Wiki

`LlmRerankSearchStrategy` and `LlmWikiPageGenerator` are static components that run without a call context, so their attribution is specified once, at builder time.

```java
WikiSearchStrategy strategy = LlmRerankSearchStrategy.builder()
        .llmClient(llmClient)
        .modelConfig(LlmModel.builder().name("claude-haiku-4-5").build())
        .llmCallMetadata(LlmCallMetadata.builder()
                .component("wiki-rerank")
                .feature("search")
                .build())
        .build();
```

If you set nothing, these defaults apply:

| Component | Default component | Default feature |
|----------|---------------|--------------|
| `LlmRerankSearchStrategy` | `wiki-rerank` | `search` |
| `LlmWikiPageGenerator` | `wiki-generator` | `page-generation` or `index-generation` |

On top of that, both components attach the `WikiScope` information as tags on every call:

| Tag key | Value |
|---------|---|
| `wiki.agent` | `WikiScope.agentName` |
| `wiki.context` | `WikiScope.contextId` |
| `wiki.name` | `WikiScope.wikiName` |

These tags are merged over the static metadata given to the builder via `LlmCallMetadata.withTags(...)`, so both the caller-supplied base tags and the per-call scope tags survive. Note that `wiki.context` is unique per execution context and is therefore a high-cardinality value — rather than using it directly as a Prometheus label in an external recorder, it is safer to use it for correlating logs and traces.

## 4. Per-call measurements: tier · cache · time

Token counts alone are not enough to price a single call. The same model costs different amounts depending on the
**service tier actually applied**, the **input read from or written to the cache**, and **the moment the price
applies**. `MeteringLlmClient` puts these into an `LlmUsageEvent` and hands it to `LlmUsageRecorder.record(LlmUsageEvent)`.

| Value | Source | When not reported |
|----|------|------------------|
| `getServiceTier()` | the tier the response reports — not the request | empty (never assumed to be `standard`) |
| `getUsage().getCacheReadTokens()` | the response's cache-read counter | empty (never assumed to be `0`) |
| `getUsage().getCacheWriteTokens()` | the response's cache-write counter | empty |
| `getServedModel()` | the model the response reports (e.g. the snapshot an alias resolved to) | empty — `getModel()` falls back to the requested model |
| `getRequestedModel()` | the request's `LlmModel`, else the client's default model | empty |
| `getStartedAt()` / `getCompletedAt()` / `getLatency()` | `MeteringLlmClient`'s `Clock` | always present |

IMPORTANT: `TokenUsage.getPromptTokens()` is the **whole input context, cache included**. Cache reads and cache writes
are not added to the prompt; they are **subsets** of it. When a provider reports only the uncached input (Anthropic's
`input_tokens`), the provider module adds the two cache counters back to restore the whole. That is what makes this
number usable for picking a context window or a context-length price band (e.g. the 272000 / 272001 boundary). As with
reasoning tokens, adding cache tokens on top of it would bill them twice.

**Unmeasured is not the same as a measured zero.** The cache counters are `OptionalInt`s, and an empty one means "the
provider did not say". Do not price a call you cannot price as free or as uncached. `TokenUsage.add` likewise leaves the
sum unmeasured when either side is (except for a side with zero prompt tokens — the `TokenUsage.empty()` accumulator
seed — whose cache counters are trivially zero).

### 4.1 Per-provider normalization

Normalization is done **explicitly** by the provider module. There are four neutral tiers, `standard`, `fast`, `flex`
and `batch` (`ServiceTier.isNormalized()`); a tier with no counterpart is not guessed but preserved as reported
(lower-cased).

| Provider | tier | cache-read | cache-write |
|--------|------|-----------|-------------|
| OpenAI (Responses / Chat) | `default`→`standard`, `priority`→`fast`, `flex`, `batch`; `auto` reads as not reported; anything else (`scale`, ...) preserved | `input_tokens_details.cached_tokens` (`prompt_tokens_details.cached_tokens`) | a measured `0` whenever cache-read is reported (OpenAI does not bill cache writes) |
| Anthropic | `standard`, `batch`; `priority` is not `fast` and is preserved as is | `cache_read_input_tokens` | `cache_creation_input_tokens` — but unmeasured when any write used the one-hour TTL, which a single cache-write rate cannot price |

Anthropic's one-hour-TTL write tokens are still included in the prompt, because they were processed input. Only their
cache-write classification is withheld.

### 4.2 Call paths and how often they are recorded

- **The blocking, streaming and cancellation-token paths** all build the same event from the same `LlmResponse`. A
  streaming call is recorded **once**, from its final response — never per chunk.
- **Retries**: `MeteringLlmClient` records every call that goes through it. The framework's retry loop
  (`LlmCallGateway`) sits outside the client, so each attempt that produced a response or an abort gets its own event.
  Retries an SDK makes internally (on a 429/5xx before any response) are invisible here and are not billed.
- **Failures and cancellation**: a call that ends in an exception is not recorded. The exception is a call that may
  still have cost money — a stream that fails or is cancelled after its first chunk arrived, or a call cut by its
  cancellation token after it was sent. Those are recorded as an `Outcome.ABORTED` event whose usage is **empty**; a
  partial count would be priced as if it were the whole bill, so none is attached. A token already tripped on entry
  means no request went out, so nothing is recorded.

### 4.3 Compatibility with existing recorders

`record(LlmUsageEvent)` is a default method. An existing recorder that only implements the four-argument `record(...)`
keeps working, and receives completed calls in this shape:

- the `model` argument is the **requested model**, as before — existing aggregation keys do not change. The actual model
  is on the event only.
- the measured values ride in the tags of `metadata` (`LlmUsageEvent.toRecordingMetadata()`).

| Tag | Value | When not measured |
|-----|----|------------------|
| `service_tier` | the normalized tier | no tag |
| `cached_input_tokens` | a non-negative integer string | no tag |
| `cache_write_tokens` | a non-negative integer string | no tag |

If a caller put the same keys (or the derived keys `cache_usage_known`, `context_input_tokens`) on its request
metadata, they are **removed just before recording**, so they can never be mistaken for measurements. The metadata
object the caller passed in is not modified. `ABORTED` events have no usage to report and are not forwarded to the
four-argument method — just as failed calls never were. To count aborted calls as well, override
`record(LlmUsageEvent)`.

## 5. Custom recorders

To send usage somewhere other than `InMemoryLlmUsageRecorder` — Prometheus, OpenTelemetry, a database — implement `LlmUsageRecorder` yourself. Only one method has to be implemented (override §4's `record(LlmUsageEvent)` as well when you need the tier, cache and timing):

```java
public final class PrometheusLlmUsageRecorder implements LlmUsageRecorder {

    private final Counter callsTotal;
    private final Counter promptTokensTotal;
    private final Counter completionTokensTotal;

    @Override
    public void record(String provider, String model, TokenUsage usage, LlmCallMetadata metadata) {
        final String component = metadata.getComponent().orElse("unknown");
        final String feature = metadata.getFeature().orElse("unknown");
        callsTotal.labels(provider, model, component, feature).inc();
        promptTokensTotal.labels(provider, model, component, feature).inc(usage.getPromptTokens());
        completionTokensTotal.labels(provider, model, component, feature).inc(usage.getCompletionTokens());
    }
}
```

Keep such an implementation in its own module and depend on it only where the CLI (or the consuming application) does its wiring — `aimon-core` knows nothing but the interface (the multi-instance design principle).

## 6. Best practices

- **Make traceId match your external correlation id**: putting the request id your gateway or CLI receives straight into it lets you join LLM usage against your external logs.
- **Use `at.aimon.core.base.Principal` for principal**: use it as-is when you need the user/group/system/service distinction. If a plain user id is all you need, putting it in `tags` is lighter.
- **Keep tags to low-cardinality values only**: they are excluded from `LlmUsageKey` aggregation, but they are likely to end up as labels in an external recorder. Move high-cardinality values such as a user id into `principal`.
- **Only one layer of the decorator**: nesting it — `MeteringLlmClient(MeteringLlmClient(...))` — emits every event twice. Meter at the outermost layer only.
- **An exception from the recorder does not break the call**: `MeteringLlmClient` catches RuntimeException, logs at WARN and returns the response unchanged. An outage in the metering backend does not fail an LLM call.

## 7. Related code

- Interfaces / value objects: `at.aimon.core.llm.LlmCallMetadata`, `at.aimon.core.llm.LlmClient` (the overload)
- The recorder package: `at.aimon.core.llm.usage.*` (including `LlmUsageEvent`)
- Measurement types: `at.aimon.core.llm.TokenUsage` (cache counters), `at.aimon.core.llm.ServiceTier`, `LlmResponse.getServedModel()` / `getServiceTier()`
- Provider normalization: `OpenAiServedBy`, `OpenAiResponseUsages`, `OpenAiChatUsages` (`aimon-llm-openai`), `AnthropicUsages` (`aimon-llm-anthropic`)
- Helper: `at.aimon.core.llm.usage.LlmCallMetadataResolver` — merges the auto-derived fields into the caller's metadata
- Integration points: `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `LlmRerankSearchStrategy`, `LlmWikiPageGenerator`
