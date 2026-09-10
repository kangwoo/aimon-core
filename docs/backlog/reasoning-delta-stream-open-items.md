# 추론 스트림 — 등록 항목 6건 (열림 4 · 닫힘 2)

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

## RD-1 — `display` 의 와이어 값이 실측되지 않았고, 그중 하나가 우리 요청에 실린다 · **닫힘 (2026-09-10, 실측)**

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

### 닫힘 (2026-09-10) — 실측했고, **값 하나가 틀려 있었다**

**결과.** 실제 요청을 보냈다(`api.anthropic.com/v1/messages`, 근거는 태스크 기록의 `MEASUREMENTS.md`).
`display: "updates"` 는 **400** 이고, 서버가 돌려주는 본문은
`thinking.adaptive.display: Input should be 'summarized', 'omitted'` 다. **받아 주는 집합은 정확히
`{summarized, omitted}` 이고 `updates` 는 없다.**

| 요청 `thinking` | HTTP |
|---|---|
| `{"type":"adaptive"}` (display 없음) | 200 |
| `{"type":"adaptive","display":"summarized"}` | 200 |
| `{"type":"adaptive","display":"updates"}` | **400** |
| `{"type":"adaptive","display":"omitted"}` | 200 |
| `{"type":"adaptive","display":"zzz-not-a-real-value"}` (음성 대조군) | **400** |

**음성 대조군이 200 들을 증거로 만든다.** 엉터리 값이 똑같이 400 이므로 이 필드는 무시되는 것이 아니라
**검증되는** 것이고, 따라서 `summarized` 의 200 은 침묵이 아니라 확인이다.

**규칙 둘대로, 근거가 어땠는지도 적는다.** 이 항목의 근거는 **참이었다** — 값은 정말로 실측되지 않았고,
정말로 그중 하나가 요청에 실려 있었다. 규칙 셋의 심각도 쪽이 달랐다: 항목은 이것을 "옵트인한 배포만
400 을 맞는다" 로 **제한된 무게**라고 적었는데, 실제로는 그 배포가 맞는 것이 **100%** 다. `updates` 를
고른 배포는 어쩌다 400 을 맞는 것이 아니라 **모든 adaptive 요청이 400** 이다. 확률이 아니라 상수였다.

**출처도 함께 닫는다.** 틀린 이름은 `anthropic-thinking-traces.md` §8 F-7 에서 왔고, **같은 파일 `:165`
가 이미 `omitted` 를 기본값으로 적고 있었다** — 문서가 스스로 모순되어 있었다. 그 행에 정정을 붙였다.

**고친 방법 — `OMITTED` 로 개명하지 않고 상수를 뺐다.** `omitted` 은 실재하는 값이므로 기계적인 수정은
`UPDATES("updates")` → `OMITTED("omitted")` 였지만, 그렇게 하지 않았다. 이유 셋과 enum-대-boolean 재검토는
[`../design/llm/reasoning-delta-stream.md`](../design/llm/reasoning-delta-stream.md) §12.2 에 있다. 요지는
`omitted` 이 **부재와 동작이 같은 데다**, 이 키의 나머지 절반이 흘려보내기 게이트를 열기 때문에
(`AnthropicLlmClient` 가 `getThinkingDisplay().isPresent()` 를 `AnthropicStreamingMapper` 에 넘긴다)
`thinkingDisplay: omitted` 이 **"채널을 열고 아무것도 담지 말라"** 라는 자기모순 상태가 된다는 것이다.

**채널이 비어 있지 않다는 것도 확인했다.** `claude-opus-5` + `display: summarized` +
`output_config.effort = high` 에서 471자 + 서명이 붙은 thinking 블록이 왔다(`thinking_tokens: 472`).
다만 **쉬운 프롬프트는 effort `high` 에서도 thinking 블록이 0개**다 — adaptive 가 생각하지 않기로
정하기 때문이며, "텍스트가 온다" 를 단언하는 테스트는 그래서 깨질 수 있다.

**남는 것.** `AnthropicThinkingLiveTest` 는 그대로 있고, 여기서 확인되지 않은 것은 §11 O-2(budgeted 모양이
`display` 형제를 받는지)와 스트리밍 이벤트 모양이다 — 이번 프로브는 전부 비스트리밍이었다.

#### 그중 **스트리밍 이벤트 모양은 이후 실측되었다** (2026-09-10, #71)

같은 날 스트리밍 요청을 한 번 더 보냈다 — `claude-opus-5`, `thinking:{type:adaptive,display:summarized}`,
`output_config:{effort:high}`, `stream:true`. **HTTP 200**, `content_block_delta` 안에
**`thinking_delta` 34건**과 **`signature_delta` 1건**(`text_delta` 2건). 블록 순서는 `[thinking, text]`,
`output_tokens: 338` 중 `thinking_tokens: 332`.

`AnthropicStreamingMapper` 는 문자열이 아니라 SDK 술어(`delta.isThinking()` · `delta.isSignature()`)로
분기하므로 마지막 고리는 그 술어가 어느 와이어 타입을 뜻하느냐인데, `anthropic-java-core` 2.13.0 의
`ThinkingDelta` 와 `SignatureDelta` 가 각각 `thinking_delta` 와 `signature_delta` 를 들고 있다. 즉 이름이
맞고 채널이 비어 있지 않다. `AnthropicThinkingLiveTest.ReasoningDeltasArriveOnAStream` 이 같은 요청을
클라이언트로 보내 `REASONING_DELTA` 청크가 실제로 도착하는 것을 단언한다.

