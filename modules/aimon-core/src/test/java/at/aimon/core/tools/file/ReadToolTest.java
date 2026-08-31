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

/** Unit tests for {@link ReadTool}. */
class ReadToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private ReadTool readTool;

    @BeforeEach
    void setUp() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        readTool = new ReadTool(fileSystem);
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
        assertThatThrownBy(() -> new ReadTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("File system cannot be null");
    }

    @Test
    void testConstructor_ValidFileSystem_Success() {
        ReadTool tool = new ReadTool(fileSystem);
        assertThat(tool).isNotNull();
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        ToolDefinition definition = readTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("Read");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        ToolDefinition definition = readTool.getDefinition();
        assertThat(definition.getDescription()).contains("Read file contents");
        assertThat(definition.getDescription()).contains("line numbers");
        assertThat(definition.getDescription()).contains("2000");
    }

    @Test
    void testGetDefinition_HasRequiredFilePathParameter() {
        ToolDefinition definition = readTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("file_path");
    }

    @Test
    void testGetDefinition_HasOptionalOffsetAndLimitParameters() {
        ToolDefinition definition = readTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("file_path", "offset", "limit");
    }

    // execute tests - success cases

    @Test
    void testExecute_SmallFile_ReturnsContentWithLineNumbers() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("     1→Line 1");
        assertThat(result.getContent()).contains("     2→Line 2");
        assertThat(result.getContent()).contains("     3→Line 3");
    }

    @Test
    void testExecute_EmptyFile_ReturnsWarningMessage() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("empty.txt");
        Files.writeString(testFile, "");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("System Warning: This file is empty");
    }

    @Test
    void testExecute_WithOffset_StartsFromSpecifiedLine() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", 3);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).doesNotContain("     1→Line 1");
        assertThat(result.getContent()).doesNotContain("     2→Line 2");
        assertThat(result.getContent()).contains("     3→Line 3");
        assertThat(result.getContent()).contains("     4→Line 4");
        assertThat(result.getContent()).contains("     5→Line 5");
    }

    @Test
    void testExecute_WithLimit_ReadsOnlySpecifiedNumberOfLines() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "limit", 3);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("     1→Line 1");
        assertThat(result.getContent()).contains("     2→Line 2");
        assertThat(result.getContent()).contains("     3→Line 3");
        assertThat(result.getContent()).doesNotContain("     4→Line 4");
        assertThat(result.getContent()).doesNotContain("     5→Line 5");
    }

    @Test
    void testExecute_WithOffsetAndLimit_ReadsCorrectRange() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\nLine 6\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", 2, "limit", 3);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).doesNotContain("     1→Line 1");
        assertThat(result.getContent()).contains("     2→Line 2");
        assertThat(result.getContent()).contains("     3→Line 3");
        assertThat(result.getContent()).contains("     4→Line 4");
        assertThat(result.getContent()).doesNotContain("     5→Line 5");
        assertThat(result.getContent()).doesNotContain("     6→Line 6");
    }

    @Test
    void testExecute_LongLine_IsTruncatedAt2000Characters() throws IOException {
        // Arrange
        String longLine = "x".repeat(3000);
        Path testFile = tempDir.resolve("long.txt");
        Files.writeString(testFile, longLine);

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getContent().split("\n");
        assertThat(lines).hasSize(1);
        // Line number (6 chars) + arrow (1 char) + content (2000 chars) + ellipsis (3 chars)
        assertThat(lines[0]).hasSize(6 + 1 + 2000 + 3);
        assertThat(lines[0]).endsWith("...");
    }

    @Test
    void testExecute_OffsetBeyondFileEnd_ReturnsEmptyContent() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\nLine 2\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", 100);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("System Warning: This file is empty");
    }

    // execute tests - error cases

    @Test
    void testExecute_Directory_ReturnsError() throws IOException {
        // Arrange
        Path directory = tempDir.resolve("subdir");
        Files.createDirectories(directory);

        Map<String, Object> toolUse = Map.of("file_path", directory.toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Cannot read directory");
        assertThat(result.getContent()).contains("ls");
    }

    @Test
    void testExecute_MissingFilePath_ReturnsError() {
        // Arrange
        Map<String, Object> toolUse = Map.of();

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: file_path");
    }

    @Test
    void testExecute_FileNotFound_ReturnsError() {
        // Arrange
        Map<String, Object> toolUse = Map.of("file_path", tempDir.resolve("nonexistent.txt").toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("File not found");
    }

    @Test
    void testExecute_InvalidOffset_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", 0);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("offset must be >= 1");
    }

    @Test
    void testExecute_NegativeOffset_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", -5);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("offset must be >= 1");
    }

    @Test
    void testExecute_InvalidLimit_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "limit", 0);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("limit must be >= 1");
    }

    @Test
    void testExecute_NegativeLimit_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "limit", -10);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("limit must be >= 1");
    }

    @Test
    void testExecute_NonNumericOffset_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", "not a number");

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter:");
        assertThat(result.getContent()).contains("Parameter 'offset' must be an integer");
    }

    @Test
    void testExecute_NonNumericLimit_ReturnsError() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Line 1\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "limit", "not a number");

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter:");
        assertThat(result.getContent()).contains("Parameter 'limit' must be an integer");
    }

    // Line numbering format tests

    @Test
    void testExecute_LineNumberFormat_IsCorrect() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "First line\n");

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString());

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        // Format should be: " 1→First line\n"
        // 6 characters for line number (right-aligned), then arrow, then content
        String[] lines = result.getContent().split("\n");
        assertThat(lines[0]).startsWith("     1→");
        assertThat(lines[0]).contains("First line");
    }

    @Test
    void testExecute_MultipleLines_AllHaveLineNumbers() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            content.append("Line ").append(i).append("\n");
        }
        Files.writeString(testFile, content.toString());

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "limit", 100);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getContent().split("\n");
        assertThat(lines).hasSize(100);
        for (int i = 0; i < 100; i++) {
            assertThat(lines[i]).contains("→Line " + (i + 1));
        }
    }

    @Test
    void testExecute_LargeLineNumbers_AreFormattedCorrectly() throws IOException {
        // Arrange
        Path testFile = tempDir.resolve("test.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            content.append("Line ").append(i).append("\n");
        }
        Files.writeString(testFile, content.toString());

        Map<String, Object> toolUse = Map.of("file_path", testFile.toString(), "offset", 9, "limit", 2);

        // Act
        ToolResult result = readTool.execute(ToolInput.of(toolUse), ToolContext.empty());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("     9→Line 9");
        assertThat(result.getContent()).contains("    10→Line 10");
    }
}
