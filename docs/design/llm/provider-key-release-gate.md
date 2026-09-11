# Design — #98: the quickstarts put the provider key on the command, and the release gate refuses one

> Status: **IMPLEMENTED** — `scripts/release.sh` (its `0. provider API keys` section), the new tests in
> `ReleaseGateMatchesCiGateTest`, the three CLI quickstarts, and `CONTRIBUTING.md` / `CONTRIBUTING.ko.md` § Live-API
> tests. Source: issue [#98](https://github.com/kangwoo/aimon-core/issues/98).
>
> **[§9](#9-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §9 is the body as approved in design review
> round 2, kept as approved rather than corrected — the house habit in this directory, for the reason
> [`model-capability-binding-round-trip.md`](model-capability-binding-round-trip.md) gives. Its file:line citations
> and counts are at `main` `9b642cc`. The review transcripts it cites (`review-1.md`, `review-2.md`) and the run
> records it names (`$RUN_DIR/build/`) are not in the repository.
>
> What this work left open is [`../../backlog/live-api-test-tier.md`](../../backlog/live-api-test-tier.md) `LA-2`.

Run `quickstart-key-release-gate`, design phase. Base `main` at `9b642cc`. Everything below cites that tree
unless it says otherwise.

**Revision 2 — after `review-1.md`.** Every finding was accepted. Nothing was rebutted, so there is no `rebuttal-1.md`.

- **Blocking.** The new tests now pin D3's ordering. They no longer just describe it. The stub `git` records every
  call, each refusal case asserts it was never called, and the no-key case asserts it was (§2 D4, §3.2). Scratch copies
  of the script with the guard in four positions show that only the D3 position passes (D4's table).
  - The prose the build writes into `release.sh`, the test javadoc and `CHANGELOG.md` now claims only what those
    assertions check (§3.1, §3.2, §3.6).
  - "Empty `PATH`" is gone everywhere. `PATH` holds one stub.
- **Non-blocking, all taken:**
  - the gate locator enters at `release.sh:3`, not `:10`;
  - bash 5.1.16 was probed by the review, so open question 6 is closed;
  - the harness writes output to a file and reads it after `waitFor`, so a hang cannot block the timeout;
  - bash is resolved and a POSIX file system is assumed before the stub is created;
  - the census failure message names both exits;
  - the CONTRIBUTING sentence says the only exclusions are *by tag*.

---

## 0. What was re-verified before designing

The issue was checked against `a1236c8`; #103 landed since. Every citation the design relies on was re-read at
`9b642cc`.

| Claim | At `9b642cc` |
|---|---|
| The three quickstarts export the key | `README.md:147`, `docs/README.md:54`, `docs/README.en.md:62` — all `export OPENAI_KEY=sk-...` then `./gradlew :aimon-cli:run`. Unchanged by #103 |
| The release gate | `scripts/release.sh:182` — `$GRADLE checkAll integrationTest packagingTest playwrightTest jacocoTestCoverageVerification`. Nothing in the script reads `OPENAI_KEY` / `ANTHROPIC_KEY`. The one environment variable it repairs is `JAVA_TOOL_OPTIONS` (`:40`) |
| Exclusions in `test` (item 3) | `buildSrc/.../aimon.java-conventions.gradle.kts:128-133` excludes `docker` and `packaging` for every module; `modules/aimon-browser-playwright/build.gradle.kts:22-26` also excludes `playwright` (the conventions comment at `:116` confirms repeated `useJUnitPlatform {}` calls accumulate). `git grep` over `*.gradle.kts` finds no other `excludeTags`, `excludeTestsMatching`, `exclude("**…")` or disabled `test` task. So the only mechanism is tag exclusion, with those three tags |
| When `test` executes (item 4) | No build cache and no configuration cache: `gradle.properties` has neither `org.gradle.caching` nor `org.gradle.configuration-cache`, and `settings.gradle.kts` configures no `buildCache`. So after `clean` or `cleanTest` the task has no outputs and executes; it cannot be restored `FROM-CACHE` in this build's configuration |
| The live classes' gates | Four classes, `@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY" / "OPENAI_KEY", matches = ".+")`, all in `modules/aimon-llm-{anthropic,openai}`. Two more env-gated classes exist outside those modules: `DockerSandboxBackendIntegrationTest` (`AIMON_DOCKER_IT`, `matches = "true"`) and `KubernetesSandboxBackendIntegrationTest` (`AIMON_KUBERNETES_IT`) — the same census `live-api-test-tier.md` §0.1 records |
| CI has no key | `.github/workflows/build.yml` runs on `ubuntu-latest`, `./gradlew --no-daemon …`, no key in any workflow (`live-api-test-tier.md` §0.2 still holds) |
| How the gate test finds the gate | `ReleaseGateMatchesCiGateTest.releaseGateInvocation()` enters "the gate section" at the first `#` line containing `quality gate` and returns the first line starting with `$GRADLE `. **The header comment at `release.sh:3` already contains `quality gate`** (*"bump version, run the quality gate, publish …"*), so the section effectively starts at line 3 — anything that adds a `$GRADLE ` line above `:182` would be taken for the gate |
| The test re-runs when the script changes | `modules/aimon-core/build.gradle.kts:64-71` declares `scripts/release.sh` and `build.yml` as inputs of aimon-core's `test`. Other modules' **test sources** are not declared (this matters for §2 D5) |
| Translations are level today | `check-translation-staleness.py`: 32 up to date, 0 stale, 0 unresolvable. `check-translation-structure.py`: 32 structurally identical |
| `source_commit` practice | `0335838` set it to the canonical's last commit before its edit, including a merge SHA (`CONTRIBUTING.ko.md` → `aa2bb4a`, a merge); `4f677a6` and `9447cb8` edited both files of a pair and left it alone. `CLAUDE.md` states the rule: the canonical commit right before this edit |
| Shell probe (scratchpad, not the repo) | This machine's only `bash` is `/bin/bash` 3.2.57. Under `set -euo pipefail`: `${!name+set}` reports set/unset without expanding the value, including for a variable set to the empty string; `cd ""` returns 0 and stays put; with `PATH=/nonexistent`, `git` is "command not found" and the script continues. **The design review repeated the first two probes on bash 5.1.16** (`ubuntu:22.04`, `--network none`) with the same results. `env -u NAME cmd` works on macOS |

Other places that advise "export a key, then build" (the task asks for all of them): only
`modules/aimon-cli/examples/gpt-5.6-terra.yaml:3-4` beyond the three quickstarts. `modules/aimon-llm-anthropic/README.md:171`
and `modules/aimon-llm-openai/README.md:182` already put the key on the command.
`modules/aimon-sandbox-kubernetes/README.md:114` (`export KEY='VALUE'`) is about sandbox env injection, not a provider key.

---

## 1. The problem

`CONTRIBUTING.md` (#94) now tells readers that a provider key exported in a shell turns every `./gradlew test` and
`checkAll` in that shell into a billed live-API run, because the four live classes carry no tag and their environment
variable is the only gate — yet the three CLI quickstarts still open with `export OPENAI_KEY=sk-...`, which puts the
reader in exactly that shell. `scripts/release.sh` has the same hole from the other side: it runs `checkAll` in
whatever environment it is started from, so a release cut from a keyed shell makes billed calls, can go red for a
provider-side reason, and no longer runs the gate CI runs (CI has no key), which is the equality
`ReleaseGateMatchesCiGateTest` exists to hold. Two sentences beside the warning are also slightly untrue: the tag
exclusions it names are the conventions plugin's, not the repository's (`aimon-browser-playwright` excludes
`playwright` too), and its list of when `test` executes omits `clean` / `cleanTest`.

---

## 2. Decisions

Each decision below gets its own heading in the PR body (option taken, option rejected, why).

### D1 — The release script refuses to start while a provider key is set. It does not unset it.

**Taken:** refuse, name the variable(s), never the value, say why and how to proceed, exit 1.

**Rejected:** `unset OPENAI_KEY ANTHROPIC_KEY` (or `env -u … $GRADLE …`) for the gate.

Neither option changes a public contract, a wire or config name, or a persisted identity: the script's arguments, the
gate's task list and CI are identical under both. Both are behaviour changes an operator can observe, and both would
be named in `CHANGELOG.md`. So the "smallest contract change" rule does not choose between them, and the choice rests
on the following.

1. **The hazard lives in the shell, and only the refusal reaches the shell.** The script exits and the operator's
   shell keeps the key exported; per #98 item 1 and `CONTRIBUTING.md:93-102`, every later build in that shell bills.
   An unset inside the script fixes one command of that shell and tells the operator nothing. The refusal is the
   moment `CONTRIBUTING.md`'s advice ("put the key in front of the one command that needs it, or `unset` it before
   you build") is delivered to the person who needs it.
2. **It is the script's own convention for a precondition that changes what the gate verifies.** Branch, clean tree,
   sync with origin, Docker, credentials, existing tag — every one is a `fail`, and the Docker check says why in so
   many words (`:74-78`, *"Fail, do not warn"*). The one variable the script repairs silently, `JAVA_TOOL_OPTIONS`
   (`:40`), changes *how* Gradle runs, not *which tests* run. A key decides which tests execute.
3. **A silent unset leaves no record.** A release log from a keyed shell and one from a clean shell would read the
   same. A refusal is visible in the transcript and in any `/release` session that relays it.
4. **It is pinned on its own output.** A refusal has an exit status, a message, and a value that must be absent from
   that message — the test asserts on exactly those (D4). An unset has no output; a test could only infer it from the
   environment some later child process receives.

The cost is friction on a rare, deliberate operation: re-run from a shell without the key, or
`env -u OPENAI_KEY scripts/release.sh <args>`. There is **no escape-hatch flag** (`--allow-provider-keys` or similar):
it would be both options behind a flag, and it would put the live tier back inside the release gate, which LA-1
decided against.

`--dry-run` is refused like any other invocation — it runs the gate.

### D2 — "Set" means set, including set to the empty string

**Taken:** refuse when the variable exists in the environment (`${!name+set}`), whatever its value.

**Rejected:** refuse only a non-empty value, mirroring the classes' `matches = ".+"`.

- The issue's own wording is "while either is set".
- The guard should not depend on how each class writes its `matches` regex. A future class gated with `matches = ".*"`
  is enabled by an empty value, and D5's census reads names, not regexes.
- The remedy is identical (`unset`), and an exported-but-empty key is rare enough that the stricter reading costs
  almost nothing.

The maintainer can overturn this in one line of the script and one test case.

### D3 — The check is the first thing after argument parsing: before `cd`, the `EXIT` trap, and any git, network or Docker call

- It needs nothing from the repository, so nothing needs to run before it.
- A shell the script is about to refuse should not first trigger `git fetch origin main` (`:68`) or `docker info` (`:79`).
- A refusal is not a failed release. Once armed, the `EXIT` trap (`:50-57`) calls `git diff` on any non-zero exit and
  prints *"gradle.properties has an uncommitted version bump"* when that file differs from HEAD, which would be a false
  note beside a refusal.
- After argument parsing, not before, so an unknown argument still gets exit 2 and the usage line (`:27-31`), which
  is the existing contract.

The `log` / `ok` / `fail` helper definitions (`:43-48`) move above `cd` so the refusal can use the same formatting.
They are pure functions, so moving them changes nothing else.

**This ordering is pinned, not just stated** (D4). Every refusal case asserts that the script never invoked `git`. That
fails for a check placed after `cd "$(git rev-parse --show-toplevel)"` (`:35`), and for one placed after
`trap cleanup EXIT` (`:57`), because the trap calls `git diff` on the refusal's non-zero exit. Every refusal case also
asserts that `Pre-flight checks` was never printed. `git fetch` and `docker info` both come after that line in the
script, so they are covered as long as that order holds; nothing asserts them separately.

### D4 — The guard is pinned by running the real script in a sandbox, inside `ReleaseGateMatchesCiGateTest`

**Taken:** new test methods in `ReleaseGateMatchesCiGateTest` run the real `scripts/release.sh --dry-run` with:
- a cleared environment;
- an empty working directory under `@TempDir`;
- a `PATH` holding only a directory with a **call-recording stub `git`**;
- the case's variables.

They assert on the exit status, the combined output, and the stub's call log.

Why the sandbox is inert whether or not the guard is present: `PATH` holds only the stub, so no real `git`, `docker`,
`perl` or `./gradlew` can be reached. The working directory has no `gradle.properties`, so without the guard the script
stops at `:61` (*"gradle.properties not found"*). `--dry-run` is passed as well. Removing the guard therefore turns
each refusal case red **safely**, which is the property the task asks for.

The stub is a `#!/bin/sh` script. It appends `$*` to a log file outside the working directory, prints `pwd` for
`rev-parse`, and exits 0. It does two jobs:

- **It pins D3's ordering.** Every refusal case asserts that the log does not exist: the script refused before
  invoking `git` for any reason. The no-key case asserts that the log **does** exist. That proves the stub was on
  `PATH` and could execute; otherwise "no git call" would pass vacuously in a sandbox whose stub cannot run. The helper
  also checks this before every case (§3.2), so a single method run with `--tests` is self-validating too.
- **It lets the no-key case reach a known later point.** `cd` lands in the sandbox, the script prints
  `Pre-flight checks`, and it stops at `gradle.properties not found`. Exiting 0 keeps the trap's `git diff --quiet`
  from printing its note. (Both bash 3.2.57 here and bash 5.1.16 in the review's probe also tolerate `cd ""`, so the
  stub is not a hedge against bash versions.)

**Probed on scratch copies of `release.sh`, never the real file.** Each copy had a minimal guard inserted at one
position, and ran as `env -i PATH=<sandbox>/bin HOME=<sandbox>/work /bin/bash copy.sh --dry-run` from
`<sandbox>/work`, with the recording stub. The key case used `OPENAI_KEY=dummy-98-sentinel`.

| Guard position | Key case: exit · `Pre-flight` · refused · sentinel · stub log | No-key case |
|---|---|---|
| none (the current script) | 1 · printed · no · absent · `rev-parse --show-toplevel`, `diff --quiet -- gradle.properties` | 1 · printed · log has both calls |
| **after the argument loop (`:33`) — D3** | 1 · absent · yes · absent · **no call** | 1 · printed · log has both calls |
| after `cd` (`:35`) | 1 · absent · yes · absent · `rev-parse --show-toplevel` | same |
| after `trap cleanup EXIT` (`:57`) | 1 · absent · yes · absent · `rev-parse --show-toplevel`, `diff --quiet -- gradle.properties` | same |

Only the D3 position passes every refusal assertion, and each of the other three fails at least one. The review's
option (b) was to cut the claims down to "refused before `Pre-flight checks`". It was rejected because D3's own
reasons — no `git fetch` from a refused shell, no trap note beside a refusal — are about git and the trap. Leaving them
unpinned would leave exactly the kind of prose claim this test class exists to replace, and pinning them costs one
line in the stub and one assertion per case.

**Rejected alternatives:**

| Alternative | Why not |
|---|---|
| Text scan like the class's other five tests (the guard's names appear, above `:182`) | The guard is behaviour, not a task name. A commented-out, inverted or unreachable check passes a scan, and a scan cannot show the value is never printed. The class's javadoc already names its limit: *"Task names, not task graphs"* |
| Extract the check into `scripts/lib/…sh`, source it from `release.sh` and from the test | The test would pin the helper, not that `release.sh` calls it before the gate. It adds a second file that would have to be declared as an input in `modules/aimon-core/build.gradle.kts`, which is not this run's file |
| Run the real script against the real repository with `--dry-run` | Needs `main`, a clean tree in sync with origin, a network fetch, Docker and publish credentials. If the guard were ever removed, it would run the entire gate recursively inside a unit test |
| Add a self-check flag to `release.sh` (`--check-env`) | Adds command-line surface to the script purely for a test |
| A new sibling test class instead of extending this one | Either would work. Extending wins because this class states the invariant the issue cites ("CI has no key, so this also keeps the release gate equal to CI's"), and its javadoc's *What is enforced* list is where a reader looks for everything the release gate is held to. The class already reads `release.sh`, and that file is already a declared input |

Spawning a process from a unit test has precedent here (`FileToolsIntegrationTest` runs `rg --version`; the packaging
tier launches JVMs). The shell is `bash`, resolved as the first executable `bash` on the test JVM's `PATH`, which is
what the script's `#!/usr/bin/env bash` would pick. The process tests skip, with the reason, when there is no `bash` —
the script cannot run on that machine either — or when the temp file system has no POSIX permissions, which the stub
needs. Both checks run before anything is created. CI's `ubuntu-latest` passes both.

### D5 — The list of refused variables is pinned against the provider modules' key gates

**Taken:** a frozen `PROVIDER_KEY_VARIABLES = {ANTHROPIC_KEY, OPENAI_KEY}` in the test. It must equal the set of
`named = "…"` values read from `@EnabledIfEnvironmentVariable` annotations under `modules/aimon-llm-*/src/test/`, and
the refusal cases iterate over it.

This is the same shape as `everyTestTagIsGated`: a frozen set, an equality against a source scan, and a scan that fails
when it finds nothing. A new provider module whose live class is gated on, say, `GEMINI_KEY` fails here until the
variable is added to the test's set, which in turn fails the refusal case until `release.sh` refuses it. The class's
javadoc records three drifts of hand-maintained lists; without this the guard would be a fourth.

**The failure message names both exits,** as `everyTestTagIsGated`'s does ("or, if it is genuinely meant to stay out,
…"):

1. If the variable is a provider key, add it to `PROVIDER_KEY_VARIABLES`, make `scripts/release.sh` refuse it, and add
   its classes to `CONTRIBUTING.md`'s live-API table.
2. If it gates something that is **not** a provider key (a `CI` switch, say), narrow the scan to exclude it, with a
   note saying what it gates and why the release gate may inherit it.

No exclusion set exists up front: today every gate under `aimon-llm-*` is a provider key, and the only non-key gates in
the repository live outside that scope.

**Rejected:**

- **Only the two hard-coded names.** Meets the acceptance criterion today and silently stops meeting it for the next
  provider.
- **Scan every module and allowlist `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT`.** The allowlist would need a written
  reason those two stay out, and `live-api-test-tier.md` §0.1 explicitly left that reason unverified. Writing one
  unverified would break the backlog's rule two.
- **Have the script derive its list by grepping test sources.** The release script would be parsing Java.

The limits go in the javadoc's *What this cannot see*: a gate written as `System.getenv` plus an assumption rather than
the annotation; a provider key gate outside `aimon-llm-*`; a new class gated on an **existing** variable (not a change
the guard needs). One further known gap is not introduced here: the scanned sources are not declared inputs of
aimon-core's `test`, so a local build that changes only them can report the test `UP-TO-DATE`. The existing tag scan
has the same gap. CI always runs from a fresh checkout. Fixing it needs `modules/aimon-core/build.gradle.kts`, which is
not this run's file → deviation.

### D6 — Each quickstart puts the key on the command and says why in one sentence

**Taken:** `OPENAI_KEY=sk-... ./gradlew :aimon-cli:run`, followed by one sentence that links to the live-API section.

**Rejected:** the command alone. A bare inline key reads like a stylistic choice, and the next editor "tidies" it
back to `export`, which is how the quickstarts and `CONTRIBUTING.md` drifted apart in the first place. A whole
paragraph was also rejected: `docs/README.md` is written for a first visit (*"처음이라면 이 페이지만 읽어도 된다"*), so one
sentence plus a link is the budget.

The sentence says "can run", not "runs", because `CONTRIBUTING.md` carries the `UP-TO-DATE` nuance and the link goes
there.

### D7 — `CONTRIBUTING`: name all three tags, and make the list of executions explicitly non-exhaustive

- **Item 3.** Say the only exclusions are by tag. Name `docker` and `packaging` as the conventions plugin's
  exclusions in every module, and `playwright` as `aimon-browser-playwright`'s addition. The issue's alternative, "say
  it is the conventions plugin that excludes", was rejected: a reader who then opens the playwright build file finds a
  third tag the sentence did not mention. Naming it costs one clause. The wording is "the only exclusions … are by tag",
  not "excludes tagged tests only", which could be read as "excludes every tagged test" (review-1). The paragraph's
  point — the live classes carry no tag — is untouched.
- **Item 4.** Add `clean` / `cleanTest` and introduce the list with "for example". Simply appending them to a list that
  reads as complete was rejected because it would still be untrue: `--rerun`, the subject of the very next paragraph,
  also makes the task execute. Other input changes do too, but the design does not claim those unmeasured. The build
  cache is off in this build (§0), so "after `clean` or `cleanTest`" holds for the repository's configuration. A user
  who enables `org.gradle.caching` globally can get `FROM-CACHE` instead, which only means fewer runs than the
  paragraph warns about, never more, so no qualifier is added.

### D8 — No `LA-2`. Two dated notes on the closed `LA-1`

Nothing this change leaves behind meets the register's bar for an item (verified "why", a revisit trigger, inside this
run's files):

- the documentation drift found in other files is a list of doc fixes → `build/deviations.md`;
- the `AIMON_*_IT` gates were deliberately not registered by `LA-1` §0.1 for lack of a verified rationale, and this
  run has not verified one either.

`LA-1`'s reader, though, is *"the person who wants to change this arrangement"*, and the release script now depends on
the decision (option 2, manual only). If option 1 is ever taken, the refusal has to be revisited too. So `LA-1` gets
a dated bullet under **어디**, and a dated parenthetical in reopen trigger 2, whose *"그것을 강제하는 검사는 없고"* is now
partly false (a class gated on a **new** variable in an `aimon-llm-*` module now fails a test). No heading, count,
title or index row changes: `등록 항목 1건 (열림 0 · 닫힘 1)` stays, as does `docs/backlog/README.md:350`.

---

## 3. Changes by file

### 3.1 `scripts/release.sh`

1. **Header comment (`:12-14`).** The "Order of operations" line gains the check first:
   `provider-key check → pre-flight → quality gate → confirm → …`.
2. **Move** the `log` / `ok` / `fail` definitions (`:43-48`) above `cd "$(git rev-parse --show-toplevel)"` (`:35`).
3. **New section** right after the argument loop (`:33`) and before `cd`, titled e.g.
   `# ── 0. provider API keys ──`. Its comment must say:
   - what it refuses and why: the live-API classes' only gate is their variable; the gate's `checkAll` would run them,
     bill, and fail for provider-side reasons; CI has no key, so the gate would stop being CI's;
   - why it refuses rather than unsets, in one sentence (D1);
   - why it runs first (D3), and what the test holds it to. `ReleaseGateMatchesCiGateTest` runs this file from an
     empty directory with a `PATH` holding only a stub `git` that records its calls, and fails if the check moves below
     the `cd` that calls `git`, below `trap cleanup EXIT` (the trap calls `git` when a refusal exits non-zero), or below
     `log "Pre-flight checks"`. Name the statements, not line numbers: the helper move shifts every line. This is the
     same style as the existing "Both tasks stay on ONE `$GRADLE` line on purpose" note, and it must claim no more than
     those three things.

   Illustrative shape only (the build owns the final text):

   ```bash
   provider_keys_set=""
   for name in ANTHROPIC_KEY OPENAI_KEY; do
       # `+set` asks whether the variable exists without expanding its value.
       if [ -n "${!name+set}" ]; then
           provider_keys_set="${provider_keys_set:+$provider_keys_set }$name"
       fi
   done
   if [ -n "$provider_keys_set" ]; then
       # red ✗ headline naming $provider_keys_set, "(value not shown)"; why in two lines;
       # remedy: unset <names>, or env -u <name>… scripts/release.sh <args>; pointer to CONTRIBUTING.md › Live-API tests
       exit 1
   fi
   ```

   Constraints on the implementation:
   - **Never expand a key's value.** Use `${!name+set}`, not `printenv NAME`, `${!name}` or `$OPENAI_KEY`.
     `printenv` would also not exist on the sandbox's `PATH`.
   - **No empty-array expansion under `set -u`.** macOS `/bin/bash` 3.2 (this machine's only bash) errors on
     `"${arr[@]}"` for an empty array. Accumulate into a string, as above.
   - **Name only the variables that are set**, all of them in one refusal, so the operator is never told to unset
     something that is not there and never has to hit the refusal twice.
   - Builtins only (`[`, `printf`, `exit`); output to stderr; exit status 1, like `fail`.
   - No `$GRADLE` invocation, and no new `$GRADLE `-prefixed line anywhere above the gate: the locator's section
     already starts at `:3` (§0).
   - The remedy may echo the parsed arguments (`patch|minor|major|--yes|--dry-run` only, already validated), which
     makes it copy-pasteable. That is optional.
4. **The gate line `:182` stays byte-identical**, so `releaseGateInvocation()`, `releaseSkillDescribesTheRealGate()`
   and the CI comparison are unaffected.

### 3.2 `modules/aimon-core/src/test/java/at/aimon/core/architecture/ReleaseGateMatchesCiGateTest.java`

- **Class javadoc.**
  - *What is enforced* gains a bullet, and it claims only what the tests assert: the release script refuses to start
    while a provider API key is in its environment, before it invokes `git` for any reason and before its pre-flight
    checks, without printing the value; and the refused set equals the key gates in the provider modules.
  - A sentence explains why these tests run the script rather than read it, and why the stub records its calls (D4).
  - *What this cannot see* gains D5's limits, and one more: calls to anything other than `git` before pre-flight. Today
    the script makes none, but nothing checks that.
- **Constants.**
  - `PROVIDER_KEY_VARIABLES` (a `List` or `Set` of the two names).
  - A distinctive sentinel value, e.g. `"dummy-98-sentinel"`. The task says a dummy is enough. It must not start with
    `sk-`, so no secret scanner mistakes it for a key.
  - `KEY_GATE_ANNOTATION`, e.g. `^\s*@(?:[\w.]+\.)?EnabledIfEnvironmentVariable\(\s*named\s*=\s*"([^"]+)"`.
    The anchor and the qualified-name group follow `TEST_TAG_ANNOTATION`'s precedent. An attribute order the regex
    does not read makes the scan find fewer names, so the equality fails loudly rather than passing.
- **Tests** (flat methods and loops, matching the class's existing style; `@DisplayName` on each):

  | Method | Environment added | Asserts |
  |---|---|---|
  | `releaseScriptRefusesEachProviderKey` | for each name: `{name: SENTINEL}` | exit 1; output contains `name`; does not contain the other name; does not contain `SENTINEL`; does not contain `Pre-flight checks`; **the stub's call log does not exist** (no `git` call: refused before `cd` and before the trap) |
  | `releaseScriptRefusesAKeySetToEmpty` | `{OPENAI_KEY: ""}` | exit 1; names `OPENAI_KEY`; no `Pre-flight checks`; no `git` call (pins D2) |
  | `releaseScriptNamesEveryKeyThatIsSet` | both, sentinel values | exit 1; both names; no `SENTINEL`; no `git` call |
  | `releaseScriptStartsWithoutAProviderKey` | none | output contains `Pre-flight checks` (got past the guard to a known point); contains neither name; **the call log exists and contains `rev-parse --show-toplevel`** (the stub was reachable and ran, so "no `git` call" in the other cases is not vacuous). Catches an inverted or always-refusing guard, and proves the harness reaches the script |
  | `refusedKeysAreTheProviderModulesKeyGates` | — (source scan) | scan of `modules/aimon-llm-*/src/test/**/*.java` is non-empty (*"the scan is broken, not clean"*) and equals `PROVIDER_KEY_VARIABLES`. The failure message names D5's two exits |

- **Harness** (private static helpers), in this order:
  1. `locateBash()`: first executable `bash` on `System.getenv("PATH")`. None →
     `assumeTrue(false, "no bash on PATH — scripts/release.sh cannot run here either")`.
  2. `assumeTrue(Files.getFileStore(sandbox).supportsFileAttributeView("posix"), …)`, **before** creating anything, so
     a non-POSIX checkout skips instead of throwing from `Files.setPosixFilePermissions`.
  3. Layout inside `@TempDir sandbox`:
     - `work/` — the empty working directory, also used as `HOME`;
     - `bin/git` — the stub;
     - `git-calls.log` — the stub's log, outside `work/` so it cannot affect the script;
     - `output.txt` — the merged output.
  4. The stub, mode `rwxr-xr-x`:

     ```sh
     #!/bin/sh
     printf '%s\n' "$*" >> '<absolute path of git-calls.log>'
     [ "$1" = rev-parse ] && pwd
     exit 0
     ```

     The log path is written single-quoted. A `@TempDir` path contains no quote.
  5. **Stub self-check**, before every script run: execute `bin/git self-check` directly and require `git-calls.log` to
     appear, then delete it. If the stub cannot start (for example a `noexec` temp mount) or writes nothing, **fail**
     with that reason: a stub that cannot run would make every "no `git` call" assertion pass vacuously.
     This is not a skip.
  6. The run itself:
     - `new ProcessBuilder(bash, REPOSITORY_ROOT.resolve(RELEASE_SCRIPT), "--dry-run")`;
     - `directory(work)`, `redirectErrorStream(true)`, `redirectOutput(output.txt)`;
     - `environment().clear()`, then `PATH=<sandbox/bin>`, `HOME=<work>`, plus the case's variables;
     - `start()`, then `waitFor(30, SECONDS)`. On timeout, `destroyForcibly()` and fail.
     - Only then read `output.txt` (UTF-8) and `git-calls.log` (empty list when absent).

     Because output goes to a file, a hung script cannot block the read, and the timeout always fires. Clearing the
     environment makes the no-key case deterministic on a developer machine that has a key exported, and drops the
     `JAVA_TOOL_OPTIONS` that `release.sh` exports into the test JVM when it runs the gate.
  7. The result holder is a small `final class` with exit code, output and git calls. Not a `record` (`CLAUDE.md`).
- `assumeTrue(REPOSITORY_ROOT != null, …)` at the top of each new method, as the existing ones do.
- `@TempDir` per test. No Gradle input change is needed for the script (already declared).

### 3.3 `README.md` (quickstart only, `:142-156`; the Configuration section `:168-194` belongs to `cli-model-followups`)

````markdown
```bash
git clone https://github.com/kangwoo/aimon-core.git
cd aimon-core
OPENAI_KEY=sk-... ./gradlew :aimon-cli:run
```

The key goes on the command rather than being `export`ed, on purpose: while a provider key is exported,
`./gradlew test` and `checkAll` in that shell can run the live-API tests too, and those calls bill — see
[Live-API tests](CONTRIBUTING.md#live-api-tests).
````

`README.md` has no Korean twin.

### 3.4 `docs/README.md` (canonical) + `docs/README.en.md` — one commit

`docs/README.md` §2 › `### CLI 로 말 걸어 보기`, block `:51-56`:

````markdown
```bash
git clone https://github.com/kangwoo/aimon-core.git
cd aimon-core
OPENAI_KEY=sk-... ./gradlew :aimon-cli:run
```

키를 `export` 하지 않고 명령 앞에 붙이는 것은 일부러다 — export 해 두면 그 셸의 `./gradlew test` 와 `checkAll`
도 라이브 API 테스트를 돌릴 수 있고, 그 호출은 청구된다. [라이브 API 테스트](../CONTRIBUTING.ko.md#라이브-api-테스트) 참고.
````

`docs/README.en.md` §2 › `### Talk to the CLI`, block `:59-64`: the same command, and

```markdown
The key goes on the command rather than being `export`ed, on purpose — while it is exported, `./gradlew test` and
`checkAll` in that shell can run the live-API tests too, and those calls bill. See
[Live-API tests](../CONTRIBUTING.md#live-api-tests).
```

- Structure: one fence line changes and one paragraph is added, so no heading, fence, table, list or quote axis changes.
- Links: out-of-`docs/` relative links are the documented pattern (`docs/README.md:216` already links both
  CONTRIBUTING files), and the mkdocs hook rewrites them. `#라이브-api-테스트` is already a working anchor
  (`CONTRIBUTING.ko.md:49`).
- `docs/README.en.md` frontmatter: `source_commit` becomes the output of `git log -1 --format=%h -- docs/README.md` on
  the pre-edit tree (`4f677a6` at `9b642cc`). Re-derive it at build time rather than copying it from here.

### 3.5 `CONTRIBUTING.md` (canonical) + `CONTRIBUTING.ko.md` — § Live-API tests only, one commit

`CONTRIBUTING.md:93-102` — two sentences change; the rest of the paragraph is untouched.

| Now | Becomes |
|---|---|
| "…they carry no tag, and the default `test` task excludes only `docker` and `packaging`." | "…they carry no tag, and the only exclusions in a module's `test` task are by tag: `docker` and `packaging`, which the conventions plugin excludes in every module, and `playwright`, which `aimon-browser-playwright` excludes as well." |
| "That happens each time the module's `test` task executes rather than reporting `UP-TO-DATE`, which is the first build and any build after a change that reaches the module." | "That happens each time the module's `test` task executes rather than reporting `UP-TO-DATE` — for example the first build, a build after `clean` or `cleanTest`, and any build after a change that reaches the module." |

`CONTRIBUTING.ko.md:98-105`:

| 지금 | 바뀐 뒤 |
|---|---|
| "…태그가 없고, 기본 `test` 태스크는 `docker` 와 `packaging` 만 뺍니다." | "…태그가 없고, 모듈의 `test` 태스크는 태그로만 테스트를 뺍니다 — 컨벤션 플러그인이 모든 모듈에서 빼는 `docker` 와 `packaging`, 그리고 `aimon-browser-playwright` 가 더 빼는 `playwright` 입니다." |
| "그 모듈의 `test` 태스크가 `UP-TO-DATE` 로 보고되지 않고 실제로 돌 때마다 그렇고, 그것은 첫 빌드와 그 모듈에 닿는 변경 뒤의 모든 빌드입니다." | "그 모듈의 `test` 태스크가 `UP-TO-DATE` 로 보고되지 않고 실제로 돌 때마다 그렇습니다 — 예를 들어 첫 빌드, `clean` 이나 `cleanTest` 뒤의 빌드, 그 모듈에 닿는 변경 뒤의 모든 빌드가 그렇습니다." |

- Keep each file's line wrapping style; no structural axis changes.
- `CONTRIBUTING.ko.md` frontmatter: `source_commit` = `git log -1 --format=%h -- CONTRIBUTING.md` on the pre-edit tree
  (`a1236c8` at `9b642cc`, a merge, which is acceptable per the `0335838` precedent).
- `backlog-checker-blind-spots` edits a different section of the same pair and may touch the same frontmatter line.
  That is a merge-time conflict, not something to pre-empt.

### 3.6 `CHANGELOG.md` — `[Unreleased]`

A new `###` section at the top of `[Unreleased]` (`:8`), following the #92 and #88 precedent. Headline, e.g.:

`### Release: scripts/release.sh refuses to start while a provider API key is in its environment`

Bullets:

1. **What changed (#98).** It stops before anything else — before it invokes `git`, and so before its network and
   Docker checks — when `OPENAI_KEY` or `ANTHROPIC_KEY` is set, even to an empty string, `--dry-run` included. It names
   the variables that are set, never a value, and says how to proceed.
2. **Why.** The four live-API classes carry no tag, so their variable is their only gate; the gate's `checkAll` would
   run them, bill, and could fail for provider-side reasons; CI has no key, so the gate would no longer be CI's.
3. **Observable change.** Previously a release cut from a keyed shell ran those classes inside the gate; now it
   refuses. Not measured, carried over from the issue: a release run with a key exported.
4. **Refusal rather than unset**, in one sentence (D1).
5. **Pinned.** `ReleaseGateMatchesCiGateTest` runs the script from an empty directory with a `PATH` holding only a
   stub `git` that records its calls, once per key. The refusal must come before any `git` call and before pre-flight,
   and must not print the value. The test also holds the refused set equal to the key gates in `aimon-llm-*`.
6. **Documentation.** The three quickstarts put the key on the command; `CONTRIBUTING.md` / `.ko.md` say the only `test`
   exclusions are the three tags and give `clean` / `cleanTest` as examples of builds that execute `test`.

### 3.7 `docs/backlog/live-api-test-tier.md`

- Under LA-1 **어디** (`:139-150`), append a dated bullet (Korean, names not line numbers — the register's own lesson):
  `scripts/release.sh` *(2026-09-11, #98)* refuses to start with either key in its environment. As long as this
  decision stands, the release gate must run keyless like CI. `ReleaseGateMatchesCiGateTest` runs that refusal and
  holds its variable list to the `aimon-llm-*` key gates. **Reopening this decision with option 1 means revisiting the
  refusal too.**
- In reopen trigger 2 (`:175-177`), after *"다만 그것을 강제하는 검사는 없고 …"*, a dated parenthetical: since #98 a class
  gated on a **new** variable in an `aimon-llm-*` module fails `ReleaseGateMatchesCiGateTest`; a class gated on an
  existing variable, and the `CONTRIBUTING.md` table, still enforce nothing.
- No heading, title count or index-row change. `check-backlog-registers.py` must stay green.

### 3.8 Deliberately not changed

| File | Why not |
|---|---|
| `.claude/skills/release/SKILL.md:10-13` ("enforces every safety gate (…)") | Not in this run's files. The skill already says to relay an abort verbatim and stop, so a refusal is handled correctly. The now-incomplete list → deviation, open question 1 |
| `docs/project/publishing-guide.md:138-144` | Not in this run's files. Its pre-flight row already omits the Docker check, and its gate row names `checkAll` alone → deviation |
| `modules/aimon-core/build.gradle.kts:64-71` | Not in this run's files. The undeclared test-source inputs (D5) are pre-existing → deviation |
| `docs/design/llm/anthropic-thinking-traces.md:643` (F-8), `docs/design/llm/anthropic-sampling-capabilities.md:733` | Design records, frozen. Their "and the release gate makes billed calls" is superseded → deviation |
| `modules/aimon-cli/examples/gpt-5.6-terra.yaml:3-4` | Outside the issue's list → deviation |
| `CONTRIBUTING.md:58` / `.ko.md:63`, `CONTRIBUTING.md:125-127` / `.ko.md:128-130` | Same incomplete tag list, but outside § Live-API tests → deviation |
| `CHANGELOG.md:1718-1732` (#90's bullet, "`test` excludes only `docker` and `packaging`") | Another change's record → deviation |

### 3.9 Commit plan

1. `docs: put the provider key on the CLI command in the quickstarts instead of exporting it (#98)` — `README.md`,
   `docs/README.md`, `docs/README.en.md`.
2. `docs(contributing): name every tag test excludes, and count clean and cleanTest among the runs (#98)` —
   `CONTRIBUTING.md`, `CONTRIBUTING.ko.md`.
3. `fix(release): refuse to start while a provider API key is in the environment (#98)` — `scripts/release.sh`,
   `ReleaseGateMatchesCiGateTest.java`, `CHANGELOG.md`, `docs/backlog/live-api-test-tier.md`.

No attribution lines. The PR body carries `Closes #98` and one heading each for D1, D2, D4, D5 and D6.

---

## 4. Interface and data shapes that change

| Surface | Change |
|---|---|
| `scripts/release.sh` command line | None. Same arguments, same exit 2 for bad usage |
| `scripts/release.sh` preconditions | **New:** neither `ANTHROPIC_KEY` nor `OPENAI_KEY` may exist in its environment. Violation → exit 1 before any `git` call or other action, message on stderr naming the set variables |
| `scripts/release.sh` gate | Unchanged (same line, same tasks) |
| Java API, config keys, wire names, persisted identities | None |
| Test surface | New methods, constants and helpers in `ReleaseGateMatchesCiGateTest`; a new invariant (refused set = key gates in `aimon-llm-*`) that a future provider module must satisfy |
| Docs | No heading or anchor changes; two translation frontmatter `source_commit` values move forward |
| Backlog | No item, count or index change |

---

## 5. Failure modes and handling

| Situation | Behaviour |
|---|---|
| One key exported | Refused at once, exit 1; message names it, not its value; nothing else ran (no `git` call, so no network, Docker or trap note) |
| Both exported | One refusal naming both |
| Key exported as empty string | Refused (D2) |
| Key set in the operator's shell but not exported | Invisible to the script and to Gradle; cannot reach a test. Correctly not refused |
| Key supplied some other way (`~/.gradle/gradle.properties`, `-P`) | Does not reach `@EnabledIfEnvironmentVariable`, which reads the environment only. Not a hole |
| Operator `unset`s, then re-runs in the same shell with a Gradle daemon started earlier with the key | Relies on Gradle applying the client's environment to the daemon for each build, which is what `CONTRIBUTING.md`'s "`unset` it before you build" already relies on. **Not measured** — open question 5 |
| `/release` run by an agent whose environment has a key | Refused. The skill already says to relay the message verbatim and stop; the user re-runs without the key |
| Future provider module gated on a new variable | `refusedKeysAreTheProviderModulesKeyGates` fails until the test and the script both name it. Locally the aimon-core test can be `UP-TO-DATE` if nothing else changed (D5); CI catches it |
| Future non-key gate (e.g. `CI`) under `aimon-llm-*` | Census fails. Its message offers the second exit: narrow the scan with a note (D5) |
| Someone removes, inverts or moves the guard, or prints the value | Removed → per-key refusal cases fail (`Pre-flight checks` printed). Inverted → the no-key case fails. **Moved below `cd` or below `trap cleanup EXIT` → the "no `git` call" assertion fails** (probed, D4). Moved below pre-flight's first log line → the "no `Pre-flight checks`" assertion fails. Value printed → the sentinel assertion fails. Dropped variable → that variable's refusal case fails |
| Someone adds a `$GRADLE ` line or a `quality gate` comment above the gate | Existing gate tests see the wrong line; the new section must do neither (§3.1) |
| Test machine has no `bash` on `PATH`, or a non-POSIX temp file system | New process tests skip with the reason, before any file is created; the census test still runs |
| `@TempDir` on a `noexec` mount | The stub self-check fails the test with that reason, so "no `git` call" cannot pass vacuously |
| Script hangs in the sandbox | Nothing blocking is reachable (the confirmation `read` is after the gate). Output goes to a file, so `waitFor(30, SECONDS)` is always reached; on timeout `destroyForcibly()` and fail |
| Sibling adds a live class on `ANTHROPIC_KEY` / `OPENAI_KEY` | Census unchanged. No cross-run breakage |

---

## 6. Test strategy

The build records every number below in `$RUN_DIR/build/measurements.md`.

0. **No key in the build shell, ever.** Before the first Gradle command:
   `env | grep -c -E '^(OPENAI|ANTHROPIC)_KEY='` must print `0`; this is a count, never the value. Run Gradle as
   `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew …` as belt and braces. **No live call is made in this run.**
1. **Focused tests:**
   `./gradlew :aimon-core:test --tests 'at.aimon.core.architecture.ReleaseGateMatchesCiGateTest' --rerun`.
   Record tests run, failures, skips.
2. **Mutation checks** prove each pin bites. Each is a temporary uncommitted edit, followed by the focused command with
   `--rerun`, then reverted (`git checkout -- <file>` or delete the scratch file). Record which test failed each time.
   - M1: delete the guard block → the per-key and both-keys cases fail.
   - M2a: move the guard below `log "Pre-flight checks"` → the refusal cases fail on `Pre-flight checks`.
   - M2b: move the guard to just after `cd "$(git rev-parse --show-toplevel)"` → the refusal cases fail on the call log
     (`rev-parse --show-toplevel`).
   - M2c: move the guard to just after `trap cleanup EXIT` → the refusal cases fail on the call log (`rev-parse …` and
     the trap's `diff --quiet -- gradle.properties`).
   - M3: make the refusal print a key's value → the sentinel assertion fails.
   - M4: invert the condition (refuse when unset) → `releaseScriptStartsWithoutAProviderKey` fails.
   - M5: drop `ANTHROPIC_KEY` from the script's list → the per-key case fails for `ANTHROPIC_KEY`.
   - M6: add a scratch test class under `modules/aimon-llm-openai/src/test/java/…` annotated
     `@EnabledIfEnvironmentVariable(named = "GEMINI_KEY", matches = ".+")` → the census fails, and its message names
     both exits. Then delete it.
   - M7: temporarily create the stub without the execute bit → the stub self-check fails with its reason. It must not
     pass vacuously.

   **Never run `scripts/release.sh` against the worktree.** The only execution of it is the sandboxed harness.
3. **Item 4 fact, measured without a key.** On `:aimon-llm-openai` (the module the paragraph is about; without a key
   its live classes skip):
   - `test` twice → the second run is `UP-TO-DATE`;
   - `:aimon-llm-openai:cleanTest` then `test` → executes;
   - `:aimon-llm-openai:clean` then `test` → executes.

   Record the task outcome lines.
4. **Gate:** `./gradlew format`, then `./gradlew checkAll`. Record totals (tests, failures, skips) from the build
   output or reports.
5. **Doc checks, all four:** `check-doc-links.py` (the new `#live-api-tests` / `#라이브-api-테스트` links),
   `check-backlog-registers.py`, `check-translation-staleness.py` (expect 32 up to date, 0 stale, 0 unresolvable),
   `check-translation-structure.py` (expect 32 identical).
6. **Grep acceptance 1:** `git grep -n -E 'export (OPENAI|ANTHROPIC)_' -- README.md docs/README.md docs/README.en.md`
   → no output.

---

## 7. Open questions (not resolvable from the task statement; the default taken is stated)

1. **Should `.claude/skills/release/SKILL.md` name the new refusal?** Its "enforces every safety gate (…)" list becomes
   incomplete, but it is not one of this run's files. *Default:* not edited; recorded as a deviation and in the PR body.
   The same applies to `docs/project/publishing-guide.md`'s table, which already omits the Docker check.
2. **Should the release script also refuse `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT`?** Exported, they too make the
   release gate run tests CI does not. They are not provider keys, and `LA-1` §0.1 left their rationale unverified.
   *Default:* out of #98; deviation, not registered.
3. **D2 (empty counts as set)** and **D5 (the census scoped to `aimon-llm-*`)** are decisions, not unknowns, but they
   are the two a maintainer is most likely to want differently. Each is one constant plus one test case to reverse.
4. **`source_commit` practice is mixed** (§0). *Default:* follow `CLAUDE.md`'s rule (the canonical's last commit
   before the edit), re-derived at build time. Leaving the old value would also pass the checker.
5. **Gradle daemon and `unset`.** The claim that unsetting and re-running in the same shell keeps the key out of a warm
   daemon's test workers rests on Gradle's documented per-build environment handling, not on a measurement here.
   Measuring it without billing needs either a throwaway Gradle build or a dummy-key round trip (an unbilled 401).
   *Default:* not measured, and stated as such in the handoff.
6. ~~**bash 5 and `cd ""`.**~~ **Closed by review 1:** probed on bash 5.1.16 with the same results as 3.2.57. The stub
   stays, for its call log (D4).

## 8. For `build/deviations.md`

- `modules/aimon-cli/examples/gpt-5.6-terra.yaml:3-4` — `export OPENAI_KEY=sk-...` then `./gradlew :aimon-cli:run --args=…`.
- `CONTRIBUTING.md:58` / `CONTRIBUTING.ko.md:63` ("excludes @Tag("docker")") and `CONTRIBUTING.md:125-127` /
  `CONTRIBUTING.ko.md:128-130` ("Docker/Testcontainers tests stay out of it") — the same incomplete tag list, outside
  § Live-API tests.
- `CHANGELOG.md:1718-1732` — #90's bullet repeats "`test` excludes only `docker` and `packaging`".
- `docs/design/llm/anthropic-thinking-traces.md:643` (F-8) and `docs/design/llm/anthropic-sampling-capabilities.md:733`
  — "the release gate makes real billed calls" is superseded; frozen design records.
- `.claude/skills/release/SKILL.md:10-13` and `docs/project/publishing-guide.md:138-144` do not name the refusal (the
  latter also omits Docker, and names `checkAll` alone as the gate).
- `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT` exported would widen the release gate beyond CI's; not refused.
- `modules/aimon-core/build.gradle.kts:64-71` does not declare other modules' test sources as inputs of aimon-core's
  `test`, so both source scans in `ReleaseGateMatchesCiGateTest` can be skipped as `UP-TO-DATE` locally.
- `docs/overview/architecture.md:638` / `.en.md:668` describe `ReleaseGateMatchesCiGateTest` as task equality only —
  still true, no longer the whole of it.

---

## 9. After the build — departures, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 2 (PASS, no blocking findings, five non-blocking notes), and it is not edited to look prescient. Three sources
feed this section: the run's `build/deviations.md`, review 2's notes, and what was measured while building.*

**D1–D7 were built as written.** The refusal (set means set, even empty; the names that are set, never a value; exit
1), its place (after the argument loop, before `cd`, the `EXIT` trap and pre-flight), the sandboxed harness with its
recording stub, the census against `aimon-llm-*`, the quickstart sentences and the two `CONTRIBUTING` sentences are the
body's. What departed is D8, the census reader, one more test, and choices the body left to the build.

### 9.1 Where the build departed from the body

- **DV-1 — `LA-2` was registered.** D8 and §3.7 say no new item. The build was asked to promote every open question
  whose consequences reach beyond this change, within the one ID reserved for this run, so §7 Q2 (`AIMON_DOCKER_IT` /
  `AIMON_KUBERNETES_IT`) became [`LA-2`](../../backlog/live-api-test-tier.md) — exactly where the new refusal stops.
  Its "why" was verified by reading rather than borrowed from `LA-1` §0.1: neither class carries a `@Tag`, so its
  module's `test` runs it once the variable is `true`; `checkAll` runs every module's `test`; nothing under `.github/`
  sets either variable; and both classes' `@BeforeAll` throw instead of skipping when the daemon or cluster is out of
  reach. What `LA-1` §0.1 left unverified — why the two are outside CI — is written into `LA-2` as the premise to check
  before deciding, and its severity is marked unmeasured. The register now reads 2 items (1 open · 1 closed), and so
  does its index row; the two dated notes on `LA-1` went in as §3.7 says.
- **DV-2 — this file.** The body did not place itself. It lands in `llm/`, beside `anthropic-thinking-traces.md`,
  whose F-8 is the live-tier record this change touches, and is indexed in [`../README.md`](../README.md). A `build/`
  or `release/` domain was rejected, because that README allows one non-domain axis (`documentation/`) and gives its
  reason. That README's §3.2–§3.3 also ask a design doc to carry no test strategy and no `file:line` in its body; this
  body keeps both, on the precedent of [`provider-switch-agent-model-check.md`](provider-switch-agent-model-check.md):
  an approved body stays as approved, and the departures go at the end.
- **DV-3 — the refusal prints its reasons first and its red `✗` last.** §3.1's sketch, which the body leaves to the
  build, puts the red headline first. The build prints the explanation and remedy with `printf` and ends with
  `fail "Refusing to start while a provider API key is in the environment: <names>"`, so the helpers moved above `cd`
  are actually what formats the refusal, and the last line is a one-line verdict naming the variables. The optional
  echo of the parsed arguments was taken (`env -u OPENAI_KEY scripts/release.sh --dry-run`), and the wording switches
  between `is`/`are` and `it`/`them`, so no name that is not set appears anywhere in the message.
- **DV-4 — the script comment names four positions the test catches, not three.** §3.1 item 3 limits the comment to
  below `cd`, below the trap and below `log "Pre-flight checks"`. The build also took review 2's bad-argument case
  (§9.2), so moving the check **above the argument loop** fails a test too, and the comment says so. It also says the
  two `AIMON_*_IT` variables are not refused and points at `LA-2`, so that item's first trigger passes through the one
  file a release operator reads.
- **DV-5 — the census reads the whole annotation.** §3.2's single regex expected `named` straight after the
  parenthesis and claimed an unreadable form "fails loudly". Review 2 showed that the miss is silent for the case D5
  exists for: a new provider module's one new name goes unread while the old names are still found. The build has two
  constants: `KEY_GATE_ANNOTATION` opens on `@EnabledIfEnvironmentVariable(` or the `@EnabledIfEnvironmentVariables(`
  container, and the text is read through the line where its parentheses balance (ignoring those inside string
  literals). Every `KEY_GATE_NAME` (`named = "…"`) in that text counts. The "fails loudly" sentence was not written.
- **DV-6 — harness details the body did not fix.** Each case runs in its own sub-directory of the method's `@TempDir`,
  so the per-key loop never reuses a call log. The stub's log path is asserted to contain no `'` before it is written
  single-quoted. The script's stdin is closed right after `start()`. The keyless case asserts only what §3.2 lists —
  nothing about the exit status or the pre-flight failure's wording, so rewording that message cannot fail it.
- **DV-7 — one test more than §3.2's table.** `releaseScriptRejectsABadArgumentBeforeTheKeyCheck` runs
  `--bogus` with `OPENAI_KEY` set and requires exit 2, the usage line, no refusal and no `git` call. The class went from
  six tests to twelve. The class javadoc's *What is enforced* bullet claims that ordering too, and *What this cannot
  see* says the `AIMON_*_IT` classes are outside the census on purpose.
- **DV-8 — `CHANGELOG.md`.** The heading carries no issue number, as no heading there does; `(#98)` is in the first
  bullet. The "Pinned" bullet also names the bad-argument run and `LA-2`.
- **DV-9 — nothing was committed by the build**, as instructed; §3.9's plan stands for whoever commits. Each
  translation pair has to land in one commit with its `source_commit` edit, or the staleness check will not skip that
  commit.

### 9.2 Review 2's non-blocking notes

| Note | Taken? | Where |
|---|---|---|
| 1 — the census regex misses a reordered attribute or the container, silently | Taken | DV-5; probes M6b–M6e in §9.4 |
| 2 — §3.9's PR-body headings drop D3, D7 and D8 | Taken | The PR body gets one heading for each of D1–D8; D8's has to say `LA-2` was registered after all (DV-1) |
| 3 — "after argument parsing, so exit 2" is stated but not pinned | Taken | DV-4, DV-7; M8 in §9.4 |
| 4 — the stub self-check fails where the other two conditions skip | Kept as a failure, with the reason written | `requireRunnableStub`'s javadoc: the two skips mean the harness cannot be built on that machine (no bash, so no release script either; no POSIX permissions, so no execute bit). A stub that was built and still cannot run turns up on a machine that can cut a release, and a skip there would leave the refusal unpinned exactly where it matters. M7 in §9.4 |
| 5 — open question 5 can be closed by proxy | Taken as a record | Q5 below |

### 9.3 The open questions and §8's findings

| Item | Now | Why |
|---|---|---|
| Q1 — `.claude/skills/release/SKILL.md` and `docs/project/publishing-guide.md` do not name the refusal | PR body, for the maintainer to register | Its consequences outlast this change, but the run had one reserved ID, and TASK.md sends an item that needs more IDs than a run has to the PR body. It is also the weaker item: a documentation fix with no revisit trigger. The skill already relays an abort verbatim and stops, so a refusal is handled correctly meanwhile |
| Q2 — `AIMON_DOCKER_IT` / `AIMON_KUBERNETES_IT` | backlog `LA-2` | DV-1 |
| Q3 — D2 (empty counts as set) and D5 (census scoped to `aimon-llm-*`) | here | Phase-local decisions for the reviewer to overturn; each is still one constant plus one test case |
| Q4 — mixed `source_commit` practice | here, settled | `CLAUDE.md`'s rule was followed: `docs/README.en.md` → `4f677a6`, `CONTRIBUTING.ko.md` → `a1236c8`. The staleness check reports 32 up to date, 0 stale, 0 unresolvable |
| Q5 — a warm Gradle daemon after `unset` | closed by review 2, not repeated | Review 2 ran a throwaway build twice on the same daemon (same pid): the variable reached an `Exec` child while the client had it and did not once the client dropped it. That was an `Exec` task, not a `Test` worker, and the build did not repeat it |
| Q6 — bash 5 and `cd ""` | closed by review 1 | — |
| §8 — `modules/aimon-cli/examples/gpt-5.6-terra.yaml` still exports the key | PR body | Outside the issue's list, and owned by no run in this batch |
| §8 — the other incomplete tag lists (`CONTRIBUTING` § Test and § Quality Checks, #90's `CHANGELOG` bullet), F-8 and the sampling-capabilities note, the architecture overview's one-line description, and `aimon-core`'s undeclared test-source inputs | PR body | Other sections, frozen records, or files outside this run; none is a decision with a trigger. The test javadoc states the inputs gap |

### 9.4 What was measured

*2026-09-11, macOS arm64, `/bin/bash` 3.2.57 (the only bash on `PATH`). No live API call was made, billed or unbilled,
and no key file was read. Every Gradle command ran as `env -u OPENAI_KEY -u ANTHROPIC_KEY ./gradlew …`, from a shell
where `env | grep -c -E '^(OPENAI|ANTHROPIC)_KEY='` printed `0`.*

**The new tests.** `ReleaseGateMatchesCiGateTest` with `--rerun`: 12 tests, 0 failures, 0 skipped. Each of the six new
ones takes between 0.02 and 0.7 seconds.

**Every pin bites.** Each mutation was a temporary edit, then the focused command with `--rerun`, then a byte-for-byte
restore from a scratch copy.

| # | Mutation | Failed | Which tests, on what |
|---|---|---|---|
| M1 | guard deleted | 3 of 12 | per-key, empty-key and both-keys: the output never names the key, because the script ran on to pre-flight and stopped there, inertly |
| M2a | guard below `log "Pre-flight checks"` | 3 | the same three: "got as far as its pre-flight checks" |
| M2b | guard just below the `cd` | 3 | the same three: "invoked git before refusing" |
| M2c | guard just below `trap cleanup EXIT` | 3 | the same three: "invoked git before refusing" |
| M3 | the message prints `${!name}` | 2 | per-key and both-keys: "printed a key's value". The empty-key case passes, its value being empty |
| M4 | condition inverted | 4 | the keyless case ("did not reach its pre-flight checks") and all three refusal cases |
| M5 | `ANTHROPIC_KEY` dropped from the script's list | 2 | per-key (for `ANTHROPIC_KEY`) and both-keys |
| M8 | helpers and guard moved above the argument loop | 1 | bad-argument: "exited 1 rather than 2" |
| M7 | stub written `rw-r--r--` | 5 | all five process tests, on the self-check: "the stub git at … cannot be started". The census passes |
| M6a | a scratch class under `aimon-llm-openai/src/test` gated `named = "GEMINI_KEY", matches = ".+"` | 1 | census: "gated on [ANTHROPIC_KEY, GEMINI_KEY, OPENAI_KEY], but scripts/release.sh refuses [ANTHROPIC_KEY, OPENAI_KEY]" |
| M6b | the same with `named` after `matches` | 1 | census, same message |
| M6c | the one-line `@EnabledIfEnvironmentVariables({ … })` container, `GEMINI_KEY` second, with a `matches` regex that contains parentheses | 1 | census, same message |
| M6d | fully qualified, arguments wrapped over three lines | 1 | census, same message |
| M6e | negative probe: the annotation only inside javadoc `{@code …}` and a `//` comment | 0 | none — mentions are not read |

Before any test existed, a scratch copy of the edited script ran the five cases by hand with the same stub. The keyless
case took the path D4 describes: `▶ Pre-flight checks`, then `✗ gradle.properties not found`, with the stub called
for `rev-parse --show-toplevel` and for the trap's `diff --quiet -- gradle.properties`.

**D7's fact about `clean` and `cleanTest`**, without a key, on `:aimon-llm-openai`, one invocation per step: `test`
executed; `test` again reported `UP-TO-DATE`; `cleanTest`, then `test`, executed; `clean`, then `test`, executed. The
last run had 313 tests and 17 skipped, which are exactly the nested classes of the module's two live classes.

**The gate.** `./gradlew format` rewrapped two assertion chains in the census test and nothing else.
`./gradlew checkAll`: BUILD SUCCESSFUL in 3m 45s, 240 actionable tasks (113 executed, 127 up-to-date), with
`checkFormat`, `checkStyle` and `verifyBom` among them. Twenty-one module `test` tasks executed: 10584 tests, 0
failures, 0 errors, 55 skipped. One, `:aimon-llm-openai:test`, was `UP-TO-DATE` from the measurement just above; with
its 313 tests the tree counts 10897 tests, 0 failures, 72 skipped.

**Documentation.** Run as CI invokes them, every one exits 0: `check-doc-links.py` (0 broken links),
`check-backlog-registers.py --github` and `--self-test`, `check-translation-staleness.py --github` (32 up to date,
0 stale, 0 unresolvable), and `check-translation-structure.py --self-test` and `--github` (32 structurally identical).
`mkdocs build --strict` also exits 0, and the built pages keep both new link fragments.

**Not measured.**
- A release run with a key exported, carried over from the issue. The refusal has only run in the sandbox.
- The test on bash 5. Review 1 probed `${!name+set}` and `cd ""` on bash 5.1.16, but the harness itself has only run
  on 3.2.57, so CI's `ubuntu-latest` will be its first bash 5 run.
- Q5, which review 2 measured and the build did not repeat.
