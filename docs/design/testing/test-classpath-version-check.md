# Design — a check that a module's tests run on the versions the module ships (D-3)

> Status: **IMPLEMENTED** — `checkTestClasspathVersions`, registered on every module by `aimon.java-conventions` and
> aggregated by the root `checkAll`; the record it reads, `gradle/test-classpath-version-differences.txt`. Source:
> backlog [D-3](../../backlog/module-dependency-scope.md). What it left open is in section 6.
>
> In English, like the two records it follows
> ([`test-classpath-shipped-versions.md`](test-classpath-shipped-versions.md),
> [`shipped-logback-and-test-classpath-followups.md`](shipped-logback-and-test-classpath-followups.md)).
> Every number here was measured on 2026-10-05 on the tree this check was built on.

---

## 1. The problem

[#91](https://github.com/kangwoo/aimon-core/issues/91) set the bar: *a module's tests run against the versions the
module ships, unless the difference is a recorded choice.* #99 decided the differences that existed, source by source,
and wrote the decisions down — as a comment block above `junit` in `gradle/libs.versions.toml` and in a design
record. Nothing read either.

A prose record is a measurement of the day it was written. The first run of this check is the evidence:

| What the record said | What the build resolves |
|---|---|
| `org.jetbrains:annotations` 13.0 → 17.0.0 on six modules, accepted | **True**, on the same six |
| `error_prone_annotations` 2.21.1 → 2.33.0 on `aimon-spring-boot-starter`, accepted | **Gone.** Caffeine 3.3.0 asks for 2.50.0, and both classpaths now resolve 2.50.0. A bump made the entry false and nothing went red |
| *(nothing)* | `jakarta.annotation-api` 2.1.1 → 3.0.0 on `aimon-rewake-webhook` |
| *(nothing)* | `org.jspecify:jspecify` 1.0.0 → 1.0.1 on `aimon-session-redis` |
| *(nothing)* | `micrometer-observation` and `micrometer-commons` 1.16.7 → 1.17.1 on `aimon-spring-boot-starter` |

Ten differences on seven modules; six recorded, four on no list, and one recorded difference that no longer exists.
The other sixteen modules the check runs on have none.

## 2. Decisions

| Question | Decision | Rejected, and why |
|---|---|---|
| Is there a check | **Yes.** A task per module, in `checkAll` | Leaving the record as prose: section 1 is what that costs |
| What is compared | `runtimeClasspath` against `testRuntimeClasspath`: every external module on both, matched by `group:name`, from `incoming.resolutionResult` | Artifact file names — a classifier or a relocated jar reads as a different library. Project dependencies are skipped: they have no version to differ in |
| Where the accepted differences live | `gradle/test-classpath-version-differences.txt`, one line each, **with the reason as a field the task requires** | A comment block (not read). A `.properties` file like the two baselines beside it — a coordinate holds a colon and a reason holds prose, both of which need escaping there, in a file edited by pasting the line a failure printed |
| Does a line carry the versions | **Yes**, both. A line whose versions are no longer the ones resolved fails | A line per `module + coordinate` only. It would have let 13.0 → 17.0.0 become 13.0 → 26.0.2 without anyone rereading "an annotations jar no test run loads" — a claim about a particular jar |
| What about a line that no longer applies | It **fails**: the difference is gone, or the module is not in the build | Tolerating it. `PackageDependencyArchitectureTest` has the sentence: *a baseline nobody shrinks is a baseline nobody reads* |
| Is `testCompileClasspath` compared too | **No** | See section 4 |
| The four unrecorded differences | **Recorded as undecided**, not decided here | Aligning or accepting them inside this change: each is the per-source choice #99 made with `javap` over the jars, and none of that was done. Leaving them out: the check would start red |

The stale rule has a half no module can see. Each module's task reads only its own lines, so a line for a module
that was renamed, removed or misspelled would be read by none. A root task, `checkRecordedDifferenceModules`, fails
on it. It is not the aggregate's own action because under `--continue` a task whose dependency failed is skipped —
the orphaned line would go unreported in the one run that was collecting everything.

## 3. What a failure says

Each of the three failures prints the line to write or delete.

```text
aimon-session-redis: the versions its tests run on and the versions it ships differ from what is recorded.

  org.jspecify:jspecify: ships 1.0.0, tests run on 1.0.1, and nothing records why.
    Either resolve the test classpaths like runtimeClasspath (modules/aimon-cli/build.gradle.kts shows how), or add to gradle/test-classpath-version-differences.txt:
      aimon-session-redis | org.jspecify:jspecify | 1.0.0 -> 1.0.1 | <why the tests may run on a version this module does not ship>

  Why a version got picked: ./gradlew :aimon-session-redis:dependencyInsight --configuration testRuntimeClasspath --dependency <name>
```

The other two read *"recorded as 12.0 -> 17.0.0 (file:line), and is now 13.0 -> 17.0.0 … rewrite the line as"* and
*"recorded as … but both classpaths resolve 2.22.3. Delete the line"*. A line the parser cannot read, a line with no
reason and a second line for the same module and coordinate each fail with the file and line number.

Every module also writes what it found to `build/reports/test-classpath-versions/differences.txt`.

## 4. The compile axis is left out

Measured the same day: `testCompileClasspath` differs from `runtimeClasspath` in **43** places across **17** modules.

| Coordinate | Shipped → on the test compile classpath | Modules |
|---|---|---|
| `org.yaml:snakeyaml` | 2.7 → 2.6 | 16 |
| `org.jetbrains:annotations` | 13.0 → 17.0.0 | 6 |
| `org.slf4j:slf4j-api` | 2.0.20 → 2.0.18 / 2.0.19 | 5 |
| `com.google.errorprone:error_prone_annotations` | 2.50.0 / 2.49.0 / 2.33.0 → 2.38.0 | 5 |
| Jackson (`annotations`, `core`, `databind`, `bom`) | 2.22.x → 2.19.4 / 2.10.3 | 6 lines on 3 modules |
| the four unrecorded ones of section 1, and `kotlin-stdlib` 2.2.21 → 1.9.0 | | 5 lines |

Thirty-two of the 43 are a **lower** version than the one shipped. That is not drift: a compile classpath resolves
a smaller graph than a runtime one (no `runtimeOnly`, no `implementation` of a dependency), so fewer askers take part
in conflict resolution and the winner is older. It says nothing about what the tests run on, which is the bar. A
43-line list of exemptions for it would be the unread table this check replaces. (The backlog item quotes 72; that
was a different quantity — entries that *changed* when consistent resolution was applied to every module.)

Against `compileClasspath` instead, the test compile classpath differs in 20 places. Not gated either; the number is
here so the next person does not measure it again.

## 5. Do not

- **Do not resolve a configuration while the project is being configured.** The task takes
  `resolutionResult.rootComponent` providers; Gradle resolves them when the task runs or when it stores the
  configuration cache entry (stored and reused with no problems reported, Gradle 9.8.0).
- **Do not record a difference without reading where it comes from.** `dependencyInsight` on both configurations is
  two commands, and the failure prints one of them.
- **Do not treat the four UNDECIDED lines as accepted.** They are in the file so the check can gate the *next*
  difference. The file says so above them.
- **Do not add `testCompileClasspath`** without first explaining the 32 lower versions.
- **Do not move the reasons back into comments.** A reason the task does not require is a reason that can be left
  out.

## 6. Open

1. **The four undecided differences.** By the precedent D-2 set, `aimon-rewake-webhook`'s `jakarta.annotation-api`
   has the shape that was *aligned* on `aimon-knowledge-opensearch` (same source, and no test in the module starts
   Spring). The two Micrometer jars on the starter are code, not annotations, and the higher version comes from the
   module's own test dependency on `spring-boot-starter-actuator` — an application that adds Actuator resolves that
   version too. `jspecify` is an annotations jar. None of the jars was opened for this change.
2. **A dependency bump now fails `checkAll` when it moves a recorded version.** That is the point — the item's own
   trigger is "the next Spring Boot or Testcontainers bump" — and it also means a Dependabot PR for Testcontainers,
   OkHttp, Caffeine, Lettuce, Javalin or Spring Boot can go red on a line it cannot edit. The fix is one pasted line.
   How often it happens is not known yet.
3. **Whether consistent resolution still holds on the three aligned modules** is answered only by absence: if
   `shouldResolveConsistentlyWith` silently stopped working, their differences would reappear as unrecorded and
   fail. That is the Gradle-upgrade trigger D-3 listed, and it is now covered.

## Reference map

| What | Where |
|---|---|
| The task, the record's parser, the orphan check | `buildSrc/src/main/kotlin/TestClasspathVersionsTask.kt` |
| Registration on every module | `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` |
| The aggregate and its place in `checkAll` | `build.gradle.kts` |
| The record | `gradle/test-classpath-version-differences.txt` |
| The decisions the record's first six lines carry | [`test-classpath-shipped-versions.md`](test-classpath-shipped-versions.md) section 3.2 |
| The three modules with no line because they align | `modules/aimon-cli/build.gradle.kts`, `modules/aimon-scheduling-quartz/build.gradle.kts`, `modules/aimon-knowledge-opensearch/build.gradle.kts` |
| CI and the release gate, which both run `checkAll` | `.github/workflows/build.yml`, `scripts/release.sh` |
