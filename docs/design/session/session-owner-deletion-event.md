# Design — session owner + session deletion event (issue #253)

> Status: **IMPLEMENTED** — `aimon-core` (`SessionRecordView.getOwner`, `SessionRecordStore.provision(id, agentRef,
> owner)`, `SessionStore.claim(…, owner, …)`, `InboundMessage.getOwner`, `SessionLifecycleListener`,
> `SessionDeletedEvent`), `aimon-session-routing` (`SubmitRequest.owner`, `DefaultSessionRouter.deleteSession`,
> `SessionRouterBuilder.lifecycleListener`), the three backends (Postgres `V3__session_record_owner.sql`), the bootstrap
> `SessionSpec` and the Spring starter. Records: [`session-model.md`](session-model.md) §3.6,
> [`routing.md`](routing.md) §7.7, [`backends.md`](backends.md) §1.1, the web-session deployment guide, and backlog
> SO-1 ~ SO-6 in [`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md).
>
> **[§9](#9-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §9 is the body as approved in design review round 2
> (PASS, no blocking findings, eight non-blocking notes), kept byte-exact rather than corrected — the house habit in
> this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its `file:line` citations are at
> `63e5d564`, as its first line says. The review transcripts and the run's `TASK.md` are not in the repository; §9
> reproduces what was measured and which review notes were taken.
>
> It is in English, like its siblings in `../tool/`; `docs/design/` is not a translation target
> (`docs/project/documentation-guide.md` §5.1).

Base: `main` @ `63e5d564`. Every file path below is relative to the repo root.

## 1. Problem

A `SessionRecord` (in `at.aimon.core.agent.session.store`) records an id, transcript, `agentRef`, compaction counter,
`sessionTotals` and `budgetOverride`. It does not record who owns the session. And although
`SessionRecordStore.delete(SessionId)` exists, nothing can observe it. The only production caller is
`DefaultSessionRouter.deleteSession` (`modules/aimon-session-routing/.../internal/DefaultSessionRouter.java:2721`), and
it tears down router-internal state only. So every multi-tenant extension has to rebuild both facts itself.
aimon-sandbox, for example, needs a host-supplied `SessionOwnerLookup.ownerOf(SessionId)`, and the host must remember
to call `deleteSessionOptions(session)` on every delete. Otherwise the sandbox's per-session state stays around until
its workspace is purged. Core should (a) persist an optional owning `Principal` on the record, decided atomically when
the record is provisioned and readable by anyone through `SessionRecordView`, and (b) fire a deletion callback that
extensions register once through the stack builder. Deployments that never set an owner and register no listener must
behave exactly as they do today.

## 2. Approach

### 2.1 Owner

- **The owner is a side field set by `provision`, with the same rule as `agentRef`: fill it if empty, never overwrite
  it.** `provision` already answers "create if missing, bind if unbound, tell me what is there" in one atomic backend
  operation (`COALESCE` in Postgres, `$ifNull` in Mongo, `HSETNX` in Redis, `compute` in memory). The owner becomes one
  more column in that same statement. The first provisioning that carries an owner wins, and every later provisioning
  reads the existing owner back. No new write primitive is needed, and the "four owners, four concerns" SPI rule
  (`SessionRecordStore` class javadoc) keeps holding: the owner is written only on the provision path, which the claim
  path already owns.
- **The owner is explicit on the submit and is not derived from `initiator`.** `SubmitRequest` gains an optional
  `owner(Principal)`. Only a request that sets it can record an owner. `initiator` is mandatory and is not the owner:
  the Spring starter fills a *system* initiator into every request (`DefaultAimonSessions:103`), and rewake, scheduled
  routines and service callers post into sessions they do not own. Deriving the owner from the initiator would make
  `SYSTEM` the owner of every single-user session, and would hand ownership of a multi-tenant session to whichever
  automated caller happened to speak first. Both break the issue's "single-user deployments are unaffected" guarantee.
- **The owner is carried through the inbox so a forwarded first submit does not lose it.** `InboundMessage` gains an
  optional `owner`, and the drain path provisions with `first.getOwner()` beside `first.getAgentRef()`
  (`DefaultSessionRouter:1467`). Both values come from the same message, for the reason the code there already gives:
  "they describe one submission".
- **Core records the owner and does not enforce it.** A submit whose `owner` differs from the recorded one is not
  rejected. Authorization is the host's or the extension's decision. The sandbox reads the owner and applies its own
  rule ("only the owner may redirect"). See open question Q1.

### 2.2 Deletion event

- **The new SPI is `SessionLifecycleListener`, in core under `at.aimon.core.agent.session`.** It has one callback,
  `onDeleted(SessionDeletedEvent)`, which is a `default` no-op so that `onCreated` and similar callbacks can be added
  later without breaking implementors. It lives in core because external extensions (aimon-sandbox) compile only
  against core's public SPI. Per the naming rule, a `Session*` name refers to the persisted record, and record deletion
  is exactly what the event reports.
- **The router fires it on the deleting node, inside the lease, once the record is gone.** Whether it fires depends
  on `recordDeleted`, not on whether the later teardown steps succeed (§3, §6).
  `DefaultSessionRouter.deleteSession` is the only path that deletes a record with the lock held. Firing it there,
  before the `finally` that returns the lease, means that no new turn can provision the same `SessionId` again and race
  the listener's cleanup. A listener that deletes "options for session X" therefore cannot delete the options of a
  *new* session X created a millisecond later.
- **Registration goes through the router builder and then the stack builder:**
  `SessionRouterBuilder.lifecycleListener(l)` (repeatable) ← `SessionSpec.Builder.lifecycleListener(l)` ← Spring
  collects every `SessionLifecycleListener` bean (`ObjectProvider.orderedStream()`) into `SessionSpec`. An extension is
  written once and wired by the host once, with no per-delete call.

### 2.3 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| `provision(id, agentRef)` replaced by `provision(id, agentRef, owner)` as the only abstract method | Breaks every third-party `SessionRecordStore` at compile time. The acceptance criteria require existing stores to keep working. A new overload with a default (§4.2) gives the same result. |
| Owner derived from `SubmitRequest.initiator` | `initiator` is mandatory and is often `SYSTEM` or a service (Spring default, rewake, routines). This would assign owners in deployments that never asked for one. See §2.1. |
| A separate `setOwner(id, owner)` write primitive | It adds a fifth writer to a record whose SPI is built around a fixed set of writers. It also needs its own fence check and opens a window in which a provisioned record has no owner yet but a turn is already running. Putting the owner on `provision` decides it in the same statement that creates the row. |
| Owner overwritable ("last submit wins") | That allows exactly the redirect the sandbox must prevent. First-wins mirrors `agentRef`. |
| Decorating `SessionRecordStore.delete` to fire the event | It would fire on every raw `delete`, including the ones `SessionRouter` documents as unsafe because they bypass the lock (`SessionRouter.java:123`). It would also fire outside the lease, which reopens the same-id race described in §2.2, and it gives no clear answer for `clear()`. |
| Reusing the per-session `events()` publisher / terminal `InterruptedAt` | It reports `SESSION_RELEASED` for both release and delete, so the two cannot be told apart. It also needs a subscription per session that exists before the delete happens. An extension wants a single global hook. |
| Reusing the cluster `EVICT` signal (adding `reason=DELETED`) | It is best-effort, fires on every node, and is meant for cache invalidation. Shared per-session state needs exactly one cleanup, not N. Node-local caches are covered in Q3. |
| A durable outbox for delete events | Gives at-least-once delivery across crashes but needs a new store SPI and four new backend implementations. That is far larger than the issue asks for. Extensions already need a reconciliation path, such as sandbox's workspace purge, so at-most-once with documented semantics is enough (§6). |
| Postgres store probing `information_schema` at startup to run without the new column | No backend probes its schema today (the runtime never inspects DDL; see `db/postgres/README.md`). Adding a probe would create two SQL code paths for one release. Requiring the migration follows the V2 precedent. See Q4. |

## 3. Changes by module and file

### aimon-core

- `agent/session/store/SessionRecordView.java` — add `default Optional<Principal> getOwner() { return Optional.empty(); }`.
  The name follows the existing `getX()` convention (`getAgentRef`, `getBudgetOverride`), not the issue's `owner()`.
- `agent/session/store/SessionRecord.java`
  - Add a `private Principal owner` field, `getOwner()`, and a public `setOwner(Principal)` documented like
    `setAgentRef` (sole writer is `provision`, called on a copy inside the store's atomic primitive). It is `public`
    because the field it sits beside, `setAgentRef`, is public too. The sole-writer ArchUnit rule already keeps the
    class itself in-package.
  - `copyOf(view)` carries `view.getOwner()`. `fromSnapshot` leaves the owner null, because a snapshot carries no side
    fields. `toString` includes the owner's type and id.
  - Do **not** add an eighth telescoping constructor. The transcript constructor plus `setOwner` is enough, and so is
    `copyOf`.
- `agent/session/store/StoredSessionRecord.java` — add an `owner` field, `getOwner()`, and `Builder.owner(Principal)`.
  Add `empty(SessionId, String agentRef, Principal owner)`. The existing two-argument `empty` delegates to it with
  `null`.
- `agent/session/store/SessionRecordStore.java`
  - Add `SessionRecordView provision(SessionId sessionId, String agentRef, Principal owner)` as a `default` (§4.2).
  - Update the class javadoc's writer list: `provision` now owns the record's existence, its binding, and its owner,
    each filled only when empty.
  - Update `mergeFromSnapshot` and `setTotalsAndBudgetOverride` docs: the owner is one of the preserved side fields.
- `agent/session/store/InMemorySessionRecordStore.java` — override the three-argument `provision`. Inside the existing
  `compute`, call `setOwner` only when the record's owner is null and the argument is non-null. The two-argument
  method delegates with `null`. `mergeFromSnapshot` must carry the owner across its merge, as it already does for
  `agentRef`.
- `agent/session/store/SessionRecordCodec.java` — add `encodeOwner(Principal) → String | null` and
  `decodeOwner(String) → Principal | null`. The format is `{"version":1,"type":"USER","id":"u-1","displayName":"…"}`.
  `null` / blank decodes to `null`. A malformed document or an unknown `type` throws `IllegalArgumentException`,
  matching `decodeTotals`. Update the "Four values" section of the class javadoc to list the owner.
- `agent/session/store/SessionStore.java` — add an overload
  `claim(SessionId, String agentRef, Principal owner, String holderId, Duration lease)` as a `default`:
  with `owner == null` it delegates to the existing `claim`, otherwise it throws `UnsupportedOperationException`.
- `agent/session/store/DefaultSessionStore.java` — implement the overload. The existing four-argument `claim`
  delegates with `null`. **A rejected claim must not win ownership**, so the owner is written in a second step, after
  the binding is known to match:
  1. `record = repository.provision(sessionId, agentRef)` — unchanged, settles and reports the binding.
  2. If the binding differs → release and return `AgentConflict` (unchanged). No owner is written.
  3. Otherwise, if `owner != null` and `record.getOwner().isEmpty()`, then
     `record = repository.provision(sessionId, agentRef, owner)`. This is still first-wins at the backend.

  The two steps are safe without a transaction for the reason `claim` already relies on: the lease was won one
  statement earlier, so this node is the only writer. The extra round trip happens only on the claim that actually
  fills an owner. Folding the condition into the backends' upsert (`CASE WHEN COALESCE(agent_ref, …) = …`) was
  considered and rejected: it would need four bespoke conditional writes for a rule `claim` can state once. `FencedRecords` overrides the three-argument
  `provision` with the same `guard.check`. If it did not, the interface default would forward to the unfenced
  two-argument path and silently drop the owner.
- `agent/session/inbox/InboundMessage.java` — add an optional `owner` (`Optional<Principal> getOwner()`,
  `Builder.owner(Principal)`). It stays absent by default.
- **New** `agent/session/SessionLifecycleListener.java` — an interface with
  `default void onDeleted(SessionDeletedEvent event) {}` and javadoc stating the delivery contract in §6.
- **New** `agent/session/SessionDeletedEvent.java` — an immutable class with a builder (CLAUDE.md: class, not record).
  Fields: `SessionId sessionId` (required), `Principal owner` (nullable → `Optional`), `String agentRef` (nullable →
  `Optional`), `Instant deletedAt` (required).
- `agent/session/package-info.java` / `store/package-info.java` — one-line mentions of the new types.

### aimon-session-routing

- `SubmitRequest.java` — add optional `owner` + `Builder.owner(Principal)` + `Optional<Principal> getOwner()`. The
  javadoc must say the owner is consumed only when the record is provisioned. It is ignored on a session that already
  has one, the same "read on cache miss only" style `OpenAttributes` uses.
- `internal/DefaultSessionRouter.java`
  - `holdOrClaim(...)` gains the request's owner and calls the five-argument `store.claim`.
  - Both `InboundMessage.builder()` sites (`:1189`, `:2122`) add `.owner(request.getOwner().orElse(null))`.
  - The drain path (`:1467`) keeps its existing binding provision unchanged. Only when `first.getAgentRef()` equals
    the binding it returns, `first.getOwner()` is present, and the record has no owner, it then calls
    `store.records().provision(convId, boundAgent, first.getOwner().get())`. This is the same "never let a refused
    message win ownership" rule `claim` follows.
  - `deleteSession`:
    - Before `store.records().delete(id)`, and only when there are listeners, read `store.load(id)` to capture the owner
      and `agentRef`. The read is unfenced and the lease is held. It is **not** cheap for a long unsealed record,
      because it reads the whole transcript. That cost is accepted because it is paid once per delete and only when a
      listener is registered. A narrower side-field-only read would mean a new SPI method on four backends, which is
      not worth it for a delete-path read.
    - **Firing is decided by `recordDeleted`, not by reaching the end of the block.** The inner `finally`, the one that
      already calls `returnLease(sessionId, held)`, becomes:
      ```java
      } finally {
          if (recordDeleted) {
              fireDeleted(event);        // never throws; lease still held
          }
          returnLease(sessionId, held);
      }
      ```
      So listeners fire whenever the record really is gone, even if a later teardown step throws, for example
      `deleteSegments`, `emitTerminalInterrupt`, a subscription close, or the unguarded EVICT
      `signalBus.publish(...)` (`RedisPubSubSignalBus.publish` and `PostgresSessionSignalBus.publish` both throw
      during a bus outage). They still fire before the lease is returned, so the same-id guarantee in §2.2 holds. The
      exception from the failed step keeps propagating unchanged. `fireDeleted` swallowing everything is what makes
      this safe inside a `finally`: it can never mask that exception.
    - `fireDeleted` iterates the listeners in registration order and catches `RuntimeException` per listener. It logs
      at WARN with session id and listener class, then continues. The method never rethrows.
- `builder/SessionRouterBuilder.java` — add `lifecycleListener(SessionLifecycleListener)`, which appends and rejects
  null, and `lifecycleListeners(List<…>)`. Pass an immutable copy to the router.
- `SessionRouter.java` — the `deleteSession` javadoc gains a paragraph on listener firing.

### aimon-session-postgres

- **New** `src/main/resources/db/postgres/V3__session_record_owner.sql` —
  `ALTER TABLE session_record ADD COLUMN IF NOT EXISTS owner jsonb;`. The V3 number is free: `V3__indexes.sql` is only
  mentioned in the README as "not yet shipped". The README renames that future file to `V4__indexes.sql` and lists
  V3 as required at deploy. See Q4.
- `PostgresSessionRecordStore.java` — add `owner` to `COLUMNS`. `SQL_PROVISION` becomes
  `INSERT … (session_id, agent_ref, owner, …) VALUES (?, ?, ?::jsonb, now()) ON CONFLICT … SET agent_ref = COALESCE(…),
  owner = COALESCE(session_record.owner, EXCLUDED.owner) … RETURNING`. `toRecord` decodes the owner. The two-argument
  `provision` delegates with `null`. `SQL_MERGE` leaves the column untouched, so the owner is preserved automatically.
- Tests: `PostgresTestSupport` applies V3 after V1 and V2, and a new V3 freeze assertion is added (see §7).

### aimon-session-mongodb

- `internal/DocumentKeys.java` — add `F_OWNER = "owner"`.
- `MongoSessionRecordStore.java` — in `provision`, when the owner is non-null, `$set` `owner` to
  `{$ifNull: ["$owner", {$literal: <owner subdocument>}]}`. `$literal` is required because `displayName` and `id` are
  caller strings that may start with `$`. The stored shape is a subdocument parsed from `SessionRecordCodec.encodeOwner`,
  like `sessionTotals`. `toRecord` decodes it. No index is needed.

### aimon-session-redis

- `RedisSessionRecordStore.java` — add a hash field `owner` (codec JSON string). Today the provision script skips the
  binding by *omitting* `ARGV[2]` (`RedisSessionRecordStore.java:128-130`). With two optional arguments, position
  can no longer carry absence, so both are always passed and **the empty string is the "absent" sentinel**:
  `ARGV[2] = agentRef or ""` and `ARGV[3] = encodedOwner or ""`. The script runs `HSETNX key agentRef ARGV[2]` only
  when `ARGV[2] ~= ""`, and `HSETNX key owner ARGV[3]` only when `ARGV[3] ~= ""`. The empty string is safe as a
  sentinel because an empty `agentRef` is already meaningless and an encoded owner is never empty. Load and provision
  decode the owner. The integration test covers `provision(id, null, owner)` and `provision(id, agentRef, null)`.

### Inbox codecs (all three backends)

- `aimon-session-postgres/.../internal/InboundMessageRowCodec.java`, `aimon-session-redis/.../internal/InboundMessageCodec.java`,
  `aimon-session-mongodb/.../internal/InboundMessageCodec.java`:
  - Encode `owner` with the same `type/id/displayName` shape each codec already uses for `initiator`, only when present.
  - Decode it as optional (the `contextDiscriminator` pattern, `modules/aimon-session-redis/src/main/java/at/aimon/session/redis/internal/InboundMessageCodec.java:145`).
  - `InMemorySessionInbox` needs nothing (it holds the object).

### aimon-bootstrap

- `spec/SessionSpec.java` — add `lifecycleListeners` (an immutable list) + `Builder.lifecycleListener(l)` /
  `lifecycleListeners(list)` + `getLifecycleListeners()`.
- `AimonStackBuilder.buildSessionRouter` (`:784`) — forward them to `SessionRouterBuilder`.
- Listeners are `@ExternallyManaged`, the convention for the other host-supplied objects on `SessionSpec`. The stack
  does not close them.
- `assemble/LateBoundFencedRecordStore.java` — this is the fifth production `SessionRecordStore`. It forwards the
  three-argument `provision` to `requireBound()`, so it does not fall into the interface default and throw although
  the store it wraps supports owners. Add a case to `LateBoundFencedRecordStoreTest`.

### aimon-spring-boot-starter

- `autoconfigure/AimonSessionAutoConfiguration.aimonSessionSpec` — add an `ObjectProvider<SessionLifecycleListener>`
  parameter and pass `orderedStream()` into the spec.
- `AimonSessions` / `DefaultAimonSessions` — no change needed: the builder they return is a `SubmitRequest.Builder`, so
  `.owner(p)` is available. Add a javadoc sentence pointing at it.

### aimon-session-testkit

- Add owner and delete-listener cases to `AbstractMultiNodeSessionContractTest`, so every backend runs them
  multi-node (§7).

### Docs and changelog

Both `docs/**` and `*.en.md` change in the same commit, each with `source_commit` = the pre-change canonical commit.

- `docs/design/session/session-model.md` — the record's fields: add the owner, its first-wins rule, and the fact that
  it is not authorization.
- `docs/design/session/routing.md` — the `deleteSession` sequence: where the listener fires and its delivery contract.
- `docs/design/session/backends.md` — owner storage per backend + Postgres V3.
- `docs/features/session/agent-session-guide.md` — a short "owner & deletion listener" usage section.
- `docs/design/integration/spring-boot-starter.md` — listener bean auto-registration.
- `modules/aimon-session-postgres/src/main/resources/db/postgres/README.md` — V3 apply step. This file has no
  translation; confirm before editing.
- `docs/design/session/session-log.md:536` (+ `.en.md`) — it names `V3__indexes.sql` as the reserved future file.
  Renumber it to `V4__indexes.sql` together with the README.
- Guide + CHANGELOG state plainly that **core records the owner but does not reject a submit from a non-owner**, so a
  host does not assume the router enforces it.
- `CHANGELOG.md` `[Unreleased]` — "Added: session owner and deletion listener (#253)" with an **Upgrade note**: Postgres
  operators must apply `V3__session_record_owner.sql` before rolling out.
- Run `check-translation-structure.py`, `check-translation-staleness.py`, and `check-doc-links.py`.

## 4. Data and interface shapes

### 4.1 New types (core)

```java
package at.aimon.core.agent.session;

public interface SessionLifecycleListener {
    /**
     * Called once, on the node that deleted the record, after the record is gone (even if a later teardown step,
     * such as segment deletion or the EVICT broadcast, fails). Must not call back into SessionRouter for this session.
     */
    default void onDeleted(SessionDeletedEvent event) {
    }
}

