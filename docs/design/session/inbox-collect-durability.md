# 인박스 `collect` 의 내구성 — 한 항목이 배치를 데려가지 않게

> Status: **DESIGNED, 미구현** — 이 문서는
> [`backlog/interrupt-open-items.md` §5](../../backlog/interrupt-open-items.md) 가 **일부러 비워 둔
> 처방 칸**을 채운다. 코드는 아직 한 줄도 바뀌지 않았다. 실측한 것은 진단(§1)과 트리거 인구조사(§2)이고,
> 기각 두 건의 근거는 소스와 SPI 계약 문구다(§5). **고른 처방 자체는 아직 테스트로 검증되지 않았다** —
> 무엇이 검증되지 않았는지는 §8 에 전부 적혀 있다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.session.inbox` ·
> `aimon-session-{redis,postgres,mongodb}` — `*SessionInbox.collect` ·
> `aimon-session-routing` — `DefaultSessionRouter` 의 드레인 경로 · `aimon-session-testkit` — 계약 스위트

---

## 0. 결론 먼저

**(a) 항목 단위 격리를 고른다.** 세 백엔드의 `collect` 는 항목 하나의 디코드 실패를 **그 항목 안에**
가둔다 — 읽힌 것은 전부 돌려주고, 못 읽은 것은 버리되 **버렸다는 사실까지 버리지는 않는다.** 세션 id 를
실은 WARN 을 남기고, 그 항목에서 아직 읽어 낼 수 있는 주소(`turnId` · `idempotencyKey`)를 라우터에게
넘겨 **이미 있는 실패 통보 레일**(`announceTurnFailure` → `discardReservation` + `resolveForward`)로
제출자에게 알린다. 새 저장 표면도, 새 원자성 프로토콜도 만들지 않는다.

**(b) 지우기 전에 디코드** 는 SPI 가 명문으로 요구하는 원자성("remove returned entries atomically",
`SessionInbox:19-20`)을 세 백엔드 중 둘에서 포기해야 하고, 그 대가로 얻는 것은 "드물고 조용한 손실"
대신 **"영구적인 정체"** 다 — 못 읽는 항목이 저장소에 남아, 전체 롤백이면 그 세션이 다시는 드레인되지
않고 부분 삭제면 `isEmpty` 가 영원히 false 가 된다. Mongo 는 정렬 선두가 그 항목이므로 부분 삭제로도
드레인이 멈춘다.

**(c) dead-letter** 는 "인박스에서 지우는 것"과 "dead 로 쓰는 것"이 **한 연산일 때만** 보장이 된다.
Postgres 는 그것을 표현할 수 있고 Redis 의 `collect` 는 표현할 수 없다 — 표현하게 하려면 2단계
claim/ack 과 staging 회수 스위퍼를 새로 만들어야 하고, 그것은 Redis Streams 의 컨슈머 그룹을 손으로
다시 구현하는 일이다. **세 백엔드가 같은 답을 하지 않는 계약**은 `aimon-session-testkit` 이 존재하는
이유를 무너뜨린다.

그리고 (c) 는 (a) 를 **대체하지 않는다** — dead-letter 를 붙여도 배치 격리는 여전히 따로 필요하다.
그래서 (a) 를 먼저 하는 것과 (c) 를 미루는 것은 모순되지 않는다. (c) 의 재검토 트리거는 §9 에 있다.

---

## 1. 진단 — 세 백엔드 전부 재현했다

`docs/backlog/interrupt-open-items.md` §0.4 의 표는 MongoDB 만 실컨테이너로 확인되어 있었다. 셋 다
돌렸다.

**측정 조건** *(2026-09-06)* — Docker 29.2.1, Testcontainers, `redis:7-alpine` · `postgres:16-alpine` ·
`mongo:7.0`. 각 백엔드에 정상 항목 2건을 `deliver` 하고 **그 사이에** 백엔드 API 로 손상 문서 1건을 직접
심었다(`initiator.type = "ROBOT"` — `Principal.Type` 에 없는 값). 그리고 `collect(id, LATER)` 한 번.

| 백엔드 | `collect` | 저장소에 남은 것 | 결과 |
|---|---|---|---|
| Redis | `IllegalArgumentException: No enum constant … Principal.Type.ROBOT` | `entries-left-in-stream=0` | **3건 전부 소실** |
| Postgres | 같은 예외 | `rows-left-in-table=0` | **3건 전부 소실** |
| MongoDB | 같은 예외 | `docs-left-in-storage=1` (생존자는 뒤쪽 `third`) | 앞쪽 정상 1건 + 손상 1건 소실 |

세 번 다 `collect` 는 **아무것도 돌려주지 않았다**(던졌다). 마지막 칸은 그 둘을 합친 것이지 라우터
쪽에서 따로 잰 값이 아니다 — 제출자가 실제로 무엇을 언제 보는지는 §3 이 소스에서 읽어 적는다.

세 결과 모두 §0.4 의 표와 일치한다. 재현에 쓴 테스트는 **커밋하지 않았다** — 이 단계는 설계이고, 이
픽스처가 들어갈 자리는 §7 이 정한다.

IMPORTANT: **빠져나온 예외가 코덱의 것이 아니었다.** 세 번 모두
`java.lang.IllegalArgumentException`(`Principal.Type.valueOf`)이고,
`SessionSnapshotCodecException` 도 `SessionInboxException` 도 아니다. 이것은
[§0.5](../../backlog/interrupt-open-items.md) 가 입력 필드에 대해 배운 것과 **같은 교훈이 한 필드 옆에서
그대로 반복되는 것**이다 — 경계를 예외 타입으로 그으면 값 객체가 던지는 것이 새어 나간다. 그래서 이
설계의 경계는 **항목(entry)** 이다: 한 항목을 읽는 동안 나온 것은 타입을 가리지 않고 그 항목에 가둔다.

세 곳의 `catch` 가 백엔드 예외뿐인 것도 다시 확인했다 — `RedisSessionInbox:109`(`RedisException`),
`PostgresSessionInbox:123`(`SQLException`), `MongoSessionInbox:106`(`MongoException`). 디코드는 각각
`:151` · `:133` · `:103` 이며 셋 다 그 `catch` 밖의 `RuntimeException` 을 낸다 *(2026-09-06 확인)*.

---

## 2. 무엇이 실제로 이 자리를 지나가는가 — 트리거 인구조사

[백로그 §5](../../backlog/interrupt-open-items.md) 는 남은 던지는 필드를 셋으로 적는다: `initiator` · `priority` · `deliveredAt`. 그런데 **셋이 전부
디코드에 닿는 것은 아니다.** 세어 봤다.

| 필드 | 디코드에 닿는가 | 근거 |
|---|---|---|
| `initiator.type` | **닿는다** | 색인·필터 어디에도 없다. 손상 문서도, **다섯 번째 `Principal.Type`** 을 쓴 더 새로운 노드의 멀쩡한 문서도 여기로 온다. §1 이 실측한 것이 이 경로다 |
| `deliveredAt` | **닿는다** | `Instant.parse`. 손상만이 트리거다 — 이 값의 형식이 자랄 일은 없다 |
| `priority` | **닿지 않는다** | 세 백엔드가 **디코드 전에 우선순위로 거른다** — Redis 는 티어가 곧 키이고(`streamKey` = `prefix:id:TIER`, `collect` 는 `QueuedInputPriority.values()` 만 훑는다), Postgres 는 `WHERE priority <= ?`, Mongo 는 `Filters.lte(F_PRIORITY, ordinal)`. 코덱 자체는 세 곳 다 `valueOf`/`values()[…]` 를 부르지만 **어휘 밖의 값이 그 호출까지 도달하지 못한다** |
| 필수 키 누락 · 깨진 JSON/BSON | **닿는다** | `root.get(...).asText()` 의 NPE, `readTree` 의 파싱 실패 |

`priority` 행은 **추론이 아니라 측정**이다. Mongo 에 `priority: 9`(네 번째 티어가 생겼다면 더 새로운
노드가 쓸 값)를 든 문서를 심고 `collect(id, LATER)` 를 돌렸더니 `returned 2` 에
`docs-left-in-storage=1` — 그 문서는 **디코드되지 않았고 필터에 걸려 남았다.**

IMPORTANT: 그러므로 **새 우선순위 티어는 이 결함의 트리거가 아니다.** 그것이 만드는 것은 배치 소실이
아니라 *"옛 노드가 그 항목을 영원히 수집하지 않는다"* 는 **별개의 조용한 고장**이며, 이 설계의 범위
밖이다(§9). 이 인구조사를 하지 않았다면 이 문서는 "enum 이 자라면 배치가 날아간다"는 **틀린 문장**을
적었을 것이다.

남는 트리거 인구는 **손상된 문서**와 **새 `Principal.Type`** 둘이다. 백로그 §5 의 *"트리거는 손상된 문서이므로
흔하지 않다"* 는 맞고, 이 절은 그것을 한 칸 더 좁힌다.

---

## 3. 지금 무엇이 부서지는가 — 라우터 쪽에서 본 결과

디코드가 던지면 `runDrainOnly:1400` 의 `collectPending(convId)` 에서 던지므로, **그 시점에
`pendingQueue` 는 비어 있다.** 그래서 이어지는
`failUndrained(convId, pendingQueue, FAILED, "holder failed before this message could run")` 은
빈 덱을 돌고 **아무에게도 아무것도 알리지 않는다.**

즉 라우터에는 "이 메시지는 못 돌린다" 를 제출자에게 알리는 레일이 **이미 있는데**, 봉투가 `collect`
안에서 사라졌기 때문에 그 레일에 실을 주소가 없다. 배치 소실의 관측 불가능성은 통보 수단이 없어서가
아니라 **주소가 먼저 파괴되기 때문**이다. 이 한 문장이 §4 의 모양을 정한다.

주소를 살리지 않을 때 제출자가 겪는 것은 침묵이 아니라 **지연**이다 — `deliverToInbox:2018` 이 모든
전달마다 `registerForward:2068` 로 미래를 등록하고, `pollForward:2109` 가
`DEFAULT_IDEMPOTENCY_FORWARD_TTL`(**5분**) 뒤에 `TimeoutException` 으로 끊는다. 5분짜리
*"produced no result within PT5M"* 은 사고를 설명하지 않는 답이지만 무응답은 아니다.
(이 문단은 **읽어서** 세운 것이고 두 노드 하네스로 돌려 보지 않았다 — §8-3.)

---

## 4. 고른 것 — (a) 의 정확한 모양

두 조각이고, 앞의 것만으로도 등록된 결함은 닫힌다. 뒤의 것은 §3 이 드러낸 자리를 메운다.

### A1 — 항목이 경계다

세 백엔드의 디코드 호출을 항목 단위 `try` 로 감싼다. `catch` 는 **`RuntimeException`** 이다 — §1 의
IMPORTANT 대로 경계는 예외 타입이 아니라 항목이며, `SessionInboxException` 이나 코덱 예외로 좁히면
`Principal.Type.valueOf` 가 그대로 새어 나간다.

| 백엔드 | 바꾸는 자리 | 바뀌지 않는 것 |
|---|---|---|
| Redis | `collectTier:151` 의 `out.add(codec.decode(payload, entryId))` | `COLLECT_SCRIPT` 의 Lua 는 **한 글자도** 바뀌지 않는다 |
| Postgres | `collect:133` 의 디코드 루프 | `SQL_COLLECT`, 트랜잭션 경계, `commit()` 위치 |
| MongoDB | `collect:103` 의 `out.add(codec.decode(doc))` | `findOneAndDelete` 루프와 정렬 |

예상 결과(아직 **측정하지 않았다** — §8-1): 세 백엔드 모두 `returned 2` · `left 0`.

SPI 문서도 함께 고친다. `SessionInbox:38-39` 의 *"Atomically removes and returns up to all messages"*
는 A1 이후 참이 아니다 — 지운 수와 돌려준 수가 갈릴 수 있다. **계약이 약해지는 것이 아니라 정확해지는
것**이다: 원자적 제거는 그대로이고, 반환이 그 부분집합일 수 있다는 사실이 추가된다. 클래스 javadoc 의
`:19-20` *"remove returned entries atomically"* 는 그대로 참이다.

라우터 쪽 주석은 **이미 이 모양을 예상하고 있다.** `collectPending:1499` 는
*"whatever this collect returns is now this node's to run or to fail, and what it does not return is
not in the inbox for a peer to find either"* 라고 적혀 있다 — 드롭된 항목을 정확히 서술하는 문장이며,
A1 이 그것을 참으로 만든다.

### A2 — 주소는 살릴 수 있는 만큼 살린다

드롭은 **조용하면 안 된다**(§6). 그런데 로그는 운영자에게만 말하고 제출자에게는 말하지 않는다. 그래서
`collect` 가 못 읽은 항목에 대해 **아직 읽히는 것**을 함께 돌려준다.

```java
// at.aimon.core.agent.session.inbox
public final class CollectedBatch {          // final class + 정적 팩토리 (둘 다 필수 필드)
    List<InboundMessage> messages();         // 읽힌 것. priority-then-FIFO
    List<UnreadableEntry> unreadable();      // 지웠지만 읽지 못한 것
}

