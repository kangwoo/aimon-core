package at.aimon.core.tools.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.ArtifactStorage;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.PathValidator;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ArtifactArchive")
class ArtifactArchiveTest {

    @TempDir
    Path base;

    @Test
    @DisplayName("the keys the executors mint pass the local store's path validation")
    void mintedKeysAreValidLocalPaths() {
        final PathValidator validator = new PathValidator(base.toString());
        assertThat(validator.isValid("artifacts/" + new ArtifactCollector().getArchiveKey() + "/report.csv"))
                .as("the raw key is what used to fail").isFalse();
        for (String key : new String[]{new ArtifactCollector().getArchiveKey(),
                "subagent:worker:3f1c2b1e-0000-4000-8000-000000000000"}) {
            final String name = ArtifactArchive.directoryName(key);
            assertThat(name).doesNotContain(":").doesNotContain("/");
            assertThat(validator.isValid("artifacts/" + name + "/report.csv")).as(key).isTrue();
        }
    }

    @Test
    @DisplayName("safe characters are kept, everything else becomes '_'")
    void replacesUnsafeCharacters() {
        assertThat(ArtifactArchive.directoryName("exec-1")).isEqualTo("exec-1");
        assertThat(ArtifactArchive.directoryName("archive:abc")).isEqualTo("archive_abc");
        assertThat(ArtifactArchive.directoryName("a/b\\c*d")).isEqualTo("a_b_c_d");
    }

    @Test
    @DisplayName("never empty, '.' or '..'")
    void neverASpecialSegment() {
        assertThat(ArtifactArchive.directoryName("")).isEqualTo("_");
        assertThat(ArtifactArchive.directoryName(".")).isEqualTo("_.");
        assertThat(ArtifactArchive.directoryName("..")).isEqualTo("_..");
    }

    @Nested
    @DisplayName("register — archiving from a non-durable environment (EE-19)")
    class Register {

        private Path workspace;
        private VirtualFileSystem fileSystem;
        private VirtualFileSystem controlFileSystem;

        @BeforeEach
        void setUp() throws IOException {
            workspace = Files.createDirectories(base.resolve("workspace"));
            fileSystem = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
            fileSystem.initialize();
            controlFileSystem = new LocalFileSystem(new LocalFileSystemConfig(base.resolve("control").toString()));
            controlFileSystem.initialize();
        }

        @AfterEach
        void tearDown() {
            fileSystem.close();
            controlFileSystem.close();
        }

        private ToolContext nonDurable(VirtualFileSystem workspaceFileSystem, ArtifactCollector collector) {
            return ToolContext.builder()
                    .put(ToolContextKeys.EXECUTION_ENVIRONMENT,
                            TestExecutionEnvironments.builder().fileSystem(workspaceFileSystem).durable(false).build())
                    .put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
        }

        private String write(String relativePath, String content) throws IOException {
            final Path file = workspace.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return file.toString();
        }

        @Test
        @DisplayName("two files with one name in different directories keep separate archive copies")
        void sameNameInDifferentDirectoriesDoesNotCollide() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.enabledWithDefaults());
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final ToolContext context = nonDurable(fileSystem, collector);

            assertThat(archive.register(context, write("a/report.md", "from a"), -1)).isEmpty();
            // The second is registered by its workspace-relative path, as the model may well spell it.
            write("b/report.md", "from b!");
            assertThat(archive.register(context, "b/report.md", -1)).isEmpty();

