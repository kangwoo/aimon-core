# Anthropic thinking 요청 해석 (Anthropic Thinking)

> Status: **IMPLEMENTED** — 네 thinking mode(`AUTO` 포함)와 네 방언 상수(`EITHER` 포함)의 해석표, budget ladder 와
> clamp, adaptive 모양 전용 `display`, 로거 없는 리졸버가 기록하고 완성된 요청이 방출을 정하는 보고 계약이 들어가 있다.
> 남은 것은 §11.
>
> 적용 대상: `aimon-llm-anthropic` — `at.aimon.core.llms.anthropic`(`AnthropicThinkingMode`, `AnthropicThinkingResolver`,
> `AnthropicThinkingResolution`, `AnthropicThinkingBudgets`, `AnthropicThinkingDisplay`, `AnthropicConfig`,
> `AnthropicLlmClient`) · `aimon-core` — `at.aimon.core.llm.capability.ThinkingDialect`

---

## 1. 문제와 범위

Anthropic 은 thinking 을 두 가지 요청 모양으로 받는다. 어느 모양을 받는지는 모델마다 다르고, 모델이 받지 않는 모양을
보내면 응답이 나빠지는 것이 아니라 HTTP 400 이다. 운영자가 알고 싶은 것은 "생각하게 하라" 와 "얼마나" 인데, 와이어가
요구하는 것은 모델마다 다른 요청 모양이다. 이 문서는 세 가지를 정한다.

- **해석** — 운영자의 의도(thinking mode), 모델의 사실(thinking dialect), 호출의 깊이(`ReasoningEffort`)로부터 요청에 실을
  `thinking` 파라미터와 `output_config.effort` 를 어떻게 정하는가
- **깊이의 번역** — 중립 effort 를 budgeted 방언의 토큰 예산과 adaptive 방언의 effort rung 으로 어떻게 옮기고, `max_tokens`
  에 들어가지 않는 예산을 어떻게 다루는가
- **보고** — 요청이 설정과 다를 때 운영자에게 무엇을, 언제 말하는가

**범위 밖.** 아래는 다른 문서가 정본이고 여기서는 필요한 곳에 한 문장으로만 부른다.

| 개념 | 정본 |
|---|---|
| thinking 블록의 캡처·재전송, `replayThinkingBlocks` 의 뜻 | [`reasoning-traces.md`](reasoning-traces.md) |
| thinking 이 실릴 때의 샘플링 파라미터 생략, divergence 보고 집합(once / recurring)과 상한 | [`request-parameters.md`](request-parameters.md) |
| 모델별 방언 실측 결론과 내장 행, 모델 이름을 보지 않는다는 원칙 | [`model-capabilities.md`](model-capabilities.md) |
| thinking 키의 설정 표면, 기동 실패와 키 경로 재던짐 | [`configuration-surface.md`](configuration-surface.md) |
| 추론 델타의 전달 게이트와 채널 | [`streaming.md`](streaming.md) |
| `max_tokens` 에서 잘린 응답의 보고 | [`../agent-execution/max-tokens-truncation-reporting.md`](../agent-execution/max-tokens-truncation-reporting.md) |

**비목표.** interleaved thinking beta 헤더는 보내지 않는다(§9). `thinking.type: disabled` 는 보내지 않는다(§4.6).
운영자가 켜지 않은 배포에서 thinking 을 켜지 않는다(§4.5).

---

## 2. 두 요청 모양과 세 축 — 모드는 운영자, 방언은 모델, 깊이는 호출

### 2.1 두 요청 모양과 400

```jsonc
{ "thinking": { "type": "enabled", "budget_tokens": 10000 } }                      // budgeted 방언
{ "thinking": { "type": "adaptive" }, "output_config": { "effort": "high" } }      // adaptive 방언
```

SDK 는 두 모양을 `ThinkingConfigParam.ofEnabled` / `ofAdaptive` 와 `OutputConfig.Effort` 로 모델링한다. 모양이 틀렸을 때
서버가 돌려주는 두 문구는 다음과 같다(라이브 API 에서 그대로 재현했고 `AnthropicThinkingMode` javadoc 이 같은 문구를
인용한다 — 오류 문구로 검색한 운영자가 그 타입에 닿게 하려는 것이다).

```text
"thinking.type.enabled" is not supported for this model. Use "thinking.type.adaptive" and
"output_config.effort" to control thinking behavior.
```

```text
adaptive thinking is not supported on this model
```

둘 다 `invalid_request_error`, 즉 요청 검증이라 결과가 결정적이다. 클라이언트에서는 `LlmInvalidRequestException` 이 되고
재시도하지 않는다. 방언을 기술한 행이 없는(`UNKNOWN`) 모델에는 요청이 설정대로 나가므로, 그 모델이 받지 않는 모드는 이
400 으로 한 번에 드러난다.

`budget_tokens` 는 1024 이상이고 `max_tokens` 미만이어야 한다. thinking 토큰이 `max_tokens` 에 포함되기 때문이다.
여러 최신 모델은 설정 없이도 thinking 을 켠 채 동작한다.

### 2.2 세 축

| 축 | 타입 | 누가 정하나 | 값 |
|---|---|---|---|
| thinking mode | `AnthropicThinkingMode` (`AnthropicConfig.thinkingMode`) | 운영자 — 이 배포가 원하는 것 | `OFF`(기본) · `EXTENDED` · `ADAPTIVE` · `AUTO` |
| thinking dialect | `ThinkingDialect` (`ModelCapabilities.thinkingDialect()`) | 능력 레지스트리 — 모델이 받는 것 | `UNKNOWN` · `EITHER` · `BUDGETED` · `ADAPTIVE` |
| 깊이 | `ReasoningEffort` | 호출(`LlmModel`), 없으면 `AnthropicConfig` | `NONE` · `MINIMAL` · `LOW` · `MEDIUM` · `HIGH` |

모드는 방언을 이름 짓지 않는다. `EXTENDED` 는 "budgeted 모양을 원한다" 는 의도이고, 그 모양을 모델이 받는지는 방언이
답한다. 같은 와이어 모양을 층위마다 다르게 부른다 — 모드 `EXTENDED`, 방언 `BUDGETED`, 와이어 `thinking.type=enabled`.
`ADAPTIVE` 는 두 enum 에 같은 이름으로 있으므로 산문에서는 "adaptive 모드" 와 "adaptive 방언" 으로 가른다.

클라이언트는 방언을 모델 이름으로 추측하지 않고, 요청에 실릴 이름으로 레지스트리가 해석한 descriptor 에서만 읽는다.
그 원칙과 방언 행의 근거는 [`model-capabilities.md`](model-capabilities.md) 가 정본이다.

### 2.3 한 번의 해석, 한 곳의 결정

`AnthropicLlmClient.buildRequest` 는 요청마다 모델 이름과 `max_tokens`(호출의 `LlmModel` 이 먼저, 없으면
`AnthropicConfig.getMaxTokens()`)를 한 번 정하고, 그 이름으로 descriptor 를 한 번 해석해 두 결정 — 샘플링과 thinking —
에 함께 쓴다. thinking 쪽 결정 전체는 `AnthropicThinkingResolver.resolve(modelConfig, maxTokens, capabilities, modelName)`
하나가 내리고, 결과 `AnthropicThinkingResolution` 이 요청에 실을 `thinking` 파라미터, 그와 함께 갈 `output_config.effort`,
운영자에게 말할 finding 을 함께 돌려준다. 보고의 구조는 §8 에 있다.

---

## 3. 방언 — 네 상수, 두 쌍

### 3.1 두 값 배타 축에는 fail-open 값이 없다 — `UNKNOWN`

두 요청 모양은 각각 반대 방언만 받는 모델에서 400 이다. 방언을 두 값 배타 축으로 두면 어떤 기본값도 절반의 모델에서
틀리고, 아무도 기술하지 않은 모델에 줄 안전한 값이 없다. 그래서 방언 축은 **"표가 답하지 못함"** 을 뜻하는 `UNKNOWN` 을
가진다. `UNKNOWN` 은 방언이 아니라 사실의 부재다. 클라이언트는 고르지 않고 운영자가 명명한 모드를 그대로 따르므로, 행이
없는 모델 — 게이트웨이가 이름을 바꾼 모델 포함 — 은 방언 필드가 없던 때와 같은 요청을 보낸다. `UNKNOWN` 이 레지스트리의
fail-open 값이다.

### 3.2 `EITHER` — `UNKNOWN` 이 하던 두 일의 분해

두 방언을 모두 받는 모델이 있다(측정 결론은 [`model-capabilities.md`](model-capabilities.md)). 네 번째 상수가 필요한 이유는
"둘 다" 에 이름이 필요해서가 아니다. **`UNKNOWN` 이 하나의 동작이 아니라 두 동작이었고, 두 방언을 받는 모델은 그중 하나만
원하기 때문이다.**

