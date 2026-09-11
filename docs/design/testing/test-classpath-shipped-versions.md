# Design — #99: decide the nine test-classpath differences, answer the memory testkit's JUnit floor, correct #95's four records

> Status: **IMPLEMENTED** — `modules/aimon-cli/build.gradle.kts` (its two test classpaths resolve consistently with
> `runtimeClasspath`), the record next to `junit` in `gradle/libs.versions.toml`, the comment in
> `modules/aimon-memory-testkit/build.gradle.kts`, the package-private `expectedDeclaration` seam on
> `ModelCapabilityBindingProbe.Builder` and the test that drives it, #91's entry in `CHANGELOG.md`, and §11.5 of
> [`../llm/model-capability-binding-round-trip.md`](../llm/configuration-surface.md#84-계약의-자리).
> Source: issue [#99](https://github.com/kangwoo/aimon-core/issues/99).
>
> **[§11](#11-after-the-build--departures-and-corrections), appended after the build, is where this document departs
> from what was built.** Everything between this header and §11 is the body as approved in design review round 1,
> kept byte-exact rather than corrected — the house habit in this directory, for the reason
> `model-capability-binding-round-trip.md` gives. Several of its supporting sentences and draft wordings are wrong;
> §11.2 names each and says what was committed instead. The run records the body cites (`TASK.md`, `review-1.md`,
> `$RUN_DIR/design/probe/`, `$RUN_DIR/build/`) are not in the repository; §11.3 reproduces the measurements that
> matter.
>
> What this work left open is in [`../../backlog/module-dependency-scope.md`](../../backlog/module-dependency-scope.md),
> D-2 and D-3.
>
> It is in English, matching the `docs/design/llm/` documents written the same way; `docs/design/` is not a
> translation target either way (`docs/project/documentation-guide.md` §5.1).

> Base: `main` at `9b642cc`, worktree branch `herdr/test-classpath-version-record`, clean.
> Every number below was **measured by the design phase on 2026-09-11** on that tree (and on `ade5978`, #95's parent,
> for the recount). The build phase re-measures before and after, as TASK.md requires — these numbers are the
> prediction the after table is checked against, not a substitute for it. No API call was made.
>
> Probes live in `$RUN_DIR/design/probe/` (Gradle init scripts, never committed) and their output in
> `$RUN_DIR/design/probe/out/`: `diff-before.txt` (runtime vs test, every module), `diff-pre95.txt` (same on
> `ade5978`), `dump-*/` (all four classpaths of every module under each candidate mechanism), `batch2.txt`
> (resolution-failure checks, `dependencyInsight` reasons, the CLI test run).

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **1** | `spring-boot-starter-test` on `aimon-cli` — Logback pair, `jakarta.xml.bind-api` | **Align.** `aimon-cli`'s two *test* classpaths resolve consistently with its `runtimeClasspath` (`shouldResolveConsistentlyWith`), in `modules/aimon-cli/build.gradle.kts`. Moves 7 resolved entries, all on that module's test classpaths; 449 CLI tests pass on the shipped versions. §3.1 |
| **2** | Testcontainers' `org.jetbrains:annotations` (13.0 shipped, 17.0.0 under test) | **Accept**, recorded next to `junit` in `gradle/libs.versions.toml`. Both jars are CLASS-retention annotations and constant holders; nothing a test run loads. §3.2 |
| **3** | The provider SDKs' `error_prone_annotations` on the starter (2.21.1 shipped, 2.33.0 under test) | **Accept**, same place. Annotation jar; and 2.33.0 is what an application that adds either provider resolves, which is the shape the starter's tests model. §3.3 |
| **4** | Does `aimon-memory-testkit`'s JUnit gap matter for a published suite? | **No.** The 5.13.4 is on a test classpath that runs nothing (the module has no test sources); the published `junit-bom` 5.12.2 is the floor the suite compiles against, which is what a floor should say. **No build change**; the answer is written into the `junit` note. §3.4 |
| **5** | `ModelCapabilityBindingProbeTest` — drive the `declaresAnything()` branch or explain the helper | **Drive it end to end** through a package-private seam on the probe's builder; the helper-level test is replaced and `refusedProbeValue` becomes private. §3.5 |
| **6** | Bookkeeping | `buildSrc/**` is **not** changed. #95's `CHANGELOG.md` entry and the `junit` note get the 21/20 correction; one new `[Unreleased]` bullet for #99; backlog **D-2** (same-source differences #99 did not name) and **D-3** (nothing checks this record) registered. §3.6 |

---

## 1. The problem, in one paragraph

#91 set a bar — *a module's tests run against the versions the module ships, unless the difference is a deliberate,
recorded choice* — and #95 met it for the twenty differences Spring Boot's platform caused through the testkits, but
left nine artifacts on six modules resolving under test to a version their module does not ship, from three other
sources (`spring-boot-starter-test`, Testcontainers, the starter's provider SDKs), with a changelog line that names
them and decides nothing. The same run measured that the one published testkit publishes a `junit-bom` 5.12.2
constraint while its own test classpath resolves 5.13.4, and four records of #95 are slightly wrong: the count (the
platform moved 21 artifacts; 20 differences went away), "the other two testkits" where there are three, a design
document still proposing a catalog alias #95 removed, and a probe message pinned through its helper rather than the
branch that produces it. #99 asks for each of the nine to be aligned or accepted per source and written down, for the
testkit gap to get a recorded answer, and for the four records to be corrected.

---

## 2. What was measured

### 2.1 The nine, on `9b642cc`

`classpathVersionDiff` (every external module whose resolved version differs between `runtimeClasspath` and
`testRuntimeClasspath`), confirmed per pair with `dependencyInsight`:

| module | artifact | shipped | under test | where the shipped version comes from | where the test version comes from |
|---|---|---|---|---|---|
| `aimon-cli` | `ch.qos.logback:logback-classic` | 1.5.13 | 1.5.34 | catalog `logback`, `implementation` | `spring-boot-starter-test` → `spring-boot-starter` → `spring-boot-starter-logging` |
| `aimon-cli` | `ch.qos.logback:logback-core` | 1.5.13 | 1.5.34 | via classic | via classic 1.5.34 |
| `aimon-cli` | `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.4 | 4.0.5 | `org.quartz-scheduler:quartz:2.5.2` | `spring-boot-starter-test` directly |
| `aimon-spring-boot-starter` | `com.google.errorprone:error_prone_annotations` | 2.21.1 | 2.33.0 | Caffeine 3.1.8 via `aimon-session-routing` | `anthropic-java-core` 2.13.0 and `openai-java-core` 4.57.0 |
| starter, `aimon-filesystem-gridfs`, `aimon-session-{mongodb,postgres,redis}` | `org.jetbrains:annotations` | 13.0 | 17.0.0 | `kotlin-stdlib` 2.2.21 ← `okhttp` 5.5.0 ← `aimon-core` | `org.rnorth.duct-tape:duct-tape:1.0.8` ← Testcontainers 1.21.4 |

### 2.2 The whole build — what #95's scope did not reach

#95 measured the seven consumers of the testkits. Run over all 28 projects, the same comparison finds the nine plus:

| module | artifact | shipped → test | source | in #99? |
|---|---|---|---|---|
| `aimon-filesystem-s3` | `org.jetbrains:annotations` | 13.0 → 17.0.0 | Testcontainers (localstack) | no — same source as the nine, same difference |
| `aimon-scheduling-quartz` | `jakarta.xml.bind-api` | 4.0.4 → 4.0.5 | `spring-boot-starter-test` | no |
| `aimon-knowledge-opensearch` | `jakarta.annotation:jakarta.annotation-api` | 1.3.5 → 2.1.1 | `spring-boot-starter-test` → `spring-boot-starter` | no — and 1.3.5 is `javax.annotation.*`, 2.1.1 is `jakarta.annotation.*`: the shipped classes are absent under test |
| `aimon-session-testkit` | Logback pair | 1.5.13 → 1.5.34 | `spring-boot-starter-test` | no — no test sources, and its runtime classpath only ever joins a consumer's test classpath. Absent on `ade5978`: Spring Boot's platform managed it until #95 |
| `aimon-memory-testkit` | JUnit (7 components incl. `junit-bom`) | 5.12.2 → 5.13.4 | conventions test dependencies | yes — task item 2 (§3.4) |

### 2.3 #95's count, recounted on its parent

`ade5978` (= `764f371^`) against `9b642cc`, the seven consumers only: **29** differences before; **20** gone; **9**
stayed; of those nine, **one moved** — the starter's `error_prone_annotations`, 2.21.1 → **2.49.0** under test before,
→ **2.33.0** after. So the platform moved 21 artifacts; 20 differences went away; the 21st still differs, now through
the SDKs. The 20 gone are on six of the seven consumers (none on `aimon-core`), so "six of their seven" stays right.
No consumer gained a difference (the session testkit's own pair in §2.2 is not a consumer).

### 2.4 What the jars contain (`javap -v` over every class)

| jar | annotation types | RUNTIME retention | other classes |
|---|---|---|---|
| `org.jetbrains:annotations:13.0` | 30 | 0 | 2 — `JdkConstants`, `PrintFormatPattern` (holders) |
| `org.jetbrains:annotations:17.0.0` | 35 | 0 | 4 — the two above, `Nls$Capitalization` (enum), `Async` (holder) |
| `error_prone_annotations:2.21.1` / `2.33.0` | 26 / 26 | 7 / 7 | 1 — `Modifier` (enum) |
| `jakarta.xml.bind-api:4.0.4` / `4.0.5` | 30 / 30 | 30 / 30 | **78 / 78** — `JAXBContext`, `ContextFinder`, `DatatypeConverter`, … |

`git grep "com.google.errorprone\|org.jetbrains.annotations" -- 'modules/*/src/*'` → **0** files.

### 2.5 The memory testkit

- `modules/aimon-memory-testkit/src` holds `main` only — **no test sources**.
- `compileClasspath`: `junit-jupiter-api` **5.12.2** (by `junit-bom` constraint). `testRuntimeClasspath`: **5.13.4**.
- The only in-tree consumer is `aimon-core` (`testImplementation`); its `testRuntimeClasspath` runs the suite on
  **5.14.4** (`archunit-junit5` 1.5.0 raises it).
- Published today (`generatePomFileForMavenPublication`, `generateMetadataFileForMavenPublication`): POM
  `<dependencyManagement>` imports `org.junit:junit-bom:5.12.2`; `junit-jupiter` is versionless; `module.json`
  `apiElements` and `runtimeElements` both carry `org.junit:junit-bom` as a platform, `requires 5.12.2`.

### 2.6 Candidate mechanisms, sized

Each applied through an init script, all four classpaths of every project dumped and diffed against the baseline.

| mechanism | entries moved | projects | resolution failures | runtime-vs-test differences left |
|---|---|---|---|---|
| strict constraints, Logback pair, `aimon-cli` `testImplementation` | 4 | 1 | 0 | 19 (JAXB on the CLI remains) |
| **`shouldResolveConsistentlyWith(runtimeClasspath)` on `aimon-cli`'s `testCompileClasspath` + `testRuntimeClasspath`** | **7** | **1** | **0** | 18 (none on `aimon-cli`) |
| `java { consistentResolution { useRuntimeClasspathVersions() } }` on `aimon-cli` | 14 | 1 | **`compileClasspath`: `org.jetbrains:annotations:26.1.0 FAILED` against `{strictly 13.0}`** | — |
| the test-only mechanism in `aimon.java-conventions` (every module) | 94 | 23 | 1 (`aimon-llm-anthropic` `testCompileClasspath`, `kotlin-stdlib-common`) | 0 |
| `useRuntimeClasspathVersions()` in `aimon.java-conventions` | 268 | 26 | 26 (every main `compileClasspath`, the annotations conflict) | 0 |

The chosen row moves exactly: `testRuntimeClasspath` — `logback-classic`, `logback-core` 1.5.34 → 1.5.13,
`jakarta.xml.bind-api` 4.0.5 → 4.0.4; `testCompileClasspath` — the same three, plus `org.yaml:snakeyaml` 2.5 → 2.7
(tests compiled against the 2.5 `jackson-dataformat-yaml` asks for and ran on the 2.7 `aimon-core` ships). Every
`compileClasspath` and `runtimeClasspath` in all 29 dumps is unchanged. `dependencyInsight` on the aligned classpath
reports: *"By constraint: version resolved in configuration ':aimon-cli:runtimeClasspath' by consistent resolution"*.

`Configuration.shouldResolveConsistentlyWith` is `@Incubating` in `gradle-core-api-9.2.1.jar` (checked with `javap`).

### 2.7 The CLI's tests on the aligned classpath

`./gradlew --init-script exp-consistent-cli-tests.init.gradle :aimon-cli:test --rerun`: **449 tests, 0 failures,
0 errors, 0 skipped.** That includes `AgentSetupFactoryAgentModelCheckTest`, which attaches a Logback `ListAppender`,
and every test that loads `src/test/resources/logback.xml` — all on Logback 1.5.13.

---

## 3. Decisions and the alternatives rejected

Each subsection is one heading in the PR body, as TASK.md requires.

### 3.1 Decision 1 — `spring-boot-starter-test` on `aimon-cli`: **aligned**, by consistent resolution of the two test classpaths

**Taken.** In `modules/aimon-cli/build.gradle.kts`, `testCompileClasspath` and `testRuntimeClasspath` each
`shouldResolveConsistentlyWith(runtimeClasspath)`: every version the distribution resolves becomes a strict
constraint on both test classpaths.

**Why align rather than accept — the issue's "weigh that".** This is the one source where acceptance is weakest.
`aimon-cli` is the application: `tasks.jar` packs `configurations.runtimeClasspath` (`build.gradle.kts:48-63`), so
its runtime classpath *is* what a user runs, not a floor a consumer mediates. Its tests assert on Logback itself
(`AgentSetupFactoryAgentModelCheckTest` attaches a `ListAppender`; `src/test/resources/logback.xml` configures it), so
the Logback under test was not the Logback shipped. And `jakarta.xml.bind-api` is not an annotations jar — 78 of its
108 classes are code (§2.4) — so the reason that carries Decisions 2 and 3 does not reach it.

**Why this mechanism.** The versions come from what ships rather than from a list: a catalog Logback bump or a
Quartz bump moves the test classpath with the distribution, and a fourth jar raised the same way on this module is
pulled back without anyone noticing it first. The constraint explains itself in `dependencyInsight` (§2.6). It stays
off the main compile classpath, which is where the Gradle-provided shorthand breaks (§2.6, row 3).

**Rejected — accept and record.** The reasons above: this is the application, its tests exercise Logback, and JAXB
carries code.

**Rejected — raise what ships (catalog `logback` 1.5.13 → 1.5.34).** It makes the numbers equal by changing the
CLI distribution a user runs, i.e. a user-observable change, chosen by the test starter's version; it re-opens at every
Spring Boot bump that moves Logback; and it cannot reach `jakarta.xml.bind-api`, a Quartz transitive with no catalog
entry, without a new main-scope constraint. Whether the CLI should ship a newer Logback is a real question (§8, F-1)
but not this issue's; with the chosen mechanism, a later catalog bump carries the tests along.

**Rejected — strict constraints naming the three** (`strictly(libs.versions.logback)` for the pair, `strictly("4.0.4")`
for JAXB). Stable API and a `because` string, and measured to work for the pair (§2.6 row 1). But JAXB's shipped
version belongs to Quartz: the literal pins the test side while the shipped side follows Quartz, so the next Quartz
bump recreates the difference in the other direction, silently. It also leaves any future difference on the
application unrecorded.

**Rejected — `java { consistentResolution { useRuntimeClasspathVersions() } }` on `aimon-cli`.** Also constrains the
main `compileClasspath`, where the conventions plugin's `compileOnly` `org.jetbrains:annotations:26.1.0` cannot resolve
against the `{strictly 13.0}` kotlin-stdlib ships (measured `FAILED`).

**Rejected — fix it at the source, in `aimon.java-conventions`.** Either flavour reaches every module: 94 entries in 23
projects with one resolution failure, or 268 in 26 with 26 (§2.6). It would force down the two sources Decisions 2 and
3 accept (six docker tiers to re-run for no observable difference), swap `jakarta.annotation-api` back to
`javax.annotation.*` on `aimon-knowledge-opensearch`, move modules no run in this batch owns, and make "accepted" — which
#91's bar explicitly allows — impossible anywhere without an opt-out. Out of proportion to three jars on one module.

**Rejected — exclude Logback and JAXB from `spring-boot-starter-test`.** In the conventions plugin it removes the
Logback binding from the test runtime of every module that relies on the starter for one (SLF4J would fall back to
no-op); on `aimon-cli` alone it covers what strict constraints cover, as a removal whose correctness depends on the CLI
continuing to ship both jars.

**Cost accepted.** `shouldResolveConsistentlyWith` is `@Incubating`. If a Gradle upgrade changes it, this build script
stops compiling — loud and local to one file, not a silent regression. `buildSrc` already uses unstable API
(`@Suppress("UnstableApiUsage")` on the version catalog accessor). This is the decision most likely to be overturned
at review; the fallback is the strict-constraints alternative with its JAXB drift written into the comment.

### 3.2 Decision 2 — Testcontainers' `org.jetbrains:annotations`: **accepted**

**Where.** `aimon-spring-boot-starter`, `aimon-filesystem-gridfs`, `aimon-session-{mongodb,postgres,redis}` (#99's
table) and `aimon-filesystem-s3` (same source and difference, outside #99's table — it is listed because the record is
per source, and a list that omits a known case is the kind of record #99 exists to correct).

**Why accept.** Both versions are CLASS-retention annotations plus constant holders (§2.4) — the JVM does not expose
CLASS-retention annotations to reflection and loads none of these classes to run test code — and no source in the
repository names them. The 13.0 on the shipped side arrives as `kotlin-stdlib`'s dependency, not through anything that
reads annotations. Two numbers nothing can observe.

**Rejected — align (strict 13.0 on six modules' test classpaths).** Pushes Testcontainers' `duct-tape` below the
version it asks for, on modules whose docker tiers — 19, 16, 10, 9, 8 and 1 `@Tag("docker")` classes — would all need
re-running, to change nothing observable. One of the six build files (`aimon-filesystem-s3`) is outside this run's
files.

### 3.3 Decision 3 — the provider SDKs' `error_prone_annotations` on the starter: **accepted**

**Why accept.** Two independent reasons, each sufficient.

1. **No behaviour.** 26 annotation types and one enum, no code (§2.4); no source here names them.
2. **"Shipped" is the wrong comparison for this jar on this module.** The starter keeps both provider modules
   `compileOnly` so that the host application adds one (`aimon-spring-boot-starter/build.gradle.kts:15-19`). 2.21.1 is
   the starter *without* a provider (Caffeine's request). Both SDKs ask for 2.33.0, so an application that adds either
   one resolves 2.33.0 itself. The test classpath carries both SDKs so the selector can be tested — it is the shape of
   an application that uses a provider. Aligning down would test a combination no such application runs.

**Rejected — align (strict 2.21.1 on the starter's tests).** Puts both SDKs on an annotations jar older than they
declare, modelling no real deployment, and requires re-running the starter's docker tier.

**Rejected — raise what ships (a main-scope constraint).** Changes the published starter POM and module metadata — a
public contract — for an annotations jar.

**The 21st artifact.** Recorded in #95's entry and the `junit` note (§4.2, §4.6): the platform moved this one too
(2.49.0 under test); removing it left 2.33.0.

### 3.4 Decision 4 — `aimon-memory-testkit`'s JUnit: **the gap does not matter; the floor stays 5.12.2**

**Answer.** The two numbers describe different things, and neither is wrong.

- **5.13.4 is on a classpath nothing runs.** The module has no test sources (§2.5). The conventions plugin gives it
  test dependencies anyway, and they raise JUnit there; no test executes on that classpath.
- **5.12.2 is what the suite links against, and a published floor should say exactly that.** The suite's main sources
  compile against 5.12.2; the POM imports `junit-bom:5.12.2`, and the module metadata requires it as a platform (a
  floor Gradle raises when a consumer resolves newer, never a pin).
- **Where the suite runs, the consumer's JUnit decides**: in this build, `aimon-core`'s tests run it on 5.14.4.

**Act.** No build change. The answer is appended to the final paragraph of the `junit` note (§4.2). The POM and
module metadata are regenerated before and after to show the constraint is the one the answer names (§7.3).

**What stays unverified, and is written down as such:** no run executes the suite on exactly 5.12.2.

**Rejected — raise the catalog `junit` to 5.13.4.** Changes the published constraint (moves a Gradle consumer on
5.12.x) and the floor of the three unpublished testkits, to match a classpath that executes no test; and it breaks the
note's own rule of keeping `junit` equal to what Spring Boot resolves.

**Rejected — a strict pin.** Would refuse the newer JUnit every real consumer, `aimon-core` included, brings.

**Rejected — strip the conventions test dependencies from this module** so its test classpath reads 5.12.2. Cosmetic:
it changes a classpath nothing runs, and the conventions plugin has no opt-out to hang it on.

### 3.5 Decision 5 — the `declaresAnything()` refusal: **driven end to end through a package-private seam**

**Why no real key reaches it.** `declaresAnything()` checks all eight fields
(`ModelCapabilityDeclaration.java:340-345`); every setter stores its argument unchanged (`:252-335`); the probe refuses
`null` before it asks the builder (`ModelCapabilityBindingProbe.java:178-181`); and
`DeclarableKeys.expectedDeclaration` hard-wires `ModelCapabilityDeclaration.builder()` (`DeclarableKeys.java:90-98`),
whose constructor is private. So the catch that produces the message (`ModelCapabilityBindingProbe.java:196-202`) is
unreachable from real keys, and today's test calls the helper (`ModelCapabilityBindingProbeTest.java:205`): deleting
that catch leaves the build green.

**Taken.** `ModelCapabilityBindingProbe.Builder` gains a package-private
`expectedDeclaration(BiFunction<String, Object, ModelCapabilityDeclaration>)`, defaulting to
`DeclarableKeys::expectedDeclaration`; the probe uses that one function at both places it asks for an expected
declaration (`acceptedDeclaration`, and the equality in `assertValueReachesTheDeclaration`). The test builds a probe
whose expectation step answers as a builder with the defect would — `(key, value) -> ModelCapabilityDeclaration.builder().build()`,
which throws core's own empty-declaration refusal, the exception such a builder throws for every value — and calls the
public `assertValuesReachTheDeclaration`. `refusedProbeValue` becomes private: its only outside caller was the test
being replaced.

**Precedent.** The same module already carries a package-private seam for exactly this purpose:
`DeclarableKeys.namesOf(Class<?>)`, *"Discovery over any builder type, so the two refusals can be shown on a builder
that has the defect"* (`DeclarableKeys.java:100-103`).

**Rejected — "say in the test why the helper is the only reachable seam".** It is not the only reachable seam (this
design reaches the path), so the sentence would be false, and the catch would stay unguarded.

**Rejected — `Mockito.mockStatic(DeclarableKeys.class, CALLS_REAL_METHODS)`.** No main-source change, but it would be
the repository's first static mock (`git grep mockStatic` → 0), it couples the test to which static method the probe
happens to call, and the module's own precedent is a seam.

**Rejected — a hook on core's builder.** Widens a published type for a test in a module this issue does not touch.

### 3.6 Decision 6 — bookkeeping

- **`buildSrc/**` is not changed**, so nothing reaches every module; §2.6 sizes what would have.
- **The record lives in `gradle/libs.versions.toml`, under `# Testing`, directly above the `junit` note** — adjacent,
  as #99 suggests, without splitting the note from the value its last paragraph calls "the number below". The memory
  testkit answer goes *inside* that last paragraph, because it is about that number.
- **A new `[Unreleased]` bullet for #99.** Acceptance criterion 4 only demands one if something a consumer resolves
  changes, and nothing does; the ground rules ask for one per behaviour change, and the CLI's tests now run on
  different jars. The bullet says plainly that nothing a consumer resolves changed. (Open question 2.)
- **Backlog D-2 and D-3 in `docs/backlog/module-dependency-scope.md`** (this run's reserved IDs). D-2 carries the
  same-source differences #99 did not name (§2.2) — leaving them only in a deviations file would reproduce #99's own
  failure, a difference nothing records. D-3 carries the fact that the record is prose: #95's records drifted within a
  day, and nothing would notice a tenth difference. The register's H1 widens to cover both kinds.

---

## 4. Concrete changes, by file

Drafts below are the substance; the build may tighten wording, but not the facts, and every number in a committed
sentence comes from the build's own after-measurement.

### 4.1 `modules/aimon-cli/build.gradle.kts`

After the `dependencies { }` block, before `tasks.jar`:

```kotlin
// The CLI's tests run on the versions the CLI ships (#99). aimon.java-conventions gives every module
// spring-boot-starter-test, and here it raised three jars the distribution carries — logback-classic and
// logback-core 1.5.13 -> 1.5.34, and Quartz's jakarta.xml.bind-api 4.0.4 -> 4.0.5 — so the Logback these tests
// assert on was not the Logback `tasks.jar` below packs.
//
// Consistent resolution rather than naming the three: every version runtimeClasspath resolves becomes a strict
// constraint on both test classpaths, so a catalog or Quartz bump moves the tests with the distribution, and a jar
// raised the same way later is pulled back too. `dependencyInsight` names it ("by consistent resolution"). A test
// library that needs a newer version of a jar the CLI ships now fails here, loudly — that is a choice to make on
// purpose and record next to `junit` in gradle/libs.versions.toml.
//
// Test classpaths only: `java { consistentResolution { useRuntimeClasspathVersions() } }` would constrain the main
// compile classpath too, where the conventions plugin's compileOnly org.jetbrains:annotations 26.1.0 cannot resolve
// against the 13.0 kotlin-stdlib ships. `shouldResolveConsistentlyWith` is @Incubating (Gradle 9.2.1).
configurations {
    val shipped = runtimeClasspath.get()
    testCompileClasspath { shouldResolveConsistentlyWith(shipped) }
    testRuntimeClasspath { shouldResolveConsistentlyWith(shipped) }
}
```

`integrationTest` and `packagingTest` use `testSourceSet.runtimeClasspath`, i.e. `testRuntimeClasspath`, so they are
covered by the same line.

### 4.2 `gradle/libs.versions.toml`

**(a) New block under `# Testing`, above `# JUnit carries no version…`:**

```toml
# Test classpaths against shipped versions (#99). #91's bar: a module's tests run against the versions the module
# ships, unless the difference is a recorded choice. Comparing runtimeClasspath with testRuntimeClasspath on every
# module (2026-09-11) leaves three sources of difference, decided here.
#
# - spring-boot-starter-test, which aimon.java-conventions gives every module's tests: ALIGNED on aimon-cli, the one
#   application, whose runtimeClasspath is its distribution. It raised logback-classic and logback-core
#   (1.5.13 -> 1.5.34) and jakarta.xml.bind-api (4.0.4 -> 4.0.5); aimon-cli/build.gradle.kts now resolves both test
#   classpaths consistently with runtimeClasspath, and says why. The same source raises jakarta.xml.bind-api on
#   aimon-scheduling-quartz and jakarta.annotation-api on aimon-knowledge-opensearch, which #99 did not decide:
#   backlog D-2, docs/backlog/module-dependency-scope.md.
# - Testcontainers (duct-tape): ACCEPTED. org.jetbrains:annotations 13.0 shipped (kotlin-stdlib, via okhttp), 17.0.0
#   under test, on aimon-spring-boot-starter, aimon-filesystem-gridfs, aimon-filesystem-s3 and
#   aimon-session-{mongodb,postgres,redis}. Both jars are CLASS-retention annotations and constant holders — nothing a
#   test run loads — and no source here names them. Aligning would push Testcontainers below the version it asks for on
#   six docker tiers to equalise a number nothing observes.
# - The provider SDKs on aimon-spring-boot-starter's test classpath: ACCEPTED. error_prone_annotations 2.21.1 shipped
#   (Caffeine, via aimon-session-routing), 2.33.0 under test (anthropic-java and openai-java both ask for it). An
#   annotations jar no source here names; and the starter keeps both SDKs compileOnly so the application adds one,
#   which then resolves 2.33.0 itself — the test classpath is that application's.
#
```

**(b) `:45` — the count.** `…and twenty artifacts on six of their seven consumers ran under test at a version the
consumer does not ship (measured 2026-09-10)…` becomes, in substance: *…and it moved twenty-one artifacts on six of
their seven consumers to a version the consumer does not ship (measured 2026-09-10; recounted for #99: twenty of those
differences went away with it, and the twenty-first, the starter's error_prone_annotations, fell from 2.49.0 to the
2.33.0 its provider SDKs ask for — see above)…* The HikariCP and MongoDB examples that follow stay.

**(c) The final paragraph gains the memory testkit answer**, after "…by normal conflict resolution.":

```toml
# For aimon-memory-testkit that floor is the answer rather than a lag (#99). The suite compiles against 5.12.2, and what
# it publishes — junit-bom 5.12.2 as a platform in the module metadata, an import in the POM — says exactly that: the
# lowest JUnit it links against. Where it runs, the consumer's JUnit wins when newer (aimon-core's tests run it on
# 5.14.4). The testkit's own testRuntimeClasspath resolves 5.13.4, but the module has no test sources, so no test runs
# there, and raising the published floor to match would move a consumer's JUnit for a classpath that executes nothing.
# Not verified: no run executes the suite on exactly 5.12.2.
```

No version value in the catalog changes.

### 4.3 `modules/aimon-memory-testkit/build.gradle.kts:7-9`

```kotlin
// Published, unlike the other three testkits — and that difference is not an inconsistency, it is the difference
// between what each suite's subjects are. `aimon-filesystem-testkit`, `aimon-session-testkit` and
// `aimon-llm-capability-testkit` describe contracts whose every subject is in this repository, so an unpublished module
// reaches all of them.
```

"implementation" → "subject" because the capability testkit's subjects are two configuration surfaces, not
implementations of an SPI; the next sentence already says "This suite's subjects". Comment-only: the POM and module
metadata must come out byte-identical (§7.3). Keep the edit free of `api(project(` and logback text — two
`aimon-core` architecture tests read this file as text.

### 4.4 `modules/aimon-llm-capability-testkit`

`src/main/java/at/aimon/llm/capability/testkit/ModelCapabilityBindingProbe.java`:

```java
private final BiFunction<String, Object, ModelCapabilityDeclaration> expectedDeclaration;   // set from the builder

// Builder<S>
private BiFunction<String, Object, ModelCapabilityDeclaration> expectedDeclaration = DeclarableKeys::expectedDeclaration;

/**
 * @param expectedDeclaration
 *            what the declaration answers for one key written alone — {@link DeclarableKeys#expectedDeclaration}
 *            unless set. Package-private, like {@link DeclarableKeys#namesOf}: the only other answer worth giving is
 *            the one a builder with a defect gives, which no real key reaches, and this module's own test uses it to
 *            drive that refusal through the pair check a real run takes.
 * @return this builder
 */
Builder<S> expectedDeclaration(BiFunction<String, Object, ModelCapabilityDeclaration> expectedDeclaration) { … }
```

`acceptedDeclaration` becomes an instance method calling `expectedDeclaration.apply(key, value)`;
`assertValueReachesTheDeclaration` compares against the same function; `refusedProbeValue` becomes `private`. No
public signature changes; the default is the current behaviour.

`src/test/java/.../ModelCapabilityBindingProbeTest.java`: `blamesDeclaresAnythingWhenTheBuilderCallsTheValueEmpty`
(`:197-210`) is replaced by one test that

- captures the refusal message from `ModelCapabilityDeclaration.builder().build()`;
- builds a probe over `FakeSurface` with `copiesAll` and `.expectedDeclaration((key, value) -> ModelCapabilityDeclaration.builder().build())`;
- calls `probe.assertValuesReachTheDeclaration("supportsSamplingParameters", ProbeValues.distinctPairFor("supportsSamplingParameters"))`;
- asserts an `AssertionError` containing `` `supportsSamplingParameters` = <first value> was the only key set ``
  and ``declaresAnything() does not check the field `.supportsSamplingParameters(...)` writes``, not containing
  `pick one the declaration accepts`, whose cause is an `IllegalArgumentException` with the captured message;
- carries a comment saying why the expectation step is substituted (the four facts in §3.5, one sentence each) and
  what it stands in for: the exception a builder that skips the key throws for every value.

The existing `refusesAValueTheDeclarationRefuses` keeps pinning the other branch through the real path.

### 4.5 `docs/design/llm/model-capability-binding-round-trip.md`

Append after §11.4, body untouched:

```markdown
### 11.5 Later departures

*Appended 2026-09-11 for #99. §4.1 is left as approved; this records where it stopped matching the tree.*

- **§4.1's build script names a catalog alias that no longer exists.** It declares
  `api(platform(libs.spring.boot.dependencies))`. #91 (PR #95, `764f371`) replaced that line in this module, and in
  `aimon-filesystem-testkit` and `aimon-session-testkit`, with `api(platform(libs.junit.bom))`, and removed the
  `spring-boot-dependencies` entry from `gradle/libs.versions.toml`. Declared `api`, Spring Boot's platform reached every
  consumer's test classpath and raised versions the consumer ships — nine of them on `aimon-cli`, the consumer this
  document added. The reason is written once, next to `junit` in the catalog;
  `modules/aimon-llm-capability-testkit/build.gradle.kts` is the current script.
```

`docs/design/` is not a translation target, so there is no twin. No inbound link targets `#11-…` anchors in this file.

### 4.6 `CHANGELOG.md` (`[Unreleased]` › *Build, CI and the release gate*)

**#95's bullet (`:1525-1540`) — the count and one line, same story:**

- "…and 20 of them came from that platform: …, `reactor-core` on `aimon-session-redis` and Caffeine on the starter." →
  "…and that platform had moved 21 of them: …, `reactor-core` on `aimon-session-redis`, and Caffeine and
  `error_prone_annotations` on the starter."
- "…and those 20 are gone; no consumer gained a difference." → "…and 20 of those differences are gone; the 21st, the
  starter's `error_prone_annotations`, fell from 2.49.0 to 2.33.0 under test and still differs from the 2.21.1 it
  ships, now through the vendor SDKs. No consumer gained a difference."
- "The nine differences that remain predate this and have other sources — …" → "The nine that remain — that one, and
  eight that predate this — have other sources: …"

**New bullet directly after it**, in substance:

> **`aimon-cli`'s tests run on the Logback and JAXB API the CLI ships, and the other remaining test-classpath
> differences are recorded as decisions** (#99). Of the nine artifacts #95 left resolving under test to a version their
> module does not ship, the three `spring-boot-starter-test` raised on the CLI — `logback-classic` and `logback-core`
> 1.5.34 against the 1.5.13 it ships, `jakarta.xml.bind-api` 4.0.5 against 4.0.4 — are aligned: the CLI's two test
> classpaths now resolve consistently with its runtime classpath, which for the one application in the build is its
> distribution. The other six are accepted, each with its measured reason, next to `junit` in
> `gradle/libs.versions.toml`: Testcontainers' `org.jetbrains:annotations` and the provider SDKs'
> `error_prone_annotations` are annotation jars no code here reads. Nothing a consumer resolves changes — every compile
> and runtime classpath in the build, and every published POM and module metadata file, is as before. The same note
> now says why `aimon-memory-testkit` publishes a `junit-bom` floor of 5.12.2 while its (testless) test classpath
> resolves 5.13.4, and #95's entry above is corrected: the platform moved 21 artifacts, and 20 differences went away.

### 4.7 `docs/backlog/module-dependency-scope.md` and the index row

- **H1:** `# 백엔드 모듈의 POM 스코프와 테스트 클래스패스 버전 — 등록 항목 3건 (열림 3 · 결정 대기)` — recount from the
  body after writing, per the README's rule; the qualifier is grammar-legal (`check-backlog-registers.py` docstring,
  TITLE).
- **Intro:** one sentence that §2 takes a neighbouring axis from #99 — the versions a module ships against the versions
  its tests run on.
- **New `## 2. 테스트 클래스패스의 버전 — #99 가 남긴 것`**; `## 2. 관련` becomes `## 3. 관련` (no inbound anchor
  links: `git grep "module-dependency-scope.md#"` → 0) and gains the catalog note and `aimon-cli/build.gradle.kts`.
- **`### D-2 — spring-boot-starter-test 가 #99 가 다루지 않은 두 모듈에서 발행 버전을 테스트 아래 올린다 · **열림 · 결정 대기**`**
  - 무엇: `aimon-scheduling-quartz` `jakarta.xml.bind-api` 4.0.4 → 4.0.5; `aimon-knowledge-opensearch`
    `jakarta.annotation-api` 1.3.5 → 2.1.1. Align (the CLI's mechanism) or accept with a reason.
  - 왜: the same source Decision 1 aligned on the CLI; JAXB is code (§2.4); on opensearch the shipped jar's
    `javax.annotation.*` classes are absent under test, and whether any test path needs them is unmeasured. Note, not an
    item of its own: `aimon-session-testkit`'s Logback pair differs too, on a test classpath with no tests, since #95
    removed the platform that used to manage it.
  - 어디: the two build files (not owned by any run in this batch), dated 2026-09-11 on `9b642cc`.
  - 언제 다시 볼까: the next change to either build script; a test there failing on a JAXB or `javax.annotation` class;
    or D-3 being built.
- **`### D-3 — 테스트와 발행 버전의 차이를 적은 기록을 아무것도 검사하지 않는다 · **열림 · 결정 대기**`**
  - 무엇: whether to add a check that fails when a module's `runtimeClasspath` and `testRuntimeClasspath` (and, if
    wanted, `testCompileClasspath`) disagree outside a recorded list.
  - 왜: the record is prose. #95's records were wrong within a day; a Spring Boot, Testcontainers or SDK bump, or a new
    test library, can add or remove a difference with nothing noticing. The compile axis is wholly unrecorded — #95
    and #99 compared runtime with test runtime only, and moving the CLI's mechanism into the conventions plugin moved 72
    `testCompileClasspath` entries (§2.6).
  - 어디: the probe #99's design used (`classpathVersionDiff`, ~30 lines of init script) is the seed; it is not
    committed.
  - 언제 다시 볼까: the next difference found by reading rather than by a check, or the next Spring Boot /
    Testcontainers bump.
- **`docs/backlog/README.md` index row:** `| module-dependency-scope.md | 아키텍처 리뷰 (2026-08-31) · #99 (2026-09-11) | 3 | 3 | 0 | 0 |`.

### 4.8 Deliberately not changed

| file | why not |
|---|---|
| `buildSrc/**` | Nothing here needs every module; §2.6 sizes the conventions-level alternatives |
| `aimon-spring-boot-starter`, `aimon-filesystem-gridfs`, `aimon-session-{mongodb,postgres,redis}` build files | Decisions 2 and 3 accept; no classpath of theirs changes, so none of their docker tiers is at stake |
| any catalog version value, including `junit` and `logback` | Decisions 1 and 4 |
| `aimon-memory-testkit`'s published POM / metadata | Decision 4; comment-only edit, verified identical |
| `settings.gradle.kts:38`, `PublishedModuleApiScopeTest` javadoc | Already say "the other testkits" / "three of the four" correctly |
| `CHANGELOG.md:1510` ("The other two testkits are unchanged.") | Names the two testkits it means in the sentence before; accurate as written |

No translated document is touched (`docs/design/`, `docs/backlog/` and the root `CHANGELOG.md` have no twins).

---

## 5. Data and interface shapes that change

| shape | before | after |
|---|---|---|
| `:aimon-cli:testRuntimeClasspath` | `logback-classic` / `logback-core` 1.5.34, `jakarta.xml.bind-api` 4.0.5 | 1.5.13 / 1.5.13, 4.0.4 — each "by consistent resolution" |
| `:aimon-cli:testCompileClasspath` | the same three, `snakeyaml` 2.5 | 1.5.13 / 1.5.13, 4.0.4, `snakeyaml` 2.7 |
| every other classpath of every project | — | unchanged (29 dumps compared) |
| published POMs / module metadata (21 modules) | — | byte-identical |
| `ModelCapabilityBindingProbe.Builder` | — | package-private `expectedDeclaration(BiFunction<String, Object, ModelCapabilityDeclaration>)` |
| `ModelCapabilityBindingProbe.refusedProbeValue` | package-private static | private static |
| catalog | comments | comments (no value) |
| backlog | 1 item | 3 items (D-2, D-3 open) |

The testkit is unpublished; no public API of any module changes. No wire, config or persisted name changes.

---

## 6. Failure modes and how they are handled

| # | failure | handling |
|---|---|---|
| 1 | A split Logback pair (classic and core on different versions) — `NoSuchMethodError` at startup, the failure `samples/aimon-sample-app/build.gradle.kts:49-53` records | Both are on `runtimeClasspath`, so both are constrained. The after table must show **both** at 1.5.13 on **both** test classpaths |
| 2 | Logback 1.5.13 parsing the CLI's `src/test/resources/logback.xml` or behaving differently for a `ListAppender` assertion | Measured green: 449/0/0/0 (§2.7). The build re-runs `:aimon-cli:test --rerun` and quotes its counts |
| 3 | A future test dependency needs a newer version of a jar the CLI ships | The strict derived constraint downgrades it; the result is a resolution failure (if the library is strict) or a test failure — loud, in `:aimon-cli:test`. The build-file comment says this is the intended signal |
| 4 | Gradle changes or removes the incubating `shouldResolveConsistentlyWith` | `aimon-cli/build.gradle.kts` stops compiling on that upgrade — loud, one file; the comment names the API's status |
| 5 | Configuration cache | Not enabled in `gradle.properties`. The build checks CI (`.github/workflows/*.yml`) for `--configuration-cache`; if present, runs `:aimon-cli:test --configuration-cache` once |
| 6 | Architecture tests that read build scripts as text (`PublishedModuleApiScopeTest`, `PublishedModuleLoggingBindingTest`) | `aimon-cli` is unpublished and not scanned. The memory testkit's edit is a comment without `api(project(` or logback text. `aimon-core`'s `test` task re-runs because its inputs include every module build script — expected cost, and part of `checkAll` |
| 7 | The seam changes probe behaviour for the two real subclasses | Package-private; subclasses live in other packages and cannot call it; the default is `DeclarableKeys::expectedDeclaration`, today's call. The CLI's and starter's contract tests run under `checkAll` |
| 8 | The substituted refusal is not the one the probe recognises (test pins the wrong branch) | The lambda throws core's own refusal, not a copied string; the test asserts the `declaresAnything()` message **and** the absence of "pick one the declaration accepts" |
| 9 | The capability testkit's coverage floor (86) moves | Run `:aimon-llm-capability-testkit:test :aimon-llm-capability-testkit:jacocoTestCoverageVerification` and quote the ratio |
| 10 | The acceptance reasons go stale (a Testcontainers or SDK bump brings code into those jars, or changes the versions) | Numbers in the note are dated; D-3 tracks the missing check |
| 11 | A Quartz bump changes the JAXB version the CLI ships | The test side follows by construction — the reason strict literals were rejected |
| 12 | Backlog checker: title count or index row disagree with the body | Recount from the body; run `check-backlog-registers.py` |
| 13 | Merge conflicts with sibling runs in `CHANGELOG.md` `[Unreleased]` or backlog index rows | Expected by TASK.md; resolved at merge by recounting from the body |

---

## 7. Test strategy

### 7.1 Before the first edit (on the worktree HEAD)

1. **TASK.md's table.** `dependencyInsight` on `runtimeClasspath` and `testRuntimeClasspath` for each pair in #99's
   table — `aimon-cli` × `logback-classic`, `logback-core`, `jakarta.xml.bind-api`; `aimon-spring-boot-starter` ×
   `error_prone_annotations`, `org.jetbrains:annotations`; `aimon-filesystem-gridfs`, `aimon-session-mongodb`,
   `aimon-session-postgres`, `aimon-session-redis` × `org.jetbrains:annotations` (use the `group:name` form — a bare
   `annotations` also matches `jackson-annotations` and `error_prone_annotations`). Add `aimon-cli`
   `testCompileClasspath` for the three CLI artifacts, since the mechanism covers it.
2. **The memory testkit's JUnit:** `:aimon-memory-testkit:dependencyInsight --dependency junit-jupiter-api` on
   `compileClasspath` and `testRuntimeClasspath`, and `:aimon-core` `testRuntimeClasspath`. Quote the path that brings
   5.13.4 (#99 attributes it to Mockito; the design did not pin that path).
3. **The whole build:** `classpath-dump.init.gradle` (from `$RUN_DIR/design/probe/`, never committed) into
   `$RUN_DIR/build/dump-before/`.
4. **Published metadata:** `generatePomFileForMavenPublication generateMetadataFileForMavenPublication` for every
   published module; keep checksums. Quote the memory testkit's `junit-bom` lines from the POM and `module.json`.

### 7.2 After the edits

1. The same `dependencyInsight` table. **Aligned** pairs show equal versions; **accepted** pairs are unchanged and listed
   with their reasons where the decision is recorded.
2. Dump into `dump-after/`; diff. **Expected: exactly the 7 entries of §5, all on `aimon-cli`'s two test classpaths.**
   Anything else moved is a finding to explain before proceeding — and if it touches a Testcontainers module, that
   module's `integrationTest` becomes mandatory.
3. POM and module metadata checksums: **identical for every published module**; quote the memory testkit's constraint
   before and after (TASK.md: its build file changed).
4. **`./gradlew format`, then `./gradlew checkAll`** — report executed test counts, failures and skips per module (or
   totals with the per-module XML sum), not "green". Include `:aimon-cli:test --rerun` counts explicitly (design
   measured 449/0/0/0 under the init-script equivalent).
5. **`:aimon-llm-capability-testkit:test`** counts (the class had 43 tests after #87; the replacement keeps the count)
   and `jacocoTestCoverageVerification` for that module.
6. **The probe test has teeth (not committed):** (a) remove the `try/catch` in `acceptedDeclaration` so the refusal
   propagates — the new test fails; (b) make the catch always produce the "pick one the declaration accepts" message —
   the new test fails; (c) on the unmodified old test, (a) stays green, which is the defect this fixes. Restore and show
   `git diff` is the intended change only. Record all three in `measurements.md`.
7. **`integrationTest`.** No Testcontainers module's classpath changes (7.2.2 proves it), and `aimon-cli` has zero
   `@Tag("docker")` classes, so none is required. Say so with the dump evidence. Docker is running on this machine
   (29.2.1), so if 7.2.2 shows any change on a docker module, run that module's `integrationTest` rather than marking it
   unverified.
8. **The four doc checks:** `check-doc-links.py`, `check-backlog-registers.py`, `check-translation-staleness.py`,
   `check-translation-structure.py`.
9. **Residue:** `git grep -n "twenty artifacts\|20 of them\|unlike the other two testkits\|spring.boot.dependencies"`
   over the owned files returns only the design document's approved body (`:398`) and history.

### 7.3 What goes into `measurements.md` and the PR body

Both `dependencyInsight` tables; the dump diff summary (entries moved, per project and configuration); the POM /
metadata comparison with the memory testkit's constraint quoted; the test counts; the three teeth runs; the #95 recount
(§2.3, re-run on `ade5978` or cited from `$RUN_DIR/design/probe/out/diff-pre95.txt` with its SHA).

---

## 8. Findings outside #99 (for `build/deviations.md`)

- **F-1 — the CLI ships Logback 1.5.13, inside CVE-2025-11226's affected range** (logback-core ≤ 1.5.18, fixed 1.5.19,
  CVSS 4.0 5.9; per the GitHub advisory GHSA-25qh-j22f-pwp8, exploitation needs Janino and Spring Framework on the
  classpath). Measured: neither is on `:aimon-cli:runtimeClasspath`. Upgrading the CLI's Logback is a shipped-dependency
  decision #99 does not ask for; Decision 1's mechanism means a catalog bump carries the tests along. Not registered
  (no reserved ID fits a security bump); for the maintainer.
- **F-2 — same-source differences #99 did not name** (§2.2): registered as D-2.
- **F-3 — `ModelCapabilityBindingProbeTest.refusesAPairEqualsCannotTellApart` pins `requireDistinguishable` through the
  helper**, the same shape as record 4 (unreachable while `equals` covers every field). The seam from Decision 5 would
  reach it too; not in #99.
- **F-4 — the test *compile* axis is unmeasured build-wide.** #95 and #99 compare runtime with test runtime; §2.6's
  conventions experiment moved 72 `testCompileClasspath` entries. Folded into D-3's body.
- **F-5 — `docs/design/documentation/backlog-register-check.md:47`** quotes this register's title as
  `1 (열림 1 · 결정 대기)`; after D-2/D-3 it reads as the design-time record it is. The file belongs to the
  `backlog-checker-blind-spots` run; not touched.

---

## 9. Open questions

1. **Is an `@Incubating` Gradle API acceptable in a module build script?** Assumed yes (loud failure mode, existing
   unstable-API use in `buildSrc`). If the maintainer says no, the fallback is strict constraints — the Logback pair via
   `libs.versions.logback`, JAXB as a literal whose Quartz drift is written into the comment.
2. **Should #99 add a `CHANGELOG.md` bullet at all?** Criterion 4 does not require one (nothing a consumer resolves
   changes); the ground rules' "every behaviour change" does. The design adds one that says so.
3. **Should the CLI's shipped Logback move off 1.5.13?** F-1. Not decided here.
4. **Is `module-dependency-scope.md` the right register for D-2/D-3?** Its IDs were reserved for this run there, so
   assumed yes; the H1 is widened. A separate register would need a prefix no run reserved.
5. **Is a run of the memory suite at exactly its floor worth having?** Recorded as unverified in the `junit` note; not
   registered.
6. **Placement of the record** — above the `junit` note (chosen, so the note still ends at its value) or below the
   value. Cosmetic; the maintainer can move it.

---

## 10. Closing

All three parts of #99 are addressed, so the PR body carries `Closes #99`. Commit subject in the house style, e.g.
`fix(build): run the CLI's tests on the versions it ships and record the remaining test-classpath differences (#99)`.
PR body: one heading per decision (§3.1–§3.6) with taken / rejected / why, the before and after tables, and §8.

---

## 11. After the build — departures and corrections

*Appended 2026-09-11, after implementation. Everything above this section, except the `Status` header, is the body as
approved in design review round 1, byte-exact. Three sources feed this section: the run's `build/deviations.md`, the
design review (`review-1.md`: PASS, no blocking findings, seven non-blocking), and what was measured while building.*

**Every decision in §0 was built as decided, and the build moved exactly what §5 predicted.** The before and after
dumps of all four classpaths of all 29 projects differ in 7 of 5,221 entries, all on `aimon-cli`'s two test
classpaths, 0 added or removed, and all 42 published POM and module-metadata files are byte-identical. What departs
is wording: several sentences the body uses as evidence, or drafts for committed text, are wrong. §11.1 lists each
with what was committed instead; none changes a decision.

### 11.1 Where the body is wrong

The review found the first seven. Each was corrected where it was committed or quoted, and the original sentence
appears nowhere outside this document's body.

- **C-1 — §3.5 and §7.2.6: "deleting that catch leaves the build green" is false.** Deleting the catch in
  `acceptedDeclaration` turns `refusesAValueTheDeclarationRefuses` red: that test reaches the catch through the real
  path with an empty `EnumSet`. What the build could not see was a catch that *reroutes* the empty-declaration refusal
  to the "pick one the declaration accepts" message. Measured on the original code with that reroute: 44 tests,
  0 failures. §11.3 has the three runs that replace §7.2.6's.
- **C-2 — §4.5, the §11.5 draft: "nine of them on `aimon-cli`, the consumer this document added".** The round-trip
  document added two consumers, `aimon-cli` (its §4.2) and `aimon-spring-boot-starter` (its §4.3), and the platform
  had moved eleven artifacts on them. The committed §11.5 says eleven and names both.
- **C-3 — §4.1, the build comment: "A test library that needs a newer version of a jar the CLI ships now fails here,
  loudly".** The derived constraints are strict, so a newer request is downgraded without a message. Resolution fails
  only when that library's own request is strict; otherwise the signal is a linkage error in `:aimon-cli:test`, and
  only if a test reaches the missing API. §6 row 3 already said so. The committed comment says it too, and adds that
  such a need "is a decision to take on purpose — this block will not announce it".
- **C-4 — §4.2(a), the catalog block.** Its header ("leaves three sources of difference, decided here") contradicted
  its own first bullet, which leaves quartz and opensearch undecided, and ignored a fourth source, JUnit on
  `aimon-memory-testkit`. The `spring-boot-starter-test` bullet also omitted `aimon-session-testkit`'s Logback pair.
  The committed block says the differences come from four sources and it decides three. It names all three
  undecided modules with their versions, the session testkit included, and points at D-2.
- **C-5 — "the one application" (§3.1's catalog draft, §4.6).** `samples/aimon-sample-app` is an application too. The
  decision rests on "whose runtimeClasspath is its distribution" (`tasks.jar`), which is what was committed; "the one"
  is gone.
- **C-6 — §4.7, D-3's 어디, pointed at a probe in `$RUN_DIR`**, which a later reader cannot open. The committed D-3
  says no code exists and gives the probe's core in one sentence. That core compares
  `incoming.resolutionResult.allComponents` of the two configurations, `ModuleComponentIdentifier` only, by
  `group:name`. D-3 also names where a check would live: a task `aimon.java-conventions` registers per module and the
  root `checkAll` collects.
- **C-7 — §7.2.5: "the class had 43 tests after #87".** `ModelCapabilityBindingProbeTest` has 18 test methods, and the
  module runs 44 tests, before and after.

Found while building:

- **C-8 — §4.5 cites `#91 (PR #95, 764f371)`.** `764f371` is #91's fix commit; the merge is `36d70e4`. The committed
  §11.5 says "#91 (`764f371`, merged as PR #95)".
- **C-9 — §2.7 and §3.1 name `src/test/resources/logback.xml`, which does not exist.** `aimon-cli` has no test Logback
  configuration. Its one `logback.xml` is `src/main/resources/logback.xml`, the file the distribution ships. It sits
  on the test runtime classpath through the main resources, so the tests were already configured by the shipped file
  while running a Logback the distribution does not ship. §3.1's reason does not weaken. Nothing committed repeats the
  wrong path: the build comment says only "the Logback these tests assert on", which `AgentSetupFactoryAgentModelCheckTest`'s
  `ListAppender` makes true.

### 11.2 Where the build departed from the body

- **B-1 — this document lives in `docs/design/testing/`, and `docs/design/README.md` now names that axis.** No
  subsystem domain fits. The first copy went to `docs/design/build/`, which the root `.gitignore`'s `build/` ignores at
  any depth (`git check-ignore -v`), so it would never have been committed. The README's new section says why the
  name is not `build`.
- **B-2 — D-2 carries `aimon-session-testkit` as a row, not a note.** Once the catalog names it as undecided and
  points at D-2 (C-4), a mere note under D-2 would record it more weakly than the pointer sending readers there. The
  row says the difference runs no test — the module has `src/main` only — and why it is still recorded: on `ade5978`
  there was no difference.
- **B-3 — the catalog's memory-testkit paragraph names what raises each JUnit**, which §7.1.2 asked the build to
  pin. `mockito-junit-jupiter` 5.23.0 brings 5.13.4 onto the testkit's `testRuntimeClasspath`, which confirms #99's
  attribution. `archunit-junit5` 1.5.0 brings 5.14.4 onto `aimon-core`'s.
- **B-4 — the new CHANGELOG bullet also covers record 4 and names D-2 and D-3**, which §4.6's draft left out.
- **B-5 — the builder's default is assigned in its constructor, not in a field initializer.** The initializer is 129
  characters, the formatter leaves it on one line, and Checkstyle's `LineLength` (120) failed `checkstyleMain`. The
  `build()` javadoc also names a `null` expected declaration as a cause of its `NullPointerException`.
- **B-6 — the test's probe variable is `builderSkipsTheKey`.** The enclosing `@Nested` class already has a `probe`
  field.
- **B-7 — the memory testkit's comment paragraph is reflowed.** §4.3's three new lines left a short line in the middle
  of the paragraph; the reflow is still comment-only, and that module's POM and module metadata, regenerated after
  it, are identical to before (§11.3).

### 11.3 What was measured

**`dependencyInsight`, #99's pairs.** On `aimon-cli`, `logback-classic` and `logback-core` resolve to 1.5.13, and
`jakarta.xml.bind-api` to 4.0.4, on `runtimeClasspath` before and after. On `testCompileClasspath` and
`testRuntimeClasspath` they went from 1.5.34 / 1.5.34 / 4.0.5 to **1.5.13 / 1.5.13 / 4.0.4**. `dependencyInsight`
reports the reason as *"By constraint: version resolved in configuration ':aimon-cli:runtimeClasspath' by consistent
resolution"*. Every accepted pair is unchanged:
- the starter's `error_prone_annotations`, 2.21.1 shipped, 2.33.0 under test;
- `org.jetbrains:annotations`, 13.0 shipped and 17.0.0 under test, on the starter, `aimon-filesystem-gridfs`,
  `aimon-filesystem-s3` and `aimon-session-{mongodb,postgres,redis}`.

**The whole build.** `runtimeClasspath` against `testRuntimeClasspath` shows 21 differing entries before and 18 after;
the three that went are the CLI's. The 18 left are:
- the six accepted `annotations`/`error_prone` rows;
- D-2's four entries: quartz JAXB, opensearch `jakarta.annotation-api`, and the session testkit's Logback pair;
- the memory testkit's seven JUnit entries.

D-2's sources, by `dependencyInsight`:
- quartz JAXB 4.0.5 comes from `spring-boot-starter-test`, against Quartz's 4.0.4;
- opensearch 2.1.1 comes via `spring-boot-starter` ← `spring-boot-starter-test`;
- the session testkit's Logback comes via `spring-boot-starter-logging` ← `spring-boot-starter` ←
  `spring-boot-starter-test`.

**Every classpath.** 29 projects × 4 configurations: 5,221 entries before and after, and **7 moved**. On `aimon-cli`'s
`testCompileClasspath`: `logback-classic` and `logback-core` 1.5.34 → 1.5.13, `jakarta.xml.bind-api` 4.0.5 → 4.0.4,
and `snakeyaml` 2.5 → 2.7. On its `testRuntimeClasspath`: the same first three. No `compileClasspath` or
`runtimeClasspath` of any project moved.

**Publications.** 42 files (21 modules × POM and module metadata), SHA-256 identical before and after.
`aimon-memory-testkit`'s constraint, before and after: the POM imports `org.junit:junit-bom:5.12.2`, and `module.json`
requires `junit-bom` 5.12.2 as a platform in `apiElements` and `runtimeElements`. The files regenerated after B-7 are
identical as well.

**#95's recount** on the seven consumers, from the design phase's diff on `ade5978`: 29 differences before, 9 after;
20 gone; one moved — the starter's `error_prone_annotations`, 2.49.0 → 2.33.0 against 2.21.1 shipped. Six of the seven
consumers carried the 21, and `aimon-core` none.

**The probe test's teeth** — `:aimon-llm-capability-testkit:test --rerun`:

| run | probe | test class | tests | failures |
|---|---|---|---|---|
| (a) | catch in `acceptedDeclaration` deleted | new | 44 | **2** — the new test, and `refusesAValueTheDeclarationRefuses` |
| (b) | catch rerouted to "pick one the declaration accepts" | new | 44 | **1** — the new test |
| (c) | catch rerouted, on the original code | original | 44 | **0** — the defect this fixes |
| — | this change, unmutated | new | 44 | 0 |

Coverage after the change: 199 of 226 lines (88.05%) against the module's floor of 86.

**The gate.** `./gradlew format`, then `./gradlew checkAll --continue`: BUILD SUCCESSFUL in 3m 44s (240 actionable
tasks, 83 executed). Every `test` task with sources ran in that invocation. **21 modules, 10,891 tests, 0 failures,
0 errors, 72 skipped.** The skips are all in modules this change does not touch. `aimon-cli` ran 449 tests with 0
failures and 0 skipped, and `:aimon-cli:test --rerun` did the same again on Logback 1.5.13. The four document checks
pass.

**`integrationTest`: not required, not run.** No Testcontainers module's classpath changed — the 7 moved entries are
all on `aimon-cli`, which has no `@Tag("docker")` and no `@Tag("packaging")` class. CI runs `checkAll` without the
configuration cache (§6 row 5), so no configuration-cache run was needed.

### 11.4 The §8 findings and §9 open questions — where each went

- **F-1** (the CLI ships Logback 1.5.13, inside CVE-2025-11226's range) has a consequence beyond this change. But it is
  a shipped-dependency decision, and neither ID this run could register (D-2, D-3) is about it. So it went to the PR
  body for the maintainer to register, not to the backlog. §9's open question 3 is the same question.
- **F-2** → backlog **D-2**, with the session testkit's pair as a row (B-2).
- **F-3** (`refusesAPairEqualsCannotTellApart` pins `requireDistinguishable` through its helper) stays here. It has no
  consequence outside this test class, and the seam this change added could reach it.
- **F-4** → folded into **D-3**. Its numbers were recomputed from the design dumps: 94 entries changed in 23 projects
  (93 moved, 1 failed to resolve), 72 of them on `testCompileClasspath`.
- **F-5** (`backlog-register-check.md` quotes the register's old title) stays here. That file belongs to another run
  in this batch, and the quote reads as the design-time record it is.
- **Open questions 1, 2, 4, 6 stay here.** They are answered by what was built, and each has a stated fallback the
  maintainer can take at review. Question 1: the `@Incubating` API is used, with the strict-constraints fallback.
  Question 2: a CHANGELOG bullet was added. Question 4: `module-dependency-scope.md` holds D-2 and D-3. Question 6:
  the record sits above the note.
- **Open question 5** (run the memory suite at exactly its floor) stays here. It is written into the `junit` note as
  unverified, and it has no consequence outside that note's claim.

D-2 and D-3 each point back here through [`../../backlog/module-dependency-scope.md`](../../backlog/module-dependency-scope.md) §2.

### 11.5 Later departures

*Appended 2026-09-11 for #114 and #120, whose design is
[`shipped-logback-and-test-classpath-followups.md`](shipped-logback-and-test-classpath-followups.md). Everything above
is left as written; this records where it stopped matching the tree.*

- **§3.1's "raise what ships", F-1 and open question 3 — the catalog's `logback` moved** (#114), to 1.6.3. §3.1 rejected
  raising it *to equalise test numbers*, and that rejection still holds as a reason. What moved it is the reason F-1
  left for the maintainer, and it turned out wider than F-1 said. 1.5.34, the version the rejected option named, is
  inside CVE-2026-13006 (logback-core ≤ 1.5.36). Every 1.5.x is inside CVE-2026-19880 (logback-classic ≤ 1.6.2), fixed
  only on 1.6.x. Consistent resolution carried the CLI's tests with the bump, as §3.1 said it would.
  `aimon-session-testkit`'s pair left D-2 (B-2) — outranked, not aligned.
- **§3.1's "Cost accepted", §6 row 4 and open question 1.** Two supporting sentences were wrong.
  - "`buildSrc` already uses unstable API": `@Suppress("UnstableApiUsage")` sits on `VersionCatalogsExtension`, which
    has no `@Incubating` in Gradle 9.2.1 (javap).
  - "If a Gradle upgrade changes it, this build script stops compiling": true of a removal, which fails every build
    since every project is configured. A change in behaviour, on the other hand, can pass without failing anything.

  #120 decided the question with both halves written down: acceptable, on terms now at the end of
  `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts`. Backlog D-3 lists a Gradle upgrade as a trigger.
- **F-3 → done** (#120). `refusesAPairEqualsCannotTellApart` drives `requireDistinguishable` through the
  `expectedDeclaration` seam §3.5 added, and the helper is private.
- **Open question 5 → run once.** The suite ran on exactly JUnit 5.12.2 (21 tests, 0 failures). The `junit` note says
  so, how, and that nothing repeats it.
