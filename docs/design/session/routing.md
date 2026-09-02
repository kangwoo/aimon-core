# 세션 라우팅 — sticky 없이 세션을 직렬화한다

> Status: **IMPLEMENTED** — `aimon-session-routing` (25파일). 공개 표면은 `SessionRouter` ·
> `SubmitRequest` · `SubmitDisposition` · `LiveSessionCache` · `LiveSessionOpener` ·
> `ClusterSessionStatus` · `DeploymentMode` · `SessionRouterBuilder` · `SessionMetrics` 이고,
> 나머지는 `at.aimon.session.routing.internal` 에 있다. 이 문서가 의존하는 SPI 4종
> (`SessionRecordStore`/`SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`,
> `IdempotencyStore`)은 `aimon-core` 에 있고 — 그 이관 경위는 [`spi-extraction.md`](spi-extraction.md) —
> 분산 구현은 `aimon-session-{redis,mongodb,postgres}` 다([`backends.md`](backends.md)).
> 남은 것은 §14.

---

## 1. 무엇을 푸는가

웹 서버(HTTP/WebSocket/SSE) 여러 대 앞에 로드밸런서가 있고, 같은 세션의 요청이 매번 다른 노드로 갈 수
있다. 이때 **sticky routing 없이** 세션의 턴을 클러스터 전체에서 하나씩만 돌게 만드는 것이 이 모듈이다.

### 1.1 목표

- **Sticky 미사용** — LB 가 어느 노드로 보내든 동작한다.
- **세션 단위 직렬화** — 같은 `SessionId` 의 턴은 전 클러스터에서 한 번에 하나.
- **자원 재사용** — `AgentRuntime` 재구축 비용(MCP 클라이언트, knowledge store)을 매 요청마다 물지 않는다.
- **장애 복원** — 홀더 노드가 죽어도 다른 노드가 이어받고, 그 사실이 구독자에게 전달된다.
- **저장소 교체 가능** — 상태는 SPI 뒤에 있고 기본 구현은 in-memory 다.

### 1.2 비목표

- **세션 소유권 고정** — 명시적으로 배제한다. LB 계층에 의존하지 않는 모델만 다룬다.
- **인증·권한** — 라우터는 `Principal` 을 받아 나르기만 한다. 누가 어느 세션에 접근할 수 있는지는 상위 계층.
- **서브에이전트의 분산 실행** — 포크는 부모 턴 안에서 같은 노드에 머문다(§7.6).

---

## 2. 제약 — 라이브 세션은 노드 로컬이다

이 설계 전체가 하나의 사실에서 나온다: **`LiveSession` 은 JVM 힙에 사는 노드 로컬 핸들이고,
`SessionRecord` 만 영속이다.** 관계는 1 : 0..N 이고 비대칭이다 —
[`overview/scope-model.md` §3](../../overview/scope-model.md).

따라서 라우터가 할 수 있는 일과 할 수 없는 일이 갈린다.

| | 노드 로컬 (핸들이 죽으면 사라짐) | 공유 (SPI 뒤) |
|---|---|---|
| 무엇 | `LiveSession`, 이벤트 publisher, 캐시 항목, 리스 갱신 스케줄 | 레코드, 리스, inbox, 시그널, idempotency |
| 라우터의 역할 | 만들고 닫는다 | 읽고 쓴다 — **소유하지 않는다** |

여기서 세 가지 규칙이 따라 나온다.

