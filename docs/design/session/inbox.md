# 세션 인박스 — 크로스 노드 우편함

**Status**: IMPLEMENTED — `aimon-core` `at.aimon.core.agent.session.inbox`, 세 백엔드의 `*SessionInbox`, `aimon-session-routing` 의 드레인
경로, 계약 스위트 `aimon-session-testkit`. 남은 것은 [마지막 절](#남은-것).

`SessionInbox` 는 세션의 크로스 노드 우편함이다. 아무 노드나 넣을 수 있고 리스 홀더만 꺼낸다. 이 문서는 봉투가 무엇을 나르는지,
`collect` 가 무엇을 보장하는지, 그리고 한 항목을 읽지 못할 때 무엇을 잃고 무엇을 지키는지를 정의한다. 인박스를 쓰는 제출
시퀀스는 [`routing.md`](routing.md) §7 이 정본이다.

---

## 1. 역할

라우터가 필요로 하는 것은 actor 의 mailbox 의미론이다 — 비홀더가 deliver 하고 홀더가 collect 한다. 세션 내부 큐
(`MessageQueueManager`)는 그 일을 하지 않는다.

| | `MessageQueueManager` | `SessionInbox` |
|---|---|---|
| scope | 세션 하나, 프로세스 안 | 크로스 노드, 멀티 세션 |
| 역할 | mid-turn 주입 | 비홀더의 deliver → 홀더의 collect |
| payload | `QueuedInput` — `sessionId`·`agentRef`·`idempotencyKey` 가 없다 | `InboundMessage` |

두 채널은 공존한다. 라우터는 인박스만 만지고, 세션 내부 큐는 홀더가 collect 한 메시지를 `QueuedInput` 으로 다시 싸서 넣을 때만
닿는다(§5).

인박스는 **아직 안 된 일**을 담는다. 멀티 노드 경로는 제출을 인박스에 먼저 적재하므로, 단일 노드라면 큐와 함께 사라졌을 미실행
메시지가 저장소에 남는다([`session-model.md`](session-model.md) §6.4 L2).

---

## 2. 봉투 — `InboundMessage`

| 필드 | 왜 나르나 |
|------|-----------|
| `id` (`InboundMessageId`) | 백엔드 항목 id — 스트림 id / row id / `_id` |
| `turnId` | 제출 노드가 발급한 턴 주소. 실패를 그 턴으로 알린다 |
| `sessionId` | |
| `agentRef` | 홀더가 collect 시점에 바인딩을 다시 비교한다 — 첫 턴 경합에서 진 쪽의 메시지를 거른다([`routing.md`](routing.md) §3.4) |
| `contextDiscriminator` | 런타임 id 해석 |
| `userInput` | `UserInput` |
| `priority` | `NOW` / `NEXT` / `LATER`(§5) |
| `idempotencyKey` | 멱등 예약을 넘겨받고 결과를 알린다([`idempotency.md`](idempotency.md)) |
| `initiator` | 제출한 주체 |
| `owner` | 세션 소유자. 포워딩된 첫 제출이 소유자를 잃지 않게 드레인 경로가 기록한다 — 메시지의 `agentRef` 가 바인딩과 같고, 레코드에 소유자가 없을 때만 |
| `deliveredAt` | FIFO 순서 |
| `metadata`, `submitOptions` | 턴 단위 메타데이터 |

**나르지 않는 것** — `LiveSessionOptions` 와 `OpenAttributes`. 홀더는 자기가 이미 연 핸들로 처리하므로 제출 노드의 열기 파라미터가
의미를 갖지 않는다.

**선택 필드는 없으면 쓰지 않는다.** 소유자 없는 봉투는 소유자 필드를 모르는 빌드가 쓴 것과 같고, 옛 코덱은 모르는 필드를 무시한다.
in-memory 인박스도 `deliver` 에서 id 를 찍으려고 봉투를 다시 만들 때 소유자를 복사한다.

**`userInput` 의 와이어는 비대칭이다.** `userInput` 키는 철자도 타입도 그대로 텍스트(`asText()`)이고, 비텍스트 입력만
`UserInputCodec` 의 인코딩을 `userInputEncoded` 에 더한다. 인박스는 아직 안 된 일을 담으므로 업그레이드 시점에 양쪽 빌드가 쓴 항목이
섞여 있고, 이 비대칭이 그것을 서로 읽게 만든다([`../../migration/frozen-names.md`](../../migration/frozen-names.md)).

---

## 3. `collect`

```java
CollectedBatch collect(SessionId sessionId, QueuedInputPriority upTo);

final class CollectedBatch {
    List<InboundMessage> getMessages();      // 읽힌 것. priority-then-FIFO
    List<UnreadableEntry> getUnreadable();   // 지웠지만 읽지 못한 것
}
```

- **원자적 제거.** 돌려줄 항목을 원자적으로 지운다. 시그널이 at-least-once 여도 collect 가 원자 배치라서 순서와 정합성이 보존된다.
  Postgres 는 `FOR UPDATE SKIP LOCKED`, Mongo 는 `findOneAndDelete` 루프, Redis 는 Lua 스크립트 하나가 그 방어다
- **반환은 제거 집합의 부분집합일 수 있다.** 지운 수와 읽어 돌려준 수가 갈릴 수 있고, 갈린 것은 `getUnreadable()` 로 돌아온다(§4).
  이것은 계약이 약해진 것이 아니라 정확해진 것이다 — 원자적 제거는 그대로다
- **한도.** 한 번의 collect 가 건드리는 수에는 백엔드별 한도가 있다(Mongo 64, Postgres 1024). 한도를 넘은 백로그는 돌려주지도 지우지도
  않고 인박스에 남는다. "이 collect 가 돌려주지 않은 것은 peer 도 못 찾는다" 는 이 collect 가 **건드린** 집합에 대한 말이다
- **홀더만 collect 한다.** 라우터는 턴 전에 언제나 collect 한다([`routing.md`](routing.md) §7.1 F4)

---

## 4. 한 항목이 배치를 데려가지 않게

### 4.1 경계는 항목이다

`collect` 는 지운 뒤에 디코드한다. 한 항목의 디코드가 실패하면 그 예외가 collect 를 빠져나가고, 이미 지운 정상 항목까지 함께 사라진다.
그래서 세 백엔드의 디코드는 **항목 단위**로 감싸이고, 그 `catch` 는 `RuntimeException` 이다.

경계를 예외 타입으로 긋지 않는 이유는 실제로 빠져나온 것이 코덱 예외가 아니었기 때문이다. 손상된 봉투의 실패는
`Principal.Type.valueOf` 가 던진 평범한 `IllegalArgumentException` 이었고, `SessionInboxException` 이나 코덱 예외로 좁히면 값 객체가
던지는 것이 그대로 새어 나간다. 한 항목을 읽는 동안 나온 것은 타입을 가리지 않고 그 항목에 가둔다.

**무엇이 이 자리에 닿는가.** 손상된 문서, 그리고 더 새로운 빌드가 쓴 어휘 밖의 값(예: 다섯 번째 `Principal.Type`)이다. `priority` 는
닿지 않는다 — 세 백엔드가 **디코드 전에 우선순위로 거른다**(Redis 는 티어가 곧 키, Postgres `WHERE priority <= ?`, Mongo `Filters.lte`).
그래서 새 우선순위 티어는 배치 소실이 아니라 "옛 노드가 그 항목을 영원히 수집하지 않는다" 는 별개의 고장을 만들고, 그것은 이
계약의 범위 밖이다(§남은 것).

봉투 필드의 디코드는 계속 **던진다.** 이 설계가 바꾸는 것은 던진 뒤의 처분이지 던질지 여부가 아니다. 봉투 전체를 저하시켜 "닫으면"
손상된 문서를 정상인 척 실행하게 된다. 코덱 단위 테스트가 세 필드가 여전히 던지는 것을 고정한다 — `priority` 는 코덱이 필터를 모르므로
코덱 층에서는 여전히 참인 계약이고, 도달 불가는 지금의 질의 모양에 기대는 사실이라 질의가 바뀌면 조용히 도달 가능해진다.

### 4.2 주소는 살릴 수 있는 만큼 살린다

드롭은 조용하면 안 되고, 로그는 운영자에게만 말한다. 제출자에게 알리려면 그 항목의 **주소**가 필요하다 — 봉투가 collect 안에서
사라지면 이미 있는 실패 통보 경로에 실을 주소가 없다. 그래서 `collect` 는 못 읽은 항목마다 아직 읽히는 것을 `UnreadableEntry` 로 돌려준다.

```java
final class UnreadableEntry {               // 불변 + 빌더
    InboundMessageId getId();               // 백엔드 항목 id
    Optional<String> getTurnId();           // 가공하지 않은 문자열 — TurnId 가 아니다
    Optional<String> getIdempotencyKey();
    String getReason();                     // 예외 메시지. 페이로드는 담지 않는다
}
```

- **주소는 검증하지 않는 문자열이다.** `TurnId` 의 생성자는 null·blank 에 `IllegalArgumentException` 을 던진다. 그 변환이 정의상 믿을 수
  없는 페이로드에서, 이미 한 번 예외가 난 경로에서 일어나면, `{"turnId": ""}` 하나가 막으려던 배치 소실을 그대로 재현한다.
  `TurnId` 로의 변환은 그것이 필요한 통보 쪽에서 하고, 실패하면 `null` 이 된다
- **회수는 그 항목의 가드 안에서 돌고, 실패해도 항목 밖으로 나가지 않는다.** 회수 전체를 별도 catch 로 감싸면 항목은 드롭되는데
  `UnreadableEntry` 가 만들어지지 않아 아무에게도 알리지 못한다
- **회수 코드는 코덱에 산다.** 주소 키(`turnId` · `idempotencyKey`)의 이름을 아는 것도, `ObjectMapper` 를 가진 것도 코덱이다. 인박스에
  mapper 를 들리면 파싱 지식이 두 곳이 된다. 코덱의 `recoverAddress(...)` 는 **전역(total) 메서드**다 — 던지지 않고, 아무것도 못 읽으면
  주소가 빈 항목을 돌려준다. 페이로드 축만이 아니라 항목 id 축에도 그렇다 — 읽을 수 없는 id 는 `unknown` 으로 보고한다. "회수가 실패해도
  항목 밖으로 나가지 않는다" 를 세 인박스가 아니라 한 자리에서 지키는 방법이다
- **빈 값은 주소가 아니다.** Jackson 의 `asText()` 는 객체·배열 노드에 `""` 를 돌려주므로 `{"idempotencyKey": {}}` 를 그대로 실으면 아무도
  등록하지 않은 주소로 통보가 나간다. 공백은 없음으로 접는다
- **코덱이 주소를 실은 예외를 던지게 하지 않는다.** 경계를 예외 타입으로 긋지 않기로 했으므로 그 예외가 온다는 보장이 없다

### 4.3 처분 — `collectPending` 안에서

라우터는 못 읽은 항목을, 드레인 경로가 거절한 메시지에 이미 하는 것과 같이 처분한다.

```
announceTurnFailure(sessionId, turnId, idempotencyKey, UNREADABLE, "…")
    → 예약을 푼다 (discardReservation) — 재시도가 죽은 시도에 붙지 않게
    → TURN_RESULT 를 발행한다         — 크로스 노드 통보
    → 대기 중인 forward 를 끊는다       — 제출 노드의 future 를 즉시 실패시킨다
```

- **처분은 `collectPending` 안에서 끝난다.** 그 메서드는 collect 를 부르는 세 지점(턴 루프, 드레인, 드레인 전용 패스)이 공유하는 유일한
  자리다. 호출 지점에 두면 가장 흔한 모양 — 대기 메시지가 하나이고 그것을 못 읽어 **읽힌 것은 없고 못 읽은 것만 있는 배치** — 에서
  비어 있음 검사에 걸려 아무에게도 알리지 못한다. 제출자는 forward TTL(5분) 뒤에 타임아웃으로 떨어지고, 그 동안 재시도는 죽은 예약에 붙는다
- **두 주소를 언제나 함께 넘긴다.** 통보의 "주소 없음" 분기는 `turnId` 와 `idempotencyKey` 가 **둘 다** 없을 때만이다. 키가 살아 있으면
  키 하나로 통보된다. `turnId` 변환이 실패했다고 통보를 건너뛰면 키로 알릴 수 있었던 제출자를 타임아웃으로 보낸다. 주소가 하나도 없으면
  DEBUG 한 줄을 남기고 돌아간다
- **처분 루프도 항목 단위로 가드한다.** 통보가 던지면 읽힌 메시지를 돌려주는 자리에 닿지 못해 한 층 위에서 같은 결함이 재현된다. 지금
  던지는 경로는 없지만, 없으면 이 보장이 세 단계 떨어진 메서드가 앞으로도 던지지 않는다는 데 기댄다
- **`discardReservation` 의 사용 조건과 맞는다** — "at-most-once 인박스 밖으로 이미 나온 메시지에만". 드롭된 항목이 정확히 그 상태다

**실패 코드는 `UNREADABLE` 이다.** `FAILED` 는 "시도했고 던졌다" 이고 드롭된 항목은 시도된 적이 없다 — 그 구별("다시 보내라, 돌지
않았다" 와 "시도했다가 실패했다")은 호출자에게 의미가 있다. `NOT_HOLDER` 는 "떠나는 홀더가 이미 collect 한 메시지" 로 좁혀져 있어서, 재사용하면
운영자가 "노드가 내려가는 중이었다" 로 읽는다. `Failure.Code` 는 `internal` 패키지의 열거이므로 값 추가가 공개 API 변경이 아니다. 옛 노드는
모르는 코드를 `FAILED` 로 읽는다 — 구별의 잘못된 쪽에 떨어지지만, 분기하는 코드가 없고 제출자는 어느 쪽이든 즉시 깨어난다.

### 4.4 관측

| | |
|---|---|
| 레벨 | **WARN** — 예상되는 에러이고 나머지 배치는 정상으로 계속된다. 신호 디코드 실패, 저널 리플레이 같은 형제 사례도 WARN 이다 |
| 개수 | 드롭된 항목마다 한 줄. 배치 요약 줄은 없다 |
| 담는 것 | 세션 id, 백엔드 항목 id, 예외 메시지 |
| 담지 않는 것 | 페이로드, 사용자가 쓴 메시지 |

- **세션 id 는 필수다.** 어느 세션의 메시지가 떨어졌는지 말하지 못하면 "관측 가능" 이 "어딘가 줄이 하나 있다" 가 된다
- **예외 메시지는 실패한 값 하나를 인용하고, 그것은 필요하다.** `Instant.parse` 와 `valueOf` 의 메시지는 그 값을 담는다. 그 값이 없으면
  운영자는 "더 새로운 빌드가 쓴 문서" 와 "손상된 문서" 를 가를 수 없고, 그것이 이 경로가 답하려는 질문이다. 나타날 수 있는 것은 봉투
  자신의 필드 값(`deliveredAt` · `initiator.type` · `priority`)뿐이다
- **배치 안 위치(`k of n`)는 싣지 않는다.** Mongo 는 루프가 끝나야 `n` 을 안다. 세 백엔드가 같은 문장을 내야 공유 계약 스위트가 백엔드별 분기
  없이 단언하고, 배치 규모는 로그가 아니라 반환값(`getUnreadable().size()`)이 말한다
- **로그만으로는 부족하다.** 저널 리플레이가 건너뛴 것은 저장소에 남고 신호는 at-least-once 라 다시 온다. 인박스는 **지운 뒤에** 건너뛰므로
  다시 볼 방법이 없다. 부족한 쪽은 운영자가 아니라 제출자이고, 그래서 §4.2 · §4.3 이 있다
- **드롭을 셀 수 있는 자리는 라우터뿐이다.** 백엔드는 `SessionMetrics` 를 볼 수 없다. 인박스 메트릭(`outcome=partial`, 드롭 카운터)을 더한다면
  그 자리는 라우터의 `collectPending` 이다

---

## 5. 우선순위

`QueuedInputPriority` 는 `NOW` / `NEXT` / `LATER` 셋이고 봉투가 그대로 나른다.

- **`NOW`** — 홀더가 `MESSAGE_ENQUEUED` 를 받고 턴이 돌고 있으면 `collect(id, NOW)` 로 NOW 만 잘라 `QueuedInput` 으로 다시 싸서 세션 내부
  큐에 넣는다. 기존 mid-turn 주입이 이어받는다
- **`NEXT` / `LATER`** — 턴이 끝난 뒤 드레인 루프의 collect 에서 처리된다

정렬은 `(priority.ordinal, deliveredAt)` 안정 정렬이다. NOW 가 앞이고 같은 priority 안에서는 도착 순서가 보존된다.

**`NOW` 의 즉시성은 홀더 노드에서만 성립한다.** mid-turn 주입은 `MESSAGE_ENQUEUED` 를 받은 홀더가 능동적으로 할 때만 일어난다. 그 밖의
경우 `NOW` 는 "다음 턴에서 가장 먼저" 로 축소된다.

---

## 6. 기각한 대안

못 읽는 항목에 대한 후보는 셋이었다.

| 후보 | 결정 | 요지 |
|---|---|---|
| **(a) 항목 단위 격리** | **채택** | 세 백엔드 모두에서 새 실패 모드 없이 순개선. 원자성 무손상, 새 저장 표면 없음, 재배포만으로 효과 |
| (b) 지우기 전에 디코드 | 기각 | 아래 |
| (c) dead-letter | 기각(보류) | 아래 |

**(b) 지우기 전에 디코드.**

1. **SPI 의 원자성 계약을 정면으로 어긴다.** Redis 는 collect 스크립트를 조회와 삭제로 쪼개 왕복을 하나 더 해야 하고, Mongo 는
   `findOneAndDelete` 를 조회 + 삭제로 바꿔야 한다. 드문 디코드 결함 하나를 고치려고 SPI 의 동시성 계약을 넓히는 것은 비용의 방향이 반대다
2. **없애려던 것보다 나쁜 것을 만든다.** 못 읽는 항목이 저장소에 남아 poison 이 된다. 전체 롤백이면 그 세션의 모든 후속 메시지가 다시는
   드레인되지 않고, 부분 삭제면 `isEmpty` 가 영원히 false 가 되어 대기 중인 forward 의 재-초인종을 TTL 내내 울린다. Mongo 는 남은 문서가
   `(priority, deliveredAt)` 정렬의 선두이므로 부분 삭제로도 드레인이 멈춘다. 드물고 1회성인 손실이 드물지만 영구적인 정체가 된다
3. **원자성을 지키며 할 수 있는 것이 Postgres 뿐이다.** 세 백엔드가 같은 답을 하지 않는 계약은 공유 계약 스위트의 전제를 무너뜨린다

**(c) dead-letter** 는 "인박스에서 지우기" 와 "dead 로 쓰기" 가 한 연산일 때만 보장이 된다.

1. **세 백엔드에서 성립하지 않는다.** Redis 는 삭제가 커밋된 뒤에야 못 읽었다는 것을 알므로 dead 쓰기는 독립적으로 실패할 수 있는 두 번째
   연산이다. 보장하려면 staging 이동 → 디코드 → ack 의 2단계와 staging 회수 스위퍼가 필요하고, 그것은 Redis Streams 의 컨슈머 그룹을 손으로
   다시 만드는 일이다
2. **효과가 배포가 아니라 운영자 시점에 시작된다.** Postgres · Mongo 는 런타임이 DDL 을 실행하지 않으므로 새 테이블·컬렉션은 운영자가
   마이그레이션을 돌린 뒤에야 존재한다
3. **표면 하나가 정책 셋을 부른다** — 보존 기간, 세션 삭제가 dead 까지 지우는가, 크기 알람
4. **읽는 사람이 없다.** 재투입(dead → inbox)은 at-most-once 계약과 멱등 원장을 다시 따져야 한다 — 방금 푼 예약을 되살리는 일이다

(c) 는 "쓸모없다" 가 아니라 "지금 사기에는 비싸다" 로 보류한다. 남은 바이트는 사후 조사와, 읽을 수 있는 빌드가 나중에 재투입하는 데 쓸모가
있다. 그리고 (c) 는 (a) 를 대체하지 않는다 — dead-letter 를 붙여도 배치 격리는 따로 필요하다. **재검토 트리거**: 운영에서 인박스 디코드
실패가 반복 관측될 때, 또는 dead-letter 를 읽는 소비자가 실제로 생길 때.

**공개 시그니처 변경.** `collect` 의 반환이 `List<InboundMessage>` 에서 `CollectedBatch` 로 바뀐 것은 공개 SPI 의 컴파일 브레이크다. 옛 시그니처를
`default` 로 남기면 갱신되지 않은 구현이 "못 읽은 것 없음" 을 조용히 보고한다 — 선언과 계산이 갈리는 모양이다. `0.x` 의 약속대로
CHANGELOG 에 적었다.

---

## 하지 말 것

- **항목 단위 `catch` 를 코덱 예외나 `SessionInboxException` 으로 좁히지 않는다.** 값 객체의 예외가 배치를 데려간다
- **봉투 필드의 디코드를 저하시켜 던지지 않게 만들지 않는다.** 손상된 문서를 정상인 척 실행한다
- **주소를 회수하는 자리에서 값 객체를 만들지 않는다.** 문자열로 싣는다
- **회수를 항목 가드 밖으로 빼지 않는다**
- **못 읽은 항목의 처분을 `collectPending` 밖, 비어 있음 검사 뒤에 두지 않는다**
- **`turnId` 변환 실패를 "알리지 않음" 으로 읽지 않는다.** 두 주소를 함께 넘긴다
- **로그에 페이로드나 사용자 메시지를 싣지 않는다**
- **`collect` 를 지우기 전에 디코드하게 바꾸지 않는다**

---

## 남은 것

| 항목 | 왜 |
|---|---|
| 못 읽은 항목의 바이트 | (a) 는 복구하지 않는다. 복구는 (c) 이고 보류했다(§6 의 재검토 트리거) |
| 새 우선순위 티어의 조용한 미수집 | 옛 노드는 새 티어의 항목을 던지지 않고 영원히 수집하지 않는다. `QueuedInputPriority` 에 값이 추가될 때 봉투 호환 쪽에서 다룬다 |
| 인박스 메트릭 | 라우터의 `collectPending` 에 둘 자리만 정해져 있다(§4.4) |
| 항목별 가드의 드레인 지연 영향 | 예외 경로가 아니면 비용이 없다고 보지만 측정하지 않았다 |
| 인박스 배치 `deliverAll` | 세션 핫스팟 부하에서 왕복을 분할상환한다. SPI 변경이므로 구현들을 한 번에 바꿔야 싸다 |
| 기다리는 노드까지 죽은 고아 메시지 | [`idempotency.md`](idempotency.md) 의 남은 것 |

---

## 참조 파일 지도

| 관심사 | 파일 |
|---|---|
| SPI | [`SessionInbox.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/SessionInbox.java), [`InboundMessage.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/InboundMessage.java), [`CollectedBatch.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/CollectedBatch.java), [`UnreadableEntry.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/UnreadableEntry.java), [`InMemorySessionInbox.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/InMemorySessionInbox.java) |
| 백엔드 | [`RedisSessionInbox.java`](../../../modules/aimon-session-redis/src/main/java/at/aimon/session/redis/RedisSessionInbox.java), [`PostgresSessionInbox.java`](../../../modules/aimon-session-postgres/src/main/java/at/aimon/session/postgres/PostgresSessionInbox.java), [`MongoSessionInbox.java`](../../../modules/aimon-session-mongodb/src/main/java/at/aimon/session/mongodb/MongoSessionInbox.java) |
| 처분 | [`DefaultSessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/DefaultSessionRouter.java) (`collectPending`, `announceTurnFailure`), [`TurnResultPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/TurnResultPayload.java) |
| 계약 스위트 | [`AbstractSessionInboxDurabilityContractTest.java`](../../../modules/aimon-session-testkit/src/main/java/at/aimon/session/testkit/AbstractSessionInboxDurabilityContractTest.java) |

---

## 관련 문서

- [`routing.md`](routing.md) — 인박스를 쓰는 제출 시퀀스와 드레인
- [`idempotency.md`](idempotency.md) — 예약과 `discardReservation`
- [`session-store.md`](session-store.md) — SPI 경계
- [`backends.md`](backends.md) — 세 백엔드의 인박스 저장
- [`../../backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) — 봉투 필드와 멀티모달 입력의 이력
- [`../../migration/frozen-names.md`](../../migration/frozen-names.md) — 동결된 와이어 이름