| 자리 | `UNKNOWN` 의 동작 | 두 방언을 받는 모델이 원하는 것 |
|---|---|---|
| 명명된 모드(`EXTENDED` · `ADAPTIVE`) | 명명된 모드를 그대로 따른다, 보고 없음 | 같다 — 두 모양 모두 동작하므로 번역할 이유가 없다 |
| `AUTO` | 아무것도 보내지 않고 보고한다 — 추측할 방언이 없다 | 다르다 — 모든 모양을 받는 모델에서 빈손으로 나가면 안 된다 |

세 상수로는 이것을 쓸 수 없다. `ADAPTIVE` 행은 동작하는 `extended` 요청을 번역해 버리고, `UNKNOWN` 은 `AUTO` 를 빈손으로
둔다. 네 상수는 두 쌍으로 나뉜다.

| 상수 | 표에 대해 말하는 것 | 요청이 쓰는 방언인가 | `AUTO` 에서 |
|---|---|---|---|
| `UNKNOWN` | 표가 **답하지 못한다** | 아니다 | 보내지 않고 보고 |
| `EITHER` | 표가 **두 모양을 모두 받는다고 답한다** | 아니다 | 선호 모양(§4.4) |
| `BUDGETED` | 이 모델의 요청은 budgeted 모양 | 그렇다 | 그 모양 |
| `ADAPTIVE` | 이 모델의 요청은 adaptive 모양 | 그렇다 | 그 모양 |

**불변식: `UNKNOWN` 과 `EITHER` 는 행이 가질 수 있는 값이고 요청이 쓰는 값이 아니다.** 리졸버의 방언 결정은 구체 방언
하나 또는 빈 값(파라미터 없음)만 돌려주고, 와이어 모양을 가르는 분기는 구체 방언만 받는다.

이름이 `BOTH` 가 아닌 것은 "둘 다" 가 "둘 다 보낸다" 로 읽히기 때문이다 — 그것은 불가능하다. 두 방언은 대부분 모델에서
서로 배타적이지만 `EITHER` 행 모델은 둘 다 받으므로, 중립 타입(`ModelCapabilities`, `ThinkingDialect`)의 서술은 보편 배타를
단언하지 않는다.

### 3.3 행이 틀렸을 때

내장 행의 방언이 틀리면 운영자는 그 모델 이름으로 설정에서 선언해 행을 대체한다. 선언은 행 전체를 대체하므로 방언만 적으면
안 된다 — 규칙은 [`configuration-surface.md`](configuration-surface.md) 에 있다. `EITHER` 가 틀린 경우(실제로는 한 모양을
거절하는 모델)의 피해는 다른 행보다 작다. `EITHER` 는 명명된 모드를 따르기만 하므로, 그때 나는 400 은 행이 없었어도 그
모드가 받았을 400 이다.

---

## 4. 모드 × 방언 해석표

### 4.1 해석표 — 정본

`OFF` 와 `NONE` 은 방언을 보기 **전에** 정한다. 두 상태 모두 `thinking` 파라미터를 싣지 않으므로 방언 조회가 막으려는
400 을 받을 수 없고, 파라미터 없는 요청에 대해 표에 물으면 아무도 필요 없는 질문에 대한 보고가 생긴다 — `AUTO` 에서
`NONE` 을 설정한 호출자도 이미 원하는 것을 말했다.

| 모드 | 방언 | 요청 | 보고 |
|---|---|---|---|
| `OFF` | 무엇이든 | 파라미터 없음 | 방언에 대해서는 없음. 설정된 effort · display 가 닿지 않으면 알린다(§8.3) |
| (`OFF` 아님), effort `NONE` | 무엇이든 | 파라미터 없음(§4.6) | 없음 |
| `EXTENDED` | `BUDGETED` | `{"type":"enabled","budget_tokens":N}` | 없음 |
| `ADAPTIVE` | `ADAPTIVE` | `{"type":"adaptive"}` + `output_config.effort` | 없음 |
| `EXTENDED` | `ADAPTIVE` | `{"type":"adaptive"}`, effort 는 의도에서 | 번역 보고 |
| `ADAPTIVE` | `BUDGETED` | `{"type":"enabled"}`, 예산은 의도에서 | 번역 보고 |
| `EXTENDED` · `ADAPTIVE` | `UNKNOWN` | 명명된 모드 그대로 | 없음 |
| `EXTENDED` | `EITHER` | `{"type":"enabled","budget_tokens":N}` — 따르고 번역하지 않는다 | 없음 |
| `ADAPTIVE` | `EITHER` | `{"type":"adaptive"}` + `output_config.effort` — 따른다 | 없음 |
| `AUTO` | `BUDGETED` · `ADAPTIVE` | 모델의 방언 | 없음 — `AUTO` 가 요청한 것이다 |
| `AUTO` | `EITHER` | `{"type":"adaptive"}` + `output_config.effort` | 없음 — `AUTO` 가 표에 물었고 표가 답했다 |
| `AUTO` | `UNKNOWN` | 파라미터 없음, `OFF` 와 같음 | 보고 — 표에 물었고 표가 답하지 못했다 |

`output_config.effort` 는 adaptive 모양에만 붙고 호출이 rung 을 말했을 때만 붙는다. 이 가드는 측정상 필요하다 —
`thinking` 파라미터 없이 보낸 `output_config.effort` 는 `claude-haiku-4-5` · `claude-sonnet-4-5` 에서 400 이다(2026-09-10
실측). 가드는 요청 빌더가 아니라 해석 결과 안에 있다. 어떤 모양을 만들었는지 아는 것이 해석이기 때문이다.

### 4.2 모순하면 번역하고 보고한다 — 거절도, 생략도 아니다

운영자의 모드가 알려진 방언과 모순되면 의도(`ReasoningEffort`)를 모델의 방언으로 번역하고 한 번 보고한다. 두 방언은 같은
중립 effort 를 두 가지로 쓴 것이고, `AnthropicThinkingBudgets` 가 두 방향의 매핑을 가진다.

- **그대로 보내면** 확정적인 400 이다 — 방언을 조회하는 이유가 바로 그 400 을 막는 것이다
- **생략하면** 요청한 thinking 을 버린다
- **거절하면** 실행을 실패시켜서 실패를 막는다. 거절이 400 보다 나은 것은 오류 메시지뿐이고, 번역은 같은 비용으로 동작하는
  요청을 준다

번역만이 실행과 의도를 함께 지킨다. 이것은 effort ladder 의 "생략하고 보고하며 올리지 않는다" 규칙과 충돌하지 않는다
([`request-parameters.md`](request-parameters.md)). effort 축에서 생략하면 서버 기본값으로 호출이 성공하지만, 방언 축에서
운영자를 글자 그대로 따르면 실패한 호출이다. 두 상황은 비교되지 않는다.

**가장 손실이 큰 모서리.** 명시한 `thinkingBudgetTokens` 가 adaptive 방언 모델을 만나면(§5.4 대로 그 예산은 `EXTENDED`
에서만 합법이므로 번역으로만 닿는다) 토큰 수에는 adaptive 대응물이 없다. 가장 가까운 rung 으로 옮기고 — 같은 거리면 아래
쪽, 모호한 수가 뜻했을 것보다 적게 생각하게 하는 쪽이 싼 실수다 — 보고가 토큰 수와 그 rung 을 함께 말한다.

번역 보고의 signature 는 모드·방언과 함께 모델 이름을 싣는다. 한 클라이언트의 모드는 하나지만 `LlmModel` 이 이름을 바꿀 수
있으므로, 같은 모드가 두 모델을 만나면 반복이 아니라 두 개의 소식이다.

### 4.3 `EITHER` 는 명명된 모드를 그대로 따른다

`EITHER` 모델에서 `EXTENDED` 와 `ADAPTIVE` 는 번역되지도 보고되지도 않는다. 아무것도 400 이 아니고 아무것도 설정과 다르지
않으므로, 번역은 정당화할 것이 없는 치환이다. 벤더가 한쪽 모양을 deprecated 로 표시한 모델이어도 같다 — 벤더 SDK 가 그
사실을 자기 문구로 말한다(§4.4). 코드에서 `UNKNOWN` 과 `EITHER` 는 명명된 모드 분기에서 같은 줄에 선다. `UNKNOWN` 은 표가
운영자와 모순될 수 없다는 뜻이고 `EITHER` 는 표가 운영자에게 동의한다는 뜻이다 — 같은 코드, 다른 두 문장이다.

### 4.4 `AUTO` — 표에 묻는 모드

`AUTO` 는 모델마다 다른 와이어 사실 대신 의도("생각하라")를 말하고 방언은 표가 채운다. 한 설정이 여러 Claude 모델을 쓰는
배포에 쓰일 수 있게 하는 값이다.

