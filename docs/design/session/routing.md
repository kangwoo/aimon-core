# 세션 라우팅 — sticky 없이 세션을 직렬화한다

**Status**: IMPLEMENTED — `aimon-session-routing`. 공개 표면은 `SessionRouter` · `SubmitRequest` · `SubmitDisposition` ·
`LiveSessionCache` · `LiveSessionOpener` · `ClusterSessionStatus` · `DeploymentMode` · `SessionRouterBuilder` · `SessionMetrics` 이고,
나머지는 `at.aimon.session.routing.internal` 에 있다. 라우터가 쓰는 SPI 는 `aimon-core` 에 있다([`session-store.md`](session-store.md) §1).
남은 것은 [마지막 절](#남은-것).

이 문서는 라우터 자체를 다룬다. 라우터가 기대는 두 계약 — 크로스 노드 우편함과 멱등 원장 — 은 각자 문서가 있다:
[`inbox.md`](inbox.md), [`idempotency.md`](idempotency.md). 세 백엔드는 [`backends.md`](backends.md).

---

## 1. 무엇을 푸는가

웹 서버(HTTP/WebSocket/SSE) 여러 대 앞에 로드밸런서가 있고, 같은 세션의 요청이 매번 다른 노드로 갈 수 있다. 이때 **sticky
routing 없이** 세션의 턴을 클러스터 전체에서 하나씩만 돌게 만드는 것이 이 모듈이다.

**목표**

- **sticky 미사용** — LB 가 어느 노드로 보내든 동작한다
- **세션 단위 직렬화** — 같은 `SessionId` 의 턴은 전 클러스터에서 한 번에 하나
- **자원 재사용** — `AgentRuntime` 재구축 비용(MCP 클라이언트, knowledge store)을 매 요청마다 물지 않는다
- **장애 복원** — 홀더 노드가 죽어도 다른 노드가 이어받고, 그 사실이 구독자에게 전달된다
- **저장소 교체 가능** — 상태는 SPI 뒤에 있고 기본 구현은 in-memory 다

**비목표**

- **세션 소유권 고정** — LB 계층에 의존하지 않는 모델만 다룬다
- **인증·권한** — 라우터는 `Principal` 을 받아 나르기만 한다. 누가 어느 세션에 접근할 수 있는지는 상위 계층이다. 세션 소유자도
  기록할 뿐 강제하지 않는다([`session-model.md`](session-model.md) §5)
- **서브에이전트의 분산 실행** — 포크는 부모 턴 안에서 같은 노드에 머문다(§7.5)

---

## 2. 제약 — 라이브 세션은 노드 로컬이다

이 설계 전체가 하나의 사실에서 나온다: **`LiveSession` 은 JVM 힙에 사는 노드 로컬 핸들이고 `SessionRecord` 만 영속이다**
([`session-model.md`](session-model.md) §1).

| | 노드 로컬 (핸들이 죽으면 사라짐) | 공유 (SPI 뒤) |
|---|---|---|
| 무엇 | `LiveSession`, 이벤트 publisher, 캐시 항목, 리스 갱신 스케줄, 턴 게이트 | 레코드, 리스, 인박스, 시그널, 멱등 원장, 세그먼트 |
| 라우터의 역할 | 만들고 닫는다 | 읽고 쓴다 — 소유하지 않는다 |

여기서 세 규칙이 나온다.

- **`AgentRuntime` 은 라우터의 것이 아니다.** agent-scoped 이고 세션들을 가로질러 산다. `LiveSessionOpener` 는 미리 등록된 런타임을
  찾아 쓸 뿐 열 때마다 만들지 않으며, `SessionRouter.close()` 도 `LiveSession.close()` 도 그것을 닫지 않는다. 닫는 것은 부트스트랩의
  일이다([`../agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md))
- **진행 중 턴의 상태는 노드와 함께 죽는다.** 그래서 턴 도중 partial state 를 커밋하지 않고, 홀더 유실을 감지해 알려 주는 경로가
  따로 있다([`idempotency.md`](idempotency.md) §6)
- **크로스 노드로 나가는 것은 전부 JSON 원시값이다.** 시그널 코덱은 페이로드를 `LinkedHashMap` 으로 풀 뿐 타입 객체를 복원하지
  않는다. 타입 객체를 실어 보내고 `instanceof` 로 받는 설계는 in-process 버스에서만 동작하고 실제 버스에서는 조용한 no-op 이
  된다(§6.3)

---

## 3. 핵심 결정

### 3.1 세션 핸들은 노드마다 중복될 수 있다

같은 `SessionId` 에 대해 두 노드가 각자 `LiveSession` 을 들고 있을 수 있다. 설계다.

- 핸들 소유권을 강제하면 그것이 곧 sticky 다. 포워딩 비용이든 hand-off 비용이든 어딘가에서 문다
- 메시지 이력의 권위는 레코드다. 핸들이 든 사본은 캐시이고, 매 턴 시작 시 `TranscriptManager.initialize` 가 레코드에서 다시
  읽는다(§12.4)
- 중복된 MCP 클라이언트 비용은 idle TTL(기본 10분)이 정리한다

대신 **턴 실행은 리스로 직렬화**한다. 어느 노드가 돌리든 한 번에 하나다.

### 3.2 리스는 세션 수명, 턴 게이트는 그 안에 따로

**리스는 세션 수명이다** — 한 턴이 따서 다음 턴들이 쓰고, 세션을 끝내는 무엇(유휴 정리, release, 셧다운, 갱신 실패)이 놓는다.
리스를 턴마다 놓으면 같은 노드의 연속 턴도 매번 다시 경합하고, hand-off 가 리스 만료 대기가 된다.

리스가 턴 경계를 넘어 살면 **"지금 이 노드에서 턴이 돌고 있는가"** 에 리스가 답하지 못한다. 그래서 그 질문 전용의 노드 로컬
게이트(`tryBeginTurn` / `endTurn`, `Set<SessionId>`)를 따로 둔다. **순서는 게이트 먼저, 리스 나중**이다. 게이트에서 지면 이 노드에
이미 턴 · 드레인 · 삭제가 돌고 있다는 뜻이므로 리스를 건드릴 이유가 없다.

이 분리 때문에 `HeldLease` 가 있다 — 리스 + 갱신 스케줄 + 멱등 touch slot 을 묶고, `returned` / `lost` 두 래치로 여러 종료
경로가 서로를 밟지 않게 한다.

### 3.3 라우터는 어느 노드에서 불러도 같게 보인다

`submit` / `interrupt` / `events` / `releaseSession` 은 호출자에게 노드를 노출하지 않는다. 캐시 hit/miss 도, 어느 노드가 턴을
돌렸는지도 투명하다. 다른 노드가 홀더면 호출자는 `FORWARDED` 를 받지만 **완결되는 future 를 함께 받는다**(§11.1).

### 3.4 `SessionId` 가 agent 를 결정한다 — 바인딩

세션 하나에 agent 하나다. `LiveSession` 의 계약과 같다.

- **첫 턴에서만 결정**한다. 이후 `submit` 의 `agentRef` 는 기록된 값과 비교만 한다. 다르면 `ConflictingAgentException`(웹 어댑터의
  409)이다
- 캐시 키는 `SessionId` **단일**이다. `(SessionId, agentRef)` 합성 키는 같은 세션에 두 핸들이 공존하는 모순을 만든다
- 바인딩은 `SessionRecord.agentRef` 에 있고 쓰는 것은 `provision` 뿐이다 — claim 과, 레코드를 프로비저닝하는 드레인 패스
  ([`session-store.md`](session-store.md) §2.1, §4). 리스가 보장하는 것은 턴 직렬화이지 레코드 write 직렬화가 아니므로 이 규칙이
  깨지면 리스 기반 분석이 통째로 무너진다
- **`SubmitRequest.build()` 는 `AgentRuntimeId.fromName` 이 거부할 `agentRef` 를 거부한다** — 공백이거나 `:` 를 포함한 이름. 라우터는
  런타임 id 를 끌어내기 전에 claim 하고, 그래서 바인딩한다. 바인딩은 first-wins 이므로 그런 이름은 어떤 턴도 열 수 없는 agent 에
  세션을 영구히 묶는다

**첫 턴 경합**은 검증을 두 번 해서 수렴시킨다. 두 노드가 동시에 첫 턴을 시도하면 둘 다 "바인딩 없음" 을 보고 통과하지만 실제
쓰기는 claim 안에서 일어나므로 진 쪽의 메시지는 인박스에 남는다. 홀더는 collect 시점에 봉투의 `agentRef` 를 다시 비교하고, 다르면
그 메시지를 처리하지 않고 `RejectedAt(CONFLICTING_AGENT)` 를 낸 뒤 버린다([`inbox.md`](inbox.md) §2).

---

## 4. 컴포넌트

```
┌─ SessionRouter (노드당 1개) ────────────────────────────────────────────────┐
│                                                                            │
│  submit / interrupt / events / status / releaseSession / deleteSession      │
│       │                                                                    │
│       ├─▶ IdempotencyStore ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐   │
│       │        (idempotency.md)                                        │   │
│       ├─▶ 턴 게이트 (activeTurns, 노드 로컬)  ── §3.2                   │   │
│       │                                                                │   │
│       ├─▶ SessionStore ─ ─ ─▶ SessionLeaseStore  (리스 선출 + 펜싱)  ─ ┤   │
│       │  (session-store.md) └ ─▶ SessionRecordStore (레코드 + 바인딩)─ ┤   │
│       │        ▲                                                       │   │
│       │        └── HeldLease ◀── LeaseRenewer (§4.3) ◀── lease sched   │   │
│       │                 └── IdempotencyTouchSlot                       │   │
│       │                                                                │   │
│       ├─▶ LiveSessionCache ─▶ LiveSessionOpener ─▶ LiveSession         │   │
│       │      (§4.2)                                    │ listener      │   │
│       │      idle TTL + LRU, pin                       ▼               │   │
│       │                                    SessionEventRelay (§6)      │   │
│       │                                      ├─▶ InProcessEventPublisher   │
│       │                                      └─▶ EVENT rail ─ ─ ─ ─ ─ ┤   │
│       │                                                                │   │
│       ├─▶ SessionInbox ─ ─ ─ ─ ─ (deliver / collect — inbox.md) ─ ─ ─ ┤   │
│       ├─▶ SessionSignalBus ─ ─ ─ (7 kinds, 2 channels — §5) ─ ─ ─ ─ ─ ┤   │
│       │        └── StatusProjection (원격 STATUS 접기)                  │   │
│       │                                                                │   │
│       └─▶ HolderLossSweeper ─▶ LostTurnAnnouncer (idempotency.md §6)    │   │
│                                                                        │   │
└────────────────────────────────────────────────────────────────────────┼───┘
                                                                         ▼
                          분산 백엔드 (Redis / MongoDB / PostgreSQL — backends.md)
```

**스케줄러는 셋이다.** 한 작업의 지연이 다른 작업을 막지 않게 하기 위해서다.

| 스케줄러 | 담당 | 크기·주기 |
|---|---|---|
| lease | `LeaseRenewer` | `max(2, availableProcessors())`, `lockExtendInterval`(기본 10초) |
| cache sweeper | `LiveSessionCache.sweep()` | 단일 스레드, 1분 |
| holder-loss sweeper | `HolderLossSweeper` | 단일 스레드, `holderLossSweepInterval`(기본 15초) |

이벤트 릴레이의 원격 fan-out 은 별도 `ExecutorService` 로 빠진다(§6.1).

### 4.1 `SessionRouter` — 공개 계약

| 메서드 | 계약 |
|---|---|
| `submit(SubmitRequest)` | `SubmitDisposition` 반환. agent 불일치면 `ConflictingAgentException` |
| `events(SessionId)` | `Flow.Publisher<AgentExecutionEvent>`. 로컬 발생분 + 원격 릴레이분 |
| `interrupt(id, reason)` | 라이브 세션 스코프 — 이 세션의 활성 턴을 멈춘다 |
| `interrupt(id, turnId, reason)` | 겨냥한 형태. 활성 턴과 다르면 no-op |
| `releaseSession(id)` | 핸들 반납. 이력은 남는다 |
| `deleteSession(id)` | 이력까지 삭제. 홀더만 할 수 있다(§9) |
| `status(id)` | `ClusterSessionStatus` — best-effort 관측, **제어 게이트 아님** |
| `fencedRecordStore(fence)` / `fencedSegmentStore(fence)` | 이 라우터의 `SessionStore` 가 주는 펜스 뷰([`session-store.md`](session-store.md) §5.2) |
| `close()` / `closeGracefully(timeout)` | 노드 로컬 자원만. `AgentRuntime` 은 건드리지 않는다 |

`interrupt` 의 두 형태는 의도적으로 다르다. `TurnId` 를 준 쪽은 "내가 보고 있던 그 턴" 을 겨냥했으므로 활성 턴이 이미 다음 턴으로
넘어갔으면 아무것도 하지 않는다 — 취소를 누른 사용자에게는 그게 맞다. id 가 없는 것은 옛 의미(세션 스코프)다.

`status()` 를 제어에 쓰지 않는다. 읽는 순간과 행동하는 순간 사이에 상태가 바뀐다. 턴을 시작할 수 있는지는 `submit` 의
`SubmitDisposition` 이 답한다.

### 4.2 `LiveSessionCache` — 두 상한, 비대칭 pin

노드당·세션당 핸들 캐시다(Caffeine). 상한이 둘이고, **pin 이 둘에 다르게 작용한다.**

| 상한 | pin 의 효과 | 이유 |
|---|---|---|
| idle TTL | 축출 자체를 **막는다** | 만료된 항목은 즉시 맵을 떠나므로, 닫기만 미루면 같은 세션에 두 번째 핸들이 열린다 |
| LRU (`maxEntries`) | 닫기를 **미룰 뿐** 축출은 일어난다 | 이 상한은 힙 경계다. 늦게 닫는 것보다 경계에 대해 거짓말하는 쪽이 나쁘다 |

LRU 쪽에 남는 이중 핸들 창은 한 단계 위에서 닫는다 — 라우터가 pin 을 푸는 같은 턴 게이트 안에서 리스를 반납한다.

- **`PinAwareExpiry` 는 세 콜백을 모두 재정의한다.** 하나라도 빠지면 pin 된 항목을 읽는 것만으로 보호가 풀린다
- **`sessionClosedListener` 는 `session.close()` 뒤에 발화한다.** Caffeine 의 `removalListener` 로는 안 된다 — pin 된 채 축출된 항목은
  세션이 닫히기 한참 전에 맵을 떠나고, 그때 리스를 반납하면 아직 도는 턴이 자기가 더 이상 쥐지 않은 이력에 쓰게 된다
- **`SessionEntry` 는 `pins` / `closeRequested` / `closed` 를 한 모니터 아래 둔다.** 나누면 세션이 아예 닫히지 않는 창이 생긴다.
  단 `unpin()` 의 `onLastPinReleased` 호출만은 모니터 **밖**에서 한다 — 안에서 부르면 락 순서 역전으로 데드락이 난다
- Caffeine 에는 백그라운드 청소부가 없으므로 라우터의 스케줄러가 `sweep()` 을 주기 호출한다

### 4.3 리스의 수명과 갱신

리스 SPI 의 의미(펜싱 토큰, `extend` 가 만료를 보지 않는다)는 [`session-store.md`](session-store.md) §3 이 정본이다. 여기서는 라우터가
리스를 어떻게 들고 있는지를 다룬다.

- **`LeaseRenewer` 는 전용 스케줄러에서 돈다.** 턴 스레드에서 돌리면 LLM 호출이나 도구 실행에 막혀 리스가 조용히 만료된다. 스윕 ·
  하트비트 · 폴링과 풀을 공유하지 않는다 — **큐에 밀린 틱은 빼앗긴 리스와 구분되지 않는다**
- **권장 간격은 `lease/3`** 이고 기본값 30초/10초가 그것이다. 두 틱을 놓칠 여유다
- **갱신은 백엔드가 아니라 `SessionStore.renew` 를 통한다.** 갱신이 실패하면 스토어가 자기 리스 기록을 버리고, 그래서 바로 다음
  펜스된 레코드 쓰기가 새 홀더의 이력 위에 얹히지 않고 거부된다. 백엔드를 직접 갱신하면 스토어는 잃은 리스를 아직 들고 있다고 믿는다
- **`onExtendFailed` 는 스케줄러 스레드에서 돌고 블록하면 안 된다.** 세션 정리, 훅 발화, 리스 반납은 전부 다른 실행기의 일이다.
  리스 하나를 잃는 것은 국소 사건이지만, 배포된 `OnSessionEnd` 코드를 기다리는 훅은 이 스레드를 공유하는 모든 세션의 리스를 같이
  잃게 만든다
- **갱신 틱마다 멱등 예약을 touch 한다** — `IdempotencyTouchSlot` 의 규칙은 [`idempotency.md`](idempotency.md) §3

**리스 숫자를 줄이지 않는다** — 30초 리스 / 10초 갱신 / 15초 홀더 손실 스윕. 리스가 세션 수명이 되면서 갱신 부하의 모집단이 "실행
중인 턴" 에서 "보유 중인 세션" 으로 바뀌었다. 1000 세션 × 30s/10s ≈ 100 extends/sec 이고, 그 부하는 유휴 스윕 · 홀더 손실 스윕 ·
상태 하트비트 · forward 폴이 함께 도는 스케줄러들 위에 얹힌다. 리스를 절반으로 줄이면 그 수가 두 배가 된다. 장애 뒤의 블랙아웃은
리스 길이가 아니라 **기다림의 상한**으로 줄인다 — 턴 스코프 실패 신호로 대기 중인 future 를 즉시 실패시키고, 대기 노드가 이미
돌리고 있는 폴이 선제적 재claim 을 촉발한다([`idempotency.md`](idempotency.md) §6).

---

## 5. 크로스 노드 시그널

`SessionSignalBus` 의 `SignalKind` 는 일곱이다.

| Kind | 뜻 | 빈도 |
|---|---|---|
| `INTERRUPT` | 홀더의 활성 턴을 멈춘다. 세션은 그대로 | 드묾 |
| `EVICT` | 세션이 **없어졌다** — 구독자 완료, 전달된 future 실패, 승인 폐기, 상태 투영 제거 | 드묾 |
| `YIELD` | 세션을 넘겨라 — 턴을 멈추고 핸들을 버리고 **리스를 반납**한다 | 드묾 |
| `MESSAGE_ENQUEUED` | 인박스에 메시지가 들어왔다 (초인종) | 턴당 ~1 |
| `TURN_RESULT` | 전달된 턴 1건의 종료 결과 | 턴당 1 |
| `EVENT` | `AgentExecutionEvent` 프레임 | 토큰 단위 |
| `STATUS` | 클러스터 관측용 상태 스냅샷 | 하트비트 |

**`INTERRUPT` / `EVICT` / `YIELD` 는 서로 다르다.** 멈추기만 하면 홀더가 리스를 내놓을 이유가 없어 요청 노드가 영원히 기다린다.
축출은 사후 종결이라, 세션이 곧 다른 데서 서빙될 상황에 쓰면 대기 중인 모든 호출자에게 "삭제됐다" 고 거짓말을 한다. 그래서 이동에는
`YIELD` 가 따로 있다. yield 에서는 큐에 있던 인박스 메시지, 전달된 future, 구독자의 스트림이 그대로 남는다. 준수는 best-effort 다 —
pin 된 세션은 닫기를 진행 중인 턴의 끝으로 미루므로 요청자가 무는 비용은 턴 하나의 나머지다.

**`YIELD` 를 모르는 노드를 위한 shim.** 모르는 `SignalKind` 이름은 `valueOf` 에서 던지고 시그널 전체가 드롭된다. 그래서 발행 측은
레거시 `INTERRUPT(SESSION_RELEASED)` 를 함께 보내고 수신 측은 둘 다 존중한다. 새 kind 는 이렇게 굴러 들어온다.

**채널은 의미가 아니라 빈도로 가른다.** `EVENT` 와 `STATUS` 는 events 채널로, 나머지는 control 채널로 간다. 구독자는 양쪽을 다
들으므로 이 분배는 수신 여부가 아니라 폭주 격리에만 영향을 준다.

**발행은 at-least-once 이고 순서 보장은 없다.** 그래서

- `StatusProjection` 은 `seq` 스탬프로 최신만 남긴다. **다른** origin 이면 무조건 수용한다 — 홀더 교체는 항상 이겨야 하고 새 홀더의
  `seq` 는 옛 홀더와 독립이다. **같은** origin 이면 `seq` 가 더 클 때만
- `MESSAGE_ENQUEUED` 는 초인종일 뿐이다. 홀더가 처리할 때 인박스가 이미 비어 있어도 정상이다(앞선 collect 가 가져갔다)

백엔드마다 전달 보장이 다르다 — Redis pub/sub 은 연결이 끊긴 동안 at-most-once, Postgres 는 행으로 남아 재연결이 백로그를 되읽고,
Mongo 는 resume token 이 oplog 구간 안에서 at-least-once 를 준다([`backends.md`](backends.md) §8).

---

## 6. 이벤트 릴레이 — `SessionEventRelay`

세션의 `submitAsync(input, listener)` 리스너로 주입되어 프레임을 두 곳으로 가른다 — 로컬 `InProcessEventPublisher`, 그리고 `EVENT`
rail(이 노드에서 다른 노드로 `EVENT` 시그널을 내보내는 경로).

**턴당 하나다.** 자기가 서비스하는 턴의 `TurnId` 를 생성자로 받아 모든 페이로드에 찍는다. 두 턴이 릴레이를 공유하면 두 번째 턴의
프레임이 첫 번째 턴의 것으로 표시된다.

### 6.1 프로듀서는 블록하지 않는다

`accept()` 는 턴 실행 스레드에서 돈다.

- 로컬 fan-out 은 `SubmissionPublisher.offer(item, 0L, NANOS, onDrop)` — demand 가 없는 구독자가 있으면 가장 오래된 이벤트를 버리지
  프로듀서를 세우지 않는다
- 원격 fan-out 은 유한 `ArrayBlockingQueue` 로 분리하고 라우터 소유 디스패처(`web-session-relay`, 2~4 스레드)가 뺀다

**버스가 빠르다고 가정하지 않는다.** publish 는 pub/sub 쓰기일 수도 있고 문서 insert 일 수도 있으며, 모델은 데이터베이스가 insert 를
확인해 주는 것보다 훨씬 빨리 델타를 낸다.

- **배치로 뺀다.** 쌓인 프레임을 최대 256개(`MAX_BATCH`)씩 `SessionSignalBus.publishAll` 한 번으로 보낸다. 왕복 수가 백로그 건수가 아니라
  건수 ÷ 배치에 비례한다. `publishAll` 의 기본 구현은 한 건씩 publish 하고, publish 가 왕복인 백엔드가 재정의한다
- **디스패처 작업 하나에 배치 하나, 릴레이 하나에 작업 하나.** 릴레이는 drain 을 동시에 둘 띄우지 않는다(`wip` 카운터). 그래서 두
  배치가 순서를 바꿔 나갈 수 없고 한 릴레이가 디스패처 스레드를 둘 차지하지도 않는다. 배치를 하나 보낸 뒤에는 디스패처 큐의 맨 뒤로
  간다 — 버스가 모델보다 느리면 버퍼는 스트림이 끝날 때까지 비지 않으므로, 빌 때까지 도는 루프는 그 스레드를 다른 세션에게서 턴
  내내 빼앗는다
- **`close()` 에는 상한이 있다.** `close()` 는 턴 스레드에서, 실행이 끝난 뒤 결과를 알리기 **전에** 돈다. 그러니 `close()` 가 기다리는
  만큼 호출자가 기다린다. `close()` 는 `relayCloseDrainTimeout`(기본 2초)까지만 기다리고, 시간이 다하면 버퍼에 남은 델타(등급 0·1)를
  버리고 세며 구조 프레임은 디스패처가 마저 발행하게 남긴다. 그래서 종결 프레임이 `TURN_RESULT` 뒤에 도착할 수 있지만, 턴의 결과가
  best-effort rail 뒤에 붙잡히지는 않는다. 디스패처가 이미 내려간 뒤(라우터 종료)에는 호출 스레드에서 상한 없이 뺀다
- **한 턴의 프레임이 다음 턴의 프레임 사이에 끼지 않는다.** 마감에서 프레임을 남긴 릴레이는 같은 세션의 다음 턴 릴레이에
  `after(predecessor)` 로 넘겨지고, 후임은 전임이 다 내보낼 때까지 발행하지 않는다. 수신 측은 `EVENT` 를 턴이 아니라 세션 단위로 읽으므로
  턴 N+1 한가운데에서 턴 N 의 종결 프레임을 보면 엉뚱한 스트림을 끝낸다. 버스가 건강하면 이 장치는 아무 일도 하지 않는다

### 6.2 오버플로 정책 — 세 등급

| 등급 | 프레임 | 이유 |
|---|---|---|
| 0 (가장 먼저 희생) | `AssistantReasoningDelta` | 양이 텍스트보다도 많고, 하류에서 필요로 하는 것이 없다 — 전사도, 요약도, 결과 필드도 |
| 1 | `AssistantTextDelta` | 양이 많고 개별 가치가 낮지만, 그것이 쌓은 assistant 메시지를 `AssistantMessageReceived` 가 iteration 끝에 다시 요약한다 |
| 2 (희생하지 않는다) | 그 외 전부 | 종결 프레임(`ExecutionCompleted` / `ExecutionError` / `InterruptedAt` / `RejectedAt`)은 원격 구독자에게 턴이 끝났음을 알리는 유일한 것이고, 그것을 잃은 구독자는 영영 완료되지 않는다 |

버퍼가 넘치면 **들어오는 프레임보다 등급이 높지 않은** 버퍼 프레임 중 가장 오래된 것을 등급 오름차순으로 찾아 버린다. 등급 비교가
있어야 하는 이유 — "둘 다 버릴 수 있다" 로 넓히면 두 등급이 같아져 숙고 폭주가 답 텍스트를 밀어낸다. 버릴 것이 하나도 없고 들어오는
것도 구조 프레임일 때만 구조 프레임을 희생한다. 드롭은 세고(`getDroppedEventCount()`) `close()` 에서 보고한다 — 빈틈이 조용하지 않다.

### 6.3 페이로드 코덱 셋 — 평평한 원시값만

`AgentExecutionEventPayload` / `TurnResultPayload` / `StatusSnapshotPayload` 는 §2 마지막 규칙 때문에 있다.

| 코덱 | 잃는 것 |
|---|---|
| `AgentExecutionEventPayload` | `ExecutionError.getCause()` 는 메시지만(원 타입·스택트레이스 소실), `ToolUseStarted.getInputSummary()` 의 비원시 값, Duration 은 ms 단위 |
| `TurnResultPayload` | artifacts 전부(§11.2) |
| `StatusSnapshotPayload` | `LiveSessionStatus.getOptions()` — 관측에 불필요하고 페이로드만 키운다 |

숫자는 전부 `Number` 로 읽는다 — JSON 왕복이 int 범위의 `long` 을 `Integer` 로 좁히고 정수를 `Double` 로 넓힌다(`PayloadValues`).
모르는 `type` 은 `Optional.empty()` 로 읽어, 롤링 배포에서 옛 노드가 새 노드의 이벤트를 무시한다.

---

## 7. 제출과 드레인

### 7.1 제출 시퀀스 (F0–F7)

```
[제출 노드]
 F0  시그널 구독 먼저 — I/O 보다, disposition 이 정해지기보다 앞
 F1  바인딩 사전 검증 → 불일치면 ConflictingAgentException (리스도 멱등 원장도 건드리지 않는다)
 F2  IdempotencyStore.putIfAbsent → 매트릭스 (idempotency.md §2)
       턴 게이트 시도  ─┐  게이트나 리스에서 지면:
       SessionStore.claim ┘    inbox.deliver(InboundMessage) + MESSAGE_ENQUEUED
                              → SubmitDisposition.forwarded(turnId, future)

[홀더 노드]
 F3  MESSAGE_ENQUEUED 수신 → 돌고 있지 않던 세션이면 드레인 시작
 F4  드레인: collect(LATER) 로 전 tier 수령 → priority-then-FIFO → 하나씩
       각 메시지마다 바인딩 재검증(§3.4). 다르면 RejectedAt + drop
 F5  session.submitAsync(input, relay) — relay 가 로컬 publisher 와 EVENT rail 로 fan-out
 F6  턴 종료: markDone 먼저, 그다음 TURN_RESULT
 F7  제출 노드가 TURN_RESULT 로 future 완결. 놓치면 IdempotencyStore 폴링 fallback 이 같은 일을 한다
```

- **F0 이 맨 앞이다.** 구독을 disposition 이 정해진 뒤로 미루면 그 사이 홀더가 낸 `TURN_RESULT` 를 놓치고, F7 이 느린 폴링에만 기댄다
- **F1 이 F2 보다 앞이다.** 명백히 거절될 요청이 예약을 남기지 않게 한다
- **리스 시도가 세션 열기보다 앞이다.** 리스 실패는 다른 노드가 활발히 처리 중이라는 뜻이므로 이 노드가 핸들을 열어 유휴 TTL 까지 띄워
  둘 이유가 없다
- **F6 은 `markDone` 이 먼저다.** `TURN_RESULT` 를 놓친 노드에게 권위는 저장소다. 순서가 거꾸로면 놓친 노드가 볼 것이 없다
- **F4 는 언제나 collect 한다.** 직전 홀더가 collect 와 리스 반납 사이에 다른 노드가 넣은 메시지(orphan)를 다음 홀더가 자기 턴 직전에 한
  번 더 수령한다. 이 규칙 하나로 release-deliver 경합에 의한 영구 누락이 사라진다. tier 를 `LATER` 까지 전부 가져오는 것은 NOW orphan 이
  직전 턴에 주입되지 못하고 남았을 수 있기 때문이다
- 인박스 봉투가 무엇을 나르고 무엇을 나르지 않는지, collect 의 원자성과 못 읽는 항목의 처분은 [`inbox.md`](inbox.md) 가 정본이다

### 7.2 홀더 쪽 드레인

```
턴 완료 (리스 보유 중, 게이트 보유 중)
  ├─ extra = collect(id, LATER)
  ├─ pendingQueue 도 extra 도 비었으면 → 루프 종료 (리스는 반납하지 않는다 — 세션 수명이다)
  └─ 아니면 priority-then-FIFO 로 병합하고 다음 턴
```

리스는 여기서 풀리지 않는다. §8.2 의 종료 경로 중 하나가 놓는다.

### 7.3 hand-off — `NOT_HOLDER` 대신 `YIELD`

리스 홀더는 **노드 id** 로 식별된다. 리스가 세션 수명이므로 턴·스레드를 붙일 이유가 없다. 멱등 예약의 홀더는 이와 다른 문자열이다
([`idempotency.md`](idempotency.md) §4).

홀더가 아닌 노드에 들어온 턴을 `NOT_HOLDER` 로 실패시키지 않는다. 요청 노드가 `YIELD` 를 보내고 현 홀더가 턴을 멈추고 리스를 반납한다.
호출자가 `NOT_HOLDER` 로 할 수 있는 일이 "리스 TTL 만큼 기다렸다 재시도" 뿐이라면 그 기다림은 프로토콜이 대신 하는 편이 낫다.

재진입은 허용하지 않는다. 턴 안에서 `submit` 을 다시 부르는 패턴은 라우터가 아니라 서브에이전트 계층의 책임이다.

### 7.4 공정성 — 보장하지 않는다

1. **선출 unfairness** — 동시 도착 시 누가 이길지 결정적이지 않다. 인박스 안에서 priority-then-FIFO 가 살아 있으므로 메시지 처리 순서는
   보존된다
2. **affinity starvation** — 홀더가 같은 리스 안에서 자기 노드의 후속 입력을 계속 처리하므로, 다른 노드에서 deliver 된 메시지는 홀더가
   collect 할 때까지 기다린다

둘 다 받아들인다. 크로스 노드 EVENT 전달 지연은 단일 Redis 기준 p50/p95/p99 가 1.73 / 2.97 / 3.74 ms(paced), 0.15 / 0.22 / 0.33 ms
(burst, ~6.3K ev/s 지속)로 측정되었고, 전형적인 LLM 턴 시간(수 초~수 분)에 비해 무시할 수준이다. starvation 의 경계를 정하는 것은
broadcast 비용이 아니라 턴 길이다. 한쪽 노드의 long-tail wait 이 관찰되면 FCFS 인박스 기반 선출을 검토한다.

### 7.5 서브에이전트

포크는 부모 턴 안에서 같은 노드에 머문다. 부모가 리스를 들고 있는 동안 포크도 그 안에서 도는 하나의 턴이므로 nested 리스 문제가 없다.

---

## 8. 라이프사이클

### 8.1 열기와 활동

| 단계 | 시점 | 동작 |
|---|---|---|
| 라우터 | 부팅 | 노드당 1회. 애플리케이션 스코프 자원 주입 |
| 세션 | 첫 `submit` | 레코드 lazy 생성(claim) |
| 핸들 | 그 노드에 첫 요청 도착 | `LiveSessionCache.ensureOpen` → `LiveSessionOpener` → 등록된 `AgentRuntime` 조회 |

warm-up 은 하지 않는다. 어느 노드로 갈지 모르고, 안 쓰인 핸들은 곧 유휴 정리된다. 같은 노드로 후속 메시지가 오면 캐시 hit 과
`lastActivityAt` 갱신, 다른 노드로 오면 그 노드에서 lazy open 이다.

### 8.2 종료 — 넷

**A. 명시적 release — `releaseSession(id)`.** 이력을 지우지 않는 **핸들 반납**이다. 같은 `SessionId` 로 다시 보내면 이력이 살아난다.
진행 중 턴이 있으면 순서가 보장된다 — `interrupt(SESSION_RELEASED)` → 턴 future 대기(`releaseInterruptTimeout`, 기본 5초) →
`session.close()` → 캐시 evict → 리스 반납 → 크로스 노드 `EVICT` → publisher `complete()`. 대기가 타임아웃하면 강제 close + 즉시
onComplete(일부 in-flight 이벤트 손실, WARN). 인박스를 purge 했으므로 이 노드가 들고 있던 초인종 표시도 버린다(`forgetDoorbell`) —
남겨 두면 다음 리스 반납 때 빈 드레인 패스를 사고, 같은 `SessionId` 를 재사용하는 나중 세션이 자기 것이 아닌 통지를 물려받는다.
delete 와 피어의 `EVICT` 수신도 같은 일을 한다.

**B. 유휴 타임아웃.** 노드별 sweeper 가 `lastActivityAt + idleTtl < now` 항목을 정리한다. 크로스 노드 broadcast 는 하지 않는다 — 다른
노드는 자기 기준으로 한다. 기본 10분이다. 너무 짧으면 다음 메시지마다 MCP 재연결 비용을 문다.

**C. 셧다운.**

1. 새 제출 거부(`submit` 은 `IllegalStateException`)
2. 진행 중 턴 완료 대기(`closeGracefully(timeout)`)
3. 타임아웃 후 남은 턴에 `interrupt(SYSTEM_SHUTDOWN)`
4. 모든 핸들 close
5. 이 노드가 쥔 리스를 명시적으로 반납 — 다른 노드가 만료를 기다리지 않게. 실패는 삼킨다(어차피 TTL 로 풀린다)
6. publisher `onComplete`, 시그널 구독 해제, 스케줄러 종료
7. 애플리케이션 스코프 자원은 건드리지 않는다

**D. 홀더 유실(crash / OOM).** 리스는 TTL 로 풀리고 다른 노드가 이어받는다. 문제는 구독자와 대기 중인 future 다 — `EVENT` 가 그냥
끊기면 원격 구독자는 무한 대기한다. `HolderLossSweeper` 가 멱등 원장의 오래된 `IN_FLIGHT` 예약을 찾아 그 **턴 하나**에 대해
`InterruptedAt(HOLDER_LOST)` 를 내고 대기 중인 future 를 실패시킨다. 세션은 헐지 않는다 — 홀더 유실은 세션 유실이 아니다. 감지 지연은
기본값 기준 최대 ~45초다. 정본은 [`idempotency.md`](idempotency.md) §6 이다.

### 8.3 끝나지 않는 턴

`extend` 가 실패하면(펜싱 토큰 불일치 = 리스가 이미 넘어감) `onExtendFailed` 가 정확히 한 번 발화하고 라우터가 턴을
`interrupt(LEASE_LOST)` 로 끊는다. 이후 틱은 `lostLease` 래치로 no-op 이다. 이것은 `ExecutionBudget` 의 시간 상한과 별개의 그물이다.
예산이 먼저고 리스는 분산 쪽 그물이다.

---

## 9. 삭제와 삭제 리스너

`deleteSession(id)` 는 이력을 지우는 유일한 API 이고 **홀더만** 한다. 라우터를 우회한 `SessionRecordStore.delete(id)` 직접 호출은 진행
중 턴과 경합하므로 하지 않는다.

```
deleteSession(id)
  1. 리스를 먼저 잡는다 — 레코드가 없던 id 면 이 claim 이 프로비저닝한다
  2. 경합하면 YIELD (+ 레거시 INTERRUPT(SESSION_RELEASED)) 로 현 홀더에게 양보를 요구, releaseInterruptTimeout 까지 bounded retry
  3. 그 안에 못 잡으면 IllegalStateException — 호출자가 재시도한다
  4. 리스너가 있으면 소유자·바인딩을 읽어 둔다
  5. records().delete(id) — HOLDER_ONLY 펜스 뷰
  6. 세그먼트 deleteAll, 종료 interrupt, 구독 종료, EVICT publish
  7. 레코드가 지워졌으면 — 6 이 실패해도 — 리스를 돌려주기 전에 리스너를 한 번 호출
  8. 리스 반납 (리스너가 Error 를 던져도)
```

**노드 로컬 정리.** 이 경로는 바인딩 캐시, 세션 승인, 상태 투영을 정리한다. 승인은 노드 로컬이라 다른 노드의 `deleteSession` 이 여기
캐시된 사본에 닿지 못하므로, `EVICT` 수신 핸들러가 같은 정리를 자기 노드에서 반복한다.

### 9.1 `SessionLifecycleListener`

확장(예: aimon-sandbox)이 세션별 공유 상태를 정리하도록, 레코드가 사라진 뒤 리스너를 부른다.

```java
package at.aimon.core.agent.session;

public interface SessionLifecycleListener {
    default void onDeleted(SessionDeletedEvent event) { }
    static SessionLifecycleListener whenDeleted(Consumer<SessionDeletedEvent> onDeleted) { … }
}

public final class SessionDeletedEvent {          // 불변 + 빌더
    SessionId getSessionId();
    Optional<Principal> getOwner();                // 삭제 직전에 기록된 값
    Optional<String> getAgentRef();
    Instant getDeletedAt();
}
```

- **core 에 있다.** 외부 확장은 core 의 공개 SPI 에만 대고 컴파일한다. 레코드 삭제를 알리므로 이름은 `Session*` 이다
- **유일한 메서드가 `default` no-op 이다.** `onCreated` 같은 콜백을 나중에 더해도 구현이 깨지지 않게. 그래서 함수형 인터페이스가 아니고,
  람다는 `whenDeleted(Consumer)` 로 감싼다 — 이름이 `onDeleted` 가 아닌 것은 오버로드가 `onDeleted(null)` 을 모호하게 만들기 때문이다
- **등록** — `SessionRouterBuilder.lifecycleListener(...)`(반복 가능) ← `SessionSpec.lifecycleListener(...)` ← Spring 의 모든
  `SessionLifecycleListener` 빈(`ApplicationBeans.resolveAll` — `@Order` 순서, 그리고 리스너를 부르는 스택보다 리스너가 나중에 파괴되는
  순서). 리스너는 `@ExternallyManaged` 이고 스택이 닫지 않는다

**전달 계약**

- **삭제한 노드에서, 레코드를 지운 호출마다 한 번.** 호출 여부는 `recordDeleted` 가 정한다 — 뒤따르는 세그먼트 삭제 · 종료 interrupt ·
  `EVICT` publish(버스 장애 때 던진다)가 실패해도 리스너는 불리고, 원래 예외는 그대로 호출자에게 간다. 레코드를 지우기 전에 실패하면
  아무것도 부르지 않는다. 존재한 적 없는 id 의 삭제는 claim 이 프로비저닝한 뒤 지우므로 빈 소유자로 불린다 — 리스너는 멱등해야 한다
- **리스 안에서.** 리스는 리스너가 도는 동안에도 갱신되므로, 갱신이 성공하는 동안에는 같은 `SessionId` 가 정리 도중 다시 프로비저닝되는
  경쟁이 없다 — "세션 X 의 옵션" 을 지우는 리스너가 1ms 뒤 새로 생긴 세션 X 의 옵션을 지우지 않는다. 느린 리스너가 도는 사이 리스 저장소가
  갱신을 거절하면 다른 노드가 먼저 그 id 를 claim 할 수 있다. 그 경쟁을 견딜 수 없는 리스너는 상태를 지우기 전에
  `SessionRecordStore.exists(id)` 를 보거나 재조정 경로에 맡긴다. 리스너가 `Error` 를 던져도 리스는 돌려준다 — 그렇지 않으면 갱신기가
  삭제된 id 의 리스를 무한정 갱신한다
- **소유자를 읽지 못해도 삭제한다.** 삭제 전 읽기가 실패하면 WARN 을 남기고 빈 소유자·바인딩으로 리스너를 부른다. 읽기 실패가 레코드를
  지울 수 없게 만들면 안 된다. 읽기는 리스너가 있을 때만 한다
- **at-most-once.** 레코드 삭제와 리스너 사이에 노드가 죽으면 이벤트는 사라진다. 확장은 재조정 경로를 둔다(예: `exists(id)` 가 false 인
  세션별 상태를 주기적으로 버린다)
- **격리.** 리스너의 `RuntimeException` 은 세션 id 와 리스너 클래스를 담은 WARN 으로 남기고 다음 리스너로 넘어가며 삭제를 실패시키지
  않는다 — 레코드는 이미 되돌릴 수 없이 사라졌고, 던지면 빈 레코드를 프로비저닝하고 지우는 재시도를 부른다. `Error` 는 잡지 않는다 —
  남은 리스너는 건너뛰고 호출자에게 전파된다. 정리 단계의 예외와 리스너의 예외가 겹치면 정리 단계의 것이 호출자에게 간다
- **동기다.** 리스너는 턴 게이트와 리스를 쥔 채 돌아 그 세션 id 에 대한 제출을 막는다. 짧게 끝내고 무거운 일은 실행기로 넘긴다
- **라우터만 부른다.** 날것의 `SessionRecordStore.delete` · `clear()` 는 부르지 않는다. 다른 노드에도 알리지 않는다 — 공유 상태는 정리가 한
  번이면 된다
- **리스너 안에서 지우는 중인 세션에 대해 `SessionRouter` 를 다시 부르지 않는다.** 삭제 스레드가 그 세션의 턴 게이트를 쥐고 있다 — 제출은
  인박스에 떨어진 채 초인종이 다시 울리지 않고, 중첩 삭제는 쥔 게이트에 재진입한다

---

## 10. 인터럽트 전파

사용자가 노드 B 에서 "stop" 을 눌렀는데 턴은 노드 A 에서 돈다.

```
[Node B] router.interrupt(id, USER)
   └─ SessionSignalBus.publish(INTERRUPT, id, reason, originNode=B)

[모든 구독 노드]
   └─ 이 노드에 활성 턴 또는 캐시 항목이 있으면 → session.interrupt(reason)   (없으면 무시)
```

멈출 세션을 고를 때 **진행 중 턴의 세션이 캐시 항목보다 우선**이다. mid-turn 축출은 항목을 맵에서 지우지만 턴은 자기가 pin 한 세션 위에서
계속 돈다. 경합은 안전하게 수렴한다 — 신호 도착 직전에 턴이 끝났으면 `LiveSession.interrupt()` 의 계약(활성 턴 없음 → 조용한 no-op)이
받는다. 겨냥한 형태는 활성 턴과 다르면 no-op 이고, `FORWARDED` 턴은 이 노드에서 돌지 않으므로 여기서 no-op 이다.

---

## 11. 비동기 결과

### 11.1 `SubmitDisposition`

```java
enum Kind { EXECUTED_LOCALLY, FORWARDED }
```

두 종류 모두 `TurnId` 와 `CompletionStage<AgentExecutionResult>` 를 필수로 나른다. `FORWARDED` 만 `Optional<InboundMessageId>` 를 더 갖는다.
전달된 future 는 F7 의 두 경로 중 하나로 완결되고, 다음 경우 예외로 완결된다 — agent 충돌, 세션 축출·삭제, 홀더 유실, 인박스가 그 메시지를
읽지 못함([`inbox.md`](inbox.md) §4), forward TTL 경과(기본 5분). 그중 아무 노드도 돌리지 않았음이 증명되는 것은
`TurnNotRunException` 이다(§11.3).

이 타입은 `at.aimon.core.agent.session.SubmitOutcome` 과 **의도적으로 다르다.** 후자는 `EXECUTED`/`QUEUED` 로 한 라이브 세션 안에서의
즉시성을 말하고, 이쪽은 클러스터에서 어느 노드가 돌리는지를 말한다. 축이 다르므로 한 타입으로 겸하면 둘 다 흐려진다.

### 11.2 전달된 턴은 artifacts 를 나르지 않는다

`AgentExecutionResult.getArtifacts()` 에는 와이어 인코딩이 없으므로 `TURN_RESULT` 로 돌아온 결과는 artifacts 가 비어 있다. **계약이지
우회할 버그가 아니다** — 파일 아티팩트는 만든 노드의 가상 파일시스템에 있고, 그 바이트를 시그널 페이로드에 실어 나르는 것은 이 rail 의
목적이 아니다. 필요하면 공유 파일시스템(`aimon-filesystem-{gridfs,s3}`)을 쓴다([`../agent-execution/artifact.md`](../agent-execution/artifact.md)).

실패 코드는 **전방 호환**이다. 모르는 `Failure.Code` 이름은 메시지를 보존한 채 `FAILED` 로 디코드한다. 롤링 배포에서 덜 정확한 이유로
열화될 뿐 시그널이 통째로 드롭되지 않는다. `SignalKind.valueOf` 가 던지는 것과 정반대의 선택이고, 이유는 여기서 잃는 것이 호출자의
future 이기 때문이다.

---

### 11.3 돌지 않았다는 증명 — `TurnNotRunException`

전달된 턴이 실패했을 때 호출자가 가장 알고 싶은 것은 "다시 보내도 되는가" 다. 대부분의 실패는 그것을 말하지 못한다 — 데드라인,
예약 소실, 홀더 유실은 입력이 어딘가에서 돌았거나 도는 중일 가능성을 남긴다. 그래서 실패는 둘로 나뉜다.

| 예외 | 언제 | 근거 |
|---|---|---|
| `TurnNotRunException` (`at.aimon.session.routing.exception`, `IllegalStateException` 의 하위 타입) | `PURGED` — 릴리스·삭제가 인박스에서 그 메시지를 지움 · `NOT_HOLDER` — 꺼낸 노드가 셧다운 유예를 넘겨 시작하지 못함 · `UNREADABLE` — 꺼낸 노드가 디코드하지 못함 · `REJECTED` — 홀더가 agent 충돌로 거절 | 메시지를 손에 쥔 노드가 직접 말한다 |
| `IllegalStateException` | 그 밖의 전부 — `FAILED`(시도했고 던짐), `HOLDER_LOST`, forward TTL, 예약 소실, 이미 수거된 메시지의 축출, 셧다운 | 돌았을 수 있다 |

**퍼지는 지운 것을 안다.** 릴리스·삭제는 `SessionInbox.purge` 대신 `collect(id, LATER)` 를 빌 때까지(최대 64 배치) 부르고, 남은 것만
`purge` 한다. `collect` 는 원자적으로 지우면서 지운 것을 돌려주므로, 그 결과가 곧 "아무도 돌리지 않을 메시지" 의 목록이다. SPI 는 바뀌지
않는다. 같은 순간 홀더가 수거한 메시지는 결과에 없고, 돌았을 수 있으므로 증명 없이 실패한다.

**두 길로 알린다.** 지운 메시지마다 퍼지한 노드가 `PURGED` 를 `TURN_RESULT` 로 알리고(멱등 예약을 먼저 지워 같은 키로 곧바로 재시도할 수
있게 한다), `EVICT` 페이로드의 `purgedTurns`(공백으로 구분한 턴 id, 최대 256개)에도 싣는다. 받는 노드는 그 목록에 있는 자기 forward 를
`TurnNotRunException` 으로, 나머지를 일반 실패로 끝낸다. 둘 중 무엇이 먼저 도착해도 같은 답이 나도록 두 번 싣는다 — 시그널 순서에 기대지
않는다.

**롤링 배포.** 이전 빌드는 모르는 코드 `PURGED` 를 `FAILED` 로 읽고 `purgedTurns` 를 무시한다. 그 노드의 호출자는 증명만 잃고 답은 받는다
— 잘못된 쪽(돌지 않았는데 돌았을 수 있다고 함)으로만 열화하고, 반대 방향(돌았는데 돌지 않았다고 함)은 일어나지 않는다.

쓰는 곳: 쉬는 세션 깨우기는 이 예외를 받으면 알림을 큐에 되돌린다([`idle-wake.md`](idle-wake.md) §5).

---

## 12. 장애

### 12.1 분산 백엔드 일시 장애

| 영향 | 응답 |
|---|---|
| `claim` 실패 | `submit` 이 실패한다. 웹 어댑터가 503 으로, 재시도 권장 |
| `renew` 실패 | 진행 중 턴은 `interrupt(LEASE_LOST)`. partial state 는 커밋되지 않는다 |
| pub/sub 끊김 | 인터럽트 전파 불가. 재연결 후 재구독하며, 백엔드에 따라 끊긴 동안의 신호는 손실된다 |
| 백엔드 영구 손실 | 모든 in-flight 턴 중단. 레코드 저장소가 별도 백엔드면 이력은 보존 |

리스·시그널 백엔드는 HA 로 구성한다.

### 12.2 노드 다운

리스는 TTL 로 풀리고, 캐시는 사라지고, 진행 중 턴은 손실된다. 인박스가 분산 백엔드면 deliver 된 메시지는 살아남아 다른 노드가 collect
한다. 구독자는 `InterruptedAt(HOLDER_LOST)` 를 받는다(§8.2 D).

### 12.3 Split-brain

파티션으로 두 노드가 동시에 리스를 들었다고 믿으면, 쓰기 펜스가 리스를 잃은 쪽의 다음 쓰기를 거절한다
([`session-store.md`](session-store.md) §5). 리스를 짧게 잡고 갱신 실패 시 즉시 중단하는 정책이 창을 좁힌다. 펜스는 완전한 해법이 아니다.

`DeploymentMode.DISTRIBUTED` 는 SPI 가 하나라도 주입되지 않으면 `build()` 에서 실패한다. in-memory SPI 로 멀티 노드를 돌리면 조용한
split-brain 이 되기 때문이다.

### 12.4 Stale 캐시

두 노드가 같은 세션을 캐시하고 A 에서 턴이 돌면 B 의 사본은 낡는다. 그래도 문제가 없다 — `OrcaAgentExecutor` 가 매 턴 시작 시
`TranscriptManager.initialize(sessionId, systemPrompt)` 를 부르고, 그 계약이 "세션이 있으면 읽고 없으면 새로 만든다" 이다. 다시 읽는
책임자는 핸들이 아니라 executor 이고, 핸들의 사본은 권위가 아니다.

---

## 13. 공개 API

### 13.1 `SubmitRequest`

불변 클래스 + 빌더.

| 필드 | 기본값 | 비고 |
|---|---|---|
| `sessionId` | (필수) | |
| `agentRef` | (필수) | §3.4 — 첫 턴 이후에는 비교만. `AgentRuntimeId.fromName` 이 거부할 이름은 `build()` 가 거부 |
| `contextDiscriminator` | 없음 | 공백과 `':'` 를 거부한다 — `AgentRuntimeId` 형식이 `:` 로 갈린다 |
| `userInput` | (필수) | `UserInput` — 텍스트·이미지·문서·조합. 빌더는 `String` 오버로드를 유지한다(=`TextInput.of`) |
| `options` | `LiveSessionOptions.defaults()` | 열 때만 쓰인다 |
| `submitOptions` | `SubmitOptions.empty()` | 턴 단위 메타데이터 |
| `openAttributes` | `OpenAttributes.empty()` | 캐시 미스 시에만 opener 로 간다 |
| `idempotencyKey` | 없음 | [`idempotency.md`](idempotency.md) |
| `priority` | `NEXT` | [`inbox.md`](inbox.md) §5 |
| `initiator` | 없음 | `Principal` — 제출한 주체 |
| `owner` | 없음 | 세션 소유자. 레코드를 프로비저닝할 때만 쓰이고 이미 소유자가 있는 세션에서는 무시된다([`session-model.md`](session-model.md) §5) |

`options` / `openAttributes` 는 "열 때만" 이다. 캐시 hit 이면 무시되므로 턴마다 달라져야 하는 값은 `submitOptions` 에 넣는다.

### 13.2 빌더와 `DeploymentMode`

`sessionFactory(...)` 와 `sessionOpener(...)` 중 **정확히 하나**를 설정한다. 전자는 무상태 `LiveSessionFactory` 를 위한 람다 친화 경로이고
(`OpenAttributes` 를 무시한다), 후자는 호출자 도메인 속성(tenant id 등)을 세션 열기까지 흘린다.

빌더는 `SessionRecordStore` 와 `SessionLeaseStore` 를 **따로** 받아 `SessionStore` 를 직접 조립한다 — "라우터당 스토어 하나" 를 사람이
기억할 것이 아니라 구조로 만들기 위해서다([`session-store.md`](session-store.md) §4.1).

| 모드 | SPI | nodeId |
|---|---|---|
| `SINGLE_NODE` | 주입하지 않은 SPI 는 in-memory 로 채운다 | 불필요 |
| `DISTRIBUTED` | 전부 주입 필수, 하나라도 빠지면 `build()` 실패 | 필수 |

| 튜너블 | 기본 | 비고 |
|---|---|---|
| `idleTtl` | 10분 | §8.2 B |
| `maxCachedSessions` | 1000 | 힙 경계(§4.2) |
| `lockLease` / `lockExtendInterval` | 30초 / 10초 | 두 틱의 여유(§4.3) |
| `statusHeartbeatInterval` | 10초 | 위와 수치만 같고 무관 |
| `holderLossSweepInterval` | 15초 | [`idempotency.md`](idempotency.md) §6 |
| `idempotencyPrimaryTtl` / `secondaryTtl` | 24시간 / 30초 | [`idempotency.md`](idempotency.md) §3 |
| `idempotencyForwardTtl` | 5분 | 전달된 future 의 마감(§11.1) |
| `releaseInterruptTimeout` | 5초 | §8.2 A, §9 |
| `relayCloseDrainTimeout` | 2초 | 턴 종료가 `EVENT` rail 을 기다리는 상한(§6.1) |

내부 컴포넌트(`LeaseRenewer`, `InProcessEventPublisher`, `LiveSessionCache`)는 라우터가 소유하며 외부에서 주입하지 않는다.

---

## 14. 계측 — `SessionMetrics`

`SessionMetrics` 는 프레임워크 중립이고 모든 메서드가 no-op 기본 구현을 갖는다. 필요한 것만 구현하면 되고, 계측을 요구하지 않은
애플리케이션에는 `NOOP` 이 들어간다. 백엔드 모듈은 이 타입을 볼 수 없다 — 그것이 라우팅에 있고 백엔드 main 은 core 만 본다. 그래서
세션 메트릭을 셀 수 있는 자리는 라우터다.

| 훅 | 답하는 질문 |
|---|---|
| `onLockAcquireSucceeded` / `onLockAcquireRejected` | 선출이 느려지고 있나 |
| `onCacheHit` / `onCacheMiss` | idle TTL 이 너무 짧은가 |
| `onCacheEviction` | `maxCachedSessions` 가 부족한가 |
| `onLeaseExtendSucceeded` / `onLeaseExtendFailed` | 노드가 리스를 잃고 있나 |
| `onSubmitOutcome` (`EXECUTED_LOCALLY` / `FORWARDED`) | 노드들이 인박스에 쌓이고 있나 |
| `onHolderLossRecovered` | 프로덕션에서 회복 경로가 실제로 발동했나 |
| `onReservationTakeOverRefused` | 이 클러스터가 같은 요청을 두 번 실행했나 — **정상 상태에서 0** 이어야 한다([`idempotency.md`](idempotency.md) §5) |
| `onForwardDoorbellRerung` | 아무도 수집하지 않은 메시지를 기다리는 호출자가 있나 — 재시도 횟수이지 회복 횟수가 아니다([`idempotency.md`](idempotency.md) §6) |

호출 규약은 셋이다 — 훅은 이벤트를 낸 스레드에서 돌고(턴 실행기, 스케줄러, 시그널 디스패처), 블록하면 안 되며, 던지면 안 된다.
라우터가 방어적으로 감싸지만(`safeMetric`), 계측 장애가 세션 수명을 깨는 것은 어느 쪽에도 이롭지 않다.

---

## 15. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 세션 배치 | sticky 없음, 리스로 직렬화 | **세션 소유권 고정(sticky)** — LB 계층에 배포 제약을 떠넘기고, 노드가 죽는 순간 아무것도 해 주지 않으므로 이 문서의 회복 경로를 어차피 만들어야 한다. 그러고 나면 순수한 추가 제약이다 |
| 리스 수명 | 세션 수명 + 노드 로컬 턴 게이트 | **턴 단위 리스** — 같은 노드의 연속 턴도 경합하고, hand-off 가 리스 만료 대기가 된다 |
| 핸들 중복 | 허용, 권위는 레코드 | **핸들 소유권 강제** — 그것이 곧 sticky 다 |
| 캐시 키 | `SessionId` 단일 | **`(SessionId, agentRef)`** — 같은 세션에 두 핸들이 공존한다 |
| 시그널 페이로드 | 평평한 원시값 코덱 셋 | **타입 객체를 싣기** — in-process 버스에서만 참이고 실제 버스에서는 크로스 노드 `events()` 가 아무것도 전달하지 않는다 |
| 세션 이동 | `YIELD` | **`INTERRUPT`** — 홀더가 리스를 놓지 않는다. **`EVICT`** — 대기 중인 호출자에게 삭제됐다고 거짓말을 한다 |
| 비홀더에 들어온 턴 | hand-off | **`NOT_HOLDER` 응답** — 호출자가 할 수 있는 일이 대기뿐이면 프로토콜이 대신 기다린다 |
| 홀더 유실 회복 | 턴 스코프 | **세션 스코프 `EVICT`** — 죽은 턴 하나 때문에 살아 있는 세션을 헌다([`idempotency.md`](idempotency.md) §6) |
| 제출 결과 타입 | `SubmitDisposition` 별도 | **`SubmitOutcome` 재사용** — 축이 다르다 |
| 오버플로 | 세 등급, 구조 프레임 보존 | **델타 둘을 같은 등급으로** — 숙고 폭주가 답 텍스트를 밀어낸다 |
| 실패 코드 디코드 | 모르는 값은 `FAILED` | **`valueOf` 로 던짐** — 호출자의 future 를 잃는다 |
| 리스 숫자 | 30/10/15 유지 | **리스 단축** — 갱신 부하가 두 배가 된다. 블랙아웃은 기다림의 상한으로 줄인다 |
| 삭제 이벤트 | 라우터가 삭제한 노드에서, 리스 안에서 부르는 core SPI | **`SessionRecordStore.delete` 를 꾸미기** — 락을 우회하는 raw delete 에도 발화하고 리스 밖에서 발화해 같은 id 경쟁이 다시 열린다. **세션별 `events()` 의 종결 프레임** — release 와 delete 를 구분하지 못하고 삭제 전에 세션마다 구독이 필요하다. **`EVICT` 에 `reason=DELETED`** — best-effort 이고 모든 노드에서 발화한다. 공유 상태는 정리가 한 번이면 된다. **durable outbox** — 새 저장 SPI 와 백엔드 구현 넷이 필요하다. 확장에는 어차피 재조정 경로가 있으므로 at-most-once 로 충분하다 |

---

## 하지 말 것

- **`LiveSession.close()` 나 `SessionRouter.close()` 에서 `AgentRuntime` 을 닫지 않는다.** 같은 agent 의 다른 세션이 아직 쓴다
- **`status()` 를 제어 게이트로 쓰지 않는다.** best-effort 관측이다
- **라우터를 우회해 `SessionRecordStore.delete(id)` 를 부르지 않는다.** 진행 중 턴과 경합하고 리스너도 불리지 않는다
- **`onExtendFailed` 에서 블록하지 않는다.** 그 스레드를 공유하는 모든 세션이 리스를 잃는다
- **리스 갱신을 다른 스케줄러와 같은 풀에 두지 않는다.** 큐에 밀린 틱은 빼앗긴 리스와 구분되지 않는다
- **크로스 노드 페이로드에 타입 객체를 싣지 않는다**
- **새 `SignalKind` 를 shim 없이 내보내지 않는다.** 모르는 노드가 시그널 전체를 드롭한다
- **`sessionClosedListener` 를 Caffeine 의 `removalListener` 로 바꾸지 않는다.** pin 된 채 축출된 세션의 리스가 턴 도중 반납된다
- **삭제 리스너 안에서 지우는 중인 세션에 대해 `SessionRouter` 를 부르지 않는다**
- **삭제 리스너를 무겁게 만들지 않는다.** 그 세션의 제출을 막는다

---

## 남은 것

| 항목 | 지금 |
|---|---|
| 이벤트 replay | 라이브만. 진행 중 재시도로 붙은 구독자는 이미 흘러간 프레임을 못 본다 — 최종 결과는 멱등 원장으로 안전하다. UI 가 progressive rendering 을 요구하면 그때 버퍼 정책을 정한다 |
| 같은 세션의 다중 구독자(collaboration) | `events()` 는 멀티 구독자지만, 여러 사용자가 한 세션을 동시에 보는 UX 는 웹 어댑터 설계 사안이다 |
| 분산 서브에이전트 | 범위 밖(§7.5) |
| `YIELD` shim 제거 | 클러스터 전 노드가 이 kind 를 이해하게 되면 레거시 `INTERRUPT(SESSION_RELEASED)` 동반 발행을 걷어낸다 |
| 다른 노드의 삭제 통지 · `onCreated` | 백로그 [SO-3 · SO-4](../../backlog/session-owner-open-items.md) |
| 멱등·홀더 유실 쪽 | [`idempotency.md`](idempotency.md) 의 남은 것 |

---

## 참조 파일 지도

| 개념 | 파일 |
|---|---|
| 공개 계약 | [`SessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SessionRouter.java) |
| 구현 | [`DefaultSessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/DefaultSessionRouter.java) |
| 조립 | [`SessionRouterBuilder`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/builder/SessionRouterBuilder.java), [`DeploymentMode`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/DeploymentMode.java) |
| 제출·결과 | [`SubmitRequest`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SubmitRequest.java), [`SubmitDisposition`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SubmitDisposition.java) |
| 핸들 캐시 (§4.2) | [`LiveSessionCache`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionCache.java), [`LiveSessionOpener`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionOpener.java) |
| 리스 (§4.3) | [`HeldLease`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/HeldLease.java), [`LeaseRenewer`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/LeaseRenewer.java) |
| 시그널 (§5) | [`SessionSignal`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/signal/SessionSignal.java), [`SessionSignalBus`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/signal/SessionSignalBus.java) |
| 이벤트 (§6) | [`SessionEventRelay`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/SessionEventRelay.java), [`InProcessEventPublisher`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/InProcessEventPublisher.java), [`EventSink`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/EventSink.java) |
| 페이로드 코덱 (§6.3) | [`AgentExecutionEventPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/AgentExecutionEventPayload.java), [`TurnResultPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/TurnResultPayload.java), [`StatusSnapshotPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/StatusSnapshotPayload.java), [`PayloadValues`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/PayloadValues.java) |
| 삭제 리스너 (§9) | [`SessionLifecycleListener`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/SessionLifecycleListener.java), [`SessionDeletedEvent`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/SessionDeletedEvent.java) |
| 관측 | [`ClusterSessionStatus`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/ClusterSessionStatus.java), [`StatusProjection`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/StatusProjection.java), [`SessionMetrics`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/metrics/SessionMetrics.java) |
| 세션 계약 | [`LiveSession`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java), [`TranscriptManager`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptManager.java) |

---

## 관련 문서

- [`session-model.md`](session-model.md) — 두 수명과 영속 모델
- [`session-store.md`](session-store.md) — 라우터가 쓰는 SPI, `SessionStore`, 쓰기 펜스
- [`inbox.md`](inbox.md) — 크로스 노드 우편함
- [`idempotency.md`](idempotency.md) — 멱등 제출과 홀더 유실 회복
- [`backends.md`](backends.md) — Redis / MongoDB / PostgreSQL 구현
- [`../agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md) — `AgentRuntime` 소유권
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `InterruptCoordinator` 와 도구별 중단 동작
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임
- [`../../features/session/web-session-deployment-guide.md`](../../features/session/web-session-deployment-guide.md) — 배포·운영 가이드
