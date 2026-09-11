# 추론 트레이스 (Reasoning Traces)

> Status: **IMPLEMENTED** — 코어의 `ReasoningTrace` 슬롯, assistant 메시지를 만드는 모든 자리의 부착, 전사 영속,
> OpenAI(Responses 경로)와 Anthropic 두 provider 의 캡처·재전송, `TokenUsage.reasoningTokens` 회계가 들어가 있다.
> 남은 것은 §11.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm`(`ReasoningTrace`, `Message`, `LlmResponse`, `TokenUsage`),
> `…llm.streaming`(`ChunkAggregator`), `…subagent.task.codec`(`JsonSessionSnapshotCodec`),
> `…agent.session.store`(`SessionRecordCodec`), `…agent.compact`(`MessageStripper`) ·
> `aimon-llm-openai` — `OpenAiReasoningTraces`, `OpenAIResponsesMessageConverter`, `OpenAIResponsesStreamingMapper` ·
> `aimon-llm-anthropic` — `AnthropicReasoningTraces`, `AnthropicOutputBlocks`, `AnthropicMessageConverter`,
> `AnthropicStreamingMapper`, `AnthropicUsages`

---

## 1. 문제와 범위

추론 모델은 도구 호출과 함께 provider 가 소유한 불투명 항목을 돌려준다. OpenAI 는 `encrypted_content` 를 담은
**reasoning item** 을, Anthropic 은 `signature` 가 붙은 `thinking` · `redacted_thinking` **블록**을 준다. 클라이언트가
그 항목을 다음 요청에 되싣지 않으면 모델은 ReAct iteration 마다 사고를 처음부터 다시 파생한다. 답은 나빠지고
추론 토큰은 매번 다시 과금된다. 이 손해는 AIMON 이 돌리는 도구 루프, 즉 iteration 이 여러 번 이어지는 곳에서 가장
크다.

이 문서가 푸는 것은 그 항목을 **도구 호출 너머로 운반하는 것**이다.

- 코어가 항목을 담는 슬롯(`ReasoningTrace`)과, 그 슬롯이 메시지 · 전사 · 컴팩션 · 레닥션 · 토큰 추정과 만나는 경계
- 두 provider 가 응답에서 슬롯을 채우고(캡처) 다음 요청에서 비우는(재전송) 규칙 — **두 provider 모두 슬롯을 채운다**
- payload 를 직렬화하는 규칙, 되실을 수 없는 트레이스를 버리고 알리는 규칙
- Anthropic 의 재전송 스위치 `replayThinkingBlocks`
- provider 가 보고하는 추론 토큰 수(`TokenUsage.reasoningTokens`)의 회계

이 문서가 정하지 않는 것:

| 주제 | 정본 |
|---|---|
| OpenAI 가 어떤 요청을 `/v1/responses` 로 보내는가, 그 경로의 요청 · 변환 · 오류, reasoning summary 요청 | [`openai-responses-path.md`](openai-responses-path.md) |
| Anthropic `thinking` 요청 파라미터 — 모드 · 방언 · budget · `display` | [`anthropic-thinking.md`](anthropic-thinking.md) |
| 사람이 보는 숙고 텍스트를 흘리는 **reasoning delta** 채널 — reasoning trace 가 아니다 | [`streaming.md`](streaming.md) |
| divergence 보고의 once 집합 / recurring 카운터 규칙 | [`request-parameters.md`](request-parameters.md) |
| 모델이 trace 왕복을 받는가(`supportsReasoningTraceRoundTrip`) | [`model-capabilities.md`](model-capabilities.md) |
| `replayThinkingBlocks` 의 설정 키와 네임스페이스 | [`configuration-surface.md`](configuration-surface.md) |
| 컴팩션 전반 | [`../agent-execution/compaction.md`](../agent-execution/compaction.md) |

---

## 2. `ReasoningTrace` — 불투명하고 영속되는 새 슬롯

### 2.1 모양

`Message` 와 `LlmResponse` 는 순서 있는 `List<ReasoningTrace>` 를 가진다. 스트리밍 경로에서는 `ChunkAggregator` 가
트레이스를 모아 `LlmResponse` 에 싣는다. `ReasoningTrace` 는 불변 클래스이고 필드는 셋이다.

| 필드 | 제약 | 뜻 |
|---|---|---|
| `providerName` | 필수, 공백 불가 | 이 payload 를 쓴 provider. `LlmClient.getProviderName()` 값 |
| `payload` | 필수, 공백 불가 | provider 가 소유한 불투명 문자열 |
| `toolUseId` | 선택 (`Optional`) | 앵커 — provider 의 출력 순서에서 이 트레이스가 **바로 앞에 선** tool use 의 id. 비어 있으면 assistant 텍스트나 메시지의 끝 앞에 선다 |

각 필드가 있어야 하는 이유는 이렇다.

- **`payload` 는 `String` 이다.** `byte[]` 나 `Map` 이 아니다. 이 저장소가 가진 모든 와이어를 그대로 통과하고,
  전사를 읽는 운영자가 들여다볼 수 있으며, 인코딩을 provider 가 책임지게 만든다. 코어가 payload 에 하는 연산은
  복사 하나뿐이다. OpenAI 의 `encrypted_content` 는 암호문이고 Anthropic 의 `signature` 는 서명이라, 바이트 하나만
  바뀌어도 무효가 된다. 코어가 내용을 읽지 않는 것이 그 성질을 지키는 유일한 방법이다. `toString()` 도 payload 가
  아니라 길이만 찍는다
- **`providerName` 은 전사가 클라이언트보다 오래 살기 때문에 있다.** `LlmFallbackPolicy` 가 실행 도중 세션을 다른
  모델로 옮길 수 있고, 운영자가 provider 를 바꾸고 재개할 수 있으며, 서브에이전트 스냅샷은 어디서든 재생될 수 있다.
  Anthropic thinking 블록을 `/v1/responses` 에 보내면 잘해야 400 이다. 그래서 클라이언트는 자기가 쓴 트레이스만
  되싣고 나머지는 버린다(§6)
- **`toolUseId` 는 provider 누수가 아니다.** `MessageArtifact.getToolUseId()` 가 이미 "이것이 속한 tool use" 를
  뜻한다. 여기서는 순서를 뜻한다(§2.3)

`thinking` 과 `redacted_thinking` 을 구분하는 필드는 두지 않는다. 블록의 JSON 이 `"type"` 을 스스로 싣고,
Anthropic SDK 의 파싱 대상이 그 값으로 분기하기 때문이다(§5.2).

### 2.2 기존 사이드카를 쓰지 않고 새 슬롯을 둔다

**`MessageArtifact` 는 추론 payload 를 담을 수 없다.**

- 필수 필드 `path` · `fileName` 이 이 내용에 대해 거짓말이 된다. 추론 항목에는 파일 경로도 파일 이름도 없고,
  검증을 통과시키려고 `"/dev/null"` 을 넣는 순간 그 타입은 뜻을 잃는다
- 이미 파일 참조로 소비된다. `ArtifactMetadata` 를 구현하고 `downloadToken` 을 들며, CLI 와 세션 계층이 사람에게
  내려받을 파일로 보여 준다. 추론 blob 이 그 리스트에 들어가면 사용자에게 파일로 제시된다
- `size` 는 `>= 0` 불변식을 가진 바이트 수인데 넣을 값이 없다

**`ToolUseResult.getRenderPayload()` 는 반대 보장을 가진다.** 불투명하고 provider 모양인 사이드카라는 점은 같지만,
스냅샷 코덱 계약이 그것을 **영속하지 않는다**고 명시한다. 추론 트레이스에 필요한 것은 불투명하면서 **영속되는**
슬롯이다. 그래서 재사용이 아니라 새 슬롯이다.

### 2.3 순서만으로는 부족하다 — 앵커

출력이 `[reasoning, call_1, reasoning, call_2]` 인 응답은 각 reasoning 항목을 자기가 만든 호출 앞에 두고 되실어야
한다. `Message` 는 이 순서를 표현할 수 없다. 텍스트는 `contentBlocks` 에, 호출은 `toolUses` 에 있는 두 리스트이기
때문이다. 그래서 순서는 앵커가 운반하고, 앵커로부터 와이어 순서를 복원하는 규칙은 provider 가 가진다(§4).

앵커는 단위 테스트로는 발견할 수 없는 서버 규칙에 대한 방어다. 대가는 nullable 필드 하나다. 앵커가 없으면
도구 호출이 여러 개인 응답에서 400 이 나고, 어떤 단위 테스트도 그것을 찾지 못한다. 앵커가 실제로 필요한지는
측정되지 않았다(§11).

---

## 3. 코어 경계 — 부착 · 영속 · 컴팩션 · 레닥션 · 토큰 추정

### 3.1 부착 — `LlmResponse` 로 assistant 메시지를 만드는 모든 자리가 붙인다

`LlmResponse` 로 assistant `Message` 를 만드는 모든 자리는 그 응답의 트레이스를 `withReasoningTraces` 로 붙인다.
**그 메시지가 다시 읽히는지를 자리마다 판단하지 않는다.** 예외가 있는 규칙은 검색 한 번으로 지켜졌는지 확인할 수
없기 때문이다. 그래서 곧 버려지는 로컬 버퍼에 쓰는 자리도 붙인다.

부착은 provider 가 아니라 **실행기의 책임**이다. `OrcaAgentExecutor`, `DefaultSubagentExecutor`,
`LlmSkillExecutor`, `ReActLlmDeriver` — 트리의 ReAct 루프 넷이 이 규칙을 따른다. provider 가 슬롯을 채워도 루프가
붙이지 않으면 기능은 조용히 아무것도 하지 않는다.

assistant 메시지를 만들지만 이 규칙의 대상이 **아닌** 자리와 그 이유:

| 자리 | 붙이지 않는 이유 |
|---|---|
| `OrcaAgentExecutor` 의 슬래시 명령 경로 | 텍스트가 명령의 출력이다. `LlmResponse` 가 없다 |
| `OrcaAgentExecutor` 의 스트림 중간 취소 경로 | 호출이 중단되어 응답이 집계되지 않았으므로 붙일 트레이스가 없다. 보존되는 텍스트 앞부분에는 도구 호출도 없고, 반쯤 흘러온 reasoning 항목에는 완성된 `encrypted_content` 가 없다 |
| `MessageStripper` 의 재구성 | 트레이스를 **의도적으로 버린다** — §3.3 |
| `DefaultCompactionEngine` 의 요약 메시지 | `CompactBoundary.summaryMessage(...)` 는 **user** 메시지다. 이력에 **관한** 새 메시지이지 어떤 응답이 된 메시지가 아니다 |
| `TranscriptBuffer.addAssistantMessage` | `String` 을 받는 편의 메서드다 |
| `LlmClient.sendMessageStreaming` 의 기본 구현 | `sendMessage` 가 만든 그 `LlmResponse` 를 돌려주므로 트레이스가 그대로 지나간다 |

### 3.2 영속 — 코덱의 `reasoning` 배열, `FORMAT_VERSION` 유지, 양방향 관용

트레이스는 `Message` → `JsonSessionSnapshotCodec` → `SessionRecordCodec.encodeTranscript` → 백엔드의 전사 컬럼으로
간다. `Message` 를 넓히면 저장되는 와이어 포맷이 넓어진다. [`frozen-names.md`](../../migration/frozen-names.md) 가
지키는 경계는 **저장된 이름을 바꾸는 것**이고, 선택 필드를 더하는 것은 그 목록에 없다. 대신 더하는 쪽이 양방향으로
실제로 관용적이어야 한다.

| 방향 | 성립하는 이유 |
|---|---|
| 옛 reader, 새 문서 | 메시지 디코더는 아는 필드 이름만 명시적으로 읽고 나머지는 무시한다. 모르는 `reasoning` 배열은 건너뛴다 |
| 새 reader, 옛 문서 | 키가 없으면 빈 리스트다. 트레이스가 없던 때와 같은 메시지가 복원된다 |

- **배열은 비어 있지 않을 때만 쓴다.** `toolUses` · `artifacts` 와 같은 방식이다. 그래서 트레이스가 없는 메시지의
  문서는 이 필드가 생기기 전과 바이트 단위로 같다
- **`FORMAT_VERSION` 은 1 로 둔다.** 올리면 저장된 모든 스냅샷이 디코딩 불가가 되는데, 위 두 방향이 이미
  관용적이므로 얻는 것이 없다
- **세 `SessionRecordCodec` 백엔드는 바뀌지 않는다.** `encodeTranscript` 는 백엔드에 불투명 문자열을 넘기고(BSON 은
  필드 이름에 `.`·`$` 를, `jsonb` 는 U+0000 을 금지한다) 모든 백엔드가 그것을 텍스트 컬럼에 둔다. 백엔드는 새 필드를
  볼 수 없으므로 잘못 다룰 수도 없다

관용의 범위는 **필드의 유무**다. 배열 원소가 JSON 객체가 아니거나 `providerName` · `payload` 가 없으면 코덱은
스냅샷 디코딩 오류로 던진다. 반면 원소는 온전한데 payload 를 provider 가 해석하지 못하는 경우는 재전송 시점에
그 트레이스만 버린다(§6).

### 3.3 컴팩션 — 트레이스는 버리고 도구 호출은 남긴다

`MessageStripper` 는 assistant 메시지를 재구성하면서 **도구 호출은 남기고 트레이스는 전부 버린다.** 이 비대칭이
안전한 방향이다.

- 트레이스를 버려도 호출은 남으므로, 뒤따르는 호출을 잃은 reasoning 항목이 생기지 않는다. OpenAI 가 거절한다고
  문서화한 모양이 그것이다. Anthropic 쪽에서도 한 메시지의 트레이스를 전부 버리므로 일부만 빠진 thinking 연속열이
  생기지 않는다
- 반대 방향 — 트레이스를 남기고 호출을 버리는 것 — 은 그 모양을 만든다(§10)

identity fast path 도 `hasReasoningTraces()` 를 확인한다. 이것이 없으면 텍스트만 있는 assistant 메시지가 fast path
를 타고, 그 응답의 텍스트보다 훨씬 큰 payload 를 요약 LLM 호출에 싣는다. 트레이스를 버리는 일은 다른 재작성의 부수 효과가
아니라 무조건이어야 "컴팩션이 긴 추론 세션의 크기를 묶는다" 가 참이 된다. 컴팩션 전반은
[`../agent-execution/compaction.md`](../agent-execution/compaction.md) 가 정본이다.

### 3.4 레닥션 — payload 는 게이트 밖이다

`Message.mapText` 는 메시지 전체 텍스트를 다시 쓰는 단일 진입점이고, 추론 payload 는 **의도적으로 그 밖에** 있다.
그래서 레닥션도 payload 에 닿지 않는다. 텍스트를 매핑하는 것으로는 고칠 수 없다. OpenAI 의 운반체는 바이트 하나만
바뀌어도 무효가 되는 암호문이고, Anthropic 의 `signature` 도 같은 성질을 가진다.

대신 두 가지를 둔다.

- **공시** — `Message.mapText` 와 `ReasoningTrace` 의 javadoc 이 이 사실을 적는다. 프로세스 밖으로 나가는 것은
  provider 자신이 만들어 이미 가진 payload 라는 점도 함께 적는다
- **끄는 스위치** — OpenAI 는 `OpenAIConfig.responsesApiEnabled(false)` 다. Chat Completions 는 reasoning 항목을
  돌려주지 않으므로 캡처할 것 자체가 없다. Anthropic 의 `replayThinkingBlocks(false)` 는 재전송만 끈다. 캡처는
  무조건이므로(§7) 트레이스는 전사에 계속 남는다

모든 반출에 강한 레닥션 요구가 있는 배포라면, 트레이스를 **다시 쓰는** 정책이 아니라 **만들지 않는** 정책이
필요하다. 그것은 별도 설계다(§11).

### 3.5 토큰 추정 — 트레이스를 0 으로 센다

`HeuristicTokenEstimator` 와 `TikTokenEstimator` 는 콘텐츠 블록 · 도구 호출 · 도구 결과를 세고 트레이스는 세지 않는다.
그런데 `DefaultCompactionGuard` 는 모든 임계값을 provider usage 가 아니라 추정기로 판단한다. 따라서 추론 세션에서
가드는 대략 추론 토큰만큼 과소 추정한다.

**의도적으로 고치지 않는다.** 뻔한 수정이 틀렸다. blob 을 텍스트로 세면 실제 입력 비용보다 한 자릿수 크게 과대
추정하고, 조기 컴팩션을 강제해 바로 그 트레이스를 파괴한다. 피해 반경은 묶여 있다. 컴팩션이 트레이스를 버리므로
오차가 초기화되고, 컨텍스트 한도는 이미 추정 오차를 위한 여유를 두며, 같은 추정기가 이미 도구 **정의**라는 더 크고
똑같이 세지 않는 항을 빼고 센다. context-length 400 을 조사하는 사람을 위해 이 문장을 남긴다.

### 3.6 전사 증가 — 운영 비용으로 명시한다

`encrypted_content` blob 은 응답 텍스트보다 훨씬 크고, 추론 세션의 assistant 메시지마다 하나씩 붙는다. 이전 요청의 thinking
을 컨텍스트에 유지하는 Anthropic 모델에서는 그 블록이 입력으로 다시 과금된다. 이것을 없애도록 설계하지 않는다.
payload 를 자르거나 상한을 두면 왕복이 깨지는데, 왕복이 이 기능 전체다. 컴팩션이 트레이스를 버려 긴 세션을 묶고,
두 스위치(§3.4)가 트레이스를 아예 없앤다. 실제 증가량은 측정되지 않았다(§11).

---

## 4. provider 규칙 — 모양 하나, provider 마다 재구성 규칙 하나

코어는 슬롯 하나만 정한다. 앵커로부터 와이어 순서를 복원하는 규칙은 provider 마다 하나씩 provider 안에 있다.
두 provider 의 규칙은 **재전송 쪽은 같고 캡처 쪽에서 갈린다.**

### 4.1 재전송 규칙 — 두 provider 가 같다

`OpenAIResponsesMessageConverter` 와 `AnthropicMessageConverter` 는 assistant 메시지 하나를 이렇게 내보낸다.

```text
assistant 메시지 하나에 대해:
  1. 앵커 없는 트레이스 전부, 저장 순서대로
  2. assistant 텍스트 (텍스트가 있을 때만)
  3. 각 tool use 마다 순서대로: 그 tool use 에 앵커된 트레이스(저장 순서) → 그 tool use
  4. 남은 것: 이 메시지에 없는 tool use 에 앵커된 트레이스는 내보내지 않고 보고한다 (§6)
