# Design — #273: hooks and forks run in the execution environment by default (EE-48 · EE-52 · EE-81)

> Status: **IMPLEMENTED** (2026-10-18, issue #273). This is the design for backlog items EE-48, EE-52 and EE-81 exactly
> as it was approved in design review (three rounds; the third passed with seven non-blocking notes). The body below,
> §1–§9, is the body as approved, kept byte-exact, including its revision notes, its own line citations (as of
> `43babfae`) and its open-question numbers. It is written in English because that is how it was reviewed; the
> `design/` directory is not a translation target (`docs/project/documentation-guide.md` §5.1).
>
> One thing was added. §10 records where the implementation departed from this design, and why — including how the
> review notes were applied. The open questions in §9 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-109 ← F12,
> EE-110 ← §8, EE-111 ← §8 · Q1, EE-112 ← Q3), which is the source of truth for what is open. The specification this
> design extends is [`execution-environment.md`](execution-environment.md) §4.4 · §10 · §13; the record it follows is
> [`execution-environment-ee9-ee12-hook-environment.md`](execution-environment-ee9-ee12-hook-environment.md).

> **Revision 2 (after review-1).**
>
> - Blocking 1: a firing with no execution behind it stays on the host, through `HookContext.hasExecution()` and
>   `ExecutionOrHostShellActionExecutor` (§2.1, §2.2, §4.2a, §5, F1a, test 7).
> - Blocking 2: the isolated exec runs on the base environment (`isolatedFrom()` to its end), so an isolated workflow
>   branch under the local provider keeps today's cwd (§2.1, F1c, test 8).
> - Non-blocking findings adopted:
>   - `LinkageError` from `supports`;
>   - re-scanning `hooks/` at every firing;
>   - the note on what staging the user layer exposes;
>   - the changed meaning of the deprecated constructor and `shellExecutor(...)`;
>   - a prominent sandbox requirement in the CHANGELOG;
>   - the EE-52 note on what changes under today's sandbox;
>   - the asyncRewake WARN.

> **Revision 3 (after review-2).**
>
> - Blocking 1: one rule for runtime-runner subagent hooks. `subagentStart` goes to the host. `subagentStop` runs in
>   the fork's environment whenever the fork resolved one, and on the host only when it never did. §2.1, §2.2, §5
>   item 1, F1a and test 7 now agree.
> - Blocking 2: the false "same cwd as today" claim is corrected. The CLI's `hooks.json` commands now run in the
>   workspace root for every event: the host shell is rebuilt on that root. §5 item 2 lists the behaviour change,
>   and test 9 pins it.
> - Blocking 3: `AIMON_HOOK_DIR` has a single staging site, the executor that runs the command (after routing, on
>   the base environment). The directory travels on `ShellAction`. Test 10 covers default placement.
> - Non-blocking findings adopted:
>   - the `isolatedFrom()` provider contract;
>   - the actual scan cost;
>   - the list renumbered (no "3a");
>   - an `onSessionEnd` integration firing in test 4;
>   - the expected audit result in the departures section.

Base: worktree `herdr/hooks-run-in-execution-env` at `43babfae` (clean). Paths are under
`modules/aimon-core/src/main/java/at/aimon/core/` unless stated. aimon-sandbox was read at `main` `80b9998`
(read-only). Line numbers were checked on 2026-10-18.

## 1. Problem

Some hooks run somewhere other than the execution they are about. This shows as soon as a provider gives an
execution an environment other than the host.

- **EE-48.** Operator `hooks.json` `command` handlers always run on a host `LocalShell`. `AgentSetupFactory` wraps
  it at `:553` and `:1130` in `HostShellActionExecutor`, which ignores the firing context. Tools and skill hooks run
  in the environment's shell. So a `preTool` policy hook cannot see the workspace it is meant to police.
- **EE-52.** `subagentStop` runs with the *spawner's* environment, from
  `DefaultSubagentExecutionManager.fireSubagentStop` `:1049`. It does not get the environment of the fork it reports
  on.
- **EE-81.** A fork-mode skill's body is rendered before the fork exists, by `SkillTool:361` →
  `SkillRenderContextAccess.builderFor`. Its `${AIMON_SKILL_DIR}` is therefore staged in the spawner's environment,
  not the fork's.

The decided principle is: **a hook runs in the environment of the execution it is about, and the host is used only
when there is no execution.** For `hooks.json` there is an extra constraint. A command placed in the agent's
environment must not be steerable by agent state. It must run as a separate exec: no agent exports, no agent cwd,
and a PATH the agent cannot change.

## 2. Approach

### 2.1 EE-48 — where a `hooks.json` command runs

**Placement rule.** It is resolved once per handler at apply time in `HookRegistryApplier`:

| `"shell"` in the handler | event fires inside an execution | event fires outside (`onSessionStart`, `onSessionEnd`, `onConfigReload`) |
|---|---|---|
| absent | **environment** (isolated exec), except for one case (*) | **host** |
| `"environment"` | environment | refused (WARN + skip; none of the three is a guard event) — the existing `canRunOn` path |
| `"host"` | host | host |

(*) A command handler with no `"shell"` that declares `asyncRewake` stays on the **host**. The rewake replay fires
outside every execution: `DefaultRewakeFireListener` rebuilds the context with no environment. A placement in the
environment would make the current code silently drop the rewake spec. That is the WARN at
`HookRegistryApplier.toRewakeSpec`. Keeping these handlers on the host keeps them working as they do today, and
`HookRegistryApplier` logs a **WARN** for each such handler. The WARN says that the handler runs on the host because
of `asyncRewake`, and that adding `"shell": "host"` silences it. That way the leftover asymmetry is visible on the one
event where it matters most. The guide gets a row for it. Open question Q2 covers this.

"Fires inside an execution" is the existing single source of truth, `HookEventType.firesInsideExecution()`. The
applier maps the event name to its `HookEventType` constant: `PRE_TOOL` and `POST_TOOL` directly, the rest through
`DeclarativeShellHookBinding.forEvent(event).getEventType()`.

