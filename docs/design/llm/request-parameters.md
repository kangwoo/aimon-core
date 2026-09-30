# 요청 파라미터 조립 (Request Parameters)

> Status: **IMPLEMENTED** — 두 provider 클라이언트가 `LlmModel` · 클라이언트 설정 · `ModelCapabilities` 로부터
> 샘플링 파라미터와 reasoning effort 를 싣거나 생략하고, 설정과 다르게 나가는 요청을 divergence 보고로 알린다.
> 남은 것은 §8.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm`(`LlmModel`, `ReasoningEffort`),
> `…llm.capability`(`ModelCapabilities` 의 샘플링·effort 필드를 읽는 쪽), `…subagent.execution`(`SubagentLlmDefaults`) ·
> `aimon-llm-openai` — `OpenAiRequestParameters`, `OpenAILlmClient`, `OpenAIResponsesRequestFactory`,
> `OpenAiReasoningEfforts`, `OpenAIConfig` · `aimon-llm-anthropic` — `AnthropicLlmClient`, `AnthropicConfig`,
> `AnthropicThinkingResolver`(effort 우선순위).

---

## 1. 문제와 범위

provider 클라이언트는 요청마다 세 입력으로 와이어 요청을 만든다 — 요청 단위 `LlmModel`, 클라이언트 단위 설정
(`OpenAIConfig` · `AnthropicConfig`), 모델 단위 `ModelCapabilities`. 모델마다 받는 파라미터가 다르고, 받지 않는 값을
실은 요청은 HTTP 400 으로 그 LLM 호출을 실패시킨다. 반대로 값을 조용히 바꿔 보낸 요청은 **성공한다** — 상태 코드도
오류도 없이 설정과 다른 결과가 나온다. 이 문서는 두 provider 가 함께 지키는 규칙을 정한다.

- 우선순위와 "값을 지어내지 않는다", 생략의 뜻 (§2)
- 샘플링 파라미터 — 무엇을 싣고 무엇을 빼는가 (§3)
- reasoning effort — 중립 어휘, ladder 를 읽는 법, 엔드포인트 도구 규칙 (§4)
- divergence 보고 — 무엇을, 어떤 문구로, 몇 번 말하는가 (§5)

**다루지 않는 것.**

| 개념 | 정본 |
|---|---|
| `ModelCapabilities` 필드의 존재·기본값, `unknown()`, fail-open 정의, 내장 표의 행과 그 측정 근거 | [`model-capabilities.md`](model-capabilities.md) |
| 요청이 싣는 모델 이름이 어디서 정해지는가 | [`model-name-resolution.md`](model-name-resolution.md) |
| Anthropic `thinking` 파라미터 해석(모드 × 방언, budget, effort → budget·adaptive effort) | [`anthropic-thinking.md`](anthropic-thinking.md) |
| Chat Completions / Responses 라우팅과 Responses 요청 매핑 | [`openai-responses-path.md`](openai-responses-path.md) |
| 설정 키(`reasoningEffort`, 능력 선언)와 그 네임스페이스 | [`configuration-surface.md`](configuration-surface.md) |
| `TokenUsage.reasoningTokens` 회계, reasoning trace 드롭 보고 | [`reasoning-traces.md`](reasoning-traces.md) |

벤더 전용 rung(`xhigh` · `max`), `top_k` 필드, "기본값만 받는다" 를 표현하는 능력 모양은 비목표다. 이유는 각 절에 있다.

---

## 2. 공통 규칙 — 누가 먼저이고, 무엇을 싣지 않는가

### 2.1 우선순위 — `LlmModel` 먼저, 그다음 클라이언트 설정, 그 뒤는 없다

값은 **요청의 `LlmModel` 이 먼저**, 없으면 **클라이언트 설정**에서 온다. 세 번째 단계는 없다 — 클라이언트는 자기
상수로 빈자리를 채우지 않는다(§2.2).

| 파라미터 | OpenAI | Anthropic |
|---|---|---|
| `temperature` | `LlmModel` → `OpenAIConfig` | `LlmModel` → `AnthropicConfig` |
| `topP` · `presencePenalty` · `frequencyPenalty` | `LlmModel` → `OpenAIConfig` | `LlmModel` 만 — `AnthropicConfig` 에는 필드가 없다 |
| `reasoningEffort` | `LlmModel` → `OpenAIConfig` | `LlmModel` → `AnthropicConfig` |

`AnthropicConfig` 도 `reasoningEffort` 를 갖고 우선순위가 같은 이유: `reasoningEffort` 는 두 provider 가 공유하는
설정 키이고, 공유 키가 한 provider 에만 닿으면 그 키는 절반의 배포에게 거짓이 된다.

에이전트 정의의 값과 배포 설정의 값이 둘 다 있으면 `LlmModel` 이 **조용히** 이긴다. 문서화된 우선순위대로
동작한 요청에 경고하면 소음이다.

우선순위 헬퍼는 provider 마다 하나다 — `OpenAiRequestParameters.requestedEffort` 와
`AnthropicThinkingResolver.requestedEffort`. Anthropic 쪽은 effort 를 두 자리에서 읽는다(thinking 파라미터를 실을지
정하는 게이트, 그리고 방언 번역 보고가 이름을 대는 rung). 우선순위를 두 자리에 따로 쓰면 하나만 바뀌었을 때 게이트와
보고가 같은 요청에 대해 다른 effort 를 말하므로, 헬퍼 하나를 두 자리가 함께 부른다. 두 provider 의 헬퍼를 core 로
합치지는 않는다(§6).

샘플링·effort 적용 함수는 **해석된 모델 이름**을 인자로 받는다. `LlmModel` 이 모델 이름을 덮는 요청이 있으므로,
설정에서 이름을 다시 읽으면 보고 문구와 signature(§5)가 다른 모델을 가리킨다.

### 2.2 값을 지어내지 않는다

파라미터는 **누군가 값을 넣었을 때만** 요청에 실린다. 그래서 두 설정 클래스의 샘플링 필드는 미설정 상태를 가진다 —
`getTemperature()` 는 `Optional<Double>` 이고, 범위 검사는 값이 있을 때만 한다(`OpenAIConfig` 는 `temperature`
0.0–2.0, `topP` 0.0–1.0, 두 penalty −2.0–2.0 으로 `LlmModel` 과 같은 경계, `AnthropicConfig` 는 `temperature`
0.0–1.0). 기본 temperature 상수는 없다.

이유는 셋이다.

1. **provider 개발 규칙이다.** 미설정을 자기 상수로 채우면 아무도 요청하지 않은 값이 매 요청에 실리고 서버 기본값이
   영영 적용되지 않는다([`llm-provider-development-guide.md`](../../features/llm/llm-provider-development-guide.md)).
2. **fail-open 의 양면을 지키려면 필요하다.** fail-open 은 "호출자가 요청한 것은 보류하지 않고, 요청하지 않은 것은
   지어내지 않는다" 이다([`model-capabilities.md`](model-capabilities.md)). 값을 지어내는 설정 위에 capability 게이트를
   얹으면 클라이언트는 그 계약의 절반만 지킨다.
3. **지어낼 법한 값 `0.0` 은 측정상 가장 나쁜 값이다.** 2026-09-09 측정에서 도달 가능한 Claude 11 모델 중 6 이
   `temperature: 0.0` 을 거절했고, 아무것도 보내지 않은 같은 요청은 11 모두 받았으며 그 결과는 11 모두가 받는 값이다.

**대가.** temperature 를 설정하지 않은 요청은 서버 기본값(1.0)으로 샘플링된다. 다른 샘플링을 원하는 배포는 값을
설정한다. 설정 표면에는 아직 샘플링 키가 없어 그 값은 에이전트 정의나 프로그램 조립에서만 줄 수 있다(§8, L-2).

### 2.3 생략은 setter 를 부르지 않는 것이다

"보내지 않는다" 는 **setter 를 부르지 않는 것**으로만 구현된다. `null` 이나 빈 `Optional` 을 넘기는 것은 생략이 아니다.

- OpenAI SDK 의 `temperature(Double)` 과 `Optional` 오버로드는 `JsonField.ofNullable` 을 지나 `JsonNull` 이 되고,
  `"temperature": null` 로 직렬화된다 — 파라미터가 **존재한다**
- Anthropic 서버는 `"temperature": null` 을 스키마 오류(`Input should be a valid number`)로 400 거절한다. 샘플링을
  받는 모델에서도 그렇다(2026-09-09) — capability 규칙이 아니라 타입 규칙이라 모든 모델에 걸린다

그래서 capability·thinking 판정은 setter 호출 **앞에서** 분기하고, nullable 한 "유효값" 을 계산해 setter 에 넘기지
않는다. OpenAI 쪽은 이 판정을 `OpenAiRequestParameters.applySampling` 한 곳에 두고, 값이 받아들여졌을 때만
`SamplingSink` 로 넘긴다.

SDK 의 읽기 접근자(`getOptional`)는 필드가 없는 것(`JsonMissing`)과 `null` 로 설정된 것(`JsonNull`)을 구분하지
못한다. 생략했는지는 raw 필드나 직렬화된 본문으로만 확인된다.

---

## 3. 샘플링 파라미터 — 받는 모델에만, 받는 모양으로

### 3.1 무엇이 샘플링 파라미터인가

샘플링 파라미터는 **`LlmModel` 과 provider 설정이 실을 수 있는 넷** — `temperature`, `top_p`, `presence_penalty`,
`frequency_penalty` — 이다. 네 파라미터를 받는지는 `ModelCapabilities.supportsSamplingParameters()` 한 비트가
답한다.

`top_k` 는 실을 필드가 없어 해당 없음이다. 어떤 코드 경로도 `top_k` 를 보낼 수 없으니 억제할 것도 보고할 것도 없다.
다만 측정에서 `temperature` 와 `top_p` 를 거절한 여섯 Claude 모델은 `top_k` 도 거절했고 받는 다섯은 받았으므로,
`LlmModel` 에 필드를 더하면 같은 플래그가 그대로 덮는다.

### 3.2 capability 에 의한 생략(억제) — 와이어에서 잃는 것이 없다

`supportsSamplingParameters()` 가 `false` 이면 네 setter 를 하나도 부르지 않고, **누군가 값을 넣은 것마다** 보고한다.
아무도 값을 넣지 않은 요청은 특별한 분기 없이 구조적으로 조용하다 — 억제될 수 있는 값은 전부 누군가 넣은 값이기
때문이다.

억제가 와이어에서 잃는 것이 없는 이유는 측정에 있다(2026-09-09, 행과 모델별 결론은
[`model-capabilities.md`](model-capabilities.md)). 샘플링을 거절하는 모델은 `temperature` 를 **값으로** 거절한다 —
기본값 `1.0` 은 받고 다른 값은 400 이다. 생략하면 서버 기본값이 적용되고, 그것이 모델이 받는 유일한 값이다. `top_p` 는
Anthropic 거절 모델에서 **존재로** 거절된다(`1.0` 도 400). 거절 문구 `` `temperature` is deprecated for this model. `` 는
파라미터 전체를 금지하는 것처럼 읽히지만 `1.0` 은 받아들여진다 — 에러 메시지는 명세가 아니며, 어떤 값이 받아들여지는지는
보내 봐야 안다.

**한 비트의 과대 근사를 수용한다.** 명시적으로 `temperature: 1.0` 을 준 요청도 억제되고 보고된다 — API 는 받았을
요청이다. "기본값만 받는다" 를 정확히 표현하려면 새 능력 모양이 필요하고, 파라미터별 플래그로 쪼개도 와이어 차이는 없다
(얻는 것은 생략과 구분되지 않는 값 `1.0` 을 보낼 권리뿐이다).

**억제 보고는 플래그가 `false` 일 때만 난다.** 설정 선언이 억제 행을 대체하면서 플래그를 적지 않으면 그 플래그는
fail-open 값(`true`)으로 돌아가고, 값은 경고 없이 실린다. 선언이 행 전체를 대체한다는 규칙은
[`configuration-surface.md`](configuration-surface.md) 에 있다.

### 3.3 OpenAI — 두 엔드포인트, 한 규칙

`OpenAiRequestParameters.applySampling` 을 Chat Completions 와 Responses 가 함께 부른다. 값을 거절하는 모델은 어느
엔드포인트에서나 거절하고 그 값을 둔 운영자는 같은 보고를 받아야 하므로, 규칙을 두 요청 빌더에 복사하면 조용히 어긋날
두 번째 자리만 생긴다.

Responses 엔드포인트에는 `presence_penalty` · `frequency_penalty` 자리가 **없다**. 샘플링을 받는 모델에 penalty 가
설정되어 있으면 `SamplingSink` 가 그것을 삼키지 않고 보고한다 — 설정과 다른 요청이 조용히 성공하는 것이 divergence
보고가 없애려는 실패다. 엔드포인트별 필드 매핑은 [`openai-responses-path.md`](openai-responses-path.md).

### 3.4 Anthropic — capability 게이트가 thinking 규칙보다 먼저

Anthropic 은 두 게이트를 이 순서로 겹친다.

```text
applySamplingParameters
  ├─ !supportsSamplingParameters()          ← 바깥: 모델 사실
  │     temperature · topP 중 값이 있는 것마다 억제 보고, setter 없음, 끝
  └─ 받는 모델                               ← 안쪽: thinking 규칙
        thinking 파라미터가 실린 요청: temperature 는 setter 없음 + 보고,
                                       topP 는 [0.95, 1.0] 안일 때만, 밖이면 생략 + 보고
        thinking 이 없는 요청:          temperature 가 [0.0, 1.0] 밖이면 범위로 clamp + 보고
