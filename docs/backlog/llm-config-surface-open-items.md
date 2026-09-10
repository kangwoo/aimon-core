# LLM 설정 표면 — 등록 항목 15건 (열림 11 · 닫힘 4)

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

측정과 방법의 정정은 [`../design/llm/reasoning-model-enablement.md`](../design/llm/reasoning-model-enablement.md)
§3.5 에 있고, §9 U-1 의 유보가 그것으로 census 전체에 대해 해소되었다.

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
[`../design/llm/thinking-reporting-and-dialect-records.md`](../design/llm/thinking-reporting-and-dialect-records.md)
§11 O-1. **이 국면 밖으로 결과가 나가는 조율 항목이다.**)*

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
행이 아니라 클라이언트에 있다 — `AnthropicLlmClient` 가 adaptive 를 고른다. 오늘 그것으로 충분한 이유는
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
[`../design/llm/reasoning-model-enablement.md`](../design/llm/reasoning-model-enablement.md) §9 U-1
(2026-09-10).

**언제 다시 볼까.** 그 prefix 로 시작하는 모델이 어느 계정에서든 닿을 때. 그때 두 이름을 각각 프로브하면
prefix 를 쪼갤지(Preview 만 `EITHER`) 그대로 둘지가 한 번에 정해진다. 그전에 쪼개는 것은 아무도 본 적 없는
식별자를 주장하는 일이고, 그것은 이 행이 지금 한 prefix 인 이유 그 자체다.

---

## L-12 — 라이브 서명 음성 대조 테스트가 서버 문구 두 가지 때문에 절반쯤 깜빡인다

*(2026-09-10 등록. 출처는 #73 국면의 빌드 —
[`../design/llm/thinking-reporting-and-dialect-records.md`](../design/llm/thinking-reporting-and-dialect-records.md)
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

**어디.** `AnthropicThinkingLiveTest:172-190` 근방(2026-09-10).

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

**어디** *(2026-09-10)* — `AnthropicThinkingLiveTest:188` · `OpenAIReasoningLiveTest:229`

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
[`../design/llm/model-capability-binding-round-trip.md`](../design/llm/model-capability-binding-round-trip.md)
§9 O-1. 그 문서 §11 이 이 항목으로 올린 이유를 적는다.)*

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
[`../design/llm/thinking-reporting-and-dialect-records.md` §16](../design/llm/thinking-reporting-and-dialect-records.md#16-the-auto-budget-policy-decided-83-2026-09-10).
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

## 관련 문서

- [`../design/llm/model-capability-config-key.md`](../design/llm/model-capability-config-key.md) — 설계.
  §9 가 설계 시점의 미해결 목록, §11 이 구현 중 실측으로 뒤집힌 사실
- [`../design/llm/model-capability-binding-round-trip.md`](../design/llm/model-capability-binding-round-trip.md) —
  #82 의 설계. §9 O-1 · O-2 가 L-13 · L-14 의 출처이고, §11 이 나머지 미해결을 왜 그 문서에 두었는지 적는다
- [`../design/llm/thinking-reporting-and-dialect-records.md`](../design/llm/thinking-reporting-and-dialect-records.md) —
  L-6·L-7 을 닫고 L-9·L-10·L-11 을 연 설계. §14 가 방언 census 의 원자료, §15.4 가 이 세 항목의 승격 근거이고,
  §16 이 #83 의 결정이자 L-15 의 출처다
- [`../design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) — capability
  SPI 자체의 설계. §7 O-8 이 이 작업으로 닫혔다
- [`../design/llm/openai-responses-path.md`](../design/llm/openai-responses-path.md) — F-2 가 L-2 의 출처
- [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) — B-21(공통 `aimon.llm.*` 을
  프로바이더별로 쪼갤 것인가)이 이 작업으로 다시 열려 결정되고 닫혔다. L-2 · L-3 이 그 결정문을 인용한다
- [`live-api-test-tier.md`](live-api-test-tier.md) — L-12 가 닫힌 #81 의 결정. 라이브 API 계층에
  CI 신호가 없다는 것과 그 이유
- [`README.md`](README.md) — 항목 등록 규칙
