# LLM 설정 표면 — 등록 항목 27건 (열림 14 · 닫힘 13)

출처는 #46 이다 — 모델 capability 표를 CLI yaml 과 스타터 프로퍼티에서 확장할 수 있게 한 작업.
설계는 옛 `model-capability-config-key.md`(지금은 [`../design/llm/configuration-surface.md`](../design/llm/configuration-surface.md)) 이고,
그 문서 §9 의 미해결 일곱 개 중 **이 국면 밖으로 결과가 나가는 넷**이 여기로 올라왔다. 나머지 셋은
설계 문서에 그대로 남는다 — 그쪽은 이 작업 안에서 답이 나왔거나(O-A: 실측으로 닫혔다, §11 D-1)
이 작업의 범위 안에서 결정된 것(O-F: 문서 목록에 한 파일을 넣는 결정, O-G: 맵 키의 `${VAR}` 해석)이다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §9 는 설계 시점의 기록이다.
설계 문서 쪽에도 이 문서를 가리키는 줄을 달아 두었으므로, §9 를 읽고 온 사람이 여기서 현재 상태를 본다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-09-09 기준이다.

---

## L-1 — 스타터에서 모르는 필드 이름이 조용하다

**무엇을.** `aimon.llm.model-capabilities.<model>.<flag>` 의 플래그 이름을 잘못 쓰면 스타터는 아무것도
말하지 않는다. 그것을 시끄럽게 만든다.

**왜.** 관측 가능한 결과는 이렇다 — `supports-sampling-parameters` 를
`supports-sampling-parameter` 로 적은 배포는 **capability 를 선언했다고 믿으면서 HTTP 400 을 계속 맞는다.**
이 키가 존재하는 이유가 정확히 그 400 이므로, 이것은 그 키가 자기가 없애려던 실패 모드를 한 겹 안쪽에서
재생산하는 자리다. CLI 는 같은 오타에 `ConfigurationException` 을 던진다 — `CliConfigLoader` 의 매퍼가
`FAIL_ON_UNKNOWN_PROPERTIES` 를 켠 채이기 때문이고, 그래서 이 항목은 **두 표면의 비대칭**이기도 하다.

**어디.** `AimonProperties.java:1189` 근방의 중첩 클래스 `ModelCapabilityProperties`(2026-09-09).
원인은 이 코드가 아니라 `@ConfigurationProperties` 의 기본값 `ignoreUnknownFields = true` 다. 실측:
`aimon.llm.model-capabilities.prod-assistant.supports-sampling-parameter=false` 는 맵을 **빈 채로** 남긴다
— 0개 선언 항목이 만들어져 거절되는 것이 아니라 **항목 자체가 만들어지지 않는다**(Boot 의
`JavaBeanBinder` 는 리프를 하나도 못 바인딩하면 값 인스턴스를 만들지 않는다).

그 침묵은 **테스트로 기록되어 있다** —
`AimonPropertiesValidationTest.aMisspelledFlagIsSilentInTheStarter`. 그 테스트의 주석이 스스로
*"limitation record, not a guarantee"* 라고 적고, 누가 이 항목을 닫으면 **그 테스트가 빨개지는 것이 옳은
결과**라고 적어 두었다. 즉 이 항목을 착수하는 사람이 가장 먼저 만나는 것은 실패하는 테스트 하나다
(`README.md` 규칙 다섯이 요구하는 순서가 이미 갖춰져 있다는 뜻이다).

**닫는 길 셋.** 앞의 둘은 설계가 저울에 올려 기각했고, 셋째는 **설계가 올리지 않았다** — 그래서
R 번호를 주지 않는다(그 번호는 설계의 기각 목록이 쓰는 것이다). 되돌리려면 이 표를 다시 봐야 한다.

| 길 | 무엇을 얻나 | 무엇을 잃나 |
|---|---|---|
| **R12** — `@ConfigurationProperties(ignoreUnknownFields = false)` | 트리 전체에서 오타가 시끄러워진다 | `aimon.*` **전체**의 동작 변경이다. 같은 prefix 아래 자기 키를 두는 호스트 앱을 기동 실패로 만들 수 있고, 그것은 이 키에 얹혀 갈 변경이 아니다 |
| **R14** — 항목을 `Map<String, Map<String, String>>` 로 받아 번역기가 리프 이름을 검사한다 | 트리를 건드리지 않고 이 서브트리만 닫는다 | 다섯 리프 이름과 `lowest-reasoning-effort` 의 enum 후보가 **IDE 자동완성에서 사라지고**, `AimonDocumentedPropertiesTest` 의 walker 가 `…model-capabilities.*.*` 를 기록하게 되어 **가이드에 적힌 어떤 리프 오타도 알려진 키로 통과한다** — 설정의 오타를 잡으려고 문서의 오타를 못 잡게 만드는 거래다 |
| **N-1** — `ConfigurationPropertiesBindHandlerAdvisor` 로 `NoUnboundElementsBindHandler` 를 감싸되 **`aimon.llm.model-capabilities` prefix 아래에서만** unbound 를 거절한다 | R12 의 결과를 R14 의 사정거리로 얻는다 — 타입도 자동완성도 문서 가드도 그대로다 | **미검증이다.** advisor 는 전역이므로 좁히는 것은 handler 안에서 해야 하고, `ConfigurationPropertyName` 으로 prefix 를 판정하는 것이 실제로 이 서브트리만 걸러 내는지 실측하지 않았다. 호스트 앱이 자기 `@ConfigurationProperties` 를 갖는 배포에서 advisor 가 그쪽 바인딩에도 끼어드는 것도 확인해야 한다 |

N-1 을 여기 적는 이유는 그것이 답이라고 보아서가 아니라 **이 항목이 이항 선택으로 닫히지 않게 하려는
것**이다. 위 두 줄만 놓고 보면 "둘 다 비싸다 → 못 닫는다" 가 결론처럼 읽히는데, 셋째 줄은 대가가 비용이
아니라 **미측정**이므로 판단이 아니라 실측 하나를 기다린다. 착수하는 사람이 가장 먼저 할 일은 R14 의
거래를 다시 저울질하는 것이 아니라 N-1 을 30분 재 보는 것이다.

**언제 다시 볼까.** 셋 중 하나다.

1. 누군가 실제로 이 오타를 밟고 보고할 때 — 그러면 빈도가 추정이 아니라 사실이 되고, R14 의 대가를
   치를 값이 있는지 판단할 수 있다.
2. `aimon.*` 아래에 **같은 성질의 두 번째 맵-of-객체 키**가 생길 때. 한 서브트리를 위해 R14 를 치르는
   것과 두 서브트리를 위해 치르는 것은 다른 계산이다. N-1 은 이 축에서 반대로 움직인다 — prefix 를
   하나 더 등록하면 되므로 서브트리가 늘수록 유리해진다.
3. 호스트 앱이 `aimon.*` 아래 자기 키를 두지 않는다는 것이 규약으로 확정될 때 — 그러면 R12 의 유일한
   대가가 사라진다.

> **2026-09-09 — 이 항목은 닫히지 않았고, 키 셋만큼 넓어졌다.** #54 가
> `aimon.llm.anthropic.thinking-mode` · `.thinking-budget-tokens` · `.replay-thinking-blocks` 를 더했고,
> 세 키 모두 같은 침묵 아래 있다 — `thinking-mod` 로 적은 배포는 아무 말도 듣지 못한 채 기본값으로 돈다.
> **성질은 그대로다**: 원인은 여전히 `@ConfigurationProperties` 의 `ignoreUnknownFields = true` 이고, CLI
> 는 여전히 같은 오타에 `ConfigurationException` 을 던진다(그쪽 매퍼가 `FAIL_ON_UNKNOWN_PROPERTIES` 를
> 켠 채다). 닫는 길 셋(R12 · R14 · N-1)의 저울도 그대로다 — 이 세 키는 맵이 아니라 세 개의 리프이므로
> R14 의 대가("자동완성이 사라진다")를 새로 지지도, 덜지도 않는다.
> 침묵은 여기서도 테스트로 기록했다 — `AimonPropertiesValidationTest.aMisspelledAnthropicKeyIsSilentInTheStarter`.
> 옆의 `aMisspelledFlagIsSilentInTheStarter` 와 같은 성격이며, 누가 이 항목을 닫으면 **둘이 함께 빨개진다.**
>
> **"언제 다시 볼까" 의 트리거 2 는 발화하지 않는다.** 그것이 세는 것은 *"같은 성질의 두 번째
> 맵-of-객체 키"* 이고 `aimon.llm.anthropic` 은 그것이 아니다 — 고정된 리프 셋을 가진 중첩 객체 하나다.
> R14 의 계산도 N-1 의 계산도 바뀌지 않으므로 **이 항목의 처분은 그대로**다. 재도출하게 두는 것보다
> 적어 두는 편이 싸다.

> **2026-09-10 — 다시 닫히지 않았고, 키 하나만큼 더 넓어졌다.** #61 이 `aimon.llm.reasoning-effort`
> 를 더했고(공통 네임스페이스 — 이름이 중립 SPI 타입 자신의 것이고 뜻이 벤더마다 다르지 않다),
> 같은 침묵 아래 있다. 같은 라운드가 `…model-capabilities.<model>.accepted-reasoning-efforts` 도 더했는데,
> 그쪽은 **이미 세고 있던 서브트리 안의 여섯 번째 리프**이므로 새로 세지 않는다.
> **성질은 세 번째로 그대로다** — 원인도, CLI 가 같은 오타에 던진다는 것도, 닫는 길 셋의 저울도.
> 침묵은 세 번째 테스트로 기록했다 — `AimonPropertiesValidationTest.aMisspelledReasoningEffortIsSilentInTheStarter`.
>
> **그 셋째 테스트가 앞의 둘과 다른 것을 고정한다는 것만 적어 둔다.** `aMisspelledFlagIsSilentInTheStarter`
> 는 **맵-of-객체의 리프**를, `aMisspelledAnthropicKeyIsSilentInTheStarter` 는 **중첩 객체의 리프**를
> 고정한다. 새것은 **이 키가 없어도 바인딩되는 빈 위의 스칼라 리프**다 — 그래서 여기서는 Boot 의
> `JavaBeanBinder` 가 값 인스턴스를 만들지 않는 경로조차 지나가지 않고, 오타 난 필드가 그냥 null 로
> 남는다. 세 모양이 전부 침묵한다는 것이 R12·R14·N-1 의 저울을 바꾸지는 않지만, 누가 N-1 을 재 볼 때
> **재야 할 모양이 셋**이라는 것은 바뀐다.
>
> **트리거 2 는 이번에도 발화하지 않는다.** `aimon.llm.reasoning-effort` 는 맵이 아니라 스칼라 리프
> 하나다. 처분은 그대로.

> 인수 조건과의 관계를 정직하게 적어 둔다. #46 의 인수 조건은
> *"잘못된 설정이 조용히 통과하지 않는다 — 양쪽 표면 모두"* 였고, **값 오류와 의미 오류에 대해서는 양쪽
> 다 충족되었지만 모르는 필드 이름에 대해서는 스타터에서 충족되지 않았다.** 설계가 O-C 로 공표하고
> 리뷰가 비블로킹으로 통과시킨 자리이며, **엄격하게 읽는 사람이 뒤집을 수 있는 판단이다.**
> 설계 §2.5 는 이 구멍이 절반 막힌다고 적었는데(한 필드짜리 항목의 오타는 0개 선언이 되어 거절된다)
> 그것도 실측으로 틀렸다 — 설계 §11 D-3.

