# Implementation plan — `ExecutionEnvironment` (docs/design/tool/execution-environment.md)

> Status: **IMPLEMENTED** (2026-09-28). This is the implementation plan for
> [`execution-environment.md`](execution-environment.md) exactly as it was approved in design review (three review
> rounds; the last one passed with three non-blocking notes). The body below is unchanged and keeps the plan's own
> vocabulary — "HANDOFF", "Q1…Q20", stage commits. It is written in English because that is how it was reviewed; the
> `design/` directory is not a translation target (`docs/project/documentation-guide.md` §5.1).
>
> Two things were added. §10 records where the implementation departed from this plan, and why. The open questions
> in §9 whose consequences reach beyond this change are tracked in
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md), which is
> the canonical open/closed list. Each item there names the `Q` number or §10 entry it came from.

Spec: `docs/design/tool/execution-environment.md` (580 lines, Status PROPOSED). The spec decides the behaviour; this
document decides **where in this codebase** each piece goes, in what order, and how each stage is tested. Section
references like "§5.1" point at the spec. Wherever this plan goes further than the spec (the spec is silent, or the
code differs from what it describes), the entry is marked **[plan decision]** and repeated in §9 Open questions.

**Revision 3 (this run).** The plan below is the round-2 plan with two sets of changes:

- **Blocking issue from review 3 (skills without a VFS source), resolved per the user's decision "소스 VFS 부여".**
  Every `SkillRepository` now exposes a `stage()`-able source, so every skill carries a `StagedResource` and
  `${AIMON_SKILL_DIR}` is always the return value of `stage()`. §4.4 and §15 of the spec are kept as written, and a
  rule is added to spec §4.4. See the new ground-truth items §1.10–§1.12, the new §2 rows, §3.1 (`StagedResource`),
  §3.3 (skill rows), the rewritten stage 3 "Skill side" block, its tests, the Docs table and Q17–Q20.
- **Review 3 nits.** One ArchUnit rule name everywhere (`onlyAgentContextAndCompactMayReachEnvironmentFromAgentTree`);
  the artifact archive key uses the prefix `"archive"`, not `"turn"`; the `SkillTool` row says what stage 2 does;
  `WriteTool(boolean useRelativePaths)` is kept; stage 4 tests that a branch-local `.aimon/` is not denied.

---

## 0. Problem, restated

Today the model's file tools (`Read`/`Write`/`Edit`/`Grep`) and `Bash` receive their `VirtualFileSystem` and
`VirtualShell` **through their constructors** when the agent-scoped `ToolRegistry` is built. So one agent has one
filesystem and one shell for its whole life. Workflow isolation works around this by cloning the registry per branch,
and even then it covers only the file tools; `Bash` stays on the base shell. The same VFS also holds the framework's
control plane (skill, agent and command definitions, task outputs, snapshots), so the model can overwrite them with
`Write`. The prompt describes the JVM host instead of the place commands actually run. `WikiIngest` reads a context
key that nothing ever sets. The spec fixes all of this by introducing a neutral `ExecutionEnvironment` SPI. It is
resolved **once per execution** by an application-scoped `ExecutionEnvironmentProvider` and published in a
write-once `ToolContext` key. It supplies the filesystem, shell, descriptor, optional content search, staging and
isolation that tools and prompt assembly read on every call. The control store becomes a separate
`controlFileSystem`. This plan maps the spec's five §11 stages onto concrete files, types, tests and commits in this
repository.

---

## 1. Prerequisites and ground truth that the spec does not state

1. **PR #193 is not on this branch.** The branch base (`docs/execution-environment`, head `975c4f0`) predates
   `origin/main`'s `50a13dc Merge pull request #193`. §4.4 assumes `at.aimon.core.skill.render.SkillRenderContexts`
   and `at.aimon.core.tools.SkillRenderContextAccess.builderFor(Skill, ToolContext)` already exist. They exist only on
   `origin/main`. **Step 0 of the build: merge (or rebase onto) `origin/main`** before stage 1. The other
   `origin/main`-only commits are dependency bumps (#184–#186) and do not conflict with the two docs commits on this
   branch.
2. **The shell `grep` in this environment is a wrapper function** that reports Korean-comment files as "binary"
   and drops their matches. Use `command grep -a` when auditing call sites. Several key files (for example
   `DefaultWorkflowContext.java`) are otherwise invisible.
3. **There are three execution entry points that build a `ToolContext`, not two.** §5.1 names
   `OrcaAgentExecutor.createToolContext()` and `DefaultSubagentExecutor.createToolContext()`. There are also:
   - `OrcaAgentExecutor` slash-command path (≈ line 2081, `commandToolContext`). This path renders skill-backed
     commands, which §4.4 and §6 require to go through `env.stage()`.
   - `at.aimon.core.scheduling.RoutineExecutor.buildToolContext(...)` (≈ line 433). A scheduled routine runs tools
     such as `Bash` directly from `agentRuntime.findToolByName(...)`. It is an execution by the glossary
     (`ExecutionId.generate("routine:…")`). If it gets no environment, every routine `Bash`/`Read` step starts failing
     at stage 2. **[plan decision]** The routine path resolves an environment as well (§3.1).
4. **Three `ToolContext` copy sites** rebuild a context with `ToolContext.builder().putAll(ctx.getContext())`:
   `SingleToolInvoker:165`, `OrcaAgentExecutor:2248` (`resolveEffectiveToolContext`) and `RoutineExecutor:564`. There
   is also the public constructor `new ToolContext(Map)`. A write-once check that learned its names from the key
   objects put into one builder would lose them across every copy. §2 therefore keeps the write-once names in a
   registry owned by `ToolContextKey`, which the copy paths cannot drop.
5. **`resolve()` must be hoisted.** In `OrcaAgentExecutor.execute(...)`, `assembleContext(agentRuntime, agent)`
   (≈ line 1044) and the system-prompt build (≈ line 1049) run long before `createToolContext` (≈ line 1577). The
   environment has to be resolved at the top of `execute(...)` and carried on `ExecutionScope`.
6. **Nobody in main calls `OrcaAgentRuntimeFactory.withShell(...)`.** Every runtime today owns one
   `LocalShells.create()` shell with **no working directory** (JVM cwd), even when the files live elsewhere. CLI:
   `LocalFileSystem` at the jar directory. Bootstrap: per-runtime `LocalFileSystem` under
   `AgentWorkspaceLayout.resolve(root, runtimeId)`, or a supplied/factory VFS. So "shell and file tools see the same
   filesystem" is not true today. The local provider fixes it by rooting the shell at the workspace.
7. **Control-plane paths are `.aimon/*` relative to the workspace VFS** (`StackPaths.*`,
   `OrcaAgentRuntimeFactory` defaults `".aimon/commands"`, `".aimon/agents"`, `".aimon/skills"`).
8. **The Mongo driver is 5.12.** `GridFSFile.getMD5()` was removed in driver 5.0, so §7's "GridFS(md5)" is not
   implementable as written. See §6 stage 5 and Q6.
9. **There is no "CLI project initialisation" writing `.gitignore`.** §9.2 says one adds `.aimon-staged/` to it. No
   such code exists in `aimon-cli` main (Q4).
10. **Skills reach `SkillTool` from three kinds of repository, and only one is VFS-backed.** Each layer of the
    default wiring has a different source:

    | Layer | Built by | Repository | Where the files are |
    |---|---|---|---|
    | user skills | `DefaultSkillRegistry(fs, ".aimon/skills")` | `VfsSkillRepository` | workspace VFS (control fs from stage 3) |
    | materialized bundled skills | `buildMaterializedSkillRegistry` (bootstrap, so also the CLI) | `VfsSkillRepository` over `.aimon/bundled-skills` | copied from the classpath at startup by `BundledSkillMaterializer` |
    | bundle's own registry (fallback layer in bootstrap; the only bundled layer in `buildSkillRegistry`, which `OrcaAgentRuntimeFactory.doCreate` uses when no registry is injected) | `AdaptiveAgentBundleLoader` | `PathSkillRepository` when the bundle resolves to `file://` (IDE, `gradle run`), otherwise `ClasspathSkillRepository` | host directory, or classpath resources |

    - In the CLI, `resource-demo` normally resolves through the **materialized** layer: `BundledSkillMaterializer`
      copies it to `.aimon/bundled-skills/resource-demo/`, and that layer shadows the bundle layer. It falls to the
      bundle layer only when materialization skipped the skill, for example on an unenumerable classpath layout or an
      unreadable resource.
    - `BundledSkillMaterializer` and `ClasspathSkillRepository` already share `ClasspathResourceTreeWalker` (walk with
      a `SKILL.md` anchor; `jar:`/`file:`/Boot nested jars; `ResourceTreeListing.unsupported(protocol)` otherwise) and
      `ClasspathIndexReader`. A classpath staging source built on the same walker therefore sees exactly the file set
      the materializer copies.
    - `ClasspathSkillRepository` returns empty maps from `findRootFiles/findScripts/findReferences/findAssets` and
      inherits the empty `findAllFiles`/`resolveBaseDir` defaults. So today a classpath skill has no `baseDir`, and
      `${AIMON_SKILL_DIR}` renders empty for it.
11. **`skill.repository` cannot construct `LocalFileSystem`.** `filesystemImplMustNotLeakOutsideFilesystemTree`
    allows `at.aimon.core.filesystem.impl..` only from the `at.aimon.core.filesystem..` tree (plus the
    `agent.impl.orca.environment..` carve-out). `FileSystemAgentBundleLoader` (`agent.impl`) cannot construct it
    either. Classes directly in `at.aimon.core.filesystem` belong to no slice of the filesystem cycle rule
    (`filesystem.(*)..`), so a factory there may reference `filesystem.impl` without adding a cycle.
    `LocalFileSystem.initialize()` creates a missing base directory and rejects a non-writable one, and every read
    checks that it was initialised. So `LocalFileSystem` cannot serve as a read-only source over an installed skills
    directory (review-1). The Path source is a separate `ReadOnlyLocalFileSystem`, which holds no resources and so
    needs no teardown.
12. **`SkillTool` shows the model each skill's source paths.** `formatSkillOutput` lists "Available Files" with the
    repository's full paths as values (`SkillTool.java` on `origin/main`, ≈ lines 436–510). From stage 3 these are
    control-store paths, which the file tools deny and which do not exist in a non-local environment. Stage 3 rebases
    the listing onto the staged directory (see the stage 3 "Skill side" block).

---

## 2. Approach and rejected alternatives (implementation-level only)

The spec's own rejected alternatives (§12) stand. The choices below are the ones this plan had to make.

| Decision | Chosen | Rejected, and why |
|---|---|---|
| How forks get the provider | Executors publish a second write-once key `EXECUTION_ENVIRONMENT_PROVIDER` next to `EXECUTION_ENVIRONMENT`. Every builder of a `SubagentExecutionEnvironment` (TaskTool, WorkflowTool, GraalJsWorkflowTool, SubagentBackedSkillForkExecutor) copies both from the invoking `ToolContext`, exactly as it already copies principal, signal and invoking session. | (a) Put the provider on `OrcaToolProviderContext` and forward it via tool constructors (the `toolContextEnrichers` pattern). Rejected: it recreates a registration-time handle to "an environment source", which §6 deletes `getFileSystem()/getShell()` to prevent. (b) Put it on `DefaultSubagentExecutor`. Rejected: that executor is application-scoped and shared across runtimes, but bootstrap needs **per-runtime** providers (point 6). |
| Write-once enforcement | `ToolContextKey.writeOnce(name, type)` creates the key and records `name` in a static, append-only `Set<String>` owned by `ToolContextKey`, queried through `ToolContextKey.isWriteOnceName(String)`. `ToolContext.Builder.put(String, …)`, `put(ToolContextKey, …)` and `putAll` all go through a single check: if the name is write-once and the builder already holds a value for it, throw `IllegalStateException`. Because the registry is keyed by name and does not depend on the builder's history, `putAll(ctx.getContext())` copies stay protected with no change to the three copy sites: the first write into a fresh builder succeeds and any second write throws. `new ToolContext(Map)` builds an immutable map, which cannot be written to, so it needs no guard. The registry is filled when the class holding the constant is initialised; `ToolContextKeys` is always initialised first because every executor references `EXECUTION_ENVIRONMENT` before anything else can write. **[plan decision]** | (a) Per-builder name memory with `toBuilder()` propagation. Rejected: every `putAll(getContext())` copy site, present or future, silently drops the protection (review-1). (b) A static list of names inside `agent.tool`. Rejected: it would import `at.aimon.core.tools.ToolContextKeys` backwards into `at.aimon.core.agent.tool`. |
| `UnavailableExecutionEnvironment` and helper location | `UnavailableExecutionEnvironment` and `ExecutionEnvironments.resolveOrUnavailable(provider, request)` go in the neutral `at.aimon.core.environment`. Reading the env out of a `ToolContext` goes in `at.aimon.core.tools.ExecutionEnvironmentAccess.require(ToolContext)`, following the existing `InvokingSessionAccess` and `SkillRenderContextAccess` precedent, so `environment` never depends on `tools` or `agent.tool`. | `environment.impl`. Rejected by convention rather than by an existing rule. CLAUDE.md's package rule is that code outside a domain reads its neutral package, not `*.impl`, and stage 1 adds `environmentImplMustNotLeakOutsideEnvironmentTree` (§6.0) to enforce that. The earlier draft said an existing rule blocked this; no rule covered `environment.impl`, and that premise was wrong. |
| ArchUnit (full list in §6.0) | Accept **one** new top-level cycle, `agent <-> environment`, add it to `BASELINE_TOP_LEVEL_CYCLES` with a rationale, and bound it with two new guard rules so it cannot grow. **[plan decision]** The cycle comes from the spec itself. `EnvironmentRequest` (§4.1) names `Agent`, `AgentRuntimeId`, `SessionId` and `ExecutionId`, all in `at.aimon.core.agent..`. Prompt assembly in `agent.context` must render the execution's `EnvironmentDescriptor` (§10). | (a) Move the SPI under `at.aimon.core.agent.environment`. Rejected: it contradicts spec §3.1, and the spec places the SPI outside `agent` so external modules can implement it without reaching into `agent`. (b) Break the cycle by moving `EnvironmentDescriptor` into `agent`, or by passing descriptor fields as strings through `ContextAssemblyRequest`. Rejected: it splits the SPI across two packages or erases its type to dodge a counter. (c) Change `EnvironmentRequest` fields to strings. Rejected: it discards the typed identities the spec defines. |
| Old impl-leak carve-out timing | Stage 1 **adds** `at.aimon.core.environment.impl..` to `filesystemImplMustNotLeakOutsideFilesystemTree` and `shellImplMustNotLeakOutsideShellTree`. It keeps `at.aimon.core.agent.impl.orca.environment..` only while that package still has impl users (`LocalShells` until stage 2, `WorktreeToolEnvironmentFactory`/`WorktreeMerge` until stage 4) and removes it in stage 4. **[plan decision]** | Moving the rule outright in stage 1 (a literal reading of §4.2/§11). Rejected: `LocalShells` (`shell.impl`) and `WorktreeToolEnvironmentFactory`/`WorktreeMerge` (`ScopedVirtualFileSystem`) would either break the build until stages 2 and 4 or have to be relocated only to be deleted later. The two `LocalExecutionEnvironment`s still never coexist, because the old one is deleted in stage 1. |
| `EnvironmentRequest.agent` | Optional (`Optional<Agent>`). A main turn sets it from `runtime.getAgent()`, and so does a routine (`RoutineExecutor` already holds the `AgentRuntime`). Forks and workflow runs leave it empty and always set `parent` when there is one. `agentRuntimeId` stays required. **[plan decision]** | Making it required, as the spec's table implies (it has no `?`). Rejected: `SubagentExecutionEnvironment`, `SubagentExecutionContext` and the fork forwarders carry no `Agent`. Adding one would mean a new constructor argument on TaskTool, WorkflowTool, the skill fork and the GraalJs tool, and it would widen `workflowMayDependOnlyOnSubagentSpiTypes` to include `Agent`, all for a value a provider can derive from `agentRuntimeId` + `parent`. |
| Bootstrap provider lifetime | One provider **per runtime**, owned by the runtime's `ResourceSink`. It is closed on eviction or stack teardown and never by `AgentRuntime.close()`. **[plan decision]** It deviates from §4.3 ("Application"), which the spec allows ("or the lifetime the provider decides"). It mirrors today's per-runtime VFS ownership in `StackAgentRuntimeProvisioner`. Consequence: a background `Bash` task that outlives an evicted runtime loses its shell, so its future fails with a shell-closed error that `BashOutput` reports. That is the same as today, where `ownedShell` closes with the runtime. | One application-scoped provider keyed by runtime. Rejected for now: bootstrap already builds the workspace per runtime, and a shared provider would need its own per-runtime eviction hook. |
| Runtime factory ↔ provider | `OrcaAgentRuntimeFactory.withExecutionEnvironmentProvider(p)`. Stage 1: when unset, the factory builds a **borrowing** local provider over the passed `fileSystem` and the effective shell. Stage 2: the provider is required, and `create(...)` without one throws `IllegalStateException`. | A provider parameter on each of the four `create(...)` overloads. Rejected: signature churn across every test, and the other collaborators are already `with*`. |
| `Environment` after §10 | Keep the class, now holding only `timeZone` (§14 is out of scope, and the task says to keep current behaviour). | Renaming it to `UserLocale`. Deferred to §14. |
| `ContentSearch` result shape | A normalised value (`ContentSearchResult` = matches with path, line number, line, context lines, plus per-file counts), parsed from `rg --json` inside the local impl. `GrepTool` formats both paths with its existing formatter, so the output is byte-identical to the fallback. **[plan decision; §14 leaves it open]** | Returning raw `rg --json` text. Rejected: `GrepTool` would need two formatters, and the sandbox would be tied to rg's wire format. |
| Staging source for skills that do not live on a VFS (review 3, user decision "소스 VFS 부여") | `SkillRepository` gains the **abstract** method `Optional<SkillSource> resolveSource(String skillName)`. `SkillSource` is a `VirtualFileSystem` plus a directory on it, and the result is empty only when the skill does not exist. It replaces `resolveBaseDir`. Each repository supplies a VFS: **Vfs** its own fs and `{base}/{name}`; **Path** a read-only `ReadOnlyLocalFileSystem` (not a `LocalFileSystem`, see the next row) rooted at its `skillsBasePath` and `{name}`; **Classpath** a read-only classpath VFS (`ClasspathSkillSourceFileSystem`, built on `ClasspathResourceTreeWalker`) rooted at its `basePath` and `{name}`. `DefaultSkillRegistry` turns the source into a `StagedResource` with one shared scan (`StagedResource.scan`). So every skill a registry loads has one, and `${AIMON_SKILL_DIR}` is always `env.stage(...)`. **[plan decision within the user's choice]** | (a) Build a classpath `StagedResource` from `findAllFiles` (the user's other permitted option). Rejected: `StagedResource` would need a second, non-VFS reader shape (relative path → stream). That changes the spec §4.1 row and gives every provider, the sandbox included, two copy paths. The classpath repository also returns no file map today, so the walk would have to be written anyway. (b) Keep `resolveBaseDir` for Path skills (a host path). Rejected: it fills `${AIMON_SKILL_DIR}` outside `stage()` (§15), and the path does not exist under a non-local provider. (c) "Render empty + WARN" for non-VFS skills. Rejected by the user; it regresses `gradle run` bundled skills. (d) Make it a `default` method returning empty. Rejected: custom repositories would silently opt out of staging. |
| Where Path's `LocalFileSystem` is built | New neutral factory `at.aimon.core.filesystem.VirtualFileSystems.readOnlyLocal(Path root)`, which returns a new `ReadOnlyLocalFileSystem` (`filesystem.impl.local`). It is a small `java.nio`-backed read-only VFS and is **not** built on `LocalFileSystem`. Its `initialize()` does nothing: it creates no directory and does not check that the root is writable. Every mutating method throws `UnsupportedOperationException`. `PathSkillRepository` calls the factory once in its constructor, and construction does no I/O at all. **[plan decision; review-1 of this run]** | (0) Wrap an initialised `LocalFileSystem` in a read-only decorator (this plan's previous revision). Rejected (review-1): `LocalFileSystem.initialize()` creates a missing root and throws `ConfigurationException("Base path is not writable")` on a read-only one. Every read calls `checkState()` first, so skipping `initialize()` is not possible either. `PathSkillRepository` would then fail on read-only skill directories (ConfigMap volumes, `readOnlyRootFilesystem` images), which work today, and would create directories on the host as a side effect. (0b) A read-only mode flag on `LocalFileSystemConfig`. Rejected: it adds a mode to a 800-line writable backend for a use that needs five read methods. (a) Carve `skill.repository` out of `filesystemImplMustNotLeakOutsideFilesystemTree`. Rejected: it widens a rule that exists to keep one assembly point per impl (§1.11). (b) Pass the VFS into `PathSkillRepository`'s constructor. Rejected: its only main caller, `FileSystemAgentBundleLoader`, is also barred from `filesystem.impl`, so the problem just moves. (c) A writable `LocalFileSystem`. Rejected: `Skill.getStagedResource()` is public, and a writable handle would let any caller write into a host skill directory. |
| `BundledSkillMaterializer` | **Kept unchanged** (target directory relative to the control root from stage 3). Materialized skills are VFS skills, so they stage like user skills. The classpath source is the fallback for skills the materializer skipped, and the only classpath path in `buildSkillRegistry`. | Retire it now that the classpath layer can stage directly. Deferred (Q17): it changes bootstrap layering and `StackPaths`, affects the starter's AOT hints (`AimonRuntimeHints`) and the documented `.aimon/bundled-skills` layout, and the task does not ask for it. The cost of keeping it is one extra copy (control store → staging area) per skill version. |
| Stamp key and path-rule normalisation | One shared static helper, `at.aimon.core.filesystem.VfsPaths.rootRelative(String workingDirectory, String path)`, extracted from `ScopedVirtualFileSystem.toBranchRelative` + `normalizeRelative` so the two cannot drift. It returns a **VFS-root-relative** normalised path. If the base working directory is `'/'`-anchored (local), it strips the base, and an absolute path outside the base is returned unchanged. If the base is URI-shaped (`gridfs://…`, `s3://…`), it strips leading slashes. In both cases it resolves `.` and `..`. So `a.txt`, `./a.txt`, `/a.txt` (URI base) and `{base}/a.txt` (local base) all collapse to `a.txt`, matching how each backend already treats leading slashes. Stamp keys (stage 5) and `PathRuleVirtualFileSystem` (stage 3) both use it. `ScopedVirtualFileSystem` is refactored onto it with no behaviour change. | (a) Adding `VirtualFileSystem.normalize(path)`. Rejected for now: it is a VFS SPI change the spec does not ask for (Q5). (b) Absolute paths against `getWorkingDirectory()`. Rejected: there is no meaningful "absolute" for URI bases (review-1). |

