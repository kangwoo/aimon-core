package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.wiki.WikiIngestTool;

/**
 * {@code WikiIngest} against the real {@link DefaultWikiKnowledgeBase} in an unavailable environment (review 2,
 * blocking 2). The knowledge base treats a failed source listing as "nothing to ingest", so without the tool's probe
 * the model was told "Documents ingested : 0" instead of why nothing could be read.
 */
@DisplayName("WikiIngest in an unavailable environment")
class WikiIngestUnavailableEnvironmentTest {

    private static final WikiScope SCOPE = new WikiScope("agent", "ctx", "wiki");

    @Test
    @DisplayName("is an error that carries the cause, not an empty successful ingest")
    void unavailableIsAnError() {
        final DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        final DefaultWikiKnowledgeBase wiki = DefaultWikiKnowledgeBase.builder().locator(new WikiStorageLocator() {
            @Override
            public VirtualFileSystem fileSystemFor(WikiScope scope) {
                return wikiVfs;
            }

            @Override
            public String directoryFor(WikiScope scope) {
                return "/wiki";
            }
        }).pageGenerator(new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator()).build();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.WIKI_KNOWLEDGE_BASE, wiki)
                .put(ToolContextKeys.WIKI_SCOPE, SCOPE)
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("sandbox is down"))
                .build();

        final ToolResult result = new WikiIngestTool().execute(ToolInput.of("source_directory", "/raw"), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("sandbox is down").doesNotContain("Documents ingested");
    }
}
