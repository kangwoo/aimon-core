package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.ToolContextKeys;

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
        editTool = new EditTool();
        // An environment and no read stamps at all: Edit treats that as "not read".
        context = TestExecutionEnvironments.withoutStamps(TestExecutionEnvironments.of(fileSystem));
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
        final ToolContext readContext = TestExecutionEnvironments.context(fileSystem);
        final ExecutionEnvironment env = ExecutionEnvironmentAccess.require(readContext);
        if (Files.exists(Path.of(filePath))) {
            readContext.get(ReadTool.FILE_STAMPS_KEY).orElseThrow().put(FileStamps.key(env, filePath),
                    FileStamps.current(env, filePath));
        }
        return readContext;
    }

    // Constructor tests

    @Test
    void testExecute_NoEnvironment_ReturnsErrorWithoutThrowing() {
        ToolResult result = editTool.execute(ToolInput.of("file_path", "a.txt", "old_string", "a", "new_string", "b"),
                ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("No execution environment");
    }

    @Test
    void testExecute_UnavailableEnvironment_ErrorCarriesCause() {
        ToolContext unavailable = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<>()).build();

        ToolResult result = editTool.execute(ToolInput.of("file_path", "a.txt", "old_string", "a", "new_string", "b"),
                unavailable);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("sandbox is down");
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
        assertThat(result.getContent()).contains("Read the file before modifying it");
        assertThat(result.getContent()).contains("Read tool");
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

    // stale-write protection (execution-environment design §7)

    @Test
    void testExecute_WithoutRead_IsRefused() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "hello");

        ToolResult result = editTool.execute(
                ToolInput.of("file_path", "a.txt", "old_string", "hello", "new_string", "bye"),
                TestExecutionEnvironments.context(fileSystem));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Read the file before modifying it");
    }

    @Test
    void testExecute_ChangedAfterRead_IsRefused() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello");
        ToolContext context = TestExecutionEnvironments.context(fileSystem);
        new ReadTool().execute(ToolInput.of("file_path", "a.txt"), context);
        fileSystem.write("a.txt", "hello, rewritten by another execution");

        ToolResult result = editTool
                .execute(ToolInput.of("file_path", "a.txt", "old_string", "hello", "new_string", "bye"), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("File changed since it was read; Read it again");
    }

    @Test
    void testExecute_ChangedByShellAfterRead_IsRefused() throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello");
        ToolContext context = TestExecutionEnvironments.context(fileSystem);
        new ReadTool().execute(ToolInput.of("file_path", "a.txt"), context);
        new LocalShell(tempDir).execute(() -> "printf 'hello from sed, longer' > a.txt");

        ToolResult result = editTool
                .execute(ToolInput.of("file_path", "a.txt", "old_string", "hello", "new_string", "bye"), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("File changed since it was read");
    }

    @Test
    void testExecute_ReadAndEditUnderDifferentSpellings_ShareOneStamp() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "one two three");
        ToolContext context = TestExecutionEnvironments.context(fileSystem);
        new ReadTool().execute(ToolInput.of("file_path", "a.txt"), context);

        ToolResult first = editTool
                .execute(ToolInput.of("file_path", "./a.txt", "old_string", "one", "new_string", "1"), context);
        ToolResult second = editTool.execute(
                ToolInput.of("file_path", tempDir.resolve("a.txt").toString(), "old_string", "two", "new_string", "2"),
                context);

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("a.txt"))).isEqualTo("1 2 three");
    }

    @Test
    void testExecute_ForkDoesNotInheritParentStamps() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        ToolContext parent = TestExecutionEnvironments.context(fileSystem);
        new ReadTool().execute(ToolInput.of("file_path", "a.txt"), parent);
        // A fork gets a fresh stamp map from its executor, never the parent's.
        ToolContext fork = TestExecutionEnvironments.context(fileSystem);

        ToolResult result = editTool
                .execute(ToolInput.of("file_path", "a.txt", "old_string", "hello", "new_string", "bye"), fork);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Read the file before modifying it");
    }

    // Line endings: Edit changes what old_string names and nothing else.

    private String editFile(String name, String original, String oldString, String newString) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, original);
        ToolResult result = editTool.execute(
                ToolInput.of("file_path", file.toString(), "old_string", oldString, "new_string", newString),
                createContextWithReadFile(file.toString()));
        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        return Files.readString(file);
    }

    @Test
    void testExecute_KeepsTheTrailingNewline() throws IOException {
        assertThat(editFile("trailing.txt", "alpha\nbeta\n", "alpha", "gamma")).isEqualTo("gamma\nbeta\n");
    }

    @Test
    void testExecute_KeepsSeveralTrailingNewlines() throws IOException {
        assertThat(editFile("blank-tail.txt", "alpha\n\n\n", "alpha", "gamma")).isEqualTo("gamma\n\n\n");
    }

    @Test
    void testExecute_KeepsCrlfLineEndings() throws IOException {
        assertThat(editFile("crlf.txt", "alpha\r\nbeta\r\n", "alpha", "gamma")).isEqualTo("gamma\r\nbeta\r\n");
    }

    @Test
    void testExecute_MultiLineOldStringWithLfMatchesACrlfFileAndWritesCrlf() throws IOException {
        // The model writes \n; a CRLF file still matches, and the lines it adds take the file's ending.
        assertThat(editFile("crlf-multi.txt", "one\r\ntwo\r\nthree\r\n", "one\ntwo", "uno\ndos\nmas"))
                .isEqualTo("uno\r\ndos\r\nmas\r\nthree\r\n");
    }

    @Test
    void testExecute_LeavesMixedLineEndingsOutsideTheEditAlone() throws IOException {
        assertThat(editFile("mixed.txt", "one\r\ntwo\nthree\r\n", "two", "dos")).isEqualTo("one\r\ndos\nthree\r\n");
    }

    @Test
    void testExecute_MultiLineOldStringMatchesAMixedEndingFileAndTouchesOnlyTheSpan() throws IOException {
        // Read shows clean lines whatever the endings, so the model joins them with \n. Only the matched span changes;
        // the CRLF on the untouched last line stays.
        assertThat(editFile("mixed-multi.txt", "one\r\ntwo\nthree\r\n", "one\ntwo", "uno\ndos"))
                .isEqualTo("uno\r\ndos\nthree\r\n");
    }

    @Test
    void testExecute_MatchesAndKeepsALoneCrFile() throws IOException {
        assertThat(editFile("cr.txt", "one\rtwo\rthree\r", "one\ntwo", "uno\ndos")).isEqualTo("uno\rdos\rthree\r");
    }

    @Test
    void testExecute_RejectsAnEditThatOnlyChangesLineEndings() throws IOException {
        Path file = tempDir.resolve("endings-only.txt");
        Files.writeString(file, "a\r\nb\r\n");

        ToolResult result = editTool.execute(
                ToolInput.of("file_path", file.toString(), "old_string", "a\r\nb", "new_string", "a\nb"),
                createContextWithReadFile(file.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("differ only in line endings");
        assertThat(Files.readString(file)).isEqualTo("a\r\nb\r\n");
    }

    @Test
    void testExecute_RejectsAnEmptyOldString() throws IOException {
        Path file = tempDir.resolve("empty-old.txt");
        Files.writeString(file, "content");

        ToolResult result = editTool.execute(
                ToolInput.of("file_path", file.toString(), "old_string", "", "new_string", "x"),
                createContextWithReadFile(file.toString()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("must not be empty");
    }

    @Test
    void testExecute_ReplaceAllSplicesEveryMatchIntoTheRawContent() throws IOException {
        Path file = tempDir.resolve("crlf-all.txt");
        Files.writeString(file, "x=1\r\ny=2\r\nx=1\r\n");

        ToolResult result = editTool.execute(ToolInput.of(
                Map.of("file_path", file.toString(), "old_string", "x=1", "new_string", "x=3", "replace_all", true)),
                createContextWithReadFile(file.toString()));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(result.getContent()).contains("2 occurrence(s)");
        assertThat(Files.readString(file)).isEqualTo("x=3\r\ny=2\r\nx=3\r\n");
    }

    // A lone CR beside an LF reads as one CRLF break. A splice must not create that pair where the two were, or are
    // meant to be, separate breaks -- every line the folded view says is there must still be there.

    @Test
    void testExecute_AnInsertedBreakBeforeALoneLfDoesNotMergeIntoCrlf() throws IOException {
        // Prevailing CR; the break added after z would otherwise sit against the LF that follows.
        final String edited = editFile("cr-then-lf.txt", "x\ry\rz\nw", "z", "z\n");
        assertThat(EditTool.LineBreakView.of(edited).text()).isEqualTo("x\ny\nz\n\nw");
    }

    @Test
    void testExecute_ADeletionBetweenALoneCrAndALoneLfKeepsBothBreaks() throws IOException {
        final String edited = editFile("cr-gap-lf.txt", "a\rb\nc", "b", "");
        assertThat(EditTool.LineBreakView.of(edited).text()).isEqualTo("a\n\nc");
    }

    @Test
    void testExecute_AReplacementStartingWithABreakAfterALoneCrKeepsBothBreaks() throws IOException {
        // Prevailing LF; new_string opens with a break and lands right after a lone CR.
        final String edited = editFile("cr-before-insert.txt", "p\rq\nr\ns\n", "q", "\nq");
        assertThat(EditTool.LineBreakView.of(edited).text()).isEqualTo("p\n\nq\nr\ns\n");
    }
}
