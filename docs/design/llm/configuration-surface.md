# LLM 설정 표면 (LLM Configuration Surface)

> Status: **IMPLEMENTED** — LLM 노브는 세 설정 표면(CLI yaml · 스타터 프로퍼티 · 에이전트 frontmatter)에서
> 닿는다. 들어가 있는 것: 네임스페이스 규칙과 그 규칙으로 배치한 키, 두 표면이 함께 쓰는 능력 선언 번역기,
> 표면별 바인딩 타입과 검증, 두 표면의 전달 코드를 한 계약으로 확인하는 바인딩 왕복 가드. 남은 것은 §11.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm.capability`(`ModelCapabilityDeclaration`,
> `InMemoryModelCapabilityRegistry.withDefaultsExtendedBy`), `at.aimon.core.agent.definition.parser`
> (`MarkdownAgentDefinitionParser`), `at.aimon.core.agent`(`AgentDefinitionVersion`) ·
> `aimon-cli` — `at.aimon.cli.config`(`CliConfigLoader`, `LlmProviderConfig`, `ModelCapabilityConfig`,
> `AnthropicProviderConfig`, `OpenAiProviderConfig`), `at.aimon.cli.factory`(`LlmClientFactory`) ·
> `aimon-spring-boot-starter` — `at.aimon.spring.boot.autoconfigure`(`AimonProperties`,
> `AimonLlmAutoConfiguration`) · `aimon-llm-capability-testkit`(미배포).

---

## 1. 문제와 범위

LLM 클라이언트를 움직이는 노브는 자바 타입에 있다 — `OpenAIConfig` · `AnthropicConfig` 의 빌더,
`ModelCapabilityRegistry`, 요청마다 오는 `LlmModel`. 설정만으로 조립한 배포는 자바를 쓰지 않으므로, 노브가 설정
표면에서 닿지 않으면 그 배포에게는 없는 노브다. 이 문서는 그 표면이 지켜야 하는 규칙을 정한다.

- **키가 어디에 사는가** — 공유 `aimon.llm.*` 인가 벤더 `aimon.llm.<provider>.*` 인가(§3)
- **모델별 사실을 설정에서 어떻게 선언하는가** — 내장 표와의 관계, 부분 선언, 거절(§4)
- **어떤 타입으로 바인딩하는가** — 벤더 모듈이 클래스패스에 보장되지 않는 표면이 있다(§5)
- **어떻게 검증하는가** — 규칙을 표면마다 복사하지 않으면서 운영자가 고칠 키를 부르는 방법(§6)
- **두 표면이 어긋나지 않음을 무엇이 붙드는가** — 키가 바인딩되고도 값이 전달되지 않는 결함(§8)

모델별 능력 선언 키가 있는 이유는 구체적이다. 게이트웨이가 모델을 다른 이름으로 노출하면 그 이름은 내장 표에
걸리지 않아 fail-open 값으로 해석되고, 모델이 받지 않는 파라미터가 요청에 실린다. 이것을 막는 레지스트리 주입은
프로그램 코드로만 닿으므로, 설정만으로 조립한 배포가 내장 표에 없는 이름을 서술할 자리가 필요하다. thinking 키도
같은 이유로 설정에 있다 — 설정만으로 조립한 배포도 thinking 방언과 예산을 고르고, 서명 오류가 날 때 저장된
thinking 블록 재전송을 끌 수 있어야 한다.

**하지 않는 것.**

- 필드와 노브의 **뜻**을 정의하지 않는다. `ModelCapabilities` 필드와 fail-open 정의는
  [`model-capabilities.md`](model-capabilities.md), effort ladder 와 샘플링 규칙은
  [`request-parameters.md`](request-parameters.md), thinking 모드 · 예산 · display 규칙은
  [`anthropic-thinking.md`](anthropic-thinking.md), `replayThinkingBlocks` 의 의미는
  [`reasoning-traces.md`](reasoning-traces.md), reasoning summary 요청은
  [`openai-responses-path.md`](openai-responses-path.md) 가 정본이다. 이 문서는 그 노브가 표면에 **어떻게 놓이는가**만 다룬다
- `${VAR}` 확장과 frontmatter 값 엄격화 일반은
  [`../integration/config-value-expansion-and-frontmatter-strictness.md`](../integration/config-value-expansion-and-frontmatter-strictness.md) 가 정본이다.
  설정 파일의 스칼라와 매핑 키는 바인딩 **전에** 확장된다
- 사용 방법과 전체 예시는 운영 가이드([`../../getting-started/aimon-core-integration-via-cli-reference.md`](../../getting-started/aimon-core-integration-via-cli-reference.md),
  [`../../getting-started/embedding-agent-in-application.md`](../../getting-started/embedding-agent-in-application.md))의 몫이다
- `responsesApiEnabled` 와 샘플링 파라미터는 아직 설정 표면이 없다 — 배치만 정해져 있다(§3.2, L-2)

---

## 2. 세 표면과 표기 — 잎 이름은 자바 필드 이름 그대로

### 2.1 표면

| 표면 | 표기 | 루트 | 바인더 | 바인딩 타입이 사는 곳 |
|---|---|---|---|---|
| CLI yaml | camelCase | `llm.*` | Jackson — `CliConfigLoader` 의 매퍼(`FAIL_ON_UNKNOWN_PROPERTIES` 켬, `ACCEPT_CASE_INSENSITIVE_ENUMS` 켬) | `at.aimon.cli.config` 의 POJO |
| 스타터 프로퍼티 | kebab-case | `aimon.llm.*` | Spring Boot relaxed binding | `AimonProperties.Llm` 과 그 중첩 클래스 |
| 에이전트 frontmatter | camelCase | `model.*` | `MarkdownAgentDefinitionParser.extractModel` | `LlmModel` |

두 표기는 섞이지 않는다. CLI 는 빈 이름을 그대로 쓰고(`@JsonProperty` 없음, 네이밍 전략 없음), 스타터는 Boot 의
kebab 규칙을 쓴다. 한 표면의 표기로 다른 표면을 쓰면 CLI 는 모르는 필드로 실패하고 스타터는 조용히 무시한다(§6.4).

### 2.2 잎 이름

**설정 잎 이름은 그 값이 들어갈 자바 필드의 이름을 그대로 옮긴다.** `supports` 접두어를 떼지 않고
(`supportsSamplingParameters`), vendor 블록 안에서도 `thinking` 접두어를 떼지 않는다(`llm.anthropic.thinkingMode`,
`mode` 가 아니다). `acceptedReasoningEfforts` 는 `ModelCapabilities` 필드, `ModelCapabilityDeclaration`, CLI 키,
스타터 키에서 같은 이름이다.

이유는 둘이다. 이 이름들은 javadoc 과 설계 문서 전체가 쓰는 어휘이고, 같은 사실에 두 번째 어휘를 만들면 아무도
유지하지 않는 번역표가 생긴다. 그리고 "설정 표면은 내장 표의 어떤 행이든 표현할 수 있다"(§4.4)는 불변식이 글자
그대로의 대응으로 읽혀야 확인할 수 있다. vendor 블록의 잎을 줄이지 않는 것도 같은 규칙이다 — `AnthropicConfig`
javadoc 을 읽는 운영자가 도착하는 이름이 그 필드 이름이다.

### 2.3 설정의 결정 조각

결정이 곧 키의 모양이므로 모양만 보인다. 전체 예시는 운영 가이드에 있다.

```yaml
# CLI (aimon.yaml) — camelCase
llm:
  provider: anthropic
  reasoningEffort: medium                 # 공유 — 두 provider 분기가 모두 읽는다
  modelCapabilities:                      # 공유 — 맵 키가 모델 이름
    prod-assistant:
      supportsSamplingParameters: false
      acceptedReasoningEfforts: [none, low, medium, high]
      thinkingDialect: adaptive
  anthropic:                              # 벤더 — anthropic 분기만 읽는다
    thinkingMode: auto
    thinkingDisplay: summarized
    replayThinkingBlocks: true
  openai:                                 # 벤더 — openai 분기만 읽는다 (provider 가 anthropic 이면 거절된다, §6.3)
    reasoningSummary: auto
```

```yaml
# 스타터 (application.yml) — kebab-case
aimon:
  llm:
    provider: anthropic
    reasoning-effort: medium
    model-capabilities:
      prod-assistant:
        supports-sampling-parameters: false
        accepted-reasoning-efforts: none,low,medium,high
      "[prod-assistant.v2]":              # 점이 있는 이름은 대괄호가 필수다 (§6.5)
        thinking-dialect: adaptive
    anthropic:
      thinking-mode: "off"                # 인용한다 — 인용하지 않은 off 는 YAML 불리언이다 (§6.6)
```

```yaml
# 에이전트 정의 frontmatter
model:
  reasoningEffort: high
