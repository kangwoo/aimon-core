package at.aimon.core.tools.wiki;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.knowledge.wiki.FiledAnswer;
import at.aimon.core.knowledge.wiki.IngestOptions;
import at.aimon.core.knowledge.wiki.IngestResult;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.knowledge.wiki.WikiPage;
import at.aimon.core.knowledge.wiki.WikiScope;
import at.aimon.core.knowledge.wiki.WikiSearchQuery;
import at.aimon.core.knowledge.wiki.WikiSource;
import at.aimon.core.knowledge.wiki.WikiStatus;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("WikiIngestTool Tests")
class WikiIngestToolTest {

    private WikiIngestTool tool;
    private StubWikiKnowledgeBase wikiKnowledgeBase;
    private StubVirtualFileSystem vfs;
    private ToolContext context;

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");

    @BeforeEach
    void setUp() {
        tool = new WikiIngestTool();
        wikiKnowledgeBase = new StubWikiKnowledgeBase();
        vfs = new StubVirtualFileSystem();
        context = ToolContext.builder().put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase)
                .put(ToolContextKeys.WIKI_SCOPE, SCOPE).put(ToolContextKeys.VIRTUAL_FILE_SYSTEM, vfs).build();
    }

    @Nested
    @DisplayName("Successful Ingest")
    class SuccessfulIngest {

        @Test
        @DisplayName("Returns formatted IngestResult on success")
        void returnsFormattedIngestResultOnSuccess() {
            wikiKnowledgeBase
                    .setResult(IngestResult.builder().ingestedCount(3).skippedCount(1).createdPageCount(3).build());

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw/articles"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Wiki ingest completed");
            assertThat(result.getContent()).contains("/raw/articles");
            assertThat(result.getContent()).contains("3");
        }

        @Test
        @DisplayName("Overwrite parameter is passed through")
        void overwriteParameterIsPassedThrough() {
            wikiKnowledgeBase.setResult(IngestResult.builder().ingestedCount(1).createdPageCount(1).build());

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw/docs", "overwrite", true),
                    context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(wikiKnowledgeBase.getLastOptions().isOverwrite()).isTrue();
        }

        @Test
        @DisplayName("File patterns parameter is passed through")
        void filePatternsParameterIsPassedThrough() {
            wikiKnowledgeBase.setResult(IngestResult.builder().build());

            tool.execute(ToolInput.of("source_directory", "/raw/docs", "file_patterns", "*.md"), context);

            assertThat(wikiKnowledgeBase.getLastOptions().getFilePatterns()).containsExactly("*.md");
        }

        @Test
        @DisplayName("Errors in IngestResult are included in output")
        void errorsInIngestResultAreIncluded() {
            wikiKnowledgeBase.setResult(
                    IngestResult.builder().ingestedCount(0).errors(List.of("file.txt: parse error")).build());

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw"), context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("Errors");
            assertThat(result.getContent()).contains("parse error");
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @Test
        @DisplayName("No wiki knowledge base in context returns error")
        void noWikiKnowledgeBaseReturnsError() {
            final ToolContext noWikiContext = ToolContext.builder().put(ToolContextKeys.WIKI_SCOPE, SCOPE)
                    .put(ToolContextKeys.VIRTUAL_FILE_SYSTEM, vfs).build();

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw"), noWikiContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki knowledge base configured");
        }

        @Test
        @DisplayName("No wiki scope in context returns error")
        void noWikiScopeReturnsError() {
            final ToolContext noScopeContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase)
                    .put(ToolContextKeys.VIRTUAL_FILE_SYSTEM, vfs).build();

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw"), noScopeContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No wiki scope configured");
        }

        @Test
        @DisplayName("No VirtualFileSystem in context returns error")
        void noVirtualFileSystemReturnsError() {
            final ToolContext noVfsContext = ToolContext.builder()
                    .put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wikiKnowledgeBase).put(ToolContextKeys.WIKI_SCOPE, SCOPE)
                    .build();

            final ToolResult result = tool.execute(ToolInput.of("source_directory", "/raw"), noVfsContext);

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("No virtual file system configured");
        }

        @Test
        @DisplayName("Missing source_directory parameter returns error")
        void missingSourceDirectoryReturnsError() {
            final ToolResult result = tool.execute(ToolInput.of(), context);

            assertThat(result.isError()).isTrue();
        }
    }

    @Nested
    @DisplayName("Tool Definition")
    class ToolDefinition {

        @Test
        @DisplayName("Tool has correct name")
        void toolHasCorrectName() {
            assertThat(tool.getDefinition().getName()).isEqualTo("WikiIngest");
        }

        @Test
        @DisplayName("Tool has non-empty description")
        void toolHasNonEmptyDescription() {
            assertThat(tool.getDefinition().getDescription()).isNotEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Stubs
    // -------------------------------------------------------------------------

    static class StubWikiKnowledgeBase implements WikiKnowledgeBase {

        private IngestResult result = IngestResult.builder().build();
        private IngestOptions lastOptions;

        void setResult(IngestResult result) {
            this.result = result;
        }

        IngestOptions getLastOptions() {
            return lastOptions;
        }

        @Override
        public IngestResult ingest(WikiScope scope, WikiSource source, IngestOptions options) {
            this.lastOptions = options;
            return result;
        }

        @Override
        public List<WikiPage> search(WikiScope scope, WikiSearchQuery query) {
            return Collections.emptyList();
        }

        @Override
        public Optional<WikiPage> getPage(WikiScope scope, String pagePath) {
            return Optional.empty();
        }

        @Override
        public WikiStatus getStatus(WikiScope scope) {
            return WikiStatus.builder().state(WikiStatus.State.EMPTY).build();
        }

        @Override
        public WikiPage fileAnswer(WikiScope scope, FiledAnswer answer) {
            return WikiPage.builder().path("/wiki/stub.md").title(answer.getTitle()).content(answer.getContent())
                    .build();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    static class StubVirtualFileSystem implements VirtualFileSystem {

        private final Map<String, String> files = new HashMap<>();

        @Override
        public void write(String path, InputStream content, long contentLength) {
            try {
                files.put(path, new String(content.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream read(String path) {
            final String content = files.get(path);
            if (content == null) {
                throw new at.aimon.core.filesystem.exception.FileNotFoundException("Not found: " + path);
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(path);
        }

        @Override
        public boolean isDirectory(String path) {
            final String prefix = path.endsWith("/") ? path : path + "/";
            return files.keySet().stream().anyMatch(p -> p.startsWith(prefix));
        }

        @Override
        public FileMetadata getMetadata(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> list(String directory) {
            final String prefix = directory.endsWith("/") ? directory : directory + "/";
            final List<String> result = new ArrayList<>();
            for (final String p : files.keySet()) {
                if (p.startsWith(prefix)) {
                    result.add(p);
                }
            }
            return result;
        }

        @Override
        public List<String> listRecursive(String directory) {
            return list(directory);
        }

        @Override
        public void copy(String src, String dst, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(String src, String dst, boolean overwrite) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream openOutputStream(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream openInputStream(String path) {
            return read(path);
        }

        @Override
        public String getWorkingDirectory() {
            return "/";
        }

        @Override
        public void initialize() {
        }

        @Override
        public BackendStatus getStatus() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
