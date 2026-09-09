# 설계 — 모델 capability 표를 설정에서 확장한다 (#46)

> Status: **APPROVED, 구현됨.** §0~§10 은 리뷰를 통과한 설계 그대로다(리뷰가 반증한 여덟 건은 반영 후의
> 상태다). **§11 은 구현 후에 쓰였고, 실측이 이 본문을 반증한 세 자리를 기록한다** — 그 셋은 전부
> Spring Boot 바인더의 동작에 관한 사실이며, 이 문서의 **결정**(키 모양 · 부분 선언 · 확장 관계 · 표기)은
> 하나도 바뀌지 않았다. 반증된 본문 자리에는 §11 을 가리키는 정정 표시를 그 자리에 달았다 — 검색으로
> 그 줄에 먼저 도착하는 사람은 이 머리말을 읽지 않기 때문이다. **§3 이나 §6 의 세부를 믿기 전에 §11 을
> 읽는다.**
>
> **열림/닫힘의 정본은 §9 가 아니다.** 이 국면 밖으로 결과가 나가는 미해결 넷(O-B · O-C · O-D · O-E)은
> [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) 의
> L-1 ~ L-4 로 올라갔고, §9 는 설계 시점의 기록으로 동결된다.
>
> 대상: `aimon-cli` yaml, `aimon-spring-boot-starter` 프로퍼티, `aimon-core`
> (`at.aimon.core.llm.capability`).
> 선행 설계: [`openai-model-capabilities.md`](openai-model-capabilities.md) — 특히 §2.3(fail-open),
> §2.6(seam), §6.1(assertion vocabulary), §7 O-8(두 후보 모양). 경로는 저장소 루트 기준이다.
> 이 문서는 그 O-8 을 닫는다.

---

## 0. 다섯 결정, 먼저

TASK.md 가 "설계 단계의 본체" 로 지목한 다섯 가지에 대한 답이다. 근거는 §2 에 하나씩 있다.

| # | 물음 | 답 |
|---|---|---|
| 1 | 키 모양. exact 와 prefix 를 둘 다 표현하는가 | **모델 이름을 맵 키로 하는 블록. exact 전용이고 prefix 는 넣지 않는다.** prefix 를 설정에 열면 (a) 등록 **순서가 load-bearing** 인 규칙이 사용자 표면으로 새어 나오고, (b) 내장 prefix 를 이길 방법이 코어 변경 없이는 없다. exact 는 그 둘을 모두 피하면서 이슈의 시나리오(고정 배포 이름 하나)를 정확히 덮는다 (§2.2) |
| 2 | 부분 선언의 기본값 | **`ModelCapabilities.unknown()` 을 깔고 선언된 것만 덮어쓴다.** 다섯 개를 다 요구하지 않는다. 그것이 `ModelCapabilities.Builder` 가 이미 하는 일이고, 두 번째 defaulting 규칙을 만들면 §2.3 의 *"fail-open 은 타입의 성질이지 등록자가 매번 다시 도출할 규칙이 아니다"* 가 거짓이 된다 (§2.3) |
| 3 | 내장 표와의 관계 | **확장이다** (`builderWithDefaults()`). 사용자 선언은 **exact 로 등록**되고, 기존 `exact > prefix` 규칙이 그대로 "사용자가 이긴다" 를 뜻한다. 새 우선순위 규칙을 만들지 않는다. 다만 **한 이름만 이긴다 — 가족을 덮지 않는다** (§2.4) |
| 4 | 잘못된 값 | 세 갈래로 나눠 처분한다. **값 오류**(잘못된 `lowestReasoningEffort`)는 두 표면 모두 바인더가 시끄럽게 실패. **의미 오류**(아무것도 선언하지 않은 항목 · 빈 이름 · 대소문자만 다른 중복 · 읽히지 않을 provider 아래 선언)는 **공유 번역기와 각 표면의 검증 자리**가 시끄럽게 실패. **모르는 필드 이름**은 CLI 만 자동으로 시끄럽고, 스타터는 구조적으로 불가능하다 — 그 비대칭을 숨기지 않고 §2.5 에 적고 O-C 로 남긴다 |
| 5 | 두 표면의 표기 | CLI `llm.modelCapabilities.<model>.supportsSamplingParameters` (camelCase), 스타터 `aimon.llm.model-capabilities.<model>.supports-sampling-parameters` (kebab-case). **필드 이름은 `ModelCapabilities` 의 자바 필드를 글자 그대로 옮긴다** — `supports` 접두어를 떼지 않는다 (§2.6) |

여기에 TASK.md 가 "다시 본다" 고 한 백로그 항목 하나에 대한 답이 붙는다.

| B-21 | 공통 `aimon.llm.*` 을 provider 별로 쪼갤 것인가 | **이 키는 쪼개지 않는다 — 단, 종전의 "나눌 것이 없다" 는 해소 사유가 더 이상 참이 아니다.** 그리고 분할 자체는 기각된 적이 없다: 설계 기록의 프로퍼티 트리가 `llm.anthropic: { … }` / `llm.openai: { … }` 자리를 **이미 비워 두고 있다.** 정할 것은 무엇이 그리로 가는가이고, 기준은 두 갈래다 — **이름이 벤더 개념을 담거나, 뜻이 벤더마다 다르거나.** `model-capabilities` 는 둘 다 아니므로 `aimon.llm.*` 에 남고, "읽히지 않는 provider 아래 선언되면 거절" 이 그 공유 네임스페이스를 정직하게 만든다 (§2.7). **#52 에서 정정됨:** 그 거절은 삭제되었다 — Anthropic 클라이언트도 이 registry 를 읽게 되어 읽지 않는 분기가 없어졌고, 이제 정직하게 만드는 것은 **두 분기가 모두 읽는다**는 사실이다. 결정("쪼개지 않는다")은 그대로이며, 근거였던 "두 번째 소비자가 예정되어 있다" 가 사실이 되었다 (§2.7 의 정정 블록) |

---

## 1. 문제

`OpenAIConfig.baseUrl` 은 Azure 배포나 OpenAI 호환 게이트웨이를 쓰라고 있는 키인데, 그런 게이트웨이가
모델을 **다른 이름으로 노출**하면(`gpt-5-mini` → `prod-assistant`) 그 이름은 `#44` 의 내장 capability 표에
걸리지 않고 `ModelCapabilities.unknown()` 의 fail-open 경로로 떨어져, 샘플링 파라미터가 그대로 실려 나가고
`#43` 이 고친 HTTP 400 을 여전히 맞는다. 탈출구인 `OpenAIConfig.Builder.modelCapabilityRegistry(...)` 는
**프로그램 전용**이어서 CLI 사용자는 CLI 를 패치하지 않는 한 닿을 수 없고, 스타터 사용자는 `LlmClient` 빈을
통째로 자기가 만들어야 한다. 즉 **설정 표면이 자기가 서빙하지 못하는 배포를 초대하고 있다.** 이 작업은 그
표면에 "이 모델은 이런 것을 받는다" 를 적을 자리를 만들어, 그 선언이 두 조립 경로를 거쳐
`modelCapabilityRegistry(...)` 에 도달하게 한다 — 내장 5행을 **대체하지 않고 그 위에 얹는 방식으로**.

---

## 2. 접근

### 2.1 seam 하나 — 공유 번역기, 표면 둘

설정 표면은 둘(CLI yaml, 스타터 프로퍼티)이지만 **규칙은 하나여야 한다.** 부분 선언의 defaulting,
"확장이지 대체가 아니다", exact 등록, 이름 검증 — 이 넷이 두 곳에 복사되면 두 표면이 서로 다른 답을 하는
날이 오고, 그 어긋남은 조용하다(요청이 성공하는데 파라미터가 다르다). 이 저장소는 같은 이유로
`ModelCapabilityRegistry.resolve` 를 총함수로 두었고(`fail-open 이 사는 단 한 곳`),
`OpenAiRequestParameters.maySendEffort` 를 두 엔드포인트가 함께 부르게 했다.

그래서 배치는 이렇다.

```
  CLI yaml                         스타터 프로퍼티
  llm.modelCapabilities            aimon.llm.model-capabilities
        │                                    │
   Map<String, ModelCapabilityConfig>   Map<String, ModelCapabilityProperties>
        │                                    │
        └──────────────┬─────────────────────┘
                       ▼   Map<String, ModelCapabilityDeclaration>   ← 중립 선언 타입 (aimon-core)
        InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)  ← 규칙의 구현이 사는 단 한 곳
                       ▼
        OpenAIConfig.Builder.modelCapabilityRegistry(...)            ← 변경 없음 (기존 seam)
```

**`aimon-core` 의 `at.aimon.core.llm.capability` 에 두는 이유**(대안은 `aimon-bootstrap`):

- 두 표면 다 이미 `aimon-core` 에 의존한다. bootstrap 을 경유해야 할 이유가 없다.
- 중앙화하는 규칙은 **`ModelCapabilities` 자신의 defaulting 규칙**이다. 그 기본값을 정의하는 빌더와
  떨어뜨려 놓는 것이 바로 드리프트를 만드는 배치다.
- `aimon-bootstrap` 의 `*Spec` 들은 **스택 조립**을 서술한다. 그런데 `LlmClient` 는 두 표면 모두에서
  스택 **밖에서** 만들어진다 — CLI 는 `AgentSetupFactory.createLlmClient` 가 spec 을 만들기 전에,
  스타터는 `AimonLlmAutoConfiguration` 이 별도 슬라이스로. 스택의 일부가 아닌 것을 스택 조립 모듈에
  두면 그 모듈의 설명이 틀려진다.
- 부수 효과 하나가 크다: 중립 타입이 코어에 있으므로 **`aimon-llm-openai` 의 테스트가 그 타입을 볼 수
  있다.** §7 의 "raw 필드 부재" 단언이 손으로 만든 `ModelCapabilities` 가 아니라 **설정 경로가 실제로
  쓰는 번역기**를 통과한 registry 위에서 돌 수 있다는 뜻이다.

**"단 한 곳" 이 무엇에 대한 주장인지 정확히 적는다 — 구현이지 호출 지점이 아니다.** 두 표면은 각자
자기 키 경로를 메시지에 실어야 하므로(CLI 는 `llm.modelCapabilities.<model>`, 스타터는
`aimon.llm.model-capabilities.<model>`) 실패 지점은 둘이다. 그러나 **판정은 복사하지 않는다** — 양쪽
검증자는 `withDefaultsExtendedBy(...)` 를 실제로 불러 보고 `IllegalArgumentException` 을 자기 표면의
예외로 다시 던진다(§4.2 ③, §4.3 ④). 규칙을 다시 적는 것이 아니라 **같은 구현을 두 번 부르는 것**이므로,
코어의 판정이 바뀌면 두 표면이 함께 따라간다. 스타터가 그것을 `afterPropertiesSet` 에서 한 번 더 부르는
이유는 빈이 만들어지기 전에 프로퍼티 이름으로 실패시키기 위해서이고(`AimonProperties` 의 기존 검증자들과
같은 자리), 그 호출은 결과를 버린다.

### 2.2 결정 1 — 모델 이름을 맵 키로, exact 전용

```yaml
# CLI
llm:
  modelCapabilities:
    prod-assistant:
      supportsSamplingParameters: false
```

**왜 리스트가 아니라 맵인가.** 셋 다 실측으로 확인한 이유다.

1. **스타터의 문서 가드가 맵만 걷는다.** `AimonDocumentedPropertiesTest.PropertyTree.walk` 는
   `Map` 과 중첩 클래스는 내려가지만 `List<X>` 는 **리프로 기록하고 끝낸다**(`:569-577`). 리스트로
   만들면 가이드에 적은 `aimon.llm.model-capabilities[0]...` 계열 키가 그 테스트에서 미지의 키가
   되거나(실패) YAML 스캐너의 `- ` 항목이 매칭되지 않아 조용히 검사 밖으로 나간다. 맵은
   `recordMapLevels` 가 와일드카드 세그먼트를 기록하고 값 타입으로 내려가므로
   `aimon.llm.model-capabilities.*.supports-sampling-parameters` 가 그대로 알려진 키가 된다 —
   `aimon.credentials.jira.password` 와 정확히 같은 기계다.
