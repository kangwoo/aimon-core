package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase.Builder")
class DefaultWikiKnowledgeBaseBuilderTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");
    private static final String WIKI_ROOT = "/wiki";

    private static WikiStorageLocator locator(VirtualFileSystem fs, String root) {
        return new WikiStorageLocator() {
            @Override
            public VirtualFileSystem fileSystemFor(WikiScope scope) {
                return fs;
            }

            @Override
            public String directoryFor(WikiScope scope) {
                return root + "/" + scope.getAgentName() + "/" + scope.getContextId() + "/" + scope.getWikiName();
            }
        };
    }

    @Test
    @DisplayName("minimal builder — only required fields — works for ingest and search")
    void minimalBuilderRoundTrips() {
        DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs.addFile("/raw/doc.md", "# Doc\nBody.");

        DefaultWikiKnowledgeBase wiki = DefaultWikiKnowledgeBase.builder().locator(locator(wikiVfs, WIKI_ROOT))
                .pageGenerator(new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator()).build();

        IngestResult result = wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());

        assertThat(result.getIngestedCount()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("builder without locator throws NullPointerException at build time")
    void missingLocatorThrows() {
        assertThatThrownBy(() -> DefaultWikiKnowledgeBase.builder()
                .pageGenerator(new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator()).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("builder without pageGenerator throws NullPointerException at build time")
    void missingPageGeneratorThrows() {
        DefaultWikiKnowledgeBaseTest.StubFileSystem fs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        assertThatThrownBy(() -> DefaultWikiKnowledgeBase.builder().locator(locator(fs, WIKI_ROOT)).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("explicit searchStrategy override is used instead of the default IndexFirst")
    void searchStrategyOverride() {
        DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        DefaultWikiKnowledgeBaseTest.StubFileSystem sourceVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        sourceVfs.addFile("/raw/doc.md", "# Doc\nkubernetes body");

        DefaultWikiKnowledgeBase wiki = DefaultWikiKnowledgeBase.builder().locator(locator(wikiVfs, WIKI_ROOT))
                .pageGenerator(new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator())
                .searchStrategy(new FullScanSearchStrategy()).build();

        wiki.ingest(SCOPE, new WikiSource(sourceVfs, "/raw"), IngestOptions.defaults());

        // Full scan has no index dependency; with no index.md ever written, it still finds the page.
        assertThat(wiki.search(SCOPE,
                at.aimon.core.knowledge.wiki.WikiSearchQuery.builder().queryText("kubernetes").build())).hasSize(1);
    }

    @Test
    @DisplayName("optional strategies default to null and the corresponding APIs throw UnsupportedOperationException")
    void optionalStrategiesDefaultNull() {
        DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();

        DefaultWikiKnowledgeBase wiki = DefaultWikiKnowledgeBase.builder().locator(locator(wikiVfs, WIKI_ROOT))
                .pageGenerator(new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator()).build();

        assertThatThrownBy(() -> wiki.answer(SCOPE, AnswerRequest.builder().question("?").build()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> wiki.synthesize(SCOPE, SynthesizeOptions.defaults()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
