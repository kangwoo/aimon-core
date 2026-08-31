# Maintainers

This file answers one question: **who decides?** [`CONTRIBUTING.md`](CONTRIBUTING.md) covers how to
propose a change; this covers who accepts it, who can cut a release, and how that list changes.

## Current maintainers

| Maintainer | GitHub | Areas | Since |
|-----------|--------|-------|-------|
| kangwoo | [@kangwoo](https://github.com/kangwoo) | all | 2025 |

**There is exactly one.** Every commit in this repository's history has the same author, and nobody
else currently holds merge or release rights. That is worth saying plainly rather than hiding behind
"the team":

- A pull request can wait longer than you expect — see [Response times](#response-times)
- The bus factor is one. If you are putting AIMON somewhere load-bearing, weigh that next to the
  compatibility policy in [`docs/project/api-stability.md`](docs/project/api-stability.md)
- Growing this table is an explicit goal, not a formality — see
  [Becoming a maintainer](#becoming-a-maintainer)

## What a maintainer does

| Duty | What it means here |
|------|--------------------|
| **Review and merge** | Every PR needs an approving review from a maintainer who did not write it. With one maintainer that reduces to "the maintainer reviews everything", which is exactly the limitation above |
| **Cut releases** | Run [`scripts/release.sh`](scripts/release.sh) — it gates on `./gradlew checkAll`, publishes to Maven Central, and only then commits, tags, and pushes. See [`docs/project/publishing-guide.md`](docs/project/publishing-guide.md) |
| **Handle security reports** | Triage private reports, request CVEs, publish advisories — the process is in [`SECURITY.md`](SECURITY.md) |
| **Keep the canon straight** | Korean is the canonical language for `docs/`; English translations follow it. A maintainer merging a doc change is responsible for not letting the two drift |
| **Say no** | Declining a change with a reason is maintenance work. An unanswered PR is worse than a declined one |

## Areas

The table above says "all" because there is one maintainer. This table exists so a second maintainer
can take **an area** rather than the whole project — that is the realistic path to more than one name
above.

| Area | Modules |
|------|---------|
| Agent core | `aimon-core` (agent execution, tools, skills, hooks, sessions) |
| Assembly | `aimon-bom`, `aimon-bootstrap`, `aimon-spring-boot-starter`, `aimon-cli` |
| LLM providers | `aimon-llm-openai`, `aimon-llm-anthropic` |
| Storage | `aimon-session-*`, `aimon-memory-*`, `aimon-knowledge-opensearch` |
| Filesystem | `aimon-filesystem-gridfs`, `aimon-filesystem-s3`, `aimon-filesystem-testkit` |
| Sandbox & browser | `aimon-sandbox`, `aimon-sandbox-docker`, `aimon-sandbox-kubernetes`, `aimon-browser-playwright` |
| Scheduling & workflow | `aimon-scheduling-quartz`, `aimon-workflow-graaljs`, `aimon-rewake-webhook` |
| Docs & build | `docs/`, `buildSrc/`, `.github/`, `scripts/` |

An area maintainer merges within their area and defers cross-cutting changes — anything touching the
scope model, a public SPI, or the release path — to a full maintainer.

## How decisions are made

Most changes need no ceremony: open a PR, get a review, merge. Three kinds do.

**Breaking changes.** Renaming or removing a public type, changing a method signature on an SPI, or
changing anything in the frozen surfaces listed in
[`api-stability.md`](docs/project/api-stability.md) §4 needs the
old ↔ new mapping written into [`docs/migration/rename-maps.md`](docs/migration/rename-maps.md) in
the same PR. This is not optional
bookkeeping — the last two breaking changes were renames, and the mapping tables are how anyone
upgrading finds their way.

**Naming and lifetime.** New types get named against [`docs/overview/scope-model.md`](docs/overview/scope-model.md)
and [`docs/overview/glossary.md`](docs/overview/glossary.md), not against intuition. Several of these
rules are enforced by ArchUnit tests rather than by review, so a PR that gets them wrong fails the
build instead of reaching a maintainer's judgement. That is deliberate: it keeps the rule from
depending on whether the reviewer remembered it.

**Design decisions with a cost.** If the reasoning is worth more than the diff, it goes in
`docs/design/` and the PR links it. The [`docs/backlog/`](docs/backlog/README.md) register exists for
the same reason — an item's value is the record of *why* it was judged that way, and that record is
most expensive exactly when the item closes.

## Response times

Good-faith targets, not an SLA. One person maintains this project.

| | Target |
|---|--------|
| First response to an issue | 1 week |
| First review pass on a PR | 2 weeks |
| Security report acknowledgement | 5 business days (see [`SECURITY.md`](SECURITY.md)) |

**If nothing happens:** comment on the thread after the target has passed. A single "ping?" is
welcome and is not considered rude here. If two weeks pass after that with no reply, assume the
maintainer is unavailable rather than uninterested — the work is not lost, and a rebase will usually
still apply.

## Becoming a maintainer

The bar is sustained, reviewable work in one [area](#areas) — not a contribution count. What actually
demonstrates it:

- Several merged PRs in that area, including at least one that was **not** a bug fix (a design
  decision, a contract, a test suite)
- Reviews of other people's PRs that caught something real
- Following the project's own rules without being told: conventional commits, DCO sign-off,
  `./gradlew checkAll` before pushing, the naming rules above

The process: an existing maintainer proposes it in a public issue. If no maintainer objects within
7 days, the candidate is added to the table above and granted the corresponding rights in the same
PR. Release credentials are **not** shared — a maintainer with release duty registers their own
Central Portal token and signing key.

Stepping back is equally routine. Move your row to Emeritus in a PR; no explanation is required.

## Emeritus

Nobody yet. The section exists so that leaving is a normal documented action rather than a name
quietly disappearing from a table.

---

## Related

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to propose a change
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — behaviour expected of everyone, maintainers included
- [`SECURITY.md`](SECURITY.md) — private reporting and disclosure
- [`docs/project/roadmap.md`](docs/project/roadmap.md) — where the project is going
- [`docs/project/api-stability.md`](docs/project/api-stability.md) — what "0.x" promises
