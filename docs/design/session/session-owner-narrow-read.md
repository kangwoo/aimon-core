# Design — a narrow read of a session's owner (#262, backlog SO-5)

> Status: **IMPLEMENTED** — `aimon-core` (`SessionRecordStore.loadOwner`, overridden by `InMemorySessionRecordStore`
> and forwarded by `DefaultSessionStore`'s fenced record view), `aimon-bootstrap` (`LateBoundFencedRecordStore`), and
> the three backends (`PostgresSessionRecordStore`, `MongoSessionRecordStore`, `RedisSessionRecordStore`). Records:
> [`session-model.md`](session-model.md) §3.6, and backlog SO-5 (closed), SO-7 and SO-8 in
> [`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md).
>
> **[§9](#9-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §9 is the body as approved in design review round 1
> (PASS, no blocking findings, five non-blocking notes), kept byte-exact rather than corrected — the house habit in
> this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its aimon-core citations are at
> `c2eca4fd`; its aimon-sandbox citations (`SessionOwnerLookup`, `SandboxWorkspaceManager` line numbers) are at
> aimon-sandbox `847dad2`. The review transcript and the run's `TASK.md` are not in the repository; §9 reproduces what
> was found while building and which review notes were taken.
>
> It is in English, like its sibling [`session-owner-deletion-event.md`](session-owner-deletion-event.md);
> `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1).

## 1. Problem

The owner that core records for a session (#253) can only be read through `SessionRecordStore.load(id)`, followed by
`SessionRecordView.getOwner()`. `load` decodes the **whole** record, transcript included. aimon-sandbox's
`SessionOwnerLookup.recorded(...)` does exactly this on every main-turn root bind and on every session-options call.
So each owner check costs time in proportion to the transcript, and it sits on a hot path. The sandbox cannot cache
the answer, because only the deleting node hears about a deletion (SO-3), so a cached owner could be stale for a
re-created session. Core needs a read that returns the owner field alone and never touches the transcript. It must
work on every shipped backend and through every decorating store. Third-party stores must keep compiling and keep
answering correctly.

## 2. Approach

Add one `default` method to `SessionRecordStore`:

```java
/**
 * The owner recorded for the session — {@code load(id).flatMap(SessionRecordView::getOwner)} without reading the
 * rest of the record. ...
 * @implSpec The default is exactly that expression, so a store that does not override it answers correctly and
 *           pays for a full load. A backend that can read the owner on its own should override this. A store that
 *           wraps another must forward it, or the backend's narrow read is lost behind the wrapper.
 */
default Optional<Principal> loadOwner(SessionId sessionId) {
    return load(sessionId).flatMap(SessionRecordView::getOwner);
}
```

Every shipped store overrides it to read only the owner. The two decorators forward it the same way they forward
`load`: unfenced, to the raw or underlying store.

### 2.1 Decisions the task left to the design

**Name: `loadOwner`.** Reads on this interface are named `load` / `exists` / `listSessionIds`. `loadOwner` reads as
"the `load` of one field", and its result is the same `Optional` shape as `load`'s. Rejected: `findOwner`
(no `find*` on this interface), `ownerOf` (that is the sandbox's `SessionOwnerLookup` method, and a store method with
the same name would blur which layer answers), and `getOwner` (that is the view's accessor).

**Scope: owner only.** The only real caller (`SessionOwnerLookup.recorded`) reads the owner and nothing else. The
other core read that wants side fields is `DefaultSessionRouter`'s delete path (`recordBeforeDelete`, which wants
`agentRef` and `owner` for the `SessionDeletedEvent`). It runs once per delete, and the #253 design already accepted
a full load there. A broader read, for example a `SessionRecordHeader { agentRef, owner }`, would add a new public
value type, a second codec path in every backend, and a decision about which side fields belong in it, all for a
caller that does not exist. If a second narrow read is ever needed, it can sit next to this one. Nothing in this
shape blocks that.

**Semantics: "no such session" and "session with no owner" are both `Optional.empty()`.** The sandbox does not need
to tell them apart:

- `SessionOwnerLookup.ownerOf` returns `Optional<Principal>`. Its javadoc says it is "empty when unknown (the binding
  is then rejected)".
- `recorded(...)` is documented as "empty for a session without one or without a record".
- `orElse(fallback)` asks the fallback in both cases. That is what lets legacy sessions (record present, no owner)
  fall through to the host's own lookup.
- `DefaultSandboxBindingPolicy` and `SandboxWorkspaceManager` (lines 76, 1258 and 1491) only `map` the `Optional`.

The `require-principal` path refuses an empty answer whatever the cause. So the conflated shape matches today's
behaviour exactly: `load(id).flatMap(getOwner)` is also the default. A caller that does need to tell the cases apart
can still call `exists(id)`. A tri-state return type was rejected as surface without a caller.

### 2.2 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Separate `SessionOwnerResolver` SPI | Already decided in the task: the store holds the owner, and a second SPI is one more thing for the host to wire. |
| Abstract method (no default) | Breaks every third-party store at compile time. The interface's "no defaults" rule exists so that no non-atomic default is ever written, and this is a read, so that rule does not apply. The fallback is correct, just slower. |
| Default that throws `UnsupportedOperationException` (as the owner-carrying `provision` does) | That default throws because silently dropping a write loses data. Here the fallback gives the right answer, so throwing would only break readers for no gain. |
| Read `agentRef` and owner together (`SessionRecordHeader`) | No caller. See "Scope" above. |
| Make `load` lazy (decode the transcript on first access) | It changes the decode-failure contract of `load`: "a row that will not decode is a failure, not an empty history" would then fail late, inside the caller. It also touches `StoredSessionRecord` and all three backends' codec paths. Much larger for the same win. |
| Also add `loadOwner` to `SessionStore` (the composite) | The sandbox wires a `SessionRecordStore` (`WorkspaceSandbox.Builder.sessionRecords(...)` / `AimonStack.sessionRecordStore()`), not a `SessionStore`. `SessionStore.load` has no owner caller. Not needed. |

## 3. Changes by module and file

### aimon-core

- `at/aimon/core/agent/session/store/SessionRecordStore.java`
  - Add `default Optional<Principal> loadOwner(SessionId)` next to `load`, with full javadoc. The javadoc covers
    the null check, what empty means (no record or no owner), "unfenced, like `load`", the decode-failure rule (§6),
    and the `@implSpec` for overriding and forwarding.
  - The default calls `Objects.requireNonNull(sessionId, ...)` before it delegates, so the NPE contract does not
    depend on the store's `load`.
  - The class-level javadoc paragraph "Reads return `SessionRecordView` … see `load`" gains one sentence: the owner
    can be read on its own through `loadOwner`.
  - This also adds one public API element. It needs javadoc for the `checkJavadocCoverage` ratchet.
- `at/aimon/core/agent/session/store/InMemorySessionRecordStore.java`: override it. Do
  `storage.get(id)`, then return `record == null ? Optional.empty() : record.getOwner()`. No deep copy, because
  `Principal` is immutable. This is the point of the override: `load` deep-copies the message list.
- `at/aimon/core/agent/session/store/DefaultSessionStore.java`, inner class `FencedRecords`. Both the
  `HOLDER_ONLY` and `UNLESS_HELD_ELSEWHERE` instances use this class. Override it as
  `return repository.loadOwner(sessionId);`, with no `guard.check`, mirroring `load`. Add the same one-line comment
  the three-argument `provision` override carries: "Overridden although the interface has a default: inheriting it
  would turn the backend's narrow read into a full load."

### aimon-bootstrap

- `at/aimon/bootstrap/assemble/LateBoundFencedRecordStore.java`: override it as
  `return raw.loadOwner(sessionId);`. Reads go to the raw store from the start, as `load` / `exists` /
  `listSessionIds` already do. This works before `bind`. Add the same "forwarded rather than inherited" comment.

### aimon-session-postgres

- `PostgresSessionRecordStore.java`
  - Add `SQL_LOAD_OWNER = "SELECT owner FROM session_record WHERE session_id = ?"`.
  - Override with the same connection, statement and `failure("loadOwner", ...)` structure as `load`.
    - No row: `Optional.empty()`.
    - Otherwise: `Optional.ofNullable(SessionRecordCodec.decodeOwner(rs.getString("owner")))`.
  - Wrap a decode `RuntimeException` in `SessionRecordStoreException("Failed to decode stored session owner for " + id, e)`.
  - Class javadoc: add `loadOwner` to the list of statements next to load.
  - No schema change. `owner` (jsonb) arrived in V3.

### aimon-session-mongodb

- `MongoSessionRecordStore.java`: override it as
  `collection.find(byId(id)).projection(new Document(DocumentKeys.F_OWNER, 1)).first()`.
  - This is the same projection idiom `exists` / `listSessionIds` use.
  - No document: `Optional.empty()`.
  - Otherwise: `Optional.ofNullable(decodeOwner(json(doc, DocumentKeys.F_OWNER)))`. The projected document carries
    `_id` and, when present, `owner`.
  - Decode failure: `SessionRecordStoreException` as above. `MongoException`: `failure("loadOwner", ...)`.
  - No index or schema change: `_id` lookup.

### aimon-session-redis

- `RedisSessionRecordStore.java`: override it as `commands.hget(recordKey(id), F_OWNER)`, then
  `Optional.ofNullable(SessionRecordCodec.decodeOwner(raw))`.
  - `HGET` returns `null` for both a missing key and a missing field, and both mean empty.
  - **The `""` sentinel from #253:** `""` is an ARGV-only marker for "no owner" in `PROVISION_SCRIPT`, and the
    script never writes it into the hash (`if ARGV[3] ~= ''`). If a hash ever did hold `owner = ""` (hand-written
    data, an older script), `decodeOwner` already maps a blank value to `null`. So `loadOwner` answers empty, as
    `load` does. No extra branch is needed. The design states this in a code comment and pins it with a test.
  - `RedisException`: `failure("loadOwner", ...)`. Decode failure: `SessionRecordStoreException`.
  - Class javadoc "Reads" list: add `HGET owner`.

### aimon-session-testkit

- `AbstractMultiNodeSessionContractTest`: in the two existing owner tests (`ownerSurvivesAHolderMove`, the
  forwarded-owner test), add `assertThat(nodes.sharedRepository().loadOwner(id)).contains(alice)` next to the existing
  `load(...).getOwner()` assertion. This is the only suite that every backend already runs. It holds each backend's
  override to the same answer as `load`.

### Out of this repo (not changed here)

- aimon-sandbox `SessionOwnerLookup.recorded(...)` should switch to `records.get().loadOwner(sessionId)`, and the
  "core has no narrower read yet" sentence should go. That happens in aimon-sandbox after a core release that ships
  this method. See open question Q2.

### Docs and changelog

- `docs/backlog/session-owner-open-items.md`
  - Mark SO-5 `· **닫힘** *(2026-10-18, #262)*` and add a `**닫음 (2026-10-18, #262).**` paragraph. It records that
    a narrow read on `SessionRecordStore` closes the cost half of SO-5, and that the "named SPI" half was decided
    against: the store already holds the owner.
  - Update the title count: `6건 (열림 4 · 닫힘 2)`.
- `docs/backlog/README.md`: index row `6 | 5 | 1 | 0` becomes `6 | 4 | 2 | 0`, and the source column appends
  `· SO-5 닫음, #262 (2026-10-18)`. Verify with `python3 scripts/check-backlog-registers.py`.
- `docs/design/session/session-model.md` §3.6: add a bullet, **읽는 곳** (where it is read). It says the owner can
  be read without the record through `SessionRecordStore.loadOwner(id)`, and that wrappers forward it.
- `docs/design/session/session-owner-deletion-event.md`: add a short §9.5 note (or a line in §9.3) that SO-5 closed
  with #262. Optional. The backlog is the canonical place for open and closed status, per that register's header.
- `CHANGELOG.md` `[Unreleased]`: add `### Added: read a session's owner without its transcript — SessionRecordStore#loadOwner (#262)`,
  with bullets: opt-in default (third-party stores unchanged and correct), per-backend reads, forwarded through the
  fenced views, empty for both no record and no owner.
- Translations: none of the touched docs has a `*.en.md`. I checked `docs/design/session/` and `docs/backlog/`.
  `CHANGELOG.md` has no `.ko.md`. Nothing else to keep in step.

## 4. Data and interface shapes

| Shape | Change |
|---|---|
| `SessionRecordStore` | New `default Optional<Principal> loadOwner(SessionId)`. Binary- and source-compatible for implementers and callers. |
| `SessionRecordView`, `StoredSessionRecord`, `SessionRecordCodec` | Unchanged. The backends reuse `decodeOwner`. |
| Persisted shapes (Postgres `owner` jsonb, Mongo `owner` sub-document, Redis `owner` hash field) | Unchanged. No migration and no new index. |
| `SessionStore` | Unchanged. |

Contract (javadoc), stated once:

- Returns the same value as `load(id).flatMap(SessionRecordView::getOwner)` whenever both succeed.
- `Optional.empty()` means there is no record **or** no recorded owner.
- Throws `NullPointerException` for a null id.
- Not fenced. Like `load`, it may read a session another node holds.
- Point-in-time. No caching or consistency guarantee beyond the single read `load` gives.

## 5. Behavioural rules

- **Fencing and lease:** identical to `load`. Both decorators already route `load` unfenced, to the raw store
  (`LateBoundFencedRecordStore`) or to the repository (`FencedRecords`), and they do the same for `loadOwner`.
  There is no write, so there is no lease check.
- **Before `bind`:** `LateBoundFencedRecordStore.loadOwner` works, as `load` does. It never throws
  `IllegalStateException`.
- **Atomicity:** a single-statement read in every backend. Each one reads only the owner, so there is no torn read
  across fields. Under the owner's first-wins rule an owner never changes once set, so a concurrent `provision` can
  only move the answer from empty to present.

## 6. Failure modes

| Failure | Handling |
|---|---|
| Backend error (`SQLException`, `MongoException`, `RedisException`) | `SessionRecordStoreException("<Backend> error during loadOwner for <id>")`, the same `failure(...)` helper `load` uses. It propagates to the caller. The sandbox already documents "a store failure propagates". |
| Stored owner will not decode | `SessionRecordStoreException("Failed to decode stored session owner for <id>")`. Never reported as empty: an empty answer would let a corrupt owner pass as "no owner" and fall through to the host fallback (`orElse`). That is the same rule `load` applies to a corrupt row. |
| **Transcript or another side field is corrupt** | **Deliberate difference from `load`:** `loadOwner` does not read those fields, so it succeeds and returns the owner, while `load` throws. This is in the javadoc. It is the correct behaviour for an owner check (the owner is intact), and it is also the observable the integration tests use to prove that no transcript decode happens. |
| Third-party store without an override | The default goes through `load`. The answer is correct, the cost is the old cost, and a corrupt transcript makes it throw as before. |
| A future decorator forgets to forward | Answers stay correct but slow. The reflective test in §7 catches this for the shipped decorators. |
| Redis `owner = ""` in the hash | Decodes as no owner, the same as `load`. |

## 7. Test strategy

**Unit (`./gradlew test`, in `checkAll`)**

- `SessionRecordStoreOwnerDefaultTest` (core). Extend this existing test class for owner-related defaults with:
  - a mock with `CALLS_REAL_METHODS`, `load` stubbed to a view with an owner → `loadOwner` returns it;
  - `load` stubbed empty → empty;
  - `load` stubbed to a view with no owner → empty;
  - a null id → NPE.
- `InMemorySessionRecordStoreTest`:
  - owner present, absent, and no record;
  - the override does not call `load`. Use a subclass whose `load` throws `AssertionError` and show `loadOwner`
    still answers.
- `DefaultSessionStoreTest`:
  - **behavioural forward test:** a repository subclass of `InMemorySessionRecordStore` whose `load` throws, reached
    through `records()` and `records(UNLESS_HELD_ELSEWHERE)`, without holding a lease. `loadOwner` returns the owner.
    It fails if `FencedRecords` inherits the default (the default would call the throwing `load`), and it fails if a
    guard is added.
  - **reflective guard:** every `default` method of `SessionRecordStore` is declared on `records().getClass()`,
    except for an explicit exemption list. Today the list is `provision(SessionId)`, which `FencedRecords` inherits
    and whose default delegates to the guarded two-argument `provision`. This is the `LeasedLiveSessionTest` pattern.
    It catches the next default nobody forwards. The exemption list states why each entry is safe.
- `LateBoundFencedRecordStoreTest`:
  - add `loadOwner` to `readsGoRaw`, before and after `bind`;
  - the same throwing-`load` behavioural test (raw store subclass);
  - the same reflective guard. `LateBoundFencedRecordStore` overrides every default today, so its exemption list is
    empty.

**Integration (`./gradlew integrationTest`, `@Tag("docker")`)**: in each of `PostgresSessionRecordStoreIntegrationTest`,
`MongoSessionRecordStoreIntegrationTest` and `RedisSessionRecordStoreIntegrationTest`:

1. `loadOwner` on a missing record → empty. After `provision(id, "agent-A")` → empty. After
   `provision(id, "agent-A", alice)` → `alice`, display name preserved.
2. **No transcript decode.**
   - Provision with an owner.
   - Then overwrite the transcript directly in the backend with a value `SessionRecordCodec.decodeTranscript` rejects
     (for example `"not-a-transcript"`). Postgres: `UPDATE session_record SET transcript = ?` (the column is
     `text`). Mongo: `updateOne($set transcript)`. Redis: `HSET … transcript`.
   - Assert that `load(id)` throws `SessionRecordStoreException` and `loadOwner(id)` returns the owner.
   - This is the observable the acceptance criterion asks for. A full-record read cannot pass it.
3. Corrupt owner (raw-write a malformed owner JSON object) → `loadOwner` throws `SessionRecordStoreException`.
   Not empty.
4. Redis only: `HSET owner ""` → `loadOwner` returns empty, matching `load(...).getOwner()` (the `""` sentinel).

**Multi-node contract (testkit)**: the `loadOwner` assertions in §3 run against all three backends through their
existing `*MultiNode*` contract subclasses.

**Gate:** `./gradlew checkAll`, then `./gradlew integrationTest` for the postgres, mongodb, redis and testkit
consumers, then `python3 scripts/check-backlog-registers.py` and `python3 scripts/check-doc-links.py`.

## 8. Open questions

- **Q1 — Record-store contract suite.** The task asks to add the method to "the record-store contract suite if it
  exists". It does not: `aimon-session-testkit` has contracts for segment stores, inboxes, scheduled tasks and
  multi-node sessions, but not for `SessionRecordStore` alone. Each backend's record-store integration test stands
  on its own. This design adds the per-backend tests and the multi-node assertions, and does **not** extract a new
  `AbstractSessionRecordStoreContractTest`, because that is a refactor of three test classes beyond this issue.
  Confirm, or ask for the extraction as follow-up work.
- **Q2 — Sandbox follow-up and version floor.** aimon-sandbox gets the benefit only after it calls `loadOwner`,
  which needs a core release that contains it. Should a matching issue be filed in kangwoo/aimon-sandbox (against
  #27), and in which core version is this expected to ship? The design does not change the other repo.
- **Q3 — Close SO-5 fully?** SO-5's title asks for a "named SPI", and its body asks for a narrow read. This design
  closes it on the task's decision (no separate SPI, a narrow read on the store). If the register should instead
  keep a residual item, for example "a narrow read of `agentRef` + owner for the delete path", say so. The design
  does not open one, because no caller pays for that cost on a hot path.
- **Q4 — Should the delete path in `DefaultSessionRouter` use `loadOwner`?** It reads `recordBeforeDelete` for both
  `agentRef` and owner, so `loadOwner` alone does not serve it. This design leaves it on `load` (no change).

## 9. After the build — departures and what went to the backlog

*Appended 2026-10-18, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 1's non-blocking notes, and what was
found while building.*

**No decision in §2 changed.** The read is one `default` method, `loadOwner`, on `SessionRecordStore`. It reads the
owner only, and it answers empty both when there is no record and when the record has no owner. Every shipped store
overrides it, and both decorators forward it unfenced, as they forward `load`.

### 9.1 Where the build departed from the body

- **DV-1 — the multi-node testkit assertions do not reach the backends' record stores.** §3 (aimon-session-testkit)
  and §7 say that the two `loadOwner` assertions in `AbstractMultiNodeSessionContractTest` hold each backend's
  override to `load`'s answer. They do not. `TwoNodeSessionHarness` builds both nodes over one
  `InMemorySessionRecordStore`, so every backend run exercises the in-memory override. #253 recorded the same fact
  ([`session-owner-deletion-event.md`](session-owner-deletion-event.md) §9.1 DV-9) and this design missed it. The
  assertions stay, as a cheap check that an owner recorded through the router reads back through `loadOwner`. The
  backend coverage is each backend's own `*SessionRecordStoreIntegrationTest` (§7, "Integration"). That makes Q1 more
  pressing than §8 says, so it went to the backlog (§9.3).
- **DV-2 — there was no "reads" list in the backend javadocs.** §3 says to add `loadOwner` to the Postgres "list of
  statements next to load" and to the Redis "Reads" list. All three class javadocs list only writes. Each backend now
  has a short `Reads` section before `Fencing`, with the same wording in all three. It says that `load` decodes
  every field, that `loadOwner` reads the owner alone (a single column, a projection, an `HGET`), and that a corrupt
  transcript fails `load` but not `loadOwner`.
- **DV-3 — a private `toOwner` per backend.** The decode and its `SessionRecordStoreException("Failed to decode stored
  session owner for <id>")` (§6) live in one private helper per backend, worded the same in all three. This changes
  the shape only, not the behaviour.

### 9.2 Review notes: taken, and left

| Note | Outcome |
|------|---------|
| Mongo's `json(...)` cast can throw `ClassCastException` outside the decode `try` | Taken. `json(doc, F_OWNER)` runs inside `toOwner`'s `try`, and the integration test also covers an owner stored as a plain string |
| Each override should do its own `requireNonNull` | Taken. The in-memory override and all three backends check the id themselves, with the same message as their `load` |
| Use one decode-failure message in all three backends | Taken. DV-3 |
| Host-facing guides might say that a wrapper must forward `loadOwner` | Left. Neither `web-session-deployment-guide.md` nor `agent-session-tutorial.md` lists the methods to implement or forward, so there is no list to extend. The `@implSpec` on `loadOwner` says it |
| Add a "SO-5 closed by #262" line to `session-owner-deletion-event.md` §9.3 | Taken, as a §9.5 note. §9.3 is a dated record and is not edited |

### 9.3 What went to the backlog

The open questions in §8 whose consequences reach past this change are filed in
[`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md):

- Q1 → SO-7 (no standalone record-store contract suite). DV-1 makes this more pressing.
- Q2 → SO-8 (aimon-sandbox still pays for a full load until it calls `loadOwner`).

Q3 was decided as written: SO-5 is closed in full, and no residual item was opened. Q4 was decided as written: the
delete path stays on `load`. Neither was filed.
