# 웹 세션 매니저 — 배포 · 운영 가이드

`SessionRouter` 는 `at.aimon.core.agent.session.LiveSession` 을 감싸, 수평 확장한 웹 배포가 필요로 하는 노드 간 관심사를 더한다 — 세션 단위 분산 락, 노드 간 신호 팬아웃, 호출한 노드가 락 홀더가 아닐 때의 우편함 인계, 멱등성, 홀더 유실 복구, 그리고 우아한 종료.

이 가이드는 그것을 배포하고 운영하는 방법을 다룬다. 설계 근거는 `docs/design/session/routing.md` 에 있다.

> 이 가이드가 따르는 스코프 모델은 [`docs/overview/scope-model.md`](../../overview/scope-model.md) 에 정의되어 있다 — `AgentRuntime` 은 agent-scoped 로 세션들이 공유하고, `SessionRecord` 하나는 수명 동안 0..N 개의 `LiveSession` 핸들이 서빙할 수 있다.

---

## 1. 배포 토폴로지

### 단일 노드 (`DeploymentMode.SINGLE_NODE`)

애플리케이션이 프로세스 하나로 돌 때 쓴다. SPI 넷이 모두 in-memory 구현으로 기본 설정된다:

```java
SessionRouter router = SessionRouter.builder()
    .sessionFactory(sessionFactory)
    .sessionRecordStore(sessionRecordStore)
    .build();
```

대부분의 테스트가 쓰는 경로이고, 개발할 때의 경험과 같다.

`sessionFactory(LiveSessionFactory)` 와 `sessionOpener(LiveSessionOpener)` 중 **정확히 하나**를 설정해야 한다 — `build()` 는 둘 다 준 것도, 둘 다 안 준 것도 거절한다. 상태 없는 열기에는 factory 를 쓰고, 호출자 도메인 속성(테넌트 id, 조직 단위, …)이 `OpenAttributes` 로 세션 단위 컨텍스트에 닿아야 할 때는 opener 를 쓴다.

### 다중 노드 (`DeploymentMode.DISTRIBUTED`)

애플리케이션이 sticky routing 없는 로드 밸런서 뒤에서 인스턴스 2개 이상으로 돌 때 쓴다. **SPI 넷을 모두 명시적으로 배선해야 한다**. 그러지 않으면 빌더가 `build()` 에서 곧바로 실패한다 — in-memory 기본값은 두 노드가 같은 세션을 병렬로 처리하게 두면서 아무 말도 하지 않기 때문이다.

```java
SessionRouter router = SessionRouter.builder()
    .sessionFactory(sessionFactory)
    .sessionRecordStore(sessionRecordStore)
    .mode(DeploymentMode.DISTRIBUTED)
    .nodeId(System.getenv("HOSTNAME"))                        // 프로세스마다 유일
    .sessionLeaseStore(new RedisSessionLeaseStore(redisConn))
    .signalBus(new RedisPubSubSignalBus(redisConn, redisPubSubConn))
    .sessionInbox(new RedisSessionInbox(redisConn))
    .idempotencyStore(new RedisIdempotencyStore(redisConn))
    .metrics(new MicrometerSessionMetrics(meterRegistry))
    .build();
```

`RedisPubSubSignalBus` 는 Lettuce 연결 두 개를 받는다 — 발행용 일반 `StatefulRedisConnection` 과 리스너용 `StatefulRedisPubSubConnection`. 둘 다 호출자 소유로 남고, 버스는 자기 리스너를 등록하고 떼어 낼 뿐이다.

`nodeId` 는 프로세스마다 유일해야 한다. 락 홀더 정체성, 멱등성 항목, 신호 버스의 출처 필터에 박힌다 — 겹치면 라우터가 피어를 자기 자신으로 착각하고, 자기 신호를 기다리며 교착에 빠진다.

### 세션 단위 스킬 승인 (선택)

세션 단위로 스킬 승인을 캐시한다면(`SessionApprovalStore`), 에이전트 런타임에 넘긴 것과 **같은 인스턴스**를
라우터에도 넘긴다:

```java
SessionRouter.builder()
    // ...
    .sessionApprovalStore(sessionApprovalStore)   // OrcaAgentRuntimeFactory#withSessionApprovalStore 에 넘긴 그 인스턴스
    .build();
```

