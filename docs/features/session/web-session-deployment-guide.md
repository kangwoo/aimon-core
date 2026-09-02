# Web Session Manager — Deployment & Operations Guide

`SessionRouter` wraps `at.aimon.core.agent.session.LiveSession` with the cross-node concerns required for a horizontally-scaled web deployment: per-session distributed locking, cross-node signal fan-out, mailbox hand-off when the calling node is not the lock holder, idempotency, holder-loss recovery, and graceful shutdown.

This guide covers how to deploy and operate it. Design rationale is in `docs/design/session/routing.md`.

> The scope model this guide follows is defined in [`docs/overview/scope-model.md`](../../overview/scope-model.md) — `AgentRuntime` is agent-scoped and shared across sessions, and one `SessionRecord` may be served by 0..N `LiveSession` handles over its lifetime.

---

## 1. Deployment Topologies

### Single-node (`DeploymentMode.SINGLE_NODE`)

Use when the application runs as a single process. All four SPIs default to in-memory implementations:

```java
SessionRouter router = SessionRouter.builder()
    .sessionFactory(sessionFactory)
    .sessionRecordStore(sessionRecordStore)
    .build();
```

This is the path most tests use and matches the development-time experience.

Exactly one of `sessionFactory(LiveSessionFactory)` and `sessionOpener(LiveSessionOpener)` must be set — `build()` rejects both and neither. Use the factory for a stateless open; use the opener when caller-domain attributes (tenant id, organization unit, …) have to reach the per-session context via `OpenAttributes`.

### Multi-node (`DeploymentMode.DISTRIBUTED`)

Use when the application runs as ≥2 instances behind a load balancer with no sticky routing. **All four SPIs must be wired explicitly**; the builder fails fast at `build()` otherwise — in-memory defaults would silently let two nodes process the same session in parallel.

```java
SessionRouter router = SessionRouter.builder()
    .sessionFactory(sessionFactory)
    .sessionRecordStore(sessionRecordStore)
    .mode(DeploymentMode.DISTRIBUTED)
    .nodeId(System.getenv("HOSTNAME"))                        // unique per process
    .sessionLeaseStore(new RedisSessionLeaseStore(redisConn))
    .signalBus(new RedisPubSubSignalBus(redisConn, redisPubSubConn))
    .sessionInbox(new RedisSessionInbox(redisConn))
    .idempotencyStore(new RedisIdempotencyStore(redisConn))
    .metrics(new MicrometerSessionMetrics(meterRegistry))
    .build();
```

`RedisPubSubSignalBus` takes two Lettuce connections — a plain `StatefulRedisConnection` for publishing and a `StatefulRedisPubSubConnection` for the listener. Both stay owned by the caller; the bus only registers and removes its own listener.

`nodeId` must be unique per process. It is embedded in lock holder identity, idempotency entries, and signal-bus origin filters — duplicates cause the router to mistake a peer for itself and deadlock waiting for its own signals.

### Session-scoped skill approvals (optional)

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

## 2. Required SPI Implementations (DISTRIBUTED mode)

| SPI | Purpose | Reference impl |
|---|---|---|
| `SessionLeaseStore` | Per-session distributed lease with fencing tokens | `RedisSessionLeaseStore` (SET NX PX + Lua release) |
| `SessionSignalBus` | Cross-node signal fan-out (INTERRUPT, YIELD, EVICT, MESSAGE_ENQUEUED, EVENT, TURN_RESULT, STATUS) | `RedisPubSubSignalBus` |
| `SessionInbox` | Cross-node mailbox for non-holder submits | `RedisSessionInbox` (Redis Streams, `XADD`) |
| `IdempotencyStore` | Cluster-wide idempotency keys + holder-loss detection | `RedisIdempotencyStore` |

All four are declared in `aimon-core`, one package each — `at.aimon.core.agent.session.{store, signal, inbox, idempotency}`. That means a backend module implements them with a dependency on `aimon-core` alone: `aimon-session-routing` is a *consumer* of these SPIs, not their owner, and the backends do not depend on it outside their tests. `aimon-session-mongodb` and `aimon-session-postgres` ship the same four (`MongoSessionLeaseStore` / `PostgresSessionLeaseStore`, and so on) for deployments without Redis.

When implementing your own backends, each SPI interface's javadoc states the contract and the reference implementations' integration tests (`Redis*IntegrationTest` in `aimon-session-redis`, and the `Mongo*` / `Postgres*` equivalents) pin the observable behaviour.

---

## 3. Operational Tunables

All defaults live in `SessionRouterBuilder`. The recommended starting points:

