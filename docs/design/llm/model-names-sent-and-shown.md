# Design — #104, #105, #106, #107: the CLI sends and shows model names that do not match what runs

> Status: **IMPLEMENTED** — `aimon-core` (`SubagentLlmDefaults.resolveModel` and the `Task` tool's `model`
> description), `aimon-cli` (`AgentSetupFactory.memoryModelName`, `AgentSetup.getAgentBundleName`, the banner lines in
> `ReplSession`, the bundled `explore` subagents and the comments in `default-config.yaml`), `README.md` ›
> Configuration, and the CLI guide (ko + en). Source: issues
> [#104](https://github.com/kangwoo/aimon-core/issues/104), [#105](https://github.com/kangwoo/aimon-core/issues/105),
> [#106](https://github.com/kangwoo/aimon-core/issues/106) and [#107](https://github.com/kangwoo/aimon-core/issues/107).
>
> **[§10](#10-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where this
> document departs from what was built.** Everything between this header and §10 is the body as approved in design
> review round 1 (PASS, no blocking findings, eleven non-blocking notes), kept byte-exact rather than corrected — the
> house habit in this directory, for the reason `model-capability-binding-round-trip.md` gives. Its file:line
> citations and counts are at `main` `9b642cc`. The review transcript (`review-1.md`) and the run records the body
> names (`TASK.md`, `$RUN_DIR/build/measurements.md`, `deviations.md`) are not in the repository; §10 reproduces what
> they measured.
>
> It is in English, matching its siblings in `docs/design/llm/`; `docs/design/` is not a translation target
> (`docs/project/documentation-guide.md` §5.1). It continues
> [`provider-switch-agent-model-check.md`](provider-switch-agent-model-check.md), whose §11 maps that document's
> findings to the decisions here. What this work left open is in
> [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md), L-24.

Run `cli-model-followups`, base `main` at **`9b642cc`**. Every file:line below was re-read at that commit by this
design agent; nothing is carried over from the issues or from #92's design without being re-read.

Files this run may change (TASK.md § Sibling runs): `modules/aimon-cli/src/**`;
`modules/aimon-core/.../subagent/execution/SubagentLlmDefaults.java` and `.../tools/task/TaskTool.java` with their
tests; `README.md` › Configuration; the CLI guide (ko + en); `docs/design/llm/provider-switch-agent-model-check.md`;
`L-17`–`L-21`; plus the shared `CHANGELOG.md` `[Unreleased]`, the register title line and the index row.

---

## 1. The problem, restated

The CLI builds one LLM client from the `llm:` block, but the model name a request carries is decided in five other
places, and each of them can pick a name the configured provider does not serve or show a name that is not the one
being sent. Bundled `explore` subagents send the bare alias `haiku`, which nothing resolves and which Anthropic
rejects with 404. A subagent that names no model under an agent that names none sends a literal `gpt-4` that core
invents. The Task tool's `model` parameter recommends `sonnet`/`gpt-4.1-nano` whatever the provider. Under
`provider: anthropic` with memory on and no `llm.model`, the memory components receive `null` and startup dies with
`Unexpected error: llmModelName cannot be null`, a message that names no key. And the CLI cannot tell the user what
it runs: the banner prints `llm.model` rather than the agent's model, three bundles share `name: default-agent` so the
prompt looks the same whichever one loaded, and README's sample model is stale. #107 adds five review follow-ups on
#103's startup check: its call site is untested, F5 compares strings not files, the design record's Status link
misses on `/en/`, the check reads its caller's constant, and the guide names the Anthropic key two ways.

---

## 2. Re-verification at `9b642cc`

| # | Fact | Evidence |
|---|---|---|
| F1 | Three bundled `explore` subagents name `haiku`; `default`'s names `gpt-5.1` | `agents/{default-anthropic,default-openai,ops-agent}/agents/explore.md:5` `model: haiku`; `agents/default/agents/explore.md:5` `model: gpt-5.1`. Main agents: `default` `gpt-5.6-terra`, `default-openai` / `ops-agent` `gpt-5.1`, `default-anthropic` `claude-sonnet-4-5`, `terra` `gpt-5.6-terra` (no subagents) |
| F2 | Subagent resolution invents `gpt-4` | `SubagentLlmDefaults.java:21` `DEFAULT_MODEL_NAME = "gpt-4"`; `:66-72` override > subagent `model` > `defaultModel.getName().orElse(DEFAULT_MODEL_NAME)`. Production callers: `DefaultSubagentExecutor.java:768` (via `buildModelConfig`, used at `:323`) and `DefaultSubagentBehaviorSupport.java:53` |
| F3 | Where `defaultModel` comes from | Always the main agent's `LlmModel` or a value passed down from it: `OrcaSubagentToolProvider.java:91` (`new TaskTool(agent.getMetadata().getModel(), …)`), `OrcaAgentRuntimeFactory.java:962`, `GraalJsWorkflowToolProvider.java:85` (CLI); `TaskTool.java:546`, `WorkflowTool.java:449`, `SubagentBackedSkillForkExecutor.java:113`, `GraalJsWorkflowTool.java:255`, `DefaultSubagentExecutionManager.java:604` forward a `defaultModel` they were given |
| F4 | **A nameless `LlmModel` is already a handled state on both paths** | Both clients send `modelConfig.getName().orElse(config.getModel())` (`AnthropicLlmClient.java:378`, `OpenAILlmClient.java:313`). The compaction guard falls back to default limits for an absent name (`DefaultCompactionGuard.java:209-213`). The main agent already hands its (possibly nameless) definition model to the guard (`OrcaAgentExecutor.java:1605-1611`) and prices with `getName().orElse(null)` (`:1547`); the subagent executor prices with `orElse("")` (`DefaultSubagentExecutor.java:433`, `:929`), which `TablePricedCostEstimator.java:53-60` answers with zero and one WARN. The tracing, metering and logging decorators use `getName().or(delegate::getDefaultModelName)` (`TracingLlmClient.java:142`, `MeteringLlmClient.java:157`, `LoggingLlmClient.java:241`). A definition with no `model` block runs this way today |
| F5 | Task tool | `TaskTool.java:344-345` description `"Optional model to use (sonnet, gpt-4.1, gpt-4.1-nano). Prefer gpt-4.1-nano for simple tasks."`; class javadoc `:70` "Model selection (sonnet, haiku, opus)", example `:94` `"model", "haiku"`. `TaskToolTest` has no assertion on the `model` description (grep `model`: 0 hits). `TaskTool` has no `LlmClient` and no provider (constructed from an `LlmModel`, registries and an execution manager) |
| F6 | Memory takes `llm.model` unguarded | Engine `AgentSetupFactory.java:525-527`; deriver and reconciler `:532-533` → `buildMemoryDeriver` `:1100-1113`; dreamer `:660-662` → `buildDreamerSubsystem` `:1175`, strategy `:1193`, judge `:1233` via `resolveJudgeModel` `:1242-1251`. These five are the **only** constructions of `LlmDialecticEngine` / `LlmDeriver` / `DefaultReconciler` / `RandomWalkDreamer` / `LlmJudgeSurprisalScorer` in any module's `src/main` |
| F7 | The factory and the clients | `LlmClientFactory.java:77-79` copies `llm.model` under anthropic only when non-null; `:322-329` requires it under openai and names the key. `AnthropicConfig.java:40` default `claude-sonnet-4-20250514`; `Builder.model` only `requireNonNull`s (`:324-325`), so `llm.model: ""` reaches the config as `""`. Both clients answer `getDefaultModelName()` with `Optional.of(config.getModel())` (`AnthropicLlmClient.java:761-764`, `OpenAILlmClient.java:588-591`); the interface default is empty (`LlmClient.java:341-343`). `AimonCli.java:115-120` prints `Configuration error:` for `ConfigurationException`, `:129-131` `Unexpected error:` otherwise |
| F8 | Banner | `ReplSession.java:254-259`: `Working Directory:` then `LLM Provider: <getProviderName()> (<getDefaultModelName()>)`. `displayAgentInfo` is private and runs inside `start()`, which needs a terminal. REPL tests build `AgentSetup.builder()` by hand (`ReplSessionQueueTest.java:82`, `ReplSessionRetryTest.java:66`) |
| F9 | Names | `agents/{default,default-openai,default-anthropic}/agent.md:3` `name: default-agent`. Prompt `AimonCli.java:106-108` from `agentSetup.getAgent().getName()`. Runtime id `AgentRuntimeId.java:114-117` `from(Agent)` → `agent:<name>`. `AgentSetup` carries no `agent.name` (getters `AgentSetupFactory.java:215-299`); the configured name is `extractAgentName` `:921-923`, default `"default"` `:142`. `aimon-cli/build.gradle.kts` applies no `aimon.publishable`, so `AgentSetup` is not library API |
| F10 | What a bundle rename would touch across a restart (census for decision 5) | Agent-wide approvals: in-memory (`AimonStackBuilder.java:283` `orElseGet(InMemoryAgentApprovalStore::new)`). Scheduling: `SchedulingSpec.enabled()` "does not survive a restart" (`SchedulingSpec.java:68-78`). Session records: `InMemorySessionRecordStore` (`AgentSetupFactory.java:563`). Dreamer: `RAMJobStore`. Memory: keyed by workspace/peer, not the runtime id. **Not in memory, and not counted to the end:** the wiki store resolves its VFS by runtime id under `.aimon/wiki` (`AgentSetupFactory.java:1398-1405`) — whether the on-disk layout carries the id was not checked; hook scripts receive `AIMON_AGENT_RUNTIME_ID` and skills `${AIMON_AGENT_RUNTIME_ID}` (`docs/migration/rename-maps.md`), and what user-owned scripts key on it cannot be counted from the tree |
| F11 | #107 item 1 | The only call is `AgentSetupFactory.java:625-626`. Tests call the seam directly (`AgentSetupFactoryAgentModelCheckTest.java:139`, `:352`). No CLI test calls `create()`. Package-private `AgentSetupFactory(LlmClientFactory, AgentBundleLoader)` at `:451-456`; `LlmClientFactory` is a public non-final class with a public `create`. `create()` roots its file system at `user.dir` when not run from a jar (`:1416-1433`; `modules/aimon-cli` under Gradle, where no `.aimon/` exists today) and starts hook hot reload over `user.home` (`:958-963`) |
| F12 | #107 items 2–5 | F5 asserts `printed().contains(expectedPath)` (`AgentSetupFactoryAgentModelCheckTest.java:355-358`). Status link `provider-switch-agent-model-check.md:5` carries the Korean fragment; `docs/design/` is not translated, so `/en/` serves that page's Korean source, the i18n plugin (`mkdocs.yml:88-99`, `docs_structure: suffix`) retargets the `.md` link to the English guide, and the Korean fragment has no target there. `AgentModelProviderCheck.java:315` reads `AgentSetupFactory.DEFAULT_AGENT_BUNDLE_BASE_PATH`, package-private for that purpose (`:422-423`, #92 DV-6). Guide `${ANTHROPIC_KEY}` ko `:270` / en `:279`; `${ANTHROPIC_API_KEY}` ko `:428` / en `:447` |
| F13 | README | `README.md:177` `model: "gpt-4o-mini"`, under `:172` naming `default-config.yaml`, which sets `model: "gpt-5.1"` (`:42`). No `README.ko.md`, no `CHANGELOG.ko.md` |
| F14 | Tests that depend on `haiku` in a shipped bundle | `AgentModelProviderCheckTest.java:355-360` (`haikuIsSilentByRule`). `AgentSetupFactoryAgentModelCheckTest` F1 (`:311-315`) and F2 (`:317-326`) use `default-anthropic`'s bundled `explore` as a `BUNDLE` entry — and `declaredModels` skips a subagent with no model (`AgentModelProviderCheck.java:219-225`). The hand-built `bundled("explore", "haiku")` in `:293-294` does not load a bundle and is unaffected |
| F15 | Backlog | Title `등록 항목 21건 (열림 17 · 닫힘 4)` (`llm-config-surface-open-items.md:1`); index row `docs/backlog/README.md:347` `\| 21 \| 17 \| 4 \| 0 \|`; the dated parenthetical under the index ends with the #92 step (`:374-376`). A closed item keeps its `## L-N — …` heading and gains a `### 닫힘 (<date>, #NN)` subsection (e.g. `:375`, `:648`), which the checker reads as its state |
| F16 | Guide bookkeeping | `…-reference.en.md` `source_commit: 6c53cfe`; the canonical's last commit is `4f677a6` (#92, touched both) |
| F17 | Fixtures that keep `model: haiku` | `aimon-core/src/test/resources/{builtin/agents,agents/test-agent/agents,agents/agent-subagents-only/agents}/explore.md`, `aimon-spring-boot-starter/src/test/resources/agents/hints-probe/agents/probe.md`, and two parser tests. Parser fixtures; no request carries them |

---

## 3. Decisions

Each gets its own heading in the PR body: taken, rejected, why.

### D-1 — What model a component gets when none is written

**Taken — one principle: a component that names no model runs on the model the client itself would send for a request
that names none — the client's default. The framework never invents a name, and never stops startup for a key the
configuration surface calls optional.** It lands differently on the two paths, because they reach the client
differently:

- **Subagents (core).** `SubagentLlmDefaults.resolveModel` stops writing `gpt-4`. When the override, the subagent's
  `model` and the parent's name are all absent, the returned `LlmModel` **carries no name**, and the client fills
  `config.getModel()` at request time by its own rule (F4). That is exactly how a nameless main agent already runs, so
  a model-less subagent now runs on precisely what its main agent runs on. No `LlmClient` enters core, and neither
  call site changes (`DefaultSubagentExecutor.java`, owned by `max-tokens-truncation-reporting`, and
  `DefaultSubagentBehaviorSupport.java` stay untouched).
- **Memory (CLI).** The CLI resolves **one** memory model name, once: `llm.model` when non-blank; otherwise
  `llmClient.getDefaultModelName()` when non-blank; otherwise a `ConfigurationException` naming `llm.model`. That name
  goes to all five constructors (F6). When the fallback is used, startup **says so** in one line naming the model and
  the key — the answer to #105's caution that this "is a decision to send memory calls on a model the user did not
  write".

**Why subagents and memory get the same answer.** Subagents cannot take the "require the key" option at all: core has
no configuration key to name, and a definition without `model.name` is valid — its main agent already runs on the
client's default. Requiring `llm.model` for memory would therefore make memory the one component that behaves
differently, for no reason except that the CLI happens to be able to.

**Rejected.**

| # | Alternative | Why rejected |
|---|---|---|
| R1a | Require `llm.model` under anthropic when `memory` is on (`ConfigurationException`) | It turns a key the factory makes optional under anthropic (F7) into a conditionally required one — a narrower config contract than the smaller option needs. Two consumers already run on the client default when it is absent (wiki generation gets a nameless model; a main agent without `model.name`), so memory would be the lone exception. The issue's concern is met by telling instead of refusing |
| R1b | Pass the client's default into `resolveModel` (a new overload with a supplier) | Same name on the wire, reached the long way: both call sites must change, one of them in a sibling-owned file, and `DefaultSubagentExecutor` holds an `LlmCallGateway`, not the client (`:187-189`), so the supplier has to be threaded in. It also freezes the default at resolution time, where a nameless model lets a decorator or router decide per request (F4) |
| R1c | Keep a literal fallback, but a better one | Any literal is a vendor guess. The literal is the defect |
| R1d | Guard only the dialectic engine | Moves the failure to the deriver, reconciler, dreamer and judge (#105 notes) |
| R1e | Let memory fall back to the main agent's `model.name` | Couples memory to `agent.name` behind the user's back, while the documented key for memory is `llm.model`, and the main agent may name no model either |

### D-2 — The bundled `explore` subagents' `model: haiku`

**Taken — drop `model:` from the three `explore.md` files**, so each `explore` inherits its main agent's name:
`claude-sonnet-4-5` in `default-anthropic`, `gpt-5.1` in `default-openai` and `ops-agent`. A one-line YAML comment in
each frontmatter says why the key is absent, so nobody re-adds an alias. `default/agents/explore.md` keeps
`model: gpt-5.1`: it names a model its provider serves, and #104 does not cite it.

**Rejected — full vendor model ids per bundle** (e.g. `claude-haiku-4-5` for `default-anthropic`, some small OpenAI
model for the other two).

- The only name known to go to the configured provider under the documented pairing is the main agent's. No request
  with `claude-haiku-4-5` was sent by #92 or by this run; the capability table's 2026-09-10 census
  (`InMemoryModelCapabilityRegistry`'s javadoc) covers the `claude-haiku-4-5` family's dialect, not these bundles. For
  OpenAI, picking the "fast" model is a product choice with no measurement in the tree.
- It adds three vendor names to keep current. README's `gpt-4o-mini` (#106 item 3) is this repository's own evidence
  of how such samples age.
- Inheriting makes `agent.name` the one switch for the whole bundle, which is what #103's guide tells users to use.

**Rejected — resolve aliases per provider in core** (`haiku` → a Claude id under anthropic). It needs vendor knowledge
in core, which the capability registry deliberately does not carry (#92 R8), plus an alias table to keep current, and
it makes `haiku` mean different things under different providers. It is D-3's rejected option in another form.

**Cost, stated.** `explore` loses its "cheaper, faster model" intent and runs on the main model. Under these three
bundles its requests fail today (Anthropic's 404 was measured; OpenAI's answer to `haiku` was not), so no working
behaviour becomes slower. A user who wants a cheaper `explore` writes `.aimon/agents/explore.md` with a full id; #103's
startup check covers that file.

### D-3 — The Task tool's `model` parameter description

**Taken — stop naming models; state the contract.** The new text (the build may polish it, but these facts stay):

> Optional model id for this run only. It is sent to the configured provider exactly as written — no alias is
> resolved — and it overrides the subagent's own model. Leave it out to run on the subagent's model, or on the main
> agent's when the subagent names none.

The class javadoc's "Model selection (sonnet, haiku, opus)" (`:70`) and the example's `"model", "haiku"` (`:94`) are
corrected in the same file.

**Rejected — name models per provider.** `TaskTool` has no provider (F5), so it would need vendor attribution in core
(R8) or a new constructor argument threaded from `OrcaSubagentToolProvider` (outside this run's files). It is the
alias-resolution decision of D-2 in another form, and a model list inside a runtime string the model reads on every
turn would go stale where nobody looks.

**Rejected — remove the `model` parameter.** The input schema is a contract that models and stored transcripts rely
on, and nobody asked for it.

**Residual, stated and not registered.** A model can still pass a name its provider does not serve; no startup check
can see a per-call argument (#92 §3.2). Nothing is left to prescribe once the description stops recommending wrong
names, and nothing marks when to look again, so it goes into L-20's closing note and `deviations.md` rather than a new
item (§8).

### D-4 — The banner's model

**Taken — the parenthesis shows the model the main agent's requests carry**: the definition's `model.name`, or the
client's default when it names none — the same `getName().orElse(...)` both clients apply. The line keeps its shape,
`LLM Provider: <provider> (<model>)`. When the result is empty or blank, the parenthesis is omitted, as it is today
for a client with no default.

**Rejected — label the parenthesis as `llm.model`.** It is truthful, but it leaves the user unable to see what runs,
which is #106's complaint. On #92's reproduction the banner would still read `Anthropic (claude-sonnet-4-5)` directly
under a warning about `gpt-5.6-terra`. And after D-1, `llm.model` has no banner role left: memory's fallback prints
its own line.

**Rejected — show both names.** On the shipped configuration they differ (`gpt-5.6-terra` vs `gpt-5.1`), so two names
in one parenthesis puts back the question "which one runs".

**Not shown, stated in the guide:** a subagent that names its own model (`default`'s `explore` on `gpt-5.1`). One line
cannot hold them (L-19's note).

### D-5 — Telling the three bundles apart

**Taken — the CLI shows the configured `agent.name`.** The banner gains one line between `Working Directory:` and
`LLM Provider:`:

```
Agent bundle: default-anthropic (agent name: default-agent)
```

When the bundle name and the definition's name are equal, the parenthesis is left out (`Agent bundle: ops-agent`). The
parenthesis explains the prompt: `default-agent> ` is the definition's name, which is also where the runtime id comes
from. `AgentSetup` gains the configured name so `ReplSession` can print it.

**Rejected — give the three `agent.md` files distinct `name`s.** That changes the prompt **and** `AgentRuntimeId`
(`agent:default-agent` → e.g. `agent:default-anthropic`), a persisted-identity change. The census (F10) finds most
runtime-keyed CLI state in memory, but it could not be finished: the wiki store's on-disk layout is unverified, and
hook scripts and skills that read `AIMON_AGENT_RUNTIME_ID` are user-owned. The smaller option meets acceptance
criterion 3 without it, and TASK.md's rule is that an option changing a persisted identity needs a stated reason the
smaller option does not work. There is none.

**Rejected — build the default prompt from `agent.name`.** It changes a line every user sees on every input, for a
fact that is fixed for the session. The shipped prompt would become `default> `, which says less than
`default-agent> `, and the prompt would disagree with the runtime id the rest of the system reports. Users who want it
still have `cli.prompt`.

---

## 4. The non-decision items

- **#107 item 1** — a test that goes through `create()` (§6 T1), shown red with the call line removed and recorded in
  `$RUN_DIR/build/measurements.md`.
- **#107 item 2** — F5 extracts the printed path and asserts `Files.isSameFile` against the written file (§6 T7).
- **#107 item 3** — the Status header links the guide by file, with no fragment, and names the passage in its text.
  Verify from `mkdocs build --strict` INFO output and the built `site/en/design/llm/provider-switch-agent-model-check/`
  page, not from `check-doc-links.py` alone.
- **#107 item 4** — `AgentModelProviderCheck.warning` receives the bundle base path. The constant goes back to `private`.
- **#107 item 5** — the thinking example uses `${ANTHROPIC_KEY}` (ko + en, one commit).
- **#106 item 3** — README's sample reads `model: "gpt-5.1"`, matching `default-config.yaml:42`.

---

## 5. Concrete changes, by file

### 5.1 `aimon-core`

**`subagent/execution/SubagentLlmDefaults.java`**
- Remove `DEFAULT_MODEL_NAME`. The last branch becomes a nameless model:
  ```java
  } else {
      // No literal: a nameless model is sent on the client's own default, as a nameless main agent already is.
      modelName = defaultModel.getName().orElse(null);
  }
  ```
- Javadoc on both overloads: say that the result's name may be empty and that the client then applies its default;
  replace "alias" with "model name, sent as written".
- `DEFAULT_TEMPERATURE` / `DEFAULT_MAX_TOKENS` are unchanged (out of scope, §8).

**`subagent/execution/SubagentLlmDefaultsTest.java`**
- Rename the fixture's name from `"gpt-4"` to a neutral `"parent-model"`, so no assertion can pass by matching the old
  literal.
- Add: nameless default + no subagent model + no override → `getName()` is **empty**, and temperature and max-tokens
  are still inherited.
- Add: nameless default + subagent model → the subagent's model.

**`tools/task/TaskTool.java`** — the `model` description (D-3), the class javadoc bullet at `:70` and the example map at
`:94`. Nothing else: the schema keeps the same property name, type and `required` list.

**`tools/task/TaskToolTest.java`** — one test reads
`getDefinition().getInputSchema()` → `properties.model.description` and asserts it contains `exactly as written`
and none of `sonnet`, `haiku`, `gpt-`, `claude-`.

### 5.2 `aimon-cli` resources

- **`agents/{default-anthropic,default-openai,ops-agent}/agents/explore.md`** — delete `model: haiku`; add one frontmatter
  comment, e.g. `# No model: runs on the main agent's, the one this bundle's provider serves.` T5 loads all three
  through the real loader, which proves the subagent parser accepts the comment. If it does not, drop the comment and
  record that.
- **`default-config.yaml`** — comments only:
  - Above `provider:`: `model -- not the agent's model, but memory and wiki generation still use it (see its comment)`.
    The banner leaves that line.
  - Next to `model:`: drop the banner bullet. The last bullet becomes "the agent and its subagents, only when the
    definition has no `model.name`". The anthropic sentence becomes: "Under anthropic it may be left out -- memory and
    wiki generation then use the client default claude-sonnet-4-20250514, and startup says so when memory is on." Keep
    the sentence about an OpenAI name left behind.
  - Next to `agent.name`: replace the `haiku` caveat with "`default-anthropic` for `provider: anthropic`
    (claude-sonnet-4-5; its `explore` subagent names no model and runs on the same one)". Add "The startup banner's
    `Agent bundle:` line names the bundle that loaded, and `LLM Provider: <provider> (<model>)` the model its main agent's
    requests carry."

### 5.3 `aimon-cli` Java

**`factory/AgentModelProviderCheck.java`** (#107 item 4)
- `warning(LlmProviderConfig llm, String bundleBasePath, String agentName, String workingDirectory,
  List<DeclaredModel> models)`. Thread it to `entryLine`, which builds `bundleBasePath + "/" + agentName`. No other
  reference to `AgentSetupFactory` remains.

**`factory/AgentSetupFactory.java`**
1. `DEFAULT_AGENT_BUNDLE_BASE_PATH` back to `private`; delete the DV-6 comment.
2. `reportAgentModelMismatch` passes `DEFAULT_AGENT_BUNDLE_BASE_PATH` to `warning`. The seam's own signature is
   unchanged, so E and F keep calling it.
3. New package-private seam, called in `create()` right after `createOutputFormatter` (`:500`) and **before**
   `new LocalShell()` (`:504`), so a refusal leaves nothing started:
   ```java
   // Null when memory is off; otherwise the one name all five memory components receive (D-1).
   static String memoryModelName(CliConfig config, LlmClient llmClient, OutputFormatter outputFormatter)
   ```
   - Memory off (`config.getMemoryConfig()` null or `!isEnabled()`): return `null` and print nothing.
   - `llm.model` non-blank: return it.
   - Else `llmClient.getDefaultModelName()` non-blank: `log.warn` and `displayInfo` one line, e.g.
     ``Peer memory: `llm.model` is not set, so memory runs on the Anthropic client's default model
     `claude-sonnet-4-20250514`. Set `llm.model` to choose another.``, then return the name. Use
     `getProviderName()`; the pair follows the `:979-984` precedent.
   - Else: throw `ConfigurationException("Peer memory needs a model: set `llm.model` in the LLM config — the <provider>
     client has no default model to fall back to.")`.
4. Pass that name, not `config.getLlmConfig().getModel()`, at `:527`, `:533` and `:661`. For `:661`,
   `CliMemoryDecorations` gains a `memoryModelName` field.
5. `AgentSetup` gains `agentBundleName` (builder setter and getter; nullable, since test-built setups omit it).
   `decorate()` sets it from `extractAgentName(config)`.

**`repl/ReplSession.java`**
- Two package-private static helpers, so the banner is testable without a terminal:
  ```java
  static Optional<String> agentBundleLine(String bundleName, Agent agent)  // empty when bundleName is null
  static String providerLine(LlmClient client, Agent agent)
  ```
  `providerLine` resolves `agent.getMetadata().getModel().getName().or(client::getDefaultModelName)` and omits the
  parenthesis when the result is empty or blank.
- `displayAgentInfo` prints `agentBundleLine` (when present) after `Working Directory:`, then `providerLine`. The agent
  comes from `agentSetup.getAgent()`, kept in a field; the bundle name from `agentSetup.getAgentBundleName()`.

### 5.4 Tests (`aimon-cli`) — see §6

- New `factory/AgentSetupFactoryCreateTest.java` (T1, T2).
- New `factory/AgentSetupFactoryMemoryModelTest.java` (T3), or a nested class in T1's file — the build's choice.
- New `repl/ReplSessionBannerTest.java` (T4).
- New `factory/BundledSubagentModelTest.java` (T5).
- Changed `factory/AgentModelProviderCheckTest.java` (T6) and `factory/AgentSetupFactoryAgentModelCheckTest.java` (T7).

### 5.5 Documentation

**CLI guide, `docs/getting-started/aimon-core-integration-via-cli-reference.md` + `.en.md` — one commit, identical
structure.**

1. Replace the `haiku` caveat paragraph (ko `:276-278`, en `:285-287`): the bundles' `explore` subagents name no model
   and run on their main agent's (except `default`'s, which names `gpt-5.1`). A subagent that names no model runs on
   what the main agent runs on, and when that names none either, on the client default (`llm.model`).
2. The `llm.model` paragraph (ko `:288-292`, en `:297-301`):
   - Drop the banner clause, and the claim that `memory` without it fails.
   - Say that under anthropic it may be left out, that memory and wiki generation then run on the client default
     `claude-sonnet-4-20250514`, and that startup prints one line saying so when memory is on.
   - Add one sentence on the banner: `Agent bundle:` names the loaded bundle; `LLM Provider:`'s parenthesis is the
     model the main agent's requests carry; a subagent's own model is not shown.
3. The warning paragraph: "including that `haiku`" no longer has a referent. Replace it with a gateway's own deployment
   name as the example of a name no vendor claims.
4. The thinking example: `${ANTHROPIC_API_KEY}` → `${ANTHROPIC_KEY}` (ko `:428`, en `:447`).
5. `.en.md` `source_commit: 4f677a6` (F16).
6. **Structure:** no heading, fence, list item, table or quote block added or removed. `check-translation-structure.py`
   must stay green. Introduce no new `aimon.*` token (`AimonDocumentedPropertiesTest` scans the guides, #92 DV-10).
7. The heading `provider 를 바꿀 때 — …` and its anchor are unchanged, so `docs/README*.md:49/:57` keep resolving.

**`README.md` › Configuration** — `model: "gpt-4o-mini"` → `model: "gpt-5.1"`. Nothing else; no twin (F13).

**`docs/design/llm/provider-switch-agent-model-check.md`**
- Status header, line 5: link the guide by file, with no fragment, and name the passage in text (e.g. "the CLI guide's
  provider-switch passage, §4.1 *provider 를 바꿀 때*"). Lines 16-17: "L-17 ~ L-21, closed by #104–#107 (§11)".
- **Append `## 11. After #104–#107`.** The body and §10 stay byte-exact, the habit this file declares.
  - A table: D1, D2, D4, D5, D9, D10 and **D11** → the issue that closed each → the decision taken (D-1…D-5 above, one
    line each).
  - What reverses §10: DV-6 (the constant is private again; the check receives its base path), the guide's `haiku`
    caveat, the `llm.model`/banner sentences.
  - What was measured: the T1 mutation, and the #105 reproduction starting.

  Use class and method names rather than file:line where the style allows (`docs/design/README.md` §3.3).

**Backlog — `llm-config-surface-open-items.md` + `docs/backlog/README.md`**
- Each of L-17 … L-21 keeps its heading and gains `### 닫힘 (2026-09-11, #NN)`: L-17 #104, L-18 #105, L-19 #106,
  L-20 #104, L-21 #106. Per the register's rules two, three and six, each note says what was done, what the item's
  premise got wrong or right, and what is left:
  - **L-17** — the second prescription shape was taken and the first rejected (reason: D-2). OpenAI's answer to `haiku`
    stays unmeasured and is now moot. The test fixtures in F17 keep `haiku` and are never sent.
  - **L-18** — the second shape was taken, plus the startup line. `ConfigurationException` remains only for a client
    with no default.
  - **L-19** — the parenthesis is the main agent's model; subagents' own models are still not shown.
  - **L-20** — the literal is gone and the description names no models. Residual: an LLM-chosen override is still
    sent as written (D-3). Rule six: the item said the sources of `.defaultModel(` were not counted. The build counts
    them (F3 lists the eleven main call sites) before writing the note.
  - **L-21** — `agent.name` is now shown and the names are unchanged. The F10 census goes here, including what was
    **not** counted.
- **Recount from the body:** 21 items, open 12, closed 9, dissolved 0. Title `등록 항목 21건 (열림 12 · 닫힘 9)`; index
  row `| 21 | 12 | 9 | 0 |`. Extend the dated parenthetical with the #104–#107 step. Then run the checker. A merge with
  `max-tokens-truncation-reporting` (L-16, L-22, L-23) is expected to conflict here; resolve it by recounting.
- **No new item.** L-24 and L-25 stay unused (§8 says why).

**`CHANGELOG.md` `[Unreleased]`** — one entry above #92's, issue numbers in the bullets rather than the heading (#92
DV-5). Its bullets:
1. Bundled `explore` in `default-anthropic`, `default-openai` and `ops-agent` inherits the main agent's model instead
   of sending `haiku` (#104).
2. **`aimon-core`**: `SubagentLlmDefaults.resolveModel` no longer invents `gpt-4`; with no name anywhere, the model is
   left nameless and the client's default applies. Visible to code that reads `resolvedModel().getName()` (#104).
3. The Task tool's `model` description names no models and says the value is sent as written (#104).
4. `provider: anthropic` + `memory` without `llm.model` now starts on the client default and prints one line saying
   so. A client with no default fails with a `ConfigurationException` naming `llm.model` (#105).
5. The banner: the parenthesis is the main agent's model, and a new `Agent bundle:` line (#106).
6. Documentation: README's sample model, the guide's key name and the design record's link (#106, #107).

**No `docs/migration/rename-maps.md` entry:** no bundle is renamed, no runtime id changes, and
`AgentModelProviderCheck` is package-private in an unpublished module.

**Suggested commits** (the build decides, keeping ko + en in the same commit):
1. `fix(core): stop inventing a subagent model name, and name no models in the Task tool (#104)`
2. `fix(cli): bundled explore subagents inherit the main agent's model (#104)`
3. `fix(cli): memory runs on the client's default model when llm.model is absent (#105)`
4. `fix(cli): the banner shows the loaded bundle and the model the agent sends (#106)`
5. `test(cli): pin the startup check's call through create() and compare F5's path as a file (#107)`
6. `docs: …` — guide ko + en, README, design §11, backlog, CHANGELOG.

---

## 6. Test strategy

JUnit 5, AssertJ, `@DisplayName`/`@Nested`; stdout captured as `AgentSetupFactoryAgentModelCheckTest` does
(`colorOutput=false`, `System.setOut` in `@BeforeEach`). Fragments, never whole strings.

| # | Test | Asserts |
|---|---|---|
| **T1** | `AgentSetupFactoryCreateTest` — #107 item 1. `new AgentSetupFactory(recordingFactory, null)`, where `recordingFactory` is an `LlmClientFactory` subclass that calls `super.create(...)` and keeps the client so `@AfterEach` can close it. Config: `provider: anthropic`, placeholder key, `model: claude-sonnet-4-5`, `agent.name: default`, no `baseUrl`, no memory. Build inside try-with-resources | stdout contains `Agent model:` and `model.name: gpt-5.6-terra`; `setup.getAgentBundleName()` is `default`. **Mutation M1 (required):** delete the `reportAgentModelMismatch(...)` call in `decorate()`, run T1, see it red, restore, see it green; record commands and outcomes in `measurements.md` |
| **T2** | Same class — #105 end to end. The issue's reproduction config, plus `reconcilerEnabled: true` and `dreamer: {enabled: true, scorer: {type: llm}}` so every constructor in F6 runs. `backend: in-memory`, `storagePath` under `@TempDir` | No exception; stdout contains ``llm.model` is not set``, `claude-sonnet-4-20250514`, `reconciler enabled` and `dreamer enabled`, and not `cannot be null`. **Mutation M2:** revert `:527` to `config.getLlmConfig().getModel()` → red |
| **T3** | `memoryModelName` unit | Memory off → `null`, nothing printed. `llm.model: claude-x` → `claude-x`, nothing printed. `llm.model` null or `"  "` + client default `claude-d` → `claude-d` and one printed line. Client default empty → `ConfigurationException` whose message contains `` `llm.model` ``. `llm.model: ""` + client default `""` (F7's reachable case) → same exception |
| **T4** | `ReplSessionBannerTest` (static helpers) | `agentBundleLine("default-anthropic", agent named default-agent)` → `Agent bundle: default-anthropic (agent name: default-agent)`; equal names → no parenthesis; null bundle → empty. `providerLine` with definition `gpt-5.6-terra` and client default `gpt-5.1` → contains `gpt-5.6-terra`, not `gpt-5.1`; nameless definition → client default; nameless definition + client with no default → no parenthesis |
| **T5** | `BundledSubagentModelTest` — acceptance criterion 1 | For `default`, `default-anthropic`, `default-openai` and `ops-agent`, loaded by `new AdaptiveAgentBundleLoader("agents")`: every subagent's `SubagentLlmDefaults.resolveModel(subagent, bundle.getAgent().getMetadata().getModel())` — the resolution the Task tool path performs with the main agent's model (F3) — is present, and either equals the main agent's name or `vendorOfModel(resolved) == vendorOfModel(main)` with both present. `default-anthropic`'s `explore` resolves to exactly `claude-sonnet-4-5`. **Mutation M3:** put `model: haiku` back in `default-anthropic/agents/explore.md` → red (`haiku` belongs to no family) |
| **T6** | `AgentModelProviderCheckTest` | `check`, `fired`, `checkBundle` and the two direct calls pass `"agents"`. Replace `haikuIsSilentByRule` with "`default-anthropic`'s declared models are the main agent's alone, and it is silent under anthropic". C1 still asserts `agents/default/agent.md`, which proves the base-path parameter reaches the text |
| **T7** | `AgentSetupFactoryAgentModelCheckTest` | **F1/F2** move to `default`, the only shipped bundle whose subagent still names a model (F14): `explore` is `BUNDLE`; with `reviewer.md` added, `reviewer` is `OUTSIDE_BUNDLE` and `explore` stays `BUNDLE`. **F3** stays on `default-anthropic` and now means "a user file that shadows a bundled subagent naming no model" → `OUTSIDE_BUNDLE`. **F4** moves to `default` + a user `explore.md` keeping `gpt-5.1` → `OUTSIDE_BUNDLE`. **F5** (#107 item 2): capture the path with ``subagent `explore`, `(.+?)`: `model: gpt-5.1` `` and assert `Files.isSameFile(Path.of(captured), tempDir.resolve(".aimon/agents/explore.md"))`, keeping `doesNotContain("agents/default/agents/explore.md")` |
| **T8** | `SubagentLlmDefaultsTest`, `TaskToolTest` | §5.1. **Mutation M4:** restore `orElse("gpt-4")` → the nameless case goes red |

**T1/T2 side effects, accepted and stated.** `create()` roots the file system at `user.dir` and starts hook hot
reload over `user.home` (F11). The assertions are fragments that files under either could add to but not remove: the
main-agent line always comes from the bundle. Everything started is closed by `AgentSetup.close()`. Constructing the
real Anthropic client with a placeholder key sends no request (#92 §10.4).

**Gates** (numbers measured and recorded, never "passed"):
- `./gradlew format`, then `./gradlew checkAll` — tests run, failures, skips.
- The four doc checks: `check-doc-links.py`; `check-backlog-registers.py` (and `--self-test`); `check-translation-staleness.py`;
  `check-translation-structure.py`. Run once before editing, as a baseline, and again after.
- `mkdocs build --strict` for #107 item 3. Grep its INFO output for `provider-switch-agent-model-check` / "anchor",
  and read the `href` in `site/en/design/llm/provider-switch-agent-model-check/index.html`. Record both.
- **No live call.** The optional OpenAI `haiku` probe is not made: after D-2 no shipped bundle sends `haiku`, so no
  wording depends on the answer (TASK.md: make it "only if the fix's wording depends on the answer").

---

## 7. Failure modes

| Failure mode | Handling |
|---|---|
| The client's default model is not served (e.g. `claude-sonnet-4-20250514` retired) | Memory and wiki requests fail at request time with the vendor's error. The startup line has already named the model and the key. **Not measured** (§9 Q2) |
| An LLM passes an unserved `model` override to the Task tool | Sent as written; the vendor's error returns through the Task tool. Unmeasured (#92 U3); the description no longer suggests one (D-3) |
| A nameless subagent under a client with no default (a router, a test double) | The client decides. Both shipped clients always have a default (F7) |
| `llm.model: ""` under anthropic with memory on | The resolver treats blank as absent; the client default is also `""`, so it throws a `ConfigurationException` naming `llm.model` (T3) |
| Memory off | The resolver returns `null` before reading anything; output is unchanged |
| The memory refusal fires | Thrown before `LocalShell` or any pool starts; `AimonCli` prints `Configuration error: …` and exits 1 |
| A definition's `model.name` is blank | The banner mirrors the client and omits an empty parenthesis |
| A test-built `AgentSetup` with no bundle name | No `Agent bundle:` line |
| A behaviour or third-party code reads `resolvedModel().getName().get()` | Can now throw when no name exists anywhere. Called out in CHANGELOG; no in-tree reader (grep of `resolvedModel` in `src/main`: the support class alone) |
| #103's check after D-2 | `default-anthropic`'s `explore` drops out of `declaredModels`. No shipped bundle's warning changes: T6's D table is unchanged |
| Link without a fragment | Lands at the top of the guide; the text names the passage |
| Sibling merges (`CHANGELOG.md`, register title, index row) | Expected; recount from the body at merge |
| `DefaultSubagentExecutor.java` (sibling-owned) | Not edited by this design |

---

## 8. Findings outside the diff — for `build/deviations.md` and the handoff

| ID | Finding | Evidence | Disposition |
|---|---|---|---|
| X1 | Core javadoc still offers aliases as examples | `SubagentMetadata.java:40`, `SubagentContentParser.java:25` (`haiku`); `Subagent.java:203` and subagent `package-info.java:62` (`sonnet`) | Outside this run's files. Documentation only; no request carries it |
| X2 | `AnthropicLlmClient`'s javadoc says `resolveModel` "always puts one on the request" | `AnthropicLlmClient.java:468` | Still true: "one" is a temperature, not a name. No edit, and the file is sibling-owned |
| X3 | Every subagent gets `temperature 0.7` when the parent sets none | `SubagentLlmDefaults.java:22`, `:73` | A value nobody wrote, but the capability table suppresses it where refused. Out of scope; not registered |
| X4 | An LLM-chosen Task `model` override is unchecked | D-3 | Goes in L-20's closing note. Not an item: no prescription, no trigger |
| X5 | Test fixtures keep `model: haiku` | F17 | Parser fixtures; never sent |
| X6 | The wiki store's on-disk layout vs. runtime id was not checked | F10 | Relevant only to a future rename; noted in L-21's closing |
| X7 | `default`'s `explore` names `gpt-5.1` rather than inheriting `gpt-5.6-terra` | F1 | Served by its provider; not a defect |

**Why L-24 and L-25 stay unused.** Each finding above either has no observable failure (X1, X2, X5, X7), is a residual
already recorded where its item closes (X4, X6), or is out of scope with no user report behind it (X3).

---

## 9. Open questions

- **Q1 — Banner wording and the prompt (D-5).** Decided: a banner line, prompt unchanged. The maintainer may prefer the
  prompt to carry `agent.name`; the PR heading makes that a one-line overturn (`AimonCli.java:107`).
- **Q2 — Is `AnthropicConfig`'s default `claude-sonnet-4-20250514` still served?** D-1 makes memory run on it when
  `llm.model` is absent under anthropic. Wiki generation and the old banner already depended on it. Unmeasured, because
  a successful probe is billed and no criterion needs it. Changing that default belongs to `aimon-llm-anthropic`,
  outside this run.
- **Q3 — What does OpenAI answer for `haiku`?** Left unmeasured on purpose (§6): no shipped bundle sends it after D-2.
- **Q4 — Should the memory fallback refuse instead of tell?** Decided tell (D-1, R1a). If review overturns it, the
  resolver's middle branch becomes the refusal and T2/T3 flip; nothing else moves.
- **Q5 — L-20's rule-six recount of `.defaultModel(` sources.** The build does it before writing the closing note; F3
  lists the eleven main call sites to start from.
- **Q6 — The comment in the `explore.md` frontmatter.** Kept only if T5 shows the subagent parser accepts it (§5.2).

---

## 10. After the build — departures, and what went to the backlog

*Appended 2026-09-11, after implementation. Everything above this section is the body as approved by design review
round 1 (PASS, no blocking findings, eleven non-blocking notes), and it is not edited to look prescient. Three sources
feed this section: the run's `build/deviations.md`, the review's notes, and what was measured while building.*

**Nothing in §3 changed shape.** D-1 through D-5 were built as decided, and every option §3 rejects stays rejected.
What departed is where this record lives, one backlog item, a literal the documentation no longer carries, and a
handful of choices the body left open.

### 10.1 Where the build departed from the body

- **DV-1 — this file.** The body does not say where it lands. It lands here, byte-exact, beside
  [`provider-switch-agent-model-check.md`](provider-switch-agent-model-check.md), whose §11 is the short map §5.5 asks
  for, and it is indexed in [`../README.md`](../README.md). Nesting a body with its own `##` sections inside that
  document's §11 would have meant demoting every heading, and it would no longer be the approved body.
- **DV-2 — one backlog item, where §5.5 and §8 say none.** Q2 has consequences outside this change: after D-1, memory,
  wiki generation, and nameless agents and subagents all run on `AnthropicConfig`'s default when `llm.model` is absent
  under anthropic, and changing that default belongs to `aimon-llm-anthropic`. It is L-24 in
  [`llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md). The register therefore reads 22
  items (open 13 · closed 9), not §5.5's 21 / 12 / 9 / 0. L-25 stays unused, and X1–X7 stay unregistered for §8's
  reasons.
- **DV-3 — no `claude-sonnet-4-20250514` in `default-config.yaml` or the guide** (review note). §5.2 and §5.5 item 2
  write the literal. It belongs to `AnthropicConfig.DEFAULT_MODEL` in another module and would go stale silently —
  the reason D-2 gives against vendor literals. Both texts say "the Anthropic client's default model", and the startup
  line names it when memory is on.
- **DV-4 — the `Task` tool's description has one more sentence** (review note on D-3): "Leave it out unless you were
  given a specific model id". Every fact D-3 fixes is in it. The string is a `MODEL_DESCRIPTION` constant whose javadoc
  says why it names no model, and it uses ASCII hyphens, as these modules' other user-facing strings do. The class
  javadoc's example drops `"model", "haiku"` rather than trading it for another id.
- **DV-5 — the yaml `llm.model` bullet is narrower than §5.2's.** It reads "the agent, and any subagent that names no
  model, only when the agent's definition has no `model.name`": a subagent that names its own model never runs on
  `llm.model`.
- **DV-6 — T6 gains a case with the base path `bundles`** (review note). §6 says C1 proves the parameter reaches the
  text, but every call passed `"agents"`, so C1 stays green with the root hard-coded. The new case does not (§10.3,
  M5).
- **DV-7 — choices the body left open.**
  - T3 is its own class, `AgentSetupFactoryMemoryModelTest`. It also asserts the WARN half of the pair, that exactly one
    line is printed, and that a disabled memory block or a set `llm.model` never consults the client.
  - T2 asserts the captured client's own `getDefaultModelName()` rather than the literal, so it survives a change of
    that default (L-24), and asserts the bundle name too.
  - T5 also asserts that `default-anthropic`'s `explore` names no model of its own, and its javadoc names the `Task`
    tool wiring it copies (review note).
  - F5 keeps its `contains` fragments and adds the file comparison on the path captured from the entry line.
  - Q6: the frontmatter comment is kept. The subagent parser is SnakeYAML, and T5 loads all three files.
  - `memoryModelName`'s terminal-and-log pair follows `createRepresentationStore`, which the review identified as the
    real precedent in place of §5.3's `:979-984`. The comment in `create()` says it runs "before the shell, the queue or
    the stack starts", not that a refusal leaves nothing started: the LLM client already exists by then, and a refusal
    leaves it to `AimonCli`'s exit, as a failed stack build already does (review note on §7).
  - `ReplSession.providerLine` accepts a null agent — a test-built setup carries none — and then shows the client's
    default.
  - Lines 7–8 of #92's record header now point at its §11 too (review note), and the CHANGELOG entry says which three
    sentences of #92's still-unreleased entry it supersedes (review note).

### 10.2 Where the body is wrong

- **C-1 — F3 undercounts the `.defaultModel(` entry points, and Q5 calls them "the eleven main call sites".** Counted in
  both forms, `.defaultModel(` and `::defaultModel`, over every module's `src/main` on the built tree: 10 call sites in
  code, 2 javadoc examples, and no method reference. F3 lists seven of the ten, plus the `Task` tool's constructor call
  in `OrcaSubagentToolProvider`. It misses `DefaultCommandExecutionManager` and `SkillBackedCommandExecutor`, the
  command path, which does not reach `resolveModel`, and `SubagentExecutionEnvironment`'s copy of itself. None of the
  ten invents a name, so D-1's conclusion stands. L-20's closing note carries the table.
- **C-2 — T6 says "the two direct calls"; there were three** — C8's two and `silentWithoutInputs`'s one (review note).
  The compiler found all three.
- **C-3 — §5.5's CHANGELOG plan does not mention #92's entry**, which sits in the same `[Unreleased]` and three of whose
  sentences this change makes false. DV-7 covers it.

### 10.3 What was measured

**The mutations — §6's M1–M4, plus M5.** A script applied each one, requiring exactly one occurrence of the text it
replaced, then ran only the named test classes with `--rerun`. It restored the file from a copy, confirmed the restore
with `cmp`, and ran the same command again. 2026-09-11:

| Mutation | `./gradlew … --rerun --tests` | With the mutation | Restored |
|---|---|---|---|
| M1 — delete the `reportAgentModelMismatch(...)` statement in `decorate()` | `:aimon-cli:test`, `*.AgentSetupFactoryCreateTest` | exit 1; 1 of 2 red: T1 | exit 0; 2 of 2 |
| M2 — the dialectic engine gets `config.getLlmConfig().getModel()` again | the same | exit 1; 1 of 2 red: T2 | exit 0; 2 of 2 |
| M3 — `model: haiku` back in `default-anthropic/agents/explore.md` | `:aimon-cli:test`, `*.BundledSubagentModelTest` and `*.AgentModelProviderCheckTest` | exit 1; 3 of 70 red: T5's `default-anthropic` row and its exact-model test, and the T6 test that replaced `haikuIsSilentByRule` | exit 0; 70 of 70 |
| M4 — `orElse("gpt-4")` back in `SubagentLlmDefaults.resolveModel` | `:aimon-core:test`, `*.SubagentLlmDefaultsTest` | exit 1; 1 of 9 red: the nameless case | exit 0; 9 of 9 |
| M5 — `AgentModelProviderCheck`'s entry line hard-codes `"agents"` | `:aimon-cli:test`, `*.AgentModelProviderCheckTest` | exit 1; 1 of 65 red: DV-6's `bundles` case | exit 0; 65 of 65 |

**Four starts of the real CLI**, 2026-09-11 around 01:58Z, each `./gradlew :aimon-cli:run --args='-c <file>'` with
stdin at end of file, a placeholder API key written into the file, and `cli.colorOutput: false`. No request was sent,
and each process exited 0 after `default-agent> Goodbye!`.

| Configuration | Printed before the banner | `Agent bundle:` | `LLM Provider:` |
|---|---|---|---|
| #105's reproduction — anthropic, no `llm.model`, `default-anthropic`, in-memory memory | the memory line below | `default-anthropic (agent name: default-agent)` | `Anthropic (claude-sonnet-4-5)` |
| #92's reproduction — anthropic, `claude-sonnet-4-5`, `default` | #92's warning, unchanged | `default (agent name: default-agent)` | `Anthropic (gpt-5.6-terra)` |
| The guide's switched configuration — anthropic, `claude-sonnet-4-5`, `default-anthropic` | nothing | `default-anthropic (agent name: default-agent)` | `Anthropic (claude-sonnet-4-5)` |
| The shipped `llm:` and `agent:` blocks — openai, `gpt-5.1`, `default` | nothing | `default (agent name: default-agent)` | `OpenAI (gpt-5.6-terra)`, where `llm.model` is `gpt-5.1` |

The memory line, as the first run printed it:

```text
Peer memory: `llm.model` is not set, so memory runs on the Anthropic client's default model `claude-sonnet-4-20250514`. Set `llm.model` to choose another.
```

It reached `~/.aimon/logs/aimon.log` as a `WARN` from `AgentSetupFactory` on the `main` thread.

**#92's Status link (#107 item 3), on the built site.** `mkdocs build --strict` exits 0 before and after the change —
which is the defect: the check is INFO-level. Built from `9b642cc`, the log carries one INFO line, *"Doc file
'design/llm/provider-switch-agent-model-check.md' contains a link
'../../getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다', but the
doc 'getting-started/aimon-core-integration-via-cli-reference.en.md' does not contain an anchor"*, and the built
`/en/` page links `…/aimon-core-integration-via-cli-reference/#provider-를-바꿀-때--agentname-도-함께-바꾼다`. Built from
this change, no INFO line names either design record, and both locales' pages link
`../../../getting-started/aimon-core-integration-via-cli-reference/` with no fragment, a page that exists in each.

**Not measured.** No live API call was made, as §6 planned: OpenAI's answer to `haiku` (Q3) and whether
`claude-sonnet-4-20250514` is still served (Q2, now L-24) stay unmeasured.

### 10.4 What §9's open questions became

- **Q1 and Q4** — decided as written: a banner line with the prompt unchanged, and telling rather than refusing. Each is
  a one-branch overturn, headed as its own decision in the PR body, and both stay here.
- **Q2** — promoted to L-24 (DV-2).
- **Q3** — stays unmeasured, and moot: no shipped bundle sends `haiku`.
- **Q5** — done. The count is in L-20's closing note, and §10.2 C-1 corrects F3.
- **Q6** — answered: the comment is kept.

### 10.5 One finding outside the diff, beyond §8

§8's X1–X7 stand. The review added one:

- **X8 — `SubagentBehaviorSupport`'s javadoc**, a public SPI in `aimon-core`, describes `resolvedModel()` as "the
  subagent's `model` alias merged with the default". After D-1 that model may carry no name; its `@return … (never
  null)` is still true of the model itself. The file is outside this change's files and is not edited, and CHANGELOG
  names the type among the code that can now find the name empty. It is not registered, for §8's reason: no in-tree
  reader calls `getName().get()` on it.