public final class SessionDeletedEvent {
    SessionId getSessionId();
    Optional<Principal> getOwner();     // as recorded just before deletion
    Optional<String> getAgentRef();
    Instant getDeletedAt();
    static Builder builder();
}
```

### 4.2 Changed SPI (core)

```java
// SessionRecordView
default Optional<Principal> getOwner() { return Optional.empty(); }

// SessionRecordStore — the two-arg provision stays abstract; the new overload is a default
default SessionRecordView provision(SessionId sessionId, String agentRef, Principal owner) {
    if (owner == null) {
        return provision(sessionId, agentRef);
    }
    throw new UnsupportedOperationException(getClass().getName() + " does not persist session owners");
}
```

This default is the one place the "no default for writes" rule is relaxed. That rule exists because a default could
only be a *non-atomic* load-mutate-store. This default is not one: it performs no owner write at all. It either
delegates the owner-less case to the atomic two-argument primitive, or fails loudly. A third-party store therefore
keeps compiling and keeps working until a host actually asks it to persist an owner, and then it fails at the first
submit instead of silently dropping the owner. The `@implSpec` says so and asks backends to override it with their
atomic primitive. All five production stores override it: in-memory, Postgres, Mongo, Redis, and the bootstrap's
`LateBoundFencedRecordStore` (forwarding).

`SessionStore.claim(id, agentRef, owner, holderId, lease)` follows the same pattern for the same reason.

### 4.3 Persisted shapes

| Backend | Where | Shape | Write rule |
|---|---|---|---|
| In-memory | `SessionRecord.owner` | `Principal` | inside `compute`: set if null |
| Postgres | `session_record.owner jsonb NULL` (V3) | codec JSON | `COALESCE(existing, excluded)` in the provision upsert |
| Mongo | `owner` subdocument | codec JSON parsed | `$ifNull` + `$literal` in the provision pipeline |
| Redis | hash field `owner` | codec JSON string | `HSETNX` in the provision script |
| Inbox envelopes | `owner` field, optional | `type/id/displayName` like `initiator` | — |

Equality for "is this the owner" follows `Principal.equals`, which compares `type` + `id` only, never `displayName`.
`displayName` is stored for display but is not part of the identity. A later provisioning with a different
display name for the same principal does not update it (first-wins applies to the whole value).

## 5. Behavioural rules

1. The owner is written only by `provision`, on either the claim path or the drain path, and only when the record has
   none. No SPI method clears or replaces it. `mergeFromSnapshot`, `setTotalsAndBudgetOverride` and the compaction
   counters preserve it.
2. The owner can come from a later provisioning. A record first provisioned without an owner (an inbox-drain acquire,
   a host that set no owner on the first submit, a rolling-upgrade old node) gets one filled on the next *claim or
   drain on a node that does not already hold the lease*. This is not the next submit: a holder keeps its lease across
   turns (rule 3), so submits to a session this node holds do not provision. The fill comes from the next provisioning
   that carries one. Hosts that need strict ownership must set `owner` on the *first* submit. This is documented.
3. A turn that reuses a lease this node already holds performs no provisioning (`holdOrClaim` "reused" branch), so it
   writes no owner. This is intentional: it avoids a write per turn. The record's owner was decided when the session
   was first provisioned.
4. `deleteSession` fires listeners exactly once per successful call. `clear()` and raw `SessionRecordStore.delete` fire
   nothing. They are documented as maintenance/test operations that bypass the router.

## 6. Failure modes

| Failure | Handling |
|---|---|
| A listener throws | Caught per listener, logged at WARN with session id and listener class. The other listeners still run, and `deleteSession` returns normally, because the record is already irreversibly gone. Throwing would invite a retry that provisions and deletes a fresh empty record. |
| A listener is slow | It runs synchronously with the turn gate and the lease held, so it delays this call and blocks submits to *that* session id only. The javadoc says: keep it short, hand heavy work to an executor. Holding the lease is what prevents the same-id race (§2.2), so it is kept on purpose. |
| A listener calls back into the router for the same session | A submit from the listener thread lands in the inbox and is deliberately not re-rung (`DefaultSessionRouter.java:2800-2810`), so it is stranded. A nested delete re-enters the held gate. The listener javadoc says: do not call `SessionRouter` for the session being deleted from inside `onDeleted`. |
| A teardown step *after* the record delete throws (`deleteSegments`, `emitTerminalInterrupt`, subscription close, EVICT `signalBus.publish` during a Redis/Postgres bus outage) | Listeners still fire exactly once, from the inner `finally` gated on `recordDeleted`, before the lease is returned. The original exception then propagates to the caller unchanged. |
| Node crashes between the record delete and the listener | The event is lost (at-most-once). Documented. Extensions keep a reconciliation path, for example the sandbox workspace purge, or periodically drop per-session state whose `SessionRecordStore.exists(id)` is false. |
| `deleteSession` fails before the record is deleted (lease timeout, store error) | No event fires, matching the record still existing. |
| Delete of a session id that never existed | `acquire` provisions and then deletes it (existing behaviour, `SessionStore.acquire` javadoc), so the listener fires with an empty owner. Listeners must be idempotent. Documented. |
| A host sets an owner, but the store has no owner support | The default `provision`/`claim` throws `UnsupportedOperationException` at the first submit. This is loud, and it is not data loss. |
| Postgres V3 not applied | Every provision or load fails with `column "owner" does not exist`, surfaced as `SessionRecordStoreException`. Mitigated by the CHANGELOG upgrade note and the README apply procedure. See Q4. |
| Rolling upgrade, a new message drained by an old node | The old codec ignores the unknown `owner` field. The record is provisioned without an owner and filled later per rule 5.2. An old message drained by a new node simply has no owner. |
| Corrupt stored owner JSON | `decodeOwner` throws `IllegalArgumentException`, which wraps as the backend's store exception on load, the same as corrupt totals. |
| Redirect attempt (a different principal submits as owner) | Ignored for recording, because first-wins. Core does not reject it (Q1). The recorded owner is readable, so the extension can enforce. |
| A submit rejected for `AgentConflict` (claim) or a drained message naming another agent | It writes no owner: the owner step runs only after the binding is confirmed to match (§3, `DefaultSessionStore` / drain path). A refused request cannot become the permanent owner. |

## 7. Test strategy

Unit tests, in the daemonless `test` task:

- `SessionRecordCodecTest` — owner round trip, null, blank, malformed, unknown type, `displayName` preserved.
- `InMemorySessionRecordStoreTest`:
  - provisioning with an owner sets it.
  - a second provisioning with a different owner keeps the first.
  - provisioning without an owner does not clear one, and a later one fills an empty owner.
  - `mergeFromSnapshot`, `setTotalsAndBudgetOverride`, and increment/reset preserve the owner.
  - `copyOf` carries it.
- `SessionRecordStore` default — a minimal fake store: the three-argument call with `null` delegates, and with an
  owner it throws `UnsupportedOperationException`. The same pair of cases for `SessionStore.claim`.
- `DefaultSessionStoreTest`:
  - `claim` with an owner reports it in `Acquired.record`.
  - `FencedRecords.provision(…, owner)` is fenced, so it is rejected when the lease is not held.
- `StoredSessionRecordTest` — builder and `empty` overloads.
- `InboundMessageCodecTest` / `InboundMessageRowCodecTest` (×3) — owner round trip, and a payload without `owner`
  decodes to empty (the rolling-upgrade case).
- `SessionDeletedEventTest` — builder requires `sessionId` and `deletedAt`, optionals empty by default.
- Routing (`SessionRouterDeleteSessionTest` and a new `SessionRouterOwnerTest`):
  - Submit with an owner makes `load(id).getOwner()` equal it. Submit without one leaves it empty.
  - A second submit by another owner does not change it.
  - A forwarded submit (two-node in-memory harness) records the owner via the drain path.
  - `deleteSession` fires each listener once, with the recorded owner and `agentRef`, after `exists(id)` is false.
  - A throwing listener does not stop the next one or fail the delete.
  - **The signal bus throws on the EVICT publish** (a stub `SessionSignalBus` whose `publish` throws for `EVICT`).
    `deleteSession` propagates that exception, the record is gone, and each listener has fired exactly once with the
    recorded owner. The same check is run with `deleteSegments` throwing.
  - A listener that throws while a teardown step is also throwing: the teardown exception, not the listener's, reaches
    the caller.
  - `AgentConflict` claim: `claim(id, "agent-b", owner)` on an unowned record bound to `agent-a` returns
    `AgentConflict` and the record's owner stays empty (`DefaultSessionStoreTest`). The drain path has the same case
    (a queued message naming another agent leaves the owner empty).
  - A failed delete (store throws) fires nothing.
  - The listener runs while this node still holds the lease: assert `store.findHolder(id)` is this node inside the
    callback.
  - No listeners means no extra `load` (verify on a spy store).
- Bootstrap: `AimonStackBuilder` passes `SessionSpec` listeners to the router (fire one through
  `stack.sessionRouter().deleteSession`).
- Spring: a context with a `SessionLifecycleListener` bean has it invoked on delete (`ApplicationContextRunner`).
- `PostgresSchemaFreezeTest` (or a sibling) pins `V3__session_record_owner.sql`'s identifiers (`session_record`,
  `owner`).

Integration tests (`@Tag("docker")`, `./gradlew integrationTest`):

- `{Postgres,Mongo,Redis}SessionRecordStoreIntegrationTest`: first-wins, a fill on the second provisioning, preserved
  across merge/totals, and Mongo `$`-prefixed id/displayName.
- `AbstractMultiNodeSessionContractTest`: the owner survives a holder move, and a delete on node A fires A's listener
  only.

Then run `./gradlew format`, `./gradlew checkAll` and the three doc scripts. The ArchUnit suites (`SessionNaming`,
`SessionRecordSoleWriter`, `InternalPackageBoundary`) must stay green unchanged.

## 8. Open questions

- **Q1 — Enforcement.** Should the router (or `claim`) reject a submit whose `owner` differs from the recorded owner,
  with a `ClaimResult.OwnerConflict` / `SessionOwnerMismatchException` analogous to `AgentConflict`? This design records
  the owner and leaves enforcement to extensions and hosts, because the issue asks only for the owner to be "exposed
  read-only" and core has no authorization model for sessions. The cost is that core does not enforce the rule the
  sandbox depends on ("only the owner may redirect").
- **Q2 — Ownership transfer / admin reassignment.** No SPI writes the owner after it is set. If hosts need to transfer
  sessions, that needs a fenced, explicit primitive, which is out of scope here.
- **Q3 — Node-local listeners on peer nodes.** Listeners fire only on the deleting node. An extension that keeps
  per-session state *in memory on every node* would need a cluster fan-out, for example carrying `reason=DELETED` on
  the existing `EVICT` signal and firing a separate `onDeletedElsewhere`. That is deferred until there is a consumer.
  The sandbox's options record is shared state, so one firing is what it needs.
- **Q4 — Postgres migration policy.** This design ships a required `V3__session_record_owner.sql` and renumbers the
  unshipped "future indexes" file to V4 in the README. An alternative is to tolerate a missing column by probing the
  schema once at startup. That was rejected in §2.3, but it is a product call on how strict upgrade notes may be.
- **Q5 — `onCreated`.** The issue asks for "at least `onDeleted`". Firing a creation event is harder: provisioning
  happens in several places, and only the backend knows whether a row was newly inserted, so a "created" event would
  need `provision` to report that. The interface leaves room for it (default methods), but this change does not add it.
- **Q6 — A core adapter for sandbox's `SessionOwnerLookup`.** The host can write
  `id -> stack.sessionRecordStore().load(id).flatMap(SessionRecordView::getOwner)` in one line. Should core ship a
  named `SessionOwnerResolver` SPI to make that seam explicit, or is `SessionRecordView.getOwner()` sufficient? This
  design assumes it is sufficient.
- **Q7 — Which queued message supplies the owner on the drain path.** This design uses the first message in
  priority/FIFO order, the same one that supplies the agent binding. If differing owners are queued for an unowned
  session, that is a host bug, and the first message wins silently. An alternative is the first message that carries
  an owner at all.

## 9. After the build — departures and what went to the backlog

*Appended 2026-10-17, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 2's non-blocking notes, and what was
measured while building.*

**No decision in §2 changed.** The owner is a first-wins side field written only by `provision`, explicit on the
submit, carried through the inbox, recorded and not enforced (§2.1). The listener lives in core, fires on the deleting
node from the inner `finally` gated on `recordDeleted`, before `returnLease`, and is registered through the router
builder, `SessionSpec` and Spring beans (§2.2). Q4 was taken as written: V3 is required and the future index file is
now `V4__indexes.sql`.

### 9.1 Where the build departed from the body

- **DV-1 — the in-memory inbox needed a change.** §3 ("Inbox codecs") says `InMemorySessionInbox` needs nothing
  because it holds the object. It does not hold it: `deliver` rebuilds the envelope to stamp the id
  (`rebuildWithId`), copying fields by hand, and the owner was dropped there. One line was added, with a test.
- **DV-2 — Mongo's `F_OWNER` already existed.** `DocumentKeys.F_OWNER = "owner"` is shared with the background-task
  and scheduled-task codecs, so it was reused rather than declared twice. The stored key is `owner`, as §3 says.
- **DV-3 — Redis rejects an empty-string `agentRef`.** §3's empty-string sentinel contradicted the old script
  comment ("an agent named "" could never be confused with no agent"). Nothing upstream rejects `""` before
  `provision` runs — `AgentRuntimeId.fromName` validates only after the claim — so `RedisSessionRecordStore.provision`
  now throws `IllegalArgumentException` for `""`, and the comment says why. The other three stores still accept `""`;
  the uniform fix is backlog SO-6.
- **DV-4 — the pre-delete read cannot block a delete.** §3 reads `store.load(id)` before the delete with no failure
  handling. Review note 2 pointed out that this made a record undeletable once a listener was installed, if the read
  failed. A failed read is now logged at WARN and the listeners fire with an empty owner and binding
  (`DefaultSessionRouter.readRecordForListeners`). This adds a row to §6 in effect.
- **DV-5 — Spring collects listeners through `ApplicationBeans.resolveAll`.** §3 says `ObjectProvider.orderedStream()`.
  `resolveAll` is that plus the destruction edge review note 5 asked for, so a listener holding a connection is
  destroyed after the stack that calls it.
- **DV-6 — the usage section went to the web-session deployment guide.** §3 names
  `docs/features/session/agent-session-guide.md`, which is the `LiveSession` handle guide and never mentions the
  router. "Session owner and deletion listener (optional)" went to `web-session-deployment-guide.md` (+ `.en.md`), which
  also needed the V3 apply step (review note 6). `agent-session-guide.md` is unchanged.
- **DV-7 — `SessionLifecycleListener` takes no lambda.** Its only method is a `default` (§4.1), so it is not a
  functional interface. The design was kept for the reason §2.2 gives; the guide says to implement it as a class, and
  the routing test harness has a `Consumer<SessionDeletedEvent>` overload. See SO-4.
- **DV-8 — the Spring test stops at the spec.** §7 asks for a context whose listener is invoked on delete. The chain is
  covered in two halves instead: `AimonSpecSliceTest.lifecycleListenerBeansReachTheSpec` (beans reach `SessionSpec` in
  `@Order` order) and `AimonStackBuilderTest.lifecycleListenersReachTheRouter` (`SessionSpec` → router →
  `deleteSession` → listener, with the recorded owner).
- **DV-9 — what the multi-node contract cases exercise.** `TwoNodeSessionHarness` shares one in-memory record store
  between its nodes, so the three new `AbstractMultiNodeSessionContractTest` cases exercise each backend's inbox,
  lease store and bus, not its record store. Record-store persistence is covered by each backend's
  `*SessionRecordStoreIntegrationTest` (first-wins, fill, preserved across merge/totals, Mongo `$`-prefixed values).
  The forwarded-owner case runs the holder's drain-only pass over a session it already holds without an owner: a
  forwarded *first* submit needs a race the harness cannot stage deterministically.
- **DV-10 — a few wordings.** `SessionRecordCodec`'s "Four values, not one blob" heading became "Separate values, not
  one blob". `session-log.md` §12, itself a post-build record, got a parenthetical for the V3 → V4 rename rather than an
  edit. `backends.md` §3.1 / §3.4 named only V1 and now list V1–V3.

### 9.2 Review notes: taken, and left

| Note | Outcome |
|------|---------|
| Rule 5.4 says "per successful call" | Taken in the code and the other docs: "once per call that deletes the record". §5 is unchanged |
| The pre-delete `load` is a new failure point | Taken — DV-4 |
| `deleteSegments` already swallows its errors | Taken: the tests throw from the EVICT publish (`firesWhenTeardownThrows`, `teardownExceptionWins`); no segment case |
| Is the lease renewed while a listener runs? | Answered yes: `installLease` attaches the renewer, and `returnLease` runs after the listeners. Stated in the listener javadoc; `runsUnderTheLease` asserts the holder inside the callback |
| No destruction edge for listener beans | Taken — DV-5 |
| `web-session-deployment-guide` lists the DDL | Taken — DV-6 |
| Redis sentinel vs the empty-`agentRef` invariant | Taken as a rejection — DV-3 |
| ArchUnit suites live in different modules | Noted; `checkAll` runs them all, unchanged |

### 9.3 What went to the backlog

The open questions in §8 whose consequences reach past this change are filed in
[`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md): Q1 → SO-1 (enforcement),
Q2 → SO-2 (transfer), Q3 → SO-3 (peer-node fan-out), Q5 → SO-4 (`onCreated`), Q6 → SO-5 (a named owner-lookup SPI),
and DV-3 → SO-6 (blank `agentRef`). Q4 and Q7 were decided as written and are not filed.

