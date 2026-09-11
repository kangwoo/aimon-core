# Design — #132: two feature guides still teach model aliases, and #125's other follow-ups

> Status: **IMPLEMENTED** — javadoc in `aimon-core` (the per-invocation model override in
> `SubagentExecutionEnvironment`, `SubagentExecutionContext` and `DefaultSubagentExecutor.buildModelConfig`), the
> subagent development guide and the built-in agent skill guide (ko + en), the `temperature` row of the
> `aimon-llm-anthropic` README, the bundled skill-creator's `benchmark.json` sample, the CLI guide's banner paragraph
> (ko + en), the `Status` block of [`provider-switch-agent-model-check.md`](provider-switch-agent-model-check.md),
> backlog `L-19` and `L-27`, and `CHANGELOG.md`. Source: issue
> [#132](https://github.com/kangwoo/aimon-core/issues/132).
>
> **[§11](#11-after-the-build--departures-and-corrections), appended after the build, is where the build departs from
> this document.** Everything between this header and §11 is the body as approved in design review round 1 (PASS, no
> blocking findings, seven non-blocking notes), kept byte-exact rather than corrected — the house habit in this
> directory, for the reason [`model-capability-binding-round-trip.md`](model-capability-binding-round-trip.md) gives.
> Its `file:line` citations and counts are at `main` `2eddf3d`. The review transcript (`review-1.md`) and the run
> records the body names (`TASK.md`, `$RUN_DIR/design/`, `build/deviations.md`) are not in the repository; §11
> reproduces what they recorded.
>
> The body's opening paragraph says this run commits no design record. That was the approved plan; the record was
> added after approval, at the direction the build was given, and §11 (DV-1) says so instead of the body being edited.
>
> It is in English, matching its siblings in `docs/design/llm/`; `docs/design/` is not a translation target
> (`docs/project/documentation-guide.md` §5.1). It continues
> [`model-names-sent-and-shown.md`](model-names-sent-and-shown.md), whose §11 registered `L-27`. Nothing this work left
> open went to the backlog: `L-28` and `L-29`, reserved for it, are unused.

Run `model-alias-docs-followups`, base `main` at **`2eddf3d`**. Every `file:line` below was re-read on that
commit unless it says otherwise. Every character count was measured with Python (`len`, plus East Asian display width
for Korean). No count here is an estimate.

This run commits **no design record** to the repository, so `docs/design/README.md` §3.4 is not engaged and its index
tables are not touched. This document lives only in `$RUN_DIR/design/`.

---

## 1. The problem, restated

#125 (#116, #118) rewrote the core javadoc to say a subagent's `model` is an id *"sent to the configured provider
exactly as written — no alias is resolved"* (`Subagent.java:204-205`, `SubagentContentParser.java:25`), and that a
resolved model's name may be empty (`SubagentBehaviorSupport.java:127`). It left six things contradicting or trailing
that:
- **The guides.** Two feature guides, in both locales, still build example subagents with `model("sonnet")` /
  `model: sonnet` and describe `resolvedModel()` as an alias merge. `L-27`, the backlog item for copy-paste examples
  that name unserved models, lists none of these sites.
- **The override javadoc.** Three core files still call the per-invocation override an "alias".
- **The README.** The `aimon-llm-anthropic` README gives `temperature` a `0.0` default that `AnthropicConfig` does not
  have.
- **The skill-creator sample.** The bundled skill-creator's `benchmark.json` sample names the retired
  `claude-sonnet-4-20250514`.
- **`L-19`.** Its registered body names that old default, with nothing under it saying #116 changed it.
- **Two line wraps.** The CLI guide's banner paragraph (ko + en) and the `Status` block of #92's design record each
  have one line that does not wrap like its neighbours.

The work is documentation and javadoc only. No signature, no wire or config name, and no persisted identity changes.

---

## 2. Re-verified facts this design stands on (`2eddf3d`)

| Claim in the issue | Re-read result |
|---|---|
| Subagent guide alias sites ko `:109` `:256` `:329` `:354`, en `:115` `:264` `:341` `:366` | Exact. The quote at ko `:354` / en `:366` is the first of a 3-line quote block (`:354-356` / `:366-368`) |
| Skill guide `model: sonnet` ko `:80` `:123`, en `:85` `:128` | Exact. Both are lines inside a fenced ` ```markdown ` block's YAML front matter |
| Override "alias" in `SubagentExecutionEnvironment.java:186` `:507` `:511`, `SubagentExecutionContext.java:137` `:273` `:277`, `DefaultSubagentExecutor.java:789` | Exact. `grep -n alias` finds no other hit in those three files |
| `DefaultSubagentExecutor.java:789` | This is the `@param modelOverride` of the private method `buildModelConfig` (`:779-794`). **There is no field.** `LoopContext` (the sibling run's member) is declared at `:1073`, a separate member ~280 lines away |
| `SubagentLlmDefaults` wording | `:51` *"a model name sent as written — nothing here resolves an alias"*, `:60` *"the per-invocation model name"* |
| README `temperature` row | `modules/aimon-llm-anthropic/README.md:134`. `AnthropicConfig.java:51` holds a nullable `Double`; the builder field `:291` has no initializer; `getTemperature()` is empty when unset (`:137-143`); the class javadoc `:18-19` says unset means not sent; the client uses the call's `LlmModel` temperature first and the config's second (`AnthropicLlmClient.java:546-547`). The builder setter takes a primitive `double` (`:338`), and the constructor validates the 0.0–1.0 range (`:79`) |
| `schemas.md:228` / `:229` | Exact. `scripts/aggregate_benchmark.py:267-268` writes `"<model-name>"` for both fields. No test references `schemas.md` or `skill-creator` |
| `L-19` sentence | `docs/backlog/llm-config-surface-open-items.md:1135-1136`. `L-19`'s closing is at `:1146` |
| `L-20`'s `정정` | `:1228-1229`. It is a blockquote `> **정정** *(date, #NN)*: …` placed right after the text it corrects |
| `L-27` site table | `:1620-1628`, seven rows. The `README.md:55` / `:106` rows **still exist on `2eddf3d`** (README lines 55 and 106 unchanged) |
| CLI guide short lines | ko `:294` 26 chars / 34 display columns; en `:303` 33 chars |
| `provider-switch-agent-model-check.md:13` | 127 chars / 129 bytes. Lines 9–17 are the Status block's second paragraph |
| Bundles after #104 | `default-anthropic`, `default-openai` and `ops-agent`'s `agents/explore.md:5` read `# No model: runs on the main agent's, the one this bundle's provider serves.` — a YAML comment line in front matter. `BundledSubagentModelTest.java:70` loads one and asserts the model is `null` |
| User subagents use the same parser | `FileSystemAgentBundleLoader.java:157` and `ClasspathAgentBundleLoader.java:247` both use `new MarkdownSubagentParser(new SubagentContentParser())` |
| Formatter | `config/eclipse/eclipse-formatter.xml`: `comment.line_length=120`, `format_javadoc_comments=true`, **`join_lines_in_comments=false`**. A javadoc line ≤120 chars is left alone, and a line >120 is split, adding a line |

**Cross-run citations that constrain line counts.** These were read from `gh issue view` (issue bodies only; no sibling
run directory was read):

| Sibling issue | Cites | Consequence for this run |
|---|---|---|
| #133 | `DefaultSubagentExecutor.java:920`, `:1082` | The `:789` edit must stay **one line** |
| #133 | `llm-config-surface-open-items.md:1329`, `:1453-1455` | These **will** move (the `L-19` note is inserted above them) → Merge notes |
| #134 | `provider-switch-agent-model-check.md:113` | The Status reflow must keep **lines 13–17 as five lines** |
| #130 | `provider-switch-agent-model-check.md` (no line) | None |

In-repo: `docs/design/integration/config-value-expansion-and-frontmatter-strictness.md:75` cites
`aimon-core-integration-via-cli-reference.md:374-378`. The record reads those at its base commit `2659d76`, so the
citation is dated.

---

## 3. Decisions

### D1 — What the guides' examples write instead of `sonnet` (TASK open decision 1)

**Chosen: no `model` in any of the four examples. Each `model` line is replaced, in place, by one comment line:** the
subagent runs on its parent's (the main agent's) model, and an id written there goes to the provider as written. No
model name appears. This is what #104 did to the bundles, and what #118 item 5 did to the core examples, which it
described as *"the alias values leave the examples with nothing put in their place"*.

Why replace with a comment rather than delete the line:
1. **The example still shows where the knob goes and what happens without it.** The first example in the subagent
   guide annotates every builder call. Deleting `.model(...)` silently would hide the one knob readers most often need
   to change.
2. **It mirrors the shipped bundles** (`# No model: runs on the main agent's …`). The skill guide's `explore.md`
   override overrides exactly that bundled file.
3. **It keeps line counts**, so nothing later in either guide moves.

Rejected:
- **A — a full model id.** Three reasons, each sufficient on its own:
  1. **Provider.** Both guides are provider-neutral. The subagent guide wires code subagents into
     `OrcaAgentRuntimeFactory` *"(CLI · web 동일)"*. Any id belongs to one vendor, and under the other provider the
     copied example is the #92 failure: Anthropic answered `gpt-5.6-terra` and `haiku` with 404
     (`provider-switch-agent-model-check.md:1071-1072`). The only startup check for that mismatch,
     `AgentModelProviderCheck`, lives in `aimon-cli`, so a web embedding gets nothing.
  2. **Staleness.** An id goes stale the way `claude-sonnet-4-20250514` did (#116). The only Anthropic name this
     repository's records show as served is `claude-sonnet-4-5`: `L-24`'s table, rows 3–4, 2026-09-11, one account,
     `api.anthropic.com`. The issue lists its retirement as *not measured*, and nothing detects retirement before a
     404. An example can avoid that trap only if something fails when the name retires. No such check exists for docs,
     and building one is outside #132.
  3. **Register cost.** `L-27` already says the id shape *"낡을 리터럴을 늘린다"* (adds literals that will go stale).
     Every id added here would become a new `L-27` row the moment it is written.
- **C — both** (no model in the example, plus a variant naming an id). The id half carries all of A's problems. The
  useful half — *when* to write one — is kept, folded into D1's comment without a literal.
- **D — a placeholder** such as `.model("<model-id>")`. In a subagent definition the value reaches a request, so a
  copied placeholder is sent as written and fails exactly like the alias. Item 4 can use a placeholder only because
  `executor_model` is never sent.

The subagent guide's builder table row `model(String)` → `null` (*"실행기 기본 모델"* / *"the executor's default
model"*) is **left unchanged**. It is literally the `defaultModel` handed to the executor, so it does not contradict the
core javadoc, and the issue does not name it.

### D2 — `L-19`'s correction (TASK open decision 2: shape fixed, placement and wording decided here)

- **Shape:** a `> **정정** *(2026-09-11, #132)*: …` blockquote, as under `L-20` (`:1228-1229`). The registered
  sentence at `:1135-1136` stays byte-for-byte.
- **Placement:** immediately after `L-19`'s **왜.** paragraph (after `:1136`, before **어디.**). The register already
  places a note directly below the registered text it corrects, while leaving that text unrewritten
  (`llm-config-surface-open-items.md:889-890`, under `L-16`'s table). A reader who lands on the sentence by search meets
  the note next.
  - Rejected — at the end of `L-19`'s `닫힘` block: the reader of the sentence would not see it.
- **Content**, all true on `2eddf3d`:
  - the sentence was true when registered;
  - #106 changed what the parenthesis shows (the closing below);
  - #116 changed `AnthropicConfig`'s default to `claude-sonnet-4-5`, and the old name answered 404 on 2026-09-11
    (`L-24`'s closing);
  - so today, under anthropic, with no `llm.model` **and** a main agent definition with no `model.name`, the
    parenthesis shows `claude-sonnet-4-5`.
  - Evidence for the last point, at `2eddf3d`:
    - `ReplSession.java:319-325` — definition `model.name`, else `client.getDefaultModelName()`;
    - `LlmClientFactory.java:77-79` — `.model(...)` only when `llm.model` is set;
    - `AnthropicLlmClient.java:762-764` — `getDefaultModelName()` returns `config.getModel()`;
    - `AnthropicConfig.java:42` — `DEFAULT_MODEL`.

### D3 — How `L-27`'s site table comes to cover both guides

**Chosen: add three rows to the existing table, and a `정정` note directly under it.** The note says four things:
- the table was registered with seven rows and missed the two guides;
- the new rows' lines were read at `2eddf3d`;
- #132 fixed those three rows with the item's own first shape (take the model line out of the example); the other
  seven rows stay open, and `L-27` stays **열림**;
- `L-27`'s own review trigger (*"`aimon-llm-anthropic` 의 README … 를 다음에 건드릴 때"*, "next time someone touches
  the README") fired, because item 3 edits that README, and #132 changed only the `temperature` row, leaving the
  `README.md:55` / `:106` rows alone.

This follows #125's `L-20` precedent (fix the table, record the original in a `정정`).

Rejected:
- **A new item (`L-28`) for the guides, closed in the same PR.** It splits one defect across two IDs, adds a closed
  item that changes the counts for no open work, and contradicts the issue's *"`L-27`'s site table covers both guides'
  sites"*.
- **Rows added with no note.** That would rewrite a registered table silently. README rule two and the `L-20`
  precedent say the correction is recorded.
- **A new status column.** Every existing row would have to be edited for three new ones.

Counts do not change. The title stays `등록 항목 27건 (열림 14 · 닫힘 13)` and the `docs/backlog/README.md:364` row stays
`27 | 14 | 13 | 0`. Recounted from the body with `check-backlog-registers.py`, not assumed. **`L-28` and `L-29` stay
unused.**

### D4 — The override's javadoc wording, and keeping every member's line count

Wording follows `SubagentLlmDefaults`: *"model name … sent as written"*. Each rewritten line was measured at ≤120
characters, the formatter's limit, so `./gradlew format` neither splits nor joins lines. That is required by #133's
citations of `DefaultSubagentExecutor.java:920` and `:1082`, and it keeps `SubagentExecutionEnvironment.java:390`
(cited in `L-20`'s table) still. The exact text is in §4.1.

Rejected:
- Adding *"— nothing resolves an alias"* to the builder summaries: it pushes both `SubagentExecutionEnvironment` lines
  past 120 characters and adds a line.
- *"The per-invocation model name, sent as written (nullable/blank = ignored), highest priority when present"* at
  `:789`: 122 characters, split by the formatter. The design uses *"… when set"* (118).

### D5 — The reflow rule (item 6)

**Rule, identical for all three paragraphs:**
- greedy fill;
- inline code spans are unbreakable;
- the width limit is the widest line of the same paragraph other than the offending one;
- lines before the offending line that are already greedy-full stay byte-identical;
- **no word is added, removed or reordered.** The paragraph's text joined with single spaces is identical before and
  after.

| Paragraph | Measure | Limit (source) | Result |
|---|---|---|---|
| ko CLI guide `:290-296` | display width (Hangul = 2). The file wraps by width: neighbours are 74–92 chars but 108–114 columns | 114 (`:291`) | `:290-293` unchanged; `:294-296` (3 lines) → **2 lines**. The paragraph goes 7 → **6 lines** |
| en CLI guide `:299-305` | characters | 114 (`:300`, `:304`) | `:299-302` unchanged; `:303-305` rewrapped, **still 7 lines** |
| `provider-switch-agent-model-check.md:13-17` | characters, including `> ` | 116 (`:14`) | `:9-12` unchanged; `:13-17` rewrapped, **still 5 lines** |

The last line of the en paragraph becomes `shown.` and the last Status line becomes `> probe's measurements.`. A short
*final* line is how every paragraph around them ends (`:315` is 43 chars, `:17` was 15). The issue's complaint is a
short line *between* long ones.

The ko paragraph losing a line moves every later line of the ko CLI guide up by one. No sibling issue cites the CLI
guide. The one in-repo citation (`config-value-expansion-and-frontmatter-strictness.md:75` → `:374-378`) is dated to
`2659d76`. It goes in Merge notes.

Rejected:
- **Forcing the ko paragraph to keep seven lines.** Every seven-line wrap either narrows four lines to ~76 columns
  beside neighbours at ~110, or leaves a one-word final line (`않는다.`). It trades the reported wart for a new one to
  avoid a one-line Merge note.
- **Limit 117 for the Status block** (the header's widest non-link line, `:6`). It gives the same five lines, but line
  14 lands at exactly 117. The paragraph's own width is the stated rule.

The §3.4 marker is intact under the chosen wrap. `Everything between this header and §10 is the` stays on `:12`,
`body as approved in design review round 3, kept byte-exact rather than corrected` stays whole on `:13`, and the `§10`
link on `:9` is not touched.

### D6 — The README `temperature` row

The default cell becomes `(미설정)`, matching the table's `(필수)` convention. The description says it is not sent
unless configured, and that a call's `LlmModel` value comes first. The type cell stays `double` (the setter's type) and
the range stays `0.0 ~ 1.0`.

Rejected — also saying *"models that reject sampling parameters omit it even when set"*. It is true
(`AnthropicLlmClient` suppresses and WARNs), but the issue asks only for "unset, and not sent, unless configured", and
"not sent unless configured" makes no claim the capability rule would contradict.

### D7 — The `benchmark.json` sample

Exactly one value changes: `"executor_model": "claude-sonnet-4-20250514"` → `"executor_model": "<model-name>"`, the
placeholder `aggregate_benchmark.py:267` writes. `"analyzer_model": "most-capable-model"` (`:229`) stays, as TASK
directs. No modification notice is added (see §9 Q1).

### D8 — `source_commit` values

The house rule, per `CONTRIBUTING.md` *Translations* and commits `65bcde0` / `3e2deef`: `source_commit` is **the
canonical's last commit before this edit**.

| Pair | Canonical's last commit before this run | `.en.md` today | After |
|---|---|---|---|
| `docs/features/subagent/subagent-development-guide` | `eec9ccd` (`git log -1 2eddf3d -- <canonical>`) | `eec9ccd` | **`eec9ccd`, unchanged — and that is right** |
| `docs/features/skill/builtin-agent-skill-guide` | `eec9ccd` | `eec9ccd` | **`eec9ccd`, unchanged** |
| `docs/getting-started/aimon-core-integration-via-cli-reference` | `e69999a` | `5606b04` | **`e69999a`** |

TASK remarks that the subagent guide's `.en.md` *"still records `eec9ccd`"*. The canonical has had no commit since the
initial one (`80f1569` rebased the translations onto it), so `eec9ccd` is its last commit before this edit. Moving it to
`2eddf3d` would name a commit that is not the canonical's. The builder re-runs the `git log -1` above before committing
and says so in the PR body.

### D9 — A CHANGELOG entry

**Yes**: one new `[Unreleased]` entry, `### Docs: …` (text in §4.8). No behaviour changes, but this repository records
user-facing documentation corrections. #125 did so with its *"Documentation (#118)"* bullet, and #121 and #122 added
`Docs` entries. The CONTRIBUTING PR checklist asks for it. No existing entry is edited: TASK's row names none for this
run.

---

## 4. Concrete changes by file

### 4.1 `aimon-core` javadoc (no code, no signature)

`modules/aimon-core/src/main/java/at/aimon/core/subagent/SubagentExecutionEnvironment.java`

```text
:186  before  *     * @return an {@link Optional} holding the override alias, or empty when none was supplied
      after   *     * @return an {@link Optional} holding the override, a model name sent as written, or empty when none was supplied   (118)

:507  before  * Sets the per-invocation model override alias (the {@code Task} tool's {@code model} argument). When non-null
:508  before  * and non-blank it takes priority over the subagent frontmatter model and the default model.
:507  after   * Sets the per-invocation model name (the {@code Task} tool's {@code model} argument), sent as written. When   (117)
:508  after   * non-null and non-blank it takes priority over the subagent frontmatter model and the default model.           (110)

:511  before  *            the override alias (nullable; ignored when null/blank)
      after   *            the per-invocation model name, sent as written (nullable; ignored when null/blank)                     (104)
```

`modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/SubagentExecutionContext.java`

```text
:137  same replacement as SubagentExecutionEnvironment :186                                                                     (118)

:273  before  * Sets the per-invocation model override alias. When non-null and non-blank it takes priority over the
:274  before  * subagent's frontmatter model and the default model.
:273  after   * Sets the per-invocation model name, sent as written. When non-null and non-blank it takes priority over the  (118)
:274  after   * subagent's frontmatter model and the default model.                                                          (62, unchanged)

:277  same replacement as SubagentExecutionEnvironment :511                                                                     (104)
```

`modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java` — `buildModelConfig`'s
`@param` only

```text
:789  before  *            The per-invocation model alias (nullable/blank = ignored), highest priority when present
      after   *            The per-invocation model name, sent as written (nullable/blank = ignored), highest priority when set    (118)
```

(Lengths in parentheses include the leading indentation and `*`. Leading whitespace in the blocks above is abbreviated;
each line keeps its current indentation.)

### 4.2 `docs/features/subagent/subagent-development-guide.md` + `.en.md` (one commit)

| ko | en | Change |
|---|---|---|
| `:109` | `:115` | `.model("sonnet") … // 모델 별칭` / `// a model alias` → a comment line at the builder indentation (8 spaces) |
| `:256` | `:264` | `.model("sonnet")` → the same comment line |
| `:329` | `:341` | The `resolvedModel()` table row rewritten (text below) |
| `:354-356` | `:366-368` | The 3-line quote reworded and re-wrapped, **still 3 lines** (measured) |

Comment lines:

```text
ko: // model 없음: 부모(보통 메인 에이전트)의 모델로 돈다. 적는 id 는 쓰인 그대로 provider 로 간다
en: // No model: runs on its parent's model (usually the main agent's). An id set here goes to the provider as written
```

The `resolvedModel()` row keeps its first two cells (`resolvedModel()`, `✅`). The `ctx` parenthesis agrees with
`SubagentBehaviorSupport`'s *"honors neither the override nor the subagent's `model`"*. The temperature / max tokens
clause keeps what the old *"merged with the default"* meant (`SubagentLlmDefaults.java:52-53`).

```text
ko: | `resolvedModel()` | ✅ | 호출별 override, 없으면 서브에이전트 `model`, 없으면 default 의 이름에 default 의 temperature·max tokens 를 합친 **해석된 모델**. 이름은 쓰인 그대로 보내고(별칭을 풀지 않는다) 비어 있을 수 있으며, 그러면 클라이언트가 자기 기본 모델을 보낸다. (raw `ctx.getDefaultModel()`은 override 도 `model` 도 미반영) |
en: | `resolvedModel()` | ✅ | The **resolved model**: the per-invocation override, else the subagent's `model`, else the default's name, with the default's temperature and max tokens. The name is sent as written (no alias is resolved) and may be empty, in which case the client sends its own default model. (raw `ctx.getDefaultModel()` reflects neither the override nor `model`) |
```

The quote blocks (measured greedy wraps, three lines each):

```text
ko :354 > raw `ctx.getDefaultModel()`은 호출별 override 도 서브에이전트의 `model` 도 **반영하지 않으며**,
ko :355 > `ctx.getToolRegistry()`는 allow-list가 **적용되지 않은** 전체 registry다. ReAct와 동일하게 동작하려면
ko :356 > `support.resolvedModel()` / `support.scopedToolRegistry()`를 사용하라.

en :366 > raw `ctx.getDefaultModel()` **reflects neither** the per-invocation override nor the subagent's `model`, and
en :367 > `ctx.getToolRegistry()` is the full registry with **no allow-list applied**. To behave the way ReAct does, use
en :368 > `support.resolvedModel()` and `support.scopedToolRegistry()`.
```

The wording may be polished at build time. What may not change: no model name appears; "sent as written" (or its ko
equivalent) appears in the comment; the row says the name may be empty; line counts, table-row counts and quote-block
counts stay as they are.

### 4.3 `docs/features/skill/builtin-agent-skill-guide.md` + `.en.md` (one commit)

| ko | en | Change |
|---|---|---|
| `:80` | `:85` | `model: sonnet` → a YAML comment line (the `.aimon/agents/explore.md` override) |
| `:123` | `:128` | `model: sonnet` → the same comment line (`.aimon/agents/my-analyzer.md`) |

```text
ko: # model 없음: 메인 에이전트의 모델로 돈다. 적으면 쓰인 그대로 provider 로 간다(별칭을 풀지 않는다).
en: # No model: runs on the main agent's. A model written here goes to the provider as written; no alias is resolved.
```

"Main agent" rather than "parent" here, because every CLI entry path a user subagent runs through hands the resolver
the main agent's model (`L-20`'s closing table), and the shipped bundle comment says the same.

### 4.4 `modules/aimon-llm-anthropic/README.md` (no translation exists)

```text
:134 before | `temperature` | double | `0.0` | 0.0 ~ 1.0 | 샘플링 온도 |
     after  | `temperature` | double | (미설정) | 0.0 ~ 1.0 | 샘플링 온도. 설정하지 않으면 요청에 싣지 않는다 — 호출의 `LlmModel` 이 싣는 값이 먼저다 |
```

Nothing else in the README changes. The `L-27` sites `:55` and `:106` are out of scope (D3).

### 4.5 `modules/aimon-cli/src/main/resources/agents/default-openai/skills/skill-creator/references/schemas.md`

```text
:228 before     "executor_model": "claude-sonnet-4-20250514",
     after      "executor_model": "<model-name>",
```

### 4.6 Reflow (item 6) — CLI guide ko + en in one commit; the design record in the same commit

`docs/getting-started/aimon-core-integration-via-cli-reference.md`, lines `:294-296` become two lines (`:290-293`
unchanged):

```text
배너의 `Agent bundle:` 줄은 불러온 번들을, `LLM Provider: <provider> (<model>)` 의 괄호 안은 메인 에이전트의
요청이 싣는 모델을 보여 준다 — 서브에이전트가 따로 적은 모델은 보여 주지 않는다.
```

`docs/getting-started/aimon-core-integration-via-cli-reference.en.md`, lines `:303-305` (`:299-302` unchanged;
`source_commit: 5606b04` → `e69999a`):

```text
model. In the startup banner, the `Agent bundle:` line names the bundle that loaded, and the parentheses in
`LLM Provider: <provider> (<model>)` are the model the main agent's requests carry — a subagent's own model is not
shown.
```

`docs/design/llm/provider-switch-agent-model-check.md`, lines `:13-17` (`:1-12` and `:18+` unchanged):

```text
> body as approved in design review round 3, kept byte-exact rather than corrected — the house habit in this
> directory, for the reason `model-capability-binding-round-trip.md` gives. Its file:line citations and counts are
> at `main` `a1236c8`. The review transcripts it cites (`review-1.md`, `review-2.md`, `rebuttal-1.md`) and the run
> records it names (`design/q1-live-probe.md`, `$RUN_DIR/build/`) are not in the repository; §10.4 reproduces the
> probe's measurements.
```

Only the Status block changes. Nothing between the header and §10, nor §10–§12, is touched.

### 4.7 `docs/backlog/llm-config-surface-open-items.md` — `L-19` and `L-27` only

**`L-19`** — insert after `:1136` (one blank line before and after). Wrap like the register: about 120 display columns,
`> ` on every line.

```text
> **정정** *(2026-09-11, #132)*: 위 **왜.** 의 마지막 문장 — anthropic 에서 `llm.model` 을 생략하면 괄호는
> `AnthropicConfig` 의 기본값 `claude-sonnet-4-20250514` 다 — 은 등록한 날에 참이었다. 그 뒤 두 가지가 바뀌었다. 괄호가
> 무엇을 찍는지는 #106 이 바꿨고(아래 닫힘), 그 기본값은 #116 이 `claude-sonnet-4-5` 로 바꿨다(L-24 의 닫힘 — 옛 이름은
> 2026-09-11 에 Messages API 가 404 로 답했다). 그래서 지금은 anthropic 에서 `llm.model` 을 생략하고 메인 에이전트
> 정의가 `model.name` 을 적지 않으면 괄호가 `claude-sonnet-4-5` 다(`2eddf3d` 의 `ReplSession.java:319-325`,
> `LlmClientFactory.java:77-79`, `AnthropicLlmClient.java:762-764`, `AnthropicConfig.java:42`). 등록 문장은 규칙 둘대로
> 고치지 않고 둔다.
```

**`L-27`** — append three rows after `:1628`, then a blank line and the note, before **심각도 (규칙 셋).**

```text
| `docs/features/subagent/subagent-development-guide.md:109` · `:256` — 영어 `.en.md:115` · `:264` | `sonnet` | 코드 서브에이전트 예시 둘의 `.model(...)`. 앞의 것은 `// 모델 별칭` 이라고 적는다 |
| `docs/features/subagent/subagent-development-guide.md:329` · `:354` — 영어 `.en.md:341` · `:366` | `sonnet` | `resolvedModel()` 행과 그 아래 인용이 서브에이전트의 `model` 을 별칭이라 부른다. 이름은 앞의 줄에만 있다 |
| `docs/features/skill/builtin-agent-skill-guide.md:80` · `:123` — 영어 `.en.md:85` · `:128` | `sonnet` | `.aimon/agents/explore.md` · `my-analyzer.md` 프론트매터 예시의 `model:` |

> **정정** *(2026-09-11, #132)*: 이 표는 처음에 일곱 행이었고, 같은 모양의 자리를 두 기능 가이드에서 빠뜨렸다 — 마지막
> 세 행이 그것이고 줄은 `2eddf3d` 에서 읽었다. 세 행은 #132 가 고쳤다: 예시의 모델 줄을 빼고 그 자리에 모델을 적지
> 않았다는 주석을 두었고(처방의 첫째 모양), `resolvedModel()` 행과 인용은 코어 javadoc 처럼 이름을 쓰인 그대로 보내며
> 비어 있을 수 있다고 적는다. 위 일곱 행은 그대로 열려 있다. #132 는 `aimon-llm-anthropic` 의 README 도 건드렸지만
> `temperature` 행만 고쳤고, 이 표의 README 두 행은 손대지 않았다.
```

The item headings, the title and the `docs/backlog/README.md` index row do not change (D3).

### 4.8 `CHANGELOG.md` — one new entry at the top of `[Unreleased]`

```markdown
### Docs: two feature guides stop giving a subagent a model alias, and the override's javadoc stops calling it one

- **The subagent development guide and the built-in agent skill guide (ko + en) no longer write `model: sonnet`**
  (#132). Their four example subagents name no model; a comment in its place says the subagent runs on the main
  agent's model and that an id written there goes to the provider as written. The subagent guide's `resolvedModel()`
  row says the name is sent as written and may be empty, as `SubagentBehaviorSupport` does. Backlog `L-27` now lists
  both guides' sites.
- **Javadoc.** The per-invocation model override in `SubagentExecutionEnvironment`, `SubagentExecutionContext` and
  `DefaultSubagentExecutor` is a model name sent as written, not an alias. No signature changed.
- **`aimon-llm-anthropic` README.** `temperature` has no default: unless configured it is not sent. The table said
  `0.0`.
- **The bundled skill-creator's `benchmark.json` sample** writes `"executor_model": "<model-name>"`, the placeholder
  `aggregate_benchmark.py` writes, instead of `claude-sonnet-4-20250514`.
- **Backlog.** `L-19` records that #116 changed the default model its registered sentence names.
```

---

## 5. Data and interface shapes that change

**None.** No public or internal Java signature, config key, wire name, persisted field or resource path changes. The
only non-prose value touched is one JSON string in a Markdown reference file the skill-creator skill reads (`schemas.md`
is documentation for the model; AIMON sends no request with `executor_model`). Javadoc changes are text only, with the
same line count per member (D4).

---

## 6. Commit plan

The style matches `git log` (`docs(scope): … (#132)`). There is no attribution in any commit or the PR body.

| # | Commit | Files |
|---|---|---|
| 1 | `docs(core): call the per-invocation model override a model name sent as written, not an alias (#132)` | the three Java files |
| 2 | `docs(subagent): stop calling a subagent's model an alias in the subagent development guide (#132)` | ko + en subagent guide |
| 3 | `docs(skill): drop model: sonnet from the built-in agent skill guide's subagent examples (#132)` | ko + en skill guide |
| 4 | `docs(llm-anthropic): say temperature is unset, and not sent, unless configured (#132)` | README |
| 5 | `docs(cli): write <model-name> for executor_model in skill-creator's benchmark.json sample (#132)` | `schemas.md` |
| 6 | `docs: re-wrap the CLI guide's banner paragraph and #92's design-record Status (#132)` | ko + en CLI guide, `provider-switch-agent-model-check.md` |
| 7 | `docs(backlog): record under L-19 that #116 changed the default, and add both guides to L-27's sites (#132)` | register, `CHANGELOG.md` |

Commit 7 comes after 2 and 3 so the `L-27` note's *"#132 가 고쳤다"* ("#132 fixed them") is already true in history.
Before commit 1, run `./gradlew format`, and confirm with `git diff` that it touched only the seven intended javadoc
lines.

---

## 7. Failure modes and handling

| # | What could go wrong | Handling |
|---|---|---|
| F1 | `./gradlew format` splits a javadoc line, adding a line and moving `DefaultSubagentExecutor.java:920`/`:1082` (#133) | Every new line is measured ≤120 and `join_lines_in_comments=false`. Verify `git diff --numstat` shows equal `+`/`-` for all three Java files (expected `3 3`, `3 3`, `1 1`). If not, shorten the wording rather than accept the shift; if it truly cannot be avoided, add a Merge note |
| F2 | Textual conflict with `fork-guard-record-followups` in `DefaultSubagentExecutor.java` | Our hunk is `:789`; theirs is `LoopContext` at `:1073+`. They are separate hunks. The builder does not touch `LoopContext` or anything below `:794` |
| F3 | The `L-19` note moves register lines that #133 cites (`:1329`, `:1453-1455`) | Unavoidable: the note must sit above them. The builder measures the exact offset with `git diff -U0 2eddf3d -- docs/backlog/llm-config-surface-open-items.md` and states it under **Merge notes** |
| F4 | Register title and index row conflict with `fork-guard-record-followups` (they add `L-30`/`L-31`) | Expected by TASK. This run changes no count; resolve at merge by recounting from the body |
| F5 | The ko reflow moves ko CLI guide lines `≥297` up by one | Merge notes name the one in-repo citation (`config-value-expansion-and-frontmatter-strictness.md:75`, dated `2659d76`, not edited). No sibling issue cites the CLI guide |
| F6 | A reflow accidentally changes a word, splits a code span, or breaks the §3.4 marker | Check that the joined text is identical before and after (Python one-liner: `' '.join(lines).split()` equal). Grep that `:13` still contains `body as approved in design review round 3, kept byte-exact`. Check that `git diff` of the design record touches only lines 13–17 |
| F7 | The structure check fails because ko and en edits diverge (these pairs are level, so a mismatch fails) | Every edit is in-fence text, one table cell, or one quote block, applied identically to both locales. Advisory `fence-hash-lines` for the skill guide gains one `#` line per fence **on both sides**, so they stay equal. Run the check |
| F8 | The new YAML comment line breaks parsing for a user who copies the example | The same shape ships in three bundles and is loaded through the same `MarkdownSubagentParser(new SubagentContentParser())` a user's `.aimon/agents` file uses. `BundledSubagentModelTest.java:70` pins it. The comment is on its own line, so YAML treats it as a comment |
| F9 | A wrong `source_commit` (for example, moving the two guides to `2eddf3d`) | D8. The builder re-runs `git log -1 --format=%h 2eddf3d -- <canonical>` for all three pairs before committing |
| F10 | Editing the frozen body of the §3.4 record | Only lines 13–17 of the Status block. The body between the header and §10 is untouched (F6's diff check) |
| F11 | A live API call, or a key leaking into the Gradle run | None is needed. Run `env | grep -E '^(ANTHROPIC_KEY|OPENAI_KEY)='` (must print nothing) and `env -u ANTHROPIC_KEY -u OPENAI_KEY ./gradlew checkAll`. Do not read `~/.anthropic-key` or `~/.openai-key` |
| F12 | The README row makes a claim the capability rule contradicts | D6 wording makes no "sent when configured" claim |
| F13 | The builder "fixes" out-of-scope sites it notices (README `:55`/`:106`, the `model(String)` row, parser javadoc) | Scope discipline. They go to `build/deviations.md` and the PR body, and `L-27` already tracks the README and parser rows |

---

## 8. Test strategy

This is a documentation and javadoc change, so no new test is added. A test that parses guide prose would be a new
docs-test mechanism #132 does not ask for, and the one executable shape involved (a front matter whose model line is a
comment) is already pinned by `BundledSubagentModelTest`. Verification is the gate plus mechanical invariants:

1. **Gate, with measured numbers.** Confirm neither key is exported, then
   `env -u ANTHROPIC_KEY -u OPENAI_KEY ./gradlew checkAll`. Report tests, failures, errors and skipped, summed from
   `modules/*/build/test-results/test/*.xml` `<testsuite>` attributes — not "it passed". Also confirm that `checkFormat`
   and `checkStyle` ran (they are part of `checkAll`).
2. **The four doc checks**, each exit 0, with their summary lines recorded:
   - `python3 scripts/check-doc-links.py`
   - `python3 scripts/check-backlog-registers.py` — expect `llm-config-surface-open-items.md  27  (열림 14 · 닫힘 13 · 해소 0)`
   - `python3 scripts/check-translation-staleness.py` — expect `32 up to date`
   - `python3 scripts/check-translation-structure.py` — expect `32 structurally identical`
3. **`mkdocs build --strict`** (CONTRIBUTING PR checklist; `mkdocs` is on this machine). It must exit 0, and its
   "unrecognized relative link" count must not rise over the base (46 after #121).
4. **Acceptance greps**, the issue's own reproduction, which must now come back empty or changed:
   - `git grep -n -E 'sonnet|alias|별칭' -- docs/features/subagent/subagent-development-guide*.md` → no output
   - `git grep -n 'model: sonnet' -- docs/features/skill/builtin-agent-skill-guide*.md` → no output
   - `git grep -n -E 'override alias|per-invocation model alias' -- modules/aimon-core/src/main/java/at/aimon/core/subagent` → no output
   - `git grep -n '`temperature`' -- modules/aimon-llm-anthropic/README.md` → shows `(미설정)`
   - `git grep -n 'executor_model' -- …/skill-creator/references/schemas.md` → `"<model-name>"`
   - `git grep -n -E 'subagent-development-guide|builtin-agent-skill-guide' -- docs/backlog` → the three `L-27` rows
   - `sed -n 13p docs/design/llm/provider-switch-agent-model-check.md | … wc -m` ≤ 116
5. **Line-count invariants**, with `git diff --numstat 2eddf3d`:
   - equal `+`/`-` for the three Java files, both guide pairs, `README.md`, `schemas.md` and
     `provider-switch-agent-model-check.md`;
   - the ko CLI guide shows exactly one line fewer, the en CLI guide an equal count (plus the one front-matter line);
   - `wc -l` of each Java file is unchanged.
6. **Javadoc diff is javadoc only**: `git diff -U0 2eddf3d -- '*.java' | grep '^[+-] ' | grep -v '^[+-]\s*\*'` → no
   output.
7. **Optional**: `./gradlew :aimon-core:javadoc`, to show the edited comments still render (no new `{@link}` is added,
   so no failure is expected).

---

## 9. Open questions (not silently assumed)

- **Q1 — Apache-2.0 §4(b) on `skill-creator`.** The directory's `LICENSE.txt:98` requires modified files to *"carry
  prominent notices stating that You changed the files"*. TASK says to keep the change to one value, which leaves no
  room for a notice, and nothing in the tree records modifications to that skill. This design follows TASK (no notice)
  and flags it in the PR body for the maintainer to rule on. Adding a notice later is a separate one-line change.
- **Q2 — "the per-invocation model field" in `DefaultSubagentExecutor`.** No such field exists. The only alias wording
  in that file is `buildModelConfig`'s `@param modelOverride` (`:789`), which is also what the issue cites. The design
  treats that `@param` as the member TASK assigns this run. If the maintainer meant something else, it is not in the
  file on `2eddf3d`.
- **Q3 — `source_commit` for the two guides.** D8 keeps `eec9ccd` because it is the canonical's last commit. If TASK's
  remark was meant to move it to the base commit instead, that would contradict `CONTRIBUTING.md`, `65bcde0` and
  `3e2deef`. The PR body states the reading so it can be overturned.
- **Q4 — The ko reflow drops a line.** D5 accepts it. If the maintainer prefers line numbers held constant over the
  natural wrap, the alternative is known and costs a short final line.
- **Q5 — `L-27`'s trigger fired.** Item 3 edits the README `L-27` says to revisit when touched. Per scope discipline the
  design leaves `README.md:55`/`:106` and records that in `L-27`'s note (D3). Whether to fix them now is the
  maintainer's call, not this run's.

---

## 10. PR body skeleton (for the builder)

- `Closes #132`
- **Decisions**, one heading each: D1 guide examples · D2 `L-19` correction placement · D3 `L-27` coverage · D4 javadoc
  wording and line counts · D5 reflow rule · D6 README row · D7 `benchmark.json` value · D8 `source_commit` · D9
  CHANGELOG. Each gives the option taken, the options rejected, and why.
- **Merge notes:**
  - the `L-19` note's measured offset, which moves #133's `llm-config-surface-open-items.md:1329` and `:1453-1455`;
  - the ko CLI guide lines `≥297` moving up by one, and the dated in-repo citation of `:374-378`;
  - confirmation that `DefaultSubagentExecutor.java:920`/`:1082` (#133) and `provider-switch-agent-model-check.md:113`
    (#134) did not move;
  - the expected title / index-row conflict with `fork-guard-record-followups`.
- **Gate numbers** (§8.1–8.3), with the key-unset check.
- **Out of scope / deviations** (mirrored in `build/deviations.md`):
  - `L-27`'s README rows (`:55`, `:106`) left, and its trigger fired (Q5);
  - the subagent guide's `model(String)` builder row left (D1 end);
  - the Apache §4(b) question (Q1);
  - TASK's "field" wording (Q2).
- **Not measured** (carried from the issue, unchanged by this run): whether `sonnet` or any name is served; the banner
  in a TTY; `claude-sonnet-4-5` behind a gateway or over time.

## 11. After the build — departures and corrections

Appended after the build, from the run's `build/deviations.md`, which carries the same nine entries. The build made no
commit (§11.2). Citations here are at `2eddf3d` unless they name a line of the change that carries this record.

### 11.1 The record is committed (DV-1)

The paragraph above §1 says this run commits no design record and leaves `docs/design/README.md` §3.4 and its index
tables alone. That was the approved plan. The build was then directed to commit the design as a record, so it is here:

- the approved title, the `Status` block, then the approved text byte for byte. Removing the `Status` block restores
  the reviewed file exactly: 41,565 bytes, equal;
- this section appended after it;
- one row in `docs/design/README.md` §2's `llm` table, after `model-names-sent-and-shown.md`, the record this one
  continues. §3 is not edited.

### 11.2 Where the build departed

- **DV-2 — nothing committed.** §6's seven commits were not made; the build was told not to commit. §6 also has no row
  for this record or its index row — they fit an eighth commit after commit 7. D8's `source_commit` values (`eec9ccd`,
  `eec9ccd`, `e69999a`) stay right only while nothing else commits to those canonicals first.
- **DV-3 — the example comments also say when to write an id** (review note 4). D1 says option C's useful half, *when*
  to write an id, is folded into the comment, but the comments in §4.2 and §4.3 only say what happens to one. Each of
  the four sites now says both. For example, the en subagent guide has
  `// No model: runs on its parent's model (usually the main agent's). Set one only to run on another model: an id the configured provider serves, sent as written`,
  and the ko skill guide has
  `# model 없음: 메인 에이전트의 모델로 돈다. 다른 모델로 돌려야 할 때만 설정된 provider 가 서비스하는 id 를 적는다 — 쓰인 그대로 간다(별칭을 풀지 않는다).`
  §4.2's constraints hold: no model name, "sent as written" in each comment, and line counts unchanged.
- **DV-4 — the README row names both sources** (review note 7). §4.4's *"설정하지 않으면 요청에 싣지 않는다 — 호출의
  `LlmModel` 이 싣는 값이 먼저다"* can be read as "not configured, so never sent". A call's `LlmModel` temperature is
  sent with no configured value (`AnthropicLlmClient.java:546-547`). The row now reads *"기본값이 없다 — 호출의
  `LlmModel` 이 싣는 값이 먼저이고, 둘 다 없으면 요청에 싣지 않는다"*. The default cell is `(미설정)`, as designed.
- **DV-5 — the CHANGELOG wording** (review note 5). The entry says the guides *"no longer give their example subagents
  the model `sonnet`"*; §4.8 said *"no longer write `model: sonnet`"*, but the subagent guide wrote
  `.model("sonnet")`. It also describes the comment as DV-3 writes it, the parent's model rather than *"the main
  agent's model"*, and its README bullet follows DV-4.
- **DV-6 — `L-27`'s new rows say they are fixed** (review note 6). Each of the three rows' 무엇 cells ends
  `— #132 가 고쳤다`; no existing row or column changes. The note says *"앞의 일곱 행"*, because it now sits below ten
  rows. It describes the comment as DV-3 writes it, and it names the **언제 다시 볼까** trigger the README edit fired.
  The counts are unchanged (`27 | 14 | 13 | 0`, recounted by `check-backlog-registers.py`), and `L-28` and `L-29` are
  unused.
- **DV-7 — §8.4's first grep narrowed** (review note 1). `sonnet|alias|별칭` cannot come back empty against §4.2's own
  row text, which contains `(별칭을 풀지 않는다)` and `(no alias is resolved)`. The build ran
  `sonnet|model alias|모델 별칭|별칭\(|override alias` over both files → no output, and `alias|별칭` → exactly the two
  `resolvedModel()` rows.

### 11.3 Merge notes §10 did not list (DV-8)

Measured with `git diff -U0` against `2eddf3d`:

- **`docs/backlog/llm-config-surface-open-items.md`.** The `L-19` note adds 8 lines after `:1137`, so #133's `:1329`,
  `:1417` and `:1453-1455` become `:1337`, `:1425` and `:1461-1463`. §2 and §10 name only the first and last. The
  `L-27` additions (10 lines after `:1628`) sit below every cited line.
- **`CHANGELOG.md`.** The new entry adds 16 lines after `:9`, which moves lines cited by five sibling issues:
  #130 `:16`, #135 `:21-22`, #133 `:118-122`, #129 `:149` and #131 `:2080-2081`, each down 16.
- **`docs/design/README.md`.** The index row (§11.1) adds a line after `:96`, which moves every §3 line cited by #130,
  #133 and #134 down one.
- **`docs/getting-started/aimon-core-integration-via-cli-reference.md`.** The ko reflow moves every line from `:297`
  up one. D5 and F5 name one in-repo citation of such lines; there are three:
  - `config-value-expansion-and-frontmatter-strictness.md:75`, which cites `:374-378`;
  - `model-names-sent-and-shown.md:67` and `:337`, which cite ko `:428`;
  - `provider-switch-agent-model-check.md:112`, which cites ko `:533`.

  All three sit in exempt approved bodies dated to their base commits (`2659d76`, `9b642cc`, `a1236c8`), so they are
  dated, not broken, and none is edited.
- **Not moved**, as §7 F1 and §2 required:
  - `DefaultSubagentExecutor.java:920` and `:1082`, because `wc -l` stays 1223;
  - `provider-switch-agent-model-check.md:113`, because the `Status` reflow replaces 5 lines with 5.

### 11.4 Corrections to the reasoning above (DV-9)

Nothing built changes because of these.

- **F1's expected numstat.** F1 expects `3 3` for `SubagentExecutionEnvironment.java`. §4.1 changes four lines there
  (`:186`, `:507`, `:508`, `:511`), so `4 4`, as measured, is what §4.1 specifies. No member's line count changes.
- **D5's standard for a short final line** (review note 3). D5 rejects a seven-line ko wrap partly for its one-word
  final line (`않는다.`), then accepts an en wrap ending in the one-word line `shown.`. The argument cannot tell the two
  apart: a short final line is the house shape in both locales. What rejects the seven-line ko wraps is D5's other
  reason, four lines narrowed to about 76 columns beside neighbours at about 110.
- **The citation count.** D5 and F5 say *"the one in-repo citation"*; §11.3 counts three.

### 11.5 What did not go to the backlog

- **§9 Q1, Apache-2.0 §4(b) on `skill-creator`**, outlives this phase: it applies to every future edit under that
  directory. It is not an LLM configuration-surface question, and `llm-config-surface-open-items.md` is the only
  register this run held IDs in, so it is left to the maintainer to rule on and register. The build followed §9: one
  value changed, no notice added.
- **Q2–Q5** are phase-local and stay in §9.
- **`L-27`'s README rows** (`README.md:55`, `:106`) are unchanged and stay open under `L-27`, whose note now records
  that its trigger fired.