public final class UnreadableEntry {         // final class + builder (선택 필드가 둘)
    InboundMessageId id();                   // 백엔드 항목 id — 스트림 id / row id / _id
    Optional<TurnId> turnId();               // 원문에서 best-effort
    Optional<String> idempotencyKey();       // 원문에서 best-effort
    String reason();                         // 예외 메시지. 페이로드는 절대 담지 않는다
}
```

`SessionInbox.collect` 의 반환이 `List<InboundMessage>` 에서 `CollectedBatch` 로 바뀐다.

**주소가 실제로 살아남는다는 것은 확인했다.** §1 의 Redis 재현에서 같은 손상 페이로드를 평범한
`readTree` 로 읽으면 `conversationId=c-repro-2 turnId=turn-abc idempotencyKey=key-abc` 가 그대로
나온다 — 세 값 모두 enum 도 날짜도 아닌 평문 문자열이고, Mongo 에서도 `payload` 하위 문서의 문자열이다
(`InboundMessageCodec:136,144`). 문서가 통째로 깨져 아무것도 못 읽는 경우에는 두 값이 비고, 그때
`announceTurnFailure:1821-1827` 이 *"Unaddressable rather than corrupt"* 로 이미 DEBUG 한 줄을 남기고
돌아간다 — **없는 경로를 새로 만들 필요가 없다.**

라우터의 처분은 드레인 경로가 **거절한 메시지에 대해 이미 하는 것과 같다**:

```
announceTurnFailure(convId, turnId, idempotencyKey, code, "…")
    → safeDiscardReservation(key)   // 예약을 풀어 재시도가 죽은 시도에 붙지 않게 한다
    → publishTurnOutcome(...)       // 크로스 노드 레일
    → resolveForward(...)           // 제출 노드의 future 를 즉시 끊는다