라우터는 `releaseSession` / `deleteSession`, 그리고 피어 노드의 `EVICT` 수신 시 해당 세션의 승인을
버린다. 안 넘기면 훅이 no-op 이 되어, 삭제된 세션의 승인이 프로세스가 살아 있는 동안 캐시에 남고 같은
`SessionId` 를 재사용하는 세션이 **묻지 않고** 그것을 물려받는다. idle-TTL 축출에는 발화하지 않는다 —
세션 자체는 그대로 살아 있으므로 사용자가 돌아왔을 때 다시 묻지 않는 것이 맞다.

인메모리 구현은 노드 로컬이다. 피어의 `EVICT` 를 듣기 때문에 삭제는 클러스터 전체에 전파되지만, 승인 자체는
노드를 넘어 공유되지 않으므로 세션이 노드를 옮기면 한 번 더 묻는다.

**`AimonStack` 을 쓴다면 위 두 곳에 손으로 넘기지 않는다.** 스펙에 한 번 얹으면 런타임 팩토리와 라우터
양쪽에 같은 인스턴스가 간다 — 두 자리에 서로 다른 것을 주는 실수가 아예 생기지 않는다.

```java
AimonStackSpec.builder()
    .skillApproval(SkillApprovalSpec.denyAll()
            .withSessionApprovalStore(sessionApprovalStore)   // 라우터에도 이 인스턴스가 간다
            .withAgentApprovalStore(agentApprovalStore)
            .withPendingTurnRegistry(pendingTurnRegistry))
    .build();
```

Spring Boot 스타터에서는 `SessionApprovalStore` · `AgentApprovalStore` · `PendingTurnRegistry` 빈을
정의하기만 하면 된다. 셋 다 **빌려온 것**이라 스택은 어느 것도 닫지 않는다.

셋 중 하나만 공유하는 배포는 기동 시 `distributed-approvals` degradation 으로 **남은 것만** 이름이 불린다.
특히 레지스트리만 공유하고 승인 저장소를 노드 로컬로 두면, 다른 노드에서 중단된 턴을 찾아내서 그 결정을
모르는 노드로 풀어 준다.

포크는 별도로 정리할 것이 없다 — 지울 자기 몫의 승인이 애초에 없기 때문이다. 서브에이전트 포크는
`SessionId` 가 **아예 없고**(툴 컨텍스트에 `SESSION_ID` 대신 `EXECUTION_ID` 가 실린다) 승인은 자기를 띄운
세션의 id (`invokingSessionId` — 와이어 키는 동결되어 `"invokingConversationId"` 그대로다) 로만 조회되므로,
그 세션 하나를 `invalidate` 하면 그 세션이 위임했던 실행들의 도달 범위도 함께 사라진다.

---

## 2. 필요한 SPI 구현 (DISTRIBUTED 모드)

| SPI | 목적 | 참조 구현 |
|---|---|---|
| `SessionLeaseStore` | 펜싱 토큰을 가진 세션 단위 분산 리스 | `RedisSessionLeaseStore` (SET NX PX + Lua release) |
| `SessionSignalBus` | 노드 간 신호 팬아웃 (INTERRUPT, YIELD, EVICT, MESSAGE_ENQUEUED, EVENT, TURN_RESULT, STATUS) | `RedisPubSubSignalBus` |
| `SessionInbox` | 홀더가 아닌 노드의 제출을 받는 노드 간 우편함 | `RedisSessionInbox` (Redis Streams, `XADD`) |
| `IdempotencyStore` | 클러스터 전역 멱등성 키 + 홀더 유실 감지 | `RedisIdempotencyStore` |

넷 모두 `aimon-core` 에 패키지 하나씩 선언되어 있다 — `at.aimon.core.agent.session.{store, signal, inbox, idempotency}`. 그래서 백엔드 모듈은 `aimon-core` 하나에만 의존해 이것들을 구현한다. `aimon-session-routing` 은 이 SPI 의 *소비자*이지 주인이 아니고, 백엔드는 테스트 밖에서 그것에 의존하지 않는다. Redis 없이 배포할 때를 위해 `aimon-session-mongodb` 와 `aimon-session-postgres` 도 같은 넷(`MongoSessionLeaseStore` / `PostgresSessionLeaseStore` 등)을 제공한다.

백엔드를 직접 구현할 때, 계약은 각 SPI 인터페이스의 javadoc 이 적고, 관찰 가능한 동작은 참조 구현의 통합 테스트(`aimon-session-redis` 의 `Redis*IntegrationTest`, 그리고 `Mongo*` / `Postgres*` 의 대응물)가 고정한다.