```

- **순서의 이유.** 안쪽 규칙은 샘플링을 받되 thinking 과는 함께 받지 않는 모델의 것이다. 순서를 뒤집으면 adaptive
  thinking 이 켜진 요청이 창 안의 `top_p: 0.98` 을 거절 모델로 보낸다 — 측정된 400 이다. thinking 이 켜진 요청은
  이미 `temperature` 를 싣지 않으므로 거절 모델에 남는 노출은 `top_p` 뿐이고, 창 안의 값도 거절되기 때문에 `top_p`
  도 같은 플래그로 억제한다.
- **거절 모델에서는 억제 문구가 나온다.** thinking 문구도 참이지만, thinking 을 꺼도 모델이 값을 받지 않으므로 억제
  문구가 쓸모 있는 쪽이다. thinking 문구는 받는 모델에서만 나온다.
- **thinking 에 의한 temperature 생략은 두 문구다.** 호출의 `LlmModel` 이 둔 값과 클라이언트 설정이 둔 값을 가른다.
  앞의 것은 이 호출에서 고른 값이고, 뒤의 것은 이 provider 에 묶인 모든 에이전트에 한 번 설정된 값이라, 운영자가
  찾아가야 할 자리가 다르다. 어디에도 값이 없으면 아무것도 말하지 않는다.
- **"안전한" 값으로 대체하지 않는다.** 생략하고 보고할 뿐이다. 대체한 요청은 운영자가 고르지 않은 샘플링으로
  성공한다.
- **두 penalty 는 capability 분기 밖에서 "Anthropic 대응물 없음" 으로 보고한다.** Anthropic 에는 대응 파라미터가
  아예 없다. capability 분기로 보내면 참이고 구체적인 문구가 모호한 문구로 바뀌고, 샘플링을 받는 모델에서는 여전히
  드롭되는데 침묵하게 된다.

thinking 파라미터가 실리는지는 [`anthropic-thinking.md`](anthropic-thinking.md) 가 정한다. 이 절은 그 결과
(`thinkingRequested`)를 입력으로 받는다.

### 3.5 서브에이전트 요청은 temperature 를 명시값으로 싣는다

`SubagentLlmDefaults.resolveModel` 은 서브에이전트 모델의 temperature 를 메인 에이전트의 `LlmModel` 에서 물려받고,
거기에 없으면 `0.7` 을 **명시값으로** `LlmModel` 에 넣는다. ReAct 경로와 코드 behavior 경로가 같은 해석을 쓴다.

그래서 §2.2 의 "미설정은 보내지 않는다" 만으로는 서브에이전트 요청을 고치지 못한다. 두 장치는 서로를 대신하지 않는다.

| | 메인 에이전트 요청(값 없음) | 서브에이전트 실행의 요청 | 명시 `temperature`/`topP` 요청 | 내장 표가 모르는 거절 모델 |
|---|---|---|---|---|
| capability 억제만 | 고친다 | 고친다 | 고친다 | 못 고친다 |
| 미설정 비전송만 | 고친다 | **못 고친다** | **못 고친다** | 아무도 값을 넣지 않았을 때만 |
| 둘 다 | 고친다 | 고친다 | 고친다 | 아무도 값을 넣지 않았을 때만 |

이 경로 때문에 보고 문구는 "configured" 라고 말하지 않는다(§5.2). 서브에이전트 기본값 자체는 §8.

---

## 4. reasoning effort — 중립 어휘와 모델의 ladder

### 4.1 중립 어휘 — 두 provider 번역에서 살아남는 다섯 rung

`at.aimon.core.llm.ReasoningEffort` 는 `NONE`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH` 다섯 값이다. 벤더 SDK 는 `XHIGH` ·
`MAX` 까지 일곱을 갖지만, 중립 enum 은 한 벤더의 ladder 를 재수출하지 않고 **두 번째 provider 로 번역해도 살아남는
공통 부분집합**을 취한다. Anthropic 은 같은 축을 thinking 토큰 예산으로 표현하므로 다섯도 이미 공유 타입이 아니라
매핑이다. 벤더 전용 rung 이 필요한 호출자는 provider 전용 탈출구를 요구하는 것이고, 그것은 다른 설계다.

