# Design — #275 / EE-109 (+ aimon-sandbox#33): narrow agent tampering with in-environment policy hooks

> Status: **IMPLEMENTED** (2026-10-19, issue [#275](https://github.com/kangwoo/aimon-core/issues/275); the sandbox
> half is [kangwoo/aimon-sandbox#33](https://github.com/kangwoo/aimon-sandbox/issues/33)). This is the design for
> backlog item EE-109 exactly as it was approved in design review (one round, passed with nine non-blocking notes). The
> body below, §1–§10, is the body as approved, kept byte-exact, including its line citations (as of `016e2680`) and its
> open-question numbers. It is written in English because that is how it was reviewed; the `design/` directory is not
> a translation target (`docs/project/documentation-guide.md` §5.1).
>
> One thing was added. §11 records where the implementation departed from this design, and why, including which review
> notes were taken. The open questions in §10 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) (EE-113 ← §7;
> EE-114 ← §8 · Q5 · Q7 · Q8; EE-115 ← Q6; EE-116 ← Q3), which is the source of truth for what is open. The record it
> follows is [`execution-environment-ee48-ee52-ee81-hooks-in-environment.md`](execution-environment-ee48-ee52-ee81-hooks-in-environment.md)
> (F12 there is this design's subject); the specification it changes is [`execution-environment.md`](execution-environment.md) §4.4.

Base: worktree `herdr/hook-tamper-narrowing` at `016e2680` (clean). aimon-sandbox read at `main` `553be78`
(read-only). Paths are under `modules/aimon-core/src/main/java/at/aimon/core/` unless stated; sandbox paths are under
`aimon-sandbox/modules/aimon-sandbox/src/main/java/at/aimon/sandbox/`. Written in English like the #273 record it
follows (`docs/design/tool/execution-environment-ee48-ee52-ee81-hooks-in-environment.md`, "the #273 record" below);
`design/` is not a translation target.

Target level, decided by the user and not re-opened here: **narrowing, not a privilege boundary.**

---

## 1. Problem

Since #273, an operator's `hooks.json` command on an in-execution event runs in the execution's environment as an
isolated exec. Every command in that environment, the hook's included, runs as the same uid as the agent's shell.
Two kinds of agent-writable file sit between what the operator wrote and the verdict core reads. (1) **Staged
script copies.** `AIMON_HOOK_DIR` and a skill hook's `AIMON_SKILL_DIR` point to a copy under the workspace's
`.aimon-staged/`. `LocalStaging` checks a copy once per process (its `verified` set,
`environment/impl/LocalStaging.java:174`, `:272-275`, `:353-362`) and then trusts the marker, so a `guard.sh`
rewritten through `Bash` keeps being served. That is a *persistent* window. Even a copy that is checked on every use
is still reopened by path when the interpreter starts, which is a *verify-then-reopen* race. (2) **Run files.** In the
sandbox, the command, its stdin and its output pass through `/workspace/.aimon-shell/<h>/run-*.{cmd,in,out,err,
timedout}` (`environment/ShellWrapper.java:389-431`, `SandboxShell.java:226-245`). A background loop can forge the
command, the hook's JSON payload, or the verdict. The local provider has a smaller version of the same thing:
`LocalShell` passes stdin and output through named temp files under `java.io.tmpdir`
(`shell/impl/local/LocalShell.java:446-455`) that stay reachable by name until the JVM reads them. This design closes
the persistent window and the file-race windows. It does not try to stop a same-uid agent from interfering with a
hook *process that is running*, and it writes down what a real boundary would need.

**A correction to the issue text.** #275 says `SandboxStaging` also trusts a copy after its first verification. At
`553be78` it does not: `SandboxStaging.stage` calls `verified(...)` (one in-sandbox `sha256sum` exec) on **every**
call (`environment/SandboxStaging.java:80-82`). The sandbox half of EE-109 is therefore only the verify-then-reopen
race. That race is closed by the run-directory feature in §2.2, not by a change to `SandboxStaging`.

## 2. Approach

Three changes, each closing one window.

### 2.1 The staging contract: verify on every use (closes the persistent window)

`ExecutionEnvironment.stage()` gains a contract sentence: **an existing copy is checked against the resource's
content key on every call; a provider never serves a copy because an earlier call checked it.** `SandboxStaging`
already does this. `LocalStaging` changes to match:

- The `verified` set goes away. `stagedAlready(...)` and the fast path of `copyVerified(...)` stop returning on
  `verified.contains(target) && exists(marker)`. Every call takes the target lock and runs `matchesContentKey`
  (marker content, file set, re-hash of the bytes on disk). The extra-file removal it already does (`__pycache__`)
  stays.
- On a mismatch the existing re-copy path runs (rename aside, publish, `WARN "Staged copy … does not match its content
  key; staging it again"`). That is today's handling of a planted copy, now applied at every call instead of the
  first.
- `rescanned` (EE-3) stays. Its doc says it is "used only while the copy it names has its marker, like `verified`".
  That becomes "only while the copy it names has its marker *and* matches its key".
- Cost: one listing plus one hash of the copy per `stage()` call, bounded by the staging limit (50 MiB,
  `LocalExecutionEnvironmentProvider.DEFAULT_MAX_STAGED_BYTES`). Skill copies are typically a few KiB. The spec
  sentence that rejected "per-call full-hash re-verification" (`execution-environment.md` §4.4, the paragraph
  "변조는 막지 않고 격리한다") is rewritten (§4.7).

This alone does **not** satisfy "the verified bytes are what runs": the interpreter still opens
`$AIMON_HOOK_DIR/guard.sh` by path after `stage()` returned. Policy hooks therefore stop using staged copies (§2.2).
§2.1 is what model-facing staging (`Skill`, link staging, forks) and the skill-hook fallback (§2.2, F4) rely on.

### 2.2 A run directory for hook files (closes verify-then-reopen for hook scripts)

Core cannot hand "the script" to the interpreter, because the command is arbitrary operator text
(`bash "$AIMON_HOOK_DIR/guard.sh"`, `python3 …`, `source "$AIMON_HOOK_DIR/lib.sh"; …`). So the design moves the
verification **into the run itself**: the shell gets the verified bytes as part of the exec request and materialises
them into a **run directory**. The run directory:

- is created fresh by that run (exclusive create, unpredictable name),
- is placed outside the workspace's file-tool view,
- holds exactly those bytes, checked in the environment after they are placed and before the command starts,
- is exported in the named variable,
- is removed when the command ends.

Nothing persistent stands between the verified bytes and the interpreter. What a same-uid agent can still do is race
a directory that exists only while that one hook runs. That falls in the residual class the user accepted
(interference with a running hook process, §6 R1).

New shell SPI (`shell/`):

```java
// shell/RunDirectory.java — immutable, builder (CLAUDE.md: class, not record)
public final class RunDirectory {
    public String getVariable();                  // e.g. "AIMON_HOOK_DIR"; [A-Za-z_][A-Za-z0-9_]*
    public Set<String> getPaths();                // confined relative paths, sorted
    public byte[] getContent(String relPath);     // defensive copy
    public long getTotalBytes();
    public static Builder builder();              // Builder.variable(..).file(relPath, bytes).build()
}
// ExecutionOptions: Optional<RunDirectory> getRunDirectory(); Builder.runDirectory(RunDirectory); carried by toBuilder()
// ShellFeature.RUN_DIRECTORY
// shell/exception/RunDirectoryIntegrityException extends ShellExecutionException
```

Contract for a shell that declares `RUN_DIRECTORY` (Javadoc on `ShellFeature.RUN_DIRECTORY` and on
`ExecutionOptions.getRunDirectory()`):

1. Before the command starts, create a directory that did not exist before (fail if it exists). Use permissions as
   tight as the environment allows (0700 dir, 0600 files), and place it outside the paths the model's file tools are
   given where the environment has such a place.
2. Write exactly `getPaths()` with their bytes. Two paths the filesystem would merge (case folding, EE-38) are an error
   (`CREATE_NEW` catches it), and the command is not run.
3. If the bytes travelled through anything the agent can write (the sandbox's files-API upload), verify the placed
   files against digests that did *not* travel that way (they are embedded in the exec request), after placement and
   before the command starts. On a mismatch, do not run the command and throw `RunDirectoryIntegrityException`.
4. Export the directory's absolute path in `getVariable()`. It wins over the same name in `getEnvironment()`.
5. Remove the directory after the command ends, on every outcome (best effort; a failure to remove is a WARN, not the
   command's failure).
6. Orthogonal to `isIsolated()` / `isHook()`: a hook-mode command (skill hook) may carry one too.

A shell that does not declare the feature ignores the option. Callers check `supports(RUN_DIRECTORY)` first, with the
`catch (RuntimeException | LinkageError)` guard that `IsolatedShellActionExecutor.supportsIsolation` already uses.

**Callers.**

- **`hooks.json` (`IsolatedShellActionExecutor`, via `ConfigHookDirectory`).** `exportStaged(...)` is replaced by
  `runDirectory(action)`: scan the layer's `hooks/` source (`StagedResource.scan`, as today, at every firing), read
  every listed file's bytes, and rebuild the content key with `StagedResource.ContentKeyBuilder`. If the key differs
  from the scan's, the source changed mid-read: re-read once, then refuse (`HOOK_DIR_STAGING_FAILED`). The bytes go
  into a `RunDirectory("AIMON_HOOK_DIR", …)` handed to `ShellActionRunner.run(...)`. **No `stage()` call and no
  `.aimon-staged/hooks-*` copy any more.** If the shell does not declare `RUN_DIRECTORY`, the command is **not run**:
  new `Unrun.RUN_DIRECTORY_UNSUPPORTED`. That is fail-closed, the same rule `ISOLATION_UNSUPPORTED` follows; a guard
  blocks unless `failOpen`. Handlers that do not name `AIMON_HOOK_DIR` carry no directory and are unaffected.
- **Skill hooks (`DefaultShellActionExecutor`, via `SkillHookDirectory`).** The declaring skill's `StagedResource` is
  read from its source and checked against its content key. If the key differs (EE-3), the source is re-scanned, the
  current version is used, and one WARN is logged, as `LocalStaging` does. The bytes become
  `RunDirectory("AIMON_SKILL_DIR", …)`. Reading a skill from its source on every `preTool` firing can be expensive
  (remote repositories, multi-MiB skills), so the verified bytes are **kept in memory**: a bounded cache in the hook
  package keyed by `(resource name, source dir, content key)`. That gives exactly EE-3's documented behaviour: the
  loaded version is served until the registry reloads, now from JVM memory instead of an agent-writable copy.
  - **Fallback to the old path, with every-use verification.** When the shell does not declare `RUN_DIRECTORY`, or the
    resource is over the run-directory cap, the hook falls back to today's path: `environment.stage(resource)`, with
    the path exported in `AIMON_SKILL_DIR`. That path is verified on every use (§2.1), so only the reopen race is left.
    One WARN is logged per shell class and resource name. The fallback exists because skill hooks were never
    policy-grade: they start from the agent's shell state, so `PATH`, `BASH_ENV` and functions reach them anyway (#273
    record §2.1, hook guide). Failing them closed would break every skill hook on today's sandbox for no real gain.
    Open question Q2.
- **Run-directory cap.** `HookRunFiles.MAX_BYTES = 4 MiB` (package-private constant, hook package). A `hooks.json`
  directory over the cap is refused, with the reason that names `.stageignore`. Q3 covers the value.

**Carrying the run directory to the executor.** `ShellAction` already carries the `hooks.json` directory
(`HookScriptDirectory`), so the isolated path needs no signature change. Skill hooks resolve their resource in the hook
(`SkillHookDirectory.export(env, context, hook, executor)`), not in the executor. `ShellActionExecutor` (public) gains:

```java
default ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
        String stdinPayload, Optional<StagedResource> skillDirectory) {
    if (skillDirectory.isEmpty()) { return run(action, context, environmentOverrides, stdinPayload); }
    return ShellHookOutcome.notRun(Unrun.RUN_DIRECTORY_UNSUPPORTED, "the hook executor cannot carry the skill directory");
}
```

- The default fails closed. A third-party executor that never learned the overload refuses rather than running
  `"/scripts/guard.sh"`; the #273 record explains why an unset variable is the worst outcome.
- `DefaultShellActionExecutor` overrides it with the run-directory-or-fallback logic. The fallback needs
  `environment.stage(...)`, so it lives in the executor, which knows the environment and the shell.
- `SkillHookDirectory.export` changes. It still decides *whether* there is a skill directory (unchanged rules: executor
  `requiresExecutionEnvironment()`, a resource exists, the context has an environment). It no longer stages anything;
  the three declarative hooks pass the resource to the new overload.
- `HostShellActionExecutor`, `NoOpShellActionExecutor` and `ExecutionOrHostShellActionExecutor` keep the default.
  The router forwards the overload to the executor it picks. Skill hooks never use a host executor, and the existing
  rule already skips the directory for executors that do not require an environment.

**Integrity failure.** `RunDirectoryIntegrityException` → `ShellActionRunner` maps it to the new
`Unrun.HOOK_FILES_TAMPERED` ("the hook's files changed between verification and the run"). Like `CANCELLED`, it
**blocks on a guard event whatever `failOpen` says** (`ShellHookVerdicts`): it is evidence of an attack, not an
infrastructure failure. Otherwise an agent could turn every `failOpen` guard off by touching the upload. The rule is
recorded as Q4 for confirmation.

### 2.3 Hook command text, stdin and output off agent-writable files

**Local (`LocalShell`), for `isHook() || isIsolated()`.**

- The command already travels in argv (`bash -c`).
- stdout and stderr keep their temp files (the issue #13 reasons still hold). Two things change: the JVM opens a read
  `FileChannel` on each file **before** `pb.start()` (`ProcessBuilder` opens the redirect in the parent, so the child
  holds its own fd), and it **unlinks both paths right after `start()` returns**. It then reads through the channels
  after `waitFor()`. Nothing is reachable by name while the hook runs or after it.
- stdin is written to the child through `Redirect.PIPE` by a daemon writer thread, which closes the stream at the end.
  The thread ends at the child's exit (EPIPE) or when the JVM destroys the process on timeout or cancel.
- What is left is the window from `createTempFile` to `start()`, inside one JVM call, on 0600 files with random
  names. Anything written there is truncated by the child's redirect, but an fd opened in that window survives (R2).
- Model-facing commands keep today's path.

**Sandbox (`aimon-sandbox#33`)** — implemented in the sandbox, specified here (§8):

- In `HOOK` and `ISOLATED` modes the wrapper creates **no file under `.aimon-shell`**.
- The command and the stdin payload are embedded in the exec argument when they fit. When they do not, they are
  uploaded and then copied into the run's private directory and checked against SHA-256 digests embedded in the
  argument (the §2.2 rule 3 pattern), before use.
- The inner bash's stdout and stderr are the exec's own stdout and stderr; the trailer stays on stderr.
- A timeout is detected by the JVM's own clock, not by a `.timedout` file that can be forged.
- Foreground and background (the model's own commands) keep their run files. Forging one's own output is not a
  boundary crossing.

### 2.4 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| **Verify on every use only** (§2.1 alone), keep `AIMON_HOOK_DIR` on the staged copy | The interpreter reopens by path after verification: the verify-then-reopen TOCTOU the user ruled out. A loop that rewrites `guard.sh` after each `stage()` wins reliably. |
| **Verify in the hook exec, then run** (`sha256sum -c` on the staged copy as the exec's first step) | Same TOCTOU, just shorter, and the staged copy it checks is a persistent, agent-known path. |
| **Hand the script to the interpreter directly** (`bash -c "$content"`, or `bash -s` on stdin) | Core cannot find "the script" in arbitrary command text. stdin already carries the JSON payload. A `"script": …` handler field could do it for one entry file, but `source`/`import` of sibling files would still reopen by path. Kept as a possible later addition (Q6). |
| **New `ExecutionEnvironment.stageForRun(...)` returning a path** | Two calls (stage, then exec) re-create the window. Verification has to happen inside the exec that runs the command, so the SPI change belongs on `ExecutionOptions`. |
| **Stage into a directory the agent cannot write** | Needs a second uid (§7), which the user deferred. Under the same uid, no such directory exists. |
| **Content-hash check by file metadata** (mtime/size) | Metadata is agent-forgeable (`touch -d`). |
| **Kill a hook's process tree at exit, so the local shell can use pipes for stdout** | Changes what a hook may do (a `postTool` hook that starts `notify-send &`) for no gain over unlink-after-start, since the running process is reachable anyway (R1). |
| **Fail skill hooks closed when the shell lacks `RUN_DIRECTORY`** | Breaks every skill hook on today's sandbox, which has no `RUN_DIRECTORY`. Skill hooks start from agent shell state, so they are not policy-grade anyway. Q2. |
| **A separate uid now** | The user deferred it. Conditions are in §7. |

---

## 3. What remains (the honest list for docs)

- **R1. A running hook process.** Same uid means the agent can:
  - write the run directory while the hook runs (its path shows in the hook's environment, `/proc/<pid>/environ`);
  - open `/proc/<pid>/fd/{0,1,2}` and inject into the stdin, stdout or stderr pipe, or the output file;
  - read the trailer nonce from `/proc/<pid>/cmdline` and forge a trailer;
  - `ptrace` the hook, or signal it (`kill` → `TIMEOUT` or `EXECUTION_FAILED` → a `failOpen` guard allows).

  None of this needs a file that outlives the hook or exists before it. This is the accepted residual.
- **R2. The local pre-start window.** Between `createTempFile` and `start()` (§2.3).
- **R3. The local provider has no boundary at all.** Its agent `Bash` runs as the JVM's uid on the host. It can
  rewrite `~/.aimon/hooks/guard.sh`, `<project>/.aimon/hooks.json` (which hot-reloads), or the JVM itself. **The
  existing guide sentence "only `"shell": "host"` is tamper-proof" is true only when the agent's shell runs somewhere
  else (a sandbox).** The guides must say so. The local changes in this design are hygiene: they remove the named
  windows and keep contract parity.
- **R4. Skill hooks** start from the agent's shell state (non-isolated by design). Making their files tamper-evident
  does not make them policy-grade. The fallback path (§2.2) keeps the reopen race on shells without
  `RUN_DIRECTORY`.
- **R5. What the hook inspects** (the workspace) is agent-writable by nature. A policy hook must not trust workspace
  files for its decision.
- **R6. Until the sandbox follow-up lands**, `hooks.json` + `AIMON_HOOK_DIR` on the sandbox is `RUN_DIRECTORY_UNSUPPORTED`
  (fail closed). Handlers without the variable keep working. The sandbox run files (#33) stay forgeable until then.

The guide wording: "`"shell": "host"` is the only placement out of the reach of an agent whose shell runs in a
sandbox. In-environment hooks are protected against tampering of their files *before* they run, not against
interference *while* they run. Under the local provider the agent's shell is the host user, so no placement is."

---

## 4. Changes by file and module (aimon-core)

### 4.1 Shell SPI — `shell/`

- `RunDirectory.java` (new, public, immutable, builder). It validates: the variable name; paths are confined relative
  paths (the `LocalStaging.isConfinedRelativePath` rules: no empty, `.`, `..` segments, no leading `/`, no `\`);
  no duplicates; `totalBytes`. `toString()` names the variable, the file count and the byte count, never the
  content.
- `ExecutionOptions.java`: the field, getter, builder method and `toBuilder()` carry. `defaults()` has none.
  `ExecutionOptionsTest` covers it.
- `ShellFeature.java`: `RUN_DIRECTORY`, with the contract (§2.2 points 1–6).
- `exception/RunDirectoryIntegrityException.java` (new): extends `ShellExecutionException`, with the same constructor
  set it needs.
- `shell/impl/local/LocalShell.java`:
  - `supports(RUN_DIRECTORY) → true`.
  - Run directory: `Files.createTempDirectory("aimon-run-", 0700 attrs)` (POSIX attrs when the filesystem supports
    them). Files are written `CREATE_NEW` with 0600, parent directories 0700. After writing, the JVM **re-reads and
    compares** each file (cheap, and it keeps the rule 3 behaviour honest even where the bytes did not travel). A
    mismatch throws `RunDirectoryIntegrityException`. The path goes into `pb.environment()` and the directory is
    deleted recursively in `finally`.
  - Hook/isolated I/O: unlink after start, stdin through a pipe (§2.3).
  - The class doc's "Why files, not pipes" gets the hook-mode amendment.
- `environment/impl/WorkingDirectoryShell.java`: no change beyond confirming that `toBuilder()` carries the field (it
  already delegates `supports`). A test pins it.

### 4.2 Staging — `environment/`

- `ExecutionEnvironment.stage(...)` Javadoc: the every-call verification sentence (§2.1). It is an SPI contract
  change, already met by `SandboxStaging`.
- `impl/LocalStaging.java`: remove `verified`; make the fast paths verify under the target lock; update the class doc.
  The doc's "That check is remembered per target, so it costs one read of the copy per process; a shell can still
  change a copy afterwards" becomes the every-call rule plus its cost. Its "Only a scanned resource is followed" and
  EE-17 sections are unchanged.
- `impl/LocalIsolatedEnvironment.stage` delegates and needs no change.

### 4.3 Hook executors — `skill/hook/declarative/`

- `HookRunFiles.java` (new, package-private):
  - `forHookDirectory(HookScriptDirectory)` (scan + read + key check, §2.2);
  - `forSkill(StagedResource)` (read + key check + EE-3 rescan + bounded cache);
  - `MAX_BYTES`;
  - the cache: a `LinkedHashMap` LRU under a lock, bounded at 32 MiB total. The bound is Q3.

  It returns `RunDirectory` or throws `StagingException` with a model-safe message (the type only for an unexpected
  failure, as `ConfigHookDirectory` does now).
- `ConfigHookDirectory.java`: `exportHost` unchanged. `exportStaged` becomes
  `runDirectory(action) : Either<RunDirectory, ShellHookOutcome>` (or `Optional` + an out-param, matching local
  style). The class doc's "Scanned at every firing … A copy of a superseded version stays in the staging area"
  becomes "read at every firing into the run directory; nothing is staged".
- `IsolatedShellActionExecutor.java`:
  - after `supportsIsolation`, if the action carries a directory, check `supports(RUN_DIRECTORY)` with the same guard;
    if it is not supported, return `notRun(RUN_DIRECTORY_UNSUPPORTED)` with a WARN that names the upgrade or
    `"shell": "host"`;
  - pass the run directory to `ShellActionRunner.run`;
  - update the class doc's staging sentence.
- `ShellActionRunner.java`: a `RunDirectory` parameter (nullable or `Optional`) set on the builder;
  `catch (RunDirectoryIntegrityException)` → `notRun(HOOK_FILES_TAMPERED, "…")` **before** the
  `ShellExecutionException` catch.
- `ShellActionExecutor.java`: the new default overload (§2.2).
- `DefaultShellActionExecutor.java`: override it with the run directory, or the fallback to `stage()` plus a WARN
  once per (shell class, resource name). The fallback keeps today's `STAGING_FAILED` / `ENVIRONMENT_UNAVAILABLE`
  mapping (moved from `SkillHookDirectory`).
- `ExecutionOrHostShellActionExecutor.java`: forward the overload.
- `SkillHookDirectory.java`: becomes `resourceFor(context, hook, executor) : Optional<StagedResource>` with the same
  "when the variable is left out" rules. The staging and refusal code moves to the executor. Class doc updated.
- `AbstractDeclarativeShellHook`, `DeclarativePreToolHook`, `DeclarativePostToolHook`: call the overload with the
  resource instead of exporting a path (`:166`, `:215`, `:197`).
- `ShellHookOutcome.Unrun`: `RUN_DIRECTORY_UNSUPPORTED("the environment's shell cannot place the hook's files for the
  run")` and `HOOK_FILES_TAMPERED("the hook's files changed before the run")`.
  `ShellHookVerdicts`: `HOOK_FILES_TAMPERED` joins `CANCELLED` in "blocks whatever `failOpen` says".
- `SkillHookEnv` doc for `AIMON_HOOK_DIR` / `AIMON_SKILL_DIR`: the value inside the environment is now a per-firing
  directory outside the workspace. It does not persist between firings and is not under `.aimon-staged`.

### 4.4 `config/hook/`, assembly

- `HookRegistryApplier`: no logic change. The load-time "`hooks/` missing" check stays.
- `aimon-cli` `AgentSetupFactory`: no change (`LocalShell` declares the feature).

### 4.5 Tests (§9)

### 4.6 Backlog — `docs/backlog/execution-environment-open-items.md` (ko; it has no `.en.md`)

- **EE-109 → closed** (date of the build), with a dated section saying what landed in core:
  - every-call verification;
  - run directories for `hooks.json` and skill hooks;
  - local hook I/O.

  The sandbox half (`RUN_DIRECTORY` in `SandboxShell`, plus the run files) is tracked by aimon-sandbox#33, which the
  follow-up widens. If the build does not land everything, it narrows EE-109 instead and lists what is left.
- **EE-111 → closed**: aimon-sandbox#31 was merged via aimon-sandbox#32 (`553be78` declares `ISOLATED_EXECUTION`,
  `SandboxShell.java:609-611`).
- **EE-113 (new)**: *a privilege boundary between the agent and in-environment policy hooks.* What: §7's conditions.
  Why: §3 R1/R3. Where: core `IsolatedShellActionExecutor`, the shell SPI (a hook-uid option), `LocalStaging`, and the
  sandbox's `SandboxShell` / `SandboxStaging` / image contract. Sandbox counterpart: a new aimon-sandbox issue,
  "run policy-hook execs under a separate uid", which the follow-up files (§8.4). When to revisit: an operator runs
  `hooks.json` guards against an untrusted agent in a sandbox, or OpenSandbox's files API gains a uid argument.
- Update the header paragraph that lists EE-109~EE-112 (`:102-103`) to add EE-113 and its source.

### 4.7 Docs

- `docs/design/tool/execution-environment.md` (ko canonical; no `.en.md` under `design/`): rewrite §4.4
  "변조는 막지 않고 격리한다" with a dated *2026-10-19 (EE-109) 이후* paragraph covering every-call verification, the
  run directory for hooks, and what remains. Also adjust the EE-37 paragraph that says verified targets are
  remembered.
- `docs/design/tool/execution-environment-ee48-ee52-ee81-hooks-in-environment.md`: append to §10 (departures, not the
  frozen body) one line under F12: "addressed by EE-109's record". The body stays byte-exact.
- New record `docs/design/tool/execution-environment-ee109-hook-tamper-narrowing.md`: this design, with the
  departures section the builder adds.
- `docs/features/hook/hook-config-guide.md` + `.en.md`:
  - rewrite the ⚠️ staging paragraph (`:336-339`) with §3's wording;
  - the `AIMON_HOOK_DIR` value change (a per-firing directory, no `.aimon-staged/hooks-*` copies any more, so nothing
    of the user layer lands in the workspace) at `:324-327`;
  - troubleshooting rows for `RUN_DIRECTORY_UNSUPPORTED` and `HOOK_FILES_TAMPERED`;
  - the local-provider caveat (R3).
- `docs/features/hook/hook-development-guide.md` + `.en.md`, and `docs/features/skill/builtin-agent-skill-guide.md`
  + `.en.md`: the builder greps for `AIMON_SKILL_DIR` / `.aimon-staged` and updates statements about where a skill
  hook's `AIMON_SKILL_DIR` points (only the hook's value changes; the body's `${AIMON_SKILL_DIR}` stays the staged
  path).
- Every translated file touched: update `source_commit`, and run `check-translation-structure.py`,
  `check-doc-links.py` and `check-translation-staleness.py`.
- `CHANGELOG.md` `[Unreleased]`: a "Security (narrowing)" / "Changed (behaviour change)" entry:
  - every-call verification;
  - the `AIMON_HOOK_DIR` / skill-hook `AIMON_SKILL_DIR` value change;
  - no more `hooks-*` staged copies;
  - the new `Unrun` causes, and that `HOOK_FILES_TAMPERED` ignores `failOpen`;
  - **a sandbox requirement box**: `hooks.json` + `AIMON_HOOK_DIR` needs aimon-sandbox with `RUN_DIRECTORY`, or
    `"shell": "host"`;
  - the honest residual list (R1, R3).

---

## 5. Data and interface shapes that change

| Shape | Change | Compatibility |
|---|---|---|
| `ExecutionOptions` | `+ Optional<RunDirectory> getRunDirectory()`, `Builder.runDirectory(..)` | Additive. Shells that ignore it are refused by callers through the feature check. |
| `ShellFeature` | `+ RUN_DIRECTORY` | A precompiled exhaustive `switch` without `default` throws `IncompatibleClassChangeError`, which the callers' `LinkageError` catch absorbs (the #273 precedent). In-tree `switch`es (`LocalShell`, test shells such as `ControllableShell` and `BashToolTest`'s) are updated. |
| `RunDirectory` | new public class | — |
| `RunDirectoryIntegrityException` | new, `extends ShellExecutionException` | A shell that throws it to an old caller is read as an ordinary execution failure. |
| `ExecutionEnvironment.stage` | contract: verify on every call | Behavioural. `SandboxStaging` already complies. A third-party provider that does not is out of contract (documented). |
| `ShellActionExecutor` | `+ default run(.., Optional<StagedResource>)` | The default fails closed for a present directory. |
| `ShellHookOutcome.Unrun` | `+ RUN_DIRECTORY_UNSUPPORTED`, `+ HOOK_FILES_TAMPERED` | The enum is public. An external exhaustive `switch` has the same LinkageError caveat; none in-tree is exhaustive without `default` (the builder checks). |
| `AIMON_HOOK_DIR` (in environment) | `…/.aimon-staged/hooks-<layer>-…/<key>` → a per-firing private directory (local: `${java.io.tmpdir}/aimon-run-*`) | **Behaviour change.** Scripts that write next to themselves lose those files at each firing; paths computed relative to the workspace via the hook dir break. Host placement is unchanged. |
| skill hook `AIMON_SKILL_DIR` | the staged path → a per-firing directory when the shell supports it | **Behaviour change**, same notes. The skill **body**'s `${AIMON_SKILL_DIR}` is unchanged. |
| sandbox SPI (later, §8) | `SandboxShell.supports(RUN_DIRECTORY)`; hook and isolated modes stop using run files | Internal to aimon-sandbox. Core's contract is all it relies on. |

---

## 6. Failure modes

| # | Failure | Handling |
|---|---|---|
| F1 | A `hooks.json` handler names `AIMON_HOOK_DIR` and the environment's shell lacks `RUN_DIRECTORY` (today's sandbox, a third-party shell) | `notRun(RUN_DIRECTORY_UNSUPPORTED)` + WARN naming the upgrade or `"shell": "host"`. A guard blocks unless `failOpen`; an observer logs. |
| F2 | The `hooks/` source changes while it is being read | The key rebuilt from the read bytes ≠ the scan's: one re-read, then `HOOK_DIR_STAGING_FAILED` ("is changing on disk"). |
| F3 | The `hooks/` directory is over `HookRunFiles.MAX_BYTES` | `HOOK_DIR_STAGING_FAILED`, with the size and `.stageignore` in the reason. |
| F4 | A skill hook on a shell without `RUN_DIRECTORY`, or a skill over the cap | Fallback to `stage()` (every-call verified) + `AIMON_SKILL_DIR` = the staged path; WARN once per (shell class, skill). Residual R4. |
| F5 | The run directory cannot be created or written (disk full, no `java.io.tmpdir`, case-merged names) | `ShellExecutionException` → `EXECUTION_FAILED`. The command does not run. Its usual guard semantics apply. |
| F6 | The placed files differ from the bytes (an upload tampered with, or a race on the private copy before start) | `RunDirectoryIntegrityException` → `HOOK_FILES_TAMPERED`: blocks a guard **regardless of `failOpen`**, WARN with the hook and event (no content). |
| F7 | The run directory cannot be removed afterwards | WARN; the command's result stands. The local leftover sits in `java.io.tmpdir` (0700). The sandbox leftover goes with the sandbox. |
| F8 | A staged copy fails every-call verification | Re-copy (today's planted-copy path) + WARN. A process running from the old copy sees the two-rename gap (EE-17), as it already can. |
| F9 | A skill script writes into its own staged directory | Extra files are removed at the next `stage()` (now every call, before only the first). Behaviour change, CHANGELOG. |
| F10 | A third-party `ShellActionExecutor` gets a skill hook with a directory | The default overload → `RUN_DIRECTORY_UNSUPPORTED` (fail closed; never runs with the variable unset). |
| F11 | The stdin writer thread (local) cannot write because the child never reads | It blocks until the child exits or is destroyed (timeout or cancel), then ends on `IOException`. A daemon thread, logged at DEBUG. |
| F12' | A same-uid agent interferes with a running hook (R1) | Not handled; documented. Produces a forged verdict, `TIMEOUT` or `EXECUTION_FAILED`. Only §7 fixes it. |
| F13 | The skill-bytes cache is over its bound | LRU eviction; the next firing re-reads the source and checks the key (the EE-3 path if it changed). |

---

## 7. Conditions for a real privilege boundary (record for EE-113 and the sandbox counterpart)

A boundary between the agent (uid **A**) and a policy hook (uid **H**) holds only if **all** of these hold:

1. **A is not root and cannot become H or root** inside the container: `runAsNonRoot`, no-new-privileges, no
   `CAP_SETUID`/`CAP_DAC_OVERRIDE`/`CAP_FOWNER`/`CAP_SYS_PTRACE`/`CAP_KILL`, and no setuid binary that reaches H.
   Today the sandbox already enforces uid 1000 and a hardened SecurityContext (sandbox §12.1, §13.3) for A.
2. **Hook execs run as H ≠ A.** OpenSandbox's `/command` has a uid argument the sandbox deliberately does not use
   (sandbox §6.1). Using it requires execd to be able to switch uid (root, or `CAP_SETUID`), which conflicts with
   condition 1 for execd itself unless execd's privilege is not reachable from A's processes.
3. **Hook files and run files are owned by H and not writable by A, and neither are their parent directories.**
   `rename` needs write permission on the parent. A owns `/workspace` (the image contract), so a staging area or run
   directory **under `/workspace`** can always be renamed away and replaced by A, whatever its own mode is. The
   H-owned area must live outside A-writable trees (e.g. `/opt/aimon/hooks`, `/run/aimon`), or be checked by owner
   and mode on every use.
4. **The file-tool owner mismatch (sandbox §13.3).** The files API runs as execd's uid and has no uid argument. If
   commands ran as A while the files API ran as execd (= H, or root), the model's file tools would write as H. That
   would either break ownership expectations or let file tools reach H-owned hook files. A boundary needs the files
   API to act as A (a uid argument, or file tools routed through A-uid execs) and the core's run-directory uploads to
   act as H.
5. **H's processes are unreachable from A**: different uid already denies `ptrace`, signals, `/proc/<pid>/fd` and
   `/proc/<pid>/environ` (given `ptrace_scope` ≥ 1 or a different uid). The hook's output pipe goes to execd, not to a
   file.
6. **The image** provides user H, the H-owned directory, and `sha256sum`/`base64`. The seed checks `id -u H` and the
   directory's ownership, as it checks uid 1000 today.
7. **Local provider:** a boundary needs the agent's `Bash` to run as a different OS user from the JVM (or in a
   container). That cannot be done inside `LocalShell` and is out of scope. `"shell": "host"` is tamper-proof only
   when the agent's shell is not the JVM's uid.
8. **The hook's inputs** (the workspace it inspects) remain A-writable. A policy must not take workspace files as
   trusted, whatever the uid arrangement (R5).

Core SPI it would need: an `ExecutionOptions` "run as the environment's hook identity" flag (or a
`ShellFeature.HOOK_IDENTITY`), declared only when conditions 1–6 hold, and `IsolatedShellActionExecutor` preferring
it.

---

## 8. aimon-sandbox follow-up (the content of `build/sandbox-followup.md`, against `main` `553be78`)

The build writes this out in full as a pasteable task. The design fixes these points.

### 8.1 `RUN_DIRECTORY` in `SandboxShell` (EE-109's sandbox half)

- `supports`: add `ShellFeature.RUN_DIRECTORY` (`SandboxShell.java:609-611`), once core with the constant is the
  dependency.
- The `execute` checks: the run directory's variable is valid. Its paths need no NUL check (they are bytes, not shell
  text) but are re-checked as confined.
- Transport:
  - **Inline**, when the base64 of all files plus the manifest fits a new `ShellWrapper.INLINE_RUN_FILES_LIMIT` (16
    KiB; it shares MAX_ARG_STRLEN with `INLINE_COMMAND_LIMIT` 64 KiB and `INLINE_ENVIRONMENT_LIMIT` 32 KiB, so the
    command limit for a run that carries files is lowered to keep the sum under 128 KiB).
  - **Upload** otherwise: through the files API to `{runPrefix}.files/<relPath>`.
  - Either way the exec argument carries a manifest of `relPath → sha256`.
- In the wrapper, before the watchdog starts:
  - `p=$(mktemp -d /workspace/.aimon-shell/.run.XXXXXXXXXX)` (location: Q5) and `chmod 700 "$p"`;
  - decode or `cp` each file into `$p/<relPath>` (with `mkdir -p` for parents);
  - `cd "$p" && /usr/bin/sha256sum -c` against the embedded manifest (fixed `PATH=/usr/bin:/bin`, as
    `SandboxStaging.verified` does);
  - on a mismatch, `rm -rf "$p"` and exit a new `ShellWrapper.RUN_FILES_TAMPERED_EXIT` (73), which `SandboxShell`
    maps to `RunDirectoryIntegrityException`;
  - remove the upload right after the copy, and pass `VAR=$p` as a per-command variable to the inner bash, after the
    others so it wins;
  - `rm -rf "$p"` in the epilogue and in an EXIT trap of the outer.
- Tests (testkit fake provider): `supports`; inline and upload paths; a tampered upload → `RunDirectoryIntegrityException`;
  a variable that overrides the same name in env; the directory is gone after success, failure, timeout and kill;
  case-distinct files; a non-ASCII file name; the size boundaries of the inline/upload switch.

### 8.2 Run files out of hook/isolated modes (#33)

For `Mode.HOOK` and `Mode.ISOLATED` only:

- **Command:**
  - inline: pass the command to the inner bash as a positional argument (`INNER` takes `__aimon_cmd` from `$6` when
    `$3` is `iso`/`hook`, instead of `read … < "$__aimon_run.cmd"`);
  - over the inline limit: upload to `{runPrefix}.cmd`, copy into the private directory, `sha256sum`-check it against
    the embedded digest, and read it from there. Or refuse with `ShellExecutionException` ("a hook command over 64 KiB
    is not supported"). Q7; the recommendation is to refuse, since `hooks.json` commands are short.
- **stdin** (the hook's JSON payload):
  - inline (in the argument, `printf '%s'` into a pipe to the inner) when it is ≤ the remaining budget;
  - otherwise upload, copy into the private directory, digest check, then `< "$p/in"`.
  - A payload containing NUL is refused (it cannot be carried as shell text).
- **Output:** the inner's stdout and stderr go to the exec's stdout and stderr (no `$r.out`/`$r.err`). The capture cap
  becomes the JVM's job: `SandboxShell` counts and truncates what the `OutputSink` delivers, and sets
  `outputTruncated`. The trailer stays the last stderr line and carries `exit=` only; `out=`/`err=` are counted by
  the JVM (normalisation per §6.1 is accounted for as today's backstop does).
- **Background descendants:** the result completes when the trailer is parsed. `SandboxShell` then `kill()`s the exec,
  which ends the wrapper's process group, so a `cmd &` left by a hook ends with it. Behaviour change for sandbox
  hooks, documented in the sandbox's §9.
- **Timeout:** no `.timedout` file. The watchdog kills the group and the JVM decides "timed out" from its own clock
  (no trailer, elapsed ≥ timeout).
- **No `mkdir -p "$d"`, no `.cwd`.** `iso` keeps its root fallback, but reports it through the trailer
  (`cwd=1|2`) instead of a file.
- `cleanUp(files, runPrefix)` only for uploads.
- Docs: sandbox `workspace-sandbox.md` §9 (hook/isolated rows), §12.1 and §16 (the run-file row), and the ko/en of
  whatever guide repeats them.
- Tests: no `.aimon-shell/<h>/run-*` path exists during an isolated or hook run (a probe command lists the directory
  from inside); a concurrent writer to `.aimon-shell` cannot change the exit code or the stdout seen; output over the
  cap is truncated with the flag; `cmd &` inside a hook does not keep the call open; a timeout is reported as a
  timeout; a large stdin goes through the upload path with a tamper → integrity failure; foreground and background
  modes unchanged (the existing tests pass).

### 8.3 `SandboxStaging`

No functional change: it already verifies on every call (§1). Add a comment stating that this is now core's
`stage()` contract, and a test that pins it (two `stage()` calls, a tamper between them → the second re-copies).

### 8.4 Issues

- Widen aimon-sandbox#33 to "hook/isolated run files + `RUN_DIRECTORY`".
- File "run policy-hook execs under a separate uid" as the sandbox counterpart of EE-113, with §7's conditions.

---

## 9. Test strategy (aimon-core; no aimon-sandbox dependency)

1. **`LocalStagingTest`.** Stage, rewrite a staged file through the raw filesystem, stage again: the second call
   re-copies and returns verified bytes (fails today). The same for a copy this instance made itself (today it is
   trusted through `verified`). Extra-file removal on every call. The EE-3 `rescanned` path still works after a
   tamper. Concurrency: two threads `stage()` while a third tampers; no call returns a mismatching copy.
2. **`ExecutionOptionsTest` / `RunDirectory` unit tests.** Builder validation (bad variable, `..`, absolute,
   backslash, duplicate), defensive copies, `toBuilder()` carry, `toString` without content.
3. **`LocalShell` run-directory test** (new `LocalShellRunDirectoryTest`, Unix):
   - the variable is set, the files hold the bytes and the mode is 0700/0600;
   - the path is outside the working directory;
   - the directory is gone after success, a non-zero exit, a timeout and a cancel;
   - a variable in `getEnvironment()` of the same name is overridden;
   - case-merged names on a case-insensitive tmpdir give `ShellExecutionException` (skipped where the tmpdir is case
     sensitive);
   - an integrity failure through a test seam (a package-private hook between write and verify that mutates a file)
     gives `RunDirectoryIntegrityException`.
4. **`LocalShell` hook I/O.**
   - During an isolated run, a probe command (`ls "$TMPDIR"` or `java.io.tmpdir`) does not find the run's
     `.out`/`.err`/`.in` by name. The command checks for its own files via a marker in its own output.
   - stdin over the pipe buffer (≥ 1 MiB) reaches a reader intact.
   - A hook that never reads a large stdin finishes at its exit.
   - Output from `cmd &` does not block (the `LocalShellOutputDrainTest` analogue for hook mode).
   - The model-facing path is unchanged (existing tests).
5. **`IsolatedShellActionExecutorTest`.**
   - A fake shell that declares `ISOLATED_EXECUTION` but not `RUN_DIRECTORY` → `RUN_DIRECTORY_UNSUPPORTED`; one whose
     `supports` throws `IncompatibleClassChangeError` → the same.
   - With `LocalShell`: `AIMON_HOOK_DIR` is not under `.aimon-staged`; **the EE-109 scenario**: tamper with any
     `.aimon-staged/hooks-*` (none is created any more; assert that) and with the source → the edit is picked up at
     the next firing, as before.
   - A fake shell that throws `RunDirectoryIntegrityException` → `HOOK_FILES_TAMPERED`, and a `preTool` guard with
     `failOpen: true` still **blocks**.
6. **`SkillHookDirectoryTest` / `DefaultShellActionExecutor`.**
   - Run directory on `LocalShell` (`AIMON_SKILL_DIR` outside the workspace, bytes = loaded version).
   - The cache: no source read on the second firing (a counting VFS).
   - EE-3: the source changed after load and no cache entry → the current version + WARN.
   - Fallback: a fake shell without `RUN_DIRECTORY` → `stage()` path, the variable = the staged path, WARN once. A
     tamper of the staged copy between two firings is re-copied (the §2.1 contract through the hook).
   - Over the cap → fallback.
   - The default overload on a third-party executor → `RUN_DIRECTORY_UNSUPPORTED`.
7. **Second provider (test-only)**, following the #273 tests: a fake `ExecutionEnvironment` whose shell implements
   `RUN_DIRECTORY` by "uploading" into an agent-writable fake path, then placing and verifying. The test tampers with
   the upload → `HOOK_FILES_TAMPERED`. This pins the contract rule 3 that the sandbox will implement.
8. **`ShellHookVerdicts` test**: `HOOK_FILES_TAMPERED` ignores `failOpen` on every guard event; it is logged on
   observer events.
9. **Architecture/docs:**
   - `checkAll` (format, checkstyle, javadoc ratchet: the new public types need javadoc);
   - the translation-structure, staleness and doc-link scripts;
   - the in-tree `ShellFeature` switches compile.
10. **Regression:** the existing #273 tests (placement, test 9's cwd, test 10's default placement with
    `AIMON_HOOK_DIR`) pass, with expectations about the variable's *value* updated, and nothing else.

---

## 10. Open questions

- **Q1. Scope of every-call verification.** The design applies it to every `stage()` (the user's decision read
  literally). It costs one hash of the copy per `Skill` invocation and protects mainly the fork-with-narrower-tools
  case. The alternative is to verify every call only for policy consumers (the fallback path), keeping the
  once-per-process check for model-facing calls. That alternative would keep the §4.4 non-goal. Recommendation: every
  call, as written.
- **Q2. Skill hooks on a shell without `RUN_DIRECTORY`.** Fall back to verified staging with a WARN (the design), or
  fail closed like `hooks.json`? Failing closed breaks skill hooks on today's sandbox until the follow-up ships.
- **Q3. Limits.** `HookRunFiles.MAX_BYTES` = 4 MiB and the skill-bytes cache = 32 MiB are guesses. Should either be
  configurable (`AimonProperties`/`ExecutionEnvironmentSpec`) now, or stay constants until someone hits them?
- **Q4. `HOOK_FILES_TAMPERED` ignores `failOpen`.** Recommended (it is attack evidence; `CANCELLED` is the
  precedent), but it is a new class of "failOpen does not open". Confirm.
- **Q5. Where the sandbox's private run directory lives.** `/workspace/.aimon-shell/.run.*` (guaranteed writable by the
  image contract, refused by file tools) or `${TMPDIR:-/tmp}` (outside the workspace, but `/tmp` is not in the image
  contract today, and a read-only root filesystem would break it). The design recommends the former. Both are equally
  reachable by the same uid.
- **Q6. A `"script"` handler field** that core feeds to the interpreter itself (true "verified bytes are what runs"
  for single-file hooks): add it now, or leave it as a backlog candidate? Not designed here.
- **Q7. Oversized hook command text in the sandbox** (> 64 KiB): refuse (recommended) or upload with a digest check?
- **Q8. Killing a sandbox hook's background children when its trailer arrives** (§8.2) is a behaviour change on the
  sandbox side only. Acceptable, or should the sandbox keep waiting until the backstop?
- **Q9. Closing #275.** If the core half lands as designed, the last commit says `Closes #275` (the sandbox half is
  #33). The user's task statement allows that. Confirm that #275 should close before the sandbox ships `RUN_DIRECTORY`,
  given that the guide will still say sandbox hook files are exposed until then (R6).

---

## 11. Where the implementation departed from this design

Written after the build, against the working tree on top of `016e2680`. The approved body above (§1–§10) is not
edited; what turned out differently is recorded here. Most of the design was built as written: every-call verification
in `LocalStaging` and the `stage()` contract sentence (§2.1); the `RunDirectory` SPI, `ShellFeature.RUN_DIRECTORY`,
`ExecutionOptions.getRunDirectory()` and `RunDirectoryIntegrityException` (§2.2, §4.1); `LocalShell`'s run directory
and hook-mode I/O (§2.3); `HookRunFiles` with its 4 MiB cap and 32 MiB LRU cache; `RUN_DIRECTORY_UNSUPPORTED` and
`HOOK_FILES_TAMPERED`, the latter blocking whatever `failOpen` says (Q4 taken as recommended); the skill-hook fallback to
every-call-verified staging (Q2 taken as written); and the backlog actions of §4.6. The departures are listed in full.

### 11.1 Review notes taken, and the ones not taken

The design review passed with nine non-blocking notes.

- **Taken — rescan only a scanned resource.** `HookRunFiles.forSkill` follows `LocalStaging`'s rule: a skill whose source
  no longer matches its key is scanned again only when `StagedResource.isScanned()`. A resource assembled by hand is
  refused (`STAGING_FAILED`, the staging layer's "changed on disk after it was loaded" wording), because a rescan would
  copy files it deliberately left out into a directory the agent's uid can read. Test:
  `SkillHookRunDirectoryTest.editedAssembledSkillIsRefused`.
- **Taken — the default overload falls back instead of refusing (changes F10).** §2.2 had
  `ShellActionExecutor.run(…, Optional<StagedResource>)` return `RUN_DIRECTORY_UNSUPPORTED` by default. The default
  instead stages the skill into the context's environment, exports the path and calls the four-argument `run` — what
  every executor did before, now with every-call verification. The reviewer's point holds: the design already accepts
  that fallback for `DefaultShellActionExecutor` (R4), so holding a third-party executor to a stricter rule would stop
  all of its skill hooks after an upgrade for no gain. The variable is never left unset: a staging failure is still a
  refusal (`STAGING_FAILED` / `ENVIRONMENT_UNAVAILABLE`). Covered by `SkillHookDirectoryTest`, whose recording executor
  does not override the overload.
- **Taken — say what the stdin writer cannot do (F11).** The writer thread is not closed from the main thread after
  `waitFor()`. `Process.getOutputStream()` is a `BufferedOutputStream` whose `write` holds the stream's monitor while it
  blocks, so any close from another thread waits on the same lock. What bounds the thread is that bash gives `cmd &` a
  stdin of `/dev/null`; only an explicit `<&3` or a program that forks itself away keeps the pipe, and then the thread
  (a daemon) ends when that process does. `execute` itself never waits on it. This is written in
  `LocalShell.startStdinWriter`'s Javadoc, and `LocalShellHookIoTest.backgroundHolderOfStdinDoesNotBlock` pins that the
  call returns.
- **Taken — the local re-read is a self-check.** `LocalShell.placeRunDirectory` reads every file back and compares it;
  its Javadoc says this checks the write and is not tamper protection (the bytes never travelled, and a same-uid writer
  can change them afterwards).
- **Taken — where the WARN-once state lives.** A static `ConcurrentHashMap.newKeySet()` in
  `DefaultShellActionExecutor`, keyed by shell class and skill name and bounded at 256 entries; past the bound, a
  fallback is logged at DEBUG. The class doc no longer says "stateless". Test:
  `SkillHookRunDirectoryTest.fallbackStagesAndWarnsOnce`.
- **Taken — a hook-level test that a tamper on an observer event is only logged.**
  `SkillHookRunDirectoryTest.tamperOnAnObserverEventIsOnlyLogged` (`postTool`) and
  `DeclarativeShellHookBindingTest.create_hookFilesTampered_…` (every lifecycle event, `failOpen` on and off).
- **Taken in the docs — the per-run window, and upgrading both sides together.** The guides and the CHANGELOG say that
  the window between the shell's check and the interpreter opening a file (and any later `source`) remains, now per run
  instead of persistent; and that a sandbox deployment whose `hooks.json` handlers name `AIMON_HOOK_DIR` must upgrade
  core and aimon-sandbox together, or move those handlers to `"shell": "host"` first. The §2.4 row and R1 in the body are
  left as approved.
- **Not taken — an ERROR at apply time for R6.** `HookRegistryApplier` builds the hooks before any execution exists and
  does not know which shell a firing will meet, so it cannot tell "this handler will be refused" at apply time. The
  per-firing WARN stays, as `ISOLATION_UNSUPPORTED`'s does.
- **Not taken — a run directory only for skill-hook commands that name the variable.** `hooks.json` attaches its
  directory only when the command text contains `AIMON_HOOK_DIR`, but a skill hook has always had `AIMON_SKILL_DIR` on
  every command, and a script reached some other way may read it from its environment. Adding the textual test would
  have been a behaviour change for those hooks. The cost the reviewer named is bounded by the cache (no source read after
  the first firing) and by the 4 MiB cap; locally the write is a few KiB for a typical skill.
- **Left to the sandbox run — Q5's location.** The reviewer suggested not using `.aimon-shell`, the directory a #33-style
  watcher already polls. `build/sandbox-followup.md` keeps the decision open for that run and records both options; EE-114
  tracks it.

### 11.2 Behaviour that differs from the body

- **Skill hooks lose `skill_dir` in the stdin payload.** Not in the design. The stdin document is rendered by the hook
  from the assembled environment *before* the executor runs, and the value of `AIMON_SKILL_DIR` is now decided by the
  executor (a run directory exists only in the shell). The payload therefore carries no `skill_dir`, as it never carried
  `hook_dir`. The variable is still set. CHANGELOG and `docs/references/aimon-skill-extensions.md` say so.
- **A hook with no skill directory goes through the four-argument `run`.** The design had the three declarative hooks
  call the new overload. They call it only when `SkillHookDirectory.resourceFor` finds a resource
  (`SkillHookDirectory.run`), so a `hooks.json` hook, a resource-less skill and any executor that never learned the
  overload see exactly the call they saw before.
- **The staging limit no longer applies to a skill hook that gets a run directory.** `maxStagedBytes` bounds what
  `stage()` copies; a run directory is bounded by `HookRunFiles.MAX_BYTES` instead (4 MiB), and a skill over that falls
  back to `stage()`, where `maxStagedBytes` applies again. Two integration tests that relied on a one-byte staging limit
  to make a skill hook fail now also take `RUN_DIRECTORY` away from the fork's shell (see §11.4).
- **`LocalStaging` takes both locks on every call.** §2.1 said every call takes the target lock. The fast path that
  skipped the per-resource source lock is gone as well, so concurrent `stage()` calls for one resource now queue on that
  lock while each verifies; calls for different resources do not. The cost is one listing and one hash of the copy
  per call, as §2.1 estimates.
- **`HOOK_DIR_STAGING_FAILED` keeps its name.** It now means "the hooks directory could not be read for the run"
  (unreadable, over the cap, or changing while read); its Javadoc says the name is kept from when the directory was
  staged. `ENVIRONMENT_UNAVAILABLE` no longer arises from the directory step, because nothing calls `stage()` there.
- **`HOOK_FILES_TAMPERED` carries no detail.** The reason is the fixed description; the shell's message (which names
  the file) goes to the WARN only.

### 11.3 What the design left to the builder

- **The shape of `ConfigHookDirectory.runDirectory`.** It returns a small package-private `Prepared` holder (a run
  directory, or the not-run outcome) rather than an `Either` or an out-parameter.
- **Where the moved staging code lives.** §4.3 moved the stage-and-export code to the executor. It stays in
  `SkillHookDirectory` as `stageInto(env, environment, resource)` and `refuse(…)`, because two callers need it: the
  interface's default overload (§11.1) and `DefaultShellActionExecutor`'s fallback.
- **`EnvironmentShells.declares(shell, feature)`.** The `catch (RuntimeException | LinkageError)` guard for `supports`
  is one helper, used for `RUN_DIRECTORY` by both executors; the older `supportsIsolation` is untouched.
- **`LocalShell.execute` was split.** Checkstyle's 150-line method limit forced the redirect handling into a private
  `Redirects` class (the temp files, the read channels, the unlink after start and the stdin writer). The model-facing
  path is unchanged.
- **A read failure in `HookRunFiles` is a mismatch, not an exception.** A file that cannot be read while the bytes are
  collected sends `forHookDirectory` to its one re-read and `forSkill` to the EE-3 path, as `LocalStaging` treats a
  recorded file that has gone.

### 11.4 Tests: what differs from §9

- **Written as listed:** `LocalStagingTest` (own copy rewritten, adopted copy rewritten, planted sibling removed on every
  call, the re-scanned copy checked on every call), `RunDirectoryTest`, `ExecutionOptionsTest`,
  `WorkingDirectoryShellTest`, `LocalShellRunDirectoryTest`, `LocalShellHookIoTest`,
  `IsolatedShellActionExecutorTest`, `SkillHookRunDirectoryTest`, `RunDirectoryUploadContractTest` (§9 item 7, the
  second provider), and the `HOOK_FILES_TAMPERED` cases in `DeclarativePreToolHookTest` and
  `DeclarativeShellHookBindingTest` (§9 item 8; there is no separate `ShellHookVerdictsTest`, the verdict is tested
  through the hooks, as the existing causes are).
- **Not written: §9 item 1's concurrency test** ("two threads stage while a third tampers; no call returns a mismatching
  copy"). A tamperer that writes after `stage()` returns makes any returned copy mismatching, so the property cannot be
  asserted from outside without a seam inside the target lock. The every-call check is pinned by the sequential tests,
  and the lock discipline is the existing EE-17 code and its tests.
- **The hook-I/O probe matches by inode.** "The command does not find its own files by name" is checked by comparing the
  inode of the command's descriptors 0–2 with the `aimon-shell-*` files in `java.io.tmpdir`; a plain listing picked up
  other test JVMs' capture files. A model-facing command run through the same probe finds all three, which shows the
  probe works.
- **Regression tests changed for the new values.** `LocalExecutionEnvironmentProviderStagingTest` (EE-37: verified on
  every call, not once), `HooksJsonExecutionEnvironmentIntegrationTest` (`AIMON_HOOK_DIR` is an `aimon-run-*`
  directory outside the workspace, gone after the run, with no `hooks-project` copy), and
  `SkillHookSkillDirIntegrationTest` (the run directory; a new case for the fallback; the two "cannot be staged" cases
  run on a shell without `RUN_DIRECTORY`). `SkillHookDirectoryTest` now calls `resourceFor` / `stageInto` and asserts
  that the payload has no `skill_dir`.

### 11.5 Open questions moved to the backlog

Tracked in [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md):
EE-113 ← §7 (a privilege boundary between the agent and in-environment policy hooks; the sandbox counterpart is to be
filed in aimon-sandbox as "run policy-hook execs under a separate uid"), EE-114 ← §8 with Q5, Q7 and Q8 (aimon-sandbox
declares `RUN_DIRECTORY` and takes hook/isolated I/O off run files; [kangwoo/aimon-sandbox#33](https://github.com/kangwoo/aimon-sandbox/issues/33)),
EE-115 ← Q6 (a `"script"` handler field), EE-116 ← Q3 (the two limits as constants). Q1, Q2 and Q4 are settled inside
this change as the body recommends. Q9 is settled by the task: the core half has landed, so the change closes #275 and
the sandbox half stays open as EE-114 / aimon-sandbox#33.
