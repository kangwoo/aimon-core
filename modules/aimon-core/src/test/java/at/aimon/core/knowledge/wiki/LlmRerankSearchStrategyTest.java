package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;

@DisplayName("LlmRerankSearchStrategy Tests")
class LlmRerankSearchStrategyTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String SCOPE_DIR = "/wiki/ops-agent/ctx-1/runbook/";
    private static final String PAGES_DIR = SCOPE_DIR + "pages/";
    private static final String INDEX_PATH = SCOPE_DIR + "index.md";

    private StubFileSystem fs;
    private StubLlmClient llm;
    private RecordingFallback fallback;
    private LlmRerankSearchStrategy strategy;

    @BeforeEach
    void setUp() {
        fs = new StubFileSystem();
        llm = new StubLlmClient();
        fallback = new RecordingFallback();
        strategy = LlmRerankSearchStrategy.builder().llmClient(llm).fallback(fallback).build();
    }

    private WikiSearchContext ctx() {
        return WikiSearchContext.builder().fileSystem(fs).scope(SCOPE).scopeDirectory(SCOPE_DIR)
                .pagesDirectory(PAGES_DIR).indexPath(INDEX_PATH).build();
    }

    private WikiSearchQuery query(String text) {
        return WikiSearchQuery.builder().queryText(text).maxResults(3).build();
    }

    private static String pageBody(String title, String tags, String body) {
        return "---\ntitle: " + title + "\ntags: [" + tags + "]\nsource: src\n---\n\n# " + title + "\n\n" + body + "\n";
    }

    /**
     * Seeds an index with N entries and corresponding page files. Paths are {@code PAGES_DIR + "page-" + i +
     * ".md"}. Returns the list of paths in insertion order.
     */
    private List<String> seedPages(int count) {
        final StringBuilder index = new StringBuilder("# Wiki Index\n\n");
        final List<String> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final String path = PAGES_DIR + "page-" + i + ".md";
            final String title = "Page " + i;
            final String summary = "Summary of page " + i;
            fs.addFile(path, pageBody(title, "tagA", "Body content for page " + i));
            index.append("- [").append(title).append("](").append(path).append(") — ").append(summary)
                    .append(" {tagA}\n");
            paths.add(path);
        }
        fs.addFile(INDEX_PATH, index.toString());
        return paths;
    }

    // =========================================================================
    // Fallback delegation paths
    // =========================================================================

    @Nested
    @DisplayName("Fallback delegation")
    class FallbackDelegation {

        @Test
        @DisplayName("missing index.md delegates to fallback without calling LLM")
        void missingIndexFallsBack() {
            // fs is empty, no index.md
            final List<WikiPage> result = strategy.search(query("anything"), ctx());

            assertThat(fallback.callCount).isEqualTo(1);
            assertThat(llm.callCount).isZero();
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("unparseable index.md (no entries) delegates to fallback")
        void emptyParsedIndexFallsBack() {
            fs.addFile(INDEX_PATH, "# Wiki Index\n\n(no entries)\n");

            strategy.search(query("anything"), ctx());

            assertThat(fallback.callCount).isEqualTo(1);
            assertThat(llm.callCount).isZero();
        }

        @Test
        @DisplayName("filtered index larger than maxIndexEntries delegates to fallback without calling LLM")
        void oversizedIndexFallsBack() {
            seedPages(10);
            final LlmRerankSearchStrategy small = LlmRerankSearchStrategy.builder().llmClient(llm).fallback(fallback)
                    .maxIndexEntries(5).build();

            small.search(query("anything"), ctx());

            assertThat(fallback.callCount).isEqualTo(1);
            assertThat(llm.callCount).isZero();
        }

        @Test
        @DisplayName("LLM exception delegates to fallback")
        void llmExceptionFallsBack() {
            seedPages(20);
            llm.throwOnNextCall = new RuntimeException("boom");

            strategy.search(query("anything"), ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(fallback.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("empty LLM response delegates to fallback")
        void emptyLlmResponseFallsBack() {
            seedPages(20);
            llm.nextResponse = LlmResponse.text("");

            strategy.search(query("anything"), ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(fallback.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("LLM response mentioning no candidate paths delegates to fallback")
        void llmHallucinatedOnlyFallsBack() {
            seedPages(20);
            llm.nextResponse = LlmResponse.text("{\"paths\": [\"/fake/ghost.md\"]}");

            strategy.search(query("anything"), ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(fallback.callCount).isEqualTo(1);
        }
    }

    // =========================================================================
    // Explicit filter stage
    // =========================================================================

    @Nested
    @DisplayName("Explicit filters (path glob, tags)")
    class ExplicitFilters {

        @Test
        @DisplayName("path glob filter restricts the candidate set passed to the LLM")
        void pathGlobFilter() {
            // Two different path prefixes
            fs.addFile(PAGES_DIR + "alpha-1.md", pageBody("Alpha 1", "a", "alpha body"));
            fs.addFile(PAGES_DIR + "alpha-2.md", pageBody("Alpha 2", "a", "alpha body"));
            fs.addFile(PAGES_DIR + "beta-1.md", pageBody("Beta 1", "b", "beta body"));
            fs.addFile(PAGES_DIR + "beta-2.md", pageBody("Beta 2", "b", "beta body"));
            fs.addFile(INDEX_PATH,
                    "# Wiki Index\n\n" + "- [Alpha 1](" + PAGES_DIR + "alpha-1.md) — alpha {a}\n" + "- [Alpha 2]("
                            + PAGES_DIR + "alpha-2.md) — alpha {a}\n" + "- [Beta 1](" + PAGES_DIR
                            + "beta-1.md) — beta {b}\n" + "- [Beta 2](" + PAGES_DIR + "beta-2.md) — beta {b}\n");

            // Arrange: LLM picks whatever is first in its input so we can inspect what was actually sent.
            // maxResults(1) forces the LLM rerank to run; otherwise a 2-entry filtered set would be <=
            // maxResults and the strategy would skip the LLM call.
            llm.nextResponse = LlmResponse
                    .text("{\"paths\": [\"" + PAGES_DIR + "alpha-1.md\", \"" + PAGES_DIR + "alpha-2.md\"]}");

            final WikiSearchQuery q = WikiSearchQuery.builder().queryText("hello").maxResults(1)
                    .pagePathPatterns(List.of("alpha-*.md")).build();

            strategy.search(q, ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(llm.lastUserPrompt).contains("alpha-1.md").contains("alpha-2.md");
            assertThat(llm.lastUserPrompt).doesNotContain("beta-1.md").doesNotContain("beta-2.md");
        }

        @Test
        @DisplayName("tag filter restricts the candidate set passed to the LLM")
        void tagFilter() {
            // Two k8s entries + one db entry. maxResults(1) forces the LLM rerank to run on the
            // 2-entry filtered set (otherwise filtered <= maxResults would skip the LLM call).
            fs.addFile(PAGES_DIR + "k8s-pods.md", pageBody("K8s pods", "kubernetes, ops", "k8s pod info"));
            fs.addFile(PAGES_DIR + "k8s-svc.md", pageBody("K8s services", "kubernetes, ops", "k8s service info"));
            fs.addFile(PAGES_DIR + "db.md", pageBody("DB", "database, ops", "db"));
            fs.addFile(INDEX_PATH,
                    "# Wiki Index\n\n" + "- [K8s pods](" + PAGES_DIR + "k8s-pods.md) — pods {kubernetes, ops}\n"
                            + "- [K8s services](" + PAGES_DIR + "k8s-svc.md) — services {kubernetes, ops}\n" + "- [DB]("
                            + PAGES_DIR + "db.md) — queries {database, ops}\n");

            llm.nextResponse = LlmResponse.text("{\"paths\": [\"" + PAGES_DIR + "k8s-pods.md\"]}");

            final WikiSearchQuery q = WikiSearchQuery.builder().queryText("outage").maxResults(1)
                    .tags(List.of("kubernetes")).build();

            strategy.search(q, ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(llm.lastUserPrompt).contains("k8s-pods.md").contains("k8s-svc.md").doesNotContain("db.md");
        }

        @Test
        @DisplayName("filter producing empty candidate set returns empty without calling LLM or fallback")
        void filterEmptyReturnsEmpty() {
            seedPages(3); // all tagged {tagA}

            final WikiSearchQuery q = WikiSearchQuery.builder().queryText("q").maxResults(3)
                    .tags(List.of("nonexistent-tag")).build();

            final List<WikiPage> result = strategy.search(q, ctx());

            assertThat(result).isEmpty();
            assertThat(llm.callCount).isZero();
            assertThat(fallback.callCount).isZero();
        }
    }

    // =========================================================================
    // LLM rerank + drill-down happy paths
    // =========================================================================

    @Nested
    @DisplayName("LLM rerank + drill-down")
    class RerankAndDrill {

        @Test
        @DisplayName("returns pages in the order picked by the LLM")
        void happyPath() {
            final List<String> paths = seedPages(10);
            // LLM returns picks in non-index order: 5, 2, 7
            llm.nextResponse = LlmResponse.text(
                    "{\"paths\": [\"" + paths.get(5) + "\", \"" + paths.get(2) + "\", \"" + paths.get(7) + "\"]}");

            final List<WikiPage> result = strategy.search(query("q"), ctx());

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getPath()).isEqualTo(paths.get(5));
            assertThat(result.get(1).getPath()).isEqualTo(paths.get(2));
            assertThat(result.get(2).getPath()).isEqualTo(paths.get(7));
        }

        @Test
        @DisplayName("parser tolerates markdown code fences and prose around the JSON")
        void lenientParsing() {
            final List<String> paths = seedPages(10);
            llm.nextResponse = LlmResponse
                    .text("Sure! Here are the most relevant pages:\n\n" + "```json\n" + "{\"paths\": [\"" + paths.get(3)
                            + "\", \"" + paths.get(1) + "\"]}\n" + "```\n" + "Hope this helps!");

            final List<WikiPage> result = strategy.search(query("q"), ctx());

            assertThat(result).extracting(WikiPage::getPath).containsExactly(paths.get(3), paths.get(1));
        }

        @Test
        @DisplayName("hallucinated paths are dropped while real picks are retained")
        void hallucinatedPathsFiltered() {
            final List<String> paths = seedPages(10);
            llm.nextResponse = LlmResponse.text(
                    "{\"paths\": [\"/wiki/does-not-exist.md\", \"" + paths.get(4) + "\", \"" + paths.get(6) + "\"]}");

            final List<WikiPage> result = strategy.search(query("q"), ctx());

            assertThat(result).extracting(WikiPage::getPath).containsExactly(paths.get(4), paths.get(6));
        }

        @Test
        @DisplayName("stale picks (file deleted since index write) are skipped silently")
        void stalePickSkipped() {
            final List<String> paths = seedPages(5);
            // Delete page-2 from the VFS while leaving it in the index
            fs.delete(paths.get(2));

            llm.nextResponse = LlmResponse.text("{\"paths\": [\"" + paths.get(2) + "\", \"" + paths.get(3) + "\"]}");

            final List<WikiPage> result = strategy.search(query("q"), ctx());

            assertThat(result).extracting(WikiPage::getPath).containsExactly(paths.get(3));
        }

        @Test
        @DisplayName("result is capped at maxResults even if LLM returns more")
        void maxResultsHonoured() {
            final List<String> paths = seedPages(10);
            final StringBuilder json = new StringBuilder("{\"paths\": [");
            for (int i = 0; i < paths.size(); i++) {
                if (i > 0) {
                    json.append(", ");
                }
                json.append('"').append(paths.get(i)).append('"');
            }
            json.append("]}");
            llm.nextResponse = LlmResponse.text(json.toString());

            final WikiSearchQuery q = WikiSearchQuery.builder().queryText("q").maxResults(3).build();
            final List<WikiPage> result = strategy.search(q, ctx());

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("filtered size <= maxResults skips LLM and drills down directly")
        void skipsLlmWhenTrivial() {
            seedPages(3);
            final WikiSearchQuery q = WikiSearchQuery.builder().queryText("q").maxResults(5).build();

            final List<WikiPage> result = strategy.search(q, ctx());

            assertThat(result).hasSize(3);
            assertThat(llm.callCount).isZero();
            assertThat(fallback.callCount).isZero();
        }

        @Test
        @DisplayName("LLM call uses no tools and targets the configured model config")
        void llmCalledWithNoToolsAndConfiguredModel() {
            seedPages(10);
            final LlmModel model = LlmModel.builder().name("test-model").build();
            final LlmRerankSearchStrategy configured = LlmRerankSearchStrategy.builder().llmClient(llm)
                    .fallback(fallback).modelConfig(model).build();
            final List<String> paths = List.of(PAGES_DIR + "page-0.md");
            llm.nextResponse = LlmResponse.text("{\"paths\": [\"" + paths.get(0) + "\"]}");

            configured.search(query("q"), ctx());

            assertThat(llm.callCount).isEqualTo(1);
            assertThat(llm.lastTools).isEmpty();
            assertThat(llm.lastModelConfig).isSameAs(model);
            assertThat(llm.lastSystemPrompt).contains("relevance ranker");
        }

        @Test
        @DisplayName("default LlmCallMetadata 는 component=wiki-rerank, feature=search 로 attribution 되고 WikiScope 가 tags 로 추가된다")
        void defaultMetadataAttribution() {
            seedPages(10);
            llm.nextResponse = LlmResponse.text("{\"paths\": [\"" + PAGES_DIR + "page-0.md\"]}");

            strategy.search(query("q"), ctx());

            assertThat(llm.lastMetadata).isNotNull();
            assertThat(llm.lastMetadata.getComponent()).contains("wiki-rerank");
            assertThat(llm.lastMetadata.getFeature()).contains("search");
            assertThat(llm.lastMetadata.getTags()).containsEntry("wiki.agent", SCOPE.getAgentName())
                    .containsEntry("wiki.context", SCOPE.getContextId())
                    .containsEntry("wiki.name", SCOPE.getWikiName());
        }

        @Test
        @DisplayName("builder 로 지정한 metadata 는 caller-supplied 필드가 우선되고 미설정 필드는 기본값으로 채워진다")
        void builderMetadataMergesWithDefaults() {
            seedPages(10);
            llm.nextResponse = LlmResponse.text("{\"paths\": [\"" + PAGES_DIR + "page-0.md\"]}");

            final LlmRerankSearchStrategy custom = LlmRerankSearchStrategy.builder().llmClient(llm).fallback(fallback)
                    .llmCallMetadata(LlmCallMetadata.builder().feature("custom-rerank").traceId("trace-zz").build())
                    .build();

            custom.search(query("q"), ctx());

            assertThat(llm.lastMetadata.getFeature()).contains("custom-rerank");
            assertThat(llm.lastMetadata.getTraceId()).contains("trace-zz");
            assertThat(llm.lastMetadata.getComponent()).contains("wiki-rerank");
        }
    }

    // =========================================================================
    // Builder validation
    // =========================================================================

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("missing llmClient throws NullPointerException on build()")
        void requiresLlmClient() {
            assertThatThrownBy(() -> LlmRerankSearchStrategy.builder().build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("llmClient");
        }

        @Test
        @DisplayName("non-positive maxIndexEntries throws IllegalArgumentException")
        void rejectsNonPositiveMaxIndexEntries() {
            final LlmRerankSearchStrategy.Builder b = LlmRerankSearchStrategy.builder().llmClient(llm);
            assertThatThrownBy(() -> b.maxIndexEntries(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> b.maxIndexEntries(-5)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null query or ctx throws NullPointerException")
        void rejectsNullArgs() {
            assertThatThrownBy(() -> strategy.search(null, ctx())).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> strategy.search(query("q"), null)).isInstanceOf(NullPointerException.class);
        }
    }

    // =========================================================================
    // Stubs
    // =========================================================================

    private static final class StubLlmClient implements LlmClient {

        int callCount;
        String lastSystemPrompt;
        String lastUserPrompt;
        List<ToolDefinition> lastTools;
        LlmModel lastModelConfig;
        LlmCallMetadata lastMetadata;

        LlmResponse nextResponse = LlmResponse.text("{\"paths\": []}");
        RuntimeException throwOnNextCall;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            callCount++;
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = messages.isEmpty() ? "" : messages.get(0).getContent();
            lastTools = tools;
            lastModelConfig = modelConfig;
            lastMetadata = metadata;
            if (throwOnNextCall != null) {
                final RuntimeException e = throwOnNextCall;
                throwOnNextCall = null;
                throw e;
            }
            return nextResponse;
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    }

    private static final class RecordingFallback implements WikiSearchStrategy {

        int callCount;
        final AtomicInteger lastMaxResults = new AtomicInteger();

        @Override
        public List<WikiPage> search(WikiSearchQuery query, WikiSearchContext context) {
            callCount++;
            lastMaxResults.set(query.getMaxResults());
            return List.of();
        }
    }

    static final class StubFileSystem implements VirtualFileSystem {

        private final Map<String, String> files = new HashMap<>();

        void addFile(String path, String content) {
            files.put(path, content);
        }

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
            return files.containsKey(path) || isDirectory(path);
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
            final List<String> out = new ArrayList<>();
            for (String p : files.keySet()) {
                if (p.startsWith(prefix)) {
                    out.add(p);
                }
            }
            return out;
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
