# OpenAI 모델 능력표가 남긴 열린 항목 — 등록 항목 1건 (열림 1)

출처는 2026-09-09 의 o-시리즈 reasoning-item 재생 프로브(라운드 8)다. 그 프로브는 자기가 물으러 간
질문에 답했고 — o-시리즈 여덟 이름이 `/v1/responses` 에서 재생된 reasoning item 을 받아 주므로
`supportsReasoningTraceRoundTrip` 이 그 이름들에 대해 켜졌다 — **그 김에 자기 범위 밖의 결함을 하나
측정했다.** 그것이 아래 L-1 이다.

**열림/닫힘의 정본은 이 문서다.** 설계 문서
[`design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) 의 §12 · §13
표는 **설계 시점의 기록**으로 동결되어 있다([`README.md`](README.md) 규칙 하나). 그 §13.4 가 이 결함을
자세히 적고 있지만, 거기에만 적어 두면 열린 항목으로 세어지지 않으므로 여기에 등록한다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 드리프트하므로 그 날짜 이후의 인용은 다시 세어야 한다.

---

## 1. 항목

### L-1 — `gpt-5.6-terra` 의 reasoning-effort 바닥이 틀렸고, 오늘의 표 모양으로는 고칠 수 없다 · **열림 (막혀 있음 — 선행 작업 필요)**

**무엇** — `ModelCapabilities.lowestReasoningEffort` 를 **바닥(floor) 하나**가 아니라 **rung 집합**으로
표현할 수 있게 만들고, 그 위에서 `gpt-5.6-terra` 의 사다리(`none` · `low` · `medium` · `high` ·
`xhigh` · `max`, **`minimal` 없음**)를 기술한다.

**왜 — 관측 가능한 결과** 터라는 `gpt-5` prefix 행에 걸리고, 그 행은 `lowestReasoningEffort = MINIMAL`
을 선언한다. 그래서 `ReasoningEffort.MINIMAL` 을 설정한 배포는 `maySendEffort` 를 통과하고
`"effort":"minimal"` 을 와이어에 올린 다음 **400 을 받는다.** 2026-09-09 에 실측했다 —
`Unsupported value: 'minimal' is not supported with the 'gpt-5.6-terra' model.` 같은 프로브에서 터라는
`none` 을 **받아들였고**(200, 응답이 `"effort":"none"` 을 되돌려 준다), 그것은 이 필드의 기본값
`MINIMAL` 이 근거로 삼던 전제 — *"NONE 은 어떤 벤더 사다리도 시작하지 않는 rung"* — 를 반증한다.
즉 이 행은 터라에 대해 **두 방향으로 동시에 틀려 있다**: 가진 rung(`none`)을 withhold 하고, 없는
rung(`minimal`)을 보낸다. 앞의 것은 보고된 누락으로 끝나고 뒤의 것은 턴을 실패시킨다.

**막혀 있는 이유 — 처방을 쓰기 전에 확인한 것** ([`README.md`](README.md) 규칙 다섯). 이름 하나짜리
행을 넣는 것이 뻔한 처방인데, **오늘의 레지스트리에는 그것을 안전하게 넣을 모양이 없다.**
`InMemoryModelCapabilityRegistry.builderWithDefaults()` 의 javadoc 이 *"`gpt-5` prefix 를 덮어쓰는
것"* 을 위치 안전한 override 의 **정본 예시**로 들고 있고 `builderWithDefaultsOverridesAPrefixInPlace`
가 그 약속을 고정하는데, 세 가지 모양이 전부 그것을 깬다.

| 모양 | 나중의 `registerPrefix("gpt-5", X)` 가 여전히 터라에 닿는가 |
|---|---|
| `register("gpt-5.6-terra", …)` — exact | **아니다** — exact 가 모든 prefix 를 이긴다 |
| `registerPrefix("gpt-5.6-terra", …)` 를 `gpt-5` **앞에** | **아니다** — 나중의 `registerPrefix("gpt-5", X)` 는 **제자리 재삽입**이므로 터라 prefix 가 여전히 먼저 매치한다. 그 제자리 성질이 바로 javadoc 이 약속하는 것이다 |
| `registerPrefix("gpt-5.6-terra", …)` 를 `gpt-5` **뒤에** | 도달 불가 — `gpt-5` 가 먼저 매치한다 |

그리고 터라는 이 저장소가 **`gpt-5` 행의 대역으로 쓰는 이름**이다 — 두 모듈에 걸쳐,
`InMemoryModelCapabilityRegistryTest` 는 그것으로 그 행의 플래그·바닥·override 레시피를 확인하고,
`OpenAIResponsesRequestFactoryTest` 는 `GPT5` 픽스처를 아예 `resolve("gpt-5.6-terra")` 로 정의한다.
그러므로 이름 하나짜리 행은 결함 하나를 고치면서 그 가족의 문서화된 탈출구를 그 이름에 대해 없애고,
덤으로 `gpt-5` 행 이름을 단 테스트들을 **초록인 채로** 다른 행의 테스트로 바꿔 놓는다.

**어디** *(2026-09-09)*

- `modules/aimon-core/src/main/java/at/aimon/core/llm/capability/InMemoryModelCapabilityRegistry.java`
  — `gpt-5` prefix 행과 그 위 주석(라운드 8 이 이 사실을 한 문단으로 적어 두었다)
- `modules/aimon-core/src/main/java/at/aimon/core/llm/capability/ModelCapabilities.java`
  — `lowestReasoningEffort()` 와 `unknown()` 의 javadoc. 바닥 하나로 모델링한다는 결정이 여기 있다
- `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAiRequestParameters.java`
  — `maySendEffort`, 두 엔드포인트가 함께 부르는 유일한 판정 지점
- [`design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) §13.4
  — 측정값·재현·왜 미뤘는지. §12.2 의 표 마지막 두 행이 그 결함을 표 안에서 보여 준다

