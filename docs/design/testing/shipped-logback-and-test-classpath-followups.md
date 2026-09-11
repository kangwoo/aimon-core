# Design — #114 and #120: the Logback the CLI ships, and four follow-ups from #111

> Status: **IMPLEMENTED** — `gradle/libs.versions.toml` (`logback` at 1.6.3 and its note, the `spring-boot-starter-test`
> bullet, and the end of the `junit` note, which records the run on JUnit 5.12.2), the `@Incubating` paragraph of the
> comment in `modules/aimon-cli/build.gradle.kts`, the note at the end of
> `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts`, `ModelCapabilityBindingProbe` (its
> `requireDistinguishable` is private) and `ModelCapabilityBindingProbeTest.refusesAPairEqualsCannotTellApart`, backlog
> D-2 and D-3, §11.5 of [`test-classpath-shipped-versions.md`](test-classpath-shipped-versions.md), and the section of
> `CHANGELOG.md` that opens "CLI: the distribution ships Logback 1.6.3". Sources: issues
> [#114](https://github.com/kangwoo/aimon-core/issues/114) and [#120](https://github.com/kangwoo/aimon-core/issues/120).
>
> **[§13](#13-after-the-build--departures-and-corrections), appended after the build, is where this document departs
> from what was built.** Everything between this header and §13 is the body as approved in design review round 3, kept
> byte-exact rather than corrected — the house habit in this directory, for the reason
> [`../llm/model-capability-binding-round-trip.md`](../llm/model-capability-binding-round-trip.md) gives. Its
> `file:line` citations are at `main` `c561e17`. The run records it cites (`TASK.md`, `review-1.md` to `review-3.md`,
> `$RUN_DIR/design/probe/`, `$RUN_DIR/build/`) are not in the repository; §13 reproduces the measurements that matter.
>
> [§14](#14-after-129-and-134--corrections-and-where-the-findings-went), appended after #129 and #134, corrects what
> the body and §13.3 say about GitHub's advisory database and what F-2 says about Dependabot, and records the
> correction mark at §4.10.
>
> What this work left open is in [`../../backlog/module-dependency-scope.md`](../../backlog/module-dependency-scope.md),
> D-2 and D-3.
>
> It is in English, matching `test-classpath-shipped-versions.md` beside it; `docs/design/` is not a translation target
> either way (`docs/project/documentation-guide.md` §5.1).

> Base: `main` at `c561e17`, worktree branch `herdr/dependency-scope-followups`. `git status --porcelain` was empty
> before and after every probe below.
>
> Every number here was **measured by the design phase on 2026-09-11** on that tree. They are the prediction the build
> phase's own before/after measurements are checked against, not a substitute for them.
>
> **Revised after design review rounds 1 and 2** (`review-1.md` and `review-2.md`, each FAIL on one blocking finding).
> Round 1: Decision 1 now takes **1.6.3**, not 1.5.38, because CVE-2026-19880 covers every `logback-classic` up to 1.6.2.
> Round 2: the CHANGELOG draft no longer says CVE-2026-13006 lacks a 1.5.x fix, and this document is now committed as a
> design record (§3.6, §4.10). §12 lists what changed in each round and why.
>
> Probes are Gradle init scripts and two single-file Java programs in `$RUN_DIR/design/probe/` (never committed); their
> output is in `$RUN_DIR/design/probe/out/`: `dump-before/` (all four classpaths of all 29 projects),
> `dump-after-logback{,-1.5.38,-1.6.3}/` (the catalog bump emulated at 1.5.34, 1.5.38, 1.6.3),
> `dump-{1.5.38,1.6.3}-consistent-off/`, `insight-*.txt`, `checkall-{1.5.38,1.6.3}.log` with their `-counts.tsv`,
> `dist-{base,1.5.38,1.6.3}/` with `logback-status-*.txt`, `boot-logback-linkage-*.txt`,
> `starter-integration-1.6.3-results/`, `floor-*.log` with `floor-*-test-results/`. The emulation
> (`logback-bump-emulation.init.gradle`) rewrites every request for `ch.qos.logback` at 1.5.13 to the target version, which
> moves resolution exactly as the catalog edit would; the build replaces it with the real edit.
>
> External facts come from NVD (`keywordSearch=logback`, and the CVE records), GitHub's advisory database
> (`gh api graphql`, `securityVulnerabilities` for both Logback artifacts), OSV (`/v1/query`), Maven Central metadata,
> `logback.qos.ch/news.html` and `notes/release_1.6.0.txt`, and Gradle's 9.7.1 javadoc and feature-lifecycle page. No LLM
> provider API was called.

---

## 0. The decisions, first

| # | Question | Answer |
|---|---|---|
| **1** | #114 — raise `logback` or keep 1.5.13 | **Raise to 1.6.3**, the one version past every Logback CVE NVD lists. No 1.5.x qualifies. CVE-2026-19880 (`logback-classic` ≤ 1.6.2) is fixed only in 1.6.3, and Logback now calls 1.5.x its legacy line. CVE-2026-13006 (logback-core ≤ 1.5.36) circumvents 1.5.19's fix. The change moves 39 resolved entries on 12 projects and no published classpath. On the emulated bump the unit gate is green (10,946 tests, 0 failures) and so is the starter's docker tier; the bundled `logback.xml` loads with the same status as today. §3.1 |
| **2** | #120 item 2 — is an `@Incubating` Gradle API acceptable in a module build script | **Yes, on stated terms; `shouldResolveConsistentlyWith` stays.** The terms go at the end of `aimon.java-conventions.gradle.kts`, where the build's conventions live. The CLI's comment names both halves of what an upgrade that changes it can break: a removal fails every build, while a change in behaviour can pass without failing anything. It also names the command that shows the second. Backlog D-3 gains a Gradle upgrade as a trigger. §3.2 |
| **3** | #120 item 4 — the memory testkit's JUnit 5.12.2 floor | **Run it once**, through a command-line init script. Design run: `StoreBackedPeerMemoryContractTest`, 21 tests, 0 failures, on Jupiter 5.12.2 / Platform 1.12.2. The build repeats it and the `junit` note records the outcome and how to repeat it. §3.3 |
| **4** | #120 item 1 — D-2's sentence | **Rewritten** for the two rows that remain; `aimon-session-testkit`'s row leaves D-2 because of Decision 1, with a dated note. D-2 and D-3 **stay open**. §3.4 |
| **5** | #120 item 3 — `requireDistinguishable` pinned through its helper | **Driven end to end** through the `Builder.expectedDeclaration` seam; the helper becomes `private`. §3.5 |
| **6** | Bookkeeping | A new `[Unreleased]` section. §11.5 appended to `test-classpath-shipped-versions.md`; no note due in `model-capability-binding-round-trip.md`. **This document is committed as a design record** in `docs/design/testing/`, with its index row. No backlog item registered; the register's title and index row recount to the same values. §3.6 |

---

## 1. The problem, in one paragraph

`aimon-cli` is the one module whose `runtimeClasspath` is a distribution a user runs, and it packs the catalog's Logback
pair at 1.5.13. Measured for this design, 1.5.13 is inside six Logback advisory ranges:
- CVE-2025-11226, which #114 reports;
- CVE-2026-13006 (logback-core ≤ 1.5.36), which NVD describes as circumventing 1.5.19's fix;
- CVE-2026-19880 (`logback-classic` ≤ 1.6.2), which has no fix on the 1.5.x line at all;
- three LOW advisories (CVE-2026-1225, CVE-2026-9828, CVE-2026-10532), all fixed by 1.5.34.

The two newest have no entry in GitHub's advisory database or OSV. #111 (for #99) found the first and deliberately left
it. It did make the CLI's tests follow any catalog bump, through `shouldResolveConsistentlyWith`, an `@Incubating` Gradle
API. #111 accepted that API on two sentences this design found to be half-true: that `buildSrc` already used unstable API
(the suppression sits on a stable one), and that a Gradle change would make the script stop compiling (true of a removal,
not of a behaviour change). The same work left three smaller records open:
- D-2 says its differences are "not annotation jars", when `jakarta.annotation-api` is one;
- the probe's equality refusal is still tested by calling its helper, which a deleted call site would not fail;
- the memory contract suite publishes a JUnit 5.12.2 floor that no run has ever exercised.

#114 asks for the shipped Logback to move or its staying to be justified. #120 asks for the four records to be settled,
each with a reason the maintainer can overturn.

---

## 2. What was measured

### 2.1 Logback advisories — searched, not looked up

**NVD `keywordSearch=logback`** returns 17 CVEs in all. Ten were published from June 2024 on: the eight Logback CVEs below,
and two unrelated products that mention Logback (Apache NiFi, CtrlPanel). The other seven are older. Every Logback CVE
since 2024, with GitHub's advisory entry where one exists:

| CVE | NVD (published · score) | GHSA (GitHub) | Affected | First unaffected | Needs |
|---|---|---|---|---|---|
| CVE-2024-12798 | 2024-12-19 · 5.9 MEDIUM | GHSA-pr98-23f8-jwxv | logback-core ≤ 1.5.12 | 1.5.13 | compromised config (`JaninoEventEvaluator`) |
| CVE-2024-12801 | 2024-12-19 · 2.4 LOW | GHSA-6v67-2wr5-gvf4 | logback-core ≤ 1.5.12 | 1.5.13 | compromised config (SSRF) |
| **CVE-2025-11226** (#114) | 2025-10-01 · 7.0 HIGH | GHSA-25qh-j22f-pwp8, MODERATE 5.9 | logback-core ≤ 1.5.18 | 1.5.19 | Janino **and** Spring Framework on the class path; write access to a config file or an injected env var |
| CVE-2026-1225 | 2026-01-22 · 1.8 LOW | GHSA-qqpg-mvqg-649v | logback-core ≤ 1.5.24 | 1.5.25 | compromised config (instantiates classes already on the class path) |
| CVE-2026-9828 | 2026-05-28 · 2.9 LOW | GHSA-p47f-322f-whfh | logback-core ≤ 1.5.32 | 1.5.33 | deserialization (`HardenedObjectInputStream`) |
| CVE-2026-10532 | 2026-06-01 · 2.9 LOW | GHSA-jhq6-gfmj-v8fx | logback-core ≤ 1.5.33 | 1.5.34 | deserialization (`HardenedObjectInputStream`) |
| **CVE-2026-13006** | 2026-06-24 · 7.0 HIGH (*Deferred*) | **none** | logback-core ≤ **1.5.36** | 1.5.37 (Janino conditionals removed) | Janino on the class path; write access to a config file or an injected env var; "circumventing existing protections against CVE-2025-11226" |
| **CVE-2026-19880** | 2026-08-14 · 6.3 MEDIUM (*Deferred*) | **none** | **logback-classic** 0.9.14 – **1.6.2** | **1.6.3** | an MDC-based discriminator (`SiftingAppender`) whose value flows into a nested `FileAppender` path, and an attacker who influences that MDC value (e.g. an HTTP header) |

Two other sources miss both CVE-2026-13006 and CVE-2026-19880. GitHub's `securityVulnerabilities` returns neither; for
`logback-classic` it returns nothing newer than 2023. OSV has no record of either: `/v1/vulns/CVE-2026-13006` and
`/v1/vulns/CVE-2026-19880` both answer 404 "Vulnerability not found" (where `CVE-2025-11226` answers with its GHSA alias),
and `/v1/query` returns **no** vulnerability for `logback-classic` or `logback-core` at 1.5.34 or 1.5.36 (inside both), at
1.5.38 (inside CVE-2026-19880), or at 1.6.3. **An empty answer from GitHub or OSV proves nothing about Logback today**;
this is why §7.1.4 searches NVD and logback's news page.

**Versions.**
- Maven Central: the newest 1.5.x is 1.5.38 (2026-07-09); the 1.6.x releases are 1.6.0 (2026-07-23), 1.6.1 (07-28),
  1.6.2 (08-10) and **1.6.3 (08-14)**, the newest overall.
- Logback's news page: *"Latest STABLE version: the 1.6.x series … a direct descendant of, and a drop-in replacement for,
  the 1.5.x series, with the notable exception of conditionals using Janino"* (removed already in 1.5.37). *"The 1.5.x
  series is now considered legacy."* 1.6.x runs on JDK 11 with SLF4J 2.0.x.
- 1.6.3's entry opens *"In relation to CVE-2026-19880, `MDCBasedDiscriminator` (used by `SiftingAppender`) now strips
  forward and backward slashes …"*; no 1.5.x release followed.
- `logback-parent`: 1.5.34, 1.5.37 and 1.5.38 declare `slf4j.version` 2.0.17; **1.6.3 declares 2.0.18**. All declare
  `jdk.version` 11.
- `logback-classic` 1.6.3's non-optional dependencies are `logback-core` and `slf4j-api`; `logback-core` 1.6.3 adds an
  optional `org.jline:jansi-core` beside the optional FuseSource `jansi`.
- `spring-boot-dependencies` 3.5.16, the newest 3.5.x, manages `logback.version` **1.5.34** (with `slf4j.version` 2.0.18 and
  `junit-jupiter.version` 5.12.2). 4.0.8 and 4.1.1, the newest of the 4.x lines, manage **1.5.38**. **No Spring Boot line
  manages 1.6.x.**

### 2.2 What ships, and what the advisories need

- `:aimon-cli:runtimeClasspath` (what `tasks.jar` and the distribution pack) holds:
  - `logback-classic` and `logback-core` 1.5.13;
  - `slf4j-api` 2.0.18 ("by conflict resolution: between versions 2.0.18, 2.0.16, 2.0.15, 2.0.7 and 1.7.36");
  - `jakarta.xml.bind-api` 4.0.4;
  - `jansi` 2.4.3 in the distribution's `lib/`.
- **Janino: 0 of 5,221 entries** across all four classpaths of all 29 projects. No `org.springframework` module on
  `:aimon-cli:runtimeClasspath`.
- **No MDC anywhere**: `git grep 'MDC\.'` over every module's and sample's Java sources finds nothing.
- **No `SiftingAppender` or discriminator in any tracked Logback configuration.** The repository's only one is
  `modules/aimon-cli/src/main/resources/logback.xml`: a `ConsoleAppender` named `CONSOLE` that nothing references, a
  `RollingFileAppender` with `TimeBasedRollingPolicy` and no compression, `${user.home}` substitution, and no `<if>` or
  `<condition>`.
- So no advisory's precondition holds in this build today — a statement about the classpath and the configuration, not
  about the version.
- Logback on any project's `compileClasspath`/`runtimeClasspath`:
  - `aimon-cli` and `aimon-session-testkit` (the catalog's 1.5.13; both unpublished);
  - `aimon-sample-app` (Boot's 1.5.34; it names no catalog entry — §8, F-8);
  - `aimon-spring-boot-starter`'s `compileClasspath` only (Boot's 1.5.34).

  No published module's `runtimeClasspath` carries Logback.
- The repository's Java references to Logback are `Logger`, `Level`, `ILoggingEvent` and `ListAppender`, all in tests. Its
  configuration references `ConsoleAppender`, `RollingFileAppender` and `TimeBasedRollingPolicy`.

### 2.3 What a catalog bump moves

Whole-build dump, emulated bump against `dump-before` (5,221 entries, 0 unresolved):

| target | entries moved | projects | published `compile`/`runtimeClasspath` moved | resolution failures | runtime-vs-testRuntime differences |
|---|---|---|---|---|---|
| 1.5.34 | 13 — `aimon-cli` 8 (the pair on all four classpaths), `aimon-session-testkit` 5 (the pair on `compile`/`runtimeClasspath`, `slf4j-api` 2.0.15 → 2.0.17 on `compileClasspath`) | 2 | none | 0 | 18 → 16 (`dependencyInsight` shows the session testkit's pair at 1.5.34 on both classpaths) |
| 1.5.38 | 39 | 12 | none | 0 | 18 → 16 |
| **1.6.3** | **39** — the **same entries** as at 1.5.38, at 1.6.3, except `slf4j-api` on the session testkit's `compileClasspath`, which goes 2.0.15 → **2.0.18** | **12** | **none** | 0 | **18 → 16** — gone: the session testkit's pair; none new |

The 39 at 1.6.3, every one moving `logback-classic` and `logback-core` together:

- `aimon-cli`: the pair on all four classpaths, 1.5.13 → 1.6.3 (8).
- `aimon-session-testkit`, 9 entries:
  - the pair 1.5.13 → 1.6.3 on `compileClasspath` and `runtimeClasspath`;
  - the pair 1.5.34 → 1.6.3 on `testCompileClasspath` and `testRuntimeClasspath`;
  - `slf4j-api` 2.0.15 → 2.0.18 on `compileClasspath`.
- `testRuntimeClasspath` 1.5.34 → 1.6.3 (18 entries) on `aimon-bootstrap`, `aimon-browser-playwright`,
  `aimon-rewake-webhook`, `aimon-scheduling-quartz`, `aimon-session-mongodb`, `aimon-session-postgres`,
  `aimon-session-redis`, `aimon-session-routing` and `aimon-spring-boot-starter`. `aimon-core` moves on both test
  classpaths (4). These modules name the catalog entry at test scope, and above 1.5.34 the catalog now outranks what
  `spring-boot-starter-test` brings (`insight-1.5.38-starter-testRuntimeClasspath.txt`: requested 1.5.13 by the module and
  1.5.34 by `spring-boot-starter-logging`, resolved to the higher).

`slf4j-api` on `aimon-cli` stays 2.0.18 on every classpath.

The step from 1.5.34 to anything above it is the price of Decision 1: at 1.5.34 only two unpublished projects move, while
above it the test classpaths of ten more do, and six of those ten have a docker tier (§7.2). Between 1.5.38 and 1.6.3 the
price is the same.

### 2.4 The unit gate on the emulated bump

`./gradlew -Daimon.logbackTo=<v> -I logback-bump-emulation.init.gradle checkAll --continue`, run twice:

| target | result | modules · tests · failures · errors · skipped |
|---|---|---|
| 1.5.38 | exit 0 | 21 · 10,946 · 0 · 0 · 72 |
| **1.6.3** | **BUILD SUCCESSFUL in 2m 43s**; the test tasks of all 11 moved modules that have tests executed | **21 · 10,946 · 0 · 0 · 72** |

The per-module counts are identical at both targets:
- `aimon-cli` 470;
- `aimon-spring-boot-starter` 261;
- `aimon-core` 8,168, 2 skipped;
- `aimon-session-routing` 202;
- `aimon-browser-playwright` 236;
- `aimon-bootstrap` 172;
- `aimon-scheduling-quartz` 147;
- `aimon-session-redis` 38;
- `aimon-session-mongodb` 32;
- `aimon-session-postgres` 25;
- `aimon-rewake-webhook` 36.

All of these have 0 failures. The starter's docker tier at 1.6.3,
`:aimon-spring-boot-starter:integrationTest --rerun`, was BUILD SUCCESSFUL with `AimonClusterIntegrationTest`
("Two starter nodes over one Redis") at 2 tests, 0 failures. The other moved modules' docker tiers and `playwrightTest`
were not run in design (§7.2).

### 2.5 The CLI's test classpaths if consistent resolution silently stopped

`consistent-off.init.gradle` calls `disableConsistentResolution()` on `aimon-cli`'s two test classpaths after the build
script, with the bump emulated, and dumps again. At **both 1.5.38 and 1.6.3** (5,221 entries each, 0 unresolved)
**exactly three entries differ, all on `aimon-cli`**:

| classpath | artifact | with consistent resolution | without | shipped (`runtimeClasspath`) |
|---|---|---|---|---|
| `testCompileClasspath` | `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.4 | 4.0.5 | 4.0.4 |
| `testCompileClasspath` | `org.yaml:snakeyaml` | 2.7 | 2.5 | 2.7 |
| `testRuntimeClasspath` | `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.4 | 4.0.5 | 4.0.4 |

The Logback pair is **not** among them: above 1.5.34 it resolves the shipped version on both test classpaths with or
without the block. After Decision 1 this is the whole of what the incubating call holds in place today, and the most a
silent change to it could undo.

### 2.6 What Logback 1.6.x changes, against this repository

The 1.6.0–1.6.3 release notes, line by line against what this repository ships, tests and configures:

| release | change | reaches this repository? |
|---|---|---|
| 1.5.37 (for context) | Janino conditionals removed | No: no `<if>` in the bundled config, and no Janino on any classpath |
| 1.6.0 | Removed deprecated API: `ReconfigureOnChangeFilter`; `PatternLayout` converter-map members; `ContextInitializer` config-file constants; `ModelUtil#setProperty`/`setProperties`; `EnvUtil#logbackVersion`; and eight more (`notes/release_1.6.0.txt`) | **No.** The repository references none (§2.2). Spring Boot 3.5.16's logging integration (35 classes in `org.springframework.boot.logging.logback`) references 73 Logback classes and 158 members; a reflection probe (`LinkageProbe.java`, name + descriptor, inherited members resolved) finds **all 158 on 1.6.3, as on 1.5.34, 0 missing**. Of `ModelUtil` it calls only `resetForReuse`, which 1.6.3 keeps |
| 1.6.0 | SLF4J 2.0.18 | Equal to the catalog's `slf4j` |
| 1.6.1 | `TimeBasedRollingPolicy` renames the intermediate file before async compression; a failed compression keeps the source | No: the bundled policy uses no compression ("No compression will be used" in its status) |
| 1.6.1 | `ConsoleAppender`'s `withJansi` probes JLine's `AnsiConsole` first | No: no config sets `withJansi` |
| 1.6.1 | `LayoutWrappingEncoder` reports an error at `start()` without a layout | No: both bundled encoders have a pattern; no ERROR status |
| 1.6.1 | `FileCollisionAnalyser` checks `SiftingAppender`'s nested appenders | No `SiftingAppender` |
| 1.6.2 | Configuration-time warning for caller-data converters under `includeCallerData=false` | No: the bundled status says "No contradictions in caller extraction instruction were detected" |
| 1.6.2 | `SimpleSocketServer` needs a client whitelist | Not used |
| 1.6.3 | `MDCBasedDiscriminator` strips `/` and `\` — **the CVE-2026-19880 fix** | No `SiftingAppender`, no MDC; the fix is why the version is 1.6.3 |
| 1.6.3 | `JansiConsoleAppender` split out; `withJansi` deprecated (still works) | No config sets it. The CLI installs `org.fusesource.jansi.AnsiConsole` itself (`AimonCli.java:56`); its `CONSOLE` appender is never started |
| 1.6.3 | `ConsoleAppender` no longer closes `System.out`/`System.err` when stopped | The CLI's `CONSOLE` is never started. Modules configured by Logback's defaults use a console appender, and their tests passed at 1.6.3, `System.setOut` capture in six CLI test classes included (§2.4) |
| 1.6.3 | `SimpleInvocationGate` renamed `FixedIntervalInvocationGate` | Referenced nowhere, Boot included |

**The bundled configuration, loaded from the built distribution.** `LogbackStatusProbe.java` puts `dist-<v>/`'s jars on
the class path, as the start script does, with `-Duser.home` set to a scratch directory. It logs one line and prints every
status Logback recorded:

| distribution | `lib/` | worst status | the WARN | differences from 1.5.13 |
|---|---|---|---|---|
| base (1.5.13) | `logback-{classic,core}-1.5.13.jar`, `slf4j-api-2.0.18.jar`, `jansi-2.4.3.jar` | WARN | "Appender named [CONSOLE] not referenced. Skipping further processing." | — |
| 1.5.38 | the pair at 1.5.38 | WARN | the same | informational lines only |
| **1.6.3** | **the pair at 1.6.3**, `slf4j-api-2.0.18.jar`, `jansi-2.4.3.jar` | **WARN** | **the same** | informational lines only, plus the caller-data INFO |

No ERROR at any version. The FILE appender resolves to `<scratch>/.aimon/logs/aimon.log` and writes there. The fat jar
built at 1.6.3 has `version=1.6.3` in both `META-INF/maven/ch.qos.logback/*/pom.properties`. That WARN makes Logback print
its whole status block to the console on start-up, at every version, including 1.5.13 (§8, F-7).

### 2.7 The Gradle API

- `javap -v` on `gradle-core-api-9.2.1.jar` shows that `Configuration.shouldResolveConsistentlyWith(Configuration)` and
  `disableConsistentResolution()` carry `RuntimeVisibleAnnotations: org.gradle.api.Incubating`.
- `VersionCatalogsExtension` and `VersionCatalog` (same jar) carry **no** `@Incubating`. They are what
  `@Suppress("UnstableApiUsage")` sits on, at `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:15` and
  `aimon.spring-starter.gradle.kts:13`. That suppression is the "`buildSrc` already uses unstable API" #111's design cited
  (§3.1, open question 1).
- Gradle's current javadoc (9.7.1) still marks both methods `@Incubating`, "Since: 6.8".
- Gradle's feature-lifecycle page (as fetched 2026-09-11) says an incubating feature "may change in future Gradle versions
  until it is no longer incubating", that changes are highlighted in release notes, and that there is no deprecation
  cycle. A public feature is never removed or intentionally changed without deprecation. **The build re-reads the page
  before quoting it in a committed comment.**
- The wrapper has been 9.2.1 since the initial commit (`eec9ccd`). 16 Dependabot PRs exist, and none touched the wrapper.
- Every project is configured on every invocation: `org.gradle.configureondemand` is set in neither `gradle.properties`
  nor `~/.gradle/gradle.properties`. A Kotlin DSL compile error in `:aimon-cli`'s script therefore fails every build.

### 2.8 The JUnit floor, run

`junit-floor-noexclude.init.gradle` adds `testRuntimeOnly(enforcedPlatform("org.junit:junit-bom:5.12.2"))` to
`:aimon-core` and prints the test task's JUnit and ArchUnit jars:

- `dependencyInsight` under it gives `junit-jupiter-api` **5.12.2** and `junit-platform-launcher` **1.12.2** ("By
  constraint / Forced").
- The run's classpath holds `junit-jupiter`, `-api`, `-engine` and `-params` at 5.12.2; `junit-platform-commons`,
  `-engine` and `-launcher` at 1.12.2. Beside them are `junit-4.13.2.jar` and `archunit-junit5-engine-1.5.0.jar`.
- `./gradlew -I junit-floor-noexclude.init.gradle :aimon-core:test --tests
  'at.aimon.core.memory.StoreBackedPeerMemoryContractTest' --rerun` — BUILD SUCCESSFUL, **21 tests, 0 failures,
  0 errors, 0 skipped**. The six nested suites are capability negotiation 4, CHAT 1, INGEST 2, OBSERVE 3, SEARCH 7 and
  SNAPSHOT 4. The XML timestamps are from that run.
- A variant that also excluded `archunit-junit5-engine` gave the same 21 / 0, so **the exclusion is not needed**, and the
  build uses the simpler script. A control run without any init script also gave 21 / 0.
- `StoreBackedPeerMemoryContractTest` is the suite's only subclass in the tree (`git grep AbstractPeerMemoryContractTest`).

### 2.9 D-2's jars (`javap -v` over every class file)

| jar | class files (excl. `module-info`) | annotation types (RUNTIME retention) | enums | other | packages |
|---|---|---|---|---|---|
| `jakarta.annotation-api` 1.3.5 (shipped by `aimon-knowledge-opensearch`) | 15 | 14 (13; the other is `Generated`) | 1 | 0 | `javax.annotation`, `.security`, `.sql` |
| `jakarta.annotation-api` 2.1.1 (under test) | 17 | **16 (15; the other is `Generated`)** | 1 | 0 | `jakarta.annotation`, `.security`, `.sql` |
| `jakarta.xml.bind-api` 4.0.4 / 4.0.5 | 114 / 114, of which 6 `package-info` | 30 (30) | 3 | 81, 6 of them `package-info` | — |

The last row reconciles with D-2's existing "108 classes, 78 not annotations": 114 − 6 `package-info` = 108, and 108 − 30
= 78 with the enums counted as code. That bullet stays.

On `c561e17` the whole-build runtime-vs-testRuntime comparison gives **18** differences, identical to #111's after table:
the six accepted annotation rows, D-2's four entries, and the memory testkit's seven JUnit entries.

### 2.10 Lines other records cite

- `docs/design/llm/provider-key-release-gate.md:46` cites `aimon.java-conventions.gradle.kts:128-133` and `:116`, and
  both are accurate today. Any line inserted or deleted above them makes that record wrong, so the conventions plugin is
  edited **at its end only**.
- Other records cite that file at `:7`, `:16`, `:21`, `:25`, `:77-79`, `:120` and `:149`, and the catalog at `:57` and
  `:91`. All were already stale before this change (§8, F-6).
- No record cites any of these by a line this change moves:
  - `modules/aimon-cli/build.gradle.kts` below `:17`;
  - `ModelCapabilityBindingProbe*.java`, which `test-classpath-shipped-versions.md` cites only at older lines, in its
    approved body;
  - `module-dependency-scope.md`.

---

## 3. Decisions and the alternatives rejected

Each subsection is one heading in the PR body: the option taken, the options rejected, and why.

### 3.1 Decision 1 — #114: **raise the catalog's `logback` to 1.6.3**

**Taken.** `gradle/libs.versions.toml` gets `logback = "1.6.3"`, with a note beside it. The note says which advisories
bound the choice, why the line is 1.6.x, where to look for the next advisory, and the floor relative to Spring Boot's
Logback (§4.1(c)).

**Why raise rather than keep and record.** 1.5.13 is inside six advisory ranges (§2.1). Three of them are CVE-2025-11226,
CVE-2026-13006 and CVE-2026-19880. The measured reason to stay is true: no Janino anywhere, no Spring Framework on the CLI,
no `SiftingAppender` in the bundled config, no MDC in any source. But it describes the **classpath and the
configuration**, and a later dependency can change the first, while a user who supplies their own `logback.xml` changes the
second. Janino is even an optional dependency of `logback-core` itself. Neither change touches the Logback line, and nothing
would notice either. A shipped version inside advisory ranges, justified by preconditions nothing checks, is the record #114
exists to replace. Against that, raising is small and measured:
- no published classpath moves;
- the unit gate and the starter's docker tier are green on it;
- the bundled configuration loads with the same status (§2.3, §2.4, §2.6).

**Why 1.6.3.** It is the only released version outside every range NVD lists (§2.1).

- **Rejected — 1.5.19**, the version #114 names. It is inside CVE-2026-13006, which NVD describes as circumventing exactly
  1.5.19's fix, inside CVE-2026-19880, and inside three LOW advisories.
- **Rejected — 1.5.34**, Spring Boot 3.5.16's version. It would be the cheapest move: the build's tests already run on it,
  and the bump would move only 13 entries on two unpublished projects and no docker tier (§2.3). But it is inside
  CVE-2026-13006 and CVE-2026-19880.
- **Rejected — 1.5.37 or 1.5.38**, the newest on the 1.5.x line. 1.5.38 is also the newest Logback any Spring Boot line
  manages (4.0.8, 4.1.1). Both are inside **CVE-2026-19880**, which has **no fix on 1.5.x**: Logback released the fix as 1.6.3
  and calls 1.5.x legacy. Keeping 1.5.38 would need CVE-2026-19880 named and accepted. The acceptable-sounding reason exists
  and is true — no `SiftingAppender` in the bundled config, no MDC in AIMON code. But it is a precondition argument, the one
  this decision refuses for 1.5.13, and it would rest on a configuration the user can replace. The price of going past 1.5.x
  is not higher: 1.5.38 and 1.6.3 move the same 39 entries and pass the same gate (§2.3, §2.4). This is the stated fallback
  if the maintainer will not take a Logback line no Spring Boot line manages (§9, Q1).
- **Rejected — 1.6.0, 1.6.1, 1.6.2.** All are inside CVE-2026-19880.
- **Rejected — wait for Spring Boot to manage 1.6.x.** No Spring Boot line does yet, and meanwhile the distribution stays
  inside every range above.
- **Rejected — move `spring-boot` to get a newer Logback.** 3.5.16 is the newest 3.5.x and manages 1.5.34. The 4.x lines
  manage 1.5.38, which is still inside CVE-2026-19880, and a Boot major is ruled out by `dependabot.yml`'s D6 note.
  It would also move far more than Logback.

**Why the line change is safe here — measured, not assumed from "drop-in replacement".**
- Nothing this repository references was removed in 1.6.0.
- The bundled configuration touches none of 1.6.x's behaviour changes and loads with the same single WARN and no ERROR.
- The whole unit gate and the starter's docker tier pass on it (§2.4, §2.6).

**What it changes that a user can see.**
- The distribution's `lib/` and the fat jar carry the 1.6.3 pair; `slf4j-api` stays 2.0.18.
- A user who **replaces** the bundled `logback.xml` takes Logback's 1.5.37–1.6.3 changes with it:
  - no Janino conditionals (inert already without Janino, which the distribution has never carried);
  - the deprecated API 1.6.0 removed, such as `ReconfigureOnChangeFilter`;
  - `withJansi` deprecated in favour of `JansiConsoleAppender`;
  - compressed rollover keeps the source file when compression fails;
  - `SiftingAppender`'s MDC discriminator strips slashes.

  The `CHANGELOG.md` entry names these and points at Logback's release notes.

**What it changes in the build.**
- Ten projects' test classpaths move from 1.5.34 to 1.6.3, because they name the catalog entry at test scope. None of them
  ships Logback, so this adds no shipped-vs-test difference.
- It does mean two things. The build's tests run on two Logback lines: 1.6.3 where a module names the catalog entry, and
  1.5.34 where only `spring-boot-starter-test` brings it. And the starter's tests now run a Logback that no Spring Boot line
  manages (§8, F-5). That second fact reaches no user, for three reasons:
  - the starter does not bring Logback, and its applications take Boot's;
  - no test here starts Boot's logging system — every starter test, the docker one included, uses
    `ApplicationContextRunner`;
  - Boot 3.5.16's logging integration links against 1.6.3 without a missing member (§2.6).
- `aimon-session-testkit`'s D-2 row disappears, and not because it was aligned: the catalog now asks for more than the test
  starter brings. A later Spring Boot patch that manages a newer Logback than the catalog would recreate it. That is written
  as a D-2 trigger and in the catalog note.

### 3.2 Decision 2 — #120 item 2: **an `@Incubating` API is acceptable in a module build script, on stated terms; the call stays**

**Taken.**

1. `modules/aimon-cli/build.gradle.kts` keeps `shouldResolveConsistentlyWith`. Its comment is corrected, because the test
   starter no longer raises Logback there, and it gains what #111's left out:
   - the Gradle versions the status was checked on;
   - that a removal fails every build, while a change in behaviour can pass without failing anything;
   - what the silent case would move (§2.5);
   - the one command that shows it.
2. `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` gains, **at its end** (§2.10), the terms on which any
   module build script may call incubating Gradle API (§4.3).
3. Backlog D-3 gains a Gradle upgrade as a trigger, because D-3's check is the thing that would notice the silent case.

**This is not a re-litigation of #111; four facts are new.**

- #111's precedent does not exist. `@Suppress("UnstableApiUsage")` in `buildSrc` sits on `VersionCatalogsExtension`,
  which has no `@Incubating` in 9.2.1 (§2.7). As far as this design found, the CLI's call is the build's only incubating
  one.
- #111's "if a Gradle upgrade changes it, this build script stops compiling — loud" is half of the truth. A removal or a
  signature change is loud, and louder than #111 said: every project is configured on every invocation, so every build
  fails (§2.7). A change in *what it does* is allowed by Gradle's lifecycle without deprecation. It might fail resolution
  loudly, and it might equally pass: nothing in the build would fail if the constraints simply stopped applying.
- After Decision 1 Logback no longer needs the block at all, because 1.6.3 outranks the test starter's 1.5.34 on its own.
  What the block still holds in place — so the most the silent case can undo — is three entries on `aimon-cli`:
  `jakarta.xml.bind-api` 4.0.4 on both test classpaths, and `snakeyaml` 2.7 on `testCompileClasspath` (§2.5).
- The API has been `@Incubating` since 6.8 and still is in 9.7.1 (§2.7).

**Why acceptable.**

- **No stable API does the job at a cost this build accepts.** Strict constraints, the alternative #111 rejected, would
  now pin only §2.5's three entries. Those are Quartz's `jakarta.xml.bind-api`, the version whose drift #111 rejected them
  for, and `snakeyaml` on the compile classpath. Replacing now would keep exactly the rejected half. The other stable routes
  are no better (below).
- **The loud case fails the whole build at configuration**, in CI as locally, before any task runs.
- **The silent case's reach is bounded and written beside the call**, with the command that detects it. The upgrade that
  could bring it is an explicit wrapper edit, and D-3 is where a mechanical check is already tracked.

**Rejected — not acceptable; replace with strict constraints.** See above. Also, their `because` strings would name
versions that a Quartz bump moves out from under them.

**Rejected — not acceptable; hand-roll the derivation from stable pieces** (resolve `runtimeClasspath` inside the test
classpaths' `withDependencies` and add strict constraints). Each call is stable, but the pattern is one Gradle documents
nowhere and has been restricting: resolving one configuration while another is being resolved, and mutating constraints
from that hook (mutation after observation). It re-implements the incubating feature with no stronger promise and more
code to keep right.

**Rejected — resolve `runtimeClasspath` at configuration time and write literal constraints.** Every invocation, `format`
included, would resolve the CLI's graph while configuring. And a Quartz or catalog bump would still move only one side
until the next configuration.

**Rejected — dependency locking.** Locks follow a lockfile, not what the distribution resolves. Every bump needs
`--write-locks`, and a lock state cannot be shared between `runtimeClasspath` and a test classpath.

**Rejected — remove the block.** Logback matches without it, but §2.5's entries would differ again, undoing #99's
Decision 1 for them. A Spring Boot patch managing Logback past the catalog would also silently re-open the pair.

**Rejected — accept, and add a CLI-local guard** (a task `:aimon-cli:test` depends on that fails when a test classpath
resolves a shipped module at another version). It would close the silent case today, and it is the fallback if the
maintainer wants that. But it is D-3's check scoped to one module, built ahead of D-3's open decisions:
- where the accepted list lives;
- whether the compile axis counts;
- that the check is a task the conventions plugin registers and `checkAll` collects.

D-3 would later have to absorb or delete the guard. The trigger added to D-3 makes the connection instead.

**Rejected — also remove the stale `@Suppress("UnstableApiUsage")` in this change.** Deleting the line at
`aimon.java-conventions.gradle.kts:15` shifts `:116` and `:128-133`, which another record cites accurately (§2.10). The two
IDE markers change no build behaviour, and #120's question is about module build scripts. The correction to #111's
precedent is recorded where the precedent was claimed, in §11.5 of its design, and the markers are a finding (§8, F-1).

### 3.3 Decision 3 — #120 item 4: **run the contract suite once on 5.12.2, by a command-line init script**

**Taken.** The build copies `junit-floor-noexclude.init.gradle` into `$RUN_DIR/build/` (never committed) and runs it with
the command in §2.8. It records the command, the printed classpath, the `dependencyInsight` lines and the counts in
`$RUN_DIR/build/measurements.md`. The `junit` note replaces "Not verified: no run executes the suite on exactly 5.12.2."
with three things (§4.1(b)):
- the outcome;
- the essence of the override, so a reader can repeat it without `$RUN_DIR`;
- the fact that nothing repeats it.

**Why run rather than say it is not exercised.** It costs one init script and 15 seconds (§2.8), and it answers the one
half of the floor claim nothing covered. The *compile* half is already exercised on every build: the testkit's own
`compileClasspath` resolves the suite against 5.12.2. What had never happened is the suite running there.

**Why this subject.** `StoreBackedPeerMemoryContractTest` is the suite's only subclass in the tree; the testkit has no test
sources of its own.

**Rejected — a permanent second test task** (a `junitFloorTest`). It puts a second JUnit into every gate run to guard a
claim that only a JUnit bump or an edit to the suite can break. Its natural home would be either
`modules/aimon-core/build.gradle.kts`, which another run in this batch owns, or a cross-project test-classes wiring in a
module with no tests. The issue asks for once; the note says when to repeat it.

**Rejected — also force `aimon-core`'s `testCompileClasspath` to 5.12.2.** It would recompile all of `aimon-core`'s tests at
the floor to answer a question about the suite, whose own compile at the floor is already the testkit's.

**Rejected — exclude `archunit-junit5-engine` from the run.** Measured unnecessary (§2.8), and excluding it would make the
run's classpath differ from the build's for no reason.

### 3.4 Decision 4 — #120 item 1: **D-2 rewritten to what the build resolves; D-2 and D-3 stay open**

**Rows.** Re-measured on `c561e17`, two rows are unchanged: `aimon-scheduling-quartz` `jakarta.xml.bind-api` 4.0.4 → 4.0.5,
and `aimon-knowledge-opensearch` `jakarta.annotation-api` 1.3.5 → 2.1.1. `aimon-session-testkit`'s Logback row leaves the
table with a dated note. It says the row went because of #114 — outranked, not aligned — and when it comes back (§4.5).

**Sentence.** "주석 jar 가 아니다" is false for `jakarta.annotation-api`, which has 16 annotation types and 1 enum (§2.9).
What sets the two apart from #99's accepted sources is how they reach a test run. `jakarta.xml.bind-api` carries code.
`jakarta.annotation-api` carries RUNTIME-retention annotations that frameworks read while running (15 of its 16), with
the package swapped from `javax` to `jakarta` between 1.3.5 and 2.1.1.

**D-2 stays open.** Its *무엇* — decide per module, align or accept — is unmet for both remaining modules. This change
touches neither build script, and neither is in this run's files.

**D-3 stays open.** Its *무엇* — whether to add a check — is not decided here. §3.2 deliberately leaves the check to D-3
and adds a Gradle upgrade to its triggers.

### 3.5 Decision 5 — #120 item 3: **`requireDistinguishable` driven end to end through the seam**

**Why no real key reaches it.** `ModelCapabilityDeclaration.equals` compares every field, so two distinct values of a key
never give equal declarations. The type is core's: `final`, with a private constructor, so no declaration with the defect
can stand in. Today's test calls the helper directly (`ModelCapabilityBindingProbeTest.java:225-234`), so deleting the call
at `ModelCapabilityBindingProbe.java:195` leaves the build green. That is a prediction; §7.2 measures it.

**Taken.** `refusesAPairEqualsCannotTellApart` builds a probe over `FakeSurface` whose expectation step answers **both**
values with the first value's real declaration: `(key, value) -> DeclarableKeys.expectedDeclaration(key, pair.get(0))`.
That is what a declaration whose `equals` skipped the key gives for any two of its values. The test calls the public
`assertValuesReachTheDeclaration` and asserts three things:
- the equality refusal's sentence;
- both values in that sentence;
- the absence of the round trip's "was written on" sentence, which is the message a missing check would produce instead.

`requireDistinguishable` becomes `private static`, because its only caller outside the class was the test being replaced.
#111 made the same move for `refusedProbeValue`. Test count stays 44.

**Rejected — "say in the test why the helper is the only reachable seam".** It is not the only one, so the sentence would
be false and the call site unguarded.

**Rejected — a hook on core's `equals`, or a static mock.** The first widens a type in a module #120 does not touch. The
second introduces the repository's first `mockStatic`, when this module already has a seam.

### 3.6 Decision 6 — bookkeeping

- **`CHANGELOG.md`**: one new `###` section at the top of `[Unreleased]`, in the house pattern (§4.8). It names the shipped
  change, what a user who replaces the bundled configuration takes with it, and what else moves. It says it supersedes
  #99's "so its tests now run on Logback 1.5.13". #99's bullet is not edited: it was true when written.
- **`docs/design/testing/test-classpath-shipped-versions.md`**: `### 11.5 Later departures` appended after §11.4 (§4.6).
- **`docs/design/llm/model-capability-binding-round-trip.md`: no note.** Its body describes the equality check as a probe
  behaviour (§2.4 there) and never says how the refusal is tested or what the helper's visibility is, so nothing it states
  changes. The follow-up was #111's F-3, recorded in the other document, and that is where its closure goes.
- **This document is committed as a design record**, `docs/design/testing/shipped-logback-and-test-classpath-followups.md` (§4.10), in the same commit as the change. Every
  design-record commit on `main` does the same: `test-classpath-shipped-versions.md` for #99,
  `provider-key-release-gate.md` for #98, `max-tokens-truncation-reporting.md` for #108. TASK.md, amended after #122 was
  decided, now describes exactly that practice. The body is this document as approved, byte-exact. A header says it is
  implemented and by what, and `## 13. After the build — departures and corrections` carries what the build changed or
  found. Its index row goes in §2 of `docs/design/README.md`; §3 of that file is not touched.
  - **Why commit it.** Each decision already has a durable home for its *conclusion*: the catalog note (Decisions 1, 3),
    the conventions note and the CLI comment (Decision 2), D-2 and D-3 (Decision 4), the test's comment (Decision 5), and
    `CHANGELOG.md`. None has room for what the next reader of that conclusion needs: the advisory search and why GitHub
    and OSV cannot be trusted for it (§2.1), the 1.6.x change table (§2.6), and the rejected versions with their costs
    (§3.1). #114 itself is the precedent — it was found as F-1 in #111's committed record.
  - **Why `testing/`.** `docs/design/README.md` defines that axis as the floor a module's tests stand on: the classpaths
    the build hands them, and the testkits. Decision 1 moves those classpaths on twelve projects and came out of #111's
    comparison, whose record is already there; Decisions 2–5 are that record's follow-ups.
  - **Rejected — commit no record.** The first revision chose this, for a reason that no longer holds: TASK.md then put
    `docs/design/README.md` off-limits. On the durable-homes argument alone it would still leave §2.1, §2.6 and §3.1 in a
    run directory no later reader can open.
- **Backlog**: no item registered; D-4 and D-5 stay unused. The findings in §8 are either fixed-in-place records, not this
  register's subject, or for the maintainer.
- **Title and index row**, recounted from the body after the edit: D-1, D-2 and D-3 are all 열림, giving `등록 항목 3건
  (열림 3 · 결정 대기)` and `| 3 | 3 | 0 | 0 |` — the values they already have. The index row's 출처 column is unchanged,
  because no item was added.
- **Not translated**: `docs/design/`, `docs/backlog/` and `CHANGELOG.md` have no twins.

---

## 4. Concrete changes, by file

Drafts are the substance. The build may tighten wording, not facts, and every number in a committed sentence comes from the
build's own after-measurement.

### 4.1 `gradle/libs.versions.toml`

**(a) The `spring-boot-starter-test` bullet (`:35-41`):**

```toml
# - spring-boot-starter-test, which aimon.java-conventions gives every module's tests: ALIGNED on aimon-cli, whose
#   runtimeClasspath is its distribution. It raised jakarta.xml.bind-api (4.0.4 -> 4.0.5) there and, until `logback`
#   below moved past it (#114), logback-classic and logback-core (1.5.13 -> 1.5.34); aimon-cli/build.gradle.kts resolves
#   both test classpaths consistently with runtimeClasspath, and says why. NOT DECIDED on two other modules, where the
#   same source raises jakarta.xml.bind-api (4.0.4 -> 4.0.5) on aimon-scheduling-quartz and jakarta.annotation-api
#   (1.3.5 -> 2.1.1) on aimon-knowledge-opensearch: backlog D-2, docs/backlog/module-dependency-scope.md. It raised the
#   Logback pair on aimon-session-testkit as well, until #114.
```

**(b) The last sentence of the `junit` note (`:86`)**, replacing "Not verified: …":

```toml
# Run once on exactly 5.12.2 (#120, <date>): aimon-core's StoreBackedPeerMemoryContractTest, the suite's one in-tree
# subclass, with every Jupiter jar at 5.12.2 and every Platform jar at 1.12.2 on that run's classpath — <N> tests, 0 failed,
# 0 skipped. An init script added `testRuntimeOnly(enforcedPlatform("org.junit:junit-bom:5.12.2"))` to :aimon-core for
# `:aimon-core:test --tests 'at.aimon.core.memory.StoreBackedPeerMemoryContractTest' --rerun`. Nothing in the build
# repeats it; repeat it when this number or the suite changes.
```

**(c) `# Logging`:**

```toml
slf4j = "2.0.18"
# The Logback aimon-cli ships — the one use of this entry that ships; every other module names it at test scope,
# or, for the unpublished aimon-session-testkit, on a classpath that only ever joins a consumer's tests. 1.6.3 (#114), the
# one release past every Logback CVE NVD lists on 2026-09-11. No 1.5.x is: CVE-2026-19880 (logback-classic up to 1.6.2, a
# path traversal through SiftingAppender's MDC discriminator) is fixed only in 1.6.3, and CVE-2026-13006 (logback-core up to
# 1.5.36) circumvents the 1.5.19 fix for CVE-2025-11226, the advisory #114 was filed for. None of the three is reachable
# from this build today — no Janino on any classpath, no SiftingAppender in the bundled logback.xml, no MDC in any source
# (measured) — but a dependency or a user's own logback.xml can change each of those without touching this line, so that
# was not a reason to stay. GitHub's advisory database and OSV listed neither of the two newest: look for the next one in
# NVD and on logback.qos.ch/news.html, not only by id.
# 1.6.x is Logback's stable line and 1.5.x its legacy one. Spring Boot manages 1.5.x (3.5.16 resolves 1.5.34), so this entry
# is ahead of `spring-boot` and wins on every test classpath that names it. Keep it at or above Boot's: below it,
# spring-boot-starter-test raises this entry there, and aimon-session-testkit's shipped and tested versions part again
# (backlog D-2). logback-classic 1.6.3 builds against slf4j 2.0.18, the version above; logback-core follows logback-classic.
logback = "1.6.3"
```

### 4.2 `modules/aimon-cli/build.gradle.kts` (comment only; `:47-65`)

```kotlin
// The CLI's tests run on the versions the CLI ships (#99). aimon.java-conventions gives every module
// spring-boot-starter-test, which asks for newer versions of jars the distribution carries — Quartz's
// jakarta.xml.bind-api (4.0.4 shipped, 4.0.5 asked for) and, until the catalog's Logback moved past it (#114), the Logback
// pair (1.5.13 shipped, 1.5.34 asked for) — so the Logback these tests asserted on was not the Logback `tasks.jar` below
// packs.
//
// Consistent resolution rather than naming them: …   (paragraph unchanged)
//
// The same strictness has a quiet side. …   (paragraph unchanged)
//
// Test classpaths only: …   (paragraph unchanged, ending before the @Incubating sentence)
//
// `shouldResolveConsistentlyWith` is @Incubating: since Gradle 6.8, still so in 9.2.1 (javap) and in 9.7.1's javadoc. It
// is called here on the terms at the end of aimon.java-conventions.gradle.kts (#120). Removed or re-signed, this script
// stops compiling, and so does every build. Changed in what it does, it may fail nothing at all: these tests would then run
// on jakarta.xml.bind-api 4.0.5 and compile against snakeyaml 2.5 again (measured 2026-09-11), and
//     ./gradlew -q :aimon-cli:dependencyInsight --configuration testRuntimeClasspath --dependency jakarta.xml.bind-api
// would stop answering 4.0.4 "by consistent resolution" — run it after a Gradle upgrade until backlog D-3's check exists.
// The record of every remaining test-classpath difference, and why each is accepted, is next to `junit` in
// gradle/libs.versions.toml.
```

The `configurations { }` block itself is unchanged. The build confirms the `dependencyInsight` wording at 1.6.3 before
quoting it.

### 4.3 `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` (appended after the `dependencies { }` block)

```kotlin
// Incubating Gradle API in a module build script (#120). Gradle promises it nothing across releases: it "may change in
// future Gradle versions until it is no longer incubating", without the deprecation a public API gets
// (docs.gradle.org/current/userguide/feature_lifecycle.html). A module build script may still call one when no stable API
// does the same job at a cost this build accepts, and when the comment beside the call says what the call holds in place,
// on which Gradle version its status was checked, and what an upgrade that changes it can break.
//
// That last part has two halves, and only one is sure to be loud. Removed or re-signed, the call stops its script
// compiling, and since every project is configured on every invocation, every build fails before a task runs. Changed in
// what it does, it can pass without failing anything — so the comment has to name the command that shows whether the call
// still holds.
//
// aimon-cli's `shouldResolveConsistentlyWith` is called on these terms, and its comment is the worked example.
```

Nothing above the appended block moves (§2.10). `@Suppress("UnstableApiUsage")` at `:15` stays (§3.2).

### 4.4 `modules/aimon-llm-capability-testkit`

`src/main/java/.../ModelCapabilityBindingProbe.java:147`: `static void requireDistinguishable(` → `private static void
requireDistinguishable(`. It stays on the same line, at 108 characters after the edit (under `LineLength` 120), so the
formatter has no reason to rewrap it; nothing else changes.

`src/test/java/.../ModelCapabilityBindingProbeTest.java:225-234` — `refusesAPairEqualsCannotTellApart` becomes, in substance:

```java
@Test
@DisplayName("a pair whose declarations compare equal is refused, naming equals as the reason")
void refusesAPairEqualsCannotTellApart() {
    // Driven through the public pair check, with the one step no real key can make fail substituted.
    // ModelCapabilityDeclaration.equals compares every field, so no two values of a real key give equal declarations,
    // and the type is core's, final, with a private constructor, so no declaration with the defect can stand in. The
    // substitute answers both values with the first value's real declaration — what a declaration whose equals skipped
    // the key gives for any two of its values — so what is pinned is the check the pair goes through, not only the
    // sentence the helper words.
    final List<Object> pair = ProbeValues.distinctPairFor("supportsSamplingParameters");
    final ModelCapabilityBindingProbe<FakeSurface> equalsSkipsTheKey = ModelCapabilityBindingProbe
            .forSurface(FakeSurface::new).forwarding(ModelCapabilityBindingProbeTest::copiesAll)
            .operatorKeyPath(key -> "fake." + key).forwardingLocation(FORWARDING)
            .expectedDeclaration((key, value) -> DeclarableKeys.expectedDeclaration(key, pair.get(0))).build();

    assertThatThrownBy(() -> equalsSkipsTheKey.assertValuesReachTheDeclaration("supportsSamplingParameters", pair))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("equals does not compare `supportsSamplingParameters`")
            .hasMessageContaining("declaring it as " + pair.get(0) + " and as " + pair.get(1))
            .hasMessageNotContaining("was written on");
}
```

Why that fails without the call: `requireUsablePair` would pass, the round trip for `true` would compare equal, and the one
for `false` would fail with the "was written on …" description. So the first assertion fails, and the last one names why.

### 4.5 `docs/backlog/module-dependency-scope.md`

- **§2 intro**, one sentence after `:105-106` ("측정은 전부 2026-09-11, `main` `9b642cc` 에서 …"): D-2 의 표는 #114·#120 에서
  `c561e17` 과 그 변경 뒤에 다시 쟀고, 그 결정과 측정은 `docs/design/testing/shipped-logback-and-test-classpath-followups.md` 에 있다 (the build writes the path
  as a relative link from the register).
- **D-2 `무엇`**: "아래 세 모듈" → "아래 두 모듈".
- **D-2 table**: the `aimon-session-testkit` row removed.
- **D-2 `:121`**, replacing the false sentence:

  > 둘 다 #99 가 `aimon-cli` 에서 맞춘 것과 **같은 출처**다. #99 가 받아들인 두 출처와 이 둘을 가르는 것은 jar 의 종류가
  > 아니라 — `jakarta.annotation-api` 도 주석 jar 다 — 테스트 실행에 닿는 방식이다. `jakarta.xml.bind-api` 는 코드를 담고,
  > `jakarta.annotation-api` 는 프레임워크가 실행 중에 읽는 RUNTIME-retention 주석을 담으며 1.3.5 와 2.1.1 사이에 패키지가
  > `javax` 에서 `jakarta` 로 바뀐다.

- **The `jakarta.annotation-api` bullet** gains the measured count: 2.1.1 의 주석 타입 16개 가운데 15개가 RUNTIME-retention
  이다 (`@PostConstruct` · `@PreDestroy` · `@Resource` 처럼 컨테이너가 실행 중에 읽는 것들). The rest of the bullet stays.
- **The `aimon-session-testkit` bullet** becomes a dated note:

  > `aimon-session-testkit` 의 Logback 쌍(1.5.13 → 1.5.34)은 이 표에서 빠졌다(#114, 2026-09-11). 맞춘 것이 아니다 —
  > 카탈로그 `logback` 이 1.6.3 으로 올라 `spring-boot-starter-test` 가 가져오는 1.5.34 보다 높아졌고, 이제 두 클래스패스가
  > 모두 1.6.3 을 해석한다(`dependencyInsight`). 이 모듈의 빌드 스크립트는 그대로이므로, Spring Boot 가 카탈로그보다 높은
  > Logback 을 관리하게 되면 차이는 다시 생긴다.

- **D-2 `어디`**: the session testkit's build script removed from the list; the date becomes `(2026-09-11, c561e17)`.
- **D-2 `언제 다시 볼까`**:
  - "이 세 빌드 스크립트" → "이 두 빌드 스크립트";
  - the D-3 trigger's "그 검사는 이 셋을 목록에 올리거나 없애라고" → "이 둘을" (`:140`);
  - a new trigger: `spring-boot` 올림이 카탈로그 `logback` 보다 높은 Logback 을 가져올 때 — `aimon-session-testkit` 의 쌍이
    이 표로 돌아온다.
- **D-3 `언제 다시 볼까`**, a new trigger:

  > Gradle 을 올릴 때 — `aimon-cli` 가 테스트 클래스패스를 맞추는 `shouldResolveConsistentlyWith` 는 `@Incubating` 이다
  > (#120). 없어지면 모든 빌드가 설정 단계에서 멈춘다. 동작만 바뀌면 아무것도 실패하지 않은 채 그 모듈의 테스트가 다시
  > `spring-boot-starter-test` 가 올린 버전 위에서 돌 수 있다. 그것을 알아챌 것이 이 검사다.

- **H1 and the `docs/backlog/README.md` index row**: recounted from the body, with unchanged values (§3.6). `README.md` is
  touched only if the recount disagrees.

### 4.6 `docs/design/testing/test-classpath-shipped-versions.md` — append after §11.4

```markdown
### 11.5 Later departures

*Appended 2026-09-11 for #114 and #120, whose design is
[`shipped-logback-and-test-classpath-followups.md`](shipped-logback-and-test-classpath-followups.md). Everything above is
left as written; this records where it stopped matching the tree.*

- **§3.1's "raise what ships", F-1 and open question 3 — the catalog's `logback` moved** (#114), to 1.6.3. §3.1 rejected
  raising it *to equalise test numbers*, and that rejection still holds as a reason. What moved it is the reason F-1 left
  for the maintainer, and it turned out wider than F-1 said. 1.5.34, the version the rejected option named, is inside
  CVE-2026-13006 (logback-core ≤ 1.5.36). Every 1.5.x is inside CVE-2026-19880 (logback-classic ≤ 1.6.2), fixed only on
  1.6.x. Consistent resolution carried the CLI's tests with the bump, as §3.1 said it would. `aimon-session-testkit`'s pair
  left D-2 (B-2) — outranked, not aligned.
- **§3.1's "Cost accepted", §6 row 4 and open question 1.** Two supporting sentences were wrong.
  - "`buildSrc` already uses unstable API": `@Suppress("UnstableApiUsage")` sits on `VersionCatalogsExtension`, which has
    no `@Incubating` in Gradle 9.2.1 (javap).
  - "If a Gradle upgrade changes it, this build script stops compiling": true of a removal, which fails every build since
    every project is configured. A change in behaviour, on the other hand, can pass without failing anything.

  #120 decided the question with both halves written down: acceptable, on terms now at the end of
  `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts`. Backlog D-3 lists a Gradle upgrade as a trigger.
- **F-3 → done** (#120). `refusesAPairEqualsCannotTellApart` drives `requireDistinguishable` through the
  `expectedDeclaration` seam §3.5 added, and the helper is private.
- **Open question 5 → run once.** The suite ran on exactly JUnit 5.12.2 (<N> tests, 0 failures). The `junit` note says so,
  how, and that nothing repeats it.
```

No anchor in the repository targets `#11-…` in this file (#111's §4.5 checked; the build re-checks with
`check-doc-links.py`).

### 4.7 `docs/design/llm/model-capability-binding-round-trip.md`

Unchanged (§3.6).

### 4.8 `CHANGELOG.md` — a new section at the top of `[Unreleased]`

```markdown
### CLI: the distribution ships Logback 1.6.3, past every Logback CVE NVD lists

- **`aimon-cli` now ships `logback-classic` and `logback-core` 1.6.3 instead of 1.5.13** (#114). The CLI is the one module
  that ships the catalog's Logback. 1.5.13 was inside CVE-2025-11226 (GHSA-25qh-j22f-pwp8, fixed in 1.5.19),
  CVE-2026-13006 (logback-core up to 1.5.36, fixed in 1.5.37) and CVE-2026-19880 (logback-classic up to 1.6.2, fixed only
  in 1.6.3), and three LOW advisories fixed by 1.5.34. None of those three named advisories is reachable from the CLI as it
  ships: no Janino on its class path, no `SiftingAppender` in the bundled `logback.xml`, no MDC set by AIMON code
  (measured). The version moves anyway, because a later dependency or a user's own configuration can change each of those.
  Logback describes 1.6.x as its stable line and, apart from Janino conditionals, a drop-in replacement for 1.5.x.
  CVE-2026-19880 is the one with no fix on 1.5.x, and neither it nor CVE-2026-13006 has a GitHub advisory yet.
  `slf4j-api` stays 2.0.18. The distribution's `lib/` and the fat jar carry the new pair, and the bundled `logback.xml`
  loads with the same status as before.

- **If you replace the bundled `logback.xml`**, Logback's changes since 1.5.13 apply to your file: Janino-based `<if>`
  conditionals are gone (1.5.37; they already needed Janino, which the distribution has never carried); 1.6.0 removed
  deprecated API such as `ReconfigureOnChangeFilter`; 1.6.3 deprecates `ConsoleAppender`'s `withJansi` in favour of
  `JansiConsoleAppender` and strips slashes from `SiftingAppender`'s MDC discriminator values. Logback's release notes list
  the rest.

- **What else moves: test classpaths only.** The CLI's tests move with its distribution (#99's consistent resolution).
  Ten other projects name the catalog's Logback at test scope, where 1.6.3 now wins over the 1.5.34
  `spring-boot-starter-test` brings. No published module's POM or module metadata changes, because none declares Logback
  outside test scope. `aimon-session-testkit`'s shipped and tested Logback, 1.5.13 against 1.5.34, are now the same, so
  backlog D-2 drops that row. **This supersedes the #99 entry's "so its tests now run on Logback 1.5.13" below.**

- **Four records #99 left, settled** (#120). Nothing a user or consumer resolves changes.
  - `shouldResolveConsistentlyWith`, the `@Incubating` Gradle API that holds the CLI's test classpaths to what it ships,
    stays. `aimon.java-conventions.gradle.kts` now says when a module build script may call incubating API. The CLI's
    comment says what an upgrade that changes it can break: a removal fails every build, while a change in behaviour can
    pass without failing anything. It also names the command that shows the second. Backlog D-3 lists a Gradle upgrade as a
    trigger.
  - `aimon-memory-testkit`'s contract suite was run once on exactly JUnit 5.12.2, the floor it publishes (<N> tests,
    0 failures); the `junit` note records how.
  - `ModelCapabilityBindingProbeTest` drives the equality refusal through the probe's public pair check, and
    `requireDistinguishable` is private.
  - D-2 called its differences "not annotation jars", but `jakarta.annotation-api` is one. It now names what does set them
    apart: code, and runtime-read annotations whose package moved from `javax` to `jakarta`.

- **Records.** The design is `docs/design/testing/shipped-logback-and-test-classpath-followups.md`, and §11.5 of
  `docs/design/testing/test-classpath-shipped-versions.md` records where #99's design stopped matching the tree.
```

### 4.9 Deliberately not changed

| file | why not |
|---|---|
| `modules/aimon-session-testkit/build.gradle.kts` | Its difference goes away through the catalog (§3.1); nothing in the script is about the version |
| `modules/aimon-memory-testkit/build.gradle.kts` | Decision 3 needs no build change, and its comment says nothing about the floor being run |
| `@Suppress("UnstableApiUsage")` in both `buildSrc` plugins | §3.2, last rejection |
| `modules/aimon-core/build.gradle.kts` | Owned by another run; the floor run is an init script |
| `aimon-scheduling-quartz` / `aimon-knowledge-opensearch` build scripts | D-2 stays open; not this run's files |
| `modules/aimon-cli/src/main/resources/logback.xml` | Loads unchanged on 1.6.3 (§2.6); `modules/aimon-cli/src/**` is another run's (§8, F-7) |
| `samples/aimon-sample-app/build.gradle.kts` | Takes Boot's Logback on purpose and names no catalog entry (§8, F-8) |
| `spring-boot`, `slf4j`, `junit` catalog values | §3.1, §3.3 |
| #99's `CHANGELOG.md` bullet | True when written; the new section supersedes it |
| `docs/design/llm/model-capability-binding-round-trip.md` | §3.6 |
| `docs/design/README.md` §3 | The `design-record-exemption` run owns it (TASK.md) |

### 4.10 This document, committed as a design record

- **`docs/design/testing/shipped-logback-and-test-classpath-followups.md`** (new). This document, byte-exact as approved at the final design review round, with two additions and
  nothing else — the house pattern of `test-classpath-shipped-versions.md`, `provider-key-release-gate.md` and
  `max-tokens-truncation-reporting.md`:
  - **A header block at the top**:
    - `Status: **IMPLEMENTED** —` followed by what implements it (the catalog entry and notes, the CLI comment, the
      conventions note, the probe and its test, D-2 and D-3, §11.5 of #99's record, the `CHANGELOG.md` section);
    - the sources, #114 and #120;
    - ~~a sentence saying that everything between the header and §13 is the body as approved in review round <n>, kept
      byte-exact rather than corrected;~~ *Correction mark ([§14.3](#143-the-placeholder-at-410)): the struck text
      reads "review round" and then `<n>`, a placeholder outside code that the site renders as nothing. The round was 3.*
    - a sentence saying that the run records it cites (`$RUN_DIR/…`, `review-*.md`) are not in the repository.
  - **`## 13. After the build — departures and corrections`**, appended after the build from `build/deviations.md`, the
    build review and what was measured.

  The body carries no markdown link outside a code block, so moving it into `docs/design/testing/` breaks nothing. The
  header's links are written relative to that directory.
- **`docs/design/README.md`**: one row in §2's `testing` table, after `test-classpath-shipped-versions.md`'s, in that
  table's form:

  ```markdown
  | [`testing/shipped-logback-and-test-classpath-followups.md`](testing/shipped-logback-and-test-classpath-followups.md) | CLI 배포본이 싣는 Logback 을 1.5.13 에서 1.6.3 으로 올린 결정 — 1.5.x 의 어느 버전도 아닌 이유(CVE-2026-19880 은 1.6.3 에서만 고쳐졌다), 권고를 id 로 찾지 않고 검색하는 이유, 모듈 빌드 스크립트가 `@Incubating` Gradle API 를 부르는 조건과 조용히 지나갈 수 있는 절반, 메모리 계약 스위트를 JUnit 바닥에서 한 번 돌린 기록, #111 이 남긴 기록 넷의 정리 |
  ```

  No record cites a line of that README, so the inserted row moves no citation. §3 of the README is not touched.

---

## 5. Data and interface shapes that change

| shape | before | after |
|---|---|---|
| catalog `logback` | 1.5.13 | **1.6.3** |
| `:aimon-cli` `compile`/`runtimeClasspath` (the distribution) | `logback-classic`, `logback-core` 1.5.13 | 1.6.3 |
| `:aimon-cli` `testCompile`/`testRuntimeClasspath` | 1.5.13 (by consistent resolution) | 1.6.3 |
| `:aimon-session-testkit` `compile`/`runtimeClasspath` | the pair 1.5.13; `slf4j-api` 2.0.15 on compile | the pair 1.6.3; `slf4j-api` 2.0.18 on compile |
| `testRuntimeClasspath` of 10 projects (plus `aimon-core`'s `testCompileClasspath` and the session testkit's test classpaths) | the pair 1.5.34 | 1.6.3 |
| every other classpath entry (5,221 total) | — | unchanged |
| published POMs / module metadata | — | unchanged (the build checks) |
| runtime-vs-testRuntime differences, whole build | 18 | 16 |
| `ModelCapabilityBindingProbe.requireDistinguishable` | package-private static | private static |
| backlog | D-2: 3 rows | D-2: 2 rows + dated note; D-3: one more trigger; counts unchanged |

`aimon-cli`, `aimon-session-testkit` and `aimon-llm-capability-testkit` are unpublished. No public API, wire format,
config key or persisted name changes. What a user who replaces the bundled `logback.xml` sees is §3.1's list.

---

## 6. Failure modes and how they are handled

| # | failure | handling |
|---|---|---|
| 1 | Logback 1.6.3 breaks a test on a moved module — a `ListAppender` assertion in `aimon-core`, the CLI or a session backend; `System.out` capture beside a console appender | Unit gate measured green at 1.6.3 (§2.4), as at 1.5.38. The build re-runs `checkAll` on the real edit **and** the `integrationTest`/`playwrightTest` tiers of every moved module that has one (§7.2) |
| 2 | 1.6.0's removed API breaks code that links against Logback | Nothing here references a removed member (§2.2); Spring Boot 3.5.16's logging integration resolves all 158 references on 1.6.3 (§2.6) |
| 3 | The starter's tests run a Logback no Spring Boot line manages | No test here starts Boot's logging system (every starter test uses `ApplicationContextRunner`); the starter ships no Logback; its applications take Boot's. Recorded in the catalog note and §8, F-5 |
| 4 | A split pair (classic ≠ core) — the `NoSuchMethodError` recorded at `samples/aimon-sample-app/build.gradle.kts:49-53` | Every moved row moves both (§2.3); the after-dump must show both at 1.6.3 on every classpath that has either |
| 5 | `slf4j-api` incompatibility | 2.0.18 before and after on the CLI; Logback 1.6.3 declares 2.0.18. The build quotes `dependencyInsight` |
| 6 | The shipped `logback.xml` behaves differently on 1.6.3 | Loaded from the built distribution: same single WARN (unreferenced `CONSOLE`), no ERROR, same appenders (§2.6). The CLI's tests load it from main resources (#111's C-9) and ran 470 / 0 at 1.6.3 |
| 7 | A user's own `logback.xml` uses something 1.5.37–1.6.3 removed or deprecated | Named in `CHANGELOG.md` with a pointer to Logback's notes (§4.8) |
| 8 | A new Logback advisory covers 1.6.3 before or after the merge | §7.1.4 searches NVD and the news page at build time; the catalog note says where to look, and that GitHub and OSV missed CVE-2026-13006 and CVE-2026-19880 |
| 9 | A later Spring Boot patch manages a newer Logback than the catalog | Session testkit's D-2 row returns; written as a D-2 trigger and in the catalog note |
| 10 | Dependabot's `production-patches` group later bumps `logback` on the 1.6.x line (patch-level, auto-merged on green) | Consistent resolution carries the CLI's tests; the note names advisories and the line, not "newest" as a standing claim (§8, F-2) |
| 11 | Gradle changes what `shouldResolveConsistentlyWith` does | It may fail resolution, or pass silently; the silent case is written beside the call with its reach (§2.5) and the command, and is a D-3 trigger. Accepted, not handled — §3.2 |
| 12 | Gradle removes or re-signs it | Every build fails at configuration (§2.7) |
| 13 | The floor run is misattributed — the suite actually ran on the build's newer JUnit | The init script prints the run's JUnit jars; `dependencyInsight` under the same script; the XML timestamps; `measurements.md` keeps all three |
| 14 | The floor run's results are overwritten or left stale for `checkAll` | Run it before `checkAll`; `aimon-core:test`'s classpath input differs, so the gate re-executes it anyway |
| 15 | The new probe test pins the wrong path (passes without the call) | Asserts the equality sentence **and** the absence of the round trip's; teeth run (a) in §7.2 |
| 16 | The committed record quotes a number from design rather than the build | Every committed number comes from the build's after-measurement; placeholders `<N>` and `<date>` above exist to force that, and §4.2's §2.5 figures are re-measured by §7.2.5 before they are committed |
| 17 | The edit to `aimon.java-conventions.gradle.kts` shifts a line another record cites accurately | Appended at the end only (§2.10); the build diffs the file and confirms `:116` and `:128-133` unchanged |
| 18 | Backlog checker: title or index row disagree with the body | Recount from the body; `check-backlog-registers.py` |
| 19 | Merge conflicts with sibling runs in `[Unreleased]` or the backlog index | Expected by TASK.md; resolved at merge by recounting from the body |
| 20 | `aimon-core`'s `test` re-runs because its inputs include every module build script and the `buildSrc` scripts (`PublishedModuleApiScopeTest`, `PublishedModuleLoggingBindingTest`) | Expected cost, part of `checkAll`; no dependency declaration changes, so neither guard's subject changes |
| 21 | The committed record is edited toward what was built, or its index row or header links break | The body is `diff`ed against the approved `design.md` (§7.2.10); departures go only in §13; `check-doc-links.py` covers the header and the README row |
| 22 | A committed sentence states a fact the design's own tables contradict (round 2's finding) | Before committing, the build reads each drafted security sentence in §4.1(c), §4.6 and §4.8 against §2.1's table: which CVE is fixed where, and which has a GitHub advisory |

---

## 7. Test strategy

The build keeps its init scripts and probe programs in `$RUN_DIR/build/` (copies of the design's, never committed) and
records every command and result in `$RUN_DIR/build/measurements.md`.

### 7.1 Before the first edit

1. **`dependencyInsight`** on these configurations and dependencies:
   - `:aimon-cli` × `runtimeClasspath`, `testCompileClasspath`, `testRuntimeClasspath` × `logback-core`,
     `logback-classic`, `org.slf4j:slf4j-api`, `jakarta.xml.bind-api`;
   - `:aimon-session-testkit` × `runtimeClasspath`, `testRuntimeClasspath` × `logback-core`;
   - `:aimon-spring-boot-starter` × `testRuntimeClasspath` × `logback-core`;
   - D-2's rows on both classpaths: `:aimon-scheduling-quartz` × `jakarta.xml.bind-api`, and
     `:aimon-knowledge-opensearch` × `jakarta.annotation:jakarta.annotation-api`.
2. **Whole-build dump** (`classpath-dump.init.gradle`) → `dump-before/`. Confirm 5,221 entries and 18 runtime-vs-testRuntime
   differences.
3. **Published metadata**: `generatePomFileForMavenPublication generateMetadataFileForMavenPublication` on every module
   that applies `aimon.publishable`, with the SHA-256 of each file.
4. **Search for advisories rather than looking up the ones already known**, and stop and report if any covers the version
   about to be committed:
   - NVD `keywordSearch=logback` — every CVE, with its affected ranges for `logback-core` and `logback-classic`;
   - logback's news page — every release newer than the chosen version, and the series notes (which line is stable, which
     is legacy);
   - Maven Central's newest `logback-core` and `logback-classic`;
   - GitHub's advisory database (both artifacts) and OSV (`/v1/query` at the chosen version, and `/v1/vulns/<id>` for
     each CVE the NVD search found) for completeness. Both were measured to miss CVE-2026-13006 and CVE-2026-19880 (OSV
     answers 404 for both ids), so an empty answer from either proves nothing by itself.

   Also re-read the Gradle feature-lifecycle wording before quoting it, and — if the register quotes them — the D-2 jar
   counts (`javap -v` over every class of `jakarta.annotation-api` 1.3.5 and 2.1.1, retention read from each type's
   `@Retention`).

### 7.2 After the edits

1. **The same `dependencyInsight` table.** Expected:
   - the pair at 1.6.3 on all four `aimon-cli` classpaths, the test ones by consistent resolution;
   - `slf4j-api` 2.0.18;
   - `jakarta.xml.bind-api` 4.0.4 on the CLI's test runtime;
   - the session testkit's pair at 1.6.3 on both classpaths;
   - D-2's two rows unchanged.
2. **Dump → `dump-after/`, diff against `dump-before/`.** Expected: exactly the 39 entries of §2.3 (with `slf4j-api`
   2.0.15 → 2.0.18 on the session testkit's compile classpath), 0 unresolved, and 18 → 16 differences. Anything else that
   moved is a finding to explain before going on.
3. **Publications**: every POM and module-metadata checksum identical to 7.1.3.
4. **The distribution**: `./gradlew :aimon-cli:installDist :aimon-cli:jar`.
   - `build/install/aimon-cli/lib/` lists `logback-classic-1.6.3.jar`, `logback-core-1.6.3.jar` and `slf4j-api-2.0.18.jar`,
     and no `janino`.
   - The fat jar's `META-INF/maven/ch.qos.logback/*/pom.properties` say 1.6.3.
   - `LogbackStatusProbe.java` over that `lib/` reports worst status WARN, the unreferenced `CONSOLE` only, and no ERROR.

   The design's run on the emulation gave exactly that (§2.6).
5. **The silent case, measured on the real edit**: `consistent-off.init.gradle` plus the dump script give the entries that
   differ from `dump-after/` on `aimon-cli`'s test classpaths. Only those go into the CLI comment (§4.2). Prediction: the
   three entries of §2.5.
6. **`./gradlew format`, then `./gradlew checkAll --continue`**, reporting per-module executed test counts, failures, errors
   and skips from the XML — not "green". Prediction from §2.4: 21 modules, 10,946 tests, 0 failures, 72 skipped; `aimon-cli`
   470; `aimon-spring-boot-starter` 261.
7. **The tiers `checkAll` does not run, on every moved module that has one.** Docker 29.2.1 and the Playwright browsers are
   present on this machine. The tasks:
   - `:aimon-core:integrationTest`;
   - `:aimon-session-mongodb:integrationTest`, `:aimon-session-postgres:integrationTest`, `:aimon-session-redis:integrationTest`;
   - `:aimon-session-routing:integrationTest`;
   - `:aimon-spring-boot-starter:integrationTest` (design at 1.6.3: 2 / 0);
   - `:aimon-browser-playwright:playwrightTest`.

   Report counts per task. A tier that cannot run is reported as not run, not as verified. `aimon-cli` has no docker,
   playwright or packaging class, and `aimon-sample-app`'s packaging test does not take the catalog's Logback, so its
   classpath does not move.
8. **The JUnit floor** (Decision 3): the insight lines, the printed classpath and the counts. `<N>` and `<date>` in the note
   and CHANGELOG come from this run.
9. **The probe test's teeth** (`:aimon-llm-capability-testkit:test --rerun`, not committed):
   1. With the change, delete the `requireDistinguishable(...)` call in `requireUsablePair`. Expect 44 tests, **1 failure**:
      the new test.
   2. On the original code and original test, make the same deletion. Expect 44 tests, **0 failures** — the defect this
      fixes.
   3. Unmutated: 44 / 0.

   Restore, and show that `git diff` is only the intended change. Then run
   `:aimon-llm-capability-testkit:jacocoTestCoverageVerification` (floor 86) and quote the ratio.
10. **Four doc checks**: `check-doc-links.py`, `check-backlog-registers.py`, `check-translation-staleness.py`,
    `check-translation-structure.py`. Then `diff` the committed `docs/design/testing/shipped-logback-and-test-classpath-followups.md`, without its header and §13, against the
    approved `$RUN_DIR/design/design.md`: no difference.
11. **Residue**:
    - `git grep -n "Not verified: no run executes"` → nothing;
    - `git grep -n "주석 jar 가 아니다\|이 셋을\|이 세 빌드"` in the register → nothing;
    - `git grep -n '1\.5\.13'` over the owned files → only the approved body of `test-classpath-shipped-versions.md`, the new
      §11.5, the history the catalog and CLI comments name, and older `CHANGELOG.md` entries;
    - `aimon.java-conventions.gradle.kts` lines 1–252 byte-identical to `c561e17`.

---

## 8. Findings outside the two issues (for `build/deviations.md`)

- **F-1 — the two `@Suppress("UnstableApiUsage")` in `buildSrc` suppress nothing.** `VersionCatalogsExtension` and
  `VersionCatalog` carry no `@Incubating` in 9.2.1. The markers are harmless and are not removed here (§3.2). This is
  recorded in §11.5 of #111's design, because #111 cited them as precedent.
- **F-2 — Dependabot never proposed a Logback bump.** 1.5.13 → 1.5.38 was patch-level. `dependabot.yml`'s
  `production-patches` group takes such updates and `dependabot-auto-merge.yml` merges them on green, yet none of the 16
  Dependabot PRs touched Logback, while the other catalog refs were bumped. Why was not measured.
- **F-3 — nothing in the build reports a shipped dependency inside a published advisory range.** #114 was found by reading.
  This is not this register's subject (POM scope and test-classpath versions), and no reserved ID fits. For the maintainer.
- **F-4 — two advisory sources miss Logback's two newest CVEs.** GitHub's advisory database has no entry for CVE-2026-13006
  or CVE-2026-19880. OSV has no record of either: both ids answer 404, and it returns no vulnerability for Logback 1.5.34,
  1.5.36 or 1.5.38 (queried 2026-09-11; NVD status *Deferred* for both). A scanner that reads either would call 1.5.34–1.6.2 clean. The first design round of this run made
  that mistake itself.
- **F-5 — after Decision 1 the build's tests run on two Logback lines.** Where a module names the catalog entry they run
  1.6.3; where only `spring-boot-starter-test` brings it they run 1.5.34. The starter's own tests pair 1.6.3 with Spring
  Boot 3.5.16, which manages neither. This is not a shipped-vs-test difference, and no test here starts Boot's logging
  system; it is recorded so nobody reads it as either.
- **F-6 — stale line citations predate this change.** Records citing `aimon.java-conventions.gradle.kts` lines already
  stale: `architecture-review-open-items.md:114,393,561`, `roadmap.md:69`, `spring-boot-starter-open-items.md:1358`,
  `integration-test-layers.md:240`, `reasoning-effort-config-surface.md:969`. Citing the catalog:
  `spring-boot-starter-open-items.md:903`, `openai-model-capabilities.md:433`. None is touched.
- **F-7 — the bundled `logback.xml` makes Logback print its whole configuration status on start-up.** Its `CONSOLE`
  appender is defined and never referenced, which Logback reports as a WARN. A WARN during auto-configuration makes Logback
  print every status line to the console: about 35 lines at 1.5.13, 1.5.38 and 1.6.3 alike. This was measured with
  `LogbackStatusProbe` over the distribution's `lib/`, not by launching the REPL. It predates this change, is unchanged by
  it, and `modules/aimon-cli/src/**` belongs to another run in this batch.
- **F-8 — `samples/aimon-sample-app` packs Spring Boot's Logback 1.5.34**, inside CVE-2026-13006 and CVE-2026-19880. Its
  build script takes Boot's logging stack on purpose, and it names no catalog entry, so #114's catalog change does not reach
  it. Even Spring Boot 4.1.1's 1.5.38 is inside CVE-2026-19880. The sample is unpublished. For the maintainer.

---

## 9. Open questions

1. **Is shipping a Logback line no Spring Boot line manages (1.6.x) acceptable?** Chosen: yes. It is the only line with every
   fix, Logback calls it stable and 1.5.x legacy, and it was measured here (§2.4, §2.6). The fallback is 1.5.38, with
   CVE-2026-19880 named in the catalog note and CHANGELOG and accepted because the bundled configuration has no
   `SiftingAppender` and AIMON code sets no MDC (§3.1).
2. **Should the silent case of the incubating API be closed now** with the CLI-local guard, rather than left to D-3? Chosen:
   left to D-3 (§3.2). The guard is the stated fallback.
3. **Should `CHANGELOG.md` name CVE-2026-13006 and CVE-2026-19880** when GitHub's database has no advisory for either?
   Chosen: yes, with NVD as the source.
4. **Is the end of `aimon.java-conventions.gradle.kts` the right place for the convention note,** rather than `CLAUDE.md`'s
   *Build Setup* or `.claude/rules/architecture.md` (neither is in this run's files)? Chosen: the plugin, which `CLAUDE.md`
   names as where cross-cutting build configuration lives.
5. **Commit and PR shape.** Suggested subject: `fix(cli): ship Logback 1.6.3 and settle #111's four follow-ups (#114, #120)`.
   The orchestrator may split the Logback change and the #120 records into two commits.
6. **F-8 — does the sample app's Boot-managed Logback need a decision?** Not in #114's scope; for the maintainer.
7. **Is `docs/design/testing/` the right home for this record?** Chosen: yes (§3.6). It is the axis the README defines as
   the classpaths a module's tests run on, and it sits beside #111's record; no other axis covers the build.

---

## 10. Closing

Both issues are fully addressed, so the PR body carries:

```
Closes #114
Closes #120
```

The PR body has:
- one heading per decision (§3.1–§3.6), with taken / rejected / why;
- the before and after `dependencyInsight` tables and the dump summary;
- the advisory search;
- the gate and tier counts;
- the distribution status probe;
- the floor run and the teeth runs;
- §8;
- the committed record and its index row (§4.10).

**Merge notes:** none expected. The conventions plugin is appended at its end, and no record cites a line of the other
owned files that this change moves (§2.10). The build re-checks after its last edit and says so either way.

---

## 11. Probes, for the build to copy

| file in `$RUN_DIR/design/probe/` | what it does |
|---|---|
| `classpath-dump.init.gradle` | `dumpClasspaths` task per project: every external module on the four classpaths, plus unresolved dependencies |
| `logback-bump-emulation.init.gradle` | `-Daimon.logbackTo=<v>`: resolves every `ch.qos.logback` request at 1.5.13 as `<v>` (design only; the build uses the real edit) |
| `consistent-off.init.gradle` | `disableConsistentResolution()` on `aimon-cli`'s two test classpaths — the silent case |
| `junit-floor-noexclude.init.gradle` | The Decision 3 run's override, printing the run's JUnit/ArchUnit jars |
| `LogbackStatusProbe.java` | Loads a distribution `lib/`, logs once, prints Logback's status list and the worst level |
| `LinkageProbe.java` | Resolves each referenced Logback member (name + descriptor, inherited included) on a class path; fed from `javap -v` over Spring Boot's `org.springframework.boot.logging.logback` classes |

---

## 12. Review rounds — what changed

### Round 1

**Blocking — Decision 1 missed CVE-2026-19880. Accepted and fixed.** The finding was verified before anything was
rewritten:
- NVD's record: `logback-classic` 0.9.14–1.6.2, 6.3 MEDIUM, 1.6.3 unaffected;
- Logback's news: 1.6.3 released "in relation to CVE-2026-19880", and 1.5.x legacy;
- GitHub's database has no entry, and OSV has no record for 1.5.38 either.

An NVD keyword search then confirmed it is the only Logback CVE the first round missed, and that none covers 1.6.3. The
decision was taken again on new measurements at 1.6.3:
- whole-build dump;
- unit gate;
- the starter's docker tier;
- the silent case;
- the built distribution's `lib/`, fat jar and Logback status at 1.5.13, 1.5.38 and 1.6.3;
- the 1.6.0 removed-API list against this repository and against Spring Boot 3.5.16's logging integration.

It now takes 1.6.3, with 1.5.38-plus-accepted-CVE as the stated fallback (§3.1, §9 Q1). The five sentences the review
named are corrected:
- "no advisory newer than 2023" (§2.1);
- "smallest version past every known advisory";
- "every advisory fix is on 1.5.x" (§3.1);
- the catalog note, which no longer says "stay on 1.5.x" (§4.1(c));
- the CHANGELOG draft (§4.8) and the §11.5 draft (§4.6).

§7.1.4 now searches for advisories instead of looking up known ids. One more error, found while re-checking: the first
round called CVE-2026-13006 "not scored", and NVD scores it 7.0 HIGH (§2.1).

**Non-blocking, all taken.**
- The `junit` note says "every Jupiter jar … every Platform jar" (§4.1(b)), because the run's classpath also carries JUnit 4
  and the ArchUnit engine (§2.8).
- D-2's D-3 trigger "이 셋을" is corrected with the rest (§4.5), and the residue grep covers it (§7.2.11).
- "Changed in what it does, nothing fails" is now "can pass without failing anything" everywhere (§0, §3.2, §4.2, §4.3,
  §4.5, §4.6, §4.8, §6).
- §3.6 states that no design record of this run is committed, and why.

**Renumbered.** §2.6 is new (Logback 1.6.x against this repository). The old §2.6–§2.9 are now §2.7–§2.10, and every
cross-reference follows. §11 (probe index) is new.

No rebuttal was needed: every finding was checked and held.

### Round 2

**Blocking — the CHANGELOG draft said "the last two CVEs have no fix on 1.5.x". Accepted and fixed.** That is false for
CVE-2026-13006, which is fixed in 1.5.37, as §2.1 and the same bullet said. The sentence now says CVE-2026-19880 is the one
with no 1.5.x fix, and that neither it nor CVE-2026-13006 has a GitHub advisory (§4.8). Every other place that states
where the two are fixed was re-read against §2.1's table: §1, §3.1, the catalog note (§4.1(c)), the §11.5 draft (§4.6)
and §9 already said it correctly. §6 row 22 makes that re-read a step for the build.

**Found while fixing — "OSV misses both" was half measured.** The first round's OSV query was at 1.5.38, which
CVE-2026-13006 does not cover, so only the CVE-2026-19880 half had been shown. It is now measured: `/v1/vulns/<id>`
answers 404 for both ids, and `/v1/query` finds nothing at 1.5.34 or 1.5.36 either (§2.1, §7.1.4, §8 F-4). §6 row 8 no
longer says "the last two".

**Non-blocking, all taken.**
- **The design record.** TASK.md was amended after #122 was decided and no longer puts `docs/design/README.md`
  off-limits. The round-1 reason for committing no record was therefore stale, and reconsidered on `main`'s practice the
  decision changes: this document is committed as `docs/design/testing/shipped-logback-and-test-classpath-followups.md`
  with its index row (§3.6, §4.10). Round 1's line "§3.6 states that no design record of this run is committed" is
  superseded, and §4.9's row goes with it.
- **The catalog note** says "the one use of this entry that ships" instead of "the catalog's one `implementation` use"
  (§4.1(c)): `aimon-session-testkit` also declares `implementation`.
- **The `junit` note** quotes the filter the build actually runs, the fully qualified class name (§4.1(b)).
- **The CHANGELOG draft** attributes "a drop-in replacement for 1.5.x" to Logback, so the next bullet's removed API does
  not read as contradicting it (§4.8).

No rebuttal was needed: every finding held.

---

## 13. After the build — departures and corrections

*Appended after the build. Everything above is the approved body, byte-exact. This section records where that body is
wrong, where the build departed from it, and what the build measured. The run's own records are not in the repository,
so the numbers that matter are reproduced here.*

### 13.1 Where the body is wrong

- **C-1 — §3.5, §6 row 15 and §7.2.9: the teeth mutation does not isolate the defect.** §7.2.9 deletes "the
  `requireDistinguishable(...)` call in `requireUsablePair`", but that statement also carries both
  `acceptedDeclaration(...)` calls, which hold the value-refusal catch. So §3.5's "deleting the call at
  `ModelCapabilityBindingProbe.java:195` leaves the build green" is false, and so are §7.2.9's first two predictions.
  Measured with `:aimon-llm-capability-testkit:test --rerun`:
  - the statement deleted, with this change: **44 tests, 3 failures** — the new test,
    `refusesAValueTheDeclarationRefuses` and `blamesDeclaresAnythingWhenTheBuilderCallsTheValueEmpty`;
  - the statement deleted, on `c561e17`: **44 / 2** — the last two.

  The mutation that isolates the defect keeps both `acceptedDeclaration` calls and drops only the comparison:
  - on `c561e17`: **44 / 0** — the defect #120 item 3 names;
  - with this change: **44 / 1** — the new test, failing on "`supportsSamplingParameters` = false was written on …", as
    §4.4 traces.

  Decision 5 and the committed test stand; only the stated mutation and its predictions were wrong. §6 row 15's "teeth
  run (a)" means §7.2.9's run 1. Design review round 3 found this and measured the same numbers.
- **C-2 — §4.10's "review round `<n>`" is not in code**, so the site renders the placeholder as an empty element and the
  sentence reads "approved in review round , kept byte-exact". It is kept as approved; the header above says round 3.
- **C-3 — §4.2's "(paragraph unchanged)" is wrong about the comment's second paragraph.** That paragraph began
  "Consistent resolution rather than naming the three", and the new first paragraph no longer counts three jars.
  Committed: "naming them".
- **C-4 — §7.2.11's residue grep misses two strings §4.5 edits**, "아래 세 모듈" and "셋 다". The build grepped for both;
  neither remains.
- **C-5 — §4.10's planned header has no "What this work left open" line**, which all three records it names as precedent
  carry. The header above has one.
- **C-6 — §7.2.7 lists two tiers that have no test.** `:aimon-core:integrationTest` and
  `:aimon-session-routing:integrationTest` are named as tiers of moved modules, but neither module has a
  `@Tag("docker")` class: the only occurrences of the string are javadoc, in `ReleaseGateMatchesCiGateTest` and
  `HolderLossSweeperTest`. Both tasks executed, passed and ran no test (13.3). Found by the build.

### 13.2 Where the build departed from the body

- **B-1 — `ModelCapabilityBindingProbe.Builder.expectedDeclaration`'s javadoc changed**, where §4.4 changes only the
  helper's modifier in that file. The javadoc said the only other answer worth giving is "the one a builder with a
  defect gives", and that this module's test uses the seam "to drive that refusal". A second test now uses the seam, for
  a defect in `equals`, so it reads: "the one a declaration with a defect gives — a builder whose `declaresAnything()`
  skips the key, an `equals` that does — … this module's own tests use it to drive those refusals". Javadoc only;
  `requireDistinguishable` stays on its line.
- **B-2 — the conventions note does not say Gradle promises an incubating API nothing.** Re-read before quoting (§2.7),
  the lifecycle page also says a change to an incubating feature "will be highlighted in the release notes for that
  release". The committed note keeps §4.3's quoted clause and says such a change is highlighted in the release notes
  rather than deprecated first, as a public API's would be.
- **B-3 — the CLI comment's silent-case sentence names all three entries §2.5 lists**: "these tests would then compile
  and run against jakarta.xml.bind-api 4.0.5, and compile against snakeyaml 2.5, again". §4.2's draft named the JAXB API
  on the runtime side only.
- **B-4 — two sentences of §4.8's `CHANGELOG.md` draft are tightened to what was measured.**
  - "What else moves: test classpaths only" became "nothing published". `aimon-session-testkit`'s `compileClasspath` and
    `runtimeClasspath` move too — the pair 1.5.13 → 1.6.3, and `slf4j-api` 2.0.15 → 2.0.18 on compile, as §2.3 lists.
    The module is unpublished and its main classpaths only join a consumer's tests, which the committed bullet says.
  - "loads with the same status as before" became "the same one warning as before (its unreferenced `CONSOLE` appender)
    and no error". The status lists differ in INFO lines, 32 at 1.5.13 and 30 at 1.6.3; the worst level and the one WARN
    are the same, which is what §2.6 measured.
- **B-5 — the base distribution's Logback status was re-probed from the design phase's copy of `c561e17`'s `lib/`**, not
  from a fresh `installDist` at 1.5.13, because the catalog was already edited by then. The 1.6.3 distribution was built
  fresh.
- **B-6 — the drafts of §4.1–§4.3, §4.6 and §4.8 are reflowed** to the 120-column width of the files they went into. The
  wording is as drafted except where B-2, B-3 and B-4 say.

### 13.3 What was measured

**Advisories, re-searched before the first edit.** NVD `keywordSearch=logback` still returns 17 CVEs. The Logback ones
since 2024 are §2.1's eight; none is newer than CVE-2026-19880, and none covers 1.6.3. Maven Central's newest
`logback-classic` and `logback-core` are 1.6.3, and the 1.5.x line ends at 1.5.38. GitHub's advisory database still has
no entry for CVE-2026-13006 or CVE-2026-19880, and OSV answers "Vulnerability not found" for both ids.

**`dependencyInsight`.** On `aimon-cli`, `logback-classic` and `logback-core` went from 1.5.13 to **1.6.3** on
`runtimeClasspath`, `testCompileClasspath` and `testRuntimeClasspath`; the test classpaths give "by consistent
resolution" before and after. `slf4j-api` stays 2.0.18 and `jakarta.xml.bind-api` 4.0.4 on all three.
`aimon-session-testkit`'s `logback-core` went 1.5.13 → 1.6.3 on `runtimeClasspath` and 1.5.34 → 1.6.3 on
`testRuntimeClasspath`; the starter's `testRuntimeClasspath`, 1.5.34 → 1.6.3. D-2's two rows are unchanged.

**The whole build.** 29 projects × 4 classpaths: 5,221 entries before and after, none unresolved, and **39 moved on 12
projects** — exactly §2.3's list — with the pair moving together on every row. No published module's `compileClasspath`
or `runtimeClasspath` moved, and the POM and module metadata of all 21 published modules (42 files) are SHA-256
identical. The runtime-vs-testRuntime differences went **18 → 16**: the session testkit's pair went, and nothing
appeared. No classpath carries Janino.

**The silent case**, on the real edit. Switching consistent resolution off on `aimon-cli`'s test classpaths changes
exactly §2.5's three entries — `jakarta.xml.bind-api` 4.0.4 → 4.0.5 on both, `snakeyaml` 2.7 → 2.5 on
`testCompileClasspath` — and `dependencyInsight` then answers 4.0.5 "By conflict resolution".

**The distribution.** `lib/` carries `logback-classic-1.6.3.jar`, `logback-core-1.6.3.jar`, `slf4j-api-2.0.18.jar` and
`jansi-2.4.3.jar`, and no Janino; the fat jar's two `pom.properties` say 1.6.3. Loading the bundled `logback.xml` from
that `lib/` gives worst status WARN — the unreferenced `CONSOLE` appender only — and no ERROR, as at 1.5.13 (B-4).

**The JUnit floor.** An init script added `testRuntimeOnly(enforcedPlatform("org.junit:junit-bom:5.12.2"))` to
`:aimon-core`. Under it, `dependencyInsight` gives `junit-jupiter-api` 5.12.2 and `junit-platform-launcher` 1.12.2 ("By
constraint", "Forced"), and the run's classpath holds every Jupiter jar at 5.12.2 and every Platform jar at 1.12.2,
beside `junit-4.13.2` and the ArchUnit engine. `:aimon-core:test --tests
'at.aimon.core.memory.StoreBackedPeerMemoryContractTest' --rerun` ran **21 tests, 0 failures, 0 errors, 0 skipped**.

**The probe test.** Unmutated, 44 / 0 on this change and on `c561e17`; the mutations are C-1's. Line coverage of
`aimon-llm-capability-testkit` after the change is 199 of 226 (88.05%), against its floor of 86.

**The gate.** `./gradlew format` changed nothing. `./gradlew checkAll --continue`, run with every module's unit-test
results deleted first so that no test task could be up-to-date: BUILD SUCCESSFUL in 3m 9s, **21 modules, 10,946 tests, 0
failures, 0 errors, 72 skipped** — §2.4's prediction, module for module (`aimon-cli` 470, `aimon-spring-boot-starter`
261, `aimon-core` 8,168 with 2 skipped). The other 70 skips are in `aimon-llm-anthropic`, `aimon-llm-openai` and the two
sandbox modules, whose classpaths did not move.

**The tiers `checkAll` does not run**, one task per invocation, each with `--rerun`:
`:aimon-session-mongodb:integrationTest` 72 tests, `:aimon-session-postgres:integrationTest` 81,
`:aimon-session-redis:integrationTest` 78, `:aimon-spring-boot-starter:integrationTest` 2
(`AimonClusterIntegrationTest`) and `:aimon-browser-playwright:playwrightTest` 4 — BUILD SUCCESSFUL, with 0 failures, 0
errors and 0 skipped in every one. `:aimon-core:integrationTest` and `:aimon-session-routing:integrationTest` passed
too, but ran no test, so they verify nothing about this change (C-6).

**Documents.** The four checks pass: `check-doc-links.py` (248 files, 2,487 relative links, 0 broken, the header's link
to this section included), `check-backlog-registers.py` (every register's title and index row agree with its items; this
one still counts 3 items, 3 open), and `check-translation-staleness.py` and `check-translation-structure.py` (32
translations, level and structurally identical). `mkdocs build --strict` exits 0. With the header and this section
removed, the body above is byte-identical to the approved design. Lines 1–252 of `aimon.java-conventions.gradle.kts` are
byte-identical to `c561e17`, so the lines `provider-key-release-gate.md` cites there have not moved, and neither have
`aimon-cli/build.gradle.kts:16-17` (`anthropic-thinking-config-surface.md`) or `CHANGELOG.md:8`
(`anthropic-sampling-capabilities.md`). The other line citations into files this change edits were already stale at
`c561e17`: §8's F-6, and `CHANGELOG.md:12` and `:1718-1732`, which cite text that was not on those lines.

### 13.4 The §8 findings and §9 open questions — where each went

- **No backlog item was registered**, and D-4 and D-5 are unused (§3.6). **D-2 and D-3 stay open.** D-2's *무엇* — decide
  quartz and opensearch per module — is unmet, and D-3's check is still undecided. D-3 now lists a Gradle upgrade as a
  trigger, and D-2 a Spring Boot bump that brings a newer Logback than the catalog's.
- **F-1** is recorded in §11.5 of [`test-classpath-shipped-versions.md`](test-classpath-shipped-versions.md), where
  #111's design cited the markers as precedent. The markers stay (§3.2).
- **F-2, F-3, F-4 and F-8** went to the PR body for the maintainer. None is the register's subject, and no reserved ID
  fits a shipped-dependency advisory check or the sample app's Boot-managed Logback.
- **F-5, F-6 and F-7 stay here.** F-5 describes the build as it now is; none of F-6's citations moved; F-7's file
  belongs to another run of this batch.
- **§9 questions 1, 2, 3, 4 and 7 stay here**, each answered by what was built, with a fallback the maintainer can take
  at review. Question 2's consequence beyond this change is D-3's new trigger. Question 5 is the commit and PR shape,
  and question 6 is F-8.

D-2 and D-3 point back here through §2 of
[`../../backlog/module-dependency-scope.md`](../../backlog/module-dependency-scope.md).

---

## 14. After #129 and #134 — corrections, and where the findings went

*Appended after issues [#129](https://github.com/kangwoo/aimon-core/issues/129) and
[#134](https://github.com/kangwoo/aimon-core/issues/134), at `main` `2eddf3d`. Everything between the header and §13 is
still the approved body, byte-exact, except for the correction mark at §4.10 (§14.3). This section records where that
body and §13 are wrong about GitHub's advisory database and about Dependabot, and where F-1, F-3, F-6, F-7 and F-8 went.*

### 14.1 GitHub's advisory database has both CVEs — unreviewed, naming no package

§1 ("The two newest have no entry in GitHub's advisory database or OSV"), the GHSA column of §2.1's table, the §4.1 and
§4.8 drafts, §8 F-4, §9 question 3, §12 rounds 1 and 2, and §13.3 all say the database has no advisory for
CVE-2026-13006 or CVE-2026-19880. It had one for each before this record was written:

| CVE | GHSA | published | type | `vulnerabilities` |
|---|---|---|---|---|
| CVE-2026-13006 | GHSA-567r-vvh5-jjr8 | 2026-06-24 | `unreviewed` | `[]` |
| CVE-2026-19880 | GHSA-9mh8-hq67-v26g | 2026-08-14 | `unreviewed` | `[]` |

What held is narrower: a query by package does not find them. `GET /advisories?ecosystem=maven&affects=` for
`ch.qos.logback:logback-core` or `logback-classic`, with `type=reviewed` or `type=unreviewed`, lists neither, because
neither names a package. §2.1's `securityVulnerabilities` sentence is such a query, and it is right. OSV still answers
"Vulnerability not found" for both CVE ids and both GHSA ids, and returns nothing for either artifact at 1.5.34, 1.6.2 or
1.6.3. NVD still has both *Deferred*, with no CPE configuration. F-4's conclusion therefore stands — a scanner reading
GitHub's database or OSV calls Logback 1.5.34–1.6.2 clean — and only its premise changes. The catalog note and
`CHANGELOG.md` now say what the database returns (#129). Queried 2026-09-11, 10:16:50–10:17:13 UTC.

### 14.2 F-2 — Dependabot computed a Logback bump in every run, and the open-PR limit dropped it

F-2 says no Dependabot PR ever touched Logback, and calls 1.5.13 → 1.5.38 patch-level. The three Gradle update jobs
before this record were runs 33366906463 and 33369271715 (2026-08-31) and 34069303184 (2026-09-07). Each logs
`Updating ch.qos.logback:logback-classic from 1.5.13 to 1.6.3` — a minor, which `production-patches` does not take — and
submits it as an individual PR. It was the 7th, 23rd and 6th of 29, 24 and 22 submissions. The jobs called
`create_pull_request` 29, 24 and 24 times, and five PRs appeared each time: `open-pull-requests-limit: 5`. That setting is
now 50, a bound above the most one run could submit on the catalog as it stands (#129).

### 14.3 The placeholder at §4.10

C-2 records that §4.10's "review round `<n>`" renders as "review round ,". §4.10 now carries the correction mark
[`../README.md`](../README.md) §3.4 allows: the approved sentence stays, struck through, and the mark says what stood
there. With the header, that mark's two `~~` pairs and added text, and §13–§14 removed, the body is byte-identical to
the approved design.

### 14.4 Where F-1, F-3, F-6, F-7 and F-8 went

- **F-1** — both `@Suppress("UnstableApiUsage")` are gone (#134). §3.2 kept them because deleting
  `aimon.java-conventions.gradle.kts:15` "shifts `:116` and `:128-133`, which another record cites accurately". Under
  §3.4 as merged with #123, a citation whose tree can be restored is dated rather than wrong, and every accurate citation
  below `:15` is dated to an older commit. `:buildSrc:compileKotlin` gives the same single warning before and after, in
  `aimon.publishable.gradle.kts`.
- **F-3** — the build still runs no dependency scanner. The note at the top of `gradle/libs.versions.toml` says that a
  version inside an advisory range is found by reading, and where to look. Enabling Dependabot alerts, with automatic
  dependency submission, was proposed to the maintainer in the pull request for #129 and #134; neither is a change in
  the repository.
- **F-6** — six of its nine citations now name what they describe (#134): `architecture-review-open-items.md` `:114` and
  `:561`, `roadmap.md:69`, `spring-boot-starter-open-items.md` `:903` and `:1358`, `integration-test-layers.md:240`. The
  other three stay: `reasoning-effort-config-surface.md:969` was accurate, `openai-model-capabilities.md:433` is in an
  approved body, and `architecture-review-open-items.md:393` quotes the file as it was before `5801997`.
- **F-7** — the bundled `logback.xml` no longer defines the `CONSOLE` appender. Launched from the built distribution,
  the CLI printed 30 Logback status lines to stdout before and none after, with stderr and the exit code unchanged; log
  output still goes only to `~/.aimon/logs/aimon.log` (#134).
- **F-8** — `aimon-sample-app` sets Spring Boot's `logback.version` from the catalog and packs 1.6.3; both fat jars
  start, and `FatJarPackagingTest` passes (#129).
