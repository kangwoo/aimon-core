# #131 — the release gate's documents, two test sentences, and the release skill as a test input

> Status: **IMPLEMENTED** — `.claude/skills/release/SKILL.md` as an input of `aimon-core`'s `test` task
> (`modules/aimon-core/build.gradle.kts`); the quality-gate row of `docs/project/publishing-guide.md`; two sentences in
> `ReleaseGateMatchesCiGateTest`, the `UP-TO-DATE` sentence of its *What this cannot see* and the key census's failure
> message; the tag exclusions `CLAUDE.md`'s `./gradlew test` line and `.claude/rules/testing.md` name; and #90's bullet
> in `CHANGELOG.md`, corrected in place. Source: issue [#131](https://github.com/kangwoo/aimon-core/issues/131), a
> follow-up to [`provider-key-census-claim-and-inputs.md`](provider-key-census-claim-and-inputs.md) (#124).
>
> **[§11](#11-after-the-build--departures-decisions-and-measurements), appended after the build, is where the build
> departs from this document.** Everything between this header and §11 is the body as approved in design review
> round 1 (PASS, no blocking findings, seven non-blocking notes), kept byte-exact rather than corrected — the house
> habit in this directory, for the reason
> [`model-capability-binding-round-trip.md`](model-capability-binding-round-trip.md) gives. Its `file:line` citations
> and commit counts are at `main` `2eddf3d`. The review transcript (`review-1.md`), the answers to §10's open
> questions (`decisions.md`) and the run records the body names (`TASK.md`, `$RUN_DIR/build/measurements.md`,
> `build/deviations.md`) are not in the repository; §11 reproduces what they decided and what was measured.
>
> Nothing this work left open went to the backlog, and `LA-3` was not used.

*Design for `release-gate-docs-followups`. Base: `main` at `2eddf3d`. Every `file:line` below was read at `2eddf3d`
unless it says otherwise. The issue cites `895ed2d`; §2 re-verifies each citation it relies on, and names the ones
#128 moved.*

---

## 1. The problem

#124 made the key census's provider-module test sources inputs of `:aimon-core:test` and corrected the records #112
left, and #131 lists six things it left behind. Two are statements about the release gate that are not true of the
tree. The publishing guide's table says the gate is `checkAll`, "the same task as CI", while `scripts/release.sh` runs
five tasks in one invocation and CI runs those five as steps of three jobs, so the table asks for a Docker daemon that
nothing it lists uses. And `CLAUDE.md` and `.claude/rules/testing.md` name `docker` as the only tag `test` excludes,
where the build excludes three. Two are sentences in `ReleaseGateMatchesCiGateTest` that no longer match the code:
the *What this cannot see* sentence saying any other module's `@Tag` edit can leave the test `UP-TO-DATE`, which #124's
declaration made false for the provider modules, and the census failure message saying the list it prints is what the
script refuses, when the assertion never reads the script. One is a record: #90's `CHANGELOG.md` bullet says `test`
excludes only `docker` and `packaging`, which was too broad the day it was written. The last is a build gap of the kind
#124 closed. The two guards that read `.claude/skills/release/SKILL.md` do not run locally after an edit to that file
alone, because the file is not an input of the task that runs them.

---

## 2. Premises, re-verified at `2eddf3d`

| Item | Premise | At `2eddf3d` | Holds? |
|---|---|---|---|
| 1 | The guide's gate row says `checkAll`, the same as CI | `docs/project/publishing-guide.md:143`: `` `checkAll` — CI 와 **같은** 태스크 ``. The pre-flight cell `:141` names `docker info` | Yes |
| 1 | The script's gate | `scripts/release.sh:237`: `$GRADLE checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification`. The Docker check `:129-135` exists because "the quality gate in §4 runs `integrationTest`" | Yes |
| 1 | CI runs them as separate steps | `.github/workflows/build.yml:107` `checkAll`, `:120` `packagingTest`, `:205` `playwrightTest` (job `build`); `:323` `integrationTest` (job `integration`); `:436` `jacocoTestReport -x test`, `:442` `jacocoTestCoverageVerification -x test` (job `coverage`). The issue's `:100`, `:113`, `:198`, `:316`, `:435` are the same steps before #128 added seven lines | Yes; lines moved |
| 1 | Nothing reads the guide | `git grep -n publishing-guide -- '*.java' '*.kts' '*.py' '*.sh' '*.yml'` prints nothing | Yes |
| 2 | The javadoc sentence | `ReleaseGateMatchesCiGateTest.java:117-118`. `:112-116` say the provider modules' test sources are inputs. The tag scan (`:750-775`) walks every `/src/test/` `.java` under `modules/` and `samples/` | Yes |
| 2 | The declared tree | `modules/aimon-core/build.gradle.kts:72-73`, `providerModuleTestSources` | Yes |
| 3 | The message | Format `:522`, arguments `:528-529`, the `isEqualTo` it labels `:530`. Display name `:512`. Class javadoc "at least" `:70-74` | Yes |
| 4 | The skill is read and not declared | `RELEASE_SKILL` `:131`, read at `:337-343` (`releaseSkillDoesNotCallAGatedTierUngated`) and `:808-813` (`skillDeclaredGateTasks`, used by `releaseSkillDescribesTheRealGate` `:300-313`). The `tasks.test` block `build.gradle.kts:69-79` declares six inputs, none of them the skill, and its comment `:58-59` lists "the release script and the CI workflow". `git grep -n 'skills/release' -- '*.kts'` prints nothing | Yes |
| 4 | CI runs both guards on every build | No `org.gradle.caching` in `gradle.properties` or `settings.gradle.kts`, and no CI step passes `--build-cache`, so a fresh checkout has no task history and no cache to reuse | Yes |
| 4 | What editing the skill has cost | 191 non-merge commits reach `2eddf3d`. Five touched the skill: `eec9ccd` (initial), `da2b940` and `3c34956` (the skill alone), `0982aa5` and `5801997` (the skill and `ReleaseGateMatchesCiGateTest.java`, among others) | — |
| 5 | The two files | `CLAUDE.md:10`; `.claude/rules/testing.md:29` | Yes |
| 5 | The three exclusions | `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:130-131` (`docker`, `packaging`); `modules/aimon-browser-playwright/build.gradle.kts:24` (`playwright`). `git grep -n excludeTags -- '*.gradle.kts'` finds no other | Yes |
| 5 | `build` and `check` add no tier | `integrationTest` and `packagingTest` carry only `shouldRunAfter(test)` (conventions `:144`, `:160`), and so does `playwrightTest` (`aimon-browser-playwright/build.gradle.kts:82`) | Yes |
| 5 | `CONTRIBUTING` names three | `CONTRIBUTING.md:58` and `:127-132`; `CONTRIBUTING.ko.md:63` | Yes |
| 6 | #90's bullet | Starts at `CHANGELOG.md:2103`; "only" is at `:2105`. `[Unreleased]` is `:8`, `[0.2.4]` is `:2195`. The issue's `:2080-2081` predates #128 | Yes; lines moved |
| 6 | Too broad when written | `git log -S'excludeTags("playwright")' -- modules/aimon-browser-playwright/build.gradle.kts` returns only `eec9ccd` (2026-08-31). #90 is `0335838` (2026-09-10) | Yes |

Nothing in scope is already fixed on `main`.

---

## 3. Decisions

### D1 — Item 4: declare the skill an input of `:aimon-core:test`

**Taken:** one `inputs.file` line in the `tasks.test` block, and the skill named in that block's comment. The build
measures it as §7 describes.

**Rejected:** writing the gap down where contributors will meet it (B1 below).

**Why.**

1. **The gap sits on exactly the edit the guards exist for.** Both guards assert on the skill's text, and no edit to
   any other file can make either fail. Without the declaration, the only local build in which they can fail is one
   that re-runs `:aimon-core:test` for an unrelated reason. The block's own comment names this outcome
   (`build.gradle.kts:54-56`): "the offending edit leaves `test` UP-TO-DATE and the guard never runs — the build reports
   green on exactly the change the test exists to catch."
2. **The price is small.** The declaration makes a build re-run `aimon-core`'s suite (#124 measured 8168 tests in 40s)
   only when the skill changed and nothing else that suite depends on did. That describes two of 191 commits
   (`da2b940`, `3c34956`). The other two post-initial commits that changed the skill also changed the test, which
   re-runs the suite anyway. #124 declined the tag scan's sources at 29 of 171 (its B2) and declared the census's at
   9 of 171. This is a fraction of both.
3. **There is no place to write the gap where the person it hits reads it.** That person is editing the skill. The
   skill is a prompt, so a note in it is text the model reads on every `/release`. The test's javadoc and the build
   script are read by someone changing the test or the build. `CONTRIBUTING.md` is not this run's file. And the gap has
   already been written down once without effect: #124's record says its own skill edit needed `--rerun` for this
   reason (§11.4 of `docs/design/llm/provider-key-census-claim-and-inputs.md`), and #131 item 4 is the result.
4. **The line uses the block's own idiom.** `releaseScript` and `ciWorkflow` are single files the same test reads from
   the repository root, declared as `inputs.file(rootProject.file(…)).withPropertyName(…)`.
5. **Why #124 did not.** Its open question 1 defaulted to "no" because the change fell outside #119's six items, not on
   the merits. Its record calls it "one line in the block D2 already edits" (§10, and §11.3's Q1 row).

No public API, configuration key, wire name or persisted identity changes. The task's inputs do, and `CHANGELOG.md`
says so (D5).

| Option | Why not |
|---|---|
| **B1** Write the gap down (test javadoc, build comment, or `CONTRIBUTING.md`) | Reasons 1 and 3 |
| **B2** Run the class in its own `Test` task with its own inputs | The class holds every CI verification task to the release gate, so a new task means editing `build.yml`, `release.sh` and the skill's `Quality gate =` line, plus excluding the class from `test`. #124 rejected the same shape (its B4) |
| **B3** Put the skill on the test classpath and read it from there | Changes a published module's test source set. The class reads repository-root files on purpose (`locateRepositoryRoot`, `:873-883`), and one of its three files would read differently from the other two |
| **B4** `outputs.upToDateWhen { false }` on `:aimon-core:test` | Re-runs 8168 tests on every build (#124's B3) |
| **B5** Declare `.claude/skills/release/` or `.claude/skills/` | The test reads one file. `.claude/skills/` holds three other skills no test reads, and an input wider than what is read re-runs the suite for nothing |
| **B6** A self-check that every repository-root file the test reads is declared | A Java test parsing Kotlin DSL text. #124's D2 chose cross-references over a self-check for the same pair of copies |

**Observable change:** after an edit to the skill alone, `:aimon-core:test` executes where it reported `UP-TO-DATE`, so
a `checkAll` in that build runs `aimon-core`'s suite and both skill guards.

### D2 — Item 6: correct #90's bullet in place, by limiting "only"

**Taken:** edit #90's sentence where it stands, limiting "only" to the two provider modules the bullet is about.

**Rejected:** superseding it from this run's entry, and naming every tag `test` excludes.

**What the file has done before.** The corrections TASK names split along one line.

| Change | The sentence it corrected | What the sentence described | Form |
|---|---|---|---|
| #124 (`02ceb7e`) | #112's, in #98's entry: the test "holds the refused set equal to" the key gates | The tree it shipped with, wrongly. The test never held equality; `docs/backlog/live-api-test-tier.md`'s #119 correction says so | Edited in place |
| #125 (`64d5b2a`) | #45's (`AnthropicConfig` keeps its default because `claude-sonnet-4-20250514` is current) and #109's (the fallback default is unmeasured, `L-24`) | The tree before #116 changed the default and closed `L-24` | "This supersedes two sentences in the entries below" |
| #126 (`b0cc272`) | #113's (`TaskTool` still prints `Status: SUCCESS`; a fork has no stalled-iteration guard) | The behaviour before #126 changed it | "This entry supersedes two sentences of #113's entry below" |

A superseding sentence was used where the correcting change itself changed the fact, so the old sentence is the
"before" and the new entry is where the "after" belongs. An in-place edit was used where nothing changed and the
sentence had misdescribed the tree it was written against.

**Why in place.**

1. **#90's sentence is the #124 case.** `aimon-browser-playwright` had excluded `playwright` since `eec9ccd`, nine days
   before `0335838`, so "only" was wrong when written.
2. **This run changes no exclusion.** A superseding sentence would have no change of its own to belong to. It would be a
   correction of a record wearing the form of a change.
3. **The bullet is in `[Unreleased]`, so it ships as release notes.** Superseding would ship the wrong sentence and its
   correction in one release section, and a reader of #90's bullet would never meet the correction.

**Why limit "only" rather than name every tag.** The issue offers both. Limiting is the smaller edit and keeps the
bullet's argument intact. The four classes live in `aimon-llm-anthropic` and `aimon-llm-openai`, whose `test` excludes
exactly `docker` and `packaging`, and the conclusion (an exported key runs the live classes) rests on those two
modules alone. Naming `playwright` would bring in a module the bullet is not about and restate `CONTRIBUTING.md`.

**The trace.** This run's entry mentions the edit in one clause (§4.5b, the *Wording* bullet). The clause carries none
of the content the bullet needs, so it records the edit rather than superseding it. Review can strike the clause
without touching D2.

### D3 — Item 1: rewrite the row, and add no check that reads the guide

**Taken:** the quality-gate cell names the five tasks the script runs, says CI runs the same tasks as steps of its
`build`, `integration` and `coverage` jobs plus one task that cannot fail a build, and ties pre-flight's Docker check to
`integrationTest`. Nothing else in the guide changes.

**Rejected:** extending `ReleaseGateMatchesCiGateTest` to read the guide's row, for example with a fixed-phrase match
like `SKILL_GATE_DECLARATION`.

**Why.** The issue asks for the row. A guard would add a Korean fixed phrase that must stay stable, a fourth
repository file the class reads, and a seventh input to declare (D1's reasoning would then apply, with its own
measurement). It would also be a behaviour change to a reviewed test in a follow-up whose issue does not ask for one.
This repository's habit is to leave that widening to the maintainer. §9 records that the guide is a fourth
hand-maintained statement of the gate, where the class javadoc (`:33`) counts three.

**What the cell names, and what it leaves out.**

- **Job names instead of `build.yml` lines.** The lines have moved once since the issue was filed, and
  `heading-self-test-followups` is editing the same file.
- **`jacocoTestReport`, named once.** It is the one task CI runs that the gate does not, and the test exempts it as
  reporting-only (`REPORTING_ONLY_CI_TASKS`, `:152`). Leaving it out would make "the same tasks" false in the other
  direction.
- **No Chromium download.** The skill's Notes cover it (`SKILL.md:86-90`), and the issue does not ask (§9).

### D4 — Items 2, 3 and 5: wording

- **Item 2.** Only the "So a local build…" sentence narrows. The sentence before it, "The tag scan's sources are not",
  speaks of the scan's sources as a set, and that set is not declared. The issue's Notes say item 2 "only narrows that
  javadoc sentence". The narrowed sentence excludes this module as well as the provider modules: a `@Tag` edit to
  `aimon-core`'s own tests changes its compiled test classes, which are the task's inputs.
- **Item 3.** Only the format string changes. The four arguments stay, so the first line says what `isEqualTo` compares
  and matches the display name (`:512`).
  - *Rejected:* dropping `RELEASE_SCRIPT` from that line, which changes the argument list for no reader.
  - *Rejected:* rewording the guidance lines after it, which say what to do and are each still true.
- **Item 5.**
  - `CLAUDE.md` copies `CONTRIBUTING.md:58`'s parenthetical exactly.
  - `.claude/rules/testing.md` keeps its docker bullet and adds a parenthetical naming the other two tags and the tasks
    that run them. The parentheses keep "Run them with `./gradlew integrationTest`" on the next line pointing at the
    docker tests.

### D5 — `CHANGELOG.md`: this run's own entry

**Taken:** a new `###` entry at the top of `[Unreleased]`.

**Rejected:** `(#131)` bullets inside #98's entry, which is what #124 did with its `(#119)` bullets.

**Why.** TASK's run table lets this run edit only one existing entry, #90's bullet, and #98's entry is not in its row.

### D6 — No design record, and `LA-3` unused

This document is not committed under `docs/design/` by default (open question 2). No finding of this run is a
live-API-tier item, so `LA-3` stays unused. The one candidate for tracking, a guard on the guide, belongs to no register
this run reserved, so it goes to `build/deviations.md` and the PR body, as TASK directs.

---

## 4. Changes by file

### 4.1 `docs/project/publishing-guide.md` — item 1

Replace `:143`:

```markdown
| 품질 게이트 | `checkAll` — CI 와 **같은** 태스크 |
```

with:

```markdown
| 품질 게이트 | `checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification` — 한 번의 Gradle 호출로 돈다. CI 는 **같은** 태스크를 `build` · `integration` · `coverage` 잡의 스텝으로 나눠 돌고, 빌드를 실패시키지 않는 보고서 태스크 `jacocoTestReport` 를 하나 더 돈다. pre-flight 가 Docker 데몬을 확인하는 것은 `integrationTest` 때문이다 |
```

The table keeps its row count. No `.en.md` exists: `docs/project/` is "아직 아님" in `documentation-guide.md` §5.1.

### 4.2 `modules/aimon-core/src/test/java/at/aimon/core/architecture/ReleaseGateMatchesCiGateTest.java` — items 2 and 3

**(a) Javadoc, `:117-118`.** Replace:

```java
 * module. So a local build that changes only another module's {@code @Tag}s can report this test {@code UP-TO-DATE};
 * CI builds from a fresh checkout and does not.
```

with the sentence:

> So a local build that changes only the {@code @Tag}s of another module whose test sources are not inputs — one
> outside {@code modules/aimon-llm-*}, or a sample — can report this test {@code UP-TO-DATE}; CI builds from a fresh
> checkout and does not.

"module." stays at the start of `:117`, closing the sentence before it.

**(b) Failure message, `:522`.** Replace the first line of the format string:

```java
"the provider modules' tests are gated on %s, but %s refuses %s.%n"
```

with:

```java
"the provider modules' tests are gated on %s, but the keys the refusal cases run %s with are %s "
        + "(PROVIDER_KEY_VARIABLES).%n"
```

The arguments stay `gated, RELEASE_SCRIPT, PROVIDER_KEY_VARIABLES, RELEASE_SCRIPT`, and the continuation lines are
unchanged. With §7's probe, the rendered first line is:

```
the provider modules' tests are gated on [ANTHROPIC_KEY, OPENAI_KEY, SCRATCH_PROBE_KEY], but the keys the refusal cases run scripts/release.sh with are [ANTHROPIC_KEY, OPENAI_KEY] (PROVIDER_KEY_VARIABLES).
```

`./gradlew format` may rewrap the javadoc paragraph and the concatenation. Accept whatever it produces.

### 4.3 `modules/aimon-core/build.gradle.kts` — item 4

**(a) Comment, `:58-59`.** Change "reads the release script and the CI workflow, and its key census reads…" to:

```kotlin
// `ReleaseGateMatchesCiGateTest` reads the release script, the CI workflow and the `/release` skill, and its key
// census reads every `.java` under `modules/aimon-llm-*/src/test`. …
```

Reflow `:58-63` to the block's width (at most 120 columns). No other words in the comment change.

**(b) The declaration**, inserted after `:71` so that the test's three single files sit together:

```kotlin
tasks.test {
    inputs.file(rootProject.file("scripts/release.sh")).withPropertyName("releaseScript")
    inputs.file(rootProject.file(".github/workflows/build.yml")).withPropertyName("ciWorkflow")
    inputs.file(rootProject.file(".claude/skills/release/SKILL.md")).withPropertyName("releaseSkill")
    inputs.files(rootProject.fileTree("modules") { include("aimon-llm-*/src/test/**/*.java") })
        .withPropertyName("providerModuleTestSources")
    …
}
```

### 4.4 `CLAUDE.md:10` — item 5

```
./gradlew test                     # Run all unit tests (excludes @Tag("docker"), @Tag("packaging") and @Tag("playwright"))
```

The column of `#` is unchanged, and no other line changes.

### 4.5 `CHANGELOG.md` — item 6, and this run's entry

**(a) #90's bullet, in place (D2).** At `:2105`, change

> classes carry no tag, and `test` excludes only `docker` and `packaging` — so while `OPENAI_KEY` or

to

> classes carry no tag, and in their two modules `test` excludes only `docker` and `packaging` — so while

Then reflow only as far as the paragraph needs: `` `OPENAI_KEY` or `` moves to the start of `:2106`, "each time" moves
to the start of `:2107`, and `:2107` still ends "with the variable". Locate the line by its content, not its number:
sibling runs add entries above it.

**(b) The new entry, at the top of `[Unreleased]` (D5).** Fill the `<…>` from `build/measurements.md`.

```markdown
### Release gate: an edit to the `/release` skill re-runs the tests that read it, and the documents name the gate's tasks

- **`.claude/skills/release/SKILL.md` is an input of `aimon-core`'s `test` task** (#131). `ReleaseGateMatchesCiGateTest`
  reads it in two tests — that the skill names the tasks `scripts/release.sh` gates a release on, and that no line of
  it calls a gated tier opt-in — but the file was not declared, so a local build whose only change was to the skill
  reported `:aimon-core:test` `UP-TO-DATE` and ran neither test; CI, which builds from a fresh checkout, ran both.
  Measured with an earlier revision of the skill that both tests fail on: `UP-TO-DATE` before the declaration; after
  it, the task executed and failed exactly those two. **The price:** a build after an edit to the skill alone also runs
  `aimon-core`'s suite (measured: `:aimon-core:test --rerun` ran <N> tests in <T>).
- **Documentation.** `docs/project/publishing-guide.md`'s quality-gate row said the gate is `checkAll`, the same task
  CI runs. It now names the five tasks the script runs in one invocation, says CI runs the same tasks as steps of three
  jobs, and says `integrationTest` is why pre-flight checks for a Docker daemon. `CLAUDE.md` and
  `.claude/rules/testing.md` name all three tags `test` excludes, as `CONTRIBUTING.md` does.
- **Wording.** The key census's failure message says what it compares — the provider modules' key gates against the
  keys the refusal cases run the script with — where it said the script refuses that list. The test's *What this
  cannot see* limits its `UP-TO-DATE` sentence to modules whose test sources are not inputs. #90's bullet under *Build,
  CI and the release gate* limits its "only" to the two provider modules it is about: `aimon-browser-playwright` had
  excluded `playwright` since before that bullet was written.
```

The entry quotes only measured numbers, not commit counts (#124's DV-5). The commit counts go in the PR body.

### 4.6 `.claude/rules/testing.md:29` — item 5, edited last

Replace:

```markdown
- `./gradlew test` / `build` / `check` **exclude** `@Tag("docker")` so unit tests stay fast and daemonless.
```

with:

```markdown
- `./gradlew test` / `build` / `check` **exclude** `@Tag("docker")` so unit tests stay fast and daemonless.
  (`test` also excludes `@Tag("packaging")` in every module and `@Tag("playwright")` in `aimon-browser-playwright`;
  those run via `./gradlew packagingTest` and `./gradlew playwrightTest`.)
```

If the write is refused, leave the file unchanged, put this block in the PR body, and apply open question 3.

### 4.7 Deliberately not changed

- **`.claude/skills/release/SKILL.md`.** §7 probes it with `git restore` and restores it, so the PR leaves it
  byte-identical to `2eddf3d`.
- **`scripts/release.sh` and `.github/workflows/build.yml`.** Read only. The script is never run, except by the test
  inside its sandbox.
- **`docs/design/llm/provider-key-release-gate.md` (#112) and `provider-key-census-claim-and-inputs.md` (#124).** Both
  are exempt approved records (§9).
- **`CONTRIBUTING.md` and `.ko.md`.** They already name all three tags.
- **Translations.** None of the changed files has one. `docs/project/` has no `.en.md`, and `CLAUDE.md`,
  `CHANGELOG.md` and `.claude/` are not translated.

---

## 5. Shapes that change

| Surface | Before | After |
|---|---|---|
| `:aimon-core:test` input properties | `releaseScript`, `ciWorkflow`, `providerModuleTestSources`, `moduleBuildScripts`, `sharedBuildScripts`, `rootBuildScript` | The same six, plus `releaseSkill` (the file `.claude/skills/release/SKILL.md`) |
| After an edit to the skill alone | `UP-TO-DATE`; neither skill guard runs | Executes; the suite runs, both guards included |
| The skill missing at its path | The test fails with "`.claude/skills/release/SKILL.md` not found — this test is pointed at the wrong path" | Expected: Gradle rejects the task because an input file does not exist, before any test runs, which is what `releaseScript` and `ciWorkflow` already do. **Not measured**, because measuring it means deleting a file under `.claude/` |
| First line of the census failure message | `…gated on %s, but %s refuses %s.` | `…gated on %s, but the keys the refusal cases run %s with are %s (PROVIDER_KEY_VARIABLES).` |
| Java API, configuration keys, wire names, persisted identities | — | Unchanged |

Everything else is prose: one table cell, one javadoc sentence, two comment lines, one command comment, one rule
bullet, and one `CHANGELOG` sentence.

---

## 6. Failure modes

| Failure | Handling |
|---|---|
| A provider key exported in the build shell bills during a run | Before the first Gradle command, `env \| grep -c -E '^(OPENAI\|ANTHROPIC)_KEY='` must print `0`. Every Gradle command runs as `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew --console=plain …`. No step needs a key value, and no step reads a key file |
| `scripts/release.sh` runs for real | Never. Only the test's sandbox runs it |
| The skill probe is left in place or committed | The probe uses `git restore --worktree` only, so the index is untouched. Each probe ends with a restore and a check that `git diff --quiet HEAD -- .claude/skills/release/SKILL.md` and `git diff --cached --quiet` both exit 0. No commit is made while a probe is in place. Before every commit, `git status --porcelain -- .claude` shows nothing but the `testing.md` edit |
| A scratch Java probe is left in the tree | Probe names start with `Scratch`. `git status --porcelain \| grep -i scratch` must print nothing before `format`, before the gate and before each commit |
| A permission dialog on the skill probe (a write under `.claude/`) | TASK makes any dialog other than `testing.md`'s a halt. Three things make one unlikely and a halt cheap. The probe is a `git restore` of a tracked file, not an editor write. #124's build wrote this same file in auto mode. The probe runs after every other non-`.claude` edit is made and checked. See open question 1 |
| The `.claude/rules/testing.md` write is refused | Leave the file unchanged, put §4.6's block in the PR body, drop `.claude/rules/testing.md` from the entry's *Documentation* bullet, and apply open question 3 |
| The skill is later copied to a new path, the test's constant follows it, and the old file stays | The declaration keeps fingerprinting the old file, and the gap returns silently for the new one. A **move** fails loudly on both sides (§5). Mitigation: the build comment names the skill. No self-check (B6) |
| `./gradlew format` rewraps more than the edited lines | Check `git diff --stat` after `format`. Only the files §4 names may change. Line shifts in the test go under **Merge notes** (§8) |
| `CHANGELOG.md` conflicts with sibling runs | Expected by TASK and resolved at merge. Every edit is located by content, never by line number |
| The price is larger than #124 measured | D1 stands whatever the number. It is paid only on a skill-only edit, and §7 records it for the maintainer |
| The configuration cache, or Gradle's implicit-dependency validation | Same idiom as the six existing inputs, and no task writes under `.claude/`. §7 M2f is an optional check |
| The guide's row drifts again | Accepted. No check reads it (D3), and §9 hands the gap to the maintainer |

---

## 7. Test strategy and measurements

No new test is added. Item 4 changes a build input, which no unit test can observe, so M1 and M2 measure it. Items 2
and 3 are wording, and M3 exercises both: it renders the new message and shows the narrowed sentence true in both
directions. Items 1, 5 and 6 are prose, covered by the four doc checks. The final tree must pass `checkAll`.

Record every command, and each outcome line verbatim (`> Task :aimon-core:test UP-TO-DATE`), in
`$RUN_DIR/build/measurements.md`. Take test counts from the console summary or from
`modules/aimon-core/build/test-results/test/*.xml`.

- `G='env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew --console=plain'`
- `FOCUS='at.aimon.core.architecture.ReleaseGateMatchesCiGateTest'`, a class of 12 tests

The `--tests` filter is itself a task input, so keep it identical within a sequence.

**The skill probe.** It swaps in the skill as it stood before `5801997` and restores it with git, not an editor:

```sh
git restore --source='5801997^' --worktree -- .claude/skills/release/SKILL.md   # probe
git restore --worktree -- .claude/skills/release/SKILL.md                       # restore from the index (= HEAD)
git diff --quiet HEAD -- .claude/skills/release/SKILL.md && git diff --cached --quiet   # both exit 0
```

That revision is a real drift both guards were written to catch. Its declaration (line 80) omits `playwrightTest`, and
line 83 calls `playwrightTest` "the only opt-in tier outside both". Against today's `release.sh:237`,
`releaseSkillDescribesTheRealGate` and `releaseSkillDoesNotCallAGatedTierUngated` both fail, and no other test reads
the file. So "2 failed, those two" shows that each guard ran. Quote `'5801997^'`, because zsh treats `^` as a glob
character.

**Order.** Edits E1–E4 (§8 commits 1, 2, 4 and 5, with the new entry's placeholders left open) come first, and none of
them is a Gradle input except the test file. Then:

**M0 — key hygiene.** `env | grep -c -E '^(OPENAI|ANTHROPIC)_KEY='` prints `0`. Record the count only.

**M1 — the gap, before the declaration.** `build.gradle.kts` is untouched.

| Step | Action | Command | Expected |
|---|---|---|---|
| M1a | — (the test file changed in E2) | `$G :aimon-core:test --tests "$FOCUS"` | executes; 12 tests, 0 failures |
| M1b | nothing | same | `UP-TO-DATE` (control) |
| M1c | skill probe | same | **`UP-TO-DATE`** — the gap |
| M1d | probe still in place | same `--rerun` | executes; 12 tests, **2 failed**, both skill guards. So M1c's skip was Gradle's, not the tests' |
| M1e | restore the skill, and verify | — | both `git diff` checks exit 0 |

**E5 — write §4.3 (the declaration and the comment).**

**M2 — after the declaration.** TASK's required measurement is M2c.

| Step | Action | Command | Expected |
|---|---|---|---|
| M2a | — (the build script changed) | `$G :aimon-core:test --tests "$FOCUS" --info` | executes; 12 tests, 0 failures. Record Gradle's "not up-to-date because" lines, and whether `releaseSkill` appears in them. That appearance is supporting evidence, not the measurement |
| M2b | nothing | `$G :aimon-core:test --tests "$FOCUS"` | `UP-TO-DATE` |
| M2c | skill probe | same | **executes**; 12 tests, **2 failed**: `releaseSkillDescribesTheRealGate` and `releaseSkillDoesNotCallAGatedTierUngated` |
| M2d | restore the skill, and verify | same | executes; 12 tests, 0 failures |
| M2e | nothing | same | `UP-TO-DATE` |
| M2f *(optional)* | — | same `--configuration-cache` | no problem attributable to the new line. If one appears, rerun once without the line to attribute it |

**M3 — items 2 and 3, with probes in two modules.**

`modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ScratchProbe.java`:

```java
package at.aimon.core.llms.openai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("scratchprobe")
@EnabledIfEnvironmentVariable(named = "SCRATCH_PROBE_KEY", matches = ".+")
class ScratchProbe {
}
```

`modules/aimon-sandbox-docker/src/test/java/at/aimon/sandbox/docker/ScratchTagProbe.java`: the same package line for
its directory, the `Tag` import, and `@Tag("scratchprobe") class ScratchTagProbe {}` on two lines.

| Step | Action | Command | Expected |
|---|---|---|---|
| M3a | create `ScratchProbe` in `aimon-llm-openai` | `$G :aimon-core:test --tests "$FOCUS"` | **executes** (a provider module's test source changed); 12 tests, 2 failed: `everyTestTagIsGated` naming `scratchprobe`, and `refusedKeysAreTheProviderModulesKeyGates`. Record the census message's first line verbatim; it must read as in §4.2b |
| M3b | delete it | same | executes; 12 tests, 0 failures |
| M3c | create `ScratchTagProbe` in `aimon-sandbox-docker` | same | **`UP-TO-DATE`** — the gap the narrowed sentence still states |
| M3d | probe still in place | same `--rerun` | executes; 1 failed, `everyTestTagIsGated` naming `scratchprobe` |
| M3e | delete it | same | executes; 12 tests, 0 failures. Then `git status --porcelain \| grep -i scratch` prints nothing |

**M4 — the price.** Run `$G :aimon-core:test --rerun` unfiltered, once. Record `BUILD SUCCESSFUL in …`, and the tests,
failures, errors and skipped summed from the XML. These are the `<N>` and `<T>` of §4.5b.

**E6 — fill §4.5b's placeholders.** `CHANGELOG.md` is not a Gradle input.

**M5 — the gate.**

1. Run `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew format`. `git status` must show only §4's files.
2. Run `$G checkAll`. Record `BUILD SUCCESSFUL in …`, the executed and up-to-date task counts, and the tests, failures,
   errors and skipped summed across every module's `test` results.
3. Name any `test` task that reported `UP-TO-DATE`. `:aimon-core:test` may, on M4's inputs; add its M4 totals as #124
   did.

**M6 — doc checks, as TASK lists them and as CI runs them.**

- `python3 scripts/check-doc-links.py`, then `--self-test`
- `python3 scripts/check-backlog-registers.py --github`, then `--self-test`
- `python3 scripts/check-translation-staleness.py --github`: expect 0 stale and 0 unresolvable
- `python3 scripts/check-translation-structure.py --self-test`, then `--github`
- `mkdocs build --strict`, if installed; otherwise say it was not run

The link check walks every `*.md` under the root outside `.git`, `.gradle`, `.venv`, `build`, `node_modules` and
`site`, so it covers `CLAUDE.md`, `CHANGELOG.md` and `.claude/`.

**E7 — `.claude/rules/testing.md` (§4.6), last.** Then re-run M6's four scripts.

---

## 8. Commit plan, PR body and merge notes

Commits follow the `git log` style, one logical change each, with no attribution. They can be made after the
measurements, since the probes are restored first.

1. `docs(release): name the tasks the release gate runs, and how CI runs them, in the publishing guide (#131)` — §4.1
2. `test(core): say what the key census compares, and which @Tag edits can leave it UP-TO-DATE (#131)` — §4.2
3. `build(core): make the release skill an input of aimon-core's test task (#131)` — §4.3
4. `docs: name every tag test excludes in CLAUDE.md (#131)` — §4.4
5. `docs(changelog): limit #90's "only" to the two provider modules it describes (#131)` — §4.5a
6. `docs(changelog): record the release skill input and the gate's documents (#131)` — §4.5b
7. `docs(rules): name every tag test excludes in the testing rules (#131)` — §4.6, last

**PR body.**

- **One heading per decision**, each giving the option taken, the option rejected and why:
  - item 4, declare the skill an input (D1, with M1c/M2c and the commit counts from §2);
  - item 6, in place, limiting "only" (D2, with the precedent table);
  - item 1, no check reads the guide (D3);
  - the new `CHANGELOG` entry (D5);
  - no design record (D6), if open question 2 stays at its default.
- **Observable change:** D1's.
- **Measured numbers:** M1–M5 in summary, and the doc checks.
- **Closing line:** `Closes #131`, or `refs #131` under open question 3.
- **For the maintainer:** §9, and §4.6's block if its write was refused.
- **Merge notes:**
  - `modules/aimon-core/build.gradle.kts` gains one line after `:71`, so `:72-79` become `:73-80`, and more if the
    comment reflow adds a line. #131 itself cites these lines. #124's record cites them from its own base.
  - `ReleaseGateMatchesCiGateTest.java` may shift by a line after `:117` and after `:522` once `format` rewraps. #112's
    and #124's records cite lines dated to their bases, and `live-api-test-tier.md` cites method names only.
  - `CHANGELOG.md` #90's bullet changes wrap on three lines.

---

## 9. Out-of-scope findings, for `build/deviations.md` and the PR body

Each was read at `2eddf3d`, and none goes into the diff.

1. **No check reads the guide's statement of the gate.** It is a fourth hand-maintained copy: the class javadoc
   (`ReleaseGateMatchesCiGateTest.java:33`) counts CI, the script and the skill. D3 explains why this run adds no check.
   This is not a live-API-tier item, so it is not `LA-3`.
2. **`scripts/release.sh:188-190`, the sentence the guide's row echoed.** It says "This is deliberately the SAME task CI
   runs … `checkAll` = checkFormat + checkStyle + every module's `test` + the BOM's `verifyBom`", above a gate of five
   tasks (`:237`). It is not this run's file, and it is an input of `:aimon-core:test`.
3. **Root `build.gradle.kts:61-63`.** It says "`test` here is each module's own test task, which excludes the
   `@Tag("docker")` integration tests" and names one of the three exclusions. It is in no run's row, and it is an input
   of `:aimon-core:test` (`rootBuildScript`).
4. **`CLAUDE.md:17` and root `build.gradle.kts:68`.** Both describe `checkAll` as format check, style and unit tests,
   and neither mentions `verifyBom`. This run owns only `CLAUDE.md`'s test line.
5. **`CONTRIBUTING.md:142`.** It says "CI (GitHub Actions) runs `./gradlew checkAll` on every PR", while `:132` of the
   same file says CI runs the three tiers too. Not this run's file.
6. **The guide never mentions the Chromium download.** On a cold machine the gate fetches the browser `playwrightTest`
   needs (`release.sh:229-233`, `SKILL.md:86-90`). The issue does not ask for it.
7. **#124's exempt record says the skill is not an input.** `provider-key-census-claim-and-inputs.md` §9.1, §10 Q1,
   §11.3 and §11.4 are true at its base. The record is not extended: it is not this run's file, and §3.4 governs how it
   changes.
8. **#112's exempt record.** `provider-key-release-gate.md`'s stale sentences, which the issue lists, are not edited.

---

## 10. Open questions

1. **A dialog on the skill probe.** TASK's decline rule names only `.claude/rules/testing.md`, so a dialog on the probe
   halts the run.
   - *Default:* leave that rule as it is. §6 explains why a dialog is unlikely and a halt cheap.
   - *For the orchestrator, before build:* decide whether to extend the decline rule to this probe. If it is extended
     and the dialog is declined, item 4 cannot be measured and acceptance criterion 1 fails for it. The run should
     then report that, not switch to B1 without review.
2. **Commit this design as a record?**
   - *Default: no.* Each decision lives next to what it governs: the build comment (D1), the `CHANGELOG` entry, and
     the PR body's decision headings. A record would add a citation-heavy document for four sentences and one input
     line, plus a row in a shared index.
   - *If yes:* place it in `docs/design/llm/` beside #124's record, follow `docs/design/README.md` §3.4 as it stands on
     `main` (base `2eddf3d`, departures after a numbered boundary), and add an index row after
     `provider-key-census-claim-and-inputs.md`.
3. **If the `testing.md` write is refused**, item 5 is half done.
   - *Default:* `refs #131`, stating that item 5's second file is left to the maintainer with §4.6's exact block in the
     PR body. A `Closes` would claim an item not done, and the maintainer can switch after applying it.
4. **The *Wording* bullet's clause about #90.**
   - *Default: kept* (§4.5b). Striking it does not change D2.

---

## 11. After the build — departures, decisions and measurements

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 1 (PASS, no blocking findings, seven non-blocking notes), and it is not edited to look prescient. Four sources
feed this section: the answers to §10's open questions (`decisions.md`), the run's `build/deviations.md`, review 1's
notes, and what was measured while building. None of them is in the repository.*

**D1–D5 were built as written**, and so were §4's texts: the publishing guide's row, the javadoc sentence and the
failure message, the declaration and its comment, `CLAUDE.md`'s test line, #90's bullet, the new `CHANGELOG` entry and
`.claude/rules/testing.md`'s bullet. What departed is below: this file, one sentence in the entry, how three edits are
laid out, and how the commands ran.

### 11.1 The open questions

| Question | Outcome | Why |
|---|---|---|
| Q1 — a dialog on the skill probe | The decline rule stayed as TASK wrote it; no dialog appeared | Both probes were a `git restore` of the tracked file, run where §7 puts them: M1c after E1–E4 and this file's body were in the tree, M2c after E5 as well |
| Q2 — commit this design as a record | **Yes** — this file, overriding D6's default (DV-1) | The approved design lands in the repository with the change it describes, as #112's and #124's did beside it |
| Q3 — if the `testing.md` write is refused | Not needed | The write went through with no permission dialog, after the gate and the documentation checks had passed (§11.5) |
| Q4 — the *Wording* bullet's clause about #90 | Kept | It departs from the precedent D2 follows: #124's bullets carried no trace of its in-place edit to #112's sentence (`a0fbd6f`). It stays as a choice for the maintainer to weigh, not as part of that precedent |

### 11.2 Where the build departed from the body

- **DV-1 — this file.** D6 and open question 2 defaulted to no record, and the answer to Q2 asked for one. It lands in
  `llm/` beside [`provider-key-census-claim-and-inputs.md`](provider-key-census-claim-and-inputs.md), whose §10 Q1 —
  whether to declare the skill — this record answers. It has one index row in [`../README.md`](../README.md), which §10
  Q2 counted as part of a record's cost. Its H1 and §1–§10 are byte-identical to the approved document.
- **DV-2 — one sentence in the new `CHANGELOG` entry.** Its Documentation bullet ends "The design is
  `docs/design/llm/release-gate-docs-and-skill-input.md`", which §4.5b could not say under D6. #98's and #124's entries
  end a bullet the same way.
- **DV-3 — how two Java edits are laid out.** The rendered text is §4.2's. The census message's literal breaks after
  "the refusal cases " rather than after "with are %s ", which keeps its first line at 120 columns; Eclipse's
  formatter does not split a string literal, so `format` would not have done it. The javadoc sentence wraps over three
  lines at the paragraph's 120 columns. `./gradlew format` changed neither.
- **DV-4 — #90's bullet keeps §4.5a's minimal reflow**, so two of its three changed lines are 111 and 114 columns in a
  paragraph that otherwise wraps at 107 (review 1, note 5). Holding 107 would re-wrap thirteen lines of #90's bullet
  for a four-word insertion and bury the correction in the diff; the section already holds eight lines over 107
  columns.
- **DV-5 — how the commands ran.** zsh does not word-split §7's `$G` (review 1, note 1), so every Gradle command ran
  through a bash wrapper that prints `env | grep -c -E '^(OPENAI|ANTHROPIC)_KEY='` — `0` every time — and then runs
  `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew --console=plain …`. M2c also ran with `--info`, a logging flag and not a
  task input: M2a's `--info` gave only "Task has failed previously", from M1d's failed run, so it could not show which
  input triggered the task; M2c's names `releaseSkill`. The new entry was drafted during M2 with `<N>` and `<T>` open,
  and filled from M4 at E6 (review 1, note 7).

### 11.3 Review 1's non-blocking notes

| Note | Taken? | Where |
|---|---|---|
| 1 — §7's `$G` macro does not run in zsh | Taken | DV-5 |
| 2 — `docs/overview/architecture.md:638` and `.en.md:668` carry the same one-task claim | Taken as a finding | §11.4 |
| 3 — "29 of 171" and "9 of 171" are #124's body figures | Stated here | #124's §11.2 recounts them as 28 and 8. D1's argument rests on this run's own count, two skill-only commits of 191, and does not change |
| 4 — `02ceb7e`, `64d5b2a` and `b0cc272` are merge commits | Stated here | They merge #124, #125 and #126. The sentences D2's table describes came from `a0fbd6f` (#124's in-place edit), `a9ea6ab` (#125's "This supersedes two sentences") and `cf4f7a9` (#126's "This entry supersedes") |
| 5 — §4.5a's minimal reflow leaves lines of 111 and 114 columns | Not taken; stated | DV-4 |
| 6 — the trace clause departs from D2's precedent | Taken as a statement | §11.1, Q4 |
| 7 — whether the entry is drafted before M1 or at E6 | Answered | DV-5 |

### 11.4 Findings outside this run's scope

§9's eight findings are for the PR body as §9 writes them, not for the diff. One more joins them (review 1, note 2):
`docs/overview/architecture.md:638` and `docs/overview/architecture.en.md:668` say `scripts/release.sh` runs the
**same** Gradle task the CI workflow does — the one-task claim the guide's row made — and are a ko/en pair no run owns.

Nothing went to the backlog. None of these is a live-API-tier item, and `LA-3` was the only ID reserved for this run.

### 11.5 What was measured

*2026-09-11, macOS arm64, Gradle 9.2.1. No live API call was made and no key file was read. `FOCUS` is
`at.aimon.core.architecture.ReleaseGateMatchesCiGateTest`, and the filter was the same in every step of each sequence.
The skill probe is `git restore --source='5801997^' --worktree -- .claude/skills/release/SKILL.md`; each restore is
`git restore --worktree -- .claude/skills/release/SKILL.md`, after which `git diff --quiet HEAD -- …` and
`git diff --cached --quiet` both exited 0.*

**The gap, before D1** (`:aimon-core:test --tests "$FOCUS"`):

| Step | State | `:aimon-core:test` |
|---|---|---|
| M1a | the test file changed | executed; 12 tests, 0 failures |
| M1b | nothing changed | `UP-TO-DATE` |
| M1c | the skill probe | **`UP-TO-DATE`** |
| M1d | the same, with `--rerun` | executed; `12 tests completed, 2 failed` — `releaseSkillDescribesTheRealGate`, the skill naming `[checkAll, integrationTest, packagingTest, jacocoTestCoverageVerification]` while the script also runs `playwrightTest`, and `releaseSkillDoesNotCallAGatedTierUngated` |

**After D1:**

| Step | State | `:aimon-core:test` |
|---|---|---|
| M2a | the build script changed | executed; 12 tests, 0 failures |
| M2b | nothing changed | `UP-TO-DATE` |
| M2c | the skill probe | **executed** — `Input property 'releaseSkill' file …/.claude/skills/release/SKILL.md has changed.` — `12 tests completed, 2 failed`, the same two |
| M2d | the skill restored | executed; 12 tests, 0 failures |
| M2e | nothing changed | `UP-TO-DATE` |
| M2f | the same, with `--configuration-cache` | `UP-TO-DATE`; `Configuration cache entry stored.`, no problem reported |

**Items 2 and 3:**

| Step | State | `:aimon-core:test` |
|---|---|---|
| M3a | `ScratchProbe` — `@Tag("scratchprobe")`, gated `named = "SCRATCH_PROBE_KEY"` — under `aimon-llm-openai/src/test` | **executed**; `12 tests completed, 2 failed`: `everyTestTagIsGated` naming `scratchprobe`, and the census, whose message opens `the provider modules' tests are gated on [ANTHROPIC_KEY, OPENAI_KEY, SCRATCH_PROBE_KEY], but the keys the refusal cases run scripts/release.sh with are [ANTHROPIC_KEY, OPENAI_KEY] (PROVIDER_KEY_VARIABLES).` |
| M3b | removed | executed; 12 tests, 0 failures |
| M3c | `ScratchTagProbe` — `@Tag("scratchprobe")` only — under `aimon-sandbox-docker/src/test` | **`UP-TO-DATE`** — the gap the narrowed sentence still states |
| M3d | the same, with `--rerun` | executed; `12 tests completed, 1 failed`, `everyTestTagIsGated` naming `scratchprobe` |
| M3e | removed | executed; 12 tests, 0 failures. No `Scratch*` file left |

**The price (M4).** `:aimon-core:test --rerun`, unfiltered: `BUILD SUCCESSFUL in 36s`; 8201 tests in 1308 classes,
0 failures, 0 errors, 2 skipped. #124 measured 8168 in 40s at its own base.

**The gate (M5).** `./gradlew format` changed nothing past this change's edits. `./gradlew checkAll`: `BUILD SUCCESSFUL in
2m 43s`, 240 actionable tasks (112 executed, 128 up-to-date), with `checkFormat`, `checkStyle`, every module's
`checkstyleMain` and `verifyBom` among them. Twenty-one module `test` tasks executed: 2783 tests, 0 failures, 0 errors,
70 skipped (`aimon-sample-app`'s ran no test). One, `:aimon-core:test`, reported `UP-TO-DATE` from M4's unfiltered run
on the same inputs; with its 8201 tests the tree counts 10984 tests, 0 failures, 0 errors, 72 skipped. Five `test`
tasks had no source.

**Documentation (M6).** Run as CI invokes them, every one exits 0: `check-doc-links.py` (251 files, 2528 relative
links, 0 broken) and its `--self-test`, `check-backlog-registers.py --github` and `--self-test`,
`check-translation-staleness.py --github` (32 up to date, 0 stale, 0 unresolvable), and
`check-translation-structure.py --self-test` and `--github` (32 structurally identical). `mkdocs build --strict` also
exits 0.

**`.claude/rules/testing.md`, last (E7).** Written after M5 and M6 as §4.6 gives it, with no permission dialog. The
four checks then ran again as CI invokes them, with their self-tests, and every one exits 0 with the same counts: 251
files and 2528 relative links with 0 broken, 32 translations up to date with 0 stale and 0 unresolvable, and 32 pairs
structurally identical. No build script names the file and no test reads it, so `checkAll` was not run again.
`git status -- .claude` shows only that file, and `.claude/skills/release/SKILL.md` is byte-identical to `2eddf3d`.

**Not measured.** The skill missing at its path (§5), because measuring it means deleting a file under `.claude/`. A
local `checkAll` after a skill-only edit, end to end: M4 prices the suite such a build adds and M2c shows the task
executing, but the two were not combined in one build. A real release — the key refusal and the gate have still only
run in the test's sandbox.
