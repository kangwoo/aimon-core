package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.filesystem.VirtualFileSystem;

@DisplayName("DefaultWikiKnowledgeBase.answer (Query Improvement 3)")
class DefaultWikiKnowledgeBaseAnswerTest {

    private static final WikiScope SCOPE = new WikiScope("ops-agent", "ctx-1", "runbook");
    private static final String WIKI_ROOT = "/wiki";

    private DefaultWikiKnowledgeBaseTest.StubFileSystem wikiVfs;
    private RecordingAnswerStrategy strategy;
    private DefaultWikiKnowledgeBase wiki;

    @BeforeEach
    void setUp() {
        wikiVfs = new DefaultWikiKnowledgeBaseTest.StubFileSystem();
        strategy = new RecordingAnswerStrategy();
        wiki = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator(),
                new at.aimon.core.knowledge.wiki.IndexFirstSearchStrategy(), null, null, strategy);
        seedPages();
    }

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

    private String pagesDir() {
        return WIKI_ROOT + "/" + SCOPE.getAgentName() + "/" + SCOPE.getContextId() + "/" + SCOPE.getWikiName()
                + "/pages/";
    }

    private void seedPages() {
        wikiVfs.addFile(pagesDir() + "entity-pod.md",
                "---\ntitle: Pod\ntype: entity\ntags: [k8s]\n---\n\n# Pod\n\nA kubernetes pod.");
        wikiVfs.addFile(pagesDir() + "entity-service.md",
                "---\ntitle: Service\ntype: entity\ntags: [k8s]\n---\n\n# Service\n\nA kubernetes service.");
    }

    @Nested
    @DisplayName("strategy gating")
    class StrategyGating {

        @Test
        @DisplayName("answer throws UnsupportedOperationException when no strategy is wired")
        void noStrategyWired() {
            DefaultWikiKnowledgeBase wikiNoStrategy = new DefaultWikiKnowledgeBase(locator(wikiVfs, WIKI_ROOT),
                    new DefaultWikiKnowledgeBaseTest.StubWikiPageGenerator());

            AnswerRequest req = AnswerRequest.builder().question("How?").build();

            assertThatThrownBy(() -> wikiNoStrategy.answer(SCOPE, req))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("answer searches, caps context, and routes through the strategy")
        void searchesAndRoutes() {
            AnswerRequest req = AnswerRequest.builder().question("kubernetes").maxContextPages(5).build();

            Answer answer = wiki.answer(SCOPE, req);

            assertThat(strategy.callCount.get()).isEqualTo(1);
            assertThat(strategy.lastContextPages).hasSize(2);
            assertThat(answer.getText()).isEqualTo("synthesized answer");
            assertThat(answer.getSourceRefs()).hasSize(2);
        }

        @Test
        @DisplayName("maxContextPages caps the context handed to the strategy")
        void maxContextPagesCaps() {
            AnswerRequest req = AnswerRequest.builder().question("kubernetes").maxContextPages(1).build();

            wiki.answer(SCOPE, req);

            assertThat(strategy.lastContextPages).hasSize(1);
        }
    }

    @Nested
    @DisplayName("strategy contract violations")
    class ContractViolations {

        @Test
        @DisplayName("strategy returning null wraps as IllegalStateException")
        void nullResultWrapsAsIse() {
            strategy.returnNullNext = true;

            AnswerRequest req = AnswerRequest.builder().question("kubernetes").build();

            assertThatThrownBy(() -> wiki.answer(SCOPE, req)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("returned null");
        }

        @Test
        @DisplayName("strategy throwing wraps as IllegalStateException")
        void throwingWrapsAsIse() {
            strategy.throwOnNext = new RuntimeException("boom");

            AnswerRequest req = AnswerRequest.builder().question("kubernetes").build();

            assertThatThrownBy(() -> wiki.answer(SCOPE, req)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("boom");
        }
    }

    @Nested
    @DisplayName("toFiledAnswer round-trip")
    class FiledAnswerRoundTrip {

        @Test
        @DisplayName("answer().toFiledAnswer() can be filed back via fileAnswer()")
        void roundTripsToFiled() {
            AnswerRequest req = AnswerRequest.builder().question("kubernetes").build();

            Answer answer = wiki.answer(SCOPE, req);
            WikiPage filed = wiki.fileAnswer(SCOPE, answer.toFiledAnswer());

            assertThat(filed.getPath()).contains("answer-");
            assertThat(filed.getContent()).contains("synthesized answer");
        }
    }

    /**
     * Recording strategy that captures invocations and returns a deterministic Answer. Lets the storage tests
     * assert on routing/capping without involving an LLM.
     */
    private static final class RecordingAnswerStrategy implements WikiAnswerStrategy {

        final AtomicInteger callCount = new AtomicInteger(0);
        List<WikiPage> lastContextPages = List.of();
        boolean returnNullNext;
        RuntimeException throwOnNext;

        @Override
        public Answer answer(WikiScope scope, AnswerRequest request, List<WikiPage> contextPages) {
            callCount.incrementAndGet();
            lastContextPages = List.copyOf(contextPages);
            if (throwOnNext != null) {
                final RuntimeException e = throwOnNext;
                throwOnNext = null;
                throw e;
            }
            if (returnNullNext) {
                returnNullNext = false;
                return null;
            }
            final List<String> refs = new java.util.ArrayList<>();
            for (WikiPage page : contextPages) {
                refs.add(page.getPath());
            }
            return Answer.builder().question(request.getQuestion()).title("Answer to " + request.getQuestion())
                    .text("synthesized answer").sourceRefs(refs).llmCallCount(1).build();
        }
    }
}