2. **키가 곧 정체성이다.** 모델 이름은 조회 키이고, 맵 키로 두면 중복 선언이 표기법 차원에서 대부분
   불가능해진다(남는 것은 대소문자만 다른 중복 하나이고 그것은 §2.5 에서 거절한다).
3. **선례가 있다.** `aimon.agents.<ref>` · `aimon.credentials.<profile>` 이 같은 모양이고, 그
   맵 키들은 "호스트가 고르는 이름" 이다.

**왜 prefix 를 넣지 않는가.** 세 가지가 모두 나쁜 방향을 가리킨다.

- **순서가 load-bearing 이다.** `InMemoryModelCapabilityRegistry` 의 prefix 매칭은 등록 순서로
  첫 일치가 이긴다. 이것을 사용자 표면에 그대로 노출하면 "yaml 에서 두 줄을 바꿔 쓰면 동작이 바뀐다" 가
  된다. 회피책(길이 내림차순 정렬 후 등록 → longest-match)은 만들 수 있지만, 그건 **설정 표면에만 있는
  세 번째 매칭 규칙**을 새로 만드는 것이고 코어의 규칙과 다르다.
- **내장 prefix 를 이길 수 없다.** `builderWithDefaults()` 위에 새 prefix 를 등록하면 내장 prefix
  **뒤에** 붙는다(같은 prefix 를 다시 등록할 때만 제자리에서 교체된다 — 그 성질은 `gpt-5-chat` 이
  `gpt-5` 에 삼켜지지 않게 하려고 일부러 그렇게 되어 있다). 따라서 사용자가 `gpt-5-mini` prefix 를
  선언해도 `gpt-5-mini-x` 는 내장 `gpt-5` 에 먼저 걸려 **사용자 항목이 영원히 발화하지 않는다.**
  이것을 고치려면 코어에 "사용자 prefix 를 앞세우는" API 를 새로 열어야 하고, 그러면 `gpt-5-chat`
  보호가 깨진다.
- **필요가 작다.** 이슈의 시나리오는 고정 배포 이름 하나다. Azure 배포 이름·게이트웨이 별칭은 가족이
  아니라 개별 이름이다.

**대가와 그 처분.** 한 게이트웨이가 가족 전체를 개명하면(`prod-assistant-mini`, `prod-assistant-nano`)
이름을 하나씩 적어야 한다. 그 비용은 실재하지만 작고, **이름을 적는 것은 정직하다.** 나중에 prefix 를
열고 싶으면 그것은 코어의 등록 순서 API 를 여는 **별개 변경**이고, 이 문서의 맵에 `match: prefix` 같은
필드를 더하는 추가 변경이다(추가적이며 오늘의 의미를 바꾸지 않는다). O-E 에 남긴다.

**스타터의 맵 키는 Boot 가 정규화한다 — 두 결과가 따라온다.**
`ConfigurationPropertyName.adapt` 는 대괄호로 감싸지 않은 세그먼트를 **소문자로 접고** 점을 세그먼트
구분자로 읽는다. 그래서 스타터 표면에서는:

| 쓴 것 | registry 에 등록되는 이름 |
|---|---|
| `prod-assistant` | `prod-assistant` |
| `Prod-Assistant` | `prod-assistant` — **Boot 가 접는다.** 조회는 여전히 맞는다(registry 도 접으므로) |
| `gpt-5.7-x` | `gpt-5` 아래 `7-x` 로 **쪼개질 수 있다** → 대괄호 표기 `"[gpt-5.7-x]"` 가 필요하다 |

대소문자는 어느 쪽으로 가도 조회가 맞으므로 **무해하다** — 다만 그 사실이 §6 F6 과 §7 의 대소문자
테스트가 스타터에서 무엇을 증명하는지를 바꾼다(각 절에 적었다). 점은 무해하지 않다: **구현 시 테스트로
못 박는다**(§7 T-S3). 이것이 이 설계에서 유일하게 모양을 되돌릴 수 있는 미확인 사항이므로 O-A 로 따로
세운다. CLI 는 Jackson 이 맵 키를 글자 그대로 받으므로 두 문제 다 해당 없다.

> **§11 D-2 에서 정정됨 — 위 표의 두 번째 행이 틀렸다.** Boot 는 맵 키의 **대소문자를 접지 않는다**:
> `Prod-Assistant` 는 `Prod-Assistant` 로 도착한다(`MapBinder` 가 키를 `Form.ORIGINAL` 로 되살린다).
> 그러므로 이 문단이 대소문자에서 파생시킨 세 결론 — F6 이 스타터에서 발화할 수 없다, 인수 조건이
> 스타터에서 증명되지 않는다, T-S3′ 를 두 갈래로 나눈다 — 은 **셋 다 성립하지 않는다.** 세 번째 행(점)은
> 맞았고 대괄호 표기가 정답이라는 것도 확인되었다(§11 D-1). 실측 방법과 결과는 §11.

### 2.3 결정 2 — `unknown()` 위에 얹는다. 다섯 개를 요구하지 않는다

선언되지 않은 필드는 `ModelCapabilities.unknown()` 의 값을 갖는다. 즉 설정의 한 항목은
`ModelCapabilities.builder()` 를 만들고 **선언된 세터만 부른 것과 정확히 같다.**

근거 넷:

1. **두 번째 defaulting 규칙을 만들지 않는다.** `ModelCapabilities.Builder` 는 이미 `unknown()` 의
   값으로 seed 되어 있고, 그 javadoc 은 *"a partially specified entry stays permissive by omission and
   fail-open is a property of the type rather than a rule each registry author has to re-derive"* 라고
   적는다. 설정 경로가 다른 규칙을 쓰면 그 문장이 거짓이 된다.
2. **다섯 개 요구는 운영자에게 모르는 것을 답하게 한다.** 게이트웨이 운영자는 "temperature 가 400 을
   낸다" 는 알지만 "이 모델이 reasoning trace 를 되싣는가" 는 모른다. 모르는 칸을 강제로 채우게 하면
   **틀린 답을 제조**하게 되고, 그 틀린 답 중 하나(`supportsReasoningTraceRoundTrip: true`)는
   `/v1/responses` 로 라우팅되어 Chat 전용 게이트웨이에서 **404** 를 만든다.
3. **최소 선언이 안전한 쪽으로 떨어진다.** 이슈의 예시대로 `supportsSamplingParameters: false` 하나만
   적으면 round-trip 은 `false` 로 남아 Chat Completions 경로를 유지한다. fail-open 값이 곧 "오늘의
   동작" 이므로, 부분 선언은 **선언한 그 한 가지만** 바꾼다.
4. **필드는 또 늘어난다.** 이 descriptor 는 이미 3 → 5 로 자랐다(round 4, round 7). 다섯 개를 요구하는
   스키마는 여섯 번째 필드가 생기는 날 **모든 기존 설정을 깨뜨린다.**

**대신 한 가지를 거절한다: 아무것도 선언하지 않은 항목.**
`prod-assistant: {}` 는 `unknown()` 을 등록하는 것이고, 선언하지 않은 것과 구별되지 않는다. 그런데
운영자는 그것이 무언가를 한다고 믿는다 — 이 기능이 존재하는 이유가 바로 "조용한 no-op 이 400 을
만들었다" 이므로, **다섯 필드 중 하나도 선언되지 않은 항목은 시끄럽게 거절한다.** 이 규칙은 §2.5 에서
한 번 더 값을 한다.

### 2.4 결정 3 — 확장이고, 사용자가 이긴다. 단 한 이름만

- 시작점은 `InMemoryModelCapabilityRegistry.builderWithDefaults()` 다. **내장 5행은 살아 있다.**
- 사용자 선언은 전부 `Builder.register(name, caps)` — **exact** 로 들어간다.
- 따라서 우선순위는 이미 있는 규칙이 그대로 답한다: **exact 가 모든 prefix 를 이긴다.** 새 규칙 없음.

내장과 겹칠 때의 정확한 의미를 문서에 못 박는다.

| 선언 | 결과 |
|---|---|
| `prod-assistant` (내장에 없음) | 사용자 항목이 답한다 |
| `gpt-5` (내장 prefix 와 같은 문자열) | **정확히 `gpt-5` 라는 이름만** 사용자 항목이 답한다. `gpt-5-mini` 는 여전히 내장 `gpt-5` prefix 가 답한다 |
| `gpt-5-nano` | 그 이름만 사용자 항목이 답한다 (내장 prefix 를 이긴다) |

즉 **설정 항목은 모델 하나를 이름으로 지목하는 것이지 가족을 덮는 것이 아니다.** 이것은 제약이 아니라
`exact > prefix` 의 정의이고, prefix 를 열지 않기로 한 결정(§2.2)의 직접적 귀결이다.

여기서 파생되는 검증 가능한 불변식 하나를 세운다 — **설정 표면은 내장 표의 어떤 행이든 표현할 수
있어야 한다.** 그래서 `lowestReasoningEffort` 를 포함해 다섯 필드를 전부 노출한다(o-시리즈 행은 그
필드 없이는 표현되지 않는다). 이 불변식은 테스트로 강제한다(§7 T-C4).

### 2.5 결정 4 — 잘못된 설정의 처분, 세 갈래

| 오류의 종류 | CLI | 스타터 |
|---|---|---|
| **모르는 필드 이름** (`supportsSamplingParameter`) | `FAIL_ON_UNKNOWN_PROPERTIES` 로 자동 실패 → `ConfigurationException("Invalid configuration structure in: …")` | **불가능하다.** `@ConfigurationProperties` 는 기본이 `ignoreUnknownFields = true` 이고, 그것을 끄는 것은 `AimonProperties` 트리 전체의 동작 변경이라 이 작업의 범위를 넘는다 → **부분 완화**를 둔다(아래) + O-C |
| **잘못된 값** (`lowestReasoningEffort: lowish`) | Jackson `InvalidFormatException`(수용값을 메시지에 싣는다) → `ConfigurationException` | Boot 바인더가 프로퍼티 이름과 수용값을 실어 기동 실패 |
| **의미 오류** (아무것도 선언 안 함 · 빈/공백 이름 · 대소문자만 다른 중복 · 읽히지 않을 provider) | `LlmClientFactory` 가 yaml 키를 이름으로 거절 (`validateModel` 선례) | `AimonProperties.afterPropertiesSet` 의 새 `validateLlm()` + Anthropic 분기 (§4.3) |

**스타터의 모르는 필드에 대한 부분 완화 두 가지 — 완전하지 않다는 것을 먼저 적는다.**