- **상수는 effort 오름차순으로 선언한다.** 그 순서가 load-bearing 이다 — ladder 를 가장 낮은 rung 부터의
  `EnumSet.range` 로 쓸 수 있고, `EnumSet` 의 반복 순서가 곧 ladder 순서라서 rung 을 출력하는 보고 문구가 ladder 로
  읽힌다. 나중에 더하는 상수는 끝이 아니라 ladder 의 제자리에 들어간다.
- **필드.** `LlmModel.reasoningEffort`, `OpenAIConfig.reasoningEffort`, `AnthropicConfig.reasoningEffort` — 모두 선택,
  기본 미설정, 검증 없음. 모든 enum 값이 합법이고, 모델이 그 값으로 무엇을 하는지는 클라이언트가 보고할 일이지 설정이
  거절할 일이 아니다.
- **번역.** OpenAI 쪽은 `OpenAiReasoningEfforts.toWire` 가 중립 값을 와이어 값으로 옮기는 유일한 자리다. Anthropic 쪽
  번역은 [`anthropic-thinking.md`](anthropic-thinking.md).

### 4.2 ladder 는 집합이다 — `acceptedReasoningEfforts` 를 읽는 법

모델이 받는 rung 은 모델 사실이고, `ModelCapabilities.acceptedReasoningEfforts()` 가 그 집합이다(필드의 모양과 기본값은
[`model-capabilities.md`](model-capabilities.md)). 요청 조립은 **"이 rung 이 이 모델의 ladder 에 있는가"** 한 질문을
집합 포함(`contains`)으로 묻는다.

