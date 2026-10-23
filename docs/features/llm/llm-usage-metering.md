# LLM Usage Metering Guide

> LLM 호출량을 컴포넌트/기능/사용자 단위로 측정하기 위한 메타데이터 + Recorder 가이드

## 1. 개요

`LlmClient` 가 발생시키는 토큰 사용량을 어디서, 누가, 왜 발생시켰는지 식별할 수 있도록 두 가지 컴포넌트를 제공한다.

| 컴포넌트 | 역할 |
|----------|------|
| `LlmCallMetadata` | 호출 시점의 attribution(component/feature/principal/traceId/tags) 을 담는 불변 값 객체 |
| `LlmUsageRecorder` | 호출 결과(provider/model/TokenUsage + metadata) 를 받는 싱크 인터페이스 |
| `MeteringLlmClient` | `LlmClient` 데코레이터. 응답을 가로채 recorder 로 이벤트 발행 |
| `InMemoryLlmUsageRecorder` | 멀티인스턴스 대응 원칙에 따른 기본 in-memory 구현 |
| `LlmUsageEvent` | 호출 1건의 측정값(실제 tier, cache 카운터, 실제 모델, 완료 시각·latency) — §4 |
| `ServiceTier` | 공급자가 실제로 적용한 처리 tier 의 중립 표현 |

provider 구현체(`OpenAILlmClient`, `AnthropicLlmClient`) 는 metering 을 알 필요가 없다. 데코레이터로 감싸기만 하면 된다.

IMPORTANT: `TokenUsage.getReasoningTokens()` 는 **기록되지만 값이 매겨지지 않는다.** 추론 토큰은
completion 토큰에 더해지는 것이 아니라 그 **부분집합**이므로(OpenAI 는 `output_tokens_details` 안에
넣어 보낸다) `ModelPrice.costOf` 의 completion 항목이 이미 값을 매긴 뒤다 — 여기서 또 더하면 두 번
청구하는 것이 된다. recorder 는 `TokenUsage` 를 통째로 받으므로 이 필드는 그냥 도착한다.

`provider` 는 벤더 이름(`"OpenAI"`, `"Anthropic"`)이고 `model` 은 그 호출이 **실제로 사용한** 모델이다 — 요청의 `LlmModel` 이 지정한 이름, 지정하지 않았으면 클라이언트의 기본 모델(`LlmClient.getDefaultModelName()`).

## 2. 빠른 시작

### 2.1 Wiring

```java
InMemoryLlmUsageRecorder recorder = new InMemoryLlmUsageRecorder();
LlmClient client = new MeteringLlmClient(new OpenAILlmClient(apiKey), recorder);

// 이후 client 를 OrcaAgentExecutor, SubagentExecutor, Wiki 컴포넌트 등에 그대로 주입
```

`LlmUsageRecorder.NOOP` 을 사용하면 metering 을 비활성화한 상태에서도 데코레이터 체인을 그대로 둘 수 있다.

### 2.2 호출 시점 attribution 부여

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

`metadata` 를 생략한 일반 `sendMessage(...)` 호출도 정상 동작한다 — `LlmCallMetadata.empty()` 가 자동으로 적용된다.

### 2.3 집계 결과 조회

```java
for (LlmUsageSnapshot snap : recorder.snapshot()) {
    System.out.printf("%s → calls=%d, totalTokens=%d%n",
            snap.getKey(), snap.getCallCount(), snap.getTotalUsage().getTotalTokens());
}
```

`LlmUsageKey` 는 provider/model + (component, feature, principal) 5축으로 집계된다. tags 는 cardinality 폭주를 막기 위해 키에서 제외되며, 이벤트 단위 분석이 필요하면 별도 recorder 를 구현하면 된다.

## 3. 프레임워크 통합

프레임워크는 주요 호출 경로에서 metadata 를 자동으로 채워준다. 호출자는 필요할 때만 일부 필드를 덮어쓰면 된다.

### 3.1 Orca 에이전트

`OrcaAgentExecutionRequest` 에 `llmCallMetadata` 필드가 있다.

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

`OrcaAgentExecutor.execute` 는 caller 가 채우지 않은 다음 필드를 자동으로 보강한다(이미 채워진 값은 보존):

| 필드 | 자동 값 |
|------|--------|
| `component` | `agent.getName()` |
| `feature` | `"react-loop"` |
| `traceId` | `conversationId.value()` |