**표가 모르면 보내지 않고 보고한다.** 추측할 방언이 없고, 추측은 절반의 확률로 400 이다. 다른 모든 조용한 경우와 달리 보고하는
이유는 `AUTO` 만이 표에 답하는 대신 질문했기 때문이다 — 침묵은 동작하는 설정과 똑같아 보인다. 보고는 **파라미터**에 대한
진술이지 **모델**에 대한 진술이 아니라고 말한다. 기본으로 thinking 이 켜진 모델은 파라미터가 없어도 생각하고 그 블록은
캡처된다. 처방은 배포의 실제 모델 이름을 레지스트리에 등록하는 것이다.

**`EITHER` 에서는 adaptive 를 고른다 — 이 표에서 사실이 아니라 정책이 적용되는 유일한 칸이다.** 근거는 벤더의 것이다.

- 벤더의 모델별 thinking 표가 `EITHER` 행의 두 모델(`claude-opus-4-6` · `claude-sonnet-4-6`)에서 budgeted 모양을
  `deprecated` 로 표시한다
- 벤더 SDK 가 `claude-opus-4-6` 에 `thinking.type=enabled` 를 보내면 "adaptive 를 쓰라, 우리 테스트에서 성능이 더 좋다" 는
  경고를 호출마다 stderr 로 출력한다. SLF4J 와 이 클라이언트의 보고 집합 밖이다. SDK 의 대상 집합은 벤더 표보다 좁다
  (`claude-opus-4-6` 하나). 이것은 SDK 소스를 읽어 얻은 사실이고 발화를 관측한 것은 아니다
- 표는 이미 이 방향으로 해석하고 있다 — 벤더가 두 방언을 받는다고 적은 `claude-mythos` 계열의 한 모델을 내장
  `claude-mythos` 행은 `ADAPTIVE` 로 덮는다(그 행의 사정은 [`model-capabilities.md`](model-capabilities.md))

**정책은 클라이언트에 두고 enum 이나 행에 두지 않는다.** 행은 사실을 말하고 선호는 어떤 프로브도 측정하지 않았다. 네 번째
상수가 있기 때문에 사실(둘 다 받음, 측정 날짜와 함께 레지스트리에)과 정책(`AUTO` 는 adaptive, 인용과 함께 리졸버의 한
분기에)을 각자 답할 수 있는 다른 자리에 쓸 수 있다. 정책을 enum 에 두면 두 번째 provider 가 중립 타입을 통해 Anthropic
정책을 물려받는다. budget 을 원하는 운영자의 처방은 `thinkingMode: extended` 이고, `EITHER` 는 그것을 번역 없이 따르므로
이 선택은 함정이 아니다. 선호가 반대인 두 방언 모델이 나오면 `EITHER` 로는 그 사실을 적을 수 없다 — [`L-10`](../../backlog/llm-config-surface-open-items.md).

`AUTO` 는 명시 예산을 받지 않는다(§5.4).

### 4.5 기본은 `OFF` 다

`thinkingMode` 의 기본값은 `OFF` 다. `AUTO` 를 기본으로 하면 변경 기록을 읽지 않고 업그레이드한 모든 Anthropic 배포에서
thinking 이 켜지고 그만큼 과금된다.

`OFF` 는 **요청 파라미터만** 끈다. "모델이 생각하지 않는다" 는 뜻이 아니다 — 기본으로 thinking 이 켜진 모델은 생각하고, 그
블록의 캡처는 모드와 무관하게 일어난다([`reasoning-traces.md`](reasoning-traces.md)).

### 4.6 `NONE` 은 파라미터를 생략한다

`ReasoningEffort.NONE` 은 두 모드 모두에서 `thinking` 파라미터를 생략한다. `{"type":"disabled"}` 는 여러 모델이 400 으로
거절하고, 생략은 400 을 받을 수 없다. 대가를 숨기지 않는다 — 기본으로 thinking 이 켜진 모델에서 `NONE` 은 thinking 을 끄지
못한다.

`NONE` 요청은 thinking 파라미터가 없으므로 샘플링 파라미터는 thinking 규칙이 아니라 능력 게이트만 따른다. 표가 샘플링 거부를
기술한 모델에서는 생략되고, 표가 모르는 모델에 `temperature` 가 설정되어 있으면 실린다
([`request-parameters.md`](request-parameters.md)).

---

## 5. 깊이의 번역 — effort, budget ladder, adaptive effort

### 5.1 effort 의 출처 — 호출이 먼저, 그다음 클라이언트 설정

리졸버가 쓰는 effort 는 호출의 `LlmModel.reasoningEffort` 가 먼저이고, 없으면 `AnthropicConfig.reasoningEffort` 다.
`OpenAIConfig` 와 같은 우선순위다([`request-parameters.md`](request-parameters.md)). `AnthropicConfig` 가 이 필드를 갖는
이유는 `reasoningEffort` 가 공유 설정 키이기 때문이다 — 공유 키가 한 provider 에만 닿으면 절반의 배포에게 거짓이 된다.
필드는 nullable 이고 검증하지 않는다. 모든 enum 값이 합법이고, 모델이 그 값으로 무엇을 하는지는 설정이 거절할 일이 아니라
클라이언트가 보고할 일이다. `equals` 에 포함된다 — 빈 설정 블록으로 만든 `AnthropicConfig` 가 블록 없이 만든 것과 같다는
동일성이 이 필드까지 말하게 하려는 것이다.

effort 를 읽는 자리는 둘이다 — 요청이 thinking 을 실을지 정하는 게이트, 그리고 번역 보고가 이름을 대는 rung. 그래서
우선순위는 헬퍼 하나(`requestedEffort`)에 있고 두 자리가 함께 부른다. 하나만 바뀌면 게이트와 보고가 서로 다른 effort 를
말한다. 두 provider 의 헬퍼는 코드를 공유하지 않는다 — 두 모듈의 서로 무관한 설정 타입 위의 두 줄이고, 어긋남은 provider
마다 같은 우선순위를 단언하는 테스트가 잡는다.

### 5.2 budget ladder — budgeted 방언

| rung | `budget_tokens` | 수의 출처 |
|---|---|---|
| `NONE` | — | 파라미터 없음(§4.6) |
| `MINIMAL` | 1024 | API 최소값이자 벤더가 단순한 작업에 권하는 출발점 |
| `LOW` | 2048 | 최소값의 두 배 — **임의** |
| `MEDIUM` | 4096 | 다시 두 배 — **임의** |
| `HIGH` | 16000 | 벤더가 복잡한 작업에 권하는 출발점 |
| effort 없음 | 4096 | `MEDIUM` — 중립 ladder 의 가운데 |

양 끝은 벤더가 공개한 수이고 가운데 둘은 아니다. 어느 것이 어느 것인지 적는 것이 이 표의 정직함 전부다. `MEDIUM` 과
`HIGH` 사이가 가장 넓은 것은 벤더 지침 자체가 선형이 아니라 이봉(1024 근처 / 16000 이상)이기 때문이다. 16000 위는 닿지
않으므로 "32k 초과 예산은 batch 로" 경고에서 떨어져 있다.

임의가 아닌 불변식은 넷이다.

1. **단조 비감소** — `ReasoningEffort` 의 선언 순서가 의미를 가지므로, 두 rung 을 뒤집는 매핑은 호출자의 의도를 조용히
   뒤집는다
2. **하한 1024** — 그 아래는 API 가 모든 요청에서 거절한다
3. **clamp** — 보내는 예산은 `min(요청 예산, max_tokens − 1)`(하한 1024)이고, clamp 되면 보고한다
4. **포기** — `max_tokens ≤ 1024` 면 합법 예산이 없으므로 파라미터를 생략하고 보고한다. 서버가 확실히 거절할 요청을 보내는
   것은 thinking 을 요청하지 않는 것보다 나쁘다

`AnthropicThinkingBudgets` 는 순수 함수다. clamp 를 보고하려면 clamp 전 값이 필요하므로 `requestedBudget(...)` 를 따로
노출하고, 비교와 기록은 리졸버가 한다.

### 5.3 adaptive effort 매핑

| `ReasoningEffort` | `output_config.effort` |
|---|---|
| `NONE` | — (파라미터 없음) |
| `MINIMAL` · `LOW` | `low` |
| `MEDIUM` | `medium` |
| `HIGH` | `high` |
| effort 없음 | 싣지 않는다 |

사다리에서 사다리로의 매핑이다. 실제 손실은 하나 — Anthropic 의 사다리가 `low` 에서 시작하므로 `MINIMAL` 과 `LOW` 가 겹친다.
벤더 범위의 윗단(`max`, 그리고 API 의 `xhigh`)은 두 가지 독립된 이유로 닿지 않는다. 중립 사다리에 `HIGH` 위가 없고, 쓰는 SDK
버전의 `OutputConfig.Effort` 에 `xhigh` 가 없다.

### 5.4 `thinkingBudgetTokens` — `EXTENDED` 전용 명시 예산

`AnthropicConfig` 생성자가 두 가지를 거절한다.

