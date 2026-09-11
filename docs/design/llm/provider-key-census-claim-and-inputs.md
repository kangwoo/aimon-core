# Design — #119: the release-gate records that still read as before #112, "exactly" against "at least", and a census Gradle can skip

> Status: **IMPLEMENTED** — the census's scanned sources as an input of `aimon-core`'s `test` task
> (`modules/aimon-core/build.gradle.kts`); "at least" in `ReleaseGateMatchesCiGateTest`, in #112's `CHANGELOG.md` entry
> and in backlog `LA-1`; and the records #112 left describing things as before it — the `gpt-5.6-terra` CLI example,
> `CONTRIBUTING.md` / `CONTRIBUTING.ko.md` § Test and § Quality Checks, `docs/project/publishing-guide.md`, the
> `/release` skill, `docs/overview/architecture.md` (ko + en) and `docs/project/api-stability.md`. Source: issue
> [#119](https://github.com/kangwoo/aimon-core/issues/119), a follow-up to
> [`provider-key-release-gate.md`](provider-key-release-gate.md) (#98).
>
> **[§11](#11-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §11 is the body as approved in design review
> round 1 (PASS, no blocking findings, eight non-blocking notes), kept byte-exact rather than corrected — the house
> habit in this directory, for the reason
> [`model-capability-binding-round-trip.md`](model-capability-binding-round-trip.md) gives — with one exception: one
> link in §4.4's proposed table was written relative to `docs/project/`, and its path now resolves from this directory
> (§11, DV-7). Its `file:line` citations and counts are at `main` `c561e17`. The review transcript (`review-1.md`) and the run records the body names
> (`TASK.md`, `$RUN_DIR/build/measurements.md`, `build/deviations.md`) are not in the repository; §11 reproduces what
> was measured.
>
> Nothing this work left open went to the backlog. `LA-2` stays open as it was, with one dated note.

Run `release-gate-record-followups`, design phase. Base `main` at `c561e17`. Every `file:line` below was re-read at that
tree during this phase. Nothing in the worktree was modified, and no Gradle command was run. The counts in §2 come from
`git` and `grep` alone.

---

## 1. The problem, in one paragraph

#112 (for #98) made `scripts/release.sh` refuse to start while `OPENAI_KEY` or `ANTHROPIC_KEY` is set. It moved the
quickstarts' key onto the command, and taught `ReleaseGateMatchesCiGateTest` to run the script in a sandbox and to
census the `@EnabledIfEnvironmentVariable` key gates under `modules/aimon-llm-*`. Six things around that change were
left describing the world as it was, or claiming more than the build checks:

- one CLI example still exports the key;
- two `CONTRIBUTING` sections still say `test` excludes only `docker`;
- the release skill and the publishing guide do not mention the refusal, and the guide also omits the Docker check;
- two overview pages describe the test as comparing task lists only;
- three sentences say the refused keys are *exactly* the census's gates, while the tests pin *at least*;
- the census reads test sources that are not inputs of the task that runs it, so a local build that adds a key gate
  there can skip the census and report green.

The first four are records to correct. The last two are the issue's open decisions (TASK "Open decisions" 1 and 2),
settled in §3.

---

## 2. Facts re-verified at `c561e17`

| Claim the design relies on | Where | Verified |
|---|---|---|
| The example exports the key, then runs the CLI | `modules/aimon-cli/examples/gpt-5.6-terra.yaml:3-4` | Yes. No test or build file reads `examples/` (`grep -rn "examples/" --include=*.java --include=*.kts modules` finds only an unrelated javadoc URL) |
| The quickstart wording to mirror | `README.md:147-152`, `docs/README.md:54-58`, `docs/README.en.md:62-67` | Yes |
| `test`'s exclusions, re-read from the build files | `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:130-131` (`excludeTags("docker")`, `excludeTags("packaging")`); `modules/aimon-browser-playwright/build.gradle.kts:24` (`excludeTags("playwright")`) | Yes. `git grep -n -E 'excludeTags\|exclude\(\|excludeTestsMatching\|filter \{' -- '*.gradle.kts'` finds no other exclusion on any `Test` task. The tags used in test sources are `docker`, `packaging` and `playwright` |
| Stale tag list in the test command | `CONTRIBUTING.md:58`, `CONTRIBUTING.ko.md:63` | Yes |
| Stale tag list in Quality Checks | `CONTRIBUTING.md:127-129` (the `checkAll` paragraph), `CONTRIBUTING.ko.md:130-132` | Yes. The doc-check sentences further down the same section (`:141-171` / `:144-169`) belong to the sibling run `doc-heading-reading-parity` |
| § Live-API tests already names the three tags and where each comes from | `CONTRIBUTING.md:94-97`, `CONTRIBUTING.ko.md:99-101` | Yes. That section is not this run's |
| The skill's list of gates omits the refusal | `.claude/skills/release/SKILL.md:10-13` | Yes. The file is tracked (`git ls-files`) |
| The guide's table of what the script enforces | `docs/project/publishing-guide.md:136-144` | Omits the refusal and Docker. Its quality-gate row still says `checkAll` alone (§9) |
| The script's order of operations | `scripts/release.sh`: key check `:63-95`; `cd` `:97`; `trap` `:112`; pre-flight `:115-127`; Docker `:134-135` (inside § 1 pre-flight); credentials `:138-152`; gate `:236-237` | Yes |
| The two overview descriptions | `docs/overview/architecture.md:638`, `docs/overview/architecture.en.md:668`, `docs/project/api-stability.md:206` | Yes. `api-stability.md` and `publishing-guide.md` have no translation (`docs/project/` is "not yet") |
| The "exactly" sentence in the test | `ReleaseGateMatchesCiGateTest.java:70-71` | Yes |
| **A fourth instance of the same claim** | `ReleaseGateMatchesCiGateTest.java:502`, `@DisplayName("the keys the release script refuses are the key gates in the provider modules")` | Found in this phase. See D1 |
| The CHANGELOG sentence | `CHANGELOG.md:125-127` ("The test also holds the refused set equal to …") inside #112's entry `:99-137` | Yes. The entry is under `[Unreleased]`; `[0.2.4]` starts at `:1983` |
| The backlog sentence | `docs/backlog/live-api-test-tier.md:157-158` ("…거부하는 변수 목록을 … 키 게이트와 같게 붙든다") | Yes. It sits in closed `LA-1`'s **어디** list. The existing `> **정정** *(2026-09-10, #90)*` block `:161-171` is the correction format this register uses |
| What the tests pin | `refusedKeysAreTheProviderModulesKeyGates` `:501-521` holds the census **equal** to `PROVIDER_KEY_VARIABLES` `:193`. The refusal cases `:395-432` run the script once per listed key, and `assertRefused` `:524-550` requires a refusal that names that key | So the script's refused set ⊇ the gates. Every refusal case clears the environment and sets only listed keys, so a script that also refused another variable would pass |
| The test's inputs | `modules/aimon-core/build.gradle.kts:64-72` declares `scripts/release.sh`, `build.yml`, `modules/*/build.gradle.kts`, `buildSrc/src/main/kotlin/*.gradle.kts` and the root `build.gradle.kts` | Yes. `git grep -n -E "inputs\.(file\|files\|dir)"` finds no other declaration for aimon-core |
| What the census scans | `:226-227` (`PROVIDER_MODULE_PREFIX = "aimon-llm-"`), `:669-688` (directories under `modules/` with that prefix → `src/test` → every `*.java`) | Equivalent glob, from `modules/`: `aimon-llm-*/src/test/**/*.java`. It matches 59 files today (anthropic 23, capability-testkit 4, openai 32) |
| What the tag scan scans | `:740-765`: every `*.java` whose path contains `/src/test/` under `modules/` and `samples/` | 1078 files |
| The inputs gap is already written in the javadoc | `:109-112` | Yes, for both scans. #112's design record (`docs/design/llm/provider-key-release-gate.md:240-243`, `:643-644`) left it as a deviation because `build.gradle.kts` was not that run's file. It was never rejected on its merits |
| A second, unlisted undeclared input | The test reads `.claude/skills/release/SKILL.md` (`:125`, `:291-352`); `build.gradle.kts:64-72` does not declare it | Found in this phase. Outside #119, see §9 and open question 1 |
| Precedent for input declarations | aimon-core's block as above; `modules/aimon-spring-boot-starter/build.gradle.kts:84-90` declares all of `docs/` for `AimonDocumentedPropertiesTest`, "same reasoning, and the same fix" | Yes. This build pays a re-run to keep a text guard honest |
| Configuration cache | Not enabled. No `org.gradle.configuration-cache` in `gradle.properties`; CI passes no flag; Gradle 9.2.1 | The existing inputs resolve `rootProject.file(…)` / `rootProject.fileTree(…)` inside `tasks.test { }` at configuration time, with `withPropertyName`. That is the convention to keep |
| Nothing produces files under those test sources | Spotless 6.25.0 (`gradle/libs.versions.toml:153`): `javap` on `SpotlessApply` / `SpotlessTaskService$ClientTask` shows only `@Internal` properties; `SpotlessTask`'s `@OutputDirectory` lives under `build/` | So a new input over `src/test/**/*.java` cannot trip Gradle 9's "implicit dependency" validation. §7 step 8 confirms it with one command |
| Translation pairs are level | `python3 scripts/check-translation-staleness.py` → 32 up to date, 0 stale, 0 unresolvable | `architecture.en.md` has `source_commit: d4608ba`, but the canonical's last commit is `53d14a1`, which touched both files. `CONTRIBUTING.ko.md` has `a1236c8`, but the canonical's last commit is `1ae856c` (a merge that changed both) |

**Sizes that price item 6** (`git` and `grep` only, so proxies — a commit is not a build):

- `@Test` annotations: 7923 of 10989 in the build are in `aimon-core` (72%).
- 171 non-merge commits since 2026-08-31. 29 touched `modules/aimon-llm-*/src/test`, and **9** of those touched nothing
  `:aimon-core:test` already takes as input (aimon-core, the two testkits it tests against, `release.sh`, `build.yml`,
  any build script, `libs.versions.toml`).
- 89 commits touched any module's or sample's test sources, and **29** of those touched none of those inputs.

---

## 3. Decisions

### D1 — Item 5: the three sentences (and the display name) say "at least". The tests are not changed

**Taken.** Reword the sentences to claim what is pinned: the script refuses *at least* every variable the provider
modules gate on. The places:

- the javadoc bullet `ReleaseGateMatchesCiGateTest.java:70-71`;
- `CHANGELOG.md:125-127`;
- `docs/backlog/live-api-test-tier.md:157-158`, with a dated 정정 block;
- the census test's `@DisplayName` `:502`, the same claim written a fourth time in the file the issue cites.

The method name `refusedKeysAreTheProviderModulesKeyGates` stays, because `LA-2`'s **어디** (`live-api-test-tier.md:227`)
cites it. Separately, `LA-2` gets one dated sentence saying what this means for it (§4.5).

**Rejected: pin equality.** "The script refuses nothing else" is a claim about every variable not on the list, and a
test can only name finitely many. Each way of writing it fails:

| Way | Why it fails |
|---|---|
| **A1** Run the script with the other gated variables set and require it to reach pre-flight | The only variables that can stand for "everything else" are `AIMON_DOCKER_IT` and `AIMON_KUBERNETES_IT`, the two `@EnabledIfEnvironmentVariable` gates outside the census (`DockerSandboxBackendIntegrationTest:38`, `KubernetesSandboxBackendIntegrationTest:40`). The test would encode `LA-2`'s "no", and the day `LA-2` answers "yes" it fails. TASK forbids deciding `LA-2` here, and a test is the most durable way to decide it |
| **A2** Read the `for name in ANTHROPIC_KEY OPENAI_KEY` loop and compare its words to `PROVIDER_KEY_VARIABLES` | The class's own rule (`:75-80`) is that the refusal is behaviour, so it is run rather than read. A scan passes a commented-out, inverted or unreachable check, and a second refusal elsewhere in the script passes a scan of that loop. The scan would pin spelling, and the sentence would still overclaim |
| **A3** Require that one arbitrary sentinel variable is *not* refused | Pins one name. A script refusing any other name still passes, so "exactly" would still claim more than is pinned |

**Why "at least" is the right claim, not just the available one.** The class exists for one direction of drift: a
release passing a gate *narrower* than CI's (`:48-49`). Over-refusal cannot do that. It stops a release from starting,
loudly, with a message naming the variable. The sentence's purpose clause — "so the next provider's key cannot join the
build without joining the refusal" — needs only ⊇, and ⊇ is fully pinned: the census equality puts every gate on the
list, and the refusal cases run the script for every entry on the list.

**Smallest change.** No public contract, wire or config name, or persisted identity changes. The alternative adds a
test. No `CHANGELOG` behaviour line is needed, because nothing observable changes.

**Checked and left:**

- **`:64-65`, "It names every key that is set and no other."** This is about the refusal *message*, not the refused set,
  and `assertRefused` pins it for every key the cases use: a set key is named, and an unset listed key is not. Printing
  a variable that is not set is not over-refusal.
- **The census failure message's "but %s refuses %s" (`:512`).** It prints the list the refusal cases check. It is
  diagnostic output, not a claim in the records.

### D2 — Item 6: declare the census's scanned sources as inputs of `aimon-core`'s `test`. The tag scan keeps its written gap

**Taken.** Add one declaration to the existing `tasks.test { }` block in `modules/aimon-core/build.gradle.kts`, in that
block's own idiom:

```kotlin
inputs.files(rootProject.fileTree("modules") { include("aimon-llm-*/src/test/**/*.java") })
    .withPropertyName("providerModuleTestSources")
```

- **It is derived from what the census scans, not from a list of modules.** The glob is the census's own walk
  (`PROVIDER_MODULE_PREFIX` → `src/test` → `*.java`), so a provider module is an input from the day its directory exists,
  whether or not `settings.gradle.kts` names it. §7 step 3f measures exactly that.
- **It keeps the existing conventions.** `rootProject.fileTree` resolved at configuration time and `withPropertyName`, as
  `moduleBuildScripts` does. No path sensitivity is set, matching this block rather than the starter's `RELATIVE`: the
  build cache is off, so path sensitivity changes nothing here, and consistency within one block is the cheaper thing to
  read.
- **Cross-references replace a self-check.** The two copies of "what the census scans" (the Java constant and the Gradle
  glob) point at each other in a comment on each side, as `RELEASE_SCRIPT` and `rootProject.file("scripts/release.sh")`
  already do. That is the precedent; neither side checks the other.

**Why declare rather than write it down.**

1. **The build has already decided this question three times.** `build.gradle.kts:54-57` says that without the
   declarations "the offending edit leaves `test` UP-TO-DATE and the guard never runs — the build reports green on
   exactly the change the test exists to catch". The starter repeats it for `docs/` (`:84-87`). "CI catches it" was true
   for every one of those inputs too, and each was declared anyway. A census over test sources is the same shape.
2. **The price is bounded and paid only when the census has something to read.** 59 files in three modules. A
   `checkAll` newly re-runs aimon-core's suite only after an edit to those sources that changes nothing else aimon-core
   depends on: 9 of 171 commits so far. Iterating on a provider module's own tests
   (`./gradlew :aimon-llm-openai:test`) never runs `:aimon-core:test` and pays nothing.
3. **The place a contributor adding a key gate would meet the note is not this run's.** That place is `CONTRIBUTING.md`
   § Live-API tests, and the census failure message already sends people there (`:514`). A note in § Quality Checks
   would be read by everyone and needed by almost no one.

**Rejected:**

| Option | Why not |
|---|---|
| **B1** Write the gap where contributors meet it (§ Quality Checks, or the test javadoc as today) | Reasons 1-3 above. The javadoc already carries the note, and the issue is evidence it was not where the next person looked. In this class's words, a note is a promise, not an invariant |
| **B2** Also declare the tag scan's sources (every `*/src/test/**/*.java` under `modules/` and `samples/`) | It would make aimon-core's suite (72% of the build's `@Test`s) re-run after a test-only edit in *any* module: 29 of 171 commits, more than three times B-taken's 9, charged to contributors who never touch the release. The gap predates #112, is already written in the javadoc, and was not put to this run (item 6 is "the key census"). Recorded in §9 with the price, for the maintainer to take up |
| **B3** `outputs.upToDateWhen { false }` on `:aimon-core:test` | Re-runs ~7.9k tests on every build |
| **B4** Move the scans into their own `Test` task with its own inputs | This test holds every CI task and the release gate to one list, so a new task means editing `build.yml`, `release.sh`, the root `checkAll` and the skill's `Quality gate =` line. That changes the gate's task list — a contract this whole class defends — to close a local-only gap |
| **B5** Pass the glob into the test as a system property, one source of truth | A run from an IDE loses the scope or needs a fallback, which is a second copy anyway. None of the existing inputs works that way |
| **B6** A small task that extracts the census's result into a file the test task takes as input (re-run only when the *answer* changes) | A second parser, in Kotlin, deciding whether the Java parser runs. Its misses would be silent skips: the failure mode #112's DV-5 paid for (a new name going unread while the old ones are still found). It is the shape to reach for if the price of B2 is ever worth paying |

**Observable change** (`CHANGELOG.md` and PR body): a `checkAll` after an edit to `modules/aimon-llm-*/src/test` now runs
`:aimon-core:test` where it used to report `UP-TO-DATE`. Nothing else changes. The build records the measured duration of
one `:aimon-core:test` run as the price (§7 step 5).

### D3 — Smaller choices the build would otherwise have to make

| # | Choice | Taken | Rejected, and why |
|---|---|---|---|
| D3a | How `CHANGELOG.md` corrects #112's sentence | **Edit it in place.** Add a separate bullet for D2's behaviour change, and one short "Records" bullet, both inside #112's entry | A superseding note, as #106's entry did for #92's (`CHANGELOG.md:45-47`). That precedent recorded a *later* behaviour change against sentences that were true when written. #112's sentence overclaimed from the day it was written, the entry is unreleased, and a supersede note would leave the disagreeing sentence standing, against acceptance criterion 2. A new `###` section: the change exists only to close a gap in the census that #112's "Pinned" bullet introduces, and that bullet's reader is the one who needs it |
| D3b | Where the Docker check goes in the guide's table | **Inside the `pre-flight` cell**, because `release.sh` checks it inside § 1 pre-flight and logs it there. The key refusal gets **its own first row**, because it is § 0 and runs before "Pre-flight checks". The table's rows then follow the script's sections | A separate Docker row. The cell text names no gate task, so it cannot contradict the stale quality-gate row (§9) |
| D3c | The skill | **The gates list at `:11-12` gains the refusal, and Notes gains one bullet.** Notes is where the other requirements (credentials, Docker, browser cache) live, and the bullet says whether to unset the key is the user's call. That matters because the refusal message itself prints an `env -u … scripts/release.sh` line an agent could otherwise run | Only the list. Step 1's "relay verbatim and stop" already handles the abort, but nothing says the printed remedy is not the agent's to take |
| D3d | Backlog | **No new item.** `LA-3` is unused: every out-of-scope finding in §9 belongs to another register or none, and TASK sends those to the PR body. **No register or index count changes** | Registering the tag-scan gap under `LA-3`: it is not a live-API-tier item |
| D3e | A design record under `docs/design/` | **None by default**, open question 2 | — |

---

## 4. Concrete changes, by file

Proposed text is given where wording matters. The build agent may rewrap lines; it should not change claims.

### 4.1 `modules/aimon-cli/examples/gpt-5.6-terra.yaml` — item 1

Replace `:3-4` with the key on the command. Keep one sentence of why, mirroring `README.md:150-152`:

```yaml
#   OPENAI_KEY=sk-... ./gradlew :aimon-cli:run --args="--config modules/aimon-cli/examples/gpt-5.6-terra.yaml"
#
# The key goes on the command rather than being exported, on purpose: while a provider key is exported,
# `./gradlew test` and `checkAll` in that shell can run the live-API tests too, and those calls bill.
# See CONTRIBUTING.md › Live-API tests.
```

`sk-...` stays as the quickstarts write it; it is a placeholder, not a value.

### 4.2 `CONTRIBUTING.md` + `CONTRIBUTING.ko.md` — item 2, one commit

Only the test command's comment and the `checkAll` paragraph of § Quality Checks.

- **EN `:58`:** `# All unit tests (excludes @Tag("docker"), @Tag("packaging") and @Tag("playwright"))`. At command
  level this is true for the whole `./gradlew test`; the paragraph below says where each exclusion comes from.
- **EN `:127-129`**, keeping the first two sentences:

  > `checkAll` is the single gate: it runs the format check, Checkstyle, **and** each module's `test` task. A separate
  > `./gradlew test` is no longer needed. Three tagged tiers stay out of it, because `test` excludes them:
  > `@Tag("docker")` (Docker/Testcontainers) and `@Tag("packaging")` (fat-jar launches), which the conventions plugin
  > excludes in every module, and `@Tag("playwright")` (a real browser), which `aimon-browser-playwright` excludes as
  > well. They run via `./gradlew integrationTest`, `./gradlew packagingTest` and `./gradlew playwrightTest`, and CI and
  > the release gate run all three.

  The last clause exists because "stay out of it" next to three tiers reads as "ungated" — the drift the release skill
  and this repository's R-7 were bitten by. It is verified: `build.yml:265,278,363,481` and `release.sh:237`.
- **KO `:63`:** `# 전체 단위 테스트 (@Tag("docker"), @Tag("packaging"), @Tag("playwright") 제외)`
- **KO `:130-132`:**

  > `checkAll` 이 유일한 게이트입니다. 포맷 검사, Checkstyle, **그리고** 각 모듈의 `test` 태스크까지 한 번에
  > 돕니다. `./gradlew test` 를 따로 돌릴 필요는 이제 없습니다. 태그가 붙은 세 계층은 `test` 가 빼므로 여기서도
  > 빠집니다 — 컨벤션 플러그인이 모든 모듈에서 빼는 `@Tag("docker")`(Docker/Testcontainers)와
  > `@Tag("packaging")`(fat jar 실행), 그리고 `aimon-browser-playwright` 가 더 빼는 `@Tag("playwright")`(실제
  > 브라우저)입니다. 각각 `./gradlew integrationTest`, `./gradlew packagingTest`, `./gradlew playwrightTest` 로 돌고,
  > CI 와 릴리스 게이트가 셋 다 돌립니다.

- **`CONTRIBUTING.ko.md` front matter:** set `source_commit` to `git log -1 --format=%h -- CONTRIBUTING.md`, run
  immediately before the commit (`1ae856c` today). The sibling run moves the same line, and that conflict is expected.
- No heading, table, fence or list changes, so the structure check is unaffected.

### 4.3 `docs/overview/architecture.md` + `.en.md` and `docs/project/api-stability.md` — item 4

The first two go in one commit with `source_commit`. `api-stability.md` may ride in the same commit.

- **`architecture.md:638`:**
  `| ReleaseGateMatchesCiGateTest | scripts/release.sh 가 CI 워크플로와 **같은** Gradle 태스크를 돌리고, 프로바이더 API 키가 환경에 있으면 시작하지 않는다 — 뒤의 것은 스크립트를 샌드박스에서 실제로 돌려 확인한다 |`
  (identifiers keep their backticks)
- **`architecture.en.md:668`:**
  `| ReleaseGateMatchesCiGateTest | scripts/release.sh runs the **same** Gradle task the CI workflow does, and refuses to start while a provider API key is in its environment — the second checked by running the script in a sandbox |`
- **`architecture.en.md` front matter:** `source_commit` = `git log -1 --format=%h -- docs/overview/architecture.md`
  (`53d14a1` today). Table row counts do not change.
- **`api-stability.md:206`:**
  `- 릴리스 게이트가 CI 게이트보다 좁지 않은지 (ReleaseGateMatchesCiGateTest — 같은 테스트가 릴리스 스크립트를 샌드박스에서 실제로 돌려, 프로바이더 API 키가 환경에 있으면 시작하지 않는 것도 확인한다)`

### 4.4 `docs/project/publishing-guide.md` — item 3, half

Table `:136-144` becomes six rows (D3b):

| 단계 | 하는 일 |
|---|---|
| 프로바이더 API 키 *(new, first)* | `ANTHROPIC_KEY`·`OPENAI_KEY` 가 환경에 있으면(빈 문자열이어도, `--dry-run` 이어도) 시작하지 않는다. 인자를 읽은 다음 가장 먼저 — `git` 을 부르기 전에 — 돌고, 설정된 변수의 **이름만** 출력한다. 라이브 API 테스트의 게이트가 그 키뿐이라, 키가 있으면 품질 게이트가 청구되는 호출을 하게 되기 때문이다 ([라이브 API 테스트](../../../CONTRIBUTING.ko.md#라이브-api-테스트)) |
| pre-flight | `main` 브랜치, 클린 워킹 트리, `origin/main` 과 동기화, Docker 데몬 응답(`docker info`), 태그 미존재 확인 |
| 크리덴셜 · 품질 게이트 · 확인 · 발행 → 커밋 → 태그 → 푸시 | unchanged |

The link form follows `docs/README.md:58`; `check-doc-links.py` verifies the Korean anchor.

### 4.5 Item 5's sentences (D1)

- **`ReleaseGateMatchesCiGateTest.java:70-71`**, the `<li>`:

  > the keys it refuses include every variable `@EnabledIfEnvironmentVariable` gates on in the provider modules' tests,
  > so the next provider's key cannot join the build without joining the refusal. That is "at least", not "exactly":
  > the refusal cases run over a list held equal to those gates, so a script that also refused a variable outside them
  > would pass here. Refusing more cannot narrow the gate, and whether the script should is backlog `LA-2`.

- **`:502`:** `@DisplayName("the keys the refusal cases run are the key gates in the provider modules")`
- **`CHANGELOG.md:125-127`**, replacing only the "The test also holds …" sentence:

  > The test also holds the keys those cases run on equal to the `@EnabledIfEnvironmentVariable` gates under
  > `modules/aimon-llm-*`, so the script must refuse at least those gates, and a new provider's key fails the build
  > until the script refuses it. A script that refused more would still pass.

- **`live-api-test-tier.md:157-158`:**

  > `ReleaseGateMatchesCiGateTest` 가 그 거부를 샌드박스에서 실제로 돌려 보고, 그 거부가
  > `modules/aimon-llm-*` 의 키 게이트를 **적어도** 모두 덮게 붙든다 — 거부 사례를 돌리는 변수 목록을 그 게이트와
  > 같게 붙들기 때문이다.

  After the existing #90 block (`:171`), add:

  > **정정** *(2026-09-11, [#119](https://github.com/kangwoo/aimon-core/issues/119))*: 위 **어디** 의
  > `scripts/release.sh` 줄은 처음에 *"거부하는 변수 목록을 `modules/aimon-llm-*` 의 키 게이트와 같게 붙든다"* 고
  > 적었다. 테스트가 붙드는 것은 같음이 아니라 **포함**이다 — 거부 사례를 돌리는 목록이 게이트와 같고, 스크립트가
  > 그 목록의 변수를 하나씩 거부하는지를 보므로, 그 밖의 변수를 더 거부하는 스크립트도 통과한다. 같음을 붙들려면
  > 거부하지 **않는** 변수를 테스트에 적어야 하는데, 그 자리에 올 변수는 `LA-2` 의 둘뿐이고 그렇게 하면 `LA-2` 를
  > 테스트로 결정하게 되므로 문장을 고쳤다. 같은 문장이 `CHANGELOG.md` 의 #98 항목과 테스트 javadoc 에도 있었고
  > 함께 고쳤다.

- **`LA-2`'s **어디** bullet `:227-228`**, appended:

  > *(2026-09-11, #119: 스크립트가 두 변수를 더 거부해도 지금은 어떤 테스트도 실패하지 않는다 — 테스트는 거부를
  > '적어도' 로 붙든다. 범위와 이름을 고치는 것은 그 새 거부를 **붙들기** 위해서다.)*

  This is the item D1 was weighed against, and the next person deciding `LA-2` reads `LA-2`, not `LA-1`'s **어디**. It
  decides nothing.

### 4.6 Item 6 (D2)

- **`modules/aimon-core/build.gradle.kts`.** Replace the sentence "`ReleaseGateMatchesCiGateTest` reads the release
  script and the CI workflow." (`:58`) with:

  ```kotlin
  // `ReleaseGateMatchesCiGateTest` reads the release script and the CI workflow, and its key census reads every
  // `.java` under `modules/aimon-llm-*/src/test`. That tree is declared below as a glob on the census's own
  // `aimon-llm-` prefix rather than a list of modules, so a provider module is an input as soon as its directory
  // exists; a wider census needs a wider glob here. The same test's tag scan reads every test source under
  // `modules/` and `samples/` and is not declared: that would re-run this module's suite, most of the build's tests,
  // after a test edit in any module. The test's javadoc says so.
  ```

  Then add D2's two lines inside `tasks.test { }`, after `ciWorkflow`.
- **`ReleaseGateMatchesCiGateTest.java:109-112`.** Replace from "And the provider modules' test sources …" to the end
  of the paragraph with:

  > The provider modules' test sources are inputs of this module's `test` task — its build script declares the same
  > `aimon-llm-*` tree this census walks, so widening the census means widening that declaration — and a change to them
  > re-runs this class. The tag scan's sources are not: they are every test source in the repository, and declaring
  > them would re-run this module's suite after a test edit in any module. So a local build that changes only another
  > module's `@Tag`s can report this test `UP-TO-DATE`; CI builds from a fresh checkout and does not.

- **`CHANGELOG.md`**, a bullet inside #112's entry, directly after "Pinned by running the script" (D3a):

  > - **A change to a provider module's test sources now re-runs that census locally** (#119). They were not inputs of
  >   `aimon-core`'s `test`, so a build that added a key gate under `modules/aimon-llm-*/src/test` and changed nothing
  >   else could report `ReleaseGateMatchesCiGateTest` `UP-TO-DATE` and stay green; CI, which builds from a fresh
  >   checkout, did not. They are declared now, as a glob on the census's own prefix rather than a list of modules.
  >   **The price:** a `checkAll` after such an edit also runs `aimon-core`'s suite, which it used to skip (measured:
  >   *<duration, tests>*). The same test's tag scan reads every test source in the repository and keeps its gap —
  >   declaring those would re-run that suite after a test edit in any module — and the test's javadoc says so.

  Plus one records bullet (adjust if §4.7 is refused):

  > - **Records** (#119). `modules/aimon-cli/examples/gpt-5.6-terra.yaml` puts the key on the command, as the
  >   quickstarts do. `CONTRIBUTING.md`'s test command and Quality Checks name all three tags `test` excludes, in both
  >   languages. `docs/project/publishing-guide.md` and the `/release` skill name the refusal, and the guide the Docker
  >   check. `docs/overview/architecture.md` (ko + en) and `docs/project/api-stability.md` describe the test as running
  >   the script as well as comparing tasks.

### 4.7 `.claude/skills/release/SKILL.md` — item 3, other half, **edited last** (TASK)

- **`:11-12`:** "enforces every safety gate (no provider API key in the environment, clean tree, on `main`, synced with
  origin, Docker daemon reachable, credentials present, quality gate)".
- **Notes**, a new bullet between the credentials bullet (`:76-77`) and **Docker must be running**:

  > - **No provider API key in the environment.** The script refuses to start while `ANTHROPIC_KEY` or `OPENAI_KEY` is
  >   set — even to the empty string, `--dry-run` included, before it calls `git`. The live-API test classes are gated
  >   on nothing but that key, so the quality gate would run them: billed calls, and a gate that can fail for a reason
  >   on the provider's side. Relay the refusal like any other abort; whether to `unset` the key or use the `env -u`
  >   form the message prints is the user's call.

- **Constraints from the test.** No line may contain `opt-in`, `outside both` or `outside the gate` together with a gate
  task name (`:186`, `:321-352`). `Quality gate = \`…\`` (`:85`) must stay intact. Both proposed texts satisfy this.
- **If the write is refused:** leave the file byte-identical and put both texts above, verbatim, in the PR body for the
  maintainer.

---

## 5. Data and interface shapes that change

| Surface | Change |
|---|---|
| `:aimon-core:test` inputs | **+1 property**, `providerModuleTestSources`: `rootProject.fileTree("modules") { include("aimon-llm-*/src/test/**/*.java") }`. Observable: the task re-runs after such an edit (D2, `CHANGELOG`) |
| JUnit report text | One `@DisplayName` string (`:502`). The method name, and so the XML `testcase` name and `LA-2`'s citation, are unchanged |
| `publishing-guide.md` table | 5 → 6 rows. No translation twin |
| Translation front matter | `architecture.en.md` `source_commit` `d4608ba` → the canonical's last commit; `CONTRIBUTING.ko.md` `a1236c8` → the canonical's last commit |
| Backlog | Text only: one sentence and one 정정 block in `LA-1`, one dated note in `LA-2`. Register title and index row stay "2 items (1 open · 1 closed)" |
| Unchanged | `scripts/release.sh`, `build.yml`, every task name and gate list, every test assertion, config keys, wire names, persisted identities, public Java API |

---

## 6. Failure modes and how each is handled

| Failure | Handling |
|---|---|
| A scratch probe file or a temporary `release.sh` edit is left in the tree and committed | Probe names start with `Scratch`. Each probe step ends with the removal and `git status --porcelain` expected empty; `release.sh` is restored with `git checkout -- scripts/release.sh`, then `git diff --quiet -- scripts/release.sh`. The gate runs only after both |
| A key exported in the build shell bills during `checkAll` | Before the first Gradle command, `env \| grep -c -E '^(OPENAI\|ANTHROPIC)_KEY='` must print `0`, and every Gradle command runs as `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew …`. No live call is needed and none is made. No probe needs a value: a gate names a variable, and the sandboxed refusal cases use their own sentinel |
| `release.sh` is run for real | Never. It runs only inside the test's sandbox harness |
| The skill guards run on a stale skill because `SKILL.md` is not an input (§9) | After the skill edit, run the class with `--rerun` and record it. A plain `checkAll` after the edit may report `:aimon-core:test` `UP-TO-DATE` and prove nothing about the skill |
| The `.claude/` write is refused | Leave the file unchanged, put §4.7's texts in the PR body, adjust the `CHANGELOG` records bullet, and use `refs #119` for the skill half (open question 3) |
| A wrong `source_commit`, or a pair split across commits | Read the SHA with `git log -1 --format=%h -- <canonical>` immediately before committing, and commit each pair together. The staleness check must still report 32 up to date, 0 stale, 0 unresolvable |
| The census's scope and the glob drift apart later (e.g. `LA-2` widens the census) | The local gap returns for the new scope only, and CI still catches it. Both sides carry a comment naming the other (§4.6), which is the precedent for this block. No self-check, for B5's reasons |
| Gradle 9 "implicit dependency" validation on the new input | Inspected: no task declares outputs under `src/test`. §7 step 8 confirms it in one invocation |
| A contributor runs with `--configuration-cache` | The new input uses exactly the idiom of the five existing ones: configuration-time `rootProject.fileTree`, no `project` at execution time. §7 step 9 is optional |
| The measured price is larger than expected | D2 stands, and the number goes into `CHANGELOG` and the PR body for the maintainer to overturn (open question 4) |
| Merge conflicts with sibling runs (`CONTRIBUTING.ko.md` `source_commit`, `CHANGELOG.md` `[Unreleased]`) | Expected by TASK; resolved at merge. Within `CONTRIBUTING`, touch only §4.2's lines |

---

## 7. Test strategy and measurements

All numbers and outcome lines go to `$RUN_DIR/build/measurements.md`, with the exact commands. Use `--console=plain` so
task outcome lines (`> Task :aimon-core:test UP-TO-DATE`) are recorded verbatim.
`FOCUS='at.aimon.core.architecture.ReleaseGateMatchesCiGateTest'`. Keep the `--tests` filter identical across a
measurement sequence, because the filter is itself a task input.

0. **Key hygiene**, as in §6, recorded as the count only.
1. **Before, on the untouched tree.** This reproduces the gap, so do it before editing `build.gradle.kts`.
   - a. `:aimon-core:test --tests "$FOCUS"` → executes; 12 tests, 0 failures.
   - b. Same command again → `UP-TO-DATE`. This is the control.
   - c. Create `modules/aimon-llm-openai/src/test/java/at/aimon/core/llms/openai/ScratchKeyGateProbe.java`:
     `@EnabledIfEnvironmentVariable(named = "SCRATCH_PROBE_KEY", matches = ".+") class ScratchKeyGateProbe {}`, with its
     import. Same command → expected **`UP-TO-DATE`**: the gap.
   - d. Same command with `--rerun` → the census **fails** naming `SCRATCH_PROBE_KEY`; the other 11 pass. So the skip in
     (c) was Gradle's, not the census's.
   - e. Delete the probe and confirm `git status --porcelain` is empty.
2. **Item 2's list, re-read:** `git grep -n -E 'excludeTags|includeTags' -- '*.gradle.kts'` → the three `excludeTags`
   lines in §2.
3. **After D2's declaration.**
   - a. `:aimon-core:test --tests "$FOCUS"` → executes (the build script changed); 12 pass.
   - b. Again → `UP-TO-DATE` (control).
   - c. Recreate the probe from 1c. Same command → **not `UP-TO-DATE`**. The task executes and the census fails naming
     `SCRATCH_PROBE_KEY`. This is TASK's required measurement.
   - d. Delete the probe → executes, 12 pass.
   - e. Again → `UP-TO-DATE`.
   - f. **Derived, not listed:** create `modules/aimon-llm-scratchprobe/src/test/java/ScratchKeyGateProbe.java` (a
     directory no settings file or list names) → executes, and the census fails naming the variable. Delete the
     directory → executes, 12 pass.
   - g. **No wider than the census:** create the same probe under
     `modules/aimon-sandbox-docker/src/test/java/at/aimon/sandbox/docker/` → **`UP-TO-DATE`**. Delete it.
   - Check `git status --porcelain` is empty after each deletion.
4. **D1's claim, measured.** Temporarily change `release.sh:64` to
   `for name in ANTHROPIC_KEY OPENAI_KEY AIMON_DOCKER_IT; do`. Run `--tests "$FOCUS" --rerun` → **12 pass**: over-refusal
   passes, which is what "at least" says. Restore and confirm with `git diff --quiet -- scripts/release.sh`.
5. **The price:** `:aimon-core:test --rerun`, unfiltered, once. Record the wall-clock (`BUILD SUCCESSFUL in …`) and the
   totals summed from `modules/aimon-core/build/test-results/test/*.xml` (tests, failures, skipped). This is the number
   the `CHANGELOG` bullet quotes.
6. **Item 5 wording:** after §4.5, `--tests "$FOCUS" --rerun` → 12 pass; the new display name appears in the output.
7. **Skill edit (last):** after §4.7, `--tests "$FOCUS" --rerun` → 12 pass, including both skill tests. Mandatory, for
   the §6 reason.
8. **Implicit-dependency check:** `./gradlew :aimon-llm-openai:spotlessApply :aimon-core:test --tests "$FOCUS"` in one
   invocation → `BUILD SUCCESSFUL`, with no "implicit dependency" or "uses this output of task" problem.
9. *(Optional)* `--configuration-cache` on `:aimon-core:test --tests "$FOCUS"`, run with and without D2's lines. Only a
   problem that appears with the new line and not without it is attributable to it. The build enables no configuration
   cache, so a pre-existing problem is not this change's.
10. **Gate:** `./gradlew format` (then `git status` shows only intended files), then `./gradlew checkAll`. Record
    `BUILD SUCCESSFUL in …`, tasks executed and up-to-date, and totals across all `test` tasks (tests, failures, errors,
    skipped). Name any task that reported `UP-TO-DATE`.
11. **Doc checks, as CI runs them:**
    - `python3 scripts/check-doc-links.py`;
    - `python3 scripts/check-backlog-registers.py --github`, then `--self-test`;
    - `python3 scripts/check-translation-staleness.py --github` → expect 32 up to date, 0 stale, 0 unresolvable;
    - `python3 scripts/check-translation-structure.py --self-test`, then `--github` → expect 32 structurally identical.
    - `mkdocs build --strict` if it is installed; otherwise say it was not run.

No new test is added. D1 corrects sentences to what is already pinned, and step 4 measures that. D2 is a build-input
change, which a unit test cannot observe; steps 1 and 3 are its proof.

---

## 8. Commit plan

`git log` style, one logical change per commit, no attribution lines:

1. `docs(cli): put the key on the command in the gpt-5.6-terra example (#119)` — §4.1
2. `docs(contributing): name every tag test excludes in the test command and Quality Checks (#119)` — §4.2, both files
   and `source_commit`
3. `docs: say ReleaseGateMatchesCiGateTest runs the release script as well as comparing tasks (#119)` — §4.3
4. `docs(release): name the provider-key refusal and the Docker check in the publishing guide (#119)` — §4.4
5. `test(core): say the release script refuses at least the provider modules' key gates (#119)` — §4.5
6. `build(core): make the provider modules' test sources inputs of aimon-core's test task (#119)` — §4.6
7. `docs(release): name the provider-key refusal in the release skill (#119)` — §4.7, last

Step 1 of §7 happens before commit 6's edit. Steps 3-9 happen after it.

**PR body.** One heading per decision — D1, D2 and each row of D3 — each giving the option taken, the option rejected,
and why, condensed from §3. Then:

- the observable change (D2);
- `Closes #119`, or `refs #119` naming the skill half if §4.7 was refused;
- the §9 findings for the maintainer;
- **Merge notes**, only if a line another record cites moved. `ReleaseGateMatchesCiGateTest.java` line numbers shift,
  and `live-api-test-tier.md:227` cites the census method by name, not by line. #112's frozen design record cites
  `build.gradle.kts:64-71`, and it is left as the dated record it is.

---

## 9. Out-of-scope findings — for `build/deviations.md` and the PR body, not the diff

1. **`.claude/skills/release/SKILL.md` is read by `ReleaseGateMatchesCiGateTest` but is not an input of
   `:aimon-core:test`** (`build.gradle.kts:64-72`). An edit to the skill alone leaves both skill guards unrun locally:
   the same class of gap as item 6, and one this run's own verification would otherwise hit. It is outside #119's six
   items. See open question 1.
2. **The tag scan's inputs gap** (B2). It predates #112 and is documented at `ReleaseGateMatchesCiGateTest.java:109-112`
   (rewritten by §4.6). Price if closed: aimon-core's suite (72% of `@Test`s) newly re-run in 29 of 171 commits so far.
   Not registered, because `LA-3` is for the live-API register and this is not that.
3. **`docs/project/publishing-guide.md:142`**'s quality-gate row says `checkAll` alone, while `release.sh:237` runs
   `checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification`. §4.4's Docker cell names no
   task, so it does not contradict the row.
4. **Docker-only tag claims** in files no run owns: `CLAUDE.md:10` ("excludes @Tag(\"docker\") integration tests") and
   `.claude/rules/testing.md` ("`./gradlew test` / `build` / `check` exclude `@Tag("docker")`").
5. **`CHANGELOG.md:1893`**, #90's bullet: "`test` excludes only `docker` and `packaging`". Another change's record.
6. **#112's design record** `docs/design/llm/provider-key-release-gate.md`:
   - `:345` and `:490` say the refused set *equals* the key gates;
   - `:51`, `:240-243`, `:556`, `:643-644` and `:730` describe the inputs gap as open.

   It is an approved record, kept as written. #122 governs how such records change, and it is not pre-empted here.
7. **`docs/design/llm/anthropic-thinking-traces.md` F-8 and `anthropic-sampling-capabilities.md`** — frozen, listed by
   the issue, and left, per TASK.
8. **Considered and left:**
   - `ReleaseGateMatchesCiGateTest.java:64-65`, "names every key that is set and no other" (D1);
   - `CONTRIBUTING.md:127`, "`checkAll` is the single gate" — true of what to run before pushing, and §4.2 adds that CI
     runs the three tiers;
   - `publishing-guide.md:156-163` — describes the test's task comparison, which is that section's subject.

---

## 10. Open questions

1. **Declare `.claude/skills/release/SKILL.md` as an input too (§9.1)?** It is one line in the block D2 already edits:
   `inputs.file(rootProject.file(".claude/skills/release/SKILL.md")).withPropertyName("releaseSkill")`. *Default: no.* It
   is not one of #119's six items, and TASK says a finding outside the issue goes to `deviations.md` and the PR body,
   not the diff. The in-run consequence is covered by §7 step 7's `--rerun`. The review gate may flip this. If it does,
   add it to D2's commit, extend §4.6's comment sentence to name the skill, and add one clause to the `CHANGELOG`
   bullet.
2. **Commit a design record under `docs/design/`?** TASK neither asks for one nor forbids it. *Default: no.* Both
   decisions end up where the next editor meets them (the test javadoc, the build comment, the backlog 정정), and the PR
   body carries the rejected options. If the orchestrator wants one, it follows `main`'s practice: this body as
   approved, departures appended at the end, and no edit to `docs/design/README.md` (#122).
3. **If the `.claude/` write is refused, how does the PR close the issue?** *Default:* `refs #119`, stating that item 3's
   skill half is left for the maintainer, whose exact sentences are in the body. A close would claim an item not done.
   The maintainer can switch to `Closes` after applying them.
4. **Is there a price at which D2 should give way to B1?** It cannot be settled without §7 step 5's number. *Default: D2
   stands whatever the number is.* The price is charged only to `checkAll` after an edit to the 59 files the census
   reads (9 of 171 commits so far), and the number goes into `CHANGELOG` and the PR body so the maintainer can overturn
   it at review with the figure in hand.

---

## 11. After the build — departures, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 1 (PASS, no blocking findings, eight non-blocking notes), and it is not edited to look prescient. Three sources
feed this section: the run's `build/deviations.md`, review 1's notes, and what was measured while building.*

**D1 and D2 were built as written**, and so were §4's texts for the CLI example, both `CONTRIBUTING` files, the two
overview pages and `api-stability.md`, the publishing guide's table and the `/release` skill. What departed is below:
three wordings, one cross-reference, two backlog sentences, this file and one link path in it, and how two
measurements were taken.

### 11.1 Where the build departed from the body

- **DV-1 — this file.** Open question 2's default was no record; the run was asked for one. It lands in `llm/`,
  beside [`provider-key-release-gate.md`](provider-key-release-gate.md), whose census and records it follows up, and
  is indexed in [`../README.md`](../README.md). Its body keeps a test strategy and `file:line` citations, as that
  record's does.
- **DV-2 — a cross-reference on the census's constant.** §3 D2 said the Java constant and the Gradle glob would point
  at each other "as `RELEASE_SCRIPT` and `rootProject.file("scripts/release.sh")` already do". Only the build-script
  side of that precedent exists. §4.6 put the Java-side pointer in the class javadoc's *What this cannot see*, which
  someone widening the census may never read, so `PROVIDER_MODULE_PREFIX`'s own javadoc now names the
  `providerModuleTestSources` input and says a wider census needs a wider declaration. The class javadoc keeps its
  pointer too.
- **DV-3 — three wordings in the test's javadoc.** The *What is enforced* bullet names `LA-2`'s two variables
  ("whether the script should also refuse `AIMON_DOCKER_IT` and `AIMON_KUBERNETES_IT` is backlog `LA-2`") instead of
  generalizing it, because `LA-2` is about those two and not about refusing more in general. *What this cannot see*
  says a change to the provider modules' test sources "re-runs this module's whole `test` task, not only this class",
  because "re-runs this class" made the price look smaller than the `CHANGELOG` bullet and the build comment say it
  is. And the tag-scan sentence opens "The tag scan's sources are not", where the old "The tag scan above" had no
  referent above it.
- **DV-4 — two backlog sentences.** `LA-1`'s new 정정 names the test's `@DisplayName` along with its javadoc, since D1
  changed both. `LA-2`'s dated note gains one sentence: widening the census to the two sandbox variables means widening
  `providerModuleTestSources` in `modules/aimon-core/build.gradle.kts` with it, or a local build that changes only the
  newly read sources skips the census `UP-TO-DATE` again. D2 made that a consequence of answering `LA-2` "yes", and
  `LA-2`'s **어디** is where the answer will be read. No register title or index count changed.
- **DV-5 — `CHANGELOG.md`.** D2's bullet quotes the measured price (§11.4) and none of §2's commit proxies. The Records
  bullet also names this file, as the entry does for #98's record.
- **DV-6 — how two measurements were taken.** The tree held this run's document edits throughout §7, so each "`git
  status --porcelain` is empty" became "no `Scratch*`, `scratchprobe` or `scripts/release.sh` entry". None of those
  edits is an input of `:aimon-core:test`, and steps 1b and 1c were `UP-TO-DATE` after they were made. Step 9 ran with
  D2's lines only: it reported no problem, so a run without them had nothing to attribute.
- **DV-7 — one link path in the body.** §4.4's proposed table for `publishing-guide.md` links
  `CONTRIBUTING.ko.md#라이브-api-테스트` relative to `docs/project/`. Copied here unchanged it pointed at
  `docs/CONTRIBUTING.ko.md`, which does not exist, and `check-doc-links.py` failed on it. That one path now has one
  more `../`; the target, the anchor and the rendered text are unchanged, and it is the only byte of the body that
  differs from the approved document.

### 11.2 Review 1's non-blocking notes

| Note | Taken? | Where |
|---|---|---|
| 1 — §4.2's `build.yml:265,278,363,481` is wrong | Taken as a check | The sentence is true and carries no citation: `build.yml:113` runs `packagingTest`, `:198` `playwrightTest`, `:316` `integrationTest`, and `release.sh:237` runs all three. The body's citation stays as approved |
| 2 — §2's proxies recount as 8 and 28, not 9 and 29 | Taken | The `CHANGELOG` quotes only §11.4's measured price. The body keeps its numbers |
| 3 — the cross-reference belongs on `PROVIDER_MODULE_PREFIX` | Taken | DV-2 |
| 4 — the guide's quality-gate row still says `checkAll` alone | Not taken; stated | §11.3 |
| 5 — "whether the script should" generalizes `LA-2` | Taken | DV-3 |
| 6 — "re-runs this class" understates the price | Taken | DV-3 |
| 7 — §7 step 4 edits `release.sh` in the shared worktree | Taken | The build committed nothing. The edit was restored, and `git diff --quiet -- scripts/release.sh` exited 0 then and again at the end |
| 8 — §7 step 7's `--rerun` is load-bearing | Taken | §11.4 records its outcome |

### 11.3 The open questions and §9's findings

| Item | Now | Why |
|---|---|---|
| Q1 — declare `SKILL.md` as an input | Not declared; PR body | Outside #119's six items. Step 7's `--rerun` covered this change's own skill edit |
| Q2 — a design record | This file | DV-1 |
| Q3 — `Closes` or `refs` if the skill write is refused | Not needed | The skill was written (§11.4, step 7) |
| Q4 — a price at which D2 gives way | D2 stands | 8168 tests in 40s (§11.4), charged only to a build after an edit to the census's sources |
| §9.2 — the tag scan's inputs gap | PR body; not registered | It is not a live-API-tier item, and `LA-3` was the only ID reserved for this run |
| §9.3 — the guide's quality-gate row | PR body | **Item 3 leaves one cell of the same table wrong.** The table now names a Docker daemon check in pre-flight, while the next row still says the gate is `checkAll`, which excludes `@Tag("docker")`; `release.sh:237` runs `checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification`. D3b kept the new cell from contradicting that row, and the fix is one cell |
| §9.1, §9.4–§9.7 | PR body | Q1, files no run owns, another change's `CHANGELOG` bullet, or frozen records |

Nothing went to the backlog, and `LA-3` was not used.

### 11.4 What was measured

*2026-09-11, macOS arm64, Gradle 9.2.1. No live API call was made and no key file was read. Every Gradle command ran
as `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew --console=plain …` from a shell where
`env | grep -c -E '^(OPENAI|ANTHROPIC)_KEY='` printed `0`. `FOCUS` is
`at.aimon.core.architecture.ReleaseGateMatchesCiGateTest`, and the filter was the same throughout each sequence.*

**The gap, before D2** (`:aimon-core:test --tests "$FOCUS"`):

| Step | State | `:aimon-core:test` |
|---|---|---|
| 1a | untouched build script and test | executed; 12 tests, 0 failures |
| 1b | nothing changed | `UP-TO-DATE` |
| 1c | `ScratchKeyGateProbe`, gated `named = "SCRATCH_PROBE_KEY"`, under `aimon-llm-openai/src/test` | **`UP-TO-DATE`** |
| 1d | the same, with `--rerun` | executed; `12 tests completed, 1 failed` — the census, gated on `[ANTHROPIC_KEY, OPENAI_KEY, SCRATCH_PROBE_KEY]` while the script refuses `[ANTHROPIC_KEY, OPENAI_KEY]` |

**After D2:**

| Step | State | `:aimon-core:test` |
|---|---|---|
| 3a | build script and test changed | executed; 12 tests, 0 failures |
| 3b | nothing changed | `UP-TO-DATE` |
| 3c | the 1c probe again | **executed**; `12 tests completed, 1 failed`, the same census message |
| 3d | probe removed | executed; 12 tests, 0 failures |
| 3e | nothing changed | `UP-TO-DATE` |
| 3f | the probe in `modules/aimon-llm-scratchprobe/src/test/java`, a directory no settings file names | executed; the census fails naming `SCRATCH_PROBE_KEY` |
| 3f2 | that directory removed | executed; 12 tests, 0 failures |
| 3g | the probe under `aimon-sandbox-docker/src/test` | `UP-TO-DATE` — the input is no wider than the census |
| 3g2 | removed | `UP-TO-DATE` |

**D1, measured (step 4).** With `release.sh`'s loop temporarily `for name in ANTHROPIC_KEY OPENAI_KEY AIMON_DOCKER_IT; do`,
`--rerun` ran 12 tests with 0 failures: a script that refuses more passes, which is what "at least" says.

**The price (step 5).** `:aimon-core:test --rerun`, unfiltered: `BUILD SUCCESSFUL in 40s`; 8168 tests in 1304 classes,
0 failures, 0 errors, 2 skipped.

**Other checks.** Step 6 (`--rerun` on the final test): 12 tests, 0 failures, and the new display name in the reports.
Step 8 (`:aimon-llm-openai:spotlessApply :aimon-core:test --tests "$FOCUS" --rerun` in one invocation):
`BUILD SUCCESSFUL`, no implicit-dependency problem. Step 9 (`--configuration-cache`):
`Configuration cache entry stored.`, no problem reported.

**The gate.** `./gradlew format` changed nothing, before step 3 and again before the gate. `./gradlew checkAll`:
`BUILD SUCCESSFUL in 3m 23s`, 240 actionable tasks (112 executed, 128 up-to-date), with `checkFormat`, every module's
`checkstyleMain` and `verifyBom` among them. Twenty-one module `test` tasks executed: 2778 tests, 0 failures, 0 errors,
70 skipped (`aimon-sample-app`'s ran no test). One, `:aimon-core:test`, reported `UP-TO-DATE` from step 5's unfiltered
run on the same inputs; with its 8168 tests the tree counts 10946 tests, 0 failures, 0 errors, 72 skipped. Five `test`
tasks had no source.

**Documentation.** Run as CI invokes them, every one exits 0: `check-doc-links.py` (248 files, 0 broken links),
`check-backlog-registers.py --github` and `--self-test`, `check-translation-staleness.py --github` (32 up to date,
0 stale, 0 unresolvable) and `check-translation-structure.py --self-test` and `--github` (32 structurally identical).
`mkdocs build --strict` also exits 0. Before DV-7, the link check reported this file's one broken link.

**The skill, last (step 7).** After both edits to `.claude/skills/release/SKILL.md`, `--tests "$FOCUS" --rerun` ran
12 tests with 0 failures, both skill tests among them (`releaseSkillDescribesTheRealGate`,
`releaseSkillDoesNotCallAGatedTierUngated`). The `--rerun` was not optional: the skill is not an input of
`:aimon-core:test` (§9.1), so a plain build after the edit would have proved nothing about those two tests. Then
`:aimon-core:test --rerun`, unfiltered, on the final tree: `BUILD SUCCESSFUL in 43s`, 8168 tests, 0 failures, 0 errors,
2 skipped. No other module's build script or test reads the skill.

**Not measured.** A local `checkAll` after an edit to the census's sources, end to end: step 5 prices the suite such a
build adds and step 3c shows the task executing, but the two were not combined in one build. A real release, with or
without a key — the refusal has still only run in the test's sandbox.