**봉인된 세션 로그 세그먼트**는 다섯 번째 저장소이고 선택이다. 버전 2 세션 로그에서는 LLM 뷰가 더는 원문으로 보여 주지 않는 구간이 레코드에서 `SessionLogSegmentStore`(`at.aimon.core.agent.session.store`)로 옮겨지고, 레코드의 manifest 가 그것을 가리킨다. Spring Boot 스타터에서는 레코드 저장소 옆에 빈으로 노출하고, `AimonStackSpec` 에서는 `SessionSpec.segmentStore(...)` 로 넘긴다. 어느 쪽이든 스택은 같은 인스턴스를 transcript manager 와 라우터에 건네므로, 세션을 지우면 레코드 뒤에 그 세그먼트도 지워진다. 손으로 조립할 때는 transcript manager 에 (`SessionLogStorage` 를 통해), 그리고 `SessionRouterBuilder.sessionLogSegmentStore(...)` 에 넘긴다. 세그먼트 저장소 없이 레코드 저장소만 주면 아무것도 봉인되지 않는다 — 버전 2 쓰기 형식에서는 레코드가 모든 항목을 영영 들고 있게 되고, 스택은 `session-log-sealing` degradation 을 남긴다. 레코드와 같은 데이터베이스에 둔다 — 세그먼트는 레코드에서 옮겨 낸 데이터다: `MongoSessionLogSegmentStore`(컬렉션 `session_log_segments`, `init.js` 참조), `PostgresSessionLogSegmentStore`(테이블 `session_log_segment`, `V1__init.sql` 뒤에 `V2__session_log_segment.sql` 로 적용), 또는 `RedisSessionLogSegmentStore`(standalone 연결 또는 Redis Cluster 연결 — 클러스터면 스캔이 모든 마스터를 돈다). 삭제는 펜스 뷰인 `SessionStore.segments(...)` 로 간다. 쓰기는 펜스하지 않는다 — 어떤 manifest 도 가리키지 않는 세그먼트는 읽히지 않기 때문이다. 스택은 transcript manager 의 **레코드 쓰기**(턴 종료 저장, 체크포인트)와 **세그먼트 삭제**(턴 종료 GC, `/clear`)도 라우터의 펜스 뷰(`SessionRouter.fencedRecordStore(fence)` · `fencedSegmentStore(fence)`)로 보낸다. `DeploymentMode.DISTRIBUTED` 에서는 `SessionFence.HOLDER_ONLY` 다 — 리스를 잃은 노드의 늦은 저장은 새 홀더의 레코드를 덮어쓰지 못하고, 새 홀더의 manifest 가 가리키는 세그먼트를 지우지도 못한다. 거절된 턴 종료 저장은 WARN 한 줄로 남고 턴은 깨지지 않는다 — 잃은 것은 리스를 잃으면서 이미 잃은 그 턴의 끝이다. 그래서 이 모드에서는 모든 라이브 세션을 라우터로 열어야 한다. 라우터 밖에서 연 세션은 리스가 없어 저장이 모두 거절된다. 리스 저장소를 준 단일 노드 모드는 `SessionFence.UNLESS_HELD_ELSEWHERE` 다 — 다른 노드가 쥔 세션만 거절하고, 아무도 쥐지 않은 세션(라우터 밖에서 연 CLI 의 세션)은 전처럼 통과한다. 리스 저장소가 기본값인 단일 노드 모드는 펜스 없이 원시 저장소로 간다. 설계: `docs/design/session/session-log.md` §5, §12.3.

