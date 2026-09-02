# Changelog

All notable AIMON changes are recorded here. The format is loosely based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows
semantic versioning at the module level (each `aimon-*` module published to Maven
Central is versioned independently).

## [Unreleased]

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
  `CONCURRENT_SAFE`. *(Renamed to `getConcurrencyBehavior()` in `[Unreleased]`.)*
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
  (`setOptions`), best-effort. *(Deleted in `[Unreleased]`; the session now holds the record store
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
