# 모델 능력 (Model Capabilities)

> Status: **IMPLEMENTED** — 일곱 필드의 `ModelCapabilities`, `ModelCapabilityRegistry` 와 기본 구현
> `InMemoryModelCapabilityRegistry`, 두 provider 설정의 레지스트리 주입 지점, OpenAI · Anthropic 두 벤더를 기술하는
> 내장 표와 그 행의 근거가 들어가 있다. 남은 것은 §9.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm.capability`(`ModelCapabilities`, `ModelCapabilityRegistry`,
> `InMemoryModelCapabilityRegistry`, `ThinkingDialect`) · `aimon-llm-openai` / `aimon-llm-anthropic` —
> `OpenAIConfig` · `AnthropicConfig` 의 `modelCapabilityRegistry`, 각 클라이언트의 방어적 조회.

---

## 1. 문제와 범위

같은 요청 모양을 모든 모델에 보낼 수 없다. 샘플링 파라미터를 거부하는 모델, reasoning effort 의 ladder 가 서로
다른 모델, reasoning trace 를 다음 요청에 되실어야 추론이 도구 호출을 건너 사는 모델, thinking 요청 모양이 둘로
갈리는 모델이 한 배포 안에 섞인다. 여러 벤더는 파라미터를 값이 아니라 **존재**로 거부하므로, 클라이언트는
그 파라미터를 `null` 로 채울 수도 없고 아예 싣지 말아야 한다.

그래서 provider 클라이언트는 요청을 만들기 전에 "이 모델의 요청 표면이 무엇을 받는가" 를 물어야 한다. 이 설계는
그 답을 프레임워크가 조회할 수 있는 사실로 만든다. 정하는 것은 넷이다.

- 기술자에 무엇이 들어가고 각 필드가 무엇을 뜻하는가 (§3)
- 아무도 기술하지 않은 모델에 무엇을 답하는가 — fail-open (§3.2)
- 레지스트리가 어떻게 조회하고, 어디서 주입되며, 오류에 어떻게 반응하는가 (§4)
- 내장 표의 행이 어떤 원칙과 어떤 측정 위에 서는가 (§5, §6)

**이 문서가 정하지 않는 것.** 각 개념은 정본 문서가 따로 있다.

| 개념 | 정본 |
|---|---|
| 능력을 읽어 와이어 요청을 만드는 규칙 — 샘플링 생략, `ReasoningEffort` 어휘, ladder 밖 rung 처리, 도구 규칙, divergence 보고 | [`request-parameters.md`](request-parameters.md) |
| 설정에서 온 능력 선언 — 키, `ModelCapabilityDeclaration`, 거절 목록 | [`configuration-surface.md`](configuration-surface.md) |
| `thinkingDialect` 를 thinking mode 와 함께 요청으로 해석하는 표 | [`anthropic-thinking.md`](anthropic-thinking.md) |
| `supportsReasoningTraceRoundTrip` 에 따른 엔드포인트 라우팅, `supportsReasoningSummary` 게이트 | [`openai-responses-path.md`](openai-responses-path.md) |
| 조회에 쓰는 모델 이름이 정해지는 곳 | [`model-name-resolution.md`](model-name-resolution.md) |

---

## 2. 모델 지식의 자리 — 레지스트리 하나, 이름 분기 없음

### 2.1 provider 는 모델 이름으로 분기하지 않는다

모델 이름에서 요청 모양을 끌어내는 곳은 `ModelCapabilityRegistry` 하나뿐이다. provider 의 요청 빌더는 모델 이름을
`model` 필드에 싣고 레지스트리 조회의 키로 쓸 뿐, `model.startsWith(...)` 같은 분기를 두지 않는다.

이유는 넷이다.

- 그 지식은 다른 provider 클라이언트에서 닿지 않는다. OpenAI 클라이언트에 적은 분기는 Anthropic 클라이언트가 모른다
- `baseUrl` 뒤의 게이트웨이와 Azure 배포는 모델 이름을 마음대로 바꾼다. 이름 비교는 바로 그 배포에서 틀린다
- 계열이 하나 늘 때마다 분기의 복사본이 하나 는다
- 벤더의 모델별 표는 릴리스마다 바뀐다. 틀린 문자열 비교는 한동안 조용하다가 아무도 그 비교까지 추적하지 못하는
  400 으로 드러난다

그 결과 빈 레지스트리(`ModelCapabilityRegistry.EMPTY`)를 받은 클라이언트는 모델 이름에 대해 아무것도 모르고,
어느 이름에든 fail-open 요청(§3.2)을 만든다.

### 2.2 두 벤더가 한 표를 읽는다 — 벤더 모양의 사실도 중립 타입에 둔다

내장 표 하나를 두 클라이언트가 함께 읽는다. `gpt-*` · `o*` 행과 `claude-*` 행은 서로의 이름에 걸리지 않으므로
간섭하지 않지만, **조회는 공유된다.** OpenAI 호환 게이트웨이를 거쳐 `OpenAILlmClient` 에 도착한 `claude-*` 이름은
Anthropic 행으로 해석되어 샘플링이 억제된다. 그 모델이 실제로 샘플링을 거부하므로 옳은 답이지만, 어느 클라이언트
소스에서도 보이지 않는 결과다. 그래서 행 하나를 고치는 영향 범위는 두 provider 다.

같은 이유로 한 벤더만 읽는 사실도 중립 타입 `ModelCapabilities` 에 둔다.

| 필드 | 읽는 쪽 |
|---|---|
| `supportsToolsWithReasoning` | OpenAI 의 Chat Completions 경로만 |
| `thinkingDialect` | Anthropic 클라이언트만 |
| `supportsReasoningSummary` | OpenAI 의 Responses 경로만 |

벤더별 레지스트리를 따로 두지 않는 결정적 이유는 비용이다. 두 설정 표면은 한 번역기를 거쳐 이 표 하나에 선언을
넣는다([`configuration-surface.md`](configuration-surface.md)). 벤더 표가 둘이면 모델 이름을 바꾸는 게이트웨이
배포 — 선언이 존재하는 바로 그 배포 — 가 모델마다 두 모양으로, 두 키 아래에 선언해야 하고, 두 표는 서로 어긋날
길을 얻는다.

필드 이름은 벤더 엔드포인트가 아니라 중립 사실을 말한다. `supportsReasoningTraceRoundTrip` 은 "이 모델의 trace 를
되실어야 추론이 도구 호출을 건너 산다" 는 사실이다. OpenAI 에서 그 답은 `/v1/responses` 로 가서 reasoning item 을
재생하는 것이고, 그 엔드포인트 추론은 OpenAI 엔드포인트를 알아도 되는 `aimon-llm-openai` 가 한다. Anthropic 에서
같은 사실은 thinking 블록을 되보내는 것이며, Anthropic 클라이언트는 이 플래그를 읽지 않고 캡처와 재전송을
조건 없이 한다([`reasoning-traces.md`](reasoning-traces.md)).

### 2.3 형제 레지스트리로 둔다

`ModelCapabilityRegistry` 는 `ModelContextWindowRegistry` · `ModelPriceTable` 옆에 선다. 모델별 사실 셋, 진실
원천 셋, 바뀌는 이유 셋이다. 컨텍스트 창 레지스트리에 합치지 않는 이유는 넷이다.

- 각 소비자가 쓰지 않는 타입에 의존하게 된다 — 컴팩션 가드는 능력을, 요청 빌더는 창 한도를 알 필요가 없다
- "창에 얼마나 들어가는가" 와 "엔드포인트가 어떤 파라미터를 받는가" 는 다른 출처에서 오고 다른 이유로 바뀐다
- 공개 SPI 의 반환 타입을 바꾸거나 모든 외부 구현자가 고려해야 할 메서드를 더해야 한다. 형제로 두면 순수 추가다
- 타입 이름이 담는 것을 잘못 말하게 된다

구조는 형제를 따른다 — exact 항목, 그다음 대소문자를 무시하는 prefix 항목을 등록 순서대로. 한 레지스트리를 설정해
본 운영자는 다른 것도 같은 방식으로 읽는다. 타입은 `at.aimon.core.llm.capability` 하위 패키지에 둔다.

---

## 3. 기술자 — 클라이언트가 무엇을 해도 되는가

### 3.1 일곱 필드

`ModelCapabilities` 는 "이 모델이 무엇인가" 가 아니라 **"클라이언트가 이 모델의 요청을 만들 때 무엇을 해도 되는가"**
를 담는다. 각 필드는 파라미터를 보내도 되는지를 말하지, 보내는 것이 좋은 생각인지를 말하지 않는다. 불변이고
스레드 안전하며 빌더로 만든다.

| 필드 | 뜻 | `unknown()` 값 | 읽는 곳 |
|---|---|---|---|
| `supportsSamplingParameters` | 샘플링 파라미터를 설정해도 되는가. `false` 는 `null` 을 포함해 아예 설정하지 말라는 뜻 | `true` | `OpenAiRequestParameters`(두 엔드포인트), `AnthropicLlmClient` |
| `supportsReasoningEffort` | reasoning effort 파라미터가 이 모델의 요청 표면에 있는가 | `false` | `OpenAILlmClient`(Chat), `OpenAIResponsesRequestFactory` |
| `supportsToolsWithReasoning` | 도구와 `NONE` 아닌 effort 를 한 요청에 함께 실어도 되는가 — Chat Completions 표면의 성질 | `true` | `OpenAILlmClient`(Chat 경로만) |
| `supportsReasoningTraceRoundTrip` | reasoning trace 를 다음 요청에 되실어야 추론이 도구 호출을 건너 사는가 | `false` | `OpenAILlmClient`(엔드포인트 선택) |
| `acceptedReasoningEfforts` | effort 값으로 받는 rung 집합. 비어 있지 않고, 반복 순서가 ladder 순서다 | `{MINIMAL, LOW, MEDIUM, HIGH}` | `OpenAiRequestParameters.maySendEffort`(두 엔드포인트) |
| `thinkingDialect` | thinking 요청 파라미터의 모양 — `UNKNOWN` · `EITHER` · `BUDGETED` · `ADAPTIVE` | `UNKNOWN` | `AnthropicThinkingResolver` |
| `supportsReasoningSummary` | reasoning summary 요청을 받는가 | `true` | `OpenAIResponsesRequestFactory` |

### 3.2 fail-open — 요청한 것은 보류하지 않고, 요청하지 않은 것은 지어내지 않는다

**`unknown()` 은 "모든 것을 허용한다" 가 아니다.** fail-open 은 두 쪽을 가진 규칙이다 — 호출자가 요청한 것은
보류하지 않고, 호출자가 요청하지 않은 것은 지어내지 않는다. `unknown()` 의 플래그별 값은 이 규칙 하나에서 나오므로
플래그마다 boolean 이 다르다.

- **샘플링 `true`.** 호출자가 설정한 값을 아무도 기술하지 않은 모델에서 빼면 fail-closed 다. 이 값은 안전하기도
  하다 — 샘플링 파라미터를 생략한 요청은 측정한 어느 모델에서도 400 을 받지 않았다(§6.3). "두 값이 모두 어떤
  모델에서는 400" 인 성질은 샘플링 축이 아니라 thinking 방언 축의 것이다
- **effort `false`.** 누구도 설정하지 않은 effort 를 보내는 것은 프레임워크가 파라미터를 지어내는 것이다
- **도구 + reasoning `true`.** 증거 없이 조합을 막는 것은 아무도 요청하지 않은 제한이다
- **trace 왕복 `false`.** 기술되지 않은 모델의 요청 표면을 다른 엔드포인트로 옮기지 않는다
- **ladder `{MINIMAL, LOW, MEDIUM, HIGH}`.** `NONE` 하나만 보류한다. `NONE` 이 가장 자주 빠지는 rung 이지만 보편적으로
  빠지지는 않는다 — 측정한 o-series 이름은 모두, `gpt-5-nano` 도 `none` 을 거부하고, `gpt-5.6-terra` 는 받는다(자기
  행을 가진다). 이 기본값은 두 실수의 비대칭으로 고른다. 모델이 가진 rung 을 보류하면 보고된 생략과 서버 기본값이
  남고, 모델에 없는 rung 을 보내면 요청이 400 으로 실패한다
- **방언 `UNKNOWN`.** 두 실제 방언은 다른 쪽만 받는 모델에서 둘 다 400 이므로 안전한 **값**이 없다. 그래서 fail-open
  값은 방언 중 하나가 아니라 사실의 **부재**다. 클라이언트는 호출자가 설정한 것을 그대로 둔다
- **reasoning summary `true`.** summary 는 누군가 설정했을 때만 요청에 있으므로 보류하는 쪽이 fail-closed 다. 이 값은
  기술되지 않은 모델에서는 읽히지도 않는다 — 그 파라미터는 `/v1/responses` 에만 있고, 요청은 trace 왕복이 `true`
  일 때만 거기에 닿으며, trace 왕복의 fail-open 값은 `false` 다. 그래서 이 기본값의 일은 이미 있는 행들이 무엇을
  뜻하는지 말하는 것이다

**플래그는 지시가 아니라 권한이다.** 권한 안에서 무엇을 보낼지 — 설정되지 않은 값을 채울지, 무엇을 기본으로 할지 —
는 클라이언트가 정한다([`request-parameters.md`](request-parameters.md)).

**빌더는 `unknown()` 의 값으로 시작한다.** 그래서 한 플래그만 적은 행은 나머지를 `false` 가 아니라 fail-open 값으로
두고, fail-open 은 행을 쓰는 사람이 매번 다시 도출하는 규칙이 아니라 타입의 성질이 된다. 빌더의 시드는 `unknown()`
을 읽지 않고 같은 값의 리터럴이다. `unknown()` 자체가 빌더로 만들어지므로 빌더 초기화 중에 그것을 읽으면 반쯤 만들어진
객체를 받는다. 두 정의가 같다는 것은 테스트가 붙든다.

**fail-open 의 대가.** 모델 이름을 바꾼 배포는 어느 행에도 걸리지 않아 fail-open 경로에 남는다. 내장 행이 고쳐 줄
문제는 누군가 그 이름을 선언할 때까지 그 배포에서 그대로 남는다. 이것이 추측하지 않는 대가이고, 처방은 이름 하나를
선언하는 것이다 — 설정에서([`configuration-surface.md`](configuration-surface.md)) 또는
`InMemoryModelCapabilityRegistry.builderWithDefaults().register(...)` 로. 반대 방향도 있다 — 샘플링을 받는 모델에 붙인
게이트웨이 이름이 내장 prefix 에 걸리면 파라미터가 억제되고 요청은 다른 샘플링으로 성공한다. 값이 설정되어 있었다면
WARN 이 남고, 처방은 같다.

### 3.3 필드별 결정

**샘플링은 한 플래그다.** 샘플링 파라미터는 `LlmModel` 과 provider 설정이 실을 수 있는 넷 — `temperature` ·
`top_p` · `presence_penalty` · `frequency_penalty` — 이다. `top_k` 는 실을 필드가 없어 해당 없다. 다만 측정한 거부
모델은 `top_k` 도 거부하므로(§6.2), 필드를 더하면 같은 플래그가 덮는다. 넷을 한 플래그로 두는 이유는 부분집합만
받는 모델이 측정되지 않았기 때문이다 — Anthropic 에서 잴 수 있는 `temperature` · `top_p` · `top_k` 는 모델마다 한꺼번에
거부되거나 한꺼번에 받아졌고, 두 penalty 는 Anthropic 에 대응물이 없다. 나중에 나누는 것은 빌더 메서드와 getter 를
더하는 순수 추가다. `temperature` 는 값으로, `top_p` 는 존재로 거부되지만 한 플래그가 둘 다 덮는다 — 그 과대 근사를
어떻게 다루는지는 [`request-parameters.md`](request-parameters.md).

**`supportsToolsWithReasoning` 은 엔드포인트 성질이다.** "이 모델은 해석하는 클라이언트가 쓰는 요청 표면에서 도구와
reasoning 을 함께 받는가" 를 뜻하고, 내장 표는 OpenAI 의 Chat Completions 를 기술한다. `/v1/responses` 에서는 둘이
공존하므로 그 경로는 이 플래그를 읽지 않는다. 적용 규칙은 [`request-parameters.md`](request-parameters.md).

**`acceptedReasoningEfforts` 는 바닥이 아니라 집합이다.** `gpt-5.6-terra` 는 `none` 을 받고 `minimal` 을 거부하므로
ladder 중간에 구멍이 있다(§6.1). 바닥 하나로는 이것을 기술할 수 없다 — `NONE` 이나 `MINIMAL` 을 바닥으로 적으면
`minimal` 이 게이트를 통과해 400 이 되고, `LOW` 를 적으면 동작은 맞지만 "terra 는 `low` 부터 시작한다" 는 거짓을
적는다. 거짓을 적어 맞는 동작을 사면 다음 독자가 그 모델에 대해 틀린 사실을 배운다. 집합은 비어 있을 수 없다 —
"effort 파라미터는 있지만 받는 값이 없다" 는 요청 표면의 상태가 아니고, 그 상태의 이름은
`supportsReasoningEffort(false)` 다. `null` 원소도 거절한다. 반복 순서는 `ReasoningEffort` 의 선언 순서, 즉 ladder 순서라
집합을 찍는 경고가 ladder 로 읽힌다. 구멍 없는 ladder 는 `Builder.lowestReasoningEffort(lowest)` 로 적을 수 있고, 그것은
`lowest` 부터 `HIGH` 까지의 집합의 줄임이다. 이 집합을 요청에서 어떻게 쓰는지는 [`request-parameters.md`](request-parameters.md).

**`thinkingDialect` 의 네 상수 중 둘은 방언이 아니다.** `UNKNOWN` 은 "이 표는 답할 수 없다" 이고, `EITHER` 는 "답할 수
있고, 답은 두 모양을 모두 받는다" 이다. `EITHER` 는 "둘 다 받는다고 측정됨" 과 "기술되지 않음" 이 같은 값이 되지 않게
하려고 있다. 두 상수는 **행**이 가질 수 있는 값이지 **요청**이 말하는 모양이 아니다 — 클라이언트는 와이어에 닿기 전에
그것을 실제 방언 하나로, 또는 thinking 파라미터 없음으로 해석한다. 두 방언은 대부분의 모델에서 서로 배타적이지만
`EITHER` 행 모델은 둘 다 받으므로, 중립 타입의 서술은 보편적 배타를 단언하지 않는다. effort rung 만으로 같은 축을
표현하는 벤더를 위한 상수는 없다 — `supportsReasoningEffort()` 가 이미 답하는 질문이다. 해석표와 `EITHER` 아래의 선택
정책은 [`anthropic-thinking.md`](anthropic-thinking.md).

**`supportsReasoningSummary` 는 게이트웨이를 위한 모델별 손잡이다.** trace 왕복을 `true` 로 선언한 OpenAI 호환 게이트웨이
모델은 Responses 엔드포인트로 가는데, 그 게이트웨이가 `reasoning.effort` 는 받고 `reasoning.summary` 는 거부하면 요청이
400 이 된다. summary 요청 자체는 클라이언트 단위이므로 모델 단위의 끄는 자리가 따로 필요하다. trace 왕복 플래그에 접지
않는다 — 그것은 측정된 사실(이 모델은 trace 를 재생한다)이고 게이트웨이는 그것을 만족하면서 summary 만 거부할 수 있어,
접으면 선언된 행 하나가 운영자가 가를 수 없는 두 사실을 단언하게 된다. 이름이 OpenAI 모양이 아닌 것은 Anthropic 이 같은
축을 `thinking.display: summarized` 로 표현하기 때문이지만, 오늘 이 플래그를 읽는 곳은 Responses 요청 팩토리 하나다.
게이트 동작은 [`openai-responses-path.md`](openai-responses-path.md).

---

## 4. 레지스트리 — 구현은 `Optional`, 호출은 total

### 4.1 두 메서드

```java
public interface ModelCapabilityRegistry {
    ModelCapabilityRegistry EMPTY = modelName -> Optional.empty();

