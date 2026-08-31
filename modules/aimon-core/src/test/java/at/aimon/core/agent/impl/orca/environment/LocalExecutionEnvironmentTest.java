package at.aimon.core.agent.impl.orca.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.VirtualShell;

class LocalExecutionEnvironmentTest {

    @Test
    void exposesLocalFileSystemAndShell(@TempDir Path tempDir) {
        LocalFileSystemConfig config = LocalFileSystemConfig.builder(tempDir.toString()).build();

        LocalExecutionEnvironment env = new LocalExecutionEnvironment(config);

        VirtualFileSystem fs = env.fileSystem();
        VirtualShell shell = env.shell();

        assertThat(fs).isNotNull();
        assertThat(shell).isNotNull();
    }

    @Test
    void fileSystemAndShellAreStableAcrossCalls(@TempDir Path tempDir) {
        LocalFileSystemConfig config = LocalFileSystemConfig.builder(tempDir.toString()).build();
        LocalExecutionEnvironment env = new LocalExecutionEnvironment(config);

        assertThat(env.fileSystem()).isSameAs(env.fileSystem());
        assertThat(env.shell()).isSameAs(env.shell());
    }

    @Test
    void implementsVirtualExecutionEnvironment(@TempDir Path tempDir) {
        LocalFileSystemConfig config = LocalFileSystemConfig.builder(tempDir.toString()).build();

        VirtualExecutionEnvironment env = new LocalExecutionEnvironment(config);

        assertThat(env.fileSystem()).isNotNull();
        assertThat(env.shell()).isNotNull();
    }
}
