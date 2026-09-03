---
translated_from: docs/features/session/web-session-deployment-guide.md
source_commit: 61231870
---

# Web Session Manager — Deployment & Operations Guide

`SessionRouter` wraps `at.aimon.core.agent.session.LiveSession` with the cross-node concerns required for a horizontally-scaled web deployment: per-session distributed locking, cross-node signal fan-out, mailbox hand-off when the calling node is not the lock holder, idempotency, holder-loss recovery, and graceful shutdown.

This guide covers how to deploy and operate it. Design rationale is in `docs/design/session/routing.md`.

> The scope model this guide follows is defined in [`docs/overview/scope-model.en.md`](../../overview/scope-model.en.md) — `AgentRuntime` is agent-scoped and shared across sessions, and one `SessionRecord` may be served by 0..N `LiveSession` handles over its lifetime.

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

If you cache skill approvals per session (`SessionApprovalStore`), pass the router **the same instance**
you passed to the agent runtime:

```java
SessionRouter.builder()
    // ...
    .sessionApprovalStore(sessionApprovalStore)   // the very instance passed to OrcaAgentRuntimeFactory#withSessionApprovalStore
    .build();
```

The router discards that session's approvals on `releaseSession` / `deleteSession`, and on receiving an
`EVICT` from a peer node. Leave it unset and the hook becomes a no-op: a deleted session's approvals stay in
the cache for as long as the process lives, and a session that reuses the same `SessionId` inherits them
**without being asked**. It does not fire on idle-TTL eviction — the session itself is still alive, so not
asking again when the user comes back is the right behaviour.

The in-memory implementation is node-local. Because it listens for a peer's `EVICT`, deletion propagates
across the cluster, but the approvals themselves are not shared across nodes, so a session that moves to
another node is asked once more.

**On an `AimonStack` you do not hand it to those two places yourself.** Put it on the spec once and the
same instance reaches both the runtime factory and the router — the mistake of giving the two seats
different instances becomes unavailable.

```java
AimonStackSpec.builder()
    .skillApproval(SkillApprovalSpec.denyAll()
            .withSessionApprovalStore(sessionApprovalStore)   // the router receives this instance too
            .withAgentApprovalStore(agentApprovalStore)
            .withPendingTurnRegistry(pendingTurnRegistry))
    .build();
```

Under the Spring Boot starter, defining a `SessionApprovalStore` / `AgentApprovalStore` /
`PendingTurnRegistry` bean is the whole configuration. All three are **borrowed**: the stack closes none
of them.

A deployment that shares only some of the three is told which ones are **left** at start-up, through the
`distributed-approvals` degradation. Sharing the registry alone is the shape worth naming: it finds the
turn suspended on another node and then releases it into a node with no record of the decision.

Forks need no separate cleanup — they have no approvals of their own to erase in the first place. A
subagent fork has **no `SessionId` at all** (the tool context carries `EXECUTION_ID` instead of
`SESSION_ID`), and approvals are looked up solely by the id of the session that launched it
(`invokingSessionId` — the wire key is frozen and stays `"invokingConversationId"`), so invalidating that
one session also removes the reach of every execution it had delegated.

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
| `onForwardDoorbellRerung` | Forward poll, on every tick where the awaited message is still uncollected | Somebody is waiting on a message no node has taken out of the inbox. **Retries, not recoveries** — ordinary queueing raises it too, and a successful takeover is what makes it stop, so alert on a rate that does not fall. |

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
