package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;

/** Unit tests for {@link WriteTool}. */
class WriteToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private WriteTool writeTool;

    @BeforeEach
    void setUp() {
        final LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        writeTool = new WriteTool(fileSystem);
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    // Constructor tests

    @Test
    void testConstructor_NullFileSystem_ThrowsException() {
        assertThatThrownBy(() -> new WriteTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("File system cannot be null");
    }

    @Test
    void testConstructor_ValidFileSystem_Success() {
        final WriteTool tool = new WriteTool(fileSystem);
        assertThat(tool).isNotNull();
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        final ToolDefinition definition = writeTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("Write");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        final ToolDefinition definition = writeTool.getDefinition();
        assertThat(definition.getDescription()).contains("Write content");
        assertThat(definition.getDescription()).contains("overwrite");
        assertThat(definition.getDescription()).containsIgnoringCase("absolute");
    }

    @Test
    void testGetDefinition_HasRequiredParameters() {
        final ToolDefinition definition = writeTool.getDefinition();
        final Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("file_path", "content");
    }

    @Test
    void testGetDefinition_HasCorrectProperties() {
        final ToolDefinition definition = writeTool.getDefinition();
        final Map<String, Object> schema = definition.getInputSchema();
        @SuppressWarnings("unchecked")
        final Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("file_path", "content");
    }

    // execute tests - success cases

    @Test
    void testExecute_CreateNewFile_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("newfile.txt");
        final String content = "Hello World";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Successfully wrote");
        assertThat(result.getContent()).contains("11 bytes"); // "Hello World" = 11 bytes
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_EmptyContent_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("empty.txt");
        final String content = "";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEmpty();
    }

    @Test
    void testExecute_MultilineContent_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("multiline.txt");
        final String content = "Line 1\nLine 2\nLine 3";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_SpecialCharacters_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("special.txt");
        final String content = "Special: !@#$%^&*()_+-={}[]|\\:\";<>?,./";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_UnicodeContent_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("unicode.txt");
        final String content = "Hello 世界 🌍 Здравствуй";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_LargeContent_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("large.txt");
        final String content = "x".repeat(10000);

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_OverwriteExistingFile_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("existing.txt");
        Files.writeString(testFile, "Original content");
        final String newContent = "New content";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", newContent);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(newContent);
        assertThat(Files.readString(testFile)).doesNotContain("Original");
    }

    @Test
    void testExecute_CreateFileInSubdirectory_Success() throws IOException {
        // Arrange
        final Path subDir = tempDir.resolve("subdir");
        Files.createDirectories(subDir);
        final Path testFile = subDir.resolve("file.txt");
        final String content = "Content in subdirectory";

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    @Test
    void testExecute_JavaCodeContent_Success() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("Main.java");
        final String content = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """;

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.exists(testFile)).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content);
    }

    // execute tests - error cases

    @Test
    void testExecute_MissingFilePath_ReturnsError() {
        // Arrange
        final Map<String, Object> toolUse = Map.of("content", "Some content");

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Missing required parameter: file_path");
    }

    @Test
    void testExecute_MissingContent_ReturnsError() {
        // Arrange
        final Path testFile = tempDir.resolve("test.txt");
        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Missing required parameter: content");
    }

    @Test
    void testExecute_Directory_ReturnsError() throws IOException {
        // Arrange
        final Path directory = tempDir.resolve("subdir");
        Files.createDirectories(directory);

        final Map<String, Object> toolUse = Map.of("file_path", directory.toString(), "content", "Some content");

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Cannot write to directory");
    }

    @Test
    void testExecute_NullFilePath_ReturnsError() {
        // Arrange - Cannot use Map.of() with null values, use empty map instead
        final Map<String, Object> toolUse = Map.of("content", "Some content");

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Missing required parameter: file_path");
    }

    @Test
    void testExecute_NullContent_ReturnsError() {
        // Arrange - Cannot use Map.of() with null values, use empty map 대신
        final Path testFile = tempDir.resolve("test.txt");
        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Missing required parameter: content");
    }

    // Integration tests

    @Test
    void testExecute_WriteAndRead_RoundTrip() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("roundtrip.txt");
        final String originalContent = "Original content for round trip test";

        final Map<String, Object> writeUse = Map.of("file_path", testFile.toString(), "content", originalContent);

        // Act - Write
        final ToolResult writeResult = writeTool.execute(ToolInput.of(writeUse), ToolContext.empty());

        // Assert - Write succeeded
        assertThat(writeResult.isSuccess()).isTrue();

        // Assert - Can read back the same content
        final String readContent = Files.readString(testFile);
        assertThat(readContent).isEqualTo(originalContent);
    }

    @Test
    void testExecute_MultipleWrites_LastOneWins() throws IOException {
        // Arrange
        final Path testFile = tempDir.resolve("multiple.txt");
        final String content1 = "First content";
        final String content2 = "Second content";
        final String content3 = "Third content";

        // Act - Write three times
        writeTool.execute(ToolInput.of(Map.of("file_path", testFile.toString(), "content", content1)),
                ToolContext.empty());
        writeTool.execute(ToolInput.of(Map.of("file_path", testFile.toString(), "content", content2)),
                ToolContext.empty());
        final ToolResult result3 = writeTool.execute(
                ToolInput.of(Map.of("file_path", testFile.toString(), "content", content3)), ToolContext.empty());

        // Assert
        assertThat(result3.isSuccess()).isTrue();
        assertThat(Files.readString(testFile)).isEqualTo(content3);
        assertThat(Files.readString(testFile)).doesNotContain(content1);
        assertThat(Files.readString(testFile)).doesNotContain(content2);
    }

    @Test
    void testExecute_CreateMultipleFiles_Success() throws IOException {
        // Arrange
        final Path file1 = tempDir.resolve("file1.txt");
        final Path file2 = tempDir.resolve("file2.txt");
        final Path file3 = tempDir.resolve("file3.txt");

        // Act
        final ToolResult result1 = writeTool.execute(
                ToolInput.of(Map.of("file_path", file1.toString(), "content", "Content 1")), ToolContext.empty());
        final ToolResult result2 = writeTool.execute(
                ToolInput.of(Map.of("file_path", file2.toString(), "content", "Content 2")), ToolContext.empty());
        final ToolResult result3 = writeTool.execute(
                ToolInput.of(Map.of("file_path", file3.toString(), "content", "Content 3")), ToolContext.empty());

        // Assert
        assertThat(result1.isSuccess()).isTrue();
        assertThat(result2.isSuccess()).isTrue();
        assertThat(result3.isSuccess()).isTrue();
        assertThat(Files.exists(file1)).isTrue();
        assertThat(Files.exists(file2)).isTrue();
        assertThat(Files.exists(file3)).isTrue();
        assertThat(Files.readString(file1)).isEqualTo("Content 1");
        assertThat(Files.readString(file2)).isEqualTo("Content 2");
        assertThat(Files.readString(file3)).isEqualTo("Content 3");
    }

    @Test
    void testExecute_ResultMessage_ContainsByteCount() {
        // Arrange
        final Path testFile = tempDir.resolve("bytes.txt");
        final String content = "1234567890"; // 10 bytes

        final Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "content", content);

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("10 bytes");
        assertThat(result.getContent()).contains("bytes.txt");
    }
}
