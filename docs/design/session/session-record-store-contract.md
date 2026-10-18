# Design — a shared `SessionRecordStore` contract suite (#266, backlog SO-7)

> Status: **IMPLEMENTED** — tests only. `aimon-session-testkit` (`AbstractSessionRecordStoreContractTest`, run there
> by `InMemorySessionRecordStoreContractTest` and `FencedSessionRecordStoreContractTest`), `aimon-bootstrap`
> (`LateBoundFencedRecordStoreContractTest`), and the three backends, whose `*SessionRecordStoreIntegrationTest`s now
> extend the suite and keep only their backend-specific cases. No production type changed. Records: backlog SO-7
> (closed) and SO-9 in [`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md).
>
> **[§10](#10-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §10 is the body as approved in design review
> round 1 (PASS, no blocking findings, six non-blocking notes), kept byte-exact rather than corrected — the house
> habit in this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its citations ("as of
> 2026-10-18") are at `ea521169`. The review transcript and the run's `TASK.md` are not in the repository; §10
> reproduces what was found while building and which review notes were taken.
>
> It is in English, like its siblings [`session-owner-narrow-read.md`](session-owner-narrow-read.md) and
> [`session-owner-deletion-event.md`](session-owner-deletion-event.md); `docs/design/` is not a translation target
> (`docs/project/documentation-guide.md` §5.1).

## 1. Problem

`SessionRecordStore` has four shipped implementations (`InMemorySessionRecordStore` in `aimon-core`; Postgres, MongoDB
and Redis in their backend modules) and two shipped decorators (`DefaultSessionStore`'s private `FencedRecords` view
in `aimon-core`, and `LateBoundFencedRecordStore` in `aimon-bootstrap`). Each of the three backends proves the SPI's
contract with its own `*SessionRecordStoreIntegrationTest` (Postgres 17, Mongo 19, Redis 21 `@Test`s as of
2026-10-18). Those tests restate the same cases nearly word for word, but they have already started to drift apart.
Mongo and Redis check arbitrary tool-input keys and Postgres does not. Only Postgres checks NUL bytes. Only Redis
checks ids that contain `:`. Only Mongo checks `$`-prefixed values. The multi-node contract suite cannot fill the gap,
because `TwoNodeSessionHarness` shares one `InMemorySessionRecordStore` between its two nodes, so a backend run never
reaches the backend's record store. The decorators run none of these cases. So when a new contract method (the way
`loadOwner` arrived in #262) or a new backend is checked in one place and missed elsewhere, the build stays green.
SO-7 asks for one abstract suite that every shipped store runs. Backend-specific cases stay in the backend tests, and
no assertion is lost.

## 2. Approach

**Add `AbstractSessionRecordStoreContractTest` to the existing, unpublished `aimon-session-testkit`, in package
`at.aimon.session.testkit`.** This is the same shape as `AbstractSessionLogSegmentStoreContractTest` and
`AbstractScheduledTaskRepositoryContractTest`, which already live there:

- an abstract `protected SessionRecordStore store()` that returns a store over an emptied backend. Emptying the backend
  is the subclass's job, done in its own `@BeforeEach`, as the segment contract already requires;
- a few `protected` hooks with safe defaults for the cases that need more than the SPI (§4);
- each subject joins with one subclass:
  - `InMemorySessionRecordStoreContractTest` in testkit's `src/test`, the precedent set by
    `InMemorySessionLogSegmentStoreContractTest`. It runs daemonless in the unit tier (`./gradlew test`);
  - `FencedSessionRecordStoreContractTest` in testkit's `src/test`, over
    `new DefaultSessionStore(new InMemorySessionLeaseStore(), new InMemorySessionRecordStore()).records(SessionFence.UNLESS_HELD_ELSEWHERE)`.
    No lease is held, so every guard passes, and what the suite checks is that each method forwards;
  - `LateBoundFencedRecordStoreContractTest` in `aimon-bootstrap`'s `src/test`, built over a raw in-memory store and
    bound to the fenced view above (this means adding `testImplementation(project(":aimon-session-testkit"))` to
    bootstrap);
  - the three existing backend classes, which keep their names and `@Tag("docker")` and now `extends
    AbstractSessionRecordStoreContractTest`. Each keeps only its backend-specific cases.

For the decorators, the suite catches a real class of bug. If a wrapper inherits the interface default for
`provision(id, agent, owner)`, any call that passes an owner throws `UnsupportedOperationException`. If a wrapper
inherits the default for `loadOwner`, it still returns correct answers but quietly turns the narrow read into a full
`load`. The suite catches the first. It does not catch the second, which is left to the existing per-decorator tests
(see §7, open question Q4).

### 2.1 Where it lives — alternatives rejected

| Option | Rejected because |
|---|---|
| **Publish a new `aimon-session-record-testkit`** (the `aimon-memory-testkit` precedent) | `aimon-memory-testkit` is published for one reason, and its build script states it: the backend that most needs the contract, `RemotePeerMemory`, lives in another repository. Today every `SessionRecordStore` lives in this repository. aimon-sandbox *calls* `loadOwner` but implements no store (checked 2026-10-18: no `implements SessionRecordStore` in `kangwoo/aimon-sandbox`). A Maven Central publish cannot be recalled, and every `api` dependency becomes public API (`api-stability.md` §5). Publishing for a hypothetical third-party store would pay a permanent cost to solve a problem that does not exist yet. The rule written in `settings.gradle.kts` and `PublishedModuleApiScopeTest` (publish a testkit only when one of its subjects is outside the repo) decides this, and this design follows that rule. |
| **Publish `aimon-session-testkit` as a whole** | The same objection applies, made larger. It would export `TwoNodeSessionHarness`, `RecordingTestSession`, a cast to `DefaultSessionRouter` (an accepted internal-boundary exception in `InternalPackageBoundaryTest`), `aimon-session-routing` on `api`, and logback, none of which a third-party record store needs. |
| **`java-test-fixtures` on `aimon-core`** | Ruled out by the precedent: under the publishing plugin it fails configuration (see `aimon-filesystem-testkit/build.gradle.kts`, backlog R-5). |
| **A new, unpublished `aimon-session-record-testkit`** | This is no better than the existing testkit, which already depends on `aimon-core`, JUnit and AssertJ and already holds the other session-SPI contracts. A new module would add one more entry to `settings.gradle.kts`, the module list and the conventions without separating anything. |

The suite's class imports only `aimon-core` types, JUnit and AssertJ, and nothing from `aimon-session-routing` or
logback. If a store outside this repository ever appears, the class can move unchanged into a published module. That
condition goes into the SO-7 closing paragraph as the "when to revisit" line.

Consequences for the task's acceptance criteria: no module is added and none is published, so `aimon-bom`,
`verifyBom` and CLAUDE.md's module list do not change. (CLAUDE.md's module tree already leaves out the unpublished
`aimon-session-testkit`, and this design does not change that.)

### 2.2 Other choices rejected

- **Delete the per-backend classes and create new `*SessionRecordStoreContractTest` subclasses.** Rejected. Keeping
  the class names keeps git history, keeps the `integrationTest` selection by tag, and leaves backend-only cases where
  they already are.
- **Run the suite on `HOLDER_ONLY` by claiming every id first.** Rejected. It would wrap each SPI call in a claim, so
  the suite would no longer be about the record store. `UNLESS_HELD_ELSEWHERE` is the same `FencedRecords` class
  with a different guard, so forwarding gets the same coverage. Holdership rejection is already tested in
  `DefaultSessionStore`'s own tests.
- **Make the corruption hooks abstract.** Rejected. `InMemorySessionRecordStore` stores objects, so it has no encoded
  form that could be corrupted. An abstract hook would force a fake implementation there. Instead, the default aborts
  the test through a JUnit assumption, so it is reported as *skipped* rather than passed.
- **Remove the cases from `InMemorySessionRecordStoreTest` that now overlap.** Not done. That test (about 50 `@Test`s)
  mostly checks things specific to the in-memory store (concurrency, copy semantics). Pruning it is a separate cleanup
  and would weaken the "do not lose coverage" guarantee. See Q3.

## 3. Rule for "suite or backend"

A case moves into the suite when **its assertions use only the `SessionRecordStore` SPI and the property they state
holds for every store.** It stays in the backend test when it needs raw backend access (`HEXISTS`, a raw `UPDATE`, a
raw `$set`) or when it describes one backend's encoding. Several cases that a single backend started for its own
reasons ("`$` is a Mongo field path", "`:` is a Redis key delimiter", "jsonb rejects NUL") assert SPI-only properties
that hold for every store, namely that a value goes in and comes back verbatim. By the rule, they move into the suite.
That gives the other backends coverage they did not have before. Where a case mixes SPI assertions with raw ones (most
Redis cases), it is split: the SPI half goes to the suite, and the raw assertion stays as a short backend test.

## 4. Interface shapes

New public abstract class in `modules/aimon-session-testkit/src/main/java/at/aimon/session/testkit/`:

```java
public abstract class AbstractSessionRecordStoreContractTest {

    /** @return the store under test, over an emptied backend */
    protected abstract SessionRecordStore store();

    /**
     * A second instance over the same backend, sharing nothing in-process with store() — another pool, connection
     * or client — as a different node would. Default: store() itself (an in-memory store has no other instance).
     * A subclass that opens a resource here closes it in its own @AfterEach.
     */
    protected SessionRecordStore peerStore() { return store(); }

    /**
     * Overwrites the stored transcript of an existing record with bytes load() cannot decode.
     * Default: aborts the calling test (Assumptions.abort) — a store with no encoded form cannot be corrupted.
     */
    protected void corruptTranscript(SessionId id) { abort(...); }

    /** Same, for the stored owner: a value of the right storage type that the owner codec rejects. */
    protected void corruptOwner(SessionId id) { abort(...); }

    /** Whether clear() is supported. The fenced views refuse it by design. Default: true. */
    protected boolean supportsClear() { return true; }

    // @Test methods — §5
}
```

No production type changes: `aimon-core`, the backends and the decorators are untouched. Build-script changes:

- `modules/aimon-bootstrap/build.gradle.kts`: add `testImplementation(project(":aimon-session-testkit"))`.
- `aimon-session-testkit/build.gradle.kts`: no change, because `api(project(":aimon-core"))` already supplies every
  type the suite uses.

Backend subclass shape, using Postgres as the example (Mongo and Redis follow the same pattern):

```java
@DisplayName("PostgresSessionRecordStore integration")
@Tag("docker")
class PostgresSessionRecordStoreIntegrationTest extends AbstractSessionRecordStoreContractTest {
    private PostgresSessionRecordStore store;
    private HikariDataSource peerPool;            // opened lazily by peerStore(), closed in @AfterEach

    @BeforeEach void setUp() { PostgresTestSupport.truncateAll(); store = new PostgresSessionRecordStore(...); }
    @Override protected SessionRecordStore store() { return store; }
    @Override protected SessionRecordStore peerStore() { ... isolatedDataSource(2) ... }
    @Override protected void corruptTranscript(SessionId id) { rawUpdate("... transcript = ? ...", "not-a-transcript", id); }
    @Override protected void corruptOwner(SessionId id) { rawUpdate("... owner = ?::jsonb ...", "{\"displayName\":\"x\"}", id); }
    // backend-only @Tests (§5.2)
}
```

The class-level `@Tag("docker")` applies to inherited test methods too. The segment-store subclasses already depend
on this, so `./gradlew test` keeps excluding the backends and `integrationTest` keeps selecting them.

## 5. Coverage mapping

The implementer copies this table into `build/summary.md` with the final method names. "P / M / R" are the current
Postgres, Mongo and Redis methods (as of 2026-10-18).

### 5.1 Into the suite

| Suite case | Comes from | Notes |
|---|---|---|
| `provisionBindsOnce` | P/M/R `provisionBindsOnce` | identical in all three |
| `provisionRecordsOwnerOnce` | P/M/R `provisionRecordsOwnerOnce` | includes the `displayName` round trip on the view and on `load`, as in P/R; M's `getOwner()…getDisplayName()` after `load` is kept |
| `ownerFillsWhenEmpty` | P `ownerFillsWhenEmpty`, M `provisionFillsMissingOwner`, R `ownerFillsWhenEmpty` (SPI half) | union: an explicit `provision(id, a, null)` keeps the owner empty (M); a later owner-carrying provision with a *different* agent fills the owner and leaves the binding alone (M); a later owner-less provision does not clear it (P) |
| `ownerWithoutBinding` | P `ownerWithoutBinding`, M `provisionOwnerWithoutBinding`, R `eachOptionalArgumentIsIndependent` (first half, SPI) | binding later leaves the owner alone; uses a `SERVICE` principal as P does |
| `bindingWithoutOwner` | R `eachOptionalArgumentIsIndependent` (second half, SPI) | `provision(id, a, null)` binds and records no owner |
| `ownerSurvivesOtherWriters` | P `ownerSurvivesOtherWriters`, M same, R `ownerSurvivesOtherWrites` | union of writes: merge, totals with an override, then totals with `null`, increment, reset; checks the owner and its `displayName` after the merge (R) and at the end |
| `provisionWithoutBinding` | P/M/R `provisionWithoutBinding` (SPI half) | all six default fields + `exists` |
| `transcriptRoundTripLeavesSideFieldsAlone` | P/M/R same name | identical |
| `nulBytesSurvive` | P `nulByteInToolInputSurvives` | tool-input value and system prompt; **new for M and R** |
| `toolInputKeysSurviveVerbatim` | M `toolInputKeysThatBsonWouldReject`, R `arbitraryToolInputKeysSurvive` | **new for P** |
| `dollarPrefixedValuesAreData` | M `provisionBindsDollarPrefixedAgentRef`, M `provisionStoresDollarPrefixedOwner` | **new for P and R** |
| `totalsAndOverrideMoveTogether` | P/M/R same name (SPI half) | |
| `sideFieldWritesDoNotCreateRecords` | P `sideFieldWritesDoNotCreateRows`, M/R `sideFieldWritesDoNotCreateRecords` (SPI half) | |
| `concurrentIncrementsAreDistinct` | P/M/R same name | identical |
| `nodeHandoffCarriesTheTranscript` | P/M/R same name | node B is `peerStore()` |
| `enumerationAndRemoval` | P/M/R same name, without the `clear()` lines | |
| `clearRemovesEveryRecord` | the `clear()` lines of P/M/R `enumerationAndRemoval` | when `!supportsClear()`, asserts `UnsupportedOperationException` and that the records survive |
| `idsRoundTripThroughEnumeration` | R `sessionIdsContainingColons` | **new for P and M** |
| `provisionedButNeverWritten` | P/M/R same name | |
| `loadOwnerReadsTheOwner` | P/M/R same name | identical |
| `loadOwnerDoesNotDecodeTheTranscript` | P/M/R same name | through `corruptTranscript`; skipped for in-memory and the decorators |
| `loadOwnerRefusesACorruptOwner` | P/M/R same name (M: the `malformed` half) | through `corruptOwner`; keeps the message check `"Failed to decode stored session owner for"` |

### 5.2 Stays in the backend test

| Backend | Case | Why it stays |
|---|---|---|
| Redis | `provisionWithoutBinding` → renamed `provisionWithoutBindingWritesTheSentinelField` | `HGET sessionId` sentinel and `HEXISTS agentRef` |
| Redis | `ownerFillsWhenEmpty` raw half → `ownerlessProvisionWritesNoOwnerField` | `HEXISTS owner` |
| Redis | `eachOptionalArgumentIsIndependent` raw half | the two `HEXISTS` checks on positional `""` arguments |
| Redis | `emptyAgentRefIsRefused` | the `""` sentinel is this script's reservation |
| Redis | `totalsAndOverrideMoveTogether` raw half → `nullOverrideIsAnHdel` | `HEXISTS budgetOverride` |
| Redis | `sideFieldWritesDoNotCreateRecords` raw half → `sideFieldWritesLeaveNoKey` | `EXISTS` on the key |
| Redis | `enumerationIgnoresForeignKeys` | shared keyspace, raw `SET` |
| Redis | `loadOwnerTreatsTheEmptySentinelAsNoOwner` | raw `HSET owner ""` |
| Mongo | `loadOwnerRefusesAnOwnerThatIsNotADocument` (the `notADocument` half of `loadOwnerRefusesACorruptOwner`) | BSON type-cast path |
| Postgres | none | the Postgres migration tests are already separate classes and are not touched |

The suite will have 22 cases. The backend tests keep 8 / 1 / 0 backend-only cases (R / M / P). Every assertion in the
current files appears in one of the two tables above, and the build step checks this by diffing the old files against
the new ones assertion by assertion.

## 6. Failure modes

| Failure | Handling |
|---|---|
| A case that moved to the suite fails on a backend that never ran it (`nulBytesSurvive` on Mongo/Redis, `$` values on P/R, `:` ids on P/M) | The suite is doing its job and has found a real divergence. Do not weaken the assertion or override it away. Fix the backend in this change if the fix is small. Otherwise file a backlog item and stop for a human decision. In either case, record it in `build/summary.md`. Expected outcome is pass: both are binary-safe strings, and the segment contract already round-trips `\u0000` and `$key.dot` on all backends. |
| `listSessionIds` assertions see leftovers from another test | The suite asserts exact lists, so each subclass's `@BeforeEach` must empty the backend. Each suite case also uses its own ids. |
| A peer resource leaks (Mongo `newClient()` is never closed today) | The subclass keeps the peer in a field and closes it in `@AfterEach`. This fixes the existing Mongo leak as a side effect. |
| A corruption hook is skipped silently and looks like a pass | `Assumptions.abort` reports it as *skipped*. The backend subclasses must override both hooks, and the build step confirms the two `loadOwner…` corruption cases run (not skip) in each backend's `integrationTest` report. |
| A decorator's `clear()` refusal breaks the `enumerationAndRemoval` setup | `clear()` has its own case behind `supportsClear()`. No other case calls `clear()`. |
| Fenced-view guard calls slow the concurrency case | `InMemorySessionLeaseStore.findHolder` is a map lookup, so the cost is small. The 30-second future timeout stays. |
| `checkTestClasspathVersions` flags bootstrap after it gains the testkit | The testkit brings `junit-bom`, `libs.bundles.testing` and logback, all of which bootstrap's tests already resolve. If the check still flags it, follow the documented route (`gradle/test-classpath-version-differences.txt`) or drop the bootstrap subclass, and record which in the summary. |
| Checkstyle and the javadoc ratchet on testkit `main` | The suite is main-source code in that module. Write class and hook javadoc to the same standard as the segment contract. |

## 7. Test strategy

- `./gradlew :aimon-session-testkit:test` runs the in-memory and fenced-view subclasses (unit tier).
  `./gradlew :aimon-bootstrap:test` runs the late-bound subclass.
- `./gradlew :aimon-session-postgres:integrationTest :aimon-session-mongodb:integrationTest :aimon-session-redis:integrationTest`
  runs the suite plus the backend-only cases (Docker is available). Check the reports: 22 suite cases per backend,
  0 skipped.
- Mutation sanity check (by hand, not committed): temporarily delete `LateBoundFencedRecordStore`'s three-argument
  `provision` override and confirm the bootstrap subclass fails. Temporarily make
  `InMemorySessionRecordStore.provision` overwrite the binding and confirm `provisionBindsOnce` fails.
- `./gradlew checkAll` (format, checkstyle, javadoc ratchet, classpath versions, unit tests, `verifyBom`).
- Docs: `check-backlog-registers.py`, `check-doc-links.py`, `check-translation-structure.py`,
  `check-translation-staleness.py`.

## 8. Documentation and backlog

- `docs/backlog/session-owner-open-items.md`:
  - set SO-7's heading to `· **닫힘** *(2026-10-18, #266)*` and add a dated **닫음** paragraph in the style of
    SO-5/SO-8. The paragraph names the suite, its five subjects plus the late-bound decorator, the "publish only when
    a store exists outside the repo" condition as the revisit trigger, and the cases that are new per backend;
  - change the title count to `(열림 4 · 닫힘 4)`;
  - add the date line for the SO-7 closing citation.
  No `.en.md` exists for this file or for `README.md`.
- `docs/backlog/README.md`: in the register row, change `8 | 5 | 3 | 0` to `8 | 4 | 4 | 0` and append
  `· SO-7 닫음, #266 (2026-10-18)` to the source cell. Touch nothing else in these files: parallel runs are editing them.
- `docs/design/session/session-owner-narrow-read.md`: leave §8/§9 as dated records. Optionally add a one-line §9.x
  note "Q1 → SO-7, closed by #266", following the precedent §9.5 note. No translation exists.
- `CHANGELOG.md` `[Unreleased]`: add an entry `### Added: a shared SessionRecordStore contract suite (tests only)`
  (precedent: the `aimon-llm-testkit` entry). It lists the subjects and the cases that are new for each backend.
- `docs/design/session/session-log.md` §641 already names the segment contract next to the backends. No similar place
  lists record-store tests, so no other design doc changes.
- Commits reference `#266`, and the last one says `Closes #266`.

## 9. Open questions

- **Q1 — Publish now or later?** This design keeps the suite unpublished, because no store exists outside the repo
  and a publish cannot be undone. If the owner wants third-party stores (for example a future
  `aimon-sandbox`-hosted or customer store) to run it now, the change is to move the class into a published module
  shaped like `aimon-memory-testkit`, add the BOM entry, CLAUDE.md, `PublishedModuleApiScopeTest`'s facade list, and
  write a CHANGELOG entry that is not marked "tests only". Confirm deferral.
- **Q2 — Redis Cluster.** `RedisClusterSessionLogSegmentStoreIntegrationTest` runs the segment contract on a cluster.
  There is no cluster record-store test today. Is a `RedisClusterSessionRecordStoreIntegrationTest` subclass wanted?
  This design does not add one, because the task names three backends, and it would be new coverage rather than
  moved coverage.
- **Q3 — Pruning `InMemorySessionRecordStoreTest`.** Several of its cases now duplicate suite cases. This design
  leaves them in place. Should a follow-up remove the duplicates?
- **Q4 — "Forwards the narrow read" for decorators.** The suite cannot see whether a wrapper's `loadOwner` forwards
  or falls back to the default, because both return the same answer. The current per-decorator tests in `aimon-core`
  and `aimon-bootstrap` that check this stay as they are. A suite-level check would need a spying raw store, which is
  beyond the scope of a record-store contract. Is that acceptable?
- **Q5 — New cases fail on a backend.** If `nulBytesSurvive`, `dollarPrefixedValuesAreData` or
  `idsRoundTripThroughEnumeration` fails on a backend that never ran it, the plan is to fix the backend when the fix
  is small, and otherwise to stop and ask. The task statement does not say whether such a fix is in scope.

## 10. After the build — departures and what went to the backlog

*Appended 2026-10-18, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Sources: the run's `build/deviations.md`, review 1's non-blocking notes, and what was
found while building.*

**No decision in §2 changed.** The suite is `AbstractSessionRecordStoreContractTest` in the unpublished
`aimon-session-testkit`, with the 22 cases of §5.1 under those names and the four hooks of §4 with those defaults.
Six subjects run it: in-memory, the fenced view and the late-bound decorator in the unit tier (22 each, the two
corruption cases skipped), and Postgres, MongoDB and Redis in `integrationTest` (22 / 23 / 30, 0 skipped — the
suite plus 0 / 1 / 8 backend cases, as §5.2 planned). Every case that was new for a backend passed on the first run,
so §6's first row and Q5 never came up: no backend changed.

### 10.1 Where the build departed from the body

- **DV-1 — a few SPI-only assertions beyond §5.1's unions.** `ownerWithoutBinding` and `bindingWithoutOwner` also
  check the owner through `load`; `enumerationAndRemoval` checks `exists` after the delete; `clearRemovesEveryRecord`
  checks `exists` on the cleared path as well as the refused one. Each is an SPI read of a property the case already
  states, and every subject passes it. `ownerFillsWhenEmpty` fills with Mongo's `Principal.service("svc-1",
  "Billing")` rather than Postgres's user `alice`, so the suite keeps one service principal on the fill path; the
  user principal is covered by every other owner case.
- **DV-2 — the hooks wrap checked exceptions.** The hooks are declared without `throws`, so Postgres's `rawUpdate`
  rethrows `SQLException` as `IllegalStateException`. The two `loadOwner` cases lost their `throws SQLException`.
- **DV-3 — Mongo's node A is `store()`.** The old Mongo handoff built a second store over the shared database for node
  A; the suite uses `store()`, as Postgres and Redis already did. Node B is still a separate client, which the
  subclass now closes in `@AfterEach` (§6's leak row).
- **DV-4 — Q2 is moot.** `RedisSessionRecordStore` has no constructor that takes a `StatefulRedisClusterConnection`
  (only `RedisSessionLogSegmentStore` does — session-log SL-4), so there is nothing a Redis Cluster subclass could
  construct. Whether the record store should support Cluster is a product question, not a test-coverage one, and it
  was not filed here: nothing in this change bears on it.

The mutation check in §7 was run by hand and not committed. Removing `LateBoundFencedRecordStore`'s three-argument
`provision` override fails 8 of the bootstrap subclass's 22 cases (every case that provisions with an owner).
Making `InMemorySessionRecordStore.provision` re-bind fails `provisionBindsOnce` and `ownerFillsWhenEmpty` in the
in-memory subclass.

### 10.2 Review notes: taken, and left

| Note | Outcome |
|------|---------|
| `LateBoundFencedRecordStore` must be built over the same raw instance as the fenced view | Taken. `LateBoundFencedRecordStoreContractTest` builds one `InMemorySessionRecordStore`, wraps it in `DefaultSessionStore`, and binds that store's `records(UNLESS_HELD_ELSEWHERE)` to a `LateBoundFencedRecordStore` over the same instance; its javadoc says why |
| SO-7's closing paragraph should say outright that the third-party half is deferred | Taken, and filed: the closing paragraph says it, and Q1 became SO-9 (§10.3) so the deferral is an open item rather than a sentence in a closed one |
| Keep Mongo's final `load(...).getOwner()` and Postgres's pre-fill `load(...).getOwner().isEmpty()` in `ownerFillsWhenEmpty` | Taken |
| The `!supportsClear()` path should prove the records survive through `listSessionIds()` and `exists` | Taken |
| Make "the corruption cases run, not skip" structural | Left. A `hasEncodedForm()` flag is one more override that the subclass which forgot both hooks would also forget, so it moves the check without making it structural. The run checks it instead: all three backend reports show 0 skipped |
| `nulBytesSurvive` puts a NUL in the system prompt too, which Mongo and Redis may store apart | Observed: it passes on all three backends |

### 10.3 What went to the backlog

The open questions in §9 whose consequences reach past this change are filed in
[`../../backlog/session-owner-open-items.md`](../../backlog/session-owner-open-items.md):

- Q1 → SO-9 (the suite is not published, so a store outside this repository cannot run it).

Q2 was moot (DV-4). Q3 was decided as written: `InMemorySessionRecordStoreTest` is not pruned, and the overlap is
harmless. Q4 was decided as written: the per-decorator "forwards `loadOwner`" tests stay where they are. Q5 did not
arise. None of the four was filed. SO-7 is closed by this change.