- **1024 미만** — 서버가 모든 요청에서 거절하므로 요청마다 실패하는 것보다 생성 시 실패가 낫다
- **모드가 `EXTENDED` 가 아닐 때** — adaptive 모양에는 예산 필드가 없고 `OFF` 는 파라미터를 싣지 않으므로, 그 값은 조용히
  무시될 값이다. `AUTO` 도 거절한다. 방언을 알기 전에는 숫자에 뜻이 없고, `AUTO` 에서는 요청을 만들 때까지 방언을 모른다

`auto` 와 `adaptive` 에서 thinking 의 양은 호출의 effort 가 정한다. 명시 예산은 어떤 rung 보다 우선한다 — 숫자를 쓴 운영자는
그 숫자를 뜻했다. 예산과 effort 가 둘 다 설정되어 있으면 요청이 어느 방언으로 나가든 한 번 보고한다(`thinkingBudgetOverridesEffort`).
signature 에 모델을 싣지 않는 것은 조건이 설정 쌍의 성질이고 결과("숫자가 이기고 rung 은 무시된다")가 두 방언에서 같아서
한 줄이 완결된 진술이기 때문이다. 명시 예산이 번역으로 adaptive 요청에 닿으면 §4.2 의 가장 가까운 rung 이 의도를 나른다.

설정 표면이 이 거절을 어떻게 키 경로와 함께 다시 던지는지는 [`configuration-surface.md`](configuration-surface.md) 가
정한다. 표면은 이 규칙을 다시 서술하지 않는다.

---

## 6. `AUTO` 예산 정책과 clamp 경고의 범위

### 6.1 산술

`BUDGETED` 행에서 `AUTO` 는 `EXTENDED` 와 같은 분기를 탄다. effort 가 없으면 요청 예산은 중간 rung 4096 이고, 보내는 예산은
`min(4096, max_tokens − 1)` 이다. `max_tokens` 는 호출의 `LlmModel` 값이 먼저이고 없으면 `AnthropicConfig.getMaxTokens()`
— 기본 4096 — 다. thinking 토큰이 `max_tokens` 에 포함되므로 4096 대 4096 이면 `budget_tokens: 4095` 가 나가고 답에 1토큰이
남는다. clamp 는 `REQUIRES_THINKING` finding `thinkingBudgetClamped=4096->4095` 로 기록되고, 클라이언트 인스턴스당 한 번
`maxTokens` 를 올리라는 WARN 으로 나간다. `MINIMAL`(1024)과 `LOW`(2048)는 4096 아래라 clamp 도 경고도 없다.

### 6.2 `AUTO` 예산 정책 — 1토큰 답도 그대로 둔다

`BUDGETED` 행 모델에서 `thinkingMode: auto` 가 답에 출력 토큰 하나만 남기는 예산으로 해석될 수 있고, **그대로 둔다.**

1. **clamp 는 `extended` 가 이 방언에서 하는 동작이고 `AUTO` 는 같은 분기를 탄다.** thinking 모드를 고른다고 `AnthropicConfig`
   의 기본 `maxTokens` 를 올리지도 않는다 — 무관한 설정의 부작용으로 문서화된 기본값을 바꾸는 일이다
2. **`AUTO` 의 계약은 표가 방언을 정하는 것이지 클라이언트가 양을 정하는 것이 아니다.** `AUTO` 전용 여유 규칙은 같은 모델·
   `max_tokens`·effort 에 운영자가 쓴 단어에 따라 다른 예산을 보내고, 리졸버에 예산 정책을 둘 둔다
3. **쓸 만한 답 여유는 측정된 적 없는 수다.** 어떤 비율·하한도 ladder 의 가운데 rung 처럼 임의로 골라진다
4. **경고가 처방을 말한다.** "Raise maxTokens" — 클라이언트 인스턴스당 첫 clamp 에서 한 번이다. 듣는 처방은 하나 더 있다
   (`reasoningEffort` 를 `low` · `minimal` 로 내리기). 경고가 그것을 말하지 않고 문구가 언제나 `only 1 tokens` 로 읽히는 것은
   [`L-15`](../../backlog/llm-config-surface-open-items.md) 다. 이 이유는 clamp 된 요청에만 해당한다(§6.4)
5. **운영자가 켜지 않으면 닿지 않는다.** 기본 모드는 `OFF` 다(§4.5)

### 6.3 누가 닿는가 — 개수가 아니라 모양

`max_tokens` 는 에이전트 정의의 `model.maxTokens` 가 먼저이고, `AnthropicConfig` 기본 4096 은 그것이 없을 때만 쓴다. 어느
설정 표면도 LLM `maxTokens` 를 바인딩하지 않는다. 그래서 영향받는 배포는 모양으로 말한다 — **`maxTokens` 가 없거나 요청 예산
(effort 없음 4096, `high` 16000) 이하인 에이전트.**

- CLI 번들 에이전트 정의는 모든 rung 보다 큰 `maxTokens` 를 적으므로 해당하지 않는다
- 에이전트 정의를 싣지 않는 스타터 앱은 `maxTokens` 를 적지 않는 한 해당한다
- 모델을 적지 않은 anthropic 배포는 클라이언트 기본 모델 `claude-sonnet-4-5`([`model-name-resolution.md`](model-name-resolution.md))로
  나가고, 그 이름은 `BUDGETED` prefix 행으로 해석되므로 역시 이 모양에 든다
- `max_tokens ≤ 1024` 면 예산이 들어가지 않아 파라미터를 생략하고 `thinkingBudgetImpossible` 로 보고한다

### 6.4 clamp 경고의 범위 — clamp 만

`thinkingBudgetClamped` 는 예산을 줄여야 들어간 요청만 덮고, 답 여유가 작은 요청 전부를 덮지 않는다. finding 은 다음 셋이
모두 성립할 때, 그리고 그때만 기록되고 방출된다.

1. **요청이 budgeted `thinking` 파라미터를 싣는다** — 모드가 `OFF` 가 아니고, 유효 effort(호출 먼저, 그다음 설정)가 `NONE`
   이 아니며, 방언이 `BUDGETED` 로 해석된다. 즉 `BUDGETED` · `EITHER` · `UNKNOWN` 행의 `extended`, `BUDGETED` 행의 `auto`,
   `BUDGETED` 행으로 번역된 `adaptive`
2. **`max_tokens` 가 1024 초과다** — 이하면 예산을 보내지 않고 `thinkingBudgetImpossible` 을 보고한다
3. **요청 예산이 `max_tokens` 이상이다** — 요청 예산은 명시 `thinkingBudgetTokens`, 없으면 effort 의 rung, 없으면 4096

기록되면 보내는 예산은 `max_tokens − 1` 이고 답에 남는 것은 언제나 정확히 1토큰이다. 방출과 로깅은 다르다 — 방출된 finding
은 클라이언트의 보고 집합이 그 `요청->전송` 쌍을 처음 볼 때만 로깅된다(보고 집합과 상한은 [`request-parameters.md`](request-parameters.md)).

**그 밖은 덮지 않는다.** `max_tokens` 미만의 요청 예산은 요청대로 나가고, 한 토큰 차로 들어맞아 clamp 와 같은 1토큰
최악을 남기는 요청도 경고 없이 나간다.

| 요청 | `max_tokens` | 보내는 `budget_tokens` | 명목상 남는 토큰 | 경고 |
|---|---|---|---|---|
| `auto`, `BUDGETED` 행, effort 없음 | 4096 | 4095 | 1 | `thinkingBudgetClamped=4096->4095` |
| 같음 | 4097 | 4096 | 1 | 없음 |
| 같음 | 4100 | 4096 | 4 | 없음 |
| `extended`, 예산 8000, `BUDGETED` · `EITHER` · `UNKNOWN` 행 | 8001 | 8000 | 1 | 없음 |
| 같음 | 8000 | 7999 | 1 | `thinkingBudgetClamped=8000->7999` |

"남는 토큰" 은 **명목값**이다. 벤더 문서상 budget 은 strict cap 이 아니라 target 이고 모델은 예산 전에 추론을 멈출 수 있으며,
`max_tokens` 만이 전체 출력의 상한이다(2026-09-10 확인). 벤더는 답 여유나 비율을 공개하지 않는다.

**경고를 넓히지 않는 이유.**

- **출처 있는 수가 없고, 문턱은 §6.2 가 거절한 그 수다.** 출처를 댈 수 있는 하한 — API 규칙의 1토큰(요청이 합법인 경계이지
  답할 수 있는 경계가 아니다), thinking 최소 1024, 벤더 예시, CLI 번들 값 — 과 비율은 모두 답이 가능한지의 경계가 아니다.
  실제로 들어가야 하는 것(대개 스키마와 맥락이 크기를 정하는 도구 호출)은 `max_tokens` 나 예산과 함께 커지지 않는다.
  운영자는 경고가 멈출 때까지 설정을 고치므로 어떤 문턱이든 배포의 실질 여유가 되고, 그 여유를 로그를 위해 고르는 것은
  요청을 위해 고르는 것과 같은 일이다. 게다가 비교하는 수치는 예산이 target 이라 결과가 아니다