> **2026-09-10 (#62) — 다시 닫히지 않았고, 키 둘만큼 더 넓어졌다.** 추론 스트림 라운드가
> `aimon.llm.anthropic.thinking-display` 와 **`aimon.llm.openai.reasoning-summary`** 를 더했다. 둘 다
> 같은 침묵 아래 있다 — `thinking-displays` 로 적은 배포는 아무 말도 듣지 못한 채 기본값(요청도 채널도
> 없음)으로 돈다. **성질은 그대로**이고 R12 · R14 · N-1 의 저울도 그대로다: 둘 다 맵이 아니라 리프이므로
> R14 의 대가("자동완성이 사라진다")를 새로 지지도, 덜지도 않는다.
>
> **다만 한 칸이 실제로 달라졌다** — 이 라운드가 `aimon.llm.openai.*` 를 **열었다**(L-2 가 예약해 둔
> 자리다). N-1 이 prefix 단위로 unbound 를 거절하는 길이라는 것을 생각하면 그쪽에 유리한 변화다:
> 등록할 prefix 가 하나 늘어난 것이 아니라 **벤더 네임스페이스가 둘이 되어 같은 처방이 두 서브트리를
> 덮게 되었다.** "언제 다시 볼까" 의 트리거 2 는 여전히 발화하지 않는다 — 그것이 세는 것은
> 맵-of-객체 키이고 이 둘은 그것이 아니다.

> **2026-09-10 (#69·#72) — 다시 닫히지 않았고, 키 둘만큼 더 넓어졌다.** 이 라운드가
> `aimon.llm.model-capabilities.<model>.thinking-dialect` 와 `.supports-reasoning-summary` 를 더했다. 둘 다
> **이미 세고 있던 서브트리 안의 잎**이므로(여섯 → 여덟) 트리거 2 는 이번에도 발화하지 않는다 —
> 그것이 세는 것은 맵-of-객체 **키**이고 이 서브트리는 이미 그 하나로 세어져 있다. R12 · R14 · N-1 의
> 저울도 그대로다.
>
> **CLI 쪽의 사정거리가 한 칸 줄어든 것만 적어 둔다.** 이 항목은 처음부터 *"CLI 는 같은 오타에 던진다"* 를
> 비대칭의 다른 쪽으로 세어 왔는데, 그 던짐이 **정확히 철자가 맞은 `thinkingDialect`** 를 잡던 자리는
> 없어졌다 — 그 키가 이제 바인딩되기 때문이고, 그것이 #69 다. 이것은 CLI 가 조용해진 것이 아니라
> **잡을 오타가 하나 줄어든 것**이며, 비대칭 자체는 그대로다. 침묵의 기록도 그대로다 —
> `AimonPropertiesValidationTest.aMisspelledFlagIsSilentInTheStarter` 는 초록으로 남고, 이 항목을 닫으면
> 빨개지는 것이 옳은 결과다.

---

## L-2 — 이 경로의 나머지 두 노브가 아직 프로그램 전용이다

**무엇을.** `OpenAIConfig.responsesApiEnabled` 와 샘플링 파라미터(`temperature` · `topP` ·
`presencePenalty` · `frequencyPenalty`)에 설정 표면을 준다.

**왜.** [`../design/llm/openai-responses-path.md`](../design/llm/openai-responses-path.md) F-2 가
*"세 개의 프로그램 전용 노브. 한 config 이슈이지 세 개의 rider 가 아니다"* 라고 적었고, #46 은 그중
**registry 하나만** 닫았다. 남은 둘 중 `responsesApiEnabled` 가 더 급하다 — 그것이 필요한 상황이
**yaml 로 완전히 만들어질 수 있는데** 처방만 자바인 비대칭이 있다: `base-url` 은 CLI 키이자 스타터
프로퍼티이고, 실제 `gpt-5*` 이름은 내장 행에 걸려 `/v1/responses` 로 라우팅되므로,
**`/v1/chat/completions` 만 구현한 게이트웨이 배포는 설정만으로 404 에 도달할 수 있고 설정만으로는
빠져나올 수 없다.** #46 의 새 키로는 우회할 수 있지만(`supports-reasoning-trace-round-trip: false` 를
그 모델 이름에 선언) 그것은 모델 하나씩이고, 배포 전체를 끄는 스위치가 아니다.

**어디.** `OpenAIConfig.Builder.responsesApiEnabled(boolean)`(2026-09-09) · 샘플링 값은 `LlmModel`.
설정 쪽에는 아직 아무 자리도 없다.

**모양은 이미 정해져 있다** — #46 이 B-21 을 결정하면서 세운 두 갈래 기준의 첫 적용 결과다
(`spring-boot-starter-open-items.md` §5 의 B-21, 설계 §2.7).

| 키 | 어디로 | 어느 조건에 걸려서 |
|---|---|---|
| `responsesApiEnabled` | `aimon.llm.openai.responses-api-enabled` · CLI `llm.openai.responsesApiEnabled` | **이름**이 OpenAI 의 엔드포인트다 |
| `temperature` · `topP` · 두 penalty | `aimon.llm.<provider>.*` | **뜻**이 벤더마다 다르다 (유효범위, 무시되는 파라미터) |

`spring-boot-starter.md` 의 프로퍼티 트리는 `llm.anthropic: { … }` / `llm.openai: { … }` 자리를 이미
비워 두고 있으므로, 이것은 새 네임스페이스를 만드는 일이 아니라 **비어 있는 자리를 채우는 일**이다.

**언제 다시 볼까.** Chat-only 게이트웨이 배포가 실제로 404 를 보고할 때, 또는 샘플링 값을 설정으로
요구하는 사람이 나올 때. 둘 중 앞엣것이 먼저 올 가능성이 높고, 그때는 `responsesApiEnabled` **하나만**
내려도 된다 — 두 키가 한 이슈일 이유는 없다.

> **2026-09-10 — 그 스위치를 내리는 사람이 함께 재야 할 칸이 하나 생겼다 (#61).** 라운드 9 가
> `gpt-5.6-terra` 에 exact 행을 주면서 그 사다리를 `{none, low, medium, high}` 로 적었는데, **그 사다리는
> `/v1/responses` 에서만 실측되었다**(옛 `openai-model-capabilities.md` §13.3, 지금은
> [`../design/llm/model-capabilities.md` §6.1](../design/llm/model-capabilities.md#61-openai-행). 옛 문서가 §13.3 첫머리에서 *"인용된 열거는 그것이 나온 엔드포인트 없이는 아무 뜻이
> 없다"* 고 경고하는 바로 그 자리다). 판정 지점인 `OpenAiRequestParameters.maySendEffort` 는 **두
> 엔드포인트가 함께 부르므로**, `responsesApiEnabled(false)` 로 터라를 Chat Completions 에 강제한 배포는
> 이제 재어진 적 없는 칸에 `reasoning_effort: none` 을 보낸다 — 오늘의 "보고된 누락" 이 400 이 될 수 있다.
>
> **오늘은 물리지 않는다.** 그 스위치는 자바로만 켤 수 있고, 그것을 바꾸는 것이 이 항목이다. 그러니 이
> 칸은 **이 항목을 착수하는 사람의 것**이다: `responsesApiEnabled` 에 설정 표면을 주는 순간 위 조합이
> 운영자 경로로 내려오므로, 그때 터라의 `none` 을 Chat 에서 한 번 재거나(키가 있으면), 재지 않기로 하고
> 그 사실을 적어야 한다. 행 자체는 여전히 옳다 — 대안은 `minimal` 에 대한 **실측된** 400 이다.
> 근거: 옛 `reasoning-effort-config-surface.md` §17.4 — 지금은 [`../design/llm/model-capabilities.md` §6.4](../design/llm/model-capabilities.md#64-미측정-칸).

> **2026-09-10 (#62) — 이 항목이 "비어 있는 자리를 채우는 일" 이라고 적은 그 자리가 더 이상 비어 있지
> 않다.** 추론 스트림 라운드가 `aimon.llm.openai.reasoning-summary` / CLI `llm.openai.reasoningSummary`
> 를 그 블록의 **첫 키**로 넣었다. 판정은 이 항목의 표와 같은 기준이고 같은 조건에 걸렸다 —
> `reasoning.summary` 는 OpenAI 요청 본문의 경로 그 자체이고 값도 그 벤더의 어휘다.
>
> **이 항목에 남는 것은 줄어들지 않았고, 착수 비용만 줄었다.** `responsesApiEnabled` 와 샘플링 값은
> 여전히 자바 전용이다. 달라진 것은 **네임스페이스를 만드는 일이 이미 끝났다**는 것 — CLI 쪽
> `OpenAiProviderConfig` · `LlmProviderConfig.openai` · `LlmClientFactory.refuseOpenAiBlock`,
> 스타터 쪽 `AimonProperties.Llm.OpenAi` · `LLM_OPENAI` · `AimonLlmAutoConfiguration.refuseOpenAiBlock`
> 이 전부 서 있으므로, `responsesApiEnabled` 는 그 블록에 필드 하나를 더하는 일이다.
>
> **거절이 대칭이 된 것도 함께 적어 둔다.** 이 블록이 없던 동안 `refuseAnthropicBlock` 의 javadoc 은
> *"반대 방향의 짝은 없다: 오늘 `llm.openai` 블록이 존재하지 않으므로 anthropic 분기가 거절할 것이
> 없다"* 라고 적고 있었고, 그 문장을 거짓으로 만든 것이 이 라운드다. 두 문장 다 고쳐졌다.
>
> **위 2026-09-10 (#61) 칸은 그대로 이 항목의 것이다** — 터라의 `none` 을 Chat Completions 에서
> 재는 일은 이 라운드가 하지 않았고, 그 스위치는 여전히 자바로만 켤 수 있다.

---

## L-3 — 읽히지 않는 선언을 감지하지 않는 경우가 둘 남았다

**무엇을.** `aimon.llm.model-capabilities` 가 선언되어 있는데 **어느 벤더 분기도 돌지 않는** 배포에서는
그 선언이 조용히 아무것도 하지 않는다. 두 경우다 — `aimon.llm.provider=none`, 그리고 애플리케이션이
자기 `LlmClient` 빈을 정의해 `@ConditionalOnMissingBean` 이 두 분기를 다 물리는 경우.

**왜.** #46 은 공유 네임스페이스(`aimon.llm.*`)를 정당화하는 근거로 *"읽지 않는 분기가 이름으로
거절한다"* 를 들었다. 그 약속이 두 경우에서는 지켜지지 않는다 — 관측 가능한 결과는 "설정했는데 안
읽힌다" 이고, 이 저장소가 `refuseIneffective` · `validateScheduling` 로 반복해서 금지해 온 그 상태다.

**어디.** `AimonLlmAutoConfiguration.refuseModelCapabilities`(2026-09-09)는 Anthropic 분기 **안**에서
불렸다. 그것이 의도였다 — 같은 파일의 `requireApiKey` javadoc 이 세운 규칙(*"the answer depends on a
bean"*)을 따른 것이고, 빈이 있는 배포에 무언가를 요구하면 **유효한 설정을 기동 실패로 만든다.**

> **이 항목의 예시가 없어졌다. 항목은 그대로 열려 있다.** #52 가 Anthropic 클라이언트에도 registry 를
> 읽히면서 `refuseModelCapabilities` 는 **삭제되었고**(CLI 쪽 `refuseModelCapabilitiesForAnthropic` 도
> 함께), 그래서 위 문단이 가리키던 메서드는 트리에 더 없다. 없어진 것은 **두 규칙의 충돌을 보여 주던
> 자리**이지 이 항목의 두 갈래가 아니다 — `provider=none` 도, 자기 `LlmClient` 빈을 정의한 앱도 여전히
> 어느 벤더 분기에도 닿지 않고, 그 배포의 선언은 여전히 조용히 아무것도 하지 않는다. 착수하는 사람이
> 정할 것도 그대로다. 근거:
> 옛 `anthropic-sampling-capabilities.md` §7.1 — 지금은
> [`../design/llm/configuration-surface.md` §6.3](../design/llm/configuration-surface.md#63-실행되는-분기만-읽히지-않을-벤더-블록을-거절한다).

**즉 이것은 결함이 아니라 두 규칙의 충돌이다.**

| 규칙 | 이 자리에서 무엇을 요구하나 |
|---|---|
| *"설정했는데 안 읽히는 것이 가장 나쁘다"* (`refuseIneffective`) | 거절하라 |
| *"답이 빈에 달려 있으면 빈을 볼 수 있는 자리에서만 판단하라"* (`requireApiKey` javadoc) | 거절하지 마라 — 앱이 자기 클라이언트에 쓰려고 적어 둔 선언일 수 있다 |

**두 번째 줄이 두 경우에서 서로 다른 무게를 갖는다는 것**이 착수하는 사람이 먼저 정할 것이다:
앱이 자기 `LlmClient` 빈을 준 경우에는 그 선언이 **정말로 쓰일 수 있지만**(앱이 `PeerMemory` 처럼
registry 를 자기 클라이언트에 넘길 수 있다), `provider=none` + 빈 없음은 그럴 수 없다 — 그 배포는
`LlmClient` 가 아예 없으므로 어떤 registry 도 소비되지 않는다. **갈래마다 답이 다르면 결정문도 갈래별로
쓴다**(`README.md` 규칙 넷).

**언제 다시 볼까.** `aimon.llm.*` 아래에 **같은 성질의 세 번째 키**가 생길 때 — 그러면 이것은 한 키의
예외가 아니라 공유 네임스페이스 전체의 정책이 되고, 한 번에 정하는 편이 싸다. 또는 L-2 를 착수할 때:
`responsesApiEnabled` 가 `aimon.llm.openai.*` 로 내려가면 그 키에 대해서는 이 질문이 자동으로 사라지므로
(벤더 네임스페이스는 읽는 주체가 이름에 적혀 있다), 남는 것이 정확히 무엇인지 그때 다시 세어야 한다.

> **2026-09-09 — 그 "다시 세는" 일이 L-2 보다 먼저 왔고, 세어 보니 늘었다.** #54 가
> `aimon.llm.anthropic.*` 세 키를 더했다. 위 문단의 논리대로라면 벤더 네임스페이스 키는 읽는 주체가
> 이름에 적혀 있으므로 이 질문에서 빠져야 하고, **한 갈래에서는 실제로 빠진다** — `provider=openai`
> 배포는 그 블록을 이름으로 거절당하며, 그 거절은 #46 이 공유 네임스페이스를 위해 발명해야 했던 것과
> 달리 여기서는 정당화가 필요 없다.
>
> **그러나 이 항목의 두 갈래는 거절할 분기가 없는 배포들이고, 거기서는 세 키가 그냥 늘어난다.**
> `provider=none` 도, 자기 `LlmClient` 빈을 정의한 앱도 여전히 어느 분기에도 닿지 않으므로
> `aimon.llm.anthropic.*` 는 거절되지도 읽히지도 않는다. 즉 **갈래는 둘 그대로이고 표면이 세 키 넓어졌다.**
> 한 갈래에서는 오히려 답이 더 분명해졌다는 것도 적어 둔다 — 자기 클라이언트를 만드는 앱은
> `model-capabilities` 를 `AimonProperties.modelCapabilityRegistry(...)` 로 소비할 수 있는 것과 달리,
> 이 세 키를 소비하려면 `AnthropicConfig.Builder` 를 자기가 부르면 되므로 프레임워크가 열어 줄 표면이
> 애초에 없다. 결정할 것은 그대로이고, 세는 수만 달라졌다.

> **2026-09-10 (#62) — "다시 세는" 일이 두 번째로 왔고, 이번에는 한쪽 갈래에서 **처음으로 줄었다**.**
> 이 라운드가 `aimon.llm.anthropic.thinking-display` 와 `aimon.llm.openai.reasoning-summary` 를 더했다.
> 위 문단이 세운 논리 — 벤더 네임스페이스 키는 읽는 주체가 이름에 적혀 있으므로 이 질문에서 빠진다 —
> 는 여전히 **거절할 분기가 있는 배포에서만** 참이고, 이 항목의 두 갈래에서는 아니다. 거기서는 두 키가
> 또 그냥 늘어난다: **갈래는 둘 그대로, 표면은 다섯 키 넓어졌다**(#54 의 셋 + 이 라운드의 둘).
>
> 줄어든 쪽은 이것이다 — **"언제 다시 볼까" 가 예상한 L-2 의 효과가 절반 실현되었다.** 그 문단은
> *"`responsesApiEnabled` 가 `aimon.llm.openai.*` 로 내려가면 그 키에 대해서는 이 질문이 자동으로
> 사라진다"* 고 적었는데, 내려간 것은 그 키가 아니라 **네임스페이스 자체**였다. 그래서 앞으로
> `aimon.llm.openai.*` 에 무엇이 들어오든 그 키는 태어날 때부터 이 질문 밖에 있다 — 남는 것은
> `aimon.llm.*` 공유 네임스페이스의 세 키(`model-capabilities` · `reasoning-effort` · 그리고 그
> 아래 붙을 다음 것)뿐이다. **트리거 "같은 성질의 세 번째 키"는 이제 공유 네임스페이스 안에서만
> 센다**, 그리고 그 기준으로는 이미 둘이다.

---

## L-4 — 설정에서 prefix 를 선언할 길을 열 것인가

**무엇을.** 오늘 설정 항목은 **exact 이름 하나**만 지목한다. 게이트웨이가 가족 전체를 개명하면
(`prod-assistant-mini` · `prod-assistant-nano` · …) 이름을 하나씩 적어야 한다.

**왜.** 오늘은 관측 가능한 결과가 "여러 줄을 적는다" 뿐이므로 결함이 아니다. 이 항목이 여기 있는 이유는
**설정 쪽이 순수 추가인 반면 코어 쪽이 그렇지 않다**는 것이고, 그 비대칭은 소비자가 나타난 날 처음
발견되면 비싸다.

**어디.** `InMemoryModelCapabilityRegistry.Builder.registerPrefix`(2026-09-09)와
`builderWithDefaults()` 의 in-place 교체 성질.

**무엇이 막고 있나.** 설정에 `match: prefix` 필드를 더하는 것은 순수 추가다. 문제는 코어다 —
**prefix 우선순위는 등록 순서이고, `builderWithDefaults()` 위에 새 prefix 를 등록하면 내장 prefix
뒤에 붙는다.** 그래서 사용자가 `gpt-5-mini` prefix 를 선언해도 `gpt-5-mini-x` 는 내장 `gpt-5` 에 먼저
걸려 **사용자 항목이 영원히 발화하지 않는다.** 고치려면 "사용자 prefix 를 내장 prefix 앞에 등록하는"
경로를 코어에 열어야 하고, 그것은 `builderWithDefaults()` 의 in-place 교체가 지켜 주던
**`gpt-5-chat` 보호**(더 구체적인 prefix 가 가족 prefix 에 삼켜지지 않는 성질)와 정면으로 부딪힌다.
그 보호가 왜 필요한지는 그 메서드의 javadoc 과
`InMemoryModelCapabilityRegistryTest.builderWithDefaultsOverridesAPrefixInPlace` 에 있다.

**대안이 하나 있고 값이 싸다** — 지금도 이름을 여러 개 적으면 되고, **exact 는 순서에 의존하지 않는다.**
설정 표면에 세 번째 매칭 규칙(길이 내림차순 정렬 → longest-match)을 만드는 것은 설계가 R6 로 기각했다:
코어의 규칙과 다른 규칙이 설정 표면에만 생긴다.

**언제 다시 볼까.** 가족 단위로 개명하는 배포가 실제로 나타날 때. 그때 세어야 하는 것은 "몇 줄을
적어야 하는가" 이고, 그 수가 작으면 이 항목은 열린 채로 두는 것이 맞다 — 코어의 매칭 규칙을 하나 더
만드는 비용이 줄 몇 개보다 비싸다.

---

## L-5 — CLI 의 매핑 오류 메시지가 어느 키인지 말하지 않는다

*(2026-09-09 등록. 출처는 #54 —
옛 `anthropic-thinking-config-surface.md` §13 O-3 — 지금은 [`../design/llm/configuration-surface.md` §6.4](../design/llm/configuration-surface.md#64-표면별-실패))*

**무엇을.** `CliConfigLoader` 는 Jackson 의 매핑 실패를 전부 한 문장으로 감싼다 —
`Invalid configuration structure in: <file>`. 어느 키가 문제인지는 원인 예외에만 있고, 그것은
`--verbose` 로만 보인다. 그 원인의 짧은 메시지를 감싸는 문장에 실어 준다.

**왜.** L-1 이 기록한 비대칭의 **CLI 쪽 절반**이다. 그쪽은 스타터가 오타에 침묵한다는 것이고, 이쪽은
CLI 가 시끄럽되 **무엇에 대해 시끄러운지 말하지 않는다**는 것이다. 두 표면 다 "잘못된 설정이 조용히
통과하지 않는다" 는 충족하지만, CLI 의 운영자는 파일 이름만 받는다. 스타터 쪽은 프로퍼티 이름을 대는
것이 규칙으로 자리 잡았고(`requireApiKey` · `requireModel` · `modelCapabilityRegistry` 의 재던지기),
CLI 도 **자기가 판단하는 자리에서는** 같은 규칙을 지킨다 — 지키지 못하는 것은 판단이 Jackson 안에서
일어나는 값 오류뿐이다.

**어디.** `CliConfigLoader.java:73-74`(2026-09-09)의 `JsonMappingException` catch.

**범위가 이 파일보다 넓다는 것을 적어 둔다.** 여기 등록하는 이유는 L-1 과 같은 표의 반대쪽이기
때문이지만, 고치면 **CLI 의 모든 키**가 함께 좋아진다 — `memory` · `mcp` · `cli` 블록의 값 오류까지.
그래서 이것은 LLM 키의 항목이라기보다 그 키들이 처음 부딪힌 자리다.

**무엇이 막고 있나.** 아무것도 막고 있지 않다. 대가는 하나뿐이고 **그 문자열을 단언하는 기존
테스트 넷**이다(`CliConfigLoaderTest` 의 `Invalid configuration structure` 단언들). 원인 메시지를
덧붙이는 형태라면 `hasMessageContaining` 은 그대로 통과하므로 실제 비용은 그보다 작을 수 있다 —
착수할 때 세어 보면 된다.

**언제 다시 볼까.** #54 가 이 자리를 다시 지났고 고치지 않았다 — 그 국면의 주제가 설정 표면 하나였고,
공유 메시지를 고치는 것은 CLI 전체에 대한 변경이라 얹혀 갈 수 없었다. 다음 사람은 **CLI 설정 키를
하나라도 더하는 국면**에서 이것을 만난다. 그때 고치는 편이 싸다: 새 키의 실패 경험을 정하면서 기존
키 전부의 것도 함께 정하게 된다.

> 부분적으로는 이미 나아졌다. `llm.anthropic.thinkingMode` 는 자기 디시리얼라이저를 가지므로
> **원인 예외가 프로퍼티 이름과 네 철자를 댄다**(`AnthropicProviderConfig.ThinkingModeDeserializer`).
> 감싸는 문장은 여전히 파일 이름뿐이므로 이 항목은 그대로 열려 있고, 저 한 키는 고쳤을 때 무엇이
> 보이게 되는지의 예시다.

---

## L-6 — `BUDGETED` 쪽 절반이 선언된 행 없이 서 있는데, 이제 실측된 행이 셋 있다

*(2026-09-10 등록. 출처는 #62 국면의 실측 —
태스크 기록의 `MEASUREMENTS.md` §4. **작업 자체는 #60 / PR #64 의 영역이다.**)*

**무엇을.** `InMemoryModelCapabilityRegistry` 의 thinking 방언 표에 **`BUDGETED` 행 셋**을 더한다 —
`claude-opus-4-5-20251101` · `claude-sonnet-4-5-20250929` · `claude-haiku-4-5-20251001`.

**왜.** 관측 가능한 결과는 **`thinkingMode: auto` 를 쓴 배포가 이 세 모델에서 아무것도 얻지 못한다**는
것이다. 표가 이름을 못 대면 `resolveDialect` 는 `thinkingDialectUnknown@<model>` 을 내고 `AUTO` 는
`OFF` 처럼 행동한다 — 그 세 모델이 실제로는 budgeted 방언을 **받아 주는데도** 그렇다. 그리고 라운드
1·2·3 이 매번 *"`BUDGETED` 행이 하나도 배포되지 않으므로 그 절반은 선언된 행을 통해서만 시험된다"* 고
적어 두었는데, 이제 그 절반에 넣을 **실측된** 행이 셋 있다.

| 모델 | adaptive | budgeted | 표가 말하는 것 | 실측된 방언 |
|---|---|---|---|---|
| `claude-opus-4-5-20251101` | **400** | 200 | UNKNOWN | **BUDGETED** |
| `claude-sonnet-4-5-20250929` | **400** | 200 | UNKNOWN | **BUDGETED** |
| `claude-haiku-4-5-20251001` | **400** | 200 | UNKNOWN | **BUDGETED** |

**어디.** `at.aimon.core.llm.capability.InMemoryModelCapabilityRegistry` 의 `registerPrefix` 행들
(2026-09-10). 더할 것은 BUDGETED 쪽뿐이다 — **오늘 배포되는 ADAPTIVE 행 여섯 중 다섯이, 모델 이름
여섯 개에 걸쳐 같은 프로브에서 확인되었다.** 행과 모델 이름은 **같은 수가 아니다**:
`registerPrefix` 로 등록된 ADAPTIVE 행은 여섯이고(`InMemoryModelCapabilityRegistry:243-253` —
`claude-fable-5` · `claude-opus-5` · `claude-opus-4-7` · `claude-opus-4-8` · `claude-sonnet-5` ·
`claude-mythos`), 그중 **다섯**이 adaptive 200 · budgeted 400 으로 확인되었다. 프로브가 이름 여섯 개를
친 것은 `claude-fable-5-1` 이 자기 행이 아니라 `claude-fable-5` prefix 에 걸리기 때문이다. 여섯 번째 행
`claude-mythos` 는 여전히 확인 불가다 — 이 계정의 `GET /v1/models` 목록에 그 prefix 로 시작하는 모델이
없다. 그것으로
옛 `reasoning-model-enablement.md` §9 U-1(지금은 [`../design/llm/model-capabilities.md` §6.2](../design/llm/model-capabilities.md#62-anthropic-행))이
적어 둔 *"행렬은 실측이지만 **오늘 어느 모델이 그 위에 있는지는 아니다**"* 라는 유보가 **그 다섯에 대해
해소된다.**

**세는 단위를 이렇게 적어 두는 것까지가 이 항목의 일이다.** 집합을 조금 틀리게 열거하는 것은 이 항목을
낳은 정정(`AnthropicThinkingDisplay` 의 `updates`)이 바로잡는 결함과 **같은 종류**이고, 처음 쓴 이 문단이
실제로 그 실수를 했다 — 리뷰가 잡았다.

**언제 다시 볼까.** #60 의 표를 다음에 손댈 때. **이 국면(#62)에서 하지 않은 것은 의도**다 — 이미 리뷰를
통과한 PR 을 넓히지 않으려는 것이고, 표는 그 PR 의 소유물이다.

### 닫힘 (2026-09-10, #73)

행 셋이 들어갔다. **다만 이 항목이 적어 둔 모양 그대로는 아니다 — 그리고 그 차이가 이 항목이 몰랐던
사실에서 나왔다.**

이 항목은 *"`claude-opus-4-5-20251101` · `claude-sonnet-4-5-20250929` · `claude-haiku-4-5-20251001`"*
라는 **날짜 붙은 이름 셋**을 더하라고 적었다. 착수 시점의 재측정이 그 전제를 뒤집었다: 날짜 없는 별칭
`claude-opus-4-5` · `claude-sonnet-4-5` · `claude-haiku-4-5` 도 **호출되고**(응답의 `model` 이 날짜 붙은
스냅샷을 가리킨다) 같은 방언을 말하는데, **`GET /v1/models` 목록에는 없다.** 즉 이 항목이 프로브 방법으로
물려받은 *"이 계정이 닿을 수 있는 모든 `claude-*` 모델"* = 모델 목록이라는 등식이 틀렸고, **호출 가능한
이름의 집합은 목록보다 넓다.**

그래서 들어간 것은 exact 행 셋이 아니라 **prefix 행 셋**이고, 그 셋이 **측정된 이름 여섯 개**를 덮는다.
별칭 쪽이 배포가 실제로 적을 가능성이 높은 이름이므로, exact 로 갔다면 이 항목이 지적한 결함
("표가 이름을 못 대면 `AUTO` 가 아무것도 얻지 못한다")을 그 절반에 대해 그대로 재생산했을 것이다.

행이 **방언 하나만** 싣는 것도 착수 시점의 결정이다. `supportsSamplingParameters` 는 fail-open `true`
그대로다 — 그 이름들은 `claude-opus-4` 를 family prefix 로 쓰지 말라는 경고가 가리키는 바로 그 이름들이고,
샘플링 파라미터를 **받아 준다**. 행이 방언을 싣고 아무것도 억제하지 않는다는 것이 이 셋의 성질이다.

측정과 방법의 정정은 옛 `reasoning-model-enablement.md`
§3.5(지금은 [`../design/llm/model-capabilities.md` §6.3](../design/llm/model-capabilities.md#63-행이-기대는-측정--방법과-대조군)) 에 있고, §9 U-1 의 유보가 그것으로 census 전체에 대해 해소되었다.

---

## L-7 — `ThinkingDialect` 에 "둘 다 받는다" 를 적을 자리가 없다

*(2026-09-10 등록. 출처는 같은 프로브 — `MEASUREMENTS.md` §4. **역시 #60 / PR #64 의 영역이다.**)*

**무엇을.** `claude-opus-4-6` 과 `claude-sonnet-4-6` 은 **두 방언을 다 받아 준다**(둘 다 200). 오늘의
`ThinkingDialect` 는 `UNKNOWN | BUDGETED | ADAPTIVE` 셋뿐이라 그 사실을 표현할 값이 없다.

**왜.** 관측 가능한 결과는 **오독**이다. 두 모델은 지금 표에 없으므로 `UNKNOWN` 으로 읽히는데,
`UNKNOWN` 의 뜻은 *"표가 답할 수 없다"* 이지 *"어느 쪽이든 된다"* 가 아니다. 동작은 지금도 옳다 —
요청이 바뀌지 않으므로 아무것도 깨지지 않는다 — **틀린 것은 다음 사람이 읽게 될 이유**다. 라운드 1 의
`deviations.md` §5 는 두 모델을 빼 둔 근거를 *"샘플링 파라미터를 받아 주므로 행이 필요 없다"* 로
적었는데, 그것은 이번에 측정된 사실과 **다른 사실**이다. 즉 지금 이 자리에는 맞는 결론이 틀린 근거 위에
서 있고, `README.md` 규칙 둘이 정확히 그것을 적어 두라고 한다.

**어디.** `at.aimon.core.llm.capability.ThinkingDialect` 의 세 값, 그리고 그것을 읽는
`AnthropicThinkingResolver.resolveDialect`(2026-09-12 확인).

**언제 다시 볼까.** 셋 중 하나다 — L-6 을 착수할 때(같은 표를 여는 김에), 세 번째 방언이 생길 때, 또는
누군가 `UNKNOWN` 을 "어느 쪽이든 된다" 로 읽고 버그를 낼 때. 값을 하나 더할지(`EITHER`), 아니면
방언을 집합으로 표현할지(#61 이 `lowestReasoningEffort` → `acceptedReasoningEfforts` 로 한 것과 같은
모양)는 착수 시점의 결정이다 — 후자에는 이미 **선례가 있다**.

### 닫힘 (2026-09-10, #73). 트리거는 첫 번째였다 — L-6 을 착수하면서 같은 표를 열었다

**`EITHER` 를 골랐다.** 네 번째 상수가 들어갔고 두 모델이 그 행을 갖는다.

고른 이유는 "둘 다" 에 이름이 필요해서가 **아니다.** #73 이 그 반론을 정확히 적어 두었다 — *"`AUTO`
아래에서 '둘 다' 는 어차피 하나로 정해져야 하고, 무엇을 고르든 그것은 모델에 대한 사실이 아니라
정책이다."* 두 문장 다 참이고, 그래도 상수가 맞는 이유는 다른 데 있다: **`UNKNOWN` 은 하나의 동작이
아니라 둘이었고, 둘 다 받는 모델은 그중 하나만 원한다.**

| 자리 | `UNKNOWN` 이 하던 일 | 둘 다 받는 모델이 원하는 것 |
|---|---|---|
| 이름 붙은 모드 | 그대로 존중한다 (표가 반박할 수 없으므로) | **같다** — 표가 동의하므로 |
| `AUTO` | 아무것도 보내지 않고 경고한다 | **다르다** — 하나를 골라야 한다 |

그래서 `EITHER` 는 **`UNKNOWN` 이 하던 두 일을 쪼갠 것**이고, 그것이 이 항목이 요구한
*"`UNKNOWN` 이 서로 다른 두 상황을 덮는 일을 그만둔다"* 를 이름 바꾸기가 아니라 분해로 충족한다.
네 값은 두 쌍으로 갈린다 — `UNKNOWN` · `EITHER` 는 **표의 지식**을 말하고 요청이 절대 말하지 않는
값이며, `BUDGETED` · `ADAPTIVE` 는 와이어 모양이다. 그 불변식은 enum javadoc 에 적혀 있고 테스트가
지킨다.

`AUTO` 가 무엇을 고르는지(adaptive)는 **행이 아니라 클라이언트**에 있다. 벤더의 per-model 표가 이 두
모델에서 budgeted 쪽을 `(deprecated)` 로 표시하고, 벤더링된 SDK 가 그중 하나에 대해 매 호출 그 문장을
찍는다 — 그것이 인용이다. 사실(둘 다 받는다, 측정 날짜와 함께 레지스트리에)과 정책(`AUTO` 는 adaptive
를 선호한다, 인용과 함께 클라이언트에)이 서로 다른 자리에 적히므로 각각 따로 반박할 수 있다.

**집합(`Set<ThinkingDialect>`)은 저울에 올렸고 실력으로 진 것이 아니라 범위로 졌다.** 이 항목이 가리킨
선례(`acceptedReasoningEfforts`)는 실재하고 다른 주에는 맞는 답이었을 것이다. 여기서 제외된 이유는 둘
이며 서로 독립이다 — `ModelCapabilities` 를 바꾸는데 그 파일은 형제 작업이 편집 중이고, `thinkingDialect`
의 **설정 바인딩 모양**을 바꾸는데 그 결정도 그 작업의 것이다. 다음에 이 자리를 여는 사람이 "검토되지
않았다" 가 아니라 "검토되고 미뤄졌다" 를 읽도록 적어 둔다.

---

## L-8 — 설정된 선언은 내장 행을 **대체**하는데, 두 설정 표면은 반대로 적고 있었다

*(2026-09-10 등록. #69 를 구현하면서 나왔다. 세 문서와 한 키는 그 라운드에서 고쳤고, 여기 남는 것은
**일반형**이다.)*

**무엇을.** 선언이 내장 표에 있는 이름을 덮으면서 그 행이 말하던 플래그를 다시 적지 않았을 때, 표면이
그것을 알아채게 만든다. 가장 그럴듯한 모양은 **기동 시 WARN** 이다.

**왜.** 관측 가능한 결과는 HTTP 400 이고, 경고가 없다.

```yaml
modelCapabilities:
  claude-sonnet-5:
    thinkingDialect: unknown
```

이 항목은 `claude-sonnet-5` 에 대해 `unknown()` + 방언만 등록한다. 내장 `claude-*` prefix 행은 **두**
플래그를 말하므로(`ADAPTIVE_REFUSING_SAMPLING` = `supportsSamplingParameters(false)` +
`thinkingDialect(ADAPTIVE)`), `supportsSamplingParameters` 는 fail-open 인 `true` 로 돌아가고 설정된
`temperature` 가 그대로 나간다 — #52 가 없애려고 존재하는 그 400 이다. **억제 WARN 은 플래그가 `false`
일 때만 울리므로 아무 말도 나오지 않는다.** `thinkingMode: extended` 도 구제하지 못한다: 그 분기는
`temperature` 는 빼지만 `top_p` 는 여전히 싣는다.

**이 함정은 #69 가 만든 것이 아니라 여섯 키에 이미 있던 것이다.** `withDefaultsExtendedBy` 의 javadoc 이
규칙을 그대로 적어 두었고(*"선언은 그 이름에 대한 행 전체이지 행에 대한 패치가 아니다 … 선언에서 다시
적어 두는 것이 좋다"*), 클래스 javadoc 의 `register("o3", …)` 예제는 다섯 플래그를 다시 적으면서 그중
하나에 `// MUST stay true` 주석까지 달아 두었다. #69 가 바꾼 것은 **누가 이 함정에 걸어 들어오는가**다 —
지금까지 이 표면의 문서화된 대상은 내장 표가 들어 본 적 없는 게이트웨이 이름이었고(덮을 행이 없다),
`thinkingDialect` 는 문서화된 대상이 **언제나 행을 가진 이름**인 첫 키다.

읽는 사람을 반대로 보내는 문장이 하나 더 있다. 같은 javadoc 이 전체 교체를 *"the safe direction (the
request keeps today's shape and stays on Chat Completions)"* 이라고 부르는데, 그 괄호는 그것이 붙어 있는
o-시리즈 예제에 대해서만 참이고 `gpt-5` 계열과 `claude-*` 행에는 **거짓**이다 — 그 행들의 "오늘의 모양" 은
capability 표 이전의 모양, 즉 400 을 낸 그 `temperature` 가 실려 있던 모양이다. 이 항목을 착수하는 사람이
"함정은 무해하다" 로 결론 내릴 때 쓰게 될 문장이 정확히 저것이다.

**어디.** `InMemoryModelCapabilityRegistry.withDefaultsExtendedBy:396`(선언의 `capabilities()` 를 그대로
`register`) · `capabilitiesOf:424-430`(exact 가 prefix 를 이긴다) · `:122-123`(두 플래그짜리
`ADAPTIVE_REFUSING_SAMPLING`) · `AnthropicLlmClient.applySamplingParameters:836`(억제 게이트) —
전부 2026-09-10.

**#69 가 고친 것과 남긴 것.** 고친 것: 반대로 적고 있던 문서 셋(CLI 레퍼런스 · 스타터 가이드 ·
`default-config.yaml`)이 이제 **완전한 형태**만 처방한다. 그리고 세 모듈에 그 동작을 못박는 테스트가 있다 —
`InMemoryModelCapabilityRegistryTest.aDeclarationReplacesRatherThanPatchesABuiltInRow`,
`LlmClientFactoryTest.aDeclaredDialectAloneReplacesABuiltInRow`,
`AimonPropertiesValidationTest.aDeclarationReplacesTheBuiltInRowRatherThanPatchingIt`. 각각 완전한 형태가
억제를 지킨다는 짝 테스트를 함께 갖는다. 남긴 것: **기계 자체**와 나머지 일곱 키.

**닫는 길 셋.** 앞의 둘은 #69 의 설계가 저울에 올려 기각했다.

| 길 | 무엇을 얻나 | 무엇을 잃나 |
|---|---|---|
| **병합(merge) 모드** — 선언이 내장 행 위에 얹힌다 | 함정이 사라진다 | `withDefaultsExtendedBy` 의 javadoc 이 못박고 #46 이 결정한 규칙을 뒤집고, **오늘 있는 모든 선언의 뜻을 조용히 바꾼다.** 자기 라운드와 자기 논거가 필요하다 |
| **거절** — 내장 행을 덮으면서 그 행의 플래그를 다시 적지 않은 항목을 기동 실패로 | 가장 시끄럽다 | 오늘 유효한 설정을 깬다. 여덟 키 전부에 대해, 이 라운드가 한 키에 대해서만 급하게 만든 문제 때문에 |
| **경고** — 같은 조건에 기동 시 WARN | 맞는 모양이다. 오늘 유효한 설정을 깨지 않고 함정만 보이게 한다 | 그래도 여덟 키를 공유하는 표면 위의 **새 동작**이다. 다른 것에 대한 이슈 셋에 얹혀 갈 변경이 아니다 |

**언제 다시 볼까.** 셋 중 하나다.

1. 누군가 "선언을 했더니 모델이 **더 나빠졌다**" 를 보고할 때 — 그것이 이 함정의 관측 가능한 모양이다.
2. 이 표면에 **다음 키가 들어올 때.** 키가 늘수록 다시 적어야 할 플래그가 늘고, 처방 스니펫이 길어진다.
3. 내장 표에 **세 플래그짜리 행**이 생길 때. 오늘 최악은 두 개이고, 셋이 되면 손으로 옮겨 적는 처방이
   버티지 못한다.

---

## L-9 — `thinkingDialect` 에 설정 키가 생기면 `EITHER` 도 그 목록에 들어가야 한다

*(2026-09-10 등록. 출처는 #73 국면의 설계 —
옛 `thinking-reporting-and-dialect-records.md`
§11 O-1 — 지금은 [`../design/llm/configuration-surface.md` §3.2](../design/llm/configuration-surface.md#32-현재-키의-판정-표). **이 국면 밖으로 결과가 나가는 조율 항목이다.**)*

**무엇을.** `ThinkingDialect` 에 네 번째 상수 `EITHER` 가 들어갔다. 오늘 그것은 **자바 전용**이다 —
`ModelCapabilityDeclaration` 은 `thinkingDialect` 필드를 갖고 있지만 **어느 설정 표면도 그것을 바인딩하지
않는다**(2026-09-10 기준 `grep -rn "thinking-dialect\|thinkingDialect"` 가 CLI·스타터 main 소스에서 0건).
누군가 그 필드에 키를 주면 **그 키의 허용값 목록과 프로퍼티 메타데이터에 네 번째 값을 함께 넣어야 한다.**

**왜.** 상수를 더한 쪽과 키를 여는 쪽이 다른 작업이면, 키가 세 값만 받는 상태가 생긴다. 그러면 표에는
있는데 설정으로는 적을 수 없는 값이 하나 생기고, 그것은 이 저장소가 반복해서 정정해 온 *"선언했는데
읽히지 않는다"* 의 거울상이다 — 읽히기는 하는데 선언할 수 없다.

**어디.** `ModelCapabilityDeclaration:55` 의 필드, 그리고 키가 생긴다면
`at.aimon.cli.config.ModelCapabilityConfig` 와 스타터의
`additional-spring-configuration-metadata.json`(2026-09-10).

**언제 다시 볼까.** 그 키가 열릴 때. 형제 작업 `llm-capability-config-gaps` 가 그 결정을 갖고 있었고,
이 항목은 그 결정이 어느 쪽으로 나든 잊히지 않게 하려고 있다 — 키를 열지 않기로 했다면 이 항목은
그 결정과 함께 닫힌다.

### 닫힘 (2026-09-10, #69 · #73 두 브랜치의 병합)

**형제 작업은 키를 여는 쪽으로 결정했고**(#69, PR #76), 이 항목이 예고한 그대로 값 목록이 뒤처졌다.
두 브랜치가 각자의 게이트를 통과했는데도 그랬다 — 어느 쪽도 혼자서는 틀리지 않았기 때문이다.
`EITHER` 는 #73 쪽에만 있었고 키는 #69 쪽에만 있었으므로, 결함은 **병합으로 처음 존재하게 되었다.**
그래서 닫는 것도 병합 커밋의 일이다.

**고친 곳은 여섯이다** — `default-config.yaml` 의 주석, `ModelCapabilityConfig` 와
`AimonProperties` 의 javadoc, CLI 레퍼런스의 정본과 번역본, 그리고 `CHANGELOG.md` 에서 #69 가 값을
셋으로 적어 둔 줄. 전부 **사람이 읽는 목록**이다.

**코드는 한 줄도 바뀌지 않았고, 그것이 이 항목의 위험이 작았던 이유다.** 두 표면 모두 enum 을 직접
바인딩하므로 `thinkingDialect: either` 는 이 커밋 이전에도 이미 바인딩되었다 — 틀린 것은 동작이 아니라
**받는 값이 셋이라고 적은 문서**였다. 스타터의
`additional-spring-configuration-metadata.json` 에는 이 키의 항목이 없고 넣지 않았다: Boot 은 enum
타입 프로퍼티의 허용값을 스스로 유도하므로, 손으로 적은 목록을 하나 더 만드는 것은 다음 상수가
추가될 때 뒤처질 자리를 하나 더 만드는 것이다 — 이 항목이 기록한 실패 그 자체다.

---

## L-10 — budgeted 쪽을 선호하는 "둘 다 받는" 모델이 나오면 `EITHER` 로는 부족하다

*(2026-09-10 등록. 출처는 같은 설계 §11 O-3.)*

**무엇을.** `EITHER` 는 **선호를 싣지 않는다.** 어느 쪽을 보낼지는 `AUTO` 아래에서만 정해지고, 그 정책은
행이 아니라 클라이언트에 있다 — `AnthropicThinkingResolver.resolveAutoDialect` 가 adaptive 를 고른다(2026-09-12 확인). 오늘 그것으로 충분한 이유는
측정된 두 모델이 **둘 다** budgeted 쪽을 deprecated 로 표시하기 때문이다. 반대 방향의 모델이 나오면
`EITHER` 행 하나로는 그 사실을 적을 수 없다.

**왜.** 그때 필요한 것은 새 **사실**이고, 이 설계는 그것을 담을 자리를 만들지 않았다. 선택지는 셋이며
전부 이 국면 밖이다 — 다섯 번째 상수(`BUDGETED_PREFERRED`, 설계 §10 A3 이 "정책을 행에 넣는다"는 이유로
기각), `EITHER` 에 선호 필드, 또는 방언을 집합으로(§10 A2, `acceptedReasoningEfforts` 선례; 범위 때문에
기각되었고 실력으로 진 것이 아니다).

**어디.** `at.aimon.core.llm.capability.ThinkingDialect`, 그리고 그것을 읽는
`AnthropicThinkingResolver.resolveAutoDialect`(2026-09-10).

**언제 다시 볼까.** 벤더의 per-model 표에 `adaptive (deprecated)` 로 표시된 행이 나타날 때. 그전에는
가정이 참인지 확인할 방법이 없고, 없는 모델을 위해 상수를 더하는 것은 이 저장소가 하지 않는 종류의
일이다.

---

## L-11 — `claude-mythos` 행은 한 prefix 로 문서상 서로 다른 두 방언을 덮고 있다

*(2026-09-10 등록. 출처는 같은 설계 §11 O-7.)*

**무엇을.** 배포되는 여섯 ADAPTIVE prefix 중 다섯은 2026-09-10 에 실측으로 확인되었고
`claude-mythos` 하나만 확인되지 않았다. 확인할 수 없는 것이 아니라 **확인할 대상이 없다** — 이 계정의
`GET /v1/models` 에 그 prefix 로 시작하는 모델이 없다.

**왜.** 그 사이 새 사실이 하나 생겼다. 벤더의 per-model 표는 *Mythos 5.1* 과 *Mythos 5* 를 adaptive
전용으로, *Mythos Preview* 를 `Adaptive, extended` 로 싣는다. 즉 **하나의 prefix 가 문서상 서로 다른 두
방언 상태를 덮고 있고**, 이제 그 두 번째 상태에는 이름이 있다 — `EITHER`. 오늘의 `ADAPTIVE` 는 앞의 둘에
대해 맞고, Preview 에 대해서는 `EITHER` 의 `AUTO` 답과 같은 것을 보낸다(그래서 동작은 옳다). 틀린 것은
동작이 아니라 **행이 말하는 내용**이며, 이것은 L-7 이 두 4-6 모델에 대해 지적했던 것과 같은 모양이다.

**어디.** `InMemoryModelCapabilityRegistry.registerAnthropicDefaults` 의 `claude-mythos` 행,
그리고 그것을 인용하는
옛 `reasoning-model-enablement.md` §9 U-1
(2026-09-10) — 지금은 [`../design/llm/model-capabilities.md` §5.4](../design/llm/model-capabilities.md#54-documentation-derived-행--claude-mythos).

**언제 다시 볼까.** 그 prefix 로 시작하는 모델이 어느 계정에서든 닿을 때. 그때 두 이름을 각각 프로브하면
prefix 를 쪼갤지(Preview 만 `EITHER`) 그대로 둘지가 한 번에 정해진다. 그전에 쪼개는 것은 아무도 본 적 없는
식별자를 주장하는 일이고, 그것은 이 행이 지금 한 prefix 인 이유 그 자체다.

---

## L-12 — 라이브 서명 음성 대조 테스트가 서버 문구 두 가지 때문에 절반쯤 깜빡인다

*(2026-09-10 등록. 출처는 #73 국면의 빌드 —
옛 `thinking-reporting-and-dialect-records.md`
가 그 파일을 편집하면서 실측했다. **고치지 않기로 한 것은 의도**다 — 아래.)*

**무엇을.** `AnthropicThinkingLiveTest.ReplayedSignatureIsAccepted.mutatedSignatureIsRejected` 가
서버 응답 문구를 그대로(`hasMessageContaining`) 대조하는데, 서버가 **같은 요청에 두 가지 400 을 번갈아**
돌려준다. 어느 한쪽으로 좁히거나 둘 다 받아들이게 만든다.

```
invalid_request_error  Invalid `signature` in `thinking` block                          ← 지금 단언하는 것
invalid_request_error  messages.1.content.0: `thinking` 또는 `redacted_thinking` 블록은
                       마지막 assistant 메시지에서 수정될 수 없다 (원문 영어)              ← 실제로도 나온다
```

**왜.** **깨진 것은 주장이 아니라 문장이다.** 이 테스트의 주장은 *"서명을 한 글자 바꾸면 서버가 거절한다
— 즉 검증기가 실제로 들여다본다"* 이고, 두 응답 모두 400 `invalid_request_error` 이므로 그 주장은 어느
쪽이 오든 성립한다. 좁은 것은 문구 대조뿐이다. 그런데 그 결과는 **간헐적 빨강**이고, 간헐적 빨강은 다음
사람에게 "이 테스트는 원래 가끔 실패한다" 를 가르친다 — 이 클래스의 존재 이유(음성 대조가 유일하게
검증기를 증명한다)를 정확히 갉아먹는 방향이다.

**실측.** 같은 커밋에서 연속 6회 중 **3회 실패**(2026-09-10). CI 는 `ANTHROPIC_KEY` 가 없어 이 클래스를
건너뛰므로 게이트는 초록이고, 키를 가진 사람만 본다.

**어디.** `AnthropicThinkingLiveTest:172-190` 근방(2026-09-10, `83d9067` 기준 — 아래 닫힘 블록의 정정).

**왜 그 국면에서 고치지 않았나.** 세 이슈(#68 · #73 · #75) 어디에도 속하지 않고, 그 변경의 diff 는 이
중첩 클래스를 건드리지 않는다. 그리고 고치는 방법이 **문구를 넓히는 것**인데, 이 클래스는 *"필드 경로가
아니라 문장 전체"* 를 대조한다고 **의도적으로** 적어 두었다 — `AnthropicThinkingMode` 의 javadoc 이 그
문장들을 그대로 인용해서 운영자가 에러를 grep 해 찾아올 수 있게 하기 때문이다. 명시된 관례를 느슨하게
하는 것은 오타 수정이 아니라 결정이고, 방언 기록에 관한 diff 안에 끼워 넣을 것이 아니다.

**언제 다시 볼까.** 지금. 트리거를 기다릴 것이 없다 — 이미 발화하고 있고, 키를 가진 사람이 볼 때마다
발화한다. 형태는 작다: 상태·타입만 단언하고 문장은 둘 중 하나를 받아들이거나, 두 문장이 공유하는 더 좁은
부분 문자열로 좁히거나. **어느 쪽인지가 질문**이며, 그 결정이 이 항목이다.

### 닫힘 (2026-09-10, #81)

**두 선택지를 합쳤다.** 단언은 이제 상태·타입 — 예외가 `LlmInvalidRequestException`(400)이고 메시지에
에러 타입 필드 `invalid_request_error` 가 있다 — 에, 두 문장이 공유하는 부분 문자열 하나
`messages.1.content.0` 을 더한 것이다. 뒤의 것을 더한 이유는 그것이 **이 테스트가 서명을 바꾼 바로 그
content 블록의 경로**이기 때문이다. 상태·타입만으로는 "무엇이든 400 이면 된다" 가 되는데, 경로가 붙으면
거절이 **바꾼 블록을 가리킨다.** 다만 그 경로의 **값**은 이 테스트가 보낸 요청의 모양
(`secondTurn` 의 두 번째 메시지, 첫 번째 content 블록)에서 나오지만, 그것이 메시지에 **실리는 것**은 여전히
서버의 문구다. 두 본문이 모두 그 앞머리를 달고 오므로 어느 문장이 오든 남지만, 서버가 메시지에서 경로를 뺀다면
이 단언은 빨개지고 실패 출력이 그 메시지를 보여 준다. 그래도 넣은 것은 아래에 적은 규칙 그대로다 — 경로가 이
거절을 **요청의 다른 자리에 대한 400** 과 가른다.

**어디** *(2026-09-10)* — `AnthropicThinkingLiveTest.ReplayedSignatureIsAccepted.mutatedSignatureIsRejected` ·
`OpenAIReasoningLiveTest.TheReproduction.theSameRequestOnChatCompletionsIsStillTheOriginal400`

> **정정** *(2026-09-10, #90)*: 이 줄은 처음에 두 좌표를 줄 번호로 적었다 — `AnthropicThinkingLiveTest:188` ·
> `OpenAIReasoningLiveTest:229`. 앞의 것은 맞았다. 뒤의 것은 **이 닫힘을 쓴 커밋(`e91b850`)에서 이미** 음성
> 대조의 `@DisplayName` 줄이었고 메서드는 `:230` 이다 — 같은 커밋이 그 파일에 `BadRequestException` import 한
> 줄을 더했고, 줄은 그 전의 파일에서 셌다. 둘 다 메서드 이름으로 바꾼 것은 다음 import 한 줄이 같은 일을 다시
> 하지 않게 하려는 것이다. 위 항목 본문의 `:172-190` 은 종류가 다르다: 등록한 커밋(`83d9067`)에서는 맞았고 —
> 메서드가 `:173` 이었다 — 같은 날 뒤의 커밋(`eedaa8f`)이 그 파일을 고치며 `:188` 로 밀렸다. 등록 시점의
> 기록이므로 이름으로 바꾸지 않고, 날짜만으로는 그날의 어느 판본인지 풀리지 않으므로 커밋을 붙였다. 같은 날
> 쓴 좌표 둘이 [`live-api-test-tier.md`](live-api-test-tier.md) LA-1 에서도 틀려 있었고 거기서 함께 고쳤다.

**착수 시점의 재측정이 등록 근거를 그대로 확인했다.** 같은 커밋, 같은 요청으로 연속 6회를 돌렸고 본문은
정확히 3 대 3 으로 갈렸다(2026-09-10). 여섯 모두 400 `invalid_request_error` 였다.

```
messages.1.content.0: Invalid `signature` in `thinking` block                                        ← 3회
messages.1.content.0: `thinking` or `redacted_thinking` blocks in the latest assistant message
                      cannot be modified. These blocks must remain as they were in the original response. ← 3회
```

위 항목 본문의 인용은 두 줄 다 원문과 달랐고, 그 차이는 이 닫힘의 단언에 직접 닿는다. 첫째 줄은 앞머리
`messages.1.content.0:` 없이 적혀 있었고, 둘째 줄은 문장을 한국어로 옮긴 것이었다. 실제 본문은 **둘 다 그
경로로 시작하며**, 좁힌 단언이 기대는 공유 부분 문자열은 바로 그것이다. 등록 당시의 인용만 보고 이 단언을
검토하면 첫째 본문에서 실패할 것처럼 읽히므로, 원문은 위의 코드 블록을 기준으로 삼는다.

**"왜 그 국면에서 고치지 않았나" 의 근거는 절반이 틀렸다** — [`README.md`](README.md) 규칙 둘. 항목은
고치는 방법이 문구를 넓히는 것이고, 그것이 이 클래스가 **의도적으로** 적어 둔 관례 — 필드 경로가 아니라
문장 전체를 대조한다, `AnthropicThinkingMode` 의 javadoc 이 그 문장들을 인용하므로 — 를 느슨하게 하는
결정이라고 적었다. 관례는 실재한다. **다만 이 테스트의 것이 아니었다.**

| | `DialectMismatchesAreRejected` | `ReplayedSignatureIsAccepted.mutatedSignatureIsRejected` |
|---|---|---|
| 대조하던 문장 | 방언 거절 두 문장 | 서명 거절 문장 |
| `AnthropicThinkingMode` 의 javadoc 이 인용하는가 | **그렇다** — 정확히 그 두 문장 | **아니다** |
| 이 단언이 참으로 지켜 주던 grep 대상 | 있다 | 없다 |

`AnthropicThinkingMode` 가 *"The two server messages, verbatim, so that an operator who greps the error text
lands here"* 로 인용하는 것은 방언 두 문장뿐이다. 서명 문장의 앞머리 ``Invalid `signature` in `thinking` block``
는 `AnthropicConfig.Builder#replayThinkingBlocks` 의 javadoc 에도 나오지만, 그것은
*"The block is bound to a different conversation."* 으로 끝나는 **다른 실패** — 접두 불일치 — 이고 이
테스트는 그 실패를 일으키지 않는다. 그러니 이 단언을 좁혀도 **어떤 grep 대상도 거짓이 되지 않는다.**
관례를 느슨하게 한 것이 아니라, 관례의 사정거리 밖에 있던 단언을 그 관례에서 떼어 낸 것이다. 방언 두
문장의 전체 대조는 그대로 두었고, 테스트의 새 주석이 그 경계를 적는다.

**같은 클래스에서 좁히지 않은 문장이 하나 있다 — 그리고 그 경계가 이 닫힘의 규칙이다.**
`AdaptiveReachability.withoutTheCapabilityRowTheSameCallIsRejected` 도 서버 문장
``"`temperature` is deprecated for this model"`` 을 대조하는데, 그것은 그대로다. 그 테스트의 주석이 이유를
적어 두었다 — *"a bare "temperature" substring would also pass on a range-validation error."* 거기서는
문장이 **주장을 이웃한 다른 400 과 가르는 유일한 것**이다. 서명 테스트는 반대다: 두 본문이 **같은 주장**
(검증기가 바뀐 블록을 알아챘다)을 말하므로 문장은 주장의 일부가 아니다. 규칙으로 쓰면 이렇다 —
**문장이 주장을 이웃한 실패와 가를 때만 문장을 대조한다.** 이것을 적어 두는 이유는 다음 사람이 이 닫힘을
선례로 들어 온도 테스트를 같은 병으로 좁히지 않게 하려는 것이다(규칙 다섯 — 값이 왜 그 값인지 설명이
있으면 그것은 결함이 아니라 결정이다).

**심각도는 적힌 그대로였다** — 규칙 셋. 등록은 6회 중 3회를, 재측정도 3 대 3 을 냈다.

**처방은 실제 본문에 대고 확인했다** — 규칙 다섯. 초록 실행만으로는 이것이 증명되지 않는다: 좁힌 단언은
어느 본문이 왔는지 출력하지 않으므로, 초록 N회는 둘째 본문이 한 번이라도 왔다는 증거가 아니다. 그래서 둘을
따로 적는다.

- **술어** — 위 6회에서 받은 실제 본문 여섯이 전부 두 부분 문자열을 담는다. 첫째 본문 셋과 둘째 본문 셋이
  모두 참이므로, 좁힌 단언은 두 본문 어느 쪽에서도 참이다
- **반복** — 좁힌 단언을 담은 최종 바이트(포맷 후)에서 `AnthropicThinkingLiveTest` 를 연속 6회 돌렸고 6회 모두
  초록이었다 — 단독 5회(매회 12건 통과, 태스크가 `UP-TO-DATE` 가 아니라 실제로 돌았다)와, 이어서 네 클래스를
  함께 돌린 1회(42건 통과)다. 서명 음성 대조는 6회 모두 통과했다(2026-09-10). 어느 본문이 왔는지는 보이지
  않는다. 한 회가 둘째 본문일 확률을 재측정한 3/6 으로 잡으면 6회가 전부 첫째 본문이었을 확률은 (1/2)^6,
  약 1.6% 다 — 6회 표본에서 나온 추정이지 관측이 아니다. 둘째 본문에서도 단언이 참이라는 증명은 위의
  **술어**가 맡는다

**같은 결함의 두 번째 사례도 여기서 닫는다. 새로 등록하지 않는다.** `OpenAIReasoningLiveTest` 의
`TheReproduction.theSameRequestOnChatCompletionsIsStillTheOriginal400` 이 #43 에 붙은 OpenAI 문장을 문구
그대로 대조하고 있었다 — #71 의 빌드 리뷰가 막지 않음으로 보고했고 어디에도 등록되지 않았다. 이쪽은
깜빡이지 않는다: 2026-09-10 에 같은 요청으로 세 번 받은 본문이 세 번 다 같았다. 깨질 날은 비결정성이 아니라
**OpenAI 가 문구를 고치는 날**이고, 그날 이 테스트는 #43 의 라우팅 회귀와 구별되지 않는 빨강이 된다.
단언은 이제 메시지를 읽지 않는다. `OpenAIExceptionMapper` 가 원인으로 보존하는 SDK 의 `BadRequestException`
에서 **구조화된 필드 둘** — `type` 이 `invalid_request_error`, `param` 이 `reasoning_effort` — 을 본다.
2026-09-10 에 실측하니 서버가 둘 다 채웠다(`code` 는 비어 있었다). 메시지 문구를 고쳐도 이 두 필드는 바뀌지
않고, `param` 은 거절된 파라미터를 이름으로 가리키는 필드이므로 이 거절을 같은 요청의 다른 파라미터에 대한
400 과 가른다. 모델과 엔드포인트는 테스트가 스스로 정하므로(`config(REASONING_MODEL).responsesApiEnabled(false)`)
서버가 되풀이하게 할 이유가 없다.

**이 단언의 첫 판본은 틀렸고, 리뷰가 잡았다** — 규칙 둘. 처음에는 `reasoning_effort` · 모델 · 엔드포인트 세
부분 문자열을 메시지에서 대조하면서 *"the parts OpenAI cannot reword without changing the API itself"* 라고 주석에 적었다.
그런데 SDK 가 만드는 메시지는 `"400: " + error.message` 이고 구조화된 필드는 그 안에 없다. 세 부분 문자열은
전부 **같은 영어 문장**에서 나왔으므로, 평범한 문구 변경 하나 — 예컨대 *"Function tools with reasoning_effort
are not supported for this model. Use the Responses API."* — 로 셋 중 둘이 깨졌을 것이다. 그리고 주석이 그것은
문구 변경일 수 없다고 말하고 있었으므로 읽는 사람은 그 빨강을 라우팅 회귀로 읽었을 것이다 — 이 작업이
없애려던 실패 그 자체다. 구조화된 필드가 있는지는 추측하지 않고 한 번 호출해서 확인했다.

**이 항목이 속한 더 큰 결정은 [`live-api-test-tier.md`](live-api-test-tier.md) 에 있다** — 이 계층에 CI
신호가 없고, 그래서 L-12 같은 썩음은 뜻밖의 일이 아니라 정상 상태라는 것. #81 에서 메인테이너가 수동
전용을 골랐다.

---

## L-13 — 선언에서 descriptor 로 가는 세 번째 손 전달에는 가드가 없다

*(2026-09-10 등록. 출처는 #82 —
옛 `model-capability-binding-round-trip.md`
§9 O-1(지금은 [`../design/llm/configuration-surface.md` §8.1](../design/llm/configuration-surface.md#81-무엇을-보는가)). 그 문서 §11 이 이 항목으로 올린 이유를 적는다.)*

**무엇을.** `ModelCapabilityDeclaration` 이 `ModelCapabilities` 를 만드는 한 줄씩의 전달에도, #82 가 두 설정
표면에 붙인 것과 같은 키별 왕복 확인을 붙인다.

**왜.** #82 는 표면 → 선언 사이의 손 전달(`declarationOf` · `toDeclaration()`)을 키마다 자동으로 확인하게
만들었다. 그 다음 고리가 같은 모양이다 — `resolve(Builder)` 가 필드마다
`if (builder.x != null) resolved.x(builder.x)` 를 한 줄씩 적는다. 아홉 번째 키를 builder 에 더하고 여기서 한
줄을 빠뜨리면 **선언은 맞고, 클라이언트가 읽는 descriptor 에는 그 키가 없다.** 관측 가능한 결과는 #69 · #82
와 같다 — 운영자가 적은 키가 바인딩되고, 아무 메시지도 없고, 요청은 그 키가 없었던 것처럼 나간다. 그리고
이번에는 두 표면 가드가 **둘 다 초록**이다. 그 가드들은 선언까지만 보도록 일부러 범위를 그었다(설계 §2.1).

**어디.** `ModelCapabilityDeclaration.java:76-105` 의 `resolve(Builder)`(2026-09-10). 프로덕션에서 닿는다 —
main 소스에서 `capabilities()` 를 읽는 곳은 `InMemoryModelCapabilityRegistry.java:477` 하나이고, 그
`withDefaultsExtendedBy` 를 CLI(`LlmClientFactory.java:231`)와 스타터(`AimonProperties.java:772`)가 부른다.
`\.withDefaultsExtendedBy(` 와 `::withDefaultsExtendedBy` 두 형태로 셌다(`README.md` 규칙 여섯).

**지금 무엇이 반쯤 막고 있나.** 완전히 무방비는 아니다(규칙 셋). `ModelCapabilityDeclarationTest` 의
`theRefusalMessageNamesEveryDeclarableKey` 는 `assertThat(setters).hasSize(8)`(`:119`)을 갖고 있어서, 키를
더하는 사람은 **그 테스트 파일을 반드시 연다.** 다만 거기서 요구되는 편집은 숫자 하나다 — `capabilities()` 를
확인하는 `everyFlagRoundTrips` · `theLadderIsDeclarableAsASet` 는 키마다 손으로 적혀 있어서, 숫자를 9 로 고치고
단언을 더하지 않아도 초록이다. 사람을 파일 앞에 세울 뿐, 빠진 줄을 가리키지는 않는다.

**처방은 적용해 보지 않았다(규칙 다섯).** 모양은 보인다. `aimon-core` 는 선언과 `ModelCapabilities` 를 둘 다
보므로, 키마다 `declaration.capabilities()` 가 `ModelCapabilities.builder()` 에 **같은 이름의 setter** 를 같은
값으로 부른 결과와 같은지 비교하면 된다 — 오늘 `ModelCapabilities.Builder` 는 여덟 키 전부에 같은 이름의
setter 를 갖고 있다(2026-09-10 확인, 불리언은 원시형이라 값이 언박싱된다). 사다리 짝은 `ModelCapabilities` 에서
하나로 합쳐지지만 전체 객체 비교라 걸리지 않아 보인다. 정하지 않은 것도 있다 — `aimon-llm-capability-testkit`
의 probe 를 코어 테스트가 그대로 쓸지(`aimon-core:test` → testkit:main → `aimon-core:main`,
`aimon-filesystem-testkit` 이 이미 그 모양이다), 코어 안에 작은 사본을 둘지.

**언제 다시 볼까.** 다음에 선언 키를 더하는 사람이 `hasSize(8)` 을 고칠 때 — 이 구멍이 실제로 열리는 순간이
그때다. 그보다 먼저 하면 더 싸다.

---

## L-14 — 바인더 고리는 여전히 키마다 손으로 확인되고, 스타터에는 아무도 확인하지 않는 키가 셋 있다

*(2026-09-10 등록. 출처는 #82 — 같은 설계 §9 O-2, 그리고 이 항목을 등록하며 센 것.
설계 §2.7 의 표가 이 세기로 **절반 틀렸다** — 그 문서 §11 에 정정이 있다.)*

**무엇을.** 운영자가 적은 **텍스트**가 설정 표면 객체에 도착하는지 — CLI 는 Jackson, 스타터는 Boot relaxed
binding — 를 새 키에 대해서도 손을 대지 않고 확인되게 만든다.

**왜.** #82 의 계약은 bean setter 로 쓰므로 바인더를 지나가지 않는다. 설계가 의도한 범위다(§2.1, §8 A8).
그 고리는 키마다 손으로 쓴 테스트에 맡겨져 있고, 설계 §2.7 은 그것을 "키마다 덮여 있다" 고 적었다.
등록하며 세어 보니 한쪽 표면만 맞았다(규칙 둘).

| 표면 | 설계 §2.7 이 적은 것 | 센 결과 (2026-09-10) |
|---|---|---|
| CLI | `LlmClientFactoryTest.ModelCapabilityDeclarations` 가 yaml 모양의 객체로 덮는다 | 그 클래스는 `ModelCapabilityConfig` 를 **자바 setter 로** 만들므로 Jackson 을 지나지 않는다. 바인더를 실제로 지나는 것은 `CliConfigLoaderTest` 의 yaml 문자열이고, 거기에 여덟 키가 **전부** 있다(일곱이 `:576-582`, `acceptedReasoningEfforts` 는 `:649` 등) |
| 스타터 | `AimonPropertiesValidationTest` 의 프로퍼티 문자열이 덮는다 | 여덟 중 **다섯**만 있다. `supports-reasoning-effort` · `supports-tools-with-reasoning` · `supports-reasoning-trace-round-trip` 은 스타터 테스트 소스 **어디에도** 프로퍼티 문자열로 없다 — kebab 과 camelCase 두 철자로 셌고, camelCase 로 걸린 두 건(`:612-613`)은 결과 단언이다 |

**심각도.** 오늘 빠진 셋은 전부 `Boolean` 이고, 스타터의 kebab 이름은 bean 프로퍼티 이름에서 파생되므로
#82 계약의 첫 확인(이름이 있고 getter · setter 가 있다)이 통과하는 한 이름이 어긋날 길은 좁다(규칙 셋).
실제로 틀릴 수 있는 자리는 **변환**이다 — enum 의 대소문자 접기, 쉼표 목록과 인덱스 목록, 빈 원소. #70 이
두 표면에서 실측으로 고친 곳이 정확히 거기였다. 그러니 이 항목의 값은 오늘의 세 키보다 **변환이 필요한
타입을 가진 다음 키**에 있다: 그 키는 두 가드를 초록으로 지나가고, 바인더 테스트는 누가 손으로 쓰기 전까지
없다. CLI 는 모르는 필드에 기동이 실패하므로 **이름** 쪽은 스스로 시끄럽지만, 스타터에는 그 안전망도 없다(L-1).

**모양.** 설계 §8 A8 — 계약을 바인더까지 늘리기 — 은 실패가 "이름이 바인딩되지 않음" 과 "값이 떨어짐"
사이에서 모호해지고, 값을 **텍스트로 렌더링**하는 규칙(yaml 시퀀스 대 쉼표 목록)이 표면마다 달라서
기각되었다. 그 이유가 여전히 맞다면 착수는 #82 계약에 붙이는 것이 아니라 표면마다 렌더러를 하나씩 받는
**별개의** 계약이 된다. 적용해 보지는 않았다.

**언제 다시 볼까.** 타입이 `Boolean` 이 아닌 선언 키 — enum · 목록 · 형식이 있는 문자열 — 를 더할 때.
또는 L-1 을 착수할 때: `ignoreUnknownFields` 에 대한 결정이 스타터 바인더 고리에 무엇이 필요한지를 바꾼다.
스타터의 세 키만 따로 막는 것은 한 줄짜리 테스트 셋이라 언제든 싸지만, 위 심각도대로 그것이 이 항목을 닫지는
않는다.

---

## L-15 — thinking 예산 clamp 경고가 `only 1 tokens` 로 읽히고, 듣는 처방 둘 중 하나만 말한다

*(2026-09-10 등록. 출처는 #83 —
옛 `thinking-reporting-and-dialect-records.md` §16 — 지금은
[`../design/llm/anthropic-thinking.md` §6.2](../design/llm/anthropic-thinking.md#62-auto-예산-정책--1토큰-답도-그대로-둔다).
**그 결정 안에서 고치지 않은 것은 의도**다 — 기록하는 대상을 같은 PR 에서 바꾸지 않는다.)*

**무엇을.** clamp 경고의 문구를 고친다 — 복수형을 바로잡고, 같은 편집에서 두 번째 처방(reasoning effort 를
`low` · `minimal` 로 내리는 것)을 문구에 넣을지 정한다.

**왜.** 문구의 `"leaves only {} tokens"` 에서 `{}` 는 `maxTokens - resolved` 이고, 이 경고가 뜨는 요청에서 그
값은 **언제나 1** 이다. clamp 는 언제나 `maxTokens - 1` 에 떨어지기 때문이다 — `AnthropicConfig` 가 1024 미만
예산을 거절하고(`AnthropicConfig.java:85-87`) 사다리의 바닥도 1024 이므로 요청된 예산은 1024 이상이며,
`maxTokens` 가 1024 이하면 예산 자체를 포기한다(`AnthropicThinkingBudgets.java:136-141`). 그러니 바닥이 결과를
끌어올리는 일은 없고, 예산이 `maxTokens - 1` 을 넘을 때만 clamp 가 일어나며 결과는 정확히 그 값이다. 즉 이
경고는 **뜰 때마다**(`extended` 포함) `only 1 tokens for the visible answer` 로 나간다. 그리고 문구가 말하는
처방은 `Raise maxTokens` 하나인데 실제로 듣는 편집은 둘이다 — effort 를 `low`(2048) 나 `minimal`(1024) 로 내려도
기본값 4096 아래로 들어간다(§16 의 이유 4).

**어디.** `AnthropicThinkingResolver.java:421-424`(2026-09-10). 그 요청을 못박는 테스트
`AnthropicThinkingDialectTest.autoOnABuiltInBudgetedRowClampsUnderTheConfigDefaultMaxTokens` 는
`"does not fit under maxTokens"` · `"Raise maxTokens"` 두 조각만 단언하므로, 두 조각을 남기는 한 문구를 고쳐도
초록이다.

**언제 다시 볼까.** 그 문구를 다음에 건드릴 때, 또는 §16 의 재검토 트리거 중 하나가 발화할 때. 기다릴 크기가
아니므로 지금 집어도 된다.

---

## L-16 — 도구 호출 안에서 `max_tokens` 로 잘린 응답은 에이전트 경로 어디서도 `max_tokens` 라는 이름을 얻지 못한다

*(2026-09-10 등록. 출처는 #89 —
옛 `thinking-reporting-and-dialect-records.md` §16.8 — 지금은
[`../design/llm/anthropic-thinking.md` §6.4](../design/llm/anthropic-thinking.md#64-clamp-경고의-범위--clamp-만).
그 결정은 "실제로 잘린 응답은 일어났을 때 보고된다" 를 이유 하나로 삼는데, 그 이유가 **두 모양 중 하나에서만** 참이라는
것이 이 항목이다. 결정을 뒤집지는 않는다 — 이 틈은 숫자 없이 닫히고, 경고 임계값을 두어도 그 너머에서 그대로 남는다.
설정 표면 항목은 아니다. 여기 두는 것은 같은 결정의 짝인 L-15 가 여기 있어서다.)*

**무엇을.** 에이전트 루프가 받은 응답이 `max_tokens` 로 끝났고 그 안에 도구 호출이 있을 때, 그 사실을 운영자가 보는
곳에 `max_tokens` 라는 이름으로 남긴다.

**왜.** 관측 가능한 결과는 이렇다 — thinking 예산이 출력 허용량을 거의 다 쓰는 요청(§16.8 의 `maxTokens: 4097` 처럼)에서
모델이 도구 호출을 쓰다가 잘리면, 운영자가 보는 것은 **인자가 빈 도구 호출** 하나이고 로그 어디에도 `max_tokens` 가
없다. 인자가 JSON 조각까지 왔으면 파싱 실패 WARN 이 뜨지만 왜 조각인지는 말하지 않고, 하나도 오기 전에 잘렸으면 그마저
없다. 도구가 필수 파라미터를 선언했다면 기본값인 `WARN` 모드의 스키마 검증이 한 줄을 더 남기고 도구를 그대로
실행한다(`DefaultToolExecutor.java:194-197`, `:201`) — 그 줄도 이유는 말하지 않는다. 도구 호출이 없는 마지막 턴이
잘리면 `TRUNCATED` 로 끝나며 이름이 붙는 것과 대조된다.

신호를 경로별로 세면 이렇다(`README.md` 규칙 여섯 — `convertResponse` 의 호출자는 `convertResponse(` 와
`::convertResponse` 두 형태로 셌다).

| 신호 | 어디 | 에이전트에서 닿는가 |
|---|---|---|
| 도구 호출 없는 마지막 턴의 `CompletionReason.TRUNCATED`, 마커, WARN | `OrcaAgentExecutor.java:1737-1752`, `:217`, `:2289` | **닿는다**, 두 경로 모두. 단 `!response.hasToolUses()` 일 때만이다 |
| 도구 호출이 있는 응답 | `OrcaAgentExecutor.java:1768-1770` 이 그대로 도구를 실행한다 | 이 분기는 stop reason 을 읽지 않는다. 실행기에서 `LlmResponse.getStopReason()` 을 읽는 곳은 `:1742` 하나다(`:2548` 은 `BudgetTracker` 의 같은 이름 메서드다) |
| Anthropic 클라이언트의 `Anthropic response was truncated due to max_tokens limit` | `AnthropicLlmClient.java:786`, `convertResponse` 안 | **닿지 않는다.** `convertResponse` 의 호출자는 `:249` 하나이고, 그것은 살아 있는 취소 토큰 **없이** 들어온 blocking 호출이다. 토큰이 `isSupported()` 면 blocking 호출도 스트리밍 경로로 돌아간다(`:282-286`). 실행기는 실행마다 `SignalBackedLlmCancellation` 을 만들어(`OrcaAgentExecutor.java:1529`) blocking 호출에도 넘기고(`:2987-2988`), 게이트웨이가 그대로 전달하며(`LlmCallGateway.java:401`), 그 타입은 `isSupported()` 를 재정의하지 않아 기본값 `true` 다(`LlmCancellation.java:70-72`) |
| Anthropic 스트리밍 매퍼 | `AnthropicStreamingMapper.java` | `max_tokens` 정지에 아무것도 남기지 않는다. WARN 은 토큰 수 범위 초과(`:360`) 하나, 보고는 서명 없는 thinking 블록(`:282`) 하나다 |
| CLI | `modules/aimon-cli/src/main/java` | `StopReason` 을 읽는 파일이 없다 |

> **정정** *(2026-09-11, #101)*: 위 표에서 한 행은 틀렸고 두 행은 불완전했다. 등록 본문은 규칙 둘대로 고치지 않고
> 두며, 지금의 사실은 아래 닫힘 블록이 적는다.
>
> - **3행이 틀렸다.** `convertResponse` 의 WARN 이 닿지 않는 것은 두 ReAct 루프의 호출뿐이다 — 둘 다 살아 있는 취소
>   토큰을 넘기므로 호출이 스트림으로 간다. blocking 오버로드를 부르는 호출자는 **닿는다**: 컴팩션
>   (`DefaultCompactionEngine`, `LlmClient` 의 다섯 인자 기본 구현을 거쳐), 스킬 LLM 실행(`LlmSkillExecutor`), CLI 가
>   조립하는 peer memory(`LlmDialecticEngine` · `LlmDeriver` · `DefaultReconciler` · `RandomWalkDreamer` ·
>   `LlmJudgeSurprisalScorer`), 위키 전략들. 행이 적은 "에이전트에서 닿지 않는다" 는 "ReAct 루프의 호출에서 닿지
>   않는다" 였다.
> - **1·2행은 `OrcaAgentExecutor` 만 서술했다.** 서브에이전트 포크(`DefaultSubagentExecutor`)는 stop reason 을 아예
>   읽지 않았으므로, 포크의 잘린 최종 답은 `COMPLETED` 로 부모에게 갔고 잘린 도구 호출은 그대로 실행되었다(#100).

잘린 도구 호출이 여전히 도구 호출인 이유는 세 줄이다. 매퍼는 `tool_use` 블록이 **시작될 때** 슬롯을 등록하고
(`AnthropicStreamingMapper.java:175`), `ChunkAggregator.toLlmResponse` 는 id 와 이름이 있는 슬롯을 전부 도구 호출로
만들며(`ChunkAggregator.java:247-253`), 인자는 비었으면 조용히 `Map.of()`, 파싱에 실패하면 WARN 과 함께 빈 맵이
된다(`:287-298`).

**어디.** 위 표의 줄들(2026-09-10, `ade5978`). 틈의 모양을 한 줄로 가리키면 `OrcaAgentExecutor.java:1737` 의 분기
조건이고, 그 틈을 메우는 것처럼 보이는 줄이 에이전트에서는 실행되지 않는 `AnthropicLlmClient.java:786` 이다.

**심각도 (규칙 셋).** 읽어서 얻은 결론이고 돌려 보지는 않았다 — 실제 API 가 잘린 `tool_use` 블록에도
`content_block_stop` 을 보내는지, 부분 JSON 이 어떤 모양으로 오는지는 라이브 호출 없이 확인하지 않았다. 다만 위 결론은
그 둘에 기대지 않는다: 슬롯은 블록 **시작**에서 등록되므로 멈춤 이벤트가 없어도 도구 호출은 생긴다. 틈은 크래시가
아니라 이유가 적히지 않은 도구 오류로 나타날 것으로 보이고, 모델이 같은 호출을 되풀이할 때 정체 가드
(`MAX_CONSECUTIVE_STALLED_ITERATIONS`, `OrcaAgentExecutor.java:226`)에 닿는지는 세지 않았다. 그리고 #89 의 설계도 그
리뷰도 `convertResponse` 의 WARN 을 신호로 셌다 — 취소 토큰을 따라가지 않으면 그 줄은 **정확히 인용되고 에이전트에서는
한 번도 실행되지 않는다**(규칙 여섯의 모양이다).

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 보인다 — 실행기가 도구 호출이 있는 응답에서도
`StopReason.isTruncated()` 를 읽어 이름을 붙이는 것(프로바이더 중립이고 두 경로에 공통이다), 그리고 thinking 이 켜진
요청이면 그 잘림을 `usage.output_tokens_details.thinking_tokens` 로 thinking 에 귀속시키는 것(`AnthropicUsages` 가 이미
읽는다. §16.8 이 숫자 없는 신호로 적어 둔 것이다). 정하지 않은 것이 둘 있다 — 잘린 도구 호출을 **여전히 실행할지**(지금은
실행한다), 그리고 `convertResponse` 의 WARN 을 없앨지 스트리밍 쪽으로 옮길지. OpenAI 클라이언트의 같은 WARN
(`OpenAIChatCompletionsExchange.java:85`, `OpenAIResponsesExchange.java:100`)이 같은 우회에 걸리는지는 세지 않았다.

**언제 다시 볼까.** 실행기의 도구 호출 분기를 다음에 건드릴 때, 또는 인자가 빈 도구 호출이 이유 없이 보고될 때. 기다릴
크기가 아니므로 지금 집어도 된다. 이 항목이 닫히면 §16.8 의 이유 3 이 두 모양 모두에서 참이 되므로, 그 문장과
"The cost" 를 같은 편집에서 고친다.

### 닫힘 (2026-09-11, #108 · #100 · #101)

**이제 두 에이전트 실행기가 `max_tokens` 에서 잘린 응답에 같은 답을 준다.** 도구 호출이 없는 최종 답은 턴이든
서브에이전트 포크든 `CompletionReason.TRUNCATED` 로 끝나며 `[System: response truncated at max_tokens]` 마커와 WARN 이
붙는다. 도구 호출이 있는 응답은 **어느 호출도 실행하지 않는다** — 호출마다 `max_tokens` 를 이름으로 대는 오류 결과로
답하고, WARN 이 `max_tokens` · iteration · 도구 이름을 적고, 루프는 이어진다. 두 실행기 모두 프로바이더 중립의
`StopReason` 을 `at.aimon.core.agent.budget.TruncatedResponses` 한 곳에서 읽는다. 설계와 기각한 대안은
[`../design/agent-execution/max-tokens-truncation-reporting.md`](../design/agent-execution/max-tokens-truncation-reporting.md)
에, 기록의 정정은
옛 `thinking-reporting-and-dialect-records.md` §16.10(지금은
[`../design/llm/anthropic-thinking.md` §6.4](../design/llm/anthropic-thinking.md#64-clamp-경고의-범위--clamp-만))에 있다.

**처방이 열어 둔 것을 한쪽으로 정했다 (규칙 다섯).** 이 항목은 잘린 도구 호출을 *여전히 실행할지* 를 열어 두었다.
실행하지 않기로 했다. 잘린 호출의 인자는 부분이 아니라 없다 — 부분 JSON 은 파싱에 실패해 빈 맵이 된다. 그리고 중립
응답은 **어느 호출이 잘렸는지 말하지 않으므로** 완결돼 보이는 호출도 함께 거절한다. 잘린 것만 고르려면 "마지막 호출"
이라는 프로바이더 사실을 추측해야 하고, 틀리면 인자 없는 호출을 실행한다 — 고치려는 결함 그대로다. thinking 귀속은
적어 둔 모양대로 `usage.output_tokens_details.thinking_tokens` 를 거쳤다. 그 값이 채우는 `TokenUsage.getReasoningTokens()`
가 0 보다 크면 네 truncation WARN 이 출력·추론 토큰 수를 붙이고, 0 이면 아무것도 붙이지 않는다. 비율로 판정하지는
않는다 — 그러려면 §16.8 이 거절한 숫자가 필요하다. `convertResponse` 의 WARN 은 없애지도 옮기지도 않았다(위 정정
블록의 호출자들이 그것을 읽는다).

**처방은 실패하는 테스트로 먼저 확인했다.** 기존 `maxTokensWithToolUsesIsNotTreatedAsTruncated` 는 도구가 실행됐는지를
보지 않아 어느 결정에서도 초록이었다. 그래서 그 자리를 대신한 테스트를 포함해 새 테스트를 **옛 실행기에 대고 먼저**
돌렸고, 네 클래스 40건 중 12건이 적힌 이유로 실패했다 — 도구 호출 수 `1`(단일 · 스트리밍) · `2`(둘), 오버랩에서 수확된
`"ran"` 결과, 정체 가드 대신 `COMPLETED`, 잘린 `Skill` 호출의 `SUSPENDED`, 포크의 `COMPLETED`, `max_tokens` 를 적은
WARN 의 부재. 수정 뒤 같은 네 클래스는 40건 모두 초록이다.

**표의 근거는 위 정정 블록이 적는다 (규칙 둘).** 3행은 틀렸고 1·2행은 불완전했다.

**세지 않았던 둘을 셌다.**

- **OpenAI 의 두 WARN 도 같은 우회에 걸린다.** `OpenAILlmClient` 의 여섯 인자 오버로드는 지원되는 취소 토큰을 받으면
  호출을 스트림으로 돌린다 — Anthropic 과 같은 자리다. 그러니 두 실행기에서는 닿지 않고, 이제 실행기의 WARN 이 그
  자리를 맡는다. 추론 토큰 수는 OpenAI 에서 Responses 경로만 채운다(`OpenAiResponseUsages`). Chat Completions 는 0 으로
  두므로 거기서는 WARN 에 절이 붙지 않는다.
- **정체 가드는 잘린 도구 응답을 센다.** 거절된 iteration 은 결과가 전부 오류이므로 `isStalledIteration` 이 참이고, 턴은
  연속 세 번이면 `ERROR` 로 끝난다 —
  `OrcaAgentExecutorTruncationTest.threeCutToolResponsesInARowTripTheStalledIterationGuard`. **포크에는 가드가 없다.**
  매 응답이 잘리는 포크는 `maxIterations`(서브에이전트가 정하지 않으면 1000)까지 거절을 반복한다 — L-23.

**심각도 (규칙 셋).** 등록 시점에는 돌려 보지 않았다. 2026-09-11 에 요청 하나로 쟀다 — Anthropic Messages API
스트리밍, `claude-haiku-4-5`, 도구 하나를 `tool_choice` 로 강제, `max_tokens: 60`, HTTP 200
(`req_011Cevqhawj5VtNbJMQGcfSN`). 이벤트는 `message_start` → `content_block_start`(`tool_use`) → `ping` →
`content_block_delta`(`input_json_delta`) 다섯 → `message_delta`(`stop_reason: max_tokens`) → `message_stop` 였고,
**잘린 `tool_use` 블록에 `content_block_stop` 은 오지 않았다.** 누적된 인자는 객체가 닫히지 않은 JSON 이므로
`ChunkAggregator` 는 파싱 실패 WARN 과 함께 빈 맵을 만든다 — 그 경로를 이 스트림으로 돌리지는 않았고 코드에서 읽었다.
즉 수정 전에는 이 항목이 적은 모양 그대로, 인자가 빈 도구 호출이 이유 없이 실행되었을 것이다. 요청 하나의 관측이지
보장이 아니며, 위 결정은 그것에 기대지 않는다.

**어디** *(2026-09-11)* — `TruncatedResponses`, `OrcaAgentExecutor` 와 `DefaultSubagentExecutor` 각각의
`refuseTruncatedToolUses` · `createTruncatedResult`, 테스트 `OrcaAgentExecutorTruncationTest` ·
`DefaultSubagentExecutorTruncationTest` · `TruncatedResponsesTest` ·
`OrcaAgentExecutorSkillSuspendTest.aSkillCallInACutResponseIsRefusedNotSuspended`.

---

## L-17 — 번들 서브에이전트의 `model: haiku` 는 별칭으로 풀리지 않고 그대로 나가며, Anthropic 은 그 이름에 404 를 준다

*(2026-09-11 등록. 출처는 #92 —
옛 `provider-switch-agent-model-check.md` 의 D2(지금은 [`../design/llm/model-name-resolution.md` §5.3](../design/llm/model-name-resolution.md#53-번들-explore-는-모델을-적지-않는다)),
§10 이 이 항목으로 올렸다. 번들의 `haiku` 를 고치는 것은 **그 작업의 범위 밖**으로 정해졌다. 새 등록부를 열지 않고
여기 두는 것은 L-16 이 적은 것과 같은 이유다 — 그 작업의 짝이 이 등록부에 있다: #92 의 경고와 주석은
`llm.provider` · `llm.model` · `agent.name` 위에 있고, 같은 작업이 남긴 L-18 ~ L-21 도 여기 있다.)*

**무엇을.** 번들로 들어 있는 `explore` 서브에이전트 셋이 설정된 provider 가 실제로 서비스하는 모델 이름을 싣게 한다.

**왜.** 관측 가능한 결과는 이렇다 — #92 의 주석과 가이드가 가리키는 짝인 `provider: anthropic` +
`agent.name: default-anthropic` 에서 메인 에이전트는 `claude-sonnet-4-5` 로 돌지만, `explore` 에 위임한 작업은
`model: haiku` 를 싣고 나간다. Anthropic Messages API 는 2026-09-10 에 그 이름에 HTTP 404 `not_found_error` 를
돌려주었다(`req_011CevaKnvSxstq6wuuWgXd8`). #92 의 기동 경고는 이것을 **일부러** 잡지 않는다 — `haiku` 는 어느
벤더의 계열에도 들지 않고, 그런 이름에 경고하면 그 별칭을 풀어 주는 게이트웨이 뒤에서 거짓 경보가 되기 때문이다
(설계 §3.1, R19).

**별칭을 푸는 것이 없다는 것은 이렇게 셌다 (규칙 여섯).** `SubagentLlmDefaults.resolveModel` 은 프론트매터 문자열을
그대로 모델 이름으로 쓴다(`:68-69`). 그 메서드의 프로덕션 호출자는 `SubagentLlmDefaults.resolveModel(` 와
`::resolveModel` 두 형태로 세어 둘이다 — `DefaultSubagentExecutor.java:768`, `DefaultSubagentBehaviorSupport.java:53`.
`grep -rn haiku modules/*/src/main/java` 가 찾는 것은 javadoc 과 주석(`TaskTool.java:70` · `:94`,
`SubagentMetadata.java:40`, `SubagentContentParser.java:25`, `LlmRerankSearchStrategy.java:80`,
`AgentModelProviderCheck.java:40`), 그리고 `claude-` 로 시작하는 전체 이름의 prefix(`claude-3-5-haiku` ·
`claude-3-haiku` · `claude-haiku-4` · `claude-haiku-4-5` — `InMemoryModelPriceTable` ·
`InMemoryModelContextWindowRegistry` · `InMemoryModelCapabilityRegistry`)뿐이다. 맨 `haiku` 를 모델 id 로 옮기는
코드는 없다. `src/main/resources` 에서는 아래 `explore.md` 셋만 걸린다. 설계 §2 의 같은 세기는 `claude-haiku-4` ·
`claude-haiku-4-5` 와 `LlmRerankSearchStrategy` 를 빠뜨렸다 — 결론은 같고, 그 문서 §10 에 정정이 있다.

**나머지 두 번들.** `default-openai` 와 `ops-agent` 의 `explore` 도 `haiku` 를 적는다. 두 번들의 메인 에이전트는
`gpt-5.1` 이므로 그 `haiku` 는 OpenAI 로 나가고, OpenAI 가 무엇을 답하는지는 재지 않았다.

**어디.** 2026-09-11, `a1236c8` 기준.

- `modules/aimon-cli/src/main/resources/agents/{default-anthropic,default-openai,ops-agent}/agents/explore.md:5`
- 해석 지점 `SubagentLlmDefaults.java:61-75`
- 이 항목을 닫을 때 같은 편집에서 고칠 주의 문구 — `default-config.yaml` 의 `agent.name` 주석과, CLI 가이드의
  [provider 를 바꿀 때](../getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다)
  절(ko + en)

**심각도 (규칙 셋).** 잰 것은 그 이름을 실은 요청 하나가 404 를 받는다는 것이다. 그 뒤 `Task` 도구가 메인 에이전트에게
무엇을 돌려주는지, 모델이 그다음 무엇을 하는지는 돌려 보지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 보이는 모양은 셋이고 어느 것도 고르지 않았다 — 번들마다 `model:` 에 벤더의
전체 이름을 적는 것(이 번들들에 대해 재지 않았다), `model:` 을 지워 서브에이전트가 메인 에이전트의 이름을 물려받게
하는 것(`:70-71`), 코어에서 provider 별로 별칭을 푸는 것. 셋째는 `Task` 도구의 `model` 설명(L-20)과 같은 결정에 닿는다.

**언제 다시 볼까.** 번들의 `explore.md` 나 `SubagentLlmDefaults` 를 다음에 건드릴 때. 지금 집어도 될 만큼 작다.

### 닫힘 (2026-09-11, #104)

**둘째 모양을 골랐다 — 세 `explore.md` 에서 `model:` 을 지웠다.** `default-anthropic` 의 `explore` 는 이제 메인
에이전트의 `claude-sonnet-4-5` 로, `default-openai` · `ops-agent` 의 것은 `gpt-5.1` 로 돈다. 프론트매터에는 키가 없는
이유를 적은 주석 한 줄을 남겼다 — 누가 별칭을 다시 적지 않게. 첫째 모양(번들마다 벤더의 전체 이름)은 기각했다: 그
이름을 이 번들들로 실어 보낸 요청이 없고, 따라가야 할 벤더 이름이 셋 늘며, 물려받으면 `agent.name` 이 번들 전체의
스위치 하나가 된다. 셋째 모양(코어의 provider 별 별칭 해석)은 capability 표가 일부러 싣지 않는 벤더 지식을 코어에
넣고 `haiku` 가 provider 마다 다른 것을 뜻하게 만들어 기각했다. 근거는
[설계](../design/llm/model-name-resolution.md#21-쓰인-그대로-보낸다--별칭은-풀지-않는다)(옛 `model-names-sent-and-shown.md` D-2) 이고, 이 결정과 L-20 의 결정은 같은 원칙 하나(D-1)에서 나온다.

**대가.** `explore` 는 "더 싸고 빠른 모델" 이라는 의도를 잃고 메인 모델로 돈다. 세 번들에서 그 요청은 이미 실패하고
있었으므로(Anthropic 의 404 는 쟀고 OpenAI 쪽은 재지 않았다) 동작하던 것이 느려지지는 않는다. 싼 `explore` 를 원하면
전체 이름을 적은 `.aimon/agents/explore.md` 를 둔다 — #92 의 기동 경고가 그 파일을 본다.

**근거는 맞았다 (규칙 둘).** 맨 `haiku` 를 모델 id 로 옮기는 코드가 없다는 세기는 착수하며 다시 확인했다. OpenAI 가
`haiku` 에 무엇을 답하는지는 여전히 재지 않았고, 이제 어떤 번들도 그 이름을 보내지 않으므로 잴 이유가 사라졌다.
`model: haiku` 를 적은 테스트 리소스 넷(`aimon-core` 의 서브에이전트 픽스처 셋, 스타터의 `hints-probe`)과 파서 테스트
둘은 그대로 둔다 — 파서의 입력이고 요청으로 나가지 않는다.

**지키는 것.** `BundledSubagentModelTest` 가 서브에이전트를 싣는 네 번들의 모든 서브에이전트를 `Task` 도구 경로와
같은 `SubagentLlmDefaults.resolveModel` 로 풀어, 메인 에이전트의 모델이거나 같은 벤더 계열인지 본다.
`default-anthropic` 의 `explore.md` 에 `model: haiku` 를 되돌리면 그 테스트가 빨개진다 — 되돌려 보고 확인했다(설계 §10).
`default-config.yaml` 의 `agent.name` 주석과 CLI 가이드의 provider 전환 절(ko + en)의 주의 문구는 같은 편집에서 고쳤다.

---

## L-18 — anthropic 에서 `llm.model` 없이 `memory` 를 켜면 기동이 설정 키를 말하지 않는 메시지로 실패한다

*(2026-09-11 등록. 출처는 #92 — [설계](../design/llm/model-name-resolution.md#33-memory--llmmodel-없으면-클라이언트-기본-모델을-알리고-쓴다)(옛 `provider-switch-agent-model-check.md`) 의 D9, §10 이 이 항목으로
올렸다. #92 는 이 실패를 `default-config.yaml` 의 `llm.model` 주석과 CLI 가이드에 **적었고 고치지 않았다**.)*

**무엇을.** `provider: anthropic` 에서 `memory` 를 켜고 `llm.model` 을 적지 않은 설정이, 고쳐야 할 키를 말하며
실패하게 하거나 기동하게 한다.

**왜.** 관측 가능한 결과는 이렇다 — 그 설정은 `Unexpected error: llmModelName cannot be null` 을 찍고 종료하며, 메시지
어디에도 `llm.model` 이 없다. openai 는 같은 누락을 `LlmClientFactory` 에서 키 이름을 대며 막는다
(`LlmClientFactory.java:322-329`). anthropic 분기는 모델이 있을 때만 옮기고(`:77-79`) 클라이언트는 `AnthropicConfig`
의 기본값으로 뜨므로 거기서는 아무것도 막히지 않는다. 그 뒤 `AgentSetupFactory` 가 dialectic 엔진을
`config.getLlmConfig().getModel()` 로 가드 없이 만들고(`AgentSetupFactory.java:527`), 엔진이 null 이름을 거절하며
(`LlmDialecticEngine.java:74`), 그 `NullPointerException` 은 `ConfigurationException` 이 아니므로 `AimonCli` 의
마지막 catch 가 `Unexpected error:` 로 찍는다(`AimonCli.java:130`). 같은 값을 받는 나머지 넷도 null 을 거절한다 —
`LlmDeriver.java:151`, `DefaultReconciler.java:102`, `RandomWalkDreamer.java:92`, `LlmJudgeSurprisalScorer.java:92`.

**어디.** 2026-09-11, #92 를 담은 트리 기준 — `AgentSetupFactory.java:527`(엔진), `:532-533`(deriver 와 reconciler 에
같은 값), `:661`(dreamer 에 같은 값), `AimonCli.java:130`, `LlmClientFactory.java:77-79` · `:322-329`, 그리고 위 다섯
생성자의 줄.

**심각도 (규칙 셋).** 돌려 봤다 — 2026-09-11 에 `provider: anthropic`, 가짜 키, `llm.model` 없음, `agent.name:
default-anthropic`, `memory`(in-memory 백엔드)로 CLI 를 띄우자 `Peer memory enabled (in-memory backend, non-durable)`
한 줄 뒤에 `Unexpected error: llmModelName cannot be null` 을 찍고 종료 코드 1 로 끝났다. 요청은 하나도 나가지 않았다 —
실패가 스택을 조립하기 전에 일어난다. 위 원인 사슬은 읽어서 얻은 것이고, 돌려서 확인한 것은 그 끝의 메시지다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 둘이 보인다 — anthropic 분기에서도 `memory` 가 켜져 있으면
`llm.model` 을 요구해 `ConfigurationException` 으로 키를 말하는 것, 또는 메모리 부품에 클라이언트의 기본 모델
(`getDefaultModelName()`)을 넘기는 것. 둘째는 배너가 이미 보여 주는 이름과 같아지지만, 사용자가 적지 않은 모델로
메모리 호출이 나간다는 결정이기도 하다.

**언제 다시 볼까.** CLI 의 메모리 배선이나 `LlmClientFactory` 의 anthropic 분기를 다음에 건드릴 때. 닫을 때
`default-config.yaml` 의 `llm.model` 주석과 가이드의 같은 문장을 같은 편집에서 고친다.

### 닫힘 (2026-09-11, #105)

**둘째 모양을 골랐고, 기동 줄 하나를 더했다.** `AgentSetupFactory.memoryModelName` 이 메모리 부품 전부에 넘길 이름
하나를 셸 · 큐 · 스택보다 먼저 한 번 정한다 — `llm.model` 이 비어 있지 않으면 그것, 아니면 클라이언트의
`getDefaultModelName()`, 둘 다 비면 `llm.model` 을 부르는 `ConfigurationException` 이다. 그 이름이 dialectic 엔진,
deriver 와 reconciler, dreamer 와 그 LLM 판정기로 간다. 기본 모델로 떨어질 때는 모델과 키를 대는 줄 하나를 터미널과
로그 파일에 남긴다 — 이 항목이 적은 "사용자가 적지 않은 모델로 메모리 호출이 나간다" 는 결정을 거절이 아니라 알림으로
치렀다. 첫째 모양(메모리가 켜지면 anthropic 에서도 `llm.model` 을 요구)은 기각했다: 팩토리가 anthropic 에서 선택으로
두는 키를 조건부 필수로 좁히는데, 이미 클라이언트 기본값으로 도는 소비자(위키 생성, `model.name` 없는 메인
에이전트)가 있어 메모리만 예외가 된다. 근거는 [설계](../design/llm/model-name-resolution.md#2-원칙--모델을-적지-않은-부품은-클라이언트-기본-모델로-돈다)(옛 `model-names-sent-and-shown.md` D-1) 이고, 같은
원칙이 L-20 의 서브에이전트 폴백을 정한다.

**근거는 맞았다 (규칙 둘 · 여섯).** 엔진 하나만 가드하면 실패가 옮겨 갈 뿐이라는 경고가 맞았다. 착수하며 `new` 와
`::new` 두 형태로 다시 세니 `src/main` 전체에서 다섯 타입(`LlmDialecticEngine` · `LlmDeriver` · `DefaultReconciler` ·
`RandomWalkDreamer` · `LlmJudgeSurprisalScorer`)이 생성되는 곳은 `AgentSetupFactory` 의 다섯 줄뿐이고 다섯 모두 같은
값을 받는다 — 그래서 이름을 정하는 자리를 하나로 두었다. 옮겨 간 실패의 모양도 하나 더 있었다: 엔진만 가드했다면
deriver 에서 기동이 멈췄을 것이고, dreamer 는 생성 실패를 스스로 삼켜 `dreamer disabled` 로 찍으므로 멈추지도 않고
조용히 꺼졌을 것이다. 아래 테스트가 `dreamer enabled` 를 단언하는 이유다.

**남은 것.** `ConfigurationException` 은 기본 모델이 없는 클라이언트에만 남는다. 배포되는 두 클라이언트는 언제나
기본값을 돌려주므로 CLI 에서는 `llm.model: ""` 처럼 `AnthropicConfig` 까지 빈 이름이 가는 경우다. 떨어지는 그 기본
모델이 아직 서비스되는지는 재지 않았고 L-24 로 올렸다.

**재현이 이제 기동한다.** 이슈의 설정에 reconciler 와 LLM 판정기 dreamer 를 더해 `create()` 를 끝까지 지나는
`AgentSetupFactoryCreateTest` 가 있고, 엔진에 넘기는 값을 옛 `config.getLlmConfig().getModel()` 로 되돌리면 빨개진다 —
되돌려 보고 확인했다. 실제 CLI 로 띄운 기록은 설계 §10 에 있다. `default-config.yaml` 의 `llm.model` 주석과 가이드의
같은 문장은 같은 편집에서 고쳤다.

---

## L-19 — REPL 배너의 `LLM Provider: <provider> (<model>)` 는 에이전트가 보내는 모델이 아니라 `llm.model` 을 찍는다

*(2026-09-11 등록. 출처는 #92 — [설계](../design/llm/model-name-resolution.md#51-배너의-모델은-메인-에이전트의-요청이-싣는-모델이다)(옛 `provider-switch-agent-model-check.md`) 의 D10, §10 이 이 항목으로
올렸다. #92 는 이 괄호가 무엇인지 주석과 가이드에 적었고 배너는 바꾸지 않았다.)*

**무엇을.** 시작 배너가 에이전트 요청이 실제로 싣는 모델을 보여 주거나, 괄호 안이 `llm.model` 이라는 것을 스스로
말하게 한다.

**왜.** 배너는 클라이언트의 `getDefaultModelName()` 을 찍고(`ReplSession.java:258-259`), 두 클라이언트 모두 그 자리에서
`config.getModel()` 을 돌려준다(`AnthropicLlmClient.java:762-764`, `OpenAILlmClient.java:589-591`). 관측 가능한 결과 —
#92 의 재현 설정(`provider: anthropic`, `model: claude-sonnet-4-5`, `agent.name: default`)으로 띄우면 배너가
`LLM Provider: Anthropic (claude-sonnet-4-5)` 를 찍는데(2026-09-11 에 가짜 키로 기동만 해서 봤다), 에이전트 요청은
`gpt-5.6-terra` 를 싣는다 — 그 이슈를 찾기 어렵게 만든 바로 그 모양이다. #92 이후 그 경우에는 배너 앞에 경고가 뜨지만, provider 는 맞고 이름만 다른 경우에는
아무것도 뜨지 않는다: 배포된 설정 그대로면 괄호는 `llm.model` 의 `gpt-5.1` 이고 요청은 `gpt-5.6-terra` 다. anthropic
에서 `llm.model` 을 생략하면 괄호는 `AnthropicConfig` 의 기본값 `claude-sonnet-4-20250514` 다.

> **정정** *(2026-09-11, #132)*: 위 **왜.** 의 마지막 문장 — anthropic 에서 `llm.model` 을 생략하면 괄호는
> `AnthropicConfig` 의 기본값 `claude-sonnet-4-20250514` 다 — 은 등록한 날에 참이었다. 그 뒤 두 가지가 바뀌었다. 괄호가
> 무엇을 찍는지는 #106 이 바꿨고(아래 닫힘), 그 기본값은 #116 이 `claude-sonnet-4-5` 로 바꿨다(L-24 의 닫힘 — 옛 이름은
> 2026-09-11 에 Messages API 가 404 로 답했다). 그래서 지금은 anthropic 에서 `llm.model` 을 생략하고 메인 에이전트
> 정의가 `model.name` 을 적지 않으면 괄호가 `claude-sonnet-4-5` 다(`2eddf3d` 의 `ReplSession.java:319-325`,
> `LlmClientFactory.java:77-79`, `AnthropicLlmClient.java:762-764`, `AnthropicConfig.java:42`). 등록 문장은 규칙 둘대로
> 고치지 않고 둔다.

**어디.** 2026-09-11 — `ReplSession.java:258-259`, `AnthropicLlmClient.java:762-764`, `OpenAILlmClient.java:589-591`.

**처방은 적용해 보지 않았다 (규칙 다섯).** 배너는 메인 에이전트의 `model.name` 을 바로 읽을 수 있다 — 괄호를 에이전트의
모델로 바꿀지, 둘을 함께 보여 줄지를 정해야 한다. 서브에이전트의 모델은 한 줄에 담기지 않는다.

**언제 다시 볼까.** `ReplSession.displayAgentInfo` 를 다음에 건드릴 때. 몇 줄짜리라 지금 집어도 된다. 닫을 때
`default-config.yaml` 의 `llm.model` 주석과 가이드의 배너 문장을 같은 편집에서 고친다.

### 닫힘 (2026-09-11, #106)

**괄호를 에이전트의 모델로 바꿨다.** `LLM Provider: <provider> (<model>)` 의 괄호는 이제 메인 에이전트 정의의
`model.name`, 그것이 없으면 클라이언트의 기본 모델이다 — 두 클라이언트가 요청마다 쓰는 `getName().orElse(...)` 와
같은 규칙이고, 그 결과가 비거나 공백이면 괄호를 뺀다. 괄호가 `llm.model` 이라고 적는 모양은 기각했다: 참이지만
사용자는 여전히 무엇이 도는지 볼 수 없고, #92 의 재현에서 배너가 `gpt-5.6-terra` 경고 바로 아래에
`claude-sonnet-4-5` 를 찍는 모양이 그대로 남는다. 둘을 함께 보여 주는 모양도 기각했다 — 배포된 설정에서 두 이름이
달라서 "어느 것이 도는가" 를 한 괄호 안에서 다시 묻게 한다. 근거는
[설계](../design/llm/model-name-resolution.md#51-배너의-모델은-메인-에이전트의-요청이-싣는-모델이다)(옛 `model-names-sent-and-shown.md` D-4).

**남은 것.** 서브에이전트가 따로 적은 모델(`default` 의 `explore` 가 적는 `gpt-5.1`)은 보여 주지 않는다 — 항목이
적은 대로 한 줄에 담기지 않는다. 가이드가 그 사실을 적는다. 어느 번들이 떴는지는 같은 배너의 `Agent bundle:` 줄이
말한다(L-21).

**근거는 맞았다 (규칙 둘).** 배포된 설정에서 괄호가 `gpt-5.1` 이고 요청이 `gpt-5.6-terra` 였다는 것은 읽어서 얻은
것이다. 새 괄호는 `ReplSessionBannerTest` 가 그 두 이름으로 못박고, 실제 기동에서 무엇을 찍는지는 설계 §10 에 있다.
`default-config.yaml` 의 `llm.model` 주석과 가이드의 배너 문장은 같은 편집에서 고쳤다.

---

## L-20 — 서브에이전트 모델의 코어 기본값 두 곳이 provider 를 모른다: `gpt-4` 리터럴과 `Task` 도구 설명의 모델 제안

*(2026-09-11 등록. 출처는 #92 — [설계](../design/llm/model-name-resolution.md#32-서브에이전트--override-서브에이전트-메인-에이전트-클라이언트-기본)(옛 `provider-switch-agent-model-check.md`) 의 D4 · D5, §10 이 한 항목으로
올렸다. 둘 다 `aimon-core` 이고 #92 의 파일 밖이다. L-17 의 셋째 처방 모양이 이 항목의 둘째와 같은 결정에 닿는다.)*

**무엇을.** 코어가 서브에이전트의 모델 이름을 스스로 지을 때, 설정된 provider 가 서비스하지 않는 이름을 짓지 않게 한다.

**왜.** 두 자리이고, 관측 가능한 결과가 각각 있다.

- **`model.name` 이 없는 정의 아래에서 `model` 이 없는 서브에이전트는 리터럴 `gpt-4` 를 보낸다.**
  `SubagentLlmDefaults.resolveModel` 은 서브에이전트 모델이 없으면 기본 모델의 이름을, 그것도 없으면
  `DEFAULT_MODEL_NAME = "gpt-4"` 를 쓴다(`:21`, `:70-71`). `Task` 도구가 넘기는 기본 모델은 메인 에이전트의
  `LlmModel` 이다 — 도구가 `agent.getMetadata().getModel()` 로 만들어지고(`OrcaSubagentToolProvider.java:91`) 그 값을
  실행 환경에 넘긴다(`TaskTool.java:546`). 메인 에이전트의 이름이 비면 클라이언트가 `llm.model` 로 채우지만
  (`orElse(config.getModel())`), 서브에이전트 경로는 그 값을 보지 못하고 `gpt-4` 를 싣는다. `provider: anthropic` 이면
  그 요청은 Anthropic 에 `gpt-4` 로 나간다.
- **`Task` 도구의 `model` 파라미터 설명이 provider 와 무관하게 `sonnet, gpt-4.1, gpt-4.1-nano` 를 권하고
  `Prefer gpt-4.1-nano for simple tasks` 라고 적는다**(`TaskTool.java:345`). 모델이 그 설명을 따르면 그 이름이
  override 로 최우선이 된다(`SubagentLlmDefaults.java:66-67`). anthropic 에서는 `gpt-4.1-nano` 가, openai 에서는 맨
  별칭 `sonnet` 이 그대로 나간다.

**셈과 범위 (규칙 여섯).** `resolveModel` 의 프로덕션 호출자는 두 형태로 세어 둘이다(L-17). 첫째는 **CLI 번들에서는
닿지 않는다** — 번들 정의는 전부 `model.name` 을 적는다. 닿는 것은 `model` 블록 없는 사용자 정의나 코드로 만든
`DefaultAgent` 다. 기본 모델이 들어오는 입구는 `Task` 도구 경로만 따라갔다 — 워크플로와 스킬 포크가
`.defaultModel(` 로 넘기는 값의 출처는 세지 않았다.

**심각도 (규칙 셋).** 읽어서 얻은 결론이고 돌려 보지는 않았다. 둘째는 모델이 설명을 따를 때만 일어나며, 얼마나 자주
따르는지는 재지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 첫째는 서브에이전트 경로가 클라이언트의 기본 모델
(`LlmClient.getDefaultModelName()`)을 마지막 폴백으로 쓰는 모양이 보인다. 둘째는 설명에서 모델 이름을 빼거나
provider 별로 채우는 것이고, 후자는 L-17 의 셋째 모양(코어에서 provider 별 별칭 해석)과 같은 결정이다.

**언제 다시 볼까.** `SubagentLlmDefaults` 나 `TaskTool` 의 입력 스키마를 다음에 건드릴 때, 또는 L-17 을 착수할 때.

### 닫힘 (2026-09-11, #104)

**리터럴을 지웠고, 설명에서 모델 이름을 뺐다.** `SubagentLlmDefaults.resolveModel` 은 override · 서브에이전트의
`model` · 부모 모델의 이름이 모두 없으면 이제 **이름 없는** `LlmModel` 을 돌려주고, 클라이언트가 요청 시점에 자기
기본 모델을 채운다 — 이름 없는 메인 에이전트가 이미 도는 방식(두 클라이언트의 `getName().orElse(config.getModel())`)
그대로다. 항목이 본 모양(클라이언트의 `getDefaultModelName()` 을 마지막 폴백으로 넘기는 것)은 같은 이름에 먼 길로
닿는 것이라 기각했다: 호출 지점 둘을 바꿔야 하고 그중 `DefaultSubagentExecutor` 는 클라이언트가 아니라
`LlmCallGateway` 를 쥐며, 이름을 해석 시점에 굳혀 데코레이터나 라우터가 요청마다 정할 자리를 없앤다. 그래서 두 호출
지점은 바뀌지 않았다. `Task` 도구의 `model` 설명은 이제 모델을 권하지 않고 계약을 적는다 — 값은 쓰인 그대로 설정된
provider 로 가고, 별칭은 풀리지 않으며, 서브에이전트의 모델을 이긴다. provider 별로 이름을 채우는 모양은 `TaskTool` 이
provider 를 모르므로 코어에 벤더 지식이나 새 생성자 인자를 들여야 해서 기각했다 — L-17 의 셋째 모양과 같은 결정이고
같은 이유로 졌다. 근거는 [설계](../design/llm/model-name-resolution.md#54-task-도구의-model-설명은-모델을-권하지-않고-계약을-말한다)(옛 `model-names-sent-and-shown.md` D-1 · D-3).

**보이는 변화.** 해석된 모델의 이름을 읽는 코드(`SubagentBehaviorSupport.resolvedModel()` 을 쓰는 동작 구현 등)는 이제
빈 이름을 볼 수 있다. 트리의 main 소스에는 그 이름을 `get()` 으로 꺼내는 곳이 없다. CHANGELOG 에 적었다.

**규칙 여섯 — 항목이 세지 않았던 입구를 셌다.** 항목은 기본 모델이 들어오는 입구를 `Task` 도구 경로만 따라갔다. 이번에
`.defaultModel(` 와 `::defaultModel` 두 형태로 main 소스 전체를 셌다(2026-09-11, 이 변경을 담은 트리). 코드는 10곳,
javadoc 예시가 2곳(`SubagentExecutionEnvironment` 와 `DefaultSubagentExecutor` 의 클래스 주석), 메서드 참조는 0곳이다.

| 입구 | 넘기는 값 |
|---|---|
| `OrcaAgentRuntimeFactory.java:962`, CLI 의 `GraalJsWorkflowToolProvider.java:85` | 메인 에이전트의 `agent.getMetadata().getModel()` |
| `TaskTool.java:553`, `WorkflowTool.java:449`, `SubagentBackedSkillForkExecutor.java:113` | 생성자로 받은 값 — 셋 다 `agent.getMetadata().getModel()` 로 만들어진다(`OrcaSubagentToolProvider.java:91` · `:113`, `OrcaSkillForkExecutorResolver.java:67`) |
| `GraalJsWorkflowTool.java:255`, `DefaultSubagentExecutionManager.java:604`, `SubagentExecutionEnvironment.java:390` | 이미 받은 값을 옮긴다 |
| `DefaultCommandExecutionManager.java:213`, `SkillBackedCommandExecutor.java:70` | 명령 실행의 `model` 인자 — 스킬 명령 경로이고 `resolveModel` 에 닿지 않는다. 그 인자의 출처는 더 따라가지 않았다 |

> **정정** *(2026-09-11, #118)*: 이 표는 처음에 `TaskTool.java:555` 로 적었다. `c561e17` 에서 `.defaultModel(`
> 호출은 `:553` 이고, 표와 괄호 안의 나머지 인용 열두 개는 같은 날 다시 읽어 맞았다.

어느 입구도 이름을 지어내지 않으므로, 리터럴이 사라지면 서브에이전트 해석에 닿는 모든 입구에서 "이름이 없으면
클라이언트의 기본 모델" 이 된다.

**남은 것 — 항목으로 올리지 않는다.** 모델은 여전히 서비스되지 않는 이름을 override 로 넘길 수 있고, 그 이름은 쓰인
그대로 나간다. 호출마다 모델이 고르는 인자라 기동 검사가 볼 수 없다. 설명이 틀린 이름을 권하지 않게 된 뒤로는 처방할
것도 다시 볼 계기도 없어서 새 항목으로 두지 않는다. 설명은 "주어진 모델 id 가 없으면 비워 두라" 고도 적는다 — 다른
에이전트 도구의 `sonnet` · `opus` · `haiku` 선택지를 아는 모델이 맨 별칭을 넘기는 버릇을 겨눈 한 줄이다.

---

## L-21 — 번들 셋이 메타데이터 이름 `default-agent` 를 함께 써서, 프롬프트와 런타임 id 로는 어느 번들이 떴는지 알 수 없다

*(2026-09-11 등록. 출처는 #92 — [설계](../design/llm/model-name-resolution.md#52-번들-줄과-프롬프트--번들-이름은-줄이-말하고-정의의-이름은-바꾸지-않는다)(옛 `provider-switch-agent-model-check.md`) 의 D1, §10 이 이 항목으로
올렸다. #92 의 주석 · 가이드 · 경고는 설정된 `agent.name` 을 쓰므로 이것을 바꾸지 않고도 참이다.)*

**무엇을.** `default` · `default-openai` · `default-anthropic` 번들이 서로 구별되는 이름으로 사용자에게 보이게 한다 —
`agent.md` 의 `name` 을 가르든, CLI 가 설정된 `agent.name` 을 보여 주든.

**왜.** 셋 다 `name: default-agent` 를 적는다(`agents/{default,default-openai,default-anthropic}/agent.md:3`). 관측
가능한 결과 — REPL 프롬프트는 `agentSetup.getAgent().getName()` 으로 만들어지므로(`AimonCli.java:107`) 셋 중 무엇을
띄워도 `default-agent> ` 이고, `AgentRuntimeId.from(Agent)` 가 그 이름에서 파생되므로(`AgentRuntimeId.java:114-117`)
런타임 id 도 셋 다 `agent:default-agent` 다. #92 가 적은 전환(`default` → `default-anthropic`)을 한 사용자는 프롬프트로는
전환이 먹었는지 볼 수 없다.

**심각도 (규칙 셋).** CLI 프로세스 하나는 번들 하나만 띄우므로 id 충돌은 없다. 이 id 로 키잉되어 CLI 재시작을 넘는
상태가 있는지는 세지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 이름을 바꾸면 프롬프트와 런타임 id 가 함께 바뀐다. 그 id 를 참조하는 값
(예: 스케줄 태스크의 `boundRuntimeId`)이 CLI 에서 저장되는지는 확인하지 않았다.

**언제 다시 볼까.** 번들의 `agent.md` 를 다음에 건드릴 때 — L-17 을 착수하면 같은 디렉토리다.

### 닫힘 (2026-09-11, #106)

**이름은 그대로 두고, CLI 가 설정된 `agent.name` 을 보여 준다.** 시작 배너의 `Working Directory:` 와 `LLM Provider:`
사이에 `Agent bundle: <agent.name>` 줄이 생겼고, 정의의 이름과 다르면 괄호로 붙인다
(`Agent bundle: default-anthropic (agent name: default-agent)`) — 그 괄호가 프롬프트와 런타임 id 가 어디서 오는지
설명한다. 세 `agent.md` 의 `name` 을 가르는 모양은 기각했다: 프롬프트와 함께 `AgentRuntimeId`(`agent:default-agent`)가
바뀌는 영속 정체성 변경인데, 아래 세기를 끝내지 못했고, 작은 모양으로 이 항목이 요구한 것("구별되게 보이게")이 채워진다.
기본 프롬프트를 `agent.name` 으로 만드는 모양도 기각했다 — 매 입력마다 보이는 줄을 바꾸고, 배포된 프롬프트가
`default> ` 가 되어 오히려 덜 말하며, 시스템의 나머지가 보고하는 런타임 id 와 어긋난다. 근거는
[설계](../design/llm/model-name-resolution.md#52-번들-줄과-프롬프트--번들-이름은-줄이-말하고-정의의-이름은-바꾸지-않는다)(옛 `model-names-sent-and-shown.md` D-5).

**항목이 세지 않았던 것을 셌다 (규칙 넷 — 결정의 전제).** 이름을 바꾸면 CLI 재시작을 넘어 무엇이 어긋나는가. 셀 수
있는 것은 전부 재시작을 넘지 않았고, 끝까지 세지 못한 것이 둘 남았다.

| 런타임 id 로 키잉되는 것 | CLI 에서 | 재시작을 넘는가 |
|---|---|---|
| 에이전트 전역 승인 | `AimonStackBuilder` 가 `InMemoryAgentApprovalStore` 로 채운다 | 넘지 않는다 |
| 스케줄 태스크의 `boundRuntimeId` | `SchedulingSpec.enabled()` — javadoc 이 기본 스케줄러는 재시작을 넘지 않는다고 적는다 | 넘지 않는다 |
| 세션 레코드 | `InMemorySessionRecordStore` | 넘지 않는다 |
| dreamer 잡 | Quartz `RAMJobStore` | 넘지 않는다 |
| 메모리 | workspace 와 peer 로 키잉된다 — 런타임 id 가 아니다 | 해당 없음 |
| 위키 저장소 | 런타임 id 로 VFS 를 풀어 `.aimon/wiki` 아래에 둔다 | **세지 않았다** — 디스크 배치에 id 가 들어가는지 확인하지 않았다 |
| 훅 스크립트의 `AIMON_AGENT_RUNTIME_ID`, 스킬 본문의 `${AIMON_AGENT_RUNTIME_ID}` | 사용자가 쓴 스크립트와 스킬 | **셀 수 없다** — 트리 밖이다 |

**남은 것.** 이름을 가르는 결정이 다시 올라오면 위 표의 마지막 두 줄부터 센다. 이 닫힘으로 프롬프트와 런타임 id 는
여전히 셋 다 `default-agent` 이며, 사용자가 프롬프트를 원하면 `cli.prompt` 가 있다.

---

## L-22 — 에이전트 실행기 밖의 도구 루프 둘은 stop reason 을 읽지 않아서, `max_tokens` 에서 잘린 도구 호출이 그대로 실행된다

*(2026-09-11 등록. 출처는 #108 · #100 —
[`../design/agent-execution/max-tokens-truncation-reporting.md`](../design/agent-execution/max-tokens-truncation-reporting.md)
§8 을 §11.3 이 이 항목으로 올렸다. 그 작업은 두 에이전트 실행기만 고쳤고, 이 루프들은 **그 작업의 범위 밖**이었다. 여기
두는 것은 L-16 의 짝이기 때문이다.)*

**무엇을.** `LlmSkillExecutor` 의 도구 루프가 `max_tokens` 에서 잘린 응답을 두 에이전트 실행기처럼 다루게 한다 — 잘린
응답의 도구 호출은 실행하지 않고 거절하며, 잘린 최종 답은 잘렸다고 표시한다.

**왜.** 관측 가능한 결과는 L-16 이 닫히기 전의 모양 그대로다. 스킬을 `/my-skill` 로 부르면 `LlmSkillExecutor` 가 자기
ReAct 루프(`while (currentResponse.hasToolUses())`)를 돌리는데, 그 루프는 `LlmResponse.getStopReason()` 을 읽지 않는다.
응답이 도구 호출 안에서 잘리면 그 호출이 받은 인자 그대로 실행되고, 도구 호출 없이 잘린 답은 성공 결과로 돌아간다. 한
가지가 다르다 — 이 루프는 네 인자 blocking `sendMessage` 를 부르므로 클라이언트 자신의 `… truncated due to max_tokens
limit` WARN 이 **뜬다.** 다만 그 줄은 어느 스킬이었는지, 어느 도구 호출이 실행됐는지 말하지 않는다.

**도달 가능성은 이렇게 셌다 (규칙 여섯).** main 소스에서 `new LlmSkillExecutor(` 와 `LlmSkillExecutor::new` 를 세면
둘이고, 둘 다 `DefaultCommandExecutionManager` 안이다. 그 매니저는
`OrcaAgentExecutorFactory.createDefaultCommandExecutionManager` 가 만든다 — 스킬 기반 슬래시 명령을 쓰는 배포는 이 루프에
닿는다. **`ReActLlmDeriver.derive` 에도 같은 루프가 있지만** 같은 방법으로 세면 생성 지점이 **0** 이다. 그 이름을 부르는
것은 자기 테스트와 아키텍처 테스트뿐이다. 그래서 그쪽은 근거가 아니라 관측으로 적는다 — 누군가 그것을 조립하는 날 같은
결함이 함께 온다.

**어디** *(2026-09-11)* — `LlmSkillExecutor` 의 `while (currentResponse.hasToolUses())` 루프, `ReActLlmDeriver.derive`
의 `for` 루프, 두 실행기가 읽는 `at.aimon.core.agent.budget.TruncatedResponses`.

**심각도 (규칙 셋).** 읽어서 얻은 결론이고 돌려 보지는 않았다. 잘린 스트림의 모양은 L-16 의 닫힘 블록이 한 번 쟀는데,
스킬 루프는 blocking 경로라 응답을 `AnthropicLlmClient.convertResponse` 가 만든다. 그 경로에서 잘린 `tool_use` 블록의
입력이 어떤 모양으로 오는지는 재지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 보인다 — `TruncatedResponses.isTruncated` 로 읽고 `refusal` 로 답하는
것. 정할 것이 셋 남는다. 스킬 루프에는 정체 가드가 없으므로 거절이 스킬의 `maxIterations` 까지 반복될 수 있다(L-23 과 같은
질문이다). 잘린 최종 답을 스킬 결과에서 무엇으로 표시할지. `ReActLlmDeriver` 를 함께 고칠지, 조립되지 않는 동안 둘지.

**언제 다시 볼까.** `LlmSkillExecutor` 의 루프를 다음에 건드릴 때, 또는 스킬 실행에서 이유 없는 도구 오류가 보고될 때.
`ReActLlmDeriver` 는 main 소스에서 처음 생성될 때.

### 닫힘 (2026-09-11, #115 · #117)

**이제 스킬 루프도 `max_tokens` 에서 잘린 응답에 두 에이전트 실행기와 같은 답을 준다.** `LlmSkillExecutor` 는 응답마다
`TruncatedResponses.isTruncated` 를 한 번 읽는다. 도구 호출이 있는 잘린 응답은 바운드 디스패처로도 폴백으로도 **디스패치하지
않고** 호출마다 같은 거절 결과로 답한 뒤 루프를 잇는다. WARN 이 스킬 이름, 1부터 센 iteration, 도구 이름을 적는다 — 인자는 적지
않는다. 도구 호출이 없는 잘린 최종 답은 성공으로 돌아가되 텍스트 끝에 `[System: response truncated at max_tokens]` 가 붙고, WARN
이 스킬 이름을 적는다. 설계와 기각한 대안은
[`../design/agent-execution/skill-loop-truncation-and-fork-stall.md`](../design/agent-execution/skill-loop-truncation-and-fork-stall.md)
의 D1 · D2 · D5 에 있다.

**정할 것 셋을 이렇게 정했다.**

- **정체 가드 — 스킬 루프도 턴의 가드로 멈춘다(D5).** 새 `at.aimon.core.agent.budget.StalledIterationGuard` 가 턴 · 포크 · 스킬
  루프의 한 정의다. 결과가 전부 오류인 iteration 이 연속 세 번이면 스킬은 턴과 같은 중단 메시지로 실패한다. 거절만 넣고 가드를
  두지 않았다면 이 수정이 L-23 의 반복을 스킬 루프에 새로 만들었을 것이다. 가드는 잘림과 무관한 오류 루프도 끝낸다 — 도구 오류,
  바운드 디스패처의 허용 목록 거절, PermissionRequest · PreTool 차단, 사용자가 부작용 승인을 세 iteration 연속 거절한 경우.
  CHANGELOG 가 그 관측 가능한 변화를 이름으로 적는다.
- **잘린 답이 스킬 결과에 보이는 자리 — `SkillExecutionResult.getResponse()` 의 끝이다(D1).** 스킬 결과에는 완료 사유가 없고,
  그것을 읽는 유일한 소비자 `SkillBackedCommandExecutor` 는 네 필드를 `CommandExecutionResult` 로 옮길 뿐이라 타입 신호를 더해도
  읽는 곳이 없다. 포크 모드 스킬은 이미 잘린 포크를 "성공 + 마커 텍스트" 로 전하고 있었다. 그래서 잘린 답을 돌려준 슬래시 스킬을
  실행한 턴은 여전히 `COMPLETED` 로 끝나며, 그것을 바꿀지는 L-26 으로 올렸다.
- **`ReActLlmDeriver` 도 함께 고쳤다(D2).** 잘린 응답의 도구 호출은 `invokeTool` 없이 거절하고, WARN 이 iteration · observer 키 ·
  도구 이름을 적으며, 루프는 여섯 iteration 과 토큰 예산 안에서 잇는다. 도구 호출이 없는 잘린 응답은 전처럼 루프를 끝낸다 —
  `derive` 는 텍스트가 아니라 관찰을 돌려주므로 표시할 답이 없다. 이 항목은 그 deriver 를 다시 볼 때를 "main 소스에서 처음
  생성될 때" 로 적었는데, **그 트리거는 이 자리를 지나가지 않는다**(규칙 일곱). 누군가 그것을 조립하는 날의 변경은 CLI 의 메모리
  배선이나 스타터 같은 다른 파일이고 이 클래스도 이 항목도 건드리지 않으므로, 결함이 소리 없이 함께 배포된다. **그리고 규칙
  여섯대로 적는다 — 이 수정은 어느 배포도 생성하지 않는 클래스에 한 것이다.** main 소스 전체에서 `new ReActLlmDeriver(` ·
  `ReActLlmDeriver::new` · 맨 이름을 세면 자기 파일 밖의 언급은 여전히 0이다.

**처방은 실패하는 테스트로 먼저 확인했다 (규칙 다섯).** 가드를 먼저 넣고 어느 루프에도 배선하지 않은 채 설계 §7 의 테스트를 옛
루프에 대고 돌렸다. 열 클래스 126건 중 15건이 적힌 이유로 실패했고, 이 항목의 것은 여덟이다 — 폴백 경로에서 잘린 호출이 실행됨,
바운드 디스패처가 잘린 호출을 넘겨받음, 잘린 최종 답에 마커가 없음, 잘린 도구 응답 셋과 실패하는 도구 셋이 스킬을 끝내지 못함,
추론 토큰 절을 붙일 WARN 이 아예 없음, 슬래시 스킬 E2E 에서 잘린 호출이 PermissionRequest · PreTool · PostTool 훅과 허용 목록
검사와 도구에 모두 닿음, deriver 가 잘린 호출로 관찰을 저장함. 수정 뒤 같은 열 클래스는 126건 모두 초록이다.

**심각도 (규칙 셋).** 등록 시점에 재지 않았던 blocking 경로의 모양을 2026-09-11 에 요청 하나로 쟀다 — Anthropic Messages API,
`stream: false`, `claude-haiku-4-5`, 두 인자가 모두 필수인 도구 `write_file(path, content)` 를 `tool_choice` 로 강제,
`max_tokens: 60`, HTTP 200(`req_011Cew8JyrxugAE6PL7atguf`), `stop_reason: max_tokens`. `content` 는 `tool_use` 블록 하나였고 그
입력은 `{"path": "/tmp/sea.txt"}` 였다 — 비지도 않고 파싱에 실패하지도 않는, **끝난 인자만 담고 필수 인자 `content` 가 빠진
온전한 객체**다. `AnthropicLlmClient.convertResponse` 는 그 입력을 그대로 `ToolUse` 의 인자 맵으로 옮긴다(코드에서 읽었고 이
응답으로 돌리지는 않았다). 즉 수정 전의 스킬 루프는 경로만 있고 내용이 없는 쓰기를 디스패치했을 것이고 파싱 실패 WARN 도 뜨지
않았을 것이다 — 남는 신호는 이 항목이 적은 클라이언트의 WARN 하나였다. 요청 하나의 관측이지 보장이 아니며, 위 결정은 그것에
기대지 않는다. 그리고 이 항목이 적은 것보다 무거웠던 자리가 하나 있다 — 스킬 루프는 **끊을 수 없다.** `LlmSkillExecutor` 는 취소
신호를 읽지 않고 슬래시 명령은 인터럽트되지 않으므로, 이 루프의 반복을 끝낼 것은 `max-iterations` 와 이제 정체 가드뿐이다.

**남은 것.** 스킬 루프에서 거절된 호출 하나는 **어느 터미널에도 보이지 않는다.** 턴의 거절된 호출은 `ToolUseStarted` ·
`ToolResultReady` 를 내고 REPL 이 그것을 찍지만, 명령 경로의 디스패처는 실행한 호출에도 수명 주기 이벤트를 내지 않고, REPL 의
도구 호출 줄은 거절된 호출이 닿지 않는 PreTool 훅에서 온다. 그래서 거절 하나는 WARN 에만 보이고, 거절이 이어지면 중단 메시지가
보인다. 명령 경로에 스킬 호출의 이벤트 채널이 없는 것이지 잘림의 틈이 아니므로 항목으로 올리지 않았다.

**어디** *(2026-09-11)* — `LlmSkillExecutor` 의 `refuseTruncatedToolUses` · `stalledFailure` 와 루프 뒤의 잘린 최종 답 분기,
`ReActLlmDeriver.refuseTruncatedToolUses`, `StalledIterationGuard`, 테스트 `LlmSkillExecutorTruncationTest` ·
`SlashSkillToolDispatchE2EIntegrationTest.slashSkillCutToolCall_ReachesNoHookNoAllowListCheckAndNoTool` ·
`ReActLlmDeriverTest.aCutToolCallIsRefusedNotRun`.

---

## L-23 — 서브에이전트 포크에는 정체 가드가 없어서, 매 응답이 `max_tokens` 에서 잘리는 포크는 기본 1000 iteration 까지 거절을 반복한다

*(2026-09-11 등록. 출처는 #100 — [설계](../design/agent-execution/max-tokens-truncation-reporting.md) §8 이 PR 본문으로만
보냈던 것을 설계 리뷰가 막지 않음으로 다시 짚었고, §11.3 이 이 항목으로 올렸다. 가드가 없는 것 자체는 그 작업보다 오래되었고
`max_tokens` 와 무관하다. 여기 두는 것은 그 결과가 L-16 을 닫은 거절과 만나는 자리이기 때문이다.)*

**무엇을.** 포크가 진척 없는 iteration 을 끝없이 반복하지 않게 한다 — 턴의 정체 가드를 포크에도 두거나, 잘린 응답이
이어질 때 포크를 끝낼 다른 경계를 둔다.

**왜.** 관측 가능한 결과는 이렇다 — thinking 예산이 출력 허용량을 거의 다 쓰는 서브에이전트는 매 응답이 도구 호출 안에서
잘릴 수 있다. #108 이후 두 실행기 모두 그런 호출을 거절한다. 턴은 연속 세 번이면 정체 가드가 `ERROR` 로 끝내지만
(`OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS`), **`DefaultSubagentExecutor.runReActLoop` 에는 그 가드가 없다.**
포크를 멈추는 것은 `maxIterations`, 예산, 취소, 오류뿐이다. `maxIterations` 의 기본값은 `SubagentMetadata` 의 1000 이다.
예산은 요청이 주는데, main 소스의 `SubagentExecutionRequest.builder()` 는 `DefaultSubagentExecutionManager` 한 곳뿐이고 거기서
`.budget(` 을 부르지 않으므로 `ExecutionBudget.unlimited()` 가 된다. 그러니 그런 포크는 `max_tokens` 크기의 요청을 최대
1000번 보내고 매번 WARN 을 남긴다. 수정 전에는 같은 포크가 받은 인자 그대로의 호출을 1000번까지 실행했을 것이므로
**회귀가 아니다** — 드러났을 뿐이다.

**어디** *(2026-09-11)* — `DefaultSubagentExecutor.runReActLoop` 의 `while (iterationCount < lc.maxIterations())`,
`SubagentMetadata` 의 `DEFAULT_MAX_ITERATIONS`, `DefaultSubagentExecutionManager` 가 `SubagentExecutionRequest` 를 만드는 자리.

**심각도 (규칙 셋).** 읽어서 얻은 결론이고 돌려 보지는 않았다. 매 응답이 잘리는 조건이 실제로 얼마나 이어지는지 — 모델이
거절 문구를 읽고 출력을 줄이는지 — 는 재지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 둘이 보인다 — 턴의 가드(결과가 전부 오류인 iteration 을 연속으로 세기)를
포크에 옮기는 것, 또는 잘린 응답만 따로 세는 것. 앞의 것은 잘림과 무관한 오류 루프까지 끝내므로 포크의 동작을 더 넓게
바꾸고, 그때 끝난 포크의 `CompletionReason` 을 무엇으로 할지(턴은 `ERROR`)도 정해야 한다 — 워크플로 판정과 태스크 기록이 그
값을 읽는다.

**언제 다시 볼까.** `DefaultSubagentExecutor` 의 루프를 다음에 건드릴 때, 또는 포크가 거절 WARN 을 반복한다는 보고가 있을 때.

### 닫힘 (2026-09-11, #115 · #117)

**이제 포크도 턴의 정체 가드로 멈춘다.** `DefaultSubagentExecutor.runReActLoop` 는 iteration 끝의 취소 검사 **뒤에** 그
iteration 을 새 `at.aimon.core.agent.budget.StalledIterationGuard` 에 기록하고, 결과가 전부 오류인 iteration 이 연속 세 번이면
턴과 같은 중단 메시지와 `CompletionReason.ERROR` 로 끝난다 — OnStop 훅은 `success=false`, 진행 스트림은 `[ended: …]`. 매 응답이
잘리는 포크는 이제 `max_tokens` 크기의 요청을 세 번 보내고 끝난다. 가드는 턴 · 포크 · 스킬 루프가 함께 쓰는 한 정의다. 설계와
기각한 대안은
[`../design/agent-execution/skill-loop-truncation-and-fork-stall.md`](../design/agent-execution/skill-loop-truncation-and-fork-stall.md)
의 D3 · D4 에 있다.

**처방 둘 중 앞의 것을 골랐다 (규칙 다섯).** 턴의 가드를 옮겼고 잘린 응답만 따로 세지 않았다. 따로 세면 턴 옆에 "진척 없음" 의
두 번째 정의가 생긴다. 잘린 응답과 실패하는 재시도가 번갈아 오는 포크는 잘리지 않은 iteration 마다 그 카운터가 리셋되어 멈추지
않는다. 그리고 잘림과 무관한 포크의 오류 루프 — 예산 없이 최대 1000 iteration, 대개 아무도 보지 않는 백그라운드 태스크에서 — 가
그대로 남는다. 턴의 가드는 바로 그것을 위해 만들어졌다.

**앞의 것은 이 항목이 경고한 대로 포크의 동작을 더 넓게 바꾼다.** 결과가 전부 오류인 iteration 이 연속 세 번이면 원인과 무관하게
포크가 끝난다 — 도구 오류, 모르는 도구 이름, 스키마 `ENFORCE` 거절, PermissionRequest · PreTool 차단, 그리고 포크에게는 물을 채널이
없어서 부작용 승인 게이트가 거절한 경우. 전에는 `maxIterations` 까지 이어갔다. 성공한 호출 하나가 연속을 리셋한다. CHANGELOG 가 이
변화를 이름으로 적는다.

**끝난 포크의 `CompletionReason` 은 `ERROR` 다 — 새 값은 만들지 않았다.** 그 값을 읽는 곳을 전부 확인했다:
`SubagentExecutionResult`(`getStatus()` 는 `FAILURE`), `TaskResult` 와 `JsonTaskResultCodec`(모든 노드 버전이 `"ERROR"` 를 읽는다),
`AgentStepResult.isComplete()` 와 워크플로 단계 캐시, `StepOutcome` 과 그 코덱(`COMPLETED` 결과만 인코딩된다),
`WorkflowPatterns.loopUntilDry` · `completenessCritic`, GraalJS `AgentResultView`, `SubagentBackedSkillForkExecutor`, 백그라운드
태스크 완료, `Task` 도구. 정체를 다른 오류와 다르게 가르는 독자는 없다. 둘을 구분해야 하는 유일한 독자는 요약을 읽는 부모
모델이고, 그 몫은 텍스트가 맡는다 — 연속된 정체가 전부 잘린 응답의 거절이었으면 턴 · 포크 · 스킬 루프 모두에서 중단 메시지 끝에
`— each of those responses was cut off at max_tokens, and its tool calls were refused` 가 붙는다. 새 상수 `STALLED` 는 아무도 가르지
않는 공개 · 영속 이름이 되고, 롤링 업그레이드 중 옛 노드는 그것을 `ERROR` 로 읽는다.

**처방은 실패하는 테스트로 먼저 확인했다.** 가드를 넣되 어느 루프에도 배선하지 않은 채 옛 포크에 대고 돌린 두 테스트 — 잘린 도구
응답 셋, 실패하는 도구 셋 — 가 둘 다 `COMPLETED` 로 실패했다. 문턱 전에 회복하는 포크, 셋째 정체 iteration 에 떨어진 부모
취소(`INTERRUPTED`), 문턱보다 낮은 `maxIterations`(`MAX_ITERATIONS`)는 수정 전후 모두 초록이다.

**심각도 (규칙 셋).** 재지 않은 것이 그대로 남는다 — 모델이 거절 문구를 읽고 출력을 줄이는지, 곧 가드가 실제로 얼마나 자주
발화하는지는 여러 턴에 걸친 라이브 실행 없이는 잴 수 없다. 위 결정은 그것에 기대지 않는다.

**남은 것.** 옛 `thinking-reporting-and-dialect-records.md`
§16.8 이 *"a fork has no guard and repeats until its `maxIterations` (L-23)"* 라고 적고, 같은 문서의 두 자리가 L-22 · L-23 을
열린 항목으로 적는다. 그 문서는 이번 작업의 파일이 아니어서 고치지 않았다 — 규칙 일곱이 말하는, 항목을 언급하는 문장이다.

**어디** *(2026-09-11)* — `StalledIterationGuard`, `DefaultSubagentExecutor.runReActLoop` 의 iteration 끝과
`createStalledResult`, `OrcaAgentExecutor.handleStalledIteration`, 테스트 `StalledIterationGuardTest` ·
`DefaultSubagentExecutorStalledIterationTest` · `DefaultSubagentExecutorTruncationTest.threeCutToolResponsesInARowEndTheForkAsError`.

---

## L-24 — anthropic 에서 `llm.model` 을 적지 않은 설정이 기대는 `AnthropicConfig` 의 기본 모델이 아직 서비스되는지 잰 적이 없다

*(2026-09-11 등록. 출처는 #104 ~ #107 — [설계](../design/llm/model-name-resolution.md#4-클라이언트-기본-모델--openai-는-없고-anthropic-은-측정된-이름이다)(옛 `model-names-sent-and-shown.md`) §9 Q2, 그 문서 §10 이 이
항목으로 올렸다. 번호가 L-22 · L-23 을 건너뛴 것은 같은 날 다른 작업이 그 두 번호를 예약했기 때문이다.)*

**무엇을.** `AnthropicConfig` 의 기본 모델 `claude-sonnet-4-20250514` 가 Anthropic Messages API 에서 아직 서비스되는지
재고, 그 결과로 기본값을 이미 잰 이름으로 바꿀지 그대로 둘지 정한다.

**왜.** 관측 가능한 결과 — `provider: anthropic` 에서 `llm.model` 을 적지 않으면 그 기본 모델로 도는 자리가 넷이다:
위키 페이지 생성, `model.name` 이 없는 메인 에이전트, #104 이후 모델을 적지 않은 서브에이전트(메인 에이전트의 이름도
없을 때), 그리고 #105 이후 메모리 부품 전부(dialectic 엔진 · deriver · reconciler · dreamer 와 그 LLM 판정기). 그
이름이 서비스되지 않는다면 기동은 성공하고 실패는 요청 시점에 벤더의 404 로 온다 — 메모리라면 배경 워커의 로그로.
메모리가 켜져 있으면 기동 줄이 모델과 키를 이름으로 대므로 원인은 찾을 수 있지만, 그 이름이 서비스되는지는 아무도 재지
않았다. #105 는 기동을 거절하는 대신 이 기본값으로 돌리기로 했으므로(설계 D-1) 이 질문의 무게가 그만큼 늘었다.

**어디.** 2026-09-11 — `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicConfig.java:40`
(`DEFAULT_MODEL`), 그 값을 돌려주는 `AnthropicLlmClient.getDefaultModelName()`, 그 값을 메모리에 넘기는
`AgentSetupFactory.memoryModelName`. 내장 capability 표도 답하지 않는다 — `InMemoryModelCapabilityRegistry` 의
`registerAnthropicDefaults` 주석이, 그 이름은 `claude-sonnet-4-5` 로 시작하지 않으므로 서술되지 않은 채 남는다고 적는다.

**심각도 (규칙 셋).** 재지 않았다. 성공하는 프로브는 과금되고, #104 ~ #107 의 인수 조건 어느 것도 그것을 요구하지
않았다. 그 표의 2026-09-10 실측이 잰 것은 undated `claude-*-4-5` 계열과 그것이 풀리는 스냅샷이지 이 이름이 아니다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 둘이 보인다 — 요청 하나로 서비스 여부를 재어 기록하는 것, 기본값을
이미 잰 이름으로 바꾸는 것. 후자는 배포 모듈 `aimon-llm-anthropic` 의 공개 기본값이 바뀌는 일이라 CHANGELOG 항목이
필요하다. #104 ~ #107 이후 `default-config.yaml` 의 주석과 CLI 가이드는 그 리터럴을 적지 않고 "Anthropic 클라이언트의
기본 모델" 이라고만 적으므로, 기본값이 바뀌어도 그 둘은 틀리지 않는다.

**언제 다시 볼까.** `AnthropicConfig` 의 기본값을 다음에 건드릴 때, 또는 anthropic 에서 `llm.model` 없이 띄운 메모리나
위키 생성이 404 로 실패한다는 보고가 올 때.

### 닫힘 (2026-09-11, #116)

**쟀고, 서비스되지 않았다 — 기본값을 `claude-sonnet-4-5` 로 바꿨다.** 잰 이름은 이슈가 아니라 코드에서 읽었다
(`c561e17` 의 `AnthropicConfig.java:40`). 키는 요청마다 그 명령 하나에만 헤더 파일로 넘겼고, 내보내거나 기록하지
않았다. 요청은 넷이고, 응답의 `date` 는 2026-09-11 04:13:49 ~ 04:16:06 GMT 다.

| # | 요청 | HTTP | 답 | `request-id` | 과금 |
|---|---|---|---|---|---|
| 1 | `GET https://api.anthropic.com/v1/models/claude-sonnet-4-20250514` | 404 | `not_found_error` — `model: claude-sonnet-4-20250514` | `req_011Cew14Po9JL3pp2n45Lyy7` | 아니다 |
| 2 | `POST https://api.anthropic.com/v1/messages` — `model: claude-sonnet-4-20250514`, `max_tokens: 1`, 한 단어 사용자 메시지 | 404 | 같은 `not_found_error` | `req_011Cew1AD4e7L3o7274fYiWa` | 아니다 |
| 3 | `GET https://api.anthropic.com/v1/models/claude-sonnet-4-5` | 200 | `id: claude-sonnet-4-5-20250929` — `thinking.types` 는 `enabled` 지원, `adaptive` 미지원 | `req_011Cew1ESjHVJA4iv1jppBdS` | 아니다 |
| 4 | `POST https://api.anthropic.com/v1/messages` — `model: claude-sonnet-4-5`, 나머지는 2 와 같다 | 200 | 서비스한 `model: claude-sonnet-4-5-20250929`, `stop_reason: max_tokens` | `req_011Cew1EUgrGJw1SazZj49Gq` | 그렇다 — 입력 8 · 출력 1 토큰 |

3 · 4 는 기본값을 바꾼다면 **같은 방법으로 잰 이름**으로만 바꾸려고 쟀다. 이 작업의 라이브 호출은 이 넷뿐이다.

**결정.** 옛 이름을 두고 쟀다고만 적는 모양은 설 자리가 없었다 — 서비스되지 않으므로, 두면 이 항목이 적은 네 자리가
오늘 요청 시점에 전부 404 로 실패한다. 남은 물음은 어느 이름이냐였고 `claude-sonnet-4-5` 가 가장 작게 움직인다. 같은
방법으로 쟀고, 내장 capability 표가 이미 서술하며(`claude-sonnet-4-5` prefix 행 — `BUDGETED` 이고 3 의
`thinking.types` 와 맞는다), `default-anthropic` 번들의 메인 에이전트가 이미 그 이름으로 돌고, 가격 · 컨텍스트 창 표는
두 이름을 같은 `claude-sonnet-4` prefix 로 답해 움직이지 않으며, 같은 Sonnet 급이다. 기각한 넷은 한 문장씩이다.

- **날짜 붙은 스냅샷 `claude-sonnet-4-5-20250929`** — 방금 낡은 것이 바로 그 모양이고, 기본값과 번들이 한 모델을 두
  철자로 적게 된다. 대가는 적어 둔다: 벤더가 undated 별칭을 같은 계열의 뒤 스냅샷으로 옮길 수 있다. 그때도 서비스한
  `model` 이 무엇이 돌았는지 말하고, prefix 행이 그 이름을 서술한다.
- **더 새 계열(`claude-sonnet-4-6`, `claude-sonnet-5`)** — 재지 않았고(과금 호출이 하나 더 든다), 이름 너머로 요청이
  바뀐다. `claude-sonnet-4-6` 은 `EITHER` 행이라 `auto` 가 adaptive 로 가고, `claude-sonnet-5` 행은 temperature 를 억제한다.
- **기본값을 없애고 모델을 요구하기** — 배포 모듈의 공개 계약(`AnthropicConfig.builder().apiKey(k).build()`)을 깨고,
  #105 의 결정을 뒤집어 anthropic 에서 `llm.model` · `aimon.llm.model` 을 필수로 만든다. 더 작은 모양으로 충분하다.
- **기동할 때 `GET /v1/models` 로 고르기** — 설정을 만드는 도중의 네트워크 호출이고, 키와 계정마다 답이 다르다.

항목이 물었던 "내장 표가 새 이름을 서술해야 하는가" 의 답은 **이미 서술한다** 이다. 행은 더하지 않았고
`InMemoryModelCapabilityRegistry` 의 `registerAnthropicDefaults` 주석과 그 테스트의 주석만 고쳤다. 근거는
[설계](../design/llm/model-name-resolution.md#4-클라이언트-기본-모델--openai-는-없고-anthropic-은-측정된-이름이다)(옛 `model-names-sent-and-shown.md` §11)이다.

**보이는 변화.** 모델을 적지 않은 세 경로가 새 이름으로 요청한다 — `.model(...)` 없이 만든 `AnthropicConfig`,
`llm.model` 없는 `provider: anthropic` CLI(이 항목의 네 자리이고, 메모리의 기동 줄은 이제 `claude-sonnet-4-5` 를 댄다),
`aimon.llm.model` 없는 스타터. 옛 이름으로 나간 요청은 Anthropic Messages API 에서 전부 404 였으므로 **그 API 에서**
성공하던 요청이 바뀌지는 않는다. `baseUrl` 뒤의 게이트웨이가 옛 이름을 아직 서비스하거나 허용하고 있었다면 이야기가
다르다 — 모델을 적지 않은 그 배포는 이제 `claude-sonnet-4-5` 를 보내고, 게이트웨이가 그 이름을 어떻게 다루는지는 재지
않았다. `thinkingMode` 를 적고 모델을 적지 않은 배포는 요청 모양도 바뀐다: `auto` 는 thinking 을 보내지 않던 자리에서
budgeted thinking 을 보내고(과금된다), `adaptive` 는 기존 경고와 함께 budgeted 로 옮겨지며, `extended` 와 배포 기본값
`off` 는 그대로다. CHANGELOG 에 적었다.

**규칙 다섯 — 처방을 적용했고, 재는 것이 먼저였다.** 항목이 본 둘째 모양(잰 이름으로 바꾸기)이다. 첫째 모양(재어
기록만 하기)은 잰 결과가 지웠다. `AnthropicConfigTest` 는 이제 기본값을 적은 단언에 더해, 기본값이 내장 표에서
`BUDGETED` 로 풀리는지 본다 — 다음에 기본값을 바꾸는 사람은 표와 마주친다. 기본값을 옛 이름으로 되돌리면 두 단언이
함께 빨개진다. 되돌려 보고 확인했다(설계 §11).

**남은 것.** 별칭도 은퇴하거나 옮겨 갈 수 있다. 다시 볼 계기는 anthropic 에서 모델을 적지 않은 요청이 404 로 실패한다는
보고, 또는 기본값을 다음에 건드릴 때다. 예시 코드가 여전히 서비스되지 않는 이름을 적는 자리는 L-27 로 올렸다.

---

## L-25 — 백그라운드 포크의 결과를 부모에게 전하는 두 자리는 `max_tokens` 에서 잘린 답을 완결된 답처럼 보여 준다

*(2026-09-11 등록. 출처는 #117 — [설계](../design/agent-execution/skill-loop-truncation-and-fork-stall.md) §8.1 F-1. #117 의
첫 항목은 전경 `Task` 호출의 출력을 고쳤고, 백그라운드로 띄운 포크가 지나는 두 파일은 그 작업의 파일이 아니었다. 여기 두는
것은 L-23 의 짝이기 때문이다.)*

**무엇을.** 백그라운드 포크의 최종 답이 `max_tokens` 에서 잘렸을 때, 그 결과를 부모 모델에게 전하는 `AgentOutput` 도구와 완료
알림이 답이 잘렸다는 사실을 말하게 한다.

**왜.** 관측 가능한 결과는 이렇다 — `run_in_background` 로 띄운 포크의 최종 답이 잘리면 태스크 기록은 `TRUNCATED` 를 저장한다.
그러나 부모 모델이 그것을 읽는 두 자리는 그 값을 전하지 않는다. `AgentOutput` 은 `TaskResult.getStatus()` 를 찍으므로 `SUCCESS`
로 보이고, 기록에 이미 있는 완료 사유는 찍지 않는다 — 요약은 뒤를 남기며 자르므로 끝의 마커는 거기서는 살아남는다. 완료 알림은
그 태스크를 `COMPLETED` 로 부르고 요약을 **앞에서부터** 500자로 자르므로, 답이 길면 끝의 마커가 잘려 나간다. 전경 `Task` 호출은
#117 이후 결과 뒤에 `Completion reason: TRUNCATED (…)` 줄을 찍는다 — 이 항목은 같은 결함의 백그라운드 절반이다.

**어디** *(2026-09-11, `c561e17`)* — `AgentOutputTool.formatAgentResult` 의 `Status:` 줄(`AgentOutputTool.java:378`),
`DefaultSubagentExecutionManager` 의 `completionDetail` · `truncateDetail` 과 `COMPLETION_DETAIL_MAX_CHARS`(`:107`).

**심각도 (규칙 셋).** 읽어서 얻은 결론이고 돌려 보지는 않았다. 부모 모델이 알림과 `AgentOutput` 중 무엇을 먼저 읽는지, 잘린
답이 500자를 넘는 경우가 얼마나 흔한지는 세지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 보인다 — 전경 `Task` 와 같은 줄을 `AgentOutput` 에도 찍는 것, 그리고 알림이
완료 사유를 따로 적거나 요약을 뒤에서 남기며 자르는 것. `AgentOutput` 의 출력을 파싱하는 곳이 트리에 있는지는 확인하지 않았다 —
전경 `Task` 의 출력은 `aimon-cli` 의 `SubagentResultDisplayHook` 이 파싱하므로 새 줄의 위치가 결정을 갈랐다.

**언제 다시 볼까.** `AgentOutputTool` 이나 완료 알림을 다음에 건드릴 때, 또는 백그라운드 태스크의 잘린 답을 부모가 완결로 다뤘다는
보고가 있을 때.

---

## L-26 — 잘린 최종 답을 돌려준 슬래시 스킬을 실행한 턴은 `COMPLETED` 로 끝난다

*(2026-09-11 등록. 출처는 #115 — [설계](../design/agent-execution/skill-loop-truncation-and-fork-stall.md) §8.1 F-2 · §9 Q4. 결정
항목이다 — 그 설계는 이 질문을 유지보수자가 정하도록 남겼고, 무엇으로 정하든 그 작업의 파일이 아닌 `command/**` 가 필요하다.)*

**무엇을.** 슬래시 명령으로 실행한 인라인 스킬의 최종 답이 `max_tokens` 에서 잘렸을 때, 그 턴의 `CompletionReason` 이 그 사실을
말하게 할지 정한다.

**왜.** #115 이후 스킬 루프는 잘린 최종 답을 성공으로 돌려주고 텍스트 끝에 `[System: response truncated at max_tokens]` 를
붙인다. 턴은 그 텍스트를 최종 답으로 커밋하고 `OrcaAgentExecutionResult.success(...)` 로 끝나므로 `COMPLETED` 다 — 에이전트가
직접 쓴 잘린 답이 `TRUNCATED` 로 끝나는 것과 다르다. 턴의 완료 사유로 결과를 가르는 소비자(예: `LiveSession` 을 쓰는
애플리케이션)는 텍스트에서 마커를 찾지 않는 한 차이를 모른다. 트리 안에는 턴의 `TRUNCATED` 로 가르는 독자가 없다 — CLI 의
`OutputFormatter` 는 `INTERRUPTED` 만 본다.

**어디** *(2026-09-11)* — `SkillExecutionResult`(완료 사유가 없다), `SkillBackedCommandExecutor.toCommandResult`(네 필드를
옮긴다), `CommandExecutionResult`(사유를 담을 자리가 없다), `OrcaAgentExecutor.executeCommandFlow`(`success` 나 `failure` 로만
끝낸다).

**심각도 (규칙 셋).** 읽어서 얻은 결론이다. 트리 밖에서 턴의 완료 사유를 읽는 애플리케이션이 몇인지는 셀 수 없다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 둘이 보인다 — 스킬 결과와 `CommandExecutionResult` 에 사유를 더해 턴까지
나르는 것(`command/**` 의 공개 추가), 또는 턴이 스킬 결과의 텍스트 끝에서 마커를 읽는 것. 지금처럼 두는 것도 선택지다 — 그 설계가
타입 신호를 기각한 이유는 읽는 곳이 없다는 것이었고, 이 항목은 그 읽는 곳이 생기는 날을 위한 것이다.

**언제 다시 볼까.** `CommandExecutionResult` 나 `executeCommandFlow` 를 다음에 건드릴 때, 또는 턴의 완료 사유로 잘린 답을 가르는
소비자가 생길 때.

---

## L-27 — 복사해 쓰는 예시가 설정된 provider 가 서비스하지 않는 모델 이름을 적는다

*(2026-09-11 등록. 출처는 #116 의 실측과 #118 item 5 — [설계](../design/llm/model-name-resolution.md#6-문서-예시-규칙--요청에-닿는-값에-모델-이름을-쓰지-않는다)(옛 `model-names-sent-and-shown.md`) §11 이
이 항목으로 올렸다. 번호가 L-25 · L-26 을 건너뛴 것은 같은 날 다른 작업이 그 두 번호를 예약했기 때문이다.)*

**무엇을.** 모델 이름을 적는 README 와 javadoc 예시가 서비스되는 이름을 적거나, 이름을 적지 않게 한다.

**왜.** 관측 가능한 결과 — 예시를 그대로 복사한 사람의 첫 요청이 404 로 실패한다. `aimon-llm-anthropic` 의 README 빠른
시작이 적는 `claude-sonnet-4-20250514` 는 2026-09-11 에 Messages API 와 모델 API 모두 404 였다(L-24). #118 item 5 는
코어의 서브에이전트 다섯 파일에서 별칭 예시를 뺐지만, 같은 모양의 예시가 그 다섯 파일 밖에 남았다. 기본값을 바꾼 변경이
이 줄들을 틀리게 만든 것은 아니다 — 그 전에도 서비스되지 않는 이름을 가리키고 있었다.

**어디** *(2026-09-11)*.

| 자리 | 적힌 이름 | 무엇 |
|---|---|---|
| `modules/aimon-llm-anthropic/README.md:55` | `claude-sonnet-4-20250514` | 빠른 시작의 `.model(...)` |
| `modules/aimon-llm-anthropic/README.md:106` | `claude-opus-4-20250514` | 예시의 `.name(...)` |
| `AnthropicConfig.java:34` | `claude-sonnet-4-20250514` | 클래스 javadoc 의 사용 예 |
| `AnthropicConfig.java:321` | `claude-sonnet-4-20250514`, `claude-opus-4-20250514` | `Builder.model` 의 `@param` 예 |
| `AnthropicLlmClient.java:78` | `claude-sonnet-4-20250514` | 클래스 javadoc 의 사용 예 |
| `MarkdownSubagentParser.java:22` | `sonnet` | 프론트매터 예시의 `model:` |
| `SubagentParser.java:20` | `sonnet` | 프론트매터 예시의 `model:` |
| `docs/features/subagent/subagent-development-guide.md:109` · `:256` — 영어 `.en.md:115` · `:264` | `sonnet` | 코드 서브에이전트 예시 둘의 `.model(...)`. 앞의 것은 `// 모델 별칭` 이라고 적는다 — #132 가 고쳤다 |
| `docs/features/subagent/subagent-development-guide.md:329` · `:354` — 영어 `.en.md:341` · `:366` | `sonnet` | `resolvedModel()` 행과 그 아래 인용이 서브에이전트의 `model` 을 별칭이라 부른다. 이름은 앞의 줄에만 있다 — #132 가 고쳤다 |
| `docs/features/skill/builtin-agent-skill-guide.md:80` · `:123` — 영어 `.en.md:85` · `:128` | `sonnet` | `.aimon/agents/explore.md` · `my-analyzer.md` 프론트매터 예시의 `model:` — #132 가 고쳤다 |

> **정정** *(2026-09-11, #132)*: 이 표는 처음에 일곱 행이었고, 같은 모양의 자리를 두 기능 가이드에서 빠뜨렸다 — 마지막
> 세 행이 그것이고 줄은 `2eddf3d` 에서 읽었다. 세 행은 #132 가 고쳤다: 예시의 모델 줄을 빼고 그 자리에, 모델이 없으면
> 무엇으로 도는지와 언제 id 를 적는지를 말하는 주석을 두었고(처방의 첫째 모양), `resolvedModel()` 행은 코어 javadoc
> 처럼 이름을 쓰인 그대로 보내며 비어 있을 수 있다고 적는다. 앞의 일곱 행은 그대로 열려 있다. 이 항목의 **언제 다시
> 볼까** 가 말한 계기 하나가 왔지만 — #132 가 `aimon-llm-anthropic` 의 README 를 건드렸다 — 고친 것은 `temperature`
> 행뿐이고, 이 표의 README 두 행은 손대지 않았다.

**심각도 (규칙 셋).** `claude-sonnet-4-20250514` 는 2026-09-11 에 두 API 모두 404 였고(L-24), 맨 별칭 `haiku` 는
2026-09-10 에 Anthropic 이 404 로 답했다([#92 설계](../design/llm/model-name-resolution.md#71-두-관문--엔드포인트와-모델-계열), 옛 `provider-switch-agent-model-check.md` §10.4). 코드는
별칭을 풀지 않으므로 `sonnet` 도 쓰인 그대로 나간다. `claude-opus-4-20250514` 와 `sonnet` 자체는 재지 않았다.

**처방은 적용해 보지 않았다 (규칙 다섯).** 모양은 둘이다 — 예시에서 모델 줄을 빼는 것(#118 item 5 가 코어 다섯 파일에서
한 모양), 잰 이름으로 바꾸는 것. 후자는 낡을 리터럴을 늘린다.

**언제 다시 볼까.** `aimon-llm-anthropic` 의 README 나 `AnthropicConfig` · `AnthropicLlmClient` 의 javadoc 을 다음에
건드릴 때, 서브에이전트 파서를 건드릴 때, 또는 기본 모델이 다시 바뀔 때.

---

## 관련 문서

이 등록부가 부르는 옛 설계 문서(`옛 …md`)와 그 절 번호는 [`../design/README.md` §4](../design/README.md#4-옛-경로-대응표) 가
적은 마지막 판본 커밋에서 읽는다.

- [`../design/llm/configuration-surface.md`](../design/llm/configuration-surface.md) — 설계. 옛 `model-capability-config-key.md`(§9 가 설계 시점의 미해결 목록, §11 이 구현 중
  실측으로 뒤집힌 사실)와 옛 `model-capability-binding-round-trip.md`(#82 의 설계. §9 O-1 · O-2 가 L-13 · L-14 의 출처)를 합친 문서다
- [`../design/llm/anthropic-thinking.md`](../design/llm/anthropic-thinking.md) — 옛 `thinking-reporting-and-dialect-records.md` 의 결정이 간 곳. 그 기록은 L-6·L-7 을 닫고
  L-9·L-10·L-11 을 열었다. §14 의 방언 census 는 지금 `model-capabilities.md` §6, §16(#83 의 결정, L-15 의 출처)과
  §16.8(#89 의 결정, L-16 의 출처)은 지금 이 문서 §6 이다
- [`../design/agent-execution/max-tokens-truncation-reporting.md`](../design/agent-execution/max-tokens-truncation-reporting.md) —
  #108 · #100 · #101 의 설계. L-16 을 닫았고, §11 이 L-22 · L-23 으로 올린 것과 설계 문서에 남긴 것을 가른다
- [`../design/agent-execution/skill-loop-truncation-and-fork-stall.md`](../design/agent-execution/skill-loop-truncation-and-fork-stall.md) —
  #115 · #117 의 설계. D1 · D2 · D5 가 L-22 를, D3 · D4 가 L-23 을 닫은 결정이고, §8.1 F-1 이 L-25 의, F-2 와 §9 Q4 가 L-26 의
  출처이며, §11 이 구현이 설계에서 벗어난 자리다
- [`../design/llm/model-capabilities.md`](../design/llm/model-capabilities.md) — capability SPI 자체의 설계(옛 `openai-model-capabilities.md`). 옛 §7 O-8 이 이 작업으로
  닫혔다
- [`../design/llm/openai-responses-path.md`](../design/llm/openai-responses-path.md) — 옛 판본의 F-2 가 L-2 의 출처
- [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) — B-21(공통 `aimon.llm.*` 을
  프로바이더별로 쪼갤 것인가)이 이 작업으로 다시 열려 결정되고 닫혔다. L-2 · L-3 이 그 결정문을 인용한다
- [`live-api-test-tier.md`](live-api-test-tier.md) — L-12 가 닫힌 #81 의 결정. 라이브 API 계층에
  CI 신호가 없다는 것과 그 이유
- [`../design/llm/model-name-resolution.md`](../design/llm/model-name-resolution.md) — 옛 `provider-switch-agent-model-check.md`(#92 의 설계. §8 이 D 번호로 된 원래 목록이고,
  §10 이 그중 L-17 ~ L-21 로 올린 것을 가르며, §11 이 그 다섯을 닫은 #104 ~ #107 을 적는다)와 옛
  `model-names-sent-and-shown.md`(#104 ~ #107 의 설계. D-1 ~ D-5 가 L-17 ~ L-21 을 닫은 결정이고, §9 Q2 가 L-24 의 출처,
  §11 이 L-24 를 닫은 실측과 결정이자 L-27 의 출처)를 합친 문서다
- [`../getting-started/aimon-core-integration-via-cli-reference.md`](../getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다) —
  CLI 가이드의 provider 전환 절. L-17 의 주의 문구와 L-18 · L-19 가 적혀 있던 자리이고, #104 ~ #107 이 그 항목들을
  닫으며 함께 고쳤다
- [`README.md`](README.md) — 항목 등록 규칙
