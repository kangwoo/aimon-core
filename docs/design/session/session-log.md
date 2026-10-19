# Session Log — 기록과 뷰의 분리

**Status**: IMPLEMENTED — `aimon-core`(`agent.session.transcript`, `agent.session.store`), `aimon-bootstrap`(조립·스윕 배선),
`aimon-session-{mongodb,postgres,redis}`(세그먼트 저장소 구현). 남은 것은 [마지막 절](#남은-것).

이 저장 모델 위에서 LLM 에 보낼 뷰를 만드는 쪽은 [`../agent-execution/context-engine.md`](../agent-execution/context-engine.md) 다.
레코드 자체는 [`session-model.md`](session-model.md), 레코드 쓰기의 펜스는 [`session-store.md`](session-store.md) §5 가 정본이다.

---

## 1. 무엇을 푸는가

전사를 LLM 에 보이는 대화 그대로 저장하면, 압축과 prompt-too-long 복구가 그것을 고쳐 쓰므로 뷰를 줄이는 일이 곧 기록을
지우는 일이 된다. 원문이 사라지고, 압축된 실행의 메모리 ingest 가 비고, 요약문이 원문처럼 ingest 된다
([`context-engine.md` §1.1](../agent-execution/context-engine.md)).

그래서 기록은 **append-only 로그**로 두고, 뷰를 줄이는 정보는 로그 옆의 **뷰 상태**에 둔다. 로그는 압축으로 줄지 않고, 뷰
상태는 로그를 가리키기만 한다.

그 대가로 레코드 크기의 상한이 사라진다. 로그가 줄지 않으면 체크포인트가 매번 쓰는 레코드가 세션 길이에 비례해 자라고, 쓰기
총량은 세션 길이의 제곱이 된다. 그래서 이 설계의 절반은 **뷰에 원문으로 나타나지 않는 로그 구간을 레코드 밖으로 봉인(seal)
하는** 규칙이다(§5).

---

## 2. 모양

```
SessionRecord.transcript  =  systemPrompt + SessionLogState
  SessionLogState
    ├─ format          V1 | V2  (이 상태가 적어도 이 형식으로 쓰여야 한다 — §9.1)
    ├─ nextSeq, floorSeq
    ├─ hot entries     (seq, message, origin)    — 봉인되지 않은 항목, seq 오름차순
    ├─ manifest        [SessionLogManifestEntry(fromSeq, toSeq, segmentId, contentHash, entryCount)]
    ├─ viewState       SessionViewState          — context-engine 이 바꾼다
    └─ rewindPoint     seq 기반

SessionLogSegmentStore  (레코드 밖)
  segment  (sessionId, segmentId) → 불변 항목 목록
```

| 값 | 수명 | 어디 |
|----|------|------|
| 로그 항목 | session | 봉인 전에는 레코드, 봉인 후에는 세그먼트 저장소 |
| manifest · 뷰 상태 · rewind point | session | 레코드 |
| ingest mark | 한 실행 | 노드 로컬, 영속하지 않음 |

**하나의 불변 값.** 레코드 쪽 상태 전부를 `SessionLogState` 하나로 묶는다. 전사는 로드·저장 체인의 여러 타입(§9.2)을 지나는데,
필드별로 골라 복사하면 새 필드는 한 홉에서라도 빠지는 순간 조용히 사라진다. 한 값이면 체인은 통째로 넘기기만 한다.

**한 레코드.** 로그 · manifest · 뷰 상태를 한 번의 저장으로 원자적으로 바꾸기 위해서다. 뷰 상태가 레코드에 없는 seq 를
가리키거나, 레코드가 없는 세그먼트를 가리키는 순간이 생기지 않는다.

---

## 3. 로그

### 3.1 seq

로그 항목은 인덱스가 아니라 **seq** 로 주소를 갖는다. seq 는 세션마다 0 부터 단조 증가하고 **재사용하지 않는다** — rewind 로
잘리거나 `/clear` 로 지워진 뒤에도 다음 항목은 `nextSeq` 에서 이어진다.

로그를 가리키는 값은 전부 seq 다 — rewind point, ingest mark, 뷰 상태의 span · 범위 · elision, manifest, 도구의 placeholder.
로그가 압축으로 줄지 않으므로 이 값들은 다시 매핑될 일이 없다. 잘린 seq 를 다시 쓰면 이미 LLM 에 나간 placeholder 나 다른
노드의 manifest 가 다른 메시지를 가리키게 된다.

### 3.2 항목의 출처 — `origin`

항목은 `(seq, message, origin)` 이다. `origin` 은 이 메시지가 대화인지 런타임이 넣은 것인지를 말한다.

| origin | 예 | ingest · 되찾기 |
|--------|----|-----------------|
| `CONVERSATION` | 사용자 입력, assistant 응답, 도구 결과, mid-turn 사용자 메시지 | 포함 |
| `SYNTHETIC` | 조립된 컨텍스트(`injectAssembledUserContext`), OnStart 훅의 advisory 피드백(메인 루프와 서브에이전트), 명령 흐름이 남기는 assistant 응답, 압축 복원 훅이 붙인 파일·스킬 목록 | **제외** |

출처는 메시지가 아니라 **로그 항목**에 둔다. `Message` 에는 메타데이터 자리가 없고, 넣으면 모든 `LlmClient` 변환과 코덱이 영향을
받는다. 텍스트 태그로 판별하면 `<system-reminder key="user-mid-turn-message">` 로 감싸인 mid-turn 사용자 메시지를 오분류한다.

`addMessage(Message)` 와 `addUserMessage(...)` 는 `CONVERSATION` 을 뜻하고, 런타임 주입 경로는 `SYNTHETIC` 오버로드를 쓴다.
외부 훅이 `PostCompactContext.getTranscriptBuffer()` 로 받은 버퍼에 append 하면 기본값 `CONVERSATION` 이 되므로, 훅 컨텍스트는
`addSyntheticMessage(...)` 를 함께 노출한다. v1 에서 옮겨 온 항목은 판별 근거가 없으므로 전부 `CONVERSATION` 이다.

### 3.3 세 가지 읽기

"레코드에 있는 메시지 = LLM 뷰 = 기록" 이 셋으로 갈라지므로 읽는 API 도 셋이다.

| API | 뜻 | 쓰는 곳 |
|-----|----|---------|
| `TranscriptBuffer.getMessages()` | hot 항목, `floorSeq` 이후 | 레코드 안의 것만 보면 되는 곳 — 서브에이전트 스냅샷(포크는 봉인하지 않으므로 전체와 같다), 명령의 이전 스냅샷 |
| `SessionLogReader.read(...)` | 봉인 포함 전체 기록 | 되찾기 도구(`SessionHistoryTool`), CLI 의 `SESSION_END`, 운영 도구 |
| `ContextEngine.prepare(...)` 의 뷰 | LLM 에 보낼 것 | LLM 호출 자리 |

`getMessages()` 는 봉인이 돌면 앞에서부터 줄어든다 — 기록도 뷰도 아닌 "레코드 안의 부분" 이다. 이 뜻은 공개 API
`AgentExecutionResult.getConversationHistory()` · `SessionSnapshot.getConversationHistory()` 에도 같다(§11).

### 3.4 봉인된 기록 읽기

**실행 루프와 뷰 계산은 봉인된 항목을 읽지 않는다.** §5.1 의 봉인 조건이 그것을 보장한다. 봉인된 항목을 읽는 것은
`SessionLogReader` 뿐이고, 그것은 **manifest 가 가리키는 세그먼트만** 읽는다.

- **모양.** `read(sessionId, from, to)` 는 저장된 레코드를 읽고, `read(sessionId, state, from, to)` 는 손에 든 로그를 읽는다(실행
  중인 턴의 되찾기 도구)
- **페이지 단위.** 한 번에 `maxReadTokens` 까지 돌려주고 다음 커서를 준다. 세션 전체 로그는 모델 창의 몇 배일 수 있으므로 한
  번에 메모리에 올리거나 한 LLM 호출에 싣는 것을 API 모양으로 막는다. 페이지는 합법 절단에서만 끝나고 항목을 하나 이상 든다
- **하드 상한.** 크래시로 결과가 쓰이지 않은 `tool_use` 가 있으면 그 뒤의 어떤 위치도 합법 절단이 아니어서 나머지 로그가 한
  페이지로 온다. 그래서 페이지가 `maxReadTokens` 의 2배에 닿으면 열린 호출이 남아 있어도 tool result 로 시작하지 않는 다음
  위치에서 자른다. 정상 로그에서는 닿지 않는다
- **호출 단위 캐시.** 한 번의 작업(도구 호출 하나)이 만든 `SessionLogReadCache` 를 여러 창·페이지에 넘기면 같은 세그먼트를 한
  번만 가져오고 해시 검사하고 디코드한다. 읽지 못한 세그먼트도 기억하므로 WARN 은 작업당 한 번이다. 작업을 넘어 두지 않는다 —
  manifest 가 바뀌면 GC 가 지운 세그먼트를 캐시가 붙들게 된다
- **구멍.** 세그먼트가 없거나 해시가 맞지 않으면 실패하지 않고 구멍을 보고한다. 그 구간의 첫 seq 에 SYNTHETIC user 항목
  `[history unavailable: seq a..b]` 를 넣고 `getGaps()` 로도 알리며 WARN 을 남긴다. 되찾기와 `SESSION_END` 는 기록의 일부를 잃을
  수는 있어도 세션 전체를 잃으면 안 된다. 실행 루프는 봉인된 구간을 읽지 않으므로 구멍이 대화를 깨지 않는다

---

## 4. 뷰 상태 — `SessionViewState`

```
SessionViewState
  summarySpan   : { fromSeq, toSeq, summaryText, boundaryId, trigger, preTokenCount,
                    messagesSummarized, discoveredToolNames }        (0 또는 1개)
  droppedRanges : [ [fromSeq, toSeq) ... ]                            (prompt-too-long 복구)
  elisions      : { seq → placeholder }                               (L0 prune)
```

뷰 = `floorSeq` 이후의 로그에서

1. `droppedRanges` 의 항목을 뺀다
2. `summarySpan` 구간을 `[boundary, summary]` 마커 쌍으로 대체한다
3. `elisions` 의 도구 결과 본문을 placeholder 로 바꾼다(tool_use id · error 플래그는 보존)

- **결정적이다.** 입력은 hot 항목과 뷰 상태뿐이다. span 안의 항목은 봉인되어 있어도 뷰 계산은 그것을 읽지 않는다 — 요약문이
  대신한다
- **마커 메타데이터는 span 에 저장한다.** `CompactBoundary` 의 경계 메시지가 담는 값을 뷰 계산 때 로그에서 다시 모으면 봉인된
  구간을 읽게 된다. 압축할 때 span 에 저장하고, span 이 넓어질 때 누적 갱신한다. trigger 는 `CompactionTrigger` 의 이름(문자열)이다
- **span 은 하나다.** 요약이 여럿이면 순서·중첩을 관리해야 하고, 누적 갱신이 한 요약을 전제한다. 새 압축은 이전 span 을 흡수해
  넓어진다
- **모든 절단면은 합법이어야 한다.** `droppedRanges` 와 span 의 경계에서 `tool_use`/`tool_result` 짝이 갈라지면 안 된다

뷰를 **줄이는** 연산은 `ContextEngine` 만 한다([`context-engine.md`](../agent-execution/context-engine.md)). 로그를 **자르는**
두 경로(§8)는 잘린 seq 를 가리키던 뷰 상태를 정리한다.

타입의 자리: `SessionViewState` · `SummarySpan` · `SeqRange` · `LegalCuts` 는 `agent.session.transcript` 에 있다. 연산(`summarize` ·
`drop` · `elide`)은 합법 절단 불변식이 항목을 필요로 하므로 `SessionLogState` 에 있고, 버퍼는 `summarizeView` / `dropFromView` /
`elideInView` 로 그것을 비춘다. 투영(`ViewProjection`)은 `agent.context` 에 있다 — 마커를 만드는 `CompactBoundary` 가
`agent.compact` 에 있어 transcript 에 두면 순환이 생긴다. 비어 있지 않은 뷰 상태는 V2 를 요구한다.

---

## 5. 봉인

### 5.1 무엇을 봉인할 수 있나

seq `s` 는 **뷰에 원문으로 나타나지 않을 때** — `summarySpan` 안, 또는 `droppedRanges` 안 — 봉인할 수 있다. 뷰 계산이 봉인된
항목을 읽지 않게 하는 조건이고, 이것 하나뿐이다.

- **구간 단위.** 봉인은 prefix 가 아니다. 롤링 engine 의 head 는 뷰에 원문으로 영원히 남으므로 봉인되지 않고, 그 뒤의 span 구간이
  봉인된다. hot 항목은 "`floorSeq` 이후에서 manifest 구간을 뺀 항목" 이다
- **rewind point 에서 끊는다.** 봉인 구간은 rewind point 를 걸치지 않는다. 걸치면 두 구간으로 나누어 봉인한다. 그래야 rewind 가
  세그먼트를 읽거나 쪼개지 않고 manifest 항목 단위로 버릴 수 있다(§8.1)
- **크기.** 연속한 봉인 가능 구간이 `minSealTokens`(기본 32K) 이상일 때만 봉인한다
- 압축하지 않는 세션은 봉인하지 않는다. 짧은 세션의 저장은 봉인이 없던 모델과 같다

rewind point 와 ingest mark 는 봉인 조건이 아니다. rewind 는 manifest 항목을 버리는 것으로 봉인된 구간을 자르고, 실행 끝 ingest 는
봉인된 항목을 메모리에서 읽는다(§5.3).

### 5.2 manifest 가 유효성을 정한다

세그먼트는 **레코드의 manifest 가 가리킬 때만 존재한다.** 저장소에 있어도 manifest 에 없으면 고아이고, 아무도 읽지 않으며, GC 가
지운다(§7).

- `segmentId` 는 봉인할 때마다 새로 만든 uuid 다. 두 노드가 같은 구간을 봉인해도 서로를 덮어쓰지 않는다
- `contentHash` 는 세그먼트 payload 문자열(`SessionLogSegmentCodec` 이 쓴 것)의 `sha256:<hex>` 다 — 다시 인코딩한 항목이 아니다.
  메시지 타입을 거친 왕복은 손실이 없지만 바이트 동일을 약속하지 않는다. 읽을 때 검증한다
- **불변식은 `SessionLogState` 가 강제한다**(코덱은 "Inconsistent session log" 로 거절한다). 줄은 정렬 · 서로소 ·
  `[floorSeq, nextSeq)` 안이고, hot 항목은 봉인 구간에 없고, 모든 줄은 뷰 상태가 가리며(`SessionViewState.hidesRange`), 비어 있지
  않은 manifest 는 V2 이고, rewind point 는 줄의 안쪽에 있을 수 없다. 줄의 `fromSeq` 와 같은 rewind point 는 허용한다 — "rewind
  지점에서 나눈다" 의 경우이고 `truncateFrom` 이 그 줄을 통째로 버린다

**세그먼트 쓰기는 펜싱하지 않는다.** 리스를 잃은 노드가 늦게 쓴 세그먼트는 새 홀더의 manifest 에 없으므로 무효다. 레코드 쓰기는
조립된 스택에서 펜스 뷰를 지나지만([`session-store.md`](session-store.md) §5), 펜스에는 남는 창이 있으므로 늦은 레코드 쓰기가
GC 가 이미 지운 세그먼트를 가리키는 manifest 를 되살릴 수 있다. 그 경우는 §3.4 의 구멍 보고가 받는다.

### 5.3 언제 · 어디서 — 버퍼를 가진 스레드에서 동기로

봉인은 **그 턴을 돌리는 스레드**가 동기로 한다. 시점은 둘이다.

| 시점 | 이유 |
|------|------|
| 압축 직후 — executor 가 `COMPACT` 경계 이벤트 뒤에 `TranscriptManager.seal(buffer)` 를 부른다 | 긴 단일 턴 동안에도 레코드가 창 크기 근처로 유지되게. 없으면 턴 중간 체크포인트가 그 턴의 로그 전체를 매번 다시 쓰고, Mongo 는 한 문서 16MB 한도에 닿는다 |
| 턴 끝 저장 직전 | 턴 중간 봉인이 `minSealTokens` 에 못 미쳐 남긴 구간을 마저 |

```
봉인 (버퍼를 가진 스레드, 동기)
  ① 봉인 가능 구간 R 계산 (rewind point 에서 끊는다)
  ② segmentStore.put(newId, R 의 항목)             — 실패하면 R 은 봉인하지 않는다
  ③ 버퍼: hot 에서 R 을 빼고 manifest 에 줄 추가, dirty
       R 의 항목은 이 실행이 끝날 때까지 버퍼의 메모리에 남는다 (영속하지 않는다)
  ④ 다음 체크포인트 또는 턴 끝 저장이 레코드를 쓴다

턴 종료:  seal  →  mailbox.flush  →  저장  →  GC
```

일은 `SessionLogSealer` 가 한다. `DefaultTranscriptManager` 가 `SessionLogStorage`(세그먼트 저장소, 삭제 뷰, `minSealTokens`,
`segmentGcGrace`, `maxReadTokens`, 토큰 추정기, clock)를 받을 때 그것을 소유한다. `TranscriptManager.seal(buffer)` 와
`getLogReader()` 는 default 메서드(no-op / empty)다.

- **턴 종료 순서는 seal → flush → 저장이다.** 봉인이 성공하면 버퍼가 dirty 가 되어 체크포인트를 올린다. flush 뒤에 봉인하면 그
  체크포인트가 barrier 밖에 줄 서서 권위 있는 저장 **뒤에** 쓰인다 — 삭제된 세션을 되살리고 영속된 rewind 를 되돌린다. 대가는
  무언가를 봉인한 턴 끝 저장마다 같은 상태의 백그라운드 쓰기 한 번이다
- **버퍼를 다른 스레드에서 고치지 않는다.** 버퍼는 턴마다 레코드에서 새로 만들어진다(`DefaultTranscriptManager.initialize`).
  다른 스레드가 지난 턴의 버퍼를 고치면 그 버퍼가 체크포인트 slot 을 가로채 옛 상태로 레코드를 덮어쓸 수 있다. 세션의 턴은
  직렬화되어 있으므로 버퍼를 가진 스레드의 봉인도 직렬화된다
- **ingest 를 줄이지 않는다.** 봉인된 항목은 버퍼의 private 목록으로 옮겨진다(영속하지 않고 `getMessages()` 에도 없다).
  `messagesSinceIngestMark()` 가 그것을 합치므로 실행 중 봉인이 실행 끝 ingest 델타를 줄이지 않는다. 다음 턴의 버퍼는 레코드에서
  만들어지므로 R 을 들고 있지 않다
- **저장 성공 신호가 필요 없다.** 저장이 실패하면 저장된 레코드는 R 을 hot 으로 들고 있고, ② 의 세그먼트는 고아다
- 체크포인트를 끈 조립(`SessionCheckpointMailbox.disabled()`)에서도 같다 — 봉인은 mailbox 와 무관하다

| 실패 지점 | 레코드 | 세그먼트 저장소 | 결과 |
|-----------|--------|-----------------|------|
| ② 실패 | 그대로 | 없음 | 다음 시점에 다시 |
| ③ 후 저장 전 크래시, 또는 저장 실패 | 이전 레코드(R 은 hot) | 고아 | 원본 무손상. 다음 봉인이 새 id 로 다시 |
| 체크포인트 `flush` 타임아웃 뒤 옛 스냅샷이 늦게 도착 | R 이 hot 으로 돌아감 | 고아 | 원본 무손상. 다음 봉인이 다시 |
| 저장 성공 | manifest 가 가리킴 | 세그먼트 있음 | 정상 |

**레코드 크기.** hot 에 남는 것은 뷰에 원문으로 보이는 부분(head, tail, span 밖의 원문)과 `minSealTokens` 미만의 조각이다. 모델
context window 로 한정되므로 긴 단일 턴 동안에도 레코드 크기는 창 크기 근처다. manifest 는 봉인 한 번에 한 줄이다.

---

## 6. 세그먼트 저장소 SPI

```java
public interface SessionLogSegmentStore {
    void put(SessionLogSegment segment);                                   // 세션 안에서 새 id 만. 펜싱 없음
    Optional<SessionLogSegment> get(SessionId sessionId, SegmentId id);
    List<SegmentInfo> list(SessionId sessionId);                            // (id, createdAt) — GC 용
    void delete(SessionId sessionId, SegmentId id);                         // 펜스 뷰로만
    void deleteAll(SessionId sessionId);                                    // 세션 삭제
    SegmentScanPage scanSessions(Instant createdBefore, String cursor, int limit);  // 저장소 단위 스윕
}
```

- **패키지** — `at.aimon.core.agent.session.store`. 예외는 `agent.session.exception.SessionLogSegmentStoreException`
- **펜스 뷰** — `SessionStore.segments(raw)` / `segments(raw, fence)` 가 `records()` 와 같은 재증명으로 `delete` · `deleteAll` 을
  감싼다([`session-store.md`](session-store.md) §5)
- **세그먼트 id 는 세션 범위다.** 모든 백엔드가 세션 안의 중복 id 의 `put` 을 거절하고, 다른 세션의 같은 id 는 받는다
- **인코딩** — 레코드 전사와 같은 `JsonSessionSnapshotCodec` 의 항목 인코딩(`encodeEntries` / `decodeEntries`). 백엔드는 불투명
  문자열로 저장한다. 백엔드별 저장 모양은 [`backends.md`](backends.md) §6
- **수명** — 레코드와 같다. read-after-write 가 필요하다
- **보호 수준** — 레코드에 있던 데이터를 옮긴 것이므로 레코드와 같으면 되고 약하면 안 된다
- **`scanSessions` 의 계약은 일부러 느슨하다.** 한 회차 안에서 오래된 세그먼트를 가진 세션을 빠뜨리지만 않으면 된다 — 더 많이
  돌려주거나, 같은 세션을 두 번 돌려주거나, `limit` 을 힌트로 다뤄도 된다. 커서는 불투명하다
- **기본 구현** — `InMemorySessionLogSegmentStore`. 세션의 마지막 세그먼트를 지우는 `delete` 와 경합하지 않게 `put` 은 바깥 맵의
  `compute` 안에서 넣는다
- 계약 스위트 `AbstractSessionLogSegmentStoreContractTest` 를 네 구현이 모두 돈다 — 이상한 세션 id(`X:created`, glob 문자, 중괄호)와
  `limit` 1 짜리 페이징을 포함한다

**조립의 짝.** 세그먼트 저장소 없이 레코드 저장소만 공급하면 **아무것도 봉인하지 않는다** — 영속 manifest 뒤에 재시작하면
사라지는 세그먼트를 두면 봉인 구간이 전부 구멍이 된다. in-memory 레코드 저장소는 in-memory 세그먼트 저장소와 짝을 이룬다.

| 조립 | 세그먼트 저장소 | 레코드 저장소만 + v2 일 때 |
|------|----------------|---------------------------|
| `SessionSpec` | `segmentStore(...)` | 봉인하지 않고 `session-log-sealing` degradation 을 남긴다(v1 은 제자리 압축이므로 아니다) |
| Spring 스타터 | `SessionLogSegmentStore` 빈(속성 없음, 빈이 선택자) | **기동 실패**. 애플리케이션의 `InMemorySessionRecordStore` 빈만은 in-memory 세그먼트 저장소와 짝지어 준다 |
| CLI | v2 면 in-memory 레코드 옆에 in-memory 세그먼트 저장소 | — (둘이 같은 프로세스와 함께 사라지므로 manifest 가 세그먼트보다 오래 살 수 없다) |

---

## 7. 고아 정리

### 7.1 GC — 세션 단위

GC 는 세 조건을 모두 만족하는 세그먼트만 지운다.

- **그 세션의 주인만, 턴 끝 저장 뒤에.** 턴 끝 저장이 성공한 직후 같은 스레드에서 돈다. 턴 중간 봉인이 만든 세그먼트는 턴 끝
  저장으로 manifest 에 실린 뒤에야 GC 를 만나므로 지워지지 않는다. 세션 열기에서는 돌지 않는다 — 열기
  (`DefaultTranscriptManager.initialize`)는 턴마다 일어나므로 저장 뒤 한 번과 겹치고 `list` 호출만 두 배가 된다. 거절된 저장
  뒤에는 돌지 않는다 — 수집 기준이 될 manifest 가 레코드의 것이 아니다
- **저장된 레코드의 manifest 에 없다**
- **유예 기간이 지났다.** `createdAt` 이 `segmentGcGrace`(기본 1시간, lease TTL 보다 충분히 길게) 이전. 리스를 잃었지만 아직 모르는
  노드가 진행 중인 봉인의 세그먼트를 보호한다. 0 이하는 거절한다

삭제는 펜스 뷰로 한다([`session-store.md`](session-store.md) §5.2). 유예 비교는 두 노드의 시계를 비교한다 — 세그먼트의 `createdAt`
은 봉인한 노드의 시계이고 비교는 수집하는 노드의 시계다. 1시간에서는 skew 가 무해하지만 `segmentGcGrace` 를 줄이는 운영자는
알아야 한다.

### 7.2 저장소 단위 스윕

GC 는 턴 끝 저장 뒤에만 돌므로, 다시 열리지 않는 세션의 고아 — 영속된 rewind 가 끊어 낸 세그먼트, 실패한 `/clear` 삭제, 저장되지
못한 봉인, 레코드 삭제와 `deleteAll` 사이의 실패 — 는 GC 가 줍지 못한다. `SessionLogSegmentSweeper` 가 세그먼트 저장소 전체를
`scanSessions` 로 훑어 그것을 줍는다.

- **지우는 조건은 둘이다.** grace 보다 오래됐고, 목록을 읽은 **뒤에** 읽은 레코드의 manifest 가 가리키지 않거나 레코드가 없다.
  목록 뒤에 레코드를 읽어야 목록을 읽는 사이 레코드가 그 세그먼트를 가리키게 된 경우를 지우지 않는다. 레코드를 읽지 못하면
  (백엔드 실패, 코덱이 거절한 문서) 그 세션은 이번 회차에 건너뛴다. `SessionRecordView.getLogState()` 를 재정의하지 않는 레코드
  저장소 위에서는 모든 세그먼트가 고아로 보이므로, 스위퍼를 켜려면 레코드 저장소가 로그 상태를 돌려줘야 한다
- **raw 로 지운다.** 스위퍼는 세션을 쥐지 않으므로 펜스 뷰는 모든 삭제를 거절한다. 안전은 GC 와 같은 두 조건(manifest 확인 + grace)에
  기댄다
- **grace 기본은 24시간이다.** GC 의 1시간보다 긴 것은, 스위퍼는 홀더가 아니어서 봉인과 그것을 가리키는 레코드 쓰기 사이의 시간을
  가장 길게 잡아야 하기 때문이다
- **클러스터에 한 번.** `coordination(leaseStore, holderId, lease)` 를 주면 `sweepIfClaimed()` 가 예약 id `aimon:segment-sweep` 의
  리스를 먼저 잡고, 못 잡으면 그 회차를 건너뛴다. 새 SPI 를 만들지 않고 이미 만료와 펜싱 토큰을 가진 `SessionLeaseStore` 를 쓴다.
  리스는 **스윕 간격 하나만큼** 잡고, 페이지마다 연장하며, 회차가 끝나도 **놓지 않는다** — 놓으면 일정이 어긋난 다른 노드가 같은
  간격 안에 다시 돈다. 쥔 노드의 다음 회차는 `tryAcquire` 가 아니라 기억해 둔 리스의 `extend` 를 먼저 시도한다. 마지막 연장이 만료를
  `회차 끝 + 간격` 에 두고 자기 일정도 그 무렵 틱하므로, 재획득을 시도하면 두 시각의 선후에 따라 같은 홀더가 거절되어 간격 하나를
  건너뛸 수 있다. `extend` 가 실패할 때만 `tryAcquire` 로 돌아간다. 쥔 노드가 죽으면 만료 뒤 처음 틱하는 노드가 이어받는다. 리스
  저장소에 닿지 못하면 그 회차는 건너뛴다. 조정 없이 여러 노드가 돌려도 안전하다 — 서로의 일을 반복할 뿐이다
- **opt-in 이다.** `SessionSpec.segmentSweepInterval(...)` / `segmentSweepGrace(...)`, Spring `aimon.session.segment-sweep-interval` /
  `aimon.session.segment-sweep-grace`. 간격이 없으면 꺼져 있다. 스택은 `SegmentSweepSchedule`(daemon 스레드 하나)을 만들어
  `startRuntimes()` 에서 시작하고 `SESSIONS` 단계에서 라우터보다 먼저 닫는다. 첫 회차는 간격 하나를 기다린다. 리스 저장소를 받은
  스택은 자동으로 조정하고 holder id 는 `nodeId`(없으면 임의의 id)다. grace 만 있고 간격이 없는 설정, 세그먼트 저장소 없이 레코드
  저장소만 준 설정은 조립 시점에 거절한다

---

## 8. 로그를 줄이는 두 경로

로그는 압축으로 줄지 않지만, 사용자가 명시적으로 지우는 경로가 둘 있다. 둘 다 `SessionLogState` 의 연산으로 정의해 버퍼 경로와
저장된 레코드 경로가 같은 규칙을 쓴다.

### 8.1 rewind — `truncateFrom(seq)`

중단된 턴을 버린다. 경로는 `DefaultLiveSession.rewindLastTurn` → 영속 rewind 이고, 저장된 로그에 `truncateFrom(rewindPoint.seq)`
를 건다. `TranscriptBuffer.rewind()` 도 같은 연산이다.

- hot 항목은 잘리고, `fromSeq` 가 rewind point 이상인 manifest 줄은 버린다. 봉인 구간은 rewind point 에서 끊겨 있으므로(§5.1)
  걸치는 줄이 없다. 세그먼트를 읽거나 쪼갤 필요가 없고, 버려진 세그먼트는 고아가 된다
- `nextSeq` 는 그대로다
- 뷰 상태에서 잘린 seq 이상을 가리키는 것을 정리한다. `droppedRanges` · `elisions` 는 그 부분을 버린다
- `summarySpan` 이 rewind point 뒤에 통째로 있으면 버리고, 걸치면(중단된 턴 안에서 롤링 압축이 일어난 경우) `toSeq` 를 rewind
  point 로 자르고 요약은 그대로 둔다. 요약에 버려진 부분의 내용이 섞여 있을 수 있다 — 알려진 부정확함이다

### 8.2 `/clear` — `clear()`

`/clear` 는 기록을 지운다는 뜻을 지킨다.

- hot 항목 · manifest · 뷰 상태를 비우고 `floorSeq = nextSeq` 로 둔다. seq 는 이어서 증가한다
- **읽기는 즉시 막힌다.** reader 는 manifest 만 따르므로 지운 대화는 되찾기 도구로 보이지 않는다
- **삭제는 저장 뒤에.** 잘려 나간 줄의 세그먼트 id 를 버퍼에 쌓아 두고, 비운 레코드의 저장이 성공한 **뒤에만** 펜스 뷰로 지운다.
  저장이 먼저이므로 "레코드는 가리키는데 세그먼트가 없는" 상태가 생기지 않는다. 삭제가 실패하면 남은 세그먼트는 고아다
- **늦은 체크포인트가 착지할 수 있으면 삭제를 미룬다.** `/clear` 이전 스냅샷을 든 체크포인트가 저장소 호출 안에 걸린 채 `/clear`
  의 저장을 넘기면, 착지하는 순간 레코드는 지운 메시지와 옛 manifest 를 다시 든다. 그 체크포인트는 스냅샷을 찍은 뒤 저장소 호출
  **안에서** 걸려 있으므로 쓰기 전의 어떤 검사로도 거절할 수 없다. 그래서 `SessionCheckpointMailbox.drain(sessionId)` 가 "이 세션의
  옛 상태 쓰기가 아직 착지할 수 있는가" 를 답하고 — 대기 중인 것이 없었거나 barrier 에 닿았거나 writer 가 멈췄으면 `true`, 시간
  초과 · 인터럽트 · 살아 있는 버려진 writer 면 `false` — transcript manager 는 `false` 인 저장 뒤에 `/clear` 삭제도 GC 도 하지
  않는다. 삭제는 버퍼에 남아 drain 이 끝나는 다음 저장에서 다시 시도된다. drain 시간 제한은 `SessionCheckpointMailbox.background(Duration)`
  (기본 5초)이다. `flush` 는 그 답을 버리는 같은 동작이다
- `ClearCommand` 의 "Removed N messages" 는 살아 있는 항목 수 — hot 항목 수 + manifest 의 `entryCount` 합 — 로 센다.
  `nextSeq − floorSeq` 는 rewind 로 잘린 seq 까지 센다

**세션 삭제.** 라우터의 `deleteSession` 은 레코드를 지운 뒤 `deleteAll` 을 부른다([`routing.md`](routing.md) §9). 둘 사이에
실패하면 세그먼트가 남는데, 가리키는 레코드가 없으므로 저장소 단위 스윕(§7.2)이 줍는다.

---

## 9. 저장 형식과 이행

### 9.1 형식

`JsonSessionSnapshotCodec` 은 `version` 필드를 항상 쓰고 decode 는 모르는 값에 실패한다. `version: 2` 가 `SessionLogState` 를 담는다.
v2 문서의 `viewState` 는 객체(`summarySpan` · `droppedRanges` · `elisions`)이고 비어 있지 않을 때만 쓴다. 없으면 빈 것으로 읽는다.

세션 백엔드는 전사를 불투명 문자열로 저장하므로 **백엔드의 레코드 저장 코드는 형식과 무관하다.** 같은 코덱을 서브에이전트 resume
스냅샷(`VfsSessionSnapshotStore`, 공유 파일 시스템)도 쓴다.

**쓰기 형식은 로그 상태가 든다.** 백엔드는 모두 정적 `SessionRecordCodec` 으로 부르고 `DefaultContextEngine` 도 형식을 알아야 하므로
(뷰 모드냐 제자리 압축이냐) 형식은 codec 설정이 아니라 `SessionLogState.getFormat()` — "이 상태가 적어도 이 형식으로 쓰여야 한다" —
에 둔다. codec 은 `max(쓰기 형식, 상태 형식)` 으로 쓴다. 노드의 스위치는 `DefaultTranscriptManager(store, mailbox, SessionLogFormat)`
(내주는 버퍼를 전부 그 형식으로 올린다)와 `JsonSessionSnapshotCodec(SessionLogFormat)`(`VfsSessionSnapshotStore` 용)이고, 노출은
`SessionSpec.logWriteFormat(...)`, Spring `aimon.session.log-write-format`, `OrcaAgentRuntimeFactory.withSessionLogWriteFormat(...)`,
CLI `cli.sessionLogWriteFormat` 이다. 모든 조립의 기본은 v2 다.

**v1 쓰기 모드.** `replaceWith` 로 기록을 고쳐 쓰는 제자리 압축으로 물러난다. 교체 항목은 `nextSeq` 부터 새 seq 를 받고 `floorSeq` 가
그 첫 seq 로 올라간다. 교체된 항목과 같은 인스턴스는 origin 을 유지하고 나머지는 `CONVERSATION` 이다. manifest 는 새 floor 아래로
내려가므로 지운다. 뷰 상태나 manifest 가 있는 v2 버퍼는 제자리 폴백을 만나도 제자리로 압축하지 않는다
([`context-engine.md`](../agent-execution/context-engine.md)). `RollingContextEngine` 은 v1 에서 구현할 수 없으므로 v1 쓰기 모드에서
배선하면 기동 시 실패한다.

### 9.2 로드·저장 체인

| 방향 | 체인 |
|------|------|
| 로드 | 백엔드 → `SessionRecordCodec.decodeTranscript` → `StoredSessionRecord` → `SessionRecordView` → `SessionSnapshot.from(view)` → `TranscriptBuffer.fromSnapshot` |
| 저장 | `TranscriptBuffer.toSnapshot` → `SessionRecord.fromSnapshot` → `SessionTranscript` → `SessionRecordCodec.encodeTranscript` → 백엔드 |

- 각 타입은 `SessionLogState` 를 통째로 들고 넘긴다. 메시지 목록은 그 값의 뷰로 제공한다. 로그를 받는 팩토리는
  `SessionTranscript.fromLog(...)` · `SessionSnapshot.fromLog(...)` 다
- `SessionRecordView.getLogState()` 는 `default` 다 — 그 인터페이스에는 트리 밖 구현이 있다
- rewind 검증은 seq 의미다 — "rewind seq 가 `[floorSeq, nextSeq]` 안에 있고 어떤 manifest 줄의 안쪽에도 있지 않다". 인덱스로 검증하면
  봉인 뒤 레코드 로드 자체가 실패한다

### 9.3 이행

**v1 레코드 읽기.** `version: 1` 의 메시지는 seq `0..n-1`, `origin = CONVERSATION` 인 로그가 된다. 뷰 상태는 비어 있다. v1 레코드에
남은 경계·요약 마커 메시지는 로그의 평범한 메시지로 남고, 그 앞의 원문은 이미 잃었다 — 되돌릴 수 없는 손실이다.

**혼합 버전 클러스터는 두 단계로 켠다.**

1. **읽기** — 모든 노드가 v1 · v2 를 읽을 수 있게 배포한다. 기본 쓰기 형식이 v2 이므로 이 단계에서는 쓰기 형식을 v1 으로 명시해
   고정한다
2. **쓰기** — 전 노드 배포가 끝난 뒤 그 고정을 풀어 쓰기를 v2 로 바꾼다

- **sticky upgrade** — v1 쓰기 모드의 노드도 v2 로 읽은 레코드는 v2 로 쓴다. 쓰기 형식이 로그 상태에 있으므로 저절로 성립한다
  (§9.1). 2단계로 바꾸는 과정 자체가 롤링 배포라, 그 사이 v1 쓰기 노드가 v2 레코드를 v1 으로 다시 쓰면 뷰 상태 · manifest ·
  `nextSeq` 가 사라진다 — span 이 사라져 hot 원문이 그대로 뷰가 되고, 봉인된 구간이 참조를 잃고, seq 가 0 부터 다시 시작한다
- **되돌리기** — v2 레코드가 하나라도 쓰인 뒤에는 1단계 이전 바이너리로 되돌리는 것을 지원하지 않는다. 그 바이너리는 v2 를 읽지
  못한다

---

## 10. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 위치 주소 | seq, 재사용 안 함 | **인덱스** — 로그가 봉인되고 잘리면 인덱스가 움직여 모든 위치 값을 다시 매핑해야 한다 |
| 레코드 쪽 상태의 형태 | 불변 값 `SessionLogState` 하나 | **기존 타입에 필드 추가** — 로드·저장 체인이 필드별로 복사하므로 한 홉만 빠져도 조용히 사라진다 |
| 항목의 출처 | 로그 항목의 `origin` | **`Message` 메타데이터** — 모든 `LlmClient` 변환과 코덱에 번진다. **텍스트 태그** — mid-turn 사용자 메시지를 오분류한다 |
| `getMessages()` 의 뜻 | hot 항목 | **전체 기록** — 실행 루프가 세그먼트 저장소에 의존한다. **뷰** — 호출자 대부분이 뷰를 원하지 않는다 |
| 레코드 크기 | 구간 단위 봉인 | **로그 전체를 레코드에** — 체크포인트 쓰기량이 세션 길이의 제곱. **prefix 봉인** — 롤링의 head 가 원문으로 남아 봉인이 나아가지 못한다 |
| 세그먼트 유효성 | 레코드의 manifest 가 정한다 | **`(sessionId, fromSeq)` 결정적 id + 멱등 put** — 넓어진 구간을 다시 봉인하거나 두 노드가 다른 구간을 봉인하면 같은 id 에 다른 내용이 생긴다 |
| 세그먼트 쓰기 펜싱 | 하지 않는다 | **리스 증명 후 쓰기** — 증명과 쓰기 사이의 틈은 닫히지 않는다. manifest 가 늦은 쓰기를 무해하게 만든다 |
| 봉인 조건에 "저장 완료" | 넣지 않는다 | **마지막 저장 seq 이하만 봉인** — 체크포인트 콜백은 성공을 돌려주지 않고 실패를 삼킨다. manifest 모델에서는 저장이 실패해도 항목이 레코드에 남는다 |
| 봉인 시점 | 버퍼를 가진 스레드에서 동기로 — 압축 직후와 턴 끝 | **비동기 executor** — 지난 턴의 버퍼가 체크포인트 slot 을 가로채 레코드를 옛 상태로 덮어쓴다. **턴 끝에만** — 긴 단일 턴 동안 레코드가 그 턴 전체를 든다. **체크포인트 writer** — 앱 전체에 하나다 |
| 턴 종료 순서 | seal → flush → 저장 | **flush 뒤 봉인** — 봉인이 올린 체크포인트가 권위 있는 저장 뒤에 쓰인다 |
| rewind 와 봉인 | 봉인 구간을 rewind point 에서 끊고, rewind 는 manifest 줄을 버린다 | **rewind point 이후는 봉인하지 않음** — 현재 턴이 통째로 봉인에서 빠져 "턴 끝에만" 과 같아진다 |
| ingest mark 를 봉인 조건으로 | 넣지 않는다 | 봉인된 항목은 실행이 끝날 때까지 메모리에 남고 ingest 는 메모리 안의 버퍼를 읽는다 |
| 세그먼트 누락 시 읽기 | 구멍을 보고한다 | **실패** — 기록 일부의 손실이 세션 전체의 실패가 된다 |
| GC 시점 | 턴 끝 저장 뒤에만 | **세션을 열 때도** — 열기는 턴마다 일어나 `list` 만 두 배가 된다 |
| 다시 열리지 않는 세션의 고아 | opt-in 저장소 단위 스윕, 클러스터 리스로 회차당 한 노드 | **GC 에만 맡김** — 다음 턴이 오지 않으면 줍지 못한다. **새 조정 SPI** — `SessionLeaseStore` 가 이미 만료와 토큰을 갖는다 |
| `/clear` | 저장 성공 뒤 삭제, drain 이 실패하면 연기, 실패분은 고아 | **저장 전 삭제** — 저장이 실패하면 레코드가 없는 세그먼트를 가리킨다. **GC 에만 맡김** — 지운 대화가 유예 동안 남는다. **늦은 체크포인트 거절** — 쓰기 전의 검사는 이미 지나갔다 |
| 펜스 삭제 뷰 | `SessionStore.segments(...)` | **세그먼트 저장소가 리스를 직접 확인** — 펜싱 규칙이 두 곳에 생긴다 |
| 세그먼트 저장소 | 새 SPI | **`SessionRecordStore` 확장** — 레코드 저장소는 "세션당 한 행" 을 전제로 원자성을 설계했다 |
| 쓰기 형식의 자리 | 로그 상태 | **codec 설정** — 정적 코덱을 쓰는 백엔드와 형식을 알아야 하는 engine 이 볼 수 없다 |
| 혼합 버전 | 2단계 + sticky upgrade | **한 번에 전환** — v1 노드가 v2 레코드를 읽지 못한다. **sticky 없이 2단계** — 전환 중 v1 쓰기가 v2 상태를 지운다 |

---

## 하지 말 것

- **실행 루프에서 봉인된 로그를 읽지 않는다.** 필요해 보이면 봉인 조건이 틀린 것이다
- **manifest 에 없는 세그먼트를 읽지 않는다.** 저장소에 있다는 것은 유효하다는 뜻이 아니다
- **버퍼를 다른 스레드에서 고치지 않는다.** 봉인은 그 턴을 돌리는 스레드가 동기로 한다
- **턴 종료에서 flush 뒤에 봉인하지 않는다**
- **봉인 구간이 rewind point 를 걸치게 하지 않는다**
- **봉인된 항목을 실행 중에 메모리에서 버리지 않는다.** 실행 끝 ingest 가 그것을 읽는다
- **GC 유예 기간을 0 으로 두지 않는다**
- **세그먼트가 없다고 읽기를 실패시키지 않는다.** 구멍을 보고한다
- **seq 를 재사용하지 않는다**
- **로그를 고쳐 쓰지 않는다.** 로그를 줄이는 것은 `truncateFrom` 과 `clear` 뿐이다. 뷰를 줄이고 싶으면 뷰 상태를 바꾼다(v1 쓰기 모드 제외)
- **`SessionLogState` 를 필드별로 복사하지 않는다**
- **v1 쓰기 노드가 v2 레코드를 v1 으로 쓰게 하지 않는다**
- **세그먼트를 레코드보다 약하게 보호하지 않는다**
- **세그먼트 저장소 없이 영속 레코드 저장소에 봉인하지 않는다.** 재시작 뒤 봉인 구간이 전부 구멍이 된다
- **reader 캐시를 작업 하나를 넘어 두지 않는다**

---

## 11. 공개 API 의 뜻

| API | 뜻 |
|-----|----|
| `TranscriptBuffer.getMessages()` | hot 항목(§3.3) |
| `AgentExecutionResult.getConversationHistory()`, `SessionSnapshot.getConversationHistory()` | 같은 뜻. 봉인이 일어난 세션에서는 전체 대화가 아니다 |
| `TranscriptBuffer.replaceWith`, `replaceMessageAt` | v1 쓰기 모드 전용. deprecated |
| `SessionStore.segments(raw)` · `segments(raw, fence)` | 세그먼트 펜스 뷰. `segments(raw)` 는 **추상** 메서드다 |
| `SessionRecordView.getLogState()` | `default` accessor |
| `SessionRewindPoint` | 개수가 아니라 seq 를 든다 — `of(long seq, …)`, `getSeq()`. `int` 를 넘기던 호출은 컴파일되지만 이미 컴파일된 호출자에게는 바이너리 비호환이다([`../../migration/rename-maps.md`](../../migration/rename-maps.md)) |
| `TranscriptManager.seal(buffer)` · `getLogReader()` | `default`(no-op / empty) |
| `SessionCheckpointMailbox.drain(sessionId)` | 옛 상태 쓰기가 아직 착지할 수 있는지 |

---

## 남은 것

열림/닫힘의 정본은 [`../../backlog/session-log-open-items.md`](../../backlog/session-log-open-items.md) 다. 이 설계가 알고 남긴 것:

- **rewind 를 걸친 span** — 요약에 버려진 턴의 내용이 섞이는 부정확함(§8.1)을 받아들일지, 넓히기 전 요약을 하나 더 들고 있다가
  되돌릴지
- **긴 실행의 메모리** — 봉인된 항목은 실행이 끝날 때까지 메모리에 남는다. 영속 크기는 창 크기로 묶이지만 한 실행의 메모리는 그
  실행의 크기다. 문제가 되면 ingest 를 실행 중간에도 흘려보내 그 부분을 메모리에서 놓을지
- **세그먼트 병합** — `minSealTokens` 로 부족하면 작은 세그먼트를 합칠지
- **v1 쓰기 모드의 수명** — 제자리 압축 폴백을 언제 지울지
- **스윕을 켜지 않은 배포의 고아** — §7.2 는 opt-in 이다
- **펜스의 남는 창과 늦은 체크포인트의 되살림** — 레코드 펜스는 재증명과 쓰기 사이의 창을 닫지 않는다
  ([`session-store.md`](session-store.md) §5.3). 늦게 착지한 체크포인트가 `/clear` 이전 메시지를 되살리면, 그 턴의 버퍼가 아직
  쓰이는 동안에는 다음 저장이 다시 비우지만, 턴이 끝난 뒤라면 다음 턴이 되살아난 메시지를 그대로 이어 저장한다. 구멍은 만들지
  않는다 — drain 이 포기한 저장 뒤에는 아무것도 지우지 않으므로 되살아난 manifest 의 세그먼트는 그대로 있다

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 기록 | [`TranscriptBuffer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptBuffer.java), [`SessionLogState.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionLogState.java) |
| 뷰 상태 | [`SessionViewState.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionViewState.java) |
| 영속 값 | [`SessionTranscript.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionTranscript.java), [`SessionSnapshot.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionSnapshot.java), [`SessionRecord.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecord.java) |
| 인코딩 | [`SessionRecordCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordCodec.java), [`JsonSessionSnapshotCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/task/codec/JsonSessionSnapshotCodec.java), [`SessionLogSegmentCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionLogSegmentCodec.java) |
| 저장 경로 | [`DefaultTranscriptManager.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/DefaultTranscriptManager.java), [`SessionCheckpointMailbox.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionCheckpointMailbox.java) |
| 세그먼트 SPI | [`SessionLogSegmentStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionLogSegmentStore.java), [`InMemorySessionLogSegmentStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/InMemorySessionLogSegmentStore.java) |
| rewind | [`DefaultLiveSession.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/DefaultLiveSession.java) |
| 펜스 | [`SessionStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionStore.java) |
| 조립 | [`AimonStackBuilder.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java) |

---

## 관련 문서

- [`../agent-execution/context-engine.md`](../agent-execution/context-engine.md) — 이 저장 모델 위에서 뷰를 만드는 자리
- [`../agent-execution/compaction.md`](../agent-execution/compaction.md) — 압축
- [`session-model.md`](session-model.md) — 레코드와 전사
- [`session-store.md`](session-store.md) — 레코드·세그먼트 쓰기의 펜스
- [`backends.md`](backends.md) — 세그먼트의 백엔드별 저장 모양
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명 규칙
