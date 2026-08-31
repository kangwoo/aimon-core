package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;

/**
 * Integration tests for file tools (Read, Write, Edit, Grep).
 *
 * <p>
 * Tests realistic workflows combining multiple file tools together.
 */
class FileToolsIntegrationTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private ReadTool readTool;
    private WriteTool writeTool;
    private EditTool editTool;
    private GrepTool grepTool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();

        readTool = new ReadTool(fileSystem);
        writeTool = new WriteTool(fileSystem);
        editTool = new EditTool(fileSystem);
        grepTool = new GrepTool(fileSystem);
        context = ToolContext.empty();
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    /**
     * Helper method to create a ToolContext with a file marked as read.
     */
    private ToolContext createContextWithReadFile(String filePath) {
        return ToolContext.builder().put(ReadTool.READ_FILES_KEY, Set.of(filePath)).build();
    }

    // Write -> Read -> Edit -> Read workflow

    @Test
    void testWorkflow_WriteReadEditRead_Success() throws IOException {
        Path file = tempDir.resolve("workflow.txt");

        // Step 1: Write initial content
        ToolResult writeResult = writeTool.execute(ToolInput.of("file_path", file.toString(), "content", "Hello World"),
                context);
        assertThat(writeResult.isSuccess()).isTrue();

        // Step 2: Read the file
        ToolResult readResult1 = readTool.execute(ToolInput.of("file_path", file.toString()), context);
        assertThat(readResult1.isSuccess()).isTrue();
        assertThat(readResult1.getContent()).contains("Hello World");

        // Step 3: Edit with context containing read file
        ToolResult editResult = editTool.execute(
                ToolInput.of("file_path", file.toString(), "old_string", "World", "new_string", "Java"),
                createContextWithReadFile(file.toString()));
        assertThat(editResult.isSuccess()).isTrue();

        // Step 4: Read again to verify edit
        ToolResult readResult2 = readTool.execute(ToolInput.of("file_path", file.toString()), context);
        assertThat(readResult2.isSuccess()).isTrue();
        assertThat(readResult2.getContent()).contains("Hello Java");
        assertThat(readResult2.getContent()).doesNotContain("Hello World");
    }

    // Write multiple files -> Grep to find pattern

    /** Checks if ripgrep is available on the system. */
    static boolean isRipgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isRipgrepAvailable")
    void testWorkflow_WriteMultipleFilesAndGrep_Success() throws IOException {
        // Step 1: Write multiple Java files
        Path file1 = tempDir.resolve("User.java");
        writeTool.execute(ToolInput.of("file_path", file1.toString(), "content",
                "public class User {\n    private String name;\n}"), context);

        Path file2 = tempDir.resolve("Product.java");
        writeTool.execute(ToolInput.of("file_path", file2.toString(), "content",
                "public class Product {\n    private String name;\n}"), context);

        Path file3 = tempDir.resolve("Order.java");
        writeTool.execute(
                ToolInput.of("file_path", file3.toString(), "content", "public class Order {\n    private int id;\n}"),
                context);

        // Step 2: Search for "private String name" pattern
        ToolResult grepResult = grepTool.execute(
                ToolInput.of("pattern", "private String name", "path", tempDir.toString(), "type", "java"), context);

        assertThat(grepResult.isSuccess()).isTrue();
        assertThat(grepResult.getContent()).contains("User.java");
        assertThat(grepResult.getContent()).contains("Product.java");
        assertThat(grepResult.getContent()).doesNotContain("Order.java");
    }

    // Read -> Edit multiple times

    @Test
    void testWorkflow_MultipleEdits_Success() throws IOException {
        Path file = tempDir.resolve("counter.java");

        // Step 1: Write initial code
        String initialContent = "public class Counter {\n" + "    private int count;\n" + "    \n"
                + "    public void increment() {\n" + "        count++;\n" + "    }\n" + "}";
        writeTool.execute(ToolInput.of("file_path", file.toString(), "content", initialContent), context);

        // Step 2: Read
        readTool.execute(ToolInput.of("file_path", file.toString()), context);

        // Step 3: Edit - Add getter (with context containing read file)
        editTool.execute(ToolInput.of("file_path", file.toString(), "old_string",
                "    public void increment() {\n        count++;\n    }", "new_string",
                "    public int getCount() {\n        return count;\n    }\n    \n    public void increment() {\n        count++;\n    }"),
                createContextWithReadFile(file.toString()));

        // Step 4: Read again
        readTool.execute(ToolInput.of("file_path", file.toString()), context);

        // Step 5: Edit - Rename variable (with context containing read file)
        editTool.execute(ToolInput.of("file_path", file.toString(), "old_string", "count", "new_string", "value",
                "replace_all", true), createContextWithReadFile(file.toString()));

        // Step 6: Verify final content
        String finalContent = Files.readString(file);
        assertThat(finalContent).contains("private int value");
        assertThat(finalContent).contains("return value");
        assertThat(finalContent).contains("value++");
        assertThat(finalContent).doesNotContain("count");
    }

    // Grep -> Read specific files -> Edit -> Grep again

    @Test
    @EnabledIf("isRipgrepAvailable")
    void testWorkflow_GrepReadEditGrep_Success() throws IOException {
        // Step 1: Write test files with TODO comments
        Path file1 = tempDir.resolve("Service1.java");
        writeTool.execute(
                ToolInput.of("file_path", file1.toString(), "content",
                        "public class Service1 {\n    // TODO: implement validation\n    public void process() {}\n}"),
                context);

        Path file2 = tempDir.resolve("Service2.java");
        writeTool.execute(
                ToolInput.of("file_path", file2.toString(), "content",
                        "public class Service2 {\n    // TODO: add error handling\n    public void execute() {}\n}"),
                context);

        // Step 2: Grep for TODO comments
        ToolResult grepResult1 = grepTool.execute(
                ToolInput.of("pattern", "TODO", "path", tempDir.toString(), "output_mode", "files_with_matches"),
                context);
        assertThat(grepResult1.isSuccess()).isTrue();
        assertThat(grepResult1.getContent()).contains("Service1.java");
        assertThat(grepResult1.getContent()).contains("Service2.java");

        // Step 3: Read first file and remove TODO
        readTool.execute(ToolInput.of("file_path", file1.toString()), context);
        editTool.execute(ToolInput.of("file_path", file1.toString(), "old_string",
                "    // TODO: implement validation\n", "new_string", ""), createContextWithReadFile(file1.toString()));

        // Step 4: Grep again - should only find Service2
        ToolResult grepResult2 = grepTool.execute(
                ToolInput.of("pattern", "TODO", "path", tempDir.toString(), "output_mode", "files_with_matches"),
                context);
        assertThat(grepResult2.isSuccess()).isTrue();
        assertThat(grepResult2.getContent()).doesNotContain("Service1.java");
        assertThat(grepResult2.getContent()).contains("Service2.java");
    }

    // Complex refactoring workflow

    @Test
    void testWorkflow_ComplexRefactoring_Success() throws IOException {
        final Path file = tempDir.resolve("Calculator.java");

        // Step 1: Write initial implementation
        final String initialCode = """
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }

                    public int subtract(int a, int b) {
                        return a - b;
                    }
                }
                """;
        final ToolResult writeResult = writeTool
                .execute(ToolInput.of("file_path", file.toString(), "content", initialCode), context);
        assertThat(writeResult.isSuccess()).isTrue();

        // Step 2: Read file
        final ToolResult readResult = readTool.execute(ToolInput.of("file_path", file.toString()), context);
        assertThat(readResult.isSuccess()).isTrue();

        // Step 3: Refactor - Change return type to long
        final ToolResult editResult = editTool.execute(ToolInput.of("file_path", file.toString(), "old_string",
                "public int", "new_string", "public long", "replace_all", true),
                createContextWithReadFile(file.toString()));
        assertThat(editResult.isSuccess()).isTrue();

        // Step 4: Read to verify no "public int" remains
        final ToolResult readResult2 = readTool.execute(ToolInput.of("file_path", file.toString()), context);
        assertThat(readResult2.isSuccess()).isTrue();

        final String finalContent = Files.readString(file);
        assertThat(finalContent).doesNotContain("public int");
        assertThat(finalContent).contains("public long");
    }

    // Test ReadTool integration with EditTool

    @Test
    void testIntegration_ReadToolMarksFileAsRead_EditToolWorks() throws IOException {
        final Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "original content");

        // Read with ReadTool
        final ToolResult readResult = readTool.execute(ToolInput.of("file_path", file.toString()), context);
        assertThat(readResult.isSuccess()).isTrue();

        // EditTool should work with context containing read file
        final ToolResult editResult = editTool.execute(
                ToolInput.of("file_path", file.toString(), "old_string", "original", "new_string", "modified"),
                createContextWithReadFile(file.toString()));
        assertThat(editResult.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("modified content");
    }

    // Test error handling across tools

    @Test
    void testIntegration_ErrorHandling_ReadNonExistentFile() {
        final Path nonExistent = tempDir.resolve("nonexistent.txt");

        // Read should fail
        final ToolResult readResult = readTool.execute(ToolInput.of("file_path", nonExistent.toString()), context);
        assertThat(readResult.isError()).isTrue();
        assertThat(readResult.getContent()).contains("File not found");

        // Edit should also fail (file not read)
        final ToolResult editResult = editTool.execute(
                ToolInput.of("file_path", nonExistent.toString(), "old_string", "old", "new_string", "new"), context);
        assertThat(editResult.isError()).isTrue();

        // Grep on non-existent file might return error or no matches depending on ripgrep behavior
        final ToolResult grepResult = grepTool
                .execute(ToolInput.of("pattern", "anything", "path", nonExistent.toString()), context);
        // Either error or success with no matches is acceptable
        if (grepResult.isSuccess()) {
            assertThat(grepResult.getContent()).contains("No matches found");
        } else {
            assertThat(grepResult.isError()).isTrue();
        }
    }

    // Test concurrent file operations

    @Test
    void testIntegration_MultipleFilesInParallel_Success() throws IOException {
        // Create multiple files
        for (int i = 1; i <= 5; i++) {
            final Path file = tempDir.resolve("file" + i + ".txt");
            final ToolResult writeResult = writeTool
                    .execute(ToolInput.of("file_path", file.toString(), "content", "Content for file " + i), context);
            assertThat(writeResult.isSuccess()).isTrue();
        }

        // Read and edit each file
        for (int i = 1; i <= 5; i++) {
            final Path file = tempDir.resolve("file" + i + ".txt");
            final ToolResult readResult = readTool.execute(ToolInput.of("file_path", file.toString()), context);
            assertThat(readResult.isSuccess()).isTrue();

            final ToolResult editResult = editTool.execute(ToolInput.of("file_path", file.toString(), "old_string",
                    "Content", "new_string", "Modified content"), createContextWithReadFile(file.toString()));
            assertThat(editResult.isSuccess()).isTrue();
        }

        // Verify all files were modified
        for (int i = 1; i <= 5; i++) {
            final Path file = tempDir.resolve("file" + i + ".txt");
            final String content = Files.readString(file);
            assertThat(content).startsWith("Modified content");
        }
    }

    // Test large file handling

    @Test
    void testIntegration_LargeFileOperations_Success() throws IOException {
        final Path file = tempDir.resolve("large.txt");

        // Step 1: Write large file
        final StringBuilder largeContent = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            largeContent.append("Line ").append(i).append(": Some content here\n");
        }

        final ToolResult writeResult = writeTool
                .execute(ToolInput.of("file_path", file.toString(), "content", largeContent.toString()), context);
        assertThat(writeResult.isSuccess()).isTrue();

        // Step 2: Read with limit
        final ToolResult readResult = readTool.execute(ToolInput.of("file_path", file.toString(), "limit", 10),
                context);
        assertThat(readResult.isSuccess()).isTrue();
        assertThat(readResult.getContent()).contains("Line 1:");
        assertThat(readResult.getContent()).contains("Line 10:");
        assertThat(readResult.getContent()).doesNotContain("Line 11:");

        // Step 3: Read full file to mark as read
        readTool.execute(ToolInput.of("file_path", file.toString()), context);

        // Step 4: Edit specific line
        final ToolResult editResult = editTool
                .execute(
                        ToolInput.of("file_path", file.toString(), "old_string", "Line 500: Some content here",
                                "new_string", "Line 500: MODIFIED CONTENT"),
                        createContextWithReadFile(file.toString()));
        assertThat(editResult.isSuccess()).isTrue();

        // Step 5: Verify edit
        final String content = Files.readString(file);
        assertThat(content).contains("Line 500: MODIFIED CONTENT");
        assertThat(content).doesNotContain("Line 500: Some content here\nLine 500:");
    }
}