**특수 경우 둘이 아니라 능력 하나인 이유.** 측정된 OpenAI 모델 대부분에는 `none` 이 없고 o-series 에는 `minimal` 도
없다. 둘은 같은 질문이고 답이 모델 계열마다 다르다 — 그것이 capability 가 존재하는 이유다. `NONE` 모양 특수 경우와
`MINIMAL` 모양 특수 경우를 두 요청 빌더에 따로 두면 둘을 묶는 것이 없다.

**바닥이 아니라 집합인 이유.** 바닥 하나는 경계 하나와 "그 위로 구멍이 없다" 를 함께 주장한다. `gpt-5.6-terra` 는
`none` 을 받고 `minimal` 을 거절한다(2026-09-09, `/v1/responses`). 가운데가 빠진 ladder 에는 맞는 바닥 값이 없다.

| 바닥 | terra 에서 일어나는 일 | 판정 |
|---|---|---|
| `NONE` | `MINIMAL` 이 통과해 `"effort":"minimal"` → 400 | 호출 실패 |
| `MINIMAL` | 같은 400, 게다가 terra 가 받는 `none` 까지 막는다 | 호출 실패 + 틀린 기술 |
| `LOW` | 400 은 피하지만 `none` 을 막고, terra 에 `minimal` 이 없는 이유를 "`low` 부터라서" 로 적는다 | 결과는 맞고 사실은 거짓 |

세 번째가 유혹적이고, 그래서 값이 아니라 모양을 바꾼다 — 맞는 동작을 거짓 기술로 사면 다음에 행을 읽는 사람이 모델에
대해 틀린 사실을 배운다.

**바닥은 입력으로만 남는다.** `ModelCapabilities.Builder.lowestReasoningEffort(X)` 는 `EnumSet.range(X, HIGH)` 의
축약이고, 구멍 없는 ladder 를 **쓰는** 좋은 방법이다. 바닥을 **읽는** getter 는 없다 — `effort.compareTo(floor) >= 0`
은 정확히 terra 에 `minimal` 을 실은 식이고, `min(set)` 을 돌려주는 getter 는 그 식을 새 이름으로 계속 부를 수 있게
둔다. 편의 메서드 `accepts(ReasoningEffort)` 도 두지 않는다 — 판정 자리가 하나라 같은 질문의 두 번째 표현이 값을 하지
못한다.

**기본 ladder 가 `NONE` 을 빼는 비대칭.** 모델이 가진 rung 을 보류하는 비용은 보고된 생략과 서버 기본값이고, 모델에 없는
rung 을 보내는 비용은 호출을 실패시키는 400 이다. 측정된 OpenAI 모델 중 `none` 을 가진 것은 terra 뿐이다. `NONE` 부터
시작하는 모델은 자기 행으로 기술할 수 있다.

**판정은 한 곳이고 두 OpenAI 엔드포인트가 함께 부른다.** `OpenAiRequestParameters.maySendEffort` 다. 어떤 rung 을
받는지는 요청 표면이 아니라 모델의 사실이기 때문이다.

**Anthropic 은 ladder 를 읽지 않는다.** Anthropic 요청 표면에는 rung 파라미터가 없고, 중립 rung 은 thinking 예산이나
adaptive effort 로 번역된다. Anthropic 이 서빙하는 모델의 행이 ladder 를 적어도 효과가 없고, 경고도 하지 않는다 —
그 필드는 이 provider 에 없는 파라미터를 기술하며, 읽히지 않는 능력 플래그마다 호출당 경고하면
`supportsToolsWithReasoning` 에 대해서도 울려야 한다.

### 4.3 ladder 밖 rung — 생략하고 보고하며, 절대 올리지 않는다

OpenAI 클라이언트는 설정된 effort 를 이 순서로 거른다. 아무도 effort 를 설정하지 않았으면 아무것도 싣지 않고 서버
기본값에 맡긴다.

| 순서 | 조건 | 처분 | 어느 엔드포인트 |
|---|---|---|---|
| 1 | `!supportsReasoningEffort()` | 생략 + 보고 | 둘 다 |
| 2 | 도구가 있고 `!supportsToolsWithReasoning()` | 생략 + 보고 (§4.4) | Chat Completions 만 |
| 3 | rung 이 `acceptedReasoningEfforts()` 밖 | 생략 + 보고 | 둘 다 (`maySendEffort`) |
| — | 그 외 | 설정된 rung 을 그대로 싣는다 | 둘 다 |

ladder 밖 rung 은 **생략하고 보고한다.** `NONE` 을 가장 가까운 `minimal` 로 올리지 않는다 — 올리면 아무도 만들지 않은
요청이 와이어에 조용히 실린다. 생략은 서버 기본값을 남기고, 보고는 그 상태를 참으로 기술할 수 있다.

보고 문구는 **그 모델이 받는 rung 들을 출력한다.** 그래서 terra 에 `minimal` 을 준 경우와 `gpt-5-mini` 에 `none` 을 준
경우가 같은 문장이 아니라 각자의 ladder 로 구분된다. 이유를 "아래(below)" 로 말하지 않는다 — 구멍 있는 ladder 에서 빠진
rung 은 어느 rung 보다도 아래가 아니다.

### 4.4 Chat Completions 도구 규칙 — 모델이 아니라 엔드포인트의 성질

