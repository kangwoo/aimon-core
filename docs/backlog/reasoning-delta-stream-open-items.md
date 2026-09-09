# 추론 스트림 — 등록 항목 6건 (열림 6)

출처는 #62 다 — 모델의 숙고를 사용자가 볼 수 있게 만든 작업(`LlmStreamChunk.Kind.REASONING_DELTA` ·
`AssistantReasoningDelta` · 양쪽 provider 의 opt-in 키). 설계는
[`../design/llm/reasoning-delta-stream.md`](../design/llm/reasoning-delta-stream.md) 이고, 그 문서 §11 의
미해결 항목 중 **이 국면 밖으로 결과가 나가는 것**과 §10 이 "고치지 말고 파일하라" 고 적은 것이
여기로 올라왔다. 나머지는 설계 문서에 그대로 남는다 — O-4(노드 경계를 넘길 것인가)와 O-6(`[thinking]`
문구)은 이 작업 안에서 답이 나왔고, O-5(`reasoningBuffer` 의 프로덕션 독자 부재)는 설계가 저울에 올려
"유지" 로 결정한 판단이다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §11 은 설계 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 전부 2026-09-10 기준이다.

---

## RD-1 — `display` 의 와이어 값이 실측되지 않았고, 그중 하나가 우리 요청에 실린다

**무엇을.** Anthropic 요청의 `thinking.display` 에 보내는 두 값(`"summarized"` · `"updates"`)이
실제로 그 철자인지 한 번 확인한다.

**왜.** 관측 가능한 결과는 **HTTP 400 이고, 그것을 받는 것은 이 키를 켠 배포뿐이다.**
`anthropic-thinking-traces.md` §8 F-7 이 두 값을 이름으로 적었고 그 문서 `:165` 가 기본값을
`"omitted"` 로 적었지만, **이 트리는 그 필드를 한 번도 보낸 적이 없고 SDK 는 그것을 모델링하지
않는다** — `anthropic-java-core` 2.13.0 의 `ThinkingConfigAdaptive` 는 `type` 하나만 들고 있어서
`putAdditionalProperty` 로 쓴다. 즉 오타가 있어도 컴파일 타임에도 SDK 타입 검사에도 걸리지 않는다.
설계가 스스로 이것을 §11 O-1 로 적고 *"구현하는 사람이 키가 있으면 머지 전에 실제 요청을 한 번
보내고 결과를 어느 쪽이든 기록해야 한다"* 고 요구했는데, **그 요청은 보내지지 않았다** — 이 환경에
키가 없었고 두 provider 어느 쪽에도 과금 호출을 하지 않았다.

**어디.** `AnthropicThinkingDisplay.wireValue()`(2026-09-10)의 두 상수, 그리고 그 값을 쓰는
`AnthropicLlmClient.resolveThinking` 의 `putAdditionalProperty("display", …)`.

**무게가 제한되어 있다는 것도 함께 적는다.** 이 키는 opt-in 이고 기본이 unset 이므로 **적어 넣은
배포만** 이 자리에 도달한다. 그리고 설계가 boolean 대신 enum 을 고른 이유가 정확히 이것이다 — 값이
운영자가 쓰는 것이므로, 철자가 틀렸다고 밝혀지면 처방이 릴리스가 아니라 yaml 한 줄이다. 그래도
그 처방을 알려면 누군가 400 을 먼저 맞아야 한다.

**언제 다시 볼까.** 셋 중 하나다.

1. 이 저장소에 Anthropic 키가 붙는 순간 — 그때 이 항목은 30분이 아니라 **한 번의 요청**이다.
   `AnthropicThinkingLiveTest` 가 이미 그 자리에 있다(`anthropic-thinking-traces.md` §8 F-8 이 그
   클래스의 실행 조건을 적어 두었다).
2. 옵트인한 배포가 400 을 보고할 때 — 그러면 값은 사실이 되고 이 항목은 그 자리에서 닫힌다.
3. SDK 가 `display` 를 타입으로 모델링할 때. 그러면 철자는 SDK 가 들고 오고, `putAdditionalProperty`
   한 줄이 타입 있는 setter 한 줄로 바뀐다.

---

## RD-2 — `reasoning.summary` 가 `include` 항목을 요구하는지 실측되지 않았다