```

저장된 리스트를 순서대로 걷기 때문에 이 규칙은 트레이스의 순서를 바꿀 수 없다. payload 를 불투명하게 두기 때문에
내용을 바꿀 수도 없다. Anthropic 쪽에서 규칙이 요구하는 모양 두 가지:

- 트레이스는 있고 tool use 는 없는 메시지도 문자열 fast path 가 아니라 블록 리스트로 내보낸다. 트레이스도 tool use 도
  없는 메시지만 이전과 같은 문자열 모양을 탄다
- thinking 만 있는 assistant 메시지는 빈 text 블록을 내지 않는다. API 가 빈 text 블록을 거절한다. 트레이스가 전부
  버려지고 텍스트도 tool use 도 없으면 빈 블록 리스트는 합법 메시지가 아니므로 문자열 모양으로 돌아간다

### 4.2 캡처 규칙 — 여기서 갈린다

**OpenAI:** 출력 배열을 순서대로 걸으며, 각 reasoning 항목은 **뒤따르는 첫 `function_call`** 에 앵커한다. 뒤따르는
호출이 없으면 앵커하지 않는다. `[r1, call_1, r2, call_2]` 는 정확히 왕복하고, `[r1, msg]` 와 `[r1, r2, call_1]` 도
올바르게 수렴한다. 앵커 값은 `call_id` 다(`call_id` 와 item `id` 의 구분은
[`openai-responses-path.md`](openai-responses-path.md)).

**Anthropic:** 응답 content 를 순서대로 걸으며, thinking 블록은 뒤따르는 첫 `tool_use` 에 앵커한다. **다만 그 사이에
`text` 블록이 먼저 오면 앵커하지 않는다.** 앵커 값은 `ToolUseBlock.id()` 다.

갈리는 이유는 Anthropic 이 흔한 모양 `[thinking, text, tool_use]` 에서 thinking 과 호출 사이에 텍스트를 둔다는 데 있다.
OpenAI 규칙을 그대로 쓰면 thinking 이 호출에 앵커되고, §4.1 의 재전송 규칙이 그것을 `[text, thinking, tool_use]` 로
재구성한다. 그러면 메시지가 thinking 블록으로 시작하지 않고, 벤더의 연속 thinking 블록 검사는 재배열을 본다.
Anthropic 이 문서화한 왕복 계약은 이렇다.

- 도구 호출이 걸린 assistant 메시지 안에서는 thinking 블록을 완전하고 수정 없는 상태로, 그것이 딸린 `tool_use` 와 함께
  되싣는다
- 최신 assistant 메시지의 연속 thinking 블록은 원래 생성된 순서와 같아야 한다. 재배열 · 편집 · 일부 삭제가 불가하고
  `redacted_thinking` 도 여기에 포함된다
- thinking 블록은 자신이 도입하는 `tool_use` 바로 앞에 선다
- 오래된 thinking 블록은 서버가 자동으로 걸러내므로 클라이언트가 가지치기할 필요가 없다

두 규칙의 짝은 텍스트 블록이 하나인 모든 모양에서 provider 의 순서를 그대로 재현한다.

| 응답 content | 앵커 | 재전송 |
|---|---|---|
| `[think, text, tool_use]` | think → 없음 | 같음 |
| `[think, tool_use]` | think → tu | 같음 |
| `[think1, think2, tool_use]` | 둘 다 → tu | 같음 |
| `[think1, tu1, think2, tu2]` | t1 → tu1, t2 → tu2 | 같음 |
| `[think, text, tu1, think2, tu2]` | t1 → 없음, t2 → tu2 | 같음 |
| `[text, think, tool_use]` | think → tu | 같음 |

**순서가 바뀌는 모양은 셋이다.**

- thinking 이 **둘 이상의** text 블록과 교차하는 모양, 그리고 text 가 `tool_use` **뒤에** 오는 모양. `Message` 가 모든
  텍스트를 한 문자열로 합치므로 어떤 재전송 규칙도 여러 텍스트 블록을 재현할 수 없다. 이 규칙이 만든 손실이 아니다.
  첫째가 더 일어나기 쉽다(adaptive 모드의 진행 업데이트)
- `[text, think]` — 뒤에 tool use 가 없는 꼬리 thinking 블록이 `[think, text]` 로 되실린다. 빈 `toolUseId` 가 "메시지
  선두" 와 "메시지 끝" 을 한 값으로 표현하고, 재전송 규칙은 선두에만 둘 수 있기 때문이다. 둘을 가르려면
  `ReasoningTrace` 에 두 번째 앵커 값이 필요하다. 문서화된 어떤 Anthropic 응답도 만들지 않는 모양을 위한 코어
  변경이라 하지 않는다 — thinking 은 답을 앞서지 뒤따르지 않는다

캡처는 `thinking` 과 `redacted_thinking` 을 **둘 다** 받는다. 타입이 `thinking` 인 블록만 거르면 `redacted_thinking` 이
조용히 빠지고, 벤더는 그것을 여러 요청에 걸친 왕복 프로토콜을 깨는 전형적 원인으로 명시한다.

캡처는 응답이 담은 블록 종류 중 `thinking` · `text` · `tool_use` 만 본다. `server_tool_use` 같은 서버 측 도구 블록은
기록하지 않으므로 앵커를 끊는 경계로 작동하지 않는다. AIMON 은 서버 측 도구를 요청하지 않아 오늘은 도달하지 않는다.

### 4.3 블로킹과 스트리밍은 같은 해석 함수를 쓴다

반드시 일치해야 하는 순서 규칙을 두 곳에 구현하면 두 경로가 어긋나 한쪽에서 왕복을 잃는다. 그래서 provider 마다
캡처 규칙은 한 곳에만 있다.

| provider | 해석 함수 | 블로킹 입력 | 스트리밍 입력 |
|---|---|---|---|
| OpenAI | `OpenAIResponsesMessageConverter` 의 출력 스캔 | 응답의 `output` 배열 | `OpenAIResponsesStreamingMapper` 가 모은 완결 항목 |
| Anthropic | `AnthropicOutputBlocks.resolve` | `Message.content()` | `AnthropicStreamingMapper` 가 관측한 블록 순서 |

`AnthropicOutputBlocks` 는 SDK 타입을 시그니처에 두지 않는 순수 함수다. 블록을 직렬화하는 일(SDK 타입)은
`AnthropicReasoningTraces.payloadOf` 가, 트레이스와 앵커를 만드는 일은 `AnthropicOutputBlocks` 가 나눠 맡는다.

스트리밍에서 provider 별로 지켜야 하는 것:

- **OpenAI** — 트레이스는 `response.output_item.done` 에서 취한다(이유는
  [`openai-responses-path.md`](openai-responses-path.md)). reasoning 항목은 나왔는데 어느 것도 `encrypted_content` 를
  싣지 않았다면 되실을 것이 없으므로 한 번 경고한다. 기능이 조용히 아무것도 하지 않는 것이 이 경로가 고치려던
  실패이기 때문이다. 블로킹 경로도 같은 경고를 낸다
- **Anthropic** — thinking 블록은 `thinking_delta` 들 뒤에 `content_block_stop` 직전의 `signature_delta` 하나로 끝난다.
  `display` 가 텍스트를 생략하는 모델에서는 `thinking_delta` 가 하나도 없이 되실어야 할 블록이 온다. **핵심은 텍스트가
  아니라 signature 다.**
  - thinking 슬롯은 `content_block_start` 가 실은 블록을 씨앗으로 두고, 프로토콜이 다시 흘려주는 두 필드(`thinking` ·
    `signature`)만 채운다. 서버가 더한 모르는 필드는 씨앗에 남는다
  - `signature_delta` 를 받지 못한 슬롯은 되싣지 않고 버리며 보고한다. 서명 없는 thinking 블록은 되실으면 확정 400 이고,
    버린 블록은 추론 재파생만 부른다
  - `redacted_thinking` 블록은 `content_block_start` 에 완결된 채 온다고 가정한다. SDK 의 delta 합집합에 redacted 변형이
    없다는 것이 근거이고 관측은 아니다(§11). 가정이 틀리면 스트리밍 경로가 그 트레이스를 버린다 — 호출이 실패하지는
    않는다
  - 모은 트레이스는 `STREAM_END` chunk 를 만들기 **전에** aggregator 로 넘긴다. `ChunkAggregator.addReasoningTrace` 는
    닫힌 뒤 `IllegalStateException` 을 던지고, `toLlmResponse()` 는 클라이언트의 try 밖에서 불리므로 그 예외는 매핑되지
    않고 샌다

두 provider 모두 스트리밍으로 흘러오는 숙고 텍스트를 reasoning delta 로 전달할 수 있다. 그것은 사람이 보는 텍스트이지
되실을 payload 가 아니다. reasoning delta 나 reasoning summary 는 트레이스에 들어가지 않는다. summary 는
`encrypted_content` 를 대신할 수 없다. 채널의 정본은 [`streaming.md`](streaming.md) 다.

### 4.4 provider 이름은 요청마다 한 번 해석한다

두 클라이언트는 공개 · 비 final 클래스이고 `getProviderName()` 은 재정의할 수 있다. 이름을 두 곳에서 다른 시점에
읽으면, 서브클래스가 트레이스를 한 이름으로 붙이고 다른 이름과 비교해 **자기 트레이스를 전부 foreign 으로 버린다.**
컴파일되고 테스트는 초록이며 기능은 아무것도 하지 않는다.

그래서 요청 진입점에서 `getProviderName()` 을 한 번 읽어 재전송 쪽(요청 조립과 메시지 변환)과 캡처 쪽(응답 변환,
스트리밍 매퍼)에 같은 값을 넘긴다. 모델 이름을 요청마다 한 번 해석하는 것과 같은 방식이다.

---

## 5. payload 직렬화 — SDK 매퍼로, 재구성하지 않고

### 5.1 SDK 자신의 매퍼만 쓴다

payload 를 쓰고 읽는 곳은 provider 마다 한 클래스뿐이다 — `OpenAiReasoningTraces`, `AnthropicReasoningTraces`. 둘 다
SDK 의 `ObjectMappers.jsonMapper()` 를 쓴다. 이것은 문체가 아니라 필수다.

모든 SDK 모델은 `@JsonAnySetter` / `@JsonAnyGetter` 쌍을 가지므로 SDK 매퍼는 **이 SDK 버전이 모르는 필드까지** 손실 없이
왕복시킨다. 한 빌드가 저장한 payload 를 서버가 필드를 더한 뒤 다른 빌드가 되실을 수 있는 것이 그 덕분이다. 새
`ObjectMapper` 는 같은 바이트를 파싱한 뒤 필드를 지어내서 다시 쓴다(`isValid()` 에서 `"valid":true`, SDK 의
`NON_ABSENT` 포함 설정이 없어 `Optional` 접근자에서 `"content":null`). 결과는 손상된 항목이다. Anthropic 에서는 서버가
그것을 *"blocks in the latest assistant message cannot be modified"* 로 거절하므로 기능이 약해지는 것이 아니라 호출이
깨진다. 두 provider 의 메시지 변환기는 이미 일반 `ObjectMapper` 를 들고 있어서 여기서 잘못된 매퍼를 쓰는 것이 가장
뻔한 실수다.

### 5.2 저장된 payload 를 빌더로 재구성하지 않는다

- **OpenAI** — 저장된 payload 를 `ResponseReasoningItem.builder()` 로 다시 만들지 않는다. 그 빌더는 `id` 와 `summary` 를
  요구하지만 `@JsonCreator` 생성자는 요구하지 않는다(`JsonField` 가 missing 을 기본값으로 가진다). 역직렬화는
  `checkRequired` 를 거치지 않는다. 재구성하면 **서버 자신이 만든** payload 에 검증 실패를 들여와, "트레이스 하나를
  버린다" 가 검증할 이유가 없는 모양에 대한 예외로 바뀐다
- **Anthropic** — 저장된 payload 는 합집합 타입 `ContentBlockParam` 으로 파싱한다. 역직렬화기가 `type` 판별자로
  `ThinkingBlockParam` · `RedactedThinkingBlockParam` 에 분기하고, 모르는 타입은 원문 JSON 으로 받아 그대로 다시 쓴다.
  파싱 대상 하나가 두 종류와 **나중에 생길 블록 타입**까지 받는다. `@JsonCreator` 경로라 `checkRequired` 에도 닿지
  않는다. 스트리밍 경로는 재구성을 피할 수 없는 유일한 자리이고, §4.3 대로 두 필드로 줄인다
- **Anthropic payload 는 JSON 객체여야 한다.** 합집합의 역직렬화기는 무엇이든 받고 모르는 것을 원문으로 두므로,
  손상된 `[1,2,3]` 도 파싱되어 배열 그대로 와이어로 나간다. 콘텐츠 블록은 언제나 JSON 객체이므로 객체가 아닌 payload
  는 파싱 불가로 버린다. 모르는 **객체** 타입은 계속 왕복한다

payload 의 모양:

| provider | payload | `toolUseId` |
|---|---|---|
| OpenAI | reasoning item 자체의 JSON | 앵커한 `function_call` 의 `call_id` |
| Anthropic `thinking` | `{"signature": …, "thinking": …, "type": "thinking"}` | `ToolUseBlock.id()` |
| Anthropic `redacted_thinking` | `{"data": …, "type": "redacted_thinking"}` | `ToolUseBlock.id()` |

`providerName` 은 `"OpenAI"` / `"Anthropic"`, 또는 서브클래스가 보고하는 값이다.

### 5.3 측정된 결론

| 무엇 | 결과 | 측정 |
|---|---|---|
| Anthropic — 이 클라이언트가 파싱해 SDK 매퍼로 재직렬화한 thinking 블록을 그 `tool_use` 옆에 되싣기 | 수용 | `claude-haiku-4-5`, `AnthropicThinkingLiveTest` |
| 같은 블록의 `signature` 한 글자를 바꿔 되싣기(음성 대조) | 거절 — ``Invalid `signature` in `thinking` block`` | 같음 |

두 행은 짝일 때만 증거다. thinking 블록을 뺀 요청도 수용되므로(§7), 수용 하나만으로는 블록을 조용히 버린 클라이언트와
구분되지 않는다. 증명된 것은 **signature 의 바이트 동일성**이지 문서 전체가 아니다. 서버의 검증기는 디코딩된 값을
읽으므로 키 순서나 문자열 이스케이프까지 같을 필요는 없다. OpenAI 쪽 reasoning 항목 왕복과 그 손상 대조군은
[`openai-responses-path.md`](openai-responses-path.md) 의 측정 결론에 있다.

---

## 6. 드롭과 보고 — 트레이스 하나를 잃는 편이 호출을 잃는 것보다 싸다

재전송 시점에 되실을 수 없는 트레이스는 **버리고, 보고하고, 요청은 그 트레이스 없이 보낸다.** 버린 트레이스의 대가는
추론 재파생이고, 던진 예외의 대가는 그 LLM 호출과 그 호출을 부른 실행이다. 그래서 트레이스 디코딩은 절대 다시 던지지
않는다.

| 조건 | 무엇이 일어나는가 | 보고 서명 |
|---|---|---|
| **foreign** — 다른 provider 가 쓴 트레이스(fallback 정책, provider 전환 후 재개, 다른 곳에서 재생된 서브에이전트 스냅샷) | 버린다 | `foreignReasoningTrace@<트레이스를 쓴 provider>` |
| **unparseable** — 이 빌드가 해석하지 못하는 payload(더 새 빌드가 쓴 전사, 손상된 행, Anthropic 에서는 객체가 아닌 payload) | 버린다 | `unparseableReasoningTrace@<provider>` |
| **orphaned** — 이 메시지에 없는 tool use 에 앵커된 트레이스 | 버린다(앞에 둘 항목이 없다) | `orphanedReasoningTrace@…` |
| **unsigned** (Anthropic 스트리밍) — `signature_delta` 없이 끝난 thinking 블록 | 캡처하지 않는다 | `unsignedThinkingBlock@<provider>` |
| **encrypted_content 없음** (OpenAI) — reasoning 항목은 왔지만 되실을 내용이 없다 | 캡처할 것이 없다 | `reasoningWithoutEncryptedContent@<provider>` |

orphaned 는 트리 안의 코드로는 도달하지 않는다. `MessageStripper` 는 호출을 남기고 트레이스를 버리며,
`DefaultCompactionEngine` 은 살아남은 메시지를 통째로 운반한다. 손으로 `Message` 를 조립한 호출자만 닿는다. 그래도
보고하는 것은 조용한 누락이 바깥에서 진단할 수 없는 유일한 모양이기 때문이다.

**보고 기록부는 provider 마다 다르다.** Anthropic 클라이언트는 이 조건들을 recurring 카운터(1 · 10 · 100 … 번째)로
보고한다. 이 조건들은 설정 사실이 아니라 트래픽 사실이어서 프로세스 중간에 시작될 수 있고, 서명당 한 번만 말하면 첫
발생 뒤 기능이 꺼진 채 침묵하기 때문이다. OpenAI 클라이언트는 같은 조건을 once 집합으로 보고한다 — 서명마다 첫 발생만
남는다. 두 기록부의 규칙은 [`request-parameters.md`](request-parameters.md) 가 정본이다.

### 6.1 호출을 잃는 유일한 자리 — 캡처 쪽 직렬화 실패

캡처할 때 SDK 가 **자기 모델**을 직렬화하지 못하면 `OpenAiReasoningTraces.toTrace` 와
`AnthropicReasoningTraces.payloadOf` 가 `IllegalStateException` 을 던진다. 그 예외는 클라이언트의 catch-all 에 닿아
호출이 실패한다. "트레이스 손실이 호출 손실보다 싸다" 는 규칙이 뒤집히는 유일한 자리이고, 알고도 그대로 둔다.

| 가능한 동작 | 결과 |
|---|---|
| 던진다 (현재) | 호출 하나를 크게 잃는다 |
| 그 블록 하나만 버린다 | Anthropic 에서 일부만 빠진 thinking 연속열 — 다음 요청이 400 |
| 그 응답의 트레이스를 전부 버린다 | 원칙상 옳다 |

셋째가 옳지만, 트리거가 "Jackson 이 SDK 매퍼로 SDK 모델을 직렬화하지 못함" 이라 주입할 이음매가 없다. 그 분기는
테스트 없이 출하되고, 도달 불가 경로의 테스트 없는 catch-all 은 나중에 진짜 버그를 삼키는 자리가 된다.

---

## 7. `replayThinkingBlocks` — 캡처는 무조건, 재전송만 설정한다

Anthropic 쪽 세 절반은 게이트가 다르다.

| 절반 | 게이트 |
|---|---|
| 응답의 `thinking` / `redacted_thinking` 블록을 `ReasoningTrace` 로 캡처 | **없음** — 언제나 |
| 저장된 트레이스를 assistant 메시지로 재전송 | `AnthropicConfig.replayThinkingBlocks` (기본 `true`) |
| `thinking` 요청 파라미터 전송 | `AnthropicConfig.thinkingMode` — [`anthropic-thinking.md`](anthropic-thinking.md) |

**캡처는 설정에 걸지 않는다.** 최신 Anthropic 모델 여럿은 설정 없이 thinking 이 켜진다. 읽기 경로를 설정 플래그에 걸면
기능이 가장 필요한 모델에서 꺼지고, 작성자가 쓰는 모든 테스트는 초록으로 남는다.

**재전송 스위치가 있는 이유**는 재전송이 새로운 오류 부류를 도달 가능하게 만들기 때문이다. 벤더의 preserved-thinking
prefix 검사에서 thinking 블록은 최상위 `system` 프롬프트 · `tools` · 그 앞의 메시지가 바뀌지 않은 동안만 유효하다.
Claude Fable 5.1 이후 모델에서, 2026-08-31 이후 생성된 계정에는 기본으로 강제된다. AIMON 은 iteration 마다 시스템
프롬프트를 다시 렌더링하고(메모리 주입, 스킬 훅) 클라이언트 쪽에서 컴팩션하므로, 둘 다 prefix 편집이다. 오류는
``Invalid `signature` in `thinking` block. The block is bound to a different conversation.`` 이다. 트레이스를 버리면
무효가 될 것이 없고, 되실으면 이 오류에 닿을 수 있다. 스위치를 끄는 것은 벤더가 문서화한 해법 — 이력에서 `thinking`
과 `redacted_thinking` 블록을 전부 뺀다 — 그대로다. 전사 증가(§3.6)를 없애는 수단이기도 하다. OpenAI 쪽의 같은 탈출구는
`responsesApiEnabled(false)` 다.

**기본값을 `true` 로 둔다.** 설정 없이 기능이 동작하게 하는 값이고, 위험은 좁다 — Fable 5.1 이후 모델, 2026-08-31
이후 생성된 계정, 실제로 바뀐 prefix 가 모두 겹쳐야 하며, 그때의 오류는 해법을 가리키는 문장이다. `false` 의 대가는
아무도 켜지 않는 기능이다.

**스위치는 클라이언트가 적용한다.** 클라이언트가 변환 전에 `AnthropicMessageConverter.withoutReasoningTraces` 로
트레이스를 벗긴다. 변환기는 "받은 것을 되싣는다" 한 규칙만 갖고, `false` 인 요청의 body 는 트레이스가 없던 때와 같다.
provider 이름을 받지 않는 1인자 `convertMessages` 는 `@Deprecated` 로 남은 공개 API 이고, 같은 helper 를 거쳐
**재전송하지 않는다** — 비교할 provider 이름이 없기 때문이다.

### 7.1 thinking 을 켠 채 재전송을 끄면 — 400 이 아니라 비용이다

벤더 문서는 extended 모드에서 마지막 assistant 메시지가 thinking 블록으로 시작해야 한다고 적는다. 그러나 재전송을
끄고 thinking 을 켠 요청은 거절되지 않는다. 서버는 그 요청의 thinking 을 조용히 끈다. 벤더 문서의 다른 문장 —
대화 도중 thinking 설정이 바뀐 충돌은 오류 없이 그 요청의 thinking 을 끄고 무효한 구조를 만들 블록을 벗긴다 — 이 이 경로를 지배한다.

| 무엇 | 결과 | 측정 |
|---|---|---|
| extended thinking 도구 루프에서 마지막 assistant 메시지의 thinking 블록을 벗기고 요청 | 200. 그다음 요청은 새 서명 블록을 만든다 | `AnthropicThinkingLiveTest` 와 같은 라이브 측정 |

그래서 이 조합을 `AnthropicConfig` 생성 시 거절하지 않는다. 거절했다면 서버가 받는 설정을 거부했을 것이다. 클라이언트는
`reportIfReplayIsOffWhileThinking` 으로 **두 방언 모두에서** 한 번 경고하고, 경고는 거절이 아니라 비용을 말한다 —
모델이 iteration 마다 추론을 다시 파생하고 thinking 토큰이 다시 과금되며, 의도한 것이라면(prefix 검사 탈출구) 할 일은 없다.

- budgeted 방언: 토큰도 원치 않으면 `thinkingMode(OFF)` 로 끄라고 덧붙인다
- adaptive 방언: `thinkingMode(OFF)` 는 레지스트리가 기술한 모델에서는 탈출구다. 400 은 레지스트리가 기술하지 않은
  모델에 **값을 실은 요청**(운영자가 둔 `temperature`, 또는 늘 `temperature` 를 싣는 서브에이전트 요청)이 겹칠 때만
  난다. 아무것도 설정하지 않았으면 파라미터를 보내지 않으므로 요청은 성공한다. 경고는 그 조건과 한 줄 해법(배포의 실제
  모델 이름을 레지스트리에 선언)을 말한다. 샘플링 파라미터 생략 규칙은 [`request-parameters.md`](request-parameters.md)
  가 정본이다

경고가 실제로 일어나는 비용만 말해야 하는 이유: 서버가 내지 않는 실패를 말하는 경고는 운영자가 다음 경고를 믿지 않게
만든다.

스위치의 설정 키와, 공유 `supportsReasoningTraceRoundTrip`(모델 사실)과 벤더 `replayThinkingBlocks`(한 벤더의 서명 오류
탈출구)를 네임스페이스가 가르는 이유는 [`configuration-surface.md`](configuration-surface.md) 에 있다.

---

## 8. `reasoningTokens` — 비용과 나란히 보고하고, 더하지 않는다

`TokenUsage` 는 네 번째 필드 `reasoningTokens` 를 가진다. provider 가 추론에 쓴 토큰 수이고 **`completionTokens` 의
부분집합**이다. OpenAI 의 `reasoning_tokens` 는 `output_tokens_details` 안에 있고, Anthropic 의 thinking 토큰은 output
토큰으로 과금된다. 두 경우 모두 `total = input + output` 이 그대로 성립한다.

- **비용에 더하지 않는다.** 이미 output 토큰으로 과금되었으므로 `ModelPrice.costOf` 에 더하면 두 번 과금한다.
  `costOf` 의 javadoc 이 이 필드를 의도적으로 무시한다고 적는다 — 적지 않으면 읽는 사람이 버그로 본다
- **`totalTokens` 에 더하지 않는다.** 그 필드의 뜻이 깨진다
- **나란히 보고한다.** 계측 · 트레이싱 데코레이터가 `TokenUsage` 전체를 넘기므로 새 필드는 모든 기록기에 도달한다
- **`>= 0` 만 검증한다.** 지금까지 통합된 provider 가 모두 포함 관계를 보고하지만 `reasoningTokens <= completionTokens`
  불변식은 두지 않는다. 값을 채우는 것은 서버이고, 던지는 교차 필드 검사는 회계상의 의외를 실패한 LLM 호출로 바꾼다
- `TokenUsage.of(int, int, int)` 는 `reasoningTokens = 0` 으로 남는다. 추론 토큰을 가진 usage 는 없는 usage 와
  `equals` 가 아니다

**영속과 노드 경계.** `SessionRecordCodec` 의 세션 합계는 이 값을 더해서 싣고, 필드가 없는 옛 문서는 0 으로 읽는다.
노드 사이 이벤트 · 상태 payload(`AgentExecutionEventPayload`, `StatusSnapshotPayload`)는 새 키에만
`PayloadValues.asIntOrZero` 를 쓴다. 기존 `asInt` 는 값이 null 이면 던지고, 두 디코더는 모든 `RuntimeException` 을
신호 드롭으로 바꾸므로, 롤링 업그레이드 중 옛 노드가 쓴 payload 는 카운터 하나가 0 이 되는 대신 상태 갱신 전체를 잃는다.
기존 세 키는 `asInt` 로 남는다 — 그 키가 없는 것은 정말로 잘못된 payload 다. 한 와이어(세션 코덱)는 구조상 관용적이고
다른 와이어(노드 payload)는 그렇지 않다는 비대칭이 이 선택의 이유다.

**읽기.** 회계 필드는 강등하고 식별 필드는 던진다.

- **OpenAI (Responses)** — `OpenAiResponseUsages` 가 raw 접근자로 읽고 없는 카운터는 0 으로 둔다. 읽기 경로 전반은
  [`openai-responses-path.md`](openai-responses-path.md)
- **Anthropic** — `usage.output_tokens_details.thinking_tokens` 다. 스트리밍에서는 마지막 `message_delta` 에만 온다. SDK
  2.13.0 의 `Usage` · `MessageDeltaUsage` 에는 이 접근자가 없고, 두 타입의 `@JsonAnySetter` 덕분에 값이
  `_additionalProperties()` 에 들어온다. `AnthropicUsages` 한 클래스가 블로킹과 스트리밍 양쪽에서 읽는다. 절대 던지지
  않는다 — 키 없음 · 모양 틀림 · 숫자 아님 · overflow 는 모두 0 이다. 읽지 못한 카운터가 성공한 호출을 실패시키면 안
  되기 때문이다. SDK 가 접근자를 갖게 되면 이 클래스 하나만 바뀐다

| 무엇 | 결과 | 측정 |
|---|---|---|
| OpenAI 스트리밍 `/v1/responses` 응답의 `reasoning_tokens` | 양수이고 `completionTokens` 이하 | 2026-09-10 |
| Anthropic extended 호출의 `output_tokens_details.thinking_tokens` | `thinking_tokens: 148` 이 `output_tokens: 239` 안에 포함, total = prompt + completion | `claude-haiku-4-5`, `AnthropicThinkingLiveTest` |

---

## 9. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `MessageArtifact` 에 추론 payload 를 담는다 | 필수 필드(`path` · `fileName` · `size`)가 이 내용에 대해 거짓이 되고, 이미 내려받을 파일로 사람에게 제시되는 타입이다(§2.2) |
| `ToolUseResult.getRenderPayload()` 를 재사용한다 | 계약상 영속되지 않는다. 트레이스는 불투명하면서 영속되어야 한다 |
| `thinking` / `redacted_thinking` 판별자를 payload 옆에 저장한다 | 블록 JSON 이 `"type"` 을 스스로 싣고 SDK 합집합이 그 값으로 분기한다. 영속 코어 타입을 이유 없이 넓힌다 |
| 캡처도 thinking 설정에 건다("요청하지 않았으면 찾지 않는다") | 설정 없이 thinking 이 켜진 모델에서 기능이 꺼지고 모든 단위 테스트는 초록으로 남는다(§7) |
| Anthropic 에 OpenAI 캡처 규칙을 그대로 쓴다 | 흔한 `[thinking, text, tool_use]` 가 `[text, thinking, tool_use]` 로 재구성되어 선두 규칙과 연속열 검사를 깬다(§4.2) |
| 저장된 payload 를 SDK 빌더로 재구성한다 | 서버가 더한 필드를 잃고, 서버가 만든 payload 에 `checkRequired` 검증을 들여와 드롭을 예외로 바꾼다(§5.2) |
| 레닥션이 payload 를 다시 쓴다 | 암호문과 서명은 바이트 하나만 바뀌어도 무효가 된다(§3.4) |
| 토큰 추정기가 트레이스 blob 을 텍스트로 센다 | 한 자릿수 과대 추정이 조기 컴팩션을 부르고 그 트레이스를 파괴한다(§3.5) |
| 전사 증가를 막으려 payload 에 상한을 두거나 자른다 | 왕복이 깨진다. 왕복이 기능 전체다(§3.6) |
| `FORMAT_VERSION` 을 올린다 | 저장된 모든 스냅샷이 디코딩 불가가 되는데, 두 방향이 이미 관용적이라 얻는 것이 없다(§3.2) |
| `replayThinkingBlocks(false)` + thinking 을 생성 시 거절한다 | 서버가 받는 설정이다(측정 200). 거절하면 동작하는 배포를 막는다(§7.1) |
| `reasoningTokens` 를 비용이나 `totalTokens` 에 더한다 | 이미 output 토큰에 포함되어 있어 두 번 과금하고 total 의 뜻을 깬다(§8) |
| `reasoningTokens <= completionTokens` 불변식을 둔다 | 값을 채우는 것이 서버라, 회계상의 의외가 실패한 호출이 된다(§8) |

---

## 10. 하지 말 것

- **payload 에 일반 `ObjectMapper` 를 쓰지 않는다.** 필드를 지어낸 손상된 항목이 나가고, Anthropic 에서는 호출이 깨진다
- **저장된 payload 를 SDK 빌더로 다시 만들거나 검증하지 않는다.** 코어에서 payload 를 파싱 · 정규화 · 자르지도 않는다
- **트레이스를 남기고 도구 호출을 버리는 스트리핑을 만들지 않는다.** 호출을 잃은 reasoning 항목, 일부만 빠진 thinking
  연속열이 그 모양이다. 컴팩션과 메시지 정제를 고칠 때 리뷰할 방향이 이것이다
- **메시지가 다시 읽히지 않는다는 이유로 부착 자리를 빼지 않는다.** 규칙에 예외가 생기면 검색으로 확인할 수 없다
- **`getProviderName()` 을 재전송 쪽과 캡처 쪽에서 따로 읽지 않는다.** 서브클래스에서 모든 트레이스가 foreign 으로 버려진다
- **요청 경로를 1인자 `AnthropicMessageConverter.convertMessages` 로 돌리지 않는다.** 컴파일되고 테스트가 초록이 되며
  재전송이 조용히 꺼진다
- **캡처에서 `thinking` 타입만 거르지 않는다.** `redacted_thinking` 이 빠지면 여러 요청에 걸친 왕복 프로토콜이 깨진다
- **순서 규칙을 블로킹과 스트리밍에 따로 구현하지 않는다.** provider 마다 해석 함수는 하나다
- **스트리밍 트레이스를 `STREAM_END` 뒤에 aggregator 로 넘기지 않는다.** 닫힌 aggregator 가 던지고 예외가 매핑 밖으로 샌다
- **서명 없는 thinking 블록을 되싣지 않는다.** 확정 400 이다
- **재전송 디코딩에서 다시 던지지 않는다.** 되실을 수 없는 트레이스는 버리고 보고한다
- **캡처를 `thinkingMode` 에 걸지 않는다**
- **reasoning delta 나 reasoning summary 를 `ReasoningTrace` 에 넣지 않는다.** 되실을 payload 가 아니다
- **`reasoningTokens` 를 `totalTokens` 나 `costOf` 에 더하지 않는다.** 노드 사이 payload 의 새 카운터 키를 `asInt` 로 읽지 않는다

---

## 11. 남은 것

모두 백로그에 등록되지 않은 항목이다.

- **앵커의 필요성이 측정되지 않았다.** OpenAI 왕복이 **동작한다**는 것은 측정했지만 앵커를 빼면 실패하는지는 재지 않았다.
  일부러 틀리게 만든 요청이 필요하다
- **Anthropic 캡처 규칙의 "text 가 끼면 비앵커" 는 문서화된 순서에서 추론한 것이다.** 재배열되는 세 모양(§4.2) 중 여러
  텍스트 블록과 교차하는 모양이 실제로 얼마나 나오는지 관측하지 않았다. 고치려면 `Message` 가 텍스트 위치를 운반하는
  코어 변경이 필요하다
- **`redacted_thinking` 스트리밍을 관측하지 않았다.** `content_block_start` 에 완결된 채 온다는 가정이 틀리면 스트리밍
  경로가 그 트레이스를 버린다
- **preserved-thinking prefix 검사를 실제로 일으켜 보지 않았다.** 재렌더된 프롬프트로 Fable 5.1 세션을 돌려 400 을 본
  적이 없다. 스위치는 운영에서 발견하면 비싼 실패라서 있다
- **`thinking-binding-controls-2026-08-01` beta header 와 `prefix_mismatch_behavior: "drop_block"`** — prefix 가 무효가
  되었을 때 요청을 실패시키지 않고 블록을 버리고 무엇을 버렸는지 알리는, prefix 검사의 원칙적 해법이다. beta header 에
  새 설정 축이 붙어 따로 설계해야 한다
- **전사 증가량이 측정되지 않았다.** iteration 40 번짜리 추론 도구 루프가 세션 행을 얼마나 키우는지 모른다(§3.6)
- **레닥션 요구가 강한 배포를 위한 정책이 없다.** 트레이스를 다시 쓰는 것이 아니라 만들지 않는 정책이 필요하고, 그것은
  별도 설계다(§3.4)
- **`replayThinkingBlocks` 기본값 `true` 는 실제 트래픽으로 확인하지 않은 판단이다**(§7)
- **컴팩션이 도구 결과를 기다리는 최신 assistant 메시지를 다시 쓰는지 끝까지 추적하지 않았다.** 스트리퍼가 메시지 단위로
  전부 버리므로 정확성은 유지되고, 남은 것은 확신의 문제다
- **1인자 `AnthropicMessageConverter.convertMessages` 를 deprecated 로 둘지 지울지 정하지 않았다.** `0.x` 는 제거를
  허용하고, 조용히 재전송하지 못하는 deprecated 메서드가 컴파일 오류보다 나쁠 수 있다

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `modules/aimon-core/…/llm/ReasoningTrace.java` | 세 필드와 제약, 코어가 payload 를 읽지 않는다는 계약, `toString` 이 길이만 찍음 |
| `modules/aimon-core/…/llm/Message.java` | `withReasoningTraces` · `getReasoningTraces` · `hasReasoningTraces`, `mapText` 가 payload 에 닿지 않는다는 javadoc |
| `modules/aimon-core/…/llm/LlmResponse.java` | 응답이 트레이스를 싣는 자리 |
| `modules/aimon-core/…/llm/streaming/ChunkAggregator.java` | `addReasoningTrace` 가 닫힌 뒤 던짐 |
| `modules/aimon-core/…/agent/impl/orca/OrcaAgentExecutor.java`, `…/subagent/execution/DefaultSubagentExecutor.java`, `…/skill/execution/llm/LlmSkillExecutor.java`, `…/memory/deriver/ReActLlmDeriver.java` | 부착 규칙을 따르는 네 루프 |
| `modules/aimon-core/…/subagent/task/codec/JsonSessionSnapshotCodec.java` | `reasoning` 배열, 비어 있을 때 쓰지 않음, `FORMAT_VERSION` |
| `modules/aimon-core/…/agent/session/store/SessionRecordCodec.java` | 세션 합계의 `reasoningTokens` 와 없는 필드의 0 |
| `modules/aimon-core/…/agent/compact/MessageStripper.java` | fast path 의 `hasReasoningTraces()`, 재구성에서 트레이스를 버리는 이유 |
| `modules/aimon-core/…/llm/token/HeuristicTokenEstimator.java`, `modules/aimon-llm-openai/…/token/TikTokenEstimator.java` | 트레이스를 세지 않음 |
| `modules/aimon-core/…/llm/TokenUsage.java`, `…/llm/cost/ModelPrice.java` | 부분집합 의미, `>= 0` 검증, `costOf` 가 무시하는 이유 |
| `modules/aimon-session-routing/…/internal/PayloadValues.java`, `AgentExecutionEventPayload.java`, `StatusSnapshotPayload.java` | 새 키에만 `asIntOrZero` |
| `modules/aimon-llm-openai/…/OpenAiReasoningTraces.java` | SDK 매퍼, 빌더 재구성 금지, foreign / unparseable 판별 |
| `modules/aimon-llm-openai/…/OpenAIResponsesMessageConverter.java` | 재전송 규칙, 캡처 스캔(첫 뒤따르는 호출), 드롭 보고 |
| `modules/aimon-llm-openai/…/OpenAIResponsesStreamingMapper.java`, `OpenAIResponsesExchange.java` | `output_item.done` 에서 모은 항목, `encrypted_content` 없음 경고 |
| `modules/aimon-llm-openai/…/OpenAILlmClient.java` | 요청당 provider 이름 1회 해석, 드롭 보고가 쓰는 once 집합 |
| `modules/aimon-llm-openai/…/OpenAiResponseUsages.java` | `reasoning_tokens` 읽기와 강등 |
| `modules/aimon-llm-anthropic/…/AnthropicReasoningTraces.java` | `payloadOf`, 합집합 파싱, JSON 객체 검사 |
| `modules/aimon-llm-anthropic/…/AnthropicOutputBlocks.java` | 캡처 규칙, 재배열되는 세 모양, 서버 측 도구 블록 사각지대 |
| `modules/aimon-llm-anthropic/…/AnthropicMessageConverter.java` | 재전송 규칙, 빈 text 블록 금지, `withoutReasoningTraces`, deprecated 1인자 overload, 드롭 보고 |
| `modules/aimon-llm-anthropic/…/AnthropicStreamingMapper.java` | thinking 슬롯, 서명 없는 블록 드롭, `STREAM_END` 전 flush |
| `modules/aimon-llm-anthropic/…/AnthropicLlmClient.java` | 요청당 provider 이름 1회 해석, 재전송 스위치 적용, `reportIfReplayIsOffWhileThinking`, recurring 카운터 |
| `modules/aimon-llm-anthropic/…/AnthropicUsages.java` | `thinking_tokens` 비타입 읽기, 절대 던지지 않음 |
| `modules/aimon-llm-anthropic/…/AnthropicConfig.java` | `replayThinkingBlocks` 기본값 |

`…` 는 `src/main/java/at/aimon/core`(provider 모듈은 `…/llms/<provider>`) 까지의 경로다.

---

## 관련 문서

- [`openai-responses-path.md`](openai-responses-path.md) — OpenAI 가 트레이스를 되싣는 엔드포인트, `output_item.done`, 읽기 경로
- [`anthropic-thinking.md`](anthropic-thinking.md) — `thinking` 요청 파라미터, 모드 · 방언 · budget · `display`
- [`request-parameters.md`](request-parameters.md) — divergence 보고의 once 집합 / recurring 카운터, 샘플링 파라미터 생략
- [`streaming.md`](streaming.md) — reasoning delta 채널(트레이스가 아닌 것), `ChunkAggregator`
- [`multimodal-content.md`](multimodal-content.md) — `Message` 콘텐츠 블록
- [`model-capabilities.md`](model-capabilities.md) — `supportsReasoningTraceRoundTrip`
- [`configuration-surface.md`](configuration-surface.md) — `replayThinkingBlocks` 설정 키와 네임스페이스
- [`../agent-execution/compaction.md`](../agent-execution/compaction.md) — 컴팩션과 `MessageStripper`
- [`../../migration/frozen-names.md`](../../migration/frozen-names.md) — 영속 필드를 더하는 것과 이름을 바꾸는 것의 경계
- [`../../features/llm/llm-provider-development-guide.md`](../../features/llm/llm-provider-development-guide.md) — provider 가 슬롯을 채우고 읽는 방법
- [`../../features/llm/llm-usage-metering.md`](../../features/llm/llm-usage-metering.md) — `reasoningTokens` 는 기록되고 가격이 매겨지지 않는다
