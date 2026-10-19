# 멱등 제출과 홀더 유실 회복

**Status**: IMPLEMENTED — `aimon-core` `at.aimon.core.agent.session.idempotency`, `aimon-session-routing`
(`IdempotencyTouchSlot`, `HolderLossSweeper`, `LostTurnAnnouncer`, 드레인 경로의 넘겨받기). 남은 것은 [마지막 절](#남은-것).

`SubmitRequest.idempotencyKey`(클라이언트 발급)로 제출을 at-most-once 로 만드는 멱등 원장의 의미와, 그 원장의 예약을 이용해 죽은 홀더의
턴을 찾아 알리는 회복 경로를 정의한다. 원장을 부르는 제출 시퀀스는 [`routing.md`](routing.md) §7 이 정본이다.

---

## 1. 범위

**키로 dedup 하지 메시지 내용으로 하지 않는다.** 내용 기반 dedup 은 인박스의 일도 이 원장의 일도 아니다. 같은 키에 다른 입력이 오는지는
`inputHash`(정규화 후 SHA-256)로 비교한다.

원장 항목은 `IN_FLIGHT`(누군가 돌리는 중) 와 `DONE`(캐시된 결과) 두 상태를 갖는다. `DONE` 에 남는 결과는
`StoredAgentExecutionResult` — 최종 답변, 에러 메시지, 작은 enum — 의 투영이다.

---

## 2. 매트릭스

| 케이스 | 라우터 동작 | 응답 |
|---|---|---|
| 키 없음 | dedup 안 함 | 정상 |
| 처음 보는 키 | `IN_FLIGHT` 기록 후 정상 처리 | 정상 |
| 같은 키 + 같은 입력 + 진행 중 | 새 턴 없음, **기존 턴에 attach** | 합성 id 로 `FORWARDED` — 결과는 그 턴의 future |
| 같은 키 + 같은 입력 + 완료 | 캐시된 결과로 즉시 완결 | `EXECUTED_LOCALLY`(이미 완료된 future) |
| 같은 키 + **다른** 입력 | 명백한 클라이언트 버그 | `IdempotencyConflictException` (409) |
| 같은 키 + primary TTL 경과 | 새 키처럼 | 정상 |
| 같은 키 + `IN_FLIGHT` + stale | `compareAndReset` 후 재진입 | 정상 |

턴이 끝나면 `markDone` 을 **먼저** 하고 그다음 `TURN_RESULT` 를 발행한다. `TURN_RESULT` 를 놓친 노드에게 권위는 원장이고, 대기 중인 forward 의
폴링 fallback 이 원장을 읽는다.

---

## 3. 두 TTL 과 touch

**primary(기본 24시간)만 있으면** 홀더가 죽었을 때 `IN_FLIGHT` 항목이 24시간 내내 박혀 있다. 그래서 `IN_FLIGHT` 에는 짧은 **secondary TTL**
(기본 30초 = 리스와 같다)을 따로 두고, 홀더가 살아 있는 동안 리스 갱신이 함께 touch 한다. 홀더가 죽으면 secondary 가 지나 항목이 stale 이
되고(§6), primary 는 `DONE` 전이 뒤에야 효력을 갖는다.

**touch 는 리스 갱신에 얹혀 있다.** 갱신이 성공할 때마다 그 시점에 `IdempotencyTouchSlot` 에 바인딩된 예약의 secondary TTL 을 함께 갱신한다.

- **키를 스케줄 시점에 캡처하지 않고 틱마다 읽는다.** 리스는 턴보다 오래 산다 — 턴 1 을 위해 시작된 스케줄이 턴 5 가 돌 때도 살아 있다.
  캡처했다면 첫 턴 이후 모든 턴이 이미 끝난 예약을 갱신하고 자기 것은 방치했을 것이고, 스위퍼가 secondary TTL 뒤에 살아 있는 턴의 키를
  리셋해 같은 요청이 두 번 실행된다
- **칸 하나가 `(키, reserverId)` 쌍이다.** 둘을 따로 두면 틱이 턴 5 의 키를 턴 4 의 reserver 로 읽을 수 있고, `touch` 는 홀더가 어긋나면
  조용히 무시한다
- **예약 하나에 칸 하나다.** 한 패스가 두 예약을 동시에 책임질 수 있다. 제출된 턴이 여는 드레인 패스는 인박스에 있던 메시지도 함께 돌리는데,
  제출 자신의 예약은 호출자가 기다리므로 패스 내내 살아 있어야 하고, 큐에 있던 메시지의 예약은 그 메시지가 도는 동안만 이 노드 것이다.
  칸이 하나뿐이면 둘이 서로를 밀어내고, 형제 턴 하나가 secondary TTL 을 넘기는 것은 LLM 턴에서 흔하므로 밀려난 쪽이 멀쩡히 돌고 있는
  노드에서 홀더 유실로 스윕된다
- **touch 가 지목하는 것은 예약의 홀더다** — 리스의 홀더(`SessionLease.getHolderId()`)가 아니다(§4)

운영 기본값: 리스 30초, secondary 30초, 홀더 손실 스윕 15초.

---

## 4. 예약의 홀더

예약의 홀더는 **리스 홀더와 다른 문자열**이다. 리스는 노드 id 로 잡히고([`routing.md`](routing.md) §7.3), 예약의 홀더는 시도마다 새로 발급되는
`reserverId` 다.

**홀더 이름은 노드 사이를 오간다.** 제출 노드가 선출에서 지면 `releaseHolder` 로 홀더를 비운 채 메시지를 인박스로 넘기고, 그 메시지를 수집한
노드가 `acquireHolder` 로 자기 이름을 다시 적으면서 TTL 을 secondary 로 되돌린다. 두 연산은 서로의 역이고 둘 다 원자적이다 — 같은 예약을 두
노드가 동시에 집어도 정확히 하나만 이긴다.

그래서 이름이 비어 있는 구간은 **아무도 돌고 있다고 알려지지 않은** 구간이고, 그것이 `findStaleInFlight` 가 홀더 없는 항목을 건너뛰는
근거다. "아무도 돌지 않는" 과 같지는 않다 — 넘겨받기가 거절되면 이름 없는 예약 위에서 도는 턴이 생기고(§5), 그 턴에 대해서는 이 배제가 실제로
커버리지를 잃는다. 이것은 의도한 교환이다. **거짓 양성은 멀쩡한 턴을 죽이고 클라이언트 재시도를 이중 실행시키지만, 놓친 것의 대가는 호출자가
forward 마감까지 기다리는 것뿐이다.**

---

## 5. 수집된 메시지의 예약 넘겨받기

드레인 패스는 인박스에서 꺼낸 메시지를 돌리기 직전에 그 예약의 홀더를 `acquireHolder` 로 넘겨받고, `(키, reserverId)` 를 리스의
`IdempotencyTouchSlot` 에 바인딩한다. 그래서 수집된 메시지도 로컬 턴과 똑같이 보인다 — 그것을 돌리던 노드가 죽으면 touch 가 멈추고, secondary
TTL 이 지난 뒤 같은 스윕(§6)이 그것을 잡으며, 제출 노드의 forward 는 `HOLDER_LOST` 로 같은 ~45초 안에 답을 받는다. 넘겨받기가 없으면 이 턴은
이름이 없어 스윕에서 빠지고, 호출자는 forward TTL(기본 5분) 타임아웃으로만 답을 받는다. 재-초인종은 여기에 쓸모가 없다 — 메시지가 이미 인박스
밖이라 드레인 패스가 집어 올 것이 없다.

**넘겨받기가 실패해도 그 메시지는 그대로 실행한다.** 메시지는 이미 at-most-once 인박스 밖에 있어서, 거절하면 아무 노드도 복구할 수 없다. 패스를
연 제출 자신의 메시지는 시도하지 않는다 — 그 예약에는 이미 이 노드 이름이 적혀 있다.

넘겨받기의 결과는 셋이고, 셋은 같지 않다.

| 결과 | 무엇인가 | 결과 캐시(`markDone`) | 정리(touch 바인딩 · 실패 시 리셋) |
|------|---------|-----------------------|-----------------------------------|
| **승리** | 이 노드가 홀더가 됐다 | 쓴다 | 한다 |
| **거절** | 저장소가 "이 항목은 당신 것이 아니다" 라고 **대답했다** — `DONE`(누군가의 캐시된 답), 홀더가 있는 예약(다른 노드가 도는 중), 만료된 예약(그 forward 는 이미 포기했다) | **쓰지 않는다** | 하지 않는다 |
| **알 수 없음** | 저장소가 던졌다 — 아무것도 판정하지 않았다 | 쓴다 | 한다 |

- **거절에서 쓰지 않는 이유.** `markDone` 은 모든 백엔드에서 키만 보고 매치한다. 방금 남의 것이라고 판정된 항목을 덮어쓰면 클라이언트가 이미
  받은 답을 그가 본 적 없는 답으로 바꾼다
- **알 수 없음은 거절이 아니다.** 침묵을 거절로 읽으면 안 된다 — 읽지 못한 호출자는 소유에 대해 아무것도 배우지 않았다. 예외 경로에서 결과
  쓰기를 걸러 내면 그것 자체가 회귀다. 성공한 턴의 항목이 홀더 없는 `IN_FLIGHT` 로 forward TTL 내내 남고, 홀더가 없으니 스위퍼도 보지 못하며,
  `TURN_RESULT` 를 놓친 노드는 그것을 5분 동안 폴링하다 이미 성공한 턴에 대해 타임아웃한다. 그 사이 재시도는 `alreadyInFlight` 로 그 죽은
  예약에 붙는다. 시도해서 실패하는 `markDone` 이 엄격히 낫다
- **알 수 없음을 "아무 일도 없었음" 으로도 읽으면 안 되고, 이쪽이 더 비싸다.** 원격 저장소가 던지는 가장 흔한 방식은 쓰기는 적용되고 응답만
  유실되는 것이다. 그러면 항목에는 이 노드 이름이 박혀 있는데 touch 하는 것이 없고, 이름이 있으면서 갱신되지 않는 항목은 `findStaleInFlight`
  가 **죽음으로 읽는** 모양이다 — 멀쩡히 도는 턴이 `HOLDER_LOST` 로 통보되고, 키가 풀려 재시도가 같은 요청을 두 번 실행한다. 그래서 예외
  경로도 예약을 touch slot 에 바인딩한다. 쓰기가 적용되지 **않았다면** 그 바인딩은 무해하고 효력도 없다 — `touch` 와 `compareAndReset` 은 둘 다
  홀더 일치로 매칭하고 `reserverId` 는 시도마다 새로 발급되므로 이 노드를 이름으로 갖지 않는 항목에 대해 둘 다 no-op 이다

정리하면, **스위퍼가 이 노드의 죽음을 그 턴에 대해 볼 수 없는 경우는 항목에 이 노드 이름이 남지 않는 경우 — 거절, 그리고 쓰기가 적용되기 전에
던진 경우 — 다.** 호출자는 forward 마감으로 되돌아간다. **결과를 캐시에 쓰지 않는 경우는 거절뿐이다.** 호출자는 넷 모두 `TURN_RESULT` 로 답을
받는다. 거절은 `SessionMetrics.onReservationTakeOverRefused` 로 센다 — 결과가 의도적으로 캐시되지 않고 다른 흔적을 남기지 않으므로, 이 카운터가
"같은 요청이 두 번 실행됐나" 의 유일한 신호이고 정상 상태에서 0 이어야 한다. 던진 경우는 세지 않는다.

**이 구조가 남기는 대가.** 전달된 턴의 예약은 이제 touch 를 받아야 살아 있으므로 touch 실패로 스윕당할 수 있다. 리스 갱신은 되는데 `touch` 만
secondary TTL 을 넘겨 실패하는 구간 — 리스 백엔드는 멀쩡한데 멱등 백엔드만 흔들리는 구간이고, `IdempotencyTouchSlot.touch()` 는 예외를 삼킨다 —
에서는 살아 있는 전달 턴이 스윕되고, 호출자가 실패하고, 재시도가 같은 요청을 두 번 실행한다. 로컬 키 있는 턴이 이미 살고 있는 조건과 같다.
두 백엔드를 같은 저장소에 두면 이 구간 자체가 없어진다.

---

## 6. 홀더 유실 회복

홀더가 죽으면 리스는 TTL 로 풀리고 다른 노드가 이어받는다. 문제는 구독자와 대기 중인 future 다 — `EVENT` 가 그냥 끊기면 원격 `events(id)`
구독자는 무한 대기한다. `HolderLossSweeper` 가 이것을 자가 회복시킨다.

```
매 holderLossSweepInterval:
  IdempotencyStore.findStaleInFlight — lastTouchedAt < now - secondaryTtl 인 IN_FLIGHT (홀더 있는 것만)
    └─ compareAndReset(key, expectedHolder)   ← 클러스터 전체에서 정확히 한 노드만 이긴다
         ├─ EventSink.emit(InterruptedAt(HOLDER_LOST))  — 로컬 + EVENT rail
         └─ LostTurnAnnouncer.announceHolderLost(...)   — 그 턴을 기다리던 future 들을 지금 실패시킨다
```

- **회복은 턴 스코프다.** 홀더 유실은 세션 유실이 아니다. 리스가 만료되면 후계자가 이어받고, 스윕이 돌기 전에 이미 다음 턴을 돌고 있을 수 있다.
  이벤트 스트림을 완료시키고 `EVICT` 를 broadcast 하면 그것이 후계자의 claim 과 경합해 **살아 있는 세션을 축출**한다 — 캐시를 버리고, 승인을
  폐기하고, 구독자의 스트림을 다른 노드에서 죽은 턴 때문에 완료시킨다. 스위퍼가 감지하는 것은 죽은 시도 하나이고, 그것만 보고한다
- **`EventSink` 에 `complete` 가 없다.** 추상화가 그 말을 할 수 없게 만들었다. `complete` 는 진짜로 세션을 끝내는 경로(`releaseSession`,
  `deleteSession`, `EVICT` 수신)에만 있고, 그 경로들은 구체 타입을 쥔다
- **턴을 `TurnId` 가 아니라 멱등 키로 지목한다.** 살아남은 어느 노드도 그 턴의 id 를 모른다 — 예약은 홀더를 기록하지 홀더가 돌리던 턴을
  기록하지 않고, 그 id 는 죽은 홀더가 소비한 인박스 봉투 안에 있었다. 추측해서 채울 빈칸이 아니다
- **`compareAndReset(key, expectedHolderId)` 가 경합 안전성을 진다.** 여러 노드가 같은 stale 항목을 발견해도 홀더가 일치하는 한 노드만 이기고
  나머지는 새 항목을 본다
- **감지 지연** = `max(리스 TTL, secondary TTL)` + 스윕 지연. 기본값으로 최대 ~45초. stale-reset 빈도가 높으면 노드 안정성 신호로 읽는다

### 6.1 아직 실행되지 않은 메시지 — 재-초인종

스위퍼가 보는 것은 누군가 **돌리던** 턴이다. 홀더가 죽은 시점에 그 세션의 인박스에 아직 collect 되지 않은 메시지가 남아 있으면 그 예약에는
홀더가 없고(제출 노드가 forward 하면서 비웠다) 스윕 대상이 아니다. 리스는 TTL 로 풀리지만 죽은 노드는 초인종을 다시 울려 줄 수 없고, 그
초인종을 들었던 피어들은 전부 "세션이 잡혀 있다" 를 보고 물러난 뒤다.

닫는 쪽은 **기다리고 있는 노드**다. `pollForward` 는 forward 가 미해결인 동안에만 도는 타이머이고, 매 틱마다 그 세션의 초인종을 다시 울린다.
`tryDrainOnce` 를 직접 부르지 않고 `ringDoorbell` 을 쓰는 것은 그 스레드가 스케줄러 스레드이기 때문이다 — 드레인 패스는 턴을 통째로 돌리므로
블록하면 안 되는 스레드에서 돌리지 않는다.

- **인박스가 비어 있으면 울리지 않는다.** 드레인 패스가 집어 올 수 있는 것은 아직 수집되지 않은 메시지뿐이다. 누군가 이미 collect 했다면 그
  메시지는 인박스 밖에 있고 결과를 낼 수 있는 것은 그 노드뿐이다. 이 검사는 피어가 삭제한 세션으로 들어가지 않게 하는 일도 겸한다 — 삭제가
  purge 한 메시지에 대해서는 인박스가 비어 보인다. 다만 되살아나지 않는다는 보장은 아니다: purge 와 레코드 삭제 사이에 배달된 메시지는 큐에
  남고 드레인 패스는 레코드를 되살린다. 그 창을 닫는 것은 삭제가 보내는 `EVICT` 이고(폴 1틱이 오기 훨씬 전에 forward 를 실패시킨다), 재시도는
  그 창을 물려받을 뿐 넓히지 않는다. 읽기가 실패하면 울린다 — 큐를 볼 수 없다는 것이 큐가 비었다는 증거는 아니다
- **회복 시간**은 리스 만료 + 폴 간격 1회로 묶인다. 기본값(리스 30초, 폴 간격 = secondary/2 = 15초) 기준 최대 ~45초다
- **살아 있는 홀더에게는 아무 일도 일어나지 않는다.** 갱신 중인 리스는 뺏기지 않으므로 대기 중인 호출자 하나당 폴 간격마다 실패하는 acquire
  1회가 전부이고, 그 acquire 가 성공한다는 것 자체가 아무도 갱신하지 않는다는 증거다
- 재시도 횟수는 `SessionMetrics.onForwardDoorbellRerung` 이 센다. 회복 횟수가 아니라 재시도 횟수다 — 인수에 성공하면 메시지가 인박스 밖으로
  나가 재시도가 멎으므로, 멎지 않는 비율이 경보 대상이다

---

## 7. `discardReservation`

`IdempotencyStore.discardReservation` 은 **at-most-once 인박스 밖으로 이미 나온 메시지에만** 쓴다. 그 메시지는 돌지 않을 것이므로 예약을 풀어,
재시도가 죽은 시도에 붙지 않게 한다. 드레인 경로가 거절한 메시지와 인박스가 읽지 못한 항목([`inbox.md`](inbox.md) §4.3)이 이 경로를 쓴다.

---

## 8. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| dedup 기준 | 클라이언트 키 + `inputHash` | **내용 기반** — 인박스의 일도 원장의 일도 아니다 |
| TTL | primary 24h + `IN_FLIGHT` 의 secondary 30s | **primary 하나** — 죽은 홀더의 항목이 24시간 박힌다 |
| touch 의 키 | 틱마다 읽는다, 예약당 `(키, reserverId)` 칸 | **스케줄 시점 캡처** — 살아 있는 턴의 키가 리셋되어 이중 실행. **칸 하나** — 형제 예약이 서로를 밀어낸다 |
| 홀더 없는 예약 | 스윕에서 뺀다 | **스윕에 포함** — 거짓 양성이 멀쩡한 턴을 죽이고 재시도를 이중 실행시킨다. 놓침의 대가는 forward 마감뿐이다 |
| 넘겨받기 실패 | 그래도 실행 | **거절** — 인박스 밖이라 아무 노드도 복구할 수 없다 |
| 넘겨받기 결과 | 승리 / 거절 / 알 수 없음 | **성공·실패 둘** — 던진 경우를 거절로 읽으면 성공한 턴이 5분 타임아웃되고, 무사로 읽으면 살아 있는 턴이 홀더 유실로 통보된다 |
| 홀더 유실 회복 | 턴 스코프 | **세션 스코프 `EVICT`** — 살아 있는 세션을 축출한다 |
| 유실된 턴의 지목 | 멱등 키 | **`TurnId`** — 살아남은 노드는 그것을 모른다 |
| 미수집 고아 메시지 | 기다리는 노드의 `pollForward` 가 재-초인종 | **스위퍼가 처리** — 홀더 없는 예약은 스윕 대상이 아니다. **인박스 스캔 SPI** — 실제로 관측되면 그때 |

---

## 하지 말 것

- **`IdempotencyTouchSlot` 의 키를 스케줄 시점에 캡처하지 않는다.** 살아 있는 턴이 두 번 실행된다
- **리스 홀더 id 로 예약을 touch 하지 않는다.** 예약의 홀더로 한다 — 다르면 touch 는 조용히 무시된다
- **넘겨받기가 거절된 노드가 결과를 캐시에 쓰지 않게 한다.** 남의 답을 덮어쓴다
- **넘겨받기가 던진 경우를 거절로 처리하지 않는다**
- **홀더 유실에 세션 스코프 정리(`EVICT`, 스트림 완료)를 하지 않는다**
- **`markDone` 보다 `TURN_RESULT` 를 먼저 내지 않는다**
- **`pollForward` 에서 드레인을 직접 돌리지 않는다.** 스케줄러 스레드다

---

## 남은 것

| 항목 | 지금 |
|---|---|
| 홀더 일치 조건부 `markDone` | `markDone` 은 모든 백엔드에서 키만 보고 매치한다. 넘겨받기에 진 노드가 남의 항목을 덮어쓰는 경로는 라우터가 그 경우 쓰지 않는 것으로 닫았고 테스트가 지킨다 — 그러나 저장소가 강제하는 계약이 아니라 라우터의 규율이다. 넘겨받기와 쓰기 사이에 스위퍼가 예약을 리셋하면 그 사이에 낀 쓰기는 막을 것이 없다. 저장소 쪽에서 닫으려면 SPI 에 `markDone(key, holderId, result)` 를 더하고 백엔드 넷의 조건을 조여야 한다 |
| 실패한 키 있는 로컬 턴의 키가 늦게 풀린다 | 실패를 알리기 전에 키를 풀어야 하지만, 패스를 연 제출의 예약은 턴 루프가 소유하므로 드레인 패스가 다 끝난 뒤에야 풀린다. 그 사이 도착한 재시도는 방금 죽었다고 통보받은 시도에 `alreadyInFlight` 로 붙는다. 드레인 루프로 옮기면 그 예약의 소유권이 바뀌므로 별건이다 |
| `pollForward` 가 끝나지 않는 경로 | 원장 `find` 가 던지면 마감 검사 앞에서 돌아가므로, 원장이 지속적으로 불가할 때 forward 는 TTL 이 지나도 실패하지 않는다(`EVICT` 나 `closeGracefully` 로만 풀린다). 고치려면 예외 카운터나 마감 우선 검사가 필요하고, 그것은 폴링 fallback 의 실패 정책을 다시 정하는 일이다 |
| 폴링 fallback 의 간격 | 동작하지만 `TURN_RESULT` 보다 느리다. 이 타이머가 고아 메시지 재-초인종도 겸하므로 간격을 늘리면 회복 시간이 같이 는다 |
| 기다리는 노드까지 죽은 고아 메시지 | 인박스에 남지만 기다리는 forward 가 없어 세션의 다음 제출까지 대기한다. 자동으로 주우려면 `SessionInbox` 에 "대기 메시지가 있는 세션" 스캔과 백엔드별 인덱스가 필요하다 — 운영에서 관측되면 한다 |

---

## 참조 파일 지도

| 관심사 | 파일 |
|---|---|
| SPI | [`IdempotencyStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/idempotency/IdempotencyStore.java), [`IdempotencyEntry.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/idempotency/IdempotencyEntry.java), [`InMemoryIdempotencyStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/idempotency/InMemoryIdempotencyStore.java) |
| touch | [`IdempotencyTouchSlot`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/IdempotencyTouchSlot.java), [`HeldLease`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/HeldLease.java) |
| 회복 | [`HolderLossSweeper`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/HolderLossSweeper.java), [`LostTurnAnnouncer`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/LostTurnAnnouncer.java) |
| 넘겨받기·폴링 | [`DefaultSessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/DefaultSessionRouter.java) |

---

## 관련 문서

- [`routing.md`](routing.md) — 원장을 부르는 제출 시퀀스와 리스 갱신
- [`inbox.md`](inbox.md) — 인박스 밖으로 나온 메시지
- [`session-store.md`](session-store.md) — SPI 경계
- [`backends.md`](backends.md) — 백엔드별 원장 저장과 TTL
