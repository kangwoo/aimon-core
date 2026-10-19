# 세션 저장소 — SPI 경계, 레코드 저장소 계약, 쓰기 펜스

**Status**: IMPLEMENTED — `aimon-core` `at.aimon.core.agent.session.{store,inbox,signal,idempotency,exception}`, 계약 스위트는
`aimon-session-testkit`(발행하지 않음). 남은 것은 [마지막 절](#남은-것).

세션의 영속 상태가 어떤 SPI 뒤에 있는지, 그 SPI 가 무엇을 보장하는지, 레코드 쓰기가 어떻게 리스에 묶이는지를 정의한다.
레코드가 **무엇을** 담는지는 [`session-model.md`](session-model.md), 전사 안의 로그는 [`session-log.md`](session-log.md),
이 SPI 를 조립해 쓰는 라우터는 [`routing.md`](routing.md), 세 백엔드의 구현은 [`backends.md`](backends.md) 가 정본이다.

---

## 1. SPI 경계 — 계약은 core, 정책은 밖

백엔드가 **구현하는 계약**은 전부 `aimon-core` 에 있고, 멀티 노드 **정책 구현**(라우터 · 핸들 캐시 · 리스 갱신기 · 홀더 손실
스위퍼)은 `aimon-session-routing` 에 있다.

| SPI | 패키지 | 하는 일 | 정본 |
|-----|--------|---------|------|
| `SessionLeaseStore` | `agent.session.store` | 어느 노드가 세션을 쥐는지 — 홀더 선출 + 펜싱 토큰 | 이 문서 §3 |
| `SessionRecordStore` | `agent.session.store` | 전사 + side field | 이 문서 §2 |
| `SessionLogSegmentStore` | `agent.session.store` | 봉인된 로그 구간 | [`session-log.md`](session-log.md) §6 |
| `SessionInbox` | `agent.session.inbox` | 세션의 크로스 노드 우편함 | [`inbox.md`](inbox.md) |
| `SessionSignalBus` | `agent.session.signal` | `SessionSignal` 팬아웃 | [`routing.md`](routing.md) §5 |
| `IdempotencyStore` | `agent.session.idempotency` | 클라이언트 키 기반 at-most-once 제출 | [`idempotency.md`](idempotency.md) |
| `BackgroundTaskStore` | `subagent.task` | 백그라운드 서브에이전트 태스크 메타데이터 | 서브에이전트 문서 |

예외는 `agent.session.exception` 에 있다. 백엔드 셋(Postgres · MongoDB · Redis)이 위 일곱을 모두 구현한다.

### 1.1 배치 규칙

- **core 에 `spi` 패키지를 만들지 않는다.** 패키지 규약은 `at.aimon.core.<domain>` = 계약, `.impl` = 구현이다. 계약을 `spi`
  로 한 번 더 싸는 것은 이 트리의 관용구가 아니다
- **in-memory 구현은 인터페이스 옆에 둔다.** `.impl` 로 보내면 ArchUnit 의 `.impl` 차단 규칙에 걸려 백엔드 테스트가
  in-memory 백엔드를 조립할 수 없다
- **`Stored*` 는 `store` 에 둔다.** `StoredAgentExecutionResult` 의 소비자는 idempotency 쪽(백엔드 코덱)과 signal 쪽(라우팅의
  `TurnResultPayload`)에 걸쳐 있다. 어느 한쪽에 넣으면 반대쪽이 남의 패키지를 들여다보므로 중립 규칙으로 정한다

### 1.2 이 선이 옳은 근거

- **SPI 는 라우팅을 참조하지 않는다.** 계약 쪽에서 나가는 참조는 자기들끼리뿐이므로 순환 없이 떼어낼 수 있다
- **core 의 의존 표면이 넓어지지 않는다.** 계약이 쓰는 외부 라이브러리는 Jackson 과 SLF4J 뿐이고 둘 다 core 가 이미 갖는다.
  caffeine 은 `LiveSessionCache` 전용이라 라우팅에 남는다

### 1.3 모듈 의존

```
aimon-core (SPI + LiveSession 계약)  ◀── aimon-session-routing  ◀── aimon-bootstrap / aimon-spring-boot-starter
        ▲                                        ▲
        └── aimon-session-{redis,mongodb,postgres}  (main: core 만 / test: routing)
```

백엔드 모듈의 **main** 은 `aimon-core` 만 본다. 라우팅은 `testImplementation` 으로만 끌어온다 — 각 백엔드의 2노드 하네스와
멀티 노드 통합 테스트가 진짜 라우터를 돌린다. 이 비대칭이 설계다: main 은 계약만 알고, 테스트는 그 계약이 실제 라우터
밑에서 성립하는지를 본다. 순환은 없다(routing 은 백엔드를 모른다). `aimon-bootstrap` / `aimon-spring-boot-starter` 는
`AimonStack` 이 `SessionRouter` 를 반환하므로 `api(project(":aimon-session-routing"))` 이다.

### 1.4 알면서 받아들인 결합

`PackageDependencyArchitectureTest` 는 `at.aimon.core.agent.session..` 이 의존할 수 있는 패키지를 화이트리스트로 정한다.
`SessionRecordCodec` 은 `at.aimon.core.subagent.task.codec` 의 `SessionSnapshotCodec` / `JsonSessionSnapshotCodec` 을 쓰므로
`PKG_SUBAGENT_TASK_CODEC` 를 좁게 허용한다. 두 코덱은 `SessionSnapshot` 을 직렬화하는 순수 데이터 코덱이라 동작이 따라오지
않고, 이 파일은 같은 모양의 예외를 주석과 함께 여럿 갖고 있다.

이 허용은 결합을 **양방향**으로 만든다. `subagent.task.codec` 은 이미 `agent.session` 을 참조한다(`SessionSnapshot`,
`SessionId`). 순환 검사의 슬라이스는 `at.aimon.core.agent.(*)..` 만 매칭하므로 그 트리 밖을 경유하는 간선은 잡히지 않는다.
두 방향 모두 값 타입만 나르기 때문에 받아들이지만, 바로 그 사실이 코덱을 `agent.session.transcript` 로 옮기는 일을 남은
과제로 두어야 하는 이유다. `SessionSnapshotCodec` 이 `subagent.task.codec` 에 있는 것은 첫 소비자가 서브에이전트 재개였기
때문이고, 지금은 소비자가 넷이다 — 패키지 이름은 첫 소비자의 기록이지 제약이 아니다.

---

## 2. 레코드 저장소 계약 — `SessionRecordStore`

### 2.1 "레코드 전체를 쓴다" 는 연산은 없다

레코드에는 쓰는 주체가 여럿이고, 각자 다른 필드를 소유한다. 어느 주체도 전부를 갖지 않는다.

| 쓰기 primitive | 소유하는 필드 | 쓰는 주체 |
|----------------|--------------|-----------|
| `mergeFromSnapshot(snapshot)` | 메시지(전사) | 체크포인트 writer · 턴 끝 저장 |
| `provision(id, agentRef)` / `provision(id, agentRef, owner)` | 레코드의 존재, 바인딩, 소유자 — 각각 **비어 있을 때만** 채운다 | claim · 레코드를 프로비저닝하는 드레인 패스 |
| `setTotalsAndBudgetOverride(id, totals, override)` | 누적 통계 · 예산 오버라이드 쌍 (절대값) | 라이브 세션 |
| `incrementCompactionFailureCount` / `resetCompactionFailureCount` | 압축 실패 카운터 | 압축 가드 |

그래서 모든 쓰기는 자기가 소유하지 않은 것을 보존하는 **부분 쓰기**다. 레코드 전체 쓰기는 다른 주체의 필드를 먼저 읽어
그대로 돌려줘야 하므로 모든 쓰기를 남의 필드에 대한 read-modify-write 로 만들고, 위 primitive 가 닫은 lost update 를 다시
연다.

- `mergeFromSnapshot` 은 side field 를 되살린 뒤 메시지만 덮어쓴다. 스냅샷은 side field 를 표현하지 않으므로, 머지가 고를
  "두 번째 값" 이 처음부터 없다([`session-model.md`](session-model.md) §4.4). 레코드가 없으면 기본값 side field 로 만든다
- `provision` 은 "없으면 만들고, 바인딩이 없으면 묶고, 지금 무엇이 있는지 알려 준다" 를 한 원자 연산으로 한다. 바인딩과
  소유자 모두 first-wins 다
- 읽기는 언제나 `SessionRecordView` 를 돌려주고 mutable `SessionRecord` 를 내주지 않는다

### 2.2 `default` 정책 — 쓰기는 없고, 읽기는 있다

**쓰기 primitive 에는 `default` 구현이 없다.** default 는 load-mutate-store, 즉 비원자로만 쓸 수 있고, 뻔한 멤버만 구현하고
나머지를 상속한 백엔드는 조용히 지는 동작을 얻는다. abstract 로 두면 컴파일러가 "가진 원자 primitive 로 이것을 구현하라" 를
대신 묻는다.

예외는 소유자를 받는 `provision(id, agentRef, owner)` 하나다. 그 default 는 아무것도 쓰지 않는다 — 소유자가 `null` 이면 원자
2인자 `provision` 에 위임하고, 소유자가 있으면 `UnsupportedOperationException` 을 던진다. 그래서 트리 밖 저장소는 계속
컴파일되고, 호스트가 실제로 소유자를 저장하라고 할 때까지 그대로 동작하며, 그때는 소유자를 조용히 버리지 않고 첫 제출에서
크게 실패한다. 출시된 저장소 다섯(in-memory · Postgres · Mongo · Redis · 부트스트랩의 `LateBoundFencedRecordStore`)은 모두
재정의한다. `SessionStore.claim(id, agentRef, owner, holderId, lease)` 도 같은 이유로 같은 모양이다.

**읽기에는 default 가 있어도 된다.** 읽기 default 는 느릴 수는 있어도 틀리지 않는다(§2.3). 쓰기 쪽 규칙의 근거가 비원자성이므로
읽기에는 적용되지 않는다.

### 2.3 소유자의 쓰기와 읽기

소유자의 의미는 [`session-model.md`](session-model.md) §5 가 정본이다. 여기서는 SPI 표현만 다룬다.

**쓰기.** 소유자는 `provision` 의 한 열이다. 바인딩과 같은 문장 안에서 "비어 있으면 채운다". 따로 `setOwner` primitive 를 두지
않는다 — 고정된 쓰기 주체 집합에 다섯째 주체를 더하고, 자기 펜스 검사가 필요하며, 레코드는 프로비저닝됐는데 소유자는 아직
없는 채로 턴이 도는 창을 연다.

**읽기 — `loadOwner(id)`.** 소유자 확인은 호스트의 핫패스(예: 샌드박스의 매 바인딩)에 있고, `load` 는 전사까지 디코드하므로
비용이 세션 길이에 비례한다. 그래서 소유자만 읽는 경로를 둔다.

```java
default Optional<Principal> loadOwner(SessionId sessionId) {
    Objects.requireNonNull(sessionId, "Session id cannot be null");
    return load(sessionId).flatMap(SessionRecordView::getOwner);
}
```

- **계약.** `load(id).flatMap(getOwner)` 와 같은 값을 돌려준다. empty 는 "레코드 없음" **또는** "소유자 없음" 이다 — 호출자가
  둘을 가를 필요가 없고(가르려면 `exists(id)`), 둘 다 호스트의 폴백으로 떨어지는 것이 지금의 동작이다. null id 는 NPE.
  `load` 처럼 펜스가 없고 한 번의 시점 읽기다
- **출시된 저장소는 전부 재정의한다** — 백엔드마다 소유자 하나만 읽는다(단일 열 · projection · `HGET`). default 는 정답이지만
  예전 비용을 낸다
- **감싸는 저장소는 상속하지 않고 전달한다.** `DefaultSessionStore` 의 펜스 뷰와 `LateBoundFencedRecordStore` 는 `load` 를
  넘기는 것과 같이 펜스 없이 아래 저장소로 넘긴다. 상속하면 백엔드의 좁은 읽기가 전체 읽기로 바뀐다. 반사적 테스트가
  "모든 `default` 가 데코레이터에 선언되어 있다(명시한 예외 목록 제외)" 를 지킨다
- **전사가 깨져도 답한다** — 의도된 차이다. `loadOwner` 는 전사를 읽지 않으므로 `load` 가 던지는 레코드에서도 소유자를 돌려준다
- **소유자 자신이 깨졌으면 empty 가 아니라 `SessionRecordStoreException` 이다.** empty 로 답하면 깨진 소유자가 "소유자 없음" 으로
  통과해 호스트의 폴백으로 떨어진다

### 2.4 "없음" 은 별도 상태가 아니다

저장된 문서·행·해시에 **필드가 없는 것**은, in-memory 저장소가 그 필드를 가진 레코드에 대해 보고하는 **기본값과 똑같이**
디코드된다 — 전사 없음, totals 없음, override 없음, 소유자 없음, 카운트 0. 그래서 claim 이 프로비저닝만 해 두고 아무도 쓰지
않은 레코드 위에서 도는 세션의 첫 턴이 모든 저장소에서 같게 동작한다. "아직 안 씀" 을 별도 상태로 두면 그 첫 턴에서만 갈린다.

### 2.5 인코딩

전사는 `SessionRecordCodec` 이 만드는 **문자열 하나**로 저장한다. 백엔드가 각자 JSON 을 만들지 않고 코덱을 공유하며, 중립
문서는 `StoredSessionRecord` 다. 문자열인 것은 강제다 — 도구 입력은 모델이 만든 임의의 맵이라 저장소의 네이티브 문서 형식이
받지 못하는 키와 값이 들어온다([`backends.md`](backends.md) §2.1). side field 는 부분 원자 쓰기가 가능해야 하므로 네이티브
타입으로 남는다. 소유자는 코덱의 `encodeOwner` 형식(`{"version":1,"type":…,"id":…,"displayName":…}`)이다.

---

## 3. 리스 SPI — `SessionLeaseStore`

리스는 세션 하나에 홀더 노드 하나와 단조 증가하는 **펜싱 토큰**을 준다. `tryAcquire` · `extend` · `release` · `findHolder` 가
있다. 리스의 **수명과 갱신 정책**(세션 수명 리스, 30초/10초, 전용 스케줄러)은 라우터가 정하며 [`routing.md`](routing.md) §4.3 이
정본이다.

**`extend` 는 리스의 생존을 확인하지 않는다.** 토큰만 비교한다 — in-memory 는 토큰 동등성만 보고, Postgres 의 extend 문에는
`lease_expires_at` 술어가 없고, Mongo 의 필터에도 시간 술어가 없다. 셋 다 이미 만료된 리스를 토큰만 맞으면 되살린다. Redis 만
거부하는데, 그것도 키가 이미 사라졌기 때문이지 의도한 검사가 아니다. **`findHolder` 는 만료와 명시적 release 를 둘 다 "쥔
사람 없음" 으로 보는, 백엔드 전체에서 의미가 균일한 유일한 술어다.** 그래서 쓰기 펜스는 `findHolder` 로 재증명한다(§5).

`extend` 를 만료 인식(fail-closed)으로 바꾸는 것이 옳은 방향이다. 다만 갱신 tick 을 전용 스레드로 분리하기 **전에** 넣으면 긴
GC 나 포화된 스케줄러 때문에 큐에 밀린 tick 이 아무도 훔치지 않은 리스에 손실 신호를 낸다. 쓰기 펜스는 `findHolder` 를 쓰므로
그 수정에 의존하지 않는다.

---

## 4. `SessionStore` — 리스와 레코드를 한 문으로

`SessionStore` 는 `SessionRecordStore` 와 `SessionLeaseStore` 를 감싼 합성물이다. 둘은 서로 다른 백엔드일 수 있다.

```
claim(sessionId, agentRef, owner, holderId, lease)
  1. 리스 선출         — 이 노드가 홀더가 될 수 있는가?            진 노드는 여기서 끝난다 (HeldElsewhere)
  2. 레코드 프로비저닝   — provision(id, agentRef): 없으면 만들고, 바인딩이 없으면 묶고, 지금의 바인딩을 돌려준다
  3. 바인딩 검증        — 다른 agent 에 묶여 있으면 리스를 놓고 AgentConflict
  4. 소유자 기록        — owner 가 있고 레코드에 소유자가 없을 때만 provision(id, agentRef, owner)
```

**선출에서 진 노드는 레코드를 건드리지 않는다.** 그래서 이 연산은 분산 트랜잭션 없이 순서만으로 원자적이다. 두 백엔드에 걸친
2PC 가 필요 없고, 두 백엔드가 다른 시스템이어도 half-committed 상태가 생기지 않는다. 순서를 뒤집으면(레코드 먼저) 경쟁에서
진 노드가 이미 레코드를 만든 뒤가 되고, 그때부터 "누가 만들었나" 를 추적해야 한다.

**소유자는 바인딩을 확인한 뒤 두 번째 쓰기로 기록한다.** 거절된 claim 이 소유권을 얻으면 안 되기 때문이다. 두 단계는 트랜잭션
없이도 안전하다 — 리스를 한 문장 앞에서 땄으므로 이 노드가 유일한 쓰기 주체다. 추가 왕복은 실제로 소유자를 채우는 claim 에만
생긴다. 조건을 백엔드 upsert 에 접어 넣는 안은 네 백엔드에 제각각의 조건부 쓰기가 필요해 기각했다.

### 4.1 노드 스코프

담는 두 저장소는 application-scoped 이지만 `SessionStore` 자신은 **노드(라우터)당 하나**다. 이 노드가 쥔 리스의 로컬
레지스트리를 소유하기 때문이다 — 그래야 `records()` 가 펜싱 토큰을 ReAct 호출 사슬 전체에 실어 나르지 않고도 "이 노드가
들고 있는 리스" 를 찾는다. 한 JVM 에 라우터를 둘 두면 같은 두 백엔드 위에 스토어도 둘 만들어야 한다. 공유하면 두 라우터의
리스 레지스트리가 섞여 펜싱이 무너진다. 라우터 빌더가 두 저장소를 받아 스토어를 직접 조립하는 것은 이 규칙을 구조로 만들기
위해서다([`routing.md`](routing.md) §13.2).

`claim()` 은 라우터 전용 진입점이다. CLI 는 라우터를 거치지 않고 핸들을 직접 조립하므로 리스를 잡지 않는다. 그렇다고
in-memory 백엔드의 `claim()` 을 no-op 로 만들면 안 된다 — 단일 JVM 두 가상 노드 하네스가 in-memory 에서 진짜 만료·펜싱
의미론을 요구한다.

---

## 5. 쓰기 펜스

### 5.1 재증명

`SessionStore.records()` 와 `segments(raw)` 는 각 변경을 위임하기 직전에 **리스 권한을 리스 저장소에 다시 묻는다**. 이때
`extend` 가 아니라 `findHolder` 를 쓴다(§3). 효과는 "리스를 잃은 홀더의 **다음** 쓰기가 실패한다" 이다 — 턴 도중 리스를 잃은
노드가 계속 메시지를 append 하던 정상 상태의 실패 모드가 사라진다. 거절된 쓰기는 `SessionNotHeldException` 이다.

### 5.2 펜스의 종류 — `SessionFence`

어떤 펜스를 거는지는 스택의 모양이 정한다.

| 스택 | 펜스 | 이유 |
|---|---|---|
| 분산 모드 | `HOLDER_ONLY` — 이 노드가 쥔 세션만 | 라이브 세션은 모두 라우터가 claim 한 뒤 열린다. 쥐지 않은 세션에 대한 쓰기는 조정 결함이거나 리스를 잃은 노드다 |
| 단일 노드 + durable 리스 저장소 | `UNLESS_HELD_ELSEWHERE` — 다른 노드가 쥔 세션만 거절 | 라우터 밖에서 연 라이브 세션(CLI)은 리스를 쥐지 않으므로 `HOLDER_ONLY` 면 모든 쓰기가 거절된다. durable 리스 저장소가 가능하게 만드는 경우 — 두 번째 노드가 이 노드의 세션을 쥐고, 이 노드의 늦은 쓰기가 그 뒤에 착지하는 것 — 만 막는다 |
| 단일 노드 + 기본 리스 저장소 | 없음 | 리스 저장소가 이 프로세스의 것이므로 다른 홀더가 생길 수 없다 |

라우터의 세션 삭제는 처음부터 `HOLDER_ONLY` 뷰를 쓴다. 라우터 opener 가 만드는 라이브 세션의 레코드 쓰기(누계 · 예산 · 영속
rewind)와 transcript manager 의 저장 · GC · `/clear` 삭제도 같은 뷰로 간다.

**늦은 결합.** 펜스 뷰를 가진 `SessionStore` 는 `SessionRouterBuilder.build()` 안에서 transcript manager 보다 **뒤에**
만들어진다. 그래서 부트스트랩은 manager 에 `LateBoundFencedRecordStore` · `LateBoundFencedSegmentStore` 를 주고, 라우터가 생긴 뒤
`SessionRouter.fencedRecordStore(fence)` · `fencedSegmentStore(fence)` 에 묶는다. 묶이기 전의 쓰기·삭제는 raw 로 떨어지지 않고
실패한다 — 그때는 턴이 돌 수 없으므로 도달하면 배선 결함이다. 읽기는 묶기 전에도 raw 로 간다.

**거절된 쓰기는 턴을 깨지 않는다.** 턴 끝 저장의 거절은 WARN 한 줄로 남는다 — 잃는 것은 리스를 잃으면서 이미 잃은 그 턴의
끝이다. 거절된 저장 뒤에는 GC 도 돌지 않는다. 체크포인트 거절과 펜스가 거절한 GC 삭제는 DEBUG 다 — 결함이 아니라 펜스가
일하는 모습이다. 던지는 `save` 경로는 예외를 그대로 올린다.

### 5.3 남는 창

재증명과 위임된 쓰기 사이의 sub-millisecond 인터리빙은 닫히지 않는다. `UNLESS_HELD_ELSEWHERE` 는 그에 더해 **아무도 쥐지 않은
순간의** 늦은 쓰기를 통과시킨다 — 바로 뒤에 claim 한 노드가 그 쓰기보다 먼저 레코드를 읽을 수 있다. 이 창을 닫으려면 레코드
쓰기 자체가 토큰을 실어야 한다. 즉 **레코드 백엔드의 펜싱 compare-and-set** 이 필요하고, 그것은 이 설계 밖이다. 그러므로
쓰기 펜스를 "리스 만료·split-brain 의 완전한 해법" 으로 인용하지 않는다 — 정상 상태 보장이다.

쓰기 경로를 `SessionStore` 뒤 하나로 모으는 것은 sole-writer 규칙(`SessionRecordSoleWriterArchitectureTest`)이 지킨다
([`session-model.md`](session-model.md) §8). 그 규칙은 펜스를 우회하는 경로를 막을 뿐 위의 창을 닫지 않는다.

**기각한 대안 — 레코드에 `fencingToken` side field 를 두고 저장소에 `putIfFenced` 를 얹는 안.** `SessionRecord.copyOf(view)` 가
상태를 뷰의 getter 만으로 재구성하므로 그 토큰은 매 load/save 왕복마다 조용히 사라진다. 살리려면 뷰와 모든 뷰 소비자를
넓혀야 하는데 그것은 공표된 SPI 변경이다.

---

## 6. 계약 스위트

모든 `SessionRecordStore` — in-memory, 펜스 뷰(`UNLESS_HELD_ELSEWHERE`), `LateBoundFencedRecordStore`, 세 백엔드 — 는 하나의
`AbstractSessionRecordStoreContractTest` 를 돈다. 백엔드마다 같은 케이스를 따로 두면 새 계약 메서드나 새 백엔드가 한 곳에서만
검사되어도 빌드가 초록으로 남는다. 멀티 노드 스위트는 이 자리를 채우지 못한다 — 그 하네스는 두 노드가 in-memory 레코드
저장소 하나를 공유하므로 백엔드의 레코드 저장소에 닿지 않는다.

- **스위트냐 백엔드냐.** 단언이 SPI 만 쓰고 그 성질이 모든 저장소에 성립하면 스위트에 둔다. 원시 백엔드 접근이 필요하거나
  한 백엔드의 인코딩을 서술하면 백엔드 테스트에 남긴다. 한 백엔드가 자기 사정으로 시작한 케이스라도("`$` 는 Mongo 필드 경로",
  "`:` 는 Redis 키 구분자", "jsonb 는 NUL 을 거부") 단언이 "값이 그대로 돌아온다" 면 스위트로 간다 — 다른 백엔드도 그 보장을 받는다
- **훅.** `store()`(비운 백엔드 위의 저장소), `peerStore()`(같은 백엔드 위의 다른 인스턴스 — 다른 노드), `corruptTranscript` ·
  `corruptOwner`(기본값은 JUnit assumption 으로 **건너뜀** — 인코딩된 형식이 없는 저장소는 손상시킬 수 없다), `supportsClear()`
  (펜스 뷰는 `clear()` 를 거부한다)
- **보지 못하는 것.** 데코레이터의 `loadOwner` 가 전달하는지 default 로 떨어지는지는 답이 같아 스위트가 볼 수 없다. 데코레이터별
  반사적 테스트가 지킨다(§2.3)
- **발행하지 않는다.** 스위트는 발행하지 않는 `aimon-session-testkit` 에 있다. 지금 모든 `SessionRecordStore` 가 이 저장소 안에
  있고, Maven Central 발행은 철회할 수 없으며 `api` 의존은 모두 공개 API 가 된다. testkit 은 그 주체 중 하나가 저장소 밖에 있을
  때만 발행한다(`aimon-memory-testkit` 의 선례와 그 규칙). 스위트 클래스는 `aimon-core` 타입과 JUnit · AssertJ 만 쓰므로, 저장소
  밖 구현이 생기면 그대로 발행 모듈로 옮길 수 있다

인박스와 세그먼트 저장소의 계약 스위트도 같은 모듈에 같은 모양으로 있다
([`inbox.md`](inbox.md), [`session-log.md`](session-log.md) §6).

---

## 7. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| SPI 의 자리 | 계약은 core, 정책은 `aimon-session-routing` | **라우팅까지 core 로** — core 가 배포 토폴로지(`DeploymentMode`, `HolderLossSweeper`, 리스 주기)를 알게 되고, caffeine 이 따라오며, 라우터를 쓰지 않는 CLI · 단일 프로세스 임베딩이 비용을 낸다 |
| 모듈 축 | 기능축(`aimon-session-*`) | **기술축 통합(mongodb/postgres 모듈에 memory·session 을 함께)** — `aimon-filesystem-gridfs`·`aimon-scheduling-quartz` 까지 일관되게 적용할 수 없고, Postgres 세션 + Mongo 메모리 같은 혼합을 표현할 수 없으며, 합칠 중복이 실제로 없다. 좌표 하나로 깔고 싶다는 편익은 BOM(`aimon-bom`)이 준다 |
| 레코드 쓰기 | 주체별 부분 쓰기 primitive | **레코드 전체 쓰기** — 남의 필드에 대한 read-modify-write 가 되어 lost update 를 연다 |
| 쓰기 primitive 의 default | 없음(abstract) | **load-mutate-store default** — 비원자. 상속한 백엔드가 조용히 진다 |
| 소유자를 받는 `provision` | 위임 또는 실패하는 default | **유일한 abstract 로 교체** — 트리 밖 저장소가 전부 컴파일되지 않는다. **별도 `setOwner`** — 쓰기 주체가 하나 늘고 소유자 없는 창을 연다 |
| 소유자 읽기 | `loadOwner` default + 전 저장소 재정의 | **별도 `SessionOwnerResolver` SPI** — 저장소가 이미 소유자를 갖고, 호스트가 배선할 것이 하나 는다. **abstract** — 트리 밖 저장소가 깨진다. **던지는 default** — 폴백이 정답인데 읽기만 깨뜨린다. **`agentRef`+소유자 헤더 읽기** — 호출자가 없다. **lazy `load`** — `load` 의 디코드 실패 계약이 바뀐다. **`SessionStore` 에도 추가** — 호출자가 `SessionRecordStore` 를 배선한다 |
| `claim` 의 순서 | 선출 → 프로비저닝 → 바인딩 검증 → 소유자 | **레코드 먼저** — 진 노드가 레코드를 만든 뒤가 된다 |
| 펜싱 재증명 | `findHolder` | **`extend`** — 세 백엔드에서 만료된 리스를 되살린다 |
| 남는 창 | 받아들이고 적어 둔다 | **`fencingToken` side field + `putIfFenced`** — 왕복마다 토큰이 사라진다. 닫는 길은 펜싱 CAS 레코드 백엔드 |
| 계약 스위트 | 공유 스위트, 발행하지 않음 | **백엔드별 테스트** — 표류한다. **지금 발행** — 저장소 밖 구현이 없고 철회할 수 없다. **`java-test-fixtures`** — 발행 플러그인 아래에서 구성이 실패한다 |

---

## 하지 말 것

- **쓰기 primitive 에 비원자 `default` 를 두지 않는다**
- **감싸는 저장소가 `SessionRecordStore` 의 `default` 를 상속하게 두지 않는다.** 전달한다 — 상속하면 소유자 쓰기가 실패하거나
  좁은 읽기가 전체 읽기가 된다
- **깨진 소유자를 "소유자 없음" 으로 답하지 않는다**
- **한 JVM 의 두 라우터가 `SessionStore` 를 공유하지 않는다**
- **in-memory `claim()` 을 no-op 로 만들지 않는다**
- **쓰기 펜스를 `extend` 로 재증명하지 않는다**
- **쓰기 펜스를 split-brain 의 완전한 해법으로 인용하지 않는다**
- **백엔드의 `testImplementation(routing)` 을 main 으로 올리지 않는다.** 비대칭이 설계다
- **옛 FQCN 언급을 일괄 치환하지 않는다.** javadoc 의 *"Formerly `at.aimon.session.base…`"* 같은 역사 기록은 이미 없는 패키지를
  가리키며, 고치면 어떤 클래스도 살았던 적 없는 주소를 인용하게 된다. 바꾸기 전에 살아 있는 참조와 역사 기록을 먼저 가른다

---

## 남은 것

| 항목 | 지금 |
|------|------|
| `SessionSnapshotCodec` 재배치 | `subagent.task.codec` 에 있다. `agent.session.transcript` 로 옮기면 §1.4 의 허용이 사라진다. 공개 패키지 이동이라 별도 과제다 |
| `StoredAgentExecutionResult` 의 자리 | `store` 에 있다. 소비자 비중만 보면 `idempotency` 가 더 많다 |
| core 의 Jackson 스코프 | `implementation`. `SessionRecordCodec` 의 public 시그니처가 `JsonNode`/`ObjectNode` 를 노출하지만, core 의 다른 클래스들도 같은 배치다 |
| 쓰기 펜스의 남는 창 | §5.3. 펜싱 CAS 레코드 백엔드가 필요하다 |
| 계약 스위트 발행 | 백로그 [SO-9](../../backlog/session-owner-open-items.md) |

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 레코드 저장소 | [`SessionRecordStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordStore.java), [`InMemorySessionRecordStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/InMemorySessionRecordStore.java), [`SessionRecordCodec.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordCodec.java), [`StoredSessionRecord.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/StoredSessionRecord.java) |
| 리스 | [`SessionLeaseStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionLeaseStore.java), [`InMemorySessionLeaseStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/InMemorySessionLeaseStore.java) |
| 합성·펜스 | [`SessionStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionStore.java), [`DefaultSessionStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/DefaultSessionStore.java), [`SessionFence.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionFence.java) |
| 늦은 결합 | [`LateBoundFencedRecordStore.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/LateBoundFencedRecordStore.java) |
| 패키지 규칙 | [`PackageDependencyArchitectureTest.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java) |
| 계약 스위트 | [`AbstractSessionRecordStoreContractTest.java`](../../../modules/aimon-session-testkit/src/main/java/at/aimon/session/testkit/AbstractSessionRecordStoreContractTest.java) |

---

## 관련 문서

- [`session-model.md`](session-model.md) — 레코드가 무엇을 담는가, 소유자의 의미
- [`session-log.md`](session-log.md) — 세그먼트 저장소와 GC 가 쓰는 펜스 뷰
- [`routing.md`](routing.md) — 리스의 수명과 갱신, claim 을 부르는 자리
- [`backends.md`](backends.md) — 세 백엔드의 구현
- [`../../project/api-stability.md`](../../project/api-stability.md) — `0.x` 에서 공개 시그니처를 바꿀 때의 약속
- [`../../../.claude/rules/architecture.md`](../../../.claude/rules/architecture.md) — 모듈 의존 규칙
