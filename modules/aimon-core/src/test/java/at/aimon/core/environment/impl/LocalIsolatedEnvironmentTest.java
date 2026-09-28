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
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
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
     * Path rules are root-anchored: a branch's own {@code .aimon/} is ordinary branch content. Promoting it would
     * write the control store, which the parent's DENY rule refuses — a branch cannot reach the control store through a
     * merge.
     */
    @Test
    @DisplayName("a branch-local .aimon/ is not denied, but promoting it into the root .aimon/ is")
    void branchLocalControlDirectoryIsNotDenied() throws IOException {
        branch.fileSystem().write(".aimon/x", "branch-local");

        assertThat(read(branch, ".aimon/x")).isEqualTo("branch-local");
        assertThat(branch.fileSystem().listRecursive(".")).containsExactly(".aimon/x");
        assertThat(read(parent, ".worktrees/k/.aimon/x")).isEqualTo("branch-local");

        Files.createDirectories(workspace.resolve(".aimon"));
        Files.writeString(workspace.resolve(".aimon/x"), "control");
        assertThat(parent.fileSystem().exists(".aimon/x")).isFalse();
        assertThatThrownBy(() -> read(parent, ".aimon/x")).isInstanceOf(FileAccessDeniedException.class);
        // From the branch the root .aimon/ is out of reach altogether: a canonical absolute path maps into the branch.
        assertThat(read(branch, workspace.toAbsolutePath().normalize() + "/.aimon/x")).isEqualTo("branch-local");

        assertThatThrownBy(() -> WorktreeMerge.promote(parent, List.of(branch), WorktreeMerge.Policy.FAIL))
                .isInstanceOf(VirtualFileSystemException.class).hasMessageContaining(".aimon/x")
                .hasRootCauseInstanceOf(FileAccessDeniedException.class);
        assertThat(workspace.resolve(".aimon/x")).hasContent("control");

        branch.fileSystem().delete(".aimon/x");
        assertThat(workspace.resolve(".worktrees/k/.aimon/x")).doesNotExist();
    }

    private static String read(ExecutionEnvironment env, String path) throws IOException {
        try (InputStream in = env.fileSystem().read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
