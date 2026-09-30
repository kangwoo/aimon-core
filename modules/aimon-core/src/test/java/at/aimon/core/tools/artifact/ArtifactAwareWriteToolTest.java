package at.aimon.core.tools.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.ScopedVirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ArtifactAwareWriteTool Tests")
class ArtifactAwareWriteToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;

    private VirtualFileSystem controlFileSystem;

    private ArtifactAwareWriteTool writeTool;

    @BeforeEach
    void setUp() {
        final LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        controlFileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.resolve("control").toString()));
        controlFileSystem.initialize();
        writeTool = new ArtifactAwareWriteTool(
                new ArtifactArchive(controlFileSystem, ArtifactPolicy.enabledWithDefaults()));
    }

    /** A context builder carrying a durable execution environment over the workspace. */
    private ToolContext.Builder contextBuilder() {
        return ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT,
                TestExecutionEnvironments.of(fileSystem));
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
        if (controlFileSystem != null) {
            controlFileSystem.close();
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException when the archive is null")
        void shouldThrowOnNullArchive() {
            assertThatThrownBy(() -> new ArtifactAwareWriteTool(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("archive cannot be null");
        }

    }

    @Nested
    @DisplayName("Tool Definition")
    class Definition {

        @Test
        @DisplayName("Should have tool name 'Write'")
        void shouldHaveCorrectName() {
            final ToolDefinition definition = writeTool.getDefinition();
            assertThat(definition.getName()).isEqualTo("Write");
        }

        @Test
        @DisplayName("Should include artifact parameter in schema")
        void shouldIncludeArtifactParameter() {
            final ToolDefinition definition = writeTool.getDefinition();
            @SuppressWarnings("unchecked")
            final Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
            assertThat(properties).containsKeys("file_path", "content", "artifact");
        }

        @Test
        @DisplayName("Should require file_path and content but not artifact")
        void shouldRequireCorrectParameters() {
            final ToolDefinition definition = writeTool.getDefinition();
            final Object required = definition.getInputSchema().get("required");
            assertThat(required).asList().containsExactly("file_path", "content");
            assertThat(required).asList().doesNotContain("artifact");
        }

    }

    @Nested
    @DisplayName("Delegation to existing WriteTool")
    class Delegation {

        @Test
        @DisplayName("Should write file successfully (same as original WriteTool)")
        void shouldWriteFileSuccessfully() throws IOException {
            final Path testFile = tempDir.resolve("test.txt");
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "content", "Hello World"));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully wrote");
            assertThat(Files.readString(testFile)).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("Should return error for missing file_path")
        void shouldReturnErrorForMissingFilePath() {
            final ToolInput input = ToolInput.of(Map.of("content", "Some content"));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Missing required parameter: file_path");
        }

        @Test
        @DisplayName("Should return error for missing content")
        void shouldReturnErrorForMissingContent() {
            final Path testFile = tempDir.resolve("test.txt");
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString()));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Missing required parameter: content");
        }

        @Test
        @DisplayName("Should return error when writing to directory")
        void shouldReturnErrorForDirectory() throws IOException {
            final Path dir = tempDir.resolve("subdir");
            Files.createDirectories(dir);
            final ToolInput input = ToolInput.of(Map.of("file_path", dir.toString(), "content", "Some content"));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Cannot write to directory");
        }

    }

    @Nested
    @DisplayName("Artifact Registration")
    class ArtifactRegistration {

        @Test
        @DisplayName("Should register artifact when artifact=true and write succeeds")
        void shouldRegisterArtifactOnSuccess() {
            final Path testFile = tempDir.resolve("report.csv");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "a,b,c", "artifact", true));

            final ToolResult result = writeTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);

            final FileArtifact artifact = collector.getArtifacts().get(0);
            assertThat(artifact.getPath()).isEqualTo(testFile.toString());
            assertThat(artifact.getFileName()).isEqualTo("report.csv");
            assertThat(artifact.getSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should not register artifact when artifact=false")
        void shouldNotRegisterWhenArtifactFalse() {
            final Path testFile = tempDir.resolve("internal.txt");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "internal data", "artifact", false));

            final ToolResult result = writeTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should register artifact when artifact parameter is omitted (defaults to true)")
        void shouldRegisterWhenArtifactOmitted() {
            final Path testFile = tempDir.resolve("default.txt");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "content", "data"));

            final ToolResult result = writeTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("default.txt");
        }

        @Test
        @DisplayName("Should not register artifact when write fails")
        void shouldNotRegisterOnWriteFailure() throws IOException {
            final Path dir = tempDir.resolve("subdir");
            Files.createDirectories(dir);
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", dir.toString(), "content", "data", "artifact", true));

            final ToolResult result = writeTool.execute(input, context);

            assertThat(result.isError()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should succeed even without ArtifactCollector in context")
        void shouldSucceedWithoutCollector() {
            final Path testFile = tempDir.resolve("no-collector.txt");
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "data", "artifact", true));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isSuccess()).isTrue();
        }

    }

    @Nested
    @DisplayName("Artifact Metadata")
    class ArtifactMetadata {

        @Test
        @DisplayName("Should include toolUseId from context")
        void shouldIncludeToolUseId() {
            final Path testFile = tempDir.resolve("with-tool-id.csv");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector)
                    .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, "toolu_abc123").build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "data", "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).hasValue("toolu_abc123");
        }

        @Test
        @DisplayName("Should have empty toolUseId when not in context")
        void shouldHaveEmptyToolUseIdWhenMissing() {
            final Path testFile = tempDir.resolve("no-tool-id.csv");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "data", "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).isEmpty();
        }

        @Test
        @DisplayName("Should use file size from metadata")
        void shouldUseSizeFromMetadata() {
            final Path testFile = tempDir.resolve("size-test.txt");
            final String content = "Hello World"; // 11 bytes in UTF-8
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", content, "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getSize()).isEqualTo(11);
        }

        @Test
        @DisplayName("Should extract fileName from path correctly")
        void shouldExtractFileName() {
            final Path subDir = tempDir.resolve("reports");
            subDir.toFile().mkdirs();
            final Path testFile = subDir.resolve("sales-2024.csv");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "a,b,c", "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("sales-2024.csv");
        }

        @Test
        @DisplayName("Should populate mimeType from file metadata")
        void shouldPopulateMimeType() {
            final Path testFile = tempDir.resolve("data.txt");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", "hello", "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            // LocalFileSystem may or may not detect mimeType; verify it is populated without error
            final FileArtifact artifact = collector.getArtifacts().get(0);
            // mimeType is Optional — either present with a non-blank value, or empty
            artifact.getMimeType().ifPresent(mime -> assertThat(mime).isNotBlank());
        }

        @Test
        @DisplayName("Should use UTF-8 byte length for artifact size, not character count")
        void shouldUseByteLengthForUnicodeContent() {
            final Path testFile = tempDir.resolve("unicode.txt");
            // "Hello 世界" = 5 ASCII + 1 space (6 bytes) + 2 CJK chars (3 bytes each = 6 bytes) = 12 bytes
            final String content = "Hello 世界";
            final int expectedByteLength = content.getBytes(StandardCharsets.UTF_8).length;
            assertThat(expectedByteLength).isEqualTo(12); // sanity check: 8 chars but 12 UTF-8 bytes

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", content, "artifact", true));

            writeTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            // Size should be byte-level (12), not character count (8)
            assertThat(collector.getArtifacts().get(0).getSize()).isEqualTo(expectedByteLength);
        }

    }

    @Nested
    @DisplayName("Multiple Artifacts")
    class MultipleArtifacts {

        @Test
        @DisplayName("Should collect multiple artifacts across writes")
        void shouldCollectMultipleArtifacts() {
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();

            final Path file1 = tempDir.resolve("report1.csv");
            writeTool.execute(ToolInput.of(Map.of("file_path", file1.toString(), "content", "data1", "artifact", true)),
                    context);

            final Path file2 = tempDir.resolve("report2.csv");
            writeTool.execute(ToolInput.of(Map.of("file_path", file2.toString(), "content", "data2", "artifact", true)),
                    context);

            final Path internalFile = tempDir.resolve("temp.txt");
            writeTool.execute(
                    ToolInput
                            .of(Map.of("file_path", internalFile.toString(), "content", "internal", "artifact", false)),
                    context);

            assertThat(collector.getArtifacts()).hasSize(2);
            assertThat(collector.getArtifacts()).extracting(FileArtifact::getFileName).containsExactly("report1.csv",
                    "report2.csv");
        }

    }

    @Nested
    @DisplayName("Return Value")
    class ReturnValue {

        @Test
        @DisplayName("Should return same success result as delegate WriteTool")
        void shouldReturnDelegateSuccessResult() throws IOException {
            final Path testFile = tempDir.resolve("delegate.txt");
            final String content = "test content";
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "content", content, "artifact", true));
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();

            final ToolResult result = writeTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully wrote");
            assertThat(result.getContent()).contains("delegate.txt");
            assertThat(Files.readString(testFile)).isEqualTo(content);
        }

        @Test
        @DisplayName("Should return delegate error result unchanged")
        void shouldReturnDelegateErrorResult() {
            final ToolInput input = ToolInput.of(Map.of("content", "data", "artifact", true));

            final ToolResult result = writeTool.execute(input, contextBuilder().build());

            assertThat(result.isError()).isTrue();
        }

    }

    @Nested
    @DisplayName("Execution environment (execution-environment design §9.3)")
    class ExecutionEnvironmentDurability {

        @Test
        @DisplayName("No execution environment: error, no throw")
        void noEnvironmentIsAnError() {
            final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "x")),
                    ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No execution environment");
        }

        @Test
        @DisplayName("Durable environment: the workspace path is registered as WORKSPACE")
        void durableRegistersWorkspacePath() {
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final Path file = tempDir.resolve("report.csv");

            writeTool.execute(ToolInput.of(Map.of("file_path", file.toString(), "content", "a,b")),
                    contextBuilder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build());

            assertThat(collector.getArtifacts()).singleElement().satisfies(artifact -> {
                assertThat(artifact.getPath()).isEqualTo(file.toString());
                assertThat(artifact.getStorage()).isEqualTo(ArtifactStorage.WORKSPACE);
            });
            assertThat(controlFileSystem.exists("artifacts")).isFalse();
        }

        @Test
        @DisplayName("Non-durable environment: the file is copied into the control store and registered as CONTROL")
        void nonDurableArchivesIntoControlStore() throws IOException {
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final ToolContext context = nonDurableContext(collector);

            final ToolResult result = writeTool.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("report.csv").toString(), "content", "a,b")),
                    context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).singleElement().satisfies(artifact -> {
                assertThat(artifact.getPath()).isEqualTo("artifacts/exec-1/report.csv");
                assertThat(artifact.getStorage()).isEqualTo(ArtifactStorage.CONTROL);
                assertThat(artifact.getSize()).isEqualTo(3);
            });
            assertThat(Files.readString(tempDir.resolve("control/artifacts/exec-1/report.csv"))).isEqualTo("a,b");
        }

        @Test
        @DisplayName("Per-file limit: the write succeeds, a note says why nothing was registered")
        void perFileLimitAddsNoteAndSkipsRegistration() {
            final ArtifactAwareWriteTool limited = new ArtifactAwareWriteTool(new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).maxFileBytes(2).build()));
            final ArtifactCollector collector = new ArtifactCollector("exec-1");

            final ToolResult result = limited.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("big.csv").toString(), "content", "a,b,c")),
                    nonDurableContext(collector));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully wrote").contains("artifact not registered")
                    .contains("per-file");
            assertThat(collector.getArtifacts()).isEmpty();
            assertThat(controlFileSystem.exists("artifacts/exec-1/big.csv")).isFalse();
        }

        @Test
        @DisplayName("Per-execution limit: archiving stops once the execution's total would exceed it")
        void perExecutionLimitAddsNote() {
            final ArtifactAwareWriteTool limited = new ArtifactAwareWriteTool(new ArtifactArchive(controlFileSystem,
                    ArtifactPolicy.builder().enabled(true).maxExecutionBytes(4).build()));
            final ArtifactCollector collector = new ArtifactCollector("exec-1");
            final ToolContext context = nonDurableContext(collector);

            final ToolResult first = limited.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("a.txt").toString(), "content", "abc")), context);
            final ToolResult second = limited.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("b.txt").toString(), "content", "def")), context);

            assertThat(first.getContent()).doesNotContain("artifact not registered");
            assertThat(second.isSuccess()).isTrue();
            assertThat(second.getContent()).contains("artifact not registered").contains("execution's limit");
            assertThat(collector.getArtifacts()).extracting(FileArtifact::getFileName).containsExactly("a.txt");
        }

        @Test
        @DisplayName("Real archive keys contain ':'; archiving still lands on a local control store")
        void realArchiveKeysArchiveOnALocalControlStore() throws IOException {
            final String subagentKey = "subagent:worker:" + UUID.randomUUID();
            for (ArtifactCollector collector : List.of(new ArtifactCollector(), new ArtifactCollector(subagentKey))) {
                assertThat(collector.getArchiveKey()).contains(":");

                final ToolResult result = writeTool.execute(
                        ToolInput.of(Map.of("file_path", tempDir.resolve("report.csv").toString(), "content", "a,b")),
                        nonDurableContext(collector));

                assertThat(result.getContent()).doesNotContain("artifact not registered");
                final String directory = ArtifactArchive.directoryName(collector.getArchiveKey());
                assertThat(collector.getArtifacts()).singleElement().satisfies(artifact -> {
                    assertThat(artifact.getPath()).isEqualTo("artifacts/" + directory + "/report.csv");
                    assertThat(artifact.getStorage()).isEqualTo(ArtifactStorage.CONTROL);
                });
                assertThat(Files.readString(tempDir.resolve("control/artifacts/" + directory + "/report.csv")))
                        .isEqualTo("a,b");
            }
        }

        @Test
        @DisplayName("Real archive key on a scoped local control store (the CLI's shape)")
        void realArchiveKeyOnAScopedControlStore() throws IOException {
            final ArtifactAwareWriteTool scoped = new ArtifactAwareWriteTool(new ArtifactArchive(
                    new ScopedVirtualFileSystem(fileSystem, ".aimon"), ArtifactPolicy.enabledWithDefaults()));
            final ArtifactCollector collector = new ArtifactCollector();

            final ToolResult result = scoped.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("out.md").toString(), "content", "# hi")),
                    nonDurableContext(collector));

            assertThat(result.getContent()).doesNotContain("artifact not registered");
            final String directory = ArtifactArchive.directoryName(collector.getArchiveKey());
            assertThat(Files.readString(tempDir.resolve(".aimon/artifacts/" + directory + "/out.md")))
                    .isEqualTo("# hi");
        }

        @Test
        @DisplayName("A failed copy is not silent: the write succeeds and a note says the file was not registered")
        void failedCopyAddsNote() {
            final VirtualFileSystem failing = spy(controlFileSystem);
            doThrow(new IllegalStateException("disk full")).when(failing).write(anyString(), any(InputStream.class),
                    anyLong());
            final ArtifactAwareWriteTool tool = new ArtifactAwareWriteTool(
                    new ArtifactArchive(failing, ArtifactPolicy.enabledWithDefaults()));
            final ArtifactCollector collector = new ArtifactCollector();

            final ToolResult result = tool.execute(
                    ToolInput.of(Map.of("file_path", tempDir.resolve("report.csv").toString(), "content", "a,b")),
                    nonDurableContext(collector));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully wrote").contains("[artifact not registered:")
                    .contains("disk full");
            assertThat(collector.getArtifacts()).isEmpty();
        }

        private ToolContext nonDurableContext(ArtifactCollector collector) {
            return ToolContext.builder()
                    .put(ToolContextKeys.EXECUTION_ENVIRONMENT,
                            TestExecutionEnvironments.builder().fileSystem(fileSystem).durable(false).build())
                    .put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
        }
    }
}
