# Design — #92: switching `llm.provider` leaves the agent on the other vendor's model

> Status: **IMPLEMENTED** — `aimon-cli` (`at.aimon.cli.factory.AgentModelProviderCheck` and the
> `AgentSetupFactory.reportAgentModelMismatch` seam), the comments in `default-config.yaml`, and the CLI guide's
> provider-switch passage — §4.1 *provider 를 바꿀 때 — `agent.name` 도 함께 바꾼다* in
> [`aimon-core-integration-via-cli-reference.md`](../../getting-started/aimon-core-integration-via-cli-reference.md).
> Source: issue [#92](https://github.com/kangwoo/aimon-core/issues/92).
>
> **[§10](#10-after-the-build--departures-corrections-and-what-went-to-the-backlog), appended after the build, is
> where this document departs from what was built, and [§11](#11-after-104107), appended after #104–#107, is what
> those four follow-up issues changed.** Everything between this header and §10 is the body as approved
> in design review round 3, kept byte-exact rather than corrected — the house habit in this directory, for the
> reason `model-capability-binding-round-trip.md` gives. Its file:line citations and counts are at `main` `a1236c8`.
> The review transcripts it cites (`review-1.md`, `review-2.md`, `rebuttal-1.md`) and the run records it names
> (`design/q1-live-probe.md`, `$RUN_DIR/build/`) are not in the repository; §10.4 reproduces the probe's
> measurements.
>
> What this work left open is in [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md),
> L-17 ~ L-21, closed by #104–#107 (§11).

Run `cli-provider-agent-switch`, base `main` at **`a1236c8`**. Scope is fixed by `TASK.md`: **option 1 (say it
where the switch is made) + option 2 (warn at startup, never refuse)**, not option 3.

Files this run changes:

- `modules/aimon-cli/src/main/**` and its tests
- the CLI guide (ko + en)
- the shared `CHANGELOG.md` `[Unreleased]`
- by the Resume decisions:
  - one backlog item (`L-17`) and its index row
  - the link targets at `docs/README.md:49` / `docs/README.en.md:57`
  - one comment at `README.md:174`

> **Revision 3.** A new design agent wrote it from revision 2, `review-2.md`, `review-1.md`, `rebuttal-1.md`
> and `TASK.md` § "Resume — 2026-09-11".
>
> **Review 2's blocking finding is fixed the way the reviewer recommended.** Origin is **instance identity alone**:
> `BUNDLE` or `OUTSIDE_BUNDLE`, nothing else. `AMBIGUOUS`, test C8 and the "unreachable" claim are gone. The origin
> table (§3.2), the prose (§3.2, §3.6 R14/R16, §7) and the tests (§6 C, F) now say the same thing.
> - §6 F1 is named as the guard against a registry that returns copies.
> - §6 F now builds the runtime through the real `AimonStackBuilder` rather than a hand-assembled composite, so it
>   also guards the wiring that hands the bundle's registry to the runtime (R21).
> - §6 F5 pins review 2's reachable variant end to end.
>
> **Every non-blocking finding is taken:**
> - user subagent paths print resolved against the runtime's working directory (§3.4);
> - bundle paths are labelled `classpath` (§3.4);
> - the banner's `LLM Provider: … (<model>)` is `llm.model`; the yaml comment and the guide say so, and it is D10;
> - test E4 now throws where the check actually reads (§6 E);
> - Q2 is settled, and the example is never called "working" without the caveat (§4.4);
> - the dreamer wording (§4.3);
> - fragment assertions include their closing backtick (§6 C9, D).
>
> **Resume decisions applied:**
> - Q1 by the launcher's probe: used in the guide, kept out of the warning (R18).
> - Q2 yes (§4.3, §4.4).
> - Q3 yes, `L-17` (§4.7), recounted against the new `check-backlog-registers.py` (§6 G).
> - Both optional edits are taken: the `docs/README` link retarget (§4.8) and the `README.md:174` comment (§4.9).
>
> No finding is rebutted, so there is no `rebuttal-2.md`.

---

## 1. The problem, restated

**Where the model name comes from.** The CLI builds its LLM client from the `llm:` block, but the model name each
agent request carries comes from the agent definition:

- `agent.name` picks the classpath bundle `agents/<name>/` (`AdaptiveAgentBundleLoader.java:152-153`; base path
  `AgentSetupFactory.java:420`).
- Both clients send that definition's `model.name` in preference to `llm.model`: `AnthropicLlmClient.java:378` and
  `OpenAILlmClient.java:313` read `modelConfig.getName().orElse(config.getModel())`.

**What ships.** The shipped config keeps `agent.name: "default"` (`default-config.yaml:130`), and that bundle names
OpenAI models:

- `gpt-5.6-terra` for the main agent (`agents/default/agent.md:8`);
- `gpt-5.1` for its `explore` subagent (`agents/default/agents/explore.md:5`).

**What goes wrong.** A user who edits the `llm:` block to Anthropic still sends OpenAI names to Anthropic, and
Anthropic answers them with HTTP 404 `not_found_error` (probe, 2026-09-10). Meanwhile `llm.model` takes effect
where the user is not looking:

- memory;
- wiki generation;
- the startup banner, which prints `llm.model` as the provider's model.

**What nobody says.** No comment, guide or check says any of this. `agent.name: default-anthropic`, the setting
that switches the main agent, is never mentioned. And that bundle is not a clean switch either: its `explore`
subagent names `haiku`, which is sent as written and got the same 404.

## 2. Re-verification at `a1236c8`

Revision 2 read every line at `ade5978`. Between `ade5978` and `a1236c8` no `src/main` file changed outside
`aimon-llm-capability-testkit`: `git diff --stat ade5978 a1236c8 -- 'modules/*/src/main/**'` lists two testkit
files. The CLI guide is unchanged since `6c53cfe`. The rows below were re-read at `a1236c8`, and rows marked
*new* are facts revision 2 did not have.

| Fact | At `a1236c8` | Consequence |
|---|---|---|
| Shipped config | `default-config.yaml`: `:19` `provider: "openai"`, `:20` `baseUrl: https://api.openai.com/v1`, `:22` `model: "gpt-5.1"`, `:130` `name: "default"`. `./gradlew :aimon-cli:run` without `-c` loads this embedded file after an optional `default-config-local.yaml` (`CliConfigLoader.java:88-96`). `-c` does not merge over it | Editing only `provider:` here is the likeliest switch path → Gate A clause 3 (§3.1) |
| Bundle models | Main agents: `default/agent.md:8` `gpt-5.6-terra`, `default-openai/agent.md:8` and `ops-agent/agent.md:8` `gpt-5.1`, `terra/agent.md:12` `gpt-5.6-terra` (no subagents), `default-anthropic/agent.md:8` `claude-sonnet-4-5`. Subagents: `default/agents/explore.md:5` `gpt-5.1`; `explore.md:5` in `default-anthropic`, `default-openai` and `ops-agent` all `model: haiku`. `default`, `default-openai` and `default-anthropic` share `name: default-agent` (`agent.md:3`) | Same defect as the issue under new names; D1 |
| Parser | `model.name` is optional: without a `model` block the model is nameless (`MarkdownAgentDefinitionParser.java:150-152`), and `name` is read only when present (`:158-160`) | Docs say "a definition's `model.name`, when it has one" (D7) |
| Clients | Both prefer the definition's model (`:378`, `:313`). Both skip a null or empty `baseUrl` (`AnthropicLlmClient.java:187-189`, `OpenAILlmClient.java:139-141`). Neither reads a base-URL environment variable (review 1, `javap`) | "Unset" means the vendor's public host |
| Where `llm.model` goes | Dialectic engine `AgentSetupFactory.java:522-524`. Deriver and reconciler `:529-530`. The dreamer strategy itself `:1143`, and its LLM judge `:1183`, which takes `memory.dreamer.scorer.llm.model` when set and `llm.model` otherwise (`:1192-1201`). Wiki generation gets a nameless model (`:1351` → `LlmWikiPageGenerator.java:219`). *New:* the REPL banner prints `LLM Provider: <provider> (<model>)` from `getDefaultModelName()` (`ReplSession.java:258-259`), and both clients return `config.getModel()` there (`AnthropicLlmClient.java:762-764`, `OpenAILlmClient.java:589-591`) | The comment and guide name all of these (§4.3, §4.4); D8, D10 |
| `llm.model` absent | **openai:** required (`LlmClientFactory.java:322-329`). **anthropic:** set only when present (`:77-79`), so the client model is `AnthropicConfig`'s default `claude-sonnet-4-20250514` (`AnthropicConfig.java:40`) — for wiki generation and the banner. The memory components reject a null name (`LlmDialecticEngine.java:74`, `LlmDeriver.java:151`, `DefaultReconciler.java:102`, `RandomWalkDreamer.java:92`, `LlmJudgeSurprisalScorer.java:92`). The dialectic engine is built unguarded (`:522-524`), so under anthropic, `memory:` without `llm.model` fails startup as `Unexpected error: llmModelName cannot be null` (`AimonCli.java:129-131`) | The comment says both halves (§4.3); D9 |
| Compaction | Does not read `llm.model`: the guard gets the execution's model (`OrcaAgentExecutor.java:1605-1608`, `DefaultSubagentExecutor.java:539`), and `/compact` uses the context default (`CompactCommand.java:123`) | Nothing to add |
| Subagent model resolution | `SubagentLlmDefaults.resolveModel` (`:61-75`) resolves in this order: the Task-tool override (`:66-67`), then the subagent's frontmatter string as is (`:68-69`), then the parent's model name (`:70-71`), then the literal `gpt-4` (`:21`). *Re-counted for L-17:* `grep -rn haiku` over `modules/*/src/main/java` finds `haiku` only in javadoc and comments (`TaskTool.java:70`, `:94`; `SubagentMetadata.java:40`; `SubagentContentParser.java:25`) and inside the full-name prefixes `claude-3-5-haiku` / `claude-3-haiku` (`InMemoryModelPriceTable.java:60`, `:63`; `InMemoryModelContextWindowRegistry.java:38`). Over `src/main/resources` it finds only the three `explore.md`. `Subagent.java:203` and subagent `package-info.java:62` mention `"sonnet"` as an example. Nothing maps an alias to a model id | `model: haiku` goes on the wire verbatim (L-17) |
| Where subagents come from | `AimonStackBuilder.java:416-417` passes `agentSpec.getBundle()` unchanged. `OrcaAgentRuntimeFactory.java:824-826` layers `agentBundle.getSubagentRegistry()` **as-is**, then a **separate** user `DefaultSubagentRegistry(fileSystem, agentsDirectory)`, then the code layer (`:996-1005`). The code layer is the `InMemorySubagentRegistry` created at `AimonStackBuilder.java:305` and passed at `:395`; nothing in bootstrap or CLI main registers into it. `DefaultSubagentRegistry` parses at construction and returns stored instances (`:81`, `:91-99`). `CompositeSubagentRegistry` returns the winning layer's instance from `getSubagent` (`:71-81`) and `getAllSubagents` (`:84-97`). In the `file://` shape the bundle's own registry is a composite (`AdaptiveAgentBundleLoader.java:196-205`), which preserves identity. Nothing in main reloads a subagent registry except the composite's own propagation (`CompositeSubagentRegistry.java:119`, `:126`). *New (review 2):* the user layer parses its own instances, so a user file is a different instance from the bundled one **whatever it contains** | Identity alone labels every reachable case (§3.2) |
| *New:* where `.aimon/agents` is | `createFileSystem()` (`AgentSetupFactory.java:878-883`) roots the CLI's `LocalFileSystem` at `getJarDirectory()` (`:1366-1383`): the jar's parent directory, else `user.dir`, which is `modules/aimon-cli` under `./gradlew :aimon-cli:run`. The stack is handed that instance (`:556`), and the runtime's `Environment` takes its working directory from it (`OrcaAgentRuntimeFactory.java:816`). Relative paths resolve under that base (`PathValidator.java:57`, `:90`). The directory is `StackPaths.AGENTS_DIRECTORY` (`StackPaths.java:26`), passed at `AimonStackBuilder.java:394`. The banner prints the same working directory (`ReplSession.java:255`) | Print the resolved path (§3.4) |
| *New:* bundle files are classpath resources | Found with `ClassLoader.getResource` (`AdaptiveAgentBundleLoader.java:152-153`): inside the jar, or a build output from source. The guide already says so (ko `:533`, en `:554`) | Label them `classpath` (§3.4) |
| Where a `log.warn` goes | `logback.xml`: the root logger is `WARN` and writes to `FILE` only (`:26-28`, `~/.aimon/logs/aimon.log`). `CONSOLE` is never referenced, and `<logger name="aimon">` (`:20`) does not match `at.aimon.*` | `log.warn` alone never reaches the terminal (§3.3) |
| *New:* Q1 probe | `design/q1-live-probe.md`, 2026-09-10T22:49:27Z, Anthropic Messages API: `model: gpt-5.6-terra` → HTTP 404 `not_found_error` (`req_011CevaKm3aqiNGUuWVjY3nY`); `model: haiku` → HTTP 404 `not_found_error` (`req_011CevaKnvSxstq6wuuWgXd8`). OpenAI was not probed | The guide states it with its date (§4.4); the warning does not (R18) |
| *New:* the Anthropic main-agent name | The capability table's javadoc lists the undated `claude-*-4-5` names among those measured on 2026-09-10, resolving to dated snapshots (`InMemoryModelCapabilityRegistry.java:64-70`) | Supporting only; this run did not probe `claude-sonnet-4-5` (§9 U2) |
| *New:* backlog | `llm-config-surface-open-items.md`: title `등록 항목 16건 (열림 12 · 닫힘 4)`, last item `## L-16` at `:860`, `## 관련 문서` at `:918`. Index row `docs/backlog/README.md:347` reads `\| 16 \| 12 \| 4 \| 0 \|`. `python3 scripts/check-backlog-registers.py` reports the same tally. `L-17` appears nowhere under `docs`, `scripts`, `modules` or in `CHANGELOG.md` | §4.7 |
| *New:* docs entry points | `docs/README.md:49` and `docs/README.en.md:57` link the guide by file, no anchor. `docs/README.en.md` has `source_commit: 8f71212`, and both files were last changed together in `0335838` (#90). `README.md:174` is `provider: "openai"          # or "anthropic"` and `:177` is `model: "gpt-4o-mini"`. There is no `README.ko.md`: the only root `*.ko.md` is `CONTRIBUTING.ko.md` | §4.8, §4.9, D11 |
| *New:* anchors | `check-doc-links.py` computes anchors with `docs_tree.slug` (`scripts/docs_tree.py:66-79`). The site uses `pymdownx.slugs.slugify(case=lower)`, configured to match GitHub (`mkdocs.yml:111-115`). The guide's table of contents lists `##` headings only (ko `:23-32`) | A new `####` changes no existing anchor (§4.4) |

---

## 3. Approach

The design has two parts, one per option.

- **Option 1:** comments next to `llm.provider`, `llm.model` and `agent.name`, and one sub-section in the CLI guide
  (ko + en). The Resume decisions add three pieces: the caveat on `default-anthropic`'s `haiku` subagent, backlog
  `L-17`, and two link retargets plus one README comment pointing at the new passage.
- **Option 2:** a stateless check in `aimon-cli`, run once after the stack is assembled. It prints one message
  through the CLI's existing startup-message path and never throws. Each entry names the file the runtime
  actually read, and each remedy it offers is scoped to the entries that remedy actually changes.

### 3.1 What "does not look like a model the provider serves" means

Unchanged from revision 2; both reviews verified it. The check produces entries only when **both** gates pass.

**Gate A — the request goes to an API that cannot serve the other vendor's names.** It passes when:

1. `llm.baseUrl` is unset — null or empty, as both clients read it — **or**
2. its host is the configured provider's own public API host: `api.openai.com` for `openai`,
   `api.anthropic.com` for `anthropic` — **or**
3. `llm.provider` is `anthropic` and the host is `api.openai.com`. This pairing has no legitimate reading: nothing
   at OpenAI's host speaks the Anthropic Messages API. It is also exactly what editing only `provider:` in the
   shipped config produces (`default-config.yaml:20`).

The comparison ignores case, scheme, port and path. The check is silent for every other `baseUrl`:

- a gateway or a proxy;
- Azure (`*.openai.azure.com`);
- a value that does not parse;
- `provider: openai` with a host on `api.anthropic.com` — see below.

**Why the mirror of clause 3 stays silent.** `provider: openai` pointed at `api.anthropic.com` can be a working
configuration, because Anthropic serves an OpenAI-SDK-compatible endpoint there. That is vendor knowledge not
written in the tree, and the choice that relies on it is silence. If the fact were false, the cost would be a
missed warning, not a false one.

**Gate B — the model name belongs to the *other* vendor's family.** The families are the vendor prefixes under
which the built-in capability table names its rows:

| Vendor | Family prefixes | Where the tree already names them |
|---|---|---|
| Anthropic | `claude-` | the eleven prefixes in `registerAnthropicDefaults` (`InMemoryModelCapabilityRegistry.java:323-366`) |
| OpenAI | `gpt-`, `o1`, `o3`, `o4` | `gpt-5-chat` `:198`, `gpt-5` `:222`, `gpt-5.6-terra` `:275`; `o1`/`o3`/`o4` `:242-243` and the exact o-series names `:154`. `TikTokenEstimator.java:109` independently treats `o1`/`o3` as OpenAI names |

A name neither family claims is **silent**: `haiku`, `prod-assistant`, `llama-3.1-70b`, a renamed gateway
deployment. Matching folds case, as the registry does (`:500-504`).

**Why the other vendor's family, and not "anything outside this vendor's family".**

- A vendor's own API never serves the other vendor's family. That is certain.
- "What this vendor serves" is not a family. OpenAI also serves `chatgpt-*`, `codex-*` and `computer-use-preview`,
  and either vendor may ship a new family tomorrow.

**Why prefixes rather than the table's rows.**

- A registry look-up would miss `gpt-4o`, `gpt-4.1` and `claude-sonnet-4-20250514`. The last is `AnthropicConfig`'s
  own default, left out of the table on purpose (`:353-354`).
- The registry carries no vendor.

**Why broad prefixes cannot cause a false alarm.** Gate A limits every family check to an API that cannot serve that
family:

- the configured vendor's own API (clauses 1-2);
- a host that cannot answer this provider at all (clause 3).

So a false alarm could only come from Gate A, which is why Gate A is the conservative half.

**Why Gate A exists.** The tree documents a `claude-*` model served through `provider: openai` behind an
OpenAI-compatible gateway as a legitimate path (`InMemoryModelCapabilityRegistry.java:53-61`, `:307-310`). A
name-only rule would warn on every start of that deployment.

**Remaining false negatives, accepted.** Both fail at their first request, for a reason a model check cannot name:

- `provider: anthropic` + `api.openai.com` + a `claude-*` agent;
- `provider: openai` + `api.anthropic.com` + an OpenAI-family agent.

They are follow-up D3.

**Host literals.** Neither vendor config exposes a default base-URL constant, so the two hosts are the SDK defaults.
One of them is already in `default-config.yaml:20`.

### 3.2 Which models are checked, and where each one lives

**Entries.** The check builds one entry per model:

- **the main agent**, when `agent.getMetadata().getModel().getName()` (`AgentMetadata.java:81`) is present.
  - Key: `model.name`.
  - The main agent always comes from the loaded bundle, so its location is the classpath file
    `agents/<agent.name>/agent.md`.
- **every subagent the runtime will launch that names a model of its own** — `runtimeSubagents.getAllSubagents()`,
  the composite's winning definition per name, when `getMetadata().getModel()` is non-blank.
  - Key: `model` (`SubagentMetadata.java:74`).

**Origin of a subagent entry** — one rule, decided against the loaded bundle's registry
(`agentBundle.getSubagentRegistry()`):

| Origin | Condition | Location printed (§3.4) |
|---|---|---|
| `BUNDLE` | the bundle registry's `getSubagent(name)` returns **the same instance** (`==`) as the runtime's winner | classpath `agents/<agent.name>/agents/<name>.md` |
| `OUTSIDE_BUNDLE` | **anything else**: the bundle has no subagent of that name, or has one that is a different instance — **whatever its model** | `<working directory>/.aimon/agents/<name>.md` |

**Why identity alone labels every reachable case at `a1236c8`.** The runtime layers three registries (§2):

- **Bundle layer** — the bundle's registry object itself, so a bundled winner is identical by construction.
- **User layer** — a separate `DefaultSubagentRegistry` that parses its own instances. A file under
  `.aimon/agents` is therefore never the bundled instance, including a copy that keeps the bundled model.
- **Code layer** — empty in the CLI.

So a non-identical winner can only be a user file, and the printed location names it. Review 2's two inputs both
fall out as `OUTSIDE_BUNDLE`:

- a user `explore.md` that keeps `model: haiku` over `default-anthropic`'s `haiku`;
- a user `explore.md` that keeps `model: gpt-5.1` over `default`'s `gpt-5.1`.

**What identity cannot survive, and what guards it.** Two future changes would break it:

- a registry class that returns copies;
- wiring that wraps or re-parses the bundle's registry before layering it.

Either would label every bundled subagent `OUTSIDE_BUNDLE` and print it at a `.aimon/agents` path that does not
exist. The message has no hedge for that; §6 F1 is the guard. It runs through the real `AimonStackBuilder`, so it
covers the wiring as well as the registry classes, and it fails before a user sees a wrong path. The cost of a miss
is bounded: the model and key on the line are still right, and startup is unaffected.

**Why neither strings nor a hedge.**

- **Comparing model strings** (R14) labels a user copy that keeps the bundled model `BUNDLE`, and names a file the
  runtime no longer reads.
- **Revision 2's `AMBIGUOUS`** (R16) hedged on exactly that reachable case and printed both paths, one of them
  unread.

**Why subagents are included.** Under `provider: anthropic`, `default/agents/explore.md:5` sends `gpt-5.1` through
the same client. The failure then shows up mid-task and is harder to attribute.

**Why the runtime's registry.** A subagent the user wrote has the same failure, and the composite is exactly what
the Task tool sees.

**What is not checked, on purpose.**

- Subagents without a model. They inherit the main agent's name (`SubagentLlmDefaults.java:70-71`), which is
  already checked.
- A definition without `model.name`. It sends `llm.model`, which is not the issue's defect. The `gpt-4` fallback for
  model-less subagents under such a definition is D4.
- The Task tool's runtime `model` argument (`TaskTool.java:345`), which the LLM chooses per call.
- Names neither family claims — in particular `default-anthropic`'s `haiku` (§3.1, R19, L-17).

### 3.3 Where the warning surfaces

Unchanged. This follows the CLI's existing precedent, and no new channel is added.

- **The terminal:** during `create()`, the CLI prints startup conditions with `OutputFormatter.displayInfo`
  (`OutputFormatter.java:164-166`). Examples: `"Peer memory: unknown backend '…', falling back…"`
  (`AgentSetupFactory.java:1016-1017`) and `"Peer memory dreamer disabled: …"` (`:1137`, `:1168`).
- **The log file:** when a condition also matters to operations, that line is paired with `log.warn`
  (`:979-984`, `:1167-1168`).

The warning uses that pair; the pair is required, because `log.warn` alone never reaches the terminal (§2). It
prints once per process start, as one message listing every entry, after the stack is built and before
`ReplSession.start()` prints the banner (`ReplSession.java:175`).

### 3.4 Warning text and remedy rules

**Locations.**

- **Bundle files** — the main agent, and `BUNDLE` subagents — print as ``classpath `agents/<agent.name>/…` ``. The
  file is a classpath resource: inside the jar, or wherever the bundle is built. It is not a path relative to where
  the user stands.
- **`OUTSIDE_BUNDLE` entries** print
  `Path.of(workingDirectory).resolve(StackPaths.AGENTS_DIRECTORY).resolve(name + ".md")`.
  - `workingDirectory` is `agentRuntime.getEnvironment().getWorkingDirectory()`, the value the banner prints as
    `Working Directory:` a moment later. It is also the base the runtime's user layer resolves against (§2).
  - If it is null, blank or not a path (`InvalidPathException`), the relative `.aimon/agents/<name>.md` is printed
    instead. A bad path string never costs the warning.
- **Why the runtime's environment rather than `decorate`'s `fileSystem` parameter.** Today they hold the same value.
  But the environment is what the runtime resolved against, and it is what the banner shows, so the printed path
  begins with the text of the `Working Directory:` line.

**Shape.** One header line, then one line per entry with its location and the key to change, then only the remedies
that apply:

```
Agent model: `llm.provider` is `<provider>`, but these definitions name <Vendor> models, which <Provider>'s API
does not serve:
  - main agent, classpath `agents/<agent.name>/agent.md`: `model.name: <name>`
  - subagent `<sub>`, classpath `agents/<agent.name>/agents/<sub>.md`: `model: <name>`
  - subagent `<sub>`, `<working directory>/.aimon/agents/<sub>.md`: `model: <name>`
Each request carries these names; `llm.model` does not replace them. <remedies> Startup continues.
```

**Remedy sentences.** Each is emitted only under its condition. The fragment in the last column is what §6 asserts,
and it must stay unique to its sentence.

| Sentence | Condition | Text (shape) | Test fragment |
|---|---|---|---|
| **S-switch** | the **main agent** is an entry **and** the configured `agent.name` ≠ the provider's bundle (`anthropic` → `default-anthropic`, `openai` → `default`) | ``Set `agent.name: default-anthropic`: it replaces the main agent and the subagents in its bundle.`` | `` `agent.name: default-anthropic` `` (with both backticks) |
| **S-outside** | S-switch is emitted **and** some entry is `OUTSIDE_BUNDLE` | ``Files under `<working directory>/.aimon/agents` load with every agent, so `agent.name` does not change them: edit those files.`` | `does not change them` |
| **S-edit** | S-switch is **not** emitted | ``Change the key shown on each line.`` | `Change the key shown` |
| &nbsp;&nbsp;+ S-built | S-edit **and** some entry is a bundle file (main agent or `BUNDLE` subagent) | ``A classpath file is part of the agent bundle: edit it where that bundle is built.`` | `where that bundle is built` |
| &nbsp;&nbsp;+ S-override | S-edit **and** some entry is a `BUNDLE` **subagent** | ``Or override a bundled subagent without rebuilding: a file of the same name in `<working directory>/.aimon/agents` replaces it.`` | `without rebuilding` |
| **S-gateway** | Gate A passed by clause 1 or 2 | ``Or point `llm.baseUrl` at a gateway that serves these names.`` | ``Or point `llm.baseUrl` `` |
| **S-host** | Gate A passed by clause 3 | `` `llm.baseUrl` is OpenAI's host, which does not serve Anthropic's API: remove it, or point it at a gateway that serves these names. `` | `is OpenAI's host` |

**The rules these sentences keep.**

- **Never name a file the runtime does not read.** A bundle entry is exactly the instance the runtime resolved
  (§3.2). A user entry prints the directory the runtime resolved it in.
- **Never present a classpath path as a file under the user's feet.** It is labelled `classpath`. S-built says
  where it is edited, and S-override gives the no-rebuild path for a subagent, which a jar user needs.
- **Never name a value that is already set.** S-switch requires `agent.name` ≠ the named bundle. This is review 1's
  input: `default-anthropic` plus a user `reviewer.md` on `gpt-4o` gets no S-switch.
- **Never name a key that does not exist.** A main agent's key is `model.name` (parser `:158-160`); a subagent's key
  is `model` (`SubagentMetadata.java:74`).
- **Never promise more than the remedy changes.**
  - S-switch says what it replaces. What it cannot change — `.aimon/agents` — is called out by S-outside.
  - S-switch does not claim the named bundle is free of other defects. `default-anthropic`'s `explore` still sends
    `haiku`, which the check cannot see by rule (R19). That caveat lives in the yaml comment and the guide (§4.3,
    §4.4) and is tracked as L-17.
- **S-switch also needs the main agent to be an entry.** When only a bundled subagent mismatches, the bundle is the
  user's choice and its main agent is right for the provider. S-edit is the proportionate remedy (R15; reason in
  `rebuttal-1.md`).
- **S-host is required.** In the clause 3 case, fixing only the agent would silence the warning while every request
  still failed on the host.
- **Names come from the configured `agent.name`** (`extractAgentName`, `AgentSetupFactory.java:871-873`), not from
  `agent.getName()`, because three bundles share the metadata name `default-agent` (D1).
- **"The remedy can never itself warn" is narrowed to what is true:** switching to the named bundle removes every
  entry S-switch covers. §6 D pins this by loading each named bundle. `.aimon/agents` entries are outside the claim,
  and S-outside says so.

**Worked outputs.** The build may polish wording; §6 pins fragments, not whole strings.

*Acceptance case* — `provider: anthropic`, shipped `agent.name: default`, no `baseUrl`, no `.aimon/agents` files:
```
Agent model: `llm.provider` is `anthropic`, but these definitions name OpenAI models, which Anthropic's API
does not serve:
  - main agent, classpath `agents/default/agent.md`: `model.name: gpt-5.6-terra`
  - subagent `explore`, classpath `agents/default/agents/explore.md`: `model: gpt-5.1`
Each request carries these names; `llm.model` does not replace them. Set `agent.name: default-anthropic`: it
replaces the main agent and the subagents in its bundle. Or point `llm.baseUrl` at a gateway that serves these
names. Startup continues.
```

*Review 1's input* — `agent.name: default-anthropic`, jar in `/opt/aimon`, and
`/opt/aimon/.aimon/agents/reviewer.md` with `model: gpt-4o`:
```
Agent model: `llm.provider` is `anthropic`, but these definitions name OpenAI models, which Anthropic's API
does not serve:
  - subagent `reviewer`, `/opt/aimon/.aimon/agents/reviewer.md`: `model: gpt-4o`
Each request carries these names; `llm.model` does not replace them. Change the key shown on each line. Or point
`llm.baseUrl` at a gateway that serves these names. Startup continues.
```

*Review 2's reachable variant* — shipped `agent.name: default`, `provider: anthropic`, and
`/opt/aimon/.aimon/agents/explore.md` copied from the bundle to change its prompt, keeping `model: gpt-5.1`:
```
Agent model: `llm.provider` is `anthropic`, but these definitions name OpenAI models, which Anthropic's API
does not serve:
  - main agent, classpath `agents/default/agent.md`: `model.name: gpt-5.6-terra`
  - subagent `explore`, `/opt/aimon/.aimon/agents/explore.md`: `model: gpt-5.1`
Each request carries these names; `llm.model` does not replace them. Set `agent.name: default-anthropic`: it
replaces the main agent and the subagents in its bundle. Files under `/opt/aimon/.aimon/agents` load with every
agent, so `agent.name` does not change them: edit those files. Or point `llm.baseUrl` at a gateway that serves
these names. Startup continues.
```
The classpath `explore.md` is not printed, because the runtime does not read it.

*Shipped file, only `provider:` edited* (clause 3): the two entries of the acceptance case, then S-switch, then
S-host.

### 3.5 Never refusing

- **The check computes a value; the call site only prints it.**
- **The seam reads its collaborators inside its own `try`.** The call site passes:
  - the bundle;
  - a `Supplier<SubagentRegistry>` (`agentRuntime::getSubagentRegistry`);
  - a `Supplier<String>` (`() -> agentRuntime.getEnvironment().getWorkingDirectory()`).

  `agentRuntime` is non-null (`orElseThrow`, `AgentSetupFactory.java:606-607`), so neither the method reference nor
  the lambda can throw where it is written. `agentBundle.getAgent()`, both `get()` calls and every registry read
  happen inside the `try`. "Never stops startup" holds by construction.
- **On a `RuntimeException`:** `log.warn("Agent model check skipped: {}", e.getMessage(), e)` to the file, nothing
  on the terminal, startup continues. Precedent: `resolveTracingMaxPayloadChars` (`:834-842`).
  - The environment is non-null by construction (`OrcaAgentRuntime.java:101`) and `getWorkingDirectory()` is a
    getter, so a throwing working-directory supplier is not reachable today. It takes this path like any other
    collaborator.
- **Inputs that stay silent or degrade, never throw:**
  - a malformed `baseUrl` or an unexpected provider → silent;
  - a working directory that is not a path → the relative location (§3.4).
- **No opt-out key.** Every configuration the check fires on sends a name to an API that cannot serve it.

### 3.6 Alternatives rejected

| # | Alternative | Why rejected |
|---|---|---|
| R1 | Refuse startup on a mismatch | Ruled out by the maintainer. Custom or proxied names must start |
| R2 | Choose the agent by provider | Option 3, ruled out |
| R3 | Name-only rule (Gate B without Gate A) | False alarm on the documented `claude-*`-through-an-OpenAI-gateway path |
| R4 | Treat *both* vendor hosts as "not a gateway" | Half adopted as clause 3, the half with no legitimate reading. The other half would false-alarm on Anthropic's OpenAI-SDK compatibility endpoint |
| R5 | Compare the name's vendor with the vendor owning the effective host | Same answers on realistic inputs, with more states; silent on clause 3's shipped-agent case |
| R6 | Recognise names by capability-registry look-up | No vendor; misses `gpt-4o`, `gpt-4.1`, `claude-sonnet-4-20250514`; a declared gateway name would look recognised |
| R7 | Warn on any name outside the provider's own family | False for OpenAI (`chatgpt-*`, `codex-*`), and a false alarm the day either vendor ships a new family |
| R8 | Vendor attribution in `aimon-core` | Outside this run's files. The registry is "the single seam through which model knowledge reaches a provider" (`ModelCapabilityRegistry.java:9-12`) |
| R9 | Check the main agent only | Misses `default/agents/explore.md:5` |
| R10 | Check the bundle's subagent registry only | Misses `.aimon/agents` subagents, and would report a bundled subagent that a user file shadows |
| R11 | `log.warn` only / `displayError` / a new `displayWarning` | Invisible / reads as a failure while startup continues / a new channel |
| R12 | A config key to silence the warning | §3.5 |
| R13 | "The loaded agent and its subagents", with no location | The remedy has to name a file the runtime reads. In the shadowing case the user would open the bundled `explore.md` and find the wrong model |
| R14 | Provenance by comparing model strings with the bundle's definition | Labels a user copy that keeps the bundled model `BUNDLE`, which names a file the runtime no longer reads — reachable whenever a user copies a bundled subagent to change its prompt |
| R15 | Offer `agent.name` whenever it differs from the provider's bundle | Would tell a user with their own correct bundle to discard it over one bundled subagent's key |
| R16 | Keep revision 2's `AMBIGUOUS` (same name, same model, different instance → print both paths) | **The state is reachable, not hypothetical:** every user copy that keeps the bundled model is exactly it (the user layer parses its own instances, §2). Printing both paths there names a file the runtime does not read, in the case R13/R14 exist for (review 2). Its one benefit, degrading gracefully if a registry ever returns copies, is bought more cheaply by a test that fails when that happens (§6 F1) |
| R17 | Print `.aimon/agents/<name>.md` relative | The base is the jar's directory or `user.dir`, not the shell's directory, so a jar user who follows it creates a file nothing reads (review 2) |
| R18 | Put the measured 404 in the warning text | The runtime text carries no date, the vendor may change the response, and only the Anthropic direction was measured. The warning's claim ("does not serve") holds without it. The guide carries it, dated |
| R19 | Warn on `haiku`, or on aliases in general | R7 by another name: a gateway may legitimately resolve `haiku`, and §3.1's no-false-alarm argument would stop holding. The bundle defect is L-17's |
| R20 | Point the warning at the guide by path | No CLI runtime string refers to a document today (grep of `aimon-cli/src/main/java` for `"…docs/…"`: none). The yaml comment next to the switch does point there |
| R21 | Guard identity with a hand-assembled composite only (revision 2's F) | Covers the registry classes but not `AimonStackBuilder` → `OrcaAgentRuntimeFactory` handing the bundle's registry through unwrapped, which identity equally depends on (§2) |

---

## 4. Concrete changes, by file

### 4.1 `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentModelProviderCheck.java` (new)

A package-private `final class` with a private constructor. It sits beside `LlmClientFactory` and is stateless.

- **Nested enums (package-private):**
  - `Vendor { OPENAI, ANTHROPIC }`;
  - `Endpoint { VENDOR_API, WRONG_HOST, UNKNOWN }`: Gate A clauses 1-2 / clause 3 / silent;
  - `Origin { BUNDLE, OUTSIDE_BUNDLE }` — two values, one rule (§3.2).
- **Constants:**
  - `FAMILY_PREFIXES` — the javadoc cites the capability rows;
  - `OWN_API_HOST`;
  - `BUNDLE_FOR` (`default`, `default-anthropic`).
- **The user subagent directory is `StackPaths.AGENTS_DIRECTORY`, referenced, not copied.** `aimon-cli` depends on
  `aimon-bootstrap` (`build.gradle.kts:15`). `StackPaths` exists so that readers of these paths cannot drift
  (`StackPaths.java:3-9`), and this check is one more reader.
- **Static methods:**
  - `static Optional<Vendor> vendorOfModel(String modelName)`
  - `static Optional<Vendor> vendorOfProvider(String provider)`
  - `static Endpoint endpointOf(Vendor provider, String baseUrl)`
  - `static List<DeclaredModel> declaredModels(Agent agent, Optional<SubagentRegistry> bundleSubagents,
    SubagentRegistry runtimeSubagents)`
    - Entries and origins per §3.2. Origin is `bundleSubagents.flatMap(r -> r.getSubagent(name))` compared with `==`
      against the runtime's instance.
    - A null `runtimeSubagents` means the main agent only.
  - `static Optional<String> warning(LlmProviderConfig llm, String agentName, String workingDirectory,
    List<DeclaredModel> models)`
    - Both gates, the §3.4 locations (including the relative fallback) and the remedy composition.
- **`static final class DeclaredModel`** — a package-private carrier with a constructor (the `MemoryWiring`
  precedent). Fields: `boolean mainAgent`, `String subagentName` (null for the main agent), `String modelName`,
  `Origin origin` (always `BUNDLE` for the main agent). The printed location and key derive from these, `agentName`
  and `workingDirectory`.

### 4.2 `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java`

- **New package-private seam:**
  ```java
  void reportAgentModelMismatch(CliConfig config, AgentBundle agentBundle,
          Supplier<SubagentRegistry> runtimeSubagents, Supplier<String> workingDirectory,
          OutputFormatter outputFormatter)
  ```
  Everything happens inside the §3.5 `try`:
  - read `agentBundle.getAgent()`, `agentBundle.getSubagentRegistry()`, `runtimeSubagents.get()` and
    `workingDirectory.get()`;
  - `declaredModels` → `warning(config.getLlmConfig(), extractAgentName(config), …)`;
  - `log.warn(...)` + `outputFormatter.displayInfo(...)`.
- **One call in `decorate(...)`**, after `enrollMemorySubsystem` (`:621`) and before `AgentSetup.builder()` (`:623`):
  ```java
  reportAgentModelMismatch(config, agentBundle, agentRuntime::getSubagentRegistry,
          () -> agentRuntime.getEnvironment().getWorkingDirectory(), cli.outputFormatter);
  ```
- No constructor, field or teardown changes. The check owns nothing.

### 4.3 `modules/aimon-cli/src/main/resources/default-config.yaml` (comments only; values unchanged)

Style: `#` lines, `--` dashes, about 110 columns.

- **Above `provider:` (`:19`).**
  - Switching providers changes four keys together:
    - this one;
    - `apiKey`;
    - `baseUrl` — remove it for the vendor's own endpoint; the line below is OpenAI's host;
    - `agent.name` — see its comment.
  - Each agent request carries the model its agent definition names, not anything in this block.
  - Startup warns, without stopping, when a loaded definition names the other vendor's models.
- **Next to `model:` (`:22`).** This is not the agent's model when the agent's definition names one, and every
  bundled definition does. It is the model for:
  - peer memory: the dialectic engine, deriver and reconciler, plus the dreamer and its LLM judge unless
    `memory.dreamer.scorer.llm.model` is set;
  - wiki page generation;
  - the name the startup banner prints in `LLM Provider: <provider> (<model>)`;
  - the agent, only when its definition has no `model.name`.

  The `openai` provider requires it. Under `anthropic` it may be left out: wiki generation and the banner then use
  the client default `claude-sonnet-4-20250514`, but **enabling `memory` without it fails startup**.
- **Next to `agent.name` (`:130`).**
  - It selects the classpath bundle `agents/<name>/`. That bundle's `agent.md` `model.name`, and each subagent's
    `model`, are what agent requests carry.
  - `default` names OpenAI models (`gpt-5.6-terra`; its `explore` subagent `gpt-5.1`).
  - For `provider: anthropic` use `default-anthropic` (`claude-sonnet-4-5`) — **but its `explore` subagent names
    `haiku`, which is sent as written, and which Anthropic's API rejected on 2026-09-10 (backlog L-17).** This is
    the Q2 caveat. The comment next to the switch is the most-read text on the likeliest switch path, so it must
    not present the bundle as a clean switch.
  - Subagent files in `.aimon/agents/` under the CLI's working directory load with every agent.
  - No full bundle list with models, because #93 already had to correct a drifted count.

### 4.4 `docs/getting-started/aimon-core-integration-via-cli-reference.md` + `.en.md` (same commit)

- **Placement.** A new `####` under §4.1, immediately before the `llm.reasoningEffort` sub-section (ko `:256`,
  en `:264`), after the "each builder constructs…" paragraph.
- **Heading.** Its anchor is the link target for §4.8, and it follows the sibling pattern
  `<situation> — `<key>`` (`모델이 얼마나 생각할지 — `llm.reasoningEffort``):
  - ko: `#### provider 를 바꿀 때 — `agent.name` 도 함께 바꾼다` → anchor
    `provider-를-바꿀-때--agentname-도-함께-바꾼다`
  - en: `#### Switching providers — change `agent.name` too` → anchor `switching-providers--change-agentname-too`

  Both anchors were computed with `docs_tree.slug`. The double hyphen from `—` matches the existing siblings.
- **Content, in this order and identical in structure in both files:**
  1. **One paragraph on where the model comes from.**
     - `agent.name` selects the classpath bundle `agents/<name>/`.
     - Both clients send `modelConfig.getName().orElse(config.getModel())`, so a definition's `model.name`, when it
       has one, wins over `llm.model`, and every bundled definition has one.
     - The shipped `agent.name: default` names OpenAI models (`gpt-5.6-terra`, `explore` `gpt-5.1`).
     - Editing only the `llm:` block sends those names to Anthropic, which answered `gpt-5.6-terra` with HTTP 404
       `not_found_error` on 2026-09-10.
  2. **One `yaml` fence** — the switched configuration, with **no comment lines** inside the fence:
     `provider: anthropic`, `apiKey: "${ANTHROPIC_KEY}"`, no `baseUrl`, `model: claude-sonnet-4-5`, `agent.name:
     default-anthropic`. The surrounding text calls it the switched configuration, **never "working"**.
  3. **The Q2 caveat, in one sentence, directly under the fence.** For example (ko):
     *"`default-anthropic` 의 `explore` 서브에이전트는 `model: haiku` 를 적고 있고 이 이름은 별칭 해석 없이 그대로
     전송되는데, Anthropic Messages API 는 2026-09-10 에 `haiku` 에 HTTP 404 `not_found_error` 를 돌려주었다(백로그
     L-17)."* The en file carries the same sentence.
  4. **A 4-item list** of the keys that change together: `provider`, `apiKey`, `baseUrl` (remove it, or it stays on
     OpenAI's host — the shipped file sets it), `agent.name`.
  5. **One paragraph on `llm.model`.**
     - What it still reaches (§4.3), including that the banner's `LLM Provider: <provider> (<model>)` shows
       `llm.model`, not the agent's model.
     - A definition without `model.name` runs on it.
     - Under anthropic it may be left out (the client default `claude-sonnet-4-20250514` then appears in the
       banner), but `memory` needs it.
  6. **One paragraph on the startup warning.**
     - **When the shipped-agent case fires:** under `provider: anthropic`, when `baseUrl` is absent, on Anthropic's
       host, or still on OpenAI's host from the shipped file.
     - **When it is silent:** behind any other `baseUrl`, and on names neither vendor claims — **including that
       `haiku`**.
     - **Where it prints:** the terminal before the banner, and `~/.aimon/logs/aimon.log`.
     - **What each line names:** the definition's key, and its location — a bundle file as `classpath`, a user
       subagent as its absolute path under the CLI's working directory. That directory is the jar's directory, or
       `user.dir` otherwise (`modules/aimon-cli` under `./gradlew :aimon-cli:run`), and it is the banner's
       `Working Directory:` line.
     - It offers only remedies that change something, and it never stops startup.
- **No sample warning block,** because the wording would drift.
- **Translation bookkeeping.** `.en.md` frontmatter gets `source_commit: 6c53cfe`, the canonical's last commit before
  this edit. Structure matches exactly, and `check-translation-structure.py` compares:
  - one `####` heading;
  - one `yaml` fence, with zero `#` lines inside it;
  - one 4-item list, not nested;
  - no table and no quote block.

  Identifiers are not translated.
- **Not touched:** the stale `(line 690)` heading coordinates (ko `:231-233` and en `:237-240` tell readers to find
  code by method name) (D6).

### 4.5 `CHANGELOG.md` `[Unreleased]`

A new entry at the top of `[Unreleased]`, above #88's:
`### CLI: startup says so when the agent's model belongs to the other provider (#92)`. Its bullets cover:

- **What fires**, and that each entry names its location (a `classpath` bundle path, or the absolute `.aimon/agents`
  path) and its key.
- **When it stays silent, and why:** behind a gateway `baseUrl`, and on names neither vendor claims such as `haiku`.
- **Remedies:** the `agent.name` remedy is offered only where it changes something.
- **Never stops startup**, and prints to the terminal before the banner and to `~/.aimon/logs/aimon.log`.
- **Documentation:** the new yaml comments and guide section, with their corrections:
  - `llm.model` also reaches wiki generation and the banner;
  - `memory` needs `llm.model` under anthropic;
  - `default-anthropic`'s `explore` sends `haiku`, which Anthropic answered with 404 on 2026-09-10 — registered as
    backlog `L-17`.

No `rename-maps.md` entry.

### 4.6 Tests (new) — see §6

Both new files go in `modules/aimon-cli/src/test/java/at/aimon/cli/factory/`:

- `AgentModelProviderCheckTest.java` — A, B, C, D;
- `AgentSetupFactoryAgentModelCheckTest.java` — E, F.

### 4.7 `docs/backlog/llm-config-surface-open-items.md` + `docs/backlog/README.md` (Q3: `L-17`)

**Placement and heading.** A new item after `L-16`, before `## 관련 문서` (`:918`):
`## L-17 — 번들 서브에이전트의 `model: haiku` 는 별칭으로 풀리지 않고 그대로 나가며, Anthropic 은 그 이름에 404 를 준다`.
It is ID-first with `—` and no bold state word, so the checker reads it as one open item (as `L-16`'s style does).

**Body** — the four parts `README.md` requires (무엇을 · 왜 · 어디 · 언제 다시 볼까), shaped like L-15/L-16:

- **Source line (italic).** 2026-09-11 등록, 출처는 #92 (the CLI provider-switch work). Fixing the bundles' `haiku`
  is **outside that work's scope**, by #92's decision. It sits in this register rather than a new one for the same
  reason L-16 gives for itself.
- **무엇을.** Make the three bundled `explore` subagents carry a model name the configured provider actually serves.
- **왜 (observable).**
  - Under `provider: anthropic` + `agent.name: default-anthropic` — the pairing #92's comment and guide point to —
    the main agent runs on `claude-sonnet-4-5`, but a task delegated to `explore` carries `model: haiku`.
  - The Anthropic Messages API answered that name with HTTP 404 `not_found_error` on 2026-09-10
    (`req_011CevaKnvSxstq6wuuWgXd8`).
  - #92's startup warning does not catch it, on purpose: `haiku` is neither vendor's family, and warning on such
    names would false-alarm on a gateway that resolves the alias.
- **Rule six — how "nothing resolves the alias" was counted.**
  - `SubagentLlmDefaults.resolveModel` uses the frontmatter string as the model name (`:68-69`).
  - `grep -rn haiku` over `modules/*/src/main/java` finds only javadoc and comments, plus full-name prefixes
    `claude-3-5-haiku` / `claude-3-haiku` (the §2 citations).
  - Over `src/main/resources` it finds only the three `explore.md`.
- **The other two bundles.** `default-openai` and `ops-agent` also name `haiku` for `explore`. Their main agents are
  `gpt-5.1`, so that `haiku` goes to OpenAI, whose answer was not measured.
- **어디.** Dated 2026-09-11, `a1236c8`:
  - `modules/aimon-cli/src/main/resources/agents/{default-anthropic,default-openai,ops-agent}/agents/explore.md:5`;
  - the resolution site `SubagentLlmDefaults.java:61-75`;
  - the caveats this item's closing must update in the same edit: the `agent.name` comment in `default-config.yaml`
    and the CLI guide's "provider 를 바꿀 때" passage (ko + en).
- **Severity (rule three).** Measured: one request with that name gets a 404. Not run: what the `Task` tool hands
  back to the main agent after that, and what the model does next.
- **Prescription not applied (rule five).** Three shapes are visible, and none is chosen:
  - put a full vendor name in each bundle's `model:`, not measured for these bundles;
  - drop `model:` so the subagent inherits the main agent's name (`:70-71`);
  - resolve aliases per provider in core — which touches the same decision as the `Task` tool's `model` description
    (`TaskTool.java:345`, D5).
- **언제 다시 볼까.** When a bundled `explore.md` or `SubagentLlmDefaults` is next touched. It is small enough to
  take now.
- **관련 문서.** One bullet linking the guide passage by its anchor (§4.4) as the place L-17's caveat lives.

**Counts — recounted from the item headings, not by adding one.** At `a1236c8` the headings read 16 items (12 open,
4 closed), which the checker confirms. With `L-17`:

- the title becomes `등록 항목 17건 (열림 13 · 닫힘 4)`;
- the index row at `docs/backlog/README.md:347` becomes `| 17 | 13 | 4 | 0 |`.

The build counts from the headings first and then runs the checker. Green means both copies agree with the
headings; it does not make the numbers true ("일치는 검증이 아니라 복제다").

### 4.8 `docs/README.md:49` + `docs/README.en.md:57` (link target only; same commit as each other, not before the guide)

- **ko:** `(getting-started/aimon-core-integration-via-cli-reference.md)` →
  `(getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다)`.
- **en:** `(getting-started/aimon-core-integration-via-cli-reference.en.md)` →
  `(getting-started/aimon-core-integration-via-cli-reference.en.md#switching-providers--change-agentname-too)`.
- **Nothing else changes.** Link texts, the second guide links (ko `:95`, en `:103`) and the frontmatter all stay.
  `docs/README.en.md` keeps `source_commit: 8f71212` (§9 Q4).
- `check-doc-links.py` must be green; it checks the anchor.

### 4.9 `README.md:174` (one comment)

`provider: "openai"          # or "anthropic"` becomes, for example:
`provider: "openai"          # "anthropic" also changes baseUrl and agent.name (CLI guide, docs/getting-started)`.

Constraints on the wording:

- It names both non-obvious keys.
- It names **no bundle.** Naming `default-anthropic` here without the `haiku` caveat would present it as a clean
  switch, which the Resume forbids.
- It points at the guide by location; a comment inside a fence cannot link.
- Column alignment with the neighbouring comments is kept.

Nothing else in `README.md` changes. There is no `README.ko.md` twin (§2). The stale `model: "gpt-4o-mini"` at `:177`
is D11.

### 4.10 Run records (`$RUN_DIR/build/`)

- **`measurements.md`:** the Q1 table copied from `design/q1-live-probe.md`, with its time and request IDs. No new
  probe, and no key read. Plus the measured `checkAll` numbers.
- **`deviations.md`:** D1, D3-D12 (§8). D2 is registered as L-17.

Suggested commit split. The build decides, within the same-commit constraints above:

1. `fix(cli): warn at startup when the agent's model belongs to the other provider (#92)` — Java, tests, yaml
   comments, `CHANGELOG.md`.
2. `docs(cli): …` — guide ko + en, the `docs/README` pair, `README.md:174`, `L-17` + index row.

---

## 5. Data and interface shapes that change

**Code.** One new package-private class and one new package-private method on `AgentSetupFactory`:

```java
final class AgentModelProviderCheck {
    enum Vendor { OPENAI, ANTHROPIC }
    enum Endpoint { VENDOR_API, WRONG_HOST, UNKNOWN }
    enum Origin { BUNDLE, OUTSIDE_BUNDLE }

    static final class DeclaredModel { /* mainAgent, subagentName, modelName, origin */ }

    static Optional<Vendor> vendorOfModel(String modelName) { … }
    static Optional<Vendor> vendorOfProvider(String provider) { … }
    static Endpoint endpointOf(Vendor provider, String baseUrl) { … }
    static List<DeclaredModel> declaredModels(Agent agent, Optional<SubagentRegistry> bundleSubagents,
            SubagentRegistry runtimeSubagents) { … }
    static Optional<String> warning(LlmProviderConfig llm, String agentName, String workingDirectory,
            List<DeclaredModel> models) { … }
}

// AgentSetupFactory
void reportAgentModelMismatch(CliConfig config, AgentBundle agentBundle,
        Supplier<SubagentRegistry> runtimeSubagents, Supplier<String> workingDirectory,
        OutputFormatter outputFormatter) { … }
```

- **Compared with revision 2:**
  - `Origin` loses `AMBIGUOUS`;
  - `warning` and the seam gain `workingDirectory`;
  - the check references `StackPaths.AGENTS_DIRECTORY`, the CLI's first import from `at.aimon.bootstrap.assemble`.
- **No public API change.** No configuration key is added, removed or renamed. No wire, DDL, frozen-name or
  `aimon-core` change.
- **Behaviour change (CHANGELOG).** Under the two gates, the check adds one startup message on stdout and one WARN in
  the log file. With no entries, the output is byte-for-byte what it was.

**Docs.**

- Two new anchors: ko `provider-를-바꿀-때--agentname-도-함께-바꾼다`, en `switching-providers--change-agentname-too`.
- One new register item. Title and index-row counts change from 16/12/4/0 to 17/13/4/0.

---

## 6. Test strategy

**Style.** JUnit 5, AssertJ, `@DisplayName`, `@Nested`; `@ParameterizedTest` is already used in this module
(`LlmClientFactoryTest.java:533`).

- Stdout is captured as `OutputFormatterTest.java:35-54` does (`colorOutput=false`).
- Message tests assert **fragments** (paths, keys, the §3.4 fragment column, and their absence), never whole strings.
- Defaults unless stated: `baseUrl` unset, `workingDirectory` `/work`.

**A. Classification (`vendorOfModel`).**

- `claude-sonnet-4-5`, `CLAUDE-OPUS-5` → ANTHROPIC.
- `gpt-5.6-terra`, `gpt-5.1`, `gpt-4o`, `o1`, `o3-mini`, `o4-mini` → OPENAI.
- `haiku`, `prod-assistant`, `llama-3.1-70b`, `""`, `null` → empty.
- **Grounding test:** every literal row key named in `builderWithDefaults()` resolves in `withDefaults()` and
  classifies to its block's vendor. The javadoc states the limit.

**B. Gate A (`endpointOf`).**

| Provider | `baseUrl` | Expected |
|---|---|---|
| openai | null, `""`, `https://api.openai.com/v1`, `HTTPS://API.OPENAI.COM` | `VENDOR_API` |
| openai | `https://gw.internal/v1`, `https://x.openai.azure.com`, **`https://api.anthropic.com/v1`** | `UNKNOWN` |
| anthropic | null, `https://api.anthropic.com` | `VENDOR_API` |
| anthropic | **`https://api.openai.com/v1`** | `WRONG_HOST` |
| anthropic | `https://gw.internal` | `UNKNOWN` |
| either | `"not a url ::"` | `UNKNOWN`, no exception |

**C. Message and remedies (`warning` over hand-built `DeclaredModel` lists).**

| # | Input | Must contain | Must **not** contain |
|---|---|---|---|
| C1 | anthropic, `default`: main `gpt-5.6-terra`; `explore` `gpt-5.1` `BUNDLE` (acceptance) | `classpath`, `agents/default/agent.md`, `model.name: gpt-5.6-terra`, `agents/default/agents/explore.md`, `model: gpt-5.1`, `` `agent.name: default-anthropic` ``, `llm.model`, ``Or point `llm.baseUrl` `` | `.aimon/agents` |
| C2 | anthropic, `default-anthropic`: main `claude-sonnet-4-5`; `reviewer` `gpt-4o` `OUTSIDE_BUNDLE` (review 1) | `/work/.aimon/agents/reviewer.md`, `model: gpt-4o`, `Change the key shown` | `agent.name:`, `model.name`, `claude-sonnet-4-5`, `classpath`, `where that bundle is built` |
| C3 | anthropic, `default-anthropic`: `explore` `gpt-5.1` `OUTSIDE_BUNDLE` (shadow) | `/work/.aimon/agents/explore.md` | `agents/default-anthropic/agents/explore.md`, `agent.name:` |
| C4 | anthropic, `my-claude`: main `claude-sonnet-4-5`; `helper` `gpt-5.1` `BUNDLE` | `classpath`, `agents/my-claude/agents/helper.md`, `where that bundle is built`, `without rebuilding`, `/work/.aimon/agents` | `agent.name:` |
| C5 | anthropic, `default`: main `gpt-5.6-terra`; `reviewer` `gpt-4o` `OUTSIDE_BUNDLE` | `` `agent.name: default-anthropic` ``, `does not change them`, `/work/.aimon/agents` | `without rebuilding`, `Change the key shown` |
| C6 | anthropic, `default-anthropic`: main `gpt-5.1` (an edited bundle) | `agents/default-anthropic/agent.md`, `model.name: gpt-5.1`, `where that bundle is built` | `agent.name:`, `without rebuilding` |
| C7 | anthropic, `default`, `baseUrl: https://api.openai.com/v1`: main `gpt-5.6-terra` | `` `agent.name: default-anthropic` ``, `is OpenAI's host` | ``Or point `llm.baseUrl` `` |
| C8 | anthropic, `default-anthropic`: `reviewer` `gpt-4o` `OUTSIDE_BUNDLE`; `workingDirectory` = null, `""`, and a string containing U+0000 (NUL), which `Path.of` rejects with `InvalidPathException` on every platform. A relative string such as `"bad dir"` is still a path and must not stand in for "not a path" | `` `.aimon/agents/reviewer.md` `` (opening backtick immediately before `.aimon`, i.e. relative); no exception | — |
| C9 | openai, `default-anthropic`, `baseUrl: https://api.openai.com/v1`: main `claude-sonnet-4-5` | `` `agent.name: default` `` (closing backtick included) | `` `agent.name: default-anthropic` `` |

**Silent (`Optional.empty()`):**

- anthropic + `claude-sonnet-4-5` + `haiku`;
- openai + `gpt-5.6-terra`;
- openai + gateway + `claude-sonnet-4-5`;
- openai + `api.anthropic.com` + `claude-sonnet-4-5`;
- `prod-assistant` under either provider.

The provider string `ANTHROPIC` behaves as `anthropic`.

C8 replaces revision 2's `AMBIGUOUS` case, which no longer exists.

**D. Shipped bundles through the real loader.** Uses `new AdaptiveAgentBundleLoader("agents")` on the test
classpath. The bundle's own registry is passed as both `bundleSubagents` and `runtimeSubagents`, which is the
no-user-layer case, so every subagent is `BUNDLE`.

| Bundle | anthropic | openai |
|---|---|---|
| `default` | **fires** (main + `explore`), `` `agent.name: default-anthropic` `` | silent |
| `default-anthropic` | silent (`haiku` unclaimed — the L-17 case, silent by rule) | **fires** (main), `` `agent.name: default` `` |
| `default-openai` | **fires**, `` `agent.name: default-anthropic` `` | silent |
| `terra` | **fires**, `` `agent.name: default-anthropic` `` | silent |
| `ops-agent` | **fires**, `` `agent.name: default-anthropic` `` | silent |

**Remedy test:** for each provider, the bundle S-switch names loads and yields **no entries** for that provider
(§3.4's narrowed claim).

**E. Factory seam (`reportAgentModelMismatch`, stub bundle and stub registries).**

| # | Input | Expect |
|---|---|---|
| E1 | anthropic; main `gpt-5.6-terra`; empty runtime registry | stdout contains `Agent model:` and `model.name: gpt-5.6-terra` |
| E2 | anthropic; main `claude-sonnet-4-5`; empty runtime registry | stdout empty |
| E3 | the runtime-registry supplier throws | no exception (`assertThatCode(...).doesNotThrowAnyException()`); stdout empty |
| E4 | anthropic; main `claude-sonnet-4-5`; the **bundle registry's `getSubagent` throws**; the runtime registry holds `explore` on `gpt-5.1` | no exception; stdout empty |
| E4′ | E4's inputs, but the bundle registry's `getSubagent` returns `Optional.empty()` | stdout contains `/work/.aimon/agents/explore.md` — proves E4's throw sits on the path the check takes |
| E5 | the runtime registry's `getAllSubagents()` throws | no exception; stdout empty |
| E6 | the runtime-registry supplier returns null; main `gpt-5.6-terra` | no exception; stdout has the main-agent line only |
| E7 | the working-directory supplier throws | no exception; stdout empty (§3.5) |

The call in `decorate()` passes a local variable, a method reference and a lambda on a non-null receiver (§3.5), so
nothing at the call site is left to test. That is the design, not a gap.

**F. Provenance through the real stack wiring.**

*Setup:*

- `fs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()))`, then `fs.initialize()`. Any user files
  go under `tempDir/.aimon/agents/` **before** the build.
- `bundle = new AdaptiveAgentBundleLoader("agents").load(<name>)`.
- In try-with-resources:
  ```java
  AimonStack stack = AimonStackBuilder.build(AimonStackSpec.builder()
          .llm(LlmSpec.of(STUB)).fileSystem(FileSystemSpec.supplied(fs))
          .agent(AgentSpec.builder().bundle(bundle).build()).build());
  ```
- `runtime = stack.runtime(stack.primaryRuntimeId()).orElseThrow()`.
- `STUB` is an anonymous `LlmClient`, as in `AimonStackBuilderTest.java:66`.

*Why this setup.* These are the three spec inputs `AgentSetupFactory.create` passes (`:553-557`), and the build runs
the whole chain identity rests on: `AimonStackBuilder` → `StackAgentRuntimeProvisioner` →
`OrcaAgentRuntimeFactory.doCreate` → `buildCompositeSubagentRegistry`. On the test classpath the bundle resolves as
`file://`, so its own registry is the two-layer composite of `AdaptiveAgentBundleLoader.java:196-205`, which is
covered too.

| # | Bundle + user files | Expect |
|---|---|---|
| F1 | `default-anthropic`, none | `explore` is `BUNDLE`. **This is the guard (§3.2):** a registry that returns copies, or wiring that wraps or re-parses the bundle's registry, turns it `OUTSIDE_BUNDLE` and fails here. The javadoc names the facts identity rests on (§2) |
| F2 | `default-anthropic` + `reviewer.md` (`model: gpt-4o`) | `reviewer` is `OUTSIDE_BUNDLE`; `explore` is still `BUNDLE` |
| F3 | `default-anthropic` + `explore.md` (`model: gpt-5.1`) | `explore` is `OUTSIDE_BUNDLE` |
| F4 | `default-anthropic` + `explore.md` keeping `model: haiku` | `explore` is `OUTSIDE_BUNDLE` — the same row as F3 under §3.2's one rule; this is the input on which revision 2's table and test disagreed |
| F5 | `default` + `explore.md` keeping `model: gpt-5.1` (review 2's reachable variant, end to end) | See below |

*F5 in detail.* The call is `reportAgentModelMismatch` with `provider: anthropic`, the loaded bundle,
`runtime::getSubagentRegistry` and `() -> runtime.getEnvironment().getWorkingDirectory()`, with stdout captured.

- **Contains:**
  - `agents/default/agent.md`;
  - `` `agent.name: default-anthropic` ``;
  - `Path.of(runtime.getEnvironment().getWorkingDirectory()).resolve(".aimon/agents/explore.md")`;
  - `does not change them`.
- **Does not contain:** `agents/default/agents/explore.md`.

*Why F5's expected path comes from the runtime rather than `@TempDir`.* It keeps the assertion about which file is
named. It does not depend on whether `LocalFileSystemConfig` canonicalises the path, which matters where `@TempDir`
sits under a symlink (macOS `/var` → `/private/var`). F1-F4 call `declaredModels(bundle.getAgent(),
bundle.getSubagentRegistry(), runtime.getSubagentRegistry())` and assert origins.

**G. Gate and docs.**

- `./gradlew format`, then `./gradlew checkAll`, reporting measured tests run / failures / skips.
- Before and after the doc edits:
  - `python3 scripts/check-doc-links.py`;
  - `python3 scripts/check-backlog-registers.py` and `python3 scripts/check-backlog-registers.py --self-test`;
  - `python3 scripts/check-translation-staleness.py`;
  - `python3 scripts/check-translation-structure.py`.
- Baseline: at `a1236c8` the backlog checker reports `llm-config-surface-open-items.md 16 (열림 12 · 닫힘 4 · 해소 0)`.
  The other three were clean at `ade5978` (review 2); the build re-runs all four before editing, to have its own
  baseline.

---

## 7. Failure modes and handling

| Failure mode | Handling |
|---|---|
| The check throws (registry, parsing, a supplier, a bug) | Caught inside the seam, including the collaborator reads; `log.warn` to file; nothing on the terminal; startup continues (§3.5) |
| `llm.baseUrl` malformed or without a host | `UNKNOWN` → silent |
| Unexpected provider string | Cannot reach the check (`LlmClientFactory.validateProvider`, `:53-58`); silent by construction |
| Working directory null, blank or not a path | Relative `.aimon/agents/<name>.md` printed; the warning still prints (§3.4) |
| A subagent from `.aimon/agents` — new, shadowing, or a copy that keeps the bundled model | `OUTSIDE_BUNDLE`, printed at the resolved path. No S-switch for it; S-outside when S-switch is offered for the main agent |
| A registry class or the stack wiring starts returning copies, or wraps the bundle's registry | Bundled subagents would print at a `.aimon/agents` path that does not exist, with S-outside. Model and key stay right, and startup is unaffected. **§6 F1 fails first** (R16, R21) |
| Main agent right, bundled subagent wrong | S-edit with the classpath path, S-built and S-override; no bundle switch (R15) |
| A jar user reads a bundle path | Labelled `classpath`. S-built says where it is edited; S-override gives the no-rebuild path for a subagent |
| Shipped `baseUrl` left after switching to anthropic | Clause 3 → fires with S-host when a checked model is OpenAI-family; silent with a `claude-*` agent (D3) |
| `provider: openai` with a host on `api.anthropic.com` | Silent by design (§3.1) |
| `default-anthropic` under anthropic: `explore` sends `haiku` | Silent by rule (§3.1, R19). Caveat in the yaml comment and the guide; L-17 |
| A definition without `model.name` | Not checked (§3.2) |
| Task-tool runtime model override | Not checkable at startup |
| Repeated warning on every start | Intended; the text names the fix per entry |
| `colorOutput: false`, piped or scripted runs | Plain text, like the memory lines |

---

## 8. Findings outside the diff — for `build/deviations.md` and the handoff

| ID | Finding | Evidence | Disposition |
|---|---|---|---|
| D1 | Three bundles share `name: default-agent`, so the prompt (`AimonCli.java:106-107`) and `AgentRuntimeId` cannot tell them apart | `agents/{default,default-openai,default-anthropic}/agent.md:3` | Follow-up only. Option 1 and the warning use the configured `agent.name`, so both are truthful without changing it |
| D2 | Bundled `explore` subagents name `model: haiku`, nothing resolves the alias, and Anthropic answers it with 404 | §2; `design/q1-live-probe.md` | **Registered as `L-17`** (§4.7); bundles unchanged, per the Resume |
| D3 | No provider/baseUrl check beyond clause 3: a `claude-*` agent on `api.openai.com`, and `provider: openai` + `api.anthropic.com` + an OpenAI-family agent, are silent and fail at the first request | `default-config.yaml:20`, `AnthropicLlmClient.java:187-189` | Follow-up |
| D4 | A model-less subagent under a definition without `model.name` sends the literal `gpt-4` | `SubagentLlmDefaults.java:21`, `:70-71` | Follow-up (core) |
| D5 | The Task tool's `model` description suggests `sonnet, gpt-4.1, gpt-4.1-nano` whatever the provider | `TaskTool.java:345` | Follow-up (core); related to L-17's third prescription shape |
| D6 | Stale line numbers in the guide's §4.x headings | guide ko `:227`, `:235` | Not touched |
| D7 | The issue's "the parser requires `model.name`" is inaccurate | `MarkdownAgentDefinitionParser.java:150-160` | Correction recorded |
| D8 | `TASK.md`'s "`llm.model` reaches memory only" misses wiki generation | `AgentSetupFactory.java:1351`, `LlmWikiPageGenerator.java:219` | Comment and guide say the true thing |
| D9 | Under anthropic, `memory:` without `llm.model` fails startup with `Unexpected error: llmModelName cannot be null`, a message that names no configuration key | `LlmDialecticEngine.java:74` (and the four siblings in §2), `AgentSetupFactory.java:522-524`, `AimonCli.java:129-131` | Follow-up; documented, not fixed |
| D10 | The REPL banner prints `llm.model` as the provider's model. With the issue's reproduction it reads `LLM Provider: anthropic (claude-sonnet-4-5)` while every agent request carries `gpt-5.6-terra` | `ReplSession.java:258-259`; `AnthropicLlmClient.java:762-764`, `OpenAILlmClient.java:589-591` | Follow-up; the yaml comment and guide say what the parenthesised name is |
| D11 | `README.md`'s Configuration snippet still shows `model: "gpt-4o-mini"` | `README.md:177` | Not touched, per the Resume |
| D12 | The CLI roots its file system, and so `.aimon/agents`, at the jar's directory (else `user.dir`), not the shell's working directory | `AgentSetupFactory.java:878-883`, `:1366-1383` | Existing behaviour. The guide and the warning's resolved path now make it visible; no follow-up proposed |

---

## 9. Open questions

**Settled by the Resume (recorded, not open):**

- **Q1 — live probe.** Run by the launcher on 2026-09-10 (§2). It is used in the guide with its date (§4.4) and in
  L-17, and kept out of the warning (R18). This run does not repeat it or read a key.
- **Q2 — the `haiku` caveat.** Yes: one sentence in the guide, stated with the measured result and its date (§4.4
  item 3). The yaml `agent.name` comment carries it too (§4.3), because that comment otherwise presents the bundle
  as the switch.
- **Q3 — backlog.** Yes: `L-17` (§4.7). D1 and D3-D12 go to `deviations.md` and the handoff.

**Still open, for review:**

- **Q4 — `docs/README.en.md` `source_commit` when retargeting.** The documentation guide asks that a translation's
  body and `source_commit` change together. Recommendation: **leave `8f71212`.**
  - The Resume allows only the link change in those two files.
  - `check-translation-staleness.py` skips a commit that touches both files.
  - #90's `0335838` touched both and left it too.

  The alternative is to set `0335838`. The guide's `.en.md` does get a new `source_commit` (§4.4), because this run
  rewrites its content.

**Unverified, stated rather than assumed:**

- **U1** — OpenAI's answer to a `claude-*` name or to `haiku` was not measured, and no text claims it.
- **U2** — `claude-sonnet-4-5` on Anthropic was not probed by this run. The guide's example claims only that the
  main agent then names an Anthropic model; the capability table's 2026-09-10 census (§2) is supporting evidence,
  not this run's measurement.
- **U3** — What the `Task` tool returns to the main agent after a subagent's 404 was not run (L-17's severity note).
- **U4** — The retargeted anchors are checked by `check-doc-links.py`, whose slug matches GitHub's. The site's
  slugify is configured to match (`mkdocs.yml:111-115`), but the site was not built in design.

---

## 10. After the build — departures, corrections, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 3 (PASS, no blocking findings, two non-blocking notes), and it is not edited to look prescient. Three sources
feed this section: the run's `build/deviations.md`, review 3's notes, and what was measured while building.*

**Nothing in §3 changed shape.** Both gates, the identity rule, the printed locations, every remedy sentence with its
condition, and the seam's never-refusing `try` were built as written. What departed is the documentation's list of
keys, how many findings went to the backlog, and a handful of choices the body left open.

### 10.1 Where the build departed from the body

- **DV-1 — five keys change together, not four** (review 3, note 1). §4.3 and §4.4 item 4 list `provider`, `apiKey`,
  `baseUrl` and `agent.name`. Review 3 showed that a reader who follows that list keeps the shipped `model: "gpt-5.1"`
  under `provider: anthropic`: startup succeeds, the banner shows `gpt-5.1`, and with `memory` on, the memory
  components send `gpt-5.1` to Anthropic. The comment above `provider:` and the guide's list, in both files, now add
  `model`, and the `model:` comment ends by saying that an OpenAI name left there is what those components send to
  Anthropic. The guide's list is therefore five items; ko and en still match on every axis.
- **DV-2 — four more backlog items.** §4.10 and §9 Q3 keep D1 and D3–D12 out of the backlog. The build was asked to
  promote every finding whose consequences reach beyond this change, so four went to
  [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) (§10.3). The register reads
  21 items (17 open · 4 closed), not §4.7's 17 / 13 / 4 / 0.
- **DV-3 — the dated parenthetical under the backlog index** gains the #92 step, as #81, #82, #83 and #89 each added
  theirs; §4.7 named only the row.
- **DV-4 — this file.** The body did not place itself. It lands in `llm/` beside the other CLI-surface designs about
  which model names a provider serves, and is indexed in [`../README.md`](../README.md). That README's §3.2–§3.3 ask a
  design doc to carry no revision notes and no `file:line` in its body; this body keeps both, on the precedent of
  [`model-capability-binding-round-trip.md`](model-capability-binding-round-trip.md) and
  [`thinking-reporting-and-dialect-records.md`](thinking-reporting-and-dialect-records.md): an approved body stays
  byte-exact, and the departures go at the end.
- **DV-5 — the CHANGELOG heading** carries no "(#92)". No heading in that file carries an issue number, so it sits in
  the first bullet, as it does everywhere else there.
- **DV-6 — `agents/` comes from the loader's constant.** §4.2 promises no field changes;
  `AgentSetupFactory.DEFAULT_AGENT_BUNDLE_BASE_PATH` went from `private` to package-private, so the printed classpath
  root is the one that loader reads rather than a second copy of the literal. No behaviour changed.
- **DV-7 — choices the body left open.**
  - `DeclaredModel` gained `mainAgent(...)` and `subagent(...)` factories.
  - A blank main-agent `model.name` is skipped like a blank subagent `model`. There is no observable difference, since
    a blank name never classifies.
  - The header prints the provider lowercased, which is the value `LlmClientFactory` resolves.
  - A subagent name that is not a valid path segment prints by concatenation, the same fallback §3.4 gives a bad
    working directory.
  - `vendorOfProvider` does not trim, like `LlmClientFactory.validateProvider`.
- **DV-8 — tests beyond §6.**
  - E1–E3 also assert the log-file half of §3.3's pair, through a `ListAppender` on the factory's logger.
  - C8 adds a blank working directory.
  - A, B and D add cases for the provider string, an unparseable `baseUrl`, "no `.aimon/agents` for the shipped
    default", and "`haiku` is silent by rule".
- **DV-9 — `README.md:174` keeps §4.9's two keys.** DV-1's point applies there too, but that snippet is a short
  sample that points at the guide, where the five-key list lives.
- **DV-10 — one test file outside §4's list.** The guide names the log file as §4.4 item 6 asks
  (`~/.aimon/logs/aimon.log`), and `aimon-spring-boot-starter`'s `AimonDocumentedPropertiesTest`, which scans the
  guides for `aimon.*` property references, read `aimon.log` as a property that does not exist and failed the gate.
  Its `EXEMPT_REFERENCES` already exempts one filename exactly (`aimon.yaml`); `aimon.log` joins it the same way.
  Rewording the guide to dodge the scan would have dropped the path §4.4 asks for.

### 10.2 Where the body is wrong

- **C-1 — §2's `haiku` census missed hits.** The "Subagent model resolution" row lists comments plus
  `claude-3-5-haiku` / `claude-3-haiku`. The same grep at `a1236c8` also finds:
  - `claude-haiku-4` (`InMemoryModelContextWindowRegistry.java:43`, `InMemoryModelPriceTable.java:65`);
  - `claude-haiku-4-5` (`InMemoryModelCapabilityRegistry.java:65`, `:336`, `:357`);
  - a javadoc example in `LlmRerankSearchStrategy.java:80`.

  The conclusion stands: every hit is a comment or a full `claude-*haiku*` name, and nothing maps the bare alias.
  L-17 states the corrected count.
- **C-2 — D10 quotes the banner in the wrong case.** Measured (§10.4), the reproduction's banner reads
  `LLM Provider: Anthropic (claude-sonnet-4-5)`. It prints the client's `getProviderName()`, not `llm.provider`.
- **C-3 — §4.4 item 4 and its structure note say four items;** there are five (DV-1).
- **C-4 — §4.7 and §5 give 17 / 13 / 4 / 0;** the register is 21 / 17 / 4 / 0 (DV-2).
- **C-5 — §4.10 and §9 Q3 route D1 and D3–D12 away from the backlog;** four of them went there (§10.3).

### 10.3 The §8 findings, and which went to the backlog

The backlog items are in [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md); each
names its D number and links back here.

| Finding | Now | Why |
|---|---|---|
| D1 — three bundles share `name: default-agent` | backlog `L-21` | The REPL prompt and the runtime id cannot tell the bundles apart, whatever this change says |
| D2 — the bundled `explore` subagents send `haiku` | backlog `L-17` | As §4.7 planned |
| D3 — no provider/host check beyond clause 3 | here | The accepted false negatives of this check's own Gate A (§3.1); what they cost is this feature's coverage |
| D4 — `gpt-4` for a model-less subagent under a nameless definition | backlog `L-20` | A core default that sends a name no provider was asked about |
| D5 — the `Task` tool's `model` description | backlog `L-20` | The same decision as D4, and as L-17's third prescription shape |
| D6 — stale line numbers in the guide's §4.x headings | here | The guide already tells readers to find code by method name |
| D7 — "the parser requires `model.name`" | here | A correction; no work follows |
| D8 — "`llm.model` reaches memory only" | here | A correction; the comment and the guide say the true thing |
| D9 — `memory` under anthropic without `llm.model` | backlog `L-18` | A startup failure that names no configuration key, now measured (§10.4) |
| D10 — the banner prints `llm.model` | backlog `L-19` | Misleading beyond a provider switch: as shipped, the banner shows `gpt-5.1` while requests carry `gpt-5.6-terra` |
| D11 — `README.md:177` shows `model: "gpt-4o-mini"` | here | No observable effect beyond the name the banner prints, and the task asked for it to stay |
| D12 — the CLI's working directory is the jar's directory | here | Existing behaviour that the guide and the warning now show; no follow-up proposed |

**Open questions.**

- Q1–Q3 were settled before review, and they stand.
- Q4 was agreed by review 3: `docs/README.en.md` keeps `source_commit: 8f71212`, and the staleness check reports
  every translation up to date.
- U1–U3 stay open and local to this change. U3 is also L-17's severity note.
- U4 is answered. `mkdocs build --strict` exited 0 on 2026-09-11, and the built pages carry both retargeted anchors
  (`provider-를-바꿀-때--agentname-도-함께-바꾼다` and `switching-providers--change-agentname-too`), the same slugs
  `check-doc-links.py` computes.

### 10.4 What was measured

**Q1, the launcher's probe** — 2026-09-10T22:49:27Z, `POST https://api.anthropic.com/v1/messages`.
- Request: `anthropic-version: 2023-06-01`, `max_tokens: 1`, and one user message.
- The key was handed to curl on stdin and never printed. A rejected request is not billed.

| model | HTTP | error.type | error.message | request_id |
|---|---|---|---|---|
| `gpt-5.6-terra` | 404 | `not_found_error` | `model: gpt-5.6-terra` | `req_011CevaKm3aqiNGUuWVjY3nY` |
| `haiku` | 404 | `not_found_error` | `model: haiku` | `req_011CevaKnvSxstq6wuuWgXd8` |

**The acceptance case, end to end** — 2026-09-11, `./gradlew :aimon-cli:run`.
- Configuration: the issue's reproduction (`provider: anthropic`, `model: claude-sonnet-4-5`, `agent.name: default`,
  no `baseUrl`), with a dummy API key.
- No input was given, so no request was sent.

The terminal showed the lines below. The ASCII-art banner is left out, and the working directory is abbreviated.

```text
Agent model: `llm.provider` is `anthropic`, but these definitions name OpenAI models, which Anthropic's API does not serve:
  - main agent, classpath `agents/default/agent.md`: `model.name: gpt-5.6-terra`
  - subagent `explore`, classpath `agents/default/agents/explore.md`: `model: gpt-5.1`
Each request carries these names; `llm.model` does not replace them. Set `agent.name: default-anthropic`: it replaces the main agent and the subagents in its bundle. Or point `llm.baseUrl` at a gateway that serves these names. Startup continues.
Aimon CLI - Interactive AI Agent
Type '/help' for commands, '/quit' to exit
Working Directory: <worktree>/modules/aimon-cli
LLM Provider: Anthropic (claude-sonnet-4-5)
Available tools: 17 tools(s)
Available commands: 12 command(s)
Registered subagents: 1 subagent(s)
Available skills: 3 skill(s)
default-agent> Goodbye!
```

- The same message reached `~/.aimon/logs/aimon.log` as a `WARN` from `AgentSetupFactory` on the `main` thread.
- The REPL started, read end of input, and the process exited 0.
- The last two lines above are also L-19 and L-21 as a user sees them: the banner shows `llm.model`, and the prompt
  says `default-agent`.

**D9, end to end** — `provider: anthropic`, a dummy key, no `llm.model`, `agent.name: default-anthropic`, and
`memory` on the in-memory backend. The process exited 1 after printing:

```text
Peer memory enabled (in-memory backend, non-durable): workspace=ws-probe peer=peer-probe
Unexpected error: llmModelName cannot be null
```

**The new tests are not vacuous.** There are 77 new tests: A 22, B 13, C 15, D 14, E 8 and F 5. Two mutations of
`AgentModelProviderCheck` were each run against both test classes, and the source was restored byte-for-byte after
each:

| Mutation | Tests that went red |
|---|---|
| The bundle registry's look-up hands back a copy of each instance — what F1 guards | F1, F2, and D's "the shipped default under anthropic names the main agent and its explore subagent" (3 of 77) |
| `agent.name` is offered even when it already names the provider's bundle | C6 (1 of 77) |
| None (control) | None — 77 of 77 green |

---

## 11. After #104–#107

*Appended 2026-09-11. Issues #104–#107 closed the five findings §10.3 sent to the backlog, and took #107's review
notes on this check. Their design is [`model-names-sent-and-shown.md`](model-names-sent-and-shown.md); the D-1 … D-5
below are that document's decisions, not this one's D-findings. Everything above, §10 included, is left as it was.*

### 11.1 Which finding each issue closed

| §8 finding | Closed by | Decision taken in `model-names-sent-and-shown.md` |
|---|---|---|
| D1 — three bundles share `name: default-agent` | #106, L-21 | D-5: the banner shows the configured `agent.name` (`Agent bundle: default-anthropic (agent name: default-agent)`). No `agent.md` is renamed, so the prompt and `AgentRuntimeId` are unchanged |
| D2 — the bundled `explore` subagents send `haiku` | #104, L-17 | D-2: `model:` is removed from the three `explore.md`, and each inherits its main agent's model |
| D4 — `gpt-4` for a model-less subagent under a nameless definition | #104, L-20 | D-1: `SubagentLlmDefaults.resolveModel` leaves the model nameless, and the client sends its own default model |
| D5 — the `Task` tool's `model` description | #104, L-20 | D-3: the description names no models and says the value is sent as written |
| D9 — `memory` under anthropic without `llm.model` | #105, L-18 | D-1: memory runs on `llm.model`, else on the client's default model with one startup line saying so; a client with no default model is refused with a `ConfigurationException` naming `llm.model` |
| D10 — the banner prints `llm.model` | #106, L-19 | D-4: the parenthesis is the model the main agent's requests carry |
| D11 — `README.md` shows `model: "gpt-4o-mini"` | #106 | README's sample reads `model: "gpt-5.1"`, as `default-config.yaml` does |

D3, D6, D7, D8 and D12 stay where §10.3 left them.

### 11.2 What in this document no longer holds

- **§10.1 DV-6 is reversed.** `AgentSetupFactory.DEFAULT_AGENT_BUNDLE_BASE_PATH` is `private` again, and
  `AgentModelProviderCheck.warning` receives the bundle base path from its caller (#107 item 4). The printed classpath
  root is still the one the loader reads; the check no longer reaches back into the class that calls it.
- **The `haiku` caveat** of §4.3 and §4.4 item 3 is gone from `default-config.yaml`'s `agent.name` comment and from the
  guide, because no shipped bundle names `haiku` any more. `default-anthropic` is now the clean switch that S-switch
  (§3.4) names.
- **The `llm.model` sentences** of §4.3 and §4.4 item 5 no longer say that the banner shows `llm.model`, or that
  `memory` needs it under anthropic.
- **§3.2 "What is not checked"** says model-less subagents under a definition without `model.name` fall back to `gpt-4`
  (D4). They now run on the client's default model, which under the CLI is `llm.model`, as their main agent does.
- **§6 F runs F1, F2 and F4 on `default`.** `default-anthropic`'s `explore` names no model now, so it is not an entry
  and cannot carry the identity guard; `default`'s `explore` still names `gpt-5.1`. F3 still shadows
  `default-anthropic`'s `explore`. **F5 compares files, not strings** (#107 item 2): it captures the printed path from
  the entry line and asserts `Files.isSameFile` against the file the test wrote.
- **§6 E's last paragraph** says nothing at the call site is left to test. That was true of the arguments, not of the
  call itself (#107 item 1): `AgentSetupFactoryCreateTest` now goes through `create()` and fails when the
  `reportAgentModelMismatch` call is removed.
- **The Status header's guide link** carried the Korean guide's fragment, which has no target on the `/en/` site. It now
  links the guide by file and names the passage in its text (#107 item 3).

### 11.3 What was measured

**The call through `create()`**, 2026-09-11. With the `reportAgentModelMismatch(...)` statement deleted from
`decorate()`, `./gradlew :aimon-cli:test --rerun --tests '*.AgentSetupFactoryCreateTest'` exited 1, with
`#107: the startup model check runs inside create(), …` red (1 of 2 tests). With the file restored byte-for-byte
(checked with `cmp`), the same command exited 0 (2 of 2).

**§10.4's D9 reproduction now starts.** 2026-09-11, `./gradlew :aimon-cli:run` with `provider: anthropic`, a
placeholder key, no `llm.model`, `agent.name: default-anthropic`, `memory` on the in-memory backend, and
`cli.colorOutput: false`. No input was given and no request was sent. The ASCII-art banner, the tool counts and the
JLine notice are left out, and the working directory is abbreviated:

```text
Peer memory: `llm.model` is not set, so memory runs on the Anthropic client's default model `claude-sonnet-4-20250514`. Set `llm.model` to choose another.
Peer memory enabled (in-memory backend, non-durable): workspace=ws-probe peer=peer-probe
Aimon CLI - Interactive AI Agent
Type '/help' for commands, '/quit' to exit
Working Directory: <worktree>/modules/aimon-cli
Agent bundle: default-anthropic (agent name: default-agent)
LLM Provider: Anthropic (claude-sonnet-4-5)
default-agent> Goodbye!
```

The process exited 0. **§10.4's acceptance case** (`provider: anthropic`, `model: claude-sonnet-4-5`,
`agent.name: default`) printed the same warning as §10.4, and then `Agent bundle: default (agent name: default-agent)`
and `LLM Provider: Anthropic (gpt-5.6-terra)` — the model the warning is about, where §10.4's banner showed
`claude-sonnet-4-5`.
