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

provider 구현체(`OpenAILlmClient`, `AnthropicLlmClient`) 는 metering 을 알 필요가 없다. 데코레이터로 감싸기만 하면 된다.

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

이 effective metadata 가 ReAct 루프의 모든 LLM 호출에 적용되며, ToolContext 를 통해 sub-execution 으로도 전파된다.

### 3.2 Subagent

Orca 가 TaskTool 을 통해 subagent 를 실행하면, 부모 metadata 가 `SubagentExecutionEnvironment.parentLlmCallMetadata` 로 자동 전파된다. `DefaultSubagentExecutor` 는 다음과 같이 effective metadata 를 만든다:

| 필드 | 값 |
|------|---|
| `component` | `subagent.getName()` — **항상 subagent 가 우선** (parent 가 채웠더라도 override) |
| `feature` | `"subagent"` — **항상 우선** |
| `traceId` | parent traceId 를 그대로 상속 |
| `principal`/`tags` | parent 값 상속 |

> **머지 방향이 Orca 와 반대**: Orca 는 "caller-supplied wins, auto-derive 가 fallback" 이지만 subagent 는 "subagent identity wins, parent 가 fallback" 이다. 이는 의도된 비대칭이다 — sub-execution 의 토큰 사용량은 항상 실제로 실행된 subagent 이름으로 집계되어야 추적이 의미가 있다.

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

## 4. 커스텀 Recorder

`InMemoryLlmUsageRecorder` 외에 Prometheus, OpenTelemetry, DB 등으로 보내려면 `LlmUsageRecorder` 를 직접 구현하면 된다. 인터페이스는 한 메서드뿐이다:

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

## 5. 베스트 프랙티스

- **traceId 는 외부 상관관계 ID와 일치시키기**: 게이트웨이/CLI 가 받는 요청 ID 를 그대로 넣으면 LLM 사용량을 외부 로그와 join 할 수 있다.
- **principal 은 `at.aimon.core.base.Principal` 사용**: user/group/system/service 구분이 필요하면 그대로 사용. 단순 사용자 ID 만 필요하면 `tags` 에 넣는 편이 가볍다.
- **tags 는 cardinality 가 낮은 값만**: `LlmUsageKey` 집계에서 제외되긴 하지만, 외부 recorder 에서 라벨로 쓸 가능성이 높다. 사용자 ID 같은 high-cardinality 값은 `principal` 로 옮긴다.
- **데코레이터는 한 겹만**: `MeteringLlmClient(MeteringLlmClient(...))` 처럼 중첩하면 이벤트가 중복 발생한다. 가장 바깥쪽 한 겹에서만 metering 한다.
- **recorder 예외는 호출 흐름을 깨지 않는다**: `MeteringLlmClient` 가 RuntimeException 을 잡아 WARN 로깅 후 응답을 그대로 반환한다. metering 백엔드 장애가 LLM 호출을 실패시키지 않는다.

## 6. 관련 코드

- 인터페이스/값 객체: `at.aimon.core.llm.LlmCallMetadata`, `at.aimon.core.llm.LlmClient` (overload)
- Recorder 패키지: `at.aimon.core.llm.usage.*`
- 헬퍼: `at.aimon.core.llm.usage.LlmCallMetadataResolver` — caller 메타데이터에 자동 보강 필드를 머지
- 통합 지점: `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `LlmRerankSearchStrategy`, `LlmWikiPageGenerator`
