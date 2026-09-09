# LLM 설정 표면 — 등록 항목 5건 (열림 5)

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

## 관련 문서

- [`../design/llm/model-capability-config-key.md`](../design/llm/model-capability-config-key.md) — 설계.
  §9 가 설계 시점의 미해결 목록, §11 이 구현 중 실측으로 뒤집힌 사실
- [`../design/llm/openai-model-capabilities.md`](../design/llm/openai-model-capabilities.md) — capability
  SPI 자체의 설계. §7 O-8 이 이 작업으로 닫혔다
- [`../design/llm/openai-responses-path.md`](../design/llm/openai-responses-path.md) — F-2 가 L-2 의 출처
- [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) — B-21(공통 `aimon.llm.*` 을
  프로바이더별로 쪼갤 것인가)이 이 작업으로 다시 열려 결정되고 닫혔다. L-2 · L-3 이 그 결정문을 인용한다
- [`README.md`](README.md) — 항목 등록 규칙