Chat Completions 에서 도구가 있는 요청이고 모델 행이 `supportsToolsWithReasoning()` 을 `false` 로 적었으면, effort 를
그 호출에서 **생략하고 보고한다.** `NONE` 을 명시해 보내지 않는다 — 측정된 모델은 Chat 에서 `reasoning_effort: none`
자체를 400 으로 거절하고(`gpt-5-nano`), 도구가 있고 effort 가 없는 요청은 200 을 받았다(`gpt-5-nano` · `o4-mini`,
2026-09-09).

- **이 규칙은 Chat 클라이언트에 남고 공유 판정으로 옮기지 않는다.** "도구와 reasoning 이 한 요청에 함께 있어도 되는가"
  는 요청 표면의 성질이고, 둘이 공존하는 Responses 경로는 이 규칙을 적용하지 않는다. 반대로 ladder 검사는 두 경로가
  공유한다. 둘을 섞으면 두 엔드포인트 모두에서 400 인 값이 한쪽에서만 막힌다.
- **내장 표의 reasoning 행은 모두 `true` 다**(gpt-5 계열, o-series — [`model-capabilities.md`](model-capabilities.md)).
  이 규칙은 호출자나 설정 선언이 `false` 를 적은 행에서만 발동한다.
- **terra 가 Chat 에서 도구 요청을 거절하는 것은 이 규칙으로 피할 수 없다.** effort 를 싣지 않은 도구 요청도 Chat 에서는
  400 이므로 처방은 파라미터 조정이 아니라 라우팅이다 — 측정과 오류 본문은
  [`openai-responses-path.md`](openai-responses-path.md) 의 측정 결론에 있다. 그 경로에서 terra 에 `none` 을 보내는 칸은
  측정되지 않았다(§8, L-2).

### 4.5 `gpt-5.6-terra` 행과 계열 prefix override 약속 — 약속을 좁힌다

terra 는 측정된 ladder `{NONE, LOW, MEDIUM, HIGH}` 를 가진 **exact 행**이고, 나머지 네 플래그는 gpt-5 계열 행과 한
출처에서 받는다(행 값과 측정은 [`model-capabilities.md`](model-capabilities.md)). exact 행은 모든 prefix 를 이긴다.
그래서:

- `InMemoryModelCapabilityRegistry.builderWithDefaults().registerPrefix("gpt-5", …)` 는 `gpt-5-mini` · `gpt-5-nano` ·
  이후의 `gpt-5*` 이름에 닿지만 **`gpt-5.6-terra` 에는 닿지 않는다**
- 그 이름을 덮으려면 `register("gpt-5.6-terra", …)` 를 쓰거나 그 이름의 설정 선언을 둔다 — 둘 다 내장 exact 행을 대체한다
- `o1` · `o1-2024-12-17` 의 exact 행이 `registerPrefix("o1", …)` 를 가리는 것과 같은 규칙이고 처방도 같다

**약속을 좁히는 이유.** "계열 override 는 모든 `gpt-5*` 이름에 언제나 닿는다" 를 지키려면 측정된 이름에 exact 행을 둘 수
없고, 알려진 400 을 그대로 두어야 한다. 현재 레지스트리 모양에서는 exact 행도, `gpt-5` 앞의 terra prefix 도, 뒤의 terra
prefix 도 그 약속을 깬다 — 레지스트리 모양을 바꿔서 둘 다 얻는 길은 없다. 물을 것은 모양이 아니라 약속의 어느 절반이
load-bearing 인가였고, 레지스트리는 같은 클래스 안에서 o-series 에 대해 이미 약한 쪽 약속을 하고 있었다.

terra 행에 `xhigh` · `max` 가 없는 것은 누락이 아니다 — 중립 어휘가 `HIGH` 에서 멈추기 때문이다(§4.1).

---

## 5. divergence 보고 — 설정과 다르게 나간 요청을 운영자에게 말한다

### 5.1 무엇을 보고하는가

divergence 는 **설정된 값과 다른 설정으로 요청이 성공하는 조건**이다. 오류도 상태 코드도 없고 시스템의 다른 무엇도
운영자에게 둘이 다르다고 알려 주지 않으므로 WARN 으로 말한다. DEBUG 에서는 divergence 가 있어도 아무도 보지 못하고,
그것은 일어나지 않은 것과 구분되지 않는다. `LlmModel` 의 범위 검사가 이 규칙을 적는다 — 그 범위는 어느 provider
계약도 아닌 sanity 경계이고, 합법이지만 provider 에 따라 다르게 다뤄지는 값은 **provider 가 운영자에게 보이는 수준으로
보고한다.**

이 문서의 규칙이 만드는 보고와 그 signature:

| 조건 | 클라이언트 | signature |
|---|---|---|
| capability 억제(§3.2) | OpenAI 두 엔드포인트 | `temperature=<v>@<model>` · `topP=…` · `presencePenalty=…` · `frequencyPenalty=…` |
| capability 억제(§3.2) | Anthropic | `temperature=<v>@<model>` · `topP=<v>@<model>` |
| Responses 에 penalty 자리 없음(§3.3) | OpenAI | `presencePenalty=<v>@<model>#responses` · `frequencyPenalty=…#responses` |
| thinking 에 의한 temperature 생략(§3.4) | Anthropic | `temperatureOmittedForThinking=<v>` · `clientTemperatureOmittedForThinking=<v>` |
| thinking 창 밖 `top_p`(§3.4) | Anthropic | `topPOmittedForThinking=<v>` |
| 범위 밖 temperature clamp(§3.4) | Anthropic | `temperature=<v>` |
| penalty 대응물 없음(§3.4) | Anthropic | `presencePenalty=<v>` · `frequencyPenalty=<v>` |
| effort 파라미터가 없는 모델(§4.3) | OpenAI 두 엔드포인트 | `reasoningEffort=<e>@<model>` |
| Chat 도구 규칙(§4.4) | OpenAI Chat | `reasoningEffortOmitted=<e>@<model>` |
| ladder 밖 rung(§4.3) | OpenAI 두 엔드포인트 | `reasoningEffortOffLadder=<e>@<model>` |

레지스트리 조회가 던지거나 `null` 을 돌려줄 때의 보고는 [`model-capabilities.md`](model-capabilities.md), thinking
파라미터 해석이 기록하는 finding 과 그 signature 는 [`anthropic-thinking.md`](anthropic-thinking.md), reasoning trace
드롭 보고는 [`reasoning-traces.md`](reasoning-traces.md) 가 정본이다. 모두 아래 §5.3 의 장치를 쓴다.