**고아 세그먼트 스윕.** GC 는 세션을 저장할 때만 돌므로, 아무도 다시 열지 않는 세션은 고아를 그대로 둔다 — rewind 가 잘라 낸 세그먼트, 실패한 `/clear` 삭제, 저장되지 못한 봉인, 세그먼트 삭제에 실패한 삭제된 레코드. 그것을 주우려면 저장소 단위 스윕을 켠다: 스타터에서는 `aimon.session.segment-sweep-interval`(예: `1h`), `AimonStackSpec` 에서는 `SessionSpec.segmentSweepInterval(...)`. 설정하지 않으면 꺼져 있다. 세그먼트는 grace — `aimon.session.segment-sweep-grace` / `segmentSweepGrace(...)`, 기본 24시간 — 보다 오래됐고, 세그먼트 목록을 읽은 **뒤에** 읽은 세션 레코드가 그것을 가리키지 않을 때만 지워진다. 스윕은 세션 리스를 쥐지 않고 원시 저장소로 지우는데, 세션 단위 GC 가 안전한 것과 같은 두 이유로 안전하다. 모든 노드에서 한꺼번에 켜도 안전하고, 비용도 곱해지지 않는다: 리스 저장소가 있으면 노드들은 **스윕 리스**(예약 id `aimon:segment-sweep`)를 번갈아 잡아, 클러스터 전체가 간격마다 한 번만 저장소를 훑는다. 리스는 간격 하나만큼 잡고 회차가 끝나도 놓지 않으며, 쥔 노드가 죽으면 리스가 만료된 뒤 다음 노드가 이어받는다. 스윕에는 세그먼트 저장소가 필요하고, 그것 없이 스윕을 설정하면 기동 시 거절한다. 스윕이 페이지를 넘기는 SPI 는 `SessionLogSegmentStore.scanSessions(...)` 이고, 직접 만든 백엔드는 그것을 구현한다(계약은 `AbstractSessionLogSegmentStoreContractTest` 에 있다).

---

## 3. 운영 조정값

기본값은 모두 `SessionRouterBuilder` 에 있다. 권장 출발점:

| 조정값 | 기본 | 권장 |
|---|---|---|
| `idleTtl` | 10분 | LLM 세션 자원의 수명(MCP 연결, 지식 저장소)에 맞춘다. 낮추면 cold start 가 늘고, 높이면 유휴 힙이 는다. |
| `maxCachedSessions` | 1000 | 힙 상한. 최악의 `idleTtl × peak-rps` 가 OOM 을 내지 않게 잡는다. |
| `lockLease` | 30초 | GC 멈춤이나 느린 도구 호출에 리스를 잃지 않을 만큼 길게. |
| `lockExtendInterval` | 10초 | 관례상 `lockLease / 3`. 턴이 LLM 을 기다리며 막혀 있어도 갱신기는 돈다. |
| `holderLossSweepInterval` | 15초 | 맞교환: 짧으면 노드 크래시에서 빨리 복구하고, 길면 Redis 부하가 준다. |
| `idempotencyPrimaryTtl` | 24시간 | 성공한 턴의 결과를 중복 제출에 되돌려 줄 수 있는 기간. |
| `idempotencySecondaryTtl` | 30초 | 이보다 오래된 `IN_FLIGHT` 항목은 스위퍼가 복구할 수 있다. `lockLease` 보다 커야 한다. |
| `releaseInterruptTimeout` | 5초 | 캐시가 축출을 강제하기 전에 진행 중인 턴이 `releaseSession()` 을 따르기를 기다리는 최대 시간. |

**불변식:** `lockExtendInterval < lockLease`(`build()` 에서 강제)와 `idempotencySecondaryTtl > lockLease`. 어기면 건강한 노드에서 가짜 홀더 유실 복구가 일어난다.

---

## 4. 메트릭 연동

`SessionMetrics` 는 기본이 no-op 인 SPI 다. 프로세스마다 구현 하나를 배선하면 라우터가 다음 자리에서 콜백을 부른다:

| 훅 | 어디서 발화하나 | 이 메트릭으로 볼 것 |
|---|---|---|
| `onLockAcquireSucceeded(Duration)` | 리스를 딴 모든 `SessionStore.claim()` 뒤 | 락 지연의 p50/p95/p99 |
| `onLockAcquireRejected(Duration)` | held-elsewhere 로 답한 모든 `SessionStore.claim()` 뒤(→ 우편함) | "노드 간 인계가 얼마나 자주 일어나나?" |
| `onCacheHit` / `onCacheMiss` | `LiveSessionCache.ensureOpen` 안 | 캐시 적중률 ⇒ cold start 비용 |
| `onCacheEviction(reason)` | Caffeine removal listener. 이유: `IDLE`, `LRU`, `EXPLICIT_RELEASE`, `OTHER` | 메모리 압박(`LRU` 급증) 대 자연스러운 교체(`IDLE`) |
| `onLeaseExtendSucceeded` / `onLeaseExtendFailed` | `LeaseRenewer` 의 매 틱 | `Failed` 비율이 0 이 아니면 리스 조정이 틀렸거나 피어가 너무 공격적이다 |
| `onSubmitOutcome(SubmitDisposition.Kind)` | 모든 `submit()` 뒤 | 세션 핫스팟별 `EXECUTED_LOCALLY` 대 `FORWARDED` 비율 |
| `onHolderLossRecovered` | CAS 를 이긴 뒤의 홀더 유실 스위퍼 | 클러스터 전체에서 복구한 크래시 수. **정상 상태에서는 0 에 가까워야 한다.** |
| `onForwardDoorbellRerung` | forward poll 의 매 틱 중, 기다리는 메시지가 아직 수거되지 않은 틱 | 어떤 노드도 우편함에서 꺼내지 않은 메시지를 누군가 기다리고 있다. **복구가 아니라 재시도다** — 평범한 대기열도 이것을 올리고, 인계가 성공해야 멈춘다. 그러니 줄지 않는 비율에 알람을 건다. |
| `onReservationTakeOverRefused` | drain 회차에서, 수거한 메시지의 키가 이 노드의 것이 아니라고 `acquireHolder` 가 답할 때 | 클러스터가 두 번 실행한 요청: 저장소는 키가 `DONE` 이거나 다른 곳이 쥐었다고 했지만, 메시지는 이미 at-most-once 우편함 밖에 있으므로 어쨌든 돈다. **0 이어야 한다.** 이것 말고는 아무것도 알리지 않는다 — 결과는 일부러 캐시에 넣지 않고, 호출자에게는 평소처럼 레일로 답하며, 이를 구별하는 알림도 없다. 저장소가 *던진* 경우는 세지 않는다 — 소유권에 대해 아무 말도 하지 않았기 때문이다. |

### Micrometer 어댑터 예시

```java
public final class MicrometerSessionMetrics implements SessionMetrics {
    private final Timer lockAcquireSucceeded;
    private final Timer lockAcquireRejected;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter holderLossRecovered;
    // … 훅마다 하나씩 …

    public MicrometerSessionMetrics(MeterRegistry r) {
        this.lockAcquireSucceeded = Timer.builder("aimon.session.lock.acquire")
            .tag("outcome", "succeeded").register(r);
        this.lockAcquireRejected = Timer.builder("aimon.session.lock.acquire")
            .tag("outcome", "rejected").register(r);
        this.cacheHits = Counter.builder("aimon.session.cache.hits").register(r);
        this.cacheMisses = Counter.builder("aimon.session.cache.misses").register(r);
        this.holderLossRecovered = Counter.builder("aimon.session.holder_loss_recovered").register(r);
        // …
    }

    @Override
    public void onLockAcquireSucceeded(Duration latency) { lockAcquireSucceeded.record(latency); }
    @Override
    public void onLockAcquireRejected(Duration latency) { lockAcquireRejected.record(latency); }
    @Override
    public void onCacheHit() { cacheHits.increment(); }
    @Override
    public void onCacheMiss() { cacheMisses.increment(); }
    @Override
    public void onHolderLossRecovered() { holderLossRecovered.increment(); }
    // …
}
```

훅은 밑바탕 이벤트를 낸 스레드(턴 실행기, 스케줄러, 신호 버스 디스패처)에서 발화한다. 구현은 **막으면 안 되고** 스레드 안전해야 한다 — 라우터는 호출마다 방어적으로 감싸지만, 느린 메트릭 백엔드는 그래도 세션 작업에 배압을 건다.

---

## 5. 우아한 종료

권장 종료 진입점은 `closeGracefully(Duration timeout)` 다:

1. 수락 게이트가 즉시 뒤집힌다. 이후의 `submit()` 호출은 `IllegalStateException("SessionRouter is shutting down — refusing new submit")` 을 던진다.
2. 라우터는 진행 중인 턴이 끝나기를 `timeout` 까지 기다린다.
3. 시간이 다 되면 모든 활성 세션을 `InterruptReason.SYSTEM_SHUTDOWN` 으로 중단한다. 그리고 중단된 턴이 마지막 이벤트를 낼 수 있게 짧은 유예 창을 기다린다.
4. 강제 종료 경로가 돈다(캐시, 실행기, 스케줄러, 구독).
5. 모든 턴이 `timeout` 안에 빠졌으면 `true`, 아니면 `false` 를 돌려준다.