### 9.4 After PR #259 review 1

*Appended 2026-10-17.*

- **The listener call no longer guards the lease return by convention alone.** The inner `finally` called
  `fireDeleted` and then `returnLease` in sequence. `fireDeleted` contains only `RuntimeException` (DV-13), so an
  `Error` from a listener skipped `returnLease`. The lease's renewer is stopped only by `returnLease`, and the cache
  entry whose close could have returned it was already evicted. So the node renewed the lease on a deleted id
  indefinitely. `fireDeleted` now sits in a `try` whose `finally` is `returnLease`; test
  `errorFromListenerStillReturnsLease`. DV-13 stands: the `Error` still propagates.
- **The lease guarantee is qualified.** "No new turn can provision the same id and race the cleanup" holds only while
  renewals succeed. The listener javadoc, `routing.md` §7.7 and the guide now say so.
- **DV-7 is softened.** `SessionLifecycleListener.whenDeleted(Consumer)` wraps a lambda. It is not named
  `onDeleted`, because the overload makes `onDeleted(null)` ambiguous. See SO-4.
- **DV-3's uniform fix landed (#260, SO-6 closed).** `SubmitRequest` now refuses, at `build()`, any `agentRef` that
  `AgentRuntimeId.fromName` would refuse: blank, or containing `:`. The review found that the problem was wider than
  DV-3 recorded. It was not that the backends disagreed about `""`. The router claims, and so binds, the session
  before it derives the runtime id, and the binding is first-wins. So any name `fromName` refuses bound the session
  permanently to an agent no turn could open, on every backend. Redis's rejection of `""` stays as a second line of
  defence for callers that use the store without the router.