**심각도 — 오늘 얼마나 물리는가** ([`README.md`](README.md) 규칙 셋 · 여섯). **설정으로는 도달할 수
없다.** `aimon-spring-boot-starter` 와 `aimon-cli` 의 main 소스에 reasoning-effort 를 받는 yaml 키도
프로퍼티도 **0건**이므로(2026-09-09 확인), `MINIMAL` 은 `OpenAIConfig` / `LlmModel` 을 통해
**프로그램으로만** 요청될 수 있다. 그래서 이것은 "배포하면 물린다" 가 아니라 **"설정 표면이 생기면
물린다"** 이고, 아래 트리거가 그 문장이다.

**선행 작업** — 둘이며, 갈라서 착수할 수 없다.

1. **rung 집합 능력.** 바닥은 구멍 뚫린 사다리를 기술하지 못한다. 터라의 진짜 바닥은 `NONE` 이고 그
   위 칸(`minimal`)이 비어 있는데, 단일 값으로는 셋 다 틀린다 — `NONE` 은 `minimal` 을 보내 400,
   `MINIMAL` 은 400 이면서 잘못 기술, `LOW` 는 400 은 피하지만 여전히 잘못 기술한다
2. **exact 행이 prefix 를 가리는 상호작용에 대한 답.** 이름 단위 항목은 무엇이든 위 표에 걸리므로,
   1번만 먼저 넣어도 터라에 적용할 자리가 없다

**언제 다시 볼까** — **reasoning effort 에 설정 표면(스타터 프로퍼티 또는 CLI yaml 키)이 생길 때.**
그 순간 이 400 이 프로그램 경로에서 운영자 경로로 내려오고, 선행 작업 2번을 어차피 그 작업이 마주친다.
트리거를 "터라를 쓰는 배포가 생길 때" 로 적지 않은 이유는 규칙 일곱이다 — 이 코드에 닿는 주어는
**그 모델을 쓰는 사람**이 아니라 **그 값을 적을 수 있는 사람**이고, 오늘은 후자가 자바 코드를 쓰는
사람뿐이다. 확인 방법:
`grep -rn "reasoningEffort\|reasoning-effort" modules/aimon-spring-boot-starter/src/main modules/aimon-cli/src/main`
가 0건이 아니게 되는 때.

**다시 측정해야 하는 것** — 위 사실은 전부 2026-09-09 의 키로 잰 것이고 **그 키는 회전되었다.**
터라의 사다리를 다시 확인하려면 새 키가 필요하다. 같은 프로브가 `gpt-5.6-luna` 와 `gpt-5.6-sol` 은
보기만 하고 **부르지 않았으므로**, 그 둘이 터라와 같은 구멍을 갖는지는 알려진 바가 없다 — 이름이
비슷하다는 이유로 행을 주지 않은 것이 그 때문이다.

---

## 여기 없는 것

- **`o1-pro` · `o4-mini-deep-research` 를 측정하는 것** — 라운드 8 의 비용 규칙이 금지했다. 이것은
  열린 *항목*이 아니라 예산이 붙은 별도 프로브이며, 그때까지 그 이름들은 prefix 행을 통해
  `supportsReasoningTraceRoundTrip=false` 를 유지하고 **이유가 적힌 채로** 남는다.
  [`design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) §13.7
- **라운드 8 이 재지 못한 나머지** — 같은 §13.7 이 목록이다. 미측정은 열린 작업이 아니라 **비어 있는
  칸**이므로 항목으로 등록하지 않는다. 이 구분은 이 저장소의 규율이다: 실측하지 않은 것은 실측하지
  않았다고 적는다