그냥 `close()` 는 `closeGracefully(Duration.ZERO)` 와 같다 — 빼내기가 없다.

### 대기 중인 작업은 어떻게 되나

유예 창 안에서 빠지는 노드는 이미 수거한 대기 메시지를 계속 돌린다 — 2단계가 기다리는 것이
바로 그 작업이다 — 그러나 우편함에서 **새로** 꺼내는 것은 멈춘다. 두 가지가 뒤따르고, 둘 다
롤링 재시작에서 보인다:

- **아직 우편함에 있는 작업은 넘겨진다.** 게이트가 뒤집힌 뒤 도착한 `MESSAGE_ENQUEUED` 초인종은
  빠지는 노드가 그것을 빼내는 대신 세션을 내려놓고 초인종을 다시 발행하게 만든다. 그러면 피어 —
  보통은 호출자가 기다리고 있는 노드이다. 그 노드는 구독을 유지하기 때문이다 — 가 `lockLease` 가
  만료된 뒤가 아니라 신호 왕복 한 번 안에 세션을 claim 한다. 넘겨진 세션마다 `MESSAGE_ENQUEUED`
  하나가 더 생긴다고 예상하면 된다. 초인종이 울린 순간의 홀더만 그것을 중계하므로, 빠지는 노드
  둘이 같은 알림을 주고받는 일은 없다.
- **창이 닫힐 때 이미 수거된 작업은 `NOT_HOLDER` 로 실패한다.** 그 메시지들은 at-most-once
  우편함 밖에 있으므로 어떤 후임도 돌릴 수 없다. 호출자는 세션을 더는 쥐지 않은 노드를 이름으로
  밝힌 `TURN_RESULT{error=NOT_HOLDER}` 를 받는다 — `FAILED` 와 구별되는 것은 바로 클라이언트가
  "한 번도 돌지 않았으니 다시 제출하라" 와 "입력이 시도되었고 던졌다" 를 가를 수 있게 하려는
  것이다. 이것이 대량으로 보이면 `timeout` 이 재시작 시점의 대기열 깊이에 비해 짧다는 뜻이다.

### Spring / Servlet 컨테이너 종료와 배선

```java
@PreDestroy
void shutdown() {
    boolean drained = router.closeGracefully(Duration.ofSeconds(20));
    if (!drained) {
        log.warn("SessionRouter: forced shutdown — surviving turns interrupted");
    }
}
```

`timeout` 은 넉넉히 잡는다 — 대부분의 LLM 턴은 몇 초면 끝나지만 도구 실행은 늘어질 수 있다. 일반적인 웹 배포에서는 20–30초가 무난한 출발점이고, `closeGracefully` 가 자주 `false` 를 돌려주면 올린다.

### 종료 시 락 반환

노드가 종료되면(우아하게든 강제로든) `runTurnLoop` 의 `finally` 블록이 세션 락을 반환한다. 종료 훅을 돌리지 못하고 JVM 이 죽어도 락의 리스는 `lockLease` 뒤에 만료되고, 홀더 유실 스위퍼가 `idempotencySecondaryTtl` 뒤에 이어받는다. **영구히 갇히는 상태는 없다** — 최악의 경우 다른 노드가 이어받기까지 클러스터가 `max(lockLease, idempotencySecondaryTtl)` 동안 멈춘다.

---

## 6. 운영 플레이북

### "락 획득 지연이 튀었다"

- Redis CPU 와 그 노드까지의 네트워크 RTT 를 본다.
- `aimon.session.lock.acquire{outcome=rejected}` 비율을 본다 — 여기가 튀면 세션 핫스팟이다(세션 하나가 여러 노드로 동시 트래픽을 끌어온다). 완화: LB 가 지원하면 엣지에서 세션 id 로 라우팅한다. 아니면 `idleTtl` 을 올려 쥔 노드가 세션을 더 오래 따뜻하게 두게 하고, 재획득 교체를 줄인다.

### "캐시 축출 `LRU` 비율이 오른다"

- 프로세스가 `maxCachedSessions` 에 가깝게 돌고 있다. 상한을 올리거나(힙을 더) `idleTtl` 을 줄인다(비활성 세션을 더 빨리 교체). 두 설정은 서로 얽힌다 — `idleTtl × steadyStateRps` 가 로드 밸런싱된 캐시 점유량이다.

### "리스 연장 실패가 0 이 아니다"