보고하지 않는 것: 아무도 값을 넣지 않은 요청, 문서화된 우선순위대로 `LlmModel` 이 설정을 이긴 요청(§2.1).

**모델에 따라 갈리는 보고는 signature 에 모델 이름을 넣는다.** 보고 집합은 클라이언트당 하나이고 클라이언트의 설정도
하나지만, `LlmModel` 이 요청마다 모델 이름을 덮는다. 같은 값이 두 모델에서 억제되면 반복이 아니라 두 개의 소식이고,
두 모델을 쓰는 배포는 둘 다 들어야 한다.

### 5.2 문구 — "configured" 가 아니라 "is set on this request"

문구는 값이 **이 요청에 실려 있다**고만 말한다 — `"temperature 0.7 is set on this request but <model> does not accept
sampling parameters; it is being omitted and the call will succeed without it."` 에서 `<model>` 은 해석된 모델 이름이다.

"configured" 라고 쓰지 않는 이유: 서브에이전트 경로에서는 아무도 설정하지 않았다 — `SubagentLlmDefaults` 가 자기 상수를
`LlmModel` 에 넣고(§3.5), 클라이언트가 보고를 결정하는 지점에서는 그것과 운영자의 값을 구분할 방법이 없다. "이 요청에
실려 있다" 는 두 출처 모두에 참이고, 운영자가 무엇을 찾아야 하는지도 그대로 말한다. 문구는 무엇이 빠지는지와 호출이 그것
없이 성공한다는 것을 함께 말한다.

### 5.3 두 보고 장치 — once 집합과 반복 보고 카운터

| 장치 | 무엇을 | 언제 로그를 남기나 | 왜 |
|---|---|---|---|
| **보고 집합(once)** — `reportDivergence` | **설정의 사실**: 위 표의 보고 전부 | 클라이언트 인스턴스당 signature 마다 한 번 | 요청 조립은 매 iteration 마다 돈다. 에이전트 정의에 한 번 적힌 값이 프로세스 수명 내내 경고하면 소음이다 |
| **반복 보고 카운터(recurring)** — `reportRecurringDivergence` | **트래픽의 사실**: 서명을 잃은 스트림, 이 빌드가 해석하지 못하는 저장된 payload, 도구 호출이 사라진 trace, 다른 provider 의 trace | signature 마다 1 · 10 · 100 … 번째, 두 번째 줄부터 발생 횟수를 붙인다 | 트래픽 조건은 프로세스 중간에 시작될 수 있고 signature 가 일정하다. once 로 보고하면 첫 발생만 말하고 조건이 계속되는 동안 침묵한다. 한 줄은 한 번 일어났다는 뜻이고 `occurrence 100` 은 기능이 꺼져 있다는 뜻이다 |

- **나누는 기준은 플래그가 아니라 호출 지점이다.** Anthropic 클라이언트는 메시지 변환기와 스트리밍 매퍼에 반복 보고
  함수를 넘기고, 자기 샘플링·예산 보고는 once 집합으로 한다. 보고 인터페이스(`AnthropicDivergenceReporter`)의 모양은
  같고, 협력자는 자기가 어느 장치에 쓰는지 알 필요가 없다. 반복 보고 카운터는 Anthropic 클라이언트에만 있다 — OpenAI
  클라이언트는 once 집합 하나로 모든 보고를 한다.
- **상한은 32 signature 다.** 두 장치 모두 클라이언트당 `MAX_REPORTED_DIVERGENCES`(32)개의 signature 를 넘으면 새
  signature 를 기록하지 않는다. signature 에 모델 이름이 들어가므로 모델 이름을 많이 바꾸는 배포는 상한에 더 빨리
  닿는다 — 두 클라이언트가 같은 노출을 갖고, 장치를 다시 설계할 만큼 넓지 않다. 상한 검사는 잠금 없이 해서 동시에 처음
  발생한 divergence 가 스레드 수만큼 상한을 넘을 수 있다 — 호출마다 도는 경로에서 정확성을 위해 잠그지 않는다.
- **알아채는 쪽과 보고하는 쪽이 다르다.** 요청 파라미터 규칙(`OpenAiRequestParameters`, Responses 요청 팩토리, 변환기)은
  divergence 를 알아채고 `OpenAIDivergenceReporter` · `AnthropicDivergenceReporter` 로 넘긴다. once 의미는 클라이언트의
  집합에 있고 WARN 은 클라이언트 자신의 로거에서 나야 하므로, 스스로 보고하는 협력자는 둘 다 갖지 못한다.
- **기록 · 방출 · 로그는 다르다.** Anthropic thinking 해석은 조립 중에 finding 을 기록하고 완성된 요청이 방출할 finding 을
  고른다([`anthropic-thinking.md`](anthropic-thinking.md)). 방출된 finding 이 로그로 남는지는 이 절의 장치가 정한다 —
  같은 signature 는 클라이언트당 한 번이고, 집합이 상한에 닿았으면 남지 않는다. 그래서 dedup 은 기록할 때가 아니라
  방출할 때 한다.

---