1. **§2.3 의 "아무것도 선언하지 않은 항목은 거절" 규칙이 여기서 두 번째 값을 한다.** 필드가 하나뿐인
   항목의 이름을 잘못 쓰면 그 항목은 **0개 선언**이 되어 시끄럽게 거절된다. 거절 메시지는 그 가능성을
   직접 말한다 — *"declared nothing; if you meant to set a flag, check the spelling of its keys"*.

   > **§11 D-3 에서 철회됨 — 이 완화책은 듣지 않는다.** 그 항목은 0개 선언이 되지 않고 **아예 만들어지지
   > 않는다**(Boot 의 `JavaBeanBinder` 는 리프를 하나도 못 바인딩하면 값 인스턴스를 만들지 않는다).
   > 그래서 남는 완화책은 아래 ②뿐이고, **O-C 의 범위는 이 절이 적은 것보다 넓다** — 두 필드 중 하나만
   > 오타 난 경우뿐 아니라 **한 필드짜리 항목도 조용하다.** 현재 상태는
   > [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-1.

2. 스타터 메타데이터가 이 트리를 기술하므로 IDE 가 모르는 리프를 표시한다.

두 필드 중 하나만 오타 난 경우는 여전히 조용하다. **그 사실을 숨기지 않고 O-C 로 남긴다.**
이 구멍을 트리 전체를 건드리지 않고 닫는 길이 하나 더 있다 — 항목을 `Map<String, Map<String, String>>`
로 받아 번역기가 리프 이름을 직접 검사하는 것. 그 대가가 더 크다고 판단했고, 근거는 R14 에 적었다.

**`lowestReasoningEffort` 의 타입.** 두 표면 모두 **`at.aimon.core.llm.ReasoningEffort` enum 으로
바인딩한다** — 문자열로 받아 공유 번역기에서 파싱하지 않는다. 이유:

- 스타터: `AimonConfigurationMetadataTest.enumSelectorsCarryTheirValuesInTheType` 가 *"손으로 쓴 힌트는
  `aimon.llm.provider` 하나뿐"* 을 못 박고 있다. enum 이면 프로세서가 `type` 에 클래스를 기록해 IDE 가
  상수를 읽고, 그 불변식이 유지된다. **코어 enum 을 스타터 프로퍼티 타입으로 쓰는 선례가 이미
  있다** — `aimon.memory.injection-mode` 는 `MemoryInjectionMode` 이고 그 테스트가 그 사실을 명시적으로
  칭찬한다.
- CLI: Jackson 의 enum 바인딩은 기본이 대소문자 구분이라 `low` 가 실패하고 `LOW` 만 통과한다. 두 표면의
  체감을 맞추기 위해 **`CliConfigLoader` 의 매퍼에 `MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS` 를
  켠다** (`JsonMapper.builder(new YAMLFactory())…build()` 형태로 써서 deprecated API 를 피한다).
  **이 스위치는 오늘의 설정을 하나도 건드리지 않는다** — `at.aimon.cli.config` 에는 enum 으로 바인딩되는
  필드가 **하나도 없다.** 유일한 후보인 `McpServerEntry.transportType` 은 `String` 필드(`:35`)이고
  `McpTransportType.valueOf(transportType)`(`:57`)로 **손으로** 파싱되므로 Jackson 의 매퍼 기능이 닿지
  않는다(`transportType: stdio` 는 이 변경 뒤에도 같은 `Invalid transportType 'stdio'` 로 실패한다).
  즉 이 스위치가 넓히는 것은 **새로 생기는 `lowestReasoningEffort` 하나뿐**이다.

결과적으로 값 오류 메시지의 **문구**는 두 표면이 다르다. 그것이 옳다 — 각 메시지는 **자기 표면의 키
경로**를 가리켜야 하고, 이 저장소는 같은 이유로 `LlmClientFactory.validateModel` 과
`AimonLlmAutoConfiguration.requireModel` 을 따로 두었다.

### 2.6 결정 5 — 표기, 그리고 필드 이름

```yaml
# CLI (aimon.yaml) — camelCase. CliConfig 계열은 네이밍 전략 없이 빈 이름을 그대로 쓴다.
llm:
  provider: openai
  baseUrl: https://gateway.internal/v1
  model: prod-assistant
  modelCapabilities:
    prod-assistant:
      supportsSamplingParameters: false
      supportsReasoningEffort: true
      supportsToolsWithReasoning: true
      supportsReasoningTraceRoundTrip: false
      lowestReasoningEffort: minimal
```

```yaml
# 스타터 — kebab-case
aimon:
  llm:
    provider: openai
    model: prod-assistant
    model-capabilities:
      prod-assistant:
        supports-sampling-parameters: false
        supports-reasoning-effort: true
        supports-tools-with-reasoning: true
        supports-reasoning-trace-round-trip: false
        lowest-reasoning-effort: minimal
```

**필드 이름은 `ModelCapabilities` 의 자바 필드를 글자 그대로 옮긴다.** `supports` 접두어를 떼어
`samplingParameters` 로 짧게 쓰고 싶은 유혹이 있지만 거절한다 — 이 다섯 이름은 `ModelCapabilities` 의
javadoc, CHANGELOG, 설계 문서 전체에서 쓰이는 **어휘**이고, 같은 다섯 사실에 두 번째 어휘를 만들면
아무도 유지하지 않는 번역표가 생긴다. 그리고 §2.4 의 "설정으로 내장 표의 모든 행을 표현할 수 있다"
불변식이 **문자 그대로의 전사**로 읽히게 된다.

두 표면이 섞이지 않는다는 것은 문서로도 강제한다 — 이 저장소는 `pluggable-memory-backend.md` §0.2 에서
kebab 예제가 사람을 부팅 실패로 보낸 전례를 기록해 두었다. §8 의 문서 목록에서 CLI 예제와 스타터 예제는
**서로 다른 파일**에 있고, 각 파일은 자기 표기만 싣는다.

### 2.7 B-21 — 항목을 되살리고, 다른 이유로 같은 답을 낸다

`docs/backlog/spring-boot-starter-open-items.md` 의 B-21 은 *"공통 `aimon.llm.*` 을 최소 교집합만 두고
전용 키를 `aimon.llm.<provider>.*` 로 분리할지"* 였고, **"나눌 것이 없다 — `AimonProperties.Llm` 에는
다섯 개뿐"** 이라는 관측으로 §5 에서 해소(dissolved)되었다. 이 작업이 여섯 번째 축을 만들므로 그 관측은
더 이상 참이 아니다.

**답은 여전히 "쪼개지 않는다" 이지만, 이유가 새로 필요하다.** 그리고 그 이유는 원 질문이 무엇을
겨눴는지에서 나온다 — `spring-boot-starter.md` §9.3 은 분할을 *"temperature 유효범위, presence/frequency
penalty 무시 등으로 공통 `aimon.llm.*` 키의 **의미가 프로바이더마다 다르다**"* 라는 이유로 제안했고,
같은 문서의 프로퍼티 트리(`:749-756`)는 그 자리를 `llm.anthropic: { … }` / `llm.openai: { … }` 로
**이미 비워 두었다**. 즉 분할 자체는 기각된 적이 없다. 정해야 하는 것은 **무엇이 그 자리로 가는가**다.

> **두 경우에 `aimon.llm.<provider>.*` 로 내린다 — 키가 벤더 개념을 이름에 담고 있거나, 같은 키가
> 벤더마다 다른 것을 뜻하거나. 오늘 소비자가 하나뿐이라는 사실은 쪼개는 이유가 아니다.**

`model-capabilities` 는 두 조건 어디에도 걸리지 않는다.

- **이름이 중립이다** — **provider-neutral SPI 의 이름**이다(`at.aimon.core.llm.capability`).
  `supportsReasoningTraceRoundTrip` 의 javadoc 이 *"Anthropic's is send the thinking blocks back"* 라고
  명시하듯, 두 번째 소비자가 예정되어 있다(#47). 지금 `aimon.llm.openai.*` 로 내리면 그날 **깨는
  키 이동**이 필요해진다.
- **뜻이 하나다** — "이 모델의 요청 표면이 무엇을 받는가" 는 벤더가 바뀌어도 같은 물음이다. §9.3 이
  분할 사유로 든 의미 발산(temperature 의 유효범위가 벤더마다 다르다)이 여기에는 없다.

대신 공유 네임스페이스가 거짓말하지 않도록 **읽히지 않을 때 거절한다** — 스타터가 실제로 Anthropic
클라이언트를 만드는 분기에서, CLI 가 `createAnthropicClient` 에서. 이 저장소의 원칙
(*"설정했는데 안 읽히는 것이 가장 나쁘다"*, `validateScheduling` 의 *"bound and act on nothing"*)의
적용이다. 이 거절이 있어야 "중립 이름이니 공유 네임스페이스에 둔다" 가 **오늘도** 참이 된다 — 두 번째
소비자는 아직 없기 때문이다.

> **#52 에서 정정됨 — 위 문단의 마지막 두 문장이 더 이상 참이 아니다.** 두 번째 소비자가 도착했다:
> `AnthropicLlmClient` 가 `ModelCapabilityRegistry` 를 읽고, CLI 의 `anthropicConfig(...)` 와 스타터의
> `AnthropicConfiguration.anthropicConfig(...)` 가 같은 번역기로 선언을 그 클라이언트에 넘긴다.
> 그래서 **두 거절 가드는 삭제되었다**(`refuseModelCapabilitiesForAnthropic`,
> `AimonLlmAutoConfiguration.refuseModelCapabilities`) — 읽지 않는 분기가 없으므로 거절할 자리가 없다.
>
> **결정은 그대로다.** 오히려 이 문단이 그 결정의 근거로 든 것("두 번째 소비자가 예정되어 있다 …
> 지금 내리면 그날 깨는 키 이동이 필요해진다", 바로 위 불릿)이 예측에서 사실이 되었다. 바뀐 것은
> 공유 네임스페이스를 정직하게 만드는 **수단**뿐이다 — 거절이 아니라 **두 분기가 모두 읽는다**는 사실이
> 그 일을 한다. 근거:
> [`anthropic-sampling-capabilities.md`](anthropic-sampling-capabilities.md) §7, 백로그는
> [`../../backlog/spring-boot-starter-open-items.md`](../../backlog/spring-boot-starter-open-items.md) B-21.

반례 둘이 같은 기준으로 답이 나오고, 둘 다 §9.3 이 비워 둔 그 자리로 간다.

| 언젠가 노출한다면 | 어디로 | 어느 조건에 걸려서 |
|---|---|---|
| `responsesApiEnabled` | `aimon.llm.openai.responses-api-enabled` | **이름**이 OpenAI 의 엔드포인트다 |
| `temperature` · `topP` · 두 penalty | `aimon.llm.<provider>.*` | **뜻**이 벤더마다 다르다 — §9.3 의 원래 사유 그대로 |

(둘 다 이 런의 범위 밖이다 — O-B.)

백로그 항목은 **닫지 않고 다시 연다**(번호 재사용 금지 규칙에 따라 B-21 그대로). 기록할 것은 세 줄이다:
해소 사유였던 관측("`AimonProperties.Llm` 에는 다섯 개뿐이고 나눌 것이 없다")이 무효가 되었다는 것,
두 갈래 기준, 그리고 그 기준을 처음 적용한 결과.

---

## 3. 기각한 대안

| # | 대안 | 왜 기각했나 |
|---|---|---|
| **R1** | **`modelCapabilitiesAs: gpt-5` — capability alias.** 배포 이름을 내장 표의 이름으로 해석하게 한다. [`openai-model-capabilities.md`](openai-model-capabilities.md) §7 O-8 의 **추천안**이었다 | 이슈 #46 이 명시적으로 반대 방향을 요구한다("names a model and **its capabilities**"). 더 근본적으로 alias 는 **내장 표에 있는 모델로만** 매핑할 수 있어서, 표가 아직 모르는 모델(새 gpt 계열, 사설 모델, 이 저장소가 측정하지 않은 o-시리즈 변종)을 가진 배포는 여전히 닿지 못한다. 그리고 alias 는 "우리 표가 이 모델에 대해 옳다" 는 신뢰를 요구하는데, 이 표는 **round 6 에서 실측으로 두 행이 뒤집힌** 표다. O-8 의 추천은 "vendor fact 를 운영자 yaml 에 복사하지 않는다" 라는 좋은 논거였으나, 그 논거는 내장 표에 **있는** 모델에만 성립한다 |
| **R2** | **alias 와 full entry 둘 다 제공** | 사용자 개념이 둘이 되고, 둘이 동시에 지정될 때의 우선순위라는 세 번째 규칙이 생긴다. alias 는 full entry 위에 나중에 얹을 수 있는 순수 추가(설탕)이므로, 먼저 표현력을 갖춘 쪽을 깐다 |
| **R3** | **리스트 형태** `modelCapabilities: [{model: …, …}]` | §2.2 의 1번. 스타터의 `AimonDocumentedPropertiesTest` 가 `List<X>` 로 내려가지 않아 문서 가드가 이 트리에 대해 **눈이 먼다**(`:569-577` 실측). 또 중복 모델 이름이 표기법 차원에서 가능해진다 |
| **R4** | **레이블 키 + `model:` 필드** (`prod: {model: prod-assistant, …}`) | 점 있는 이름 문제(O-A)를 원천 회피하지만, 한 사물에 이름이 둘이 되고 두 항목이 같은 모델을 가리키는 상태가 만들어진다. `aimon.agents.<ref>.bundle` 이 "생략하면 맵 키와 같다" 로 이 하이브리드를 이미 갖고 있으나, 거기서는 ref 와 bundle 이 **정말로 다른 것**(같은 번들을 여러 ref 로 굴린다)이다. capability 에는 그런 다대일이 없다. **O-A 가 대괄호로 풀리지 않으면 이 대안이 fallback 이다** |
| **R5** | **키 안에 `*` 로 prefix 표기** (`gpt-5*:`) | Boot 의 맵 키 정규화가 대괄호 밖의 비영숫자를 제거하므로 `*` 가 조용히 사라진다. 게다가 두 표면에만 존재하는 미니 언어를 만든다 |
| **R6** | **prefix 를 지원하되 길이 내림차순으로 등록해 longest-match 를 흉내낸다** | 코어의 매칭 규칙(등록 순서 first-match)과 **다른** 세 번째 규칙이 설정 표면에만 생긴다. 그리고 그렇게 해도 내장 prefix 를 이기지 못한다(§2.2) |
| **R7** | **설정이 내장 표를 대체한다** (`builder()` 에서 시작) | 이슈가 확장을 요구하고, 대체는 한 줄만 적은 운영자에게서 나머지 네 행을 조용히 빼앗는다 |
| **R8** | **다섯 필드를 전부 필수로** | §2.3 의 2·4번. 모르는 칸을 채우게 해 틀린 답을 제조하고(특히 round-trip → 404), 여섯 번째 필드가 생기는 날 모든 설정을 깨뜨린다 |
| **R9** | **`lowestReasoningEffort` 를 `String` 으로 받아 공유 번역기가 파싱** | 두 표면의 메시지를 통일할 수 있지만, 스타터에서 손으로 쓴 힌트가 필요해져 `enumSelectorsCarryTheirValuesInTheType` 의 불변식을 깬다. 코어 enum 을 프로퍼티 타입으로 쓰는 선례(`MemoryInjectionMode`)가 이미 있다 |
| **R10** | **번역기를 `aimon-bootstrap` 에 둔다** | §2.1. `LlmClient` 는 두 표면 모두 스택 **밖에서** 만들어지므로 스택 조립 모듈의 소관이 아니고, 코어에 두어야 `aimon-llm-openai` 테스트가 같은 번역기를 통과한 registry 로 raw-필드 단언을 할 수 있다 |
| **R11** | **번역 로직을 두 표면에 각각 인라인** (새 코어 타입 없음) | 규칙이 넷(defaulting · 확장 · exact 등록 · 이름 검증)인데 그것이 두 곳에 있으면 어긋남이 조용하다. 그리고 §7 의 테스트 이음매가 사라져 "yaml → raw 필드 부재" 사슬을 두 동강 낸 채로 남긴다 |
| **R12** | **`ignoreUnknownFields = false` 를 `AimonProperties` 에 켠다** (스타터의 오타를 시끄럽게) | 올바른 방향이지만 `aimon.*` 트리 **전체**의 동작 변경이고, 호스트 애플리케이션이 같은 prefix 아래 자기 키를 두는 배포를 기동 실패로 만들 수 있다. 이 이슈에 얹혀 갈 변경이 아니다 → O-C |
| **R13** | **`OpenAILlmClient.getConfig()` 를 공개해 조립 테스트가 registry 를 확인** | 배포 모듈의 공개 표면을 테스트 편의로 넓힌다. 대신 각 표면에서 `OpenAIConfig` 를 만드는 부분을 package-private 메서드로 추출한다(§4.2·§4.3) — 이 저장소가 `OpenAILlmClient(config, client)` 테스트 생성자에 이미 쓰는 수법이다 |
| **R14** | **항목을 `Map<String, Map<String, String>>` 로 받고 번역기가 리프 이름을 검사한다** — `aimon.credentials` 가 이미 쓰는 모양(`AimonProperties:260`) | 이것은 O-C 를 **트리 전체를 건드리지 않고** 닫는다 — 번역기가 모든 리프를 보므로 스타터에서도 모르는 필드 이름을 프로퍼티 이름으로 거절할 수 있다. 그럼에도 기각하는 이유는 잃는 것이 셋이기 때문이다. ① **메타데이터에서 다섯 리프 이름이 사라진다** — IDE 자동완성이 `supports-sampling-parameters` 를 제안하지 못하고, `lowest-reasoning-effort` 의 enum 후보도 함께 사라진다(R9 가 지키려는 것). ② **문서 가드가 눈을 감는다** — `PropertyTree.recordMapLevels` 는 맵의 값이 또 맵이면 와일드카드를 한 단 더 기록하므로 `aimon.llm.model-capabilities.*.*` 가 되고, 그러면 **가이드에 적힌 어떤 리프 오타도 알려진 키로 통과한다.** 즉 O-C 를 닫는 대가로 §8 의 문서 정확성 가드를 이 서브트리에 대해 끄는 셈이다. ③ boolean·enum 강제 변환이 바인더에서 번역기로 옮겨 온다 — 타입 검사를 직접 짜야 하고, 그 메시지는 Boot 의 것보다 나쁘다. ①②를 잃고 O-C 를 얻는 거래는 남는 장사가 아니라고 판단했다. **다만 이것은 판단이므로 O-C 에 대안으로 함께 적는다** |

---

## 4. 구체적 변경 — 모듈·파일별

### 4.1 `aimon-core` — 새 타입 하나, 팩토리 하나

**새 파일: `modules/aimon-core/src/main/java/at/aimon/core/llm/capability/ModelCapabilityDeclaration.java`**

- 불변 클래스 + 빌더 (CLAUDE.md 의 `record` 금지 규약 그대로. 이 타입은 `GenericTool` 입력 DTO 가
  아니다).
- 필드 다섯: `Boolean supportsSamplingParameters` · `Boolean supportsReasoningEffort` ·
  `Boolean supportsToolsWithReasoning` · `Boolean supportsReasoningTraceRoundTrip` ·
  `ReasoningEffort lowestReasoningEffort`. **박싱 타입인 것이 요점이다** — `null` 이 "선언되지 않음"
  이고, 그것이 §2.3 의 부분 선언을 표현하는 유일한 방법이다. 빌더 세터는 `null` 을 허용하며 javadoc 이
  그 뜻을 명시한다(이 저장소의 통상적 `requireNonNull` 관례에 대한 **의도된 예외**이고, 이유가 타입
  javadoc 에 적힌다).
- 모델 이름은 **담지 않는다.** 이름은 양쪽 표면에서 맵 키이고, 한 사물이 두 자리에 있으면 어긋난다.
- `build()` 가 거절하는 것: **다섯 필드가 모두 `null`** (§2.3 의 "아무것도 선언하지 않은 항목").
- `ModelCapabilities capabilities()` — `ModelCapabilities.builder()` 위에 선언된 세터만 부른 결과.
  빌드 시점에 한 번 만들어 들고 있는다(불변).
- `equals` / `hashCode` / `toString`.

**수정: `InMemoryModelCapabilityRegistry`** — 정적 팩토리 하나 추가.

```java
public static InMemoryModelCapabilityRegistry withDefaultsExtendedBy(
        Map<String, ModelCapabilityDeclaration> declarations)
```

- `builderWithDefaults()` 에서 시작해 각 항목을 `register(name, declaration.capabilities())` — **exact**.
- 거절: 이름이 blank · 이름이 자기 `trim()` 과 다름(공백 오타는 조용한 no-op 을 만든다) ·
  **대소문자를 접었을 때 중복인 두 키**(둘 다 바인딩된 뒤 뒤엣것이 조용히 이긴다) ·
  **값이 `null` 인 항목**.
- **`null` 값을 여기서 막는 것이 요점이다.** CLI yaml 의 `modelCapabilities:` 아래 `prod-assistant:` 만
  적고 본문을 비우면 Jackson 은 그 엔트리를 **키는 유지한 채 값 `null` 로** 바인딩한다. 표면 쪽에서
  막으면 두 곳에 같은 검사가 생기고, 막지 않으면 번역 루프가 `ModelCapabilityDeclaration.build()` 에
  닿기도 전에 **NPE 로 터진다** — 시끄럽기는 하나 운영자에게 아무것도 말해 주지 않는 시끄러움이다.
  여기서 잡고 **F4 와 같은 문장**("아무것도 선언하지 않았다")으로 거절한다: 빈 본문과 빈 객체는
  운영자에게 같은 것이고, 두 개의 다른 메시지를 만들 이유가 없다.
- 이 팩토리를 `withDefaults()` 바로 옆에 두는 이유: "확장이지 대체가 아니다" 를 읽으러 오는 사람이
  보는 자리가 거기다. 클래스 javadoc 의 "Extending the built-in table" 문단에 설정 경로를 한 줄 잇는다.
- **`ModelCapabilityRegistry` · `ModelCapabilities` · 기존 빌더 메서드는 한 글자도 바뀌지 않는다.**

> **주의(범위 밖):** 같은 파일 javadoc `:45` 근처의 *"The o-series is deliberately **not** in the
> built-in table"* 은 지금 틀려 있다(`:128-136` 이 `o1`/`o3`/`o4` 를 등록한다). TASK.md 가 이 런에서
> 고치지 말라고 했다(#48 스윕과 충돌). **건드리지 않고 `HANDOFF.md` 에만 남긴다.**

> **넘긴 대로 #48 이 고쳤다 (병합 시점 정정).** 그 문단은 이제 *"The o-series is in the table"* 로 시작하고
> prefix 세 행과 exact 여덟 행을 함께 기술한다. 두 런을 충돌시키지 않으려던 판단이 그대로 맞았고, 위
> 문단은 그 판단의 기록으로 남는다 — **지금 트리에 대한 서술로 읽으면 안 된다.**

### 4.2 `aimon-cli`

| 파일 | 변경 |
|---|---|
| `config/ModelCapabilityConfig.java` **(새 파일)** | 가변 POJO. 필드 다섯(위와 같은 박싱 타입 + `ReasoningEffort`), getter/setter, `equals`/`hashCode`/`toString`. **`@JsonProperty` 를 붙이지 않는다** — 바로 옆의 `LlmProviderConfig` 가 애노테이션 없이 빈 이름을 그대로 쓴다. (`MemoryConfig` 는 붙이지만 그것은 그 클래스의 관례다. **주변 코드와 같은 모양**을 고른다.) `JavadocType` 이 public 타입의 클래스 javadoc 을 요구하므로 클래스 주석은 필수 |
| `config/LlmProviderConfig.java` | `private Map<String, ModelCapabilityConfig> modelCapabilities = new LinkedHashMap<>();` + getter/setter, `equals`/`hashCode`/`toString` 에 포함. `toString` 은 `apiKey` 를 빼는 기존 방침을 유지하되 capability 는 싣는다(비밀이 아니다) |
| `config/CliConfigLoader.java` | ① 매퍼를 `JsonMapper.builder(new YAMLFactory()).enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build()` 로 (§2.5). ② `resolveEnvironmentVariables` 가 `modelCapabilities` 의 **맵 키**에서도 `${VAR}` 를 푼다 — `model` 이 이미 풀리므로, 풀지 않으면 `model: ${MODEL}` 을 쓰는 배포는 자기 모델을 서술할 수 없고 그 실패가 원래의 400 이다 |
| `factory/LlmClientFactory.java` | ① `createOpenAIClient` 가 선언이 있을 때만 `builder.modelCapabilityRegistry(...)` 를 부른다(비어 있으면 기본값 유지). ② 그 조립을 **package-private `OpenAIConfig openAiConfig(LlmProviderConfig)`** 로 추출해 테스트가 잡을 수 있게 한다(R13). ③ 코어가 던지는 `IllegalArgumentException` 을 `ConfigurationException` 으로 감싸되 **yaml 키 경로**(`llm.modelCapabilities.<model>`)를 메시지에 싣는다 — `validateModel` 의 선례("메시지는 사용자가 고쳐야 하는 yaml 키를 가리켜야 한다")를 그대로 따른다. ④ `createAnthropicClient` 가 선언이 비어 있지 않으면 거절한다(§2.7) |
| `src/main/resources/default-config.yaml` | 주석 처리된 예제 블록. `memory:` 블록이 이미 그 형식이므로 같은 모양으로 — 게이트웨이 시나리오를 한 문장으로 설명하고 다섯 키를 전부 보인다 |

### 4.3 `aimon-spring-boot-starter`

| 파일 | 변경 |
|---|---|
| `autoconfigure/AimonProperties.java` | ① `Llm` 에 `private Map<String, ModelCapabilityProperties> modelCapabilities = new LinkedHashMap<>();` + 접근자. ② **중첩 static 클래스 `ModelCapabilityProperties`** — 반드시 `AimonProperties` 의 중첩 클래스여야 한다. `AimonDocumentedPropertiesTest.PropertyTree.isNested` 가 `AimonProperties$` 로 시작하는 타입만 내려가고(`:697-699`), 그렇지 않으면 문서 가드가 이 서브트리에 눈이 먼다. 이름에 `Properties` 접미어를 붙이는 것은 `at.aimon.core.llm.capability.ModelCapabilities` 와 이름이 정면으로 겹치는 것을 피하기 위해서다 — 이 모듈이 그 코어 타입을 함께 다루므로, 같은 이름의 중첩 타입은 읽는 사람과 import 를 동시에 헷갈리게 한다. (**옆의 `SessionProperties` 도 접미어를 쓰지만 이유가 다르다** — 그 클래스의 javadoc(`:1176-1181`)이 적듯 맨 이름 `Session` 이 **프로젝트 전역에서 금지**되어 ArchUnit 이 막기 때문이다. 결론은 같고 근거는 같지 않다.) ③ 상수 `LLM_MODEL_CAPABILITIES = PREFIX + ".llm.model-capabilities"`. ④ `afterPropertiesSet` 에 `validateLlm()` 추가 — **규칙을 다시 적지 않고 `InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(...)` 를 실제로 불러 보고 결과를 버린다.** `IllegalArgumentException` 을 잡아 `LLM_MODEL_CAPABILITIES` 와 문제의 모델 이름을 실은 `IllegalStateException` 으로 다시 던진다(§2.1). 자리는 `validateMemory` 계열과 같고(빈이 만들어지기 전, 프로퍼티 이름으로), 판정은 코어 것 그대로다 |
| `autoconfigure/AimonLlmAutoConfiguration.java` | ① `OpenAiConfiguration` 이 선언이 있을 때 `modelCapabilityRegistry(...)` 를 넘긴다. ② 그 조립을 **`OpenAiConfiguration` 안의** package-private `static OpenAIConfig openAiConfig(AimonProperties.Llm llm)` 로 추출한다(R13). **바깥 클래스에 두면 안 된다** — 자세한 이유는 표 아래. ③ `AnthropicConfiguration` 이 선언이 비어 있지 않으면 `IllegalStateException` — 그 분기가 **실제로 도는 순간**에만 거절한다. 이 파일의 javadoc 이 `requireApiKey` 에 대해 적어 둔 규칙(*"answer depends on a bean"*)의 적용이다: 앱이 자기 `LlmClient` 빈을 주면 어느 분기도 돌지 않고, 그때 무언가를 요구하는 것은 유효한 설정을 기동 실패로 만드는 일이다 |
| `resources/META-INF/additional-spring-configuration-metadata.json` | **손대지 않는다.** enum 은 프로세서가 `type` 으로 기록하므로 힌트가 필요 없고, 손으로 쓰면 `enumSelectorsCarryTheirValuesInTheType`(*"only the provider needs hand-written hints"*)가 깨진다 |

#### `openAiConfig(...)` 는 반드시 중첩 클래스 안에 둔다

바깥 `AimonLlmAutoConfiguration` 에 두면 **Anthropic 만 담은 배포가 기동하지 못한다.** 두 벤더 모듈은
`compileOnly` 이고, 이 파일의 javadoc(`:26-31`)이 그 배치의 안전성을 이렇게 설명한다 — 각 벤더는
`@ConditionalOnClass` 가 걸린 중첩 `@Configuration` 안에 살고, *"Spring reads these conditions from
bytecode: the nested class is inspected, found inapplicable, and skipped **without the classloader ever
being asked for** `AnthropicLlmClient`"*. 그 보호는 **바이트코드로 읽히는 한**에서만 성립한다.

반환 타입이 `OpenAIConfig` 인 메서드를 바깥 클래스에 두면 그 타입이 바깥 클래스의 **선언된 메서드
서술자**에 들어간다. 스프링은 설정 클래스 빈을 후처리하면서 `getDeclaredMethods()` 를 부르고, 그
호출은 반환 타입을 **로드한다**(private 메서드도 포함된다). `aimon-llm-openai` 가 없는 배포에서는
그 자리에서 `NoClassDefFoundError` 가 난다 — 기존 `requireApiKey` · `requireModel` 이 바깥 클래스에
있으면서도 안전한 이유는 그 시그니처에 벤더 타입이 하나도 없기 때문이다.

`OpenAiConfiguration` 안에 두면 그 클래스 자체가 `@ConditionalOnClass(OpenAILlmClient.class)` 뒤에
있으므로 같은 보호를 받는다. **현재 back-off 테스트는 이것을 잡지 못한다** — `AimonAutoConfigurationTest`
의 `FilteredClassLoader` 는 `AnthropicLlmClient` 만 가린다(`:152`). 그래서 §7.4 에 **`OpenAILlmClient`
를 가리는 대칭 케이스**를 추가한다.

### 4.4 `aimon-llm-openai`

**변경 없음.** `OpenAIConfig.Builder.modelCapabilityRegistry(...)` 는 그대로 호출되기만 한다.
테스트만 는다(§7).

---

## 5. 바뀌는 데이터·인터페이스 모양

### 5.1 새 공개 타입 (추가적, 깨지는 것 없음)

```java
package at.aimon.core.llm.capability;

public final class ModelCapabilityDeclaration {

    public static Builder builder();

    public Optional<Boolean> supportsSamplingParameters();      // 이하 넷 동일
    public Optional<ReasoningEffort> lowestReasoningEffort();
    /** 선언되지 않은 필드가 {@link ModelCapabilities#unknown()} 의 값을 갖는, 이 선언의 실체. */
    public ModelCapabilities capabilities();

    public static final class Builder {
        /** @param value {@code null} 이면 "선언되지 않음" — 그 필드는 unknown() 의 값을 갖는다. */
        public Builder supportsSamplingParameters(Boolean value);
        // ... 넷 더
        /** @throws IllegalArgumentException 다섯 필드가 모두 선언되지 않았을 때 */
        public ModelCapabilityDeclaration build();
    }
}
```

```java
// InMemoryModelCapabilityRegistry 에 추가
public static InMemoryModelCapabilityRegistry withDefaultsExtendedBy(
        Map<String, ModelCapabilityDeclaration> declarations);
```

### 5.2 설정 표면 (순수 추가)

| 표면 | 키 | 타입 | 기본값 |
|---|---|---|---|
| CLI | `llm.modelCapabilities` | `Map<String, ModelCapabilityConfig>` | 빈 맵 |
| CLI | `llm.modelCapabilities.<model>.supportsSamplingParameters` 외 3 | `Boolean` | 미선언 |
| CLI | `llm.modelCapabilities.<model>.lowestReasoningEffort` | `ReasoningEffort` | 미선언 |
| 스타터 | `aimon.llm.model-capabilities` | `Map<String, ModelCapabilityProperties>` | 빈 맵 |
| 스타터 | `aimon.llm.model-capabilities.<model>.supports-sampling-parameters` 외 3 | `Boolean` | 미선언 |
| 스타터 | `aimon.llm.model-capabilities.<model>.lowest-reasoning-effort` | `ReasoningEffort` | 미선언 |

**"미선언" 은 문서에 기본값으로 적지 않는다** — `AimonDocumentedPropertiesTest.everyStatedDefaultMatchesTheField`
는 필드 초기값과 대조하므로, `null` 필드에 "기본값 false" 같은 문장을 붙이면 실패한다. 문서는 "선언하지
않으면 `ModelCapabilities.unknown()` 의 값" 이라고 쓰고, 그 값이 무엇인지는 별도 표로 보인다.

### 5.3 기존 동작은 바뀌지 않는다

**모든 변경이 순수 추가다.** `CliConfigLoader` 의 매퍼에 켜는
`ACCEPT_CASE_INSENSITIVE_ENUMS` 조차 오늘의 설정을 하나도 건드리지 않는다 — `at.aimon.cli.config` 에
enum 으로 바인딩되는 필드가 **하나도 없기 때문**이다(§2.5 의 실측). 그 스위치가 넓히는 것은 이 작업이
새로 만드는 `lowestReasoningEffort` 하나뿐이므로, **그것은 새 키의 성질이지 별도의 동작 변경이 아니다.**

따라서 CHANGELOG 는 이 스위치를 독립 항목으로 세우지 않는다. 새 키를 설명하면서 *"값은 대소문자를
가리지 않는다"* 로 적는다 — 이 작업이 존재하는 이유가 CHANGELOG 의 거짓 문장 하나를 없애는 것인데,
그 자리에 두 번째 거짓 문장("기존 설정의 동작이 넓어졌다")을 넣을 수는 없다.

---

## 6. 실패 모드

| # | 상황 | 처분 |
|---|---|---|
| **F1** | 모르는 필드 이름 — CLI | `FAIL_ON_UNKNOWN_PROPERTIES` → `ConfigurationException("Invalid configuration structure in: …")`. 자동 |
| **F2** | 모르는 필드 이름 — 스타터 | 바인딩되지 않는다. **필드가 하나뿐인 항목이면 §2.3 의 "0개 선언" 규칙에 걸려 시끄럽게 실패**하고, 메시지가 철자 확인을 지시한다. 둘 이상 중 하나만 틀린 경우는 조용하다 → **O-C 로 공표** |
| **F3** | 잘못된 `lowestReasoningEffort` 값 | 두 표면 모두 바인더가 수용값을 실어 기동 실패 |
| **F4** | 항목이 아무것도 선언하지 않음 (`prod-assistant: {}`) | 거절. 이유: `unknown()` 을 등록하는 것은 선언하지 않은 것과 구별되지 않는데 운영자는 무언가를 한다고 믿는다 |
| **F4′** | **항목의 본문이 아예 비어 있음** — CLI yaml 의 `prod-assistant:` 다음 줄이 없다 | Jackson 은 키를 유지한 채 값 `null` 을 넣는다. 코어 팩토리가 **F4 와 같은 문장**으로 거절한다(§4.1). 여기서 잡지 않으면 번역 루프가 `build()` 에 닿기 전에 **NPE** 로 터진다 — 시끄럽지만 운영자에게 아무것도 말하지 않는 시끄러움이다. 스타터에서 같은 입력이 `null` 값을 만들지 전 필드가 `null` 인 객체를 만들지는 바인더에 달렸는데, **두 경우 다 같은 규칙이 잡으므로 어느 쪽이든 결과가 같다** |
| **F5** | 빈 이름 / 앞뒤 공백이 붙은 이름 | 거절. 공백이 붙은 이름은 절대 매치되지 않는 조용한 no-op 이다 |
| **F6** | 대소문자만 다른 두 키 (`Prod-Assistant` + `prod-assistant`) | **§11 D-2 에서 정정됨 — 이 칸의 뒷절반이 틀렸다. 두 표면 모두에서 발화한다.** Boot 는 맵 키의 케이스를 접지 않으므로 두 키가 합쳐지지 않고 둘 다 도착하며, `namesDifferingOnlyInCaseAreRefused` 가 대괄호 **없이** 그것을 증명한다. 아래는 원래 서술이다 → **CLI 에서만 발화한다.** Jackson 은 두 키를 그대로 두므로 registry 단계에서 뒤엣것이 조용히 이긴다 → 거절. ~~**스타터에서는 이 검사가 절대 발화하지 못한다** — Boot 가 대괄호 없는 세그먼트를 소문자로 접으므로 두 키가 **바인딩 시점에 한 항목으로 합쳐진다**(§2.2). 그쪽의 실패 모드는 "하나가 조용히 이긴다" 가 아니라 "두 반쪽 선언이 조용히 합쳐진다" 이고, 검사로 잡을 수 없다.~~ 대괄호로 감싼 두 키(`"[Prod]"` + `"[prod]"`)도 물론 발화한다 |
| **F7** | provider 가 openai 가 아닌데 선언이 있음 | 그 분기가 실제로 도는 순간 거절 (CLI `createAnthropicClient`, 스타터 Anthropic 분기). *"설정했는데 안 읽히는 것이 가장 나쁘다"* |
| **F8** | `provider: none` 또는 앱이 자기 `LlmClient` 빈을 준 스타터 배포에 선언이 있음 | **감지하지 않는다.** 어느 분기도 돌지 않고, 그때 무언가를 요구하는 것은 유효한 설정을 기동 실패로 만드는 일이다(`requireApiKey` 의 javadoc 이 세운 규칙). 한계로 문서화 → O-D |
| **F9** | 이름이 내장 prefix 와 겹침 | 정의된 동작이다 — exact 가 이기고, **그 이름 하나만** 이긴다(§2.4). 문서와 테스트 양쪽에 적는다 |
| **F10** | 점 있는 모델 이름을 스타터 맵 키로 (`gpt-5.7-x`) | 대괄호 표기(`"[gpt-5.7-x]"`)를 문서화하고 **테스트로 못 박는다**. 대괄호로도 안 되면 스타터 모양을 R4(레이블 키 + `model` 필드)로 돌린다 → **O-A** |
| **F11** | 선언이 `supportsReasoningTraceRoundTrip: true` 인데 게이트웨이에 `/v1/responses` 가 없음 | 404. **운영자가 스스로 켠 것**이고 fail-open 기본값(false)은 그리로 가지 않는다. 다만 끄는 스위치(`responsesApiEnabled`)가 여전히 프로그램 전용이라는 것을 문서에 명시 → O-B |
| **F12** | 선언은 맞는데 `model:` 이 다른 이름 | 선언은 죽은 항목이 된다. 조용하다. **감지하지 않는다** — 한 배포가 여러 모델을 부를 수 있고(서브에이전트 frontmatter 의 `model`), "선언된 이름 중 아무것도 `llm.model` 과 같지 않다" 를 에러로 만들면 정당한 설정을 거절한다. 문서에서 "이름은 `model:` 이 부르는 그 이름" 이라고 못 박는 것으로 대신한다 |

**부분 적용된 표는 존재하지 않는다.** 위의 거절은 전부 registry 를 만들기 전에 일어나고, 어느 한 항목이
거절되면 팩토리는 아무것도 돌려주지 않고 던진다 — "절반만 적용된 capability 표" 라는 상태가 만들어지지
않는다는 뜻이지, 등록이 실패하지 않는다는 뜻이 아니다(F4~F6 이 실패다).

---

## 7. 테스트 전략

### 7.0 인수 조건의 사슬 — 어디서 끊기고, 무엇이 이음매인가

인수 조건은 *"yaml 선언 → `temperature` 가 **원시 요청 필드의 부재로** 확인된다"* 를 요구한다.
**한 테스트가 이 사슬 전체를 덮을 수 없고, 그 이유는 빌드 구조다.**

- 원시 필드 단언(`params._temperature()` 이 `JsonMissing`)에는 `com.openai.core.JsonMissing` 가 필요한데,
  `aimon-llm-openai` 는 `libs.openai.client` 를 `implementation` 으로 선언하므로 그 SDK 는
  `aimon-cli` / 스타터의 **컴파일 클래스패스에 없다.**
- 요청을 가로채려면 SDK 클라이언트를 주입해야 하는데 그 생성자
  (`OpenAILlmClient(OpenAIConfig, OpenAIClient)`)는 **package-private** 이다.

그래서 사슬을 둘로 나누되 **이음매를 공유 타입에 둔다.**

```
[CLI/스타터 테스트]  yaml/프로퍼티  →  Map<String, ModelCapabilityDeclaration>  →  OpenAIConfig.getModelCapabilityRegistry()
                                                    ▲ 이음매
[aimon-llm-openai 테스트]   InMemoryModelCapabilityRegistry.withDefaultsExtendedBy(같은 선언)  →  _temperature() == JsonMissing
```

이음매가 `ModelCapabilities` 값이 아니라 **설정 경로가 실제로 쓰는 번역기**라는 점이 중요하다.
손으로 만든 capability 로 이었다면 번역기의 defaulting 이 틀려도 두 절반이 다 초록일 수 있다.

### 7.1 `aimon-core`

| # | 테스트 | 단언 |
|---|---|---|
| **T-C1** | `ModelCapabilityDeclarationTest` | 한 필드만 선언한 declaration 의 `capabilities()` 가 `ModelCapabilities.builder().supportsSamplingParameters(false).build()` 와 **같다**(즉 나머지 넷이 `unknown()` 값) · 다섯 필드가 각각 round-trip · 전부 미선언이면 `build()` 가 던진다 · `equals`/`hashCode` |
| **T-C2** | `…RegistryTest`(확장) — 내장 5행 보존 | `withDefaultsExtendedBy(Map.of("prod-assistant", d))` 가 `gpt-5-chat-latest` · `gpt-5.6-terra` · `o1-x` · `o3-mini` · `o4-mini` 에 대해 `withDefaults()` 와 **같은 답**을 낸다 (5행이 살아 있다는 인수 조건) |
| **T-C3** | 우선순위 | 사용자 `gpt-5-nano` 선언이 내장 `gpt-5` prefix 를 이긴다 · **`gpt-5-mini` 는 여전히 내장 prefix 가 답한다**(§2.4 의 "가족을 덮지 않는다" 를 못 박는다) |
| **T-C4** | **전사 불변식** | 내장 5행 각각을 declaration 으로 옮겨 적었을 때 `capabilities()` 가 그 행의 `ModelCapabilities` 와 같다 — "설정 표면은 내장 표의 어떤 행이든 표현할 수 있다"(§2.4) |
| **T-C5** | 대소문자 | `Prod-Assistant` 로 등록하고 `prod-assistant` / `PROD-ASSISTANT` 로 조회하면 맞는다 |
| **T-C6** | 거절 | blank 이름 · 앞뒤 공백 이름 · 케이스만 다른 중복 키 → 각각 던지고, 메시지가 문제의 이름을 싣는다 |

### 7.2 `aimon-llm-openai` — `OpenAILlmClientModelCapabilityTest` 확장

| # | 단언 |
|---|---|
| **T-O1** | **이슈의 재현 시나리오.** `withDefaultsExtendedBy` 로 만든 registry + `model("prod-assistant")` + `LlmModel` 이 temperature 를 설정 → `assertThat(params._temperature()).isSameAs(JsonMissing.of())`. **`JsonNull` 이 아님**을 §6.1 의 어휘로 명시적으로 단언하고, 주석이 그 이유를 적는다 |
| **T-O2** | 같은 registry 에서 `gpt-5-mini` (선언되지 않은 이름) 는 내장 `gpt-5` 행이 답한다 — 확장이 다른 행을 밟지 않는다 |
| **T-O3** | 최소 선언(sampling 하나)만 한 모델은 `supportsReasoningTraceRoundTrip=false` 이므로 **Chat Completions 경로로 간다** (`OpenAILlmClientEndpointSelectionTest` 와 같은 방식). §2.3 의 "최소 선언이 안전한 쪽으로 떨어진다" |

### 7.3 `aimon-cli`

| # | 테스트 | 단언 |
|---|---|---|
| **T-L1** | `CliConfigLoaderTest` | `llm.modelCapabilities` 블록이 있는 yaml 이 `Map<String, ModelCapabilityConfig>` 로 바인딩된다 · 모르는 필드 이름이 `ConfigurationException` 을 낸다 · `lowestReasoningEffort: low` 와 `LOW` 가 **둘 다** 통과한다(매퍼 스위치가 사는 유일한 자리다 — §2.5) · 잘못된 값이 `ConfigurationException` 을 낸다 · 맵 키의 `${VAR}` 가 풀린다 · **본문이 빈 항목(`prod-assistant:` 한 줄)이 NPE 가 아니라 F4 의 메시지로 거절된다** |
| **T-L2** | `LlmClientFactoryTest` | `openAiConfig(config).getModelCapabilityRegistry().resolve("prod-assistant").supportsSamplingParameters()` 가 `false` · 같은 registry 가 `gpt-5.6-terra` 에 대해 내장 행을 그대로 답한다 · 선언이 없으면 registry 가 `withDefaults()` 와 같은 답을 낸다 |
| **T-L3** | 대소문자 (인수 조건) | yaml 에 `Prod-Assistant` 로 적고 `model: prod-assistant` 로 조회 → 맞는다. ~~**인수 조건이 실제로 증명되는 자리는 여기다**~~ — Jackson 이 맵 키를 글자 그대로 넘기므로 registry 의 케이스 접기가 진짜로 시험된다(~~스타터 쪽은 그렇지 않다, T-S3′~~). **§11 D-2 에서 정정됨:** 스타터도 키의 케이스를 보존하므로 이 조건은 **양쪽에서** 증명된다. 이 테스트가 유일한 자리라는 서술만 틀렸고, 테스트 자체는 그대로 유효하다 |
| **T-L4** | 거절 | `provider: anthropic` + 선언 → `ConfigurationException`, 메시지가 `llm.modelCapabilities` 를 이름으로 부른다 · 빈 항목 → 거절, 메시지가 yaml 키 경로를 싣는다 |

### 7.4 `aimon-spring-boot-starter`

| # | 테스트 | 단언 |
|---|---|---|
| **T-S1** | `AimonPropertiesValidationTest`(확장) | `aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameters=false` 가 바인딩된다 · 빈 항목이 `LLM_MODEL_CAPABILITIES` 를 이름으로 부르며 기동 실패 · 잘못된 `lowest-reasoning-effort` 가 기동 실패 |
| **T-S2** | `AimonLlmAutoConfiguration` 단위 | `openAiConfig(llm).getModelCapabilityRegistry()` 가 선언을 답하고 내장 5행을 보존한다 · Anthropic 분기 + 선언 → `IllegalStateException` |
| **T-S3** | **O-A 를 닫는 테스트** | 맵 키 `"[gpt-5.7-x]"` 가 그 이름 그대로 바인딩된다 · 대괄호 없는 `prod-assistant` 도 바인딩된다. **이 테스트가 빨간색이면 스타터 모양을 R4 로 되돌린다** |
| **T-S3′** | 스타터의 대소문자 — **무엇을 증명하는지 정확히** | **§11 D-2 에서 정정됨 — 이 행의 전제가 틀렸으므로 두 갈래로 나눌 필요가 없었다.** 대괄호 없는 `Prod-Assistant` 는 **접히지 않고 그대로 도착하며**, 그래서 `prod-assistant` 로 조회하는 것이 registry 의 접기를 진짜로 시험한다. 구현된 테스트는 하나다(`mapKeyCaseIsPreservedAndTheRegistryFoldsIt`). 아래는 원래 계획이다 → ~~대괄호 없는 `Prod-Assistant` 는 Boot 가 접어 `prod-assistant` 로 **도착한다**(§2.2). 그러므로 이 케이스를 "registry 의 케이스 접기가 동작한다" 로 적으면 **아무것도 증명하지 못한다** — 애초에 소문자로 도착했기 때문이다. 단언은 두 개로 나눈다: ① 대괄호 없는 대문자 키가 소문자 이름으로 등록된다(Boot 의 정규화를 기록) ② **대괄호로 감싼 `"[Prod-Assistant]"`** 는 케이스가 보존된 채 등록되고 `prod-assistant` 로 조회하면 맞는다(registry 의 접기가 여기서 시험된다)~~ |
| **T-S3″** | 벤더 모듈 back-off 의 대칭 케이스 | `FilteredClassLoader(OpenAILlmClient.class)` 로 OpenAI 를 가린 컨텍스트가 **기동한다**(`aimon.llm.provider=anthropic`). §4.3 의 "`openAiConfig` 는 중첩 클래스 안에" 를 지키는 테스트이고, 기존 back-off 테스트는 반대쪽만 가리므로(`AimonAutoConfigurationTest:152`) 이 케이스가 없으면 회귀가 보이지 않는다. `NoClassDefFoundError` 가 스택에 없음을 함께 단언한다(기존 테스트와 같은 방식) |
| **T-S4** | `AimonDocumentedPropertiesTest` | **수정 없이 통과해야 한다.** 새 키가 가이드에 적히므로 walker 가 이 서브트리를 실제로 걸었다는 증거가 된다. `thePropertyTreeWasWalked` 에 `aimon.llm.model-capabilities.*.supports-sampling-parameters` 한 줄을 더해 walker 가 눈감지 않았음을 못 박는다 |
| **T-S5** | `AimonConfigurationMetadataTest` | **수정 없이 통과해야 한다** — 특히 `enumSelectorsCarryTheirValuesInTheType`(손으로 쓴 힌트는 provider 하나뿐) |

### 7.5 게이트

`./gradlew format` → `./gradlew checkAll`. 구현자 주의:

- `LineLength` 120, import 순서(java, javax, jakarta, org, com, 빈 줄, 프로젝트).
- `JavadocType` 이 **public 타입의 클래스 javadoc 을 요구**한다(새 클래스 셋 모두).
  `MissingJavadocMethod` 는 의도적으로 꺼져 있으므로 getter 마다 javadoc 을 쓸 필요는 없다.
- `aimon-cli` 의 `checkstyle { maxWarnings = 21 }` — 새 코드가 이 수를 올리지 않는지 확인한다.
  올린다면 baseline 을 올리지 말고 경고를 없앤다(그 파일의 주석이 그렇게 지시한다).
- `HideUtilityClassConstructor` / `FinalClass` — 유틸 클래스를 새로 만들 경우.

---

## 8. 문서 — 전수

| 파일 | 무엇을 |
|---|---|
| `CHANGELOG.md` `[Unreleased]` | **`:56` 의 *"A CLI deployment in that state has no yaml key for this yet; that is a config-surface decision left to its own issue"* 를 고친다.** 이 작업이 그 문장을 거짓으로 만든다. 대신 두 키를 보이고, 남은 프로그램 전용 노브(`responsesApiEnabled`)는 여전히 열려 있다고 적는다. **매퍼 스위치를 별도 항목으로 세우지 않는다** — 그것이 넓히는 것은 새 키 하나뿐이므로(§5.3) *"값은 대소문자를 가리지 않는다"* 로 새 키 설명 안에 넣는다. 기존 설정의 동작이 넓어졌다고 쓰면 **이 작업이 없애려는 것과 같은 종류의 거짓 문장**이 된다 |
| `docs/design/llm/openai-model-capabilities.md` | 세 자리가 거짓이 된다 — **`:282`**(§2.6 표의 마지막 행 "no — `LlmClientFactory.createOpenAIClient` has no seam"), **`:694`**(§7 O-8 "no CLI override … I chose not to invent one here" → 닫힘 표시 + 어느 후보가 선택되었고 **왜 추천안 R1 이 뒤집혔는지**), **`:986`**(§9.5 "The two round-1 gaps stay open" → 둘 중 하나가 닫혔다). 이 저장소의 관례대로 **원문을 지우지 않고 정정 표시**로 남긴다 |
| `docs/design/llm/openai-responses-path.md` | **`:553` F-2** 가 부분적으로 거짓이 된다("한 config 이슈, 세 개의 rider 가 아니라" — 셋 중 하나만 닫혔다). 남은 둘을 명시 |
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` **+ `.en.md`** | §4.1 이 *"각 빌더는 … `apiKey`, `model`, `timeout`, `baseUrl` 을 주입한다"* 라고 적는다 — 목록이 낡는다. 게이트웨이 시나리오와 CLI 키를 여기에 싣는다. `.en.md` 는 번역본이므로 `source_commit` 을 **이번 수정 직전의 정본 커밋**으로 맞추고, 구조(제목·표 행·펜스 개수)를 정본과 일치시킨다 |
| `docs/getting-started/embedding-agent-in-application.md` **+ `.en.md`** | **TASK.md 의 목록에 없지만 필요하다** — §4 "프로퍼티" 의 `aimon:` yaml 블록이 스타터 키를 읽는 사람의 실제 진입점이고, `AimonDocumentedPropertiesTest` 가 스캔하는 파일이다. 스타터 표기(kebab)로 예제를 싣는다. 번역 대상 디렉토리이므로 `.en.md` 규칙 동일 → **O-F** |
| `docs/design/integration/spring-boot-starter.md` | 두 자리. ① **프로퍼티 트리(`:749-756`)의 `llm:` 블록**에 `model-capabilities` 를 넣는다 — `← 선택자` 표시는 **붙이지 않는다**(슬라이스를 고르는 값이 아니다). ② §9.3 의 provider 분할 항목 옆에 §2.7 의 두 갈래 기준을 한 문단으로 적는다. **`:848-855` 의 힌트 표에는 행을 더하지 않는다** — 그 표는 *선택자* 표이고 바로 위 문장이 *"위 트리에서 `← 선택자` 로 표시한 7개"* 라고 개수를 세므로, 여덟 번째 행을 넣으면 그 문장이 거짓이 된다. 새 키가 손으로 쓴 힌트를 **필요로 하지 않는다**는 사실(enum 이라 프로세서가 `type` 에 적는다)은 표가 아니라 ②의 문단에 적는다 |
| `docs/backlog/spring-boot-starter-open-items.md` **B-21** | §2.7. 해소 사유였던 관측이 무효가 되었다는 것 · 새 기준(벤더 개념을 이름에 담는 키만 쪼갠다) · 그 기준의 첫 적용 결과. 번호는 재사용하지 않는다 |
| `modules/aimon-cli/src/main/resources/default-config.yaml` | 주석 처리된 예제 블록 (`memory:` 블록과 같은 모양) |
| `docs/features/llm/llm-provider-development-guide.md` (+`.en.md`) | **선택.** "파라미터를 설정하기 전에 능력을 확인한다" 절에 "이 registry 는 이제 설정에서도 확장된다" 한 줄 크로스레퍼런스. 프로바이더 개발자용 문서이므로 필수는 아니다 |

**번역 규칙:** `design/` 과 `backlog/` 는 번역 대상이 아니다. `getting-started/` 는 대상이다
(`docs/project/documentation-guide.md` §5.1). 번역본을 같은 커밋에서 못 고치면 그 사실을
`HANDOFF.md` 에 적는다 — 번역 때문에 정본 수정을 미루지 않는다.

검증: `python3 scripts/check-doc-links.py` · `check-translation-staleness.py` ·
`check-translation-structure.py`.

---

## 9. 미해결 — TASK.md 에서 풀리지 않은 것

**O-A — 스타터의 맵 키 정규화. 두 갈래이고, 한 갈래만 모양을 되돌릴 수 있다.**
Boot 의 `ConfigurationPropertyName.adapt` 는 대괄호로 감싸지 않은 세그먼트를 **소문자로 접고** 점을
세그먼트 구분자로 읽는다.

- **점 (미확인, 모양을 바꿀 수 있다).** `gpt-5.7-x` 가 `gpt-5` / `7-x` 로 쪼개질 수 있고, 대괄호 표기
  (`"[gpt-5.7-x]"`)가 정답이라고 보지만 **이 런에서 실행해 확인하지 않았다.** T-S3 가 이것을 못 박고,
  **빨간색이면 스타터 모양을 R4(레이블 키 + `model` 필드)로 되돌린다.** 이 설계에서 결과에 따라 모양이
  바뀌는 유일한 항목이므로 구현의 **첫 번째** 작업으로 둔다.
- **대소문자 (확인됨, 모양을 바꾸지 않는다).** 대괄호 없는 `Prod-Assistant` 는 `prod-assistant` 로
  도착한다. 조회는 registry 도 케이스를 접으므로 **어느 쪽이든 맞고**, 그래서 무해하다. 다만 두 가지가
  따라온다: F6 의 중복 검사가 스타터에서는 **발화할 수 없고**(두 키가 바인딩 시점에 합쳐진다), 인수 조건
  *"대소문자 무시가 설정 경로에서도 유지된다"* 를 스타터 테스트로 증명하려 하면 **증명되지 않는다**
  (소문자로 도착한 것을 소문자로 찾는 것이므로). 그 조건은 CLI(T-L3)에서 증명되고, 스타터 쪽은 무엇을
  보이는지 정확히 적은 T-S3′ 로 남긴다.

CLI 는 Jackson 이 키를 글자 그대로 받으므로 두 갈래 다 해당 없다.

> **§11 이 이 항목을 닫았고, 두 갈래 중 하나는 서술 자체가 틀렸다.**
> **점** — 대괄호 표기가 정답임이 실측으로 확인되었다. 스타터 모양은 R4 로 되돌리지 않았고, T-S3
> (`aDottedModelNameNeedsBracketNotation`)가 두 방향을 다 못 박는다. 대괄호 **없는** 점 이름은 이 문단이
> 예상한 "`gpt-5` 아래 `7-x` 로 쪼개진다" 보다 조용하게 실패한다 — 항목이 아예 도착하지 않는다(§11 D-1).
> **대소문자** — *"확인됨"* 이라고 적혀 있으나 **확인되지 않았고 틀렸다.** Boot 는 케이스를 접지 않으므로
> 이 갈래에서 파생시킨 두 결론(F6 이 스타터에서 발화할 수 없다 · 인수 조건이 스타터에서 증명되지 않는다)이
> 둘 다 성립하지 않는다(§11 D-2). 이 항목은 **닫혔다** — 아래 O-B ~ O-E 와 달리 백로그로 올라가지 않는다.

> **이 국면 밖으로 올라갔다 → [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-2.** 아래는 설계 시점의 기록으로 동결된다. 현재 상태와 재검토 트리거는 그 항목이 갖는다.

**O-B — 나머지 두 개의 프로그램 전용 노브.**
[`openai-responses-path.md`](openai-responses-path.md) F-2 는 *"registry override, `responsesApiEnabled`, 샘플링 파라미터 —
세 개의 프로그램 전용 노브. **한 config 이슈이지 세 개의 rider 가 아니다**"* 라고 적었다. 이슈 #46 과
이 런의 TASK.md 는 그중 **registry 하나만** 이름으로 부른다. 셋을 다 닫으면 diff 가 (문서 2개 언어 ×
3키 + 메타데이터 + 테스트) 만큼 커지고 인수 조건 밖으로 나간다. **registry 만 닫고, 나머지 둘의 모양은
여기서 미리 정해 둔다:**
`responsesApiEnabled` 는 §2.7 의 기준상 **벤더 엔드포인트를 이름에 담으므로**
`aimon.llm.openai.responses-api-enabled` / CLI `llm.openai.responsesApiEnabled` 로 내려야 한다.
샘플링 키(`temperature` 등)는 중립이므로 `aimon.llm.*` 에 남는다. 이 결정을 F-2 옆에 적어 두면 다음
사람이 다시 도출하지 않는다. **사람의 판단이 필요하다 — 이 런에서 셋을 다 닫기를 원하는가.**

> **이 국면 밖으로 올라갔다 → [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-1.** 아래는 설계 시점의 기록으로 동결된다. 현재 상태와 재검토 트리거는 그 항목이 갖는다.

**O-C — 스타터에서 모르는 필드 이름을 시끄럽게 만들 수 없다.** 인수 조건
*"잘못된 설정이 조용히 통과하지 않는다 — 양쪽 표면 모두"* 가 스타터 쪽에서 **완전히는 충족되지 않는
자리이므로 명시한다.** 길이 둘 있고 둘 다 기각했다.

- **R12** — `ignoreUnknownFields = false`. `aimon.*` 트리 **전체**의 동작 변경이고, 같은 prefix 아래
  자기 키를 두는 호스트 앱을 기동 실패로 만들 수 있다. 이 이슈에 얹혀 갈 것이 아니다.
- **R14** — 항목을 `Map<String, Map<String, String>>` 로 받아 번역기가 리프를 검사한다. 이것은 트리를
  건드리지 않고 구멍을 닫지만, IDE 자동완성에서 다섯 리프 이름과 enum 후보가 사라지고 **문서 정확성
  가드가 이 서브트리에 눈을 감는다**(리프가 와일드카드가 되므로 가이드의 오타가 통과한다). 근거 전문은
  R14 행에 있다.

남는 것은 부분 완화 둘(§2.5)이고, 가장 흔한 오타(한 필드짜리 항목의 철자)는 잡히지만 **전부는 아니다.**
별도 이슈 후보이며, **이 트레이드는 사람이 뒤집을 수 있는 판단이다.**

> **이 국면 밖으로 올라갔다 → [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-3.** 아래는 설계 시점의 기록으로 동결된다. 현재 상태와 재검토 트리거는 그 항목이 갖는다.

**O-D — 앱이 자기 `LlmClient` 빈을 주거나 `provider: none` 인 스타터 배포의 고아 선언은 감지하지
않는다**(F8). `requireApiKey` 의 javadoc 이 세운 규칙을 따른 것이지만, "설정했는데 안 읽힌다" 를
막겠다는 §2.7 의 약속에는 구멍이다. 두 규칙 중 어느 쪽이 이겨야 하는지는 이 저장소의 유지자가 정할
일이다.

> **이 국면 밖으로 올라갔다 → [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-4.** 아래는 설계 시점의 기록으로 동결된다. 현재 상태와 재검토 트리거는 그 항목이 갖는다.

**O-E — prefix 지원을 나중에 열 것인가, 그 비용은 무엇인가.** 열려면 코어에 "사용자 prefix 를 내장
prefix 보다 앞에 등록하는" 경로가 필요하고, 그것은 `builderWithDefaults()` 의 in-place 교체가 지켜
주던 `gpt-5-chat` 보호와 정면으로 부딪힌다. 설정 쪽 추가는 `match: prefix` 필드 하나로 순수 추가지만,
**코어 쪽이 순수 추가가 아니다.** 소비자가 나타난 뒤에 본다.

**O-F — `embedding-agent-in-application.md`(+`.en.md`) 가 문서 목록에 없다.** TASK.md 의 목록은
"전수" 라고 적혀 있지만 이 파일이 빠져 있고, 이 파일이 스타터 키에 대한 실제 사용자 진입점이며
`AimonDocumentedPropertiesTest` 의 스캔 대상이다. **넣는 쪽으로 설계했다.** 번역본까지 두 파일이
늘어나므로 사람이 잘라도 좋으나, **누락이 아니라 결정이어야 한다.**

**O-G — CLI 쪽 맵 키의 `${VAR}` 해석을 넣기로 했다**(§4.2). `model` 이 이미 해석되므로 대칭이지만
TASK.md 가 요구한 것은 아니다. 잘라도 기능은 성립한다(단 `model: ${MODEL}` 배포는 자기 모델을 서술할
수 없다). 이 설계에서 가장 먼저 잘라도 되는 조각이다.

---

## 10. 발견 사실 — `HANDOFF.md` 로 넘길 것 (이 런에서 고치지 않는다)

1. **`InMemoryModelCapabilityRegistry` javadoc `:45` 근방이 틀려 있다** — *"The o-series is
   deliberately **not** in the built-in table"* 인데 같은 파일 `:128-136` 이 `o1`/`o3`/`o4` 를
   등록한다. 2026-09-09 측정으로 행이 들어가면서 예시 문단만 안 따라온 것으로 보인다. TASK.md 의 지시대로
   **건드리지 않는다** — #48 의 스윕에 속하고 지금 만지면 두 런이 충돌한다.
   **→ #48 이 고쳤다** (두 브랜치를 합치는 시점에 확인). 넘긴 것이 실제로 받아졌다는 기록으로 남긴다.
2. **`openai-model-capabilities.md` §11.5 · §9.5 의 "두 round-1 gaps" 중 하나가 이 작업으로 닫힌다** —
   나머지 하나(o-시리즈 실측)는 #48 이다. **→ 그쪽도 닫혔다**: #48 이 여덟 개 이름을 실측해
   `supportsReasoningTraceRoundTrip` 을 이름 단위로 뒤집었고, 미측정 이름(`o1-pro` ·
   `o4-mini-deep-research`)은 prefix 행에 남겨 `false` 를 유지한다.
3. **[`openai-responses-path.md`](openai-responses-path.md) F-2 의 "한 config 이슈" 는 이 런으로 3분의 1만 닫힌다** (O-B).
4. **`CliConfigLoader` 는 `${VAR}` 를 손으로 나열한 필드에서만 푼다.** `mcp` 와 `llm` 의 세 필드뿐이고
   `memory` 블록은 전혀 풀지 않는다(`memory.dreamer.scorer.embedding.apiKey` 는 `${OPENAI_KEY}` 를
   예제로 싣고 있는데도). 이 작업의 범위 밖이지만 같은 종류의 조용한 실패다.

---

## 11. 구현이 이 설계와 갈라진 자리 — 실측 셋

**구현 후에 쓴 절이다.** §0~§10 은 승인된 설계 그대로이고, 이 절은 그 본문을 **트리에서 측정한 사실이
반증한 세 자리**를 적는다. 셋 다 Spring Boot 바인더의 실제 동작에 관한 것이며, 이 문서의
**결정**(키 모양 · 부분 선언의 기본값 · 확장 관계 · 두 표면의 표기)은 하나도 바뀌지 않았다. 반증된
본문 자리에는 이 절을 가리키는 정정 표시를 달아 두었다 — §2.2 · §2.5 ① · §6 F6 · §7 T-L3 · §7 T-S3′ ·
§9 O-A.

측정 방법: `AimonPropertiesValidationTest` 와 같은 `ApplicationContextRunner` 로 각 입력을 실제로
바인딩해 결과 맵을 찍었다(임시 probe 테스트, 확인 후 삭제하고 그 자리를 정식 테스트로 대체했다).
Spring Boot 3.5.x, 이 저장소의 의존성 그대로, 2026-09-09.

### D-1 (O-A 의 점 갈래) — **닫혔다. 설계 모양 그대로 간다**

§2.2 · §6 F10 · §9 O-A 가 *"확인하지 않았다, 빨간색이면 R4(레이블 키 + `model` 필드)로 되돌린다"* 고
남긴 항목이며, **이 설계에서 결과에 따라 모양이 바뀔 수 있는 유일한 자리**였다. 그래서 구현의 첫 작업으로
측정했다.

| 입력 | 바인딩된 맵 키 |
|---|---|
| `aimon.llm.model-capabilities[gpt-5.7-x].supports-sampling-parameters=false` | `[gpt-5.7-x]` — 점 포함, 그대로 |
| `aimon.llm.model-capabilities.gpt-5.7-x.supports-sampling-parameters=false` (대괄호 없이) | `[]` — **항목이 아예 만들어지지 않는다** |

대괄호가 이름을 보존하므로 **O-A 는 "대괄호가 정답" 쪽으로 닫혔고 스타터 모양은 R4 로 되돌리지 않았다.**

둘째 줄은 설계의 예상보다 조용한 실패다. §2.2 는 *"`gpt-5` 아래 `7-x` 로 쪼개질 수 있다"* 고 적었는데,
실제로는 쪼개진 항목이 `ModelCapabilityProperties` 로 바인딩되지 못하고 **통째로 사라진다.** 그래서
테스트(`aDottedModelNameNeedsBracketNotation`)가 두 줄을 다 못 박고 — 뒷줄의 단언이 그 침묵의 유일한
보고다 — 두 사용자 문서가 대괄호를 **요구**로 적는다(권유가 아니다).

### D-2 — Boot 는 맵 키의 **대소문자를 접지 않는다.** §2.2 의 정규화 표가 틀렸다

설계는 `ConfigurationPropertyName.adapt` 를 근거로 *"대괄호 없는 `Prod-Assistant` 는 `prod-assistant` 로
도착한다"* 고 적고, 그것을 **확인된 갈래**로 분류했다(O-A 의 둘째 불릿: *"대소문자 (확인됨, 모양을 바꾸지
않는다)"*). 확인되지 않았고, 틀렸다.

**실측: `Prod-Assistant` 는 `Prod-Assistant` 로 도착한다.** Boot 의 `MapBinder` 는 맵 키를
`Form.ORIGINAL` 로 되살리므로 케이스가 보존된다 — `aimon.credentials.<profile>` 도 같은 성질이고, 그
사실이 트리 안에 이미 있었다는 것이 이 오독의 값을 더 비싸게 만든다(읽어서 확인할 수 있었다).

파생 결론 셋이 전부 뒤집히고, **전부 더 좋은 쪽으로** 간다.

| 설계가 적은 것 | 실제 |
|---|---|
| F6(대소문자만 다른 두 키)이 스타터에서 **발화할 수 없다** | **발화한다.** 두 키가 합쳐지지 않고 둘 다 도착하므로 중복 검사가 잡는다 — `namesDifferingOnlyInCaseAreRefused` 가 대괄호 **없이** 그것을 증명한다 |
| 인수 조건("대소문자 무시가 설정 경로에서도 유지된다")이 스타터에서 **증명되지 않는다** | **증명된다.** 이름이 대문자로 도착하고 소문자로 조회되므로, 시험되는 접기는 진짜로 registry 의 것이다 |
| T-S3′ 를 두 갈래로 나눠 "무엇을 증명하는지 정확히" 적어야 한다 | 나눌 필요가 없다. 테스트는 하나다 — `mapKeyCaseIsPreservedAndTheRegistryFoldsIt` |

바뀐 것은 **사실의 기록**뿐이다. 코드도 결정도 이 발견 때문에 달라지지 않았다.

### D-3 — 스타터에서 **오타 난 필드 하나뿐인 항목도 조용하다.** §2.5 완화책 ①이 틀렸다

§2.5 는 O-C(스타터에서 모르는 필드 이름을 잡을 수 없다)에 부분 완화 둘을 들었고, ①이 이것이었다 —
*"필드가 하나뿐인 항목의 이름을 잘못 쓰면 그 항목은 0개 선언이 되어 시끄럽게 거절된다."*

**실측: 그 항목은 0개 선언이 되지 않는다. 아예 만들어지지 않는다.**

| 입력 | 바인딩된 맵 |
|---|---|
| `…prod-assistant.supports-sampling-parameter=false` (오타 하나뿐) | `{}` — 항목 없음 |
| `…prod-assistant.supports-sampling-parameters=false` + `…supports-reasoning-effot=true` | `{prod-assistant=…}` — 맞는 플래그만 |

Boot 의 `JavaBeanBinder` 는 리프가 하나도 바인딩되지 않으면 값 인스턴스를 만들지 않으므로, 0개 선언
항목이 검증에 도달하지 않는다. 둘째 줄은 설계가 예측한 대로 조용하다.

**처분: 완화책 ①을 철회하고, 그 자리를 침묵을 기록하는 테스트로 바꿨다.**
`aMisspelledFlagIsSilentInTheStarter` 가 "맵이 빈다" 를 단언하고, 그 주석이 스스로 *"limitation record,
not a guarantee"* 이며 **누가 이 구멍을 닫으면 빨개지는 것이 옳은 결과**라고 적는다. 침묵을 없애는 두 길
(R12 `ignoreUnknownFields=false` · R14 `Map<String,Map<String,String>>`)은 §3 이 이미 저울에 올려 기각했고
여기서 뒤집지 않았다 — 다만 **O-C 의 범위가 설계보다 넓다.** 설계는 "두 필드 중 하나만 오타 난 경우" 만
조용하다고 했는데 **한 필드짜리 항목도 조용하다.** 남는 완화책은 ②(IDE 가 스타터 메타데이터로 모르는
리프를 표시)뿐이고, 코드 주석과 두 사용자 문서가 그렇게 적는다.

그래서 인수 조건 *"잘못된 설정이 조용히 통과하지 않는다 — 양쪽 표면 모두"* 는 **CLI 에서는 완전히,
스타터에서는 값 오류·의미 오류에 대해서만** 충족된다. 모르는 필드 이름은 스타터에서 조용하다 — 설계가
O-C 로 공표한 그 한계이며 범위만 넓어졌다. 현재 상태와 재검토 트리거는
[`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-1.

### 11.1 갈라지지 않은 것 — 셋의 공통점

세 발견 모두 **바인더의 동작**이고, 하나도 이 설계의 결정을 건드리지 않았다. 그 이유는 우연이 아니라
§2.1 이 고른 배치의 결과다 — 규칙(부분 선언의 defaulting · 확장 관계 · exact 등록 · 이름 검증)은
`withDefaultsExtendedBy` 안에 있고 **바인더 바깥**이므로, 바인더가 무엇을 하든 규칙은 같은 자리에서 같은
답을 낸다. D-2 가 코드 변경 없이 "사실의 기록" 으로만 끝난 것이 그 증거다.

반대로 배치가 달랐다면 — 규칙이 두 표면에 인라인되어 있었다면(§3 R11) — D-2 는 스타터 쪽 검증만 조용히
틀리게 만들었을 것이고, 그 어긋남은 요청이 성공하는 채로 파라미터가 달라지는 종류였을 것이다.
"규칙의 구현이 사는 단 한 곳" 이 그 값을 여기서 실제로 냈다.