---

## 3. Shared shapes (introduced in the stage named)

### 3.1 New package `at.aimon.core.environment` (stage 1 unless noted)

All value types are `final class` with a builder, per the CLAUDE.md convention (no records).

```java
public interface ExecutionEnvironment {                        // not Closeable (§4.1)
    VirtualFileSystem fileSystem();
    VirtualShell shell();
    EnvironmentDescriptor descriptor();
    default boolean durable() { return true; }
    String stage(StagedResource resource);                      // stage 1: same-instance passthrough only
    default Optional<ExecutionEnvironment> isolate(String branchKey) { return Optional.empty(); } // impl: stage 4
    default Optional<ContentSearch> contentSearch() { return Optional.empty(); }                  // added: stage 5
}
public interface ExecutionEnvironmentProvider { ExecutionEnvironment resolve(EnvironmentRequest request); }
```

| Type | Fields / members | Stage |
|---|---|---|
| `EnvironmentDescriptor` | `workingDirectory`, `platform`, `osVersion`, `shellName`, `notes` (all `Optional<String>` getters except `workingDirectory`); `static EnvironmentDescriptor unavailable(String cause)` | 1 |
| `EnvironmentRequest` | `AgentRuntimeId agentRuntimeId` (required). Optional: `Agent agent` (see §2), `SessionId sessionId`, `ExecutionId executionId`, `SessionId invokingSessionId`, `Principal principal`, `ExecutionEnvironment parent`, `String branchKey`. | 1 |
| `StagedResource` | `VirtualFileSystem sourceFileSystem`, `String sourceDir`, `String contentKey`, `String name`, `long totalBytes`, and from stage 3 `List<String> files` (the relative paths `scan` hashed, and exactly what `stage()` copies). Stage 3 adds `static StagedResource scan(VirtualFileSystem fs, String dir, String name)`. It lists `dir` recursively, drops directories and paths ignored by `dir/.stageignore` (`StageIgnore`), and hashes `sorted(relPath + '\0' + bytes)` with SHA-256, keeping 16 hex characters. It sums `totalBytes`. Both the registry (hash) and `stage()` (copy) use its file-set rule, so they cannot disagree. A source needs only the read side of the VFS: `exists`, `isDirectory`, `listRecursive`, `read`. The javadoc states this. | 1 (`totalBytes`, `scan`: 3) |
| `UnavailableExecutionEnvironment` | `static ExecutionEnvironment of(Throwable cause)`. `fileSystem()`/`shell()` return proxies whose every call throws `ExecutionEnvironmentUnavailableException(cause)`. `stage()` throws. `descriptor()` = `EnvironmentDescriptor.unavailable(cause)`. | 1 |
| `ExecutionEnvironments` | `resolveOrUnavailable(provider, request)`: catches `RuntimeException`, logs WARN, and returns `UnavailableExecutionEnvironment.of(e)`. A `null` provider or a `null` return is treated as a failure. It has no `ToolContext` dependency. | 1 |
| `at.aimon.core.tools.ExecutionEnvironmentAccess` (tools package, not environment) | `require(ToolContext)` returns the env or throws `IllegalStateException("No execution environment in tool context")`; tools catch it and return `ToolResult.error`. `providerOf(ToolContext)` returns an `Optional`. | 1 |
| `EnvironmentProviding` | `ExecutionEnvironmentProvider getExecutionEnvironmentProvider()`. It is implemented by `OrcaAgentRuntime` and read by `RoutineExecutor` through `instanceof`; a runtime that does not implement it gets an Unavailable env whose cause names the runtime class. **[plan decision]** It keeps `at.aimon.core.agent.AgentRuntime`, which lives in the agent-core allow-list, from depending on `environment`, so the only agent-side edge into `environment` is `agent.context` (§6.0). | 1 |
| `exception/ExecutionEnvironmentUnavailableException`, `exception/StagingException` | unchecked | 1, 3 |
| `FileStamp` | `long size`, `Instant modifiedAt`, `Optional<String> etag`; `static FileStamp of(FileMetadata)`. `equals` compares etag when both sides have one, and size+modifiedAt otherwise. | 5 |
| `ContentSearch`, `ContentQuery`, `ContentSearchResult` | query = GrepTool's inputs (pattern, path, glob, type, caseInsensitive, before/after/context lines, outputMode, headLimit, multiline) | 5 |

`ToolContextKeys` (stage 1):
```java
public static final ToolContextKey<ExecutionEnvironment> EXECUTION_ENVIRONMENT =
        ToolContextKey.writeOnce("executionEnvironment", ExecutionEnvironment.class);
public static final ToolContextKey<ExecutionEnvironmentProvider> EXECUTION_ENVIRONMENT_PROVIDER =
        ToolContextKey.writeOnce("executionEnvironmentProvider", ExecutionEnvironmentProvider.class); // [plan decision]
```
Stage 2 deletes `VIRTUAL_FILE_SYSTEM`. Stage 5 deletes `ReadTool.READ_FILES_KEY` and adds
`ReadTool.FILE_STAMPS_KEY : ToolContextKey<Map<String, FileStamp>>` (the value is a `ConcurrentHashMap`).

### 3.2 `at.aimon.core.environment.impl`