```

CLI 의 `default-config.yaml` 은 `reasoningEffort` 와 `acceptedReasoningEfforts` 를 **주석으로만** 보인다. 주석을 푼
기본값은 모든 CLI 요청을 바꾸기 때문이다.

---

## 3. 네임스페이스 — 이름이나 뜻이 벤더의 것일 때만 벤더 서브트리로

### 3.1 규칙

> **두 경우에 `aimon.llm.<provider>.*` 로 내린다 — 키가 벤더 개념을 이름에 담고 있거나, 같은 키가 벤더마다 다른
> 것을 뜻하거나. 오늘 소비자가 하나뿐이라는 사실은 쪼개는 이유가 아니다.**

두 긍정 검사(이름 · 뜻) 중 하나라도 "예" 면 벤더, 둘 다 "아니오" 면 공유다. 세 가지를 함께 정한다.

- **비-기준의 이유.** 중립 이름의 키를 오늘 소비자가 하나라는 이유로 벤더 서브트리에 내리면, 두 번째 소비자가
  생기는 날 깨지는 키 이동이 필요하다. 반대로 벤더 이름의 키는 다른 벤더가 비슷한 노브를 가져도 옮길 필요가
  없다 — 그 벤더의 노브는 그 벤더의 enum 이고 옆에 형제 키로 생긴다. 한 공유 키가 두 벤더 enum 을 바인딩할
  수는 없으므로, 비싼 이동은 벤더 이름을 공유 네임스페이스에 둔 쪽에서 난다
- **공유 키가 정직한 조건.** 공유 키는 두 provider 분기가 **모두 실제로 읽을 때만** 정직하다. 한 분기만 읽는 공유
  키는 절반의 배포에게 "설정했는데 읽히지 않는" 키다. 그래서 공유 키는 두 분기에서 적용된다(§3.2 의 두 공유 가족)
- **기준의 주어는 키 가족이지 잎이 아니다.** 기준은 네임스페이스가 어디서 시작하는지를 고른다. 이미 공유로 정해진
  맵 안의 잎에 기준을 다시 적용하면 모델 하나를 서술하는 레코드가 두 네임스페이스로 쪼개지고, 잎 이름은 자바 필드를
  그대로 옮긴 것(§2.2)이라 벤더 개념을 담는지 여부를 아무도 고르지 않았다. `thinkingDialect` 와
  `supportsReasoningSummary` 가 공유 맵에 사는 이유다

벤더 개념을 이름에 담은 키는 공유 네임스페이스에 두지 않는다. 기준이 처음으로 분할을 내야 할 키에서 공유 이름을
택하면, 그 기준은 분할을 한 번도 정당화하지 못한 규칙이 되어 반증할 수 없게 된다.

벤더 서브트리는 벤더마다 **중첩 블록 하나**이고 그 안의 잎은 벤더 config 의 필드 이름을 평평하게 옮긴다. 블록이어야
"읽히지 않을 벤더 블록" 을 한 단위로 거절할 수 있다(§6.3). 서브트리를 두는 결정은 그 거절과 함께 온다 — 벤더
서브트리는 설정했는데 읽히지 않는 블록을 만들 수 있고, 서브트리 이름이 벤더를 말하므로 누구의 블록인지는 모호하지
않다.

이 규칙과 판정이 백로그 B-21([`../../backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md))의
결정 기록이다. 스타터 프로퍼티 트리 전체는 [`../integration/spring-boot-starter.md`](../integration/spring-boot-starter.md) 에 있다.

### 3.2 현재 키의 판정 표

| 키 가족 | CLI | 스타터 | frontmatter | 판정 | 이유 |
|---|---|---|---|---|---|
| 모델 능력 선언(잎 여덟) | `llm.modelCapabilities.<model>.*` | `aimon.llm.model-capabilities.<model>.*` | — | **공유** | 이름이 provider 중립 SPI(`at.aimon.core.llm.capability`)의 것이고, "이 모델의 요청 표면이 무엇을 받는가" 는 벤더가 바뀌어도 같은 물음이다. OpenAI · Anthropic 두 클라이언트가 같은 번역기로 읽는다. `thinkingDialect` · `supportsReasoningSummary` 도 이 가족의 잎이다 |
| `reasoningEffort` | `llm.reasoningEffort` | `aimon.llm.reasoning-effort` | `model.reasoningEffort` | **공유** | 이름이 중립 SPI 타입 `ReasoningEffort` 의 것이고, OpenAI 의 rung 과 Anthropic 의 예산 · effort 는 한 의도를 두 가지로 옮긴 것이다. 두 분기가 모두 적용한다 |
| `thinkingMode` | `llm.anthropic.thinkingMode` | `aimon.llm.anthropic.thinking-mode` | — | **벤더** | 이름 — "thinking" 은 Anthropic 의 현상 어휘이고(이 저장소의 중립 명사는 `ReasoningEffort` · `ReasoningTrace`), 값 `EXTENDED` · `ADAPTIVE` 는 Anthropic 와이어 모양의 이름이다 |
| `thinkingBudgetTokens` | `llm.anthropic.thinkingBudgetTokens` | `aimon.llm.anthropic.thinking-budget-tokens` | — | **벤더** | 이름 — `budget_tokens` 는 Anthropic 요청 본문의 필드다. 뜻 — 다른 벤더는 생각의 양을 토큰 수로 말하지 않는다 |
| `replayThinkingBlocks` | `llm.anthropic.replayThinkingBlocks` | `aimon.llm.anthropic.replay-thinking-blocks` | — | **벤더** | 이름 — thinking 블록은 Anthropic 의 서명된 콘텐츠 블록이다 |
| `thinkingDisplay` | `llm.anthropic.thinkingDisplay` | `aimon.llm.anthropic.thinking-display` | — | **벤더** | 이름 — "thinking" 과 Anthropic `thinking` 객체 안의 필드명 `display` |
| `reasoningSummary` | `llm.openai.reasoningSummary` | `aimon.llm.openai.reasoning-summary` | — | **벤더** | 이름 — `reasoning.summary` 는 OpenAI 요청 본문의 경로이고, 값 `auto` · `concise` · `detailed` 는 OpenAI 의 어휘다 |
| *(표면 없음)* `responsesApiEnabled` | `llm.openai.responsesApiEnabled` | `aimon.llm.openai.responses-api-enabled` | — | **벤더** | 이름이 OpenAI 엔드포인트다. 표면을 주는 일은 L-2 |
| *(표면 없음)* `temperature` · `topP` · 두 penalty | — | `aimon.llm.<provider>.*` | — | **벤더** | 뜻이 벤더마다 다르다(유효 범위, 무시되는 파라미터). 표면을 주는 일은 L-2 |

판정이 네임스페이스로 갈라 놓는 쌍이 하나 있다.

```
aimon.llm.model-capabilities.<model>.supports-reasoning-trace-round-trip   # 모델에 대한 사실 — 두 클라이언트가 읽는다
aimon.llm.anthropic.replay-thinking-blocks                                 # 한 벤더의 서명 검증 실패에서 빠져나오는 스위치
```

앞의 것은 모델이 무엇인가를, 뒤의 것은 이 배포가 무엇을 원하는가를 말한다. 같은 이유로 `thinking*` 키와 `thinkingDialect`
능력 키는 다른 가족이다 — 앞의 것은 벤더 서브트리에, 뒤의 것은 다섯 플래그의 검증 · 우선순위 · 대소문자 규칙을 물려받는
공유 맵에 산다. 스위치의 의미는 [`reasoning-traces.md`](reasoning-traces.md) 가 가진다.

추론 스트림을 켜는 중립 우산 키는 두지 않고, 스트림 노브는 frontmatter 에 두지 않는다 — 그 판단은
[`streaming.md`](streaming.md) 가 가진다.

---

## 4. 능력 선언 — 모델 이름 하나를 지목하는, 그 이름의 행 전체

### 4.1 맵 키는 모델 이름이고 exact 전용이다

선언은 **모델 이름을 맵 키로 하는 블록**이다. 리스트가 아닌 이유는 셋이다.

1. **스타터의 문서 가드가 맵만 걷는다.** `AimonDocumentedPropertiesTest` 의 트리 walker 는 `Map` 과 `AimonProperties`
   중첩 클래스로 내려가지만 `List<X>` 는 잎으로 기록하고 멈춘다. 리스트로 만들면 가이드에 적은 선언 키가 그 가드에서
   미지의 키가 되거나 검사 밖으로 빠진다. 맵은 와일드카드 세그먼트(`aimon.llm.model-capabilities.*.<leaf>`)로 기록되어
   알려진 키가 된다
2. **키가 정체성이다.** 모델 이름은 조회 키이므로 맵 키로 두면 중복 선언이 표기법 차원에서 대부분 불가능하다.
   남는 것은 대소문자만 다른 중복이고 그것은 거절한다(§4.5)
3. **같은 모양의 선례가 있다** — `aimon.agents.<ref>`, `aimon.credentials.<profile>`

**prefix 는 넣지 않는다.**

- 내장 표의 prefix 우선순위는 등록 순서다([`model-capabilities.md`](model-capabilities.md)). 설정에 prefix 를 열면
  "yaml 두 줄의 순서를 바꾸면 동작이 바뀐다" 가 운영자 표면으로 새어 나온다. 길이 순으로 정렬해 흉내 내면 코어와
  다른 세 번째 매칭 규칙이 설정 표면에만 생긴다
- 내장 표 위에 등록한 새 prefix 는 내장 prefix **뒤에** 붙으므로 내장 prefix 가 먼저 걸리는 이름에서 영원히 발화하지
  않는다. 앞세우는 코어 API 를 열면 같은 prefix 의 제자리 교체가 지키던 보호가 깨진다
- 필요가 작다 — 게이트웨이의 배포 이름은 가족이 아니라 개별 이름이다

대가는 게이트웨이가 가족 전체를 개명하면 이름을 하나씩 적어야 한다는 것이다. 설정 쪽에 prefix 를 여는 일은
L-4 가 가진다.

### 4.2 부분 선언 — `unknown()` 위에 선언된 것만 얹는다

선언하지 않은 필드는 `ModelCapabilities.unknown()` 의 값을 갖는다. 설정의 한 항목은 `ModelCapabilities.builder()` 에
**선언된 세터만 부른 것**과 같다. 이유는 넷이다.

1. **두 번째 defaulting 규칙을 만들지 않는다.** `ModelCapabilities.Builder` 가 이미 `unknown()` 값으로 시작한다.
   설정 경로가 다른 규칙을 쓰면 "fail-open 은 타입의 성질이지 등록자가 매번 다시 도출하는 규칙이 아니다" 가 거짓이 된다
