package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ContentQuery;
import at.aimon.core.environment.ContentSearchResult;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.tools.file.GrepTool;

@DisplayName("RipgrepContentSearch — parity with Grep's own filesystem walk")
class RipgrepContentSearchTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fs;
    private RipgrepContentSearch search;

    @BeforeEach
    void setUp() {
        final Optional<Path> rg = RipgrepContentSearch.probe();
        assumeTrue(rg.isPresent(), "rg is not on the PATH");
        fs = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fs.initialize();
        fs.write("src/Main.java", "class Main {\n  // TODO fix\n  void run() {}\n}\n");
        fs.write("src/util/Helper.java", "class Helper {\n  // todo later\n}\n");
        fs.write("docs/readme.md", "Intro\nTODO: write docs\nmore\nlines\nTODO again\n");
        fs.write("scripts/run.sh", "#!/bin/sh\necho TODO\n");
        fs.write(".hidden/notes.txt", "hidden TODO\n");
        fs.write(".aimon/skills/s/SKILL.md", "control TODO\n");
        search = new RipgrepContentSearch(rg.get(), Path.of(fs.getWorkingDirectory()), List.of(".aimon"));
    }

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.close();
        }
    }

    private String grep(ExecutionEnvironment env, Map<String, Object> input) {
        final ToolResult result = new GrepTool().execute(ToolInput.of(input),
                TestExecutionEnvironments.contextBuilder(env).build());
        return (result.isSuccess() ? "OK:" : "ERR:") + result.getContent();
    }

    @Test
    @DisplayName("every query prints the same text through rg and through the walk")
    void parity() {
        final ExecutionEnvironment walk = TestExecutionEnvironments.builder().fileSystem(fs).build();
        final ExecutionEnvironment withRg = TestExecutionEnvironments.builder().fileSystem(fs).contentSearch(search)
                .build();
        // The walk sees .aimon/ (no path rules here), so leave it out of the comparison by searching sub-paths or
        // patterns it does not contain.
        fs.deleteRecursive(".aimon");
        final List<Map<String, Object>> queries = List.of(Map.of("pattern", "TODO"),
                Map.of("pattern", "TODO", "output_mode", "content"),
                Map.of("pattern", "TODO", "output_mode", "content", "-n", false),
                Map.of("pattern", "TODO", "output_mode", "content", "-A", 1),
                Map.of("pattern", "TODO", "output_mode", "content", "-B", 2),
                Map.of("pattern", "TODO", "output_mode", "content", "-C", 1),
                Map.of("pattern", "TODO", "output_mode", "count"), Map.of("pattern", "todo", "-i", true),
                Map.of("pattern", "class", "type", "java"), Map.of("pattern", "TODO", "glob", "*.md"),
                Map.of("pattern", "TODO", "path", "src"),
                Map.of("pattern", "TODO", "output_mode", "content", "head_limit", 2, "offset", 1),
                Map.of("pattern", "no-such-text"));
        for (Map<String, Object> query : queries) {
            assertThat(grep(withRg, query)).as(query.toString()).isEqualTo(grep(walk, query));
        }
    }

    @Test
    @DisplayName("a hidden (DENY) prefix is never returned")
    void denyPrefixExcluded() {
        final ContentSearchResult result = search
                .search(ContentQuery.builder().pattern("TODO").path(fs.getWorkingDirectory()).build());
        assertThat(result.getFiles()).extracting(ContentSearchResult.FileMatches::getPath)
                .contains(".hidden/notes.txt", "src/Main.java").noneMatch(p -> p.startsWith(".aimon"));
    }

    @Test
    @DisplayName("a multiline query is left to the walk")
    void multilineRefused() {
        assertThatThrownBy(() -> search.search(ContentQuery.builder().pattern("a.b").path(".").multiline(true).build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a path outside the root is refused")
    void outsideRootRefused() {
        assertThatThrownBy(
                () -> search.search(ContentQuery.builder().pattern("x").path("/definitely/elsewhere").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