    Optional<ModelCapabilities> capabilitiesOf(String modelName);   // 구현자가 쓴다

    default ModelCapabilities resolve(String modelName) {           // 호출자가 읽는다
        final Optional<ModelCapabilities> found = capabilitiesOf(modelName);
        return found == null || found.isEmpty() ? ModelCapabilities.unknown() : found.get();
    }
}
```

메서드가 둘인 것은 두 사람에게 답하기 때문이다.

- **구현자**(Azure 배포 조회, 설정 기반 표, 원격 카탈로그)는 빗나갈 수 있는 조회를 쓴다. `Optional` 이면 빗나감이
  타입으로 강제된다. total 메서드 하나라면 빗나갈 때 `unknown()` 을 돌려주는 것을 기억해야 하고, `null` 은 요청
  빌더를 NPE 로 멈추며, 제한적인 기술자는 들어 본 적 없는 모든 모델에서 fail-open 을 조용히 fail-closed 로 바꾼다
- **호출자**는 null 이 아닌 답 하나를 원한다. `resolve` 가 그 답이고, fail-open 규칙이 사는 유일한 자리라 테스트가
  붙들 수 있는 유일한 자리이기도 하다

`capabilitiesOf` 가 `null` 을 돌려주면 `resolve` 는 빗나감으로 다룬다. `resolve` 를 override 하는 구현은 절대 `null`
을 돌려주지 않는다 — 호출자는 결과를 total 로 다룬다.

형제 `ModelPriceTable` 은 `Optional` 만 있고 total 뷰가 없다. 정직한 기본 가격이 없어서 추정한 단가로 비용을 지어내지
않기 위해서다. 능력에는 정직한 기본값 — fail-open — 이 있으므로 둘 다 둔다.

### 4.2 조회 순서 — exact, 그다음 등록 순서의 prefix

`InMemoryModelCapabilityRegistry.capabilitiesOf` 는 이 순서로 답한다.

1. **exact 항목.** 대소문자를 무시한다
2. **prefix 항목을 등록 순서대로.** 대소문자를 무시하고, 처음 걸린 것이 이긴다
3. 아무것도 걸리지 않으면 `Optional.empty()` — `resolve` 가 `unknown()` 으로 바꾼다

기본 항목(fallback entry)은 없다. 표는 들어 본 적 없는 모델을 기술할 수 없고, 추측은 fail-open 을 조용한 와이어
변경으로 바꾸는 유일한 방법이다.

**exact 와 prefix 가 모두 대소문자를 무시한다.** 운영자가 포털에서 복사한 배포 이름(`Prod-Assistant`)과 설정의 모델
이름이 만나야 한다. 한쪽만 대소문자를 구분하면 올바르게 등록한 항목이 아무 보고 없이 빗나간다.

**prefix 사이에서는 등록 순서가 의미를 갖는다.** 더 구체적인 prefix(`gpt-5-chat`)를 넓은 계열 prefix(`gpt-5`) 앞에
등록해야 넓은 쪽이 삼키지 않는다. `builderWithDefaults()` 위에서 이미 있는 prefix 를 다시 등록하면 **제자리에서**
교체된다(`LinkedHashMap` 은 다시 넣어도 순서를 바꾸지 않는다). 그래서 `gpt-5` 를 덮어써도 `gpt-5-chat` 앞으로 뛰어들
수 없다. exact 항목은 순서와 무관하게 모든 prefix 를 이긴다.

### 4.3 exact 행은 자기 이름에 대해 prefix override 를 가린다

exact 가 prefix 를 이기므로, 내장 exact 행이 있는 이름에는 계열 prefix override 가 닿지 않는다.

| override | 닿는 이름 | 닿지 않는 이름 |
|---|---|---|
| `builderWithDefaults().registerPrefix("o1", …)` | `o1-pro` 와 앞으로의 `o1*` 이름 | `o1`, `o1-2024-12-17` |
| `builderWithDefaults().registerPrefix("gpt-5", …)` | `gpt-5-mini`, `gpt-5-nano` 와 앞으로의 `gpt-5*` 이름 | `gpt-5.6-terra` |

처방은 둘 다 같다 — `register(name, …)` 로 그 이름을 덮어쓰거나 그 이름을 설정에서 선언한다. 둘 다 exact 항목을
등록하므로 내장 exact 행을 대체한다.

계열 override 가 "모든 `gpt-5*` 이름에 언제나" 닿는다는 약속은 두지 않는다. 그 약속을 지키려면 terra 에 자기 행을
줄 수 없는데, 레지스트리의 어떤 모양도 — exact 행, `gpt-5` 앞의 terra prefix, 뒤의 terra prefix — 그 약속을 깨지 않고
terra 의 ladder 를 고치지 못한다. 약속에서 하중을 받는 것은 **제자리 교체**(계열 override 가 더 구체적인 prefix 를
앞지르지 않는다)이고, 그것은 그대로 남는다. 모든 계열 이름에 닿는다는 나머지 절반의 값은 문서 예시 하나가 깔끔한
것이었고, 그 값은 측정된 400 보다 싸다. o-series 에는 같은 약한 약속이 이미 있다.

### 4.4 레지스트리 오류도 fail-open 이다

레지스트리는 호출자가 준 협력자다. 두 클라이언트의 `capabilitiesFor(modelName)` 은 조회를 방어적으로 감싼다.

| 레지스트리가 | 클라이언트는 | 보고 signature |
|---|---|---|
| `RuntimeException` 을 던진다 | 한 번 보고하고 `unknown()` 을 쓴다 | `capabilityLookupFailed@<model>` |
| `resolve` 에서 `null` 을 돌려준다 | 한 번 보고하고 `unknown()` 을 쓴다 | `capabilityLookupReturnedNull@<model>` |

던지는 레지스트리를 삼키는 이유는 호출 지점에 있다. 조회는 스트리밍 경로의 try-with-resources **밖**에서 일어나므로,
여기서 빠져나간 예외는 예외 매퍼와 취소 분류를 모두 건너뛴다. SPI 의 fail-open 규칙을 SPI 자신에게 적용하는 것이다.

`null` 을 조용히 흡수하지 않고 보고하는 이유는 옆 갈래가 보고하기 때문이다. 보고되는 강등 옆에 조용한 강등이 있으면
운영자는 능력 조회가 실패하지 않는다고 배운다. 보고는 divergence 보고의 once 집합을 쓴다
([`request-parameters.md`](request-parameters.md)).

### 4.5 주입 지점 — provider 설정 빌더

레지스트리는 provider 설정으로 들어간다 — `OpenAIConfig.Builder.modelCapabilityRegistry(...)`,
`AnthropicConfig.Builder.modelCapabilityRegistry(...)`. 기본값은 `InMemoryModelCapabilityRegistry.withDefaults()` 이고
`null` 은 거절한다. `baseUrl` · `timeout` 처럼 "이 배포의 엔드포인트는 기본 OpenAI · Anthropic 이 아니다" 를 말하는
손잡이 옆에 둔다. CLI 와 스타터의 조립 경로는 선언이 있을 때 그것으로 만든 레지스트리를 여기에 넘긴다
([`configuration-surface.md`](configuration-surface.md)). 능력을 `LlmModel` 에 싣지 않는 이유는 §7.

**`AnthropicConfig` 의 `equals` · `hashCode` · `toString` 에는 레지스트리가 들어가지 않는다.** 레지스트리는 정체성
의미를 가진 협력자다 — 어떤 구현도 `equals` 를 정의하지 않고, 기본값은 `build()` 마다 새로 만든 인스턴스다. 동등성에
넣으면 운영자가 쓸 수 있는 모든 값이 같은 두 설정이 서로 다르게 된다. `toString` 에서 빼는 대가는 로그에 찍힌 설정으로는
능력 선언이 적용되었는지 알 수 없다는 것이다. 그 질문의 답은 값을 떨어뜨린 모델을 이름하는 WARN, 또는 그것의 부재다.
`OpenAIConfig` 는 `equals` 를 정의하지 않는다.

### 4.6 설정 선언은 행 전체를 대체한다

설정의 능력 선언(`llm.modelCapabilities.<model>`, `aimon.llm.model-capabilities.<model>`)은
`InMemoryModelCapabilityRegistry.withDefaultsExtendedBy` 를 거쳐 내장 표 위에 **exact** 항목으로 들어가고, 그 이름의
행을 **패치하지 않고 전체를 대체한다** — 적지 않은 필드는 내장 행이 말하던 값이 아니라 fail-open 값으로 떨어진다.
규칙과 거절 목록의 정본은 [`configuration-surface.md`](configuration-surface.md) 다.

---

## 5. 내장 표의 원칙

### 5.1 버그만큼 작게, 측정한 만큼만

내장 표는 이 설계에서 세상에 대해 틀릴 수 있는 유일한 부분이다. 동시에 완전히 덮어쓸 수 있고 fail-open 하는 부분이다.
그래서 문제만큼 작게 둔다 — 요청 표면이 fail-open 기본값과 다르다고 알려진 계열만 기술한다.

- **측정 전까지 능력을 덜 준다.** 아무도 본 적 없는 와이어 변경을 행이 주장하지 않는다. 표는 이름이 측정될 때까지
  능력을 덜 주고, 틀린 방향은 언제나 "덜 준다" 쪽이다
- **행은 측정한 플래그만 적는다.** 나머지는 fail-open 에 둔다. Claude 행은 trace 왕복 · effort · ladder 를 적지 않는다 —
  Anthropic 클라이언트가 그 셋을 읽지 않으므로 값을 적으면 소비자 없는, 측정하지 않은 단언이 된다. 방언만 적는
  `claude-*-4-5` · `-4-6` 행은 샘플링을 fail-open `true` 에 둔다 — 그 모델들은 샘플링 파라미터를 받는다(§6.2)
- **틀린 행과 빠진 행의 비용을 비교한다.** 샘플링을 거부한다고 잘못 적은 행은 설정된 값 하나를 떨어뜨리고 WARN 을
  남긴 채 요청이 성공한다. 거부하는 모델의 행이 빠지면 매 요청이 400 이다

### 5.2 측정된 이름은 exact, 계열은 측정한 세대까지 prefix

**OpenAI 는 측정한 이름을 exact 로 적는다.** reasoning item 재생과 ladder 는 계열이 아니라 이름 단위로 알려진다. 이름
prefix 를 공유하는 형제의 측정은 요청 표면에 대한 증거가 아니다 — `o1` 이 통과했다고 `o1-pro` 에 대해 말해지는 것은
없다. 그래서 측정된 o-series 여덟 이름과 `gpt-5.6-terra` 는 exact 행이고, 같은 prefix 아래의 나머지 이름은 prefix 행의
값으로 떨어진다. 본 적 없는 terra 의 날짜 스냅샷은 `gpt-5` prefix 에 떨어져 계열 ladder 를 물려받으며, 누군가 재거나
선언할 때까지 그대로다.

별칭 exact 행(`o1`, `o3`, …)은 별칭이 측정한 날 가리키던 곳을 가리키는 동안만 옳다. 별칭이 옮겨 가면 그 행은 조용히
측정하지 않은 모델에 대한 단언이 된다. 이 위험은 계열 prefix 행이 더 크게 지고 있으며, 수용한다.

**Anthropic 은 prefix 로 적되 측정한 세대까지만 주장한다.** prefix 이면 날짜 스냅샷이 행을 물려받는다. 날짜 없는
`claude-*-4-5` 별칭은 모델 목록에 없지만 호출되고 같은 방언이므로, prefix 하나가 배포가 가장 쓰기 쉬운 별칭과 날짜
스냅샷을 함께 덮는다. 계열 prefix 는 측정한 세대까지만 넓힌다 — `claude-fable` 이 아니라 `claude-fable-5`, `claude-opus-4`
가 아니라 `claude-opus-4-7` · `claude-opus-4-8`. `claude-opus-4` 는 샘플링을 받는 `claude-opus-4-5` · `claude-opus-4-6` 을
삼킨다. Anthropic 쪽이 본 적 없는 스냅샷에 대해 prefix 로 주장해도 되는 것은 벤더의 모델별 표가 그 계열을 열거하기
때문이다. terra 의 스냅샷을 열거하는 문서는 없다.

### 5.3 등록 순서가 의미를 갖는 곳

등록 순서는 한 이름에 함께 걸릴 수 있는 prefix 사이에서만 의미를 갖는다.

| 블록 | 순서 |
|---|---|
| `gpt-5-chat` · `gpt-5` | **의미 있음** — `gpt-5-chat` 이 먼저여야 계열의 억제를 물려받지 않는다 |
| `o1` · `o3` · `o4` | 자유 — 서로의 prefix 가 아니다 |
| `claude-*` 열한 prefix | 자유 — `gpt-*` · `o*` 와 겹칠 수 없고, `-4-5` · `-4-6` · `-4-7` · `-4-8` 은 형제이지 중첩이 아니다 |
| exact 행 | 자유 — exact 는 위치와 무관하게 모든 prefix 를 이긴다 |

### 5.4 documentation-derived 행 — `claude-mythos`

`claude-mythos` 행은 측정하지 않았다. 측정에 쓴 계정의 모델 목록에 그 prefix 로 시작하는 모델이 없어 사실도 식별자도
호출하지 못했다. 그래도 행을 싣고 소스 주석에 documentation-derived 로 표시한다.

- **증거의 종류가 다르다.** 벤더 문장이 샘플링을 거부하는 아홉 모델을 이름하고, 그중 도달 가능한 여섯이 세 파라미터
  모두에서 문장과 정확히 일치했다(거짓 양성 · 거짓 음성 없음). 이것은 그 문장의 신뢰도에 대한 증거이지, 이름 prefix 를
  공유하는 형제로부터의 추론이 아니다
- **비용 행렬이 같은 쪽을 가리킨다.** 틀린 행은 값 하나 드롭과 WARN, 빠진 행은 매 요청 400 이다
- **식별자를 추측하지 않는다.** `claude-mythos-5-1` 같은 이름을 적으면 아무도 본 적 없는 식별자를 단언하게 된다. 계열
  prefix 하나는 사실만 단언한다

### 5.5 측정을 인용하는 법

- **행 옆 소스 주석은** 측정 날짜 · 조사 표면(모델 목록과, 목록에 없지만 호출한 이름) · 보낸 요청 모양과 그 결과 · 행이
  일부러 말하지 않는 것 · 결론을 담은 이 문서를 적는다. 백로그 항목이나 이슈는 인용하지 않는다 — 닫힌 항목은 증거가
  아니라 결정의 기록이다
- **거부 메시지의 열거는 엔드포인트 범위이자 모델 범위다.** 같은 `o4-mini` 에 대해 Chat Completions 는 `low, medium,
  high, xhigh` 를, `/v1/responses` 는 `low, medium, high` 를 열거한다. 엔드포인트 없이 인용한 열거는 아무것도 뜻하지
  않고, 확실히 아는 rung 은 **실제로 보낸** rung 뿐이다. 결론 표는 보낸 것(측정)과 서버가 열거만 한 것을 가른다
- **에러 메시지는 명세가 아니다.** 샘플링 거부 모델의 400 은 `` `temperature` is deprecated for this model. `` 이라서
  파라미터 전체를 거부하는 것처럼 읽히지만 `temperature: 1.0` 은 받아들여진다. 어떤 값이 받아들여지는지는 보내 봐야 안다
- **수용만으로는 증거가 아니다.** 서버는 재생한 item 이 없는 요청도 받으므로, 재생 수용은 손상시킨 payload 가 거절되는
  음성 대조와 짝일 때만 소비의 증거다
- **모델 목록은 호출 가능한 이름의 집합이 아니다.** 목록에 없는 이름이 호출되고(날짜 없는 `claude-*-4-5`), 목록에
  있다는 것이 호출된다는 뜻도 아니다
- **미측정은 미측정이라고 적는다.** 빈 칸을 이웃의 측정으로 채우지 않는다(§6.4)

---

## 6. 내장 표 — 결론 표

행 옆 소스 주석이 가리키는 측정 결론이 이 절이다. 각 행은 적지 않은 필드를 fail-open 값에 둔다.

### 6.1 OpenAI 행

| 행 | 모양 | 적는 값 | 근거 | 날짜 |
|---|---|---|---|---|
| `gpt-5-chat` | prefix, `gpt-5` 앞 | 샘플링 `true` · effort `false` · 도구+reasoning `true` · trace 왕복 `false` — `unknown()` 과 같은 값 | 측정한 행이 아니다. 행의 일은 계열의 비-reasoning 변형이 `gpt-5` 행에 걸려 샘플링을 억제당하지 않게 하는 것이다. `gpt-5-chat-latest` 는 404(deprecated)지만 행은 그 이름 하나가 아니라 prefix 다. 모델이 없으면 행은 무해하고, 행이 없으면 비-reasoning 모델의 샘플링이 생략된다 | 404: 2026-09-09 |
| `gpt-5` | prefix | 샘플링 `false` · effort `true` · 도구+reasoning `true` · trace 왕복 `true`. ladder 는 적지 않음 | Chat Completions(`gpt-5-nano`): `temperature` 는 `1.0` 만 받고 `0.0` 거부 — 생략이 모델이 받는 유일한 값과 같다. effort 없이 도구를 실은 요청 200 — 충돌이 없으므로 `true`. `reasoning_effort: "none"` 거부, 열거는 `minimal`·`low`·`medium`·`high`. Responses: 재생한 reasoning item 수용. ladder 는 fail-open 기본 `{MINIMAL..HIGH}` 이 측정과 맞으므로 적지 않는다 — 구멍은 계열이 아니라 terra 의 것이다 | 2026-09-09 |
| `gpt-5.6-terra` | exact | `gpt-5` 계열의 네 플래그 + ladder `{NONE, LOW, MEDIUM, HIGH}` | `/v1/responses` 에 일곱 rung 을 하나씩 보냈다: `none`·`low`·`medium`·`high`·`xhigh`·`max` 수용, `minimal` 거부. 모든 rung 을 실제로 보낸 유일한 ladder 다. `xhigh`·`max` 는 중립 어휘에 상수가 없어 행에 없다([`request-parameters.md`](request-parameters.md)). 재생한 reasoning item 수용. `/v1/responses` 에서 `temperature: 0.0` 거부 · `1.0` 수용. 네 플래그는 계열 행과 같은 출처에서 오고 ladder 만 다르다 — trace 왕복이 `false` 로 어긋나면 이 이름이 조용히 `/v1/responses` 를 떠난다 | ladder · 재생 2026-09-09, `temperature` 2026-09-10 |
| `o1` · `o3` · `o4` | prefix ×3 | 샘플링 `false` · effort `true` · 도구+reasoning `true` · trace 왕복 `false` · ladder `{LOW, MEDIUM, HIGH}` | Chat Completions: `o3-mini`·`o4-mini` 의 `temperature` 는 `1.0` 수용·`0.0` 거부, `o4-mini` 는 effort 없이 도구를 실은 요청 200, `none` 거부. ladder 는 아래 exact 행과 같은 측정이다. trace 왕복 `false` 는 이 prefix 아래의 호출하지 않은 이름 — `o1-pro`, `o4-mini-deep-research` 와 앞으로의 이름 — 이 떨어지는 값이다 | 2026-09-09 |
| 측정된 o-series 여덟 이름: `o1` `o1-2024-12-17` `o3` `o3-2025-04-16` `o3-mini` `o3-mini-2025-01-31` `o4-mini` `o4-mini-2025-04-16` | exact | prefix 행과 같고 trace 왕복 `true` | `/v1/responses` 에서 `o1`·`o3`·`o3-mini`·`o4-mini` 가 재생한 reasoning item 을 받았다. 수용이 소비라는 것은 손상 대조군이 보인다([`openai-responses-path.md`](openai-responses-path.md)). 날짜 붙은 이름은 요청에 보낸 것이 아니라 수용된 응답의 `model` 로 돌아온 이름이다 — 스냅샷을 요청하면 그 스냅샷에 닿는다는 가정 하나가 있다. ladder: `none`·`minimal`·`xhigh`·`max` 를 하나씩 보내 거부, `low` 수용, `medium`·`high` 는 서버 열거에 있다(`high` 는 `o1` 을 뺀 셋에서 따로 보냈다) | 2026-09-09 |

### 6.2 Anthropic 행

| 행 | 모양 | 적는 값 | 근거 | 날짜 |
|---|---|---|---|---|
| `claude-fable-5` · `claude-opus-5` · `claude-opus-4-7` · `claude-opus-4-8` · `claude-sonnet-5` | prefix ×5 | 샘플링 `false` · `ADAPTIVE` | 샘플링: 여섯 이름(`claude-fable-5-1` 은 `claude-fable-5` prefix 에 걸린다)이 `temperature` `0.0`·`0.5` 거부 · `1.0` 수용, `top_p`(`0.9`, 그리고 `claude-opus-5`·`claude-opus-4-7` 에서 `1.0`) 거부, `top_k: 5` 거부. 방언: adaptive 200 · budgeted 400 | 샘플링 2026-09-09, 방언 2026-09-10 |
| `claude-mythos` | prefix | 샘플링 `false` · `ADAPTIVE` | documentation-derived(§5.4). 사실도 식별자도 호출하지 않았다 | — |
| `claude-opus-4-5` · `claude-sonnet-4-5` · `claude-haiku-4-5` | prefix ×3 | `BUDGETED` 만 | 날짜 스냅샷과 목록에 없는 날짜 없는 별칭 모두 adaptive 400 · budgeted 200. 세 모델 모두 샘플링 파라미터를 받으므로 샘플링은 fail-open 그대로 둔다 | 방언 2026-09-10, 샘플링 수용 2026-09-09 |
| `claude-opus-4-6` · `claude-sonnet-4-6` | prefix ×2 | `EITHER` 만 | adaptive 200 · budgeted 200. 샘플링 파라미터를 받는다. 날짜 붙은 4-6 이름은 추측한 둘이 404 여서 주장하지 않고, 나타나면 prefix 가 물려준다 | 방언 2026-09-10, 샘플링 수용 2026-09-09 |

샘플링을 거부하는 모델과 adaptive 전용 모델이 같은 집합인 것은 측정된 현재 세대가 adaptive 전용 세대이기 때문이다.
샘플링을 받으면서 budgeted 방언을 쓰는 모델도 기술할 수 있다 — 내장 표에 그런 행이 필요하지 않을 뿐이다.

### 6.3 행이 기대는 측정 — 방법과 대조군

| 측정 | 방법 | 결과 | 날짜 |
|---|---|---|---|
| OpenAI 샘플링 · 도구 · effort `none` | `api.openai.com` `/v1/chat/completions` — `gpt-5-nano`·`o4-mini`·`o3-mini`·`gpt-4o-mini` | `gpt-5-nano`·`o4-mini`·`o3-mini` 는 `temperature: 1.0` 수용·`0.0` 거부, `gpt-4o-mini` 는 둘 다 수용. effort 없이 도구를 실은 요청은 `gpt-5-nano`·`o4-mini` 에서 200. `reasoning_effort: "none"` 은 둘 다 거부 | 2026-09-09 |
| OpenAI reasoning item 재생 · ladder | `/v1/responses` — `o4-mini`·`o3-mini`·`o3`·`o1`·`gpt-5.6-terra`. 첫 요청은 함수 도구 하나 · `store: false` · `include: ["reasoning.encrypted_content"]` · effort `low`, 둘째 요청은 받은 reasoning item 을 함수 호출과 그 출력 앞에 재생. rung 은 하나씩 보냈다. 비용 규칙으로 `*-pro` · `*-deep-research` 이름은 호출하지 않았다 | 다섯 이름 모두 재생 수용. ladder 는 §6.1 | 2026-09-09 |
| `gpt-5.6-terra` 샘플링 | `/v1/responses`, `temperature` 값 둘 | `0.0` 거부, `1.0` 수용 | 2026-09-10 |
| Anthropic 샘플링 | `POST /v1/messages`, `max_tokens: 16`, 셀당 파라미터 하나, thinking 파라미터 없음 — 계정의 `GET /v1/models` 목록 11 모델 | §6.2. 대조군 셋: 샘플링 파라미터를 뺀 같은 body 는 거부 여섯과 수용 둘 모두 200(400 은 요청 모양 · 계정 · 헤더 탓이 아니다), `temperature: 1.0` 은 거부 여섯 모두 200(값으로 거부한다), `"temperature": null` 은 샘플링을 받는 모델에서도 400(능력 규칙이 아니라 타입 규칙이다) | 2026-09-09 |
| Anthropic 방언 | SDK 검증이 서버 답을 가리지 않도록 raw HTTP `POST /v1/messages` — 목록의 11 모델과 목록에 없는 날짜 없는 별칭 `claude-opus-4-5`·`claude-sonnet-4-5`·`claude-haiku-4-5`. 네 모양: `thinking.type: adaptive` + `output_config.effort: low` / `thinking.type: enabled` + `budget_tokens: 1024` / adaptive 단독 / effort 단독 | §6.2. 반복 측정이 모든 칸에서 재현되었고, 400 은 `invalid_request_error`(요청 검증)라 결정적이다. adaptive 단독은 첫 모양과 같은 답이므로 400 의 원인은 `thinking.type` 이다. effort 단독은 `claude-haiku-4-5`·`claude-sonnet-4-5` 에서 400, `claude-opus-4-5` 에서 200 — 방언과 독립된 축이다(그 가드는 [`anthropic-thinking.md`](anthropic-thinking.md)). 별칭 셋은 호출되어 날짜 스냅샷으로 해석되었다. 추측한 날짜 4-6 이름 둘은 404 | 2026-09-10 |

### 6.4 미측정 칸

아래는 열린 작업이 아니라 비어 있는 칸이다. 채우려면 예산이 붙은 별도 프로브가 필요하다.

**OpenAI**

- `o1-pro` · `o1-pro-2025-03-19` · `o4-mini-deep-research` · `o4-mini-deep-research-2025-06-26` — 비용 규칙으로 호출하지
  않았다. 재생 · ladder · 스트리밍 모양 · 샘플링 모두 모른다. 그래서 prefix 행의 trace 왕복이 `false` 다
- `gpt-5.6-luna` · `gpt-5.6-sol` — 목록에서 보았지만 호출하지 않았다. terra 와 같은 구멍이 있는지 모르므로 행이 없다
- 날짜 붙은 `gpt-5.6-terra-*` 스냅샷 — 본 적 없다
- terra 의 ladder 와 샘플링은 `/v1/responses` 에서만 쟀다. effort 게이트는 두 엔드포인트가 함께 쓰므로, Chat Completions
  로 강제한 terra 는 잰 적 없는 칸에 `reasoning_effort: none` 을 보낸다. 행은 그래도 옳다 — 대안은 `minimal` 의 측정된
  400 이다(백로그 L-2, §9)
- effort 를 싣지 않은 o-series `/v1/responses` 요청이 `encrypted_content` 를 가진 reasoning item 을 돌려주는가 — 모든
  프로브가 effort 를 실었지만 `OpenAIConfig.reasoningEffort` 의 기본은 미설정이다. 돌려주지 않으면 그런 배포에서 trace
  왕복은 효과가 없다
- 프로브의 도구는 `"strict": true` 였고 출하하는 모양은 `strict(false)` 다. 방향은 안전하지만(서버에 검증을 덜 요청한다)
  같은 종류의 차이다
- 모든 모델의 `medium` rung, `o1` 의 `high` — 서버 열거로만 안다
- Chat Completions 의 `xhigh` · `max` — 어느 모델에도 보내지 않았다
- 손상 대조군을 `o3` · `o3-mini` · `o1` 에서 — `o4-mini` 와 terra 에서만 돌렸다. 플랫폼 수준 검사라는 해석은 추론이다
- `o4-mini` 밖의 스트리밍 텍스트 출력과 `output_item.added` / `.done` 재생 비교
- `gpt-5-nano` 의 ladder 를 rung 하나씩 — Chat 에서 `none` 만 보냈다
- `gpt-5-chat` 행의 플래그 — 측정한 적이 없다(§6.1)
- Azure · 게이트웨이 배포 — `api.openai.com` 만 호출했다

**Anthropic**

- `claude-mythos` 계열 — 방언도 샘플링도, 식별자 모양도 모른다(§9)
- 샘플링 거부 여섯의 날짜 스냅샷 — 목록이 모두 날짜 없는 이름이라 호출할 스냅샷이 없었다. prefix 가 물려준다고 가정한다
- `top_k` 를 존재로 거부하는가 — `top_k: 5` 만 보냈다(문서화된 기본값이 없다). `LlmModel` 에 `top_k` 필드가 생기면 이
  가정을 물려받지 않는다
- 거부 여섯의 `temperature` 가 `0.5` 와 `1.0` 사이에서 어떤가 — `0.0` · `0.5` · `1.0` 만 보냈다. 결론 표는 경계로 읽지 않는다
- §6.2 의 샘플링 칸은 thinking 을 끈 요청 표면이다. 수용 다섯이 thinking 을 켰을 때 따르는 제약은 다시 재지 않았다
- 날짜 붙은 `claude-*-4-6-*` 스냅샷 — 추측한 이름이 404 였다

---

## 7. 기각한 대안

방언 상수의 모양에 관한 대안(집합 값 `thinkingDialect` 등)은 [`anthropic-thinking.md`](anthropic-thinking.md), 설정 선언에 관한
대안은 [`configuration-surface.md`](configuration-surface.md) 에 있다.

| 대안 | 기각 이유 |
|---|---|
| provider 요청 빌더 안에서 모델 이름으로 분기(`model.startsWith(...)`) | §2.1. 다른 provider 가 닿지 못하는 곳에 모델 지식을 두고, 이름을 바꾸는 게이트웨이에서 틀리며, 계열마다 복사본이 생긴다 |
| 능력을 `ModelContextWindowRegistry` 에 합친다 | §2.3. 두 소비자가 쓰지 않는 타입에 의존하고, 바뀌는 이유가 다른 두 사실이 섞이며, 공개 SPI 를 깨거나 넓혀야 하고, 타입 이름이 틀린다 |
| total `resolve` 하나만 둔다 | 빗나갈 때 `unknown()` 을 돌려줄 의무가 모든 외부 구현자에게 가고, 잊으면 fail-open 이 조용히 fail-closed 가 된다 |
| `Optional` 만 두고 호출자마다 `.orElse(...)` | fail-open 상수가 호출 지점마다 흩어져 다음 호출자가 제한적인 기본값을 쓸 수 있다. 규칙은 테스트할 수 있는 한 곳에 있어야 한다 |
| 능력을 `LlmModel` 의 필드로 싣는다 | `LlmModel` 은 에이전트 정의에서 오는 사용자 설정이고 능력은 벤더 API 에 대한 사실이다. `LlmModel` 을 만드는 모든 자리가 그것을 알아야 한다 |
| 이름 `ModelCapabilityResolver` | 같은 역할의 형제 두 조회가 `Registry` · `Table` 이다. 셋째 이름은 알아보기 어렵다 |
| 타입을 `at.aimon.core.llm` 에 평평하게 둔다 | 그 패키지는 이미 타입이 많고 `cost/` · `retry/` · `streaming/` 같은 형제가 하위 패키지다. ArchUnit 허용 목록이 `at.aimon.core.llm..` 이라 하위 패키지는 비용이 없다 |
| Anthropic 전용 레지스트리 | §2.2. 게이트웨이 배포가 모델마다 두 모양 · 두 키로 선언해야 하고, 두 표가 어긋날 수 있으며, 공유 설정 키가 둘로 쪼개진다 |
| `unknown()` 을 fail-closed 로 — 모르는 모델에 샘플링 생략 | 샘플링 축만 보면 싼 실수지만 `unknown()` 은 두 provider 가 공유하는 상수다. 뒤집으면 기술되지 않은 모든 OpenAI 모델에서 호출자가 설정한 `temperature` 가 빠지고, 중립 기술자의 그 값은 "이 모델은 샘플링을 거부한다" 로 읽힌다 |
| 네 번째 플래그의 이름을 `usesResponsesApi` 로 | 중립 타입에 한 벤더의 엔드포인트 이름을 넣는다. 중립 사실에서 엔드포인트로의 추론은 벤더 모듈의 일이다 |
| effort rung 만 쓰는 벤더용 방언 상수(`EFFORT_ONLY`) | `supportsReasoningEffort()` 가 이미 답하는 질문이다. 같은 사실을 말하는 둘째 방법은 둘이 어긋나도 보이지 않는다 |
| 나중에 등록한 prefix 가 exact 행을 이기도록 조회를 바꾼다 | o-series exact 행 설계를 일부러 깨고, 설정 선언이 이기는 근거인 "exact 가 모든 prefix 를 이긴다" 를 무너뜨린다 |
| o-series prefix 행 셋을 모두 trace 왕복 `true` 로 | 호출하지 않은 `o1-pro` · `o4-mini-deep-research` 를 이름 prefix 를 공유하는 형제의 측정으로 다른 엔드포인트에 보낸다. `*-deep-research` 는 다른 제품 모양이라 같은 prefix 가 말하는 것이 더 적다 |
| 보이는 이름이 모두 측정된 `o3` prefix 만 켜고 나머지는 exact | "모두 측정됨" 은 한 키가 하루에 본 이름에 대한 진술이지 prefix 에 대한 진술이 아니다 — `o3` 는 앞으로의 모든 `o3*` 이름에 걸린다. 한 계열을 두 방식으로 읽게 된다 |
| 더 좁은 prefix — `o1-pro`(`false`)를 `o1`(`true`) 앞에 | 알려진 미측정 이름은 막지만 앞으로의 모든 `o1-*` 이름은 여전히 보낸다. prefix 는 "정확히 이 이름들" 을 말할 수 없고, 그것이 exact 항목의 일이다 |
| 별칭만 exact 행으로 두고 날짜 스냅샷은 prefix 에 | 같은 가중치의 두 이름이 다르게 동작한다. 스냅샷 고정은 흔한 운영 방식이라 함정을 싣는 것이다 |
| o-series 측정을 기록만 하고 행을 바꾸지 않는다 | 대조군까지 통과한 측정을 쓰지 않으면 그 계열은 도구 호출 사이에 추론을 버리는 비용을 기록된 이유 없이 계속 낸다 |
| 날짜 붙은 `claude-*-4-5` 이름 셋만 exact 행으로 | 목록에 없지만 호출되는 날짜 없는 별칭 — 배포가 가장 쓰기 쉬운 이름 — 이 `UNKNOWN` 으로 남는다. prefix 셋이 여섯 이름을 덮는다 |
| effort 단독 측정을 근거로 Claude 행에 `supportsReasoningEffort` 를 적는다 | 측정된 사실이지만 그 플래그를 읽는 Anthropic 경로가 없다. 소비자 없는 단언은 적지 않는다 |

---

## 8. 하지 말 것

- **provider 코드에 모델 이름 분기를 넣지 않는다.** 모델 지식은 `ModelCapabilityRegistry` 로만 들어온다
- **레지스트리 구현이 빗나감에 제한적인 기술자를 돌려주지 않는다.** `resolve` 를 override 해 `null` 을 돌려주지 않는다
- **`unknown()` 의 값과 빌더 시드 리터럴 중 한쪽만 바꾸지 않는다.** 값 하나를 바꾸면 기술되지 않은 모든 모델의 와이어가
  조용히 바뀐다. 그래서 플래그마다 테스트가 고정한다
- **행에 측정하지 않은 플래그를 적지 않는다.** 방언만 적는 `claude-*-4-5` · `-4-6` 행에 샘플링 억제를 더하지 않는다 —
  그 모델들은 샘플링 파라미터를 받는다
- **계열 prefix 로 측정한 세대 너머를 주장하지 않는다.** `claude-opus-4` 는 샘플링을 받는 4-5 · 4-6 을 삼킨다
- **형제가 측정되었다는 이유로 호출하지 않은 이름에 행을 주지 않는다** — `o1-pro`, `gpt-5.6-luna`, `gpt-5.6-sol`
- **더 구체적인 prefix 를 계열 prefix 뒤에 등록하지 않는다.** 계열 prefix 가 먼저 걸려 구체적인 행이 영원히 답하지 않는다
- **terra 행을 계열 행과 따로 적지 않는다.** ladder 말고는 계열의 네 플래그를 한 출처에서 받아야 한다 — trace 왕복이
  어긋나면 그 이름이 조용히 `/v1/responses` 를 떠난다
- **행 하나를 고칠 때 한 provider 만 보지 않는다.** 두 클라이언트가 같은 표를 읽는다
- **거부 메시지의 열거를 엔드포인트 없이 인용하거나 모델의 ladder 로 읽지 않는다.** 에러 문구를 명세로 읽지 않는다
- **미측정 칸을 이웃의 측정으로 채우지 않는다**
- **`thinkingDialect` 의 `UNKNOWN` · `EITHER` 를 요청 모양으로 쓰지 않는다.** 둘은 행의 값이다
- **`AnthropicConfig` 의 동등성에 레지스트리를 넣지 않는다.** 같은 값의 두 설정이 서로 달라진다

---

## 9. 남은 것

- **`claude-mythos` 행이 한 prefix 로 문서상 서로 다른 두 방언 상태를 덮는다.** 벤더 표는 Mythos 5.1 · 5 를 adaptive
  전용으로, Mythos Preview 를 두 모양으로 싣는다. 그 prefix 로 시작하는 모델이 닿으면 이름별로 프로브해 prefix 를 쪼갤지
  정한다 — [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-11
- **같은 `claude-mythos` 행의 샘플링 사실도 측정되지 않았다.** L-11 은 방언만 다룬다(백로그 미등록). 도달 가능한 모델이
  없어 지금 잴 수 없다
- **두 모양을 받으면서 budgeted 쪽을 선호하는 모델이 나오면 `EITHER` 는 그 선호를 적을 자리가 없다** —
  [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-10
- **Chat Completions 로 강제한 `gpt-5.6-terra` 의 `reasoning_effort: none` 칸** —
  [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-2. `responsesApiEnabled` 에 설정 표면이
  생기는 순간 그 조합이 프로그램 전용이 아니게 되므로, L-2 를 맡는 쪽이 그 칸을 재거나 재지 않기로 한 것을 적는다
- **나머지 미측정은 항목이 아니라 §6.4 의 빈 칸이다**

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `llm/capability/ModelCapabilities.java` | 일곱 필드의 뜻, `unknown()` 값과 그 도출, 빌더 시드 리터럴, 집합 ladder 의 거절 규칙 |
| `llm/capability/ModelCapabilityRegistry.java` | `capabilitiesOf` / `resolve`, `EMPTY`, `resolve` 의 never-null 계약 |
| `llm/capability/InMemoryModelCapabilityRegistry.java` | 조회 순서와 대소문자 무시, `builderWithDefaults()` 의 행 · 등록 순서 · 행 옆 측정 주석, exact 행이 prefix override 를 가리는 동작, `withDefaultsExtendedBy` |
| `llm/capability/ThinkingDialect.java` | 네 상수, 행 전용 값의 불변식 |
| `llm/capability/package-info.java` | fail-open · overridable 두 규칙과 그 대가 |
| `llm/ModelContextWindowRegistry.java`, `llm/cost/ModelPriceTable.java` | 형제 레지스트리와 total 뷰 유무 |
| `llm/capability/ModelCapabilitiesTest.java`, `llm/capability/InMemoryModelCapabilityRegistryTest.java` (test) | 플래그별 `unknown()` 고정, 빌더 기본값 = `unknown()`, 조회 · 제자리 교체 · exact 가림 |
| `aimon-llm-openai/…/OpenAIConfig.java`, `aimon-llm-anthropic/…/AnthropicConfig.java` | `modelCapabilityRegistry` 주입과 기본값, Anthropic 동등성에서 레지스트리를 빼는 이유 |
| `aimon-llm-openai/…/OpenAILlmClient.java`, `aimon-llm-anthropic/…/AnthropicLlmClient.java` | `capabilitiesFor` 방어 조회와 두 보고 signature |
| `aimon-llm-openai/…/OpenAiRequestParameters.java`, `aimon-llm-openai/…/OpenAIResponsesRequestFactory.java`, `aimon-llm-anthropic/…/AnthropicThinkingResolver.java` | 각 플래그를 읽는 곳 |

`llm/` 경로는 `modules/aimon-core/src/main/java/at/aimon/core/`(테스트는 `src/test/java/`) 기준이다. provider 쪽은
`modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/`,
`modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/` 기준이다.

---

## 관련 문서

- [`request-parameters.md`](request-parameters.md) — 능력을 읽어 요청을 만드는 규칙: 샘플링 생략, effort ladder 사용, 도구 규칙, divergence 보고
- [`configuration-surface.md`](configuration-surface.md) — 능력 선언 키, 선언이 행 전체를 대체하는 규칙, 거절 목록
- [`anthropic-thinking.md`](anthropic-thinking.md) — `thinkingDialect` 를 thinking mode 와 함께 요청으로 해석하는 표
- [`openai-responses-path.md`](openai-responses-path.md) — trace 왕복에 따른 엔드포인트 라우팅, reasoning summary 게이트, 경로 전제의 측정
- [`reasoning-traces.md`](reasoning-traces.md) — trace 캡처와 재전송
- [`model-name-resolution.md`](model-name-resolution.md) — 조회 키가 되는 모델 이름이 정해지는 곳
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — L-2 · L-10 · L-11
- [`../../migration/rename-maps.md`](../../migration/rename-maps.md) — `ModelCapabilities.lowestReasoningEffort()` → `acceptedReasoningEfforts()`
- [`../../features/llm/llm-provider-development-guide.md`](../../features/llm/llm-provider-development-guide.md) — `LlmClient` SPI 의 정본