2. **모든 필드를 요구하면 운영자가 모르는 칸을 지어낸다.** 게이트웨이 운영자는 "temperature 가 400 을 낸다" 는
   알아도 trace 왕복 여부는 모른다. 강제로 채운 틀린 답 하나(`supportsReasoningTraceRoundTrip: true`)는
   `/v1/responses` 로 라우팅되어 Chat 전용 게이트웨이에서 404 를 만든다
3. **최소 선언이 안전한 쪽으로 떨어진다.** fail-open 값이 곧 선언하지 않았을 때의 동작이므로, 부분 선언은 선언한 그
   사실만 바꾼다
4. **필드가 늘어도 기존 설정이 깨지지 않는다**

대신 **아무것도 선언하지 않은 항목은 거절한다.** 그런 항목은 `unknown()` 을 등록하므로 적지 않은 것과 구별되지
않는데, 운영자는 그것이 무언가를 한다고 믿는다. 이 키가 존재하는 이유가 조용한 no-op 이 만든 400 이므로 표면이
같은 no-op 을 내놓지 않는다. 본문이 빈 항목(CLI yaml 에서 `prod-assistant:` 한 줄)도 같은 문장으로 거절한다 —
운영자에게 빈 본문과 빈 객체는 같은 것이다.

### 4.3 확장이고, 사용자가 이긴다 — 단 그 이름 하나만

선언은 내장 표를 **대체하지 않고 확장한다.** 번역기는 내장 표(`builderWithDefaults()`)에서 시작해 각 선언을 **exact**
로 등록하므로, 이미 있는 "exact 가 모든 prefix 를 이긴다" 규칙이 곧 "사용자가 이긴다" 를 뜻한다. 새 우선순위 규칙은 없다.
선언은 모델 하나를 이름으로 지목하지 가족을 덮지 않는다.

| 선언한 이름 | 결과 |
|---|---|
| 내장 표에 없는 이름(`prod-assistant`) | 사용자 항목이 답한다 |
| 내장 prefix 와 같은 문자열(`gpt-5`) | 정확히 그 이름만 사용자 항목이 답한다. `gpt-5-mini` 는 여전히 내장 prefix 행이 답한다 |
| 내장 prefix 에 걸리는 이름(`gpt-5-nano`) | 그 이름만 사용자 항목이 답한다 |

선언은 **그 이름에 대한 행 전체이지 행에 대한 패치가 아니다.** 내장 표에 행이 있는 이름을 선언하면서 그 행이 말하던
플래그를 적지 않으면, 적지 않은 플래그는 그 행의 값이 아니라 fail-open 값으로 떨어진다. 가장 비싼 귀결은 방언만
고치려는 선언이다 — `claude-*` 행은 샘플링 억제(`supportsSamplingParameters: false`)도 말하므로, 그 이름에
`thinkingDialect` 만 적으면 억제가 풀려 샘플링 파라미터가 400 을 내는 모델로 가고, 억제할 것이 없으므로 억제 경고도
나지 않는다. 그래서 운영 문서는 행 전체를 적는 모양을 보인다. `thinkingDialect: unknown` 도 유효한 진술이다 — "이
이름에 대해 내장 행의 방언으로 행동하지 말라". 선언이 내장 행을 가리면서 그 행의 플래그를 빠뜨렸을 때 기동 시 알리는
일반형은 L-8 이 가진다. 선언이 행을 대체한다는 사실은 [`model-capabilities.md`](model-capabilities.md) 가 한 문장으로
가리키고, 규칙은 여기가 정본이다.

### 4.4 설정 표면은 내장 표의 어떤 행이든 표현한다

그래서 `ModelCapabilities` 의 모든 사실이 선언 키로 노출된다 — 다섯 불리언, 방언, 그리고 ladder 를 쓰는 **두 키**.

- `lowestReasoningEffort` — "이 rung 에서 시작해 위로 구멍이 없다" 의 축약
- `acceptedReasoningEfforts` — 구멍이 있는 ladder(예: `[none, low, medium, high]`)

두 키는 같은 사실을 두 가지로 적으므로 **함께 적으면 거절한다.** 설정 파일은 두 키를 순서 없이 한꺼번에 내놓으므로 어느
쪽을 이기게 해도 운영자가 예측할 수 없는 답이다. 거절 문장은 두 키 중 무엇을 남길지 말한다. **빈 목록은 거절한다** —
받는 rung 이 없는 모델은 `supportsReasoningEffort: false` 로 적는다. **중복 rung 은 조용히 접는다** — 반복된 rung 이
하나와 다르게 읽힐 방법이 없다. 표면은 `List` 로 받고 선언으로 갈 때 집합이 된다.

설정 키 `lowestReasoningEffort` 는 SPI 가 floor 를 읽기용으로 내놓지 않게 된 뒤에도 **개명하지 않는다.** 스타터는 모르는
프로퍼티를 조용히 무시하므로(L-1), 개명하면 그 키를 선언한 모든 배포가 말없이 선언을 잃는다. 키는 남고 형제 키가 생긴다.

### 4.5 공유 번역기 한 곳 — `ModelCapabilityDeclaration` 과 `withDefaultsExtendedBy`

```
  CLI yaml                                   스타터 프로퍼티
  llm.modelCapabilities                      aimon.llm.model-capabilities
        │                                              │
  Map<String, ModelCapabilityConfig>        Map<String, AimonProperties.ModelCapabilityProperties>
        │  declarationOf(...)                          │  toDeclaration()
        └──────────────────────┬───────────────────────┘
                               ▼  Map<String, ModelCapabilityDeclaration>        ← 중립 선언 타입 (aimon-core)
         InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)             ← 규칙의 구현이 사는 한 곳
                               ▼
         OpenAIConfig.Builder / AnthropicConfig.Builder .modelCapabilityRegistry(...)
```

**`ModelCapabilityDeclaration` 의 계약.**

- 필드는 전부 박싱 타입이고 **`null` 이 "선언되지 않음"** 이다. 부분 선언을 표현하는 유일한 방법이므로, 빌더 세터는
  `null` 을 받는다 — 이 저장소의 `requireNonNull` 관례에 대한 의도된 예외이고 이유가 타입 javadoc 에 있다
- 모델 이름을 담지 않는다. 이름은 두 표면에서 맵 키이고, 한 사실이 두 자리에 있으면 어긋난다
- `build()` 는 여덟 필드가 모두 미선언인 선언과 두 ladder 키를 함께 적은 선언을 거절한다
- `capabilities()` 는 선언된 필드만 `ModelCapabilities.builder()` 에 적용한 결과를 한 번 만들어 들고 있다
- `equals` · `hashCode` · `toString` 은 여덟 필드 전부를 대상으로 한다(파생된 `capabilities` 는 제외) — §8 의 가드가 여기에 기댄다

**`withDefaultsExtendedBy` 가 거절하는 것.**

| 입력 | 거절 이유 |
|---|---|
| 빈 · 공백 이름 | 조회될 수 없는 항목이다 |
| 자기 `trim()` 과 다른 이름 | 앞뒤 공백이 붙은 이름은 절대 매치되지 않는 조용한 no-op 이다 |
| 대소문자를 접으면 같은 두 이름 | 레지스트리는 조회에서 대소문자를 접으므로 둘 다 바인딩된 뒤 뒤엣것이 조용히 이긴다 |
| 값이 `null` 인 항목(본문이 빈 항목) | 선언하지 않은 항목과 같은 문장으로 거절한다. 표면이 아니라 여기서 잡으므로 결과가 바인더에 달리지 않는다 |

거절은 레지스트리를 돌려주기 전에 일어나므로 **절반만 적용된 능력 표는 만들어지지 않는다.** 두 벤더 분기가 모두 이
번역기로 선언을 읽으므로 어느 벤더 분기도 선언을 거절하지 않는다.

**`aimon-core` 에 두는 이유.** 두 표면이 이미 코어에 의존한다. 중앙화하는 규칙이 `ModelCapabilities` 자신의
defaulting 규칙이므로 그 기본값을 정의하는 빌더 옆에 두어야 어긋나지 않는다. `aimon-bootstrap` 의 `*Spec` 은 스택
조립을 서술하는데 `LlmClient` 는 두 표면 모두에서 스택 **밖에서** 만들어진다. 그리고 중립 타입이 코어에 있어야
provider 모듈의 테스트가 설정 경로가 실제로 쓰는 번역기를 통과한 레지스트리 위에서 요청을 단언할 수 있다.

**"한 곳" 은 구현에 대한 주장이지 호출 지점이 아니다.** 두 표면은 각자 자기 키 경로를 메시지에 실어야 하므로 실패
지점은 둘이지만, 판정은 복사하지 않는다 — 두 표면이 같은 구현을 부르고 예외를 자기 표면의 것으로 다시 던진다(§6.1).
선언 규칙이 바인더 **바깥**에 있다는 것도 같은 결정의 일부다. 바인더 동작은 표면마다 다르고 버전마다 달라질 수 있는데,
규칙이 표면마다 복사되어 있으면 한 표면의 바인더 차이가 그 표면의 검증만 조용히 틀리게 만든다.

---

## 5. 바인딩 타입 — 모듈이 보장되는 곳은 enum, 아닌 곳은 `String`

### 5.1 표면별 타입

