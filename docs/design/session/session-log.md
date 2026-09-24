# Session Log — 기록과 뷰의 분리

> Status: **IMPLEMENTED** — 적용 대상 `aimon-core`, `aimon-bootstrap`, `aimon-session-{mongodb,postgres,redis}`
> (세그먼트 저장소 구현). 열린 질문은 §11.
> §1 ~ §11 과 부록은 리뷰를 통과한 설계 그대로다(`f09d891`, 승인 뒤 고치지 않았다). 구현이 갈라진 자리와 남은 틈은
> [§12](#12-구현과의-차이).
> 후속 설계: [`../agent-execution/context-engine.md`](../agent-execution/context-engine.md) (이 저장 모델 위에서 뷰를 만드는 자리)
> 관련 문서: [`session-model.md`](session-model.md), [`backends.md`](backends.md),
> [`../agent-execution/compaction.md`](../agent-execution/compaction.md), [`../../overview/scope-model.md`](../../overview/scope-model.md)

---

## 1. 무엇을 푸는가

`SessionRecord` 의 transcript 는 **LLM 에 보이는 대화**다. 압축과 prompt-too-long 복구가 그것을 `replaceWith` 로 고쳐
쓰므로, 뷰를 줄이는 일이 곧 기록을 지우는 일이 된다. 그 결과가 [`context-engine.md` §1.1](../agent-execution/context-engine.md)
의 L3~L5 다 — 원문 소실, 압축된 실행의 메모리 누락, 요약문의 ingest.

이 문서는 기록을 **append-only 로그**로 바꾸고, 뷰를 줄이는 정보는 로그 옆의 **뷰 상태**에 따로 둔다. 로그는 압축으로
줄지 않고, 뷰 상태는 로그를 가리키기만 한다.

그 대가로 새 문제가 하나 생긴다. 지금 레코드는 압축 덕분에 크기에 상한이 있고, 체크포인트는 매번 레코드 전체를 쓴다.
로그가 줄지 않으면 쓰기량이 세션 길이의 제곱으로 는다. 그래서 이 설계의 절반은 **뷰에 원문으로 나타나지 않는 로그
구간을 레코드 밖으로 봉인(seal)하는** 규칙이다(§5).

---

## 2. 모양

```
SessionRecord.transcript  =  systemPrompt + SessionLogState
  SessionLogState
    ├─ nextSeq, floorSeq
    ├─ hot entries      (seq, message, origin)   — 봉인되지 않은 항목, seq 오름차순
    ├─ manifest         [(fromSeq, toSeq, segmentId, contentHash)]  — 봉인된 구간
    ├─ viewState        SessionViewState  (context-engine 이 바꾼다)
    └─ rewindPoint      seq 기반

SessionLogSegmentStore  (레코드 밖)
  segment  segmentId → 불변 메시지 목록
```

| 값 | 수명 | 어디 |
|----|------|------|
| 로그 항목 | session | 봉인 전에는 레코드, 봉인 후에는 세그먼트 저장소 |
| manifest · 뷰 상태 · rewind point | session | 레코드 |
| ingest mark | 한 실행 | 노드 로컬, 영속하지 않음 (지금 그대로 — seq 로 바뀔 뿐) |

**하나의 불변 값** — 위의 레코드 쪽 전부를 `SessionLogState` 하나로 묶는다. 지금 transcript 는 레코드 로드·저장 체인의
여러 타입(§7.2)을 지나며 필드별로 **골라서** 복사되고, 새 필드는 한 홉이라도 빠지면 조용히 사라진다. 한 값으로 묶으면
체인은 그 값을 통째로 넘기기만 한다.

**한 레코드에 두는 이유** — 로그·manifest·뷰 상태를 한 번의 저장으로 원자적으로 바꾸기 위해서다. 뷰 상태가 레코드에
없는 seq 를 가리키거나, 레코드가 없는 세그먼트를 가리키는 순간이 생기지 않는다.

---

## 3. 로그

### 3.1 seq

로그 항목은 인덱스가 아니라 **seq** 로 주소를 갖는다. seq 는 세션마다 0 부터 단조 증가하고 **재사용하지 않는다** —
rewind 로 잘리거나 `/clear` 로 지워진 뒤에도 다음 항목은 `nextSeq` 에서 이어진다.

로그를 가리키는 값은 전부 seq 다 — rewind point, ingest mark, 뷰 상태의 span·범위·elision, manifest, 도구의 placeholder.
로그가 압축으로 줄지 않으므로 이 값들은 **다시 매핑될 일이 없다.** 제자리 수정 모델에서 압축마다 필요했던 위치 재매핑이
사라지는 것이 이것 때문이다.

재사용하지 않는 이유 — 잘린 seq 를 다시 쓰면 이미 LLM 에 나간 placeholder 나 다른 노드의 manifest 가 다른 메시지를
가리키게 된다.

### 3.2 항목의 출처 — `origin`

항목은 `(seq, message, origin)` 이다. `origin` 은 이 메시지가 **대화인지 런타임이 넣은 것인지**를 말한다.

| origin | 예 | ingest·되찾기 |
|--------|----|---------------|
| `CONVERSATION` | 사용자 입력, assistant 응답, 도구 결과, mid-turn 사용자 메시지 | 포함 |
| `SYNTHETIC` | CTX-06 user-context 블록, 조립된 `<system-reminder>`, 압축 복원 훅이 붙인 파일·스킬 목록 | **제외** |

`Message` 에는 메타데이터 자리가 없고, 넣으면 모든 `LlmClient` 변환과 코덱이 영향을 받는다. 텍스트 태그로 판별하면
mid-turn 사용자 메시지(`<system-reminder key="user-mid-turn-message">` 로 감싸인다)를 오분류한다. 그래서 출처는 메시지가
아니라 **로그 항목**에 둔다.

- **API** — `addMessage(Message, LogOrigin)` 을 더하고, 기존 `addMessage(Message)`·`addUserMessage(...)` 는 `CONVERSATION` 을
  뜻한다. 런타임 주입 경로는 전부 `SYNTHETIC` 오버로드로 바꾼다
- **런타임 주입 경로** — CTX-06 user-context(`maybeInjectUserContextMessage`), 조립된 컨텍스트(`injectAssembledUserContext`),
  OnStart 훅의 advisory 피드백(`HookFeedback.toReminderBlock`), 명령 흐름이 남기는 assistant 응답(`executeCommandFlow`), 압축
  복원 훅. 외부 훅이 `PostCompactContext.getTranscriptBuffer()` 로 받은 버퍼에 append 하면 기본값 `CONVERSATION` 이 된다 —
  훅 컨텍스트는 `addSyntheticMessage(...)` 를 함께 노출하고, 훅 개발 가이드가 그것을 쓰라고 말한다

v1 에서 옮겨 온 항목은 전부 `CONVERSATION` 이다 — 판별할 근거가 없다.

### 3.3 `TranscriptBuffer` 의 API

지금 `getMessages()` 는 "레코드에 있는 메시지 = LLM 뷰 = 기록" 이었다. 셋이 갈라지므로 API 도 셋이 된다.

| API | 뜻 | 쓰는 곳 |
|-----|----|---------|
| `getMessages()` | hot 항목, `floorSeq` 이후 | 레코드 안의 것만 보면 되는 곳 — 서브에이전트 스냅샷(봉인하지 않으므로 전체와 같다), 명령의 이전 스냅샷 |
| `SessionLogReader.read(sessionId, fromSeq, toSeq)` | 봉인 포함 전체 기록 | 되찾기 도구, `SESSION_END`, 운영 도구 |
| `ContextEngine.prepare(...)` 의 뷰 | LLM 에 보낼 것 | LLM 호출 자리 |

`getMessages()` 는 봉인이 돌면 앞에서부터 줄어든다 — 기록도 뷰도 아닌 "레코드 안의 부분" 이다. 이 뜻의 변화는 공개 API
(`AgentExecutionResult.getConversationHistory()`, `SessionSnapshot.getConversationHistory()`)에도 번진다. 호출자 전수와
각각의 이행은 부록 A 에 있다.

`replaceWith(...)`·`replaceMessageAt(...)` 는 v2 에서 쓰지 않는다. 뷰를 줄이던 호출자(압축, 복구)는 뷰 상태 연산이 되고,
`TimeBasedMicrocompact` 는 프로덕션 배선이 없으므로 deprecated 로 둔다. v1 쓰기 모드(§7.3)에서만 남는다.

### 3.4 봉인된 기록 읽기

**실행 루프와 뷰 계산은 봉인된 항목을 읽지 않는다.** §5.1 의 봉인 조건이 그것을 보장한다. 봉인된 항목을 읽는 것은
`SessionLogReader` 뿐이고, 그것은 manifest 가 가리키는 세그먼트만 읽는다.

읽기는 **페이지 단위**다 — `read` 는 한 번에 `maxReadTokens` 까지 돌려주고 다음 커서를 준다. 세션 전체 로그는 모델 창의
몇 배일 수 있어, 한 번에 메모리에 올리거나 한 LLM 호출에 싣는 것을 API 모양으로 막는다
([`context-engine.md` §7](../agent-execution/context-engine.md)).

---

## 4. 뷰 상태 — `SessionViewState`

```
SessionViewState
  summarySpan   : { fromSeq, toSeq, summaryText, boundaryId, boundaryMeta }   (0 또는 1개)
  droppedRanges : [ [fromSeq, toSeq) ... ]                                     (prompt-too-long 복구)
  elisions      : { seq → placeholder }                                         (L0 prune)
```

뷰 = `floorSeq` 이후의 로그에서

1. `droppedRanges` 의 항목을 뺀다
2. `summarySpan` 구간을 `[boundary, summary]` 마커 쌍으로 대체한다
3. `elisions` 의 도구 결과 본문을 placeholder 로 바꾼다 (tool_use id·error 플래그는 보존)

- **결정적이다** — 입력은 hot 항목과 뷰 상태뿐이다. span 안의 항목은 봉인되어 있어도 뷰 계산은 그것을 읽지 않는다 —
  요약문이 대신한다
- **마커 메타데이터는 span 에 저장한다** — `CompactBoundary` 의 경계 메시지는 `trigger`, `preTokenCount`,
  `messagesSummarized`, `discoveredToolNames` 를 담는다. 뷰 계산 때 로그에서 다시 모으면 봉인된 구간을 읽게 된다. 압축 시
  `boundaryMeta` 로 저장하고, span 이 넓어질 때 누적 갱신한다
- **span 이 하나인 이유** — 요약이 여럿이면 순서·중첩을 관리해야 하고, 누적 갱신이 한 요약을 전제한다. 새 압축은 이전
  span 을 흡수해 넓어진다
- **모든 절단면은 합법이어야 한다** — `droppedRanges` 와 span 의 경계에서 `tool_use`/`tool_result` 짝이 갈라지면 안 된다.
  뷰 상태를 바꾸는 쪽([`context-engine.md` §3.4](../agent-execution/context-engine.md))이 지킨다

뷰 상태를 바꾸는 것은 둘이다. **뷰를 줄이는** 연산은 `ContextEngine` 만 한다. **로그를 자르는** 두 경로(rewind, `/clear`,
§6)는 잘린 seq 를 가리키던 뷰 상태를 정리한다.

---

## 5. 봉인

### 5.1 무엇을 봉인할 수 있나

seq `s` 는 **뷰에 원문으로 나타나지 않을 때** — `summarySpan` 안, 또는 `droppedRanges` 안 — 봉인할 수 있다. 뷰 계산이 봉인된
항목을 읽지 않게 하는 조건이고, 이것 하나뿐이다.

- **구간 단위** — 봉인은 prefix 가 아니다. 롤링 engine 의 head 는 뷰에 원문으로 영원히 남으므로 봉인되지 않고, 그 뒤의 span
  구간이 봉인된다. hot 항목은 seq 로 주소하는 정렬 구조이며 "`floorSeq` 이후에서 manifest 구간을 뺀 항목" 이다
- **rewind point 에서 끊는다** — 봉인 구간은 rewind point 를 걸치지 않는다. 걸치면 두 구간으로 나누어 봉인한다. 그래야 rewind
  가 세그먼트를 읽거나 쪼개지 않고 **manifest 항목 단위로** 버릴 수 있다(§6.1)
- **크기** — 연속한 봉인 가능 구간이 `minSealTokens`(기본 32K) 이상일 때만 봉인한다
- 압축하지 않는 세션은 봉인하지 않는다. 짧은 세션의 저장은 지금과 같다

rewind point 와 ingest mark 는 봉인 조건이 아니다. rewind 는 manifest 항목을 버리는 것으로 봉인된 구간을 자르고(§6.1), 실행 끝
ingest 는 봉인된 항목을 메모리에서 읽는다(§5.3).

### 5.2 manifest 가 유효성을 정한다

세그먼트는 **레코드의 manifest 가 가리킬 때만 존재한다.** 세그먼트 저장소에 있어도 manifest 에 없으면 고아이고, 아무도
읽지 않으며, GC 가 지운다(§5.4).

- `segmentId` 는 봉인할 때마다 새로 만든 uuid 다. 두 노드가 같은 구간을 봉인해도 서로를 덮어쓰지 않는다
- manifest 항목은 `(fromSeq, toSeq, segmentId, contentHash, entryCount)` 다. 읽을 때 해시를 검증하고, 개수는 §6.2 가 쓴다

**펜싱** — 세그먼트 쓰기는 펜싱하지 않는다. lease 를 잃은 노드가 늦게 쓴 세그먼트는 새 홀더의 manifest 에 없으므로 무효다.
transcript 쓰기 자체가 펜싱되는지는 어셈블리가 주는 `SessionRecordStore` 에 달려 있다 — `AimonStackBuilder` 는 spec 의 레코드
저장소를 그대로 `DefaultTranscriptManager` 에 준다. 이 설계는 그것에 기대지 않는다. 다만 펜싱되지 않은 늦은 레코드 쓰기가
**GC 가 이미 지운 세그먼트**를 가리키는 manifest 를 되살릴 수는 있다. 그 경우는 §5.5 의 읽기 규칙이 받는다.

### 5.3 언제·어디서 — 버퍼를 가진 스레드에서 동기로

봉인은 **그 턴을 돌리는 스레드**가 동기로 한다. 두 시점이 있다.

| 시점 | 이유 |
|------|------|
| 압축 직후 (iteration 경계, `ContextEngine` 이 뷰 상태를 바꾼 뒤) | 긴 단일 턴 동안에도 레코드가 창 크기 근처로 유지되게. 없으면 턴 중간 체크포인트가 그 턴의 로그 전체를 매번 다시 쓴다 — 지금 코드에서는 턴 중간 압축이 레코드를 줄이므로 회귀가 되고, Mongo 는 한 문서 16MB 한도에 닿는다 |
| 턴 끝 저장 직전 (`endTurn()` 뒤) | 턴 중간 봉인이 `minSealTokens` 에 못 미쳐 남긴 구간을 마저 |

```
봉인 (버퍼를 가진 스레드, 동기)
  ① 봉인 가능 구간 R 계산 (rewind point 에서 끊는다)
  ② segmentStore.put(newId, R 의 항목)             — 실패하면 R 은 봉인하지 않는다
  ③ 버퍼: hot 에서 R 을 빼고 manifest 에 (R, newId, …) 추가, dirty
       R 의 항목은 이 실행이 끝날 때까지 버퍼의 메모리에 남는다 (영속하지 않는다)
  ④ 다음 체크포인트 또는 턴 끝 저장이 레코드를 쓴다
```

- **버퍼를 다른 스레드에서 고치지 않는다.** 버퍼는 턴마다 레코드에서 새로 만들어진다(`DefaultTranscriptManager.initialize`).
  다른 스레드가 지난 턴의 버퍼를 고치면 그 버퍼가 체크포인트 slot 을 가로채 옛 상태로 레코드를 덮어쓸 수 있다. 버퍼를 가진
  스레드가 동기로 고치면 그런 버퍼가 없다. 세션의 턴은 직렬화되어 있으므로 봉인도 직렬화된다
- **ingest** — 실행 끝 ingest 는 메모리 안의 버퍼를 읽는다. ③ 은 R 의 항목을 영속 형태에서만 빼고 메모리에는 실행이 끝날
  때까지 남기므로, 봉인이 ingest 델타를 줄이지 않는다. 다음 턴의 버퍼는 레코드에서 만들어지므로 R 을 들고 있지 않다
- **저장 성공 신호가 필요 없다.** 저장이 실패하면 저장된 레코드는 R 을 hot 으로 들고 있고, ② 의 세그먼트는 고아다
- **`SessionCheckpointMailbox.disabled()` 조립**에서도 같다 — 봉인은 mailbox 와 무관하다
- **지연** — 봉인이 일어나는 iteration 에 세그먼트 쓰기 한 번이 더해진다. `minSealTokens` 때문에 압축마다는 아니다

| 실패 지점 | 레코드 | 세그먼트 저장소 | 결과 |
|-----------|--------|-----------------|------|
| ② 실패 | 그대로 | 없음 | 다음 시점에 다시 |
| ③ 후 저장 전 크래시, 또는 저장 실패 | 이전 레코드 (R 은 hot) | 고아 | 원본 무손상. 다음 봉인이 새 id 로 다시 |
| 체크포인트 `flush` 타임아웃 뒤 옛 스냅샷이 늦게 도착 | R 이 hot 으로 되돌아감 | 고아 | 원본 무손상. 다음 봉인이 다시 |
| 저장 성공 | manifest 가 가리킴 | 세그먼트 있음 | 정상 |

**레코드 크기** — hot 에 남는 것은 **뷰에 원문으로 보이는 부분**(head, tail, span 밖의 원문)과 `minSealTokens` 미만의 조각이다.
모델 context window 로 한정되므로 긴 단일 턴 동안에도 레코드 크기는 창 크기 근처다 — 지금 압축이 주는 상한과 같다. manifest
는 봉인 한 번에 한 줄이다.

### 5.4 GC

GC 는 고아 세그먼트를 지운다. 세 조건을 모두 만족할 때만 지운다.

- **그 세션의 주인만** — 세션을 연 노드가, 그 세션의 턴을 돌리지 않는 순간에(세션을 열 때, 턴 끝 저장 직후 같은 스레드에서)
  돈다. 턴 중간 봉인이 만든 세그먼트는 턴 끝 저장으로 manifest 에 실린 뒤에야 GC 를 만나므로 지워지지 않는다. 삭제는 `SessionStore` 가 주는 **펜싱된 삭제 뷰**로 한다.
  `SessionStore` 가 없는 조립(CLI, 단일 노드)은 lease 가 없으므로 원시 저장소로 지운다
- **저장된 레코드의 manifest 에 없다**
- **유예 기간** — `createdAt` 이 `segmentGcGrace`(기본 1시간, lease TTL 보다 충분히 길게) 이전. 다른 노드가 진행 중인 봉인
  (lease 를 잃었지만 아직 모르는 노드)의 세그먼트를 보호한다. 0 은 허용하지 않는다

### 5.5 읽기

`SessionLogReader` 는 manifest 가 가리키는 세그먼트만 읽는다. 세그먼트가 없거나 해시가 맞지 않으면 — §5.2 의 늦은 레코드
쓰기, 운영 사고 — **실패하지 않고 구멍을 보고한다.** 그 구간 대신 `[history unavailable: seq a..b]` 를 돌려주고 WARN 을
남긴다. 되찾기와 `SESSION_END` 는 기록의 일부를 잃을 수는 있어도 세션 전체를 잃으면 안 된다. 실행 루프는 봉인된 구간을
읽지 않으므로 구멍이 대화를 깨지 않는다.

### 5.6 SPI

```java
public interface SessionLogSegmentStore {
    void put(SessionLogSegment segment);                               // 새 id 만. 펜싱 없음
    Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id);
    List<SegmentInfo> list(SessionId sessionId);                        // (id, createdAt) — GC 용
    void delete(SessionId sessionId, SegmentId id);                     // 펜싱된 뷰로만
    void deleteAll(SessionId sessionId);                                // 세션 삭제
}
```

- **패키지** — `at.aimon.core.agent.session.store`. 예외는 `at.aimon.core.agent.session.exception`
- **펜싱된 삭제 뷰** — `SessionStore.segments(SessionLogSegmentStore raw)` 를 더한다. `records()` 와 같은 re-proof 로
  `delete`·`deleteAll` 을 감싼다
- **기본 구현** — `InMemorySessionLogSegmentStore`. 세션 백엔드 모듈이 구현체를 제공한다 — Mongo 컬렉션, Postgres 테이블,
  Redis 해시. 수명은 레코드와 같다(세션 삭제 시 `deleteAll`). **read-after-write** 가 필요하다
- **인코딩** — 레코드 transcript 와 같은 `JsonSessionSnapshotCodec` 의 메시지 인코딩. 백엔드는 불투명 문자열로 저장한다
- **저장 시 보호** — 레코드에 있던 데이터를 옮긴 것이다. 보호 수준은 레코드와 같으면 되고 약하면 안 된다

---

## 6. 로그를 줄이는 두 경로

로그는 압축으로 줄지 않지만, 사용자가 명시적으로 지우는 경로가 둘 있다. 둘 다 `SessionLogState` 의 연산으로 정의해,
버퍼 경로와 저장된 레코드 경로가 같은 규칙을 쓴다.

### 6.1 rewind — `truncateFrom(seq)`

중단된 턴을 버린다. 실제 경로는 `TranscriptBuffer.rewind()` 가 아니라 **`DefaultLiveSession.rewindLastTurn` →
`rewindPersistedTranscript`** 다(버퍼의 `rewind()` 는 main 소스에 호출자가 없다). 지금 그 경로는 저장된 레코드의 메시지를
인덱스로 잘라 새 `SessionSnapshot` 을 만든다. 봉인 뒤에는 인덱스가 seq 와 어긋나고, 새 스냅샷은 뷰 상태와 `nextSeq` 를
잃는다.

v2 에서는 두 경로 모두 `SessionLogState.truncateFrom(rewindPoint.seq)` 를 쓴다.

- hot 항목은 잘리고, **`fromSeq` 가 rewind point 이상인 manifest 항목은 버린다.** 봉인 구간은 rewind point 에서 끊겨
  있으므로(§5.1) 걸치는 항목이 없다. 세그먼트를 읽거나 쪼갤 필요가 없고, 버려진 세그먼트는 고아가 되어 GC 가 지운다
- `nextSeq` 는 그대로다 — seq 를 재사용하지 않는다
- 뷰 상태에서 잘린 seq 이상을 가리키는 것을 정리한다. `droppedRanges`·`elisions` 는 그 부분을 버린다
- `summarySpan` 이 rewind point 뒤에 통째로 있으면 버리고, **걸치면**(중단된 턴 안에서 롤링 압축이 일어난 경우) `toSeq` 를
  rewind point 로 자르고 요약은 그대로 둔다. 요약에 버려진 부분의 내용이 섞여 있을 수 있다 — 알려진 부정확함이다(§11)

### 6.2 `/clear` — `clear()`

지금 `/clear` 는 기록을 지운다. 그 뜻을 지킨다.

- hot 항목·manifest·뷰 상태를 비우고 `floorSeq = nextSeq` 로 둔다. seq 는 이어서 증가한다
- **읽기는 즉시 막힌다** — `SessionLogReader` 는 manifest 만 따르므로 지운 대화는 되찾기 도구로 보이지 않는다
- **삭제는 저장 뒤에** — 비운 레코드를 쓰는 턴 끝 저장이 성공하면, 같은 저장 경로가 clear 이전 manifest 의 세그먼트를
  펜싱된 삭제 뷰로 지운다. 저장이 먼저이므로 "레코드는 가리키는데 세그먼트가 없는" 상태가 생기지 않는다. 삭제가 실패하면
  남은 세그먼트는 고아이고 GC(§5.4)가 지운다
- `ClearCommand` 의 "Removed N messages" 는 **살아 있는 항목 수** — hot 항목 수 + manifest 의 `entryCount` 합 — 로 센다.
  `nextSeq − floorSeq` 는 rewind 로 잘린 seq 까지 센다

세션 삭제(`DefaultSessionRouter` 의 `records().delete`)는 레코드를 지운 뒤 `deleteAll` 을 부른다. 둘 사이에 실패하면
세그먼트가 남는데, 가리키는 레코드가 없으므로 고아다. 세션 단위 GC 는 레코드가 없는 세션을 볼 수 없으므로, 저장소 단위
정리(`list` 가 아니라 전체 스캔)를 운영 도구로 둔다(§11).

---

## 7. 저장 형식과 이행

### 7.1 형식

`JsonSessionSnapshotCodec` 은 이미 `version` 필드를 항상 쓴다(`FORMAT_VERSION = 1`)고, decode 는 다른 값이면 실패한다.
새 형식은 **`version: 2`** 이고 `SessionLogState` 를 담는다. 판별은 이 필드로 한다.

세션 백엔드는 transcript 를 불투명 문자열로 저장하므로 **백엔드의 레코드 저장 코드는 바뀌지 않는다.** 바뀌는 것은 core 의
레코드 타입 체인(§7.2)과, 새 세그먼트 저장소 구현이다.

같은 코덱을 **서브에이전트 resume 스냅샷**(`VfsSessionSnapshotStore`, 공유 파일 시스템)도 쓴다. 이 경로도 §7.3 의 쓰기
게이트를 따른다 — 게이트가 코덱에 있기 때문이다.

### 7.2 로드·저장 체인

v2 상태는 다음 타입을 지난다. 지금은 모두 `systemPrompt`·`messages`·`rewindPoint` 를 필드별로 복사한다.

| 방향 | 체인 |
|------|------|
| 로드 | 백엔드 → `SessionRecordCodec.decodeTranscript` → `StoredSessionRecord.Builder.transcript(SessionSnapshot)` → `SessionRecordView` → `SessionSnapshot.from(view)` → `TranscriptBuffer.fromSnapshot` |
| 저장 | `TranscriptBuffer.toSnapshot` → `SessionRecord.fromSnapshot` → `SessionTranscript` → `SessionRecordCodec.encodeTranscript` → 백엔드 |

- 각 타입은 `SessionLogState` 를 통째로 들고 넘긴다. 메시지 목록은 그 값의 뷰로 제공한다
- `SessionRecordView` 에는 트리 밖 구현이 있다([`session-model.md` §3.5](session-model.md)). 새 accessor 는 `default` 로
  더한다
- **인덱스 검증 두 곳**을 seq 의미로 다시 정의한다 — `SessionTranscript.of`/`withRewindPoint` 의 "`rewindPoint.getMessageCount()
  > messages.size()` 면 거부" 와 코덱 `decodeRewindPoint` 의 "`keep > messageCount` 면 거부". 그대로 두면 봉인 뒤 레코드 로드
  자체가 실패한다. v2 의 검증은 "`rewindPoint.seq` 가 `[floorSeq, nextSeq]` 안에 있고 어떤 manifest 구간에도 속하지 않는다" 다

### 7.3 이행

**v1 레코드 읽기** — `version: 1` 은 v1 으로 읽는다.

- 메시지는 seq `0..n-1`, `origin = CONVERSATION` 인 로그가 된다
- 뷰 상태는 비어 있다. v1 레코드에 남은 경계·요약 마커 메시지는 **로그의 평범한 메시지**로 남는다. 그 앞의 원문은 이미
  잃었다 — 이행이 되돌릴 수 없는 손실이다

**혼합 버전 클러스터** — 두 단계로 켠다.

1. **읽기** — 모든 노드가 v1·v2 를 읽을 수 있게 배포한다. 쓰기는 v1 이다
2. **쓰기** — 전 노드 배포가 끝난 뒤 쓰기를 v2 로 바꾼다

두 규칙을 더한다.

- **sticky upgrade** — v1 쓰기 모드의 노드도 **v2 로 읽은 레코드는 v2 로 쓴다.** 2단계로 바꾸는 과정 자체가 롤링 배포라,
  그 사이 v1 쓰기 노드가 v2 레코드를 v1 으로 다시 쓰면 뷰 상태·manifest·`nextSeq` 가 사라진다 — span 이 사라져 hot 원문이
  그대로 뷰가 되고, 봉인된 구간이 참조를 잃고, seq 가 0 부터 다시 시작한다
- **되돌리기** — v2 레코드가 하나라도 쓰인 뒤에는 1단계 이전 바이너리로 되돌리는 것을 **지원하지 않는다.** 그 바이너리는
  v2 를 읽지 못한다

v1 쓰기 모드에서 `DefaultContextEngine` 은 기록을 고쳐 쓰는 지금의 동작으로 물러난다. `RollingContextEngine` 은 v1 에서
구현할 수 없으므로 **v1 쓰기 모드에서 배선하면 기동 시 실패한다**.

---

## 8. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 위치 주소 | seq, 재사용 안 함 | **인덱스** — 로그가 봉인되고 잘리면 인덱스가 움직여 모든 위치 값을 다시 매핑해야 한다 |
| 레코드 쪽 상태의 형태 | 불변 값 `SessionLogState` 하나 | **기존 타입에 필드 추가** — 로드·저장 체인이 필드별로 복사하므로 한 홉만 빠져도 조용히 사라진다 |
| 항목의 출처 | 로그 항목의 `origin` | **`Message` 메타데이터** — 모든 `LlmClient` 변환과 코덱에 번진다. **텍스트 태그** — mid-turn 사용자 메시지를 오분류한다 |
| `getMessages()` 의 뜻 | hot 항목 (공개 API 의미 변경) | **전체 기록** — 봉인된 항목을 매번 읽어야 해 실행 루프가 세그먼트 저장소에 의존한다. **뷰** — 호출자 대부분이 뷰를 원하지 않는다 |
| 레코드 크기 | 구간 단위 봉인 | **로그 전체를 레코드에** — 체크포인트 쓰기량이 세션 길이의 제곱. **prefix 봉인** — 롤링의 head 가 원문으로 남아 봉인이 한 걸음도 나아가지 못한다 |
| 세그먼트 유효성 | 레코드의 manifest 가 정한다 | **`(sessionId, fromSeq)` 결정적 id + 멱등 put** — 같은 노드가 넓어진 구간을 다시 봉인하거나 두 노드가 다른 구간을 봉인하면 같은 id 에 다른 내용이 생긴다 |
| 세그먼트 쓰기 펜싱 | 하지 않는다 | **lease 증명 후 쓰기** — 증명과 쓰기 사이의 틈은 닫히지 않는다(`SessionStore` 가 명시). manifest 가 늦은 쓰기를 무해하게 만든다 |
| 봉인 조건에 "저장 완료" | 넣지 않는다 | **마지막 저장 seq 이하만 봉인** — 체크포인트 콜백은 성공을 돌려주지 않고 실패를 삼키며, 저장 경로가 셋이다. manifest 모델에서는 저장이 실패해도 항목이 레코드에 남으므로 필요 없다 |
| 봉인 시점 | 버퍼를 가진 스레드에서 동기로 — 압축 직후와 턴 끝 | **비동기 executor** — 버퍼는 턴마다 새로 만들어지므로, 지난 턴의 버퍼에 결과를 적용하면 그 버퍼가 체크포인트 slot 을 가로채 레코드를 옛 상태로 덮어쓴다. **턴 끝에만** — 긴 단일 턴 동안 레코드가 그 턴 전체를 들어, 턴 중간 압축이 레코드를 줄이는 지금보다 나빠진다. **체크포인트 writer** — 앱 전체에 하나다 |
| rewind 와 봉인 | 봉인 구간을 rewind point 에서 끊고, rewind 는 manifest 항목을 버린다 | **rewind point 이후는 봉인하지 않음** — 현재 턴이 통째로 봉인 대상에서 빠져 위의 "턴 끝에만" 과 같은 결과가 된다 |
| ingest mark 를 봉인 조건으로 | 넣지 않는다 | 봉인된 항목은 실행이 끝날 때까지 메모리에 남는다. ingest 는 메모리 안의 버퍼를 읽는다 |
| 세그먼트 누락 시 읽기 | 구멍을 보고한다 | **실패** — 펜싱 없는 늦은 레코드 쓰기가 지워진 세그먼트를 가리킬 수 있다. 기록 일부의 손실이 세션 전체의 실패가 되면 안 된다 |
| `/clear` | 저장 성공 뒤 같은 경로에서 삭제, 실패분은 GC | **저장 전 삭제** — 저장이 실패하면 레코드가 없는 세그먼트를 가리킨다. **GC 에만 맡김** — 지운 대화가 유예 기간 동안 저장소에 남는다 |
| 펜싱된 삭제 뷰 | `SessionStore.segments(...)` 추가 | **세그먼트 저장소가 lease 를 직접 확인** — 펜싱 규칙이 두 곳에 생긴다 |
| 세그먼트 저장소 | 새 SPI | **`SessionRecordStore` 확장** — 레코드 저장소는 "세션당 한 행" 을 전제로 원자성을 설계했다 |
| 혼합 버전 | 2단계 + sticky upgrade | **한 번에 전환** — v1 노드가 v2 레코드를 읽지 못한다. **sticky 없이 2단계** — 전환 중 v1 쓰기가 v2 상태를 지운다 |

---

## 9. 하지 말 것

- **실행 루프에서 봉인된 로그를 읽지 않는다.** 필요해 보이면 봉인 조건이 틀린 것이다. 조건을 고친다
- **manifest 에 없는 세그먼트를 읽지 않는다.** 저장소에 있다는 것은 유효하다는 뜻이 아니다
- **버퍼를 다른 스레드에서 고치지 않는다.** 봉인은 그 턴을 돌리는 스레드가 동기로 한다
- **봉인 구간이 rewind point 를 걸치게 하지 않는다.** rewind 가 세그먼트를 쪼개야 하게 된다
- **봉인된 항목을 실행 중에 메모리에서 버리지 않는다.** 실행 끝 ingest 가 그것을 읽는다
- **GC 유예 기간을 0 으로 두지 않는다.** 다른 노드가 진행 중인 봉인의 세그먼트를 지운다
- **세그먼트가 없다고 읽기를 실패시키지 않는다.** 구멍을 보고한다
- **seq 를 재사용하지 않는다.** rewind 와 `/clear` 뒤에도 `nextSeq` 에서 잇는다
- **로그를 고쳐 쓰지 않는다.** 로그를 줄이는 것은 `truncateFrom`(rewind)과 `clear` 뿐이다. 뷰를 줄이고 싶으면 뷰 상태를 바꾼다
- **`SessionLogState` 를 필드별로 복사하지 않는다.** 체인의 어느 홉에서든 통째로 넘긴다
- **v1 쓰기 노드가 v2 레코드를 v1 으로 쓰지 않는다.** sticky upgrade 를 끄지 않는다
- **세그먼트를 레코드보다 약하게 보호하지 않는다.** 같은 데이터다

---

## 10. 영향받는 공개 API

| API | 변화 |
|-----|------|
| `TranscriptBuffer.getMessages()` | 의미 변경 — hot 항목 (§3.3) |
| `AgentExecutionResult.getConversationHistory()`, `SessionSnapshot.getConversationHistory()` | 같은 의미 변경. 봉인이 일어난 세션에서는 전체 대화가 아니다 |
| `TranscriptBuffer.replaceWith`, `replaceMessageAt` | v2 쓰기 모드에서 쓰지 않는다. deprecated |
| `SessionStore` | `segments(...)` 추가 |
| `SessionRecordView` | `getLogState()` default accessor 추가 |
| `SessionRewindPoint` | 개수가 아니라 seq 를 든다 |

---

## 11. 열린 질문

- **rewind 를 걸친 span** — 요약에 버려진 턴의 내용이 섞이는 부정확함(§6.1)을 받아들일지, 넓히기 전 요약을 하나 더 들고
  있다가 되돌릴지
- **긴 실행의 메모리** — 봉인된 항목은 실행이 끝날 때까지 메모리에 남는다(§5.3). 영속 크기는 창 크기로 묶이지만 한 실행의
  메모리는 그 실행의 크기다. 문제가 되면 ingest 를 실행 중간에도 흘려보내 그 부분을 메모리에서 놓을지
- **저장소 단위 고아 정리** — 레코드 삭제와 `deleteAll` 사이 실패로 남은 세그먼트를 찾는 운영 도구의 형태
- **세그먼트 병합** — `minSealTokens` 로 부족하면 작은 세그먼트를 합칠지. 합치면 manifest 한 줄이 바뀐다
- **v1 쓰기 모드의 수명** — §7.3 의 물러난 동작을 언제 지울지

---

## 부록 A. `TranscriptBuffer` 소비자와 이행

| 호출자 | 지금 읽는 뜻 | v2 |
|--------|-------------|----|
| `OrcaAgentExecutor.invokeGatewayOnce` (동기·스트리밍) | 뷰 | `ContextEngine.prepare` 의 뷰 |
| `OrcaAgentExecutor.invokeGateway` (복구) | 뷰 | `ContextEngine.recover` |
| `OrcaAgentExecutor` 압축 게이트 전후 `size()` → `emitCompactBoundary` | 뷰 크기 | engine 이 돌려주는 뷰 크기 — 로그 크기는 압축해도 변하지 않는다 |
| `OrcaAgentExecutor.maybeInjectUserContextMessage` (재개 여부) | 사용자 메시지 수 | `SessionLogState.hasConversation()` — `floorSeq` 이후 살아 있는 `CONVERSATION` USER 항목이 있는가. `truncateFrom` 이 갱신한다. `nextSeq > floorSeq` 로 판정하면 첫 턴이 중단되어 rewind 된 새 세션에서 재시도가 CTX-06 블록을 받지 못한다 |
| `OrcaAgentExecutor` → `AgentExecutionResult.getConversationHistory()` | 대화 전체 | hot 항목 (§10) |
| `DefaultCompactionGuard.evaluate` / `preconditionMet` | 뷰 | engine 내부의 뷰 |
| `DefaultCompactionEngine` (`getMessages`, `replaceWith`) | 뷰 | 요약만 만든다. 교체는 뷰 상태 연산 |
| `TimeBasedMicrocompact` | 뷰 | 배선 없음. deprecated |
| `CompactCommand` (`getMessages().isEmpty()`) | 뷰 | 뷰가 비었는지 |
| `ClearCommand` ("Removed N messages") | 전체 | 살아 있는 항목 수 (§6.2) |
| `DefaultCommandExecutionManager` (`previousSnapshot`) | 전체 | 스냅샷은 `SessionLogState` 를 든다 |
| CLI `AgentSetupFactory` (`SESSION_END` 최종 derivation) | 전체 | `SessionLogReader` 페이지 읽기 |
| `DefaultLiveSession.rewindPersistedTranscript` | 인덱스 | `SessionLogState.truncateFrom` (§6.1) |
| `SessionRecord.rewind` / `SessionTranscript.rewind` | 인덱스 | 같음 |
| `SessionTranscript.of` 검증, 코덱 `decodeRewindPoint` | 인덱스 ≤ size | seq 범위 검증 (§7.2) |
| `DefaultSubagentExecutor` (호출, `toSnapshot`/`fromSnapshot`) | 뷰 = 전체 | 호출은 engine 의 뷰. 포크는 세션이 없어 봉인하지 않으므로 스냅샷은 전체와 같다 |
| `LlmSkillExecutor` 두 호출 | 스크래치 버퍼 | engine 의 뷰 (`passthrough`) |
| `RecentFilesRestoreHook`, `InvokedSkillsRestoreHook` | (append) | `addSyntheticMessage` |
| OnStart advisory 피드백, `executeCommandFlow` 의 assistant 응답 | (append) | `SYNTHETIC` (§3.2) |

---

## 부록 B. 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 기록 | [`TranscriptBuffer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptBuffer.java) |
| 영속 값 | [`SessionTranscript.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionTranscript.java), [`SessionSnapshot.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionSnapshot.java), [`SessionRecord.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecord.java) |
| 인코딩 | [`SessionRecordCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordCodec.java), [`JsonSessionSnapshotCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/task/codec/JsonSessionSnapshotCodec.java) |
| 저장 경로 | [`DefaultTranscriptManager.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/DefaultTranscriptManager.java), [`SessionCheckpointMailbox.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionCheckpointMailbox.java) |
| rewind | [`DefaultLiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/DefaultLiveSession.java) |
| 펜싱 | [`SessionStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionStore.java) |
| 어셈블리 | [`AimonStackBuilder.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java) |
| 백엔드 | [`PostgresSessionRecordStore.java`](../../../modules/aimon-session-postgres/src/main/java/at/aimon/session/postgres/PostgresSessionRecordStore.java), [`MongoSessionRecordStore.java`](../../../modules/aimon-session-mongodb/src/main/java/at/aimon/session/mongodb/MongoSessionRecordStore.java) |