**무엇을.** OpenAI Responses 요청이 요약을 받으려면 `include` 에 무언가를 더 적어야 하는지 확인한다.

**왜.** 관측 가능한 결과는 **조용한 빈 채널**이다 — 요청은 200 이고 요약 이벤트는 오지 않으며,
그것은 "모델이 요약을 만들지 않았다" 와 화면에서 구분되지 않는다. RD-1 의 400 과 달리 이쪽은
아무도 알아채지 못하는 종류의 실패다.

**어디.** `OpenAIResponsesRequestFactory.build`(2026-09-10)가
`include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))` 하나만 적는다.

**근거가 부재의 추론이다.** `openai-java-core` 4.57.0 의 `ResponseIncludable.Known` 에 요약용 상수가
**없다**(javap 로 확인). 그래서 "없으니 필요 없다" 로 읽었는데, 이것은 **없는 enum 상수로부터의
추론이지 측정이 아니다** — SDK 가 아직 모델링하지 않은 값일 수도 있고, 그 경우 `include` 도
`putAdditionalProperty` 로 써야 한다. 설계 §11 O-3 이 그 구분을 적어 두었다.

**언제 다시 볼까.** 이 저장소에 OpenAI 키가 붙거나, 옵트인한 배포가 "켰는데 아무것도 안 나온다" 를
보고할 때. 두 번째 경우 가장 먼저 확인할 것이 이 항목이다 — 모델이 요약을 지원하지 않는 것과
`include` 가 빠진 것이 밖에서 똑같이 보이기 때문이다.

---

## RD-3 — `OutputFormatter.displayEvent` 가 두 서브타입에서 던진다

**무엇을.** REPL 이 `InterruptedAt` 과 `RejectedAt` 을 받으면 `IllegalStateException` 을 던진다.
둘을 렌더하거나, 의도적 no-op 으로 만든다.

**왜.** **이 라운드가 만든 결함이 아니라 이 라운드의 사이트 감사가 찾아낸 기존 결함이다.**
`displayEvent` 의 instanceof 체인은 sealed 계층 16개 중 **14개**만 처리하고 나머지 둘은 마지막
`else` 로 떨어진다. 그 체인의 주석은 *"sealed 계층이 이 체인을 갱신하도록 강제한다"* 고 적고 있지만
**강제하지 않는다** — 두 서브타입이 이미 그 반례이고, 지금 이 줄을 초록으로 통과시키는 것은
`grep -rn "InterruptedAt\|RejectedAt" modules/aimon-cli/src/` 가 0건이라는 사실뿐이다. 관측 가능한
결과: 라우터 경로가 REPL 에 그 프레임을 흘리는 배선이 하나라도 생기면 렌더러가 턴 중간에 죽는다.

**어디.** `modules/aimon-cli/src/main/java/at/aimon/cli/repl/OutputFormatter.java` 의
`displayEvent` 마지막 `else`(2026-09-10).

**착수하는 사람이 먼저 정할 것.** 던지지 않게 만드는 것은 한 줄이지만, **무엇을 인쇄할지가 이
항목의 실제 내용**이다. `InterruptedAt` 은 `getPartialOutput()` 을 들고 있고 `RejectedAt` 은 거절
사유와 두 에이전트 이름을 든다 — 각각 REPL 이 이미 다른 경로로 보여 주는 것과 겹칠 수 있다.
`README.md` 규칙 다섯대로 **고치기 전에 실패하는 테스트를 먼저 만든다**: 오늘
`assertThatThrownBy(() -> formatter.displayEvent(interruptedAt))` 이 초록이므로, 그 반대를 쓰는 것이
첫 걸음이다.

**언제 다시 볼까.** 세션 라우터의 이벤트가 REPL 로 흐르게 되는 순간, 또는 누가 그 예외를 밟을 때.
전자가 먼저 올 가능성이 높다.

---

## RD-4 — 중립 `llm.streamReasoning` 우산 키를 열 것인가

**무엇을 (결정 항목).** 두 provider 가 함께 읽는 중립 키 하나를 벤더 키 둘 위에 얹을 것인가.