`principal` 만은 예외다. 요청에 `principal(…)` 을 주면 그 값이 metadata 의 `principal` 보다 **이긴다** — 턴이 실행되는
신원이기 때문이다. 요청에 principal 이 없을 때만 metadata 의 값이 쓰인다. compaction 요약 호출도 같은 principal 로 집계된다.

이 effective metadata 가 ReAct 루프의 모든 LLM 호출에 적용되며, ToolContext 를 통해 sub-execution 으로도 전파된다.

### 3.2 Subagent

Orca 가 TaskTool 을 통해 subagent 를 실행하면, 부모 metadata 가 `SubagentLaunchContext.parentLlmCallMetadata` 로 자동 전파된다. `DefaultSubagentExecutor` 는 다음과 같이 effective metadata 를 만든다:

| 필드 | 값 |
|------|---|
| `component` | `subagent.getName()` — **항상 subagent 가 우선** (parent 가 채웠더라도 override) |
| `feature` | `"subagent"` — **항상 우선** |
| `traceId` | parent traceId 를 그대로 상속 |
| `principal` | `SubagentExecutionRequest.principal` 이 있으면 그 값, 없으면 parent 값 상속 |
| `tags` | parent 값 상속 |

> **머지 방향이 Orca 와 반대**: Orca 는 "caller-supplied wins, auto-derive 가 fallback"(`principal` 만 예외, §3.1) 이지만 subagent 는 "subagent identity wins, parent 가 fallback" 이다. 이는 의도된 비대칭이다 — sub-execution 의 토큰 사용량은 항상 실제로 실행된 subagent 이름으로 집계되어야 추적이 의미가 있다.

직접 `SubagentExecutionRequest` 를 만들어 호출하는 경우에도 동일한 규칙이 적용된다. component/feature 를 강제로 다르게 태깅하고 싶다면 `MeteringLlmClient` 앞단에 별도 데코레이터를 두는 편이 깔끔하다.

### 3.3 Wiki

`LlmRerankSearchStrategy` 와 `LlmWikiPageGenerator` 는 호출 컨텍스트 없이 동작하는 정적 컴포넌트라, 빌더 시점에 attribution 을 한 번만 지정한다.

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

미설정 시 다음 기본값이 적용된다:

| 컴포넌트 | 기본 component | 기본 feature |
|----------|---------------|--------------|
| `LlmRerankSearchStrategy` | `wiki-rerank` | `search` |
| `LlmWikiPageGenerator` | `wiki-generator` | `page-generation` 또는 `index-generation` |

추가로, 두 컴포넌트 모두 호출 시점마다 `WikiScope` 정보를 자동으로 tags 로 첨부한다:

| 태그 키 | 값 |
|---------|---|
| `wiki.agent` | `WikiScope.agentName` |
| `wiki.context` | `WikiScope.contextId` |
| `wiki.name` | `WikiScope.wikiName` |

이 tags 는 builder 로 지정한 정적 metadata 위에 `LlmCallMetadata.withTags(...)` 로 머지되므로, caller-supplied 기본 tags 와 per-call scope tags 가 모두 보존된다. `wiki.context` 는 실행 컨텍스트마다 고유하므로 high-cardinality 값임에 유의 — 외부 recorder(Prometheus 등) 에서 Prometheus 라벨로 직접 쓰기보다는 로그/트레이스 연결용으로 사용하는 편이 안전하다.

## 4. 호출별 측정값: tier · cache · 시각

호출 단위로 값을 매기려면 토큰 수만으로는 모자란다. 같은 모델이라도 **실제로 적용된 service tier**,
**cache 에서 읽은/쓴 입력**, **가격이 적용되는 시점**에 따라 값이 달라진다. `MeteringLlmClient` 는 이것들을
`LlmUsageEvent` 에 담아 `LlmUsageRecorder.record(LlmUsageEvent)` 로 넘긴다.

| 값 | 출처 | 보고되지 않았을 때 |
|----|------|------------------|
| `getServiceTier()` | 응답이 보고한 tier — 요청이 아니다 | 비어 있음 (`standard` 로 가정하지 않는다) |
| `getUsage().getCacheReadTokens()` | 응답의 cache-read 카운터 | 비어 있음 (`0` 으로 가정하지 않는다) |
| `getUsage().getCacheWriteTokens()` | 응답의 cache-write 카운터 | 비어 있음 |
| `getServedModel()` | 응답이 보고한 모델 (alias 가 풀린 snapshot 등) | 비어 있음 — `getModel()` 은 요청 모델로 대체 |
| `getRequestedModel()` | 요청의 `LlmModel`, 없으면 클라이언트 기본 모델 | 비어 있음 |
| `getStartedAt()` / `getCompletedAt()` / `getLatency()` | `MeteringLlmClient` 의 `Clock` | 항상 있음 |