| 키 | CLI 필드 타입 | 스타터 필드 타입 |
|---|---|---|
| `modelCapabilities` 의 다섯 불리언 · `supportsReasoningSummary` | `Boolean` | `Boolean` |
| `lowestReasoningEffort` · `acceptedReasoningEfforts` | `ReasoningEffort` · `List<ReasoningEffort>` | 같음 |
| `thinkingDialect` | `ThinkingDialect` | `ThinkingDialect` |
| `reasoningEffort` | `ReasoningEffort` | `ReasoningEffort` |
| `thinkingMode` | `AnthropicThinkingMode` (전용 deserializer, §6.6) | `String` — 가드된 슬라이스 안에서 enum 으로 fold |
| `thinkingDisplay` | `AnthropicThinkingDisplay` | `String` — 같음 |
| `reasoningSummary` | `OpenAiReasoningSummary` | `String` — 같음 |
| `thinkingBudgetTokens` · `replayThinkingBlocks` | `Integer` · `Boolean` | 같음 |

규칙은 하나다.

- **코어 enum 은 두 표면 모두 enum 으로 바인딩한다.** `ReasoningEffort` · `ThinkingDialect` 는 스타터의 `api` 의존인
  `aimon-core` 에 있어 언제나 로드된다. enum 이면 설정 프로세서가 메타데이터 `type` 에 클래스를 기록해 IDE 가 상수를
  제안하고, relaxed binding 이 대소문자를 접는다. CLI 는 매퍼의 `ACCEPT_CASE_INSENSITIVE_ENUMS` 가 대소문자를 접는다
- **벤더 enum 은 CLI 에서만 enum 이다.** CLI 는 두 벤더 모듈을 `implementation` 으로 가지므로 모든 CLI 배포에 벤더
  enum 이 있다. 스타터는 벤더 모듈을 `compileOnly` 로 가지므로 `String` 으로 받아 그 벤더의 `@ConditionalOnClass`
  슬라이스 안에서 `values()` 위로 대소문자 무시 fold 한다. 두 표면의 fold 가 모두 enum 의 `values()` 를 돌기 때문에
  상수가 한쪽에만 조용히 빠질 수 없다

모든 필드는 박싱 타입이고 **`null` 은 "적지 않음"** 이다. 이것이 세 일을 한다 — 적지 않은 키의 세터를 부르지 않으므로
벤더 config 의 기본값이 살아남아 아무것도 적지 않은 배포의 요청이 그대로이고, 블록의 `isEmpty()` 가 §6.3 의 거절에
답하며, 원시 `boolean` 이었다면 "적지 않음" 과 "`true` 로 적음" 을 구분하지 못해 블록이 없는 배포도 채워진 것처럼 보인다.

### 5.2 `AimonProperties` 시그니처 불변식

**`AimonProperties` 와 그 중첩 클래스의 어떤 메서드 시그니처도 `compileOnly` 모듈의 타입을 싣지 않는다.** 시그니처의
타입은 `api` 의존(`aimon-core`, `aimon-bootstrap`, `aimon-session-routing`)이나 스타터 자신에서 온다.

메서드 반사는 시그니처를 해석하면서 거기 적힌 클래스를 로드하므로, 벤더 타입 접근자를 가진 프로퍼티 클래스는 그 벤더
모듈이 없는 배포에서 `NoClassDefFoundError` 를 낸다. 스타터 바인더는 그 서브트리에 값이 쓰이는 순간에만 그 반사에
닿고, 실패가 `Error` 라서 바인딩 예외로 포장되지도 않는다 — 가이드의 yaml 을 복사하고 의존성을 빠뜨린 운영자가 가장
그럴듯한 도착자다. 필드를 `null` 로 검사하는 것은 클래스를 로드하지 않는다(2026-09-09 실측).

불변식은 인스턴스가 아니라 규칙으로 붙들린다 — `AimonAutoConfigurationTest` 가 `AimonProperties` 와 모든 중첩 클래스의
선언 메서드를 반사로 걸어 시그니처 타입을 패키지 허용 목록(`java.` · `at.aimon.core.` · `at.aimon.bootstrap.` ·
`at.aimon.session.routing.` · `at.aimon.spring.boot.`)과 대조하고, 두 벤더 모듈이 사는 `at.aimon.core.llms.` 는 명시적으로
거부한다. 허용 목록 방식이라 다른 `compileOnly` 가족(Quartz, actuator)도 같은 단언이 덮는다.

### 5.3 중첩 클래스 배치

- **벤더 타입을 메서드 서술자에 가진 헬퍼는 그 벤더의 가드된 중첩 설정 클래스 안에 둔다.** `OpenAIConfig openAiConfig(...)`
  는 `OpenAiConfiguration` 안에, `AnthropicConfig anthropicConfig(...)` 와 벤더 enum fold 는 `AnthropicConfiguration` 안에
  있다. 스프링은 설정 클래스를 후처리하면서 `getDeclaredMethods()` 를 부르고, 그 호출이 반환 · 파라미터 타입을 로드하므로
  바깥 `AimonLlmAutoConfiguration` 에 두면 한 벤더 모듈만 가진 배포가 기동하지 못한다. 중첩 클래스는
  `@ConditionalOnClass` 가 바이트코드에서 읽혀 로드 없이 건너뛰어진다
- **코어 타입만 반환하거나 스타터 타입만 읽는 헬퍼는 바깥에 둬도 안전하다.** `AimonProperties.modelCapabilityRegistry(...)`
  는 코어 타입을 반환한다. `refuseAnthropicBlock` · `refuseOpenAiBlock` 은 서술자에 스타터 타입만 있고 본문은 `isEmpty()` 로
  필드의 `null` 만 검사한다 — 그리고 **바깥에 있어야 한다.** 그 거절이 필요한 분기가 바로 반대쪽 벤더 모듈이 없는 분기다
- **`ModelCapabilityProperties` 와 vendor 블록 클래스는 `AimonProperties` 의 중첩 클래스여야 한다.** 문서 가드의 walker 는
  이름이 `AimonProperties$` 로 시작하는 타입으로만 내려간다
- 구현 테스트가 조립된 config 를 확인해야 할 때는 표면의 조립 메서드를 package-private 으로 둔다
  (`LlmClientFactory.openAiConfig` · `anthropicConfig` · `declarationOf`, 스타터 `ModelCapabilityProperties.toDeclaration`).
  배포 모듈의 공개 표면을 테스트 편의로 넓히지 않는다

### 5.4 메타데이터 힌트

enum 선택자는 타입이 값을 싣는다. **`String` 선택자만 손으로 쓴 값 힌트를 가진다** — `aimon.llm.provider`(서드파티가
값을 기여할 수 있게)와 벤더 키 셋 `aimon.llm.anthropic.thinking-mode` · `aimon.llm.anthropic.thinking-display` ·
`aimon.llm.openai.reasoning-summary`(시그니처에 `compileOnly` 타입을 싣지 않으려고). `AimonConfigurationMetadataTest` 가 힌트
블록의 집합을 정확히 이 넷으로 붙들고, 벤더 키의 힌트 값은 enum 의 `values()` 에서 파생한 기대값과 대조한다. 그래서
enum 이 상수를 얻으면 힌트가 따라오지 않은 채 초록일 수 없다.

---

## 6. 검증 — 규칙은 코어에, 키 경로는 표면에

### 6.1 core 예외를 키 경로와 함께 다시 던진다

표면은 코어 규칙을 **사전 검사로 다시 적지 않는다.** 사전 검사는 규칙의 두 번째 사본이고, 두 사본은 조용히 어긋난다.
대신 코어 호출을 **좁게** 감싸 `IllegalArgumentException` 을 자기 표면의 예외로 다시 던지면서, 운영자가 **고칠 키**를
메시지에 싣는다.

| 코어 호출 | CLI (`ConfigurationException`) | 스타터 (`IllegalStateException`) |
|---|---|---|
| 선언 하나의 `build()` | `llm.modelCapabilities.<model>` | `aimon.llm.model-capabilities.<model>` |
| `withDefaultsExtendedBy(...)` | `llm.modelCapabilities` | `aimon.llm.model-capabilities` |
| `AnthropicConfig.Builder.build()` | `llm.anthropic.thinkingBudgetTokens` | `aimon.llm.anthropic.thinking-budget-tokens` |

마지막 행이 블록이 아니라 예산 키를 부르는 이유: 이 경로에서 `build()` 가 던질 수 있는 예외 중 설정에서 도달하는 것은
둘 다 예산의 것이다(나머지는 앞에서 이름으로 거절되거나 설정할 수 없는 값의 것이다). 이 전제는 catch 옆 주석에 적혀
있고, 같은 빌드 경로에 설정 가능한 키가 새로 생기면 catch 를 가른다. 표면마다 메시지 문구가 다른 것은 옳다 — 각
메시지는 자기 표면의 키 경로를 가리켜야 한다.

예산과 모드의 규칙 자체(예산은 `EXTENDED` 전용, 1024 이상)는 [`anthropic-thinking.md`](anthropic-thinking.md) 가 정본이다.
표면이 더하는 것은 실패의 모양뿐이다 — `thinkingBudgetTokens` 를 `EXTENDED` 가 아닌 모드와 함께 적거나 **예산만 적으면**
(모드 기본값이 `OFF`) 기동이 그 키를 부르며 실패한다. 예산만 적는 것이 가장 그럴듯한 실수이고, 조용히 무시했다면
"설정했는데 읽히지 않는" 상태가 된다.

### 6.2 검증 시점

- **CLI** — 값 오류와 모르는 필드는 로드 시점에 Jackson 이, 의미 오류는 클라이언트 조립 시점에 `LlmClientFactory` 가 낸다
- **스타터 능력 선언** — `AimonProperties.afterPropertiesSet` 의 `validateLlm()` 이 번역기를 실제로 불러 보고 결과를 버린다.
  빈이 만들어지기 전에 프로퍼티 이름으로 실패시키기 위해서다. 효과를 내는 호출은 슬라이스에서 한 번 더 일어난다
