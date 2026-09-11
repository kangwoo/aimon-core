# Changelog

All notable AIMON changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows
semantic versioning at the module level (each `aimon-*` module published to Maven
Central is versioned independently).

## [Unreleased]

### Docs: two feature guides stop giving a subagent a model alias, and the override's javadoc stops calling it one

- **The subagent development guide and the built-in agent skill guide (ko + en) no longer give their example
  subagents the model `sonnet`** (#132). The four examples name no model. A comment in its place says the subagent
  runs on its parent's model, usually the main agent's; that an id is worth writing only to run on another model the
  configured provider serves; and that it goes to the provider as written. The subagent guide's `resolvedModel()` row
  says the name is sent as written and may be empty, as `SubagentBehaviorSupport` does. Backlog `L-27` now lists both
  guides' sites.
- **Javadoc.** The per-invocation model override in `SubagentExecutionEnvironment`, `SubagentExecutionContext` and
  `DefaultSubagentExecutor` is a model name sent as written, not an alias. No signature changed.
- **`aimon-llm-anthropic` README.** `temperature` has no default. A call's `LlmModel` value is sent first, and with
  neither that nor a configured value, none is sent. The table said `0.0`.
- **The bundled skill-creator's `benchmark.json` sample** writes `"executor_model": "<model-name>"`, the placeholder
  `aggregate_benchmark.py` writes, instead of `claude-sonnet-4-20250514`.
- **Backlog.** `L-19` records that #116 changed the default model its registered sentence names.

### Docs CI: the two doc checks write down where they read headings differently, and the link check pins its side

- **Where the link check and the backlog check read a heading differently is written down** (#121), in
  `docs_tree.anchors_of`'s docstring, with the reason each is left. A heading inside an HTML comment block gets an
  anchor, so a link into one passes and lands at the top of the page. A heading behind one to three spaces, `>` or
  a list marker gets none, so a correct link to one fails, and the backlog check reports it as `unread-heading`. A
  heading after a `<!--` that `unfence` exposes inside a fence it pairs differently from the page is anchored, as
  the page shows it, and the backlog check hides it. That last edge is why the backlog check's comment reading is
  not taken into `anchors_of`: the link check would start failing correct links to headings the page shows.
- **`check-doc-links.py --self-test`**, run by the `docs-links` job after the check, pins the link check's side of
  each difference that docstring names, and of a `<details>` block. The backlog check's `--self-test` gains the
  cases for its side: the mis-paired fence, a `<details>` block with no blank line, and the fence and comment
  shapes SHARP EDGES now describes.
- **Decision 6 names the raw HTML blocks the backlog check does not follow**: every kind in CommonMark 0.31.2 §4.6
  but the comment. A heading inside `<details>` with no blank line after the opening tags is counted though the
  page shows no heading. `docs/backlog/README.md` tells authors, and its rule seven now names every displaced
  heading that fails: an ID heading, a numbered `## N.` and a state record.
- **Wording.** SHARP EDGES says which fences and comments are recognised in a list item's continuation.
  `CONTRIBUTING.md` and `.ko.md` say "the anchor failure" where "the second" counted failures, not checks. The
  translation glossary counts four backlog words and defines `접힘` as the backlog README does. `features.md` and
  `.en.md` link the feature-guide index's `README` instead of its directory, which mkdocs left unresolved.
- **Registered:** backlog `T-5` (a link to a directory passes the link check, and mkdocs leaves it unresolved) and
  `T-6` (YAML front matter is read as text, so a `#` line in it becomes an anchor).

### Docs: a design record committed as its approved text keeps its test strategy and line citations

- **`docs/design/README.md` §3.4 exempts such a record from part of §3** (#122). The exemption covers a record
  whose `Status` names, by number, the section where the build's departures begin, and says the body before that
  section is the text design review approved, unchanged. Twelve records carry this marker today. Such a record
  keeps its test strategy, implementation order and `file:line` citations, and needs no decision table or
  reference file map. A correction goes into a section after the boundary, never into the body.
- **A citation is dated by its line, not its file.** A body citation is read at the base commit the record names.
  A line after the boundary is read at the commit `git blame` gives for that line, which may be a merge. The
  commit that added the file dates neither.
- **Not lifted:** checkboxes, progress tables and phase logs stay out of every record, and so do usage and
  troubleshooting material. The one allowance is a configuration snippet whose shape is itself the decision. A
  record with a test strategy or citations but no marker is not exempt. Sixteen such records exist, and none was
  edited. Backlog `T-7` records that no check looks for the marker.

### Anthropic client: the built-in default model is one the Messages API serves

- **`aimon-llm-anthropic`: `AnthropicConfig`'s default model is now `claude-sonnet-4-5`** (#116). The Anthropic
  Messages API answered the old default, `claude-sonnet-4-20250514`, with HTTP 404 `not_found_error` on 2026-09-11,
  and so did `GET /v1/models/…`; `claude-sonnet-4-5` was served, as `claude-sonnet-4-5-20250929`. The default applies
  wherever no model is written: an `AnthropicConfig` built without `.model(...)`; the CLI under `provider: anthropic`
  with no `llm.model` — wiki page generation, a main agent whose definition has no `model.name`, a subagent that names
  no model under one, and every memory component, whose startup line now names `claude-sonnet-4-5`; and the Spring
  Boot starter with `aimon.llm.provider=anthropic` and no `aimon.llm.model`. Against the Anthropic Messages API each
  of those requests failed before, so no request that worked there changes. **Behind a `baseUrl` gateway** that still
  served or allowed the old name, such a deployment now sends `claude-sonnet-4-5` instead; a deployment that names its
  model is unaffected.
  - It is the model `default-anthropic` already runs on, and the built-in capability table already describes it; no
    row was added. With a `thinkingMode` set and no model written, `auto` now sends budgeted thinking where it sent
    none, and `adaptive` is translated to budgeted with the existing warning; `extended` and the shipped `off` are
    unchanged. Its price and context-window rows are the ones the old name matched.
  - Backlog `L-24` is closed. **This supersedes two sentences in the entries below**: #109's, that the default memory
    can fall back to is unmeasured and registered as `L-24`, and #45's, that `AnthropicConfig` keeps its default
    because `claude-sonnet-4-20250514` is current.

- **Documentation** (#118). The CLI guide says what a subagent that names no model, and a definition without
  `model.name`, run on when `llm.model` is not set (ko + en), and so does `default-config.yaml`'s `llm.model` comment.
  Core javadoc for a subagent's `model` — `Subagent`, `SubagentMetadata`, `SubagentContentParser`, the
  `at.aimon.core.subagent` package and the public SPI `SubagentBehaviorSupport` — no longer offers `sonnet`, `haiku` or
  `opus`, and says a model id is sent as written; `resolvedModel()`'s name may be empty. No signature changed.

### Agent loop: a skill's loop refuses a cut tool call too, a fork stops after three stalled iterations, and the Task tool says when a fork's answer was cut

- **A slash skill's tool loop no longer runs a tool call cut at `max_tokens`** (#115). `LlmSkillExecutor`, the loop a
  skill invoked as `/<skill>` runs, never read the stop reason: a call cut mid-argument ran with whatever arguments had
  arrived, and a cut final answer came back as a plain success. It now answers a cut response as both agent executors
  do. None of the response's calls is dispatched, through the bound dispatcher or the fallback; each is answered with
  the same `Cut off at max_tokens: …` error result, and the loop continues. A cut final answer is still a success, and
  its text now ends with `[System: response truncated at max_tokens]` — the text the skill result, the turn's final
  answer and the transcript carry. A skill result has no completion reason, so a turn that ran such a skill still ends
  `COMPLETED` (backlog `L-26`). Each case logs a WARN naming the skill — for a cut call, the 1-based iteration and the
  tool names, not their arguments — and the client's own `… truncated due to max_tokens limit` WARN still fires on
  this blocking path.
  - **A person sees less of a refusal here than on a turn.** A turn's refused call emits `ToolUseStarted` and
    `ToolResultReady`, which the REPL prints. The command path emits no lifecycle event for any skill call, refused or
    run, so a single refused call shows only in the log; a streak of them ends the skill with the stop message below,
    which the REPL shows.

- **A slash skill stops after three consecutive iterations whose tool calls all fail** (#115). It used to run to its
  `max-iterations` (100 unless the skill sets one), and a slash command cannot be interrupted. It now fails with the
  turn's stop message, `Execution aborted: 3 consecutive tool-only iterations made no progress (all tool calls
  failed)`, whatever made the calls fail: a tool error or an unknown tool, an allow-list refusal on the dispatcher
  path, a PermissionRequest or PreTool block, the side-effect approval gate denying a call — so a user who declines a
  mutating call three iterations running ends the skill — or refused cut responses. One successful call resets the
  streak. A permission violation on the fallback path still fails the skill at once.

- **A subagent fork stops after three consecutive iterations whose tool calls all fail, and ends `ERROR`** (#115). A
  fork had no stalled-iteration guard, so only `maxIterations` (1000 unless the subagent sets one; no in-tree caller
  gives a fork a budget), a budget, cancellation or an error stopped it. It now stops on the turn's guard, for every
  cause above — including the approval gate denying a call because a fork has no channel to ask through. OnStop hooks
  fire with `success=false`, the progress stream ends `[ended: <stop message>]`, and every reader of the reason gets
  `ERROR`, a value it already handles:
  - task records persist `ERROR`, and a background task is `FAILED` with the stop message as its notification detail;
  - `AgentStepResult.isComplete()` is `false`: the workflow step cache does not store the step,
    `WorkflowPatterns.loopUntilDry` does not count it as a quiet round, `WorkflowPatterns.completenessCritic` stops at
    it, and GraalJS workflow scripts read `isComplete: false` and `completionReason: "ERROR"`;
  - a fork-mode skill fails with `Skill fork failed for '<skill>': <stop message>`;
  - the Task tool prints `Status: FAILURE` and `Completion reason: ERROR`.

  A cancellation that lands on the would-be third stalled iteration still ends the fork `INTERRUPTED`. **No
  `CompletionReason` value was added.**

- **The stop message names `max_tokens` when the streak was made of refused cut responses** (#115). On the turn, the
  fork and a skill's loop, when every one of the three stalled iterations was a response cut at `max_tokens` whose
  calls were refused, the message gains ` — each of those responses was cut off at max_tokens, and its tool calls were
  refused`. Any other streak reads as above, byte for byte. The guards' own WARNs do not name `max_tokens`, so each cut
  response's WARN stays the one that does.

- **`ReActLlmDeriver` refuses a cut tool call too** (#115). A cut `deriver.observation.create` call no longer persists
  an observation from whatever arguments arrived: each call is answered with the refusal, a WARN names `max_tokens`,
  the iteration, the observer and the tool names, and the loop continues inside its six iterations and token budget. A
  cut response with no tool calls ends the loop as before. No in-tree code constructs this deriver.

- **The Task tool says when a fork did not finish on its own terms** (#117). After the result, a foreground Task call
  now prints `Completion reason: <REASON>` whenever the reason is not `COMPLETED`; for a successful result — a fork
  whose final answer was cut at `max_tokens` — it reads `Completion reason: TRUNCATED (the subagent's final answer is
  incomplete)`. Everything from `=== Subagent Task Result ===` through `Result:` is unchanged, because `aimon-cli`'s
  `SubagentResultDisplayHook` parses it and allows only whitespace between `Status:` and `Result:`; the CLI shows the
  new line at the end of the rendered result. A `COMPLETED` result is byte-identical, and
  `SubagentExecutionResult.getStatus()` still reads `SUCCESS` for a cut fork. `AgentOutput`, which reports a background
  task, is unchanged (backlog `L-25`).

- **`ChunkAggregator`'s parse-failure WARN no longer logs the arguments** (#117). It printed Jackson's message and the
  whole accumulated `tool_call` arguments — a file body for a write, a command line. Dropping the arguments alone would
  not have been enough, because Jackson copies the offending token into its message. The WARN now reads `Failed to
  parse accumulated tool_call arguments as JSON: <ExceptionType> at offset N (L chars); the arguments are not logged`,
  without the offset when Jackson does not know it.

- **Pinned by tests** (#117): the entry below's *"No permission check and no PermissionRequest/PreTool/PostTool hook
  runs for a refused call"*. On the turn, the fork and a skill's loop, a test counts the PermissionRequest, PreTool and
  PostTool hooks and the tool across a cut call and then the same call uncut: 0 after the first, 1 after the second.
  The permission check itself is counted on the skill loop only (0, then at least 1): the turn and the fork pass an
  empty allow-list, and the check returns before it reads anything, so a count there would read 0 either way.

- **API.** New public `at.aimon.core.agent.budget.StalledIterationGuard` — the threshold, the predicate, the
  per-execution streak and the stop message the three loops share. `OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS`
  keeps its name and value (3), now taken from the guard. Nothing was renamed.

- **Records.** `docs/design/agent-execution/orca-executor.md` §2.2 described a `TruncationRecoveryStrategy` and two
  implementations that no source file declares; it now describes what shipped, and the sentences in §2, §5, §12 and
  §13 that leaned on it follow. `AnthropicThinkingBudgetsTest.budgetIsClampedBelowMaxTokens`'s comment carries the
  qualification #101 gave the javadoc. Backlog `L-22` and `L-23` are closed; `L-25` (the background half of the Task
  tool's line) and `L-26` (whether a turn that ran a cut slash skill should end `TRUNCATED`) are registered. The design
  is `docs/design/agent-execution/skill-loop-truncation-and-fork-stall.md`.

- **This entry supersedes two sentences of #113's entry below:** *"`TaskTool` still prints `Status: SUCCESS`; what
  tells the parent model is the marker at the end of the summary"* and *"A fork has no stalled-iteration guard, so a
  fork whose every response is cut repeats the refusal until its `maxIterations` stops it — 1000 unless the subagent
  sets one, since no in-tree caller gives a fork a request budget (backlog `L-23`)."*

### CLI: the distribution ships Logback 1.6.3, past every Logback CVE NVD lists

- **`aimon-cli` now ships `logback-classic` and `logback-core` 1.6.3 instead of 1.5.13** (#114). The CLI is the one
  module that ships the catalog's Logback. 1.5.13 was inside CVE-2025-11226 (GHSA-25qh-j22f-pwp8, fixed in 1.5.19),
  CVE-2026-13006 (logback-core up to 1.5.36, fixed in 1.5.37) and CVE-2026-19880 (logback-classic up to 1.6.2, fixed
  only in 1.6.3), and three LOW advisories fixed by 1.5.34. None of those three named advisories is reachable from the
  CLI as it ships: no Janino on its class path, no `SiftingAppender` in the bundled `logback.xml`, no MDC set by AIMON
  code (measured). The version moves anyway, because a later dependency or a user's own configuration can change each of
  those. Logback describes 1.6.x as its stable line and, apart from Janino conditionals, a drop-in replacement for
  1.5.x. CVE-2026-19880 is the one with no fix on 1.5.x, and neither it nor CVE-2026-13006 has a GitHub advisory yet.
  `slf4j-api` stays 2.0.18. The distribution's `lib/` and the fat jar carry the new pair, and the bundled `logback.xml`
  loads with the same one warning as before (its unreferenced `CONSOLE` appender) and no error.

- **If you replace the bundled `logback.xml`**, Logback's changes since 1.5.13 apply to your file: Janino-based `<if>`
  conditionals are gone (1.5.37; they already needed Janino, which the distribution has never carried); 1.6.0 removed
  deprecated API such as `ReconfigureOnChangeFilter`; 1.6.3 deprecates `ConsoleAppender`'s `withJansi` in favour of
  `JansiConsoleAppender` and strips slashes from `SiftingAppender`'s MDC discriminator values. Logback's release notes
  list the rest.

- **What else moves: nothing published.** The CLI's tests move with its distribution (#99's consistent resolution). Ten
  other projects name the catalog's Logback at test scope, where 1.6.3 now wins over the 1.5.34
  `spring-boot-starter-test` brings. The unpublished `aimon-session-testkit` names it on its main classpaths, which only
  ever join a consumer's tests; its main and test classpaths, 1.5.13 against 1.5.34 before, now both resolve 1.6.3, so
  backlog D-2 drops that row. No published module's POM or module metadata changes, because none declares Logback
  outside test scope. **This supersedes the #99 entry's "so its tests now run on Logback 1.5.13" below.**

- **Four records #99 left, settled** (#120). Nothing a user or consumer resolves changes.
  - `shouldResolveConsistentlyWith`, the `@Incubating` Gradle API that holds the CLI's test classpaths to what it ships,
    stays. `aimon.java-conventions.gradle.kts` now says when a module build script may call incubating API. The CLI's
    comment says what an upgrade that changes it can break: a removal fails every build, while a change in behaviour can
    pass without failing anything. It also names the command that shows the second. Backlog D-3 lists a Gradle upgrade
    as a trigger.
  - `aimon-memory-testkit`'s contract suite was run once on exactly JUnit 5.12.2, the floor it publishes (21 tests, 0
    failures); the `junit` note records how.
  - `ModelCapabilityBindingProbeTest` drives the equality refusal through the probe's public pair check, and
    `requireDistinguishable` is private.
  - D-2 called its differences "not annotation jars", but `jakarta.annotation-api` is one. It now names what does set
    them apart: code, and runtime-read annotations whose package moved from `javax` to `jakarta`.

- **Records.** The design is `docs/design/testing/shipped-logback-and-test-classpath-followups.md`, and §11.5 of
  `docs/design/testing/test-classpath-shipped-versions.md` records where #99's design stopped matching the tree.

### CLI: subagents and memory run on a model the provider serves, and the banner shows what runs

- **The bundled `explore` subagents no longer send `haiku`** (#104). `default-anthropic`, `default-openai` and
  `ops-agent` named `model: haiku`, which nothing resolves to a model id and which the Anthropic Messages API answered
  with HTTP 404 on 2026-09-10. They now name no model and run on their main agent's (`claude-sonnet-4-5`, `gpt-5.1`,
  `gpt-5.1`), so `agent.name` is the one switch for a whole bundle. `default`'s `explore` still names `gpt-5.1`.
  **The "cheaper, faster" `explore` goes with it**: a user who wants one writes `.aimon/agents/explore.md` with a
  full model id, which #92's startup check covers.

- **`aimon-core`: `SubagentLlmDefaults.resolveModel` no longer invents `gpt-4`** (#104). When the Task tool's
  override, the subagent's `model` and the parent model's name are all absent, the resolved `LlmModel` now carries no
  name, and the client sends its own default model — as it already does for a main agent whose definition names none.
  Code that reads the resolved model's name (`SubagentBehaviorSupport.resolvedModel()`, for one) can now find it empty.

- **The Task tool's `model` parameter names no models** (#104). Its description suggested `sonnet`, `gpt-4.1` and
  `gpt-4.1-nano` whatever the provider, and an override wins over everything else. It now says the value is a model
  id sent exactly as written, with no alias resolved, and to leave it out unless given one. The input schema is
  otherwise unchanged.

- **`provider: anthropic` with `memory` on and no `llm.model` now starts** (#105). It used to exit with
  `Unexpected error: llmModelName cannot be null`. The memory components — the dialectic engine, deriver, reconciler,
  and the dreamer with its LLM judge — now receive one name: `llm.model` when it is set, otherwise the client's default
  model. That is a model the user did not write, so startup says so in one line, on the terminal and in
  `~/.aimon/logs/aimon.log`. A client with no default model of its own fails with a `ConfigurationException` that
  names `llm.model`.

- **The startup banner shows what runs** (#106). The parenthesis in `LLM Provider: <provider> (<model>)` is now the
  model the main agent's requests carry — the definition's `model.name`, or the client's default when it names none —
  instead of `llm.model`; as shipped it read `gpt-5.1` while requests carried `gpt-5.6-terra`. A new
  `Agent bundle: <agent.name> (agent name: <definition name>)` line says which bundle loaded, because `default`,
  `default-openai` and `default-anthropic` share the definition name `default-agent`, and with it the prompt and the
  runtime id. **No bundle was renamed: the prompt and `AgentRuntimeId` are unchanged.**

- **Documentation** (#106, #107). `README.md`'s Configuration sample shows `model: "gpt-5.1"`, matching
  `default-config.yaml`; the CLI guide's thinking example writes the Anthropic key as `${ANTHROPIC_KEY}`, as the rest
  of the page does (ko + en); the #92 design record's Status link resolves on both locales. **This entry supersedes
  three sentences of #92's entry below**: that the banner prints `llm.model`, that `memory` needs `llm.model` under
  anthropic, and that `default-anthropic`'s `explore` names `haiku`. Backlog `L-17` to `L-21` are closed, and the
  unmeasured default model memory can now fall back to is registered as `L-24`; the design is
  `docs/design/llm/model-names-sent-and-shown.md`.

### Agent loop: a response cut at `max_tokens` says so on both executors, and its tool calls are not run

- **A tool call in a response that stopped at `max_tokens` is no longer executed** (#108). The main executor used to
  run such calls as they came: a call cut mid-argument reached its tool with an empty argument map, and no log line,
  marker or completion reason said `max_tokens`. Now none of the response's tool calls runs — including calls that look
  complete, because the response does not say which call was cut. Each is answered with an error result saying the
  response was cut off at `max_tokens`, that no call which changes anything was run, and that fewer calls at once, or a
  large argument split across calls, fits under the limit. The loop continues.
  - No permission check and no PermissionRequest/PreTool/PostTool hook runs for a refused call, and a cut `Skill` call
    no longer suspends the turn for approval.
  - With streaming-tool overlap on, a `CONCURRENT_SAFE` call already started from the cut response may have run; its
    result is discarded and the refusal takes its place.
  - Three such responses in a row trip the existing stalled-iteration guard and end the turn as `ERROR`.

- **The operator is told.** One WARN per cut response names `max_tokens`, the iteration and the tool names (not their
  arguments), and says whether overlap had already started any of the calls. `ToolUseStarted` and `ToolResultReady`
  still fire for each refused call; the REPL shows it as `Tool '<name>' failed: Cut off at max_tokens: …`, because its
  usual tool-call line comes from a PreTool hook, which does not run. When the cut response's usage reports reasoning
  tokens — both Anthropic paths and OpenAI's Responses API — this WARN and the existing truncated-answer WARN end with
  `; the response's usage reports N output tokens and R reasoning tokens`. When it reports none (OpenAI Chat
  Completions, or any usage without the counter), the text is what it was.

- **A subagent fork's final answer cut at `max_tokens` is now `TRUNCATED`, not `COMPLETED`** (#100). The fork appends
  the same `[System: response truncated at max_tokens]` marker, logs a WARN, fires OnStop hooks with `success=true`,
  and ends its progress stream with `[completed: TRUNCATED at max_tokens after N iterations]`. `isSuccess()` stays
  `true` and `getSummary()` keeps the partial text, as on a turn. What reads the reason sees the change:
  - `AgentStepResult.isComplete()` is `false` for such a step: the workflow step cache does not store it (a resume
    re-runs it), `WorkflowPatterns.loopUntilDry` does not count it as a quiet round,
    `WorkflowPatterns.completenessCritic` stops at it, and GraalJS workflow scripts read `isComplete: false`;
  - background task results record `TRUNCATED`;
  - `TaskTool` still prints `Status: SUCCESS`; what tells the parent model is the marker at the end of the summary.

  A fork's cut tool calls are refused as above. A fork has no stalled-iteration guard, so a fork whose every response
  is cut repeats the refusal until its `maxIterations` stops it — 1000 unless the subagent sets one, since no in-tree
  caller gives a fork a request budget (backlog `L-23`).

- **Unchanged:** the Anthropic and OpenAI clients' own truncation WARNs, which callers of the blocking overloads still
  reach (compaction, skill LLM execution, peer memory, the wiki); `CompletionReason` and `StopReason` values; and
  `OrcaAgentExecutor.TRUNCATION_MARKER`'s name and text, now defined once in the new
  `at.aimon.core.agent.budget.TruncatedResponses`.

- **Records.** §16.8 of `docs/design/llm/thinking-reporting-and-dialect-records.md` stated three things the code does
  not support and described only the main executor; it now says what the code does, and §16.10 records each
  correction (#101). Three statements in `aimon-llm-anthropic` code are corrected with it, and
  `AnthropicThinkingResolverTest.theOtherTwoConditions` gains the paths §16.8 said it covered. Backlog `L-16` is
  closed; `L-22` (two more tool loops that never read the stop reason) and `L-23` are registered. The design is
  `docs/design/agent-execution/max-tokens-truncation-reporting.md`.

### Release: `scripts/release.sh` refuses to start while a provider API key is in its environment

- **`scripts/release.sh` now stops before anything else when `OPENAI_KEY` or `ANTHROPIC_KEY` is in its
  environment** (#98), even set to the empty string, and `--dry-run` included. It exits 1 before it invokes
  `git`, and so before its network and Docker checks. The message names the variables that are set, never a
  value, and says how to proceed: `unset` them and re-run, or keep them out of one run with
  `env -u <name> scripts/release.sh <args>`. A bad argument still gets exit 2 and the usage line first. No flag
  lets a key through.

- **Why.** The four live-API classes carry no tag, so their variable is their only gate. With a key in the
  environment the gate's `checkAll` ran them: calls billed to that key's account, a gate that could go red for
  a reason on the provider's side, and a gate that was no longer the one CI runs, since CI has no key. Neither
  F-8 nor backlog `LA-1`'s manual-only decision is reopened; the release gate just stops inheriting a key.

- **Observable change.** A release cut from a shell with a key exported used to run those classes inside the
  gate; now it refuses to start. Not measured, carried over from the issue: a release run with a key exported.

- **Refused, not unset.** The key stays exported in the shell the script was started from, where every later
  build bills the same way. Unsetting it inside the script would fix one command of that shell and tell the
  operator nothing.

- **Pinned by running the script, not by reading it.** `ReleaseGateMatchesCiGateTest` runs the real
  `scripts/release.sh --dry-run` from an empty directory, with a cleared environment and a `PATH` holding only
  a stub `git` that records its calls: once per key, once with a key set to the empty string, once with both,
  once with neither, and once with a bad argument. A refusal must come before any `git` call and before
  pre-flight, name exactly the keys that are set, and not print the value; the keyless run must reach
  pre-flight and call the stub, so "no `git` call" cannot pass vacuously. The test also holds the keys those
  cases run on equal to the `@EnabledIfEnvironmentVariable` gates under `modules/aimon-llm-*`, so the script
  must refuse at least those gates, and a new provider's key fails the build until the script refuses it. A
  script that refused more would still pass. `AIMON_DOCKER_IT` and `AIMON_KUBERNETES_IT`, which gate two
  sandbox classes the same way, are not refused; whether they should be is registered as backlog `LA-2`.

- **A change to a provider module's test sources now re-runs that census locally** (#119). They were not inputs of
  `aimon-core`'s `test`, so a build that added a key gate under `modules/aimon-llm-*/src/test` and changed nothing
  else could report `ReleaseGateMatchesCiGateTest` `UP-TO-DATE` and stay green; CI, which builds from a fresh
  checkout, did not. They are declared now, as a glob on the census's own prefix rather than a list of modules.
  **The price:** a `checkAll` after such an edit also runs `aimon-core`'s suite, which it used to skip (measured:
  `:aimon-core:test --rerun` ran 8168 tests in 40s on one macOS arm64 machine). The same test's tag scan reads
  every test source in the repository and keeps its gap — declaring those would re-run that suite after a test edit
  in any module — and the test's javadoc says so.

- **Documentation.** The three CLI quickstarts (`README.md`, `docs/README.md`, `docs/README.en.md`) put the
  key on the command instead of exporting it, and say why in one sentence. `CONTRIBUTING.md` and its Korean
  translation now say the only exclusions in a module's `test` task are by tag — `docker` and `packaging` from
  the conventions plugin, `playwright` from `aimon-browser-playwright` — and give a build after `clean` or
  `cleanTest` as one of the builds that execute it. Both were re-checked against the build files, and the second
  was measured without a key on `:aimon-llm-openai`: a repeated `test` reported `UP-TO-DATE`, and `test` executed
  again after `cleanTest` and again after `clean` (313 tests, its 17 live tests skipped). The design is
  `docs/design/llm/provider-key-release-gate.md`.

- **Records** (#119). `modules/aimon-cli/examples/gpt-5.6-terra.yaml` puts the key on the command, as the
  quickstarts do. `CONTRIBUTING.md`'s test command and Quality Checks name all three tags `test` excludes, in both
  languages. `docs/project/publishing-guide.md` and the `/release` skill name the refusal, and the guide the Docker
  check. `docs/overview/architecture.md` (ko + en) and `docs/project/api-stability.md` describe the test as running
  the script as well as comparing tasks. The design is `docs/design/llm/provider-key-census-claim-and-inputs.md`.

### Docs CI: the backlog check stops counting a commented-out item, and fails on item headings it used to skip

- **A heading inside an HTML comment block is no longer read** (#102). The block does not render, so the
  item is not on the page, and the check counted it anyway: a register whose title matched its visible items
  failed with `… but the items read N+1`, and so did its index row.

- **An ID heading written behind indentation, a `>` or a list marker now fails as `unread-heading`**
  (`   ## CE-3 — …`, `> ## CE-3 — …`, `- ## CE-3 — …`). GitHub shows each as a heading, but the check skipped
  them without a word, which inverted the verdict: a register whose title counted such an item failed with a
  count that disagreed with the page, and one whose title did not count it passed. Move the heading to the
  start of the line. A state record written that way inside an item's section (`   ### 닫힘 (…)`) fails the
  same way. Indentation of any width counts, so an item-heading example in a four-space indented code block
  fails too; examples belong in a fence. No register on `main` is written like this, so none changes verdict.

- **Named, not read:** a setext heading and a raw HTML `<h2>`, in the docstring's BLIND SPOT and in
  `docs/backlog/README.md`. The self-test pins both, so reading either later means changing the docstring too.

- **Prose that went stale with #88's check.** `CONTRIBUTING.md` and `.ko.md` describe the fourth doc check,
  where it runs and in which order. `docs/backlog/README.md` stops calling `결정됨` a kind of 열림 — B-10 is
  decided, closed and counted 닫힘 — and records that `접힘` gets no index column and that section subtotals
  are not checked. `anthropic-thinking-config-surface.md` quotes B-21's heading as it reads now. The check's
  design document is trimmed to `docs/design/README.md`'s rules (decisions, rejected alternatives and
  don'ts; no test plan, no line numbers) and states the order the workflow runs: the check, then
  `--self-test`.

### CLI: startup says so when the agent's model belongs to the other provider

- **Switching `llm.provider` left the agent on the other vendor's model, and nothing said so** (#92). The
  model name each agent request carries comes from the agent definition — both clients send its `model.name`
  in preference to `llm.model` — and the shipped `agent.name: default` names OpenAI models (`gpt-5.6-terra`,
  and `gpt-5.1` for its `explore` subagent). A configuration whose `llm:` block was edited to Anthropic sent
  those names to Anthropic, which answered `gpt-5.6-terra` with HTTP 404 `not_found_error` (measured
  2026-09-10).

- **Startup now prints one message, before the banner, when a loaded definition names the other vendor's
  models.** It goes to the terminal and to `~/.aimon/logs/aimon.log`. The main agent and every subagent the
  runtime resolves are checked, and each line names the key to change (`model.name` for the main agent,
  `model` for a subagent) and where it was read: a bundle file as `classpath agents/<name>/…`, a user subagent
  as its absolute path under `.aimon/agents` in the CLI's working directory. The file is chosen by instance
  identity, so a user copy of a bundled subagent is named as the user file even when it keeps the bundled
  model.

- **It never stops startup, and it stays silent wherever a false alarm is possible.** It fires only when
  `llm.baseUrl` is unset, is the provider's own public host, or is OpenAI's host under `provider: anthropic`
  (what editing only `provider:` in the shipped file produces); behind any other `baseUrl` — a gateway, a
  proxy, Azure — it is silent. It fires only on a name in the *other* vendor's family (`claude-*`; `gpt-*` and
  `o1`/`o3`/`o4`, the prefixes the built-in capability rows are named under), and is silent on names neither
  vendor claims, such as `haiku` or a renamed gateway deployment. There is no key to silence it.

- **Each remedy is offered only where it changes something.** `agent.name: default-anthropic` (or `default`)
  is offered when the main agent mismatches and `agent.name` is not already that bundle; files under
  `.aimon/agents`, which load with every agent, are called out separately; a bundled subagent that mismatches
  under a correct main agent gets "change the key" instead of a bundle switch; and OpenAI's host under
  anthropic gets "remove `llm.baseUrl`" instead of "point it at a gateway".

- **Documentation.** Comments next to `llm.provider`, `llm.model` and `agent.name` in `default-config.yaml`,
  and a new passage in the CLI guide (`docs/getting-started/aimon-core-integration-via-cli-reference.md` §4.1,
  ko + en), which `docs/README` now links to. Both say that switching changes five keys, not one; that
  `llm.model` still reaches peer memory, wiki page generation and the name the banner prints in
  `LLM Provider: <provider> (<model>)`; that `memory` needs it under anthropic; and that `default-anthropic`'s
  `explore` subagent names `haiku`, which is sent as written and which Anthropic answered with HTTP 404 on
  2026-09-10 — registered as backlog `L-17`, with the bundles unchanged. Four findings outside this fix are
  registered beside it as `L-18` to `L-21`; the design is
  `docs/design/llm/provider-switch-agent-model-check.md`.

### Docs CI: backlog registers are checked for duplicate item IDs and for counts that disagree with the items

- **New check `scripts/check-backlog-registers.py`, a second step of the `docs-links` job** (#88). On
  2026-09-10 two branches registered `L-13` in the same register and git merged both item bodies without a
  conflict; only the count lines collided. It fails on an item ID repeated within a register, on a register
  title (`등록 항목 N건 (…)`) that disagrees with the items, on a `docs/backlog/README.md` index row that
  disagrees with them, on a register with no row, and on an ID read in two registers (`L-1` predates the
  check and is declared shared). A duplicate or shared ID's finding names the number to take instead: the
  next one no register with that prefix uses.

- **It reads headings, never tables or prose.** An item is a heading that starts with its ID; its state is
  the heading's bold `열림`/`닫힘`/`완료`/`해소`, or `✅`, or a nested `### 닫힘 (…)`; nothing means open.
  `interrupt-open-items.md` is read by its `## N.` numbers, which is how it is cited. A heading that starts
  with an ID but is not an item fails instead of being skipped, and so does any register the check cannot
  read -- there is no exemption.

- **Two registers had headings reordered so they can be read, and no count changed**: the three §5 group
  headings and the B-21 revival heading in `spring-boot-starter-open-items.md` (which also stops filing
  B-21 under 해소), and `### T-1 (원문)` in `translation-tooling-open-items.md`. Before the edits the starter
  read as 28 items against its title's 34; after them, 34 (열림 4 · 닫힘 26 · 해소 4). No title or index row
  on `main` disagreed with its items, so no count was corrected.

- **`--self-test` runs second, in the same step**: it breaks the real tree one way at a time (a duplicated
  ID, a wrong title, a wrong row), switches each reading rule off in turn against a synthetic register, and
  fires every other finding kind once. It aims only at registers and rows with no finding of their own, so
  drift elsewhere on the tree leaves it green. It runs after the check -- the reverse of the translation
  structure step -- so that a red step names the heading or row that is wrong, not the self-test.

### CLI: the bundled `default` agent runs on `gpt-5.6-terra`, and a `terra` bundle ships beside it

- **The bundled `default` agent now names `gpt-5.6-terra` with `reasoningEffort: medium`** instead of
  `gpt-5.1`. `temperature` and `topP` are gone from it: the built-in capability row says terra rejects
  sampling parameters, so they would only have been omitted with a WARN on every request. `maxTokens: 40000`
  is unchanged. An agent definition's `model.name` wins over `llm.model`, so a deployment whose endpoint
  does not serve terra should pick another bundled agent (`agent.name`) rather than change `llm.model`.

- **Its `explore` subagent names `gpt-5.1`** instead of `haiku`.

- **New: the `terra` bundle and `modules/aimon-cli/examples/gpt-5.6-terra.yaml`.** A minimal agent — no
  subagents, no template variables — and a config that runs it with `llm.openai.reasoningSummary: auto`, so
  the model's reasoning summary streams live and dimmed. Run it with
  `./gradlew :aimon-cli:run --args="--config modules/aimon-cli/examples/gpt-5.6-terra.yaml"`.

### Core: an agent definition's frontmatter reports a number it cannot read instead of substituting one

- **BREAKING for agent definitions that have been quietly running on a default** (#74). A definition
  whose `model.temperature`, `model.topP`, `model.maxTokens`, `maxIterations`, `model.name` or
  `version` holds something the parser cannot read has been starting anyway, on the default, since the
  parser was written. It now fails to load, naming the key and the value. That is the point of the
  change: `temperature: hot` bound `1.0` and the agent ran on a sampling parameter its author did not
  choose, and `maxIterations: fifty` bound `Integer.MAX_VALUE` — a ReAct loop with no ceiling. A
  definition whose numbers are numbers is unaffected, and no definition in this repository changed.

- **The same treatment the key beside them already had.** `model.reasoningEffort` has thrown on this
  class of input since #61, so one key in that method was strict and the rest were lenient. The rule is
  now one rule: an **absent** key takes its documented default; a key **present with a value the parser
  cannot use** is an `AgentDefinitionParseException` naming the key and the value.

- **A key written with nothing after it is "present", not "absent".** `map.get(key) == null` cannot tell
  the two apart, so `model:\n  name:\n` silently ran on `gpt5.1` and `version:` silently became
  `1.0.0`. `containsKey` can, and now does.

- **Two silent numeric conversions inside the same method go with them.** `maxTokens: 4096.5` truncated
  to `4096` and `maxIterations: 9999999999` narrowed to `1410065407`, both through `intValue()`. A
  whole-valued `Double` is still accepted (`4096.0` binds `4096`) — the answer `ToolInputBinder` already
  gives on the tool surface — and a real fraction or an out-of-range whole number is now an error.

### CLI: `${VAR}` is expanded everywhere in the configuration file, not in five places

- **A `${VAR}` outside five hand-listed fields used to be handed on as its literal characters** (#53).
  The CLI's own shipped `default-config.yaml` demonstrates `apiKey: "${OPENAI_KEY}"` inside the `memory`
  block, which the loader never visited, so a deployment that followed the example passed the literal
  seven characters to its embedding provider and read the resulting 401 half an hour later inside a
  Quartz job, naming a workspace and no configuration key.

- **The list is gone rather than extended, and the rule replacing it is one sentence:** every scalar
  value and every mapping key in the configuration file is expanded; `${NAME}` is replaced by the
  environment variable `NAME`, and a variable that is not set fails startup naming the variable and the
  key it was written on. It is stated in
  `docs/getting-started/aimon-core-integration-via-cli-reference.md` §3.1 and at the top of
  `default-config.yaml`. A list that grows by one field every time a block gains a credential is what
  produced this issue.

- **Two behaviour changes a deployment can observe.** An unset variable **anywhere** in the file now
  fails startup — outside those five fields it used to pass through as a literal. And `${VAR}` now works
  on keys whose type is not `String`, such as `llm.timeout` and `llm.anthropic.thinkingMode`, because
  expansion moved to the token stream and runs **before** Jackson binds. The CLI and the Spring starter
  now give the same answer about when placeholders resolve.

- **What did not change**, which is the part a reader of this diff will worry about: a scalar carrying
  no placeholder reaches its deserializer exactly as the YAML parser read it, so `thinkingMode: off`
  still means `off` and a `String`-typed key still sees `0755`, `1.10` and `yes` as written. The
  refusal of two `llm.modelCapabilities` keys that expand to the same name is kept and generalised to
  every mapping; two keys written identically are still yaml's own last-wins.
### LLM: what the Anthropic thinking path tells an operator, and the records behind it

- **Three warnings described a request other than the one that was sent** (#68), and the fix is one
  reporting contract obeyed in one place rather than three patches. A step in the thinking resolution
  now *records* a finding and the **finished request** decides which records are emitted: a finding
  about the thinking parameter is dropped when the request ends up carrying none, and the one finding
  that explains the absence is emitted instead. **Observable change: a request that asks for thinking
  and then abandons it now emits one warning explaining why, instead of up to four describing a
  request that was never sent.** Every divergence signature and every message is unchanged, and the
  per-signature dedup register still sees only surviving findings — so a dropped finding cannot spend
  its signature and silence that message for the life of the process.

- **An effort silently dropped by an explicit budget on a *translated* adaptive request is now
  warned about.** `thinkingBudgetTokens` is legal only under `thinkingMode: extended`, so it reaches
  an adaptive request only by dialect translation — and there the call's `reasoningEffort` was
  discarded with nothing said, because the `thinkingBudgetOverridesEffort` warning lived in the
  budgeted branch alone. One recorder now covers both dialects. Same message, same signature, and
  the combination gains the test it lacked.

- **`thinkingDisplay` together with `reasoningEffort: none` stays silent, and that is now a derived
  decision with a test pinning it** rather than a judgement someone remembered. The rule: an inert
  combination is reported when there is a remedy that reverses nothing the operator set, or when the
  operator could otherwise draw a false conclusion from what they see. Neither holds for this pair,
  so nothing is said.

- **`ThinkingDialect.EITHER`** — the fourth constant, for a model measured to accept **both** request
  shapes (#73). `UNKNOWN` was covering two different situations: *the table cannot answer* and
  *either works*. They differ in exactly one behaviour and it is `thinkingMode: auto`, which had
  nothing to send for the first and can send either for the second. A named mode against an `EITHER`
  row is **honoured unchanged and unreported** — translating a working, explicitly requested shape
  would be a substitution with nothing behind it. `UNKNOWN` and `EITHER` are values a *row* may hold
  and a *request* never speaks.

- **BREAKING, and source-breaking for any out-of-tree exhaustive consumer of `ThinkingDialect`.** A
  `switch` over the three constants that compiled before will no longer compile. `docs/project/api-stability.md`
  §5 permits this at `0.x`, taken in one step rather than through a deprecation window, as with
  `AgentExecutionEvent`'s sixteenth subtype in this same block. In-tree the compiler catches nothing —
  every read is an `==` — so the reads were found by reading and each gained a test.

- **Five new `claude-*` capability rows, measured 2026-09-10** and carrying a dialect and nothing
  else. `claude-opus-4-5` / `claude-sonnet-4-5` / `claude-haiku-4-5` speak the **budgeted** dialect
  (the first `BUDGETED` rows this table has ever shipped); `claude-opus-4-6` / `claude-sonnet-4-6`
  accept **either**. Prefixes rather than exact names because the undated aliases resolve and are
  absent from `GET /v1/models` — three rows cover six measured names. `supportsSamplingParameters`
  stays fail-open `true` on all five: these are the names the table's own `claude-opus-4` warning is
  about, and they accept the parameters a family prefix would have suppressed. The census is
  `docs/design/llm/reasoning-model-enablement.md` §3.5.

- **Behaviour changes those rows produce, on upgrade, with no configuration edit:**
  - `thinkingMode: auto` on those five model families now **sends a thinking parameter and bills for
    it**, where it previously sent nothing and warned. That is what `AUTO` asks for and what a row is
    for, but it is new spending. It also opens the thinking gate in `applySamplingParameters`, so a
    configured `temperature` is now **omitted** on those requests with a
    `temperatureOmittedForThinking` warning, and a `top_p` outside `[0.95, 1.0]` goes the same way.
    **And on the three budgeted families the budget is clamped**: with `AnthropicConfig`'s default
    `maxTokens` of 4096 — what an agent definition that sets no `model.maxTokens` gets; the CLI's
    bundled definitions set 40000 and do not reach this — and no `reasoningEffort` set, the request
    resolves to `budget_tokens: 4095`, leaving one token for the visible answer and raising the
    divergence `thinkingBudgetClamped=4096->4095`, whose message names the remedy. That is the same
    behaviour `extended` has always had on this dialect, arriving for the first time on a deployment
    that only ever wrote `auto` — raise `maxTokens`. Deliberate rather than an oversight: the policy,
    the two alternatives weighed against it and what would re-open it are in
    `docs/design/llm/thinking-reporting-and-dialect-records.md` §16 (#83).
  - `thinkingMode: adaptive` on the three 4-5 families was a **certain HTTP 400**; it is now a
    translated budgeted request that succeeds, with one WARN.
  - `thinkingMode: extended` on `claude-opus-4-6` / `claude-sonnet-4-6` is **unchanged on the wire** —
    `EITHER` honours it. Stated because "we gave these models a row" would otherwise read as a change.
    The vendored SDK's per-call stderr deprecation notice for that shape therefore continues too;
    this client neither suppresses nor paraphrases it.

- **Three records the #54/#60/#61/#62 stack left behind** (#75), none of them a behaviour change:
  the two `AnthropicThinkingMode.values()` folds (CLI deserializer and starter auto-configuration)
  now name each other and state the reassurance neither did — both derive from `values()`, so a fifth
  constant cannot reach one surface and miss the other; `builderWithDefaults()`'s javadoc names
  `gpt-5.6-terra` as the one name its documented `registerPrefix("gpt-5", …)` override no longer
  reaches; and `docs/backlog/README.md`'s index is corrected in two rows, settled by counting the
  items rather than by reconciling to either number.
### LLM: the capability table and its two config surfaces now say only things that are true

- **`thinkingDialect` was advertised as declarable and no config surface bound a key for it** (#69).
  `ModelCapabilityDeclaration.build()`'s refusal message named it, `AnthropicLlmClient`'s `auto` WARN
  prescribed declaring it, and both operator guides repeated that prescription — while following the
  advice was a hard `ConfigurationException` at boot on the CLI (its yaml mapper fails on an unknown
  property) and a silent no-op in the starter (Boot ignores one). Both surfaces bind it now:

  ```yaml
  # aimon-cli — camelCase              # Spring Boot starter — kebab-case
  llm:                                 aimon:
    modelCapabilities:                   llm:
      prod-claude:                         model-capabilities:
        thinkingDialect: adaptive            prod-claude:
                                               thinking-dialect: adaptive
  ```

  Values are the enum constants — `unknown` · `either` · `budgeted` · `adaptive`, any casing — and `unknown` is a
  real statement rather than an absence: *act on no built-in row for this name*, which is the answer for
  an operator who knows a row is wrong and not what the right value is.

- **The key goes in the shared namespace**, which is the third application of
  `docs/design/llm/model-capability-config-key.md` §2.7's criterion and the one where it needed
  refining. #54 sent `thinkingMode` to `llm.anthropic.*` because "thinking" is Anthropic's word, and
  issue #69's own body reads the criterion the same way for this key. It loses on three grounds: the
  criterion picks a namespace for a **key family** rather than for a leaf, and this leaf joins
  `model-capabilities.<model>` — splitting it would put one record type in two namespaces, keyed by
  model name in both; §2.6 already fixed these leaf names as letter-for-letter transcriptions of the
  `ModelCapabilities` Java fields, so nobody *chose* the name the test would be applied to; and #54 §12
  had already placed this exact key here in writing, naming #69's trigger as the moment to do it.

- **`reasoning.summary` now consults the capability table** (#72), through a seventh flag on a
  published `0.x` SPI: `ModelCapabilities.supportsReasoningSummary()`, plus
  `llm.modelCapabilities.<m>.supportsReasoningSummary` and
  `aimon.llm.model-capabilities.<m>.supports-reasoning-summary`. It was the one reasoning parameter on
  the Responses request with no gate at all while its sibling `reasoning.effort` had two, so a gateway
  that implements the effort and rejects the summary answered 400 on the parameter nobody could
  describe. **Fail-open `true`**, which is the same two-sided rule as every other default reaching the
  opposite boolean from `supportsReasoningEffort`: a summary is only ever on a request because somebody
  set `reasoningSummary`, so withholding it would be fail-*closed*. Nothing on the wire moves for a
  deployment that declares none of this. When it does withhold, it reports once, the way
  `maySendEffort` and `applySampling` already do. **`supportsReasoningTraceRoundTrip` was deliberately
  not widened** to also mean "and accepts a summary" — it means something measured, and a gateway
  satisfies the two independently.

- **BEHAVIOUR CHANGE: a `null` element in a reasoning-effort rung list now fails startup** (#70). A
  configuration that boots today stops booting. `acceptedReasoningEfforts: [none, ~, high]` used to bind
  as `{NONE, HIGH}` in silence, and `accepted-reasoning-efforts=none,,high` did the same in the starter
  — two identical folds over the same list, each skipping the element. That value decides **which
  requests the client is allowed to send**, and a silently narrowed ladder does not fail: it makes the
  client refuse an effort the operator declared, and the symptom turns up later as an omitted parameter
  that reads as the model's own behaviour. Both surfaces now refuse it naming the leaf key in that
  surface's own spelling **and the index**, so the fix is deleting the one entry the message points at.
  (The starter's indexed spelling — `accepted-reasoning-efforts[1]=` — was already loud, for a different
  reason: Boot leaves that index unbound and `IndexedElementsBinder` refuses it and every index after
  it.) **#69 carries a narrow behaviour change on the starter for the same reason a key binding can:**
  `thinking-dialect` did not bind at all before, so a deployment that wrote it was silently running on
  the built-in row for that model, and it now runs on a replacing entry — which for a `claude-*` name
  means the two-flag form is what keeps the sampling suppression.

- **A declared entry is the whole row for its name, and three documents said otherwise.** No code
  behaviour changes here. Both operator guides and the CLI's shipped `default-config.yaml` told
  operators that what they leave out keeps today's behaviour — true only for a name the built-in table
  does not describe. `thinkingDialect` is the first key whose documented target is a name that
  **always** has a row (`claude-*`), and those rows carry a **suppression**, so
  `{claude-sonnet-5: {thinkingDialect: unknown}}` hands `supportsSamplingParameters` back at fail-open
  `true` and sends `temperature` to a model measured to refuse it — with no warning, because the
  suppression WARN fires only when the flag is `false`. `withDefaultsExtendedBy`'s javadoc already
  stated the rule; the surfaces did not. The three documents now print the **full** form and say why,
  and a test in each of the three modules pins the behaviour so it is chosen rather than discovered.
  The remaining general case — the same trap on the six older keys, and whether the surface should warn
  — is `L-8` in `docs/backlog/llm-config-surface-open-items.md`.

- **The declaration surface is eight keys and all eight bind on both surfaces**, which is the whole of
  #69: the refusal message became true because the surfaces caught up with it, not because the message
  got shorter. Two new tests keep it that way — one per surface module, each reflecting over
  `ModelCapabilityDeclaration.Builder` and asserting that surface carries a property of the same name.
  Neither can live in `aimon-core`, which cannot see either surface, and that blind spot is exactly what
  #69 was. `L-1` (the CLI-throws / starter-ignores asymmetry) is **widened by two keys and closed by
  none of this**.

- **Those two tests now check that a key's value arrives, not only that the key exists** (#82). As #69
  shipped them they stopped one step short of the only code written by hand for each key —
  `LlmClientFactory.declarationOf` and `ModelCapabilityProperties.toDeclaration()` — so a ninth key with
  a getter and a setter on both surfaces and no forwarding call would bind, say nothing, and never reach
  the declaration, with both tests green. Each now writes two distinct values per key, generated from
  the builder's setter types, through that surface's own forwarding, and compares the whole declaration
  that comes out. The two share one contract in a new unpublished module, `aimon-llm-capability-testkit`:
  the check still cannot live in `aimon-core`, but that never required two copies of it. Nothing an
  operator writes or sees changes; `declarationOf` became package-private so the CLI's test can call it.

### LLM: the reasoning stream is measured against both live APIs, and #43 is closed on evidence

- **#62's streaming path had never been run against a live API on either provider** (#71). Every test
  of it drove a hand-written event stream, which is green whatever the server actually sends — and a
  wrong event name there does not fail, it produces **an empty channel indistinguishable from a model
  that chose not to think**. Both halves are now measured — the event names counted off the raw SSE
  body, then the same request driven through the client by a test that skips without a key:

  | provider | request | what arrived |
  |---|---|---|
  | Anthropic | `claude-opus-5`, `thinkingMode: adaptive` + `thinkingDisplay: summarized`, effort `high` | `thinking_delta` × 34 and one `signature_delta` inside `content_block_delta` |
  | OpenAI | `gpt-5-mini`, `reasoningSummary: auto`, effort `high` | `response.reasoning_summary_text.delta` × 611 |

  Both reach the sink as `LlmStreamChunk.Kind.REASONING_DELTA`, and the deliberation is not inside the
  answer text. **The prompt is part of the measurement:** an easy question returns no deliberation at
  all even at `effort: high` with a display asked for, so both tests use a problem that earns it — a
  test that is flaky for that reason would be worse than no test.

- **`REASONING_DELTA` → `AssistantReasoningDelta` was the one hop nothing asserted.** Both ends were
  pinned and the middle was not: the provider mappers by fixtures, the cross-node codec and the REPL
  formatter by tests that *construct* the event by hand. A chunk that reached the executor and was
  dropped in its switch would have been green everywhere. Now pinned, including that the reasoning
  channel keeps its own chunk-index sequence rather than sharing the text one.

- **`gpt-5.x` tool calling: the fix is confirmed against the live API, and the original 400 still
  happens where the issue found it** (#43). The issue's own reproduction — a config carrying nothing
  but a key and `model("gpt-5.6-terra")`, a default `LlmModel`, a non-empty tool list — is **accepted**
  today. Forcing the same request back onto Chat Completions with `responsesApiEnabled(false)` returns
  the issue's sentence verbatim (`Function tools with reasoning_effort are not supported for
  gpt-5.6-terra in /v1/chat/completions`), with no `temperature` and no `reasoning_effort` sent — so
  the endpoint routing is what fixes it, not a change of parameters. Both are now tests.

- **The reasoning item really does round-trip, and the server really does read it.** A captured
  `[reasoning, function_call]` turn is replayed on turn two and accepted; the same turn with 40
  characters of `encrypted_content` overwritten is refused with *"The encrypted content for item rs_…
  could not be verified"*. Without that control, "the second call succeeded" would be equally
  satisfied by a client that dropped the item — which is not hypothetical: a turn carrying **no**
  reasoning item at all is also accepted.

- **A tool call does not imply a reasoning item, and that is a model decision rather than a defect.**
  Measured: `gpt-5.6-terra` asked for the weather with a tool available returns
  `output: [function_call]` and `reasoning_tokens: 0`. A turn that has to carry a reasoning item must
  earn the reasoning *and* require the tool.

- **One correction to #43's wording, not to the code.** The issue says these models reject sampling
  parameters "by the presence of the parameter, regardless of value". On `/v1/responses`,
  `gpt-5.6-terra` refuses `temperature: 0.0` and accepts `temperature: 1.0` — non-default values are
  what it refuses, which is what `InMemoryModelCapabilityRegistry`'s `gpt-5` row already said and why
  suppressing the parameter loses nothing on the wire.

- **No production code changed.** This entry is tests and records. The evidence, request by request,
  is in `docs/design/llm/reasoning-delta-stream.md` §12.4 and
  `docs/design/llm/openai-responses-path.md` §10; `RD-1` and `RD-2` in
  `docs/backlog/reasoning-delta-stream-open-items.md` record which of their caveats this discharges.

### LLM: a reasoning model's thinking is something a person can watch, and nothing else changes

- **A reasoning model can deliberate for tens of seconds before it emits a visible token, and AIMON
  showed nothing while it did** (#62) — a silence indistinguishable from a hang, on exactly the turns
  where the user most wants to know something is happening. Two independent gaps produced it and
  closing either alone would have left the feature inert, so both close here: the transport had no way
  to say "this text is deliberation, not answer", and nothing asked the provider for the text in the
  first place.

- **`LlmStreamChunk.Kind.REASONING_DELTA`**, with its own factory, its own `reasoningDelta` field and
  its own accessor. Not a reuse of `textDelta`: every existing caller that reads `getTextDelta()`
  without checking the kind would otherwise start reading deliberation, and the constructor now
  refuses a chunk carrying both, from either side.

- **`AssistantReasoningDelta` beside `AssistantTextDelta`**, carrying `delta` and `chunkIndex` on
  **its own** monotone sequence — sharing the text counter would punch holes in that event's
  documented ordering contract. It crosses a node boundary like its sibling, so a web UI on another
  node — precisely who wants to watch thinking — sees it too.

- **BREAKING, and source-breaking for any out-of-tree exhaustive consumer:
  `AgentExecutionEvent` is a `sealed` hierarchy and this is its sixteenth subtype.** A `switch` or an
  `instanceof` chain over the fifteen that compiled before will no longer compile, or will fall
  through to whatever it does with an unrecognized subtype. `docs/project/api-stability.md` §5 permits
  this at `0.x`; taken in one go rather than through an adapter or a deprecation window, which is this
  repository's stated habit there. In-tree the compiler caught the `permits` clause and one `switch`
  **expression**; a third catcher, `AgentExecutionEventTest`'s subtype count, went red on purpose.

- **The deliberation never becomes the answer, and that is a privacy invariant rather than a rendering
  one.** `ChunkAggregator` accumulates reasoning into a **second buffer** that `toLlmResponse()` does
  not read, because `peekText()` — the first one — is what the executor commits to the transcript as
  the assistant's message when an execution is cancelled mid-stream. Folding the two would have
  persisted the model's private reasoning as its public answer, and a transcript is not recoverable.
  Both mid-stream cancel arms are pinned by a test asserting the deliberation is nowhere in the
  session history, not merely absent from one message.

- **Under buffer pressure the relay now sacrifices thinking before answer text.**
  `SessionEventRelay`'s overflow policy grows from two ranks to three — reasoning, then text, then
  everything structural — and the rank of the *incoming* frame counts as well as that of the buffered
  one, so an incoming reasoning delta is dropped rather than displacing buffered answer text. Every
  row that existed before behaves exactly as it did. **A behaviour change worth knowing** for anyone
  reading `getDroppedEventCount()` or a remote event stream.

- **Both providers ask for the text, opt-in and off by default on both**, because forwarding without
  asking ships a channel that is always empty:

  ```yaml
  # aimon-cli — camelCase          # Spring Boot starter — kebab-case
  llm:                             aimon:
    anthropic:                       llm:
      thinkingDisplay: summarized      anthropic:
    openai:                              thinking-display: summarized
      reasoningSummary: auto           openai:
                                         reasoning-summary: auto
  ```

  (Two blocks side by side, not one: a deployment picks one provider, so it writes one of the two
  vendor sub-blocks.)

  Both go to a **vendor** namespace, by `model-capability-config-key.md` §2.7's first test, and the
  two asks are genuinely different things rather than one thing spelled twice: on OpenAI the reasoning
  itself is `encrypted_content` — ciphertext by design — so a *summary* is the only readable surrogate
  that exists, while Anthropic has no summary and gates the model's own thinking text with `display`.
  A neutral `llm.streamReasoning` umbrella would be **shared** by the same criterion and is
  deliberately not shipped; the reasoning and the trigger that would change it are `RD-4` in
  `docs/backlog/reasoning-delta-stream-open-items.md`.

- **Both asks are now measured against the live APIs, and one of them was wrong.**
  `thinkingDisplay` first carried two constants in this round, `summarized` and `updates`, and no
  release ever carried the second one. The server accepts exactly `{summarized, omitted}` and returns
  400 for anything else — the same 400 a deliberately bogus value gets, so the field is validated and
  the accepted spelling is confirmed rather than merely unrejected. **`updates` is gone, and no
  constant replaced it:** `omitted` is the server's own default, so writing it behaves exactly like
  leaving the key unset, and since this key's other half is what opens the streaming gate,
  `thinkingDisplay: omitted` would have meant "open the channel and put nothing in it". The wrong name
  came from `docs/design/llm/anthropic-thinking-traces.md` §8 F-7, which is corrected at the source.
  On the OpenAI side the measurement went the other way and **nothing changed**: `reasoning.summary`
  needs no `include` entry of its own — a request that asks for one is refused with the whole valid
  set enumerated, and none of the eight is a summary — so the reading previously inferred from an
  absent SDK constant is now what the server says. Reasoning:
  `docs/design/llm/reasoning-delta-stream.md` §12.2 and §12.3.

- **`aimon.llm.openai.*` / CLI `llm.openai` is a new namespace**, the slot `L-2` and
  `spring-boot-starter.md` §9.3 have held open since before #46. Its arrival makes the block refusals
  symmetric: the OpenAI branch refuses a populated `llm.anthropic` as it always did, and the Anthropic
  branch now refuses a populated `llm.openai`. An empty block of either kind still refuses nothing.

- **A deployment that writes neither key is unchanged, byte for byte** — verified on the serialised
  request params on both providers rather than asserted, plus at the event-stream altitude, plus by a
  whole-tree `checkAll` (10632 tests) in which the only failure was the subtype count above. **Three
  deliberate exceptions, all of them one WARN saying a key reached nothing:** `thinkingDisplay` under
  the shipped default `thinkingMode: off`; `thinkingDisplay` under `extended`, where the forwarding
  works but the word itself is not sent (the budgeted shape is not given a `display` sibling —
  unmeasured, and the deltas already arrive there); and `reasoningSummary` on a model routed to Chat
  Completions, which has no such parameter. Each is said once per process, because each is a property
  of the configuration rather than of the traffic. `thinkingDisplay` together with
  `reasoningEffort: none` is a fourth inert pair and is deliberately **not** warned about, for the
  reason `reasoningEffort: none` under `thinkingMode: off` is not: the operator asked for no
  reasoning, so there is none to display, and telling them to turn reasoning on would be advice in
  the wrong direction.

- **Anthropic's forwarding is gated on configuration, not on the arrival of the deltas**, and the
  distinction is not academic: on the budgeted dialect `thinking_delta` events arrive **today** and are
  swallowed, so "forward whatever arrives" would have turned a user-visible reasoning stream on for
  every existing `thinkingMode: extended` deployment without it asking. The trace round trip is
  untouched in either position — a thinking block still feeds its signed payload whether or not a
  person is watching.

- **On OpenAI both delta families are forwarded**, `response.reasoning_summary_text.delta` and
  `response.reasoning_text.delta`, under the one gate. Forwarding only the first would leave a model
  that emits raw reasoning text showing nothing to a deployment that asked to see reasoning. Neither
  becomes a `ReasoningTrace`: that payload is the `encrypted_content` the next request replays, and a
  summary is not a substitute for it.

- **The REPL prints it dim and marked.** A `[thinking]` marker opens each run — colour alone
  distinguishes nothing on a monochrome terminal, which is a supported mode — and the text streams
  inline like answer text does. Collapsing, toggling and a `/thinking` command stay out of scope.

  Design: [`reasoning-delta-stream.md`](docs/design/llm/reasoning-delta-stream.md).
  Closes `anthropic-thinking-traces.md` §8 **F-4** and **F-7**, and `openai-responses-path.md` §7
  **F-5**. Open items: `docs/backlog/reasoning-delta-stream-open-items.md`.

### LLM: what a model accepts is now a fact the framework can look up, and gpt-5.x tool calling works

- **New provider-neutral SPI `at.aimon.core.llm.capability`** — `ModelCapabilities`,
  `ModelCapabilityRegistry` and `InMemoryModelCapabilityRegistry`. Six fields in all, three of them
  introduced here (`supportsSamplingParameters`, `supportsReasoningEffort`,
  `supportsToolsWithReasoning`), two by the Responses entry below
  (`supportsReasoningTraceRoundTrip`, `lowestReasoningEffort`) and one by the thinking-dialect entry
  that follows (`thinkingDialect`). Purely additive. It sits **beside**
  `ModelContextWindowRegistry` and `ModelPriceTable` rather than folding into either: three per-model
  facts, three reasons to change, and each consumer depends on exactly its own.

- **What it fixes.** Every `gpt-5.x` tool-calling turn — which is every agent turn — failed with HTTP
  400. Two independent causes, and fixing one surfaced the other. The client always called
  `.temperature(...)` because `OpenAIConfig.getTemperature()` defaulted to `0.0` and had no way to say
  "unset", and `gpt-5.x` rejects that value. Both decisions now come from the descriptor. There is no
  `model.startsWith("gpt-5")` anywhere in the request builder.

  **Corrected against the live API on 2026-09-09** (the first release to test rather than infer): two
  of the premises above, taken from the issue report, are wrong. Rejection is by **value**, not by
  presence — `temperature: 1.0` returns 200 on `gpt-5-nano` and `o4-mini`, only non-default values 400.
  And tools on Chat Completions work fine for these models with the effort simply omitted; the
  `reasoning_effort: "none"` workaround is not merely unreachable but **invalid**, since `none` is not
  an accepted value. Suppression stays — omitting yields the default anyway and spares every other
  value a 400 — but the remedy that used to send `none` now omits. See the probe table in
  `docs/design/llm/openai-model-capabilities.md` §11.

- **Fail open, and that has a consequence worth knowing.** A model no registry describes resolves to
  `ModelCapabilities.unknown()`, which is not "everything permitted" but **nothing the caller asked
  for is withheld, and nothing the caller did not ask for is invented** — a sampling value somebody
  set is sent, no reasoning effort is conjured up. The flip side: a deployment behind a gateway or Azure endpoint
  that **renames** its gpt-5 model (`model: prod-assistant`) is unknown to the built-in table and keeps
  hitting the 400. One line closes it programmatically — and, since #46, from configuration too (the
  entry after this one):

  ```java
  OpenAIConfig.builder().apiKey(key).model("prod-assistant")
          .modelCapabilityRegistry(InMemoryModelCapabilityRegistry.builderWithDefaults()
                  .register("prod-assistant", ModelCapabilities.builder()
                          .supportsSamplingParameters(false).supportsReasoningEffort(true)
                          .supportsToolsWithReasoning(false).build())
                  .build())
          .build();
  ```

  Both halves of that table — exact names and prefixes — match **ignoring case**, because the name an
  operator has is the one their portal shows: registering `prod-assistant` for a deployment configured
  as `Prod-Assistant` has to meet, or the one line that closes this gap silently does nothing.

- **The same escape hatch from configuration** (#46) — a **CLI** deployment in that state used to have no
  yaml key for this, which is why the entry above was programmatic-only; that was a config-surface
  decision left to its own issue rather than ridden in on a bug fix. It is decided: the CLI has the key,
  and so does the Spring Boot starter. The two surfaces keep their own spellings and do not mix:

  ```yaml
  # CLI (aimon.yaml) -- camelCase, like every other key in this file
  llm:
    provider: openai
    baseUrl: https://gateway.internal/v1
    model: prod-assistant
    modelCapabilities:
      prod-assistant:
        supportsSamplingParameters: false
  ```

  ```yaml
  # Spring Boot starter -- kebab-case, like every other aimon.* property
  aimon:
    llm:
      provider: openai
      model: prod-assistant
      model-capabilities:
        prod-assistant:
          supports-sampling-parameters: false
  ```

  Five flags are available under a model and **every one of them is optional**: what you leave out keeps
  `ModelCapabilities.unknown()`'s value, so the one-line form above is a complete answer to the 400 and
  does not quietly route the deployment anywhere new. The field names are the descriptor's own, spelled
  out — there is no second vocabulary for the same five facts, and the invariant that every built-in row
  can be transcribed into this surface is held by a test. Declarations **extend** the built-in table
  rather than replacing it, registered as exact entries, so the existing "exact beats every prefix" rule
  is what makes an operator's name win — for that one name: declaring `gpt-5` overrides that exact
  string and leaves `gpt-5-mini` on the built-in `gpt-5` prefix. There is deliberately no way to declare
  a prefix from configuration, because prefix precedence is registration order and a yaml file's line
  order is not a place to keep that. Values are case-insensitive on both surfaces, in the key as well as
  in `lowestReasoningEffort`.

  What is refused rather than ignored: an entry that declares nothing, a blank or space-padded name, two
  names differing only in case, two CLI keys that `${VAR}`-expand to the same name, an unusable
  `lowestReasoningEffort`, and a declaration under a provider that does not read it. An application that
  brings its own `LlmClient` bean reaches neither starter branch, so its declaration is neither refused
  nor read — it consumes it itself through the public
  `AimonProperties.modelCapabilityRegistry(properties.getLlm())`. One asymmetry is worth knowing before
  you rely on it — a **misspelled flag
  name** fails loudly on the CLI, whose mapper rejects unknown properties, and is **silent in the
  starter**, where Spring Boot ignores unknown properties by default; turning that off is a change to
  the whole `aimon.*` tree and not something this fix rides in on. A dotted model name needs bracket
  notation in the starter (`aimon.llm.model-capabilities[gpt-5.7-x]...`); without brackets the entry does
  not arrive at all.

  The other two knobs on this path — `responsesApiEnabled` and the sampling parameters themselves — are
  still programmatic only.

- **The built-in table is five rows** — `gpt-5-chat` (unchanged behaviour), `gpt-5`, then `o1` / `o3` /
  `o4`. The o-series rows were **withheld in the first cut and added on 2026-09-09 once measured**: the
  belief that they reject `temperature` was unverified, and a wrong row is a *silent* sampling change
  while no row leaves those users exactly where they are. The probes settled it — `o3-mini` and
  `o4-mini` reject `0.0`, accept `1.0`, accept tools with no effort, and reject effort `none`. They were
  **not** routed to `/v1/responses` in that cut, because reasoning-item replay had not been measured for
  them, and asserting a round trip nobody has seen is how the `gpt-5` row came out wrong the first time
  — see the round-8 entry below, which measured it and flipped the flag for eight names. The same probes
  fixed their `lowestReasoningEffort` at `LOW` — `o4-mini` rejects `minimal`, so the neutral `MINIMAL`
  has no wire value there either.

- **`ModelCapabilities.lowestReasoningEffort()` — which rungs, as opposed to whether the knob exists.**
  `supportsReasoningEffort()` says the model takes a reasoning-effort parameter; this says where its
  ladder starts, because the two OpenAI families disagree (`gpt-5.x` starts at `minimal`, the o-series
  at `low`). A requested rung below the floor is **omitted and reported**, never raised to meet it
  — a clamp upward is a request the operator did not make, and it would arrive silently. The default
  and `unknown()` value is `MINIMAL`, so the only rung ever withheld from a model no registry describes
  is `NONE`, which no OpenAI ladder measured to date except `gpt-5.6-terra`'s has at all. The rule lives
  in one place and both endpoints ask it; the *tools* clamp stays Chat-only, because that one really is
  a property of the request surface.

- **Breaking: `OpenAIConfig.getTemperature()` returns `Optional<Double>`, not `double`.** A published
  module (`at.aimon.core.llms.openai`), and — like the entries below — not a rename, so there is no row
  in [`rename-maps.md`](docs/migration/rename-maps.md). Per
  [`api-stability.md`](docs/project/api-stability.md) §1 a breaking change cannot ship as a `0.2.x`
  patch: **this forces the next release to be a minor bump.** The break is a compile error, which is
  the point — nothing changes quietly.

  | Was | Is |
  |---|---|
  | `double t = config.getTemperature();` | `config.getTemperature().orElse(0.0)` |

  The nullable shape is not cosmetic: it is what lets the client tell "somebody asked for `0.0`" from
  "nobody asked" — which decides both whether the parameter is sent at all (see the entry below) and
  whether suppressing it deserves a `WARN`. Keeping `0.0` as the builder seed makes those two states
  the same one. `OpenAIConfig` also gains unset-by-default
  `topP` / `presencePenalty` / `frequencyPenalty` / `reasoningEffort` and a `modelCapabilityRegistry`.

- **Additive: `LlmModel.getReasoningEffort()`** and the neutral `at.aimon.core.llm.ReasoningEffort`
  (`NONE`/`MINIMAL`/`LOW`/`MEDIUM`/`HIGH` — the common subset that survives translation to a second
  provider, not one vendor's ladder; the constants are declared in ascending order, and that ordering
  is now load-bearing because `lowestReasoningEffort` compares against it). Not yet readable from agent
  frontmatter or CLI yaml, on purpose: `AnthropicLlmClient` still ignores the field silently, so a key
  for it would do nothing on one of the two shipped providers. Note that the earlier justification for
  withholding the key — *"a non-`NONE` effort is clamped on every tool-calling turn anyway"* — stopped
  being true when the 2026-09-09 probes set `supportsToolsWithReasoning` to `true` on every shipped
  row; nothing is clamped now.

- **Suppression is reported, not silent.** A value that somebody set and that the model will not take
  is logged once per distinct parameter/value/model at `WARN`, bounded at 32 entries, the same shape
  `AnthropicLlmClient` already uses for the penalties it drops. Every suppressible value is now one
  somebody set, so an unconfigured request is silent by construction rather than by a special case.
  Note the wording says only that a value
  "is set on this request": a subagent turn carries a temperature from `SubagentLlmDefaults` rather
  than from an operator, and nothing at that point can tell the two apart.

- **Breaking: an unset sampling parameter is no longer sent at all.** Issue
  [#43](https://github.com/kangwoo/aimon/issues/43)'s rule — *sampling parameters are sent only when
  the caller explicitly set them* — now holds without exception. **Before:** a default-configured
  `gpt-4o` put `temperature: 0.0` on every request, because the client substituted a fallback nobody
  had asked for. **After:** nothing is sent and OpenAI's server default (`1.0`) applies, so agent
  output is **less deterministic**. **Affected:** anyone who never set a temperature explicitly — in
  practice, main-agent turns of agents whose frontmatter has no `model.temperature`. Subagent turns
  are unaffected (`SubagentLlmDefaults` always sets one), and so is any agent that names one.
  **Remedy, one line:** set the old value explicitly — `OpenAIConfig.builder()...temperature(0.0)`
  for an application that assembles its own config, or `model: { temperature: 0.0 }` in the agent's
  frontmatter for a CLI or Spring-starter deployment, since neither of those configuration surfaces
  has a temperature key of its own. `OpenAIConfig.DEFAULT_TEMPERATURE` is gone rather than kept as a
  constant nobody applies.

- **Breaking: `OpenAIConfig` has no default model.** Issue
  [#45](https://github.com/kangwoo/aimon/issues/45). **Before:** `DEFAULT_MODEL = "gpt-4"`, so a
  config that named no model silently talked to a long-superseded one. **After:** `build()` rejects a
  config with no model, and both assembly paths fail at startup naming the key they own — the CLI
  with a `ConfigurationException` naming the yaml `model:`, the starter naming `aimon.llm.model`.
  **Affected:** anyone relying on the default. **Remedy:** name the model. `AnthropicConfig` keeps
  its default (`claude-sonnet-4-20250514` is current, and #45 names OpenAI only).

- **Breaking: `getProviderName()` is the vendor alone; the effective model has its own accessor.**
  Issue [#45](https://github.com/kangwoo/aimon/issues/45). The method takes no arguments, so it could
  never see the per-request model an `LlmModel` names — and per-agent model selection makes that
  override the normal case, so logs, traces and metering reported a model that was never called.
  **Before:** `"OpenAI (gpt-4o)"` / `"Anthropic (claude-…)"`. **After:** `"OpenAI"` /
  `"Anthropic"`, plus a new additive `default Optional<String> LlmClient.getDefaultModelName()`
  (existing implementations and test doubles need no change; the five decorators forward it).
  **Affected:** anything keyed on the old string. Three observable consequences — an out-of-tree
  `LlmUsageRecorder` sees its `provider` label lose the model **and** its `model` label start being
  populated where it was `null`; trace spans are named `llm:<model>` rather than
  `llm:<provider> (<model>)`; the REPL's "LLM Provider:" line is unchanged, because it now composes
  the two. **Remedy:** read the model from the request's `LlmModel`, falling back to
  `getDefaultModelName()`.

- **Fixed: `OrcaAgentExecutor.toString()` names the model again.** Debug output only, and the one
  call site where the vendor-only provider name is strictly less useful than the composite it
  replaced: it read `OrcaAgentExecutor{provider='OpenAI'}`, and now reads
  `OrcaAgentExecutor{provider='OpenAI', model='gpt-4o'}`, composed from `getDefaultModelName()`. The
  `model` segment is omitted rather than filled in when a client reports no default.

- **A capability registry that returns `null` no longer takes the request down with it.** An
  implementation overriding the `default resolve()` to return `null` broke its own never-null
  contract and NPE'd inside the request builder — which on the streaming path runs *before* the
  try-with-resources, so it escaped both the exception mapper and the cancellation classification.
  It now degrades to `unknown()` and is reported once, beside the existing throwing-registry case.

- **One log-only side effect of the upgrade.** `AgentDefinitionVersion.canonicalForm` enumerates every
  `LlmModel` field, so it gained a `model.reasoningEffort` line — which changes **every** agent's
  digest once. A cron task scheduled before the upgrade logs "definition changed" the first time it
  fires afterwards. `AgentDefinitionVersion` is a change detector and not a gate; nothing refuses to
  run on a mismatch.

- **Behaviour change: eight o-series model names now use `/v1/responses`, so their reasoning survives a
  tool call.** Measured on 2026-09-09, which is the whole point — the `false` those rows carried was
  never a measurement, it was the placeholder for *nobody has looked*. `o4-mini`, `o3-mini`, `o3` and
  `o1` each accept a replayed reasoning item (HTTP 200, turn completed), and a control that corrupts the
  encrypted payload earns a 400, so the server **consumes** the item rather than tolerating it. Affected
  names, alias and served snapshot alike: `o1`, `o1-2024-12-17`, `o3`, `o3-2025-04-16`, `o3-mini`,
  `o3-mini-2025-01-31`, `o4-mini`, `o4-mini-2025-04-16`.

  **The flag flipped per measured name, not per prefix.** `o1-pro` and `o4-mini-deep-research` sit under
  the same prefix rows and were never called, so they keep `false`, keep going to Chat Completions, and
  are byte-identical to before; so is any future `o1*` / `o3*` / `o4*` name. The table under-delivers a
  capability until somebody measures the name, rather than asserting a wire change nobody has seen.
  **One trap comes with that shape:** `builderWithDefaults().registerPrefix("o1", …)` no longer reaches
  `o1` or `o1-2024-12-17`, because a built-in exact row beats every prefix; override those names with
  `register("o1", …)` instead. Reverting per deployment is `responsesApiEnabled(false)`; per name it is
  a `register(...)` row with the flag off.

  Landing with it: the first **live streaming** probe of `/v1/responses` (all five names stream cleanly
  and `OpenAIResponsesStreamingMapper` needed no change); two **corrected reasons** that were false
  rather than merely stale — an effort-ladder enumeration is endpoint-scoped as well as model-scoped, so
  the `xhigh` quote above was a Chat reply read as a model's ladder, and `NONE` is *not* a rung no OpenAI
  ladder has, because `gpt-5.6-terra` accepts it on both endpoints. Neither correction moves a default or
  an assertion. And one **known defect, recorded and deliberately unfixed**: terra rejects `minimal`
  while the `gpt-5` row it resolves to declares that as its floor, so a programmatically configured
  `MINIMAL` on that model is a 400 — unreachable from configuration today, unfixable in the current table
  without breaking the `gpt-5` prefix override the class documents, and tracked as L-1 in
  [`openai-model-capabilities-open-items.md`](docs/backlog/openai-model-capabilities-open-items.md).
  Full measurement, controls and decision: [`openai-model-capabilities.md`](docs/design/llm/openai-model-capabilities.md) §13.

### LLM: the Anthropic thinking dialect is a per-model fact now, not a name the operator has to know

- **What it fixes.** Anthropic has two mutually exclusive thinking request shapes, availability is per
  model, and sending the wrong one is an HTTP 400 rather than a degraded answer:

  ```jsonc
  { "thinking": { "type": "enabled", "budget_tokens": 10000 } }                    // budgeted
  { "thinking": { "type": "adaptive" }, "output_config": { "effort": "high" } }    // adaptive
  ```

  Which one reached the wire was decided entirely by `AnthropicConfig.thinkingMode`, **named by the
  operator**. Nothing in the request builder branched on the model, so an operator had to know a
  per-model fact the framework could look up, getting it wrong cost a turn, and there was no value of
  the setting meaning *"whatever this model speaks"* — so the knob could not be set once for a
  deployment running more than one Claude model.

- **The sixth capability field, and why a third value was the whole answer.**
  `ModelCapabilities.thinkingDialect()` returns the new
  `at.aimon.core.llm.capability.ThinkingDialect { UNKNOWN, BUDGETED, ADAPTIVE }`, seeded at `UNKNOWN`
  in the builder beside the five existing fail-open literals. Two previous rounds recorded this axis as
  unmodellable, and the reason they gave was correct for the shape they assumed: both real dialects are
  a 400 on the model that speaks the other, so neither can be the default for a model nobody has
  described. **`UNKNOWN` is not a third dialect** — it is the absence of the fact, *this table cannot
  answer, so do not act on it*, which is exactly the behaviour every model had before the field
  existed. A model no row describes keeps its request **byte for byte**, and a test asserts that
  against the literal body the parent commit produced.

- **Where the rows come from.** The six `claude-*` prefix rows already in the built-in table
  (`claude-fable-5`, `claude-opus-5`, `claude-opus-4-7`, `claude-opus-4-8`, `claude-sonnet-5`, and the
  documentation-derived `claude-mythos`) now state `ADAPTIVE`, read off the vendor's own per-model
  thinking table. That is **documentation, not measurement** — no call was made for this change — and
  it is the same standing this table's Mythos row already had. No row states `BUDGETED`: the Claude
  models that speak that dialect accept the sampling parameters, so nothing in the table needs a row
  for them, and they stay `UNKNOWN` and unchanged.

- **`AnthropicThinkingMode.AUTO`** — use the model's dialect when the table knows it, send nothing when
  it does not. **The default stays `OFF`.** Flipping it would turn thinking on, and bill for it, in
  every Anthropic deployment that upgrades without reading this file; that is its own change with its
  own entry. `AUTO` against a model the registry cannot name is the one case here that sends nothing
  *and warns*: an operator asked the table a question it could not answer, and the warning says
  explicitly that it is the request parameter that is absent rather than the thinking — several current
  models think by default whatever the request says.

- **A mode that contradicts a known dialect is translated, not sent and not refused.** The request goes
  out in the dialect the model speaks, carrying the same intent, and the substitution is reported at
  `WARN` once per signature. This does **not** contradict the repository's standing "omitted and
  reported, never raised to meet it" rule for `lowestReasoningEffort`: there, omitting leaves the
  server's own default in force and the call succeeds; here, honouring the operator literally is a
  guaranteed failed turn and omitting throws away the thinking they asked for. The translation itself
  is free, because the neutral `ReasoningEffort` is the intent both dialects are spellings of. One
  corner is lossy and gets one warning naming both numbers: an explicit `thinkingBudgetTokens` against
  an adaptive-only model has no counterpart there, so it becomes the nearest rung.

- **Nothing changes for a deployment that does not set `AUTO`** unless it is already pointing a
  contradicting `thinkingMode` at a described `claude-*` model — in which case a turn that used to fail
  with a non-retryable `LlmInvalidRequestException` now succeeds with a warning. Every other request is
  byte-identical, `thinkingMode`'s default included.

- **Declarable, and programmatically only for now.** `ModelCapabilityDeclaration` carries the sixth
  flag, so a registry row can be overridden by name — including back to `UNKNOWN`, which is a real
  statement here meaning *do not act on any built-in row for this name*. Neither the CLI's
  `llm.modelCapabilities` nor the starter's `aimon.llm.model-capabilities` gains a key for it; a
  configuration surface for the dialect is not part of this change. (Those two surfaces expose six keys
  after the entry above, which added the second way of writing a model's reasoning ladder — the dialect
  is still not among them.)

  Design and the full mode × dialect table, one test per row:
  [`reasoning-model-enablement.md`](docs/design/llm/reasoning-model-enablement.md) §3.

### LLM: how hard a model should think is reachable from configuration, and one model's ladder has a hole in it

- **One key on all three surfaces** (#61). `ReasoningEffort` was settable only from Java, so a
  deployment assembled from configuration could not ask a reasoning model to think harder or less.
  Unlike the three keys in the entry below it goes to the **shared** namespace, and by the same
  criterion (`model-capability-config-key.md` §2.7): the name is the neutral SPI type's own
  (`at.aimon.core.llm.ReasoningEffort`) and "how much deliberation should this call spend" means the
  same thing of either vendor, so both of that criterion's tests answer "no". Both provider branches
  read it, which is what makes a shared key honest rather than merely defensible.

  ```yaml
  # aimon-cli — camelCase       # starter — kebab-case          # agent definition frontmatter
  llm:                          aimon:                          model:
    reasoningEffort: medium       llm:                            name: gpt-5.1
                                    reasoning-effort: medium      reasoningEffort: high
  ```

  All three accept any casing. A bad value fails loudly on all three — the CLI with a
  `ConfigurationException`, the starter with a bind failure naming the property, the frontmatter with
  an `AgentDefinitionParseException` naming the key and every accepted spelling. A misspelled *key
  name* is loud on the CLI and in the frontmatter's own block and **silent in the starter**, which is
  `L-1` in `docs/backlog/llm-config-surface-open-items.md`, widened by one key and closed by none of
  this.

- **`AnthropicConfig` gains `reasoningEffort`, with the `LlmModel`-first precedence OpenAI already
  had.** Without it the shared key would have reached one provider and been dropped by the other —
  a key that means the same thing on both had to resolve the same way on both. The precedence is
  pinned by a test on each provider, written alike so the pair reads as one claim.

- **A deployment that writes none of these is unchanged, byte for byte** — every setter is called
  only when its key was written. **One deliberate exception:** a deployment that already sets an
  effort while Anthropic's `thinkingMode` is at its shipped default `OFF` now gets a WARN, once per
  process, saying the effort reaches nothing and naming the mode that would act on it. That state is
  "configured and never read", which this repository refuses to leave silent. `reasoningEffort: none`
  under `OFF` is **not** warned about — both mean "send no thinking parameter", so that pair is
  consistent rather than inert.

- **Breaking, in a published `0.x` SPI: `ModelCapabilities.lowestReasoningEffort()` is gone**,
  replaced by `Set<ReasoningEffort> acceptedReasoningEfforts()`. Taken in one go rather than through
  a deprecated adapter, which is `docs/project/api-stability.md` §5's stated habit for `0.x`. An
  out-of-tree caller reading the floor **fails to compile**, and that is the outcome to want: the
  expression a floor invites — `effort.compareTo(floor) >= 0` — is exactly the one that put
  `"effort":"minimal"` on `gpt-5.6-terra`'s wire.

  The **input** side is unchanged. `Builder.lowestReasoningEffort(X)` stays as shorthand for "the
  ladder starts at X and runs to the top", every built-in row without a hole still uses it, and both
  configuration keys keep their names — renaming `lowest-reasoning-effort` would have made every
  deployment that declared it lose the declaration in silence, which is the failure mode of the
  backlog item above.

- **Why a set.** `gpt-5.6-terra` resolves to the `gpt-5` prefix row, whose ladder starts at `minimal`
  — and terra **rejects** `minimal` while **accepting** `none` (measured 2026-09-09). No floor
  describes a ladder with a gap in the middle: `NONE` sends `minimal` and 400s, `MINIMAL` 400s and
  misdescribes, `LOW` avoids the 400 by writing down something untrue about the model. So terra gets
  an exact row of its own, `{none, low, medium, high}` — the four rungs the neutral vocabulary has;
  the model also takes `xhigh` and `max`, which `ReasoningEffort` deliberately does not carry.

- **That exact row narrows a promise, and the narrowing is the interesting part.**
  `builderWithDefaults().registerPrefix("gpt-5", …)` now reaches `gpt-5-mini`, `gpt-5-nano` and every
  future `gpt-5*` name **but not `gpt-5.6-terra`** — the same shadowing the eight built-in o-series
  exact rows already produce for `o1` and `o1-2024-12-17`, with the same remedy:
  `register("gpt-5.6-terra", …)`, or a configured declaration for that name, displaces the built-in
  row. The stronger promise ("every `gpt-5*` name, always") existed only to keep one javadoc example
  tidy, and it was costing a shipped HTTP 400.

- **A configuration surface for the gap.** `acceptedReasoningEfforts` joins `lowestReasoningEffort`
  on both `modelCapabilities` surfaces — `[none, low, medium, high]` in the CLI's yaml,
  `accepted-reasoning-efforts=none,low,medium,high` in the starter — because an operator whose
  gateway renames terra needs to be able to say what this table says. Writing both keys for one model
  fails at startup naming both and which to keep; an empty list is refused rather than treated as
  undeclared.

- **The divergence signature changed**, since "below" is provably not the reason any more:
  `reasoningEffortBelowLadder=` → `reasoningEffortOffLadder=`, and the message now prints the rungs
  the model does accept instead of the one it starts at. It is internal apart from the log text.

  Design: [`reasoning-effort-config-surface.md`](docs/design/llm/reasoning-effort-config-surface.md).
  Closes `L-1` in `docs/backlog/openai-model-capabilities-open-items.md`.

### LLM: Anthropic's thinking settings are reachable from configuration, under a vendor namespace

- **Three keys on both surfaces** (#54). `AnthropicConfig`'s `thinkingMode`, `thinkingBudgetTokens`
  and `replayThinkingBlocks` were programmatic-only, so a deployment assembled from configuration
  could not choose a dialect, set a budget, or turn replay off. Each surface keeps its own notation
  and the two do not mix:

  ```yaml
  # aimon-cli — camelCase                    # aimon-spring-boot-starter — kebab-case
  llm:                                       aimon:
    provider: anthropic                        llm:
    anthropic:                                   anthropic:
      thinkingMode: extended                       thinking-mode: extended
      thinkingBudgetTokens: 4000                   thinking-budget-tokens: 4000
      replayThinkingBlocks: true                   replay-thinking-blocks: true
  ```

- **A deployment that sets nothing is unchanged, byte for byte.** Every setter is called only when
  its key was written, so the vendor defaults — mode `OFF`, no budget, replay on — stand untouched.
  Capture and replay were already unconditional, so an always-on thinking model keeps the benefit of
  the previous entry with nothing configured; what these keys add is *tuning*.

- **They went to `aimon.llm.anthropic.*` / `llm.anthropic.*`, not beside the shared keys — which
  contradicts what #54's own body proposed.** The issue drafted `llm.thinkingMode` /
  `aimon.llm.thinking-mode` and then, two paragraphs later, called `thinkingMode` *"the first key
  whose name carries a vendor concept"* and named itself the trigger for the criterion that decides
  this. The criterion (`model-capability-config-key.md` §2.7) sends a key to a vendor namespace when
  its **name** carries a vendor concept or its **meaning** differs per vendor, and all three fail the
  first test: "thinking" is Anthropic's word for what this codebase calls `ReasoningEffort` /
  `ReasoningTrace`, `budget_tokens` is a literal request-body field, and a "thinking block" is a
  signed content block on that wire. Implementing the draft would have put the first vendor-named key
  into the shared namespace on the first occasion the criterion applied, which makes the criterion
  unfalsifiable. This is its second application and its first split; `B-21` in
  `docs/backlog/spring-boot-starter-open-items.md` records both.

- **A budget is `extended`'s, not an independent knob.** Written with `auto`, `adaptive` or the
  default `off` it fails at startup naming the block, rather than being silently dropped — the rule is
  `AnthropicConfig`'s and the surfaces only add the key path. The likeliest mistake is writing a
  budget with no mode at all, where the mode is `off` and the number would reach nothing. A budget is
  still floored at 1024 and clamped below `max_tokens`, which is 4096 when the agent definition sets
  no `model.maxTokens` (the CLI's bundled definitions set 40000) — so there `thinkingBudgetTokens: 8000`
  goes out as 4095 with a WARN, and raising that ceiling is the agent definition's `model.maxTokens`.

- **An anthropic block under another provider fails at startup**, from inside the branch that runs.
  A subtree named after one vendor leaves no ambiguity about whose it is. Deployments where no branch
  runs — `provider=none`, or an application supplying its own `LlmClient` — are untouched, for the
  reason `requireApiKey` gives: outside a running branch a check turns valid configuration into a
  boot failure. Backlog `L-3` counts three keys more; `L-1` (a misspelled starter key is silent, while
  the CLI's mapper throws) is widened by three keys and closed by none of this.

- **`thinkingMode` binds the vendor enum on the CLI and a `String` in the starter**, and that
  asymmetry is load-bearing rather than an oversight: `aimon-llm-anthropic` is `compileOnly` in the
  starter, and Spring's binder calls `getDeclaredMethods()` on every bean it binds, so a vendor-typed
  accessor would be a raw `NoClassDefFoundError` the first time somebody wrote one of these keys on an
  OpenAI-only classpath. The starter folds the string over `AnthropicThinkingMode.values()` inside the
  guarded slice, so the two surfaces cannot come to accept different spellings. Both accept all four
  values in any case. **In the starter, quote `off`** — YAML reads it unquoted as a boolean, and the
  failure message says so.

  Design: [`anthropic-thinking-config-surface.md`](docs/design/llm/anthropic-thinking-config-surface.md).

### LLM: Anthropic's thinking blocks now survive a tool call too

- **Verified against the real API, and one shipped claim was wrong.** The feature was built without a
  key, so its design carried a list of what fixtures could not establish. Those calls have now been made.
  **The load-bearing one holds: a `signature` this client parses and re-serialises through the SDK mapper
  is accepted by Anthropic's verifier** — which is the single property the whole round trip rests on, and
  the reason `AnthropicReasoningTraces` insists on the SDK's own mapper. That is established by a *pair* of
  live assertions, not by acceptance alone: a stripped turn is also accepted, so "the replay succeeded"
  would equally describe a client that silently dropped the block. The negative control is what settles it
  — a signature with **one character changed** is rejected with ``Invalid `signature` in `thinking` block``,
  so the verifier demonstrably reads it. `output_tokens_details.thinking_tokens`
  is real under exactly that name, both dialect rejections are word for word what `AnthropicThinkingMode`'s
  javadoc quotes, and the model/mode matrix matches the live model listing.

  **What was wrong:** `replayThinkingBlocks(false)` under `EXTENDED` was described as a pair that would be
  *rejected* on the second iteration of a tool loop, reasoned from two documented sentences. It is not —
  the request is accepted and the next turn still thinks; the vendor's graceful-degradation sentence is
  the one that governs. The warning stays, because the configuration does have a cost worth naming
  (thinking tokens billed every turn for reasoning discarded before the next), but it now names that cost
  instead of predicting a failure, and it fires for **both** dialects rather than only `EXTENDED`, since
  the cost does not pick one. Nothing about the round trip itself changed.

  **The verbatim rejection on Sonnet 5 / Opus 5 / Opus 4.7 / 4.8 / Fable / Mythos** is
  ``  `temperature` is deprecated for this model. `` So `temperature: 0.0` is rejected, and the sampling
  note below is right that any non-default value is — the word "deprecated" is about the value, not the
  key, which a later round measured by sending `1.0` and getting 200. `AnthropicThinkingMode.ADAPTIVE`
  was the one configuration that reached those models before the entry below fixed it, because asking for
  thinking is what suppressed the parameter.

  The live assertions are `AnthropicThinkingLiveTest`, gated on `ANTHROPIC_KEY` like the existing
  integration test, so a keyless build still skips rather than fails.

- **What it fixes.** `AnthropicLlmClient` dropped every `thinking` and `redacted_thinking` block it
  received (`convertResponse`, the line whose comment said "ignore other block types") and never sent one
  back, so the model re-derived its chain of thought on every ReAct iteration — worse answers, and
  thinking tokens billed again each turn, on exactly the multi-turn tool loops AIMON exists to run.
  `at.aimon.core.llm.ReasoningTrace` was built for this in the entry below and is already persisted end to
  end, so no core type changes shape: this provider now fills the list the OpenAI one fills.

- **Capture and replay are unconditional; only the request parameter is configured.** On Opus 5, Sonnet 5
  and the Fable/Mythos family thinking is **on by default**, so a client that gated its read path on a
  config flag would do nothing on the models the feature matters most for while every test stayed green.
  So blocks are read into traces always, stored traces are replayed unless you say otherwise, and only the
  `thinking` request parameter is a setting.

  **A thinking-off deployment is unchanged, structurally rather than by promise.** A model that is not
  thinking returns no blocks, so capture appends nothing; a transcript written before this change carries
  no Anthropic traces, so replay emits nothing; and with the default `thinkingMode = OFF` the request body
  is byte-identical to yesterday's, `temperature` included. A test asserts the serialised body, not the
  absence of a setter call.

- **Three new `AnthropicConfig` setters, programmatic only.**

  ```java
  AnthropicConfig.builder().apiKey(key).model("claude-sonnet-4-5")
          .thinkingMode(AnthropicThinkingMode.EXTENDED)   // default OFF; also ADAPTIVE
          .thinkingBudgetTokens(10_000)                   // optional, EXTENDED only, >= 1024
          .replayThinkingBlocks(true)                     // default true
          .maxTokens(16_000)
          .build();
  ```

  **There is no yaml or starter property for these yet**, deliberately and following the same precedent as
  the entry below: a config surface is its own issue rather than a rider on a behaviour fix. The
  consequence, stated rather than left implicit: **a CLI or starter deployment cannot turn thinking on
  until that lands** — though it still gets capture and replay for free on the always-on models, because
  that half is not configured.

- **Why the mode is three-valued and not a boolean.** Anthropic has two mutually exclusive thinking
  dialects, availability is per-model, and the wrong one is a 400 rather than a degraded response.
  `{"type":"enabled","budget_tokens":N}` is rejected by Opus 4.7/4.8/5, Sonnet 5 and the Fable/Mythos
  family; `{"type":"adaptive"}` is rejected by Sonnet 4.5, Opus 4.5, Haiku 4.5 and every earlier Claude 4 —
  including `AnthropicConfig`'s own default model. A boolean would have to guess which. The operator names
  the dialect instead, and **nothing in the request builder branches on a model name**, preserving the rule
  the entry below established. Deriving it from a per-model capability table would be better and is a
  separate round: `ModelCapabilities`'s five fields have nowhere to carry a two-valued mutually exclusive
  dialect axis, so "extend the registry" is a design round wearing a small name. The cost is honest — name
  the wrong mode and you get a 400 — and it is mitigated by the failure being non-retryable and therefore
  loud, by the default being `OFF`, and by both exact server messages being quoted in
  `AnthropicThinkingMode`'s javadoc so the error text leads to the one-line fix.

  **Superseded within this release**, by the thinking-dialect entry above — recorded rather than edited
  away, because both entries ship in one release and this bullet is the reason the newer one exists. The
  "separate round" it defers to is in these same notes: `ModelCapabilities` has a sixth field now,
  `thinkingDialect`, and the axis it "has nowhere to carry" turned out not to have to be two-valued.
  `AnthropicThinkingMode.AUTO` asks the table, and a named mode that contradicts a known dialect is
  translated rather than sent as a certain 400 — so **naming the wrong mode no longer costs a turn** on a
  model the table describes. Two things this bullet says do survive: the request builder still branches
  on **no model name** (it branches on the descriptor the registry resolved *for* that name, which is
  the whole point of the registry), and `OFF` is still the default.

- **`ReasoningEffort` maps onto a token budget, and two of the four numbers are made up.** Extended mode
  takes a budget, not a rung, so the mapping is a choice: `MINIMAL 1024 / LOW 2048 / MEDIUM 4096 /
  HIGH 16000`, with `NONE` meaning "send no `thinking` parameter at all". **`MINIMAL` and `HIGH` are the
  vendor's own published numbers** — the documented floor and the documented complex-task starting point;
  **`LOW` and `MEDIUM` are arbitrary**, chosen as doublings of the floor, and the design says so rather than
  dressing them up. What is not arbitrary and is pinned by tests: monotonicity, the 1024 floor, the
  `maxTokens - 1` clamp, and a loud give-up when no legal budget exists. **The clamp bites on
  `AnthropicConfig`'s default** — its `maxTokens` is 4096, so wherever the agent definition sets no
  `model.maxTokens` (the CLI's bundled definitions set 40000) `HIGH` clamps to 4095 and says so at WARN.
  In adaptive mode it is a ladder-to-ladder map onto `output_config.effort`, where the only loss is
  `MINIMAL` and `LOW` collapsing onto `low`.

- **`temperature` is omitted when thinking is on, never substituted.** Verified from the vendor docs rather
  than guessed, because the SDK javadoc says nothing about it: on thinking-capable models `temperature` and
  `top_k` are incompatible with thinking and `top_p` is accepted only between 0.95 and 1. So a request that
  carries a `thinking` parameter does not call the temperature setter at all — omission means never calling
  it, since a key left present with a null value is rejected the same way a value is — and `top_p` is sent
  only inside that window. Every omission goes through the existing `reportDivergence` at WARN, once per
  distinct value, because the observable outcome is a request that **succeeds with settings other than the
  ones configured**: no status code, and nothing else that would tell an operator.

  **A pre-existing, unrelated breakage this surfaces but does not fix.** The same vendor paragraph says that
  on Sonnet 5, Opus 5, Opus 4.7/4.8 and the Fable/Mythos family *any* non-default `temperature` returns 400
  on **every** request, thinking or not — so this client's unconditional `.temperature(0.0)` already blocks
  those models today. That is a model fact needing a per-model source of truth, i.e. the capability work
  above, and it is neither caused nor repaired by this change. Said plainly here so nobody infers that
  thinking support means those models now work. **The "non-default" qualifier in that sentence is right, and
  it was briefly overturned before being measured back** — see the Anthropic sampling entry below, which
  also fixes the breakage.

- **`redacted_thinking` gets no special case, on purpose.** Same slot, same anchor, same replay rule, no
  core-level discriminator: each block's own JSON carries its `type` and the SDK's union deserializer
  dispatches on it. Filtering capture on `block.type == "thinking"` alone is the vendor-documented way to
  break the multi-turn protocol, so the predicate is both kinds and a dedicated test pins the redacted case
  on the blocking and the streaming path.

- **Ordering is captured, not guessed — and the rule is not OpenAI's.** Anthropic puts text *between* the
  thinking block and the tool call on the ordinary `[thinking, text, tool_use]` shape, so OpenAI's "anchor
  to the first following tool call" rule would replay it as `[text, thinking, tool_use]`: a turn that no
  longer begins with a thinking block, which extended mode requires, and a rearranged consecutive sequence,
  which the API rejects. The rule here is *anchor to the first `tool_use` that follows, unless a `text`
  block intervenes first*. Both the blocking converter and the streaming mapper run the same function, which
  is the seam where the OpenAI round trip was nearly lost.

- **Streaming surfaces thinking and signature deltas.** `AnthropicStreamingMapper` reassembles the block
  from `thinking_delta` and `signature_delta` and hands the traces to the aggregator **before** the terminal
  chunk closes it. A block that streams no `thinking_delta` still yields a trace — on newer models the text
  is omitted by default and the signature is the load-bearing half — while a block that never receives a
  `signature_delta` is dropped with one warning rather than replayed unsigned, which would be a guaranteed
  rejection. Thinking text is **not** forwarded to the sink: showing a live thinking stream to a user is a
  different feature and needs a chunk kind `aimon-core` does not have.

- **`TokenUsage.reasoningTokens` is filled from `usage.output_tokens_details.thinking_tokens`.** SDK 2.13.0
  models neither `Usage` nor `MessageDeltaUsage` with that field, so it is read untyped from
  `_additionalProperties()` in one place, degrading to `0` for anything unrecognised and never throwing. It
  is reported and **not** priced and **not** added to `totalTokens`, because thinking tokens are billed as
  output tokens and are therefore already contained in `output_tokens`. The field name comes from the
  vendor docs and **has not been seen on a live response**; if it is wrong the counter reads zero and
  nothing else changes.

- **`AnthropicMessageConverter.convertMessages(List<Message>)` is deprecated**, delegating to a new
  three-argument overload that takes the provider name and a divergence reporter. The old one has no
  provider name to match a stored trace against, so it cannot tell one this client authored from one it must
  not send, and therefore replays none of them. It still compiles and still behaves exactly as it did.

- **`replayThinkingBlocks(false)` alongside `EXTENDED` is warned about at startup of the first request.**
  The two are documented as incompatible: extended mode requires the final assistant turn of a
  thinking-enabled request to begin with a thinking block, and that setting strips exactly that block, so a
  tool loop is expected to be rejected on its second iteration. The warning names `thinkingMode(OFF)` as the
  remedy rather than "turn replay back on" — the switch exists because replay can itself fail, so undoing it
  walks back into the other failure. It is a warning and **not** a constructor refusal, which is what the same
  config class does for a budget set outside `EXTENDED`, because the rule is read from documentation and
  another documented sentence (mid-turn conflicts *"degrade gracefully… the API doesn't error"*) contradicts
  it. A live call settles it; until then an unverified rule is not made un-overridable.

- **Two divergence registers, because "say it once" is right for a setting and wrong for the traffic.** The
  existing once-per-signature rule is justified by the thing it describes having been set once, in an agent
  definition. Four of the conditions this entry adds are not like that — a stream that loses its
  `signature_delta`, a stored payload this build cannot parse, a trace anchored to a tool use that is gone, a
  trace authored by another provider. Those are properties of the traffic, they can start midway through a
  process, and their signatures are constant, so once-only would have described the first occurrence and then
  gone quiet while every following turn lost its reasoning too. They are counted instead and reported on the
  **1st, 10th, 100th …** occurrence with the count in the line: one warning still means it happened once, and
  a line reading *occurrence 100* means the feature is off. Sampling and budget divergences are unchanged.

- **The omitted `temperature` is described differently depending on whose value it was.** `LlmModel` knows
  whether a call set one; `AnthropicConfig` cannot know whether anyone typed its own, and its default is
  `0.0`. Telling an operator who never touched sampling that *"temperature 0.0 is incompatible"* is a
  complaint about a configuration they did not write, so when no per-call temperature is present the message
  says that first. Two signatures, deduplicated separately.

- **What is not verified, and it is the thing the feature rests on.** This work was done with **no
  Anthropic API key**; every test is a fixture test and the suite runs without network. So nothing here
  shows that Anthropic's verifier accepts a replayed signature — only that this client does not change it:
  the decoded `signature` that leaves equals the one that arrived, and the surrounding object gains and
  loses no field, asserted as a parsed tree rather than by substring. The full list of what fixtures cannot
  establish, including the preserved-thinking prefix check that `replayThinkingBlocks(false)` exists to
  escape, is in `docs/design/llm/anthropic-thinking-traces.md` §9.

### LLM: `AnthropicLlmClient` can talk to the current Claude generation again

- **What it fixes.** `buildRequest` called `.temperature(...)` on every non-thinking request, filling it
  from `AnthropicConfig.getTemperature()` — a primitive `double` seeded at `0.0`, so "nobody configured a
  temperature" and "somebody configured 0.0" were the same state. Six of the eleven models this account
  can reach reject `temperature: 0.0`, and the default thinking mode is `OFF`, so **in its default
  configuration this client could not reach the current Claude generation at all** — Fable 5.1, Fable 5,
  Opus 5, Opus 4.8, Opus 4.7 and Sonnet 5, plus the Mythos family it cannot see. Both halves are fixed:
  the model fact comes from the capability registry, and the config stops inventing a value. There is no
  `model.startsWith("claude-")` anywhere in the request builder.

- **The registry is reused, not duplicated.** `at.aimon.core.llm.capability` was already vendor-neutral
  and already had the field this needed (`supportsSamplingParameters`), and the two sibling per-model
  registries in the same area already carry `claude-*` rows beside `gpt-*` ones. `withDefaults()` grows by
  six prefixes — `claude-fable-5` (which also covers `claude-fable-5-1`), `claude-opus-5`,
  `claude-opus-4-7`, `claude-opus-4-8`, `claude-sonnet-5`, and `claude-mythos`. No new type, no new field.
  `AnthropicConfig` takes a `modelCapabilityRegistry(...)` exactly as `OpenAIConfig` does.

  **One consequence of one table with two consumers:** a `claude-*` model name routed through an
  OpenAI-compatible gateway by `OpenAILlmClient` now resolves to these rows and has its sampling
  suppressed too. That is correct — the underlying model does refuse — but it arrives from a direction
  neither client's source shows, so the registry says so beside the rows.

- **Measured against the live API on 2026-09-09**, 58 calls with three controls, and **it corrects the
  Anthropic thinking entry above.** Rejection is by **value** for `temperature` — `1.0` returns 200 on all
  six, so the earlier "refused outright rather than compared against a default" is wrong and the original
  "any non-default value" reading was right — and by **presence** for `top_p`, where even `1.0` is a 400.
  No prior design distinguished the two. The negative control matters as much as the table: the identical
  body with no sampling parameter returns 200 on all six, so **omission cannot 400**, which is what makes
  suppression safe and fail-open the right posture for a model nothing describes. `"temperature": null` is
  a 400 on an accepting model too, so omission means never calling the setter — now measured rather than
  asserted. The five reachable models that **accept** all three are deliberately absent from the table;
  a `claude-opus-4` family prefix would have caught two of them and is what one test exists to prevent.

- **Breaking:** `AnthropicConfig.getTemperature()` returns `Optional<Double>` instead of `double`, and
  `DEFAULT_TEMPERATURE` is gone. The same change `OpenAIConfig` made in #43/#44, for the same reason —
  the provider guide forbids a provider inventing a sampling value, and of the values available to invent
  `0.0` was the measurably worst one. **A deployment that never configured a temperature changes on the
  wire**: it sent `0.0` and now sends nothing, so Anthropic's own default (`1.0`) applies and output is
  less deterministic. On the six refusing models that is moot — those deployments did not work. On the
  five accepting ones it is real, and the remedy is one line: set the temperature you want.

- **Suppression is reported, never silent.** Each dropped value goes through the existing
  `reportDivergence` at WARN, once per distinct value **and model**, naming both. A request nobody put a
  sampling value on says nothing, by construction rather than by a special case. The capability gate sits
  *outside* the older thinking rules, so a `top_p` inside the `[0.95, 1.0]` window that thinking allows is
  still dropped on a model that refuses `top_p` — the one live 400 a temperature-only fix would have left.

- **Unknown models still fail open**, and that is inherited from `ModelCapabilities.unknown()` rather than
  decided here: a model no registry describes keeps today's request shape. A gateway that renames a
  refusing model therefore still hits the 400 until somebody names it — which is what the config surface
  below is for.

- **Both configuration surfaces now reach the Anthropic branch.** `llm.modelCapabilities.<model>` (CLI,
  camelCase) and `aimon.llm.model-capabilities.<model>` (starter, kebab-case) are unchanged in spelling,
  keys and translation; what changes is that the two guards which **refused** them under
  `provider: anthropic` are deleted, because the sentence they carried ("only the OpenAI client consults
  the model capability registry") stopped being true. A configuration that failed fast now boots and
  works. That is what #46 kept the key in the shared `aimon.llm.*` namespace for.

- Design: `docs/design/llm/anthropic-sampling-capabilities.md`, whose §2 carries the probe table and §11
  the rows that were **not** measured — above all `claude-mythos`, which no account here can see and which
  ships as documentation-derived, labelled in the source beside it.

### LLM: a reasoning model's chain of thought now survives a tool call (OpenAI Responses API)

- **What it fixes, and why the phase-1 fix was not enough.** Phase 1 tried to make `gpt-5.x` usable
  with tools by sending `reasoning_effort: none` — a working request bought by turning off the thing
  the model was chosen for, and, as the 2026-09-09 probes later showed, not even a working one, since
  `none` is not an accepted value. That remedy is gone; the effort is now omitted instead. It also left a quieter cost: Chat Completions never returns reasoning items, so
  nothing carried across a tool call and the model re-derived its chain of thought on every ReAct
  iteration. Worse answers, and reasoning tokens billed again each time, on exactly the multi-turn
  tool loops AIMON exists to run. A model whose capabilities say its reasoning traces round-trip now
  goes to `/v1/responses`, where tools and reasoning coexist and the items can be replayed.

- **How the endpoint is chosen: per request, never by name.** `ModelCapabilities` gains a fourth
  flag, `supportsReasoningTraceRoundTrip()` — *does this model return reasoning traces a client
  should send back next turn* — defaulting to `false` in `unknown()`, so every model no registry
  describes keeps the request surface it has today. The built-in table sets it on the `gpt-5` prefix
  and leaves the non-reasoning `gpt-5-chat` variant alone. The decision is made from the
  **per-request** model name (`LlmModel.name(...)` overriding the config's), because a `gpt-4o`
  compaction call inside a `gpt-5.x` session is the ordinary case: that call goes to Chat while its
  session goes to Responses. There is still no `model.startsWith("gpt-5")` anywhere.

  The flag is deliberately not called `usesResponsesApi`: that would name one vendor's endpoint
  inside a provider-neutral type. Anthropic will read the same flag to mean "send the thinking
  blocks back", with no endpoint change at all.

- **New `at.aimon.core.llm.ReasoningTrace`, and `Message` gains an eighth field.** A trace carries
  three things: an opaque provider `payload`, the `providerName` that authored it, and an optional
  `toolUseId` anchoring it to the tool call it precedes. `aimon-core` performs exactly one operation
  on the payload — copy. `MessageArtifact` was examined first and rejected: its `path` and
  `fileName` are both required and would be lies for this content, and it is already consumed as a
  file reference, so a reasoning blob in that list would be offered to a user as a downloadable file.
  **The new field participates in `Message.equals`/`hashCode`/`toString`, and so does
  `LlmResponse`'s** — the same caveat the `TokenUsage` entry below carries, and for the same reason: a
  message or response carrying traces is no longer equal to one without, which can matter to a test
  that builds the expected value by hand (`Message.assistant(text, toolUses)`) and compares it against
  one an executor built from a Responses turn.

- **It lands in the persisted transcript, additively in both directions.** `JsonSessionSnapshotCodec`
  writes a `reasoning` array **only when non-empty**, so every document produced before this field
  existed is byte-identical to one produced now; a reader that has never heard of the key skips it,
  exactly as it already skips anything else it does not know. `FORMAT_VERSION` stays at **1** — the
  codec's existing argument applies unchanged, since bumping it would make every stored snapshot
  undecodable to buy nothing. The three `SessionRecordCodec` backends need no change: they store the
  transcript as an opaque string and cannot see the field.

- **`TokenUsage` gains a fourth field, `reasoningTokens`. Not source-breaking; visible in `equals`.**
  `of(int,int,int)` is retained and yields `0`; a four-argument overload is added; the constructor is
  private, so no caller constructs one directly. **But it participates in `equals`, `hashCode` and
  `toString`** — a usage carrying reasoning tokens is no longer equal to one without, which can
  matter to a test that compares usages built two different ways. **It is deliberately not priced:**
  reasoning tokens are a *subset* of the completion tokens rather than an addition to them (OpenAI
  reports them inside `output_tokens_details`), so `ModelPrice.costOf` has already billed them and
  adding them again would bill them twice. Recorders receive the whole object, so the field reaches
  every metering and tracing sink for free.

- **The cross-node wire carries it, and tolerates a node that does not.** `StatusSnapshotPayload`
  and `AgentExecutionEventPayload` write a new `reasoning` key and read it through a new
  `PayloadValues.asIntOrZero`. The other three token keys keep the strict accessor, because their
  absence really is a malformed payload; this one is read tolerantly because during a rolling
  upgrade a map written by an old node has no such key at all — and both decoders turn any exception
  into a dropped signal, so the strict accessor would discard the whole status update rather than
  report one counter as zero.

- **A gateway that implements only `/v1/chat/completions` can turn the new path off**, with
  `OpenAIConfig.responsesApiEnabled(false)`. **Say the asymmetry plainly:** the situation that needs
  the switch is fully yaml-creatable — `baseUrl` is a CLI key and a starter property, and any real
  `gpt-5*` name hits the built-in table — while the remedy is **Java-only**. That makes it different
  in kind from the other two programmatic-only knobs, which override things that already work; this
  is the one case in this change where a working yaml-configured deployment can break with no
  yaml-reachable fix. Setting it restores phase 1's behaviour exactly.

- **Redaction does not reach a reasoning payload, and that cannot be fixed.**
  `Message.mapText` is the documented single entry point for whole-message text rewriting, and the
  payload deliberately bypasses it: OpenAI's carrier is `encrypted_content` and Anthropic's is a
  `signature`, so rewriting one byte invalidates it and the provider rejects the replay. What is on
  offer is disclosure — stated on `mapText`, in the design doc and here — plus the off switch above.
  A deployment with a hard redaction requirement on everything leaving the process should set
  `responsesApiEnabled(false)` and accept phase 1's behaviour.

- **Transcripts grow.** An `encrypted_content` blob is far larger than the text of the turn, and
  every assistant turn in a reasoning session carries one. Capping or truncating it would break the
  round trip, which is the whole feature. Compaction sheds them — `MessageStripper` now drops traces
  unconditionally while keeping tool uses, so it can never leave a reasoning item whose following
  call is gone — and the switch removes them entirely. Nobody has measured what a 40-turn `gpt-5`
  tool loop does to a session row.

- **Token *estimation* under-counts on this path, deliberately.** `HeuristicTokenEstimator` and
  `TikTokenEstimator` count `0` for reasoning traces while `DefaultCompactionGuard` drives every
  threshold from the estimator rather than from provider usage, so the guard under-counts by roughly
  `reasoning_tokens`. The obvious fix is wrong: counting the base64 blob as text would over-count by
  an order of magnitude and force premature compaction, destroying the traces it was protecting. The
  blast radius is bounded — compaction drops the traces so the error resets, the context limits
  already reserve headroom, and the same estimator already omits tool *definitions*.

- **Also on this path:** Responses stop reasons (`status` + `incomplete_details.reason`, plus whether
  the output carried a tool call) map to real `StopReason` values instead of degrading to `UNKNOWN`;
  `call_id` is what `ToolUse.getId()` holds, because that is the id a `function_call_output` must
  match, and the item's own `id` is never invented on a replay; tool definitions are sent with
  `strict: false`, which is what the field's absence already means on Chat, because strict mode
  rejects schemas this repo explicitly exempts from its own strictness rule (every MCP tool, for one).

- **A provider error on this endpoint arrives as data, not as an SDK exception.** The SSE decoder
  throws only on a top-level `"error"` key, which neither `response.error` nor `response.failed` has,
  and a blocking 200 carrying `status: "failed"` is an ordinary return value. Left alone, the same
  server condition that yields a retryable exception on the Chat path would yield none here. The new
  code builds the exception the mapper would have produced and throws it from inside the client's
  existing cascade, so classification and the retry budget match the blocking path.

- **Chat Completions is unchanged**, and structurally so rather than by promise:
  `OpenAIMessageConverter` is not opened, and a message carrying reasoning traces produces a
  byte-identical Chat request to the same message without them.

- **Two follow-ups to the above, applied.** A tool call whose `arguments` are not JSON now raises the
  same `MessageConversionException` on the Responses blocking path that Chat Completions has always
  raised for the same bytes, instead of running the tool with an empty input map — user-visible, and
  the reason is that an empty map is a *different* answer, not a smaller one. A JSON **`null`** value
  is on the other side of that line and is not malformed at all: it means *absent*, so the key is
  dropped through `NullSafeMaps` and the turn completes, exactly as it does on Chat Completions. (It
  briefly did not: reading the arguments through `Map.copyOf`, which rejects a null value, failed the
  whole turn over an optional parameter a model had filled with `null` instead of omitting.) Streaming
  is unchanged and still degrades, because there `ChunkAggregator` owns those bytes for every provider.
  Separately,
  a streamed `response.completed` whose nested `status` is `failed` or `cancelled` now fails the call
  as the blocking path already did, instead of returning an empty success; no conforming provider
  sends that shape, so nobody in-tree can hit it.

- **Not started, by design: the Anthropic half.** `AnthropicLlmClient` still ignores
  `ThinkingBlock`/`RedactedThinkingBlock`, and `AnthropicStreamingMapper` still does not surface
  thinking deltas. The slot is designed to fit them — signature-carrying blocks round-trip byte-exact
  precisely because nothing in core reads the payload — and the work is recorded as a follow-up
  rather than half-built. Design:
  [`docs/design/llm/openai-responses-path.md`](docs/design/llm/openai-responses-path.md).

### Sessions: one unreadable inbox entry no longer costs the whole batch

- **`SessionInbox.collect` returns `CollectedBatch` instead of `List<InboundMessage>`.** A
  **breaking change** to a published SPI (`at.aimon.core.agent.session.inbox`), and — like the
  `getUserInput()` change below — not a rename, so there is no row in
  [`rename-maps.md`](docs/migration/rename-maps.md): the method resolves exactly as before with a
  different return type. Every implementation and call site is a compile error, which is the whole
  point; there is nothing that breaks quietly.

  | Was | Is |
  |---|---|
  | `List<InboundMessage> m = inbox.collect(id, LATER);` | `inbox.collect(id, LATER).getMessages()` |
  | — | `.getUnreadable()` is new: entries removed from the backend that this build could not decode |

  A `default` method keeping the old signature was considered and rejected: an implementation that
  was never updated would then report "nothing unreadable" without anyone having decided that.

- **What it fixes.** All three backends delete an entry from storage *before* the codec rebuilds it
  — Redis's collect script `XDEL`s inside Lua, Postgres commits its `DELETE … RETURNING`, MongoDB
  uses `findOneAndDelete`. One entry this build could not decode therefore took **every message that
  call collected** with it: gone from storage, delivered to nobody, and the submitters left waiting
  on turns no node would ever run. Reproduced on all three with real containers (a damaged
  `initiator.type` between two sound messages left 0 entries on Redis and Postgres and 1 on MongoDB,
  with the message *ahead* of the poison lost as well). The decode is now guarded **per entry**,
  catching `RuntimeException` — the boundary is the entry, not the exception type, because the value
  objects an envelope rebuilds signal a refusal with exceptions of their own.

- **A dropped entry is not silent.** It is logged at `WARN` naming the session and the backend's
  entry id (never the payload), and whatever remained legible of its address — `turnId`,
  `idempotencyKey`, as raw strings — rides back on the batch so `SessionRouter` fails the waiting
  submitter through the rail it already uses for a message it refused. Without that the caller
  learned nothing until the five-minute forward deadline. A new `TURN_RESULT` outcome `UNREADABLE`
  distinguishes "never attempted" from `FAILED`'s "attempted and threw"; a node one release behind
  reads it as `FAILED` rather than dropping the signal.

- **Not covered:** the dropped entry's bytes are gone. A dead-letter surface was considered and
  deferred — the reasoning, and the trigger that would reopen it, are in
  [`inbox-collect-durability.md`](docs/design/session/inbox-collect-durability.md).

### Sessions: a routed submission carries the input, not a rendering of it

- **`SubmitRequest.getUserInput()` and `InboundMessage.getUserInput()` return `UserInput` instead of
  `String`.** This is a **breaking change** to two published surfaces
  (`at.aimon.session.routing`, `at.aimon.core.agent.session.inbox`), and it closes a **capability**
  gap rather than a runtime one. `LiveSession` has taken a `UserInput` for some time, so an image or
  a document worked on whichever host held the handle; a submission routed through `SessionRouter`
  could not carry one at all, because the builder took only text. **Nothing was being dropped at
  runtime** — an application that scaled out found the call it had been making no longer compiled,
  which is a wall rather than a leak. What this adds is the ability to route such a turn, not the
  recovery of a value the framework used to discard.

  It is not a rename, so there is no row in
  [`rename-maps.md`](docs/migration/rename-maps.md): the name resolves exactly as before, with a
  different type. It is also the shape the rest of the codebase already had —
  `AgentExecutionRequest`, `SessionRewindPoint` and `RewoundTurn` all spell `getUserInput()` that
  way, and these two were the odd ones out.

  | Was | Is |
  |---|---|
  | `String s = request.getUserInput();` | `String s = request.getUserInput().asText();` |
  | `builder.userInput("hello")` | unchanged — the `String` overload stays, as sugar for `TextInput.of` |
  | — | `builder.userInput(image)` is new |

  No producer call site changes; the break falls on consumers, which is where the loss was.

- **The inbox wire gained a key rather than changing one.** `userInput` keeps its spelling *and its
  type*, now holding `asText()`; a non-text input additionally writes its encoding under
  `userInputEncoded`, and a plain-text one writes no sidecar at all — so a text submission is
  byte-for-byte the document the previous build wrote. Decode prefers the sidecar and falls back to
  wrapping the string. **No data migration and no rolling-upgrade coordination is needed**, in either
  direction; [`frozen-names.md`](docs/migration/frozen-names.md) records why the obvious tidy (making
  `userInput` hold the structured value) is the one thing that may not happen — an older node reading
  such an entry gets `""` from `JsonNode.asText()` and runs an empty turn, or, in MongoDB, cannot
  decode it at all.

  What an older node *does* get from a multimodal entry is the `asText()` rendering: a turn that says
  an image was attached rather than one that pretends nothing was. It cannot run the image either
  way, and the alternative — an entry it cannot decode — is a turn nobody runs.

- **An encoding this build cannot read degrades to the text beside it rather than refusing the
  entry.** The wire's forward direction — this build reading what a node one release ahead wrote,
  with a sixth `InputType` in it — costs far more than a refusal usually does, because all three
  backends remove an entry from storage *before* the codec runs (Redis `XDEL`s inside its collect
  script, Postgres commits its `DELETE … RETURNING`, MongoDB uses `findOneAndDelete`). A throw there
  does not reject one message; it destroys every message that call collected. `UserInputCodec`
  therefore exposes `decodeOrText`, which the three inbox codecs call: it falls back to the
  `asText()` rendering stored beside the encoding — the same thing an older node would run — and
  logs at `WARN` **naming the session**, because a silent fallback would be the very failure the
  structured encoding was added to remove, and a warning nobody can attribute to a session is one
  nobody can act on. `JsonSessionSnapshotCodec` still refuses the same failure — and refuses a
  narrower set of them than the inbox catches — and the asymmetry is documented where the decision is
  made: a rewind point has no string to fall back to and loses only a retry the user can re-issue.

  **The boundary is the field, not the kind of failure.** A decoder cannot distinguish a document
  from a newer build from a damaged one, so the rule is the one it can enforce: everything thrown
  while reading that one field degrades, and the rest of the envelope still refuses — a malformed
  `initiator` or an unknown `priority` says *damaged*, and the text beside them stands in for
  nothing. `UserInputCodec` also normalizes what the input value objects declare
  (`ImageInput`/`AudioInput` enforce a MIME prefix and signal with `IllegalArgumentException`) into
  its own exception, the way it always did for invalid base64, so the declared contract of `decode`
  is true for its strict caller as well — which incidentally stops one unreadable rewind point from
  failing the whole snapshot around it.

- **`IdempotencyEntry.inputHash` keeps `sha256(text)` for text turns.** That digest is written to the
  shared store and recomputed by whichever node a retry lands on, which during a rolling upgrade is
  as likely to be one that predates this change. Hashing the encoded form for text would have turned
  every legitimate cross-version retry into `IdempotencyConflictException` — "key reused with
  different input" — for as long as the two builds coexisted. Non-text turns hash over the encoding,
  where there is no earlier value to match.

- **`UserInputCodec` is new** (`at.aimon.core.subagent.task.codec`), lifted unchanged out of three
  private methods on `JsonSessionSnapshotCodec` so the inbox reuses the encoding rather than
  hand-mapping the five shapes a second time — the situation `SubmitOptionsCodec` was extracted to
  stop, one layer up. What made it a class is that those three were private, so no other module
  could call them at all.

  **The stored format is unchanged** — same field names, same type tags, same 32-level nesting
  bound — so every existing snapshot encodes and decodes as before. One thing about *reading* an old
  document did change, and it is the improvement described two bullets down rather than a
  regression: a rewind point whose input a value object refuses (an `image` carrying a `video/mp4`
  MIME type) used to take the whole snapshot down with it, and now drops only the point.

  Unlike its neighbour it takes no `ObjectMapper` (every leaf is a `String`, so no mapper
  configuration can reach the wire) and it offers a text form as well as a node form, which is what
  keeps the MongoDB inbox from acquiring a second representation the way it has one of
  `submitOptions`.

- **`AimonSessions.newRequest(SessionId, UserInput)` is new.** The starter is the scale-out shape,
  so it is where this most needed a route that is not `newRequest(id, "")` followed by
  `.userInput(image)`, which works and submits a turn whose text part is an empty string.

  Additive **for callers, not for implementors**: `AimonSessions` is a published interface and this
  is an abstract method with no `default`, so an application with its own implementation of the
  facade stops compiling until it adds one. That is allowed in a `0.x` minor and is named here rather
  than hidden behind the word "additive". No `default` was given deliberately — a default could only
  throw or flatten the input, and both are worse than a compiler error for a facade whose entire job
  is to fill in the fields a raw `SubmitRequest` leaves dangerous.

- The multi-node contract suite gained the scenario, so it runs over Redis, Postgres and MongoDB:
  what an image has to survive is each backend's own serialization, and no in-memory test can see
  that. Closes the second open item in
  [`interrupt-open-items.md`](docs/backlog/interrupt-open-items.md).

### Memory: the distributed backends leave, the SPI stays

- **`aimon-memory-postgres` and `aimon-memory-mongodb` are removed.** This is a **removal, not a
  migration**, and the distinction is the whole entry: distributed memory now lives in a separate
  service ([aimon-memory](https://github.com/kangwoo/aimon-memory)) whose schema is a different
  design keyed on `(workspace, observer, observed)`. **Nothing migrates the old `mem_*` tables or
  collections into it.** A deployment with data in either backend keeps running on `0.2.4`, or starts
  empty on the service — there is no path that carries the rows across, and the similar module names
  must not be read as one. What each removed piece was replaced *by*, rather than migrated *to*:

  | Removed | Replaced by |
  |---|---|
  | `PostgresDerivationQueueManager` (row-locked derivation queue) | `aimon-memory-worker`'s `WorkerLoop` and its Representation / Summary / Dream / Deletion consumers |
  | `KnowledgeStoreOutboxRelay` (outbox → embedding index) | pgvector natively — `Vectors`, `EmbeddingDimensionCheck`, `aimon-memory-embed` |
  | `Postgres`/`Mongo` `{Observation,Representation,Workspace}Store` | `aimon-memory-store` — Flyway plus twelve JDBC repositories |

  [`docs/project/api-stability.md`](docs/project/api-stability.md) §4.1 covers how this squares with
  the DDL freeze that section used to name these modules in: the freeze is a promise about a *live*
  surface — that stored names do not drift out from under stored data — and a surface that is gone
  has no data left to read out from under.

- **`aimon-memory-file` is merged into `aimon-core`** as `at.aimon.core.memory.file`, beside the
  `InMemory*Store`s it is the durable counterpart to. The classes keep their names and signatures, and
  **the JSONL format, the field names, the sidecar `<log>.lock` and the compaction temp-file swap are
  unchanged** — an existing log is read without conversion. Applications drop
  `implementation("at.aimon.core:aimon-memory-file")` and change the import prefix; the table is in
  [`docs/migration/rename-maps.md`](docs/migration/rename-maps.md). Not `at.aimon.core.memory.impl.file`:
  the memory domain has no `.impl` split today, `aimon-cli` still assembles these classes by name, and an
  `.impl` package with no ArchUnit rule behind it is a label rather than a boundary
  ([`pluggable-memory-backend.md`](docs/design/memory/pluggable-memory-backend.md) §4.2).

  One internal detail changed and nothing observable followed it: `MemoryJsonCodec` no longer registers
  Jackson's `JavaTimeModule`. It never used it — every timestamp is mapped by hand through
  `Instant.toString()` / `Instant.parse` and the mapper only ever handles `JsonNode` trees — and keeping
  it would have made `jackson-datatype-jsr310` a published dependency of `aimon-core` for nothing.

- **`aimon-memory-testkit` is now published** (`at.aimon.core:aimon-memory-testkit`), and joins the BOM
  automatically. The five-tier `PeerMemory` contract suite was deliberately unpublished, following
  `aimon-filesystem-testkit` and `aimon-session-testkit`. Those two describe contracts whose every
  implementation is in this repository, so an unpublished module reaches all of them. This one's
  subjects are `PeerMemory` backends, and after the removals above the implementation that most needs
  holding to the contract — `RemotePeerMemory`, in another repository — was the one implementation that
  could not run it. The other two testkits are unchanged.

- **The `PeerMemory` SPI now has an out-of-repository compile consumer**, which is a new obligation
  rather than a change: `aimon-memory-client` compiles against `at.aimon.core.memory.PeerMemory` and the
  five tier interfaces, so changing those signatures breaks a build this repository's own gate cannot
  see. [`api-stability.md`](docs/project/api-stability.md) §4.2 records the ordering that follows.

- Documentation: [`pluggable-memory-backend.md`](docs/design/memory/pluggable-memory-backend.md) is
  corrected where it predicted otherwise — §4.3 said the three backend modules would not change by a
  single line, and §8.1/§12 expected two in-tree adapter modules (`aimon-memory-honcho`,
  `aimon-memory-dyad`) that were never built. The design's actual claim — that the replacement seam is
  the five service tiers, not the store interfaces — is what the outcome confirms.

### Build, CI and the release gate

- **Three testkits no longer run their consumers' tests against library versions those consumers do not
  ship** (#91). `aimon-filesystem-testkit`, `aimon-session-testkit` and `aimon-llm-capability-testkit`
  governed the JUnit their main sources compile against with `api(platform(libs.spring.boot.dependencies))`,
  and `api` put Spring Boot's whole dependency management on every consumer's test classpath, where a
  managed version newer than the one a module ships wins. Across their seven consumers, 29 artifacts
  resolved under test to a version other than the shipped one, and that platform had moved 21 of them:
  the nine #87 added to `aimon-cli` (HikariCP 6.3.3 against the 5.1.0 it ships among them), the MongoDB
  driver 5.5.2 against 4.11.1 on `aimon-filesystem-gridfs` and `aimon-session-mongodb`, HikariCP on
  `aimon-session-postgres`, `reactor-core` on `aimon-session-redis`, and Caffeine and
  `error_prone_annotations` on the starter. All three now take `platform(libs.junit.bom)`, as
  `aimon-memory-testkit` already did, and 20 of those differences are gone; the 21st, the starter's
  `error_prone_annotations`, fell from 2.49.0 to 2.33.0 under test and still differs from the 2.21.1 it
  ships, now through the vendor SDKs. No consumer gained a difference. The three are unpublished, and the
  POM and module metadata of all 21 published modules are byte-identical before and after. The reason is
  written once, next to `junit` in `gradle/libs.versions.toml`, and the catalog's now-unused
  `spring-boot-dependencies` entry is removed. The nine that remain — that one, and eight that predate
  this — have other sources: `spring-boot-starter-test` (Logback and `jakarta.xml.bind-api` on the CLI),
  Testcontainers (`org.jetbrains:annotations`) and the vendor SDKs on the starter's test classpath
  (`error_prone_annotations`).

- **`aimon-cli`'s tests run on the Logback and JAXB API the CLI ships, and the other remaining test-classpath
  differences are recorded as decisions** (#99). Of the nine artifacts the entry above left resolving under
  test to a version their module does not ship, the three `spring-boot-starter-test` raised on the CLI —
  `logback-classic` and `logback-core` 1.5.34 against the 1.5.13 it ships, `jakarta.xml.bind-api` 4.0.5
  against 4.0.4 — are aligned: the CLI's two test classpaths now resolve consistently with its runtime
  classpath, which is what its distribution packs, so its tests now run on Logback 1.5.13. The other six are
  accepted, each with its measured reason, next to `junit` in `gradle/libs.versions.toml`: Testcontainers'
  `org.jetbrains:annotations` and the provider SDKs' `error_prone_annotations` are annotation jars no code
  here names. Nothing a consumer resolves changes — every compile and runtime classpath in the build, and
  every published POM and module metadata file, is as before. The same note now says why
  `aimon-memory-testkit` publishes a `junit-bom` floor of 5.12.2 while its test classpath, which runs no test,
  resolves 5.13.4; the entry above is corrected (the platform moved 21 artifacts, and 20 differences went
  away); and `ModelCapabilityBindingProbeTest` drives the `declaresAnything()` refusal through the probe's
  public pair check instead of calling the helper that words it. What #99 left undecided — the same source on
  three more modules, and a check that would notice the next difference — is backlog D-2 and D-3.

- **The model-capability binding probe names the right fix when the builder, not the value, refuses a
  probe value** (#91). A declarable key missing from `ModelCapabilityDeclaration.Builder.declaresAnything()`
  made `ModelCapabilityBindingProbe` say *"pick one the declaration accepts"*, which no value can satisfy;
  it now points at `declaresAnything()`, and a test pins that message. The class javadoc of
  `AbstractModelCapabilityBindingContractTest` credited yaml-key binding to `LlmClientFactoryTest`, which
  never reaches Jackson; it is `CliConfigLoaderTest`.

- **`playwrightTest` ran nothing, and now it runs in both gates.** The task was registered without
  `testClassesDirs` or `classpath` — which a bare `register<Test>` does not inherit from `test` — so it
  matched no test class, reported `NO-SOURCE` and finished green in 650ms. It had been in that state
  since the initial commit, so the four `@Tag("playwright")` tests in `PlaywrightLifecycleManagerTest`
  had never executed anywhere, including for anyone who ran the opt-in task deliberately. **A tier
  nothing runs is a tier nothing can tell apart from a passing one**, and the two lists
  `ReleaseGateMatchesCiGateTest` compares could not see it precisely because it was absent from both.

- **Browser binaries are installed by the build, not by the first test.** Playwright's Java bindings
  download from inside `Playwright.create()` — `DriverJar.installBrowsers()` shells out to the bundled
  driver with a bare `install`, fetching every browser marked `installByDefault` (chromium,
  chromium-headless-shell, firefox, webkit, ffmpeg): **158s and 1.0 GB** cold.
  `PlaywrightLifecycleManager` wraps that call in `future.get(30, SECONDS)`, so a cold cache did not
  make the tier slow, it made it **red** — reproduced twice, 2m21s and 2m27s, all four tests failing.
  A half-populated cache was worse: with chromium present and firefox missing, two tests failed and
  two passed in the same run. A new `installPlaywrightBrowsers` task hoists the download out of the
  timeout and narrows it to chromium, which is all these tests launch — **94s and 520 MB** — and
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` stops the run-time call reaching for the rest. From an empty
  cache the tier is now green in 1m48s; warm, 14-28s.

- **The tier joined CI and the release gate on `integrationTest`'s argument, not `packagingTest`'s.**
  `aimon-browser-playwright` is published to Maven Central and these are the only tests in the build
  that start a real browser: `PlaywrightLifecycleManager` — which owns the browser process, the daemon
  worker thread and the shutdown ordering — measures **9% line without them and 57% with them**, and
  the module goes 83.7% → 88.2%. What had blocked the decision was that the install cost had never
  been measured; measured, it is smaller than the tier it was being weighed against. A step in the
  `build` job rather than a job of its own, on the rule `packagingTest` set. **A release now downloads
  Chromium once on a machine with no browser cache** (280 MB, 94s) — a smaller demand than the Docker
  daemon the gate already makes, and a safe one now that a cold machine is slow rather than red.

- **The first ubuntu run settled the two things macOS could not** (run
  [`33998782676`](https://github.com/kangwoo/aimon-core/actions/runs/33998782676), all five jobs green).
  `--with-deps` is not needed — the runner image's own libraries are enough, and the log carries no
  `error while loading shared libraries` anywhere; the comment explaining the question stays, because
  the answer belongs to a runner image rather than to this build. The coverage floor also holds:
  CI measured this module at **714/810 line, identical to the laptop figure it was frozen from**,
  down to the per-class numbers, so the one exception to "measure on CI" in
  `coverage-baselines.properties` is gone rather than excused.

- **The cost this decision was made on was about three times the real one.** The Chromium download
  measured at 94s on a home connection took roughly **six seconds** on the runner, and the whole tier
  cost **32s cold** against the 1m48s measured locally. The decision does not change — it was wrong in
  the direction that makes gating easier to justify — but it is worth writing down that measuring a CI
  cost off a laptop was out by that much.

- **The browser cache is keyed on the Playwright version alone**, not on a hash of the version
  catalogue: the browsers rotate only when that line moves, and hashing the catalogue would discard a
  249 MiB entry on every unrelated dependency bump. `restore-keys` takes an older entry on a miss and
  the install task tops it up with just the new revision. `playwrightTest.exec` now travels with
  `test.exec` into the coverage hand-off — leaving it behind would measure the module with its browser
  tests excluded, the same mistake the `coverage` job exists to correct for the docker tier — and the
  module's floor moves **82 → 87**, which makes the wiring self-enforcing: the module cannot reach 87
  on the unit tier alone, so quietly dropping the tier out of CI fails the floor instead of passing.

- **No tier in this build is opt-in any more**, and **seven** pieces of prose said otherwise, including
  the module's own `build.gradle.kts` header calling it an "Opt-in task". The count was written as
  "three", then "five", then "six" — each time too low, and the last two after the counting rule had
  been written down; the enumeration and what each said are in the backlog item. Three further comments
  called `integrationTest` opt-in, which had been loose since before this work and is corrected with
  them. **One of the seven was missed and caught in review** — three lines below the gate declaration this change had just edited,
  in the one file a release operator reads to decide what has been verified, and
  `releaseSkillDescribesTheRealGate` stayed green throughout because its pattern reads the backticked
  task list and nothing else. So the fix is not only the sentence: a second, narrow check now fails
  when any line of that file calls a gated task opt-in. The `SKILL_GATE_DECLARATION` pattern itself was
  deliberately **not** widened — its javadoc records why it matches a fixed phrase rather than scanning
  loose prose, and that reasoning still holds.

- **`SKILL.md` now states the browser precondition** alongside the Docker one. The gate downloads
  ~280 MB of Chromium on a machine with no cache, which the script's own comment said loudly and the
  skill did not say at all.

- **The `/release` skill's description of the gate is now checked against the gate.**
  `ReleaseGateMatchesCiGateTest` held `scripts/release.sh` and `.github/workflows/build.yml` to each
  other while a third hand-maintained copy of the same list sat unread in
  `.claude/skills/release/SKILL.md` — telling whoever was about to publish that the gate was
  `checkAll` alone, and that `integrationTest` and `packagingTest` stayed out of it "in both places".
  Both had been inside both gates since the release that moved them there. That release corrected the
  test's own javadoc and missed the skill, so this is precisely the failure that javadoc names — a
  stale comment outliving the condition it described because no check read it — recurring in the one
  file the check did not read. A fourth case parses the skill's `Quality gate = …` claim and fails
  when it and the script name different tasks in either direction, and when the sentence is deleted
  rather than corrected.

- **The skill gained the steps cutting 0.2.4 actually needed**: promoting `[Unreleased]` to
  `[X.Y.Z]` before the release rather than after (the script only warns, and a step nobody wrote down
  is a step that gets walked past), the ordering constraint that makes that edit a separate pushed
  commit — `release.sh` wants a clean, in-sync `main` and stages only `gradle.properties` — the
  Docker daemon the gate now requires, and verifying the GitHub Release the tag push triggers, which
  runs after the Central publish and so fails without endangering anything or telling anyone.

- **`translation-staleness` now fails the build on a `source_commit` it cannot resolve.** The job had
  two findings sharing one exit code, and only one of them had an argument for it. A *stale*
  translation — the canonical moved on — is still reported and still passes, for the reason the script
  has always given: a translation backlog that blocks edits to the canonical makes the canonical go
  stale instead, which is the worse failure. That reasoning is about a backlog, and an *unresolvable*
  finding is not one. It is the guard having no opinion at all: absent front matter, a
  `translated_from` naming a canonical that is not there, or a `source_commit` that is not a commit in
  this history. Failing on it pressures nobody to skip a translation; it asks for a SHA that exists,
  which is one line and belongs to whoever wrote the file.

  Left sharing an exit code it hid the thing it was built to catch. The open-source history squash
  retired every pre-squash SHA at once and **19 of 32 translations went unresolvable** — 59% of the
  guard's subjects — and every run after it stayed green. Of the 19 annotations emitted, the
  check-runs API returns 10, and they hang off files no pull request touches, so the job's console was
  the only complete account and a green job gives nobody a reason to open it. Those 19 now carry
  `eec9ccd`, checked pair by pair against the canonical rather than rewritten on faith: a resolvable
  SHA on a translation that no longer matches reports green, which is worse than the silence it
  replaces. **A fork carrying translations with pre-squash SHAs will see this job go red**; point
  `source_commit` at the oldest commit that still holds the canonical in the state you translated.
  Unresolvable findings annotate as `::error` rather than `::warning`, matching the new severity.
  [`CONTRIBUTING.md`](CONTRIBUTING.md) and [`CLAUDE.md`](CLAUDE.md) carry the contributor-facing half.

- **The shallow-clone exemption reaches exactly as far as its own reason, and `--strict` changed with
  it.** A shallow clone makes every `source_commit` look absent, so those findings are reported
  without failing — CI passes `fetch-depth: 0` and never takes that path. The exemption is decided per
  finding rather than by one global flag: depth cannot delete front matter, move a canonical, or point
  `translated_from` outside the repository, so those fail at any depth. **`--strict` no longer fails on
  a clean shallow clone** (it did before, when it failed on any finding). It now covers staleness and
  nothing else — excusing a finding under one flag but not the other would make the flag, rather than
  the finding, decide whether depth counts as a defect. Unresolvable findings fail with or without it.
  The flag still has no caller here, and [`scripts/check-translation-staleness.py`](scripts/check-translation-staleness.py)
  records why it is deliberately not in the release gate.

- **Javalin 6.3.0 → 7.2.3, which is a code change and not only a version line.** Dependabot's bump
  ([#11](https://github.com/kangwoo/aimon-core/pull/11)) moved one line in
  `gradle/libs.versions.toml` and failed both gate jobs on the same compile error, because Javalin 7
  moved two things `RewakeWebhookServer` was using: `JavalinConfig.showJavalinBanner` is now
  `config.startup.showJavalinBanner`, and the HTTP verb methods are gone from `Javalin` itself —
  routing is declared through `config.routes` (`JavalinDefaultRoutingApi`) at create time rather than
  chained onto the instance afterwards. Three lines at the one call site; the module's 18 tests,
  including the two that start a real server, pass unchanged.

- **The blast radius is one module, and that was checked rather than assumed.** `aimon-rewake-webhook`
  is the only module that depends on Javalin and **nothing depends on it**, so the Jetty it drags in
  (12.1.12, `ee10-servlet`) meets no other pin in the build — there is no `jetty` entry in the version
  catalog at all. Javalin 7.2.3 is Java 17 bytecode, matching the toolchain. 7.2.1 is the release
  upstream marked unusable over a Jetty bug; this is the fix for it, not that.

- **The live-API test tier is documented as manual-only, because it has no CI signal and never
  did** (#81). Four classes call a provider's real API and gate on `ANTHROPIC_KEY` or `OPENAI_KEY`;
  no workflow supplies either key, so wherever the keys are absent, CI included, they skip and the
  build stays green. The issue offered two ways out — a scheduled workflow with the keys in repository
  secrets, or saying plainly that the tier is verified only when someone chooses to verify it — and
  **the maintainer chose the second**. [`CONTRIBUTING.md`](CONTRIBUTING.md) now carries the command,
  the cost, and the consequence that comes with the choice: rot in this tier is the steady state,
  found by whoever runs it next. `CONTRIBUTING.md` had also named `ANTHROPIC_API_KEY` as the key for
  end-to-end testing, a name no gate in the tree reads; it now says `ANTHROPIC_KEY`. The decision,
  the alternative it did not take, and what would reopen it are recorded in
  [`docs/backlog/live-api-test-tier.md`](docs/backlog/live-api-test-tier.md).

- **The obvious way to run that tier by hand ran nothing.** Gradle does not track environment
  variables as inputs to `test`, so exporting the keys after a keyless run and repeating the same
  command reported both test tasks `UP-TO-DATE` — green in 543ms with zero tests executed. It is the
  `playwrightTest` failure at the top of this section in a new form: a tier that executes nothing
  cannot be told apart from a passing one. The documented command passes `--rerun` to each task, which
  was measured to execute all four classes.

- **Two live assertions stopped pinning a vendor's sentence where their claim did not need one.**
  `AnthropicThinkingLiveTest`'s signature negative control asserted one 400 body verbatim while the
  server answers the same request with either of two — 3 and 3 in six back-to-back runs; it now
  asserts the error type and the path of the block it mutated, which both bodies carry.
  `OpenAIReasoningLiveTest` asserted OpenAI's Chat Completions refusal word for word; it no longer
  reads the message at all, and asserts the structured `type` and `param` of the SDK's
  `BadRequestException` instead — `invalid_request_error` and `reasoning_effort`, both measured as
  filled by the server — which no rewording of the message can change. The assertions that pin whole
  sentences on purpose — the two dialect rejections `AnthropicThinkingMode` quotes for operators to
  grep, and the `temperature` refusal whose exact wording is what separates it from a range error —
  are unchanged. `L-12` is closed.

- **An exported provider key opts that provider's live classes into every ordinary build, and
  `CONTRIBUTING.md` now says so** (#90). The environment variable is the tier's only gate — the four
  classes carry no tag, and `test` excludes only `docker` and `packaging` — so while `OPENAI_KEY` or
  `ANTHROPIC_KEY` is exported, `./gradlew test` and `checkAll` run that provider's live classes each time
  the module's `test` task executes, and those runs bill. Measured without a real key: with the variable
  set to a deliberately invalid value, the plain `:aimon-llm-openai:test` executed its 17 live tests and
  failed 16 on HTTP 401, and `:aimon-llm-anthropic:test` executed its 25 and failed 24 — both builds red.
  The same commands without the variable skipped all 42 and stayed green, and repeating a keyed command
  with nothing changed reported `UP-TO-DATE` and ran nothing. The documented command now puts the keys in
  front of itself instead of exporting them. `README.md`'s prerequisite still named the Anthropic key
  `ANTHROPIC_API_KEY`, which no gate reads; it now says `ANTHROPIC_KEY`. The docs site's CLI quickstart
  offered that same variable as an alternative the bundled configuration never reads; it now asks for the
  OpenAI key the configuration does read and points at the CLI reference. Line citations of the live
  classes in `docs/backlog/` that no longer landed — three of them already wrong in the commit that wrote
  them — now name the method or annotation, or the commit they were counted against. Documentation only.

### Docs CI: translations are now checked for shape, not only for age

- **New check `scripts/check-translation-structure.py`, wired as a second step of the CI job that
  was `translation-staleness` and is now `translations`.** The two checks answer different questions
  of the same 32 pairs -- is the translation *current*, and is it *complete* -- and a translation
  that sits at the same commit as its canonical while missing a section used to pass both of them
  green. `CLAUDE.md` and `CONTRIBUTING.md` have always required matching structure; until now the
  only thing enforcing it was a person.

  It compares six things that survive re-wrapping: headings and their level sequence, fences and
  their language sequence, table rows per table, list items and their nesting depth, quote *blocks*,
  and (advisory only) `#` comment lines inside fences. Line counts are used for nothing: Korean
  carries more per column, so all 32 pairs disagree on raw line count and one by 16%.

- **The job renamed from `translation-staleness` to `translations`.** `main` carries no branch
  protection and no rulesets, so no required-check name had to move with it.

- **A structure mismatch fails the build only when the pair is level with its canonical.** When the
  canonical has moved on, the mismatch is reported and the run still exits 0 -- failing there would
  hand a red build to whoever edited the canonical over a translation backlog, which is the pressure
  `check-translation-staleness.py` documents itself as existing to avoid. Doing it from a second
  script would have overturned that decision through a side door.

- **Translations may declare a per-axis exemption** with `structure_exempt` and
  `structure_exempt_reason` in their front matter. Both keys are required and both shapes are
  enforced, because mkdocs reads the same block with a YAML parser while the scripts read it with a
  line regex: a YAML list silently loses its second entry through one of them, and an unquoted
  reason containing a colon or a backtick makes mkdocs drop the metadata and publish it as page
  text with `mkdocs build --strict` still exiting 0. An exemption also expires -- once its axis
  matches again the check asks for the line to be deleted.

- **`scripts/docs_tree.py` gained `translations()`, `frontmatter()`, `canonical_of()` and
  `pair_state()`**, and `check-translation-staleness.py` now sits on them; its output and exit codes
  are unchanged on every branch. That module now runs git, which it did not before -- the cost is
  paid so that two checks cannot compute "is this pair current" differently, which is exactly the
  drift it was created to prevent.

- **`check-translation-structure.py --self-test`** runs in CI ahead of the check itself. It varies
  the *reading* while holding the corpus still: each wrong reading must still split at least one pair
  that the specified reading is happy with. On a corpus with nothing to catch, that is the only thing
  separating a check that measured and found nothing from one that quietly stopped measuring -- and
  because it counts the *difference* between two readings rather than an absolute, a pair that is
  behind its canonical or carries a legitimate exemption cancels out instead of failing the step.

### Documentation: the site's front page is now written for a first-time reader

- **`docs/README.md` is a landing page rather than a catalogue.** It was the index *and* the first
  screen of the published site, and the two jobs pulled in opposite directions: someone arriving at
  <https://kangwoo.github.io/aimon-core/> to find out what AIMON is met "checkboxes go in `plan/` only"
  and a table distinguishing `backlog/` from `design/backlog/`. The page now answers, in order, what
  AIMON is (next to what you would otherwise build yourself), how to run it — CLI in four commands,
  or three Spring properties and one injected `AimonSessions` — how a turn actually loops, what the
  seven names worth knowing mean, which modules exist, and where to go next. `docs/README.en.md`
  follows it.

- **The documentation rules moved to
  [`docs/project/documentation-guide.md`](docs/project/documentation-guide.md)** — where a new document
  goes, what separates `design/` from `plan/` from `backlog/`, the link rules, and the whole translation
  convention. Nothing was dropped in the move; the audience was. `CONTRIBUTING.md` (and its Korean
  translation), `CLAUDE.md` and `docs/design/README.md` now point there for those rules.

- **`backlog/` and `plan/` are no longer published to the docs site.** They were top-level entries in
  the site navigation, which offered a reader looking for "what can I use" a list of what is not done
  and a tracker that gets deleted when its work lands. `mkdocs.yml` excludes them through
  `exclude_docs`, and this is an exclusion rather than a deletion: both directories stay in the
  repository and stay readable on GitHub, and `scripts/mkdocs_github_links.py` now reads that same
  `exclude_docs` list — rather than repeating it — to rewrite links pointing into them into GitHub URLs
  at render time, exactly as it already did for links escaping `docs/`. Sources keep writing relative
  paths and `mkdocs build --strict` still passes.

  The patterns are anchored (`/backlog/`, `/plan/`). Written bare, gitignore semantics would match at
  any depth and take `design/backlog/` with them — deferred *design* rationale, which belongs with the
  rest of `design/` and is still published.

---

## [0.2.4] - 2026-09-04

This section covers everything since `[0.1.11]` — the 0.2.x releases in between shipped without
sections of their own.

Six themes:

1. **The session-first restructure (Stages 0–6)** — the durable aggregate is now `SessionRecord`
   (`SessionId`), the node-local handle is `LiveSession`, the relation is `1 : 0..N`, and a run with
   no session carries an `ExecutionId` instead of a fabricated `SessionId`.
2. **Assembly** — new `aimon-bom`, `aimon-bootstrap` and `aimon-spring-boot-starter`;
   `aimon-session-base` renamed to `aimon-session-routing` with its storage SPIs moved into `aimon-core`.
3. **Tool contract** — schema validation before `execute()`, `GenericTool<I, O>`, permission checks
   that see the call, and side-effect / destructiveness as separate declared axes.
4. **The starter's open-items register, worked end to end** — `docs/backlog/spring-boot-starter-open-items.md`
   went from 25 registered items to 34 registered / 4 open. What that produced is filed under the
   sections below by *what changed*, not by item number; the register keeps the reasoning.
5. **The `VirtualFileSystem` backend contract** — directory semantics, a per-file size cap and
   failure behaviour are now stated once and tested once (`aimon-filesystem-testkit`) instead of
   being whatever each backend happened to do. GridFS is the backend that moves furthest: it stops
   destroying the previous revision on a failed write, stops reading the whole bucket to answer a
   listing, and gets a usable default. Recorded in
   [`docs/design/filesystem/backend-contract.md`](docs/design/filesystem/backend-contract.md).
6. **Every `@Deprecated` in the repository is gone** — eleven symbols removed (one of them by first
   un-deprecating it, then building the replacement it lacked). This
   is the only theme here that breaks source compatibility against **published** coordinates (0.2.1)
   rather than against unreleased work; the removals are listed individually under
   [Breaking](#breaking).

Renames are Java-symbol only. **No wire format, DDL, channel name, key prefix or persisted field
changed anywhere in this block** — see [`docs/migration/frozen-names.md`](docs/migration/frozen-names.md).
The one adjacent value that did change is the GridFS **default** database name, which no deployment
can have stored anything under (MongoDB rejected it); it is filed under [Fixed](#fixed).
Old names are searchable in [`docs/migration/rename-maps.md`](docs/migration/rename-maps.md).

---

### Added

#### Modules

- **`aimon-bom`** — a `java-platform` publishing only `<dependencyManagement>`, so the other
  coordinates can be versionless. It manages AIMON's own coordinates and nothing else: Maven treats
  a BOM entry as an override, so pinning third-party versions here would silently replace a host
  application's Spring Boot-managed logback/lettuce/mongo. The constraint list is derived from the
  subprojects that actually publish; `verifyBom` cross-checks it against each module's
  `POM_ARTIFACT_ID` and runs before publishing (without `evaluationDependsOn` the derivation yields
  **zero** modules while the build still succeeds).

  ```kotlin
  implementation(platform("at.aimon.core:aimon-bom:0.2.0"))
  implementation("at.aimon.core:aimon-spring-boot-starter")
  ```

- **`aimon-memory-testkit`** — the shared five-tier `PeerMemory` contract suite, in a module of its own
  so every backend runs the same one. Not published, the same way `aimon-filesystem-testkit` and
  `aimon-session-testkit` are not, and by the same mechanism: no `aimon.publishable`, so `aimon-bom`
  leaves it out automatically. It depends on none of `aimon-memory-{file,mongodb,postgres}` — those
  implement stores, and stores are the default backend's materials rather than the seam.

  Three of its cases are about the capability model rather than any tier: a tier that is offered must
  answer (not throw `UnsupportedOperationException`), search results are ordered by relevance whether
  or not the backend can score, and a backend that cannot score must reject a positive `minScore`
  rather than ignore it. The first is the loophole the tier boundary cannot close by itself — hand
  the default backend a metadata-only observation store and its SEARCH tier exists and fails on every
  call — which is exactly why it is written down and executed rather than assumed.

- **`aimon-bootstrap`** (`at.aimon.bootstrap`, no Spring) — `AimonStack.from(spec)` replaces copying
  the CLI's 216-line `AgentSetupFactory.create()`. Input is one immutable `AimonStackSpec`
  (`LlmSpec`, `AgentSpec`, `FileSystemSpec`, `SessionSpec`, `SkillApprovalSpec`, `ToolSpec`,
  `ExecutorSpec`, `SchedulingSpec`); output publishes the executor, runtime, session router, record
  store, message-queue manager and scheduling engine plus a `HealthReport`. Teardown is the part
  worth having: `TeardownPhase` is a 16-constant enum whose **declaration order is the shutdown
  order**, `close()` runs every entry even when one throws and is idempotent, and an embedder adds
  its own resources with `own(phase, label, resource)`. One agent per stack — the builder rejects a
  second, because `SkillPreflightScanner` is one scanner per skill registry.

- **`aimon-spring-boot-starter`** — Spring Boot 3.5 autoconfiguration over `aimon-bootstrap`. Set
  `aimon.workspace.root`, `aimon.llm.api-key` and `aimon.agent-defaults.default-agent`, inject
  `AimonSessions`, call `submit(sessionId, input)`. Slices contribute *materials*, never components:
  the session slice leaves `SessionSpec.recordStore` empty on the in-memory default rather than
  publishing a bean, which would satisfy the builder's check and erase the `session-durability`
  degradation. Two beans are closed by Spring — the stack and the LLM client, whose pool the stack
  borrows and never closes; ordering comes from making the spec take the client as a direct
  constructor parameter.

  **Server defaults differ from the CLI's, deliberately**: budgets are finite
  (`max-iterations: 20`, `max-tokens: 100000`, `max-wall-clock: 120s`) where the CLI is unlimited,
  and `BashTool` is off (`aimon.tools.bash.enabled`). The budget prefix is `aimon.budget.*`.
  `aimon.enabled=false` publishes a facade that throws `AimonDisabledException` rather than removing
  the bean; `aimon.fail-fast` defaults to `false` because three documented server defaults each
  register a degradation on purpose. Vendor modules are `compileOnly` behind `@ConditionalOnClass`.
  Scope is single node, in-memory sessions, synchronous turns, one agent.

- **`aimon-filesystem-testkit`** — `AbstractVirtualFileSystemContractTest`, the one description of the
  `VirtualFileSystem` contract that every backend is checked against. A subclass supplies
  `newFileSystem()` and inherits the whole suite; `LocalFileSystem`, `ScopedVirtualFileSystem` and
  `GridFSFileSystem` subclass it today. Deliberately **not published** — it carries no
  `aimon.publishable`, which also keeps it out of `aimon-bom`'s derived constraint list the same way
  the samples stay out.

  It is a normal module rather than `java-test-fixtures` on `aimon-core` because the fixtures plugin
  and `com.vanniktech.maven.publish` 0.30.0 do not coexist on Gradle 9
  (`NoSuchMethodError: ProjectDerivedCapability.<init>(Project, String)` at configuration time), and
  upgrading the publishing plugin to dodge that is a release-pipeline change, not a test-plumbing one.
  `aimon-core` then depends on it at `testImplementation` scope, which points back at a module that
  depends on `aimon-core`: not a cycle, because only *main* source sets have to be acyclic
  (`aimon-core:test` → `aimon-filesystem-testkit:main` → `aimon-core:main`).

  The cap cases are a `default` method returning `null` rather than an abstract one, so a backend that
  has not implemented `maxFileSize` yet still compiles against the suite; the suite then calls
  `assumeTrue`, so those cases report as **skipped**. A silent pass would have been the failure mode
  worth avoiding — the report has to be able to say "not checked here".

- **`aimon-session-testkit`** — `AbstractMultiNodeSessionContractTest`, the seven cross-node scenarios
  every session backend has to satisfy: which node owns a turn, where a message goes when the other
  node holds the lock, whether an interrupt reaches the holder, what the survivor says when the holder
  dies mid-turn. Redis, Postgres and MongoDB each had their own copy — 2,196 lines across nine files,
  with the seven method names identical down to the letter and `RecordingTestSession` byte-identical
  in all three but for a javadoc paragraph in each promising it mirrored the others. That comment is
  what a duplicate writes when it has no way to prove the claim. Now 1,282 lines, and the scenarios
  exist once.

  A backend joins by implementing `SessionBackendFactory`: reset the container, build one node's four
  SPIs (`SessionBackend`), and build a lone lease store / inbox / idempotency store for the three
  scenarios that deliberately bypass the router to reach an SPI directly. Every method takes a
  `Consumer<AutoCloseable>` sink instead of returning something closeable, because the resources behind
  those SPIs differ per backend and outnumber them — three Lettuce connections, two Hikari pools, one
  Mongo client — and the harness should not have to know how many there were.

  The waits are three overridable windows (`settle`, `propagationTimeout`, `holderLossTimeout`)
  defaulting to the Redis values, which a backend may only widen. Consolidating them surfaced that the
  old suites used **two different settle values inside the same backend** — Postgres 200ms and 300ms,
  MongoDB 500ms and 300ms, inverted between the two scenarios — with nothing to justify the difference;
  the wider of each pair won, so no scenario now waits less than it used to. Not published, for the
  same reasons as the filesystem testkit above.

#### Starter — the surfaces an application needed on day one

- **`aimon.credentials.<profile>.<field>`** binds, becomes the stack's shared `CredentialStore`, and
  reaches the tools that resolve a `profile.field` reference. Of the starter's four day-one extension
  points this is the only one that is *data* rather than code, which is why it is the only one that
  becomes a property — a bean would make the profile list a compile-time decision and `${JIRA_PASSWORD}`
  would stop arriving from the environment, a config server or Vault for free. The plural is
  load-bearing: the actuator function below tests the **whole key** and carries `credentials` in its
  word list, so every leaf under this prefix is masked whatever it is called (`username`, `pat`,
  `client-id`); spelled `aimon.credential.*` none of them would be. Profile and field names may not
  contain a dot (`TypeActionHandler.resolveCredentialRef` requires exactly one, so a dotted name would
  bind and then be permanently unreferenceable); a profile with no fields is refused; properties, a
  `CredentialStore` bean and a `CredentialStoreFactory` bean are alternatives and any two refuse each
  other at startup — bean-beats-properties is the conventional resolution and the wrong one here,
  because what loses is a secret and the loss is invisible until a tool reports a missing credential
  hours later. An empty tree leaves the spec's store unset rather than installing an empty one.
- **`AimonRuntimeHints`** (`@ImportRuntimeHints` from `AimonAutoConfiguration`) declares the three
  things AIMON reaches without a compile-time reference, each of which fails *quietly* in a native
  image — the agent starts and then has no skills, or no scheduler, or an empty todo list. Bundle
  resources are the single pattern `agents/*` rather than the five known shapes, because a skill
  directory also carries payload files whose names no constant knows and Spring's
  `ResourcePatternHint#toRegex()` maps `*` to `.*`, crossing separators. `Todo` / `TodoStatus` go
  through Spring's `BindingReflectionHintsRegistrar`; Quartz jobs are declared by binary name through
  `registerTypeIfPresent`, so a class path without `aimon-scheduling-quartz` writes no dangling hint.
  Resource **enumeration** stays unhintable and the registrar says so — see the scope note below.
  `AimonRuntimeHintsTest` verifies the declaration (not an image), and a drift guard runs the real
  `AdaptiveAgentBundleLoader` against a fixture bundle through a recording class loader, so a sixth
  shape added in core fails here rather than in someone's image.
- **A durable `ScheduledTaskRepository` can reach the stack.** `SchedulingSpec.withTaskRepository(...)`,
  passed through `AimonStackBuilder` to the scheduling engine, and the starter picks up a
  `ScheduledTaskRepository` bean. `SchedulingEngineBuilder.taskRepository(...)` had accepted one all
  along; nothing between an application and that builder passed it, so using a durable implementation
  meant hand-building the engine and giving up `AimonStack`'s ordered teardown. The starter takes a
  bean and still declines to offer a property — a property would put a durable-sounding switch over
  machinery that does not exist. No rehydration step is added: under Quartz with a JDBC job store
  there is none to add (`DelegatingJob` stores only the task id and re-reads at fire time), and the
  in-memory scheduler's re-scheduling pass belongs to the scheduler, not to this seam.
- **The approval axis' four stores can be supplied.** `SkillApprovalSpec.withAgentApprovalStore(...)`
  / `withSessionApprovalStore(...)` / `withPendingTurnRegistry(...)` and
  `AimonStackSpec.Builder.messageQueueRepository(...)`, passed through `AimonStackBuilder`, with the
  starter picking up a bean of each. Same gap `withTaskRepository` closed on the scheduling axis: the
  SPIs existed and every distributed implementation of them was unreachable from an assembled stack,
  because the builder constructed the four in-memory ones itself. The queue lands on `AimonStackSpec`
  rather than beside the three approval stores because it is not keyed by a session — `QueuedInput`
  carries an `AgentRuntimeId`, and the ReAct loop, the live session and the subagent manager all read
  it. That is also why sharing the queue is **not** the same move as sharing the three: the drain filters
  on `AgentRuntimeId` alone and a queued entry carries no `SessionId`, so on a shared repository the next
  node to run a turn for that agent runtime takes the input — possibly into another session's turn. All
  four are borrowed: the stack closes none of them.
- **The starter registers the destruction edge Spring does not.** A `@Bean` parameter records "this bean
  depends on that one"; an `ObjectProvider`'s `getIfAvailable()` records nothing, and every optional
  contribution the starter resolves went through the latter. The order was still right — each is created
  while the bean that gathers it is, therefore before the stack — but right by accident, and a store backed
  by a connection would have been closable before the stack still writing to it. `ApplicationBeans` now
  registers the edge on every slice that gathers one: the approval axis, the five session SPIs, the
  filesystem, the scheduling SPIs and the borrowed Quartz `Scheduler`, knowledge and memory. The
  connection-holding ones were never in the first group, and the Quartz branch had written the mistaken
  reason down independently — the helper is what makes "did this get an edge?" answerable at the call site.
  It matches a `FactoryBean`'s **product**, not the factory sitting in the singleton cache: the borrowed
  Quartz `Scheduler` is published by `spring-boot-starter-quartz` through a `SchedulerFactoryBean`, so the
  one entry named as mattering most was the one the first version silently skipped.
- **A `PendingTurnRegistry` the stack cannot reach now fails the context** instead of diverging in silence.
  It is the one type this starter both publishes and accepts, and the two halves are resolved by different
  means, which can disagree: a bean the application names `aimonPendingTurnRegistry` is skipped as the
  re-export while `@ConditionalOnMissingBean` withdraws the real one, so the application injects one
  registry and the stack suspends into another and `/approve` finds nothing. The check is written against
  the disagreement rather than against that cause — *what `getBean(PendingTurnRegistry.class)` returns is
  what the stack suspends into* — so `@Primary` and the refusal to guess stay Spring's answers rather than a
  second copy of them. That paid off immediately: there is a second route in, and it is an
  application-supplied `AimonStackSpec` or `AimonStack` bean (both `@ConditionalOnMissingBean`), where it is
  no longer this starter's spec factory that applies a published registry. The message asks which shape it
  is in before naming a cause, because that one arrives with the re-export's name still free and "rename
  your bean" would be advice about a bean the application does not have. This is the one `@Bean` in the
  starter without `@ConditionalOnMissingBean`: it asserts an invariant about the beans a host supplied
  rather than being a collaborator a host replaces.
- **`distributed-approvals` now names only what is still node-local**, and says nothing when all three
  approval-axis stores were supplied. It had been unconditional on `DeploymentMode.DISTRIBUTED`, which
  was always true while there was no way to supply them and becomes a lie the moment there is. The
  half-configured shapes are named individually because they fail differently: a shared pending-turn
  registry over node-local approval stores finds a suspended turn from another node and then releases
  it into a node with no record of the decision. Narrowing it also retired a claim `ToolApprovalStore`'s
  javadoc had been making — that this degradation reported "the whole category" of approval storage rather
  than repeating it per store. It never covered that store (nothing assembles one) and now visibly counts
  three, so the javadoc says what is true and what an assembly that wires it would owe it.
- **`aimon.agent.runtimes.leased`** — a read-through gauge over the new
  `AgentRuntimeResolver.leasedCount()`, reporting the subset of `trackedCount()` a caller is holding
  right now. The difference between the two is the number of runtimes alive on the idle TTL alone,
  which is what tells "the cap is what to raise" from "the TTL is what to shorten" — two opposite
  situations that `.active` / `.saturated` reported identically. A lease is held for as long as a
  `LiveSession` handle exists, so `.leased` does not mean "running a turn"; the two idle timers are in
  series (cache eviction closes the session, which releases the lease, which starts the runtime's TTL)
  and both default to 30 minutes, so a runtime is not reclaimable until an hour after its last turn.

#### Tools

- **`GenericTool<I, O>`** (`at.aimon.core.agent.tool.generic`) — an opt-in base class beside
  `AbstractTool`. The input is a `record` whose components carry `@ToolParam`; `ToolSchemaGenerator`
  derives the JSON Schema from it (including `additionalProperties: false`, recursively) and
  `ToolInputBinder` binds calls against the same declaration, so schema and extraction cannot
  disagree. Subclasses implement `doExecute(I, ToolContext)` and `render(O)`; `execute` is `final`.
  Wire names are declared, never converted (`Grep` keeps `-i`, `-A`, `-B`).

  **Convention exception**: these input DTOs are the one place the project's "prefer `class` over
  `record`" rule does not apply (`.claude/rules/code-style.md`).

- **Schema validation before the tool runs** (`at.aimon.core.agent.tool.schema`). `DefaultToolExecutor`
  checks four things and no more — `required` presence, declared `type`, `enum` membership, and
  undeclared parameter names — one level into nesting. Ranges (`minimum`, `maxLength`, `minItems`,
  `default`) are deliberately not checked, because a tool may legitimately *clamp* rather than reject
  as `BashTool` does with `timeout`: shape belongs to the gate, ranges belong to the tool.
  Unrecognized constructs (`$ref`, `oneOf`, an unknown type name) pass untouched so no third-party MCP
  schema breaks.

  Ships as `WARN` (log and run); `ENFORCE` returns violations to the model, `OFF` disables.
  `OrcaAgentExecutorFactory.withSchemaValidationMode(ENFORCE)`, or
  `new DefaultToolExecutionManager(mode[, ceiling])`. Unknown-name detection needs the schema to say
  it is closed: every built-in tool now declares `"additionalProperties", false` at top level, pinned
  by `BuiltInToolSchemaArchitectureTest` (no exclusion list, scope `at.aimon.core.tools`, top-level
  map only). `schedule_task` and the memory deriver's loop stay outside the gate on purpose, and both
  descriptions were corrected to stop promising validation that never happened.

- **`DestructiveBehavior`** (`NON_DESTRUCTIVE` / `DESTRUCTIVE`, `at.aimon.core.agent.tool`) with
  `Tool#getDestructiveBehavior()` defaulting to `DESTRUCTIVE`, so no existing tool changes by not
  being edited. Unordered, hence `*Behavior`: deleting a volume is not *more* side-effecting than
  appending a log line, it is *differently* so. Read only when the tool declares `MUTATING`.
  `SideEffectApprovalGate` gains the rule the axis exists for — **a `DESTRUCTIVE` tool is asked about
  regardless of the exemption line**, so no setting disables the gate. Until in-tree tools are
  audited, `exemptAtOrBelow = MUTATING` therefore exempts nothing; the gate is opt-in and nothing in
  production constructs one, so this lands when it is wired.

- **MCP annotations are no longer discarded.** `McpToolAnnotations` (the four hints as sent, each
  absent-able, MCP defaults applied on read), `McpToolSchema#getAnnotations()`, `McpToolTraits` (the
  single place trust is applied), `McpServerConfig.AnnotationTrust` = `IGNORE` (default) or `TRUST`
  per server, and the CLI `McpServerEntry.annotationTrust` YAML key. A malformed hint stays *absent*
  rather than becoming `false`. Default `IGNORE` leaves every MCP tool at `MUTATING` + `DESTRUCTIVE`.

- **`PermissionSubject` / `ToolPermissionSubjectAware`** — a tool declares its permission target as a
  `COMMAND` (matched by `ToolPattern`) or a `PATH` (matched by the new `PathPattern`, a glob where
  `**` crosses directories and `*` does not). The kind cannot be recovered from the spec string, so
  the tool supplies it. `BashTool` takes this path and `BashToolPermissionRule` is deleted; the file
  tools declare `PATH` subjects through `FilePathSubjects`, resolving relatives against the
  `Environment` working directory and normalizing lexically (never resolving symlinks — a pattern
  narrows what was asked for, isolation is the sandbox's job). `CustomToolPermissionAware` remains
  for decisions that are not one value (`BrowserToolPermissionRule`'s `action:url`); a tool
  implementing both is judged by its subject first.

- **`SkillToolDispatcher`** — bound by `OrcaAgentExecutor.executeCommand` under
  `ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY`, backed by the same `SingleToolInvoker` the ReAct loop
  uses.

  ```java
  public interface SkillToolDispatcher {
      List<ToolUseResult> dispatch(ToolRegistry toolRegistry, ToolContext toolContext, List<ToolUse> toolUses,
              List<AllowedTool> allowedTools, int iterationCount);
  }
  ```

#### Session

- **`LiveSession.retryLastTurn(...)`** — takes a turn that ended `INTERRUPTED` back out of the history
  and runs it again. Retrying is not the same as asking again, which is the whole of why this is not
  a one-line resubmission: a stopped turn leaves a trail — the user message, the synthetic context
  blocks injected ahead of it, the assistant output produced before the stop, the tool results filled
  in as skipped — and submitting the same request on top of that would ask the model to redo work in
  a history that says it already half-did it. The trail is kept until someone asks to retry, because
  the user saw it happen, and removed at that point.

  Finding where to cut needed a **turn boundary in the transcript**, because the obvious heuristic is
  wrong here: `checkOnStartHooks` appends its hook advisory as a user message and CTX-06 injects the
  assembled context the same way, so the last user-role message is not the start of the turn. The new
  `SessionRewindPoint` records the message count before the turn began and the `UserInput` that started
  it — the latter held whole rather than looked up by index, for that same reason. It keeps the
  **request**, not the `Message` the executor built from it: that conversion cannot be run backwards
  (an image has no text of its own and reads back as `[Image: image/png, 1024 bytes]`), so a retry that
  recovered its input from the transcript could only ever resubmit a description of the request.

  It lives **inside `SessionTranscript`**, not beside it as a record side field. The count indexes the
  message list, so it must be replaced whenever those messages are; as a side field restored by
  `mergeFromSnapshot` it would survive a compaction and point into a history that no longer exists.
  Living in the transcript also means it rides the existing `SessionSnapshot` → `mergeFromSnapshot`
  path, so no store SPI gained a method and the three distributed backends inherited it through
  `SessionRecordCodec` without a line of change.

  Being persisted is what makes the feature worth having: an interrupt is exactly when a process is
  most likely to go away — a SIGINT, an idle eviction, a node handing the session over — so a retry
  that only worked while the original handle lived would fail precisely when it was wanted. There is
  deliberately **no "can I retry?" predicate**: the answer can change between the check and the act,
  so an empty `Optional` is the answer instead. `SessionRecordView.getRewindPoint()` and
  `SessionTranscript.rewind()` are the pieces underneath, both defaulted or additive.

  Two entry points, because callers differ on the submission rather than on the rewind:
  `retryLastTurn(...)` runs a plain synchronous turn, and `rewindLastTurn()` performs the rewind and
  hands back the input so a caller can submit it its own way. The CLI's new **`/retry`** takes the
  second, because a retried turn needs the streaming listener and — above all — the Ctrl+C handler the
  REPL binds around an ordinary turn. It is the turn the user just stopped, so being unable to stop it
  a second time would lose that at the worst possible moment.

  Two things the rewind point has to lose to. **Compaction wins**: `replaceWith` can leave far fewer
  messages than there were, so the recorded count stops being a position in the transcript and the
  point is dropped with the history it counted. Keeping it would not merely rewind to the wrong place
  — the count is validated where the transcript is rebuilt, so the end-of-turn persist would throw
  into `saveSilently`, which swallows it, and the whole turn's history would vanish without a word.
  **A running turn wins too**: rewinding under one cannot work, because that turn writes its own copy
  back when it ends, so `DefaultLiveSession` refuses rather than performing a rewind that is silently
  undone.

  The JSON snapshot codec writes `rewindPoint` only when there is one, so a session with nothing to
  retry encodes exactly as before, and a document written without the field decodes as not retryable.
  A count that does not fit the messages it arrived with is refused at decode: the pair is written by
  one writer into one document, so a mismatch means corruption, and materialising a snapshot whose
  retry would misbehave later is worse than saying so. The point's input is written under `userInput`
  with its own type tags — `file` and `multimodal` have no content-block counterpart.

  The input half of that pair is treated the opposite way, and the asymmetry is the point. A count that
  does not fit describes the transcript itself, so it means the document is inconsistent. An input this
  build cannot decode — written under an older field name, or tagged with a type a later build added —
  costs exactly one turn's retry, whereas throwing would cost the whole record: every backend turns a
  decode failure into a `SessionRecordStoreException`, so the session could not be opened at all. Such a
  point therefore decodes as **absent**, which is both the smaller answer and the true one. Nesting is
  bounded at 32 levels so a pathological `multimodal` document is refused rather than overflowing the
  stack.

- **`LiveSession` submits `UserInput`, not just `String`.** `submit`, `submitAsync` and `offerAsync`
  gained `UserInput` overloads. Before this, a multimodal turn could not be *started* through the session
  facade at all — `AgentExecutionRequest` has always carried a `UserInput` and the facade was narrowing
  it to text on the way in — which is also why `retryLastTurn` could not replay one.

  The three `String` methods stay **abstract**, and the `UserInput` overloads default on top of them: a
  `TextInput` is unwrapped and handed down, anything else throws `UnsupportedOperationException`. Both
  halves are load-bearing. Making all three default instead would have left `LiveSession` with two
  abstract methods (`getSessionId`, `close`), so a session implementing no submit path at all would
  compile and fail at runtime. And routing text down is what keeps `retryLastTurn` working on a session
  that only implements the `String` overloads — the rewind point is always a `UserInput` now, so without
  it such a session would refuse to retry even its own plain-text turns. What is *not* done is flattening:
  an image submitted as its `asText()` placeholder would be a turn asking about a sentence describing a
  picture. `LiveSessionUserInputTest` pins all three behaviours.

  **The queue stays a text channel, so it can refuse.** A deferred input is replayed as a
  `<system-reminder>` block (`QueuedInput.getInputText()`), which nothing but text fits into. Offering a
  non-text input while a turn is running therefore has no correct answer available — it cannot be
  deferred, and running it would hand two turns the same transcript, which is what the busy flag exists
  to prevent — so it throws `IllegalStateException` rather than picking the quiet wrong one. A caller
  that means to run turns concurrently has `submitAsync`. Nothing in the repository reaches this: the
  REPL runs one turn at a time, and a retry cannot start while a turn is running because the rewind
  refuses first.

  `rewindLastTurn()` returns `Optional<RewoundTurn>` rather than `Optional<String>`, and no longer refuses
  a turn whose request carried no text. This is a source-breaking change against unreleased work only —
  the method was added earlier in this same block.

- **A retry runs under the options the turn was submitted with.** The rewind point keeps the turn's
  `SubmitOptions` beside its input, because a turn is not only what was asked but who asked and in what
  context. Dropping them was the same defect as replaying an image as its placeholder, and quieter: the
  principal reaches tool context (`ToolContextKeys.PRINCIPAL`) and the memory request, so a retry
  submitted as nobody ran the same words as a different caller against differently assembled context.
  The no-argument `retryLastTurn()` now reuses the originals and `retryLastTurn(options)` replaces them,
  which is what that overload is for; `rewindLastTurn()` hands back both halves as a `RewoundTurn` so
  neither can be picked up without the other.

  The options are carried to the rewind point on the request itself (`OrcaAgentExecutionRequest`
  `getSubmitOptions()`), not reconstructed from the fields they were flattened into. By then defaults
  have been applied, so "never named" is indistinguishable from "named with the default" — an
  `llmCallMetadata` that was meant to be re-derived per turn would come back pinned to one component and
  trace id. That field is read by nothing that executes the turn.

  Persisting them reused an encoding that already existed **three times over**: the Redis, Postgres and
  Mongo inbox codecs each hand-map `SubmitOptions`. Rather than write a fourth, the mapping is now
  `SubmitOptionsCodec`, with field names and shapes identical to what those backends already write — so
  nothing on the wire changed and converging the two Jackson-based copies onto it is a deletion. Mongo's
  is BSON and needs a separate decision; both are registered in
  [`docs/backlog/interrupt-open-items.md`](docs/backlog/interrupt-open-items.md).

- **The interrupt work's open items have a register.** `docs/backlog/interrupt-open-items.md` is now the
  authority on what is left, per the rule `docs/backlog/README.md` states — a design document's list is
  the reasoning for deferring, frozen at design time, not the current state. `interrupt.md` §14 says so
  and points at it.


- **`TurnId`** (`at.aimon.core.agent.session`) — addressing for one turn, non-persisted. New
  overloads `LiveSession.submitAsync(TurnId, …)` and `SessionRouter.interrupt(SessionId, TurnId,
  InterruptReason)` (abstract on purpose, not a `default`), `SubmitDisposition.getTurnId()`
  (mandatory), optional `InboundMessage.getTurnId()`, a `turn` key on every `EVENT` signal frame.
  A **missing** turn id keeps its old meaning — interrupt is live-session-scoped, an event is
  delivered session-wide — rather than reading as "unknown → drop", so a rolling upgrade is safe.
- **`SignalKind.TURN_RESULT`** — the holder's terminal answer for a turn it ran on behalf of another
  node, plus `StoredAgentExecutionResult` as its wire projection, a doorbell drain pass on every
  `MESSAGE_ENQUEUED`, and a polling fallback armed for every forwarded submit.
- **`SignalKind.YIELD`** and **`HOLDER_LOST`** — a peer asking the holder to hand a session over, and
  the reason a turn fails when its lease is gone. Holder loss is published to every subscriber, not
  just the loser, and is *not* spelled `EVICT`: eviction is a lifecycle event, holder loss is a
  failure.
- **`SessionStore`** (`at.aimon.core.agent.session.store`) — one door to a session. `claim` performs
  lease election → agent-binding validation → record provisioning *in that order*, so a node that
  loses election never touches the record and no distributed transaction is needed; `acquire` is the
  delete path's variant; `records()` hands back a `SessionRecordStore` view fenced by the lease this
  node holds, so callers do not thread a fencing token through the ReAct call chain. Node-scoped —
  two managers in one JVM need two stores over the same two backends.
- **`SessionRecordStore.setTotalsAndBudgetOverride(...)`** — the single atomic primitive a live
  session uses to write back its two durable side fields. **Absolute values, not deltas**, so a
  duplicate call cannot double-count a turn; a missing record is a no-op.
- **`SessionCheckpointMailbox`** — single-writer persistence for transcript checkpoints.
- **`ExecutionId`** (`at.aimon.core.agent`) — correlation id for a run that has no session
  (subagent fork, skill fork, rewake replay, scheduled routine). Four commitments in its javadoc:
  node-local, never persisted, grants no lease, **never forwarded** — the exact inverse of
  `INVOKING_SESSION_ID`. `of(String)`, `generate()`, `generate(prefix)` → `<prefix>:<uuid>`.
  Companions: `ToolContextKeys.EXECUTION_ID` (string `"executionId"`, new so unfrozen — for
  correlation, deliberately **not** an authorization input), `SkillHookEnv.AIMON_EXECUTION_ID`,
  `executionId` on `OnSessionStartContext` / `OnSessionEndContext` / `PreCompactContext`, and the
  `${AIMON_EXECUTION_ID}` render variable. `AIMON_SESSION_ID` and `AIMON_EXECUTION_ID` are an
  **exclusive pair**: both always present, exactly one carries a value, neither falls back to the
  other.
- **`IdempotencyStore.releaseHolder(key, expectedHolderId, ttl)`** and
  `SessionRouterBuilder.idempotencyForwardTtl(Duration)`.
- **`SessionRouterBuilder.statusHeartbeatInterval(Duration)`** plus a dedicated single-thread lease
  scheduler; `SessionRouterConfig.build()` validates lease timings instead of letting an unrenewable
  lease ship.

#### Hooks — Phase 3 (closed)

- `HookResult.Status` is `ALLOW | ASK | DENY | MODIFY | …`; construct from `HookResult.allow()` /
  `deny(reason)` / `ask(...)` / `modifyInput(...)`. The legacy `success()` / `block()` factories still
  work and map onto the new model. `HookResult.merge(...)` resolves `DENY > ASK > MODIFY > ALLOW`.
- **Dispatch defaults to `PARALLEL`** — hooks depending on an earlier hook's side effects must opt
  into `ExecutionMode.SEQUENTIAL`. `HookExecutionPolicy.stopOnBlocked` now governs whether a parallel
  batch short-circuits on the first `DENY`. Per-hook `timeout` + `TimeoutBehavior(FAIL_OPEN |
  FAIL_CLOSED)`.
- New non-blocking events: `OnPermissionDecisionHook`, `OnSubagentStartHook`, `OnSubagentStopHook`,
  `OnSessionStartHook`, `OnSessionStopHook`, `OnConfigReloadHook`.
- **`hooks.json` hot reload (CLI)** — watches `~/.aimon/hooks.json`, `<project>/.aimon/hooks.json`,
  `<project>/.aimon/hooks.local.json`; SKILL frontmatter is not hot-reloaded. Edit → event ≤ 2 s
  (poll 1 s, debounce 2 s). The swap is transactional and never touches programmatically registered
  hooks. Wiring helper: `at.aimon.core.config.hook.HookHotReloadBootstrap`. No `hooks.json` schema
  change; implementing `Tool` requires no changes.

#### Filesystem

- **A per-file size cap is part of the `VirtualFileSystem` contract, not a `LocalFileSystem` feature.**
  The interface states it (`NO_MAX_FILE_SIZE = -1`, rejection is `InsufficientStorageException`,
  the cap is measured on **bytes actually read** rather than on a declared length) and all three
  configuration objects carry `maxFileSize`: `LocalFileSystemConfig` already did, `GridFSConfig` and
  `S3Config` now do. There is deliberately **no `getMaxFileSize()` on the interface** — the cap is a
  backend's configuration, and the contract is about what a caller observes when a write crosses it.
  `0` is a cap of zero bytes, not "unlimited"; only `-1` disables.

  Both write paths are covered — the bulk `write(...)` and the stream from `openOutputStream(...)` —
  because a cap enforced on one of them is a cap a caller can walk around by choosing the other. The
  cap is checked as `additional > maxBytes - written`, which cannot overflow the way `written +
  additional > maxBytes` can.

  **What survives a refusal differs per backend, and that is documented rather than smoothed over**:
  GridFS aborts the upload so neither chunks nor a file entry remain and the previous revision still
  stands; S3 refuses before any request is sent; `LocalFileSystem`'s bulk path deletes the target,
  while its streaming path leaves the accepted prefix on disk. The shared contract test asserts only
  what all three guarantee — never more than the cap is stored — and each backend's own test pins
  the stronger promise it actually makes.

- **`SizeLimitedOutputStream` moved to `at.aimon.core.filesystem`** (from
  `at.aimon.core.filesystem.impl.local`). It is the single enforcement point for the cap on every
  streaming path, so every backend has to reach it — and `…filesystem.impl..` is precisely the tree
  nothing outside the filesystem packages may import
  (`PackageDependencyArchitectureTest.filesystemImplMustNotLeakOutsideFilesystemTree`). A shared
  enforcement point in a package other backends are forbidden to name would have been three copies
  of the same arithmetic. A subclass hook, `onLimitExceeded()`, lets a backend reclaim what it had
  already started — GridFS aborts its upload there.

- **`VirtualFileSystem.getUsageSummary(String path)`** — usage under one subtree, so a quota can be
  read per workspace rather than per bucket. A `default` that ignores the path and delegates to the
  whole-backend summary, since over-reporting is the safe direction for a quota check; GridFS
  overrides it with a server-side `$group`.

- **`GridFSFileSystem(GridFSConfig, MongoClient)` and `GridFSConfig.forSharedClient(...)`** — the
  backend can now run on a `MongoClient` the host already has. The two constructors differ only in
  `ownsClient`, which is what `close()` consults: a borrowed client is left open. The live client
  stays *out* of the configuration object on purpose — a value object that is compared, hashed and
  printed has no business holding a connection pool.

#### Scheduling

- **A scheduled task's run can now be stopped, and cancelling one actually stops it.** Unscheduling
  only ever governed *future* fires, so `ScheduledTaskManager.cancel` left a routine that was
  mid-step to finish its remaining steps — writing files, calling out to systems — on behalf of a
  task it had just deleted. `RoutineExecutor` now owns a per-run `InterruptCoordinator`, exactly as a
  session's turn does, and `cancel` trips it. The propagation ladder is the framework's existing one
  ([`docs/design/agent-execution/interrupt.md`](docs/design/agent-execution/interrupt.md) §12):
  the **step boundary** stops the run unconditionally, `COOPERATIVE` steps get the signal on their
  `ToolContext` and can return early, and `THREAD_INTERRUPT` / `EXTERNALLY_TERMINATED` steps are
  terminated where they stand. The retry backoff is part of it — it waits on the signal instead of
  sleeping, since a delay configured in minutes is otherwise the difference between a run that stops
  and one that stops eventually. `shutdown()` trips everything in flight before draining the pool,
  for the same reason: a step that has not been told to stop cannot.

  | New | What it is |
  |---|---|
  | `ScheduledTaskManager.interrupt(taskId, principal)` | Stops the run, leaves the schedule alone — the narrower half of `cancel`, and the project's own distinction between 중단 and 취소 |
  | `RoutineExecutor.interrupt(taskId, reason)` / `isRunning(taskId)` | The control plane underneath, node-local |
  | `InterruptReason.TASK_CANCELLED` | Not `PARENT_CANCELLED`: nothing cascaded, the request named this run's own task |
  | `RoutineResult.cancelled(...)` / `isCancelled()` / `getInterruptReason()` | A third way for a run to end |
  | `ScheduledTaskExecutionHistory.Status.CANCELLED` | Read **before** the step counts, so a stopped run is not filed as `PARTIAL` |
  | `TaskInterruptedEvent` + `ScheduledTaskEventListener.onTaskInterrupted` | One *run* stopped — distinct from `TaskCancelledEvent`, which says the *schedule* is gone |

  An interrupt landing inside a step arrives as an ordinary step failure, so the signal is read
  before the failure is: without that, a run somebody stopped would publish `TaskFailedEvent` and put
  a fault on a task that has none. A run on **another node** is covered by the interrupt bus below;
  everything here reaches the JVM it runs in.

- **`ScheduledTaskRepository.updateIfPresent(task)`** — replaces a stored task and does nothing at all
  if it has been deleted meanwhile, atomically. `executeTask` reads its task at fire time and writes
  it back when the run ends, so the previous blind `save` **recreated whatever `cancel` deleted in
  between** — leaving an unscheduled task that never fires again yet is still listed and still found
  by id, with its quota unit already refunded. No ordering inside `cancel` can prevent that (the
  run's write is always later), so the guarantee lives on the write side. Implementations must make
  the check and the write atomic, which is why the method has no `default`: `findById` then `save`
  narrows the window rather than closing it. Stopping a run promptly is what puts that write squarely
  inside the delete's window, so this is a prerequisite for the feature above rather than an aside.
  A history row can still be orphaned in the gap between the guard and the history write — the two
  repositories share no transaction — so `executeTask` sweeps it, since such a row is unreachable
  (`getHistory` authorizes through `getById`).

  **Breaking for out-of-tree implementations of `ScheduledTaskRepository`** — the only in-tree one is
  `InMemoryScheduledTaskRepository`, which backs it with `computeIfPresent`.

- **`ScheduledTaskInterruptBus`** — carries "stop this task's runs" to the other nodes, so cancelling
  on the node a user happens to be talking to stops the run the node that fired is holding. Without
  it `cancel` reaches one JVM, and in a cluster that JVM is usually the wrong one: the run over there
  keeps writing files and calling out to systems on behalf of a task that has just been deleted.
  What crosses the wire is the **event, not the signal** — a `CancellationSignal` stays a per-run
  in-memory object, and the SPI carries a `(ScheduledTaskId, InterruptReason)` pair. It is a new
  interface rather than a reuse of `SessionSignalBus` because the join key is a `ScheduledTaskId`: a
  scheduled routine is an execution with no session, so borrowing that bus would mean minting a
  `SessionId` for something that is not a session. It therefore follows `ScheduledExecutionGuard`
  instead — same package, in-memory implementation, distributed by swapping the implementation.

  Publishing and subscribing are deliberately split between two types. `ScheduledTaskManager.cancel`
  / `.interrupt` publish, because those are the two places a person asked for a stop;
  `SchedulingEngine` subscribes and hands the request to its own executor. Had `RoutineExecutor` done
  both, its `shutdown()` — which stops everything in flight here — would have broadcast that to the
  cluster and stopped runs on nodes that were not shutting down. Delivery is at-least-once and may
  echo to the publisher, which is harmless because re-tripping a tripped signal is a no-op, and a
  publish that fails is logged rather than allowed to abort the cancellation it was part of.

  Two implementations ship: `LOCAL_ONLY` (the default — on one node the caller's own interrupt has
  already reached the only place a run can be) and `InMemoryScheduledTaskInterruptBus` (one JVM, for
  several engines in a process and for testing the contract without a broker). A cluster-wide one is
  written by the application and reaches the engine through `SchedulingEngineBuilder.interruptBus`,
  `SchedulingSpec.withInterruptBus`, or simply a `ScheduledTaskInterruptBus` bean under the starter —
  the last two exist for the same reason `withTaskRepository` does: an implementation with nowhere to
  be passed would leave hand-building the engine as the only route to a feature whose entire audience
  is multi-node deployments.

  **`ScheduledTaskManager.interrupt` now means less by its return value.** `false` reports that
  nothing was running *on the calling node*, not that nothing was stopped — a fan-out has no answer
  to bring back, so a run held elsewhere is stopped without it ever returning true.

- **A distributed `ScheduledExecutionGuard` can now reach an assembled stack** —
  `SchedulingSpec.withExecutionGuard`, plus a `ScheduledExecutionGuard` bean under the starter.
  `SchedulingEngineBuilder` has always taken one, but nothing between an application and that builder
  passed it along, so the seam that decides *which node may start a fire* was reachable only by
  hand-building the engine and giving up the stack's ordered teardown. Same gap, same shape and same
  fix as the interrupt bus above, which guards the opposite end of a run.

- **`ScheduledTaskManager.builder()`** — the manager's collaborators outgrew what a constructor may
  take (Checkstyle stops at seven), and the eighth is the one a cluster has to supply. Rather than
  choose which of the two cluster seams gets to be the last positional argument, both reach the
  manager through the builder, where each defaults to its single-node answer. The existing
  constructors are unchanged and now delegate to it.

#### Memory — a backend seam at service altitude

- **`PeerMemory` and five capability tiers** (`at.aimon.core.memory`) — `MemorySnapshotReader`
  (SNAPSHOT), `MemorySearcher` (SEARCH), the existing `DialecticEngine` (CHAT, adopted unchanged),
  `ObservationRecorder` (OBSERVE) and `MemoryIngestor` (INGEST), each with a request value object.
  This is now the seam a memory backend is replaced at. The storage SPI is **unchanged and still
  supported** — `ObservationStore` / `RepresentationStore` / `WorkspaceStore` keep every signature —
  but it is demoted to the *materials* the default backend is built from, which `StoreBackedPeerMemory`
  does. The demotion is checkable rather than asserted: an ArchUnit rule forbids any tier signature
  from naming a store, so a backend with no store at all is expressible.

  Which capabilities a backend has is **computed** from its tier accessors by
  `MemoryCapabilities.of(...)`, a static utility. `PeerMemory` deliberately has no `capabilities()`
  method and a rule keeps it that way: a declared set is a second source of truth, and the two
  disagree not at assembly but at the first call, after a tool has been registered and offered to
  the model.

  Losses *inside* a tier are separate and each says so on its own —
  `MemorySnapshot.observationsAvailable` / `confidenceAvailable`, `MemoryHit.confidenceAvailable`,
  `MemorySearcher.ranksByScore()`, `MemorySearcher.narrowsBySession()`,
  `ObservationRecorder.storesConfidence()`. The last two of those govern the query's two narrowing
  axes: a backend that cannot score **rejects** a positive `MemorySearchQuery.minScore`, and one that
  cannot confine a search to a session rejects a `MemorySearchQuery.sessionId`, rather than either
  being ignored — a filter that silently did not run reads as one that did.
  `MemorySearchQuery.observer` is deliberately not in that family: it names who is asking rather than
  promising a smaller result, so a backend that does not read it has not failed to keep a promise.

- **`ObservationType` gains `INDUCTIVE` and `CONTRADICTION`**, with base confidences `0.4` and `0.3`.
  Two values collapsed distinctions a memory backend can express — an inference from a pattern and a
  recorded conflict both filed as `DEDUCTIVE`. Nothing in the tree switches exhaustively over this
  enum, so existing code is unaffected.

  **The in-tree producers still emit only the original two, and that is now enforced rather than
  incidental.** All three of them — `ObserveTool`, `DeriverObservationCreateTool` and `LlmDeriver` —
  offer exactly `EXPLICIT` and `DEDUCTIVE` (two in a schema `enum`, one in the extraction prompt) and
  used to read the answer back with `ObservationType.valueOf`. That agreed with the advertisement only
  because the other two names did not exist; widening the enum turned all three into parsers that
  accept what they never offered. The schema gate does not cover it — its default mode is `WARN`,
  which logs the mismatch and runs the tool anyway, and `ReActLlmDeriver` calls its tool directly
  without passing the gate at all. Each parser now reads the accepted set from the same list its
  advertisement is built from, so the two cannot drift again. Refusing a name the tool never offered
  is the same rule that makes built-in schemas declare `additionalProperties: false`.

  **This breaks downgrade.** Once an `INDUCTIVE` or `CONTRADICTION` observation has been written to
  file, Mongo or Postgres, an older jar reading it back throws from `valueOf`. That can only happen
  through a backend that classifies more finely than this tree does — no in-tree producer can put one
  there. Neither mitigation is worth taking: folding the new values down on write gives up the
  distinction the widening exists for, and a lenient `valueOf` would have to be added to the jar that
  is already released.

- **`MemorySpec.peerMemory(...)`** — names the backend directly instead of the stores it would be
  built from. Mutually exclusive with the store setters, which stay and are folded into a
  `StoreBackedPeerMemory` by the assembly, so every existing program-assembled spec is unaffected.
  Two of the spec's invariants widened to cover both paths: "needs at least one store" became "needs
  a `PeerMemory` or at least one store", and "per-caller needs a representation store" became
  "per-caller requires the SNAPSHOT capability" — the same rule, said in the vocabulary that now has
  two ways of naming a memory.

- **`MemoryChatTool` is finally registered by the assembly.** It was previously wired only by the
  CLI's hand-written code and `MemorySpec` had nowhere to put a `DialecticEngine`, so no
  stack-assembled deployment could use it however its memory was configured. Registration by
  capability closes that: wherever the CHAT tier exists, the tool appears; where it does not, a
  `memory-chat` degradation says so. The starter's `in-memory` backend declares no `DialecticEngine`
  bean, so that path takes the degradation rather than the tool.

- **`ExecutionMemorySink`** (`at.aimon.core.memory`) and
  `OrcaAgentExecutorFactory.withExecutionMemorySink(...)` — the write counterpart of
  `MemoryContextProvider`. Until now nothing fed a conversation into memory except the CLI's shutdown
  hook, so a memory backend built around a message stream read an empty memory for the life of the
  process. The seam is fed after the transcript is persisted, is fire-and-forget, and passes the same
  `(sessionId, principal)` identity the read seam gets, resolved through the same
  `MemoryPeerResolver` so the two cannot answer for different peers.

  When it fires is `MemorySpec.ingestMode(...)`: `OFF` (the default, and what every stack-assembled
  deployment does today), `SESSION_END` (the whole transcript once at close — the CLI's existing
  behaviour, and its default via `memory.ingest` in the yaml) or `EXECUTION_END`.

  `EXECUTION_END` needs a delta, and a delta needs a watermark, and `Message` has no stable id — so
  the watermark is a message count, held in `TranscriptBuffer` beside the rewind point and dropped by
  `replaceWith` exactly as that is. An execution whose history was rewritten under it (compaction,
  prompt-size recovery) therefore **sends nothing**: sending the summary would feed a paraphrase in as
  conversation, and sending everything would re-run an extraction the backend has already paid for.

- **`RedactingPeerMemory`** — the assembly wraps every backend in it whenever a `RedactionPolicy` is
  configured, and hands on only the wrapper. Redaction used to be guaranteed by an implementation
  (`InMemoryDerivationQueueManager` masks inside `enqueue`, so no caller could route around it); a
  public `MemoryIngestor` would have downgraded that to a documented precondition an application can
  ignore. It masks the four tiers that carry caller-written text outwards — INGEST, OBSERVE, SEARCH
  and **CHAT**, the last of which had no gate anywhere before this — and deliberately leaves SNAPSHOT
  alone, whose query carries peers, a session, a mode and a budget and no text at all. Adapters
  therefore never implement redaction, which is the property that matters once there is more than one
  of them.

#### Other

- **`TaskResultStore`** (`at.aimon.core.subagent.task`) — the background-task surface's missing half.
  Lifecycle was already durable in `BackgroundTaskStore` and incremental output in `TaskOutputStore`,
  but the **final result** lived in a node-local `CompletableFuture` map, so a task started on another
  node was listable and stoppable yet unreadable, and a restart lost every result. Four pieces, keyed
  by the same `taskId` as the other three stores: `TaskResult` (a projection, not the result object),
  the SPI, `InMemoryTaskResultStore` (LRU, 256) and `VfsTaskResultStore` (`.aimon/task-result`,
  no envelope — unlike the snapshot store there is no owner tag to carry, because authorization stays
  in `ScopedSubagentTaskController`), plus `TaskResultCodec` / `JsonTaskResultCodec` (`FORMAT_VERSION
  = 1`; an unrecognised `CompletionReason` degrades to `COMPLETED`/`ERROR` rather than discarding the
  answer, while a version mismatch is rejected).

  Three things about it are load-bearing. **The result is saved before the terminal state transition**,
  on both write paths — the finalizer and the pool-rejection path, which never reaches the finalizer —
  so observing a terminal state guarantees the result is already readable, and terminal-with-no-result
  unambiguously means the task produced none. `AgentOutput`'s poll therefore reads state first and the
  result second, never the reverse. **`TaskResult` is a projection**: it drops the `SessionSnapshot`
  (already persisted per-`taskId` by `SessionSnapshotStore` — storing it again would repeat the record
  bloat this work exists to avoid) and the cost, keeping success, answer-or-error, `CompletionReason`,
  iteration count, duration, tokens and a `summaryTruncated` flag. **The storage
  cap is wider than the inline cap** — `TaskResult.DEFAULT_MAX_SUMMARY_CHARS` is 128,000 against
  `SubagentResultFormatter.DEFAULT_MAX_CHARS`'s 32,000, tail-keep in both — so persistence never
  discards text the inline path would have shown; a test pins the ordering of the two constants.

  Wiring: `OrcaAgentRuntimeFactory.withTaskResultStoreFactory(..)` / `withDistributedTaskResultStore()`,
  defaulting to in-memory. Not wiring a store is a supported configuration — `AgentOutput` then says
  result retention is not configured rather than reporting an empty result.
- **`/revoke` (`RevokeApprovalsCommand`)** — drops cached skill approvals for the current session;
  `--agent` drops the agent-wide ones too. Registered whenever an approval store is configured.
  Revoking never weakens security: on a cache miss the policy is consulted again.
- **`docs/backlog/`** — where a finished piece of work registers what it deferred, and the authority
  on what is open (`spring-boot-starter-open-items.md` holds B-1…B-34: 34 registered, 4 open, 25
  closed, 5 dissolved). Design documents' P1/P2 and open-issue tables are frozen and point here;
  rebuilding the register from source rather than copying those tables is what caught three drifted
  rows, including one already-closed item. **"Dissolved" is not "closed"** — a closed item was fixed,
  a dissolved one was never there, and collapsing the two would report a problem solved when the
  truth is there was no problem. `README.md` carries the rules the sweep paid for, each one a check
  the previous pass had skipped: a cited line number is not a checked rationale, a true rationale is
  not a checked severity, a right severity is not a checked *scope*, a written prescription is not a
  tested one, and a type's construction sites must be counted before its documented behaviour is
  quoted (two items cited real lines in types nothing constructs).
- **`AgentDefinitionVersion`** (`at.aimon.core.agent`) — a SHA-256 over a canonical rendering of an
  `Agent` (name, max iterations, sampling `LlmModel`, tags, definition variables, system prompt),
  truncated to 16 hex characters, with tags and variables sorted so ordering cannot reach the digest
  and absent optionals rendered as `""` so an absent key cannot collide with an empty value.
  `ScheduledTask.getAgentDefinitionVersion()` carries it (`Optional`, preserved by both `withEnabled`
  and `withLastExecutedAt` — the second runs after every fire, so dropping it there would read as
  "never recorded"), `ScheduleTaskTool.forAgent(manager, agent)` stamps new tasks, and `RoutineExecutor`
  logs the comparison at fire time: `WARN` with both versions when they differ, `DEBUG` when they
  match, nothing when the task carries no version or the runtime is unregistered. `boundRuntimeId` is
  a *binding*, not an identity — it resolves to whatever runtime the registry holds at fire time, and
  the old definition is still deliberately not pinned, because a task quietly running a prompt its
  owner has since rewritten is the worse of the two failures. What was missing was the ability to
  *say* it changed. A **change detector, not a provenance record**: equal versions mean those fields
  are unchanged, not that the same file produced them, and they say nothing about resolved tools.
  `ScheduleTaskTool` refuses to stamp when the bound id names a different agent than the tool holds
  (comparing the agent-name segment alone, so `agent:<name>:<tenant>` still matches) — recording
  nothing is the documented harmless case, recording the wrong thing would report drift on every run.
- **`ResourceTreeListing`** (`at.aimon.core.skill.repository`) — `enumerated(files)` / `empty()` /
  `unsupported(protocol)`, read back through `getFiles()`, `isEnumerated()`, `getUnsupportedProtocol()`.
  It deliberately has **no `isEmpty()`**: that method would be the same conflation under a new name.
- **`close()` on `DefaultHookExecutor` and `DefaultHookExecutionManager`**, plus
  `TeardownPhase.HOOK_EXECUTOR` between `HOOK_HOT_RELOAD` and `SKILL_HOOK_SHELL`. Each releases
  **only** the pool it created itself — an injected executor stays the caller's even when it happens
  to be `AutoCloseable`, since ownership rather than type decides. Hygiene rather than a leak (those
  threads are daemons and a cached pool retires idle workers after 60s); what it buys is the
  scope-model rule that the creator releases what it created, which starts to matter once a host tears
  a whole stack down and rebuilds it.
- **`AimonStack.agentDescriptors()` / the `AimonAgents.list()` bean** and
  **`AgentRuntimeResolver.trackedIds()`** — the two enumerating views over what a deployment holds,
  and they answer different questions: which agents this deployment *has*, versus which runtimes exist
  on this node right now. Both javadocs say so, and `AimonAgents` carries it as a heading.

---

### Changed

#### Breaking

**Every deprecated symbol is removed**

The repository now contains **no `@Deprecated`**. Ten symbols were deleted outright, and one —
`SubagentExecutionManager.getBackgroundTaskManager()` — was first un-deprecated for lack of anywhere
to move, then deleted once it had somewhere (below). Unlike the rest of this section these break
source compatibility against a **published** release (0.2.1), so each is listed with what to call
instead.

| Removed | Replacement |
|---|---|
| `ArtifactCollector.CONTEXT_KEY` | `at.aimon.core.tools.ToolContextKeys.ARTIFACT_COLLECTOR` — typed, so `context.get(key)` needs no cast |
| `OrcaAgentExecutorFactory.create(LlmClient)` and the `createDefaultTranscriptManager()` hook behind it | `create(LlmClient, TranscriptManager)` with a manager over the host's own `SessionRecordStore` |
| `BackendConnectionException(String, Throwable)` | `BackendConnectionException(BackendType, Throwable)` |
| `PostToolContext.getToolUseResult()` | `getCurrentToolUseResult()` / `getOriginalToolUseResult()` |
| `PreToolContext.getToolUse()` | `getCurrentToolUse()` / `getOriginalToolUse()` |
| `ExecutionOptions.getDrainTimeout()` and `Builder.drainTimeout(..)` | `maxCaptureBytes` — see below |
| `SkillHookEnv.AIMON_AGENT_EXECUTION_CONTEXT_ID` | `AIMON_AGENT_RUNTIME_ID` |
| `at.aimon.core.skill.hook.declarative.ToolMatcher` | `ToolInputPredicate` |
| `SkillPreflightScanner.scan(List, AgentRuntimeId, Principal)` | `scan(List, AgentRuntimeId, SessionId, Principal)`, passing `null` where there genuinely is no session |

**`IdempotencyStore` gains an abstract method**

`boolean acquireHolder(String key, String holderId, Duration ttl)` is added without a `default`, so an
implementation outside this repository **stops compiling** until it supplies one. That is deliberate: a
`default` returning `false` would compile everywhere and silently opt every such backend out of
holder-loss detection for forwarded turns, which is the defect the method exists to close — a silent
wrong answer where a compile error is available. What to implement is in the method's javadoc; the four
in-tree backends are worked examples, and the contract is one conditional write (name a holder on a
holderless `IN_FLIGHT` entry, atomically, refusing everything else). See [Fixed](#fixed) for why.

Three of these deserve more than a row.

One further removal was never `@Deprecated` and is listed here because it breaks the same published
surface: **`OrcaToolProviderContext.getDependencies()`**. It returned the whole
`OrcaProviderDependencies` aggregate instead of one collaborator from it — a bypass around the
seventeen typed accessors beside it — and had **no caller in main or test sources**, across
`.getDependencies()` and `::getDependencies` alike. Replacement is the typed accessor for whatever was
actually wanted (`getSubagentRegistry()`, `getCredentialStore()`, …); the composition behind them is
unchanged. `OrcaCommandProviderContext` keeps its identical method deliberately — it lives in
`agent.impl.orca`, which is not part of the promised surface.

- **`create(LlmClient)` did not merely deprecate a signature, it silently lost data.** It substituted
  a factory-private `InMemorySessionRecordStore`, so an application that had configured Mongo,
  Postgres or Redis still discarded every transcript at shutdown, with a WARN as the only signal.
  Removing the overload converts that into a compile error, which is the whole point.
- **`ExecutionOptions.drainTimeout` was inert, not merely superseded.** Since the file-backed capture
  in `LocalShell` (issue #13) the child writes to a temp file read after it exits — there is no pipe
  to drain, so the value was read by nobody. A setting that changes nothing is worse than one that is
  gone. The identically named `SessionSpec.drainTimeout` in `aimon-bootstrap` is **unrelated and
  still live**; do not migrate it.
- **`AIMON_AGENT_EXECUTION_CONTEXT_ID` was the last exported legacy alias**, and its existence made
  the rule ambiguous. `AIMON_SESSION_ID` had already chosen the other policy — the pre-restructure
  `AIMON_CONVERSATION_ID` is *not* exported, so a handler reading it finds it unset rather than
  quietly receiving a value that now means something else. One rule for every name in `SkillHookEnv`
  is better than a rule and an exception.

**`SubagentExecutionManager.getBackgroundTaskManager()` is gone, and so is `BackgroundTaskManager`
itself** (with `BackgroundTaskStatus`). It had been un-deprecated earlier in this cycle because it
was the only way to obtain what a background task produced — `BackgroundTask`, the record
`BackgroundTaskStore` persists, carries lifecycle metadata but no output — and `@Deprecated` means
"move off this" when there is nowhere to move. There is now somewhere: `TaskResultStore` (see
[Added](#other)). With the accessor gone the holder had no readers, and keeping it would have kept a
`ConcurrentHashMap` that retained every completed `SubagentExecutionResult` — each with a full
transcript attached — for the life of the process.

Replacement for a caller that used it to read a result: none is needed at the call site, because
`AgentOutput` no longer takes the manager. A host that wired its own `AgentOutputTool` passes
`SubagentTaskController` + `TaskOutputStore` (+ optionally `TaskResultStore`) instead. Design:
[`docs/design/subagent/background-task-result-persistence.md`](docs/design/subagent/background-task-result-persistence.md).

**Tools**

- `at.aimon.core.agent.tool.ConcurrencyPolicy` → **`ConcurrencyBehavior`**, `Tool#getConcurrencyPolicy()`
  → **`getConcurrencyBehavior()`**. This enum shipped, so grep for the old method name: an override
  carrying `@Override` fails to compile, but one written **without** it compiles clean, stops
  overriding anything, and silently falls back to `SEQUENTIAL`.
- `SideEffectPolicy` → **`SideEffectLevel`**, `Tool#getSideEffectPolicy()` → **`getSideEffectLevel()`**,
  `OrcaAgentExecutorFactory#withMaxSideEffectPolicy(..)` → **`withMaxSideEffectLevel(..)`**. Never
  released; no migration. The suffix now carries the one real distinction: `*Level` is an **ordered**
  trait that is compared, `*Behavior` an **unordered** set of alternatives.
- **`SideEffectLevel.IDEMPOTENT` is gone** — the scale is two rungs, `READ_ONLY < MUTATING`.
  Idempotency is not a *degree* of side effect; ranking it below `MUTATING` made a ceiling set to
  `IDEMPOTENT` wave through idempotent-but-destructive tools (a delete keyed by id is idempotent).
  Nothing declared it. Destructiveness returns as its own axis above; idempotency returns when
  something reads it.
- **`ToolPermissionValidator`** — `validate(String, Map, List, CustomToolPermissionRule)` becomes
  `validate(Tool, ToolInput, ToolContext, List)`, and `validateOrThrow` follows. The name-only check
  (tool listing, skill `allowed-tools` gating) is now `validateByName` / `validateByNameOrThrow`.
- **`CustomToolPermissionRule`** — `isAllowed(Map, List<AllowedTool>)` becomes
  `isAllowed(ToolInput, ToolContext, List<AllowedTool>)`.
- **`SideEffectApprovalGate.denialReason`** — `(Tool, ToolContext)` becomes
  `(Tool, ToolUse, ToolContext)`; callers must pass the `ToolUse` they are about to dispatch.
- **`BashExecutor`, `ProcessBashExecutor`, `VirtualShellBashExecutor` deleted**; `BashTool`'s
  constructors take a `VirtualShell`. A tool provider reaches the configured shell through the new
  `OrcaToolProviderContext.getShell()`.
- **`OrcaAgentExecutorFactory.createDefaultCommandExecutionManager`** takes a second parameter:
  `(LlmClient, ToolExecutionManager)`. The one-arg `DefaultCommandExecutionManager(LlmClient)` still
  exists and still wires an unrestricted manager.

**Session (routing / SPIs)**

- **`at.aimon.core:aimon-session-base` no longer exists** — depend on `aimon-session-routing`. Most
  consumers never name it: `aimon-bootstrap` and `aimon-spring-boot-starter` both `api(...)` it.
- **`SubmitDisposition.Kind.QUEUED` → `FORWARDED`**, `queued(TurnId, CompletionStage<AgentExecutionResult>)`,
  and `getFuture()` is mandatory, returning a bare `CompletionStage` rather than `Optional`.
- **`SubmitDisposition.executedLocally` / `queued` take the `TurnId` first**; `SessionEventRelay`'s
  constructor takes it second.
- **Removed from the record SPI**: `setAgentRef` (→ `provision(id, agentRef)`, which binds only when
  unbound), `updateCompactionFailureCount` (→ `provision(id)`, or read via `load` and write back
  through the lease-fenced `records()`), `save(SessionRecord)`, `setSessionTotals`, `setBudgetOverride`
  (→ the single `setTotalsAndBudgetOverride`).
- **Deleted**: `ConversationStatePersistence`, `PersistedConversationState`,
  `RepositoryConversationStatePersistence`; `ConversationLock`, `InMemoryConversationLock`,
  `ConversationLockException`; `BindingResolver` (135 LOC — once `claim()` returns
  `Acquired(lease, view)` both its callers are satisfied by that return value);
  `at.aimon.core.agent.compact.RepositoryCompactionFailureStore` (no production wiring; a
  multi-instance deployment reimplements it in ~40 lines against `SessionStore.load` +
  `records()` — no atomic primitive needed, since compaction runs inside a turn and the instance
  recording a failure is by construction the session's single writer).
- **`SessionSnapshot` carries the transcript only** — `getCompactionFailureCount()` and the four-arg
  `of(...)` are gone, and `toSession()` yields a record with every side field at its default, which is
  what makes merging rather than overwriting mandatory. The persisted field is untouched and
  preserved by `mergeFromSnapshot`.
- **`DefaultLiveSession`'s and `LiveSessionFactory`'s last constructor parameter is a
  `SessionRecordStore`** (was a `ConversationStatePersistence`), nullable in both.
- All `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`,
  `SessionSnapshotStore` and `SessionSnapshotCodec` implementations outside this repo **must rename
  their types, overrides and imports**. No deprecated aliases; backward compatibility is not
  maintained across this restructure.

**Execution identity**

- **A subagent fork publishes `EXECUTION_ID`, not a fabricated `SESSION_ID`** — breaking for tools
  reading `ToolContextKeys.SESSION_ID` inside a fork. A `SessionId` means a durable record plus a
  cluster-unique lease, and a fork is entitled to neither. What the publication was *for* was
  partitioning, which `subagent:<name>:<uuid>` does just as well; `TodoWriteTool.CONTEXT_ID_KEY` now
  keys on it. A **resumed** fork keeps the identity of the run it continues, derived from the
  snapshot's transcript label — generating a fresh one would split the todo bucket at the
  suspend/resume boundary.
- **`SkillExecutionContext.executionId` is required.** Mandatory rather than optional on purpose: an
  optional field leaves the executor a reason to keep minting one when the caller stays silent.
  `SkillBackedCommandExecutor` generates `skill:<name>:<uuid>` per *invocation*.
- **`OnSessionStartContext.getSessionId()` / `OnSessionEndContext.getSessionId()` return
  `Optional<SessionId>`**; `ToolContextEnrichmentInfo.getSessionId()` likewise, with an
  `Optional<ExecutionId>` beside it. The pair is deliberately **not** cross-validated — rejecting the
  empty pair is exactly what drives a caller to fabricate. Enrichers wanting the user's session read
  `getInvokingSessionId().or(this::getSessionId)`.
- **`PreCompactContext.sessionIdValue` is optional**, defaulting to `""`; it stays a `String` because
  that shape is part of the declarative env contract.
- **`AIMON_SESSION_ID` can now be empty** for `onSessionStart` / `onSessionEnd` / `preCompact` —
  read `AIMON_EXECUTION_ID` in that case. Previously these firings exported a fabricated `rewake:`
  string, so a script could not detect the situation at all.
- **`${AIMON_SESSION_ID}` renders empty inside a subagent fork** and the warning names
  `${AIMON_EXECUTION_ID}` as the replacement.
- **`${AIMON_SESSION_ID}` is reclaimed as a render variable.** It spent one release as a deprecated
  alias of `${AIMON_AGENT_RUNTIME_ID}`; the alias was **withdrawn, not re-pointed**, so a skill body
  that ignored the `WARN` now receives the per-session value the name always promised. This is the
  one rename in this block that changes runtime behaviour rather than only breaking compilation, and
  it is the fix: `/tmp/work/${AIMON_SESSION_ID}` is now correct where it used to collide across
  concurrent sessions of one agent.
- **`AIMON_CONVERSATION_ID` is gone and no alias is exported** — a handler reading it finds it unset
  rather than silently receiving a value that means something else.
- **`InvokingSessionAccess.idToPropagate` reads `SESSION_ID` only when `EXECUTION_ID` is absent.**
  Precedence is unchanged; the guard makes crossing the two senses of a `SessionId` structurally
  impossible. The second read is deliberately **not** deleted: for a session's turn it is the origin
  of every user-initiated reach, and removing it would stop user-granted approvals from reaching forks.
- **`G10 death-spiral guard counts iterations`** — `OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_TURNS`
  → `MAX_CONSECUTIVE_STALLED_ITERATIONS` (value still 3). The abort message changed with it:
  `"… consecutive tool-only turns made no progress"` → `"… consecutive tool-only iterations made no
  progress"`.
- **`LoopTransitionReason.NEXT_TURN` → `NEXT_ITERATION`** — operator-visible: anything parsing the
  `loop.transition` tracing attribute sees the new string.
- **`PendingTurnRegistry.listByContext(...)` → `listByAgentRuntime(...)`** is an interface method;
  custom implementations must rename the override.
- **`ToolContextKeys.EXECUTION_CONTEXT_ID` → `AGENT_RUNTIME_ID`**, key string
  `"executionContextId"` → `"agentRuntimeId"` (in-process only, never persisted).
- **`SkillPreflightScanner.scan(toolUses, agentRuntimeId, sessionId, principal)`** — the three-arg
  overload remains, `@Deprecated`, delegating with a null session id.

**LLM / skills**

- **`LlmClient.isConfigured()` is removed.** No implementation could return `false`:
  `AnthropicLlmClient` answered a literal `true` with a comment saying why, and `OpenAILlmClient`
  performed `getApiKey() != null && !isBlank()` — **both halves already invariants**, since
  `OpenAIConfig`'s constructor does `requireNonNull` and then throws on `isBlank()`. The second is the
  worse of the two: a live-looking condition tells the next maintainer that `false` is reachable.
  Construction *is* the check — holding a client is the proof its config validated, and whether the
  key works is not knowable from configuration at all; that answer comes back from a call, as an
  `LlmClientException`. There was no production caller: every call site in the tree was a test
  assertion, and `AimonHealthIndicator` never names `LlmClient`. Removed with it: 2 provider
  implementations, 5 decorator delegations (`TracingLlmClient`, `LoggingLlmClient`, `MeteringLlmClient`,
  `TaggingLlmClient`, `BoundMetadataLlmClient`) and 74 test/sample stub overrides — 81 method bodies.
  **Migration**: delete the override. Leaving it with `@Override` is a compile error, leaving it
  without is harmless dead code; nothing calls it either way. There is no replacement — validate in
  your config's constructor and let a failed call report a bad key.
- **`ClasspathResourceTreeWalker.listFiles(...)` is now `list(...)`**, both overloads returning
  `ResourceTreeListing` instead of `List<String>`. The rename is the point: a changed return type
  alone would let every call site keep compiling while quietly changing meaning.

**Filesystem**

- **`SizeLimitedOutputStream` is `at.aimon.core.filesystem.SizeLimitedOutputStream`**, moved out of
  `…filesystem.impl.local`. Import-only for anyone who was reaching into an `impl` package they were
  not supposed to name.
- **GridFS behaves like a filesystem on the three operations where it used to behave like a bucket.**
  Source compatibility is unaffected — every signature is what it was — but the answers changed, and
  they changed towards `LocalFileSystem`, whose behaviour the shared contract test now holds both to:
  - `list(dir)` returns **subdirectories as well as files**. It previously returned only the direct
    file entries, so a caller walking a tree saw nothing below the first level.
  - `list(dir)` / `listRecursive(dir)` on a path that is not a directory now throw —
    `FileNotFoundException` when nothing is there, `InvalidPathException` when a *file* is there.
    Both used to return an empty list, which reads as "an empty directory".
  - `getMetadata(dir)` and `exists(dir)` answer for directories instead of reporting them missing.
  A deployment written against the old answers keeps compiling and starts getting better ones; a
  deployment that *relied* on directories being invisible is the case to check.

**Memory**

- **`RepresentationMemoryContextProvider` is `SnapshotMemoryContextProvider`**, and it takes a
  `MemorySnapshotReader` rather than a `RepresentationStore`. The old name said it read
  `Representation`s; a backend that computes its snapshot on read has no such type, so the name was
  wrong for every backend but one. `SnapshotMemoryContextProvider.readerOver(store)` builds the tier
  over a store for callers assembling the default backend by hand — a second *constructor* was
  rejected because it would be ambiguous for a `null` argument and would have implied the store and
  the tier are interchangeable, which is what the rename denies. Behaviour is unchanged.
- **`ObserveTool`'s input schema now depends on the backend.** When
  `ObservationRecorder.storesConfidence()` is `false`, the `confidence` parameter is removed from the
  schema and omitted from the rendered result, because echoing back a number the backend discarded
  tells the model its value was kept. The default backend stores confidence, so a deployment running
  today sees the schema it saw before.
- **`MemoryAssembly.CAPABILITY_WRITE_PATH` is `CAPABILITY_INGEST`, and its value is `"memory-ingest"`
  rather than `"memory-write-path"`.** The old key named a direction; what is actually missing is a
  capability, and it now has four siblings — `memory-snapshot`, `memory-search`, `memory-chat`,
  `memory-observe` — one per tier the backend does not serve, each with a sentence saying what the
  deployment loses. `memory-tools` and `memory-redaction` keep their names and values. Degradation
  keys are public API because a deployment reads them back with `stack.degradations().has(...)`;
  they are not frozen names, because nothing persists them.
- **`OrcaMemoryToolProvider` takes a `PeerMemory`** and registers by capability. Its store-taking
  constructor is gone rather than kept as a convenience: the class is in an internal package
  (`at.aimon.core.agent.impl.orca.tool`), so it is not part of the published surface and there is no
  out-of-tree source for a compatibility constructor to keep compiling. A caller holding stores writes
  `StoreBackedPeerMemory.builder()`.
- **The three memory tools take a store through a named factory, not a second constructor:**
  `MemoryRecallTool.overStore(representationStore)`, `MemorySearchTool.overStore(observationStore[,
  redaction])` and `ObserveTool.overStore(observationStore[, redaction])` replace the store-taking
  constructors. A store no longer appears in any constructor signature: every constructor left on the
  three takes a tier. Two of them keep their with-and-without-`RedactionPolicy` pair, which was never
  the ambiguous axis. `MemoryChatTool` is untouched — it takes a `DialecticEngine` and never had a
  store constructor to lose, so this is three tools, not the four the memory tool set has.

  Keeping both as constructors would have made a store and a tier overloads of each other: ambiguous
  for any caller passing a literal `null`, and — the part that matters more — reading as a claim that
  the two are interchangeable, which is the one thing this whole seam exists to deny.
  `SnapshotMemoryContextProvider.readerOver(...)` had already made that call for the same reason; the
  tools now follow it instead of contradicting it.
- **`TeardownPhase` moves the memory block from the front of shutdown to after `CHECKPOINTS`, and adds
  `MEMORY_BACKEND` at the end of it.** Declaration order *is* the shutdown order, so this is a behaviour
  change rather than a rename, and it is listed here for a deployment that depends on the old sequence.

  The old placement fitted the only writer that existed: a CLI hook that dumped the whole transcript
  into the derivation queue as the process exited. It does not fit a memory fed as executions end —
  both ingest modes fire while sessions are draining, so a memory block that had already run would
  hand the last of them a closed backend and a stopped queue. The observable change is that session
  drain and checkpoint flush now finish *before* the final derivation, which also makes that
  derivation read a completely written transcript. It can read it at all because the record store is
  application-scoped and `SESSIONS` does not close it — measured in `AimonStackBuilderTest` rather
  than assumed, because the design had it as an unverified premise.

- **`AimonStackSpec` rejects `MemorySpec` + `ExecutorSpec.memoryContextProvider` on the wider test.**
  The guard used to ask whether the spec named a representation store; it now asks whether the spec
  can produce a snapshot at all, which is the question it meant. A spec naming a `PeerMemory` that
  serves SNAPSHOT used to slip past it and end up with two injection providers, one silently dropped.

#### Non-breaking

- **`aimon-scheduling-quartz` now ships its own connection pool, and it is HikariCP.** Quartz 2.5
  moved `com.mchange:c3p0` and `com.zaxxer:HikariCP` from `compile` to `provided`, so a pool no
  longer arrives with the scheduler. Nothing in this repository had ever chosen c3p0 — it was a
  transitive of 2.3.2, named in no build file, no source file and no document — while HikariCP is
  already the pool `aimon-memory-postgres` and `aimon-session-postgres` use. So the module declares
  HikariCP and `QuartzTaskSchedulerBuilder` names the provider (`…dataSource.aimonDS.provider =
  hikaricp`) instead of falling through to Quartz's c3p0 default, which would now fail to build a
  scheduler with a `ClassNotFoundException` naming a library the project never picked.
  **One behaviour changes for callers**: HikariCP loads the JDBC driver class while the pool is
  being configured, where c3p0 deferred it to first use, so `jdbcJobStore(url, driver)` with a
  driver that is not on the classpath now fails in `build()` rather than at the first database
  access. That is stricter, and it caught something immediately — the module's two JDBC job store
  tests named `org.h2.Driver` and `org.postgresql.Driver` with **neither on the test classpath**,
  and had been passing on c3p0's laziness. They now carry both drivers at `testRuntimeOnly`. This is
  the second time that blind spot has been recorded here; the `dataSourceClass` test's own comment
  is the first.

- **The Redis and Postgres inboxes stopped hand-mapping `SubmitOptions`.** Both called
  `SubmitOptionsCodec` instead, which the rewind work had already added and which the Mongo inbox
  cannot use — its currency is a BSON `Document`, not an `ObjectNode`. Three hand-written copies of
  one mapping therefore become one shared codec and one deliberate second representation, and what
  keeps those two honest is that `SubmitOptionsCodec` now publishes its field names
  (`TOP_LEVEL_FIELDS`, `PRINCIPAL_FIELDS`, `LLM_CALL_METADATA_FIELDS`) and the Mongo codec's test
  asserts its key sets against them rather than against literals of its own. The core-side twin
  asserts the same three sets against the declared properties of `SubmitOptions`, `Principal` and
  `LlmCallMetadata`, so a **new** property fails there and handling it in the shared codec alone
  fails in the Mongo test. **Nothing on the wire changed**: the three field name / shape sets were
  already identical, which is what made converging them a deletion rather than a migration.
- **`SubmitOptionsCodec` gained `encode(SubmitOptions, ObjectMapper)` and
  `decode(JsonNode, ObjectMapper)`**; the existing no-mapper forms delegate to its private one, so
  the rewind point is untouched. The parameter is not decoration. `systemPromptVariables` and
  `executionAttributes` are `Map<String, Object>`, so the mapper's configuration is part of what
  reaches the wire, and both inboxes let the application supply one (defaulting to a mapper with
  `JavaTimeModule` registered). Had the convergence above used the codec's private mapper, one
  subtree of a document would have followed different rules from the document around it, for
  temporal values only — invisible to a round-trip test, which puts the same mapper on both sides.
  The one behaviour that does change: a malformed `principal` inside `submitOptions` now fails as
  `SessionSnapshotCodecException` rather than a raw `NullPointerException`. Both are unchecked, both
  fail the decode, and no test pinned the old type.

- **`ToolExecutionManager.getMaxSideEffectLevel()`** is a new interface `default` returning the
  unrestricted ceiling, so no implementation must change. `DefaultSubagentExecutor` and
  `LlmSkillExecutor` now filter their definition lists by it, closing two holes: a fork was shown
  mutating tools the shared manager would refuse (a wasted iteration), and a user-invoked `/slash`
  skill got a `new DefaultToolExecutionManager()` that permitted everything (a real hole — the skill
  runs against the agent's real `ToolRegistry`). The ceiling is read from the manager that would do
  the refusing, never configured twice; a test pins the sharing by **identity**.
- **A `/slash` skill's tool calls take the agent's pipeline.** `LlmSkillExecutor` dispatches through
  `SkillToolDispatcher` instead of calling `ToolExecutionManager.executeAll(...)` directly, so
  permission hooks → approval gate → `PreTool` → execute → `PostTool` all run. A permission violation
  is now one tool's error observation rather than the end of the skill. Embedders driving
  `LlmSkillExecutor` with no agent runtime bind no dispatcher and keep the old behaviour.
  `ReActLlmDeriver` remains the one loop calling `Tool.execute` directly, and its javadoc now says
  why: its tool set is closed and the only side effect reachable is the `ObservationStore`, while
  derivation runs unattended where an `ASK` resolves to deny. **A new deriver tool may write to the
  observation store and nothing else.**
- **The side-effect approval prompt shows the call** — `'Bash(command=rm -rf build)'`, arguments
  sorted by key (`ToolUse.getInput()` is a `Map.copyOf`, so unsorted output would differ between
  runs), whitespace flattened, values cut at 60 characters and the list at 200. The **decision still
  keys on the tool alone**, and the prompt says so: *"Approving covers every 'Bash' call for the rest
  of this session, whatever its arguments."*
- **`BashTool`'s `timeout` now kills the process.** The old SPI released the Java-side wait and left
  the process running; `BashTool` now hands `VirtualShell` an `ExecutionOptions` with the real
  timeout and a `maxCaptureBytes` of 1,000,000, so both limits are enforced where the process is.
  `LocalShell` destroys the process **tree** and keeps partial output on interrupt.
  `ShellExecutionException` / `ShellTimeoutException` carry `stdout`, `stderr`, `outputTruncated`.
  `timeout` is clamped to `[1s, 600s]` and the schema declares the range — the lower clamp matters
  because `VirtualShell` reads a non-positive timeout as "wait forever".
- **A non-zero exit code is a value, not an exception.** `BashTool` renders the output and appends
  `[exit code: N]`, or `[timed out after Nms]` with the partial output. A shell that could not run at
  all is still `Command failed: …`, so the three outcomes are told apart by content. `is_error` is
  unchanged — a non-zero exit still sets it. The old "Consider increasing the timeout parameter"
  advice is gone.
- **A JSON `null` no longer fails the turn.** `ToolUse.of(...)` built its map with `Map.copyOf`, which
  throws on a null *value*, upstream of `ToolInput` and so beyond any `ToolResult.error`. Both types
  now drop null-valued entries. Contract consequence: **a parameter present with a null value reads
  as absent** — `has(...)` is false and the gate reports a missing `required`, not a type violation.
- **`aimon-cli` assembles through `aimon-bootstrap`.** `AgentSetupFactory` keeps only what is bound to
  the terminal; `create()` is 104 lines and `AgentSetup.close()` is `stack.close()`. Two behaviour
  changes: `DefaultLiveSession` now receives the real hook execution manager instead of `null`, so
  **`OnSessionStart` / `OnSessionEnd` fire in the CLI for the first time** (nothing changes for a
  default install, which registers only `PRE_TOOL`, `POST_TOOL` and `SUBAGENT_START`); and bundled
  skills resolve their classpath root from the agent's own name (`agents/<agent name>/skills`) rather
  than the configured one, which is already what `AgentRuntimeId` derives from.
- **`ScheduledTaskManager.executeTask` is now `public`** — it is the callback an external
  `TaskScheduler` fires into, and the documented wiring
  (`.taskExecutor(taskId -> taskManager.executeTask(taskId))`) could not have compiled from another
  package. Pinned by `ExternalSchedulerWiringTest`, which lives in a different package on purpose.
- **`SchedulingEngineBuilder.executionGuard(ScheduledExecutionGuard)`** — there was previously no
  supported path to a distributed guard at all, since the builder used the constructor that
  hardcodes the in-memory one and `SchedulingEngine`'s constructor is package-private. Default
  unchanged (node-local).
- **Scheduled runs carry their agent runtime and owner.** `RoutineExecutor` handed every step an
  empty `ToolContext`, so `ScheduleTask` refused the call and `Task` / `TaskList` / `TaskStop` /
  `AgentOutput` threw or fell back to an unscoped view; both values now come from the task itself and
  survive a cron re-fire. Owner was not merely missing — a routine step scheduling follow-up work
  recorded `Principal.system()`, losing the human one hop in. This widens approval reach on purpose:
  a skill invoked from a routine step now reaches `AgentApprovalStore`, which is what agent scope is
  documented to mean.
- **`RoutineExecutor.buildToolContext` publishes a fresh `ExecutionId` per fire**
  (`routine:<taskId>:<uuid>` — the task id alone would collide across fires and quietly merge two
  runs' per-run state). **A rewake replay** carries `ExecutionId.of("rewake:" + envelopeId)` instead of
  a fabricated `"rewake:<envelopeId>"` session id. `SESSION_ID` / `INVOKING_SESSION_ID` stay unset for
  both.
- **`MemoryToolContextEnricher` omits `memory.sessionId` when there is no session** rather than
  falling back to the run's own id — with no session id, `MemoryRecall(mode=LOCAL)` matches across
  sessions, a superset of what a never-written id could return.
- **`TodoWriteTool.CONTEXT_ID_KEY` is documented as a run identity, not a session id** — the contract
  is the property its two writers share: the id must name **this run and no other**.
- **"turn" now means one thing.** Per `docs/overview/glossary.md` §4: **turn** = one user input,
  **iteration** = one ReAct pass, **execution** = one agent run which may have no session at all.
  `assistant turn` / `user turn` stay — they are LLM message-role vocabulary and the qualifier is what
  disambiguates them. `ExecutionBudget` / `BudgetTracker` are per **execution unit** (turn *or* fork);
  that is exhaustive, not a hedge — main sources construct a `BudgetTracker` in exactly two places.
  `TurnVocabularyArchitectureTest` keeps `Turn` out of identifiers under the trees that run no turn.
- **A suspended turn always has a session** — `PendingTurn.getSessionId()`'s `Optional` was justified
  by callers that cannot exist. Nothing session-less reaches the suspend path. The `Optional` stays
  because the builder does not require the id, so an embedder may omit it; only the justification
  changed. No behaviour change.
- **`SessionRecord` has no append path.** The transcript half is the immutable `SessionTranscript`,
  held by reference so copies share it. `addMessage` / `addUserMessage` / `addAssistantMessage`
  (already `@Deprecated`) are removed — they had **zero** production callers, since every real append
  goes through `TranscriptBuffer`. Never declared on the mutable view, so no interface changed.
  Two `ArchitectureRulesTest` rules hold the split, the second existing because the first can only
  forbid calls to methods that exist.
- **Skill approvals default to the session they were given in.** Answering "y" used to write into a
  store keyed by `AgentRuntimeId`, pre-answering every later session of that agent forever. The chain
  is now consulted **narrow-first**: pending turn → session → agent → configured rules. CLI prompt is
  `Allow skill 'X'? [y/a/N]`; `/approve` and `/deny` default to the session and `--agent` widens
  them; `/revoke` drops the session's, `/revoke --agent` drops both; `/clear` now drops the session's
  approvals along with its history and says so only when a session store was actually wired.
  `SkillApprovalChannel` gains a three-arg `requestApproval(...)` **default** method, so existing
  channels compile and behave as before.
- **A fork inherits the invoking session's decisions, in both directions.** Narrowing the default
  write target broke every fork — a fork shares its parent's `AgentRuntimeId` but has no `SessionId`,
  so it missed both stores and fell to the rule tail's `ASK`, which for a fork means `DENY`. The
  invoking session id is threaded on `ToolContextKeys.INVOKING_SESSION_ID` (read through
  `InvokingSessionAccess`) and on `SubagentExecutionEnvironment` / `SubagentExecutionRequest`, so
  nesting is transitive: a fork spawning a fork hands down the **user's** session. Covers subagent
  forks, skill forks and foreground workflows; background workflows and agent-scoped workflow runners
  inherit nothing, because they outlive the session.
- **The session router drops a session's approvals** on `releaseSession`, on `deleteSession`, and on a
  peer's `EVICT` broadcast (the in-memory store is node-local, so a peer's delete cannot reach this
  node's copy and a later session reusing the id would inherit approvals without asking). Idle-TTL
  eviction and `close()` deliberately do **not** purge — the session survives those, so the answer
  should too. Fail-open. Wired via `SessionRouter.builder().sessionApprovalStore(...)`.
- **MCP startup is bounded by a net rather than by a transport's politeness.** `createClients` waited
  with a bare `future.get()` and `awaitTermination(Long.MAX_VALUE)`, safe only by borrowed luck:
  `StdioMcpTransport` polls `ready()` against a deadline instead of blocking in `readLine()`, so every
  request already returned within `requestTimeout`. Nothing in `createClients` asked for that, and
  `DefaultMcpClientFactory` has `SSE` / `STREAMABLE_HTTP` branches stubbed out waiting to be filled
  in. Every future now shares one deadline of `longest requestTimeout + spawn allowance`, and a worker
  still running when it expires is reported as a failed server rather than waited on. It is a **net,
  not a startup budget** — loose enough that a healthy server cannot trip it, holding no opinion about
  how long startup should take. A *max* rather than a sum because `newCachedThreadPool` has an
  effectively unbounded maximum over a `SynchronousQueue` and `initialize()` sends exactly one
  request: N servers cost one `requestTimeout`, not N of them.
- **The single-server fast path is gone**, so the net covers the most common case. Its one real
  behaviour — rejecting an already-registered name — moved into the parallel path, where it now also
  catches a name repeated *within* one `configs` list; such a duplicate previously overwrote the
  client holding that name and was reported as a success. A server that finishes after being given up
  on is closed rather than left registered.
- **A sampling parameter Anthropic cannot honour is said out loud, once.** `LlmModel` is
  provider-neutral and accepts `temperature` up to `2.0`; Anthropic's range is `0.0`–`1.0` and it has
  no counterpart to `presencePenalty` / `frequencyPenalty` at all, so the client clamped one and
  dropped two — correct behaviour, reported where nobody could see it (the clamp warned on *every*
  request, the penalties left only a `log.debug`). Either way the call **succeeds**, with sampling
  settings other than the ones configured, which is the one failure mode a log line is the only
  defence against. Both paths now go through `reportDivergence`: `WARN`, keyed by parameter **and
  value** (one client serves every agent bound to that provider, so keying on the parameter alone
  would report whichever agent went first and silence the rest), said once rather than once per ReAct
  iteration, bounded at 32 distinct divergences. `LlmModel`'s range check stays where it is — these
  are sanity bounds built at agent-definition load time, so nonsense fails a deployment at startup
  rather than on its first LLM call.
- **`KeyPatternSpanRedactor` normalises the key** (lower-case, then drop `-`, `_`, `.` and spaces)
  instead of enumerating separators. Its fragment list carried `apikey` **and** `api_key` — two
  spellings of one word, having missed the third: `api-key` matched nothing. That is the header Azure
  OpenAI authenticates with and the spelling this project's own property uses. Normalising closes
  `x-api-key` with it and cannot lose a match, since no fragment contains a separator. Over-masking is
  untouched in both directions: `contains` still reads `max-tokens` as a token, which is
  wrong-but-safe, and narrowing it here would drop `headers.x-auth-token`.
- **`FileSystemFactory` constructs the local backend directly.** It reached all three backends through
  `Class.forName`, and the local one's catch clause advised putting `filesystem-local` on the class
  path — an artifact that has never existed. `LocalFileSystem` ships **inside `aimon-core`**, in the
  same module as the factory, so those strings were a compile error postponed to run time with a false
  remedy attached. GridFS and S3 keep their reflection and their hints; those modules really can be
  absent.
- **The `scheduling-durability` degradation is graded rather than unconditional.** No repository says
  the tasks are gone after a restart and a durable scheduler does not change it; a repository over the
  default scheduler says the opposite half is missing — stored tasks survive and nothing is left
  scheduled to fire them, the shape that looks like durability until the hour comes; both halves
  supplied says nothing at all, because whether the supplied implementations are genuinely durable is
  not something the builder can inspect, and it does not guess.
- **`AgentRuntimeExhaustedException` reports both readings.** It carried `entries.size()` and
  `maxEntries` — precisely the pair that reads the same whether every slot is serving someone or none
  is — and asserted *"none of them is idle"*, false in exactly the second case, while its own remedy
  list offered "shorten the idle TTL" one clause later. The message now says `<held> of the <live> are
  held by a caller right now`, and its closing line changed from "as soon as any current holder
  releases" to "or an idle runtime ages out", since the zero-held state has no holder to wait on.
- **The legacy `.aimon/commands/*.md` guard names the workspace root.** `legacyCommandsDirectory` is
  filesystem-relative and therefore identical for every runtime, so on a multi-tenant deployment
  `'.aimon/commands'` alone never said *whose* workspace to clean up; the message now carries
  `fileSystem.getWorkingDirectory()`. "Delete the originals before starting the agent" was false on
  the lazy path, where the agent is being created right then on a request thread, and
  `scripts/migrate-custom-command-to-skill.sh` is a path in the AIMON repository rather than in a
  consumer's deployment. Both now say so. The guard itself stays a hard stop — see the scope note.
- **`PlaywrightLifecycleManager`'s worker thread is a daemon**, via a named `workerThreadFactory(int)`,
  so a Playwright-enabled JVM is no longer held open by it.
- **`gradle/libs.versions.toml` holds one ref per Spring Boot version, not two.** A second pair of keys
  (`spring-boot-plugin`, `dependency-management-plugin`) carried the same two values for `[plugins]`
  alone, with nothing keeping the halves in step, so a Boot bump was two edits that looked like one.
  Collapsed onto `spring-boot` / `spring-dependency-management`; the comment says to split the ref back
  out deliberately if the plugin ever needs to lead or trail the library BOM.

---

### Fixed

- **Cross-node signals reach the holder, not just observers.** Subscriptions were created only inside
  `events(sessionId)`, so a node nobody streamed from received no `INTERRUPT` / `EVICT` / `STATUS`
  for a session it was actively serving — cross-node control worked only when the client happened to
  stream from the node that won the lease. Two user-visible consequences: `interrupt(...)` issued on
  another node did nothing, and `deleteSession(...)` on a peer could not make the holder yield and
  threw after exhausting its retries. Also, a remote `EVICT` now drops the cached
  `sessionId → agentRef` binding, which is positive-only and TTL-less — after a peer's delete nothing
  could ever correct it and every later submit with a different `agentRef` was rejected permanently
  on that node. Subscriptions are released on release / delete / graceful close but deliberately
  **not** on idle eviction, which would reintroduce the second defect.
- **A turn running longer than `idleTtl` no longer closes its own session.** Pinned entries now expire
  through `expireAfter(Expiry)`.
- **A submit that reserved an idempotency key and then lost the session no longer strands the key** —
  `releaseHolder` returns it.
- **Mongo's fencing token survives release.** `release` deleted the lease document, taking the fence
  with it; it now expires the lease in place, with rows removed only by `deleteSession`.
- **`SessionEventRelay` overflow no longer discards the terminal frame.**
- **`closeGracefully` releases the lease of a turn that never started.**
- **Holder loss fails the turn instead of being reported as an eviction**, and reaches every
  subscriber rather than only the node that lost it.
- **A draining node hands the session over** rather than refusing, answering `NOT_HOLDER` so the peer
  can take it.
- **A running turn keeps its session alive** across idle-TTL expiry.
- **A failed lease renewal drops the lease locally in the same call**, and re-proof
  (`requireHeld`) reads the holder rather than extending it. `deleteSession` acquires rather than
  claims, and deletes through the fenced view.
- **The lease is held under the bare `nodeId`, not a per-attempt id** — the idempotency reservation
  keeps its own per-attempt *reserver* id, and the two are no longer conflated
  (`IdempotencyDecision` carries the reserver id; `LeaseRenewer.start(held, touchSlot,
  onExtendFailed)` is the only overload).
- **`submit` takes the node-local turn gate before it touches the store**, and `endTurn` runs before
  `unpin` — the order is load-bearing, because unpinning can evict. A peer's
  `INTERRUPT(SESSION_RELEASED)` now makes the holder yield rather than merely stop.
- **A forward is failed, never abandoned** — `releaseSession` / `deleteSession` complete the pending
  future exceptionally instead of leaving a caller waiting forever. `submit()` subscribes to the
  signal bus before any I/O.
- **A forward whose holder died is retried rather than left to its deadline.** A submission that loses
  the election is queued in the inbox and announced once; every peer that heard that announcement and
  found the session held gave up, on the understanding that the holder re-rings from its lease-return
  path. A holder that *crashed* never runs that path — its lease merely lapses on its TTL — and the
  queued message carries no idempotency holder, so the holder-loss sweeper does not see it either. The
  message therefore waited for the session's next submission, which may never come, while its caller
  waited out the whole `idempotencyForwardTtl` (5 min) for a turn no node was running. The forward
  poll now re-rings the doorbell on every tick where that session's inbox still holds the message,
  which bounds recovery at one poll interval past the lease expiry (~45 s on defaults) and cannot
  disturb a live holder — the retry's `acquire` fails against a lease that is still being renewed.
  An uncollected message is the only thing a drain pass can pick up, which is what the emptiness check
  is for — once some node has taken the message out of the inbox, only that node can produce its
  result. Counted by the new `SessionMetrics#onForwardDoorbellRerung()`.
- **A forwarded turn whose holder dies *after* collecting it is reported as `HOLDER_LOST`.** This is
  the other half of the entry above, and the half a doorbell cannot reach: once a node has taken the
  message out of the at-most-once inbox there is nothing left to re-announce, so recovery had to come
  from the sweeper — which never saw it. `forwardToInbox` clears the reservation's holder so a message
  waiting in the inbox is not mistaken for a live turn, and nothing put a name back on it, while
  `findStaleInFlight` reports only entries that *have* a holder. A node that crashed mid-turn on a
  drained message therefore died anonymously, and its caller was answered by nothing faster than the
  `idempotencyForwardTtl` (5 min) timeout — where the same crash on a locally submitted turn was
  reported in ~45 s. The drain pass now takes the reservation over before running each message
  (`IdempotencyStore#acquireHolder`, new on the SPI and implemented atomically by all four backends:
  a Lua CAS on Redis, a conditional `findOneAndUpdate` on Mongo, `UPDATE ... WHERE holder_id IS NULL`
  on Postgres, `computeIfPresent` in memory), binds it into the lease's touch slot for the length of
  that turn, and hands it back through `markDone` or a holder-matched reset when the turn ends. A
  take-over can lose four ways — a `DONE` entry, one another node holds, one whose TTL has lapsed, or a
  store that throws — and the message runs anyway in all of them, because it is already out of the
  inbox and refusing it would destroy work no successor can recover. What such a turn gives up is
  stated rather than glossed: the sweeper cannot see its node die, **and its result is not written to
  the idempotency cache**. That second half is also a fix in its own right. `markDone` matches on the
  key alone in every backend, so a drained turn used to overwrite whatever entry happened to hold the
  key — including the `DONE` one the take-over had just declined to disturb, replacing an answer a
  client had already been given with one it would never see and making every later replay of that key
  return the wrong one. The caller is still answered over the rail either way; only the durable copy is
  withheld, and a retry then re-executes rather than replaying an answer that belongs to somebody else.
  **Only a refusal withholds it**: a take-over that could not read the store learned nothing about who
  owns the entry, so that path writes exactly as it did before — treating silence as a refusal would
  leave a successful turn's entry `IN_FLIGHT` with no holder for the whole forward TTL, invisible to
  the sweeper, so a node that missed the rail would time out five minutes after that turn succeeded.
  And silence is not the same as *no write*: the ordinary way a remote store throws is with the
  write applied and only the response lost, which leaves the entry naming this node with nothing
  refreshing it — the exact shape the sweeper reads as a death, so a peer declared a live turn's
  holder lost and the client's retry ran the same request a second time. That path therefore binds
  the reservation as well, and binding it when the write did not land costs nothing, because `touch`
  and `compareAndReset` both match on the holder and reserver ids are minted per attempt. Enforcing
  any of this in the store instead would need a holder-matched `markDone` on the SPI, registered in
  the design's §14 rather than done here.

  A refusal is now counted by the new `SessionMetrics#onReservationTakeOverRefused()`, which should
  read zero: it means the store answered that the key belongs to something else, so the message this
  node went on to run is a request the cluster executed twice. Nothing else shows it — the result is
  deliberately withheld, the caller is answered over the rail as usual, and no announcement
  distinguishes it. Like every method on that interface it has a no-op default, so existing
  implementations are unaffected.

  No schema change: every backend already had a nullable holder column. `IdempotencyTouchSlot` holds
  one binding per reservation rather than one in total, because a pass owes refreshes to both the
  submission that opened it and the queued message it is currently running, and a single slot let a
  sibling LLM turn outlast the secondary TTL and get the other swept as lost. One cost is new and
  named in the design doc rather than left implicit: a forwarded turn's reservation used to need no
  touching at all, so it could not be swept by a touch failure; now a drainer whose lease renews but
  whose `touch` fails past the secondary TTL has its live turn swept and its client's retry
  double-execute. That is the regime local keyed turns already lived in, and the symmetry is the
  point of the change, but it is not pure gain.
- **A doorbell notice does not outlive the session it announces.** `releaseSession`, `deleteSession`
  and a peer's `EVICT` all purge the inbox; the node-local marks that say "somebody still has to
  collect this" now go with it, instead of buying an empty drain pass on the next lease return and
  being inherited by a later session that reuses the same `SessionId`.
- **A permission fail-open is closed (security).** The validator's final branch permitted the call, so
  a tool listed *with* a pattern that produced no subject and had no rule was **allowed** — the
  strictest-looking configuration produced the weakest enforcement. That branch now denies: an empty
  subject means "cannot be judged", and cannot-be-judged is a denial whenever a pattern is configured.
  Relatedly, a bare name listed alongside a patterned entry for the same name no longer grants
  everything — a bare name is unlimited only when no entry for that name carries a pattern. And
  `AllowedTool` splits on the **last** `)` rather than the first, rejecting unbalanced or trailing
  text with `InvalidToolSpecException`, so a pattern containing parentheses parses as written.
- **An interrupt can no longer walk past a PreTool block (security).** `DefaultHookExecutor` routed
  the `InterruptedException` from `future.get` through `HookExecutionPolicy.onException`, whose
  availability-first default maps it to `success()`. On a thread whose interrupt flag is already set
  that `get` throws without waiting, so **every hook in the chain reported SUCCESS without having
  run** — a PreTool hook that was about to return BLOCKED was silently downgraded to allow, and the
  same silence covered the permission-request chain, an OnStart veto and an AUTO compaction's. An
  interrupted wait is now BLOCKED regardless of the policy, because the mapper is not asked: it
  answers "what if a hook *fails*", and an interrupt is not a hook failure but the loss of this
  thread's ability to wait for a verdict. No verdict means no permission to proceed, exactly as
  `TimeoutBehavior.FAIL_CLOSED` already decides for an expiry. Gating on `stopOnBlocked` would not
  have worked either — OnStart runs under the never-stop policy and its caller still aborts the turn
  on a blocked result. This is defence in depth behind the ReAct loop's flag hygiene, which keeps a
  stale flag from reaching the executor at all, and it cannot deny anything in a healthy turn: the
  path is reachable only when the thread driving the turn has genuinely been interrupted, which is to
  say when the turn is being cancelled anyway. Work that already finished keeps its verdict.
  Registered as remaining work in `docs/design/agent-execution/interrupt.md` §14; the design note is
  now §8.7.
- **An agent has the same subagents whether it was started from a jar or from a directory.**
  `AdaptiveAgentBundleLoader` chose a loader from the protocol of one URL — the agent's own
  `agent.md` — so an application with its definition unpacked on disk and its skills and subagents in
  dependency jars lost everything the jars shipped, but only when running from a directory: only in
  development, only under `bootRun` and every IDE. Skills survived because
  `BundledSkillMaterializer` runs over the whole class path regardless; subagents had no such second
  pass. The two loaders are now **composed** — class path underneath, working directory on top, merged
  through `CompositeSkillRegistry` / `CompositeSubagentRegistry`, later wins — so a locally edited
  skill still overrides its packaged shadow. `ClasspathAgentBundleLoader.asUnderlay(...)` constructs a
  quiet variant, since the "no index file" warning is a claim only the loader with the last word on a
  directory can make. Verified end to end by a new `@Tag("packaging")` tier that launches the same
  sample from a fat jar and from a directory and compares what the model was shown.
- **Memory recall inside a subagent fork missed every time.** `MemoryToolContextEnricher` stamped the
  run's own id into `memory.sessionId`, but memory is only ever written under a user-facing session,
  so `MemoryRecall(mode=LOCAL)` looked up a session that by construction had never been written — a
  structural 100% miss, silent because a miss and an empty memory are indistinguishable.
- Two file-name/class-name mismatches left by the Stage 5 rename: `SessionIdTest.java` declared
  `class ConversationIdTest`, `OnSessionContextsTest.java` declared
  `class OnAgentEnvironmentSnapshotsTest`. Both compiled; neither could be found by class name.
- **Three YAML frontmatter parsers shared a `Yaml` instance, and it silently swapped documents
  (security).** `SkillContentParser` held a `static final` one across the whole JVM;
  `SubagentContentParser` and `MarkdownAgentDefinitionParser` held fields of their own. All three now
  build one per parse call. snakeyaml 2.2 has two independent hazards here: `loadFromReader` publishes
  each call's fresh `Composer` onto the *shared* `BaseConstructor` and reads it straight back out, and
  `BaseConstructor` carries plain `HashMap`/`HashSet` collections that every concurrent construction
  mutates and whose `finally` clears them mid-flight for everyone else — `SafeConstructor` does
  nothing about the second. Eight threads parsing eight distinct `SKILL.md` files reproduce it within
  a few dozen attempts, and roughly **five in six failures throw nothing at all**: `parse` returns
  success with keys missing or another skill's values in place. That silence is why this is not
  hygiene. A dropped `allowed-tools` makes `SkillMetadata.hasToolRestrictions()` false, and
  `SkillPermissionManager` gates on it at three points that all fail **open**; `DefaultSkillRegistry`
  then caches the degraded skill, so a momentary race is served until the process restarts, with
  nothing in the log. Two class javadocs claimed "Thread-safe and stateless" about the exact opposite.
- **A frontmatter key written with nothing after the colon no longer aborts skill loading.**
  `SkillContentResult`'s defensive `Map.copyOf` rejects null *values*, and YAML gives a null for any
  such key, so `license:` — or `description:`, or any key at all, the defect was in the shared copy —
  threw an undeclared NPE that the one in-tree caller flattened to "Unexpected error during parsing".
  It now copies into a `HashMap` wrapped in `Collections.unmodifiableMap`: still unmodifiable, the
  null survives. A **second** NPE site sat one level in — a nested empty value passes the copy
  untouched and then kills `MarkdownSkillParser.extractMetadata` at `entry.getValue().getClass()`,
  *while building the error message meant to describe that very input*. All five such sites now go
  through a `typeOf` helper that names a null without dereferencing it. Normalising an empty value to
  `""` was rejected on evidence rather than taste: it would make `description:` a valid empty
  description and build the skill, which is the same bad-input-ends-in-a-pass shape as the entry above.
- **`MarkdownSkillParser` no longer overwrites its own exact errors.** `parse` caught
  `IllegalArgumentException` and then `Exception`, and `SkillParseException` is neither — its line is
  `SkillException` → `AimonException` → `RuntimeException` — so one raised *inside* the try was caught
  by the blanket clause and rewrapped as "Unexpected error during parsing". The three it overwrote are
  the commonest authoring mistakes there are: `Missing required field: description`, `Field
  'description' must be a string, got: Integer`, `Skill name mismatch`. Fixed with
  `catch (SkillParseException e) { throw e; }` at the head of the chain. What kept it alive is that
  `hookSetParser` throws `IllegalArgumentException`, so every hook test stayed green and not one test
  anywhere asserted on those messages.
- **`DefaultSkillRegistry` does what its javadoc claimed.** `getSkill` is a `computeIfAbsent`
  (concurrent callers for one name produce one repository read and one parse, not N — the condition
  that made the shared-`Yaml` race a normal path rather than a rare one), `reloadAll` is
  `synchronized` and builds the replacement map in full before a single assignment, and `cache` is
  `volatile` to publish that swap. A miss is still not cached, so a skill that appears later is
  loadable. The rewritten javadoc records what is *not* promised — a `getSkill` overlapping a reload
  may deposit into the map about to be discarded, costing one repeated load — because an unqualified
  "thread-safe" is the exact sentence that opened this item.
- **`OrcaAgentRuntimeManager`'s per-id lock survives its own retirement.** `destroyRuntime` retires
  the per-id monitor while holding it, and must — `contextLocks` is keyed by runtime id, so destroy is
  the only place that map ever shrinks on the tenant axis. But `getLock` was a bare `computeIfAbsent`,
  so the next arrival minted a **different** object for the same id, and two threads on two different
  monitors are not mutually excluded at all. The window is not nanoseconds: a creator queues behind
  the destroyer for however long `close()` takes (MCP shutdown, seconds), so every caller arriving in
  that window got the fresh monitor. The cost was not a duplicate call —
  `DefaultAgentRuntimeRegistry.register` is a plain `put`, so the loser was silently overwritten and
  **never closed**, an `AgentRuntime` holding live MCP clients orphaned by the very method whose job
  is releasing them. Both callers now go through `withRuntimeLock`, which re-validates the monitor
  after acquiring it and retries with the current one. The removal stays inside the lock and must
  remain the **last statement** under the monitor. `destroyRuntime`'s javadoc said it must not run
  concurrently with `getOrCreateRuntime` for the same id; that warning is now false and was rewritten.
- **An agent the stack declared can no longer get a second runtime built behind its back.**
  `AgentRuntimeResolver` builds runtimes on demand for ids the registry does not hold — that is how a
  multi-tenant host gets `agent:ops:acme` — and it read "not in the registry" as "not the stack's",
  with a discriminator as the mark of a tenant. Neither holds: `AgentSpec.Builder.discriminator` is
  public API, so a stack can **declare** `agent:ops:eu`, and by shape that id is indistinguishable
  from a tenant's. The registry is empty between `assemble(spec)` and `startRuntimes()`, so the
  resolver provisioned — and *succeeded*, because `StackAgentRuntimeProvisioner` keys templates by
  agent **name**. `startRuntimes()` then replaced that entry without closing it, leaving a live
  runtime that cron re-fires and session bootstraps can no longer reach. That window is not
  incidental: `assemble` exists as a separate entry point precisely so a host with an inbound port can
  finish wiring before it serves. The resolver now carries the stack's declared ids
  (`AgentRuntimeResolver.Builder.declaredIds(Set)`) and refuses them, checked *before* the
  discriminator rule so the message names the real problem — the agent is configured, the stack is
  simply not serving yet. An atomic register-if-absent was deliberately **not** added: the three
  registrants are separated in time, not racing, and `registerIfAbsent` would have made the resolver's
  second runtime win while turning the stack's own registration into a silent no-op. What was missing
  was not atomicity but **authority**.
- **`aimon.llm.api-key` no longer prints itself to `/actuator/env` (security).** Two deliberate
  opt-ins stand in front of the leak — an operator has to expose `env` or `configprops`, then move
  `show-values` off its `NEVER` default — but past the second the key came out in full, at the one
  moment an operator was most likely to assume Boot's usual discretion about credentials was still in
  effect. It was not: `Sanitizer` masks everything while `show-values` is `NEVER` and then applies
  only the `SanitizingFunction` beans the application published, and **Boot 3.x publishes none**
  (`ifLikelyCredential()` is a helper for writing one, not a default that is already running).
  `AimonObservabilityAutoConfiguration` now publishes `aimonSanitizingFunction` in a nested
  `@ConditionalOnClass(SanitizingFunction.class)` branch. It matches Boot's own word list because the
  two endpoints spell keys differently (`/env` reports the source's name — `aimon.llm.api-key`, or
  `AIMON_LLM_API_KEY` when bound from the environment, which is why the prefix check accepts both
  separators — while `/configprops` qualifies the serialized field, `aimon.llm.apiKey`), matches
  **suffixes** rather than substrings so `aimon.budget.max-tokens` stays readable, and backs off by
  bean *name*: sanitizing functions compose, so a type-level `@ConditionalOnMissingBean` would have
  withdrawn this one the moment an application registered a function for its own properties, un-masking
  the API key as a side effect of an unrelated decision. Scope stops at the `aimon` prefix.
- **"This layout cannot be walked" stops arriving as "this directory is empty."**
  `ClasspathResourceTreeWalker` walks `file:` and `jar:`; anything else — a servlet container's `vfs:`
  or `wsjar:`, a `jlink` image's `jrt:` — it answered with `List.of()`, which is also how it answered
  "this directory really is empty". So an application packaged in an unsupported shape read
  `Bundled skill 'commit' has no files under '…'; skipping` about skills sitting right there in its
  archive. `BundledSkillMaterializer` now branches on the layout before the content and names the
  protocol; the "has no files … skipping" line survives for the case it was always meant for. The
  anchor fallback (fat JARs repackaged without directory entries) had the same defect one step
  further in — it checked `"jar".equals(protocol)` and returned empty for everything else, which is
  **worse**, since the archive demonstrably holds the anchor. A `file:` anchor still yields an
  enumerated empty result, deliberately: on an exploded class path a populated directory resolves
  directly, so nothing was hidden from the walk. `ClasspathAgentBundleLoader.hasBundledContent` keeps
  its behaviour — it is a best-effort probe picking between two log levels, runs only after
  `getResources` found nothing, and the walker has already warned.
- **`AimonDocumentedPropertiesTest`'s walker descended one level into a `Map` and stopped**, so a
  `Map<String, Map<String, String>>` left `aimon.credentials.jira.password` unknown and the guard
  would have reported a real key as a typo — the exact inversion of its purpose. It now descends per
  level.
- **A failed GridFS write no longer destroys the file it was replacing.** `write(...)` deleted the
  existing revisions and *then* uploaded, so any failure in between — a dropped connection, a cap
  refusal, a killed process — left the path with nothing at all. It now uploads first and retires the
  previous revisions only once the new one is durable, and the retirement list is snapshotted
  **before** the upload: taken afterwards it would include the revision just written and delete it.
  Reads resolve newest-first (`uploadDate` descending, `_id` descending as the tiebreak, because
  `uploadDate` is millisecond-granular and two writes can land in the same millisecond), so the
  window between the two steps serves the new content, never a missing file. The bytes are copied
  through an explicitly opened upload stream rather than `uploadFromStream` because that helper wraps
  everything, including our own `InsufficientStorageException`, in `MongoGridFSException`.
- **Listing and usage stopped reading the whole bucket.** Every `list` / `listRecursive` / usage call
  scanned all files and filtered in the JVM — for the root, with no filter at all. Queries are now a
  half-open range on `filename` (`[prefix, prefix-with-last-char-incremented)`), which an index can
  serve, and they project only the fields the caller needs. `getUsageSummary` folds size and count
  **server-side** with `$group` instead of streaming every document back to add them up.
- **The GridFS default database name is `aimon`, not `at/aimon`.** MongoDB rejects `/` in a database
  name, so `FileSystemFactory.createFromEnvironment()`'s GridFS branch failed on every run that had
  not set `FILESYSTEM_MONGO_DATABASE` — the default was not a poor choice, it was unusable. Nothing
  is stranded under the old name for exactly that reason: no database could ever be created with it.
- **GridFS says which kind of thing is at a path instead of failing generically.** A trailing `/` on a
  *file* path is now rejected up front (`InvalidPathException`) because that shape is how a directory
  marker is stored, and reading a directory, writing over one, or listing a file each raise the
  exception that names the mismatch rather than surfacing as a missing file. **A filename ending in
  `/` is reserved by AIMON in a bucket AIMON manages** — the constraint is documented, not enforced
  against a foreign writer, which is why a document of that shape is read back as a directory.

#### Behaviour changes in the five tools migrated to `GenericTool`

Each is a contract that was already written down and is now enforced.

- `Grep` — `-A`/`-B`/`-C`/`head_limit`/`offset` are `integer` rather than `number`, and an
  `output_mode` outside its enum is rejected instead of falling through to `formatFilesWithMatches`.
- `Workflow` — `strategy` and `mode` match their declared enums exactly rather than being lowercased
  first; an explicitly empty `mode` is rejected instead of read as `foreground`.
- `RunSandbox` — each `commands[]` element is a closed object with typed fields, so a misspelled key
  or a non-string `argv` element is rejected with its position. A missing `commands` is a `required`
  violation; the empty-list case still returns "Commands must not be empty".
- `CopyToSandbox` — same shape; a missing `source` names its index (`files[1].source`).
- `Browser` — the schema is closed, and an `action` outside the 14 declared values is rejected at
  binding instead of reaching `UNKNOWN_ACTION`. Per-action required parameters surface unchanged.

#### Known issue, not introduced here

`SkillBackedCommandExecutor` builds its `SkillExecutionRequest` without a `renderContext`, so on the
slash-command path **every** `AIMON_*` variable substitutes `""` with a warning.

---

### Scope notes

Two things the session-first restructure deliberately did **not** do, worth knowing before citing it:

- **Record-backend fencing CAS is out of scope.** Stage 3's re-proof of lease authority through
  `SessionStore.records()` is a *steady-state* guarantee: the sub-millisecond window between the
  re-proof and the delegated write is not closed. Do not cite this work as a complete answer to lease
  expiry or split-brain.
- **`deleteSession` still takes two exclusion devices in sequence** — the node-local turn gate, then
  the backend lease. Confirmed as final rather than provisional: the two have different lifetimes
  (session-spanning lease vs. turn-spanning gate) and different scopes (cross-node authority vs.
  intra-node serialization), and the single writer serializes *record writes* only — two concurrent
  turns on one node would still both run the ReAct loop.

And two from the starter register:

- **Classpath agent bundles support exactly two deployment shapes**, now written down in the embedding
  guide (§2.4) rather than inferred: an executable JAR (`jar:`) and an exploded directory (`file:`).
  A traditional WAR under a servlet container (`vfs:`, `wsjar:`), a `jlink` runtime image (`jrt:`) and
  GraalVM native image are **not** supported — the first three because `ClasspathResourceTreeWalker`
  cannot walk them, native image because the resources are not in the image unless the application
  registers them itself. Spring Boot's own executable WAR *is* fine: it is a `jar:` URL wearing a
  different extension. What an unsupported shape looks like from outside is the log line named in
  Fixed above, so the guide quotes it.
- **The legacy `.aimon/commands/*.md` guard stays a hard stop**, not a warn-and-continue. It fires
  when a directory that once held markdown slash-commands still holds them after the format moved to
  skills; continuing would start an application whose operators believe commands are registered when
  none are. Only the message changed (it now names the file it found and the directory to move it to).

---

### Rename maps

Moved out of this file: **[`docs/migration/rename-maps.md`](docs/migration/rename-maps.md)**.

A changelog entry is written once and never revisited; that table is consulted long after the release
that produced it and grows whenever another rename lands. Leaving it here would have buried it under
a version heading the moment this block shipped.

### Not changed (deliberately frozen)

Moved out of this file: **[`docs/migration/frozen-names.md`](docs/migration/frozen-names.md)**.

Every rename in this release stops at the Java symbol boundary. **No data migration and no
rolling-upgrade coordination is needed** — a node running the new jars interoperates with the stored
state and the live traffic of a node running the old ones. The document lists exactly what that
covers, and it is a standing contract rather than a record of this release.

### New architecture rules

Fifteen rules are now enforced by tests; the complete index is
**[`docs/overview/architecture.md` §9](docs/overview/architecture.md)**. New in this release:
`SessionNamingArchitectureTest`, `SessionRecordSoleWriterArchitectureTest`,
`TurnVocabularyArchitectureTest`, `BuiltInToolSchemaArchitectureTest`, `ExternalSchedulerWiringTest`,
`YamlParserInstanceArchitectureTest`, `AimonDocumentedPropertiesTest`, `ReleaseGateMatchesCiGateTest`,
`PublishedModuleApiScopeTest`, the `at.aimon.core.config.hook` isolation rule and
`PackageDependencyArchitectureTest.noNewTopLevelCorePackageCycles`.

### Build, CI and the release gate

- **`integrationTest` is now gated in both places.** `@Tag("docker")` covers 68 test classes and ran
  in neither CI nor `scripts/release.sh`. For `aimon-filesystem-{gridfs,s3}`,
  `aimon-session-{redis,postgres,mongodb}` and `aimon-memory-{postgres,mongodb}` those are the *only*
  tests there are, so seven published artifacts had never had their sole verification executed by any
  automation. CI gains a parallel `integration` job; the release gate becomes
  `checkAll integrationTest` on one line (`ReleaseGateMatchesCiGateTest` reads the first `$GRADLE`
  invocation after the section marker, so a second line would be invisible to it). **A release now
  requires a running Docker daemon**, checked in pre-flight rather than discovered minutes later, and
  as a hard failure — a gate that skips itself when the daemon is absent is the strictest-looking
  setup with the weakest enforcement.
- **`packagingTest` followed it, on a different argument.** `@Tag("packaging")` is four methods in one
  class and ran nowhere. Unlike the seven backends above it is not the only verification any module has;
  it is the only one that can *see a fat jar*. Packaging turns resource lookup into jar-entry enumeration
  — the code casts a `URLConnection` to `JarURLConnection` — and when that breaks the skill list comes
  back silently short instead of failing, which is a regression this framework has actually shipped.
  Every other test in the build runs off a directory class path, where that path does not exist. It is a
  step in the `build` job rather than a job of its own: a separate job buys a failing check that names the
  tier and costs a second JDK and a second full compile, which is worth paying for a Testcontainers tier
  running for minutes and not for one adding **57 seconds** from a cleaned sample build directory (6 warm).
  The task builds both fat jars itself — Boot's current loader and its classic one — so nothing has to run
  first. The release gate line becomes `checkAll integrationTest packagingTest`.

  This split an open backlog item in half. It had held `playwrightTest` and `packagingTest` together on
  the single ground that both were opt-in, but opt-in is a state rather than a property: one needs browser
  binaries installed and the other needs nothing. The item's own body had written both reasons into one
  sentence, and its three review triggers were already 1:1:1 across the two tiers — a trigger list that
  splits cleanly is a sign the item is two. `playwrightTest` stays outside both gates, now as its own
  item, and its install cost is still unmeasured.
- **`JavaCompile` workers pin their own heap.** A worker daemon inherits `JAVA_TOOL_OPTIONS` from the
  shell, and Gradle's own smaller `-Xmx` on the command line overrides the inherited `-Xmx` but not
  the inherited `-Xms`; `JAVA_TOOL_OPTIONS=-Xmx4g -Xms1g` therefore killed the worker before javac
  started, with `Initial heap size set to a larger value than the maximum heap size` and nothing wrong
  with the source. The `Test` block already pinned against exactly this; the compile side did not.
  Invisible in CI, which has no such variable — it only ever hit a contributor's machine.
- **Two races in `DefaultWorkflowRunnerBackgroundTest`, and a wrong diagnosis of the first one.**
  Running the gate — rather than reading it — turned up `run run:cancel-proof did not reach KILLED (was
  COMPLETED)` on a run that was green the next time, its evidence overwritten by the passing re-run and
  recovered from the Gradle daemon log.

  The first fix was aimed at the wrong thing. The stub's wall-clock escape hatch was 5,000 ms, the same
  budget the test's own state poller uses, so it could fire mid-assertion; that was real, and widening it
  to 60,000 ms is kept. But it was not this failure. The actual cause is that the stub **returned a value**
  when it observed cancellation, giving it two exits where a real cancelled subagent has one, and
  `DefaultWorkflowRunner.finalizeRun` deliberately lets *a normal completion win over a concurrently
  arriving stop* — the handle observably delivered a result. So whenever the returning exit won the race
  against the worker's interrupt, a stopped run settled COMPLETED. The runner was never wrong; the stub
  was. It now unwinds by throwing, which removes the race instead of narrowing it, and an expiring valve
  throws too rather than impersonating a run that finished on its own. Confirmed by forcing the
  cooperative exit deterministically: both original messages reproduce verbatim, on both tests.

  Fixing that exposed a second race in the same test, at `assertThat(runner.stop(id)).isFalse()` — about
  one run in five. `finalizeRun` writes the store's terminal state **before** removing the registry entry,
  deliberately, so that a re-submit arriving in between sees a terminal run rather than a missing one. The
  poller reads the store, so a test that has just watched KILLED appear can still be inside that window,
  where `stop` correctly reports the entry it can still see. What the test means — a finalized run stops
  being stoppable — is eventual, so it now waits for that and re-checks. Twenty consecutive runs clean.

  Both tests carried a byte-identical copy of the stub, so each defect existed twice; they now share one
  helper.
- **A third flaky test, and the pattern the three of them make.**
  `CompactionConcurrencyScenarioHTest` failed on CI with a bare `TimeoutException` at `first.get(2, SECONDS)`
  while every other test in the run passed. The number was the problem: two seconds for work that takes
  milliseconds once unblocked, in a class that forks blocking work onto a cached pool while the rest of the
  suite runs at `maxParallelForks = cores / 2`. The assertion budget is now 10 seconds — generous on purpose,
  because it exists to fail a hung test rather than to measure anything, so a passing run pays nothing.

  This file also had the defect the entry above describes — a different file, the same mistake — in its
  second form: `BlockingEngine`'s own release
  valve was **also** two seconds, so the stub's safety net shared a number with the assertions waiting on it
  and could fire mid-assertion, reporting "blocking engine never released" about a release the test had not
  reached yet. It is now 60 seconds. That is the shape all three flakes share — a safety valve indistinguishable
  from the thing it guards — and it is worth naming, because in each case the failure named the assertion
  rather than the cause and each was green on the next run.

- **The build-script-reading guards now declare their inputs.** `PublishedModuleLoggingBindingTest`
  reads every module and shared build script and had no `inputs` declaration, so it could report
  UP-TO-DATE across exactly the edits it exists to catch. Found by suspecting the new
  `PublishedModuleApiScopeTest` of passing vacuously, deliberately breaking a module, and watching
  the test not run at all. `:aimon-core:test` now takes the module build-script tree, the `buildSrc`
  script plugins and the root build script as inputs.
- **The coverage report reads every test tier, not just `test`.** JaCoCo's default execution data is
  `test.exec` alone, and for seven published modules that described a run their tests were excluded
  from: `aimon-memory-{mongodb,postgres}` measured **0.0%** line, `aimon-session-postgres` 6.2%,
  `aimon-filesystem-gridfs` 11.0%, `s3` 12.9%, `aimon-session-redis` 19.8%, `aimon-session-mongodb`
  28.0% — every one a module whose tests are `@Tag("docker")`. With the docker tier folded in they are
  84.6 / 77.2 / 81.8 / 84.2 / 82.7 / 82.1 / 75.1%. They were not under-covered, they were unmeasured,
  and those were the numbers any coverage floor would have been set against.

  Ordering is `mustRunAfter`, not `dependsOn`: producing a report must not start requiring a Docker
  daemon or a fat jar, so `./gradlew test jacocoTestReport` still works with neither and still says
  0.0% for those modules — correctly, since nothing measured them in that invocation. Gradle 9 fails
  the build if the relationship is left undeclared entirely. One caveat stays in the build script:
  stale `build/jacoco/*.exec` from an earlier run is folded in too.

- **CI builds that report in a third job, because no other job can.** `build` and `integration` run in
  parallel with separate workspaces, so a report generated inside either one sees a single tier — and
  it was generated inside `build`, the `test`-only tier, so the XML CI uploaded carried exactly the
  misleading numbers above rather than the merged ones. Each job now archives its own `.exec` and a
  `coverage` job needing both restores them before running `jacocoTestReport -x test`. The exclusion is
  what keeps this a tail job rather than a third test run: `dependsOn(test)` exists so that reading
  `test.exec` is a declared dependency, and dropping it reuses the data the other two jobs already
  produced, compiling only main classes. A tar rather than the bare glob, because `upload-artifact`
  roots an artifact at the longest common prefix of what it matched — `modules/` with several modules,
  but silently `modules/<one>/build/jacoco/` the day only one matches, which would restore the file to
  the wrong path and lose that module's coverage without failing anything.

  No `if: always()` on the job, deliberately: a coverage number means something only when every tier
  feeding it actually ran, and the floor this job is meant to carry next would otherwise fail with
  "coverage dropped" when the real cause was one red integration test — naming the wrong culprit, which
  is the very thing the two-job split exists to avoid. The price is that a red run produces no coverage
  XML. This was the last thing blocking that floor
  ([`docs/backlog/architecture-review-open-items.md`](docs/backlog/architecture-review-open-items.md)
  R-3), the missing measurement having been settled earlier in this block.

- **Coverage now has a floor, frozen from what CI measured.** JaCoCo produced reports and nothing else: no
  `jacocoTestCoverageVerification` rule existed anywhere in the build, so coverage could fall and nothing
  broke. `gradle/coverage-baselines.properties` now carries a per-module line floor — data rather than code,
  so moving a number is a one-line diff and the whole frozen set is readable on one page. Same shape as
  checkstyle's error budget and `BASELINE_TOP_LEVEL_CYCLES`: nothing here is a target, and raising a value is
  a separate decision from not letting it fall. It runs in the `coverage` job after the report (when the
  floor trips you want the report that explains it, and that upload is `if: always()`), and in the release
  gate, which is not optional — a task that can fail a build cannot sit in `ReleaseGateMatchesCiGateTest`'s
  reporting-only exemption without lying. The gate pays nothing extra: every tier already ran in that one
  workspace.

  **The values are `floor(measured) - 1`, and the -1 came from data rather than caution.** Plain flooring
  left four modules 0.1pp of headroom and three more 0.2pp — while the same commit measured 81.8% locally
  and 81.4% on CI for `aimon-session-postgres`. A floor with less room than the noise is a gate that flakes,
  and a flaking gate gets excluded, which is worse than no gate: exactly what this item was opened to avoid.
  Headroom is now 1.1-2.0pp.

  Checked in three directions rather than assumed: without the docker tier, `filesystem-gridfs`,
  `memory-mongodb` and `memory-postgres` fail loudly (0.10 / 0.00 / 0.00) — the very situation that made the
  old numbers meaningless; with every tier's data, all 23 modules pass; and raising `aimon-core` from 86 to
  88 fails with *"lines covered ratio is 0.87, but expected minimum is 0.88"*. What JaCoCo's message cannot
  say — that a zero means an unrun tier rather than a collapse — is in the task's `description` instead.

- **Three unused dependencies removed from published POMs** — `org.commonmark:commonmark` from
  `aimon-core` (zero imports anywhere, catalog entry deleted with it), `org.yaml:snakeyaml` from
  `aimon-cli`, which had no main-source import and did not even pin the version it named
  (`dependencyInsight` resolves snakeyaml to 2.3, not the declared 2.2, and `jackson-dataformat-yaml`
  brings it transitively regardless), and `jackson-databind` from `aimon-knowledge-opensearch`, which
  is the same shape a third time: no `com.fasterxml` import in the module at all, `opensearch-java`
  brings it through `jackson-bom`, and the catalog's 2.16.1 loses to that 2.17.0 in conflict
  resolution. It was held back on the expectation that `integrationTest` would settle it — that
  module has **zero** docker-tagged tests, so the task runs nothing there and the wait would never
  have ended.

### Documentation

- **This file stopped being two things at once.** It was 1,865 lines, and the diagnosis is not the one
  the size suggests: the three released sections are 23–30 lines each, while `[Unreleased]` alone held
  1,779. What had accumulated in it was not release history but **lookup tables** — text that is
  consulted long after the release that produced it and grows whenever another rename lands. A change
  record is written once and never revisited; the two are on different clocks, which is the same
  criterion `docs/README.md` already uses to separate `design/` from `plan/`. Left here, the first
  release to ship would have buried both tables under a version heading and turned the twelve files
  citing them (eight documents, counting each translation pair once) into "go read the 0.2.0
  section", getting worse with each release. So:
  [`docs/migration/rename-maps.md`](docs/migration/rename-maps.md) (the old ↔ new lookup for both
  refactors) and [`docs/migration/frozen-names.md`](docs/migration/frozen-names.md) (what was
  deliberately *not* renamed, which is a standing contract rather than news). Every citation was
  re-aimed, `MAINTAINERS.md` now requires the mapping in the document rather than here, and this file
  is 1,534 lines with pointer stubs where the tables were.
- **`docs/overview/architecture.md` §9 — the rules the build enforces.** The changelog's "new
  architecture rules" list was a release-shaped slice of something that had no home: fifteen ArchUnit
  and wiring rules exist across three modules and nothing indexed them. Three of the fifteen live
  outside `aimon-core`, so reading `at.aimon.core.architecture` alone misses them — which is the note
  the section leads with.
- **Release bodies stop carrying dead links.** `.github/workflows/release.yml` cuts a Release body
  out of this file, and a relative link in that body resolves against the release page and 404s. The
  workflow already knew this for its fallback pointer but not for the body it extracts; two of the
  three released sections carry such a link and the unreleased block carries ten.
  `scripts/absolutize-release-links.py` rewrites them at the one point where the text leaves the
  repository, so the changelog can keep writing links relative — which is what renders on GitHub and
  what `scripts/check-doc-links.py` can verify.

- **An over-long section stops publishing nothing at all.** GitHub rejects a release body over
  125,000 characters with a 422, which fails the whole `gh release create` call — a worse outcome
  than the *missing* section the workflow already handles, since that one at least falls back to a
  pointer. This section is the case that found it: consolidating everything since `[0.1.11]` (0.2.0
  through 0.2.3 shipped without sections of their own) extracted to 156,902 characters, 32k over.
  `scripts/cap-release-notes.py` truncates on a line boundary, closes a code fence the cut landed
  inside, and appends a pointer to the full section. It runs *after*
  `absolutize-release-links.py`, never before — absolutization only ever grows the text.

- `docs/overview/scope-model.md` — four scopes plus execution units, with **Session** (durable) and
  **live session** (node-local) as separate tiers; the naming rules and the deliberate
  `Session*` / `LiveSession*` asymmetry; the reused `SessionApprovalStore` name; both renames and why.
- `docs/overview/glossary.md` — lifetime table, session-vs-live-session comparison, the five distinct
  meanings of "session", the approval-reach table, the `turn` / `iteration` / `execution` rule, and
  the note that "conversation" is not a retired word.
- **`docs/overview/context.md` and `docs/overview/deployment.md`** — the two views the docs did not
  have, added after weighing C4, ADR and arc42 and adopting **none of them as a framework**. Each
  already has a local equivalent that would be made worse by a second one: the `design/<domain>/`
  documents are ADRs in content but deliberately *living* rather than append-only, and
  `docs/README.md`'s placement table is already the single authority arc42's twelve sections would
  compete with. What the comparison did surface was two empty slots — arc42 §3 (context and scope,
  which is also C4 L1) and §7 (deployment view) — so those were filled directly. `context.md` states
  that the boundary is the **host application** rather than the framework, tables every external
  system with a *without it* column (only the LLM provider is mandatory; leaving the sandbox module
  out removes isolation rather than weakening it), and records why there is no container-level
  diagram: the core is not a deployment unit, and the component level is already enforced by ArchUnit
  rather than drawn. `deployment.md` draws the multi-node topology, the node-local/shared boundary,
  the path one turn takes through a non-holder node, and a ten-row cluster checklist. Diagrams are
  mermaid, which is what `mkdocs.yml` now registers as a `superfences` custom fence — Korean labels
  break ASCII box alignment (Hangul is two columns wide), and GitHub renders the same fences, so one
  source works in both places. `architecture.md` §1 gains a third column, *what it costs instead*:
  a table listing only what was gained is marketing copy, and that column is where arc42's quality
  goals belong. English translations ship in the same commit.
- `docs/getting-started/embedding-agent-in-application.md` now leads with the starter (hand-wiring is
  Appendix A) and covers the property tree, the four scopes, streaming, budgets, multi-agent and
  per-tenant runtimes, multi-instance deployment, two-phase shutdown, health and metrics; a
  line-by-line check against the code it describes fixed ten defects in the draft, five of which named
  API that does not exist. `README.md` points at the same path.
- `quartz-scheduling-web-deployment-guide.md` §6.3 / §7 describe the execution guard as an option an
  operator chooses rather than a defense that does not exist.
- Design records keep their filenames even when their vocabulary moved on
  (`agent-execution-context-rescoping.md`, `conversation-state-persistence-design.md`,
  `web-agent-session-manager-design.md`); `session-first-restructure-design.md` moved from
  `docs/design/backlog/` to `docs/design/implemented/`.
- `docs/migration/*` §5 covers the starter — what the autoconfiguration provides, which beans an
  application overrides and which it must not, and §5.3 the properties whose defaults changed.
- `llm-provider-development-guide.md` drops `isConfigured()` from the provider checklist and states
  what replaces it: a provider that cannot be configured fails at construction.
- Javadoc corrections where the text was wrong rather than merely thin —
  `AgentRuntimeRegistry.register` now names all three callers and states outright that no enumeration
  API exists (the previous wording implied one); `ScheduledTaskRepository.findByEnabledTrue()` says
  which lifecycle phase reads it; `SessionRouterBuilder#maxCachedSessions` no longer describes a
  per-session limit; `FileSystemFactory`'s package docs match what the factory actually constructs.
- `docs/design/filesystem/backend-contract.md` — a new design domain. What the `VirtualFileSystem`
  contract promises about directories, per-file caps and failure, **where the backends legitimately
  differ** (a table, because pretending they agree is how the divergence got there), why the contract
  test is its own module, and the four questions this work closed. Its §11 records what is still not
  true: S3 does not run the shared contract test, S3's streaming path buffers entirely in memory,
  nothing in the tree declares the GridFS indexes the range queries assume, and per-scope caps are
  not expressible through `ScopedVirtualFileSystem`.
- `docs/design/subagent/background-task-result-persistence.md` — the replacement for
  `getBackgroundTaskManager()`. It was registered as backlog first, on the grounds that the store,
  the codec, the meaning of `block=true` and a size policy have to be decided as one set; the
  document now records that set as decided and moves out of `backlog/` accordingly. Its §3 is the
  save-before-terminal-transition ordering contract, §4 the redefinition of `block=true` (a bounded
  poll, with the three behaviour changes that follow from no longer joining a future), §8 what is
  still missing — no Redis/Mongo/Postgres `TaskResultStore`, and an in-memory retention mismatch
  (1000 terminal tasks against 256 results) that can show a task whose result has been evicted.
- **`docs/backlog/architecture-review-open-items.md`** — the 2026-08-31 review's remaining items
  (R-1…R-7), and with it the review's plan document is deleted: a progress tracker is removed when the
  work ends, its reasoning going to `design/` or `project/` and its open items here. What the register
  is actually for is its §0: the plan's "why this was left out" table had **three of six rows wrong**.
  One said no seam existed for injecting an executor (there are seven, just none at the assembly
  layer), one drew the session-testkit duplication at 415 lines when the suites duplicate too and it is
  about 1,520, and one deferred a dependency question until `integrationTest` ran in a module that has
  no docker-tagged tests — a trigger that could never fire. That last one makes `docs/backlog/README.md`
  rule seven say one thing more: a trigger has to be checked for whether the event *passes through this
  item*, not merely whether it happens. Two `@Tag` censuses quoted from string searches were corrected
  the same way (68 docker-tagged classes, not 71; four `@Tag("playwright")` methods in one class, not
  five), and `ReleaseGateMatchesCiGateTest`'s own javadoc still said `integrationTest` was outside both
  gates after this release put it inside them.

---

## [0.1.11] - 2026-06-04

### Parallel tool execution (opt-in)

Independent, mutually-safe tools returned in a single LLM response can run concurrently. Gated by
model intent (multiple `tool_use`s) plus a framework safety check; results and `ToolUseStarted` events
stay in input order. Off by default.

- `Tool#getConcurrencyPolicy()` default method (`SEQUENTIAL`); `Read` / `Grep` / `WebFetch` declare
  `CONCURRENT_SAFE`. *(Renamed to `getConcurrencyBehavior()` in `[0.2.4]`.)*
- `ParallelToolDispatcher` / `DefaultParallelToolDispatcher` (bounded, lazily created daemon pool) +
  `ToolConcurrencyConfig` (disabled by default, `maxConcurrency` 4).
- Wired into `OrcaAgentExecutor` (`OrcaAgentExecutorFactory.withToolConcurrencyConfig`) and
  `DefaultSubagentExecutor` (`withParallelToolDispatcher`); both default to a sequential dispatcher.
- Both executors now inject a thread-safe `READ_FILES_KEY` set, which also activates `EditTool`'s
  read-before-edit guard in production for the first time.

See [docs/features/tool/parallel-tool-execution-guide.md](docs/features/tool/parallel-tool-execution-guide.md).

### Configurable SSRF protection for WebFetch

- `SsrfGuardConfig` (immutable): an `enabled` flag plus a host allow-list. `SsrfGuard` is secure by
  default; `SsrfGuardConfig.disabled()` opts out.
- `WebHttpClientFactory` registers `SsrfRedirectInterceptor` as a *network* interceptor, so DNS
  rebinding is re-validated against the actual connected address on every redirect hop.
- Allow-listed hosts are exempted at both the URL and connection level
  (`SsrfGuard.isAddressCheckExempt`).

---

## [0.1.10] - 2026-06-04

### Subagent executor parity with the main agent

`DefaultSubagentExecutor` was brought to parity with `OrcaAgentExecutor`.

- **Cancellation (bidirectional):** a per-execution `InterruptCoordinator`; the parent
  `CancellationSignal` cascades into it, the ReAct loop checks at the iteration head/tail (clearing
  the thread interrupt flag so pooled threads stay clean), and the signal is injected into the tool
  context. `TaskTool` declares `EXTERNALLY_TERMINATED`, forwards the parent signal, and registers a
  thread-interrupt terminator on the synchronous launch path.
- **ToolContext parity:** subagent tools receive `ENVIRONMENT`, `LLM_CALL_METADATA`,
  `ARTIFACT_COLLECTOR`, todo/current-tool-use ids, the tool-search registry, `PRINCIPAL`,
  `KNOWLEDGE_STORE` / `KNOWLEDGE_SCOPE` (sharing the parent's namespace), and module-supplied
  `ToolContextEnricher` keys.
- **PostTool hook isolation:** a PostTool hook failure no longer discards the real tool result.
- **Robustness:** LLM calls route through `LlmCallGateway` (retry/fallback); optional
  `CompactionGuard` and `ExecutionBudget` bounds are honoured.

Backward compatible — existing constructors are retained and new overloads were added.

---

## [0.1.9] - 2026-06-01

### Session state persistence (restart-durable)

Cumulative totals and the runtime budget override survive a restart as record side fields, alongside
the existing `compactionFailureCount` / `agentRef`. Design:
[docs/design/session/session-model.md](docs/design/session/session-model.md).

- **Two new side fields** — `sessionTotals` and `budgetOverride`. Excluded from the snapshot,
  preserved by `mergeFromSnapshot`, updated via atomic primitives.
- **`SessionStatePersistence` SPI** (+ `PersistedSessionState`, adapter, `NOOP`) through which the
  live session hydrates on open and writes through totals (end of turn) and the budget override
  (`setOptions`), best-effort. *(Deleted in `[0.2.4]`; the session now holds the record store
  directly.)*
- **`clearBudgetOverride()`** — explicit "revert to opener default" that erases the persisted
  override; **`AgentSessionOptions.withBudget(ExecutionBudget)`** budget-only copy helper.
- **Removed `AgentSessionStatus.lastCompletedTurn`** — the only `status()` field that silently reset
  on restart. `status()` fields are now cleanly either restart-durable or genuinely live-only.
- **Known limitations (L1–L4)**: `setOptions` persists only the budget; a single-node mid-turn queue
  is lost on restart (durable via the inbox on multi-node); a turn in flight at crash time must be
  re-submitted; `messageTimestamps` are not persisted.

## Earlier history

Pre-Phase-3 history is captured in `docs/design/*.md` design notes per subsystem. Future module
releases will tag versions here.
