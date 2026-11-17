# 중단된 턴의 재시도와 되감기

> Status: **IMPLEMENTED**
> 적용 대상: `aimon-core` — `agent.session`(`LiveSession.retryLastTurn` · `rewindLastTurn`, `RewoundTurn`),
> `agent.session.transcript`(`SessionRewindPoint`, `TranscriptBuffer`, `SessionLogState`), `agent.impl.orca`(지점의 표시와 지우기),
> `subagent.task.codec`(`SubmitOptionsCodec`).
> 남은 일은 [`backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) 가 정본이다.
> 관련 문서: [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md)(턴을 끊는 메커니즘),
> [`session-log.md`](session-log.md)(rewind 저장 형태), [`session-model.md`](session-model.md)(레코드와 전사),
> [`routing.md`](routing.md)(크로스 노드 제출)

중단된 턴의 흔적을 걷어 내고 같은 요청을 같은 옵션으로 다시 제출하는 방식을 정한다. 턴을 끊는 메커니즘은
[`../agent-execution/interrupt.md`](../agent-execution/interrupt.md), 되감기가 로그를 자르는 저장 형태는
[`session-log.md` §8.1](session-log.md#81-rewind--truncatefromseq), 레코드와 전사의 구조는 [`session-model.md`](session-model.md) 가
정본이다.

---

## 1. 흔적과 재시도

중단된 턴은 흔적을 남긴다. 사용자 메시지, 그 앞에 주입된 합성 컨텍스트 블록, 멈추기 전에 나온 assistant 출력, 그리고 도구 결과들 —
"Interrupted — skipped" 로 채워진 것까지. 그 흔적은 **일부러 남긴다.** 사용자가 그 일이 벌어지는 것을 봤기 때문이다.

하지만 그 위에 같은 요청을 다시 얹으면 안 된다. 모델에게 "이미 반쯤 해 놓은 일" 이라고 적힌 이력에서 그 일을 다시 하라고 시키는
것이 되기 때문이다. 그래서 재시도는 먼저 흔적을 걷어 낸다 — `LiveSession.retryLastTurn()`.

흔적은 누군가 재시도를 요청할 때만 걷는다. 중단 뒤 자동으로 재시도하지 않는다 — 사용자 의도(abort 인지 retry 인지)가 모호하기
때문이다([`interrupt.md` §13](../agent-execution/interrupt.md#13-의도적으로-제외한-것)).

---

## 2. 되감기 지점

되감으려면 턴이 어디서 시작했는지 알아야 한다. **마지막 user 메시지를 찾는 휴리스틱은 틀린다** — 훅 조언(`checkOnStartHooks`)과
조립된 합성 컨텍스트 블록이 모두 user 역할로 들어가기 때문이다. 그래서 경계를 기록한다.

`SessionRewindPoint`(`agent.session.transcript`)는 셋을 든다.

| 필드 | 뜻 |
|------|----|
| `getSeq()` | 턴 첫 항목의 seq — 턴이 무엇을 더하기 전의 `nextSeq` |
| `getUserInput()` | 그 턴을 시작한 `UserInput` |
| `getSubmitOptions()` | 그 턴을 제출한 `SubmitOptions` (§8) |

- **seq 를 든다.** 인덱스는 앞의 것이 빠지면 움직이지만 seq 는 재사용되지 않는 로그 항목의 주소다
  ([`session-log.md` §3.1](session-log.md#31-seq)). 그래서 지점은 레코드가 어떻게 바뀌어도 같은 항목을 가리킨다.
- **입력을 인덱스로 찾지 않고 통째로 든다.** 사이의 메시지들이 role 로 구별되지 않기 때문이다 — 주입된 컨텍스트 블록도 user 역할이다.
- **`Message` 가 아니라 요청 자체를 든다.** 실행기는 `UserInput` 을 받아 `UserInputConverter` 로 `Message` 를 만들고, 그 변환은
  되돌릴 수 없다. 이미지는 자기 텍스트가 없어 `[Image: image/png, 1024 bytes]` 같은 자리표시자로 읽히고, 그것을 다시 제출하면 그림에
  대해 묻던 턴이 그림을 묘사한 문장에 대해 묻는 **다른 턴**이 된다. 요청을 들고 있으면 재시도가 같은 변환을 다시 태우므로 재구성할
  것이 없고, 재생할 수 없는 입력 종류도 없다.
- **지점은 최대 하나이고 가장 최근 턴만 가리킨다.** 다른 방식으로 끝난 턴은 지점을 지운다. "재시도" 는 언제나 마지막 턴이지 임의의
  턴이 아니다.

---

## 3. 지점을 로그 안에 두는 이유

지점은 `SessionTotals` 나 `budgetOverride` 처럼 레코드의 side field 로 두지 않고 `SessionLogState` 안에 둔다. **지점은 로그 항목의
주소이므로 로그와 한 값으로 저장되고 검증되어야 한다.**

- **함께 검증된다.** `SessionLogState` 는 지점이 `[floorSeq, nextSeq]` 안에 있고 어떤 봉인 구간의 안쪽에도 있지 않은지 확인한다. 로그를
  다시 쓰는 연산이 지점을 함께 정리하지 않으면 그 검증이 실패한다(§6.1).
- **함께 움직인다.** 지점은 `SessionSnapshot` 을 타고 로그와 함께 레코드로 간다. side field 는 `mergeFromSnapshot` 이 전사와 따로
  보존하므로([`session-model.md` §4.4](session-model.md#44-스냅샷은-side-field-를-담지-않는다)), 거기 두면 로그가 다시 쓰여도 지점은
  옛 값으로 살아남아 자기가 가리키지 않는 이력을 가리키게 된다.
- **저장소 SPI 가 늘지 않는다.** `SessionSnapshot` → `mergeFromSnapshot` 이라는 기존 경로를 그대로 탄다.

---

## 4. 표시와 지우기

| 시점 | 하는 일 |
|------|---------|
| 턴 시작 (`transcriptManager.initialize` 직후) | `beginTurn(userInput, submitOptions)` — 합성 컨텍스트 블록이 들어가기 **전**에 찍는다 |
| 턴 종료, `INTERRUPTED` 가 아님 | `endTurn()` — 지운다 |
| 턴 종료, `INTERRUPTED` | 아무것도 안 한다 — 남아 있는 것이 곧 "재시도할 수 있음" |

- **합성 블록 앞에 찍는다.** 합성 블록도 사용자 메시지만큼 그 턴의 것이다. 재시도가 그것을 남기면 새 대화에는 두 번째 사본이
  주입되고 나머지에는 고아 사본이 남는다.
- **지우기는 `saveSilently` 앞에서 한다.** 영속된 것이 이미 "이 세션에 재시도할 것이 있는가" 를 말하게 하기 위해서다. 뒤에서 하면 그
  사이에 죽은 프로세스가 완료된 턴을 중단된 것처럼 보이게 남긴다.

---

## 5. 재시도는 평범한 턴이다

`retryLastTurn` 은 짧아진 이력을 **먼저 영속**시키고 나서 제출한다. 그래야 재시도가 또 중단돼도 흔적이 쌓이지 않고 같은 자리에서
다시 시작한다. 그 밖에는 보통 턴과 같다 — 예산, 훅, 이벤트, 인터럽트 전부.

**"재시도할 수 있나?" 를 먼저 묻는 술어는 두지 않는다.** 묻는 순간과 하는 순간 사이에 답이 바뀔 수 있어서 그 위에 분기를 쓰면
경합이 된다. 빈 `Optional` 이 그 답이다. `LiveSessionStatus` 를 제어 게이트로 쓰지 않는 것과 같은 이유다
([`glossary.md` › Live session 계층](../../overview/glossary.md#live-session-계층-일시적)).

**되감기 지점이 영속되므로 핸들이 죽어도 재시도는 살아남는다.** 인터럽트는 프로세스가 사라지기 가장 쉬운 순간(SIGINT · 축출 · 노드
이동)이므로, 핸들에만 있었다면 가장 필요할 때 없었을 것이다.

---

## 6. 컴팩션과 동시 턴

지점은 로그 항목의 seq 이므로, 압축이 로그를 어떻게 다루는가에 따라 결과가 갈린다. 압축 모드 자체는
[`context-engine.md`](../agent-execution/context-engine.md) 가 정본이다.

### 6.1 in-place 경로는 지점을 버린다

in-place 경로(v1 쓰기 모드, 그리고 v2 전사가 뷰 모드를 쓸 수 없을 때의 폴백)는 압축과 prompt-too-long 복구 모두 `replaceWith` 로
로그를 다시 쓴다. 새 항목은 `nextSeq` 부터 새 seq 를 받고 `floorSeq` 가 그 첫 항목으로 올라가므로, 지점이 가리키던 항목은 사라진다.
그래서 `replaceWith` 는 **지점을 버린다.**

그냥 두면 엉뚱한 자리로 되감는 정도가 아니다. 지점이 `floorSeq` 아래에 놓이고, 그 위치는 전사를 재구성하는 자리에서 검증되므로
(§3) 턴 종료 저장이 던지고, 그것을 `saveSilently` 가 삼켜서 **그 턴의 이력 전체가 말없이 사라진다.** 그 턴 하나를 재시도할 수 없게
되는 것이 정직한 대가다.

### 6.2 뷰 모드에서는 지점이 살고, rewind 가 요약 span 을 자른다

뷰 모드에서 압축은 로그를 고치지 않고 뷰 상태에 요약 span 을 설치하므로 지점은 살아남는다. 대신 중단된 턴 안에서 압축이 일어나
span 이 지점을 걸치면, rewind 는 span 의 `toSeq` 를 지점으로 자르고 요약문은 그대로 둔다. 요약에 버려진 부분의 내용이 섞여 있을 수
있다 — 알려진 부정확함이며 규칙과 남은 일은 [`session-log.md` §8.1](session-log.md#81-rewind--truncatefromseq) 이 정본이다.

### 6.3 도는 턴 위의 되감기는 거절한다

**턴이 도는 중의 되감기는 거절한다.** 될 수가 없기 때문이다 — 도는 턴은 자기 이력 사본을 들고 있다가 끝날 때 되쓰므로, 방금 걷어 낸
흔적을 그대로 되돌려 놓는다. `DefaultLiveSession` 은 진행 중인 턴이 보이면 `IllegalStateException` 을 던진다. 검사 직후에 시작하는
턴까지 막지는 못하지만, 흔한 실수를 **조용히 아무것도 안 한 되감기** 대신 에러로 바꾼다. 먼저 인터럽트하고 나서 되감는다.

---

## 7. 멀티모달 입력

이미지 하나만 보낸 턴, 첨부 파일이 붙은 턴, 텍스트와 스크린샷을 함께 보낸 턴도 재시도된다. 되감기 지점이 `UserInput` 을 들고
있으므로(§2) 재시도는 **같은 요청을 다시 제출**하는 것이고, 변환은 첫 시도 때와 똑같이 그 자리에서 다시 일어난다.

`LiveSession` 의 `submit` · `submitAsync` · `offerAsync` 는 `UserInput` 오버로드를 가진다.

- **필수 메서드는 `String` 쪽이다.** 세 메서드의 `(String, SubmitOptions, …)` 형태가 추상으로 남고, `UserInput` 오버로드가 그 위에 기본 구현으로 얹힌다 —
  `TextInput` 이면 벗겨서 `String` 메서드로 내려보내고, 그 밖이면 `UnsupportedOperationException` 이다. 셋 다 기본 구현이면 제출
  메서드를 하나도 구현하지 않은 세션이 컴파일되고 런타임에 실패하기 때문이다.
- **텍스트는 어디서나 통한다.** `String` 오버로드만 구현한 세션에서도 `submit(UserInput)` 이 동작한다. 되감기 지점은 언제나
  `UserInput` 이므로, 이것이 없으면 되감은 것이 평범한 텍스트여도 그런 세션에서 재시도가 거절된다.
- **납작하게 만들지 않는다.** 이미지를 `asText()` 자리표시자로 바꿔 제출하면 그림에 대해 묻던 턴이 그림을 묘사한 문장에 대해 묻는
  다른 턴이 되므로, 못 한다고 말하는 쪽이 낫다.

**큐는 텍스트 채널이다.** 지연된 입력은 `<system-reminder>` 블록으로 재생되므로(`QueuedInput.getInputText()`) 이미지가 기다릴 수 있는
형태가 없다. 그래서 턴이 도는 중에 들어온 비텍스트 입력에 `offerAsync` 는 줄 수 있는 답이 없다 — 미룰 수 없고, 그렇다고 실행하면
도는 턴이 쓰고 있는 전사에 두 번째 턴을 얹는다. 조용히 틀린 쪽을 고르는 대신 `IllegalStateException` 으로 거절한다. 동시 실행을
**의도한** 호출자에게는 `submitAsync` 가 따로 있다. 재시도는 되감기가 도는 턴을 먼저 거절하므로(§6.3) 이 분기에 닿지 않는다.

**크로스 노드 제출(`SubmitRequest`)은 `UserInput` 을 나르지만, 라우터는 재시도를 노출하지 않는다.** 재시도는 핸들을 직접 쥔
호스트(임베더, CLI)의 것이다. 제출 요청의 모양은 [`routing.md` §13.1](routing.md#131-submitrequest) 이 정본이다.

---

## 8. 옵션 보존과 코덱

턴은 무엇을 물었는가만이 아니라 **누가 어떤 맥락에서** 제출했는가까지다. 되감기 지점은 `UserInput` 과 함께 그 턴의
`SubmitOptions`(principal, 시스템 프롬프트 변수, 실행 속성, LLM 호출 메타데이터)를 든다. 옵션 없이 재시도하면 같은 요청이 다른
정체성으로 돈다 — principal 은 `ToolContextKeys.PRINCIPAL` 로 도구 컨텍스트에, `MemoryContextRequest` 로 메모리 조립에 들어가기
때문이다. 도구는 요청자를 못 보고 메모리는 다른 사람 것으로 조립된다. 이미지를 자리표시자로 납작하게 만드는 것과 같은 종류이고 더
알아채기 어렵다.

| 메서드 | 하는 일 |
|--------|---------|
| `retryLastTurn()` | 원래 옵션으로 다시 제출한다 |
| `retryLastTurn(SubmitOptions)` | 옵션을 갈아 끼워 제출한다 — 이 오버로드의 존재 이유 |
| `rewindLastTurn()` | 되감고 입력과 옵션을 함께 담은 `RewoundTurn` 을 돌려준다 |

`RewoundTurn` 이 둘을 함께 담는 것은 반쪽만 집어 가기 어렵게 하기 위해서다. 입력만 받아 가면 같은 말을 다른 사람이 하는 것이 된다.

**옵션은 요청에서 되짚지 않고 통째로 나른다.** `OrcaAgentExecutionRequest` 는 옵션의 값들을 이미 펼쳐서 갖고 있지만, 거기서
`SubmitOptions` 를 재구성할 수는 없다 — 그 시점엔 기본값이 적용된 뒤라 "지정하지 않음" 과 "기본값으로 지정함" 이 구별되지 않는다.
`llmCallMetadata` 가 특히 그렇다. 지정하지 않으면 에이전트·세션에서 다시 도출되는데, 되짚어 재구성하면 재시도가 component 와 traceId
를 못 박아 버린다. 그래서 요청이 원본 `SubmitOptions` 를 따로 들고 다니며(`getSubmitOptions()`), 그 필드는 **실행에는 쓰이지 않는다**
— 되감기 지점에 넘겨주기 위해서만 있다.

**옵션은 `SubmitOptionsCodec` 하나로 인코딩한다.** 인박스 와이어와 같은 필드 이름과 모양을 쓴다. 대가로 `Map<String, Object>` 두 개는
값이 JSON 스칼라로 왕복하고 커스텀 POJO 는 중첩 `Map` 으로 돌아온다 — `ToolUse` 의 입력 맵이 지는 것과 같은 대가다.

---

## 9. 설계 결정

| 쟁점 | 결정 | 기각한 대안 |
|------|------|------------|
| 흔적을 언제 걷나 | 재시도를 요청할 때만(§1) | 중단 즉시 걷는다: 사용자가 본 일이 사라진다. 흔적 위에 그대로 재제출: 반쯤 한 일이라고 적힌 이력에서 다시 하라고 시킨다 |
| 턴 경계를 아는 법 | 턴 시작 때 기록한다(§2) | 마지막 user 메시지를 찾는다: 훅 조언과 합성 블록도 user 역할이다 |
| 지점의 주소 | seq(§2) | 메시지 개수(인덱스): 앞의 항목이 빠지면 다른 메시지를 가리킨다 |
| 재시도할 입력 | `UserInput` 그대로(§2) | 만들어진 `Message`: 변환이 되돌릴 수 없어 이미지가 자리표시자로 재생된다 |
| 지점의 자리 | `SessionLogState` 안(§3) | 레코드의 side field: 로그와 따로 보존되어 다시 쓰인 로그의 옛 위치를 가리킨다 |
| 지우는 시점 | `saveSilently` 앞(§4) | 뒤: 그 사이에 죽으면 완료된 턴이 중단된 것으로 보인다 |
| 재시도 가능 여부 | 빈 `Optional` 이 답, 술어 없음(§5) | 먼저 묻는 술어: 묻는 순간과 하는 순간 사이의 경합 |
| in-place 압축과 지점 | 지점을 버린다(§6.1) | 지점을 남긴다: 턴 종료 저장이 던지고 그 턴의 이력 전체가 말없이 사라진다 |
| 도는 턴 위의 되감기 | `IllegalStateException`(§6.3) | 조용히 진행: 도는 턴이 끝날 때 흔적을 되돌려 놓는다 |
| `UserInput` 오버로드의 방향 | `String` 이 추상, `UserInput` 이 기본 구현(§7) | 셋 다 기본 구현: 제출 메서드 없는 세션이 컴파일된다 |
| 비텍스트 입력을 못 받는 세션·큐 | 거절한다(§7) | 텍스트로 납작하게: 다른 턴이 된다 |
| 재시도의 옵션 | 원본 `SubmitOptions` 를 지점이 든다(§8) | `SubmitOptions.empty()`: 다른 정체성으로 돈다. 요청에서 재구성: "지정 안 함" 과 "기본값" 이 구별되지 않는다 |
| 옵션의 인코딩 | 인박스와 같은 모양의 `SubmitOptionsCodec`(§8) | 저장소마다 사본: 같은 매핑이 어긋난다 |

---

## 10. 하지 말 것

- **마지막 user 메시지로 턴 경계를 추정하지 말 것**(§2).
- **되감기 지점을 레코드의 side field 로 옮기지 말 것**(§3).
- **지점을 `saveSilently` 뒤에 지우지 말 것**(§4).
- **"재시도할 수 있나" 술어를 만들어 그 위에 분기하지 말 것**(§5).
- **`replaceWith` 뒤에 지점을 남기지 말 것.** 턴의 이력이 말없이 사라진다(§6.1).
- **도는 턴 위에서 되감지 말 것.** 먼저 인터럽트한다(§6.3).
- **재시도할 입력을 `asText()` 로 납작하게 만들지 말 것**(§7).
- **재시도를 `SubmitOptions.empty()` 로 돌리거나 요청에서 옵션을 재구성하지 말 것**(§8).

---

## 11. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|------|------|------|
| 재시도 · 되감기 API, `UserInput` 오버로드 | `LiveSession`, `RewoundTurn` | `agent/session/` |
| 되감기 경로, 도는 턴 거절, 큐의 비텍스트 거절 | `DefaultLiveSession` | `agent/session/` |
| 지점 | `SessionRewindPoint` | `agent/session/transcript/` |
| 표시 · 지우기 · in-place 의 지점 폐기 | `TranscriptBuffer` | `agent/session/transcript/` |
| 지점 검증 · `truncateFrom` | `SessionLogState` | `agent/session/transcript/` |
| 턴 경계 표시 시점 | `OrcaAgentExecutor` | `agent/impl/orca/` |
| 원본 옵션 운반 · 입력 변환 | `OrcaAgentExecutionRequest`, `UserInputConverter` | `agent/impl/orca/` |
| 옵션 코덱 | `SubmitOptionsCodec` | `subagent/task/codec/` |
| 크로스 노드 제출 | `SubmitRequest` | `modules/aimon-session-routing/src/main/java/at/aimon/session/routing/` |
