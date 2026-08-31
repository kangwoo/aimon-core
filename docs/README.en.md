---
translated_from: docs/README.md
source_commit: aaae6c3c
---

# AIMON Documentation

The entry point for the AIMON project's documentation. The docs are organised
**by feature** — split by *what the document is about*, not by who reads it
(guide / development / operations).

## Which document should I read

| Situation | Start here |
|------|----------|
| **I want to skim what AIMON can do** | [`overview/features.en.md`](overview/features.en.md) ← **feature catalogue** |
| I want to see what AIMON is and what abstractions it has | [`overview/architecture.en.md`](overview/architecture.en.md) |
| The lifetimes behind terms like Session / Live session / Turn confuse me | [`overview/glossary.en.md`](overview/glossary.en.md) |
| I don't know where to put this value or when to close it | [`overview/scope-model.en.md`](overview/scope-model.en.md) |
| I want to embed AIMON in my own application | [`getting-started/embedding-agent-in-application.en.md`](getting-started/embedding-agent-in-application.en.md) |
| I want to follow a working integration line by line | [`getting-started/aimon-core-integration-via-cli-reference.en.md`](getting-started/aimon-core-integration-via-cli-reference.en.md) |
| I want to go deep on one feature (tools, skills, hooks, sessions, workflows …) | [`features/`](features/) |
| I want to know why a component was designed the way it is | [`design/README.md`](design/README.md) ← **design document index** |
| Something broke upgrading 0.0.x → 0.1.x | [`migration/`](migration/) |
| I want to contribute or publish | [`project/`](project/) |
| I want to see how far work in progress has got | [`plan/`](plan/) — exists only while a plan is in progress (see below) |
| I want to see **what finished work left behind** | [`backlog/`](backlog/) ← the **canonical** list of open items |

## Directory layout

```
docs/
├── overview/          The whole of AIMON — feature catalogue, architecture, terms, lifetime rules
├── getting-started/   Attaching it for the first time — embedding, integration reference
├── features/          Per-feature guides (use, development and operations grouped by feature)
├── design/            Design rationale and implementation notes
├── backlog/           Open items left behind by finished work — canonical open/closed
├── references/        External standards and pattern specifications
├── migration/         Version upgrade procedures
└── project/           Running the project — direction, compatibility promises, coding principles, releases
```

### [`overview/`](overview/) — the whole picture

| Document | Purpose |
|------|------|
| [`features.en.md`](overview/features.en.md) | **Feature catalogue** — what `aimon-core` can do, what the entry point is, and whether it is built into core or a separate module |
| [`architecture.en.md`](overview/architecture.en.md) | Reference for the core abstractions (Agent, AgentExecutor, Tool, LlmClient, VirtualFileSystem, …) |
| [`glossary.en.md`](overview/glossary.en.md) | Glossary — the lifetime of each term, and `SessionRecord`:`LiveSession` = 1:0..N |
| [`scope-model.en.md`](overview/scope-model.en.md) | Lifetime, ownership and teardown rules — read before creating a new type or calling `close()` |

### [`getting-started/`](getting-started/) — attaching it for the first time

| Document | Purpose |
|------|------|
| [`embedding-agent-in-application.en.md`](getting-started/embedding-agent-in-application.en.md) | Embedding into Spring Boot or the SDK |
| [`aimon-core-integration-via-cli-reference.en.md`](getting-started/aimon-core-integration-via-cli-reference.en.md) | Walks the `aimon-cli` bootstrap code line by line to explain the integration pattern |

### [`features/`](features/) — per-feature detail

One directory per feature. The use, development and operations documents for
that feature all live in the same folder. The full index is
[`features/README.md`](features/README.en.md).

| Area | Representative documents |
|------|----------|
| [`agent-execution/`](features/agent-execution/) | Command queue, interruptible tools, the `<system-reminder>` convention |
| [`session/`](features/session/) | Session tutorial, the `LiveSession` API, multi-node deployment |
| [`tool/`](features/tool/) | **Tool development guide**, parallel execution, the browser tool |
| [`skill/`](features/skill/) | Built-in Agent/Skill system |
| [`hook/`](features/hook/) | Hook development, hook configuration and hot reload |
| [`subagent/`](features/subagent/) | Defining subagents in code |
| [`workflow/`](features/workflow/) | Workflow CLI, assembling and resuming in code |
| [`llm/`](features/llm/) | LLM Provider development, usage and cost metering |
| [`memory/`](features/memory/) | Using memory |
| [`knowledge/`](features/knowledge/) | OpenSearch Knowledge Store |
| [`scheduling/`](features/scheduling/) | Quartz cluster deployment |
| [`observability/`](features/observability/) | Execution tracing |

