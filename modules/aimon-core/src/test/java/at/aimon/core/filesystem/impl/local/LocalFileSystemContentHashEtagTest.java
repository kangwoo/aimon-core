package at.aimon.core.filesystem.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;

/**
 * The local filesystem's optional content-hash etag (EE-5). Without it a read stamp is size and modification time, and
 * a rewrite that keeps both — same size, same second on a filesystem that counts in seconds — is invisible. The tests
 * put the old modification time back by hand, which is that case on any disk.
 */
@DisplayName("LocalFileSystem content-hash etag — off by default, a hash of the bytes when enabled (EE-5)")
class LocalFileSystemContentHashEtagTest {

    @TempDir
    Path root;

    private LocalFileSystem open(boolean contentHashEtag) {
        final LocalFileSystem fs = new LocalFileSystem(
                LocalFileSystemConfig.builder(root.toString()).contentHashEtag(contentHashEtag).build());
        fs.initialize();
        return fs;
    }

    /** Rewrites a file with other bytes of the same size and gives it back the modification time it had. */
    private void rewriteWithinTheSameTick(LocalFileSystem fs, String path, String content) throws Exception {
        final FileTime before = Files.getLastModifiedTime(root.resolve(path));
        fs.write(path, content);
        Files.setLastModifiedTime(root.resolve(path), before);
    }

    @Test
    @DisplayName("by default there is no etag, and a same-size rewrite within one mtime tick is not seen")
    void defaultHasNoEtagAndMissesTheRewrite() throws Exception {
        try (LocalFileSystem fs = open(false)) {
            fs.write("a.txt", "aaaa");
            final FileStamp read = FileStamp.of(fs.getMetadata("a.txt"));

            rewriteWithinTheSameTick(fs, "a.txt", "bbbb");

            assertThat(fs.getMetadata("a.txt").getEtag()).isEmpty();
            assertThat(new LocalFileSystemConfig(root.toString()).isContentHashEtag()).isFalse();
            // The gap the option closes, pinned so that it stays a known limit of the default.
            assertThat(FileStamp.of(fs.getMetadata("a.txt"))).isEqualTo(read);
        }
    }

    @Test
    @DisplayName("enabled, a same-size rewrite within one mtime tick changes the stamp")
    void enabledSeesTheRewrite() throws Exception {
        try (LocalFileSystem fs = open(true)) {
            fs.write("a.txt", "aaaa");
            final FileStamp read = FileStamp.of(fs.getMetadata("a.txt"));

            rewriteWithinTheSameTick(fs, "a.txt", "bbbb");

            assertThat(FileStamp.of(fs.getMetadata("a.txt"))).isNotEqualTo(read);
        }
    }

    @Test
    @DisplayName("enabled, the etag is the SHA-256 of the bytes, and a rewrite of the same bytes keeps the stamp")
    void enabledHashesTheBytes() throws Exception {
        try (LocalFileSystem fs = open(true)) {
            fs.write("empty.txt", "");
            fs.write("a.txt", "same bytes");
            final FileStamp read = FileStamp.of(fs.getMetadata("a.txt"));
            Files.setLastModifiedTime(root.resolve("a.txt"), FileTime.fromMillis(1_000_000_000_000L));

            assertThat(fs.getMetadata("empty.txt").getEtag())
                    .hasValue("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            assertThat(FileStamp.of(fs.getMetadata("a.txt"))).as("touched, not changed").isEqualTo(read);
        }
    }

    @Test
    @DisplayName("enabled, a file larger than the I/O buffer is hashed whole, and a directory has no etag")
    void enabledHashesLargeFilesAndSkipsDirectories() {
        try (LocalFileSystem fs = open(true)) {
            final String head = "x".repeat(3 * LocalFileSystemConfig.DEFAULT_BUFFER_SIZE);
            fs.write("dir/big.txt", head + "1");
            final String first = fs.getMetadata("dir/big.txt").getEtag().orElseThrow();
            fs.write("dir/big.txt", head + "2");

            assertThat(fs.getMetadata("dir/big.txt").getEtag().orElseThrow()).isNotEqualTo(first);
            assertThat(fs.getMetadata("dir").getEtag()).isEmpty();
        }
    }

    @Test
    @DisplayName("the local provider passes the option to the workspace it builds, and a branch sees the same etags")
    void providerOption() throws Exception {
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(root).contentSearch(false).contentHashStamps(true).build()) {
            final ExecutionEnvironment env = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("hash")).build());
            env.fileSystem().write("a.txt", "aaaa");
            final FileStamp read = FileStamp.of(env.fileSystem().getMetadata("a.txt"));
            final FileTime before = Files.getLastModifiedTime(root.resolve("a.txt"));
            env.fileSystem().write("a.txt", "bbbb");
            Files.setLastModifiedTime(root.resolve("a.txt"), before);

            assertThat(read.getEtag()).isPresent();
            assertThat(FileStamp.of(env.fileSystem().getMetadata("a.txt"))).isNotEqualTo(read);

            final ExecutionEnvironment branch = env.isolate("k").orElseThrow();
            branch.fileSystem().write("b.txt", "branch");
            assertThat(branch.fileSystem().getMetadata("b.txt").getEtag().orElseThrow()).startsWith("sha256:");
        }
        try (LocalExecutionEnvironmentProvider provider = LocalExecutionEnvironmentProvider.builder()
                .workspaceRoot(root).contentSearch(false).build()) {
            final ExecutionEnvironment env = provider
                    .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.fromName("hash")).build());

            assertThat(env.fileSystem().getMetadata("a.txt").getEtag()).as("off unless asked for").isEmpty();
        }
    }
}