| Tunable | Default | Recommendation |
|---|---|---|
| `idleTtl` | 10 min | Match LLM-session resource lifetime (MCP connections, knowledge stores). Lower = more cold-starts; higher = more idle heap. |
| `maxCachedSessions` | 1000 | Bound on heap. Set so worst-case `idleTtl × peak-rps` does not OOM. |
| `lockLease` | 30 s | Long enough that a paused GC or a slow tool call does not lose the lease. |
| `lockExtendInterval` | 10 s | Conventionally `lockLease / 3`. Renewer ticks even when the turn is blocked on the LLM. |
| `holderLossSweepInterval` | 15 s | Trade-off: shorter = faster recovery from a node crash, longer = less Redis load. |
| `idempotencyPrimaryTtl` | 24 h | How long a successful turn's result stays replayable on a duplicate submit. |
| `idempotencySecondaryTtl` | 30 s | A lapsed `IN_FLIGHT` entry past this age is eligible for sweeper-driven recovery. Must be > `lockLease`. |
| `releaseInterruptTimeout` | 5 s | Max wait for an active turn to honor `releaseSession()` before the cache forces eviction. |

**Invariant:** `lockExtendInterval < lockLease` (enforced at `build()`) and `idempotencySecondaryTtl > lockLease`. Violations cause spurious holder-loss recoveries on healthy nodes.

---

## 4. Metrics Integration

`SessionMetrics` is a no-op-by-default SPI. Wire one implementation per process and the router will fire callbacks at:

| Hook | Where it fires | Use the metric for |
|---|---|---|
| `onLockAcquireSucceeded(Duration)` | After every `SessionStore.claim()` that won the lease | p50/p95/p99 lock latency |
| `onLockAcquireRejected(Duration)` | After every `SessionStore.claim()` that answered held-elsewhere (→ inbox) | "How often are we hitting cross-node hand-off?" |
| `onCacheHit` / `onCacheMiss` | Inside `LiveSessionCache.ensureOpen` | Cache hit-rate ⇒ cold-start cost |
| `onCacheEviction(reason)` | Caffeine removal listener; reasons: `IDLE`, `LRU`, `EXPLICIT_RELEASE`, `OTHER` | Memory pressure (`LRU` spike) vs. natural churn (`IDLE`) |
| `onLeaseExtendSucceeded` / `onLeaseExtendFailed` | `LeaseRenewer` per tick | A non-zero `Failed` rate means lease tuning is wrong, or peers are too aggressive |
| `onSubmitOutcome(SubmitDisposition.Kind)` | After every `submit()` | `EXECUTED_LOCALLY` vs. `FORWARDED` ratio per session hot-spot |
| `onHolderLossRecovered` | Holder-loss sweeper after winning the CAS | Counts cluster-wide crashes recovered. **Should be near zero in steady state.** |

### Micrometer adapter recipe