### [`design/`](design/) — design documents

The rationale behind a component and the alternatives that were rejected.
Ordinary users are served by the `features/` guides; come here when you need to
know the internals or why something changed. It is split **by domain**, and the
directory names match those under `features/` — so that the usage guide and the
design rationale for the same subject face each other.

- [`design/README.md`](design/README.md) — the **full index**. Per-domain document list and the old-path mapping table
- [`design/<domain>/`](design/) — `agent-execution` · `session` · `tool` · `skill` · `hook` ·
  `subagent` · `workflow` · `llm` · `filesystem` · `memory` · `knowledge` · `scheduling` ·
  `observability` · `integration`
- [`design/backlog/`](design/backlog/) — items deliberately deferred. Not a progress tracker but a record
  of "why not now" and the trigger for reconsidering. Easily confused with the top-level
  [`backlog/`](backlog/) — the two are distinguished below

Whether something is implemented is stated by the one-line `Status` at the head of each
document, not by which directory it sits in. There used to be a `design/implemented/`
status axis, but it lied whenever half a document was implemented, so it was dropped —
the old-path mapping table is in [`design/README.md`](design/README.md) §4.

You can also reach the design documents for a given feature through the **design rationale**
links in each section of [`features/README.md`](features/README.en.md).

### [`backlog/`](backlog/) — open items left behind by finished work

IMPORTANT: **This is the canonical record of what is open.** The priority tables in design
documents (P0/P1/P2, unresolved U) are frozen as a **record of the moment of design**, so the
absence of a strikethrough there does not mean an item is still open. The evidence that this
rule was needed showed up with the very first entry — the table in the starter design document
was still listing an item as open that had already been wired up. Details in
[`backlog/README.md`](backlog/README.md).

It shares a name with `design/backlog/` but holds something different.

| | [`design/backlog/`](design/backlog/) | [`backlog/`](backlog/) |
|---|---|---|
| Holds | **A design that was deferred** — the shape was settled, but nothing consumed it, so it was not built | **What was left over after building** — it was built, but some item was pushed back |
| Unit | one document = one deferred design | one document = every item left behind by one finished piece of work |
| Appears | during design | when work ends |

It also differs from `plan/`. A plan document holds the next steps of **work in progress** and
is deleted when that work ends; the items here come into being **after** the work is done and
stay until somebody picks them up. That makes this the exception to the "status markers live in
`plan/` only" rule below — except that what it holds must be **a single open/closed**, not
progress.

### [`references/`](references/) — external specifications and patterns

External standards or pattern specifications that AIMON references or extends.

| Document | Contents |
|------|------|
| [`agentskills-specification.md`](references/agentskills-specification.md) | The Agent Skills standard format specification |
| [`aimon-skill-extensions.md`](references/aimon-skill-extensions.md) | The extension frontmatter fields AIMON adds to the standard |
| [`hooks-specification.md`](references/hooks-specification.md) | The parity boundary with the Claude Code hook spec — mappings, extensions, and what is unsupported |
| [`llm-wiki.md`](references/llm-wiki.md) | The LLM Wiki pattern — where the `WikiKnowledgeStore` concept came from |

### [`migration/`](migration/) — version upgrade guides

| Document | Target version |
|------|----------|
| [`custom-command-to-skill.md`](migration/custom-command-to-skill.md) | 0.0.37 → 0.1.0 (`CustomCommand` removed) |

### [`project/`](project/) — running the project

| Document | Contents |
|------|------|
| [`roadmap.md`](project/roadmap.md) | Where this is going — what is being worked on, what is blocked, and the road to `1.0` |
| [`api-stability.md`](project/api-stability.md) | What `0.x` promises and what it does not |
| [`solid-principles.md`](project/solid-principles.md) | The SOLID principles as applied to this project |
| [`translation-glossary.md`](project/translation-glossary.md) | Korean canonical → English translation glossary (see **Translation rules** below) |
| [`publishing-guide.md`](project/publishing-guide.md) | The Maven Central publishing procedure |
| [`aimon-core-coverage-priority.md`](project/aimon-core-coverage-priority.md) | Test coverage priorities |