**왜 지금 안 했나.** 설계 §3.4 가 `model-capability-config-key.md` §2.7 을 적용해 **shared 로
판정하고도 싣지 않았다**, 이유 둘 때문이다. (i) 벤더 키 둘 **옆에** 두면 §3 **R2** 가 거절하는
모양이 된다 — 사용자 개념이 둘이 되고 둘이 동시에 지정될 때의 우선순위라는 세 번째 규칙이 생긴다.
(ii) 벤더 키 둘 **대신** 두면 boolean 이 `concise` · `detailed` · `updates` 를 표현하지 못하고,
나중에 enum 으로 넓히는 것은 설정 키의 **타입을 깨는 변경**이다.

**언제 다시 볼까 — 트리거가 이름으로 적혀 있다.** 세 번째 provider 가 붙을 때, 또는 배포를 벤더
사이에서 옮기면서 키를 다시 쓰지 않아도 되게 해 달라는 요구가 나올 때. 그 시점에는 우산이 **순수
가산**이 된다(벤더 키가 덮는 기본값), 즉 우선순위 규칙을 부작용이 아니라 의도적으로 쓰게 된다.

---

## RD-5 — `AssistantTextStreamReset` / `…Completed` 의 이름이 하는 일보다 좁다

**무엇을.** 두 이벤트는 이제 **시도(attempt) 전체**를 경계 짓고 추론 채널도 함께 닫는데, 이름은
텍스트만 말한다. `scope-model.md` §6 의 오칭 목록에 올릴 것인가, 개명할 것인가.

**왜.** 관측 가능한 결과는 아직 없다 — 두 이벤트를 둘 다 처리하는 렌더러는 두 채널을 다 닫으므로
동작은 옳다. 문제는 **다음 사람이 이름으로 추론할 때**다: "텍스트 스트림이 리셋됐다" 는 문장은
추론 델타를 지워야 한다고 말해 주지 않는다. 두 클래스의 javadoc 에 그 사실을 한 문장씩 적어
두었으므로(2026-09-10) 오늘의 처방은 "읽으면 알 수 있다" 이고, 그것이 오칭 목록이 존재하는 이유다.

**개명하지 않은 이유.** 이 두 이름은 **공개 이벤트 타입이자 노드 경계를 넘는 페이로드 프레임
이름**이다(`AgentExecutionEventPayload` 의 `"AssistantTextStreamReset"` / `"AssistantTextStreamCompleted"`).
개명은 sealed 계층의 소스 파괴에 더해 롤링 업그레이드 중 프레임 유실을 뜻하고, 그것은 이 기능보다
큰 변경이다.

**언제 다시 볼까.** 다음에 이 두 타입을 어떤 이유로든 건드릴 때 함께. 단독으로 착수할 값은 없다.

---

## RD-6 — 에이전트 정의의 `model.reasoningSummary`

**무엇을 (결정 항목).** 요약을 요청할지 말지를 **에이전트 단위**로도 쓸 수 있게 할 것인가.

**왜 지금 안 했나.** 설계 §3.5 의 판단이다 — 에이전트 정의는 **에이전트**를 서술하고, 터미널이
숙고를 렌더하는지는 **그것을 돌리는 배포**를 서술한다. 같은 정의 파일을 CLI 사용자와 콘솔 없는
Spring 서비스와 스케줄 루틴이 함께 읽는다. #61 이 `model.reasoningEffort` 를 세 번째 표면에 실은
것과 대비되며, 그 대비가 이 항목이 결정 항목인 이유다.

**반대 논거도 적어 둔다** (규칙 넷: 결정문의 전제는 착수 근거보다 비싸다). 이 요청은 매 요청
**출력 토큰을 쓰고**, 토큰 비용은 모델 동작 노브이므로 frontmatter 의 소관이기도 하다. 설계는
그 비용이 "에이전트의 설계가 아니라 배포의 렌더링 선택의 결과" 라는 이유로 기울었지만, 그것이
결정적이지는 않다고 스스로 적었다.

**언제 다시 볼까.** 누군가 토큰 비용을 에이전트 단위로 제어하고 싶다고 말할 때. 그때 확인할 전제는
하나다 — 그 사람이 원하는 것이 **에이전트마다 다른 답**인가, 아니면 **배포마다 다른 답**인가.
후자면 이 항목이 아니라 이미 있는 벤더 키가 답이다.