- 거의 언제나 Redis 배압이나 네트워크 순간 끊김이다. 갱신기는 유휴 스윕과 하트비트를 나르는 풀과 떨어진, 갱신 전용 스케줄러에서 돌기 때문에 턴 스레드의 JVM GC 멈춤이 그것을 굶기지 못한다. 그래도 실패가 보이면 `lockLease` 를 올려 틱 하나를 놓쳐도 치명적이지 않게 한다.

### "홀더 유실 복구가 관찰된다"

- 노드 크래시 신호다. 크래시한 노드 하나, 대기 중인 턴 하나마다 복구 한 번이 정상이다. 크래시 없이 0 이 아닌 비율이 이어지면 `idempotencySecondaryTtl` 이 `lockLease` 에 비해 너무 낮게 잡힌 것이다 — 2차 TTL 을 `≥ 2 × lockLease` 로 올린다.

### "제출 시 IdempotencyConflictException"

- 클라이언트가 1차 TTL 안에서 같은 멱등성 키를 다른 입력 바이트로 다시 썼다. 의도된 동작이다 — 라우터는 앞선 결과를 말없이 덮어쓰기를 거부한다. 호출자에게 409 를 올리고 새 키를 발급하게 한다.

---

## 7. 진단

쓸모 있는 로그 줄(모두 INFO/WARN — 자세한 흐름은 `at.aimon.session.routing` 을 DEBUG 로):

| 로그 메시지 | 뜻 |
|---|---|
| `Lease renewal rejected (token mismatch) for session X holder Y` | 락을 이미 누가 이어받았다(리스 만료). 갱신기가 `onLeaseExtendFailed` 를 부르고, 라우터는 턴을 `LEASE_LOST` 로 중단하며, 루프가 풀린다 — 손댈 것 없다. |
| `Holder loss detected for session X (holder Y) — emitting HOLDER_LOST recovery` | 스위퍼가 낡은 `IN_FLIGHT` 항목을 찾아 복구했다. 크래시한 노드를 찾으려면 피어의 `nodeId` 로그와 맞춰 본다. |
| `closeGracefully: N in-flight turn(s) did not drain within T; interrupting and forcing close` | 종료가 시간을 넘겼다. 타임아웃을 올리거나, 특정 도구 / LLM 호출 하나가 매달려 있는지 조사한다. |
| `Subscription close threw on shutdown: …` | 대개 무해하다 — 밑바탕 클라이언트 쪽 구독이 이미 내려가 있었다. 진짜 백엔드 버그를 조용히 놓치지 않도록 WARN 으로 남긴다. |
| `Re-ringing the doorbell for session X failed: …` | 빠지는 노드가 세션 X 를 내려놓았지만 초인종을 다시 발행하지 못했다(신호 백엔드가 이미 내려갔거나 닿지 않음). 알림은 다음 리스 반환 때 다시 시도된다. 그 전에 노드가 끝나면 대기 메시지는 그 세션의 다음 제출을 기다린다. 종료 순서에서 백엔드 가용성을 확인한다 — 신호 버스 클라이언트보다 세션 라우터를 *먼저* 닫는다. |

---

## 8. 테스트 예시

### NOOP 메트릭으로 하는 단위 테스트

```java
TestManagerHarness harness = TestManagerHarness.builder().build();
// SessionMetrics.NOOP 이 기본으로 배선된다
```

### 테스트에서 메트릭 검증하기

```java
RecordingSessionMetrics metrics = new RecordingSessionMetrics();
TestManagerHarness harness = TestManagerHarness.builder().metrics(metrics).build();
// … 라우터를 움직인다 …
assertThat(metrics.lockAcquireSucceeded.get()).isEqualTo(1);
```

`RecordingSessionMetrics` 는 `at.aimon.session.routing.fixture.RecordingSessionMetrics` 에 있고, 정식 테스트 가짜다.

### 다중 노드 시나리오

`TwoNodeRedisHarness`(`aimon-session-redis` 의 테스트 범위)는 Testcontainers Redis 하나에 라우터 둘을 배선한다. 시나리오가 노드 경계를 건널 때 — 락, 신호 팬아웃, 우편함 인계, 홀더 유실 복구 — 쓴다. `aimon-session-mongodb` 와 `aimon-session-postgres` 에는 대응하는 `TwoNodeMongoHarness` / `TwoNodePostgresHarness` 가 있다.
