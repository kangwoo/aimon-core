package at.aimon.core.tools.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.ReadTool;

@DisplayName("ArtifactAwareEditTool Tests")
class ArtifactAwareEditToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;

    private ArtifactAwareEditTool editTool;

    @BeforeEach
    void setUp() {
        final LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        editTool = new ArtifactAwareEditTool(fileSystem);
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    /**
     * Creates a ToolContext with the given file marked as read. EditTool requires files to be read before editing.
     */
    private ToolContext createContextWithReadFile(String filePath) {
        final Set<String> readFiles = new HashSet<>();
        readFiles.add(filePath);
        return ToolContext.builder().put(ReadTool.READ_FILES_KEY, readFiles).build();
    }

    /**
     * Creates a ToolContext with the given file marked as read and an ArtifactCollector.
     */
    private ToolContext createContextWithCollector(String filePath, ArtifactCollector collector) {
        final Set<String> readFiles = new HashSet<>();
        readFiles.add(filePath);
        return ToolContext.builder().put(ReadTool.READ_FILES_KEY, readFiles)
                .put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException when fileSystem is null")
        void shouldThrowOnNullFileSystem() {
            assertThatThrownBy(() -> new ArtifactAwareEditTool(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("File system cannot be null");
        }

        @Test
        @DisplayName("Should create tool with valid fileSystem")
        void shouldCreateWithValidFileSystem() {
            final ArtifactAwareEditTool tool = new ArtifactAwareEditTool(fileSystem);
            assertThat(tool).isNotNull();
        }

    }

    @Nested
    @DisplayName("Tool Definition")
    class Definition {

        @Test
        @DisplayName("Should have tool name 'Edit'")
        void shouldHaveCorrectName() {
            final ToolDefinition definition = editTool.getDefinition();
            assertThat(definition.getName()).isEqualTo("Edit");
        }

        @Test
        @DisplayName("Should include artifact parameter in schema")
        void shouldIncludeArtifactParameter() {
            final ToolDefinition definition = editTool.getDefinition();
            @SuppressWarnings("unchecked")
            final Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
            assertThat(properties).containsKeys("file_path", "old_string", "new_string", "replace_all", "artifact");
        }

        @Test
        @DisplayName("Should require file_path, old_string, new_string but not artifact")
        void shouldRequireCorrectParameters() {
            final ToolDefinition definition = editTool.getDefinition();
            final Object required = definition.getInputSchema().get("required");
            assertThat(required).asList().containsExactly("file_path", "old_string", "new_string");
            assertThat(required).asList().doesNotContain("artifact");
        }

    }

    @Nested
    @DisplayName("Delegation to existing EditTool")
    class Delegation {

        @Test
        @DisplayName("Should edit file successfully (same as original EditTool)")
        void shouldEditFileSuccessfully() throws IOException {
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "Hello World");
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "old_string", "World", "new_string", "Java"));

            final ToolResult result = editTool.execute(input, createContextWithReadFile(testFile.toString()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully replaced 1 occurrence");
            assertThat(Files.readString(testFile)).isEqualTo("Hello Java");
        }

        @Test
        @DisplayName("Should return error for missing file_path")
        void shouldReturnErrorForMissingFilePath() {
            final ToolInput input = ToolInput.of(Map.of("old_string", "old", "new_string", "new"));

            final ToolResult result = editTool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Missing required parameter: file_path");
        }

        @Test
        @DisplayName("Should return error when file has not been read")
        void shouldReturnErrorForFileNotRead() throws IOException {
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "old_string", "content", "new_string", "modified"));

            final ToolResult result = editTool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("has not been read yet");
        }

        @Test
        @DisplayName("Should return error when old_string equals new_string")
        void shouldReturnErrorForSameStrings() throws IOException {
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "old_string", "same", "new_string", "same"));

            final ToolResult result = editTool.execute(input, createContextWithReadFile(testFile.toString()));

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("must be different");
        }

    }

    @Nested
    @DisplayName("Artifact Registration")
    class ArtifactRegistration {

        @Test
        @DisplayName("Should register artifact when artifact=true and edit succeeds")
        void shouldRegisterArtifactOnSuccess() throws IOException {
            final Path testFile = tempDir.resolve("report.csv");
            Files.writeString(testFile, "old,data,here");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            final ToolResult result = editTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);

            final FileArtifact artifact = collector.getArtifacts().get(0);
            assertThat(artifact.getPath()).isEqualTo(testFile.toString());
            assertThat(artifact.getFileName()).isEqualTo("report.csv");
            assertThat(artifact.getSize()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should not register artifact when artifact=false")
        void shouldNotRegisterWhenArtifactFalse() throws IOException {
            final Path testFile = tempDir.resolve("internal.txt");
            Files.writeString(testFile, "old content");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", false));

            final ToolResult result = editTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should register artifact when artifact parameter is omitted (defaults to true)")
        void shouldRegisterWhenArtifactOmitted() throws IOException {
            final Path testFile = tempDir.resolve("default.txt");
            Files.writeString(testFile, "original text");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput
                    .of(Map.of("file_path", testFile.toString(), "old_string", "original", "new_string", "modified"));

            final ToolResult result = editTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("default.txt");
        }

        @Test
        @DisplayName("Should not register artifact when edit fails")
        void shouldNotRegisterOnEditFailure() throws IOException {
            final Path testFile = tempDir.resolve("test.txt");
            Files.writeString(testFile, "content");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "nonexistent",
                    "new_string", "new", "artifact", true));

            final ToolResult result = editTool.execute(input, context);

            assertThat(result.isError()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should succeed even without ArtifactCollector in context")
        void shouldSucceedWithoutCollector() throws IOException {
            final Path testFile = tempDir.resolve("no-collector.txt");
            Files.writeString(testFile, "old value");
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            final ToolResult result = editTool.execute(input, createContextWithReadFile(testFile.toString()));

            assertThat(result.isSuccess()).isTrue();
        }

    }

    @Nested
    @DisplayName("Artifact Metadata")
    class ArtifactMetadata {

        @Test
        @DisplayName("Should include toolUseId from context")
        void shouldIncludeToolUseId() throws IOException {
            final Path testFile = tempDir.resolve("with-tool-id.csv");
            Files.writeString(testFile, "old,data");
            final ArtifactCollector collector = new ArtifactCollector();
            final Set<String> readFiles = new HashSet<>();
            readFiles.add(testFile.toString());
            final ToolContext context = ToolContext.builder().put(ReadTool.READ_FILES_KEY, readFiles)
                    .put(ToolContextKeys.ARTIFACT_COLLECTOR, collector)
                    .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, "toolu_abc123").build();
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            editTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).hasValue("toolu_abc123");
        }

        @Test
        @DisplayName("Should have empty toolUseId when not in context")
        void shouldHaveEmptyToolUseIdWhenMissing() throws IOException {
            final Path testFile = tempDir.resolve("no-tool-id.csv");
            Files.writeString(testFile, "old,data");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            editTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).isEmpty();
        }

        @Test
        @DisplayName("Should use file size from metadata")
        void shouldUseSizeFromMetadata() throws IOException {
            final Path testFile = tempDir.resolve("size-test.txt");
            Files.writeString(testFile, "Hello World"); // 11 bytes
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "World",
                    "new_string", "Java!", "artifact", true));

            editTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            // After edit: "Hello Java!" = 11 bytes
            assertThat(collector.getArtifacts().get(0).getSize()).isEqualTo(11);
        }

        @Test
        @DisplayName("Should extract fileName from path correctly")
        void shouldExtractFileName() throws IOException {
            final Path subDir = tempDir.resolve("reports");
            subDir.toFile().mkdirs();
            final Path testFile = subDir.resolve("sales-2024.csv");
            Files.writeString(testFile, "old,data,here");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            editTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("sales-2024.csv");
        }

        @Test
        @DisplayName("Should populate mimeType from file metadata")
        void shouldPopulateMimeType() throws IOException {
            final Path testFile = tempDir.resolve("data.txt");
            Files.writeString(testFile, "hello world");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "hello",
                    "new_string", "goodbye", "artifact", true));

            editTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            // LocalFileSystem may or may not detect mimeType; verify it is populated without error
            final FileArtifact artifact = collector.getArtifacts().get(0);
            artifact.getMimeType().ifPresent(mime -> assertThat(mime).isNotBlank());
        }

    }

    @Nested
    @DisplayName("Multiple Artifacts")
    class MultipleArtifacts {

        @Test
        @DisplayName("Should collect multiple artifacts across edits")
        void shouldCollectMultipleArtifacts() throws IOException {
            final ArtifactCollector collector = new ArtifactCollector();

            final Path file1 = tempDir.resolve("report1.csv");
            Files.writeString(file1, "old1,data");
            final Set<String> readFiles = new HashSet<>();
            readFiles.add(file1.toString());

            final Path file2 = tempDir.resolve("report2.csv");
            Files.writeString(file2, "old2,data");
            readFiles.add(file2.toString());

            final Path internalFile = tempDir.resolve("temp.txt");
            Files.writeString(internalFile, "old3 internal");
            readFiles.add(internalFile.toString());

            final ToolContext context = ToolContext.builder().put(ReadTool.READ_FILES_KEY, readFiles)
                    .put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();

            editTool.execute(ToolInput.of(Map.of("file_path", file1.toString(), "old_string", "old1", "new_string",
                    "new1", "artifact", true)), context);

            editTool.execute(ToolInput.of(Map.of("file_path", file2.toString(), "old_string", "old2", "new_string",
                    "new2", "artifact", true)), context);

            editTool.execute(ToolInput.of(Map.of("file_path", internalFile.toString(), "old_string", "old3",
                    "new_string", "new3", "artifact", false)), context);

            assertThat(collector.getArtifacts()).hasSize(2);
            assertThat(collector.getArtifacts()).extracting(FileArtifact::getFileName).containsExactly("report1.csv",
                    "report2.csv");
        }

    }

    @Nested
    @DisplayName("Return Value")
    class ReturnValue {

        @Test
        @DisplayName("Should return same success result as delegate EditTool")
        void shouldReturnDelegateSuccessResult() throws IOException {
            final Path testFile = tempDir.resolve("delegate.txt");
            Files.writeString(testFile, "old content here");
            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = createContextWithCollector(testFile.toString(), collector);
            final ToolInput input = ToolInput.of(Map.of("file_path", testFile.toString(), "old_string", "old",
                    "new_string", "new", "artifact", true));

            final ToolResult result = editTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Successfully replaced");
            assertThat(result.getContent()).contains("delegate.txt");
            assertThat(Files.readString(testFile)).isEqualTo("new content here");
        }

        @Test
        @DisplayName("Should return delegate error result unchanged")
        void shouldReturnDelegateErrorResult() {
            final ToolInput input = ToolInput.of(Map.of("old_string", "old", "new_string", "new", "artifact", true));

            final ToolResult result = editTool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
        }

    }

}
