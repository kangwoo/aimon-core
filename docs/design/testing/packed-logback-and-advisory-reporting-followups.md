# Design — #129 and #134: the sample's Logback, what reports an advisory, Dependabot's PR limit, and #127's other follow-ups

> Status: **IMPLEMENTED** — `samples/aimon-sample-app/build.gradle.kts` (Spring Boot's `logback.version` set from the
> catalog, and the note on it), the note at the top of `gradle/libs.versions.toml` and its `logback` note,
> `.github/dependabot.yml` (the header, and the Gradle entry's `open-pull-requests-limit` and its comment), the comment
> that replaces the unreferenced `CONSOLE` appender in `modules/aimon-cli/src/main/resources/logback.xml`, the two
> `@Suppress("UnstableApiUsage")` removed from `buildSrc`, backlog D-2's sentence, the six citations #134 item 5 lists,
> §14 and the §4.10 correction mark of
> [`shipped-logback-and-test-classpath-followups.md`](shipped-logback-and-test-classpath-followups.md), §14 and the §2.5
> correction mark of [`../llm/openai-model-capabilities.md`](../llm/model-capabilities.md), and the section of
> `CHANGELOG.md` that opens "Dependencies: the sample app packs Logback 1.6.3". Sources: issues
> [#129](https://github.com/kangwoo/aimon-core/issues/129) and [#134](https://github.com/kangwoo/aimon-core/issues/134).
>
> **[§11](#11-after-the-build--departures-and-corrections), appended after the build, is where this document departs
> from what was built.** Everything between this header and §11 is the body as approved in design review round 1, kept
> byte-exact rather than corrected — the house habit in this directory, for the reason
> [`../llm/model-capability-binding-round-trip.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록) gives. Its
> `file:line` citations are at `main` `2eddf3d`. The run records it cites (`TASK.md`, `review-1.md`,
> `$RUN_DIR/design/probe/`, `$RUN_DIR/build/`) are not in the repository; §11 reproduces the measurements that matter.
>
> What this work left open is not in the backlog, and D-4 and D-5 stay unused: the repository settings it proposes, the
> findings outside #129 and #134, and the open questions the body leaves went to the maintainer in the pull request, as
> §11.4 lists.
>
> It is in English, matching the two records beside it; `docs/design/` is not a translation target either way
> (`docs/project/documentation-guide.md` §5.1).

> Base: `main` at `2eddf3d`. Every `file:line` below reads that tree unless it names another commit.
>
> Every number here was **measured by the design phase on 2026-09-11**, on an extracted copy of `2eddf3d`
> (`git archive HEAD | tar -x`) under `$RUN_DIR/design/probe/tree`, so the worktree was not touched. Outputs are in
> `$RUN_DIR/design/probe/out/`; network queries are in `$RUN_DIR/design/probe/*.txt|json`, each with its UTC time.
> No provider key was set in any shell (`env | grep -E '^(ANTHROPIC|OPENAI)_KEY'` empty, and every Gradle and CLI
> command ran under `env -u ANTHROPIC_KEY -u OPENAI_KEY`). These numbers are the prediction the build's own
> before/after measurements are checked against, not a substitute for them.
>
> Environment: macOS arm64, Homebrew OpenJDK 17.0.15 for the CLI and the probes, Gradle 9.2.1 from the wrapper. CI is
> ubuntu. Where a number describes CI (the packaging tier on Linux, CI minutes), that is said.

---

## 0. The decisions, first

| # | Question | Taken | Rejected |
|---|---|---|---|
| D1 | The sample's Logback (#129) | **Set Spring Boot's `logback.version` in the sample's build script from the catalog's `logback` (1.6.3).** One property moves `logback-classic` and `logback-core` together. Both fat jars were launched on it: `packagingTest` 6 tests, 0 failures | Keep 1.5.34 and record why; pin to 1.5.38; name `logback-classic` from the catalog (the split pair already recorded); a literal `"1.6.3"` |
| D2 | What reports a version inside an advisory range (#129) | **Record that the repository relies on reading, and where to look**: at the top of `gradle/libs.versions.toml` and in `.github/dependabot.yml`'s header. **Propose** Dependabot alerts plus automatic dependency submission to the maintainer in the PR body. No new workflow job | A PR-gating scanner; a scheduled scanner; a dependency-submission job; an NVD keyword-search job |
| D3 | Dependabot's open-PR limit (#129) | **Raise the Gradle `open-pull-requests-limit` from 5 to 50**, above what one run can submit, with a comment on how a drop shows and how to notice one | Keep 5 and record the drop; a grouped `production-minors` group |
| D4 | The CLI's start-up status print (#134 item 1) | **Remove the unreferenced `CONSOLE` appender** and put a comment in its place saying why. Measured: 30 status lines on stdout before, 0 after, on a real launch of the built distribution. Log output still only in `~/.aimon/logs/aimon.log` | Reference `CONSOLE`; install `NopStatusListener`; attach `CONSOLE` to a dead logger |
| D5 | The two `@Suppress("UnstableApiUsage")` (#134 item 4) | **Delete both.** `:buildSrc:compileKotlin` warnings: the same one line before and after | Keep them with a new reason |
| D6 | Records (#129 item 4, #134 items 2, 3, 5) | #127's CHANGELOG sentence **edited in place** with a pointer forward. #127's record gets a **§14** after its boundary, plus one correction mark at §4.10. `openai-model-capabilities.md` gets a correction mark at §2.5 and a **§14** for the mark to point at. D-2 names what sets each jar apart. The six citations name what they point at. **No new design record, no backlog item** (D-4 and D-5 unused) | Supersede note for #127's advisory sentence; inline marks at every body site of the advisory claim; line numbers for the six citations; a cross-record pointer for the openai mark |

---

## 1. The problem, in one paragraph

`samples/aimon-sample-app` takes Logback from Spring Boot 3.5.16, which manages 1.5.34. That version is inside
CVE-2026-13006 (logback-core ≤ 1.5.36) and CVE-2026-19880 (logback-classic ≤ 1.6.2), and #127's catalog change never
reached the sample. Nothing in the repository reports that. No workflow runs a scanner and Dependabot alerts are off.
Even with a scanner, the databases scanners read do not tie either CVE to Logback: GitHub holds both only as unreviewed
advisories naming no package, OSV has no record of either, and NVD has no CPE for either. Dependabot, for its part,
computed the catalog's Logback bump in each of its three Gradle runs and dropped it each time at an
`open-pull-requests-limit` of 5, silently. #127 recorded three wrong things about this: that GitHub had no advisory,
that Dependabot never proposed Logback, and that the bump was patch-level. #134 collects #127's smaller leftovers:
- the CLI's bundled `logback.xml` defines a `CONSOLE` appender nothing references, so Logback prints its whole
  configuration status to stdout on start-up;
- D-2's first clause contradicts the sentence after it;
- two exempt design records render a raw `<n>` and `<model>` as nothing;
- two `@Suppress` in `buildSrc` suppress nothing;
- six line citations point at the wrong lines.

---

## 2. What was measured

### 2.1 The advisory databases, re-queried

`$RUN_DIR/design/probe/ghsa.txt` and `nvd-osv.txt`, 2026-09-11T09:30:21Z–09:30:47Z.

| Source | Query | Answer |
|---|---|---|
| GitHub | `advisories/GHSA-567r-vvh5-jjr8` | `cve_id` CVE-2026-13006, `type` **unreviewed**, `published_at` 2026-06-24T09:30:45Z, `github_reviewed_at` null, `vulnerabilities: []` |
| GitHub | `advisories/GHSA-9mh8-hq67-v26g` | `cve_id` CVE-2026-19880, `type` **unreviewed**, `published_at` 2026-08-14T15:32:51Z, `github_reviewed_at` null, `vulnerabilities: []` |
| GitHub | `advisories?ecosystem=maven&affects=ch.qos.logback:logback-core&type=reviewed` | 10 advisories, CVE-2025-11226's GHSA-25qh-j22f-pwp8 among them; neither of the two |
| GitHub | same, `type=unreviewed`; and both queries for `logback-classic` | `[]` for unreviewed; 2 reviewed for classic (CVE-2023-6378, CVE-2017-5929) |
| GitHub | `advisories/GHSA-25qh-j22f-pwp8` | reviewed; `ch.qos.logback:logback-core >= 1.4.0, < 1.5.19` and `< 1.3.16` |
| NVD | `cves/2.0?cveId=CVE-2026-13006` | `vulnStatus` **Deferred**, `configurations` none, one reference `logback.qos.ch/news.html#1.5.37` |
| NVD | `cves/2.0?cveId=CVE-2026-19880` | **Deferred**, no configurations, one reference `news.html#1.6.3` |
| OSV | `/v1/vulns/` for both CVE ids and both GHSA ids | `"Vulnerability not found"` ×4 |
| OSV | `/v1/query` for `logback-core` and `logback-classic` at 1.5.34, 1.6.2, 1.6.3 | `{}` ×6 |

Both GHSA records were published **before** #127 measured on 2026-09-11. #127's sentences that say GitHub has no
advisory were therefore not true when written. What was true is narrower: a query by package does not find them.
§4.8 uses this to pick between editing #127's CHANGELOG sentence and superseding it.

### 2.2 The repository's security settings (GET only)

`repo-settings.txt`, 2026-09-11T09:30:49Z–09:30:51Z.
- Public repository.
- `dependabot_security_updates` disabled; `automated-security-fixes` `{"enabled": false}`.
- `dependabot/alerts` → 403, "Dependabot alerts are disabled for this repository".
- `dependency-graph/sbom` → 404.
- Secret scanning and push protection enabled.

**GitHub's documentation** (fetched 2026-09-11):
- The dependency graph's ecosystem table gives Gradle **no static manifest support** and no static transitive
  dependencies. It supports only *automatic dependency submission*, which runs "a fork of the open source Gradle
  actions from gradle/actions" with `github-dependency-graph-gradle-plugin`, on commits to the default branch.
  Otherwise the dependency submission API is needed.
- `open-pull-requests-limit`: *"If five pull requests with version updates are open, no further pull requests are
  raised until some of those open requests are merged or closed."* *"Security update pull requests are not subject to
  this limit."*
- `gradle/actions` `dependency-submission`:
  - needs `contents: write`;
  - can filter with `dependency-graph-exclude-configurations` / `-include-projects`;
  - cannot submit from fork PRs without a two-workflow split.

### 2.3 Dependabot's three Gradle jobs

`$RUN_DIR/design/probe/dependabot-<run>.log` (`gh run view --log`, read only) and `dependabot-prs.json`.

| Run | Date | `Submitting … pull request for creation` lines | `POST …/create_pull_request` calls | Logback's position among the submissions | PRs that appeared |
|---|---|---|---|---|---|
| 33366906463 | 2026-08-31 | 29 | 29 | 7th (`logback-classic from 1.5.13 to 1.6.3`) | #1–#5 — exactly the first five submissions |
| 33369271715 | 2026-08-31 | 24 | 24 | 23rd | #7–#11 — exactly the first five |
| 34069303184 | 2026-09-07 | 22 | **24** (two grouped PRs log no `Submitting` line) | 6th | #37, #39 (groups) + #40–#42 (the first three individual) |

- Every `create_pull_request` call answered 204; no line in any log mentions a limit.
- Grouped PRs count toward the limit (run 3: 2 groups + 3 individual = 5).
- `gradle-wrapper` 9.2.1 → 9.7.1 was dropped in all three.
- `.github/dependabot.yml`'s limit has been 5 since the initial commit (`git log -S`).

**What a raised limit would open on the first run.** Take run 3's 22 individual submissions. Remove the three merged
since (#40 openai, #41 snakeyaml, #42 kubernetes-client) and Logback, which the catalog now has at 1.6.3. **18
individual PRs** remain, plus up to three grouped PRs, plus whatever was released since. Estimate, not measured.

**CI cost per Dependabot PR**, from PRs #39, #40 and #41 on 2026-09-07 (`gh pr view --json statusCheckRollup`):
- `build` 10m24s–10m32s;
- `integration` 5m03s–5m16s;
- `coverage` 1m50s–1m57s;
- `docs-links` and `translations` 5–8s each.

About 18 job-minutes per PR; for 20 PRs, about 350 job-minutes on ubuntu runners. The repository is public, so
standard runners are not billed. The wall-clock queue depends on the account's concurrent-job limit, which was not
measured.

### 2.4 What an OSV-reading scanner would report today

`osv-querybatch.json` (2026-09-11T09:41:39Z) and `osv-vulns.json` (09:42:47Z). The design probe's init script
(`dump.init.gradle`) wrote every project's resolved `runtimeClasspath` module components.
- 27 projects, 20 of them published.
- **340 unique coordinates** went to OSV `/v1/querybatch`.
- **23 coordinates have advisories: 35 unique ones, 5 CRITICAL, 14 HIGH, 16 MODERATE** (OSV's `database_specific.severity`).
- **All 23 are transitive**, and no version was chosen by this repository's catalog:

| Where | Coordinates (advisory count) |
|---|---|
| `aimon-cli` and `aimon-llm-anthropic` | `httpclient5` 5.3.1 (1), `httpcore5` 5.2.4 (1), `httpcore5-h2` 5.2.4 (1) |
| `aimon-sandbox-kubernetes` | `netty-codec-http` 4.1.135 (8), `netty-handler` (2), `netty-codec-http2` (2), `netty-codec` (1), `httpclient5` 5.6.1, `httpcore5-h2` 5.4 |
| `aimon-session-redis` | `netty-handler` 4.2.13 (5), `netty-resolver-dns` (3), `netty-codec-dns` (1) |
| `aimon-sandbox-docker` | `bcprov-jdk18on` 1.82 (3), `bcpkix-jdk18on` 1.82 (1), `httpclient5` 5.5.1, `httpcore5` / `-h2` 5.3.6 |
| `aimon-knowledge-opensearch` | `httpclient5` 5.4.2 (2), `httpcore5` / `-h2` 5.3.3 |
| `aimon-sample-app` (Boot-managed) | `tomcat-embed-core` 10.1.55 (3 CRITICAL), `jackson-databind` 2.21.4 (3), `log4j-api` 2.24.3 (1) |

None of them is Logback. A scanner reading OSV would go red on its first run with 35 findings, and would still call
Logback 1.5.34–1.6.2 clean. These findings are outside #129 and #134. §8 routes them to the maintainer.

### 2.5 The sample on Logback 1.6.3

`out/02-*`, `03-*`, `04-*`. The probe added `extra["logback.version"] = libs.versions.logback.get()` to the extracted
sample build script.

| | Before (`2eddf3d`) | After |
|---|---|---|
| `dependencyInsight --configuration runtimeClasspath --dependency ch.qos.logback:logback-core` | `1.5.34 (selected by rule)` via `spring-boot-starter-logging:3.5.16` | **`1.6.3 (selected by rule)`** |
| same, `logback-classic` | `1.5.34 (selected by rule)` | **`1.5.34 -> 1.6.3` (selected by rule)** |
| `org.slf4j:slf4j-api` | 2.0.18 | 2.0.18 |
| `org.codehaus.janino` | "No dependencies matching given input were found" | the same, on `runtimeClasspath` and `testRuntimeClasspath` |
| `testRuntimeClasspath` | — | the pair at 1.6.3 as well |
| `unzip -l` both fat jars | — | `BOOT-INF/lib/logback-classic-1.6.3.jar`, `logback-core-1.6.3.jar`, `slf4j-api-2.0.18.jar`, `jul-to-slf4j-2.0.18.jar`, `log4j-to-slf4j-2.24.3.jar`, in both `aimon-sample-app-0.2.4.jar` and `-classic.jar` |
| `./gradlew :aimon-sample-app:packagingTest` | green in CI on `main` | **BUILD SUCCESSFUL; `FatJarPackagingTest` 6 tests, 0 failures, 0 errors, 0 skipped.** The task builds both fat jars and launches three JVMs (nested jar, classic jar, exploded) |

#127's §2.6 had already probed Boot 3.5.16's Logback integration by reflection: all 158 members it references exist on
1.6.3. This run launched the application on it. Measured on macOS; CI's `build` job measures the same task on ubuntu
when the PR runs.

### 2.6 The CLI's status print

`out/status-*`, `out/launch-*`.

1. **The #127 probe, as its §2.6 describes it.** `LogbackStatusProbe.java` puts the built distribution's `lib/*` on the
   class path, sets `-Duser.home` to a scratch directory and logs one WARN line.
2. **A real launch of the built CLI that fails before any LLM client exists.**
   - Command: `bin/aimon-cli --config unsupported-provider.yaml < /dev/null`, with
     `JAVA_OPTS=-Duser.home=<scratch>`.
   - The config is `llm: {provider: "no-such-provider", apiKey: "not-a-key"}`. It passes
     `CliConfigLoader.validateConfig`, which only requires `llm`, `provider` and `apiKey`.
   - `new AgentSetupFactory()` initialises its static `Logger` (`AgentSetupFactory.java:141`), and Logback configures
     at that point.
   - `create()` calls `createLlmClient` first (`:507`), and `LlmClientFactory` throws
     `Unsupported LLM provider` (`LlmClientFactory.java:49`).
   - No model call is possible on this path.

| | stdout lines | Logback status lines (`\|-`) | WARN | stderr | exit | `~/.aimon/logs/aimon.log` |
|---|---|---|---|---|---|---|
| Probe, `logback.xml` as on `main` | 31 | 30 | 1 — `Appender named [CONSOLE] not referenced. Skipping further processing.` | `PROBE-DONE` | 0 | written; holds the probe's line |
| Probe, `CONSOLE` removed | **0** | 0 | 0 | `PROBE-DONE` | 0 | written; holds the probe's line |
| Launch, `main` | 31 | 30 | 1 (same) | `Configuration error: Unsupported LLM provider: no-such-provider` | 1 | created |
| Launch, `CONSOLE` removed | **0** | 0 | 0 | same | 1 | created |

The 31st stdout line is the blank line `StatusPrinter2` ends with. Both runs are on Logback 1.6.3
(`lib/logback-{classic,core}-1.6.3.jar`).

### 2.7 `buildSrc` Kotlin warnings with and without the two `@Suppress`

`out/01-dump-base.log`, `out/07-help-after-suppress.log`. `:buildSrc:compileKotlin` ran in both invocations: in the
first because `buildSrc` had never been compiled in the extracted tree, in the second because both sources changed.

| | `w:` lines |
|---|---|
| Before | 1: `aimon.publishable.gradle.kts:56:17 Check for instance is always 'true'.` |
| After deleting both `@Suppress("UnstableApiUsage")` | the same 1 line |

`javap -v … | grep -c Incubating`, over the wrapper's jars:
- `gradle-core-api-9.2.1.jar`: `VersionCatalogsExtension` 0, `VersionCatalog` 0 (as #134 found);
- `gradle-kotlin-dsl-9.2.1.jar`: `org.gradle.kotlin.dsl.ProjectExtensionsKt`, which declares `the<T>()`, 0.

The build repeats all three (§7.2).

### 2.8 Citations into files this change moves

- **Inside the repository** (`git grep`):
  - `provider-switch-agent-model-check.md:113` cites `logback.xml` `:20` and `:26-28`, in an approved body whose
    citations are at `a1236c8`.
  - `shipped-logback-and-test-classpath-followups.md:893` and `test-classpath-shipped-versions.md:562` cite the sample
    build script at `:49-53`, both in approved bodies.
  - `shipped-logback-…:268` cites `aimon.spring-starter.gradle.kts:13`, in its approved body.
  - The conventions-plugin and catalog citations are #134's lists: six stale ones, fixed by name here, and the dated
    ones listed in #134 item 4.
  - Nothing in the repository cites `dependabot.yml` by line.
- **In the sibling issues** (`gh issue view 130–135`, read only):
  - #131 cites `aimon.java-conventions.gradle.kts:130-131` and `:130`, the `excludeTags` lines.
  - #130 cites `shipped-logback-…:8`, `openai-model-capabilities.md:3-5`, `:11-19` and `:748`.
  - #133 and #135 cite `CHANGELOG.md` lines, which move anyway.

---

## 3. Decisions and the alternatives rejected

### 3.1 D1 — #129: the sample sets Boot's `logback.version` from the catalog

**Taken.** In `samples/aimon-sample-app/build.gradle.kts`, after the `dependencies { }` block:

```kotlin
extra["logback.version"] = libs.versions.logback.get()
```

This is Spring Boot's documented way to change a version its dependency management governs. The
`io.spring.dependency-management` plugin reads a project property with the name of the BOM's version property. Boot's
BOM governs `logback-classic` and `logback-core` with the one `logback.version`, so the pair cannot split. The split is
what the existing note at `:49-53` records: classic from the catalog, core from Boot, `NoSuchMethodError` at start-up.

**Why the catalog and not a literal.**
- One Logback version for the repository. The CLI ships the catalog's `logback`, and the catalog note already says to
  keep it at or above Boot's.
- Dependabot tracks the catalog's `logback-classic`, the only Logback it has ever checked (§2.3). With the sample reading
  that entry, a Dependabot bump moves the sample too. That answers #129's "Dependabot never sees the sample's Logback"
  without a second place to update.
- If a later catalog bump brings a Logback that Boot 3.5.x cannot start, CI's `build` job fails: its
  `Fat-jar packaging tests` step launches both fat jars.

**Why not the options the issue lists as alternatives.**
- **Keep 1.5.34 and record why.** It is defensible on reachability: no Janino on the sample's class path, and no
  Logback configuration file, so no `SiftingAppender` discriminator. But the sample is what an integrator copies. Its
  README tells readers to `bootRun` it, including a `live` profile on fixed port 18080. The fix costs one line and was
  measured to start (§2.5). The issue frames this as the fallback, and it is kept as the fallback for one case only: if
  the build's launch fails where the design's passed.
- **Pin to 1.5.38**, the newest 1.5.x and the Boot 4.x lines' version. Still inside CVE-2026-19880; it meets neither the
  issue nor #127's reasoning for 1.6.3.
- **`implementation(libs.logback.classic)` in the sample.** That is the split pair `:49-53` records.
- **A `dependencyManagement { dependencySet("ch.qos.logback:…") { entry(…) } }` block.** It works too, but names both
  jars by hand where Boot's single property already covers both. If Boot ever adds a third Logback artifact to the
  property, the property follows and the hand-written set does not.
- **A literal `"1.6.3"`.** A second copy of the number, which no Dependabot run would move.

**What it does not change.** The sample stays unpublished. `spring-boot-starter-logging` still brings Logback, the
bridges `jul-to-slf4j` and `log4j-to-slf4j` still come from Boot, and `slf4j-api` stays 2.0.18. The existing note's
first sentence, *"Deliberately no explicit logback dependency"*, stays true.

### 3.2 D2 — #129: record reliance on reading and where to look; propose the settings

**Taken.**

1. **A short note at the top of `gradle/libs.versions.toml`**, before `[versions]` (§4.2). Every version in the build is
   chosen there, so every contributor who picks or keeps a version meets it. It says three things:
   - nothing in this build reads these versions against an advisory database;
   - Dependabot proposes newer versions without knowing which old ones are affected, and Dependabot alerts are off
     (dated);
   - so an affected version is found by reading: NVD searched by the library's name, and the project's release notes,
     because GitHub's database and OSV find an advisory by package only once it is reviewed.

   The `logback` note already does this for Logback ("look for the next one in NVD and on logback.qos.ch/news.html"), so
   the new note generalises a rule the file already follows.
2. **Two sentences in `.github/dependabot.yml`'s header**, pointing at that note. Whoever changes update automation meets
   it there, and it stops anyone reading version updates as advisory coverage.
3. **In the PR body, for the maintainer**, not changed here:
   - enable **Dependabot alerts**;
   - enable **automatic dependency submission**, because GitHub's dependency graph has no static Gradle support (§2.2).
     Without it, alerts would not see the resolved graph, including the sample's transitive Logback;
   - optionally enable **Dependabot security updates**, which are exempt from `open-pull-requests-limit`.

   The body says plainly what these would and would not report. They would cover the reviewed advisories among §2.4's
   35. They would not cover either Logback CVE while GitHub keeps both unreviewed with no package. They would also have
   caught #114's CVE-2025-11226 on the CLI's 1.5.13, because that advisory is reviewed and names `logback-core`.

**The first half of the issue's "should happen instead"** asks that something other than reading report it, "not only
GitHub's database or OSV". On 2026-09-11 **nothing automated can**:
- GitHub and OSV do not tie either CVE to a package;
- NVD's records carry no CPE configuration, so CPE-matching scanners (OWASP dependency-check, Grype's NVD matcher) miss
  them too.

Only NVD's free-text description and Logback's news page connect the CVE to the jar. So the issue's second branch,
"the repository records that it relies on reading, and where to look", is the one that can be met truthfully. The
settings proposal adds automated coverage for the reviewed majority of advisories, and that coverage is the
maintainer's to switch on.

**Rejected — a scanner job that gates PRs or `main`** (OSV-Scanner, Trivy, Grype, or `actions/dependency-review-action`).
- **It goes red on its first run** with 35 advisories (§2.4). Every one is transitive, and many sit where this repository
  does not choose the version: Boot-managed in the sample, pulled in by the Anthropic SDK, docker-java, the Kubernetes
  client or Lettuce. Those are exactly "advisories nobody can act on" in the PR that trips them.
- **It would still call Logback 1.5.34–1.6.2 clean.**
- **This build does not produce what these scanners read.** OSV-Scanner and Trivy read Gradle lockfiles, and #127
  rejected dependency locking. Grype needs an SBOM, which would mean a CycloneDX plugin or a hand-written converter in
  `buildSrc`, adding supply-chain surface to a security job.
- **`dependency-review-action`** checks only what a PR changes. It would not have reported #114 or #129, whose versions
  sat unchanged for weeks, and it needs the dependency graph, which needs submission.

**Rejected — a weekly scheduled scanner that emails on red.** It never blocks a PR, which answers the noise objection
for PRs. But:
- it opens with the same 35 findings;
- turning it green means an allowlist of 35 entries on its first commit — the shape T-7 declined, "16건짜리 예외 목록
  … 아무도 다시 읽지 않는 표";
- it needs the same lockfile or SBOM step;
- it is still blind to the two CVEs that motivated it.

**Rejected — a `gradle/actions/dependency-submission` job.** Its one advantage over the setting is real: it can exclude
test configurations (`dependency-graph-exclude-configurations`), where GitHub's documentation does not say which
configurations automatic submission includes (§9 Q3). But the job reports nothing until a repository setting
(Dependabot alerts) is changed, and that setting is the maintainer's decision. It also needs `contents: write`, and a
two-workflow split for fork PRs. Shipping it here would ship half of a feature whose other half is not this run's to
turn on. It stays the stated alternative in the PR body, for the case where the maintainer enables alerts and finds
automatic submission noisy.

**Rejected — a job that searches NVD by keyword.** It is the only automated check that would have found these two CVEs
(`keywordSearch=logback` returns them). But:
- keyword matching over 340 coordinates is free text, not package identity;
- NVD rate-limits unauthenticated clients, and an API key is a repository secret, which is a setting;
- it is a new mechanism larger than both issues.

The catalog note names that exact query for a reader instead.

### 3.3 D3 — #129: raise `open-pull-requests-limit` for Gradle from 5 to 50

**Taken.** `.github/dependabot.yml`'s `gradle` entry gets `open-pull-requests-limit: 50`, with a comment (§4.3). The
`github-actions` and `pip` entries keep 3. Each routes every update through one catch-all group, so a run opens at most
one PR there and their limit never decides anything.

**Why raise rather than keep and record.**
- **A record cannot say what was dropped.** Every `create_pull_request` call answers 204, no log line mentions the
  limit, and the dropped PR simply never exists (§2.3). A comment saying "each run drops most of what it computes" is
  true every Monday and names nothing on any of them. #129's own evidence for which update was dropped came from
  cross-reading three logs against the PR list, and nobody does that weekly.
- **The file's own policy says minors and majors "arrive as individual PRs so somebody reads the changelog before the
  merge".** At 5, 17–24 of them never arrive. The limit does not bring the policy's cost down; it stops the policy
  running.
- **The case that cost something is the one #129 found.** A Logback release fixing two CVEs was computed three times
  and shown zero times.
- **The flood is bounded and one-off.** About 18 individual PRs are queued behind the limit today (§2.3). After one
  triage pass — merge, close, or `@dependabot ignore this major version` — a week brings what the dependency set
  actually produces. Grouped PRs keep auto-merging on green, and individual ones keep waiting for a person, as
  `dependabot-auto-merge.yml` already decides.

**Why 50.** The limit should decide nothing. Per run, this configuration can open at most one PR per update unit (a
version ref Dependabot updates in one PR, or the wrapper) plus the three groups.
- Counted by hand from `gradle/libs.versions.toml`: about 45 version refs that libraries and plugins use, including the
  inline `jetbrains-annotations`, plus `gradle-wrapper`, gives about 46 units. With three groups, about 49.
- The largest run measured submitted 29.

50 is above both, and the grouping above the limit stays what decides how many PRs a week brings. The build recounts the
units exactly (§7.2). If the recount exceeds 47 units, making 50 too tight, the build takes the next multiple of ten
and records it as a departure. It is not a throttle, but it remains a guard: a configuration error that multiplies
units, such as a second `directory`, still stops at a ceiling.

**Rejected — keep 5 and record the drop.** See above; the issue offers it, and it is the fallback if the maintainer
finds 18 PRs at once worse than losing them.

**Rejected — a `production-minors` group** (`update-types: [minor]` over the same patterns as `production-patches`).
It would shrink the computed set by folding minors into one PR, but:
- `dependabot-auto-merge.yml` enables auto-merge for **any** grouped PR at minor or patch
  (`steps.metadata.outputs.dependency-group != ''`). Production minors, the LLM SDKs among them, would merge on green
  with nobody reading the changelog. That is the opposite of what `dependabot.yml`'s own comment reserves them for.
- Preventing that means editing `dependabot-auto-merge.yml`, which is not in this run's files.
- It would still not guarantee that nothing is dropped. Majors stay individual, and there are nine of them in run 3
  (junit-bom 6, HikariCP 7, jline 4, spotless 8, mongodb 5, both OpenSearch 3s, graal-sdk 25, spring-boot 4 as the
  already-open #2).

### 3.4 D4 — #134 item 1: remove the unreferenced `CONSOLE` appender

**Taken.** Delete `logback.xml:2-6` (the appender) and put in its place a five-line XML comment plus the existing blank
line (§4.5). The comment says three things:
- the configuration is file-only on purpose;
- #92's record relies on that;
- an appender defined without a reference makes Logback print its status block on start-up.

The replacement keeps the file's line count, so `FILE` still starts at `:8`, `<logger name="aimon">` stays at `:20` and
the root logger at `:26-28`. Those are the lines `provider-switch-agent-model-check.md:113` and #134 cite. That is not
the reason for the change, but it costs nothing.

**What reaches the terminal is unchanged except for the status block.** Measured on a real launch (§2.6):
- stdout goes from 30 status lines (plus Logback's trailing blank line) to nothing;
- stderr is identical;
- the exit code is identical;
- the log file is created in both cases, and in the probe the logged line lands in `aimon.log` in both cases.

The root logger still has only `FILE` attached. #92's *"`log.warn` alone never reaches the terminal"* holds before and
after.

**Rejected — reference `CONSOLE`** (from the root, or from `aimon`). Log output would reach the terminal the REPL owns,
breaking what #134 says a fix must keep and what #92's record relies on.

**Rejected — `<statusListener class="ch.qos.logback.core.status.NopStatusListener"/>`.** It silences the WARN, but it also
silences an ERROR: a log directory that cannot be created, a malformed pattern, a user's broken replacement file. Today
those print, and they should. `printInCaseOfErrorsOrWarnings` is the mechanism that surfaces them. The defect is the one
WARN, not the mechanism.

**Rejected — attach `CONSOLE` to a logger nothing uses.** It silences the WARN by adding configuration that means nothing.

### 3.5 D5 — #134 item 4: delete both `@Suppress("UnstableApiUsage")`

**Taken.** Delete `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:15` and `aimon.spring-starter.gradle.kts:13`.

- **Build stays clean.** `:buildSrc:compileKotlin` gives the same single warning, which is in another file (§2.7). The
  Kotlin compiler does not know `UnstableApiUsage`; it is an IntelliJ inspection id, so the compiler could not have
  warned in either state.
- **#127's reason to keep them no longer holds.** Its §3.2 said deleting `:15` "shifts `:116` and `:128-133`, which
  another record cites accurately". Under `docs/design/README.md` §3.4 as merged with #123, a citation whose tree can be
  restored is dated, not wrong. Every accurate citation below `:15` is in an approved body with an older base commit,
  or in a post-boundary section dated by blame:
  - `provider-key-release-gate.md:46`, base `9b642cc`;
  - `provider-key-census-claim-and-inputs.md:55` and #127's `:267`, `:312`, `:464`, base `c561e17`;
  - `reasoning-effort-config-surface.md:969`, §17, `3dd56df`;
  - `architecture-review-open-items.md:393`, which quotes the file before `5801997`.

  The six stale citations in #134 item 5 get names instead of lines (§3.6), so the shift cannot make them wrong again.
- **What is not measured.** Whether an IDE inspection flags anything once the markers are gone. It would be IDE-only,
  and none of the three declarations the line uses carries `@Incubating` (§2.7). §9 Q4.

**Rejected — keep them with a new reason.** No reason holds on `main`: nothing is suppressed, and the citations they
protected are dated.

### 3.6 D6 — the records

**#127's CHANGELOG sentence** (`CHANGELOG.md:173`): *"…and neither it nor CVE-2026-13006 has a GitHub advisory yet."*
**Edited in place, with a pointer to this run's entry.** `CHANGELOG.md` has two precedents, and this sentence fits the
second:
- **Supersede notes in a newer entry** (`:65`, `:159`, `:188`, `:241`, and the "Superseded within this release" bullet
  at `:1386`) record a sentence that was true when written and was overtaken by a later change. `:188`'s *"so its tests
  now run on Logback 1.5.13"* was true until #114.
- **The o-series bullet at `:958-965`** was corrected in place with a pointer forward (*"not routed … in that cut … see
  the round-8 entry below"*), which `openai-model-capabilities.md` §13.8 describes as "Edited in place, as a superseded
  record with a pointer forward".

The advisory sentence was never true of the database: both GHSA records predate #127's queries (§2.1). A release note
that ships a false sentence plus a correction elsewhere is worse than a corrected sentence. The PR body says which
precedent was followed and why.

The same entry's *"loads with the same one warning as before (its unreferenced `CONSOLE` appender)"* **was** true
until D4. It gets a supersede note in this run's new entry, following the first precedent. Both precedents are used,
each where it fits.

**#127's record** (exempt, boundary §13):
- A `## 14.` appended after §13 (§4.9). Under §3.4, a later correction to a post-boundary section is appended, not
  written into §13.
- One sentence added to its `Status` naming §14. `provider-switch-agent-model-check.md`'s Status names its later §11 and
  §12 the same way.
- The §4.10 correction mark (#134 item 3).
- **No correction marks at the other body sites of the advisory claim**: §1 `:74`, the §2.1 table, the §4.1 and §4.8
  drafts, §8 F-4, §9 question 3, §12 round 2. §3.4 allows marks ("달 수는 있다") but does not require them. The task
  scopes #129 item 4's record correction to "a correction after its boundary". Seven marks would edit seven places in an
  approved body that §14.1 lists in one paragraph.

**`openai-model-capabilities.md`** (exempt, boundary §8):
- The §2.5 correction mark sits inside the existing physical lines `:271-273`. **No line moves**, so every line citation
  into the record stays put, including #130's `:3-5`, `:11-19` and `:748`, and #134's `:748` and `:433`.
- A short `## 14.` is appended after §13 for the mark to point at.

The task's file table grants this run "the placeholder's correction mark" in that file. §3.4 as it stands allows a mark
only "가리킨 절이 그 정정을 적는 한" (as long as the section it points at writes the correction). No post-boundary
section of that record mentions the placeholder, and editing §8.1's table would itself break §3.4 ("덧붙인 절도 같다"). So
the mark needs its own appended section. **Rejected — point the mark at #127's §14 instead**: §3.4's pointer is to the
record's own post-boundary section, and a reader of the openai record would find nothing in it about an edit to its own
body. The PR's Merge notes say the section was added. No other run in the batch touches the file. §9 Q1.

**D-2** (#134 item 2). Rewrite the one sentence so it says, per jar, what sets it apart from #99's two accepted sources,
which are both annotation jars:
- `jakarta.xml.bind-api` differs in kind, being a jar that carries code;
- `jakarta.annotation-api` is the same kind of jar but differs in how it reaches a test run.

Draft in §4.8. The bullets below it already say the same per jar and stay unchanged. Register counts are unchanged.

**The six citations** (#134 item 5): **by name, not by line**. D5 moves every conventions-plugin line below `:15`, and
§4.2 moves every catalog line, in the same change. A line number would be right for one commit. §3.3 of the design
README already asks records to cite by name, and none of the four files is an exempt record. `integration-test-layers.md`
has no marker, so §3.3 applies to it in full, and a name is the conforming form whatever T-7 later decides about that
record. `spring-boot-starter-open-items.md:903` also dates the measurement it cites: 2.2 was the pin then, and 2.7 is
now. It does not re-measure (§4.10).

**No new design record.** Every decision's reason lives beside what it governs:
- the sample's build script comment;
- `dependabot.yml`'s comment;
- `logback.xml`'s comment;
- the catalog note.

The corrections to #127's findings live in #127's record, where they were made. A new record would split one Logback
advisory story across two exempt records and add an index row to `docs/design/README.md`. The PR body's decision headings
carry the rejected alternatives for review. Rejected alternative: commit this design as `docs/design/testing/…`.

**No backlog item; `D-4` and `D-5` unused.** Nothing this run leaves open belongs to `module-dependency-scope.md`'s subject
(POM scope and test-classpath versions):
- the reporting decision is made and recorded;
- the settings are the maintainer's and live in the PR body;
- §2.4's 35 advisories are findings about shipped transitive dependencies, which is not that register's subject.

No reserved ID fits them, so they go to `build/deviations.md` and the PR body (§8).

---

## 4. Concrete changes, by file

Drafts below are wording to start from. The build may reflow them to the target file's width (120 columns in the
catalog, build scripts and `CHANGELOG.md`; about 100 in `openai-model-capabilities.md`), and fills measured numbers from
its own measurements.

### 4.1 `samples/aimon-sample-app/build.gradle.kts`

- `:1-53` byte-identical, so `:49-53`, which two approved bodies cite, still holds the split-pair note.
- Inside the comment, after `:53`, one line:

  ```kotlin
      // It takes the version from the catalog rather than from Boot, though: see `logback.version` below.
  ```
- After the `dependencies { }` block's closing brace:

  ```kotlin

  // Spring Boot 3.5.16 manages Logback 1.5.34, inside CVE-2026-13006 (logback-core up to 1.5.36; needs Janino, which this
  // class path does not carry) and CVE-2026-19880 (logback-classic up to 1.6.2; needs a SiftingAppender whose MDC
  // discriminator an attacker influences, and this module has no Logback configuration file), and no Spring Boot line
  // manages a Logback outside both (#129). So the sample does what an application does to take a fix Boot does not
  // manage yet: it overrides the property Boot's dependency management reads for logback-classic and logback-core, with
  // the catalog's `logback` — the version aimon-cli ships. One property moves both jars; naming one of them is what split
  // the pair above. Both fat jars start on it (packagingTest, 2026-09-11). A catalog bump moves this with it, and
  // `packagingTest` is what fails if that Logback stops starting under Boot. If Boot ever manages a newer Logback than
  // the catalog, this line holds the sample below Boot's; the catalog note says to keep `logback` at or above it.
  extra["logback.version"] = libs.versions.logback.get()
  ```

### 4.2 `gradle/libs.versions.toml`

**(a) A note before `[versions]`** (every line below moves; §2.8):

```toml
# Advisories. Nothing in this build reads these versions against an advisory database: no CI job runs a dependency
# scanner, Dependabot alerts are off in the repository's settings (2026-09-11), and .github/dependabot.yml proposes newer
# versions without knowing which old ones are affected. So a version inside a published advisory range is found by
# reading. Before choosing or keeping a version, search NVD for the library by name
# (services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=<name>) and read the project's release notes. Do not stop at
# GitHub's advisory database or OSV: a query by package finds an advisory there only once it is reviewed, and the
# `logback` note below records two CVEs neither returned. If Dependabot alerts are turned on, they report reviewed
# advisories against the resolved graph; this note still covers the rest.

[versions]
```

**(b) The `logback` note** (`:152-165`). Three sentences change; the rest stays:
- `:152-153` *"The Logback aimon-cli ships — the one use of this entry that ships; every other module …"* becomes *"The
  Logback aimon-cli ships, and the Logback the unpublished aimon-sample-app packs, whose build script sets Spring Boot's
  `logback.version` from this entry (#129); every other module …"*.
- `:159-160` *"GitHub's advisory database and OSV listed neither of the two newest: look for the next one in NVD and on
  logback.qos.ch/news.html, not only by id."* becomes *"GitHub's advisory database holds the two newest only as
  unreviewed advisories that name no package (GHSA-567r-vvh5-jjr8, GHSA-9mh8-hq67-v26g), so a query by package returns
  neither, and OSV has no record of either (2026-09-11): look for the next one in NVD and on logback.qos.ch/news.html,
  not only by id."*
- `:161-163` *"… so this entry is ahead of `spring-boot` and wins on every test classpath that names it. Keep it at or
  above Boot's: below it, spring-boot-starter-test raises this entry there, and aimon-session-testkit's shipped and
  tested versions part again (backlog D-2)."* gains *"…, and replaces Boot's version in aimon-sample-app"* after
  *"names it"*, and *"; below it, aimon-sample-app would pack an older Logback than Boot manages"* before the closing
  period.

`logback = "1.6.3"` is unchanged.

### 4.3 `.github/dependabot.yml`

**Header**, after `:11`:

```yaml
#
# Version updates only. Nothing here reports a version that sits inside an advisory range: Dependabot proposes newer
# versions without knowing which old ones are affected, and Dependabot alerts are a repository setting that is off
# (2026-09-11). The note at the top of `gradle/libs.versions.toml` says how an affected version is found.
```

**The `gradle` entry's limit** (`:22`):

```yaml
    # Not a throttle — the grouping below decides how many PRs a week brings. Dependabot opens at most this many
    # version-update PRs at a time, grouped ones included, and drops every other update it computed without a log line:
    # the job log shows one `create_pull_request` call per update, and the PR simply never appears. At 5, the three
    # Gradle runs to 2026-09-07 made 29, 24 and 24 such calls and opened five PRs each, so a Logback release fixing two
    # CVEs was computed three times and shown none (#129). 50 is above what this configuration can submit in one run on
    # today's catalog — one PR per version ref plus the wrapper, and the three groups. If the catalog outgrows it, raise
    # it: past the limit, updates are dropped again, and the only sign is a job log with more calls than new PRs.
    open-pull-requests-limit: 50
```

The two other ecosystems are unchanged.

### 4.4 A dependency-scan workflow or job

**None** (D2).

### 4.5 `modules/aimon-cli/src/main/resources/logback.xml`

`:2-6`, the `CONSOLE` appender, becomes a five-line comment, and `:7` stays blank:

```xml
<configuration>
    <!-- File only, on purpose: nothing Logback writes reaches the terminal, which belongs to the REPL
         (docs/design/llm/provider-switch-agent-model-check.md relies on it). Do not define an appender here
         without the appender-ref that uses it: Logback reports an unreferenced appender as a WARN, and any
         WARN while it configures itself makes it print its whole configuration status to stdout (#134).
    -->

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
```

The rest of the file is byte-identical.

### 4.6 `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` and `aimon.spring-starter.gradle.kts`

Delete the line `@Suppress("UnstableApiUsage")` in each (`:15`, `:13`). Nothing else.

### 4.7 `CHANGELOG.md`

**(a) A new section at the top of `[Unreleased]`.** The build fills the bracketed numbers.

```markdown
### Sample app packs Logback 1.6.3, the CLI stops printing Logback's status, and Dependabot stops dropping updates

- **`aimon-sample-app` packs `logback-classic` and `logback-core` 1.6.3 instead of 1.5.34** (#129). Spring Boot 3.5.16
  manages 1.5.34, inside CVE-2026-13006 (logback-core up to 1.5.36) and CVE-2026-19880 (logback-classic up to 1.6.2),
  and no Boot line manages a Logback outside both. The sample's build script now sets Boot's `logback.version` from the
  catalog's `logback`, the version `aimon-cli` ships, so the two jars move together. Both fat jars start on it; the
  packaging tier passes ([n] tests). The sample is not published, and no published module's POM or module metadata
  changes.
- **The CLI no longer prints Logback's configuration status when it starts** (#134). The bundled `logback.xml` defined a
  `CONSOLE` appender that nothing referenced. Logback reports that as a WARN, and a WARN while it configures itself
  makes it print every status line to stdout — [30] lines, before the REPL's first output or a configuration error. The
  appender is gone. Log output still goes only to `~/.aimon/logs/aimon.log`, and nothing else a user sees at start-up
  changes. **This supersedes #127's entry below**, which says the file "loads with the same one warning as before (its
  unreferenced `CONSOLE` appender)".
- **Dependabot opens every Gradle update it computes** (#129). `open-pull-requests-limit` goes from 5 to 50. At 5, each
  weekly run computed 22 to 29 updates and five became PRs; the rest were dropped without a log line, Logback 1.6.3 among
  them three times. Expect the first run after this to open about twenty PRs, one per production minor or major the
  catalog is behind on. Grouping and auto-merge are unchanged, so those PRs wait for a person.
- **Nothing in the build reports a version inside an advisory range, and the catalog now says so** (#129). The note at
  the top of `gradle/libs.versions.toml` says where to look: NVD by the library's name, and the project's release notes.
  GitHub's advisory database and OSV find an advisory by package only once it is reviewed, and they hold CVE-2026-13006
  and CVE-2026-19880 as unreviewed advisories naming no package, or not at all. #127's entry below said neither CVE had
  a GitHub advisory; that sentence is corrected in place.
- **Records** (#134). Backlog D-2 says what sets each of its two jars apart from #99's accepted annotation jars. Two design
  records carry a correction mark where a placeholder rendered as nothing — §4.10 of
  `docs/design/testing/shipped-logback-and-test-classpath-followups.md`, which also gains a §14 on where #127's findings
  went, and §2.5 of `docs/design/llm/openai-model-capabilities.md`, which gains a §14 for its mark. `buildSrc` drops two
  `@Suppress("UnstableApiUsage")` that suppressed nothing (Kotlin compile warnings unchanged). Six line citations into
  the conventions plugin and the catalog now name what they point at.
```

**(b) #127's entry, `:173`, edited in place** (reflowed with its neighbours):

> *"CVE-2026-19880 is the one with no fix on 1.5.x, and neither it nor CVE-2026-13006 has a GitHub advisory yet."*
>
> → *"CVE-2026-19880 is the one with no fix on 1.5.x. GitHub's advisory database holds it and CVE-2026-13006 only as
> unreviewed advisories that name no package, so a query by package finds neither (corrected in #129's entry above)."*

Nothing else in #127's entry changes. Its `CONSOLE` sentence is superseded from (a).

### 4.8 `docs/backlog/module-dependency-scope.md` — D-2's sentence (`:122-125`)

Current:

> 둘 다 #99 가 `aimon-cli` 에서 맞춘 것과 **같은 출처**다. #99 가 받아들인 두 출처와 이 둘을 가르는 것은 jar 의 종류가
> 아니라 — `jakarta.annotation-api` 도 주석 jar 다 — 테스트 실행에 닿는 방식이다. `jakarta.xml.bind-api` 는 코드를 담고,
> `jakarta.annotation-api` 는 프레임워크가 실행 중에 읽는 RUNTIME-retention 주석을 담으며 1.3.5 와 2.1.1 사이에 패키지가
> `javax` 에서 `jakarta` 로 바뀐다.

Draft:

> 둘 다 #99 가 `aimon-cli` 에서 맞춘 것과 **같은 출처**다. #99 가 받아들인 두 출처는 주석 jar 이고, 이 둘이 그 둘과
> 갈리는 자리는 jar 마다 다르다. `jakarta.xml.bind-api` 는 jar 의 종류부터 다르다 — 주석 jar 가 아니라 코드를 담은 jar 다.
> `jakarta.annotation-api` 는 같은 주석 jar 이지만 테스트 실행에 닿는 방식이 다르다 — 프레임워크가 실행 중에 읽는
> RUNTIME-retention 주석을 담고, 1.3.5 와 2.1.1 사이에 패키지가 `javax` 에서 `jakarta` 로 바뀐다.

The two bullets at `:127-132` are unchanged; the first already counts 78 of 108 classes as code. The title count line
and the `docs/backlog/README.md` index row are unchanged (3 items, 3 open), and the build recounts both from the body.
The file has no `.en.md`, because `backlog/` is not a translation target.

### 4.9 `docs/design/testing/shipped-logback-and-test-classpath-followups.md`

**(a) `Status`.** After the marker paragraph (`:12-17`, ending *"§13 reproduces the measurements that matter."*), inside
the same blockquote, add:

```markdown
> [§14](#14-after-129-and-134--corrections-and-where-the-findings-went), appended after #129 and #134, corrects what
> §1, §2.1, §8, §9 and §13.3 say about GitHub's advisory database and what F-2 says about Dependabot, and records the
> correction mark at §4.10.
```

**(b) The §4.10 correction mark** (`:847-848`):

```markdown
    - ~~a sentence saying that everything between the header and §13 is the body as approved in review round <n>, kept
      byte-exact rather than corrected;~~ *Correction mark ([§14.3](#143-the-placeholder-at-410)): the struck text
      reads "review round" and then `<n>`, a placeholder outside code that the site renders as nothing. The round was 3.*
```

The approved text is kept; only the two `~~` pairs and the mark are new.

**(c) `## 14.` appended after the file's last line.** Draft; the build fills in its base commit and its own re-query
times.

```markdown
## 14. After #129 and #134 — corrections, and where the findings went

*Appended after issues [#129](https://github.com/kangwoo/aimon-core/issues/129) and
[#134](https://github.com/kangwoo/aimon-core/issues/134), at `main` `<base>`. Everything between the header and §13 is
still the approved body, byte-exact, except for the correction mark at §4.10 (§14.3). This section records where that
body and §13 are wrong about GitHub's advisory database and about Dependabot, and where F-1, F-3, F-6, F-7 and F-8 went.*

### 14.1 GitHub's advisory database has both CVEs — unreviewed, naming no package

§1 ("The two newest have no entry in GitHub's advisory database or OSV"), the GHSA column of §2.1's table, the §4.1 and
§4.8 drafts, §8 F-4, §9 question 3, §12 round 2 and §13.3 all say the database has no advisory for CVE-2026-13006 or
CVE-2026-19880. It had one for each before this record was written:

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
`CHANGELOG.md` now say what the database returns (#129). Queried <time>.

### 14.2 F-2 — Dependabot computed a Logback bump in every run, and the open-PR limit dropped it

F-2 says no Dependabot PR ever touched Logback, and calls 1.5.13 → 1.5.38 patch-level. The three Gradle update jobs
before this record were runs 33366906463 and 33369271715 (2026-08-31) and 34069303184 (2026-09-07). Each logs
`Updating ch.qos.logback:logback-classic from 1.5.13 to 1.6.3` — a minor, which `production-patches` does not take — and
submits it as an individual PR. It was the 7th, 23rd and 6th of 29, 24 and 22 submissions. The jobs called
`create_pull_request` 29, 24 and 24 times, and five PRs appeared each time: `open-pull-requests-limit: 5`. That setting is
now 50, above what one run can submit (#129).

### 14.3 The placeholder at §4.10

C-2 records that §4.10's "review round `<n>`" renders as "review round ,". §4.10 now carries the correction mark
[`../README.md`](../README.md) §3.4 allows: the approved sentence stays, struck through, and the mark says what stood
there. With the header, that mark's added text and §13–§14 removed, the body is byte-identical to the approved design.

### 14.4 Where F-1, F-3, F-6, F-7 and F-8 went

- **F-1** — both `@Suppress("UnstableApiUsage")` are gone (#134). §3.2 kept them because deleting
  `aimon.java-conventions.gradle.kts:15` "shifts `:116` and `:128-133`, which another record cites accurately". Under
  §3.4 as merged with #123, a citation whose tree can be restored is dated rather than wrong, and every accurate citation
  below `:15` is dated to an older commit. `:buildSrc:compileKotlin` gives the same warnings before and after.
- **F-3** — the build still runs no dependency scanner. The note at the top of `gradle/libs.versions.toml` says that a
  version inside an advisory range is found by reading, and where to look. Enabling Dependabot alerts was proposed to the
  maintainer in #129's pull request; it is not a change in the repository.
- **F-6** — six of its nine citations now name what they describe (#134): `architecture-review-open-items.md` `:114` and
  `:561`, `roadmap.md:69`, `spring-boot-starter-open-items.md` `:903` and `:1358`, `integration-test-layers.md:240`. The
  other three stay: `reasoning-effort-config-surface.md:969` was accurate, `openai-model-capabilities.md:433` is in an
  approved body, and `architecture-review-open-items.md:393` quotes the file as it was before `5801997`.
- **F-7** — the bundled `logback.xml` no longer defines the `CONSOLE` appender. Launched from the built distribution,
  the CLI printed <n> Logback status lines to stdout before and none after; log output still goes only to
  `~/.aimon/logs/aimon.log` (#134).
- **F-8** — `aimon-sample-app` sets Spring Boot's `logback.version` from the catalog and packs 1.6.3; both fat jars
  start (#129).
```

Anchors: `docs_tree.slug` drops `#` and `—`, so the §14 slug is
`14-after-129-and-134--corrections-and-where-the-findings-went` (the same double hyphen as §13's), and §14.3's is
`143-the-placeholder-at-410`. `check-doc-links.py` verifies both (§7.2). Every `<n>` and `<model>` the new text writes
is inside backticks.

### 4.10 `docs/design/llm/openai-model-capabilities.md`

**(a) The §2.5 correction mark**, inside `:271-273`, with no line added:

```markdown
~~The wording therefore states only that the value *is set on this request*: "temperature 0.7 is set on
this request but <model> does not accept sampling parameters; it is being omitted and the call will
succeed without it."~~ *Correction mark ([§14](#14-a-correction-mark-in-25-134)): the struck quote writes the model as `<model>`, a placeholder outside code that the site renders as nothing; the message names the request's resolved model there.* True for both origins, and it still tells the operator exactly what to look
```

`:274` ("for.") and every other line are untouched. The `Status` is unchanged: it already names §8 as the boundary, and
§3.4 counts every numbered section after it as appended.

**(b) `## 14.` appended after the last line:**

```markdown
## 14. A correction mark in §2.5 (#134)

*Appended after issue [#134](https://github.com/kangwoo/aimon-core/issues/134), 2026-09-11. §2.5 now carries a
correction mark, the one kind of text [`../README.md`](../README.md) §3.4 allows before this record's §8 boundary
besides `Status`.*

§2.5 quotes the warning as "temperature 0.7 is set on this request but `<model>` does not accept sampling parameters; …",
with `<model>` outside code, so the site renders it as an empty element and the sentence reads "…on this request but
does not accept…". The placeholder stands for the model name the request resolves to, which the message names there
(§8.2: `applySamplingParameters` takes the resolved model name). The approved sentence stays in place, struck through,
and the mark beside it says what stood there. Nothing else before §8 changed, and no line moved.
```

### 4.11 The six citations (#134 item 5): one line each, no reflow, no line added

| File:line | Current | Draft |
|---|---|---|
| `docs/backlog/architecture-review-open-items.md:114` | `측정 장치(\`-Xdoclint:none\`, \`aimon.java-conventions.gradle.kts:21\`)를` | `측정 장치(\`-Xdoclint:none\`, \`aimon.java-conventions.gradle.kts\` 의 \`tasks.withType<Javadoc>()\` 블록)를` |
| `docs/project/roadmap.md:69` | `(\`buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:21\`)` | `(\`buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts\` 의 \`tasks.withType<Javadoc>()\` 블록)` |
| `docs/backlog/architecture-review-open-items.md:561` | `` `aimon.java-conventions.gradle.kts:7` 이 플러그인을 붙이고 `:149` 가 `JacocoReport` 를 설정하지만`` | `` `aimon.java-conventions.gradle.kts` 의 `plugins { }` 블록이 `jacoco` 를 붙이고 `tasks.withType<JacocoReport>()` 블록이 리포트를 설정하지만`` |
| `docs/backlog/spring-boot-starter-open-items.md:1358` | `(\`aimon.java-conventions.gradle.kts:16\`)` | `(\`aimon.java-conventions.gradle.kts\` 의 \`java { toolchain { … } }\` 블록)` |
| `docs/backlog/spring-boot-starter-open-items.md:903` | `snakeyaml **2.2**(\`libs.versions.toml:57\`) 로 직접 돌려 확인했다.` | `snakeyaml **2.2**(그때의 핀이다. 지금 \`libs.versions.toml\` 의 \`snakeyaml\` 은 2.7 이고, 2.7 에서는 다시 돌리지 않았다) 로 직접 돌려 확인했다.` |
| `docs/design/agent-execution/integration-test-layers.md:240` | `(\`aimon.java-conventions.gradle.kts:77-79\`)` | `(\`aimon.java-conventions.gradle.kts\` 의 \`tasks.withType<Test>()\` 블록 — \`minHeapSize\` · \`maxHeapSize\`)` |

- `:561` sits in R-3, closed on 2026-09-02. Its *왜* describes the tree before the floor existed, and the names are
  accurate for what the sentence cites: the plugin and the report configuration.
- None of the four files has a translation twin (§2.8; `backlog/`, `project/` and `design/` are not translation targets
  today).

### 4.12 Not changed, deliberately

| File | Why |
|---|---|
| `docs/design/testing/test-classpath-shipped-versions.md` §11.5 (*"`@Suppress("UnstableApiUsage")` sits on `VersionCatalogsExtension`"*) | Not this run's file. The sentence is in a post-boundary section dated to the commit that wrote it, where it was true. Recorded in §8 |
| `samples/aimon-sample-app/README.md` | Says nothing about Logback |
| `.github/workflows/build.yml` | D2 adds no job; the `docs-links` job belongs to another run |
| `.github/workflows/dependabot-auto-merge.yml` | Not this run's file, and D3 needs no change to it |
| `CONTRIBUTING.md`, `SECURITY.md` | Not this run's files. A pointer from either to the catalog note is proposed in §8 |
| `docs/design/README.md` | No new record, so no index row; §3 belongs to `design-record-rule-followups` |

---

## 5. Data and interface shapes that change

No Java type, public API, configuration key, wire name or persisted identity changes.

| What | Before | After | Who observes it |
|---|---|---|---|
| `aimon-sample-app` fat jars' `BOOT-INF/lib` | `logback-{classic,core}-1.5.34.jar` | `logback-{classic,core}-1.6.3.jar` | Anyone running the sample (`bootRun`, the jars). Unpublished |
| `aimon-sample-app` Gradle project property | none | `logback.version` = the catalog's `logback` | The dependency-management plugin |
| CLI stdout at start-up, before the REPL's first output or a start-up error | 30 Logback status lines and a blank line | nothing | Every CLI user |
| CLI log destination | `~/.aimon/logs/aimon.log` only | the same | — |
| `.github/dependabot.yml` `gradle.open-pull-requests-limit` | 5 | 50 | The maintainer's PR list, from the first Monday run after merge |
| Catalog and `dependabot.yml` comments | — | the reading note | Contributors |
| `CHANGELOG.md` `[Unreleased]` | — | one new section; one sentence of #127's entry corrected | Release readers |

---

## 6. Failure modes and how they are handled

| # | Failure | Handling |
|---|---|---|
| 1 | The dependency-management plugin ignores `extra["logback.version"]` (a Boot or plugin change renames the property), and the sample silently returns to Boot's Logback | `dependencyInsight` shows it. The build checks it before and after (§7). Within Boot 3.5.x the property name has not moved, and Boot majors are ignored by `dependabot.yml`. Not guarded by a test: the only way to lose the override is a Boot upgrade, which is a visible catalog edit, and `packagingTest` would not notice a return to 1.5.x anyway. Stated in §9 Q2 as the option not taken |
| 2 | A catalog bump brings a Logback that Boot 3.5.x cannot start | CI's `build` job runs `packagingTest`, which launches both fat jars and the exploded layout, and goes red. The sample comment says this is the check |
| 3 | Boot later manages a newer Logback than the catalog | The override pins the sample below Boot's. The sample comment and the catalog note both say to keep `logback` at or above Boot's |
| 4 | The build's launch of the fat jars on 1.6.3 fails where the design's passed (for example on CI's Linux runner) | D1 falls back to keeping 1.5.34. The reason is recorded beside `:49-53`, naming both CVEs and what each needs, as #129 allows, and the build records it as a departure. The design does not implement both |
| 5 | Removing `CONSOLE` hides a status Logback should print | No. Only the WARN it caused goes away. An ERROR or a different WARN still prints through `printInCaseOfErrorsOrWarnings`. That is why `NopStatusListener` was rejected |
| 6 | A user who copied the bundled `logback.xml` still has the unreferenced appender | Their file is theirs, and nothing breaks: their start-up prints the status block as before. The CHANGELOG bullet names the cause, so they can fix their copy |
| 7 | Raising the Dependabot limit floods the PR list and the CI queue on the first Monday | Estimated about 20 PRs × 18 job-minutes (§2.3). The CHANGELOG bullet warns the maintainer. The fallback is keeping 5 and recording the drop, a one-line revert the PR body offers |
| 8 | A future catalog grows past 50 update units | The `dependabot.yml` comment says so and names the only sign: a job log with more `create_pull_request` calls than new PRs |
| 9 | `dependabot.yml` has a syntax error | Dependabot validates only after merge, in its UI; runs cannot be triggered from here. The change is one scalar and comments. The build parses the file with `python3 -c 'import yaml,sys; yaml.safe_load(open(sys.argv[1]))'` |
| 10 | The reading note goes stale when GitHub reviews the two advisories or the maintainer enables alerts | The note is dated, and it says what alerts would and would not cover. The PR body asks the maintainer to adjust the dated sentence if they enable alerts |
| 11 | The struck-through text does not render across the line break in `~~…~~` | Python-Markdown inline processors match across the paragraph's soft line breaks. The build checks the rendered page anyway (`mkdocs build --strict` into `$RUN_DIR`, then grep for `<del>` around the approved text and for the mark's `<code>&lt;n&gt;</code>` / `<code>&lt;model&gt;</code>`). If it does not render, the build records that and stops, rather than rewrapping approved text |
| 12 | Deleting `buildSrc:15` makes a citation wrong | Every citation below it is dated or named (§3.5). The six stale ones become names. Merge notes list the moved lines, including #131's `:130-131` |
| 13 | A sibling run's merge moves `CHANGELOG.md`, the register counts or the README index | Expected by the task. Resolved at merge time by recounting from the body |
| 14 | The CLI launch path used to measure D4 stops initialising Logback before the configuration error (the order of `create()` changes) | The probe path, the distribution's `lib/` loaded directly, measures the same thing without depending on that order. The build runs both |

---

## 7. Test strategy

**Every command:**
- runs from the worktree unless it says otherwise;
- runs under `env -u ANTHROPIC_KEY -u OPENAI_KEY`, after checking `env | grep -E '^(ANTHROPIC|OPENAI)_KEY'` is empty;
- records its time and output under `$RUN_DIR/build/`.

Any CLI launch sets `JAVA_OPTS=-Duser.home=$RUN_DIR/build/home-<label>`, so the user's real `~/.aimon/logs` is never
written.

### 7.1 Before the first edit

1. **Advisories**, re-queried with times, into `build/measurements.md`, repeating §2.1's table:
   - `gh api advisories/GHSA-567r-vvh5-jjr8` and `/GHSA-9mh8-hq67-v26g`;
   - `gh api 'advisories?ecosystem=maven&affects=ch.qos.logback:logback-core&type=unreviewed'` and the `reviewed` /
     `logback-classic` variants;
   - NVD `cveId=` for both CVEs;
   - OSV `/v1/vulns/` for the four ids, and `/v1/query` for both artifacts at 1.5.34 and 1.6.3.

   If any answer differs from §2.1 (for example a GHSA now reviewed), the catalog, CHANGELOG and §14.1 wording follows
   the new answer, and the build records the change.
2. **Sample**: `./gradlew -q :aimon-sample-app:dependencyInsight --configuration runtimeClasspath --dependency <d>` for
   `ch.qos.logback:logback-core`, `ch.qos.logback:logback-classic`, `org.slf4j:slf4j-api` and `org.codehaus.janino`.
   Predict 1.5.34, 1.5.34, 2.0.18, no match.
3. **CLI**, on `logback.xml` as on `main`: `./gradlew :aimon-cli:installDist`, then both measurements from §2.6.
   - Probe: `java -Duser.home=… -cp 'modules/aimon-cli/build/install/aimon-cli/lib/*' LogbackStatusProbe.java`, with the
     probe copied from `$RUN_DIR/design/probe/`.
   - Launch: `modules/aimon-cli/build/install/aimon-cli/bin/aimon-cli --config $RUN_DIR/build/unsupported-provider.yaml < /dev/null`.

   Record stdout line count, `|-` count, WARN and ERROR counts, stderr, exit code, and whether `aimon.log` exists. Predict
   31 / 30 / 1 / 0, `Configuration error: Unsupported LLM provider: no-such-provider`, 1, yes.
4. **`buildSrc` warnings**: `./gradlew help --rerun-tasks --console=plain 2>&1 | grep -E '^w: '` (`--rerun-tasks` forces
   `:buildSrc:compileKotlin`; confirm the task appears in the output). Predict the one `aimon.publishable.gradle.kts:56:17`
   line.
5. **Gate baseline**: delete `modules/*/build/test-results/test` and `samples/*/build/test-results/test`, then
   `./gradlew checkAll --continue`. Record modules, tests, failures, errors and skips per module from the XML. #127
   measured 21 modules, 10,946 tests, 72 skipped; the count may differ on `2eddf3d`, so measure it rather than assume it.
6. **Unit count for D3**: count the update units in `gradle/libs.versions.toml` — distinct version refs used by
   `[libraries]` and `[plugins]`, plus inline `version = "…"` libraries, plus `gradle-wrapper` — and add 3 for the groups.
   Predict at most 49. If it exceeds 50, take the next multiple of ten and record the departure (§3.3).

### 7.2 After the edits

1. `./gradlew format`; record whether anything changed, and read the diff if so.
2. **Sample**:
   - Repeat 7.1.2. Predict `1.6.3 (selected by rule)` for core, `1.5.34 -> 1.6.3` for classic, slf4j 2.0.18, no Janino.
     Repeat it on `testRuntimeClasspath`.
   - `unzip -l` both jars in `samples/aimon-sample-app/build/libs/`. Predict both pairs at 1.6.3, and no `-1.5.34` jar.
3. **The launch, the way CI runs it**: `./gradlew --no-daemon packagingTest --rerun` (CI's step is
   `./gradlew --no-daemon packagingTest`; `--rerun` defeats up-to-date).
   - Record `FatJarPackagingTest`'s tests, failures, errors and skips from its XML. Predict 6 / 0 / 0 / 0.
   - Grep the output for `NoSuchMethodError`, `ClassNotFoundException` and `LinkageError`. Predict none.
   - This is the measured start D1 requires. Linux is measured by the PR's CI run; the PR body says which was measured
     where.
4. **CLI**: `./gradlew :aimon-cli:installDist`, then both §2.6 measurements again.
   - Predict 0 / 0 / 0 / 0 on stdout, identical stderr and exit code, and `aimon.log` created.
   - Also check that the probe's WARN line is in the probe's `aimon.log`, so logging still reaches the file.
   - Confirm `unzip -p lib/aimon-cli-*.jar logback.xml` has no `CONSOLE` and has the same line count as on `main`
     (`wc -l`: 29).
   - `git grep -n CONSOLE -- modules/aimon-cli` shows only the comment, if the comment names it (the draft does not).
5. **`buildSrc`**:
   - Repeat 7.1.4. Predict the identical single `w:` line.
   - `git grep -n 'UnstableApiUsage' -- buildSrc`: empty.
   - `javap -v` over the wrapper's `gradle-core-api-9.2.1.jar` for `org.gradle.api.artifacts.VersionCatalogsExtension` and
     `VersionCatalog`, and over `gradle-kotlin-dsl-9.2.1.jar` for `org.gradle.kotlin.dsl.ProjectExtensionsKt`:
     `grep -c Incubating` = 0 for each, as in §2.7.
6. **`dependabot.yml`**: `python3 -c 'import yaml,sys; d=yaml.safe_load(open(sys.argv[1])); print([(u["package-ecosystem"], u["open-pull-requests-limit"]) for u in d["updates"]])' .github/dependabot.yml`.
   Predict `[('gradle', 50), ('github-actions', 3), ('pip', 3)]`.
7. **Residue greps**:
   - `git grep -n -E 'listed neither|has a GitHub advisory yet' -- gradle CHANGELOG.md`: empty.
   - `git grep -n -E '(java-conventions\.gradle\.kts|libs\.versions\.toml)`?:[0-9]' -- docs`: only the dated citations
     §3.5 lists, plus `openai-model-capabilities.md:433` and `skill-loop-truncation-and-fork-stall.md:134` and
     `provider-key-census-claim-and-inputs.md:75` (not in #134's list; §8), and none of the six.
8. **Approved bodies unchanged**:
   - #127's record: take the file, remove the `Status` sentence added in §4.9(a), strip `~~` and the mark's added text
     from §4.10's two lines, and cut at `## 14.`. The result must be byte-identical to `git show 2eddf3d:<file>`.
   - `openai-model-capabilities.md`: stripping `~~` and the mark from `:271-273` and cutting `## 14.` must give `2eddf3d`'s
     bytes, and `wc -l` before `## 14.` must equal `2eddf3d`'s 1576.
   - Script it; do not eyeball it.
9. **Gate**: delete the unit test results again, then `./gradlew checkAll --continue`. Predict the same numbers as 7.1.5:
   no Java source changes, and the sample's only tests are `@Tag("packaging")`, which `test` excludes.
10. **Docs**, all four checks:
    - `python3 scripts/check-doc-links.py` — the new `§14` and `§14.3` anchors and both issue links;
    - `python3 scripts/check-backlog-registers.py` — `module-dependency-scope.md` still 3 / 3 / 0 / 0, recounted from the
      body;
    - `python3 scripts/check-translation-staleness.py`;
    - `python3 scripts/check-translation-structure.py`.
11. **Rendering**: `mkdocs build --strict -d $RUN_DIR/build/site` (not into the worktree).
    - In `site/design/testing/shipped-logback-and-test-classpath-followups/index.html`: the §4.10 item's approved text
      inside `<del>`, and the mark's `<code>&lt;n&gt;</code>`.
    - In `site/design/llm/openai-model-capabilities/index.html`: the quote inside `<del>`, and `<code>&lt;model&gt;</code>`
      in the mark.
    - Record both greps.
12. **Record** `git diff --stat` and a line-count delta per changed file, for the Merge notes.

---

## 8. Findings outside the two issues (for `build/deviations.md` and the PR body)

- **F-A — 35 OSV advisories on transitive dependencies of shipped and packed classpaths** (§2.4). GitHub's advisory API
  gives all 35 as `type: reviewed` (2026-09-11T09:57:18Z, `$RUN_DIR/design/probe/ghsa-types.txt`), so Dependabot alerts
  with dependency submission would show them.
  - In the CLI distribution: `httpclient5` 5.3.1 and `httpcore5` / `httpcore5-h2` 5.2.4, via the Anthropic SDK.
  - Netty through `aimon-session-redis` (Lettuce) and `aimon-sandbox-kubernetes`.
  - Bouncy Castle 1.82 through `aimon-sandbox-docker`.
  - Tomcat 10.1.55 (3 CRITICAL), `jackson-databind` 2.21.4 and `log4j-api` 2.24.3 in the sample, all Boot-managed.

  Not triaged for reachability; not this run's subject. For the maintainer. No reserved ID fits, since
  `module-dependency-scope.md` covers POM scope and test-classpath versions.
- **F-B — the sample resolves Boot's `jackson-databind` 2.21.4, not the catalog's 2.22.2.** It is the same mechanism
  #129 found for Logback, on another artifact. Not measured beyond the dump.
- **F-C — `docs/design/testing/test-classpath-shipped-versions.md` §11.5 says the marker "sits on"
  `VersionCatalogsExtension`.** After D5 there is nothing to sit. The sentence is post-boundary and dated to the commit
  that wrote it, so it is dated, not wrong. Not this run's file.
- **F-D — two more stale catalog citations outside #134's list**:
  - `skill-loop-truncation-and-fork-stall.md:134` cites `libs.versions.toml:109` for `jackson`, which is at `:114`;
  - `provider-key-census-claim-and-inputs.md:75` cites `:153` for Spotless, which is at `:172`.

  The top-of-catalog note moves both further. Whether each sits in an approved body with an older base (dated) was not
  checked. Not this run's files; the first belongs to `fork-guard-record-followups`' record.
- **F-E — `CONTRIBUTING.md` and `SECURITY.md` do not mention how an affected dependency is found.** A one-line pointer
  to the catalog note from `SECURITY.md`'s "Vulnerabilities in third-party dependencies" is a natural follow-up. Not this
  run's files.
- **F-F — held majors surfaced by D3.** Run 3 computed nine majors that the repository may be holding on purpose:
  - `graal-sdk` 25 (the catalog records the JDK-17 line);
  - `junit-bom` 6 (the `junit` note says keep it equal to Boot's);
  - `mongodb-driver-sync` 5, both OpenSearch 3s, `HikariCP` 7, `jline` 4, `spotless` 8, and `spring-boot` 4, already
    ignored.

  After the limit rises they arrive as PRs. Recording each hold as an `ignore` with a reason in `dependabot.yml` is a
  per-dependency decision for the maintainer, not part of this change.

---

## 9. Open questions

1. **The openai record's `## 14.`** — the task's file table grants "the placeholder's correction mark" in that file, and
   §3.4 requires the mark to point at a post-boundary section that records the correction. **Chosen:** append a short
   §14 (§3.6, §4.10(b)). No other run touches the file, and no line before the new section moves. If the maintainer reads
   the grant strictly, the alternative is no mark there, with #134 item 3 closed for #127's record only (`refs #134` for
   that half).
2. **A guard that the sample packs the catalog's Logback** — for example a `FatJarPackagingTest` assertion on
   `BOOT-INF/lib/logback-core-<catalog>.jar`, or a build-script check. **Chosen: none.** The silent case needs a Boot
   upgrade that renames the property (§6 row 1). The maintainer may want it anyway; it would be a few lines in
   `samples/**`.
3. **Which configurations GitHub's automatic dependency submission includes for Gradle** — test classpaths or not. Not
   stated in GitHub's documentation and not measurable without enabling it. It affects only how noisy the proposed
   alerts would be, and the PR body names the `dependency-submission` workflow with
   `dependency-graph-exclude-configurations` as the alternative.
4. **Whether an IDE inspection flags `the<VersionCatalogsExtension>()` once the `@Suppress` is gone** — not measurable
   here; the build records `javap`'s `@Incubating` counts instead (§7.2.5).
5. **The Dependabot limit's number** — 50 is argued from the configuration's maximum per run (§3.3). 30, just above the
   largest run measured, would work today and fail silently the first time the catalog is further behind. 100 would say
   "no limit" without a reason. The maintainer may prefer either; the comment says how to tell when it is too low.
6. **The PR body's settings proposal** — whether to include Dependabot security updates. **Chosen:** include, marked
   optional. They bypass the PR limit, but they act only on reviewed advisories, so they would not have helped with
   Logback.

---

## 10. Closing — the PR body's shape

- `Closes #129` and `Closes #134`, one line each. Both are fully addressed. #129's reporting item takes the issue's second
  branch ("or the repository records that it relies on reading, and where to look"), which the issue offers.
- **One heading per decision** (D1–D6, including "CHANGELOG: edited in place, not superseded", "No new design record" and
  "No backlog item"), each with the option taken, the options rejected, and why, from §3.
- **For the maintainer, not changed here**: enable Dependabot alerts; enable automatic dependency submission; optionally
  enable Dependabot security updates; what they would and would not report (§3.2); and the dated catalog sentence to
  adjust if they do.
- **Measured where**: the sample's start (macOS locally; ubuntu in this PR's CI), and the CLI's status print (macOS,
  built distribution, real launch plus probe).
- **Merge notes** (line moves into files other runs or records cite):
  - `aimon.java-conventions.gradle.kts`: every line from `:16` moves up one, so #131's `:130-131` / `:130` become
    `:129-130` / `:129`. `aimon.spring-starter.gradle.kts`: every line from `:14` moves up one.
  - `gradle/libs.versions.toml`: the new note moves every line down by its length.
  - `.github/dependabot.yml`: lines move.
  - `modules/aimon-cli/src/main/resources/logback.xml`: no line moves (`:20` and `:26-28` hold).
  - `samples/aimon-sample-app/build.gradle.kts`: `:1-53` unchanged; lines from `:54` move.
  - `shipped-logback-and-test-classpath-followups.md`: lines from `:18` move down by the `Status` sentence, and from `:849`
    by the mark's line as well; `:8`, which #130 cites, does not move.
  - `openai-model-capabilities.md`: no line moves; §14 is appended.
  - The six citation lines: no line moves.
  - `CHANGELOG.md`: a new section at the top of `[Unreleased]`, and #127's entry reflowed at `:172-176`.
- **Findings outside #129 and #134**: §8.

---

## 11. After the build — departures and corrections

*Appended after the build. Everything above is the approved body, byte-exact. This section records where that body is
wrong, where the build departed from it, and what the build measured. The run's own records are not in the repository,
so the numbers that matter are reproduced here.*

### 11.1 Where the body is wrong

- **C-1 — §3.2's third reason for rejecting a scanner job is wrong.** "This build does not produce what these scanners
  read … Grype needs an SBOM": Grype (through Syft) and Trivy catalog the jar files in a directory, and this build
  produces such directories — the CLI distribution's `lib/` and both fat jars. The rejection stands on the other
  reasons: 35 findings on the first run, none of them Logback, and `dependency-review-action` sees only a pull request's
  diff. Design review round 1 found this. No committed file repeats the reason.
- **C-2 — §4.10(a) and §7.2.8 place the openai record's struck sentence at `:271-273`.** On `2eddf3d` it is on
  `:272-274` — `:273` holds `<model>` — and "for." is on `:275`, not `:274`. §7.2.8's "`wc -l` before `## 14.` must equal
  1576" would also have counted the new section's leading lines. The mark went on `:272-274`, no line was added, and the
  byte-identity check anchors on text (11.3).
- **C-3 — §4.11's table says "one line each", but `architecture-review-open-items.md:561`'s citation runs onto `:562`**,
  where "`JacocoReport` 를 설정하지만," begins. Applied to `:561` alone, the draft would have written that phrase twice.
  Both lines were edited together, and no line was added.
- **C-4 — §4.5's comment draft says "nothing Logback writes reaches the terminal".** That is false by §3.4's own
  argument: a WARN or ERROR status still prints through `printInCaseOfErrorsOrWarnings`, which is why `NopStatusListener`
  was rejected. The comment ships as "no log event reaches the terminal", and "any WARN or ERROR" where the draft said
  "any WARN".
- **C-5 — §4.2(a)'s last sentence says Dependabot alerts alone "report reviewed advisories against the resolved
  graph".** §2.2 measured that GitHub's dependency graph has no static Gradle support, so alerts see the resolved graph
  only with automatic dependency submission or a submission job. The note ships as "If Dependabot alerts and automatic
  dependency submission are turned on".
- **C-6 — §3.3 and §4.3 call 50 "above what this configuration can submit in one run".** True today — 46 units and 3
  groups make 49 (11.3) — and false at the next version ref the catalog gains, while the only sign §4.3 names is a job
  log §3.3 says nobody reads weekly. 49 is also a worst case, every unit behind at once; the grouping keeps real runs far
  below it (the largest measured submitted 29). 50 stays, and the comment calls it a bound above the worst case, not an
  expected count.
- **C-7 — §4.9(a)'s `Status` sentence and §4.9(c)'s §14.1 list different sites, and both miss one.** §14.1 names the
  §4.1 and §4.8 drafts and §12 round 2, and the sentence does not. Neither names §12 round 1, whose "GitHub's database has
  no entry" makes the same claim. The committed sentence says "the body and §13.3", and §14.1 names §12 rounds 1 and 2.
- **C-8 — §4.9(c)'s §14.3 leaves the two `~~` pairs out** of what must be removed for #127's body to be byte-identical.
  The committed §14.3 lists them.
- **C-9 — §4.7(a)'s heading names no area, and its Dependabot bullet says "each weekly run computed 22 to 29 updates".**
  Every heading under `[Unreleased]` opens with an area, and two of the three runs were on 2026-08-31. The committed
  heading opens "Dependencies:", and the bullet says each of the three Gradle runs so far "submitted 24 to 29 pull
  requests" — 29, 24 and 24 `create_pull_request` calls (§2.3).
- **C-10 — §2.5, §4.1's draft and §6 row 2 treat `packagingTest` as the check that Logback starts under Boot.** It
  starts the application on Boot's default logging setup. Boot's Joran extensions (`logback-spring.xml`,
  `<springProfile>`, `<springProperty>`) run only when a configuration file is loaded, and nothing in the build loads one.
  The build launched both fat jars on such a file by hand (11.3), and the committed comment says what `packagingTest`
  does not cover.
- **C-11 — §4.11's table puts two raw elements on this page.** Its drafts escape backticks inside code spans, which ends
  each span early, so `tasks.withType<Javadoc>()` and `tasks.withType<Test>()` leave `<Javadoc>` and `<Test>` outside
  code — the shape of #127's C-2. They are kept as approved. The four files the table describes were edited with the
  backticks it meant.

### 11.2 Where the build departed from the body

- **B-1 — this document is committed, reversing D6's "No new design record".** The pipeline that ran this change
  requires the approved design to land with the change it describes, which is also this directory's habit: the last
  twelve records added under `docs/design/` each arrived in their run's fix commit. The body keeps the sentences that
  reject committing it (§3.6, §4.12). `docs/design/README.md` gains this document's row in its `testing` table; its §3 is
  untouched.
- **B-2 — the comments and notes carry C-4's, C-5's and C-6's corrections and are reflowed to their files' widths**: the
  catalog's `logback` note to 120 columns around its three changed sentences, and `dependabot.yml`'s limit comment to
  that file's ~100. In the limit comment, "drops every other update it computed without a log line: the job log shows
  one `create_pull_request` call per update" became "without saying so: the job log still shows a
  `create_pull_request` call for each", which says the same without reading as a contradiction.
- **B-3 — the sample's comment says what `packagingTest` starts** (C-10): "Both fat jars start on it, on Boot's default
  logging setup", and it would not catch a failure only a `logback-spring.xml` shows.
- **B-4 — the `CHANGELOG.md` section follows C-9**, names this document in its "Records" bullet, mentions the hand launch
  on a `logback-spring.xml`, and tells a user whose replacement `logback.xml` was copied from the old one that it keeps
  printing the status (§6 row 6). Where §4.7(a) says "no published module's POM or module metadata changes", it says "no
  version a published module declares changes": the catalog's parsed values are identical to `2eddf3d`'s, which is what
  was checked, and no POM was regenerated.
- **B-5 — #127's §14 fills its draft's placeholders** (`main` `2eddf3d`, the re-query's UTC window), calls the limit "a
  bound above the most one run could submit on the catalog as it stands" (C-6), and has F-3 propose alerts "with
  automatic dependency submission" (C-5).
- **B-6 — measured beyond §7:** `dependencyInsight` before the edits on `testRuntimeClasspath` as well (§7.1.2 names
  `runtimeClasspath` only), and the hand launch of both fat jars on a `logback-spring.xml` (C-10).
- **B-7 — `packagingTest` ran as CI's exact command**, `./gradlew --no-daemon packagingTest`, after deleting every
  `build/test-results/packagingTest` directory, rather than with §7.2.3's `--rerun`. The deletion is what makes the task
  run.

### 11.3 What was measured

All on macOS arm64, with Homebrew OpenJDK 17.0.15 for the CLI and the probes and the Gradle 9.2.1 wrapper, and with no
`ANTHROPIC_KEY` or `OPENAI_KEY` in the environment, checked before each chain. Linux is measured by the pull request's CI
run.

**Advisories, re-queried before the first edit** (2026-09-11, 10:16:50–10:17:13 UTC) — identical to §2.1's table.
GHSA-567r-vvh5-jjr8 and GHSA-9mh8-hq67-v26g are `unreviewed`, with `vulnerabilities: []`. `affects=` lists neither for
either artifact, under `type=reviewed` (10 advisories for `logback-core`, GHSA-25qh-j22f-pwp8 among them, and 2 for
`logback-classic`) or `type=unreviewed` (`[]`). NVD has both *Deferred*, with no configurations. OSV answers
"Vulnerability not found" for all four ids and `{}` for both artifacts at 1.5.34, 1.6.2 and 1.6.3. `GET
…/dependabot/alerts` still answers 403, "Dependabot alerts are disabled for this repository" (10:27:48 UTC).

**The sample.** `dependencyInsight` before → after, on `runtimeClasspath` and `testRuntimeClasspath` alike:
`logback-core` 1.5.34 → **1.6.3** (selected by rule); `logback-classic` 1.5.34 → **1.6.3**, the tree showing `1.5.34 ->
1.6.3`; `slf4j-api` 2.0.18 both times; Janino "No dependencies matching" both times. Both fat jars,
`aimon-sample-app-0.2.4.jar` and `-classic.jar`, pack `logback-classic-1.6.3.jar`, `logback-core-1.6.3.jar`,
`slf4j-api-2.0.18.jar`, `jul-to-slf4j-2.0.18.jar` and `log4j-to-slf4j-2.24.3.jar`, and no 1.5.34 jar.
`./gradlew --no-daemon packagingTest`: BUILD SUCCESSFUL in 1m 3s, `FatJarPackagingTest` **6 tests, 0 failures, 0 errors,
0 skipped**, and no `NoSuchMethodError`, `ClassNotFoundException`, `NoClassDefFoundError` or `LinkageError` in its
output.

**Boot's Joran extensions, by hand** (C-10). Each fat jar was launched with `--logging.config` pointing at a probe file
whose only appender sits inside `<springProfile name="!live">` and whose pattern prints a `<springProperty>` read from
`spring.application.name`. Both started ("Started SampleApplication in 1.1 seconds") and printed 23 lines through that
pattern with the property resolved to `aimon-sample-app`, with no WARN or ERROR status and no linkage error.

**The CLI** — §2.6's two measurements, before → after, both on the Logback 1.6.3 in the distribution's `lib/`:

| | stdout lines | status lines (`\|-`) | WARN | ERROR | stderr | exit | `~/.aimon/logs/aimon.log` |
|---|---|---|---|---|---|---|---|
| Probe | 31 → **0** | 30 → 0 | 1 → 0 | 0 → 0 | `PROBE-DONE`, identical | 0 → 0 | written both times; holds the probe's WARN line |
| Launch | 31 → **0** | 30 → 0 | 1 → 0 | 0 → 0 | `Configuration error: Unsupported LLM provider: no-such-provider`, identical | 1 → 1 | created both times |

The one WARN before was `Appender named [CONSOLE] not referenced. Skipping further processing.` The `logback.xml` in the
distribution's jar has 29 lines before and after, and no `CONSOLE` after.

**`buildSrc`.** `./gradlew help --rerun-tasks` ran `:buildSrc:compileKotlin` both times, and both give the same single
warning, `aimon.publishable.gradle.kts:56:17 Check for instance is always 'true'.` `git grep UnstableApiUsage -- buildSrc`
is empty. `javap -v … | grep -c Incubating` is 0 for `VersionCatalogsExtension` and `VersionCatalog` in
`gradle-core-api-9.2.1.jar` and for `ProjectExtensionsKt` in `gradle-kotlin-dsl-9.2.1.jar`, and 6 for the control,
`Configuration`.

**Dependabot.** `dependabot.yml` loads as `[('gradle', 50), ('github-actions', 3), ('pip', 3)]`. §7.1.6's recount: 44
version refs used by `[libraries]` and `[plugins]`, one inline version (`jetbrains-annotations`) and the wrapper — **46
units, 49 with the three groups**. The 71 coordinates run 34069303184 checked fall into exactly those 46 units, Boot's
`spring-boot-dependencies` under the `spring-boot` ref.

**Approved bodies**, by script and anchored on text. `openai-model-capabilities.md`, with the two `~~` pairs and the mark
removed and cut before the new §14, is byte-identical to `2eddf3d` (1576 lines). `shipped-logback-and-test-classpath-followups.md`,
with the new `Status` paragraph, the two `~~` pairs and the mark removed and cut before §14, is byte-identical to
`2eddf3d` (1310 lines). This document without its `Status` block is byte-identical to the approved design.
`git grep -E 'listed neither|has a GitHub advisory yet' -- gradle CHANGELOG.md` is empty. The line citations into the
conventions plugin and the catalog left in `docs/` are the dated ones §3.5 lists, `openai-model-capabilities.md:433`, and
§8 F-D's two.

**The gate.** `./gradlew format` changed nothing. `./gradlew checkAll --continue` ran with every module's unit-test
results deleted first: before the edits, BUILD SUCCESSFUL in 5m 13s; after, BUILD SUCCESSFUL in 3m 16s, with all 1,698
result files rewritten. Both give **21 modules, 10,984 tests, 0 failures, 0 errors, 72 skipped**, identical module by
module — `aimon-core` 8,201 with 2 skipped, `aimon-cli` 474, `aimon-spring-boot-starter` 261; the other 70 skips are in
`aimon-llm-anthropic` (25), `aimon-llm-openai` (17) and the two sandbox modules (14 each).

**Documents.** The four checks pass: `check-doc-links.py` (251 files, 2,532 relative links, 0 broken, this document's
links to §11 included), `check-backlog-registers.py` (every register's title and index row agree with its items;
`module-dependency-scope.md` still counts 3 items, 3 open), and `check-translation-staleness.py` and
`check-translation-structure.py` (32 translations, level and structurally identical). None of the documents this change
edits has a translation. `mkdocs build --strict` exits 0 with no warning. On the built pages, #127's §4.10 item and the
openai record's §2.5 quote sit inside `<del>`, and the two marks render `<code>&lt;n&gt;</code>` and
`<code>&lt;model&gt;</code>`; this page carries `<Javadoc>` and `<Test>` as raw elements (C-11), and no other.

### 11.4 The §8 findings and §9 open questions — where each went

- **No backlog item was registered, and D-4 and D-5 are unused.** `module-dependency-scope.md` covers POM scope and
  test-classpath versions against shipped ones, and nothing below is either (§3.6).
- **§8 F-A to F-F went to the pull request body for the maintainer**, with the repository settings §3.2 proposes:
  Dependabot alerts, automatic dependency submission, and optionally Dependabot security updates. None of them is a change
  in the repository.
- **§9 Q1** is answered by what was built: the openai record has its own §14. That record already points inline markers
  at its own appended sections — "Reversed (round 2 …)" at `:185`, `:254`, `:408` and `:603` name §9.1 or §9.3 — so a
  mark pointing into this document instead would have been the odd one.
- **§9 Q2 to Q6 stay here** and went to the pull request body. Q2, a guard that the sample packs the catalog's Logback,
  is about what a fat jar packs against the catalog, not about test classpaths against shipped versions, so it is not
  D-2's or D-3's subject. Q3 and Q4 cannot be measured from the repository. Q5 and Q6 are the maintainer's choices at
  review.