```

`IdempotencyStore.discardReservation` 의 javadoc 이 자기 사용 조건을 *"only for a message already
taken out of the at-most-once inbox"* 라고 적어 두었는데, 드롭된 항목은 정확히 그 상태다. **새 개념이
아니라 이미 있는 상태의 새 진입점이다.**

#### 실패 코드는 새로 하나 만든다

`TurnResultPayload.Failure.Code` 의 넷 중 맞는 것이 없다. `FAILED` 는 *"attempted and threw"* 이고
드롭된 항목은 시도된 적이 없다 — 그 구별은 `NOT_HOLDER` 의 javadoc 이 *"resubmit this, it never ran"*
과 *"this input was attempted and threw"* 를 가르며 **호출자에게 의미 있다고 스스로 적어 둔** 축이다.
그러므로 `UNREADABLE` 을 더한다.

와이어 호환은 **이미 확보되어 있다** — `Code.parse:274-282` 가 모르는 값을 DEBUG 한 줄과 함께
`FAILED` 로 떨어뜨린다. 즉 옛 노드는 이 코드를 "시도됐다 실패했다"로 읽고, 이는 무응답보다 낫고
`resolveForward` 는 어느 쪽이든 동작한다.

#### 왜 A2 를 "나중" 으로 미루지 않는가

셋 다 독립적으로 충분하다.

1. **백로그 §5 가 적은 피해의 절반이 제출자 쪽이다** — *"제출자 입장에서는 약속받은 턴이 조용히 사라진
   것이고, 재시도할 근거도 남지 않는다."* A1 만으로는 그 절반이 남는다.
2. **메트릭이 A2 에 종속된다**(§6). 드롭을 셀 수 있는 자리는 라우터뿐이고, 라우터가 세려면 반환 모양이
   필요하다.
3. **A1 만 넣으면 그 뒤로 A2 를 부르는 신호가 없어진다.** 배치 소실이 사라지면 남는 것은 5분 타임아웃
   하나이고, 그것은 아무 로그도 남기지 않는다. 지금 함께 하지 않으면 다음 사람이 이 자리를 다시 찾을
   근거가 없다.

**공개 시그니처가 바뀐다는 것은 인정하고 넘어간다.** `SessionInbox` 는 배포 모듈 `aimon-core` 의
공개 SPI 이므로 이것은 컴파일 브레이크다. `default` 메서드로 옛 시그니처를 남기는 길은 **일부러 고르지
않았다** — 그러면 갱신되지 않은 구현이 "못 읽은 것 없음"을 조용히 보고하게 되고, 그 상태는 이
저장소가 반복해서 거절해 온 모양이다(선언과 계산이 갈리는 자리). `0.x` 의 약속대로
`CHANGELOG.md` 와 [`migration/rename-maps.md`](../../migration/rename-maps.md) 에 적는다.

---

## 5. 후보 3 × 백엔드 3

각 칸은 **비용 / 잃는 것 / 얻는 것** 순이다.

### Redis

| | (a) 항목 격리 | (b) 지우기 전 디코드 | (c) dead-letter |
|---|---|---|---|
| 비용 | 디코드 한 줄을 `try` 로 감싼다. Lua 무손상 | `COLLECT_SCRIPT` 를 XRANGE 전용으로 쪼개고 XDEL 을 두 번째 왕복으로 뺀다. 티어당 왕복 1 → 2 | XDEL 이 **이미 커밋된 뒤에야** 못 읽었다는 것을 안다 → dead 스트림 XADD 는 **독립적으로 실패할 수 있는 두 번째 연산** |
| 잃는 것 | 못 읽은 항목의 바이트 | **이 백엔드의 유일한 원자성 방어.** Postgres 의 `SKIP LOCKED`·Mongo 의 `findOneAndDelete` 에 해당하는 것이 Redis 에서는 이 스크립트 하나다. SPI `:19-20` 문구를 고쳐야 한다 | 보장이 성립하지 않는다(위). 보장하려면 "배치를 staging 스트림으로 원자 이동 → 자바 디코드 → 정상 삭제·불량 dead 이동" 2단계 + staging 회수 스위퍼 = **컨슈머 그룹의 수제 재구현** |
| 얻는 것 | 나머지 전부 | 바이트가 스트림에 남는다 — 그런데 남은 그것이 곧 **영구 poison** 이다 | 바이트 보존(단, best-effort) |

### Postgres

| | (a) | (b) | (c) |
|---|---|---|---|
| 비용 | 디코드 루프를 `try` 로 감싼다 | **가능하다.** `commit():121` 을 디코드 뒤로 옮기고 실패 시 `rollback()`. `FOR UPDATE SKIP LOCKED` 의 행 잠금이 디코드 동안 유지되므로 **원자성을 잃지 않는 유일한 백엔드** | 같은 트랜잭션 안의 `INSERT INTO conversation_inbox_dead` → **원자적으로 된다.** 대가는 새 테이블 = `V1__init.sql` 변경 = `PostgresSchemaFreezeTest` 갱신 |
| 잃는 것 | 못 읽은 항목의 바이트 | **전체 롤백이면 그 세션의 인박스가 영구히 드레인되지 않는다** — 다음 `collect` 가 같은 행을 다시 읽고 같은 예외를 낸다. **부분 삭제면** poison 행이 남아 `isEmpty` 가 영원히 false 이고, `mayHaveQueuedWork:2193` 이 pending forward 의 재-doorbell 을 forward TTL 내내 울린다 | **런타임은 DDL 을 실행하지 않는다**([`backends.md`](backends.md) *"운영자가 환경당 한 번 적용하고, 런타임은 DDL 을 실행하지 않는다"*) — 즉 배포해도 **운영자가 마이그레이션을 돌리기 전까지 아무것도 보존되지 않는다** |
| 얻는 것 | 나머지 전부 | 바이트가 테이블에 남는다 | 바이트 보존 + 사후 조사 |

### MongoDB

| | (a) | (b) | (c) |
|---|---|---|---|
| 비용 | 디코드 한 줄을 `try` 로. **셋 중 개선 폭이 가장 크다** — 오늘은 앞서 지운 정상 항목까지 함께 버린다 | `findOneAndDelete` → `find().sort().limit(1)` + 디코드 + `deleteOne(_id)`. 배치당 왕복 ≤64 → ≤128 | `findOneAndDelete` 뒤 `insertOne(dead)` 는 두 연산. 레플리카셋 트랜잭션으로 묶는 길은 있으나 **이 모듈에는 트랜잭션 사용이 0건**이다. 새 컬렉션 = `init.js` + `MongoSchemaFreezeTest` + 운영자 적용 |
| 잃는 것 | 못 읽은 항목의 바이트, **그리고 오늘 우연히 살아남던 뒤쪽 꼬리**(아래) | 원자 청구 — 클래스 javadoc 이 *"defensive against any future relaxation"* 이라고 적어 둔 그 방어 | Postgres 와 같은 운영자 선행 조건 |
| 얻는 것 | 나머지 전부 + 앞쪽 정상 항목 | 바이트가 컬렉션에 남는다. 그 대가로 정렬 선두 차단은 **오늘과 같다** — (b) 가 이 백엔드에서 고치는 것은 사실상 없다 | 바이트 보존 |

IMPORTANT: Mongo 의 "뒤쪽 꼬리"는 (a) 가 없애는 유일한 **보존** 항목이므로 정확히 적는다. 오늘 그
꼬리가 살아남는 것은 안전망이 아니라 **정체**다 — 다음 `collect` 가 정렬 선두의 같은 문서에서 다시
던지므로, 그 꼬리는 그 세션을 읽을 수 있는 빌드가 오기 전까지 **영구 head-of-line 차단** 상태로 남는다.
(a) 는 보존을 잃는 대신 그 차단을 없앤다.

### 세 후보를 한 줄로

| 후보 | 결정 | 한 줄 |
|---|---|---|
| **(a)** | **채택** | 세 백엔드 모두에서 **새 실패 모드 없이** 순개선. 원자성 무손상, 새 저장 표면 없음, 배포 즉시 효과 |
| (b) | 기각 | §5.1 |
| (c) | 기각(보류) | §5.2 |

### 5.1 (b) 를 기각하는 이유

1. **SPI 계약을 정면으로 어긴다.** `SessionInbox:19-20` 이 *"remove returned entries atomically"* 를
   구현자 의무로 적고 있고, Redis·Mongo 에서 (b) 는 그 문구를 고쳐야만 가능하다. **드문 디코드 버그
   하나를 고치려고 SPI 의 동시성 계약을 넓히는 것**은 비용의 방향이 반대다.
2. **없애려던 것보다 나쁜 것을 만든다.** 남은 poison 은 (i) 전체 롤백이면 **그 세션의 모든 후속
   메시지**를, (ii) 부분 삭제면 `isEmpty` 를 영원히 오염시킨다. 오늘의 결함은 **드물고 1회성**인데
   (b) 이후의 것은 **드물지만 영구적**이다.
3. **원자성을 지키면서 할 수 있는 것이 한 백엔드뿐이다.** Postgres 는 트랜잭션 안에서 되지만
   Redis·Mongo 는 안 된다(2번의 poison 은 셋 다에 남는다). `aimon-session-testkit` 의 전제는 세 백엔드가
   같은 답을 한다는 것이고, (b) 는 그것을 못 지킨다.

부수적으로: (b) 는 못 읽은 항목을 **어떻게든** 처분해야 하므로 결국 (a) 나 (c) 를 안에 품는다. 단독
처방이 아니다.

### 5.2 (c) 를 기각(보류)하는 이유 — 그리고 "아무도 안 읽으면 (a) 와 같은가"

**같지 않다.** (a) 는 바이트를 없애고 (c) 는 남긴다. 남은 바이트로 할 수 있는 일이 둘 있다 —
운영자의 사후 조사, 그리고 **읽을 수 있는 빌드가 나중에 재투입**하는 것. 두 번째는 §2 가 남긴 트리거
인구의 절반(새 `Principal.Type` 을 쓴 더 새로운 노드의 멀쩡한 문서)에 대해 실제로 의미가 있다.
그러므로 (c) 는 "쓸모없다" 가 아니라 **"지금 사기에는 비싸다"** 로 기각한다.

값이 안 맞는 이유 넷:

1. **보장이 세 백엔드에서 성립하지 않는다.** Redis 는 삭제가 이미 커밋된 뒤에야 판단이 서므로,
   두 단계 claim/ack 을 새로 만들지 않는 한 dead-letter 쓰기는 **best-effort** 다. 두 백엔드에서만
   지켜지는 보장은 보장이 아니다.
2. **효과가 배포 시점이 아니라 운영자 시점에 시작된다.** Postgres 와 Mongo 는 DDL/`init.js` 를 런타임이
   실행하지 않으므로, 새 표면은 운영자가 클러스터마다 마이그레이션을 돌린 뒤에야 존재한다. (a) 는
   재배포만으로 효과가 난다.
3. **표면 하나가 정책 셋을 부른다.** 보존 기간, `purge(sessionId)` / `deleteSession` 이 dead 까지
   지우는가, 크기 알람([`backends.md`](backends.md) 의 인박스 1000만 행 기준에 대응하는 것). 셋 다
   지금 답할 근거가 없다.
4. **읽는 사람이 없다.** 재투입 경로(dead → inbox)는 이 설계가 만들지 않는다. 만들려면 at-most-once
   계약과 멱등 원장을 다시 따져야 한다 — A2 가 방금 `discardReservation` 으로 풀어 준 키를 되살리는
   일이기 때문이다.

**재검토 트리거**(§9 에 다시 적는다): 운영에서 인박스 디코드 실패가 **반복** 관측될 때, 또는
dead-letter 를 읽는 소비자가 실제로 생길 때. 그때 (c) 는 (a) 위에 얹히는 증분이고, (a) 를 되돌릴 필요가
없다.

---

## 6. 관측 가능성

### 6.1 로그

| | 값 |
|---|---|
| 레벨 | **WARN** |
| 개수 | 드롭된 **항목마다 한 줄** (배치 요약 줄은 두지 않는다 — 세는 것은 줄 수로 되고, 같은 사건에 두 줄은 소음이다) |
| 담는 것 | 세션 id, 백엔드 항목 id(스트림 id / row id / `_id`), 예외 메시지, 배치 안 위치(`k of n`) |
| 담지 않는 것 | **페이로드.** 이 저장소의 규칙이며 `decodeOrText` 가 *"the payload itself is never logged"* 로 적어 두었다 |

**WARN 인 이유**: [`error-handling.md`](../../../.claude/rules/error-handling.md) 의 표에서 WARN 은
"예상되는 에러"다. 그리고 이 트리의 형제 사례가 전부 WARN 이다 — 신호 디코드 실패
(`RedisPubSubSignalBus:176` · `MongoSessionSignalBus:260` · `ListenDispatcher:230`), 메모리 저널
리플레이(`FileObservationStore:149` · `FileWorkspaceStore:121`), `UserInputCodec.degrade:318`.
ERROR 는 과하다 — 나머지 배치는 정상적으로 계속된다.

**세션 id 가 필수인 이유**는 §0.5 가 이미 적었다 — *"어느 세션의 턴이 텍스트로 떨어졌는지 못 말하면
'관측 가능' 이 '어딘가 줄이 하나 있다' 가 된다."* 같은 문장이 여기에 그대로 적용된다.

IMPORTANT: **그런데 그 형제 사례들과 이 자리는 성질이 다르고, 그래서 로그만으로는 부족하다.** 저널
리플레이와 스냅샷 로드가 건너뛴 것은 **저장소에 그대로 남고**, 신호는 애초에 at-least-once 라 다시
온다. 인박스는 **지운 뒤에 건너뛴다** — 건너뛴 것을 다시 볼 방법이 없다. 그래서 이 자리에는 §4 의 A2 가
붙는다. "로그만으로 충분한가"에 대한 이 문서의 답은 **아니오** 이고, 부족한 쪽은 운영자가 아니라
제출자다.

### 6.2 메트릭

[`backends.md`](backends.md) §8 은 `aimon.session.inbox.collect{outcome=success|empty|error}` 를
**버킷 A(매니저 소유)** 로 이미 계획해 두었다. 이 결함이 요구하는 것은 그 축에
`outcome=partial` 과 드롭 건수 카운터를 더하는 것이다.

**다만 그 계획에는 오늘 구현이 없다** *(2026-09-06 확인)* — `SessionMetrics` 에 인박스 훅이 없고,
main 소스에 `aimon.session.inbox` 리터럴이 0건이며, Micrometer 는 스타터에만 있다. 그리고 결정적으로
**백엔드 모듈은 `SessionMetrics` 를 볼 수 없다** — 그것이 `aimon-session-routing` 에 있고, 백엔드가 그
모듈을 main 에서 보지 않게 만든 것이 [`spi-extraction.md`](spi-extraction.md) 의 결론이다.

따라서 **드롭을 셀 수 있는 자리는 라우터 하나뿐이고, 라우터가 세려면 §4 의 A2 반환 모양이 필요하다.**
이것이 A2 를 미루지 않는 두 번째 이유다(§4). 백엔드 안에 코어용 메트릭 SPI 를 새로 만드는 길은 고르지
않는다 — 한 카운터를 위해 공개 표면을 하나 더 만드는 값이 안 나오고, 계획된 버킷 A 와 두 번째 진실
원천이 된다.

---

## 7. 테스트 전략

### 7.1 두 층

**1층 — 데몬 없음, 백엔드마다, 기존 `internal/*CodecTest` 옆.** 봉투의 세 필드가 **여전히 던지는가**를
고정한다. 이 설계가 바꾸는 것은 *던진 뒤의 처분*이지 *던질지 여부*가 아니며(백로그 §5 본문의
*"그것이 맞다"*), 이 테스트가 없으면 다음 사람이 `decodeOrText` 의 저하를 봉투 전체로 넓혀 이 항목을
"닫을" 수 있다. 그 확장은 손상된 문서를 **정상인 척 실행**하게 만든다.

**2층 — docker, 공유.** `aimon-session-testkit` 에 `AbstractSessionInboxDurabilityContractTest` 를 두고
세 백엔드가 상속한다.

| 왜 여기인가 | |
|---|---|
| 성질의 성격 | 이것은 **세 백엔드가 같은 답을 해야 하는** 성질이다. 그것이 이 모듈의 존재 이유이며 build 파일이 *"so every session backend can run it"* 이라고 적어 둔 것이다 |
| 백로그 §2 작업이 범위 밖에 둔 이유 | *"픽스처가 백엔드마다 다르다."* 그것은 이 자리를 피할 이유가 아니라 **추상 메서드가 필요한 이유**다 — 다른 것은 픽스처뿐이고 단언은 하나다 |
| `SessionBackend` 에 넣지 않는 이유 | 그 타입은 네 SPI 만 노출하고 하네스는 *"never looks inside again"* 이다. 심는 데 필요한 것은 원시 핸들(Lettuce 커넥션 / `DataSource` / `MongoDatabase`)이므로, 이미 그것을 들고 있는 **백엔드 쪽 테스트 클래스**가 구현하는 추상 메서드가 맞다 |

서브클래스가 구현할 것은 셋이다.

```java
protected abstract SessionInbox inbox();
protected abstract InboundMessageId plantUnreadableEntry(SessionId id, QueuedInputPriority tier,
        String turnId, String idempotencyKey);   // 손상 문서를 그 백엔드의 원시 API 로 심는다
protected abstract long countStored(SessionId id);
```

### 7.2 케이스

| # | 무엇을 고정하나 |
|---|---|
| 1 | 정상 2건 **사이에** 심는다. 선두나 말미에만 심으면 Mongo 변종(앞서 지운 것까지 함께 버림)이 가려진다 |
| 2 | `collect` 가 정상 항목을 **priority-then-FIFO 로 전부** 돌려준다 |
| 3 | 그 세션의 저장소가 **0** 이 된다 — 드롭된 것도 지워졌다. 재시도 루프도 poison 도 생기지 않는다는 것이 여기서 고정된다 |
| 4 | 드롭마다 WARN 이 하나, **세션 id 를 담아서** 남는다 (`ListAppender`, `UserInputCodecTest` 의 선례) |
| 5 | **A2** — 심은 문서의 `turnId`/`idempotencyKey` 가 `unreadable()` 로 되돌아온다. 그 둘을 빼고 심으면 빈 `Optional` 이 되고 예외가 나지 않는다 |
| 6 | **A2** — 주소가 있는 드롭에 대해 라우터가 `UNREADABLE` 을 알린다. 두 노드 하네스가 필요하므로 `AbstractMultiNodeSessionContractTest` 쪽 케이스다 |

`InMemorySessionInbox` 는 **참여하지 않는다.** 객체를 그대로 담고 디코드가 없어 이 성질을 만들 수
없다 — 계약을 못 도는 것이 아니라 **계약이 말하는 것이 그 구현에는 없다.**

전부 `@Tag("docker")` 다([`testing.md`](../../../.claude/rules/testing.md)).

### 7.3 규칙 다섯의 순서

[`backlog/README.md`](../../backlog/README.md) 규칙 다섯은 **고치기 전에 실패하는 테스트**를 요구한다.
이 항목에서는 그것이 값싸다 — **오늘의 트리가 이미 빨간 상태**이고 §1 이 세 백엔드에서 그것을
실측했다. 위 케이스 1~4 를 먼저 넣으면 세 백엔드가 전부 FAILED 로 시작한다.

변이 검사(처방을 되돌려 보는 것)도 정해 둔다.

| 되돌리는 것 | 기대 |
|---|---|
| 세 백엔드의 항목별 `catch` 제거 | 케이스 2·3 이 셋 다 FAILED |
| `catch` 를 `SessionInboxException` 으로 좁힘 | §1 의 `IllegalArgumentException` 이 다시 새어 나가 셋 다 FAILED — 경계가 타입이 아니라 항목이라는 것이 여기서 고정된다 |
| WARN 에서 세션 id 만 제거 | 케이스 4 만 FAILED (드롭 격리는 여전히 통과) |
| A2 의 주소 살리기 제거 | 케이스 5·6 만 FAILED |

---

## 8. 아직 확인되지 않은 것

**실측하지 않은 것은 실측하지 않았다고 적는다.**

1. **고른 처방을 아직 돌려 보지 않았다.** 실측한 것은 진단(§1)과 트리거 인구조사(§2)뿐이다. §4 가
   적은 예상 결과(`returned 2` · `left 0`)는 **예측**이며, 구현할 때 §7 의 케이스가 그것을 측정한다.
2. **A2 의 핵심 주장을 두 노드로 돌려 보지 않았다.** *살린 주소로 `announceTurnFailure` 를 부르면
   제출한 노드의 future 가 즉시 풀린다* 는 `deliverToInbox:2018` · `registerForward:2068` ·
   `pollForward:2109` · `announceTurnFailure:1819` · `resolveForward` 를 **읽어서** 세운 것이다.
3. **오늘의 제출자 경험(5분 뒤 `TimeoutException`)도 같은 읽기에서 나왔다.** `DEFAULT_IDEMPOTENCY_FORWARD_TTL`
   이 `Duration.ofMinutes(5)` 인 것은 확인했지만 그 타임아웃이 실제로 그 문구로 도착하는 것은 보지 않았다.
4. **항목별 `try` 가 드레인 지연에 미치는 영향을 재지 않았다.** 예외 경로가 아니면 비용이 없다고 보지만
   측정은 아니다. 재는 자리는 있다 — [`backends.md`](backends.md) §3 이 Postgres 의 드레인 p95 25 ms 를
   알람선으로 잡아 두었다.
5. **새 `Principal.Type` 이 실제로 생길지는 모른다.** §2 의 그 트리거는 `Principal.Type.valueOf` 를
   읽어서 세운 것이지 관측한 것이 아니다. 관측된 트리거는 손상 문서 하나뿐이고, 그것도 이 문서가 직접
   심은 것이다.
6. **(c) 의 Mongo 원자성을 확인하지 않았다.** 레플리카셋 트랜잭션으로 `findOneAndDelete` + `insertOne`
   을 묶을 수 있는지는 읽지 않았다 — 이 모듈에 트랜잭션 사용이 **0건**이라는 것만 확인했다. (c) 의 기각은
   Redis 축 하나로 이미 성립하므로 이 확인을 하지 않았고, (c) 를 되살릴 때는 **여기부터** 봐야 한다.
7. **`UNREADABLE` 코드를 옛 노드가 실제로 `FAILED` 로 읽는 것을 돌려 보지 않았다.** `Code.parse:274-282`
   를 읽어서 세운 것이다.

---

## 9. 이 설계가 고치지 않는 것

| 항목 | 왜 |
|---|---|
| **못 읽은 항목의 바이트** | (a) 는 그것을 복구하지 않는다. 복구는 (c) 이고 §5.2 가 보류했다. **재검토 트리거**: 운영에서 인박스 디코드 실패가 **반복** 관측될 때, 또는 dead-letter 를 읽는 소비자(재투입 도구)가 실제로 생길 때 |
| **새 우선순위 티어가 만드는 조용한 미수집** | §2 가 측정한 별개 고장이다 — 옛 노드는 새 티어의 항목을 던지지 않고 **영원히 수집하지 않는다**. 트리거는 `QueuedInputPriority` 에 값이 추가되는 것이고, 그때 이 문서가 아니라 봉투 호환 쪽에서 다뤄야 한다 |
| **봉투 세 필드의 저하 여부** | [백로그 §5](../../backlog/interrupt-open-items.md) 의 판단(*"계속 던지는 것이 맞다"*)을 유지한다. 이 설계가 바꾸는 것은 **던진 뒤에 무엇을 잃는가**이지 던질지 말지가 아니다 |
| **Mongo 의 "뒤쪽 꼬리" 보존** | (a) 이후 살아남지 않는다. 그 보존은 안전망이 아니라 영구 head-of-line 차단이었다(§5, MongoDB 표의 IMPORTANT) |
| **인박스 메트릭 전반** | `aimon.session.inbox.*` 는 계획만 있고 구현이 없다(§6.2). 이 설계는 그중 한 축(`outcome=partial` + 드롭 카운터)이 **어디에 꽂혀야 하는지**만 정한다 |

---

## 관련 문서

- [`../../backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) — 이 문서가 채우는 항목(§5)과 그 앞의 정정들(§0.4 · §0.5)
- [`routing.md`](routing.md) — `SessionInbox` 가 사는 계층과 §5.6 의 봉투 계약
- [`backends.md`](backends.md) — 세 백엔드의 스키마·운영자 마이그레이션·메트릭 버킷
- [`spi-extraction.md`](spi-extraction.md) — 백엔드가 `SessionMetrics` 를 볼 수 없는 이유
- [`../../overview/glossary.md`](../../overview/glossary.md) — turn / execution, `SessionInbox` 의 수명
- [`../../../.claude/rules/error-handling.md`](../../../.claude/rules/error-handling.md) — 로그 레벨 표
- [`../../../.claude/rules/testing.md`](../../../.claude/rules/testing.md) — `@Tag("docker")` 규약
- [`../../project/api-stability.md`](../../project/api-stability.md) — `0.x` 에서 공개 시그니처를 바꿀 때의 약속