**Two executors in the applier, not one.** `HookRegistryApplier` keeps a host executor (today's `shellExecutor`) and
gains an environment executor. Each handler is constructed with the executor its placement picked. The environment
executor's default is a core class that needs no wiring, `IsolatedShellActionExecutor`. It takes the shell from
`HookContext.getExecutionEnvironment()` when the hook fires. That is why the change works for **any assembly**,
`HookHotReloadBootstrap` with any provider: no provider-specific code sits on the hooks.json path.

**A firing with no execution goes to the host (review-1, blocking 1).** An event can be in-execution by type and
still fire with *no execution behind it*. This happens in the shipped CLI. The agent-scoped workflow runner, which
background `Workflow` uses, spawns from a base `SubagentLaunchContext` that has no parent environment ("there is no
calling execution": `OrcaAgentRuntimeFactory.java:1141-1143`, `WorkflowTool.java:284-307`). As a result
`subagentStart`, and `subagentStop` when the fork never resolved an environment, fire with
`executionEnvironment(null)`. Today those `hooks.json` commands run on the host. The decided principle ("the host is
used only when there is no execution") says they still should.

The rule has to tell *no execution* apart from *an execution whose environment did not reach the hook*. The second
case is a wiring gap and still must not fall back to the host (§15). So the context states it positively:

- `HookContext` gets `default boolean hasExecution()`, which returns `true`.
- `SubagentStartContext` and `SubagentStopContext` get a builder flag, `spawnedOutsideExecution(boolean)`, and
  `hasExecution()` returns its negation. `DefaultSubagentExecutionManager` sets the flag exactly when
  `launchContext.getExecutionEnvironment().isEmpty()`. For `subagentStop` it sets it only if the result also carries
  no fork environment. A fork that resolved an environment had an execution, and its stop runs there.
- **One rule (review-2, blocking 1).** A firing is no-execution only when no execution it is about has an
  environment.
  - `subagentStart` from the agent-scoped runner is a no-execution firing, so it runs on the host.
  - `subagentStop` from the same runner is **not**, when the fork ran its loop. The runner forwards the provider
    (`OrcaAgentRuntimeFactory.java:1158`), `DefaultSubagentExecutor:408` always resolves an environment for an LLM
    fork, and with no parent the local provider returns its root environment (`:198`). The stop therefore runs in
    the fork's environment, at the workspace root.
  - `subagentStop` *is* a no-execution firing on the paths that never resolve one: an unknown subagent, a
    caller-ceiling refusal, a pool rejection, an exception thrown before `:408`, or a code behaviour, all spawned by
    that runner.
- The three out-of-execution contexts (`OnSessionStart`, `OnSessionEnd`, `OnConfigReload`) override `hasExecution()`
  to return `false`. That makes the dynamic answer agree with `firesInsideExecution()`. Placement for those events
  is still decided statically.
- The handler is placed by default (no `"shell"` key) on an in-execution event. In that case the applier wraps the
  two executors in a package-private `ExecutionOrHostShellActionExecutor(environment, host)`:
  - `context.hasExecution()` true: the call goes to the environment executor.
  - `context.hasExecution()` false: the call goes to the host executor.
  - `requiresExecutionEnvironment()` returns `false`, because the wrapper can always run somewhere, so `canRunOn`
    and `toRewakeSpec` treat it as the host executor would.
- Explicit `"shell": "environment"` gets the bare environment executor. The operator asked for the environment, so a
  firing with no execution is `notRun(NO_ENVIRONMENT)` with a WARN.
- A context that has an execution but no environment stays `notRun(NO_ENVIRONMENT)` and is never routed to the
  host. The only producers today are hand-built `ToolContext`s and third-party `CompactionGuard`s.

The builder audits the shipped CLI's other in-execution firing sites, so that each one either carries an
environment or is a genuine no-execution case marked with `hasExecution() == false`:

- `SingleToolInvoker` (pre/postTool, permission*): `EXECUTION_ENVIRONMENT` from the tool context, which
  `OrcaAgentExecutor:1082`, the forks and `RoutineExecutor:646` always set.
- `OrcaAgentExecutor` `onStart`/`onStop`: `scope.executionEnvironment`.
- `CompactCommand`.
- The compaction requests, whose environment is `Optional`: `DefaultContextEngine:187/336/547`,
  `RollingContextEngine:566`, `DefaultCompactionGuard:436`.

A site found without an environment is fixed by passing the environment it already has. It is not marked as a
no-execution case. An integration test pins the runtime-runner case (§7, test 7).

**Expected result of the audit.** The builder copies this into the build's departures section and corrects it where
the code disagrees, so the reviewer of the build can check each line.

| Site | Environment it passes |
|---|---|
| `SingleToolInvoker` | `ToolContextKeys.EXECUTION_ENVIRONMENT` from the spec's tool context |
| `OrcaAgentExecutor` `onStart`/`onStop` | `scope.executionEnvironment` (resolved at `:1082`) |
| `DefaultSubagentExecutor` hooks | `lc.executionEnvironment()` (`:1624`) |
| `CompactCommand` | the command `ToolContext`'s environment (`:149`, `:216`) |
| compaction (`DefaultContextEngine`, `RollingContextEngine`, `DefaultCompactionGuard`) | `request.getExecutionEnvironment()`. Expected to be present on every CLI path; to be confirmed. |
| `RoutineExecutor` | the environment resolved at `:646` |
| `DefaultSubagentExecutionManager` start/stop | the spawner's, or the fork's for stop, with the `spawnedOutsideExecution` rule |

**Integrity guard: the smallest SPI addition.** The SPI already has `ExecutionOptions.isHook()`. Under it a hook
command takes no session lock and saves no state, but it *may start from the session's current state*, and the
sandbox does exactly that. Its `ShellWrapper.INNER` sources `$__aimon_dir/state` for every mode, see `SandboxShell`
`:175` and `ShellWrapper` `:123`. Agent exports such as `BASH_ENV`, `LD_PRELOAD` or `PATH`, and the agent's cwd,
would therefore reach a policy hook. Two additions:

1. `ExecutionOptions.isIsolated()` and `Builder.isolated(boolean)`, carried by `toBuilder()`. The contract: run the
   command as a fresh shell of this environment would. It starts in the environment's root (unless
   `getWorkingDirectory()` names a directory) with the environment's **base** variables plus `getEnvironment()`. It
   reads no per-session state (cwd, exports), saves none, and takes no session lock.
2. `ShellFeature.ISOLATED_EXECUTION`: the shell honours `isIsolated()`.

`IsolatedShellActionExecutor` asks `shell.supports(ISOLATED_EXECUTION)` first. If the shell does not support it, the
command is **not run**, with `ShellHookOutcome.notRun(Unrun.ISOLATION_UNSUPPORTED, …)`. A guard event blocks unless
the handler has `failOpen`; an observer event only logs a WARN (the EE-51/EE-70 rules). A shell that keeps no state
between commands supports the feature trivially:

- `LocalShell` returns `true`.
- `WorkingDirectoryShell` already delegates `supports`.

**The default is documented.** For a `supports` that compares or uses a `Set`, a constant nobody declares reads as
`false`. A precompiled third-party shell with an exhaustive `switch` over `ShellFeature` and no `default` throws
`IncompatibleClassChangeError` on a new constant (`LocalShell.supports` has that shape). So `IsolatedShellActionExecutor`
wraps the `supports` call in `catch (RuntimeException | LinkageError e)` and maps it to `ISOLATION_UNSUPPORTED`, as
`SkillHookDirectory.export` does. Either way, a shell that does not know the feature keeps working for everything
else, and only isolated callers refuse it. They never run a policy hook from agent state without saying so. The
builder updates the in-tree `supports` implementations that switch over the enum, main and test
(`LocalShell`, `ControllableShell`, `BashToolTest`'s shells, …), so that they compile.

**Fixed PATH.** Core sets no PATH. The isolated exec gets the environment's base variables, and the agent cannot
change those:

- local provider: the JVM's environment, as today;
- sandbox: the profile's exec environment.

Core adds only the hook's own fixed `AIMON_*` variables (`SkillHookEnv`). Under the local provider the process
environment is the same as today.

**The isolated exec runs at the base environment's root, not in a branch's view (review-1, blocking 2).** A workflow
step with `isolate=true` runs in a `LocalIsolatedEnvironment`. Its shell is a `WorkingDirectoryShell` whose default
cwd is `.worktrees/{branchKey}/` (`LocalIsolatedEnvironment.java:98`, `WorkingDirectoryShell.java:42-51`), and the
local provider keeps a fork inside that branch. Running a `hooks.json` command there would change two things
compared with today's host run:

- relative script paths would exit 127, and on `preTool` that blocks every tool call;
- relative writes would be promoted into the project by `WorktreeMerge`.

The design therefore defines the isolated exec's "environment root" as the root of the **base environment**. The
base environment is the end of the `ExecutionEnvironment.isolatedFrom()` chain, and the context's own environment
when the chain is empty. In code: before choosing the shell, `IsolatedShellActionExecutor` walks
`env.isolatedFrom()` to its end and uses that environment's `shell()`.

- Under the local provider that is the `LocalShell(hostRoot)` of the root workspace. A root execution and an
  isolated branch therefore get the same cwd: the **workspace root**. That is not necessarily the cwd the CLI's
  `hooks.json` commands use today; see the next paragraph.
- The branch's own files stay reachable at `.worktrees/{branchKey}/…` relative to that root, exactly as they are for
  today's host run.
- `AIMON_HOOK_DIR` is staged by the same executor, into the same base environment, so the staged path and the shell
  agree (the single staging site: "Script staging" below).
- **A contract for providers.** A branch that declares its lineage through `isolatedFrom()` is a *view* of its base
  environment. Core runs isolated commands on the base. A future provider whose `isolate()` returns a genuinely
  separate environment must not declare lineage that way, or its branch hooks run in the parent. The `isIsolated()`
  and `isolatedFrom()` Javadocs, and the design record, both state this.
- The sandbox does not implement `isolate()`, so the chain is empty there and nothing changes.
- An environment that has no lineage (`isolatedFrom()` empty) is used as it is.

The `isIsolated()` contract wording states this: an isolated command starts in the environment's root. A branch
that declares its lineage is a view of its base environment, not an environment with its own root, and core runs
isolated commands on the base. Skill-declared hooks are not affected. They keep running on the context's own shell
(the branch view), as today.

**The CLI's cwd for `hooks.json` commands: the workspace root, for every event (review-2, blocking 2).** In the CLI
today the two roots can differ.

- **Host shell today.** It is `new LocalShell()` (`AgentSetupFactory.java:553`), so its cwd is the JVM's `user.dir`.
- **Workspace root.** The workspace root, and the cwd of the environment's `LocalShell(hostRoot)`, is
  `fileSystem.getWorkingDirectory()` (`:613-614`). That value is `getJarDirectory()` (`:1058`, `:1684-1696`): the
  jar's directory when the CLI runs from a jar, and `user.dir` when it runs from an IDE or Gradle classes.
- **They differ under the documented launch.** `java -jar modules/aimon-cli/build/libs/aimon-cli-*.jar`, run from
  the repository root (`README.md:159`).
- **The project layer is already read from the workspace root.** `setupHookHotReload` passes
  `projectRoot(fileSystem.getWorkingDirectory())`, so it loads `<workspace root>/.aimon/hooks.json` (`:1100`). A
  relative path in that file today resolves against `user.dir`, which is not where the file lives.

A provider-neutral "keep `user.dir`" rule does not exist: an environment has no notion of the JVM's cwd. A
CLI-only cwd override on the isolated exec would be meaningless under any other provider. So the design takes the
reviewer's second option and makes the rule uniform:

- In-execution commands run at the environment root (workspace root under the local provider).
- The CLI's host shell becomes `new LocalShell(Path.of(fileSystem.getWorkingDirectory()))`. Out-of-execution
  commands, `"shell": "host"` handlers, asyncRewake handlers and no-execution firings therefore use the **same**
  directory. One `hooks.json` never splits across two cwds, and relative paths resolve next to the project layer that
  declared them.
- **Behaviour change, listed in §5 and the CHANGELOG.** When the CLI is launched with `user.dir` ≠ the jar
  directory, every `hooks.json` command now runs in the jar directory (the workspace root, where the agent's `Bash`
  already runs) instead of `user.dir`. When the two are equal (an IDE run, `./gradlew :aimon-cli:run`, or launching
  from the jar's directory), nothing changes.
- **Pinned by test 9.**

The rest of this design says "workspace root" where revision 2 said "the project directory the CLI uses today".

**Script staging (reuses EE-50).**

- **`AIMON_HOOK_DIR` variable.** A `hooks.json` command can name the variable `AIMON_HOOK_DIR`. Its value is the
  `hooks/` directory next to the declaring file: `~/.aimon/hooks/` for the user layer, `<project>/.aimon/hooks/` for
  the project and local layers.
- **One staging site: the executor that actually runs the command (review-2, blocking 3).** The hook cannot know the
  route: an `ExecutionOrHostShellActionExecutor` decides it per firing. So the hook does **not** export
  `AIMON_HOOK_DIR`.
  - **Where the directory travels.** At apply time the applier attaches the layer's directory to the action itself.
    `ShellAction` gains an optional `HookScriptDirectory`: the host path, plus a read-only source over it (a
    `ReadOnlyLocalFileSystem`, as `PathSkillRepository` uses). It is set only when the command text contains
    `AIMON_HOOK_DIR`. Skill-parsed actions never carry one.
  - **`HostShellActionExecutor`.** Puts the host path into the variable.
  - **`IsolatedShellActionExecutor`.** After choosing the base environment (above), and on that same environment,
    re-scans the source with `StagedResource.scan`, calls `stage(...)`, and puts the returned path into the
    variable. The shell and the staged path therefore always belong to the same environment.
  - **`ExecutionOrHostShellActionExecutor`.** Only routes. The executor it picks does the export.
  - **`DefaultShellActionExecutor` (skill hooks).** Ignores the field. The parser never sets it there.
  - **Why the action carries it.** That keeps the `ShellActionExecutor.run` signature as it is.
  - **Why re-scan at every firing.** `LocalStaging` keeps serving the copy under a loaded key once that copy exists,
    and only a change to `hooks.json` triggers a reload. A resource scanned once at apply time would therefore
    silently run an old `guard.sh` after the operator edits it, while host placement runs the live file (review-1,
    non-blocking).
  - **Cost.** `StagedResource.scan` lists the directory and **reads and hashes every file**
    (`StagedResource.java:107`, the content key). That cost is paid on every environment-routed firing of a handler
    that names `AIMON_HOOK_DIR`, and so on every matching `preTool`/`postTool`. It is bounded by the staging limit.
    Handlers that do not name the variable pay nothing. If the build measures this as significant, the cheaper
    variant is a scan keyed on file metadata (`StagedResource.sizeOf` plus modification times) that re-hashes only
    when the metadata changed. The builder records which one shipped.
  - **Stage.** When the content is unchanged, `stage` is a marker check. The
  failure handling is EE-50's: a failed stage is `notRun(STAGING_FAILED)`, and the command is never run without the
  variable.
- **Host placement.** The variable is the host path itself, so the same command works with either placement.
- **When it is staged.** Only when the command text contains `AIMON_HOOK_DIR`. Other handlers stage nothing, so a
  local workspace gets no new `.aimon-staged/hooks-*` copies for handlers that do not ask.
- **What staging exposes.** A staged copy of the **user** layer's `~/.aimon/hooks/` lands in the project workspace's
  `.aimon-staged/`, and the model's file tools can read it there. The guide says so next to F12: do not keep
  credentials beside hook scripts you stage, or use `"shell": "host"`.
- **Fail clearly at load time.** If the command names `AIMON_HOOK_DIR` but the `hooks/` directory is missing or not
  a directory, the handler is inapplicable. On a guard event that throws `HookConfigParseException` and startup
  stops. Elsewhere the handler gets WARN + skip.
- **Fail clearly at fire time, for literal host paths.** At apply time the applier marks an environment-placed
  command whose text names a path inside a layer's `.aimon` directory. Literal forms: `~/.aimon/`, `$HOME/.aimon/`,
  `${HOME}/.aimon/`, the absolute layer directory, or a relative `.aimon/` that is not `.aimon-staged`. The mark
  becomes a hint in `DeclarativeHookOptions`. If such a command exits 126 or 127 (EE-66 "could not run"), the hint is
  appended to the reason: *"names host path X, which this execution environment may not have — use
  `$AIMON_HOOK_DIR/…` or `"shell": "host"`"*. Under the local provider those paths exist and the command runs as
  today. Nothing is rejected up front, because at load time core cannot know whether the environment is the host.

**Hosts keep the host shell.** The CLI keeps `hookConfigShell` and `TeardownPhase.HOOK_CONFIG_SHELL`. They now serve
only out-of-execution events, `"shell": "host"` handlers, the asyncRewake case, and firings with no execution behind
them.

### 2.2 EE-52 — `subagentStop` carries the fork's environment

`DefaultSubagentExecutor` already stamps every result it returns with `.withExecutionId(executionId)` (`:471`). It
also stamps the fork's environment: `SubagentExecutionResult.withExecutionEnvironment(ExecutionEnvironment)` and
`Optional<ExecutionEnvironment> getExecutionEnvironment()`. The value is a live handle, not a value, so it is
excluded from `equals`, `hashCode`, `toString` and `TaskResult.from`.

`fireSubagentStop` uses `result.getExecutionEnvironment().or(launchContext::getExecutionEnvironment)`. Some paths
leave the result without a fork environment, and fall back to the spawner's:

- a code behaviour (`SubagentBehaviorRunner` resolves none and runs in the spawner's environment, so that is the
  right answer there);
- an unknown subagent;
- a caller-ceiling refusal;
- an exception shaped by the manager;
- a pool rejection.

Sometimes neither the result nor the spawner carries an environment: a runtime-level runner spawned the fork and
the fork never resolved one (the paths listed above). Then the stop context is built with
`spawnedOutsideExecution(true)` (§2.1, "A firing with no execution goes to the host"), and a default-placed
`hooks.json` handler runs on the host. A fork that the runner spawned and that ran its loop has its own environment,
so its `subagentStop` runs there, at the workspace root under the local provider.

**What EE-52 changes under today's sandbox.** The fork's environment differs from the parent's only in its
`exec:{id}` shell state. The slot and the workspace are the same (EE-52's 2026-10-18 note). An isolated exec reads
neither state, so a `hooks.json` `subagentStop` handler sees no difference there. Only a skill-declared
`subagentStop` hook sees one: it is not isolated and now starts from the fork's `exec:` state. The EE-52 closure says
this, so that readers do not expect a visible change from `hooks.json` hooks.

**Teardown ordering, checked.** Core never closes a fork's environment, because `ExecutionEnvironmentProvider` has
no release method. `subagentStop` fires in `runResolvedSubagent` right after `execute(...)` returns. That is before
control goes back to `Task`, `SkillTool` or a workflow branch, and so before `WorktreeMerge` or any branch cleanup.
On the sandbox side the fork's `exec:{id}` shell directory is removed only by the idle sweep
(`SandboxConnectionCache.sweepExecShells`), which a just-finished fork has not reached. So the environment is open
when the hook fires. An isolated exec does not read the `exec:` state anyway.

`subagentStart` keeps the spawner's environment, because the fork's environment is not resolved yet. This is
documented on `fireSubagentStart`, in the hook docs, and in the EE-52 closure.

### 2.3 EE-81 — the fork's body is rendered in the fork's environment

Rendering is deferred to the one place where the fork's environment exists: just after `resolveExecutionEnvironment`
in `DefaultSubagentExecutor.execute` (`:408`).

- New functional interface `subagent.execution.ForkGoalRenderer`: `String render(ExecutionEnvironment forkEnvironment)`.
  It carries the skill, its arguments and the renderer. Other render fields (session id, principal) stay the
  spawner's, because only the staged directory was in question.
- `SubagentLaunchContext.forkGoalRenderer(...)` passes it to the manager, and the manager copies it into
  `SubagentExecutionRequest.forkGoalRenderer(...)`.
- In the executor: if a renderer is present **and** the fork's environment is not the same object as the parent's
  (`!=`), the goal becomes `renderer.render(forkEnv)`. Otherwise it is `request.getGoal()`. Under the local provider,
  whose `resolve` returns `request.parent()`, nothing is re-rendered or re-staged, so behaviour stays the same.
- Staging or rendering in the fork can fail with `StagingException`, `ExecutionEnvironmentUnavailableException` or
  a `RuntimeException`. The executor then returns a failure result ("Failed to stage skill 'x' in the fork's
  environment: …"), stamped with the execution id and environment. That happens before `onStart`, so no `onStop`
  fires. `subagentStop` still fires.
- `SkillRenderContextAccess` gets `builderFor(Skill, ToolContext, ExecutionEnvironment)`: the same as today but
  staging into the given environment. The current two-argument method delegates with
  `ExecutionEnvironmentAccess.require(context)`.
- `SkillForkExecutor` gets `default SkillForkOutcome fork(Skill, String goal, ForkGoalRenderer forkGoal, ToolContext)`,
  which delegates to the existing three-argument method. A custom fork executor that does not override it keeps
  today's behaviour (documented). `SubagentBackedSkillForkExecutor` overrides it. Both fork entry points call the new
  overload: `SkillTool:385` and `LlmSkillExecutor.executeFork` (the slash-command path). `LlmSkillExecutor` builds
  the renderer from `request.getRenderContext()` with `skillBaseDir` replaced.
- The spawner-rendered `goal` is still used for the `subagentStart` hook, the SUBAGENT span input and a code
  behaviour (which runs in the spawner's environment).

## 3. Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Keep `hooks.json` on the host and document it (EE-48 status quo) | The user asked for the opposite, and the decision is made. |
| Run `hooks.json` in the environment with `isHook()` only | Under the sandbox, `isHook` sources the session's state file, so agent exports and cwd steer the policy hook. That violates the integrity guard. |
| A new `ExecutionEnvironment.isolatedShell()` default method returning `shell()` | The default would *silently* give today's state-reading behaviour on any provider that does not override it, so a policy hook would run from agent state without anyone knowing. A per-call option plus a capability bit is smaller (one flag, one enum constant), follows the existing `isBackground`/`isHook` idiom, and lets core refuse instead of degrade. |
| On a shell without `ISOLATED_EXECUTION`, run non-isolated with a WARN | Same silent integrity loss. Fail-closed follows EE-51/EE-70. Q1 asks the maintainer to confirm. |
| Core pins `PATH` to a constant (e.g. `/usr/bin:/bin`) | Local behaviour would change, for example Homebrew tools vanish on macOS. With isolation, the base PATH is already out of the agent's reach. A pinned value would also have to be right for every provider. |
| A per-handler `env` / `path` key in `hooks.json` | Adds schema and scope nobody asked for. The project layer (`<project>/.aimon/`) is often agent-writable under the local provider, so a PATH key there is no stronger than the base PATH. Q3. |
| Detect host script paths and reject at load time | Load time cannot know whether a run will use the host (local) or not (sandbox). Rejecting would break the local provider, which must stay unchanged. |
| Auto-stage every path a command mentions | Parsing shell text for paths is unreliable. An explicit variable (`AIMON_HOOK_DIR`, by analogy with EE-50's `AIMON_SKILL_DIR`) is predictable. |
| A parse error for `"shell": "environment"` on an out-of-execution event | `HookHandlerSpec` does not know its event, and the applier already has the `canRunOn` refusal path. None of the three events is a guard, so WARN + skip loses no guard. |
| A tolerant reading of unknown `"shell"` values (WARN, keep the default) | A typo such as `"hots"` would silently move a handler to the environment. Unknown enum values in this file (`type`) already fail the parse, and `"shell"` follows that rule. |
| EE-52: a mutable holder in `SubagentExecutionContext` that the executor fills | The result-stamp precedent (`withExecutionId`) already exists, and every exit passes through it. A holder adds shared mutable state across the manager/executor boundary. |
| EE-52: resolve the fork's environment in the manager, before `subagentStart` | `subagentStart` would then get the fork's environment, against decision 2. Resolution would have to mint the fork's `ExecutionId` early (the sandbox keys the shell on `exec:{id}`), and the single resolution site would move. |
| Send every `NO_ENVIRONMENT` firing of a default-placed handler to the host | That cannot tell "no execution" from "an execution whose environment did not reach the hook". The second case is a wiring gap, and quietly using the host there is the §15 fallback. The positive `hasExecution()` marker keeps the two apart. |
| Infer "no execution" from the event and `getExecutionEnvironment().isEmpty()` inside the executor | Same ambiguity, made at a distance from the code that knows the answer (the manager). |
| Run a branch's `hooks.json` command in the branch view (`.worktrees/{key}/`) and list it as a behaviour change | Under the local provider it breaks relative script paths (127, so `preTool` blocks every tool call in the step), and `WorktreeMerge` promotes the hook's relative writes into the project. That contradicts "unchanged under the local provider". |
| Set `workingDirectory` from the base environment's `descriptor().workingDirectory()` and keep the branch's shell | A descriptor is prompt text and is not guaranteed to be a path the shell accepts. Using the base environment's own shell needs no such assumption. |
| EE-81: string-replace the spawner's staged path with the fork's in the rendered body | Fragile: the path could also appear in arguments or literal text. |
| EE-81: skip spawner staging in fork mode | The `subagentStart` goal and span input need text. With the providers we have, both paths are the same copy, so the cost is one marker check. See failure mode F9. |

## 4. Changes by file and module

### 4.1 Shell SPI — `shell/`

- `ExecutionOptions`: add field `isolated`, `isIsolated()`, `Builder.isolated(boolean)`, carried in `toBuilder()`,
  `defaults()` false. Javadoc gives the contract in §2.1 and its relation to `isHook()` (independent; the
  `hooks.json` path sets both).
- `ShellFeature`: add `ISOLATED_EXECUTION`.
- `VirtualShell` class Javadoc, "Who the command is for": add the third flag.
- `shell/impl/local/LocalShell.supports`: return `true` for `ISOLATED_EXECUTION` (it keeps no state between
  commands). Isolation already means its default working directory and the JVM environment.

### 4.2 Hook executors — `skill/hook/declarative/`

- `ShellActionRunner.run(...)`: new `boolean isolated` parameter, setting `.isolated(isolated)` next to `.hook(true)`.
- New `IsolatedShellActionExecutor` (public, final, no-arg). `requiresExecutionEnvironment() == true`. It gets the
  environment and shell the way `DefaultShellActionExecutor` does: move that lookup into a package helper shared by
  both. It checks `shell.supports(ShellFeature.ISOLATED_EXECUTION)`; without it, `notRun(ISOLATION_UNSUPPORTED)` with
  a WARN naming the shell class. A `RuntimeException | LinkageError` from `supports` is mapped the same way.
  Otherwise it calls `ShellActionRunner.run(..., isolated=true)`. It picks the shell, and stages `AIMON_HOOK_DIR`, on
  the **base environment**: `isolatedFrom()` followed to its end (§2.1, blocking 2).
- New package-private `ExecutionOrHostShellActionExecutor(environment, host)`. It routes on `context.hasExecution()`.
  `isShellSupported()` is true when both executors' are. `requiresExecutionEnvironment()` is `false`. The applier
  uses it for default-placed handlers on in-execution events (§2.1, blocking 1).
- `DefaultShellActionExecutor` (skill hooks): unchanged, so skill hooks stay non-isolated. `HostShellActionExecutor`:
  unchanged.
- `ShellHookOutcome.Unrun`: add `ISOLATION_UNSUPPORTED("the environment's shell cannot run an isolated command")`.
  Check `ShellHookVerdicts` and the deny-reason formatting, so that it reads as "could not run" (blocks on a guard,
  opens with `failOpen`).
- `DeclarativeHookOptions`: one new optional field, `hostPathHint` (a string). The script directory travels on
  `ShellAction` instead (review-2, blocking 3).
- New package-private `ConfigHookDirectory`. It is called **by the executors that run the command**
  (`HostShellActionExecutor`, `IsolatedShellActionExecutor`), after any routing, never by the hooks (review-2,
  blocking 3). It reads the `HookScriptDirectory` on `ShellAction` (`skill/hook/action/ShellAction.java`: new
  optional field, getter and constructor overload).
  - **Host executor.** Exports the host path.
  - **Isolated executor.** Re-scans the directory, stages it into the base environment that executor picked, and
    exports the staged path.
  - **Failures.** They follow `SkillHookDirectory`'s rules: `STAGING_FAILED` or `ENVIRONMENT_UNAVAILABLE`, and the
    command is not run.
- `SkillHookEnv`: constant `AIMON_HOOK_DIR`. Nothing is added to the stdin JSON. The hook builds that JSON before the
  route is known, so the variable is the only carrier.
- Where 126/127 become `COMMAND_NOT_FOUND`/`COMMAND_NOT_EXECUTABLE`, the hook appends `hostPathHint` to the reason
  when one is set.

### 4.2a Hook contexts — `hook/`, `hook/event/`

- `HookContext`: `default boolean hasExecution()`, which returns `true`. Its Javadoc says that `false` means no
  execution is behind this firing, which is different from an execution whose environment is missing.
- `SubagentStartContext` and `SubagentStopContext`: a builder flag `spawnedOutsideExecution(boolean)`, default
  `false`. `hasExecution()` returns its negation.
- `OnSessionStartContext`, `OnSessionEndContext` and `OnConfigReloadContext`: `hasExecution()` returns `false`.
- `SignalDetachedHookContext`, the view report hooks receive: delegates `hasExecution()`. Every other wrapper or
  view of `HookContext` gets the same check.

### 4.3 `hooks.json` — `config/hook/`

- `HookHandlerSpec`: a new `"shell"` property, bound as a raw string and parsed with
  `HookHandlerSpec.Shell.fromJson`. Enum `Shell { HOST, ENVIRONMENT }`; matching ignores case, like `Type.fromJson`.
  An unknown value throws `IllegalArgumentException`, which the parser turns into a `HookConfigParseException`, as
  with an unknown `type`. On a non-`command` handler, `"shell"` is also a parse error ("'shell' applies only to
  command handlers"). The getter is `Optional<Shell> getShell()`, and the property is serialised as `NON_NULL`.
  Unknown *keys* are still ignored (`@JsonIgnoreProperties(ignoreUnknown = true)` stays).
- `HookRegistryApplier`:
  - The constructor becomes `(ShellActionExecutor hostShellExecutor, ShellActionExecutor environmentShellExecutor,
    HttpActionExecutor, McpActionExecutor, Map<String,String> processEnv)`. The old four-argument constructor
    delegates with `new IsolatedShellActionExecutor()` as the environment executor, and is deprecated.
  - **Its meaning changes, and that is said where people will see it.** The executor an embedder passed to the old
    constructor, or to `HookHotReloadBootstrap.Builder.shellExecutor(...)`, now serves only the host-placed
    handlers. Those are the out-of-execution events, `"shell": "host"`, asyncRewake, and the no-execution firings.
    The deprecation Javadoc, the builder method's Javadoc and the CHANGELOG entry all say so.
  - `placementOf(spec, eventType)` implements the table in §2.1 and chooses the executor. The existing
    `isShellSupported`, `canRunOn` and `toRewakeSpec` checks run against the *chosen* executor.
    `register(..., executor)` and the two `DeclarativePre/PostToolHook` constructions pass it.
  - It attaches a `HookScriptDirectory` to the `ShellAction` when the command names `AIMON_HOOK_DIR` (layer
    directory from `MergedHookConfig`/`HookConfigSource`; `SKILL` entries are already skipped), and builds the
    host-path hint.
- `MergedHookConfig` / `LayeredHookConfig`: expose each layer's `.aimon` directory, if they do not already, so the
  applier can resolve `hooks/`. The builder checks what `describe(source)` already has.
- `HookHotReloadBootstrap.Builder`:
  - `shellExecutor(...)` keeps its name and becomes the *host* executor. Its Javadoc says what it now serves.
  - New optional `environmentShellExecutor(ShellActionExecutor)`, defaulting to `new IsolatedShellActionExecutor()`.
  - New optional `hookScriptRoots`, only if `MergedHookConfig` cannot supply layer directories. The builder decides.
  - The class Javadoc example and the placement paragraph are updated.
- `HookConfigLoader` Javadoc, "Loading policy": add the unknown `shell` value.

### 4.4 Subagents — `subagent/`, `subagent/execution/`

- `SubagentExecutionResult`: `withExecutionEnvironment` and `getExecutionEnvironment`, excluded from equality.
- `DefaultSubagentExecutor`:
  - `:471` becomes `.withExecutionId(executionId).withExecutionEnvironment(executionEnvironment)`.
  - The fork-goal rendering of §2.3 goes right after `:408`. The rendered goal feeds `buildDynamicSystemPrompt`'s
    neighbour `LoopContext.goal(...)` and the transcript instead of `request.getGoal()`.
  - The stage-failure result is stamped the same way.
- `SubagentExecutionRequest` and `SubagentLaunchContext`: an optional `forkGoalRenderer`.
- New `ForkGoalRenderer` (functional interface, `subagent.execution`).
- `DefaultSubagentExecutionManager`: `fireSubagentStart` sets `spawnedOutsideExecution(launchContext
  .getExecutionEnvironment().isEmpty())`. `fireSubagentStop` takes the result as an argument: it already does on the
  run path, and the rejection path passes `failure`. It sets the flag when neither the result nor the launch context
  carries an environment. `fireSubagentStop` also gains the result-environment fallback. The comment block at
  `:1015-1018` is rewritten to say Start = spawner's environment, Stop = fork's when one was resolved.
  `runResolvedSubagent` copies `forkGoalRenderer` into the request.

### 4.5 Skills — `tools/`, `tools/skill/`, `skill/fork/`, `skill/execution/llm/`

- `SkillRenderContextAccess`: the three-argument overload.
- `SkillForkExecutor`: the default four-argument `fork`. `SubagentBackedSkillForkExecutor` overrides it and sets
  `launchContext.forkGoalRenderer`.
- `SkillTool`: in fork mode, build the renderer
  `env -> renderer.render(skill, args, SkillRenderContextAccess.builderFor(skill, context, env).build())` and call
  the new `fork` overload.
- `LlmSkillExecutor.executeFork`: the same, from `request.getRenderContext()`. Use `RenderContext` with
  `skillBaseDir` swapped; add `RenderContext.toBuilder()` if it is missing, since it now has a builder but no
  `toBuilder`.

### 4.6 Assembly

- `aimon-cli` `AgentSetupFactory`:
  - `setupHookHotReload` passes the host executor and leaves the environment executor at its default.
  - The comments at `:551-552`, `:1079-1086` and `:1126-1131` are rewritten.
  - The provider stays the local one, so CLI behaviour is unchanged except where §5 says otherwise.
  - `hookConfigShell` is kept, but it is built as `new LocalShell(Path.of(fileSystem.getWorkingDirectory()))`, so
    that host-placed and environment-placed commands share the workspace root (§2.1, review-2, blocking 2).
    `createFileSystem()` therefore moves ahead of it (`:553` / `:559`).
- `aimon-bootstrap` `TeardownPhase.HOOK_CONFIG_SHELL`: Javadoc only (what the host shell still serves).
- `aimon-session-routing` `LiveSessionOpener` Javadoc: mention the default environment placement. No code change.

### 4.7 Docs (the builder writes them in Korean first, `*.en.md` where one exists)

- **Backlog register** `docs/backlog/execution-environment-open-items.md`: dated 2026-10-18 **닫음** paragraphs for
  EE-48, EE-52 and EE-81. Update the header counts. New items from §8, if adopted.
- **`docs/features/hook/hook-config-guide.md` and `.en.md`.** The "어디서 도는가" section (`:290`) gets the placement
  table, `"shell"`, `AIMON_HOOK_DIR`, the isolation contract, the asyncRewake rule, and new troubleshooting rows
  (`ISOLATION_UNSUPPORTED`, the host-path hint, unknown `shell` value, the asyncRewake-on-host WARN). It also gets
  the no-execution host rule, the isolated-branch rule (commands run at the project root), and a caution: a staged
  user-layer `hooks/` directory is readable by the agent, and staged copies are agent-writable (F12).
- **Other hook docs.** `docs/features/hook/hook-development-guide.md` and `.en.md`; `docs/references/hooks-specification.md`
  (no translation); `docs/features/subagent/subagent-development-guide.md` and `.en.md` (subagentStart/Stop
  environments); `docs/references/aimon-skill-extensions.md` (fork body `${AIMON_SKILL_DIR}`).
- **Design records.** Add §10.7 or an addendum to `docs/design/tool/execution-environment-ee9-ee12-hook-environment.md`
  (Q1 and Q3 resolved). Add a paragraph to `docs/design/tool/execution-environment.md` §4.4 for the fork-body
  rendering, and to §10/§13 for `hooks.json`. Either add a new design record
  `docs/design/tool/execution-environment-ee48-ee52-ee81-hooks-in-environment.md`, which is this design in Korean
  with a later departures section, or fold it into the EE-9/12 record. Precedent favours a new file; register it in
  `docs/design/README.md`.
- **`CHANGELOG.md [Unreleased]`.** A `### Changed (behaviour change): …` entry, see §5.
- **Doc checks.** `check-backlog-registers`, `check-doc-links` (new anchors), `check-translation-structure` and
  `check-translation-staleness`. Set `source_commit` to the pre-edit commit.

## 5. Data and interface shapes that change

```java
// shell
public boolean ExecutionOptions.isIsolated();            // + Builder.isolated(boolean)
ShellFeature.ISOLATED_EXECUTION

// hooks
public final class IsolatedShellActionExecutor implements ShellActionExecutor { public IsolatedShellActionExecutor() }
ShellHookOutcome.Unrun.ISOLATION_UNSUPPORTED
SkillHookEnv.AIMON_HOOK_DIR                               // env only, set by the running executor
ShellAction(String command, Duration timeout, HookScriptDirectory scripts)   // + Optional<HookScriptDirectory> getScriptDirectory()
HookHandlerSpec.Shell { HOST, ENVIRONMENT }; Optional<Shell> HookHandlerSpec.getShell()
HookRegistryApplier(ShellActionExecutor host, ShellActionExecutor environment, Http.., Mcp.., Map env)
HookHotReloadBootstrap.Builder.environmentShellExecutor(ShellActionExecutor)   // optional

// subagents / skills
@FunctionalInterface public interface ForkGoalRenderer { String render(ExecutionEnvironment forkEnvironment); }
SubagentExecutionResult.withExecutionEnvironment(ExecutionEnvironment) / Optional<ExecutionEnvironment> getExecutionEnvironment()
SubagentLaunchContext.Builder.forkGoalRenderer(..), SubagentExecutionRequest.Builder.forkGoalRenderer(..)
SkillRenderContextAccess.builderFor(Skill, ToolContext, ExecutionEnvironment)
default SkillForkOutcome SkillForkExecutor.fork(Skill, String, ForkGoalRenderer, ToolContext)
```

`hooks.json`, one handler:

```json
{ "type": "command", "command": "bash \"$AIMON_HOOK_DIR/guard.sh\"", "shell": "host", "timeoutMs": 5000 }
```

**Behaviour changes for the CHANGELOG.** These are visible changes, not compile breaks; the API changes are all
additive or deprecations.

1. `hooks.json` command handlers on in-execution events now run in the execution's environment, as an isolated
   exec, at the base environment's root. Under the local provider: same machine, same process environment, and one
   cwd for root executions and for isolated workflow branches. That cwd is the workspace root, not
   `.worktrees/{key}/`; item 2 covers how it relates to today's cwd. A firing with no execution behind it stays on
   the host. That is `subagentStart` from the agent-scoped workflow runner, which background `Workflow` uses, and
   that runner's `subagentStop` only when the fork never resolved an environment. A fork that ran has its stop in
   its own environment.
2. **The CLI's `hooks.json` commands run in the workspace root, for every event.** The workspace root is the jar's
   directory when launched from a jar, otherwise `user.dir`. Today the CLI runs them in the JVM's `user.dir`. Nothing
   changes when the two are the same (an IDE run, `./gradlew :aimon-cli:run`, or launching from the jar's directory).
   Under `java -jar modules/aimon-cli/build/libs/aimon-cli-*.jar` run from the repository root, relative paths in
   commands now resolve against the jar's directory. That is where the project-layer `hooks.json` is already read
   from, and where the agent's `Bash` runs.
3. A firing that *has* an execution but whose context carries no environment, such as a hand-built `ToolContext` or a
   third-party `CompactionGuard` that does not forward it, now means `notRun(NO_ENVIRONMENT)` where it used to mean a
   host run. Guards block. The shipped CLI has no such site after the audit in §2.1. The remedy is `"shell": "host"`.
4. **Required: aimon-sandbox with `ISOLATED_EXECUTION` support (the follow-up in §8), or `"shell": "host"` on each
   handler.** This goes at the top of the CHANGELOG entry, not in the list. An environment whose shell does not
   declare the feature gets `notRun(ISOLATION_UNSUPPORTED)`, and guards block every matching tool. No shipped
   assembly combines `hooks.json` with the sandbox today.
5. The executor an embedder passed as `shellExecutor(...)`, or through the four-argument `HookRegistryApplier`
   constructor, no longer serves in-execution handlers placed by default (§4.3).
6. An unknown `"shell"` value, or `"shell"` on a non-command handler, fails the parse: startup stops, and a hot
   reload keeps the previous config.
7. `subagentStop` hooks see the fork's environment (only different from before under a provider that gives forks
   their own environment).
8. A fork-mode skill body's `${AIMON_SKILL_DIR}` is the fork's staged path (likewise).

## 6. Failure modes

| # | Situation | Handling |
|---|---|---|
| F1 | An environment-placed handler fires and the context has an execution but no environment | `notRun(NO_ENVIRONMENT)`, as today's skill-hook path. A guard blocks (or `failOpen` passes) and an observer logs a WARN. No host fallback (§15). |
| F1a | A default-placed handler fires with no execution behind it (`hasExecution() == false`: a runtime-level runner's `subagentStart`, and its `subagentStop` only when the fork never resolved an environment) | Host executor, as today. With explicit `"shell": "environment"`: `notRun(NO_ENVIRONMENT)` + WARN. |
| F1b | An in-execution firing site in the shipped CLI sends no environment | Found by the §2.1 audit and fixed by passing the environment. It is never marked as no-execution to get the host. |
| F1c | A `hooks.json` command fires in an isolated workflow branch | Runs on the base environment's shell at its root (§2.1, blocking 2). Branch files are at `.worktrees/{key}/`. Relative writes land in the project root, as today, and are not promoted by `WorktreeMerge`. |
| F2 | The environment is unavailable, or `shell()` throws | `notRun(ENVIRONMENT_UNAVAILABLE)` with the environment's message (shared helper). |
| F3 | The shell lacks `ISOLATED_EXECUTION` | `notRun(ISOLATION_UNSUPPORTED)`. The WARN names the shell class and the remedies (upgrade the provider, or use `"shell": "host"`). |
| F4 | A shell declares `ISOLATED_EXECUTION` but does not actually honour it | Outside core's reach. That is a provider bug, and the contract Javadoc is the spec. The sandbox follow-up adds a test for it. |
| F5 | `AIMON_HOOK_DIR` named but `hooks/` missing at load | The handler is inapplicable: a guard event stops startup (`HookConfigParseException`), elsewhere WARN + skip. |
| F6 | `AIMON_HOOK_DIR` staging fails when the hook fires (over the limit, source changed, environment down) | `notRun(STAGING_FAILED)` with the `StagingException` message. The command does not run (EE-50's rule). |
| F7 | A command names a literal host path, in an environment without it | Runs and exits 126/127 (or another code). On a guard event 126/127 blocks (EE-66), and the reason carries the host-path hint. Any other exit code is read by the normal contract; a non-zero `preTool` exit still blocks or denies. |
| F8 | `"shell": "environment"` on `onSessionStart`, `onSessionEnd` or `onConfigReload` | WARN + skip through the existing `canRunOn` branch. |
| F9 | Fork-mode skill: spawner staging fails though the fork's would succeed | `SkillTool` fails as today ("Failed to stage skill"). This was accepted: same copy under today's providers. Q5. |
| F10 | Fork staging fails (EE-81 path) | Failure result before `onStart`; `subagentStop` still fires with the fork's environment; `SkillForkOutcome.failure(message)`. |
| F11 | The fork never resolved an environment (code behaviour, unknown subagent, pool rejection, exception) | `subagentStop` gets the spawner's environment, as today. If the spawner has none either, the firing is no-execution (F1a). |
| F12 | A staged copy of `AIMON_HOOK_DIR` is tampered with through the agent's shell after its first verification | Residual risk. `LocalStaging` verifies the content once per environment instance and then trusts the marker, and the copy lives in an agent-writable workspace. It is the same exposure skill hooks have (EE-37/EE-50), but higher stakes for operator policy. This design does not close it; §8 lists a new item, and the guides tell operators that `"shell": "host"` keeps the script out of the agent's reach. |
| F13 | An asyncRewake command handler with no `"shell"` | Host, with a WARN (§2.1 *). With explicit `"environment"`: today's WARN, rewake ignored. |
| F14 | The operator edits a script under `hooks/` without touching `hooks.json` | The next firing re-scans and stages the new content (§2.1). The old copy is not served. |
| F15 | A shell's `supports` throws on the new constant (an exhaustive `switch` compiled against the old enum) | Caught as `RuntimeException \| LinkageError`, becomes `ISOLATION_UNSUPPORTED` (F3). |

## 7. Test strategy (no aimon-sandbox)

**Fixtures (test sources only).**

- `SessionStateShell`, a `VirtualShell` decorator over `LocalShell` that **models the sandbox contract**.
  - A foreground command (neither `isHook` nor `isIsolated`) runs in real bash after sourcing a per-instance state
    file, and saves `export -p` and `pwd` back into it.
  - An `isHook()` command sources the state file but does not save.
  - An `isIsolated()` command neither sources nor saves, and starts in the root.
  - It declares `ISOLATED_EXECUTION`. A constructor flag can turn the declaration off, for F3.
- `SecondWorkspaceProvider`: the existing `placingTheForkIn` decorator from `SkillHookSkillDirIntegrationTest`.
  Moved into `OrcaRuntimeItSupport` if it is shared, plus a variant that wraps the environment's shell in
  `SessionStateShell` (a small `ExecutionEnvironment` delegate).
- `OrcaRuntimeItSupport.Options.hooksJson(String json)`: writes `<root>/.aimon/hooks.json` and wires
  `HookHotReloadBootstrap` against the node's hook registry, with a host `HostShellActionExecutor(new
  LocalShell(hostDir))`. `hostDir` is a temp directory that is *not* the environment's workspace, so where a command
  ran can be seen from the files it writes. The watcher can stay off, or be closed in `close()`.

**Acceptance tests.**

1. **`preTool` in a non-host environment.** The environment is a local provider on `workspaceB`, different from
   `hostDir`. The `hooks.json` handler is `pwd > where.txt; exit 0`. Assert `where.txt` is in `workspaceB` and not in
   `hostDir`, and that the guarded tool ran. A second case uses `exit 2` and asserts the tool is denied.
2. **The agent's export does not reach the hook.** The environment shell is `SessionStateShell`. Bash runs
   `export AGENT_SECRET=leak; cd sub`. The `hooks.json` `preTool` handler writes `[$AGENT_SECRET] $(pwd)` to a file.
   Assert `[]` and the root. **Control in the same test class:** a skill-declared `preTool` hook (non-isolated
   `isHook`) does see `leak`, which shows the fixture is not vacuous. A unit test in `IsolatedShellActionExecutorTest`
   asserts that the options passed have `isIsolated() && isHook()`.
3. **`"shell": "host"` opts out.** The same setup as 1 with `"shell": "host"`: the file is in `hostDir`.
   `HookRegistryApplierTest` asserts the chosen executor.
4. **Out-of-execution events stay on the host.** `onSessionStart` and `onConfigReload` handlers with no `"shell"`
   write to `hostDir`. The `onSessionStart` handler fires through `openLiveSession`, the `onConfigReload` one through
   the reloader. Closing the live session fires `onSessionEnd`, which must also land in `hostDir`, so all three
   out-of-execution events are covered end to end. A unit test asserts that `"shell": "environment"` on `onSessionEnd` is skipped with a WARN.
5. **`subagentStop` sees the fork's environment.** Fork placed on `workspaceB` through `placingTheForkIn`. A
   `hooks.json` `subagentStop` handler writes `pwd`, which must be `workspaceB`. A `subagentStart` handler writes its
   `pwd`, which must be the spawner's workspace (documents decision 2). Unit test in
   `DefaultSubagentExecutionManager…Test`: the stop context's environment is the one stamped on the result, and the
   spawner's when none is stamped (code behaviour, not-found).
6. **The fork skill body gets the fork's staged path.** Extend `SkillHookSkillDirIntegrationTest`, or a sibling L2
   test, with a fork-mode skill whose body is `Run ${AIMON_SKILL_DIR}/x`. Capture the fork's first user message from
   `ScriptedLlmClient`'s recorded request and assert it contains `workspaceB/.aimon-staged/…`, not the spawner's.
   Local identity case: when the fork's environment is the parent's, the renderer is **not** called (spy).
7. **Runtime-runner subagent hooks follow the one rule (review-1, blocking 1; review-2, blocking 1).** The
   CLI-shaped node is a local provider on `workspaceRoot`, the agent-scoped workflow runner, and `hooks.json` loaded
   through `hooksJson(...)` with the host shell on `hostDir` (≠ `workspaceRoot`, so the route can be seen). Each
   handler writes `pwd` to a marker named after its event.
   - (a) A background workflow through that runner, whose fork runs its loop: the `subagentStart` marker is in
     `hostDir` (no execution), the `subagentStop` marker is in `workspaceRoot` (the fork's resolved environment),
     and no `NO_ENVIRONMENT` WARN is logged.
   - (b) The same runner, aimed at an **unknown subagent** (the fork never resolves an environment): both markers
     are in `hostDir`.
   - (c) Explicit `"shell": "environment"`, in case (b): both handlers WARN `NO_ENVIRONMENT` and write no marker. In
     case (a): `subagentStart` WARNs and `subagentStop` writes its marker in `workspaceRoot`.
   - Unit tests:
     - `ExecutionOrHostShellActionExecutor` routes on `hasExecution()`.
     - The manager sets `spawnedOutsideExecution` exactly when the launch context has no environment (start), and
       when neither the result nor the launch context has one (stop). Pool rejection and code behaviour from the
       runner give `true`; a resolved fork gives `false`.
8. **Isolated workflow branch, local provider (review-1, blocking 2).** A workflow step with `isolate=true` makes a
   tool call guarded by a `hooks.json` `preTool` handler `bash .aimon/hooks/guard.sh`, a relative path whose script
   exits 0 and writes `pwd` to `audit.log`. Assert three things:
   - the tool ran, so the guard was not blocked by a 127;
   - `audit.log` is in the project root and not in `.worktrees/{key}/`;
   - after `WorktreeMerge` the root's `audit.log` is the hook's own (not promoted from the branch).

   This pins "unchanged under the local provider" for branches. A unit test for `IsolatedShellActionExecutor` gives
   it an environment whose `isolatedFrom()` is another environment and checks that the base environment's shell is
   called.

9. **The CLI's `hooks.json` cwd is the workspace root for both placements (review-2, blocking 2).** This is a
   unit test in `aimon-cli`.
   - It checks that `AgentSetupFactory` builds `hookConfigShell` with `getWorkingDirectory()` equal to the
     file system's working directory. Add a package-private accessor or a factory method,
     `createHookConfigShell(LocalFileSystem)`, to make that testable.
   - An integration case in `aimon-core` gives the host shell and the local provider the same root, `workspaceRoot`,
     which differs from the JVM's `user.dir`. The `preTool` handler `pwd > a` (default placement) writes `a` in
     `workspaceRoot`, and so does the `onSessionStart` handler `pwd > b` (host). One file, one cwd.
10. **`AIMON_HOOK_DIR` under default placement in a non-host environment (review-2, blocking 3).** The setup is
    test 1's: a local provider on `workspaceB`, with the host shell on `hostDir`.
    - The project layer has `.aimon/hooks/guard.sh`, which writes its `$0` and exits 2.
    - The `preTool` handler has no `"shell"` and its command is `bash "$AIMON_HOOK_DIR/guard.sh"`.
    - Assert three things: the tool is denied **by the script**, its reason text is present, and `$0` starts with
      `workspaceB/.aimon-staged/`. The script must not run from the host path or exit 127.
    - Same handler with `"shell": "host"`: `$0` is the host path under the layer directory.
    - Run under the agent-scoped runner's `subagentStart`, which routes to the host: `$0` is the host path.
    - This pins the single staging site: the variable always matches the executor that ran the command.

**Unit tests (more).**

- `HookHandlerSpec`: `"shell"` accepted values and their casing, an unknown value as a parse error, `"shell"` on
  `http` as a parse error.
- `HookRegistryApplier`: the placement table (each row: default in-execution → `ExecutionOrHostShellActionExecutor`,
  explicit `"environment"` → bare isolated executor, `"host"` and out-of-execution → host), and the asyncRewake rule
  (*) with its WARN.
- `IsolatedShellActionExecutor`: `supports` throwing `IncompatibleClassChangeError` gives `ISOLATION_UNSUPPORTED`.
- `ConfigHookDirectory`: editing a script between two firings stages the new content (F14).
- `ExecutionOptions`: `toBuilder` carries `isolated`.
- `LocalShell.supports`.
- `ConfigHookDirectory` is driven through the executors, not the hooks. `HostShellActionExecutor` gives the host path.
  `IsolatedShellActionExecutor` gives the path that `stage()` returned **for the base environment**: the test hands
  it a branch whose `isolatedFrom()` is another environment, and asserts that both `stage` and `shell` were called on
  the base. `ExecutionOrHostShellActionExecutor` delegates the export to the executor it picks. A staging failure
  becomes `STAGING_FAILED`. Also covered: the load-time missing directory.
- The host-path hint in 127 reasons.
- `DefaultSubagentExecutor`: a renderer that throws `StagingException` gives a failure result and no `onStart`.
- `SkillForkExecutor`: the default overload delegates.

**Kept green.** The existing `HookRegistryApplierTest`, `HookHotReloadBootstrapTest`, `HookConfigHotReloadE2ETest`
and `DenyGuardFailOpenTest` construct the applier with a single executor and expect `preTool` commands to go to it.
Those tests move to the five-argument constructor, or pass the same executor twice, so their intent stays the same.
A line in the build's departures section records the change. Also kept green: the starter's
`HookHotReloadStartupFailureTest`, and ArchUnit (`PackageDependencyArchitectureTest`: check that
`skill.fork → subagent.execution` for `ForkGoalRenderer` is allowed; otherwise put the interface in `subagent`).

**Gate.** `./gradlew checkAll` and the four doc scripts.

## 8. aimon-sandbox follow-up (needed) and new backlog items

**aimon-sandbox needs a follow-up.** Without it, item 4 of §5 blocks guards under the sandbox. The builder files a
draft at `build/sandbox-followup.md`; do not edit that repository. Its content:

> **Implement `ExecutionOptions.isIsolated()` / `ShellFeature.ISOLATED_EXECUTION` (aimon-core #273).**
>
> - `SandboxShell.supports(ISOLATED_EXECUTION)` returns `true`.
> - In `execute`, an isolated command takes the no-lock path (like `isHook`).
> - Add a `Mode.ISOLATED`, or pass `iso` as `$3`, so that `ShellWrapper.INNER` neither sources
>   `$__aimon_dir/state` nor installs the save trap, and starts with `cd -- "$__aimon_root"` (fallback as today). The
>   watchdog stays as in `hook()`.
> - The base environment is `slot.profile().environment()` plus the per-command variables, as today.
> - Test: export in a foreground command, then check that an isolated command does not see it, that it starts in
>   the root, and that state is not saved.
> - Doc: `docs/design/workspace-sandbox.md` §9.
> - Compatibility: a core older than #273 never sets the flag.

**New backlog items, to register if the maintainer agrees.**

- **EE-new-1:** the integrity of staged hook scripts against agent tampering (F12; shared with skill staging).
- **EE-new-2:** whether `subagentStart` should ever carry the fork's environment, which would require resolving
  earlier. This only matters if a use case appears.

## 9. Open questions

- **Q1. Fail-closed or degrade on `ISOLATION_UNSUPPORTED`?** The design refuses to run, so guards block. The other
  option is to run non-isolated (agent state visible) with a WARN, which preserves availability on today's
  aimon-sandbox until the follow-up ships. The choice is the maintainer's. Refusing is the reading of "agent-controlled
  state must not be able to steer a policy hook", and is what this design does.
- **Q2. asyncRewake command handlers with no `"shell"` stay on the host (§2.1 *).** This keeps them working, but it is
  a second implicit rule in the placement table. The alternatives: (a) place them in the environment and drop the
  rewake with a WARN, as today's code does for an environment-bound executor; (b) make `asyncRewake` with no explicit
  `"shell"` a parse error. Neither is in the task statement.
- **Q3. An operator-defined PATH or environment beyond the environment's base?** The task says "a fixed,
  operator-defined environment … and a fixed PATH". This design reads "operator-defined" as the provider and process
  configuration (the JVM's environment, the sandbox profile) and adds no knob. If an explicit override is wanted, the
  smallest form is `HookHotReloadBootstrap.Builder.commandEnvironment(Map<String,String>)`, applied as the command's
  own variables (assembly code, not `hooks.json`).
- **Q4. The name and placement of `AIMON_HOOK_DIR`.** The design stages `<layer .aimon>/hooks/`. The task says only
  "a host script path … staged … reusing EE-50's staging". An alternative is a per-handler `"scripts": "<dir>"` key.
  The convention needs no schema and mirrors `AIMON_SKILL_DIR`.
- **Q5. Spawner staging in fork mode (F9).** Should `SkillTool` skip staging into the spawner when the skill is fork
  mode? The `subagentStart` goal and span input would then render `${AIMON_SKILL_DIR}` empty. Today the design keeps
  the spawner staging.
- **Q6. Deprecation versus removal.** The single-executor `HookRegistryApplier` constructor is deprecated, not
  removed. Under the 0.x policy it could also be removed. The design keeps it so embedders compile.
- **Q7. A new design-record file or an addendum to the EE-9/12 record (§4.7)?** Left to the builder, which follows
  precedent (new file).
- **Q8. The CLI's cwd change (§2.1, §5 item 2).** The design moves every CLI `hooks.json` command from `user.dir`
  to the workspace root (the jar directory under `java -jar`). That is the only provider-neutral rule, and it keeps
  one file on one cwd. Keeping `user.dir` for the local CLI would need a CLI-only working-directory override on the
  isolated exec, and that override is meaningless under any other provider. The alternative is still open if the
  maintainer prefers `user.dir`. A separate and older question is why the CLI's workspace is the jar directory at all.
  It is not in scope here.

## 10. Where the implementation departed from this design

Written after the build, against the working tree on top of `43babfae`. The approved body above (§1–§9) is not edited;
what turned out differently is recorded here. Most of the design was built as written: the placement table and the
no-execution rule (§2.1), the isolated-exec SPI (`ExecutionOptions.isIsolated()`, `ShellFeature.ISOLATED_EXECUTION`),
the base-environment rule for branches, the single `AIMON_HOOK_DIR` staging site, the CLI's host shell on the workspace
root, EE-52's result stamp (§2.2) and EE-81's deferred rendering (§2.3). The departures are listed in full.

### 10.1 Review-3 notes applied

The final design review passed with seven non-blocking notes. All seven were taken.

- **`ExecutionOrHostShellActionExecutor` is public.** The body (§2.1, §4.2) calls it package-private in
  `skill.hook.declarative`, but `HookRegistryApplier` in `config.hook` constructs it. It is a public final class with a
  public two-argument constructor and getters for its two executors.
- **`HookScriptDirectory`'s package and its read-only source.** It lives in `skill.hook.action`, next to the
  `ShellAction` that exposes it. Its source is `VirtualFileSystems.readOnlyLocal(<layer .aimon>)`, not
  `ReadOnlyLocalFileSystem`: `config.hook` may not reference `filesystem.impl..`, and in fact may not reference
  `filesystem..` at all (`PackageDependencyArchitectureTest.configHookOutboundDependenciesAreCurated` allows only
  `config.hook`, `base`, `agent`, `hook`, `skill.hook`, `scheduling.exception`, Java, SLF4J and Jackson). So the factory
  `HookScriptDirectory.of(Path layerDirectory, String resourceName)` builds the source itself, and the applier only
  passes a `Path`. The source's root is the layer's `.aimon` directory and the scanned directory is `hooks` inside it,
  so nothing outside `hooks/` is ever read for staging.
- **F12 names the mechanism.** The guide's caution (`hook-config-guide.md`, the staging paragraph) and EE-109 say it
  exactly: the staging layer trusts a copy's marker after the first verification, so a staged copy the agent rewrites
  through its shell keeps being served; re-scanning protects against edits to the *source*, not to the copy.
  `"shell": "host"` is the only tamper-proof placement.
- **Superseded copies stay until the sweep.** The guide says that each edit of a `hooks/` script stages a new copy under
  a new content key, and that old `.aimon-staged/hooks-*` copies stay until the provider's startup sweep.
- **A custom environment executor exports `AIMON_HOOK_DIR` itself.** Said in the Javadoc of
  `HookHotReloadBootstrap.Builder.environmentShellExecutor(...)` and of the five-argument `HookRegistryApplier`
  constructor.
- **F1c.** "Relative writes land in the project root, as today" is wrong for the CLI under `java -jar` after §5 item 2.
  The guide and the CHANGELOG say "the workspace root".
- **The CLI end-to-end gap is recorded** in §10.5.

### 10.2 Behaviour that differs from the body

1. **A failed `hooks/` stage reports its own cause.** §2.1, §4.2 and F6 say `notRun(STAGING_FAILED)`. That constant's
   description, which is what the deny reason reads, is "skill directory could not be staged". A `hooks.json` command has
   no skill directory, so the build added `Unrun.HOOK_DIR_STAGING_FAILED` ("hook script directory could not be staged").
   It is decided like every other not-run cause: a guard blocks unless `failOpen`, an observer logs.
2. **The `asyncRewake` exception applies on rewakeable events only.** §2.1 (*) keeps every default-placed command
   handler that declares `asyncRewake` on the host. The build narrows it to the events the rewake listener can re-fire
   (`preTool` and `preCompact` among the in-execution ones). On any other event the applier already drops the rewake
   with a WARN (`toRewakeSpec`), so there is nothing to keep working, and the handler takes the default placement.
3. **The host-path hint is read on guard events.** §2.1 says the hint is appended "if such a command exits 126 or 127".
   Exit 126/127 becomes a reason only where it becomes a not-run answer, i.e. on the four guard events
   (`ShellHookOutcome.asGuardAnswer()`). The hint is appended there (`withCouldNotStartHint`). On an observer event the
   runner's WARN logs the exit code and no reason is built. The hint names the spelling it matched (`~/.aimon/`, …) or
   the absolute layer directory, never the command. It is computed for every handler not bound to the bare host
   executor, so a default-placed handler that was routed to the host on a no-execution firing would carry it too; on the
   host those paths exist, so it can only appear when the script is missing there as well.
4. **EE-81: the fork renders again only a body that names `${AIMON_SKILL_DIR}`.** §2.3 passes a renderer for every
   fork-mode skill. Re-rendering stages the skill into the fork's environment, and a body that does not name the
   variable reads the same everywhere, so the stage would be a copy nobody reads. It also turned out to change existing
   behaviour: `SkillHookSkillDirIntegrationTest`'s two tests that put the fork in a workspace where nothing can be staged
   (`maxStagedBytes(1)`) failed, because the fork now ended at its goal before the hook the tests are about could run.
   `SkillRenderContextAccess.rendersSkillDir(Skill)` decides it, and `SkillTool` and `LlmSkillExecutor` call the
   four-argument `fork` only when it answers `true`. For a body that names the variable, F10 holds as designed.
5. **The fork's goal-render failure message.** §2.3 words it "Failed to stage skill 'x' in the fork's environment: …".
   The executor does not know the skill, so the message is "Failed to render the fork's goal in its own environment: "
   + the exception's message. It says "render", not "stage", because the cause is not always staging: a fork that
   resolved an unavailable environment fails here too, and the unavailable environment's own message follows. A
   `StagingException` from staging a skill already names it ("Cannot stage 'x': …").
6. **The env→shell lookup is a function, not a holder.** §4.2 asks for a package helper shared by
   `DefaultShellActionExecutor` and `IsolatedShellActionExecutor`. A helper returning "the shell or the refusal" failed
   `PackageDependencyArchitectureTest.skillHooksHoldNoShell` (no class under `skill.hook..` may have a `VirtualShell`
   field or constructor parameter). `EnvironmentShells.withShell(environment, command, what, body)` hands the shell to a
   lambda and keeps nothing.
7. **`baseOf` is bounded.** Following `isolatedFrom()` to its end stops after 32 hops, and a throwing `isolatedFrom()`
   is read as no lineage (WARN), so a provider defect cannot hang or fail a hook.
8. **No `hookScriptRoots` builder option.** `MergedHookConfig` already kept each layer's file (`origins`); it gained a
   public `origin(source)`. A command that names `AIMON_HOOK_DIR` in a layer whose file is not known (a config assembled
   in code) is inapplicable, by the same rule as a missing `hooks/` directory.
9. **Resource names.** The staged copies are filed under `hooks-user`, `hooks-project` and `hooks-local`. The project and
   local layers share `<project>/.aimon/hooks/`, so if both name the variable the directory is staged under both names.

### 10.3 What the design left to the builder

- **Scan cost.** The full re-scan shipped (`StagedResource.scan`, reading and hashing every file at every
  environment-routed firing of a handler that names `AIMON_HOOK_DIR`). It was not measured as significant: the tests'
  directories hold one or two files. The metadata-keyed variant is not built.
- **Design record.** A new file, `docs/design/tool/execution-environment-ee48-ee52-ee81-hooks-in-environment.md` (this
  record), following precedent (Q7). The EE-9/12 record gets a dated addendum pointing here.
- **Deprecation.** The four-argument `HookRegistryApplier` constructor is deprecated, not removed (Q6).

### 10.4 Expected result of the audit (§2.1), checked

| Site | Environment it passes |
|---|---|
| `SingleToolInvoker` (pre/postTool, permission*) | `environmentOf(spec)`, the tool context's `EXECUTION_ENVIRONMENT` (`:199`, `:224`, `:321`) |
| `OrcaAgentExecutor` `onStart`/`onStop` | `scope.executionEnvironment` (`:2671`, `:2718`) |
| `DefaultSubagentExecutor` hooks | `lc.executionEnvironment()` (`:1288`, `:1331`, `:1490`) and `SubagentOnStartGate` (`:82`) |
| `CompactCommand` | the command `ToolContext`'s environment (`:149`, `:216`) |
| compaction (`DefaultContextEngine`, `RollingContextEngine`, `DefaultCompactionEngine`, `DefaultCompactionGuard`) | `request.getExecutionEnvironment()`; the turn (`OrcaAgentExecutor:3313`) and the fork (`DefaultSubagentExecutor:754`) put theirs on the `ContextRequest`. One request carries none: `LlmSkillExecutor`'s scratch loop (`:254`), which also carries no hook registry and compacts nothing, so no compaction hook fires from it |
| `RoutineExecutor` | the environment resolved for the routine |
| `DefaultSubagentExecutionManager` start/stop | the spawner's, or the fork's for stop, with the `spawnedOutsideExecution` rule |

No site was found without an environment it already had, so none was changed. Line numbers are of the working tree
after the build (2026-10-18).

### 10.5 Tests: what differs from §7

- **Test 2's control** is a `DeclarativePreToolHook` built with `DefaultShellActionExecutor` and registered on the
  node's registry, which is exactly what `SkillHookSetParser` builds. It is not a parsed skill. It sees `[leak] …/sub`
  while the `hooks.json` command sees `[] <root>`. `SessionStateShell` models the sandbox contract with a state file
  sourced by bash; an environment is wrapped once per instance, so its state survives across turns.
- **Test 4's `onConfigReload`** fires through the real watcher (the test rewrites `hooks.json` and waits up to 20 s),
  because `HookHotReloadBootstrap` does not expose its reloader.
- **Test 6's "renderer not called" spy** is a unit test (`DefaultSubagentExecutorForkGoalTest`). The integration test
  checks the spawner's staged path in the local case.
- **Test 7** drives the runtime's agent-scoped `WorkflowRunner` directly (`runtime.getWorkflowRunner().run(...)`): the
  same runner and base launch context that background `Workflow` uses, without the tool around it. Case (b), an unknown
  subagent from that runner, is not an integration test: an inline `AgentTask` always resolves its subagent. It is the
  unit test `DefaultSubagentExecutionManagerTest.runtimeRunnerUnknownSubagentHasNoExecution`. Case (c) is covered for
  case (a) only.
- **Test 8** does not call `WorktreeMerge`. No core caller runs it for a runtime-runner workflow. The test asserts that
  the step did run in a branch (its `Bash` write is under `.worktrees/`, not in the root) and that the branch holds no
  `audit.log`, so a merge would have nothing of the hook's to promote.
- **Test 9.** The CLI unit test checks `AgentSetupFactory.createHookConfigShell(LocalFileSystem)`. **Gap:** nothing
  exercises the shipped CLI end to end with `user.dir` ≠ the jar directory; the integration case only gives the host
  shell and the local provider the same root, which differs from the JVM's `user.dir`.
- **Kept green.** `HookRegistryApplierTest.failOpenIsPassedToTheHook`, `DefaultSubagentExecutorOnStartBlockTest` and
  `SubagentBehaviorOnStartTest` pass their host executor as both executors (the five-argument constructor): what they
  test is the block, not where the command runs. `HookHotReloadBootstrapTest`, `HookConfigHotReloadE2ETest` and
  `DenyGuardFailOpenTest` pass unchanged. `ShellActionRunnerTest` gained the `isolated` argument.

### 10.6 Open questions moved to the backlog

The open questions whose consequences reach beyond this change are tracked in
[`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md):
EE-109 ← F12 (the integrity of staged hook scripts), EE-110 ← §8 EE-new-2 (`subagentStart` in the fork's environment),
EE-111 ← §8 and Q1 (aimon-sandbox must declare `ISOLATED_EXECUTION`; until it does, fail-closed), EE-112 ← Q3 (an
operator-defined environment for the isolated exec). Q2, Q4–Q7 are settled inside this change as the body says. Q8 (the
CLI's cwd) is recorded as a behaviour change in the CHANGELOG; the older question of why the CLI's workspace is the jar
directory stays out of scope.
