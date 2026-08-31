package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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

/** Unit tests for {@link EditTool}. */
class EditToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private EditTool editTool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        editTool = new EditTool(fileSystem);
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
     *
     * @param filePath
     *            The file path to mark as read
     * @return A ToolContext with the file marked as read
     */
    private ToolContext createContextWithReadFile(String filePath) {
        return ToolContext.builder().put(ReadTool.READ_FILES_KEY, Set.of(filePath)).build();
    }

    // Constructor tests

    @Test
    void testConstructor_NullFileSystem_ThrowsException() {
        assertThatThrownBy(() -> new EditTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("File system cannot be null");
    }

    @Test
    void testConstructor_ValidFileSystem_Success() {
        EditTool tool = new EditTool(fileSystem);
        assertThat(tool).isNotNull();
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        ToolDefinition definition = editTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("Edit");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        ToolDefinition definition = editTool.getDefinition();
        assertThat(definition.getDescription()).contains("exact string replacements");
        assertThat(definition.getDescription()).contains("Read tools");
        assertThat(definition.getDescription()).contains("CRITICAL");
    }

    @Test
    void testGetDefinition_HasRequiredParameters() {
        ToolDefinition definition = editTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("file_path", "old_string", "new_string");
    }

    @Test
    void testGetDefinition_HasOptionalReplaceAllParameter() {
        ToolDefinition definition = editTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("file_path", "old_string", "new_string", "replace_all");
    }

    // execute tests - validation

    @Test
    void testExecute_FileNotRead_ReturnsError() {
        String filePath = tempDir.resolve("test.txt").toString();

        ToolInput toolInput = ToolInput.of("file_path", filePath, "old_string", "old", "new_string", "new");

        ToolResult result = editTool.execute(toolInput, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("has not been read yet");
        assertThat(result.getContent()).contains("Read tools");
    }

    @Test
    void testExecute_MissingFilePath_ReturnsError() {
        ToolInput toolInput = ToolInput.of("old_string", "old", "new_string", "new");

        ToolResult result = editTool.execute(toolInput, context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: file_path");
    }

    @Test
    void testExecute_MissingOldString_ReturnsError() {
        String filePath = tempDir.resolve("test.txt").toString();

        ToolInput toolInput = ToolInput.of("file_path", filePath, "new_string", "new");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(filePath));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: old_string");
    }

    @Test
    void testExecute_MissingNewString_ReturnsError() {
        String filePath = tempDir.resolve("test.txt").toString();

        ToolInput toolInput = ToolInput.of("file_path", filePath, "old_string", "old");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(filePath));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: new_string");
    }

    @Test
    void testExecute_SameOldAndNewString_ReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "same", "new_string", "same");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("must be different");
    }

    @Test
    void testExecute_OldStringNotFound_ReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "existing content");

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "nonexistent", "new_string",
                "new");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("old_string not found");
    }

    @Test
    void testExecute_OldStringNotUnique_ReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "count = 0;\nint x = 1;\ncount = 0;");

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string", "count = 0;", "new_string",
                "total = 0;");

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("appears 2 times");
        assertThat(result.getContent()).contains("replace_all");
    }

    // execute tests - success cases

    @Test
    void testExecute_SimpleReplacement_Success() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "World", "new_string", "Java");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Successfully replaced 1 occurrence");

        String newContent = Files.readString(file);
        assertThat(newContent).isEqualTo("Hello Java");
    }

    @Test
    void testExecute_MultiLineReplacement_Success() throws IOException {
        Path file = tempDir.resolve("test.java");
        String originalContent = "public class Test {\n" + "    public void oldMethod() {\n" + "        return;\n"
                + "    }\n" + "}";
        Files.writeString(file, originalContent);

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string",
                "    public void oldMethod() {\n        return;\n    }", "new_string",
                "    public String newMethod() {\n        return \"result\";\n    }");

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();

        String newContent = Files.readString(file);
        assertThat(newContent).contains("public String newMethod()");
        assertThat(newContent).contains("return \"result\";");
        assertThat(newContent).doesNotContain("oldMethod");
    }

    @Test
    void testExecute_ReplaceAll_Success() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "count = 0;\nint x = 1;\ncount = 0;\ncount = 0;");

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string", "count = 0;", "new_string",
                "total = 0;", "replace_all", true);

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Successfully replaced 3 occurrence(s)");

        String newContent = Files.readString(file);
        assertThat(newContent).contains("total = 0;");
        assertThat(newContent).doesNotContain("count = 0;");
    }

    @Test
    void testExecute_PreserveIndentation_Success() throws IOException {
        Path file = tempDir.resolve("test.java");
        String originalContent = "public class Test {\n" + "    private String username;\n" + "    private int age;\n"
                + "}";
        Files.writeString(file, originalContent);

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string", "    private String username;",
                "new_string", "    private String email;");

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();

        String newContent = Files.readString(file);
        assertThat(newContent).contains("    private String email;");
        assertThat(newContent).contains("    private int age;");
    }

    @Test
    void testExecute_DeleteText_Success() throws IOException {
        Path file = tempDir.resolve("test.java");
        String originalContent = "public class Test {\n" + "    System.out.println(\"Debug\");\n" + "    doWork();\n"
                + "}";
        Files.writeString(file, originalContent);

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string",
                "    System.out.println(\"Debug\");\n", "new_string", "");

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();

        String newContent = Files.readString(file);
        assertThat(newContent).doesNotContain("System.out.println");
        assertThat(newContent).contains("doWork()");
    }

    @Test
    void testExecute_AddText_Success() throws IOException {
        Path file = tempDir.resolve("test.java");
        String originalContent = "public class Test {\n" + "    public void process(String input) {\n"
                + "        doSomething(input);";
        Files.writeString(file, originalContent);

        ToolInput input = ToolInput.of("file_path", file.toString(), "old_string",
                "    public void process(String input) {\n        doSomething(input);", "new_string",
                "    public void process(String input) {\n        if (input == null) {\n            throw new IllegalArgumentException();\n        }\n        doSomething(input);");

        ToolResult result = editTool.execute(input, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();

        String newContent = Files.readString(file);
        assertThat(newContent).contains("if (input == null)");
        assertThat(newContent).contains("throw new IllegalArgumentException()");
    }

    @Test
    void testExecute_SpecialCharacters_Success() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "regex: [a-z]+ (test) {1,3}");

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "regex: [a-z]+ (test) {1,3}",
                "new_string", "regex: [0-9]+ (demo) {2,4}");

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();

        String newContent = Files.readString(file);
        assertThat(newContent).contains("[0-9]+");
        assertThat(newContent).contains("(demo)");
        assertThat(newContent).contains("{2,4}");
    }

    @Test
    void testExecute_VariableRenaming_Success() throws IOException {
        Path file = tempDir.resolve("test.java");
        String originalContent = "int userId = 1;\nString userName = \"test\";\nreturn userId;";
        Files.writeString(file, originalContent);

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "userId", "new_string",
                "accountId", "replace_all", true);

        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Successfully replaced 2 occurrence(s)");

        String newContent = Files.readString(file);
        assertThat(newContent).contains("accountId");
        assertThat(newContent).doesNotContain("userId");
        assertThat(newContent).contains("userName"); // Should not affect userName
    }

    // Context-based read file validation tests

    @Test
    void testContextWithReadFile_AllowsEdit() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        ToolInput toolInput = ToolInput.of("file_path", file.toString(), "old_string", "content", "new_string",
                "modified");

        // Provide context with file marked as read
        ToolResult result = editTool.execute(toolInput, createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).isTrue();
    }
}
