# LLM 설정 표면 — 등록 항목 8건 (열림 8)

출처는 #46 이다 — 모델 capability 표를 CLI yaml 과 스타터 프로퍼티에서 확장할 수 있게 한 작업.
설계는 [`../design/llm/model-capability-config-key.md`](../design/llm/model-capability-config-key.md) 이고,
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
> `/v1/responses` 에서만 실측되었다**([`../design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md)
> §13.3, 그리고 그 문서가 §13.3 첫머리에서 *"인용된 열거는 그것이 나온 엔드포인트 없이는 아무 뜻이
> 없다"* 고 경고하는 바로 그 자리다). 판정 지점인 `OpenAiRequestParameters.maySendEffort` 는 **두
> 엔드포인트가 함께 부르므로**, `responsesApiEnabled(false)` 로 터라를 Chat Completions 에 강제한 배포는
> 이제 재어진 적 없는 칸에 `reasoning_effort: none` 을 보낸다 — 오늘의 "보고된 누락" 이 400 이 될 수 있다.
>
> **오늘은 물리지 않는다.** 그 스위치는 자바로만 켤 수 있고, 그것을 바꾸는 것이 이 항목이다. 그러니 이
> 칸은 **이 항목을 착수하는 사람의 것**이다: `responsesApiEnabled` 에 설정 표면을 주는 순간 위 조합이
> 운영자 경로로 내려오므로, 그때 터라의 `none` 을 Chat 에서 한 번 재거나(키가 있으면), 재지 않기로 하고
> 그 사실을 적어야 한다. 행 자체는 여전히 옳다 — 대안은 `minimal` 에 대한 **실측된** 400 이다.
> 근거: [`../design/llm/reasoning-effort-config-surface.md`](../design/llm/reasoning-effort-config-surface.md) §17.4.

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
> [`../design/llm/anthropic-sampling-capabilities.md`](../design/llm/anthropic-sampling-capabilities.md) §7.1.

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
[`../design/llm/anthropic-thinking-config-surface.md`](../design/llm/anthropic-thinking-config-surface.md) §13 O-3)*

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
[`../design/llm/reasoning-model-enablement.md`](../design/llm/reasoning-model-enablement.md) §9 U-1 이
적어 둔 *"행렬은 실측이지만 **오늘 어느 모델이 그 위에 있는지는 아니다**"* 라는 유보가 **그 다섯에 대해
해소된다.**

**세는 단위를 이렇게 적어 두는 것까지가 이 항목의 일이다.** 집합을 조금 틀리게 열거하는 것은 이 항목을
낳은 정정(`AnthropicThinkingDisplay` 의 `updates`)이 바로잡는 결함과 **같은 종류**이고, 처음 쓴 이 문단이
실제로 그 실수를 했다 — 리뷰가 잡았다.

**언제 다시 볼까.** #60 의 표를 다음에 손댈 때. **이 국면(#62)에서 하지 않은 것은 의도**다 — 이미 리뷰를
통과한 PR 을 넓히지 않으려는 것이고, 표는 그 PR 의 소유물이다.

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
`AnthropicLlmClient.resolveDialect`(2026-09-10).

**언제 다시 볼까.** 셋 중 하나다 — L-6 을 착수할 때(같은 표를 여는 김에), 세 번째 방언이 생길 때, 또는
누군가 `UNKNOWN` 을 "어느 쪽이든 된다" 로 읽고 버그를 낼 때. 값을 하나 더할지(`EITHER`), 아니면
방언을 집합으로 표현할지(#61 이 `lowestReasoningEffort` → `acceptedReasoningEfforts` 로 한 것과 같은
모양)는 착수 시점의 결정이다 — 후자에는 이미 **선례가 있다**.

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

## 관련 문서

- [`../design/llm/model-capability-config-key.md`](../design/llm/model-capability-config-key.md) — 설계.
  §9 가 설계 시점의 미해결 목록, §11 이 구현 중 실측으로 뒤집힌 사실
- [`../design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) — capability
  SPI 자체의 설계. §7 O-8 이 이 작업으로 닫혔다
- [`../design/llm/openai-responses-path.md`](../design/llm/openai-responses-path.md) — F-2 가 L-2 의 출처
- [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) — B-21(공통 `aimon.llm.*` 을
  프로바이더별로 쪼갤 것인가)이 이 작업으로 다시 열려 결정되고 닫혔다. L-2 · L-3 이 그 결정문을 인용한다
- [`README.md`](README.md) — 항목 등록 규칙
