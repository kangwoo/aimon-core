package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

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
        writeTool = new WriteTool();
    }

    /**
     * A context with an environment but no read-stamp map: {@code Write} then performs no stale-write check, which is
     * the behaviour these older cases exercise. The stamp cases below use {@link #stampedContext()}.
     */
    private ToolContext legacyContext() {
        return TestExecutionEnvironments.withoutStamps(TestExecutionEnvironments.of(fileSystem));
    }

    private ToolContext stampedContext() {
        return TestExecutionEnvironments.context(fileSystem);
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    // Constructor tests

    @Test
    void testExecute_NoEnvironment_ReturnsErrorWithoutThrowing() {
        final ToolResult result = writeTool.execute(
                ToolInput.of(Map.of("file_path", tempDir.resolve("a.txt").toString(), "content", "x")),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("No execution environment");
    }

    @Test
    void testExecute_UnavailableEnvironment_ErrorCarriesCause() {
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .build();

        final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "x")),
                context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("sandbox is down");
    }

    @Test
    void testExecute_RelativeDisplay_IsRelativeToEnvironmentWorkingDirectory() {
        final ToolResult result = new WriteTool().execute(
                ToolInput.of(Map.of("file_path", tempDir.resolve("sub/a.txt").toString(), "content", "x")),
                legacyContext());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).endsWith(" to sub/a.txt");
    }

    @Test
    void testExecute_AbsoluteDisplay_ShowsPathAsGiven() {
        final String path = tempDir.resolve("a.txt").toString();

        final ToolResult result = new WriteTool(false).execute(ToolInput.of(Map.of("file_path", path, "content", "x")),
                legacyContext());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).endsWith(" to " + path);
    }

    // stale-write protection (execution-environment design §7)

    @Test
    void testExecute_NewFile_NeedsNoRead() {
        final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "new.txt", "content", "x")),
                stampedContext());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void testExecute_OverwriteWithoutRead_IsRefused() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "old");

        final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "new")),
                stampedContext());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Read the file before modifying it");
        assertThat(Files.readString(tempDir.resolve("a.txt"))).isEqualTo("old");
    }

    @Test
    void testExecute_OverwriteAfterRead_SucceedsAndRefreshesStamp() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "old");
        final ToolContext context = stampedContext();
        assertThat(new ReadTool().execute(ToolInput.of(Map.of("file_path", "a.txt")), context).isSuccess()).isTrue();

        final ToolResult first = writeTool.execute(
                ToolInput.of(Map.of("file_path", tempDir.resolve("a.txt").toString(), "content", "newer")), context);
        final ToolResult second = writeTool.execute(ToolInput.of(Map.of("file_path", "./a.txt", "content", "newest")),
                context);

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("a.txt"))).isEqualTo("newest");
    }

    @Test
    void testExecute_OverwriteAfterExternalChange_IsRefused() throws IOException {
        final Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "old");
        final ToolContext context = stampedContext();
        new ReadTool().execute(ToolInput.of(Map.of("file_path", "a.txt")), context);
        Files.writeString(file, "changed by someone else, longer");

        final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "x")),
                context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("File changed since it was read; Read it again");
    }

    @Test
    void testExecute_EtagOnlyDifference_IsRefused() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "old");
        final AtomicReference<String> etag = new AtomicReference<>("v1");
        final VirtualFileSystem etagFs = withEtag(fileSystem, etag);
        final ToolContext context = TestExecutionEnvironments.contextBuilder(etagFs).build();
        new ReadTool().execute(ToolInput.of(Map.of("file_path", "a.txt")), context);
        etag.set("v2"); // same size and mtime, different content version

        final ToolResult result = writeTool.execute(ToolInput.of(Map.of("file_path", "a.txt", "content", "x")),
                context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("File changed since it was read");
    }

    /** A view of {@code delegate} whose metadata carries the current value of {@code etag}. */
    static VirtualFileSystem withEtag(VirtualFileSystem delegate, AtomicReference<String> etag) {
        return (VirtualFileSystem) Proxy.newProxyInstance(VirtualFileSystem.class.getClassLoader(),
                new Class<?>[]{VirtualFileSystem.class}, (proxy, method, args) -> {
                    try {
                        final Object value = method.invoke(delegate, args);
                        if ("getMetadata".equals(method.getName())) {
                            final FileMetadata m = (FileMetadata) value;
                            return FileMetadata.builder().path(m.getPath()).size(m.getSize())
                                    .createdAt(m.getCreatedAt()).modifiedAt(m.getModifiedAt())
                                    .directory(m.isDirectory()).etag(etag.get()).build();
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Cannot write to directory");
    }

    @Test
    void testExecute_NullFilePath_ReturnsError() {
        // Arrange - Cannot use Map.of() with null values, use empty map instead
        final Map<String, Object> toolUse = Map.of("content", "Some content");

        // Act
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

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
        final ToolResult writeResult = writeTool.execute(ToolInput.of(writeUse), legacyContext());

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
        writeTool.execute(ToolInput.of(Map.of("file_path", testFile.toString(), "content", content1)), legacyContext());
        writeTool.execute(ToolInput.of(Map.of("file_path", testFile.toString(), "content", content2)), legacyContext());
        final ToolResult result3 = writeTool
                .execute(ToolInput.of(Map.of("file_path", testFile.toString(), "content", content3)), legacyContext());

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
        final ToolResult result1 = writeTool
                .execute(ToolInput.of(Map.of("file_path", file1.toString(), "content", "Content 1")), legacyContext());
        final ToolResult result2 = writeTool
                .execute(ToolInput.of(Map.of("file_path", file2.toString(), "content", "Content 2")), legacyContext());
        final ToolResult result3 = writeTool
                .execute(ToolInput.of(Map.of("file_path", file3.toString(), "content", "Content 3")), legacyContext());

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
        final ToolResult result = writeTool.execute(ToolInput.of(toolUse), legacyContext());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("10 bytes");
        assertThat(result.getContent()).contains("bytes.txt");
    }
}