- `LocalExecutionEnvironmentProvider implements ExecutionEnvironmentProvider, AutoCloseable`. Builder:
  - `.workspaceRoot(Path)`: the provider **owns** a `LocalFileSystem` and a `LocalShell(workspaceRoot)` and closes
    them in `close()`;
  - or `.fileSystem(VirtualFileSystem)` (borrowed) + an owned `LocalShell` rooted at `Path.of(fs.getWorkingDirectory())`
    when that is a local path, and `LocalShell()` otherwise. This mode covers `FileSystemSpec.supplied/factory` with
    GridFS/S3 (today's behaviour: files remote, shell local).
  - stage 1 only: `.shell(VirtualShell)` (borrowed), used by the factory's default path while `withShell/ownedShell`
    still exist;
  - stage 3: `.pathRules(List<PathRule>)` (defaults `.aimon/`→DENY, `.aimon-staged/`→READ_ONLY in `workspaceRoot`
    mode), `.stagingRoot(".aimon-staged")`, `.maxStagedBytes(long)`;
  - stage 5: `.contentSearch(boolean auto)` (probes `rg` on `PATH` once at build time with `Files.isExecutable` over
    the PATH entries, spawning no process).
  - `resolve(req)`: returns `req.parent()` when present (§5.2), and the single `LocalExecutionEnvironment` otherwise.
- `LocalExecutionEnvironment` (package-private `final class`). Holds `rawFileSystem` (unwrapped, used by `stage()`
  and staging cleanup), `toolFileSystem` (path-rule wrapped from stage 3), the shell, and the descriptor
  (platform/osVersion computed **inside `environment.impl`** from `System.getProperty("os.name"/"os.version")` with
  the same mapping as `Environment.detectPlatform()` (`darwin`/`windows`/`linux`/raw), so the prompt text is
  byte-identical, plus the workspace dir and `shellName` from `LocalShell`). It does **not** call
  `at.aimon.core.agent.Environment`, which is outside `environmentSpiDependenciesAreCurated`'s agent-type set
  (review-2). The duplicate mapping in `Environment` disappears in stage 5 together with the fields.
- **What `stage()` returns (all local envs).** The value is the **absolute** path of the copy, built as
  `VfsPaths.join(rawFileSystem.getWorkingDirectory(), stagingRoot, name, contentKey)`. For a local base that is a real
  host path such as `/proj/.aimon-staged/{name}/{key}`, so the shell resolves it whatever its cwd. The file tools
  accept it because every VFS already takes absolute paths under its base. For a URI base it is root-anchored
  (`/.aimon-staged/…`), which those backends accept. The same-instance passthrough (stages 1–2, and the
  `.fileSystem(vfs)` mode when the source *is* the workspace fs) returns the source directory in that same absolute
  form.
- `LocalIsolatedEnvironment` (stage 4):
  - **Filesystem.** `new ScopedVirtualFileSystem(parent.toolFileSystem, ".worktrees/" + key, Set.of(stagingRoot))`.
    It sits over the parent's *path-rule-wrapped* fs, not the raw one, and takes a new constructor argument,
    `sharedPrefixes`. A caller path whose root-relative form (via `VfsPaths.rootRelative`) begins with a shared prefix
    is passed to the delegate **without** the branch prefix. All other paths are scoped as today. As a result:
    - `/proj/.aimon-staged/n/k/x.sh` from a branch `Read` goes to the delegate as `.aimon-staged/n/k/x.sh`, which is
      the parent's copy. It no longer lands at `.worktrees/{key}/.aimon-staged/…` (the review-1 finding).
    - The parent's path rules see root-relative paths, so `READ_ONLY .aimon-staged/` protects the real staging area
      from the branch's file tools. `DENY .aimon/` still applies, although scoping already puts the root `.aimon/` out
      of a branch's reach.
    - `listRecursive(".")` on the branch walks only `.worktrees/{key}`. Staged copies therefore never appear in the
      branch listing, and `WorktreeMerge` never promotes them.
    - The shared-prefix check runs after normalisation, so `a/../.aimon-staged/x` is routed as shared, and it matches
      whole segments only, so `.aimon-staged2/` is not shared. Both cases are unit-tested.
  - **Descriptor and absolute paths (review-2, non-blocking).** `descriptor().workingDirectory()` is the branch
    root's **host path** (`/proj/.worktrees/{key}`), the same directory the shell's default cwd uses. For the file
    tools to accept that path without double-prefixing, `ScopedVirtualFileSystem.toBranchRelative` (stage 4) strips
    `{base}/{prefix}/` (and `{base}/{prefix}` itself) **before** the existing `{base}/` strip. So
    `/proj/.worktrees/k/a` reaches the branch's `a`, and relative, branch-absolute and shell paths all agree. The
    residual that the spec accepts (§4.2, "file tools + default cwd only") remains: a *canonical* absolute path
    `/proj/a` is mapped into the branch by the file tools, whereas `cat /proj/a` reads the canonical file. The
    javadoc states this. `FilePathSubjects` resolves relative paths against this descriptor directory, so
    permission subjects in a branch name the branch path the shell would see. Tests cover both.
  - **Shell.** A `WorkingDirectoryShell` view, which is a `VirtualShell` decorator that fills
    `ExecutionOptions.workingDirectory` with the branch root's host path when the command did not set one. Because
    `stage()` returns an absolute host path, staged scripts work from the branch cwd.
  - **Staging.** `stage()` delegates to the parent: same `stagingRoot`, same marker, same absolute path, no per-branch
    copy (§4.4).
  - **Other.** `durable() = false`. It shares `contentSearch` with the parent, rooted at the branch. Its javadoc states
    the "file tools + default cwd only" limit (§4.2).
- `RipgrepContentSearch` (stage 5).

### 3.3 Changes to existing types (by stage)

| Type | Change | Stage |
|---|---|---|
| `agent.tool.ToolContextKey` | `static <T> writeOnce(String, Class<T>)` (records the name in the static registry), `boolean isWriteOnce()`, `static boolean isWriteOnceName(String)`; `equals` still compares the name only | 1 |
| `agent.tool.ToolContext` (+`Builder`) | `put(String)`, `put(key)` and `putAll` share one check against `ToolContextKey.isWriteOnceName`; a second write to a write-once name throws `IllegalStateException("ToolContext key '<n>' is write-once")`. No `toBuilder()` is added, and the copy sites are left as they are. | 1 |
| `subagent.SubagentExecutionEnvironment` | `+ executionEnvironment` (parent, optional), `+ executionEnvironmentProvider`; kept by `toBuilder()` | 1 |
| `subagent.execution.SubagentExecutionContext` | forwards both fields (built in `DefaultSubagentExecutionManager:632`) | 1 |
| `agent.AgentRuntime` | **unchanged**. `OrcaAgentRuntime` implements `environment.EnvironmentProviding` instead (§3.1) | 1 |
| `filesystem.VfsPaths` (new, filesystem core) | `rootRelative(workingDirectory, path)`, `join(...)`; extracted from `ScopedVirtualFileSystem` | 3 |
| `filesystem.impl.ScopedVirtualFileSystem` | refactored onto `VfsPaths` (stage 3, no behaviour change); `+ ScopedVirtualFileSystem(delegate, prefix, Set<String> sharedPrefixes)` (stage 4, §3.2) | 3/4 |
| `agent.impl.orca.OrcaAgentRuntime` | `+ executionEnvironmentProvider` (borrowed, never closed). Stage 2: `- ownedShell` and the close branch. Stage 3: `fileSystem` → `controlFileSystem`. | 1/2/3 |
| `agent.orca.tool.OrcaToolProviderContext` | stage 2: `- getFileSystem()`, `- getShell()`; stage 3: `+ getControlFileSystem()` | 2/3 |
| `agent.context.ContextAssemblyRequest` | stage 2: `- fileSystem`, `+ executionEnvironment`; stage 5: `- environment` (the descriptor comes from the env) | 2/5 |
| `shell.ExecutionOptions` | `+ boolean background` (default false) | 2 |
| `shell.ShellCommandResult` | `+ List<String> notices()` (default empty), with a new constructor overload | 5 |
| `filesystem.FileMetadata` | `+ Optional<String> getEtag()` (+ builder `etag(String)`) | 5 |
| `agent.artifact.FileArtifact` | `+ ArtifactStorage storage` (`WORKSPACE`, `CONTROL`; default `WORKSPACE`) | 3 |
| `agent.compact.CompactionRequest` | `+ Optional<EnvironmentDescriptor> getEnvironmentDescriptor()`, set by the executor that triggers compaction | 5 |
| `agent.Environment` | stage 5: `- workingDirectory`, `- platform`, `- osVersion`, `- createWithWorkingDirectory`; keeps `timeZone` | 5 |
| `skill.repository.SkillSource` (new) | `final class`: `getFileSystem()`, `getDirectory()`; `static of(fs, dir)` (two required fields, so a static factory and no builder, like `ResourceTreeListing`) | 3 |
| `skill.repository.SkillRepository` | `- default Optional<String> resolveBaseDir(String)`; `+ Optional<SkillSource> resolveSource(String)` (abstract, empty only when the skill does not exist) | 3 |
| `skill.repository.VfsSkillRepository` | `resolveSource` = `(fileSystem, base + "/" + name)` when the directory exists | 3 |
| `skill.repository.PathSkillRepository` | `+ final VirtualFileSystem sourceFileSystem = VirtualFileSystems.readOnlyLocal(skillsBasePath)` (no I/O at construction; a missing or read-only root is fine); `resolveSource` = `(sourceFileSystem, name)` after `resolveSafely(name)` and an existence check | 3 |
| `skill.repository.ClasspathSkillRepository` | `+ final ClasspathSkillSourceFileSystem sourceFileSystem` (same class loader and `basePath`); `resolveSource` = `(sourceFileSystem, name)` when `exists(name)`; `findAllFiles` is implemented from the same listing (keys relative to the skill dir, values = classpath resource paths), so classpath skills list their files too | 3 |
| `skill.repository.ClasspathSkillSourceFileSystem` (new, package-private `final class implements VirtualFileSystem`) | Paths are VFS-relative (`{name}/…`), and the root is `basePath`. Read side: `listRecursive(dir)` calls `walker.list(basePath + "/" + dir, basePath + "/" + dir + "/SKILL.md")` and re-prefixes every `ResourceTreeListing.getFiles()` entry with `dir + "/"`. The walker returns paths relative to the listed directory, while the `VirtualFileSystem.listRecursive` contract wants paths relative to the VFS root, which is the shape `StagedResource.scan` relies on. `read(path)` resolves `basePath + "/" + path`. When the listing is not enumerated, it returns only `dir/SKILL.md` (if that resource exists) and logs one WARN that names the protocol and uses the materializer's wording. `read`/`openInputStream` use `getResourceAsStream`; `exists`/`isDirectory` resolve against the resource and the listing; `getMetadata` reads the size from the bytes. A listed resource that `getResourceAsStream` then cannot read is **skipped with a WARN by `StagedResource.scan`**, exactly as `BundledSkillMaterializer.materializeSkill` skips it ("Skipping unreadable bundled resource"). For `scan` the rule is general: an entry that is listed but not readable is left out of the hash and `totalBytes`. `scan` records the exact file list it hashed (`StagedResource.getFiles()`, relative paths), and **`stage()` copies exactly that list and nothing else**. If a recorded file cannot be read during the copy (a transient I/O error or a permission change after the scan), `stage()` throws `StagingException` **without writing the `.staged` marker**. So an incomplete copy is never served, and the next call retries. So the materialized copy and the classpath source always produce the same file set and the same `contentKey`; `getWorkingDirectory()` = `"classpath:/" + basePath`. Every mutating method throws `UnsupportedOperationException`. `initialize`/`close` do nothing, and `getStatus` reports healthy. | 3 |
| `filesystem.VirtualFileSystems` (new, filesystem core) | `static VirtualFileSystem readOnlyLocal(Path root)` → `new ReadOnlyLocalFileSystem(root)` | 3 |
| `filesystem.impl.local.ReadOnlyLocalFileSystem` (new, `final`) | A `java.nio` read-only VFS over `root`, with the path rule "normalise, then require `startsWith(root)`" (the same containment `PathSkillRepository.resolveSafely` applies today; symlinks are followed, as today). Behaviour:<ul><li>`exists`/`isDirectory` wrap `Files.exists`/`Files.isDirectory`;</li><li>`read`/`openInputStream` wrap `Files.newInputStream`;</li><li>`getMetadata` reads size and mtime and sets no etag;</li><li>`list` and `listRecursive` return root-relative file paths, and an **empty list when the root is missing**;</li><li>`getWorkingDirectory()` is `root.toString()`;</li><li>`initialize` and `close` do nothing, and `getStatus` is healthy;</li><li>every mutating method throws `UnsupportedOperationException("read-only file system")`.</li></ul>It never creates, checks for writability, or logs at INFO, so a file-based bundle adds no "Initializing LocalFileSystem" line. | 3 |
| `skill.Skill` | `- baseDir`/`getBaseDir()`; `+ Optional<StagedResource> getStagedResource()` (+ builder). It stays `Optional` because hand-built skills (tests, custom `SkillRegistry` implementations) may omit it. Every skill loaded through `DefaultSkillRegistry` has one. | 3 |
| `skill.render.SkillRenderContexts` | `builderFor(skill)` stops setting `skillBaseDir`; `resolveSkillBaseDir` and `derive` are deleted. `skill.render` knows neither the environment nor any skill path. | 3 |
| `tools.SkillRenderContextAccess` | `builderFor(skill, ctx)` sets `skillBaseDir(env.stage(resource))` (the rule is in stage 3, "Skill side") | 3 |

---

## 4. Execution-time assembly (§5.1), concretely

**`OrcaAgentExecutor.execute(runtime, request, listener)`** (stage 1):
1. The first statement after the null checks is
   `env = ExecutionEnvironments.resolveOrUnavailable(runtime.getExecutionEnvironmentProvider(), EnvironmentRequest{agent = runtime.getAgent(), runtimeId, sessionId, principal})`.
   Store it on `ExecutionScope` (new final field `executionEnvironment`).
2. From stage 2, `assembleContext` passes `env` into `ContextAssemblyRequest`. From stage 5, the prompt renderer
   reads `env.descriptor()`.
3. `createToolContext(scope, …)` puts `EXECUTION_ENVIRONMENT` and `EXECUTION_ENVIRONMENT_PROVIDER` **after** the
   framework keys and **before** `applyEnrichers`. An enricher that writes either key throws `IllegalStateException`
   inside the existing per-enricher `try/catch`, which logs WARN. The env is unchanged.
4. The slash-command `commandToolContext` (≈ line 2081) puts the same two keys from `scope`.
5. `resolveEffectiveToolContext` is unchanged. Its `putAll` copy is still protected, because the registry is keyed by name (§2).

**`DefaultSubagentExecutor`** (stage 1): at the top of the execute path, before the system prompt is built (≈ line
338), call `resolveOrUnavailable(context.getExecutionEnvironmentProvider(), EnvironmentRequest{runtimeId, agent = empty, executionId,
invokingSessionId, principal, parent = context.getExecutionEnvironment()})`. Put both keys in `createToolContext`
before `applyEnrichers`. A missing provider yields an Unavailable env with the cause "no ExecutionEnvironmentProvider
forwarded to this fork", which makes a wiring bug visible to the model instead of silently using the host.

**`RoutineExecutor`** (stage 1, **[plan decision]**): `runRoutine` resolves once per run via
`agentRuntime instanceof EnvironmentProviding p ? p.getExecutionEnvironmentProvider() : null`, where a null provider yields an Unavailable env (request: `agent = agentRuntime.getAgent()`, runtimeId,
`executionId` = the routine's id, principal = task owner). The keys go into `buildToolContext`. The copy at line 564 is unchanged.

**`SingleToolInvoker:165`** is unchanged. Stage 1 adds a regression test showing that its `putAll` copy followed by a `put(EXECUTION_ENVIRONMENT, …)` throws.

---

## 5. Tool behaviour after stage 2 (and 5)

All tools obtain the environment with `ExecutionEnvironmentAccess.require(context)` (`at.aimon.core.tools`) inside `execute()`. They catch
`IllegalStateException` and `ExecutionEnvironmentUnavailableException` and return
`ToolResult.error("No execution environment for this execution: …")` or the underlying cause. They never throw.

| Tool | Constructor after | `execute()` |
|---|---|---|
| `ReadTool` | `()` | `env.fileSystem()`. From stage 5 it records a stamp under the normalised key. |
| `WriteTool` | `()` and `(boolean useRelativePaths)`. Today's `(VirtualFileSystem, boolean)` mode loses only the fs; `()` = `this(true)`, as today. | `env.fileSystem()`. `toDisplayPath` reads the working directory from `env.fileSystem()` on each call. From stage 5 the stamp check applies when the file exists. |
| `EditTool` | `()` | as above. Until stage 5 it keeps the `READ_FILES_KEY` check. |
| `GrepTool` | `()` | `env.fileSystem()`. From stage 5, `env.contentSearch()` is tried first. |
| `ArtifactAwareWriteTool` / `ArtifactAwareEditTool` | `()` until stage 3, then `(ArtifactArchive)` | delegate plus registration; the §9.3 copy lands in stage 3 |
| `BashTool` | `(BackgroundBashManager)` | `env.shell()`. The background path sets `ExecutionOptions.background(true)` and captures that shell in the submitted future (§5.3). From stage 5, notices are prefixed as `[environment] …`. |
| `BashOutputTool` | unchanged | unchanged. *Later (EE-18):* reports a finished background task's notices once, ahead of its output |
| `WikiIngestTool` | unchanged | `env.fileSystem()` instead of `VIRTUAL_FILE_SYSTEM` |
| `SkillTool` | unchanged | via `SkillRenderContextAccess.builderFor(skill, ctx)`. **Stage 2:** no skill-side change. `${AIMON_SKILL_DIR}` keeps today's `SkillRenderContexts.resolveSkillBaseDir` value, because `Skill.getStagedResource()` does not exist yet and there is nothing to pass to `stage()`. In stage 2 that value is still a path on the one shared VFS, so it resolves as it does today. **Stage 3:** `skillBaseDir = env.stage(resource)` for every loaded skill, and from then on the §15 rule "`${AIMON_SKILL_DIR}` only from `stage()`" holds. The "Available Files" listing is rebased onto that directory (§1.12). |

`OrcaFileToolProvider`: stage 2 keeps only the artifact branch. `FILE_TOOL_NAMES` survives only while
`WorktreeToolEnvironmentFactory` exists and is deleted in stage 4. `OrcaBashToolProvider`: always registers (there is
no longer a "no shell → skip" branch; an env without a shell errors at call time). **[plan decision]** The WARN-and-skip
branch is removed because the shell is no longer known at registration time.

---

## 6. Stages → files, tests, commits

Every stage ends with `./gradlew checkAll` green, `./gradlew format` applied, and a commit (or commits) whose
message follows the repository's `type(scope): subject` style. Docker integration tests are not part of the gate.

### 6.0 ArchUnit change list (all in `modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java`)

Every rule in that file that restricts outbound dependencies was re-checked against the new edges: 
`agentCoreShouldOnlyDependOnCoreAndLlmAndFilesystem`, `agentCompactMayDependOnExtHook`,
`agentContextMayDependOnHookRegistryOnly`, `liveSessionMayDependOnHookSessionTypes`,
`workflowMayDependOnlyOnSubagentSpiTypes`, `filesystemCoreShouldOnlyDependOnCore`, `llmCoreShouldOnlyDependOnCore`,
`coreShouldNotDependOnOtherAimonPackages`, `configHook*`, the `*ImplMustNotLeak*` rules, `noCorePackageCycles` and
`noNewTopLevelCorePackageCycles`. New edges and where they come from:

| Edge | First stage | Source |
|---|---|---|
| `environment → base, filesystem, shell` | 1 | the SPI types |
| `environment → agent` (only `Agent`, `AgentRuntimeId`, `agent.session.SessionId`, `ExecutionId`) | 1 | `EnvironmentRequest` |
| `environment.impl → filesystem.impl, shell.impl` | 1 | the local provider |
| `tools, subagent, scheduling, skill → environment` | 1 / 3 (skill) | keys, forwarding, routines, `Skill.getStagedResource` |
| `agent.impl → environment{,.impl}` | 1 | executor, runtime factory (stage 1 default provider). `agent.impl` is excluded from the cycle scan and from the agent-core rule |
| `agent.context → environment` | **2** | `ContextAssemblyRequest.executionEnvironment`; stage 5 adds `EnvironmentContextProvider` rendering `EnvironmentDescriptor` |
| `workflow → environment` (`ExecutionEnvironment`, `ExecutionEnvironmentProvider`, `EnvironmentRequest`) | **4** | `DefaultWorkflowContext.resolveEnv` |
| `hook → environment` (`EnvironmentDescriptor`) | 5 | `HookContext` |
| `agent.compact → environment` (`EnvironmentDescriptor`) | 5 | `CompactionRequest` → Pre/PostCompact hook contexts (review-2) |
| `filesystem.impl → filesystem` (`PathRule`, `VfsPaths`) | 3 | the path-rule wrapper and the scoped refactor. `filesystem` itself gains no new outbound edge, so `filesystemCoreShouldOnlyDependOnCore` holds |
| `filesystem` (root package) `→ filesystem.impl{,.local}` | 3 | `VirtualFileSystems.readOnlyLocal` → `ReadOnlyLocalFileSystem`. This edge stays inside `PKG_FILESYSTEM_CORE`, so neither `filesystemCoreShouldOnlyDependOnCore` nor the impl-leak rule is affected. Root-package classes belong to no `filesystem.(*)..` slice, so the filesystem cycle rule is unaffected too (§1.11). |
| `skill → environment` (`StagedResource`, `StageIgnore`) and `skill.repository → filesystem` (`VirtualFileSystems`, `VirtualFileSystem`) | 3 | registry scan and repository sources. `skill → filesystem` already exists (`VfsSkillRepository`), and `skill.repository` gains **no** edge into `filesystem.impl` |

Deliberately **not** added: `agent` (core, outside `agent.context` and `agent.compact`) `→ environment`. `AgentRuntime` stays untouched
(`EnvironmentProviding`, §3.1). In stage 5, `UserContextMessageBuilder` and `AgentEnvironmentSnapshot` receive the
working directory as a `String` from `agent.impl`, not an `EnvironmentDescriptor`. So `agentCoreShouldOnlyDependOnCoreAndLlmAndFilesystem`
**needs no change in any stage**. `environment → tools` / `environment → agent.tool` also never happen, because
`ExecutionEnvironmentAccess` lives in `tools` (§2).

Changes by stage:

| Stage | Rule | Change |
|---|---|---|
| 1 | `filesystemImplMustNotLeakOutsideFilesystemTree`, `shellImplMustNotLeakOutsideShellTree` | add `.and().resideOutsideOfPackage("at.aimon.core.environment.impl..")`. Keep the `agent.impl.orca.environment..` carve-out for now. Update `@DisplayName` and comments. |
| 1 | **new** `environmentImplMustNotLeakOutsideEnvironmentTree` | `noClasses().that().resideOutsideOfPackage("at.aimon.core.environment..")` `.and().resideOutsideOfPackage("at.aimon.core.agent.impl.orca..")` (**temporary**: the stage-1 default borrowing provider built in `OrcaAgentRuntimeFactory`) `.should().dependOnClassesThat().resideInAPackage("at.aimon.core.environment.impl..")` |
| 1 | **new** `environmentSpiDependenciesAreCurated` | Classes in `at.aimon.core.environment` and `.exception` may depend only on the `environment` package tree, `PKG_CORE`, `PKG_FILESYSTEM_CORE`, `PKG_SHELL_CORE`, `belongToAnyOf(Agent, AgentRuntimeId, SessionId, ExecutionId)`, `PKG_JAVA` and `PKG_SLF4J`. Classes in `environment.impl` may additionally depend on `filesystem.impl..`, `shell.impl..` and `PKG_JACKSON` (stage 5, the `rg --json` parse). This rule bounds the `environment → agent` half of the cycle. |
| 2 | `agentContextMayDependOnHookRegistryOnly` | add the neutral packages `"at.aimon.core.environment"` and `"at.aimon.core.environment.exception"` (exact packages, not `..`, so `environment.impl` stays forbidden). Update `@DisplayName`. |
| 2 | **new** `onlyAgentContextAndCompactMayReachEnvironmentFromAgentTree` | `noClasses().that().resideInAPackage(PKG_AGENT_CORE).and().resideOutsideOfPackage(PKG_AGENT_CONTEXT).and().resideOutsideOfPackage(PKG_AGENT_COMPACT).and().resideOutsideOfPackage(PKG_AGENTS)` `.should().dependOnClassesThat().resideInAPackage("at.aimon.core.environment..")`. The `PKG_AGENT_COMPACT` exemption is there from stage 2 so that stage 5 does not have to reopen the rule. This bounds the `agent → environment` half of the cycle. |
| 2 | `noNewTopLevelCorePackageCycles` / `BASELINE_TOP_LEVEL_CYCLES` | add `"agent <-> environment"`. The entry goes in **stage 2, not stage 1**, because the baseline is exact in both directions and the `agent → environment` edge first appears in stage 2. The Javadoc note must give the rationale: the request names agent identities (spec §4.1), prompt assembly renders the descriptor (§10), and the two guard rules above keep the coupling to `EnvironmentRequest`'s identity types in one direction, and to `agent.context` (prompt assembly) plus `agent.compact` (compaction hooks must say where commands run, §10) in the other. Also update the "Nine of the thirteen" count in the Javadoc (now 14). |
| 2 | **new** `toolsHoldNoFileSystemOrShellFields` | No class in `at.aimon.core.tools..` has a constructor parameter or field of type `VirtualFileSystem` or `VirtualShell`. This is spec §15, bullet 1. `ArtifactArchive` holds the control fs, so it gets an explicit, commented exemption by class name. |
| 2 | `shellImplMustNotLeakOutsideShellTree` | remove the `agent.impl.orca.environment..` clause. `LocalShells` was that package's only `shell.impl` user and is deleted in this stage. (The filesystem rule keeps its clause until stage 4.) |
| 2 | `environmentImplMustNotLeakOutsideEnvironmentTree` | drop the temporary `agent.impl.orca..` exemption (the factory no longer builds a provider) |
| 4 | `workflowMayDependOnlyOnSubagentSpiTypes` | add `ExecutionEnvironment.class`, `ExecutionEnvironmentProvider.class` and `EnvironmentRequest.class` to `belongToAnyOf`. Update `@DisplayName` and comment. |
| 4 | `filesystemImplMustNotLeakOutsideFilesystemTree` | remove the `agent.impl.orca.environment..` carve-out (`WorktreeToolEnvironmentFactory` is deleted; `WorktreeMerge` no longer touches `filesystem.impl`) |
| 5 | `agentCompactMayDependOnExtHook` | add the neutral packages `"at.aimon.core.environment"` and `"at.aimon.core.environment.exception"` (exact packages). The reason: `CompactionRequest` gains `Optional<EnvironmentDescriptor>`, and `DefaultCompactionEngine` threads it into the Pre/PostCompact hook contexts next to `getEnvironment()`. The guard rule already exempts `agent.compact` (stage 2 row). Update `@DisplayName`, the `PKG_AGENT_COMPACT` comment and the baseline note. (review-2) |
| 5 | none further | `hook → environment` (HookContext descriptor) needs no rule change, since `hook` has no outbound allow-list. `environment.impl → Jackson` is already allowed by `environmentSpiDependenciesAreCurated`. |

`TurnVocabularyArchitectureTest` is also relevant: no new type or member uses a bare "turn" for an execution. The artifact
archive key for a main-agent turn is `ExecutionId.generate("archive").value()`. It contains no `turn`, neither in
identifiers nor in string values (§6 stage 3). The glossary rule applies to values as well as to names, even though
that test only checks names.

### Stage 0 — base

- Commit `chore: merge origin/main (brings #193 SkillRenderContextAccess)`. Resolve conflicts, if any, in favour of
  main. `checkAll`.

### Stage 1 — SPI and local provider

**Main (aimon-core)**
- New `environment/{ExecutionEnvironment, ExecutionEnvironmentProvider, EnvironmentDescriptor, EnvironmentRequest,
  StagedResource, UnavailableExecutionEnvironment, ExecutionEnvironments, package-info}.java`,
  `environment/exception/ExecutionEnvironmentUnavailableException.java`.
- New `environment/impl/{LocalExecutionEnvironmentProvider, LocalExecutionEnvironment, package-info}.java`.
- `agent/tool/ToolContextKey.java`, `agent/tool/ToolContext.java`: the write-once registry and the single put-check.
- `tools/ExecutionEnvironmentAccess.java` (new), `environment/EnvironmentProviding.java` (new).
- `tools/ToolContextKeys.java`: the two keys.
- `agent/impl/orca/OrcaAgentRuntime.java`: implements `EnvironmentProviding` and gains the builder field. `AgentRuntime` is not touched.
- `agent/impl/orca/OrcaAgentRuntimeFactory.java`: `withExecutionEnvironmentProvider`, with a default borrowing
  provider over `fileSystem` and `effectiveShell`.
- `agent/impl/orca/OrcaAgentExecutor.java`: hoisted resolve, `ExecutionScope.executionEnvironment`, both
  context-building sites.
- `subagent/SubagentExecutionEnvironment.java`, `subagent/execution/{SubagentExecutionContext,
  DefaultSubagentExecutor}.java`, `subagent/DefaultSubagentExecutionManager.java`.
- Forwarders: `tools/task/TaskTool.java`, `tools/workflow/WorkflowTool.java`,
  `skill/fork/SubagentBackedSkillForkExecutor.java`, `aimon-workflow-graaljs/.../GraalJsWorkflowTool.java`, and the
  runtime-level workflow base env in `OrcaAgentRuntimeFactory` (≈ line 1065; it sets only the provider, because there
  is no parent execution).
- `scheduling/RoutineExecutor.java`, `toolinvocation/SingleToolInvoker.java`.
- **Delete** `agent/impl/orca/environment/{VirtualExecutionEnvironment, LocalExecutionEnvironment}.java` and update
  that package's `package-info.java`.

**Tests**
- New `environment/UnavailableExecutionEnvironmentTest`: every fs/shell call throws with the cause; the descriptor
  does not throw and carries the cause in `notes`; `stage` throws.
- New `environment/ExecutionEnvironmentsTest`: provider throws → Unavailable; `null` provider/return → Unavailable;
  `require` on an empty context throws.
- New `environment/impl/LocalExecutionEnvironmentProviderTest`: the same env instance for all requests; `parent` is
  returned as-is; `stage()` with the same fs instance returns `sourceDir` unchanged and with a different instance
  throws `UnsupportedOperationException` (until stage 3); `close()` closes owned resources only.
- `agent/tool/ToolContextTest` (extend): a write-once key rejects a second `put` through the key and through the
  string name and through `putAll`; a fresh builder filled with `putAll(ctx.getContext())` accepts the first copy but
  rejects a later `put` of the same name. Every case first references `ToolContextKeys.EXECUTION_ENVIRONMENT`
  explicitly, so the registry is populated regardless of test order; a direct string `put("executionEnvironment", …)` into a fresh builder
  followed by the key `put` throws; non-write-once keys still overwrite.
- `agent/impl/orca/OrcaAgentExecutor*Test` (a new focused `OrcaAgentExecutorExecutionEnvironmentTest`): the provider
  is called once per `execute`, before `ContextAssembler.assemble`; the tool sees `EXECUTION_ENVIRONMENT`; an
  overwriting enricher does not replace it; a throwing provider yields an Unavailable env and the turn still
  completes.
- `subagent/execution/DefaultSubagentExecutor*Test`: `parent` is forwarded into the request; a missing provider
  yields Unavailable.
- `scheduling/RoutineExecutorTest`: the routine context carries the env.
- Delete or port the tests that used the deleted old types (`command grep -arl "VirtualExecutionEnvironment\|orca.environment.LocalExecutionEnvironment" modules/*/src/test`).
- ArchUnit: the stage-1 rows of §6.0 (two impl-leak carve-outs, `environmentImplMustNotLeakOutsideEnvironmentTree`,
  `environmentSpiDependenciesAreCurated`). `noNewTopLevelCorePackageCycles` must still pass **unchanged** in stage 1.
  That checks the claim that no `agent`-core class references `environment` yet.

**Commits**: `feat(environment): add ExecutionEnvironment SPI and local provider`;
`feat(tool): write-once ToolContext keys`;
`feat(agent): resolve the execution environment once per execution in both executors and routines`.

### Stage 2 — tool switch (+ CLI/bootstrap/starter)

**Main (aimon-core)**
- Tools listed in §5: `tools/file/{ReadTool, WriteTool, EditTool, GrepTool}.java`,
  `tools/artifact/{ArtifactAwareWriteTool, ArtifactAwareEditTool}.java`, `tools/bash/BashTool.java`,
  `tools/wiki/WikiIngestTool.java`. The constructors lose FS/shell.
- `tools/file/FilePathSubjects.java`: reads the working directory from
  `EXECUTION_ENVIRONMENT.descriptor().workingDirectory()`, falling back to `ENVIRONMENT_KEY` until stage 5 removes it.
- `agent/impl/orca/tool/{OrcaFileToolProvider, OrcaBashToolProvider}.java`.
- `agent/impl/orca/environment/WorktreeToolEnvironmentFactory.java` stays until stage 4, but it can no longer
  rebind file tools, because they have no constructor FS. **[plan decision]** Stage 2 rewrites `derive()` to stop
  cloning the registry. Instead it sets `SubagentExecutionEnvironment.executionEnvironment` to a scoped view of
  `baseEnv.getExecutionEnvironment()`, using a package-private `ScopedExecutionEnvironment` that lives in the same
  package and is deleted in stage 4. The view's file system is `.worktrees/{key}` over the parent's, and its shell is
  the parent's. The fork's provider returns `parent` as-is (§5.2), so the branch's tools see the scoped view. That
  preserves today's behaviour (file tools isolated, Bash not) until stage 4. When the base env carries no parent env
  (the runtime-level runner used by `WorkflowTool` background mode and `GraalJsWorkflowToolProvider`), `derive()`
  resolves one through `baseEnv.getExecutionEnvironmentProvider()` (set on that base env since stage 1), as
  stage 4 does. So the interim has no regression for background `isolate=true` runs. See §8 and Q2.
- `shell/ExecutionOptions.java`: `background`.
- `agent/orca/tool/OrcaToolProviderContext.java`: remove `fileSystem`/`shell`.
- `agent/impl/orca/OrcaAgentRuntimeFactory.java`: remove `withShell`, the `shell` field, `LocalShells` use and
  `ownedShell`, and the default provider. `create()` requires the provider.
- `agent/impl/orca/OrcaAgentRuntime.java`: remove `ownedShell`.
- **Delete** `agent/impl/orca/environment/LocalShells.java`.
- `agent/context/{ContextAssemblyRequest, GitStatusContextProvider, DirectorySummaryContextProvider}.java`: read
  `request.getExecutionEnvironment().map(ExecutionEnvironment::fileSystem)`. `OrcaAgentExecutor.assembleContext`
  passes `scope`'s env instead of `agentRuntime.getFileSystem()`.
- `tools/ToolContextKeys.java`: delete `VIRTUAL_FILE_SYSTEM`.
- §10 audit (the spec requires it in this PR). The consumers of `Environment`'s
  `platform`/`osVersion`/`workingDirectory`, found by `command grep -a`:
  `SystemPromptRenderer.buildEnvironmentBlock`, `EnvironmentContextProvider`,
  `DefaultSubagentExecutor` (≈ lines 865–867, subagent prompt), `UserContextMessageBuilder` (via
  `AgentEnvironmentSnapshot.getWorkingDirectory`), `FilePathSubjects`, `OrcaSystemCommandProvider` (≈ lines
  146–156), `OrcaSkillToolProvider:88`, `OrcaSubagentToolProvider:69`, `GraalJsWorkflowToolProvider:74` (CLI),
  `ReplSession:265`, `AgentSetupFactory:664`, the hook contexts built in `SingleToolInvoker`/`DefaultCompactionEngine`.
  Record the audit result as a table in the stage-2 commit body. The actual switch to the descriptor is stage 5.

**aimon-bootstrap**
- `assemble/StackAgentRuntimeProvisioner.createFileSystem`: next to the per-runtime VFS it builds a
  `LocalExecutionEnvironmentProvider`. In the local case, `workspaceRoot` mode with the same root, sharing the FS: the
  provider constructs it and the provisioner reads `provider.fileSystem()` for the control plane until stage 3. In the
  supplied/factory case, `.fileSystem(vfs)` mode. The provider is owned by the same `ResourceSink`
  (`sink.own("executionEnvironment(" + id + ")", provider)`), so it closes on eviction or stack teardown and never
  from `AgentRuntime.close()`. The provisioner calls `runtimeFactory.withExecutionEnvironmentProvider(provider)`
  before `create`.
- `AimonStackSpec`/`spec/*`: `+ ExecutionEnvironmentSpec` (optional
  `Function<AgentRuntimeId, ExecutionEnvironmentProvider> factory` or a shared instance). When it is absent, the local
  default above applies. **[plan decision]** A sandbox provider needs this seam; the spec leaves names to stage 3, and
  the seam is added in stage 2 because the provider becomes mandatory here.

**aimon-cli**
- `factory/AgentSetupFactory`: supply `ExecutionEnvironmentSpec.instance(localProvider over the jar-dir LocalFileSystem)`
  and own it on `TeardownPhase.AGENT_RESOURCES` (or the existing phase that owns the FS). `GraalJsWorkflowToolProvider`
  keeps receiving the `WorktreeToolEnvironmentFactory` until stage 4.

**aimon-spring-boot-starter**
- No new properties yet. `AimonAutoConfiguration` passes an `ExecutionEnvironmentSpec` from an optional
  `ExecutionEnvironmentProvider` bean, and falls back to the bootstrap default otherwise.

**Tests**
- Tool tests (the ~15 files under `src/test` that construct these tools, found by
  `command grep -arl "new ReadTool(\|new BashTool(\|…" modules/*/src/test`): construct argument-less and pass a
  `ToolContext` built with a shared test helper `TestExecutionEnvironments.of(fs, shell)` in
  `modules/aimon-core/src/testFixtures` if that source set exists, otherwise in `src/test/.../environment/`. Each tool
  gets a "no environment → `ToolResult.error`, no throw" case and an "Unavailable env → error carries cause" case.
- `BashToolTest`: the background path passes `background=true`, the foreground path `false`, and the background task
  uses the env's shell even after the context is gone.
- `WikiIngestToolTest`: uses the env's fs; the old "No virtual file system configured" message is gone.
- `WriteToolTest`: `new WriteTool(false)` shows absolute paths, and `new WriteTool()` shows paths relative to
  `env.fileSystem().getWorkingDirectory()`.
- `SkillToolTest` (stage-2 guard): `${AIMON_SKILL_DIR}` still renders the `resolveSkillBaseDir` value. This pins the
  interim behaviour so that stage 3 shows up as a deliberate diff.
- `GitStatusContextProviderTest`, `DirectorySummaryContextProviderTest`: read from the env.
- `OrcaAgentRuntimeFactoryTest`: `create` without a provider throws; the runtime does not close the provider's
  shell.
- Bootstrap `StackAgentRuntimeProvisionerTest`: the provider is created per runtime and closed by the sink, not by
  `runtime.close()`.
- ArchUnit: the stage-2 rows of §6.0: the `agentContext` allow-list, the `agent <-> environment` baseline entry with
  its rationale, `onlyAgentContextAndCompactMayReachEnvironmentFromAgentTree` (this exact name, and no second rule), `toolsHoldNoFileSystemOrShellFields`, and removal
  of the temporary exemptions.

**Commits**: `feat(tool)!: file tools, Bash, WikiIngest and Skill read the execution environment from ToolContext`;
`feat(agent)!: drop withShell/ownedShell; the provider owns shell and filesystem`;
`feat(bootstrap,cli,starter): wire an ExecutionEnvironmentProvider per runtime`.

### Stage 3 — control store split, path rules, staging, artifact

**Main (aimon-core)**
- `OrcaAgentRuntimeFactory`: the `create(...)` parameter `VirtualFileSystem fileSystem` is renamed to
  `controlFileSystem`, and all §1.3 rows except "file tools / runtime VFS / prompt cwd" use it. Default directory
  constants become **relative to the control root**: `"commands"`, `"agents"`, `"skills"` (dropping the `.aimon/`
  prefix). **[plan decision]** With the control root at `{project}/.aimon/`, the physical paths are unchanged
  (`{project}/.aimon/skills`). This holds **only if every `.aimon/`-prefixed default that moves onto the control fs
  loses the prefix in the same commit**, otherwise it lands at `{root}/.aimon/.aimon/…` and existing data is silently
  orphaned (review-2 of this run).
  - **Complete list.** Audited with `git grep -n '"\.aimon/\|"\.aimon"' -- 'modules/*/src/main/**'` on the merged
    base. Each row is changed in the stage-3 control-split commit.

    | Default (today) | Constructed over | After stage 3 | Physical path (local) |
    |---|---|---|---|
    | `OrcaAgentRuntimeFactory` `".aimon/commands"`, `".aimon/agents"`, `".aimon/skills"` (≈ :243) | runtime fs | `"commands"`, `"agents"`, `"skills"` on control fs | unchanged |
    | `StackPaths.{USER_SKILLS,BUNDLED_SKILLS,COMMANDS,AGENTS}_DIRECTORY` | runtime fs | `"skills"`, `"bundled-skills"`, `"commands"`, `"agents"` on control fs | unchanged |
    | `VfsTaskOutputStore.DEFAULT_BASE_DIR = ".aimon/task-output"` (`new VfsTaskOutputStore(fileSystem)`, factory ≈ :931) | runtime fs | `"task-output"`, constructed over the control fs | unchanged |
    | `VfsTaskResultStore.DEFAULT_BASE_DIR = ".aimon/task-result"` (`taskResultStoreFactory = VfsTaskResultStore::new`, ≈ :514) | runtime fs via factory | `"task-result"`; the factory function receives the control fs | unchanged |
    | `VfsSessionSnapshotStore.DEFAULT_BASE_DIR = ".aimon/task-snapshot"` (`sessionSnapshotStoreFactory`, ≈ :487) | runtime fs via factory | `"task-snapshot"`; the factory receives the control fs | unchanged |
    | CLI wiki: `AgentSetupFactory` ≈ :1552 `ContextResolvingWikiStorageLocator.defaultLayout(id -> …map(OrcaAgentRuntime::getFileSystem), ".aimon/wiki")` | runtime fs | `…map(OrcaAgentRuntime::getControlFileSystem), "wiki"`. The wiki knowledge base is framework-managed state (the model reaches it only through the Wiki tools), so it belongs to the control plane; `WikiIngestTool`'s *source* documents still come from `env.fileSystem()` | unchanged (`{jarDir}/.aimon/wiki`) |
    | `VfsStepResultCache.DEFAULT_BASE_DIR = ".aimon/step-cache"` | caller-supplied fs; **no default wiring in main** (the factory uses `WorkflowRunners.inMemoryStepResultCache()`) | `"step-cache"`; the javadoc says to construct it over the control fs, because it is a control-plane store (resume state, not model-visible files). **[plan decision]** | unchanged when the caller follows the javadoc. A caller that used to pass the whole workspace VFS and now passes it still gets `{vfs}/step-cache`. That is the one relocation, and it goes in CHANGELOG `Unreleased` and HANDOFF. |

  - **Checked and not affected:**
    - `HookConfigLoader.DEFAULT_DIRECTORY` / `HookHotReloadBootstrap.AIMON_DIR` (`".aimon"`) resolve host `java.nio`
      `Path`s under `user.home` and the project root, not a VFS.
    - The CLI `memory.storagePath` (`.aimon/memory/…`) is a host path read by the memory backend.

    None of these goes through the control fs, so none can double. Their behaviour is unchanged.
  - Javadoc examples that show `".aimon/commands"` (`CommandRegistry`, `DefaultCommandRegistry`,
    `MutableCommandRegistry`, `command/package-info`) and `BundledSkillMaterializer`'s `".aimon/bundled-skills"` example
    are updated to the control-root-relative form, so no one copies the old prefix.
  - Supplied/factory case in bootstrap: control fs = `ScopedVirtualFileSystem(vfs, ".aimon")`, so the same relative
    names land at `/.aimon/<store>/…` on the shared VFS, as today.

  `OrcaAgentRuntime.getFileSystem()` becomes `getControlFileSystem()`. `Environment.createWithWorkingDirectory(fileSystem.getWorkingDirectory())` becomes
  `Environment.createDefault()` until stage 5.
- `OrcaToolProviderContext.getControlFileSystem()`.
- New `filesystem/impl/PathRuleVirtualFileSystem.java` + `filesystem/PathRule.java` (`prefix`, `Access{DENY,
  READ_ONLY}`, class+builder). Behaviour:
  - DENY: `exists`→false, the entry is filtered from `list*`/`search`, and `read`/`getMetadata`/write/delete/move
    throw `FileAccessDeniedException`.
  - READ_ONLY: writes, deletes, moves or copies *into* the prefix throw; reads pass.
  - Paths are normalised before matching, so `a/../.aimon/x` is caught.
  - It passes through `getWorkingDirectory` and `FileMetadata`, including the etag from stage 5.
  - It lives in `filesystem.impl`, per §9.2.
- `LocalExecutionEnvironmentProvider`:
  - `workspaceRoot` mode applies the default rules.
  - `stage(resource)` implements §4.4: a same-instance passthrough is kept for the `.fileSystem(vfs)` mode when the
    source *is* the workspace fs. Otherwise the target is `{stagingRoot}/{name}/{contentKey}/`: if the `.staged`
    marker `exists` on the raw fs, return; if `totalBytes > maxStagedBytes`, throw `StagingException`; copy file by
    file (skipping `.stageignore` matches with `environment.StageIgnore`) via `openInputStream`→raw `write`; write the
    marker last.
  - **Startup sweep (§4.4) — restricted so it cannot delete a copy another runtime or process is still using**
    (review-2). The spec allows the sweep only "when no execution can reference those paths". The provider can know
    that only when it owns the staging area privately, so:
    - it runs **only in owned `workspaceRoot` mode**. That root is private to one runtime (bootstrap's per-runtime
      `AgentWorkspaceLayout` dir, or the CLI's project dir);
    - it **never runs in `.fileSystem(vfs)` mode**, the borrowed and possibly shared VFS such as
      `FileSystemSpec.supplied`, which every runtime shares. There, copies accumulate: they are content-addressed,
      so growth is bounded by the number of distinct skill versions ever staged. This is recorded in HANDOFF and Q16;
    - even in owned mode it deletes a `{contentKey}` directory only if (a) it is not the newest marker for its
      `{name}` **and** (b) its `.staged` marker is older than `stagingSweepGrace` (default 24h, which matches
      `BashTool.BACKGROUND_TIMEOUT_MS`, the longest an execution-launched process can outlive its start). Rule (b)
      covers the cases where even a private root is not quiet at startup: two CLI processes in the same project dir,
      and a bootstrap runtime re-provisioned after eviction while one of its background commands still holds an old
      path. Directories without a marker (an interrupted copy) older than the grace are deleted too;
    - the clock is injectable for tests.
- Skill side. **Every skill gets a staging source (user decision "소스 VFS 부여"; review 3):**
  - **Repositories** (§3.3 rows). `SkillRepository.resolveSource(name)` replaces `resolveBaseDir(name)` and is
    abstract. The three implementations:
    - `VfsSkillRepository`: its own fs and `{base}/{name}`. This covers user skills and the materialized bundled
      layer, which is how `resource-demo` normally resolves in the CLI (§1.10).
    - `PathSkillRepository`: `VirtualFileSystems.readOnlyLocal(skillsBasePath)`, built once in the constructor, and
      `{name}`. The name is still checked by `resolveSafely`, and `ReadOnlyLocalFileSystem`'s containment check keeps
      reads under the root. Constructing the repository does no I/O, so a read-only or missing skills directory
      behaves as it does today.
    - `ClasspathSkillRepository`: `ClasspathSkillSourceFileSystem(classLoader, basePath)` and `{name}`. It uses the
      same walker and `SKILL.md` anchor as `BundledSkillMaterializer`, so it stages exactly the files the materializer
      would copy. On an unenumerable layout it exposes `SKILL.md` alone and logs a WARN. The skill still loads and
      renders, `${AIMON_SKILL_DIR}` is a real staged directory holding `SKILL.md`, and the skill's other files are
      unreachable, which is also true today.
    - `resolveBaseDir` is deleted from the interface and all three implementations. `Skill.getBaseDir()` is deleted as
      well. Its only main reader was `SkillRenderContexts.resolveSkillBaseDir`.
  - **Registry.** `DefaultSkillRegistry.loadComplete` computes the resource once per (re)load. It calls
    `repository.resolveSource(name)`, then `StagedResource.scan(source.getFileSystem(), source.getDirectory(), name)`, and
    stores the result with `builder.stagedResource(...)`. The result is cached with the skill (`computeIfAbsent`), so
    hashing happens once per load, as §4.4 requires. Failure handling:
    - If `findByName` found the skill but `resolveSource` is empty, the repository is inconsistent. The registry
      throws `SkillRepositoryException("<RepoClass> returned no staging source for existing skill '<name>'")`. There
      is no silent "no directory" skill.
    - An I/O failure during `scan` surfaces as a `SkillRepositoryException`, just as a `findAllFiles` failure does
      today.
  - **Rendering (`SkillRenderContextAccess.builderFor(skill, ctx)`):**
    - `SkillRenderContexts.builderFor(skill)` no longer sets `skillBaseDir`.
    - The skill has a `StagedResource` and the context has an env: `skillBaseDir(env.stage(resource))`. This is the
      only way `${AIMON_SKILL_DIR}` gets filled.
    - The skill has no `StagedResource`: only possible for a hand-built `Skill` from a test or a custom
      `SkillRegistry`, and never through `DefaultSkillRegistry`. `skillBaseDir` stays unset, so it renders empty, and
      a WARN names the skill. There is no fallback path.
    - The context has no env: unset plus WARN, as in the round-2 plan.
  - **`SkillTool` "Available Files"** (§1.12). The listing uses `renderContext.getSkillBaseDir()`, the `stage()`
    result that was already computed for rendering, so `stage()` is not called a second time. Each entry is printed as
    `{stagedDir}/{path relative to the skill dir}`:
    - root files: `{stagedDir}/{name}`;
    - scripts, references, assets: `{stagedDir}/scripts|references|assets/{key}`;
    - other files: `{stagedDir}/{relPath}`.

    When there is no staged dir, entries are printed as skill-relative paths with no directory. Repository source
    paths never reach the model again. A file excluded by `.stageignore` is left out of the listing (it was never
    copied); the listing filters with the resource's `StageIgnore`.
  - The matcher is **`at.aimon.core.environment.StageIgnore`**, not a `skill` class (review-2). The spec makes both
    local and sandbox providers honour `.stageignore` (§4.4), so it belongs with the SPI. It is a minimal gitignore
    subset (globs, `dir/`, `!` negation, `#` comments) with `static StageIgnore parse(String)` and
    `boolean ignored(String relPath)`, and depends only on `java`. `StagedResource.scan` (used by
    `DefaultSkillRegistry` for the hash) and `stage()` (the copy) both use it, so the hash and the copy always agree on the file set. The dependency direction is
    `skill → environment` only: `environment.impl` never imports `skill`, so there is no `environment <-> skill`
    cycle. **[plan decision]**
- `tools/SkillRenderContextAccess.builderFor`: the rendering rule above. It **never** falls back to a repository
  path, which §15 forbids (review-2). The fallback cannot happen: `resolveSkillBaseDir` and `Skill.getBaseDir()` are
  deleted in this stage, so no repository path exists on the skill any more. The same applies to an Unavailable env, whose `stage()`
  throws and is mapped to an error like any `StagingException`. A `StagingException` propagates to the caller, which is `SkillTool` or
  `SkillBackedCommandExecutor`/`LlmSkillExecutor`, and they map it to `ToolResult.error`/a command error. The skill
  fork path (`SubagentBackedSkillForkExecutor`) goes through the same helper, so §15 "no `${AIMON_SKILL_DIR}` outside
  `stage()`" holds by construction.
- Artifact:
  - New `tools/artifact/ArtifactArchive.java`: wraps `controlFileSystem` and `ArtifactPolicy`, with
    `archive(key, fileName, source VFS, path) → Optional<String>`.
  - New `tools/artifact/ArtifactPolicy.java` (class+builder: `enabled`, `maxFileBytes` = 50 MB,
    `maxExecutionBytes` = 100 MB).
  - `OrcaFileToolProvider(boolean)` becomes `OrcaFileToolProvider(ArtifactPolicy)`, and the no-arg constructor means
    disabled.
  - The artifact-aware tools copy when `!env.durable()` and register `FileArtifact.storage(CONTROL)` with the control
    path. If the file or execution limit is exceeded, they do not register and append a note to the successful
    result.
  - The per-execution total comes from `ArtifactCollector` (`+ long totalBytes(ArtifactStorage)`).
  - The archive key is `ArtifactCollector.getArchiveKey()`, minted per execution by the executor that creates the
    collector (a fork uses its `ExecutionId` value; a turn uses a fresh `ExecutionId.generate("archive")` value **that is
    not published as `EXECUTION_ID`**, so the prefix names what the key is for and not the execution kind, per
    the CLAUDE.md glossary rule) (Q3).
  - `agent/artifact/{FileArtifact, ArtifactStorage}.java`.

**aimon-bootstrap / cli / starter**
- Bootstrap: the local case is control = `LocalFileSystem({runtimeRoot}/.aimon)` and provider = `workspaceRoot` mode
  on `{runtimeRoot}`.
- Bootstrap: `FileSystemSpec` now describes the **control** store. In the supplied/factory case, the same VFS
  instance serves both control and work (no split; the provider uses `.fileSystem(vfs)` + `.pathRules(defaults)` so
  that `.aimon/` is still denied to tools, and the control FS is a `ScopedVirtualFileSystem(vfs, ".aimon")`).
  **[plan decision]**
- `StackPaths.*` lose the `.aimon/` prefix.
- Bootstrap: `buildMaterializedSkillRegistry(bundle, controlFileSystem, "skills", "bundled-skills", …)`. The materializer
  now writes into the control store (physically still `{runtimeRoot}/.aimon/bundled-skills`). The file tools cannot
  see that directory (DENY). Its skills reach the model only through `stage()`, because they are VFS skills (§1.10).
  The bundle's fallback layer (Path or Classpath) stages through its own source.
- CLI: control = `LocalFileSystem(jarDir/.aimon)`, workspace provider on `jarDir`.
- CLI resource: `modules/aimon-cli/src/main/resources/agents/default/skills/resource-demo/SKILL.md`. The sentence
  "resolves to this skill's directory in the workspace (it is materialized there on startup)" becomes "resolves to a
  read-only copy of this skill's directory that is staged into the workspace on first use". The steps
  (`Read ${AIMON_SKILL_DIR}/templates/…`, `python3 ${AIMON_SKILL_DIR}/scripts/hello.py $1`) are unchanged and must
  keep working (tested below). Staging writes through the VFS and so does not keep the POSIX exec bit, and neither
  does the materializer today. `python3 x.py` and `bash x.sh` work; `./x.sh` does not, now or before.
- Starter property names (the spec defers them to this stage):
  - `aimon.environment.staging.max-bytes` (default 50MB);
  - `aimon.environment.control-writable` (default `false`; `true` drops the DENY rule, i.e. the §9.2 explicit
    opt-in);
  - `aimon.tools.artifact.enabled`, `aimon.tools.artifact.max-file-bytes` (50MB),
    `aimon.tools.artifact.max-execution-bytes` (100MB).
  - These are bound in `AimonProperties` (new nested `Environment` class and `Tools.Artifact`) and mirrored in the
    bootstrap `ExecutionEnvironmentSpec`/`ToolSpec`.

**Tests**
- `PathRuleVirtualFileSystemTest`: DENY hides from list/exists/search and blocks read/write; READ_ONLY allows read and
  blocks write/delete/move/copy-into; `..` traversal and `./` prefixes are caught; metadata passes through.
- `LocalExecutionEnvironmentProviderStagingTest`:
  - first stage copies and writes the marker last, and returns the absolute path defined in §3.2, which the
    workspace `fileSystem()` can read and a `LocalShell` rooted elsewhere can `cat`;
  - second stage with the marker skips (asserted with a spy fs that counts writes);
  - a deleted target with the marker gone is re-copied;
  - a different `contentKey` gets a different path and the old path stays;
  - over the limit throws `StagingException`;
  - `.stageignore` excludes, and the hash and the copy agree on the file set;
  - owned-mode sweep: keeps the newest per name; keeps a non-newest copy younger than the grace; deletes a
    non-newest copy older than the grace; deletes a marker-less directory older than the grace (injected clock);
  - **shared-VFS case (review-2 scenario):** two providers in `.fileSystem(vfs)` mode over one VFS; runtime A stages
    `report/k1`, runtime B stages `report/k2`; building a third provider (and rebuilding B) leaves `report/k1` intact
    and readable at the path A was given;
  - an interrupted copy (throwing fs after N files) leaves no marker, and the next call recopies;
  - `stage()` copies exactly `resource.getFiles()`. A recorded file that is unreadable at copy time →
    `StagingException` and no marker. A file added to the source after `scan` is not copied;
  - the source is edited between load and the first `stage()` → content mismatch → `StagingException`, no marker, and
    the target is removed; after `reloadSkill` the new key stages.
- `DefaultSkillRegistryTest`: the `contentKey` is stable across reloads of identical content and changes on edit.
  (Port `DefaultSkillRegistryBaseDirTest` to `DefaultSkillRegistryStagedResourceTest`.)
  - **Every repository kind yields a `StagedResource`**: Vfs, Path (temp dir) and Classpath (the test classpath's
    skill fixtures). For each, `sourceFileSystem.listRecursive(sourceDir)` lists the skill's files, and `scan`
    agrees with a hand-computed hash.
  - A stub repository whose `findByName` finds a skill but whose `resolveSource` is empty → `SkillRepositoryException`
    naming the repository class.
  - `DefaultSkillRegistryConcurrencyTest.FakeSkillRepository` (≈ line 181) must implement the now-abstract
    `resolveSource`. It answers deliberately: a `SkillSource` over an in-memory fs holding just that skill's
    `SKILL.md`, **not** `Optional.empty()`. Empty would make every load throw, and the test would stop measuring
    concurrency. The test's assertions on load counts stay as they are; `scan` runs inside the same `computeIfAbsent`
    as the load it measures.
- `PathSkillRepositoryTest`:
  - `resolveSource` returns a fs that reads the files, and whose `write`/`delete` throw
    `UnsupportedOperationException`;
  - a traversal name (`../x`) yields empty, and an unknown skill yields empty;
  - **read-only root** (review-1). Remove write permission from a temp skills dir (`assumeTrue` POSIX and not root,
    because root ignores permission bits). Then `new PathSkillRepository(dir)` succeeds. `DefaultSkillRegistry` loads
    the skill and `scan` hashes it, and a local env's `stage()` copies it into the workspace and returns a readable
    path;
  - **missing root.** Construction over a non-existent dir does not throw, `findAllNames()` is empty, and the dir
    still does not exist afterwards.
- `ClasspathSkillRepositoryTest`:
  - for an exploded fixture and a jar fixture (built into a temp dir, as `BundledSkillMaterializerTest` does), the
    source lists **the same file set `BundledSkillMaterializer` materializes** for that skill. This is a parity
    assertion with both run over the same class loader;
  - `findAllFiles` is now populated;
  - an unenumerable layout (reuse `BundledSkillMaterializerTest`'s `UnwalkableDirectoryClassLoader` pattern) lists
    only `SKILL.md` and logs one WARN;
  - mutating calls throw.
- `ReadOnlyLocalFileSystemTest`:
  - reads, `listRecursive` (root-relative) and metadata work;
  - every mutating method throws;
  - `../` escape and an absolute path outside the root are rejected;
  - a missing root gives `exists == false` and an empty listing, and is not created;
  - a non-writable root works (same `assumeTrue`).
- `SkillToolTest` and the skill-command test:
  - `${AIMON_SKILL_DIR}` equals the `stage()` return value;
  - **review 3's missing case: a `PathSkillRepository`-backed skill under a local env.** The rendered path is under
    `{workspace}/.aimon-staged/{name}/{key}`, not the host skill dir. `env.fileSystem().read(path + "/scripts/x.sh")`
    returns the bytes, and `env.shell().execute("bash " + path + "/scripts/x.sh")` exits 0;
  - the same for a `ClasspathSkillRepository`-backed skill;
  - a hand-built `Skill` without a `StagedResource` renders empty and never shows a repository path;
  - "Available Files" shows staged paths only. The test asserts that no line contains the source root (host dir,
    control-store `skills/`, or `classpath:`), and that a `.stageignore`d file is not listed.
- **CLI `resource-demo` (`modules/aimon-cli/src/test/java/at/aimon/cli/skill/ResourceDemoSkillStagingTest`).** This is
  the user's acceptance condition. It runs over a temp project dir with the stage-3 CLI shape: control
  `LocalFileSystem(tmp/.aimon)` and a `LocalExecutionEnvironmentProvider` in `workspaceRoot(tmp)` mode. It invokes
  `SkillTool` for `resource-demo` with arg `demo` and asserts, for each of three registry shapes:
  1. The materialized registry (`buildMaterializedSkillRegistry` with the CLI class loader and
     `agents/default/skills`), which is what the CLI actually runs.
  2. The bundle registry alone, loaded by `AdaptiveAgentBundleLoader` for `default`. In the test JVM this is the
     `file://` → `PathSkillRepository` shape (`gradle run`/IDE). The test first asserts that shape. If the loader
     resolves differently on some build layout, the test builds `PathSkillRepository` over the resolved resource
     directory itself, so the Path case is always covered.
  3. `new ClasspathSkillRepository("agents/default/skills", cliClassLoader)` alone (the jar fallback).

  The assertions:
  - the rendered body contains no literal `${AIMON_SKILL_DIR}`, and every occurrence was replaced by one path `P`
    under `tmp/.aimon-staged/resource-demo/`;
  - `env.fileSystem().read(P + "/templates/report-template.md")` and `read(P + "/references/notes.md")` return the
    bundled bytes;
  - `env.shell().execute("python3 " + P + "/scripts/hello.py demo")` exits 0 and prints the script's line
    (`assumeTrue` that `python3` is on `PATH`);
  - `env.fileSystem().write(P + "/x", …)` throws (READ_ONLY);
  - shapes 1–3 give the same `P`. The content key depends only on content, and all three sources skip
    unreadable entries by the same rule as the materializer (§3.3 classpath row);
  - the file tools cannot `Read tmp/.aimon/bundled-skills/resource-demo/SKILL.md` (DENY).
- `ArtifactAware*ToolTest`: a durable env registers a WORKSPACE path; a non-durable fake env copies to
  `/artifacts/{key}/{name}` on control and registers CONTROL; the limits produce a success result with a note and no
  registration.
- `OrcaAgentRuntimeFactoryTest`: subagent/skill/command registries and task stores read from the control fs, and the
  file tools cannot see `.aimon/`.
- **No doubled control root** (review-2 of this run): new `ControlRootLayoutTest` in bootstrap, with a CLI twin for
  the wiki. Control = `LocalFileSystem(tmp/.aimon)` and the provider is in `workspaceRoot(tmp)` mode. The test drives
  one real write through each control-plane store:
  - a task output (`VfsTaskOutputStore`);
  - a task result (`VfsTaskResultStore`);
  - a session snapshot (`VfsSessionSnapshotStore`);
  - a user skill and a command definition read back through the registries;
  - a materialized bundled skill;
  - a wiki page through the CLI's `createWikiKnowledgeStore` locator;
  - a `VfsStepResultCache` put over the control fs.

  Assertions:
  - each file exists at `tmp/.aimon/{task-output|task-result|task-snapshot|skills|commands|bundled-skills|wiki|step-cache}/…`;
  - `Files.exists(tmp.resolve(".aimon/.aimon"))` is **false**;
  - a pre-seeded file at the old physical location, for example `tmp/.aimon/skills/x/SKILL.md` and
    `tmp/.aimon/wiki/…` written before assembly, is **found** after assembly. This shows existing user data is not
    orphaned.
- Store unit tests (`VfsTaskOutputStoreTest`, `VfsTaskResultStoreTest`, `VfsSessionSnapshotStoreTest`,
  `VfsStepResultCacheTest`): the default base dir constant is prefix-free. Tests that assert `.aimon/task-…` paths are
  updated to construct the store over a `ScopedVirtualFileSystem(fs, ".aimon")`, so the physical path they assert is
  unchanged.
- ArchUnit: `PathRuleVirtualFileSystem` is constructed only in `environment.impl`, which the existing
  `filesystemImplMustNotLeak…` rule already enforces. *Later (EE-41):* it is constructed only by the public factory
  `VirtualFileSystems.withPathRules`, which the local provider now calls too. `VfsPaths` is in `filesystem` core and depends only on `java`.

**Commits**: `feat(filesystem): path-rule VFS wrapper (DENY / READ_ONLY)` (+ `ReadOnlyLocalFileSystem`,
`VirtualFileSystems.readOnlyLocal`);
`feat(agent)!: separate controlFileSystem from the execution environment`;
`feat(skill)!: every SkillRepository exposes a staging source; drop resolveBaseDir/getBaseDir`;
`feat(environment): content-addressed skill staging` (+ `SkillTool` staged file listing, `resource-demo` text, the
spec §4.4 rule and the skill guide);
`feat(artifact): archive artifacts from non-durable environments into the control store`;
`feat(bootstrap,cli,starter): control root at .aimon/ and environment properties`.

### Stage 4 — isolation

- `LocalExecutionEnvironment.isolate(key)` returns `LocalIsolatedEnvironment` (§3.2). The branch-key shape check
  `[A-Za-z0-9_]+` moves here from `WorktreeMerge`.
- `workflow/impl/DefaultWorkflowContext.resolveEnv`: for isolated steps, get
  `parent = baseEnv.getExecutionEnvironment()`. If it is absent (the runtime-level runner), resolve one with
  `ExecutionEnvironments.resolveOrUnavailable(baseEnv.getExecutionEnvironmentProvider(),
  EnvironmentRequest{agentRuntimeId, principal})` (`agent` is optional, §2; `invokingSessionId` is omitted so
  `workflow` does not need `SessionId`). Then `parent.isolate(key)`. If empty, throw
  `WorkflowException("… environment does not support isolation — refusing to run unscoped")` (run-fatal, which keeps
  the C30 semantics). Otherwise return `baseEnv.toBuilder().executionEnvironment(branchEnv).build()`, which keeps
  `cancellationSignal` and does not touch `toolRegistry`.
- **Delete**:
  - `workflow/WorktreeEnvironmentFactory.java`;
  - `agent/impl/orca/environment/WorktreeToolEnvironmentFactory.java` and the stage-2 `ScopedExecutionEnvironment`;
  - `OrcaFileToolProvider.FILE_TOOL_NAMES`;
  - `worktreeFactory` from `WorkflowRunnerOptions`, `WorkflowRunners`, `DefaultWorkflowRunner.Builder`,
    `ContextExecutionOptions`, `GraalJsWorkflowTool.Builder`, `GraalJsWorkflowToolProvider` (CLI) and
    `AgentSetupFactory:785`;
  - `OrcaAgentRuntimeFactory.withWorktreeEnvironmentFactory` and its field.
- `WorktreeMerge.promote(ExecutionEnvironment parent, List<ExecutionEnvironment> branches, Policy)`:
  - Branches are listed with `branch.fileSystem().listRecursive(".")`.
  - Promotion is a cross-instance copy: `parent.fileSystem().write(p, branch.fileSystem().openInputStream(p), size)`,
    then verify with `exists`, then `branch.fileSystem().delete(p)`.
  - Pre-scan, `Policy`, fail-fast and no-rollback are unchanged.
  - Precedence order is the list order.
  - It no longer needs `filesystem.impl`, so it stays in `agent.impl.orca.environment` with `MergeReport`.
  - Its javadoc (and `workflow-usage-guide`) says how an assembler that knows only branch keys, for example from
    listing `.worktrees/`, gets the branch objects: `parent.isolate(key).orElseThrow()` for each key. Isolation is
    deterministic on the key, so this rebuilds the same view the run used.
- ArchUnit: the stage-4 rows of §6.0 (the workflow allow-list, removal of the last `agent.impl.orca.environment..`
  carve-out).

**Tests**
- `LocalIsolatedEnvironmentTest`: writes through `fileSystem()` land under `.worktrees/{key}/`; a shell command with
  no cwd runs in the branch root (`pwd`); an explicit cwd is respected; `durable()==false`; a bad key is rejected.
- **Staged files are reachable from a branch** (review-1). Staging is done through the branch env:
  `path = branch.stage(resource)`. Then:
  - `path` is absolute and equals `parent.stage(resource)`, and the copy exists once, under the parent's
    `.aimon-staged/`, with nothing under `.worktrees/{key}/.aimon-staged/`.
  - `branch.fileSystem().read(path + "/scripts/x.sh")` returns the staged bytes.
  - `branch.shell().execute("cat " + path + "/scripts/x.sh")`, run with no cwd (so the branch root is the cwd),
    prints the same bytes, and `sh path/scripts/x.sh` runs.
  - `branch.fileSystem().write(path + "/y", …)` throws, because READ_ONLY applies to the real staging area.
  - `branch.fileSystem().listRecursive(".")` does not list staged files, and a `WorktreeMerge.promote` of that branch
    does not promote them.
- ~~**Branch-local `.aimon/` is not denied**~~ *(reversed later — §10.8)* (review 3). A branch file written as `.aimon/x` lands at
  `.worktrees/{key}/.aimon/x` on the parent's path-rule fs:
  - through the branch fs it can be written, read, listed and deleted;
  - `parent.fileSystem().read(".worktrees/{key}/.aimon/x")` also succeeds;
  - the root `.aimon/x` is still denied from both.

  This pins the rules as **root-anchored** (`VfsPaths.rootRelative` against the parent root) and not
  segment-anywhere. A later change to "match `.aimon/` at any depth" would fail here instead of silently hiding branch
  files. It also checks that `WorktreeMerge.promote` of that branch promotes `.aimon/x` to the root `.aimon/x` and
  **fails there** with the DENY error. That is the intended guard: a branch cannot write the control store through a
  merge. The failure is reported like any other promotion failure (fail-fast, partial progress logged).
- `ScopedVirtualFileSystemTest` (extend): shared prefix routing for relative, `./`, absolute-under-base and
  `a/../.aimon-staged` forms; `.aimon-staged2/` is not shared; escape via `..` from a shared path is still rejected;
  the constructor without `sharedPrefixes` behaves exactly as before.
- `WorkflowPhase4Test` (port): isolate uses `env.isolate`; an env without isolation → `WorkflowException`; the
  cancellation signal is preserved; **the tool registry is identical** (same instance) to the base.
- `WorktreeMergeTest` (port to the new signature): collision pre-scan, FAIL/FIRST/LAST policies, partial failure
  message.
- End-to-end: a two-branch isolated workflow whose leaves `Write` the same path and run `Bash echo > f` produces two
  separate branch files, and promotion under `FAIL` reports the collision.

**Commits**: `feat(environment): isolate() for the local environment`;
`feat(workflow)!: isolate branches through ExecutionEnvironment; remove WorktreeEnvironmentFactory`;
`refactor(workflow)!: WorktreeMerge promotes between environments`.

### Stage 5 — stamp, content search, notices, descriptor (independent; one commit each)

**5a Stamp (§7)**
- `FileMetadata.getEtag()`.
- `aimon-filesystem-s3/.../S3FileSystem.getMetadata`: `.etag(response.eTag())`.
- `aimon-filesystem-gridfs/.../GridFSFileSystem.getMetadata`: `.etag(file.getObjectId().toHexString())`. Every
  GridFS write uploads a new document, so the id changes on each content change and satisfies the contract (Q6).
- `LocalFileSystem`: no etag (nanosecond mtime + size) (Q7).
- `ScopedVirtualFileSystem` and `PathRuleVirtualFileSystem` pass the etag through.
- `docs/design/filesystem/backend-contract.md` gets the §7 `getMetadata` clause.
- `ReadTool.FILE_STAMPS_KEY` replaces `READ_FILES_KEY` in both executors and the routine context.
- `EditTool`/`WriteTool` (and, through delegation, the artifact-aware tools) follow the §7 pseudo-code with the two
  exact messages "Read the file before modifying it" and "File changed since it was read; Read it again".
- The stamp is updated after a successful write.
- The shared helper `tools/file/FileStamps.java` holds `key(env, path)` and `current(env, path)`. The base for
  `key` is `env.descriptor().workingDirectory()` when that is `'/'`-anchored (local and isolated envs); otherwise
  it is `env.fileSystem().getWorkingDirectory()` (URI bases). Either way the key is
  `VfsPaths.rootRelative(base, path)`. `rootRelative` also defines the relative base `"."`: it only strips leading
  `./` and resolves `.`/`..`, and never treats a `/`-leading path as relative. This handles the review-2 branch case:
  in a branch, `Read a.txt` and `Edit /proj/.worktrees/k/a.txt` both key as `a.txt`. A canonical `/proj/a.txt` edit
  keys differently and is refused with "Read the file before modifying it". That is fail-closed, and one `Read`
  recovers from it.
- Tests:
  - stamp on read;
  - Edit without a read → error;
  - an external modification (a direct fs write between Read and Edit) → error;
  - a Bash `sed -i` modification through a real `LocalShell` → error;
  - a `./a.txt` vs `a.txt` vs absolute key match, run on a local env **and on an isolated branch env** (relative,
    `./`, branch-absolute);
  - Write of a new file → no check;
  - Write overwrite without a read → error;
  - a fork does not inherit the parent's stamps;
  - an etag-only difference (same size+mtime) → error;
  - S3/GridFS unit tests assert the etag is populated (the existing mocked-client tests).

**5b ContentSearch**
- `environment/{ContentSearch, ContentQuery, ContentSearchResult}`, `environment/impl/RipgrepContentSearch`.
- `RipgrepContentSearch` runs `rg --json` via `ProcessBuilder` in the env's root. It passes
  `--no-ignore --hidden` (the fallback Grep ignores nothing), and one `--glob '!<prefix>**'` per DENY rule, so rg
  does not leak `.aimon/`.
- `ExecutionEnvironment.contentSearch()` default method.
- `GrepTool`: delegate when present, fall back on `Optional.empty()` **or on a `ContentSearch` failure** (logged at
  DEBUG). Both paths share the formatter.
- Tests:
  - a parity test that runs the same query set through the fallback and through `RipgrepContentSearch` over a temp
    tree and asserts identical `ToolResult` text (tagged or `assumeTrue(rgOnPath)`);
  - a DENY prefix is never returned;
  - the query mapping for each GrepTool option.

**5c Notices (§8)**
- `ShellCommandResult.notices()`.
- `BashTool` prefixes `"[environment] " + notice` lines before stdout, and never puts them into stderr.
- Tests use a fake shell that returns notices, and check the order and that stderr is untouched.
- *Later (EE-18):* `ShellExecutionException` / `ShellTimeoutException` carry `notices()` too, so the timeout and
  failure paths report them, and background tasks keep them for `BashOutput`.

**5d Descriptor (§10)**
- `SystemPromptRenderer.buildEnvironmentBlock(EnvironmentDescriptor)`, `EnvironmentContextProvider`, and the
  subagent prompt in `DefaultSubagentExecutor` read the execution's descriptor. The field names are unchanged, so the
  prompt text is byte-identical on local.
- `Environment` fields are removed.
- The rest of the §10 audit (stage 2 list) switches: hooks' `HookContext` gains
  `Optional<EnvironmentDescriptor>`, set from the execution where one exists. The compaction engine receives it
  through `CompactionRequest.getEnvironmentDescriptor()` (agent.compact → environment, allowed by the stage-5 row of
  §6.0).
- `ReplSession`/`AgentSetupFactory` show the working directory from the provider's workspace root.
- The descriptor leaves `AgentEnvironmentSnapshot`. `UserContextMessageBuilder` takes the working directory from the
  execution's descriptor.
- Tests:
  - a prompt golden test on local keeps the existing text;
  - a fake provider with descriptor `platform=Linux` renders Linux while the JVM is macOS;
  - the Unavailable descriptor renders its note;
  - the hook context carries the descriptor.

**Commits**: `feat(tool): stale-write protection with FileStamp` (+ `feat(filesystem): FileMetadata etag for S3 and
GridFS`); `feat(environment): ContentSearch with a ripgrep-backed local implementation`;
`feat(shell): environment notices on ShellCommandResult`;
`feat(agent)!: render the execution's EnvironmentDescriptor instead of host values`.

### Docs (in the stage that changes the behaviour; translations in the same commit)

| Doc | Stage | `.en.md` pair |
|---|---|---|
| `docs/design/tool/execution-environment.md` Status → `IMPLEMENTED (stages 1–5)` + implementation notes for the [plan decision] rows | final | none today (checked: `docs/design/tool/` has no `execution-environment.en.md`) |
| `docs/design/tool/execution-environment.md` **§4.4 rule "모든 스킬 저장소가 스테이징 소스를 낸다"** (the user asked for it; this edits the canonical doc). Proposed text below. Also update the §4.1 `StagedResource` row to add "`SkillRepository.resolveSource` 가 낸다" | 3 (with the skill-source commit) | none, as above; HANDOFF says so |
| `docs/features/skill/builtin-agent-skill-guide.md` (≈ lines 340–410: `${AIMON_SKILL_DIR}` = `Skill#getBaseDir()` → the `stage()` result; materialized copy is now a control-store copy, not agent-readable) | 3 | yes (`.en.md`) |
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` (≈ line 663: the `buildMaterializedSkillRegistry` snippet's directory arguments and the comment that the copy "becomes a real file the agent can read") | 3 | yes (`.en.md`) |
| `docs/features/tool/tool-development-guide.md` (tools read the env from the context; the constructor rule) and `.claude/rules/tool-development.md` | 2, 5 | yes |
| `docs/features/tool/parallel-tool-execution-guide.md` (`READ_FILES_KEY` → `FILE_STAMPS_KEY`) | 5 | yes |
| `docs/design/tool/parallel-execution.md`, `docs/design/tool/contract-hardening.md` | 5 | check |
| `docs/design/workflow/workflow.md` §6.3, `docs/features/workflow/workflow-usage-guide.md` | 4 | yes (guide) |
| `docs/design/agent-execution/artifact.md` (§9.3 storage) | 3 | check |
| `docs/design/agent-execution/agent-runtime-scope.md`, `docs/overview/scope-model.md` (ownedShell gone, provider lifetime row) | 2 | scope-model yes |
| `docs/overview/architecture.md`, `docs/overview/features.md` | 4 | yes |
| `docs/design/integration/spring-boot-starter.md`, `docs/backlog/spring-boot-starter-open-items.md` | 3 | check |
| `docs/getting-started/aimon-core-integration-via-cli-reference.md` | 2 | yes |
| `docs/design/filesystem/backend-contract.md` | 5 | check |
| `docs/design/memory/pluggable-memory-backend.md`, `docs/project/translation-glossary.md` (they only mention the old names) | whichever stage removes the name | glossary is itself the glossary |

Run `python3 scripts/check-translation-structure.py`, `check-translation-staleness.py` and `check-doc-links.py`
after each doc commit. Any translation not updated goes into HANDOFF.

**Proposed spec §4.4 addition** (Korean, canonical). It goes after the paragraph "**스킬을 렌더하는 모든 경로가
거친다.**":

> **모든 스킬 저장소가 스테이징 소스를 낸다.** `SkillRepository` 는 스킬마다 `stage()` 에 넘길 소스 — 읽기만 하는
> `VirtualFileSystem` 과 그 안의 스킬 디렉터리 — 를 돌려준다(`resolveSource`). VFS 저장소는 자기 VFS 를, 호스트
> 경로 저장소(`PathSkillRepository`)는 그 루트 위의 읽기 전용 로컬 파일 시스템(`ReadOnlyLocalFileSystem`)을, 클래스패스 저장소
> (`ClasspathSkillRepository`)는 번들 스킬 머티리얼라이즈와 같은 방식으로 트리를 걷는 읽기 전용 클래스패스 VFS 를
> 낸다. 그래서 스킬이 어디서 왔든 레지스트리가 `StagedResource` 를 싣고, `${AIMON_SKILL_DIR}` 는 언제나
> `stage()` 의 반환값이다 — 호스트 경로 스킬이라고 그 호스트 경로를 그대로 넣지 않는다(비로컬 제공자에서는 그
> 경로가 없다). 소스는 선택 사항이 아니다: 스킬을 찾았는데 소스가 비었으면 저장소 결함으로 보고 적재가 실패한다.
> 클래스패스 배치를 열거할 수 없으면(지원하지 않는 URL 프로토콜) 소스에는 `SKILL.md` 만 보이고 경고가 남는다 —
> 스킬은 쓸 수 있고, 다른 파일은 지금처럼 닿지 않는다. 레지스트리를 거치지 않고 손으로 조립한 `Skill` 처럼
> `StagedResource` 가 없는 스킬은 `${AIMON_SKILL_DIR}` 를 빈 문자열로 렌더하고 경고한다 — 저장소 경로로 되돌아가지
> 않는다. 스킬 도구가 모델에게 보여 주는 파일 목록도 같은 스테이징 경로 기준이다.

§15 is not changed. Its bullets "제어 저장소를 파일 도구에 노출하지 말 것" and "`${AIMON_SKILL_DIR}` 를 `stage()` 밖에서
채우지 말 것" now hold for every repository kind with no exception.

---

## 7. Failure modes and handling

| Failure | Handling | Where tested |
|---|---|---|
| `provider.resolve` throws or returns null | `UnavailableExecutionEnvironment`. The turn runs; file/shell tools return `ToolResult.error` with the cause; the prompt notes the cause (stage 5). There is never a host fallback. | stage 1 executor tests |
| No provider forwarded to a fork | Unavailable with an explicit cause | stage 1 |
| An enricher overwrites `EXECUTION_ENVIRONMENT` | `IllegalStateException`, caught per enricher and logged at WARN; the env is unchanged | stage 1 |
| A tool runs with no env key (a custom invoker, a test) | `ToolResult.error("No execution environment…")`, no throw | stage 2 tool tests |
| A background Bash task outlives its execution | The shell is captured in the future. The provider owns the shell and closes it only at app/runtime-eviction teardown, never at execution end. | stage 2 |
| Staging interrupted mid-copy | No marker, so the next `stage()` recopies | stage 3 |
| Staging over the limit | `StagingException` → `ToolResult.error` (Skill) or a command error | stage 3 |
| Skill repository finds a skill but returns no staging source | `SkillRepositoryException` naming the repository class. The skill does not load: a contract violation, never a silent "no directory" skill. | stage 3 registry test |
| Classpath layout cannot be enumerated | Source exposes `SKILL.md` only, with one WARN. The skill renders, `${AIMON_SKILL_DIR}` is a real staged dir, and the other files are unreachable, as today. | stage 3 classpath test |
| Path skills directory is read-only or missing | Works. `ReadOnlyLocalFileSystem` does no creation or writability check, so a missing root has no skills and the directory is not created (review-1) | stage 3 Path tests |
| Listed but unreadable resource (classpath or other source) | Skipped with WARN from the hash, `totalBytes` and the copy, matching the materializer | stage 3 classpath parity test |
| I/O error while hashing a skill directory at load | `SkillRepositoryException`, the same as a `findAllFiles` failure today | stage 3 |
| Hand-built `Skill` without `StagedResource` | `${AIMON_SKILL_DIR}` renders empty, WARN; no repository path is used | stage 3 SkillTool test |
| Host skill directory edited without a registry reload | The cached `contentKey` is stale. `stage()` **verifies content while copying**: it hashes the bytes it actually copies with the same `scan` algorithm, and if the result differs from `contentKey` it deletes the partial target and throws `StagingException("skill '<name>' changed since it was loaded; reload skills")`, without a marker. So new bytes are never stored under an old key, and a later revert cannot resurrect edited bytes (review-2 of this run, non-blocking). If a copy for the old key already exists (marker present), it is reused, which is §4.4's accepted "hash when the registry reads" behaviour. | stage 3 staging test: edit the source between load and first `stage()` → `StagingException`, no marker; `reloadSkill` → new key, copy succeeds |
| Staged script run as `./x.sh` | Fails: the VFS copy keeps no exec bit, the same as materialized copies today. Skills use `bash`/`python3`. | documented |
| Branch writes `.aimon/x` and is merged | Promotion to the root `.aimon/x` hits DENY and fails like any promotion failure. A branch cannot reach the control store through a merge. | stage 4 |
| Non-durable env recreated (sandbox) | The marker is absent on the target, so `stage()` recopies. There is no in-memory "already staged" state (§15). | stage 3 |
| Model tampers with a staged copy via the shell | Accepted (§4.4). The original in the control store is unaffected. | n/a |
| Artifact copy over the limit | Write succeeds; not registered; note in the result | stage 3 |
| Isolation unsupported | `WorkflowException` (run-fatal); the branch never runs unscoped | stage 4 |
| Promotion fails mid-merge | Unchanged: fail-fast, partial progress logged, no rollback, idempotent rerun | stage 4 |
| File changed between Read and Edit (tools, shell, human) | Stamp mismatch → error "…Read it again". The check-then-write race inside one call remains (§7 accepts it). | stage 5 |
| Coarse-mtime local fs, same size, same second | Missed (Q7). The etag closes it for S3/GridFS. | documented |
| `rg` missing or crashing | The fallback Grep runs, with identical output | stage 5 |
| `rg` honouring `.gitignore` or reading `.aimon/` | `--no-ignore --hidden` + DENY globs | stage 5 |

---

## 8. Risks and sequencing notes

- **Startup reads every skill byte once.** `DefaultSkillRegistry.loadComplete` hashes each skill, and `getAllSkills()`
  (used for skill listings) loads every skill. So the first listing reads every file of every user, materialized and
  bundled skill. §4.4 requires this (hash at load, not per call). A skill with large assets makes startup slower
  unless it `.stageignore`s them. This goes in the skill guide's `.stageignore` paragraph.

- **Stage 2 isolation gap.** Once the tools lose their constructors (stage 2), `WorktreeToolEnvironmentFactory`'s
  registry rebinding means nothing. To avoid a silent isolation regression between stages 2 and 4, stage 2 makes
  `derive()` attach a scoped `ExecutionEnvironment` (file view `.worktrees/{key}` over the parent env's filesystem,
  with the same shell) instead of cloning the registry. That is today's behaviour: the file tools are isolated and
  Bash is not. The temporary helper lives in `agent.impl.orca.environment`, which ArchUnit still carves out at that
  point, and it is deleted in stage 4. The alternative, doing stages 2 and 4 in one PR, is rejected because it makes
  the largest stage larger.
- **Test churn.** Stage 2 touches every test that constructs a file tool or `BashTool` (~15 files), plus every test
  that calls `OrcaAgentRuntimeFactory.create` without a provider. Add `TestExecutionEnvironments` in the first commit
  of stage 2 to keep this mechanical.
- **Public-SPI breaks** (allowed, spec header): the `OrcaToolProviderContext` getters, tool constructors,
  `ToolContextKeys.VIRTUAL_FILE_SYSTEM`, `READ_FILES_KEY`, `WorktreeEnvironmentFactory`,
  `withWorktreeEnvironmentFactory`, `withShell`, `Environment` fields, and the `FileMetadata`/`ShellCommandResult`
  additions. Commits use `!` and list them in CHANGELOG `Unreleased`.
- The external consumers `OrcaSandboxToolProvider` (aimon-sandbox) and `OrcaBrowserToolProvider` (aimon-browser)
  break on `getFileSystem()/getShell()`. They are out of scope and are listed in HANDOFF.

---

## 9. Open questions (not settled by the task statement or spec; each has a proposed default the build uses unless overridden)

1. **ArchUnit.** Two questions, detailed in §6.0:
   - A new baseline cycle, `agent <-> environment`, is added in stage 2. It is inherent in the spec's type placement
     and bounded by two guard rules. Should it instead be broken by moving `EnvironmentDescriptor` under `agent`?
   - The old impl-leak carve-out is removed in stages 2 and 4 rather than moved in stage 1.
2. **Stage-2 interim isolation** (§8): a temporary scoped-environment helper that preserves today's
   "file tools isolated, Bash not" behaviour until stage 4. Acceptable, or merge stages 2 and 4?
3. **The artifact path key for main-agent turns.** A turn has no `ExecutionId` (and must not publish one). Default: a
   per-execution archive key minted on `ArtifactCollector`. A fork uses its `ExecutionId`; a turn uses an unpublished
   `ExecutionId.generate("archive")` value (review 3: the prefix is not `"turn"`).
4. **`.gitignore` for `.aimon-staged/`.** §9.2 refers to a "CLI project initialisation" that does not exist. Default:
   no code writes `.gitignore`. The CLI docs tell users to add it. Alternative: the local provider writes a
   `.aimon-staged/.gitignore` containing `*`. Recommended, because it is self-contained and needs no project init.
5. **Stamp key normalisation** without a VFS `normalize` method: `VfsPaths.rootRelative`, which is the same
   root-relative rule `ScopedVirtualFileSystem` already applies to local and URI bases. The spec wording "normalised
   by the environment's filesystem" could imply a new VFS method.
6. **GridFS etag.** md5 is unavailable in driver 5.x, so the plan uses the `ObjectId` hex. It changes on every
   rewrite even when the content is identical, which is a safe false positive.
7. **LocalFileSystem etag.** None; mtime is nanosecond on APFS/ext4, but second-granular filesystems (HFS+, some
   network mounts) can miss a same-size same-second rewrite. Should local add an opt-in content-hash etag?
8. **Staging size limit default.** The spec names none. Default 50 MB per skill directory, matching the artifact
   per-file limit.
9. **`EXECUTION_ENVIRONMENT_PROVIDER` key.** Not in the spec. The plan adds it so forks can reach the per-runtime
   provider without a registration-time handle (§2).
10. **`RoutineExecutor` as a third resolve site.** Not in the spec. It is required so that routine `Bash`/file steps
    keep working after stage 2.
11. **Supplied/factory VFS in bootstrap** (GridFS/S3): the plan uses one VFS for both control (scoped at `.aimon`) and
    work (path-rule wrapped), plus a local shell rooted nowhere in particular. It does not split them. Because the
    control fs (`ScopedVirtualFileSystem(vfs, ".aimon")`) is a different instance from the workspace `vfs`, the
    same-instance passthrough never applies. Staging always copies into `/.aimon-staged/…` **on the remote VFS**,
    where the file tools can read it but the host shell cannot. So `bash ${AIMON_SKILL_DIR}/x.sh` keeps failing there,
    which is the existing §1.5 limitation, now explicit and recorded in HANDOFF. Should a separate control-store spec
    be required instead?
12. **Skill-declared hook shell** (§14) and **ending background commands** (§14): unchanged per the task (hooks keep
    `skillHookShell` on the host; there is no kill tool). Recorded in HANDOFF.
13. **`EnvironmentRequest.agent` is optional** (§2), although the spec's table marks it as required. Forks and
    workflow runs do not carry an `Agent`.
14. **`EnvironmentProviding` + `instanceof` in `RoutineExecutor`** instead of adding an accessor to `AgentRuntime`.
    This keeps the agent-core allow-list untouched (§6.0). The alternative is to widen that rule to `environment`.
15. **Per-runtime providers in bootstrap** (§2): the spec's §4.3 calls the provider application-scoped. On eviction a
    runtime's background `Bash` tasks lose their shell, which is today's behaviour.
16. **Staging sweep scope** (§6 stage 3, review-2): the sweep runs only in owned `workspaceRoot` mode and only for
    non-newest copies older than `stagingSweepGrace` (24h). A shared or borrowed VFS is never swept, so copies
    accumulate there, bounded by the number of distinct skill versions. HANDOFF must say, together with the Q11 note
    that the host shell cannot see copies on a remote VFS, that the supplied-VFS deployment has both properties.
    Alternative: namespace `stagingRoot` per provider and sweep that namespace. Rejected for now: identical skills
    shared by many runtimes would then be copied once per runtime.
17. **Retire `BundledSkillMaterializer`?** The classpath repository can now stage its files directly, so the startup
    copy into `.aimon/bundled-skills` only duplicates what `stage()` does. Default: **keep it** in this task (§2).
    Retiring it touches bootstrap layering, `StackPaths`, the starter's `AimonRuntimeHints` and the documented
    layout. It is a follow-up candidate, recorded in HANDOFF.
18. **Placement of the read-only local factory.** Default: `at.aimon.core.filesystem.VirtualFileSystems.readOnlyLocal`
    (returning `filesystem.impl.local.ReadOnlyLocalFileSystem`),
    in the filesystem SPI package, referencing `filesystem.impl` inside the same tree. The alternative is a narrow
    carve-out for `skill.repository` in `filesystemImplMustNotLeakOutsideFilesystemTree`. Both pass ArchUnit; the
    factory keeps the rule unchanged.
19. **`resolveSource` is abstract** (a compile break for any external `SkillRepository`). This is intended under the
    user's "every repository" decision and the no-compat policy, and it is listed in CHANGELOG `Unreleased`. The
    alternative, a default method that returns empty, would let custom repositories silently skip staging.
20. **A branch-local `.aimon/` fails on promotion** (§7, stage 4 test). The spec does not address it. Before this
    change a branch's `.aimon/` merged into the root `.aimon/`, meaning the model could write the control plane
    through a workflow branch. Default: let DENY refuse it, which is consistent with §15 "제어 저장소를 파일 도구에
    노출하지 말 것", and record it in HANDOFF as a behaviour change.

---

## 10. Where the implementation departed from this plan

Everything not listed here was built as §1–§8 describe. Each entry names what the plan said, what was done instead,
and why. Entries marked **(open)** are also tracked in
[`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md).

### 10.1 Process

- **No stage commits.** §6 plans one or more commits per stage. The task that ran the implementation said "do not
  commit yet", so all five stages are one working-tree change, and the pipeline commits after review. As a result the
  scaffolding that existed only to keep each intermediate commit green was never built: the stage-1 default
  *borrowing* provider in `OrcaAgentRuntimeFactory`, the stage-2 `ScopedExecutionEnvironment`, and the temporary ArchUnit
  exemptions (the `agent.impl.orca` exemption in `environmentImplMustNotLeakOutsideEnvironmentTree`, and the
  `agent.impl.orca.environment..` carve-outs). The final rules are the stage-4/5 rows of §6.0.
- **Stage 0 was a fast-forward.** `origin/main` already contained this branch (#194 merged it), so there was no merge
  commit to make.

### 10.2 SPI and local provider

- **Accessor style.** `EnvironmentDescriptor` and `EnvironmentRequest` use `workingDirectory()` / `agentRuntimeId()`
  accessors, as the spec's text does (`env.descriptor().workingDirectory()`). The other new value types
  (`StagedResource`, `FileStamp`, `ContentQuery`, `SkillSource`, `ArtifactPolicy`) use `getX()`.
- **Extra helpers.** `ExecutionEnvironmentAccess.of(ToolContext)` (an `Optional`, next to `require`) and
  `UnavailableExecutionEnvironment.of(String)` (a cause with no exception, used when no provider is configured).
- **Default path rules in both modes.** §3.2 gives the local provider its default rules (`.aimon/` DENY,
  `.aimon-staged/` READ_ONLY) in `workspaceRoot` mode, and has bootstrap pass them explicitly in `.fileSystem(vfs)` mode.
  The provider applies them in both modes by default; `pathRules(List.of())` opts out. The result is the same, and a
  new assembly is closed by default.
- **`contentSearch` is on by default** (probed once, as planned), and `contentSearch(false)` turns it off. Tests and the
  runtime integration support turn it off, so `Grep` output does not depend on whether `rg` is installed.
- **`StagedResource`'s hash.** Each entry is `relPath + '\0' + bytes + '\0'`: a trailing separator was added to the
  planned `relPath + '\0' + bytes`, so two entries cannot run into each other.
- **Review 3, third note.** The general scan/stage contract (copy exactly `getFiles()`; no marker when a recorded file
  is unreadable or the bytes no longer hash to the key) is documented on `StagedResource` and `LocalStaging`, not on
  the classpath VFS.
- **Isolated environments.** `LocalIsolatedEnvironment.isolate(...)` returns empty (no nested isolation). Its descriptor
  carries a `notes` line saying that only the file tools and the default cwd are scoped, so a fork running in a branch
  sees one more `Notes:` line in its prompt.
- **Path rules resolve with `VfsPaths.resolveUnder`, not `rootRelative`, match ignoring case, and fail closed (review 2
  of the build, blocking 1).** §2's "Stamp key and path-rule normalisation" row had `PathRuleVirtualFileSystem` use
  `rootRelative`, which returns `null` for `../<base-name>/.aimon/x` and an absolute path for
  `{base}/../<base-name>/.aimon/x`. The decorator treated both as "no rule applies" and passed the raw path on, and
  `LocalFileSystem` resolved it back into the workspace, so the file tools read `.aimon/` and wrote `.aimon-staged/`.
  `resolveUnder` resolves a relative path against the base, normalises it, and returns where it lands (or `null` when
  it lands outside). The decorator matches rules on that, and treats a path that lands outside like a `DENY`ed one
  (`FileAccessDeniedException("outside this filesystem")`, `exists` false) rather than passing it through.
  `PathRule.covers` and `RipgrepContentSearch`'s hidden check compare case-insensitively
  (`VfsPaths.isUnderIgnoreCase`), so `.AIMON/x` is caught on APFS/NTFS; on a case-sensitive store this also hides a
  user directory spelled `.AIMON`, which is the safe direction. Since PR review 1 the comparison folds Unicode, not
  only ASCII (see the entry below). `rootRelative` itself is unchanged: stamp keys and
  `ScopedVirtualFileSystem` keep their behaviour. `RipgrepContentSearch` resolves its target with `resolveUnder` too.
- **The GridFS etag is the file document's `ObjectId`, not the spec's md5 (open, EE-5).** Design §7 lists the etag
  source as "GridFS(md5)". `GridFSFile.getMD5()` was removed in driver 5.0 (§0 item 8), so `GridFSFileSystem.getMetadata`
  uses the hex `ObjectId` of the newest revision, as §6 stage 5 planned. Every write uploads a new document, so the id
  changes with every rewrite; it also changes when the content does not, which is a false "changed" and the safe
  direction. This departs from the spec rather than from the plan, and review 3 of the build asked for it to be listed.
- **The startup staging sweep never follows a symbolic link (review 3 of the build, blocking).** The plan's sweep used
  `Files.isDirectory` and `Files.list`, which follow links, so a `.aimon-staged` link (committed in a cloned repository,
  or made through the shell) led the sweep to delete week-old directories wherever it pointed. The sweep now does
  nothing when the staging directory is a link or its real path is not under the workspace's real path, and it only
  descends into and deletes name and copy directories that are real directories (`LinkOption.NOFOLLOW_LINKS`).
- **`LocalStaging` validates the content key and every recorded file path (review 3 of the build).** A content key must be
  lowercase hex, and a recorded path must be relative with no empty, `.` or `..` segment and no `\`. Either failure is a
  `StagingException` before anything is written or deleted. `scan` never produces anything else; a hand-built
  `StagedResource` (SPI code, a remote repository's keys) could otherwise write outside its copy or make the cleanup of
  an interrupted copy delete the control store.
- **Path rules fold Unicode, not only ASCII (PR review 1).** APFS folds U+017F `ſ` to `s`, so
  `.aimon-ſtaged/…` named the staging area and `Write` replaced a staged script, and `.ſecrets/…` read through a
  `DENY` rule. `VfsPaths.isUnderIgnoreCase` now compares names folded by `VfsPaths.foldCase`: NFKC, then
  `toLowerCase`/`toUpperCase`/`toLowerCase(Locale.ROOT)`, then NFC. That covers compatibility forms (`ſ`, the `ﬅ`
  ligature), full case mappings (`ß` and U+1E9E `ẞ` → `ss`, the Kelvin sign → `k`) and NFD spellings. The leading
  lower-casing matters: `ẞ` is already uppercase, so upper-then-lower stopped at `ß`, and `.ẞh/id` read through a
  `deny(".ssh")` rule on APFS (PR review 2). Folding more than a store does only hides more. The PR review brute-forced
  every BMP code point that APFS equates with a one- or two-letter ASCII name and found `ẞ` the only mismatch, so this
  is checked for short names, not proven for all of Unicode. NTFS's trailing-dot and 8.3 aliases are still open (EE-33).
- **`stagingRoot` is validated, and the sweep cannot delete outside a staging area (PR review 1).** With explicit
  `pathRules(...)`, `stagingRoot(".")` or `stagingRoot("src")` let the startup sweep delete old workspace directories.
  `build()` now requires one directory name (no `.`, `..`, `/`, `\` or `:`) that is not the control store, and throws
  `IllegalArgumentException` otherwise. The sweep also does nothing when the staging directory's real path is the
  workspace's, and it only deletes copy directories whose name is a content key: exactly
  `StagedResource.CONTENT_KEY_HEX_LENGTH` (16) lowercase hex characters, so a misconfigured `stagingRoot("logs")`
  leaves `logs/2024/01` alone (PR review 2). `stage()` validates a hand-built key against the same shape.
- **A failed provider build closes what it built (PR review 1).** When a step of the constructor throws after the
  owned `LocalFileSystem` or `LocalShell` exists, both are closed before the exception propagates. The package-private
  `Builder.ownedResourceDecorator(...)` lets a test see it. EE-23 still covers the bootstrap level.

### 10.3 Executors, runtime, factory

- **`OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory(Function<AgentRuntimeId, …>)`** was added next to
  `withExecutionEnvironmentProvider(p)`. Bootstrap still sets a single provider, under the same factory lock it
  already holds for the skill registry.
- **The runtime's `Environment` is `Environment.createDefault()` from stage 3 on**, and after stage 5 `Environment` holds
  only `timeZone`, as planned. `ReplSession`, `AgentSetupFactory` and the CLI's model-mismatch hint read the working
  directory from the runtime's provider (`AgentSetupFactory.workingDirectoryOf`).
- **Routines get no `FILE_STAMPS_KEY` (open, EE-11).** §6 stage 5a puts the stamp map into "both executors and the
  routine context". It is in both executors, not in routines. With the map, a routine `Write` that overwrites a file
  would start failing without a preceding `Read`, and deployed routines would break. Without it, routines behave as
  before: `Edit` always refuses and `Write` does not check.
- **`AgentEnvironmentSnapshot` keeps `workingDirectory` (open, EE-10).** §6 stage 5d takes the working directory out of
  the snapshot. Instead, `UserContextMessageBuilder.build(snapshot, executionWorkingDirectory)` lets the execution's
  descriptor win, and the snapshot's value is used only when the descriptor has none. This avoided churning every
  snapshot collector.
- **Hook contexts (open, EE-9).** `HookContext.getEnvironmentDescriptor()` exists (default empty) and is filled for
  `PreToolContext` and `PostToolContext` (from the tool context in `SingleToolInvoker`). `CompactionRequest` did not
  gain a descriptor, and the compaction, lifecycle, subagent and permission contexts stay empty, so
  `agentCompactMayDependOnExtHook` did not need its stage-5 row.
- **One shared renderer for the environment block.** `agent.context.EnvironmentBlocks.render(EnvironmentDescriptor)` is
  used by the system prompt, the fork prompt and `EnvironmentContextProvider`, instead of three copies. A field the
  descriptor lacks is left out, and `notes` are rendered as `Notes: …`, so a local environment's text is unchanged. The
  fork prompt now always has the block; before, it was skipped when a fork had no `Environment`.
- **`RecentFilesRestoreHook` takes the `ToolContext` its reads run with.** A caller-wired `PostCompactHook` could no
  longer read through `ReadTool` with `ToolContext.empty()` once `Read` needed an environment. The hook cannot import
  `ToolContextKeys` without closing a `hook <-> tools` cycle, so the caller builds the context.
- **`OrcaAgentRuntimeManager.Builder.build()` requires a factory that has a provider (review 2 of the build, blocking
  3).** The plan made `OrcaAgentRuntimeFactory.create(...)` refuse without a provider but left the manager defaulting
  to `new OrcaAgentRuntimeFactory()`, so the documented direct-core embedding path built and then failed on the first
  `getOrCreateRuntime(...)`. Choosing a provider in the manager would pick a workspace nobody chose (§3 decision: no
  host fallback), so the default is gone: a missing factory, or one without a provider
  (`OrcaAgentRuntimeFactory.requireExecutionEnvironmentProvider()`, package-private), fails at `build()`. The
  embedding guide (both languages) and the session guide now pass a provider and the control root.

### 10.4 Skills and staging

- **Review 3, second note (staging mismatch).** The error when a skill changed on disk after it was loaded now says
  what the user can do: "Restart the application, or reload the skill registry". There is no re-keying. See EE-3.
- **`.gitignore` for `.aimon-staged/`.** This follows Q4's default: no code writes it, and the skill guide tells users
  to add it (EE-4).
- **`ClasspathSkillRepository.findAllFiles`** keys each file by its path relative to the skill directory, with the
  classpath resource path as the value, as planned. Classpath skills are not split into the root/scripts/references/
  assets categories, so in `SkillTool`'s "Available Files" they appear under "Other Files".
- **Symbolic links in a host-path skill (review 4, blocking).** The plan's table said "symlinks are followed, as
  today", but `ReadOnlyLocalFileSystem.listRecursive` walked with `Files.walk` and no `FOLLOW_LINKS`, so a skill
  directory installed as a link (`skills/foo -> ../shared/foo`), or a linked subdirectory inside one, scanned to no
  files and staged as an empty copy. The rule now (spec §4.4, chosen by the user): the listing walks with
  `FOLLOW_LINKS` and checks every entry's real path, and `read` checks the file's real path. A link is followed when
  it resolves inside the repository root or inside an operator-configured allowed root
  (`PathSkillRepository.builder(root).allowedLinkRoot(...)` / `.allowedLinkRoots(...)`, and
  `VirtualFileSystems.readOnlyLocal(root, allowedLinkRoots)`); the default is none, which allows the root only. A link
  that resolves anywhere else throws `InvalidPathException` naming the link and its real path, and the registry reports
  it as a `SkillRepositoryException`, so the skill does not load. A link that loops back to an ancestor is skipped
  with a WARN. As a backstop, `DefaultSkillRegistry` fails the load when the source shows a `SKILL.md` but the scan
  found zero files. The startup sweep's `NOFOLLOW_LINKS` rule (review 3) is unchanged: the sweep deletes, this rule
  reads. This also settles EE-16 (a linked file outside the root is now refused, not staged). Tests:
  `SkillLinkStagingTest` (five cases) and `ReadOnlyLocalFileSystemTest.linkedFileConfined`.
- **A skill that fails to load no longer fails the listing (PR review 1, blocking).** `getAllSkills()` and `reloadAll()`
  let the first `SkillRepositoryException` escape. So one skill that the link rule refused took down every skill, and
  with them `SkillTool.getDefinition()` (its description is built from the listing), tool registration, `/skills`,
  `SkillBackedCommandRegistry` and the REPL banner. That loop predates this change, but the link rule gave it a new
  trigger. Both now skip that skill with a WARN (spec §4.4: only that skill does not load). A failure is not cached,
  so `getSkill(name)` keeps throwing the error that says why. Other failures (the repository listing itself, a parse
  error) still propagate as before. Test: `SkillLinkStagingTest.refusedSkillSkippedInListings`.
- **The zero-file backstop exempts `.stageignore` (PR review 1).** The backstop ran after `.stageignore` was applied, so
  a skill whose ignore file excluded everything was refused. It now refuses only when the source's own listing is
  empty too. Test: `SkillLinkStagingTest.stageIgnoreExcludingEverythingLoads`.
- **`StagedResource.scan` fails on an unreadable file (PR review 1).** §3.3's classpath row has `scan` skip a listed
  entry it cannot read, with a WARN, "exactly as `BundledSkillMaterializer` skips it". That is the silently missing file
  under `${AIMON_SKILL_DIR}` the link rule exists to prevent. `scan` now throws `UncheckedIOException` naming the file
  and pointing at `.stageignore`, and the registry reports it as a `SkillRepositoryException`. An entry the ignore file
  excludes is never read. The materializer itself is unchanged, so the classpath source and the materialized copy can
  now differ for an unreadable resource: the source refuses the skill, and the materializer skips the resource.
  Test: `StagedResourceTest.unreadableFailsScan`.

### 10.5 Tools

- **`GrepTool` sorts matching files by path, in both the walk and the `ContentSearch` path.** Byte-identical output
  needs a deterministic order, and `Files.walk` order was never specified. The walk's output order therefore changed
  from filesystem order to path order.
- **`RipgrepContentSearch` does not answer multiline queries.** `rg -U` reports whole line blocks, while `Grep`'s walk
  reports the matched text. A multiline query throws, and `Grep` walks instead.
- **Edit without a stamp map** still refuses ("Read the file before modifying it"), as the old `READ_FILES_KEY`
  check did. `Write` with no map in the context does not check.
- **`ArtifactArchive.register(...)`** holds the durable/non-durable branch that the plan put into the two artifact-aware
  tools, so they cannot diverge. A non-durable environment with no control store gets a note rather than a
  registration.
- **The archive directory is the archive key made path-safe, not the key itself** (`ArtifactArchive.directoryName`).
  The spec writes `/artifacts/{executionId}/{fileName}`, but every execution id contains `':'`
  (`archive:<uuid>`, `subagent:<name>:<uuid>`), and `LocalFileSystem`'s `PathValidator` rejects `':'`. So every
  character outside `[A-Za-z0-9._-]` becomes `'_'`. The ids end in a UUID, so two keys cannot share a directory.
  A copy that fails for any reason now adds the `[artifact not registered: …]` note as well; before, it was only
  logged.
- **`RipgrepContentSearch` refuses a target at or under a hidden prefix, and drops hidden paths from rg's output.**
  The plan relied on one `--glob '!/<prefix>'` per hidden prefix, which leaves two holes. rg does not apply globs to
  a path named on its command line (`Grep(path=".aimon")`). And a later glob wins, so the user's `glob="*"`
  re-included the store. A refused target falls back to `Grep`'s walk, where the path-rule filesystem hides the
  store. The exclusion globs are still passed, now after the user's glob.
- **`Grep` propagates `ExecutionEnvironmentUnavailableException` from its walk.** The walk's catch-all blocks turned an
  unavailable environment with an explicit `path` into "No matches found".
- **`WikiIngest` probes its source directory before ingesting (review 2 of the build, blocking 2).**
  `DefaultWikiKnowledgeBase.listSourceFiles` turns any listing failure into an empty list, so an unavailable
  environment produced a successful "Documents ingested : 0". The tool calls `isDirectory(source_directory)` first and
  maps `ExecutionEnvironmentUnavailableException` to `ToolResult.error` with the cause; the knowledge base is unchanged.
- **`LocalStaging` refuses a resource name that is not one path segment** (empty, `.`, `..`, or containing `/` or `\`)
  with a `StagingException`. The sweep and the cleanup of an interrupted copy trust the name.
- **`RipgrepContentSearch` checks the target's real path (review 3 of the build).** rg follows a symbolic link named on
  its command line, so `Grep(path="lnk")` with `lnk -> .aimon`, or with a link out of the workspace, read through it.
  The target's real path must now lie under the root's real path and outside every hidden prefix; otherwise the search
  throws and `Grep`'s walk answers, and the walk's path validation refuses the link.
- **rg's timeout and cancellation take effect while rg runs (review 3 of the build).** The plan read rg's stdout to the end
  before waiting with a timeout, so a stuck rg (a huge tree, a FIFO target) was never killed and a cooperative `Grep`
  interrupt was not seen. stdout is now drained on its own thread while the caller polls the process, the timeout and
  `ContentQuery.isCancelled()`; either kills rg. `ContentQuery` gained `cancellation(BooleanSupplier)` (default: never),
  and `Grep` passes its `CancellationSignal`.
- **An isolated branch's content search is confined to the parent workspace (PR review 1).** `rootedAt(branchRoot)`
  dropped the parent's hidden prefixes, and `confineRealPath` compared the target with the branch root's own real
  path. So a `.worktrees/step_1 -> ../.aimon` link made the branch's `Grep` return control-store contents. The branch
  search now keeps the parent's hidden prefixes, and it also requires the branch root's real path, and the target's,
  to lie under the parent root's real path and outside every hidden prefix. Test:
  `RipgrepControlStoreTest.linkedBranchRootRefused`.
- **A path-rule refusal is a plain tool error (PR review 1).** `Read`, `Write` and `Edit` let
  `FileAccessDeniedException` fall into their catch-all. The model saw "Unexpected error: Access denied …" (or "Failed
  to write file: …"), and `Read` and `Write` logged it at ERROR with a stack trace. The tools now return the
  exception's message ("Access denied: <path> (<reason>)") and log it at WARN. Test: `FileToolsPathRuleTest`.

### 10.6 Bootstrap, CLI, starter, sample

- **`ExecutionEnvironmentSpec`** has `factory(Function)`, `shared(provider)` (caller-owned and never closed by the
  stack), `maxStagedBytes`, `controlWritable` and `contentSearch`. The starter maps an `ExecutionEnvironmentProvider`
  bean to `shared(...)`, so Spring closes it and the stack does not.
- **The CLI keeps `FileSystemSpec.supplied(jarDirFs)` and adds `ExecutionEnvironmentSpec.factory(workspaceRoot(jarDir))`.**
  Its control store is `ScopedVirtualFileSystem(jarDirFs, ".aimon")` (the supplied-VFS path in bootstrap), not a
  separate `LocalFileSystem(jarDir/.aimon)`. The physical paths are the same, and the provider still owns its workspace,
  so the startup sweep runs.
- **`ToolSpec.artifactPolicy(ArtifactPolicy)`** mirrors the starter's `aimon.tools.artifact.*`, as §6 stage 3 describes.
- **Review 3, first note (`AgentModelProviderCheck`).** Its host-path hint resolves
  `StackPaths.CONTROL_DIRECTORY` + `StackPaths.AGENTS_DIRECTORY` (`{workingDir}/.aimon/agents`). Its tests were not
  changed.
- **`samples/aimon-sample-app`** was not in the plan. Its introspection endpoint read `OrcaAgentRuntime.getFileSystem()`.
  It now reads the control store for the materialized files, reported as `.aimon/…` relative to the workspace (the
  shape its packaging test asserts), and reads the environment's working directory for `workspaceRoot`.

### 10.7 Behaviour changes that tests pinned

These are intended by the spec, and the tests that pinned the old behaviour were updated rather than weakened:

- `Bash` runs in the workspace root (the environment's working directory), not in the JVM's `user.dir`
  (`BashToolTurnIntegrationTest`).
- The user-context `working-directory` reminder shows the execution's working directory, not the snapshot's
  (`OrcaAgentExecutorUserContextInjectionTest`).
- The bootstrap teardown plan names `executionEnvironment(agent:…)` and `controlFileSystem(agent:…)` per runtime, where it
  used to name `fileSystem(agent:…)` (`AimonStackBuilderTest`).
- `OrcaBashToolProvider` always registers `Bash` and `BashOutput`; an environment without a shell fails the call
  instead.
- A hand-built skill without a `StagedResource` renders `${AIMON_SKILL_DIR}` empty. Before, its directory was derived
  from a resource path (`SkillBackedCommandExecutorTest`, `SkillToolTest`).

### 10.8 Workflow isolation, hardened after the fact (EE-8, EE-25, EE-27, EE-28, EE-29)

A later change closed five backlog items that this plan's Stage 4 had left behind. Its approved design, and where the
build departed from it, are in [`workflow-isolation-hardening.md`](workflow-isolation-hardening.md). Against this
plan:

- **The pinned test in Stage 4 is reversed.** "Branch-local `.aimon/` is not denied" pinned the rules as root-anchored
  and treated the merge-time `DENY` as the guard. That guard left a half-merge (no rollback), so a branch now applies
  the parent's rules re-anchored at its own root, through a second `VirtualFileSystems.withPathRules` layer placed
  *below* the `ScopedVirtualFileSystem`. The branch's `.aimon/` is refused at write time and hidden from listings, so
  a merge never meets one. The rules are still anchored, not "match `.aimon/` at any depth" — only the branch root is
  added. `LocalIsolatedEnvironmentTest.branchLocalControlDirectoryIsDenied` replaces the old test.
- **`isolate()` can throw.** The plan's SPI returned empty for "unsupported". Empty still means "this kind of
  environment has no isolation"; throwing means "refused here, and the message is why".
  `UnavailableExecutionEnvironment.isolate` throws its `ExecutionEnvironmentUnavailableException`, and
  `LocalIsolatedEnvironment.isolate` throws `UnsupportedOperationException` (no nested isolation). The runner chains
  the exception as the `WorkflowException`'s cause.
- **New SPI method `isolatedFrom()`**, default empty. `WorktreeMerge.promote` uses it, together with identity checks,
  to refuse branches that do not belong to the parent, and it reads every promoted file's metadata before the first
  write. After the build review it also checks every destination against the parent's path rules first, read through
  the new `VirtualFileSystems.pathRules` (the local parent's filesystem is the path-rule layer; other filesystems
  answer empty and are checked only on write).
- **Branch host paths and shell-made staging copies (build review).** `ScopedVirtualFileSystem` matches an absolute
  `{base}/.worktrees/{key}/…` after normalisation and ignoring case, so `./`, `//` and a case variant of the key no
  longer nest the branch inside itself past its rules. It also leaves out of its listings any branch-local entry under
  a shared prefix — a staging directory a shell made in the branch root, which no caller path reaches.
- **The shared staging prefix matches ignoring case**, like the path rules it sits beside.
