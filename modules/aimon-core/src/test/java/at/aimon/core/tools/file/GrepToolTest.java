package at.aimon.core.tools.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Unit tests for {@link GrepTool}.
 *
 * <p>
 * Tests the VirtualFileSystem-based grep implementation.
 */
class GrepToolTest {

    @TempDir
    Path tempDir;

    private VirtualFileSystem fileSystem;
    private GrepTool grepTool;
    private ToolContext context;

    @BeforeEach
    void setUp() throws IOException {
        LocalFileSystemConfig config = new LocalFileSystemConfig(tempDir.toString());
        fileSystem = new LocalFileSystem(config);
        fileSystem.initialize();
        grepTool = new GrepTool();
        context = TestExecutionEnvironments.context(fileSystem);

        // Create test files
        createTestFiles();
    }

    @AfterEach
    void tearDown() {
        if (fileSystem != null) {
            fileSystem.close();
        }
    }

    private void createTestFiles() throws IOException {
        // Java file with various patterns
        Files.writeString(tempDir.resolve("Main.java"),
                "package com.example;\n" + "\n" + "public class Main {\n" + "    // TODO: Implement validation\n"
                        + "    public void authenticate(String user, String password) {\n"
                        + "        System.out.println(\"Authenticating user\");\n" + "    }\n" + "    \n"
                        + "    public void login() {\n" + "        authenticate(\"admin\", \"secret\");\n" + "    }\n"
                        + "}\n");

        // Another Java file
        Files.writeString(tempDir.resolve("Service.java"),
                "package com.example;\n" + "\n" + "public class Service {\n" + "    // TODO: Add error handling\n"
                        + "    // TODO: Add logging\n" + "    public void authenticate(String token) {\n"
                        + "        // Authentication logic\n" + "    }\n" + "}\n");

        // Python file
        Files.writeString(tempDir.resolve("test.py"), "def authenticate(username, password):\n" + "    return True\n"
                + "\n" + "class User:\n" + "    pass\n");

        // Config file
        Files.writeString(tempDir.resolve("config.yaml"), "database:\n" + "  host: localhost\n" + "  port: 5432\n");
    }

    // Constructor tests