IMPORTANT: `TokenUsage.getPromptTokens()` 는 **cache 를 포함한 전체 input context** 다. cache-read 와 cache-write 는
prompt 에 더해지는 값이 아니라 그 **부분집합**이다. 공급자가 uncached input 만 보고하면(Anthropic 의
`input_tokens`) provider 모듈이 두 cache 카운터를 더해 전체를 복원한다. 그래서 이 값으로 context window 나
context-length 가격 구간(예: 272000 / 272001 경계)을 고를 수 있다. reasoning 토큰과 마찬가지로 cache 토큰을 따로
더해 과금하면 두 번 청구된다.

**미측정과 측정된 0 은 다르다.** cache 카운터는 `OptionalInt` 이고, 비어 있으면 "공급자가 알려주지 않았다"는 뜻이다.
가격을 매길 수 없는 호출을 무료나 uncached 로 매기지 말 것. `TokenUsage.add` 도 한쪽이 미측정이면 합을 미측정으로
둔다(prompt 가 0 인 쪽 — `TokenUsage.empty()` 누산 시작값 — 은 cache 도 자명하게 0 이므로 예외).

core 의 비용 추정(`ModelPrice.costOf`)도 같은 규칙을 따른다. `ModelPrice` 에 cache-read/cache-write 요율을 선택적으로
줄 수 있고(`perMillionUsd(in, out, cacheRead, cacheWrite)`), cache 부분은 **카운터가 측정되었고 요율도 있을 때만**
input 항목에서 빠져 자기 요율로 매겨진다. 그 밖의 경우는 이전처럼 prompt 전체가 input 요율이다.
`InMemoryModelPriceTable.withDefaults()` 는 OpenAI cached input 과 Anthropic cache read(0.1x)·write(1.25x) 요율을 갖는다.

### 4.1 공급자별 정규화

정규화는 provider 모듈이 **명시적으로** 한다. 중립 tier 는 `standard`, `fast`, `flex`, `batch` 넷이며
(`ServiceTier.isNormalized()`), 대응하는 값이 없는 tier 는 추측하지 않고 보고된 그대로(소문자) 보존한다.

| 공급자 | tier | cache-read | cache-write |
|--------|------|-----------|-------------|
| OpenAI (Responses / Chat) | `default`→`standard`, `priority`→`fast`, `flex`, `batch`; `auto` 는 미보고; 그 외(`scale` 등) 보존 | `input_tokens_details.cached_tokens` (`prompt_tokens_details.cached_tokens`) | cache-read 가 보고되면 측정된 `0` (OpenAI 는 cache 쓰기를 과금하지 않는다) |
| Anthropic | `standard`, `batch`; `priority` 는 `fast` 가 아니므로 그대로 보존 | `cache_read_input_tokens` | `cache_creation_input_tokens`. 단 1시간 TTL 쓰기가 있으면 미측정 — 단일 cache-write 요율로 값을 매길 수 없다 |

Anthropic 의 1시간 TTL 쓰기 토큰도 prompt 에는 포함된다. 처리된 입력이기 때문이다. 빠지는 것은 cache-write 분류뿐이다.

### 4.2 호출 경로와 기록 횟수

- **blocking / streaming / cancellation 토큰이 있는 경로** 모두 같은 `LlmResponse` 에서 같은 이벤트를 만든다.
  streaming 은 최종 응답에서 **한 번만** 기록한다 — chunk 마다 기록하지 않는다.
- **retry**: `MeteringLlmClient` 는 자신을 통과한 호출마다 기록한다. 프레임워크의 retry 루프(`LlmCallGateway`)는
  클라이언트 바깥에 있으므로, 응답이나 abort 를 만든 시도마다 이벤트가 하나씩 생긴다. SDK 내부 재시도(응답 전의
  429/5xx)는 보이지 않으며 과금되지도 않는다.
- **실패와 취소**: 예외로 끝난 호출은 기록하지 않는다. 단, 비용이 생겼을 수 있는 경우 — 첫 chunk 가 도착한 뒤
  실패하거나 취소된 stream, 또는 전송 후 cancellation 토큰으로 끊긴 호출 — 는 usage 가 **비어 있는**
  `Outcome.ABORTED` 이벤트로 기록한다. 부분 카운트는 전체 청구서처럼 값이 매겨지므로 싣지 않는다. 진입 시점에 이미
  취소된 토큰은 요청이 나가지 않으므로 기록하지 않는다.