**O-2 는 그대로 열려 있다** — budgeted 모양에 `display` 형제를 붙여 본 요청은 여전히 없다. 그리고
`display: summarized` **없이** `claude-opus-5` 가 `thinking_delta` 를 보내는지도 스트리밍으로는 재확인하지
않았다. 같은 서버가 이미 비스트리밍으로 답한 것을 다른 경로에서 한 번 더 사는 값이 없다고 보았다.

## RD-2 — `reasoning.summary` 가 `include` 항목을 요구하는지 실측되지 않았다 · **닫힘 (2026-09-10, 실측)**

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

### 닫힘 (2026-09-10) — 실측했고, **코드가 맞았다. 바꾼 것은 없다**

**결과.** 실제 요청을 보냈다(`api.openai.com/v1/responses`, `gpt-5-mini`, `store: false`, 근거는 태스크
기록의 `MEASUREMENTS-OPENAI.md`). **`OpenAIResponsesRequestFactory` 는 그대로 옳고 코드는 한 줄도 바뀌지
않았다.**

| 요청 | HTTP | `summary` 파트 |
|---|---|---|
| 지금 그대로 — `include:["reasoning.encrypted_content"]` + `reasoning:{effort:"high",summary:"auto"}` | 200 | **5** |
| `include` 를 아예 안 보냄, `reasoning` 은 동일 | 200 | **5** |
| `include:["reasoning.summary"]` (음성 대조군) | **400** | — |
| 요약을 청하지 않음 — `include:["reasoning.encrypted_content"]` + `reasoning:{effort:"high"}` | 200 | **0** |

**이 항목의 제목이 말하던 것이 바로 뒤집혔다.** 400 의 본문이 유효한 `include` 값 **전체를 열거**하는데,
여덟 개 중 **요약에 해당하는 것이 없다.** 즉 SDK 가 모델링을 빠뜨린 것이 아니라 **와이어에도 없다.**

**규칙 둘대로 적는다 — 근거가 틀렸다.** 이 항목은 스스로를 *"근거가 부재의 추론이다"* 로 적었고, 그것이
이 항목이 열려 있던 이유였다. 추론은 **맞았고**, 이제 추론도 아니다 — 없는 enum 상수로부터 읽은 것이
아니라 **서버가 집합을 직접 열거했다.** 따라서 이것은 "확인해 보니 다행히 맞았다" 가 아니라 **근거의
종류가 바뀐 것**이다: 부재로부터의 읽기가 존재하는 400 본문으로 교체되었다.

**심각도(규칙 셋)도 다시 잰다.** 항목은 실패 모드를 **"조용한 빈 채널"** — 아무도 알아채지 못하는 종류 —
로 적었다. 그 서술은 옳았지만 **해당 사항이 없다.** 대신 마지막 행이 이 기능에 중요한 대조군이다:
**청하지 않으면 요약 파트가 0개**이므로 채널은 진짜로 opt-in 이고 진짜로 꺼져 있다.

**이 프로브가 덮지 않은 것** — 닫힘을 과독하지 않도록 함께 적는다.

- ~~**비스트리밍이다.**~~ **이 절반은 이후 실측되었다 (2026-09-10, #71)** — 아래.
- **`gpt-5-mini` 하나다.** 다른 모델은 보지 않았다. 아래의 스트리밍 요청도 같은 모델이다.

#### 스트리밍 이벤트 이름 — 실측 (2026-09-10, #71)

`POST /v1/responses`, `gpt-5-mini`, `reasoning:{effort:"high",summary:"auto"}`, `store:false`,
`stream:true`. **HTTP 200**, SSE `event:` 834줄 중 **`response.reasoning_summary_text.delta` 611건**
(`response.output_text.delta` 201건, `reasoning_summary_part.added`/`.done` 각 4건,
`reasoning_summary_text.done` 4건). 요약 텍스트는 611개 델타에 걸쳐 2095자,
`reasoning_tokens: 1280` / `output_tokens: 1502`.

`OpenAIResponsesStreamingMapper` 는 `event.isReasoningSummaryTextDelta()` 로 분기하고,
`openai-java-core` 4.57.0 의 `ResponseReasoningSummaryTextDeltaEvent` 가
`response.reasoning_summary_text.delta` 를 들고 있다 — 위에서 611번 센 그 이름이다.
`OpenAIReasoningLiveTest.ReasoningDeltasArriveOnAStream` 이 같은 요청을 클라이언트로 보내
`REASONING_DELTA` 청크 도착을 단언한다.

**`response.reasoning_text.delta` 는 여전히 실측되지 않았다.** 이 모델은 요약 계열만 보낸다. 매퍼가 두
계열을 한 게이트 아래 함께 흘려보내는 결정은 바로 그런 모델을 위한 것이므로 영향은 없지만, 두 번째 계열은
서버를 상대로 확인된 적이 없다.

**부수 관찰 하나 — 이것으로 아무것도 바꾸지 않는다.** `include` 를 아예 안 보낸 요청에서도
`encrypted_content` 가 돌아왔다. 팩토리의 javadoc 은 `store: false` 가 SDK 자신의 javadoc 이 콕 집어
말하는 경우라서 명시적으로 청한다고 적어 두었는데, 오늘 이 엔드포인트에서는 안 청해도 왔다. **그래도
`include` 는 그대로 두고 javadoc 도 약화하지 않았다** — 모델 하나에 대한 관측 하나는 벤더의 문서화된
계약보다 약하고, 그것을 뺐을 때 생기는 실패(reasoning item 이 턴을 넘어 조용히 왕복하지 못하는 것)는
조용한 쪽이다. 나중에 누군가 javadoc 을 읽고 한 번 테스트해 본 뒤 그 줄을 "간소화" 하지 않도록 여기
적어 둘 뿐이다.

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
(ii) 벤더 키 둘 **대신** 두면 boolean 이 `concise` · `detailed` 같은 결을 표현하지 못하고,
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