- **스타터 vendor 키** — `afterPropertiesSet` 이 아니라 **가드된 슬라이스의 빈 생성 시점**에 검증한다. fold 는 `compileOnly`
  enum 을 이름으로 부르는데 `afterPropertiesSet` 은 모든 배포에서 도는 메서드이기 때문이다(§5.2). 실패가 일부 빈이 생긴 뒤에
  오는 비대칭은 받아들인다 — 여전히 요청을 서빙하기 전이고 메시지가 프로퍼티를 부르며, 같은 파일의
  `requireApiKey` · `requireModel` 이 같은 이유("답이 빈에 달려 있다")로 같은 자리에 있다

### 6.3 실행되는 분기만 읽히지 않을 벤더 블록을 거절한다

| 배포 | 채워진 블록 | 결과 |
|---|---|---|
| CLI `provider: openai` | `llm.anthropic` | `ConfigurationException` — `llm.anthropic` 과 `llm.provider` 를 부른다 |
| CLI `provider: anthropic` | `llm.openai` | `ConfigurationException` — `llm.openai` 와 `llm.provider` 를 부른다 |
| 스타터 `provider=openai` | `aimon.llm.anthropic` | `IllegalStateException` — 두 프로퍼티를 부른다 |
| 스타터 `provider=anthropic`(또는 미지정) | `aimon.llm.openai` | `IllegalStateException` — 두 프로퍼티를 부른다 |
| 스타터 `provider=openai`, Anthropic 모듈이 클래스패스에 없음 | `aimon.llm.anthropic` | 위와 같은 메시지. 스타터 vendor 키가 `String` 이어서 가능하다 — 벤더 enum 이었다면 `NoClassDefFoundError` |
| 스타터 `provider=none`, 또는 애플리케이션이 자기 `LlmClient` 빈을 준 배포 | 어느 것이든 | **읽지도 거절하지도 않는다** — L-3 |
| 어느 표면이든 | 빈 블록(`anthropic:` 만 적음) | 거절하지 않는다 |

거절은 **실제로 도는 분기 안에서만** 한다(`requireApiKey` 와 같은 자리). 분기 밖에서 검사하면 `provider=none` 이나 자기
`LlmClient` 빈을 쓰는 배포의 유효한 설정이 기동 실패가 된다. 그 두 모양에서 선언과 블록이 조용히 읽히지 않는 것은 L-3 이다.
능력 선언은 두 벤더 분기가 모두 읽으므로 이 거절의 대상이 아니다.

### 6.4 표면별 실패

| 실패 | CLI | 스타터 | frontmatter |
|---|---|---|---|
| **모르는 잎 이름** | `ConfigurationException("Invalid configuration structure in: <file>")`. 어느 키인지는 원인 예외에만 있다(L-5) | **조용하다.** `@ConfigurationProperties` 의 기본 `ignoreUnknownFields = true`. 잎이 하나도 바인딩되지 않은 맵 항목은 만들어지지도 않는다(L-1). IDE 가 메타데이터로 모르는 잎을 표시하는 것이 남는 완화책이다 | `model:` 아래 모르는 키는 읽지 않는다 |
| **enum 값 오류** | Jackson `InvalidFormatException` → 위와 같은 `ConfigurationException`(L-5) | 코어 enum 은 Boot 바인딩 실패가 프로퍼티와 변환을 부른다. vendor `String` 은 fold 가 프로퍼티와 허용 철자 전부를 부르는 `IllegalStateException` | `model.reasoningEffort` 는 키와 허용 값을 부르는 `AgentDefinitionParseException` |
| **의미 오류** — 아무것도 선언하지 않음 · 본문이 빔 · 빈/공백 이름 · 대소문자만 다른 중복 · 두 ladder 키 · 빈 ladder | 코어 거절을 키 경로와 재던짐(§6.1) | 같음, `afterPropertiesSet` 시점 | — |
| **예산 규칙 위반** | `llm.anthropic.thinkingBudgetTokens` 를 부르며 실패 | `aimon.llm.anthropic.thinking-budget-tokens` 를 부르며 실패 | — |
| **읽히지 않을 벤더 블록** | §6.3 | §6.3 | — |
| **선언은 맞는데 요청이 다른 이름을 부름** | 선언은 죽은 항목이 된다. 감지하지 않는다 | 같음 | — |

마지막 행을 감지하지 않는 이유: 한 배포는 여러 모델을 부를 수 있다(에이전트 정의의 `model.name`, 서브에이전트).
"선언된 이름 중 어느 것도 `llm.model` 과 같지 않다" 를 오류로 만들면 정당한 설정을 거절한다. 선언의 이름은 요청이 부르는
바로 그 이름이라는 규칙이 운영 문서에 적힌다.

### 6.5 스타터 맵 키 — 대괄호와 대소문자

Spring Boot 바인더는 맵 키의 대소문자를 **보존**하고, 대괄호 없이 점이 들어간 이름으로는 항목을 **만들지 않으며**, 잎이
하나도 바인딩되지 않은 항목도 만들지 않는다(Spring Boot 3.5.x, 2026-09-09 실측). 그래서:

- 점이 있는 모델 이름은 대괄호 표기(`"[name.with.dot]"`)가 **필수**다 — 대괄호가 없으면 항목이 조용히 사라진다
- 대소문자만 다른 두 키는 합쳐지지 않고 둘 다 도착하므로 **두 표면 모두에서** 번역기가 거절한다
- 레지스트리가 조회에서 대소문자를 접으므로 `Prod-Assistant` 로 선언하고 `prod-assistant` 로 불러도 맞는다

CLI 는 Jackson 이 맵 키를 글자 그대로 받으므로 대괄호가 필요 없다.

### 6.6 YAML 1.1 의 `off`

YAML 1.1 은 인용하지 않은 `off` 를 불리언으로 읽는다. `off` 는 `thinkingMode` 의 문서화된 값이자 기본값이라 이 충돌이
실제로 운영자에게 닿는다.

- **CLI** 는 `AnthropicProviderConfig.ThinkingModeDeserializer` 가 파서가 넘기는 **원래 스칼라**를 읽어 `off` 를 모드로
  되살린다. `no` · `false` 는 모드가 아니므로 여전히 거절되고, 거절은 매핑 오류 분류(`InvalidFormatException`)로 보고된다
- **스타터** 는 바인더가 불리언을 문자열 `"false"` 로 바꾼 뒤에야 fold 를 보므로 원래 스칼라가 없다. `"false"` 를 `OFF` 로
  짐작하지 않고 거절하며, 값이 `true`/`false` 이면 `thinking-mode: "off"` 로 인용하라는 문장을 덧붙인다

(jackson-dataformat-yaml 2.18.2 · snakeyaml 2.5, 2026-09-09 실측) 이 비대칭은 없애지 않고 문서화한다. 전용 deserializer 는 이
키 하나에만 있다 — `ReasoningEffort` · `ThinkingDialect` · `thinkingDisplay` · `reasoningSummary` 의 어떤 철자도 YAML 의 불리언 ·
null 리졸버와 부딪히지 않고, 대소문자는 매퍼 기능이 덮는다.

---

## 7. frontmatter `model.reasoningEffort`

에이전트 정의는 설정 표면이다. `MarkdownAgentDefinitionParser.extractModel` 은 `model.reasoningEffort` 를 대소문자 무시로
`ReasoningEffort` 에 fold 하고, 맞는 상수가 없으면 **키와 허용 값 전부를 부르는 `AgentDefinitionParseException` 으로
실패한다.** 잘못된 값을 기본값으로 대체하지 않는 것은 frontmatter 값 일반의 규칙이다
([`../integration/config-value-expansion-and-frontmatter-strictness.md`](../integration/config-value-expansion-and-frontmatter-strictness.md)).
에이전트 정의의 effort 와 클라이언트 설정의 effort 가 함께 있을 때 무엇이 이기는지는
[`request-parameters.md`](request-parameters.md) 가 정한다.

**`AgentDefinitionVersion.canonicalForm` 은 `model.reasoningEffort=` 줄을 싣는다.** 이 버전은 요청 시점보다 오래 사는 작업
(예약된 cron 이 다시 울릴 때 그 사이 수정된 정의로 다시 만들어진 런타임에 붙는 것)을 위한 변경 감지기이고, 정의가 바뀌었다고
**말할 수 있게** 하는 것이 전부다. effort 는 에이전트의 동작을 바꾸므로, 그 줄이 빠지면 effort 만 다른 두 정의가 같은 버전이
되어 바뀐 사실이 보고되지 않는다.

---

## 8. 바인딩 왕복 가드 — 계약 하나, 주체 둘

### 8.1 무엇을 보는가

바인딩 가드는 키 이름이 표면에 **있는지**가 아니라 값이 선언에 **도착하는지**를 확인한다. 새 키마다 손으로 써야 하는 코드는
표면에서 선언으로 옮기는 전달 한 줄이고, 그 줄이 빠지면 운영자가 쓸 수 있고 바인딩되고 메시지도 없는데 클라이언트가 읽는
기술자에는 실리지 않는 키가 생기기 때문이다.

운영자의 값이 지나는 사슬은 네 고리다.

```
   yaml / 프로퍼티 텍스트
        │  (1) 바인더      Jackson(CLI, 모르는 필드에 실패) / Boot relaxed binding(스타터, 무시)
        ▼
   ModelCapabilityConfig · AimonProperties.ModelCapabilityProperties      ← 표면 (키마다 bean 프로퍼티)
        │  (2) 전달        LlmClientFactory.declarationOf(...) / toDeclaration()   ← 손으로 쓴, 키마다 한 줄
        ▼
   ModelCapabilityDeclaration
        │  (3) 해석        ModelCapabilityDeclaration.capabilities()
        ▼
   ModelCapabilities → InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)
```

가드는 **고리 (2) 만 끝에서 끝까지** 돌린다 — 표면에 쓰고 선언에서 읽는다.

