# Two places where a value the author wrote is silently discarded — design

Closes **#53** (`aimon-cli`: `${VAR}` is not resolved in the `memory` block) and **#74**
(`aimon-core`: `extractInt` / `extractDouble` substitute their default in silence).

> Status: **IMPLEMENTED** — `aimon-cli` (`PlaceholderExpandingParser`, `CliConfigLoader`) and
> `aimon-core` (`MarkdownAgentDefinitionParser`). §12 records where the implementation departed from
> this text and why; everything before it is the design as approved.

Branch `herdr/config-values-dropped-silently`, base `main` @ `2659d76`.
Every line number below was read on that commit; where the design asserts runtime behaviour it says
whether it was **read** or **measured**, and §11 lists the probes.

---

## 1. The problem, once

Two modules throw away a value its author wrote and say nothing, and in both the consequence arrives
far from the cause. `CliConfigLoader.resolveEnvironmentVariables` (`:112-132`) expands `${VAR}` in
exactly five places — `llm.apiKey`, `llm.baseUrl`, `llm.model`, the `llm.modelCapabilities` map keys
and an MCP entry's `command`/`url`/`env` values — while the CLI's own shipped
`default-config.yaml:138` demonstrates `apiKey: "${OPENAI_KEY}"` **inside the `memory` block**, and
`docs/features/memory/memory-usage-guide.md:261` prints the same example; a deployment that follows
either gets the literal seven-character string `${OPENAI_KEY}` handed to `OpenAIEmbeddingClient`
(`AgentSetupFactory:1215` builds its `OpenAIEmbeddingConfig`, read), whose 401 surfaces up to thirty
minutes later inside a Quartz job as
`DreamerJob:81`'s `log.error`, naming a workspace and no configuration key. Meanwhile
`MarkdownAgentDefinitionParser.extractInt`/`extractDouble` (`:282-307`) return their default whenever
the frontmatter value is not a `Number`, so `temperature: hot` binds `1.0`, `maxIterations: fifty`
binds `Integer.MAX_VALUE` — an unbounded ReAct loop — and the agent runs on sampling and budget
parameters its author did not choose, beside a key (`model.reasoningEffort`) that has thrown loudly on
the same class of input since #61. Both defects are the same shape: the write is accepted, the value
is dropped, and the report arrives somewhere that cannot name the key.

---

## 2. Decision 1 (#53, mandated) — a general pass, run **before** binding, **on the token stream**

**Resolution becomes a general pass over the config tree. It is not an explicit per-field list, and the
per-field list that exists today is deleted rather than extended.**

The rule, in the one sentence a deployment has to be able to read:

> Every scalar value and every mapping key in the CLI configuration file is expanded: `${NAME}` is
> replaced by the environment variable `NAME`, and a variable that is not set fails startup naming the
> variable and the key it was written on.

### 2.1 Why a general pass

- **The failure mode the task names is the per-field list's growth rate, and it has already fired
  once.** The list was three fields when `llm` was the only block with a credential. `memory` gained
  one (`dreamer.scorer.embedding.apiKey`) and the list was not extended, because nothing connects the
  two edits: a person adding a config class has no reason to open `CliConfigLoader`. The list would
  have to be extended again for `memory.dreamer.scorer.llm.model`, `memory.storagePath`,
  `mcp.servers[].args` and every future block. Fixing the instance leaves the generator.
- **The list is not discoverable.** Which keys expand is a fact about a private method, not about the
  yaml. That is the second half of the issue and the reason it asks for a decision rather than a patch.
- **A general rule is documentable in one sentence; the list is not.** "These five, and one of them is
  a map key" cannot be maintained in prose next to a config file that grows.
