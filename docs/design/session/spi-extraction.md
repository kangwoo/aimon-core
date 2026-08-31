# 세션 SPI 코어 이관 — 계약은 core, 라우팅은 밖

> Status: **IMPLEMENTED** — 세션 SPI 18개 파일이 `aimon-core` 의 `agent.session.{store,inbox,signal,
> idempotency,exception}` 으로 내려왔고, 백엔드 3모듈의 main 은 `aimon-core` 만 본다. 라우팅은 밖에
> 남았으며 모듈은 `aimon-session-base` → **`aimon-session-routing`** 으로 개명되었다. 동작 변경은 없다 —
> wire key · Redis 채널 · Mongo 컬렉션 · Postgres DDL 이 전부 그대로다. 남은 것은 §8.
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.session.{store,inbox,signal,idempotency,exception}` ·
> `aimon-session-routing` — 라우팅 25파일 · `aimon-session-{mongodb,postgres,redis}` — 의존 스코프 ·
> `aimon-bootstrap` · `aimon-spring-boot-starter` — 조립.

---

## 1. 문제 — 같은 범주의 계약이 두 모듈에 갈려 있었다

`MongoSessionLeaseStore` 와 `MongoSessionInbox` 는 같은 모듈, 같은 백엔드, 같은 성격의 어댑터다.
그런데 전자가 구현하는 계약은 `aimon-core` 에 있었고, 후자가 구현하는 계약은 `aimon-session-base` 에
있었다.

```
aimon-core        : SessionRecordStore, SessionLeaseStore, SessionLease, InMemorySessionLeaseStore
aimon-session-base: SessionInbox, SessionSignalBus, IdempotencyStore, SessionRecordCodec, StoredSessionRecord
                    ↑ 같은 범주인데 한 모듈 위에 있었다