- **(1) 을 넣지 않는다.** 바인더까지 늘이면 합성한 값을 두 표기(yaml 시퀀스, 쉼표 목록)의 텍스트로 렌더해야 하고, 빨간
  결과가 "이름이 바인딩되지 않았다" 와 "값이 버려졌다" 사이에서 모호해진다. 렌더 규칙은 표면마다 진짜로 다른 부분이라 공유
  계약이 남는 것이 없다. 바인더 고리는 키마다 손으로 쓴 테스트가 덮고, 그 일반화는 L-14 다
- **(3) 을 넣지 않는다.** 레지스트리 의미가 바인딩 테스트에 끌려 들어오고, 두 표면이 닿을 수 있는 끝점이 다른 메서드라 한
  계약이 두 사슬을 시험하게 된다. 선언에서 단언해야 두 서브클래스가 같은 사슬을 시험한다. 고리 (3) 도 손으로 쓴 전달이고
  가드가 없다 — L-13

### 8.2 키와 값

- **키는 빌더에서 발견한다** — `ModelCapabilityDeclaration.Builder` 의 인자 하나짜리 public 메서드 중 `Builder` 를 반환하는
  것. 발견 결과가 비면 발견 자체가 실패한다
- **키마다 서로 다른 값 둘을 쓴다.** 값 하나는 키가 **도착했다**를 증명할 뿐이고, 전달이 표면을 무시하고 상수를 박아 넣었는데
  우연히 그 상수를 골랐다면 초록이다. 값 둘이어야 "옮겨졌다" 와 "상수다" 가 다른 관측이 된다
- **값을 합성할 수 없는 타입은 건너뛰지 않고 실패한다** — 키, 타입, 넓혀야 할 파일을 부른다. 서로 다른 값 둘을 낼 수 없는
  타입(상수 하나짜리 enum)도 이유를 말하며 실패한다. 건너뛰는 가드는 이 가드가 막으려는 결함을 다른 옷으로 입은 것이다
- **탈출구는 키를 끌 수 없다.** 표면이 합성 값을 정당하게 거절하는 키를 위해 서브클래스가 `valuesFor(key)` 로 값 쌍을 줄 수
  있다. 합성한 쌍이든 준 쌍이든 같은 검사를 받는다 — 정확히 둘, `null` 없음, 세터의 타입, 서로 다름, 선언이 받아들임,
  `equals` 가 구분함. 빈 목록은 건너뛰기가 아니라 실패다
- **표면에 쓸 때의 변환은 일방향이다.** 표면 프로퍼티 타입이 빌더와 다르면 선언된 제네릭 파라미터 타입을 읽어 쓰기 직전에만
  변환하고, 기대 선언은 **변환 전** 값으로 만든다. 변환이 기대값까지 만들 수 있으면 틀린 답을 초록으로 세탁할 수 있다.
  `List<E>` 세터에는 리스트로 쓴다

### 8.3 도착의 단언

**선언 전체의 `equals` 로 단언한다.** 한 단언이 셋을 말한다 — 키가 도착했다, **이** 값으로 도착했다, **다른 것은 도착하지
않았다.** 여덟 줄이 거의 같은 전달은 복붙 실수를 부르는데, 틀린 getter 를 읽은 쪽은 키가 비어서, getter 하나가 세터 둘을
먹인 쪽은 남는 필드 때문에 실패한다. 키별 `Optional` getter 를 읽지 않는 이유는 그 방식이 따로 낡을 수 있는 두 번째 이름
대응을 필요로 하고, "다른 것은 도착하지 않았다" 를 잃고, 타입이 약속하지 않은 명명 관례에 기대기 때문이다 — `equals` 는
타입이 약속한다.

귀결 하나를 분명히 한다. 키를 복사하지 않고 **기본값을 채우거나 파생하는 전달은 이 계약에서 실패한다.** 의도다. 표면이 그
규칙에서 벗어나야 한다면 그 이탈은 표면별 훅이 아니라 계약에 한 번, 보이게 적는다.

**`equals` 가 그 키를 보는지 먼저 확인한다.** 단언 전체가 `equals` 에 기대므로, 빌더에 필드가 생기고 `equals` 에서 빠지면
"옮겨졌다" 절반이 조용히 사라진다. 그래서 왕복 전에 두 값의 기대 선언이 서로 다른지 확인하고, 같으면 `equals` 를 원인으로
부르며 실패한다.

**한 번에 한 키만 쓴다.** 두 ladder 키는 함께 쓸 수 없으므로 "전부 한꺼번에" 는 표현되지 않는다. 그리고 키 하나만 쓴 상태에서
전달이 빠지면 빌더가 아무것도 받지 않아 `build()` 가 빈 선언을 **이름으로** 거절한다. 그 거절은 빈 항목을 쓴 운영자를 향한
문장이므로, 프로브는 그 거절을 **메시지로** 알아보고(CLI 는 그것을 `ConfigurationException` 으로 감싸므로 원인 사슬 어디에
있든) 키, 운영자가 쓰는 키 경로, 표면 클래스, 전달 메서드를 부르는 문장 — "그 메서드에 이 프로퍼티를 읽는 `.<key>(...)`
호출을 더한다" — 으로 옮긴다. 그 밖의 예외는 버려진 전달이라고 부르지 않고, 원래 예외를 원인으로 달아 보고한다.

**키가 표면에 있는지는 따로 확인한다.** 왕복이 그것을 포함하지만(세터가 없으면 쓰기가 실패한다) 두 실패는 다른 처방이다 —
"이 표면에 그 키가 없다"(프로퍼티를 더한다)와 "키는 있는데 전달이 버린다"(줄을 더한다). 이 확인은 **읽기와 쓰기 메서드를
모두** 요구한다. 쓰기 전용 프로퍼티는 바인딩되고 전달에게 읽을 것을 주지 않는다.

### 8.4 계약의 자리

가드를 `aimon-core` 에 둘 수 없다는 사실(코어는 두 표면을 보지 못한다)은 가드를 두 벌 두어야 한다는 뜻이 아니다. 계약은
**미배포 모듈 `aimon-llm-capability-testkit`** 의 `AbstractModelCapabilityBindingContractTest` 에 하나 두고, 표면 모듈마다
짧은 서브클래스 하나(`ModelCapabilityConfigBindingTest`, `AimonPropertiesBindingCoverageTest`)가 상속한다. 실패는 여전히
자기 모듈의 테스트 태스크에서 난다. 두 확인은 중첩 클래스 `EveryKeyIsBound` · `EveryValueIsForwarded` 다.

- **계약 하나가 성립하는 이유.** 두 표면은 선언 키마다 같은 필드 타입을 바인딩한다. enum 과 문자열의 차이는 바인더
  고리(Jackson 의 yaml 스칼라 변환, Boot 의 프로퍼티 문자열 변환)에만 있고 계약은 그 고리 바깥의 전달을 시험한다. 표면이
  언젠가 다른 타입을 바인딩하면 일방향 변환이 흡수한다
- **하나여야 하는 이유.** 번역기를 한 곳에 둔 것(§4.5)과 같다 — 가드 두 벌은 규칙 두 벌처럼 어긋나고, 약해진 가드는 성공을
  보고하므로 어긋남이 더 조용하다
- **서브클래스가 갖는 것.** 새 표면 인스턴스, 그 표면의 전달 호출, 운영자 키 경로의 철자(스타터 서브클래스가 kebab 변환을
  갖는다), 전달 위치의 이름. 계약은 한 표면의 명명 규칙을 배우지 않는다. CLI 서브클래스는 호출해야 하는 package-private
  전달이 있는 `at.aimon.cli.factory` 에 산다
- **별도 모듈인 이유.** `aimon-core` 의 test fixtures 는 이 빌드의 퍼블리싱 플러그인과 Gradle 조합에서 구성이 실패하고, 코어
  main 에 두면 JUnit 과 AssertJ 가 배포 모듈의 컴파일 클래스패스에 들어온다. 기존 testkit 은 도메인이 다르고 그중
  `aimon-memory-testkit` 은 배포된다. 이 모듈은 `aimon.publishable` 을 적용하지 않으므로 BOM 밖이다. 이 모듈의 테스트
  클래스패스가 어떤 platform 을 선언하는가는 [`../testing/test-classpath-shipped-versions.md`](../testing/test-classpath-shipped-versions.md) 가 정본이다
- **자기 실패 경로를 시험한다.** 두 표면이 옳은 동안 트리의 어느 테스트도 가드가 빨개지는 경로를 돌리지 않으므로, 테스트킷은
  한 키를 빠뜨린 가짜 표면 · getter 하나가 세터 둘을 먹이는 가짜 표면 등으로 그 경로를 자기 테스트에서 붙든다. 가짜 표면은
  모양별로 고른 키만 가지므로 새 키가 이 모듈의 편집 지점이 되지 않는다
- **바인더 고리 테스트를 대체하지 않는다.** 프로브는 bean 세터로 쓰므로 바인더를 지나지 않는다. 키마다 손으로 쓴 바인더
  테스트는 그대로 남고, 그 범위의 현재 사실(스타터에서 확인되지 않는 키)은 L-14 가 가진다

---

## 9. 기각한 대안

### 9.1 설정 표면