- **It removes code.** `resolveEnvironmentVariables`, `resolveModelCapabilityKeys` and
  `resolveMcpServerEnvVars` (49 lines) are replaced by one decorator. The behaviour that decorator must
  keep — refusing two keys that expand to the same name (#46 / PR #50, `:143-161`) — becomes a property
  of every mapping instead of a property of one map, which is a generalisation of an existing fix and
  not a new one.

### 2.2 Two constraints on the mechanism, not one

Revision 1 had only the first of these and shipped a mechanism that violated the second.

**Constraint A — expansion must run before binding.** Today it runs after Jackson has bound, which is
why it can only reach `String`-typed fields. That limitation is currently *asserted*:
`CliConfigLoaderTest.environmentVariablesAreNotExpandedHere` (`:1026`) pins that
`thinkingMode: "${THINKING_MODE}"` fails at bind time, and its own comment says *"The starter has no
such limit: Spring resolves placeholders before binding. Documented, not fixed."* The canonical doc
repeats it (`aimon-core-integration-via-cli-reference.md:374-378`). A post-bind walk would still be
unable to expand `llm.timeout` or `llm.anthropic.thinkingMode`, so the documented rule would become
*"every key whose Java type is `String`"* — a rule a reader cannot evaluate without reading the config
classes, i.e. a smaller version of the same defect.

**Constraint B — the mechanism must hand each deserializer the scalar text the YAML parser read.** One
field's deserializer depends on it by design. `AnthropicProviderConfig.ThinkingModeDeserializer:185`
reads `parser.getText()`, and its javadoc (`:170-172`) states why: *"같은 토큰에서 `getText()` 는 원문
`off` 를 그대로 돌려주므로, 텍스트로 접으면 그 한 값이 살아난다"* — `off` is a YAML 1.1 boolean, arrives
as `VALUE_FALSE`, and only the raw text distinguishes it from `no` and `false`, which the same enum must
keep refusing. Any mechanism that materialises values before binding destroys that distinction for
**every** file, including files with no placeholder in them.

**The mechanism that satisfies both is a `JsonParserDelegate` between the YAML parser and the
deserializers.** It rewrites the text of `VALUE_STRING` tokens and the text of `FIELD_NAME` tokens, and
passes every other token through untouched — same token type, same `getText()`, same numeric decoding.
A `${…}` can never resolve to a YAML boolean, number or null, so a placeholder always arrives as
`VALUE_STRING`; a value that carries no placeholder is never touched at all.

**Measured** (§11, probes E–I): with the delegate in place, `thinkingMode: off` / `OFF` / `Off` bind
`OFF` while `no` and `false` are still refused *and still name the written text*; `0755`, `1.10`, `1e3`
and `yes` arrive on a `String` field exactly as written; expansion works quoted **and unquoted**, on
`String`, `Integer` and enum fields, inside arrays, on map keys, and in the `memory` block; and the
classification of every failure input — `.inf`, empty, comment-only, `---`, an early YAML error, a late
YAML error, an unknown property, a bad enum — is **identical to today's**, all eight cases.

### 2.3 Rejected alternatives

| # | Alternative | Rejected because |
|---|---|---|
| **A1** | **Add the memory block's credential fields to the list** | This is the failure mode the task names. It fixes one field and leaves the mechanism that produced the issue, and it cannot be documented as a rule. The issue itself prefers the useful fix |
| **A2** | **Delete the `${OPENAI_KEY}` example from `default-config.yaml`** (the issue's "minimum honest" option) | Honest but strictly worse: the credential still cannot come from the environment, so the block's only documented way to hold a key is to write it into a file on disk. It also does not touch `memory-usage-guide.md`, which ships the same example |
| **A3** | **Post-bind reflective walk over the bound object graph** | Violates constraint A: `${VAR}` on `Integer`/enum still fails at bind, so the documented rule becomes type-dependent. Also needs `setAccessible` reflection over config classes, of which the CLI module has none, and per-type recursion rules for `List<String>`, `Map<String,String>` and `Map<String,ModelCapabilityConfig>` — a new list, of types instead of fields |
| **A4** | **Regex substitution over the raw file text before the YAML parse** | **Breaks the shipped default config.** `default-config.yaml` carries `${OPENAI_KEY}` and `${GITHUB_TOKEN}` inside *comments* (`:138`, `:102`); a text pass expands commented-out examples, so `loadDefault()` would fail with `Environment variable not set: GITHUB_TOKEN` on a machine that has never used MCP |
| **A5** | **Expand values generically but keep `resolveModelCapabilityKeys` as a post-bind special case** | Leaves a per-field remnant and puts collision detection in a second place, so the rule becomes "every value, plus the keys of one particular map" |
| **A6** | **Add an escape (`$${VAR}` → literal `${VAR}`)** | New syntax, new documentation, and nothing in the tree needs it. Recorded as OQ-1 with the one plausible victim named (`mcp.servers[].args`) rather than pre-emptively built |
| **A7** | **Bind from a re-serialised `JsonNode` tree** (`readTree` → walk → `readValue(treeAsTokens(root), …)`) — **revision 1's mechanism** | Satisfies constraint A and **violates constraint B for every file, whether or not it contains a placeholder.** Measured: on a `String` field `off`→`false`, `on`/`yes`→`true`, `0755`→`493`, `1.10`→`1.1`, `1e3`→`1000.0`; and `thinkingMode: off`, which binds `OFF` today, becomes `InvalidFormatException … from String "false"`, failing `CliConfigLoaderTest.unquotedOffBinds` (`:871`) and three of the twelve iterations of `everyModeSpellingBinds` (`:847`). Unfixable inside the mechanism: `off`, `no` and `false` all become `BooleanNode(false)`, so `unquotedOffBinds` and `otherYamlBooleansAreStillRefused` (`:889`) become mutually unsatisfiable. It also moved `.inf`'s report from `Invalid configuration structure` to `Invalid YAML syntax` for a file whose YAML is valid, and needed a null guard for empty input that the chosen mechanism does not |
| **A8** | **Compose with snakeyaml's `Node` API and re-emit YAML text** | Preserves the written scalar (the emitter round-trips an implicitly-tagged scalar) but introduces a second YAML implementation path into `aimon-cli`, which today only uses Jackson's `YAMLFactory`, and pays a full parse-rewrite-emit-reparse for a substitution. The delegate gets the same fidelity for one class and no extra dependency |

### 2.4 The documentation surface, and its translation

The task requires naming this explicitly.

| Surface | Change | Translation |
|---|---|---|
| **`docs/getting-started/aimon-core-integration-via-cli-reference.md`** — new **`### 3.1 ${VAR} — 어디서 풀리는가`** under `## 3. 부트스트랩 흐름`, where `configLoader.load(path)` is already described | **This is the canonical statement of the rule.** One paragraph: every scalar and every mapping key; unset fails startup naming variable and key; two keys expanding to one name are refused; expansion is a single pass; and the one thing it does *not* do — it never changes a scalar that carries no placeholder, so `thinkingMode: off` still means `off` | **Yes — `…-via-cli-reference.en.md` must gain the same `###` heading and paragraph in the same commit.** `check-translation-structure.py` compares heading count and level, so a one-sided heading fails the build. `source_commit` in the `.en.md` frontmatter moves from `d3500f6` to the canonical's last commit before this one (`3a41fcd` today — re-read at implementation time) |
| Same doc, `:374-378` (`.en.md:390-393`) — the paragraph saying `${VAR}` is *not* expanded in the `llm.anthropic` block, listing the three `llm` string fields | **Becomes false and must be replaced**, by two lines pointing at §3.1 and stating that the starter and the CLI now agree on when placeholders resolve | Yes, same commit, same reason |
| **`modules/aimon-cli/src/main/resources/default-config.yaml`** — a comment block at the top | The issue's complaint is *"a deployment cannot tell from the yaml"*. The yaml is where the reader is, and it is the surface the reporter was reading | n/a (not under `docs/`) |
| **`docs/features/memory/memory-usage-guide.md`** §7.2 — one bullet under the dreamer yaml block | The reporter's example lives here too. One line: the `apiKey` above is expanded from the environment like every other value, with a link to §3.1 | **Yes — `memory-usage-guide.en.md` gains the same bullet** (list-items axis) and its `source_commit` moves |
| **`docs/design/llm/model-capability-config-key.md:752-754`** — §10's `HANDOFF.md` list, item 4: *"`CliConfigLoader` 는 `${VAR}` 를 손으로 나열한 필드에서만 푼다 … `memory` 블록은 전혀 풀지 않는다"* | **Append `**→ #53 이 고쳤다**` with a pointer**, which is that list's own convention — items 1 and 2 already carry appended `**→ #48 이 고쳤다**` notes. This is the same stale-record sweep §3.4 performs for #74; missing it in revision 1 was the failure §3.4 is about | n/a — `design/` is 번역 대상 아님, and no `.en.md` exists |
| `README.md:176` — `# ${ENV} interpolation supported`, on the `apiKey` line | **Not touched.** Root markdown is outside the task's file list, and the comment is narrow rather than wrong. Noted in `deviations.md` | n/a (no `README.ko.md` exists) |

Baseline verified: `check-doc-links.py` 235 files / 2316 links / 0 broken; `check-translation-staleness.py`
32 pairs / 0 stale / 0 unresolvable; `check-translation-structure.py` 32 pairs / 0 findings. All three
must still say that afterwards.

---

## 3. Decision 2 (#74, mandated) — the parser sweep, with evidence

**I read all 308 lines of `MarkdownAgentDefinitionParser.java`. The same leniency — a default
substituted for a value the author wrote, in silence — exists in two places beyond the two methods the
issue names, and both are in scope by the issue's own "look elsewhere" clause. A third family of
looseness exists, is *not* the same defect, and is reported rather than fixed.** `review-1.md` read the
file independently and confirmed the table below line-for-line.

### 3.1 Every extraction site in the file

| # | Site | Shape today | Verdict |
|---|---|---|---|
| 1 | `:93` `extractStringOrElseThrow(frontmatter, "name")` | throws when absent or null | **already strict** |
| 2 | `:95` `extractInt(frontmatter, "maxIterations", Integer.MAX_VALUE)` | non-`Number` → `Integer.MAX_VALUE` | **SAME DEFECT — a fourth call site the issue's "three keys" count omits.** `maxIterations: fifty` yields an unbounded loop. In scope by construction: it is a caller of `extractInt` |
| 3 | `:126` `extractString(frontmatter, "version", null)` → `extractVersion` | absent → `DEFAULT_VERSION`; **present-and-null → also `DEFAULT_VERSION`**; present-and-unparseable → throws (`:133-135`) | **SAME DEFECT, one branch.** `version:` with nothing after it silently becomes `1.0.0` |
| 4 | `:147` `(Map<String, Object>) frontmatter.get("model")` | unchecked cast; `model: gpt-4` → `ClassCastException` → generic wrap at `:111-113` | **not silent** — loud with a message that does not name the key. §8 note |
| 5 | `:156` `extractString(configMap, "name", "gpt5.1")` behind `containsKey` | the guard makes the default reachable **only** when the value is null, and it then substitutes a specific model name | **SAME DEFECT.** `model:\n  name:\n` silently runs on `gpt5.1` |
| 6 | `:159` `extractDouble(configMap, "temperature", 1.0)` | non-`Number` → `1.0` | **the reported defect** |
| 7 | `:162` `extractInt(configMap, "maxTokens", 4096)` | non-`Number` → `4096`; also `intValue()` **truncates** `4096.5` and **narrows** `9999999999` → `1410065407` | **the reported defect, plus two silent numeric conversions inside the same method** |
| 8 | `:165` `extractDouble(configMap, "topP", 1.0)` | non-`Number` → `1.0` | **the reported defect** |
| 9 | `:168` `extractReasoningEffort` | throws naming key and every accepted spelling | **already strict — the bar** |
| 10 | `:224` `extractTags` | non-list → throws; null/blank elements dropped, documented in its own javadoc `:215-216` | **strict on shape; the element dropping is documented behaviour.** §8 note |
| 11 | `:105` `(Map<String, Object>) frontmatter.getOrDefault("variables", Map.of())` | unchecked cast; `variables: [a]` → `ClassCastException` → generic wrap | **not silent.** §8 note |
| 12 | `:258-261` `extractString` general form | returns `defaultValue` only when `map.get(key) == null`, which conflates absent with present-and-null | **the mechanism behind 3 and 5** |

### 3.2 The rule this design applies

> For every frontmatter key the parser reads: **absent** takes the documented default; **present with a
> value the parser cannot use** is an `AgentDefinitionParseException` naming the key and the value.

`map.get(key) == null` cannot tell the two apart; `map.containsKey(key)` can. **Measured** (§11, probe C):
snakeyaml 2.7 — the version `aimon-core` resolves — returns `null` for `t4:` while `containsKey("t4")`
is `true`, so the distinction is available.

Applied to sites 2, 3, 5, 6, 7, 8. Sites 1, 9, 10 already obey it. Sites 4, 11 are a different failure
(loud, badly worded) and are left alone.

### 3.3 What "cannot use" means for a number — and what it does not

**Measured** (§11, probe C) on snakeyaml 2.7, which decides this and not the parser:

| written | arrives as | today | after |
|---|---|---|---|
| `hot` | `String` | default, silently | error |
| `""` | `String ""` | default, silently | error |
| `1,5` | `String "1,5"` | default, silently | error |
| *(empty)* | `null`, `containsKey` true | default, silently | error |
| `true` | `Boolean` | default, silently | error |
| `[1,2]` | `ArrayList` | default, silently | error |
| `1e-3` | **`Double 0.001`** | 0.001 | 0.001 — unchanged |
| `40_000` / `0x10` | `Integer 40000` / `16` | as written | unchanged |
| `4096.0` | `Double` | `4096` | `4096` — unchanged |
| `4096.5` (int key) | `Double` | **`4096`, silently truncated** | error |
| `9999999999` (int key) | `Long` | **`1410065407`, silently narrowed** | error |
| `.inf` / `.nan` (double key) | `Double` | passed through | **unchanged** — see below |

- **Accept a whole-valued `Double`, reject a real fraction.** This is the repository's own existing
  answer to the same question, in two places: `ToolInputBinder.isWholeNumber` (`:436-442`) with
  `toIntegral`'s comment *"accepting the `3.0` that a JSON parser hands back for a `3`"*, and
  `docs/features/tool/tool-development-guide.md` — *"`3.0` 은 `integer` 를 통과한다 … 거부되는 것은 진짜
  소수부(`3.5`)다"*. Mirroring it costs one predicate and removes two silent conversions.
- **Do not coerce a numeric-looking `String`.** `temperature: "0.7"` becomes an error rather than
  binding `0.7`. Rejected coercion because the nearest in-repo analogue refuses it outright
  (`ToolInputBinder.toDecimal`, `:317-318`), because the error already tells the author exactly what to
  fix, and because `1e-3` — the case that would have justified coercion — is measured to arrive as a
  `Double` anyway.
- **Do not add a finiteness check to `extractDouble`.** `.nan` / `.inf` are `Number`s; whether they are
  *usable* is a range question and `LlmModel` owns ranges (`:59-66`). The int path rejects them only as
  a by-product of having no representation for them. §8 note.

### 3.4 Records that become false and must move with the code

Three in-tree statements assert the leniency this change removes. Leaving them is the stale-record
failure this repository is organised against.

| Where | What it says | Action |
|---|---|---|
| `MarkdownAgentDefinitionParser:178-183` (the `extractReasoningEffort` javadoc) | *"Those two substitute their default when the value has the wrong type, so `temperature: "hot"` silently becomes `1.0` … a pre-existing defect this round records and does not fix"* | **Rewrite.** The divergence it explains no longer exists; the paragraph becomes "all four keys are read the same way, and here is the extra thing an enum needs" |
| `MarkdownAgentDefinitionParserTest:169-171` (comment in `shouldThrowOnAnUnknownRung`) | same claim | Rewrite alongside |
| `docs/design/llm/reasoning-effort-config-surface.md` §16 **O-3** (`:876-879`) — *"should the parser's lenient `extractInt` / `extractDouble` be a registered backlog item?"* | open question, answered by #74 existing | **Append one dated note** saying it was answered by #74 and fixed here. §6.3 (`:536-544`) and §14 (`:830-833`) are frozen design-time records of that round and are **not** rewritten, per `docs/backlog/README.md` 규칙 하나. No `.en.md` exists |

**No registered backlog item closes.** Searched `docs/backlog/`: no `L-n` / `RD-n` / `O-n` covers either
defect. The only citation of `extractDouble` there is inside `spring-boot-starter-open-items.md` B-30
(`:1680`), which is already **완료** and cites the parser as evidence that frontmatter reaches `LlmModel`
— a claim my change does not affect. `llm-config-surface-open-items.md` **L-5** ("CLI 의 매핑 오류
메시지가 어느 키인지 말하지 않는다") stays **open and untouched** — see §8.

---

## 4. Concrete changes, by file

### 4.1 `aimon-cli` (#53)

**New — `modules/aimon-cli/src/main/java/at/aimon/cli/config/PlaceholderExpandingParser.java`**
(package-private, `final`, `extends com.fasterxml.jackson.core.util.JsonParserDelegate`). Named the way
this repository names decorators (`RedactingPeerMemory`, `IndexedObservationStore`,
`ApprovalCachingSkillInvocationPolicy`): what it does, then what it wraps. It is the one javadoc home
for the rule, which is what #53 asks for, and it can be unit-tested by driving tokens directly.

```java
final class PlaceholderExpandingParser extends JsonParserDelegate {

    PlaceholderExpandingParser(JsonParser delegate, Function<String, String> envVarResolver) { … }

    @Override
    public JsonToken nextToken() throws IOException {
        final JsonToken token = delegate.nextToken();
        rewritten = null;                       // cleared on every token
        if (token == null) {
            return null;                        // end of input — an empty or comment-only file on call 1
        }
        switch (token) {
            case START_OBJECT -> pushLevel();
            case END_OBJECT   -> popLevel();
            case FIELD_NAME   -> rewriteName();  // expand, refuse a sibling collision, record the path
            case VALUE_STRING -> rewriteValue(); // expand
            default -> { }                       // every other token passes through untouched
        }
        return token;
    }

    @Override
    public String getText() throws IOException {
        return rewritten != null ? rewritten : delegate.getText();
    }
}
```

What it overrides, and why each one:

| Override | Reason |
|---|---|
| `nextToken()` | the only place that decides whether a token is rewritten, and the only place that maintains the depth / per-object bookkeeping |
| `getText()`, `getValueAsString()`, `getValueAsString(String)` | the accessors deserializers actually read. **Measured**: `StringDeserializer`, `Integer`'s string path, `EnumDeserializer` and `ThinkingModeDeserializer` all arrive through these |
| `getTextCharacters()`, `getTextLength()`, `getTextOffset()`, `hasTextCharacters()` | the char-array path some deserializers prefer. `hasTextCharacters()` returns `false` for a rewritten token so a caller that trusts it does not read the delegatee's buffer. **Measured** to agree with `getText()` on a rewritten token |
| `currentName()` | what Jackson reads for a field name, including `Map` keys. **Measured**: `getCurrentName()` — deprecated in 2.22.2 — needs **no** override; removing it changed no outcome, map-key expansion included, and `resolvesEnvironmentVariablesInTheKey` is the pin that would go red if a Jackson upgrade ever routed through it |
| `getText(Writer)` | also forwarded, and **measured** to write the raw `${V}` on a rewritten token while `getText()` returns the expansion |
| `getValueAsInt/Long/Double/Boolean` (both arities) and `getBinaryValue(Base64Variant)` | derived from the rewrite for the same reason. **Measured** not to be reached by today's config classes — `String`, `Integer`, plain enum, `@JsonDeserialize` enum, `List<String>` and both `Map` shapes all route through `getText()` / `currentName()` — so this is not a fix but the removal of a residual: a future `int` or `boolean` config key, or any deserializer reaching for `getValueAsInt()`, would otherwise silently read the unexpanded text. Five one-line overrides |
| `nextValue()`, `skipChildren()` | insurance, not a fix. `JsonParserDelegate` forwards both straight to the delegatee, which would advance the raw parser **past** `nextToken()` and desynchronise the level bookkeeping. Re-implemented on top of this class's own `nextToken()`. **Measured**: neither is reached by this config tree today (`FAIL_ON_UNKNOWN_PROPERTIES` is on and no config class carries `@JsonIgnoreProperties`), and `nextTextValue()` — which `List<String>` binding does use — needs no override because `JsonParserDelegate` does not forward it either, so it already routes through `nextToken()` + `getText()` |

Behaviour:
- **Only `VALUE_STRING` and `FIELD_NAME` are ever rewritten**, and only when the text contains `${`. A
  `${…}` cannot resolve to a YAML boolean, number or null, so no placeholder is missed; and a scalar
  with no placeholder reaches its deserializer byte-for-byte, which is constraint B.
- **Arrays are new reach**: `mcp.servers[].args` was never expanded.
- **One pass, not recursive**: a variable whose value is itself `${OTHER}` stays literal. Same as today.
- Unset variable → `ConfigurationException("Environment variable not set: " + name + " (at " + path + ")")`.
  The path is added because the general pass makes this failure reachable from every key, so the message
  has to say which one. **Measured**: `memory.dreamer.scorer.embedding.apiKey`, `mcp.servers[].env.T`,
  `mcp.servers[].args[]`, `llm.${MISSING}` for a key that fails while expanding. Array segments carry no
  index — a deliberate simplification, since the message already names the key.
- Collision → `ConfigurationException("Configuration keys `a` and `b` both expand to `x`, so one would
  silently replace the other. Keep one of them under `<path>`.")`. **Measured** to preserve everything
  `refusesTwoKeysThatExpandToTheSameName` asserts — `${PRIMARY}`, `${SECONDARY}`, `prod-assistant`,
  `llm.modelCapabilities`.
- **Every field name is recorded, and the refusal fires only when expansion is what made the two
  collide.** The two halves are separate decisions and both have a consequence. *Recording every name*
  is what preserves today's behaviour: `resolveModelCapabilityKeys` runs **every** declared key through
  the expander, so `modelCapabilities: { prod: …, ${P}: … }` with `P=prod` is refused today, and
  recording only placeholder-bearing names would drop that refusal — the one thing §2.1 promises the
  decorator must keep. *Firing only on an expansion-created collision* is what stops the change from
  reaching further than it was asked to: two **literal** duplicate sibling keys are visible at the token
  layer for the first time, Jackson accepts them today last-wins (measured: `OK`), and starting to fail
  a deployment's startup on a plain duplicated yaml key is a different defect in a different layer that
  no issue asked about. So a collision throws when either written spelling differs from the shared
  expansion, and passes through when both are literally the same key. Both shapes are pinned by tests.
- **One measured limit, stated rather than hidden.** Detection is per open object in a token stream, so
  it fires when both colliding keys are reached. For a map-valued block — `llm.modelCapabilities`, the
  tested case — both are reached and the refusal is identical to today's, and the same holds when the
  two keys expand to a *declared* property name (`${A}`/`${B}` → `apiKey`): the collision message
  arrives. What pre-empts it is an **undeclared** expanded name, where Jackson's unknown-property error
  on the first key is thrown before the second is ever read — still a loud failure naming the expanded
  name, but not this message. Nothing is lost: an undeclared name fails startup either way.

**`CliConfigLoader.java`** — `resolveEnvironmentVariables` (`:112-132`), `resolveModelCapabilityKeys`
(`:134-161`), `resolveMcpServerEnvVars` (`:163-175`) and `resolveEnvVars` (`:177-198`) are **deleted**;
`ENV_VAR_PATTERN` and the `Pattern`/`Matcher` imports move to the new class. Both call sites read the
source through the delegate; `load()` keeps its three `catch` clauses and `loadDefault()` keeps its one,
each gaining the same unwrap:

```java
// load(String) — the three existing catches, plus the guard marked NEW
try (JsonParser parser = new PlaceholderExpandingParser(yamlMapper.createParser(file), envVarResolver)) {
    final CliConfig config = yamlMapper.readValue(parser, CliConfig.class);
    validateConfig(config);
    return config;
} catch (JsonParseException e) {             // unchanged → "Invalid YAML syntax in: "
    …
} catch (JsonMappingException e) {           // NEW — unwrap, else unchanged
    throw placeholderFailureIn(e)
            .orElseGet(() -> new ConfigurationException("Invalid configuration structure in: " + path, e));
} catch (IOException e) {                    // unchanged → "Failed to read configuration from: "
    …
}
```

`loadDefault()` needs the unwrap for a sharper reason: its **only** existing `catch` is `IOException`, and
a `JsonMappingException` is one — so without it a placeholder failure there would be reported as
`Failed to load configuration from: <source>` and lose its message. Its shape is `catch (IOException e)`
with the same `placeholderFailureIn(e)` unwrap ahead of today's message.

Two details about error reporting, both measured:

1. **Message parity does not need a pre-advancing `nextToken()`, and there is not one.** An earlier
   revision added one on the theory that `readValue(JsonParser, …)` would wrap a YAML scanner error into
   a `JsonMappingException` and demote `Invalid YAML syntax in:`. **Measured across twelve inputs —
   early YAML error, late YAML error (two shapes), `.inf`, empty, comment-only, `---`, unknown property
   (two depths), bad enum, `Integer`-from-string, valid — the classification is identical to today's
   with and without it, 12/12 both ways** (§11, probe I). `_initForReading` performs the same first
   `nextToken()` outside any wrapping try/catch, so the guard was measuring as necessary something that
   was never load-bearing. `.inf` keeps today's `Invalid configuration structure` because it arrives as
   `VALUE_NUMBER_FLOAT` without throwing at tokenisation time.
2. **A placeholder failure must be unwrapped.** **Measured**: Jackson wraps a `RuntimeException` thrown
   from inside a property's deserialization into a `JsonMappingException`, so without an unwrap the
   loader would report `Invalid configuration structure in: <file>` and lose
   `Environment variable not set: MISSING_VAR` — breaking `testUnsetEnvVar` (`:229`). A cause-chain scan
   inside the `JsonMappingException` branch handles it. No `catch (ConfigurationException e) { throw e; }`
   clause is needed: it is a `RuntimeException`, so when it arrives unwrapped no clause in `load()`
   catches it and it propagates on its own.

**No null guard.** Revision 1 needed one because
`readTree` returned `MissingNode` for an empty file and binding that yielded `null`. Through the parser,
empty and comment-only input reach `readValue` and throw `MismatchedInputException` — a
`JsonMappingException` — exactly as today, in both `load()` and `loadDefault()`. A `---`-only document
still binds to `null` and still NPEs in `validateConfig`, which is **pre-existing on `main`** and
deliberately not touched (§8).

`modules/aimon-cli/src/main/resources/default-config.yaml` — a header comment stating the rule; the
`memory` block stays commented out and its `${OPENAI_KEY}` example now describes something that works.

### 4.2 `aimon-core` (#74)

**`MarkdownAgentDefinitionParser.java`** only. Split the three extractors into an absence policy and a
value policy, so "absent" and "unusable" stop sharing a branch:

```java
// absence policy
private String extractString(Map<String, Object> map, String label, String defaultValue) {
    return map.containsKey(leafKey(label)) ? requireString(map, label) : defaultValue;
}
private int extractInt(Map<String, Object> map, String label, int defaultValue) { … }
private double extractDouble(Map<String, Object> map, String label, double defaultValue) { … }

// value policy — the key is known to be present
private double requireDouble(Map<String, Object> map, String label) {
    final Object value = map.get(leafKey(label));
    if (value instanceof Number number) {
        return number.doubleValue();
    }
    throw new AgentDefinitionParseException("Invalid " + label + ": " + value + ". Expected a number.");
}
```

`requireInt` additionally normalises through `new BigDecimal(number.toString())` inside a
`try`/`catch (NumberFormatException)` — which folds `.inf`/`.nan` into the same rejection — and demands
`stripTrailingZeros().scale() <= 0` and 32-bit range, throwing
`"Invalid " + label + ": " + value + ". Expected a whole number that fits in a 32-bit integer."`
`requireString` throws `"Invalid " + label + ": no value. Expected a text value."` for a present-and-null
key.

The extractors take the **display label** (`"model.temperature"`, `"maxIterations"`) and derive the map
key from it, `leafKey(label)` returning the substring after the last dot. One string per call site, no
label/key drift, and messages that read like the neighbour's: `Invalid model.reasoningEffort: mediumish.
Accepted values: …` → `Invalid model.temperature: hot. Expected a number.`

Call sites become `extractInt(frontmatter, "maxIterations", Integer.MAX_VALUE)`,
`extractString(frontmatter, "version", null)`, and inside `extractModel`'s existing `containsKey`
guards `requireString(configMap, "model.name")`, `requireDouble(configMap, "model.temperature")`,
`requireInt(configMap, "model.maxTokens")`, `requireDouble(configMap, "model.topP")` — the `"gpt5.1"`,
`1.0` and `4096` defaults disappear, because the guard above them already proves they are unreachable.
**`extractStringOrElseThrow` takes the same label signature** rather than a raw key: leaving it as the
one method with a different convention would relocate the "two strings that can disagree" hazard the
paragraph above rejects. Its behaviour is unchanged — already loud for both absence and null. The
`extractReasoningEffort` javadoc is rewritten (§3.4).

**No in-tree agent definition breaks.** There are **17** files named `agent.md` under `modules/` and
`samples/`; every `maxIterations`, `temperature`, `topP` and `maxTokens` in all of them is a plain
well-formed number, and **no `version:` key appears anywhere**, so site 3's strictness has no in-tree
effect at all. Two of the seventeen are deliberate failure fixtures and keep failing for the reasons
they already fail: `agents/agent-empty/agent.md` is 0 bytes (no frontmatter delimiters) and
`agents/agent-malformed/agent.md` holds invalid YAML. The three files under `agent-templates/` are
0 bytes each and no main source references that directory.

### 4.3 `CHANGELOG.md`

Two new `### ` sections inserted directly under `## [Unreleased]` (newest first, matching the file).
The #74 one must say the breaking part in those words:

> **BREAKING for agent definitions that have been quietly running on a default.** A definition whose
> `model.temperature`, `model.topP`, `model.maxTokens`, `maxIterations`, `model.name` or `version` holds
> something the parser cannot read has been starting anyway, on the default, since the parser was
> written. It now fails to load, naming the key and the value. That is the point of the change: the
> agent was running on a sampling parameter or an iteration ceiling its author did not choose. A
> definition whose numbers are numbers is unaffected, and no definition in this repository changed.

The #53 one names the two behaviour changes a deployment can observe: an unset variable anywhere in the
file now fails startup (it used to be silently literal outside five fields), and `${VAR}` now works on
non-`String` keys such as `llm.timeout` and `llm.anthropic.thinkingMode`. It also says what did **not**
change, because that is the part a reader of this diff will worry about: a scalar carrying no
placeholder still reaches its deserializer exactly as written, so `thinkingMode: off` still means `off`.

### 4.4 Commits

- `fix(core): reject an agent frontmatter number that does not parse (refs #74)`
- `fix(cli): expand ${VAR} everywhere in the config file, not in five places (refs #53)`

Two commits, one per issue, since they share no files. PR body carries `Closes #53` and `Closes #74`.
No attribution lines.

---

## 5. Data and interface shapes that change

| Shape | Before | After |
|---|---|---|
| `PlaceholderExpandingParser` | — | new package-private `JsonParserDelegate`; not public API |
| `CliConfigLoader` private methods | 4 resolution methods | 0; one `placeholderFailureIn(…)` unwrap helper added |
| `CliConfigLoader` public API | `load`, `loadDefault`, two constructors | **unchanged** |
| Config classes (`CliConfig` … `MemoryDreamerConfig`) | — | **unchanged.** No field, getter, setter, `@JsonProperty` or yaml key is added, removed or renamed. Nothing goes in `docs/migration/rename-maps.md` |
| Token stream seen by deserializers | YAML parser's own tokens | same token types, same text — **except** `VALUE_STRING` / `FIELD_NAME` text that contained `${…}` |
| Order of `llm.modelCapabilities` entries | declaration order | declaration order — the stream is never reordered |
| `MarkdownAgentDefinitionParser` private helpers | 4 | 7 (3 absence + 3 value + `leafKey`), all taking a display label |
| `AgentDefinition` / `LlmModel` / `Version` | — | **unchanged.** Both fixes are inside the reading layer |
| Exception types thrown | `ConfigurationException`, `AgentDefinitionParseException` | same types, new messages; no new exception class |

---

## 6. Failure modes

| # | Situation | Behaviour |
|---|---|---|
| 1 | `${VAR}` unset, anywhere in the config file | `ConfigurationException: Environment variable not set: VAR (at memory.dreamer.scorer.embedding.apiKey)`. Was: silent literal outside the five visited fields. **Intended, and a behaviour change** |
| 2 | Two sibling keys expand to the same name | Refused, naming both spellings, the expansion and the path — measured identical to today for `modelCapabilities`. When the expanded name is one no config class declares, Jackson's unknown-property error on the first key arrives first (§4.1) |
| 3 | `${VAR}` expands to something the field cannot bind (`TIMEOUT=abc`) | `ConfigurationException: Invalid configuration structure in: <file>`; the key is in the cause, visible only under `--verbose`. **This is registered item L-5 and it is deliberately not closed here** — §8 |
| 4 | A value that should contain a literal `${` | Impossible; there is no escape. OQ-1 |
| 5 | Expanded value itself contains `${OTHER}` | Left literal — one pass, not recursive. Same as today. Pinned |
| 6 | Expanded value contains `$` or `\` | `Matcher.quoteReplacement`, carried over unchanged |
| 7 | A scalar YAML reads as a boolean or a number (`off`, `0755`, `1.10`, `1e3`) | **Reaches its deserializer exactly as written**, whether or not the file contains placeholders. This is the property revision 1 lost; measured in probes E and F and pinned by four tests in §7.1 |
| 8 | Malformed YAML | `Invalid YAML syntax in:` for an error the pre-advance reaches, `Invalid configuration structure in:` for one deeper in the stream — **measured identical to today in both shapes** |
| 9 | Empty or comment-only config file | `Invalid configuration structure in:` from `load()`, `Failed to load configuration from:` from `loadDefault()` — both exactly as today. No null guard on the bound object is needed; `nextToken()` does return `null` there, and returns it rather than switching on it |
| 10 | `---`-only document | Binds `null`, then NPEs in `validateConfig`. **Pre-existing on `main` and unchanged** — §8 |
| 11 | `.inf` anywhere | `Invalid configuration structure in:` — exactly as today, because the numeric decode is lazy |
| 12 | Very deep config nesting | Bounded before the delegate sees it: snakeyaml's `LoaderOptions` nesting-depth limit applies at parse time |
| 13 | Frontmatter number that does not parse | `AgentDefinitionParseException` naming key and value. Wrapped by the bundle loaders as `AgentDefinitionLoadException("Failed to load agent bundle: <name>")` (`FileSystemAgentBundleLoader:136`, `ClasspathAgentBundleLoader:225`), which `AimonCli:129-133` prints as `Unexpected error: …`, with the precise cause **only under `--verbose`**. The parser's message is right; the presentation is a gap in files this task does not own — §8 |
| 14 | Frontmatter number out of range for its target (`temperature: 5.0`) | Unchanged: `LlmModel.Builder.build()` throws `IllegalArgumentException`, wrapped as `Failed to parse agent definition` with the reason in the cause |
| 15 | `model.temperature: .nan` | Unchanged — passes through; `LlmModel`'s range check cannot see NaN. §8 note |
| 16 | `maxIterations: 0` or negative | Unchanged: parses, and nothing validates it. §8 note |

---

## 7. Test strategy

Every new test below is written to fail on `main` first: run the new test class against the unmodified
loader/parser before applying the fix, and record the failure.

### 7.1 `#53` — `CliConfigLoaderTest`, plus a new `PlaceholderExpandingParserTest`

**Red before, green after:**
1. `memory.dreamer.scorer.embedding.apiKey: "${OPENAI_KEY}"` resolves — **the issue's reproduction, verbatim**.
2. `memory.storagePath: "${HOME}/x.jsonl"` resolves.
3. `mcp.servers[0].args: ["-y", "${PKG}"]` resolves (array reach).
4. `llm.timeout: "${TIMEOUT}"` binds an `Integer` (non-`String` reach).
5. `llm.anthropic.thinkingMode: "${MODE}"` binds the enum — **replaces**
   `environmentVariablesAreNotExpandedHere` (`:1026`), whose name and comment invert.
6. **The same five, written unquoted** (`apiKey: ${OPENAI_KEY}`), because a quoted scalar is the case
   that survives a lossy mechanism and an unquoted one is not — the gap in revision 1's probe A.
7. An unset variable inside the `memory` block fails naming the variable **and** the key path.
8. Two sibling `mcp.servers[0].env` keys expanding to one name are refused (the guard is general now).

**Fidelity — red on revision 1's mechanism, green before and after this one.** These are the tests that
would have caught the rejected design, so they are named individually rather than folded into a suite:
9. `llm.anthropic.thinkingMode: off` unquoted binds `OFF` **in a file that also contains a `${VAR}`**,
   so the delegate is provably active on the same stream.
10. `llm.anthropic.thinkingMode: no` and `: false` are still refused, and the message still names the
    written text.
11. A `String`-typed key whose value YAML reads as a boolean or number (`0755`, `1.10`, `1e3`, `yes`)
    arrives exactly as written — `cli.prompt` is the available `String` key.
12. A config file with **no** `${…}` anywhere binds byte-identically to what it binds on `main`.

**Regression pins — green before and after, unmodified.** The two suites the review names are the ones
most exposed by moving expansion across the bind boundary, and both must run untouched:
13. **`@Nested class AnthropicThinkingBlock` in full** (`:782-1042`) — twelve `everyModeSpellingBinds`
    iterations, `unquotedOffBinds`, `otherYamlBooleansAreStillRefused`, all four keys, the display
    spellings, and the two rejection tests.
14. **`@Nested class ModelCapabilityDeclarations` in full** (`:557-778`) — including
    `resolvesEnvironmentVariablesInTheKey` (`:704`, which is also the pin for `currentName()`-only rewriting,
    §4.1) and `refusesTwoKeysThatExpandToTheSameName` (`:725`).
15. The five existing env tests (`:151-270`), `testLoadInvalidYaml` (`:113-128`), `testLoadInvalidStructure`
    (`:130-146`) — load-bearing: `timeout: "not-a-number"` has no `${}` and must still fail to bind — and
    the three `loadDefault` tests.
16. `@Nested class SharedReasoningEffort` (`:487`) and `CompleteConfiguration` (`:439`), untouched.

**New `PlaceholderExpandingParserTest`** drives tokens directly, without files: a rewritten token's
`getText()`, `getTextCharacters()`/`getTextLength()`/`getTextOffset()` and `getValueAsString()` all agree;
`hasTextCharacters()` is `false` for a rewritten token; a non-rewritten token is passed through
identically; `${}` with an empty name is left literal; one pass only; collision at two nesting depths;
`nextValue()` and `skipChildren()` keep the level bookkeeping consistent (the two overrides §4.1 calls
insurance — this is where they are exercised, since the config tree does not reach them). It also covers
`placeholderFailureIn(…)` directly, which is how `loadDefault()`'s unwrap is tested: the shipped default
resource cannot be made to carry an unset variable from a test, so the end-to-end path is `load()`'s and
the helper carries the rest.

### 7.2 `#74` — `MarkdownAgentDefinitionParserTest`, new `@Nested class NumericFrontmatter`

Red before, green after — each asserts `AgentDefinitionParseException` whose message contains the
qualified key **and** the written value:
1. `model.temperature: hot` · 2. `model.temperature: ""` · 3. `model.temperature: 1,5`
4. `model.maxTokens: many` · 5. `model.topP: high`
6. `maxIterations: fifty` — the fourth call site
7. `model.maxTokens: 4096.5` (silent truncation gone) · 8. `maxIterations: 9999999999` (silent narrowing gone)
9. `model.temperature: true` (a `Boolean` is not a number)
10. `model.name:` present-and-null (silent `gpt5.1` gone) · 11. `version:` present-and-null

Green before and after:
12. absent `maxIterations` → `Integer.MAX_VALUE`; absent `model` → `LlmModel.builder().build()`;
    absent `version` → `1.0.0`.
13. `temperature: 0.7`, `topP: 0.9`, `maxTokens: 4096` bind as before.
14. `maxTokens: 4096.0` binds `4096`; `temperature: 1` binds `1.0`; `temperature: 1e-3` binds `0.001`.
15. The four existing `model.reasoningEffort` tests, unchanged.
16. The bundle-loader suites (`FileSystemAgentBundleLoaderTest`, `ClasspathAgentBundleLoaderTest`,
    `AdaptiveAgentBundleLoaderTest`, `AimonRuntimeHintsTest`) parse the shipped definitions — the pin
    that no in-tree definition regressed, including the two failure fixtures, which must keep failing for
    the reasons they already fail.

### 7.3 Gate

`./gradlew format` then `./gradlew checkAll` (= `checkFormat` + `checkStyle` + every module's `test` +
`:aimon-bom:verifyBom`). Coverage verification is **not** in `checkAll`, so
`:aimon-cli:jacocoTestCoverageVerification` and `:aimon-core:jacocoTestCoverageVerification` are run
separately against their floors (`aimon-cli=62`, `aimon-core=86`). Then the three doc scripts. Report
measured counts — tests run, failures, the two coverage percentages, the script tallies — not "it
passed". No live-API probe is needed: neither fix touches a provider request.

---

## 8. Findings outside these two issues — for `deviations.md` and the handoff, **not** the diff

1. **L-5 stays open.** `llm-config-surface-open-items.md` L-5 — the CLI wraps every Jackson mapping
   failure as `Invalid configuration structure in: <file>` without naming the key — is *adjacent* and
   untouched. Its own trigger is "the next phase that adds a CLI config key"; this phase adds none, and
   appending the cause message is a change to every CLI key's failure experience. Not closed, not
   widened, index row unchanged.
2. **The parser's precise message is invisible without `--verbose`** (§6-13). Fixing it means touching
   `AimonCli` or the bundle loaders, neither of which this task owns.
3. **A `---`-only config file NPEs in `validateConfig`.** Pre-existing on `main` (`readValue` returns
   `null` for a null document, both today and through the delegate). Revision 1 would have masked it as
   a side effect of a null guard it needed for its own reasons; this design neither fixes nor worsens it.
4. **`MemoryConfig.storagePath` does not expand `~`.** `AgentSetupFactory:987` calls `Paths.get(...)`, so
   `~/.aimon/...` creates a literal `./~` directory. After this change `${HOME}/...` is a working
   alternative; the `~` gap is a separate defect.
5. **Two unchecked casts in the parser** (`model`, `variables`) produce `Failed to parse agent
   definition` for a wrong-shaped block. Loud but unnamed — the same class of complaint as L-5.
6. **`extractDouble` still accepts `.nan` / `.inf`** and `LlmModel`'s range check cannot see NaN.
7. **`maxIterations` has no positivity check** despite `AgentDefinition.getMaxIterations`'s javadoc.
8. **`MemoryConfig.ingest` is undocumented in `default-config.yaml`**, though `MemoryConfig:79-80` binds it.
9. **`README.md:176`'s `# ${ENV} interpolation supported`** sits on the `apiKey` line as if it were a
   property of that key; after this change it is true of every value. Root markdown, outside the file list.
10. **The `modelCapabilities` duplicate-key fix from #46 is already on `main`** (`:143-161`, with a test).
    This design **preserves and generalises** it rather than re-doing it.

---

## 9. Open questions — unresolved from the task statement, not silently assumed

- **OQ-1 — no escape for a literal `${`.** Under a universal rule there is no way to write a literal
  `${FOO}` in any value. The one plausible victim is `mcp.servers[].args`, where a stdio server's
  argument could carry a placeholder meant for a child shell (`command` is already expanded today, so
  only `args` changes). Recommendation: ship without an escape; if one is wanted, `$${VAR}` → literal is
  the conventional form and is a two-line follow-up. **Needs a yes.**
- **OQ-2 — is the reviewer content that `${VAR}` now works on non-`String` keys?** It is the consequence
  that makes the rule one sentence (§2.2 constraint A), and it deletes a test that asserted the
  limitation. A reviewer who wants that asymmetry kept is choosing A3. **Needs a yes.**
- **OQ-3 — how far #74 goes.** The issue names three keys; the rule as designed also changes
  `maxIterations` (unavoidable — same method), `model.name`, `version`, and the two silent numeric
  conversions inside `extractInt`. My reading is that the issue's "if there is more of it, fixing it is
  in scope" authorises all of them, and `review-1.md` agrees. **A reviewer who wants the minimum can cut
  `model.name` and `version` without touching anything else**; the other four cannot be separated from
  the two named methods. **Needs a preference.**
- **OQ-4 — `cli.prompt` now expands.** A consequence of the universal rule, not a decision. Exempting one
  key would reintroduce a list of one.
- **OQ-5 — `mcp.servers[].env` map keys now expand** (values already did). Harmless and consistent, but a
  behaviour change nobody asked for.
- **OQ-6 — the dated note on `reasoning-effort-config-surface.md` §16 O-3.** I judge it required (a design
  doc otherwise keeps saying a fixed defect is unfixed), and it is cuttable in one line.
- **OQ-7 — the doc footprint is the only real collision surface with the sibling runs.** Exactly four doc
  edits, and their size is the mitigation: **(a)** one new `### 3.1` plus one replaced paragraph in
  `aimon-core-integration-via-cli-reference.md` and the mirror in its `.en.md`; **(b)** one bullet in
  `memory-usage-guide.md` and its `.en.md`; **(c)** one appended `**→ #53 이 고쳤다**` line in
  `model-capability-config-key.md`; **(d)** one appended dated note in
  `reasoning-effort-config-surface.md` §16. `llm-capability-config-gaps` plausibly edits (a)'s §4.x
  sections and (c)/(d)'s files, so (c) and (d) stay one line each and (a) stays two edits. `RUN.md` says
  rebasing is the launcher's problem; naming it so nobody is surprised.

---

## 10. What is *not* changed, deliberately

- No config key, class, field or accessor is renamed → `docs/migration/rename-maps.md` gains nothing.
- No registered backlog item is opened or closed → `docs/backlog/` and its `README.md` index are untouched.
- The `memory` block stays commented out in `default-config.yaml`; `MemoryConfig.isEnabled()`'s
  three-required-field rule is untouched.
- `MemoryDreamerConfig.notReadyReason()`'s fail-soft dreamer behaviour is untouched: a *blank* apiKey
  still disables the dreamer with a reason; what changes is that a `${VAR}` apiKey is no longer
  non-blank-but-wrong.
- The Spring starter is untouched. It already resolves placeholders before binding; the point of §2.2 is
  that the two surfaces now agree, which is a sentence in the docs and no code.
- `LlmModel`, `AgentDefinition` and `Version` validation are untouched.
- The token stream's shape is untouched: no token is added, removed, reordered or retyped.

---

## 11. Measurements

Run on this machine against the versions the build resolves (jackson-databind 2.22.2,
jackson-dataformat-yaml 2.22.2, snakeyaml 2.7 from the Gradle cache), not inferred. Probes D–I use a
fixture mirroring the real config classes, including a copy of `ThinkingModeDeserializer`.

- **Probe A (revision 1, superseded).** Tree-path expansion binds `Integer`, enum and map keys, and
  preserves the three exception classifications. Its gap, named by `review-1.md`: it exercised only
  `mode: "${MODE}"` — a **quoted** scalar, the case that survives a lossy round trip. Every expansion
  case in probes E–F is now run quoted *and* unquoted.
- **Probe C — snakeyaml scalar resolution** (unchanged, re-run on 2.5 and 2.7 with identical results);
  the table in §3.3 is its output. `1e-3` → `Double 0.001`, `t4:` → `null` with `containsKey` `true`,
  `9999999999` → `Long`.
- **Probe D — the tree round trip loses the written scalar.** On a `String` field: `off`→`false`,
  `on`/`yes`→`true`, `no`→`false`, `0755`→`493`, `1.10`→`1.1`, `1e3`→`1000.0`. On the enum with the
  `getText()` deserializer: `off`/`OFF`/`Off` bind `OFF` today and become
  `InvalidFormatException … from String "false"` through `treeAsTokens`, indistinguishable from `no` and
  `false`. This is A7's rejection.
- **Probe E — the delegate, end to end.** 34 cases. Fidelity: `off`/`OFF`/`Off` → `OFF`; `no`/`false`
  refused naming the written text; `0755`, `1.10`, `1e3`, `yes` exact. Expansion: quoted, unquoted and
  mixed (`pre-${VAR}-post`); `Integer`; plain enum; custom-deserializer enum; the `memory` embedding
  `apiKey`; `memory.storagePath`; `mcp` `args` array and `env` values; `modelCapabilities` map keys;
  `mcp` `env` map keys. Preserved failures: unknown property (top-level and nested), bad enum,
  `Integer`-from-string, YAML syntax error, empty, comment-only. One pass confirmed; `${}` left literal.
- **Probe F — paths, unwrapping, char accessors, real sources.** Error paths name the right key at depth:
  `memory.dreamer.scorer.embedding.apiKey`, `memory.storagePath`, `mcp.servers[].args[]`,
  `mcp.servers[].env.T`, `llm.${MISSING}`. Collision message carries both written keys, the expansion and
  the path. `getText()` / `getTextCharacters()` / `getValueAsString()` agree on a rewritten token and
  `hasTextCharacters()` is `false`. Works from `createParser(File)` and `createParser(InputStream)`.
- **Probe G — what does *not* need overriding.** Removing the `getCurrentName()` override (deprecated in
  2.22.2) changed **no** outcome across probe F's whole matrix, map-key expansion included. Also measured
  here: a collision between two placeholders expanding to the same *declared* property name is pre-empted
  by Jackson's unknown-property error on the first key — the limit §4.1 states.
- **Probe H — where each failure is thrown.** `.inf` arrives as `VALUE_NUMBER_FLOAT` **without** throwing
  at tokenisation; a malformed quote throws `MarkedYAMLException` on the second token; a bad indent throws
  later in the stream. This is why the pre-advancing `nextToken()` separates the two YAML shapes correctly.
- **Probe I — message parity, the acceptance test for the mechanism.** Twelve inputs — early YAML error,
  late YAML error (indent), late YAML error (tab), `.inf`, empty, comment-only, `---`, unknown property
  at two depths, bad enum, `Integer`-from-string, and a valid file — compared between today's
  `readValue(File, Class)` and `createParser` + `readValue(JsonParser, Class)`, **with and without** a
  pre-advancing `nextToken()`. **All twelve classify identically in both arrangements (12/12, 12/12)**,
  which is what retired the pre-advance.
- **Baselines before any change:** `check-doc-links.py` 235 files / 2316 links / 0 broken;
  `check-translation-staleness.py` 32 / 32 up to date / 0 stale / 0 unresolvable;
  `check-translation-structure.py` 32 / 32 structurally identical / 0 findings.
- **Read, not measured:** all 308 lines of `MarkdownAgentDefinitionParser.java` for §3.1;
  `AnthropicProviderConfig:163-201` for constraint B; the twelve `at.aimon.cli.config` classes for the
  leaf inventory behind §2; the frontmatter of all 17 in-tree `agent.md` files plus the three empty
  `agent-templates/` files for §4.2; `AgentSetupFactory:1208-1226` and `DreamerJob:80-81` for §1;
  `JsonParserDelegate`'s method list (91 declared members by `javap`) for the override table in §4.1.

---

## 12. 구현이 이 설계와 갈라진 자리

**구현 후에 쓴 절이다.** §0~§11 은 승인된 설계이고 — 리뷰가 짚은 사실 오류를 본문에서 바로잡은 것을
제외하면 그대로다 — 이 절은 그 본문과 트리가 갈라진 자리를 적는다. `docs/design/README.md` §3.2 가 금지하는
"rev.1/rev.2 정정 이력" 이 아니다: 정정은 본문에 반영되어 있고, 여기 있는 것은 **구현이 설계를 따르지 않은
자리**뿐이다.

### 12.1 승인 리뷰가 코드에 대해 짚은 것 — 전부 반영했다

`review-2.md` 는 PASS 였고 블로킹이 없었다. 아래 다섯은 그 non-blocking 중 **코드에서 실행 가능한 것**이며,
전부 이 구현에 들어갔다.

| # | 갈라진 자리 | 처분 |
|---|---|---|
| 1 | §4.1 의 `nextToken()` 스니펫이 입력 끝에서 NPE 를 낸다 — `delegate.nextToken()` 은 거기서 `null` 을 돌려주고, 빈 파일·주석만 있는 파일은 **첫 호출**이 그렇다. 맨 NPE 는 `load()` 의 세 `catch` 를 전부 빠져나가므로 §6-9 가 약속한 "오늘과 같음" 이 성립하지 않는다 | `null` 을 switch 앞에서 돌려준다. 스니펫과 §6 행 9 를 본문에서 고쳤고, `PlaceholderExpandingParserTest.endOfInputIsNull` 이 핀이다 |
| 2 | 충돌 검출이 **모든** 필드 이름을 기록하는지 `${` 를 가진 것만 기록하는지 설계가 말하지 않았고, 두 해석 모두 적히지 않은 결과가 있다 | 아래 §12.2 에서 결정하고 근거를 적었다. 본문 §4.1 에 항목을 추가했고, 두 모양 모두 테스트가 있다 |
| 3 | `getText(Writer)` 와 `getValueAsInt/Long/Double/Boolean` · `getBinaryValue` 가 위임되어 **확장되지 않은 원문**을 본다 | 아홉 개의 한 줄 오버라이드를 `rewritten` 에서 파생시켰다(`NumberInput` 으로 Jackson 의 수치 의미를 그대로 쓴다). §4.1 의 오버라이드 표에 두 행이 늘었다. 오늘의 설정 클래스가 닿지 않는다는 것은 다시 확인했다 — 고침이 아니라 잔여물 제거다 |
| 4 | §4.1 의 "One measured limit" 이 **반대로** 적혀 있다 | 직접 재현해 §12.3 에 적었고 본문 두 자리를 고쳤다 |
| 5 | 선행 `nextToken()` 이 load-bearing 하다고 적혀 있으나 아니다 | 열두 입력으로 있을 때와 없을 때를 재서 **12/12 동일**임을 확인하고 **제거했다**. 그 결과 §4.1 의 `ConfigurationException` 통과 절도 없어졌다 — §12.4 |

여섯 번째(줄 번호 일곱 건)는 본문에 반영했다. 일곱 번째(`tags` · `variables` 의 present-null)는 §12.5.

### 12.2 충돌 검출의 사정거리 — 두 개의 결정

**기록은 모든 이름에 대해 하고, 거절은 확장이 충돌을 만들었을 때만 한다.** 두 반쪽은 서로 다른 결정이고
각각 이유가 다르다.

| 반쪽 | 선택 | 이유 |
|---|---|---|
| 무엇을 기록하나 | **전부** | 오늘의 `resolveModelCapabilityKeys` 는 **선언된 모든 키**를 확장기에 통과시키므로 `modelCapabilities: { prod: …, ${P}: … }` 를 `P=prod` 에서 거절한다(**실측: 거절**). 플레이스홀더가 있는 이름만 기록하면 그 거절이 조용히 사라진다 — §2.1 이 데코레이터가 반드시 지켜야 한다고 적은 바로 그 동작이다 |
| 언제 거절하나 | **두 written 철자 중 하나가 공통 확장 결과와 다를 때만** | 전부 기록하면 **문자 그대로 같은 키가 두 번** 적힌 것도 토큰 층에서 처음으로 보이게 된다. Jackson 은 오늘 그것을 last-wins 로 받는다(**실측: `OK`**). 평범한 yaml 중복 키에 기동 실패를 새로 만드는 것은 확장이 만들지 않은 다른 계층의 결함이고, 어느 이슈도 요구하지 않았다 |

두 번째 반쪽은 트리에 조용한 값 손실을 남긴다. 그것을 숨기지 않고
[`../../backlog/config-value-expansion-open-items.md`](../../backlog/config-value-expansion-open-items.md)
**CE-2** 로 등록했다. 같은 문서의 **CE-1** 은 §9 OQ-1(리터럴 `${` 를 적을 escape 가 없다)이 이 국면 밖으로
나가므로 함께 올라간 것이다. 나머지 OQ 는 이 국면 안에서 끝났으므로 여기 남는다.

### 12.3 "One measured limit" 은 반대였다 — 직접 잰 결과

| 적힌 것 | 확장 결과 | 실제 |
|---|---|---|
| `${A}` · `${B}` → `apiKey` (**선언된** 프로퍼티) | `apiKey` | 충돌 메시지가 **나온다** — ``Configuration keys `${A}` and `${B}` both expand to `apiKey` …`` |
| `${A}` · `${B}` → `nosuch` (**선언되지 않은** 이름) | `nosuch` | 첫 키에서 `UnrecognizedPropertyException` 이 먼저 나 `Invalid configuration structure` 가 된다 |

설계는 이 둘을 바꿔 적고 있었다. 실제 동작이 적힌 것보다 **낫고**, 어느 쪽이든 잃는 것은 없다 — 선언되지
않은 이름은 어느 경로로도 기동을 실패시킨다.

### 12.4 선행 `nextToken()` 과 `ConfigurationException` 통과 절이 없다

전자는 열두 입력으로 재서 있을 때와 없을 때가 **12/12 동일**이었다(§11 probe I). 후자는 그 정직성 정리의
따름결과다 — `ConfigurationException` 은 `RuntimeException` 이므로 **감싸이지 않고 올라올 때는 `load()` 의
어느 절도 잡지 않아 스스로 전파되고**, 감싸여 올라올 때는 `JsonMappingException` 가지의 cause 체인 스캔이
잡는다. 설계가 적은 통과 절은 도달 불가능한 코드였다. `loadDefault()` 는 설계가 적은 이유 그대로 자기
`IOException` 가지에서 unwrap 이 필요하다.

`placeholderFailureIn` 은 §5 의 표가 적은 `CliConfigLoader` 가 아니라 `PlaceholderExpandingParser` 의
static 이다 — 두 호출 지점이 모두 쓰고, 그 클래스의 예외에 대한 일이기 때문이다. §7.1 이 적은 것과 달리
직접 단위 테스트하지 않고 `load()` 를 통해 끝에서 끝까지 검증한다.

### 12.5 §3.1 의 표가 말하지 않은 선 하나

`tags:` 와 `variables:` 를 **값 없이** 적으면 각각 `Set.of()` 와 (`getOrDefault` 가 present-null 에는
기본값을 주지 않으므로) `null` → `Map.of()` 가 된다. 조용하고, §3.2 가 지목한 `map.get(key) == null` 의
같은 혼동이다. 이 설계가 실제로 그은 선은 이것이다 — **present-null 인 컬렉션 키에 빈 컬렉션을 넣는 것은
값을 지어내는 것이 아니지만**(빈 컬렉션은 그 키의 항등원이다), present-null 인 **스칼라** 키에 `1.0.0` 이나
`gpt5.1` 을 넣는 것은 지어내는 것이다. 표가 전 추출 지점을 다룬다고 말하면서 이 선을 적지 않았으므로 여기
적는다. 바꾸지 않았다.

### 12.6 #74 의 "다른 곳에도 있는가" — 파일 밖에서도 한 번 더 셌다

이슈가 답을 요구한 것은 **그 파서 안**이고, §3.1 의 표가 308줄 전수 조사로 답한다. 구현 중에 파일 밖도
한 번 셌다: 트리 전체에서 `extractInt` · `extractDouble` 이라는 이름을 쓰는 곳은
`MarkdownAgentDefinitionParser` **하나뿐**이고, 같은 종류의 이웃인
`SubagentContentParser.parseMaxIterations` 는 **이미 엄격하다** — `Integer` 가 아니면
`SubagentParseException` 을 던지고 타입 이름까지 말한다. 즉 이 결함은 이 한 파일의 것이었고, 옆 파서는
이미 이 설계가 §3.2 에 적은 규칙대로 동작하고 있었다.

### 12.7 문서 배치 — `design/integration/`

`docs/design/README.md` §1 은 디렉토리 축이 `docs/features/` 를 그대로 따른다고 적고, 거기서 벗어나는
자리는 `documentation/` **하나뿐**이라고 명시한다. `features/config` 가 없으므로 새 `design/config/` 는
두 번째 미선언 예외가 된다. `integration/` 은 그 README 가 "바깥과 만나는 자리" 로 부르는 절이고 이미
설정 표면 문서(`spring-boot-starter.md`)를 갖고 있다 — 운영자가 쓴 yaml 도 운영자가 쓴 프론트매터도 바깥이
프레임워크와 만나는 자리다.

같은 README §3.3 은 *"본문에 `file:line` 을 박지 않는다"* 고 적는데 이 문서는 그것으로 지어져 있고 두 번의
리뷰가 그 형태로 검증했다. 줄 번호를 걷어내는 것은 정정이 아니라 재작성이므로 하지 않았고, **틀린 일곱
개만 고쳤다.** 알려진 긴장으로 적어 둔다.

### 12.8 게이트 실측

- `./gradlew format` → BUILD SUCCESSFUL, 그 뒤 `checkAll` → **BUILD SUCCESSFUL**
  (`checkFormat` + `checkStyle` + 전 모듈 `test` + `:aimon-bom:verifyBom`). 전체 실행 5분 6초,
  마지막 확인 실행 3분 38초.
- 테스트 **10,675건 / 실패 0 / 에러 0 / 스킵 64** (스킵은 전부 docker·live-key 태그). 그중
  `aimon-cli` 347, `aimon-core` 8,128.
- 커버리지 — `aimon-cli` **LINE 68.03%** (바닥 62), `aimon-core` **LINE 87.36%** (바닥 86). 두
  `jacocoTestCoverageVerification` 모두 통과.
- 문서 스크립트 — `check-doc-links.py` 237 파일 / 2,326 링크 / **깨짐 0**;
  `check-translation-staleness.py` 32쌍 / **낡음 0 / 해석 불가 0**;
  `check-translation-structure.py` 32쌍 / **불일치 0**.
- **먼저 빨갛게 만든 것**: #53 의 새 테스트 9건이 `main` 의 로더에서 실패하고 #74 의 새 테스트 8건이
  `main` 의 파서에서 실패하는 것을 확인한 뒤 구현을 되돌렸다. 나머지 신규 테스트(§7.1 의 9~12, §7.2 의
  12~14)는 **양쪽에서 초록인 보존 핀**이며 그것이 그 테스트들의 일이다.