### 4.3 기존 recorder 와의 호환

`record(LlmUsageEvent)` 는 default 메서드다. 4-인자 `record(...)` 만 구현한 기존 recorder 는 그대로 동작하며,
완료된 호출을 다음 형태로 받는다.

- `model` 인자는 이전과 같이 **요청 모델**이다 — 기존 집계 키가 바뀌지 않는다. 실제 모델은 이벤트에만 있다.
- `metadata` 의 tags 에 측정값이 실린다(`LlmUsageEvent.toRecordingMetadata()`).

| Tag | 값 | 측정되지 않았을 때 |
|-----|----|------------------|
| `service_tier` | 정규화된 tier | 태그 없음 |
| `cached_input_tokens` | 0 이상의 정수 문자열 | 태그 없음 |
| `cache_write_tokens` | 0 이상의 정수 문자열 | 태그 없음 |

호출자가 요청 metadata 에 같은 키(및 파생 키 `cache_usage_known`, `context_input_tokens`)를 넣었더라도
**기록 직전에 제거**되므로 측정값으로 오인될 수 없다. 호출자가 넘긴 metadata 객체 자체는 바뀌지 않는다.
`ABORTED` 이벤트는 보고할 usage 가 없으므로 4-인자 메서드로 전달되지 않는다 — 이전에 실패한 호출이 그랬던 것과 같다.
abort 된 호출까지 세려면 `record(LlmUsageEvent)` 를 override 한다.

## 5. 커스텀 Recorder

`InMemoryLlmUsageRecorder` 외에 Prometheus, OpenTelemetry, DB 등으로 보내려면 `LlmUsageRecorder` 를 직접 구현하면 된다. 구현해야 하는 메서드는 하나뿐이다(tier·cache·시각이 필요하면 §4 의 `record(LlmUsageEvent)` 를 함께 override 한다):

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

구현체는 별도 모듈에 두고, CLI(또는 사용 측 애플리케이션)에서 wiring 할 때만 의존하도록 한다 — `aimon-core` 는 인터페이스만 안다(멀티인스턴스 설계 원칙).

## 6. 베스트 프랙티스

- **traceId 는 외부 상관관계 ID와 일치시키기**: 게이트웨이/CLI 가 받는 요청 ID 를 그대로 넣으면 LLM 사용량을 외부 로그와 join 할 수 있다.
- **principal 은 `at.aimon.core.base.Principal` 사용**: user/group/system/service 구분이 필요하면 그대로 사용. 단순 사용자 ID 만 필요하면 `tags` 에 넣는 편이 가볍다.
- **tags 는 cardinality 가 낮은 값만**: `LlmUsageKey` 집계에서 제외되긴 하지만, 외부 recorder 에서 라벨로 쓸 가능성이 높다. 사용자 ID 같은 high-cardinality 값은 `principal` 로 옮긴다.
- **데코레이터는 한 겹만**: `MeteringLlmClient(MeteringLlmClient(...))` 처럼 중첩하면 이벤트가 중복 발생한다. 가장 바깥쪽 한 겹에서만 metering 한다.
- **recorder 예외는 호출 흐름을 깨지 않는다**: `MeteringLlmClient` 가 RuntimeException 을 잡아 WARN 로깅 후 응답을 그대로 반환한다. metering 백엔드 장애가 LLM 호출을 실패시키지 않는다.

## 7. 관련 코드

- 인터페이스/값 객체: `at.aimon.core.llm.LlmCallMetadata`, `at.aimon.core.llm.LlmClient` (overload)
- Recorder 패키지: `at.aimon.core.llm.usage.*` (`LlmUsageEvent` 포함)
- 측정값 타입: `at.aimon.core.llm.TokenUsage` (cache 카운터), `at.aimon.core.llm.ServiceTier`, `LlmResponse.getServedModel()` / `getServiceTier()`
- 공급자 정규화: `OpenAiServedBy`, `OpenAiResponseUsages`, `OpenAiChatUsages` (`aimon-llm-openai`), `AnthropicUsages` (`aimon-llm-anthropic`)
- 헬퍼: `at.aimon.core.llm.usage.LlmCallMetadataResolver` — caller 메타데이터에 자동 보강 필드를 머지
- 통합 지점: `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `LlmRerankSearchStrategy`, `LlmWikiPageGenerator`