- **이 요청들은 divergence 가 아니다.** clamp 경고는 "요청이 설정과 다르다" 는 `REQUIRES_THINKING` finding 이다. 4097 이나
  8000/8001 은 설정대로 나간다. §8.3 은 비활성·재구성된 조합을 보고하고, 따르되 현명하지 않다고 판단되는 요청은 보고하지
  않는다
- **실제로 짜인 결과는 일어날 때 보고된다.** `max_tokens` 에서 잘린 응답의 보고는
  [`max_tokens` 잘림 보고](../agent-execution/max-tokens-truncation-reporting.md)가 정하고, 수치를 요구하지 않는다. 그래서 clamp
  경고는 짜일 가능성을 추정해 경고하지 않는다
- **경고 문구가 참으로 남는다.** 경고가 뜨는 모든 요청은 정확히 1토큰을 남기고, `maxTokens` 인상은 그 요청에 듣는 처방이다

§6.2 의 이유 2(두 모드의 일관성)는 여기서 쓰지 않는다 — 문턱 경고는 `auto` 와 `extended` 에서 똑같이 뜨므로 두 모드를 가르지
않는다.

**대가.** 경고를 따라 가장 작게 — 4096 에서 4097 로 — 고친 운영자는 경고를 잃고 1토큰 최악을 그대로 가진다. thinking 이
예산을 다 쓰면 그들이 처음 보는 것은 요청 전의 한 줄이 아니라 잘린 답이거나, 잘림이 도구 호출 안에 떨어졌을 때 `max_tokens`
를 부르는 WARN 과 실행되지 않고 거절된 호출이다. 이 함정을 받아들인다. 대안은 출처 없는 수로 선을 긋는 것이고, 그것은 같은
함정을 선이 있는 곳으로 옮길 뿐이다.

### 6.5 재검토 조건

`AUTO` 예산 정책(§6.2)은 다음 중 하나가 일어나면 다시 본다.

- 쓸 만한 답 여유가 골라지지 않고 측정된다
- 벤더가 thinking 토큰을 `max_tokens` 에 세지 않게 바뀐다 — 산술이 사라진다
- `AUTO` 가 명시 예산을 받게 된다(§11) — 운영자가 경고가 말하는 그 숫자를 쓰게 되므로 모양이 바뀐다
- `AnthropicConfig` 의 기본 `maxTokens` 가 바뀐다 — §6.3 의 모집단이 움직인다

경고에 두 번째 처방을 넣는 것은 조건이 아니다 — 경고가 말하는 것을 바꿀 뿐 `AUTO` 가 하는 일을 바꾸지 않는다(L-15).

clamp 경고의 범위(§6.4)는 다음 중 하나가 일어나면 다시 본다.

- 답 여유가 측정되거나 벤더가 공개한다
- interleaved thinking 을 보낸다 — 예산이 `max_tokens` 를 넘을 수 있어 clamp 와 이 범위가 함께 바뀐다
- clamp 의 목표가 `max_tokens − 1` 이 아니게 된다 — "정확히 1토큰" 과 L-15 의 전제가 함께 사라진다
- 잘림 신호가 놓치는 짜임의 증거가 나온다. 알려진 누락은 이미 백로그에 있다 —
  [`L-25`](../../backlog/llm-config-surface-open-items.md)(백그라운드 포크의 잘린 답이 부모에게 완결처럼 보인다),
  [`L-26`](../../backlog/llm-config-surface-open-items.md)(잘린 최종 답을 돌려준 슬래시 스킬을 실행한 턴이 `COMPLETED`)

잘림을 보고하는 방식이 바뀌는 것은 조건이 아니다.

---

## 7. `display` — adaptive 모양에만

### 7.1 무엇을 요청하는가

현재 모델 세대에서 adaptive 요청의 `display` 기본값은 `omitted` 다. 그 요청은 재전송해야 할 서명된 thinking 블록은 받지만
읽을 수 있는 thinking 텍스트는 받지 못한다. 그래서 사람이 숙고를 보려면 전송만으로는 부족하고 요청 쪽이 `display` 를 물어야
한다([`streaming.md`](streaming.md) 가 요청과 전송을 함께 두는 결정을 가진다).

`AnthropicConfig.thinkingDisplay` 가 있으면 리졸버가 adaptive 모양에 `display` 를 싣는다. SDK 의 `ThinkingConfigAdaptive` 는
`type` 외의 필드를 모델링하지 않으므로 SDK 자신의 탈출구 `putAdditionalProperty` 로 쓴다 — 이 저장소가 모델링되지 않은 요청
필드를 쓰는 방식이고, SDK 가 필드를 얻으면 한 줄이 바뀐다.

| 측정(2026-09-10) | 결과 |
|---|---|
| adaptive `thinking` 에 `display` 값을 바꿔 가며 보냄(음성 대조군 포함) | `summarized` · `omitted` 만 200, 나머지는 같은 검증 오류로 400 — 필드는 무시되지 않고 검증되며 허용 집합은 정확히 `{summarized, omitted}` |
| `display: summarized` + 숙고할 만한 프롬프트 | thinking 텍스트가 실제로 온다. 쉬운 프롬프트는 adaptive 가 생각하지 않기로 해 빈 블록일 수 있다 |

`display` 는 요청에 있으면 비스트리밍 경로의 요청에도 실린다. 요청 빌더를 두 진입점이 공유하기 때문이며, 렌더할 곳이 없어도
같은 토큰을 쓴다. 기본이 off 인 이유 중 하나다.

### 7.2 모양 — `SUMMARIZED` 하나, 부재가 off

`AnthropicThinkingDisplay` 는 상수 `SUMMARIZED` 하나다. 키가 없으면 `display` 를 싣지 않고, 요청은 이 키가 없던 때와 바이트
단위로 같다.

- **`OFF` 상수가 없다.** 박싱된 null 이 이미 "쓰지 않음" 을 말하고(`thinkingBudgetTokens` 와 같다), `off` 는 YAML 1.1
  예약어다
- **`OMITTED` 상수가 없다.** `omitted` 는 서버 기본값이라 키 부재와 동작이 같다. 더 나쁘게는 모순이 된다 — 이 키는 추론 델타
  전달 게이트도 여는데, 게이트는 값이 아니라 **키의 존재**로 열린다. `omitted` 를 쓸 수 있게 하면 adaptive 에서는 "채널을
  열되 비워 달라", `extended` 에서는(budgeted 모양에는 `display` 를 싣지 않으므로) "숙고를 스트리밍하라" 는, 단어와 반대
  뜻의 설정이 생긴다
- **벤더의 열거를 복제하지 않는다.** 이 타입 가족은 벤더 어휘를 이 저장소의 어휘로 옮긴다 — `AnthropicThinkingMode` 에서
  `EXTENDED` 는 와이어의 `enabled` 를 쓰고, `OFF` 는 아무것도 쓰지 않고, `AUTO` 는 어느 방언의 와이어 값도 아니다
- **상수가 하나라고 boolean 으로 하지 않는다.** 출하된 설정 키를 나중에 boolean 에서 enum 으로 넓히는 것은 깨지는 타입
  변경이고, 허용 집합은 서버가 넓힐 수 있다 — 다른 provider 의 같은 요청(`reasoning.summary`)은 이미 세 입도를 갖는다.
  하나짜리 enum 은 추가로 넓어진다

### 7.3 budgeted 모양에는 싣지 않는다

`ThinkingConfigEnabled` 는 `budget_tokens` 와 `type` 을 가진다. budgeted 모양이 `display` 를 받는지는 측정되지 않았다. 그
방언에서는 `thinking_delta` 가 이미 오므로 얻는 것 없이 400 위험만 사게 된다. 그래서 싣지 않는다.

이 키의 다른 절반 — 전달 게이트 — 은 그대로 동작하므로, budgeted 요청에서 `thinkingDisplay` 를 쓴 운영자는 thinking 텍스트를
**본다.** 그 출력은 `display` 가 나갔다는 증거가 아니므로, 단어 자체는 아무 데도 닿지 않았다는 것을 한 번 알린다
(`thinkingDisplayOnBudgetedDialect`, §8.4).

---

## 8. 보고 계약

### 8.1 R-WHEN — 조립 중에는 보고하지 않는다

Anthropic thinking 경로의 경고는 **실제로 보내는 요청만** 설명한다. 해석에는 서로를 되돌리는 단계가 있다 — 모드를 모델
방언으로 번역한 뒤, `max_tokens` 아래에 합법 예산이 없어 파라미터를 통째로 버릴 수 있다. 조립 도중에 보고하면 보내지 않는
요청을 설명하게 된다.

