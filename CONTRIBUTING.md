# Contributing to AIMON Core

> 한국어: [`CONTRIBUTING.ko.md`](CONTRIBUTING.ko.md)

First off, thank you for considering contributing to AIMON Core! This document outlines how to set up your environment, the conventions we follow, and the process for submitting changes.

For everything it does not cover — architecture, per-feature guides, design records — start at
[`docs/README.md`](docs/README.md), the documentation catalog.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Ways to Contribute](#ways-to-contribute)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [Coding Conventions](#coding-conventions)
- [Commit Messages](#commit-messages)
- [Branching & Pull Requests](#branching--pull-requests)
- [Developer Certificate of Origin (DCO)](#developer-certificate-of-origin-dco)
- [Reporting Security Issues](#reporting-security-issues)

---

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating you agree to abide by its terms.

## Ways to Contribute

- **Report bugs** — open an issue using the *Bug report* template
- **Propose features** — open an issue using the *Feature request* template, or start a Discussion if it needs design input
- **Improve documentation** — typos, clarifications, translations are all welcome
- **Submit code** — fix a bug, add a tool/hook, implement a new LLM provider or storage backend
- **Triage** — reproduce reported bugs, label issues, review pull requests

If you're new and want a place to start, look for issues labeled `good first issue` or `help wanted`.

## Development Setup

### Prerequisites

- **Java 17+** (build toolchain pins JDK 17)
- **Gradle 8.x** — use the included wrapper (`./gradlew`); no system install required
- **An LLM API key**, only to run the live-API tests — `ANTHROPIC_KEY` or `OPENAI_KEY`; see [Live-API tests](#live-api-tests)
- (Optional) **Docker** for tests that use Testcontainers (MongoDB, Redis, PostgreSQL, OpenSearch)

### Build

```bash
./gradlew build               # Compile + test all modules
./gradlew :aimon-core:build   # Single module
./gradlew :aimon-cli:run      # Run the REPL
```

### Test

```bash
./gradlew test                                                        # All unit tests (excludes @Tag("docker"))
./gradlew :aimon-core:test                                            # Single module
./gradlew :aimon-core:test --tests "at.aimon.core.agent.tool.*Test"   # Glob pattern
./gradlew :aimon-core:test --tests "at.aimon.core.agent.tool.ToolInputTest"  # Single class
```

### Live-API tests

Four test classes call a provider's real API, and each one is gated on that provider's key with
`@EnabledIfEnvironmentVariable`:

| Class | Module | Key |
|-------|--------|-----|
| `AnthropicThinkingLiveTest` | `aimon-llm-anthropic` | `ANTHROPIC_KEY` |
| `AnthropicLlmClientIntegrationTest` | `aimon-llm-anthropic` | `ANTHROPIC_KEY` |
| `OpenAIReasoningLiveTest` | `aimon-llm-openai` | `OPENAI_KEY` |
| `OpenAILlmClientIntegrationTest` | `aimon-llm-openai` | `OPENAI_KEY` |

**This tier has no CI signal at all.** No workflow supplies either key, so wherever the keys are
absent — CI included — each of these classes reports `SKIPPED` and `checkAll` stays green. The
tier is verified only when a person chooses to verify it:

```bash
ANTHROPIC_KEY=... OPENAI_KEY=... \
./gradlew :aimon-llm-anthropic:test --rerun \
              --tests 'at.aimon.core.llms.anthropic.AnthropicThinkingLiveTest' \
              --tests 'at.aimon.core.llms.anthropic.AnthropicLlmClientIntegrationTest' \
          :aimon-llm-openai:test --rerun \
              --tests 'at.aimon.core.llms.openai.OpenAIReasoningLiveTest' \
              --tests 'at.aimon.core.llms.openai.OpenAILlmClientIntegrationTest'
```

**Every run costs money** — these are billed calls on the account the keys belong to. Never commit a
key, and redact it from any failure output you paste into an issue or a pull request.

**The gate works in the other direction too, and that is why the command above scopes the keys to
itself.** The environment variable is the only thing keeping these classes out of an ordinary build: they
carry no tag, and the only exclusions in a module's `test` task are by tag: `docker` and `packaging`,
which the conventions plugin excludes in every module, and `playwright`, which `aimon-browser-playwright`
excludes as well. So while a key is exported in a shell — for this tier, or to run the CLI — every
`./gradlew test` and `checkAll` in that shell runs that provider's live classes as well, not only the
command above. That happens each time the module's `test` task executes rather than reporting
`UP-TO-DATE` — for example the first build, a build after `clean` or `cleanTest`, and any build after a
change that reaches the module. Those runs call the API and bill. They can also go red for
reasons that have nothing to do with the change being built: a key that is no longer valid fails them on
authentication (HTTP 401), and the rot described below fails them from the provider's side. Put the key
in front of the one command that needs it, as above, or `unset` it before you build.

**`--rerun` is not optional.** Gradle does not treat environment variables as inputs to `test`, so if
nothing else has changed since these tasks last ran, the run reports `UP-TO-DATE` and executes nothing.
Supplying a key after a keyless run is exactly that case: green, in under a second, with no test output
at all. Confirm the output lists the tests as `PASSED` — `SKIPPED` means a key was missing, and no
test lines at all, neither `PASSED` nor `SKIPPED`, means the task did not run.

Expect the tier to have rotted since anyone last ran it. With no signal, rot is the steady state rather
than a surprise — a provider rewords an error, retires a model, or starts refusing a request shape — and
it is found by whoever runs the tier next. If that is you, fix it or open an issue rather than stepping
past it. Why the tier has no scheduled run, and what would change that, is recorded in
[`docs/backlog/live-api-test-tier.md`](docs/backlog/live-api-test-tier.md).

### Quality Checks

Before pushing:

```bash
./gradlew format     # Apply Spotless (Eclipse formatter)
./gradlew checkAll   # checkFormat + checkStyle + every module's unit tests
```

`checkAll` is the single gate: it runs the format check, Checkstyle, **and** each module's `test`
task. A separate `./gradlew test` is no longer needed. Docker/Testcontainers tests stay out of it —
they are tagged `@Tag("docker")` and run via `./gradlew integrationTest`.

When a check fails, the HTML reports say why:

```
modules/<module>/build/reports/checkstyle/main.html   # Checkstyle violations
modules/<module>/build/reports/tests/test/index.html  # Test failures
modules/<module>/build/reports/jacoco/                # Coverage
```

CI (GitHub Actions) runs `./gradlew checkAll` on every PR — broken builds will be flagged automatically. See `.github/workflows/build.yml`.

Documentation has its own gates, which `checkAll` does not cover:

```bash
python3 scripts/check-doc-links.py             # every relative markdown link, target and anchor
python3 scripts/check-translation-staleness.py # is each translation current with its canonical
python3 scripts/check-translation-structure.py # does each translation still have the same shape
python3 scripts/check-backlog-registers.py     # docs/backlog/: duplicate item IDs, and counts that disagree with the items
```

The first walks every `*.md` in the repository and fails on two things: a link to a path
that does not exist, and a `#fragment` that matches no heading in the file it points at.
The second one matters more than it sounds — a wrong anchor still loads the page, so the
reader lands at the top and never learns they were sent to the wrong section. External
URLs are deliberately not checked; a gate that goes red because someone else's host is
down stops being read. CI runs this as the `docs-links` job.

The other two are about translations and run together as the `translations` job; what
each fails on, and why one of them mostly does not, is under
[Translations](#translations). Both need the full git history, so on a shallow clone they
report rather than fail and say so.

### Previewing the documentation site

`docs/` is also published as a site (MkDocs Material) at
<https://kangwoo.github.io/aimon-core/>. To see your change the way readers will:

```bash
pip install -r docs-requirements.txt
mkdocs serve            # http://127.0.0.1:8000
mkdocs build --strict   # what CI runs; warnings are failures
```

Two things about that site are worth knowing before you edit a link.

Korean is the site's default language and builds at the root; English translations are
`*.en.md` files served under `/en/`. A page with no translation yet is *not* a 404 — the
Korean original is served in its place, so the site stays whole while translation
proceeds. See [`docs/project/documentation-guide.md`](docs/project/documentation-guide.md)
for the translation conventions, and for which directories are published to the site at all.

Links that point outside `docs/` — at a source file, at `CHANGELOG.md` — stay **relative**
in the source, because that is what works when the file is read on GitHub. A build hook
(`scripts/mkdocs_github_links.py`) rewrites just those into GitHub URLs when the site is
built. So write the link that works on GitHub and leave the site to the hook.

### IDE Setup

Import the Eclipse formatter into your IDE for consistent formatting:

- **Config file:** `config/eclipse/eclipse-formatter.xml`
- **Import order:** `java` → `javax` → `jakarta` → `org` → `com` → (blank) → project imports

## Project Structure

```
modules/
├── aimon-bom                    # java-platform BOM: every module's version
├── aimon-core                   # Framework core: agents, tools, skills, hooks, scheduling, VFS
├── aimon-bootstrap              # Framework-neutral assembly: AimonStack + ordered teardown
├── aimon-spring-boot-starter    # Spring Boot autoconfiguration over aimon-bootstrap
├── aimon-cli                    # Reference REPL application
│
├── aimon-llm-openai             # OpenAI LlmClient
├── aimon-llm-anthropic          # Anthropic LlmClient
├── aimon-llm-capability-testkit # Shared model-capability binding contract tests
│
├── aimon-filesystem-gridfs      # MongoDB GridFS VFS
├── aimon-filesystem-s3          # AWS S3 VFS
├── aimon-filesystem-testkit     # Shared VirtualFileSystem contract tests
│
├── aimon-sandbox                # Sandbox abstraction
├── aimon-sandbox-docker         # Docker backend
├── aimon-sandbox-kubernetes     # Kubernetes backend
│
├── aimon-session-routing        # Multi-node session routing (SPIs live in aimon-core)
├── aimon-session-testkit        # Shared multi-node session contract tests
├── aimon-session-redis          # Redis session store
├── aimon-session-postgres       # PostgreSQL session store
├── aimon-session-mongodb        # MongoDB session store
│
├── aimon-memory-testkit         # Shared five-tier PeerMemory contract suite (published)
│
├── aimon-knowledge-opensearch   # OpenSearch knowledge store
├── aimon-scheduling-quartz      # Distributed cron scheduler
├── aimon-workflow-graaljs       # GraalJS-scripted subagent workflow
├── aimon-rewake-webhook         # HMAC-verified HTTP endpoint that fires rewake
└── aimon-browser-playwright     # Playwright browser automation

samples/
├── aimon-sample-app             # Minimal embedding example
├── aimon-sample-skills-alpha    # Example skill bundle
└── aimon-sample-skills-beta     # Example skill bundle
```

A new JVM module opts into the shared build configuration with the pre-compiled script plugins
under `buildSrc/src/main/kotlin/` — `aimon.java-conventions` for every module, plus
`aimon.publishable` for the ones released to Maven Central.

When adding a new feature, prefer extending an existing module over creating a new one. New modules should be discussed in an issue first.

## Coding Conventions

### Language & Style

- **Java 17** — feel free to use pattern matching, records (sparingly — see below), `var` where it improves readability
- **Prefer `class` over `record`** — domain objects use immutable classes with the builder pattern. See `at.aimon.core.agent.AgentContent` for the canonical example.
- **Null safety** — `Objects.requireNonNull` at constructor / method entry points; use `Optional<T>` for nullable returns; annotate with JetBrains `@Nullable` / `@NotNull` where it adds clarity.
- **No exceptions from `Tool#execute`** — return `ToolResult.error(...)` instead. See [tool-development-guide.md](docs/features/tool/tool-development-guide.md).

### Extension Points

Most contributions plug into one of four extension points, each with a dedicated guide:

| Extension point | Guide |
|-----------------|-------|
| **Tool** — how the agent acts on the outside world | [tool-development-guide.md](docs/features/tool/tool-development-guide.md) |
| **Hook** — lifecycle interception (`PreTool`, `PostTool`, `OnStop`, …) | [hook-development-guide.md](docs/features/hook/hook-development-guide.md) |
| **LLM provider** — a new `LlmClient` implementation | [llm-provider-development-guide.md](docs/features/llm/llm-provider-development-guide.md) |
| **Skill** — a declarative bundle of prompt, tools, and hooks | [agentskills-specification.md](docs/references/agentskills-specification.md) + [aimon-skill-extensions.md](docs/references/aimon-skill-extensions.md) |

Before naming a new type, read [scope-model.md](docs/overview/scope-model.md) and
[glossary.md](docs/overview/glossary.md). Component lifetime rules (Application / Agent / Session /
Live session) and the `turn` vs `iteration` vs `execution` distinction are enforced by ArchUnit
tests, so a name that contradicts them fails the build rather than the review.

### SOLID

We follow [SOLID principles](docs/project/solid-principles.md):

- One responsibility per class
- Extend via interfaces, not by modifying existing code
- Sub-types must honor the parent's contract
- Small, focused interfaces
- Depend on abstractions, inject via constructor

### Tests

- **JUnit 5 + AssertJ + Mockito** are the defaults; **Testcontainers** for integration tests,
  tagged `@Tag("docker")` so they stay out of `checkAll`
- Test class name = `<ClassUnderTest>Test`; one test per behavior
- Group related cases into `@Nested` classes and describe every test with `@DisplayName`. The suite
  leans on both heavily, and display names are written in **English** even where the surrounding
  comments are Korean — they are what a failing CI run prints
- Two method-naming styles coexist: descriptive camelCase (`shouldReturnErrorWhenPathIsMissing`),
  which dominates, and the older `method_Condition_ExpectedResult`. **Match the file you are
  editing** rather than converting it; a rename-only diff buries the change under review
- Tests don't need Checkstyle compliance, but should still be formatted (`./gradlew format`)
- Aim for meaningful coverage on new code — JaCoCo reports under `modules/<module>/build/reports/jacoco/`

A representative test class:

```java
class ExampleToolTest {

    @TempDir
    Path tempDir;

    private ExampleTool tool;

    @BeforeEach
    void setUp() {
        tool = new ExampleTool(/* dependencies */);
    }

    @Test
    @DisplayName("rejects a null dependency at construction")
    void shouldRejectNullDependency() {
        assertThatThrownBy(() -> new ExampleTool(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("returns the rendered content for valid input")
        void shouldReturnContentForValidInput() {
            ToolResult result = tool.execute(ToolInput.of("param", "value"), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("expected output");
        }

        @Test
        @DisplayName("reports a missing required parameter as an error, not an exception")
        void shouldReturnErrorWhenRequiredParameterMissing() {
            ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

            assertThat(result.isError()).isTrue();
        }
    }
}
```

That last case is not filler. `Tool#execute` is contractually forbidden from throwing, so the test
that proves it returns an error is the one protecting the contract.

### Documentation

- Public APIs need Javadoc
- User-facing strings (exception messages, log messages) should be in **English**
- Internal comments may be in English or Korean; new files should prefer English to keep the project accessible to global contributors
- Update relevant guides under `docs/` when changing user-facing behavior
- Link between documents with **relative paths**, and point `#anchors` at headings that
  actually exist — `scripts/check-doc-links.py` checks both
- Heading anchors are generated identically on GitHub and on the docs site, so an anchor
  the checker accepts works on both. Note that `.` is dropped without leaving a hyphen:
  the heading `AimonCli.call()` is reached as `#aimonclicall`

### Translations

Documentation is bilingual, and which language is the original depends on where the
file lives. Under `docs/`, **Korean is canonical** and English translations are
`*.en.md`. At the repository root the direction is reversed: `README.md`,
`CONTRIBUTING.md`, `SECURITY.md` and `CODE_OF_CONDUCT.md` are written in English and
translated to `*.ko.md`. The rule that holds in both places is that **the file with the
language suffix is the translation**.

Every translation says so in its front matter, and names the canonical commit it was
made from:

```yaml
---
translated_from: docs/features/hook/hook-config-guide.md
source_commit: eec9ccd
---
```

`source_commit` is the canonical's last commit *before* your edit — you cannot name the
SHA of the commit you are about to write. `scripts/check-translation-staleness.py`
knows this and ignores commits that touched the canonical and its translation together,
so the one-commit lag is not reported as staleness.

**When you change a canonical, update its translation in the same PR if you can.** If
you can't — you don't speak the other language well enough, or the change is large —
say so in the PR and open an issue. Do not hold the canonical edit hostage to the
translation: a translation lagging a week is a smaller problem than documentation that
is wrong in both languages. CI reports stale translations as warnings for exactly this
reason, and never fails on them. The structure check follows it: a mismatch fails the
build only when the pair is level, because that is the case where someone is being asked
to finish a translation rather than to start one.

If a translation legitimately cannot match on some axis, declare it on that file rather
than arguing with the checker:

```yaml
structure_exempt: list-items
structure_exempt_reason: "the canonical's 3-item list is idiomatically 2 in English"
```

Both lines are required and both shapes are enforced. The comma-separated string and the
quotes are not style: mkdocs reads this same front matter with a YAML parser and the
scripts read it with a line regex, and a YAML list or an unquoted reason containing a
colon or a backtick makes the two disagree — in the worst case mkdocs drops the metadata
silently and publishes it as page text. The exemption also expires: once the axis matches
again, the check asks you to delete it.

**A `source_commit` CI cannot resolve does fail the build, though.** That is a different
finding: a stale translation means the check ran and did not like the answer, while an
unresolvable one means it has no answer — it cannot tell you whether that file is current
or a year behind. Nothing about failing on it pressures you to skip a translation; it
asks for a SHA that exists, which is one line. If a rewritten history retires the SHA you
recorded (a squash, a rebase), point `source_commit` at the oldest commit that still
contains the canonical in the state you translated — and check that it really is that
state before you write it, because a resolvable SHA on a translation that no longer
matches is worse than an unresolvable one: it reports green.

When writing a translation:

- **Match the structure exactly.** Same heading count, same table rows, same code
  blocks. The two files should diff cleanly on shape even when no word matches.
  `scripts/check-translation-structure.py` checks this one, and it counts what
  survives re-wrapping: headings and their levels, fences and their languages, table
  rows per table, list items and their nesting, and quote *blocks*. Line counts are
  not comparable in either direction — Korean carries more per column, so the same
  paragraph wraps to more lines in English
- **Translating a heading changes its anchor.** Retarget the in-page `#links` and run
  `scripts/check-doc-links.py`
- **Leave identifiers alone** — type and package names, file paths, config keys, CLI
  commands and flags, annotations, enum constants, and the deliberately frozen wire
  names (`conversationId`, `conversation_locks`). Comments *inside* code blocks are
  prose and do get translated
- **Rebuild ASCII diagrams rather than editing them.** Hangul is double-width, so
  substituting Korean into the middle of a box breaks the alignment that looked fine in
  your editor
- **Translate what the canonical says, not what it should say.** If the canonical is
  out of date, fix the canonical in its own commit — a translation that quietly
  corrects its original leaves the two disagreeing with no record of why

[`docs/project/translation-glossary.md`](docs/project/translation-glossary.md) is the
binding reference for recurring terms, and settles the ones that have bitten us before
(`turn` / `iteration` / `execution` in particular). The full set of documentation rules —
where a new document goes, how `design/` differs from `plan/`, which directories the site
publishes — is [`docs/project/documentation-guide.md`](docs/project/documentation-guide.md).

## Commit Messages

We follow a lightweight [Conventional Commits](https://www.conventionalcommits.org/) style:

```
<type>(<scope>): <short summary>

<optional body explaining the WHY, wrapped at ~72 cols>

<optional footer: BREAKING CHANGE:, Refs:, Co-authored-by:>
```

**Types:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `build`, `ci`

**Scopes:** module short name (`core`, `cli`, `llm-openai`, `session`, `sandbox`, etc.) or feature area.

Examples:

```
feat(session): add per-turn SubmitOptions to AgentSession
fix(llm-usage): preserve native streaming through MeteringLlmClient
refactor(session-mongodb): normalize nested maps for cross-backend uniformity
docs(tool): clarify error-handling pattern in tool-development-guide
```

Keep commits focused — one logical change per commit. Squash work-in-progress commits before opening a PR.

## Branching & Pull Requests

### Branch naming

- `feat/<short-description>`
- `fix/<short-description>`
- `docs/<short-description>`
- `chore/<short-description>`

### Workflow

1. Fork the repo (or create a topic branch if you have write access)
2. Create your branch off `main`
3. Make focused commits with DCO sign-off (see below)
4. Push and open a Pull Request against `main`
5. Fill in the PR template; link relevant issues with `Fixes #123` / `Refs #123`
6. Ensure CI is green
7. Address review feedback in additional commits (don't force-push during review unless asked)
8. A maintainer will squash-merge or merge as appropriate

### PR checklist

- [ ] `./gradlew checkAll` passes locally (format + checkstyle + unit tests)
- [ ] `python3 scripts/check-doc-links.py` passes, if any markdown changed
- [ ] `mkdocs build --strict` passes, if `docs/` or `mkdocs.yml` changed
- [ ] New code has tests
- [ ] Public API changes have Javadoc
- [ ] User-facing changes are reflected in `docs/` and `CHANGELOG.md` (Unreleased section)
- [ ] Commits are signed off (DCO)

## Developer Certificate of Origin (DCO)

By contributing to this project, you certify that you wrote the patch (or otherwise have the right to submit it under the project's license) — see the [DCO 1.1 text](https://developercertificate.org/).

To indicate agreement, sign off every commit:

```bash
git commit -s -m "feat(core): add cool new thing"
```

This adds a `Signed-off-by: Your Name <you@example.com>` trailer using your `user.name` / `user.email` git config. Set them once with:

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
```

PRs missing sign-off will be asked to amend. If you forget on multiple commits:

```bash
git rebase --signoff main
git push --force-with-lease
```

## Reporting Security Issues

**Do not** open a public issue for security vulnerabilities. See [SECURITY.md](SECURITY.md) for the private disclosure channel.

---

Thanks again for contributing! If anything in this document is unclear, please open a Discussion or PR — we'd rather fix the docs than leave you guessing.