---

## 12. 구현과의 차이

§1 ~ §11 과 부록은 리뷰를 통과한 설계 그대로다(`f09d891`). 이 절은 구현이 그 글에서 갈라진 자리와, 구현을 마친 뒤에도
열려 있는 결과를 적는다. 뷰를 만드는 쪽의 차이는
[`context-engine.md` §13](../agent-execution/context-engine.md#13-구현과의-차이) 에 있다.

### 12.1 로그와 저장 형식 (§3, §4, §7)

- **쓰기 형식은 codec 이 아니라 로그 상태가 든다.** §7.1 은 쓰기 게이트를 codec 에 두었지만, 백엔드는 모두 정적
  `SessionRecordCodec` 으로 부르고 `DefaultContextEngine` 도 형식을 알아야 한다(뷰 모드냐 in-place 냐). 그래서
  `SessionLogState.getFormat()` 이 "이 상태가 적어도 이 형식으로 쓰여야 한다" 를 말하고, codec 은
  `max(쓰기 형식, 상태 형식)` 으로 쓴다. sticky upgrade 가 여기서 저절로 나온다 — `version: 2` 에서 읽은 상태는 `V2` 를
  들고 언제나 `V2` 로 쓰인다. 노드 스위치는 `DefaultTranscriptManager(store, mailbox, SessionLogFormat)` 이고(내주는 버퍼를
  전부 그 형식으로 올린다) `JsonSessionSnapshotCodec(SessionLogFormat)` 이 `VfsSessionSnapshotStore` 를 덮는다
- **스위치의 노출.** `SessionSpec.logWriteFormat(...)`, Spring `aimon.session.log-write-format`,
  `OrcaAgentRuntimeFactory.withSessionLogWriteFormat(...)`. **CLI 에는 없다** — CLI 는 언제나 v1 로 쓴다
- **뷰 상태 타입의 자리.** `SessionViewState` · `SummarySpan` · `SeqRange` · `LegalCuts` 는 `agent.session.transcript` 에
  있고, 연산(`summarize` / `drop` / `elide`)은 합법 절단 불변식이 항목을 필요로 하므로 `SessionLogState` 에 있다. 버퍼는
  `summarizeView` / `dropFromView` / `elideInView` 로 그것을 비춘다. 투영(`ViewProjection`)은 `agent.context` 에 있다 —
  마커를 만드는 `CompactBoundary` 가 `agent.compact` 에 있어 여기 두면 순환이 생긴다. `SummarySpan` 은 trigger 를
  `CompactionTrigger` 의 이름(문자열)으로, 경계 메타데이터(`boundaryId` · `preTokenCount` · `messagesSummarized` ·
  `discoveredToolNames`)와 함께 저장한다. 비어 있지 않은 뷰 상태는 V2 를 요구한다
- **v2 문서의 `viewState`** 는 객체(`summarySpan` · `droppedRanges` · `elisions`)이고 비어 있지 않을 때만 쓴다. 없으면 빈
  것으로 읽는다
- **로그를 받는 팩토리의 이름**은 `SessionTranscript.fromLog(...)` · `SessionSnapshot.fromLog(...)` 다. `of(..., null)` 을
  넘기는 기존 호출이 두 오버로드 사이에서 모호해진다
- **`SessionRewindPoint`.** `of(long seq, …)` 는 같은 이름으로 남았고 `getMessageCount()` 는 `getSeq()` 로 바뀌었다
  ([`../../migration/rename-maps.md`](../../migration/rename-maps.md)). `int` 를 넘기던 호출은 컴파일되지만 이미 컴파일된
  호출자에게는 바이너리 비호환이다. rewind 검증 문구는 "rewind seq 가 `[floorSeq, nextSeq]` 안" 이 되었다
- **v1 in-place 압축의 seq.** v1 쓰기(와 [`context-engine.md` §13.2](../agent-execution/context-engine.md#132-defaultcontextengine-의-뷰-모드-4-82) 의 폴백)가 쓰는 `replaceWith` 는 교체 항목에 `nextSeq` 부터 새 seq 를 주고
  `floorSeq` 를 그 첫 seq 로 올린다. 교체된 항목과 같은 인스턴스는 origin 을 유지하고 나머지는 CONVERSATION 이다.
  manifest 는 새 floor 아래로 내려가므로 지운다 — 세그먼트는 GC 의 고아가 된다
- **SYNTHETIC.** 서브에이전트의 OnStart advisory feedback(`DefaultSubagentExecutor.fireOnStart`)도 SYNTHETIC 으로 붙는다.
  §3.2 목록은 메인 루프만 적었지만 이유가 같다. 훅 개발 가이드에는 `PostCompactContext` 절이 없어서 새로 썼다
- **공개 API 추가.** `SessionRecordView.getLogState()` 는 default 메서드이고 `SessionStore.segments(raw)` 는 **추상** 메서드다
  (트리 밖 구현은 컴파일이 깨진다 — `0.x` 에서 허용되고 CHANGELOG 에 있다)

### 12.2 봉인 (§5)

- **봉인의 자리.** §5.3 은 executor 가 "버퍼의 봉인" 을 부른다고 했지만 버퍼에는 저장소가 없다. 일은 `SessionLogSealer` 가
  하고, `DefaultTranscriptManager` 가 `SessionLogStorage`(세그먼트 저장소, 삭제 뷰, `minSealTokens`, `segmentGcGrace`,
  `maxReadTokens`, 추정기, clock)를 받을 때 그것을 소유한다. `TranscriptManager` 에 default 메서드 `seal(buffer)`(no-op)와
  `getLogReader()`(empty)가 생겼고, executor 는 `COMPACT` 의 경계 이벤트 뒤에 `seal` 을 부른다
- **턴 종료 순서는 seal → `mailbox.flush` → 저장이다.** §5.3 은 "봉인한 뒤 저장" 만 말하고 체크포인트 barrier 를 말하지
  않는다. 봉인이 성공하면 버퍼가 dirty 가 되어 체크포인트를 올리므로, flush 뒤에 봉인하면 그 체크포인트가 barrier 밖에
  줄 서서 권위 있는 저장 **뒤에** 쓰인다 — 삭제된 세션을 되살리고, 영속된 rewind 를 되돌린다. 순서를 바꾸어 봉인이 올린
  체크포인트가 저장 전에 비워진다. 대가는 무언가를 봉인한 턴 종료 저장마다 같은 상태의 백그라운드 쓰기 한 번이다
- **payload 는 쓴 그대로 해시한다.** `SessionLogSegment.payload` 는 `SessionLogSegmentCodec`(→
  `JsonSessionSnapshotCodec.encodeEntries` / `decodeEntries`)이 쓴 문자열이고, `contentHash` 는 그 문자열의
  `sha256:<hex>` 다 — 다시 인코딩한 항목이 아니다. 메시지 타입을 거친 왕복은 손실이 없지만 바이트 동일을 약속하지 않는다.
  manifest 한 줄의 타입 이름은 `SessionLogManifestEntry` 다
- **manifest 불변식은 `SessionLogState` 가 강제한다**(codec 은 "Inconsistent session log" 로 거절한다). 줄은 정렬 · 서로소 ·
  `[floorSeq, nextSeq)` 안이고, carried 항목은 봉인 구간에 없고, 모든 줄은 뷰 상태가 가리며(`SessionViewState.hidesRange`),
  비어 있지 않은 manifest 는 V2 이고, rewind point 는 줄의 **안쪽**에 있을 수 없다. 줄의 `fromSeq` 와 같은 rewind point 는
  허용한다 — 바로 "rewind 지점에서 나눈다" 의 경우이고 `truncateFrom` 이 그 줄을 통째로 버린다
- **`hasConversation()` 은 manifest 줄도 센다.** 전 이력이 봉인된 세션이 새 세션처럼 보여 다음 턴이 CTX-06 블록을 다시
  받지 않게 하려는 것이다
- **ingest 를 위한 보존.** 봉인된 항목은 버퍼의 private 목록으로 옮겨진다(영속하지 않고 `getMessages()` 에도 없다).
  `messagesSinceIngestMark()` 가 그것을 합치므로 실행 중 봉인이 실행 종료 델타를 줄이지 않는다.
  `getConversationMessages()` 는 carried 항목만이고, CLI 의 session-end 경로는 `SessionLogReader` 로 읽는다
- **reader 의 모양.** `read(sessionId, from, to)` 는 레코드를 읽고 `read(sessionId, state, from, to)` 는 손에 든 로그를
  읽는다(실행 중인 턴의 `SessionHistoryTool`). 페이지는 합법 절단에서만 끝나고 항목을 하나 이상 든다. gap 은 두 번
  돌아온다 — 구간의 첫 seq 에 SYNTHETIC user 항목 `[history unavailable: seq a..b]` 로, 그리고 `getGaps()` 로
- **페이지 상한은 답 없는 호출이 있어도 걸린다.** 크래시로 결과가 쓰이지 않은 `tool_use` 가 있으면 그 뒤의 어떤 위치도
  합법 절단이 아니어서, 절단 조건을 그대로 두면 나머지 로그가 한 페이지로 온다. 그래서 페이지가
  `HARD_LIMIT_FACTOR`(2) × `maxReadTokens` 에 닿으면, 열린 호출이 남아 있어도 tool result 로 시작하지 않는 다음 위치에서
  자른다. 정상 로그에서는 닿지 않는다 — 호출의 결과는 바로 뒤에 오고, result 는 페이지의 첫 항목이 되지 않는다
- **세그먼트는 호출 단위 캐시로 한 번만 읽는다.** `SessionLogReadCache` 를 받는 `read(sessionId, state, from, to, cache)` 와
  `SessionLogSource.readRange(...)` 가 생겼다. 한 번의 작업(도구 호출 하나)이 만든 캐시를 여러 창·페이지에 넘기면 같은
  세그먼트를 한 번만 가져오고 해시 검사하고 디코드한다. 읽지 못한 세그먼트도 기억하므로 WARN 은 작업당 한 번이다. 작업을
  넘어 두지 않는다 — manifest 가 바뀌면 GC 가 지운 세그먼트를 캐시가 붙들고 있게 된다
- **백엔드.** Postgres 는 `V2__session_log_segment.sql`(운영자가 `V1__init.sql` 뒤에 적용)이고, README 가 미래 인덱스용으로
  예약해 둔 이름은 `V3__indexes.sql` 로 밀렸다. Redis 는 세션당 해시 둘 — `<prefix>:{s:<sid>}:data`(id → payload 를
  든 JSON)와 `<prefix>:{s:<sid>}:created`(id → createdAt) — 이라 `list` 가 payload 를 끌어오지 않고, Lua 스크립트가 둘을
  맞춘다. 중괄호는 hash tag 라서 두 키가 Redis Cluster 의 한 슬롯에 들고, 두 키 스크립트와 `DEL` 이 `CROSSSLOT` 을 내지
  않는다. tag 는 고정 표식 `s:` 로 시작한다 — Redis 는 첫 `{` 와 그 뒤 첫 `}` 사이가 비면 키 **전체**를 해시하므로,
  `{<sid>}` 만으로는 `}` 로 시작하는 id 의 두 키가 서로 다른 슬롯에 떨어진다. 접미사가 세션 id **뒤**에 고정되어
  있으므로 한 세션의 키가 다른 세션의 키가 될 수 없다 — 처음의
  `<prefix>:<sid>` / `<prefix>:<sid>:created` 는 세션 `X:created` 의 data 키가 세션 `X` 의 created 키와 같았다(첫 배포 전에
  바꿨다). 기본 prefix `aimon:session:segment` 와 이 키 모양은 동결 테스트에 걸려 있고, `{` 가 든 prefix 는 모든 세션을 한
  슬롯에 몰아넣으므로 거절한다. Mongo 의 `_id` 는 `{sessionId, segmentId}` 쌍이다 — 세그먼트 id 는 세션 범위라서 세그먼트
  id 하나만 `_id` 로 두면 다른 세션의 같은 id 를 E11000 으로 거절한다. `list` · `deleteAll` 은 여전히 최상위 `sessionId`
  와 `by_session` 인덱스로 간다. 세 백엔드 모두 세션 안의 중복 id 의 `put` 을 거절하고, 계약 테스트가 "두 세션의 같은 id"
  와 "다른 세션 id 를 늘인 세션 id(`X` 와 `X:created`)" 를 모든 백엔드에 돌린다
- **in-memory 세그먼트 저장소의 `put`** 은 바깥 맵의 `compute` 안에서 넣는다. 세션의 마지막 세그먼트를 지우는 `delete` 가
  안쪽 맵을 떼어 내는 순간 그 맵에 쓰던 `put` 은 세그먼트를 잃고, 그 세그먼트를 가리키는 manifest 는 gap 이 된다
- **조립의 짝.** `SessionSpec.segmentStore(...)` 가 생겼다. 없을 때 in-memory 레코드 저장소는 in-memory 세그먼트 저장소와
  짝을 이루고, 공급된 레코드 저장소는 세그먼트 저장소 없이 **아무것도 봉인하지 않는다** — 영속 manifest 뒤에 재시작하면
  사라지는 세그먼트를 두면 봉인 구간이 전부 gap 이 된다. 이때 쓰기 형식이 v2 이면 스택이 `session-log-sealing`
  degradation 을 남긴다(v1 은 제자리에서 압축하므로 아니다). Spring 스타터는 `SessionLogSegmentStore` 빈을 읽어 넘긴다 —
  속성은 없고 빈이 선택자다

### 12.3 GC 와 두 삭제 경로 (§5.4, §6)

- **GC 는 턴 종료 저장 뒤에만 돈다.** §5.4 의 "세션을 열 때" 는 하지 않았다. 세션 열기(`DefaultTranscriptManager.initialize`)
  는 턴마다 일어나므로 저장 뒤 한 번과 겹치고 `list` 호출만 두 배가 된다. grace 는 0 이하를 거절하고 기본은 1시간이다
- **조립된 스택의 레코드 쓰기와 GC · `/clear` 삭제는 펜스 뷰로 간다.** §5.4 · §6.2 는 `SessionStore` 가 있으면 펜스 뷰
  (`SessionStore.segments(raw)`)를 쓰라고 한다. 그 뷰를 가진 `SessionStore` 는 `SessionRouterBuilder.build()` 안에서 transcript
  manager 보다 **뒤에** 만들어지므로, bootstrap 은 manager 에 `LateBoundFencedRecordStore`(레코드 저장소 자리)와
  `LateBoundFencedSegmentStore`(`deleteStore` 자리)를 주고, 라우터가 생긴 뒤 `SessionRouter.fencedRecordStore(fence)` ·
  `fencedSegmentStore(fence)` 에 묶는다. 라우터 opener 가 만드는 라이브 세션의 레코드 쓰기(누계 · 예산 · 영속 rewind)도 같은
  뷰로 간다. 묶이기 전의 쓰기 · 삭제는 raw 로 떨어지지 않고 실패한다 — 그때는 턴이 돌 수 없으므로 도달하면 배선 결함이다.
  **어떤 펜스를 거는지는 스택의 모양이 정한다**(`SessionFence`, `agent.session.store`):

  | 스택 | 펜스 | 왜 |
  |---|---|---|
  | 분산 모드 | `HOLDER_ONLY` — 이 노드가 쥔 세션만 | 라이브 세션은 모두 라우터가 claim 한 뒤 열린다. 쥐지 않은 세션의 쓰기는 리스를 잃은 노드다 |
  | 단일 노드 + 리스 저장소를 준 경우 | `UNLESS_HELD_ELSEWHERE` — 다른 노드가 쥔 세션만 거절 | durable 리스 저장소는 두 번째 노드가 이 노드의 세션을 쥘 수 있게 만든다. 반면 라우터 밖에서 만든 라이브 세션(CLI 의 것)은 리스를 쥐지 않으므로, 아무도 쥐지 않은 세션은 통과시켜야 GC 가 돈다 |
  | 단일 노드 + 기본 리스 저장소 | 없음 — raw | 리스 저장소가 이 프로세스의 것이므로 다른 홀더가 생길 수 없다. 기존 동작 그대로 |

  라우터의 세션 삭제는 처음부터 `HOLDER_ONLY` 뷰를 쓴다. `SessionStore` javadoc 이 적은 "재증명과 쓰기 사이의 창" 은 두 펜스
  모두에 남는다 — 닫으려면 레코드 백엔드가 펜싱 compare-and-set 을 해야 한다
- **거절된 저장은 턴을 깨지 않는다.** 턴 끝의 `saveSilently` 는 `SessionNotHeldException` 을 WARN 한 줄로 남기고 돌아온다 —
  잃는 것은 리스를 잃으면서 이미 잃은 그 턴의 끝이다. 거절된 저장 뒤에는 GC 도 돌지 않는다(수집 기준이 될 manifest 가 레코드의
  것이 아니다). 체크포인트 거절은 턴이 끝날 때까지 반복되므로 DEBUG 로, 펜스가 거절한 GC 삭제도 DEBUG 로 남긴다 — 둘 다
  결함이 아니라 펜스가 일하는 모습이다. 던지는 `save` 경로는 예외를 그대로 올린다
- **`/clear`** 는 잘려 나간 줄의 세그먼트 id 를 버퍼에 쌓아 두고, 비운 레코드의 저장이 성공한 **뒤에만** 지운다. 저장이
  실패하면 남겨 두고 GC 가 나중에 줍는다
- **rewind.** `TranscriptBuffer.rewind()` 와 `SessionLogState.truncateFrom` 은 `fromSeq` 가 절단 이후인 줄을 통째로 버린다.
  `DefaultLiveSession` 의 영속 rewind 는 인덱스가 아니라 저장된 로그에 `truncateFrom` 을 건다. 봉인된 v2 레코드 — rewind
  point 에서 끝나는 줄과 거기서 시작하는 줄, 그리고 그 점을 걸친 span — 에서 `rewindLastTurn` 부터 재시도까지를 끝에서
  끝까지 고정하는 테스트(`SealedLogRewindIntegrationTest`)가 있다. 앞의 줄은 남고 뒤의 줄은 버려지며 span 은 그 점으로
  잘리고, reader 는 gap 없이 읽고, 재시도는 요약을 보고 멈춘 시도의 항목은 보지 않는다. 새 결함은 드러나지 않았다

### 12.4 알려진 열린 결과

구현을 마친 시점에 남아 있는 행동상의 틈이다. 열림/닫힘의 정본은
[`../../backlog/session-log-open-items.md`](../../backlog/session-log-open-items.md) 다.

- **다시 열리지 않는 세션의 고아는 스윕을 켜야 줍는다.** GC 가 턴 종료 저장 뒤에만 돌기 때문에(§12.3) 다음 턴이 오지 않으면
  GC 는 줍지 않는다 — 영속된 `rewindLastTurn` 이 끊어 낸 세그먼트, 실패한 `/clear` 삭제, 저장되지 못한 봉인, flush timeout
  뒤 늦게 도착한 옛 스냅샷이 남긴 고아. 저장소 단위 스윕(§12.6)이 그것을 줍지만 **opt-in** 이다. 켜지 않은 배포에서는 여전히
  남는다
- **펜스는 재증명과 쓰기 사이의 창을 닫지 않는다.** 리스를 잃은 노드의 레코드 쓰기와 삭제는 이제 거절된다(§12.3). 그러나
  재증명(`findHolder`)과 위임된 쓰기 사이의 창은 남고, `UNLESS_HELD_ELSEWHERE` 는 **아무도 쥐지 않은 순간의** 늦은 쓰기를
  통과시킨다 — 바로 뒤에 claim 한 노드가 그 쓰기보다 먼저 레코드를 읽을 수 있다. 닫으려면 레코드 백엔드의 펜싱
  compare-and-set 이 필요하고, 이 설계 밖이다
- **늦은 체크포인트가 지운 메시지를 되살리는 문제는 남는다.** `/clear` 이전 스냅샷을 든 체크포인트가 저장소 호출 안에 걸린 채
  `/clear` 의 저장을 넘기면, 그것이 착지하는 순간 레코드는 지운 메시지를 다시 든다. 다음 쓰기(곧 이어지는 체크포인트나 다음
  턴의 저장)가 그것을 다시 비운다. 이제 **gap 은 만들지 않는다** — drain 이 포기한 저장 뒤에는 아무것도 지우지 않으므로(§12.7)
  되살아난 manifest 가 가리키는 세그먼트는 그대로 있다
- **GC 의 grace 는 두 노드의 시계를 비교한다.** 세그먼트의 `createdAt` 은 봉인한 노드의 시계이고 비교는 수집하는 노드의
  시계로 한다. skew 만큼 grace 가 늘거나 준다. 1시간에서는 무해하지만 `segmentGcGrace` 를 줄이는 운영자는 알아야 한다

### 12.5 구현 뒤 개선에서 닫힌 것

리뷰가 남긴 항목 중 저장 쪽에서 고친 것이다. 위 절들의 본문도 그에 맞게 고쳤다.

- **v2 레코드가 in-place 폴백을 만나도 manifest 를 잃지 않는다.** 뷰 상태나 manifest 가 있는 v2 버퍼는 in-place 로 압축하지
  않는다 — [`context-engine.md` §13.6](../agent-execution/context-engine.md#136-구현-뒤-개선에서-닫힌-것)
- **큰 세그먼트를 창·페이지마다 다시 디코드하지 않는다.** 호출 단위 `SessionLogReadCache`(§12.2)
- **reader 의 페이지 상한이 답 없는 `tool_use` 뒤에도 걸린다**(§12.2)
- **Redis 키 충돌과 Cluster 슬롯, Mongo 의 세션 범위 `_id`, in-memory `put` 의 경합**(§12.2)

### 12.6 두 번째 개선에서 닫힌 것

구현 뒤 개선의 두 번째 묶음이다. 위 절들의 본문도 그에 맞게 고쳤다.

- **조립된 스택의 삭제 펜스.** §12.3 첫 항목. `SessionRouter.fencedSegmentStore()` 가 새 공개 표면이고, 라우터는 그 뷰를 한
  번만 만들어 자기 세션 삭제에도 쓴다. 리스를 빼앗긴 노드의 늦은 GC 가 거절되는 것을 조립된 스택 위에서 고정하는 테스트
  (`AimonStackSegmentFencingTest`)가 있다
- **저장소 단위 고아 스윕 — §11 의 세 번째 질문에 대한 답.** `SessionLogSegmentSweeper`(`agent.session.transcript`)가
  세그먼트 저장소 전체를 훑는다. 세그먼트를 지우는 조건은 둘이다. **grace 보다 오래됐고**, 목록을 읽은 **뒤에** 읽은 레코드의
  manifest 가 가리키지 않거나 레코드가 없을 것. 레코드를 읽지 못하면(백엔드 실패, 코덱이 거절한 문서) 그 세션은 이번 회차에
  건너뛴다. 스위퍼는 세션을 쥐지 않으므로 펜스 뷰는 모든 삭제를 거절한다. 그래서 raw 로 지우고, 안전은 GC 와 같은 두
  조건(manifest 확인 + grace)에 기댄다. 여러 노드가 동시에 돌려도 안전하다 — 서로의 일을 반복할 뿐이다. 반복의 비용은 §12.7
  의 스윕 리스가 없앤다. grace 기본은
  **24시간**이다. GC 의 1시간보다 긴 이유는, 스위퍼는 홀더가 아니어서 봉인과 그것을 가리키는 레코드 쓰기 사이의 시간을
  가장 길게 잡아야 하기 때문이다
- **SPI 에 `scanSessions(createdBefore, cursor, limit)` 를 더했다.** 결과는 `SegmentScanPage` 이고 커서는 불투명하다. 계약은
  일부러 느슨하다. 한 회차 안에서 오래된 세그먼트를 가진 세션을 **빠뜨리지만 않으면** 된다. 더 많이 돌려주거나, 같은 세션을
  두 번 돌려주거나, `limit` 을 힌트로 다뤄도 된다. in-memory · Postgres · Mongo 는 정확하다(나이로 거르고, 세션 id 오름차순,
  커서는 마지막 id). Redis 는 `SCAN` 으로 `:created` 키를 훑는다. 세션별 키와 **같은 슬롯에 둘 수 없는** 색인 키를 따로 두지
  않으려는 선택이다. 그래서 나이로 거르지 않고, 중복을 허용하며, `COUNT` 는 힌트다. 이 클래스가 받는 standalone 연결에서는
  그 노드의 키 공간이 전체다 — Redis Cluster 는 §12.7. 계약 테스트(`AbstractSessionLogSegmentStoreContractTest`)가 네 백엔드 모두에서 돈다 — 이상한
  세션 id(`X:created`, glob 문자, 중괄호)와 `limit` 1 짜리 페이징을 포함한다
- **배선은 opt-in 이다.** `SessionSpec.segmentSweepInterval(...)` / `segmentSweepGrace(...)`, Spring 은
  `aimon.session.segment-sweep-interval` / `aimon.session.segment-sweep-grace`. 간격이 없으면 꺼져 있다. 스택은
  `SegmentSweepSchedule`(daemon 스레드 하나)을 만들고, 다른 백그라운드 스위퍼와 함께 `startRuntimes()` 에서 시작하며,
  `SESSIONS` 단계에서 라우터보다 먼저 닫는다. 첫 회차는 간격 하나를 기다린다. grace 만 있고 간격이 없는 설정, 그리고 세그먼트
  저장소 없이 레코드 저장소만 준 설정(봉인되는 것이 없어 스윕할 것도 없다)은 조립 시점에 거절한다
- **CLI 의 쓰기 형식 스위치.** `cli.sessionLogWriteFormat: v2`. v2 면 CLI 는 in-memory 레코드 옆에 in-memory 세그먼트
  저장소를 붙인다 — 둘이 같은 프로세스와 함께 사라지므로 manifest 가 세그먼트보다 오래 살 수 없다. 기본은 v1 이고,
  `context-engine: rolling` 에이전트는 v2 에서만 뜬다 —
  [`context-engine.md` §13.7](../agent-execution/context-engine.md#137-두-번째-개선에서-닫힌-것)

### 12.7 세 번째 개선에서 닫힌 것

[`../../backlog/session-log-open-items.md`](../../backlog/session-log-open-items.md) 의 SL-1…SL-5 다. 위 절들의 본문도 그에 맞게
고쳤다.

- **레코드 쓰기의 펜스 (SL-1).** §12.3 첫 항목. 분산 모드에서 리스를 빼앗긴 노드의 늦은 턴 종료 저장이 거절되고 새 홀더의
  레코드가 남는 것을 조립된 스택 위에서 고정하는 테스트(`AimonStackSegmentFencingTest.staleHolderIsRejected`)가 있다 — 이전에는
  "삭제는 거절, 저장은 성공" 을 고정하던 테스트다. 새 공개 표면은 `SessionFence`, `SessionStore.records(fence)` ·
  `segments(raw, fence)`(기본 구현은 `HOLDER_ONLY` 만 답하고 다른 펜스는 거절한다), `SessionRouter.fencedRecordStore(fence)` ·
  `fencedSegmentStore(fence)` 다
- **리스 저장소를 준 단일 노드의 펜스 (SL-2).** §12.3 의 표. 규칙은 "리스 저장소가 **다른** 홀더를 가리키면 거절" 이다.
  CLI 처럼 라우터 밖에서 연 세션은 아무도 쥐지 않으므로 저장도 GC 도 전과 같고, 두 번째 노드가 쥔 세션만 막힌다. 기본 리스
  저장소의 단일 노드는 펜스가 없다 — 동작이 한 줄도 바뀌지 않는다
- **클러스터에 한 번만 도는 스윕 (SL-3).** `SessionLogSegmentSweeper.Builder.coordination(leaseStore, holderId, lease)` 를 주면
  `sweepIfClaimed()` 가 예약 id `aimon:segment-sweep`(`SWEEP_LEASE_ID`) 의 리스를 먼저 잡고, 못 잡으면 그 회차를 건너뛴다. 새
  SPI 를 만들지 않고 이미 있는 `SessionLeaseStore` 를 썼다 — 리스는 만료와 펜싱 토큰을 이미 갖고 있고, 네 백엔드가 모두
  구현한다. 리스는 **스윕 간격 하나만큼** 잡고, 페이지마다 연장하며, 회차가 끝나도 **놓지 않는다**. 놓으면 일정이 어긋난 다른
  노드가 같은 간격 안에 다시 돌기 때문이다. 쥔 노드가 죽으면 연장이 멈추고, 만료 뒤 처음 틱하는 노드가 이어받는다. 리스
  저장소에 닿지 못하면 그 회차는 건너뛴다(다음 회차는 간격 하나 뒤다). 스택은 리스 저장소를 받았을 때 자동으로 조정하고,
  holder id 는 `nodeId`(없으면 임의의 id)다
- **Redis Cluster 스캔 (SL-4).** `RedisSessionLogSegmentStore` 가 `StatefulRedisClusterConnection` 을 받는 생성자를 갖는다.
  세션 단위 명령은 해시 태그가 가리키는 슬롯으로 가고, `scanSessions` 는 마스터를 노드 id 순으로 하나씩 `SCAN` 한다. 커서는
  `<nodeId>:<nodeCursor>` 다 — Lettuce 의 클러스터 스캔은 위치를 커서 **객체**에 두므로 `scanSessions` 두 호출 사이에 문자열로
  넘길 수 없다. 스캔 중에 노드가 빠지면 순서상 다음 마스터로 넘어간다(그 회차는 옮겨 간 슬롯의 키를 놓칠 수 있다 — `SCAN`
  자체의 주의사항이고, 다음 회차가 본다). 노드별 순회는 가짜 노드로 단위 테스트(`KeyspaceScannerTest`)하고, 마스터 둘짜리 실제
  Cluster(Testcontainers, `redis:7-alpine` 둘을 `CLUSTER MEET` 하고 슬롯을 반씩 준 것)에서 계약 테스트 전체와 "두 마스터의
  세션을 모두 보고한다" 를 돌린다(`RedisClusterSessionLogSegmentStoreIntegrationTest`)
- **`/clear` 뒤 늦은 체크포인트 (SL-5).** 체크포인트를 거절하는 길은 고르지 않았다 — 문제의 체크포인트는 스냅샷을 찍은 뒤
  저장소 호출 **안에서** 걸려 있으므로, 쓰기 전의 어떤 검사도 이미 지나간 뒤다. 대신 삭제를 미룬다.
  `SessionCheckpointMailbox.drain(sessionId)` 가 새로 생겼고(`flush` 는 그 답을 버리는 같은 동작이다), **이 세션의 옛 상태
  쓰기가 아직 착지할 수 있는지**를 답한다 — 대기 중인 것이 없었거나 barrier 에 닿았거나 writer 가 멈췄으면 `true`, 시간 초과 ·
  인터럽트 · 살아 있는 버려진 writer 면 `false`. transcript manager 는 `false` 인 저장 뒤에 `/clear` 삭제도 GC 도 하지 않는다.
  `/clear` 삭제는 버퍼에 남아 drain 이 끝나는 다음 저장에서 다시 시도된다. 그래서 늦게 착지한 옛 manifest 가 가리키는
  세그먼트는 그대로 있고 gap 이 생기지 않는다. drain 시간 제한은 `SessionCheckpointMailbox.background(Duration)` 으로 줄 수
  있다(기본 5초). 백그라운드 mailbox 로 이 순서를 끝에서 끝까지 고정하는 테스트가 있다
  (`DefaultTranscriptManagerSealingTest.aClearWhoseDrainGaveUpDefersItsDeletesUntilALateCheckpointCannotLand`)
- **스위퍼의 "목록 뒤에 레코드" 순서를 고정하는 테스트.** 목록을 읽는 동안 레코드가 세그먼트를 가리키게 되는 가짜로, 그
  세그먼트가 남는 것을 확인한다(`SessionLogSegmentSweeperTest.readsTheRecordAfterListing`). 스위퍼 javadoc 에는
  `SessionRecordView.getLogState()` 를 재정의하지 않는 레코드 저장소가 모든 세그먼트를 쓸어 버린다는 요구를 적었다