> **무언가를 알아챈 단계는 finding 을 기록한다. 완성된 요청이 어느 finding 을 방출할지 정한다.** 일반화하면 — 같은 해석의
> 뒤 단계가 finding 이 설명하는 대상을 바꾸거나 버릴 수 있으면 finding 을 모아 두고, 결과를 아는 해석 바깥에서 보고한다.

finding 은 둘 중 하나의 scope 를 가지고, scope 는 기록의 일부다.

| scope | 뜻 | 방출 조건 |
|---|---|---|
| `REQUIRES_THINKING` | 요청이 설정과 다르다 | 완성된 요청이 `thinking` 파라미터를 싣는다 |
| `EXPLAINS_ABSENCE` | thinking 설정이 아무 데도 닿지 않는다 | 싣지 않는다 |

`AnthropicThinkingResolution.findingsToReport()` 가 이 필터를 적용하고, **필터되지 않은 목록을 돌려주는 접근자는 없다.** 그래서
호출 지점에서 필터를 잊을 수 없다.

**중복 제거는 방출 시점에만 한다.** 클라이언트의 보고 집합은 살아남은 finding 만 본다. 버려진 finding 이 signature 를 소비하면
그 문구는 프로세스가 끝날 때까지 침묵하는데, 한 번에 경고 하나를 단언하는 테스트로는 보이지 않는다. 기록하면서 중복을
제거하는 것이 자연스러운 실수이므로 적어 둔다.

세 단어를 구분해 쓴다 — **기록**(단계가 finding 을 남김) → **방출**(`findingsToReport()` 통과) → **로깅**(클라이언트 보고
집합이 그 signature 를 처음 보고 상한 전일 때 WARN). 보고 집합의 규칙은 [`request-parameters.md`](request-parameters.md) 에
있다.

**대가.** 모드도 틀리고 `max_tokens` 도 작은 운영자는 한 번에 하나씩 배운다 — 파라미터를 버린 요청에서는 부재를 설명하는 한
줄만 나가고, 번역 보고는 `maxTokens` 를 올린 다음 실행에서 나타난다. 둘 중 하나가 존재하지 않는 요청을 설명하는 두 줄보다,
행동할 수 있는 한 줄이 낫다.

### 8.2 리졸버는 로거 없이 계산한다

R-WHEN 은 규율이 아니라 구조로 강제한다.

- `AnthropicThinkingResolver` 는 package-private 이고, 필드는 생성 시 받은 `AnthropicConfig` 하나다. **로거가 없고**, 클라이언트의
  private `reportDivergence` 에 닿지 못한다. finding 이 운영자에게 가는 길은 `findingsToReport()` 하나뿐이다
- 상태가 설정뿐이라 스레드 안전하고, 클라이언트가 수명 동안 하나를 들고 요청마다 한 번 부른다
- `AnthropicThinkingResolution` 은 불변이다 — `parameter()`, `outputConfigEffort()`(adaptive 모양이고 rung 이 있을 때만),
  `findingsToReport()`
- `AnthropicLlmClient.buildRequest` 가 결과의 파라미터와 effort 를 요청에 싣고, 살아남은 finding 을 `reportDivergence` 로
  넘긴다

클라이언트의 보고 중 thinking 파라미터를 설명하는 것도 같은 규칙을 따른다. 재전송을 끈 채 thinking 을 요청했을 때의 경고
(`replayOffWhileThinking@<방언>`, 뜻은 [`reasoning-traces.md`](reasoning-traces.md))는 리졸버 밖에서 **완성된** 파라미터와 그
방언을 보고 판단한다. 번역된 요청은 설정 모드와 방언이 다를 수 있기 때문이다.

`AnthropicDivergenceReporter` 는 발견 코드를 협력자에 두면서 보고를 클라이언트의 로거와 보고 집합에 남기려고 있는 인터페이스다.
메시지 변환기와 스트리밍 매퍼에는 전달되지만 리졸버에는 전달되지 않는다. 남는 위험은 누군가 리졸버에 로거를 더하는 것이고,
그것은 쉽게 저지르는 사고가 아니라 리뷰에서 보이는 한 줄이다(§10).

### 8.3 R-WHAT — 무엇이 한 줄의 가치가 있는가

> 비활성이거나 재구성된 조합은 다음 **중 하나**가 성립할 때 보고한다.
>
> **(a)** 운영자가 설정한 **다른 값을 되돌리지 않고** 설정한 키를 동작하게 만드는 변경이 있다 — 설정이 *불완전*하다
>
> **(b)** 보고가 없으면 운영자가 **관찰한 것에서 거짓 결론**을 낸다 — 버그처럼 보이는 부재, 또는 증거처럼 보이는 출력
>
> 가능한 처방이 "요청한 다른 것을 되돌려라" 뿐이면 두 키는 **불완전한 설정이 아니라 서로 배타인 쌍**이고, 운영자는 이미 둘
> 중 하나를 골랐으므로 클라이언트는 침묵한다.

규칙은 **설정된 값만으로** 판정할 수 있다. "중화하는 설정이 운영자가 쓰지 않은 기본값일 때 보고" 라는 대안은 판정할 수 없다 —
`AnthropicConfig` 는 `thinkingMode` 의 출처를 갖지 않고 그 기본값이 `OFF` 이므로, 그 규칙을 구현하려면 설정 타입을 넓혀야
한다. 같은 이유로 값이 설정에서 왔는지 에이전트 정의에서 왔는지는 보고 여부를 바꾸지 않는다.

| 조합 | (a) | (b) | 판정 |
|---|---|---|---|
| `reasoningEffort`(`NONE` 아님) + `thinkingMode: off` | 있다 — 모드를 켠다 | — | **보고** |
| `thinkingDisplay` + `thinkingMode: off` | 있다 — 모드를 켠다 | — | **보고** |
| `thinkingDisplay` + budgeted 방언 | 처방이 필요 없다 | 있다 — 텍스트가 흐르므로 `display` 가 나갔다고 믿는다 | **보고** |
| `thinkingBudgetTokens` + `reasoningEffort` | 한쪽을 지우는 것이 처방이지만, 두 키는 같은 의도("이만큼 생각하라")를 두 어휘로 쓴 것이라 배타인 쌍이 아니다 — 운영자는 한쪽이 다른 쪽을 가린다는 것을 모른다 | — | **보고** |
| `AUTO` + 표가 모르는 모델 | 있다 — 모델 이름을 등록한다 | 있다 — 침묵이 동작하는 설정처럼 보인다 | **보고** |
| `reasoningEffort: none` + `thinkingMode: off` | 없다 — 둘은 같은 뜻이다 | 없다 | **침묵** |
| `thinkingDisplay` + `reasoningEffort: none` | 없다 — 처방은 명시적으로 쓴 "추론하지 말라" 를 되돌리는 것뿐이다 | 없다 — 텍스트 없음이 `none` 이 요청한 것이다 | **침묵** |
| `thinkingMode: extended` + `EITHER` 모델 | 비활성도 재구성도 없다 | — | **침묵** — 벤더 deprecation 은 SDK 가 벤더 문구로 말한다 |

`reasoningEffort`(`NONE` 아님) + `OFF` 는 기동을 거절하지 않고 보고한다. `OFF` 는 기본값이고 처방은 **두 번째 키**다. 처방이
다른 키인 조합으로 기동을 실패시키면 기본값 아래의 합리적인 설정 — "더 생각하게 하라" — 이 기동 실패가 된다. 이 보고와
`AUTO` + 모르는 모델의 보고는, 기본으로 thinking 이 켜진 모델은 그래도 생각할 수 있다고 함께 말한다.

`AUTO` + 표가 모르는 모델에서 `thinkingDisplay` 도 아무 데도 닿지 않지만 따로 말하지 않는다 — `thinkingDialectUnknown` 보고가
`AUTO` 가 아무것도 답하지 못했고 그 위에 탄 설정도 마찬가지라는 것을 이미 말한다.

### 8.4 finding 목록

signature 는 중복 제거 키이며 로그 줄에는 나오지 않는다. 목표는 틀린 문구를 줄이는 것이지 문구 수를 줄이는 것이 아니다.

| signature | scope | 기록 조건 |
|---|---|---|
| `thinkingDialectTranslated=<모드>-><방언>@<모델>` | `REQUIRES_THINKING` | 명명된 모드가 알려진 방언과 모순(§4.2). 명시 예산이 있으면 문구가 토큰 수와 가장 가까운 rung 을 함께 말한다 |
| `thinkingBudgetOverridesEffort=<예산>@<effort>` | `REQUIRES_THINKING` | 명시 예산과 effort 가 둘 다 있다 — 어느 방언이든 한 번(§5.4) |
| `thinkingBudgetClamped=<요청>-><전송>` | `REQUIRES_THINKING` | clamp(§6.4) |
| `thinkingDisplayOnBudgetedDialect=<display>` | `REQUIRES_THINKING` | budgeted 방언 요청에 `thinkingDisplay`(§7.3) |
| `thinkingDialectUnknown@<모델>` | `EXPLAINS_ABSENCE` | `AUTO` + 표가 모르는 모델(§4.4) |
| `thinkingBudgetImpossible=<maxTokens>` | `EXPLAINS_ABSENCE` | budgeted 분기에서 `max_tokens ≤ 1024` |
| `reasoningEffortWithThinkingOff=<effort>` | `EXPLAINS_ABSENCE` | `OFF` + `NONE` 이 아닌 effort |
| `thinkingDisplayWithThinkingOff=<display>` | `EXPLAINS_ABSENCE` | `OFF` + `thinkingDisplay` |

