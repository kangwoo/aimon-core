# Design — workflow isolation hardening (EE-8 · EE-25 · EE-27 · EE-28 · EE-29)

> Status: **IMPLEMENTED** — `aimon-core` (`ExecutionEnvironment.isolate` / `isolatedFrom`,
> `UnavailableExecutionEnvironment`, `LocalExecutionEnvironment(Provider)`, `LocalIsolatedEnvironment`,
> `ScopedVirtualFileSystem`, `WorktreeMerge`, `DefaultWorkflowContext.resolveEnv`, and after review 2
> `VirtualFileSystems.pathRules`) and the records:
> [`execution-environment.md`](execution-environment.md) §4.1, §4.2, §5.2, §9.2, §13 and §15,
> [`execution-environment-implementation.md`](execution-environment-implementation.md) §10.9,
> [`../workflow/workflow.md`](../workflow/workflow.md) §6.3, the workflow usage guide, and backlog EE-8, EE-25, EE-27,
> EE-28 and EE-29 (closed) plus EE-46 and EE-47 (opened) in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md).
>
> **[§8](#8-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §8 is the body as approved in design review round 1
> (PASS, no blocking findings, seven non-blocking notes), kept byte-exact rather than corrected — the house habit in
> this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its `file:line` citations are at
> `7c0c4d2`, as its first line says. The review transcript (`review-1.md`) and the run records the body names
> (`TASK.md`) are not in the repository; §8 reproduces what was measured and which review notes were taken.
>
> It is in English, like its sibling [`execution-environment-implementation.md`](execution-environment-implementation.md);
> `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1).

Grounded against `herdr/workflow-isolation-hardening` at `7c0c4d2`. Line numbers were checked on 2026-09-29.

## 0. Problem, restated

A workflow step with `isolate=true` runs in a branch environment that `ExecutionEnvironment.isolate(key)` derives from
the parent. For the local provider that branch is a `LocalIsolatedEnvironment`: a `ScopedVirtualFileSystem`
(`.worktrees/{key}`) stacked on the parent's path-rule filesystem. `WorktreeMerge.promote` later copies branch files up
to the parent. Five gaps in this path make failures surface late, look like something else, or lose data:

- **EE-8.** Root-anchored rules never reach a branch's own `.aimon/`, so the write succeeds. The merge then trips on
  `DENY` after other files have already been promoted, which leaves a half-merge.
- **EE-27.** The shared-prefix check is case-sensitive while the path rules are not. A branch write to
  `.AIMON-STAGED/x` therefore lands in the branch and fails at promotion.
- **EE-28.** `promote` does not check that the branches actually belong to `parent`. The parent itself, or another
  parent's branch, copies each file onto itself and then deletes the "branch" copy.
- **EE-25.** An unavailable parent environment reports "does not support isolation", which hides the real cause (no
  provider, sandbox down).
- **EE-29.** A branch cannot be isolated again, and a nested isolated step fails with the same uninformative message.

The goal: refuse bad input at the earliest point (write time, or the first line of `promote`), and have every refusal
name its cause. Behaviour for correct input stays the same.

## 1. Approach, and rejected alternatives

### 1.1 EE-8: branch path rules, placed *below* the scope

The chosen approach: in `LocalIsolatedEnvironment`, wrap the parent's tool filesystem once more with
`VirtualFileSystems.withPathRules(...)`. The wrapper uses the parent's own rules, re-anchored under the branch prefix
(`.aimon` → `.worktrees/{key}/.aimon`). The `ScopedVirtualFileSystem` then sits **on top of** that wrapper:

```
file tool ──► ScopedVirtualFileSystem(prefix=.worktrees/k, shared={.aimon-staged})
                 └─► PathRuleVFS(rules=[DENY .worktrees/k/.aimon, READ_ONLY .worktrees/k/.aimon-staged])   ← new
                        └─► PathRuleVFS(rules=[DENY .aimon, READ_ONLY .aimon-staged])   (parent tool fs, unchanged)
                               └─► LocalFileSystem
```

This settles the maintainer's note ("check that EE-41's public factory can express it"): it can. `environment.impl`
already imports `VirtualFileSystems`, so no ArchUnit change is needed.

Why *below* the scope:

- `ScopedVirtualFileSystem.getWorkingDirectory()` returns `"."`. A rule wrapper placed on top would resolve paths with
  `VfsPaths.resolveUnder(".", p)`. For an unanchored base, that turns `/ws/.aimon/x` into `ws/.aimon/x`, which no rule
  covers. `ScopedVirtualFileSystem` then maps the canonical absolute path into the branch (pinned by
  `LocalIsolatedEnvironmentTest` line 147), so the write would land in `.worktrees/k/.aimon/x`. That is a spelling
  bypass. So is `/ws/.worktrees/k/.aimon/x` (the branch host path).
- Below the scope, every path the rule layer sees is the normalised, delegate-relative path that `scope()` has already
  computed (`.worktrees/k/...`). The rule layer resolves it against the parent's anchored working directory, and
  `PathRule.covers` folds case. All spellings therefore meet one check.
- `PathRuleVirtualFileSystem.getWorkingDirectory()` returns its delegate's, so the `baseWorkingDir` that
  `ScopedVirtualFileSystem` captures is unchanged. Branch-host-path stripping keeps working.

The rules are **derived from the parent's effective rules, not hard-coded**. An assembly that passed
`pathRules(List.of())` has an unguarded root `.aimon/`, so its branches get no rules either. That keeps the invariant
"the branch obeys the same rules as the parent, anchored at the branch root". All parent rules are re-anchored,
including the staging `READ_ONLY` one. The re-anchored staging rule is unreachable, because `scope()` routes
`.aimon-staged/` to the root before the rule layer sees it. Keeping it costs nothing and means no rule silently depends
on the shared-prefix set.

Observable consequence: a branch's `.aimon/` now behaves exactly like the root's. It is invisible (`exists` is false and
listings omit it), and every file-tool access throws `FileAccessDeniedException`. Because `listRecursive` omits it,
`WorktreeMerge` never sees it.

Rejected:

| Alternative | Why rejected |
|---|---|
| Filter `.aimon/` out during the merge | Rejected by the maintainer (decision of 2026-09-29): written data disappears without a word. |
| Document today's behaviour | Rejected by the maintainer. Also, the premise check showed the user sees a half-merge, not a clean failure. |
| Put the rule wrapper on top of the scope | Can be bypassed with absolute paths (above). |
| Teach `ScopedVirtualFileSystem` a list of denied prefixes | Would duplicate `PathRuleVirtualFileSystem`'s semantics (hide from `exists`/listings, subtree checks, fail-closed) inside a second class. The composition reuses one implementation. |
| Hard-code `deny(".aimon")` for branches | Diverges from the parent when an assembly customises or empties its rules. |

### 1.2 EE-27: `isUnder` → `isUnderIgnoreCase` in `ScopedVirtualFileSystem.scope()`

This is a one-line change at `ScopedVirtualFileSystem.java:140`. After the change, a branch write to `.AIMON-STAGED/x`
is routed to the parent's `.AIMON-STAGED/x`. The parent's `READ_ONLY` rule (which already ignores case) refuses it at
write time, so it never lands inside the branch and never reaches a merge. The returned path keeps the caller's
spelling:

- On a case-insensitive store it is the same directory.
- On a case-sensitive store it is a different directory at the root, which the root rule still covers (read-only), so a
  read simply misses.

**Not fixed: the `a`/`A` branch-key collision on case-insensitive filesystems** (review 1 of PR #195).
`sanitizeBranchKey` derives keys from structural step paths, and the framework never produces two keys that differ
only in case (see Q3). Only a direct `isolate()` caller could collide. Detecting that would need per-environment state
(the set of issued keys), and "multi-instance ready" would then require that state behind a store. That is too much
for a hypothetical caller. The limitation goes in the `LocalExecutionEnvironment.isolate` javadoc and the closing note
of the backlog item.

### 1.3 EE-25: the environment names its own reason; the runner stops dropping the cause

- `UnavailableExecutionEnvironment` overrides `isolate(key)` to **throw its `ExecutionEnvironmentUnavailableException`**
  ("Execution environment unavailable: {cause}", with the original throwable as cause). This is the same thing its
  filesystem, shell and `stage()` already do; the class javadoc says "every call throws … carrying the cause". An
  unavailable environment cannot honestly answer "no isolation" (`Optional.empty()`). Its capability is unknown, and
  the reason is exactly what the caller needs.
- `DefaultWorkflowContext.resolveEnv` already catches `RuntimeException` from `isolate()` and reports
  `"could not isolate branch '<k>': <message> — refusing to run unscoped"`. Two small changes: pass `e` as the
  `WorkflowException` cause (today it is dropped), and keep the message.
- `ExecutionEnvironment.isolate` javadoc: state the two answers explicitly. **Empty** means "this kind of environment
  has no isolation". **Throwing** means "isolation is refused here, and the exception message is the reason" (the
  environment is unavailable, or the environment is already a branch; see §1.4).

Rejected:

| Alternative | Why rejected |
|---|---|
| `resolveEnv` checks `parent instanceof UnavailableExecutionEnvironment` and quotes `message()` | The ArchUnit whitelist for `at.aimon.core.workflow` (`PackageDependencyArchitectureTest` ~line 305) does not include `UnavailableExecutionEnvironment`, so the rule would have to widen. It also fixes only this one caller; every other `isolate()` caller would still get a silent empty. |
| Parse `descriptor().notes()` for "unavailable" | Stringly-typed coupling to a prompt-facing text. |
| Change `isolate` to return a result type carrying a reason | Breaks the SPI for the external sandbox provider, for one diagnostic. |

### 1.4 EE-29: option (b), an explicit error with its cause (not nested support)

`LocalIsolatedEnvironment` overrides `isolate(key)` to throw `UnsupportedOperationException`:

```
nested isolation is not supported: this environment is already the isolated workflow branch '<k>'
(.worktrees/<k>); run the nested workflow's isolated steps from the parent environment, or drop isolate from them
```

Through §1.3 this reaches the user as
`WorkflowException("… could not isolate branch '<k2>': nested isolation is not supported: …")`.

Why not support nesting (the acceptance criteria make (b) the default when support is expensive, and it is):

1. **The paths break.** `Scoped(Scoped(...))` does not work. The inner scope captures `"."` as its base, so absolute
   paths (the ones the file tools forward, anchored on the advertised host-path working directory) get stripped of
   their leading slash and land at a garbage location inside the branch. Nesting would have to rebuild the environment
   from the root with a compound prefix (`.worktrees/k/.worktrees/k2`), which is a refactor of the constructor and of
   the staging share.
2. **The merge becomes unsafe.** Before merging the outer branch, the inner branch's unmerged directory
   (`.worktrees/k/.worktrees/k2/...`) sits in the outer branch's listing. Merging the outer branch into the root would
   then promote it to the root's `.worktrees/k2/...`, which means writing into *another branch's directory*. The fix
   is to hide `.worktrees/` from branch listings, plus a merge order (inner → outer → root) that nothing enforces.
3. **Lineage.** EE-28's ownership check would need a lineage chain, not one parent.
4. **The sandbox contract is unknown.** Git worktrees inside git worktrees are the sandbox provider's own problem (it
   lives in another repository), and the core would be promising semantics it cannot test.
5. **No use case yet.** The backlog item's own trigger is "when someone tries to use it".

`UnsupportedOperationException` rather than `Optional.empty()`, because empty would reproduce the EE-25 symptom ("does
not support isolation" with no reason).

### 1.5 EE-28: ownership and identity checks in `promote`, plus a metadata pre-flight

- **A new default SPI method, `ExecutionEnvironment.isolatedFrom()`**: `Optional<ExecutionEnvironment>`, the
  environment this one was `isolate()`d from, if it is a branch and declares its lineage. The default is empty.
  `LocalIsolatedEnvironment` returns `Optional.of(parent)`. Adding a default method breaks no implementor.
- `promote` validates **before any I/O**. Each check throws `IllegalArgumentException` (argument misuse, alongside the
  existing `requireNonNull`s, and not a `VirtualFileSystemException`, since nothing was attempted):
  1. `branch == parent`: "branches[i] is the parent environment itself".
  2. `branch.fileSystem() == parent.fileSystem()`: "branches[i] shares the parent's filesystem; promoting it would copy
     each file onto itself and then delete it". This is the direct cause of the EE-28 data loss, independent of how the
     environment objects are wrapped.
  3. `branch.isolatedFrom()` is present and `!= parent` (identity): "branches[i] was isolated from <other>, not from
     <parent>".
  4. The same branch object appears twice: "branches[i] and branches[j] are the same environment". A duplicate would
     otherwise report false conflicts under `FAIL`.
  5. `isolatedFrom()` empty (a third-party environment that does not declare lineage): **accepted**, with only checks
     1, 2 and 4 applied. Design §13 says `promote` must work for sandbox branches, and today's sandbox cannot declare
     lineage. Q1 covers whether to go strict later.

  The comparison is by identity. The local provider returns one `LocalExecutionEnvironment` per provider for every
  request (design §4.2), and branches hold that instance.
- **Metadata pre-flight (the pre-existing half-merge from the EE-28 text).** Before the first write, resolve
  `getMetadata` for every winning `(branch, path)` and keep it. If any lookup throws (for example a shell-made symlink
  in the branch), abort with
  `VirtualFileSystemException("Worktree promotion aborted before promoting anything: …", e)`. This removes the one
  half-merge class that is detectable up front. Copy, verify and delete failures stay fail-fast with no rollback, as
  documented. It is cheap, since the pre-scan already walks every path. The only cost is holding one `FileMetadata`
  per promoted file (the merge report already holds one string per file). If the build wants to trim scope, this is
  the part to drop (Q2).

Rejected:

| Alternative | Why rejected |
|---|---|
| `instanceof LocalIsolatedEnvironment` in `WorktreeMerge` | `WorktreeMerge` (`agent.impl.orca.environment`) may not import `environment.impl` (ArchUnit impl rule), and it would be local-only. |
| A key-based overload `promote(parent, List<String> keys, policy)` that calls `parent.isolate(key)` itself | Ownership by construction and attractive, but the task asks for checks on the existing signature, which sandbox assemblers use with branch objects. It can be added later without conflicting with this change. |
| Reject branches that do not declare lineage | Breaks `promote` for the sandbox provider (design §13 row "`promote` must work in that environment too"). |
| Detect "two distinct objects for one key" by comparing `descriptor().workingDirectory()` | Not a contract. A sandbox could describe every worktree as `/workspace`. Q4 covers it. |

## 2. Concrete changes, by file

All paths are under `modules/aimon-core/src/main/java/at/aimon/core/` unless stated. **Not touched** (the EE-42
branch): `SubagentResolver`, `InlineSubagentResolver`, `WorkflowTool`, `GraalJsWorkflowTool`,
`DefaultSubagentExecutor`, `EnvironmentRequest`.

| File | Change | Item |
|---|---|---|
| `environment/ExecutionEnvironment.java` | `isolate` javadoc: empty vs throw semantics. New `default Optional<ExecutionEnvironment> isolatedFrom()`. | 25, 28, 29 |
| `environment/UnavailableExecutionEnvironment.java` | Override `isolate` → `throw unavailable()`. Class javadoc: "…and so do `stage` and `isolate`". | 25 |
| `environment/impl/LocalExecutionEnvironmentProvider.java` | Pass the effective `rules` (already computed at line 125) into `new LocalExecutionEnvironment(...)`. | 8 |
| `environment/impl/LocalExecutionEnvironment.java` | New constructor parameter `List<PathRule> pathRules` (copied), package-private `pathRules()` accessor. `isolate` javadoc: note the case-insensitive key limitation (EE-27 remainder). | 8, 27 |
| `environment/impl/LocalIsolatedEnvironment.java` | Keep `branchKey` in a field. Build the re-anchored rules, wrap `parent.fileSystem()` with `VirtualFileSystems.withPathRules` when non-empty, and scope on top. Override `isolatedFrom()` → `Optional.of(parent)`. Override `isolate` → throw UOE (§1.4). Class javadoc: branch path rules, no nesting. | 8, 28, 29 |
| `filesystem/impl/ScopedVirtualFileSystem.java` | Line 140: `VfsPaths.isUnderIgnoreCase(normalizedRel, shared)`. Javadoc "Shared prefixes" bullet: "whole segments, ignoring case, after normalisation". | 27 |
| `agent/impl/orca/environment/WorktreeMerge.java` | `validateBranches(parent, branches)` before the pre-scan. Metadata pre-flight. Class javadoc lines 36–38: replace "promoted to the root `.aimon/`, where the parent's path rules refuse it" with "a branch cannot write under its own `.aimon/` (the parent's rules apply at the branch root), so a merge never meets one". `promote` javadoc: `@throws IllegalArgumentException`. | 8, 28 |
| `workflow/impl/DefaultWorkflowContext.java` | `resolveEnv` lines 189–192 only: `new WorkflowException(msg, e)`. Message unchanged. The empty branch (lines 194–197) is unchanged. **Do not touch** lines 180–184 (the `EnvironmentRequest` build), which EE-42 may edit. | 25 |

Illustrative sketch of the branch filesystem (not final code):

```java
final String branchPrefix = LocalExecutionEnvironment.WORKTREE_ROOT + "/" + branchKey;
final List<PathRule> branchRules = parent.pathRules().stream()
        .map(rule -> PathRule.builder().prefix(branchPrefix + "/" + rule.getPrefix()).access(rule.getAccess()).build())
        .toList();
final VirtualFileSystem guarded = branchRules.isEmpty()
        ? parent.fileSystem()
        : VirtualFileSystems.withPathRules(parent.fileSystem(), branchRules);
this.fileSystem = new ScopedVirtualFileSystem(guarded, branchPrefix, Set.of(parent.staging().stagingRoot()));
```

### 2.1 Docs

These are required by acceptance item 7. The translation status of each file was checked.

| Doc | Change | Translation |
|---|---|---|
| `docs/backlog/execution-environment-open-items.md` | EE-8/25/27/28/29 → `**닫힘** *(date)*`, each with what was done and what turned out different from the text (§5). Header count: `42건 (열림 38, 그중 결정됨 6 · 닫힘 4)` → `열림 33, 그중 결정됨 5 · 닫힘 9` (EE-8 leaves "decided"). Add one sentence to the intro paragraph. **Expected to conflict with the EE-42 branch; resolve at merge.** | none |
| `docs/design/tool/execution-environment.md` | §4.1 (`isolate` semantics, `isolatedFrom`), §4.2 `isolate` bullet (branch rules, no nesting), §5.2 workflow bullet (reason reported; `promote` validates ownership), §9.2 (branch re-anchoring), §13 contract table: new row "a branch applies the parent's rules at its own root via `VirtualFileSystems.withPathRules`; declare `isolatedFrom()`" (should, not must; see Q1). | none |
| `docs/design/tool/execution-environment-implementation.md` | §10 new entries (departures): branch rules below the scope, `isolatedFrom`, Unavailable `isolate` throws, nested refused. §10.7: the pinned test "branch-local `.aimon/` is not denied" is inverted. The Stage 4 test bullets (~lines 884–891) describe the old `.aimon` behaviour: annotate them rather than rewrite, because they are the plan's history. | none |
| `docs/design/workflow/workflow.md` §6.3 (~line 451) | "…승격이 실패한다" → the branch's `.aimon/` is refused at write time. Add that nested isolation is refused with a reason, and that `promote` rejects foreign/self branches. | none |
| `docs/features/workflow/workflow-usage-guide.md` (~line 562) **and `.en.md`** | Same sentence change, both in the same commit, `source_commit` = the canonical doc's pre-change commit. | **yes** |

Run `python3 scripts/check-doc-links.py`, `check-translation-structure.py` and `check-translation-staleness.py`.

## 3. Data and interface shapes that change

- **`ExecutionEnvironment` (public SPI)**
  - `+ default Optional<ExecutionEnvironment> isolatedFrom()`: empty by default.
  - `isolate(String)`: the contract text gains "may throw a `RuntimeException` whose message is the reason isolation
    is refused here". The signature is unchanged.
- **`UnavailableExecutionEnvironment.isolate`**: `Optional.empty()` → throws `ExecutionEnvironmentUnavailableException`.
- **`LocalIsolatedEnvironment.isolate`**: `Optional.empty()` (inherited) → throws `UnsupportedOperationException`.
- **`LocalExecutionEnvironment`**: its package-private constructor gains `List<PathRule> pathRules`, plus a new
  package-private accessor.
- **`WorktreeMerge.promote`**: new `IllegalArgumentException` cases, and a new "aborted before promoting anything"
  `VirtualFileSystemException`. `MergeReport` is unchanged.
- **Branch filesystem behaviour**
  - `{branch}/.aimon/**` is hidden and throws on access.
  - A branch-relative `.AIMON-STAGED/**` (any case) is routed to the root staging area, where it is read-only.
  - `branch.fileSystem().deleteRecursive(".")` and `move(".", …)` are now refused ("contains the protected directory
    `.worktrees/k/.aimon`"). Deleting through the parent (`parent.fileSystem().deleteRecursive(".worktrees/k")`) is
    unaffected. No main code deletes a branch through its own filesystem; this was checked by grepping for
    `deleteRecursive`/`discard` in `aimon-core` and `aimon-workflow-graaljs`.
- **`WorkflowException` from `resolveEnv`**: now carries a cause.

No persisted format and no wire name changes. Nothing in the step cache fingerprint changes (`isolate` is already part
of it).

## 4. Failure modes and handling

| Situation | Before | After |
|---|---|---|
| File tool writes `.aimon/x` (any spelling or case, relative or absolute) in a branch | Succeeds; the merge later aborts halfway | `FileAccessDeniedException` at write time. The tool returns `ToolResult.error`, so the model sees it immediately. The message carries the delegate path (`.worktrees/k/.aimon/x: hidden from this filesystem`). |
| **The shell** writes `.worktrees/k/.aimon/x` (the local limit: the shell is not guarded) | Merged, then refused (half-merge) | Hidden from the branch listing, so it is **not promoted and not reported**, and stays in the branch directory. This is the same status as a shell-written root `.aimon/` file (invisible to file tools). Documented as the local limit, not the rejected "silent filter": file tools never show the model that file in the first place. |
| Branch writes `.AIMON-STAGED/x` | Lands in the branch; the merge aborts | `FileAccessDeniedException` (read-only) at write time. |
| Parent has no path rules (assembly passed `List.of()`) | Branch `.aimon/` writable; merged to an unguarded root | Unchanged; the rules follow the parent. |
| Isolated step, no provider / provider threw / sandbox down | "does not support isolation" | "could not isolate branch 'k': Execution environment unavailable: <cause> — refusing to run unscoped", with the cause chained. Still run-fatal (C30). |
| Isolated step inside an isolated branch (nested workflow) | "does not support isolation" | "could not isolate branch 'k2': nested isolation is not supported: this environment is already the isolated workflow branch 'k' …". Run-fatal; zero subagent executions. |
| Environment that genuinely has no isolation (`TestExecutionEnvironments`) | "does not support isolation" | Unchanged. |
| `promote(parent, [parent])`, a foreign branch, a duplicate, or a branch sharing the parent's filesystem | Self-copy then delete (data loss), or false conflicts | `IllegalArgumentException` before any I/O. |
| A branch path whose `getMetadata` throws (symlink) | Half-merge | Aborts before the first write. Nothing is promoted. |
| Copy, write or delete fails mid-merge | Fail-fast, no rollback | Unchanged (documented, idempotent re-run). |
| Third-party branch without `isolatedFrom()` | Accepted | Accepted (checks 1, 2 and 4 only). |

## 5. What turned out different from the item texts (for the backlog closing notes, README rules 2/3)

- **EE-8.** The fix does more than "refuse writes": it applies `DENY`, so the branch's `.aimon/` is also invisible. That
  is the parent's semantics, and it is what "the same path rules at the branch root" means. Shell-written files there
  are left behind unpromoted (§4). The composition order matters: rules placed above the scope are bypassable (§1.1).
- **EE-27.** The `a`/`A` branch-key collision is not fixed and is documented. Framework-derived keys cannot collide.
- **EE-28.** The check is lenient for environments that do not declare lineage. The symlink half-merge is also closed
  by the pre-flight (if kept; see Q2).
- **EE-25.** The fix lives in the environment (`UnavailableExecutionEnvironment.isolate`), not in `resolveEnv`. The
  runner's only change is chaining the cause.
- **EE-29.** Closed with (b). Nested support would additionally require keeping `.worktrees/` out of branch listings.
  The underlying hazard exists **today, independent of nesting**: a branch that writes `.worktrees/other/x` has it
  promoted into the root `.worktrees/other/x`, which is another branch's directory. See Q5.

## 6. Test strategy

Unit tests only; no Docker. Then run `./gradlew format` and `./gradlew checkAll`.

**`environment/impl/LocalIsolatedEnvironmentTest`** (EE-8, EE-27, EE-29)
- **Replace** "a branch-local .aimon/ is not denied, but promoting it into the root .aimon/ is" (lines ~128–156) with:
  - The branch write `.aimon/x` is refused (`FileAccessDeniedException`) under each of these spellings:
    `.AIMON/x`, `./a/../.aimon/x`, branch host absolute `{ws}/.worktrees/k/.aimon/x`, canonical absolute
    `{ws}/.aimon/x`. Nothing exists under `{ws}/.worktrees/k/.aimon`.
  - `exists(".aimon")` is false.
  - A file created directly on disk at `.worktrees/k/.aimon/y` (standing in for a shell write) is absent from
    `listRecursive(".")`.
  - `WorktreeMerge.promote` of that branch plus an ordinary file promotes the ordinary file, succeeds, and leaves the
    root `.aimon/` untouched.
- A provider built with `pathRules(List.of())`: the branch `.aimon/x` is writable (the rules follow the parent).
- The branch write `.AIMON-STAGED/x` → `FileAccessDeniedException`. Nothing under `.worktrees/k/.AIMON-STAGED`.
- Regression: a staged skill is still readable from the branch; the existing test stays green.
- `branch.fileSystem().deleteRecursive(".")` is refused; `parent.fileSystem().deleteRecursive(".worktrees/k")`
  succeeds.
- `branch.isolate("x")` → `UnsupportedOperationException` whose message names branch `k` and "nested isolation is not
  supported".
- `branch.isolatedFrom()` returns the parent instance; `parent.isolatedFrom()` is empty.

**`filesystem/impl/ScopedVirtualFileSystemTest`** (EE-27)
- `.AIMON-STAGED/x`, `.Aimon-Staged/x` and `.aimon-ſtaged/x` route to the delegate unscoped (the delegate records the
  path, as the existing shared-prefix tests do).
- `.aimon-staged2/x` is still scoped (whole segments).

**`environment/UnavailableExecutionEnvironmentTest`** (EE-25)
- `isolate("k")` throws `ExecutionEnvironmentUnavailableException`. The message contains the cause, and `getCause()`
  is the original throwable (for `of(Throwable)`) or null (for `of(String)`).

**`workflow/impl/WorkflowPhase4Test`** (EE-25, EE-29)
- `isolateWithoutEnvironmentIsRunFatal`: additionally asserts the message contains
  "no ExecutionEnvironmentProvider is configured" and the cause is `ExecutionEnvironmentUnavailableException`.
- New: a provider that throws `"sandbox down"` → the message contains "sandbox down".
- New: the base env's execution environment is a local branch (built from `LocalExecutionEnvironmentProvider` +
  `isolate("k")`) → `WorkflowException` containing "nested isolation is not supported", and `executeCount == 0`.
- `isolateOnNonIsolatingEnvironmentIsRunFatal` is unchanged ("does not support isolation").

**`agent/impl/orca/environment/WorktreeMergeTest`** (EE-28)
- `promote(parent, [parent])`, `promote(parent, [otherProvider.isolate("k")])`, `promote(parent, [b, b])`, and a fake
  branch whose `fileSystem()` returns the parent's → each throws `IllegalArgumentException`, and the parent and branch
  files are unchanged.
- A fake `ExecutionEnvironment` with no `isolatedFrom()` and its own filesystem is still promoted (lenient path).
- Pre-flight: a branch with an ordinary file `a` plus an entry whose `getMetadata` throws. Use a real symlink
  (`Files.createSymbolicLink`) **only if** `LocalFileSystem.getMetadata` is confirmed to throw on it during the build;
  otherwise use the existing fake-VFS style from the mid-merge failure test (line ~154). Expect
  `VirtualFileSystemException` "before promoting anything", and `a` not promoted.
- The existing tests (policies, rebuild by key, mid-merge failure) stay green.

**ArchUnit**: no rule change is expected. `checkAll` confirms it.

## 7. Open questions (each with the default the build should use unless overridden)

- **Q1: strict lineage.** Should `promote` eventually reject branches whose `isolatedFrom()` is empty? **Default: no**
  (lenient). Add a §13 contract row asking the sandbox provider to declare lineage. Revisit when aimon-sandbox
  implements it.
- **Q2: metadata pre-flight in scope?** The backlog text calls the symlink half-merge pre-existing, so it is not
  strictly part of EE-28. **Default: include** (cheap, and on topic for "half-merge"). Drop it if a reviewer objects.
  Nothing else depends on it.
- **Q3: are framework branch keys really case-unique?** `sanitizeBranchKey` replaces non-alphanumerics in structural
  paths. This design assumes the path segments come from a fixed lowercase alphabet (for example `a0`, `p1`). **The
  build should confirm this** from `PathFrame`/`childPath` before writing the EE-27 closing note. If keys can differ
  only by case, EE-27's remainder is a real bug and needs its own item.
- **Q4: two distinct branch objects for one key** (an assembler calls `isolate(k)` twice and passes both). This is not
  caught, and it causes false `FAIL` conflicts. **Default: leave it**, and document "pass each branch once" in the
  `promote` javadoc.
- **Q5: the `.worktrees/` injection found while designing EE-29** (a branch writing `.worktrees/other/x` promotes into
  another branch's directory). This is outside the five items. **Default: do not fix here**; record it as a new
  backlog item. **Numbering risk:** the EE-42 branch may add items concurrently, so take the next free number when
  committing, and expect to renumber at merge. An alternative cheap fix, if the orchestrator wants it: add
  `DENY {branchPrefix}/.worktrees` to the branch rules. That is a local-only rule the parent does not have, so it
  breaks the "same rules as the parent" invariant, which is why it is not the default.
- **Q6: EE-42 overlap in `DefaultWorkflowContext`.** EE-42 is not listed as touching this file, but it changes
  `EnvironmentRequest`, which `resolveEnv` builds (lines 180–184). This design edits only lines 189–192. If EE-42
  lands first and moved that block, rebase trivially.
- **Q7: the error message path.** A refused branch write names the delegate path (`.worktrees/k/.aimon/x`), not the
  path the model passed. **Default: accept it.** It names the real location and is unambiguous. Rewriting it would
  mean catching and re-throwing in `ScopedVirtualFileSystem` for every operation.
- **Q8: the closing date and commit shape.** Use the date the build commits. Commit shape: one commit,
  `fix(environment): harden workflow isolation (EE-8, EE-25, EE-27, EE-28, EE-29)`, with docs and the `.en.md`
  translation in the same commit, and no PR (acceptance item 8).

---

## 8. After the build — departures and what went to the backlog

*Appended 2026-09-29, after implementation. Everything above this section is the body as approved, and it is not
edited to look prescient. Three sources feed this section: the run's `build/deviations.md`, the review's non-blocking
notes, and what was measured while building.*

**No decision in §1 changed.** The branch rules sit below the scope and follow the parent's (§1.1); the shared-prefix
check folds case (§1.2); `UnavailableExecutionEnvironment.isolate` throws and the runner chains the cause (§1.3); a
branch refuses nested isolation with its reason (§1.4); `promote` checks ownership before it reads or writes any
file and pre-flights metadata (§1.5; "before any I/O" there holds for the local provider only, see DV-9). Q2 was kept (the pre-flight is in). What departed is below.

### 8.1 Where the build departed from the body

- **DV-1 — the pre-flight validates; it does not cache.** §1.5 kept each pre-flight `FileMetadata` and used its size
  for the later write. Review 1 pointed out that a file changed between the two would then be written with a stale
  length. The pre-flight now only reads metadata to prove every winning path is readable, and the copy reads it again
  right before each write, as before. The cost is one extra `getMetadata` per promoted file.
- **DV-2 — the pre-flight probes the call that actually fails, confirmed on a real symlink.** Review 1 doubted that
  `getMetadata` is the call that throws on a shell-made symlink. Measured on the local filesystem: `listRecursive` lists
  a symlink to a regular file (`Files.isRegularFile` follows links), and both `getMetadata` and `openInputStream` refuse
  it, because `PathValidator.validateAndNormalize` rejects any symbolic link on the path before either one reads.
  `getMetadata` is therefore the earliest failing call, and §6's condition for the real-symlink test held.
  `WorktreeMergeTest.unreadableBranchFileAbortsBeforePromoting` uses `Files.createSymbolicLink`, not the fake-VFS
  fallback.
- **DV-3 — the existing mid-merge failure test had to change, not just stay green.** §6 expected
  `midMergeFailureReportsPartialProgress` to pass unchanged. It wrapped the *parent* in a fake to inject the failure,
  and the branches, which declare `isolatedFrom()` = the real parent, are now rightly refused against that fake (check
  3). The fault moved to the branch side: a branch that declares no lineage, with a filesystem whose
  `openInputStream` fails on the second file. The message still reports "branch #1", "after 1 promoted file(s)" and
  "no rollback". The failure moved from the parent's `write` to the branch's `openInputStream` because a
  `getMetadata` fault would now stop the pre-flight instead of the merge.
- **DV-4 — the throw-branch message of `resolveEnv` gained "(C30)".** §2 said the message was unchanged. Review 1
  noted that the empty branch's message ends with "(C30)" and the throw branch's did not. Since the line was edited
  anyway (to chain the cause), the two now match.
- **DV-5 — the EE-27 test writes through a real filesystem.** §6 described a recording delegate. The existing
  shared-prefix tests use a real `LocalFileSystem`, so the new one does too: it writes `.AIMON-STAGED/y.txt`,
  `.Aimon-Staged/y.txt` and `.aimon-ſtaged/y.txt` through the scope, then asserts that each exists at the root spelling
  and that nothing exists under `.worktrees/k/`. This holds on a case-sensitive store and on a case-insensitive one.

### 8.2 Review notes: taken, and left

- **Taken.** The staging-rule assumption (review 1, first note) is now stated in `LocalIsolatedEnvironment`'s javadoc
  and in [`execution-environment.md`](execution-environment.md) §9.2: sharing the staging prefix is safe only because
  the parent guards it `READ_ONLY`. An assembly whose custom rules leave it unguarded lets a branch write the root
  staging area, and that was already true for the exact spelling before this change. The "pass the same instance"
  wording (fifth note) and "pass each branch once" (Q4) are in the `promote` javadoc. The case-variant and cross-run
  parts of Q5 (fourth note) are in EE-46.
- **Left.** Sharing the staging prefix *only* when the parent guards it (first note, second half) was not done. A
  branch that did not share the prefix could not read a staged skill through the absolute path `stage()` returns: the
  scope would map it into the branch, where nothing is staged. That is EE-26's failure, made general. Mentioning the
  branch's `.aimon/` in the descriptor's `notes` (sixth note) was not done either. The parent does not mention its own
  `.aimon/` in `notes` either, and the refusal names the path.

### 8.3 Open questions — where each one went

| Q | Outcome |
|---|---|
| Q1 strict lineage | Default kept (lenient). The §13 row was added to [`execution-environment.md`](execution-environment.md) as a recommendation. Registered as **EE-47**, since it depends on aimon-sandbox. |
| Q2 metadata pre-flight | Included (DV-1, DV-2). |
| Q3 case-unique keys | Confirmed by review 1: segments come from `next("a")` / `next(kind)` plus list indexes, so framework keys cannot differ only by case. The limitation is in the `LocalExecutionEnvironment.isolate` javadoc and the EE-27 closing note. |
| Q4 two objects for one key | Default kept. Documented in the `promote` javadoc and registered with Q1 in **EE-47**, because it is the same kind of promote-side gap. |
| Q5 `.worktrees/` injection | Not fixed here. Registered as **EE-46**, with review 1's case variant (`{ws}/.worktrees/K/...` from branch `k`) and its note that framework keys repeat across runs (`a0`). |
| Q6 EE-42 overlap | Only the `resolveEnv` catch block was edited. The `EnvironmentRequest` build above it is untouched. |
| Q7 refusal names the delegate path | Accepted as the default. |
| Q8 closing date, commit shape | Items closed 2026-09-29. The commit is left to the pipeline, as the run's instructions said. |

**Backlog numbering.** The items were first registered as EE-43 and EE-44, the next free numbers on this branch. The
concurrent EE-42 branch had already taken EE-43 to EE-45, so before merging they were renumbered to EE-46 and EE-47.
On this branch alone, the register therefore skips EE-43 to EE-45; the merge fills the gap. The same merge moved this change's
section of the implementation plan from §10.8 to §10.9, since EE-42 had taken §10.8.

### 8.4 Review 2 — what the build review changed

*Appended 2026-09-29, after an independent review of the build (no blocking findings, two should-fix, four nits).
All six were taken; the review's point about environment `toString()` in ownership messages was left, as
acceptable.*

- **DV-6 — a branch-local directory under a shared prefix is left out of branch listings.** A shell in branch `k` can
  run `mkdir .aimon-staged && cp …` (or `.Aimon-Staged/…`, since EE-27 folds case). The re-anchored rule for it,
  `.worktrees/k/.aimon-staged`, is `READ_ONLY`, not `DENY`, so `listRecursive(".")` listed `.aimon-staged/x`. But
  `scope()` routes every spelling of that path to the root staging area, so the merge then read the root's file (or
  none, aborting the pre-flight) and wrote the root's `READ_ONLY` directory — EE-8's half-merge by another route.
  Two fixes were possible: filter the listing in `ScopedVirtualFileSystem`, or make the re-anchored copy of each
  shared prefix `DENY`. **The filter was chosen.** The listing is where the class's own invariant ("staged files never
  appear in a branch listing") is stated, and the entry is dropped for the reason that holds whatever the rules are:
  no caller path reaches it. A `DENY` would only work while the parent's rules cover the staging prefix, and it would
  make the branch rules differ from the parent's — the invariant §1.1 keeps and EE-46 declines to break for the same
  reason. The filter drops only entries under the branch prefix; a listing of `.aimon-staged` itself still shows the
  parent's staged copies. As with a shell-made `.aimon/` (EE-8, closing note 2), the directory stays in the branch
  unpromoted and unreported. Tests: `ScopedVirtualFileSystemTest.branchLocalSharedDirectoryIsNotListed` and
  `LocalIsolatedEnvironmentTest.shellWrittenStagingCopyIsNeverPromoted`, which write straight to the host directory
  and merge without touching the staging area.
- **DV-7 — the branch host path is matched after normalisation and ignoring case.** `toBranchRelative` compared
  `{base}/{prefix}` literally, before normalising and with case. `{ws}/./.worktrees/k/.aimon/x`,
  `{ws}//.worktrees/k/.aimon/x` and `{ws}/.worktrees/K/.aimon/x` therefore became `.worktrees/k/.worktrees/k/.aimon/x`,
  past the branch rules, and a merge promoted them to the root's `.worktrees/k/.aimon/x` — branch `k`'s own hidden
  directory. (The third is the case variant §8.3 registered under Q5 as part of EE-46.) Now the remainder below the base is normalised first, and the branch prefix is stripped when the leading
  segments equal it ignoring case (compared with `VfsPaths.isUnderIgnoreCase` both ways, since case folding may change
  a string's length). A relative path is left alone: it is already branch-relative. So "every spelling reduces to one
  delegate path", which §1.1 and the docs claimed, is now true of every spelling of a *branch* path; what it is still
  not true of — a path that names `.worktrees/` from inside the branch — is EE-46, whose text was rewritten to say
  exactly what remains. Side effect: `{ws}/.worktrees/k/../../x` is now the base path `{ws}/x` (the branch's `x`)
  rather than an escape error, as the same input without the detour already was. Tests:
  `ScopedVirtualFileSystemTest.branchHostPathMatchesEverySpelling` / `branchHostPathClimbingOutIsABasePath` and the
  three spellings added to `LocalIsolatedEnvironmentTest.branchLocalControlDirectoryIsDenied`.
- **DV-8 — the pre-flight also checks destinations.** The metadata pre-flight proved each source readable, not each
  destination writable, so a file a shell wrote under a custom `READ_ONLY` directory of a branch (`vendor/`) still
  half-failed a merge. `promote` now checks every destination against the parent's rules before its metadata
  pre-flight. `WorktreeMerge` lives in `agent.impl.orca.environment` and may not import `filesystem.impl` (ArchUnit),
  so the rules are read through a new neutral accessor, `VirtualFileSystems.pathRules(VirtualFileSystem)`, which
  returns the rules of a filesystem `withPathRules` built and an empty list for anything else. **Its limit:** only the
  outermost layer is seen. The local provider's parent filesystem *is* that layer; a provider that wraps it in another
  decorator, or guards writes some other way, is checked only when each file is written, as before. Test:
  `WorktreeMergeTest.readOnlyDestinationAbortsBeforePromoting`.
- **DV-9 — "before any I/O" holds for the local provider only.** The ownership checks call each environment's
  `fileSystem()`, and §13 of the environment design allows a provider to provision on first use. The wording is now
  "before any file is read or written" in the `promote` javadoc, the CHANGELOG, the environment and workflow designs
  and the EE-28 closing note. The body above (§1.5, §4) is left as approved.
- **Nits.** Javadoc lines in `ScopedVirtualFileSystem` that the formatter had broken mid-sentence (the class
  javadoc's list and a constructor parameter) were reflowed. `WorkflowPhase4Test.isolateOnFailingProviderReportsTheReason` now also asserts that the cause is the
  `ExecutionEnvironmentUnavailableException` and that the root cause's message is the provider's own
  (`"sandbox down"`); both held.

### 8.5 Later — EE-46: the worktree root is reserved in the scope

*Appended 2026-10-05, when EE-46 (Q5) was worked. The body above is left as approved.*

**What was done.** `ScopedVirtualFileSystem` takes a set of *reserved prefixes*, and `LocalIsolatedEnvironment` passes
`.worktrees`. A path whose branch-relative form is at or under a reserved prefix (whole segments, ignoring case, after
normalisation) is refused by every operation with `InvalidPathException`; a branch-local entry there is left out of
`list` / `listRecursive` / `search`, so a merge — which works from the branch's listing — never sees a `.worktrees/`
directory a shell made in the branch root. Both halves were asked for: the refusal so that a write does not vanish
without a word, the listing filter for what never went through the file tools.

**Why this does not contradict §1.1.** §1.1 rejected "teach `ScopedVirtualFileSystem` a list of denied prefixes"
because it "would duplicate `PathRuleVirtualFileSystem`'s semantics … inside a second class", and §7 Q5 declined a
`DENY {branchPrefix}/.worktrees` rule because the parent has no such rule. Both reasons are about `.aimon/`-like
prefixes: ones the **parent's rules already describe**, so that composing a re-anchored copy of those rules reuses one
implementation and keeps "the branch obeys the same rules as the parent". Neither applies to `.worktrees/`:

- There is no parent rule to re-anchor. The composition §1.1 chose has nothing to compose, and the only rule-shaped
  fix is the branch-only `DENY` that Q5 already turned down.
- What is needed is not an access level. Nothing is hidden from `exists`, there are no subtree checks and no
  `READ_ONLY`; one name is not part of the branch's name space at all. That name is the scope's own — `.worktrees/` is
  where the scope's prefix lives — which is the kind of thing this class already owns: the shared prefixes are routed
  by it, and DV-6 filters its listings for the same "no caller path reaches it" reason.

So the invariant is intact: `LocalIsolatedEnvironment` still builds the branch rules from `parent.pathRules()` and
nothing else, and `LocalIsolatedEnvironmentTest.worktreeRootIsReservedWithoutPathRules` shows the reservation holding
for an assembly with no rules, where a rule could not.

**Refusing the name, against "a legitimate directory name inside the branch".** DV-7 strips the branch prefix from an
*absolute* path and leaves a relative `.worktrees/k/…` alone, and EE-46 recorded why: stripping it would silently turn
a name the caller wrote into a different file. That argues against *rewriting* the path, not against refusing it. A
branch starts empty and holds only what its step writes, and every path in it is promoted to the same path under the
workspace root — so a branch-root `.worktrees/…` has exactly one possible destination, the directory the provider
keeps its branches in. There is no outcome of such a write that is not an injection into another branch, a file under
this branch's own root that the next merge promotes a second time, or litter in the provider's directory. The name is
therefore taken at the branch root only; `docs/.worktrees/x` is an ordinary path, and the branch's own absolute root
keeps working (it is stripped before the check).

**What the reproduction showed** (macOS APFS, before the change, one merge of branch `k` after five writes from it):
`.worktrees/other/rel.txt` and `{ws}/.worktrees/other/abs.txt` were promoted into branch `other`'s directory and then
appeared in `other`'s own listing; relative `.worktrees/k/own.txt` and `.worktrees/k/.aimon/hidden.txt` were promoted
to the root's `.worktrees/k/…`, the first now a file of branch `k` again, the second in the place the branch's rules
hide. `.worktrees/K/OWN.txt` was the same file as `own.txt` on that disk.

**Narrower than the item read (rule six).** `WorktreeMerge.promote` has **no caller in this repository's main
sources** — only tests call it. It is the public helper an assembling application calls, so the injection reached a
deployment only through such a caller. The nesting itself (a file outside the branch's rules) needed no merge.

**Left open: branch keys repeat across runs.** `DefaultWorkflowContext.sanitizeBranchKey` derives the key from the
step's structural path alone, so every run's first isolated step is `a0`, whatever the run id and whichever runner.
`WorkflowPhase4Test.successiveRunsShareTheFirstIsolatedBranch` measures it: two runners with different run ids write
`first.txt` and `second.txt` into the one `.worktrees/a0`, and a merge of that branch promotes both. It was not
changed here, for three reasons that are each a decision rather than a detail:

- **Determinism is what the merge contract rests on.** `LocalExecutionEnvironment.isolate` and `WorktreeMerge` both
  document that an assembler knowing only a key gets the run's branch back with `parent.isolate(key)`. A key that
  holds something the assembler does not have breaks that.
- **The run id cannot make it unique.** `run(script)` uses the shared `DEFAULT_RUN_ID` for every default run, so a
  key built from the run id still collides for exactly the runs most likely to share a workspace. A nonce per
  invocation would be unique and would not survive a resume.
- **Nothing removes a branch directory.** No code in the framework merges or deletes `.worktrees/{key}`; today the
  set of directories is bounded by the set of step paths because runs reuse them. A key per run turns that into one
  directory tree per run, forever, unless a cleanup is designed with it.

EE-28's ownership checks would not be affected either way: they compare environment instances and lineage, not keys —
which is also why they cannot see two runs sharing a key.