- **`AgentRuntime` 은 라우터의 것이 아니다.** agent-scoped 이고 세션들을 가로질러 산다.
  `LiveSessionOpener` 는 미리 등록된 런타임을 **찾아 쓸 뿐** 열 때마다 만들지 않으며,
  `SessionRouter.close()` 도 `LiveSession.close()` 도 그것을 닫지 않는다. 닫는 것은 부트스트랩의 일이다
  ([`../agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md)).
- **진행 중 턴의 상태는 노드와 함께 죽는다.** 그래서 턴 도중 partial state 를 커밋하지 않는 것이 중요하고,
  홀더 유실을 **감지해서 알려 주는 경로**(§6.3 D)가 별도로 필요하다.
- **크로스 노드로 나가는 것은 전부 JSON 원시값이다.** 시그널 코덱은 페이로드를 `LinkedHashMap` 으로 풀 뿐
  타입 객체를 복원하지 않는다. 타입 객체를 실어 보내고 `instanceof` 로 받는 설계는 in-process 버스에서만
  동작하고 실제 버스에서는 **조용한 no-op** 이 된다 — §5.5 의 페이로드 코덱 3종이 존재하는 이유다.

---

## 3. 핵심 설계 결정

### 3.1 세션 핸들은 노드마다 중복될 수 있다 — 허용한다

같은 `SessionId` 에 대해 두 노드가 각자 `LiveSession` 을 들고 있을 수 있다. 버그가 아니라 설계다.

- 핸들 소유권을 강제하면 그것이 곧 sticky 다(포워딩 비용이든 hand-off 비용이든 어딘가에서 물어야 한다).
- 메시지 이력의 권위는 `SessionRecordStore` 다. 핸들이 든 사본은 캐시일 뿐이고, 매 턴 시작 시
  `TranscriptManager.initialize` 가 레코드에서 다시 읽는다(§10.4).
- 중복된 MCP 클라이언트 비용은 idle TTL(기본 10분)이 정리한다.

대신 **턴 실행은 리스로 직렬화**한다 — 어느 노드가 돌리든 한 번에 하나.

### 3.2 리스는 세션 수명, 턴 게이트는 그 안에 따로 있다

처음 설계에서 리스는 턴 단위였다: `submit` 이 따고, 턴 루프가 갱신하고, `finally` 가 놓았다. 지금은
**리스가 세션 수명**이다 — 한 턴이 따서 다음 다섯 턴이 쓰고, 세션을 끝내는 무엇(유휴 정리, release,
셧다운, 갱신 실패)이 놓는다. 이유는 hand-off 다: 매 턴 리스를 놓으면 같은 노드가 다음 턴에서 다시 따야
하고, 그 사이가 전부 경합 구간이 된다.

리스가 턴 경계를 넘어 살게 되면 **"지금 이 노드에서 턴이 돌고 있는가"** 에 리스가 답을 못 한다. 그래서
그 질문 전용의 노드 로컬 게이트(`tryBeginTurn` / `endTurn`, `Set<SessionId>`)를 따로 둔다. 순서는
**게이트 먼저, 리스 나중**이다(§7.4). 게이트에서 지면 이 노드에 이미 턴·드레인·삭제가 돌고 있다는 뜻이므로
리스를 건드릴 이유가 없다.

이 분리 때문에 `HeldLease` 가 존재한다 — 리스 + 갱신 스케줄 + idempotency 슬롯을 묶고, `returned` /
`lost` 두 래치로 여러 종료 경로가 서로를 밟지 않게 한다.

### 3.3 크로스 노드 시그널은 하나의 버스, 채널은 **빈도**로 가른다

`SessionSignalBus` 의 `SignalKind` 는 일곱이다.

| Kind | 뜻 | 빈도 |
|---|---|---|
| `INTERRUPT` | 홀더의 활성 턴을 멈춘다. 세션은 그대로 | 드묾 |
| `EVICT` | 세션이 **없어졌다** — 구독자 완료, 전달된 future 실패, 승인 폐기, 상태 투영 제거 | 드묾 |
| `YIELD` | 세션을 넘겨라 — 턴을 멈추고 핸들을 버리고 **리스를 반납**한다 | 드묾 |
| `MESSAGE_ENQUEUED` | inbox 에 메시지가 들어왔다(초인종) | 턴당 ~1 |
| `TURN_RESULT` | 전달된 턴 1건의 종료 결과 | 턴당 1 |
| `EVENT` | `AgentExecutionEvent` 프레임 | 토큰 단위 |
| `STATUS` | 클러스터 관측용 상태 스냅샷 | 하트비트 |

`INTERRUPT` / `EVICT` / `YIELD` 셋이 나뉘어 있는 것이 이 표의 핵심이다. **멈추기**만 하면 홀더가 리스를
내놓을 이유가 없어 요청 노드가 영원히 기다리고, **축출**은 사후 종결이라 세션이 곧 다른 데서 서빙될
상황에 쓰면 대기 중인 모든 호출자에게 "삭제됐다"고 거짓말을 한다. 그래서 이동에는 `YIELD` 가 따로 있다.
`YIELD` 는 나중에 추가된 값이라, 모르는 노드가 시그널 전체를 드롭하지 않도록 발행 측이 레거시
`INTERRUPT(SESSION_RELEASED)` 를 **함께** 보내고 수신 측이 둘 다 존중하는 shim 이 `DefaultSessionRouter`
양쪽에 표시되어 있다.

채널 분배 기준은 의미가 아니라 **빈도**다 — `EVENT` 와 `STATUS` 는 events 채널로, 나머지는 control
채널로 간다(`RedisPubSubSignalBus.channelFor`). 구독자는 양쪽을 다 듣기 때문에 이 분배는 수신 여부가
아니라 폭주 격리에만 영향을 준다.

### 3.4 hand-off 는 `SessionInbox`, 세션 내부 큐와는 별개다

`MessageQueueManager` 는 **단일 세션 scope** 다 — `QueuedInput` 에 `sessionId`/`agentRef`/
`idempotencyKey` 가 없고, mid-turn 주입이라는 다른 목적을 위해 존재한다. 라우터가 필요로 하는 것은
actor 의 mailbox 의미론이다: 아무 노드나 넣을 수 있고 리스 홀더만 꺼낸다.

| | `MessageQueueManager` | `SessionInbox` |
|---|---|---|
| scope | 세션 하나, 프로세스 안 | 크로스 노드, 멀티 세션 |
| 역할 | mid-turn 주입 | 비홀더의 deliver → 홀더의 collect |
| payload | `QueuedInput` | `InboundMessage` (봉투에 `sessionId`/`agentRef`/`idempotencyKey`/`Principal`) |

두 채널은 공존한다. 라우터는 inbox 만 만지고, 세션 내부 큐는 홀더가 collect 한 메시지를 `QueuedInput`
으로 다시 싸서 넣을 때만 닿는다(§9.3).

### 3.5 라우터는 어느 노드에서 불러도 같게 보인다

`submit` / `interrupt` / `events` / `releaseSession` 은 호출자에게 노드를 노출하지 않는다. 캐시
hit/miss 도, 실제로 어느 노드가 턴을 돌렸는지도 투명하다. 다른 노드가 홀더면 호출자는 `FORWARDED` 를
받지만 — **그래도 완결되는 future 를 함께 받는다**(§9.1). "어디서 도느냐"는 라우터 안쪽 사정이다.

### 3.6 `SessionId` 가 agent 를 결정한다

세션 하나에 agent 하나다. `LiveSession` 의 계약과 같다.

- **첫 턴에서만 결정**한다. 이후 `submit` 의 `agentRef` 는 기록된 값과 비교만 한다.
- 다르면 `ConflictingAgentException` — 웹 어댑터가 409 로 매핑한다.
- 캐시 키는 `SessionId` **단일**이다. `(SessionId, agentRef)` 합성 키는 같은 세션에 두 핸들이 공존하는
  모순을 만든다.

바인딩은 `SessionRecord.agentRef` 에 얹혀 있고, **쓰는 것은 `SessionStore.claim` 하나뿐**이다. 리스가
보장하는 것은 턴 직렬화이지 레코드 write 직렬화가 아니므로, 이 sole-writer 규칙이 깨지면 리스 기반 분석이
통째로 무너진다. 그래서 `SessionRecordStore.load` 는 읽기 전용 `SessionRecordView` 를 돌려주고, mutable
`SessionRecord` 직접 import 는 ArchUnit 이 막는다.

**첫 턴 경합**은 검증을 두 번 해서 수렴시킨다. 두 노드가 동시에 첫 턴을 시도하면 둘 다 "바인딩 없음"을
보고 통과하지만, 실제 write 는 `claim` 안에서 일어나므로 진 쪽의 메시지는 inbox 에 남는다. 그래서 홀더는
**collect 시점에 `InboundMessage.agentRef` 를 다시 비교**하고, mismatch 면 그 메시지를 처리하지 않고
`RejectedAt(CONFLICTING_AGENT)` 를 emit 한 뒤 버린다. 봉투가 `agentRef` 를 나르는 이유가 이것이다.

---

## 4. 아키텍처

### 4.1 컴포넌트 배치

```
┌─ SessionRouter (노드당 1개) ────────────────────────────────────────────────┐
│                                                                            │
│  submit / interrupt / events / status / releaseSession / deleteSession      │
│       │                                                                    │
│       ├─▶ IdempotencyStore ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐   │
│       │        (§9.2)                                                  │   │
│       ├─▶ 턴 게이트 (activeTurns, 노드 로컬)  ── §3.2                   │   │
│       │                                                                │   │
│       ├─▶ SessionStore ─ ─ ─▶ SessionLeaseStore  (리스 선출 + 펜싱)  ─ ┤   │
│       │      (§5.3)     └ ─ ─▶ SessionRecordStore (레코드 + 바인딩)  ─ ┤   │
│       │        ▲                                                       │   │
│       │        └── HeldLease ◀── LeaseRenewer (§7.4) ◀── lease sched   │   │
│       │                 └── IdempotencyTouchSlot                       │   │
│       │                                                                │   │
│       ├─▶ LiveSessionCache ─▶ LiveSessionOpener ─▶ LiveSession         │   │
│       │      (§5.2)                                    │ listener      │   │
│       │      idle TTL + LRU, pin                       ▼               │   │
│       │                                    SessionEventRelay (§5.5)    │   │
│       │                                      ├─▶ InProcessEventPublisher   │
│       │                                      └─▶ EVENT rail ─ ─ ─ ─ ─ ┤   │
│       │                                                                │   │
│       ├─▶ SessionInbox ─ ─ ─ ─ ─ (deliver / collect) ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤   │
│       ├─▶ SessionSignalBus ─ ─ ─ (7 kinds, 2 channels) ─ ─ ─ ─ ─ ─ ─ ─ ┤   │
│       │        └── StatusProjection (원격 STATUS 접기)                  │   │
│       │                                                                │   │
│       └─▶ HolderLossSweeper (§6.3 D) ─▶ LostTurnAnnouncer               │   │
│                                                                        │   │
└────────────────────────────────────────────────────────────────────────┼───┘
                                                                         ▼
                          분산 백엔드 (Redis / MongoDB / PostgreSQL — backends.md)
```

**스케줄러는 셋으로 나뉜다.** 한 작업의 지연이 다른 작업을 막지 않게 하기 위해서다.

| 스케줄러 | 담당 | 크기·주기 |
|---|---|---|
| lease | `LeaseRenewer` | `max(2, availableProcessors())`, `lockExtendInterval` (기본 10초) |
| cache sweeper | `LiveSessionCache.sweep()` | 단일 스레드, 1분 |
| holder-loss sweeper | `HolderLossSweeper` | 단일 스레드, `holderLossSweepInterval` (기본 15초) |

리스 갱신이 자기 풀을 갖는 것이 특히 중요하다 — 갱신 틱이 **큐에 밀린 것**과 리스를 **빼앗긴 것**은
구분되지 않는다. 그리고 relay 의 원격 fan-out 은 별도 `ExecutorService` 로 빠진다(§5.5).

### 4.2 의존 방향

```
aimon-core (SPI + LiveSession 계약)  ◀── aimon-session-routing  ◀── (어셈블리)
        ▲
        └── aimon-session-{redis,mongodb,postgres}  (SPI 구현, main 은 routing 을 모른다)
```

백엔드 모듈의 **main** 은 `aimon-core` 만 본다. 라우팅은 `testImplementation` 으로만 끌어온다 —
멀티 노드 테스트가 진짜 라우터를 돌려야 하기 때문이다. 경위는 [`spi-extraction.md`](spi-extraction.md) §5.

---

## 5. 컴포넌트

### 5.1 `SessionRouter` — 공개 계약

| 메서드 | 계약 |
|---|---|
| `submit(SubmitRequest)` | `SubmitDisposition` 반환. agent 불일치면 `ConflictingAgentException` |
| `events(SessionId)` | `Flow.Publisher<AgentExecutionEvent>`. 로컬 발생분 + 원격 릴레이분 |
| `interrupt(id, reason)` | 라이브 세션 스코프 — 이 세션의 활성 턴을 멈춘다 |
| `interrupt(id, turnId, reason)` | **겨냥한** 형태. 활성 턴과 다르면 no-op |
| `releaseSession(id)` | 핸들 반납. 이력은 남는다 |
| `deleteSession(id)` | 이력까지 삭제. **홀더만** 할 수 있다 (§7.7) |
| `status(id)` | `ClusterSessionStatus` — best-effort 관측, **제어 게이트 아님** |
| `close()` / `closeGracefully(timeout)` | 노드 로컬 자원만. `AgentRuntime` 은 건드리지 않는다 |

`interrupt` 의 두 형태는 의도적으로 다르다. `TurnId` 를 준 쪽은 "내가 보고 있던 그 턴"을 겨냥한 것이므로
활성 턴이 이미 다음 턴으로 넘어갔으면 아무것도 하지 않는다 — 취소를 누른 사용자에게는 그게 맞다. id 가
**없는 것은 "모름"이 아니라 옛 의미**(세션 스코프)다.

`status()` 를 제어에 쓰면 안 된다는 규칙은 여기서도 같다 — 읽는 순간과 행동하는 순간 사이에 상태가 바뀐다.
턴을 시작할 수 있는지는 `submit` 의 `SubmitDisposition` 이 답한다.

### 5.2 `LiveSessionCache` — 두 개의 상한, 비대칭 pin

노드당·세션당 핸들 캐시다(Caffeine). 상한이 둘이고, **pin 이 둘에 다르게 작용한다.**

| 상한 | pin 의 효과 | 이유 |
|---|---|---|
| idle TTL | 축출 자체를 **막는다** | 만료된 항목은 즉시 맵을 떠나므로, 닫기만 미루면 같은 세션에 두 번째 핸들이 열린다 |
| LRU (`maxEntries`) | 닫기를 **미룰 뿐** 축출은 일어난다 | 이 상한은 힙 경계다. 늦게 닫는 것보다 경계에 대해 거짓말하는 쪽이 나쁘다 |

LRU 쪽에 남는 이중 핸들 창은 한 단계 위에서 닫는다 — 라우터가 pin 을 푸는 **같은 턴 게이트 안에서**
리스를 반납한다.

두 가지가 이 클래스에서 미묘하다.

- **`PinAwareExpiry` 는 세 콜백을 모두 재정의한다.** 하나라도 빠지면 pin 된 항목을 읽는 것만으로 보호가
  풀린다.
- **`sessionClosedListener` 는 `session.close()` **뒤에** 발화한다.** Caffeine 의 `removalListener` 로는
  안 되는데, pin 된 채 축출된 항목은 세션이 닫히기 한참 전에 맵을 떠나기 때문이다. 그때 리스를 반납하면
  아직 도는 턴이 자기가 더 이상 들고 있지 않은 이력에 쓰게 된다.

`SessionEntry` 는 `pins` / `closeRequested` / `closed` 를 **한 모니터** 아래 둔다. lock-free 판본에는
세션이 아예 닫히지 않는 창이 있었다. 단 `unpin()` 의 `onLastPinReleased` 호출만은 모니터 **밖**에서 —
안에서 부르면 락 순서 역전으로 데드락이 난다.

Caffeine 에는 백그라운드 청소부가 없으므로 `sweep()` 을 라우터의 스케줄러가 주기 호출한다.

### 5.3 `SessionStore` — 문 하나 뒤의 리스·레코드·바인딩

`SessionLeaseStore` 와 `SessionRecordStore` 는 서로 다른 백엔드일 수 있다. 그 둘을 노드 스코프 합성물
하나로 묶은 것이 `SessionStore` 이고, `claim` 이 **이 순서로** 수행한다.

```
claim(sessionId, agentRef, nodeId, lease)
  1. 리스 선출        → 진 노드는 여기서 끝난다 (HeldElsewhere)
  2. agent 바인딩 검증 → 불일치면 AgentConflict
  3. 레코드 프로비저닝 → 없으면 만들고 agentRef 를 기록
```

순서가 이래야 **분산 트랜잭션이 필요 없다.** 선출에서 진 노드는 레코드를 아예 건드리지 않으므로, 두
백엔드가 서로 다른 시스템이어도 half-committed 상태가 생기지 않는다.

이 스토어가 **이 노드가 쥔 리스를 추적**하기 때문에 `records()` 가 펜싱 토큰을 ReAct 콜체인으로 흘리지
않고도 쓰기를 펜싱할 수 있다. 대가는 하나다 — **한 JVM 에 라우터가 둘이면 스토어도 둘 만들어야 한다.**
같은 두 백엔드 위에 각자. 빌더가 두 백엔드를 받아 스토어를 **직접 조립**하는 것은 이 규칙을 사람이
기억할 것이 아니라 구조로 만들기 위해서다.

### 5.4 `SessionSignalBus`

발행은 **at-least-once** 다. 같은 신호가 두 번 와도 견뎌야 하고, 순서 보장은 없다. 그래서:

- `StatusProjection` 은 `seq` 스탬프로 최신만 남긴다 — **다른** origin 이면 무조건 수용(홀더 교체는 항상
  이겨야 한다. 새 홀더의 `seq` 는 옛 홀더와 독립이다), **같은** origin 이면 `seq` 가 더 클 때만.
- `MESSAGE_ENQUEUED` 는 초인종일 뿐이다. 홀더가 처리하는 시점에 inbox 가 이미 비어 있어도 정상(앞선
  collect 가 가져갔다). 빈 결과는 no-op.
- 모르는 `SignalKind` 이름은 `valueOf` 에서 던지고 시그널 전체가 드롭된다. 그래서 새 kind 는 §3.3 의
  shim 을 거쳐 굴러 들어온다.

### 5.5 `SessionEventRelay` — 턴당 하나

세션의 `submitAsync(input, listener)` 리스너로 주입되어 프레임을 두 곳으로 가른다: 로컬
`InProcessEventPublisher`, 그리고 `EVENT` rail.

**턴당 하나**다. 자기가 서비스하는 턴의 `TurnId` 를 생성자로 받아 모든 페이로드에 찍는다. 두 턴이 릴레이를
공유하면 두 번째 턴의 프레임이 첫 번째 턴의 것으로 표시되는데, 그것이 정확히 턴별 구독이 봐서는 안 되는
장면이다.

#### 5.5.1 프로듀서는 절대 블록하지 않는다

`accept()` 는 턴 실행 스레드에서 돈다. 그러므로:

- 로컬 fan-out 은 `SubmissionPublisher.offer(item, 0L, NANOS, onDrop)` — demand 가 없는 구독자가 있으면
  **가장 오래된 이벤트를 버리지** 프로듀서를 세우지 않는다.
- 원격 fan-out 은 유한 `ArrayBlockingQueue` 로 분리하고 라우터 소유 디스패처 스레드가 뺀다. Redis publish
  지연이 턴을 세울 수 없다.

**오버플로 정책**은 프레임이 다 같지 않다는 데서 나온다. `AssistantTextDelta` 는 양이 많고 개별 가치가
낮지만, 종결 프레임(`ExecutionCompleted` / `ExecutionError` / `InterruptedAt` / `RejectedAt`)은 원격
구독자에게 **턴이 끝났음을 알리는 유일한 것**이다. 그것을 잃은 구독자는 영영 완료되지 않는다. 그래서 버퍼가
넘치면 **가장 오래된 텍스트 델타부터** 버리고, 버릴 델타가 하나도 없을 때만 구조 프레임을 희생한다. 드롭은
세고(`getDroppedEventCount()`) `close()` 에서 보고한다 — 빈틈이 조용한 적은 없다.

#### 5.5.2 페이로드 코덱 3종 — 평평한 원시값만

`AgentExecutionEventPayload` / `TurnResultPayload` / `StatusSnapshotPayload`. 셋 다 같은 이유로 존재한다
(§2 마지막 규칙). 알려진 손실은 명시해 둔다.

| 코덱 | 잃는 것 |
|---|---|
| `AgentExecutionEventPayload` | `ExecutionError.getCause()` 는 메시지만 — 원 타입·스택트레이스 소실. `ToolUseStarted.getInputSummary()` 의 비원시 값. Duration 은 ms 단위 |
| `TurnResultPayload` | **artifacts 전부** — §9.4 |
| `StatusSnapshotPayload` | `LiveSessionStatus.getOptions()` (관측에 불필요, 페이로드만 키움) |

숫자는 전부 `Number` 로 읽는다. JSON 왕복이 int 범위의 `long` 을 `Integer` 로 좁히고 정수를 `Double` 로
넓히기 때문이다(`PayloadValues`). 모르는 `type` 은 `Optional.empty()` 로 — 롤링 배포에서 새 노드의 이벤트를
옛 노드가 만나도 우아하게 무시한다.

### 5.6 `SessionInbox`

봉투(`InboundMessage`)가 나르는 것은 `sessionId` · `agentRef` · `userInput` · `priority` ·
`idempotencyKey` · `Principal` · `deliveredAt` 다. `LiveSessionOptions` 와 `OpenAttributes` 는 **나르지
않는다** — 그 결정은 §7.1 F2 에 있다.

`collect` 는 원자 배치다. 시그널이 at-least-once 여도 collect 가 배치라서 순서와 정합성이 모두 보존된다.

---

## 6. 라이프사이클

### 6.1 열기

| 단계 | 시점 | 동작 |
|---|---|---|
| 라우터 | 부팅 | 노드당 1회. 애플리케이션 스코프 자원 주입 |
| 세션 | 첫 `submit` | 레코드 lazy 생성 (`SessionStore.claim` 3단계) |
| 핸들 | 그 노드에 첫 요청 도착 | `LiveSessionCache.ensureOpen` → `LiveSessionOpener` → 등록된 `AgentRuntime` **조회** |

warm-up 은 하지 않는다. 어차피 어느 노드로 갈지 모르고, 안 쓰인 핸들은 곧 유휴 정리된다.

### 6.2 활동 중

- 같은 노드로 후속 메시지 → 캐시 hit, `lastActivityAt` 갱신.
- 다른 노드로 → 그 노드에서 lazy open. 두 노드 공존 가능, 리스가 턴 직렬성을 지킨다(§3.1).
- 긴 턴 → `LeaseRenewer` 가 `lockExtendInterval` 마다 갱신(§7.4).

### 6.3 종료 — 네 가지

#### A. 명시적 release — `releaseSession(id)`

"end" 가 아닌 이유: 이력을 지우지 않는다. 같은 `SessionId` 로 다시 보내면 이력이 그대로 살아난다. 이 API 는
**핸들 반납**이지 세션 종료가 아니다.

진행 중 턴이 있으면 순서가 보장된다 — `interrupt(SESSION_RELEASED)` → 턴 future await
(`releaseInterruptTimeout`, 기본 5초) → `session.close()` → 캐시 evict → 리스 반납 → 크로스 노드 `EVICT` →
publisher `complete()`. await 이 타임아웃하면 강제 close + 즉시 onComplete (일부 in-flight 이벤트 손실,
warn 로그).

inbox 를 purge 했으므로 이 노드가 들고 있던 **초인종 표시도 함께 버린다**(`forgetDoorbell`). 남겨 두면 다음 리스
반납 때 빈 드레인 패스를 한 번 사고, 같은 `SessionId` 를 재사용하는 나중 세션이 자기 것이 아닌 통지를 물려받는다.
delete 와 피어의 `EVICT` 수신도 같은 이유로 같은 일을 한다.

#### B. 유휴 타임아웃

노드별 sweeper 가 `lastActivityAt + idleTtl < now` 항목을 정리한다. **크로스 노드 broadcast 는 하지
않는다** — 다른 노드는 자기 기준으로 알아서 한다. 기본 10분. 너무 짧으면 다음 메시지마다 MCP 재연결 비용을
문다.

#### C. 셧다운 (graceful)

1. 새 제출 거부 (`acceptingSubmits` 를 내린다 — `submit` 은 `IllegalStateException`).
2. 진행 중 턴 완료 대기 (`closeGracefully(timeout)`).
3. 타임아웃 후 남은 턴에 `interrupt(SYSTEM_SHUTDOWN)`.
4. 모든 핸들 close.
5. **이 노드가 쥔 리스를 명시적으로 반납** — 다른 노드가 lease 만료를 기다리지 않아도 된다. 실패는 삼킨다
   (어차피 TTL 로 풀린다).
6. publisher `onComplete`, 시그널 구독 해제, 스케줄러 종료.
7. **애플리케이션 스코프 자원은 건드리지 않는다** (§2).

#### D. 홀더 유실 (crash / OOM)

홀더가 죽으면 리스는 TTL 로 풀리고 다른 노드가 이어받는다. 문제는 **구독자**다 — `EVENT` 가 그냥 끊기면
원격 `events(id)` 구독자는 무한 대기한다. `HolderLossSweeper` 가 이것을 자가 회복시킨다.

```
매 holderLossSweepInterval:
  IdempotencyStore 에서 lastTouchedAt < now - secondaryTtl 인 IN_FLIGHT 를 훑는다
    └─ compareAndReset(key, expectedHolder)   ← 클러스터 전체에서 정확히 한 노드만 이긴다
         ├─ EventSink.emit(InterruptedAt(HOLDER_LOST))  — 로컬 + EVENT rail
         └─ LostTurnAnnouncer.announceHolderLost(...)   — 그 턴을 기다리던 future 들을 지금 실패시킨다
```

**회복은 턴 스코프다.** 예전에는 세션 스코프였다 — 이벤트 스트림을 완료시키고 `EVICT` 를 broadcast 해서
모든 노드가 세션을 헐었다. 그런데 **홀더 유실은 세션 유실이 아니다.** 리스가 만료되면 후계자가 이어받고,
스윕이 돌기도 전에 이미 다음 턴을 돌고 있을 수 있다. 그 broadcast 는 후계자의 `claim` 과 경합해 **살아 있는
세션을 축출**했다 — 캐시를 버리고, 승인을 폐기하고, 구독자의 스트림을 다른 노드에서 죽은 턴 때문에 완료시켰다.
스위퍼가 감지하는 것은 **죽은 시도 하나**이고, 지금은 그것만 보고한다.

이 축소가 `EventSink` 에 `complete` 가 없는 이유이기도 하다 — 추상화가 그 말을 **할 수 없게** 만들었다.
`complete` 는 진짜로 세션을 끝내는 경로(`releaseSession`, `deleteSession`, `EVICT` 수신)에만 남아 있고,
그 경로들은 구체 타입을 들고 있다.

`LostTurnAnnouncer` 가 턴을 `TurnId` 가 아니라 **idempotency key 로** 지목하는 것도 같은 사정이다. 살아남은
어느 노드도 그 턴의 id 를 모른다 — 예약은 홀더를 기록하지 홀더가 돌리던 턴을 기록하지 않고, 그 id 는 죽은
홀더가 소비한 inbox 봉투 안에 있었다. 추측해서 채울 빈칸이 아니다.

감지 지연 = `max(리스 TTL, secondary TTL)` + 스윕 지연. 기본값 기준 최대 ~45초.

**수집된 메시지도 로컬 턴과 똑같이 보인다.** 드레인 패스는 메시지를 하나 돌리기 직전에 그 예약의 홀더를
`acquireHolder` 로 넘겨받고, `(키, reserverId)` 를 리스의 `IdempotencyTouchSlot` 에 바인딩한다(§7.4). 그래서 그
메시지를 돌리던 노드가 죽으면 touch 가 멈추고, secondary TTL 이 지난 뒤 위와 **같은 스윕**이 그것을 잡는다 —
제출 노드의 forward 는 `HOLDER_LOST` 로 같은 ~45초 안에 답을 받는다. 넘겨받기 이전에는 이 턴만 **이름이 없어서**
스윕에서 빠졌고, 호출자는 `idempotencyForwardTtl`(기본 5분) 타임아웃으로만 답을 받았다. 재-초인종은 여기에
쓸모가 없다 — 메시지가 이미 inbox 밖이라 드레인 패스가 집어 올 것이 없다.

넘겨받기가 **실패해도 그 메시지는 그대로 실행한다.** 실패는 **넷** 중 하나이고 모두 "가져가면 안 되는" 경우이지
"돌리면 안 되는" 경우가 아니다 — `DONE`(누군가의 캐시된 답), 홀더가 있는 예약(다른 노드가 돌고 있다), 만료된
예약(그 forward 는 이미 포기했다), 그리고 저장소가 던진 경우. 메시지는 이미 at-most-once inbox 밖이라 거절하면
아무 노드도 복구할 수 없다. (패스를 연 제출 자신의 메시지는 애초에 시도하지 않는다 — 그 예약에는 제출 시점에
이미 이 노드 이름이 적혀 있어서 넘겨받기가 거절할 수밖에 없다.)

지는 대가는 **둘**이다. 그 턴은 이 노드 이름이 없는 예약 위에서 돌므로 (1) **스위퍼가 이 노드의 죽음을 그 턴에
대해 볼 수 없고** — 호출자는 forward 마감으로 되돌아가며, 그것이 이 연산이 없던 때의 상태다 — (2) **그 결과를
멱등 캐시에 쓰지 않는다.** `markDone` 은 네 백엔드 모두 키만 보고 매치하므로, 방금 "남의 것"이라고 판정한 항목을
덮어쓰는 것이 안전이 아니라 해악이기 때문이다(§9.2). 호출자는 어느 쪽이든 rail 로 답을 받는다.

**대가가 하나 더 있고, 그것은 이 변경이 새로 만든 것이다.** 전달된 턴의 예약은 이전에는 아무도 touch 할 필요가
없어 forward TTL(5분) 위에 얹혀 있었고, 따라서 touch 실패로 스윕당할 수가 없었다. 이제는 **리스 갱신은 되는데
`touch` 만 secondary TTL 을 넘겨 실패하는 경우** — 리스 백엔드는 멀쩡한데 멱등 백엔드만 흔들리는 구간이고,
`IdempotencyTouchSlot.touch()` 는 예외를 삼킨다 — 살아 있는 전달 턴이 스윕되고, 호출자가 실패하고, 클라이언트
재시도가 **같은 요청을 두 번 실행**한다. 이것은 로컬 키 있는 턴이 이미 살고 있던 조건과 같은 것이고 그 대칭이
이 변경의 목적이지만, 순이익만은 아니다. 두 백엔드를 같은 저장소에 두면 이 구간 자체가 없어진다.

**아직 실행되지 않은 메시지는 스위퍼가 보지 못한다.** 스위퍼가 훑는 것은 `IN_FLIGHT` 예약, 즉 누군가 **돌리던**
턴이다. 홀더가 죽은 시점에 그 세션의 inbox 에 아직 collect 되지 않은 메시지가 남아 있으면 그 예약에는 홀더가
없고(제출 노드가 forward 하면서 `releaseHolder` 로 비웠다) 따라서 스윕 대상이 아니다. 리스는 TTL 로 풀리지만
**죽은 노드는 재-초인종(`republishDoorbell`)을 울려 줄 수 없고**, 그 초인종을 들었던 피어들은 전부 "세션이 잡혀
있다"를 보고 물러난 뒤다. 그래서 그 메시지는 세션의 **다음 제출**까지 기다렸다 — 올지 안 올지 모르는 제출을.
그 사이 호출자의 forward 는 `idempotencyForwardTtl`(기본 5분)을 통째로 기다린 뒤 타임아웃했다.

닫는 쪽은 **기다리고 있는 노드**다. `pollForward` 는 forward 가 미해결인 동안에만 도는 타이머이므로, 매 틱마다
그 세션의 초인종을 다시 울린다. `tryDrainOnce` 를 직접 부르지 않고 `ringDoorbell` 을 쓰는 것은 그 스레드가
스케줄러 스레드이기 때문이다(드레인 패스는 턴을 통째로 돌린다 — §7.4 의 "블록하면 안 된다"와 같은 규칙).

- **inbox 가 비어 있으면 울리지 않는다.** 드레인 패스가 집어 올 수 있는 것은 아직 수집되지 않은 메시지뿐이다.
  누군가 이미 collect 했다면 그 메시지는 at-most-once inbox 밖으로 나갔고 결과를 낼 수 있는 것은 그 노드뿐이므로,
  재-초인종은 아무것도 찾지 못하고 빈 패스 하나를 살 뿐이다 — 건강한 홀더가 턴 중인 경우와 같다. 이 검사는 피어가
  delete 한 세션을 이 경로가 되살리지 않게 하는 일도 겸한다(delete 는 inbox 를 purge 하므로, 그 피어의 `EVICT`
  가 이 forward 를 실패시키기 전이라도 재시도가 조용해진다). 읽기가 실패하면 울린다 — 큐를 볼 수 없다는 것이
  큐가 비었다는 증거는 아니다.
- **회복 시간**은 리스 만료 + 폴 간격 1회로 묶인다. 기본값(리스 30초, 폴 간격 = secondary/2 = 15초) 기준
  최대 ~45초로, 위 홀더 유실 감지와 같은 자릿수다.
- **살아 있는 홀더에게는 아무 일도 일어나지 않는다.** 갱신 중인 리스는 `acquire` 로 뺏기지 않으므로 대기 중인
  호출자 하나당 폴 간격마다 실패하는 acquire 1회가 전부이고, 그 `acquire` 가 성공한다는 것 자체가 아무도
  갱신하지 않는다는 증거다.
- 재시도 횟수는 `SessionMetrics.onForwardDoorbellRerung` 이 센다(§12).

여전히 남는 것은 **기다리는 노드까지 같이 죽은 경우**다 — 그 메시지는 inbox 에 남아 세션의 다음 제출을 기다린다.
자동으로 줍게 하려면 inbox 쪽에 스캔 SPI 가 필요하다(§14).

### 6.4 끝나지 않는 턴

`extend` 가 실패하면(펜싱 토큰 불일치 = 리스가 이미 넘어감) `onExtendFailed` 가 **정확히 한 번** 발화하고
라우터가 턴을 `interrupt(LEASE_LOST)` 로 끊는다. 이후 틱은 `lostLease` 래치로 no-op 이다.

이것은 `ExecutionBudget` 의 시간 상한과 **별개의** 안전망이다. 예산이 먼저고, 리스는 분산 쪽 그물이다.

---

## 7. 동시성 — 게이트, 리스, 제출 시퀀스

### 7.1 제출 시퀀스 (F0–F7)

```
[제출 노드]
 F0  시그널 구독 먼저 — I/O 보다, disposition 이 정해지기보다 앞
 F1  바인딩 사전 검증 → 불일치면 ConflictingAgentException
       (리스도 idempotency 도 건드리지 않는다)
 F2  IdempotencyStore.putIfAbsent → §9.2 매트릭스
       턴 게이트 시도  ─┐  게이트나 리스에서 지면:
       SessionStore.claim ┘    inbox.deliver(InboundMessage) + MESSAGE_ENQUEUED broadcast
                              → SubmitDisposition.forwarded(turnId, future)

[홀더 노드]
 F3  MESSAGE_ENQUEUED 수신 → 아직 돌고 있지 않던 세션이면 드레인 시작 (초인종 경로)
 F4  드레인 루프: collect(LATER) 로 전 tier 수령 → priority-then-FIFO 정렬 → 하나씩
       각 메시지마다 바인딩 재검증(§3.6). mismatch 면 RejectedAt + drop
 F5  session.submitAsync(input, relay) — relay 가 로컬 publisher 와 EVENT rail 로 fan-out
 F6  턴 종료: markDone **먼저**, 그다음 TURN_RESULT broadcast
       (broadcast 를 놓친 노드에게 권위는 스토어다 — 순서가 거꾸로면 놓친 노드가 볼 것이 없다)
 F7  제출 노드가 rail 로 받아 future 완결.
       rail 을 놓치면 IdempotencyStore 폴링 fallback 이 같은 일을 한다
```

**순서가 이런 이유.** F1 이 F2 보다 앞인 것은 명백히 거절될 요청이 예약도 남기지 않게 하기 위해서다. 리스
시도가 세션 열기보다 앞인 것은 옛 초안(open → acquire)이 리스 실패 시 방금 연 핸들을 유휴 TTL 까지 띄워
두었기 때문이다 — 리스 실패는 **다른 노드가 활발히 처리 중**이라는 뜻이므로 이 노드가 핸들을 들고 있을
이유가 없다.

**F0 이 맨 앞인 이유.** 구독을 disposition 이 정해진 뒤로 미루면, 그 사이에 홀더가 낸 `TURN_RESULT` 를
놓친다. 그러면 F7 은 폴링 fallback 에만 의존하게 되고, 그것은 느린 길이다.

**F4 의 always-collect.** 직전 홀더가 `collect` 와 리스 반납 사이에 다른 노드가 넣은 메시지(orphan)를 다음
홀더가 자기 턴 직전에 한 번 더 수령한다. 이 단순한 "턴 전에는 항상 collect" 규칙 하나로 release-deliver
경합에 의한 영구 누락이 사라진다. tier 를 `LATER` 까지 전부 가져오는 이유는 NOW orphan 이 직전 턴에 주입되지
못하고 남았을 수 있기 때문이다 — 그것은 다음 턴의 첫 후보가 되는 게 자연스럽다.

**F2 가 나르지 않는 것.** 봉투에 `LiveSessionOptions` 와 `OpenAttributes` 는 넣지 않는다. 홀더는 자기가
이미 연 핸들로 처리하므로 제출 노드의 열기 파라미터가 의미를 갖지 않는다.

### 7.2 홀더 쪽 드레인

F4 루프의 종료 조건이 §7.2 의 전부다.

```
턴 완료 (리스 보유 중, 게이트 보유 중)
  ├─ extra = inbox.collect(id, LATER)
  ├─ pendingQueue 도 extra 도 비었으면 → 루프 종료 (리스는 반납하지 않는다 — 세션 수명이다)
  └─ 아니면 priority-then-FIFO 로 병합하고 다음 턴
```

리스가 여기서 풀리지 않는 것이 §3.2 의 실제 결과다. 리스는 §6.3 의 네 경로 중 하나가 놓는다.

### 7.3 `holderId` 와 — `NOT_HOLDER` 대신 hand-off

리스 홀더는 노드 id 로 식별된다. 예전에는 `{nodeId}/{thread}/{turnSeq}` 였으나, 리스가 세션 수명이 되면서
턴·스레드를 붙일 이유가 없어졌다. (idempotency 예약의 홀더는 이와 **다른** 문자열이다 — §7.4 마지막 문단.)

원 설계는 홀더가 아닌 노드에 큐잉된 턴을 `NOT_HOLDER` 로 실패시키려 했다. 실제 구현은 **hand-off** 를 한다:
요청 노드가 `YIELD` 를 보내고 현 홀더가 턴을 멈추고 리스를 반납한다. 이유는 `NOT_HOLDER` 가 답으로서
쓸모없기 때문이다 — 호출자가 할 수 있는 일이 "리스 TTL 만큼 기다렸다 재시도" 뿐이라면, 그 기다림을
프로토콜이 대신 하는 편이 낫다. 준수는 best-effort 다: pin 된 세션은 닫기를 진행 중인 턴의 끝으로 미루므로
요청자가 무는 비용은 턴 하나의 나머지이지 즉시성이 아니다.

재진입은 허용하지 않는다. 턴 안에서 `submit` 을 다시 부르는 패턴은 라우터가 아니라 서브에이전트 계층의
책임이다(§7.6).

### 7.4 리스 수명과 갱신

**순서는 게이트 먼저, 리스 나중**이다(§3.2). 게이트에서 지면 리스를 아예 건드리지 않는다.

`LeaseRenewer` 는 **전용 스케줄러**에서 돈다. 턴 스레드에서 돌리면 LLM 호출이나 도구 실행에 막혀 리스가
조용히 만료된다. 권장 간격은 `lease/3` — 두 틱을 놓칠 여유이고, 기본값 30초/10초가 정확히 그것이다. 이
여유를 지키기 위해 스위프·하트비트·폴링과 풀을 공유하지 않는다. **큐에 밀린 틱은 빼앗긴 리스와 구분되지
않기 때문이다.**

갱신은 백엔드가 아니라 **`SessionStore.renew` 를 통한다.** 단정함의 문제가 아니다 — 갱신이 실패하면 스토어가
자기 리스 기록을 버리고, 그래서 바로 다음 펜싱된 레코드 write 가 새 홀더의 이력 위에 얹히는 대신 거부된다.
백엔드를 직접 갱신하면 스토어는 잃은 리스를 아직 들고 있다고 믿는다.

`onExtendFailed` 는 스케줄러 스레드에서 돈다. 그 스레드는 절대 바쁘면 안 되는 것이 존재 이유이므로
**블록하면 안 된다.** 세션 정리, 훅 발화, 리스 반납은 전부 다른 실행기의 일이다. 리스 하나를 잃는 것은 국소
사건이지만, 배포된 `OnSessionEnd` 코드가 끝나기를 기다리는 훅은 **이 스레드를 공유하는 모든 세션의 리스를
같이 잃게 만든다.**

**idempotency 슬롯이 여기 얹혀 있다.** 갱신이 성공할 때마다 그 시점에 `IdempotencyTouchSlot` 에 바인딩된
예약의 secondary TTL 을 함께 갱신한다(§9.2). 키를 스케줄 시점에 캡처하지 않고 **틱마다 읽는** 이유는 리스가
이제 턴보다 오래 살기 때문이다 — 턴 1 을 위해 시작된 스케줄이 턴 5 가 돌 때도 살아 있다. 캡처했다면 첫 턴
이후 모든 턴이 이미 끝난 예약을 갱신하고 자기 것은 방치했을 것이고, 스위퍼가 secondary TTL 후에 **살아 있는
턴의 키를 리셋**해 같은 요청이 두 번 실행됐을 것이다. 슬롯은 **예약 하나에 칸 하나**이고 칸 하나가
`(키, reserverId)` 쌍이다 — 둘을 따로 두면 틱이 턴 5 의 키를 턴 4 의 reserver 로 읽을 수 있고, `touch` 는
홀더가 어긋나면 **조용히 무시**한다.

칸이 하나가 아닌 이유는 한 패스가 두 예약을 동시에 책임질 수 있기 때문이다. 제출된 턴이 여는 드레인 패스는
inbox 에 있던 메시지도 함께 돌리는데, **제출 자신의 예약**은 호출자가 기다리고 있으므로 패스 내내 살아 있어야
하고, **큐에 있던 메시지의 예약**은 그 메시지가 도는 동안만 이 노드 것이다(§6.3 D). 칸이 하나뿐이면 둘이 서로를
밀어내고, 형제 턴 하나가 secondary TTL 을 넘기는 것은 LLM 턴에서 흔한 일이므로 밀려난 쪽이 **멀쩡히 돌고 있는
노드에서 홀더 유실로 스윕된다.**

그 touch 가 지목하는 것은 **예약의 홀더**이지 `SessionLease.getHolderId()` 가 아니다. 예전에는 같은
문자열이었지만 리스가 맨 노드 id 로 잡히게 된 뒤로는 그것으로 touch 하면 아무 일도 일어나지 않는다.

### 7.5 Fairness — 보장하지 않는다

분산 리스는 공정하지 않다. 두 종류가 있다.

1. **선출 unfairness** — 동시 도착 시 누가 이길지 결정적이지 않다. 다만 inbox 안에서 priority-then-FIFO 가
   살아 있으므로 **메시지 처리 순서는 보존된다.**
2. **affinity starvation** — 홀더가 같은 리스 안에서 자기 노드의 후속 입력을 계속 처리하므로, 다른 노드에서
   deliver 된 메시지는 홀더가 collect 할 때까지 기다린다.

둘 다 받아들인다. 근거는 측정이다 — 크로스 노드 EVENT 전달 p50/p95/p99 가 단일 Redis 기준 1.73 / 2.97 /
3.74 ms(paced), 0.15 / 0.22 / 0.33 ms(burst, ~6.3 K ev/s 지속)로, 전형적인 LLM 턴 시간(수 초~수 분)에 비해
무시할 수준이다. **starvation 경계를 정하는 것은 broadcast 비용이 아니라 턴 길이**이므로 affinity 를 그대로
수용한다. 운영 메트릭에서 한쪽 노드의 long-tail wait 이 관찰되면 그때 FCFS inbox 기반 선출을 검토한다.

### 7.6 서브에이전트

포크는 부모 턴 안에서 같은 노드에 머문다. 부모가 리스를 들고 있는 동안 포크도 그 안에서 도는 **하나의 턴**
이므로 nested 리스 문제가 없다. 분산 포크는 이 설계 범위 밖이다.

### 7.7 삭제는 홀더만 한다

`deleteSession(id)` 는 이력을 지우는 유일한 API 다. 라우터를 우회한 `SessionRecordStore.delete(id)` 직접
호출은 진행 중 턴과 경합하므로 javadoc 에서 명시적으로 비권장한다.

절차는 이렇다.

```
deleteSession(id)
  1. 리스를 먼저 잡는다
  2. 경합하면 YIELD broadcast (+ 레거시 INTERRUPT(SESSION_RELEASED) shim) 로 현 홀더에게 양보를 요구
     releaseInterruptTimeout 까지 bounded retry
  3. 그 안에 못 잡으면 IllegalStateException — 호출자가 재시도한다
  4. 잡았으면 records().delete(id)
```

이 경로가 손대는 노드 로컬 상태가 셋 더 있다 — 바인딩 캐시, 세션 승인, 상태 투영. **승인은 노드 로컬이라
다른 노드의 `deleteSession` 이 여기 캐시된 사본에 닿지 못한다.** 그래서 `EVICT` 수신 핸들러가 같은 정리를
자기 노드에서 반복한다.

---

## 8. Interrupt 전파

사용자가 노드 B 에서 "stop" 을 눌렀는데 턴은 노드 A 에서 돈다.

```
[Node B] router.interrupt(id, USER)
   └─ SessionSignalBus.publish(INTERRUPT, id, reason, originNode=B)

[모든 구독 노드]
   └─ 이 노드에 활성 턴 또는 캐시 항목이 있으면 → session.interrupt(reason)
                                              (없으면 무시)
```

멈출 세션을 고를 때 **진행 중 턴의 세션이 캐시 항목보다 우선**이다. 캐시가 틀리는 경우가 정확히 그것이기
때문이다 — mid-turn 축출은 항목을 맵에서 지우지만 턴은 자기가 pin 한 세션 위에서 계속 돈다.

경합은 안전하게 수렴한다. 신호 도착 직전에 턴이 끝났으면 `LiveSession.interrupt()` 의 idempotency 계약
(활성 턴 없음 → 조용한 no-op)이 받아 준다. 겨냥한 형태(`turnId` 있음)는 활성 턴과 다르면 no-op 이고,
`FORWARDED` 턴은 애초에 이 노드에서 돌지 않으므로 여기서 no-op 이다.

---

## 9. 비동기 실행

### 9.1 `SubmitDisposition`

```java
enum Kind { EXECUTED_LOCALLY, FORWARDED }
```

**두 종류 모두** `TurnId` 와 `CompletionStage<AgentExecutionResult>` 를 필수로 나른다. `FORWARDED` 가
"기다려 봐라"가 아니라 완결되는 future 를 주는 것이 §3.5 의 실현이다. `FORWARDED` 만 추가로
`Optional<InboundMessageId>` 를 갖는다.

전달된 future 는 F7 의 두 경로 중 하나로 완결되고, 다음 경우 **예외로** 완결된다.

- agentRef 충돌 (`ConflictingAgentException`)
- 세션 축출 또는 삭제
- forward TTL 경과 (기본 5분)

이 타입은 `at.aimon.core.agent.session.SubmitOutcome` 과 **의도적으로 다르다.** 후자는 `EXECUTED`/`QUEUED`
로 한 라이브 세션 안에서의 즉시성만 말하고, 이쪽은 클러스터에서 어느 노드가 돌리는지를 말한다. 두 축이
다르므로 한 타입으로 겸하면 둘 다 흐려진다.

### 9.2 Idempotency

`SubmitRequest.idempotencyKey`(클라이언트 발급)로 at-most-once 를 보장한다. **메시지 내용으로 dedup 하지
않는다** — 그것은 inbox 의 일도 이 저장소의 일도 아니다.

| 케이스 | 라우터 동작 | 응답 |
|---|---|---|
| 키 없음 | dedup 안 함 | 정상 |
| 처음 보는 키 | `IN_FLIGHT` 기록 후 정상 처리 | 정상 |
| 같은 키 + 같은 입력 + 진행 중 | 새 턴 없음, **기존 턴에 attach** | 합성 id 로 `FORWARDED` — 결과는 그 턴의 future |
| 같은 키 + 같은 입력 + 완료 | 캐시된 result 로 즉시 완결 | `EXECUTED_LOCALLY`(이미 완료된 future) |
| 같은 키 + **다른** 입력 | 명백한 클라이언트 버그 | `IdempotencyConflictException` (409) |
| 같은 키 + primary TTL 경과 | 새 키처럼 | 정상 |
| 같은 키 + `IN_FLIGHT` + stale | `compareAndReset` 후 재진입 | 정상 |

입력 비교는 `inputHash`(정규화 후 SHA-256)로 한다.

**두 개의 TTL 이 있는 이유.** primary(기본 24시간)만 있으면 홀더가 죽었을 때 `IN_FLIGHT` 항목이 24시간 내내
박혀 있다. 그래서 `IN_FLIGHT` 에는 짧은 secondary TTL(기본 30초 = 리스와 동일)을 따로 두고, 홀더가 살아
있는 동안 리스 갱신이 함께 touch 한다(§7.4). 홀더가 죽으면 secondary 가 만료되어 항목이 없는 것처럼 되고,
primary 는 `DONE` 전이 후에야 효력을 갖는다.

**홀더 이름은 노드 사이를 오간다.** 제출 노드가 선출에서 지면 `releaseHolder` 로 홀더를 비운 채 메시지를
inbox 로 넘기고(§7.1 F2), 그 메시지를 수집한 노드가 `acquireHolder` 로 자기 이름을 다시 적으면서 TTL 을
secondary 로 되돌린다. 두 연산은 서로의 역이고 둘 다 원자적이다 — 같은 예약을 두 노드가 동시에 집어도 정확히
하나만 이긴다. 그래서 이름이 비어 있는 구간은 **아무도 돌고 있다고 알려지지 않은** 구간이고, 그것이
`findStaleInFlight` 가 홀더 없는 항목을 건너뛰는 근거다. **"아무도 돌지 않는" 과 같지는 않다** — 넘겨받기가
거절되거나 던지면 이름 없는 예약 위에서 도는 턴이 생기고, 그 턴에 대해서는 이 배제가 실제로 커버리지를 잃는다.
의도한 교환이다: 거짓 양성은 멀쩡한 턴을 죽이고 재시도를 이중 실행시키지만, 놓친 것의 대가는 호출자가 forward
마감까지 기다리는 것뿐이다(§6.3 D).

`markDone` 은 이 이름을 보지 않고 키만 본다. 그래서 넘겨받기에 진 노드는 **결과를 캐시에 쓰지 않는다** —
쓰면 방금 "남의 것"이라 판정한 항목을 덮어써서, 클라이언트가 이미 받은 답을 그가 본 적 없는 답으로 바꾼다.
홀더 일치 조건부 `markDone` 을 SPI 에 두면 이것을 저장소 쪽에서 강제할 수 있지만 지금은 라우터가 지킨다(§14).

**stale 정리의 경합 안전성**은 `compareAndReset(key, expectedHolderId)` 하나가 담당한다. 여러 노드가 동시에
같은 stale 항목을 발견해도 홀더가 일치하는 한 노드만 이기고, 나머지는 새 항목을 본다. 이긴 노드가 §6.3 D 의
두 단계를 수행한다.

운영 기본값: 리스 30초, secondary 30초, 스윕 15초. 감지 지연 최대 ~45초. stale-reset 빈도가 높으면 노드
안정성 신호로 읽는다(§12).

### 9.3 우선순위

`QueuedInputPriority` 는 `NOW` / `NEXT` / `LATER` 셋이고 봉투가 그대로 나른다.

- **`NOW`** — 홀더가 `MESSAGE_ENQUEUED` 를 받고 턴이 돌고 있으면 `collect(id, NOW)` 로 NOW 만 잘라
  `QueuedInput` 으로 다시 싸서 세션 내부 큐에 넣는다. 기존 mid-turn 주입 로직이 이어받는다.
- **`NEXT` / `LATER`** — 턴이 끝난 뒤 §7.2 의 collect 에서 처리된다.

정렬은 `(priority.ordinal, deliveredAt)` 안정 정렬이다 — NOW 가 NEXT/LATER 보다 앞이고, 같은 priority
안에서는 도착 순서가 보존된다.

한 가지 정직하게 적어 둔다. **`NOW` 의 즉시성은 홀더 노드에서만 성립한다.** 라우터는 턴 경계에서 회수하고,
mid-turn 주입은 `MESSAGE_ENQUEUED` 를 받은 홀더가 능동적으로 할 때만 일어난다. 그 밖의 경우 `NOW` 의 의미는
"다음 턴에서 가장 먼저" 로 축소된다.

### 9.4 전달된 턴은 artifacts 를 나르지 않는다

`AgentExecutionResult.getArtifacts()` 에는 와이어 인코딩이 없다. 그래서 `TURN_RESULT` rail 로 돌아온 결과는
artifacts 가 비어 있다. **이것은 계약이지 우회할 버그가 아니다** — 파일 아티팩트는 만든 노드의 가상
파일시스템에 있고, 그 바이트를 시그널 페이로드에 실어 나르는 것은 이 rail 의 목적이 아니다. 필요하면
공유 파일시스템(`aimon-filesystem-{gridfs,s3}`)을 쓴다
([`../agent-execution/artifact.md`](../agent-execution/artifact.md)).

실패 코드는 **전방 호환**이다. 모르는 `Failure.Code` 이름은 메시지를 보존한 채 `FAILED` 로 디코드한다 —
롤링 배포에서 덜 정확한 이유로 열화될 뿐 시그널이 통째로 드롭되지는 않는다. `SignalKind.valueOf` 가 던져서
시그널 전체를 날리는 것과 정반대의 선택이며, 이유는 여기서 잃는 것이 **호출자의 future** 이기 때문이다.

---

## 10. 장애 시나리오

### 10.1 분산 백엔드 일시 장애

| 영향 | 응답 |
|---|---|
| `claim` 실패 | `submit` 이 실패한다. 웹 어댑터가 503 으로 매핑, 재시도 권장 |
| `renew` 실패 | 진행 중 턴은 `interrupt(LEASE_LOST)`. partial state 는 커밋되지 않는다 |
| pub/sub 끊김 | interrupt 전파 불가. 재연결 후 재구독하며, 끊긴 동안의 신호는 손실 |
| 백엔드 영구 손실 | 모든 in-flight 턴 중단. `SessionRecordStore` 가 별도 백엔드면 이력은 보존 |

권장: 리스·시그널 백엔드는 HA 로 구성한다.

### 10.2 노드 다운

리스는 TTL 로 풀리고, 캐시는 사라지고, 진행 중 턴은 손실된다. inbox 가 분산 백엔드면 deliver 된 메시지는
살아남아 다른 노드가 collect 한다. 구독자는 §6.3 D 로 `InterruptedAt(HOLDER_LOST)` 를 받는다.

### 10.3 Split-brain

파티션으로 두 노드가 동시에 리스를 들었다고 믿는 경우, `SessionStore` 의 펜싱 토큰이 write 단계에서 걸러
낸다(§5.3). 리스를 짧게 잡고 갱신 실패 시 즉시 중단하는 정책이 창을 좁힌다.

`DeploymentMode.DISTRIBUTED` 가 SPI 미주입 시 **fail-fast** 하는 이유가 이것이다 — in-memory SPI 로 멀티
노드를 돌리면 조용한 split-brain 이 된다(§11.2).

### 10.4 Stale 캐시

두 노드가 같은 세션을 캐시하고 A 에서 턴이 돌면 B 의 사본은 낡는다. B 에 다음 요청이 오면 턴 시작 전에
레코드에서 다시 읽어야 한다.

이 불변식은 이미 성립한다. `OrcaAgentExecutor` 가 매 턴 시작 시 `TranscriptManager.initialize(sessionId,
systemPrompt)` 를 호출하고, 그 계약이 *"세션이 있으면 읽고 없으면 새로 만든다"* 이다. 즉 **다시 읽는 책임자는
핸들이 아니라 executor** 이고, 핸들의 사본은 권위가 아니다. 두 노드의 핸들이 잠시 서로 다른 시점의 view 를
들고 있어도 실제 턴은 항상 레코드 최신 상태에서 시작한다.

---

## 11. 공개 API

### 11.1 `SubmitRequest`

불변 클래스 + 빌더(프로젝트 규약). 필드:

| 필드 | 기본값 | 비고 |
|---|---|---|
| `sessionId` | (필수) | |
| `agentRef` | (필수) | §3.6 — 첫 턴 이후에는 비교만 |
| `contextDiscriminator` | 없음 | 공백과 `':'` 를 거부한다 — `AgentRuntimeId` 형식이 `:` 로 갈리기 때문 |
| `userInput` | (필수) | |
| `options` | `LiveSessionOptions.defaults()` | 열 때만 쓰인다 |
| `submitOptions` | `SubmitOptions.empty()` | 턴 단위 메타데이터 |
| `openAttributes` | `OpenAttributes.empty()` | 캐시 미스 시에만 opener 로 간다 |
| `idempotencyKey` | 없음 | §9.2 |
| `priority` | `NEXT` | §9.3 |
| `initiator` | 없음 | `Principal` |

`options` / `openAttributes` 가 "열 때만" 인 것에 주의한다. 캐시 hit 이면 무시된다 — 턴마다 달라져야 하는
값은 `submitOptions` 에 넣는다.

### 11.2 빌더와 `DeploymentMode`

`sessionFactory(...)` 와 `sessionOpener(...)` 중 **정확히 하나**를 설정한다. 전자는 무상태
`LiveSessionFactory` 를 위한 람다 친화 경로이고(`OpenAttributes` 를 무시한다 — `aimon-cli` 가 쓴다), 후자는
호출자 도메인 속성(tenant id 등)을 세션 열기까지 흘린다.

빌더는 `SessionRecordStore` 와 `SessionLeaseStore` 를 **따로** 받아 `SessionStore` 를 여기서 조립한다.
이유는 §5.3 — "라우터당 스토어 하나"를 사람이 기억할 것이 아니라 구조로 만들기 위해서다.

| 모드 | SPI | nodeId |
|---|---|---|
| `SINGLE_NODE` | 미주입 SPI 는 in-memory 로 채운다 | 불필요 |
| `DISTRIBUTED` | **전부 주입 필수**, 하나라도 빠지면 `build()` 가 실패 | 필수 |

기본값:

| 튜너블 | 기본 | 비고 |
|---|---|---|
| `idleTtl` | 10분 | §6.3 B |
| `maxCachedSessions` | 1000 | 힙 경계 (§5.2) |
| `lockLease` / `lockExtendInterval` | 30초 / 10초 | 정확히 두 틱의 여유 (§7.4) |
| `statusHeartbeatInterval` | 10초 | 위와 수치만 같고 무관 |
| `holderLossSweepInterval` | 15초 | §6.3 D |
| `idempotencyPrimaryTtl` / `secondaryTtl` | 24시간 / 30초 | §9.2 |
| `idempotencyForwardTtl` | 5분 | 전달된 future 의 마감 (§9.1) |
| `releaseInterruptTimeout` | 5초 | §6.3 A, §7.7 |

내부 컴포넌트(`LeaseRenewer`, `InProcessEventPublisher`, `LiveSessionCache`)는 라우터가 소유하며 외부에서
주입하지 않는다.

---

## 12. 운영 계측

`SessionMetrics` 는 프레임워크 중립이고 모든 메서드가 no-op 기본 구현을 갖는다 — 필요한 것만 구현하면 되고,
계측을 요구하지 않은 애플리케이션에는 `NOOP` 이 들어간다.

| 훅 | 답하는 질문 |
|---|---|
| lock acquire latency (성공/거절) | 선출이 느려지고 있나 |
| 캐시 hit-rate | idle TTL 이 너무 짧은가 |
| evict 빈도 | `maxCachedSessions` 가 부족한가 |
| lease extend 실패 | 노드가 리스를 잃고 있나 |
| submit outcome (`EXECUTED_LOCALLY` / `FORWARDED`) | 노드들이 inbox 에 쌓이고 있나 |
| holder loss | 프로덕션에서 회복 경로가 실제로 발동했나 |
| forward 재-초인종 | 아무도 수집하지 않은 메시지를 기다리는 호출자가 있나 — **재시도 횟수이지 회복 횟수가 아니다.** 같은 노드의 lock acquire 성공과 상관 지어야 실제 인수 여부가 나온다 |

호출 규약이 셋 있다 — 훅은 **이벤트를 낸 스레드에서** 돌고(턴 실행기, 스케줄러, 시그널 디스패처),
**블록하면 안 되며**, **던지면 안 된다.** 라우터가 방어적으로 감싸긴 하지만(`safeMetric`), 계측 장애가 세션
수명을 깨는 것은 어느 쪽에도 이롭지 않다.

---

## 13. 기각한 대안

**세션 소유권 고정(sticky).** LB 계층에 배포 제약을 떠넘긴다. 그리고 sticky 는 노드가 죽는 순간 아무것도
해 주지 않으므로, 어차피 이 문서의 회복 경로를 전부 만들어야 한다 — 그러고 나면 sticky 는 순수한 추가
제약이다.

**턴 단위 리스.** 원래 그랬고 §3.2 에서 버렸다. 매 턴 반납은 같은 노드가 연속 턴을 돌 때조차 경합을
만들고, hand-off 를 리스 만료 대기로 바꾼다.

**타입 객체를 시그널에 싣기.** 처음 릴레이는 `Map.of("event", typedEvent)` 를 발행하고 수신 측이
`instanceof AgentExecutionEvent` 로 받았다. in-process 버스에서만 참이고 실제 버스에서는 크로스 노드
`events()` 가 **아무것도 전달하지 않았다.** 평평한 원시값 코덱 3종이 그 자리를 대신한다(§5.5.2).

**홀더 유실 시 `EVICT` broadcast.** §6.3 D 에 적은 대로, 죽은 턴 하나 때문에 살아 있는 세션을 헐었다.

**전달된 턴에 `NOT_HOLDER` 응답.** §7.3 — 호출자가 할 수 있는 일이 대기뿐이면 프로토콜이 대신 기다린다.

**`SubmitOutcome` 재사용.** §9.1 — 축이 다르다.

---

## 14. 남은 것

| 항목 | 지금 상태 |
|---|---|
| 이벤트 replay | 라이브만. 진행 중 재시도로 붙은 구독자는 이미 흘러간 프레임을 못 본다 — 최종 결과는 `IdempotencyStore` 조회로 안전하다. UI 가 progressive rendering 을 요구하면 그때 버퍼 정책을 정한다 |
| 같은 세션의 다중 구독자 (collaboration) | `events()` 는 멀티 구독자지만, 여러 사용자가 한 세션을 동시에 보는 UX 는 웹 어댑터 설계 사안 |
| 분산 서브에이전트 | 범위 밖 (§7.6) |
| `YIELD` shim 제거 | 클러스터 전 노드가 이 kind 를 이해하게 되면 레거시 `INTERRUPT(SESSION_RELEASED)` 동반 발행을 걷어낸다 |
| forward TTL 의 폴링 fallback | 동작하지만 rail 보다 느리다. 다만 이제 이 타이머가 고아 메시지 재-초인종도 겸하므로(§6.3 D), 간격을 늘리면 회복 시간이 같이 늘어난다 |
| 홀더 일치 조건부 `markDone` | `markDone` 은 네 백엔드 모두 **키만 보고** 매치한다. 도달 가능한 오염 경로(넘겨받기에 진 노드가 남의 항목을 덮어쓰는 것)는 라우터가 그 경우 쓰지 않는 것으로 닫았고 테스트가 지킨다 — 하지만 그것은 규율이지 저장소가 강제하는 계약이 아니다. 넘겨받기와 쓰기 사이에 스위퍼가 예약을 리셋하면 그 사이에 낀 쓰기는 여전히 막을 것이 없다. 저장소 쪽에서 닫으려면 SPI 에 `markDone(key, holderId, result)` 를 추가하고 백엔드 4종의 조건을 조여야 하며, 그것은 이 항목의 크기가 아니라 이 항목이 미뤄진 이유다 |
| 기다리는 노드까지 죽은 고아 메시지 | inbox 에 남지만 그것을 기다리는 forward 가 어디에도 없어 세션의 다음 제출까지 대기한다. 자동으로 주우려면 `SessionInbox` 에 "대기 메시지가 있는 세션" 스캔을 추가하고 백엔드 4종에 인덱스를 붙여야 한다 — 운영에서 실제로 관측되면 그때 한다 |

### 하지 말 것

- **`LiveSession.close()` 나 `SessionRouter.close()` 에서 `AgentRuntime` 을 닫지 말 것.** 같은 agent 의 다른
  세션이 아직 쓰고 있다. 닫는 것은 부트스트랩의 일이다(§2).
- **`status()` 를 제어 게이트로 쓰지 말 것.** best-effort 관측이다(§5.1).
- **라우터를 우회해 `SessionRecordStore.delete(id)` 를 부르지 말 것.** 진행 중 턴과 경합한다(§7.7).
- **한 JVM 의 두 라우터가 `SessionStore` 를 공유하지 말 것.** 펜싱이 무너진다(§5.3).
- **`onExtendFailed` 에서 블록하지 말 것.** 그 스레드를 공유하는 모든 세션이 리스를 잃는다(§7.4).
- **크로스 노드 페이로드에 타입 객체를 싣지 말 것.** 실제 버스에서 조용한 no-op 이 된다(§2).
- **`IdempotencyTouchSlot` 의 키를 스케줄 시점에 캡처하지 말 것.** 살아 있는 턴이 두 번 실행된다(§7.4).

---

## 부록. 참조 파일 지도

| 개념 | 파일 |
|---|---|
| 공개 계약 | [`SessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SessionRouter.java) |
| 구현 | [`DefaultSessionRouter`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/DefaultSessionRouter.java) |
| 조립 | [`SessionRouterBuilder`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/builder/SessionRouterBuilder.java), [`DeploymentMode`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/DeploymentMode.java) |
| 제출·결과 | [`SubmitRequest`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SubmitRequest.java), [`SubmitDisposition`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/SubmitDisposition.java) |
| 핸들 캐시 (§5.2) | [`LiveSessionCache`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionCache.java), [`LiveSessionOpener`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionOpener.java) |
| 리스 (§7.4) | [`HeldLease`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/HeldLease.java), [`LeaseRenewer`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/LeaseRenewer.java), [`IdempotencyTouchSlot`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/IdempotencyTouchSlot.java) |
| 이벤트 (§5.5) | [`SessionEventRelay`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/SessionEventRelay.java), [`InProcessEventPublisher`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/InProcessEventPublisher.java), [`EventSink`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/EventSink.java) |
| 페이로드 코덱 (§5.5.2) | [`AgentExecutionEventPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/AgentExecutionEventPayload.java), [`TurnResultPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/TurnResultPayload.java), [`StatusSnapshotPayload`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/StatusSnapshotPayload.java), [`PayloadValues`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/PayloadValues.java) |
| 홀더 유실 (§6.3 D) | [`HolderLossSweeper`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/HolderLossSweeper.java), [`LostTurnAnnouncer`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/LostTurnAnnouncer.java) |
| 관측 | [`ClusterSessionStatus`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/ClusterSessionStatus.java), [`StatusProjection`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/internal/StatusProjection.java), [`SessionMetrics`](../../../modules/aimon-session-routing/src/main/java/at/aimon/session/routing/metrics/SessionMetrics.java) |
| SPI (core) | [`SessionStore`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionStore.java), [`SessionInbox`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/inbox/SessionInbox.java), [`SessionSignal`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/signal/SessionSignal.java), [`IdempotencyStore`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/idempotency/IdempotencyStore.java) |
| 세션 계약 | [`LiveSession`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/LiveSession.java), [`TranscriptManager`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptManager.java) |

---

## 관련 문서

- [`session-model.md`](session-model.md) — `SessionRecord` / `LiveSession` 두 수명과 영속 모델
- [`spi-extraction.md`](spi-extraction.md) — SPI 가 core 로 내려오고 이 모듈이 라우팅만 남은 경위
- [`backends.md`](backends.md) — Redis / MongoDB / PostgreSQL 구현
- [`../agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md) — `AgentRuntime` 소유권
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — `InterruptCoordinator` 와 도구별 중단 동작
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임
- [`../../features/session/agent-session-guide.md`](../../features/session/agent-session-guide.md) — `LiveSession` API 와 이벤트 스트리밍
</content>
</invoke>
