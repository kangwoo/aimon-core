---
translated_from: docs/features/llm/llm-usage-metering.md
source_commit: 8830d022
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

The provider implementations (`OpenAILlmClient`, `AnthropicLlmClient`) do not need to know that metering exists. Wrapping them in the decorator is enough.

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

This effective metadata applies to every LLM call in the ReAct loop, and propagates into sub-executions through the ToolContext.

### 3.2 Subagents

When Orca runs a subagent through TaskTool, the parent metadata propagates automatically as `SubagentExecutionEnvironment.parentLlmCallMetadata`. `DefaultSubagentExecutor` builds its effective metadata like this:

| Field | Value |
|------|---|
| `component` | `subagent.getName()` — **the subagent always wins** (overridden even if the parent set it) |
| `feature` | `"subagent"` — **always wins** |
| `traceId` | inherits the parent's traceId as-is |
| `principal`/`tags` | inherits the parent's values |

> **The merge direction is the opposite of Orca's**: Orca is "caller-supplied wins, auto-derived is the fallback", whereas a subagent is "the subagent's identity wins, the parent is the fallback". The asymmetry is deliberate — a sub-execution's token usage is only meaningful to track when it is aggregated under the name of the subagent that actually ran.

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

## 4. Custom recorders

To send usage somewhere other than `InMemoryLlmUsageRecorder` — Prometheus, OpenTelemetry, a database — implement `LlmUsageRecorder` yourself. The interface has exactly one method:

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

## 5. Best practices

- **Make traceId match your external correlation id**: putting the request id your gateway or CLI receives straight into it lets you join LLM usage against your external logs.
- **Use `at.aimon.core.base.Principal` for principal**: use it as-is when you need the user/group/system/service distinction. If a plain user id is all you need, putting it in `tags` is lighter.
- **Keep tags to low-cardinality values only**: they are excluded from `LlmUsageKey` aggregation, but they are likely to end up as labels in an external recorder. Move high-cardinality values such as a user id into `principal`.
- **Only one layer of the decorator**: nesting it — `MeteringLlmClient(MeteringLlmClient(...))` — emits every event twice. Meter at the outermost layer only.
- **An exception from the recorder does not break the call**: `MeteringLlmClient` catches RuntimeException, logs at WARN and returns the response unchanged. An outage in the metering backend does not fail an LLM call.

## 6. Related code

- Interfaces / value objects: `at.aimon.core.llm.LlmCallMetadata`, `at.aimon.core.llm.LlmClient` (the overload)
- The recorder package: `at.aimon.core.llm.usage.*`
- Helper: `at.aimon.core.llm.usage.LlmCallMetadataResolver` — merges the auto-derived fields into the caller's metadata
- Integration points: `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `LlmRerankSearchStrategy`, `LlmWikiPageGenerator`