            assertThat(collector.getArtifacts()).extracting(FileArtifact::getPath)
                    .containsExactly("artifacts/exec-1/a/report.md", "artifacts/exec-1/b/report.md");
            assertThat(collector.getArtifacts()).extracting(FileArtifact::getFileName).containsExactly("report.md",
                    "report.md");
            assertThat(Files.readString(base.resolve("control/artifacts/exec-1/a/report.md"))).isEqualTo("from a");
            assertThat(Files.readString(base.resolve("control/artifacts/exec-1/b/report.md"))).isEqualTo("from b!");
        }

        @Test
        @DisplayName("a file registered again (every Edit does) is counted against the execution limit once")
        void reRegistrationIsCountedOnce() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).maxExecutionBytes(10).build());
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final ToolContext context = nonDurable(fileSystem, collector);
            final String report = write("report.md", "123456");

            assertThat(archive.register(context, report, -1)).isEmpty();
            Files.writeString(Path.of(report), "1234567");
            assertThat(archive.register(context, report, -1)).as("the same file, edited: 7 bytes of a 10-byte limit")
                    .isEmpty();

            assertThat(collector.totalBytes(ArtifactStorage.CONTROL)).isEqualTo(7);
            assertThat(Files.readString(base.resolve("control/artifacts/exec-1/report.md"))).isEqualTo("1234567");
            // Another file still has to fit beside it: 7 + 4 > 10.
            assertThat(archive.register(context, write("other.md", "abcd"), -1)).get().asString()
                    .contains("execution's limit");
        }

        @Test
        @DisplayName("EE-79: a re-registration whose copy fails leaves the earlier archived copy as it was")
        void failedReRegistrationKeepsTheEarlierCopy() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).build());
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final String report = write("report.md", "first version");
            assertThat(archive.register(nonDurable(fileSystem, collector), report, -1)).isEmpty();
            Files.writeString(Path.of(report), "second version, longer");
            // The source breaks off part-way through the copy.
            final VirtualFileSystem breaking = new DelegatingFileSystem(fileSystem) {
                @Override
                public java.io.InputStream openInputStream(String path) {
                    return new java.io.InputStream() {
                        private int served;

                        @Override
                        public int read() throws IOException {
                            if (served++ < 5) {
                                return 'x';
                            }
                            throw new IOException("source went away");
                        }
                    };
                }
            };

            final Optional<String> note = archive.register(nonDurable(breaking, collector), report, -1);

            assertThat(note).get().asString().startsWith("[artifact not registered:").contains("copying it");
            // The first registration is still in the collector, so what it points at must still be what it archived.
            final Path archived = base.resolve("control/artifacts/exec-1/report.md");
            assertThat(archived).hasContent("first version");
            try (java.util.stream.Stream<Path> beside = Files.list(archived.getParent())) {
                assertThat(beside.map(p -> p.getFileName().toString())).as("no partial copy left beside it")
                        .containsExactly("report.md");
            }
        }

        @Test
        @DisplayName("a size that cannot be read is not taken for 0: nothing is archived and a note says why")
        void unknownSizeIsNotArchived() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).maxFileBytes(2).build());
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final String report = write("report.md", "far more than two bytes");

            final Optional<String> note = archive.register(nonDurable(new NoMetadataFileSystem(fileSystem), collector),
                    report, -1);

            assertThat(note).get().asString().startsWith("[artifact not registered:").contains("size");
            assertThat(collector.getArtifacts()).isEmpty();
            assertThat(controlFileSystem.exists("artifacts/exec-1/report.md")).isFalse();
        }

        @Test
        @DisplayName("the caller's fallback size stands in for unreadable metadata and is held to the limits")
        void fallbackSizeIsHeldToTheLimits() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).maxFileBytes(2).build());
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final VirtualFileSystem noMetadata = new NoMetadataFileSystem(fileSystem);

            assertThat(archive.register(nonDurable(noMetadata, collector), write("big.md", "abc"), 3)).get().asString()
                    .contains("per-file");
            assertThat(archive.register(nonDurable(noMetadata, collector), write("empty.md", ""), 0)).isEmpty();
            assertThat(collector.getArtifacts()).extracting(FileArtifact::getPath)
                    .containsExactly("artifacts/exec-1/empty.md");
        }

        @Test
        @DisplayName("an unexpected failure while registering is not silent: a note says the file was not registered")
        void unexpectedFailureAddsNote() throws IOException {
            final ArtifactArchive archive = new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.enabledWithDefaults());
            final ArtifactCollector collector = new ArtifactCollector("exec-1") {
                @Override
                public void add(FileArtifact artifact) {
                    throw new IllegalStateException("collector is closed");
                }
            };

            final Optional<String> note = archive.register(nonDurable(fileSystem, collector),
                    write("report.md", "data"), -1);

            assertThat(note).get().asString().startsWith("[artifact not registered:").contains("collector is closed");
        }
    }

    /** A workspace whose metadata cannot be read, as a backend that fails between the write and the registration. */
    private static final class NoMetadataFileSystem extends DelegatingFileSystem {

        NoMetadataFileSystem(VirtualFileSystem delegate) {
            super(delegate);
        }

        @Override
        public FileMetadata getMetadata(String path) {
            throw new IllegalStateException("metadata unavailable");
        }
    }
}