## 6. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `OpenAIConfig` 에 `omitSamplingParameters` boolean 노브 | capability 기술자와 같은 질문에 답하는 두 번째 레버라서 둘이 어긋날 수 있다. 레버는 기술자 하나다 |
| `double getTemperature()` 를 유지하고 `Optional<Double> getConfiguredTemperature()` 를 옆에 더한다 | 공개 타입에 거의 같은 질문에 답하는 getter 가 둘 생기고, 이름이 뻔한 쪽이 거짓말을 한다 — temperature 를 보내지 않을 모델에도 `0.0` 을 돌려준다. 보고 여부는 명시/미설정 구분에 달려 있는데, 주 접근자가 미설정을 표현하지 못하면 다음 호출자는 그 구분을 틀린 getter 에서 읽는다 |
| `ModelCapabilities.defaultReasoningEffort()` — reasoning 모델에 늘 명시 effort 를 보낸다 | "서버 기본값은 medium" 을 모델별 정책 기본값으로 능력 기술자에 섞는다. effort 를 설정하지 않은 요청은 생략으로 성공하는데(도구가 있어도 — §4.4) 그 경우를 위해 기술자의 뜻을 흐린다 (열린 질문은 §8) |
| 모든 Anthropic 모델에 샘플링을 무조건 억제 | 측정된 11 모델 중 5 가 셋을 모두 받는다. 그 배포들에서 동작하던 설정을 조용히 드롭한다 |
| `AnthropicConfig.temperature` 를 `0.0` 기본값으로 두고 레지스트리만 쓴다 | 억제는 동작하지만 provider 개발 규칙을 어기고, `0.0` 은 지어낼 수 있는 값 중 측정상 가장 나쁘다(§2.2). 내장 표가 모르는 거절 모델은 아무도 값을 넣지 않아도 400 이다 |
| `supportsSamplingParameters` 를 파라미터별 플래그로 나눈다 | 같은 여섯 모델이 둘 다 거절하므로 와이어 차이가 없다. 얻는 것은 생략과 구분되지 않는 `temperature: 1.0` 을 보낼 권리뿐이다. 과대 근사를 기록한다(§3.2) |
| Anthropic 의 두 penalty 를 capability 분기로 보낸다 | "Anthropic 대응물 없음" 이라는 참이고 구체적인 문구가 모호한 문구로 바뀌고, 샘플링을 받는 모델에서는 여전히 드롭되는데 침묵한다 |
| ladder 밖 rung 을 가장 가까운 받는 rung 으로 올린다 | 아무도 만들지 않은 요청을 조용히 보낸다. 생략은 서버 기본값을 남기고 보고가 그것을 참으로 기술한다 |
| 바닥을 유지하고 구멍 필드(`lowestReasoningEffort` + 제외 rung)를 더한다 | 한 사실을 두 필드가 기술해 서로 어긋날 수 있고, 모든 독자가 둘을 맞게 조합해야 답을 얻는다. 집합이 곧 그 조합이다 |
| `lowestReasoningEffort()` 를 `min(set)` 으로 남긴다 | 소스 호환이지만 조용히 틀리다 — 그 getter 가 부르는 식(`effort.compareTo(floor) >= 0`)이 terra 에 `minimal` 을 실은 식이다. 컴파일 오류가 더 나은 결과다 |
| terra 를 고치려고 `gpt-5` prefix 행의 ladder 를 계열 교집합 `{LOW, MEDIUM, HIGH}` 로 좁힌다 | 이름 단위 행이 필요 없고 override 약속도 그대로지만, `minimal` 을 측정상 받는 `gpt-5` · `gpt-5-mini` · `gpt-5-nano` 에서 막는다. 어떤 모델도 갖지 않은 ladder 를 적는 것이고, 행은 자기가 매치하는 이름에 대한 측정된 사실을 적는다는 원칙을 뒤집는다 |
| `registerPrefix("gpt-5.6-terra", …)` 를 `gpt-5` 앞에 둔다 | 같은 override 약속을 깨고, 아무도 본 적 없는 날짜 스냅샷에까지 ladder 를 주장한다 |
| "`LlmModel` 먼저, 그다음 설정" 우선순위를 core 공유 헬퍼로 | 서로 무관한 두 설정 타입에 걸친 두 줄이라 `Supplier` 를 받는 추상이 필요하고 호출자는 둘뿐이다. 두 provider 에 같은 우선순위를 단언하는 테스트가 드리프트를 더 싸게 막는다 |
| `reportDivergence` 를 core 공유 헬퍼로 추출 | 보고는 각 클라이언트의 로거에서 나야 하고 once 집합과 상한도 클라이언트별이다. 공유 헬퍼는 그 둘을 클라이언트에서 떼어 낸다 |

모델 이름 스니핑·fail-closed·레지스트리 모양에 관한 대안은 [`model-capabilities.md`](model-capabilities.md), 설정 키
모양에 관한 대안은 [`configuration-surface.md`](configuration-surface.md) 에 있다.

---

## 7. 하지 말 것

- **생략을 `null` 이나 빈 `Optional` 로 구현하지 않는다.** 판정은 setter 앞에서 분기한다. 생략을 `getOptional` 계열
  접근자로 확인하지 않는다 — `JsonNull` 에도 비어 있다고 답한다
- **미설정 샘플링 값을 클라이언트 상수로 채우지 않는다.** 설정 필드에 기본값을 되살리면 "운영자가 0.0 을 원했다" 와
  "아무도 원하지 않았다" 가 같은 상태가 된다
- **요청 파라미터를 모델 이름으로 분기하지 않는다.** 모델 지식은 레지스트리로만 온다([`model-capabilities.md`](model-capabilities.md))
- **Anthropic 의 두 게이트 순서를 뒤집지 않는다.** capability 가 바깥, thinking 창이 안쪽이다
- **"안전한" 값으로 대체하지 않는다.** 생략하고 보고한다
- **effort 를 `compareTo` 로 바닥과 비교하지 않고, 바닥 getter 를 되살리지 않는다.** ladder 는 집합이다
- **ladder 밖 rung 을 올리지 않는다**
- **`ReasoningEffort` 에 상수를 끝에 붙이지 않는다.** ladder 의 제자리에 넣는다
- **Chat 도구 규칙을 `maySendEffort` 로 옮기지 않고, ladder 검사를 도구 규칙과 함께 빼지 않는다.** 하나는 엔드포인트,
  하나는 모델의 사실이다
- **provider 의 두 effort 읽기 자리 중 하나만 우선순위를 바꾸지 않는다.** 헬퍼를 함께 부른다
- **보고 문구에 "configured" 를 쓰지 않는다.** 값의 출처가 운영자가 아닐 수 있다
- **트래픽 사실을 once 집합으로 보고하지 않는다.** 첫 발생 뒤로 조건이 계속되는 동안 침묵한다
- **모델에 따라 갈리는 보고의 signature 에서 모델 이름을 빼지 않는다.** 두 번째 모델의 보고가 첫 번째에 먹힌다

---

## 8. 남은 것