분류가 형식이 아닌 이유는 두 행이 보여 준다. `thinkingDisplayOnBudgetedDialect` 는 합법 예산을 찾기 **전에** 기록되므로, 예산이
없어 파라미터가 버려지면 함께 버려져야 한다. `thinkingDialectUnknown` 은 thinking 이 전혀 없는 요청에 대해 말하는 유일한
것이므로 번역 보고와 같은 scope 에 두면 완전히 침묵한다.

**적용 사례 — 나중에 버려지는 요청.** `thinkingMode: adaptive` + `BUDGETED` 모델 + `maxTokens ≤ 1024` 에서 번역,
display-on-budgeted, budget-overrides-effort 는 모두 `REQUIRES_THINKING` 이라 버려지고, `thinkingBudgetImpossible` 한 줄만 나간다.
요청은 어떤 방언도 말하지 않으므로 앞의 것들은 모두 거짓이었을 것이다.

**적용 사례 — 번역된 adaptive 요청의 effort.** `EXTENDED` + `thinkingBudgetTokens` + 호출의 `reasoningEffort` 가 adaptive 방언
모델을 만나면, 번역 보고는 예산과 그것이 된 rung 을 말하지만 **다른** rung 이 설정되었다가 버려졌다는 것은 말하지 않는다. 그
공백은 `thinkingBudgetOverridesEffort` 가 채운다. 이 finding 은 우선순위를 적용하는 단계에서 두 방언 모두에 대해 기록되므로,
어느 분기도 그것을 기억할 필요가 없다.

---

## 9. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| boolean 모드 하나 — `{"type":"enabled"}` 를 보내거나 `{"type":"adaptive"}` 를 보내거나, 또는 위험한 기본값을 문서화한 `boolean adaptive` | 두 값 모두 반대 방언 모델에서 400 이라 **어떤** 기본값도 절반의 모델에서 틀린다. budgeted 전용 스위치는 현재 세대 전부가, adaptive 전용 스위치는 이전 세대 전부가 거절한다. `UNKNOWN` 은 세 번째 방언이 아니라 사실의 부재이며, 대부분의 이름에 대해 표가 실제로 그 상태에 있다 |
| 모드가 방언과 모순되면 요청을 거절 | 호출을 실패시켜서 호출 실패를 막는다. 400 을 그냥 두는 것보다 나은 것은 오류 메시지뿐이고, 번역은 같은 비용으로 동작하는 요청을 준다(§4.2) |
| 두 방언을 받는 모델을 표에서 빼고 주석으로만 기록 | 그 모델들이 `UNKNOWN` 으로 해석되어 `UNKNOWN` 이 "측정상 둘 다 받음" 과 "미측정" 을 계속 함께 뜻한다. `AUTO` 는 모든 모양을 받는 모델에서 빈손이 되고, `extended` 배포가 듣는 것은 SDK 의 호출마다 stderr 한 줄뿐이다 |
| 두 방언을 받는 모델에 `ADAPTIVE` 행, 새 상수 없음 | 가장 작은 변경이고 두 번 틀린다. 운영자가 정확한 토큰 예산과 함께 명시한, 동작하는 `extended` 요청을 벤더 **선호**를 근거로 번역한다 — 번역의 정당화는 "그대로 보내면 확정적 400" 인데 여기서는 거짓이다. 그리고 번역 보고가 "다른 모양을 400 으로 거절한다" 고, 그 모양을 받는다고 측정된 모델에 대해 말하게 된다 — 보내는 요청이 아닌 요청을 설명하는 경고다 |
| 선호를 담은 상수(`ADAPTIVE_PREFERRED` · `BUDGETED_PREFERRED`) | 어떤 프로브도 측정하지 않은 `AUTO` 정책을 행에 넣고, 선호가 다른 두 방언 모델마다 상수가 늘어난다. 사실은 레지스트리에, 정책은 리졸버의 한 분기에 두어 각자 인용할 수 있게 한다(§4.4) |
| 집합 값 `thinkingDialect()`(`Set<ThinkingDialect>`, `acceptedReasoningEfforts` 선례) | 검토했으나 당시 범위 사유로 채택하지 않았다 — 설계상 판단은 기록되지 않았다. 채택한 네 상수 enum 의 이유는 §3.2(`UNKNOWN` 이 하던 두 일의 분해)와 §4.4(선호 정책을 enum 밖에)에 있다. 선호가 반대인 모델이 나오면 선택지 중 하나로 다시 오른다(L-10) |
| 보고 계약을 클라이언트 안의 로컬 finding 목록으로, 새 클래스 없이 | 크기는 절반이고 결함도 고친다. 그러나 계약이 규율로 남는다 — 이후 작성자가 해석 안에서 private `reportDivergence` 를 다시 부를 수 있고 리뷰 외에는 막을 것이 없다. 로거 없는 클래스 뒤로 해석을 옮기면 "하지 않기로 문서화됨" 이 "할 수 없음" 이 된다 |
| 완성된 요청에서 보고를 다시 계산, 버퍼 없음 | 번역이 일어났는지 알려면 방언 결정을 두 번째 자리에서 다시 도출해야 한다 — 해석표의 구현이 둘이 되고 첫 어긋남은 보이지 않는다. 버퍼는 결정을 하나로 두고 방출만 옮긴다 |
| 가변 `List<Finding>` 을 out-parameter 로 내려 보냄 | 같은 것을 강제하지만 해석의 답이 둘(하나는 변경으로)이 되고, `parameter().isPresent()` 필터를 둘 한 자리가 없어진다 |
| `thinkingDisplay` + `reasoningEffort: none` 을 보고 | R-WHAT 의 두 조건을 모두 통과하지 못한다. 보고하면 반대 방향의 조언이 된다 — `OFF` 아래 effort 보고가 `NONE` 을 제외하는 것과 같은 이유다 |
| `EITHER` 모델에서 `extended` 가 deprecated 모양이라고 보고 | 비활성도 재구성도 없고, SDK 가 이미 벤더 자신의 문구를 매 호출 출력한다. 다른 방식으로 중복 제거되는 의역은 한 주제에 두 목소리다 |
| `OFF` 아래 `reasoningEffort` 를 기동 시 거절 | `OFF` 가 기본값이고 처방이 두 번째 키다. 합리적인 설정의 기동을 실패시키고, 실행되지 않는 분기 밖의 검사가 유효한 설정을 기동 실패로 만든다는 경고와 같은 모양이다(§8.3) |
| `AUTO` 가 명시 `thinkingBudgetTokens` 를 받게 한다 | `nearestEffort` 가 있어 거의 공짜지만, `AnthropicConfig` 검증과 클라이언트 의미를 바꾸는 코어 동작 변경이다. 그리고 adaptive 경우에 조용함을 되살린다 — adaptive 전용 모델에 대해 설정한 토큰 수가 rung 으로 바뀌고 숫자 자체는 생성 시 신호 없이 버려진다(§11) |
| `AUTO` 에서만 답 여유를 확보(비율 또는 하한) | `auto` 와 `extended` 가 같은 모델에서 다른 예산을 보내고, 여유는 측정된 적 없는 수다(§6.2 이유 2·3) |
| `AUTO` 에서 답을 남길 수 없으면 아무것도 보내지 않음 | `AUTO` 가 측정된 사실에 따라 행동하라고 둔 행에서, 바로 `BUDGETED` 라고 말하는 세 행 위에서 `AUTO` 를 다시 빈손으로 돌린다. 그리고 `extended` 는 계속 clamp 하므로 역시 두 모드가 갈린다 |
| thinking 모드를 고르면 `AnthropicConfig` 의 기본 `maxTokens` 를 올림 | 더 조용하지만 무관한 설정의 부작용으로 문서화된 기본값을 바꾼다(§6.2 이유 1) |
| clamp 경고에 답 여유 하한 | 출처 있는 하한이 모두 답이 가능한지의 경계가 아니고, 비교하는 수치는 target 이라 결과가 아니다(§6.4) |
| clamp 경고에 예산 대 `max_tokens` 비율 | 어떤 비율도 출처가 없고, 들어가야 하는 것(도구 호출이나 답)이 `max_tokens` 나 예산과 함께 커지지 않으므로 두 설정의 비율은 어느 쪽도 재지 못한다(§6.4) |
| `EXTENDED` / `ADAPTIVE` + `NONE` 에서 `{"type":"disabled"}` 전송 | 여러 모델이 400 으로 거절하고, 생략은 400 을 받을 수 없다. adaptive 에서만 보내고 400 으로 가르치는 것은 조용한 과소 전달을 호출자가 의도하지 않았을 수 있는 경로의 시끄러운 실패와 바꾼다(§4.6) |
| `interleaved-thinking` beta 헤더를 보내 도구 호출 사이에서도 생각하게 함 | 배포에 보이는 변경이고 자체 모델별 매트릭스(받고 무시, 효과 없음, deprecated)가 있으며 `budget_tokens` 를 `max_tokens` 에 세는 방식을 바꾼다. 다른 무엇에도 얹을 수 없고 자기 게이트가 필요하다. 그래서 budgeted 방언 모델은 첫 도구 호출 전에만 생각하고 호출 사이에서는 생각하지 않는다 — 오는 블록은 올바르게 왕복한다 |
| `display` 에 `OMITTED` 상수, 또는 boolean 키 | §7.2 |