```

이 선은 설계가 아니라 **이력**이었다. session-first 개편에서 리스 SPI 만 core 로 내려왔고, 그 사실이
`SessionLeaseStore` 와 `SessionLeaseException` 의 javadoc(*"it moved with the SPI it belongs to"*)에 그대로
적혀 있다. 같은 논리가 나머지 SPI 에도 적용되는데 그때 함께 오지 않았을 뿐이다.

대가는 백엔드가 냈다. `aimon-session-{mongodb,postgres,redis}` 는 셋 다 `implementation` 으로 라우팅
모듈 전체를 끌어왔지만 **main 소스에서 라우팅 코드를 한 줄도 쓰지 않았다.** 계약 하나를 얻으려고
라우터·캐시·리스 갱신기·홀더 손실 스위퍼가 든 모듈에 의존하고 있었다.

---

## 2. 자른 선 — SPI 는 옮기고 라우팅은 남긴다

`aimon-session-base` 는 이미 성격이 다른 두 덩어리로 갈려 있었다: 백엔드가 **구현하는 계약**(SPI, 18파일)과
멀티노드 **정책 구현**(라우팅, 25파일). 계약만 내려보냈다.

| core 목적지 | 들어간 타입 |
|---|---|
| `agent.session.store` | `StoredSessionRecord`, `StoredAgentExecutionResult`, `SessionRecordCodec` |
| `agent.session.inbox` | `SessionInbox`, `InboundMessage`, `InboundMessageId`, `InMemorySessionInbox` |
| `agent.session.signal` | `SessionSignalBus`, `SessionSignal`, `InMemorySignalBus` |
| `agent.session.idempotency` | `IdempotencyStore`, `IdempotencyEntry`, `PutResult`, `InMemoryIdempotencyStore` |
| `agent.session.exception` | `SessionInboxException`, `SessionSignalBusException`, `IdempotencyStoreException`, `IdempotencyConflictException` |

배치는 접두어가 아니라 **역할**로 정했고, 세 가지 판단이 그 뒤에 있다.

1. **core 에 `spi` 패키지를 만들지 않는다.** core 에는 그 이름의 패키지가 하나도 없고, 패키지 규약은
   `at.aimon.core.<domain>` = 계약, `.impl` = 구현이다. 계약을 `spi` 로 한 번 더 싸는 것은 이 트리의
   관용구가 아니다.
2. **in-memory 구현은 인터페이스 옆에 둔다.** `.impl` 로 보내면 ArchUnit 의 `.impl` 차단 규칙에 걸려
   백엔드 테스트가 in-memory 백엔드를 조립할 수 없다. 선례도 이미 있다 — `InMemorySessionLeaseStore` 와
   `InMemorySessionRecordStore` 가 자기 인터페이스와 같은 패키지에 있다.
3. **`Stored*` 는 `store` 에 둔다.** `StoredAgentExecutionResult` 만 유일하게 논쟁적이었다 — 소비자가
   idempotency 쪽(백엔드 3개의 `IdempotencyEntryCodec`)과 signal 쪽(`TurnResultPayload`, 라우팅에 잔류)
   양쪽이다. 어느 한쪽에 넣으면 반대쪽이 남의 패키지를 들여다보므로 중립 규칙으로 잘랐다.

라우팅 쪽에 남은 것: `SessionRouter`, `DefaultSessionRouter`, `LiveSessionCache`, `LiveSessionOpener`,
`LeaseRenewer`, `HolderLossSweeper`, `LostTurnAnnouncer`, `SessionEventRelay`, `DeploymentMode`,
`ClusterSessionStatus`, `SessionRouterBuilder`, `SessionMetrics`.

---

## 3. 이 선이 옳은 근거 — 두 개의 측정

**(a) SPI 는 라우팅을 참조하지 않았다.** 이동 대상 안에서 나가는 참조는 전부 자기들끼리였고 라우팅 쪽으로
나가는 것은 0건이었다 — **폐포(closed) 상태**이므로 순환 없이 떼어낼 수 있었다.

**(b) core 의 의존성 표면이 넓어지지 않는다.** 이동 대상이 쓰는 외부 라이브러리는 Jackson 과 SLF4J 뿐이고
둘 다 core 가 이미 갖고 있다. **build 파일에 한 줄도 추가되지 않았다.** `caffeine` 은 `LiveSessionCache`
전용이라 라우팅에 남는다.

(b) 는 §7.1(전체 통합 기각)의 핵심 근거이기도 하다 — SPI 만 옮기면 신규 의존이 0인데, 전체를 옮기면
caffeine 이 따라온다. 반대 방향으로도 정리가 하나 생겼다: `SessionRecordCodec` 이 라우팅 모듈의 **유일한**
Jackson 사용처였으므로, 이동 후 그 모듈에서 Jackson 번들이 통째로 빠졌다.

---

## 4. 알면서 받아들인 결합 — ArchUnit carve-out 1건

`PackageDependencyArchitectureTest` 는 `at.aimon.core.agent.session..` 이 의존할 수 있는 패키지를
화이트리스트로 못박는다. 그런데 `SessionRecordCodec` 은 `at.aimon.core.subagent.task.codec` 의
`SessionSnapshotCodec` / `JsonSessionSnapshotCodec` 을 쓴다 — 화이트리스트에 없는 패키지다.

| 옵션 | 내용 | 판단 |
|---|---|---|
| **A** | `PKG_SUBAGENT_TASK_CODEC` narrow carve-out 추가 | **채택** — 이 파일은 이미 동종 carve-out 을 여럿 갖고 있고(각각 주석으로 근거를 적는 확립된 관용구다), 두 코덱은 `SessionSnapshot` 을 직렬화하는 순수 데이터 코덱이라 동작이 따라오지 않는다 |
| B | 두 코덱을 `agent.session.transcript` 로 재배치 | 후속 과제 — 공개 패키지 이동이라 "순수 이동" 이어야 할 이 작업에 섞이면 안 됐다 |

IMPORTANT: **이 carve-out 은 결합을 양방향으로 만든다.** `subagent.task.codec` 은 이미 `agent.session` 을
참조하고 있으므로(`SessionSnapshot`, `SessionId`) 방향은 원래 한쪽이 아니었다. 다만 **잡히지 않을 뿐이다** —
순환 검사의 슬라이스는 `at.aimon.core.agent.(*)..` 만 매칭하므로 그 트리 밖 클래스를 경유하는 간선은
보이지 않는다. 그리고 지금까지 `subagent..` 를 참조하던 agent 하위 패키지는 `agent.impl.*` 과 `agent.orca.*`
뿐이었고 **둘 다 이 규칙의 적용 대상에서 이미 제외**돼 있었다. 즉 `agent.session` 은 규칙이 실제로 감시하는
패키지 중 이 간선을 갖는 **첫 번째**다.

그럼에도 A 를 채택한 것은 두 방향 모두 값 타입만 실어 나르기 때문이고, 이 사실이 오히려 **옵션 B 를 후속
과제로 반드시 남겨야 하는 이유**가 된다. 같은 설명이 `PKG_SUBAGENT_TASK_CODEC` 선언부 주석에도 있다.

`SessionSnapshotCodec` 이 `subagent.task.codec` 에 있는 것은 첫 소비자가 서브에이전트 재개용
`SessionSnapshotStore` 였기 때문이고, 지금은 소비자가 넷(subagent, workflow `StepOutcomeCodec`,
`OrcaAgentRuntimeFactory`, 이 SPI)이라 이름이 자리를 잘못 말한다. 패키지 이름은 **첫 소비자를 기록한
것이지 제약이 아니다.**

---

## 5. 백엔드 의존의 비대칭 — 의도된 모양

백엔드 3모듈의 라우팅 의존은 **사라지지 않고 test 스코프로 내려갔다.**

```kotlin
// modules/aimon-session-{mongodb,postgres,redis}/build.gradle.kts
testImplementation(project(":aimon-session-routing"))
```

각 백엔드의 2노드 하네스와 멀티노드 통합 테스트가 라우팅 타입(`SessionRouter`, `DefaultSessionRouter`,
`LiveSessionOpener`, `SubmitRequest`, `SubmitDisposition`, `ClusterSessionStatus`, `DeploymentMode`)을 실제로
쓰기 때문이다 — 백엔드당 하네스 1개 + 통합 테스트 1개, 여섯 파일이다.

이것은 결함이 아니라 옳은 모양이다: **main 은 계약만 알고, 테스트는 그 계약이 실제 라우터 밑에서 성립하는지를
본다.** 순환도 없다 (routing → core, 백엔드 test → routing, routing 은 백엔드를 모른다).

`aimon-bootstrap` / `aimon-spring-boot-starter` 는 `api(project(":aimon-session-routing"))` 을 **유지**한다.
`AimonStack` 이 여전히 `SessionRouter` 를 반환하므로 facade 재수출 규칙이 그대로 적용된다.

---

## 6. 개명 — `base` 는 내용물을 말하지 않는 이름이었다

모듈은 이 작업 마지막 단계에서 `aimon-session-base` → **`aimon-session-routing`**, 패키지는
`at.aimon.session.base` → `at.aimon.session.routing` 으로 바뀌었다. 처음에는 "짧은 간격의 두 번째 개명은
검색 가능성을 해친다" 는 이유로 미루자고 적혀 있었으나, 두 전제가 모두 틀렸다.

| 전제 | 실제 |
|---|---|
| 좌표 변경은 major 를 기다려야 한다 | 이 프로젝트는 0.x 이고 semver 0.x 는 minor 범프에 breaking change 를 허용한다. 기다릴 major 가 사실상 지금이며, 미루면 비용이 1.0 이후로 **커진다** |
| 두 번째 개명이 첫 번째를 무의미하게 만든다 | 방향이 반대다. `aimon-session-web` → `aimon-session-base` 는 *"web 전용이 아니다"* 라는 **부정**만 말한 이름이라 내용물을 설명한 적이 없다. `routing` 은 처음으로 내용물을 말하는 이름이므로 두 번째 개명이 첫 번째의 미완성을 끝낸다 |

**순서가 중요했다.** 개명을 SPI 추출보다 먼저 했다면 옛 패키지를 참조하는 158개 파일이 개명으로 한 번,
그중 상당수가 이동으로 또 한 번 수정됐을 것이다. 추출을 먼저 하면 개명이 닿는 것은 살아남은 라우팅 절반과
그 소비자뿐이다(실측 96파일). 그리고 그 순서라야 **개명 시점에 모듈 내용물이 실제로 라우팅뿐**이므로 새
이름이 하루도 거짓말을 하지 않는다.

남는 비용은 하나이고 실재한다 — `at.aimon.core:aimon-session-base` 좌표가 사라진다. 완화는 CHANGELOG 의
좌표 매핑표로 한다. Maven relocation POM 은 쓰지 않았다: 0.x 에서 좌표 하나를 위해 게시 파이프라인에 영구
특례를 만드는 비용이 이득보다 크고, 이전 개명(`aimon-session-web`) 때도 남기지 않았다.

### 옛 이름 전부가 개명 대상은 아니다

javadoc 의 `at.aimon.session.base.*` 언급 중 실제로 고쳐야 했던 것은 **살아 있는 참조 1건**뿐이었다.
나머지는 *"Formerly `at.aimon.session.base.spi.ConversationLock`"* 형태의 **역사 기록**이고, 그 FQCN 은 이미
삭제된 패키지를 가리킨다. `session.routing` 으로 고치면 **어떤 클래스도 살았던 적 없는 주소**를 인용하게
되므로 그대로 둔다. 옛 이름을 일괄 치환하기 전에 **살아 있는 참조와 역사 기록을 먼저 가른다** — 개명
regex 도 같은 이유로 접미사 화이트리스트를 썼다.

---

## 7. 기각한 대안

### 7.1 라우팅까지 통째로 core 로

기각. 세 이유가 각각 독립적으로 충분하다.

1. **core 가 배포 토폴로지를 알게 된다.** `DeploymentMode`, `ClusterSessionStatus`, `HolderLossSweeper`,
   `LeaseRenewer` 가 따라온다. core 는 "세션 레코드를 어디에 저장하는가"의 계약을 갖는 계층이지
   "노드가 몇 개이고 리스를 몇 초마다 갱신하는가"를 아는 계층이 아니다.
2. **의존성 표면이 넓어진다.** caffeine 이 core 로 따라온다(§3).
3. **쓰지 않는 소비자가 지불한다.** `aimon-cli` 는 라우팅 타입 참조가 0건이다 — CLI 는 라우터를 거치지
   않고 라이브 세션을 직접 조립하므로 `claim()` 경로가 애초에 없다. 단일 프로세스 임베딩도 마찬가지다.

### 7.2 `aimon-memory-*` 와 `aimon-session-*` 을 기술축(mongodb / postgres)으로 통합

기각. 모듈 축을 기능축에서 기술축으로 뒤집는 변경인데 이 트리에서는 일관되게 적용할 수가 없다.

- **일관성이 성립하지 않는다.** `aimon-filesystem-gridfs` 도 MongoDB 이고 `aimon-scheduling-quartz` 도
  JDBC 다. gridfs 까지 넣으면 VFS 만 쓰려는 소비자가 세션 SPI 를 끌고 오고, 안 넣으면 "mongodb 모듈에
  memory·session 은 있는데 filesystem 은 없다"는 더 나쁜 규칙이 남는다.
- **믹스매치를 표현할 수 없다.** Postgres 세션 + Mongo 메모리는 있을 법한 구성인데, 통합 후엔 각각이
  반대쪽 절반을 함께 끌고 온다.
- **결합이 늘어난다.** `aimon-memory-mongodb` 는 지금 core 에만 의존한다. 통합하면 메모리만 쓰는 소비자가
  inbox / idempotency / signal-bus 계약을 함께 받는다.
- **합칠 중복이 실제로 없다.** 두 postgres 모듈의 Java 소스에 `CREATE TABLE` 0건, 두 mongo 모듈의
  `internal/DocumentKeys` 는 서로 다른 컬렉션의 키 집합으로 **이름이 겹치는 것이 0건**이다. 실제 공유는
  build 파일의 드라이버 선언 1줄뿐이다.
- **게시 좌표가 사라진다.** 넷 다 publishable 이라 통합은 다운스트림 breaking change 다.

그리고 §1 의 문제(SPI 가 두 모듈에 갈려 있다)는 이 통합으로 **전혀 해결되지 않는다** — 통합 후에도
`MongoSessionInbox` 는 여전히 다른 모듈의 계약을 구현한다.

### 7.3 모듈 개수가 부담이라면 — aggregator / BOM

기술축 통합이 노리는 실제 편익("Mongo 로 다 깔고 싶은데 좌표를 여러 개 적어야 한다")은 소스 트리를 합치지
않고 얻을 수 있다. `java-platform` BOM 또는 `api` 의존만 선언하는 aggregator 모듈이면 소비자는 좌표 하나로
끝내면서 의존성 그래프의 분리는 유지된다. 아키텍처 규칙이 이미 facade/aggregator 예외와 BOM 을 인정한다.

---

## 8. 남은 것

| 항목 | 현재 | 비고 |
|------|------|------|
| `SessionSnapshotCodec` 재배치 (§4 옵션 B) | `subagent.task.codec` 에 잔류 | 옮기면 carve-out 이 사라진다. 공개 패키지 이동이라 별도 과제 |
| `StoredAgentExecutionResult` 의 자리 | `store` | 소비자 비중만 보면 `idempotency` 가 더 많다. `Stored*` → `store` 는 중립적이지만 인위적이기도 하다 |
| core 의 Jackson 스코프 | `implementation` | `SessionRecordCodec` 의 public 시그니처가 `JsonNode`/`ObjectNode` 를 노출한다. 이동으로 **새로 생긴 누출은 아니다**(core 의 다른 클래스들이 이미 같은 배치) — 부수효과로 조용히 `api` 승격하지 않고 그대로 두었다 |
| 좌표 `aimon-session-base` | 존재하지 않음 | CHANGELOG 에 breaking change 로 명시 |

**하지 말 것** — 옛 FQCN 언급을 일괄 치환하지 말 것(§6 마지막). 백엔드의 `testImplementation(routing)` 을
"의존이 남아 있으니 main 으로 올리자" 로 되돌리지 말 것 — 비대칭이 설계다(§5).

---

## 관련 문서

- [`session-model.md`](session-model.md) — `SessionRecord` : `LiveSession` 모델과 SPI 의 계약
- [`routing.md`](routing.md) — core 밖에 남은 라우팅 계층
- [`backends.md`](backends.md) — 이 SPI 를 구현하는 Mongo/Postgres/Redis 어댑터
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명·소유권 규칙
- [`../../overview/glossary.md`](../../overview/glossary.md) — 세션 계층 용어 사전
- [`../../../.claude/rules/architecture.md`](../../../.claude/rules/architecture.md) — 모듈 의존 규칙
- [`../../../.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md) — 멀티 인스턴스 설계 규칙
