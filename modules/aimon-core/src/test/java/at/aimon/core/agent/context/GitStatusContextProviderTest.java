package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

@DisplayName("GitStatusContextProvider Tests")
class GitStatusContextProviderTest {

    @TempDir
    Path tempDir;

    private final GitStatusContextProvider provider = new GitStatusContextProvider();

    private LocalFileSystem fileSystem() {
        LocalFileSystem fs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fs.initialize();
        return fs;
    }

    private ContextAssemblyRequest requestWith(LocalFileSystem fs) {
        return ContextAssemblyRequest.builder().fileSystem(fs).build();
    }

    @Test
    @DisplayName("reports the branch from a symbolic-ref HEAD")
    void reportsBranch() {
        LocalFileSystem fs = fileSystem();
        fs.write(".git/HEAD", "ref: refs/heads/feature/orca\n");

        List<ContextBlock> blocks = provider.provide(requestWith(fs));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getKind()).isEqualTo(ContextBlockKind.SYSTEM);
        assertThat(blocks.get(0).getKey()).isEqualTo(GitStatusContextProvider.BLOCK_KEY);
        assertThat(blocks.get(0).getBody()).isEqualTo("Current git branch: feature/orca");
    }

    @Test
    @DisplayName("reports a short id in detached-HEAD state")
    void reportsDetachedHead() {
        LocalFileSystem fs = fileSystem();
        fs.write(".git/HEAD", "0123456789abcdef0123456789abcdef01234567\n");

        List<ContextBlock> blocks = provider.provide(requestWith(fs));

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getBody()).isEqualTo("Current git branch: 0123456789ab (detached)");
    }

    @Test
    @DisplayName("emits nothing when .git/HEAD is absent")
    void emptyWhenNoGit() {
        assertThat(provider.provide(requestWith(fileSystem()))).isEmpty();
    }

    @Test
    @DisplayName("emits nothing when no filesystem is bound")
    void emptyWhenNoFileSystem() {
        assertThat(provider.provide(ContextAssemblyRequest.builder().build())).isEmpty();
    }
}