```java
public final class MicrometerSessionMetrics implements SessionMetrics {
    private final Timer lockAcquireSucceeded;
    private final Timer lockAcquireRejected;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter holderLossRecovered;
    // … one per hook …

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

Hooks fire on the thread that emits the underlying event (turn executor, scheduler, signal-bus dispatcher). Implementations **must not block** and must be thread-safe — the router wraps each invocation defensively but a slow metrics backend will still backpressure session work.

---

## 5. Graceful Shutdown

`closeGracefully(Duration timeout)` is the recommended shutdown entry point:

1. The acceptance gate flips immediately. Subsequent `submit()` calls throw `IllegalStateException("SessionRouter is shutting down — refusing new submit")`.
2. The router waits up to `timeout` for in-flight turns to finish.
3. If the timeout elapses, every active session is interrupted with `InterruptReason.SYSTEM_SHUTDOWN`. The router then waits a brief grace window for the interrupted turns to publish their terminal events.
4. The hard-close path runs (cache, executors, schedulers, subscriptions).
5. Returns `true` if all turns drained inside `timeout`, `false` otherwise.

The plain `close()` is equivalent to `closeGracefully(Duration.ZERO)` — no draining.

### What happens to queued work

Inside the grace window the draining node still runs the queued messages it had already
collected — that is exactly the work step 2 is waiting for — but it stops taking anything
**new** out of the inbox. Two things follow, and both are visible in a rolling restart:

- **Work still in the inbox is handed over.** A `MESSAGE_ENQUEUED` doorbell that arrives
  after the gate flips makes the draining node give up the session and re-publish the
  doorbell instead of draining it. A peer — usually the node whose caller is waiting, since
  it stays subscribed — then claims the session within a signal round-trip rather than
  after `lockLease` expires. Expect one extra `MESSAGE_ENQUEUED` per handed-over
  session; only the node that was the holder when the doorbell rang relays it, so two
  draining nodes cannot volley the same notice.
- **Work already collected when the window closes is failed with `NOT_HOLDER`.** Those
  messages are out of the at-most-once inbox, so no successor can run them. Their callers get
  `TURN_RESULT{error=NOT_HOLDER}` naming the node that stopped holding the session —
  distinct from `FAILED` precisely so a client can tell "resubmit this, it never ran" from
  "your input was attempted and threw". Seeing these in bulk means `timeout` is too short for
  the queue depth at restart time.

### Wiring with Spring / Servlet container shutdown

```java
@PreDestroy
void shutdown() {
    boolean drained = router.closeGracefully(Duration.ofSeconds(20));
    if (!drained) {
        log.warn("SessionRouter: forced shutdown — surviving turns interrupted");
    }
}
```

Pick `timeout` generously — most LLM turns finish in seconds, but tool execution can stretch. 20–30 s is a reasonable starting point for typical web deployments; raise it if you see `closeGracefully` returning `false` regularly.

### Lock release on shutdown

When a node shuts down (graceful or hard), the `runTurnLoop` `finally` block releases the per-session lock. If the JVM is killed without running shutdown hooks, the lock's lease still expires after `lockLease` and the holder-loss sweeper takes over after `idempotencySecondaryTtl`. **There is no permanent stuck state** — at worst the cluster pauses for `max(lockLease, idempotencySecondaryTtl)` before another node can take over.

---

## 6. Operational Playbook

### "Lock acquire latency spiked"

- Check Redis CPU and network RTT to the node.
- Look at `aimon.session.lock.acquire{outcome=rejected}` rate — spikes here mean session hot-spotting (one session drawing concurrent traffic to many nodes). Mitigation: route by session id at the edge if your LB supports it; otherwise raise `idleTtl` so the holding node keeps the session warm longer and reduces re-acquire churn.

### "Cache eviction `LRU` rate is rising"

- The process is running close to `maxCachedSessions`. Either raise the cap (more heap) or shorten `idleTtl` (turn over inactive sessions sooner). The two settings interact — `idleTtl × steadyStateRps` is the load-balanced cache occupancy.

### "Lease extend failures are non-zero"

- Almost always Redis backpressure or a network blip. The renewer ticks on a scheduler dedicated to renewal, separate from the pool that carries idle sweeps and heartbeats, so JVM GC pauses on the turn thread cannot starve it; if you still see failures, raise `lockLease` so a single missed tick is not fatal.

### "Holder-loss recoveries observed"

- This is a node-crash signal. One recovery per crashed node per pending turn is expected. A steady non-zero rate without crashes points at `idempotencySecondaryTtl` set too low relative to `lockLease` — bump the secondary TTL to `≥ 2 × lockLease`.

### "IdempotencyConflictException at submit"

- A client reused an idempotency key with different input bytes within the primary TTL. This is intentional — the router refuses to silently override the prior result. Surface a 409 to the caller and have them mint a fresh key.

---

## 7. Diagnostics

Useful log lines (all at INFO/WARN — set `at.aimon.session.routing` at DEBUG for the verbose flow):

| Log message | Meaning |
|---|---|
| `Lease renewal rejected (token mismatch) for session X holder Y` | The lock was already taken over (lease lapsed). The renewer fires `onLeaseExtendFailed`, the router interrupts the turn with `LEASE_LOST`, and the loop unwinds — no manual action needed. |
| `Holder loss detected for session X (holder Y) — emitting HOLDER_LOST recovery` | The sweeper found a stale `IN_FLIGHT` entry and recovered it. Cross-reference with peer `nodeId` logs to identify the crashed node. |
| `closeGracefully: N in-flight turn(s) did not drain within T; interrupting and forcing close` | Shutdown timed out. Either raise the timeout or investigate whether one specific tool / LLM call is hanging. |
| `Subscription close threw on shutdown: …` | Usually benign — the underlying client-side subscription was already torn down. Warns at WARN-level so you do not silently miss a real backend bug. |
| `Re-ringing the doorbell for session X failed: …` | A draining node gave up session X but could not re-publish the doorbell (signal backend already down or unreachable). The notice is retried on the next lease return; if the node exits first, the queued message waits for the session's next submission. Check backend availability during shutdown ordering — close the session router *before* the signal-bus client. |

---

## 8. Testing Recipes

### Unit tests with NOOP metrics

```java
TestManagerHarness harness = TestManagerHarness.builder().build();
// SessionMetrics.NOOP wired by default
```

### Asserting against metrics in a test

```java
RecordingSessionMetrics metrics = new RecordingSessionMetrics();
TestManagerHarness harness = TestManagerHarness.builder().metrics(metrics).build();
// … exercise the router …
assertThat(metrics.lockAcquireSucceeded.get()).isEqualTo(1);
```

`RecordingSessionMetrics` lives at `at.aimon.session.routing.fixture.RecordingSessionMetrics` and is the canonical test-fake.

### Multi-node scenarios

`TwoNodeRedisHarness` (in `aimon-session-redis` test scope) wires two routers against one Testcontainers Redis. Use it when the scenario crosses a node boundary — locking, signal fan-out, inbox hand-off, holder-loss recovery. `aimon-session-mongodb` and `aimon-session-postgres` carry the equivalent `TwoNodeMongoHarness` / `TwoNodePostgresHarness`.