| 대안 | 기각 이유 |
|---|---|
| **capability alias** — 배포 이름을 내장 표의 이름으로 해석하게 한다(`modelCapabilitiesAs: <내장 이름>`) | alias 는 내장 표가 **이미 아는** 모델만 가리킬 수 있어, 표가 모르는 모델(새 계열, 사설 모델, 측정하지 않은 변종)을 가진 배포는 여전히 닿지 못한다. "벤더 사실을 운영자 yaml 에 복사하지 않는다" 는 논거는 표에 있는 모델에만 성립하고, 그 경우도 표가 옳다는 신뢰를 요구한다 |
| alias 와 전체 항목을 둘 다 제공 | 사용자 개념이 둘이 되고 둘을 함께 적었을 때의 우선순위라는 세 번째 규칙이 생긴다. alias 는 전체 항목 위에 나중에 얹을 수 있는 순수 추가다 |
| 리스트 형태 `modelCapabilities: [{model: …}]` | 문서 가드 walker 가 `List` 로 내려가지 않아 이 서브트리에 눈이 멀고, 중복 이름이 표기법 차원에서 가능해진다(§4.1) |
| 레이블 키 + `model:` 필드 | 한 사물에 이름이 둘이 되고 두 항목이 같은 모델을 가리킬 수 있다. 능력 선언에는 레이블과 모델이 다대일인 경우가 없다 |
| 키 안의 `*` 로 prefix 표기 | 두 설정 표면에만 존재하는 미니 언어를 만들고, prefix 를 열지 않는 이유(§4.1)가 그대로 걸린다 |
| prefix 를 길이 내림차순으로 등록해 longest-match 흉내 | 코어와 다른 세 번째 매칭 규칙이 설정 표면에만 생기고, 그래도 내장 prefix 를 이기지 못한다 |
| 설정이 내장 표를 **대체**(빈 빌더에서 시작) | 한 줄만 적은 운영자에게서 나머지 내장 행을 조용히 빼앗는다 |
| 선언 필드 전부 필수 | 모르는 칸을 지어내게 하고(trace 왕복 → 404), 필드가 느는 날 모든 설정을 깨뜨린다(§4.2) |
| `lowestReasoningEffort` 를 `String` 으로 받아 번역기가 파싱 | 스타터에 손 힌트가 필요해져 "enum 선택자는 타입이 값을 싣는다" 가 깨진다. 코어 enum 을 프로퍼티 타입으로 쓰는 선례(`MemoryInjectionMode`)가 이미 있다 |
| 번역기를 `aimon-bootstrap` 에 | `LlmClient` 는 두 표면 모두 스택 밖에서 만들어진다. 코어에 있어야 provider 모듈 테스트가 같은 번역기를 쓴다(§4.5) |
| 번역 로직을 두 표면에 각각 인라인 | 규칙 넷(defaulting · 확장 · exact 등록 · 이름 검증)이 두 곳에 있으면 어긋남이 조용하다 |
| `AimonProperties` 에 `ignoreUnknownFields = false` | `aimon.*` 트리 **전체**의 동작 변경이고, 같은 prefix 아래 자기 키를 두는 호스트 앱을 기동 실패로 만들 수 있다. 스타터 오타 문제 자체는 L-1 |
| 항목을 `Map<String, Map<String, String>>` 로 받아 번역기가 잎을 검사 | 스타터 오타를 이 서브트리만 닫지만, 메타데이터에서 잎 이름과 enum 후보가 사라지고, 문서 가드가 `*.*` 를 기록해 **가이드의 잎 오타가 통과한다.** 설정 오타를 잡으려고 문서 오타를 못 잡게 만드는 거래다 — L-1 이 저울을 가진다 |
| 테스트를 위해 `OpenAILlmClient.getConfig()` 공개 | 배포 모듈의 공개 표면을 테스트 편의로 넓힌다. 표면의 조립 메서드를 package-private 으로 추출한다(§5.3) |
| 설정 키 `lowestReasoningEffort` 를 SPI 에 맞춰 개명 | 스타터가 모르는 프로퍼티에 침묵하므로 그 키를 선언한 배포가 말없이 선언을 잃는다(§4.4) |
| 스타터 `reasoning-effort` 를 `String` 으로 받아 fold | `String` 은 `compileOnly` 벤더 클래스의 부재를 견디려는 모양이다. `ReasoningEffort` 는 코어라 늘 있고, `String` 은 IDE 완성과 메타데이터 타입만 잃는다 |
| `reasoningEffort` 에 CLI 전용 deserializer | 그 예외는 `off` 가 YAML 불리언이라 강제된 것이다. `ReasoningEffort` 철자는 YAML 리졸버와 부딪히지 않고 대소문자는 매퍼가 덮는다 |
| thinking 키에 공유 이름(`llm.thinkingMode`) | 이름에 벤더 개념을 담은 첫 키에서 공유를 택하면 네임스페이스 기준이 반증 불가능해진다(§3.1) |
| 셋째 단계 `llm.anthropic.thinking.{mode, budgetTokens, …}` | 잎 이름은 `AnthropicConfig` 필드 이름을 평평하게 그대로 옮기고, 한 가족으로 선언된 필드를 설정 모양 때문에 쪼개지 않는다 |
| 평평한 접두 키 `llm.anthropicThinkingMode` | 잎 이름 안의 접두어는 네임스페이스가 아니다. "벤더 블록" 을 한 단위로 거절할 수 없다 |
| 스타터도 벤더 enum 으로 바인딩 | 그럴듯한 운영자 실수가 어떤 `FailureAnalyzer` 도 설명해 주지 않는 `NoClassDefFoundError` 가 되고, `AimonProperties` 시그니처 불변식을 깬다(§5.2) |
| 스타터 로컬 거울 enum | 한 사실 집합에 두 번째 어휘다. 그것이 사는 IDE 완성은 테스트로 고정한 힌트 하나가 더 싸게 산다 |
| 가드된 슬라이스 안에서만 등록하는 별도 `@ConfigurationProperties("aimon.llm.anthropic")` | 문서 가드 walker 가 `AimonProperties$` 중첩 타입으로만 내려가 서브트리 전체가 가드 밖으로 빠지고, `afterPropertiesSet` 이 검증할 수 없으며, 반대편 분기의 거절이 읽을 것이 없다 |

### 9.2 바인딩 왕복 가드

| 대안 | 기각 이유 |
|---|---|
| 표면마다 가드 두 벌을 두고 왕복을 각각 복사 | 두 표면의 타입은 계약이 닿는 층에서 같고, 가드 두 벌은 규칙 두 벌처럼 어긋나며 약해진 쪽이 성공을 보고한다(§8.4) |
| 선언 대신 레지스트리의 `ModelCapabilities` 에서 단언 | 두 표면이 닿는 끝점이 다른 타입의 다른 메서드라 한 계약이 두 사슬을 시험한다. 바인딩 가드가 레지스트리 의미에 묶여 빨개질 이유가 둘이 되고, 두 ladder 키가 `ModelCapabilities` 에서는 한 필드로 합쳐져 키별 대응에 손 예외가 필요하다 |
| 전달을 반사로 만들어 잊을 줄을 없앤다 | 두 전달이 가진 `List` → 집합 변환과 빈 rung 거절을 조용히 삼키고, grep 할 수 있는 명시적 복사를 아무도 적지 않은 이름 결합으로 바꾼다 |
| 코어에서 두 모듈의 소스 텍스트를 읽어 전달이 키를 언급하는지 검사 | 틀린 getter 를 읽는 줄도 키를 언급하므로 통과한다. 텍스트 검사가 보지 못하는 것이 결함 전체다 |
| 선언의 키별 `Optional` getter 로 단언 | 따로 낡는 두 번째 이름 대응이 필요하고, "다른 것은 도착하지 않았다" 를 잃는다(§8.3) |
| 키마다 값 하나 | 박아 넣은 상수가 우연히 맞으면 통과한다(§8.2) |
| 모든 키를 한꺼번에 쓰고 선언 하나를 비교 | 두 ladder 키 때문에 표현되지 않고, 빠진 전달이 이름으로 거절되는 성질을 잃는다 |
| 왕복을 바인더까지 늘인다 | 빨간 결과가 모호해지고 렌더 규칙이 표면마다 달라 공유할 것이 줄어든다 — L-14 에 형태로 남긴다 |
| `aimon-core` 의 `java-test-fixtures` | 이 빌드에서 구성이 실패한다(§8.4) |
| 기존 testkit 에 넣는다 | 도메인이 다르고, `aimon-memory-testkit` 은 배포되어 LLM 설정 가드가 릴리스 아티팩트에 들어간다 |
| 처리할 수 없는 키에 대한 skip 반환 | 아무것도 하지 않는 키 위에서 성공을 보고하는 가드가 바로 이 가드가 고치는 결함이다 |

---

## 10. 하지 말 것

- **미선언(`null`) 필드를 운영 문서에 기본값으로 적지 않는다.** `AimonDocumentedPropertiesTest.everyStatedDefaultMatchesTheField`
  는 문서의 기본값 문장을 필드 초기값과 대조하므로 `null` 필드에 "기본값 false" 를 붙이면 실패한다. "선언하지 않으면
  `ModelCapabilities.unknown()` 의 값" 이라고 쓰고, 그 값은 [`model-capabilities.md`](model-capabilities.md) 를 가리킨다
