package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;

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
