package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;

@DisplayName("LocalExecutionEnvironmentProvider")
class LocalExecutionEnvironmentProviderTest {

    @TempDir
    Path workspace;

    private static EnvironmentRequest request() {
        return EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:test")).build();
    }

    @Test
    @DisplayName("answers every request with the same environment")
    void sameEnvironment() {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final ExecutionEnvironment first = provider.resolve(request());
            assertThat(provider.resolve(request())).isSameAs(first);
            assertThat(first.durable()).isTrue();
        }
    }

    @Test
    @DisplayName("returns a request's parent environment as it is")
    void parentReturned() {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final ExecutionEnvironment parent = TestExecutionEnvironments.of(null);
            final EnvironmentRequest forked = EnvironmentRequest.builder()
                    .agentRuntimeId(AgentRuntimeId.of("agent:test")).parent(parent).build();
            assertThat(provider.resolve(forked)).isSameAs(parent);
        }
    }

    @Test
    @DisplayName("describes the host: platform, OS version and the workspace as working directory")
    void hostDescriptor() {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final EnvironmentDescriptor descriptor = provider.resolve(request()).descriptor();
            assertThat(descriptor.workingDirectory()).isEqualTo(workspace.toAbsolutePath().normalize().toString());
            assertThat(provider.workingDirectory()).isEqualTo(descriptor.workingDirectory());
            final String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
            final String expected = os.contains("mac") || os.contains("darwin")
                    ? "darwin"
                    : os.contains("win") ? "windows" : os.contains("nux") ? "linux" : os;
            assertThat(descriptor.platform()).hasValue(expected);
            assertThat(descriptor.osVersion()).hasValue(System.getProperty("os.version"));
            assertThat(descriptor.notes()).isEmpty();
        }
    }

    @Test
    @DisplayName("hides the control store and keeps the staging area read-only by default")
    void defaultPathRules() throws Exception {
        Files.createDirectories(workspace.resolve(".aimon/skills"));
        Files.writeString(workspace.resolve(".aimon/skills/secret.md"), "x");
        Files.createDirectories(workspace.resolve(".aimon-staged"));
        Files.writeString(workspace.resolve(".aimon-staged/copy.txt"), "staged");
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final VirtualFileSystem fs = provider.resolve(request()).fileSystem();
            assertThat(fs.exists(".aimon/skills/secret.md")).isFalse();
            assertThat(fs.list(".")).noneMatch(p -> p.startsWith(".aimon/") || p.equals(".aimon"));
            assertThatThrownBy(() -> fs.write(".aimon/skills/new.md", "y"))
                    .isInstanceOf(FileAccessDeniedException.class);
            assertThat(new String(fs.read(".aimon-staged/copy.txt").readAllBytes())).isEqualTo("staged");
            assertThatThrownBy(() -> fs.write(".aimon-staged/copy.txt", "tampered"))
                    .isInstanceOf(FileAccessDeniedException.class);
            fs.write("work.txt", "ok");
            assertThat(Files.readString(workspace.resolve("work.txt"))).isEqualTo("ok");
        }
    }

    @Test
    @DisplayName("an empty rule list exposes the whole workspace")
    void noRules() throws Exception {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).pathRules(java.util.List.of()).contentSearch(false).build()) {
            provider.resolve(request()).fileSystem().write(".aimon/skills/x.md", "y");
            assertThat(Files.readString(workspace.resolve(".aimon/skills/x.md"))).isEqualTo("y");
        }
    }

    @Test
    @DisplayName("closes the filesystem and shell it owns, and nothing it borrowed")
    void closesOwnedOnly() {
        final LocalFileSystem local = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        local.initialize();
        final AtomicInteger fsClosed = new AtomicInteger();
        final VirtualFileSystem borrowedFs = new DelegatingFileSystem(local) {
            @Override
            public void close() {
                fsClosed.incrementAndGet();
            }
        };
        final CountingShell borrowedShell = new CountingShell();
        final LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .fileSystem(borrowedFs).shell(borrowedShell).contentSearch(false).build();
        assertThat(provider.resolve(request()).shell()).isSameAs(borrowedShell);
        provider.close();
        assertThat(fsClosed).hasValue(0);
        assertThat(borrowedShell.closed).hasValue(0);
        local.close();
    }

    @Test
    @DisplayName("borrowed filesystem mode roots the owned shell at the filesystem's working directory")
    void borrowedFileSystemShellRoot() {
        final LocalFileSystem local = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        local.initialize();
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder().fileSystem(local)
                .contentSearch(false).build()) {
            assertThat(provider.resolve(request()).shell().getWorkingDirectory())
                    .isEqualTo(local.getWorkingDirectory());
        } finally {
            local.close();
        }
    }

    @Test
    @DisplayName("rejects both workspaceRoot and fileSystem, and neither")
    void exactlyOneWorkspace() {
        final LocalFileSystem local = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        assertThatThrownBy(
                () -> LocalExecutionEnvironmentProvider.builder().workspaceRoot(workspace).fileSystem(local).build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LocalExecutionEnvironmentProvider.builder().build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("contentSearch(false) offers no content search")
    void noContentSearch() {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            assertThat(provider.resolve(request()).contentSearch()).isEmpty();
        }
    }

    @Test
    @DisplayName("isolate rejects a key outside [A-Za-z0-9_]+")
    void isolateRejectsBadKey() {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(workspace).contentSearch(false).build()) {
            final ExecutionEnvironment env = provider.resolve(request());
            assertThatThrownBy(() -> env.isolate("../x")).isInstanceOf(IllegalArgumentException.class);
            assertThat(env.isolate("step_1")).isPresent();
        }
    }

    @Test
    @DisplayName("an owned filesystem is closed with the provider; a borrowed one is not")
    void ownedFileSystemIsClosed(@TempDir Path ownedRoot, @TempDir Path borrowedRoot) {
        final LocalFileSystem owned = new LocalFileSystem(new LocalFileSystemConfig(ownedRoot.toString()));
        owned.initialize();
        final LocalFileSystem borrowed = new LocalFileSystem(new LocalFileSystemConfig(borrowedRoot.toString()));
        borrowed.initialize();
        try {
            final LocalExecutionEnvironmentProvider owning = LocalExecutionEnvironmentProvider.builder()
                    .ownedFileSystem(owned).contentSearch(false).build();
            final LocalExecutionEnvironmentProvider borrowing = LocalExecutionEnvironmentProvider.builder()
                    .fileSystem(borrowed).contentSearch(false).build();
            // The unguarded view is the filesystem itself — what an assembly puts its control store on.
            assertThat(owning.rawFileSystem()).isSameAs(owned);
            assertThat(owning.fileSystem()).isNotSameAs(owned);

            owning.close();
            borrowing.close();

            assertThat(owned.getStatus().isAvailable()).isFalse();
            assertThat(borrowed.getStatus().isAvailable()).isTrue();
        } finally {
            borrowed.close();
        }
    }

    @Test
    @DisplayName("the background command ceiling is the environment's, unset by default, and must be positive")
    void backgroundCommandTimeout(@TempDir Path root) {
        final EnvironmentRequest request = EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("a"))
                .build();
        try (LocalExecutionEnvironmentProvider unset = LocalExecutionEnvironmentProvider.builder().workspaceRoot(root)
                .contentSearch(false).build()) {
            assertThat(unset.resolve(request).backgroundCommandTimeout()).isEmpty();
        }
        try (LocalExecutionEnvironmentProvider limited = LocalExecutionEnvironmentProvider.builder().workspaceRoot(root)
                .contentSearch(false).backgroundCommandTimeout(Duration.ofMinutes(45)).build()) {
            assertThat(limited.resolve(request).backgroundCommandTimeout()).contains(Duration.ofMinutes(45));
        }
        assertThatThrownBy(() -> LocalExecutionEnvironmentProvider.builder().backgroundCommandTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(
                () -> LocalExecutionEnvironmentProvider.builder().backgroundCommandTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class CountingShell implements VirtualShell {
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public ShellCommandResult execute(ShellCommand command) {
            return new ShellCommandResult(0, "", "", java.time.Duration.ZERO);
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) {
            return execute(command);
        }

        @Override
        public String getWorkingDirectory() {
            return null;
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