---

## 10. 하지 말 것

- **리졸버에 로거나 reporter 를 넘기지 않는다.** `AnthropicThinkingResolver` 에 `Logger`, `AnthropicDivergenceReporter`,
  클라이언트 메서드 참조를 주면 보고 계약이 규율로 되돌아가고, 보내지 않는 요청을 설명하는 경고가 다시 가능해진다
- **finding 을 기록하면서 중복 제거하지 않는다.** signature 는 방출된 finding 만 소비한다. 버려진 finding 이 signature 를
  소비하면 그 문구가 프로세스 끝까지 침묵한다
- **필터되지 않은 finding 목록을 꺼내는 접근자를 만들지 않는다.** `findingsToReport()` 가 유일한 접근자여야 호출 지점에서
  scope 필터를 잊을 수 없다
- **`UNKNOWN` 이나 `EITHER` 를 와이어 모양 분기에 흘리지 않는다.** 방언 결정은 구체 방언 또는 빈 값만 돌려준다
- **`ThinkingDialect` 에 상수를 더할 때 컴파일러를 믿지 않는다.** 트리 안의 읽기는 모두 `==` 비교라 컴파일러가 바뀌어야 할
  읽기를 찾아 주지 않는다 — 읽어서 찾는다. `UNKNOWN` · `EITHER` 를 알아야 하는 읽기는 명명된 모드와 `AUTO` 의 방언 선택
  두 곳이고, 와이어 모양 분기는 구체 방언만 받으므로 바꾸지 않는다. 트리 밖의 exhaustive `switch` 는 깨진다
- **`AUTO` × `EITHER` 선호를 enum 이나 행에 넣지 않는다.** 행은 측정한 사실만 말한다
- **알려진 방언과 모순되는 모드를 그대로 보내지 않는다.** 확정적인 400 이다. 번역하고 보고한다
- **`output_config.effort` 를 budgeted 모양에 붙이지 않는다.** 측정상 두 모델에서 400 이다
- **`display` 를 budgeted 모양에 싣지 않는다.** 받는지 측정되지 않았고, 그 방언에서는 텍스트가 이미 온다
- **`thinking.type: disabled` 를 보내지 않는다.** `NONE` 은 생략이다
- **기본 `thinkingMode` 를 `OFF` 에서 옮기지 않는다.** 업그레이드만으로 과금이 켜진다
- **clamp 경고에 하한이나 비율을 더하지 않는다** — §6.5 의 조건이 먼저다
- **벤더 deprecation 문구를 의역해 다시 보고하지 않는다**

---

## 11. 남은 것

- **선호가 budgeted 쪽인 두 방언 모델** — `EITHER` 는 선호를 싣지 않으므로 그런 모델이 나오면 적을 자리가 없다.
  [`L-10`](../../backlog/llm-config-surface-open-items.md)
- **clamp 경고 문구** — 언제나 `only 1 tokens` 로 읽히고 듣는 처방 둘 중 하나만 말한다.
  [`L-15`](../../backlog/llm-config-surface-open-items.md)
- **잘림 신호가 놓치는 짜임** — clamp 경고 범위의 재검토 조건 곁에 있는 두 누락.
  [`L-25`](../../backlog/llm-config-surface-open-items.md) · [`L-26`](../../backlog/llm-config-surface-open-items.md)
- **`AUTO` 가 명시 예산을 받아야 하는가** — §9 에서 기각했지만 이유는 불가능이 아니라 범위와 조용함이다. 받게 되면
  `AnthropicConfig` 검증과 클라이언트 의미가 함께 바뀌고 §6.5 의 재검토 조건이 발화한다. 미등록
- **기본 모드를 `OFF` 에서 `AUTO` 로 옮길 것인가** — 업그레이드만으로 thinking 과 과금을 켜는 동작 변경이라 별도 결정이
  필요하다. 미등록
- **budgeted 모양이 `display` 를 받는가** — 측정되지 않았다(§7.3). 받는다면 budgeted 배포가 이미 받는 텍스트의 더 풍부한
  형태를 요청할 수 있다 — 결함이 아니라 놓친 기회다. 미등록
- **interleaved thinking** — 자체 게이트와 모델별 매트릭스가 필요해 착수하지 않았다(§9). 보내게 되면 §6.4 가 바뀐다. 미등록
- **보고가 읽힌다는 현장 증거** — `AUTO` + 모르는 모델, `OFF` 아래 effort 는 이 설계가 동작 대신 로그 한 줄을 두는 자리이고,
  운영자가 그것을 읽는다는 증거는 없다. 미등록

---

## 부록 — 참조 파일 지도

경로는 `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/` 기준, `ThinkingDialect` 만
`modules/aimon-core/src/main/java/at/aimon/core/llm/capability/`.

| 파일 | 무엇을 확인하나 |
|---|---|
| `ThinkingDialect.java` | 네 상수, 두 쌍, "행 값은 요청이 쓰지 않는다" 불변식, 효과 전용 상수를 두지 않는 이유 |
| `AnthropicThinkingMode.java` | 네 모드, 두 400 문구 인용, `AUTO` 의 뜻과 명시 예산을 받지 않는 이유, 기본 `OFF` |
| `AnthropicThinkingResolver.java` | 로거 없음, `OFF`·`NONE` 을 방언 전에 정하는 순서, 해석표(`resolveDialect` · `resolveAutoDialect` · `resolveNamedDialect`), `requestedEffort`, finding 기록 지점과 scope, `display` 쓰기 |
| `AnthropicThinkingResolution.java` | `Scope` 둘, `findingsToReport()` 가 유일한 접근자, 방출 뒤 중복 제거 |
| `AnthropicThinkingBudgets.java` | budget ladder 와 수의 출처, `requestedBudget` · `budgetFor`(clamp · 포기) · `nearestEffort` · `effortFor` |
| `AnthropicThinkingDisplay.java` | `SUMMARIZED` 하나, 부재가 off, `OMITTED` · boolean 을 두지 않는 이유, budgeted 모양 제외 |
| `AnthropicConfig.java` | `thinkingMode` 기본 `OFF`, `thinkingBudgetTokens` 생성 시 검증, `reasoningEffort`, `thinkingDisplay`, 기본 `maxTokens` |
| `AnthropicLlmClient.java` | `buildRequest` — 해석 한 번, `outputConfig` 는 해석 결과로만, `findingsToReport()` → `reportDivergence`, `reportIfReplayIsOffWhileThinking` 가 완성된 파라미터를 읽는 자리, 전달 게이트에 `display` 존재를 넘기는 자리 |
| `AnthropicDivergenceReporter.java` | 변환기·매퍼용 보고 인터페이스 — 리졸버에는 전달되지 않는다 |
| `InMemoryModelCapabilityRegistry.java` (core) | `BUDGETED` · `EITHER` 방언 행 — 근거는 `model-capabilities.md` |

---

## 관련 문서

- [`model-capabilities.md`](model-capabilities.md) — 방언 행과 그 실측 결론, 모델 이름을 보지 않는다는 원칙
- [`request-parameters.md`](request-parameters.md) — effort 우선순위, thinking 과 샘플링 생략의 순서, divergence 보고 집합과 상한
- [`reasoning-traces.md`](reasoning-traces.md) — thinking 블록 캡처·재전송과 `replayThinkingBlocks`
- [`streaming.md`](streaming.md) — 추론 델타 채널과 설정으로 여는 전달 게이트
- [`configuration-surface.md`](configuration-surface.md) — thinking 키의 네임스페이스·바인딩·기동 실패
- [`openai-responses-path.md`](openai-responses-path.md) — 다른 provider 의 숙고 요청(`reasoning.summary`)
- [`model-name-resolution.md`](model-name-resolution.md) — 클라이언트 기본 모델
- [`../agent-execution/max-tokens-truncation-reporting.md`](../agent-execution/max-tokens-truncation-reporting.md) — `max_tokens` 에서 잘린 응답의 보고
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — L-10 · L-15 · L-25 · L-26