    @Test
    void testExecute_NoEnvironment_ReturnsErrorWithoutThrowing() {
        ToolResult result = grepTool.execute(ToolInput.of(Map.of("pattern", "x")), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("No execution environment");
    }

    @Test
    void testExecute_UnavailableEnvironment_ErrorCarriesCause() {
        ToolContext unavailable = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .build();

        ToolResult result = grepTool.execute(ToolInput.of(Map.of("pattern", "x")), unavailable);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("sandbox is down");
    }

    @Test
    void testExecute_UnavailableEnvironmentWithExplicitPath_IsAnErrorNotNoMatches() {
        ToolContext unavailable = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .build();

        for (String path : List.of("src", ".", "/proj/src", "Main.java")) {
            ToolResult result = grepTool.execute(ToolInput.of(Map.of("pattern", "x", "path", path)), unavailable);

            assertThat(result.isError()).as(path).isTrue();
            assertThat(result.getContent()).as(path).contains("sandbox is down").doesNotContain("No matches found");
        }
    }

    @Test
    void testExecute_EnvironmentLostDuringTheWalk_IsAnErrorNotAPartialAnswer() {
        VirtualFileSystem dying = spy(fileSystem);
        doThrow(new ExecutionEnvironmentUnavailableException("sandbox went away", null)).when(dying).read(anyString());
        ToolContext ctx = TestExecutionEnvironments.context(dying);

        ToolResult result = grepTool.execute(ToolInput.of(Map.of("pattern", "TODO", "path", ".")), ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("sandbox went away").doesNotContain("No matches found");
    }

    // content search delegation (execution-environment design §4.1, §6)

    @Test
    void testExecute_ContentSearchPresent_IsUsedAndFormattedLikeTheWalk() {
        Map<String, Object> input = Map.of("pattern", "authenticate\\(String user", "output_mode", "content");
        ToolResult walked = grepTool.execute(ToolInput.of(input), context);

        List<ContentQuery> queries = new ArrayList<>();
        ContentSearch search = query -> {
            queries.add(query);
            return ContentSearchResult
                    .of(List.of(ContentSearchResult.FileMatches.of("Main.java", List.of(ContentSearchResult.Match.of(5,
                            "    public void authenticate(String user, String password) {", List.of(), List.of())))));
        };
        ToolContext searching = TestExecutionEnvironments
                .contextBuilder(
                        TestExecutionEnvironments.builder().fileSystem(fileSystem).contentSearch(search).build())
                .build();
        ToolResult delegated = grepTool.execute(ToolInput.of(input), searching);

        assertThat(queries).hasSize(1);
        assertThat(queries.get(0).getPattern()).isEqualTo("authenticate\\(String user");
        assertThat(queries.get(0).getPath()).isEqualTo(fileSystem.getWorkingDirectory());
        assertThat(walked.isSuccess()).isTrue();
        assertThat(delegated.getContent()).isEqualTo(walked.getContent());
    }

    @Test
    void testExecute_ContentSearchFails_FallsBackToTheWalk() {
        Map<String, Object> input = Map.of("pattern", "TODO", "output_mode", "count");
        ToolResult walked = grepTool.execute(ToolInput.of(input), context);
        ContentSearch broken = query -> {
            throw new IllegalStateException("rg crashed");
        };
        ToolContext searching = TestExecutionEnvironments
                .contextBuilder(
                        TestExecutionEnvironments.builder().fileSystem(fileSystem).contentSearch(broken).build())
                .build();

        ToolResult result = grepTool.execute(ToolInput.of(input), searching);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo(walked.getContent());
    }

    @Test
    void testExecute_ContentSearch_ReceivesTypeExtensionsAndContext() {
        List<ContentQuery> queries = new ArrayList<>();
        ContentSearch search = query -> {
            queries.add(query);
            return ContentSearchResult.of(List.of());
        };
        ToolContext searching = TestExecutionEnvironments
                .contextBuilder(
                        TestExecutionEnvironments.builder().fileSystem(fileSystem).contentSearch(search).build())
                .build();

        grepTool.execute(ToolInput.of(Map.of("pattern", "x", "type", "java", "-i", true, "-C", 2, "glob", "*.java")),
                searching);

        assertThat(queries).hasSize(1);
        ContentQuery query = queries.get(0);
        assertThat(query.getExtensions()).containsExactly(".java");
        assertThat(query.isCaseInsensitive()).isTrue();
        assertThat(query.getBeforeContext()).isEqualTo(2);
        assertThat(query.getAfterContext()).isEqualTo(2);
        assertThat(query.getGlob()).contains("*.java");
    }

    // getDefinition tests

    @Test
    void testGetDefinition_ReturnsCorrectName() {
        ToolDefinition definition = grepTool.getDefinition();
        assertThat(definition.getName()).isEqualTo("Grep");
    }

    @Test
    void testGetDefinition_ReturnsCorrectDescription() {
        ToolDefinition definition = grepTool.getDefinition();
        assertThat(definition.getDescription()).contains("search");
        assertThat(definition.getDescription()).contains("CRITICAL");
        assertThat(definition.getDescription()).contains("NEVER invoke");
    }

    @Test
    void testGetDefinition_HasRequiredPatternParameter() {
        ToolDefinition definition = grepTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();

        assertThat(schema.get("required")).asList().contains("pattern");
    }

    @Test
    void testGetDefinition_HasOptionalParameters() {
        ToolDefinition definition = grepTool.getDefinition();
        Map<String, Object> schema = definition.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("pattern", "path", "output_mode", "type", "glob", "-i", "-n", "-A", "-B",
                "-C", "multiline", "head_limit", "offset");
    }

    // execute tests - validation

    @Test
    void testExecute_MissingPattern_ReturnsError() {
        Map<String, Object> input = Map.of();

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isError()).isTrue();
        // The wording comes from ViolationMessages, which the executor's schema gate also speaks, so the model reads
        // the same sentence whichever of the two gates rejected the call.
        assertThat(result.getContent())
                .contains("Parameter 'pattern' is required (type: string). The tool was not executed.");
    }

    @Test
    void testExecute_UnknownParameter_ReturnsError() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "recursive", true);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Unknown parameter 'recursive'.");
    }

    @Test
    void testExecute_WrongParameterType_ReturnsError() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "-i", "yes");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("-i").contains("boolean");
    }

    @Test
    void testExecute_OutputModeOutsideEnum_ReturnsError() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "lines");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("output_mode").contains("content");
    }

    @Test
    void testExecute_EveryViolationReportedAtOnce() {
        Map<String, Object> input = Map.of("-i", "yes", "recursive", true);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("pattern").contains("-i").contains("recursive");
    }

    // execute tests - basic search

    @Test
    void testExecute_BasicSearch_FindsFiles() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString());

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Main.java");
        assertThat(result.getContent()).contains("Service.java");
        assertThat(result.getContent()).contains("test.py");
    }

    @Test
    void testExecute_NoMatches_ReturnsNoMatchesMessage() {
        Map<String, Object> input = Map.of("pattern", "nonexistent_pattern_xyz", "path", tempDir.toString());

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("No matches found");
    }

    // execute tests - output modes

    @Test
    void testExecute_ContentMode_ShowsMatchingLines() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "content");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("public void authenticate");
        assertThat(result.getContent()).contains("def authenticate");
    }

    @Test
    void testExecute_CountMode_ShowsCounts() {
        Map<String, Object> input = Map.of("pattern", "TODO", "path", tempDir.toString(), "output_mode", "count");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Main.java");
        assertThat(result.getContent()).contains("Service.java");
        // Contains counts
        assertThat(result.getContent()).matches("(?s).*\\d+.*");
    }

    // execute tests - file type filtering

    @Test
    void testExecute_FileTypeFilter_OnlySearchesSpecifiedType() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "type", "java");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Main.java");
        assertThat(result.getContent()).contains("Service.java");
        assertThat(result.getContent()).doesNotContain("test.py");
    }

    @Test
    void testExecute_GlobFilter_OnlySearchesMatchingFiles() {
        Map<String, Object> input = Map.of("pattern", "host", "path", tempDir.toString(), "glob", "*.yaml");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("config.yaml");
    }

    // execute tests - case sensitivity

    @Test
    void testExecute_CaseSensitive_DefaultBehavior() {
        Map<String, Object> input = Map.of("pattern", "TODO", "path", tempDir.toString(), "output_mode",
                "files_with_matches");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("java");
    }

    @Test
    void testExecute_CaseInsensitive_FindsAllCases() {
        Map<String, Object> input = Map.of("pattern", "class", "path", tempDir.toString(), "-i", true, "output_mode",
                "content");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).containsIgnoringCase("class");
    }

    // execute tests - line numbers

    @Test
    void testExecute_WithLineNumbers_ShowsLineNumbers() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "content", "-n", true);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        // Should contain line numbers (e.g., "5:", "6:")
        assertThat(result.getContent()).matches("(?s).*\\d+:.*");
    }

    @Test
    void testExecute_WithoutLineNumbers_NoLineNumbers() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "content", "-n", false);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("authenticate");
    }

    // execute tests - context lines

    @Test
    void testExecute_WithAfterContext_ShowsLinesAfter() {
        Map<String, Object> input = Map.of("pattern", "public void authenticate", "path", tempDir.toString(),
                "output_mode", "content", "-A", 2);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("authenticate");
        // Should have multiple lines shown
        assertThat(result.getContent().split("\n").length).isGreaterThan(2);
    }

    @Test
    void testExecute_WithBothContext_ShowsLinesBeforeAndAfter() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.resolve("Main.java").toString(),
                "output_mode", "content", "-C", 1);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("authenticate");
    }

    // execute tests - pagination

    @Test
    void testExecute_WithHeadLimit_LimitsResults() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "content", "head_limit", 2);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        // Should have limited number of lines
        int lineCount = result.getContent().split("\n").length;
        assertThat(lineCount).isLessThanOrEqualTo(2);
    }

    @Test
    void testExecute_WithOffset_SkipsFirstResults() {
        Map<String, Object> input = Map.of("pattern", "authenticate", "path", tempDir.toString(), "output_mode",
                "files_with_matches", "offset", 1, "head_limit", 2);

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        // Should skip first result
        String[] lines = result.getContent().split("\n");
        assertThat(lines.length).isLessThanOrEqualTo(2);
    }

    // execute tests - regex patterns

    @Test
    void testExecute_RegexPattern_FindsMatches() {
        Map<String, Object> input = Map.of("pattern", "public \\w+ \\w+", "path", tempDir.toString(), "output_mode",
                "content", "type", "java");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("public");
    }

    @Test
    void testExecute_WordBoundary_FindsWholeWords() {
        Map<String, Object> input = Map.of("pattern", "\\bclass\\b", "path", tempDir.toString(), "output_mode",
                "content", "type", "java");

        ToolResult result = grepTool.execute(ToolInput.of(input), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("class");
    }
}