- **샘플링 값과 `responsesApiEnabled` 에 설정 표면이 없다** — 두 노브는 프로그램 조립에서만 닿는다. 이 항목을 착수하면
  Chat Completions 로 강제한 terra 에 `reasoning_effort: none` 을 보내는 측정되지 않은 칸이 운영자 경로로 내려오므로,
  그 칸을 재거나 재지 않기로 한 사실을 적어야 한다.
  [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-2
- **reasoning 모델에 effort 를 설정하지 않았을 때 명시 effort 를 늘 보낼지** — 현재는 보내지 않고 서버 기본값에 맡긴다.
  기술자에 정책 기본값을 넣는 모양은 기각했지만(§6), 명시 전송 자체를 하지 않기로 닫은 판단은 기록되지 않았다. 백로그 미등록
- **서브에이전트 요청의 temperature `0.7`** — 메인 에이전트가 temperature 를 정하지 않으면 서브에이전트 요청에 아무도
  적지 않은 값이 실린다. 거절 모델에서는 capability 억제가 막지만, 내장 표가 모르는 거절 모델에서는 400 이다(§3.5).
  사용자 보고가 없어 등록하지 않았다
- **`top_k` 는 실제 값(`5`)에서만 거절이 측정되었다** — `top_p` 처럼 존재로 거절되는지는 재지 않았다. 지금은 보낼
  경로가 없어 설계에 영향이 없지만, `LlmModel` 에 `topK` 가 생기면 "존재로 거절" 을 가정으로 물려받지 않아야 한다. 백로그 미등록
- **OpenAI 클라이언트에는 반복 보고 카운터가 없다** — reasoning trace 드롭 같은 트래픽 사실도 once 집합으로 보고되어, 조건이
  계속되는 동안 첫 발생 뒤로 침묵한다. §5.3 의 기준과 §7 의 규칙에 어긋나는 현재 코드다. 백로그 미등록
- **미설정 temperature 가 서버 기본값(1.0)으로 샘플링되는 것이 에이전트 동작에 주는 영향은 측정되지 않았다** — 값을
  원하는 배포가 설정할 판단으로 남긴다. 백로그 미등록

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `aimon-core/…/llm/LlmModel.java` | 샘플링·effort 선택 필드, 범위 검사가 sanity 경계이고 divergence 는 provider 가 보고한다는 규칙 |
| `aimon-core/…/llm/ReasoningEffort.java` | 다섯 rung, 오름차순 선언이 load-bearing 인 이유 |
| `aimon-core/…/llm/capability/ModelCapabilities.java` | `acceptedReasoningEfforts()`, `Builder.lowestReasoningEffort` 축약, `supportsSamplingParameters` · `supportsToolsWithReasoning` |
| `aimon-core/…/llm/capability/InMemoryModelCapabilityRegistry.java` | terra exact 행, `registerPrefix("gpt-5", …)` 가 terra 에 닿지 않는다는 좁힌 약속 |
| `aimon-core/…/subagent/execution/SubagentLlmDefaults.java` | 서브에이전트 temperature 상속과 `0.7` |
| `aimon-llm-openai/…/OpenAiRequestParameters.java` | `applySampling`, `requestedEffort`, `maySendEffort`, 억제·ladder 보고 문구, `SamplingSink` |
| `aimon-llm-openai/…/OpenAILlmClient.java` | `applyReasoningEffort`(도구 규칙이 여기 남는 이유), `reportDivergence`(once 집합, 상한 32) |
| `aimon-llm-openai/…/OpenAIResponsesRequestFactory.java` | 같은 effort 게이트(도구 규칙 없음), penalty 를 보고하는 Responses `SamplingSink` |
| `aimon-llm-openai/…/OpenAiReasoningEfforts.java` | 중립 rung → 와이어 값 번역의 유일한 자리 |
| `aimon-llm-openai/…/OpenAIConfig.java` | 선택 샘플링·effort 필드, 값이 있을 때만 범위 검사 |
| `aimon-llm-openai/…/OpenAIDivergenceReporter.java` · `aimon-llm-anthropic/…/AnthropicDivergenceReporter.java` | 협력자가 알아채고 클라이언트가 보고하는 구조 |
| `aimon-llm-anthropic/…/AnthropicLlmClient.java` | `applySamplingParameters`(두 게이트), `reportSuppressedSampling`, penalty 보고, `reportDivergence` · `reportRecurringDivergence` |
| `aimon-llm-anthropic/…/AnthropicConfig.java` | nullable `temperature`, `reasoningEffort` |
| `aimon-llm-anthropic/…/AnthropicThinkingResolver.java` | `requestedEffort` — 두 읽기 자리가 부르는 한 헬퍼 |
| `docs/features/llm/llm-provider-development-guide.md` | "프로바이더는 값을 지어내지 않는다" 규칙과 생략 = setter 미호출 |

경로의 `…` 는 `src/main/java/at/aimon/core/`(core) 또는 `src/main/java/at/aimon/core/llms/<provider>/`(provider
모듈)다. Anthropic 샘플링 규칙이 `OpenAiRequestParameters` 같은 별도 파일이 아니라 클라이언트 메서드 하나인 것은
의도다 — OpenAI 쪽 파일은 두 엔드포인트가 공유하는 규칙이라 존재하고, Anthropic 엔드포인트는 하나라서 떼어 내면 중복
제거 없이 "capability 먼저, thinking 규칙 안쪽" 이라는 한 결정을 두 파일로 쪼갠다. 두 번째 Anthropic 엔드포인트가
생기면 추출이 옳아진다.

---

## 관련 문서

- [`model-capabilities.md`](model-capabilities.md) — 능력 필드의 뜻과 기본값, fail-open, 내장 표와 측정 결론
- [`anthropic-thinking.md`](anthropic-thinking.md) — thinking 파라미터 해석, effort → 예산, finding 보고 계약
- [`openai-responses-path.md`](openai-responses-path.md) — 엔드포인트 라우팅과 Responses 요청 매핑
- [`configuration-surface.md`](configuration-surface.md) — `reasoningEffort` · 능력 선언 키, 선언 = 행 전체
- [`model-name-resolution.md`](model-name-resolution.md) — 요청이 싣는 모델 이름
- [`reasoning-traces.md`](reasoning-traces.md) — `reasoningTokens` 회계, trace 드롭 보고
- [`../../features/llm/llm-provider-development-guide.md`](../../features/llm/llm-provider-development-guide.md) — provider 구현 가이드
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — 설정 표면의 열린 항목