### `plan/` — progress tracking documents

The **current state and next steps** of work that spans several PRs. It pairs with a design
document but plays a different role — a design document records rationale and rarely changes,
while a plan document is updated as work progresses and is **deleted when the work ends** (the
outcome survives in the design document and in the git history).

The directory **exists only while a plan is in progress.** Right now that is
[`open-source-readiness.md`](plan/open-source-readiness.md) (the move to open source), and when
it finishes and is deleted, the directory disappearing with it is the normal state.

Building the `OrcaAgentRuntime` integration test layers (L0–L4) was the first application of
this rule — as the work ended the plan document was deleted, and the rationale worth keeping
moved to
[`design/agent-execution/integration-test-layers.md`](design/agent-execution/integration-test-layers.md).

Cleaning up the term `turn` (the places where iterations and executions were being called
`turn`) followed the same path. What differs is where it landed: not in a design document,
because what was worth keeping was not design rationale but a **vocabulary rule**. So it went
into [`overview/glossary.en.md`](overview/glossary.en.md) §4 › 실행 단위 as a rule — that is where the
substance of the prevention lives — and what was changed and why stayed in
[`CHANGELOG.md`](../CHANGELOG.md).

## Documentation rules

### Where does it go

When you get stuck adding a new document, decide by **what the document is about, not who
reads it**.

| What the document contains | Location |
|------------|------|
| A view over the whole of AIMON (feature list, terms, lifetime rules) | `overview/` |
| The procedure for attaching it the first time | `getting-started/` |
| How to use, develop or operate one feature | `features/<feature>/` |
| The rationale for a design decision / rejected alternatives | `design/<domain>/` — the domain shares its name with `features/` |
| A design item deliberately deferred | `design/backlog/` |
| **Open items left behind by finished work** | `backlog/` — the canonical open/closed |
| The progress and next steps of work spanning several PRs | `plan/` |
| A citation of an external specification or pattern | `references/` |
| A version upgrade procedure | `migration/` |
| Running the project itself (principles, releases, quality) | `project/` |

IMPORTANT: Do not split the "for developers" and "for operators" documents of one feature into
different directories. Both go in `features/<feature>/` — whoever attaches a feature usually
reads both.

If you added a new feature directory, reflect it in both the
[`features/README.md`](features/README.en.md) index and the
[`overview/features.en.md`](overview/features.en.md) catalogue.

### What separates `design/` from `plan/`

The same piece of work can produce both documents. What separates them is **what causes the
document to be updated**.

| | `design/` | `plan/` |
|---|---|---|
| Holds | Why it was decided this way — the rationale for the classification, structure and decision | How far it has got and what comes next |
| Updated | Only when a decision changes | Every time the work progresses |
| Lifetime | Permanent (whether it is implemented is stated by the one-line `Status` at the head) | **Deleted** when the work ends |

Status markers — checkboxes, "not started / done" — belong in `plan/` only. If a design
document carries a progress figure, someone who came to read the rationale meets a stale status
table first. Conversely, a plan document should link the design document rather than copy the
rationale out of it.

[`backlog/`](backlog/) is the one exception. Items left over **after** work ends have no plan
document left to update them, so they are collected there — and in exchange what they may carry
is limited to **a single open/closed**. The moment a progress figure gets in, it becomes a
`plan/` that never gets deleted.

### Link rules

- Links between documents are written as **relative paths**.
- When you move a document, fix every link pointing at it in the same change. Relative paths
  cannot be fixed by string substitution — you must **resolve against the old directory → map to
  the new location → re-relativise against the new directory**.
- When pointing at a document from Java Javadoc or `CLAUDE.md`, use a repository-root path
  (`docs/features/tool/tool-development-guide.md`).
- Links are checked automatically — `python3 scripts/check-doc-links.py` looks at both the path
  and the `#anchor`. The `docs-links` job in CI runs the same thing.

### Translation rules

**The canonical language is Korean.** The English documents are translations, distinguished by
a suffix — the canonical files do not move an inch.

```
docs/features/tool/tool-development-guide.md      ← Korean canonical (path unchanged)
docs/features/tool/tool-development-guide.en.md   ← English translation
```

That suffix determines the site's URLs.

| URL | Contents |
|-----|------|
| `/` | Korean — the unsuffixed files |
| `/en/` | English — the `.en.md` files |

