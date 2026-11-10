# Design — move the default Anthropic model off `claude-sonnet-4-5`

> Status: **IMPLEMENTED** — `aimon-core` (`ModelCapabilities#supportsThinkingBlockBinding`, the three exact
> `claude-*-5-5` capability rows, 1M context windows and 5.x prices), `aimon-llm-anthropic` (default model, `maxTokens`
> 16000, timeout 600s, `AnthropicModelLifecycle`, the block binding in `AnthropicThinkingResolver`, the drop report
> `AnthropicInputTransformations`, the 400 hint), `aimon-cli` and `aimon-spring-boot-starter` (the new key; 60s no
> longer pinned). Measured live on 2026-11-10 (§8.2).
>
> **[§8](#8-after-the-build--departures-and-what-went-to-the-backlog), appended after the build, is where the build
> departs from this document.** Everything between this header and §8 is the body as approved in design review round 3
> (rounds 1 and 2 failed), kept byte-exact rather than corrected — the house habit in this directory
> ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its citations are at `34ed3c70`. The review
> transcripts, the run's `TASK.md` and the vendor reference pages (`ref/…`) it cites are not in the repository; §8
> reproduces what was found while building.
>
> It is in English, like its siblings in this directory written from an approved run; `docs/design/` is not a
> translation target (`docs/project/documentation-guide.md` §5.1).

Run: `20261110-094050-claude-default-model` · base `main` @ `34ed3c70` · one PR.
Model facts come **only** from `ref/models-current.md`, `ref/migrating-to-sonnet-5-5.md` and
`ref/migrating-to-haiku-5-5.md`. No model id below is guessed.

---

## 1. Problem (restated)

`AnthropicConfig.DEFAULT_MODEL` is `claude-sonnet-4-5`, and the CLI's `default-anthropic` bundle names the same model.
Anthropic retires that model on **2026-11-30**. After that date every deployment that never named a model, and every
CLI user on `provider: anthropic`, fails on every request. The default has to move to `claude-sonnet-5-5`. That alone
is a one-line change. The work is in making sure the move does not break anything else. Sonnet 5.5 and Haiku 5.5 have
breaking API changes:

- Sonnet 5.5 rejects `thinking:{type:"disabled"}` and forced `tool_choice`.
- Haiku 5.5 rejects `budget_tokens`, sampling parameters and prefill.
- Both have adaptive thinking on by default, which counts against `max_tokens`.
- Both bind thinking blocks to the conversation (preserved thinking).

AIMON's capability, context-window and price registries also have to describe the new models on purpose, not by an
accidental prefix match. Today none of the three registries knows `claude-haiku-5-5`. Two of them know nothing about
any 5.x model. The capability registry reaches `claude-sonnet-5-5` and `claude-opus-5-5` only because the
`claude-sonnet-5` and `claude-opus-5` prefixes happen to match them.

## 2. What the audit found (the facts the design rests on)

| Breaking change in the guides | Where AIMON stands today | Verdict |
|---|---|---|
| Sonnet 5.5: `thinking:{type:"disabled"}` → 400 | `AnthropicThinkingResolver.resolve`: `AnthropicThinkingMode.OFF` and `ReasoningEffort.NONE` both **omit** the `thinking` parameter. `disabled` is never sent anywhere (`git grep -i disabled` on `aimon-llm-anthropic/src/main` → javadoc only). | **Safe as is.** OFF on Sonnet 5.5 means the server's default: adaptive thinking at effort `high`. |
| Sonnet 5.5: `between_tools` is the only way to turn thinking off | Not used. | Not needed for correctness. See §3, alternative A2. |
| Sonnet 5.5 / Opus 5.5: forced `tool_choice` (`any`/`tool`) → 400 | `git grep -i "toolchoice\|tool_choice"` over `modules/*/src/main/**` → **zero hits**. Compaction (`DefaultCompactionEngine`), the rolling context engine, structured output and the skill or subagent loops never force a tool. The SDK default is `auto`. | **Safe.** A regression guard test is added (§6). |
| Haiku 5.5: sampling params → 400 | `AnthropicLlmClient.reportOmittedSampling`: API version `2026-10-21` (SDK `anthropic-java` 3.3.0) has no sampling fields. Nothing reaches the wire on **any** model, and a configured value is WARNed once. | **Safe.** |
| Haiku 5.5: `budget_tokens` → 400 | Sent only when the resolved dialect is `BUDGETED`. Without a row, `claude-haiku-5-5` resolves to `UNKNOWN`, so `thinkingMode(EXTENDED)` is **honoured literally → 400**, and `AUTO` sends nothing, with a "dialect unknown" warning. | **Bug → fix with a registry row** (`ADAPTIVE`), which makes the resolver translate EXTENDED to adaptive. |
| Haiku 5.5: assistant prefill → 400 | The client sends what it is given. Core already ends compaction and rolling requests on a user turn (`DefaultCompactionEngine:74,390`, `RollingContextEngine:137`). `AnthropicLlmClient.endsOnAnAssistantMessage` only explains the response afterwards. | **Safe for the built-in paths.** A caller-built prefill now gets a 400 instead of an empty response. Documented, not prevented (§5). |
| Thinking on by default counts toward `max_tokens` | `AnthropicConfig.DEFAULT_MAX_TOKENS = 4096`. The E2E harness uses `maxTokens(4000)`. The CLI bundle sets `maxTokens: 40000`. | **Risk:** with default config on Sonnet 5.5 (effort `high`), a 4096 cap can be spent on thinking, and the turn ends on `max_tokens` with no text. Fix in §4.2. |
| Thinking blocks bound to model + conversation (prefix check, enforced for accounts created on or after 2026-08-31) | Capture is unconditional. `replayThinkingBlocks` defaults to `true`, and `AnthropicMessageConverter` replays the traces of **every** assistant message in the history. Two paths edit the prefix in a way you can predict: (1) `agent/impl/orca/OrcaAgentExecutor.java:1146-1149` renders the system prompt once per **turn**, with memory context and assembled context blocks, so it can change between turns; (2) `DefaultCompactionEngine` (`agent/compact/DefaultCompactionEngine.java`) replaces the head of the history with a summary and keeps a preserved tail (rebuild at `:203-232`; `:588-620` is only the cut-point validation) whose assistant messages **keep their traces**. Only the summarized part goes through `MessageStripper`. `reasoning-traces.md` §7 calls both prefix edits and calls the risk "narrow — Fable 5.1+ only". | **A regression on the default path.** Sonnet 4.5 never ran the check, and Sonnet 5.5 does. On a new account, the first request after either kind of edit is expected to fail with "bound to a different conversation", and every later request fails the same way, because the bad blocks stay in the history. **Handled in this PR** with `drop_block` (§4.5). A warning alone would not be enough. |
| Response may begin with `thinking` blocks; blocks may have an empty `thinking` text | The converter reads blocks by type (`AnthropicOutputBlocks`). Traces carry a signature. | Expected to be safe. A unit test pins "signed block with empty text is captured and replayed" (§6). |
| Sonnet 5.5 puts text between tool calls in `thinking` blocks | The CLI renders text deltas only. | Fails nothing, but the CLI goes quieter between tool calls. Noted in the CHANGELOG. Out of scope to change. |
| `stop_reason: "refusal"` | Already WARNed in `convertResponse`. A content-less refusal throws `LlmClientException` with the stop reason in the message. | Unchanged. |
| Computer-use `computer_20250124` | Not used by core. Browser and computer tools live outside this repo. | Out of scope. |

What the registries say today (`builderWithDefaults()` / `withDefaults()`):

| Name | Capability row | Context window | Price |
|---|---|---|---|
| `claude-sonnet-5-5` | `ADAPTIVE_REFUSING_SAMPLING` via the **`claude-sonnet-5` prefix** (accidental) | default **128K** (wrong; actual 1M) | **unknown** |
| `claude-opus-5-5` | same via the `claude-opus-5` prefix (accidental) | 128K (wrong) | unknown |
| `claude-haiku-5-5` | **unknown** (`UNKNOWN` dialect) | 128K (wrong) | unknown |
| `claude-sonnet-4-5` | `BUDGETED_DIALECT_ONLY` prefix | 200K via `claude-sonnet-4` | $3/$15 via `claude-sonnet-4` |
| `claude-haiku-4-5` | `BUDGETED_DIALECT_ONLY` prefix | 200K | $1/$5 |

## 3. Approach and rejected alternatives

**Chosen approach:**

- Move the default to `claude-sonnet-5-5`.
- Describe the three 5.5 models explicitly in all three registries.
- **Make preserved thinking safe on those models:** whenever this client replays thinking blocks to one of them, it
  sends `thinking.block_binding.prefix_mismatch_behavior: "drop_block"` with the
  `thinking-binding-controls-2026-08-01` beta header. This is gated by a new capability flag (§4.5). A changed prefix
  then costs the stale reasoning, not the request.
- Keep the current meaning of `thinkingMode` (OFF = ask for no thinking beyond the model's default). The wire changes
  only by the binding (§4.5).
- Raise the client's default `max_tokens` so that default thinking fits.
- Add a cheap once-per-model WARN for deprecated or retired model names.
- Add a remediation hint for the preserved-thinking 400, which can still happen on models without the flag.
- Prove all of it with unit tests, plus live tests that **force** the check on whatever account runs them (§6.3).

**How the preserved-thinking regression is handled — options weighed:**

| Option | Verdict |
|---|---|
| **P1. `drop_block` + beta header, gated per model** (chosen) | The guide names it as the fix ("To drop the affected blocks instead, send the `thinking-binding-controls-2026-08-01` beta header and set `thinking.block_binding.prefix_mismatch_behavior: "drop_block"`"). It covers both prefix-editing paths (a cross-turn system-prompt change and compaction) and any future one, all in one place. Blocks that are still valid are kept, so reasoning carries across an unchanged prefix. On older accounts, "setting that field to either value opts the request in", so behaviour is the same for every account age, and the live test becomes valid on any account (reviewer finding (a)). Cost: a capability flag, a beta header, and an `adaptive` parameter that is now sent under OFF on these models. That parameter is the server's own default there, so nothing else changes. |
| P2. Strip traces from the preserved tail at the compaction boundary (core) | Fixes compaction only. A memory change between turns would still fail. It would also throw away OpenAI reasoning items that are not known to be prefix-bound. Not chosen. P1 already covers compaction, because the server drops exactly the invalidated blocks. |
| P3. Default `replayThinkingBlocks` to `false` for adaptive-default models | Always safe, but it turns reasoning replay off on exactly the models it was built for, and re-bills thinking every iteration (`reasoning-traces.md` §7.1). This is the **fallback** if P1's live test fails (§5 F1). |
| P4. Keep a fingerprint of system and tools in the client and strip traces when it changes | The client holds no per-conversation state, and the prefix also includes earlier *messages*, which compaction changes. That means reimplementing the server's check. Not chosen. |
| P5. WARN hint only (the first draft) | Leaves the default path broken on new accounts. Rejected by review. It stays as the message for models **without** the flag. |

Rejected alternatives:

- **A1. Default to `claude-opus-5-5`** (the catalog's "default model for new code"). Rejected because the task names
  Sonnet 5.5 and the user agreed to Sonnet. Sonnet also costs half as much ($2/$10 vs $4/$20). Opus 5.5 also cannot
  turn thinking off at all.
- **A2. Map `AnthropicThinkingMode.OFF` to `{type:"between_tools"}` on Sonnet 5.5** (and to `disabled` on Haiku 5.5).
  Rejected for this PR, for four reasons:
  1. OFF is documented across the code (`AnthropicThinkingMode.OFF` javadoc, `anthropic-thinking.md`) as "send no
     `thinking` parameter; the body is what it was before thinking existed". Changing that is a contract change on
     every model, not a model migration. (§4.5 does change the OFF body on the three 5.5 rows, but only to send the
     server's *own default* `adaptive` type as a carrier for `block_binding`. How much the model thinks is unchanged.
     `between_tools` would change the thinking behaviour itself.)
  2. `between_tools` is a 400 on every other model, and it is a 400 at effort `xhigh`/`max`. It also forbids
     `display` and `block_binding`, and any per-message effort change. So it needs a new per-model capability axis (a
     fifth `ThinkingDialect` state or a new flag), new resolver branches and new findings.
  3. The guide itself says to try adaptive at `low` effort *first*, which AIMON already expresses as
     `reasoningEffort: low` + `thinkingMode: adaptive|auto`.
  4. The Anthropic SDK 3.3.0 does not model `between_tools`. It would need `putAdditionalBodyProperty`.

  Logged as open question Q2. The cost of rejecting it is stated in the CHANGELOG: on Sonnet 5.5, OFF does not stop
  thinking. That cost already exists today on Sonnet 5, Opus 5 and Fable.
- **A3. Rely on the existing `claude-sonnet-5` and `claude-opus-5` prefixes and add only `claude-haiku-5`.** Rejected.
  The task asks explicitly for rows that resolve "not just by an accidental prefix", and the same-family assumption
  already fails on the price axis (Opus 5.5 is $4/$20, Opus 5 is $5/$25).
- **A4. Add the rows as prefixes (`claude-sonnet-5-5`, …).** Rejected for the **capability** registry, where exact rows
  are used instead. The 5.5 ids have **no dated snapshots** ("fixed ID with no date suffix and no separate alias").
  The table's own precedent for that case is `registerGpt6Defaults`: "Exact, like every other OpenAI name this table
  knows by name … a prefix would assert identifiers nobody has seen". Exact rows also leave the existing claim "none
  of these prefixes is a prefix of another" true. A `claude-sonnet-5-5` prefix would nest under `claude-sonnet-5` and
  make registration order load-bearing. For the **price** and **context-window** tables, prefixes are kept, because
  those tables contain only prefixes and treat a window or a price as a family-wide fact
  (`InMemoryModelContextWindowRegistry`'s own comment about `gpt-6`). The one nesting there, `claude-opus-5-5` before
  `claude-opus-5`, follows the existing `claude-opus-4-5`-before-`claude-opus-4` pattern in the price table.
- **A5. Add a `deprecated` / `retiresOn` field to `ModelCapabilities`.** Rejected. `ModelCapabilities` is a
  provider-neutral request-surface descriptor read by both clients. Lifecycle is not a request-surface fact, and a new
  field would ripple into `ModelCapabilityDeclaration`, the CLI yaml, the Spring properties and `ShadowedBuiltInRow`.
  Instead, a small package-private table goes in `aimon-llm-anthropic` (§4.3).
- **A6. Automatic retry with traces stripped on the preserved-thinking 400.** Rejected. A silent retry doubles cost and
  hides a structural issue. `drop_block` (P1, §4.5) is the principled fix and is implemented here. This closes the
  item `reasoning-traces.md` §11 lists as "needs its own design".
- **A7. Change the price table to support Haiku 5.5's two rate cards** (≤100K / >100K prompt). Rejected. `ModelPrice`
  is a flat per-token price used by `costOf(TokenUsage)`, and tiering would change the `ModelPriceTable` SPI. The ≤100K
  card is registered (the agent default range) and the under-estimate above 100K is documented. That keeps the
  table's existing stance: its prices are "convenience defaults, not a billing source of truth". See Q4.

## 4. Concrete changes, by module and file

### 4.1 Default model → `claude-sonnet-5-5`

| File | Change |
|---|---|
| `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicConfig.java:42-43` | `DEFAULT_MODEL = "claude-sonnet-5-5"`. Rewrite the comment: the previous default retires 2026-11-30, and the new one is described by the capability table's exact `claude-sonnet-5-5` row. |
| `modules/aimon-cli/src/main/resources/agents/default-anthropic/agent.md` | `model.name: claude-sonnet-5-5`. **Also remove `temperature: 0.2`.** It never reaches the wire (API 2026-10-21 has no sampling) and today it produces a WARN on the first request of every CLI session. It is shipped config the client can never act on. Keep `maxTokens: 40000`. |
| `modules/aimon-cli/src/main/resources/default-config.yaml:196` | Comment → `claude-sonnet-5-5`. |
| `modules/aimon-cli/src/main/resources/default-config.yaml:49` | `timeout: 60` → commented out, plus the explanatory comment (§4.2 decision 2). |
| `modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonProperties.java:1371-1372` | `timeout` field default `Duration.ofSeconds(60)` → `null`, plus javadoc (§4.2 decision 3). |
| `modules/aimon-core/src/main/java/at/aimon/core/knowledge/wiki/LlmRerankSearchStrategy.java:80` (javadoc) | `claude-haiku-4-5` → `claude-haiku-5-5`. |
| `modules/aimon-cli/src/test/java/at/aimon/cli/e2e/LiveModels.java` | Anthropic default `claude-haiku-4-5-20251001` → `claude-haiku-5-5`. Raise the **Anthropic** client's `maxTokens(4000)` to `maxTokens(16000)`. That is **line 47 only**: line 43 is the OpenAI client's `maxTokens(4000)` and must not change, so no search-and-replace. This is so default thinking does not consume the reply. Update the javadoc. The OpenAI client and its default model stay as they are (out of scope). |
| `CONTRIBUTING.md:216` + `CONTRIBUTING.ko.md:214` | "`claude-haiku-4-5-20251001` by default" → `claude-haiku-5-5`. Lines 102/106 describe the context-engine live rig, which keeps `claude-haiku-4-5`. Leave those. |
| `modules/aimon-llm-anthropic/README.md:134` | Default model cell. Default `maxTokens` cell if that row exists. |
| `docs/getting-started/aimon-core-integration-via-cli-reference{,.en}.md` (lines ~281/291, 287/296) | Bundle model → `claude-sonnet-5-5`. |
| `docs/overview/architecture{,.en}.md` (152/161) | Example `name:` → `claude-sonnet-5-5`. |
| `docs/features/llm/llm-usage-metering{,.en}.md` (124/131) | Example `claude-haiku-4-5` → `claude-haiku-5-5`. |
| `docs/design/llm/model-name-resolution.md` §(lines 165-190) | Default table row, and the "default is X, four reasons" paragraph rewritten for `claude-sonnet-5-5`. Keep the 2026-09-11 measurement rows as history of the previous default, under a sentence saying it retires 2026-11-30. Add a row for the new default's live check (filled from the E2E result, §6.3). No `.en.md` exists for `docs/design/llm/*`. |
| `docs/design/llm/anthropic-thinking.md:352` | "client default model" → `claude-sonnet-5-5`, which is ADAPTIVE, so `auto` sends adaptive. **This changes the meaning of that paragraph** and must be re-read, not search-replaced. |
| `docs/design/integration/spring-boot-starter.md:544` | Example `claude-sonnet-4-20250514` (already retired) → `claude-sonnet-5-5`. |
| Lines that mention `claude-sonnet-4-5` **as a BUDGETED example** (`anthropic-thinking.md:166`, `model-capabilities.md:419,436`, the cli-reference and embedding guides at 466/488, 483/508) | **Do not change.** They describe measured facts about that model, which stays registered. Add "(deprecated, retires 2026-11-30)" where a model list is presented to a user (the guides). |
| `docs/backlog/llm-config-surface-open-items.md` (51 hits) | Historical backlog. Not edited, except where an *open* item states the current default. The implementer checks with `grep -n "기본 모델\|default model"`. |

The Spring Boot starter and `aimon-bootstrap` have **no** own default model string. They copy the configured model
only when it is set (`AimonLlmAutoConfiguration:237`), so they inherit `AnthropicConfig`'s default. No change.

### 4.2 Default `max_tokens` (consequence of thinking-by-default)

`AnthropicConfig.DEFAULT_MAX_TOKENS` changes from `4096` to **`16000`**. Reasons:

- The guide's own example uses 16000.
- Thinking counts against `max_tokens` on Sonnet 5.5 (effort `high` by default) even under `thinkingMode OFF`.
- `max_tokens` is a ceiling, not a charge.

(`anthropic-java` 3.3.0 has no client-side guard against large non-streaming `max_tokens`, unlike the Python and TS
SDKs, so that is not a constraint here.)

**Timeout interaction, settled now.** In anthropic-java 3.3.0, `ClientOptions.timeout(Duration)` sets
`Timeout.request` (`OkHttpClient.kt:292`), and that becomes OkHttp's `callTimeout` (`OkHttpClient.kt:69`):
"the maximum time allowed for a complete HTTP call … as well as reading the response body" (`Timeout.kt:44-54`).
`read()` also defaults to `request()`. **The configured timeout is therefore a total ceiling on every call,
streaming included.** When it is hit the SDK retries, and each attempt is billed. Thinking is now on by default on
the default model (effort `high`), so one response routinely takes longer than it did on Sonnet 4.5. Today three
values set that ceiling:

| Where | Today | Who it applies to |
|---|---|---|
| `AnthropicConfig.DEFAULT_TIMEOUT` (`AnthropicConfig.java:45`) | 60s | library users who set no timeout |
| CLI `default-config.yaml:49` `llm.timeout: 60` | 60s, **shipped uncommented** | every CLI user, both providers. It is applied only when non-null (`LlmClientFactory.java:91-92,227-228`), and `LlmProviderConfig.timeout` is an `Integer` that defaults to `null` |
| Spring `AimonProperties.Llm.timeout` (`AimonProperties.java:1372`) | `Duration.ofSeconds(60)` field default | every starter user, both providers. Copied when non-null (`AimonLlmAutoConfiguration.java:243-244,462-463`), which is always, given the field default |

Raising only `DEFAULT_TIMEOUT` would leave the default CLI path (the `default-anthropic` bundle, `maxTokens: 40000`,
streaming) and every Spring app at a 60s total. **Decision:**

1. `AnthropicConfig.DEFAULT_TIMEOUT` 60s → **600s**. Ten minutes covers the bundle's 40000-token ceiling at a slow
   output rate. A ceiling costs nothing when responses are fast, while a too-short ceiling costs a cut-off reply plus
   billed retries.
2. **CLI: comment out `timeout: 60`** in `default-config.yaml`. In its place, write a comment saying that unset means
   each provider client's own default (Anthropic 600s, OpenAI 60s, unchanged), that the value is a total per-call
   ceiling including streaming, and that a value set here applies to both providers. The CLI's OpenAI behaviour does
   not change: `OpenAIConfig.DEFAULT_TIMEOUT` is also 60s, so out-of-scope OpenAI defaults stay as they are.
3. **Spring: the `AimonProperties.Llm.timeout` field default becomes `null`** (unset). Each client's default then
   applies. Again, OpenAI stays at 60s. `requirePositive(Duration, …)` already accepts `null`
   (`AimonProperties.java:948-951`), and the copy sites already check for non-null. Update the property javadoc
   ("Per-request timeout for the vendor client" → "total per-call ceiling, streaming included; unset = the client's
   default: Anthropic 600s, OpenAI 60s"). Update any starter test that asserts 60s.
   Docs: `README.md:184` shows `timeout: 60` in an **openai** example. It stays as an explicit value, but a trailing
   comment ("total per-call ceiling; unset = client default") is added, and `README.ko.md` is updated if it exists
   (checked: only `README.md` is present). The guides' `llm.timeout` mentions concern config expansion and need no
   change.
4. The `AnthropicConfig` javadoc on `timeout` and `maxTokens` states the pairing and the SDK semantics in two
   sentences.
5. CHANGELOG (§4.6): Anthropic default timeout 60s → 600s, and the CLI and Spring no longer pin 60s for both providers.
   Anyone who wants the old ceiling sets `llm.timeout: 60` / `aimon.llm.timeout=60s`.

This is a **breaking default change** and goes in the CHANGELOG. It does not affect anyone who sets `maxTokens`, and
that includes the CLI bundles (40000) and every Spring property user who sets it. Verify, without assuming, that no
code path derives a budget or a context reservation from `AnthropicConfig.getMaxTokens()` in a way that a larger value
breaks. `AnthropicThinkingBudgets.budgetFor` only gets more headroom. Grep `getMaxTokens()` in `aimon-llm-anthropic`
and `aimon-spring-boot-starter`. Open question Q3 covers keeping 4096.

### 4.3 Registries (aimon-core, `at.aimon.core.llm..`)

**`capability/InMemoryModelCapabilityRegistry.java` — `registerAnthropicDefaults`**

Add three **exact** rows, all `ADAPTIVE_REFUSING_SAMPLING` (sampling refused; adaptive dialect: `budget_tokens` → 400
on all three per the guides; Opus 5.5 "thinking can't be disabled, effort is the only control"):

```java
// Current generation, transcribed from the vendor catalog (2026-11): claude-sonnet-5-5, claude-opus-5-5 and
// claude-haiku-5-5 are adaptive-only and refuse non-default sampling. Exact rather than prefix: these ids have no
// dated snapshots (registerGpt6Defaults states the same rule), and an exact row keeps "no claude-* prefix nests
// under another" true. Two of them were already reached by the claude-sonnet-5 / claude-opus-5 prefixes; that was
// a coincidence of spelling, and the Haiku one was not reached at all -- EXTENDED was sent literally and 400'd.
builder.register("claude-sonnet-5-5", ADAPTIVE_REFUSING_SAMPLING)
       .register("claude-opus-5-5", ADAPTIVE_REFUSING_SAMPLING)
       .register("claude-haiku-5-5", ADAPTIVE_REFUSING_SAMPLING);
```

Facts the row deliberately does **not** state (comment them, same rule as the existing block): forced `tool_choice`
refusal (no client sends it), `between_tools` (no client sends it; Q2), the effort default (`high` for Sonnet, `medium`
for Haiku and Opus — a server default, not a request-surface fact). `supportsToolsWithReasoning` and the other OpenAI
flags stay fail-open, for the reason already given in the block.

Also update:

- The class javadoc paragraph "**The table describes two vendors** … six prefixes that state two flags … five that
  state the dialect alone" so that it counts three exact rows too.
- The `withDefaults()` javadoc "eleven Anthropic prefixes".
- `registerAnthropicDefaults`' javadoc "eleven".
- The comment at lines 421-423 ("The default is now claude-sonnet-4-5 itself, which the row below describes") →
  "the default moved to claude-sonnet-5-5 (exact row above); claude-sonnet-4-5 is deprecated, retires 2026-11-30, and
  keeps its measured row so a deployment that pins it still resolves until then".
- `AgentModelProviderCheck.java:98` javadoc ("the eleven `claude-*` prefixes") — wording only. `FAMILY_PREFIXES` is
  `"claude-"` and needs no change.

**Provenance caveat:** these rows are *documentation-derived* (the catalog and the migration guides), like the
`claude-mythos` row. The live E2E in §6.3 confirms Sonnet 5.5 and Haiku 5.5 on the adaptive path. Opus 5.5 stays
documentation-only unless a probe is run (Q5). Say so in the comment, the way the `claude-mythos` comment does.

**`InMemoryModelContextWindowRegistry.withDefaults()`**

Add 1M windows for the families the catalog lists at 1M:

```java
.registerPrefix("claude-sonnet-5", ModelContextLimits.ofContextWindow(1_000_000))   // Sonnet 5, 5.5
.registerPrefix("claude-opus-5",   ModelContextLimits.ofContextWindow(1_000_000))   // Opus 5, 5.5
.registerPrefix("claude-haiku-5",  ModelContextLimits.ofContextWindow(1_000_000))   // Haiku 5.5
.registerPrefix("claude-fable-5",  ModelContextLimits.ofContextWindow(1_000_000))
.registerPrefix("claude-mythos",   ModelContextLimits.ofContextWindow(1_000_000))
```

There is no collision with the existing `claude-4`, `claude-opus-4`, `claude-sonnet-4` and `claude-haiku-4` prefixes,
because `claude-sonnet-5…` does not start with `claude-sonnet-4`. Fix the class javadoc list ("claude-3.x,
claude-4.x" → "+ claude-5.x"). `claude-haiku-4-5` stays at 200K, which is correct.

The 4.6–4.8 Opus and Sonnet models are 1M but currently resolve to 200K. That under-reports, which is safe (compaction
runs earlier). They are not touched here (Q6).

**`cost/InMemoryModelPriceTable.withDefaults()`**

Rows are `perMillionUsd(input, output, cacheRead, cacheWrite5m)`, with cache read at 0.1x and 5-minute write at 1.25x,
per the table's existing convention:

| Prefix (registration order) | Price | Source |
|---|---|---|
| `claude-sonnet-5` | 2.00 / 10.00 / 0.20 / 2.50 | sonnet-5-5 guide: "same prices as Sonnet 5 — $2/$10, 5-minute cache writes $2.50, cache reads $0.20" |
| `claude-haiku-5` | 0.10 / 0.50 / 0.01 / 0.125 | haiku-5-5 guide, ≤100K-prompt card (A7, Q4) |
| `claude-opus-5-5` | 4.00 / 20.00 / 0.20 / 5.00 | catalog: "$4 / $20 per MTok; cache reads $0.20" |
| `claude-opus-5` (after `-5-5`) | 5.00 / 25.00 / 0.50 / 6.25 | catalog: Opus 5 "at Opus 4.8's pricing ($5/$25)"; 0.1x / 1.25x convention |

**The Haiku 5.5 under-estimate is not an edge case**, and the comment beside the row has to say so. This PR also
gives Haiku 5.5 a 1M window, so compaction starts near 800K (`DEFAULT_AUTO_COMPACT_RATIO = 0.8`). Prompts above 100K
become routine in long sessions, and on those requests the table under-reports cost by up to 5x ($0.50/$2.50 billed
vs $0.10/$0.50 recorded). The same sentence goes in the CHANGELOG. An operator who budgets on these figures overrides
the row, or registers a lower context window to keep prompts under 100K.

`claude-opus-5-5` must be registered **before** `claude-opus-5`, with a comment mirroring the existing
`claude-opus-4-5` note. `claude-sonnet-4` ($3/$15) keeps matching `claude-sonnet-4-5`. Fable and Mythos prices are not
added (Q6).

### 4.4 Anthropic client (`aimon-llm-anthropic`)

1. **Deprecated or retired model WARN** (task item 3, the cheap version). Add a package-private final class
   `AnthropicModelLifecycle`, immutable, with no state beyond a static table:

   ```java
   // name prefix (lower-case) -> lifecycle entry; prefix so the dated snapshot claude-sonnet-4-5-20250929 matches.
   DEPRECATED: "claude-sonnet-4-5" -> retires 2026-11-30, successor "claude-sonnet-5-5"
   RETIRED:    "claude-sonnet-4-2025", "claude-opus-4-2025", "claude-sonnet-4-0", "claude-opus-4-0",
               "claude-opus-4-1", "claude-3-", "claude-2." -> "is retired; requests 404"
   static Optional<String> warningFor(String modelName)   // the full sentence, or empty
   ```

   - `AnthropicLlmClient.buildRequest` (the one place the wire model name is resolved) calls it and passes the result
     to the existing `reportDivergence("modelLifecycle@" + modelName, …)`. That logs once per model name per client,
     and it covers both the config default and per-request `LlmModel` overrides. The CLI's agent-definition model
     reaches the wire as such an override, so a constructor-only check would miss it.
   - `claude-haiku-4-5` and `claude-opus-4-5` are "Active" in the catalog and get **no** warning.
   - `claude-3-` is a safe prefix for retired names, because the whole 3.x line is retired. The `claude-opus-4-2025` /
     `claude-sonnet-4-2025` prefixes match only the retired `-20250514` snapshots, not `claude-opus-4-5`.
   - A WARN, never a refusal. Nothing is blocked before the server decides.
2. **Preserved-thinking 400 hint**, for the models §4.5 does **not** cover: names without the flag, gateway renames,
   and Fable 5.1, Mythos 5.1 and Opus 5. In `AnthropicLlmClient.mapSdkFailure` (or in `AnthropicExceptionMapper`,
   wherever the message text is available), when the mapped exception is `LlmInvalidRequestException` **and** its
   message contains `bound to a different conversation`, call `reportDivergence("thinkingBlockBindingRejected", …)`.
   The hint says:
   - The model checks that system, tools and earlier messages are unchanged since a thinking block was produced, and
     AIMON re-renders the system prompt per turn and compacts history.
   - Remedies: declare `supportsThinkingBlockBinding: true` for the model name, if the model is one the vendor says
     accepts `block_binding`; or set `replayThinkingBlocks(false)` (CLI `llm.anthropic.replayThinkingBlocks: false`,
     Spring `aimon.llm.anthropic.replay-thinking-blocks=false`).

   Confirm the exact keys in `docs/design/llm/configuration-surface.md`.
   - This applies with or without `baseUrl`. The existing gateway hints are gated on `baseUrl`, and this one must not
     be.
   - The exception itself is unchanged. The hint goes beside it, as the existing hints do.
3. **No change for the model migration itself** to `AnthropicThinkingBudgets` or the converter: the registry row is
   what fixes Haiku 5.5 EXTENDED and AUTO. `AnthropicThinkingResolver`, `AnthropicThinkingResolution` and the
   request builder **do** change, but only for the block binding (§4.5). Javadoc touch-ups:
   - `AnthropicThinkingMode` class javadoc: the split list adds "Sonnet 5.5, Opus 5.5, Haiku 5.5 are adaptive only".
     The OFF javadoc names Sonnet 5.5 as an always-on model where OFF does not stop thinking, and points to
     `reasoningEffort: low` as the lever.
   - `AnthropicThinkingDisplay`: no change needed.

### 4.5 Preserved thinking: `drop_block` on the models that run the check

**New capability flag** `ModelCapabilities.supportsThinkingBlockBinding()`. The fail-open value is `false`: send no
binding, which keeps the body byte-identical to today. It follows the path `supportsReasoningSummary` took, the most
recent flag added, through every file that flag touches:

- `ModelCapabilities` (field, builder, getter, `equals`/`hashCode`/`toString`).
- `ModelCapabilityDeclaration` (an optional `Boolean`, resolved into the descriptor).
- `ShadowedBuiltInRow`: `true` is non-fail-open, so a declaration that omits it on a covered name is reported.
- CLI `ModelCapabilityConfig` + `LlmClientFactory` + `default-config.yaml` comment, key
  `llm.modelCapabilities.<model>.supportsThinkingBlockBinding`.
- Spring `AimonProperties` (`aimon.llm.model-capabilities.<model>.supports-thinking-block-binding`) + its translator.
- Docs: `model-capabilities.md`, `configuration-surface.md`, and the two getting-started guides with their `.en.md`.

It is a request-surface fact: "this model accepts `thinking.block_binding`". That is why it belongs in
`ModelCapabilities`, unlike lifecycle (A5). It is vendor-shaped on a neutral type, with the same precedent and the same
justification as `thinkingDialect`. The OpenAI client never reads it.

**Rows.** `true` on the three exact rows `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5`. The guides say all
three run the history-editing check and accept `block_binding` with adaptive thinking. Define a fourth constant,
`ADAPTIVE_REFUSING_SAMPLING_BINDING`, built from the same builder so the shared flags cannot drift.
**Not** set on:

- the `claude-fable-5` / `claude-mythos` prefixes: they also cover Fable 5 and Mythos 5, and the guides give no
  `block_binding` evidence for those;
- `claude-sonnet-5` / `claude-opus-5`: Sonnet 5 does not run the check.

Those names keep today's body. A new account that hits the check there gets the hint from §4.4.2. Listed in Q6.

**Wire shape**, decided in `AnthropicThinkingResolver`, the one place that owns the `thinking` parameter. The binding
is added only when **all** of the following hold:

- the model's row says `supportsThinkingBlockBinding`;
- `config.isReplayThinkingBlocks()` is true (with replay off there is nothing to bind, and the body stays as it is
  today);
- the resolved dialect is `ADAPTIVE`, or no parameter was going to be sent at all.

| Resolution without binding | With binding (flag + replay on) |
|---|---|
| adaptive parameter (AUTO / ADAPTIVE / translated EXTENDED) | the same object plus `"block_binding": {"prefix_mismatch_behavior": "drop_block"}` |
| no parameter (OFF, `ReasoningEffort.NONE`) | `{"type": "adaptive", "block_binding": {...}}`, a **carrier-only** parameter (see below). No `display` and no `output_config.effort`. On these three models, adaptive at the server-default effort **is** what an omitted parameter means, so the only change is the binding. |
| budgeted parameter | Not reachable: the flag is only set on ADAPTIVE rows. A *declared* row that states the flag with a non-ADAPTIVE dialect gets no binding, plus one `reportDivergence("thinkingBlockBindingWithoutAdaptive@" + model)`, because the guide says `block_binding` "works only with `{type: "adaptive"}"`. |

**Carrier-only parameters must not silence the OFF findings.** `AnthropicThinkingResolution.findingsToReport()`
(`AnthropicThinkingResolution.java:74-76`) chooses which findings survive by `parameter != null`. It keeps
`EXPLAINS_ABSENCE` findings only when no parameter is sent. `reasoningEffortWithThinkingOff` (`AnthropicThinkingResolver.java:328`)
and `thinkingDisplayWithThinkingOff` (`:358`) are `EXPLAINS_ABSENCE`. A non-null carrier would therefore drop both
warnings on the new default model, even though the effort or display they describe still reaches nothing. Fix:

- `AnthropicThinkingResolution` gains `requestsThinking()`, a boolean set by the resolver, separate from
  `parameter()`:
  - `true` whenever the resolver chose a thinking shape on its own merits (adaptive or budgeted, as today);
  - `false` for no parameter, and for a carrier-only parameter added solely to hold `block_binding`.

  Builder: `parameter(p)` sets both. A new `bindingCarrier(p)` sets the parameter but leaves `requestsThinking`
  false. The class javadoc states the invariant `requestsThinking ⇒ parameter present` (the reverse does not hold).
- `findingsToReport()` filters on `requestsThinking`, not on `parameter != null`. The javadoc of `Scope` is reworded
  from "the request carries no thinking" to "the request asks for no thinking". So under OFF on a flagged row with
  replay on, `reasoningEffortWithThinkingOff` and `thinkingDisplayWithThinkingOff` are still reported, once each,
  word for word.
- `AnthropicLlmClient.reportIfReplayIsOffWhileThinking` (`:585`) takes the resolution, not the
  `Optional<ThinkingConfigParam>`, and gates on `requestsThinking()`. A carrier never exists when replay is off, so
  the warning stays dead code for carriers by construction. The gate is there for clarity, not to change behaviour.
- The implementer greps every other reader of `resolution.parameter()` / `resolvedThinking.parameter()` in
  `aimon-llm-anthropic/src/main`, and for each one decides whether it means "on the wire" (keep `parameter()`) or "the
  operator asked for thinking" (switch to `requestsThinking()`). The decision goes in the PR. Known readers:
  - the request builder, which puts the parameter on the wire (keeps `parameter()`);
  - the `output_config` guard at `AnthropicLlmClient.java:511-515`, which is unaffected because a carrier has no
    effort;
  - `reportIfReplayIsOffWhileThinking` (above).

- `block_binding` is written with `ThinkingConfigAdaptive.Builder.putAdditionalProperty`, the escape hatch the class
  already uses for `display`.
- The `thinking-binding-controls-2026-08-01` beta header goes on the request params with `putAdditionalHeader`
  (`AnthropicLlmClient.java:411` already uses it), on the blocking path and on the streaming path. Carry it in
  `AnthropicThinkingResolution`, for example `requiresBetaHeader()`, so the request builder makes no decision of its
  own.
- If a request already carries an `anthropic-beta` value, values are comma-joined. Today none does; a unit test pins
  the joining.

**OFF's javadoc changes.** It no longer says "the body is what it was before thinking support existed". It says: no
thinking is *requested*; on a model whose row states block binding, the server-default `adaptive` type is sent to
carry the binding, and nothing about how much the model thinks changes. `anthropic-thinking.md` §4.1's mode × dialect
table gains this column. `reasoning-traces.md` §7 drops "narrow — Fable 5.1+" and §11's `drop_block` open item, and
points here instead.

**Reporting drops.** With the beta header, the response carries a top-level `input_transformations` array listing
dropped blocks. SDK 3.3.0 does not model it, so read it through `Message._additionalProperties()` on the blocking path
and the `message_start` / `message_delta` additional properties on the streaming path. Report it with the existing
`reportRecurringDivergence("thinkingBlocksDropped@" + model, …)`, which fires on the 1st, 10th, 100th … drop, at WARN.
Group the count by each entry's `reason` and put the reason strings in the message, with one signature per reason
(`thinkingBlocksDropped@<model>@<reason>`). Not every drop is a prefix edit: the guide also lists model binding (a
`/model` switch or a router change) and account binding (`organization_binding_mismatch`). The text says: N thinking
blocks were dropped (reason: `<reason>`); the model re-derives that reasoning. Add a cause hint only for the
prefix-mismatch reason ("the prompt prefix changed: a system prompt re-rendered on a new turn, compaction, or the
rolling context window"). If the property is absent or unparseable, say nothing. Reporting must never fail a request.

**Rolling context engine.** `RollingContextEngine` drops the oldest messages once its window fills, so on a flagged
model with replay on, nearly every later request edits the prefix and loses the replayed blocks. Under `drop_block`
that is correct, but those deployments effectively lose reasoning replay, and the 1/10/100 WARN fires. State this in
`reasoning-traces.md` §7 and in the CHANGELOG line so it is not read as a bug.

### 4.6 CHANGELOG (`[Unreleased]`, new top section)

`### Changed (breaking default): Anthropic default model claude-sonnet-4-5 → claude-sonnet-5-5`. Bullets:

- The default model, plus the CLI `default-anthropic` bundle. The bundle also drops its inert `temperature: 0.2`.
- Default `maxTokens` 4096 → 16000, and why: thinking is on by default and counts toward the cap.
- Default Anthropic `timeout` 60s → 600s. The timeout is a **total** per-call ceiling (OkHttp `callTimeout`),
  streaming included, and a hit is retried and billed. The CLI's shipped `llm.timeout: 60` is now commented out, and
  Spring's `aimon.llm.timeout` no longer defaults to 60s, so each provider's client default applies: Anthropic 600s,
  OpenAI 60s, unchanged. To restore the old ceiling, set `llm.timeout: 60` / `aimon.llm.timeout=60s`.
- On a flagged model with the rolling context engine, the window drops old messages on nearly every request once it
  fills, so replayed blocks are dropped and the drop WARN fires. This is expected, not a bug.
- **Behaviour on the new default:**
  - Adaptive thinking runs even with `thinkingMode: off`. Use `reasoningEffort: low` with `auto`/`adaptive` to think
    less.
  - Text between tool calls may arrive as thinking blocks.
  - **Preserved thinking:** on `claude-sonnet-5-5`, `claude-opus-5-5` and `claude-haiku-5-5`, with
    `replayThinkingBlocks: true` (the default), requests carry
    `thinking.block_binding.prefix_mismatch_behavior: drop_block` and the `thinking-binding-controls-2026-08-01` beta
    header. Under `thinkingMode: off` this means `thinking: {type: adaptive}` is now sent, which is the server default
    on those models. When the prompt prefix changes (a system prompt re-rendered on a new turn, client-side
    compaction), stale thinking blocks are dropped instead of failing the request, and the drop is WARNed on the 1st,
    10th, 100th … occurrence. New capability flag `supportsThinkingBlockBinding` (CLI
    `llm.modelCapabilities.<m>.supportsThinkingBlockBinding`, Spring `…supports-thinking-block-binding`). Other models
    that run the check (Fable 5.1, Mythos 5.1) are not flagged. If they get the 400, the log names both remedies.
  - Cost: $2/$10, versus $3/$15 for the previous default.
  - The Haiku 5.5 price row uses the ≤100K-prompt card. With the 1M window, long sessions routinely go past 100K and
    are under-reported by up to 5x.
- **Pin the old model until 2026-11-30:** set `model: claude-sonnet-4-5`. It now logs a deprecation WARN.
- **Registry:**
  - Capability: exact rows for `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5`. Fixes
    `thinkingMode: extended` and `auto` on `claude-haiku-5-5`.
  - Context window: 1M for the `claude-{sonnet,opus,haiku,fable}-5*` and `claude-mythos` families.
  - Price: Sonnet 5.x, Haiku 5.5 (≤100K card), Opus 5.5, Opus 5.
- **Build only:** the live E2E defaults to `claude-haiku-5-5` and `maxTokens` 16000.

### 4.7 Translations

| Edited canonical | Translation to update in the same commit |
|---|---|
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` | `.en.md` |
| `docs/getting-started/embedding-agent-in-application.md` (only if the "deprecated" note is added) | `.en.md` |
| `docs/overview/architecture.md` | `.en.md` |
| `docs/features/llm/llm-usage-metering.md` | `.en.md` |
| `docs/design/llm/*.md` (incl. `anthropic-thinking.md`, `reasoning-traces.md`, `model-name-resolution.md`), `docs/design/integration/spring-boot-starter.md` | none exists (checked: no `docs/design/llm/*.en.md`; integration checked below) |
| `CONTRIBUTING.md` (root: English is canonical) | `CONTRIBUTING.ko.md` |
| `docs/design/llm/model-capabilities.md`, `configuration-surface.md` (new flag, §4.5) | none |
| `CHANGELOG.md`, `modules/aimon-llm-anthropic/README.md` | none |

`docs/design/integration/spring-boot-starter.md` has no `.en.md`. Checked: `ls docs/design/integration/ | grep spring`
→ only `spring-boot-starter.md`. Record this in the PR body.

For each `.en.md`, set `source_commit` to the canonical's commit **before** this change. Then run
`python3 scripts/check-translation-staleness.py`, `check-translation-structure.py` and `check-doc-links.py`. Structure
must stay identical: same table row counts, and no headings added on one side only.

## 5. Failure modes and handling

- **F1 — preserved-thinking 400 on the default path** (the highest risk). The setup: Sonnet 5.5 thinks by default,
  traces are replayed by default, AIMON edits the prefix in two predictable ways, and the account was created on or
  after 2026-08-31 or opted in. The two edits are a system prompt re-rendered on the next turn
  (`agent/impl/orca/OrcaAgentExecutor.java:1146-1149`, memory and context blocks) and client-side compaction that keeps
  a tail with traces (`agent/compact/DefaultCompactionEngine.java:203-232`). The rolling context engine's window also
  edits the prefix (§4.5). Then the first request after the edit, and every request after it, fails with
  ``Invalid `signature` in `thinking` block. The block is bound to a different conversation.``
  - *Handling in this PR:* `drop_block` on the three 5.5 rows (§4.5). The hint in §4.4.2 covers models without the
    flag.
  - *Proof:* the gated live test in §6.3 step 3. It **forces** the check on any account age (reviewer finding (a)).
    It covers a cross-turn system change and a post-compaction history (finding (b)), and includes a control that
    shows the probe *would* see a 400.
  - *If the beta header or `block_binding` is refused* (a 400 naming either one), or the forced post-compaction case
    still fails with `drop_block`: implement fallback **P3** in this PR. Set the `supportsThinkingBlockBinding` rows to
    `false`, and make replay off the default for the three 5.5 models through a new resolution in the client (sketch:
    `replayThinkingBlocks` becomes a tri-state whose unset value means "on, except for models whose row marks
    preserved thinking without binding"). The orchestrator **halts and reports to the user before building P3**,
    because it re-bills thinking on every iteration and changes a documented default. That decision belongs to the
    user, not to the dev agent.
  - *Residual:* a new account using Fable 5.1, Mythos 5.1 or a gateway rename of a 5.5 model still meets the 400. The
    hint names both remedies (declare the flag, or turn replay off). This is not a regression: those names behave as
    they did before this PR.
- **F2 — `max_tokens` truncation with no text** (`stop_reason: max_tokens` after a thinking block). This throws "No
  content blocks…" or returns empty text. *Mitigated* by the 16000 default (§4.2) and by the E2E harness cap. The
  existing truncation WARN still fires.
- **F3 — a user pins `claude-haiku-5-5` with `thinkingMode: extended` + `thinkingBudgetTokens`.** After the row, this
  is translated to adaptive at the nearest effort rung, with the existing WARN (`thinkingDialectTranslated`). Before
  the row it was a 400. A unit test covers it.
- **F4 — a user sets `reasoningEffort` (or `thinkingDisplay`) with OFF on Sonnet 5.5.** The existing
  `reasoningEffortWithThinkingOff` / `thinkingDisplayWithThinkingOff` findings must still fire. Under replay-on, the
  request now carries a carrier-only parameter, and without the `requestsThinking()` split (§4.5) the
  `parameter != null` filter would drop both findings silently. With the split, they fire once each, word for word.
  Their text already says "a model whose thinking is on by default still thinks". A unit test pins this (§6.1).
- **F5 — a caller-built prefill on Haiku 5.5** (or any 5.5 model) now returns HTTP 400 instead of an empty response.
  It surfaces as `LlmInvalidRequestException` with the server's text. Documented in the CHANGELOG, not prevented: the
  client cannot know what the trailing assistant message was for.
- **F6 — a gateway or Bedrock name** (`anthropic.claude-sonnet-5-5`) does not match the exact row. It falls back to
  `unknown()`, which is the existing documented fail-open (OFF omits; AUTO warns). The remedy is unchanged: declare the
  name under `llm.modelCapabilities`. No new code.
- **F7 — `claude-sonnet-4-5` used after 2026-11-30.** It gets 404 `not_found_error`, mapped as today. The deprecation
  WARN, logged once and earlier, is the advance notice.
- **F8 — the lifecycle WARN misfires on an active model.** This is prevented by keeping the table to the exact
  catalog entries plus unit tests that assert `claude-haiku-4-5`, `claude-opus-4-5`, `claude-sonnet-4-6` and
  `claude-sonnet-5-5` produce no warning.
- **F9 — the live E2E needs more effort than Haiku 5.5's default `medium` to write correct code** and reports
  `answer=WRONG`. That is a model-quality result, not a client failure. Record it, and rerun on Sonnet 5.5 through
  `AIMON_LLM_TEST_ANTHROPIC_MODEL`. Keep runs few.

## 6. Test strategy

### 6.1 Unit tests (all in `./gradlew test` / `checkAll`)

- `InMemoryModelCapabilityRegistryTest`:
  - Each of `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5` resolves to `ADAPTIVE` +
    `supportsSamplingParameters=false`.
  - Each is matched by an **exact** row. Assert through the shadow-warning label, or by building
    `builderWithDefaults().registerPrefix("claude-sonnet-5", <different>)` and asserting `claude-sonnet-5-5` still
    resolves to the built-in exact row. That proves it is not reached by the prefix.
  - Case-folded look-up (`Claude-Haiku-5-5`) works.
  - `claude-sonnet-4-5` and `claude-sonnet-4-5-20250929` still resolve to `BUDGETED`.
  - Update any test that counts rows or the "eleven" wording.
- `DeclarationShadowWarningTest`: a declaration for `claude-haiku-5-5` that omits `supportsSamplingParameters` now
  produces the shadow WARN naming `exact row 'claude-haiku-5-5'`.
- `InMemoryModelContextWindowRegistry` test (find or create it next to the existing core tests): the three 5.5 names
  resolve to 1,000,000; `claude-haiku-4-5` stays 200,000; an unknown name stays 128,000.
- `InMemoryModelPriceTableTest` / `CostSummaryTest`: Sonnet 5.5, Haiku 5.5, Opus 5.5 and Opus 5 prices, with Opus 5.5
  ≠ Opus 5 (pins the ordering). `claude-sonnet-4-5` is still $3/$15.
- `AnthropicConfigTest:30,34`: default model `claude-sonnet-5-5`, default maxTokens 16000. Line 375's comment about
  the default's row → the new exact row. Assert that `withDefaults().resolve(default model)` is `ADAPTIVE`, so the
  default is always described by a row: that is the existing intent of the #116 test.
- `AnthropicThinkingRequestTest` / `AnthropicThinkingDialectTest` (request-body tests that need no network): with
  `model claude-haiku-5-5`:
  - EXTENDED + budget → body has `thinking.type=adaptive` + `output_config.effort`, never `budget_tokens`, and the
    translation finding fires.
  - AUTO → adaptive, with no "dialect unknown" finding.
  - OFF, replay on → `thinking == {"type":"adaptive","block_binding":{"prefix_mismatch_behavior":"drop_block"}}`
    exactly. No `display`, no `output_config`, the beta header present.
  - OFF, `replayThinkingBlocks(false)` → no `thinking` key, no `output_config`, no beta header (today's body).
- **OFF findings survive the carrier** (review 2). On `claude-sonnet-5-5`, with OFF, replay on, `reasoningEffort:
  MEDIUM` and `thinkingDisplay: SUMMARIZED`:
  - the body carries the carrier;
  - `reasoningEffortWithThinkingOff` and `thinkingDisplayWithThinkingOff` are each reported **exactly once** across
    two requests, with today's message text;
  - `resolution.requestsThinking()` is false.

  The mirror case: ADAPTIVE on the same row → `requestsThinking()` true, and neither OFF finding is reported. Plus a
  resolution-level unit test: `findingsToReport()` with a carrier keeps `EXPLAINS_ABSENCE` and drops
  `REQUIRES_THINKING`.
- **Timeout:** `AnthropicConfigTest` asserts the default timeout of 600s. A CLI test that the shipped
  `default-config.yaml` leaves `llm.timeout` null (in `CliConfigLoaderTest`, if it loads the shipped file). The
  Spring binding test asserts that an unset `aimon.llm.timeout` reaches the Anthropic client as 600s and the OpenAI
  client as 60s.
  - With `claude-sonnet-5-5`, in every mode → `thinking.type` is never `disabled` or `between_tools`.
- **Block-binding matrix** (`AnthropicThinkingResolverTest` or the request-body test class), all without network:
  - For each of the three 5.5 names × {OFF, AUTO, ADAPTIVE, EXTENDED→translated}: binding and header present when
    replay is on, absent when it is off.
  - `ADAPTIVE` + `thinkingDisplay(SUMMARIZED)` → `display` and `block_binding` sit together in one object.
  - `claude-sonnet-5`, `claude-opus-5`, `claude-fable-5-1`, `claude-haiku-4-5`, `claude-sonnet-4-6` and an unknown
    name → body unchanged from today (no binding, no header). This pins that the change is opt-in per row.
  - A declared row with `supportsThinkingBlockBinding: true` and `thinkingDialect: BUDGETED` → no binding, and the
    `thinkingBlockBindingWithoutAdaptive` divergence reported once.
  - Header joining: a request whose params already carry an `anthropic-beta` value ends with both, comma-joined.
  - Blocking **and** streaming paths both carry the header (the streaming builder is rebuilt at
    `AnthropicLlmClient:411`).
- **Drop reporting:** a mocked response with top-level
  `"input_transformations":[{…"reason":…}]` produces one recurring WARN that names the model and the count. A response
  without it, or with a malformed value, produces nothing and the request still succeeds. Cover both the blocking
  and the streaming paths.
- **Capability flag plumbing** (follow the `supportsReasoningSummary` tests):
  - `ModelCapabilities` equality and `toString`.
  - `ModelCapabilityDeclaration` resolves the flag.
  - `ShadowedBuiltInRow` reports a declaration for `claude-sonnet-5-5` that omits the flag.
  - CLI `CliConfigLoaderTest` / `LlmClientFactoryTest` bind `supportsThinkingBlockBinding`.
  - Spring `AimonPropertiesValidationTest` binds `supports-thinking-block-binding`.
  - `InMemoryModelCapabilityRegistryTest`: the flag is true on exactly the three exact rows and false on every prefix
    row.
- **Regression guard for forced tool_choice:** in an existing request-body test, build a request with tools and assert
  that the serialized body has no `tool_choice`, or has `{"type":"auto"}`. Use `AnthropicLlmClientTest` /
  `AnthropicLlmClientParameterDivergenceTest`, whichever already serializes bodies.
- **Empty-text thinking block:** in `AnthropicReasoningRoundTripTest`, a response with
  `{"type":"thinking","thinking":"","signature":"sig"}` before a `tool_use` is captured as a trace and replayed
  verbatim on the next request (Sonnet/Haiku 5.5 `display: omitted`).
- `AnthropicModelLifecycleTest` (new):
  - `claude-sonnet-4-5` and `claude-sonnet-4-5-20250929` → a warning naming 2026-11-30 and `claude-sonnet-5-5`.
  - `claude-sonnet-4-20250514`, `claude-sonnet-4-0`, `claude-opus-4-0`, `claude-opus-4-1`,
    `claude-3-5-haiku-20241022`, `claude-2.1` → "retired".
  - `claude-haiku-4-5`, `claude-opus-4-5`, `claude-sonnet-4-6`, `claude-sonnet-5-5`, `claude-haiku-5-5`, `null` and
    `""` → empty.
  - Plus one client-level test: two requests on `claude-sonnet-4-5` → exactly one WARN, using the existing
    log-capture helper the divergence tests use.
- Preserved-thinking hint: feed a mapped 400 with the vendor's message through `mapSdkFailure`. Mock HTTP is
  available, because the divergence tests use it. Assert one WARN containing `replayThinkingBlocks` and
  `supportsThinkingBlockBinding`, both with and without `baseUrl`.
- CLI:
  - `BundledSubagentModelTest:64-70`: the explore subagent runs on `claude-sonnet-5-5`.
  - `ReplSessionBannerTest:90,191,210-211`: banner strings. Swap `claude-haiku-4-5` for `claude-haiku-5-5` as the
    "other" model.
  - `AgentModelProviderCheckTest`: still green, because the bundle model is still `claude-*`.
  - A test that the `default-anthropic` bundle sets no `temperature`, if a bundle-content test exists. Otherwise skip.
- Spring: `AimonPropertiesValidationTest` / `AimonAutoConfigurationTest` use `claude-opus-5` / `claude-sonnet-4-5` as
  arbitrary names. Leave them unless they assert the default.

### 6.2 Build gates

`./gradlew format`, then `./gradlew checkAll`, then `./gradlew integrationTest` (Docker on). Then the doc scripts:
`check-translation-staleness.py`, `check-translation-structure.py`, `check-doc-links.py`. Then CI green on the PR.

### 6.3 Live verification (billed; few runs; never print the key)

1. `ANTHROPIC_KEY_FILE=~/.anthropic-key AIMON_LLM_TEST_ANTHROPIC_MODEL=claude-haiku-5-5 ./gradlew :aimon-cli:llmTest`
   (the new default, so the env var is optional). This runs the JS and shell/Python `ExecuteCode` E2E: a
   multi-iteration tool loop on the shipped request shape, now carrying the binding.
2. The same with `AIMON_LLM_TEST_ANTHROPIC_MODEL=claude-sonnet-5-5`. Re-check how `llmTest` filters providers
   (`*LiveE2ETest`). If it also runs OpenAI when `OPENAI_KEY` is set, leave `OPENAI_KEY` unset.

   Steps 1–2 do **not** prove F1 by themselves: on an account older than 2026-08-31 no check runs without opt-in, and
   a single-turn loop never changes the prefix. Step 3 is the F1 proof.
3. **Preserved-thinking binding live test** (new, repeatable, gated). Put it in
   `AnthropicThinkingBindingLiveTest` in `aimon-llm-anthropic/src/test`, behind the same `@EnabledIfSystemProperty` /
   key gate as `AnthropicThinkingLiveTest`, never in CI. Run it with the module's live-test invocation for
   `claude-sonnet-5-5` and `claude-haiku-5-5`, parameterized over the two names. Every request carries
   `block_binding`, which **opts any account into the check**, so the result does not depend on account age. Cases:
   - **a. Baseline.** Turn 1: a tool-forcing prompt (via the prompt, not `tool_choice`) with a trivial tool. Assert a
     signed thinking block was captured, the tool result went back, and the turn finished. 200 throughout.
   - **b. Cross-turn system change** (finding (b), first path). Replay case a's full history with a **different
     system prompt** plus a new user message, through `AnthropicLlmClient` with the shipped `drop_block`. Expect 200,
     and the `thinkingBlocksDropped` WARN captured, or `input_transformations` non-empty. That proves the check ran
     and the drop worked.
   - **c. Post-compaction history** (finding (b), second path). Replace case a's first user/assistant pair with a
     summary user message. Keep the later assistant messages **with their traces**, which is what
     `DefaultCompactionEngine`'s preserved tail sends. Expect 200 plus a reported drop. Preferably build this history
     by running the real `DefaultCompactionEngine` over case a's messages with a tiny threshold, so the test exercises
     the shipped tail logic, not a hand-made imitation.
   - **d. Control: the probe can fail.** The same request as case b, but with the binding set to the *non-dropping*
     behaviour. The guide says either value opts in. Take the exact spelling of the other value from the server's
     enumeration on a deliberately invalid value; do not guess it. This uses a raw request through the SDK's
     additional-body escape hatch inside the test. Expect a 400 containing `bound to a different conversation`.
     Without case d, cases b and c passing would prove nothing. **If the control cannot be built**, the test **fails**
     (never skips) with "control could not be constructed: …" and the server's text. That covers three cases: the
     invalid-value probe is accepted, the error names no value other than `drop_block`, or the request with the other
     value is not a 400 of the expected kind. A skipped control would quietly weaken the F1 proof.
   - **e. Thinking off/on smoke on the default model.** `claude-sonnet-5-5` with OFF (200; body =
     adaptive+binding only), and with AUTO + `reasoningEffort: LOW` (200; `output_config.effort=low`). Also
     `claude-haiku-5-5` with EXTENDED + `thinkingBudgetTokens` (200; translated to adaptive, never `budget_tokens`).
   - Also flip `AnthropicThinkingLiveTest.ADAPTIVE_MODEL` from `claude-sonnet-5` to `claude-sonnet-5-5`, so the
     existing adaptive suite runs on the new default.

   Cost: about 5 short requests per model per case, with tiny prompts, at `reasoningEffort: LOW` where the case
   allows. That is a few cents.

   **Outcomes:** if a–e pass, F1 is closed. If case d returns 200, the probe is invalid: report that, and do not claim
   F1 closed. If case b or c returns 400, or any case 400s on the header or `block_binding`, follow F1's fallback
   (halt and report before P3).
4. Record in `build/` (run dir) and in the PR body:
   - model, pass/fail, `answer=` lines, token usage and the cost from the price table;
   - every case of step 3 with its status, the drop count, and the case-d error text;
   - the date-stamped "measured on 2026-11-xx" result in `model-name-resolution.md` (the new default's live check
     row) and in `reasoning-traces.md` §7 (the binding measurement).

## 7. Open questions (not silently assumed)

- **Q1 — Preserved thinking: user visibility of the chosen remedy.** The design now *decides*: `drop_block` + beta
  header on the three 5.5 rows (§4.5), proven by a forced-check live test (§6.3 step 3). The fallback (P3, replay off
  by default on those models) is **not** built without asking the user first. Two things the orchestrator should make
  visible in the PR body, so the user can veto them:
  - the beta header now goes on every request to the default model;
  - under `thinkingMode: off`, `thinking: {type: adaptive}` is now sent on those models. It is the server's own
    default, but it is a visible change to OFF's documented "no parameter" body.
- **Q1b — Is `input_transformations` present on the streaming `message_start`/`message_delta` events,** or only on
  the blocking response? The guide says "a top-level `input_transformations` array" without naming the stream event.
  The live test records where it shows up. Drop reporting on the path where it does not show up is best-effort, and
  the request is unaffected either way.
- **Q2 — Should `thinkingMode: off` (or `reasoningEffort: none`) send `between_tools` on Sonnet 5.5** so that "off"
  really means off? This design says not in this PR (A2). It needs a new capability axis and an SDK raw-body override.
  Confirm, or open a backlog item. **Per-model difference that A2's "a 400 on every other model" does not cover:**
  Haiku 5.5 *accepts* `{"type":"disabled"}` at effort ≤ `high` (haiku guide), so on Haiku 5.5 OFF *could* mean off,
  with `disabled` and not `between_tools`. Sonnet 5.5 rejects `disabled`, and Opus 5.5 cannot turn thinking off at
  all. Any follow-up therefore needs per-model "lowest thinking shape" data, not one mapping.
- **Q3 — Raising `DEFAULT_MAX_TOKENS` 4096 → 16000** is a library-default change beyond the literal task. The design
  recommends it (F2). If the user prefers a minimal diff, keep 4096 and document the truncation risk instead.
- **Q4 — Haiku 5.5's >100K-prompt rate card** ($0.50/$2.50) is not representable in `ModelPrice`. The ≤100K card is
  registered, which under-estimates long prompts. Accept, or file a backlog item for tiered pricing?
- **Q5 — Opus 5.5's row is documentation-only.** Should one cheap live probe (adaptive 200 / budgeted 400) be run, as
  for the 2026-09-10 census, or is the catalog enough, as for `claude-mythos`?
- **Q6 — Adjacent table gaps not fixed here:**
  - Fable 5 / 5.1 / Mythos prices.
  - 1M windows for Opus 4.6–4.8 and Sonnet 4.6, which currently resolve to 200K, a safe under-report.
  - Should a `claude-sonnet-4-6` / `claude-opus-4-6` context row be corrected while we are here? The design leaves
    them out to keep the PR on-task.
  - `supportsThinkingBlockBinding` for Fable 5.1 and Mythos 5.1. They run the check, but their rows are prefixes
    shared with Fable 5 and Mythos 5. Flagging them needs exact rows and evidence, so it gets a backlog item rather
    than going in this PR.
- **Q3b — the timeout decision (§4.2) is a user-visible default change** on three surfaces (Anthropic 600s; the CLI
  and Spring stop pinning 60s for both providers). It is settled in the design, not left open. Listed here only so
  the user can veto the number.
- **Q7 — Retired-model WARN scope.** The design includes retired 3.x and 4.0/4.1 names because the cost is the same
  table. Drop that half if the reviewer wants strictly "deprecated only".

---

## 8. After the build — departures and what went to the backlog

Appended after the build. Nothing above this section was edited.

### 8.1 Departures

None changes a decision of the body: the default model, `maxTokens` 16000, the timeout (600s; CLI and Spring no longer
pin 60s), the exact rows, `drop_block` gated per row, the lifecycle WARN and the 400 hint are as designed. F1's
fallback (P3) was not needed — §6.3 step 3 passed on both models.

| # | Body said | Build did | Why |
|---|---|---|---|
| D1 | §6.3 a: a tool-forcing turn 1 with a captured thinking block | The live capture runs up to two tool iterations per attempt (three attempts) and accepts a traced final answer; it runs under `thinkingMode: auto` at effort `high` | `claude-sonnet-5-5` called the tool on iteration 1 with **0 thinking tokens, 3/3** and thinks between tools instead. The binding is identical in every mode (unit-tested) and case e covers `off` live |
| D2 | §6.3 c: preferably run the real `DefaultCompactionEngine` | The compacted history is hand-built: one summary user message, then the traced assistant message with its traces, then the next user message | The engine is not wired on that test classpath and would add a billed summary call; the server-side condition (an edited earlier-message prefix with traces kept) is the same |
| D3 | §6.3 d: read the other value off the server's enumeration | Done; the value is `error` (`Input should be 'error' or 'drop_block'`) | A first regex over the whole SDK message matched the JSON key `request_id`; only the enumeration sentence is parsed now |
| D4 | §4.5: the drop WARN fires on the 1st, 10th, 100th … drop | It fires on the 1st, 10th, 100th … **response with drops** per reason and model; the message carries that response's block count | One `report` call per response and reason through the existing recurring reporter. The live case c uses a fresh client for that reason |
| D5 | §4.5: `reportDivergence("thinkingBlockBindingWithoutAdaptive@…")` | A third finding scope, `AnthropicThinkingResolution.Scope.UNCONDITIONAL`, always reported; such a row gets no binding in any mode, OFF carrier included | The resolver has no logger by design, and this finding must survive both the `requestsThinking` and the no-thinking filter |
| D6 | §4.5: the header via `putAdditionalHeader` on the request params | Applied to the finished params by `AnthropicLlmClient.withBetaHeader` (`toBuilder()`), comma-joining an existing value | Reading the existing `anthropic-beta` value needs a built `MessageCreateParams`, and the builder refuses `build()` before `messages` is set. The streaming path's own `toBuilder()` keeps it (unit-tested) |
| D7 | §4.5: a cause hint for the prefix-mismatch reason only | The hint attaches to any reason containing `prefix` | The measured reason string is `prefix_binding_mismatch`, which the vendor pages did not spell |
| D8 | — | Three request-body test classes moved their "undescribed model" fixture from `claude-sonnet-4-20250514` to `gateway-claude-deployment`; the two clamp tests (#83, L-15) set `maxTokens(4096)` | The retired name now draws the lifecycle WARN, which broke every "says nothing" assertion; the clamp they pin no longer happens at the new default |
| D9 | §6.1: the hint "with and without `baseUrl`" | Without: a mocked SDK `BadRequestException`. With: a real 400 from the local server in `AnthropicGatewayCredentialsTest` (both hints fire) | A local server needs `baseUrl` |

### 8.2 Live results (2026-11-10)

`AnthropicThinkingBindingLiveTest` (`ANTHROPIC_KEY`-gated, never in CI), one run per model after the fixes above:

| Case | `claude-sonnet-5-5` | `claude-haiku-5-5` |
|---|---|---|
| a — traced turn | 200; trace on iteration 2 (a final answer); iteration 1 had 0 thinking tokens | 200; trace on iteration 1 |
| b — changed system prompt, `drop_block` | 200; 1 block dropped, reason `prefix_binding_mismatch` | 200; 1 block dropped, reason `prefix_binding_mismatch` |
| c — compacted head, traces kept | 200; 1 block dropped, reason `prefix_binding_mismatch` | 200; 1 block dropped, reason `prefix_binding_mismatch` |
| d — control, `prefix_mismatch_behavior: error` | 400 "Invalid `signature` in `thinking` block. The block is bound to a different conversation. … The `system` prompt differs from the one this block was created with." | the same 400 |
| e — smoke | `off` (binding carrier) 200 · `auto` + `low` 200 | `extended` + `thinkingBudgetTokens` 2048 → translated to adaptive, 200 |

F1 is therefore closed for the two models measured. `AnthropicThinkingLiveTest` (now on `claude-sonnet-5-5`): 11/11.
`./gradlew :aimon-cli:llmTest` (ExecuteCode E2E): `claude-haiku-5-5` JS and Python `result=PASS answer=CORRECT`;
`claude-sonnet-5-5` JS and Python `result=PASS answer=CORRECT`. Token counts are in the run's `build/summary.md`.

### 8.3 Open questions — where each went

| Q | Outcome |
|---|---|
| Q1 | Decided as designed; the two visible changes (beta header on every request to the three models; the OFF carrier) are in the CHANGELOG for the user to veto |
| Q1b | Not measured live (the live test uses the blocking path) → backlog [L-36](../../backlog/llm-config-surface-open-items.md) |
| Q2 | → backlog [L-31](../../backlog/llm-config-surface-open-items.md) |
| Q3 · Q3b · Q7 | Decided as designed (16000; 600s and unpinned; retired names kept in the WARN table) |
| Q4 | → backlog [L-32](../../backlog/llm-config-surface-open-items.md) |
| Q5 | → backlog [L-33](../../backlog/llm-config-surface-open-items.md) |
| Q6 | Split: the block binding for Fable 5.1 / Mythos 5.1 → [L-34](../../backlog/llm-config-surface-open-items.md); the price and context-window gaps → [L-35](../../backlog/llm-config-surface-open-items.md) |
