package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.impl.orca.environment.MergeReport;
import at.aimon.core.agent.impl.orca.environment.WorktreeMerge;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.ShellCancelledException;

@DisplayName("LocalIsolatedEnvironment — isolate() of the local environment (execution-environment design §4.2)")
@DisabledOnOs(OS.WINDOWS)
class LocalIsolatedEnvironmentTest {

    @TempDir
    Path workspace;

    @TempDir
    Path skillSource;

    private LocalExecutionEnvironmentProvider provider;
    private ExecutionEnvironment parent;
    private ExecutionEnvironment branch;

    @BeforeEach
    void setUp() {
        provider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(workspace).contentSearch(false).build();
        parent = provider.resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("iso")).build());
        branch = parent.isolate("k").orElseThrow();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("a command in the branch shell is stopped by its cancellation signal, like one in the parent's")
    void branchShellHonoursCancellation() throws Exception {
        assertThat(branch.shell().supports(ShellFeature.CANCELLATION)).isTrue();
        final ShellCancellationSource source = ShellCancellationSource.create();
        final ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofMinutes(5))
                .cancellation(source.token()).build();

        // The branch shell derives its options to set the working directory; a derivation that dropped the signal
        // would leave this command running until its timeout with nothing to say why.
        final CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(
                () -> catchThrowable(() -> branch.shell().execute(() -> "echo $$ > pid; exec sleep 120", options)));
        final Path pidFile = workspace.resolve(".worktrees/k/pid");
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!(Files.exists(pidFile) && !Files.readString(pidFile).isBlank())) {
            assertThat(System.nanoTime()).as("the command never started").isLessThan(deadline);
            Thread.sleep(20);
        }
        final long pid = Long.parseLong(Files.readString(pidFile).trim());

        source.cancel();

        assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    @DisplayName("a branch reports its parent's background command ceiling")
    void branchInheritsTheBackgroundCeiling(@TempDir Path other) {
        assertThat(branch.backgroundCommandTimeout()).isEmpty();

        try (LocalExecutionEnvironmentProvider limited = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(other).contentSearch(false).backgroundCommandTimeout(Duration.ofMinutes(10)).build()) {
            final ExecutionEnvironment root = limited
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("iso")).build());

            assertThat(root.backgroundCommandTimeout()).contains(Duration.ofMinutes(10));
            assertThat(root.isolate("k").orElseThrow().backgroundCommandTimeout()).contains(Duration.ofMinutes(10));
        }
    }

    @Test
    @DisplayName("file-tool writes land under .worktrees/{key}/")
    void writesLandInTheBranch() {
        branch.fileSystem().write("out.txt", "branch");

        assertThat(workspace.resolve(".worktrees/k/out.txt")).hasContent("branch");
        assertThat(workspace.resolve("out.txt")).doesNotExist();
    }

    @Test
    @DisplayName("a command with no working directory runs in the branch root; an explicit one is respected")
    void shellDefaultsToTheBranchRoot() throws Exception {
        final ShellCommandResult inBranch = branch.shell().execute(() -> "pwd");
        final ShellCommandResult explicit = branch.shell().execute(() -> "pwd",
                ExecutionOptions.builder().workingDirectory(workspace.toString()).build());

        assertThat(Path.of(inBranch.stdout().trim()).toRealPath())
                .isEqualTo(workspace.resolve(".worktrees/k").toRealPath());
        assertThat(Path.of(explicit.stdout().trim()).toRealPath()).isEqualTo(workspace.toRealPath());
    }

    @Test
    @DisplayName("the branch is not durable, and its descriptor names the branch root's host path")
    void branchIsNotDurable() {
        assertThat(parent.durable()).isTrue();
        assertThat(branch.durable()).isFalse();
        assertThat(branch.descriptor().workingDirectory())
                .isEqualTo(workspace.toAbsolutePath().normalize().resolve(".worktrees/k").toString());
    }

    @Test
    @DisplayName("a key that is not [A-Za-z0-9_]+ is rejected")
    void badKeyRejected() {
        assertThatThrownBy(() -> parent.isolate("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parent.isolate("a/b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a staged skill is shared with the parent, readable from the branch and never promoted")
    void stagedFilesAreReachableFromABranch() throws Exception {
        Files.createDirectories(skillSource.resolve("demo/scripts"));
        Files.writeString(skillSource.resolve("demo/SKILL.md"), "# demo");
        Files.writeString(skillSource.resolve("demo/scripts/x.sh"), "echo staged-script\n");
        final StagedResource resource = StagedResource.scan(VirtualFileSystems.readOnlyLocal(skillSource), "demo",
                "demo");

        final String path = branch.stage(resource);

        assertThat(path).startsWith("/").isEqualTo(parent.stage(resource));
        assertThat(Path.of(path)).isEqualTo(
                workspace.toAbsolutePath().normalize().resolve(".aimon-staged/demo/" + resource.getContentKey()));
        assertThat(workspace.resolve(".worktrees/k/.aimon-staged")).doesNotExist();
        assertThat(read(branch, path + "/scripts/x.sh")).isEqualTo("echo staged-script\n");

        final ShellCommandResult cat = branch.shell().execute(() -> "cat " + path + "/scripts/x.sh");
        assertThat(cat.stdout()).isEqualTo("echo staged-script\n");
        final ShellCommandResult run = branch.shell().execute(() -> "sh " + path + "/scripts/x.sh");
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("staged-script");

        assertThatThrownBy(() -> branch.fileSystem().write(path + "/y", "tamper"))
                .isInstanceOf(FileAccessDeniedException.class);

        branch.fileSystem().write("result.txt", "done");
        assertThat(branch.fileSystem().listRecursive(".")).containsExactly("result.txt");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);
        assertThat(report.promoted()).containsExactly("result.txt");
    }

    /**
     * EE-26. A skill whose source is the workspace itself is not copied by the parent: {@code stage()} answers with the
     * source directory. A branch cannot use that path — its file tools map it into {@code .worktrees/k/}, where the
     * skill is not — so a branch is given a copy in the staging area, the one directory it shares with the parent.
     */
    @Test
    @DisplayName("EE-26: a skill that lives in the workspace is staged for a branch as a copy both its file tools and"
            + " its shell can use")
    void workspaceResidentSkillIsUsableFromABranch() throws Exception {
        parent.fileSystem().write("skills/demo/SKILL.md", "# demo");
        parent.fileSystem().write("skills/demo/scripts/x.sh", "echo workspace-script\n");
        // What a VfsSkillRepository over the workspace filesystem hands the registry.
        final StagedResource resource = StagedResource.scan(parent.fileSystem(), "skills/demo", "demo");
        final String ws = workspace.toAbsolutePath().normalize().toString();

        final String path = branch.stage(resource);

        // The shell (bash ${AIMON_SKILL_DIR}/x.sh) resolves the string on the host ...
        final ShellCommandResult run = branch.shell().execute(() -> "sh " + path + "/scripts/x.sh");
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout()).contains("workspace-script");
        // ... and the file tools (Read on a path of the rendered body) resolve it through the branch scope.
        assertThat(read(branch, path + "/scripts/x.sh")).isEqualTo("echo workspace-script\n");
        assertThat(branch.fileSystem().listRecursive(path)).isNotEmpty();
        assertThat(path).isEqualTo(ws + "/.aimon-staged/demo/" + resource.getContentKey());
        // Nothing of it is in the branch, so a merge promotes nothing.
        assertThat(branch.fileSystem().listRecursive(".")).isEmpty();
    }

    @Test
    @DisplayName("EE-26: the parent still gets a workspace skill's own directory, and nothing is copied for it")
    void workspaceResidentSkillIsNotCopiedForTheParent() throws Exception {
        parent.fileSystem().write("skills/demo/SKILL.md", "# demo");
        parent.fileSystem().write("skills/demo/scripts/x.sh", "echo workspace-script\n");
        final StagedResource resource = StagedResource.scan(parent.fileSystem(), "skills/demo", "demo");

        final String path = parent.stage(resource);

        assertThat(path).isEqualTo(workspace.toAbsolutePath().normalize() + "/skills/demo");
        assertThat(workspace.resolve(".aimon-staged")).doesNotExist();
        assertThat(read(parent, path + "/scripts/x.sh")).isEqualTo("echo workspace-script\n");
    }

    /**
     * EE-26, which bytes. The branch is given the parent's skill — the version the registry loaded while a copy of it
     * exists — never what the branch has since written under the same relative path in its own tree. A parent
     * directory that changed after the load, with no copy of the loaded version left, is staged like any other source
     * that no longer matches its content key: as it is now, under the key those bytes have (EE-3) — which is also
     * what the parent itself, handed the live directory, is reading.
     */
    @Test
    @DisplayName("EE-26: a branch gets the parent's workspace skill, whatever it wrote in its own tree; one changed"
            + " since the load is staged under its own key (EE-3)")
    void branchGetsTheLoadedVersionOfAWorkspaceSkill() throws Exception {
        parent.fileSystem().write("skills/demo/SKILL.md", "# demo");
        parent.fileSystem().write("skills/demo/scripts/x.sh", "echo loaded\n");
        final StagedResource resource = StagedResource.scan(parent.fileSystem(), "skills/demo", "demo");
        branch.fileSystem().write("skills/demo/scripts/x.sh", "echo branch-edit\n");

        final String path = branch.stage(resource);

        assertThat(read(branch, path + "/scripts/x.sh")).isEqualTo("echo loaded\n");
        assertThat(read(branch, "skills/demo/scripts/x.sh")).isEqualTo("echo branch-edit\n");

        parent.fileSystem().write("skills/demo/scripts/x.sh", "echo changed-after-load\n");
        final ExecutionEnvironment other = parent.isolate("other").orElseThrow();
        // Already staged under this key: the copy on disk still is the loaded version.
        assertThat(other.stage(resource)).isEqualTo(path);
        Files.delete(Path.of(path, LocalStaging.MARKER));

        final String changed = other.stage(resource);

        final String current = StagedResource.scan(parent.fileSystem(), "skills/demo", "demo").getContentKey();
        assertThat(changed).isNotEqualTo(path)
                .isEqualTo(workspace.toAbsolutePath().normalize() + "/.aimon-staged/demo/" + current);
        assertThat(read(other, changed + "/scripts/x.sh")).isEqualTo("echo changed-after-load\n");
        assertThat(other.shell().execute(() -> "sh " + changed + "/scripts/x.sh").stdout())
                .contains("changed-after-load");
        // The branch's own edit is still not what is staged, and both branches of this parent are told the same.
        assertThat(branch.stage(resource)).isEqualTo(changed);
        assertThat(read(branch, "skills/demo/scripts/x.sh")).isEqualTo("echo branch-edit\n");
        assertThatThrownBy(() -> other.fileSystem().write(changed + "/y", "tamper"))
                .isInstanceOf(FileAccessDeniedException.class);
    }

    @Test
    @DisplayName("EE-26: a workspace given as a filesystem instance behaves the same — the source is that instance")
    void workspaceResidentSkillOnABorrowedFileSystem(@TempDir Path borrowedRoot) throws Exception {
        final LocalFileSystem shared = new LocalFileSystem(new LocalFileSystemConfig(borrowedRoot.toString()));
        shared.initialize();
        try (shared;
                LocalExecutionEnvironmentProvider borrowed = LocalExecutionEnvironmentProvider.builder()
                        .fileSystem(shared).contentSearch(false).build()) {
            shared.write("skills/demo/SKILL.md", "# demo");
            shared.write("skills/demo/scripts/x.sh", "echo workspace-script\n");
            final StagedResource resource = StagedResource.scan(shared, "skills/demo", "demo");
            final ExecutionEnvironment borrowedParent = borrowed
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("iso")).build());
            final ExecutionEnvironment borrowedBranch = borrowedParent.isolate("k").orElseThrow();
            final String root = borrowedRoot.toAbsolutePath().normalize().toString();

            assertThat(borrowedParent.stage(resource)).isEqualTo(root + "/skills/demo");
            final String path = borrowedBranch.stage(resource);

            assertThat(path).isEqualTo(root + "/.aimon-staged/demo/" + resource.getContentKey());
            assertThat(read(borrowedBranch, path + "/scripts/x.sh")).isEqualTo("echo workspace-script\n");
            assertThat(borrowedBranch.shell().execute(() -> "sh " + path + "/scripts/x.sh").stdout())
                    .contains("workspace-script");
        }
    }

    /**
     * The parent's path rules apply at the branch root too: the branch's own {@code .aimon/} is refused at write time
     * under every spelling, so a merge never meets one (EE-8).
     */
    @Test
    @DisplayName("a branch's own .aimon/ is refused under every spelling, as the root's is")
    void branchLocalControlDirectoryIsDenied() {
        final String ws = workspace.toAbsolutePath().normalize().toString();
        for (final String path : List.of(".aimon/x", ".AIMON/x", "./a/../.aimon/x", ws + "/.worktrees/k/.aimon/x",
                ws + "/.aimon/x", ws + "/./.worktrees/k/.aimon/x", ws + "//.worktrees/k/.aimon/x",
                ws + "/.worktrees/K/.aimon/x")) {
            assertThatThrownBy(() -> branch.fileSystem().write(path, "branch-local")).as(path)
                    .isInstanceOf(FileAccessDeniedException.class);
        }

        assertThat(workspace.resolve(".worktrees/k/.aimon")).doesNotExist();
        assertThat(workspace.resolve(".worktrees/k/.worktrees")).doesNotExist();
        assertThat(branch.fileSystem().exists(".aimon")).isFalse();
    }

    @Test
    @DisplayName("a shell-written file under the branch's .aimon/ is not listed, so a merge leaves it and the root alone")
    void shellWrittenControlFileIsNeverPromoted() throws IOException {
        Files.createDirectories(workspace.resolve(".worktrees/k/.aimon"));
        Files.writeString(workspace.resolve(".worktrees/k/.aimon/y"), "shell-written");
        branch.fileSystem().write("result.txt", "done");

        assertThat(branch.fileSystem().listRecursive(".")).containsExactly("result.txt");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);

        assertThat(report.promoted()).containsExactly("result.txt");
        assertThat(workspace.resolve("result.txt")).hasContent("done");
        assertThat(workspace.resolve(".aimon/y")).doesNotExist();
        assertThat(workspace.resolve(".worktrees/k/.aimon/y")).hasContent("shell-written");
    }

    @Test
    @DisplayName("a staging directory a shell made in the branch is not listed, so a merge leaves it and staging alone")
    void shellWrittenStagingCopyIsNeverPromoted() throws IOException {
        Files.createDirectories(workspace.resolve(".worktrees/k/.aimon-staged"));
        Files.writeString(workspace.resolve(".worktrees/k/.aimon-staged/x"), "shell copy");
        Files.createDirectories(workspace.resolve(".worktrees/k/.Aimon-Staged"));
        Files.writeString(workspace.resolve(".worktrees/k/.Aimon-Staged/y"), "shell copy");
        branch.fileSystem().write("result.txt", "done");

        assertThat(branch.fileSystem().listRecursive(".")).containsExactly("result.txt");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);

        assertThat(report.promoted()).containsExactly("result.txt");
        assertThat(workspace.resolve("result.txt")).hasContent("done");
        assertThat(workspace.resolve(".aimon-staged/x")).doesNotExist();
        assertThat(workspace.resolve(".aimon-staged/y")).doesNotExist();
        assertThat(workspace.resolve(".worktrees/k/.aimon-staged/x")).hasContent("shell copy");
    }

    // ---- EE-46: a branch cannot address .worktrees/
    // ------------------------------------------------------------------

    /**
     * EE-46. A branch-relative {@code .worktrees/other/x} used to land in {@code .worktrees/k/.worktrees/other/x}, and
     * a merge promoted it to the root's {@code .worktrees/other/x} — branch {@code other}'s directory.
     */
    @Test
    @DisplayName("EE-46: a branch cannot write into another branch's directory, relative or absolute, in any case")
    void anotherBranchDirectoryIsRefused() throws IOException {
        final String ws = workspace.toAbsolutePath().normalize().toString();
        final ExecutionEnvironment other = parent.isolate("other").orElseThrow();
        other.fileSystem().write("mine.txt", "other's own");

        for (final String path : List.of(".worktrees/other/x", ws + "/.worktrees/other/x", ".WORKTREES/other/x",
                ws + "/.Worktrees/other/x", "./a/../.worktrees/other/x", ws + "/.worktrees/k/.worktrees/other/x",
                ".worktrees/x", ".worktrees")) {
            assertThatThrownBy(() -> branch.fileSystem().write(path, "injected")).as(path)
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("worktree");
        }
        branch.fileSystem().write("result.txt", "done");

        assertThat(workspace.resolve(".worktrees/k/.worktrees")).doesNotExist();
        assertThat(branch.fileSystem().listRecursive(".")).containsExactly("result.txt");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);
        assertThat(report.promoted()).containsExactly("result.txt");
        assertThat(other.fileSystem().listRecursive(".")).containsExactly("mine.txt");
        assertThat(workspace.resolve(".worktrees/x")).doesNotExist();
    }

    /**
     * EE-46. The target can be the branch's own directory: {@code .worktrees/k/x} written relative from branch
     * {@code k} — and {@code .worktrees/K/x} on a disk that folds case — was promoted to the root's
     * {@code .worktrees/k/x}, and under {@code .aimon/} to a place the branch's rules hide.
     */
    @Test
    @DisplayName("EE-46: a branch cannot name its own directory relative to itself, in either letter case")
    void ownBranchDirectoryNamedFromInsideIsRefused() throws IOException {
        for (final String path : List.of(".worktrees/k/x", ".worktrees/K/x", ".worktrees/k/.aimon/x",
                ".worktrees/K/.aimon/x")) {
            assertThatThrownBy(() -> branch.fileSystem().write(path, "nested")).as(path)
                    .isInstanceOf(InvalidPathException.class).hasMessageContaining("worktree");
        }
        branch.fileSystem().write("result.txt", "done");

        assertThat(workspace.resolve(".worktrees/k/.worktrees")).doesNotExist();
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);
        assertThat(report.promoted()).containsExactly("result.txt");
        assertThat(workspace.resolve(".worktrees/k/x")).doesNotExist();
        assertThat(workspace.resolve(".worktrees/k/.aimon")).doesNotExist();
    }

    @Test
    @DisplayName("EE-46: every operation that would create or read something under .worktrees/ is refused, not only"
            + " write")
    void everyOperationOnTheWorktreeRootIsRefused() {
        branch.fileSystem().write("a.txt", "a");
        final VirtualFileSystem fs = branch.fileSystem();

        assertThatThrownBy(() -> fs.createDirectory(".worktrees/other")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.move("a.txt", ".worktrees/other/a.txt", true))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.copy("a.txt", ".worktrees/other/a.txt", true))
                .isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.openOutputStream(".worktrees/other/y")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.read(".worktrees/other/y")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.listRecursive(".worktrees")).isInstanceOf(InvalidPathException.class);
        assertThatThrownBy(() -> fs.deleteRecursive(".worktrees/other")).isInstanceOf(InvalidPathException.class);

        assertThat(workspace.resolve(".worktrees/k/a.txt")).hasContent("a");
        assertThat(workspace.resolve(".worktrees/k/.worktrees")).doesNotExist();
    }

    @Test
    @DisplayName("EE-46: only the name at the branch root is reserved, and the branch's absolute root still works")
    void worktreeNameBelowTheBranchRootIsAnOrdinaryDirectory() throws IOException {
        final String ws = workspace.toAbsolutePath().normalize().toString();

        branch.fileSystem().write("docs/.worktrees/notes.md", "an ordinary directory");
        branch.fileSystem().write(ws + "/.worktrees/k/host.txt", "the path the shell prints");
        branch.fileSystem().write(ws + "/.worktrees/K/case.txt", "and its case variant");

        assertThat(branch.fileSystem().listRecursive(".")).containsExactlyInAnyOrder("docs/.worktrees/notes.md",
                "host.txt", "case.txt");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);
        assertThat(report.promoted()).containsExactlyInAnyOrder("docs/.worktrees/notes.md", "host.txt", "case.txt");
        assertThat(workspace.resolve("docs/.worktrees/notes.md")).hasContent("an ordinary directory");
    }

    @Test
    @DisplayName("EE-46: a .worktrees/ directory a shell made in the branch is not listed, so a merge leaves it and the"
            + " other branches alone")
    void shellWrittenWorktreeDirectoryIsNeverPromoted() throws IOException {
        final ExecutionEnvironment other = parent.isolate("other").orElseThrow();
        other.fileSystem().write("mine.txt", "other's own");
        Files.createDirectories(workspace.resolve(".worktrees/k/.worktrees/other"));
        Files.writeString(workspace.resolve(".worktrees/k/.worktrees/other/x"), "shell-written");
        Files.createDirectories(workspace.resolve(".worktrees/k/sub"));
        Files.writeString(workspace.resolve(".worktrees/k/sub/kept.txt"), "shell-written, ordinary");
        branch.fileSystem().write("result.txt", "done");

        assertThat(branch.fileSystem().listRecursive(".")).containsExactlyInAnyOrder("result.txt", "sub/kept.txt");
        assertThat(branch.fileSystem().list(".")).doesNotContain(".worktrees");
        final MergeReport report = WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL);

        assertThat(report.promoted()).containsExactlyInAnyOrder("result.txt", "sub/kept.txt");
        assertThat(other.fileSystem().listRecursive(".")).containsExactly("mine.txt");
        assertThat(workspace.resolve(".worktrees/k/.worktrees/other/x")).hasContent("shell-written");
    }

    @Test
    @DisplayName("EE-46: the reservation is the scope's, not a path rule — it holds with no path rules at all")
    void worktreeRootIsReservedWithoutPathRules(@TempDir Path unguarded) {
        try (LocalExecutionEnvironmentProvider open = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(unguarded).pathRules(List.of()).contentSearch(false).build()) {
            final ExecutionEnvironment openBranch = open
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("open")).build())
                    .isolate("k").orElseThrow();

            assertThatThrownBy(() -> openBranch.fileSystem().write(".worktrees/other/x", "injected"))
                    .isInstanceOf(InvalidPathException.class);
            assertThat(unguarded.resolve(".worktrees/k/.worktrees")).doesNotExist();
        }
    }

    @Test
    @DisplayName("the branch rules follow the parent's: with no path rules, the branch's .aimon/ is writable")
    void branchRulesFollowTheParent(@TempDir Path unguarded) throws IOException {
        try (LocalExecutionEnvironmentProvider open = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(unguarded).pathRules(List.of()).contentSearch(false).build()) {
            final ExecutionEnvironment openBranch = open
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("open")).build())
                    .isolate("k").orElseThrow();

            openBranch.fileSystem().write(".aimon/x", "branch-local");

            assertThat(read(openBranch, ".aimon/x")).isEqualTo("branch-local");
            assertThat(unguarded.resolve(".worktrees/k/.aimon/x")).hasContent("branch-local");
        }
    }

    @Test
    @DisplayName("a branch write to the staging area in another letter case is refused, not kept in the branch")
    void stagingInAnotherCaseIsReadOnly() {
        assertThatThrownBy(() -> branch.fileSystem().write(".AIMON-STAGED/x", "tamper"))
                .isInstanceOf(FileAccessDeniedException.class);

        assertThat(workspace.resolve(".worktrees/k/.AIMON-STAGED")).doesNotExist();
    }

    @Test
    @DisplayName("the branch cannot delete itself through its own filesystem; the parent can")
    void branchRootDeletedOnlyThroughTheParent() {
        branch.fileSystem().write("out.txt", "branch");

        assertThatThrownBy(() -> branch.fileSystem().deleteRecursive(".")).isInstanceOf(FileAccessDeniedException.class)
                .hasMessageContaining(".aimon");
        parent.fileSystem().deleteRecursive(".worktrees/k");

        assertThat(workspace.resolve(".worktrees/k")).doesNotExist();
    }

    @Test
    @DisplayName("a branch refuses to be isolated again, naming itself and the reason")
    void nestedIsolationIsRefused() {
        assertThatThrownBy(() -> branch.isolate("x")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("nested isolation is not supported").hasMessageContaining("'k'");
    }

    @Test
    @DisplayName("a branch declares the environment it was isolated from; the parent declares none")
    void branchDeclaresItsLineage() {
        assertThat(branch.isolatedFrom()).containsSame(parent);
        assertThat(parent.isolatedFrom()).isEmpty();
    }

    private static String read(ExecutionEnvironment env, String path) throws IOException {
        try (InputStream in = env.fileSystem().read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