**A document with no translation does not 404 under `/en/`; the Korean canonical is served
instead.** That is why translating one directory at a time leaves the site whole throughout —
which is to say there is no reason to translate everything at once.

The root being Korean is not a preference but a consequence of the suffix scheme. Unsuffixed
files belong to the default locale and the default locale always builds at the root, so putting
English at the root would mean suffixing every canonical file with `.ko`. The reasoning is in
[`plan/open-source-readiness.md`](plan/open-source-readiness.md) §0.2.

#### What gets translated — decided by directory

This is not judged document by document. **The translation scope is pinned to directories, and
this table is that boundary.**

| Directory | Status | Why |
|---------|------|------|
| `docs/README.md` | **in scope** | The site's entry point |
| `overview/` | **in scope** | The first thing someone opens when asking what this project is. It becomes the reference for the terms and lifetimes in every other translation |
| `getting-started/` | **in scope** | The path for someone trying to attach it |
| `features/` | **in scope** | The path for someone trying to use a feature |
| `project/` | not yet | **Promotable**. What defers it is priority, not its nature |
| `references/` | not yet | Same. Being cross-reference tables against external specs, they move up when demand appears |
| `migration/` | not yet | Same — though the point at which a new migration document appears is the natural moment to promote it |
| `design/` | **out of scope** | A record of design **rationale**. Not a contributor's entry path, and the most frequently changed |
| `backlog/` | **out of scope** | Same reason. The door from outside is GitHub Issues |
| `plan/` | **out of scope** | Documents that get deleted when they finish |

The middle three rows and the bottom three say different things. **"Not yet" means deferred**
and needs no new argument to promote — just change the status in this table. **"Out of scope"
means a decision not to translate**, and reversing it means reversing that decision first.

If you want to move the boundary, **change this table first.** Translating a directory that is
not in the table makes the next person re-decide "why is there a translation here and not
there" every single time.

#### The repository root runs the other way

The canonical documents under `docs/` are Korean, but the root's `README.md` ·
`CONTRIBUTING.md` · `SECURITY.md` · `CODE_OF_CONDUCT.md` · `MAINTAINERS.md` are **canonically
English**. GitHub shows those files first, and whoever opens them does not yet know this
project's language.

So the translations on that side are `.ko.md`, and `translated_from` points the other way. The
suffix convention is the same on both sides — **the suffixed one is the translation.**

#### Translations carry frontmatter

**Only the English files** carry it. The canonical files are left alone — putting metadata on
the canonical would impose translation-management overhead even on documents that have no
translation.

```yaml
---
translated_from: docs/features/tool/tool-development-guide.md
source_commit: 4d1779d3
---
```

| Field | Value |
|------|-----|
| `translated_from` | The canonical file's path, **relative to the repository root** |
| `source_commit` | The last commit SHA of the canonical that this translation followed (short form) |

`source_commit` is what lets a tool, rather than a person, decide that **the canonical changed
and the translation did not follow.** The decision is simple — if there are commits to the
`translated_from` path after that SHA, the translation is stale. When you update a translation,
change the body and `source_commit` **in the same commit**. Changed separately, this field
quietly turns into "when somebody last paid attention", which is a different thing.

#### Relative links inside a translation point at a suffix **only where a translation exists**

Whether a translation links to `foo.md` or to `foo.en.md` makes **no difference on the site** —
the i18n plugin resolves both to the same page, and a document with no translation serves the
Korean canonical rather than a 404. That has been verified by measurement.

The difference shows up on GitHub. GitHub opens files directly with no plugin, so `.en.md` is
right only when that file exists and is a 404 when it does not. That collapses the rule into
one line.

| Translation of the target | What to link |
|------------|-------------|
| exists | `foo.en.md` — correct on both surfaces |
| not yet | `foo.md` — the site substitutes Korean, and GitHub opens |

Each time a batch finishes, some links in earlier batches newly fall under this rule.
`python3 scripts/upgrade-translation-links.py` picks out exactly those and raises them — do not
go looking by hand.

#### Terminology

There is a separate table to stop one word being rendered several ways —
[`project/translation-glossary.md`](project/translation-glossary.md). Before translating you
must read §1 (what is not translated) and §2 (`turn`/`iteration`/`execution`). If you settled a
word that is not in the table, add it there **in the same PR**.