- `compileOnly` 모듈의 타입을 `AimonProperties` 의 어떤 시그니처에도 싣지 않는다
- 벤더 타입을 서술자에 가진 헬퍼를 바깥 `AimonLlmAutoConfiguration` 에 두지 않는다
- 프로퍼티 클래스를 `AimonProperties` 밖의 타입이나 별도 `@ConfigurationProperties` 로 만들지 않는다 — 문서 가드가 눈을 감는다
- 코어 규칙을 표면에서 사전 검사로 다시 적지 않는다. 좁은 catch 로 재던진다
- 읽히지 않을 벤더 블록을 실행되는 분기 **밖**에서 거절하지 않는다
- 출하된 설정 키를 개명하지 않는다 — 스타터에서는 선언이 말없이 사라진다
- 공유 가족의 잎에 네임스페이스 기준을 따로 적용하지 않는다
- 내장 행이 있는 이름에 플래그 하나만 적는 선언을 운영 문서에 예시로 싣지 않는다 — 선언은 행 전체다
- 모델 이름을 선언 타입의 필드로 넣지 않는다 — 이름은 맵 키 한 자리에만 있다
- 선언 키를 더하면서 두 표면의 전달 줄을 빠뜨리지 않는다. 가드가 빨개지면 `valuesFor` 로 비우거나 건너뛰지 말고 줄을 더한다
- 전달에서 키를 기본값으로 채우거나 파생하지 않는다 — 필요하면 계약에 적는다
- 테스트 편의로 배포 모듈의 공개 표면을 넓히지 않는다
- `AnthropicConfig` 빌드 경로에 설정 가능한 키를 더하면서 예산 키를 부르는 catch 를 그대로 두지 않는다
- 한 표면의 표기로 다른 표면의 예시를 쓰지 않는다

---

## 11. 남은 것

등록된 항목 — [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md):

- **L-1** — 스타터에서 모르는 잎 이름이 조용하다(CLI 와의 비대칭). 닫는 길 셋의 저울이 거기 있다
- **L-2** — `responsesApiEnabled` 와 샘플링 파라미터에 아직 설정 표면이 없다. 배치는 §3.2 표대로다. Anthropic 쪽은
  `AnthropicConfig` 에 `topP` · penalty 필드부터 없다. `gpt-5.6-terra` 를 Chat Completions 로 강제한 칸의 미측정도 여기 붙어 있다
- **L-3** — `provider=none` 과 애플리케이션 자체 `LlmClient` 빈 배포에서 선언과 벤더 블록이 조용히 읽히지 않는다
- **L-4** — 설정에서 prefix 를 선언할 길을 열 것인가(코어 쪽이 순수 추가가 아니다)
- **L-5** — CLI 매핑 오류 메시지가 어느 키인지 말하지 않는다
- **L-8** — 선언이 내장 행을 가리면서 그 행의 플래그를 적지 않았을 때 알리지 않는다
- **L-13** — 선언에서 기술자로 가는 세 번째 손 전달(고리 3)에 가드가 없다
- **L-14** — 바인더 고리(고리 1)는 키마다 손으로 확인되고, 스타터에는 확인되지 않는 키가 있다

등록되지 않은 항목:

- **frontmatter 에 없는 `LlmModel` 노브.** `model.requestTimeout` · `model.presencePenalty` · `model.frequencyPenalty` 는
  `extractModel` 이 읽지 않는다(`AgentDefinitionVersion` 은 셋 다 싣는다). 요구가 없어 열지 않았다
- **교차 키 `build()` 규칙에 대한 훅이 없다.** "X 는 Y 를 요구한다" 같은 규칙이 생기면 한 번에 한 키 방식의 계약이 빨개진다.
  필요한 것이 없어 만들지 않았고, 그 빨간 결과는 "버려진 전달이 아니다" 로 보고되므로 읽을 수 있다 — 규칙을 더하는 쪽이 정한다
- **전달 안에서 값을 검증하는 표면.** 표면 세터가 합성 값을 거절하는 경우는 그렇게 보고되지만, 전달 **안에서** 검증하는 표면이
  생기면 "거절" 과 "버림" 을 구분하지 못한다. 그런 표면은 아직 없다
- **키 발견의 거짓 양성.** 키가 아닌 인자 하나짜리 fluent 메서드가 `Builder` 에 생기면 키로 취급되어 두 표면에서 빨개진다.
  시끄러우므로 좁히지 않았고, 코어의 키 개수 단언이 사람을 한 번 더 세운다

---

## 부록 — 참조 파일 지도

경로는 `modules/` 기준.

| 파일 | 무엇을 확인하나 |
|---|---|
| `aimon-core/…/llm/capability/ModelCapabilityDeclaration.java` | 박싱 필드 · `null` 세터 · `build()` 의 두 거절 · `capabilities()` · `equals` 범위 |
| `aimon-core/…/llm/capability/InMemoryModelCapabilityRegistry.java` | `withDefaultsExtendedBy` — 내장 표에서 시작, exact 등록, 이름 · 대소문자 중복 · `null` 값 거절 |
| `aimon-core/…/agent/definition/parser/MarkdownAgentDefinitionParser.java` | `extractModel` 이 읽는 키, `model.reasoningEffort` fold 와 `AgentDefinitionParseException` |
| `aimon-core/…/agent/AgentDefinitionVersion.java` | `canonicalForm` 의 `model.reasoningEffort=` 줄과 변경 감지기의 목적 |
| `aimon-cli/…/cli/config/CliConfigLoader.java` | 매퍼 기능(`ACCEPT_CASE_INSENSITIVE_ENUMS`), 바인딩 전 확장, 매핑 오류의 일반 메시지 |
| `aimon-cli/…/cli/config/LlmProviderConfig.java` · `ModelCapabilityConfig.java` | CLI 표면 필드와 타입 |
| `aimon-cli/…/cli/config/AnthropicProviderConfig.java` | 박싱 필드 · `isEmpty()` · `ThinkingModeDeserializer`(YAML `off`) |
| `aimon-cli/…/cli/config/OpenAiProviderConfig.java` | `reasoningSummary` 블록과 `isEmpty()` |
| `aimon-cli/…/cli/factory/LlmClientFactory.java` | `declarationOf` · `registryFor` · 두 분기의 조립 메서드 · 좁은 catch 와 키 경로 · `refuseAnthropicBlock` / `refuseOpenAiBlock` |
| `aimon-spring-boot-starter/…/autoconfigure/AimonProperties.java` | `LLM_*` 상수, `Llm` · `Llm.Anthropic` · `Llm.OpenAi` · `ModelCapabilityProperties` 필드 타입, `validateLlm` · `modelCapabilityRegistry` · `toDeclaration` |
| `aimon-spring-boot-starter/…/autoconfigure/AimonLlmAutoConfiguration.java` | 가드된 중첩 슬라이스, vendor fold 와 `off` 힌트, 예산 키 catch, 바깥 클래스의 두 거절 |
| `aimon-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json` | `String` 선택자 네 개의 값 힌트 |
| `aimon-spring-boot-starter/src/test/…/AimonAutoConfigurationTest.java` | `noPropertiesSignatureNamesAVendorType` — 시그니처 패키지 허용 목록 |
| `aimon-spring-boot-starter/src/test/…/AimonConfigurationMetadataTest.java` | 손 힌트 집합 고정과 힌트 값 대조 |
| `aimon-spring-boot-starter/src/test/…/AimonDocumentedPropertiesTest.java` | 트리 walker(맵 · `AimonProperties$` 중첩 타입), `everyStatedDefaultMatchesTheField` |
| `aimon-spring-boot-starter/src/test/…/AimonPropertiesValidationTest.java` | 대괄호 · 대소문자 보존 · 스타터 오타 침묵의 한계 기록 테스트 |
| `aimon-cli/src/test/…/cli/config/CliConfigLoaderTest.java` | yaml 로 바인더를 지나는 키별 확인, `off` |
| `aimon-llm-capability-testkit/…/testkit/AbstractModelCapabilityBindingContractTest.java` | 네 훅 · `valuesFor` · 두 중첩 확인, 계약 하나의 논거(javadoc) |
| `aimon-llm-capability-testkit/…/testkit/DeclarableKeys.java` · `ProbeValues.java` · `SurfaceWriter.java` · `ModelCapabilityBindingProbe.java` | 키 발견, 값 쌍 합성과 검사, 일방향 변환, 빈 선언 거절의 번역 |
| `aimon-llm-capability-testkit/build.gradle.kts` | 미배포 · `api` 의존의 이유 |
| `aimon-cli/src/test/…/cli/factory/ModelCapabilityConfigBindingTest.java` · `aimon-spring-boot-starter/src/test/…/AimonPropertiesBindingCoverageTest.java` | 표면 서브클래스 — 전달 호출과 운영자 키 경로 철자 |
| `aimon-cli/src/main/resources/default-config.yaml` | 주석으로만 보이는 effort · ladder 키와 vendor 블록 |

---

## 관련 문서

- [`model-capabilities.md`](model-capabilities.md) — 선언이 채우는 필드의 뜻, 내장 표, 조회 순서, fail-open
- [`request-parameters.md`](request-parameters.md) — `reasoningEffort` 우선순위와 effort ladder, 샘플링 파라미터 규칙
- [`anthropic-thinking.md`](anthropic-thinking.md) — thinking 모드 · 예산 · display 규칙(이 문서는 그 키의 표면 실패만)
- [`reasoning-traces.md`](reasoning-traces.md) — `replayThinkingBlocks` 의 의미
- [`openai-responses-path.md`](openai-responses-path.md) — `reasoningSummary` 요청과 `responsesApiEnabled`
- [`streaming.md`](streaming.md) — 추론 스트림 노브와 중립 우산 키를 두지 않는 판단
- [`model-name-resolution.md`](model-name-resolution.md) — 요청이 싣는 모델 이름과 문서 예시의 모델 이름 규칙
- [`../integration/spring-boot-starter.md`](../integration/spring-boot-starter.md) — 스타터 프로퍼티 트리와 자동설정 슬라이스
- [`../integration/config-value-expansion-and-frontmatter-strictness.md`](../integration/config-value-expansion-and-frontmatter-strictness.md) — `${VAR}` 확장, frontmatter 값 엄격화
- [`../testing/test-classpath-shipped-versions.md`](../testing/test-classpath-shipped-versions.md) — 테스트킷의 테스트 클래스패스
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — 이 표면의 열린 항목
- [`../../backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md) — B-21(네임스페이스 결정 기록이 이 문서를 가리킨다)
